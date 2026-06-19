//! Native master state for the effective Breathing Room projection target.
//!
//! Profile properties, OpenXR controller actions, and Hostess/Manifold stream
//! samples adapt into this model. The renderer reads the final effective
//! target rect and marker fields from here.

use crate::camera_projection_metadata::TargetRect;

pub(crate) const PROP_PROJECTION_TARGET_CONTROLS: &str =
    "debug.rustyquest.native_renderer.projection.target.controls";
pub(crate) const PROP_PROJECTION_TARGET_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.target.scale";
pub(crate) const PROP_PROJECTION_TARGET_TUNED_MAX_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.target.tuned.max.scale";
pub(crate) const PROP_PROJECTION_TARGET_MIN_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.target.min.scale";
pub(crate) const PROP_PROJECTION_TARGET_MAX_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.target.max.scale";
pub(crate) const PROP_PROJECTION_TARGET_OFFSET_X_UV: &str =
    "debug.rustyquest.native_renderer.projection.target.offset.x.uv";
pub(crate) const PROP_PROJECTION_TARGET_OFFSET_Y_UV: &str =
    "debug.rustyquest.native_renderer.projection.target.offset.y.uv";
pub(crate) const PROP_PROJECTION_TARGET_JOYSTICK_CONTROLS: &str =
    "debug.rustyquest.native_renderer.projection.target.joystick.controls";
pub(crate) const PROP_PROJECTION_TARGET_JOYSTICK_RATE: &str =
    "debug.rustyquest.native_renderer.projection.target.joystick.scale.rate_per_second";
pub(crate) const PROP_PROJECTION_TARGET_BREATH_BRIDGE_MODE: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.bridge.mode";
pub(crate) const PROP_PROJECTION_TARGET_BREATH_STATE_STREAM: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.state.stream";
pub(crate) const PROP_PROJECTION_TARGET_BREATH_VALUE_STREAM: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.value.stream";
pub(crate) const PROP_PROJECTION_TARGET_BREATH_INHALE_SECONDS: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.inhale.seconds.min_to_max";
pub(crate) const PROP_PROJECTION_TARGET_BREATH_EXHALE_SECONDS: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.exhale.seconds.max_to_min";
pub(crate) const PROP_PROJECTION_TARGET_BREATH_SYNTHETIC_PERIOD_SECONDS: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.synthetic.period.seconds";
pub(crate) const PROP_PROJECTION_TARGET_BREATH_HIGH_RATE_JSON_PAYLOAD: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.high_rate_json_payload";
pub(crate) const PROP_PROJECTION_TARGET_MANIFOLD_HOST: &str =
    "debug.rustyquest.native_renderer.manifold.broker.host";
pub(crate) const PROP_PROJECTION_TARGET_MANIFOLD_PORT: &str =
    "debug.rustyquest.native_renderer.manifold.broker.port";
pub(crate) const PROP_PROJECTION_TARGET_MANIFOLD_PATH: &str =
    "debug.rustyquest.native_renderer.manifold.broker.path";

const DEFAULT_BASE_SCALE: f32 = 1.0;
const DEFAULT_MIN_SCALE: f32 = 0.05;
const DEFAULT_MAX_SCALE: f32 = 5.0;
const DEFAULT_JOYSTICK_RATE_PER_SECOND: f32 = 0.45;
const DEFAULT_JOYSTICK_DEADZONE: f32 = 0.18;
const DEFAULT_RAMP_SECONDS: f32 = 4.0;
const DEFAULT_SYNTHETIC_PERIOD_SECONDS: f32 = 6.0;
const DEFAULT_STATE_STREAM: &str = "stream.breath.state";
const DEFAULT_VALUE_STREAM: &str = "stream.breath.state.value";
const DEFAULT_MANIFOLD_HOST: &str = "127.0.0.1";
const DEFAULT_MANIFOLD_PORT: u16 = 8765;
const DEFAULT_MANIFOLD_PATH: &str = "/manifold/v1/events";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BreathBridgeMode {
    Disabled,
    ManifoldState,
    ManifoldValue,
    Synthetic,
}

impl BreathBridgeMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized(value).as_deref() {
            Some("manifold-state" | "manifold_state" | "state" | "pmb-state" | "pmb_state") => {
                Self::ManifoldState
            }
            Some(
                "manifold-value"
                | "manifold_state_value"
                | "manifold-state-value"
                | "state-value"
                | "state_value"
                | "pmb-state-value"
                | "pmb_state_value"
                | "value",
            ) => Self::ManifoldValue,
            Some("synthetic" | "synthetic-state" | "synthetic_state") => Self::Synthetic,
            _ => Self::Disabled,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::ManifoldState => "manifold-state",
            Self::ManifoldValue => "manifold-state-value",
            Self::Synthetic => "synthetic",
        }
    }

    pub(crate) fn uses_breath_stream(self) -> bool {
        matches!(
            self,
            Self::ManifoldState | Self::ManifoldValue | Self::Synthetic
        )
    }
}

#[derive(Clone, Debug)]
pub(crate) struct ProjectionTargetSettings {
    pub(crate) controls_enabled: bool,
    pub(crate) base_scale: f32,
    pub(crate) tuned_max_scale: f32,
    pub(crate) min_scale: f32,
    pub(crate) max_scale: f32,
    pub(crate) offset_uv: [f32; 2],
    pub(crate) joystick_controls_enabled: bool,
    pub(crate) joystick_rate_per_second: f32,
    pub(crate) breath_bridge_mode: BreathBridgeMode,
    pub(crate) breath_state_stream: String,
    pub(crate) breath_value_stream: String,
    pub(crate) breath_inhale_seconds_min_to_max: f32,
    pub(crate) breath_exhale_seconds_max_to_min: f32,
    pub(crate) breath_synthetic_period_seconds: f32,
    pub(crate) high_rate_json_payload_enabled: bool,
    pub(crate) manifold_broker_host: String,
    pub(crate) manifold_broker_port: u16,
    pub(crate) manifold_broker_path: String,
}

impl ProjectionTargetSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let min_scale = f32_clamped_value(
            lookup(PROP_PROJECTION_TARGET_MIN_SCALE),
            DEFAULT_MIN_SCALE,
            0.01,
            DEFAULT_MAX_SCALE,
        );
        let max_scale = f32_clamped_value(
            lookup(PROP_PROJECTION_TARGET_MAX_SCALE),
            DEFAULT_MAX_SCALE,
            min_scale,
            10.0,
        );
        let base_scale = f32_clamped_value(
            lookup(PROP_PROJECTION_TARGET_SCALE),
            DEFAULT_BASE_SCALE,
            min_scale,
            max_scale,
        );
        let tuned_max_scale = f32_clamped_value(
            lookup(PROP_PROJECTION_TARGET_TUNED_MAX_SCALE),
            base_scale,
            min_scale,
            max_scale,
        );
        let manifold_broker_port = u16_value(
            lookup(PROP_PROJECTION_TARGET_MANIFOLD_PORT),
            DEFAULT_MANIFOLD_PORT,
            1,
            u16::MAX,
        );
        Self {
            controls_enabled: bool_value(lookup(PROP_PROJECTION_TARGET_CONTROLS), false),
            base_scale,
            tuned_max_scale,
            min_scale,
            max_scale,
            offset_uv: [
                f32_clamped_value(lookup(PROP_PROJECTION_TARGET_OFFSET_X_UV), 0.0, -1.0, 1.0),
                f32_clamped_value(lookup(PROP_PROJECTION_TARGET_OFFSET_Y_UV), 0.0, -1.0, 1.0),
            ],
            joystick_controls_enabled: bool_value(
                lookup(PROP_PROJECTION_TARGET_JOYSTICK_CONTROLS),
                false,
            ),
            joystick_rate_per_second: f32_clamped_value(
                lookup(PROP_PROJECTION_TARGET_JOYSTICK_RATE),
                DEFAULT_JOYSTICK_RATE_PER_SECOND,
                0.0,
                8.0,
            ),
            breath_bridge_mode: BreathBridgeMode::from_property(lookup(
                PROP_PROJECTION_TARGET_BREATH_BRIDGE_MODE,
            )),
            breath_state_stream: non_empty_value(
                lookup(PROP_PROJECTION_TARGET_BREATH_STATE_STREAM),
                DEFAULT_STATE_STREAM,
            ),
            breath_value_stream: non_empty_value(
                lookup(PROP_PROJECTION_TARGET_BREATH_VALUE_STREAM),
                DEFAULT_VALUE_STREAM,
            ),
            breath_inhale_seconds_min_to_max: f32_clamped_value(
                lookup(PROP_PROJECTION_TARGET_BREATH_INHALE_SECONDS),
                DEFAULT_RAMP_SECONDS,
                0.05,
                120.0,
            ),
            breath_exhale_seconds_max_to_min: f32_clamped_value(
                lookup(PROP_PROJECTION_TARGET_BREATH_EXHALE_SECONDS),
                DEFAULT_RAMP_SECONDS,
                0.05,
                120.0,
            ),
            breath_synthetic_period_seconds: f32_clamped_value(
                lookup(PROP_PROJECTION_TARGET_BREATH_SYNTHETIC_PERIOD_SECONDS),
                DEFAULT_SYNTHETIC_PERIOD_SECONDS,
                0.25,
                120.0,
            ),
            high_rate_json_payload_enabled: bool_value(
                lookup(PROP_PROJECTION_TARGET_BREATH_HIGH_RATE_JSON_PAYLOAD),
                false,
            ),
            manifold_broker_host: non_empty_value(
                lookup(PROP_PROJECTION_TARGET_MANIFOLD_HOST),
                DEFAULT_MANIFOLD_HOST,
            ),
            manifold_broker_port,
            manifold_broker_path: non_empty_value(
                lookup(PROP_PROJECTION_TARGET_MANIFOLD_PATH),
                DEFAULT_MANIFOLD_PATH,
            ),
        }
    }

    pub(crate) fn disabled_for_volume_only_route() -> Self {
        Self::default()
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "projectionTargetControlsEnabled={} projectionTargetBaseScale={:.4} projectionTargetTunedMaxScale={:.4} projectionTargetMinScale={:.4} projectionTargetMaxScale={:.4} projectionTargetOffsetUv={:.6},{:.6} projectionTargetJoystickControlsEnabled={} projectionTargetJoystickRatePerSecond={:.4} breathBridgeMode={} breathStateStream={} breathValueStream={} breathRampInhaleSeconds={:.4} breathRampExhaleSeconds={:.4} breathSyntheticPeriodSeconds={:.4} breathHighRateJsonPayload={} manifoldBrokerHost={} manifoldBrokerPort={} manifoldBrokerPath={}",
            self.controls_enabled,
            self.base_scale,
            self.tuned_max_scale,
            self.min_scale,
            self.max_scale,
            self.offset_uv[0],
            self.offset_uv[1],
            self.joystick_controls_enabled,
            self.joystick_rate_per_second,
            self.breath_bridge_mode.marker_value(),
            marker_token(&self.breath_state_stream),
            marker_token(&self.breath_value_stream),
            self.breath_inhale_seconds_min_to_max,
            self.breath_exhale_seconds_max_to_min,
            self.breath_synthetic_period_seconds,
            self.high_rate_json_payload_enabled,
            marker_token(&self.manifold_broker_host),
            self.manifold_broker_port,
            marker_token(&self.manifold_broker_path),
        )
    }
}

impl Default for ProjectionTargetSettings {
    fn default() -> Self {
        Self::from_property_lookup(|_| None)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ProjectionTargetBreathState {
    Unknown,
    Inhale,
    Exhale,
    Pause,
    BadTracking,
}

impl ProjectionTargetBreathState {
    pub(crate) fn from_text(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "inhale" | "inhaling" | "inspiration" | "breath_in" | "breath-in" => Self::Inhale,
            "exhale" | "exhaling" | "expiration" | "breath_out" | "breath-out" => Self::Exhale,
            "pause" | "hold" | "retention" | "retained" | "still" => Self::Pause,
            "bad_tracking" | "bad-tracking" | "badtracking" | "lost" | "invalid" => {
                Self::BadTracking
            }
            _ => Self::Unknown,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Unknown => "unknown",
            Self::Inhale => "inhale",
            Self::Exhale => "exhale",
            Self::Pause => "pause",
            Self::BadTracking => "bad-tracking",
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) enum ProjectionTargetInput {
    JoystickScaleDelta {
        right_thumbstick_y: f32,
        dt_seconds: f32,
    },
    ResetBaseScale,
    ToggleScaleDriver,
    BreathState {
        state: ProjectionTargetBreathState,
        sequence_id: Option<u64>,
    },
    BreathValue {
        value01: f32,
        sequence_id: Option<u64>,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ProjectionTargetScaleDriver {
    Joystick,
    Pmb,
}

impl ProjectionTargetScaleDriver {
    fn initial(settings: &ProjectionTargetSettings) -> Self {
        if settings.breath_bridge_mode.uses_breath_stream() {
            Self::Pmb
        } else {
            Self::Joystick
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Joystick => "joystick",
            Self::Pmb => "pmb",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ProjectionTargetScaleSource {
    ProfileDefaults,
    Controller,
    ControllerReset,
    BreathStateRamp,
    BreathValue,
}

impl ProjectionTargetScaleSource {
    fn marker_value(self) -> &'static str {
        match self {
            Self::ProfileDefaults => "profile-defaults",
            Self::Controller => "right-controller-thumbstick",
            Self::ControllerReset => "right-controller-primary-reset",
            Self::BreathStateRamp => "hostess-manifold-breath-state-ramp",
            Self::BreathValue => "hostess-manifold-breath-state-value",
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct ProjectionTargetState {
    settings: ProjectionTargetSettings,
    base_scale: f32,
    tuned_max_scale: f32,
    live_scale: f32,
    source: ProjectionTargetScaleSource,
    breath_state: ProjectionTargetBreathState,
    breath_age_seconds: f32,
    last_breath_sequence_id: Option<u64>,
    last_breath_value01: Option<f32>,
    state_ramp_dt_seconds: f32,
    received_breath_samples: u64,
    scale_driver: ProjectionTargetScaleDriver,
    last_scale_driver_switch: &'static str,
}

impl ProjectionTargetState {
    pub(crate) fn new(settings: ProjectionTargetSettings) -> Self {
        let base_scale = settings
            .base_scale
            .clamp(settings.min_scale, settings.max_scale);
        let tuned_max_scale = settings
            .tuned_max_scale
            .max(base_scale)
            .clamp(settings.min_scale, settings.max_scale);
        let scale_driver = ProjectionTargetScaleDriver::initial(&settings);
        Self {
            settings,
            base_scale,
            tuned_max_scale,
            live_scale: base_scale,
            source: ProjectionTargetScaleSource::ProfileDefaults,
            breath_state: ProjectionTargetBreathState::Unknown,
            breath_age_seconds: 0.0,
            last_breath_sequence_id: None,
            last_breath_value01: None,
            state_ramp_dt_seconds: 0.0,
            received_breath_samples: 0,
            scale_driver,
            last_scale_driver_switch: "profile-default",
        }
    }

    pub(crate) fn apply_input(&mut self, input: ProjectionTargetInput) {
        if !self.settings.controls_enabled {
            return;
        }
        match input {
            ProjectionTargetInput::JoystickScaleDelta {
                right_thumbstick_y,
                dt_seconds,
            } => self.apply_joystick(right_thumbstick_y, dt_seconds),
            ProjectionTargetInput::ResetBaseScale => {
                if self.scale_driver == ProjectionTargetScaleDriver::Joystick {
                    self.tuned_max_scale = self.base_scale;
                }
                self.live_scale = self.base_scale;
                self.breath_state = ProjectionTargetBreathState::Unknown;
                self.last_breath_value01 = None;
                self.source = ProjectionTargetScaleSource::ControllerReset;
            }
            ProjectionTargetInput::ToggleScaleDriver => self.toggle_scale_driver(),
            ProjectionTargetInput::BreathState { state, sequence_id } => {
                if self.settings.breath_bridge_mode.uses_breath_stream() {
                    self.breath_state = state;
                    self.breath_age_seconds = 0.0;
                    self.last_breath_sequence_id = sequence_id;
                    self.received_breath_samples = self.received_breath_samples.saturating_add(1);
                    if self.scale_driver == ProjectionTargetScaleDriver::Pmb {
                        self.source = ProjectionTargetScaleSource::BreathStateRamp;
                    }
                }
            }
            ProjectionTargetInput::BreathValue {
                value01,
                sequence_id,
            } => {
                if self.settings.breath_bridge_mode.uses_breath_stream() {
                    let clamped = value01.clamp(0.0, 1.0);
                    self.last_breath_value01 = Some(clamped);
                    if self.scale_driver == ProjectionTargetScaleDriver::Pmb {
                        self.live_scale =
                            self.base_scale + (self.tuned_max_scale - self.base_scale) * clamped;
                        self.source = ProjectionTargetScaleSource::BreathValue;
                    }
                    self.breath_age_seconds = 0.0;
                    self.last_breath_sequence_id = sequence_id;
                    self.received_breath_samples = self.received_breath_samples.saturating_add(1);
                }
            }
        }
    }

    pub(crate) fn update_frame(&mut self, dt_seconds: f32) {
        if !self.settings.controls_enabled {
            self.live_scale = self.base_scale;
            return;
        }
        let dt_seconds = sanitize_dt(dt_seconds);
        self.state_ramp_dt_seconds = dt_seconds;
        self.breath_age_seconds += dt_seconds;

        if self.scale_driver == ProjectionTargetScaleDriver::Joystick
            || !self.settings.breath_bridge_mode.uses_breath_stream()
        {
            self.live_scale = self.tuned_max_scale;
            return;
        }

        match self.breath_state {
            ProjectionTargetBreathState::Inhale => {
                self.live_scale = step_toward(
                    self.live_scale,
                    self.tuned_max_scale,
                    self.scale_delta_per_second(self.settings.breath_inhale_seconds_min_to_max)
                        * dt_seconds,
                );
                self.source = ProjectionTargetScaleSource::BreathStateRamp;
            }
            ProjectionTargetBreathState::Exhale => {
                self.live_scale = step_toward(
                    self.live_scale,
                    self.base_scale,
                    self.scale_delta_per_second(self.settings.breath_exhale_seconds_max_to_min)
                        * dt_seconds,
                );
                self.source = ProjectionTargetScaleSource::BreathStateRamp;
            }
            ProjectionTargetBreathState::Pause
            | ProjectionTargetBreathState::BadTracking
            | ProjectionTargetBreathState::Unknown => {}
        }
        self.live_scale = self.clamp_scale(self.live_scale);
    }

    pub(crate) fn effective_rect(&self, base_rect: TargetRect) -> TargetRect {
        if !self.settings.controls_enabled {
            return base_rect;
        }
        scale_rect_around_center(
            base_rect,
            self.live_scale,
            self.settings.offset_uv[0],
            self.settings.offset_uv[1],
        )
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "{} projectionTargetLiveScale={:.4} projectionTargetScaleSource={} projectionTargetScaleDriver={} projectionTargetPmbAvailable={} projectionTargetScaleDriverSwitch={} breathState={} breathStateAgeMs={} breathLastSequenceId={} breathLastValue01={} breathReceivedSamples={} stateRampDtSeconds={:.6} projectionTargetRuntimeAuthority=native-renderer",
            self.settings.marker_fields(),
            self.live_scale,
            self.source.marker_value(),
            self.scale_driver.marker_value(),
            self.pmb_available(),
            self.last_scale_driver_switch,
            self.breath_state.marker_value(),
            (self.breath_age_seconds * 1000.0).round() as u64,
            self.last_breath_sequence_id
                .map(|value| value.to_string())
                .unwrap_or_else(|| "none".to_string()),
            self.last_breath_value01
                .map(|value| format!("{value:.4}"))
                .unwrap_or_else(|| "none".to_string()),
            self.received_breath_samples,
            self.state_ramp_dt_seconds,
        )
    }

    pub(crate) fn breath_haptics_enabled(&self) -> bool {
        self.settings.controls_enabled
            && self.settings.breath_bridge_mode.uses_breath_stream()
            && self.scale_driver == ProjectionTargetScaleDriver::Pmb
    }

    #[cfg(test)]
    pub(crate) fn live_scale(&self) -> f32 {
        self.live_scale
    }

    fn apply_joystick(&mut self, right_thumbstick_y: f32, dt_seconds: f32) {
        if !self.settings.joystick_controls_enabled {
            return;
        }
        if self.scale_driver != ProjectionTargetScaleDriver::Joystick {
            return;
        }
        if !right_thumbstick_y.is_finite() || right_thumbstick_y.abs() <= DEFAULT_JOYSTICK_DEADZONE
        {
            return;
        }
        let dt_seconds = sanitize_dt(dt_seconds);
        let signed = if right_thumbstick_y > 0.0 {
            right_thumbstick_y - DEFAULT_JOYSTICK_DEADZONE
        } else {
            right_thumbstick_y + DEFAULT_JOYSTICK_DEADZONE
        };
        self.tuned_max_scale = self.clamp_scale(
            self.tuned_max_scale + signed * self.settings.joystick_rate_per_second * dt_seconds,
        );
        self.live_scale = self.tuned_max_scale;
        self.source = ProjectionTargetScaleSource::Controller;
    }

    fn toggle_scale_driver(&mut self) {
        if self.scale_driver == ProjectionTargetScaleDriver::Pmb {
            self.scale_driver = ProjectionTargetScaleDriver::Joystick;
            self.tuned_max_scale = self.clamp_scale(self.live_scale);
            self.live_scale = self.tuned_max_scale;
            self.source = ProjectionTargetScaleSource::Controller;
            self.last_scale_driver_switch = "right-controller-secondary-to-joystick";
            return;
        }
        if self.settings.breath_bridge_mode.uses_breath_stream() && self.pmb_available() {
            self.scale_driver = ProjectionTargetScaleDriver::Pmb;
            self.last_scale_driver_switch = "right-controller-secondary-to-pmb";
            match self.settings.breath_bridge_mode {
                BreathBridgeMode::ManifoldValue => {
                    if let Some(value01) = self.last_breath_value01 {
                        self.live_scale =
                            self.base_scale + (self.tuned_max_scale - self.base_scale) * value01;
                    }
                    self.source = ProjectionTargetScaleSource::BreathValue;
                }
                BreathBridgeMode::ManifoldState | BreathBridgeMode::Synthetic => {
                    self.source = ProjectionTargetScaleSource::BreathStateRamp;
                }
                BreathBridgeMode::Disabled => {}
            }
        } else {
            self.last_scale_driver_switch = "right-controller-secondary-pmb-unavailable-no-samples";
        }
    }

    fn pmb_available(&self) -> bool {
        self.received_breath_samples > 0
    }

    fn scale_delta_per_second(&self, seconds: f32) -> f32 {
        ((self.tuned_max_scale - self.base_scale).abs() / seconds.max(0.05)).max(0.0)
    }

    fn clamp_scale(&self, value: f32) -> f32 {
        value.clamp(self.settings.min_scale, self.settings.max_scale)
    }
}

pub(crate) fn marker_token(value: &str) -> String {
    value
        .trim()
        .replace('\0', "")
        .replace(|character: char| character.is_whitespace(), "_")
        .replace(',', "_")
        .replace(';', "_")
}

fn scale_rect_around_center(
    base_rect: TargetRect,
    scale: f32,
    offset_x_uv: f32,
    offset_y_uv: f32,
) -> TargetRect {
    let scale = scale.max(0.0001);
    let width = (base_rect.width * scale).clamp(0.0001, 1.0);
    let height = (base_rect.height * scale).clamp(0.0001, 1.0);
    let center_x = base_rect.x + base_rect.width * 0.5 + offset_x_uv;
    let center_y = base_rect.y + base_rect.height * 0.5 + offset_y_uv;
    let x = (center_x - width * 0.5).clamp(0.0, 1.0 - width);
    let y = (center_y - height * 0.5).clamp(0.0, 1.0 - height);
    TargetRect {
        x,
        y,
        width,
        height,
    }
}

fn step_toward(current: f32, target: f32, max_delta: f32) -> f32 {
    if (target - current).abs() <= max_delta {
        target
    } else if target > current {
        current + max_delta
    } else {
        current - max_delta
    }
}

fn sanitize_dt(dt_seconds: f32) -> f32 {
    if dt_seconds.is_finite() && dt_seconds > 0.0 {
        dt_seconds.min(1.0)
    } else {
        0.0
    }
}

fn bool_value(value: Option<String>, default_value: bool) -> bool {
    value.map_or(default_value, |value| {
        matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on"
        )
    })
}

fn u16_value(value: Option<String>, default_value: u16, min_value: u16, max_value: u16) -> u16 {
    value
        .and_then(|value| value.trim().parse::<u16>().ok())
        .filter(|value| *value >= min_value)
        .unwrap_or(default_value)
        .min(max_value)
}

fn f32_clamped_value(
    value: Option<String>,
    default_value: f32,
    min_value: f32,
    max_value: f32,
) -> f32 {
    value
        .and_then(|value| value.trim().parse::<f32>().ok())
        .filter(|value| value.is_finite())
        .unwrap_or(default_value)
        .clamp(min_value, max_value)
}

fn non_empty_value(value: Option<String>, default_value: &str) -> String {
    value
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| default_value.to_string())
}

fn normalized(value: Option<String>) -> Option<String> {
    value
        .map(|value| value.trim().to_ascii_lowercase())
        .filter(|value| !value.is_empty())
}

#[cfg(test)]
mod tests {
    use super::{
        BreathBridgeMode, ProjectionTargetBreathState, ProjectionTargetInput,
        ProjectionTargetSettings, ProjectionTargetState, PROP_PROJECTION_TARGET_BREATH_BRIDGE_MODE,
        PROP_PROJECTION_TARGET_CONTROLS, PROP_PROJECTION_TARGET_JOYSTICK_CONTROLS,
        PROP_PROJECTION_TARGET_SCALE, PROP_PROJECTION_TARGET_TUNED_MAX_SCALE,
    };
    use crate::camera_projection_metadata::TargetRect;
    use std::collections::BTreeMap;

    fn settings_from(values: &[(&str, &str)]) -> ProjectionTargetSettings {
        let values = values.iter().copied().collect::<BTreeMap<_, _>>();
        ProjectionTargetSettings::from_property_lookup(|name| {
            values.get(name).map(|value| (*value).to_owned())
        })
    }

    fn enabled_settings() -> ProjectionTargetSettings {
        settings_from(&[
            (PROP_PROJECTION_TARGET_CONTROLS, "true"),
            (PROP_PROJECTION_TARGET_JOYSTICK_CONTROLS, "true"),
            (PROP_PROJECTION_TARGET_SCALE, "1.0"),
            (PROP_PROJECTION_TARGET_TUNED_MAX_SCALE, "1.5"),
        ])
    }

    #[test]
    fn parses_profile_defaults() {
        let settings = settings_from(&[
            (PROP_PROJECTION_TARGET_CONTROLS, "true"),
            (
                PROP_PROJECTION_TARGET_BREATH_BRIDGE_MODE,
                "manifold-state-value",
            ),
        ]);
        assert!(settings.controls_enabled);
        assert_eq!(settings.breath_bridge_mode, BreathBridgeMode::ManifoldValue);
        assert!(settings
            .marker_fields()
            .contains("breathBridgeMode=manifold-state-value"));
    }

    #[test]
    fn joystick_tunes_scale_and_reset_returns_to_base() {
        let mut state = ProjectionTargetState::new(enabled_settings());
        state.apply_input(ProjectionTargetInput::JoystickScaleDelta {
            right_thumbstick_y: 1.0,
            dt_seconds: 1.0,
        });
        state.update_frame(1.0 / 72.0);
        assert!(state.live_scale() > 1.5);
        state.apply_input(ProjectionTargetInput::ResetBaseScale);
        state.update_frame(1.0 / 72.0);
        assert!((state.live_scale() - 1.0).abs() < 0.000_001);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleSource=right-controller-primary-reset"));
    }

    #[test]
    fn pmb_reset_returns_live_scale_to_base_without_erasing_max_endpoint() {
        let mut settings = enabled_settings();
        settings.breath_bridge_mode = BreathBridgeMode::ManifoldState;
        settings.breath_inhale_seconds_min_to_max = 4.0;
        let mut state = ProjectionTargetState::new(settings);

        state.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::Inhale,
            sequence_id: Some(1),
        });
        state.update_frame(1.0);
        assert!(state.live_scale() > 1.0);

        state.apply_input(ProjectionTargetInput::ResetBaseScale);
        state.update_frame(1.0 / 72.0);
        assert!((state.live_scale() - 1.0).abs() < 0.000_001);

        state.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::Inhale,
            sequence_id: Some(2),
        });
        state.update_frame(1.0);
        assert!(state.live_scale() > 1.0);
    }

    #[test]
    fn pmb_driver_blocks_joystick_until_secondary_toggle_selects_joystick() {
        let mut settings = enabled_settings();
        settings.breath_bridge_mode = BreathBridgeMode::ManifoldState;
        let mut state = ProjectionTargetState::new(settings);
        state.apply_input(ProjectionTargetInput::JoystickScaleDelta {
            right_thumbstick_y: 1.0,
            dt_seconds: 1.0,
        });
        state.update_frame(1.0 / 72.0);
        assert!((state.live_scale() - 1.0).abs() < 0.000_001);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=pmb"));

        state.apply_input(ProjectionTargetInput::ToggleScaleDriver);
        state.apply_input(ProjectionTargetInput::JoystickScaleDelta {
            right_thumbstick_y: 1.0,
            dt_seconds: 1.0,
        });
        state.update_frame(1.0 / 72.0);
        assert!(state.live_scale() > 1.0);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleSource=right-controller-thumbstick"));
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=joystick"));
    }

    #[test]
    fn secondary_toggle_returns_to_pmb_only_after_breath_samples_exist() {
        let mut settings = enabled_settings();
        settings.breath_bridge_mode = BreathBridgeMode::ManifoldState;
        settings.breath_inhale_seconds_min_to_max = 4.0;
        let mut state = ProjectionTargetState::new(settings);

        state.apply_input(ProjectionTargetInput::ToggleScaleDriver);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=joystick"));
        state.apply_input(ProjectionTargetInput::ToggleScaleDriver);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=joystick"));
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriverSwitch=right-controller-secondary-pmb-unavailable-no-samples"));

        state.apply_input(ProjectionTargetInput::JoystickScaleDelta {
            right_thumbstick_y: 1.0,
            dt_seconds: 1.0,
        });
        state.update_frame(1.0 / 72.0);
        let joystick_scale = state.live_scale();
        assert!(joystick_scale > 1.0);
        state.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::Exhale,
            sequence_id: Some(1),
        });
        state.apply_input(ProjectionTargetInput::ToggleScaleDriver);
        state.update_frame(1.0);
        assert!(state.live_scale() < joystick_scale);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=pmb"));
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleSource=hostess-manifold-breath-state-ramp"));
    }

    #[test]
    fn secondary_button_repeated_presses_toggle_between_pmb_and_joystick() {
        let mut settings = enabled_settings();
        settings.breath_bridge_mode = BreathBridgeMode::ManifoldState;
        let mut state = ProjectionTargetState::new(settings);

        assert!(state.breath_haptics_enabled());

        state.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::Inhale,
            sequence_id: Some(1),
        });
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=pmb"));

        state.apply_input(ProjectionTargetInput::ToggleScaleDriver);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=joystick"));
        assert!(!state.breath_haptics_enabled());
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriverSwitch=right-controller-secondary-to-joystick"));

        state.apply_input(ProjectionTargetInput::ToggleScaleDriver);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=pmb"));
        assert!(state.breath_haptics_enabled());
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriverSwitch=right-controller-secondary-to-pmb"));

        state.apply_input(ProjectionTargetInput::ToggleScaleDriver);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriver=joystick"));
        assert!(!state.breath_haptics_enabled());
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleDriverSwitch=right-controller-secondary-to-joystick"));
    }

    #[test]
    fn target_rect_scales_around_center_and_clamps() {
        let mut settings = enabled_settings();
        settings.tuned_max_scale = 2.0;
        let mut state = ProjectionTargetState::new(settings);
        state.apply_input(ProjectionTargetInput::JoystickScaleDelta {
            right_thumbstick_y: 1.0,
            dt_seconds: 10.0,
        });
        state.update_frame(0.1);
        let rect = state.effective_rect(TargetRect {
            x: 0.25,
            y: 0.25,
            width: 0.5,
            height: 0.5,
        });
        assert!(rect.x >= 0.0);
        assert!(rect.y >= 0.0);
        assert!(rect.x + rect.width <= 1.0);
        assert!(rect.y + rect.height <= 1.0);
        assert!(rect.width > 0.5);
        assert!(rect.height > 0.5);
    }

    #[test]
    fn breath_state_ramp_uses_elapsed_frame_time_not_sample_cadence() {
        let mut settings = enabled_settings();
        settings.breath_bridge_mode = BreathBridgeMode::ManifoldState;
        settings.breath_inhale_seconds_min_to_max = 4.0;
        let mut high_frequency = ProjectionTargetState::new(settings.clone());
        let mut low_frequency = ProjectionTargetState::new(settings);
        high_frequency.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::Inhale,
            sequence_id: Some(1),
        });
        low_frequency.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::Inhale,
            sequence_id: Some(1),
        });
        for _ in 0..240 {
            high_frequency.update_frame(1.0 / 240.0);
        }
        for _ in 0..60 {
            low_frequency.update_frame(1.0 / 60.0);
        }
        assert!((high_frequency.live_scale() - low_frequency.live_scale()).abs() < 0.001);
    }

    #[test]
    fn pause_and_bad_tracking_hold_live_scale() {
        let mut settings = enabled_settings();
        settings.breath_bridge_mode = BreathBridgeMode::ManifoldState;
        let mut state = ProjectionTargetState::new(settings);
        state.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::Inhale,
            sequence_id: Some(1),
        });
        state.update_frame(1.0);
        let scaled = state.live_scale();
        state.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::Pause,
            sequence_id: Some(2),
        });
        state.update_frame(10.0);
        assert!((state.live_scale() - scaled).abs() < 0.000_001);
        state.apply_input(ProjectionTargetInput::BreathState {
            state: ProjectionTargetBreathState::BadTracking,
            sequence_id: Some(3),
        });
        state.update_frame(10.0);
        assert!((state.live_scale() - scaled).abs() < 0.000_001);
    }

    #[test]
    fn breath_value_maps_processed_value_to_base_and_tuned_max() {
        let mut settings = enabled_settings();
        settings.breath_bridge_mode = BreathBridgeMode::ManifoldValue;
        let mut state = ProjectionTargetState::new(settings);
        state.apply_input(ProjectionTargetInput::BreathValue {
            value01: 0.0,
            sequence_id: Some(10),
        });
        assert!((state.live_scale() - 1.0).abs() < 0.000_001);
        state.apply_input(ProjectionTargetInput::BreathValue {
            value01: 1.0,
            sequence_id: Some(11),
        });
        assert!((state.live_scale() - 1.5).abs() < 0.000_001);
        assert!(state
            .marker_fields()
            .contains("projectionTargetScaleSource=hostess-manifold-breath-state-value"));
    }
}
