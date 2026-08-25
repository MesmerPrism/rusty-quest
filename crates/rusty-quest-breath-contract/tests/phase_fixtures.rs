//! Deterministic neutral fixtures for the common timestamp-aware phase owner.

use rusty_quest_breath_contract::{
    assessment::CommonBreathPhase,
    phase::{
        CommonPhaseClassifier, CommonPhaseConfiguration, CommonPhaseInput, CommonPhaseObservation,
        CommonPhaseParameters, CommonPhaseResetReason, CommonPhaseStatus,
    },
    BreathTimestampMicros,
};

fn parameters() -> CommonPhaseParameters {
    CommonPhaseParameters {
        enter_derivative_per_second: 0.16,
        exit_derivative_per_second: 0.05,
        derivative_filter_alpha: 0.5,
        directional_confirmation_micros: 100_000,
        hold_confirmation_micros: 120_000,
        minimum_phase_dwell_micros: 120_000,
        stale_after_micros: 250_000,
        discontinuity_after_micros: 900_000,
        inverted: false,
    }
}

fn classifier_with(parameters: CommonPhaseParameters) -> CommonPhaseClassifier {
    CommonPhaseClassifier::new(
        CommonPhaseConfiguration::new(parameters).expect("valid fixture configuration"),
    )
}

fn micros_as_f64(value: u64) -> f64 {
    f64::from(u32::try_from(value).unwrap_or(u32::MAX))
}

fn replay(parameters: CommonPhaseParameters, points: &[(u64, f64)]) -> Vec<CommonPhaseObservation> {
    let mut classifier = classifier_with(parameters);
    let mut at = 1_u64;
    points
        .iter()
        .enumerate()
        .map(|(index, &(delta_micros, value01))| {
            at = at.saturating_add(delta_micros);
            classifier.observe(
                BreathTimestampMicros::new(at),
                CommonPhaseInput::Sample {
                    sequence_id: index as u64 + 1,
                    sampled_at: BreathTimestampMicros::new(at),
                    value01,
                },
            )
        })
        .collect()
}

fn cycle_points() -> Vec<(u64, f64)> {
    let mut points = Vec::new();
    for index in 0..=12 {
        points.push((50_000, 0.20 + f64::from(index) * 0.05));
    }
    for _ in 0..12 {
        points.push((50_000, 0.80));
    }
    for index in 1..=12 {
        points.push((50_000, 0.80 - f64::from(index) * 0.05));
    }
    points
}

#[test]
fn inhale_hold_exhale_and_endpoint_hold_are_distinct_and_deterministic() {
    let points = cycle_points();
    let first = replay(parameters(), &points);
    let second = replay(parameters(), &points);
    assert_eq!(first, second);
    let phases: Vec<_> = first.iter().map(|observation| observation.phase).collect();
    assert!(phases.contains(&CommonBreathPhase::Inhale));
    assert!(phases.contains(&CommonBreathPhase::Hold));
    assert!(phases.contains(&CommonBreathPhase::Exhale));
    let final_hold = replay(
        parameters(),
        &points
            .into_iter()
            .chain((0..8).map(|_| (50_000, 0.20)))
            .collect::<Vec<_>>(),
    );
    assert_eq!(
        final_hold.last().expect("endpoint observation").phase,
        CommonBreathPhase::Hold
    );
}

#[test]
fn asymmetric_rates_and_variable_dropped_samples_preserve_direction() {
    let mut points = vec![(40_000, 0.20)];
    let mut value = 0.20;
    for delta in [40_000, 90_000, 60_000, 110_000, 45_000, 80_000] {
        value += micros_as_f64(delta) * 0.45 / 1_000_000.0;
        points.push((delta, value));
    }
    for _ in 0..12 {
        points.push((50_000, value));
    }
    for delta in [100_000, 55_000, 120_000, 65_000, 90_000] {
        value -= micros_as_f64(delta) * 0.75 / 1_000_000.0;
        points.push((delta, value));
    }
    let phases: Vec<_> = replay(parameters(), &points)
        .into_iter()
        .map(|observation| observation.phase)
        .collect();
    assert!(phases.contains(&CommonBreathPhase::Inhale));
    assert!(phases.contains(&CommonBreathPhase::Hold));
    assert_eq!(phases.last(), Some(&CommonBreathPhase::Exhale));
}

#[test]
fn drift_shallow_motion_and_threshold_noise_do_not_flicker() {
    let mut points = Vec::new();
    let mut value = 0.50;
    for index in 0..40 {
        value += 0.002;
        let noise = if index % 2 == 0 { 0.001 } else { -0.001 };
        points.push((100_000, value + noise));
    }
    let observations = replay(parameters(), &points);
    assert!(observations
        .iter()
        .skip(4)
        .all(|observation| observation.phase == CommonBreathPhase::Hold));
    assert_eq!(
        observations
            .last()
            .expect("drift observation")
            .telemetry
            .phase_transition_count,
        1
    );
}

fn first_transition_at(sample_hz: u64, target: CommonBreathPhase) -> u64 {
    let dt = 1_000_000 / sample_hz;
    let mut value = 0.15;
    let mut points = Vec::new();
    for _ in 0..sample_hz {
        value += 0.40 * micros_as_f64(dt) / 1_000_000.0;
        points.push((dt, value));
    }
    replay(parameters(), &points)
        .into_iter()
        .find(|observation| observation.phase == target)
        .and_then(|observation| observation.sampled_at)
        .expect("directional transition")
        .get()
}

#[test]
fn cadence_parity_is_timestamp_based_at_72_90_and_120_hz() {
    let baseline = first_transition_at(72, CommonBreathPhase::Inhale);
    for rate in [90, 120] {
        let observed = first_transition_at(rate, CommonBreathPhase::Inhale);
        assert!(observed.abs_diff(baseline) <= 20_000);
    }
}

#[test]
fn stale_reconnect_discontinuity_and_source_reset_require_fresh_confirmation() {
    let mut classifier = classifier_with(parameters());
    let prime = classifier.observe(
        BreathTimestampMicros::new(100_000),
        CommonPhaseInput::Sample {
            sequence_id: 1,
            sampled_at: BreathTimestampMicros::new(100_000),
            value01: 0.2,
        },
    );
    assert_eq!(prime.status, CommonPhaseStatus::Primed);
    let stale = classifier.observe(
        BreathTimestampMicros::new(500_001),
        CommonPhaseInput::Sample {
            sequence_id: 2,
            sampled_at: BreathTimestampMicros::new(200_000),
            value01: 0.4,
        },
    );
    assert_eq!(stale.phase, CommonBreathPhase::Unknown);
    assert_eq!(
        stale.status,
        CommonPhaseStatus::Reset(CommonPhaseResetReason::Stale)
    );
    let reconnected = classifier.observe(
        BreathTimestampMicros::new(510_000),
        CommonPhaseInput::Sample {
            sequence_id: 3,
            sampled_at: BreathTimestampMicros::new(510_000),
            value01: 0.4,
        },
    );
    assert_eq!(reconnected.status, CommonPhaseStatus::Primed);
    let reset = classifier.reset_history(
        BreathTimestampMicros::new(520_000),
        CommonPhaseResetReason::SourceChanged,
    );
    assert_eq!(reset.phase, CommonBreathPhase::Unknown);
    let after_switch = classifier.observe(
        BreathTimestampMicros::new(530_000),
        CommonPhaseInput::Sample {
            sequence_id: 1,
            sampled_at: BreathTimestampMicros::new(530_000),
            value01: 0.8,
        },
    );
    assert_eq!(after_switch.status, CommonPhaseStatus::Primed);

    let discontinuity = classifier.observe(
        BreathTimestampMicros::new(1_500_001),
        CommonPhaseInput::Sample {
            sequence_id: 2,
            sampled_at: BreathTimestampMicros::new(1_500_001),
            value01: 0.7,
        },
    );
    assert_eq!(
        discontinuity.status,
        CommonPhaseStatus::Reset(CommonPhaseResetReason::TimeDiscontinuity)
    );
}

#[test]
fn inversion_reverses_direction_without_changing_signal_bounds() {
    let points: Vec<_> = (0..8)
        .map(|index| (50_000, 0.2 + f64::from(index) * 0.05))
        .collect();
    let normal = replay(parameters(), &points);
    let inverted = replay(
        CommonPhaseParameters {
            inverted: true,
            ..parameters()
        },
        &points,
    );
    assert_eq!(
        normal.last().expect("normal phase").phase,
        CommonBreathPhase::Inhale
    );
    assert_eq!(
        inverted.last().expect("inverted phase").phase,
        CommonBreathPhase::Exhale
    );
    assert!(inverted.iter().all(|observation| observation
        .filtered_derivative_per_second
        .is_none_or(f64::is_finite)));
}
