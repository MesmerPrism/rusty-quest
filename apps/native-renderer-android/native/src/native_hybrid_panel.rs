//! Native toolbar interaction, separate from Android forms and application settings.
//! Coordinates are in panel-height units, centered on the panel, positive Y down.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PanelAction {
    ToggleForms,
    Center,
    Size,
    CycleDetail,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum DetailMode {
    #[default]
    Auto,
    Base,
    Medium,
    High,
}
impl DetailMode {
    pub(crate) fn next(self) -> Self {
        match self { Self::Auto => Self::Base, Self::Base => Self::Medium,
            Self::Medium => Self::High, Self::High => Self::Auto }
    }
    pub(crate) fn label(self) -> &'static str {
        match self { Self::Auto => "AUTO", Self::Base => "1X", Self::Medium => "1.5X", Self::High => "2X" }
    }
}

#[derive(Debug)]
pub(crate) struct HybridPanelState {
    pub(crate) detail: DetailMode,
    pub(crate) forms_visible: bool,
    pub(crate) hovered: Option<usize>,
    pub(crate) captured: Option<usize>,
    was_pressed: bool,
}

impl Default for HybridPanelState {
    fn default() -> Self {
        Self {
            detail: DetailMode::Auto,
            forms_visible: true,
            hovered: None,
            captured: None,
            was_pressed: false,
        }
    }
}

impl HybridPanelState {
    /// A press must begin on a native button and release over that same button.
    /// Cancellation preserves pressed state so tracking recovery cannot synthesize a press.
    pub(crate) fn update(
        &mut self,
        valid: bool,
        pressed: bool,
        hit: Option<usize>,
        forms_captured: bool,
    ) -> (bool, Option<PanelAction>) {
        let fresh_press = pressed && !self.was_pressed;
        self.was_pressed = pressed;
        self.hovered = if valid && !forms_captured { hit } else { None };
        if !valid || forms_captured {
            let consumed = self.captured.take().is_some();
            return (consumed, None);
        }
        if fresh_press {
            self.captured = hit.filter(|index| *index < 4);
        }
        let Some(captured) = self.captured else {
            return (false, None);
        };
        if pressed {
            return (true, None);
        }
        self.captured = None;
        let action = (hit == Some(captured)).then_some(match captured {
            0 => PanelAction::ToggleForms,
            1 => PanelAction::Center,
            2 => PanelAction::Size,
            _ => PanelAction::CycleDetail,
        });
        (true, action)
    }

    pub(crate) fn cancel(&mut self) {
        self.hovered = None;
        self.captured = None;
        // Require an open input after ownership changes before accepting another press.
        self.was_pressed = true;
    }
}

pub(crate) fn selected() -> bool {
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_HYBRID") == Some("true")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detail_uses_matching_release_and_does_not_change_forms() {
        let mut state = HybridPanelState::default();
        assert_eq!(state.update(true, true, Some(3), false), (true, None));
        assert_eq!(state.update(true, false, Some(3), false), (true, Some(PanelAction::CycleDetail)));
        assert!(state.forms_visible);
        state.update(true, true, Some(4), false);
        assert_eq!(state.update(true, false, Some(4), false), (false, None));
        assert_eq!(DetailMode::Auto.next().next().next().next(), DetailMode::Auto);
    }

    #[test]
    fn activates_on_matching_release_and_does_not_steal_forms_drag() {
        let mut state = HybridPanelState::default();
        assert_eq!(state.update(true, true, Some(0), false), (true, None));
        assert_eq!(
            state.update(true, false, Some(0), false),
            (true, Some(PanelAction::ToggleForms))
        );
        assert_eq!(state.update(true, true, Some(1), true), (false, None));
        assert_eq!(state.update(true, false, Some(1), true), (false, None));
    }

    #[test]
    fn lost_tracking_and_cross_button_release_never_activate() {
        let mut state = HybridPanelState::default();
        state.update(true, true, Some(0), false);
        assert_eq!(state.update(false, true, None, false), (true, None));
        assert_eq!(state.update(true, true, Some(0), false), (false, None));
        state.update(true, false, None, false);
        state.update(true, true, Some(0), false);
        assert_eq!(state.update(true, false, Some(1), false), (true, None));
        state.cancel();
        assert_eq!(state.update(true, true, Some(0), false), (false, None));
        state.update(true, false, None, false);
        assert_eq!(state.update(true, true, Some(2), false), (true, None));
    }
}
