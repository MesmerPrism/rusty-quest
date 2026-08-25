//! Deterministic accepted-frame calibration for source-neutral motion vectors.
//!
//! Analysis admission is bounded to a configured cadence of at most 10 Hz.
//! Once a model is ready, every valid input still traverses the live
//! projection and filtering path, including inputs between analysis ticks.

use crate::{BreathGeneration, BreathTimestampMicros};

/// Structured observation schema emitted by the calibration core.
pub const BREATH_CALIBRATION_OBSERVATION_SCHEMA_ID: &str =
    "rusty.quest.breath_calibration.observation.v1";
/// Fastest permitted analysis cadence: one admission every 100 milliseconds.
pub const MIN_ANALYSIS_INTERVAL_MICROS: u64 = 100_000;
/// Smallest useful accepted-frame target.
pub const MIN_ACCEPTED_FRAME_TARGET: usize = 8;
/// Largest useful accepted-frame target.
pub const MAX_ACCEPTED_FRAME_TARGET: usize = 512;
/// Longest permitted virtual-time watchdog.
pub const MAX_CALIBRATION_WATCHDOG_MICROS: u64 = 60_000_000;
/// Fixed live median-filter capacity.
pub const LIVE_MEDIAN_CAPACITY: usize = 5;

const EIGEN_EPSILON: f64 = 1.0e-12;
const MAX_FINITE_PARAMETER: f64 = 1.0e9;

/// Coordinate subset used for calibration and live projection.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum CalibrationProjectionSpace {
    /// Use all three vector coordinates.
    Full3d,
    /// Use only the first and third coordinates.
    #[default]
    Xz,
}

impl CalibrationProjectionSpace {
    /// Stable neutral token for telemetry and fixtures.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Full3d => "full-3d",
            Self::Xz => "xz",
        }
    }
}

/// Caller parameters validated into a calibration configuration.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CalibrationParameters {
    /// Coordinate subset used by the model.
    pub projection_space: CalibrationProjectionSpace,
    /// Number of accepted analysis frames needed before fitting.
    pub accepted_frame_target: usize,
    /// Minimum time between analysis admissions.
    pub analysis_interval_micros: u64,
    /// Maximum virtual time allowed to reach the frame target.
    pub watchdog_micros: u64,
    /// Maximum admitted source-frame age.
    pub stale_after_micros: u64,
    /// Minimum projected vector norm admitted as useful signal.
    pub useful_signal_min_norm: f64,
    /// Minimum cumulative projected motion between accepted analysis frames.
    pub motion_deadband: f64,
    /// Maximum admitted component magnitude.
    pub maximum_component_abs: f64,
    /// Maximum admitted projected step between useful frames.
    pub maximum_step: f64,
    /// Minimum robust projection span required for a ready model.
    pub minimum_span: f64,
    /// Minimum relative separation between the first two PCA eigenvalues.
    pub minimum_axis_dominance: f64,
    /// Exponential live-filter weight in `(0, 1]`.
    pub live_ema_alpha: f64,
    /// Maximum bound expansion per admitted analysis tick.
    pub adaptive_expand_step: f64,
    /// Maximum contraction toward initial robust bounds per analysis tick.
    pub adaptive_contract_step: f64,
    /// Maximum expansion from either initial robust bound.
    pub adaptive_maximum_expansion: f64,
    /// Reverse the deterministic principal-axis direction.
    pub inverted: bool,
}

impl Default for CalibrationParameters {
    fn default() -> Self {
        Self {
            projection_space: CalibrationProjectionSpace::Xz,
            accepted_frame_target: 120,
            analysis_interval_micros: MIN_ANALYSIS_INTERVAL_MICROS,
            watchdog_micros: 30_000_000,
            stale_after_micros: 250_000,
            useful_signal_min_norm: 1.0e-4,
            motion_deadband: 8.0e-4,
            maximum_component_abs: 32.0,
            maximum_step: 4.0,
            minimum_span: 0.02,
            minimum_axis_dominance: 0.05,
            live_ema_alpha: 0.45,
            adaptive_expand_step: 0.02,
            adaptive_contract_step: 0.002,
            adaptive_maximum_expansion: 0.5,
            inverted: false,
        }
    }
}

/// Validated deterministic calibration configuration.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CalibrationConfiguration(CalibrationParameters);

impl CalibrationConfiguration {
    /// Validate calibration parameters.
    ///
    /// # Errors
    ///
    /// Returns a precise [`CalibrationConfigurationError`] for the first
    /// invalid public bound.
    pub fn new(parameters: CalibrationParameters) -> Result<Self, CalibrationConfigurationError> {
        if !(MIN_ACCEPTED_FRAME_TARGET..=MAX_ACCEPTED_FRAME_TARGET)
            .contains(&parameters.accepted_frame_target)
        {
            return Err(CalibrationConfigurationError::AcceptedFrameTarget);
        }
        if parameters.analysis_interval_micros < MIN_ANALYSIS_INTERVAL_MICROS {
            return Err(CalibrationConfigurationError::AnalysisInterval);
        }
        let minimum_collection_micros = parameters
            .analysis_interval_micros
            .checked_mul(parameters.accepted_frame_target.saturating_sub(1) as u64)
            .ok_or(CalibrationConfigurationError::Watchdog)?;
        if parameters.watchdog_micros < minimum_collection_micros
            || parameters.watchdog_micros > MAX_CALIBRATION_WATCHDOG_MICROS
        {
            return Err(CalibrationConfigurationError::Watchdog);
        }
        if parameters.stale_after_micros == 0
            || parameters.stale_after_micros > parameters.watchdog_micros
        {
            return Err(CalibrationConfigurationError::StaleInterval);
        }
        if !is_finite_nonnegative_bounded(parameters.useful_signal_min_norm) {
            return Err(CalibrationConfigurationError::UsefulSignalThreshold);
        }
        if !is_finite_positive_bounded(parameters.motion_deadband) {
            return Err(CalibrationConfigurationError::MotionDeadband);
        }
        if !is_finite_positive_bounded(parameters.maximum_component_abs) {
            return Err(CalibrationConfigurationError::MaximumComponent);
        }
        if !is_finite_positive_bounded(parameters.maximum_step)
            || parameters.maximum_step <= parameters.motion_deadband
        {
            return Err(CalibrationConfigurationError::MaximumStep);
        }
        if !is_finite_positive_bounded(parameters.minimum_span) {
            return Err(CalibrationConfigurationError::MinimumSpan);
        }
        if !parameters.minimum_axis_dominance.is_finite()
            || !(0.0..=1.0).contains(&parameters.minimum_axis_dominance)
            || parameters.minimum_axis_dominance == 0.0
        {
            return Err(CalibrationConfigurationError::AxisDominance);
        }
        if !parameters.live_ema_alpha.is_finite()
            || !(0.0..=1.0).contains(&parameters.live_ema_alpha)
            || parameters.live_ema_alpha == 0.0
        {
            return Err(CalibrationConfigurationError::LiveFilterAlpha);
        }
        if !is_finite_positive_bounded(parameters.adaptive_expand_step) {
            return Err(CalibrationConfigurationError::AdaptiveExpandStep);
        }
        if !is_finite_nonnegative_bounded(parameters.adaptive_contract_step) {
            return Err(CalibrationConfigurationError::AdaptiveContractStep);
        }
        if !is_finite_positive_bounded(parameters.adaptive_maximum_expansion)
            || parameters.adaptive_maximum_expansion < parameters.adaptive_expand_step
        {
            return Err(CalibrationConfigurationError::AdaptiveMaximumExpansion);
        }
        Ok(Self(parameters))
    }

    /// Return the validated parameters.
    #[must_use]
    pub const fn parameters(self) -> CalibrationParameters {
        self.0
    }
}

fn is_finite_positive_bounded(value: f64) -> bool {
    value.is_finite() && value > 0.0 && value <= MAX_FINITE_PARAMETER
}

fn is_finite_nonnegative_bounded(value: f64) -> bool {
    value.is_finite() && (0.0..=MAX_FINITE_PARAMETER).contains(&value)
}

/// Typed calibration-configuration failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CalibrationConfigurationError {
    /// Accepted-frame target is outside its public bound.
    AcceptedFrameTarget,
    /// Analysis cadence would exceed 10 Hz.
    AnalysisInterval,
    /// Watchdog is impossible for the requested target or exceeds its bound.
    Watchdog,
    /// Stale interval is zero or exceeds the watchdog.
    StaleInterval,
    /// Useful-signal threshold is invalid.
    UsefulSignalThreshold,
    /// Motion deadband is invalid.
    MotionDeadband,
    /// Maximum component magnitude is invalid.
    MaximumComponent,
    /// Maximum step is invalid or does not exceed the deadband.
    MaximumStep,
    /// Minimum robust span is invalid.
    MinimumSpan,
    /// Axis-dominance threshold is outside `(0, 1]`.
    AxisDominance,
    /// Live filter weight is outside `(0, 1]`.
    LiveFilterAlpha,
    /// Adaptive expansion step is invalid.
    AdaptiveExpandStep,
    /// Adaptive contraction step is invalid.
    AdaptiveContractStep,
    /// Adaptive expansion cap is invalid or smaller than one expansion step.
    AdaptiveMaximumExpansion,
}

/// Explicit calibration lifecycle.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum CalibrationLifecycle {
    /// No configuration or generation exists.
    #[default]
    Disabled,
    /// A validated configuration exists.
    Configured,
    /// Accepted analysis frames are being collected.
    Collecting,
    /// A fitted model is available and live output is active.
    Ready,
    /// The exact generation was cancelled.
    Cancelled,
    /// The active generation ended with a typed calibration failure.
    Failed,
}

impl CalibrationLifecycle {
    /// Stable neutral token for telemetry and fixtures.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Configured => "configured",
            Self::Collecting => "collecting",
            Self::Ready => "ready",
            Self::Cancelled => "cancelled",
            Self::Failed => "failed",
        }
    }
}

/// Source-neutral timed motion frame.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CalibrationMotionFrame {
    /// Strictly increasing caller-owned frame sequence.
    pub sequence_id: u64,
    /// Source timestamp in the injected monotonic time domain.
    pub sampled_at: BreathTimestampMicros,
    /// Three finite coordinates in caller-defined consistent units.
    pub vector: [f64; 3],
}

/// Explicit input to the calibration owner.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CalibrationInput {
    /// No frame was available at this action time.
    Missing,
    /// One source-neutral timed motion frame.
    Frame(CalibrationMotionFrame),
}

/// Stable status for the latest calibration action.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum CalibrationStatus {
    /// Default construction is inert.
    #[default]
    Disabled,
    /// Reset completed.
    Reset,
    /// Configuration completed.
    Configured,
    /// Collection started for an exact generation.
    Started,
    /// A valid frame arrived before the next analysis tick.
    AnalysisDeferred,
    /// A frame was accepted into calibration analysis.
    AnalysisFrameAccepted,
    /// A useful frame did not cross the cumulative motion deadband.
    MotionBelowDeadband,
    /// One live output was updated; analysis may or may not also have run.
    LiveUpdated,
    /// A ready model was fitted and its first live output was produced.
    Ready,
    /// Input was missing and any live output was cleared.
    MissingInput,
    /// Input was structurally rejected and any live output was cleared.
    InputRejected,
    /// Cancellation completed.
    Cancelled,
    /// Calibration terminated with a typed failure.
    Failed,
    /// The requested lifecycle action was rejected.
    ActionRejected,
}

impl CalibrationStatus {
    /// Stable neutral token for telemetry and fixtures.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Reset => "reset",
            Self::Configured => "configured",
            Self::Started => "started",
            Self::AnalysisDeferred => "analysis-deferred",
            Self::AnalysisFrameAccepted => "analysis-frame-accepted",
            Self::MotionBelowDeadband => "motion-below-deadband",
            Self::LiveUpdated => "live-updated",
            Self::Ready => "ready",
            Self::MissingInput => "missing-input",
            Self::InputRejected => "input-rejected",
            Self::Cancelled => "cancelled",
            Self::Failed => "failed",
            Self::ActionRejected => "action-rejected",
        }
    }
}

/// Lifecycle action names used in typed rejection evidence.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CalibrationActionKind {
    /// Clear model and configuration.
    Reset,
    /// Install a validated configuration.
    Configure,
    /// Begin collection for an exact generation.
    Start,
    /// Cancel an exact generation.
    Cancel,
    /// Submit an explicit input.
    Observe,
}

/// Malformed or inadmissible frame reason.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CalibrationInputError {
    /// A coordinate was NaN or infinite.
    NonFiniteComponent {
        /// Invalid coordinate index.
        index: usize,
    },
    /// A coordinate exceeded the configured absolute bound.
    ComponentOutOfBounds {
        /// Rejected coordinate index.
        index: usize,
        /// Rejected finite coordinate.
        value: f64,
        /// Configured absolute bound.
        maximum_abs: f64,
    },
    /// Source time was later than action time.
    FutureTimestamp,
    /// Source time did not increase strictly.
    OutOfOrderTimestamp {
        /// Rejected source timestamp.
        submitted: BreathTimestampMicros,
        /// Last structurally admitted source timestamp.
        previous: BreathTimestampMicros,
    },
    /// Sequence did not increase strictly.
    OutOfOrderSequence {
        /// Rejected sequence.
        submitted: u64,
        /// Last structurally admitted sequence.
        previous: u64,
    },
    /// Source frame was older than the configured age bound.
    Stale {
        /// Observed frame age.
        age_micros: u64,
        /// Configured maximum age.
        maximum_age_micros: u64,
    },
    /// Projected vector norm did not meet useful-signal admission.
    NotUsefulSignal {
        /// Observed projected norm.
        projected_norm: f64,
        /// Configured minimum norm.
        minimum_norm: f64,
    },
    /// Projected step exceeded its configured bound.
    StepOutOfBounds {
        /// Observed projected step.
        step: f64,
        /// Configured maximum step.
        maximum_step: f64,
    },
}

/// Typed rejected-action evidence.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CalibrationRejection {
    /// Configuration parameters were invalid.
    InvalidConfiguration(CalibrationConfigurationError),
    /// Action was not valid in the current lifecycle.
    InvalidLifecycle {
        /// Rejected action.
        action: CalibrationActionKind,
        /// Lifecycle before rejection.
        lifecycle: CalibrationLifecycle,
    },
    /// Submitted generation was not the active generation.
    GenerationMismatch {
        /// Submitted generation.
        submitted: BreathGeneration,
        /// Active generation, if any.
        current: Option<BreathGeneration>,
    },
    /// Start attempted to reuse or move behind a prior generation.
    GenerationNotFresh {
        /// Submitted generation.
        submitted: BreathGeneration,
        /// Largest generation previously started.
        previous: BreathGeneration,
    },
    /// Injected action time moved backwards.
    TimeRegression {
        /// Rejected timestamp.
        submitted: BreathTimestampMicros,
        /// Last accepted action timestamp.
        previous: BreathTimestampMicros,
    },
    /// Input frame was malformed or inadmissible.
    Input(CalibrationInputError),
}

/// Watchdog classification for an incomplete accepted-frame collection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CalibrationWatchdogCause {
    /// No useful vector was admitted.
    NoUsefulSignal,
    /// Useful vectors arrived but remained below the cumulative deadband.
    InsufficientMotion,
    /// Some motion frames were accepted, but the bounded target was not met.
    InsufficientAcceptedFrames,
}

/// Terminal structured calibration failure.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CalibrationFailure {
    /// Virtual-time watchdog expired before collection completed.
    Watchdog {
        /// Specific incomplete-collection classification.
        cause: CalibrationWatchdogCause,
        /// Accepted analysis frames at expiry.
        accepted_frames: usize,
        /// Required accepted-frame target.
        target_frames: usize,
        /// Injected elapsed time at expiry.
        elapsed_micros: u64,
    },
    /// PCA did not identify one sufficiently dominant direction.
    DegenerateAxis {
        /// Largest covariance eigenvalue.
        principal_variance: f64,
        /// Second-largest covariance eigenvalue.
        secondary_variance: f64,
        /// Relative eigenvalue separation in `[0, 1]`.
        dominance01: f64,
        /// Configured minimum dominance.
        required_dominance01: f64,
    },
    /// Robust fifth-to-ninety-fifth-percentile span was too small.
    InsufficientSpan {
        /// Observed robust span.
        observed_span: f64,
        /// Required minimum span.
        required_span: f64,
    },
}

/// Fitted deterministic calibration model.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CalibrationModel {
    /// Coordinate subset used by this model.
    pub projection_space: CalibrationProjectionSpace,
    /// Arithmetic center of accepted frames.
    pub center: [f64; 3],
    /// Deterministically anchored unit principal axis.
    pub axis: [f64; 3],
    /// Initial robust fifth-percentile projection.
    pub initial_lower: f64,
    /// Initial robust ninety-fifth-percentile projection.
    pub initial_upper: f64,
    /// Current bounded adaptive lower limit.
    pub lower: f64,
    /// Current bounded adaptive upper limit.
    pub upper: f64,
    /// Initial robust projection span.
    pub initial_span: f64,
    /// Relative principal-axis dominance in `[0, 1]`.
    pub axis_dominance01: f64,
    /// Whether explicit direction inversion was applied.
    pub inverted: bool,
}

/// Latest source-neutral live result.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CalibrationLiveOutput {
    /// Source frame sequence.
    pub sequence_id: u64,
    /// Source frame timestamp.
    pub sampled_at: BreathTimestampMicros,
    /// Action timestamp.
    pub observed_at: BreathTimestampMicros,
    /// Projection before live filtering.
    pub raw_projection: f64,
    /// Median-of-five plus EMA projection.
    pub filtered_projection: f64,
    /// Bounded normalized live output.
    pub volume01: f64,
    /// True only when the same frame also entered bounded model maintenance.
    pub analysis_admitted: bool,
}

/// Saturating neutral telemetry for calibration and live response.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct CalibrationTelemetry {
    /// Explicit input actions received.
    pub received_input_count: u64,
    /// Structurally valid frames admitted to useful-signal evaluation.
    pub structurally_admitted_frame_count: u64,
    /// Frames admitted as useful signal.
    pub useful_frame_count: u64,
    /// Frames rejected as malformed, stale, out of order, or inadmissible.
    pub rejected_frame_count: u64,
    /// Useful frames considered at bounded analysis ticks.
    pub analysis_tick_count: u64,
    /// Motion frames accepted for model fitting.
    pub accepted_analysis_frame_count: u64,
    /// Analysis ticks rejected by the cumulative motion deadband.
    pub motion_deadband_rejection_count: u64,
    /// Valid frames deferred because the next analysis tick was not due.
    pub analysis_deferred_count: u64,
    /// Live results produced after fitting.
    pub live_update_count: u64,
    /// Adaptive-bound maintenance updates.
    pub adaptive_update_count: u64,
    /// Missing inputs.
    pub missing_input_count: u64,
    /// Stale frames.
    pub stale_input_count: u64,
    /// Malformed finite-shape failures.
    pub malformed_input_count: u64,
    /// Out-of-order timestamp or sequence failures.
    pub out_of_order_input_count: u64,
    /// Useful-signal gate rejections.
    pub not_useful_input_count: u64,
    /// Excessive-step rejections.
    pub excessive_step_count: u64,
    /// Old or unrelated generation rejections.
    pub generation_rejection_count: u64,
    /// Action-time regressions.
    pub time_regression_count: u64,
    /// Invalid lifecycle actions.
    pub lifecycle_rejection_count: u64,
}

/// Complete low-rate snapshot of calibration authority.
#[derive(Clone, Debug, PartialEq)]
pub struct CalibrationObservation {
    /// Stable schema identifier.
    pub schema_id: &'static str,
    /// Current lifecycle.
    pub lifecycle: CalibrationLifecycle,
    /// Exact active or failed generation.
    pub generation: Option<BreathGeneration>,
    /// Latest action status.
    pub status: CalibrationStatus,
    /// Typed non-terminal rejection.
    pub rejection: Option<CalibrationRejection>,
    /// Typed terminal failure.
    pub failure: Option<CalibrationFailure>,
    /// Accepted analysis frames.
    pub accepted_frames: usize,
    /// Configured accepted-frame target, if configured.
    pub target_frames: Option<usize>,
    /// Bounded collection progress in `[0, 1]`.
    pub progress01: f64,
    /// Injected watchdog age for an active generation.
    pub watchdog_age_micros: Option<u64>,
    /// Fitted model, available only when ready or failed after fitting.
    pub model: Option<CalibrationModel>,
    /// Latest valid live result, cleared on fail-closed input states.
    pub live: Option<CalibrationLiveOutput>,
    /// Saturating neutral telemetry.
    pub telemetry: CalibrationTelemetry,
}

/// Pure accepted-frame calibration owner.
#[derive(Clone, Debug)]
pub struct AcceptedFrameCalibration {
    lifecycle: CalibrationLifecycle,
    configuration: Option<CalibrationConfiguration>,
    generation: Option<BreathGeneration>,
    highest_started_generation: Option<BreathGeneration>,
    started_at: Option<BreathTimestampMicros>,
    last_action_at: Option<BreathTimestampMicros>,
    last_input_at: Option<BreathTimestampMicros>,
    last_sequence_id: Option<u64>,
    last_useful_vector: Option<[f64; 3]>,
    last_analysis_at: Option<BreathTimestampMicros>,
    last_analysis_vector: Option<[f64; 3]>,
    accepted_frames: Vec<[f64; 3]>,
    model: Option<CalibrationModel>,
    median_values: [f64; LIVE_MEDIAN_CAPACITY],
    median_len: usize,
    median_next: usize,
    filtered_projection: Option<f64>,
    live: Option<CalibrationLiveOutput>,
    status: CalibrationStatus,
    rejection: Option<CalibrationRejection>,
    failure: Option<CalibrationFailure>,
    telemetry: CalibrationTelemetry,
}

impl Default for AcceptedFrameCalibration {
    fn default() -> Self {
        Self {
            lifecycle: CalibrationLifecycle::Disabled,
            configuration: None,
            generation: None,
            highest_started_generation: None,
            started_at: None,
            last_action_at: None,
            last_input_at: None,
            last_sequence_id: None,
            last_useful_vector: None,
            last_analysis_at: None,
            last_analysis_vector: None,
            accepted_frames: Vec::new(),
            model: None,
            median_values: [0.0; LIVE_MEDIAN_CAPACITY],
            median_len: 0,
            median_next: 0,
            filtered_projection: None,
            live: None,
            status: CalibrationStatus::Disabled,
            rejection: None,
            failure: None,
            telemetry: CalibrationTelemetry::default(),
        }
    }
}

impl AcceptedFrameCalibration {
    /// Create an inert calibration owner.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Return the current low-rate snapshot without advancing virtual time.
    #[must_use]
    pub fn snapshot(&self) -> CalibrationObservation {
        let target_frames = self
            .configuration
            .map(|configuration| configuration.parameters().accepted_frame_target);
        let progress01 = target_frames.map_or(0.0, |target| {
            let accepted = u32::try_from(self.accepted_frames.len()).unwrap_or(u32::MAX);
            let target = u32::try_from(target).unwrap_or(u32::MAX);
            (f64::from(accepted) / f64::from(target)).clamp(0.0, 1.0)
        });
        let watchdog_age_micros =
            self.started_at
                .zip(self.last_action_at)
                .map(|(started_at, last_action_at)| {
                    last_action_at.get().saturating_sub(started_at.get())
                });
        CalibrationObservation {
            schema_id: BREATH_CALIBRATION_OBSERVATION_SCHEMA_ID,
            lifecycle: self.lifecycle,
            generation: self.generation,
            status: self.status,
            rejection: self.rejection,
            failure: self.failure,
            accepted_frames: self.accepted_frames.len(),
            target_frames,
            progress01,
            watchdog_age_micros,
            model: self.model,
            live: self.live,
            telemetry: self.telemetry,
        }
    }

    /// Clear all configuration, model, frame, filter, and live state.
    ///
    /// The generation high-water mark remains so reset cannot make an old
    /// generation fresh again.
    pub fn reset(&mut self, at: BreathTimestampMicros) -> CalibrationObservation {
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        self.lifecycle = CalibrationLifecycle::Disabled;
        self.configuration = None;
        self.generation = None;
        self.clear_generation_state();
        self.last_action_at = Some(at);
        self.status = CalibrationStatus::Reset;
        self.rejection = None;
        self.failure = None;
        self.telemetry = CalibrationTelemetry::default();
        self.snapshot()
    }

    /// Install a validated configuration while no generation is active.
    pub fn configure(
        &mut self,
        at: BreathTimestampMicros,
        configuration: Result<CalibrationConfiguration, CalibrationConfigurationError>,
    ) -> CalibrationObservation {
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        if matches!(
            self.lifecycle,
            CalibrationLifecycle::Collecting | CalibrationLifecycle::Ready
        ) {
            return self.reject_lifecycle(CalibrationActionKind::Configure);
        }
        let configuration = match configuration {
            Ok(configuration) => configuration,
            Err(error) => {
                return self.reject(CalibrationRejection::InvalidConfiguration(error));
            }
        };
        self.configuration = Some(configuration);
        self.lifecycle = CalibrationLifecycle::Configured;
        self.generation = None;
        self.clear_generation_state();
        self.last_action_at = Some(at);
        self.status = CalibrationStatus::Configured;
        self.rejection = None;
        self.failure = None;
        self.snapshot()
    }

    /// Start deterministic accepted-frame collection for one fresh generation.
    pub fn start(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
    ) -> CalibrationObservation {
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        if self.configuration.is_none()
            || !matches!(
                self.lifecycle,
                CalibrationLifecycle::Configured
                    | CalibrationLifecycle::Cancelled
                    | CalibrationLifecycle::Failed
            )
        {
            return self.reject_lifecycle(CalibrationActionKind::Start);
        }
        if let Some(previous) = self.highest_started_generation {
            if generation <= previous {
                return self.reject(CalibrationRejection::GenerationNotFresh {
                    submitted: generation,
                    previous,
                });
            }
        }
        self.clear_generation_state();
        self.lifecycle = CalibrationLifecycle::Collecting;
        self.generation = Some(generation);
        self.highest_started_generation = Some(generation);
        self.started_at = Some(at);
        self.last_action_at = Some(at);
        self.status = CalibrationStatus::Started;
        self.rejection = None;
        self.failure = None;
        self.snapshot()
    }

    /// Cancel the exact collecting, ready, or failed generation.
    pub fn cancel(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
    ) -> CalibrationObservation {
        if !matches!(
            self.lifecycle,
            CalibrationLifecycle::Collecting
                | CalibrationLifecycle::Ready
                | CalibrationLifecycle::Failed
        ) {
            return self.reject_lifecycle(CalibrationActionKind::Cancel);
        }
        if self.generation != Some(generation) {
            return self.reject_generation(generation);
        }
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        self.lifecycle = CalibrationLifecycle::Cancelled;
        self.generation = None;
        self.clear_generation_state();
        self.last_action_at = Some(at);
        self.status = CalibrationStatus::Cancelled;
        self.rejection = None;
        self.failure = None;
        self.snapshot()
    }

    /// Observe one explicit input for the exact active generation.
    pub fn observe(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
        input: CalibrationInput,
    ) -> CalibrationObservation {
        if !matches!(
            self.lifecycle,
            CalibrationLifecycle::Collecting | CalibrationLifecycle::Ready
        ) {
            return self.reject_lifecycle(CalibrationActionKind::Observe);
        }
        if self.generation != Some(generation) {
            return self.reject_generation(generation);
        }
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        self.last_action_at = Some(at);
        increment(&mut self.telemetry.received_input_count);
        if self.lifecycle == CalibrationLifecycle::Collecting && self.watchdog_expired(at) {
            return self.fail_watchdog(at);
        }
        match input {
            CalibrationInput::Missing => {
                self.live = None;
                self.status = CalibrationStatus::MissingInput;
                self.rejection = None;
                increment(&mut self.telemetry.missing_input_count);
                self.snapshot()
            }
            CalibrationInput::Frame(frame) => self.observe_frame(at, frame),
        }
    }

    fn observe_frame(
        &mut self,
        at: BreathTimestampMicros,
        frame: CalibrationMotionFrame,
    ) -> CalibrationObservation {
        let parameters = self
            .configuration
            .expect("active lifecycle retains configuration")
            .parameters();
        if frame.sampled_at > at {
            return self.reject_input(CalibrationInputError::FutureTimestamp);
        }
        if let Some(previous) = self.last_input_at {
            if frame.sampled_at <= previous {
                increment(&mut self.telemetry.out_of_order_input_count);
                return self.reject_input(CalibrationInputError::OutOfOrderTimestamp {
                    submitted: frame.sampled_at,
                    previous,
                });
            }
        }
        if let Some(previous) = self.last_sequence_id {
            if frame.sequence_id <= previous {
                increment(&mut self.telemetry.out_of_order_input_count);
                return self.reject_input(CalibrationInputError::OutOfOrderSequence {
                    submitted: frame.sequence_id,
                    previous,
                });
            }
        }
        for (index, component) in frame.vector.into_iter().enumerate() {
            if !component.is_finite() {
                increment(&mut self.telemetry.malformed_input_count);
                return self.reject_input(CalibrationInputError::NonFiniteComponent { index });
            }
            if component.abs() > parameters.maximum_component_abs {
                increment(&mut self.telemetry.malformed_input_count);
                return self.reject_input(CalibrationInputError::ComponentOutOfBounds {
                    index,
                    value: component,
                    maximum_abs: parameters.maximum_component_abs,
                });
            }
        }
        let age_micros = at.get() - frame.sampled_at.get();
        self.last_input_at = Some(frame.sampled_at);
        self.last_sequence_id = Some(frame.sequence_id);
        if age_micros > parameters.stale_after_micros {
            increment(&mut self.telemetry.stale_input_count);
            return self.reject_input(CalibrationInputError::Stale {
                age_micros,
                maximum_age_micros: parameters.stale_after_micros,
            });
        }
        increment(&mut self.telemetry.structurally_admitted_frame_count);
        let projected_norm = projected_norm(frame.vector, parameters.projection_space);
        if projected_norm < parameters.useful_signal_min_norm {
            increment(&mut self.telemetry.not_useful_input_count);
            return self.reject_input(CalibrationInputError::NotUsefulSignal {
                projected_norm,
                minimum_norm: parameters.useful_signal_min_norm,
            });
        }
        if let Some(previous) = self.last_useful_vector {
            let step = projected_distance(previous, frame.vector, parameters.projection_space);
            if step > parameters.maximum_step {
                increment(&mut self.telemetry.excessive_step_count);
                return self.reject_input(CalibrationInputError::StepOutOfBounds {
                    step,
                    maximum_step: parameters.maximum_step,
                });
            }
        }
        self.last_useful_vector = Some(frame.vector);
        increment(&mut self.telemetry.useful_frame_count);

        let analysis_due = self.last_analysis_at.map_or(true, |last_analysis_at| {
            at.get().saturating_sub(last_analysis_at.get()) >= parameters.analysis_interval_micros
        });
        if analysis_due {
            self.last_analysis_at = Some(at);
            increment(&mut self.telemetry.analysis_tick_count);
        }

        if self.lifecycle == CalibrationLifecycle::Collecting {
            return self.observe_collecting(at, frame, parameters, analysis_due);
        }
        self.update_live(
            at,
            frame,
            parameters,
            analysis_due,
            CalibrationStatus::LiveUpdated,
        )
    }

    fn observe_collecting(
        &mut self,
        at: BreathTimestampMicros,
        frame: CalibrationMotionFrame,
        parameters: CalibrationParameters,
        analysis_due: bool,
    ) -> CalibrationObservation {
        if !analysis_due {
            self.status = CalibrationStatus::AnalysisDeferred;
            self.rejection = None;
            self.live = None;
            increment(&mut self.telemetry.analysis_deferred_count);
            return self.snapshot();
        }
        if let Some(previous) = self.last_analysis_vector {
            let motion = projected_distance(previous, frame.vector, parameters.projection_space);
            if motion < parameters.motion_deadband {
                self.status = CalibrationStatus::MotionBelowDeadband;
                self.rejection = None;
                self.live = None;
                increment(&mut self.telemetry.motion_deadband_rejection_count);
                return self.snapshot();
            }
        }
        self.last_analysis_vector = Some(frame.vector);
        self.accepted_frames.push(frame.vector);
        increment(&mut self.telemetry.accepted_analysis_frame_count);
        if self.accepted_frames.len() < parameters.accepted_frame_target {
            self.status = CalibrationStatus::AnalysisFrameAccepted;
            self.rejection = None;
            return self.snapshot();
        }

        let model = match fit_model(&self.accepted_frames, parameters) {
            Ok(model) => model,
            Err(failure) => return self.fail(failure),
        };
        self.model = Some(model);
        self.lifecycle = CalibrationLifecycle::Ready;
        self.status = CalibrationStatus::Ready;
        self.rejection = None;
        self.failure = None;
        self.update_live(at, frame, parameters, false, CalibrationStatus::Ready)
    }

    fn update_live(
        &mut self,
        at: BreathTimestampMicros,
        frame: CalibrationMotionFrame,
        parameters: CalibrationParameters,
        analysis_admitted: bool,
        status: CalibrationStatus,
    ) -> CalibrationObservation {
        let mut model = self.model.expect("ready lifecycle retains model");
        let raw_projection = project(frame.vector, model.center, model.axis);
        self.median_values[self.median_next] = raw_projection;
        self.median_next = (self.median_next + 1) % LIVE_MEDIAN_CAPACITY;
        self.median_len = self.median_len.saturating_add(1).min(LIVE_MEDIAN_CAPACITY);
        let median_projection = median(&self.median_values[..self.median_len]);
        let filtered_projection = self
            .filtered_projection
            .map_or(median_projection, |previous| {
                previous + parameters.live_ema_alpha * (median_projection - previous)
            });
        self.filtered_projection = Some(filtered_projection);

        if analysis_admitted {
            adapt_bounds(&mut model, filtered_projection, parameters);
            increment(&mut self.telemetry.adaptive_update_count);
            self.model = Some(model);
        }
        let span = (model.upper - model.lower).max(parameters.minimum_span);
        let volume01 = ((filtered_projection - model.lower) / span).clamp(0.0, 1.0);
        self.live = Some(CalibrationLiveOutput {
            sequence_id: frame.sequence_id,
            sampled_at: frame.sampled_at,
            observed_at: at,
            raw_projection,
            filtered_projection,
            volume01,
            analysis_admitted,
        });
        self.status = status;
        self.rejection = None;
        increment(&mut self.telemetry.live_update_count);
        self.snapshot()
    }

    fn watchdog_expired(&self, at: BreathTimestampMicros) -> bool {
        let parameters = self
            .configuration
            .expect("active lifecycle retains configuration")
            .parameters();
        self.started_at.is_some_and(|started_at| {
            at.get().saturating_sub(started_at.get()) > parameters.watchdog_micros
        })
    }

    fn fail_watchdog(&mut self, at: BreathTimestampMicros) -> CalibrationObservation {
        let parameters = self
            .configuration
            .expect("collecting lifecycle retains configuration")
            .parameters();
        let elapsed_micros = at.get().saturating_sub(
            self.started_at
                .expect("collecting lifecycle retains start time")
                .get(),
        );
        let cause = if self.telemetry.useful_frame_count == 0 {
            CalibrationWatchdogCause::NoUsefulSignal
        } else if self.telemetry.motion_deadband_rejection_count > 0
            && self.accepted_frames.len() <= 1
        {
            CalibrationWatchdogCause::InsufficientMotion
        } else {
            CalibrationWatchdogCause::InsufficientAcceptedFrames
        };
        self.fail(CalibrationFailure::Watchdog {
            cause,
            accepted_frames: self.accepted_frames.len(),
            target_frames: parameters.accepted_frame_target,
            elapsed_micros,
        })
    }

    fn fail(&mut self, failure: CalibrationFailure) -> CalibrationObservation {
        self.lifecycle = CalibrationLifecycle::Failed;
        self.live = None;
        self.status = CalibrationStatus::Failed;
        self.rejection = None;
        self.failure = Some(failure);
        self.snapshot()
    }

    fn reject_time_regression(
        &mut self,
        at: BreathTimestampMicros,
    ) -> Option<CalibrationObservation> {
        let previous = self.last_action_at?;
        if at >= previous {
            return None;
        }
        increment(&mut self.telemetry.time_regression_count);
        Some(self.reject(CalibrationRejection::TimeRegression {
            submitted: at,
            previous,
        }))
    }

    fn reject_lifecycle(&mut self, action: CalibrationActionKind) -> CalibrationObservation {
        increment(&mut self.telemetry.lifecycle_rejection_count);
        self.reject(CalibrationRejection::InvalidLifecycle {
            action,
            lifecycle: self.lifecycle,
        })
    }

    fn reject_generation(&mut self, submitted: BreathGeneration) -> CalibrationObservation {
        increment(&mut self.telemetry.generation_rejection_count);
        self.reject(CalibrationRejection::GenerationMismatch {
            submitted,
            current: self.generation,
        })
    }

    fn reject_input(&mut self, error: CalibrationInputError) -> CalibrationObservation {
        self.live = None;
        self.status = CalibrationStatus::InputRejected;
        increment(&mut self.telemetry.rejected_frame_count);
        self.rejection = Some(CalibrationRejection::Input(error));
        self.snapshot()
    }

    fn reject(&mut self, rejection: CalibrationRejection) -> CalibrationObservation {
        self.status = CalibrationStatus::ActionRejected;
        self.rejection = Some(rejection);
        self.snapshot()
    }

    fn clear_generation_state(&mut self) {
        self.started_at = None;
        self.last_input_at = None;
        self.last_sequence_id = None;
        self.last_useful_vector = None;
        self.last_analysis_at = None;
        self.last_analysis_vector = None;
        self.accepted_frames.clear();
        self.model = None;
        self.median_values = [0.0; LIVE_MEDIAN_CAPACITY];
        self.median_len = 0;
        self.median_next = 0;
        self.filtered_projection = None;
        self.live = None;
    }
}

fn fit_model(
    frames: &[[f64; 3]],
    parameters: CalibrationParameters,
) -> Result<CalibrationModel, CalibrationFailure> {
    let center = mean(frames);
    let covariance = covariance(frames, center, parameters.projection_space);
    let (eigenvalues, eigenvectors) = symmetric_eigen(covariance);
    let principal_variance = eigenvalues[0].max(0.0);
    let secondary_variance = eigenvalues[1].max(0.0);
    let dominance01 = if principal_variance <= EIGEN_EPSILON {
        0.0
    } else {
        ((principal_variance - secondary_variance) / principal_variance).clamp(0.0, 1.0)
    };
    if principal_variance <= EIGEN_EPSILON || dominance01 < parameters.minimum_axis_dominance {
        return Err(CalibrationFailure::DegenerateAxis {
            principal_variance,
            secondary_variance,
            dominance01,
            required_dominance01: parameters.minimum_axis_dominance,
        });
    }
    let mut axis = eigenvectors[0];
    if parameters.projection_space == CalibrationProjectionSpace::Xz {
        axis[1] = 0.0;
    }
    normalize(&mut axis);
    anchor_direction(&mut axis, parameters.projection_space);
    if parameters.inverted {
        for component in &mut axis {
            *component = -*component;
        }
    }
    let mut projections: Vec<f64> = frames
        .iter()
        .copied()
        .map(|frame| project(frame, center, axis))
        .collect();
    projections.sort_by(f64::total_cmp);
    let lower = percentile(&projections, 5);
    let upper = percentile(&projections, 95);
    let span = upper - lower;
    if !span.is_finite() || span < parameters.minimum_span {
        return Err(CalibrationFailure::InsufficientSpan {
            observed_span: span,
            required_span: parameters.minimum_span,
        });
    }
    Ok(CalibrationModel {
        projection_space: parameters.projection_space,
        center,
        axis,
        initial_lower: lower,
        initial_upper: upper,
        lower,
        upper,
        initial_span: span,
        axis_dominance01: dominance01,
        inverted: parameters.inverted,
    })
}

fn mean(frames: &[[f64; 3]]) -> [f64; 3] {
    let mut center = [0.0; 3];
    for frame in frames {
        for (sum, component) in center.iter_mut().zip(frame) {
            *sum += component;
        }
    }
    let frame_count = u32::try_from(frames.len()).expect("accepted-frame target is bounded to u32");
    let divisor = f64::from(frame_count);
    for value in &mut center {
        *value /= divisor;
    }
    center
}

fn covariance(
    frames: &[[f64; 3]],
    center: [f64; 3],
    projection_space: CalibrationProjectionSpace,
) -> [[f64; 3]; 3] {
    let mut xx = 0.0;
    let mut xy = 0.0;
    let mut xz = 0.0;
    let mut yy = 0.0;
    let mut yz = 0.0;
    let mut zz = 0.0;
    for frame in frames {
        let mut delta = [
            frame[0] - center[0],
            frame[1] - center[1],
            frame[2] - center[2],
        ];
        if projection_space == CalibrationProjectionSpace::Xz {
            delta[1] = 0.0;
        }
        xx += delta[0] * delta[0];
        xy += delta[0] * delta[1];
        xz += delta[0] * delta[2];
        yy += delta[1] * delta[1];
        yz += delta[1] * delta[2];
        zz += delta[2] * delta[2];
    }
    let frame_count = u32::try_from(frames.len()).expect("accepted-frame target is bounded to u32");
    let divisor = f64::from(frame_count);
    [
        [xx / divisor, xy / divisor, xz / divisor],
        [xy / divisor, yy / divisor, yz / divisor],
        [xz / divisor, yz / divisor, zz / divisor],
    ]
}

fn symmetric_eigen(mut matrix: [[f64; 3]; 3]) -> ([f64; 3], [[f64; 3]; 3]) {
    let mut vectors = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
    for _ in 0..24 {
        let (p, q, magnitude) = largest_off_diagonal(matrix);
        if magnitude <= EIGEN_EPSILON {
            break;
        }
        let angle = 0.5 * (2.0 * matrix[p][q]).atan2(matrix[q][q] - matrix[p][p]);
        let cosine = angle.cos();
        let sine = angle.sin();
        for index in [0, 1, 2] {
            if index != p && index != q {
                let first_off_diagonal = matrix[index][p];
                let second_off_diagonal = matrix[index][q];
                matrix[index][p] = cosine * first_off_diagonal - sine * second_off_diagonal;
                matrix[p][index] = matrix[index][p];
                matrix[index][q] = sine * first_off_diagonal + cosine * second_off_diagonal;
                matrix[q][index] = matrix[index][q];
            }
        }
        let diagonal_first = matrix[p][p];
        let diagonal_second = matrix[q][q];
        let cross = matrix[p][q];
        matrix[p][p] = cosine.mul_add(
            cosine * diagonal_first - 2.0 * sine * cross,
            sine * sine * diagonal_second,
        );
        matrix[q][q] = sine.mul_add(
            sine * diagonal_first + 2.0 * cosine * cross,
            cosine * cosine * diagonal_second,
        );
        matrix[p][q] = 0.0;
        matrix[q][p] = 0.0;
        for row in &mut vectors {
            let old_p = row[p];
            let old_q = row[q];
            row[p] = cosine * old_p - sine * old_q;
            row[q] = sine * old_p + cosine * old_q;
        }
    }

    let mut pairs = [
        (matrix[0][0], [vectors[0][0], vectors[1][0], vectors[2][0]]),
        (matrix[1][1], [vectors[0][1], vectors[1][1], vectors[2][1]]),
        (matrix[2][2], [vectors[0][2], vectors[1][2], vectors[2][2]]),
    ];
    pairs.sort_by(|left, right| right.0.total_cmp(&left.0));
    (
        [pairs[0].0, pairs[1].0, pairs[2].0],
        [pairs[0].1, pairs[1].1, pairs[2].1],
    )
}

fn largest_off_diagonal(matrix: [[f64; 3]; 3]) -> (usize, usize, f64) {
    let candidates = [
        (0, 1, matrix[0][1].abs()),
        (0, 2, matrix[0][2].abs()),
        (1, 2, matrix[1][2].abs()),
    ];
    candidates
        .into_iter()
        .max_by(|left, right| left.2.total_cmp(&right.2))
        .expect("fixed non-empty candidate set")
}

fn anchor_direction(axis: &mut [f64; 3], projection_space: CalibrationProjectionSpace) {
    let indices: &[usize] = match projection_space {
        CalibrationProjectionSpace::Full3d => &[0, 1, 2],
        CalibrationProjectionSpace::Xz => &[0, 2],
    };
    let dominant_index = indices
        .iter()
        .copied()
        .max_by(|left, right| axis[*left].abs().total_cmp(&axis[*right].abs()))
        .expect("projection space has coordinates");
    if axis[dominant_index] < 0.0 {
        for component in axis {
            *component = -*component;
        }
    }
}

fn normalize(vector: &mut [f64; 3]) {
    let norm = vector.iter().map(|value| value * value).sum::<f64>().sqrt();
    if norm > EIGEN_EPSILON {
        for value in vector {
            *value /= norm;
        }
    }
}

fn project(vector: [f64; 3], center: [f64; 3], axis: [f64; 3]) -> f64 {
    vector
        .into_iter()
        .zip(center)
        .zip(axis)
        .map(|((value, center_value), axis_value)| (value - center_value) * axis_value)
        .sum()
}

fn projected_norm(vector: [f64; 3], projection_space: CalibrationProjectionSpace) -> f64 {
    match projection_space {
        CalibrationProjectionSpace::Full3d => vector
            .into_iter()
            .map(|component| component * component)
            .sum::<f64>()
            .sqrt(),
        CalibrationProjectionSpace::Xz => vector[0].hypot(vector[2]),
    }
}

fn projected_distance(
    left: [f64; 3],
    right: [f64; 3],
    projection_space: CalibrationProjectionSpace,
) -> f64 {
    let delta = [left[0] - right[0], left[1] - right[1], left[2] - right[2]];
    projected_norm(delta, projection_space)
}

fn percentile(sorted: &[f64], percent: usize) -> f64 {
    let scaled_position = sorted.len().saturating_sub(1) * percent;
    let lower_index = scaled_position / 100;
    let remainder = scaled_position % 100;
    let upper_index = if remainder == 0 {
        lower_index
    } else {
        lower_index + 1
    };
    if lower_index == upper_index {
        sorted[lower_index]
    } else {
        let remainder = u32::try_from(remainder).expect("percentile remainder is below 100");
        let fraction = f64::from(remainder) / 100.0;
        sorted[lower_index] + fraction * (sorted[upper_index] - sorted[lower_index])
    }
}

fn median(values: &[f64]) -> f64 {
    let mut ordered = [0.0; LIVE_MEDIAN_CAPACITY];
    ordered[..values.len()].copy_from_slice(values);
    ordered[..values.len()].sort_by(f64::total_cmp);
    let middle = values.len() / 2;
    if values.len() % 2 == 0 {
        (ordered[middle - 1] + ordered[middle]) * 0.5
    } else {
        ordered[middle]
    }
}

fn adapt_bounds(model: &mut CalibrationModel, projection: f64, parameters: CalibrationParameters) {
    let minimum_lower = model.initial_lower - parameters.adaptive_maximum_expansion;
    let maximum_upper = model.initial_upper + parameters.adaptive_maximum_expansion;
    if projection < model.lower {
        model.lower = (model.lower
            - (model.lower - projection).min(parameters.adaptive_expand_step))
        .max(minimum_lower);
    } else if model.lower < model.initial_lower {
        model.lower = (model.lower + parameters.adaptive_contract_step).min(model.initial_lower);
    }
    if projection > model.upper {
        model.upper = (model.upper
            + (projection - model.upper).min(parameters.adaptive_expand_step))
        .min(maximum_upper);
    } else if model.upper > model.initial_upper {
        model.upper = (model.upper - parameters.adaptive_contract_step).max(model.initial_upper);
    }
    if model.upper - model.lower < parameters.minimum_span {
        let midpoint = (model.upper + model.lower) * 0.5;
        let half_span = parameters.minimum_span * 0.5;
        model.lower = midpoint - half_span;
        model.upper = midpoint + half_span;
    }
}

fn increment(counter: &mut u64) {
    *counter = counter.saturating_add(1);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn timestamp(value: u64) -> BreathTimestampMicros {
        BreathTimestampMicros::new(value)
    }

    fn generation(value: u64) -> BreathGeneration {
        BreathGeneration::new(value).expect("non-zero generation")
    }

    fn test_parameters() -> CalibrationParameters {
        CalibrationParameters {
            accepted_frame_target: 8,
            watchdog_micros: 2_000_000,
            stale_after_micros: 200_000,
            useful_signal_min_norm: 0.001,
            motion_deadband: 0.001,
            maximum_step: 8.0,
            minimum_span: 0.05,
            ..CalibrationParameters::default()
        }
    }

    fn configured(parameters: CalibrationParameters) -> AcceptedFrameCalibration {
        let mut owner = AcceptedFrameCalibration::new();
        owner.configure(timestamp(0), CalibrationConfiguration::new(parameters));
        owner
    }

    #[test]
    fn configuration_bounds_are_typed_and_fail_closed() {
        let mut parameters = test_parameters();
        parameters.accepted_frame_target = MIN_ACCEPTED_FRAME_TARGET - 1;
        assert_eq!(
            CalibrationConfiguration::new(parameters),
            Err(CalibrationConfigurationError::AcceptedFrameTarget)
        );
        parameters = test_parameters();
        parameters.accepted_frame_target = MAX_ACCEPTED_FRAME_TARGET + 1;
        assert_eq!(
            CalibrationConfiguration::new(parameters),
            Err(CalibrationConfigurationError::AcceptedFrameTarget)
        );
        parameters = test_parameters();
        parameters.analysis_interval_micros = MIN_ANALYSIS_INTERVAL_MICROS - 1;
        assert_eq!(
            CalibrationConfiguration::new(parameters),
            Err(CalibrationConfigurationError::AnalysisInterval)
        );
        parameters = test_parameters();
        parameters.watchdog_micros = 1;
        assert_eq!(
            CalibrationConfiguration::new(parameters),
            Err(CalibrationConfigurationError::Watchdog)
        );
        parameters = test_parameters();
        parameters.live_ema_alpha = f64::NAN;
        assert_eq!(
            CalibrationConfiguration::new(parameters),
            Err(CalibrationConfigurationError::LiveFilterAlpha)
        );
    }

    #[test]
    fn lifecycle_and_generation_fences_remain_fail_closed() {
        let mut owner = configured(test_parameters());
        let first = generation(2);
        assert_eq!(
            owner.start(timestamp(10), first).lifecycle,
            CalibrationLifecycle::Collecting
        );
        assert_eq!(
            owner.start(timestamp(20), generation(3)).status,
            CalibrationStatus::ActionRejected
        );
        assert_eq!(
            owner
                .observe(timestamp(30), generation(1), CalibrationInput::Missing)
                .rejection,
            Some(CalibrationRejection::GenerationMismatch {
                submitted: generation(1),
                current: Some(first)
            })
        );
        owner.cancel(timestamp(40), first);
        assert_eq!(
            owner.start(timestamp(50), first).rejection,
            Some(CalibrationRejection::GenerationNotFresh {
                submitted: first,
                previous: first
            })
        );
        owner.reset(timestamp(60));
        owner.configure(
            timestamp(70),
            CalibrationConfiguration::new(test_parameters()),
        );
        assert!(matches!(
            owner.start(timestamp(80), first).rejection,
            Some(CalibrationRejection::GenerationNotFresh { .. })
        ));
    }

    #[test]
    fn malformed_stale_out_of_order_and_regressed_time_clear_live_state() {
        let mut owner = configured(test_parameters());
        let active = generation(1);
        owner.start(timestamp(10), active);
        let malformed = owner.observe(
            timestamp(20),
            active,
            CalibrationInput::Frame(CalibrationMotionFrame {
                sequence_id: 1,
                sampled_at: timestamp(20),
                vector: [f64::NAN, 0.0, 0.0],
            }),
        );
        assert!(matches!(
            malformed.rejection,
            Some(CalibrationRejection::Input(
                CalibrationInputError::NonFiniteComponent { index: 0 }
            ))
        ));
        let stale = owner.observe(
            timestamp(500_000),
            active,
            CalibrationInput::Frame(CalibrationMotionFrame {
                sequence_id: 2,
                sampled_at: timestamp(20),
                vector: [1.0, 0.0, 0.0],
            }),
        );
        assert!(matches!(
            stale.rejection,
            Some(CalibrationRejection::Input(
                CalibrationInputError::Stale { .. }
            ))
        ));
        let out_of_order = owner.observe(
            timestamp(510_000),
            active,
            CalibrationInput::Frame(CalibrationMotionFrame {
                sequence_id: 1,
                sampled_at: timestamp(510_000),
                vector: [1.0, 0.0, 0.0],
            }),
        );
        assert!(matches!(
            out_of_order.rejection,
            Some(CalibrationRejection::Input(
                CalibrationInputError::OutOfOrderSequence { .. }
            ))
        ));
        let regressed = owner.observe(timestamp(509_999), active, CalibrationInput::Missing);
        assert!(matches!(
            regressed.rejection,
            Some(CalibrationRejection::TimeRegression { .. })
        ));
        assert!(regressed.live.is_none());
    }
}
