use rusty_quest_particle_adapter::{
    activation_receipt, resolve_particle_adapter_activation, ParticleAdapterLockActivationDecision,
    ParticleAdapterRuntimeActivationInput, QuestParticleAdapterDescriptor,
    PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID, PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID,
};

pub(crate) const PROJECT_ID: &str = "spatial-camera-panel";
pub(crate) const FEATURE_ID: &str = "surface-particle-runtime";
const MODULE_ID: &str = "surface-particle-runtime";
pub(crate) const ACCEPTED_PROFILE_ID: &str =
    "profile.quest.spatial_camera_panel.particle_adapter_conformance";
const CONFORMANCE_LOCK_JSON: &str =
    include_str!("../../morphospace/conformance-locks/particle-adapter.feature.lock.json");
const ACCEPTED_LOCK_SHA256: &str =
    "780814BE82C12A54036DE0259C6188E2D41813858C30E6B6C725EB8422F7301B";
#[cfg(test)]
const DEFAULT_LOCK_JSON: &str = include_str!("../../morphospace/feature.lock.json");

pub(crate) fn descriptor(enabled: bool, max_particles: usize) -> QuestParticleAdapterDescriptor {
    QuestParticleAdapterDescriptor::new(
        "adapter.quest.spatial-camera-panel.particle",
        "spatial-camera-panel",
        enabled,
        max_particles.max(1),
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

pub(crate) fn activation_marker(
    input: &ParticleAdapterRuntimeActivationInput,
    requested_particles: usize,
) -> String {
    let decision = resolve_activation(input);
    let enabled = decision.is_applied();
    let descriptor = descriptor(enabled, requested_particles.max(16_384));
    let receipt = activation_receipt(&descriptor)
        .expect("Spatial Camera Panel particle adapter descriptor must remain valid");
    format!(
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=particle-adapter status={} particleAdapterDescriptorSchema={} particleAdapterReceiptSchema={} particleAdapterId={} particleAdapterConsumer={} particleAdapterEnabled={} particleAdapterRequestedCount={} particleAdapterSourceContracts=matter-render-payload+lattice-situated-anchor+optics-visual-frame particleAdapterHighRateJson=false particleAdapterBackendPayloadAbsent={} particleAdapterRuntimeMode=surface-layer-explicit-start {}",
        if enabled { "accepted" } else { "rejected" },
        PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID,
        PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID,
        receipt.adapter_id,
        receipt.consumer_id,
        receipt.enabled,
        requested_particles,
        receipt.backend_payload_absent,
        decision.marker_fields(),
    )
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
    fn spatial_consumer_requires_exact_lock_and_explicit_surface_input() {
        let decision = resolve_activation(&accepted_input());
        assert!(decision.is_applied());
        assert_eq!(decision.lock_sha256(), ACCEPTED_LOCK_SHA256);
        let marker = activation_marker(&accepted_input(), 1024);
        assert!(marker.contains("status=accepted"));
        assert!(marker.contains("particleAdapterEnabled=true"));
        assert!(marker.contains("activationState=applied"));
        assert!(marker.contains("projectId=spatial-camera-panel"));
        assert!(marker.contains("featureId=surface-particle-runtime"));
    }

    #[test]
    fn property_only_default_and_wrong_lock_inputs_are_inert() {
        let property_only = ParticleAdapterRuntimeActivationInput {
            enabled: true,
            profile_id: String::new(),
            project_id: String::new(),
            feature_id: String::new(),
            lock_revision: 0,
            lock_sha256: String::new(),
        };
        let marker = activation_marker(&property_only, 1024);
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

        let mut wrong_feature = accepted_input();
        wrong_feature.feature_id = "tracked-hand-surface".to_owned();
        assert!(activation_marker(&wrong_feature, 1024)
            .contains("activationRejectReason=runtime-feature-mismatch"));
        let mut wrong_digest = accepted_input();
        wrong_digest.lock_sha256 = "0".repeat(64);
        assert!(activation_marker(&wrong_digest, 1024)
            .contains("activationRejectReason=runtime-digest-mismatch"));
    }
}
