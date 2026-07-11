pub(crate) fn activation_marker(enabled: bool) -> String {
    format!(
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=hand-adapter {} handAdapterRuntimeMode=explicit-live-hand-bridge-start",
        rusty_quest_hand_adapter::activation_marker("spatial-camera-panel", enabled)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spatial_hand_consumer_requires_explicit_bridge_start() {
        assert!(activation_marker(false).contains("status=disabled"));
        let accepted = activation_marker(true);
        assert!(accepted.contains("handAdapterConsumer=spatial-camera-panel"));
        assert!(accepted.contains("handAdapterCoordinateBasisPreserved=true"));
    }
}
