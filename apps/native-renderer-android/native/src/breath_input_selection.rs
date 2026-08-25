//! Generic, fail-closed selection for direct breath inputs.
//!
//! Source and mapping are independent contract dimensions. Availability is a
//! separate decision: a syntactically valid pair can remain unavailable while
//! its estimator is not implemented. Rejected and incomplete requests stay
//! inert and are never presented as ordinary disabled state.

use crate::native_renderer_property_values::normalized_property;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BreathSourceKind {
    Controller,
    PolarAcc,
}

impl BreathSourceKind {
    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Controller => "controller",
            Self::PolarAcc => "polar-acc",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BreathMappingKind {
    Volume,
    State,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum ControllerVolumeKind {
    FixedOrientation,
    #[default]
    DynamicMotionAxis,
}

impl BreathMappingKind {
    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Volume => "volume",
            Self::State => "state",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BreathSelectionStatus {
    Disabled,
    Accepted,
    RejectedIncomplete,
    RejectedUnsupportedSource,
    RejectedUnsupportedMapping,
    RejectedUnsupportedMode,
}

impl BreathSelectionStatus {
    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Accepted => "accepted",
            Self::RejectedIncomplete => "rejected-incomplete",
            Self::RejectedUnsupportedSource => "rejected-unsupported-source",
            Self::RejectedUnsupportedMapping => "rejected-unsupported-mapping",
            Self::RejectedUnsupportedMode => "rejected-unsupported-mode",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct BreathInputSelection {
    source: Option<BreathSourceKind>,
    mapping: Option<BreathMappingKind>,
    controller_volume: Option<ControllerVolumeKind>,
    status: BreathSelectionStatus,
}

impl BreathInputSelection {
    pub(crate) fn from_parts(source: Option<String>, mapping: Option<String>) -> Self {
        let source = normalized_property(source);
        let mapping = normalized_property(mapping);
        let source_disabled = is_disabled_token(&source);
        let mapping_disabled = is_disabled_token(&mapping);

        if source_disabled && mapping_disabled {
            return Self::disabled();
        }
        if source_disabled || mapping_disabled {
            return Self {
                source: parse_source(&source),
                mapping: parse_mapping(&mapping),
                controller_volume: None,
                status: BreathSelectionStatus::RejectedIncomplete,
            };
        }

        let Some(source) = parse_source(&source) else {
            return Self {
                source: None,
                mapping: parse_mapping(&mapping),
                controller_volume: None,
                status: BreathSelectionStatus::RejectedUnsupportedSource,
            };
        };
        let Some(mapping) = parse_mapping(&mapping) else {
            return Self {
                source: Some(source),
                mapping: None,
                controller_volume: None,
                status: BreathSelectionStatus::RejectedUnsupportedMapping,
            };
        };

        Self::from_pair(source, mapping)
    }

    pub(crate) fn from_legacy_mode_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "" | "disabled" | "off" => Self::disabled(),
            "direct-controller-state"
            | "native-controller-state"
            | "local-controller-state"
            | "fixed-controller-state" => {
                Self::from_pair(BreathSourceKind::Controller, BreathMappingKind::State)
            }
            "polar-acc-normalized" => {
                Self::from_pair(BreathSourceKind::PolarAcc, BreathMappingKind::Volume)
            }
            "direct-controller-volume-fixed-orientation" => {
                Self::controller_volume(ControllerVolumeKind::FixedOrientation)
            }
            "direct-controller-volume-dynamic-motion-axis" | "controller-volume" => {
                Self::controller_volume(ControllerVolumeKind::DynamicMotionAxis)
            }
            "polar-acc-state" => {
                Self::from_pair(BreathSourceKind::PolarAcc, BreathMappingKind::State)
            }
            _ => Self {
                source: None,
                mapping: None,
                controller_volume: None,
                status: BreathSelectionStatus::RejectedUnsupportedMode,
            },
        }
    }

    fn from_pair(source: BreathSourceKind, mapping: BreathMappingKind) -> Self {
        let status = BreathSelectionStatus::Accepted;
        Self {
            source: Some(source),
            mapping: Some(mapping),
            controller_volume: (source == BreathSourceKind::Controller
                && mapping == BreathMappingKind::Volume)
                .then_some(ControllerVolumeKind::DynamicMotionAxis),
            status,
        }
    }

    fn controller_volume(kind: ControllerVolumeKind) -> Self {
        Self {
            source: Some(BreathSourceKind::Controller),
            mapping: Some(BreathMappingKind::Volume),
            controller_volume: Some(kind),
            status: BreathSelectionStatus::Accepted,
        }
    }

    pub(crate) fn disabled() -> Self {
        Self {
            source: None,
            mapping: None,
            controller_volume: None,
            status: BreathSelectionStatus::Disabled,
        }
    }

    pub(crate) fn accepted(self) -> bool {
        self.status == BreathSelectionStatus::Accepted
    }

    pub(crate) fn uses_controller_state(self) -> bool {
        self.accepted()
            && self.source == Some(BreathSourceKind::Controller)
            && self.mapping == Some(BreathMappingKind::State)
    }

    pub(crate) fn uses_polar_acc_volume(self) -> bool {
        self.accepted()
            && self.source == Some(BreathSourceKind::PolarAcc)
            && self.mapping == Some(BreathMappingKind::Volume)
    }

    pub(crate) fn uses_polar_acc_state(self) -> bool {
        self.accepted()
            && self.source == Some(BreathSourceKind::PolarAcc)
            && self.mapping == Some(BreathMappingKind::State)
    }

    pub(crate) fn uses_controller_volume(self) -> bool {
        self.accepted()
            && self.source == Some(BreathSourceKind::Controller)
            && self.mapping == Some(BreathMappingKind::Volume)
    }

    pub(crate) fn controller_volume_kind(self) -> Option<ControllerVolumeKind> {
        self.uses_controller_volume()
            .then_some(self.controller_volume.unwrap_or_default())
    }

    pub(crate) fn source_marker(self) -> &'static str {
        self.source
            .map(BreathSourceKind::marker_value)
            .unwrap_or("none")
    }

    pub(crate) fn mapping_marker(self) -> &'static str {
        self.mapping
            .map(BreathMappingKind::marker_value)
            .unwrap_or("none")
    }

    pub(crate) fn status_marker(self) -> &'static str {
        self.status.marker_value()
    }

    pub(crate) fn effective_mode_marker(self) -> &'static str {
        if self.uses_controller_state() {
            "direct-controller-state"
        } else if self.controller_volume_kind() == Some(ControllerVolumeKind::FixedOrientation) {
            "direct-controller-volume-fixed-orientation"
        } else if self.uses_controller_volume() {
            "direct-controller-volume-dynamic-motion-axis"
        } else if self.uses_polar_acc_volume() {
            "polar-acc-normalized"
        } else if self.uses_polar_acc_state() {
            "polar-acc-state"
        } else {
            self.status.marker_value()
        }
    }
}

fn is_disabled_token(value: &str) -> bool {
    matches!(value, "" | "disabled" | "off" | "none")
}

fn parse_source(value: &str) -> Option<BreathSourceKind> {
    match value {
        "controller" | "native-controller" => Some(BreathSourceKind::Controller),
        "polar" | "polar-acc" => Some(BreathSourceKind::PolarAcc),
        _ => None,
    }
}

fn parse_mapping(value: &str) -> Option<BreathMappingKind> {
    match value {
        "volume" => Some(BreathMappingKind::Volume),
        "state" => Some(BreathMappingKind::State),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn select(source: &str, mapping: &str) -> BreathInputSelection {
        BreathInputSelection::from_parts(Some(source.to_owned()), Some(mapping.to_owned()))
    }

    #[test]
    fn source_and_mapping_form_an_independent_four_way_matrix() {
        assert!(select("controller", "state").accepted());
        assert!(select("controller", "volume").accepted());
        assert!(select("polar-acc", "volume").accepted());
        assert!(select("polar-acc", "state").accepted());
    }

    #[test]
    fn controller_volume_variants_and_polar_state_are_explicit() {
        for (mode, kind) in [
            (
                "direct-controller-volume-fixed-orientation",
                ControllerVolumeKind::FixedOrientation,
            ),
            (
                "direct-controller-volume-dynamic-motion-axis",
                ControllerVolumeKind::DynamicMotionAxis,
            ),
        ] {
            let selection = BreathInputSelection::from_legacy_mode_property(Some(mode.to_owned()));
            assert!(selection.accepted());
            assert_eq!(selection.controller_volume_kind(), Some(kind));
        }
        let polar_state =
            BreathInputSelection::from_legacy_mode_property(Some("polar-acc-state".to_owned()));
        assert!(polar_state.accepted());
        assert!(polar_state.uses_polar_acc_state());
        assert_eq!(polar_state.effective_mode_marker(), "polar-acc-state");
    }

    #[test]
    fn unknown_and_incomplete_requests_are_distinct_from_disabled() {
        let unknown = BreathInputSelection::from_legacy_mode_property(Some("broker".to_owned()));
        assert_eq!(
            unknown.status,
            BreathSelectionStatus::RejectedUnsupportedMode
        );
        assert_eq!(unknown.effective_mode_marker(), "rejected-unsupported-mode");

        let incomplete = BreathInputSelection::from_parts(Some("controller".to_owned()), None);
        assert_eq!(incomplete.status, BreathSelectionStatus::RejectedIncomplete);
        assert!(!incomplete.accepted());
    }

    #[test]
    fn explicitly_disabled_selection_is_inert() {
        let disabled = BreathInputSelection::from_parts(None, None);
        assert_eq!(disabled.status, BreathSelectionStatus::Disabled);
        assert!(!disabled.accepted());
        assert_eq!(disabled.source_marker(), "none");
        assert_eq!(disabled.mapping_marker(), "none");
    }

    #[test]
    fn source_switching_does_not_leave_controller_assessment_selected() {
        let controller = select("controller", "volume");
        assert!(controller.uses_controller_volume());
        assert!(!controller.uses_polar_acc_volume());

        let polar = select("polar-acc", "volume");
        assert!(!polar.uses_controller_volume());
        assert_eq!(polar.controller_volume_kind(), None);
        assert!(polar.uses_polar_acc_volume());
    }
}
