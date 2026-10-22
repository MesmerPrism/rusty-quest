#[path = "../../native-receipt/src/embedded_duplex/preparation_native_guard.rs"]
pub(crate) mod preparation_native_guard;
mod embedded_duplex {
    pub(crate) use crate::preparation_native_guard;
}
#[path = "../../native-receipt/src/embedded_duplex/preparation_diagnostic.rs"]
mod production;
use preparation_native_guard::{FailureReason as NativeReason, Guard as NativeGuard, Trace};
use production::*;
use std::sync::{Arc, Mutex};

fn actor() -> Actor {
    Actor {
        app_generation: 7,
        app_record: "a".repeat(64),
        native_generation: 19,
        config: "b".repeat(64),
    }
}
fn trail() -> (Arc<Mutex<Journal>>, Attempt) {
    let journal = Arc::new(Mutex::new(Journal::default()));
    let attempt = Attempt::begin(journal.clone(), actor());
    (journal, attempt)
}

#[test]
fn native_failure_must_retain_a_closed_subguard_instead_of_only_outer_call_failed() {
    let (journal, attempt) = trail();
    let result: Result<(), &str> =
        attempt.call(Stage::NativeEligibility, Reason::CallFailed, || {
            let observe = |guard, reason| attempt.native_failure(guard, reason);
            let trace = Trace::new(&observe);
            trace.run(|| {
                trace.mark(
                    NativeGuard::ReceiverRestoredHistory,
                    NativeReason::PredicateRejected,
                );
                Err("fresh incoming history unresolved or restored")
            })
        });
    assert_eq!(result, Err("fresh incoming history unresolved or restored"));
    let view = journal
        .lock()
        .unwrap()
        .read(&actor(), None)
        .unwrap()
        .unwrap();
    assert!(
        format!("{view:?}").contains("native_guard: Some(ReceiverRestoredHistory)"),
        "actual native failure lost its first closed subguard: {view:?}"
    );
}

#[test]
fn every_native_guard_is_closed_and_cannot_replace_first_failure_or_successor() {
    let mut names = std::collections::BTreeSet::new();
    for guard in NativeGuard::ALL {
        assert!(names.insert(guard.name()));
        assert!(guard
            .name()
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b == b'_'));
        let (journal, attempt) = trail();
        let id = "c".repeat(32);
        attempt.bind_original(&id);
        attempt.native_failure(guard, NativeReason::PredicateRejected);
        attempt.native_failure(NativeGuard::BrokerPending, NativeReason::CallFailed);
        attempt.finish(true);
        let view = journal
            .lock()
            .unwrap()
            .read(&actor(), Some(&id))
            .unwrap()
            .unwrap();
        assert_eq!(view.native_guard, Some(guard));
        assert_eq!(view.native_reason, Some(NativeReason::PredicateRejected));
        assert!(!view.arena_installed);
        let next = Attempt::begin(journal.clone(), actor());
        let before = journal.lock().unwrap().clone();
        attempt.native_failure(NativeGuard::Capability, NativeReason::CallFailed);
        assert_eq!(*journal.lock().unwrap(), before);
        next.finish(true);
    }
    assert_eq!(NativeReason::CallFailed.name(), "call_failed");
    assert_eq!(NativeReason::PredicateRejected.name(), "predicate_rejected");
}

#[test]
fn every_actual_stage_preserves_primary_error_and_retains_only_closed_failure() {
    for stage in Stage::ALL {
        let (journal, attempt) = trail();
        let mut calls = 0;
        let result: Result<(), &str> = attempt.call(stage, Reason::CallFailed, || {
            calls += 1;
            Err("private native exception text")
        });
        attempt.finish(result.is_ok());
        assert_eq!(result, Err("private native exception text"));
        assert_eq!(calls, 1);
        let view = journal
            .lock()
            .unwrap()
            .read(&actor(), None)
            .unwrap()
            .unwrap();
        assert_eq!(view.state, State::Failed);
        assert_eq!(view.stage, Some(stage));
        assert_eq!(view.reason, Some(Reason::CallFailed));
        assert_eq!(view.original, None);
        assert!(!format!("{view:?}").contains("private native exception"));
    }
}
#[test]
fn first_failure_and_known_original_cannot_be_replaced() {
    let (journal, attempt) = trail();
    let id = "c".repeat(32);
    assert!(attempt.bind_original(&id));
    assert!(!attempt.bind_original(&"d".repeat(32)));
    let _: Result<(), ()> = attempt.call(
        Stage::PhysicalEligibility,
        Reason::PredicateRejected,
        || Err(()),
    );
    let _: Result<(), ()> = attempt.call(Stage::SdkPreparation, Reason::CallFailed, || Err(()));
    attempt.finish(true);
    let view = journal
        .lock()
        .unwrap()
        .read(&actor(), Some(&id))
        .unwrap()
        .unwrap();
    assert_eq!(view.stage, Some(Stage::PhysicalEligibility));
    assert_eq!(view.state, State::Failed);
    assert_eq!(
        journal
            .lock()
            .unwrap()
            .read(&actor(), Some(&"d".repeat(32))),
        Err(ReadError::OriginalMismatch)
    );
}
#[test]
fn reads_are_pure_and_wrong_actor_or_unobserved_original_refuse() {
    let (journal, attempt) = trail();
    let before = journal.lock().unwrap().clone();
    for _ in 0..5 {
        assert_eq!(
            journal
                .lock()
                .unwrap()
                .read(&actor(), None)
                .unwrap()
                .unwrap()
                .state,
            State::Entered
        );
    }
    assert_eq!(*journal.lock().unwrap(), before);
    assert_eq!(
        journal
            .lock()
            .unwrap()
            .read(&actor(), Some(&"c".repeat(32))),
        Err(ReadError::OriginalUnobserved)
    );
    for field in 0..4 {
        let mut wrong = actor();
        match field {
            0 => wrong.app_generation += 1,
            1 => wrong.app_record = "d".repeat(64),
            2 => wrong.native_generation += 1,
            _ => wrong.config = "d".repeat(64),
        };
        assert_eq!(
            journal.lock().unwrap().read(&wrong, None),
            Err(ReadError::ActorMismatch)
        );
    }
    attempt.arena_installed();
    let value: Result<usize, ()> =
        attempt.call(Stage::SdkPreparation, Reason::CallFailed, || Ok(9));
    attempt.finish(value.is_ok());
    let view = journal
        .lock()
        .unwrap()
        .read(&actor(), None)
        .unwrap()
        .unwrap();
    assert_eq!(view.state, State::Succeeded);
    assert!(view.arena_installed);
    assert_eq!(view.reason, None);
}
#[test]
fn unobserved_is_not_prior_unknown_success_and_stale_attempt_cannot_modify_successor() {
    let empty = Journal::default();
    assert_eq!(empty.read(&actor(), None), Ok(None));
    assert_eq!(
        empty.read(&actor(), Some(&"c".repeat(32))),
        Err(ReadError::OriginalUnobserved)
    );
    let (journal, old) = trail();
    assert!(old.bind_original(&"c".repeat(32)));
    let current = Attempt::begin(journal.clone(), actor());
    assert!(current.bind_original(&"d".repeat(32)));
    let before = journal.lock().unwrap().clone();
    old.arena_installed();
    old.finish(false);
    let _: Result<(), ()> = old.call(Stage::SdkPreparation, Reason::CallFailed, || Err(()));
    assert_eq!(*journal.lock().unwrap(), before);
    assert_eq!(
        journal
            .lock()
            .unwrap()
            .read(&actor(), Some(&"c".repeat(32))),
        Err(ReadError::OriginalMismatch)
    );
}
#[test]
fn diagnostic_lock_failure_never_replaces_primary_operation_outcome() {
    let journal = Arc::new(Mutex::new(Journal::default()));
    let other = journal.clone();
    let _ = std::thread::spawn(move || {
        let _guard = other.lock().unwrap();
        panic!("modeled diagnostic poison")
    })
    .join();
    let attempt = Attempt::begin(journal, actor());
    let mut calls = 0;
    let result: Result<(), &str> = attempt.call(Stage::Capability, Reason::CallFailed, || {
        calls += 1;
        Err("primary")
    });
    attempt.finish(false);
    assert_eq!(calls, 1);
    assert_eq!(result, Err("primary"));
}
#[test]
fn closed_java_guard_and_computed_predicates_survive_read_without_callbacks() {
    let (journal, attempt) = trail();
    let predicates = PhysicalPredicates {
        shape: true,
        schema: true,
        app_generation: true,
        app_record: true,
        native_generation: true,
        config: true,
        classification: true,
        original: true,
        eligible: Some(false),
        unresolved_zero: Some(false),
        peer: Some(true),
        renderer: Some(false),
        control: None,
    };
    attempt.physical(predicates.clone());
    let _: Result<(), ()> = attempt.call(Stage::JavaEligibility, Reason::CallFailed, || {
        attempt.java_failure(JavaGuard::ArmValidation);
        Err(())
    });
    let view = journal
        .lock()
        .unwrap()
        .read(&actor(), None)
        .unwrap()
        .unwrap();
    assert_eq!(view.reason, Some(Reason::JavaGuardFailed));
    assert_eq!(view.java_guard, Some(JavaGuard::ArmValidation));
    assert_eq!(view.physical, Some(predicates));
    for name in [
        "callback_fence",
        "target_actor",
        "target_fields",
        "target_original_actor",
        "target_metadata",
        "actor_identity",
        "arm_observation",
        "arm_validation",
        "original_binding",
        "physical_observation",
        "callback_close",
    ] {
        assert_eq!(JavaGuard::parse(name).unwrap().name(), name);
    }
    assert_eq!(JavaGuard::parse("private error text"), None);
    assert_eq!(Stage::Entropy.name(), "entropy");
    assert_eq!(Reason::JavaGuardFailed.name(), "java_guard_failed");
    assert_eq!(State::Failed.name(), "failed");
}
