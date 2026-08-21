//! Source-neutral bounded integration for categorical breath observations.

use rusty_quest_breath_contract::assessment::CommonBreathPhase;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BreathHoldPolicy {
    Hold,
    ResetToLoss,
}

impl BreathHoldPolicy {
    pub(crate) fn from_token(value: &str) -> Option<Self> {
        match value {
            "hold" => Some(Self::Hold),
            "reset-to-loss" => Some(Self::ResetToLoss),
            _ => None,
        }
    }

    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Hold => "hold",
            Self::ResetToLoss => "reset-to-loss",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct BoundedBreathPhaseIntegrator {
    value01: f32,
}

impl BoundedBreathPhaseIntegrator {
    pub(crate) fn new(initial_value01: f32) -> Self {
        Self {
            value01: bounded(initial_value01),
        }
    }

    pub(crate) fn reset(&mut self, value01: f32) {
        self.value01 = bounded(value01);
    }

    pub(crate) fn update(
        &mut self,
        phase: CommonBreathPhase,
        dt_seconds: f32,
        inhale_rate_per_second: f32,
        exhale_rate_per_second: f32,
        hold_policy: BreathHoldPolicy,
        loss_value01: f32,
    ) {
        let dt_seconds = sanitize_dt(dt_seconds);
        let inhale_delta = dt_seconds * sanitize_rate(inhale_rate_per_second);
        let exhale_delta = dt_seconds * sanitize_rate(exhale_rate_per_second);
        self.value01 = match phase {
            CommonBreathPhase::Inhale => bounded(self.value01 + inhale_delta),
            CommonBreathPhase::Exhale => bounded(self.value01 - exhale_delta),
            CommonBreathPhase::Hold if hold_policy == BreathHoldPolicy::ResetToLoss => {
                bounded(loss_value01)
            }
            CommonBreathPhase::Hold
            | CommonBreathPhase::Unknown
            | CommonBreathPhase::BadTracking => self.value01,
        };
    }

    pub(crate) const fn value01(self) -> f32 {
        self.value01
    }
}

fn bounded(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(0.0, 1.0)
    } else {
        0.0
    }
}

fn sanitize_dt(value: f32) -> f32 {
    if value.is_finite() && value > 0.0 {
        value.min(1.0)
    } else {
        0.0
    }
}

fn sanitize_rate(value: f32) -> f32 {
    if value.is_finite() && value > 0.0 {
        value.min(62.5)
    } else {
        0.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn integration_is_bounded_and_cadence_independent() {
        for cadence in [72_u32, 90, 120] {
            let mut integrator = BoundedBreathPhaseIntegrator::new(0.25);
            for _ in 0..cadence {
                integrator.update(
                    CommonBreathPhase::Inhale,
                    1.0 / cadence as f32,
                    0.5,
                    0.25,
                    BreathHoldPolicy::Hold,
                    0.1,
                );
            }
            assert!((integrator.value01() - 0.75).abs() < 0.000_01);
            integrator.update(
                CommonBreathPhase::Inhale,
                10.0,
                62.5,
                0.25,
                BreathHoldPolicy::Hold,
                0.1,
            );
            assert_eq!(integrator.value01(), 1.0);
        }
    }

    #[test]
    fn hold_and_explicit_reset_policies_are_distinct() {
        let mut integrator = BoundedBreathPhaseIntegrator::new(0.7);
        integrator.update(
            CommonBreathPhase::Hold,
            1.0,
            0.5,
            0.5,
            BreathHoldPolicy::Hold,
            0.2,
        );
        assert_eq!(integrator.value01(), 0.7);
        integrator.update(
            CommonBreathPhase::Hold,
            1.0,
            0.5,
            0.5,
            BreathHoldPolicy::ResetToLoss,
            0.2,
        );
        assert_eq!(integrator.value01(), 0.2);
    }
}
