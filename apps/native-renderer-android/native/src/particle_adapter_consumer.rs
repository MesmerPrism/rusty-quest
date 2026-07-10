#[cfg(target_os = "android")]
use crate::native_renderer_properties::PROP_PARTICLE_ADAPTER_ENABLED;
use rusty_quest_particle_adapter::{
    activation_receipt, QuestParticleAdapterDescriptor, PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID,
    PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID,
};

pub(crate) fn descriptor(enabled: bool) -> QuestParticleAdapterDescriptor {
    QuestParticleAdapterDescriptor::new(
        "adapter.quest.native-renderer.particle",
        "native-renderer-android",
        enabled,
        65_536,
    )
}

pub(crate) fn activation_marker(enabled: bool) -> String {
    let descriptor = descriptor(enabled);
    let receipt = activation_receipt(&descriptor)
        .expect("native renderer particle adapter descriptor must remain valid");
    format!(
        "status={} particleAdapterDescriptorSchema={} particleAdapterReceiptSchema={} particleAdapterId={} particleAdapterConsumer={} particleAdapterEnabled={} particleAdapterSourceContracts=matter-render-payload+lattice-situated-anchor+optics-visual-frame particleAdapterHighRateJson=false particleAdapterBackendPayloadAbsent={} particleAdapterRuntimeMode=explicit-opt-in-conformance",
        if enabled { "accepted" } else { "disabled" },
        PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID,
        PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID,
        receipt.adapter_id,
        receipt.consumer_id,
        receipt.enabled,
        receipt.backend_payload_absent,
    )
}

#[cfg(target_os = "android")]
pub(crate) fn load_activation_marker() -> String {
    let mut property = android_properties::getprop(PROP_PARTICLE_ADAPTER_ENABLED);
    let enabled = property
        .value()
        .map(|value| matches!(value.trim(), "1" | "true" | "on" | "enabled"))
        .unwrap_or(false);
    activation_marker(enabled)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_renderer_consumer_is_explicit_and_inert_by_default() {
        let disabled = descriptor(false);
        assert_eq!(disabled.consumer_id, "native-renderer-android");
        assert!(!disabled.enabled);
        assert!(activation_marker(false).contains("status=disabled"));
        assert!(activation_marker(true).contains("particleAdapterEnabled=true"));
    }
}
