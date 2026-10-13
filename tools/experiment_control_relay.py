#!/usr/bin/env python3
"""Bounded HTTPS adapter for opaque app-owned signed experiment commands.

Operator-provisioned private scopes only; no enrollment, platform effects, or
command authority. Never emit credentials or message contents to logs.
"""
from __future__ import annotations
import argparse
from collections import deque
from dataclasses import dataclass, field
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import secrets
import ssl
import threading
import time
from pathlib import Path

MAX_BODY = 65536
SCOPE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
TOKEN = re.compile(r"^[A-Za-z0-9_-]{32,128}$")

def consume_scope_file(path):
    """A process restart must not resurrect a revoked/previously used session.

    Keep the marker with the private config; a fresh scope uses a fresh filename
    and fresh random session/tokens. Startup failure consumes the scope safely.
    """
    marker = Path(str(path) + ".used")
    descriptor = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write("single-use relay scope consumed\n")
        stream.flush()
        os.fsync(stream.fileno())
    if os.name != "nt":
        directory = os.open(marker.parent, os.O_RDONLY)
        try: os.fsync(directory)
        finally: os.close(directory)

class Rejected(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code

@dataclass
class Job:
    request: dict
    expires: float
    event: threading.Event = field(default_factory=threading.Event)
    response: dict | None = None
    delivered: bool = False

class Session:
    def __init__(self, config):
        self.session = config["session"]
        self.device_token = config["device_token"]
        self.browser_token = config["browser_token"]
        self.device = config["device"]
        self.expires_at = config["expires_at_ms"] / 1000
        self.origin = config["browser_origin"]
        from urllib.parse import urlsplit
        origin = urlsplit(self.origin)
        if (not SCOPE.fullmatch(self.session) or not TOKEN.fullmatch(self.device_token)
                or not TOKEN.fullmatch(self.browser_token) or self.device_token == self.browser_token
                or not re.fullmatch(r"[a-f0-9]{32}", self.device)
                or not 0 < self.expires_at - time.time() <= 3600
                or origin.scheme != "https" or not origin.hostname or origin.path
                or origin.username or origin.query or origin.fragment):
            raise ValueError("invalid_private_scope")
        self.deadline = time.monotonic() + self.expires_at - time.time()
        self.lock = threading.Lock()
        self.jobs: dict[str, Job] = {}
        self.pending = deque()
        self.identity = None
        self.last_poll = 0.0
        self.revoked = False

    def check(self, role, token):
        with self.lock:
            self._live()
            expected = self.device_token if role == "device" else self.browser_token
            if not hmac.compare_digest(token, expected):
                raise Rejected(401, "authentication_required")

    def _live(self):
        if self.revoked or time.time() >= self.expires_at or time.monotonic() >= self.deadline:
            raise Rejected(410, "scope_retired")

    def poll(self, identity):
        with self.lock:
            self._live()
            if (not isinstance(identity, dict) or identity.get("v") != 1
                    or identity.get("device") != self.device or identity.get("enabled") is not True
                    or not isinstance(identity.get("epoch"), str)
                    or not re.fullmatch(r"[1-9][0-9]{0,19}", identity["epoch"])):
                raise Rejected(409, "device_identity_mismatch")
            if self.identity and self.identity["epoch"] != identity["epoch"]:
                self._revoke()
                raise Rejected(410, "device_epoch_changed")
            self.identity = {field: identity[field] for field in ("v", "device", "epoch", "enabled")}
            self.last_poll = time.monotonic()
            while self.pending:
                key = self.pending.popleft()
                job = self.jobs.get(key)
                if job and job.expires > time.monotonic():
                    job.delivered = True
                    return {"id": key, "request": job.request}
            return {}

    def device_identity(self):
        with self.lock:
            self._live()
            if self.identity is None or time.monotonic() - self.last_poll > 15:
                raise Rejected(503, "device_offline")
            return self.identity

    def enqueue(self, request, timeout=12):
        if not isinstance(request, dict) or len(json.dumps(request).encode()) > MAX_BODY:
            raise Rejected(400, "invalid_envelope")
        with self.lock:
            self._live()
            if self.identity is None or time.monotonic() - self.last_poll > 15:
                raise Rejected(503, "device_offline")
            if len(self.jobs) >= 8:
                raise Rejected(429, "queue_full")
            key = secrets.token_hex(16)
            job = Job(request, time.monotonic() + timeout)
            self.jobs[key] = job
            self.pending.append(key)
        return key, job

    def wait(self, key, job):
        job.event.wait(max(0, job.expires - time.monotonic()))
        with self.lock:
            self.jobs.pop(key, None)
            try: self.pending.remove(key)
            except ValueError: pass
            if job.response is not None:
                return job.response
            # A missing response never asserts command rejection or permits replay.
            raise Rejected(504, "outcome_unknown")

    def reply(self, key, response):
        with self.lock:
            self._live()
            job = self.jobs.get(key)
            if not job or not job.delivered or job.event.is_set() or job.expires <= time.monotonic():
                raise Rejected(409, "request_retired")
            if not isinstance(response, dict) or len(json.dumps(response).encode()) > MAX_BODY:
                raise Rejected(400, "invalid_response")
            job.response = response
            job.event.set()
            return {}

    def _revoke(self):
        self.revoked = True
        for job in self.jobs.values(): job.event.set()
        self.pending.clear()

    def revoke(self):
        with self.lock: self._revoke()

class RelayServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16
    def __init__(self, address, session, cert, key):
        self.session = session
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(address, Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, key)
        self.socket = context.wrap_socket(self.socket, server_side=True, do_handshake_on_connect=False)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            request.settimeout(15)
            request.do_handshake()
            super().process_request_thread(request, client_address)
        except (OSError, ssl.SSLError): request.close()
        finally: self.slots.release()

class Handler(BaseHTTPRequestHandler):
    server_version = "RQControlRelay"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    def log_message(self, *_): pass
    def do_OPTIONS(self):
        if self.headers.get("Origin") != self.server.session.origin:
            self.send_json(403, {"error": "origin_rejected"}); return
        self.send_json(200, {})
    def do_GET(self): self.dispatch()
    def do_POST(self): self.dispatch()
    def dispatch(self):
        try:
            scope = self.server.session
            prefix = "/v1/relay/" + scope.session + "/"
            if not self.path.startswith(prefix): raise Rejected(404, "unknown_route")
            operation = self.path[len(prefix):]
            role = "device" if operation in ("poll", "reply", "revoke") else "browser"
            if operation not in ("identity", "exchange", "poll", "reply", "revoke"):
                raise Rejected(404, "unknown_route")
            if self.command != ("GET" if operation == "identity" else "POST"):
                raise Rejected(405, "method_rejected")
            origin = self.headers.get("Origin")
            if role == "browser" and origin != scope.origin or role == "device" and origin is not None:
                raise Rejected(403, "origin_rejected")
            auth = self.headers.get("Authorization", "")
            if not auth.startswith("Bearer "): raise Rejected(401, "authentication_required")
            scope.check(role, auth[7:])
            body = self.body() if self.command == "POST" else None
            if operation == "identity": result = scope.device_identity()
            elif operation == "poll": result = scope.poll(body["identity"])
            elif operation == "reply": result = scope.reply(body["id"], body["response"])
            elif operation == "revoke": scope.revoke(); result = {}
            else:
                key, job = scope.enqueue(body)
                result = scope.wait(key, job)
            self.send_json(200, result)
        except Rejected as error: self.send_json(error.status, {"error": error.code})
        except (ValueError, KeyError, TypeError, UnicodeError): self.send_json(400, {"error": "invalid_body"})
        except (OSError, TimeoutError): self.close_connection = True
    def body(self):
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type") != "application/json":
            raise Rejected(400, "bounded_json_required")
        value = self.headers.get("Content-Length", "")
        if not value.isdecimal() or not 0 < int(value) <= MAX_BODY: raise Rejected(413, "body_limit")
        raw = self.rfile.read(int(value))
        if len(raw) != int(value): raise Rejected(400, "truncated_body")
        result = json.loads(raw)
        if not isinstance(result, dict): raise Rejected(400, "object_required")
        return result
    def send_json(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        if self.headers.get("Origin") == self.server.session.origin:
            self.send_header("Access-Control-Allow-Origin", self.server.session.origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True, help="private JSON scope, never commit")
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19443)
    args = parser.parse_args()
    session = Session(json.loads(args.config.read_text()))
    consume_scope_file(args.config)
    server = RelayServer((args.bind, args.port), session, args.cert, args.key)
    print(json.dumps({"schema": "rusty.quest.experiment_control_relay.v1", "state": "listening", "tls": True}), flush=True)
    try: server.serve_forever(poll_interval=0.25)
    finally: session.revoke(); server.server_close()

if __name__ == "__main__": main()
