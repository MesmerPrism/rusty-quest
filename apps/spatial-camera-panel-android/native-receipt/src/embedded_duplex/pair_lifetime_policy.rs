//! Bounded pre-Start lifetime envelope for the adopted Common-LAN authority.

pub(crate) const CEREMONY_TTL_MS: u64 = 60_000;
pub(crate) const CONTEXT_TTL_MS: u64 = 240_000;
pub(crate) const PAIR_STATUS_TTL_MS: u64 = 300_000;
pub(crate) const PAIR_CREDENTIAL_TTL_MS: u64 = 300_000;
pub(crate) const ISSUE_BACKDATE_MS: u64 = 5_000;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn common_lan_lineage_outlives_ceremony_and_110_second_run() {
        // A context can be prepared at the start of the ceremony, and the
        // second host can still finish at the full ceremony deadline.
        let prime_at = 1_000_000u64;
        let context_at = prime_at;
        let pair_done_at = prime_at + CEREMONY_TTL_MS;
        let context_expires = context_at + CONTEXT_TTL_MS - ISSUE_BACKDATE_MS;
        let status_expires = prime_at + PAIR_STATUS_TTL_MS;
        let credential_expires = prime_at + PAIR_CREDENTIAL_TTL_MS;
        assert_eq!(CONTEXT_TTL_MS, 240_000);
        assert_eq!(CONTEXT_TTL_MS - ISSUE_BACKDATE_MS, 235_000);
        assert_eq!(context_expires - pair_done_at, 175_000);
        assert!(context_expires > pair_done_at + 110_000);
        assert!(status_expires > context_expires);
        assert!(credential_expires > context_expires);
    }

    #[test]
    fn every_in_deadline_context_remains_below_live_peer_expiries() {
        let prime_at = 1_000_000u64;
        for offset in [0, 1, CEREMONY_TTL_MS - 1, CEREMONY_TTL_MS] {
            let context_at = prime_at + offset;
            let context_expires = context_at + CONTEXT_TTL_MS - ISSUE_BACKDATE_MS;
            assert!(context_expires < prime_at + PAIR_STATUS_TTL_MS);
            assert!(context_expires < prime_at + PAIR_CREDENTIAL_TTL_MS);
        }
    }
}
