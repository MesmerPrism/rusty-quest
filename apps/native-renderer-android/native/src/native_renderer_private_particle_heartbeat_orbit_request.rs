//! Fenced runtime requests for the private Polar-RR orbit boost.
//!
//! The request is one atomic Android-property value. It is admitted only for
//! the current OpenXR session and becomes effective only after a visible
//! private-particle frame using the requested mode has been submitted.

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
pub(crate) enum PrivateParticleHeartbeatOrbitMode {
    Enabled,
    Disabled,
    Default,
}

impl PrivateParticleHeartbeatOrbitMode {
    fn parse(value: &str) -> Option<Self> {
        match value {
            "enabled" => Some(Self::Enabled),
            "disabled" => Some(Self::Disabled),
            "default" => Some(Self::Default),
            _ => None,
        }
    }

    pub(crate) fn override_value(self) -> Option<bool> {
        match self {
            Self::Enabled => Some(true),
            Self::Disabled => Some(false),
            Self::Default => None,
        }
    }

    fn marker_name(self) -> &'static str {
        match self {
            Self::Enabled => "enabled",
            Self::Disabled => "disabled",
            Self::Default => "default",
        }
    }

    fn matches_effective(self, effective: bool) -> bool {
        match self {
            Self::Enabled => effective,
            Self::Disabled => !effective,
            Self::Default => true,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PrivateParticleHeartbeatOrbitRequest {
    session_id: String,
    generation: u64,
    request_id: String,
    mode: PrivateParticleHeartbeatOrbitMode,
}

impl PrivateParticleHeartbeatOrbitRequest {
    pub(crate) fn override_value(&self) -> Option<bool> {
        self.mode.override_value()
    }

    pub(crate) fn marker_fields(&self, state: &str) -> String {
        format!(
            "privateParticleHeartbeatOrbitRequestSession={} privateParticleHeartbeatOrbitRequestGeneration={} privateParticleHeartbeatOrbitRequestId={} privateParticleHeartbeatOrbitRequested={} privateParticleHeartbeatOrbitRequestState={}",
            self.session_id,
            self.generation,
            self.request_id,
            self.mode.marker_name(),
            state
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PrivateParticleHeartbeatOrbitRejection {
    reason: &'static str,
    request: Option<PrivateParticleHeartbeatOrbitRequest>,
}

impl PrivateParticleHeartbeatOrbitRejection {
    pub(crate) fn marker_fields(&self) -> String {
        let request_fields = self
            .request
            .as_ref()
            .map(|request| request.marker_fields("rejected"))
            .unwrap_or_else(|| {
                "privateParticleHeartbeatOrbitRequestSession=unavailable privateParticleHeartbeatOrbitRequestGeneration=unavailable privateParticleHeartbeatOrbitRequestId=unavailable privateParticleHeartbeatOrbitRequested=unavailable privateParticleHeartbeatOrbitRequestState=rejected".to_string()
            });
        format!(
            "{} privateParticleHeartbeatOrbitRequestRejectReason={}",
            request_fields, self.reason
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PrivateParticleHeartbeatOrbitEffectiveReceipt {
    request: PrivateParticleHeartbeatOrbitRequest,
    effective_enabled: bool,
    frame: u64,
}

impl PrivateParticleHeartbeatOrbitEffectiveReceipt {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "{} privateParticleHeartbeatOrbitEffectiveEnabled={} privateParticleHeartbeatOrbitRendererPrepared=true privateParticleHeartbeatOrbitSubmittedFrame={}",
            self.request.marker_fields("effective"),
            self.effective_enabled,
            self.frame
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum PrivateParticleHeartbeatOrbitRequestObservation {
    NoChange,
    Accepted(PrivateParticleHeartbeatOrbitRequest),
    Rejected(PrivateParticleHeartbeatOrbitRejection),
}

#[derive(Clone, Debug, Default)]
pub(crate) struct PrivateParticleHeartbeatOrbitRequestState {
    session_id: Option<String>,
    highest_accepted_generation: u64,
    active_request: Option<PrivateParticleHeartbeatOrbitRequest>,
    effective_request: Option<PrivateParticleHeartbeatOrbitRequest>,
    prepared_frame: Option<(u64, bool, PrivateParticleHeartbeatOrbitRequest)>,
    last_observed_payload: Option<String>,
}

impl PrivateParticleHeartbeatOrbitRequestState {
    pub(crate) fn begin_session(&mut self) {
        let session_id = generated_session_id();
        self.begin_session_with_id(&session_id)
            .expect("generated heartbeat-orbit session identity must be valid");
    }

    pub(crate) fn begin_session_with_id(&mut self, session_id: &str) -> Result<String, String> {
        if !is_lower_hex(session_id, SESSION_ID_HEX_LEN) || is_all_zero_hex(session_id) {
            return Err(
                "heartbeat-orbit session identity must be a nonzero 16-digit lowercase hex value"
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
            "privateParticleHeartbeatOrbitRequestSession={} privateParticleHeartbeatOrbitRequestGeneration=0 privateParticleHeartbeatOrbitRequestId=unset privateParticleHeartbeatOrbitRequested=unset privateParticleHeartbeatOrbitRequestState=ready",
            session
        )
    }

    pub(crate) fn observe_property(
        &mut self,
        property_value: Option<String>,
    ) -> PrivateParticleHeartbeatOrbitRequestObservation {
        let Some(payload) = property_value.map(|value| value.trim().to_owned()) else {
            self.last_observed_payload = None;
            return PrivateParticleHeartbeatOrbitRequestObservation::NoChange;
        };
        if payload.is_empty() {
            self.last_observed_payload = None;
            return PrivateParticleHeartbeatOrbitRequestObservation::NoChange;
        }
        if self.last_observed_payload.as_deref() == Some(payload.as_str()) {
            return PrivateParticleHeartbeatOrbitRequestObservation::NoChange;
        }
        self.last_observed_payload = Some(payload.clone());

        let request = match parse_request(&payload) {
            Ok(request) => request,
            Err(reason) => {
                return PrivateParticleHeartbeatOrbitRequestObservation::Rejected(
                    PrivateParticleHeartbeatOrbitRejection {
                        reason,
                        request: None,
                    },
                );
            }
        };
        let Some(session_id) = self.session_id.as_deref() else {
            return PrivateParticleHeartbeatOrbitRequestObservation::Rejected(
                PrivateParticleHeartbeatOrbitRejection {
                    reason: "no-current-openxr-session",
                    request: Some(request),
                },
            );
        };
        if request.session_id != session_id {
            return PrivateParticleHeartbeatOrbitRequestObservation::Rejected(
                PrivateParticleHeartbeatOrbitRejection {
                    reason: "session-mismatch",
                    request: Some(request),
                },
            );
        }
        if request.generation <= self.highest_accepted_generation {
            return PrivateParticleHeartbeatOrbitRequestObservation::Rejected(
                PrivateParticleHeartbeatOrbitRejection {
                    reason: "stale-or-replayed-generation",
                    request: Some(request),
                },
            );
        }

        self.highest_accepted_generation = request.generation;
        self.active_request = Some(request.clone());
        self.effective_request = None;
        self.prepared_frame = None;
        PrivateParticleHeartbeatOrbitRequestObservation::Accepted(request)
    }

    pub(crate) fn active_override(&self) -> Option<bool> {
        self.active_request
            .as_ref()
            .and_then(PrivateParticleHeartbeatOrbitRequest::override_value)
    }

    pub(crate) fn clear_request_for_panel_authority(&mut self) {
        self.active_request = None;
        self.effective_request = None;
        self.prepared_frame = None;
    }

    pub(crate) fn note_renderer_prepared_frame(
        &mut self,
        frame: u64,
        effective_enabled: bool,
        frame_uses_private_particles: bool,
    ) {
        let Some(request) = self.active_request.as_ref() else {
            return;
        };
        if self.effective_request.as_ref() == Some(request)
            || !frame_uses_private_particles
            || !request.mode.matches_effective(effective_enabled)
        {
            return;
        }
        self.prepared_frame = Some((frame, effective_enabled, request.clone()));
    }

    pub(crate) fn confirm_submitted_frame(
        &mut self,
        frame: u64,
    ) -> Option<PrivateParticleHeartbeatOrbitEffectiveReceipt> {
        let (prepared_frame, effective_enabled, request) = self.prepared_frame.take()?;
        if prepared_frame != frame || self.active_request.as_ref() != Some(&request) {
            return None;
        }
        self.effective_request = Some(request.clone());
        Some(PrivateParticleHeartbeatOrbitEffectiveReceipt {
            request,
            effective_enabled,
            frame,
        })
    }
}

fn parse_request(payload: &str) -> Result<PrivateParticleHeartbeatOrbitRequest, &'static str> {
    let fields: Vec<_> = payload.split('|').collect();
    if fields.len() != 5 || fields[0] != REQUEST_VERSION {
        return Err("malformed-envelope");
    }
    if !is_lower_hex(fields[1], SESSION_ID_HEX_LEN) || is_all_zero_hex(fields[1]) {
        return Err("invalid-session");
    }
    if !is_lower_hex(fields[2], GENERATION_HEX_LEN) {
        return Err("invalid-generation");
    }
    let generation = u64::from_str_radix(fields[2], 16).map_err(|_| "invalid-generation")?;
    if generation == 0 {
        return Err("zero-generation");
    }
    if !is_lower_hex(fields[3], REQUEST_ID_HEX_LEN) || is_all_zero_hex(fields[3]) {
        return Err("invalid-request-id");
    }
    let mode = PrivateParticleHeartbeatOrbitMode::parse(fields[4]).ok_or("invalid-mode")?;
    Ok(PrivateParticleHeartbeatOrbitRequest {
        session_id: fields[1].to_owned(),
        generation,
        request_id: fields[3].to_owned(),
        mode,
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

    fn payload(session: &str, generation: u64, request: &str, mode: &str) -> String {
        format!("v1|{session}|{generation:016x}|{request}|{mode}")
    }

    #[test]
    fn unset_default_is_neutral() {
        let mut state = PrivateParticleHeartbeatOrbitRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert_eq!(
            state.observe_property(None),
            PrivateParticleHeartbeatOrbitRequestObservation::NoChange
        );
        assert_eq!(state.active_override(), None);
    }

    #[test]
    fn accepted_request_needs_matching_visible_submitted_frame() {
        let mut state = PrivateParticleHeartbeatOrbitRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "enabled"))),
            PrivateParticleHeartbeatOrbitRequestObservation::Accepted(_)
        ));
        assert_eq!(state.active_override(), Some(true));
        assert_eq!(state.confirm_submitted_frame(7), None);
        state.note_renderer_prepared_frame(7, true, false);
        assert_eq!(state.confirm_submitted_frame(7), None);
        state.note_renderer_prepared_frame(8, true, true);
        let receipt = state.confirm_submitted_frame(8).unwrap();
        let marker = receipt.marker_fields();
        assert!(marker.contains("privateParticleHeartbeatOrbitEffectiveEnabled=true"));
        assert!(marker.contains("privateParticleHeartbeatOrbitSubmittedFrame=8"));
        assert!(state.confirm_submitted_frame(9).is_none());
    }

    #[test]
    fn invalid_stale_and_prior_session_requests_do_not_change_active_mode() {
        let mut state = PrivateParticleHeartbeatOrbitRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "enabled"))),
            PrivateParticleHeartbeatOrbitRequestObservation::Accepted(_)
        ));
        for invalid in [
            "malformed".to_string(),
            payload(SESSION_A, 0, REQUEST_B, "disabled"),
            payload(SESSION_B, 2, REQUEST_B, "disabled"),
            payload(SESSION_A, 2, "bad", "disabled"),
            payload(SESSION_A, 2, REQUEST_B, "maybe"),
        ] {
            assert!(matches!(
                state.observe_property(Some(invalid)),
                PrivateParticleHeartbeatOrbitRequestObservation::Rejected(_)
            ));
            assert_eq!(state.active_override(), Some(true));
        }
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "enabled"))),
            PrivateParticleHeartbeatOrbitRequestObservation::Rejected(_)
        ));
    }

    #[test]
    fn new_session_and_panel_authority_clear_runtime_override() {
        let mut state = PrivateParticleHeartbeatOrbitRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "enabled")));
        state.clear_request_for_panel_authority();
        assert_eq!(state.active_override(), None);
        state.observe_property(Some(payload(SESSION_A, 2, REQUEST_B, "disabled")));
        state.begin_session_with_id(SESSION_B).unwrap();
        assert_eq!(state.active_override(), None);
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 3, REQUEST_A, "enabled"))),
            PrivateParticleHeartbeatOrbitRequestObservation::Rejected(_)
        ));
    }

    #[test]
    fn default_request_restores_underlying_owner_and_reports_actual_result() {
        let mut state = PrivateParticleHeartbeatOrbitRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "default")));
        assert_eq!(state.active_override(), None);
        state.note_renderer_prepared_frame(4, false, true);
        assert!(state.confirm_submitted_frame(4).is_some());
    }
}
