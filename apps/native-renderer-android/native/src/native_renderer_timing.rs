use std::{
    sync::atomic::{AtomicBool, Ordering},
    time::Instant,
};

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
    PrivateParticleCompute,
    PrivateParticleSort,
    PrivateParticleDrawLeftEye,
    PrivateParticleDrawRightEye,
    PrivateParticleTileComposite,
    PrivateParticleHalfResDrawLeftEye,
    PrivateParticleHalfResDrawRightEye,
    PrivateParticleHalfResCompositeLeftEye,
    PrivateParticleHalfResCompositeRightEye,
    PrivateParticleMainFullResDrawLeftEye,
    PrivateParticleMainFullResDrawRightEye,
    PrivateParticleTracerHalfResDrawLeftEye,
    PrivateParticleTracerHalfResDrawRightEye,
    StimulusVolumeCompute,
    StimulusVolumeProjection,
    ProjectionComposite,
    PrivateParticleComputeDispatch,
    PrivateParticleComputePostDispatchSync,
}

impl GpuTimestampStage {
    const COUNT: u32 = 22;

    const fn index(self) -> u32 {
        match self {
            Self::CameraProjection => 0,
            Self::GuideGraph => 1,
            Self::HandSdf => 2,
            Self::HandMeshVisual => 3,
            Self::PrivateParticleCompute => 4,
            Self::PrivateParticleSort => 5,
            Self::PrivateParticleDrawLeftEye => 6,
            Self::PrivateParticleDrawRightEye => 7,
            Self::PrivateParticleTileComposite => 8,
            Self::PrivateParticleHalfResDrawLeftEye => 9,
            Self::PrivateParticleHalfResDrawRightEye => 10,
            Self::PrivateParticleHalfResCompositeLeftEye => 11,
            Self::PrivateParticleHalfResCompositeRightEye => 12,
            Self::PrivateParticleMainFullResDrawLeftEye => 13,
            Self::PrivateParticleMainFullResDrawRightEye => 14,
            Self::PrivateParticleTracerHalfResDrawLeftEye => 15,
            Self::PrivateParticleTracerHalfResDrawRightEye => 16,
            Self::StimulusVolumeCompute => 17,
            Self::StimulusVolumeProjection => 18,
            Self::ProjectionComposite => 19,
            Self::PrivateParticleComputeDispatch => 20,
            Self::PrivateParticleComputePostDispatchSync => 21,
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
    private_particle_compute_ms: f64,
    private_particle_compute_dispatch_ms: f64,
    private_particle_compute_post_dispatch_sync_ms: f64,
    private_particle_sort_ms: f64,
    private_particle_draw_ms: f64,
    private_particle_draw_left_eye_ms: f64,
    private_particle_draw_right_eye_ms: f64,
    private_particle_tile_composite_ms: f64,
    private_particle_half_res_draw_ms: f64,
    private_particle_half_res_draw_left_eye_ms: f64,
    private_particle_half_res_draw_right_eye_ms: f64,
    private_particle_half_res_composite_ms: f64,
    private_particle_half_res_composite_left_eye_ms: f64,
    private_particle_half_res_composite_right_eye_ms: f64,
    private_particle_main_full_res_draw_left_eye_ms: f64,
    private_particle_main_full_res_draw_right_eye_ms: f64,
    private_particle_tracer_half_res_draw_left_eye_ms: f64,
    private_particle_tracer_half_res_draw_right_eye_ms: f64,
    stage_active_mask: u32,
    measured_frame_id: Option<u64>,
    stimulus_volume_compute_ms: f64,
    stimulus_volume_projection_ms: f64,
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
            private_particle_compute_ms: -1.0,
            private_particle_compute_dispatch_ms: -1.0,
            private_particle_compute_post_dispatch_sync_ms: -1.0,
            private_particle_sort_ms: -1.0,
            private_particle_draw_ms: -1.0,
            private_particle_draw_left_eye_ms: -1.0,
            private_particle_draw_right_eye_ms: -1.0,
            private_particle_tile_composite_ms: -1.0,
            private_particle_half_res_draw_ms: -1.0,
            private_particle_half_res_draw_left_eye_ms: -1.0,
            private_particle_half_res_draw_right_eye_ms: -1.0,
            private_particle_half_res_composite_ms: -1.0,
            private_particle_half_res_composite_left_eye_ms: -1.0,
            private_particle_half_res_composite_right_eye_ms: -1.0,
            private_particle_main_full_res_draw_left_eye_ms: -1.0,
            private_particle_main_full_res_draw_right_eye_ms: -1.0,
            private_particle_tracer_half_res_draw_left_eye_ms: -1.0,
            private_particle_tracer_half_res_draw_right_eye_ms: -1.0,
            stage_active_mask: 0,
            measured_frame_id: None,
            stimulus_volume_compute_ms: -1.0,
            stimulus_volume_projection_ms: -1.0,
            projection_composite_ms: -1.0,
        }
    }

    fn ready(
        timestamp_valid_bits: u32,
        timestamp_period_ns: f64,
        query_values: &[u64],
        stage_active_mask: u32,
        measured_frame_id: Option<u64>,
    ) -> Self {
        let stage_ms = |stage: GpuTimestampStage| -> f64 {
            if stage_active_mask & (1_u32 << stage.index()) == 0 {
                return -1.0;
            }
            timestamp_delta_ms(
                query_values[(stage.index() * QUERIES_PER_STAGE) as usize],
                query_values[(stage.index() * QUERIES_PER_STAGE + 1) as usize],
                timestamp_valid_bits,
                timestamp_period_ns,
            )
        };
        let paired_stage_ms = |left: GpuTimestampStage, right: GpuTimestampStage| -> f64 {
            let left_ms = stage_ms(left);
            let right_ms = stage_ms(right);
            if left_ms < 0.0 || right_ms < 0.0 {
                -1.0
            } else {
                left_ms + right_ms
            }
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
            private_particle_compute_ms: stage_ms(GpuTimestampStage::PrivateParticleCompute),
            private_particle_compute_dispatch_ms: stage_ms(
                GpuTimestampStage::PrivateParticleComputeDispatch,
            ),
            private_particle_compute_post_dispatch_sync_ms: stage_ms(
                GpuTimestampStage::PrivateParticleComputePostDispatchSync,
            ),
            private_particle_sort_ms: stage_ms(GpuTimestampStage::PrivateParticleSort),
            private_particle_draw_ms: paired_stage_ms(
                GpuTimestampStage::PrivateParticleDrawLeftEye,
                GpuTimestampStage::PrivateParticleDrawRightEye,
            ),
            private_particle_draw_left_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleDrawLeftEye,
            ),
            private_particle_draw_right_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleDrawRightEye,
            ),
            private_particle_tile_composite_ms: stage_ms(
                GpuTimestampStage::PrivateParticleTileComposite,
            ),
            private_particle_half_res_draw_ms: paired_stage_ms(
                GpuTimestampStage::PrivateParticleHalfResDrawLeftEye,
                GpuTimestampStage::PrivateParticleHalfResDrawRightEye,
            ),
            private_particle_half_res_draw_left_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleHalfResDrawLeftEye,
            ),
            private_particle_half_res_draw_right_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleHalfResDrawRightEye,
            ),
            private_particle_half_res_composite_ms: paired_stage_ms(
                GpuTimestampStage::PrivateParticleHalfResCompositeLeftEye,
                GpuTimestampStage::PrivateParticleHalfResCompositeRightEye,
            ),
            private_particle_half_res_composite_left_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleHalfResCompositeLeftEye,
            ),
            private_particle_half_res_composite_right_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleHalfResCompositeRightEye,
            ),
            private_particle_main_full_res_draw_left_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleMainFullResDrawLeftEye,
            ),
            private_particle_main_full_res_draw_right_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleMainFullResDrawRightEye,
            ),
            private_particle_tracer_half_res_draw_left_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleTracerHalfResDrawLeftEye,
            ),
            private_particle_tracer_half_res_draw_right_eye_ms: stage_ms(
                GpuTimestampStage::PrivateParticleTracerHalfResDrawRightEye,
            ),
            stage_active_mask,
            measured_frame_id,
            stimulus_volume_compute_ms: stage_ms(GpuTimestampStage::StimulusVolumeCompute),
            stimulus_volume_projection_ms: stage_ms(GpuTimestampStage::StimulusVolumeProjection),
            projection_composite_ms: stage_ms(GpuTimestampStage::ProjectionComposite),
        }
    }

    pub(crate) fn stage_ms(self, stage: GpuTimestampStage) -> f64 {
        match stage {
            GpuTimestampStage::CameraProjection => self.camera_projection_ms,
            GpuTimestampStage::GuideGraph => self.guide_graph_ms,
            GpuTimestampStage::HandSdf => self.hand_sdf_ms,
            GpuTimestampStage::HandMeshVisual => self.hand_mesh_visual_ms,
            GpuTimestampStage::PrivateParticleCompute => self.private_particle_compute_ms,
            GpuTimestampStage::PrivateParticleComputeDispatch => {
                self.private_particle_compute_dispatch_ms
            }
            GpuTimestampStage::PrivateParticleComputePostDispatchSync => {
                self.private_particle_compute_post_dispatch_sync_ms
            }
            GpuTimestampStage::PrivateParticleSort => self.private_particle_sort_ms,
            GpuTimestampStage::PrivateParticleDrawLeftEye => self.private_particle_draw_left_eye_ms,
            GpuTimestampStage::PrivateParticleDrawRightEye => {
                self.private_particle_draw_right_eye_ms
            }
            GpuTimestampStage::PrivateParticleTileComposite => {
                self.private_particle_tile_composite_ms
            }
            GpuTimestampStage::PrivateParticleHalfResDrawLeftEye => {
                self.private_particle_half_res_draw_left_eye_ms
            }
            GpuTimestampStage::PrivateParticleHalfResDrawRightEye => {
                self.private_particle_half_res_draw_right_eye_ms
            }
            GpuTimestampStage::PrivateParticleHalfResCompositeLeftEye => {
                self.private_particle_half_res_composite_left_eye_ms
            }
            GpuTimestampStage::PrivateParticleHalfResCompositeRightEye => {
                self.private_particle_half_res_composite_right_eye_ms
            }
            GpuTimestampStage::PrivateParticleMainFullResDrawLeftEye => {
                self.private_particle_main_full_res_draw_left_eye_ms
            }
            GpuTimestampStage::PrivateParticleMainFullResDrawRightEye => {
                self.private_particle_main_full_res_draw_right_eye_ms
            }
            GpuTimestampStage::PrivateParticleTracerHalfResDrawLeftEye => {
                self.private_particle_tracer_half_res_draw_left_eye_ms
            }
            GpuTimestampStage::PrivateParticleTracerHalfResDrawRightEye => {
                self.private_particle_tracer_half_res_draw_right_eye_ms
            }
            GpuTimestampStage::StimulusVolumeCompute => self.stimulus_volume_compute_ms,
            GpuTimestampStage::StimulusVolumeProjection => self.stimulus_volume_projection_ms,
            GpuTimestampStage::ProjectionComposite => self.projection_composite_ms,
        }
    }

    fn stage_available(self, stage: GpuTimestampStage) -> bool {
        self.ready && (self.stage_active_mask & (1_u32 << stage.index())) != 0
    }

    pub(crate) fn marker_fields(self, submitted_frame_id: u64) -> String {
        let (measured_frame_id, measured_frame_lag_frames) = self
            .measured_frame_id
            .map(|measured| {
                (
                    measured.to_string(),
                    submitted_frame_id.saturating_sub(measured).to_string(),
                )
            })
            .unwrap_or_else(|| ("unavailable".to_string(), "unavailable".to_string()));
        let paired_stage_ms = |left: f64, right: f64| {
            if left < 0.0 || right < 0.0 {
                -1.0
            } else {
                left + right
            }
        };
        format!(
            "gpuTimestampQuerySupported={} gpuTimestampQueryReady={} gpuTimestampValidBits={} gpuTimestampPeriodNs={:.3} gpuTimestampFrameLag={} gpuTimestampSubmittedFrameId={} gpuTimestampMeasuredFrameId={} gpuTimestampMeasuredFrameLagFrames={} cameraProjectionGpuMs={:.3} guideGraphGpuMs={:.3} handSdfGpuMs={:.3} handMeshVisualGpuMs={:.3} privateParticleComputeGpuMs={:.3} privateParticleComputeTimingAvailable={} privateParticleComputeStageScope=single-dispatch-and-post-compute-sync privateParticleComputeDispatchGpuMs={:.3} privateParticleComputeDispatchTimingAvailable={} privateParticleComputeDispatchTimingScope=compute-shader-dispatch-stage privateParticleComputePostDispatchSyncGpuMs={:.3} privateParticleComputePostDispatchSyncTimingAvailable={} privateParticleComputePostDispatchSyncTimingScope=post-dispatch-visibility-and-diagnostic-host-sync-command-region privateParticleComputeSubstageTimingAvailable=false privateParticleSortGpuMs={:.3} privateParticleDrawGpuMs={:.3} privateParticleDrawLeftEyeGpuMs={:.3} privateParticleDrawRightEyeGpuMs={:.3} privateParticleTileCompositeGpuMs={:.3} privateParticleTileCompositeScope=private-particle-render-window privateParticleHalfResDrawGpuMs={:.3} privateParticleHalfResDrawLeftEyeGpuMs={:.3} privateParticleHalfResDrawRightEyeGpuMs={:.3} privateParticleHalfResCompositeGpuMs={:.3} privateParticleHalfResCompositeLeftEyeGpuMs={:.3} privateParticleHalfResCompositeRightEyeGpuMs={:.3} privateParticleHalfResTimingScope=offscreen-render-pass-and-projection-composite privateParticleMainFullResDrawGpuMs={:.3} privateParticleMainFullResDrawLeftEyeGpuMs={:.3} privateParticleMainFullResDrawRightEyeGpuMs={:.3} privateParticleMainFullResDrawTimingAvailable={} privateParticleMainFullResDrawTimingScope=mixed-path-main-particles-only-full-resolution-draw-command-region privateParticleTracerHalfResDrawGpuMs={:.3} privateParticleTracerHalfResDrawLeftEyeGpuMs={:.3} privateParticleTracerHalfResDrawRightEyeGpuMs={:.3} privateParticleTracerHalfResDrawTimingAvailable={} privateParticleTracerHalfResDrawTimingScope=mixed-path-tracer-instances-only-offscreen-half-resolution-draw-command-region stimulusVolumeComputeGpuMs={:.3} stimulusVolumeProjectionGpuMs={:.3} projectionCompositeGpuMs={:.3} gpuTimingScope=vulkan-timestamp-query",
            self.supported,
            self.ready,
            self.timestamp_valid_bits,
            self.timestamp_period_ns,
            GPU_TIMESTAMP_FRAME_LAG,
            submitted_frame_id,
            measured_frame_id,
            measured_frame_lag_frames,
            self.camera_projection_ms,
            self.guide_graph_ms,
            self.hand_sdf_ms,
            self.hand_mesh_visual_ms,
            self.private_particle_compute_ms,
            self.stage_available(GpuTimestampStage::PrivateParticleCompute),
            self.private_particle_compute_dispatch_ms,
            self.stage_available(GpuTimestampStage::PrivateParticleComputeDispatch),
            self.private_particle_compute_post_dispatch_sync_ms,
            self.stage_available(GpuTimestampStage::PrivateParticleComputePostDispatchSync),
            self.private_particle_sort_ms,
            self.private_particle_draw_ms,
            self.private_particle_draw_left_eye_ms,
            self.private_particle_draw_right_eye_ms,
            self.private_particle_tile_composite_ms,
            self.private_particle_half_res_draw_ms,
            self.private_particle_half_res_draw_left_eye_ms,
            self.private_particle_half_res_draw_right_eye_ms,
            self.private_particle_half_res_composite_ms,
            self.private_particle_half_res_composite_left_eye_ms,
            self.private_particle_half_res_composite_right_eye_ms,
            paired_stage_ms(
                self.private_particle_main_full_res_draw_left_eye_ms,
                self.private_particle_main_full_res_draw_right_eye_ms,
            ),
            self.private_particle_main_full_res_draw_left_eye_ms,
            self.private_particle_main_full_res_draw_right_eye_ms,
            self.stage_available(GpuTimestampStage::PrivateParticleMainFullResDrawLeftEye)
                && self.stage_available(GpuTimestampStage::PrivateParticleMainFullResDrawRightEye),
            paired_stage_ms(
                self.private_particle_tracer_half_res_draw_left_eye_ms,
                self.private_particle_tracer_half_res_draw_right_eye_ms,
            ),
            self.private_particle_tracer_half_res_draw_left_eye_ms,
            self.private_particle_tracer_half_res_draw_right_eye_ms,
            self.stage_available(GpuTimestampStage::PrivateParticleTracerHalfResDrawLeftEye)
                && self.stage_available(GpuTimestampStage::PrivateParticleTracerHalfResDrawRightEye),
            self.stimulus_volume_compute_ms,
            self.stimulus_volume_projection_ms,
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
    stage_active: Vec<AtomicBool>,
    frame_ids: Vec<Option<u64>>,
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
            stage_active: (0..frame_slots * GpuTimestampStage::COUNT as usize)
                .map(|_| AtomicBool::new(false))
                .collect(),
            frame_ids: vec![None; frame_slots],
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
            stage_active: (0..frame_slots * GpuTimestampStage::COUNT as usize)
                .map(|_| AtomicBool::new(false))
                .collect(),
            frame_ids: vec![None; frame_slots],
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
            vk::QueryResultFlags::TYPE_64,
        ) {
            Ok(()) => GpuStageTimings::ready(
                self.timestamp_valid_bits,
                self.timestamp_period_ns,
                &query_values,
                self.stage_active_for_frame(frame_slot),
                self.frame_ids[frame_slot],
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
        frame_id: u64,
    ) {
        let Some(query_pool) = self.query_pool else {
            return;
        };
        if frame_slot >= self.frame_slots {
            return;
        }
        self.reset_frame_metadata(frame_slot, frame_id);
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
        self.mark_stage_active(frame_slot, stage);
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

    pub(crate) unsafe fn write_compute_stage_start(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        stage: GpuTimestampStage,
    ) {
        self.mark_stage_active(frame_slot, stage);
        self.write_timestamp(
            device,
            cmd,
            frame_slot,
            stage,
            0,
            vk::PipelineStageFlags::COMPUTE_SHADER,
        );
    }

    pub(crate) unsafe fn write_compute_stage_end(
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
            vk::PipelineStageFlags::COMPUTE_SHADER,
        );
    }

    pub(crate) unsafe fn write_disabled_stage(
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
        self.write_timestamp(
            device,
            cmd,
            frame_slot,
            stage,
            1,
            vk::PipelineStageFlags::TOP_OF_PIPE,
        );
    }

    fn first_query(&self, frame_slot: usize) -> u32 {
        frame_slot as u32 * self.queries_per_frame
    }

    fn mark_stage_active(&self, frame_slot: usize, stage: GpuTimestampStage) {
        if frame_slot < self.frame_slots {
            self.stage_active
                [frame_slot * GpuTimestampStage::COUNT as usize + stage.index() as usize]
                .store(true, Ordering::Relaxed);
        }
    }

    fn reset_frame_metadata(&mut self, frame_slot: usize, frame_id: u64) {
        self.used_slots[frame_slot] = true;
        self.frame_ids[frame_slot] = Some(frame_id);
        for stage in 0..GpuTimestampStage::COUNT as usize {
            self.stage_active[frame_slot * GpuTimestampStage::COUNT as usize + stage]
                .store(false, Ordering::Relaxed);
        }
    }

    fn stage_active_for_frame(&self, frame_slot: usize) -> u32 {
        (0..GpuTimestampStage::COUNT as usize)
            .filter(|stage| {
                self.stage_active[frame_slot * GpuTimestampStage::COUNT as usize + *stage]
                    .load(Ordering::Relaxed)
            })
            .fold(0_u32, |mask, stage| mask | (1_u32 << stage))
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inactive_and_paired_stages_are_unavailable() {
        let mut values = vec![0_u64; (GpuTimestampStage::COUNT * QUERIES_PER_STAGE) as usize];
        let dispatch = GpuTimestampStage::PrivateParticleComputeDispatch;
        let first_query = (dispatch.index() * QUERIES_PER_STAGE) as usize;
        values[first_query] = 100;
        values[first_query + 1] = 300;
        let timings = GpuStageTimings::ready(64, 1.0, &values, 1_u32 << dispatch.index(), Some(7));

        assert_eq!(timings.private_particle_compute_ms, -1.0);
        assert_eq!(timings.private_particle_draw_ms, -1.0);
        assert!(timings.private_particle_compute_dispatch_ms > 0.0);
        let marker = timings.marker_fields(9);
        assert!(marker.contains("gpuTimestampSubmittedFrameId=9"));
        assert!(marker.contains("gpuTimestampMeasuredFrameId=7"));
        assert!(marker.contains("gpuTimestampMeasuredFrameLagFrames=2"));
        assert!(marker.contains("privateParticleComputeTimingAvailable=false"));
        assert!(marker.contains("privateParticleComputeDispatchTimingAvailable=true"));
    }

    #[test]
    fn reset_clears_active_mask_and_replaces_measured_frame_id() {
        let mut tracker = GpuTimestampTracker::disabled(1, 64, 1.0);
        tracker.mark_stage_active(0, GpuTimestampStage::PrivateParticleCompute);
        assert_ne!(tracker.stage_active_for_frame(0), 0);

        tracker.reset_frame_metadata(0, 12);
        assert_eq!(tracker.stage_active_for_frame(0), 0);
        assert_eq!(tracker.frame_ids[0], Some(12));
    }

    #[test]
    fn unavailable_marker_never_claims_a_duration_or_frame_identity() {
        let marker = GpuStageTimings::unavailable(0, 0.0).marker_fields(9);
        assert!(marker.contains("gpuTimestampQueryReady=false"));
        assert!(marker.contains("gpuTimestampMeasuredFrameId=unavailable"));
        assert!(marker.contains("privateParticleComputeGpuMs=-1.000"));
    }
}
