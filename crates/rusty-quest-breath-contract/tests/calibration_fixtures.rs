//! Neutral deterministic synthetic calibration-fixture conformance.

use std::f64::consts::TAU;

use rusty_quest_breath_contract::{
    calibration::{
        AcceptedFrameCalibration, CalibrationConfiguration, CalibrationFailure, CalibrationInput,
        CalibrationLifecycle, CalibrationLiveOutput, CalibrationMotionFrame,
        CalibrationObservation, CalibrationParameters, CalibrationProjectionSpace,
        CalibrationWatchdogCause, MIN_ANALYSIS_INTERVAL_MICROS,
    },
    BreathGeneration, BreathTimestampMicros,
};
use serde::Deserialize;

const FIXTURE_SCHEMA: &str = "rusty.quest.breath_calibration_fixture_set.v1";

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct FixtureSet {
    schema: String,
    #[serde(rename = "fixture_set_id")]
    set_id: String,
    accepted_frame_target: usize,
    analysis_interval_micros: u64,
    watchdog_micros: u64,
    scenarios: Vec<Scenario>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Scenario {
    id: String,
    kind: String,
    projection: String,
    axis: [f64; 3],
    bias: [f64; 3],
    amplitude: f64,
    noise: f64,
    spike_every: usize,
    minimum_span: f64,
    inverted: bool,
    expected: String,
}

#[derive(Debug)]
struct ScenarioRun {
    owner: AcceptedFrameCalibration,
    observation: CalibrationObservation,
    generation: BreathGeneration,
    next_sequence: u64,
    last_at: u64,
}

fn fixture_set() -> FixtureSet {
    serde_json::from_str(include_str!(
        "../../../fixtures/breath-contract/calibration-scenarios.json"
    ))
    .expect("calibration fixture set parses")
}

fn timestamp(value: u64) -> BreathTimestampMicros {
    BreathTimestampMicros::new(value)
}

fn generation() -> BreathGeneration {
    BreathGeneration::new(1).expect("non-zero fixture generation")
}

fn projection(token: &str) -> CalibrationProjectionSpace {
    match token {
        "full-3d" => CalibrationProjectionSpace::Full3d,
        "xz" => CalibrationProjectionSpace::Xz,
        other => panic!("unsupported fixture projection {other}"),
    }
}

fn parameters(set: &FixtureSet, scenario: &Scenario) -> CalibrationParameters {
    CalibrationParameters {
        projection_space: projection(&scenario.projection),
        accepted_frame_target: set.accepted_frame_target,
        analysis_interval_micros: set.analysis_interval_micros,
        watchdog_micros: set.watchdog_micros,
        stale_after_micros: set.analysis_interval_micros,
        useful_signal_min_norm: 0.001,
        motion_deadband: 0.0001,
        maximum_component_abs: 16.0,
        maximum_step: 4.0,
        minimum_span: scenario.minimum_span,
        minimum_axis_dominance: 0.05,
        live_ema_alpha: 1.0,
        adaptive_expand_step: 0.05,
        adaptive_contract_step: 0.01,
        adaptive_maximum_expansion: 0.12,
        inverted: scenario.inverted,
    }
}

fn sinusoid_vector(scenario: &Scenario, index: usize, count: usize) -> [f64; 3] {
    let index = u32::try_from(index).expect("fixture index is bounded");
    let count = u32::try_from(count).expect("fixture count is bounded");
    let phase = TAU * (f64::from(index) + 0.25) / f64::from(count);
    let signal = scenario.amplitude * phase.sin();
    let noise_pattern = f64::from((index * 17) % 11) / 5.0 - 1.0;
    [
        scenario.bias[0] + scenario.axis[0] * signal + scenario.noise * noise_pattern,
        scenario.bias[1] + scenario.axis[1] * signal - scenario.noise * noise_pattern * 0.5,
        scenario.bias[2] + scenario.axis[2] * signal + scenario.noise * noise_pattern * 0.25,
    ]
}

fn frame(sequence_id: u64, at: u64, vector: [f64; 3]) -> CalibrationInput {
    CalibrationInput::Frame(CalibrationMotionFrame {
        sequence_id,
        sampled_at: timestamp(at),
        vector,
    })
}

fn feed_sinusoid(
    owner: &mut AcceptedFrameCalibration,
    set: &FixtureSet,
    scenario: &Scenario,
    active_generation: BreathGeneration,
) -> (CalibrationObservation, u64, u64) {
    let mut next_sequence = 1_u64;
    let mut observation = owner.snapshot();
    let mut last_at = 1_u64;
    for index in 0..set.accepted_frame_target {
        let offset = u64::try_from(index + 1).expect("fixture index is bounded");
        let at = 1 + offset * set.analysis_interval_micros;
        if scenario.spike_every > 0 && index % scenario.spike_every == 0 {
            observation = owner.observe(
                timestamp(at - set.analysis_interval_micros / 2),
                active_generation,
                frame(
                    next_sequence,
                    at - set.analysis_interval_micros / 2,
                    [99.0, 0.0, 0.0],
                ),
            );
            assert!(observation.live.is_none());
            next_sequence += 1;
        }
        observation = owner.observe(
            timestamp(at),
            active_generation,
            frame(
                next_sequence,
                at,
                sinusoid_vector(scenario, index, set.accepted_frame_target),
            ),
        );
        next_sequence += 1;
        last_at = at;
    }
    (observation, next_sequence, last_at)
}

fn feed_stationary(
    owner: &mut AcceptedFrameCalibration,
    set: &FixtureSet,
    scenario: &Scenario,
    active_generation: BreathGeneration,
) -> (CalibrationObservation, u64, u64) {
    let mut next_sequence = 1_u64;
    for index in 0..set.accepted_frame_target {
        let offset = u64::try_from(index + 1).expect("fixture index is bounded");
        let at = 1 + offset * set.analysis_interval_micros;
        owner.observe(
            timestamp(at),
            active_generation,
            frame(next_sequence, at, scenario.bias),
        );
        next_sequence += 1;
    }
    let last_at = 2 + set.watchdog_micros;
    let observation = owner.observe(
        timestamp(last_at),
        active_generation,
        CalibrationInput::Missing,
    );
    (observation, next_sequence, last_at)
}

fn feed_short_signal(
    owner: &mut AcceptedFrameCalibration,
    set: &FixtureSet,
    scenario: &Scenario,
    active_generation: BreathGeneration,
) -> (CalibrationObservation, u64, u64) {
    let mut next_sequence = 1_u64;
    for index in 0..3 {
        let offset = u64::try_from(index + 1).expect("fixture index is bounded");
        let at = 1 + offset * set.analysis_interval_micros;
        owner.observe(
            timestamp(at),
            active_generation,
            frame(
                next_sequence,
                at,
                sinusoid_vector(scenario, index, set.accepted_frame_target),
            ),
        );
        next_sequence += 1;
    }
    let last_at = 2 + set.watchdog_micros;
    let observation = owner.observe(
        timestamp(last_at),
        active_generation,
        CalibrationInput::Missing,
    );
    (observation, next_sequence, last_at)
}

fn feed_isotropic_ring(
    owner: &mut AcceptedFrameCalibration,
    set: &FixtureSet,
    scenario: &Scenario,
    active_generation: BreathGeneration,
) -> (CalibrationObservation, u64, u64) {
    let mut next_sequence = 1_u64;
    let mut observation = owner.snapshot();
    let mut last_at = 1_u64;
    for index in 0..set.accepted_frame_target {
        let index_u32 = u32::try_from(index).expect("fixture index is bounded");
        let count = u32::try_from(set.accepted_frame_target).expect("target is bounded");
        let phase = TAU * (f64::from(index_u32) + 0.25) / f64::from(count);
        let vector = [
            scenario.bias[0] + scenario.amplitude * phase.cos(),
            scenario.bias[1] + scenario.amplitude * phase.sin(),
            scenario.bias[2],
        ];
        let offset = u64::try_from(index + 1).expect("fixture index is bounded");
        let at = 1 + offset * set.analysis_interval_micros;
        observation = owner.observe(
            timestamp(at),
            active_generation,
            frame(next_sequence, at, vector),
        );
        next_sequence += 1;
        last_at = at;
    }
    (observation, next_sequence, last_at)
}

fn execute(set: &FixtureSet, scenario: &Scenario) -> ScenarioRun {
    let active_generation = generation();
    let mut owner = AcceptedFrameCalibration::new();
    owner.configure(
        timestamp(0),
        CalibrationConfiguration::new(parameters(set, scenario)),
    );
    owner.start(timestamp(1), active_generation);
    let (observation, next_sequence, last_at) = match scenario.kind.as_str() {
        "sinusoid" => feed_sinusoid(&mut owner, set, scenario, active_generation),
        "stationary" => feed_stationary(&mut owner, set, scenario, active_generation),
        "short-sinusoid" => feed_short_signal(&mut owner, set, scenario, active_generation),
        "isotropic-ring" => feed_isotropic_ring(&mut owner, set, scenario, active_generation),
        "missing" => {
            let last_at = 2 + set.watchdog_micros;
            let observation = owner.observe(
                timestamp(last_at),
                active_generation,
                CalibrationInput::Missing,
            );
            (observation, 1, last_at)
        }
        other => panic!("unsupported fixture kind {other}"),
    };

    ScenarioRun {
        owner,
        observation,
        generation: active_generation,
        next_sequence,
        last_at,
    }
}

fn dot(left: [f64; 3], right: [f64; 3]) -> f64 {
    left.into_iter().zip(right).map(|(a, b)| a * b).sum()
}

fn ready_live(run: &ScenarioRun) -> CalibrationLiveOutput {
    assert_eq!(run.observation.lifecycle, CalibrationLifecycle::Ready);
    run.observation.live.expect("ready live output")
}

#[test]
fn synthetic_fixture_matrix_is_deterministic_and_failure_coded() {
    let set = fixture_set();
    assert_eq!(set.schema, FIXTURE_SCHEMA);
    assert_eq!(set.set_id, "neutral-accepted-frame-calibration");
    assert_eq!(set.analysis_interval_micros, MIN_ANALYSIS_INTERVAL_MICROS);

    for scenario in &set.scenarios {
        let first = execute(&set, scenario);
        let second = execute(&set, scenario);
        assert_eq!(first.observation, second.observation, "{}", scenario.id);
        match scenario.expected.as_str() {
            "ready" => {
                assert_eq!(first.observation.lifecycle, CalibrationLifecycle::Ready);
                assert_eq!(first.observation.accepted_frames, set.accepted_frame_target);
                assert!((first.observation.progress01 - 1.0).abs() < f64::EPSILON);
                let model = first.observation.model.expect("ready model");
                assert!(model.initial_span >= scenario.minimum_span);
                assert!((0.0..=1.0).contains(&model.axis_dominance01));
                assert!((0.0..=1.0).contains(&ready_live(&first).volume01));
                let expected_axis = if scenario.inverted {
                    scenario.axis.map(|value| -value)
                } else {
                    scenario.axis
                };
                assert!(
                    dot(model.axis, expected_axis).abs() > 0.97,
                    "{}",
                    scenario.id
                );
                if scenario.spike_every > 0 {
                    assert!(first.observation.telemetry.malformed_input_count > 0);
                    assert!(first.observation.telemetry.rejected_frame_count > 0);
                }
            }
            "watchdog-no-useful-signal" => assert!(matches!(
                first.observation.failure,
                Some(CalibrationFailure::Watchdog {
                    cause: CalibrationWatchdogCause::NoUsefulSignal,
                    ..
                })
            )),
            "watchdog-insufficient-motion" => assert!(matches!(
                first.observation.failure,
                Some(CalibrationFailure::Watchdog {
                    cause: CalibrationWatchdogCause::InsufficientMotion,
                    ..
                })
            )),
            "watchdog-insufficient-frames" => assert!(matches!(
                first.observation.failure,
                Some(CalibrationFailure::Watchdog {
                    cause: CalibrationWatchdogCause::InsufficientAcceptedFrames,
                    ..
                })
            )),
            "degenerate-axis" => assert!(matches!(
                first.observation.failure,
                Some(CalibrationFailure::DegenerateAxis { .. })
            )),
            "insufficient-span" => assert!(matches!(
                first.observation.failure,
                Some(CalibrationFailure::InsufficientSpan { .. })
            )),
            other => panic!("unsupported fixture expectation {other}"),
        }
    }
}

#[test]
fn explicit_inversion_reverses_axis_bounds_and_live_direction() {
    let set = fixture_set();
    let inverted = set
        .scenarios
        .iter()
        .find(|scenario| scenario.id == "direction-inversion")
        .expect("inversion fixture");
    let mut forward = inverted.clone();
    forward.inverted = false;
    let forward_run = execute(&set, &forward);
    let inverted_run = execute(&set, inverted);
    let forward_model = forward_run.observation.model.expect("forward model");
    let inverted_model = inverted_run.observation.model.expect("inverted model");
    for (forward_axis, inverted_axis) in forward_model.axis.into_iter().zip(inverted_model.axis) {
        assert!((forward_axis + inverted_axis).abs() < 1.0e-10);
    }
    assert!((forward_model.initial_lower + inverted_model.initial_upper).abs() < 1.0e-10);
    assert!((forward_model.initial_upper + inverted_model.initial_lower).abs() < 1.0e-10);
    let combined_volume = ready_live(&forward_run).volume01 + ready_live(&inverted_run).volume01;
    assert!((combined_volume - 1.0).abs() < 1.0e-10);
}

#[test]
fn rapid_valid_change_updates_live_output_before_next_analysis_tick() {
    let set = fixture_set();
    let scenario = set
        .scenarios
        .iter()
        .find(|scenario| scenario.id == "rapid-live-response")
        .expect("rapid-response fixture");
    let mut run = execute(&set, scenario);
    let before = ready_live(&run);
    let analysis_ticks = run.observation.telemetry.analysis_tick_count;
    let live_updates = run.observation.telemetry.live_update_count;
    let at = run.last_at + set.analysis_interval_micros / 5;
    let changed_vector = [
        scenario.bias[0] + scenario.axis[0] * scenario.amplitude,
        scenario.bias[1] + scenario.axis[1] * scenario.amplitude,
        scenario.bias[2] + scenario.axis[2] * scenario.amplitude,
    ];
    let observation = run.owner.observe(
        timestamp(at),
        run.generation,
        frame(run.next_sequence, at, changed_vector),
    );
    let after = observation.live.expect("rapid live output");
    assert!(!after.analysis_admitted);
    assert_eq!(observation.telemetry.analysis_tick_count, analysis_ticks);
    assert_eq!(observation.telemetry.live_update_count, live_updates + 1);
    assert!((after.volume01 - before.volume01).abs() > 0.05);
    assert!((after.filtered_projection - before.filtered_projection).abs() > 0.05);
}

#[test]
fn unrelated_generation_cannot_mutate_ready_model_or_live_output() {
    let set = fixture_set();
    let scenario = set
        .scenarios
        .iter()
        .find(|scenario| scenario.id == "rapid-live-response")
        .expect("generation fixture");
    let mut run = execute(&set, scenario);
    let before = run.observation.clone();
    let unrelated = BreathGeneration::new(2).expect("non-zero unrelated generation");
    let rejected = run.owner.observe(
        timestamp(run.last_at + 1),
        unrelated,
        CalibrationInput::Missing,
    );
    assert_eq!(rejected.generation, Some(run.generation));
    assert_eq!(rejected.model, before.model);
    assert_eq!(rejected.live, before.live);
    assert_eq!(rejected.accepted_frames, before.accepted_frames);
    assert_eq!(
        rejected.telemetry.generation_rejection_count,
        before.telemetry.generation_rejection_count + 1
    );
}

#[test]
fn adaptive_limits_expand_contract_and_remain_bounded() {
    let set = fixture_set();
    let scenario = set
        .scenarios
        .iter()
        .find(|scenario| scenario.id == "adaptive-limit-behavior")
        .expect("adaptive fixture");
    let mut run = execute(&set, scenario);
    let initial = run.observation.model.expect("initial model");
    let parameters = parameters(&set, scenario);
    let high_vector = [
        scenario.bias[0] + scenario.axis[0] * 1.8,
        scenario.bias[1] + scenario.axis[1] * 1.8,
        scenario.bias[2] + scenario.axis[2] * 1.8,
    ];
    for step in 1..=5 {
        let offset = u64::try_from(step).expect("bounded step");
        let at = run.last_at + offset * (set.analysis_interval_micros / 5);
        run.observation = run.owner.observe(
            timestamp(at),
            run.generation,
            frame(run.next_sequence, at, high_vector),
        );
        run.next_sequence += 1;
    }
    run.last_at += set.analysis_interval_micros;
    let expanded = run.observation.model.expect("expanded model");
    assert!(expanded.upper > initial.upper);
    assert!(expanded.upper - initial.upper <= parameters.adaptive_expand_step + 1.0e-12);
    assert!(
        expanded.upper <= initial.initial_upper + parameters.adaptive_maximum_expansion + 1.0e-12
    );

    for step in 1..=5 {
        let offset = u64::try_from(step).expect("bounded step");
        let at = run.last_at + offset * (set.analysis_interval_micros / 5);
        run.observation = run.owner.observe(
            timestamp(at),
            run.generation,
            frame(run.next_sequence, at, scenario.bias),
        );
        run.next_sequence += 1;
    }
    let contracted = run.observation.model.expect("contracted model");
    assert!(contracted.upper < expanded.upper);
    assert!(expanded.upper - contracted.upper <= parameters.adaptive_contract_step + 1.0e-12);
    assert!(contracted.upper - contracted.lower >= parameters.minimum_span);
}
