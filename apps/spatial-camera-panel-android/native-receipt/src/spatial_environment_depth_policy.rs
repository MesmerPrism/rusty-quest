#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum EnvironmentDepthAcquireDisposition {
    Acquired,
    RetryNextEligibleFrame,
    RateLimitRetry,
    RecoverableCallOrder,
    DropFrame,
}

pub(crate) fn environment_depth_acquire_disposition(
    result: openxr_sys::Result,
) -> EnvironmentDepthAcquireDisposition {
    if result == openxr_sys::Result::SUCCESS {
        EnvironmentDepthAcquireDisposition::Acquired
    } else if result == openxr_sys::Result::ENVIRONMENT_DEPTH_NOT_AVAILABLE_META {
        EnvironmentDepthAcquireDisposition::RetryNextEligibleFrame
    } else if result == openxr_sys::Result::ERROR_TIME_INVALID {
        EnvironmentDepthAcquireDisposition::RateLimitRetry
    } else if result == openxr_sys::Result::ERROR_CALL_ORDER_INVALID {
        EnvironmentDepthAcquireDisposition::RecoverableCallOrder
    } else {
        EnvironmentDepthAcquireDisposition::DropFrame
    }
}

pub(crate) fn environment_depth_retry_not_before_request(current_request: u64) -> u64 {
    current_request.saturating_add(BOUNDED_RECOVERY_RETRY_INTERVAL_REQUESTS)
}

pub(crate) fn environment_depth_retry_rate_limited(
    current_request: u64,
    retry_not_before_request: u64,
    aggressive_retry: bool,
) -> bool {
    !aggressive_retry && current_request < retry_not_before_request
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn call_order_errors_are_recoverable_without_invalidating_last_valid_depth() {
        assert_eq!(
            EnvironmentDepthAcquireDisposition::Acquired,
            environment_depth_acquire_disposition(openxr_sys::Result::SUCCESS)
        );
        assert_eq!(
            EnvironmentDepthAcquireDisposition::RecoverableCallOrder,
            environment_depth_acquire_disposition(openxr_sys::Result::ERROR_CALL_ORDER_INVALID)
        );
        assert_eq!(
            EnvironmentDepthAcquireDisposition::RetryNextEligibleFrame,
            environment_depth_acquire_disposition(
                openxr_sys::Result::ENVIRONMENT_DEPTH_NOT_AVAILABLE_META
            )
        );
        assert_eq!(
            EnvironmentDepthAcquireDisposition::RateLimitRetry,
            environment_depth_acquire_disposition(openxr_sys::Result::ERROR_TIME_INVALID)
        );
        assert_eq!(
            EnvironmentDepthAcquireDisposition::DropFrame,
            environment_depth_acquire_disposition(openxr_sys::Result::ERROR_RUNTIME_FAILURE)
        );
    }

    #[test]
    fn bounded_recovery_skips_seven_following_frame_requests() {
        let retry_not_before = environment_depth_retry_not_before_request(10);
        assert_eq!(18, retry_not_before);
        for request in 11..18 {
            assert!(environment_depth_retry_rate_limited(
                request,
                retry_not_before,
                false,
            ));
        }
        assert!(!environment_depth_retry_rate_limited(
            18,
            retry_not_before,
            false
        ));
    }

    #[test]
    fn aggressive_recovery_bypasses_existing_backoff() {
        let retry_not_before = environment_depth_retry_not_before_request(10);
        for request in 11..18 {
            assert!(!environment_depth_retry_rate_limited(
                request,
                retry_not_before,
                true,
            ));
        }
    }
}
pub(crate) const BOUNDED_RECOVERY_RETRY_INTERVAL_REQUESTS: u64 = 8;
