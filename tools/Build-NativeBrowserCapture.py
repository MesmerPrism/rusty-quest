#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Host tests, API 34 compile and D8 sidecar; no APK, ADB or device effects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "io.github.mesmerprism.rustyquest.nativecapture"


def fingerprint(path):
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-home", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--out-dir", type=Path, default=ROOT / "target/native-browser-capture-android")
    args = parser.parse_args()
    if not args.java_home or not args.android_home:
        parser.error("--java-home/JAVA_HOME and --android-home/ANDROID_HOME are required")
    out = args.out_dir.resolve()
    target = (ROOT / "target").resolve()
    if target not in out.parents:
        parser.error("output must be a new directory strictly below this checkout's target/")
    jdk, sdk = Path(args.java_home).resolve(), Path(args.android_home).resolve()
    exe = ".exe" if os.name == "nt" else ""
    java, javac = jdk / ("bin/java" + exe), jdk / ("bin/javac" + exe)
    android = sdk / "platforms/android-34/android.jar"
    d8 = sdk / "build-tools" / args.build_tools / "lib/d8.jar"
    app = ROOT / "apps/native-browser-capture-android"
    sources = sorted((app / "src/main/java").rglob("*.java"))
    tests = sorted((app / "host-tests").glob("*.java"))
    if len(sources) != 5 or len(tests) != 1:
        raise RuntimeError("unexpected native capture source closure")
    inputs = [fingerprint(p) for p in [*sources, *tests, Path(__file__).resolve(), java, javac, android, d8]]
    out.mkdir(parents=True, exist_ok=False)
    for name in ("host", "classes", "dex"):
        (out / name).mkdir()

    def run(label, command):
        result = subprocess.run([str(p) for p in command], capture_output=True, timeout=90)
        (out / (label + ".stdout")).write_bytes(result.stdout)
        (out / (label + ".stderr")).write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError(label + ": " + result.stderr.decode(errors="replace"))
        return result

    pure = [p for p in sources if p.name in ("NativeBrowserOptions.java", "NativeVideoWire.java", "VideoRequestOwner20.java")]
    run("host-compile", [javac, "--release", "8", "-encoding", "UTF-8", "-d", out / "host", *pure, *tests])
    result = run("host-tests", [java, "-cp", out / "host", PACKAGE + ".NativeVideoWireTest", out / "synthetic-wire.bin", out / "synthetic-base64.txt"])
    if result.stdout.count(b"PASS ") != 7:
        raise RuntimeError("expected seven focused host test groups")
    capture = next(p for p in sources if p.name == "NativeBrowserCapture.java").read_text()
    forbidden = ["System.out", ".bindService(", ".registerReceiver(", ".createPackageContext(", "setAccessible", "System.loadLibrary", ".transact("]
    if any(value in capture for value in forbidden):
        raise RuntimeError("native helper violates code-only original-proxy contract")
    if "receiver.peekService(app,new Intent().setComponent(component))" not in capture:
        raise RuntimeError("missing existing-service peek")
    if capture.count("startCapture.invoke(originalProxy,request)") != 1 or capture.count("stopCapture.invoke(originalProxy,request)") != 1:
        raise RuntimeError("expected one original-proxy Start/Stop call site")
    run("android-api34", [javac, "--release", "8", "-encoding", "UTF-8", "-cp", android, "-d", out / "classes", *sources])
    entry = out / "classes" / Path(*PACKAGE.split(".")) / "NativeBrowserCapture.class"
    if not entry.is_file():
        raise RuntimeError("public app_process entry class missing")
    run("android-d8", [java, "-cp", d8, "com.android.tools.r8.D8", "--lib", android, "--min-api", "31", "--output", out / "dex", *sorted((out / "classes").rglob("*.class"))])
    dex = list((out / "dex").glob("*.dex"))
    if len(dex) != 1 or dex[0].name != "classes.dex":
        raise RuntimeError("expected one classes.dex")
    jar = out / "native-browser-capture.jar"
    with zipfile.ZipFile(jar, "x") as archive:
        entry = zipfile.ZipInfo("classes.dex", (1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, dex[0].read_bytes())
    if any(fingerprint(Path(row["path"])) != row for row in inputs):
        raise RuntimeError("input bytes changed during host build")
    receipt = {"schema": "rusty.quest.native_browser_capture.host_build.v1", "status": "HOST_PASS_RUNTIME_UNTESTED", "entry_class": PACKAGE + ".NativeBrowserCapture", "inputs": inputs, "artifact": fingerprint(jar), "host_test_groups": 7, "synthetic_fixture": fingerprint(out / "synthetic-wire.bin"), "api": 34, "min_dex_api": 31, "device_operations": False}
    (out / "host-build.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(result.stdout.decode(), end="")
    print(out / "host-build.json")


if __name__ == "__main__":
    main()
