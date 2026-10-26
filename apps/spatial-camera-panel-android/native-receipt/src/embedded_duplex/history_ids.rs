//! A remote signed context carries its own owner epoch. This format check
//! grants no authority: the issuing live owner still verifies its exact epoch,
//! revisions, signatures, expiry and replay state when accepting the context.

pub(crate) fn remote_correlation_matches(
    local_qualified: &str,
    remote: &str,
    suffix: &str,
) -> bool {
    if local_qualified == suffix {
        return remote == suffix;
    }
    fn qualified<'a>(value: &'a str, suffix: &str) -> Option<u64> {
        let rest = value.strip_prefix("history.")?;
        let (epoch, tail) = rest.split_once('.')?;
        let number = epoch.parse::<u64>().ok()?;
        (number > 0 && epoch == number.to_string() && tail == suffix).then_some(number)
    }
    qualified(local_qualified, suffix).is_some() && qualified(remote, suffix).is_some()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn finite_correlation_bytes_remain_exact() {
        assert!(remote_correlation_matches(
            "correlation.a",
            "correlation.a",
            "correlation.a"
        ));
        assert!(!remote_correlation_matches(
            "correlation.a",
            "history.1.correlation.a",
            "correlation.a"
        ));
    }
    #[test]
    fn distinct_actual_owner_epochs_are_not_conflated() {
        assert!(remote_correlation_matches(
            "history.3.correlation.a",
            "history.2.correlation.a",
            "correlation.a"
        ));
        for bad in [
            "correlation.a",
            "history.0.correlation.a",
            "history.02.correlation.a",
            "history.2.correlation.b",
            "history.18446744073709551616.correlation.a",
            "history.2.history.2.correlation.a",
        ] {
            assert!(!remote_correlation_matches(
                "history.3.correlation.a",
                bad,
                "correlation.a"
            ));
        }
        assert!(!remote_correlation_matches(
            "history.03.correlation.a",
            "history.2.correlation.a",
            "correlation.a"
        ));
    }
}
