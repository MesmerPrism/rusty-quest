#!/usr/bin/env python3
"""Run actual Native projection bodies through the whole Java renderer boundary.

Current Own/phase/store leaves are modeled. SourceHold, Gate, PrepareCalls and
both JSON producer bodies are production code. No JNI, SDK or device is run.
"""
import sys
sys.dont_write_bytecode = True
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def block(text, signature):
    start = text.index(signature)
    brace = text.index("{", start)
    depth, quoted, escaped = 0, False, False
    for end in range(brace, len(text)):
        char = text[end]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:end + 1]
    raise ValueError("unclosed actual production block")


def pin(path):
    data = path.read_bytes()
    return {"path": str(path.resolve()), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("output", "json-jar", "android-jar"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    out.relative_to(root / "target")
    out.mkdir(parents=True, exist_ok=False)
    java = root / "apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex"
    native = root / "apps/spatial-camera-panel-android/native-receipt/src/prepared_peer_renderer.rs"
    gate = native.with_name("prepared_renderer_gate.rs")
    fixture = root / "apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexPreparedRendererHostTest.java"
    sources = [native, gate, fixture, java / "EmbeddedDuplexPreparedRenderer.java",
               java / "EmbeddedDuplexPeerRoots.java", java / "EmbeddedDuplexCanonicalJson.java", root / "Cargo.lock"]
    before = [pin(path) for path in sources]
    jar, android = args.json_jar.resolve(strict=True), args.android_jar.resolve(strict=True)
    with zipfile.ZipFile(android) as archive:
        if not {"android/app/Activity.class", "org/json/JSONObject.class"} <= set(archive.namelist()):
            raise ValueError("selected real Android API jar required")
    text = native.read_text(encoding="utf-8")
    parts = [block(text, signature) for signature in (
        "struct Ticket {", "struct Job {", "struct Registration {", "struct OwnReuse {", "struct Store {",
        "fn view(", "fn read_reuse(")]
    host = '''use std::sync::{Arc,Mutex,LazyLock,atomic::AtomicBool,mpsc::Receiver};
use std::thread;
#[path=GATE]mod prepared_renderer_gate;
use prepared_renderer_gate::{SourceHold,PrepareCalls,ReusePhase,Gate,Phase};
type SourceReceipt=();
mod stereo_input_set { #[derive(Clone,Copy)]pub struct SourceEpoch {pub process_generation:u64,pub source_generation:u64} }
'''.replace("GATE", json.dumps(gate.as_posix()))
    host += "#[derive(Clone)]\n" + "\n".join(parts[:4]) + "\n#[derive(Default)]\n" + parts[4]
    host += '''
static STORE:LazyLock<Mutex<Store>>=LazyLock::new(||Mutex::new(Store::default()));
fn reuse_current(_: &OwnReuse)->bool{true}
fn reuse_phase(_: &OwnReuse)->Result<ReusePhase,String>{Ok(ReusePhase::Ready)}
''' + "\n".join(parts[5:]) + '''
fn main(){
 let stop=Arc::new(AtomicBool::new(false));let mut hold=SourceHold::default();hold.acquired=true;
 let calls=Arc::new(PrepareCalls::new(stop.clone()));let _actual_call=calls.enter().unwrap();
 let ticket=Ticket{id:"1".repeat(32),app:65,record:"2".repeat(64),native:60,config:"3".repeat(64),request:format!("sha256:{}","4".repeat(64)),target:format!("sha256:{}","5".repeat(64)),route:9,challenge:7,surface:8,switches:0,width:2048,height:1024,window:1,ordinal:1,prior:None,target_value:serde_json::Value::Null};
 let registration=Registration{id:ticket.id.clone(),identity:serde_json::Value::Null,entered:true,pre_worker_terminal:false,closed:false,hold,calls:calls.clone(),stop:stop.clone()};
 let g=Arc::new(Gate::new(stop.clone()));g.ready((2048,1024)).unwrap();
 let job=Job{ticket:ticket.clone(),gate:g,worker:None,ready:None,joined:false,source_hold_released:false};
 let cold=view(&job,&registration);assert_eq!(cold["state"],"ready");assert_eq!(cold["source_hold_released"],false);
 let mut registration=registration;registration.hold.release(||Ok(())).unwrap();
 let mut s=STORE.lock().unwrap();s.registration=Some(registration);
 s.reuse=Some(OwnReuse{ticket,epoch:stereo_input_set::SourceEpoch{process_generation:100,source_generation:101},claim:4,attempts:1,released:false,committed:false});drop(s);
 let own=read_reuse(&"1".repeat(32)).unwrap().unwrap();assert_eq!(own["state"],"ready");assert_eq!(own["source_hold_released"],true);
 assert_eq!(own["prepare_calls_joined"],false);assert_eq!(cold["prepare_calls_joined"],false);
 println!("{}",serde_json::json!({"own":own,"cold":cold}));
}
'''
    scratch = out / "producer"
    scratch.mkdir()
    (scratch / "main.rs").write_text(host, encoding="utf-8")
    (scratch / "Cargo.toml").write_text('''[package]
name = "prepared-renderer-contract-host"
version = "0.0.0"
edition = "2021"
[workspace]
[dependencies]
serde_json = "1"
[[bin]]
name = "prepared-renderer-contract-host"
path = "main.rs"
''', encoding="utf-8")
    checks = []

    def run(name, argv):
        result = subprocess.run(argv, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (out / (name + ".stdout")).write_bytes(result.stdout)
        (out / (name + ".stderr")).write_bytes(result.stderr)
        checks.append({"name": name, "argv": argv, "exit": result.returncode, "reaped": True, "full_dual_eof": True})
        (out / "CHECKS.json").write_text(json.dumps(checks, indent=2) + "\n", encoding="utf-8")
        if result.returncode:
            raise RuntimeError(name + " failed; actual raw output retained")

    run("native-producer", ["cargo", "run", "--offline", "--manifest-path", str(scratch / "Cargo.toml"),
                            "--target-dir", str(out / "cargo")])
    api = out / "android-api-classes"
    api.mkdir()
    production = [str(java / name) for name in ("EmbeddedDuplexPreparedRenderer.java", "EmbeddedDuplexPeerRoots.java", "EmbeddedDuplexCanonicalJson.java")]
    run("android-api-javac", ["javac", "--release", "17", "-encoding", "UTF-8", "-cp", str(android), "-d", str(api), *production])
    classes = out / "host-classes"
    classes.mkdir()
    run("host-javac", ["javac", "--release", "17", "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes), *production, str(fixture)])
    run("host-contract", ["java", "-cp", str(classes) + ";" + str(jar),
                          "io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexPreparedRendererHostTest",
                          str(out / "native-producer.stdout")])
    unchanged = before == [pin(path) for path in sources]
    result = {"passed": unchanged, "checks": checks, "source_pins": before, "runner": pin(Path(__file__)),
              "json_jar": pin(jar), "android_jar": pin(android), "source_and_workspace_lock_unchanged": unchanged,
              "actual_native_cold_and_own_projection_bodies": True, "whole_production_java_boundary": True,
              "limits": "Host producer state/currentness/phase and cold worker presence are modeled. Actual projection/Gate/SourceHold/PrepareCalls code and complete Java classes execute. Real Android API compilation only; no JNI, SDK, physical liveness, transport, media, cleanup, APK or device qualification."}
    (out / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print((out / "host-contract.stdout").read_text(), end="")
    return 0 if unchanged else 1


if __name__ == "__main__":
    sys.exit(main())
