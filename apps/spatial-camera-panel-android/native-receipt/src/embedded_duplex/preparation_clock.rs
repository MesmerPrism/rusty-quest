//! Wait for actual local wall time before an authenticated context admission.
//! This does not widen a validator's future/expiry bounds or invent an effective
//! timestamp. The two-second wait is bounded like the existing owner-dispatch
//! clock-skew envelope; the original strict validator still runs afterwards.
use std::time::{Duration, Instant};
pub(crate) const MAX_CATCHUP_MS: u64 = 2_000;

pub(crate) fn authenticated_context_time(
    issued_at_ms: u64,
    mut actual_now: impl FnMut() -> Result<u64, String>,
    mut current: impl FnMut() -> Result<(), String>,
) -> Result<u64, String> {
    let started = Instant::now();
    poll(
        issued_at_ms,
        &mut actual_now,
        &mut current,
        || started.elapsed().as_millis(),
        || std::thread::sleep(Duration::from_millis(5)),
    )
}

fn poll(
    issued: u64,
    mut actual_now: impl FnMut() -> Result<u64, String>,
    mut current: impl FnMut() -> Result<(), String>,
    mut elapsed_ms: impl FnMut() -> u128,
    mut pause: impl FnMut(),
) -> Result<u64, String> {
    let mut previous = None;
    loop {
        if previous.is_some() && elapsed_ms() > u128::from(MAX_CATCHUP_MS) {
            return Err("authenticated preparation clock did not catch up".into());
        }
        current()?;
        let now = actual_now()?;
        if previous.is_some_and(|old| now < old) {
            return Err("authenticated preparation clock moved backwards".into());
        }
        previous = Some(now);
        if issued <= now {
            return Ok(now);
        }
        if issued - now > MAX_CATCHUP_MS {
            return Err("authenticated preparation clock difference exceeds bound".into());
        }
        if elapsed_ms() >= u128::from(MAX_CATCHUP_MS) {
            return Err("authenticated preparation clock did not catch up".into());
        }
        // No process, phase, source or owner mutex is held across this pause.
        pause();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    #[test]
    fn actual_one_second_catchup_preserves_the_timestamp_and_strict_future_check() {
        let now = Cell::new(100_000);
        let elapsed = Cell::new(0u128);
        let checks = Cell::new(0);
        let admitted = poll(
            101_000,
            || Ok(now.get()),
            || {
                checks.set(checks.get() + 1);
                Ok(())
            },
            || elapsed.get(),
            || {
                now.set(now.get() + 100);
                elapsed.set(elapsed.get() + 100)
            },
        )
        .unwrap();
        assert_eq!(admitted, 101_000);
        assert!(101_000 <= admitted);
        assert_eq!(checks.get(), 11);
    }
    #[test]
    fn excessive_frozen_backwards_cancelled_and_failed_clock_refuse_without_effects() {
        assert!(poll(
            102_001,
            || Ok(100_000),
            || Ok(()),
            || 0,
            || panic!("must not sleep")
        )
        .is_err());
        let elapsed = Cell::new(0u128);
        assert!(poll(
            101_000,
            || Ok(100_000),
            || Ok(()),
            || elapsed.get(),
            || elapsed.set(elapsed.get() + 100)
        )
        .unwrap_err()
        .contains("did not catch up"));
        let now = Cell::new(100_000);
        assert!(poll(
            101_000,
            || Ok(now.get()),
            || Ok(()),
            || 0,
            || now.set(99_999)
        )
        .unwrap_err()
        .contains("backwards"));
        let calls = Cell::new(0);
        assert_eq!(
            poll(
                101_000,
                || Ok(100_000),
                || {
                    calls.set(calls.get() + 1);
                    if calls.get() == 2 {
                        Err("actual desired Stop".into())
                    } else {
                        Ok(())
                    }
                },
                || 0,
                || {}
            )
            .unwrap_err(),
            "actual desired Stop"
        );
        assert_eq!(
            poll(
                101_000,
                || Err("actual clock failure".into()),
                || Ok(()),
                || 0,
                || panic!()
            )
            .unwrap_err(),
            "actual clock failure"
        );
    }
    #[test]
    fn exact_bound_waits_but_does_not_extend_any_expiry() {
        let now = Cell::new(100_000);
        let elapsed = Cell::new(0u128);
        let actual = poll(
            102_000,
            || Ok(now.get()),
            || Ok(()),
            || elapsed.get(),
            || {
                now.set(102_000);
                elapsed.set(1)
            },
        )
        .unwrap();
        assert_eq!(actual, 102_000);
        let unchanged_expiry = 101_999;
        assert!(unchanged_expiry <= actual);
    }
}
