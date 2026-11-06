//! Closed, inert guard cursor shared by the actual native eligibility owners.
//! No authority, logging, retry, callbacks or allocation in the cursor itself.
use std::cell::Cell;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Guard {
    Capability,
    LocalSlot,
    LocalState,
    ActivationSlot,
    ActivationFlight,
    DispatchSlot,
    ActivationReplaySlot,
    CleanupReplaySlot,
    InboundPending,
    ReceiverRestoredHistory,
    ReceiverActivationWithoutHistory,
    ReceiverCompleteHistory,
    ReceiverKey,
    ReceiverResponseIdentity,
    ReceiverSignature,
    ReceiverEffect,
    ReceiverReadbackDecode,
    ReceiverReadbackBinding,
    ReceiverState,
    ReceiverRevision,
    ReceiverLatestStop,
    ReceiverActivationIdentity,
    ReceiverActivationSignature,
    CleanupState,
    CleanupPublicKey,
    CleanupSerial,
    CleanupStateLock,
    CleanupForwardOriginal,
    CleanupPreparedTerminal,
    CleanupReplayShape,
    CleanupPending,
    CleanupResponseDecode,
    CleanupResponseIdentity,
    CleanupResponseCompleted,
    CleanupResponseTerminal,
    CleanupResponseGeneration,
    CleanupResponseStop,
    CleanupResponseSignature,
    ProviderSlot,
    ProviderEvidence,
    ProviderDecode,
    BrokerPending,
}
impl Guard {
    pub fn name(self) -> &'static str {
        match self {
            Self::Capability => "capability",
            Self::LocalSlot => "local_slot",
            Self::LocalState => "local_state",
            Self::ActivationSlot => "activation_slot",
            Self::ActivationFlight => "activation_flight",
            Self::DispatchSlot => "dispatch_slot",
            Self::ActivationReplaySlot => "activation_replay_slot",
            Self::CleanupReplaySlot => "cleanup_replay_slot",
            Self::InboundPending => "inbound_pending",
            Self::ReceiverRestoredHistory => "receiver_restored_history",
            Self::ReceiverActivationWithoutHistory => "receiver_activation_without_history",
            Self::ReceiverCompleteHistory => "receiver_complete_history",
            Self::ReceiverKey => "receiver_key",
            Self::ReceiverResponseIdentity => "receiver_response_identity",
            Self::ReceiverSignature => "receiver_signature",
            Self::ReceiverEffect => "receiver_effect",
            Self::ReceiverReadbackDecode => "receiver_readback_decode",
            Self::ReceiverReadbackBinding => "receiver_readback_binding",
            Self::ReceiverState => "receiver_state",
            Self::ReceiverRevision => "receiver_revision",
            Self::ReceiverLatestStop => "receiver_latest_stop",
            Self::ReceiverActivationIdentity => "receiver_activation_identity",
            Self::ReceiverActivationSignature => "receiver_activation_signature",
            Self::CleanupState => "cleanup_state",
            Self::CleanupPublicKey => "cleanup_public_key",
            Self::CleanupSerial => "cleanup_serial",
            Self::CleanupStateLock => "cleanup_state_lock",
            Self::CleanupForwardOriginal => "cleanup_forward_original",
            Self::CleanupPreparedTerminal => "cleanup_prepared_terminal",
            Self::CleanupReplayShape => "cleanup_replay_shape",
            Self::CleanupPending => "cleanup_pending",
            Self::CleanupResponseDecode => "cleanup_response_decode",
            Self::CleanupResponseIdentity => "cleanup_response_identity",
            Self::CleanupResponseCompleted => "cleanup_response_completed",
            Self::CleanupResponseTerminal => "cleanup_response_terminal",
            Self::CleanupResponseGeneration => "cleanup_response_generation",
            Self::CleanupResponseStop => "cleanup_response_stop",
            Self::CleanupResponseSignature => "cleanup_response_signature",
            Self::ProviderSlot => "provider_slot",
            Self::ProviderEvidence => "provider_evidence",
            Self::ProviderDecode => "provider_decode",
            Self::BrokerPending => "broker_pending",
        }
    }
    #[cfg(test)]
    pub const ALL: [Self; 42] = [
        Self::Capability,
        Self::LocalSlot,
        Self::LocalState,
        Self::ActivationSlot,
        Self::ActivationFlight,
        Self::DispatchSlot,
        Self::ActivationReplaySlot,
        Self::CleanupReplaySlot,
        Self::InboundPending,
        Self::ReceiverRestoredHistory,
        Self::ReceiverActivationWithoutHistory,
        Self::ReceiverCompleteHistory,
        Self::ReceiverKey,
        Self::ReceiverResponseIdentity,
        Self::ReceiverSignature,
        Self::ReceiverEffect,
        Self::ReceiverReadbackDecode,
        Self::ReceiverReadbackBinding,
        Self::ReceiverState,
        Self::ReceiverRevision,
        Self::ReceiverLatestStop,
        Self::ReceiverActivationIdentity,
        Self::ReceiverActivationSignature,
        Self::CleanupState,
        Self::CleanupPublicKey,
        Self::CleanupSerial,
        Self::CleanupStateLock,
        Self::CleanupForwardOriginal,
        Self::CleanupPreparedTerminal,
        Self::CleanupReplayShape,
        Self::CleanupPending,
        Self::CleanupResponseDecode,
        Self::CleanupResponseIdentity,
        Self::CleanupResponseCompleted,
        Self::CleanupResponseTerminal,
        Self::CleanupResponseGeneration,
        Self::CleanupResponseStop,
        Self::CleanupResponseSignature,
        Self::ProviderSlot,
        Self::ProviderEvidence,
        Self::ProviderDecode,
        Self::BrokerPending,
    ];
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FailureReason {
    CallFailed,
    PredicateRejected,
}
impl FailureReason {
    pub fn name(self) -> &'static str {
        match self {
            Self::CallFailed => "call_failed",
            Self::PredicateRejected => "predicate_rejected",
        }
    }
}
/// One cursor follows the unchanged operation, including nested guards. Only
/// an actual returned Err invokes the observer, after owner locks have unwound.
pub(crate) struct Trace<'a> {
    point: Cell<(Guard, FailureReason)>,
    observe: &'a dyn Fn(Guard, FailureReason),
}
impl<'a> Trace<'a> {
    pub fn new(observe: &'a dyn Fn(Guard, FailureReason)) -> Self {
        Self {
            point: Cell::new((Guard::Capability, FailureReason::CallFailed)),
            observe,
        }
    }
    pub fn mark(&self, guard: Guard, reason: FailureReason) {
        self.point.set((guard, reason));
    }
    pub fn run<T, E>(&self, operation: impl FnOnce() -> Result<T, E>) -> Result<T, E> {
        let result = operation();
        if result.is_err() {
            let (guard, reason) = self.point.get();
            (self.observe)(guard, reason);
        }
        result
    }
}
