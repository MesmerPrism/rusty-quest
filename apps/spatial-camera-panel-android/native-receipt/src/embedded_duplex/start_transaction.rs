//! Fail-closed phase guard for an app-owned duplex Start transaction.
//!
//! The owner must durably record an `Attempted` phase before its corresponding
//! external call. A returned error never advances the phase by inference.

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum StartPhase {
    Prepared,
    AdmissionAttempted,
    Admitted,
    DecisionAttempted,
    PendingStart,
    RouteAttempted,
    RouteCurrent,
    OwnerAttempted,
    Active,
    AbortAttempted,
    StopAttempted,
    CleanupPending,
    Terminal,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RecoveryAction {
    ReconcileAdmission,
    ReconcileDecision,
    AbortPendingStart,
    ReconcileRouteThenAbort,
    ReconcileOwnersThenStopOrAbort,
    StopActive,
    ResumeAbort,
    ResumeStop,
    CompleteCleanup,
    None,
}

/// Serializable owner checkpoint. The complete, exact inputs and receipts are
/// retained by the enclosing journal; these digests prevent a phase marker
/// from being rebound to another transaction or receipt after restoration.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct StartCheckpoint {
    pub(crate) phase: StartPhase,
    pub(crate) revision: u64,
    pub(crate) lineage_sha256: String,
    pub(crate) last_verified_receipt_sha256: Option<String>,
}

impl StartCheckpoint {
    pub(crate) fn prepared(lineage_sha256: String) -> Result<Self, &'static str> {
        if !sha256(&lineage_sha256) {
            return Err("Start lineage digest invalid");
        }
        Ok(Self {
            phase: StartPhase::Prepared,
            revision: 1,
            lineage_sha256,
            last_verified_receipt_sha256: None,
        })
    }

    /// The caller persists the returned checkpoint before an attempted call.
    /// A verified phase requires a digest of the exact checked owner receipt.
    pub(crate) fn advance(
        &self,
        next: StartPhase,
        verified_receipt_sha256: Option<String>,
    ) -> Result<Self, &'static str> {
        if !self.phase.allows(next) {
            return Err("Start phase transition invalid");
        }
        let requires_receipt = matches!(
            next,
            StartPhase::Admitted
                | StartPhase::PendingStart
                | StartPhase::RouteCurrent
                | StartPhase::Active
                | StartPhase::CleanupPending
                | StartPhase::Terminal
        );
        if requires_receipt != verified_receipt_sha256.is_some()
            || verified_receipt_sha256
                .as_deref()
                .is_some_and(|value| !sha256(value))
        {
            return Err("Start receipt digest invalid");
        }
        Ok(Self {
            phase: next,
            revision: self
                .revision
                .checked_add(1)
                .ok_or("Start revision exhausted")?,
            lineage_sha256: self.lineage_sha256.clone(),
            last_verified_receipt_sha256: verified_receipt_sha256
                .or_else(|| self.last_verified_receipt_sha256.clone()),
        })
    }

    /// A restarted process must join this checkpoint to the exact restored
    /// authority lineage. A fresh provider epoch cannot inherit its actions.
    pub(crate) fn recovery_for_lineage(
        &self,
        expected_lineage_sha256: &str,
    ) -> Result<RecoveryAction, &'static str> {
        if !sha256(expected_lineage_sha256)
            || self.lineage_sha256 != expected_lineage_sha256
            || self.revision == 0
            || self
                .last_verified_receipt_sha256
                .as_deref()
                .is_some_and(|value| !sha256(value))
            || (matches!(
                self.phase,
                StartPhase::Admitted
                    | StartPhase::DecisionAttempted
                    | StartPhase::PendingStart
                    | StartPhase::RouteAttempted
                    | StartPhase::RouteCurrent
                    | StartPhase::OwnerAttempted
                    | StartPhase::Active
                    | StartPhase::AbortAttempted
                    | StartPhase::StopAttempted
                    | StartPhase::CleanupPending
                    | StartPhase::Terminal
            ) && self.last_verified_receipt_sha256.is_none())
        {
            return Err("Start recovery lineage invalid");
        }
        Ok(self.phase.recovery())
    }
}

fn sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

impl StartPhase {
    /// A crash or ambiguous response never means that a mutation failed.
    pub(crate) const fn recovery(self) -> RecoveryAction {
        match self {
            Self::Prepared | Self::AdmissionAttempted | Self::Admitted => {
                RecoveryAction::ReconcileAdmission
            }
            Self::DecisionAttempted => RecoveryAction::ReconcileDecision,
            Self::PendingStart => RecoveryAction::AbortPendingStart,
            Self::RouteAttempted => RecoveryAction::ReconcileRouteThenAbort,
            Self::RouteCurrent => RecoveryAction::AbortPendingStart,
            Self::OwnerAttempted => RecoveryAction::ReconcileOwnersThenStopOrAbort,
            Self::Active => RecoveryAction::StopActive,
            Self::AbortAttempted => RecoveryAction::ResumeAbort,
            Self::StopAttempted => RecoveryAction::ResumeStop,
            Self::CleanupPending => RecoveryAction::CompleteCleanup,
            Self::Terminal => RecoveryAction::None,
        }
    }

    /// A transition is allowed only after the owner has verified the exact
    /// receipt and durable snapshot for the preceding attempted operation.
    pub(crate) const fn allows(self, next: Self) -> bool {
        matches!(
            (self, next),
            (Self::Prepared, Self::AdmissionAttempted)
                | (Self::AdmissionAttempted, Self::Admitted)
                | (Self::Admitted, Self::DecisionAttempted)
                | (Self::DecisionAttempted, Self::PendingStart)
                | (Self::PendingStart, Self::RouteAttempted)
                | (Self::RouteAttempted, Self::RouteCurrent)
                | (Self::RouteCurrent, Self::OwnerAttempted)
                | (Self::OwnerAttempted, Self::Active)
                | (Self::PendingStart, Self::AbortAttempted)
                | (Self::RouteAttempted, Self::AbortAttempted)
                | (Self::RouteCurrent, Self::AbortAttempted)
                | (Self::OwnerAttempted, Self::AbortAttempted)
                | (Self::Active, Self::StopAttempted)
                | (Self::AbortAttempted, Self::CleanupPending)
                | (Self::StopAttempted, Self::CleanupPending)
                | (Self::CleanupPending, Self::Terminal)
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_uncertain_boundary_requires_reconciliation_or_cleanup() {
        let cases = [
            (
                StartPhase::AdmissionAttempted,
                RecoveryAction::ReconcileAdmission,
            ),
            (
                StartPhase::DecisionAttempted,
                RecoveryAction::ReconcileDecision,
            ),
            (StartPhase::PendingStart, RecoveryAction::AbortPendingStart),
            (
                StartPhase::RouteAttempted,
                RecoveryAction::ReconcileRouteThenAbort,
            ),
            (StartPhase::RouteCurrent, RecoveryAction::AbortPendingStart),
            (
                StartPhase::OwnerAttempted,
                RecoveryAction::ReconcileOwnersThenStopOrAbort,
            ),
            (StartPhase::Active, RecoveryAction::StopActive),
            (StartPhase::AbortAttempted, RecoveryAction::ResumeAbort),
            (StartPhase::StopAttempted, RecoveryAction::ResumeStop),
            (StartPhase::CleanupPending, RecoveryAction::CompleteCleanup),
        ];
        for (phase, expected) in cases {
            assert_eq!(phase.recovery(), expected, "{phase:?}");
            assert_ne!(phase.recovery(), RecoveryAction::None);
        }
        assert_eq!(StartPhase::Terminal.recovery(), RecoveryAction::None);
    }

    #[test]
    fn route_cannot_precede_pending_media_decision_or_skip_owner_attempt() {
        assert!(!StartPhase::Admitted.allows(StartPhase::RouteAttempted));
        assert!(!StartPhase::DecisionAttempted.allows(StartPhase::RouteAttempted));
        assert!(!StartPhase::RouteCurrent.allows(StartPhase::Active));
        assert!(StartPhase::PendingStart.allows(StartPhase::RouteAttempted));
        assert!(StartPhase::RouteCurrent.allows(StartPhase::OwnerAttempted));
    }

    #[test]
    fn attempted_effects_cannot_become_terminal_without_cleanup() {
        for phase in [
            StartPhase::DecisionAttempted,
            StartPhase::PendingStart,
            StartPhase::RouteAttempted,
            StartPhase::RouteCurrent,
            StartPhase::OwnerAttempted,
            StartPhase::Active,
            StartPhase::AbortAttempted,
            StartPhase::StopAttempted,
        ] {
            assert!(!phase.allows(StartPhase::Terminal), "{phase:?}");
        }
        assert!(StartPhase::CleanupPending.allows(StartPhase::Terminal));
    }

    #[test]
    fn write_ahead_attempt_and_checked_receipt_cannot_be_skipped_or_replayed() {
        let digest = "a".repeat(64);
        let prepared = StartCheckpoint::prepared(digest.clone()).unwrap();
        assert!(prepared
            .advance(StartPhase::Admitted, Some(digest.clone()))
            .is_err());
        let attempted = prepared
            .advance(StartPhase::AdmissionAttempted, None)
            .unwrap();
        assert_eq!(
            attempted.phase.recovery(),
            RecoveryAction::ReconcileAdmission
        );
        assert_eq!(attempted.revision, 2);
        assert!(attempted.advance(StartPhase::Admitted, None).is_err());
        let admitted = attempted
            .advance(StartPhase::Admitted, Some(digest.clone()))
            .unwrap();
        assert_eq!(admitted.last_verified_receipt_sha256, Some(digest));
        assert!(admitted
            .advance(StartPhase::Admitted, Some("b".repeat(64)))
            .is_err());
        assert!(admitted
            .advance(StartPhase::Terminal, Some("b".repeat(64)))
            .is_err());
    }

    #[test]
    fn stale_epoch_lineage_never_recovers_an_old_action() {
        let old = "a".repeat(64);
        let fresh = "b".repeat(64);
        let pending = StartCheckpoint::prepared(old.clone())
            .unwrap()
            .advance(StartPhase::AdmissionAttempted, None)
            .unwrap()
            .advance(StartPhase::Admitted, Some("c".repeat(64)))
            .unwrap()
            .advance(StartPhase::DecisionAttempted, None)
            .unwrap()
            .advance(StartPhase::PendingStart, Some("d".repeat(64)))
            .unwrap();
        assert_eq!(
            pending.recovery_for_lineage(&old),
            Ok(RecoveryAction::AbortPendingStart)
        );
        assert_eq!(
            pending.recovery_for_lineage(&fresh),
            Err("Start recovery lineage invalid")
        );
        let mut damaged = pending;
        damaged.last_verified_receipt_sha256 = None;
        assert!(damaged.recovery_for_lineage(&old).is_err());
    }
}
