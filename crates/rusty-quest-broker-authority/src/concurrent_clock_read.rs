//! Closed OS-clock validation and non-effect sampling for concurrent renewal.
//! No authority snapshot is changed by validation or resampling.

/// Finite rejection classes; none contains runtime or credential payloads.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConcurrentClockRejection {
    /// The provider has not been initialized.
    NotInitialized,
    /// The retained authority lock is poisoned.
    RuntimeLockPoisoned,
    /// The accepted clock is unhealthy.
    Unhealthy,
    /// The accepted clock is outside the Android system domain.
    ForeignDomain,
    /// Actual OS wall time moved backwards.
    WallReversed,
    /// Actual OS monotonic time moved backwards.
    MonotonicReversed,
    /// Actual wall and monotonic deltas are inconsistent.
    Discontinuity,
    /// The retained revoker has a foreign domain or epoch.
    RevokerIdentity,
    /// Actual clocks precede the retained revoker clock.
    RevokerReversed,
    /// The authority sequence cannot advance.
    SequenceExhausted,
    /// Only the measured OS-read bracket is too uncertain.
    ExcessiveReadUncertainty,
    /// Reading the embedding OS clocks failed.
    OsReadUnavailable,
    /// The OS sample bracket reversed or overflowed.
    SampleBounds,
}

impl ConcurrentClockRejection {
    /// Closed debug code, never a formatted underlying error.
    pub fn code(self) -> &'static str {
        match self {
            Self::NotInitialized => "not_initialized",
            Self::RuntimeLockPoisoned => "runtime_lock_poisoned",
            Self::Unhealthy => "unhealthy",
            Self::ForeignDomain => "foreign_domain",
            Self::WallReversed => "wall_reversed",
            Self::MonotonicReversed => "monotonic_reversed",
            Self::Discontinuity => "discontinuity",
            Self::RevokerIdentity => "revoker_identity",
            Self::RevokerReversed => "revoker_reversed",
            Self::SequenceExhausted => "sequence_exhausted",
            Self::ExcessiveReadUncertainty => "excessive_read_uncertainty",
            Self::OsReadUnavailable => "os_read_unavailable",
            Self::SampleBounds => "sample_bounds",
        }
    }
}

/// Safe numeric fields from one retained clock (no epoch identifiers).
#[derive(Clone, Copy, Debug)]
pub struct ConcurrentClockBounds {
    /// Whether accepted health is Healthy.
    pub healthy: bool,
    /// Whether the domain matches the required domain/epoch comparison.
    pub identity_matches: bool,
    /// Accepted wall milliseconds.
    pub wall_ms: i64,
    /// Accepted monotonic nanoseconds.
    pub monotonic_ns: u64,
    /// Accepted authority sequence.
    pub sequence: u64,
}

/// One actual embedding OS read, with its measured monotonic bracket.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ConcurrentClockSample {
    /// OS wall milliseconds read inside the bracket.
    pub wall_ms: i64,
    /// Monotonic nanoseconds immediately before the wall read.
    pub before_ns: u64,
    /// Monotonic nanoseconds immediately after the wall read.
    pub after_ns: u64,
}

impl ConcurrentClockSample {
    /// Preserve the original one-millisecond quantization allowance.
    pub fn uncertainty_ns(self) -> Result<u64, ConcurrentClockRejection> {
        self.after_ns
            .checked_sub(self.before_ns)
            .and_then(|v| v.checked_add(1_000_000))
            .ok_or(ConcurrentClockRejection::SampleBounds)
    }
}

/// Validate every original bound, returning only the next sequence.
/// Hard failures take precedence over uncertainty, so they cannot be retried.
pub fn validate_concurrent_clock(
    prior: ConcurrentClockBounds,
    revoker: Option<ConcurrentClockBounds>,
    wall_ms: i64,
    monotonic_ns: u64,
    uncertainty_ns: u64,
) -> Result<u64, ConcurrentClockRejection> {
    use ConcurrentClockRejection as R;
    if !prior.healthy {
        return Err(R::Unhealthy);
    }
    if !prior.identity_matches {
        return Err(R::ForeignDomain);
    }
    if wall_ms < prior.wall_ms {
        return Err(R::WallReversed);
    }
    if monotonic_ns < prior.monotonic_ns {
        return Err(R::MonotonicReversed);
    }
    let wall_delta = u64::try_from(i128::from(wall_ms) - i128::from(prior.wall_ms))
        .map_err(|_| R::Discontinuity)?;
    let mono_delta = (monotonic_ns - prior.monotonic_ns) / 1_000_000;
    if wall_delta.abs_diff(mono_delta) > 1_000 {
        return Err(R::Discontinuity);
    }
    let mut sequence = prior.sequence;
    if let Some(v) = revoker {
        if !v.identity_matches {
            return Err(R::RevokerIdentity);
        }
        if wall_ms < v.wall_ms || monotonic_ns < v.monotonic_ns {
            return Err(R::RevokerReversed);
        }
        sequence = sequence.max(v.sequence);
    }
    let next = sequence.checked_add(1).ok_or(R::SequenceExhausted)?;
    if uncertainty_ns > 10_000_000 {
        return Err(R::ExcessiveReadUncertainty);
    }
    Ok(next)
}

/// One closed observation of a sample and its actual owner validation.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ConcurrentClockObservation {
    /// Actual sample, absent if reading failed.
    pub sample: Option<ConcurrentClockSample>,
    /// Measured uncertainty, absent for malformed/unavailable samples.
    pub uncertainty_ns: Option<u64>,
    /// Owner rejection, absent for accepted validation.
    pub rejection: Option<ConcurrentClockRejection>,
}

/// Fresh-read at most three times, exclusively for measured uncertainty.
/// Each validator call is read-only; no accepted sequence is published here.
pub fn sample_concurrent_clock<T>(
    mut sample: impl FnMut() -> Result<ConcurrentClockSample, ConcurrentClockRejection>,
    mut validate: impl FnMut(ConcurrentClockSample, u64) -> Result<T, ConcurrentClockRejection>,
) -> (
    Result<T, ConcurrentClockRejection>,
    Vec<ConcurrentClockObservation>,
) {
    let mut observations = Vec::new();
    for attempt in 0..3 {
        let value = match sample() {
            Ok(value) => value,
            Err(error) => {
                observations.push(ConcurrentClockObservation {
                    sample: None,
                    uncertainty_ns: None,
                    rejection: Some(error),
                });
                return (Err(error), observations);
            }
        };
        let uncertainty = match value.uncertainty_ns() {
            Ok(value) => value,
            Err(error) => {
                observations.push(ConcurrentClockObservation {
                    sample: Some(value),
                    uncertainty_ns: None,
                    rejection: Some(error),
                });
                return (Err(error), observations);
            }
        };
        let result = validate(value, uncertainty);
        let rejection = result.as_ref().err().copied();
        observations.push(ConcurrentClockObservation {
            sample: Some(value),
            uncertainty_ns: Some(uncertainty),
            rejection,
        });
        if rejection != Some(ConcurrentClockRejection::ExcessiveReadUncertainty) || attempt == 2 {
            return (result, observations);
        }
    }
    unreachable!()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn prior() -> ConcurrentClockBounds {
        ConcurrentClockBounds {
            healthy: true,
            identity_matches: true,
            wall_ms: 1_000,
            monotonic_ns: 1_000_000_000,
            sequence: 7,
        }
    }
    fn narrow() -> ConcurrentClockSample {
        ConcurrentClockSample {
            wall_ms: 1_020,
            before_ns: 1_020_000_000,
            after_ns: 1_020_001_000,
        }
    }
    #[test]
    fn delayed_real_caller_resamples_without_publishing_authority() {
        let slow = ConcurrentClockSample {
            wall_ms: 1_000,
            before_ns: 1_000_000_000,
            after_ns: 1_020_000_000,
        };
        let mut samples = [slow, narrow()].into_iter();
        let (result, observations) = sample_concurrent_clock(
            || Ok(samples.next().unwrap()),
            |s, u| validate_concurrent_clock(prior(), None, s.wall_ms, s.after_ns, u),
        );
        assert_eq!(result, Ok(8));
        assert_eq!(observations.len(), 2);
        assert_eq!(
            observations[0].rejection,
            Some(ConcurrentClockRejection::ExcessiveReadUncertainty)
        );
        assert_eq!(observations[1].sample, Some(narrow()));
        assert_eq!(prior().sequence, 7);
    }
    #[test]
    fn uncertainty_exhausts_three_actual_reads_no_effect() {
        let mut reads = 0;
        let (result, observations) = sample_concurrent_clock(
            || {
                reads += 1;
                Ok(ConcurrentClockSample {
                    wall_ms: 1_000,
                    before_ns: 1_000_000_000,
                    after_ns: 1_010_000_000,
                })
            },
            |s, u| validate_concurrent_clock(prior(), None, s.wall_ms, s.after_ns, u),
        );
        assert_eq!(reads, 3);
        assert_eq!(observations.len(), 3);
        assert_eq!(
            result,
            Err(ConcurrentClockRejection::ExcessiveReadUncertainty)
        );
    }
    #[test]
    fn hard_rejections_are_not_hidden_by_wide_sample() {
        use ConcurrentClockRejection as R;
        let p = prior();
        for (p, r, w, m, expected) in [
            (
                ConcurrentClockBounds {
                    healthy: false,
                    ..p
                },
                None,
                1_020,
                1_020_000_000,
                R::Unhealthy,
            ),
            (
                ConcurrentClockBounds {
                    identity_matches: false,
                    ..p
                },
                None,
                1_020,
                1_020_000_000,
                R::ForeignDomain,
            ),
            (p, None, 999, 1_020_000_000, R::WallReversed),
            (p, None, 1_020, 999_000_000, R::MonotonicReversed),
            (p, None, 3_000, 1_020_000_000, R::Discontinuity),
            (
                p,
                Some(ConcurrentClockBounds {
                    identity_matches: false,
                    ..p
                }),
                1_020,
                1_020_000_000,
                R::RevokerIdentity,
            ),
            (
                p,
                Some(ConcurrentClockBounds {
                    wall_ms: 1_100,
                    ..p
                }),
                1_020,
                1_020_000_000,
                R::RevokerReversed,
            ),
            (
                ConcurrentClockBounds {
                    sequence: u64::MAX,
                    ..p
                },
                None,
                1_020,
                1_020_000_000,
                R::SequenceExhausted,
            ),
        ] {
            let mut calls = 0;
            let (result, obs) = sample_concurrent_clock(
                || {
                    calls += 1;
                    Ok(ConcurrentClockSample {
                        wall_ms: w,
                        before_ns: m - 20_000_000,
                        after_ns: m,
                    })
                },
                |s, u| validate_concurrent_clock(p, r, s.wall_ms, s.after_ns, u),
            );
            assert_eq!(result, Err(expected));
            assert_eq!(calls, 1);
            assert_eq!(obs.len(), 1);
        }
    }
    #[test]
    fn exact_uncertainty_and_sequence_boundaries_remain() {
        assert_eq!(
            validate_concurrent_clock(prior(), None, 1_000, 1_000_000_000, 10_000_000),
            Ok(8)
        );
        assert_eq!(
            validate_concurrent_clock(prior(), None, 1_000, 1_000_000_000, 10_000_001),
            Err(ConcurrentClockRejection::ExcessiveReadUncertainty)
        );
        assert_eq!(
            validate_concurrent_clock(
                prior(),
                Some(ConcurrentClockBounds {
                    sequence: 20,
                    ..prior()
                }),
                1_000,
                1_000_000_000,
                1_000_000
            ),
            Ok(21)
        );
    }
    #[test]
    fn unavailable_or_reversed_samples_never_resample() {
        let (r, obs) = sample_concurrent_clock::<u64>(
            || Err(ConcurrentClockRejection::OsReadUnavailable),
            |_, _| panic!(),
        );
        assert_eq!(r, Err(ConcurrentClockRejection::OsReadUnavailable));
        assert_eq!(obs.len(), 1);
        let (r, obs) = sample_concurrent_clock::<u64>(
            || {
                Ok(ConcurrentClockSample {
                    wall_ms: 1,
                    before_ns: 2,
                    after_ns: 1,
                })
            },
            |_, _| panic!(),
        );
        assert_eq!(r, Err(ConcurrentClockRejection::SampleBounds));
        assert_eq!(obs.len(), 1);
    }
}
