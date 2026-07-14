use rusty_quest_feature_activation::{
    resolve_lock_bound_activation, LockBoundActivationDecision, LockBoundActivationPolicy,
    LockBoundActivationRuntimeInput,
};

pub(crate) const PROJECT_ID: &str = "spatial-camera-panel";
pub(crate) const FEATURE_ID: &str = "spatial-asset-model";
const MODULE_ID: &str = "spatial-asset-model";
pub(crate) const ACCEPTED_PROFILE_ID: &str =
    "profile.quest.spatial_camera_panel.spatial_asset_model_conformance";
const REQUESTED_BY: &str = "conformance-profile:spatial-asset-model";
const RECEIPT_SCHEMA: &str = "rusty.quest.spatial_asset_model.activation_receipt.v1";
const EFFECTIVE_MARKER: &str = "rusty.quest.spatial_asset_model.effective";
const CONFORMANCE_LOCK_JSON: &str =
    include_str!("../../morphospace/conformance-locks/spatial-asset-model.feature.lock.json");
pub(crate) const ACCEPTED_LOCK_SHA256: &str =
    "DA385EFC37AA4BD8D74B5AEADD7C8A06461E8EE68308B9E20C24BBA64A1C8793";
#[cfg(test)]
const DEFAULT_LOCK_JSON: &str = include_str!("../../morphospace/feature.lock.json");

pub(crate) fn resolve_activation(
    input: &LockBoundActivationRuntimeInput,
) -> LockBoundActivationDecision {
    resolve_lock_bound_activation(
        CONFORMANCE_LOCK_JSON,
        ACCEPTED_LOCK_SHA256,
        PROJECT_ID,
        FEATURE_ID,
        MODULE_ID,
        ACCEPTED_PROFILE_ID,
        LockBoundActivationPolicy {
            requested_by: REQUESTED_BY,
            receipt_schema: RECEIPT_SCHEMA,
            effective_marker: EFFECTIVE_MARKER,
        },
        input,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn accepted_input() -> LockBoundActivationRuntimeInput {
        LockBoundActivationRuntimeInput {
            enabled: true,
            profile_id: ACCEPTED_PROFILE_ID.to_owned(),
            project_id: PROJECT_ID.to_owned(),
            feature_id: FEATURE_ID.to_owned(),
            lock_revision: 1,
            lock_sha256: ACCEPTED_LOCK_SHA256.to_owned(),
        }
    }

    #[test]
    fn spatial_asset_consumer_requires_exact_lock_and_runtime_input() {
        let decision = resolve_activation(&accepted_input());
        assert!(decision.is_applied());
        assert_eq!(decision.lock_sha256(), ACCEPTED_LOCK_SHA256);
        assert!(decision.marker_fields().contains("activationState=applied"));
        assert!(decision
            .marker_fields()
            .contains("featureId=spatial-asset-model"));
    }

    #[test]
    fn property_only_default_and_stale_inputs_are_inert() {
        let property_only = LockBoundActivationRuntimeInput {
            enabled: true,
            profile_id: String::new(),
            project_id: String::new(),
            feature_id: String::new(),
            lock_revision: 0,
            lock_sha256: String::new(),
        };
        assert!(resolve_activation(&property_only)
            .marker_fields()
            .contains("activationRejectReason=runtime-profile-mismatch"));

        let default = resolve_lock_bound_activation(
            DEFAULT_LOCK_JSON,
            ACCEPTED_LOCK_SHA256,
            PROJECT_ID,
            FEATURE_ID,
            MODULE_ID,
            ACCEPTED_PROFILE_ID,
            LockBoundActivationPolicy {
                requested_by: REQUESTED_BY,
                receipt_schema: RECEIPT_SCHEMA,
                effective_marker: EFFECTIVE_MARKER,
            },
            &accepted_input(),
        );
        assert!(!default.is_applied());
        assert!(default
            .marker_fields()
            .contains("activationRejectReason=lock-feature-not-selected"));

        let mut stale = accepted_input();
        stale.lock_sha256 = "0".repeat(64);
        assert!(resolve_activation(&stale)
            .marker_fields()
            .contains("activationRejectReason=runtime-digest-mismatch"));
    }
}
