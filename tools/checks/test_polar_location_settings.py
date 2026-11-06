"""Run actual full Polar dispatcher and common settings handler with bounded doubles.

The extracted production bodies are retained with hashes. Unused owner operations
throw; no rewritten dispatcher or replacement settings policy is tested.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

PACKAGE = "io/github/mesmerprism/rustyquest/native_renderer"
SOURCE = Path("apps/native-renderer-android/panel-modules/polar/src/main/java") / PACKAGE / "PolarSensorPanel.java"
FIXTURE = Path("apps/native-renderer-android/tests/java") / PACKAGE / "PolarLocationSettingsTest.java"
TOKENS = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]')


def method(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 0
    for token in TOKENS.finditer(text, opening):
        if token.group() == "{":
            depth += 1
        elif token.group() == "}":
            depth -= 1
            if depth == 0:
                return text[start:token.end()]
    raise ValueError("Unbalanced actual method: " + signature)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--javac", default=shutil.which("javac"))
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    if not args.javac or not args.java:
        raise ValueError("Existing javac/java required")
    repo = Path(__file__).resolve().parents[2]
    output = args.output_root.resolve()
    output.mkdir(parents=False, exist_ok=False)
    inputs = [repo / SOURCE, repo / FIXTURE, Path(__file__).resolve()]
    before = {str(path): digest(path) for path in inputs}
    (output / "SOURCE_INPUTS.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
    original = (repo / SOURCE).read_text(encoding="utf-8")
    dispatcher = method(original, "    OperatorCommandStatus handleCommand(String rawCommand)")
    settings = method(original, "    private void openLocationSettings()")
    (output / "actual-dispatcher.java.txt").write_text(dispatcher, encoding="utf-8")
    (output / "actual-settings-handler.java.txt").write_text(settings, encoding="utf-8")
    stub_methods = "\n".join("void " + name + "(){throw new AssertionError(\"unused owner operation\");}" for name in (
        "beginManualConnectionControl", "startScan", "connectSelected", "startSelectedPmd", "startAllPmd", "stopAllPmd", "stopParallelCapture", "stopPmd", "disconnect", "clearCounters"))
    prefix = """package io.github.mesmerprism.rustyquest.native_renderer;
import android.app.Activity;import android.content.Intent;import android.provider.Settings;import java.util.Locale;
final class PolarSensorPanel {
Activity activity;boolean locationSettingsPromptPending;long operatorCommandGeneration;String statusState="initial",detail;
final java.util.List<String> markers=new java.util.ArrayList<>();
static final class OperatorCommandStatus {final long operationGeneration;final String command,dispatchStatus,reasonCode,effectStatus;
OperatorCommandStatus(long generation,String command,String dispatch,String reason,String effect){operationGeneration=generation;this.command=command;dispatchStatus=dispatch;reasonCode=reason;effectStatus=effect;} }
OperatorCommandStatus finishOperatorCommand(long generation,String command,String dispatch,String reason,String effect){return new OperatorCommandStatus(generation,command,dispatch,reason,effect);}
void marker(String text){markers.add(text);}void setStatus(String text){detail=text;}void setStatusState(String state,String text){statusState=state;detail=text;}
String markerToken(String text){return "fixture-diagnostic-token";}
void uiOpenForTest(){openLocationSettings();}
void setSelectedPmdMode(String mode){throw new AssertionError("unused owner operation");}
void setAccPresentationMode(String mode){throw new AssertionError("unused owner operation");}
String startParallelCapture(){throw new AssertionError("unused owner operation");}
"""
    stubs = {
        "android/content/Intent.java": "package android.content;public final class Intent {public final String action;public Intent(String action){this.action=action;}}",
        "android/provider/Settings.java": "package android.provider;public final class Settings {public static final String ACTION_LOCATION_SOURCE_SETTINGS=\"android.settings.LOCATION_SOURCE_SETTINGS\",ACTION_SETTINGS=\"android.settings.SETTINGS\";}",
        "android/app/Activity.java": """package android.app;public final class Activity {private final int failures;public final java.util.List<String> attempts=new java.util.ArrayList<>();public Activity(int failures){this.failures=failures;}public void startActivity(android.content.Intent intent){attempts.add(intent.action);if(attempts.size()<=failures)throw new IllegalStateException("fixture platform failure");}}""",
        PACKAGE + "/NativeRendererSelfKioskApplication.java": """package io.github.mesmerprism.rustyquest.native_renderer;final class NativeRendererSelfKioskApplication {static int begins,ends;static void beginSystemPrompt(android.app.Activity activity){begins++;}static void endSystemPrompt(android.app.Activity activity){ends++;}}""",
    }
    commands = []
    started = time.time()
    passed = False

    def run(command, label):
        begin = time.time()
        child = subprocess.Popen(command, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stdout, stderr = child.communicate()
        (output / (label + ".stdout.log")).write_text(stdout, encoding="utf-8")
        (output / (label + ".stderr.log")).write_text(stderr, encoding="utf-8")
        commands.append(dict(command=[str(value) for value in command], pid=child.pid,
                             exit_code=child.returncode, started_unix=begin, ended_unix=time.time(), seconds=time.time()-begin))
        return child.returncode, stdout, stderr

    try:
        stubroot = output / "stub"
        for relative, text in stubs.items():
            target = stubroot / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        for variant in ("actual", "damaged-skip-prompt"):
            directory = output / variant
            directory.mkdir()
            body = settings
            if variant != "actual":
                needle = "NativeRendererSelfKioskApplication.beginSystemPrompt(currentActivity);"
                if body.count(needle) != 1:
                    raise ValueError("Actual common prompt seam changed")
                body = body.replace(needle, "", 1)
            source = directory / "PolarSensorPanel.java"
            source.write_text(prefix + stub_methods + "\n" + dispatcher + "\n" + body + "\n}", encoding="utf-8")
            classes = directory / "classes"
            classes.mkdir()
            code, _, _ = run([args.javac, "--release", "8", "-encoding", "UTF-8", "-d", str(classes),
                              *map(str, sorted(stubroot.rglob("*.java"))), str(source), str(repo / FIXTURE)], variant + "-compile")
            if code:
                raise RuntimeError(variant + " must compile; retained log identifies failure")
            code, stdout, stderr = run([args.java, "-cp", str(classes), PACKAGE.replace("/", ".") + ".PolarLocationSettingsTest"], variant + "-test")
            if variant == "actual" and (code or "PolarLocationSettingsTest PASS" not in stdout):
                raise RuntimeError("Actual dispatcher/settings fixture failed")
            if variant != "actual" and (not code or "one original prompt begin" not in stderr):
                raise RuntimeError("Compiling damaged shared-handler fixture not detected")
        if before != {str(path): digest(path) for path in inputs}:
            raise RuntimeError("Source changed during qualification")
        passed = True
    finally:
        (output / "RESULT.json").write_text(json.dumps(dict(passed=passed,
            source_stable=before == {str(path): digest(path) for path in inputs}, commands=commands,
            seconds=time.time()-started, device_effects=0, actual_production_bodies=2,
            limits="Bounded Android/effect/unused-operation doubles; no actual settings display, permission grant, BLE, JNI or device claim"), indent=2), encoding="utf-8")
    print(json.dumps(dict(passed=passed, receipt=str(output / "RESULT.json"))))


if __name__ == "__main__":
    main()
