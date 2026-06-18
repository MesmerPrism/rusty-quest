//! Bounded Camera2 capture-result metadata snapshots for HWB frame correlation.

use std::collections::VecDeque;

use crate::acamera_sys::{
    ACameraMetadata, ACameraMetadataConstEntry, ACameraMetadata_getConstEntry,
    ACAMERA_CONTROL_AE_STATE, ACAMERA_CONTROL_AE_STATE_CONVERGED,
    ACAMERA_CONTROL_AE_STATE_FLASH_REQUIRED, ACAMERA_CONTROL_AE_STATE_INACTIVE,
    ACAMERA_CONTROL_AE_STATE_LOCKED, ACAMERA_CONTROL_AE_STATE_PRECAPTURE,
    ACAMERA_CONTROL_AE_STATE_SEARCHING, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE,
    ACAMERA_CONTROL_AWB_STATE, ACAMERA_CONTROL_AWB_STATE_CONVERGED,
    ACAMERA_CONTROL_AWB_STATE_INACTIVE, ACAMERA_CONTROL_AWB_STATE_LOCKED,
    ACAMERA_CONTROL_AWB_STATE_SEARCHING, ACAMERA_EDGE_MODE, ACAMERA_EDGE_MODE_FAST,
    ACAMERA_EDGE_MODE_HIGH_QUALITY, ACAMERA_EDGE_MODE_OFF, ACAMERA_NOISE_REDUCTION_MODE,
    ACAMERA_NOISE_REDUCTION_MODE_FAST, ACAMERA_NOISE_REDUCTION_MODE_HIGH_QUALITY,
    ACAMERA_NOISE_REDUCTION_MODE_OFF, ACAMERA_SENSOR_EXPOSURE_TIME, ACAMERA_SENSOR_FRAME_DURATION,
    ACAMERA_SENSOR_SENSITIVITY, ACAMERA_SENSOR_TIMESTAMP, ACAMERA_SYNC_FRAME_NUMBER,
};

const RECENT_CAPTURE_RESULT_LIMIT: usize = 8;

#[derive(Default)]
pub(crate) struct NativeCameraCaptureResultRing {
    results: VecDeque<NativeCameraCaptureResultSnapshot>,
}

impl NativeCameraCaptureResultRing {
    pub(crate) fn push(&mut self, snapshot: NativeCameraCaptureResultSnapshot) {
        self.results.push_back(snapshot);
        while self.results.len() > RECENT_CAPTURE_RESULT_LIMIT {
            self.results.pop_front();
        }
    }

    pub(crate) fn correlate(&self, timestamp_ns: i64) -> NativeCameraCaptureResultCorrelation {
        let Some(latest) = self.results.back().cloned() else {
            return NativeCameraCaptureResultCorrelation::unavailable();
        };
        let Some((nearest, delta_ns)) = self
            .results
            .iter()
            .filter_map(|result| {
                result
                    .sensor_timestamp_ns
                    .map(|sensor_timestamp_ns| (result, timestamp_ns.abs_diff(sensor_timestamp_ns)))
            })
            .min_by_key(|(_, delta_ns)| *delta_ns)
        else {
            return NativeCameraCaptureResultCorrelation::from_snapshot(
                "latest-no-sensor-timestamp",
                None,
                latest,
            );
        };
        let status = if delta_ns == 0 {
            "exact-sensor-timestamp"
        } else {
            "nearest-sensor-timestamp"
        };
        NativeCameraCaptureResultCorrelation::from_snapshot(
            status,
            Some(delta_ns),
            (*nearest).clone(),
        )
    }
}

#[derive(Clone, Debug)]
pub(crate) struct NativeCameraCaptureResultCorrelation {
    status: &'static str,
    delta_ns: Option<u64>,
    snapshot: Option<NativeCameraCaptureResultSnapshot>,
}

impl NativeCameraCaptureResultCorrelation {
    fn unavailable() -> Self {
        Self {
            status: "unavailable",
            delta_ns: None,
            snapshot: None,
        }
    }

    fn from_snapshot(
        status: &'static str,
        delta_ns: Option<u64>,
        snapshot: NativeCameraCaptureResultSnapshot,
    ) -> Self {
        Self {
            status,
            delta_ns,
            snapshot: Some(snapshot),
        }
    }

    pub(crate) fn ready(&self) -> bool {
        self.snapshot.is_some()
    }

    pub(crate) fn frame_marker_fields(&self) -> String {
        let snapshot = self.snapshot.as_ref();
        format!(
            "captureResultCorrelationStatus={} captureResultDeltaNs={} resultMetadataReady={} resultCount={} resultSensorTimestampNs={} exposureTimeNs={} sensitivityIso={} frameDurationNs={} aeFpsRange={} aeState={} awbState={} noiseReductionMode={} edgeMode={} syncFrameNumber={}",
            self.status,
            optional_u64_unavailable_marker(self.delta_ns),
            self.ready(),
            snapshot
                .map(|value| value.result_count.to_string())
                .unwrap_or_else(|| "unavailable".to_string()),
            optional_i64_unavailable_marker(snapshot.and_then(|value| value.sensor_timestamp_ns)),
            optional_i64_unavailable_marker(snapshot.and_then(|value| value.exposure_time_ns)),
            optional_i32_unavailable_marker(snapshot.and_then(|value| value.sensitivity_iso)),
            optional_i64_unavailable_marker(snapshot.and_then(|value| value.frame_duration_ns)),
            optional_i32_range_unavailable_marker(snapshot.and_then(|value| value.ae_fps_range)),
            optional_u8_mode_unavailable_marker(
                snapshot.and_then(|value| value.ae_state),
                ae_state_label
            ),
            optional_u8_mode_unavailable_marker(
                snapshot.and_then(|value| value.awb_state),
                awb_state_label
            ),
            optional_u8_mode_unavailable_marker(
                snapshot.and_then(|value| value.noise_reduction_mode),
                noise_reduction_mode_label
            ),
            optional_u8_mode_unavailable_marker(
                snapshot.and_then(|value| value.edge_mode),
                edge_mode_label
            ),
            optional_i64_unavailable_marker(snapshot.and_then(|value| value.sync_frame_number)),
        )
    }

    pub(crate) fn scorecard_marker_fields(&self, prefix: &str) -> String {
        let snapshot = self.snapshot.as_ref();
        format!(
            "{}ResultCorrelationStatus={} {}ResultDeltaNs={} {}ResultMetadataReady={} {}ResultCount={} {}ResultSensorTimestampNs={} {}ResultExposureTimeNs={} {}ResultSensitivityIso={} {}ResultFrameDurationNs={} {}ResultAeFpsRange={} {}ResultAeState={} {}ResultAwbState={} {}ResultNoiseReductionMode={} {}ResultEdgeMode={} {}ResultSyncFrameNumber={}",
            prefix,
            self.status,
            prefix,
            optional_u64_unavailable_marker(self.delta_ns),
            prefix,
            self.ready(),
            prefix,
            snapshot
                .map(|value| value.result_count.to_string())
                .unwrap_or_else(|| "unavailable".to_string()),
            prefix,
            optional_i64_unavailable_marker(snapshot.and_then(|value| value.sensor_timestamp_ns)),
            prefix,
            optional_i64_unavailable_marker(snapshot.and_then(|value| value.exposure_time_ns)),
            prefix,
            optional_i32_unavailable_marker(snapshot.and_then(|value| value.sensitivity_iso)),
            prefix,
            optional_i64_unavailable_marker(snapshot.and_then(|value| value.frame_duration_ns)),
            prefix,
            optional_i32_range_unavailable_marker(snapshot.and_then(|value| value.ae_fps_range)),
            prefix,
            optional_u8_mode_unavailable_marker(
                snapshot.and_then(|value| value.ae_state),
                ae_state_label
            ),
            prefix,
            optional_u8_mode_unavailable_marker(
                snapshot.and_then(|value| value.awb_state),
                awb_state_label
            ),
            prefix,
            optional_u8_mode_unavailable_marker(
                snapshot.and_then(|value| value.noise_reduction_mode),
                noise_reduction_mode_label
            ),
            prefix,
            optional_u8_mode_unavailable_marker(
                snapshot.and_then(|value| value.edge_mode),
                edge_mode_label
            ),
            prefix,
            optional_i64_unavailable_marker(snapshot.and_then(|value| value.sync_frame_number)),
        )
    }
}

impl Default for NativeCameraCaptureResultCorrelation {
    fn default() -> Self {
        Self::unavailable()
    }
}

#[derive(Clone, Debug)]
pub(crate) struct NativeCameraCaptureResultSnapshot {
    result_count: u64,
    sensor_timestamp_ns: Option<i64>,
    exposure_time_ns: Option<i64>,
    sensitivity_iso: Option<i32>,
    frame_duration_ns: Option<i64>,
    ae_fps_range: Option<[i32; 2]>,
    ae_state: Option<u8>,
    awb_state: Option<u8>,
    noise_reduction_mode: Option<u8>,
    edge_mode: Option<u8>,
    sync_frame_number: Option<i64>,
}

impl NativeCameraCaptureResultSnapshot {
    pub(crate) unsafe fn from_metadata(
        result_count: u64,
        metadata: *const ACameraMetadata,
    ) -> Self {
        Self {
            result_count,
            sensor_timestamp_ns: metadata_i64(metadata, ACAMERA_SENSOR_TIMESTAMP),
            exposure_time_ns: metadata_i64(metadata, ACAMERA_SENSOR_EXPOSURE_TIME),
            sensitivity_iso: metadata_i32(metadata, ACAMERA_SENSOR_SENSITIVITY),
            frame_duration_ns: metadata_i64(metadata, ACAMERA_SENSOR_FRAME_DURATION),
            ae_fps_range: metadata_i32_pair(metadata, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE),
            ae_state: metadata_u8(metadata, ACAMERA_CONTROL_AE_STATE),
            awb_state: metadata_u8(metadata, ACAMERA_CONTROL_AWB_STATE),
            noise_reduction_mode: metadata_u8(metadata, ACAMERA_NOISE_REDUCTION_MODE),
            edge_mode: metadata_u8(metadata, ACAMERA_EDGE_MODE),
            sync_frame_number: metadata_i64(metadata, ACAMERA_SYNC_FRAME_NUMBER),
        }
    }

    pub(crate) fn capture_result_marker_fields(&self) -> String {
        format!(
            "resultSensorTimestampNs={} exposureTimeNs={} sensitivityIso={} frameDurationNs={} aeFpsRange={} aeState={} awbState={} noiseReductionMode={} edgeMode={} syncFrameNumber={}",
            optional_i64_unavailable_marker(self.sensor_timestamp_ns),
            optional_i64_unavailable_marker(self.exposure_time_ns),
            optional_i32_unavailable_marker(self.sensitivity_iso),
            optional_i64_unavailable_marker(self.frame_duration_ns),
            optional_i32_range_unavailable_marker(self.ae_fps_range),
            optional_u8_mode_unavailable_marker(self.ae_state, ae_state_label),
            optional_u8_mode_unavailable_marker(self.awb_state, awb_state_label),
            optional_u8_mode_unavailable_marker(self.noise_reduction_mode, noise_reduction_mode_label),
            optional_u8_mode_unavailable_marker(self.edge_mode, edge_mode_label),
            optional_i64_unavailable_marker(self.sync_frame_number),
        )
    }
}

unsafe fn read_u8_values(metadata: *const ACameraMetadata, tag: u32) -> Vec<u8> {
    let Some(entry) = metadata_entry(metadata, tag) else {
        return Vec::new();
    };
    if entry.count == 0 || entry.data.u8_.is_null() {
        return Vec::new();
    }
    std::slice::from_raw_parts(entry.data.u8_, entry.count as usize).to_vec()
}

unsafe fn read_i32_values(metadata: *const ACameraMetadata, tag: u32) -> Vec<i32> {
    let Some(entry) = metadata_entry(metadata, tag) else {
        return Vec::new();
    };
    if entry.count == 0 || entry.data.i32_.is_null() {
        return Vec::new();
    }
    std::slice::from_raw_parts(entry.data.i32_, entry.count as usize).to_vec()
}

unsafe fn metadata_entry(
    metadata: *const ACameraMetadata,
    tag: u32,
) -> Option<ACameraMetadataConstEntry> {
    let mut entry = std::mem::MaybeUninit::<ACameraMetadataConstEntry>::zeroed();
    if ACameraMetadata_getConstEntry(metadata, tag, entry.as_mut_ptr()) == 0 {
        Some(entry.assume_init())
    } else {
        None
    }
}

unsafe fn metadata_u8(metadata: *const ACameraMetadata, tag: u32) -> Option<u8> {
    read_u8_values(metadata, tag).first().copied()
}

unsafe fn metadata_i32(metadata: *const ACameraMetadata, tag: u32) -> Option<i32> {
    read_i32_values(metadata, tag).first().copied()
}

unsafe fn metadata_i64(metadata: *const ACameraMetadata, tag: u32) -> Option<i64> {
    let Some(entry) = metadata_entry(metadata, tag) else {
        return None;
    };
    if entry.count == 0 || entry.data.i64_.is_null() {
        return None;
    }
    Some(*entry.data.i64_)
}

unsafe fn metadata_i32_pair(metadata: *const ACameraMetadata, tag: u32) -> Option<[i32; 2]> {
    let values = read_i32_values(metadata, tag);
    if values.len() < 2 {
        None
    } else {
        Some([values[0], values[1]])
    }
}

fn optional_i64_unavailable_marker(value: Option<i64>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "unavailable".to_string())
}

fn optional_i32_unavailable_marker(value: Option<i32>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "unavailable".to_string())
}

fn optional_u64_unavailable_marker(value: Option<u64>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "unavailable".to_string())
}

fn optional_i32_range_unavailable_marker(value: Option<[i32; 2]>) -> String {
    value
        .map(|range| format!("{}-{}", range[0], range[1]))
        .unwrap_or_else(|| "unavailable".to_string())
}

fn optional_u8_mode_unavailable_marker(value: Option<u8>, label: fn(u8) -> &'static str) -> String {
    value
        .map(|mode| label(mode).to_string())
        .unwrap_or_else(|| "unavailable".to_string())
}

fn ae_state_label(value: u8) -> &'static str {
    match value {
        ACAMERA_CONTROL_AE_STATE_INACTIVE => "INACTIVE",
        ACAMERA_CONTROL_AE_STATE_SEARCHING => "SEARCHING",
        ACAMERA_CONTROL_AE_STATE_CONVERGED => "CONVERGED",
        ACAMERA_CONTROL_AE_STATE_LOCKED => "LOCKED",
        ACAMERA_CONTROL_AE_STATE_FLASH_REQUIRED => "FLASH_REQUIRED",
        ACAMERA_CONTROL_AE_STATE_PRECAPTURE => "PRECAPTURE",
        _ => "UNKNOWN",
    }
}

fn awb_state_label(value: u8) -> &'static str {
    match value {
        ACAMERA_CONTROL_AWB_STATE_INACTIVE => "INACTIVE",
        ACAMERA_CONTROL_AWB_STATE_SEARCHING => "SEARCHING",
        ACAMERA_CONTROL_AWB_STATE_CONVERGED => "CONVERGED",
        ACAMERA_CONTROL_AWB_STATE_LOCKED => "LOCKED",
        _ => "UNKNOWN",
    }
}

fn noise_reduction_mode_label(value: u8) -> &'static str {
    match value {
        ACAMERA_NOISE_REDUCTION_MODE_OFF => "OFF",
        ACAMERA_NOISE_REDUCTION_MODE_FAST => "FAST",
        ACAMERA_NOISE_REDUCTION_MODE_HIGH_QUALITY => "HIGH_QUALITY",
        _ => "UNKNOWN",
    }
}

fn edge_mode_label(value: u8) -> &'static str {
    match value {
        ACAMERA_EDGE_MODE_OFF => "OFF",
        ACAMERA_EDGE_MODE_FAST => "FAST",
        ACAMERA_EDGE_MODE_HIGH_QUALITY => "HIGH_QUALITY",
        _ => "UNKNOWN",
    }
}
