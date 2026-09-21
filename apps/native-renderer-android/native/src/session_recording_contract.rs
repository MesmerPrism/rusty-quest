//! Typed, identity-minimal recording inputs.
//!
//! Producers construct these bounded values only. JSON encoding and all file
//! operations belong to `session_recording_writer`.

use serde_json::{json, Value};

use crate::session_recording_clock::{
    ClockAnchor, MonotonicNanos, SessionTimestampKey, SourceTimestamp,
};

pub(crate) const DEFAULT_COMPLETION_THRESHOLD_NS: u64 = 30_000_000_000;
pub(crate) const SESSION_MANIFEST_SCHEMA: &str =
    "rusty.quest.native_renderer.session_recording_manifest.v1";
pub(crate) const SESSION_ROW_SCHEMA: &str = "rusty.quest.native_renderer.session_recording_row.v1";
pub(crate) const SESSION_CHECKPOINT_SCHEMA: &str =
    "rusty.quest.native_renderer.session_recording_checkpoint.v1";
pub(crate) const SESSION_FINAL_SCHEMA: &str =
    "rusty.quest.native_renderer.session_recording_final.v1";
pub(crate) const SESSION_INDEX_SCHEMA: &str =
    "rusty.quest.native_renderer.session_recording_index.v1";
pub(crate) const SESSION_STREAM_FILES: [&str; 6] = [
    "events.jsonl",
    "polar-acc.jsonl",
    "polar-ecg.jsonl",
    "polar-hr-rr.jsonl",
    "breath.jsonl",
    "radius.jsonl",
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ConditionKey(String);

impl ConditionKey {
    pub(crate) fn parse(value: &str) -> Result<Self, &'static str> {
        let value = value.trim();
        if value.is_empty()
            || value.len() > 64
            || !value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        {
            return Err("condition-key-invalid");
        }
        Ok(Self(value.to_owned()))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct SessionStartSpec {
    pub(crate) started_at: SessionTimestampKey,
    pub(crate) condition: ConditionKey,
    pub(crate) completion_threshold_ns: u64,
    pub(crate) clock_anchor: ClockAnchor,
    pub(crate) experiment_identity: ExperimentIdentity,
}

impl SessionStartSpec {
    pub(crate) fn validate(&self) -> Result<(), &'static str> {
        if self.completion_threshold_ns == 0 {
            return Err("completion-threshold-invalid");
        }
        if self.started_at != SessionTimestampKey::from_utc(self.clock_anchor.utc) {
            return Err("session-key-clock-anchor-mismatch");
        }
        self.experiment_identity.validate_for(&self.condition)?;
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct AudioAssetIdentity {
    pub(crate) logical_destination: String,
    pub(crate) source_sha256: String,
    pub(crate) source_bytes: u64,
    pub(crate) media_type: String,
}

/// One named deformation value at the configured minimum and maximum radius.
/// The public recorder does not interpret either endpoint or embed private
/// product values; the trusted app inventory supplies the effective pair.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct DeformationEnvelopeEndpoints {
    pub(crate) at_radius_min: f32,
    pub(crate) at_radius_max: f32,
}

impl DeformationEnvelopeEndpoints {
    fn validate(self) -> Result<(), &'static str> {
        if !self.at_radius_min.is_finite() || !self.at_radius_max.is_finite() {
            return Err("radius-deformation-envelope-invalid");
        }
        Ok(())
    }

    fn encoded_value(self) -> Value {
        json!({
            "at_radius_min": self.at_radius_min,
            "at_radius_max": self.at_radius_max,
        })
    }

    pub(crate) fn parse_encoded(value: &Value) -> Result<Self, &'static str> {
        let object = value
            .as_object()
            .filter(|value| value.len() == 2)
            .ok_or("radius-deformation-envelope-invalid")?;
        let endpoint = |name| {
            object
                .get(name)
                .and_then(Value::as_f64)
                .filter(|value| value.is_finite())
                .map(|value| value as f32)
                .filter(|value| value.is_finite())
                .ok_or("radius-deformation-envelope-invalid")
        };
        let endpoints = Self {
            at_radius_min: endpoint("at_radius_min")?,
            at_radius_max: endpoint("at_radius_max")?,
        };
        endpoints.validate()?;
        Ok(endpoints)
    }
}

/// Generic app-owned radius/deformation profile bound by the trusted inventory.
/// Distinct optional fields prevent the two deformation envelopes from being
/// collapsed into an unlabeled pair.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct EffectiveRadiusProfile {
    pub(crate) configured_radius_min_m: f32,
    pub(crate) configured_radius_max_m: f32,
    pub(crate) oblateness: Option<DeformationEnvelopeEndpoints>,
    pub(crate) axis_profile: Option<DeformationEnvelopeEndpoints>,
}

impl EffectiveRadiusProfile {
    pub(crate) fn validate(self) -> Result<(), &'static str> {
        if !self.configured_radius_min_m.is_finite()
            || !self.configured_radius_max_m.is_finite()
            || self.configured_radius_min_m <= 0.0
            || self.configured_radius_max_m < self.configured_radius_min_m
        {
            return Err("radius-profile-limits-invalid");
        }
        if let Some(endpoints) = self.oblateness {
            endpoints.validate()?;
        }
        if let Some(endpoints) = self.axis_profile {
            endpoints.validate()?;
        }
        Ok(())
    }

    fn encoded_value(self) -> Value {
        let mut value = json!({
            "configured_radius_min_m": self.configured_radius_min_m,
            "configured_radius_max_m": self.configured_radius_max_m,
        });
        if let Some(endpoints) = self.oblateness {
            value["oblateness"] = endpoints.encoded_value();
        }
        if let Some(endpoints) = self.axis_profile {
            value["axis_profile"] = endpoints.encoded_value();
        }
        value
    }

    pub(crate) fn parse_encoded(value: &Value) -> Result<Self, &'static str> {
        let object = value.as_object().ok_or("radius-profile-invalid")?;
        if object.len() < 2
            || object.len() > 4
            || object.keys().any(|key| {
                !matches!(
                    key.as_str(),
                    "configured_radius_min_m"
                        | "configured_radius_max_m"
                        | "oblateness"
                        | "axis_profile"
                )
            })
        {
            return Err("radius-profile-fields-invalid");
        }
        let finite = |name| {
            object
                .get(name)
                .and_then(Value::as_f64)
                .filter(|value| value.is_finite())
                .map(|value| value as f32)
                .filter(|value| value.is_finite())
                .ok_or("radius-profile-limits-invalid")
        };
        let profile = Self {
            configured_radius_min_m: finite("configured_radius_min_m")?,
            configured_radius_max_m: finite("configured_radius_max_m")?,
            oblateness: object
                .get("oblateness")
                .map(DeformationEnvelopeEndpoints::parse_encoded)
                .transpose()?,
            axis_profile: object
                .get("axis_profile")
                .map(DeformationEnvelopeEndpoints::parse_encoded)
                .transpose()?,
        };
        profile.validate()?;
        Ok(profile)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct ExperimentIdentity {
    pub(crate) provider_id: String,
    pub(crate) provider_manifest_sha256: String,
    pub(crate) provider_inventory_sha256: String,
    pub(crate) non_audio_profile_sha256: String,
    pub(crate) condition_id: String,
    pub(crate) audio: AudioAssetIdentity,
    pub(crate) effective_radius_profile: EffectiveRadiusProfile,
}

impl ExperimentIdentity {
    pub(crate) fn validate_for(&self, condition: &ConditionKey) -> Result<(), &'static str> {
        let token = |value: &str| {
            !value.is_empty()
                && value.len() <= 256
                && value.bytes().all(|byte| {
                    byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b'/')
                })
        };
        let digest = |value: &str| {
            value.len() == 64
                && !value.bytes().all(|byte| byte == b'0')
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        };
        if !token(&self.provider_id) {
            return Err("experiment-provider-id-invalid");
        }
        if !digest(&self.provider_manifest_sha256)
            || !digest(&self.provider_inventory_sha256)
            || !digest(&self.non_audio_profile_sha256)
            || !digest(&self.audio.source_sha256)
        {
            return Err("experiment-identity-digest-invalid");
        }
        if self.condition_id != condition.as_str() {
            return Err("experiment-condition-identity-mismatch");
        }
        if !token(&self.audio.logical_destination)
            || self.audio.source_bytes == 0
            || !self.audio.media_type.starts_with("audio/")
            || !token(&self.audio.media_type)
        {
            return Err("experiment-audio-identity-invalid");
        }
        self.effective_radius_profile.validate()?;
        Ok(())
    }

    pub(crate) fn encoded_value(&self) -> Value {
        json!({
            "provider_id": self.provider_id,
            "provider_manifest_sha256": self.provider_manifest_sha256,
            "provider_inventory_sha256": self.provider_inventory_sha256,
            "non_audio_profile_sha256": self.non_audio_profile_sha256,
            "condition_id": self.condition_id,
            "audio": {
                "logical_destination": self.audio.logical_destination,
                "source_sha256": self.audio.source_sha256,
                "source_bytes": self.audio.source_bytes,
                "media_type": self.audio.media_type,
            },
            "effective_radius_profile": self.effective_radius_profile.encoded_value(),
        })
    }

    pub(crate) fn parse_encoded(
        value: &Value,
        condition: &ConditionKey,
    ) -> Result<Self, &'static str> {
        let object = value.as_object().ok_or("experiment-identity-not-object")?;
        if object.len() != 7 {
            return Err("experiment-identity-fields-invalid");
        }
        let string = |name| {
            object
                .get(name)
                .and_then(Value::as_str)
                .map(str::to_owned)
                .ok_or("experiment-identity-field-invalid")
        };
        let audio_object = object
            .get("audio")
            .and_then(Value::as_object)
            .filter(|value| value.len() == 4)
            .ok_or("experiment-audio-identity-invalid")?;
        let audio_string = |name| {
            audio_object
                .get(name)
                .and_then(Value::as_str)
                .map(str::to_owned)
                .ok_or("experiment-audio-identity-invalid")
        };
        let identity = Self {
            provider_id: string("provider_id")?,
            provider_manifest_sha256: string("provider_manifest_sha256")?,
            provider_inventory_sha256: string("provider_inventory_sha256")?,
            non_audio_profile_sha256: string("non_audio_profile_sha256")?,
            condition_id: string("condition_id")?,
            audio: AudioAssetIdentity {
                logical_destination: audio_string("logical_destination")?,
                source_sha256: audio_string("source_sha256")?,
                source_bytes: audio_object
                    .get("source_bytes")
                    .and_then(Value::as_u64)
                    .ok_or("experiment-audio-identity-invalid")?,
                media_type: audio_string("media_type")?,
            },
            effective_radius_profile: EffectiveRadiusProfile::parse_encoded(
                object
                    .get("effective_radius_profile")
                    .ok_or("radius-profile-invalid")?,
            )?,
        };
        identity.validate_for(condition)?;
        Ok(identity)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SessionEventKind {
    Started,
    ImmersiveActive,
    ImmersivePaused,
    DeveloperOpened,
    DeveloperClosed,
    AudioStarted,
    AudioEnded,
    AudioError,
    CompletionReached,
    RestartRequested,
    ExitRequested,
    SourceGap,
}

impl SessionEventKind {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Started => "started",
            Self::ImmersiveActive => "immersive-active",
            Self::ImmersivePaused => "immersive-paused",
            Self::DeveloperOpened => "developer-opened",
            Self::DeveloperClosed => "developer-closed",
            Self::AudioStarted => "audio-started",
            Self::AudioEnded => "audio-ended",
            Self::AudioError => "audio-error",
            Self::CompletionReached => "completion-reached",
            Self::RestartRequested => "restart-requested",
            Self::ExitRequested => "exit-requested",
            Self::SourceGap => "source-gap",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SessionEvent {
    pub(crate) observed_at: MonotonicNanos,
    pub(crate) kind: SessionEventKind,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarAccSample {
    pub(crate) source_sequence: u64,
    pub(crate) sample_index: u32,
    pub(crate) source_time: SourceTimestamp,
    pub(crate) received_source: SourceTimestamp,
    pub(crate) observed_at: MonotonicNanos,
    pub(crate) xyz_mg: [f32; 3],
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PolarAccBatch {
    pub(crate) frame_sequence: u64,
    pub(crate) samples: Box<[PolarAccSample]>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarEcgSample {
    pub(crate) source_sequence: u64,
    pub(crate) sample_index: u32,
    pub(crate) source_time: SourceTimestamp,
    pub(crate) received_source: SourceTimestamp,
    pub(crate) observed_at: MonotonicNanos,
    pub(crate) microvolts: i32,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PolarEcgBatch {
    pub(crate) frame_sequence: u64,
    pub(crate) samples: Box<[PolarEcgSample]>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarHeartRateObservation {
    pub(crate) source_sequence: u64,
    pub(crate) source_time: SourceTimestamp,
    pub(crate) observed_at: MonotonicNanos,
    pub(crate) bpm: u16,
    pub(crate) rr_interval_ms: Option<f32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BreathPhase {
    Inhale,
    Exhale,
    Hold,
    Unknown,
}

impl BreathPhase {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Inhale => "inhale",
            Self::Exhale => "exhale",
            Self::Hold => "hold",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct BreathObservation {
    pub(crate) source_sequence: u64,
    pub(crate) sampled_at: SourceTimestamp,
    pub(crate) observed_source: SourceTimestamp,
    pub(crate) observed_at: MonotonicNanos,
    pub(crate) phase: BreathPhase,
    pub(crate) volume01: Option<f32>,
    pub(crate) quality01: f32,
    pub(crate) settings_revision: u64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct EffectiveRadiusSnapshotObservation {
    pub(crate) source_frame: u64,
    pub(crate) observed_at: MonotonicNanos,
    pub(crate) configured_radius_min_m: f32,
    pub(crate) configured_radius_max_m: f32,
    pub(crate) radius_progress01: f32,
    pub(crate) resulting_radius_m: f32,
    pub(crate) deformation_progress01: f32,
    pub(crate) oblateness: Option<DeformationEnvelopeEndpoints>,
    pub(crate) axis_profile: Option<DeformationEnvelopeEndpoints>,
    pub(crate) settings_revision: u64,
    pub(crate) render_session_generation: u64,
    pub(crate) resulting_radius_parameter_source: &'static str,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum SessionRecord {
    Event(SessionEvent),
    PolarAcc(PolarAccBatch),
    PolarEcg(PolarEcgBatch),
    PolarHeartRate(PolarHeartRateObservation),
    Breath(BreathObservation),
    EffectiveRadiusSnapshot(EffectiveRadiusSnapshotObservation),
}

impl SessionRecord {
    pub(crate) const fn stream_file(&self) -> &'static str {
        match self {
            Self::Event(_) => "events.jsonl",
            Self::PolarAcc(_) => "polar-acc.jsonl",
            Self::PolarEcg(_) => "polar-ecg.jsonl",
            Self::PolarHeartRate(_) => "polar-hr-rr.jsonl",
            Self::Breath(_) => "breath.jsonl",
            Self::EffectiveRadiusSnapshot(_) => "radius.jsonl",
        }
    }

    pub(crate) fn observed_at(&self) -> MonotonicNanos {
        match self {
            Self::Event(value) => value.observed_at,
            Self::PolarAcc(value) => value
                .samples
                .last()
                .map_or(MonotonicNanos::new(0), |sample| sample.observed_at),
            Self::PolarEcg(value) => value
                .samples
                .last()
                .map_or(MonotonicNanos::new(0), |sample| sample.observed_at),
            Self::PolarHeartRate(value) => value.observed_at,
            Self::Breath(value) => value.observed_at,
            Self::EffectiveRadiusSnapshot(value) => value.observed_at,
        }
    }

    pub(crate) fn floats_are_finite(&self) -> bool {
        let optional_finite = |value: Option<f32>| value.map_or(true, f32::is_finite);
        match self {
            Self::Event(_) | Self::PolarEcg(_) => true,
            Self::PolarAcc(value) => value
                .samples
                .iter()
                .all(|sample| sample.xyz_mg.iter().all(|axis| axis.is_finite())),
            Self::PolarHeartRate(value) => optional_finite(value.rr_interval_ms),
            Self::Breath(value) => optional_finite(value.volume01) && value.quality01.is_finite(),
            Self::EffectiveRadiusSnapshot(value) => {
                value.configured_radius_min_m.is_finite()
                    && value.configured_radius_max_m.is_finite()
                    && value.configured_radius_min_m > 0.0
                    && value.configured_radius_max_m >= value.configured_radius_min_m
                    && value.radius_progress01.is_finite()
                    && (0.0..=1.0).contains(&value.radius_progress01)
                    && value.resulting_radius_m.is_finite()
                    && value.resulting_radius_m > 0.0
                    && value.deformation_progress01.is_finite()
                    && (0.0..=1.0).contains(&value.deformation_progress01)
                    && value
                        .oblateness
                        .is_none_or(|endpoints| endpoints.validate().is_ok())
                    && value
                        .axis_profile
                        .is_none_or(|endpoints| endpoints.validate().is_ok())
                    && !value.resulting_radius_parameter_source.is_empty()
            }
        }
    }

    /// Number of logical sensor samples held by this one queue item. This is
    /// computed without serialization on producer threads.
    pub(crate) fn sample_count(&self) -> usize {
        match self {
            Self::PolarAcc(value) => value.samples.len(),
            Self::PolarEcg(value) => value.samples.len(),
            _ => 1,
        }
    }

    /// Deterministic upper-bound unit used by queue admission. It accounts for
    /// the owned record plus every heap-resident batch sample without encoding.
    pub(crate) fn queued_payload_bytes(&self) -> usize {
        let base = std::mem::size_of::<Self>();
        match self {
            Self::PolarAcc(value) => base.saturating_add(
                value
                    .samples
                    .len()
                    .saturating_mul(std::mem::size_of::<PolarAccSample>()),
            ),
            Self::PolarEcg(value) => base.saturating_add(
                value
                    .samples
                    .len()
                    .saturating_mul(std::mem::size_of::<PolarEcgSample>()),
            ),
            _ => base,
        }
    }

    pub(crate) fn encoded_rows(&self, sequence: u64, spec: &SessionStartSpec) -> Vec<Value> {
        let common = |kind: &str, observed_at: MonotonicNanos| {
            let mut row = json!({
                "schema": SESSION_ROW_SCHEMA,
                "record_sequence": sequence,
                "kind": kind,
                "session_started_at_utc": spec.started_at.as_str(),
                "condition": spec.condition.as_str(),
                "observed_monotonic_ns": observed_at.get()
            });
            if let Some(observed_utc) = spec.clock_anchor.utc_at(observed_at) {
                row["observed_utc_ns"] = json!(observed_utc.get());
            }
            row
        };
        match self {
            Self::Event(value) => {
                let mut row = common("event", value.observed_at);
                row["event"] = json!(value.kind.as_str());
                vec![row]
            }
            Self::PolarAcc(value) => value
                .samples
                .iter()
                .map(|sample| {
                    let mut row = common("polar-acc", sample.observed_at);
                    row["frame_sequence"] = json!(value.frame_sequence);
                    row["source_sequence"] = json!(sample.source_sequence);
                    row["sample_index"] = json!(sample.sample_index);
                    row["source_clock"] = json!(sample.source_time.clock.as_str());
                    row["source_time_ns"] = json!(sample.source_time.value_ns);
                    row["received_source_clock"] = json!(sample.received_source.clock.as_str());
                    row["received_source_time_ns"] = json!(sample.received_source.value_ns);
                    row["xyz_mg"] = json!(sample.xyz_mg);
                    row
                })
                .collect(),
            Self::PolarEcg(value) => value
                .samples
                .iter()
                .map(|sample| {
                    let mut row = common("polar-ecg", sample.observed_at);
                    row["frame_sequence"] = json!(value.frame_sequence);
                    row["source_sequence"] = json!(sample.source_sequence);
                    row["sample_index"] = json!(sample.sample_index);
                    row["source_clock"] = json!(sample.source_time.clock.as_str());
                    row["source_time_ns"] = json!(sample.source_time.value_ns);
                    row["received_source_clock"] = json!(sample.received_source.clock.as_str());
                    row["received_source_time_ns"] = json!(sample.received_source.value_ns);
                    row["microvolts"] = json!(sample.microvolts);
                    row
                })
                .collect(),
            Self::PolarHeartRate(value) => {
                let mut row = common("polar-hr-rr", value.observed_at);
                row["source_sequence"] = json!(value.source_sequence);
                row["source_clock"] = json!(value.source_time.clock.as_str());
                row["source_time_ns"] = json!(value.source_time.value_ns);
                row["bpm"] = json!(value.bpm);
                if let Some(rr_interval_ms) = value.rr_interval_ms {
                    row["rr_interval_ms"] = json!(rr_interval_ms);
                }
                vec![row]
            }
            Self::Breath(value) => {
                let mut row = common("breath", value.observed_at);
                row["source_sequence"] = json!(value.source_sequence);
                row["sampled_clock"] = json!(value.sampled_at.clock.as_str());
                row["sampled_time_ns"] = json!(value.sampled_at.value_ns);
                row["observed_source_clock"] = json!(value.observed_source.clock.as_str());
                row["observed_source_time_ns"] = json!(value.observed_source.value_ns);
                row["phase"] = json!(value.phase.as_str());
                if let Some(volume01) = value.volume01 {
                    row["volume01"] = json!(volume01);
                }
                row["quality01"] = json!(value.quality01);
                row["settings_revision"] = json!(value.settings_revision);
                vec![row]
            }
            Self::EffectiveRadiusSnapshot(value) => {
                let mut row = common("effective-radius-snapshot", value.observed_at);
                row["metric"] = json!("effective-radius-snapshot");
                row["metric_status"] =
                    json!("actual-runtime-radius-with-configured-deformation-envelopes");
                row["source_frame"] = json!(value.source_frame);
                row["configured_radius_min_m"] = json!(value.configured_radius_min_m);
                row["configured_radius_max_m"] = json!(value.configured_radius_max_m);
                row["radius_progress01"] = json!(value.radius_progress01);
                row["radius_progress_source"] = json!("runtime-driver-slot-0");
                row["resulting_radius_m"] = json!(value.resulting_radius_m);
                row["resulting_radius_parameter_source"] =
                    json!(value.resulting_radius_parameter_source);
                row["deformation_progress01"] = json!(value.deformation_progress01);
                row["deformation_progress_source"] = json!("runtime-driver-slot-0");
                if let Some(endpoints) = value.oblateness {
                    row["oblateness"] = endpoints.encoded_value();
                }
                if let Some(endpoints) = value.axis_profile {
                    row["axis_profile"] = endpoints.encoded_value();
                }
                row["settings_revision"] = json!(value.settings_revision);
                row["render_session_generation"] = json!(value.render_session_generation);
                vec![row]
            }
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FinalizationReason {
    RestartToExperimenter,
    SaveAndExit,
    InterruptedRecovery,
}

impl FinalizationReason {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::RestartToExperimenter => "restart-to-experimenter",
            Self::SaveAndExit => "save-and-exit",
            Self::InterruptedRecovery => "interrupted-recovery",
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct SessionCounts {
    pub(crate) completed: u64,
    pub(crate) stopped_early: u64,
    pub(crate) errors: u64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session_recording_clock::{SourceClock, UtcEpochNanos};

    fn spec() -> SessionStartSpec {
        let utc = UtcEpochNanos::new(1_725_000_000_000_000_000);
        SessionStartSpec {
            started_at: SessionTimestampKey::from_utc(utc),
            condition: ConditionKey::parse("condition-a").unwrap(),
            completion_threshold_ns: DEFAULT_COMPLETION_THRESHOLD_NS,
            clock_anchor: ClockAnchor::new(MonotonicNanos::new(1_000), utc),
            experiment_identity: ExperimentIdentity {
                provider_id: "private-provider".to_owned(),
                provider_manifest_sha256: "11".repeat(32),
                provider_inventory_sha256: "22".repeat(32),
                non_audio_profile_sha256: "33".repeat(32),
                condition_id: "condition-a".to_owned(),
                audio: AudioAssetIdentity {
                    logical_destination: "audio/condition-a.wav".to_owned(),
                    source_sha256: "44".repeat(32),
                    source_bytes: 1024,
                    media_type: "audio/wav".to_owned(),
                },
                effective_radius_profile: EffectiveRadiusProfile {
                    configured_radius_min_m: 0.8,
                    configured_radius_max_m: 1.6,
                    oblateness: Some(DeformationEnvelopeEndpoints {
                        at_radius_min: 0.2,
                        at_radius_max: 0.4,
                    }),
                    axis_profile: Some(DeformationEnvelopeEndpoints {
                        at_radius_min: 1.8,
                        at_radius_max: 0.9,
                    }),
                },
            },
        }
    }

    #[test]
    fn start_spec_binds_timestamp_key_to_clock_anchor() {
        assert_eq!(spec().validate(), Ok(()));
        let mut invalid = spec();
        invalid.started_at =
            SessionTimestampKey::from_utc(UtcEpochNanos::new(1_725_000_000_000_000_001));
        assert_eq!(invalid.validate(), Err("session-key-clock-anchor-mismatch"));
    }

    #[test]
    fn experiment_identity_is_exact_condition_bound_and_round_trips() {
        let spec = spec();
        let encoded = spec.experiment_identity.encoded_value();
        assert_eq!(
            ExperimentIdentity::parse_encoded(&encoded, &spec.condition),
            Ok(spec.experiment_identity.clone())
        );

        let other_condition = ConditionKey::parse("condition-b").unwrap();
        assert_eq!(
            ExperimentIdentity::parse_encoded(&encoded, &other_condition),
            Err("experiment-condition-identity-mismatch")
        );

        for damaged in [
            {
                let mut value = encoded.clone();
                value["provider_manifest_sha256"] = json!("0".repeat(64));
                value
            },
            {
                let mut value = encoded.clone();
                value["audio"]["source_bytes"] = json!(0);
                value
            },
            {
                let mut value = encoded.clone();
                value["audio"]["media_type"] = json!("application/octet-stream");
                value
            },
            {
                let mut value = encoded.clone();
                value["unexpected"] = json!(true);
                value
            },
        ] {
            assert!(ExperimentIdentity::parse_encoded(&damaged, &spec.condition).is_err());
        }
    }

    #[test]
    fn typed_rows_export_no_runtime_generation_or_participant_identity() {
        let record = SessionRecord::PolarAcc(PolarAccBatch {
            frame_sequence: 7,
            samples: vec![PolarAccSample {
                source_sequence: 8,
                sample_index: 0,
                source_time: SourceTimestamp {
                    clock: SourceClock::PolarSensor,
                    value_ns: 900,
                },
                received_source: SourceTimestamp {
                    clock: SourceClock::JavaNanoTime,
                    value_ns: 1_050,
                },
                observed_at: MonotonicNanos::new(1_100),
                xyz_mg: [1.0, 2.0, 3.0],
            }]
            .into_boxed_slice(),
        });
        let text = record.encoded_rows(1, &spec())[0].to_string();
        assert!(text.contains("condition-a"));
        assert!(text.contains("utc-ns-"));
        assert!(!text.contains("participant"));
        assert!(!text.contains("session_id"));
        assert!(!text.contains("generation"));
    }

    #[test]
    fn effective_radius_snapshot_names_both_deformation_envelopes() {
        let snapshot = SessionRecord::EffectiveRadiusSnapshot(EffectiveRadiusSnapshotObservation {
            source_frame: 12,
            observed_at: MonotonicNanos::new(1_300),
            configured_radius_min_m: 0.8,
            configured_radius_max_m: 1.6,
            radius_progress01: 0.75,
            resulting_radius_m: 1.4,
            deformation_progress01: 0.75,
            oblateness: Some(DeformationEnvelopeEndpoints {
                at_radius_min: 0.2,
                at_radius_max: 0.4,
            }),
            axis_profile: Some(DeformationEnvelopeEndpoints {
                at_radius_min: 1.8,
                at_radius_max: 0.9,
            }),
            settings_revision: 4,
            render_session_generation: 2,
            resulting_radius_parameter_source: "app-effective-world-anchor",
        })
        .encoded_rows(2, &spec())
        .remove(0);

        assert_eq!(snapshot["kind"], "effective-radius-snapshot");
        assert_eq!(snapshot["metric"], "effective-radius-snapshot");
        assert_eq!(
            snapshot["metric_status"],
            "actual-runtime-radius-with-configured-deformation-envelopes"
        );
        let approximately = |field: &str, expected: f64| {
            assert!(
                (snapshot[field].as_f64().expect("numeric field") - expected).abs() < 1.0e-6,
                "{field}"
            );
        };
        approximately("configured_radius_min_m", 0.8);
        approximately("configured_radius_max_m", 1.6);
        assert_eq!(snapshot["radius_progress_source"], "runtime-driver-slot-0");
        approximately("resulting_radius_m", 1.4);
        assert!(
            (snapshot["oblateness"]["at_radius_min"]
                .as_f64()
                .expect("oblateness minimum")
                - 0.2)
                .abs()
                < 1.0e-6
        );
        assert!(
            (snapshot["oblateness"]["at_radius_max"]
                .as_f64()
                .expect("oblateness maximum")
                - 0.4)
                .abs()
                < 1.0e-6
        );
        assert!(
            (snapshot["axis_profile"]["at_radius_min"]
                .as_f64()
                .expect("axis-profile minimum")
                - 1.8)
                .abs()
                < 1.0e-6
        );
        assert!(
            (snapshot["axis_profile"]["at_radius_max"]
                .as_f64()
                .expect("axis-profile maximum")
                - 0.9)
                .abs()
                < 1.0e-6
        );
        assert!(snapshot.get("minimum_m").is_none());
        assert!(snapshot.get("mean_m").is_none());
        assert!(snapshot.get("maximum_m").is_none());
    }

    #[test]
    fn effective_radius_snapshot_rejects_non_finite_or_out_of_contract_values() {
        let record = |resulting_radius_m, radius_progress01, deformation_progress01| {
            SessionRecord::EffectiveRadiusSnapshot(EffectiveRadiusSnapshotObservation {
                source_frame: 1,
                observed_at: MonotonicNanos::new(1_000),
                configured_radius_min_m: 0.8,
                configured_radius_max_m: 1.6,
                radius_progress01,
                resulting_radius_m,
                deformation_progress01,
                oblateness: None,
                axis_profile: None,
                settings_revision: 1,
                render_session_generation: 1,
                resulting_radius_parameter_source: "app-effective-world-anchor",
            })
        };
        assert!(record(1.2, 0.5, 0.5).floats_are_finite());
        for damaged in [
            record(f32::NAN, 0.5, 0.5),
            record(f32::INFINITY, 0.5, 0.5),
            record(f32::NEG_INFINITY, 0.5, 0.5),
            record(0.0, 0.5, 0.5),
            record(1.2, -0.01, 0.5),
            record(1.2, 1.01, 0.5),
            record(1.2, 0.5, -0.01),
            record(1.2, 0.5, 1.01),
        ] {
            assert!(!damaged.floats_are_finite());
        }
    }
}
