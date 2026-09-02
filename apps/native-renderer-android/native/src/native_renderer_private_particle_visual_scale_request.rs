//! Fenced, app-owned private-particle visual-scale requests.
//!
//! The legacy visual-scale property remains a compatibility input, but it is
//! deliberately not an acknowledgement protocol.  This module owns the closed
//! one-property request envelope used by the private performance comparison:
//! the request is admitted against one current OpenXR-session identity and is
//! only reported effective after the renderer prepares and submits a frame
//! using that exact value.

use std::{
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

pub(crate) const PRIVATE_PARTICLE_VISUAL_SCALE_MIN: f32 = 0.05;
pub(crate) const PRIVATE_PARTICLE_VISUAL_SCALE_MAX: f32 = 1.0;

const REQUEST_VERSION: &str = "v1";
const SESSION_ID_HEX_LEN: usize = 16;
const GENERATION_HEX_LEN: usize = 16;
const REQUEST_ID_HEX_LEN: usize = 32;
static SESSION_ID_COUNTER: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleVisualScaleRequest {
    session_id: String,
    generation: u64,
    request_id: String,
    scale: f32,
}

impl PrivateParticleVisualScaleRequest {
    pub(crate) fn scale(&self) -> f32 {
        self.scale
    }

    pub(crate) fn marker_fields(&self, state: &str) -> String {
        format!(
            "privateParticleVisualScaleRequestSession={} privateParticleVisualScaleRequestGeneration={} privateParticleVisualScaleRequestId={} privateParticleVisualScaleRequested={:.3} privateParticleVisualScaleRequestState={}",
            self.session_id, self.generation, self.request_id, self.scale, state
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleVisualScaleRejection {
    reason: &'static str,
    request: Option<PrivateParticleVisualScaleRequest>,
}

impl PrivateParticleVisualScaleRejection {
    pub(crate) fn marker_fields(&self) -> String {
        let request_fields = self
            .request
            .as_ref()
            .map(|request| request.marker_fields("rejected"))
            .unwrap_or_else(|| {
                "privateParticleVisualScaleRequestSession=unavailable privateParticleVisualScaleRequestGeneration=unavailable privateParticleVisualScaleRequestId=unavailable privateParticleVisualScaleRequested=unavailable privateParticleVisualScaleRequestState=rejected".to_string()
            });
        format!(
            "{} privateParticleVisualScaleRequestRejectReason={}",
            request_fields, self.reason
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleVisualScaleEffectiveReceipt {
    request: PrivateParticleVisualScaleRequest,
    frame: u64,
}

impl PrivateParticleVisualScaleEffectiveReceipt {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "{} privateParticleVisualScaleEffective={:.3} privateParticleVisualScaleRendererPrepared=true privateParticleVisualScaleSubmittedFrame={}",
            self.request.marker_fields("effective"),
            self.request.scale,
            self.frame,
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum PrivateParticleVisualScaleRequestObservation {
    NoChange,
    Accepted(PrivateParticleVisualScaleRequest),
    Rejected(PrivateParticleVisualScaleRejection),
}

#[derive(Clone, Debug, Default)]
pub(crate) struct PrivateParticleVisualScaleRequestState {
    session_id: Option<String>,
    highest_accepted_generation: u64,
    active_request: Option<PrivateParticleVisualScaleRequest>,
    effective_request: Option<PrivateParticleVisualScaleRequest>,
    prepared_frame: Option<(u64, PrivateParticleVisualScaleRequest)>,
    last_observed_payload: Option<String>,
}

impl PrivateParticleVisualScaleRequestState {
    pub(crate) fn begin_session(&mut self) {
        let session_id = generated_session_id();
        self.begin_session_with_id(&session_id)
            .expect("generated visual-scale session identity must be valid");
    }

    pub(crate) fn begin_session_with_id(&mut self, session_id: &str) -> Result<String, String> {
        if !is_lower_hex(session_id, SESSION_ID_HEX_LEN) || is_all_zero_hex(session_id) {
            return Err(
                "visual-scale session identity must be a nonzero 16-digit lowercase hex value"
                    .to_string(),
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
            "privateParticleVisualScaleRequestSession={} privateParticleVisualScaleRequestGeneration=0 privateParticleVisualScaleRequestId=unset privateParticleVisualScaleRequested=unset privateParticleVisualScaleRequestState=ready",
            session
        )
    }

    pub(crate) fn observe_property(
        &mut self,
        property_value: Option<String>,
    ) -> PrivateParticleVisualScaleRequestObservation {
        let Some(payload) = property_value.map(|value| value.trim().to_owned()) else {
            self.last_observed_payload = None;
            return PrivateParticleVisualScaleRequestObservation::NoChange;
        };
        if payload.is_empty() {
            self.last_observed_payload = None;
            return PrivateParticleVisualScaleRequestObservation::NoChange;
        }
        if self.last_observed_payload.as_deref() == Some(payload.as_str()) {
            return PrivateParticleVisualScaleRequestObservation::NoChange;
        }
        self.last_observed_payload = Some(payload.clone());

        let request = match parse_request(&payload) {
            Ok(request) => request,
            Err(reason) => {
                return PrivateParticleVisualScaleRequestObservation::Rejected(
                    PrivateParticleVisualScaleRejection {
                        reason,
                        request: None,
                    },
                );
            }
        };
        let Some(session_id) = self.session_id.as_deref() else {
            return PrivateParticleVisualScaleRequestObservation::Rejected(
                PrivateParticleVisualScaleRejection {
                    reason: "no-current-openxr-session",
                    request: Some(request),
                },
            );
        };
        if request.session_id != session_id {
            return PrivateParticleVisualScaleRequestObservation::Rejected(
                PrivateParticleVisualScaleRejection {
                    reason: "session-mismatch",
                    request: Some(request),
                },
            );
        }
        if request.generation <= self.highest_accepted_generation {
            return PrivateParticleVisualScaleRequestObservation::Rejected(
                PrivateParticleVisualScaleRejection {
                    reason: "stale-or-replayed-generation",
                    request: Some(request),
                },
            );
        }

        self.highest_accepted_generation = request.generation;
        self.active_request = Some(request.clone());
        self.effective_request = None;
        self.prepared_frame = None;
        PrivateParticleVisualScaleRequestObservation::Accepted(request)
    }

    pub(crate) fn active_scale(&self) -> Option<f32> {
        self.active_request
            .as_ref()
            .map(PrivateParticleVisualScaleRequest::scale)
    }

    pub(crate) fn note_renderer_prepared_frame(
        &mut self,
        frame: u64,
        actual_scale: f32,
        frame_uses_private_particles: bool,
    ) {
        let Some(request) = self.active_request.as_ref() else {
            return;
        };
        if self.effective_request.as_ref() == Some(request)
            || !frame_uses_private_particles
            || !scale_matches(actual_scale, request.scale)
        {
            return;
        }
        self.prepared_frame = Some((frame, request.clone()));
    }

    pub(crate) fn confirm_submitted_frame(
        &mut self,
        frame: u64,
    ) -> Option<PrivateParticleVisualScaleEffectiveReceipt> {
        let (prepared_frame, request) = self.prepared_frame.take()?;
        if prepared_frame != frame || self.active_request.as_ref() != Some(&request) {
            return None;
        }
        self.effective_request = Some(request.clone());
        Some(PrivateParticleVisualScaleEffectiveReceipt { request, frame })
    }
}

fn parse_request(payload: &str) -> Result<PrivateParticleVisualScaleRequest, &'static str> {
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
    let scale = fields[4].parse::<f32>().map_err(|_| "invalid-scale")?;
    if !scale.is_finite() {
        return Err("nonfinite-scale");
    }
    if !(PRIVATE_PARTICLE_VISUAL_SCALE_MIN..=PRIVATE_PARTICLE_VISUAL_SCALE_MAX).contains(&scale) {
        return Err("out-of-range-scale");
    }
    Ok(PrivateParticleVisualScaleRequest {
        session_id: session_id.to_owned(),
        generation,
        request_id: request_id.to_owned(),
        scale,
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

fn scale_matches(left: f32, right: f32) -> bool {
    left.is_finite() && right.is_finite() && (left - right).abs() <= f32::EPSILON
}

fn generated_session_id() -> String {
    let clock = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0_u64, |duration| duration.as_nanos() as u64);
    let counter = SESSION_ID_COUNTER.fetch_add(1, Ordering::Relaxed);
    let mixed = clock.wrapping_mul(0x9e37_79b9_7f4a_7c15).rotate_left(17)
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

    fn payload(session: &str, generation: u64, request: &str, scale: &str) -> String {
        format!("v1|{session}|{generation:016x}|{request}|{scale}")
    }

    #[test]
    fn empty_default_does_not_change_the_legacy_scale_path() {
        let mut state = PrivateParticleVisualScaleRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert_eq!(
            state.observe_property(None),
            PrivateParticleVisualScaleRequestObservation::NoChange
        );
        assert_eq!(state.active_scale(), None);
    }

    #[test]
    fn accepted_request_is_fenced_then_effective_only_after_submitted_frame() {
        let mut state = PrivateParticleVisualScaleRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        let accepted = state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "1.000")));
        assert!(matches!(
            accepted,
            PrivateParticleVisualScaleRequestObservation::Accepted(_)
        ));
        assert_eq!(state.active_scale(), Some(1.0));
        assert_eq!(state.confirm_submitted_frame(7), None);

        state.note_renderer_prepared_frame(7, 1.0, true);
        let effective = state.confirm_submitted_frame(7).unwrap();
        let marker = effective.marker_fields();
        assert!(
            marker.contains("privateParticleVisualScaleRequestId=11111111111111111111111111111111")
        );
        assert!(marker.contains("privateParticleVisualScaleEffective=1.000"));
        assert!(marker.contains("privateParticleVisualScaleSubmittedFrame=7"));
        assert!(state.confirm_submitted_frame(8).is_none());
    }

    #[test]
    fn malformed_zero_generation_session_mismatch_nonfinite_and_out_of_range_requests_leave_effective_scale_unchanged(
    ) {
        let mut state = PrivateParticleVisualScaleRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "0.700"))),
            PrivateParticleVisualScaleRequestObservation::Accepted(_)
        ));
        state.note_renderer_prepared_frame(1, 0.7, true);
        state.confirm_submitted_frame(1).unwrap();

        for invalid in [
            "v2|0123456789abcdef|0000000000000002|22222222222222222222222222222222|1.000"
                .to_string(),
            "v1|bad|0000000000000002|11111111111111111111111111111111|1.000".to_string(),
            payload(SESSION_A, 0, REQUEST_B, "1.000"),
            payload(SESSION_A, 2, "not-a-valid-request-id", "1.000"),
            payload(SESSION_B, 2, REQUEST_B, "1.000"),
            payload(SESSION_A, 2, REQUEST_B, "NaN"),
            payload(SESSION_A, 2, REQUEST_B, "1.001"),
        ] {
            assert!(matches!(
                state.observe_property(Some(invalid)),
                PrivateParticleVisualScaleRequestObservation::Rejected(_)
            ));
            assert_eq!(state.active_scale(), Some(0.7));
        }
    }

    #[test]
    fn stale_duplicate_and_prior_session_replays_are_rejected_without_reapplication() {
        let mut state = PrivateParticleVisualScaleRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        let first = payload(SESSION_A, 1, REQUEST_A, "1.000");
        assert!(matches!(
            state.observe_property(Some(first.clone())),
            PrivateParticleVisualScaleRequestObservation::Accepted(_)
        ));
        state.note_renderer_prepared_frame(3, 1.0, true);
        state.confirm_submitted_frame(3).unwrap();
        assert_eq!(
            state.observe_property(Some(first.clone())),
            PrivateParticleVisualScaleRequestObservation::NoChange
        );
        state.observe_property(None);
        assert!(matches!(
            state.observe_property(Some(first)),
            PrivateParticleVisualScaleRequestObservation::Rejected(_)
        ));

        state.begin_session_with_id(SESSION_B).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 2, REQUEST_B, "1.000"))),
            PrivateParticleVisualScaleRequestObservation::Rejected(_)
        ));
        assert_eq!(state.active_scale(), None);
    }

    #[test]
    fn fresh_session_resets_generation_and_requires_its_own_frame_evidence() {
        let mut state = PrivateParticleVisualScaleRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 9, REQUEST_A, "1.000"))),
            PrivateParticleVisualScaleRequestObservation::Accepted(_)
        ));
        state.note_renderer_prepared_frame(4, 1.0, true);
        state.confirm_submitted_frame(4).unwrap();

        state.begin_session_with_id(SESSION_B).unwrap();
        assert!(state
            .session_marker_fields()
            .contains("privateParticleVisualScaleRequestGeneration=0"));
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_B, 1, REQUEST_B, "0.700"))),
            PrivateParticleVisualScaleRequestObservation::Accepted(_)
        ));
        state.note_renderer_prepared_frame(5, 0.7, false);
        assert_eq!(state.confirm_submitted_frame(5), None);
        state.note_renderer_prepared_frame(6, 0.7, true);
        assert!(state.confirm_submitted_frame(6).is_some());
    }
}
