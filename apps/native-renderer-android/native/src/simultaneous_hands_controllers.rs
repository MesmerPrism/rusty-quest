//! Lock-bound lifecycle for Meta simultaneous hand and controller tracking.
//!
//! This module deliberately has no OpenXR dependency. The native renderer's
//! existing OpenXR owner translates its typed commands and observations into
//! platform calls without creating another instance, session, or frame loop.

use crate::native_renderer_properties::{
    PROP_SIMULTANEOUS_HANDS_CONTROLLERS_ACTIVATION_BINDING_SHA256,
    PROP_SIMULTANEOUS_HANDS_CONTROLLERS_ENABLED,
};

const PACKAGED_ACTIVATION_BINDING_SHA256: Option<&str> = option_env!(
    "RUSTY_QUEST_NATIVE_RENDERER_SIMULTANEOUS_HANDS_CONTROLLERS_EXPECTED_BINDING_SHA256"
);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ActivationBinding {
    Missing,
    Malformed,
    Digest([u8; 32]),
}

impl ActivationBinding {
    fn parse(value: Option<&str>) -> Self {
        let Some(value) = value.map(str::trim).filter(|value| !value.is_empty()) else {
            return Self::Missing;
        };
        if value.len() != 64 {
            return Self::Malformed;
        }
        let mut digest = [0_u8; 32];
        for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
            let Ok(pair) = std::str::from_utf8(pair) else {
                return Self::Malformed;
            };
            let Ok(byte) = u8::from_str_radix(pair, 16) else {
                return Self::Malformed;
            };
            digest[index] = byte;
        }
        Self::Digest(digest)
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Missing => "missing",
            Self::Malformed => "malformed",
            Self::Digest(_) => "digest",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct SimultaneousHandsControllersSettings {
    pub(crate) enabled: bool,
    packaged_binding: ActivationBinding,
    runtime_binding: ActivationBinding,
}

impl Default for SimultaneousHandsControllersSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            packaged_binding: ActivationBinding::Missing,
            runtime_binding: ActivationBinding::Missing,
        }
    }
}

impl SimultaneousHandsControllersSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            enabled: lookup(PROP_SIMULTANEOUS_HANDS_CONTROLLERS_ENABLED)
                .map(|value| matches!(value.trim(), "1" | "true" | "on" | "enabled"))
                .unwrap_or(false),
            packaged_binding: ActivationBinding::parse(PACKAGED_ACTIVATION_BINDING_SHA256),
            runtime_binding: ActivationBinding::parse(
                lookup(PROP_SIMULTANEOUS_HANDS_CONTROLLERS_ACTIVATION_BINDING_SHA256).as_deref(),
            ),
        }
    }

    #[cfg(test)]
    fn test(enabled: bool, packaged: Option<&str>, runtime: Option<&str>) -> Self {
        Self {
            enabled,
            packaged_binding: ActivationBinding::parse(packaged),
            runtime_binding: ActivationBinding::parse(runtime),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ActivationRejectReason {
    PackagedBindingMissing,
    PackagedBindingMalformed,
    RuntimeBindingMissing,
    RuntimeBindingMalformed,
    BindingMismatch,
    HandAdapterNotApplied,
}

impl ActivationRejectReason {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::PackagedBindingMissing => "packaged-binding-missing",
            Self::PackagedBindingMalformed => "packaged-binding-malformed",
            Self::RuntimeBindingMissing => "runtime-binding-missing",
            Self::RuntimeBindingMalformed => "runtime-binding-malformed",
            Self::BindingMismatch => "activation-binding-mismatch",
            Self::HandAdapterNotApplied => "hand-adapter-not-applied",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ActivationDecision {
    Inert,
    Rejected(ActivationRejectReason),
    Selected { binding: [u8; 32] },
}

impl ActivationDecision {
    pub(crate) fn is_selected(self) -> bool {
        matches!(self, Self::Selected { .. })
    }

    pub(crate) fn is_rejected(self) -> bool {
        matches!(self, Self::Rejected(_))
    }

    pub(crate) fn marker_fields(self, settings: SimultaneousHandsControllersSettings) -> String {
        let (status, reason) = match self {
            Self::Inert => ("inert", "disabled-or-unselected"),
            Self::Rejected(reason) => ("rejected", reason.as_str()),
            Self::Selected { .. } => ("selected", "none"),
        };
        format!(
            "status={status} reason={reason} simultaneousHandsControllersEnabled={} packagedBinding={} runtimeBinding={} exactActivationBinding={} effectsStarted=false extensionName=XR_META_simultaneous_hands_and_controllers detachedControllersRequested=false",
            settings.enabled,
            settings.packaged_binding.marker_value(),
            settings.runtime_binding.marker_value(),
            self.is_selected(),
        )
    }
}

pub(crate) fn resolve_activation(
    settings: SimultaneousHandsControllersSettings,
    hand_adapter_applied: bool,
) -> ActivationDecision {
    if !settings.enabled {
        return ActivationDecision::Inert;
    }
    let packaged = match settings.packaged_binding {
        ActivationBinding::Missing => {
            return ActivationDecision::Rejected(ActivationRejectReason::PackagedBindingMissing)
        }
        ActivationBinding::Malformed => {
            return ActivationDecision::Rejected(ActivationRejectReason::PackagedBindingMalformed)
        }
        ActivationBinding::Digest(digest) => digest,
    };
    let runtime = match settings.runtime_binding {
        ActivationBinding::Missing => {
            return ActivationDecision::Rejected(ActivationRejectReason::RuntimeBindingMissing)
        }
        ActivationBinding::Malformed => {
            return ActivationDecision::Rejected(ActivationRejectReason::RuntimeBindingMalformed)
        }
        ActivationBinding::Digest(digest) => digest,
    };
    if packaged != runtime {
        return ActivationDecision::Rejected(ActivationRejectReason::BindingMismatch);
    }
    if !hand_adapter_applied {
        return ActivationDecision::Rejected(ActivationRejectReason::HandAdapterNotApplied);
    }
    ActivationDecision::Selected { binding: packaged }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct IndependentInputReadiness {
    pub(crate) hand_adapter_applied: bool,
    pub(crate) hand_tracker_ready: bool,
    pub(crate) hand_frame_ready: bool,
    pub(crate) hand_active: bool,
    pub(crate) controller_action_set_ready: bool,
    pub(crate) controller_profile_ready: bool,
    pub(crate) controller_action_ready: bool,
}

impl IndependentInputReadiness {
    fn hands_ready(self) -> bool {
        self.hand_adapter_applied
            && self.hand_tracker_ready
            && self.hand_frame_ready
            && self.hand_active
    }

    fn controllers_ready(self) -> bool {
        self.controller_action_set_ready
            && self.controller_profile_ready
            && self.controller_action_ready
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PlatformCallResult {
    Success,
    SessionLossPending,
    Failure,
}

impl PlatformCallResult {
    fn as_str(self) -> &'static str {
        match self {
            Self::Success => "success",
            Self::SessionLossPending => "session-loss-pending",
            Self::Failure => "failure",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LifecycleCommand {
    Resume { session_generation: u64 },
    Pause { session_generation: u64 },
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct SimultaneousHandsControllersLifecycle {
    selected: bool,
    extension_requested: bool,
    extension_available: bool,
    extension_enabled: bool,
    functions_resolved: bool,
    system_supported: bool,
    session_generation: Option<u64>,
    resume_attempted_generation: Option<u64>,
    resumed_generation: Option<u64>,
    pause_attempted_generation: Option<u64>,
    pause_completed_generation: Option<u64>,
    last_resume_result: Option<PlatformCallResult>,
    last_pause_result: Option<PlatformCallResult>,
    resume_call_count: u64,
    pause_call_count: u64,
    hard_reset_count: u64,
    readiness: IndependentInputReadiness,
}

impl SimultaneousHandsControllersLifecycle {
    pub(crate) fn new(decision: ActivationDecision) -> Self {
        Self {
            selected: decision.is_selected(),
            extension_requested: decision.is_selected(),
            ..Self::default()
        }
    }

    pub(crate) fn observe_instance(
        &mut self,
        extension_available: bool,
        extension_enabled: bool,
        functions_resolved: bool,
    ) -> Result<(), &'static str> {
        if !self.selected {
            return Ok(());
        }
        self.extension_available = extension_available;
        self.extension_enabled = extension_enabled;
        self.functions_resolved = functions_resolved;
        if !extension_available {
            return Err("extension-unavailable");
        }
        if !extension_enabled {
            return Err("extension-not-enabled");
        }
        if !functions_resolved {
            return Err("extension-functions-unresolved");
        }
        Ok(())
    }

    pub(crate) fn observe_system_support(&mut self, supported: bool) -> Result<(), &'static str> {
        if !self.selected {
            return Ok(());
        }
        self.system_supported = supported;
        supported.then_some(()).ok_or("system-unsupported")
    }

    pub(crate) fn begin_session(&mut self, generation: u64) -> Option<LifecycleCommand> {
        if !self.selected
            || !self.extension_available
            || !self.extension_enabled
            || !self.functions_resolved
            || !self.system_supported
        {
            return None;
        }
        if self.session_generation == Some(generation)
            && self.resume_attempted_generation == Some(generation)
        {
            return None;
        }
        if self.session_generation != Some(generation) {
            self.resume_attempted_generation = None;
            self.resumed_generation = None;
            self.pause_attempted_generation = None;
            self.pause_completed_generation = None;
            self.last_resume_result = None;
            self.last_pause_result = None;
        }
        self.session_generation = Some(generation);
        self.resume_attempted_generation = Some(generation);
        Some(LifecycleCommand::Resume {
            session_generation: generation,
        })
    }

    pub(crate) fn record_resume(
        &mut self,
        generation: u64,
        result: PlatformCallResult,
    ) -> Result<(), &'static str> {
        if self.session_generation != Some(generation) {
            return Err("stale-session-generation");
        }
        self.resume_call_count = self.resume_call_count.saturating_add(1);
        self.last_resume_result = Some(result);
        match result {
            PlatformCallResult::Success => {
                self.resumed_generation = Some(generation);
                Ok(())
            }
            PlatformCallResult::SessionLossPending => {
                self.hard_reset(generation);
                Err("resume-session-loss-pending")
            }
            PlatformCallResult::Failure => Err("resume-failed"),
        }
    }

    pub(crate) fn request_pause(&mut self) -> Option<LifecycleCommand> {
        let generation = self.session_generation?;
        if !self.selected
            || self.resumed_generation != Some(generation)
            || self.pause_attempted_generation == Some(generation)
            || self.pause_completed_generation == Some(generation)
        {
            return None;
        }
        self.pause_attempted_generation = Some(generation);
        Some(LifecycleCommand::Pause {
            session_generation: generation,
        })
    }

    pub(crate) fn record_pause(
        &mut self,
        generation: u64,
        result: PlatformCallResult,
    ) -> Result<(), &'static str> {
        if self.session_generation != Some(generation) {
            return Err("stale-session-generation");
        }
        self.pause_call_count = self.pause_call_count.saturating_add(1);
        self.last_pause_result = Some(result);
        match result {
            PlatformCallResult::Success => {
                self.pause_completed_generation = Some(generation);
                self.resumed_generation = None;
                self.readiness = IndependentInputReadiness::default();
                Ok(())
            }
            PlatformCallResult::SessionLossPending => {
                self.hard_reset(generation);
                Err("pause-session-loss-pending")
            }
            PlatformCallResult::Failure => {
                self.resumed_generation = None;
                self.readiness = IndependentInputReadiness::default();
                Err("pause-failed")
            }
        }
    }

    pub(crate) fn hard_reset(&mut self, generation: u64) {
        if self.session_generation == Some(generation) {
            self.session_generation = None;
            self.resume_attempted_generation = None;
            self.resumed_generation = None;
            self.pause_attempted_generation = None;
            self.pause_completed_generation = None;
            self.readiness = IndependentInputReadiness::default();
            self.hard_reset_count = self.hard_reset_count.saturating_add(1);
        }
    }

    pub(crate) fn session_generation(&self) -> Option<u64> {
        self.session_generation
    }

    pub(crate) fn observe_readiness(&mut self, readiness: IndependentInputReadiness) {
        self.readiness = readiness;
    }

    pub(crate) fn combined_ready(&self) -> bool {
        self.selected
            && self.extension_requested
            && self.extension_available
            && self.extension_enabled
            && self.functions_resolved
            && self.system_supported
            && self.session_generation.is_some()
            && self.resumed_generation == self.session_generation
            && self.readiness.hands_ready()
            && self.readiness.controllers_ready()
    }

    pub(crate) fn is_selected(&self) -> bool {
        self.selected
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "simultaneousHandsControllersSelected={} extensionRequested={} extensionAvailable={} extensionEnabled={} systemSupported={} functionsResolved={} sessionGeneration={} resumeAttemptedGeneration={} resumedGeneration={} pauseAttemptedGeneration={} pauseCompletedGeneration={} resumeResult={} pauseResult={} resumeCallCount={} pauseCallCount={} hardResetCount={} handAdapterApplied={} handTrackerReady={} handFrameReady={} handActive={} handsReady={} controllerActionSetReady={} controllerProfileReady={} controllerActionReady={} controllersReady={} simultaneousHandsControllersReady={}",
            self.selected,
            self.extension_requested,
            self.extension_available,
            self.extension_enabled,
            self.system_supported,
            self.functions_resolved,
            optional_u64(self.session_generation),
            optional_u64(self.resume_attempted_generation),
            optional_u64(self.resumed_generation),
            optional_u64(self.pause_attempted_generation),
            optional_u64(self.pause_completed_generation),
            self.last_resume_result.map_or("not-attempted", PlatformCallResult::as_str),
            self.last_pause_result.map_or("not-attempted", PlatformCallResult::as_str),
            self.resume_call_count,
            self.pause_call_count,
            self.hard_reset_count,
            self.readiness.hand_adapter_applied,
            self.readiness.hand_tracker_ready,
            self.readiness.hand_frame_ready,
            self.readiness.hand_active,
            self.readiness.hands_ready(),
            self.readiness.controller_action_set_ready,
            self.readiness.controller_profile_ready,
            self.readiness.controller_action_ready,
            self.readiness.controllers_ready(),
            self.combined_ready(),
        )
    }

    pub(crate) fn marker_fields_for_controller_composition(&self) -> String {
        self.marker_fields()
            .split_whitespace()
            .filter(|field| {
                !field.starts_with("controllerActionSetReady=")
                    && !field.starts_with("controllerActionReady=")
            })
            .collect::<Vec<_>>()
            .join(" ")
    }
}

fn optional_u64(value: Option<u64>) -> String {
    value.map_or_else(|| "none".to_owned(), |value| value.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    const B: &str = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    fn selected() -> ActivationDecision {
        resolve_activation(
            SimultaneousHandsControllersSettings::test(true, Some(A), Some(A)),
            true,
        )
    }

    fn platform_ready() -> SimultaneousHandsControllersLifecycle {
        let mut lifecycle = SimultaneousHandsControllersLifecycle::new(selected());
        lifecycle.observe_instance(true, true, true).unwrap();
        lifecycle.observe_system_support(true).unwrap();
        lifecycle
    }

    fn full_readiness() -> IndependentInputReadiness {
        IndependentInputReadiness {
            hand_adapter_applied: true,
            hand_tracker_ready: true,
            hand_frame_ready: true,
            hand_active: true,
            controller_action_set_ready: true,
            controller_profile_ready: true,
            controller_action_ready: true,
        }
    }

    #[test]
    fn controller_composition_preserves_standalone_contract_without_duplicate_action_keys() {
        let controller_fields =
            crate::native_renderer_diagnostics_contract::controller_action_readiness_marker_fields(
                true,
                true,
                true,
                "/interaction_profiles/oculus/touch_controller",
            );
        let mut lifecycle = platform_ready();
        lifecycle.observe_readiness(full_readiness());
        let standalone = lifecycle.marker_fields();
        assert!(standalone.contains("controllerActionSetReady=true"));
        assert!(standalone.contains("controllerActionReady=true"));

        let composed = crate::native_renderer_diagnostics_contract::compose_marker_fields(&[
            "status=readiness frame=7",
            &controller_fields,
            &lifecycle.marker_fields_for_controller_composition(),
        ]);
        assert!(crate::native_renderer_diagnostics_contract::marker_keys_are_unique(&composed));
        assert_eq!(composed.matches("controllerActionSetReady=").count(), 1);
        assert_eq!(composed.matches("controllerActionReady=").count(), 1);
        assert!(composed.contains("controllerProfileReady=true"));
        assert!(composed.contains("controllersReady=true"));
    }

    #[test]
    fn disabled_and_unselected_configuration_is_inert() {
        for settings in [
            SimultaneousHandsControllersSettings::default(),
            SimultaneousHandsControllersSettings::test(false, Some(A), Some(B)),
            SimultaneousHandsControllersSettings::test(false, Some("bad"), Some("bad")),
        ] {
            assert_eq!(
                resolve_activation(settings, false),
                ActivationDecision::Inert
            );
        }
    }

    #[test]
    fn activation_requires_exact_bindings_and_applied_hand_lock() {
        let cases = [
            (
                SimultaneousHandsControllersSettings::test(true, None, Some(A)),
                ActivationRejectReason::PackagedBindingMissing,
            ),
            (
                SimultaneousHandsControllersSettings::test(true, Some("bad"), Some(A)),
                ActivationRejectReason::PackagedBindingMalformed,
            ),
            (
                SimultaneousHandsControllersSettings::test(true, Some(A), None),
                ActivationRejectReason::RuntimeBindingMissing,
            ),
            (
                SimultaneousHandsControllersSettings::test(true, Some(A), Some("bad")),
                ActivationRejectReason::RuntimeBindingMalformed,
            ),
            (
                SimultaneousHandsControllersSettings::test(true, Some(A), Some(B)),
                ActivationRejectReason::BindingMismatch,
            ),
        ];
        for (settings, expected) in cases {
            assert_eq!(
                resolve_activation(settings, true),
                ActivationDecision::Rejected(expected)
            );
        }
        assert_eq!(
            resolve_activation(
                SimultaneousHandsControllersSettings::test(true, Some(A), Some(A)),
                false,
            ),
            ActivationDecision::Rejected(ActivationRejectReason::HandAdapterNotApplied)
        );
        assert!(selected().is_selected());
    }

    #[test]
    fn platform_preconditions_fail_closed() {
        for (available, enabled, functions, expected) in [
            (false, false, false, "extension-unavailable"),
            (true, false, false, "extension-not-enabled"),
            (true, true, false, "extension-functions-unresolved"),
        ] {
            let mut lifecycle = SimultaneousHandsControllersLifecycle::new(selected());
            assert_eq!(
                lifecycle.observe_instance(available, enabled, functions),
                Err(expected)
            );
            assert!(!lifecycle.combined_ready());
        }
        let mut lifecycle = SimultaneousHandsControllersLifecycle::new(selected());
        lifecycle.observe_instance(true, true, true).unwrap();
        assert_eq!(
            lifecycle.observe_system_support(false),
            Err("system-unsupported")
        );
        assert_eq!(lifecycle.begin_session(1), None);
    }

    #[test]
    fn resume_is_generation_fenced_and_duplicate_application_is_inert() {
        let mut lifecycle = platform_ready();
        assert_eq!(
            lifecycle.begin_session(7),
            Some(LifecycleCommand::Resume {
                session_generation: 7
            })
        );
        lifecycle
            .record_resume(7, PlatformCallResult::Success)
            .unwrap();
        assert_eq!(lifecycle.begin_session(7), None);
        assert_eq!(
            lifecycle.record_resume(6, PlatformCallResult::Success),
            Err("stale-session-generation")
        );
        assert_eq!(lifecycle.resume_call_count, 1);
    }

    #[test]
    fn resume_failure_and_session_loss_never_activate() {
        for (result, expected) in [
            (PlatformCallResult::Failure, "resume-failed"),
            (
                PlatformCallResult::SessionLossPending,
                "resume-session-loss-pending",
            ),
        ] {
            let mut lifecycle = platform_ready();
            lifecycle.begin_session(9).unwrap();
            assert_eq!(lifecycle.record_resume(9, result), Err(expected));
            if result == PlatformCallResult::Failure {
                assert_eq!(
                    lifecycle.begin_session(9),
                    None,
                    "a failed resume is not retried inside the same generation"
                );
            }
            lifecycle.observe_readiness(full_readiness());
            assert!(!lifecycle.combined_ready());
        }
    }

    #[test]
    fn pause_and_loss_teardown_do_not_reuse_stale_handles() {
        let mut lifecycle = platform_ready();
        lifecycle.begin_session(11).unwrap();
        lifecycle
            .record_resume(11, PlatformCallResult::Success)
            .unwrap();
        lifecycle.observe_readiness(full_readiness());
        assert!(lifecycle.combined_ready());
        assert_eq!(
            lifecycle.request_pause(),
            Some(LifecycleCommand::Pause {
                session_generation: 11
            })
        );
        lifecycle
            .record_pause(11, PlatformCallResult::Success)
            .unwrap();
        assert_eq!(lifecycle.request_pause(), None);
        assert!(!lifecycle.combined_ready());

        lifecycle.begin_session(12).unwrap();
        lifecycle
            .record_resume(12, PlatformCallResult::Success)
            .unwrap();
        lifecycle.hard_reset(12);
        assert_eq!(lifecycle.request_pause(), None);
        assert!(!lifecycle.combined_ready());
    }

    #[test]
    fn pause_failure_is_one_shot_and_clears_effective_readiness() {
        let mut lifecycle = platform_ready();
        lifecycle.begin_session(13).unwrap();
        lifecycle
            .record_resume(13, PlatformCallResult::Success)
            .unwrap();
        lifecycle.observe_readiness(full_readiness());
        assert!(lifecycle.combined_ready());

        lifecycle.request_pause().unwrap();
        assert_eq!(
            lifecycle.record_pause(13, PlatformCallResult::Failure),
            Err("pause-failed")
        );
        assert!(!lifecycle.combined_ready());
        assert_eq!(lifecycle.request_pause(), None);
    }

    #[test]
    fn aggregate_requires_current_resume_and_independent_live_inputs() {
        let mut lifecycle = platform_ready();
        lifecycle.begin_session(17).unwrap();
        lifecycle
            .record_resume(17, PlatformCallResult::Success)
            .unwrap();

        let mut readiness = full_readiness();
        readiness.controller_action_ready = false;
        lifecycle.observe_readiness(readiness);
        assert!(!lifecycle.combined_ready(), "hands-only must not pass");

        let mut readiness = full_readiness();
        readiness.hand_active = false;
        lifecycle.observe_readiness(readiness);
        assert!(!lifecycle.combined_ready(), "controller-only must not pass");

        lifecycle.observe_readiness(full_readiness());
        assert!(lifecycle.combined_ready());
        assert!(lifecycle
            .marker_fields()
            .contains("simultaneousHandsControllersReady=true"));
    }
}
