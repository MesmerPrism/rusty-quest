#!/usr/bin/env python3
"""Execute the production Java signing boundary with native cleanup domains.

The JCA Ed25519 path is real; Android identity loading/JNI and device effects
are not exercised. Domain bytes come from the production Rust constructors.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def method(source, signature):
    if source.count(signature) != 1:
        raise ValueError("unique production method required: " + signature)
    start = source.index(signature)
    brace = source.index("{", start)
    depth, quoted, escaped = 0, None, False
    for offset in range(brace, len(source)):
        char = source[offset]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quoted:
                quoted = None
        elif char in ('"', "'"):
            quoted = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[start:offset + 1]
    raise ValueError("unclosed production method")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--javac", required=True)
    parser.add_argument("--java", required=True)
    args = parser.parse_args()
    root, out = Path(args.root).resolve(), Path(args.output).resolve()
    out.relative_to(root / "target")
    out.mkdir(parents=True, exist_ok=False)
    java_path = root / "apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexIdentity.java"
    rust_path = root / "apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/retained_cleanup_host.rs"
    proof_path = root / "crates/rusty-quest-media-stream-android/src/remote_cleanup_proof.rs"
    source, rust, proof = java_path.read_text(), rust_path.read_text(), proof_path.read_text()
    constants = []
    for name in ("OWNER_DISPATCH_REQUEST_DOMAIN", "OWNER_DISPATCH_RESPONSE_DOMAIN",
                 "PRODUCT_ACTIVATION_DOMAIN", "PRODUCT_ACTIVATION_ACK_DOMAIN", "MAX_AUTHORITY_BYTES"):
        matches = re.findall(r"private static final (?:byte\[\]|int) " + name + r"\s*=.*?;", source, re.S)
        if len(matches) != 1:
            raise ValueError("unique production constant required")
        constants.append(matches[0])
    methods = [method(source, signature) for signature in (
        "static byte[] signExactAuthorityBytes(", "private static byte[] sign(",
        "static boolean hasSupportedAuthorityDomain(", "private static boolean startsWith(",
        "private static String availableEd25519Name(")]
    # Decode the exact Rust byte-literal domains used by both request and response constructors.
    domains = []
    for text in (rust, proof):
        for literal in re.findall(r'const \w+: &\[u8\] = b"(rusty\.quest\.android\.media\.retained_[^"]+)";', text):
            value = bytes(literal, "ascii").decode("unicode_escape")
            if value not in domains:
                domains.append(value)
    if len(domains) != 4:
        raise ValueError("four closed native cleanup prepare domains required")
    domains.sort(key=lambda value: not value.startswith("rusty.quest.android.media.retained_cleanup_prepare.v3\0"))
    domain_rows = ",\n".join(json.dumps(value).replace("\\u0000", "\\0") for value in domains)
    harness = "import java.nio.charset.StandardCharsets; import java.security.*;\nclass CleanupSigningHost {\n" + "\n".join(constants + methods) + r'''
    static class Identity { byte[] seed; PrivateKey privateKey; Identity(PrivateKey key) { privateKey=key; } }
    static class EmbeddedDuplexNative {
        static byte[] ed25519SignAuthorityBytes(byte[] seed, byte[] bytes) {
            throw new AssertionError("JNI seed path is outside this host control");
        }
    }
    static int cases;
    static void reject(Identity id, byte[] bytes) throws Exception {
        try { signExactAuthorityBytes(id, bytes); throw new AssertionError("unsupported bytes signed"); }
        catch (IllegalArgumentException expected) { cases++; }
    }
    public static void main(String[] args) throws Exception {
        KeyPair key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Identity id=new Identity(key.getPrivate());
        String[] domains={DOMAIN_ROWS};
        for (String domain:domains) {
            byte[] bytes=(domain+"{\"source_ticket\":\"host-fixture\"}").getBytes(StandardCharsets.US_ASCII);
            byte[] signed=signExactAuthorityBytes(id,bytes);
            Signature verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(key.getPublic());verifier.update(bytes);
            if(signed.length!=64||!verifier.verify(signed))throw new AssertionError("native domain did not sign");
            cases++;
            bytes[bytes.length-1]^=1;verifier.initVerify(key.getPublic());verifier.update(bytes);
            if(verifier.verify(signed))throw new AssertionError("changed payload verified");cases++;
            reject(id,domain.getBytes(StandardCharsets.US_ASCII));
            reject(id,(domain.replace("\0",".extra\0")+"{}").getBytes(StandardCharsets.US_ASCII));
        }
        for(String bad:new String[]{"rusty.quest.android.media.retained_cleanup_prepare.v5\0{}",
                "rusty.quest.android.media.retained_abort_prepare.v3\0{}",
                "rusty.manifold.peer.common_lan_reciprocal_ed25519_context.v1\0{}",
                "rusty.quest.embedded_duplex.pair_ceremony.v1\0{}", "arbitrary\0{}"}) {
            reject(id,bad.getBytes(StandardCharsets.US_ASCII));
        }
        reject(id,null);reject(id,new byte[0]);reject(id,new byte[MAX_AUTHORITY_BYTES+1]);
        reject(null,(domains[0]+"{}").getBytes(StandardCharsets.US_ASCII));
        System.out.println("PASS "+cases+" production signing controls");
    }
}
'''.replace("DOMAIN_ROWS", domain_rows)
    generated = out / "CleanupSigningHost.java"
    generated.write_text(harness, encoding="utf-8")
    subprocess.run([args.javac, "-d", str(out), str(generated)], check=True)
    run = subprocess.run([args.java, "-cp", str(out), "CleanupSigningHost"], capture_output=True, text=True)
    (out / "execution.log").write_text(run.stdout + run.stderr, encoding="utf-8")
    run.check_returncode()
    pins = [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in (java_path, rust_path, proof_path, generated, Path(args.javac), Path(args.java), Path(__file__))]
    result = {"status": "passed", "tests": 25, "production_java_methods_executed": True,
              "native_domain_sources": [str(rust_path), str(proof_path)], "source_pins": pins,
              "limits": "Exact Java signing/domain methods and Rust constructor domain bytes; real JCA Ed25519 sign/verify. Identity fixture uses a fresh JCA key. No JNI seed signer, Android callback capability, full Rust prepare serialization, remote exchange, lifecycle completion, APK, or device effect."}
    (out / "RESULT.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(run.stdout, end="")


if __name__ == "__main__":
    main()
