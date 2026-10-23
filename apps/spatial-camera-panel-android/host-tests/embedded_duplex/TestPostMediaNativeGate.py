"""Production Java projection -> production native fresh-history guard, no devices.

Uses actual replay snapshot types from a caller-selected host-mapped Android crate.
The source-selected native guard and bootstrap restored calculation are unchanged.
Diagnostic marks are inert; the unrelated signed-stopped verifier is an explicit
unreachable boundary. This is not full native/authority/SDK admission coverage.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True


def rust_function(source, name):
    start = source.index("pub(crate) fn " + name + "(")
    begin = source.index("{", start)
    depth = 0
    # The selected function has no braces in string literals except json! objects;
    # count lexical braces outside strings so source extraction stays exact.
    quoted = escaped = False
    for end in range(begin, len(source)):
        char = source[end]
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
                return source[start:end + 1]
    raise AssertionError("unclosed native function")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--classes", required=True)
    parser.add_argument("--json-jar", required=True)
    parser.add_argument("--mapped-android", required=True)
    parser.add_argument("--cargo-target-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)

    def run(label, argv, **kwargs):
        result = subprocess.run(argv, cwd=root, capture_output=True, **kwargs)
        (output / (label + ".stdout")).write_bytes(result.stdout)
        (output / (label + ".stderr")).write_bytes(result.stderr)
        assert result.returncode == 0, (label, result.returncode, result.stderr.decode(errors="replace"))

    emitted = output / "java-transaction"
    run("java", ["java", "-Drusty.quest.postMedia.output=" + str(emitted), "-cp",
                 str(Path(args.classes).resolve()) + os.pathsep + str(Path(args.json_jar).resolve()),
                 "io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexPostMediaAbandonmentHostTest"])
    native = root / "native-receipt/src/embedded_duplex"
    # root is apps/spatial-camera-panel-android, not the repository root.
    guard_path = native / "remote_owner_close.rs"
    host_path = native / "runtime_host.rs"
    guard_source, host_source = guard_path.read_text(), host_path.read_text()
    function = rust_function(guard_source, "fresh_setup_receiver_history_observed")
    begin = host_source.index("    let restored_owner_replay = ")
    calculation = host_source[begin:host_source.index(";", begin) + 1]
    crate = output / "native-guard"
    (crate / "src").mkdir(parents=True)
    mapped = str(Path(args.mapped_android).resolve()).replace("\\", "/")
    (crate / "Cargo.toml").write_text('[package]\nname="post-media-native-guard"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\nserde_json="1"\nrusty-quest-media-stream-android={path=' + json.dumps(mapped) + '}\n')
    harness = r'''
use rusty_quest_media_stream_android::{OwnerDispatchReplaySnapshot, ProductActivationReplaySnapshot};
use serde_json::{json, Value};
struct Bootstrap { replay: OwnerDispatchReplaySnapshot, activation_replay: ProductActivationReplaySnapshot }
enum NativeGuard { ReceiverRestoredHistory, ReceiverActivationWithoutHistory }
enum NativeReason { PredicateRejected }
struct NativeTrace<'a>(std::marker::PhantomData<&'a ()>);
impl NativeTrace<'_> { fn mark(&self, _:NativeGuard, _:NativeReason){} }
fn verify_receiver_history_observed(_: &OwnerDispatchReplaySnapshot, _: &ProductActivationReplaySnapshot,
    _: &[u8;32], _: &str, _:u64, _:bool, _: &NativeTrace<'_>)->Result<Value,String>{
    panic!("signed-stopped verifier outside this bounded guard control")
}
fn restored(bootstrap:&Bootstrap)->bool { CALCULATION restored_owner_replay }
FUNCTION
fn main(){
    let root=std::path::PathBuf::from(std::env::args().nth(1).unwrap());
    let load=|prefix:&str|->Bootstrap{Bootstrap{
        replay:serde_json::from_str(&std::fs::read_to_string(root.join(format!("{}-owner.json",prefix))).unwrap()).unwrap(),
        activation_replay:serde_json::from_str(&std::fs::read_to_string(root.join(format!("{}-activation.json",prefix))).unwrap()).unwrap(),
    }};
    let raw=load("raw");let projected=load("projected");let trace=NativeTrace(std::marker::PhantomData);
    let call=|b:&Bootstrap,flag:bool,generation:u64|fresh_setup_receiver_history_observed(&b.replay,&b.activation_replay,&[0;32],"MODELED",generation,flag,&trace);
    assert!(restored(&raw));assert!(call(&raw,restored(&raw),70).is_err());
    assert!(!restored(&projected));let fresh=call(&projected,restored(&projected),70).unwrap();
    assert_eq!(fresh["state"],"never_started");assert_eq!(fresh["physical_cleanup_claimed"],false);
    assert!(call(&projected,true,70).is_err());assert!(call(&projected,false,0).is_err());
    let mut owner=load("projected");owner.replay.pending_request_sha256.insert("new".into(),"b".repeat(64));
    assert!(restored(&owner));assert!(call(&owner,false,70).is_err());
    let mut activation=load("projected");activation.activation_replay.pending_request_sha256.insert("new".into(),"c".repeat(64));
    assert!(restored(&activation));assert!(call(&activation,false,70).is_err());
    println!("PASS actual Java projection -> actual native restored calculation + fresh-history guard: old denied, projected empty accepted without cleanup claim, forced restored/zero/new pending denied; no full SDK/device claim");
}
'''.replace("CALCULATION", calculation).replace("FUNCTION", function)
    (crate / "src/main.rs").write_text(harness)
    env = os.environ.copy()
    env["CARGO_TARGET_DIR"] = str(Path(args.cargo_target_dir).resolve())
    run("native", ["cargo", "run", "--offline", "--quiet", "--manifest-path", str(crate / "Cargo.toml"), "--", str(emitted)], env=env)
    pins = [{"path": str(path), "bytes": path.stat().st_size,
             "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in (guard_path, host_path)]
    (output / "RESULT.json").write_text(json.dumps({"passed": True, "native_sources": pins,
        "scope": "production projection and fresh-history guard only", "kernel_actor_absence": "modeled",
        "signed_stopped_verifier_exercised": False, "device_or_full_sdk_claim": False}, indent=2))
    print((output / "native.stdout").read_text())


if __name__ == "__main__":
    main()
