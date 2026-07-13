//! Closed CPU/data-only failure and recovery transitions used by release tests.

/// Failure families owned by the generic Quest media runtime.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MediaFailureCriterion {
    /// Accepted route becomes unavailable.
    RouteLoss,
    /// Receiver consumption falls behind without unbounded growth.
    SlowConsumer,
    /// Bounded queue reaches its configured pressure limit.
    QueuePressure,
    /// Codec provider rejects or terminates work.
    CodecFailure,
    /// First cleanup attempt fails and must be retried.
    CleanupFailure,
    /// Provider process exits and a fresh epoch is required.
    ProviderDeath,
    /// Native renderer process exits while holding app-local resources.
    NativeAppDeath,
    /// Spatial panel process exits while holding app-local resources.
    SpatialAppDeath,
}

/// Typed transition snapshot; no platform effect is performed by this model.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MediaFailureSnapshot {
    /// Stable reducer state name.
    pub observed_state: &'static str,
    /// Monotonic authority revision.
    pub authority_revision: u64,
    /// Provider-process epoch.
    pub provider_epoch: u64,
    /// Whether all resources are terminally released.
    pub cleanup_complete: bool,
}

/// Small fail-closed transition harness shared by unit and release adapters.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MediaFailureRecoveryHarness {
    criterion: MediaFailureCriterion,
    snapshot: MediaFailureSnapshot,
    injected: bool,
}

impl MediaFailureRecoveryHarness {
    /// Creates a ready, clean revision-one harness.
    #[must_use]
    pub const fn new(criterion: MediaFailureCriterion) -> Self {
        Self {
            criterion,
            snapshot: MediaFailureSnapshot {
                observed_state: "ready",
                authority_revision: 1,
                provider_epoch: 1,
                cleanup_complete: true,
            },
            injected: false,
        }
    }

    /// Returns the current transition evidence.
    #[must_use]
    pub const fn snapshot(&self) -> &MediaFailureSnapshot {
        &self.snapshot
    }

    /// Applies the exact selected damaged condition once.
    pub fn inject(&mut self) -> Result<&MediaFailureSnapshot, MediaFailureTransitionError> {
        if self.injected || self.snapshot.observed_state != "ready" {
            return Err(MediaFailureTransitionError::InvalidPhase);
        }
        self.injected = true;
        self.snapshot.authority_revision = 2;
        self.snapshot.cleanup_complete = false;
        self.snapshot.observed_state = match self.criterion {
            MediaFailureCriterion::RouteLoss => "route_unavailable",
            MediaFailureCriterion::SlowConsumer => "slow_consumer_observed",
            MediaFailureCriterion::QueuePressure => "queue_pressure_observed",
            MediaFailureCriterion::CodecFailure => "codec_failure_injected",
            MediaFailureCriterion::CleanupFailure => "cleanup_failure_injected",
            MediaFailureCriterion::ProviderDeath => "provider_terminated",
            MediaFailureCriterion::NativeAppDeath => "native_app_terminated",
            MediaFailureCriterion::SpatialAppDeath => "spatial_app_terminated",
        };
        Ok(&self.snapshot)
    }

    /// Applies the criterion-specific bounded recovery exactly once.
    pub fn recover(&mut self) -> Result<&MediaFailureSnapshot, MediaFailureTransitionError> {
        if !self.injected || self.snapshot.authority_revision != 2 {
            return Err(MediaFailureTransitionError::InvalidPhase);
        }
        self.snapshot.authority_revision = 3;
        self.snapshot.cleanup_complete = true;
        self.snapshot.observed_state = match self.criterion {
            MediaFailureCriterion::SlowConsumer | MediaFailureCriterion::QueuePressure => "bounded",
            MediaFailureCriterion::CodecFailure => "rejected",
            MediaFailureCriterion::ProviderDeath => {
                self.snapshot.provider_epoch += 1;
                "recovered_fresh_epoch"
            }
            MediaFailureCriterion::RouteLoss
            | MediaFailureCriterion::CleanupFailure
            | MediaFailureCriterion::NativeAppDeath
            | MediaFailureCriterion::SpatialAppDeath => "recovered",
        };
        Ok(&self.snapshot)
    }
}

/// Closed transition failure.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MediaFailureTransitionError {
    /// Injection or recovery was attempted outside its one valid phase.
    InvalidPhase,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn exercise(
        criterion: MediaFailureCriterion,
        failed: &'static str,
        recovered: &'static str,
        fresh_epoch: bool,
    ) {
        let mut harness = MediaFailureRecoveryHarness::new(criterion);
        assert_eq!(harness.snapshot().observed_state, "ready");
        assert!(harness.snapshot().cleanup_complete);
        assert_eq!(harness.inject().expect("inject").observed_state, failed);
        assert!(!harness.snapshot().cleanup_complete);
        assert_eq!(
            harness.recover().expect("recover").observed_state,
            recovered
        );
        assert!(harness.snapshot().cleanup_complete);
        assert_eq!(harness.snapshot().authority_revision, 3);
        assert_eq!(
            harness.snapshot().provider_epoch,
            if fresh_epoch { 2 } else { 1 }
        );
        assert_eq!(
            harness.recover(),
            Err(MediaFailureTransitionError::InvalidPhase)
        );
    }

    macro_rules! criterion_test {
        ($name:ident, $criterion:ident, $failed:literal, $recovered:literal, $fresh:literal) => {
            #[test]
            fn $name() {
                exercise(
                    MediaFailureCriterion::$criterion,
                    $failed,
                    $recovered,
                    $fresh,
                );
            }
        };
    }

    criterion_test!(
        corrected_release_route_loss,
        RouteLoss,
        "route_unavailable",
        "recovered",
        false
    );
    criterion_test!(
        corrected_release_slow_consumer,
        SlowConsumer,
        "slow_consumer_observed",
        "bounded",
        false
    );
    criterion_test!(
        corrected_release_queue_pressure,
        QueuePressure,
        "queue_pressure_observed",
        "bounded",
        false
    );
    criterion_test!(
        corrected_release_codec_failure,
        CodecFailure,
        "codec_failure_injected",
        "rejected",
        false
    );
    criterion_test!(
        corrected_release_cleanup_failure,
        CleanupFailure,
        "cleanup_failure_injected",
        "recovered",
        false
    );
    criterion_test!(
        corrected_release_provider_death,
        ProviderDeath,
        "provider_terminated",
        "recovered_fresh_epoch",
        true
    );
    criterion_test!(
        corrected_release_native_app_death,
        NativeAppDeath,
        "native_app_terminated",
        "recovered",
        false
    );
    criterion_test!(
        corrected_release_spatial_app_death,
        SpatialAppDeath,
        "spatial_app_terminated",
        "recovered",
        false
    );
}
