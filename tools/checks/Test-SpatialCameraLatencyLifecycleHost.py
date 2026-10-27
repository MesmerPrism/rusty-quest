"""Compile the actual diagnostic module/tests; model only Android clock/geometry leaves.

Requires an existing Kotlin compiler lib directory and JDK; no Gradle, APK or device.
The test annotation is removed in the fixture copy so the actual tests can be
invoked directly without a JUnit runner. kotlin.test assertions stay unchanged.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.dont_write_bytecode = True
parser = argparse.ArgumentParser()
for name in ("repo-root", "output-root", "java-home", "kotlin-lib"):
    parser.add_argument("--" + name, type=Path, required=True)
parser.add_argument("--kotlin-test", type=Path, required=True)
a = parser.parse_args()
root, out = a.repo_root.resolve(), a.output_root.resolve()
if out.exists() or not out.is_relative_to(root / "target"):
    raise ValueError("Fresh fixture-owned target required")
out.mkdir(parents=True)
package = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
app = root / "apps/spatial-camera-panel-android/app/src"
relative = "java/io/github/mesmerprism/rustyquest/spatial_camera_panel"
source = app / "main" / relative / "SpatialCameraLatencyDiagnosticModule.kt"
test = app / "test" / relative / "SpatialCameraLatencyDiagnosticModuleTest.kt"
activity = app / "main" / relative / "SpatialCameraPanelActivity.kt"
activity_text = activity.read_text()
extracts = []


def extract(signature):
    start = activity_text.index(signature)
    opening, closing = ("(", ")") if signature.endswith(" =") else ("{", "}")
    brace = activity_text.index(opening, start)
    depth, end = 1, brace + 1
    while depth:
        depth += (activity_text[end] == opening) - (activity_text[end] == closing)
        end += 1
    body = activity_text[start:end]
    extracts.append(dict(signature=signature, sha256=hashlib.sha256(body.encode()).hexdigest()))
    return body


fixture = root / "tools/checks/fixtures/camera_latency_activity_leaves.kt"
activity_controls = fixture.read_text()
for placeholder, signature in (
    ("// ACTUAL_BINDING", "  private val cameraLatencyDiagnosticModule ="),
    ("// ACTUAL_PROJECTION_LAUNCH", "  private fun runCameraHwbProjectionProbe("),
    ("// ACTUAL_SPATIAL_SHUTDOWN", "  override fun onSpatialShutdown()"),
):
    activity_controls = activity_controls.replace(placeholder, extract(signature))
(out / "ActivityControls.kt").write_text(activity_controls)
test_text = test.read_text()
methods = re.findall(r"@Test\s+fun (\w+)\(", test_text)
(out / source.name).write_text(source.read_text())
(out / test.name).write_text(test_text.replace("import kotlin.test.Test\n", "").replace("  @Test\n", ""))
(out / "Clock.kt").write_text("""package android.os
object SystemClock {
  fun elapsedRealtime() = System.nanoTime() / 1000000
  fun elapsedRealtimeNanos() = System.nanoTime()
}
""")
(out / "Controls.kt").write_text("""package """ + package + """
data class HostVector(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)
data class CameraHwbProjectionPlane(
  val center: HostVector = HostVector(), val targetDistanceMeters: Float = 1f,
  val projectionWidthMeters: Float = 1f, val projectionHeightMeters: Float = 1f,
  val viewerPosition: HostVector = HostVector(), val right: HostVector = HostVector(),
  val up: HostVector = HostVector(), val forward: HostVector = HostVector(),
)
fun activityMarkerToken(s: String) = s
fun activityVectorMarker(v: HostVector) = "fixture"
fun main() {
  val tests = SpatialCameraLatencyDiagnosticModuleTest()
""" + "\n".join("  tests." + name + "()" for name in methods) + """
  activityLifetimeControls()
  println("Actual diagnostic module: " + """ + str(len(methods)) + """ + " parser/lifetime controls passed; modeled clock/geometry; no device proof.")
  println("Actual Activity bindings/launch/shutdown: optional SDK handle capture and both carrier routes passed; modeled SDK/JNI leaves.")
}
""")
libs = sorted(a.kotlin_lib.glob("*.jar"))
stdlib = next(p for p in libs if p.name.startswith("kotlin-stdlib-") and "jdk" not in p.name)
testlib = a.kotlin_test.resolve(strict=True)
classes = out / "classes"
classes.mkdir()
classpath = os.pathsep.join(map(str, (stdlib, testlib)))
commands = [
    ("compile", [str(a.java_home / "bin/java.exe"), "-cp", os.pathsep.join(map(str, libs)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath, "-d", str(classes), *map(str, out.glob("*.kt"))]),
    ("controls", [str(a.java_home / "bin/java.exe"), "-cp", os.pathsep.join((str(classes), classpath)), package + ".ControlsKt"]),
]
(out / "sources.json").write_text(json.dumps([
    dict(source=str(p.relative_to(root)), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in (source, test, activity, fixture)
], indent=2))
(out / "extracts.json").write_text(json.dumps(extracts, indent=2))
for name, command in commands:
    child = subprocess.run(command, capture_output=True, timeout=60)
    (out / (name + ".stdout")).write_bytes(child.stdout)
    (out / (name + ".stderr")).write_bytes(child.stderr)
    (out / (name + ".process.json")).write_text(json.dumps(dict(argv=command, exit_code=child.returncode), indent=2))
    if child.returncode:
        sys.stdout.buffer.write(child.stdout)
        sys.stderr.buffer.write(child.stderr)
        raise SystemExit(child.returncode)
    if name == "controls":
        sys.stdout.buffer.write(child.stdout)
