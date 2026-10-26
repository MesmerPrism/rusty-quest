//! Bounded retry and replay guards used by the production signed renewal actor.
use super::super::ceremony_id::{self, CeremonyId};
#[derive(Default, serde::Serialize)]
pub(crate) struct CycleLedger {
    completed: Vec<String>,
    ongoing_next: Option<u64>,
    abandoned: Vec<String>,
}
impl CycleLedger {
    pub(crate) fn enable_ongoing(&mut self) -> Result<(), String> {
        if self.ongoing_next.is_some() {
            return Ok(());
        }
        if !self.completed.is_empty() {
            return Err("continuation must be selected before completed cycles".into());
        }
        self.ongoing_next = Some(1);
        Ok(())
    }
    pub(crate) fn fresh_id(&self, nonce: &str) -> Result<String, String> {
        let id = match self.ongoing_next {
            Some(next) => ceremony_id::continuation(next, nonce)?,
            None => ceremony_id::finite(nonce)?,
        };
        self.require_fresh(&id)?;
        Ok(id)
    }
    pub(crate) fn abandon(&mut self, id: &str) -> Result<(), String> {
        if self.ongoing_next.is_none() {
            return self.complete(id);
        }
        self.require_fresh(id)?;
        if self.abandoned.len() >= 8 {
            return Err("too many unresolved continuation contenders".into());
        }
        self.abandoned.push(id.to_owned());
        Ok(())
    }
    pub(crate) fn require_fresh(&self, id: &str) -> Result<(), String> {
        let parsed = ceremony_id::parse(id)?;
        match (self.ongoing_next, parsed) {
            (Some(next), CeremonyId::Continuation { ordinal, .. }) => {
                // Refuse before any effect if the accepted cycle could not commit a successor.
                next.checked_add(1)
                    .ok_or("continuation cycle ordinal exhausted")?;
                if ordinal != next || self.abandoned.iter().any(|old| old == id) {
                    return Err("stale, skipped or abandoned continuation cycle".into());
                }
                Ok(())
            }
            (Some(_), CeremonyId::Finite { .. }) => {
                Err("continuation cycle namespace required".into())
            }
            (None, CeremonyId::Continuation { .. }) => {
                Err("continuation cycle requires the selected owner policy".into())
            }
            (None, CeremonyId::Finite { .. }) => {
                if self.completed.iter().any(|old| old == id) {
                    Err("completed signed renewal cycle cannot be replayed".into())
                } else {
                    Ok(())
                }
            }
        }
    }
    pub(crate) fn require_completable(&self, id: &str) -> Result<(), String> {
        self.require_fresh(id)?;
        if self.ongoing_next.is_none() && self.completed.len() >= 32 {
            return Err("bounded signed renewal cycle history exhausted".into());
        }
        Ok(())
    }
    pub(crate) fn complete(&mut self, id: &str) -> Result<(), String> {
        self.require_completable(id)?;
        if let Some(next) = self.ongoing_next {
            self.ongoing_next = Some(
                next.checked_add(1)
                    .ok_or("continuation cycle ordinal exhausted")?,
            );
            self.abandoned.clear();
            return Ok(());
        }
        self.completed.push(id.to_owned());
        Ok(())
    }
}
pub(crate) fn cached_reply<'a, T: PartialEq>(
    entries: &'a [(String, T, &'static str, T)],
    key: &str,
    request: &T,
) -> Result<Option<(&'static str, &'a T)>, String> {
    match entries.iter().find(|(old, _, _, _)| old == key) {
        Some((_, accepted, kind, payload)) if accepted == request => Ok(Some((*kind, payload))),
        Some(_) => Err("renewal replay payload differs from retained exact request".into()),
        None => Ok(None),
    }
}
pub(crate) fn remember_reply<T: PartialEq>(
    entries: &mut Vec<(String, T, &'static str, T)>,
    key: String,
    request: T,
    kind: &'static str,
    payload: T,
) -> Result<(), String> {
    if cached_reply(entries, &key, &request)?.is_some() {
        return Err("renewal reply already retained".into());
    }
    if entries.len() >= 8 {
        return Err("bounded renewal reply capacity exhausted".into());
    }
    entries.push((key, request, kind, payload));
    Ok(())
}
pub(crate) fn bounded_expiry(
    now: u64,
    expires: u64,
    ttl: u64,
    existing_signed_clock_skew: u64,
) -> Result<(), String> {
    let upper = now
        .checked_add(ttl)
        .and_then(|v| v.checked_add(existing_signed_clock_skew))
        .ok_or("signed renewal expiry overflow")?;
    if expires <= now || expires > upper {
        return Err("signed renewal expiry exceeds existing bounded time policy".into());
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn explicit_continuation_has_bounded_replay_fence_past_sixty_four() {
        let mut ledger = CycleLedger::default();
        ledger.enable_ongoing().unwrap();
        let first = ledger.fresh_id(&"ab".repeat(32)).unwrap();
        for cycle in 1..=80 {
            let id = ledger.fresh_id(&format!("{cycle:064x}")).unwrap();
            assert!(ledger.require_fresh(&id).is_ok());
            ledger.complete(&id).unwrap();
            assert!(ledger.require_fresh(&id).is_err());
            assert!(ledger.completed.is_empty());
            assert!(ledger.abandoned.is_empty());
        }
        assert!(ledger.require_fresh(&first).is_err());
        assert!(ledger
            .require_fresh(&format!(
                "ceremony.duplex.continuation.82.{}",
                "ab".repeat(32)
            ))
            .is_err());
        assert!(ledger
            .require_fresh(&format!(
                "ceremony.duplex.continuation.081.{}",
                "ab".repeat(32)
            ))
            .is_err());
        assert!(ledger.require_fresh("ceremony.duplex.old-finite").is_err());
    }
    #[test]
    fn unresolved_contender_is_bounded_and_not_an_accepted_cycle() {
        let mut ledger = CycleLedger::default();
        ledger.enable_ongoing().unwrap();
        let cancelled = ledger.fresh_id(&"ab".repeat(32)).unwrap();
        ledger.abandon(&cancelled).unwrap();
        assert!(ledger.require_fresh(&cancelled).is_err());
        assert_eq!(ledger.ongoing_next, Some(1));
        let accepted = ledger.fresh_id(&"cd".repeat(32)).unwrap();
        ledger.complete(&accepted).unwrap();
        assert_eq!(ledger.ongoing_next, Some(2));
        assert!(ledger.require_fresh(&cancelled).is_err());
        assert!(ledger.abandoned.is_empty());
    }
    #[test]
    fn finite_history_cannot_be_relabelled_as_continuation() {
        let mut ledger = CycleLedger::default();
        assert!(ledger
            .require_fresh(&format!(
                "ceremony.duplex.continuation.1.{}",
                "ab".repeat(32)
            ))
            .is_err());
        let original = ceremony_id::finite(&"ab".repeat(32)).unwrap();
        ledger.complete(&original).unwrap();
        assert!(ledger.enable_ongoing().is_err());
        assert!(ledger.require_fresh(&original).is_err());
    }
    #[test]
    fn remote_receipt_accepts_only_existing_signed_clock_skew() {
        assert!(bounded_expiry(1_000_000, 1_305_000, 300_000, 5_000).is_ok());
        assert!(bounded_expiry(1_000_000, 1_305_001, 300_000, 5_000).is_err());
        assert!(bounded_expiry(1_000_000, 1_300_001, 300_000, 0).is_err());
    }
    #[test]
    fn expired_receipt_and_time_overflow_never_refresh() {
        assert!(bounded_expiry(1_000_000, 1_000_000, 300_000, 5_000).is_err());
        assert!(bounded_expiry(u64::MAX - 1, u64::MAX, 300_000, 5_000).is_err());
    }
    #[test]
    fn pending_retry_returns_exact_retained_response() {
        let mut rows = vec![];
        remember_reply(
            &mut rows,
            "credential.hello".into(),
            "actual-old-session-observation".to_owned(),
            "reply",
            "actual-receipt".to_owned(),
        )
        .unwrap();
        assert_eq!(
            cached_reply(
                &rows,
                "credential.hello",
                &"actual-old-session-observation".into()
            )
            .unwrap(),
            Some(("reply", &"actual-receipt".to_owned()))
        );
        assert_eq!(rows.len(), 1);
    }
    #[test]
    fn changed_observation_is_not_a_retry() {
        let mut rows = vec![];
        remember_reply(
            &mut rows,
            "credential.hello".into(),
            "observed-at-10",
            "reply",
            "receipt",
        )
        .unwrap();
        assert!(cached_reply(&rows, "credential.hello", &"observed-at-11").is_err());
        assert_eq!(rows.len(), 1);
    }
    #[test]
    fn phase_caches_are_independent() {
        let mut rows = vec![];
        remember_reply(
            &mut rows,
            "credential.hello".into(),
            "nonce-A",
            "reply",
            "receipt",
        )
        .unwrap();
        assert!(cached_reply(&rows, "session.hello", &"nonce-B")
            .unwrap()
            .is_none());
    }
    #[test]
    fn only_eight_actual_replies_can_be_retained() {
        let mut rows = vec![];
        for i in 0..8 {
            remember_reply(&mut rows, i.to_string(), i, "reply", i + 10).unwrap();
        }
        assert!(remember_reply(&mut rows, "ninth".into(), 9, "reply", 19).is_err());
        assert_eq!(rows.len(), 8);
        assert_eq!(cached_reply(&rows, "0", &0).unwrap(), Some(("reply", &10)));
    }
    #[test]
    fn completed_cycle_is_rejected_after_state_reset() {
        let mut ledger = CycleLedger::default();
        let accepted = ceremony_id::finite(&"ab".repeat(32)).unwrap();
        let next = ceremony_id::finite(&"cd".repeat(32)).unwrap();
        ledger.require_fresh(&accepted).unwrap();
        ledger.complete(&accepted).unwrap();
        assert!(ledger.require_fresh(&accepted).is_err());
        assert!(ledger.complete(&accepted).is_err());
        ledger.require_fresh(&next).unwrap();
    }
    #[test]
    fn history_does_not_evict_old_replay_guards() {
        let mut ledger = CycleLedger::default();
        for i in 0..32 {
            ledger
                .complete(&ceremony_id::finite(&format!("{i:064x}")).unwrap())
                .unwrap();
        }
        assert!(ledger
            .complete(&ceremony_id::finite(&"ff".repeat(32)).unwrap())
            .is_err());
        assert!(ledger
            .require_fresh(&ceremony_id::finite(&format!("{:064x}", 0)).unwrap())
            .is_err());
    }
}

#[cfg(test)]
mod canonical_ledger_controls {
    use super::*;
    #[test]
    fn syntactic_validity_never_enables_ongoing_policy() {
        let mut finite = CycleLedger::default();
        let id = ceremony_id::continuation(1, &"ab".repeat(32)).unwrap();
        assert!(finite.require_fresh(&id).is_err());
        assert!(finite.complete(&id).is_err());
        let original = finite.fresh_id(&"cd".repeat(32)).unwrap();
        finite.complete(&original).unwrap();
        assert!(finite.enable_ongoing().is_err());
        assert_eq!(finite.completed, vec![original]);
    }
    #[test]
    fn final_ordinal_refuses_before_cycle_effects_and_never_wraps_to_finite() {
        let mut ledger = CycleLedger::default();
        ledger.enable_ongoing().unwrap();
        ledger.ongoing_next = Some(u64::MAX - 1);
        let id = ledger.fresh_id(&"ab".repeat(32)).unwrap();
        ledger.complete(&id).unwrap();
        assert_eq!(ledger.ongoing_next, Some(u64::MAX));
        assert!(ledger.fresh_id(&"cd".repeat(32)).is_err());
        assert!(ledger
            .complete(&ceremony_id::continuation(u64::MAX, &"cd".repeat(32)).unwrap())
            .is_err());
        assert_eq!(ledger.ongoing_next, Some(u64::MAX));
        assert!(ledger.completed.is_empty());
    }
}
