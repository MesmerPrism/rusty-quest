#!/usr/bin/env python3
"""Pure host tests: no ADB process, APK compilation, or device access."""
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("transfer", Path(__file__).with_name("Stage-ExternalHotloadCandidate.py"))
transfer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transfer)


class FakeAdb:
    def __init__(self, apk, fail=None):
        self.apk, self.fail, self.calls, self.last = apk, fail, [], b""

    def run(self, arguments, data=None):
        self.calls.append((arguments, data))
        if arguments[:3] == ["shell", "pm", "path"]:
            return b"package:/data/app/fixture/base.apk\n"
        if arguments[:2] == ["shell", "sha256sum"]:
            return (self.apk + "  /data/app/fixture/base.apk\n").encode()
        if data is not None:
            self.last = data
            return b""
        if arguments[:2] == ["exec-out", "run-as"]:
            return b"wrong" if self.fail == "readback" else self.last
        if arguments[:3] == ["shell", "content", "call"]:
            if self.fail == "unknown":
                raise TimeoutError("unknown dispatch")
            return b"Bundle[{observed=true, outcome=staged}]"
        raise AssertionError(arguments)


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = "a" * 64
        self.context = {"schema": "rusty.quest.hotload.external_context.v1", "appBuild": self.apk,
                        "session": "12345678-1234-1234-1234-123456789abc", "producerGeneration": "1",
                        "expectedGeneration": "2", "expectedActiveDigest": "b" * 64, "nextRequestId": "3"}
        self.code = b"\x03\x02\x23\x07" * 8
        self.manifest = {key: self.context[key] for key in ("appBuild", "session", "producerGeneration", "expectedGeneration", "expectedActiveDigest")}
        self.manifest.update(schema="rusty.quest.hotload.external_candidate.v1", slot="public-diagnostic-v1",
                             entry="main", target="vulkan1.1", codeBytes=len(self.code), codeSha256=transfer.sha(self.code),
                             abiSha256="c" * 64, requestId="3")
        (self.root / "kernel.spv").write_bytes(self.code)
        (self.root / "context.json").write_text(json.dumps(self.context), encoding="utf-8")
        self.write_manifest()
        self.options = SimpleNamespace(serial="TEST123", package="org.example.probe", candidate_dir=str(self.root),
                                       context_json=str(self.root / "context.json"),
                                       context_sha=transfer.sha((self.root / "context.json").read_bytes()),
                                       expected_apk_sha=self.apk, output=str(self.root / "receipt.json"))
        self.host_receipt = {"schema": "rusty.quest.hotload.external_host_receipt.v1", "codeSha256": transfer.sha(self.code),
                             "codeBytes": len(self.code), "candidateSha256": transfer.sha((self.root / "candidate.json").read_bytes()),
                             "abiSha256": self.manifest["abiSha256"], "sourceSha256": "d" * 64,
                             "disassemblySha256": "e" * 64, "semanticValidation": "spirv-val --target-env vulkan1.1 passed",
                             "tools": {key: {"sha256": "f" * 64} for key in ("glslc", "spirv-val", "spirv-dis")}}
        self.write_host_receipt()

    def write_host_receipt(self):
        path = self.root / "host-receipt.json"
        path.write_text(json.dumps(self.host_receipt), encoding="utf-8")
        self.options.host_receipt_sha = transfer.sha(path.read_bytes())

    def write_manifest(self):
        (self.root / "candidate.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_one_stage_after_exact_two_readbacks(self):
        adb = FakeAdb(self.apk)
        result = transfer.stage(self.options, adb)
        self.assertEqual("stage_response_received", result["phase"])
        self.assertFalse(result["gpuExecutionProof"])
        self.assertEqual(self.options.host_receipt_sha, result["hostValidationEvidence"]["hostReceiptSha256"])
        self.assertEqual("d" * 64, result["hostValidationEvidence"]["sourceSha256"])
        self.assertEqual({key: "f" * 64 for key in ("glslc", "spirv-val", "spirv-dis")}, result["hostValidationEvidence"]["toolSha256"])
        self.assertFalse(result["hostValidationEvidence"]["authenticated"])
        self.assertFalse(result["shaderSemanticSafetyProof"])
        self.assertEqual(7, len(adb.calls))
        self.assertEqual("compute-stage", adb.calls[-1][0][-1])
        self.assertEqual(self.code, adb.calls[2][1])
        self.assertNotIn("activate", str(adb.calls))

    def test_unknown_is_recorded_and_never_replayed(self):
        adb = FakeAdb(self.apk, "unknown")
        with self.assertRaises(TimeoutError):
            transfer.stage(self.options, adb)
        receipt = json.loads(Path(self.options.output).read_text())
        self.assertTrue(receipt["stageInvoked"])
        self.assertEqual("transfer_verified_stage_result_unknown", receipt["phase"])
        count = len(adb.calls)
        with self.assertRaises(FileExistsError):
            transfer.stage(self.options, adb)
        self.assertEqual(count, len(adb.calls))

    def test_bad_readback_never_stages(self):
        adb = FakeAdb(self.apk, "readback")
        with self.assertRaisesRegex(ValueError, "readback"):
            transfer.stage(self.options, adb)
        self.assertNotIn("compute-stage", str(adb.calls))

    def test_wrong_installed_apk_never_transfers(self):
        adb = FakeAdb("d" * 64)
        with self.assertRaisesRegex(ValueError, "installed APK"):
            transfer.stage(self.options, adb)
        self.assertEqual(2, len(adb.calls))

    def test_mutated_code_never_contacts_device(self):
        (self.root / "kernel.spv").write_bytes(b"x" * len(self.code))
        adb = FakeAdb(self.apk)
        with self.assertRaisesRegex(ValueError, "code identity"):
            transfer.stage(self.options, adb)
        self.assertEqual([], adb.calls)

    def test_context_and_manifest_negatives(self):
        cases = [("requestId", "4"), ("requestId", "03"), ("requestId", "9223372036854775808"),
                 ("producerGeneration", 1), ("expectedGeneration", "3"), ("session", "stale"),
                 ("appBuild", "d" * 64), ("codeBytes", True), ("target", "vulkan1.2"), ("extra", "path")]
        original = dict(self.manifest)
        for key, value in cases:
            with self.subTest(key=key, value=value):
                self.manifest = dict(original); self.manifest[key] = value; self.write_manifest()
                adb = FakeAdb(self.apk)
                with self.assertRaises(ValueError):
                    transfer.stage(self.options, adb)
                self.assertEqual([], adb.calls)

    def test_context_hash(self):
        self.options.context_sha = "0" * 64
        with self.assertRaisesRegex(ValueError, "context digest"):
            transfer.stage(self.options, FakeAdb(self.apk))

    def test_duplicate_key(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            transfer.load_json(b'{"x":1,"x":2}')

    def test_host_receipt_hash_mismatch_never_contacts_device(self):
        self.options.host_receipt_sha = "0" * 64
        adb = FakeAdb(self.apk)
        with self.assertRaisesRegex(ValueError, "host receipt digest"):
            transfer.stage(self.options, adb)
        self.assertEqual([], adb.calls)

    def test_host_receipt_binding_negatives(self):
        cases = [("schema", "unknown"), ("codeSha256", "0" * 64), ("codeBytes", True),
                 ("codeBytes", len(self.code) + 4), ("candidateSha256", "0" * 64),
                 ("abiSha256", "0" * 64), ("semanticValidation", "not run"),
                 ("sourceSha256", "invalid"), ("disassemblySha256", "invalid"),
                 ("tools", {}), ("tools", {key: {"sha256": "invalid"} for key in ("glslc", "spirv-val", "spirv-dis")})]
        original = dict(self.host_receipt)
        for key, value in cases:
            with self.subTest(key=key, value=value):
                self.host_receipt = dict(original); self.host_receipt[key] = value; self.write_host_receipt()
                adb = FakeAdb(self.apk)
                with self.assertRaises(ValueError):
                    transfer.stage(self.options, adb)
                self.assertEqual([], adb.calls)

    def test_missing_or_oversized_host_receipt(self):
        path = self.root / "host-receipt.json"
        path.unlink()
        with self.assertRaisesRegex(ValueError, "regular local file"):
            transfer.stage(self.options, FakeAdb(self.apk))
        path.write_bytes(b" " * 262145)
        self.options.host_receipt_sha = transfer.sha(path.read_bytes())
        with self.assertRaisesRegex(ValueError, "local file size"):
            transfer.stage(self.options, FakeAdb(self.apk))

    def make_negative(self, preset):
        # Synthetic framing fixture only; no compiler or validator is executed by this test.
        words = [0x07230203, 0x00010300, 0, 20, 0, (2 << 16) | 17, 1, (4 << 16) | 71, 5, 33, 0]
        original = struct.pack("<" + "I" * len(words), *words)
        offset = 5 if preset == "malformed-instruction" else 10
        before = words[offset]
        after = before & 65535 if preset == "malformed-instruction" else 1
        words[offset] = after
        self.code = struct.pack("<" + "I" * len(words), *words)
        parent_manifest = dict(self.manifest, codeSha256=transfer.sha(original), codeBytes=len(original))
        parent_candidate = json.dumps(parent_manifest).encode()
        source, disassembly = b"synthetic source fixture", b"synthetic disassembly fixture"
        parent_receipt = dict(self.host_receipt, schema="rusty.quest.hotload.external_host_receipt.v1",
                              semanticValidation="spirv-val --target-env vulkan1.1 passed",
                              codeSha256=transfer.sha(original), codeBytes=len(original),
                              candidateSha256=transfer.sha(parent_candidate), sourceSha256=transfer.sha(source),
                              disassemblySha256=transfer.sha(disassembly), abi={},
                              sourceSnapshot={"file": "input.comp", "sha256": transfer.sha(source), "bytes": len(source)})
        parent_bytes = json.dumps(parent_receipt).encode()
        for name, content in (("parent-host-receipt.json", parent_bytes), ("parent-kernel.spv", original),
                              ("parent-candidate.json", parent_candidate), ("parent-kernel.spvasm", disassembly),
                              ("input.comp", source), ("kernel.spv", self.code)):
            (self.root / name).write_bytes(content)
        self.manifest.update(codeSha256=transfer.sha(self.code), codeBytes=len(self.code))
        self.write_manifest()
        self.host_receipt = {
            "schema": "rusty.quest.hotload.external_negative_receipt.v1", "preset": preset,
            "intentionalInvalidCandidate": True, "stageOnly": True, "expectedOutcome": "native_rejection",
            "semanticValidation": "not_run_intentional_rejection", "codeSha256": transfer.sha(self.code),
            "codeBytes": len(self.code), "candidateSha256": transfer.sha((self.root / "candidate.json").read_bytes()),
            "abi": {}, "abiSha256": self.manifest["abiSha256"], "sourceSha256": transfer.sha(source),
            "sourceSnapshot": parent_receipt["sourceSnapshot"],
            "sourceRelationship": "valid_parent_source_with_intentional_binary_mutation", "tools": parent_receipt["tools"],
            "parentHostReceiptFile": "parent-host-receipt.json", "parentHostReceiptSha256": transfer.sha(parent_bytes),
            "parentCodeFile": "parent-kernel.spv", "parentCodeSha256": transfer.sha(original),
            "parentCandidateFile": "parent-candidate.json", "parentCandidateSha256": transfer.sha(parent_candidate),
            "parentDisassemblyFile": "parent-kernel.spvasm", "parentDisassemblySha256": transfer.sha(disassembly),
            "mutation": {"wordOffset": offset, "before": before, "after": after},
            "hostContractRejection": "synthetic fixture rejection", "commands": []}
        self.write_host_receipt()

    def test_named_negative_stage_only_even_if_provider_reports_staged(self):
        for preset in ("malformed-instruction", "wrong-binding"):
            with self.subTest(preset=preset):
                # Reinitialize receipt basis for independent synthetic parent evidence.
                self.host_receipt = dict(self.host_receipt, tools={key: {"sha256": "f" * 64}
                                                                 for key in ("glslc", "spirv-val", "spirv-dis")})
                self.make_negative(preset)
                self.options.output = str(self.root / (preset + ".json"))
                adb = FakeAdb(self.apk)
                result = transfer.stage(self.options, adb)
                self.assertTrue(result["intentionalNegative"])
                self.assertTrue(result["stageOnly"])
                self.assertEqual("rejection", result["expectedNativeOutcome"])
                self.assertEqual("not_run_intentional_rejection", result["hostValidationEvidence"]["semanticValidation"])
                self.assertEqual("compute-stage", adb.calls[-1][0][-1])
                self.assertNotIn("compute-activate", str(adb.calls))

    def test_negative_receipt_closed_and_honest(self):
        self.make_negative("wrong-binding")
        cases = [("preset", "arbitrary"), ("schema", "unknown"), ("intentionalInvalidCandidate", False),
                 ("stageOnly", False), ("expectedOutcome", "activate"), ("semanticValidation", "spirv-val --target-env vulkan1.1 passed"),
                 ("parentHostReceiptSha256", "0" * 64), ("parentCodeSha256", "0" * 64),
                 ("parentCandidateSha256", "0" * 64), ("parentCodeFile", "elsewhere.spv"),
                 ("commands", ["validator passed"]), ("hostContractRejection", ""),
                 ("mutation", {"wordOffset": 9, "before": 33, "after": 1}), ("extra", "bypass")]
        original = dict(self.host_receipt)
        for key, value in cases:
            with self.subTest(key=key, value=value):
                self.host_receipt = dict(original); self.host_receipt[key] = value; self.write_host_receipt()
                adb = FakeAdb(self.apk)
                with self.assertRaises(ValueError):
                    transfer.stage(self.options, adb)
                self.assertEqual([], adb.calls)

    def test_negative_parent_file_tamper(self):
        self.make_negative("malformed-instruction")
        (self.root / "parent-kernel.spv").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "negative parent file identity"):
            transfer.stage(self.options, FakeAdb(self.apk))


if __name__ == "__main__":
    unittest.main()
