//! Bounded retry and replay guards used by the production signed renewal actor.
#[derive(Default)]
pub(crate) struct CycleLedger {
    completed: Vec<String>,
}
impl CycleLedger {
    pub(crate) fn require_fresh(&self, id: &str) -> Result<(), String> {
        if self.completed.iter().any(|old| old == id) {
            Err("completed signed renewal cycle cannot be replayed".into())
        } else {
            Ok(())
        }
    }
    pub(crate) fn complete(&mut self, id: &str) -> Result<(), String> {
        self.require_fresh(id)?;
        if self.completed.len() >= 32 {
            return Err("bounded signed renewal cycle history exhausted".into());
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
        ledger.require_fresh("accepted").unwrap();
        ledger.complete("accepted").unwrap();
        assert!(ledger.require_fresh("accepted").is_err());
        assert!(ledger.complete("accepted").is_err());
        ledger.require_fresh("next").unwrap();
    }
    #[test]
    fn history_does_not_evict_old_replay_guards() {
        let mut ledger = CycleLedger::default();
        for i in 0..32 {
            ledger.complete(&i.to_string()).unwrap();
        }
        assert!(ledger.complete("overflow").is_err());
        assert!(ledger.require_fresh("0").is_err());
    }
}
