//! Disabled-by-default controller gesture for the generic breath calibration lifecycle.

use crate::{
    native_renderer_properties::{
        PROP_BREATH_CALIBRATION_RIGHT_SECONDARY_ACTION_HOLD_SECONDS,
        PROP_BREATH_CALIBRATION_RIGHT_SECONDARY_ACTION_MODE,
    },
    native_renderer_property_values::{f32_clamped_value, normalized_property},
};

const DEFAULT_HOLD_SECONDS: f32 = 1.25;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum BreathCalibrationControllerActionMode {
    Disabled,
    RightSecondaryHoldStart,
}

impl BreathCalibrationControllerActionMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "right-secondary-hold-start" => Self::RightSecondaryHoldStart,
            _ => Self::Disabled,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::RightSecondaryHoldStart => "right-secondary-hold-start",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct BreathCalibrationControllerActionSettings {
    mode: BreathCalibrationControllerActionMode,
    hold_seconds: f32,
}

impl BreathCalibrationControllerActionSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            mode: BreathCalibrationControllerActionMode::from_property(lookup(
                PROP_BREATH_CALIBRATION_RIGHT_SECONDARY_ACTION_MODE,
            )),
            hold_seconds: f32_clamped_value(
                lookup(PROP_BREATH_CALIBRATION_RIGHT_SECONDARY_ACTION_HOLD_SECONDS),
                DEFAULT_HOLD_SECONDS,
                0.5,
                5.0,
            ),
        }
    }

    pub(crate) fn enabled(self) -> bool {
        self.mode == BreathCalibrationControllerActionMode::RightSecondaryHoldStart
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "breathCalibrationControllerActionMode={} breathCalibrationControllerActionHoldSeconds={:.3}",
            self.mode.marker_value(),
            self.hold_seconds,
        )
    }
}

impl Default for BreathCalibrationControllerActionSettings {
    fn default() -> Self {
        Self {
            mode: BreathCalibrationControllerActionMode::Disabled,
            hold_seconds: DEFAULT_HOLD_SECONDS,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct BreathCalibrationControllerAction {
    settings: BreathCalibrationControllerActionSettings,
    held_seconds: f32,
    triggered_this_hold: bool,
    triggered_count: u64,
}

impl BreathCalibrationControllerAction {
    pub(crate) fn new(settings: BreathCalibrationControllerActionSettings) -> Self {
        Self {
            settings,
            held_seconds: 0.0,
            triggered_this_hold: false,
            triggered_count: 0,
        }
    }

    pub(crate) fn enabled(self) -> bool {
        self.settings.enabled()
    }

    pub(crate) fn update(&mut self, dt_seconds: f32, pressed: bool) -> bool {
        if !self.enabled() || !pressed {
            self.held_seconds = 0.0;
            self.triggered_this_hold = false;
            return false;
        }
        let dt_seconds = if dt_seconds.is_finite() && dt_seconds > 0.0 {
            dt_seconds.min(1.0)
        } else {
            0.0
        };
        self.held_seconds = (self.held_seconds + dt_seconds).min(60.0);
        if self.triggered_this_hold || self.held_seconds < self.settings.hold_seconds {
            return false;
        }
        self.triggered_this_hold = true;
        self.triggered_count = self.triggered_count.saturating_add(1);
        true
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "{} breathCalibrationControllerActionHeldSeconds={:.3} breathCalibrationControllerActionTriggeredCount={}",
            self.settings.marker_fields(),
            self.held_seconds,
            self.triggered_count,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn enabled_settings() -> BreathCalibrationControllerActionSettings {
        BreathCalibrationControllerActionSettings {
            mode: BreathCalibrationControllerActionMode::RightSecondaryHoldStart,
            hold_seconds: 1.25,
        }
    }

    #[test]
    fn disabled_action_is_inert() {
        let mut action = BreathCalibrationControllerAction::new(Default::default());
        assert!(!action.update(5.0, true));
    }

    #[test]
    fn hold_triggers_once_until_release_and_rearms() {
        let mut action = BreathCalibrationControllerAction::new(enabled_settings());
        assert!(!action.update(0.5, true));
        assert!(!action.update(0.5, true));
        assert!(action.update(0.25, true));
        assert!(!action.update(2.0, true));
        assert!(!action.update(0.0, false));
        assert!(!action.update(1.0, true));
        assert!(action.update(0.25, true));
        assert_eq!(action.triggered_count, 2);
    }

    #[test]
    fn malformed_time_does_not_advance_hold() {
        let mut action = BreathCalibrationControllerAction::new(enabled_settings());
        assert!(!action.update(f32::NAN, true));
        assert!(!action.update(-1.0, true));
        assert!(!action.update(1.0, true));
        assert!(action.update(0.25, true));
    }
}
