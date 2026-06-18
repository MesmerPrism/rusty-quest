//! Camera2 PRIVATE reader-size capability and ranking helpers.

use crate::{
    acamera_sys::{
        ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, ACAMERA_EDGE_MODE, ACAMERA_NOISE_REDUCTION_MODE,
    },
    native_renderer_options::{NativeCameraQualityProfile, NativeCameraResolutionProfile},
};

#[derive(Default)]
pub(crate) struct CameraCapabilities {
    pub(crate) hardware_level: Option<u8>,
    pub(crate) capabilities: Vec<u8>,
    pub(crate) request_keys: Vec<i32>,
    pub(crate) result_keys: Vec<i32>,
    pub(crate) ae_fps_ranges: Vec<[i32; 2]>,
    pub(crate) noise_reduction_modes: Vec<u8>,
    pub(crate) edge_modes: Vec<u8>,
    pub(crate) private_output_sizes: Vec<[i32; 2]>,
    pub(crate) private_output_min_frame_durations: Vec<PrivateOutputMinFrameDuration>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct PrivateOutputMinFrameDuration {
    pub(crate) width: i32,
    pub(crate) height: i32,
    pub(crate) duration_ns: i64,
}

#[derive(Clone, Debug)]
pub(crate) struct ReaderSizeSelection {
    pub(crate) width: i32,
    pub(crate) height: i32,
    pub(crate) requested_width: i32,
    pub(crate) requested_height: i32,
    pub(crate) status: &'static str,
    pub(crate) reason: String,
    pub(crate) min_frame_duration_ns: Option<i64>,
    pub(crate) target_fps: Option<i32>,
    pub(crate) target_fps_feasible: Option<bool>,
}

impl CameraCapabilities {
    pub(crate) fn supports_request_key(&self, tag: u32) -> bool {
        self.request_keys.iter().any(|key| *key == tag as i32)
    }

    pub(crate) fn supports_ae_fps_request(&self) -> bool {
        self.supports_request_key(ACAMERA_CONTROL_AE_TARGET_FPS_RANGE)
    }

    pub(crate) fn supports_noise_reduction_mode(&self, mode: u8) -> bool {
        self.supports_request_key(ACAMERA_NOISE_REDUCTION_MODE)
            && self
                .noise_reduction_modes
                .iter()
                .any(|value| *value == mode)
    }

    pub(crate) fn supports_edge_mode(&self, mode: u8) -> bool {
        self.supports_request_key(ACAMERA_EDGE_MODE)
            && self.edge_modes.iter().any(|value| *value == mode)
    }

    fn min_frame_duration_ns(&self, size: [i32; 2]) -> Option<i64> {
        self.private_output_min_frame_durations
            .iter()
            .filter(|duration| duration.width == size[0] && duration.height == size[1])
            .map(|duration| duration.duration_ns)
            .min()
    }
}

pub(crate) fn select_reader_size(
    profile: NativeCameraResolutionProfile,
    quality_profile: NativeCameraQualityProfile,
    capabilities: &CameraCapabilities,
    default_size: [i32; 2],
) -> ReaderSizeSelection {
    let requested = profile.requested_size().unwrap_or(default_size);
    let target_fps = quality_profile.target_ae_fps_range().map(|range| range[1]);
    if capabilities.private_output_sizes.is_empty() {
        return ReaderSizeSelection {
            width: requested[0],
            height: requested[1],
            requested_width: requested[0],
            requested_height: requested[1],
            status: "support-unknown-using-requested",
            reason: "no-private-output-size-capability".to_string(),
            min_frame_duration_ns: None,
            target_fps,
            target_fps_feasible: None,
        };
    }
    if let Some(requested_size) = profile.requested_size() {
        if capabilities.private_output_sizes.contains(&requested_size) {
            let min_duration = capabilities.min_frame_duration_ns(requested_size);
            return ReaderSizeSelection {
                width: requested_size[0],
                height: requested_size[1],
                requested_width: requested_size[0],
                requested_height: requested_size[1],
                status: "exact-supported",
                reason: ranked_reader_reason(
                    requested_size,
                    requested_size,
                    target_fps,
                    min_duration,
                ),
                min_frame_duration_ns: min_duration,
                target_fps,
                target_fps_feasible: target_fps_feasible(min_duration, target_fps),
            };
        }
        let fallback = ranked_private_output_size(requested_size, target_fps, capabilities);
        let min_duration = capabilities.min_frame_duration_ns(fallback);
        return ReaderSizeSelection {
            width: fallback[0],
            height: fallback[1],
            requested_width: requested_size[0],
            requested_height: requested_size[1],
            status: "fallback-ranked-supported",
            reason: ranked_reader_reason(requested_size, fallback, target_fps, min_duration),
            min_frame_duration_ns: min_duration,
            target_fps,
            target_fps_feasible: target_fps_feasible(min_duration, target_fps),
        };
    }

    let fallback = ranked_private_output_size(requested, target_fps, capabilities);
    let min_duration = capabilities.min_frame_duration_ns(fallback);
    ReaderSizeSelection {
        width: fallback[0],
        height: fallback[1],
        requested_width: requested[0],
        requested_height: requested[1],
        status: "closest-ranked-supported",
        reason: ranked_reader_reason(requested, fallback, target_fps, min_duration),
        min_frame_duration_ns: min_duration,
        target_fps,
        target_fps_feasible: target_fps_feasible(min_duration, target_fps),
    }
}

fn ranked_private_output_size(
    requested_size: [i32; 2],
    target_fps: Option<i32>,
    capabilities: &CameraCapabilities,
) -> [i32; 2] {
    capabilities
        .private_output_sizes
        .iter()
        .copied()
        .min_by_key(|size| {
            let min_duration = capabilities.min_frame_duration_ns(*size);
            let fps_penalty = match target_fps_feasible(min_duration, target_fps) {
                Some(true) | None => 0_i64,
                Some(false) => 1_000_000_000_i64,
            };
            (
                fps_penalty,
                preferred_size_rank(*size),
                aspect_error_milli(requested_size, *size),
                (size[0] - requested_size[0]).abs() as i64
                    + (size[1] - requested_size[1]).abs() as i64,
                min_duration.unwrap_or(i64::MAX),
                size[0] as i64 * size[1] as i64,
            )
        })
        .unwrap_or(requested_size)
}

fn preferred_size_rank(size: [i32; 2]) -> i32 {
    match size {
        [1280, 1280] => 0,
        [1280, 960] => 1,
        _ => 100,
    }
}

fn aspect_error_milli(requested: [i32; 2], candidate: [i32; 2]) -> i64 {
    ((requested[0] as i64 * candidate[1] as i64 - candidate[0] as i64 * requested[1] as i64).abs()
        * 1000)
        / (requested[1].max(1) as i64 * candidate[1].max(1) as i64)
}

fn target_fps_feasible(min_duration_ns: Option<i64>, target_fps: Option<i32>) -> Option<bool> {
    let target_fps = target_fps?;
    if target_fps <= 0 {
        return None;
    }
    let min_duration_ns = min_duration_ns?;
    Some(min_duration_ns <= 1_000_000_000_i64 / target_fps as i64)
}

fn ranked_reader_reason(
    requested: [i32; 2],
    selected: [i32; 2],
    target_fps: Option<i32>,
    min_duration_ns: Option<i64>,
) -> String {
    format!(
        "ranked-private-output:requested={}x{};selected={}x{};preferredRank={};aspectErrorMilli={};targetFps={};minFrameDurationNs={};targetFpsFeasible={}",
        requested[0],
        requested[1],
        selected[0],
        selected[1],
        preferred_size_rank(selected),
        aspect_error_milli(requested, selected),
        optional_i32_marker(target_fps),
        optional_i64_marker(min_duration_ns),
        target_fps_feasible(min_duration_ns, target_fps)
            .map(|feasible| feasible.to_string())
            .unwrap_or_else(|| "unknown".to_string())
    )
}

fn optional_i32_marker(value: Option<i32>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "none".to_string())
}

fn optional_i64_marker(value: Option<i64>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "none".to_string())
}
