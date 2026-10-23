#!/usr/bin/env python3
"""Explicit serial-scoped app-UID transfer, exact readback, then one stage call.

Requires a pinned compute-info JSON from an already-admitted operator session.
This command never activates, retries, starts ADB, installs, or starts the app.
An existing output receipt is refused, including after an unknown dispatch.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import struct
import subprocess
import sys

HEX = re.compile(r"[a-f0-9]{64}\Z")
DECIMAL = re.compile(r"[1-9][0-9]{0,18}\Z")
FIELDS = set("schema slot codeSha256 codeBytes abiSha256 appBuild session producerGeneration expectedGeneration expectedActiveDigest requestId entry target".split())
INBOX = "files/native-hotload/inbox"
NEGATIVE_FIELDS = set("schema preset intentionalInvalidCandidate stageOnly expectedOutcome semanticValidation codeSha256 codeBytes candidateSha256 abi abiSha256 sourceSha256 sourceSnapshot sourceRelationship tools parentHostReceiptFile parentHostReceiptSha256 parentCodeFile parentCodeSha256 parentCandidateFile parentCandidateSha256 parentDisassemblyFile parentDisassemblySha256 mutation hostContractRejection commands".split())


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def unique_object(pairs):
    out = {}
    for key, value in pairs:
        require(key not in out, "duplicate JSON key")
        out[key] = value
    return out


def load_json(data):
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object)


def bounded_file(path, limit):
    require(path.is_file() and not path.is_symlink(), "regular local file required")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    require(0 < len(data) <= limit, "local file size")
    return data


def validate(candidate_dir, context_path, context_sha, expected_apk):
    require(HEX.fullmatch(context_sha) and HEX.fullmatch(expected_apk), "pinned hash syntax")
    context_bytes = bounded_file(context_path, 8192)
    require(sha(context_bytes) == context_sha, "context digest mismatch")
    context = load_json(context_bytes)
    require(context.get("schema") == "rusty.quest.hotload.external_context.v1", "context schema")
    code = bounded_file(candidate_dir / "kernel.spv", 65536)
    manifest_bytes = bounded_file(candidate_dir / "candidate.json", 8192)
    manifest = load_json(manifest_bytes)
    require(set(manifest) == FIELDS, "manifest fields")
    for key in ("codeSha256", "abiSha256", "appBuild", "expectedActiveDigest"):
        require(isinstance(manifest[key], str) and HEX.fullmatch(manifest[key]), "manifest hash syntax")
    for key in ("producerGeneration", "expectedGeneration", "requestId"):
        value = manifest[key]
        require(isinstance(value, str) and DECIMAL.fullmatch(value) and int(value) <= 9223372036854775807, "manifest decimal")
    require(manifest["schema"] == "rusty.quest.hotload.external_candidate.v1" and
            manifest["slot"] == "public-diagnostic-v1" and manifest["entry"] == "main" and
            manifest["target"] == "vulkan1.1", "manifest contract")
    require(type(manifest["codeBytes"]) is int and manifest["codeBytes"] == len(code) and
            len(code) % 4 == 0 and manifest["codeSha256"] == sha(code), "code identity")
    require(context.get("appBuild") == expected_apk == manifest["appBuild"], "APK context mismatch")
    for key in ("session", "producerGeneration", "expectedGeneration", "expectedActiveDigest"):
        require(isinstance(manifest[key], str) and manifest[key] == context.get(key), "stale context")
    require(manifest["requestId"] == context.get("nextRequestId"), "stale request")
    return code, manifest_bytes, manifest


def valid_host_evidence(receipt, pinned_sha):
    require(receipt.get("schema") == "rusty.quest.hotload.external_host_receipt.v1", "host receipt schema")
    require(receipt.get("semanticValidation") == "spirv-val --target-env vulkan1.1 passed", "host receipt semantic validation")
    require(type(receipt.get("codeBytes")) is int and 0 < receipt["codeBytes"] <= 65536 and receipt["codeBytes"] % 4 == 0,
            "host receipt code size")
    for key in ("sourceSha256", "disassemblySha256", "codeSha256", "candidateSha256", "abiSha256"):
        require(isinstance(receipt.get(key), str) and HEX.fullmatch(receipt[key]), "host receipt evidence hash")
    tools = receipt.get("tools")
    require(isinstance(tools, dict) and set(tools) == {"glslc", "spirv-val", "spirv-dis"}, "host receipt tools")
    for tool in tools.values():
        require(isinstance(tool, dict) and isinstance(tool.get("sha256"), str) and HEX.fullmatch(tool["sha256"]), "host receipt tool hash")
    # Preserve exact provenance without copying local tool/source paths into transfer evidence.
    # A pinned receipt binds evidence; it is not a signature or independent validation.
    return {"hostReceiptSha256": pinned_sha, "sourceSha256": receipt["sourceSha256"],
            "toolSha256": {key: value["sha256"] for key, value in tools.items()},
            "disassemblySha256": receipt["disassemblySha256"], "abiSha256": receipt["abiSha256"],
            "semanticValidation": receipt["semanticValidation"], "authenticated": False}


def validate_host_receipt(candidate_dir, pinned_sha, code, manifest_bytes, manifest):
    require(HEX.fullmatch(pinned_sha), "host receipt pinned hash syntax")
    data = bounded_file(candidate_dir / "host-receipt.json", 262144)
    require(sha(data) == pinned_sha, "host receipt digest mismatch")
    receipt = load_json(data)
    require(receipt.get("codeSha256") == sha(code) and type(receipt.get("codeBytes")) is int and
            receipt["codeBytes"] == len(code), "host receipt code identity")
    require(receipt.get("candidateSha256") == sha(manifest_bytes), "host receipt candidate identity")
    require(receipt.get("abiSha256") == manifest["abiSha256"], "host receipt ABI identity")
    if receipt.get("schema") == "rusty.quest.hotload.external_negative_receipt.v1":
        require(set(receipt) == NEGATIVE_FIELDS, "negative receipt fields")
        require(receipt.get("preset") in {"malformed-instruction", "wrong-binding"}, "negative receipt preset")
        require(receipt.get("intentionalInvalidCandidate") is True and receipt.get("stageOnly") is True and
                receipt.get("expectedOutcome") == "native_rejection", "negative receipt intent")
        require(receipt.get("semanticValidation") == "not_run_intentional_rejection", "negative receipt false validation claim")
        parent_bytes = bounded_file(candidate_dir / "parent-host-receipt.json", 262144)
        require(sha(parent_bytes) == receipt.get("parentHostReceiptSha256"), "negative parent receipt digest")
        parent = load_json(parent_bytes)
        parent_evidence = valid_host_evidence(parent, sha(parent_bytes))
        require(parent["codeSha256"] == receipt.get("parentCodeSha256") and
                parent["candidateSha256"] == receipt.get("parentCandidateSha256") and
                parent["abiSha256"] == receipt["abiSha256"], "negative parent identity")
        require(receipt["codeSha256"] != parent["codeSha256"], "negative code must differ from valid parent")
        require(receipt["commands"] == [] and receipt["sourceRelationship"] == "valid_parent_source_with_intentional_binary_mutation",
                "negative receipt provenance")
        require(receipt["sourceSha256"] == parent["sourceSha256"] and receipt["tools"] == parent["tools"] and
                receipt["abi"] == parent.get("abi"), "negative parent provenance")
        require(isinstance(receipt["hostContractRejection"], str) and 0 < len(receipt["hostContractRejection"]) <= 4096,
                "negative host rejection missing")
        for field, filename, hash_field, parent_hash, limit in (
                ("parentHostReceiptFile", "parent-host-receipt.json", "parentHostReceiptSha256", sha(parent_bytes), 262144),
                ("parentCodeFile", "parent-kernel.spv", "parentCodeSha256", parent["codeSha256"], 65536),
                ("parentCandidateFile", "parent-candidate.json", "parentCandidateSha256", parent["candidateSha256"], 8192),
                ("parentDisassemblyFile", "parent-kernel.spvasm", "parentDisassemblySha256", parent["disassemblySha256"], 4194304)):
            require(receipt[field] == filename and receipt[hash_field] == parent_hash and
                    sha(bounded_file(candidate_dir / filename, limit)) == parent_hash, "negative parent file identity")
        source = bounded_file(candidate_dir / "input.comp", 262144)
        require(receipt["sourceSnapshot"] == parent.get("sourceSnapshot") ==
                {"file": "input.comp", "sha256": sha(source), "bytes": len(source)} and sha(source) == parent["sourceSha256"],
                "negative source snapshot identity")
        original = bounded_file(candidate_dir / "parent-kernel.spv", 65536)
        mutation = receipt["mutation"]
        require(isinstance(mutation, dict) and set(mutation) == {"wordOffset", "before", "after"} and
                all(type(value) is int for value in mutation.values()), "negative mutation descriptor")
        offset, before, after = (mutation[key] for key in ("wordOffset", "before", "after"))
        require(len(original) == len(code) == parent["codeBytes"] and 5 <= offset < len(code) // 4 and
                0 <= before <= 0xffffffff and 0 <= after <= 0xffffffff and before != after, "negative mutation bounds")
        changed = bytearray(original)
        require(struct.unpack_from("<I", original, offset * 4)[0] == before, "negative mutation original")
        struct.pack_into("<I", changed, offset * 4, after)
        require(bytes(changed) == code, "negative mutation exact bytes")
        if receipt["preset"] == "malformed-instruction":
            require(offset == 5 and before >> 16 > 0 and after == before & 65535, "negative malformed preset mutation")
        else:
            words = struct.unpack("<" + "I" * (len(original) // 4), original)
            bindings = []
            at = 5
            while at < len(words):
                count, opcode = words[at] >> 16, words[at] & 65535
                require(count > 0 and at + count <= len(words), "negative parent instruction framing")
                if opcode == 71 and count == 4 and words[at + 2] == 33:
                    bindings.append(at + 3)
                at += count
            require(bindings == [offset] and before == 0 and after == 1, "negative binding preset mutation")
        return {"hostReceiptSha256": pinned_sha, "intentionalNegative": True, "preset": receipt["preset"],
                "stageOnly": True, "expectedNativeOutcome": "rejection", "authenticated": False,
                "semanticValidation": receipt["semanticValidation"], "parentValidEvidence": parent_evidence}
    return valid_host_evidence(receipt, pinned_sha)


class Adb:
    def __init__(self, executable, serial):
        self.executable, self.serial = executable, serial

    def run(self, arguments, data=None):
        result = subprocess.run([self.executable, "-s", self.serial, *arguments], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False)
        require(result.returncode == 0, "ADB transport failed")
        return result.stdout


def stage(options, transport):
    require(re.fullmatch(r"[A-Za-z0-9_.:-]+", options.serial) is not None, "serial syntax")
    require(re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){2,}", options.package) is not None, "package syntax")
    code, manifest_bytes, manifest = validate(Path(options.candidate_dir), Path(options.context_json),
                                             options.context_sha, options.expected_apk_sha)
    host_evidence = validate_host_receipt(Path(options.candidate_dir), options.host_receipt_sha,
                                          code, manifest_bytes, manifest)
    output = Path(options.output)
    receipt = {"schema": "rusty.quest.hotload.external_transfer.v1", "phase": "not_invoked",
               "serial": options.serial, "package": options.package, "appBuild": options.expected_apk_sha,
               "contextSha256": options.context_sha, "codeSha256": sha(code),
               "manifestSha256": sha(manifest_bytes), "requestId": manifest["requestId"],
               "hostValidationEvidence": host_evidence, "shaderSemanticSafetyProof": False,
               "intentionalNegative": host_evidence.get("intentionalNegative", False), "stageOnly": True,
               "expectedNativeOutcome": host_evidence.get("expectedNativeOutcome", "staged"),
               "stageInvoked": False, "gpuExecutionProof": False, "sdkPixelProof": False}
    # Exclusive creation is the operation's local no-replay marker.
    with output.open("x", encoding="utf-8") as stream:
        json.dump(receipt, stream, indent=2)

    def save():
        output.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    try:
        paths = transport.run(["shell", "pm", "path", options.package]).decode("utf-8").splitlines()
        bases = [line.removeprefix("package:") for line in paths if line.endswith("/base.apk")]
        require(len(bases) == 1 and re.fullmatch(r"/data/app/[A-Za-z0-9_/=.+~-]+/base\.apk", bases[0]), "installed base APK path")
        actual = transport.run(["shell", "sha256sum", bases[0]]).decode("ascii").split()[0]
        require(actual == options.expected_apk_sha, "installed APK digest mismatch")
        receipt["installedApkVerified"] = True
        for name, data in (("kernel.spv", code), ("candidate.json", manifest_bytes)):
            # Only fixed literal paths appear in the device script. File data travels on stdin.
            script = ("set -eu; umask 077; test ! -L files/native-hotload; "
                      "test ! -L files/native-hotload/inbox; mkdir -p files/native-hotload/inbox; "
                      f"test ! -L {INBOX}/{name}; cat > {INBOX}/{name}")
            transport.run(["exec-out", "run-as", options.package, "sh", "-c", shlex.quote(script)], data)
            readback = transport.run(["exec-out", "run-as", options.package, "cat", f"{INBOX}/{name}"])
            require(len(readback) == len(data) and sha(readback) == sha(data), "app UID readback mismatch")
        receipt["phase"] = "transfer_verified_stage_result_unknown"
        receipt["stageInvoked"] = True
        save()  # Persist before dispatch; a timeout must never cause an automatic replay.
        response = transport.run(["shell", "content", "call", "--uri",
                                  f"content://{options.package}.probe-control", "--method", "compute-stage"])
        receipt["phase"] = "stage_response_received"
        receipt["providerResponse"] = response.decode("utf-8", errors="replace")
        save()
        return receipt
    except Exception as exc:
        receipt["error"] = type(exc).__name__ + ": " + str(exc)
        save()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("adb", "serial", "package", "candidate-dir", "expected-apk-sha", "context-json", "context-sha", "host-receipt-sha", "output"):
        parser.add_argument("--" + name, required=True)
    options = parser.parse_args()
    try:
        result = stage(options, Adb(options.adb, options.serial))
    except Exception as exc:
        print(f"Transfer stopped: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
