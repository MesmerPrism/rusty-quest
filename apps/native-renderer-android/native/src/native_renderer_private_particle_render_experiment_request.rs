//! Fenced, app-owned render-coverage performance A/B requests.
//!
//! This deliberately controls only primitive coverage and mask discard policy.
//! It does not alter particle simulation, counts, sizes, colour/material, or
//! the packaged startup mode. A request becomes effective only once it was used
//! by a renderer-prepared frame which the OpenXR layer subsequently submitted.

use std::{
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

const REQUEST_VERSION: &str = "v1";
const SESSION_ID_HEX_LEN: usize = 16;
const GENERATION_HEX_LEN: usize = 16;
const REQUEST_ID_HEX_LEN: usize = 32;
static SESSION_ID_COUNTER: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PrivateParticleRenderExperimentPreset {
    ControlSquareDiscard,
    SquareNoDiscard,
    StaticRingAnnulus12,
}

impl PrivateParticleRenderExperimentPreset {
    #[cfg(test)]
    pub(crate) fn wire_code(self) -> &'static str {
        match self {
            Self::ControlSquareDiscard => "sq-d",
            Self::SquareNoDiscard => "sq-nd",
            Self::StaticRingAnnulus12 => "ann12-d",
        }
    }

    pub(crate) fn marker_name(self) -> &'static str {
        match self {
            Self::ControlSquareDiscard => "square-discard",
            Self::SquareNoDiscard => "square-no-discard",
            Self::StaticRingAnnulus12 => "static-ring-annulus-12-discard",
        }
    }

    pub(crate) const fn geometry_code(self) -> u32 {
        match self {
            Self::ControlSquareDiscard | Self::SquareNoDiscard => 0,
            Self::StaticRingAnnulus12 => 1,
        }
    }

    pub(crate) const fn mask_discard_code(self) -> u32 {
        match self {
            Self::ControlSquareDiscard | Self::StaticRingAnnulus12 => 0,
            Self::SquareNoDiscard => 1,
        }
    }

    pub(crate) const fn vertices_per_instance(self) -> u32 {
        match self {
            Self::ControlSquareDiscard | Self::SquareNoDiscard => 6,
            Self::StaticRingAnnulus12 => 72,
        }
    }

    pub(crate) const fn geometry_marker(self) -> &'static str {
        match self {
            Self::ControlSquareDiscard | Self::SquareNoDiscard => "square-billboard",
            Self::StaticRingAnnulus12 => "static-ring-annulus-12",
        }
    }

    pub(crate) const fn discard_marker(self) -> &'static str {
        match self {
            Self::ControlSquareDiscard | Self::StaticRingAnnulus12 => "discard-near-zero-mask",
            Self::SquareNoDiscard => "zero-output-no-discard",
        }
    }

    pub(crate) const fn requires_static_ring(self) -> bool {
        matches!(self, Self::StaticRingAnnulus12)
    }

    fn parse_wire_code(value: &str) -> Option<Self> {
        match value {
            "sq-d" => Some(Self::ControlSquareDiscard),
            "sq-nd" => Some(Self::SquareNoDiscard),
            "ann12-d" => Some(Self::StaticRingAnnulus12),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleRenderExperimentRequest {
    session_id: String,
    generation: u64,
    request_id: String,
    preset: PrivateParticleRenderExperimentPreset,
}

impl PrivateParticleRenderExperimentRequest {
    pub(crate) fn preset(&self) -> PrivateParticleRenderExperimentPreset {
        self.preset
    }

    pub(crate) fn marker_fields(&self, state: &str) -> String {
        format!(
            "privateParticleRenderExperimentSession={} privateParticleRenderExperimentGeneration={} privateParticleRenderExperimentRequestId={} privateParticleRenderExperimentPresetRequested={} privateParticleRenderExperimentState={}",
            self.session_id,
            self.generation,
            self.request_id,
            self.preset.marker_name(),
            state
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleRenderExperimentRejection {
    reason: &'static str,
    request: Option<PrivateParticleRenderExperimentRequest>,
}

impl PrivateParticleRenderExperimentRejection {
    pub(crate) fn marker_fields(&self) -> String {
        let request_fields = self
            .request
            .as_ref()
            .map(|request| request.marker_fields("rejected"))
            .unwrap_or_else(|| {
                "privateParticleRenderExperimentSession=unavailable privateParticleRenderExperimentGeneration=unavailable privateParticleRenderExperimentRequestId=unavailable privateParticleRenderExperimentPresetRequested=unavailable privateParticleRenderExperimentState=rejected".to_owned()
            });
        format!(
            "{request_fields} privateParticleRenderExperimentRejectReason={}",
            self.reason
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleRenderExperimentEffectiveReceipt {
    request: PrivateParticleRenderExperimentRequest,
    frame: u64,
}

impl PrivateParticleRenderExperimentEffectiveReceipt {
    pub(crate) fn marker_fields(&self) -> String {
        let preset = self.request.preset;
        format!(
            "{} privateParticleRenderExperimentGeometryEffective={} privateParticleRenderExperimentMaskDiscardEffective={} privateParticleRenderExperimentVerticesPerInstance={} privateParticleRenderExperimentStaticRingRequired={} privateParticleRenderExperimentRendererPrepared=true privateParticleRenderExperimentSubmittedFrame={}",
            self.request.marker_fields("effective"),
            preset.geometry_marker(),
            preset.discard_marker(),
            preset.vertices_per_instance(),
            preset.requires_static_ring(),
            self.frame
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum PrivateParticleRenderExperimentRequestObservation {
    NoChange,
    Accepted(PrivateParticleRenderExperimentRequest),
    Rejected(PrivateParticleRenderExperimentRejection),
}

#[derive(Clone, Debug, Default)]
pub(crate) struct PrivateParticleRenderExperimentRequestState {
    session_id: Option<String>,
    highest_accepted_generation: u64,
    active_request: Option<PrivateParticleRenderExperimentRequest>,
    effective_request: Option<PrivateParticleRenderExperimentRequest>,
    prepared_frame: Option<(u64, PrivateParticleRenderExperimentRequest)>,
    last_observed_payload: Option<String>,
}

impl PrivateParticleRenderExperimentRequestState {
    pub(crate) fn begin_session(&mut self) {
        let session_id = generated_session_id();
        self.begin_session_with_id(&session_id)
            .expect("generated private-particle render experiment session identity must be valid");
    }

    pub(crate) fn begin_session_with_id(&mut self, session_id: &str) -> Result<String, String> {
        if !is_lower_hex(session_id, SESSION_ID_HEX_LEN) || is_all_zero_hex(session_id) {
            return Err(
                "render experiment session identity must be a nonzero 16-digit lowercase hex value"
                    .to_owned(),
            );
        }
        self.session_id = Some(session_id.to_owned());
        self.highest_accepted_generation = 0;
        self.active_request = None;
        self.effective_request = None;
        self.prepared_frame = None;
        self.last_observed_payload = None;
        Ok(session_id.to_owned())
    }

    pub(crate) fn session_marker_fields(&self) -> String {
        let session = self.session_id.as_deref().unwrap_or("unavailable");
        format!(
            "privateParticleRenderExperimentSession={} privateParticleRenderExperimentGeneration=0 privateParticleRenderExperimentRequestId=unset privateParticleRenderExperimentPresetRequested=unset privateParticleRenderExperimentState=ready",
            session
        )
    }

    pub(crate) fn observe_property(
        &mut self,
        property_value: Option<String>,
        static_ring_enabled: bool,
    ) -> PrivateParticleRenderExperimentRequestObservation {
        let Some(payload) = property_value.map(|value| value.trim().to_owned()) else {
            self.last_observed_payload = None;
            return PrivateParticleRenderExperimentRequestObservation::NoChange;
        };
        if payload.is_empty() {
            self.last_observed_payload = None;
            return PrivateParticleRenderExperimentRequestObservation::NoChange;
        }
        if self.last_observed_payload.as_deref() == Some(payload.as_str()) {
            return PrivateParticleRenderExperimentRequestObservation::NoChange;
        }
        self.last_observed_payload = Some(payload.clone());
        let request = match parse_request(&payload) {
            Ok(request) => request,
            Err(reason) => {
                return PrivateParticleRenderExperimentRequestObservation::Rejected(
                    PrivateParticleRenderExperimentRejection {
                        reason,
                        request: None,
                    },
                );
            }
        };
        let Some(session_id) = self.session_id.as_deref() else {
            return rejected("no-current-openxr-session", request);
        };
        if request.session_id != session_id {
            return rejected("session-mismatch", request);
        }
        if request.generation <= self.highest_accepted_generation {
            return rejected("stale-or-replayed-generation", request);
        }
        if request.preset.requires_static_ring() && !static_ring_enabled {
            return rejected("static-ring-required", request);
        }
        self.highest_accepted_generation = request.generation;
        self.active_request = Some(request.clone());
        self.effective_request = None;
        self.prepared_frame = None;
        PrivateParticleRenderExperimentRequestObservation::Accepted(request)
    }

    pub(crate) fn active_preset(&self) -> Option<PrivateParticleRenderExperimentPreset> {
        self.active_request
            .as_ref()
            .map(PrivateParticleRenderExperimentRequest::preset)
    }

    pub(crate) fn note_renderer_prepared_frame(
        &mut self,
        frame: u64,
        actual_preset: Option<PrivateParticleRenderExperimentPreset>,
        frame_uses_private_particles: bool,
    ) {
        let Some(request) = self.active_request.as_ref() else {
            return;
        };
        if self.effective_request.as_ref() == Some(request)
            || !frame_uses_private_particles
            || actual_preset != Some(request.preset)
        {
            return;
        }
        self.prepared_frame = Some((frame, request.clone()));
    }

    pub(crate) fn confirm_submitted_frame(
        &mut self,
        frame: u64,
    ) -> Option<PrivateParticleRenderExperimentEffectiveReceipt> {
        let (prepared_frame, request) = self.prepared_frame.take()?;
        if prepared_frame != frame || self.active_request.as_ref() != Some(&request) {
            return None;
        }
        self.effective_request = Some(request.clone());
        Some(PrivateParticleRenderExperimentEffectiveReceipt { request, frame })
    }
}

fn rejected(
    reason: &'static str,
    request: PrivateParticleRenderExperimentRequest,
) -> PrivateParticleRenderExperimentRequestObservation {
    PrivateParticleRenderExperimentRequestObservation::Rejected(
        PrivateParticleRenderExperimentRejection {
            reason,
            request: Some(request),
        },
    )
}

fn parse_request(payload: &str) -> Result<PrivateParticleRenderExperimentRequest, &'static str> {
    let fields: Vec<_> = payload.split('|').collect();
    if fields.len() != 5 || fields[0] != REQUEST_VERSION {
        return Err("malformed-envelope");
    }
    let session_id = fields[1];
    if !is_lower_hex(session_id, SESSION_ID_HEX_LEN) || is_all_zero_hex(session_id) {
        return Err("invalid-session");
    }
    let generation_text = fields[2];
    if !is_lower_hex(generation_text, GENERATION_HEX_LEN) {
        return Err("invalid-generation");
    }
    let generation = u64::from_str_radix(generation_text, 16).map_err(|_| "invalid-generation")?;
    if generation == 0 {
        return Err("zero-generation");
    }
    let request_id = fields[3];
    if !is_lower_hex(request_id, REQUEST_ID_HEX_LEN) || is_all_zero_hex(request_id) {
        return Err("invalid-request-id");
    }
    let preset = PrivateParticleRenderExperimentPreset::parse_wire_code(fields[4])
        .ok_or("unsupported-render-experiment-preset")?;
    Ok(PrivateParticleRenderExperimentRequest {
        session_id: session_id.to_owned(),
        generation,
        request_id: request_id.to_owned(),
        preset,
    })
}

fn is_lower_hex(value: &str, expected_len: usize) -> bool {
    value.len() == expected_len
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn is_all_zero_hex(value: &str) -> bool {
    value.bytes().all(|byte| byte == b'0')
}

fn generated_session_id() -> String {
    let clock = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0_u64, |duration| duration.as_nanos() as u64);
    let counter = SESSION_ID_COUNTER.fetch_add(1, Ordering::Relaxed);
    let mixed = clock.wrapping_mul(0x9e37_79b9_7f4a_7c15).rotate_left(19)
        ^ counter.wrapping_mul(0xd6e8_feb8_6659_fd93);
    format!("{:016x}", mixed.max(1))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SESSION_A: &str = "0123456789abcdef";
    const SESSION_B: &str = "fedcba9876543210";
    const REQUEST_A: &str = "11111111111111111111111111111111";
    const REQUEST_B: &str = "22222222222222222222222222222222";

    fn payload(session: &str, generation: u64, request: &str, preset: &str) -> String {
        format!("v1|{session}|{generation:016x}|{request}|{preset}")
    }

    #[test]
    fn default_is_neutral_square_discard_without_a_request() {
        let mut state = PrivateParticleRenderExperimentRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert_eq!(
            state.observe_property(None, true),
            PrivateParticleRenderExperimentRequestObservation::NoChange
        );
        assert_eq!(state.active_preset(), None);
    }

    #[test]
    fn closed_presets_are_accepted_then_effective_only_after_a_submitted_frame() {
        let mut state = PrivateParticleRenderExperimentRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "ann12-d")), true),
            PrivateParticleRenderExperimentRequestObservation::Accepted(_)
        ));
        let preset = PrivateParticleRenderExperimentPreset::StaticRingAnnulus12;
        state.note_renderer_prepared_frame(7, Some(preset), true);
        let marker = state.confirm_submitted_frame(7).unwrap().marker_fields();
        assert!(marker
            .contains("privateParticleRenderExperimentGeometryEffective=static-ring-annulus-12"));
        assert!(marker.contains("privateParticleRenderExperimentVerticesPerInstance=72"));
        assert!(marker.contains("privateParticleRenderExperimentSubmittedFrame=7"));
    }

    #[test]
    fn stale_invalid_cross_session_and_nonstatic_annulus_requests_fail_closed() {
        let mut state = PrivateParticleRenderExperimentRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "sq-d")), true),
            PrivateParticleRenderExperimentRequestObservation::Accepted(_)
        ));
        for (invalid, static_ring) in [
            (payload(SESSION_A, 1, REQUEST_B, "sq-nd"), true),
            (payload(SESSION_B, 2, REQUEST_B, "sq-nd"), true),
            (payload(SESSION_A, 2, REQUEST_B, "bogus"), true),
            (payload(SESSION_A, 2, REQUEST_B, "ann12-d"), false),
        ] {
            assert!(matches!(
                state.observe_property(Some(invalid), static_ring),
                PrivateParticleRenderExperimentRequestObservation::Rejected(_)
            ));
            assert_eq!(
                state.active_preset(),
                Some(PrivateParticleRenderExperimentPreset::ControlSquareDiscard)
            );
        }
    }

    #[test]
    fn new_session_clears_prior_effective_state_and_requires_current_identity() {
        let mut state = PrivateParticleRenderExperimentRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 9, REQUEST_A, "sq-nd")), true),
            PrivateParticleRenderExperimentRequestObservation::Accepted(_)
        ));
        state.begin_session_with_id(SESSION_B).unwrap();
        assert_eq!(state.active_preset(), None);
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 10, REQUEST_B, "sq-d")), true),
            PrivateParticleRenderExperimentRequestObservation::Rejected(_)
        ));
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_B, 1, REQUEST_B, "sq-d")), true),
            PrivateParticleRenderExperimentRequestObservation::Accepted(_)
        ));
    }

    #[test]
    fn all_wire_envelopes_fit_android_property_transport() {
        for preset in [
            PrivateParticleRenderExperimentPreset::ControlSquareDiscard,
            PrivateParticleRenderExperimentPreset::SquareNoDiscard,
            PrivateParticleRenderExperimentPreset::StaticRingAnnulus12,
        ] {
            let envelope = payload(SESSION_A, 1, REQUEST_A, preset.wire_code());
            assert!(envelope.len() <= 91, "{envelope}");
        }
    }
}
