#!/usr/bin/env python3
"""Exercise the production native seed signer and prepared metadata codec.

The scratch crate compiles a byte-exact signer copy with one integration test
appended and includes the production prepared metadata codec directly. Java callback domains
are observed from the actual closed signing methods, without Android doubles,
private identity reads, JNI execution or device/media authority.
"""
import sys
sys.dont_write_bytecode = True
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def pin(path):
    raw = path.read_bytes()
    return {"path": str(path), "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def signing_domains(java):
    """Observe only the production fixed native callback signing surface."""
    constants = dict(re.findall(
        r'private static final byte\[\] (\w+)\s*=\s*"([^"\n]+)"\s*\.getBytes\(', java))
    authority = java.split("static boolean hasSupportedAuthorityDomain(byte[] value) {", 1)[1].split("\n    }", 1)[0]
    domains = [bytes(value, "ascii").decode("unicode_escape")
               for value in re.findall(r'startsWith\(value, "([^"\n]+)"', authority)]
    for name in re.findall(r'startsWith\(value, ([A-Z_]+)\)', authority):
        domains.append(bytes(constants[name], "ascii").decode("unicode_escape"))
    for name in ("COMMON_LAN_CONTEXT_DOMAIN", "PAIR_CEREMONY_DOMAIN"):
        domains.append(bytes(constants[name], "ascii").decode("unicode_escape"))
    domains.extend(bytes(value, "ascii").decode("unicode_escape") + "\0"
                   for value in re.findall(r'return signFreshDomain\(identity,bytes,"([^"\n]+)"\);', java))
    if len(domains) != 16 or len(set(domains)) != len(domains):
        raise ValueError("closed Java signing callback inventory changed; review its actual consumers")
    return domains


CODEC_INTEGRATION = r'''
#[cfg(test)]
mod prepared_codec_integration {
    use super::{sign_checked, public_from_seed};
    #[test]
    fn prepared_metadata_frames_use_the_production_seed_signer() {
        use rusty_quest_media_stream_android::{
            decode_prepared_start_frame, encode_prepared_start_frame,
            prepared_start_frame_signing_bytes, AndroidPreparedStartFrameBody,
        };
        use serde_json::json;

        let seed = [0x37; 32];
        let public = public_from_seed(&seed);
        let route = format!("sha256:{}", "5".repeat(64));
        for (kind, payload) in [
            ("view", json!({"view":{"source":"host fixture"}})),
            ("viewed", json!({"initiator_view_sha256":"fixture", "view":{}})),
            ("decoder_prepare", json!({"initiator_view":{}, "responder_view":{}, "sender_receipt":{}})),
            ("decoder_prepared", json!({"initiator_view_sha256":"fixture", "responder_view_sha256":"fixture", "decoder_receipt":{}})),
        ] {
            // The production codec owns the closed metadata envelope. This
            // fixture grants no resource, session, media or cleanup authority.
            let reply = matches!(kind, "viewed" | "decoder_prepared");
            let body = AndroidPreparedStartFrameBody {
                schema: "rusty.quest.embedded_duplex.prepared_start_frame.v1".into(),
                kind: kind.into(),
                request_id: if reply { "2" } else { "1" }.repeat(32),
                sender_preparation_id: "1".repeat(32),
                receiver_preparation_id: (kind != "view").then(|| "2".repeat(32)),
                sender_peer_id: "peer.q".into(),
                receiver_peer_id: "peer.w".into(),
                route_configuration_sha256: route.clone(),
                issued_at_ms: 1,
                payload,
            };
            for bad in ["5".repeat(64),format!("SHA256:{}", "5".repeat(64)),format!("sha256:{}", "A".repeat(64)),format!("sha256:{}", "5".repeat(63))] {
                let mut wrong_route = body.clone();wrong_route.route_configuration_sha256=bad;
                assert!(prepared_start_frame_signing_bytes(&wrong_route).is_err());
            }
            let bytes = prepared_start_frame_signing_bytes(&body).unwrap();
            let signature = sign_checked(&seed, &bytes)
                .expect("production seed signer must accept the typed prepared metadata domain");
            let raw = encode_prepared_start_frame(body.clone(), signature).unwrap();
            let decoded = decode_prepared_start_frame(
                &raw, &public, "peer.q", "peer.w", &route, 1,
            ).unwrap();
            assert_eq!(decoded.kind, kind);
            assert!(decode_prepared_start_frame(
                &raw, &public_from_seed(&[0x38; 32]), "peer.q", "peer.w", &route, 1,
            ).is_err());
            assert!(decode_prepared_start_frame(
                &raw, &public, "peer.q", "peer.w", &format!("sha256:{}", "6".repeat(64)), 1,
            ).is_err());
            let mut changed = body;
            changed.payload["view"] = json!({"changed":true});
            // Preserve a valid closed body for view while changing signed bytes.
            if kind == "view" {
                let changed_raw = encode_prepared_start_frame(changed, signature).unwrap();
                assert!(decode_prepared_start_frame(
                    &changed_raw, &public, "peer.q", "peer.w", &route, 1,
                ).is_err());
            } else {
                changed.payload["unknown_field"] = json!(true);
                assert!(prepared_start_frame_signing_bytes(&changed).is_err());
            }
        }
    }

}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--cargo", default="cargo")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = Path(args.output).resolve()
    out.relative_to(root / "target")
    out.mkdir(parents=True, exist_ok=False)
    native = root / "apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/identity_jni.rs"
    codec = root / "crates/rusty-quest-media-stream-android/src/prepared_start.rs"
    java = root / "apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexIdentity.java"
    bridge = root / "apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/java_bridge.rs"
    before = [pin(p) for p in (native, codec, java, bridge, codec.with_name("prepared_start_tests.rs"), root / "Cargo.lock")]
    java_domains = signing_domains(java.read_text(encoding="utf-8"))
    native_text = native.read_text(encoding="utf-8")
    native_block = re.search(r"const SIGNING_DOMAINS:.*?= \[(.*?)\n\];", native_text, re.S).group(1)
    native_domains = [bytes(value, "ascii").decode("unicode_escape")
                      for value in re.findall(r'b"([^"\n]+)"', native_block)]
    missing = sorted(set(java_domains) - set(native_domains))
    # The older native fresh-frame v1 remains an explicit compatibility domain.
    compatibility = {"rusty.quest.embedded_duplex.fresh_pair_setup_frame.v1\0"}
    unexpected = sorted(set(native_domains) - set(java_domains) - compatibility)
    (out / "DOMAIN_PARITY.json").write_text(json.dumps({
        "java_fixed_domains": java_domains, "native_fixed_domains": native_domains,
        "missing": missing, "unexpected": unexpected,
        "separate_custody_signer": True,
    }, indent=2) + "\n", encoding="utf-8")
    scratch = out / "signer"
    scratch.mkdir()
    (scratch / "Cargo.toml").write_text('''[package]
name = "prepared-start-signing-host"
version = "0.0.0"
edition = "2021"
[workspace]
[dependencies]
ed25519-dalek = { version = "=2.1.1", default-features = false, features = ["std", "zeroize"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
sha2 = "0.10"
[lib]
path = "lib.rs"
''', encoding="utf-8")
    # The signer prefix is byte-exact; only the integration child test is appended.
    copied_signer = scratch / "identity.rs"
    copied_signer.write_bytes(native.read_bytes() + CODEC_INTEGRATION.encode("utf-8"))
    assert copied_signer.read_bytes().startswith(native.read_bytes())
    # Preserve the codec's normal production crate import in the integration test.
    (scratch / "lib.rs").write_text(
        'extern crate self as rusty_quest_media_stream_android;\n'
        + '#[path = ' + json.dumps(codec.as_posix()) + ']\nmod prepared_start;\npub use prepared_start::*;\n'
        + '#[path = ' + json.dumps(copied_signer.as_posix()) + ']\nmod identity;\n', encoding="utf-8")
    argv = [args.cargo, "test", "--offline", "--manifest-path", str(scratch / "Cargo.toml"),
            "--target-dir", str(out / "cargo"), "--", "--test-threads=1"]
    run = subprocess.run(argv, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (out / "rust.stdout").write_bytes(run.stdout)
    (out / "rust.stderr").write_bytes(run.stderr)
    malformed = []
    if run.returncode == 0:
        # The actual signing leaf takes &[u8; 32]. Execute compiler refusal
        # controls against that signature, without modeling Android JNI.
        bad = out / "malformed-seed"
        bad.mkdir()
        manifest = (scratch / "Cargo.toml").read_text(encoding="utf-8").replace(
            'name = "prepared-start-signing-host"', 'name = "prepared-start-malformed-seed-host"')
        (bad / "Cargo.toml").write_text(manifest, encoding="utf-8")
        for length in (31, 33):
            extra = '\n#[cfg(test)] mod malformed_seed { #[test] fn wrong_size() { let _ = super::sign_checked(&[0u8; ' + str(length) + '], b"{}"); } }\n'
            (bad / "lib.rs").write_bytes(native.read_bytes() + extra.encode("ascii"))
            check = subprocess.run([args.cargo, "check", "--offline", "--tests", "--manifest-path",
                                    str(bad / "Cargo.toml"), "--target-dir", str(out / "cargo")],
                                   cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            (out / ("malformed-seed-" + str(length) + ".stdout")).write_bytes(check.stdout)
            (out / ("malformed-seed-" + str(length) + ".stderr")).write_bytes(check.stderr)
            refused = check.returncode != 0 and b"expected an array with a size of 32" in check.stderr
            malformed.append({"synthetic_seed_bytes": length, "refused_by_actual_typed_signature": refused,
                              "compiler_exit": check.returncode})
    current = [pin(Path(row["path"])) for row in before]
    unchanged = before == current
    result = {"passed": run.returncode == 0 and not missing and not unexpected and unchanged
                       and len(malformed) == 2 and all(row["refused_by_actual_typed_signature"] for row in malformed),
              "rust_exit": run.returncode, "argv": argv, "java_native_domain_parity": not missing and not unexpected,
              "malformed_seed_compile_controls": malformed,
              "source_and_workspace_lock_unchanged": unchanged, "source_pins": before,
              "runner": pin(Path(__file__).resolve()), "production_signer_and_codec_executed": True,
              "limits": "Host Ed25519/domain/metadata codec only; no Android/JNI callback, actual identity, transport, SDK, media, cleanup, APK or runtime qualification."}
    (out / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(run.stdout.decode(errors="replace"), end="")
    print(json.dumps({k: v for k, v in result.items() if k not in ("source_pins", "argv", "runner")}, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
