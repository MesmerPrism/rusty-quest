//! Wait for actual local wall time before an authenticated context admission.
//! This does not widen a validator's future/expiry bounds or invent an effective
//! timestamp. The clock-difference bound remains strict. The development wait
//! estimate warns while the same live phase continues; its strict validator
//! still runs only after actual time catches up.
use std::time::{Duration, Instant};
pub(crate) const MAX_CATCHUP_MS: u64 = 2_000;

pub(crate) fn authenticated_context_time(
    issued_at_ms: u64,
    actual_now: impl FnMut() -> Result<u64, String>,
    current: impl FnMut() -> Result<(), String>,
) -> Result<u64, String> {
    authenticated_context_time_with_wait_policy(issued_at_ms, actual_now, current, false)
}

pub(crate) fn authenticated_context_time_with_wait_policy(
    issued_at_ms: u64,
    mut actual_now: impl FnMut() -> Result<u64, String>,
    mut current: impl FnMut() -> Result<(), String>,
    retained: bool,
) -> Result<u64, String> {
    let started = Instant::now();
    poll_with_wait_policy(
        issued_at_ms,
        &mut actual_now,
        &mut current,
        || started.elapsed().as_millis(),
        || std::thread::sleep(Duration::from_millis(5)),
        retained,
    )
}

fn poll(
    issued: u64,
    actual_now: impl FnMut() -> Result<u64, String>,
    current: impl FnMut() -> Result<(), String>,
    elapsed_ms: impl FnMut() -> u128,
    pause: impl FnMut(),
) -> Result<u64, String> {
    poll_with_wait_policy(issued, actual_now, current, elapsed_ms, pause, false)
}

fn poll_with_wait_policy(
    issued: u64,
    mut actual_now: impl FnMut() -> Result<u64, String>,
    mut current: impl FnMut() -> Result<(), String>,
    mut elapsed_ms: impl FnMut() -> u128,
    mut pause: impl FnMut(),
    retained: bool,
) -> Result<u64, String> {
    let mut previous = None;
    let mut warned = false;
    loop {
        if !retained && previous.is_some() && elapsed_ms() > u128::from(MAX_CATCHUP_MS) {
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
        if !retained && elapsed_ms() >= u128::from(MAX_CATCHUP_MS) {
            return Err("authenticated preparation clock did not catch up".into());
        }
        if retained && !warned && elapsed_ms() >= u128::from(MAX_CATCHUP_MS) {
            eprintln!(
                "authenticated preparation clock wait estimate exceeded; same phase retained"
            );
            warned = true;
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
    fn development_wait_estimate_never_fabricates_time_or_loses_actual_stop() {
        let now = Cell::new(100_000);
        let elapsed = Cell::new(0u128);
        assert_eq!(
            poll_with_wait_policy(
                101_000,
                || Ok(now.get()),
                || Ok(()),
                || elapsed.get(),
                || {
                    now.set(now.get() + 100);
                    elapsed.set(elapsed.get() + 1000);
                },
                true
            )
            .unwrap(),
            101_000
        );
        assert!(elapsed.get() > u128::from(MAX_CATCHUP_MS));
        let calls = Cell::new(0);
        assert_eq!(
            poll_with_wait_policy(
                101_000,
                || Ok(100_000),
                || {
                    calls.set(calls.get() + 1);
                    if calls.get() > 3 {
                        Err("actual desired Stop".into())
                    } else {
                        Ok(())
                    }
                },
                || 9_000,
                || {},
                true
            )
            .unwrap_err(),
            "actual desired Stop"
        );
        assert!(poll_with_wait_policy(
            102_001,
            || Ok(100_000),
            || Ok(()),
            || 9_000,
            || panic!("must reject real clock difference"),
            true
        )
        .is_err());
    }

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
