//! Deterministic accepted and damaged replay-fixture conformance.

use rusty_quest_breath_contract::{
    run_bounded_replay, BreathContractConfiguration, BreathGeneration, BreathInput,
    BreathObservationStatus, BreathReplayAction, BreathReplayError, BreathReplayGeneration,
    BreathTimestampMicros,
};
use serde::Deserialize;

const FIXTURE_SCHEMA: &str = "rusty.quest.breath_contract_replay.v1";

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct ReplayFixture {
    schema: String,
    fixture_id: String,
    max_actions: usize,
    configuration: FixtureConfiguration,
    actions: Vec<FixtureAction>,
    expected_statuses: Vec<String>,
    expected_final: ExpectedFinal,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct FixtureConfiguration {
    stale_after_micros: u64,
    discontinuity_after_micros: u64,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case", deny_unknown_fields)]
enum FixtureAction {
    Reset {
        at_micros: u64,
    },
    Configure {
        at_micros: u64,
    },
    Start {
        at_micros: u64,
    },
    CancelCurrent {
        at_micros: u64,
    },
    ObserveCurrent {
        at_micros: u64,
        input: FixtureInput,
    },
    ObserveExact {
        at_micros: u64,
        generation: u64,
        input: FixtureInput,
    },
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case", deny_unknown_fields)]
enum FixtureInput {
    Missing,
    Sample {
        sampled_at_micros: u64,
        value01: f32,
        quality01: f32,
    },
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct ExpectedFinal {
    lifecycle: String,
    generation: u64,
    accepted_sample_count: u64,
    missing_input_count: u64,
    stale_input_count: u64,
    malformed_input_count: u64,
    out_of_order_input_count: u64,
    generation_rejection_count: u64,
    discontinuity_count: u64,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct DamagedFixture {
    schema: String,
    fixture_id: String,
    max_actions: usize,
    actions: Vec<DamagedAction>,
    expected_error: String,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case", deny_unknown_fields)]
enum DamagedAction {
    Reset { at_micros: u64 },
}

fn timestamp(value: u64) -> BreathTimestampMicros {
    BreathTimestampMicros::new(value)
}

fn fixture_input(input: FixtureInput) -> BreathInput {
    match input {
        FixtureInput::Missing => BreathInput::Missing,
        FixtureInput::Sample {
            sampled_at_micros,
            value01,
            quality01,
        } => BreathInput::Sample {
            sampled_at: timestamp(sampled_at_micros),
            value01,
            quality01,
        },
    }
}

fn fixture_actions(fixture: &ReplayFixture) -> Vec<BreathReplayAction> {
    let configuration = BreathContractConfiguration::new(
        fixture.configuration.stale_after_micros,
        fixture.configuration.discontinuity_after_micros,
    )
    .expect("fixture configuration");
    fixture
        .actions
        .iter()
        .copied()
        .map(|action| match action {
            FixtureAction::Reset { at_micros } => BreathReplayAction::Reset {
                at: timestamp(at_micros),
            },
            FixtureAction::Configure { at_micros } => BreathReplayAction::Configure {
                at: timestamp(at_micros),
                configuration: Ok(configuration),
            },
            FixtureAction::Start { at_micros } => BreathReplayAction::Start {
                at: timestamp(at_micros),
            },
            FixtureAction::CancelCurrent { at_micros } => BreathReplayAction::Cancel {
                at: timestamp(at_micros),
                generation: BreathReplayGeneration::Current,
            },
            FixtureAction::ObserveCurrent { at_micros, input } => BreathReplayAction::Observe {
                at: timestamp(at_micros),
                generation: BreathReplayGeneration::Current,
                input: fixture_input(input),
            },
            FixtureAction::ObserveExact {
                at_micros,
                generation,
                input,
            } => BreathReplayAction::Observe {
                at: timestamp(at_micros),
                generation: BreathReplayGeneration::Exact(
                    BreathGeneration::new(generation).expect("non-zero fixture generation"),
                ),
                input: fixture_input(input),
            },
        })
        .collect()
}

#[test]
fn accepted_fixture_replays_deterministically() {
    let fixture: ReplayFixture = serde_json::from_str(include_str!(
        "../../../fixtures/breath-contract/deterministic-lifecycle-replay.json"
    ))
    .expect("fixture parses");
    assert_eq!(fixture.schema, FIXTURE_SCHEMA);
    assert_eq!(fixture.fixture_id, "bounded-lifecycle-replay");
    let actions = fixture_actions(&fixture);
    let first = run_bounded_replay(&actions, fixture.max_actions).expect("first replay");
    let second = run_bounded_replay(&actions, fixture.max_actions).expect("second replay");
    assert_eq!(first, second);
    let statuses: Vec<&str> = first
        .observations
        .iter()
        .map(|observation| observation.status.as_str())
        .collect();
    assert_eq!(statuses, fixture.expected_statuses);
    let final_observation = first.final_observation().expect("final observation");
    assert_eq!(
        final_observation.lifecycle.as_str(),
        fixture.expected_final.lifecycle
    );
    assert_eq!(
        final_observation
            .generation
            .expect("final generation")
            .get(),
        fixture.expected_final.generation
    );
    let telemetry = final_observation.telemetry;
    assert_eq!(
        telemetry.accepted_sample_count,
        fixture.expected_final.accepted_sample_count
    );
    assert_eq!(
        telemetry.missing_input_count,
        fixture.expected_final.missing_input_count
    );
    assert_eq!(
        telemetry.stale_input_count,
        fixture.expected_final.stale_input_count
    );
    assert_eq!(
        telemetry.malformed_input_count,
        fixture.expected_final.malformed_input_count
    );
    assert_eq!(
        telemetry.out_of_order_input_count,
        fixture.expected_final.out_of_order_input_count
    );
    assert_eq!(
        telemetry.generation_rejection_count,
        fixture.expected_final.generation_rejection_count
    );
    assert_eq!(
        telemetry.discontinuity_count,
        fixture.expected_final.discontinuity_count
    );
    assert_eq!(
        final_observation.status,
        BreathObservationStatus::SampleAccepted
    );
}

#[test]
fn damaged_fixture_is_rejected_before_execution() {
    let fixture: DamagedFixture = serde_json::from_str(include_str!(
        "../../../fixtures/breath-contract/damaged/replay-too-many-actions.json"
    ))
    .expect("damaged fixture parses");
    assert_eq!(fixture.schema, FIXTURE_SCHEMA);
    assert_eq!(fixture.fixture_id, "replay-too-many-actions");
    assert_eq!(fixture.expected_error, "action-limit-exceeded");
    let actions: Vec<BreathReplayAction> = fixture
        .actions
        .iter()
        .map(|action| match *action {
            DamagedAction::Reset { at_micros } => BreathReplayAction::Reset {
                at: timestamp(at_micros),
            },
        })
        .collect();
    assert_eq!(
        run_bounded_replay(&actions, fixture.max_actions),
        Err(BreathReplayError::ActionLimitExceeded {
            submitted: actions.len(),
            limit: fixture.max_actions,
        })
    );
}
