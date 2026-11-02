"""Non-compiling checks of explicit capability fences; not a linker attestation."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"

class ShowcaseSourceClosure(unittest.TestCase):
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
