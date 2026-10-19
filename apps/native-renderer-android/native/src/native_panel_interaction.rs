//! Shared bounded pointer capture and presentation-control proposals; no application authority.
use crate::native_panel_model::LogicalBounds;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ControlId {
    pub(crate) panel: u64,
    pub(crate) control: u32,
}
impl ControlId {
    fn valid(self) -> bool {
        self.panel != 0
    }
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct CaptureOutcome {
    pub(crate) consumed: bool,
    pub(crate) pressed: Option<ControlId>,
    pub(crate) dragging: Option<ControlId>,
    pub(crate) released: Option<ControlId>,
    pub(crate) activated: Option<ControlId>,
    pub(crate) cancelled: Option<ControlId>,
}
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct CaptureState {
    hovered: Option<ControlId>,
    captured: Option<ControlId>,
    was_pressed: bool,
    require_open: bool,
}
impl CaptureState {
    pub(crate) fn hovered(self) -> Option<ControlId> {
        self.hovered
    }
    pub(crate) fn captured(self) -> Option<ControlId> {
        self.captured
    }
    /// Call for every sample, including invalid/hidden owners. A blocked external capture wins.
    /// Identity includes the panel, preventing drag/release from retargeting an overlapping form.
    pub(crate) fn update(
        &mut self,
        valid: bool,
        pressed: bool,
        hit: Option<ControlId>,
        blocked: bool,
    ) -> CaptureOutcome {
        let fresh = pressed && !self.was_pressed;
        self.was_pressed = pressed;
        self.hovered = if valid && !blocked {
            hit.filter(|id| id.valid())
        } else {
            None
        };
        if !valid || blocked {
            let cancelled = self.captured.take();
            self.require_open = true;
            return CaptureOutcome {
                consumed: cancelled.is_some(),
                cancelled,
                ..CaptureOutcome::default()
            };
        }
        if self.require_open {
            if !pressed {
                self.require_open = false;
                self.was_pressed = false;
            }
            return CaptureOutcome::default();
        }
        let mut outcome = CaptureOutcome::default();
        if fresh {
            self.captured = self.hovered;
            outcome.pressed = self.captured;
        }
        if let Some(captured) = self.captured {
            outcome.consumed = true;
            if pressed {
                outcome.dragging = Some(captured);
            } else {
                self.captured = None;
                outcome.released = Some(captured);
                if self.hovered == Some(captured) {
                    outcome.activated = Some(captured);
                }
            }
        }
        outcome
    }
    /// Caller emits original-owner cancellation when returned ID is Some.
    pub(crate) fn cancel(&mut self) -> Option<ControlId> {
        self.hovered = None;
        self.was_pressed = true;
        self.require_open = true;
        self.captured.take()
    }
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct SliderDescriptor {
    pub(crate) bounds: LogicalBounds,
    pub(crate) minimum: f32,
    pub(crate) maximum: f32,
    pub(crate) value: f32,
    pub(crate) step: Option<f32>,
}
impl SliderDescriptor {
    pub(crate) fn valid(self) -> bool {
        self.bounds.valid()
            && [
                self.minimum,
                self.maximum,
                self.value,
                self.maximum - self.minimum,
            ]
            .iter()
            .all(|v| v.is_finite())
            && self.maximum > self.minimum
            && self.value >= self.minimum
            && self.value <= self.maximum
            && self.step.is_none_or(|step| step.is_finite() && step > 0.0)
    }
    /// Absolute value proposal into the real owner; no accumulated local slider state.
    pub(crate) fn proposal(self, logical: [f32; 2]) -> Option<f32> {
        if !self.valid() || logical.iter().any(|v| !v.is_finite()) {
            return None;
        }
        let normalized = ((logical[0] - self.bounds.min[0])
            / (self.bounds.max[0] - self.bounds.min[0]))
            .clamp(0.0, 1.0);
        let mut value = self.minimum + normalized * (self.maximum - self.minimum);
        if let Some(step) = self.step {
            value = self.minimum + ((value - self.minimum) / step).round() * step;
        }
        value
            .is_finite()
            .then_some(value.clamp(self.minimum, self.maximum))
    }
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum ControlProposal {
    Activate(ControlId),
    SetToggle { control: ControlId, value: bool },
    SetScalar { control: ControlId, value: f32 },
}
#[derive(Clone, Copy, Debug)]
pub(crate) enum ControlDescriptor<'a> {
    Button {
        control: ControlId,
    },
    Toggle {
        control: ControlId,
        value: bool,
    },
    Slider {
        control: ControlId,
        range: SliderDescriptor,
    },
    Progress {
        fraction: f32,
    },
    Readout {
        label: &'a str,
        value: &'a str,
    },
}
impl<'a> ControlDescriptor<'a> {
    /// Descriptors are per-frame observations; only emitted proposals reach the app command map.
    /// Progress/readout never become input targets or commands.
    pub(crate) fn proposal(
        self,
        outcome: CaptureOutcome,
        logical: [f32; 2],
    ) -> Option<ControlProposal> {
        match self {
            Self::Button { control } if outcome.activated == Some(control) => {
                Some(ControlProposal::Activate(control))
            }
            Self::Toggle { control, value } if outcome.activated == Some(control) => {
                Some(ControlProposal::SetToggle {
                    control,
                    value: !value,
                })
            }
            Self::Slider { control, range } if outcome.dragging == Some(control) => range
                .proposal(logical)
                .map(|value| ControlProposal::SetScalar { control, value }),
            _ => None,
        }
    }
    pub(crate) fn progress_fraction(self) -> Option<f32> {
        match self {
            Self::Progress { fraction } if fraction.is_finite() => Some(fraction.clamp(0.0, 1.0)),
            _ => None,
        }
    }
    pub(crate) fn readout(self) -> Option<(&'a str, &'a str)> {
        match self {
            Self::Readout { label, value } => Some((label, value)),
            _ => None,
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn a() -> ControlId {
        ControlId {
            panel: 1,
            control: 0,
        }
    }
    fn b() -> ControlId {
        ControlId {
            panel: 2,
            control: 0,
        }
    }
    #[test]
    fn cross_panels_drag_keeps_original_owner_and_release_does_not_click_other() {
        let mut state = CaptureState::default();
        assert_eq!(
            state.update(true, true, Some(a()), false).pressed,
            Some(a())
        );
        assert_eq!(
            state.update(true, true, Some(b()), false).dragging,
            Some(a())
        );
        let release = state.update(true, false, Some(b()), false);
        assert_eq!(release.released, Some(a()));
        assert_eq!(release.activated, None);
    }
    #[test]
    fn tracking_loss_with_false_pressed_still_requires_observed_open() {
        let mut state = CaptureState::default();
        state.update(true, true, Some(a()), false);
        assert_eq!(state.update(false, false, None, false).cancelled, Some(a()));
        assert!(!state.update(true, true, Some(a()), false).consumed);
        state.update(true, false, Some(a()), false);
        assert_eq!(
            state.update(true, true, Some(a()), false).pressed,
            Some(a())
        );
    }
    #[test]
    fn hidden_panel_and_external_form_capture_cancel_without_stealing() {
        let mut state = CaptureState::default();
        state.update(true, true, Some(a()), false);
        assert_eq!(state.update(false, true, None, false).cancelled, Some(a()));
        assert!(!state.update(true, true, Some(b()), true).consumed);
        state.update(true, false, None, false);
        assert_eq!(
            state.update(true, true, Some(b()), false).pressed,
            Some(b())
        );
        assert_eq!(state.cancel(), Some(b()));
        assert!(!state.update(true, true, Some(a()), false).consumed);
    }
    #[test]
    fn matching_release_activates_once() {
        let mut state = CaptureState::default();
        state.update(true, true, Some(a()), false);
        assert_eq!(
            state.update(true, false, Some(a()), false).activated,
            Some(a())
        );
        assert_eq!(state.update(true, false, Some(a()), false).activated, None);
    }
    #[test]
    fn slider_clamps_quantizes_and_only_proposes_to_captured_owner() {
        let range = SliderDescriptor {
            bounds: LogicalBounds {
                min: [10.0, 20.0],
                max: [110.0, 40.0],
            },
            minimum: 0.0,
            maximum: 2.0,
            value: 1.0,
            step: Some(0.25),
        };
        assert_eq!(range.proposal([39.0, 30.0]), Some(0.5));
        assert_eq!(range.proposal([-100.0, 30.0]), Some(0.0));
        assert_eq!(range.proposal([900.0, 30.0]), Some(2.0));
        assert_eq!(range.proposal([f32::NAN, 30.0]), None);
        let slider = ControlDescriptor::Slider {
            control: a(),
            range,
        };
        assert_eq!(
            slider.proposal(
                CaptureOutcome {
                    dragging: Some(b()),
                    ..CaptureOutcome::default()
                },
                [50.0, 30.0]
            ),
            None
        );
    }
    #[test]
    fn toggle_and_readonly_contracts_do_not_duplicate_owner_state() {
        let activated = CaptureOutcome {
            activated: Some(a()),
            ..CaptureOutcome::default()
        };
        let toggle = ControlDescriptor::Toggle {
            control: a(),
            value: false,
        };
        assert_eq!(
            toggle.proposal(activated, [0.0; 2]),
            Some(ControlProposal::SetToggle {
                control: a(),
                value: true
            })
        );
        assert_eq!(
            ControlDescriptor::Button { control: a() }.proposal(activated, [0.0; 2]),
            Some(ControlProposal::Activate(a()))
        );
        let progress = ControlDescriptor::Progress { fraction: 1.2 };
        assert_eq!(progress.progress_fraction(), Some(1.0));
        assert_eq!(progress.proposal(activated, [0.0; 2]), None);
        let readout = ControlDescriptor::Readout {
            label: "RASTER",
            value: "1536",
        };
        assert_eq!(readout.proposal(activated, [0.0; 2]), None);
        assert_eq!(readout.readout(), Some(("RASTER", "1536")));
    }
}
