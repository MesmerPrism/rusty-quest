use rusty_quest_hand_adapter::{
    activation_marker as shared_activation_marker, resolve_hand_adapter_activation,
    HandAdapterLockActivationDecision, HandAdapterRuntimeActivationInput,
};

pub(crate) const PROJECT_ID: &str = "spatial-camera-panel";
pub(crate) const FEATURE_ID: &str = "tracked-hand-surface";
const MODULE_ID: &str = "tracked-hand-surface";
pub(crate) const ACCEPTED_PROFILE_ID: &str =
    "profile.quest.spatial_camera_panel.hand_adapter_conformance";
const CONFORMANCE_LOCK_JSON: &str =
    include_str!("../../morphospace/conformance-locks/hand-adapter.feature.lock.json");
const ACCEPTED_LOCK_SHA256: &str =
    "FFB07E39C7290FDE8EBB154EFB94985CF4628CB2E4D098A81A5611849DFD32F1";
#[cfg(test)]
const DEFAULT_LOCK_JSON: &str = include_str!("../../morphospace/feature.lock.json");

pub(crate) fn resolve_activation(
    input: &HandAdapterRuntimeActivationInput,
) -> HandAdapterLockActivationDecision {
    resolve_hand_adapter_activation(
        CONFORMANCE_LOCK_JSON,
        ACCEPTED_LOCK_SHA256,
        PROJECT_ID,
        FEATURE_ID,
        MODULE_ID,
        ACCEPTED_PROFILE_ID,
        input,
    )
}

pub(crate) fn activation_marker(input: &HandAdapterRuntimeActivationInput) -> String {
    let decision = resolve_activation(input);
    format!(
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=hand-adapter {} handAdapterRuntimeMode=explicit-live-hand-bridge-start",
        shared_activation_marker("spatial-camera-panel", &decision)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn accepted_input() -> HandAdapterRuntimeActivationInput {
        HandAdapterRuntimeActivationInput {
            enabled: true,
            profile_id: ACCEPTED_PROFILE_ID.to_owned(),
            project_id: PROJECT_ID.to_owned(),
            feature_id: FEATURE_ID.to_owned(),
            lock_revision: 1,
            lock_sha256: ACCEPTED_LOCK_SHA256.to_owned(),
        }
    }

    #[test]
    fn spatial_hand_consumer_requires_exact_lock_and_bridge_input() {
        let decision = resolve_activation(&accepted_input());
        assert!(decision.is_applied());
        assert_eq!(decision.lock_sha256(), ACCEPTED_LOCK_SHA256);
        let marker = activation_marker(&accepted_input());
        assert!(marker.contains("status=accepted"));
        assert!(marker.contains("handAdapterConsumer=spatial-camera-panel"));
        assert!(marker.contains("handAdapterEnabled=true"));
        assert!(marker.contains("handAdapterCoordinateBasisPreserved=true"));
        assert!(marker.contains("activationState=applied"));
        assert!(marker.contains("featureId=tracked-hand-surface"));
    }

    #[test]
    fn property_only_default_and_wrong_lock_inputs_are_inert() {
        let property_only = HandAdapterRuntimeActivationInput {
            enabled: true,
            profile_id: String::new(),
            project_id: String::new(),
            feature_id: String::new(),
            lock_revision: 0,
            lock_sha256: String::new(),
        };
        let marker = activation_marker(&property_only);
        assert!(marker.contains("status=rejected"));
        assert!(marker.contains("handAdapterEnabled=false"));
        assert!(marker.contains("activationRejectReason=runtime-profile-mismatch"));

        let default = resolve_hand_adapter_activation(
            DEFAULT_LOCK_JSON,
            ACCEPTED_LOCK_SHA256,
            PROJECT_ID,
            FEATURE_ID,
            MODULE_ID,
            ACCEPTED_PROFILE_ID,
            &accepted_input(),
        );
        assert!(!default.is_applied());
        assert!(default
            .marker_fields()
            .contains("activationRejectReason=lock-feature-not-selected"));

        let mut stale = accepted_input();
        stale.lock_revision = 2;
        assert!(
            activation_marker(&stale).contains("activationRejectReason=runtime-revision-mismatch")
        );
        let mut wrong_digest = accepted_input();
        wrong_digest.lock_sha256 = "F".repeat(64);
        assert!(activation_marker(&wrong_digest)
            .contains("activationRejectReason=runtime-digest-mismatch"));
    }
}
