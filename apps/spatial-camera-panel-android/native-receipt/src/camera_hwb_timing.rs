#![cfg_attr(not(target_os = "android"), allow(dead_code))]

#[cfg(target_os = "android")]
use std::ffi::{CStr, CString};
#[cfg(target_os = "android")]
use std::os::raw::{c_char, c_int};

use ash::vk;

const GPU_TIMESTAMP_PROPERTY: &str =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.gpu_timestamps";
const QUERIES_PER_STAGE: u32 = 2;
const TELEMETRY_CAPACITY: usize = 4096;

#[cfg(target_os = "android")]
extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraHwbGpuTimestampStage {
    FrameTotal,
    Guide0Camera,
    Guide1PreHorizontal,
    Guide2PreVertical,
    Guide3Strength,
    Guide4StrengthHorizontal,
    Guide5StrengthVertical,
    FinalCompositor,
}

impl CameraHwbGpuTimestampStage {
    const COUNT: usize = 8;

    const fn index(self) -> usize {
        match self {
            Self::FrameTotal => 0,
            Self::Guide0Camera => 1,
            Self::Guide1PreHorizontal => 2,
            Self::Guide2PreVertical => 3,
            Self::Guide3Strength => 4,
            Self::Guide4StrengthHorizontal => 5,
            Self::Guide5StrengthVertical => 6,
            Self::FinalCompositor => 7,
        }
    }

    const fn bit(self) -> u32 {
        1_u32 << self.index()
    }

    pub(crate) const fn from_guide_pass_index(index: usize) -> Option<Self> {
        match index {
            0 => Some(Self::Guide0Camera),
            1 => Some(Self::Guide1PreHorizontal),
            2 => Some(Self::Guide2PreVertical),
            3 => Some(Self::Guide3Strength),
            4 => Some(Self::Guide4StrengthHorizontal),
            5 => Some(Self::Guide5StrengthVertical),
            _ => None,
        }
    }
}

const QUERIES_PER_FRAME: u32 = CameraHwbGpuTimestampStage::COUNT as u32 * QUERIES_PER_STAGE;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct TimestampQueryWord {
    value: u64,
    available: u64,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct CameraHwbGpuTimestampSample {
    pub(crate) frame_id: u64,
    pub(crate) expected_mask: u32,
    pub(crate) ready_mask: u32,
    pub(crate) gpu_ns: [u64; CameraHwbGpuTimestampStage::COUNT],
}

impl Default for CameraHwbGpuTimestampSample {
    fn default() -> Self {
        Self {
            frame_id: 0,
            expected_mask: 0,
            ready_mask: 0,
            gpu_ns: [0; CameraHwbGpuTimestampStage::COUNT],
        }
    }
}

impl CameraHwbGpuTimestampSample {
    fn query_set_ready(self) -> bool {
        self.expected_mask != 0 && self.ready_mask == self.expected_mask
    }

    fn stage_ms(self, stage: CameraHwbGpuTimestampStage) -> f64 {
        if self.ready_mask & stage.bit() == 0 {
            return -1.0;
        }
        self.gpu_ns[stage.index()] as f64 / 1_000_000.0
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "gpuTimestampFrameId={} gpuTimestampExpectedMask=0x{:02x} gpuTimestampReadyMask=0x{:02x} gpuTimestampQuerySetReady={} frameTotalGpuMs={:.3} guide0CameraGpuMs={:.3} guide1PreHorizontalGpuMs={:.3} guide2PreVerticalGpuMs={:.3} guide3StrengthGpuMs={:.3} guide4StrengthHorizontalGpuMs={:.3} guide5StrengthVerticalGpuMs={:.3} finalCompositorGpuMs={:.3} gpuTimingScope=vulkan-retired-slot-timestamp-query",
            self.frame_id,
            self.expected_mask,
            self.ready_mask,
            self.query_set_ready(),
            self.stage_ms(CameraHwbGpuTimestampStage::FrameTotal),
            self.stage_ms(CameraHwbGpuTimestampStage::Guide0Camera),
            self.stage_ms(CameraHwbGpuTimestampStage::Guide1PreHorizontal),
            self.stage_ms(CameraHwbGpuTimestampStage::Guide2PreVertical),
            self.stage_ms(CameraHwbGpuTimestampStage::Guide3Strength),
            self.stage_ms(CameraHwbGpuTimestampStage::Guide4StrengthHorizontal),
            self.stage_ms(CameraHwbGpuTimestampStage::Guide5StrengthVertical),
            self.stage_ms(CameraHwbGpuTimestampStage::FinalCompositor),
        )
    }
}

#[derive(Clone, Copy, Debug, Default)]
struct CameraHwbGpuTimestampCounters {
    query_sets_attempted: u64,
    query_sets_ready: u64,
    query_sets_partial: u64,
    query_sets_not_ready: u64,
    query_read_errors: u64,
    samples_stored: u64,
    samples_overwritten: u64,
}

pub(crate) struct CameraHwbGpuTimestampTracker {
    requested: bool,
    hardware_supported: bool,
    query_pool: Option<vk::QueryPool>,
    frame_slots: usize,
    timestamp_valid_bits: u32,
    timestamp_period_ns: f64,
    used_slots: Vec<bool>,
    frame_ids: Vec<u64>,
    expected_masks: Vec<u32>,
    samples: Box<[CameraHwbGpuTimestampSample]>,
    sample_write_index: usize,
    counters: CameraHwbGpuTimestampCounters,
    creation_error: bool,
}

impl CameraHwbGpuTimestampTracker {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        frame_slots: usize,
        requested: bool,
        timestamp_valid_bits: u32,
        timestamp_period_ns: f64,
    ) -> Self {
        let hardware_supported = timestamp_valid_bits > 0 && timestamp_period_ns > 0.0;
        let query_count = frame_slots
            .checked_mul(QUERIES_PER_FRAME as usize)
            .and_then(|count| u32::try_from(count).ok());
        let mut creation_error = false;
        let query_pool = if requested && hardware_supported {
            query_count.and_then(|query_count| {
                match device.create_query_pool(
                    &vk::QueryPoolCreateInfo::default()
                        .query_type(vk::QueryType::TIMESTAMP)
                        .query_count(query_count),
                    None,
                ) {
                    Ok(pool) => Some(pool),
                    Err(_) => {
                        creation_error = true;
                        None
                    }
                }
            })
        } else {
            None
        };
        if requested && hardware_supported && query_count.is_none() {
            creation_error = true;
        }
        Self {
            requested,
            hardware_supported,
            query_pool,
            frame_slots,
            timestamp_valid_bits,
            timestamp_period_ns,
            used_slots: vec![false; frame_slots],
            frame_ids: vec![0; frame_slots],
            expected_masks: vec![0; frame_slots],
            samples: vec![CameraHwbGpuTimestampSample::default(); TELEMETRY_CAPACITY]
                .into_boxed_slice(),
            sample_write_index: 0,
            counters: CameraHwbGpuTimestampCounters::default(),
            creation_error,
        }
    }

    pub(crate) fn requested_from_runtime() -> bool {
        runtime_property(GPU_TIMESTAMP_PROPERTY)
            .is_some_and(|value| matches!(value.as_str(), "1" | "true" | "on" | "enabled"))
    }

    pub(crate) fn active(&self) -> bool {
        self.query_pool.is_some()
    }

    pub(crate) fn config_marker_fields(&self) -> String {
        format!(
            "gpuTimestampRequested={} gpuTimestampHardwareSupported={} gpuTimestampActive={} gpuTimestampCreationError={} gpuTimestampValidBits={} gpuTimestampPeriodNs={:.3} gpuTimestampFrameSlots={} gpuTimestampStageCount={} gpuTimestampTelemetryCapacity={} gpuTimestampReadPolicy=retired-slot-availability-no-wait gpuTimestampProperty={}",
            self.requested,
            self.hardware_supported,
            self.active(),
            self.creation_error,
            self.timestamp_valid_bits,
            self.timestamp_period_ns,
            self.frame_slots,
            CameraHwbGpuTimestampStage::COUNT,
            TELEMETRY_CAPACITY,
            GPU_TIMESTAMP_PROPERTY,
        )
    }

    pub(crate) fn summary_marker_fields(&self) -> String {
        let ready_rate_permille = if self.counters.query_sets_attempted == 0 {
            0
        } else {
            self.counters.query_sets_ready.saturating_mul(1000) / self.counters.query_sets_attempted
        };
        format!(
            "gpuTimestampQuerySetsAttempted={} gpuTimestampQuerySetsReady={} gpuTimestampQuerySetsPartial={} gpuTimestampQuerySetsNotReady={} gpuTimestampQueryReadErrors={} gpuTimestampReadyRatePermille={} gpuTimestampSamplesStored={} gpuTimestampSamplesOverwritten={} gpuTimestampTelemetryAllocation=single-fixed-capacity-ring gpuTimestampPerFrameAllocation=false",
            self.counters.query_sets_attempted,
            self.counters.query_sets_ready,
            self.counters.query_sets_partial,
            self.counters.query_sets_not_ready,
            self.counters.query_read_errors,
            ready_rate_permille,
            self.counters.samples_stored,
            self.counters.samples_overwritten,
        )
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(query_pool) = self.query_pool.take() {
            device.destroy_query_pool(query_pool, None);
        }
    }

    pub(crate) unsafe fn begin_frame(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        frame_slot: usize,
        frame_id: u64,
    ) {
        let Some(query_pool) = self.query_pool else {
            return;
        };
        if frame_slot >= self.frame_slots {
            return;
        }
        device.cmd_reset_query_pool(
            command_buffer,
            query_pool,
            self.first_query(frame_slot),
            QUERIES_PER_FRAME,
        );
        self.used_slots[frame_slot] = true;
        self.frame_ids[frame_slot] = frame_id;
        self.expected_masks[frame_slot] = 0;
        self.write_stage_start(
            device,
            command_buffer,
            frame_slot,
            CameraHwbGpuTimestampStage::FrameTotal,
        );
    }

    pub(crate) unsafe fn finish_frame(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        frame_slot: usize,
    ) {
        self.write_stage_end(
            device,
            command_buffer,
            frame_slot,
            CameraHwbGpuTimestampStage::FrameTotal,
        );
    }

    pub(crate) unsafe fn write_stage_start(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        frame_slot: usize,
        stage: CameraHwbGpuTimestampStage,
    ) {
        self.write_timestamp(
            device,
            command_buffer,
            frame_slot,
            stage,
            0,
            vk::PipelineStageFlags::TOP_OF_PIPE,
        );
    }

    pub(crate) unsafe fn write_stage_end(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        frame_slot: usize,
        stage: CameraHwbGpuTimestampStage,
    ) {
        self.write_timestamp(
            device,
            command_buffer,
            frame_slot,
            stage,
            1,
            vk::PipelineStageFlags::BOTTOM_OF_PIPE,
        );
        if frame_slot < self.frame_slots && self.query_pool.is_some() {
            self.expected_masks[frame_slot] |= stage.bit();
        }
    }

    pub(crate) unsafe fn read_retired_slot(
        &mut self,
        device: &ash::Device,
        frame_slot: usize,
    ) -> Option<CameraHwbGpuTimestampSample> {
        let query_pool = self.query_pool?;
        if frame_slot >= self.frame_slots || !self.used_slots[frame_slot] {
            return None;
        }
        self.counters.query_sets_attempted = self.counters.query_sets_attempted.saturating_add(1);
        let mut words = [TimestampQueryWord::default(); CameraHwbGpuTimestampStage::COUNT * 2];
        match device.get_query_pool_results(
            query_pool,
            self.first_query(frame_slot),
            &mut words,
            vk::QueryResultFlags::TYPE_64 | vk::QueryResultFlags::WITH_AVAILABILITY,
        ) {
            Ok(()) => {}
            Err(vk::Result::NOT_READY) => {
                self.counters.query_sets_not_ready =
                    self.counters.query_sets_not_ready.saturating_add(1);
                return None;
            }
            Err(_) => {
                self.counters.query_read_errors = self.counters.query_read_errors.saturating_add(1);
                return None;
            }
        }
        let sample = decode_query_words(
            self.frame_ids[frame_slot],
            self.expected_masks[frame_slot],
            &words,
            self.timestamp_valid_bits,
            self.timestamp_period_ns,
        );
        if sample.query_set_ready() {
            self.counters.query_sets_ready = self.counters.query_sets_ready.saturating_add(1);
        } else {
            self.counters.query_sets_partial = self.counters.query_sets_partial.saturating_add(1);
        }
        self.store_sample(sample);
        Some(sample)
    }

    fn first_query(&self, frame_slot: usize) -> u32 {
        frame_slot as u32 * QUERIES_PER_FRAME
    }

    unsafe fn write_timestamp(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        frame_slot: usize,
        stage: CameraHwbGpuTimestampStage,
        endpoint: u32,
        pipeline_stage: vk::PipelineStageFlags,
    ) {
        let Some(query_pool) = self.query_pool else {
            return;
        };
        if frame_slot >= self.frame_slots {
            return;
        }
        let query =
            self.first_query(frame_slot) + stage.index() as u32 * QUERIES_PER_STAGE + endpoint;
        device.cmd_write_timestamp(command_buffer, pipeline_stage, query_pool, query);
    }

    fn store_sample(&mut self, sample: CameraHwbGpuTimestampSample) {
        if self.counters.samples_stored >= TELEMETRY_CAPACITY as u64 {
            self.counters.samples_overwritten = self.counters.samples_overwritten.saturating_add(1);
        } else {
            self.counters.samples_stored = self.counters.samples_stored.saturating_add(1);
        }
        self.samples[self.sample_write_index] = sample;
        self.sample_write_index = (self.sample_write_index + 1) % TELEMETRY_CAPACITY;
    }
}

fn decode_query_words(
    frame_id: u64,
    expected_mask: u32,
    words: &[TimestampQueryWord; CameraHwbGpuTimestampStage::COUNT * 2],
    timestamp_valid_bits: u32,
    timestamp_period_ns: f64,
) -> CameraHwbGpuTimestampSample {
    let mut sample = CameraHwbGpuTimestampSample {
        frame_id,
        expected_mask,
        ..CameraHwbGpuTimestampSample::default()
    };
    for index in 0..CameraHwbGpuTimestampStage::COUNT {
        let stage_bit = 1_u32 << index;
        if expected_mask & stage_bit == 0 {
            continue;
        }
        let start = words[index * 2];
        let end = words[index * 2 + 1];
        if start.available == 0 || end.available == 0 {
            continue;
        }
        sample.ready_mask |= stage_bit;
        sample.gpu_ns[index] = timestamp_delta_ns(
            start.value,
            end.value,
            timestamp_valid_bits,
            timestamp_period_ns,
        );
    }
    sample
}

fn timestamp_delta_ns(
    start_timestamp: u64,
    end_timestamp: u64,
    timestamp_valid_bits: u32,
    timestamp_period_ns: f64,
) -> u64 {
    if timestamp_valid_bits == 0 || timestamp_period_ns <= 0.0 {
        return 0;
    }
    let mask = if timestamp_valid_bits >= 64 {
        u64::MAX
    } else {
        (1_u64 << timestamp_valid_bits) - 1
    };
    let ticks = (end_timestamp & mask).wrapping_sub(start_timestamp & mask) & mask;
    (ticks as f64 * timestamp_period_ns).round().max(0.0) as u64
}

#[cfg(target_os = "android")]
fn runtime_property(name: &str) -> Option<String> {
    let name = CString::new(name).ok()?;
    let mut value = [0 as c_char; 92];
    let len = unsafe { __system_property_get(name.as_ptr(), value.as_mut_ptr()) };
    if len <= 0 {
        return None;
    }
    let raw = unsafe { CStr::from_ptr(value.as_ptr()) }
        .to_string_lossy()
        .trim()
        .to_ascii_lowercase();
    (!raw.is_empty()).then_some(raw)
}

#[cfg(not(target_os = "android"))]
fn runtime_property(_name: &str) -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timestamp_delta_masks_wraparound() {
        assert_eq!(timestamp_delta_ns(250, 5, 8, 2.0), 22);
        assert_eq!(timestamp_delta_ns(10, 15, 64, 1.5), 8);
        assert_eq!(timestamp_delta_ns(10, 15, 0, 1.0), 0);
    }

    #[test]
    fn decoder_requires_both_availability_words_for_expected_stage() {
        let mut words = [TimestampQueryWord::default(); CameraHwbGpuTimestampStage::COUNT * 2];
        words[0] = TimestampQueryWord {
            value: 10,
            available: 1,
        };
        words[1] = TimestampQueryWord {
            value: 20,
            available: 0,
        };
        let incomplete = decode_query_words(7, 1, &words, 64, 1.0);
        assert_eq!(incomplete.ready_mask, 0);
        words[1].available = 1;
        let complete = decode_query_words(7, 1, &words, 64, 1.0);
        assert_eq!(complete.ready_mask, 1);
        assert_eq!(complete.gpu_ns[0], 10);
        assert!(complete.query_set_ready());
    }
}
