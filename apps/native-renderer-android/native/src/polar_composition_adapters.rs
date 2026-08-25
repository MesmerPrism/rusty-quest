//! Bounded, disabled-by-default composition sources for Polar measurements.
//!
//! The Android BLE panel owns raw acquisition. This module owns only neutral,
//! bounded source contracts. Downstream compositions own driver-slot meaning,
//! tuning values, and visual or behavioral interpretation.

use std::{
    collections::VecDeque,
    sync::{Mutex, MutexGuard, OnceLock},
};

use serde_json::{json, Value};

const RR_QUEUE_CAPACITY: usize = 64;
const ACC_QUEUE_CAPACITY: usize = 2_048;
pub(crate) const POLAR_ACC_PRESENTATION_DELAY_NS: u64 = 180_000_000;
const POLAR_ACC_SMOOTHING_TIME_CONSTANT_NS: u64 = 120_000_000;

/// Render-side ACC presentation policy. Both policies retain every decoded
/// sample for capture and calibration; they differ only in how a frame-time
/// observation is presented to the live composition.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum PolarAccPresentationMode {
    /// Follow the newest received sample and ease toward it at render cadence.
    #[default]
    LowLatencySmooth,
    /// Hold one short sample-time buffer and linearly interpolate real samples.
    TimestampFaithful,
}

impl PolarAccPresentationMode {
    pub(crate) fn from_token(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "low-latency-smooth" | "low_latency_smooth" => Some(Self::LowLatencySmooth),
            "timestamp-faithful" | "timestamp_faithful" => Some(Self::TimestampFaithful),
            _ => None,
        }
    }

    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::LowLatencySmooth => "low-latency-smooth",
            Self::TimestampFaithful => "timestamp-faithful",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarAccMeasurement {
    pub(crate) sequence_id: u64,
    pub(crate) host_time_ns: u64,
    pub(crate) sensor_time_ns: u64,
    pub(crate) xyz_mg: [f32; 3],
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarRrMeasurement {
    pub(crate) sequence_id: u64,
    pub(crate) host_time_ns: u64,
    pub(crate) rr_interval_ms: f32,
}

#[derive(Debug, Default)]
struct PolarMeasurementIngress {
    next_acc_sequence_id: u64,
    next_acc_presentation_sequence_id: u64,
    next_rr_sequence_id: u64,
    latest_acc: Option<PolarAccMeasurement>,
    acc_measurements: VecDeque<PolarAccMeasurement>,
    presentation_mode: PolarAccPresentationMode,
    last_acc_presentation_target_ns: Option<u64>,
    last_low_latency_presentation_at_ns: Option<u64>,
    low_latency_smoothed_acc: Option<[f32; 3]>,
    dropped_acc_measurements: u64,
    rr_measurements: VecDeque<PolarRrMeasurement>,
    dropped_rr_measurements: u64,
}

impl PolarMeasurementIngress {
    fn next_acc_sequence_id(&mut self) -> u64 {
        self.next_acc_sequence_id = self.next_acc_sequence_id.saturating_add(1);
        self.next_acc_sequence_id
    }

    fn next_rr_sequence_id(&mut self) -> u64 {
        self.next_rr_sequence_id = self.next_rr_sequence_id.saturating_add(1);
        self.next_rr_sequence_id
    }

    fn submit_acc(&mut self, host_time_ns: u64, sensor_time_ns: u64, xyz_mg: [f32; 3]) {
        let sequence_id = self.next_acc_sequence_id();
        let measurement = PolarAccMeasurement {
            sequence_id,
            host_time_ns,
            sensor_time_ns,
            xyz_mg,
        };
        if self
            .latest_acc
            .is_some_and(|latest| host_time_ns <= latest.host_time_ns)
        {
            self.dropped_acc_measurements = self.dropped_acc_measurements.saturating_add(1);
            return;
        }
        if self.acc_measurements.len() == ACC_QUEUE_CAPACITY {
            self.acc_measurements.pop_front();
            self.dropped_acc_measurements = self.dropped_acc_measurements.saturating_add(1);
        }
        self.acc_measurements.push_back(measurement);
        self.latest_acc = Some(measurement);
    }

    fn submit_rr(&mut self, host_time_ns: u64, rr_interval_ms: f32) {
        let sequence_id = self.next_rr_sequence_id();
        if self.rr_measurements.len() == RR_QUEUE_CAPACITY {
            self.rr_measurements.pop_front();
            self.dropped_rr_measurements = self.dropped_rr_measurements.saturating_add(1);
        }
        self.rr_measurements.push_back(PolarRrMeasurement {
            sequence_id,
            host_time_ns,
            rr_interval_ms,
        });
    }

    fn resample_acc(&mut self, target_host_time_ns: u64) -> Option<PolarAccMeasurement> {
        if self
            .last_acc_presentation_target_ns
            .is_some_and(|previous| target_host_time_ns <= previous)
        {
            return None;
        }
        while self.acc_measurements.len() >= 2
            && self.acc_measurements[1].host_time_ns <= target_host_time_ns
        {
            self.acc_measurements.pop_front();
        }
        let left = *self.acc_measurements.front()?;
        let right = *self.acc_measurements.get(1)?;
        if target_host_time_ns < left.host_time_ns || target_host_time_ns > right.host_time_ns {
            return None;
        }
        let span_ns = right.host_time_ns.saturating_sub(left.host_time_ns);
        if span_ns == 0 {
            return None;
        }
        let alpha = target_host_time_ns.saturating_sub(left.host_time_ns) as f32 / span_ns as f32;
        let mut xyz_mg = [0.0; 3];
        for axis in 0..3 {
            xyz_mg[axis] = left.xyz_mg[axis] + (right.xyz_mg[axis] - left.xyz_mg[axis]) * alpha;
        }
        let sensor_time_ns = left.sensor_time_ns.saturating_add(
            ((right.sensor_time_ns.saturating_sub(left.sensor_time_ns)) as f64 * f64::from(alpha))
                as u64,
        );
        self.next_acc_presentation_sequence_id =
            self.next_acc_presentation_sequence_id.saturating_add(1);
        self.last_acc_presentation_target_ns = Some(target_host_time_ns);
        Some(PolarAccMeasurement {
            sequence_id: self.next_acc_presentation_sequence_id,
            host_time_ns: target_host_time_ns,
            sensor_time_ns,
            xyz_mg,
        })
    }

    fn reset_presentation_state(&mut self) {
        self.last_acc_presentation_target_ns = None;
        self.last_low_latency_presentation_at_ns = None;
        self.low_latency_smoothed_acc = None;
    }

    fn present_low_latency_acc(
        &mut self,
        observed_host_time_ns: u64,
        stale_after_ns: u64,
    ) -> Option<PolarAccMeasurement> {
        let target = self.latest_acc?;
        if observed_host_time_ns.saturating_sub(target.host_time_ns) > stale_after_ns {
            return None;
        }
        if self
            .last_low_latency_presentation_at_ns
            .is_some_and(|previous| observed_host_time_ns <= previous)
        {
            return None;
        }
        let dt_ns = self
            .last_low_latency_presentation_at_ns
            .map(|previous| observed_host_time_ns.saturating_sub(previous))
            .unwrap_or(0);
        let alpha = if self.low_latency_smoothed_acc.is_none() {
            1.0
        } else {
            let ratio = dt_ns as f32 / POLAR_ACC_SMOOTHING_TIME_CONSTANT_NS as f32;
            (1.0 - (-ratio).exp()).clamp(0.0, 1.0)
        };
        let mut xyz_mg = self.low_latency_smoothed_acc.unwrap_or(target.xyz_mg);
        for axis in 0..3 {
            xyz_mg[axis] += (target.xyz_mg[axis] - xyz_mg[axis]) * alpha;
        }
        self.low_latency_smoothed_acc = Some(xyz_mg);
        self.last_low_latency_presentation_at_ns = Some(observed_host_time_ns);
        self.next_acc_presentation_sequence_id =
            self.next_acc_presentation_sequence_id.saturating_add(1);
        Some(PolarAccMeasurement {
            sequence_id: self.next_acc_presentation_sequence_id,
            host_time_ns: observed_host_time_ns,
            sensor_time_ns: target.sensor_time_ns,
            xyz_mg,
        })
    }

    fn presentation_status(&self) -> Value {
        json!({
            "mode": self.presentation_mode.as_str(),
            "timestamp_faithful_delay_ms": POLAR_ACC_PRESENTATION_DELAY_NS / 1_000_000,
            "low_latency_smoothing_time_constant_ms": POLAR_ACC_SMOOTHING_TIME_CONSTANT_NS / 1_000_000,
            "queued_acc_samples": self.acc_measurements.len(),
            "dropped_acc_samples": self.dropped_acc_measurements,
            "latest_acc_sequence_id": self.latest_acc.map(|sample| sample.sequence_id)
        })
    }
}

fn ingress() -> &'static Mutex<PolarMeasurementIngress> {
    static INGRESS: OnceLock<Mutex<PolarMeasurementIngress>> = OnceLock::new();
    INGRESS.get_or_init(|| Mutex::new(PolarMeasurementIngress::default()))
}

fn lock_ingress() -> MutexGuard<'static, PolarMeasurementIngress> {
    ingress()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

pub(crate) fn submit_polar_acc_measurement(
    host_time_ns: u64,
    sensor_time_ns: u64,
    xyz_mg: [f32; 3],
) {
    lock_ingress().submit_acc(host_time_ns, sensor_time_ns, xyz_mg);
}

pub(crate) fn submit_polar_acc_measurement_and_advance_composition(
    host_time_ns: u64,
    sensor_time_ns: u64,
    xyz_mg: [f32; 3],
) {
    submit_polar_acc_measurement(host_time_ns, sensor_time_ns, xyz_mg);
}

pub(crate) fn polar_acc_for_presentation(
    observed_host_time_ns: u64,
    stale_after_ns: u64,
) -> Option<PolarAccMeasurement> {
    let mut state = lock_ingress();
    match state.presentation_mode {
        PolarAccPresentationMode::LowLatencySmooth => {
            state.present_low_latency_acc(observed_host_time_ns, stale_after_ns)
        }
        PolarAccPresentationMode::TimestampFaithful => {
            let target = observed_host_time_ns.saturating_sub(POLAR_ACC_PRESENTATION_DELAY_NS);
            state.resample_acc(target)
        }
    }
}

pub(crate) fn set_polar_acc_presentation_mode(value: &str) -> Result<Value, &'static str> {
    let mode = PolarAccPresentationMode::from_token(value).ok_or("presentation-mode-invalid")?;
    let mut state = lock_ingress();
    if state.presentation_mode != mode {
        state.presentation_mode = mode;
        state.reset_presentation_state();
    }
    let status = state.presentation_status();
    drop(state);
    crate::breath_capture::record_polar_acc_presentation(status.clone());
    Ok(status)
}

pub(crate) fn polar_acc_presentation_status() -> Value {
    lock_ingress().presentation_status()
}

pub(crate) fn submit_polar_rr_measurement(host_time_ns: u64, rr_interval_ms: f32) {
    lock_ingress().submit_rr(host_time_ns, rr_interval_ms);
}

pub(crate) fn latest_polar_acc_after(sequence_id: Option<u64>) -> Option<PolarAccMeasurement> {
    lock_ingress()
        .latest_acc
        .filter(|sample| sequence_id.is_none_or(|last| sample.sequence_id > last))
}

pub(crate) fn polar_rr_after(sequence_id: Option<u64>) -> Vec<PolarRrMeasurement> {
    lock_ingress()
        .rr_measurements
        .iter()
        .copied()
        .filter(|sample| sequence_id.is_none_or(|last| sample.sequence_id > last))
        .collect()
}

pub(crate) fn dropped_polar_rr_measurements() -> u64 {
    lock_ingress().dropped_rr_measurements
}

pub(crate) fn dropped_polar_acc_measurements() -> u64 {
    lock_ingress().dropped_acc_measurements
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PolarAccAxis {
    X,
    Y,
    Z,
    Magnitude,
}

impl PolarAccAxis {
    pub(crate) fn from_token(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "y" => Self::Y,
            "z" => Self::Z,
            "magnitude" | "norm" => Self::Magnitude,
            _ => Self::X,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::X => "x",
            Self::Y => "y",
            Self::Z => "z",
            Self::Magnitude => "magnitude",
        }
    }

    fn select(self, xyz_mg: [f32; 3]) -> f32 {
        match self {
            Self::X => xyz_mg[0],
            Self::Y => xyz_mg[1],
            Self::Z => xyz_mg[2],
            Self::Magnitude => {
                (xyz_mg[0] * xyz_mg[0] + xyz_mg[1] * xyz_mg[1] + xyz_mg[2] * xyz_mg[2]).sqrt()
            }
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
/// Compatibility-only configured-range diagnostic source.
///
/// Calibrated volume is owned by `PolarAccBreathAdapter`; this source must not
/// be represented as calibrated assessment evidence.
pub(crate) struct PolarAccBreathSourceSettings {
    pub(crate) enabled: bool,
    pub(crate) axis: PolarAccAxis,
    pub(crate) input_min_mg: f32,
    pub(crate) input_max_mg: f32,
    pub(crate) stale_seconds: f32,
}

impl PolarAccBreathSourceSettings {
    pub(crate) fn valid(self) -> bool {
        self.input_min_mg.is_finite()
            && self.input_max_mg.is_finite()
            && self.input_max_mg > self.input_min_mg
            && self.stale_seconds.is_finite()
            && self.stale_seconds > 0.0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SourceState {
    Disabled,
    Missing,
    Malformed,
    Stale,
    Ready,
}

impl SourceState {
    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Missing => "missing",
            Self::Malformed => "malformed",
            Self::Stale => "stale",
            Self::Ready => "ready",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct NormalizedSourceSample {
    pub(crate) sequence_id: u64,
    pub(crate) value01: f32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
/// Compatibility-only fixed-range diagnostic normalization.
pub(crate) struct PolarAccBreathSource {
    settings: PolarAccBreathSourceSettings,
    latest: Option<NormalizedSourceSample>,
    latest_host_time_ns: Option<u64>,
    age_seconds: f32,
    state: SourceState,
    malformed_count: u64,
}

impl PolarAccBreathSource {
    pub(crate) fn new(settings: PolarAccBreathSourceSettings) -> Self {
        Self {
            settings,
            latest: None,
            latest_host_time_ns: None,
            age_seconds: 0.0,
            state: if settings.enabled {
                SourceState::Missing
            } else {
                SourceState::Disabled
            },
            malformed_count: 0,
        }
    }

    pub(crate) fn push(&mut self, sample: PolarAccMeasurement) -> bool {
        if !self.settings.enabled {
            self.state = SourceState::Disabled;
            return false;
        }
        let selected = self.settings.axis.select(sample.xyz_mg);
        let timestamp_valid = sample.host_time_ns > 0
            && self
                .latest_host_time_ns
                .is_none_or(|last| sample.host_time_ns >= last);
        if !self.settings.valid()
            || !sample.xyz_mg.iter().all(|value| value.is_finite())
            || !selected.is_finite()
            || !timestamp_valid
        {
            self.malformed_count = self.malformed_count.saturating_add(1);
            self.state = SourceState::Malformed;
            return false;
        }
        let value01 = ((selected - self.settings.input_min_mg)
            / (self.settings.input_max_mg - self.settings.input_min_mg))
            .clamp(0.0, 1.0);
        self.latest = Some(NormalizedSourceSample {
            sequence_id: sample.sequence_id,
            value01,
        });
        self.latest_host_time_ns = Some(sample.host_time_ns);
        self.age_seconds = 0.0;
        self.state = SourceState::Ready;
        true
    }

    pub(crate) fn advance(&mut self, dt_seconds: f32) {
        if !self.settings.enabled {
            self.state = SourceState::Disabled;
            return;
        }
        let dt_seconds = sanitize_dt(dt_seconds);
        self.age_seconds = (self.age_seconds + dt_seconds).min(3600.0);
        if self.latest.is_none() && self.state != SourceState::Malformed {
            self.state = SourceState::Missing;
        } else if self.latest.is_some() && self.age_seconds >= self.settings.stale_seconds {
            self.state = SourceState::Stale;
        }
    }

    pub(crate) fn sample(self) -> Option<NormalizedSourceSample> {
        (self.state == SourceState::Ready)
            .then_some(self.latest)
            .flatten()
    }

    pub(crate) fn state(self) -> SourceState {
        self.state
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "polarAccBreathSourceEnabled={} polarAccBreathSourceAxis={} polarAccBreathSourceState={} polarAccBreathSourceValue01={} polarAccBreathSourceAgeMs={} polarAccBreathSourceMalformedCount={}",
            self.settings.enabled,
            self.settings.axis.marker_value(),
            self.state.marker_value(),
            self.latest
                .map(|sample| format!("{:.3}", sample.value01))
                .unwrap_or_else(|| "none".to_owned()),
            (self.age_seconds * 1000.0).round() as u64,
            self.malformed_count,
        )
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarRrPulseSourceSettings {
    pub(crate) enabled: bool,
    pub(crate) min_rr_ms: f32,
    pub(crate) max_rr_ms: f32,
    pub(crate) stale_seconds: f32,
}

impl PolarRrPulseSourceSettings {
    pub(crate) fn valid(self) -> bool {
        self.min_rr_ms.is_finite()
            && self.max_rr_ms.is_finite()
            && self.min_rr_ms > 0.0
            && self.max_rr_ms > self.min_rr_ms
            && self.stale_seconds.is_finite()
            && self.stale_seconds > 0.0
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarRrPulseSource {
    settings: PolarRrPulseSourceSettings,
    pending_pulse: Option<NormalizedSourceSample>,
    latest_host_time_ns: Option<u64>,
    age_seconds: f32,
    state: SourceState,
    malformed_count: u64,
    accepted_count: u64,
}

impl PolarRrPulseSource {
    pub(crate) fn new(settings: PolarRrPulseSourceSettings) -> Self {
        Self {
            settings,
            pending_pulse: None,
            latest_host_time_ns: None,
            age_seconds: 0.0,
            state: if settings.enabled {
                SourceState::Missing
            } else {
                SourceState::Disabled
            },
            malformed_count: 0,
            accepted_count: 0,
        }
    }

    pub(crate) fn push(&mut self, sample: PolarRrMeasurement) -> bool {
        if !self.settings.enabled {
            self.state = SourceState::Disabled;
            return false;
        }
        let timestamp_valid = sample.host_time_ns > 0
            && self
                .latest_host_time_ns
                .is_none_or(|last| sample.host_time_ns >= last);
        if !self.settings.valid()
            || !sample.rr_interval_ms.is_finite()
            || sample.rr_interval_ms < self.settings.min_rr_ms
            || sample.rr_interval_ms > self.settings.max_rr_ms
            || !timestamp_valid
        {
            self.malformed_count = self.malformed_count.saturating_add(1);
            self.state = SourceState::Malformed;
            return false;
        }
        self.pending_pulse = Some(NormalizedSourceSample {
            sequence_id: sample.sequence_id,
            value01: 1.0,
        });
        self.latest_host_time_ns = Some(sample.host_time_ns);
        self.age_seconds = 0.0;
        self.state = SourceState::Ready;
        self.accepted_count = self.accepted_count.saturating_add(1);
        true
    }

    pub(crate) fn take_pulse(&mut self) -> Option<NormalizedSourceSample> {
        if self.state == SourceState::Ready {
            self.pending_pulse.take()
        } else {
            None
        }
    }

    pub(crate) fn advance(&mut self, dt_seconds: f32) {
        if !self.settings.enabled {
            self.state = SourceState::Disabled;
            return;
        }
        self.age_seconds = (self.age_seconds + sanitize_dt(dt_seconds)).min(3600.0);
        if self.latest_host_time_ns.is_none() && self.state != SourceState::Malformed {
            self.state = SourceState::Missing;
        } else if self.age_seconds >= self.settings.stale_seconds {
            self.state = SourceState::Stale;
        } else if self.state != SourceState::Malformed {
            self.state = SourceState::Ready;
        }
    }

    pub(crate) fn state(self) -> SourceState {
        self.state
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "polarRrPulseSourceEnabled={} polarRrPulseSourceState={} polarRrPulseSourceAgeMs={} polarRrPulseSourceAcceptedCount={} polarRrPulseSourceMalformedCount={}",
            self.settings.enabled,
            self.state.marker_value(),
            (self.age_seconds * 1000.0).round() as u64,
            self.accepted_count,
            self.malformed_count,
        )
    }
}

fn sanitize_dt(dt_seconds: f32) -> f32 {
    if dt_seconds.is_finite() && dt_seconds > 0.0 {
        dt_seconds.min(1.0)
    } else {
        0.0
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeSubmitPolarAccMeasurement(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    sample_host_time_ns: jni::sys::jlong,
    sample_sensor_time_ns: jni::sys::jlong,
    frame_host_receipt_time_ns: jni::sys::jlong,
    frame_sequence_id: jni::sys::jlong,
    sample_index: jni::sys::jint,
    sample_count: jni::sys::jint,
    jni_submit_time_ns: jni::sys::jlong,
    x_mg: jni::sys::jint,
    y_mg: jni::sys::jint,
    z_mg: jni::sys::jint,
) {
    let xyz_mg = [x_mg as f32, y_mg as f32, z_mg as f32];
    submit_polar_acc_measurement_and_advance_composition(
        sample_host_time_ns.max(0) as u64,
        sample_sensor_time_ns.max(0) as u64,
        xyz_mg,
    );
    crate::lsl_panel_runtime::submit_polar_acc(xyz_mg);
    crate::breath_capture::record_polar_acc_sample(
        frame_sequence_id.max(0) as u64,
        sample_index.max(0) as usize,
        sample_count.max(0) as usize,
        frame_host_receipt_time_ns.max(0) as u64,
        sample_host_time_ns.max(0) as u64,
        sample_sensor_time_ns.max(0) as u64,
        jni_submit_time_ns.max(0) as u64,
        xyz_mg,
    );
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeSubmitPolarEcgMeasurement(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    sample_host_time_ns: jni::sys::jlong,
    sample_sensor_time_ns: jni::sys::jlong,
    frame_host_receipt_time_ns: jni::sys::jlong,
    frame_sequence_id: jni::sys::jlong,
    sample_index: jni::sys::jint,
    sample_count: jni::sys::jint,
    jni_submit_time_ns: jni::sys::jlong,
    microvolts: jni::sys::jint,
) {
    crate::lsl_panel_runtime::submit_polar_ecg(microvolts);
    crate::breath_capture::record_polar_ecg_sample(
        frame_sequence_id.max(0) as u64,
        sample_index.max(0) as usize,
        sample_count.max(0) as usize,
        frame_host_receipt_time_ns.max(0) as u64,
        sample_host_time_ns.max(0) as u64,
        sample_sensor_time_ns.max(0) as u64,
        jni_submit_time_ns.max(0) as u64,
        microvolts,
    );
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeSubmitPolarPmdFrame(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    measurement_type: jni::sys::jint,
    frame_sequence_id: jni::sys::jlong,
    host_receipt_time_ns: jni::sys::jlong,
    sensor_frame_time_ns: jni::sys::jlong,
    sample_rate_hz: jni::sys::jint,
    sample_count: jni::sys::jint,
    previous_receipt_delta_ns: jni::sys::jlong,
) {
    let kind = if measurement_type == 0 {
        "polar_ecg_frame"
    } else {
        "polar_acc_frame"
    };
    crate::breath_capture::record_polar_frame(
        kind,
        frame_sequence_id.max(0) as u64,
        host_receipt_time_ns.max(0) as u64,
        sensor_frame_time_ns.max(0) as u64,
        sample_rate_hz.max(0) as u32,
        sample_count.max(0) as usize,
        (previous_receipt_delta_ns > 0).then_some(previous_receipt_delta_ns as u64),
    );
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeSubmitPolarHeartRateMeasurement(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    host_time_ns: jni::sys::jlong,
    bpm: jni::sys::jint,
) {
    crate::lsl_panel_runtime::submit_polar_hr(bpm.max(0) as u32);
    crate::breath_capture::record_polar_hr(host_time_ns.max(0) as u64, bpm.max(0) as u32);
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeSubmitPolarRrMeasurement(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    host_time_ns: jni::sys::jlong,
    rr_interval_ms: jni::sys::jfloat,
) {
    submit_polar_rr_measurement(host_time_ns.max(0) as u64, rr_interval_ms);
    crate::lsl_panel_runtime::submit_polar_rr(rr_interval_ms);
    crate::breath_capture::record_polar_rr(host_time_ns.max(0) as u64, rr_interval_ms);
}

#[cfg(target_os = "android")]
fn jni_string_response(mut env: jni::EnvUnowned, value: Value) -> jni::sys::jstring {
    match env
        .with_env(|env| {
            env.new_string(value.to_string())
                .map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeSetPolarAccPresentationMode(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    mode: jni::objects::JString,
) -> jni::sys::jstring {
    let value = match env.with_env(|env| mode.try_to_string(env)).into_outcome() {
        jni::Outcome::Ok(mode) => match set_polar_acc_presentation_mode(&mode) {
            Ok(presentation) => json!({"status":"accepted", "presentation":presentation}),
            Err(reason) => json!({"status":"rejected", "reason_code":reason}),
        },
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => {
            json!({"status":"rejected", "reason_code":"jni-string-invalid"})
        }
    };
    jni_string_response(env, value)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeReadPolarAccPresentationStatus(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    jni_string_response(env, polar_acc_presentation_status())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn acc_settings(enabled: bool) -> PolarAccBreathSourceSettings {
        PolarAccBreathSourceSettings {
            enabled,
            axis: PolarAccAxis::X,
            input_min_mg: -100.0,
            input_max_mg: 100.0,
            stale_seconds: 1.0,
        }
    }

    fn rr_settings(enabled: bool) -> PolarRrPulseSourceSettings {
        PolarRrPulseSourceSettings {
            enabled,
            min_rr_ms: 200.0,
            max_rr_ms: 3000.0,
            stale_seconds: 1.0,
        }
    }

    #[test]
    fn timestamped_acc_batches_resample_at_render_cadence_with_one_batch_delay() {
        let mut ingress = PolarMeasurementIngress::default();
        for index in 0..=36_u64 {
            ingress.submit_acc(
                1_000_000_000 + index * 5_000_000,
                2_000_000_000 + index * 5_000_000,
                [index as f32, 0.0, 0.0],
            );
        }
        let first = ingress
            .resample_acc(1_002_500_000)
            .expect("bracketed sample");
        let second = ingress
            .resample_acc(1_016_388_889)
            .expect("next render sample");
        assert!((first.xyz_mg[0] - 0.5).abs() < 0.001);
        assert!((second.xyz_mg[0] - 3.277_777_7).abs() < 0.001);
        assert!(second.host_time_ns > first.host_time_ns);
        assert!(second.sequence_id > first.sequence_id);
    }

    #[test]
    fn presentation_resampler_does_not_invent_future_samples_or_repeat_time() {
        let mut ingress = PolarMeasurementIngress::default();
        ingress.submit_acc(100, 200, [0.0, 0.0, 0.0]);
        ingress.submit_acc(200, 300, [1.0, 0.0, 0.0]);
        assert!(ingress.resample_acc(99).is_none());
        assert!(ingress.resample_acc(150).is_some());
        assert!(ingress.resample_acc(150).is_none());
        assert!(ingress.resample_acc(201).is_none());
    }

    #[test]
    fn low_latency_presentation_uses_the_freshest_target_and_eases_at_render_cadence() {
        let mut ingress = PolarMeasurementIngress::default();
        ingress.submit_acc(1_000_000_000, 2_000_000_000, [0.0, 0.0, 0.0]);
        let first = ingress
            .present_low_latency_acc(1_000_000_000, 500_000_000)
            .expect("initial frame");
        assert_eq!(first.xyz_mg, [0.0, 0.0, 0.0]);

        ingress.submit_acc(1_010_000_000, 2_010_000_000, [100.0, 0.0, 0.0]);
        let second = ingress
            .present_low_latency_acc(1_010_000_000, 500_000_000)
            .expect("fresh target frame");
        let third = ingress
            .present_low_latency_acc(1_020_000_000, 500_000_000)
            .expect("next render frame");
        assert!(second.xyz_mg[0] > 7.5 && second.xyz_mg[0] < 8.5);
        assert!(third.xyz_mg[0] > second.xyz_mg[0]);
        assert_eq!(second.sensor_time_ns, 2_010_000_000);
        assert!(third.sequence_id > second.sequence_id);
    }

    #[test]
    fn low_latency_presentation_stales_and_mode_switch_resets_interpolation() {
        let mut ingress = PolarMeasurementIngress::default();
        ingress.submit_acc(1_000, 2_000, [5.0, 0.0, 0.0]);
        assert!(ingress.present_low_latency_acc(1_000, 100).is_some());
        assert!(ingress.present_low_latency_acc(1_101, 100).is_none());

        ingress.presentation_mode = PolarAccPresentationMode::TimestampFaithful;
        ingress.reset_presentation_state();
        ingress.submit_acc(1_200, 2_200, [10.0, 0.0, 0.0]);
        ingress.submit_acc(1_300, 2_300, [20.0, 0.0, 0.0]);
        let interpolated = ingress.resample_acc(1_250).expect("bracketed sample");
        assert!((interpolated.xyz_mg[0] - 15.0).abs() < 0.001);
        assert_eq!(ingress.presentation_mode.as_str(), "timestamp-faithful");
    }

    #[test]
    fn polar_acc_synthetic_conformance_maps_configured_axis() {
        let mut source = PolarAccBreathSource::new(acc_settings(true));
        assert!(source.push(PolarAccMeasurement {
            sequence_id: 1,
            host_time_ns: 10,
            sensor_time_ns: 20,
            xyz_mg: [0.0, 50.0, -50.0],
        }));
        assert_eq!(source.state(), SourceState::Ready);
        assert_eq!(source.sample().unwrap().value01, 0.5);
    }

    #[test]
    fn polar_acc_output_is_bounded() {
        let mut source = PolarAccBreathSource::new(acc_settings(true));
        for (sequence_id, x_mg, expected) in [(1, -1000.0, 0.0), (2, 1000.0, 1.0)] {
            assert!(source.push(PolarAccMeasurement {
                sequence_id,
                host_time_ns: sequence_id,
                sensor_time_ns: sequence_id,
                xyz_mg: [x_mg, 0.0, 0.0],
            }));
            assert_eq!(source.sample().unwrap().value01, expected);
        }
    }

    #[test]
    fn polar_acc_reports_missing_then_stale_input() {
        let mut source = PolarAccBreathSource::new(acc_settings(true));
        assert_eq!(source.state(), SourceState::Missing);
        assert!(source.push(PolarAccMeasurement {
            sequence_id: 1,
            host_time_ns: 1,
            sensor_time_ns: 1,
            xyz_mg: [0.0, 0.0, 0.0],
        }));
        source.advance(1.1);
        assert_eq!(source.state(), SourceState::Stale);
        assert_eq!(source.sample(), None);
    }

    #[test]
    fn polar_acc_rejects_malformed_input() {
        let mut source = PolarAccBreathSource::new(acc_settings(true));
        assert!(!source.push(PolarAccMeasurement {
            sequence_id: 1,
            host_time_ns: 1,
            sensor_time_ns: 1,
            xyz_mg: [f32::NAN, 0.0, 0.0],
        }));
        assert_eq!(source.state(), SourceState::Malformed);
    }

    #[test]
    fn polar_acc_disabled_state_is_inert() {
        let mut source = PolarAccBreathSource::new(acc_settings(false));
        assert!(!source.push(PolarAccMeasurement {
            sequence_id: 1,
            host_time_ns: 1,
            sensor_time_ns: 1,
            xyz_mg: [0.0, 0.0, 0.0],
        }));
        assert_eq!(source.state(), SourceState::Disabled);
        assert_eq!(source.sample(), None);
    }

    #[test]
    fn rr_synthetic_conformance_emits_one_normalized_pulse() {
        let mut source = PolarRrPulseSource::new(rr_settings(true));
        assert!(source.push(PolarRrMeasurement {
            sequence_id: 1,
            host_time_ns: 1,
            rr_interval_ms: 1000.0,
        }));
        assert_eq!(source.take_pulse().unwrap().value01, 1.0);
        assert_eq!(source.take_pulse(), None);
    }

    #[test]
    fn rr_bounds_and_malformed_input_are_rejected() {
        for rr_interval_ms in [199.0, 3001.0, f32::NAN] {
            let mut source = PolarRrPulseSource::new(rr_settings(true));
            assert!(!source.push(PolarRrMeasurement {
                sequence_id: 1,
                host_time_ns: 1,
                rr_interval_ms,
            }));
            assert_eq!(source.state(), SourceState::Malformed);
        }
    }

    #[test]
    fn rr_reports_missing_then_stale_input() {
        let mut source = PolarRrPulseSource::new(rr_settings(true));
        assert_eq!(source.state(), SourceState::Missing);
        assert!(source.push(PolarRrMeasurement {
            sequence_id: 1,
            host_time_ns: 1,
            rr_interval_ms: 1000.0,
        }));
        source.take_pulse();
        source.advance(1.1);
        assert_eq!(source.state(), SourceState::Stale);
    }

    #[test]
    fn rr_disabled_state_is_inert() {
        let mut source = PolarRrPulseSource::new(rr_settings(false));
        assert!(!source.push(PolarRrMeasurement {
            sequence_id: 1,
            host_time_ns: 1,
            rr_interval_ms: 1000.0,
        }));
        assert_eq!(source.state(), SourceState::Disabled);
        assert_eq!(source.take_pulse(), None);
    }

    #[test]
    fn ingress_is_bounded_and_rr_cannot_contaminate_the_acc_lane() {
        let mut ingress = PolarMeasurementIngress::default();
        ingress.submit_acc(1, 1, [1.0, 0.0, 0.0]);
        let first = ingress.latest_acc.unwrap();
        assert_eq!(first.sequence_id, 1);
        for index in 0..3 {
            ingress.submit_rr((index + 1) as u64, 1000.0);
        }
        assert_eq!(ingress.latest_acc, Some(first));
        ingress.submit_acc(2, 2, [2.0, 0.0, 0.0]);
        assert_eq!(ingress.latest_acc.unwrap().sequence_id, 2);
        for index in 0..(RR_QUEUE_CAPACITY + 3) {
            ingress.submit_rr((index + 1) as u64, 1000.0);
        }
        assert_eq!(ingress.rr_measurements.len(), RR_QUEUE_CAPACITY);
        assert_eq!(ingress.dropped_rr_measurements, 6);
        assert_eq!(ingress.latest_acc.unwrap().sequence_id, 2);
    }
}
