//! Neutral RR/periodic-event handoff for generic private-particle driver slots.
//!
//! The adapter emits a bounded `0.0`/`1.0` event signal. Downstream private
//! compositions own the selected slot's meaning, pulse envelope, baseline,
//! decay, visual mapping, and all selected tuning values.

use crate::{
    native_renderer_properties::{
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MAX_RR_MS,
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MIN_RR_MS,
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MODE,
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_STALE_SECONDS,
        PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_TARGET_SLOT,
    },
    native_renderer_property_values::{f32_clamped_value, normalized_property, u32_value},
    polar_composition_adapters::{
        dropped_polar_rr_measurements, polar_rr_after, PolarRrMeasurement, PolarRrPulseSource,
        PolarRrPulseSourceSettings,
    },
};

const DRIVER_SLOT_COUNT: usize = 8;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HeartbeatPulseAdapterMode {
    Disabled,
    PolarRrEvent,
    SyntheticEvent,
}

impl HeartbeatPulseAdapterMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "polar-rr-event" => Self::PolarRrEvent,
            _ => Self::Disabled,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::PolarRrEvent => "polar-rr-event",
            Self::SyntheticEvent => "synthetic-event",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PrivateParticleHeartbeatPulseAdapterSettings {
    mode: HeartbeatPulseAdapterMode,
    target_slot: usize,
    stale_seconds: f32,
    min_rr_ms: f32,
    max_rr_ms: f32,
    synthetic_bpm: f32,
}

impl PrivateParticleHeartbeatPulseAdapterSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            mode: HeartbeatPulseAdapterMode::from_property(lookup(
                PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MODE,
            )),
            target_slot: u32_value(
                lookup(PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_TARGET_SLOT),
                0,
                0,
                (DRIVER_SLOT_COUNT - 1) as u32,
            ) as usize,
            stale_seconds: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_STALE_SECONDS),
                5.0,
                0.016,
                120.0,
            ),
            min_rr_ms: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MIN_RR_MS),
                1.0,
                1.0,
                60_000.0,
            ),
            max_rr_ms: f32_clamped_value(
                lookup(PROP_PRIVATE_PARTICLES_HEARTBEAT_PULSE_MAX_RR_MS),
                60_000.0,
                1.0,
                60_000.0,
            ),
            synthetic_bpm: 60.0,
        }
    }

    pub(crate) fn disabled() -> Self {
        Self {
            mode: HeartbeatPulseAdapterMode::Disabled,
            target_slot: 0,
            stale_seconds: 5.0,
            min_rr_ms: 1.0,
            max_rr_ms: 60_000.0,
            synthetic_bpm: 60.0,
        }
    }

    /// Closed same-APK panel route for the private orbit boost. The panel may
    /// choose only whether it is on; the established private RR bounds, target
    /// slot, and stale policy remain fixed here rather than becoming UI tuning
    /// parameters.
    pub(crate) fn panel_polar_rr_orbit_boost(enabled: bool) -> Self {
        if !enabled {
            return Self::disabled();
        }
        Self {
            mode: HeartbeatPulseAdapterMode::PolarRrEvent,
            target_slot: 5,
            stale_seconds: 2.0,
            min_rr_ms: 200.0,
            max_rr_ms: 3000.0,
            synthetic_bpm: 60.0,
        }
    }

    /// Closed panel route sharing the real RR event's bounded driver slot.
    /// JSON admission owns rejection; this typed constructor normalizes callers.
    pub(crate) fn panel_synthetic_orbit_boost(bpm: f32) -> Self {
        Self {
            mode: HeartbeatPulseAdapterMode::SyntheticEvent,
            synthetic_bpm: if bpm.is_finite() {
                bpm.clamp(30.0, 240.0)
            } else {
                60.0
            },
            ..Self::panel_polar_rr_orbit_boost(true)
        }
    }

    pub(crate) fn synthetic_bpm(self) -> f32 {
        self.synthetic_bpm
    }

    pub(crate) fn enabled(self) -> bool {
        match self.mode {
            HeartbeatPulseAdapterMode::Disabled => false,
            HeartbeatPulseAdapterMode::PolarRrEvent => self.max_rr_ms > self.min_rr_ms,
            HeartbeatPulseAdapterMode::SyntheticEvent => true,
        }
    }

    pub(crate) fn parameter_source(self) -> &'static str {
        match self.mode {
            HeartbeatPulseAdapterMode::SyntheticEvent => "synthetic-periodic-event-source",
            _ if self.enabled() => "polar-rr-normalized-event-source",
            _ => "particle-payload-build-env",
        }
    }

    fn source_settings(self) -> PolarRrPulseSourceSettings {
        PolarRrPulseSourceSettings {
            enabled: self.mode == HeartbeatPulseAdapterMode::PolarRrEvent && self.enabled(),
            min_rr_ms: self.min_rr_ms,
            max_rr_ms: self.max_rr_ms,
            stale_seconds: self.stale_seconds,
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "privateParticleHeartbeatPulseMode={} privateParticleHeartbeatPulseTargetSlot={} privateParticleHeartbeatPulseStaleSeconds={:.3} privateParticleHeartbeatPulseMinRrMs={:.3} privateParticleHeartbeatPulseMaxRrMs={:.3} privateParticleHeartbeatPulseSourceAuthority={} privateParticleHeartbeatPulseSyntheticBpm={:.3}",
            self.mode.marker_value(),
            self.target_slot,
            self.stale_seconds,
            self.min_rr_ms,
            self.max_rr_ms,
            self.parameter_source(),
            self.synthetic_bpm(),
        )
    }
}

impl Default for PrivateParticleHeartbeatPulseAdapterSettings {
    fn default() -> Self {
        Self::disabled()
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PrivateParticleHeartbeatPulseAdapter {
    settings: PrivateParticleHeartbeatPulseAdapterSettings,
    source: PolarRrPulseSource,
    value01: f32,
    last_transport_sequence_id: Option<u64>,
    emitted_pulse_count: u64,
    synthetic_seconds_remaining: Option<f64>,
}

impl PrivateParticleHeartbeatPulseAdapter {
    pub(crate) fn new(settings: PrivateParticleHeartbeatPulseAdapterSettings) -> Self {
        Self {
            settings,
            source: PolarRrPulseSource::new(settings.source_settings()),
            value01: 0.0,
            // The transport keeps bounded history for its consumers. A newly
            // selected source starts after that history rather than replaying it.
            last_transport_sequence_id: Some(Self::fresh_rr_cursor()),
            emitted_pulse_count: 0,
            synthetic_seconds_remaining: None,
        }
    }

    fn fresh_rr_cursor() -> u64 {
        polar_rr_after(None)
            .last()
            .map_or(0, |measurement| measurement.sequence_id)
    }

    pub(crate) fn enabled(self) -> bool {
        self.settings.enabled()
    }

    pub(crate) fn settings(self) -> PrivateParticleHeartbeatPulseAdapterSettings {
        self.settings
    }

    pub(crate) fn reconfigure(&mut self, settings: PrivateParticleHeartbeatPulseAdapterSettings) {
        if self.settings == settings {
            return;
        }
        *self = Self::new(settings);
    }

    pub(crate) fn update_frame(&mut self, dt_seconds: f32) {
        let measurements = if self.settings.mode == HeartbeatPulseAdapterMode::PolarRrEvent {
            polar_rr_after(self.last_transport_sequence_id)
        } else {
            Vec::new()
        };
        self.update_frame_with_measurements(dt_seconds, measurements);
    }

    fn update_frame_with_measurements(
        &mut self,
        dt_seconds: f32,
        measurements: impl IntoIterator<Item = PolarRrMeasurement>,
    ) {
        self.value01 = 0.0;
        if !self.enabled() {
            return;
        }
        if self.settings.mode == HeartbeatPulseAdapterMode::SyntheticEvent {
            if !dt_seconds.is_finite() || dt_seconds <= 0.0 {
                return;
            }
            let remaining = self
                .synthetic_seconds_remaining
                .map_or(0.0, |remaining| remaining - f64::from(dt_seconds));
            if remaining <= 0.0 {
                self.value01 = 1.0;
                self.emitted_pulse_count = self.emitted_pulse_count.saturating_add(1);
                // Drop missed beats and overshoot: every emitted event starts
                // a full period, including the first valid frame after selection.
                self.synthetic_seconds_remaining =
                    Some(60.0 / f64::from(self.settings.synthetic_bpm));
            } else {
                self.synthetic_seconds_remaining = Some(remaining);
            }
            return;
        }
        for measurement in measurements {
            self.last_transport_sequence_id = Some(measurement.sequence_id);
            if self.source.push(measurement) && self.source.take_pulse().is_some() {
                self.value01 = 1.0;
                self.emitted_pulse_count = self.emitted_pulse_count.saturating_add(1);
            }
        }
        self.source.advance(dt_seconds);
    }

    pub(crate) fn apply_to_driver_values(self, values01: &mut [f32]) -> bool {
        if !self.enabled() || self.settings.target_slot >= values01.len() {
            return false;
        }
        values01[self.settings.target_slot] = self.value01;
        true
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "{} privateParticleHeartbeatPulseValue01={:.3} privateParticleHeartbeatPulseEmittedCount={} privateParticleHeartbeatPulseLastTransportSequenceId={} privateParticleHeartbeatPulseDroppedTransportMeasurements={} {}",
            self.settings.marker_fields(),
            self.value01,
            self.emitted_pulse_count,
            self.last_transport_sequence_id
                .map(|value| value.to_string())
                .unwrap_or_else(|| "none".to_owned()),
            dropped_polar_rr_measurements(),
            self.source.marker_fields(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings(enabled: bool) -> PrivateParticleHeartbeatPulseAdapterSettings {
        PrivateParticleHeartbeatPulseAdapterSettings {
            mode: if enabled {
                HeartbeatPulseAdapterMode::PolarRrEvent
            } else {
                HeartbeatPulseAdapterMode::Disabled
            },
            target_slot: 5,
            stale_seconds: 2.0,
            min_rr_ms: 200.0,
            max_rr_ms: 3000.0,
            synthetic_bpm: 60.0,
        }
    }

    fn measurement(sequence_id: u64, rr_interval_ms: f32) -> PolarRrMeasurement {
        PolarRrMeasurement {
            sequence_id,
            host_time_ns: sequence_id,
            rr_interval_ms,
        }
    }

    #[test]
    fn disabled_adapter_does_not_write() {
        let adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(false));
        let mut values = [0.5; 8];
        assert!(!adapter.apply_to_driver_values(&mut values));
        assert_eq!(values, [0.5; 8]);
    }

    #[test]
    fn valid_event_emits_one_bounded_frame_then_returns_to_zero() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(true));
        adapter.update_frame_with_measurements(0.01, [measurement(1, 1000.0)]);
        let mut values = [0.0; 8];
        assert!(adapter.apply_to_driver_values(&mut values));
        assert_eq!(values[5], 1.0);
        adapter.update_frame_with_measurements(0.01, []);
        assert!(adapter.apply_to_driver_values(&mut values));
        assert_eq!(values[5], 0.0);
    }

    #[test]
    fn malformed_or_out_of_bounds_event_does_not_emit() {
        for (sequence_id, value) in [(1, f32::NAN), (2, 199.0), (3, 3001.0)] {
            let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(true));
            adapter.update_frame_with_measurements(0.01, [measurement(sequence_id, value)]);
            assert_eq!(adapter.value01, 0.0);
        }
    }

    #[test]
    fn multiple_valid_events_still_produce_a_normalized_output() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(true));
        adapter
            .update_frame_with_measurements(0.01, [measurement(1, 800.0), measurement(2, 900.0)]);
        assert_eq!(adapter.value01, 1.0);
        assert_eq!(adapter.emitted_pulse_count, 2);
    }

    #[test]
    fn panel_route_reconfigures_only_the_closed_orbit_boost_settings() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(false));
        adapter.reconfigure(
            PrivateParticleHeartbeatPulseAdapterSettings::panel_polar_rr_orbit_boost(true),
        );
        assert!(adapter.enabled());
        assert_eq!(adapter.settings().target_slot, 5);
        adapter.update_frame_with_measurements(0.01, [measurement(1, 900.0)]);
        assert_eq!(adapter.value01, 1.0);

        adapter.reconfigure(
            PrivateParticleHeartbeatPulseAdapterSettings::panel_polar_rr_orbit_boost(false),
        );
        assert!(!adapter.enabled());
        assert_eq!(adapter.value01, 0.0);
        assert_eq!(adapter.emitted_pulse_count, 0);
    }

    #[test]
    fn polar_mode_never_manufactures_beats_without_fresh_rr() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(true));
        for dt in [0.01, 1.0, 4.0, 60.0, f32::NAN, f32::INFINITY, -1.0] {
            adapter.update_frame_with_measurements(dt, []);
            assert_eq!(adapter.value01, 0.0);
        }
        adapter.update_frame_with_measurements(0.01, [measurement(1, 900.0)]);
        for _ in 0..10 {
            adapter.update_frame_with_measurements(1.0, []);
            assert_eq!(adapter.value01, 0.0);
        }
        assert_eq!(adapter.emitted_pulse_count, 1);
    }

    #[test]
    fn polar_selection_ignores_rr_history_from_before_selection() {
        use crate::polar_composition_adapters::submit_polar_rr_measurement;

        submit_polar_rr_measurement(1, 900.0);
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings(true));
        adapter.update_frame(0.01);
        assert_eq!(
            adapter.value01, 0.0,
            "initial selection replayed old RR history"
        );

        adapter.reconfigure(settings(false));
        submit_polar_rr_measurement(2, 900.0);
        adapter.reconfigure(settings(true));
        adapter.update_frame(0.01);
        assert_eq!(
            adapter.value01, 0.0,
            "re-enabling replayed disabled RR history"
        );

        submit_polar_rr_measurement(3, 900.0);
        adapter.update_frame(0.01);
        assert_eq!(adapter.value01, 1.0);
        adapter.update_frame(0.01);
        assert_eq!(adapter.value01, 0.0);

        adapter.reconfigure(
            PrivateParticleHeartbeatPulseAdapterSettings::panel_synthetic_orbit_boost(60.0),
        );
        submit_polar_rr_measurement(4, 900.0);
        adapter.update_frame(0.01);
        assert_eq!(adapter.value01, 1.0);
        adapter.reconfigure(settings(true));
        adapter.update_frame(0.01);
        assert_eq!(
            adapter.value01, 0.0,
            "selecting Polar replayed RR history received in synthetic mode"
        );
        submit_polar_rr_measurement(5, 900.0);
        adapter.update_frame(0.01);
        assert_eq!(adapter.value01, 1.0);
    }

    #[test]
    fn synthetic_events_use_the_same_slot_once_per_period() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(
            PrivateParticleHeartbeatPulseAdapterSettings::panel_synthetic_orbit_boost(60.0),
        );
        let mut values = [0.25; 8];
        for (dt, expected) in [
            (0.01, 1.0),
            (0.25, 0.0),
            (0.25, 0.0),
            (0.25, 0.0),
            (0.25, 1.0),
        ] {
            adapter.update_frame_with_measurements(dt, []);
            assert!(adapter.apply_to_driver_values(&mut values));
            assert_eq!(values[5], expected);
            assert!(values
                .iter()
                .enumerate()
                .all(|(slot, value)| slot == 5 || *value == 0.25));
        }
        assert_eq!(adapter.emitted_pulse_count, 2);
    }

    #[test]
    fn synthetic_delayed_frame_emits_once_and_discards_catch_up_debt() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(
            PrivateParticleHeartbeatPulseAdapterSettings::panel_synthetic_orbit_boost(60.0),
        );
        for (dt, expected) in [
            (0.01, 1.0),
            (10.75, 1.0),
            (0.25, 0.0),
            (0.5, 0.0),
            (0.25, 1.0),
        ] {
            adapter.update_frame_with_measurements(dt, []);
            assert_eq!(adapter.value01, expected);
        }
        assert_eq!(adapter.emitted_pulse_count, 3);
    }

    #[test]
    fn synthetic_invalid_frame_times_do_not_emit_or_advance_the_period() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(
            PrivateParticleHeartbeatPulseAdapterSettings::panel_synthetic_orbit_boost(60.0),
        );
        for dt in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY, -1.0, 0.0] {
            adapter.update_frame_with_measurements(dt, []);
            assert_eq!(adapter.value01, 0.0);
        }
        adapter.update_frame_with_measurements(0.01, []);
        assert_eq!(adapter.value01, 1.0);
        adapter.update_frame_with_measurements(0.5, []);
        assert_eq!(adapter.value01, 0.0);
        for dt in [f32::NAN, f32::INFINITY, -1.0, 0.0] {
            adapter.update_frame_with_measurements(dt, []);
            assert_eq!(adapter.value01, 0.0);
        }
        adapter.update_frame_with_measurements(0.5, []);
        assert_eq!(adapter.value01, 1.0);
        assert_eq!(adapter.emitted_pulse_count, 2);
    }

    #[test]
    fn synthetic_rate_is_normalized_and_controls_the_period() {
        for (requested_bpm, expected_bpm, quarter_period) in [
            (f32::NAN, 60.0, 0.25),
            (f32::INFINITY, 60.0, 0.25),
            (f32::NEG_INFINITY, 60.0, 0.25),
            (-1.0, 30.0, 0.5),
            (30.0, 30.0, 0.5),
            (120.0, 120.0, 0.125),
            (240.0, 240.0, 0.0625),
            (1000.0, 240.0, 0.0625),
        ] {
            let settings =
                PrivateParticleHeartbeatPulseAdapterSettings::panel_synthetic_orbit_boost(
                    requested_bpm,
                );
            assert_eq!(settings.synthetic_bpm(), expected_bpm);
            let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(settings);
            adapter.update_frame_with_measurements(0.01, []);
            assert_eq!(adapter.value01, 1.0);
            for expected in [0.0, 0.0, 0.0, 1.0] {
                adapter.update_frame_with_measurements(quarter_period, []);
                assert_eq!(adapter.value01, expected, "rate {requested_bpm}");
            }
        }
    }

    #[test]
    fn source_and_rate_changes_clear_pulses_and_restart_the_period() {
        let synthetic =
            PrivateParticleHeartbeatPulseAdapterSettings::panel_synthetic_orbit_boost(60.0);
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(synthetic);
        adapter.update_frame_with_measurements(0.01, []);
        adapter.update_frame_with_measurements(0.75, []);
        adapter.reconfigure(synthetic);
        adapter.update_frame_with_measurements(0.25, []);
        assert_eq!(
            adapter.value01, 1.0,
            "unchanged selection must preserve its period"
        );

        adapter.reconfigure(
            PrivateParticleHeartbeatPulseAdapterSettings::panel_synthetic_orbit_boost(120.0),
        );
        assert_eq!(adapter.value01, 0.0);
        assert_eq!(adapter.emitted_pulse_count, 0);
        for (dt, expected) in [(0.01, 1.0), (0.25, 0.0), (0.25, 1.0)] {
            adapter.update_frame_with_measurements(dt, []);
            assert_eq!(adapter.value01, expected);
        }

        adapter.reconfigure(settings(true));
        assert_eq!(adapter.value01, 0.0);
        adapter.update_frame_with_measurements(1.0, []);
        assert_eq!(adapter.value01, 0.0);
        adapter.reconfigure(settings(false));
        adapter.update_frame_with_measurements(10.0, []);
        adapter.reconfigure(synthetic);
        assert_eq!(adapter.value01, 0.0);
        adapter.update_frame_with_measurements(0.01, []);
        assert_eq!(adapter.value01, 1.0);
    }

    #[test]
    fn synthetic_does_not_mix_real_rr_into_its_periodic_events() {
        let mut adapter = PrivateParticleHeartbeatPulseAdapter::new(
            PrivateParticleHeartbeatPulseAdapterSettings::panel_synthetic_orbit_boost(60.0),
        );
        adapter.update_frame_with_measurements(0.01, [measurement(1, 900.0)]);
        adapter.update_frame_with_measurements(0.25, [measurement(2, 900.0)]);
        assert_eq!(adapter.value01, 0.0);
        assert_eq!(adapter.emitted_pulse_count, 1);
    }
}
