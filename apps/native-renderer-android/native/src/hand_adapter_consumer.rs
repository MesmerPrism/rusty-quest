#[cfg(target_os = "android")]
use crate::native_renderer_properties::PROP_HAND_ADAPTER_ENABLED;

pub(crate) fn activation_marker(enabled: bool) -> String {
    rusty_quest_hand_adapter::activation_marker("native-openxr-hand-lab", enabled)
}

#[cfg(target_os = "android")]
pub(crate) fn load_activation_marker() -> String {
    let mut property = android_properties::getprop(PROP_HAND_ADAPTER_ENABLED);
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
    fn native_hand_consumer_is_explicit_and_inert_by_default() {
        assert!(activation_marker(false).contains("status=disabled"));
        let accepted = activation_marker(true);
        assert!(accepted.contains("handAdapterConsumer=native-openxr-hand-lab"));
        assert!(accepted.contains("handAdapterBothHands=true"));
        assert!(accepted.contains("handAdapterCpuPreparedParity=true"));
    }
}
