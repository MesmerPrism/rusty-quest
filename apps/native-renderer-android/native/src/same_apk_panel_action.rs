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
const RIGHT_TRIGGER_PRESS_THRESHOLD: f32 = 0.82;
const RIGHT_TRIGGER_RELEASE_THRESHOLD: f32 = 0.35;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum SameApkPanelActionMode {
    Disabled,
    RightSecondaryTriplePressToggle,
    RightSecondaryTriplePressExperimenterRestart,
}

impl SameApkPanelActionMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "right-secondary-triple-press-toggle" => Self::RightSecondaryTriplePressToggle,
            "right-secondary-triple-press-experimenter-restart" => {
                Self::RightSecondaryTriplePressExperimenterRestart
            }
            _ => Self::Disabled,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::RightSecondaryTriplePressToggle => "right-secondary-triple-press-toggle",
            Self::RightSecondaryTriplePressExperimenterRestart => {
                "right-secondary-triple-press-experimenter-restart"
            }
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SameApkPanelActionTrigger {
    CompatibilityToggle,
    ExperimenterRestart,
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
        self.mode != SameApkPanelActionMode::Disabled
    }

    pub(crate) fn experimenter_profile_enabled(self) -> bool {
        self.mode == SameApkPanelActionMode::RightSecondaryTriplePressExperimenterRestart
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
    sequence: TriplePressSequence,
    triggered_count: u64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct TriplePressSequence {
    previous_pressed: bool,
    press_count: u8,
    seconds_since_first_press: f32,
    window_seconds: f32,
}

impl TriplePressSequence {
    fn new(window_seconds: f32) -> Self {
        Self {
            previous_pressed: false,
            press_count: 0,
            seconds_since_first_press: 0.0,
            window_seconds,
        }
    }

    fn cancel(&mut self, pressed: bool) {
        self.reset(pressed);
    }

    fn update(&mut self, dt_seconds: f32, pressed: bool) -> bool {
        let dt_seconds = if dt_seconds.is_finite() && dt_seconds > 0.0 {
            dt_seconds.min(60.0)
        } else {
            0.0
        };
        if self.press_count > 0 {
            self.seconds_since_first_press += dt_seconds;
            if self.seconds_since_first_press > self.window_seconds {
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
        true
    }

    fn reset(&mut self, pressed: bool) {
        self.previous_pressed = pressed;
        self.press_count = 0;
        self.seconds_since_first_press = 0.0;
    }
}

impl SameApkPanelAction {
    pub(crate) fn new(settings: SameApkPanelActionSettings) -> Self {
        Self {
            settings,
            sequence: TriplePressSequence::new(settings.window_seconds),
            triggered_count: 0,
        }
    }

    pub(crate) fn enabled(self) -> bool {
        self.settings.enabled()
    }

    pub(crate) fn experimenter_profile_enabled(self) -> bool {
        self.settings.experimenter_profile_enabled()
    }

    pub(crate) fn cancel_pending_sequence(&mut self, pressed: bool) {
        self.sequence.cancel(pressed);
    }

    pub(crate) fn update(
        &mut self,
        dt_seconds: f32,
        pressed: bool,
    ) -> Option<SameApkPanelActionTrigger> {
        if !self.enabled() {
            self.sequence.cancel(false);
            return None;
        }
        if !self.sequence.update(dt_seconds, pressed) {
            return None;
        }
        self.triggered_count = self.triggered_count.saturating_add(1);
        Some(if self.experimenter_profile_enabled() {
            SameApkPanelActionTrigger::ExperimenterRestart
        } else {
            SameApkPanelActionTrigger::CompatibilityToggle
        })
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "{} sameApkPanelActionPressCount={} sameApkPanelActionTriggeredCount={}",
            self.settings.marker_fields(),
            self.sequence.press_count,
            self.triggered_count,
        )
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct SameApkDeveloperAction {
    enabled: bool,
    trigger_pressed: bool,
    sequence: TriplePressSequence,
    triggered_count: u64,
}

impl SameApkDeveloperAction {
    pub(crate) fn new(settings: SameApkPanelActionSettings) -> Self {
        Self {
            enabled: settings.experimenter_profile_enabled(),
            trigger_pressed: false,
            sequence: TriplePressSequence::new(settings.window_seconds),
            triggered_count: 0,
        }
    }

    pub(crate) fn update(&mut self, dt_seconds: f32, active: bool, value: f32) -> bool {
        if !self.enabled || !active || !value.is_finite() {
            self.cancel_pending_sequence();
            return false;
        }
        if self.trigger_pressed {
            if value <= RIGHT_TRIGGER_RELEASE_THRESHOLD {
                self.trigger_pressed = false;
            }
        } else if value >= RIGHT_TRIGGER_PRESS_THRESHOLD {
            self.trigger_pressed = true;
        }
        if !self.sequence.update(dt_seconds, self.trigger_pressed) {
            return false;
        }
        self.triggered_count = self.triggered_count.saturating_add(1);
        true
    }

    pub(crate) fn cancel_pending_sequence(&mut self) {
        self.trigger_pressed = false;
        self.sequence.cancel(false);
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "sameApkDeveloperActionEnabled={} triggerPressed={} pressThreshold={:.3} releaseThreshold={:.3} pressCount={} triggeredCount={}",
            self.enabled,
            self.trigger_pressed,
            RIGHT_TRIGGER_PRESS_THRESHOLD,
            RIGHT_TRIGGER_RELEASE_THRESHOLD,
            self.sequence.press_count,
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

    fn press(action: &mut SameApkPanelAction, elapsed: f32) -> Option<SameApkPanelActionTrigger> {
        assert_eq!(action.update(elapsed, false), None);
        action.update(0.0, true)
    }

    #[test]
    fn disabled_action_is_inert() {
        let mut action = SameApkPanelAction::new(settings(false));
        for _ in 0..3 {
            assert_eq!(press(&mut action, 0.1), None);
        }
    }

    #[test]
    fn exactly_three_press_edges_trigger_one_deterministic_action() {
        let mut action = SameApkPanelAction::new(settings(true));
        assert_eq!(press(&mut action, 0.0), None);
        assert_eq!(press(&mut action, 1.0), None);
        assert_eq!(
            press(&mut action, 1.0),
            Some(SameApkPanelActionTrigger::CompatibilityToggle)
        );
        assert_eq!(action.update(0.0, true), None);
        assert_eq!(action.triggered_count, 1);
    }

    #[test]
    fn timeout_discards_incomplete_sequence() {
        let mut action = SameApkPanelAction::new(settings(true));
        assert_eq!(press(&mut action, 0.0), None);
        assert_eq!(press(&mut action, 6.0), None);
        assert_eq!(press(&mut action, 1.0), None);
        assert_eq!(
            press(&mut action, 1.0),
            Some(SameApkPanelActionTrigger::CompatibilityToggle)
        );
    }

    #[test]
    fn held_button_counts_as_one_press() {
        let mut action = SameApkPanelAction::new(settings(true));
        assert_eq!(action.update(0.0, true), None);
        assert_eq!(action.update(1.0, true), None);
        assert_eq!(action.update(1.0, true), None);
        assert_eq!(action.sequence.press_count, 1);
    }

    #[test]
    fn calibration_hold_can_cancel_pending_triple_press_state() {
        let mut action = SameApkPanelAction::new(settings(true));
        assert_eq!(action.update(0.0, true), None);
        assert_eq!(action.sequence.press_count, 1);
        action.cancel_pending_sequence(true);
        assert_eq!(action.sequence.press_count, 0);
        assert_eq!(action.update(0.0, true), None);
    }

    #[test]
    fn explicit_experiment_profile_routes_b_to_restart() {
        let settings = SameApkPanelActionSettings {
            mode: SameApkPanelActionMode::RightSecondaryTriplePressExperimenterRestart,
            window_seconds: 5.0,
        };
        let mut action = SameApkPanelAction::new(settings);
        assert_eq!(press(&mut action, 0.0), None);
        assert_eq!(press(&mut action, 1.0), None);
        assert_eq!(
            press(&mut action, 1.0),
            Some(SameApkPanelActionTrigger::ExperimenterRestart)
        );
    }

    #[test]
    fn trigger_triple_press_uses_hysteresis_and_rejects_held_or_stale_input() {
        let settings = SameApkPanelActionSettings {
            mode: SameApkPanelActionMode::RightSecondaryTriplePressExperimenterRestart,
            window_seconds: 5.0,
        };
        let mut action = SameApkDeveloperAction::new(settings);
        assert!(!action.update(0.0, true, 0.9));
        assert!(!action.update(1.0, true, 0.9));
        assert!(!action.update(0.0, true, 0.5));
        assert!(!action.update(0.0, true, 0.2));
        assert!(!action.update(1.0, true, 0.9));
        assert!(!action.update(0.0, true, 0.2));
        assert!(!action.update(6.0, true, 0.9));
        assert!(!action.update(0.0, false, 0.0));
        assert!(!action.update(0.0, true, 0.9));
        assert!(!action.update(0.0, true, 0.2));
        assert!(!action.update(1.0, true, 0.9));
        assert!(!action.update(0.0, true, 0.2));
        assert!(action.update(1.0, true, 0.9));
    }
}
