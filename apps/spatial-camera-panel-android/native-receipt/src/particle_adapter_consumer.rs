use rusty_quest_particle_adapter::{
    activation_receipt, QuestParticleAdapterDescriptor, PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID,
    PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID,
};

pub(crate) fn descriptor(enabled: bool, max_particles: usize) -> QuestParticleAdapterDescriptor {
    QuestParticleAdapterDescriptor::new(
        "adapter.quest.spatial-camera-panel.particle",
        "spatial-camera-panel",
        enabled,
        max_particles.max(1),
    )
}

pub(crate) fn activation_marker(enabled: bool, requested_particles: usize) -> String {
    let descriptor = descriptor(enabled, requested_particles.max(16_384));
    let receipt = activation_receipt(&descriptor)
        .expect("Spatial Camera Panel particle adapter descriptor must remain valid");
    format!(
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=particle-adapter status={} particleAdapterDescriptorSchema={} particleAdapterReceiptSchema={} particleAdapterId={} particleAdapterConsumer={} particleAdapterEnabled={} particleAdapterRequestedCount={} particleAdapterSourceContracts=matter-render-payload+lattice-situated-anchor+optics-visual-frame particleAdapterHighRateJson=false particleAdapterBackendPayloadAbsent={} particleAdapterRuntimeMode=surface-layer-explicit-start",
        if enabled { "accepted" } else { "disabled" },
        PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID,
        PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID,
        receipt.adapter_id,
        receipt.consumer_id,
        receipt.enabled,
        requested_particles,
        receipt.backend_payload_absent,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spatial_consumer_is_selected_only_by_explicit_surface_start() {
        let disabled = descriptor(false, 1024);
        assert_eq!(disabled.consumer_id, "spatial-camera-panel");
        assert!(!disabled.enabled);
        assert!(activation_marker(false, 1024).contains("status=disabled"));
        assert!(activation_marker(true, 1024).contains("particleAdapterEnabled=true"));
    }
}
