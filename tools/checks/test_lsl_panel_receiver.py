"""Exercise the actual LSL receiver, real org.json and bounded effect doubles.

No Android/device/native-runtime evidence is implied. Requires an existing org.json
jar; never downloads dependencies. Includes a compiling damaged persistence case.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile

PACKAGE = "io/github/mesmerprism/rustyquest/native_renderer"
RECEIVER = Path("apps/native-renderer-android/panel-modules/lsl/src/main/java") / PACKAGE / "LslPanelCommandReceiver.java"
FIXTURE = Path("apps/native-renderer-android/tests/java") / PACKAGE / "LslPanelCommandReceiverTest.java"
STUBS = {
    "android/app/Activity.java": "package android.app; public class Activity { public static final int RESULT_OK=-1,RESULT_CANCELED=0; }",
    "android/content/Context.java": "package android.content; public class Context { public Context getApplicationContext(){return this;} }",
    "android/content/Intent.java": """package android.content;
public class Intent { private final String action; private final java.util.Map<String,Object> extras=new java.util.HashMap<>();
public Intent(String action){this.action=action;} public String getAction(){return action;}
public boolean hasExtra(String key){return extras.containsKey(key);}public String getStringExtra(String key){Object value=extras.get(key);return value instanceof String?(String)value:null;} public Intent putExtra(String key,String value){extras.put(key,value);return this;} public Intent putExtra(String key,int value){extras.put(key,value);return this;} }""",
    "android/content/BroadcastReceiver.java": """package android.content;
public abstract class BroadcastReceiver { private boolean ordered=true;private int code;private String data;
public abstract void onReceive(Context context,Intent intent); public boolean isOrderedBroadcast(){return ordered;}
public void setResultCode(int value){code=value;}public void setResultData(String value){data=value;}
public void testOrdered(boolean value){ordered=value;}public int testResultCode(){return code;}public String testResultData(){return data;} }""",
    "android/util/Base64.java": """package android.util;
public final class Base64 {public static final int NO_WRAP=2,NO_PADDING=1;
public static byte[] decode(String value,int flags){return java.util.Base64.getDecoder().decode(value);}
public static String encodeToString(byte[] value,int flags){return (flags&NO_PADDING)!=0?java.util.Base64.getEncoder().withoutPadding().encodeToString(value):java.util.Base64.getEncoder().encodeToString(value);} }""",
    PACKAGE + "/ControlPanelActivity.java": """package io.github.mesmerprism.rustyquest.native_renderer;
final class ControlPanelActivity {static int calls;static String raw,response;static boolean noLink;
static String applyLslTransportCommandFromOwner(String command){calls++;raw=command;if(noLink)throw new UnsatisfiedLinkError("fixture");return response;} }""",
    PACKAGE + "/LslPanelConfigStore.java": """package io.github.mesmerprism.rustyquest.native_renderer;
final class LslPanelConfigStore {static int saves,resets;static String saved;static boolean saveResult,resetResult;
static boolean save(android.content.Context context,org.json.JSONObject config){saves++;if(saveResult)saved=config.toString();return saveResult;}
static boolean reset(android.content.Context context){resets++;if(resetResult)saved="default-next-launch-config";return resetResult;} }""",
    PACKAGE + "/LslMulticastLockManager.java": """package io.github.mesmerprism.rustyquest.native_renderer;
final class LslMulticastLockManager {static int calls;static boolean enabled;
static void setFromCli(android.content.Context context,boolean value){calls++;enabled=value;} }""",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--json-jar", type=Path, required=True)
    parser.add_argument("--javac", default=shutil.which("javac"))
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    jar = args.json_jar.resolve(strict=True)
    with zipfile.ZipFile(jar) as archive:
        if "org/json/JSONObject.class" not in archive.namelist():
            raise ValueError("Existing real org.json jar required")
    if not args.javac or not args.java:
        raise ValueError("Existing javac and java required")
    output = args.output_root.resolve()
    output.mkdir(parents=False, exist_ok=False)
    inputs = [repo / RECEIVER, repo / FIXTURE, Path(__file__).resolve(), jar]
    before = {str(path): digest(path) for path in inputs}
    (output / "SOURCE_INPUTS.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
    commands = []
    started = time.time()

    def run(command, name):
        begin = time.time()
        child = subprocess.Popen(command, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stdout, stderr = child.communicate()
        (output / (name + ".stdout.log")).write_text(stdout, encoding="utf-8")
        (output / (name + ".stderr.log")).write_text(stderr, encoding="utf-8")
        commands.append(dict(command=[str(part) for part in command], pid=child.pid,
                             exit_code=child.returncode, started_unix=begin,
                             ended_unix=time.time(), seconds=time.time() - begin))
        return child.returncode, stdout, stderr

    passed = False
    try:
        stubroot = output / "stub"
        for relative, text in STUBS.items():
            target = stubroot / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        stubs = sorted(stubroot.rglob("*.java"))
        for variant in ("actual", "damaged-runtime-persists"):
            classes = output / variant / "classes"
            classes.mkdir(parents=True)
            receiver = repo / RECEIVER
            if variant != "actual":
                text = receiver.read_text(encoding="utf-8")
                needle = 'if ("persistent".equals(scope) && acceptedConfig != null) {'
                if text.count(needle) != 1:
                    raise ValueError("Damaged fixture seam changed; review required")
                receiver = output / variant / "LslPanelCommandReceiver.java"
                receiver.write_text(text.replace(needle, "if (acceptedConfig != null) {", 1), encoding="utf-8")
            code, _, _ = run([args.javac, "--release", "8", "-encoding", "UTF-8", "-cp", str(jar),
                              "-d", str(classes), *map(str, stubs), str(receiver), str(repo / FIXTURE)], variant + "-compile")
            if code:
                raise RuntimeError(variant + " must compile; inspect retained compiler log")
            code, stdout, stderr = run([args.java, "-cp", str(classes) + os.pathsep + str(jar),
                                       PACKAGE.replace("/", ".") + ".LslPanelCommandReceiverTest"], variant + "-test")
            if variant == "actual" and (code or "LslPanelCommandReceiverTest PASS" not in stdout):
                raise RuntimeError("Actual receiver fixture failed")
            if variant != "actual" and (not code or "runtime-only must not persist" not in stderr):
                raise RuntimeError("Compiling damaged runtime-persistence fixture was not detected")
        if before != {str(path): digest(path) for path in inputs}:
            raise RuntimeError("Source changed during qualification")
        passed = True
    finally:
        result = dict(passed=passed, source_stable=before == {str(path): digest(path) for path in inputs},
                      elapsed_seconds=time.time() - started, commands=commands, device_effects=0,
                      scope="Actual receiver; real org.json; bounded Android/native/store/lock doubles; no native or Android runtime acceptance")
        (output / "RESULT.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(dict(passed=passed, receipt=str(output / "RESULT.json"))))


if __name__ == "__main__":
    main()
