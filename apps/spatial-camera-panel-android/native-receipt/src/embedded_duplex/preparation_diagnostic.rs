//! Bounded observation of actual preparation calls. No authority or callbacks.
use crate::embedded_duplex::preparation_native_guard::{
    FailureReason as NativeReason, Guard as NativeGuard,
};
use std::sync::{Arc, Mutex};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Actor {
    pub app_generation: u64,
    pub app_record: String,
    pub native_generation: u64,
    pub config: String,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Stage {
    Capability,
    PriorArchive,
    OriginalRead,
    JavaEligibility,
    OriginalMatch,
    NativeEligibility,
    PhysicalEligibility,
    ResourceTarget,
    ConstructorBinding,
    Entropy,
    ResourceTargetDigest,
    RequestReservation,
    SourceSnapshot,
    ArenaInstall,
    SdkPreparation,
}
impl Stage {
    #[cfg(test)]
    pub const ALL: [Self; 15] = [
        Self::Capability,
        Self::PriorArchive,
        Self::OriginalRead,
        Self::JavaEligibility,
        Self::OriginalMatch,
        Self::NativeEligibility,
        Self::PhysicalEligibility,
        Self::ResourceTarget,
        Self::ConstructorBinding,
        Self::Entropy,
        Self::ResourceTargetDigest,
        Self::RequestReservation,
        Self::SourceSnapshot,
        Self::ArenaInstall,
        Self::SdkPreparation,
    ];
    pub fn name(self) -> &'static str {
        match self {
            Self::Capability => "capability",
            Self::PriorArchive => "prior_archive",
            Self::OriginalRead => "original_read",
            Self::JavaEligibility => "java_eligibility",
            Self::OriginalMatch => "original_match",
            Self::NativeEligibility => "native_eligibility",
            Self::PhysicalEligibility => "physical_eligibility",
            Self::ResourceTarget => "resource_target",
            Self::ConstructorBinding => "constructor_binding",
            Self::Entropy => "entropy",
            Self::ResourceTargetDigest => "resource_target_digest",
            Self::RequestReservation => "request_reservation",
            Self::SourceSnapshot => "source_snapshot",
            Self::ArenaInstall => "arena_install",
            Self::SdkPreparation => "sdk_preparation",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Reason {
    CallFailed,
    PredicateRejected,
    JavaGuardFailed,
}
impl Reason {
    pub fn name(self) -> &'static str {
        match self {
            Self::CallFailed => "call_failed",
            Self::PredicateRejected => "predicate_rejected",
            Self::JavaGuardFailed => "java_guard_failed",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum JavaGuard {
    CallbackFence,
    TargetActor,
    TargetFields,
    TargetOriginalActor,
    TargetMetadata,
    ActorIdentity,
    ArmObservation,
    ArmValidation,
    OriginalBinding,
    PhysicalObservation,
    CallbackClose,
}
impl JavaGuard {
    pub fn name(self) -> &'static str {
        match self {
            Self::CallbackFence => "callback_fence",
            Self::TargetActor => "target_actor",
            Self::TargetFields => "target_fields",
            Self::TargetOriginalActor => "target_original_actor",
            Self::TargetMetadata => "target_metadata",
            Self::ActorIdentity => "actor_identity",
            Self::ArmObservation => "arm_observation",
            Self::ArmValidation => "arm_validation",
            Self::OriginalBinding => "original_binding",
            Self::PhysicalObservation => "physical_observation",
            Self::CallbackClose => "callback_close",
        }
    }
    pub fn parse(value: &str) -> Option<Self> {
        [
            Self::CallbackFence,
            Self::TargetActor,
            Self::TargetFields,
            Self::TargetOriginalActor,
            Self::TargetMetadata,
            Self::ActorIdentity,
            Self::ArmObservation,
            Self::ArmValidation,
            Self::OriginalBinding,
            Self::PhysicalObservation,
            Self::CallbackClose,
        ]
        .into_iter()
        .find(|v| v.name() == value)
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PhysicalPredicates {
    pub shape: bool,
    pub schema: bool,
    pub app_generation: bool,
    pub app_record: bool,
    pub native_generation: bool,
    pub config: bool,
    pub classification: bool,
    pub original: bool,
    pub eligible: Option<bool>,
    pub unresolved_zero: Option<bool>,
    pub peer: Option<bool>,
    pub renderer: Option<bool>,
    pub control: Option<bool>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum State {
    Entered,
    Failed,
    Succeeded,
}
impl State {
    pub fn name(self) -> &'static str {
        match self {
            Self::Entered => "entered",
            Self::Failed => "failed",
            Self::Succeeded => "succeeded",
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Observation {
    pub actor: Actor,
    pub sequence: u64,
    pub original: Option<String>,
    pub state: State,
    pub stage: Option<Stage>,
    pub reason: Option<Reason>,
    pub arena_installed: bool,
    pub physical: Option<PhysicalPredicates>,
    pub java_guard: Option<JavaGuard>,
    pub native_guard: Option<NativeGuard>,
    pub native_reason: Option<NativeReason>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ReadError {
    ActorMismatch,
    OriginalMismatch,
    OriginalUnobserved,
    Unavailable,
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Journal {
    sequence: u64,
    last: Option<Observation>,
    unavailable: bool,
}
impl Journal {
    pub fn read(
        &self,
        actor: &Actor,
        original: Option<&str>,
    ) -> Result<Option<Observation>, ReadError> {
        if self.unavailable {
            return Err(ReadError::Unavailable);
        }
        let Some(value) = &self.last else {
            return if original.is_some() {
                Err(ReadError::OriginalUnobserved)
            } else {
                Ok(None)
            };
        };
        if &value.actor != actor {
            return Err(ReadError::ActorMismatch);
        }
        if let Some(expected) = original {
            match value.original.as_deref() {
                None => return Err(ReadError::OriginalUnobserved),
                Some(actual) if actual != expected => return Err(ReadError::OriginalMismatch),
                _ => {}
            }
        }
        Ok(Some(value.clone()))
    }
}
pub(crate) struct Attempt {
    journal: Arc<Mutex<Journal>>,
    actor: Actor,
    sequence: Option<u64>,
}
impl Attempt {
    pub fn begin(journal: Arc<Mutex<Journal>>, actor: Actor) -> Self {
        // Diagnostics never replace an actual operation result, including poison
        // or counter exhaustion. Such a journal becomes unavailable, not healthy.
        let sequence = journal.lock().ok().and_then(|mut value| {
            let Some(next) = value.sequence.checked_add(1) else {
                value.last = None;
                value.unavailable = true;
                return None;
            };
            value.sequence = next;
            value.last = Some(Observation {
                actor: actor.clone(),
                sequence: next,
                original: None,
                state: State::Entered,
                stage: None,
                reason: None,
                arena_installed: false,
                physical: None,
                java_guard: None,
                native_guard: None,
                native_reason: None,
            });
            Some(next)
        });
        Self {
            journal,
            actor,
            sequence,
        }
    }
    fn update(&self, action: impl FnOnce(&mut Observation)) {
        if let Ok(mut journal) = self.journal.lock() {
            if let Some(value) = journal
                .last
                .as_mut()
                .filter(|v| Some(v.sequence) == self.sequence && v.actor == self.actor)
            {
                action(value)
            }
        }
    }
    pub fn call<T, E>(
        &self,
        stage: Stage,
        reason: Reason,
        operation: impl FnOnce() -> Result<T, E>,
    ) -> Result<T, E> {
        self.update(|value| {
            if value.state == State::Entered {
                value.stage = Some(stage)
            }
        });
        // Never hold the diagnostic lock while entering a guard, callback or SDK.
        let result = operation();
        if result.is_err() {
            self.update(|value| {
                if value.state == State::Entered {
                    value.state = State::Failed;
                    value.stage = Some(stage);
                    value.reason = Some(reason);
                }
            })
        }
        result
    }
    pub fn bind_original(&self, original: &str) -> bool {
        if original.len() != 32
            || !original
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return false;
        }
        let mut bound = false;
        self.update(|value| {
            if value
                .original
                .as_deref()
                .is_none_or(|prior| prior == original)
            {
                value.original = Some(original.into());
                bound = true
            }
        });
        bound
    }
    pub fn arena_installed(&self) {
        self.update(|value| value.arena_installed = true)
    }
    pub fn physical(&self, facts: PhysicalPredicates) {
        self.update(|value| value.physical = Some(facts))
    }
    pub fn java_failure(&self, guard: JavaGuard) {
        self.update(|value| {
            if value.state == State::Entered {
                value.state = State::Failed;
                value.stage = Some(Stage::JavaEligibility);
                value.reason = Some(Reason::JavaGuardFailed);
                value.java_guard = Some(guard);
            }
        })
    }
    pub fn native_failure(&self, guard: NativeGuard, reason: NativeReason) {
        self.update(|value| {
            if value.state == State::Entered {
                value.state = State::Failed;
                value.stage = Some(Stage::NativeEligibility);
                value.reason = Some(Reason::CallFailed);
                value.native_guard = Some(guard);
                value.native_reason = Some(reason);
            }
        })
    }
    pub fn finish(&self, succeeded: bool) {
        self.update(|value| {
            if value.state == State::Entered {
                value.state = if succeeded {
                    State::Succeeded
                } else {
                    State::Failed
                };
                if succeeded {
                    value.stage = None
                } else {
                    value.reason = Some(Reason::CallFailed)
                }
            }
        })
    }
}

#[cfg(test)]
mod exhaustion_tests {
    use super::*;
    #[test]
    fn exhausted_counter_invalidates_prior_view_without_changing_the_operation() {
        let actor = Actor {
            app_generation: 1,
            app_record: "a".repeat(64),
            native_generation: 2,
            config: "b".repeat(64),
        };
        let journal = Arc::new(Mutex::new(Journal::default()));
        let prior = Attempt::begin(journal.clone(), actor.clone());
        prior.finish(true);
        journal.lock().unwrap().sequence = u64::MAX;
        let next = Attempt::begin(journal.clone(), actor.clone());
        let mut count = 0;
        assert_eq!(
            next.call(Stage::Capability, Reason::CallFailed, || {
                count += 1;
                Ok::<_, ()>(7)
            }),
            Ok(7)
        );
        assert_eq!(count, 1);
        assert_eq!(
            journal.lock().unwrap().read(&actor, None),
            Err(ReadError::Unavailable)
        );
    }
}
