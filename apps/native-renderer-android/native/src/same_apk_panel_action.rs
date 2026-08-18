//! Disabled-by-default controller gesture for the existing same-APK panel.
//!
//! This module recognizes a generic right-secondary triple press. Android
//! activity selection and intent dispatch remain in `native_renderer_panel_bridge`.

use crate::{
    native_renderer_properties::{
        PROP_CONTROL_PANEL_RIGHT_SECONDARY_ACTION_MODE,
        PROP_CONTROL_PANEL_RIGHT_SECONDARY_ACTION_WINDOW_SECONDS,
    },
    native_renderer_property_values::{f32_clamped_value, normalized_property},
};

const DEFAULT_TRIPLE_PRESS_WINDOW_SECONDS: f32 = 5.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum SameApkPanelActionMode {
    Disabled,
    RightSecondaryTriplePressToggle,
}

impl SameApkPanelActionMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "right-secondary-triple-press-toggle" => Self::RightSecondaryTriplePressToggle,
            _ => Self::Disabled,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::RightSecondaryTriplePressToggle => "right-secondary-triple-press-toggle",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct SameApkPanelActionSettings {
    mode: SameApkPanelActionMode,
    window_seconds: f32,
}

impl SameApkPanelActionSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            mode: SameApkPanelActionMode::from_property(lookup(
                PROP_CONTROL_PANEL_RIGHT_SECONDARY_ACTION_MODE,
            )),
            window_seconds: f32_clamped_value(
                lookup(PROP_CONTROL_PANEL_RIGHT_SECONDARY_ACTION_WINDOW_SECONDS),
                DEFAULT_TRIPLE_PRESS_WINDOW_SECONDS,
                0.25,
                30.0,
            ),
        }
    }

    pub(crate) fn disabled() -> Self {
        Self {
            mode: SameApkPanelActionMode::Disabled,
            window_seconds: DEFAULT_TRIPLE_PRESS_WINDOW_SECONDS,
        }
    }

    pub(crate) fn enabled(self) -> bool {
        self.mode == SameApkPanelActionMode::RightSecondaryTriplePressToggle
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "sameApkPanelActionMode={} sameApkPanelActionWindowSeconds={:.3}",
            self.mode.marker_value(),
            self.window_seconds,
        )
    }
}

impl Default for SameApkPanelActionSettings {
    fn default() -> Self {
        Self::disabled()
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct SameApkPanelAction {
    settings: SameApkPanelActionSettings,
    previous_pressed: bool,
    press_count: u8,
    seconds_since_first_press: f32,
    triggered_count: u64,
}

impl SameApkPanelAction {
    pub(crate) fn new(settings: SameApkPanelActionSettings) -> Self {
        Self {
            settings,
            previous_pressed: false,
            press_count: 0,
            seconds_since_first_press: 0.0,
            triggered_count: 0,
        }
    }

    pub(crate) fn enabled(self) -> bool {
        self.settings.enabled()
    }

    pub(crate) fn update(&mut self, dt_seconds: f32, pressed: bool) -> bool {
        if !self.enabled() {
            self.reset(false);
            return false;
        }
        let dt_seconds = if dt_seconds.is_finite() && dt_seconds > 0.0 {
            dt_seconds.min(60.0)
        } else {
            0.0
        };
        if self.press_count > 0 {
            self.seconds_since_first_press += dt_seconds;
            if self.seconds_since_first_press > self.settings.window_seconds {
                self.press_count = 0;
                self.seconds_since_first_press = 0.0;
            }
        }
        let rising_edge = pressed && !self.previous_pressed;
        self.previous_pressed = pressed;
        if !rising_edge {
            return false;
        }
        if self.press_count == 0 {
            self.seconds_since_first_press = 0.0;
        }
        self.press_count = self.press_count.saturating_add(1);
        if self.press_count < 3 {
            return false;
        }
        self.press_count = 0;
        self.seconds_since_first_press = 0.0;
        self.triggered_count = self.triggered_count.saturating_add(1);
        true
    }

    fn reset(&mut self, pressed: bool) {
        self.previous_pressed = pressed;
        self.press_count = 0;
        self.seconds_since_first_press = 0.0;
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "{} sameApkPanelActionPressCount={} sameApkPanelActionTriggeredCount={}",
            self.settings.marker_fields(),
            self.press_count,
            self.triggered_count,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings(enabled: bool) -> SameApkPanelActionSettings {
        SameApkPanelActionSettings {
            mode: if enabled {
                SameApkPanelActionMode::RightSecondaryTriplePressToggle
            } else {
                SameApkPanelActionMode::Disabled
            },
            window_seconds: 5.0,
        }
    }

    fn press(action: &mut SameApkPanelAction, elapsed: f32) -> bool {
        assert!(!action.update(elapsed, false));
        action.update(0.0, true)
    }

    #[test]
    fn disabled_action_is_inert() {
        let mut action = SameApkPanelAction::new(settings(false));
        for _ in 0..3 {
            assert!(!press(&mut action, 0.1));
        }
    }

    #[test]
    fn exactly_three_press_edges_trigger_one_deterministic_action() {
        let mut action = SameApkPanelAction::new(settings(true));
        assert!(!press(&mut action, 0.0));
        assert!(!press(&mut action, 1.0));
        assert!(press(&mut action, 1.0));
        assert!(!action.update(0.0, true));
        assert_eq!(action.triggered_count, 1);
    }

    #[test]
    fn timeout_discards_incomplete_sequence() {
        let mut action = SameApkPanelAction::new(settings(true));
        assert!(!press(&mut action, 0.0));
        assert!(!press(&mut action, 6.0));
        assert!(!press(&mut action, 1.0));
        assert!(press(&mut action, 1.0));
    }

    #[test]
    fn held_button_counts_as_one_press() {
        let mut action = SameApkPanelAction::new(settings(true));
        assert!(!action.update(0.0, true));
        assert!(!action.update(1.0, true));
        assert!(!action.update(1.0, true));
        assert_eq!(action.press_count, 1);
    }
}
