#[cfg(target_os = "android")]
use crate::native_renderer_properties::{
    PROP_PARTICLE_ADAPTER_ENABLED, PROP_PARTICLE_ADAPTER_FEATURE_ID,
    PROP_PARTICLE_ADAPTER_LOCK_REVISION, PROP_PARTICLE_ADAPTER_LOCK_SHA256,
    PROP_PARTICLE_ADAPTER_PROFILE_ID, PROP_PARTICLE_ADAPTER_PROJECT_ID,
};
use rusty_quest_particle_adapter::{
    activation_receipt, resolve_particle_adapter_activation, ParticleAdapterLockActivationDecision,
    ParticleAdapterRuntimeActivationInput, QuestParticleAdapterDescriptor,
    PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID, PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID,
};

const PROJECT_ID: &str = "native-renderer";
const FEATURE_ID: &str = "particle-adapter-consumer";
const MODULE_ID: &str = "particle-adapter-consumer";
const ACCEPTED_PROFILE_ID: &str = "profile.quest.native_renderer.particle_adapter_conformance";
const CONFORMANCE_LOCK_JSON: &str =
    include_str!("../../morphospace/conformance-locks/particle-adapter.feature.lock.json");
const ACCEPTED_LOCK_SHA256: &str =
    "D51D97F6B01663F360E867EB01C3F27A3DB7C3204210F1C1D7634CA52DD276BC";
#[cfg(test)]
const DEFAULT_LOCK_JSON: &str = include_str!("../../morphospace/feature.lock.json");

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct ParticleAdapterEffectRequest {
    pub(crate) environment_depth_particles: bool,
    pub(crate) hand_anchor_particles: bool,
    pub(crate) private_particles: bool,
}

impl ParticleAdapterEffectRequest {
    pub(crate) fn any(self) -> bool {
        self.environment_depth_particles || self.hand_anchor_particles || self.private_particles
    }
}

pub(crate) fn effects_authorized(
    decision: &ParticleAdapterLockActivationDecision,
    request: ParticleAdapterEffectRequest,
) -> bool {
    !request.any() || decision.is_applied()
}

pub(crate) fn descriptor(enabled: bool) -> QuestParticleAdapterDescriptor {
    QuestParticleAdapterDescriptor::new(
        "adapter.quest.native-renderer.particle",
        "native-renderer-android",
        enabled,
        65_536,
    )
}

pub(crate) fn resolve_activation(
    input: &ParticleAdapterRuntimeActivationInput,
) -> ParticleAdapterLockActivationDecision {
    resolve_particle_adapter_activation(
        CONFORMANCE_LOCK_JSON,
        ACCEPTED_LOCK_SHA256,
        PROJECT_ID,
        FEATURE_ID,
        MODULE_ID,
        ACCEPTED_PROFILE_ID,
        input,
    )
}

pub(crate) fn activation_marker(input: &ParticleAdapterRuntimeActivationInput) -> String {
    let decision = resolve_activation(input);
    let enabled = decision.is_applied();
    let descriptor = descriptor(enabled);
    let receipt = activation_receipt(&descriptor)
        .expect("native renderer particle adapter descriptor must remain valid");
    format!(
        "status={} particleAdapterDescriptorSchema={} particleAdapterReceiptSchema={} particleAdapterId={} particleAdapterConsumer={} particleAdapterEnabled={} particleAdapterSourceContracts=matter-render-payload+lattice-situated-anchor+optics-visual-frame particleAdapterHighRateJson=false particleAdapterBackendPayloadAbsent={} particleAdapterRuntimeMode=explicit-opt-in-conformance {}",
        if enabled { "accepted" } else { "rejected" },
        PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID,
        PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID,
        receipt.adapter_id,
        receipt.consumer_id,
        receipt.enabled,
        receipt.backend_payload_absent,
        decision.marker_fields(),
    )
}

#[cfg(target_os = "android")]
pub(crate) fn load_runtime_input() -> ParticleAdapterRuntimeActivationInput {
    ParticleAdapterRuntimeActivationInput {
        enabled: property_value(PROP_PARTICLE_ADAPTER_ENABLED)
            .map(|value| matches!(value.as_str(), "1" | "true" | "on" | "enabled"))
            .unwrap_or(false),
        profile_id: property_value(PROP_PARTICLE_ADAPTER_PROFILE_ID).unwrap_or_default(),
        project_id: property_value(PROP_PARTICLE_ADAPTER_PROJECT_ID).unwrap_or_default(),
        feature_id: property_value(PROP_PARTICLE_ADAPTER_FEATURE_ID).unwrap_or_default(),
        lock_revision: property_value(PROP_PARTICLE_ADAPTER_LOCK_REVISION)
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(0),
        lock_sha256: property_value(PROP_PARTICLE_ADAPTER_LOCK_SHA256).unwrap_or_default(),
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

    fn accepted_input() -> ParticleAdapterRuntimeActivationInput {
        ParticleAdapterRuntimeActivationInput {
            enabled: true,
            profile_id: ACCEPTED_PROFILE_ID.to_owned(),
            project_id: PROJECT_ID.to_owned(),
            feature_id: FEATURE_ID.to_owned(),
            lock_revision: 1,
            lock_sha256: ACCEPTED_LOCK_SHA256.to_owned(),
        }
    }

    #[test]
    fn native_renderer_consumer_requires_exact_lock_and_profile() {
        let decision = resolve_activation(&accepted_input());
        assert!(decision.is_applied());
        assert_eq!(decision.lock_sha256(), ACCEPTED_LOCK_SHA256);
        let marker = activation_marker(&accepted_input());
        assert!(marker.contains("status=accepted"));
        assert!(marker.contains("particleAdapterEnabled=true"));
        assert!(marker.contains("activationState=applied"));
        assert!(marker.contains("projectId=native-renderer"));
        assert!(marker.contains("featureId=particle-adapter-consumer"));
    }

    #[test]
    fn property_only_default_and_stale_inputs_are_inert() {
        let property_only = ParticleAdapterRuntimeActivationInput {
            enabled: true,
            profile_id: String::new(),
            project_id: String::new(),
            feature_id: String::new(),
            lock_revision: 0,
            lock_sha256: String::new(),
        };
        let marker = activation_marker(&property_only);
        assert!(marker.contains("status=rejected"));
        assert!(marker.contains("particleAdapterEnabled=false"));
        assert!(marker.contains("activationRejectReason=runtime-profile-mismatch"));

        let default = resolve_particle_adapter_activation(
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
        stale.lock_revision = 0;
        assert!(
            activation_marker(&stale).contains("activationRejectReason=runtime-revision-mismatch")
        );
        let mut wrong_digest = accepted_input();
        wrong_digest.lock_sha256 = "0".repeat(64);
        assert!(activation_marker(&wrong_digest)
            .contains("activationRejectReason=runtime-digest-mismatch"));
    }

    #[test]
    fn disabling_guard_property_cannot_authorize_requested_particle_effects() {
        let disabled = ParticleAdapterRuntimeActivationInput {
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
            ParticleAdapterEffectRequest {
                environment_depth_particles: true,
                ..ParticleAdapterEffectRequest::default()
            },
            ParticleAdapterEffectRequest {
                hand_anchor_particles: true,
                ..ParticleAdapterEffectRequest::default()
            },
            ParticleAdapterEffectRequest {
                private_particles: true,
                ..ParticleAdapterEffectRequest::default()
            },
        ] {
            assert!(!effects_authorized(&decision, request));
        }
        assert!(effects_authorized(
            &decision,
            ParticleAdapterEffectRequest::default()
        ));
        assert!(effects_authorized(
            &resolve_activation(&accepted_input()),
            ParticleAdapterEffectRequest {
                environment_depth_particles: true,
                hand_anchor_particles: true,
                private_particles: true,
            }
        ));
    }
}
