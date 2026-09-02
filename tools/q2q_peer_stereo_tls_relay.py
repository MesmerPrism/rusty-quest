#!/usr/bin/env python3
"""Authenticated TLS byte relay for Rusty Quest packed-stereo peer sessions.

The relay authenticates a bounded binary prelude, pairs one sender with one
receiver by session and channel, then copies opaque RMANVID bytes. It never
parses media and never emits credentials in status output.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hmac
import json
import socket
import ssl
import struct
import tempfile
import threading
from dataclasses import dataclass, field
from pathlib import Path


MAGIC = b"RQPRLY1\n"
SCHEMA_VERSION = 1
ROLE_SENDER = 1
ROLE_RECEIVER = 2
MAX_FIELD_BYTES = 1024
COPY_BUFFER_BYTES = 64 * 1024


def _read_exact(sock: socket.socket, size: int) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise EOFError("relay connection closed during authentication")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def _read_field(sock: socket.socket) -> str:
    size = struct.unpack(">I", _read_exact(sock, 4))[0]
    if size < 1 or size > MAX_FIELD_BYTES:
        raise ValueError("relay authentication field length is invalid")
    return _read_exact(sock, size).decode("utf-8")


def _write_field(sock: socket.socket, value: str) -> None:
    encoded = value.encode("utf-8")
    if len(encoded) < 1 or len(encoded) > MAX_FIELD_BYTES:
        raise ValueError("relay authentication field length is invalid")
    sock.sendall(struct.pack(">I", len(encoded)) + encoded)


def write_authentication(
    sock: socket.socket,
    role: int,
    session_id: str,
    channel: str,
    token: str,
) -> None:
    sock.sendall(MAGIC + struct.pack(">IB", SCHEMA_VERSION, role))
    _write_field(sock, session_id)
    _write_field(sock, channel)
    _write_field(sock, token)


@dataclass(frozen=True)
class Authentication:
    role: int
    session_id: str
    channel: str


def read_authentication(sock: socket.socket, expected_token: str) -> Authentication:
    if _read_exact(sock, len(MAGIC)) != MAGIC:
        raise ValueError("relay magic is invalid")
    version, role = struct.unpack(">IB", _read_exact(sock, 5))
    session_id = _read_field(sock)
    channel = _read_field(sock)
    token = _read_field(sock)
    if version != SCHEMA_VERSION or role not in (ROLE_SENDER, ROLE_RECEIVER):
        raise ValueError("relay schema or role is invalid")
    if not hmac.compare_digest(token, expected_token):
        raise ValueError("relay authentication rejected")
    return Authentication(role=role, session_id=session_id, channel=channel)


@dataclass
class Lane:
    sender: ssl.SSLSocket | None = None
    receiver: ssl.SSLSocket | None = None
    done: threading.Event = field(default_factory=threading.Event)
    bytes_forwarded: int = 0
    chunks_forwarded: int = 0
    error: str = ""


class RelayServer:
    def __init__(
        self,
        host: str,
        port: int,
        token: str,
        certfile: str,
        keyfile: str,
        *,
        one_shot: bool = False,
    ) -> None:
        self.host = host
        self.port = port
        self.token = token
        self.one_shot = one_shot
        self._context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self._context.minimum_version = ssl.TLSVersion.TLSv1_2
        self._context.load_cert_chain(certfile=certfile, keyfile=keyfile)
        self._listener: socket.socket | None = None
        self._lock = threading.Lock()
        self._lanes: dict[tuple[str, str], Lane] = {}
        self._stopping = threading.Event()
        self._accepted = 0

    def serve(self) -> None:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((self.host, self.port))
        listener.listen(8)
        listener.settimeout(0.25)
        self.port = listener.getsockname()[1]
        self._listener = listener
        try:
            while not self._stopping.is_set():
                try:
                    raw, _ = listener.accept()
                except socket.timeout:
                    continue
                threading.Thread(target=self._handle, args=(raw,), daemon=True).start()
                self._accepted += 1
                if self.one_shot and self._accepted >= 2:
                    break
            if self.one_shot:
                lanes = list(self._lanes.values())
                for lane in lanes:
                    lane.done.wait(10.0)
        finally:
            with contextlib.suppress(OSError):
                listener.close()

    def stop(self) -> None:
        self._stopping.set()
        listener = self._listener
        if listener is not None:
            with contextlib.suppress(OSError):
                listener.close()

    def summary(self) -> dict[str, object]:
        with self._lock:
            lanes = list(self._lanes.values())
        return {
            "schema": "rusty.quest.q2q_peer_stereo_tls_relay_summary.v1",
            "authenticated_tls": True,
            "opaque_binary_media": True,
            "high_rate_json_payload": False,
            "accepted_connections": self._accepted,
            "paired_lanes": sum(1 for lane in lanes if lane.sender and lane.receiver),
            "bytes_forwarded": sum(lane.bytes_forwarded for lane in lanes),
            "chunks_forwarded": sum(lane.chunks_forwarded for lane in lanes),
            "failed_lanes": sum(1 for lane in lanes if lane.error),
            "secret_serialized": False,
            "endpoint_serialized": False,
        }

    def _handle(self, raw: socket.socket) -> None:
        peer: ssl.SSLSocket | None = None
        lane: Lane | None = None
        try:
            peer = self._context.wrap_socket(raw, server_side=True)
            peer.settimeout(10.0)
            auth = read_authentication(peer, self.token)
            key = (auth.session_id, auth.channel)
            with self._lock:
                lane = self._lanes.setdefault(key, Lane())
                if auth.role == ROLE_SENDER:
                    if lane.sender is not None:
                        raise ValueError("relay sender role is already occupied")
                    lane.sender = peer
                else:
                    if lane.receiver is not None:
                        raise ValueError("relay receiver role is already occupied")
                    lane.receiver = peer
                paired = lane.sender is not None and lane.receiver is not None
            if paired:
                threading.Thread(target=self._forward, args=(lane,), daemon=True).start()
            lane.done.wait(15.0 if self.one_shot else 86_400.0)
        except Exception as error:  # bounded server edge; detail stays local
            if lane is not None:
                lane.error = type(error).__name__
                lane.done.set()
        finally:
            if peer is not None and (lane is None or lane.done.is_set()):
                with contextlib.suppress(OSError):
                    peer.close()
            elif peer is None:
                with contextlib.suppress(OSError):
                    raw.close()

    @staticmethod
    def _forward(lane: Lane) -> None:
        assert lane.sender is not None and lane.receiver is not None
        try:
            while True:
                chunk = lane.sender.recv(COPY_BUFFER_BYTES)
                if not chunk:
                    break
                lane.receiver.sendall(chunk)
                lane.bytes_forwarded += len(chunk)
                lane.chunks_forwarded += 1
        except Exception as error:
            lane.error = type(error).__name__
        finally:
            for peer in (lane.sender, lane.receiver):
                with contextlib.suppress(OSError):
                    peer.shutdown(socket.SHUT_RDWR)
                with contextlib.suppress(OSError):
                    peer.close()
            lane.done.set()


def _generate_local_certificate(root: Path) -> tuple[Path, Path]:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = issuer = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(minutes=1))
        .not_valid_after(now + dt.timedelta(hours=1))
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False)
        .sign(key, hashes.SHA256())
    )
    cert_path = root / "relay-cert.pem"
    key_path = root / "relay-key.pem"
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    return cert_path, key_path


def self_test() -> int:
    token = "local-self-test-secret"
    session_id = "session-local-tls"
    channel = "packed-stereo-a-to-b"
    payload = (b"RMANVID1" + bytes(range(256))) * 4096
    with tempfile.TemporaryDirectory(prefix="rusty-q2q-relay-") as temp:
        root = Path(temp)
        cert, key = _generate_local_certificate(root)
        relay = RelayServer("127.0.0.1", 0, token, str(cert), str(key), one_shot=True)
        server_thread = threading.Thread(target=relay.serve, daemon=True)
        server_thread.start()
        while relay.port == 0:
            threading.Event().wait(0.01)

        client_context = ssl.create_default_context(cafile=str(cert))
        received = bytearray()
        payload_received = threading.Event()

        def receive() -> None:
            raw = socket.create_connection(("127.0.0.1", relay.port), timeout=5.0)
            with client_context.wrap_socket(raw, server_hostname="localhost") as peer:
                write_authentication(peer, ROLE_RECEIVER, session_id, channel, token)
                while len(received) < len(payload):
                    chunk = peer.recv(COPY_BUFFER_BYTES)
                    if not chunk:
                        break
                    received.extend(chunk)
                if len(received) == len(payload):
                    payload_received.set()

        receiver = threading.Thread(target=receive)
        receiver.start()
        raw_sender = socket.create_connection(("127.0.0.1", relay.port), timeout=5.0)
        with client_context.wrap_socket(raw_sender, server_hostname="localhost") as sender:
            write_authentication(sender, ROLE_SENDER, session_id, channel, token)
            sender.sendall(payload)
            payload_received.wait(10.0)
        receiver.join(15.0)
        server_thread.join(15.0)
        summary = relay.summary()
        passed = (
            not receiver.is_alive()
            and received == payload
            and summary["paired_lanes"] == 1
            and summary["bytes_forwarded"] == len(payload)
            and summary["failed_lanes"] == 0
        )
        summary["schema"] = "rusty.quest.q2q_peer_stereo_tls_relay_self_test.v1"
        summary["status"] = "passed" if passed else "failed"
        summary["payload_identity_match"] = received == payload
        print(json.dumps(summary, sort_keys=True))
        return 0 if passed else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("self-test")
    server = subparsers.add_parser("server")
    server.add_argument("--host", default="0.0.0.0")
    server.add_argument("--port", type=int, required=True)
    server.add_argument("--token-file", required=True)
    server.add_argument("--certfile", required=True)
    server.add_argument("--keyfile", required=True)
    args = parser.parse_args()
    if args.command == "self-test":
        return self_test()
    token = Path(args.token_file).read_text(encoding="utf-8").strip()
    relay = RelayServer(args.host, args.port, token, args.certfile, args.keyfile)
    print(
        json.dumps(
            {
                "schema": "rusty.quest.q2q_peer_stereo_tls_relay_ready.v1",
                "authenticated_tls": True,
                "opaque_binary_media": True,
                "high_rate_json_payload": False,
                "secret_serialized": False,
            },
            sort_keys=True,
        ),
        flush=True,
    )
    try:
        relay.serve()
    except KeyboardInterrupt:
        relay.stop()
    print(json.dumps(relay.summary(), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
