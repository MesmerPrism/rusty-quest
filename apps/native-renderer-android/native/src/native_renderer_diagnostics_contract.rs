//! Public, payload-neutral build policy for renderer diagnostics.
//!
//! Metric definitions and reduction shaders remain private payload concerns.

use std::path::Path;

const RENDERER_FOCUS_STATUS_FILE: &str = "renderer_focus_state.json";
const RENDERER_FOCUS_STAGING_FILE: &str = "renderer_focus_state.next.json";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DiagnosticsLevel {
    Off,
    Basic,
    Detailed,
}

impl DiagnosticsLevel {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Basic => "basic",
            Self::Detailed => "detailed",
        }
    }

    pub(crate) const fn timestamps_enabled(self) -> bool {
        !matches!(self, Self::Off)
    }

    pub(crate) const fn detailed_readback_enabled(self) -> bool {
        matches!(self, Self::Detailed)
    }
}

#[cfg(gpu_diagnostics_off)]
pub(crate) const BUILD_DIAGNOSTICS_LEVEL: DiagnosticsLevel = DiagnosticsLevel::Off;
#[cfg(gpu_diagnostics_basic)]
pub(crate) const BUILD_DIAGNOSTICS_LEVEL: DiagnosticsLevel = DiagnosticsLevel::Basic;
#[cfg(gpu_diagnostics_detailed)]
pub(crate) const BUILD_DIAGNOSTICS_LEVEL: DiagnosticsLevel = DiagnosticsLevel::Detailed;

pub(crate) const DIAGNOSTIC_SCHEMA_V2: u32 = 2;
pub(crate) const DIAGNOSTIC_HEALTH_WINDOW_SAMPLES: usize = 120;

pub(crate) fn build_policy_marker_fields() -> String {
    format!(
        "gpuDiagnosticsSchema=v{} gpuDiagnosticsLevel={} gpuDiagnosticsTimestampPolicy={} gpuDiagnosticsReadbackPolicy={} gpuDiagnosticsHealthWindowSamples={}",
        DIAGNOSTIC_SCHEMA_V2,
        BUILD_DIAGNOSTICS_LEVEL.as_str(),
        BUILD_DIAGNOSTICS_LEVEL.timestamps_enabled(),
        BUILD_DIAGNOSTICS_LEVEL.detailed_readback_enabled(),
        DIAGNOSTIC_HEALTH_WINDOW_SAMPLES,
    )
}

pub(crate) fn compose_marker_fields(parts: &[&str]) -> String {
    parts
        .iter()
        .map(|part| part.trim())
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

pub(crate) fn controller_action_readiness_marker_fields(
    action_set_ready: bool,
    interaction_profile_ready: bool,
    action_ready: bool,
    interaction_profile: &str,
) -> String {
    format!(
        "controllerActionSetReady={} controllerInteractionProfileReady={} controllerActionReady={} controllerInteractionProfile={} controllerPhysicalInputObserved={} handsCannotSubstituteController=true",
        action_set_ready,
        interaction_profile_ready,
        action_ready,
        crate::sanitize(interaction_profile),
        action_ready,
    )
}

#[cfg(test)]
pub(crate) fn marker_keys_are_unique(fields: &str) -> bool {
    let mut keys = std::collections::BTreeSet::new();
    fields
        .split_whitespace()
        .filter_map(|field| field.split_once('=').map(|(key, _)| key))
        .all(|key| keys.insert(key))
}

pub(crate) fn renderer_focus_state_json(
    process_id: u32,
    session_state: &str,
    frame_count: u64,
    submitted: bool,
    updated_at_unix_ms: u64,
) -> String {
    serde_json::json!({
        "schema": "rusty.quest.native_renderer.renderer_focus_state.v1",
        "activity": "android.app.NativeActivity",
        "process_id": process_id,
        "session_state": session_state,
        "frame_count": frame_count,
        "submitted": submitted,
        "updated_at_unix_ms": updated_at_unix_ms,
    })
    .to_string()
}

pub(crate) fn write_renderer_focus_state_file(
    data_path: &Path,
    process_id: u32,
    session_state: &str,
    frame_count: u64,
    submitted: bool,
    updated_at_unix_ms: u64,
) -> std::io::Result<()> {
    let body = renderer_focus_state_json(
        process_id,
        session_state,
        frame_count,
        submitted,
        updated_at_unix_ms,
    );
    let target = data_path.join(RENDERER_FOCUS_STATUS_FILE);
    let staging = data_path.join(RENDERER_FOCUS_STAGING_FILE);
    std::fs::write(&staging, body)?;
    std::fs::rename(staging, target)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn levels_are_closed_and_have_distinct_instrumentation_policies() {
        assert!(!DiagnosticsLevel::Off.timestamps_enabled());
        assert!(DiagnosticsLevel::Basic.timestamps_enabled());
        assert!(!DiagnosticsLevel::Basic.detailed_readback_enabled());
        assert!(DiagnosticsLevel::Detailed.detailed_readback_enabled());
        assert!(build_policy_marker_fields().contains("gpuDiagnosticsSchema=v2"));
    }

    #[test]
    fn marker_key_validator_rejects_duplicate_evidence() {
        assert!(marker_keys_are_unique(&compose_marker_fields(&[
            "status=frame frame=7",
            "ready=true",
        ])));
        assert!(!marker_keys_are_unique(
            "status=frame guideProjectionCoverage=one guideProjectionCoverage=two"
        ));
    }

    #[test]
    fn controller_readiness_producer_has_unique_standalone_fields() {
        let fields = controller_action_readiness_marker_fields(
            true,
            true,
            true,
            "/interaction_profiles/oculus/touch_controller",
        );
        assert!(marker_keys_are_unique(&fields));
        assert_eq!(fields.matches("controllerActionSetReady=").count(), 1);
        assert_eq!(fields.matches("controllerActionReady=").count(), 1);
    }

    #[test]
    fn focus_state_file_is_atomically_staged_and_pid_bound() {
        let unique_suffix = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("system time after Unix epoch")
            .as_nanos();
        let directory = std::env::temp_dir().join(format!(
            "rusty-quest-renderer-focus-state-{}-{unique_suffix}",
            std::process::id(),
        ));
        let _ = std::fs::remove_dir_all(&directory);
        std::fs::create_dir_all(&directory).expect("create isolated test directory");

        write_renderer_focus_state_file(&directory, 4242, "FOCUSED", 73, true, 9001)
            .expect("write renderer focus state");

        let target = directory.join(RENDERER_FOCUS_STATUS_FILE);
        let value: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&target).expect("read renderer focus state"))
                .expect("parse renderer focus state");
        assert_eq!(
            value["schema"],
            "rusty.quest.native_renderer.renderer_focus_state.v1"
        );
        assert_eq!(value["activity"], "android.app.NativeActivity");
        assert_eq!(value["process_id"], 4242);
        assert_eq!(value["session_state"], "FOCUSED");
        assert_eq!(value["frame_count"], 73);
        assert_eq!(value["submitted"], true);
        assert_eq!(value["updated_at_unix_ms"], 9001);
        assert!(!directory.join(RENDERER_FOCUS_STAGING_FILE).exists());

        write_renderer_focus_state_file(&directory, 4243, "VISIBLE", 74, false, 9002)
            .expect("atomically replace renderer focus state");
        let replacement: serde_json::Value = serde_json::from_slice(
            &std::fs::read(&target).expect("read replacement renderer focus state"),
        )
        .expect("parse replacement renderer focus state");
        assert_eq!(replacement["process_id"], 4243);
        assert_eq!(replacement["session_state"], "VISIBLE");
        assert_eq!(replacement["frame_count"], 74);
        assert_eq!(replacement["submitted"], false);
        assert_eq!(replacement["updated_at_unix_ms"], 9002);
        assert!(!directory.join(RENDERER_FOCUS_STAGING_FILE).exists());

        std::fs::remove_dir_all(directory).expect("remove isolated test directory");
    }
}
