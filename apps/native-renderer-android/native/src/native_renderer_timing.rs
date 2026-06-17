use std::time::Instant;

use ash::vk;

pub(crate) const GPU_TIMESTAMP_FRAME_LAG: u32 = 2;

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct FrameCpuTimings {
    pub(crate) camera_acquire_import_ms: f64,
    pub(crate) guide_graph_ms: f64,
    pub(crate) live_hand_ms: f64,
    pub(crate) hand_sdf_prepare_ms: f64,
    pub(crate) hand_mesh_visual_ms: f64,
    pub(crate) projection_composite_ms: f64,
    pub(crate) command_record_ms: f64,
    pub(crate) swapchain_wait_ms: f64,
    pub(crate) queue_submit_ms: f64,
    pub(crate) openxr_end_frame_ms: f64,
}

impl FrameCpuTimings {
    pub(crate) fn marker_fields(self) -> String {
        format!(
            "cameraAcquireImportCpuMs={:.3} guideGraphCpuMs={:.3} liveHandLocateCpuMs={:.3} handSdfPrepareCpuMs={:.3} handMeshVisualCpuMs={:.3} projectionCompositeCpuMs={:.3} commandRecordCpuMs={:.3} swapchainWaitCpuMs={:.3} queueSubmitCpuMs={:.3} openxrEndFrameCpuMs={:.3} cpuTimingScope=host-recording-and-submit",
            self.camera_acquire_import_ms,
            self.guide_graph_ms,
            self.live_hand_ms,
            self.hand_sdf_prepare_ms,
            self.hand_mesh_visual_ms,
            self.projection_composite_ms,
            self.command_record_ms,
            self.swapchain_wait_ms,
            self.queue_submit_ms,
            self.openxr_end_frame_ms
        )
    }
}

pub(crate) fn elapsed_ms(started: Instant) -> f64 {
    started.elapsed().as_secs_f64() * 1000.0
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum GpuTimestampStage {
    CameraProjection,
    GuideGraph,
    HandSdf,
    HandMeshVisual,
    ProjectionComposite,
}

impl GpuTimestampStage {
    const COUNT: u32 = 5;

    const fn index(self) -> u32 {
        match self {
            Self::CameraProjection => 0,
            Self::GuideGraph => 1,
            Self::HandSdf => 2,
            Self::HandMeshVisual => 3,
            Self::ProjectionComposite => 4,
        }
    }
}

const QUERIES_PER_STAGE: u32 = 2;

#[derive(Clone, Copy, Debug)]
pub(crate) struct GpuStageTimings {
    supported: bool,
    ready: bool,
    timestamp_valid_bits: u32,
    timestamp_period_ns: f64,
    camera_projection_ms: f64,
    guide_graph_ms: f64,
    hand_sdf_ms: f64,
    hand_mesh_visual_ms: f64,
    projection_composite_ms: f64,
}

impl GpuStageTimings {
    pub(crate) fn unavailable(timestamp_valid_bits: u32, timestamp_period_ns: f64) -> Self {
        Self {
            supported: timestamp_valid_bits > 0 && timestamp_period_ns > 0.0,
            ready: false,
            timestamp_valid_bits,
            timestamp_period_ns,
            camera_projection_ms: -1.0,
            guide_graph_ms: -1.0,
            hand_sdf_ms: -1.0,
            hand_mesh_visual_ms: -1.0,
            projection_composite_ms: -1.0,
        }
    }

    fn ready(timestamp_valid_bits: u32, timestamp_period_ns: f64, query_values: &[u64]) -> Self {
        let stage_ms = |stage: GpuTimestampStage| -> f64 {
            timestamp_delta_ms(
                query_values[(stage.index() * QUERIES_PER_STAGE) as usize],
                query_values[(stage.index() * QUERIES_PER_STAGE + 1) as usize],
                timestamp_valid_bits,
                timestamp_period_ns,
            )
        };
        Self {
            supported: true,
            ready: true,
            timestamp_valid_bits,
            timestamp_period_ns,
            camera_projection_ms: stage_ms(GpuTimestampStage::CameraProjection),
            guide_graph_ms: stage_ms(GpuTimestampStage::GuideGraph),
            hand_sdf_ms: stage_ms(GpuTimestampStage::HandSdf),
            hand_mesh_visual_ms: stage_ms(GpuTimestampStage::HandMeshVisual),
            projection_composite_ms: stage_ms(GpuTimestampStage::ProjectionComposite),
        }
    }

    pub(crate) fn stage_ms(self, stage: GpuTimestampStage) -> f64 {
        match stage {
            GpuTimestampStage::CameraProjection => self.camera_projection_ms,
            GpuTimestampStage::GuideGraph => self.guide_graph_ms,
            GpuTimestampStage::HandSdf => self.hand_sdf_ms,
            GpuTimestampStage::HandMeshVisual => self.hand_mesh_visual_ms,
            GpuTimestampStage::ProjectionComposite => self.projection_composite_ms,
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "gpuTimestampQuerySupported={} gpuTimestampQueryReady={} gpuTimestampValidBits={} gpuTimestampPeriodNs={:.3} gpuTimestampFrameLag={} cameraProjectionGpuMs={:.3} guideGraphGpuMs={:.3} handSdfGpuMs={:.3} handMeshVisualGpuMs={:.3} projectionCompositeGpuMs={:.3} gpuTimingScope=vulkan-timestamp-query",
            self.supported,
            self.ready,
            self.timestamp_valid_bits,
            self.timestamp_period_ns,
            GPU_TIMESTAMP_FRAME_LAG,
            self.camera_projection_ms,
            self.guide_graph_ms,
            self.hand_sdf_ms,
            self.hand_mesh_visual_ms,
            self.projection_composite_ms
        )
    }
}

pub(crate) struct GpuTimestampTracker {
    query_pool: Option<vk::QueryPool>,
    frame_slots: usize,
    queries_per_frame: u32,
    timestamp_valid_bits: u32,
    timestamp_period_ns: f64,
    used_slots: Vec<bool>,
}

impl GpuTimestampTracker {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        frame_slots: usize,
        timestamp_valid_bits: u32,
        timestamp_period_ns: f64,
    ) -> Result<Self, String> {
        let queries_per_frame = GpuTimestampStage::COUNT * QUERIES_PER_STAGE;
        if frame_slots == 0 || timestamp_valid_bits == 0 || timestamp_period_ns <= 0.0 {
            return Ok(Self::disabled(
                frame_slots,
                timestamp_valid_bits,
                timestamp_period_ns,
            ));
        }
        let query_count = frame_slots
            .checked_mul(queries_per_frame as usize)
            .and_then(|count| u32::try_from(count).ok())
            .ok_or_else(|| "GPU timestamp query count overflow".to_string())?;
        let query_pool = device
            .create_query_pool(
                &vk::QueryPoolCreateInfo::default()
                    .query_type(vk::QueryType::TIMESTAMP)
                    .query_count(query_count),
                None,
            )
            .map_err(|error| format!("create GPU timestamp query pool: {error}"))?;
        Ok(Self {
            query_pool: Some(query_pool),
            frame_slots,
            queries_per_frame,
            timestamp_valid_bits,
            timestamp_period_ns,
            used_slots: vec![false; frame_slots],
        })
    }

    pub(crate) fn disabled(
        frame_slots: usize,
        timestamp_valid_bits: u32,
        timestamp_period_ns: f64,
    ) -> Self {
        Self {
            query_pool: None,
            frame_slots,
            queries_per_frame: GpuTimestampStage::COUNT * QUERIES_PER_STAGE,
            timestamp_valid_bits,
            timestamp_period_ns,
            used_slots: vec![false; frame_slots],
        }
    }

    pub(crate) fn config_marker_fields(&self) -> String {
        format!(
            "gpuTimestampQuerySupported={} gpuTimestampValidBits={} gpuTimestampPeriodNs={:.3} gpuTimestampFrameLag={} gpuTimingScope=vulkan-timestamp-query",
            self.query_pool.is_some(),
            self.timestamp_valid_bits,
            self.timestamp_period_ns,
            GPU_TIMESTAMP_FRAME_LAG
        )
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(query_pool) = self.query_pool.take() {
            device.destroy_query_pool(query_pool, None);
        }
    }

    pub(crate) unsafe fn read_frame(
        &mut self,
        device: &ash::Device,
        frame_slot: usize,
    ) -> GpuStageTimings {
        let Some(query_pool) = self.query_pool else {
            return GpuStageTimings::unavailable(
                self.timestamp_valid_bits,
                self.timestamp_period_ns,
            );
        };
        if frame_slot >= self.frame_slots || !self.used_slots[frame_slot] {
            return GpuStageTimings::unavailable(
                self.timestamp_valid_bits,
                self.timestamp_period_ns,
            );
        }
        let first_query = self.first_query(frame_slot);
        let mut query_values = vec![0_u64; self.queries_per_frame as usize];
        match device.get_query_pool_results(
            query_pool,
            first_query,
            &mut query_values,
            vk::QueryResultFlags::TYPE_64 | vk::QueryResultFlags::WAIT,
        ) {
            Ok(()) => GpuStageTimings::ready(
                self.timestamp_valid_bits,
                self.timestamp_period_ns,
                &query_values,
            ),
            Err(_) => {
                GpuStageTimings::unavailable(self.timestamp_valid_bits, self.timestamp_period_ns)
            }
        }
    }

    pub(crate) unsafe fn reset_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
    ) {
        let Some(query_pool) = self.query_pool else {
            return;
        };
        if frame_slot >= self.frame_slots {
            return;
        }
        self.used_slots[frame_slot] = true;
        device.cmd_reset_query_pool(
            cmd,
            query_pool,
            self.first_query(frame_slot),
            self.queries_per_frame,
        );
    }

    pub(crate) unsafe fn write_stage_start(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        stage: GpuTimestampStage,
    ) {
        self.write_timestamp(
            device,
            cmd,
            frame_slot,
            stage,
            0,
            vk::PipelineStageFlags::TOP_OF_PIPE,
        );
    }

    pub(crate) unsafe fn write_stage_end(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        stage: GpuTimestampStage,
    ) {
        self.write_timestamp(
            device,
            cmd,
            frame_slot,
            stage,
            1,
            vk::PipelineStageFlags::BOTTOM_OF_PIPE,
        );
    }

    fn first_query(&self, frame_slot: usize) -> u32 {
        frame_slot as u32 * self.queries_per_frame
    }

    unsafe fn write_timestamp(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        stage: GpuTimestampStage,
        endpoint: u32,
        pipeline_stage: vk::PipelineStageFlags,
    ) {
        let Some(query_pool) = self.query_pool else {
            return;
        };
        if frame_slot >= self.frame_slots {
            return;
        }
        let query = self.first_query(frame_slot) + stage.index() * QUERIES_PER_STAGE + endpoint;
        device.cmd_write_timestamp(cmd, pipeline_stage, query_pool, query);
    }
}

fn timestamp_delta_ms(
    start_timestamp: u64,
    end_timestamp: u64,
    timestamp_valid_bits: u32,
    timestamp_period_ns: f64,
) -> f64 {
    let mask = if timestamp_valid_bits >= 64 {
        u64::MAX
    } else {
        (1_u64 << timestamp_valid_bits) - 1
    };
    let start = start_timestamp & mask;
    let end = end_timestamp & mask;
    let ticks = if end >= start {
        end - start
    } else {
        mask - start + end + 1
    };
    ticks as f64 * timestamp_period_ns / 1_000_000.0
}
