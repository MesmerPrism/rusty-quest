"""Exercise exact native Prepare serde and signature verification, without Android effects."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def item(source, token):
    start = source.index(token)
    body = source.index("{", start)
    depth = 1
    end = body + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--fixture", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--cargo", required=True)
    args = parser.parse_args()
    root, out = args.repo_root.resolve(), args.output_root.resolve()
    if out.exists() or not out.is_relative_to(root / "target"):
        raise ValueError("create-new ignored target output required")
    native = root / "apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/retained_cleanup_host.rs"
    source = native.read_text(encoding="utf-8")
    prepare = item(source, "struct Prepare {")
    constants = "\n".join(re.findall(r'const \w+DOMAIN: &\[u8\] = b"[^"]*";', source))
    constants = 'const DOMAIN: &[u8] = b"rusty.quest.android.media.retained_cleanup_prepare.v1\\0";\n' + constants
    verify_start = source.index("let mut unsigned = request.clone();", source.index("fn remote_projection("))
    verify_end = source.index("if request.route_grant_id", verify_start)
    verification = source[verify_start:verify_end]
    old_source = subprocess.run(["git", "-C", str(root), "show",
        "0f9f418529b6b319613e59e1e528e053a1aac575:apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/retained_cleanup_host.rs"],
        capture_output=True, text=True, check=True).stdout
    old_prepare = item(old_source, "struct Prepare {")
    harness = '''use serde::{Serialize,Deserialize};
use ed25519_dalek::{Signature,VerifyingKey,SigningKey,Signer};
use rusty_quest_media_stream_android::*;
#[derive(Clone,Serialize,Deserialize)] #[serde(deny_unknown_fields)]
''' + prepare + "\n" + constants + "\n" + item(source, "fn prepare_domain(") + "\n" + item(source, "fn encode<T:") + '''
struct Verifier { remote_key:[u8;32] }
impl Verifier { fn verify(&self,request:&Prepare)->Result<(),String> {
''' + verification + ''' Ok(()) } }
mod legacy { use super::*;
#[derive(Clone,Serialize,Deserialize)] #[serde(deny_unknown_fields)]
''' + old_prepare + '''
pub fn accepts(bytes:&[u8])->bool {serde_json::from_slice::<Prepare>(bytes).is_ok()}
}
#[test] fn actual_native_prepare_serialization_and_verification_cover_ancestry() {
 let fixture:serde_json::Value=serde_json::from_slice(&std::fs::read(std::env::var("RQ_ANCESTRY_FIXTURE").unwrap()).unwrap()).unwrap();
 let mut request=Prepare {schema_id:"rusty.quest.android.media.retained_cleanup_prepare_request.v3".into(),dispatch_id:"fixture.dispatch".into(),
 source_ticket:serde_json::from_value(fixture["ticket"].clone()).unwrap(),requester_id:"fixture.requester".into(),requester_lease_id:"fixture.lease".into(),
 route_grant_id:"fixture.grant".into(),sequence:1,issued_at_ms:1,signer_key_id:"fixture.key".into(),signature_base64:String::new(),
 authority:Some(serde_json::from_value(fixture["cleanup"].clone()).unwrap()),renewal_ancestry:serde_json::from_value(fixture["ancestry"].clone()).unwrap()};
 let key=SigningKey::from_bytes(&[11;32]);let verifier=Verifier{remote_key:key.verifying_key().to_bytes()};
 let mut bytes=prepare_domain(&request.schema_id).unwrap().to_vec();bytes.extend(encode(&request).unwrap());
 request.signature_base64=encode_signature_base64(&key.sign(&bytes).to_bytes());
 verifier.verify(&request).unwrap();
 assert!(!legacy::accepts(&encode(&request).unwrap()),"Old receiver must reject new ancestry; no downgrade fallback");
 request.renewal_ancestry[0].applied=false;assert!(verifier.verify(&request).is_err());request.renewal_ancestry[0].applied=true;
 request.renewal_ancestry.reverse();assert!(verifier.verify(&request).is_err());request.renewal_ancestry.reverse();
 request.renewal_ancestry.clear();assert!(verifier.verify(&request).is_err());
 request.signature_base64.clear();let mut bytes=prepare_domain(&request.schema_id).unwrap().to_vec();bytes.extend(encode(&request).unwrap());
 request.signature_base64=encode_signature_base64(&key.sign(&bytes).to_bytes());verifier.verify(&request).unwrap();
 assert!(legacy::accepts(&encode(&request).unwrap()),"Empty ancestry retains exact legacy shape");
}
'''
    out.mkdir(parents=True)
    (out / "lib.rs").write_text(harness, encoding="utf-8")
    (out / "Cargo.toml").write_text('''[package]
name="retained-cleanup-ancestry-host"
version="0.0.0"
edition="2021"
[workspace]
[lib]
path="lib.rs"
[dependencies]
serde={version="1",features=["derive"]}
serde_json="1"
ed25519-dalek={version="=2.1.1",default-features=false,features=["std"]}
rusty-quest-media-stream-android={path=''' + json.dumps((root / "crates/rusty-quest-media-stream-android").as_posix()) + '''}
rusty-quest-broker-authority={path=''' + json.dumps((root / "crates/rusty-quest-broker-authority").as_posix()) + "}\n", encoding="utf-8")
    import os
    env = os.environ.copy()
    env["RQ_ANCESTRY_FIXTURE"] = str(args.fixture.resolve())
    run = subprocess.run([args.cargo, "test", "--offline", "--manifest-path", str(out / "Cargo.toml")], capture_output=True, text=True, env=env)
    (out / "execution.log").write_text(run.stdout + run.stderr, encoding="utf-8")
    run.check_returncode()
    result = {"status":"passed", "device_calls":0, "production_native_serde_and_verifier_extracted":True,
        "native_source_sha256":hashlib.sha256(native.read_bytes()).hexdigest(),
        "limits":"Real Ed25519 over exact native Prepare type/domain/verification block. Key and surrounding state are host fixtures; no JNI, current requester, exchange, physical cleanup, APK or device execution."}
    (out / "RESULT.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
