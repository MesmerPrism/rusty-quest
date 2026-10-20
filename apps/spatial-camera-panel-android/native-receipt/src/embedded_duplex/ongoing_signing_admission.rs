//! Borrowed actual Native owner admission. No caller JSON/boolean can select it.
use super::*;
use rusty_manifold_peer::ManifoldCommonLanReciprocalEd25519Context;

pub(super) enum Admission<'a> {
    Finite,
    SourceBoundOngoing(Permit<'a>),
}
pub(super) struct Permit<'a> {
    host: &'a Host,
    source: String,
    phase: Value,
}

pub(super) fn owner(host: &Host) -> Result<(), String> {
    host.capability.require_live()?;
    let p = process()
        .lock()
        .map_err(|_| "ongoing signing process owner poisoned")?;
    if p.closing
        || p.initializing
        || p.lease_integrity_failed
        || p.retired_fresh_owner_fenced
        || p.host_leases == 0
        || !p
            .host
            .as_ref()
            .is_some_and(|h| Arc::ptr_eq(&h.pair_state, &host.pair_state))
        || p.fresh_setup_request
            .as_ref()
            .is_some_and(|id| p.fresh_setup_stop_request.as_ref() == Some(id))
    {
        return Err("ongoing signing actual owner/source/Stop fence refused".into());
    }
    Ok(())
}
pub(super) fn selected(host: &Host) -> Result<bool, String> {
    // The actual facade getter refuses observer copies and requires the real
    // signed ongoing owner/gate when this new policy is selected.
    Ok(host.authority.selected_fresh_pair_setup_policy()?.is_some())
}
pub(super) fn select<'a>(
    host: &'a Host,
    context: &ManifoldCommonLanReciprocalEd25519Context,
    source: &str,
    now: u64,
) -> Result<Admission<'a>, String> {
    if !selected(host)? {
        return Ok(Admission::Finite);
    }
    owner(host)?;
    if host.authority.snapshot_json()? != source {
        return Err("ongoing signing current source changed".into());
    }
    let phase = pair_ceremony::ongoing_signing_binding(host, context, source, now)?;
    if context.issued_at_ms > now || context.expires_at_ms <= now {
        return Err("ongoing signing actual signed validity refused".into());
    }
    if now.saturating_sub(context.issued_at_ms) > 30_000 {
        super::estimated_warning::publish("ongoing-common-lan-context-age-estimate-elapsed sameOriginal=true signedExpiryStrict=true");
    }
    Ok(Admission::SourceBoundOngoing(Permit {
        host,
        source: source.into(),
        phase,
    }))
}
impl Admission<'_> {
    pub(super) fn context_age_limit(
        &self,
        context: &ManifoldCommonLanReciprocalEd25519Context,
    ) -> Result<u64, String> {
        match self {
            Self::Finite => Ok(30_000),
            // This is the ACTUAL signed interval, not a larger estimated age
            // constant or a TTL extension. The unchanged validator still checks
            // exact expires_at>now, future issue time and its original TTL cap.
            Self::SourceBoundOngoing(_) => context
                .expires_at_ms
                .checked_sub(context.issued_at_ms)
                .filter(|v| *v > 0)
                .ok_or_else(|| "ongoing signing actual interval invalid".into()),
        }
    }
    pub(super) fn revalidate(
        &self,
        context: &ManifoldCommonLanReciprocalEd25519Context,
    ) -> Result<(), String> {
        let Self::SourceBoundOngoing(p) = self else {
            return Ok(());
        };
        owner(p.host)?;
        let now = p.host.clock.now_ms()?;
        if context.issued_at_ms > now
            || context.expires_at_ms <= now
            || p.host.authority.snapshot_json()? != p.source
            || pair_ceremony::ongoing_signing_binding(p.host, context, &p.source, now)? != p.phase
        {
            return Err(
                "ongoing signing actual source/phase/floor or signed expiry changed".into(),
            );
        }
        Ok(())
    }
}
