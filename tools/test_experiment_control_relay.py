#!/usr/bin/env python3
"""Focused relay state and real HTTPS rehearsal. --tls-port requires host-port reservation."""
import argparse
import datetime
import http.client
import ipaddress
import json
from pathlib import Path
import secrets
import ssl
import tempfile
import threading
import time
import unittest
import subprocess
import hashlib
import hmac
import os
from experiment_control_relay import Session, Rejected, RelayServer, consume_scope_file

def config():
    return dict(session=secrets.token_urlsafe(18), device_token=secrets.token_urlsafe(32),
                browser_token=secrets.token_urlsafe(32), device="a" * 32,
                expires_at_ms=int(time.time() * 1000) + 60000, browser_origin="https://controller.example")

def identity(): return dict(v=1, device="a" * 32, epoch="123", enabled=True)

class StateTests(unittest.TestCase):
    def test_scope_file_cannot_restart_or_resurrect_session(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "scope.json"
            path.write_text(json.dumps(config()))
            consume_scope_file(path)
            with self.assertRaises(FileExistsError): consume_scope_file(path)
            self.assertNotIn("token", Path(str(path) + ".used").read_text())
    def test_separate_roles_and_scope_validation(self):
        c = config(); s = Session(c)
        s.check("device", c["device_token"])
        with self.assertRaises(Rejected): s.check("browser", c["device_token"])
        c["browser_token"] = c["device_token"]
        with self.assertRaises(ValueError): Session(c)
    def test_opaque_original_response_no_transport_receipt(self):
        s = Session(config()); s.poll(identity())
        request = {"v": 1, "payload": '{"action":"pause"}', "mac": "opaque"}
        key, job = s.enqueue(request)
        self.assertEqual(s.poll(identity()), {"id": key, "request": request})
        response = {"payload": '{"receipt":{"outcome":"technical_hold"}}', "mac": "owner_mac"}
        s.reply(key, response)
        self.assertEqual(s.wait(key, job), response)
        with self.assertRaises(Rejected): s.reply(key, response)
    def test_identity_epoch_and_expiry_revoke(self):
        s = Session(config()); s.poll(identity())
        s.poll(dict(identity(), route="changed_hint"))
        self.assertEqual(s.device_identity(), identity())
        with self.assertRaises(Rejected): s.poll(dict(identity(), epoch=123))
        with self.assertRaises(Rejected): s.poll(dict(identity(), epoch="124"))
        self.assertTrue(s.revoked)
        s = Session(config()); s.deadline = time.monotonic() - 1
        with self.assertRaises(Rejected): s.poll(identity())
    def test_queue_limit_and_cancel_before_delivery(self):
        s = Session(config()); s.poll(identity())
        jobs = [s.enqueue({"i": i}, .01) for i in range(8)]
        with self.assertRaises(Rejected): s.enqueue({})
        time.sleep(.02)
        self.assertEqual(s.poll(identity()), {})
        for key, job in jobs:
            with self.assertRaises(Rejected) as error: s.wait(key, job)
            self.assertEqual(error.exception.code, "outcome_unknown")
    def test_revocation_wakes_waiter_without_invented_outcome(self):
        s = Session(config()); s.poll(identity()); key, job = s.enqueue({})
        s.poll(identity()); s.revoke()
        with self.assertRaises(Rejected) as error: s.wait(key, job)
        self.assertEqual(error.exception.code, "outcome_unknown")
        with self.assertRaises(Rejected): s.reply(key, {"receipt": "fake"})

def tls_rehearsal(port, java_home=None, json_jar=None):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    with tempfile.TemporaryDirectory(prefix="rq-control-relay-") as temp:
        root = Path(temp)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(minutes=1))
                .not_valid_after(now + datetime.timedelta(minutes=10))
                .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
                .sign(key, hashes.SHA256()))
        cert_path = root / "cert.pem"; key_path = root / "key.pem"
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        c = config(); session = Session(c)
        server = RelayServer(("127.0.0.1", port), session, str(cert_path), str(key_path))
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        context = ssl.create_default_context(cafile=str(cert_path))
        def call(op, body=None, role="device", origin=None, token=None, ssl_context=context):
            conn = http.client.HTTPSConnection("127.0.0.1", port, context=ssl_context, timeout=5)
            headers = {"Authorization": "Bearer " + (token or c[role + "_token"])}
            if role == "browser": headers["Origin"] = origin or c["browser_origin"]
            if body is not None: headers["Content-Type"] = "application/json"
            try:
                conn.request("GET" if body is None else "POST", "/v1/relay/" + c["session"] + "/" + op,
                             json.dumps(body) if body is not None else None, headers)
                response = conn.getresponse(); return response.status, json.loads(response.read())
            finally: conn.close()
        try:
            try: call("poll", {"identity": identity()}, ssl_context=ssl.create_default_context())
            except ssl.SSLCertVerificationError: pass
            else: raise AssertionError("untrusted TLS certificate accepted")
            assert call("poll", {"identity": identity()}) == (200, {})
            assert call("identity", role="browser") == (200, identity())
            assert call("identity", role="browser", origin="https://evil.example")[0] == 403
            assert call("identity", role="browser", token=c["device_token"])[0] == 401
            response = {}; request = {"v": 1, "payload": '{"action":"pause"}', "mac": "opaque"}
            worker = threading.Thread(target=lambda: response.update(result=call("exchange", request, "browser")))
            worker.start()
            job = {}
            for _ in range(30):
                _, job = call("poll", {"identity": identity()})
                if "id" in job: break
                time.sleep(.02)
            assert job["request"] == request
            original = {"payload": '{"receipt":{"outcome":"confirmed"}}', "mac": "owner_response_mac"}
            assert call("reply", {"id": job["id"], "response": original}) == (200, {})
            worker.join(5); assert response["result"] == (200, original)
            assert call("revoke", {}) == (200, {})
            assert call("identity", role="browser")[0] == 410
        finally:
            session.revoke(); server.shutdown(); server.server_close(); thread.join(5)
        if java_home and json_jar:
            java_rehearsal(root, cert_path, key_path, port, java_home, json_jar, context)
    print(json.dumps({"schema": "rusty.quest.experiment_control_relay_test.v1", "real_https": True,
                      "certificate_rejection": True, "role_separation": True, "origin_rejection": True,
                      "opaque_request_response": True, "revocation": True,
                      "actual_gateway_commands": False, "device_evidence": False}))

def java_rehearsal(root, cert_path, key_path, port, java_home, json_jar, context):
    repo = Path(__file__).resolve().parents[1]
    pkg = Path("io/github/mesmerprism/rustyquest/native_renderer")
    source = repo / "apps/native-renderer-android/panel-modules/breath-composition/src/main/java" / pkg
    harness = repo / "apps/native-renderer-android/tests/java" / pkg / "ExperimentSessionRelayHarness.java"
    classes = root / "classes"; classes.mkdir()
    subprocess.run([str(Path(java_home)/"bin/javac.exe"), "--release", "8", "-cp", str(json_jar), "-d", str(classes),
                    str(source/"ExperimentSessionGatewayProtocol.java"), str(source/"ExperimentSessionRelayClient.java"), str(harness)], check=True)
    trust = root/"trust.p12"
    subprocess.run([str(Path(java_home)/"bin/keytool.exe"), "-importcert", "-noprompt", "-alias", "rehearsal",
                    "-file", str(cert_path), "-keystore", str(trust), "-storepass", "test-only-password"], check=True, capture_output=True)
    c = config(); c.update(origin="https://127.0.0.1:"+str(port), controller=secrets.token_hex(16), gateway_key=secrets.token_hex(32))
    cfg = root/"java-private.json"; cfg.write_text(json.dumps(c))
    session = Session(c); server = RelayServer(("127.0.0.1", port), session, str(cert_path), str(key_path))
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    process = subprocess.Popen([str(Path(java_home)/"bin/java.exe"), "-Djavax.net.ssl.trustStore="+str(trust),
                               "-Djavax.net.ssl.trustStorePassword=test-only-password", "-cp", str(classes)+os.pathsep+str(json_jar),
                               "io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionRelayHarness", str(cfg)],
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    def signed(action, payload):
        nonce = secrets.token_hex(16); body = json.dumps(payload, separators=(",", ":"))
        message = "|".join(["RQG1", c["controller"], "123", nonce, action, body])
        return dict(v=1, controller=c["controller"], epoch="123", nonce=nonce, action=action, payload=body,
                    mac=hmac.new(c["gateway_key"].encode(), message.encode(), hashlib.sha256).hexdigest())
    def exchange(envelope):
        conn = http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=15)
        try:
            conn.request("POST", "/v1/relay/"+c["session"]+"/exchange", json.dumps(envelope),
                         {"Authorization":"Bearer "+c["browser_token"], "Origin":c["browser_origin"], "Content-Type":"application/json"})
            response = conn.getresponse(); body = json.loads(response.read()); assert response.status == 200, body
            message = "|".join(["RQG1-REPLY", c["controller"], c["device"], "123", envelope["nonce"], envelope["action"], body["payload"]])
            assert hmac.compare_digest(body["mac"], hmac.new(c["gateway_key"].encode(), message.encode(), hashlib.sha256).hexdigest())
            return json.loads(body["payload"])
        finally: conn.close()
    try:
        for _ in range(100):
            if session.identity: break
            if process.poll() is not None: raise AssertionError(process.stderr.read().decode())
            time.sleep(.05)
        assert session.identity == identity(), "Java client did not publish identity"
        assert exchange(signed("status", {}))["status"]["s"] == "paused"
        for command in ["arm", "official_start", "pause", "resume", "finish", "abort"]:
            request_id = secrets.token_hex(16)
            receipt = exchange(signed("command", {"action":command, "request_id":request_id}))["receipt"]
            assert receipt == {"action":command, "request_id":request_id, "outcome":"pending"}
        assert exchange(signed("receipt", {}))["receipt"]["outcome"] == "outcome_unknown"
        print(json.dumps({"real_java_relay_client":True, "real_gateway_protocol":True,
                          "signed_status_commands_receipt":True, "command_owner":"modeled", "device_evidence":False}))
    finally:
        process.communicate(input=b"\n", timeout=15)
        session.revoke(); server.shutdown(); server.server_close(); thread.join(5)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("--tls-port", type=int)
    parser.add_argument("--java-home"); parser.add_argument("--json-jar")
    args = parser.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(StateTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful(): raise SystemExit(1)
    if bool(args.java_home) != bool(args.json_jar): parser.error("--java-home and --json-jar required together")
    if args.tls_port is not None: tls_rehearsal(args.tls_port, args.java_home, args.json_jar)
