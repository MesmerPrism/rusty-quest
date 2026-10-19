#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Closed local shell recenter provider. Pending originals are never replayed/killed.

No device is discovered or selected implicitly. Coordination/physical custody is
an operator prerequisite, not something a profile or this provider can grant.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import threading
import time
import uuid

REQUEST_SCHEMA = "rusty.quest.shell_recenter.request.v1"
RESULT_SCHEMA = "rusty.quest.shell_recenter.result.v1"
MAIN = "io.github.mesmerprism.rustyquest.shellrecenter.ShellRecenterMain"
TARGET = "com.oculus.vrshell"
FUNCTION = "com.oculus.vrshell.appfunctions.ShellAppFunctions#recenterView"
FIELDS = {"schema", "request_id", "nonce", "adb", "canonical_serial", "selector",
          "boot_id", "operator_id", "artifact", "source", "firmware", "output_dir"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate-json-property")
        result[key] = value
    return result


def read_json(path):
    data = Path(path).read_bytes()
    if len(data) > 262144:
        raise ValueError("bounded-document-required")
    return json.loads(data, object_pairs_hook=object_pairs)


def exact(value, fields):
    if not isinstance(value, dict) or set(value) != set(fields):
        raise ValueError("closed-document-fields-required")


def match(value, pattern):
    if not isinstance(value, str) or not re.fullmatch(pattern, value):
        raise ValueError("invalid-typed-identity")


def absolute(value):
    if not isinstance(value, str) or not value or not Path(value).is_absolute():
        raise ValueError("absolute-path-required")
    path = Path(value)
    if path.resolve() != path.absolute() or path.is_symlink():
        raise ValueError("canonical-nonsymlink-path-required")
    return path


def validate(request):
    exact(request, FIELDS)
    if request["schema"] != REQUEST_SCHEMA:
        raise ValueError("request-schema")
    for key in ("request_id", "nonce"):
        match(request[key], r"[0-9a-f]{32}")
    match(request["canonical_serial"], r"[A-Za-z0-9_.-]{1,128}")
    match(request["selector"], r"[A-Za-z0-9_.:-]{1,192}")
    if str(uuid.UUID(request["boot_id"])) != request["boot_id"]:
        raise ValueError("canonical-boot-required")
    match(request["operator_id"], r"[A-Za-z0-9_.:@-]{1,128}")
    absolute(request["adb"])
    output = absolute(request["output_dir"])
    if output == output.parent or not output.parent.is_dir():
        raise ValueError("existing-output-parent-required")
    exact(request["artifact"], {"path", "sha256", "bytes"})
    absolute(request["artifact"]["path"])
    match(request["artifact"]["sha256"], r"[0-9a-f]{64}")
    if type(request["artifact"]["bytes"]) is not int or request["artifact"]["bytes"] <= 0:
        raise ValueError("artifact-bytes")
    exact(request["source"], {"revision", "tree", "source_id"})
    for key in ("revision", "tree"):
        match(request["source"][key], r"[0-9a-f]{40}")
    match(request["source"]["source_id"], r"[0-9a-f]{64}")
    exact(request["firmware"], {"fingerprint", "vrshell_sha256", "framework_sha256"})
    match(request["firmware"]["fingerprint"], r"[A-Za-z0-9_./:+-]{1,512}")
    for key in ("vrshell_sha256", "framework_sha256"):
        match(request["firmware"][key], r"[0-9a-f]{64}")
    return request


def publish(path, value, exclusive=False):
    data = canonical(value)
    if exclusive:
        with Path(path).open("xb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    else:
        temporary = Path(str(path) + ".new")
        with temporary.open("wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        for attempt in range(100):
            try:
                os.replace(temporary, path)
                break
            except PermissionError:
                # Windows readers can momentarily deny rename. Keep the old
                # complete receipt visible; never truncate it for an observer.
                if attempt == 99:
                    raise
                time.sleep(.01)


def initial(request):
    return {"schema": RESULT_SCHEMA, "request_id": request["request_id"],
            "nonce": request["nonce"], "boot_id": request["boot_id"],
            "source_id": request["source"]["source_id"],
            "request_sha256": digest(canonical(request)), "artifact": request["artifact"],
            "availability": {"status": "unknown", "reason": "not_checked",
                             "privilege": "android_shell_uid_2000"},
            "dispatch": {"state": "not_sent"}, "callback": {"state": "not_received"},
            "effect": {"state": "unverified"},
            "original": {"state": "unknown", "retired": False},
            "cleanup": {"state": "not_started"}}


def original_store_root():
    """Local original ownership, shared across profiles and Quest checkouts."""
    return Path.home() / ".rusty-quest" / "shell-recenter" / "originals"


def original_record(request):
    path = original_store_root() / (request["request_id"] + ".json")
    if not path.exists():
        return None
    record = read_json(path)
    if (record.get("schema") != "rusty.quest.shell_recenter.original.v1" or
            record.get("request_id") != request["request_id"] or
            record.get("nonce") != request["nonce"] or
            record.get("request_sha256") != digest(canonical(request)) or
            record.get("output_dir") != request["output_dir"]):
        raise ValueError("original-identity-already-owned-by-another-exact-request")
    return record


def claim_original(request, mode):
    root = original_store_root()
    root.mkdir(parents=True, exist_ok=True)
    record = {"schema": "rusty.quest.shell_recenter.original.v1", "request_id": request["request_id"],
              "nonce": request["nonce"], "request_sha256": digest(canonical(request)),
              "output_dir": request["output_dir"], "mode": mode}
    try:
        publish(root / (request["request_id"] + ".json"), record, exclusive=True)
        return True
    except FileExistsError:
        previous = original_record(request)
        if previous["mode"] != mode:
            raise ValueError("original-mode-cannot-change-use-fresh-identity")
        return False


def reconcile(request):
    record = original_record(request)
    output = Path(request["output_dir"])
    for filename in ("RESULT.json", "STATUS.json"):
        path = output / filename
        if path.exists():
            result = read_json(path)
            if (result.get("schema") != RESULT_SCHEMA or
                    result.get("request_sha256") != digest(canonical(request)) or
                    result.get("request_id") != request["request_id"] or
                    result.get("nonce") != request["nonce"]):
                raise ValueError("original-request-mismatch")
            return result
    result = initial(request)
    if output.exists() or record is not None:
        result["dispatch"]["state"] = "unknown"
        result["cleanup"]["state"] = "held"
        result["availability"]["reason"] = "original-receipt-unavailable-no-replay"
    return result


class Transport:
    def __init__(self, request, output):
        self.request, self.output, self.count = request, output, 0

    def argv(self, *args):
        return [self.request["adb"], "-s", self.request["selector"], *args]

    def command(self, *args):
        self.count += 1
        child = subprocess.run(self.argv(*args), stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, check=False)
        stem = self.output / ("transport-%03d" % self.count)
        stem.with_suffix(".stdout").write_bytes(child.stdout)
        stem.with_suffix(".stderr").write_bytes(child.stderr)
        publish(stem.with_suffix(".json"), {"argv": self.argv(*args), "exit_code": child.returncode})
        if child.returncode != 0:
            raise RuntimeError("transport-command-failed-%d" % self.count)
        return child.stdout

    def start(self, command):
        kwargs = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        return subprocess.Popen(self.argv("shell", "-T", command),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)


class Session:
    def __init__(self, request, mode, transport_type=Transport):
        self.request, self.mode = validate(request), mode
        self.output = Path(request["output_dir"])
        self.stage = "/data/local/tmp/rusty-quest-recenter-" + request["request_id"]
        self.result = initial(request)
        self.transport_type = transport_type
        self.stage_created = False
        self.started = False
        self.final = None

    def status(self):
        publish(self.output / "STATUS.json", self.result)

    def verify_source(self):
        root = Path(__file__).resolve().parents[2]
        def git(*args):
            return subprocess.check_output(["git", "-C", str(root), *args]).decode().strip()
        if (git("rev-parse", "HEAD") != self.request["source"]["revision"] or
                git("rev-parse", "HEAD^{tree}") != self.request["source"]["tree"] or
                git("status", "--porcelain", "--untracked-files=all", "--", "tools/shell_recenter",
                    "apps/shell-recenter-android", "tools/Build-ShellRecenter.ps1")):
            raise ValueError("exact-clean-provider-source-required")
        sources = sorted((root / "apps/shell-recenter-android/src/main/java").rglob("*.java"))
        inventory = "".join(path.relative_to(root).as_posix() + " " + digest(path.read_bytes()) + "\n" for path in sources)
        if not sources or digest(inventory.encode()) != self.request["source"]["source_id"]:
            raise ValueError("exact-java-source-identity-required")

    def guard(self):
        t, r = self.transport, self.request
        values = [t.command("shell", "getprop", "ro.serialno").decode().strip(),
                  t.command("shell", "cat", "/proc/sys/kernel/random/boot_id").decode().strip(),
                  t.command("shell", "getprop", "ro.build.fingerprint").decode().strip(),
                  t.command("shell", "id", "-u").decode().strip(),
                  t.command("shell", "getenforce").decode().strip()]
        if values != [r["canonical_serial"], r["boot_id"], r["firmware"]["fingerprint"], "2000", "Enforcing"]:
            raise ValueError("current-target-boot-firmware-shell-enforcing-required")
        for field, path in (("vrshell_sha256", "/system_ext/priv-app/VrShell/VrShell.apk"),
                            ("framework_sha256", "/system/framework/framework.jar")):
            value = t.command("shell", "sha256sum", path).decode().split()
            if len(value) != 2 or value[0] != r["firmware"][field] or value[1] != path:
                raise ValueError("current-platform-source-drift")

    def launch_command(self):
        r = self.request
        args = ["/system/bin/app_process64", "/", "--nice-name=rusty-quest-shell-recenter", MAIN,
                "--mode", self.mode, "--session", r["request_id"], "--nonce", r["nonce"],
                "--output-dir", self.stage + "/output", "--source-id", r["source"]["source_id"],
                "--expected-boot-id", r["boot_id"],
                "--expected-fingerprint", r["firmware"]["fingerprint"]]
        return ("echo ORIGINAL_PID=$$; cat /proc/$$/stat; exec env CLASSPATH=" +
                shlex.quote(self.stage + "/recenter.jar") + " " + shlex.join(args))

    def observe(self, child):
        lines, errors = [], []
        lock = threading.Lock()
        def drain(stream, name, events):
            try:
                with (self.output / name).open("xb") as log:
                    for line in stream:
                        try:
                            log.write(line)
                            log.flush()
                        except Exception as error:
                            errors.append("raw-log:" + str(error))
                        if events:
                            try:
                                text = line.decode("utf-8", "strict").strip()
                                lines.append(text)
                                if text.startswith("{"):
                                    value = json.loads(text, object_pairs_hook=object_pairs)
                                    if (value.get("schema") != "rusty.quest.shell_recenter.event.v1" or
                                            value.get("session") != self.request["request_id"] or
                                            value.get("original_nonce") != self.request["nonce"]):
                                        raise ValueError("original-event-identity-mismatch")
                                    with lock:
                                        if value.get("execute_attempted"):
                                            self.result["dispatch"]["state"] = "sent"
                                        if value.get("cleanup_held"):
                                            self.result["callback"]["state"] = "unknown"
                                        self.result["evidence"] = {"last_event": value}
                                        self.status()
                            except Exception as error:
                                # Preserve subsequent original callback/error bytes
                                # even when this observer projection fails.
                                errors.append("event-projection:" + str(error))
            except BaseException as error:
                errors.append(str(error))
                # Continue draining the original pipe even after malformed evidence.
                try:
                    while stream.read(65536):
                        pass
                except BaseException:
                    pass
            finally:
                stream.close()
        readers = [threading.Thread(target=drain, args=(child.stdout, "original.stdout", True)),
                   threading.Thread(target=drain, args=(child.stderr, "original.stderr", False))]
        for reader in readers:
            reader.start()
        # No observer deadline may kill, retry or retire an unresolved original.
        while True:
            try:
                code = child.wait()
                break
            except KeyboardInterrupt:
                self.result["cleanup"]["state"] = "held"
                self.result["callback"]["state"] = "unknown"
                self.status()
        for reader in readers:
            reader.join()
        publish(self.output / "PROCESS.json", {"host_pid": child.pid, "native_exit": code,
                                               "both_eof": True, "reaped": True})
        if errors:
            raise ValueError("original-stream-evidence-invalid:" + repr(errors))
        if len(lines) < 2 or not re.fullmatch(r"ORIGINAL_PID=[1-9][0-9]*", lines[0]):
            raise ValueError("original-pid-required")
        pid = int(lines[0].split("=")[1])
        if not lines[1].startswith(str(pid) + " "):
            raise ValueError("original-birth-required")
        events = [json.loads(line, object_pairs_hook=object_pairs) for line in lines if line.startswith("{")]
        finals = [event for event in events if event.get("event") == "FINAL_PUBLISHED"]
        if code not in (0, 1) or len(finals) != 1:
            raise ValueError("genuine-original-final-required")
        return pid, lines[1], finals[0]

    def cleanup_stage(self, owned):
        for path in owned:
            self.transport.command("shell", "rm", path)
        if self.final is not None:
            self.transport.command("shell", "rmdir", self.stage + "/output")
        self.transport.command("shell", "rmdir", self.stage)
        remaining = self.transport.command("shell", "sh", "-c", shlex.quote(
            "if test ! -e " + self.stage + "; then echo ABSENT; else echo PRESENT; fi"))
        if remaining.strip() != b"ABSENT":
            raise ValueError("owned-stage-retirement-unconfirmed")

    def run(self):
        if self.mode not in ("readiness", "recenter"):
            raise ValueError("closed-mode-required")
        # The replay barrier is independent of the caller-selected output path.
        # Keep it after completion and even when the evidence directory is lost.
        if not claim_original(self.request, self.mode):
            return reconcile(self.request)
        try:
            self.output.mkdir()  # Admission lock: never reuse an original, even a completed one.
        except FileExistsError:
            return reconcile(self.request)
        publish(self.output / "REQUEST.json", self.request, exclusive=True)
        publish(self.output / "ACTIVE.json", {"mode": self.mode, "host_pid": os.getpid(),
                "request_sha256": digest(canonical(self.request)), "no_replay": True}, exclusive=True)
        self.status()
        self.transport = self.transport_type(self.request, self.output)
        try:
            self.verify_source()
            artifact = Path(self.request["artifact"]["path"]).read_bytes()
            if len(artifact) != self.request["artifact"]["bytes"] or digest(artifact) != self.request["artifact"]["sha256"]:
                raise ValueError("artifact-pin-drift")
            adb = Path(self.request["adb"]).read_bytes()
            publish(self.output / "TOOLS.json", {"adb_sha256": digest(adb), "provider_sha256": digest(Path(__file__).read_bytes())})
            self.guard()
            self.transport.command("shell", "mkdir", self.stage)
            self.stage_created = True
            self.transport.command("push", self.request["artifact"]["path"], self.stage + "/recenter.jar")
            remote_hash = self.transport.command("shell", "sha256sum", self.stage + "/recenter.jar").decode().split()[0]
            if remote_hash != self.request["artifact"]["sha256"] or digest(Path(self.request["artifact"]["path"]).read_bytes()) != remote_hash:
                raise ValueError("staged-artifact-pin-drift")
            # Fresh target/source checks immediately before the only helper launch.
            self.guard()
            self.verify_source()
            if digest(Path(self.request["adb"]).read_bytes()) != digest(adb):
                raise ValueError("adb-input-drift")
            self.result["dispatch"]["state"] = "intent_recorded" if self.mode == "recenter" else "not_sent"
            self.result["original"] = {"state": "active", "retired": False}
            self.result["cleanup"]["state"] = "held"
            self.status()
            publish(self.output / "LAUNCH_INTENT.json", {"command": self.launch_command(),
                    "request_sha256": self.result["request_sha256"], "mode": self.mode}, exclusive=True)
            self.started = True  # Any launch exception remains uncertain until original evidence exists.
            child = self.transport.start(self.launch_command())
            try:
                publish(self.output / "STARTED.json", {"host_pid": child.pid}, exclusive=True)
            except Exception:
                # Even a host-journal failure cannot abandon a launched original.
                self.result["host_journal_warning"] = "started-record-unavailable"
            pid, birth, published = self.observe(child)
            ref = published["final"]
            if ref["path"] != self.stage + "/output/FINAL.json":
                raise ValueError("owned-final-path-required")
            data = self.transport.command("exec-out", "cat", ref["path"])
            if len(data) != ref["bytes"] or digest(data) != ref["sha256"]:
                raise ValueError("original-final-pin-mismatch")
            (self.output / "FINAL.json").write_bytes(data)
            final = json.loads(data, object_pairs_hook=object_pairs)
            if (final.get("schema") != "rusty.quest.shell_recenter.event.v1" or
                    final.get("session") != self.request["request_id"] or
                    final.get("original_nonce") != self.request["nonce"] or
                    final.get("source_id") != self.request["source"]["source_id"] or
                    final.get("target_package") != TARGET or final.get("function_id") != FUNCTION or
                    final.get("cleanup_held") is not False):
                raise ValueError("original-final-identity-required")
            attempted = final.get("execute_attempted") is True
            callback = final.get("original_callback_received") is True
            outcome = str(final.get("outcome", "UNKNOWN"))
            originals = {"ORIGINAL_RESULT", "ORIGINAL_ERROR", "ORIGINAL_CALLBACK_UNREADABLE"}
            noexec = {"READINESS_READY", "READINESS_UNAVAILABLE", "PREEXEC_ERROR_NO_EXECUTE",
                      "READ_DEADLINE_NO_EXECUTE", "INTENT_PUBLISHED_DEADLINE_NO_EXECUTE"}
            if (any(type(final.get(key)) is not bool for key in
                    ("execute_attempted", "original_callback_received", "intent_published")) or
                    type(final.get("pid")) is not int or final["pid"] != pid or
                    type(final.get("uid")) is not int or final["uid"] != 2000 or
                    published.get("pid") != pid or published.get("uid") != 2000 or
                    outcome not in originals | noexec or
                    (outcome in originals and not (attempted and callback)) or
                    (outcome in noexec and (attempted or callback)) or
                    (attempted and not final["intent_published"]) or
                    (outcome == "READINESS_READY" and self.mode != "readiness")):
                raise ValueError("original-final-state-invariants-required")
            if attempted and not callback:
                raise ValueError("pending-original-cannot-retire")
            if attempted and final.get("boot_id") != self.request["boot_id"]:
                raise ValueError("original-execution-boot-mismatch")
            if self.mode == "readiness" and attempted:
                raise ValueError("readiness-must-not-dispatch")
            state = self.transport.command("shell", "sh", "-c", shlex.quote(
                "if test -e /proc/%d/stat; then cat /proc/%d/stat; else echo ABSENT; fi" % (pid, pid)))
            if state.strip() != b"ABSENT":
                raise ValueError("original-process-absence-unconfirmed")
            self.guard()
            self.final = final
            self.result["dispatch"]["state"] = "sent" if attempted else "not_sent"
            success = outcome == "ORIGINAL_RESULT"
            ready = outcome == "READINESS_READY" or success or callback
            self.result["availability"].update(status="ready" if ready else "unavailable", reason=outcome)
            self.result["callback"] = {"state": ("success" if success else "error") if callback else "not_received"}
            if callback and not success:
                self.result["callback"]["error"] = final.get("error", final.get("details", outcome))
            owned = [self.stage + "/recenter.jar", self.stage + "/output/FINAL.json"]
            if final["intent_published"]:
                intent_ref = published.get("intent")
                if (not isinstance(intent_ref, dict) or set(intent_ref) != {"path", "bytes", "sha256"} or
                        intent_ref["path"] != self.stage + "/output/EXECUTE_INTENT.json"):
                    raise ValueError("original-intent-pin-required")
                intent = self.transport.command("exec-out", "cat", intent_ref["path"])
                (self.output / "EXECUTE_INTENT.json").write_bytes(intent)
                if len(intent) != intent_ref["bytes"] or digest(intent) != intent_ref["sha256"]:
                    raise ValueError("original-intent-pin-mismatch")
                intent_value = json.loads(intent, object_pairs_hook=object_pairs)
                bindings = {"schema": "rusty.quest.shell_recenter.event.v1", "event": "EXECUTE_INTENT",
                            "session": self.request["request_id"], "original_nonce": self.request["nonce"],
                            "source_id": self.request["source"]["source_id"], "boot_id": self.request["boot_id"],
                            "target_package": TARGET, "function_id": FUNCTION, "pid": pid, "uid": 2000}
                if any(intent_value.get(key) != value for key, value in bindings.items()):
                    raise ValueError("original-intent-identity-mismatch")
                owned.append(self.stage + "/output/EXECUTE_INTENT.json")
            self.cleanup_stage(owned)
            self.result["original"] = {"state": "retired", "retired": True}
            self.result["cleanup"]["state"] = "complete"
            self.result["evidence"] = {"final": final, "original_pid": pid, "original_birth": birth,
                                       "both_eof": True, "reaped": True, "stage_absent": True}
        except Exception as error:
            self.result["error"] = {"type": type(error).__name__, "message": str(error)[:4096]}
            if not self.started:
                self.result["availability"].update(status="unavailable", reason=str(error)[:512])
                if self.stage_created:
                    try:
                        # No helper was launched; only this create-new namespace
                        # and its single staged artifact can belong to the run.
                        self.transport.command("shell", "rm", "-f", self.stage + "/recenter.jar")
                        self.cleanup_stage([])
                        self.stage_created = False
                    except Exception as cleanup_error:
                        self.result["cleanup_error"] = str(cleanup_error)[:4096]
                        self.result["cleanup"]["state"] = "held"
                if not self.stage_created:
                    self.result["original"] = {"state": "retired", "retired": True}
                    self.result["cleanup"]["state"] = "complete"
            else:
                if self.result["dispatch"]["state"] == "intent_recorded":
                    self.result["dispatch"]["state"] = "unknown"
                self.result["original"] = {"state": "unknown", "retired": False}
                self.result["cleanup"]["state"] = "held"
        self.status()
        publish(self.output / "RESULT.json", self.result, exclusive=True)
        if self.result["original"]["retired"] and self.result["cleanup"]["state"] == "complete":
            (self.output / "ACTIVE.json").unlink()
        return self.result


def main(argv=None):
    # A console observer interruption cannot terminate the effect owner. The
    # original exits only after its genuine result/error and normal retirement.
    if threading.current_thread() is threading.main_thread():
        signal.signal(signal.SIGINT, signal.SIG_IGN)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("readiness", "recenter", "reconcile"))
    parser.add_argument("--request", required=True)
    args = parser.parse_args(argv)
    request = validate(read_json(args.request))
    result = reconcile(request) if args.mode == "reconcile" else Session(request, args.mode).run()
    print(json.dumps(result, sort_keys=True), flush=True)
    return 0 if (result["cleanup"]["state"] == "complete" and result["availability"]["status"] == "ready"
                 and result["callback"]["state"] not in ("error", "unknown")) else 1


if __name__ == "__main__":
    raise SystemExit(main())
