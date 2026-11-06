"""Exercise the actual public scene producer; no SDK, private packet or device."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class PublicSceneInputs(unittest.TestCase):
    def test_public_packet_and_create_new_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "public"
            command = [sys.executable, str(Path(__file__).with_name("Prepare-DevelopmentFeedbackInputs.py")), "--output", str(output)]
            made = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(made.returncode, 0, made.stderr)
            manifest = json.loads((output / "assets/workload/manifest.json").read_text())
            self.assertEqual(manifest["schema"], "rusty.quest.spatial_worldspace_hotload.development_scene.v1")
            self.assertEqual((manifest["mainCapacity"], manifest["tracerCapacity"], manifest["handsCount"], manifest["graftsCount"]), (512, 0, 0, 0))
            self.assertEqual((manifest["source"], manifest["backend"], manifest["handSource"]), ("public-procedural", "none", "none"))
            self.assertEqual([row["path"] for row in manifest["assets"]], ["workload/mask.png"])
            for row in manifest["assets"]:
                data = (output / "assets" / row["path"]).read_bytes()
                self.assertEqual(len(data), row["bytes"])
                self.assertEqual(hashlib.sha256(data).hexdigest(), row["sha256"])
                self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
            before = (output / "assets/workload/manifest.json").read_bytes()
            again = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(again.returncode, 0)
            self.assertEqual((output / "assets/workload/manifest.json").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
