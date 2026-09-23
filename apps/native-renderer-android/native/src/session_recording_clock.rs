//! Clock-domain types for app-private experiment recordings.
//!
//! Recording code must not assume that Android elapsed realtime, Java
//! `System.nanoTime`, Polar sensor time, OpenXR time, and Unix time share an
//! epoch.  A session owns one explicit UTC/monotonic anchor and retains the
//! source clock for every observation.

const SESSION_KEY_PREFIX: &str = "utc-ns-";
const SESSION_KEY_DIGITS: usize = 20;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct MonotonicNanos(u64);

impl MonotonicNanos {
    pub(crate) const fn new(value: u64) -> Self {
        Self(value)
    }

    pub(crate) const fn get(self) -> u64 {
        self.0
    }

    pub(crate) const fn checked_duration_since(self, earlier: Self) -> Option<u64> {
        self.0.checked_sub(earlier.0)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct UtcEpochNanos(u64);

impl UtcEpochNanos {
    pub(crate) const fn new(value: u64) -> Self {
        Self(value)
    }

    pub(crate) const fn get(self) -> u64 {
        self.0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ClockAnchor {
    pub(crate) monotonic: MonotonicNanos,
    pub(crate) utc: UtcEpochNanos,
}

impl ClockAnchor {
    pub(crate) const fn new(monotonic: MonotonicNanos, utc: UtcEpochNanos) -> Self {
        Self { monotonic, utc }
    }

    pub(crate) fn utc_at(self, monotonic: MonotonicNanos) -> Option<UtcEpochNanos> {
        if monotonic >= self.monotonic {
            self.utc
                .get()
                .checked_add(monotonic.get() - self.monotonic.get())
                .map(UtcEpochNanos::new)
        } else {
            self.utc
                .get()
                .checked_sub(self.monotonic.get() - monotonic.get())
                .map(UtcEpochNanos::new)
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SourceClock {
    AndroidElapsedRealtime,
    JavaNanoTime,
    PolarSensor,
    OpenXrTime,
    UtcUnix,
}

impl SourceClock {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::AndroidElapsedRealtime => "android-elapsed-realtime",
            Self::JavaNanoTime => "java-nano-time",
            Self::PolarSensor => "polar-sensor",
            Self::OpenXrTime => "openxr-time",
            Self::UtcUnix => "utc-unix",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct SourceTimestamp {
    pub(crate) clock: SourceClock,
    pub(crate) value_ns: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct SessionTimestampKey(String);

impl SessionTimestampKey {
    pub(crate) fn from_utc(utc: UtcEpochNanos) -> Self {
        Self(format!(
            "{SESSION_KEY_PREFIX}{:0SESSION_KEY_DIGITS$}",
            utc.get()
        ))
    }

    pub(crate) fn parse(value: &str) -> Result<Self, &'static str> {
        let Some(digits) = value.strip_prefix(SESSION_KEY_PREFIX) else {
            return Err("session-key-prefix-invalid");
        };
        if digits.len() != SESSION_KEY_DIGITS
            || !digits.bytes().all(|byte| byte.is_ascii_digit())
            || digits.parse::<u64>().is_err()
        {
            return Err("session-key-timestamp-invalid");
        }
        Ok(Self(value.to_owned()))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn anchor_maps_both_sides_without_mixing_clock_domains() {
        let anchor = ClockAnchor::new(MonotonicNanos::new(5_000), UtcEpochNanos::new(1_000_000));
        assert_eq!(
            anchor.utc_at(MonotonicNanos::new(5_125)),
            Some(UtcEpochNanos::new(1_000_125))
        );
        assert_eq!(
            anchor.utc_at(MonotonicNanos::new(4_875)),
            Some(UtcEpochNanos::new(999_875))
        );
    }

    #[test]
    fn session_key_contains_only_the_utc_timestamp() {
        let key = SessionTimestampKey::from_utc(UtcEpochNanos::new(1_725_000_000_123_456_789));
        assert_eq!(key.as_str(), "utc-ns-01725000000123456789");
        assert_eq!(SessionTimestampKey::parse(key.as_str()), Ok(key));
        assert!(SessionTimestampKey::parse("utc-ns-not-a-time").is_err());
        assert!(SessionTimestampKey::parse("participant-7").is_err());
    }
}
