//! Source clock labels for sensor/configuration provenance. No clock or file owner.
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
