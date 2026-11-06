"""Non-compiling checks of explicit capability fences; not a linker attestation."""
from pathlib import Path
import os
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"

class ShowcaseSourceClosure(unittest.TestCase):
    def lifecycle_bindings(self, rust=None):
        events = (SRC / "android_events.rs").read_text()
        selection = re.search(r'bridge::call\(app,\s*if resumed\s*\{\s*"([^"]+)"\s*\}\s*else\s*\{\s*"([^"]+)"', events)
        self.assertIsNotNone(selection, "NativeActivity resume/pause dispatch must remain explicit")
        names = set(selection.groups()) | set(re.findall(r'bridge::call\(app,\s*"([^"]+)"', events))
        self.assertEqual({"resume", "suspend", "shutdown"}, names)
        if rust is None:
            rust = (SRC / "showcase_panel_bridge.rs").read_text()
        java = (ROOT.parent / "panel-modules/showcase/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ShowcaseNativeBridge.java").read_text()
        bindings = {}
        for name in names:
            arm = re.search(r'"' + re.escape(name) + r'"\s*=>\s*\{([^{}]*)\}', rust)
            self.assertIsNotNone(arm, "Missing native lifecycle arm: " + name)
            method = re.search(r'jni_str!\("([^"]+)"\)', arm.group(1))
            signature = re.search(r'jni_sig!\("([^"]+)"\)', arm.group(1))
            self.assertIsNotNone(method)
            self.assertIsNotNone(signature)
            self.assertEqual("()V", signature.group(1))
            self.assertRegex(java, r'public\s+static\s+void\s+' + re.escape(method.group(1)) + r'\s*\(\s*\)')
            bindings[name] = method.group(1)
        return bindings

    def test_actual_native_lifecycle_names_bind_to_java_methods(self):
        self.assertEqual({"resume": "resume", "suspend": "suspend", "shutdown": "shutdown"}, self.lifecycle_bindings())

    def test_lifecycle_contract_detects_a_missing_resume_arm(self):
        rust = (SRC / "showcase_panel_bridge.rs").read_text()
        changed = re.sub(r'"resume"\s*=>\s*\{[^{}]*\}', '', rust, count=1)
        self.assertNotEqual(rust, changed)
        with self.assertRaisesRegex(AssertionError, "Missing native lifecycle arm: resume"):
            self.lifecycle_bindings(changed)

    def test_lifecycle_jni_descriptors_match_explicit_compiled_java(self):
        classes = os.environ.get("SHOWCASE_JAVA_CLASSES")
        javap = os.environ.get("SHOWCASE_JAVAP")
        if not classes or not javap:
            self.skipTest("Set SHOWCASE_JAVA_CLASSES and SHOWCASE_JAVAP for compiled JNI contract validation")
        result = subprocess.run([javap, "-classpath", classes, "-s", "io.github.mesmerprism.rustyquest.native_renderer.ShowcaseNativeBridge"], capture_output=True, text=True, check=True, timeout=30)
        for method in self.lifecycle_bindings().values():
            self.assertRegex(result.stdout, r'public static void ' + re.escape(method) + r'\(\);\s*descriptor:\s*\(\)V')

    def test_real_capability_modules_are_not_selected(self):
        text = (SRC / "lib.rs").read_text()
        for module in ["experiment_session", "experiment_session_runtime", "breath_capture", "breath_monitor", "session_recording_clock", "session_recording_contract", "session_recording_writer", "session_recording_export", "session_recording_inventory", "session_recording_recovery", "native_standalone_terminal_jni", "live_hand_joint_capture", "live_hand_mesh_capture", "display_composite_capture_export", "lsl_panel_runtime", "lsl_transport_bridge", "lsl_android", "lsl_rusty_outlet"]:
            self.assertRegex(text, r'#\[cfg\(not\(feature = "showcase"\)\)\]\s*mod ' + module + r';')
    def test_legacy_panel_jni_is_not_selected(self):
        for module in ["native_renderer_stimulus_panel.rs", "breath_composition_runtime.rs"]:
            text = (SRC / module).read_text()
            for match in re.finditer(r'#\[no_mangle\]', text):
                self.assertIn('#[cfg(not(feature = "showcase"))]', text[max(0,match.start()-60):match.start()])
    def test_selected_adapter_has_no_effect_sinks(self):
        for module in ["showcase_runtime.rs", "showcase_panel_bridge.rs", "showcase_no_capture_export.rs", "showcase_jni.rs", "automatic_breath.rs", "source_clock.rs"]:
            text = (SRC / module).read_text()
            for forbidden in ["std::fs", "OpenOptions", "File::", "session_recording", "experiment_session_runtime", "append_questionnaire", "ConditionAudio", "BreathPhaseAudio"]:
                self.assertNotIn(forbidden, text, (module, forbidden))
    def test_shader_extension_is_explicit_and_old_bank_unchanged(self):
        text = (ROOT / "build.rs").read_text()
        self.assertIn('CARGO_FEATURE_SHOWCASE', text)
        self.assertIn('assert_eq!(showcase, extension_enabled', text)
        self.assertIn('("PRIVATE_PARTICLE_FRAME_EXTENSION_V1", frame_extension_define)', text)
        text = (SRC / "gpu_private_particles.rs").read_text()
        self.assertIn('if cfg!(feature = "showcase") { 2 } else { 0 }', text)

    def test_frame_parameters_are_explicit_and_render_ack_follows_submission(self):
        gpu = (SRC / "gpu_private_particles.rs").read_text()
        self.assertIn("&crate::showcase_runtime::PreparedVisualFrame", gpu)
        for global_read in ["showcase_runtime::frame_extension(", "showcase_runtime::applied_radius_m(", "showcase_runtime::prepare_visual_frame("]:
            self.assertNotIn(global_read, gpu)
        xr = (SRC / "xr_vulkan.rs").read_text()
        self.assertNotIn("note_visual_applied", xr)
        self.assertLess(xr.index('format!("end OpenXR frame: {error}")'), xr.index("showcase_runtime::confirm_visual_frame_submission("))
        self.assertLess(xr.index("showcase_runtime::tick("), xr.index("showcase_runtime::prepare_visual_frame("))

if __name__ == "__main__":
    unittest.main()
