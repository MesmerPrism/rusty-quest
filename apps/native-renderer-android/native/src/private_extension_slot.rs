pub(crate) const PRIVATE_LAYER_SLOT_ID: &str = "private-layer-slot-0";
pub(crate) const PRIVATE_LAYER_SLOT_ABI_ID: &str =
    "rusty.quest.native_renderer.private_layer_slot.v1";

#[derive(Default)]
pub(crate) struct PrivateExtensionSlotRuntime {
    invocation_sequence: u64,
}

impl PrivateExtensionSlotRuntime {
    pub(crate) fn config_marker_fields() -> String {
        format!(
            "privateLayerSlotReady=true privateLayerSlotId={} privateLayerAbiId={} privateLayerPublicAbiOnly=true privateLayerPayloadLinked=false privateLayerImplementationPath=none privateLayerOutput=identity-public-abi-resource privateLayerColorEffectActive=false",
            PRIVATE_LAYER_SLOT_ID,
            PRIVATE_LAYER_SLOT_ABI_ID
        )
    }

    pub(crate) fn record_noop_frame(
        &mut self,
        frame_count: u64,
        guide_graph_ready: bool,
        sdf_field_ready: bool,
    ) -> PrivateExtensionSlotFrameStats {
        self.invocation_sequence = self.invocation_sequence.saturating_add(1);
        PrivateExtensionSlotFrameStats {
            frame_count,
            invocation_sequence: self.invocation_sequence,
            guide_graph_ready,
            sdf_field_ready,
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct PrivateExtensionSlotFrameStats {
    frame_count: u64,
    invocation_sequence: u64,
    guide_graph_ready: bool,
    sdf_field_ready: bool,
}

impl PrivateExtensionSlotFrameStats {
    pub(crate) fn marker_fields(self) -> String {
        format!(
            "privateLayerSlotReady=true privateLayerSlotId={} privateLayerAbiId={} privateLayerPublicAbiOnly=true privateLayerPayloadLinked=false privateLayerImplementationPath=none privateLayerFrame={} privateLayerInvocationSequence={} privateLayerInputGuideGraphReady={} privateLayerInputSdfFieldReady={} privateLayerOutput=identity-public-abi-resource privateLayerColorEffectActive=false privateLayerVisualAcceptance=not-applicable-public-noop",
            PRIVATE_LAYER_SLOT_ID,
            PRIVATE_LAYER_SLOT_ABI_ID,
            self.frame_count,
            self.invocation_sequence,
            self.guide_graph_ready,
            self.sdf_field_ready
        )
    }
}
