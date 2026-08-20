//! Pure lifecycle and observation contract for bounded breath inputs.
//!
//! The contract has no clock, thread, platform, transport, sensor, or renderer
//! dependency. Callers inject timestamps and explicit lifecycle actions.

pub mod assessment;
pub mod calibration;
pub mod phase;

/// Observation schema emitted by this contract.
pub const BREATH_CONTRACT_OBSERVATION_SCHEMA_ID: &str =
    "rusty.quest.breath_contract.observation.v1";

/// Maximum number of actions accepted by the neutral replay harness.
pub const MAX_REPLAY_ACTIONS: usize = 1_024;
/// Maximum accepted stale-input interval.
pub const MAX_STALE_AFTER_MICROS: u64 = 60_000_000;
/// Maximum accepted forward-discontinuity interval.
pub const MAX_DISCONTINUITY_AFTER_MICROS: u64 = 600_000_000;

/// Injected monotonic timestamp in microseconds.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord)]
pub struct BreathTimestampMicros(u64);

impl BreathTimestampMicros {
    /// Construct an injected timestamp.
    #[must_use]
    pub const fn new(value: u64) -> Self {
        Self(value)
    }

    /// Return the raw microsecond value.
    #[must_use]
    pub const fn get(self) -> u64 {
        self.0
    }
}

/// Non-zero generation issued by a successful `Start` action.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct BreathGeneration(u64);

impl BreathGeneration {
    /// Construct an exact generation for replay or cross-call fencing.
    ///
    /// # Errors
    ///
    /// Returns [`BreathGenerationError::Zero`] for the reserved zero value.
    pub const fn new(value: u64) -> Result<Self, BreathGenerationError> {
        if value == 0 {
            Err(BreathGenerationError::Zero)
        } else {
            Ok(Self(value))
        }
    }

    /// Return the raw generation value.
    #[must_use]
    pub const fn get(self) -> u64 {
        self.0
    }
}

/// Generation construction error.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathGenerationError {
    /// Generation zero is reserved for the disabled state.
    Zero,
}

/// Validated lifecycle configuration.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BreathContractConfiguration {
    /// Maximum admitted sample age.
    stale_after_micros: u64,
    /// Maximum admitted gap between current-generation actions.
    discontinuity_after_micros: u64,
}

impl BreathContractConfiguration {
    /// Create a configuration after checking all temporal bounds.
    ///
    /// # Errors
    ///
    /// Rejects zero, inverted, or over-limit intervals.
    pub const fn new(
        stale_after_micros: u64,
        discontinuity_after_micros: u64,
    ) -> Result<Self, BreathConfigurationError> {
        if stale_after_micros == 0 || stale_after_micros > MAX_STALE_AFTER_MICROS {
            return Err(BreathConfigurationError::InvalidStaleInterval);
        }
        if discontinuity_after_micros <= stale_after_micros
            || discontinuity_after_micros > MAX_DISCONTINUITY_AFTER_MICROS
        {
            return Err(BreathConfigurationError::InvalidDiscontinuityInterval);
        }
        Ok(Self {
            stale_after_micros,
            discontinuity_after_micros,
        })
    }

    /// Return the maximum admitted sample age.
    #[must_use]
    pub const fn stale_after_micros(self) -> u64 {
        self.stale_after_micros
    }

    /// Return the maximum admitted current-generation action gap.
    #[must_use]
    pub const fn discontinuity_after_micros(self) -> u64 {
        self.discontinuity_after_micros
    }
}

/// Configuration validation error.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathConfigurationError {
    /// The stale interval is zero or exceeds its public bound.
    InvalidStaleInterval,
    /// The discontinuity interval is not greater than stale or exceeds its bound.
    InvalidDiscontinuityInterval,
}

/// Explicit lifecycle state.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum BreathLifecycle {
    /// No configuration or active generation exists.
    #[default]
    Disabled,
    /// A validated configuration exists, but no generation is active.
    Configured,
    /// One exact generation may submit observations.
    Running,
    /// The preceding generation was cancelled; configuration is retained.
    Cancelled,
}

impl BreathLifecycle {
    /// Stable neutral token for telemetry and fixtures.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Configured => "configured",
            Self::Running => "running",
            Self::Cancelled => "cancelled",
        }
    }
}

/// Lifecycle action names used in typed rejection evidence.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathActionKind {
    /// Clear configuration and current-generation state.
    Reset,
    /// Install validated temporal bounds.
    Configure,
    /// Issue and activate a fresh generation.
    Start,
    /// Invalidate the exact active generation.
    Cancel,
    /// Submit one explicit input observation.
    Observe,
}

/// Raw input supplied to `Observe`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum BreathInput {
    /// No input was available at the injected observation time.
    Missing,
    /// One normalized sample and its source timestamp.
    Sample {
        /// Source timestamp in the same injected time domain.
        sampled_at: BreathTimestampMicros,
        /// Normalized source value, required to be finite and in `[0, 1]`.
        value01: f32,
        /// Normalized source quality, required to be finite and in `[0, 1]`.
        quality01: f32,
    },
}

/// Latest accepted sample for the current generation.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AcceptedBreathSample {
    /// Source timestamp.
    pub sampled_at: BreathTimestampMicros,
    /// Contract observation timestamp.
    pub observed_at: BreathTimestampMicros,
    /// Age at admission.
    pub age_micros: u64,
    /// Bounded normalized value.
    pub value01: f32,
    /// Bounded normalized quality.
    pub quality01: f32,
}

/// Terminal status of the most recent lifecycle action.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum BreathObservationStatus {
    /// Default construction is inert.
    #[default]
    Disabled,
    /// Reset completed.
    Reset,
    /// Configure completed.
    Configured,
    /// Start completed with a fresh generation.
    Started,
    /// Cancel completed for the exact active generation.
    Cancelled,
    /// One bounded sample was accepted.
    SampleAccepted,
    /// Missing input cleared the effective sample.
    MissingInput,
    /// A stale input was rejected and cleared.
    StaleInput,
    /// A forward time discontinuity was observed and current data was cleared.
    TimeDiscontinuity,
    /// Configuration validation failed.
    RejectedInvalidConfiguration,
    /// The requested action was invalid for the current lifecycle.
    RejectedInvalidLifecycle,
    /// An old or unrelated generation attempted a mutation.
    RejectedGeneration,
    /// Injected action time moved backwards.
    RejectedTimeRegression,
    /// A sample was non-finite, out of bounds, or from the future.
    RejectedMalformedInput,
    /// A sample timestamp did not advance strictly.
    RejectedOutOfOrderInput,
    /// The generation counter cannot issue another non-zero value.
    RejectedGenerationExhausted,
}

impl BreathObservationStatus {
    /// Stable neutral token for telemetry and fixtures.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Reset => "reset",
            Self::Configured => "configured",
            Self::Started => "started",
            Self::Cancelled => "cancelled",
            Self::SampleAccepted => "sample-accepted",
            Self::MissingInput => "missing-input",
            Self::StaleInput => "stale-input",
            Self::TimeDiscontinuity => "time-discontinuity",
            Self::RejectedInvalidConfiguration => "rejected-invalid-configuration",
            Self::RejectedInvalidLifecycle => "rejected-invalid-lifecycle",
            Self::RejectedGeneration => "rejected-generation",
            Self::RejectedTimeRegression => "rejected-time-regression",
            Self::RejectedMalformedInput => "rejected-malformed-input",
            Self::RejectedOutOfOrderInput => "rejected-out-of-order-input",
            Self::RejectedGenerationExhausted => "rejected-generation-exhausted",
        }
    }
}

/// Malformed sample reason.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathSampleError {
    /// The value was NaN or infinite.
    NonFiniteValue,
    /// The value was outside `[0, 1]`.
    ValueOutOfBounds,
    /// The quality was NaN or infinite.
    NonFiniteQuality,
    /// The quality was outside `[0, 1]`.
    QualityOutOfBounds,
    /// The source timestamp was later than the injected observation time.
    FutureTimestamp,
}

/// Typed reason for a rejected action.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathRejection {
    /// Configuration did not satisfy public bounds.
    InvalidConfiguration(BreathConfigurationError),
    /// The action is not valid in the current lifecycle.
    InvalidLifecycle {
        /// Rejected action.
        action: BreathActionKind,
        /// Lifecycle observed before rejection.
        lifecycle: BreathLifecycle,
    },
    /// The submitted generation was not the current active generation.
    GenerationMismatch {
        /// Submitted generation.
        submitted: BreathGeneration,
        /// Current generation, if one is active.
        current: Option<BreathGeneration>,
    },
    /// Injected action time moved backwards.
    TimeRegression {
        /// Rejected timestamp.
        submitted: BreathTimestampMicros,
        /// Last accepted action timestamp.
        last_accepted: BreathTimestampMicros,
    },
    /// Sample contents were malformed.
    MalformedSample(BreathSampleError),
    /// Sample timestamp did not advance strictly.
    OutOfOrderSample {
        /// Rejected source timestamp.
        submitted: BreathTimestampMicros,
        /// Last observed source timestamp.
        last_observed: BreathTimestampMicros,
    },
    /// No additional non-zero generation can be issued.
    GenerationExhausted,
}

/// Neutral low-rate counters. All counters saturate instead of wrapping.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BreathTelemetry {
    /// Completed resets since the latest successful reset.
    pub reset_count: u64,
    /// Completed configurations.
    pub configure_count: u64,
    /// Generations started.
    pub start_count: u64,
    /// Generations cancelled.
    pub cancel_count: u64,
    /// Accepted samples.
    pub accepted_sample_count: u64,
    /// Explicit missing inputs.
    pub missing_input_count: u64,
    /// Stale inputs.
    pub stale_input_count: u64,
    /// Malformed inputs.
    pub malformed_input_count: u64,
    /// Out-of-order inputs.
    pub out_of_order_input_count: u64,
    /// Old or unrelated generation submissions.
    pub generation_rejection_count: u64,
    /// Backwards injected action timestamps.
    pub time_regression_count: u64,
    /// Forward time discontinuities.
    pub discontinuity_count: u64,
    /// Actions rejected because of lifecycle state.
    pub lifecycle_rejection_count: u64,
    /// Invalid configuration attempts.
    pub configuration_rejection_count: u64,
}

/// Complete low-rate observation of contract state.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BreathContractObservation {
    /// Stable schema identifier.
    pub schema_id: &'static str,
    /// Current lifecycle.
    pub lifecycle: BreathLifecycle,
    /// Exact active generation, if running.
    pub generation: Option<BreathGeneration>,
    /// Most recent action status.
    pub status: BreathObservationStatus,
    /// Typed rejection reason, when rejected.
    pub rejection: Option<BreathRejection>,
    /// Latest accepted current-generation sample.
    pub sample: Option<AcceptedBreathSample>,
    /// Last accepted current-generation action time.
    pub last_action_at: Option<BreathTimestampMicros>,
    /// Neutral counters.
    pub telemetry: BreathTelemetry,
}

/// Stateful pure contract owner.
#[derive(Clone, Debug)]
pub struct BreathContract {
    lifecycle: BreathLifecycle,
    configuration: Option<BreathContractConfiguration>,
    generation: Option<BreathGeneration>,
    next_generation: u64,
    last_action_at: Option<BreathTimestampMicros>,
    last_input_at: Option<BreathTimestampMicros>,
    sample: Option<AcceptedBreathSample>,
    status: BreathObservationStatus,
    rejection: Option<BreathRejection>,
    telemetry: BreathTelemetry,
}

impl Default for BreathContract {
    fn default() -> Self {
        Self {
            lifecycle: BreathLifecycle::Disabled,
            configuration: None,
            generation: None,
            next_generation: 1,
            last_action_at: None,
            last_input_at: None,
            sample: None,
            status: BreathObservationStatus::Disabled,
            rejection: None,
            telemetry: BreathTelemetry::default(),
        }
    }
}

impl BreathContract {
    /// Create an inert contract.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Return the current low-rate observation without advancing time.
    #[must_use]
    pub const fn snapshot(&self) -> BreathContractObservation {
        BreathContractObservation {
            schema_id: BREATH_CONTRACT_OBSERVATION_SCHEMA_ID,
            lifecycle: self.lifecycle,
            generation: self.generation,
            status: self.status,
            rejection: self.rejection,
            sample: self.sample,
            last_action_at: self.last_action_at,
            telemetry: self.telemetry,
        }
    }

    /// Clear configuration, active generation, source history, and counters.
    ///
    /// The generation allocator remains monotonic so a reset cannot make an old
    /// generation current again.
    pub fn reset(&mut self, at: BreathTimestampMicros) -> BreathContractObservation {
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        self.lifecycle = BreathLifecycle::Disabled;
        self.configuration = None;
        self.generation = None;
        self.last_action_at = Some(at);
        self.last_input_at = None;
        self.sample = None;
        self.status = BreathObservationStatus::Reset;
        self.rejection = None;
        self.telemetry = BreathTelemetry::default();
        increment(&mut self.telemetry.reset_count);
        self.snapshot()
    }

    /// Install validated temporal bounds while no generation is running.
    pub fn configure(
        &mut self,
        at: BreathTimestampMicros,
        configuration: Result<BreathContractConfiguration, BreathConfigurationError>,
    ) -> BreathContractObservation {
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        if self.lifecycle == BreathLifecycle::Running {
            return self.reject_lifecycle(BreathActionKind::Configure);
        }
        let configuration = match configuration {
            Ok(configuration) => configuration,
            Err(error) => {
                increment(&mut self.telemetry.configuration_rejection_count);
                return self.reject(
                    BreathObservationStatus::RejectedInvalidConfiguration,
                    BreathRejection::InvalidConfiguration(error),
                );
            }
        };
        self.lifecycle = BreathLifecycle::Configured;
        self.configuration = Some(configuration);
        self.generation = None;
        self.last_action_at = Some(at);
        self.last_input_at = None;
        self.sample = None;
        self.status = BreathObservationStatus::Configured;
        self.rejection = None;
        increment(&mut self.telemetry.configure_count);
        self.snapshot()
    }

    /// Start a fresh generation from configured or cancelled state.
    pub fn start(&mut self, at: BreathTimestampMicros) -> BreathContractObservation {
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        if self.configuration.is_none()
            || !matches!(
                self.lifecycle,
                BreathLifecycle::Configured | BreathLifecycle::Cancelled
            )
        {
            return self.reject_lifecycle(BreathActionKind::Start);
        }
        let Some(next_generation) = self.next_generation.checked_add(1) else {
            return self.reject(
                BreathObservationStatus::RejectedGenerationExhausted,
                BreathRejection::GenerationExhausted,
            );
        };
        let generation = BreathGeneration(self.next_generation);
        self.next_generation = next_generation;
        self.lifecycle = BreathLifecycle::Running;
        self.generation = Some(generation);
        self.last_action_at = Some(at);
        self.last_input_at = None;
        self.sample = None;
        self.status = BreathObservationStatus::Started;
        self.rejection = None;
        increment(&mut self.telemetry.start_count);
        self.snapshot()
    }

    /// Cancel the exact active generation.
    pub fn cancel(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
    ) -> BreathContractObservation {
        if self.lifecycle != BreathLifecycle::Running {
            return self.reject_lifecycle(BreathActionKind::Cancel);
        }
        if self.generation != Some(generation) {
            return self.reject_generation(generation);
        }
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        self.lifecycle = BreathLifecycle::Cancelled;
        self.generation = None;
        self.last_action_at = Some(at);
        self.last_input_at = None;
        self.sample = None;
        self.status = BreathObservationStatus::Cancelled;
        self.rejection = None;
        increment(&mut self.telemetry.cancel_count);
        self.snapshot()
    }

    /// Observe one explicit input for the exact active generation.
    pub fn observe(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
        input: BreathInput,
    ) -> BreathContractObservation {
        if self.lifecycle != BreathLifecycle::Running {
            return self.reject_lifecycle(BreathActionKind::Observe);
        }
        if self.generation != Some(generation) {
            return self.reject_generation(generation);
        }
        if let Some(observation) = self.reject_time_regression(at) {
            return observation;
        }
        let Some(configuration) = self.configuration else {
            return self.reject_lifecycle(BreathActionKind::Observe);
        };
        if let Some(last_action_at) = self.last_action_at {
            if at.get().saturating_sub(last_action_at.get())
                > configuration.discontinuity_after_micros
            {
                self.last_action_at = Some(at);
                self.sample = None;
                self.status = BreathObservationStatus::TimeDiscontinuity;
                self.rejection = None;
                increment(&mut self.telemetry.discontinuity_count);
                return self.snapshot();
            }
        }
        self.last_action_at = Some(at);
        match input {
            BreathInput::Missing => {
                self.sample = None;
                self.status = BreathObservationStatus::MissingInput;
                self.rejection = None;
                increment(&mut self.telemetry.missing_input_count);
            }
            BreathInput::Sample {
                sampled_at,
                value01,
                quality01,
            } => {
                if sampled_at > at {
                    return self.reject_malformed(BreathSampleError::FutureTimestamp);
                }
                if let Some(last_input_at) = self.last_input_at {
                    if sampled_at <= last_input_at {
                        self.sample = None;
                        increment(&mut self.telemetry.out_of_order_input_count);
                        return self.reject(
                            BreathObservationStatus::RejectedOutOfOrderInput,
                            BreathRejection::OutOfOrderSample {
                                submitted: sampled_at,
                                last_observed: last_input_at,
                            },
                        );
                    }
                }
                self.last_input_at = Some(sampled_at);
                if !value01.is_finite() {
                    return self.reject_malformed(BreathSampleError::NonFiniteValue);
                }
                if !(0.0..=1.0).contains(&value01) {
                    return self.reject_malformed(BreathSampleError::ValueOutOfBounds);
                }
                if !quality01.is_finite() {
                    return self.reject_malformed(BreathSampleError::NonFiniteQuality);
                }
                if !(0.0..=1.0).contains(&quality01) {
                    return self.reject_malformed(BreathSampleError::QualityOutOfBounds);
                }
                let age_micros = at.get() - sampled_at.get();
                if age_micros > configuration.stale_after_micros {
                    self.sample = None;
                    self.status = BreathObservationStatus::StaleInput;
                    self.rejection = None;
                    increment(&mut self.telemetry.stale_input_count);
                    return self.snapshot();
                }
                self.sample = Some(AcceptedBreathSample {
                    sampled_at,
                    observed_at: at,
                    age_micros,
                    value01,
                    quality01,
                });
                self.status = BreathObservationStatus::SampleAccepted;
                self.rejection = None;
                increment(&mut self.telemetry.accepted_sample_count);
            }
        }
        self.snapshot()
    }

    fn reject_time_regression(
        &mut self,
        at: BreathTimestampMicros,
    ) -> Option<BreathContractObservation> {
        let last_accepted = self.last_action_at?;
        if at >= last_accepted {
            return None;
        }
        increment(&mut self.telemetry.time_regression_count);
        Some(self.reject(
            BreathObservationStatus::RejectedTimeRegression,
            BreathRejection::TimeRegression {
                submitted: at,
                last_accepted,
            },
        ))
    }

    fn reject_lifecycle(&mut self, action: BreathActionKind) -> BreathContractObservation {
        increment(&mut self.telemetry.lifecycle_rejection_count);
        self.reject(
            BreathObservationStatus::RejectedInvalidLifecycle,
            BreathRejection::InvalidLifecycle {
                action,
                lifecycle: self.lifecycle,
            },
        )
    }

    fn reject_generation(&mut self, submitted: BreathGeneration) -> BreathContractObservation {
        increment(&mut self.telemetry.generation_rejection_count);
        self.reject(
            BreathObservationStatus::RejectedGeneration,
            BreathRejection::GenerationMismatch {
                submitted,
                current: self.generation,
            },
        )
    }

    fn reject_malformed(&mut self, error: BreathSampleError) -> BreathContractObservation {
        self.sample = None;
        increment(&mut self.telemetry.malformed_input_count);
        self.reject(
            BreathObservationStatus::RejectedMalformedInput,
            BreathRejection::MalformedSample(error),
        )
    }

    fn reject(
        &mut self,
        status: BreathObservationStatus,
        rejection: BreathRejection,
    ) -> BreathContractObservation {
        self.status = status;
        self.rejection = Some(rejection);
        self.snapshot()
    }
}

fn increment(counter: &mut u64) {
    *counter = counter.saturating_add(1);
}

/// Generation selector used by the neutral replay harness.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathReplayGeneration {
    /// Resolve the current generation at this action.
    Current,
    /// Submit one exact generation, including deliberately stale generations.
    Exact(BreathGeneration),
}

/// One pure replay action.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum BreathReplayAction {
    /// Invoke `Reset`.
    Reset {
        /// Injected action time.
        at: BreathTimestampMicros,
    },
    /// Invoke `Configure`.
    Configure {
        /// Injected action time.
        at: BreathTimestampMicros,
        /// Validated configuration or its typed validation failure.
        configuration: Result<BreathContractConfiguration, BreathConfigurationError>,
    },
    /// Invoke `Start`.
    Start {
        /// Injected action time.
        at: BreathTimestampMicros,
    },
    /// Invoke `Cancel`.
    Cancel {
        /// Injected action time.
        at: BreathTimestampMicros,
        /// Current or exact generation selector.
        generation: BreathReplayGeneration,
    },
    /// Invoke `Observe`.
    Observe {
        /// Injected action time.
        at: BreathTimestampMicros,
        /// Current or exact generation selector.
        generation: BreathReplayGeneration,
        /// Explicit input observation.
        input: BreathInput,
    },
}

/// Bounded deterministic replay report.
#[derive(Clone, Debug, PartialEq)]
pub struct BreathReplayReport {
    /// One observation for every input action, in order.
    pub observations: Vec<BreathContractObservation>,
}

impl BreathReplayReport {
    /// Return the final observation, if the replay was non-empty.
    #[must_use]
    pub fn final_observation(&self) -> Option<&BreathContractObservation> {
        self.observations.last()
    }
}

/// Replay harness rejection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathReplayError {
    /// Requested hard limit is zero or exceeds [`MAX_REPLAY_ACTIONS`].
    InvalidActionLimit,
    /// Input actions exceed the caller-selected hard limit.
    ActionLimitExceeded {
        /// Submitted action count.
        submitted: usize,
        /// Caller-selected hard limit.
        limit: usize,
    },
    /// A `Current` selector was used while no generation was active.
    CurrentGenerationUnavailable {
        /// Zero-based action index.
        action_index: usize,
    },
}

/// Execute a deterministic replay with an explicit bounded action count.
///
/// # Errors
///
/// Rejects invalid limits, over-limit inputs, and unresolved `Current`
/// generation selectors before the affected action executes.
pub fn run_bounded_replay(
    actions: &[BreathReplayAction],
    max_actions: usize,
) -> Result<BreathReplayReport, BreathReplayError> {
    if max_actions == 0 || max_actions > MAX_REPLAY_ACTIONS {
        return Err(BreathReplayError::InvalidActionLimit);
    }
    if actions.len() > max_actions {
        return Err(BreathReplayError::ActionLimitExceeded {
            submitted: actions.len(),
            limit: max_actions,
        });
    }
    let mut contract = BreathContract::new();
    let mut observations = Vec::with_capacity(actions.len());
    for (action_index, action) in actions.iter().copied().enumerate() {
        let observation = match action {
            BreathReplayAction::Reset { at } => contract.reset(at),
            BreathReplayAction::Configure { at, configuration } => {
                contract.configure(at, configuration)
            }
            BreathReplayAction::Start { at } => contract.start(at),
            BreathReplayAction::Cancel { at, generation } => {
                let generation = resolve_replay_generation(&contract, generation, action_index)?;
                contract.cancel(at, generation)
            }
            BreathReplayAction::Observe {
                at,
                generation,
                input,
            } => {
                let generation = resolve_replay_generation(&contract, generation, action_index)?;
                contract.observe(at, generation, input)
            }
        };
        observations.push(observation);
    }
    Ok(BreathReplayReport { observations })
}

fn resolve_replay_generation(
    contract: &BreathContract,
    selector: BreathReplayGeneration,
    action_index: usize,
) -> Result<BreathGeneration, BreathReplayError> {
    match selector {
        BreathReplayGeneration::Current => contract
            .generation
            .ok_or(BreathReplayError::CurrentGenerationUnavailable { action_index }),
        BreathReplayGeneration::Exact(generation) => Ok(generation),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn timestamp(value: u64) -> BreathTimestampMicros {
        BreathTimestampMicros::new(value)
    }

    fn configuration() -> BreathContractConfiguration {
        BreathContractConfiguration::new(100, 1_000).expect("valid configuration")
    }

    fn running() -> (BreathContract, BreathGeneration) {
        let mut contract = BreathContract::new();
        contract.configure(timestamp(10), Ok(configuration()));
        let started = contract.start(timestamp(20));
        (contract, started.generation.expect("generation"))
    }

    fn sample(sampled_at: u64, value01: f32, quality01: f32) -> BreathInput {
        BreathInput::Sample {
            sampled_at: timestamp(sampled_at),
            value01,
            quality01,
        }
    }

    #[test]
    fn default_construction_is_disabled_and_inert() {
        let observation = BreathContract::new().snapshot();
        assert_eq!(observation.lifecycle, BreathLifecycle::Disabled);
        assert_eq!(observation.status, BreathObservationStatus::Disabled);
        assert_eq!(observation.generation, None);
        assert_eq!(observation.sample, None);
        assert_eq!(observation.telemetry, BreathTelemetry::default());
    }

    #[test]
    fn configuration_bounds_fail_closed() {
        assert_eq!(
            BreathContractConfiguration::new(0, 1_000),
            Err(BreathConfigurationError::InvalidStaleInterval)
        );
        assert_eq!(
            BreathContractConfiguration::new(MAX_STALE_AFTER_MICROS + 1, 70_000_000),
            Err(BreathConfigurationError::InvalidStaleInterval)
        );
        assert_eq!(
            BreathContractConfiguration::new(100, 100),
            Err(BreathConfigurationError::InvalidDiscontinuityInterval)
        );
        assert_eq!(
            BreathContractConfiguration::new(100, MAX_DISCONTINUITY_AFTER_MICROS + 1),
            Err(BreathConfigurationError::InvalidDiscontinuityInterval)
        );
        assert_eq!(BreathGeneration::new(0), Err(BreathGenerationError::Zero));
    }

    #[test]
    fn lifecycle_requires_configure_start_and_exact_cancel() {
        let mut contract = BreathContract::new();
        assert_eq!(
            contract.start(timestamp(1)).status,
            BreathObservationStatus::RejectedInvalidLifecycle
        );
        assert_eq!(
            contract.configure(timestamp(2), Ok(configuration())).status,
            BreathObservationStatus::Configured
        );
        let started = contract.start(timestamp(3));
        let generation = started.generation.expect("generation");
        assert_eq!(started.status, BreathObservationStatus::Started);
        assert_eq!(
            contract.configure(timestamp(4), Ok(configuration())).status,
            BreathObservationStatus::RejectedInvalidLifecycle
        );
        assert_eq!(
            contract.cancel(timestamp(5), generation).status,
            BreathObservationStatus::Cancelled
        );
        let restarted = contract.start(timestamp(6));
        assert!(restarted.generation.expect("new generation") > generation);
    }

    #[test]
    fn generation_isolation_prevents_old_generation_mutation() {
        let (mut contract, first) = running();
        contract.observe(timestamp(30), first, sample(30, 0.25, 0.8));
        contract.cancel(timestamp(40), first);
        let second = contract.start(timestamp(50)).generation.expect("second");
        let accepted = contract.observe(timestamp(60), second, sample(60, 0.75, 0.9));
        let stale = contract.observe(timestamp(1_000), first, BreathInput::Missing);
        assert_eq!(stale.status, BreathObservationStatus::RejectedGeneration);
        assert_eq!(stale.generation, Some(second));
        assert_eq!(stale.sample, accepted.sample);
        assert_eq!(stale.last_action_at, accepted.last_action_at);

        contract.reset(timestamp(70));
        contract.configure(timestamp(80), Ok(configuration()));
        let third = contract.start(timestamp(90)).generation.expect("third");
        assert!(third > second);
        assert_eq!(
            contract
                .observe(timestamp(100), second, sample(100, 0.5, 1.0))
                .status,
            BreathObservationStatus::RejectedGeneration
        );
    }

    #[test]
    fn missing_and_stale_inputs_clear_effective_sample() {
        let (mut contract, generation) = running();
        let accepted = contract.observe(timestamp(30), generation, sample(30, 0.5, 1.0));
        assert!(accepted.sample.is_some());
        let missing = contract.observe(timestamp(40), generation, BreathInput::Missing);
        assert_eq!(missing.status, BreathObservationStatus::MissingInput);
        assert_eq!(missing.sample, None);
        let stale = contract.observe(timestamp(300), generation, sample(150, 0.5, 1.0));
        assert_eq!(stale.status, BreathObservationStatus::StaleInput);
        assert_eq!(stale.sample, None);
    }

    #[test]
    fn malformed_samples_are_distinct_and_inert() {
        let cases = [
            (sample(30, f32::NAN, 1.0), BreathSampleError::NonFiniteValue),
            (
                sample(30, f32::INFINITY, 1.0),
                BreathSampleError::NonFiniteValue,
            ),
            (sample(30, -0.1, 1.0), BreathSampleError::ValueOutOfBounds),
            (sample(30, 1.1, 1.0), BreathSampleError::ValueOutOfBounds),
            (
                sample(30, 0.5, f32::NAN),
                BreathSampleError::NonFiniteQuality,
            ),
            (sample(30, 0.5, 1.1), BreathSampleError::QualityOutOfBounds),
            (sample(31, 0.5, 1.0), BreathSampleError::FutureTimestamp),
        ];
        for (input, expected) in cases {
            let (mut contract, generation) = running();
            let observation = contract.observe(timestamp(30), generation, input);
            assert_eq!(
                observation.status,
                BreathObservationStatus::RejectedMalformedInput
            );
            assert_eq!(
                observation.rejection,
                Some(BreathRejection::MalformedSample(expected))
            );
            assert_eq!(observation.sample, None);
        }
    }

    #[test]
    fn time_regression_does_not_advance_or_clear_current_state() {
        let (mut contract, generation) = running();
        let accepted = contract.observe(timestamp(30), generation, sample(30, 0.5, 1.0));
        let regressed = contract.observe(timestamp(29), generation, BreathInput::Missing);
        assert_eq!(
            regressed.status,
            BreathObservationStatus::RejectedTimeRegression
        );
        assert_eq!(regressed.sample, accepted.sample);
        assert_eq!(regressed.last_action_at, accepted.last_action_at);
        assert_eq!(regressed.telemetry.time_regression_count, 1);
    }

    #[test]
    fn forward_discontinuity_clears_then_allows_a_fresh_sample() {
        let (mut contract, generation) = running();
        contract.observe(timestamp(30), generation, sample(30, 0.5, 1.0));
        let discontinuous = contract.observe(timestamp(2_000), generation, sample(2_000, 0.6, 1.0));
        assert_eq!(
            discontinuous.status,
            BreathObservationStatus::TimeDiscontinuity
        );
        assert_eq!(discontinuous.sample, None);
        let resumed = contract.observe(timestamp(2_010), generation, sample(2_010, 0.6, 1.0));
        assert_eq!(resumed.status, BreathObservationStatus::SampleAccepted);
    }

    #[test]
    fn input_timestamps_must_advance_strictly() {
        let (mut contract, generation) = running();
        contract.observe(timestamp(30), generation, sample(30, 0.5, 1.0));
        let duplicate = contract.observe(timestamp(31), generation, sample(30, 0.6, 1.0));
        assert_eq!(
            duplicate.status,
            BreathObservationStatus::RejectedOutOfOrderInput
        );
        assert_eq!(duplicate.sample, None);
        assert_eq!(duplicate.telemetry.out_of_order_input_count, 1);
    }

    #[test]
    fn replay_limits_reject_before_execution() {
        let actions = [
            BreathReplayAction::Reset { at: timestamp(0) },
            BreathReplayAction::Reset { at: timestamp(1) },
        ];
        assert_eq!(
            run_bounded_replay(&actions, 1),
            Err(BreathReplayError::ActionLimitExceeded {
                submitted: 2,
                limit: 1
            })
        );
        assert_eq!(
            run_bounded_replay(&actions, 0),
            Err(BreathReplayError::InvalidActionLimit)
        );
        assert_eq!(
            run_bounded_replay(&actions, MAX_REPLAY_ACTIONS + 1),
            Err(BreathReplayError::InvalidActionLimit)
        );
    }
}
