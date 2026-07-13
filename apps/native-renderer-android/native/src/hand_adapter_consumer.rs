#[cfg(target_os = "android")]
use crate::native_renderer_properties::{
    PROP_HAND_ADAPTER_ENABLED, PROP_HAND_ADAPTER_FEATURE_ID, PROP_HAND_ADAPTER_LOCK_REVISION,
    PROP_HAND_ADAPTER_LOCK_SHA256, PROP_HAND_ADAPTER_PROFILE_ID, PROP_HAND_ADAPTER_PROJECT_ID,
};
use rusty_quest_hand_adapter::{
    activation_marker as shared_activation_marker, resolve_hand_adapter_activation,
    HandAdapterLockActivationDecision, HandAdapterRuntimeActivationInput,
};

const PROJECT_ID: &str = "native-renderer";
const FEATURE_ID: &str = "hand-adapter-consumer";
const MODULE_ID: &str = "hand-adapter-consumer";
const ACCEPTED_PROFILE_ID: &str = "profile.quest.native_renderer.hand_adapter_conformance";
const CONFORMANCE_LOCK_JSON: &str =
    include_str!("../../morphospace/conformance-locks/hand-adapter.feature.lock.json");
const ACCEPTED_LOCK_SHA256: &str =
    "A1391A7EF2C41F072032283E485F5A9EB58CAB3B74681F150CE24CD9262CF91D";
#[cfg(test)]
const DEFAULT_LOCK_JSON: &str = include_str!("../../morphospace/feature.lock.json");

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct HandAdapterEffectRequest {
    pub(crate) live_hand_input: bool,
    pub(crate) default_hand_visual: bool,
    pub(crate) graft_copies: bool,
    pub(crate) real_hand_meshes: bool,
    pub(crate) hand_anchor_particles: bool,
}

impl HandAdapterEffectRequest {
    pub(crate) fn any(self) -> bool {
        self.live_hand_input
            || self.default_hand_visual
            || self.graft_copies
            || self.real_hand_meshes
            || self.hand_anchor_particles
    }
}

pub(crate) fn effects_authorized(
    decision: &HandAdapterLockActivationDecision,
    request: HandAdapterEffectRequest,
) -> bool {
    !request.any() || decision.is_applied()
}

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
    shared_activation_marker("native-openxr-hand-lab", &decision)
}

#[cfg(target_os = "android")]
pub(crate) fn load_runtime_input() -> HandAdapterRuntimeActivationInput {
    HandAdapterRuntimeActivationInput {
        enabled: property_value(PROP_HAND_ADAPTER_ENABLED)
            .map(|value| matches!(value.as_str(), "1" | "true" | "on" | "enabled"))
            .unwrap_or(false),
        profile_id: property_value(PROP_HAND_ADAPTER_PROFILE_ID).unwrap_or_default(),
        project_id: property_value(PROP_HAND_ADAPTER_PROJECT_ID).unwrap_or_default(),
        feature_id: property_value(PROP_HAND_ADAPTER_FEATURE_ID).unwrap_or_default(),
        lock_revision: property_value(PROP_HAND_ADAPTER_LOCK_REVISION)
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(0),
        lock_sha256: property_value(PROP_HAND_ADAPTER_LOCK_SHA256).unwrap_or_default(),
    }
}

#[cfg(target_os = "android")]
fn property_value(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().map(|value| value.trim().to_owned())
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
    fn native_hand_consumer_requires_exact_lock_and_profile() {
        let decision = resolve_activation(&accepted_input());
        assert!(decision.is_applied());
        assert_eq!(decision.lock_sha256(), ACCEPTED_LOCK_SHA256);
        let marker = activation_marker(&accepted_input());
        assert!(marker.contains("status=accepted"));
        assert!(marker.contains("handAdapterConsumer=native-openxr-hand-lab"));
        assert!(marker.contains("handAdapterEnabled=true"));
        assert!(marker.contains("activationState=applied"));
        assert!(marker.contains("projectId=native-renderer"));
        assert!(marker.contains("featureId=hand-adapter-consumer"));
    }

    #[test]
    fn property_only_default_and_stale_inputs_are_inert() {
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

        let mut wrong_project = accepted_input();
        wrong_project.project_id = "spatial-camera-panel".to_owned();
        assert!(activation_marker(&wrong_project)
            .contains("activationRejectReason=runtime-project-mismatch"));
        let mut wrong_digest = accepted_input();
        wrong_digest.lock_sha256 = "F".repeat(64);
        assert!(activation_marker(&wrong_digest)
            .contains("activationRejectReason=runtime-digest-mismatch"));
    }

    #[test]
    fn disabling_guard_property_cannot_authorize_requested_hand_effects() {
        let disabled = HandAdapterRuntimeActivationInput {
            enabled: false,
            profile_id: ACCEPTED_PROFILE_ID.to_owned(),
            project_id: PROJECT_ID.to_owned(),
            feature_id: FEATURE_ID.to_owned(),
            lock_revision: 1,
            lock_sha256: ACCEPTED_LOCK_SHA256.to_owned(),
        };
        let decision = resolve_activation(&disabled);
        assert!(!decision.is_applied());
        for request in [
            HandAdapterEffectRequest {
                live_hand_input: true,
                ..HandAdapterEffectRequest::default()
            },
            HandAdapterEffectRequest {
                default_hand_visual: true,
                ..HandAdapterEffectRequest::default()
            },
            HandAdapterEffectRequest {
                graft_copies: true,
                ..HandAdapterEffectRequest::default()
            },
            HandAdapterEffectRequest {
                real_hand_meshes: true,
                ..HandAdapterEffectRequest::default()
            },
            HandAdapterEffectRequest {
                hand_anchor_particles: true,
                ..HandAdapterEffectRequest::default()
            },
        ] {
            assert!(!effects_authorized(&decision, request));
        }
        assert!(effects_authorized(
            &decision,
            HandAdapterEffectRequest::default()
        ));
        assert!(effects_authorized(
            &resolve_activation(&accepted_input()),
            HandAdapterEffectRequest {
                live_hand_input: true,
                default_hand_visual: true,
                graft_copies: true,
                real_hand_meshes: true,
                hand_anchor_particles: true,
            }
        ));
    }
}
