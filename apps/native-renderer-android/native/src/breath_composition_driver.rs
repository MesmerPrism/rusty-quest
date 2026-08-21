//! Closed-world composition output to one generic particle-driver slot.
//!
//! This adapter assigns no meaning to the slot. It consumes only the exact
//! current-generation output of the public breath composition authority and
//! leaves all downstream response tuning and visual interpretation to the
//! composing application.

use rusty_quest_breath_contract::{
    assessment::CommonBreathPhase,
    composition::{
        BreathCompositionMapping, BreathCompositionRequest, BreathCompositionSnapshot,
        BreathCompositionSource, BreathCompositionStatus,
    },
    BreathGeneration, BreathTimestampMicros,
};

use crate::{
    bounded_breath_phase_integrator::{BoundedBreathPhaseIntegrator, BreathHoldPolicy},
    native_renderer_properties::{
        PROP_BREATH_COMPOSITION_STALE_MILLIS,
        PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_ACTIVATION_BINDING_SHA256,
        PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_ENABLED,
        PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_EXHALE_RATE,
        PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_HOLD_POLICY,
        PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_INHALE_RATE,
        PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_LOSS_VALUE01,
        PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_TARGET_SLOT,
    },
    native_renderer_property_values::{bool_value, normalized_property, u64_value},
};

const DRIVER_SLOT_COUNT: usize = 8;
const DEFAULT_STALE_MILLIS: u64 = 500;
const DEFAULT_RATE_PER_SECOND: f32 = 0.25;
const DEFAULT_LOSS_VALUE01: f32 = 0.5;

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct BreathCompositionDriverSettings {
    requested_enabled: bool,
    config_valid: bool,
    activation_binding_sha256: Option<[u8; 32]>,
    target_slot: usize,
    inhale_rate_per_second: f32,
    exhale_rate_per_second: f32,
    hold_policy: BreathHoldPolicy,
    loss_value01: f32,
    stale_after_micros: u64,
}

impl BreathCompositionDriverSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let requested_enabled = bool_value(
            lookup(PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_ENABLED),
            false,
        );
        let hold_policy = BreathHoldPolicy::from_token(&normalized_property(lookup(
            PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_HOLD_POLICY,
        )));
        let target_slot = parse_required_u32(
            lookup(PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_TARGET_SLOT),
            0,
            (DRIVER_SLOT_COUNT - 1) as u32,
        );
        let inhale_rate_per_second = parse_required_f32(
            lookup(PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_INHALE_RATE),
            0.0,
            62.5,
        );
        let exhale_rate_per_second = parse_required_f32(
            lookup(PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_EXHALE_RATE),
            0.0,
            62.5,
        );
        let loss_value01 = parse_required_f32(
            lookup(PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_LOSS_VALUE01),
            0.0,
            1.0,
        );
        let config_valid = target_slot.is_some()
            && inhale_rate_per_second.is_some()
            && exhale_rate_per_second.is_some()
            && hold_policy.is_some()
            && loss_value01.is_some();
        Self {
            requested_enabled,
            config_valid,
            activation_binding_sha256: parse_sha256(
                lookup(PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_ACTIVATION_BINDING_SHA256)
                    .as_deref(),
            ),
            target_slot: target_slot.unwrap_or_default() as usize,
            inhale_rate_per_second: inhale_rate_per_second.unwrap_or(DEFAULT_RATE_PER_SECOND),
            exhale_rate_per_second: exhale_rate_per_second.unwrap_or(DEFAULT_RATE_PER_SECOND),
            hold_policy: hold_policy.unwrap_or(BreathHoldPolicy::Hold),
            loss_value01: loss_value01.unwrap_or(DEFAULT_LOSS_VALUE01),
            stale_after_micros: u64_value(
                lookup(PROP_BREATH_COMPOSITION_STALE_MILLIS),
                DEFAULT_STALE_MILLIS,
                1,
                60_000,
            ) * 1_000,
        }
    }

    pub(crate) const fn disabled() -> Self {
        Self {
            requested_enabled: false,
            config_valid: false,
            activation_binding_sha256: None,
            target_slot: 0,
            inhale_rate_per_second: DEFAULT_RATE_PER_SECOND,
            exhale_rate_per_second: DEFAULT_RATE_PER_SECOND,
            hold_policy: BreathHoldPolicy::Hold,
            loss_value01: DEFAULT_LOSS_VALUE01,
            stale_after_micros: DEFAULT_STALE_MILLIS * 1_000,
        }
    }

    pub(crate) const fn enabled(self) -> bool {
        self.requested_enabled && self.config_valid
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "breathCompositionDriverRequested={} breathCompositionDriverTargetSlot={} breathCompositionDriverInhaleRatePerSecond={:.6} breathCompositionDriverExhaleRatePerSecond={:.6} breathCompositionDriverHoldPolicy={} breathCompositionDriverLossValue01={:.6} breathCompositionDriverStaleMillis={}",
            self.requested_enabled,
            self.target_slot,
            self.inhale_rate_per_second,
            self.exhale_rate_per_second,
            self.hold_policy.as_str(),
            self.loss_value01,
            self.stale_after_micros / 1_000,
        )
    }
}

impl Default for BreathCompositionDriverSettings {
    fn default() -> Self {
        Self::disabled()
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum DriverStatus {
    #[default]
    Disabled,
    Rejected,
    Waiting,
    AppliedVolume,
    AppliedState,
}

impl DriverStatus {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Rejected => "rejected",
            Self::Waiting => "waiting",
            Self::AppliedVolume => "applied-volume",
            Self::AppliedState => "applied-state",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct BreathCompositionDriver {
    settings: BreathCompositionDriverSettings,
    integrator: BoundedBreathPhaseIntegrator,
    requested: Option<BreathCompositionRequest>,
    effective: Option<BreathCompositionRequest>,
    generation: Option<BreathGeneration>,
    last_source: Option<BreathCompositionSource>,
    last_mapping: Option<BreathCompositionMapping>,
    last_sequence_id: Option<u64>,
    last_sampled_at: Option<BreathTimestampMicros>,
    last_observed_at: Option<BreathTimestampMicros>,
    pending_reset_slot: Option<(usize, f32)>,
    owns_slot: bool,
    status: DriverStatus,
    reason: &'static str,
    accepted_outputs: u64,
    reset_count: u64,
}

impl BreathCompositionDriver {
    pub(crate) fn new(settings: BreathCompositionDriverSettings) -> Self {
        Self {
            settings,
            integrator: BoundedBreathPhaseIntegrator::new(settings.loss_value01),
            requested: None,
            effective: None,
            generation: None,
            last_source: None,
            last_mapping: None,
            last_sequence_id: None,
            last_sampled_at: None,
            last_observed_at: None,
            pending_reset_slot: None,
            owns_slot: false,
            status: DriverStatus::Disabled,
            reason: "not-observed",
            accepted_outputs: 0,
            reset_count: 0,
        }
    }

    pub(crate) const fn settings(&self) -> BreathCompositionDriverSettings {
        self.settings
    }

    pub(crate) fn update_frame(
        &mut self,
        settings: BreathCompositionDriverSettings,
        snapshot: BreathCompositionSnapshot,
        observed_at: BreathTimestampMicros,
        dt_seconds: f32,
    ) {
        self.requested = snapshot.requested;
        if settings != self.settings {
            self.pending_reset_slot = self
                .owns_slot
                .then_some((self.settings.target_slot, self.settings.loss_value01));
            self.settings = settings;
            self.reset("config-changed");
        }
        if !self.settings.requested_enabled {
            self.owns_slot = false;
            self.status = DriverStatus::Disabled;
            self.reason = "settings-disabled";
            return;
        }
        if !self.settings.config_valid {
            self.reject("malformed-or-missing-config");
            return;
        }
        let Some(expected_binding) = self.settings.activation_binding_sha256 else {
            self.reject("missing-activation-binding");
            return;
        };
        if !snapshot.feature_lock_active
            || snapshot.feature_lock_sha256 != expected_binding
            || snapshot.packaged_feature_lock_sha256 != expected_binding
        {
            self.reject("activation-binding-mismatch");
            return;
        }
        self.owns_slot = true;
        if snapshot.status != BreathCompositionStatus::Running {
            self.wait("composition-not-running");
            return;
        }
        let (Some(effective), Some(generation), Some(output)) =
            (snapshot.effective, snapshot.generation, snapshot.output)
        else {
            self.wait("missing-running-output");
            return;
        };
        if output.source != effective.source || output.mapping != effective.mapping {
            self.wait("output-selection-mismatch");
            return;
        }
        if output.sampled_at > observed_at
            || observed_at.get().saturating_sub(output.sampled_at.get())
                > self.settings.stale_after_micros
        {
            self.wait("stale-output");
            return;
        }
        if self
            .last_observed_at
            .is_some_and(|previous| observed_at < previous)
        {
            self.wait("observed-time-discontinuity");
            return;
        }

        let boundary_changed = self.generation != Some(generation)
            || self.last_source != Some(output.source)
            || self.last_mapping != Some(output.mapping);
        if boundary_changed {
            self.reset("source-generation-mapping-changed");
        } else if let (Some(previous_sequence), Some(previous_sampled_at)) =
            (self.last_sequence_id, self.last_sampled_at)
        {
            let out_of_order = output.sequence_id < previous_sequence
                || (output.sequence_id == previous_sequence
                    && output.sampled_at != previous_sampled_at)
                || (output.sequence_id > previous_sequence
                    && output.sampled_at <= previous_sampled_at);
            let discontinuous = output.sequence_id > previous_sequence
                && output
                    .sampled_at
                    .get()
                    .saturating_sub(previous_sampled_at.get())
                    > self.settings.stale_after_micros;
            if out_of_order {
                self.wait("out-of-order-output");
                return;
            }
            if discontinuous {
                self.wait("sample-time-discontinuity");
                return;
            }
        }

        match effective.mapping {
            BreathCompositionMapping::Volume => {
                let Some(value01) = output
                    .volume01
                    .filter(|value| value.is_finite() && (0.0..=1.0).contains(value))
                else {
                    self.wait("malformed-volume-output");
                    return;
                };
                if output.phase.is_some() {
                    self.wait("malformed-volume-output");
                    return;
                }
                self.integrator.reset(value01 as f32);
                self.status = DriverStatus::AppliedVolume;
            }
            BreathCompositionMapping::State => {
                let Some(phase) = output.phase else {
                    self.wait("malformed-state-output");
                    return;
                };
                if output.volume01.is_some()
                    || matches!(
                        phase,
                        CommonBreathPhase::Unknown | CommonBreathPhase::BadTracking
                    )
                {
                    self.wait("malformed-state-output");
                    return;
                }
                self.integrator.update(
                    phase,
                    dt_seconds,
                    self.settings.inhale_rate_per_second,
                    self.settings.exhale_rate_per_second,
                    self.settings.hold_policy,
                    self.settings.loss_value01,
                );
                self.status = DriverStatus::AppliedState;
            }
        }
        self.effective = Some(effective);
        self.generation = Some(generation);
        self.last_source = Some(output.source);
        self.last_mapping = Some(output.mapping);
        self.last_sequence_id = Some(output.sequence_id);
        self.last_sampled_at = Some(output.sampled_at);
        self.last_observed_at = Some(observed_at);
        self.reason = if boundary_changed {
            "accepted-after-boundary-reset"
        } else {
            "accepted-current-output"
        };
        self.accepted_outputs = self.accepted_outputs.saturating_add(1);
    }

    pub(crate) fn apply_to_driver_values(&mut self, values01: &mut [f32]) -> bool {
        let mut wrote = false;
        if let Some((slot, value01)) = self.pending_reset_slot.take() {
            if let Some(value) = values01.get_mut(slot) {
                *value = value01;
                wrote = true;
            }
        }
        if self.owns_slot {
            if let Some(value) = values01.get_mut(self.settings.target_slot) {
                *value = self.integrator.value01();
                wrote = true;
            }
        }
        wrote
    }

    #[cfg(test)]
    pub(crate) const fn value01(&self) -> f32 {
        self.integrator.value01()
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "{} breathCompositionDriverStatus={} breathCompositionDriverReason={} breathCompositionDriverRequestedSource={} breathCompositionDriverRequestedMapping={} breathCompositionDriverEffectiveSource={} breathCompositionDriverEffectiveMapping={} breathCompositionDriverGeneration={} breathCompositionDriverValue01={:.6} breathCompositionDriverLastSequenceId={} breathCompositionDriverAcceptedOutputs={} breathCompositionDriverResetCount={} breathCompositionDriverRrConsumed=false",
            self.settings.marker_fields(),
            self.status.as_str(),
            self.reason,
            self.requested.map_or("none", |value| value.source.as_str()),
            self.requested.map_or("none", |value| value.mapping.as_str()),
            self.effective.map_or("none", |value| value.source.as_str()),
            self.effective.map_or("none", |value| value.mapping.as_str()),
            self.generation.map_or_else(|| "none".to_owned(), |value| value.get().to_string()),
            self.integrator.value01(),
            self.last_sequence_id.map_or_else(|| "none".to_owned(), |value| value.to_string()),
            self.accepted_outputs,
            self.reset_count,
        )
    }

    fn reject(&mut self, reason: &'static str) {
        if self.owns_slot && self.pending_reset_slot.is_none() {
            self.pending_reset_slot = Some((self.settings.target_slot, self.settings.loss_value01));
        }
        self.owns_slot = false;
        self.reset(reason);
        self.status = DriverStatus::Rejected;
    }

    fn wait(&mut self, reason: &'static str) {
        self.reset(reason);
        self.status = DriverStatus::Waiting;
    }

    fn reset(&mut self, reason: &'static str) {
        self.integrator.reset(self.settings.loss_value01);
        self.effective = None;
        self.generation = None;
        self.last_source = None;
        self.last_mapping = None;
        self.last_sequence_id = None;
        self.last_sampled_at = None;
        self.last_observed_at = None;
        self.reason = reason;
        self.reset_count = self.reset_count.saturating_add(1);
    }
}

fn parse_sha256(value: Option<&str>) -> Option<[u8; 32]> {
    let value = value?.trim();
    if value.len() != 64 {
        return None;
    }
    let mut bytes = [0_u8; 32];
    for (index, output) in bytes.iter_mut().enumerate() {
        *output = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16).ok()?;
    }
    (bytes != [0; 32]).then_some(bytes)
}

fn parse_required_u32(value: Option<String>, min: u32, max: u32) -> Option<u32> {
    value?
        .trim()
        .parse::<u32>()
        .ok()
        .filter(|value| (min..=max).contains(value))
}

fn parse_required_f32(value: Option<String>, min: f32, max: f32) -> Option<f32> {
    value?
        .trim()
        .parse::<f32>()
        .ok()
        .filter(|value| value.is_finite() && (min..=max).contains(value))
}

#[cfg(test)]
mod tests {
    use rusty_quest_breath_contract::{
        composition::{
            BreathCompositionOutput, BreathCompositionTelemetry, ControllerProjectionSelection,
            PolarProjectionSelection,
        },
        BreathTimestampMicros,
    };

    use super::*;

    const BINDING: [u8; 32] = [0x5a; 32];

    fn settings() -> BreathCompositionDriverSettings {
        BreathCompositionDriverSettings {
            requested_enabled: true,
            config_valid: true,
            activation_binding_sha256: Some(BINDING),
            target_slot: 2,
            inhale_rate_per_second: 0.5,
            exhale_rate_per_second: 0.25,
            hold_policy: BreathHoldPolicy::Hold,
            loss_value01: 0.2,
            stale_after_micros: 2_000_000,
        }
    }

    fn request(
        source: BreathCompositionSource,
        mapping: BreathCompositionMapping,
    ) -> BreathCompositionRequest {
        BreathCompositionRequest {
            source,
            mapping,
            controller_projection: ControllerProjectionSelection::DynamicAxis,
            polar_projection: PolarProjectionSelection::Xz,
            inverted: false,
        }
    }

    fn running_snapshot(
        source: BreathCompositionSource,
        mapping: BreathCompositionMapping,
        generation_value: u64,
        sequence_id: u64,
        sampled_at: u64,
        volume01: Option<f64>,
        phase: Option<CommonBreathPhase>,
    ) -> BreathCompositionSnapshot {
        let request = request(source, mapping);
        BreathCompositionSnapshot {
            schema_id: "rusty.quest.breath_composition.snapshot.v1",
            feature_lock_active: true,
            feature_lock_sha256: BINDING,
            packaged_feature_lock_sha256: BINDING,
            requested: Some(request),
            effective: Some(request),
            status: BreathCompositionStatus::Running,
            generation: Some(
                BreathGeneration::new(generation_value).expect("non-zero test generation"),
            ),
            output: Some(BreathCompositionOutput {
                source,
                mapping,
                sequence_id,
                sampled_at: BreathTimestampMicros::new(sampled_at),
                volume01,
                phase,
                quality01: 1.0,
            }),
            latest_assessment: None,
            rejection: None,
            telemetry: BreathCompositionTelemetry::default(),
        }
    }

    #[test]
    fn settings_are_disabled_by_default_and_require_complete_bounded_configuration() {
        assert!(!BreathCompositionDriverSettings::from_property_lookup(|_| None).enabled());
        let complete = BreathCompositionDriverSettings::from_property_lookup(|name| {
            match name {
                "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.enabled" => Some("true".to_owned()),
                "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.activation.binding_sha256" => Some("5a".repeat(32)),
                "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.target_slot" => Some("7".to_owned()),
                "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.inhale.rate_per_second" => Some("0.5".to_owned()),
                "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.exhale.rate_per_second" => Some("0.25".to_owned()),
                "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.hold.policy" => Some("hold".to_owned()),
                "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.loss.value01" => Some("0.2".to_owned()),
                "debug.rustyquest.native_renderer.breath_composition.stale_millis" => Some("750".to_owned()),
                _ => None,
            }
        });
        assert!(complete.enabled());
        assert_eq!(complete.target_slot, 7);
        assert_eq!(complete.stale_after_micros, 750_000);

        for (property, damaged) in [
            (
                PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_TARGET_SLOT,
                "8",
            ),
            (
                PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_INHALE_RATE,
                "nan",
            ),
            (
                PROP_PRIVATE_PARTICLES_BREATH_COMPOSITION_DRIVER_HOLD_POLICY,
                "unknown",
            ),
        ] {
            let candidate = BreathCompositionDriverSettings::from_property_lookup(|name| {
                if name == property {
                    return Some(damaged.to_owned());
                }
                match name {
                    "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.enabled" => Some("true".to_owned()),
                    "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.activation.binding_sha256" => Some("5a".repeat(32)),
                    "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.target_slot" => Some("7".to_owned()),
                    "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.inhale.rate_per_second" => Some("0.5".to_owned()),
                    "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.exhale.rate_per_second" => Some("0.25".to_owned()),
                    "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.hold.policy" => Some("hold".to_owned()),
                    "debug.rustyquest.native_renderer.private_particles.breath_composition_driver.loss.value01" => Some("0.2".to_owned()),
                    _ => None,
                }
            });
            assert!(!candidate.enabled());
        }
    }

    #[test]
    fn all_four_source_mapping_selections_reach_one_neutral_slot() {
        for source in [
            BreathCompositionSource::Controller,
            BreathCompositionSource::PolarAcc,
        ] {
            for mapping in [
                BreathCompositionMapping::Volume,
                BreathCompositionMapping::State,
            ] {
                let mut driver = BreathCompositionDriver::new(settings());
                let (volume, phase) = match mapping {
                    BreathCompositionMapping::Volume => (Some(0.8), None),
                    BreathCompositionMapping::State => (None, Some(CommonBreathPhase::Inhale)),
                };
                driver.update_frame(
                    settings(),
                    running_snapshot(source, mapping, 1, 1, 100, volume, phase),
                    BreathTimestampMicros::new(100),
                    0.1,
                );
                let mut values = [0.0; DRIVER_SLOT_COUNT];
                assert!(driver.apply_to_driver_values(&mut values));
                assert!(values[2] > 0.0);
                let marker = driver.marker_fields();
                assert!(marker.contains(source.as_str()));
                assert!(marker.contains(mapping.as_str()));
                assert!(marker.contains("breathCompositionDriverRrConsumed=false"));
            }
        }
    }

    #[test]
    fn volume_endpoints_and_rapid_accepted_change_apply_immediately() {
        let mut driver = BreathCompositionDriver::new(settings());
        for (sequence, at, expected) in [(1, 100, 0.0), (2, 110, 1.0), (3, 120, 0.91)] {
            driver.update_frame(
                settings(),
                running_snapshot(
                    BreathCompositionSource::Controller,
                    BreathCompositionMapping::Volume,
                    1,
                    sequence,
                    at,
                    Some(expected),
                    None,
                ),
                BreathTimestampMicros::new(at),
                0.0,
            );
            assert!((f64::from(driver.value01()) - expected).abs() < 0.000_001);
        }
    }

    #[test]
    fn state_integration_has_cadence_parity_and_explicit_hold_behavior() {
        for cadence in [72_u64, 90, 120] {
            let mut driver = BreathCompositionDriver::new(settings());
            for frame in 0..cadence {
                let at = frame * 1_000_000 / cadence;
                driver.update_frame(
                    settings(),
                    running_snapshot(
                        BreathCompositionSource::PolarAcc,
                        BreathCompositionMapping::State,
                        4,
                        1,
                        0,
                        None,
                        Some(CommonBreathPhase::Inhale),
                    ),
                    BreathTimestampMicros::new(at),
                    1.0 / cadence as f32,
                );
            }
            assert!((driver.value01() - 0.7).abs() < 0.000_1);
            driver.update_frame(
                settings(),
                running_snapshot(
                    BreathCompositionSource::PolarAcc,
                    BreathCompositionMapping::State,
                    4,
                    2,
                    1_100_000,
                    None,
                    Some(CommonBreathPhase::Hold),
                ),
                BreathTimestampMicros::new(1_100_000),
                0.1,
            );
            assert!((driver.value01() - 0.7).abs() < 0.000_1);
        }
    }

    #[test]
    fn stale_missing_malformed_and_discontinuous_outputs_reset_to_loss() {
        let mut driver = BreathCompositionDriver::new(settings());
        let valid = running_snapshot(
            BreathCompositionSource::Controller,
            BreathCompositionMapping::Volume,
            1,
            1,
            100,
            Some(0.9),
            None,
        );
        driver.update_frame(settings(), valid, BreathTimestampMicros::new(100), 0.01);
        driver.update_frame(
            settings(),
            valid,
            BreathTimestampMicros::new(2_000_101),
            0.01,
        );
        assert_eq!(driver.value01(), settings().loss_value01);
        assert!(driver
            .marker_fields()
            .contains("breathCompositionDriverReason=stale-output"));

        let missing = BreathCompositionSnapshot {
            output: None,
            ..valid
        };
        driver.update_frame(settings(), missing, BreathTimestampMicros::new(200), 0.01);
        assert_eq!(driver.value01(), settings().loss_value01);

        let malformed = running_snapshot(
            BreathCompositionSource::Controller,
            BreathCompositionMapping::Volume,
            1,
            2,
            200,
            Some(f64::NAN),
            None,
        );
        driver.update_frame(settings(), malformed, BreathTimestampMicros::new(200), 0.01);
        assert_eq!(driver.value01(), settings().loss_value01);

        driver.update_frame(settings(), valid, BreathTimestampMicros::new(100), 0.01);
        let gap = running_snapshot(
            BreathCompositionSource::Controller,
            BreathCompositionMapping::Volume,
            1,
            3,
            2_000_101,
            Some(0.8),
            None,
        );
        driver.update_frame(settings(), gap, BreathTimestampMicros::new(2_000_101), 0.01);
        assert_eq!(driver.value01(), settings().loss_value01);
        assert!(driver.marker_fields().contains("sample-time-discontinuity"));
    }

    #[test]
    fn source_generation_slot_and_configuration_switches_clear_prior_state() {
        let mut driver = BreathCompositionDriver::new(settings());
        driver.update_frame(
            settings(),
            running_snapshot(
                BreathCompositionSource::Controller,
                BreathCompositionMapping::Volume,
                1,
                1,
                100,
                Some(0.9),
                None,
            ),
            BreathTimestampMicros::new(100),
            0.01,
        );
        let mut switched = settings();
        switched.target_slot = 5;
        switched.loss_value01 = 0.3;
        driver.update_frame(
            switched,
            running_snapshot(
                BreathCompositionSource::PolarAcc,
                BreathCompositionMapping::State,
                2,
                1,
                200,
                None,
                Some(CommonBreathPhase::Exhale),
            ),
            BreathTimestampMicros::new(200),
            0.1,
        );
        let mut values = [0.9; DRIVER_SLOT_COUNT];
        assert!(driver.apply_to_driver_values(&mut values));
        assert_eq!(values[2], settings().loss_value01);
        assert!(values[5] < switched.loss_value01);
        assert!(driver
            .marker_fields()
            .contains("breathCompositionDriverGeneration=2"));
    }

    #[test]
    fn disabled_unlisted_and_stale_or_mismatched_bindings_are_inert() {
        let snapshot = running_snapshot(
            BreathCompositionSource::Controller,
            BreathCompositionMapping::Volume,
            1,
            1,
            100,
            Some(0.9),
            None,
        );
        for candidate in [
            BreathCompositionDriverSettings::disabled(),
            BreathCompositionDriverSettings {
                activation_binding_sha256: None,
                ..settings()
            },
            BreathCompositionDriverSettings {
                activation_binding_sha256: Some([0x33; 32]),
                ..settings()
            },
        ] {
            let mut driver = BreathCompositionDriver::new(candidate);
            driver.update_frame(candidate, snapshot, BreathTimestampMicros::new(100), 0.01);
            let mut values = [0.4; DRIVER_SLOT_COUNT];
            assert!(!driver.apply_to_driver_values(&mut values));
            assert_eq!(values, [0.4; DRIVER_SLOT_COUNT]);
        }

        let mut driver = BreathCompositionDriver::new(settings());
        driver.update_frame(settings(), snapshot, BreathTimestampMicros::new(100), 0.01);
        let mut values = [0.4; DRIVER_SLOT_COUNT];
        assert!(driver.apply_to_driver_values(&mut values));
        assert_eq!(values[2], 0.9);
        let stale_binding = BreathCompositionDriverSettings {
            activation_binding_sha256: Some([0x33; 32]),
            ..settings()
        };
        driver.update_frame(
            stale_binding,
            snapshot,
            BreathTimestampMicros::new(101),
            0.01,
        );
        assert!(driver.apply_to_driver_values(&mut values));
        assert_eq!(values[2], settings().loss_value01);
    }

    #[test]
    fn out_of_order_and_observed_time_reversal_fail_closed() {
        let mut driver = BreathCompositionDriver::new(settings());
        driver.update_frame(
            settings(),
            running_snapshot(
                BreathCompositionSource::Controller,
                BreathCompositionMapping::Volume,
                1,
                2,
                200,
                Some(0.9),
                None,
            ),
            BreathTimestampMicros::new(200),
            0.01,
        );
        driver.update_frame(
            settings(),
            running_snapshot(
                BreathCompositionSource::Controller,
                BreathCompositionMapping::Volume,
                1,
                1,
                300,
                Some(0.8),
                None,
            ),
            BreathTimestampMicros::new(300),
            0.01,
        );
        assert_eq!(driver.value01(), settings().loss_value01);
        assert!(driver.marker_fields().contains("out-of-order-output"));

        driver.update_frame(
            settings(),
            running_snapshot(
                BreathCompositionSource::Controller,
                BreathCompositionMapping::Volume,
                1,
                3,
                300,
                Some(0.7),
                None,
            ),
            BreathTimestampMicros::new(400),
            0.01,
        );
        driver.update_frame(
            settings(),
            running_snapshot(
                BreathCompositionSource::Controller,
                BreathCompositionMapping::Volume,
                1,
                3,
                300,
                Some(0.7),
                None,
            ),
            BreathTimestampMicros::new(399),
            0.01,
        );
        assert_eq!(driver.value01(), settings().loss_value01);
        assert!(driver
            .marker_fields()
            .contains("breathCompositionDriverReason=observed-time-discontinuity"));
    }

    #[test]
    fn denied_selection_and_lifecycle_or_calibration_loss_reset_the_owned_slot() {
        let valid = running_snapshot(
            BreathCompositionSource::Controller,
            BreathCompositionMapping::Volume,
            1,
            1,
            100,
            Some(0.9),
            None,
        );
        let mut driver = BreathCompositionDriver::new(settings());
        driver.update_frame(settings(), valid, BreathTimestampMicros::new(100), 0.01);

        let lifecycle_loss = BreathCompositionSnapshot {
            status: BreathCompositionStatus::Configured,
            generation: None,
            output: None,
            ..valid
        };
        driver.update_frame(
            settings(),
            lifecycle_loss,
            BreathTimestampMicros::new(110),
            0.01,
        );
        assert_eq!(driver.value01(), settings().loss_value01);
        assert!(driver
            .marker_fields()
            .contains("breathCompositionDriverReason=composition-not-running"));

        let denied = BreathCompositionSnapshot {
            effective: None,
            status: BreathCompositionStatus::RejectedUnavailable,
            generation: None,
            output: None,
            ..valid
        };
        driver.update_frame(settings(), denied, BreathTimestampMicros::new(120), 0.01);
        let mut values = [0.9; DRIVER_SLOT_COUNT];
        assert!(driver.apply_to_driver_values(&mut values));
        assert_eq!(values[2], settings().loss_value01);
        assert!(driver.marker_fields().contains("EffectiveSource=none"));
    }
}
