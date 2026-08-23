//! Fenced, app-owned private-particle material A/B requests.
//!
//! The request is deliberately a closed preset rather than a collection of
//! independently sampled blend, opacity, depth, and facing properties. That
//! keeps an A/B window attributable to one material decision and proves the
//! selected preset only after it has rendered in a submitted frame.

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
pub(crate) enum PrivateParticleMaterialPreset {
    CurrentAdditive,
    PremultipliedAlphaOver,
    PremultipliedAlphaOverDepthFade,
    PremultipliedAlphaOverDepthFacingFade,
    AkdMaterialEmulation,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PrivateParticleMaterialPresetParameters {
    pub(crate) uses_premultiplied_alpha_over: bool,
    pub(crate) opacity: f32,
    pub(crate) output_alpha_scale: f32,
    pub(crate) depth_suppression_strength: f32,
    pub(crate) rgb_alpha_coupling: f32,
    pub(crate) facing_attenuation_strength: f32,
}

impl PrivateParticleMaterialPreset {
    #[cfg(test)]
    pub(crate) fn wire_code(self) -> &'static str {
        match self {
            Self::CurrentAdditive => "add",
            Self::PremultipliedAlphaOver => "over",
            Self::PremultipliedAlphaOverDepthFade => "over-d",
            Self::PremultipliedAlphaOverDepthFacingFade => "over-df",
            Self::AkdMaterialEmulation => "akd",
        }
    }

    pub(crate) fn marker_name(self) -> &'static str {
        match self {
            Self::CurrentAdditive => "current-additive",
            Self::PremultipliedAlphaOver => "premultiplied-alpha-over",
            Self::PremultipliedAlphaOverDepthFade => "premultiplied-alpha-over-depth",
            Self::PremultipliedAlphaOverDepthFacingFade => "premultiplied-alpha-over-depth-facing",
            Self::AkdMaterialEmulation => "akd-material-emulation",
        }
    }

    /// The complete material envelope for a closed A/B preset. Keeping these
    /// coefficients beside the wire parser makes the accepted request and the
    /// GPU-applied effective material one contract.
    pub(crate) const fn parameters(self) -> PrivateParticleMaterialPresetParameters {
        match self {
            Self::CurrentAdditive => PrivateParticleMaterialPresetParameters {
                uses_premultiplied_alpha_over: false,
                opacity: 1.0,
                output_alpha_scale: 1.0,
                depth_suppression_strength: 0.0,
                rgb_alpha_coupling: 0.0,
                facing_attenuation_strength: 0.0,
            },
            Self::PremultipliedAlphaOver => PrivateParticleMaterialPresetParameters {
                uses_premultiplied_alpha_over: true,
                opacity: 1.0,
                output_alpha_scale: 1.0,
                depth_suppression_strength: 0.0,
                rgb_alpha_coupling: 0.0,
                facing_attenuation_strength: 0.0,
            },
            Self::PremultipliedAlphaOverDepthFade => PrivateParticleMaterialPresetParameters {
                uses_premultiplied_alpha_over: true,
                opacity: 1.0,
                output_alpha_scale: 1.0,
                depth_suppression_strength: 1.5,
                rgb_alpha_coupling: 0.0,
                facing_attenuation_strength: 0.0,
            },
            Self::PremultipliedAlphaOverDepthFacingFade => {
                PrivateParticleMaterialPresetParameters {
                    uses_premultiplied_alpha_over: true,
                    opacity: 1.0,
                    output_alpha_scale: 1.0,
                    depth_suppression_strength: 1.5,
                    rgb_alpha_coupling: 0.0,
                    facing_attenuation_strength: 0.20,
                }
            }
            Self::AkdMaterialEmulation => PrivateParticleMaterialPresetParameters {
                uses_premultiplied_alpha_over: true,
                opacity: 0.36,
                output_alpha_scale: 0.45,
                depth_suppression_strength: 1.5,
                rgb_alpha_coupling: 0.0,
                facing_attenuation_strength: 0.20,
            },
        }
    }

    fn parse_wire_code(value: &str) -> Option<Self> {
        match value {
            "add" => Some(Self::CurrentAdditive),
            "over" => Some(Self::PremultipliedAlphaOver),
            "over-d" => Some(Self::PremultipliedAlphaOverDepthFade),
            "over-df" => Some(Self::PremultipliedAlphaOverDepthFacingFade),
            "akd" => Some(Self::AkdMaterialEmulation),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleMaterialRequest {
    session_id: String,
    generation: u64,
    request_id: String,
    preset: PrivateParticleMaterialPreset,
}

impl PrivateParticleMaterialRequest {
    pub(crate) fn preset(&self) -> PrivateParticleMaterialPreset {
        self.preset
    }

    pub(crate) fn marker_fields(&self, state: &str) -> String {
        format!(
            "privateParticleMaterialRequestSession={} privateParticleMaterialRequestGeneration={} privateParticleMaterialRequestId={} privateParticleMaterialPresetRequested={} privateParticleMaterialRequestState={}",
            self.session_id,
            self.generation,
            self.request_id,
            self.preset.marker_name(),
            state
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleMaterialRejection {
    reason: &'static str,
    request: Option<PrivateParticleMaterialRequest>,
}

impl PrivateParticleMaterialRejection {
    pub(crate) fn marker_fields(&self) -> String {
        let request_fields = self
            .request
            .as_ref()
            .map(|request| request.marker_fields("rejected"))
            .unwrap_or_else(|| {
                "privateParticleMaterialRequestSession=unavailable privateParticleMaterialRequestGeneration=unavailable privateParticleMaterialRequestId=unavailable privateParticleMaterialPresetRequested=unavailable privateParticleMaterialRequestState=rejected".to_owned()
            });
        format!(
            "{} privateParticleMaterialRequestRejectReason={}",
            request_fields, self.reason
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PrivateParticleMaterialEffectiveReceipt {
    request: PrivateParticleMaterialRequest,
    frame: u64,
}

impl PrivateParticleMaterialEffectiveReceipt {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "{} privateParticleMaterialRendererPrepared=true privateParticleMaterialSubmittedFrame={}",
            self.request.marker_fields("effective"),
            self.frame
        )
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum PrivateParticleMaterialRequestObservation {
    NoChange,
    Accepted(PrivateParticleMaterialRequest),
    Rejected(PrivateParticleMaterialRejection),
}

#[derive(Clone, Debug, Default)]
pub(crate) struct PrivateParticleMaterialRequestState {
    session_id: Option<String>,
    highest_accepted_generation: u64,
    active_request: Option<PrivateParticleMaterialRequest>,
    effective_request: Option<PrivateParticleMaterialRequest>,
    prepared_frame: Option<(u64, PrivateParticleMaterialRequest)>,
    last_observed_payload: Option<String>,
}

impl PrivateParticleMaterialRequestState {
    pub(crate) fn begin_session(&mut self) {
        let session_id = generated_session_id();
        self.begin_session_with_id(&session_id)
            .expect("generated private-particle material session identity must be valid");
    }

    pub(crate) fn begin_session_with_id(&mut self, session_id: &str) -> Result<String, String> {
        if !is_lower_hex(session_id, SESSION_ID_HEX_LEN) || is_all_zero_hex(session_id) {
            return Err(
                "material session identity must be a nonzero 16-digit lowercase hex value"
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
            "privateParticleMaterialRequestSession={} privateParticleMaterialRequestGeneration=0 privateParticleMaterialRequestId=unset privateParticleMaterialPresetRequested=unset privateParticleMaterialRequestState=ready",
            session
        )
    }

    pub(crate) fn observe_property(
        &mut self,
        property_value: Option<String>,
    ) -> PrivateParticleMaterialRequestObservation {
        let Some(payload) = property_value.map(|value| value.trim().to_owned()) else {
            self.last_observed_payload = None;
            return PrivateParticleMaterialRequestObservation::NoChange;
        };
        if payload.is_empty() {
            self.last_observed_payload = None;
            return PrivateParticleMaterialRequestObservation::NoChange;
        }
        if self.last_observed_payload.as_deref() == Some(payload.as_str()) {
            return PrivateParticleMaterialRequestObservation::NoChange;
        }
        self.last_observed_payload = Some(payload.clone());

        let request = match parse_request(&payload) {
            Ok(request) => request,
            Err(reason) => {
                return PrivateParticleMaterialRequestObservation::Rejected(
                    PrivateParticleMaterialRejection {
                        reason,
                        request: None,
                    },
                );
            }
        };
        let Some(session_id) = self.session_id.as_deref() else {
            return PrivateParticleMaterialRequestObservation::Rejected(
                PrivateParticleMaterialRejection {
                    reason: "no-current-openxr-session",
                    request: Some(request),
                },
            );
        };
        if request.session_id != session_id {
            return PrivateParticleMaterialRequestObservation::Rejected(
                PrivateParticleMaterialRejection {
                    reason: "session-mismatch",
                    request: Some(request),
                },
            );
        }
        if request.generation <= self.highest_accepted_generation {
            return PrivateParticleMaterialRequestObservation::Rejected(
                PrivateParticleMaterialRejection {
                    reason: "stale-or-replayed-generation",
                    request: Some(request),
                },
            );
        }

        self.highest_accepted_generation = request.generation;
        self.active_request = Some(request.clone());
        self.effective_request = None;
        self.prepared_frame = None;
        PrivateParticleMaterialRequestObservation::Accepted(request)
    }

    pub(crate) fn active_preset(&self) -> Option<PrivateParticleMaterialPreset> {
        self.active_request
            .as_ref()
            .map(PrivateParticleMaterialRequest::preset)
    }

    pub(crate) fn note_renderer_prepared_frame(
        &mut self,
        frame: u64,
        actual_preset: Option<PrivateParticleMaterialPreset>,
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
    ) -> Option<PrivateParticleMaterialEffectiveReceipt> {
        let (prepared_frame, request) = self.prepared_frame.take()?;
        if prepared_frame != frame || self.active_request.as_ref() != Some(&request) {
            return None;
        }
        self.effective_request = Some(request.clone());
        Some(PrivateParticleMaterialEffectiveReceipt { request, frame })
    }
}

fn parse_request(payload: &str) -> Result<PrivateParticleMaterialRequest, &'static str> {
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
    let preset = PrivateParticleMaterialPreset::parse_wire_code(fields[4])
        .ok_or("unsupported-material-preset")?;
    Ok(PrivateParticleMaterialRequest {
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
    let mixed = clock.wrapping_mul(0x94d0_49bb_1331_11eb).rotate_left(23)
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
    fn unset_default_has_no_material_override() {
        let mut state = PrivateParticleMaterialRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert_eq!(
            state.observe_property(None),
            PrivateParticleMaterialRequestObservation::NoChange
        );
        assert_eq!(state.active_preset(), None);
    }

    #[test]
    fn closed_presets_accept_then_become_effective_only_after_submitted_frame() {
        let mut state = PrivateParticleMaterialRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        let accepted = state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "over-df")));
        assert!(matches!(
            accepted,
            PrivateParticleMaterialRequestObservation::Accepted(_)
        ));
        assert_eq!(
            state.active_preset(),
            Some(PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFacingFade)
        );
        state.note_renderer_prepared_frame(
            7,
            Some(PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFacingFade),
            true,
        );
        let marker = state.confirm_submitted_frame(7).unwrap().marker_fields();
        assert!(marker.contains(
            "privateParticleMaterialPresetRequested=premultiplied-alpha-over-depth-facing"
        ));
        assert!(marker.contains("privateParticleMaterialSubmittedFrame=7"));
    }

    #[test]
    fn malformed_unknown_stale_and_other_session_requests_do_not_change_active_preset() {
        let mut state = PrivateParticleMaterialRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 1, REQUEST_A, "over"))),
            PrivateParticleMaterialRequestObservation::Accepted(_)
        ));
        for invalid in [
            "v2|0123456789abcdef|0000000000000002|22222222222222222222222222222222|add".to_owned(),
            payload(SESSION_A, 2, REQUEST_B, "unknown"),
            payload(SESSION_A, 0, REQUEST_B, "add"),
            payload(SESSION_B, 2, REQUEST_B, "add"),
            payload(SESSION_A, 1, REQUEST_B, "akd"),
        ] {
            assert!(matches!(
                state.observe_property(Some(invalid)),
                PrivateParticleMaterialRequestObservation::Rejected(_)
            ));
            assert_eq!(
                state.active_preset(),
                Some(PrivateParticleMaterialPreset::PremultipliedAlphaOver)
            );
        }
    }

    #[test]
    fn new_session_resets_the_preset_and_requires_its_own_matching_frame() {
        let mut state = PrivateParticleMaterialRequestState::default();
        state.begin_session_with_id(SESSION_A).unwrap();
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 9, REQUEST_A, "akd"))),
            PrivateParticleMaterialRequestObservation::Accepted(_)
        ));
        state.begin_session_with_id(SESSION_B).unwrap();
        assert_eq!(state.active_preset(), None);
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_A, 10, REQUEST_B, "add"))),
            PrivateParticleMaterialRequestObservation::Rejected(_)
        ));
        assert!(matches!(
            state.observe_property(Some(payload(SESSION_B, 1, REQUEST_B, "add"))),
            PrivateParticleMaterialRequestObservation::Accepted(_)
        ));
        state.note_renderer_prepared_frame(
            5,
            Some(PrivateParticleMaterialPreset::PremultipliedAlphaOver),
            true,
        );
        assert_eq!(state.confirm_submitted_frame(5), None);
        state.note_renderer_prepared_frame(
            6,
            Some(PrivateParticleMaterialPreset::CurrentAdditive),
            true,
        );
        assert!(state.confirm_submitted_frame(6).is_some());
    }

    #[test]
    fn wire_codes_stay_compact_for_android_property_transport() {
        for preset in [
            PrivateParticleMaterialPreset::CurrentAdditive,
            PrivateParticleMaterialPreset::PremultipliedAlphaOver,
            PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFade,
            PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFacingFade,
            PrivateParticleMaterialPreset::AkdMaterialEmulation,
        ] {
            let envelope = payload(SESSION_A, 1, REQUEST_A, preset.wire_code());
            assert!(envelope.len() <= 91, "{envelope}");
        }
    }

    #[test]
    fn closed_presets_define_the_expected_color_and_transparency_envelopes() {
        assert_eq!(
            PrivateParticleMaterialPreset::CurrentAdditive.parameters(),
            PrivateParticleMaterialPresetParameters {
                uses_premultiplied_alpha_over: false,
                opacity: 1.0,
                output_alpha_scale: 1.0,
                depth_suppression_strength: 0.0,
                rgb_alpha_coupling: 0.0,
                facing_attenuation_strength: 0.0,
            }
        );
        assert_eq!(
            PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFacingFade.parameters(),
            PrivateParticleMaterialPresetParameters {
                uses_premultiplied_alpha_over: true,
                opacity: 1.0,
                output_alpha_scale: 1.0,
                depth_suppression_strength: 1.5,
                rgb_alpha_coupling: 0.0,
                facing_attenuation_strength: 0.20,
            }
        );
        assert_eq!(
            PrivateParticleMaterialPreset::AkdMaterialEmulation.parameters(),
            PrivateParticleMaterialPresetParameters {
                uses_premultiplied_alpha_over: true,
                opacity: 0.36,
                output_alpha_scale: 0.45,
                depth_suppression_strength: 1.5,
                rgb_alpha_coupling: 0.0,
                facing_attenuation_strength: 0.20,
            }
        );
    }
}
