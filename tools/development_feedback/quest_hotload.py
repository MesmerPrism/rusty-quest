#!/usr/bin/env python3
"""Finite, journaled local operator for the two explicitly selected debug hosts.

Uses existing app transports and adb. It builds/installs nothing and never retries
a mutation. An interrupted/unknown command is resolved with `observe`, not resend.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

PACKAGES = {
    "native-openxr": "io.github.mesmerprism.rustyquest.native_renderer.development_hotload",
    "spatial-sdk": "io.github.mesmerprism.rustyquest.spatial_worldspace_hotload",
}
OPERATOR_PACKAGES = {
    "native-openxr": "io.github.mesmerprism.rustyquest.native_renderer.development_hotload_operator",
    "spatial-sdk": "io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.operator",
}
FIELDS = {
    "status": (), "native_status": (), "native_init": ("sha256",),
    "native_activate": ("generation", "sha256", "probe_result"),
    "native_compute": ("input",), "compute": ("input",), "select": ("generation",),
    "native_shader_activate": ("generation", "vertex_sha256", "fragment_sha256"),
    "activate": ("generation", "sha256", "probe_input", "probe_counter", "probe_initial", "probe_result", "probe_expected"),
}
ID = re.compile(r"[a-z0-9][a-z0-9-]{0,63}\Z")
SESSION = re.compile(r"[A-Za-z0-9-]{1,96}\Z")


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("duplicate JSON key")
        obj[key] = value
    return obj


def decode(data):
    return json.loads(data, object_pairs_hook=unique_object)


def validate(command):
    if set(command) != {"schema", "request_id", "method", "arguments"}:
        raise ValueError("exact command fields required")
    if command["schema"] != "rusty.quest.hotload_command.v1" or not ID.fullmatch(command["request_id"]):
        raise ValueError("command identity")
    method, args = command["method"], command["arguments"]
    if method not in FIELDS or not isinstance(args, dict) or set(args) != set(FIELDS[method]):
        raise ValueError("exact method arguments required")
    for key, value in args.items():
        if not isinstance(value, str) or len(value) > 128:
            raise ValueError("bounded string arguments required")
        if "sha256" in key:
            valid = re.fullmatch(r"[0-9a-f]{64}", value)
        elif key in ("probe_initial", "probe_expected"):
            valid = re.fullmatch(r"[0-9a-f]{32}", value)
        elif key == "generation" and method in ("activate", "select"):
            valid = re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", value)
        else:
            valid = re.fullmatch(r"0|-?[1-9][0-9]{0,18}", value)
            valid = valid and -(2**63) <= int(value) < 2**63
            if key == "generation":
                valid = valid and int(value) > 0
        if not valid:
            raise ValueError("invalid argument: " + key)
    return command


def create_json(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def publication_scripts(target, temp, digest):
    # Android app-data SELinux disallows hard links. A create-new, retained
    # identity lock serializes compliant publishers before no-clobber rename.
    # Failed publication consumes the identity; never replay an uncertain write.
    prepare = "set -eu; umask 077; test ! -e " + shlex.quote(target)
    prepare += "; test ! -L " + shlex.quote(target)
    prepare += "; mkdir -- " + shlex.quote(target + ".publish-lock")
    # Keep cat the final exec-in command, as in the proven Android DEX route.
    # Finalization is a separate shell transaction with a remote exit status.
    receive = "umask 077; set -C; cat > " + shlex.quote(temp)
    script = "set -eu; set -C; trap " + shlex.quote("rm -f -- " + shlex.quote(temp)) + " 0"
    script += "; chmod 400 " + shlex.quote(temp)
    script += "; test \"$(sha256sum " + shlex.quote(temp) + " | cut -d ' ' -f 1)\" = " + digest
    script += "; test ! -e " + shlex.quote(target) + "; test ! -L " + shlex.quote(target)
    script += "; mv -n -- " + shlex.quote(temp) + " " + shlex.quote(target)
    script += "; test ! -e " + shlex.quote(temp)
    proof = hashlib.sha256(temp.encode()).hexdigest()
    script += "; printf '%s' " + proof + " > " + shlex.quote(target + ".publish-lock/complete")
    return prepare, receive, script


def publication_script(target, temp, digest):
    """Combined form for actual POSIX filesystem regression checks."""
    return "; ".join(publication_scripts(target, temp, digest))


class Operator:
    def __init__(self, args):
        self.args = args
        selected = getattr(args, "operator_variant", False)
        if type(selected) is not bool:
            raise ValueError("operator variant selection must be boolean")
        self.package = (OPERATOR_PACKAGES if selected else PACKAGES)[args.host]

    def shell(self, command, data=None, check=True):
        # exec-in preserves complete binary stdin with the selected Android
        # run-as route. Its exit is transport-only; publish verifies app bytes.
        route = ["exec-in", command] if data is not None else ["shell", "-T", command]
        result = subprocess.run([self.args.adb, "-s", self.args.serial] + route,
                                input=data, capture_output=True, timeout=15)
        if check and result.returncode:
            raise RuntimeError("adb command failed (" + str(result.returncode) + "): " + result.stderr.decode(errors="replace")[:512])
        return result

    def app(self, script, data=None, check=True):
        return self.shell("run-as " + shlex.quote(self.package) + " sh -c " + shlex.quote(script), data, check)

    def identity(self):
        pid = self.shell("pidof " + shlex.quote(self.package)).stdout.decode().strip()
        if pid != str(self.args.pid):
            raise ValueError("process changed; rediscover instead of replaying")
        descriptor = "files/development-feedback-native/current-session.json" if self.args.host == "native-openxr" else "files/development-feedback/current-session.json"
        current = decode(self.app("cat " + descriptor).stdout)
        if current.get("schema") != "rusty.quest.hotload_current_session.v1" or current.get("host_kind") != self.args.host or current.get("pid") != self.args.pid or current.get("session_id") != self.args.session:
            raise ValueError("host process/owner/session changed")
        if not SESSION.fullmatch(self.args.session):
            raise ValueError("exact session required")

    def observation_path(self, request):
        if not ID.fullmatch(request):
            raise ValueError("invalid request ID")
        if self.args.host == "native-openxr":
            return "files/development-feedback-native/" + self.args.session + "/observation-" + request + ".json"
        return "files/development-feedback-observations/" + request + ".json"

    def observe(self, request):
        result = self.app("cat " + shlex.quote(self.observation_path(request)), check=False)
        if result.returncode:
            return None
        value = decode(result.stdout)
        if value.get("schema") != "rusty.quest.hotload_observation.v1" or value.get("request_id") != request or value.get("host_kind") != self.args.host:
            raise ValueError("observation identity mismatch")
        return value

    def publish(self, target, data):
        # Publish a completed readonly temporary into an identity-locked target.
        # No overwrite, shared external staging directory, or caller-supplied path.
        temp = target + ".receiving-" + uuid.uuid4().hex
        digest = hashlib.sha256(data).hexdigest()
        prepare, receive, finalize = publication_scripts(target, temp, digest)
        self.app(prepare)
        try:
            self.app(receive, data)
            self.app(finalize)
        except BaseException:
            self.app("rm -f -- " + shlex.quote(temp), check=False)
            raise
        # Correlate this exact publication, not an older equal-byte target.
        expected = hashlib.sha256(temp.encode()).hexdigest()
        proof = self.app("cat " + shlex.quote(target + ".publish-lock/complete")).stdout.decode().strip()
        actual = self.app("sha256sum -- " + shlex.quote(target) + "; stat -c '%a:%s' " + shlex.quote(target)).stdout.decode().split()
        if proof != expected or len(actual) != 3 or actual[0] != digest or actual[2] != "400:" + str(len(data)):
            raise RuntimeError("Publication unconfirmed; preserve identity and observe original request")

    def stage(self, source, kind, generation):
        self.identity()
        source = Path(source)
        if source.is_symlink() or not source.is_file():
            raise ValueError("ordinary source file required")
        size = source.stat().st_size
        # The trusted JNI bridge uses the Android bridge's staged-byte cap;
        # native replacement images and shaders retain their smaller cap.
        limit = {"bridge": 64 * 1024 * 1024, "dex": 16 * 1024 * 1024}.get(kind, 4 * 1024 * 1024)
        if not 0 < size <= limit:
            raise ValueError("candidate size limit")
        data = source.read_bytes()
        if self.args.host == "native-openxr":
            if kind not in ("native", "vertex", "fragment") or not re.fullmatch(r"[1-9][0-9]{0,18}", generation):
                raise ValueError("native host requires positive native/shader generation")
            name = "generation-" + generation + ".so" if kind == "native" else "shader-" + generation + (".vert.spv" if kind == "vertex" else ".frag.spv")
            target = "files/development-feedback-native/" + self.args.session + "/" + name
        else:
            root = "files/development-feedback/" + self.args.session
            if kind == "dex" and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", generation):
                target = root + "/incoming/" + generation + ".jar"
            elif kind in ("bridge", "native") and self.args.native_session and SESSION.fullmatch(self.args.native_session):
                root += "/native-feedback/" + self.args.native_session
                if kind == "bridge":
                    target = root + "/bridge.so"
                elif re.fullmatch(r"[1-9][0-9]{0,18}", generation):
                    target = root + "/native-incoming/" + generation + ".so"
                else:
                    raise ValueError("positive native generation required")
            else:
                raise ValueError("unsupported staging kind or missing native session")
        self.publish(target, data)
        return {"target": target, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}

    def send(self, command, journal):
        validate(command)
        if self.args.host == "native-openxr" and command["method"] not in ("native_status", "native_activate", "native_compute", "native_shader_activate"):
            raise ValueError("operation unsupported by native host")
        if self.args.host == "spatial-sdk" and command["method"] == "native_shader_activate":
            raise ValueError("native shader operation unsupported by SDK host")
        self.identity()
        request = command["request_id"]
        if self.observe(request) is not None:
            raise ValueError("request already observed; use observe")
        # This create-new local journal prevents accidental replay after any failure.
        create_json(journal / (request + ".pending.json"), {
            "schema": "rusty.quest.hotload_dispatch.v1", "state": "execution_result_unknown",
            "serial": self.args.serial, "package": self.package, "pid": self.args.pid,
            "session": self.args.session, "command": command})
        if self.args.host == "native-openxr":
            if command["method"] not in ("native_status", "native_activate", "native_compute", "native_shader_activate"):
                raise ValueError("operation unsupported by native host")
            target = "files/development-feedback-native/" + self.args.session + "/command-" + request + ".json"
            self.publish(target, json.dumps(command, separators=(",", ":")).encode())
        else:
            if command["method"] == "native_shader_activate":
                raise ValueError("native shader operation unsupported by SDK host")
            remote = ["content", "call", "--uri", "content://" + self.package + ".probe-control",
                      "--method", "feedback_" + command["method"], "--extra", "request_id:s:" + request]
            for key, value in command["arguments"].items():
                remote += ["--extra", key + ":s:" + value]
            response = self.shell(shlex.join(remote), check=False)
            (journal / (request + ".transport.txt")).write_bytes(response.stdout + response.stderr)
        # Observe finite read-only polls; never repeat the dispatch on timeout.
        for _ in range(40):
            value = self.observe(request)
            if value is not None:
                create_json(journal / (request + ".observation.json"), value)
                return value
            time.sleep(.1)
        return {"request_id": request, "state": "execution_result_unknown", "next": "observe this exact request; do not resend"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--host", choices=PACKAGES, required=True)
    parser.add_argument("--operator-variant", action="store_true", help="Select the fixed coinstalled debug Operator package")
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--session", required=True)
    parser.add_argument("--native-session")
    sub = parser.add_subparsers(dest="action", required=True)
    send = sub.add_parser("send"); send.add_argument("command", type=Path); send.add_argument("--journal", type=Path, required=True)
    stage = sub.add_parser("stage"); stage.add_argument("source"); stage.add_argument("--kind", choices=("native", "dex", "bridge", "vertex", "fragment"), required=True); stage.add_argument("--generation", default="1")
    observe = sub.add_parser("observe"); observe.add_argument("request_id")
    args = parser.parse_args()
    if args.pid < 1:
        parser.error("positive observed PID required")
    operator = Operator(args)
    if args.action == "send":
        args.journal.mkdir(parents=True, exist_ok=True)
        result = operator.send(decode(args.command.read_bytes()), args.journal)
    elif args.action == "stage":
        result = operator.stage(args.source, args.kind, args.generation)
    else:
        result = operator.observe(args.request_id)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
