//! Optional, audio-bound breath guidance timeline.
//!
//! A private app may package one exact JSON document beside a condition's
//! audio asset.  This public owner validates the generic timeline and exposes
//! only the expected phase at official active time.  Missing documents and
//! uncovered timeline gaps are deliberately unbiased.

use serde_json::{Map, Value};

use rusty_quest_breath_contract::assessment::CommonBreathPhase;

pub(crate) const BREATH_GUIDANCE_SCHEMA: &str = "rusty.quest.breath_guidance_timeline.v1";
pub(crate) const BREATH_GUIDANCE_TIMEBASE: &str = "official-active-time-ms";
const MAX_DOCUMENT_BYTES: u64 = 256 * 1024;
const MAX_SEGMENTS: usize = 4_096;
const MAX_TIMELINE_MS: u64 = 4 * 60 * 60 * 1_000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct BreathGuidanceAssetIdentity {
    pub(crate) logical_destination: String,
    pub(crate) source_sha256: String,
    pub(crate) source_bytes: u64,
    pub(crate) media_type: String,
}

impl BreathGuidanceAssetIdentity {
    pub(crate) fn validate(&self) -> Result<(), &'static str> {
        if !valid_relative_identity_path(&self.logical_destination) {
            return Err("breath-guidance-destination-invalid");
        }
        if !valid_sha256(&self.source_sha256) {
            return Err("breath-guidance-sha256-invalid");
        }
        if self.source_bytes == 0 || self.source_bytes > MAX_DOCUMENT_BYTES {
            return Err("breath-guidance-bytes-invalid");
        }
        if self.media_type != "application/json" {
            return Err("breath-guidance-media-type-invalid");
        }
        Ok(())
    }

    pub(crate) fn encoded_value(&self) -> Value {
        serde_json::json!({
            "logical_destination": self.logical_destination,
            "source_sha256": self.source_sha256,
            "source_bytes": self.source_bytes,
            "media_type": self.media_type,
        })
    }

    pub(crate) fn parse_encoded(value: &Value) -> Result<Self, &'static str> {
        let object = value
            .as_object()
            .filter(|object| object.len() == 4)
            .ok_or("breath-guidance-identity-invalid")?;
        let string = |name| {
            object
                .get(name)
                .and_then(Value::as_str)
                .map(str::to_owned)
                .ok_or("breath-guidance-identity-invalid")
        };
        let identity = Self {
            logical_destination: string("logical_destination")?,
            source_sha256: string("source_sha256")?,
            source_bytes: object
                .get("source_bytes")
                .and_then(Value::as_u64)
                .ok_or("breath-guidance-identity-invalid")?,
            media_type: string("media_type")?,
        };
        identity.validate()?;
        Ok(identity)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BreathGuidancePhase {
    Inhale,
    Exhale,
    Hold,
}

impl BreathGuidancePhase {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Inhale => "inhale",
            Self::Exhale => "exhale",
            Self::Hold => "hold",
        }
    }

    const fn common(self) -> CommonBreathPhase {
        match self {
            Self::Inhale => CommonBreathPhase::Inhale,
            Self::Exhale => CommonBreathPhase::Exhale,
            Self::Hold => CommonBreathPhase::Hold,
        }
    }

    fn parse(value: &str) -> Option<Self> {
        match value {
            "inhale" => Some(Self::Inhale),
            "exhale" => Some(Self::Exhale),
            "hold" => Some(Self::Hold),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct BreathGuidanceSegment {
    start_ms: u64,
    end_ms: u64,
    phase: BreathGuidancePhase,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct BreathGuidanceTimeline {
    pub(crate) identity: BreathGuidanceAssetIdentity,
    audio_source_sha256: String,
    segments: Box<[BreathGuidanceSegment]>,
}

impl BreathGuidanceTimeline {
    pub(crate) fn parse_exact(
        bytes: &[u8],
        identity: BreathGuidanceAssetIdentity,
        expected_audio_sha256: &str,
    ) -> Result<Self, String> {
        identity.validate().map_err(str::to_owned)?;
        if bytes.len() as u64 != identity.source_bytes {
            return Err("breath-guidance-byte-count-mismatch".to_owned());
        }
        let text =
            std::str::from_utf8(bytes).map_err(|_| "breath-guidance-utf8-invalid".to_owned())?;
        if text.starts_with('\u{feff}') {
            return Err("breath-guidance-bom-forbidden".to_owned());
        }
        if rusty_quest_broker_authority::packaged_json_sha256(text) != identity.source_sha256 {
            return Err("breath-guidance-digest-mismatch".to_owned());
        }
        let document: Value =
            serde_json::from_str(text).map_err(|_| "breath-guidance-json-invalid".to_owned())?;
        let object = document
            .as_object()
            .ok_or_else(|| "breath-guidance-document-invalid".to_owned())?;
        require_exact_fields(
            object,
            &["schema", "timebase", "audio_source_sha256", "segments"],
        )?;
        if object.get("schema").and_then(Value::as_str) != Some(BREATH_GUIDANCE_SCHEMA) {
            return Err("breath-guidance-schema-invalid".to_owned());
        }
        if object.get("timebase").and_then(Value::as_str) != Some(BREATH_GUIDANCE_TIMEBASE) {
            return Err("breath-guidance-timebase-invalid".to_owned());
        }
        let audio_source_sha256 = object
            .get("audio_source_sha256")
            .and_then(Value::as_str)
            .filter(|value| valid_sha256(value) && *value == expected_audio_sha256)
            .ok_or_else(|| "breath-guidance-audio-binding-invalid".to_owned())?
            .to_owned();
        let encoded = object
            .get("segments")
            .and_then(Value::as_array)
            .filter(|values| !values.is_empty() && values.len() <= MAX_SEGMENTS)
            .ok_or_else(|| "breath-guidance-segments-invalid".to_owned())?;
        let mut segments = Vec::with_capacity(encoded.len());
        let mut previous_end = 0_u64;
        for value in encoded {
            let segment = value
                .as_object()
                .ok_or_else(|| "breath-guidance-segment-invalid".to_owned())?;
            require_exact_fields(segment, &["start_ms", "end_ms", "phase"])?;
            let start_ms = segment
                .get("start_ms")
                .and_then(Value::as_u64)
                .ok_or_else(|| "breath-guidance-segment-time-invalid".to_owned())?;
            let end_ms = segment
                .get("end_ms")
                .and_then(Value::as_u64)
                .filter(|end| *end > start_ms && *end <= MAX_TIMELINE_MS)
                .ok_or_else(|| "breath-guidance-segment-time-invalid".to_owned())?;
            if !segments.is_empty() && start_ms < previous_end {
                return Err("breath-guidance-segments-overlap".to_owned());
            }
            let phase = segment
                .get("phase")
                .and_then(Value::as_str)
                .and_then(BreathGuidancePhase::parse)
                .ok_or_else(|| "breath-guidance-segment-phase-invalid".to_owned())?;
            segments.push(BreathGuidanceSegment {
                start_ms,
                end_ms,
                phase,
            });
            previous_end = end_ms;
        }
        Ok(Self {
            identity,
            audio_source_sha256,
            segments: segments.into_boxed_slice(),
        })
    }

    pub(crate) fn target_at(
        &self,
        official_active_time_ms: u64,
        bias_percent: u8,
    ) -> Option<BreathGuidanceTarget> {
        if bias_percent == 0 || bias_percent > 100 {
            return None;
        }
        let index = self
            .segments
            .partition_point(|segment| segment.end_ms <= official_active_time_ms);
        let segment = self.segments.get(index)?;
        (official_active_time_ms >= segment.start_ms).then_some(BreathGuidanceTarget {
            expected_phase: segment.phase.common(),
            expected_phase_token: segment.phase.as_str(),
            bias_percent,
            official_active_time_ms,
        })
    }

    pub(crate) fn audio_source_sha256(&self) -> &str {
        &self.audio_source_sha256
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct BreathGuidanceTarget {
    pub(crate) expected_phase: CommonBreathPhase,
    pub(crate) expected_phase_token: &'static str,
    pub(crate) bias_percent: u8,
    pub(crate) official_active_time_ms: u64,
}

fn require_exact_fields(object: &Map<String, Value>, expected: &[&str]) -> Result<(), String> {
    if object.len() != expected.len() || expected.iter().any(|field| !object.contains_key(*field)) {
        return Err("breath-guidance-fields-invalid".to_owned());
    }
    Ok(())
}

fn valid_sha256(value: &str) -> bool {
    value.len() == 64
        && !value.bytes().all(|byte| byte == b'0')
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn valid_relative_identity_path(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && !value.starts_with('/')
        && !value.contains('\\')
        && value
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b'/'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> (Vec<u8>, BreathGuidanceAssetIdentity) {
        let text = serde_json::json!({
            "schema": BREATH_GUIDANCE_SCHEMA,
            "timebase": BREATH_GUIDANCE_TIMEBASE,
            "audio_source_sha256": "a".repeat(64),
            "segments": [
                {"start_ms": 0, "end_ms": 4000, "phase": "inhale"},
                {"start_ms": 4000, "end_ms": 5000, "phase": "hold"},
                {"start_ms": 5000, "end_ms": 9000, "phase": "exhale"},
                {"start_ms": 10000, "end_ms": 12000, "phase": "inhale"}
            ]
        })
        .to_string();
        let bytes = text.into_bytes();
        let identity = BreathGuidanceAssetIdentity {
            logical_destination: "breath-guidance/condition-a.json".to_owned(),
            source_sha256: rusty_quest_broker_authority::packaged_json_sha256(
                std::str::from_utf8(&bytes).unwrap(),
            ),
            source_bytes: bytes.len() as u64,
            media_type: "application/json".to_owned(),
        };
        (bytes, identity)
    }

    #[test]
    fn timeline_is_half_open_and_gaps_are_unbiased() {
        let (bytes, identity) = fixture();
        let timeline = BreathGuidanceTimeline::parse_exact(&bytes, identity, &"a".repeat(64))
            .expect("valid timeline");
        assert_eq!(
            timeline.target_at(0, 50).unwrap().expected_phase_token,
            "inhale"
        );
        assert_eq!(
            timeline.target_at(4_000, 50).unwrap().expected_phase_token,
            "hold"
        );
        assert_eq!(
            timeline.target_at(8_999, 50).unwrap().expected_phase_token,
            "exhale"
        );
        assert!(timeline.target_at(9_000, 50).is_none());
        assert!(timeline.target_at(1_000, 0).is_none());
    }

    #[test]
    fn wrong_audio_overlap_and_digest_fail_closed() {
        let (bytes, identity) = fixture();
        assert!(
            BreathGuidanceTimeline::parse_exact(&bytes, identity.clone(), &"b".repeat(64))
                .unwrap_err()
                .contains("audio-binding")
        );

        let mut damaged: Value = serde_json::from_slice(&bytes).unwrap();
        damaged["segments"][1]["start_ms"] = serde_json::json!(3999);
        let text = damaged.to_string();
        let damaged_bytes = text.as_bytes();
        let damaged_identity = BreathGuidanceAssetIdentity {
            source_sha256: rusty_quest_broker_authority::packaged_json_sha256(&text),
            source_bytes: damaged_bytes.len() as u64,
            ..identity
        };
        assert!(BreathGuidanceTimeline::parse_exact(
            damaged_bytes,
            damaged_identity,
            &"a".repeat(64),
        )
        .unwrap_err()
        .contains("overlap"));
    }
}
