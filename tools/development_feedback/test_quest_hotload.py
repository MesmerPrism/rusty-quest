import copy
import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from quest_hotload import Operator, create_json, decode, publication_script, validate


class CommandContractTests(unittest.TestCase):
    def test_operator_variant_is_closed_and_keeps_original_packages(self):
        for host, original, selected in (
            ("native-openxr", "io.github.mesmerprism.rustyquest.native_renderer.development_hotload",
             "io.github.mesmerprism.rustyquest.native_renderer.development_hotload_operator"),
            ("spatial-sdk", "io.github.mesmerprism.rustyquest.spatial_worldspace_hotload",
             "io.github.mesmerprism.rustyquest.spatial_worldspace_hotload.operator"),
        ):
            self.assertEqual(original, Operator(SimpleNamespace(host=host)).package)
            self.assertEqual(selected, Operator(SimpleNamespace(host=host, operator_variant=True)).package)

    def setUp(self):
        self.command = {"schema": "rusty.quest.hotload_command.v1", "request_id": "real-edit-1",
                        "method": "native_activate", "arguments": {"generation": "1", "sha256": "a" * 64, "probe_result": "13"}}

    def test_exact_closed_command(self):
        validate(self.command)
        for key, value in (("generation", "0"), ("generation", "9223372036854775808"),
                           ("probe_result", "-0"), ("sha256", "A" * 64), ("path", "/tmp/foreign")):
            damaged = copy.deepcopy(self.command)
            damaged["arguments"][key] = value
            with self.assertRaises(ValueError):
                validate(damaged)

    def test_duplicate_decoded_keys_are_rejected(self):
        with self.assertRaises(ValueError):
            decode('{"request_id":"x","request_\\u0069d":"y"}')

    def test_shader_pair_has_exact_identity(self):
        command = dict(self.command, method="native_shader_activate", arguments={
            "generation": "2", "vertex_sha256": "a" * 64, "fragment_sha256": "b" * 64})
        validate(command)
        del command["arguments"]["fragment_sha256"]
        with self.assertRaises(ValueError):
            validate(command)

    def test_unknown_dispatch_journal_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "real-edit-1.pending.json"
            create_json(path, {"state": "execution_result_unknown", "command": self.command})
            original = path.read_bytes()
            with self.assertRaises(FileExistsError):
                create_json(path, {"state": "pretend_not_dispatched"})
            self.assertEqual(original, path.read_bytes())

    def test_stage_bridge_has_separate_limit_from_native_candidates(self):
        operator = Operator(SimpleNamespace(host="spatial-sdk", session="owner-1", native_session="native-1"))
        identities, publications = [], []
        operator.identity = lambda: identities.append(True)
        operator.publish = lambda target, data: publications.append((target, len(data), hashlib.sha256(data).hexdigest()))
        with tempfile.TemporaryDirectory() as root:
            source = Path(root) / "bridge.so"
            # Match the retained trusted JNI bridge: genuinely larger than the
            # native candidate's 4 MiB cap, within the bridge's 64 MiB cap.
            with source.open("wb") as stream:
                stream.truncate(4_316_368)
            result = operator.stage(source, "bridge", "1")
            self.assertEqual("files/development-feedback/owner-1/native-feedback/native-1/bridge.so", result["target"])
            self.assertEqual(4_316_368, result["bytes"])
            self.assertEqual([(result["target"], result["bytes"], result["sha256"])], publications)
            with self.assertRaisesRegex(ValueError, "candidate size limit"):
                operator.stage(source, "native", "1")
            with source.open("wb") as stream:
                stream.truncate(64 * 1024 * 1024 + 1)
            with self.assertRaisesRegex(ValueError, "candidate size limit"):
                operator.stage(source, "bridge", "1")
            self.assertEqual(3, len(identities))
            self.assertEqual(1, len(publications))

    def test_actual_shell_publish_refusal_leaves_no_extra_files(self):
        shell = shutil.which("sh") if os.name != "nt" else str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if not shell or not Path(shell).is_file():
            self.skipTest("POSIX shell unavailable; device shell publication still requires qualification")
        data = b"actual staged bytes"
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as root:
            def publish(temp, expected=digest):
                return subprocess.run([shell, "-c", publication_script("generation-1.so", temp, expected)],
                                      cwd=root, input=data, capture_output=True).returncode
            self.assertEqual(0, publish("first.receiving"))
            for n in range(4):
                self.assertNotEqual(0, publish(str(n) + ".receiving"))
            self.assertEqual(["generation-1.so", "generation-1.so.publish-lock"], sorted(p.name for p in Path(root).iterdir()))
            self.assertEqual(data, (Path(root) / "generation-1.so").read_bytes())
            # Independent failed hash has no retained temporary or destination.
            script = publication_script("generation-2.so", "bad.receiving", "0" * 64)
            result = subprocess.run([shell, "-c", script], cwd=root, input=data, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(["generation-1.so", "generation-1.so.publish-lock", "generation-2.so.publish-lock"], sorted(p.name for p in Path(root).iterdir()))


if __name__ == "__main__":
    unittest.main()
