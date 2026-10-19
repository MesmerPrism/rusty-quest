# SPDX-License-Identifier: AGPL-3.0-or-later
"""Production provider lifecycle tests; transport responses are synthetic, not E2E evidence."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("recenter", Path(__file__).with_name("quest_shell_recenter.py"))
provider = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provider)


class FakeTransport:
    starts = 0
    instances = []
    outcome = "ORIGINAL_RESULT"
    pending = False
    bad_final = False
    failed_guard = False
    failed_cleanup = False
    damaged_state = False
    damaged_intent = False
    damaged_pid = False

    def __init__(self, request, output):
        self.request, self.output = request, output
        self.commands = []
        self.instances.append(self)

    def command(self, *args):
        self.commands.append(args)
        r = self.request
        stage = "/data/local/tmp/rusty-quest-recenter-" + r["request_id"]
        fixed = {("shell", "getprop", "ro.serialno"): r["canonical_serial"],
                 ("shell", "cat", "/proc/sys/kernel/random/boot_id"): r["boot_id"],
                 ("shell", "getprop", "ro.build.fingerprint"): r["firmware"]["fingerprint"],
                 ("shell", "id", "-u"): "10001" if self.failed_guard else "2000",
                 ("shell", "getenforce"): "Enforcing"}
        if args in fixed:
            return (fixed[args] + "\n").encode()
        if args[:2] == ("shell", "sha256sum"):
            hashes = {"/system_ext/priv-app/VrShell/VrShell.apk": r["firmware"]["vrshell_sha256"],
                      "/system/framework/framework.jar": r["firmware"]["framework_sha256"],
                      stage + "/recenter.jar": r["artifact"]["sha256"]}
            return (hashes[args[2]] + "  " + args[2] + "\n").encode()
        if args[:2] == ("exec-out", "cat"):
            return self.final_bytes if args[2].endswith("/FINAL.json") else self.intent_bytes
        if args[:2] == ("shell", "sh"):
            return b"ABSENT\n"
        if args[:2] == ("shell", "rm") and self.failed_cleanup:
            raise RuntimeError("cleanup-failed")
        if args[:2] in (("shell", "mkdir"), ("shell", "rm"), ("shell", "rmdir")) or args[0] == "push":
            return b""
        raise AssertionError(args)

    def start(self, command):
        type(self).starts += 1
        r = self.request
        readiness = "--mode readiness" in command
        final = {"schema": "rusty.quest.shell_recenter.event.v1", "event": "TERMINAL",
                 "session": r["request_id"], "original_nonce": r["nonce"],
                 "source_id": r["source"]["source_id"], "boot_id": r["boot_id"],
                 "target_package": provider.TARGET, "function_id": provider.FUNCTION,
                 "pid": 9876 if self.damaged_pid else 1234, "uid": 2000,
                 "execute_attempted": not readiness, "original_callback_received": not readiness,
                 "cleanup_held": False, "outcome": "READINESS_READY" if readiness else self.outcome,
                 "intent_published": not readiness}
        intent = dict(final, event="EXECUTE_INTENT", execute_attempted=False, original_callback_received=False)
        self.intent_bytes = provider.canonical(intent)
        if self.damaged_state:
            final.update(execute_attempted=False, original_callback_received=False)
        self.final_bytes = provider.canonical(final)
        ref = {"path": "/data/local/tmp/rusty-quest-recenter-" + r["request_id"] + "/output/FINAL.json",
               "bytes": len(self.final_bytes), "sha256": provider.digest(self.final_bytes)}
        if self.bad_final:
            ref["sha256"] = "0" * 64
        published = dict(final, event="FINAL_PUBLISHED", final=ref)
        if not readiness:
            published["intent"] = {"path": ref["path"].replace("FINAL.json", "EXECUTE_INTENT.json"),
                                   "bytes": len(self.intent_bytes),
                                   "sha256": "0" * 64 if self.damaged_intent else provider.digest(self.intent_bytes)}
        unknown = dict(final, event="ORIGINAL_UNKNOWN_HELD", cleanup_held=True, original_callback_received=False)
        code = "import json,os,time; print('ORIGINAL_PID=1234',flush=True); print('1234 (owned) S 1 2 3',flush=True); "
        if self.pending:
            code += "print(" + repr(json.dumps(unknown)) + ",flush=True); "
            code += "\nwhile not os.path.exists(" + repr(str(self.output / "allow-final")) + "): time.sleep(.01)\n"
        code += "print(" + repr(json.dumps(published)) + ",flush=True)"
        return subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE, stderr=subprocess.PIPE)


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        artifact = root / "dex.jar"
        artifact.write_bytes(b"synthetic-artifact")
        self.request = {"schema": provider.REQUEST_SCHEMA, "request_id": "1" * 32, "nonce": "2" * 32,
                        "adb": str(Path(sys.executable).resolve()), "canonical_serial": "synthetic-device",
                        "selector": "192.0.2.1:5555", "boot_id": "11111111-1111-4111-8111-111111111111",
                        "operator_id": "synthetic-operator", "output_dir": str(root / "run"),
                        "artifact": {"path": str(artifact), "sha256": provider.digest(artifact.read_bytes()),
                                     "bytes": artifact.stat().st_size},
                        "source": {"revision": "a" * 40, "tree": "b" * 40, "source_id": "c" * 64},
                        "firmware": {"fingerprint": "synthetic/product/build:14/test/user/release-keys",
                                     "vrshell_sha256": "d" * 64, "framework_sha256": "e" * 64}}
        store = patch.object(provider, "original_store_root", return_value=root / "originals")
        store.start()
        self.addCleanup(store.stop)
        FakeTransport.starts = 0
        FakeTransport.instances = []
        FakeTransport.outcome = "ORIGINAL_RESULT"
        FakeTransport.pending = FakeTransport.bad_final = FakeTransport.failed_guard = FakeTransport.failed_cleanup = False
        FakeTransport.damaged_state = FakeTransport.damaged_intent = FakeTransport.damaged_pid = False
        self.source = patch.object(provider.Session, "verify_source")
        self.source.start()
        self.addCleanup(self.source.stop)

    def run_session(self, mode="recenter"):
        return provider.Session(self.request, mode, FakeTransport).run()

    def test_success_original_retirement_and_no_replay(self):
        result = self.run_session()
        self.assertEqual(result["callback"]["state"], "success")
        self.assertEqual(result["effect"]["state"], "unverified")
        self.assertTrue(result["original"]["retired"])
        self.assertEqual(result["cleanup"]["state"], "complete")
        self.assertFalse((Path(self.request["output_dir"]) / "ACTIVE.json").exists())
        self.assertEqual(self.run_session(), result)
        self.assertEqual(FakeTransport.starts, 1)
        with patch.object(provider, "Transport", side_effect=AssertionError("reconcile must be inert")):
            self.assertEqual(provider.reconcile(self.request), result)

    def test_readiness_never_dispatches(self):
        result = self.run_session("readiness")
        self.assertEqual(result["availability"]["status"], "ready")
        self.assertEqual(result["dispatch"]["state"], "not_sent")
        self.assertEqual(result["callback"]["state"], "not_received")

    def test_wrong_privilege_fails_before_stage_or_launch(self):
        FakeTransport.failed_guard = True
        result = self.run_session()
        self.assertEqual(result["availability"]["status"], "unavailable")
        self.assertEqual(result["dispatch"]["state"], "not_sent")
        self.assertTrue(result["original"]["retired"])
        self.assertEqual(FakeTransport.starts, 0)
        self.assertFalse(any(c[:2] == ("shell", "mkdir") for c in FakeTransport.instances[0].commands))

    def test_late_original_stays_owned_and_duplicate_does_not_retrigger(self):
        FakeTransport.pending = True
        received, errors = [], []
        def execute():
            try:
                received.append(self.run_session())
            except BaseException as error:
                errors.append(error)
        thread = threading.Thread(target=execute)
        thread.start()
        output = Path(self.request["output_dir"])
        try:
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                try:
                    pending = provider.reconcile(self.request)
                    if pending["callback"]["state"] == "unknown":
                        break
                except (OSError, ValueError):
                    pass
                time.sleep(.01)
            else:
                self.fail("unknown original not durably projected: " + repr((errors, received, provider.reconcile(self.request))))
            self.assertTrue(thread.is_alive())
            self.assertEqual(self.run_session()["cleanup"]["state"], "held")
            self.assertEqual(FakeTransport.starts, 1)
        finally:
            (output / "allow-final").write_text("final")
            thread.join(10)
        self.assertFalse(thread.is_alive())
        self.assertEqual(errors, [])
        self.assertEqual(received[0]["callback"]["state"], "success")

    def test_error_callback_is_retired_but_effect_not_invented(self):
        FakeTransport.outcome = "ORIGINAL_ERROR"
        result = self.run_session()
        self.assertEqual(result["callback"]["state"], "error")
        self.assertTrue(result["original"]["retired"])
        self.assertEqual(result["effect"]["state"], "unverified")

    def test_final_or_cleanup_failure_retains_original_and_stage(self):
        FakeTransport.bad_final = True
        result = self.run_session()
        self.assertFalse(result["original"]["retired"])
        self.assertEqual(result["cleanup"]["state"], "held")
        self.assertTrue((Path(self.request["output_dir"]) / "ACTIVE.json").exists())
        self.assertFalse(any(c[:2] == ("shell", "rm") for c in FakeTransport.instances[0].commands))
        self.assertEqual(FakeTransport.starts, 1)

    def test_cleanup_exception_not_reported_complete(self):
        FakeTransport.failed_cleanup = True
        result = self.run_session()
        self.assertEqual(result["callback"]["state"], "success")
        self.assertEqual(result["cleanup"]["state"], "held")
        self.assertFalse(result["original"]["retired"])

    def test_damaged_state_pid_or_intent_never_acquires_completion(self):
        original_output = self.request["output_dir"]
        for field in ("damaged_state", "damaged_pid", "damaged_intent"):
            setattr(FakeTransport, field, True)
            self.request["request_id"] = provider.digest(field.encode())[:32]
            self.request["output_dir"] = original_output + "-" + field
            result = self.run_session()
            self.assertFalse(result["original"]["retired"], field)
            self.assertEqual(result["cleanup"]["state"], "held", field)
            self.assertFalse(any(c[:2] == ("shell", "rm") for c in FakeTransport.instances[-1].commands), field)
            setattr(FakeTransport, field, False)

    def test_identity_journal_blocks_changed_output_and_lost_evidence_replay(self):
        result = self.run_session()
        changed = copy.deepcopy(self.request)
        changed["output_dir"] += "-changed"
        with self.assertRaisesRegex(ValueError, "original-identity-already-owned"):
            provider.Session(changed, "recenter", FakeTransport).run()
        changed = copy.deepcopy(self.request)
        changed["nonce"] = "3" * 32
        with self.assertRaisesRegex(ValueError, "original-identity-already-owned"):
            provider.Session(changed, "recenter", FakeTransport).run()
        with self.assertRaisesRegex(ValueError, "original-mode-cannot-change"):
            self.run_session("readiness")
        output = Path(self.request["output_dir"])
        # Simulate operator evidence loss without touching the durable identity.
        for path in output.iterdir():
            path.unlink()
        output.rmdir()
        held = self.run_session()
        self.assertEqual(held["dispatch"]["state"], "unknown")
        self.assertEqual(held["cleanup"]["state"], "held")
        self.assertEqual(FakeTransport.starts, 1)
        self.assertTrue(result["original"]["retired"])

    def test_artifact_and_original_identity_drift_fail_closed(self):
        self.request["artifact"]["sha256"] = "0" * 64
        result = self.run_session()
        self.assertEqual(FakeTransport.starts, 0)
        self.assertEqual(result["dispatch"]["state"], "not_sent")
        changed = copy.deepcopy(self.request)
        changed["nonce"] = "3" * 32
        with self.assertRaisesRegex(ValueError, "original-identity-already-owned"):
            provider.reconcile(changed)

    def test_closed_requests_and_shell_injection_rejected(self):
        for key, value in (("selector", "serial;reboot"), ("operator_id", "bad id"),
                           ("request_id", "../escape"), ("output_dir", "relative")):
            bad = dict(self.request, **{key: value})
            with self.assertRaises((ValueError, TypeError)):
                provider.validate(bad)
        bad = dict(self.request, arbitrary_shell="reboot")
        with self.assertRaises(ValueError):
            provider.validate(bad)
        with self.assertRaisesRegex(ValueError, "duplicate-json-property"):
            json.loads('{"schema":1,"schema":2}', object_pairs_hook=provider.object_pairs)

    def test_real_source_inventory_and_git_identity_are_checked(self):
        self.source.stop()
        root = Path(provider.__file__).resolve().parents[2]
        sources = sorted((root / "apps/shell-recenter-android/src/main/java").rglob("*.java"))
        text = "".join(p.relative_to(root).as_posix() + " " + provider.digest(p.read_bytes()) + "\n" for p in sources)
        self.request["source"]["source_id"] = provider.digest(text.encode())
        session = provider.Session(self.request, "recenter", FakeTransport)
        def git(args):
            if args[-1] == "HEAD":
                return (self.request["source"]["revision"] + "\n").encode()
            if args[-1] == "HEAD^{tree}":
                return (self.request["source"]["tree"] + "\n").encode()
            return b""
        with patch.object(provider.subprocess, "check_output", side_effect=git):
            session.verify_source()
            self.request["source"]["source_id"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "exact-java-source-identity-required"):
                session.verify_source()
        with patch.object(provider.subprocess, "check_output", return_value=b"wrong-git-identity"):
            with self.assertRaisesRegex(ValueError, "exact-clean-provider-source-required"):
                session.verify_source()


if __name__ == "__main__":
    unittest.main()
