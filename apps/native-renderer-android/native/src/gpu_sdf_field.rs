//! Native Vulkan skinned-mesh SDF compute and overlay for recorded hand replay frames.

use std::{ffi::CString, mem};

use ash::vk;

use crate::{
    camera_projection_metadata::TargetRect,
    recorded_hand_replay::{
        RecordedHandGpuPose, RecordedHandReplaySummary, RecordedHandSkinningFrame,
        RecordedMeshTargetTransform,
    },
};

const SDF_GRID_WIDTH: u32 = 64;
const SDF_GRID_HEIGHT: u32 = 48;
const SDF_TILE_GRID_WIDTH: u32 = 16;
const SDF_TILE_GRID_HEIGHT: u32 = 12;
const SDF_MAX_TRIANGLES_PER_TILE: u32 = 1024;
const RUNTIME_JOINT_POSE_STRIDE_BYTES: vk::DeviceSize =
    mem::size_of::<RecordedHandGpuPose>() as vk::DeviceSize;
const TIP_LENGTH_ROW_STRIDE_BYTES: vk::DeviceSize = 16;
const SKINNED_POSITION_STRIDE_BYTES: vk::DeviceSize = 16;
const FIELD_CELL_STRIDE_BYTES: vk::DeviceSize = 16;
const TILE_HEADER_STRIDE_BYTES: vk::DeviceSize = 16;
const TILE_INDEX_STRIDE_BYTES: vk::DeviceSize = 4;
const TRIANGLE_BOUNDS_STRIDE_BYTES: vk::DeviceSize = 16;
const SDF_NARROW_BAND_RADIUS: f32 = 0.018;

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct GpuSdfFieldFrameStats {
    pub(crate) ready: bool,
    pub(crate) skinning_ready: bool,
    pub(crate) overlay_visible: bool,
    pub(crate) frame_index: u32,
    pub(crate) timestamp_ns: u64,
    pub(crate) mesh_vertex_count: u32,
    pub(crate) mesh_triangle_count: u32,
    pub(crate) grid_width: u32,
    pub(crate) grid_height: u32,
    pub(crate) cell_count: u32,
    pub(crate) dispatch_count: u64,
    pub(crate) field_update_dispatched: bool,
    pub(crate) field_reused: bool,
    pub(crate) field_cache_hits: u64,
    pub(crate) sdf_update_period_frames: u64,
    pub(crate) triangle_bounds_ready: bool,
    pub(crate) tile_bins_ready: bool,
    pub(crate) narrow_band_ready: bool,
    pub(crate) tile_grid_width: u32,
    pub(crate) tile_grid_height: u32,
    pub(crate) max_triangles_per_tile: u32,
    pub(crate) tile_bin_dispatch_count: u64,
    pub(crate) source_vertex_buffer_bytes: u64,
    pub(crate) source_triangle_buffer_bytes: u64,
    pub(crate) bind_joint_pose_buffer_bytes: u64,
    pub(crate) bind_joint_source_buffer_bytes: u64,
    pub(crate) runtime_joint_pose_buffer_bytes: u64,
    pub(crate) tip_length_buffer_bytes: u64,
    pub(crate) compact_joint_frame_upload_bytes: u64,
    pub(crate) joint_matrix_buffer_bytes: u64,
    pub(crate) skinned_position_buffer_bytes: u64,
    pub(crate) field_buffer_bytes: u64,
    pub(crate) triangle_bounds_buffer_bytes: u64,
    pub(crate) tile_header_buffer_bytes: u64,
    pub(crate) tile_index_buffer_bytes: u64,
    pub(crate) source_mesh_buffers_resident: bool,
    pub(crate) source_mesh_buffers_reused: bool,
    pub(crate) derived_buffers_resident: bool,
    pub(crate) derived_buffers_reused: bool,
    pub(crate) live_compact_input_frame: bool,
    pub(crate) target_transform_source: &'static str,
}

impl GpuSdfFieldFrameStats {
    pub(crate) fn unavailable(replay: &RecordedHandReplaySummary, frame_count: u64) -> Self {
        let frame = replay.skinning_frame_for_count(frame_count);
        Self {
            frame_index: frame.map(|frame| frame.frame_index).unwrap_or(0),
            timestamp_ns: frame.map(|frame| frame.timestamp_ns).unwrap_or(0),
            mesh_vertex_count: replay.skinning_vertices.len() as u32,
            mesh_triangle_count: replay.skinning_triangles.len() as u32,
            grid_width: SDF_GRID_WIDTH,
            grid_height: SDF_GRID_HEIGHT,
            cell_count: SDF_GRID_WIDTH * SDF_GRID_HEIGHT,
            tile_grid_width: SDF_TILE_GRID_WIDTH,
            tile_grid_height: SDF_TILE_GRID_HEIGHT,
            max_triangles_per_tile: SDF_MAX_TRIANGLES_PER_TILE,
            source_vertex_buffer_bytes: replay.skinning_source_vertex_buffer_bytes(),
            source_triangle_buffer_bytes: replay.skinning_triangle_buffer_bytes(),
            bind_joint_pose_buffer_bytes: replay.bind_joint_pose_buffer_bytes(),
            bind_joint_source_buffer_bytes: replay.bind_joint_source_buffer_bytes(),
            runtime_joint_pose_buffer_bytes: replay.runtime_joint_pose_frame_buffer_bytes(),
            tip_length_buffer_bytes: replay.tip_length_frame_buffer_bytes(),
            compact_joint_frame_upload_bytes: replay.compact_joint_frame_buffer_bytes(),
            ..Default::default()
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "handMeshSkinningReady={} handMeshSkinningStage=compact-joint-gpu-skinning handMeshSkinningKernel=compact-joint-pose-tip-length handMeshSkinningInputSource=recorded-compatible-compact-joint-frame handMeshSkinningOutputBuffer=resident-skinned-position-buffer handMeshSkinningConsumesSdfField=false sdfFieldVisualReady={} sdfFieldVisualEffectVisible={} dynamicSdfReady={} sdfVisualEffectVisible={} sdfFieldSource=recorded-compact-joint-skinned-mesh-gpu-field legacySdfFieldSource=recorded-validation-mesh-target-space-gpu-field sdfComputePath=native-vulkan-compute-recorded-skinned-mesh-sdf-field legacySdfComputePath=native-vulkan-compute-recorded-validation-mesh-sdf-field gpuSkinningReady={} gpuSdfFieldReady={} gpuSdfOverlayVisible={} gpuSdfFrame={} gpuSdfTimestampNs={} gpuSdfMeshVertexCount={} gpuSdfMeshTriangleCount={} gpuSdfGrid={}x{} gpuSdfCellCount={} gpuSdfDispatchCount={} sdfUpdateCadenceFrames={} sdfFieldUpdateDispatched={} sdfFieldReused={} sdfFieldCacheHits={} sdfTriangleBoundsReady={} sdfTriangleBinsReady={} sdfTileBinsReady={} sdfNarrowBandReady={} sdfNarrowBandMode=tile-local-triangle-bin-band-cull sdfNarrowBandRadius={:.3} sdfTileGrid={}x{} sdfTileCount={} sdfMaxTrianglesPerTile={} sdfTileBinDispatchCount={} sdfSeparateVisualParticleResolutions=true sdfVisualGrid={}x{} sdfParticleGrid=0x0 gpuSdfMeshVertexBufferBytes={} gpuSdfSourceVertexBufferBytes={} gpuSdfSourceTriangleBufferBytes={} gpuSdfBindJointPoseBufferBytes={} gpuSdfBindJointSourceBufferBytes={} gpuSdfRuntimeJointPoseBufferBytes={} gpuSdfTipLengthBufferBytes={} gpuSdfCompactJointFrameUploadBytes={} gpuSdfJointMatrixBufferBytes={} gpuSdfSkinnedPositionBufferBytes={} skinnedPositionBufferCoordinateSpace=openxr-reference-space gpuSdfFieldBufferBytes={} gpuSdfTriangleBoundsBufferBytes={} gpuSdfTileHeaderBufferBytes={} gpuSdfTileIndexBufferBytes={} sourceMeshBuffersResident={} sourceMeshBuffersReused={} derivedBuffersResident={} derivedBuffersReused={} cpuSdfPerFrame=false meshToSdfKernel={} targetSpaceMeshToSdfKernelAvailable=true fullSkinnedMeshSdfReady={} compactJointSkinningKernel={} jointMatrixSkinningKernel=false jointMatrixUploadPerFrame=false compactJointPoseUploadPerFrame=true sourceMeshToSdfKernel={} fieldSamplingKernel=false fieldParticleKernel=false",
            self.skinning_ready,
            self.ready,
            self.overlay_visible,
            self.ready,
            self.overlay_visible,
            self.skinning_ready,
            self.ready,
            self.overlay_visible,
            self.frame_index,
            self.timestamp_ns,
            self.mesh_vertex_count,
            self.mesh_triangle_count,
            self.grid_width,
            self.grid_height,
            self.cell_count,
            self.dispatch_count,
            self.sdf_update_period_frames,
            self.field_update_dispatched,
            self.field_reused,
            self.field_cache_hits,
            self.triangle_bounds_ready,
            self.tile_bins_ready,
            self.tile_bins_ready,
            self.narrow_band_ready,
            SDF_NARROW_BAND_RADIUS,
            self.tile_grid_width,
            self.tile_grid_height,
            self.tile_grid_width * self.tile_grid_height,
            self.max_triangles_per_tile,
            self.tile_bin_dispatch_count,
            self.grid_width,
            self.grid_height,
            self.source_vertex_buffer_bytes,
            self.source_vertex_buffer_bytes,
            self.source_triangle_buffer_bytes,
            self.bind_joint_pose_buffer_bytes,
            self.bind_joint_source_buffer_bytes,
            self.runtime_joint_pose_buffer_bytes,
            self.tip_length_buffer_bytes,
            self.compact_joint_frame_upload_bytes,
            self.joint_matrix_buffer_bytes,
            self.skinned_position_buffer_bytes,
            self.field_buffer_bytes,
            self.triangle_bounds_buffer_bytes,
            self.tile_header_buffer_bytes,
            self.tile_index_buffer_bytes,
            self.source_mesh_buffers_resident,
            self.source_mesh_buffers_reused,
            self.derived_buffers_resident,
            self.derived_buffers_reused,
            self.field_update_dispatched,
            self.ready,
            self.skinning_ready,
            self.field_update_dispatched,
        )
        + &format!(
            " sdfCompactInputSource={} liveSdfVisualAcceptance={}",
            if self.live_compact_input_frame {
                "live-meta-openxr-hand-tracking"
            } else {
                "recorded-replay"
            },
            if self.live_compact_input_frame {
                "pending-repeat-headset-visual-proof"
            } else {
                "not-live-input"
            }
        )
        + &format!(
            " handMeshTargetTransformSource={} liveHandMeshTargetLocalNormalized=false sdfProjectionInputCoordinateSpace=openxr-reference-space sdfProjectionOutputCoordinateSpace=metadata-target-screen-uv",
            self.target_transform_source,
        )
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct SkinnedHandMeshDrawResources {
    pub(crate) skinned_position_buffer: vk::Buffer,
    pub(crate) skinned_position_buffer_bytes: vk::DeviceSize,
    pub(crate) triangle_buffer: vk::Buffer,
    pub(crate) triangle_buffer_bytes: vk::DeviceSize,
    pub(crate) vertex_count: u32,
    pub(crate) triangle_count: u32,
    pub(crate) target_transform: RecordedMeshTargetTransform,
}

pub(crate) struct GpuSdfFieldRenderer {
    resources: GpuSdfResources,
    target_transform: RecordedMeshTargetTransform,
    skinning_dispatch_count: u64,
    tile_bin_dispatch_count: u64,
    sdf_dispatch_count: u64,
    sdf_field_initialized: bool,
    sdf_field_cache_hits: u64,
}

impl GpuSdfFieldRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        replay: &RecordedHandReplaySummary,
    ) -> Result<Self, String> {
        if !replay.has_gpu_skinning_sdf_payload() {
            return Err(
                "recorded hand replay has no resident skinning/SDF payload for GPU path"
                    .to_string(),
            );
        }
        let target_transform = replay
            .mesh_target_transform
            .ok_or_else(|| "recorded hand replay missing mesh target transform".to_string())?;
        let resources = GpuSdfResources::new(device, memory_properties, render_pass, replay)?;
        crate::marker(
            "gpu-sdf-field",
            format!(
                "status=created handMeshSkinningStage=compact-joint-gpu-skinning handMeshSkinningKernel=compact-joint-pose-tip-length handMeshSkinningOutputBuffer=resident-skinned-position-buffer handMeshSkinningConsumesSdfField=false sdfFieldVisualPath=optional-gpu-field-overlay sdfComputePath=native-vulkan-compute-recorded-skinned-mesh-sdf-field legacySdfComputePath=native-vulkan-compute-recorded-validation-mesh-sdf-field grid={}x{} meshVertexCapacity={} meshTriangleCapacity={} sourceVertexBufferBytes={} sourceTriangleBufferBytes={} bindJointPoseBufferBytes={} bindJointSourceBufferBytes={} runtimeJointPoseBufferBytes={} tipLengthBufferBytes={} compactJointFrameUploadBytes={} jointMatrixBufferBytes=0 skinnedPositionBufferBytes={} skinnedPositionBufferCoordinateSpace=openxr-reference-space fieldBufferBytes={} triangleBoundsBufferBytes={} tileHeaderBufferBytes={} tileIndexBufferBytes={} tileGrid={}x{} maxTrianglesPerTile={} sourceMeshBuffersResident=true sourceMeshBuffersReused=false derivedBuffersResident=true derivedBuffersReused=false cpuSdfPerFrame=false meshToSdfKernel=false targetSpaceMeshToSdfKernelAvailable=true sdfProjectionInputCoordinateSpace=openxr-reference-space sdfProjectionOutputCoordinateSpace=metadata-target-screen-uv fullSkinnedMeshSdfReady=false compactJointSkinningKernel=true jointMatrixSkinningKernel=false jointMatrixUploadPerFrame=false compactJointPoseUploadPerFrame=true sourceMeshToSdfKernel=false sdfFieldUpdateDispatched=false sdfFieldReused=false sdfFieldCacheHits=0 sdfTriangleBoundsReady=false sdfTriangleBinsReady=false sdfTileBinsReady=false sdfNarrowBandReady=false sdfSeparateVisualParticleResolutions=true sdfVisualGrid={}x{} sdfParticleGrid=0x0",
                SDF_GRID_WIDTH,
                SDF_GRID_HEIGHT,
                replay.skinning_vertices.len(),
                replay.skinning_triangles.len(),
                resources.source_vertex_buffer.bytes,
                resources.triangle_buffer.bytes,
                resources.bind_joint_pose_buffer.bytes,
                resources.bind_joint_source_buffer.bytes,
                resources.runtime_joint_pose_buffer.bytes,
                resources.tip_length_buffer.bytes,
                replay.compact_joint_frame_buffer_bytes(),
                resources.skinned_position_buffer.bytes,
                resources.field_buffer.bytes,
                resources.triangle_bounds_buffer.bytes,
                resources.tile_header_buffer.bytes,
                resources.tile_index_buffer.bytes,
                SDF_TILE_GRID_WIDTH,
                SDF_TILE_GRID_HEIGHT,
                SDF_MAX_TRIANGLES_PER_TILE,
                SDF_GRID_WIDTH,
                SDF_GRID_HEIGHT
            ),
        );
        Ok(Self {
            resources,
            target_transform,
            skinning_dispatch_count: 0,
            tile_bin_dispatch_count: 0,
            sdf_dispatch_count: 0,
            sdf_field_initialized: false,
            sdf_field_cache_hits: 0,
        })
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        self.resources.destroy(device);
    }

    pub(crate) fn skinned_hand_mesh_draw_resources(&self) -> SkinnedHandMeshDrawResources {
        SkinnedHandMeshDrawResources {
            skinned_position_buffer: self.resources.skinned_position_buffer.buffer,
            skinned_position_buffer_bytes: self.resources.skinned_position_buffer.bytes,
            triangle_buffer: self.resources.triangle_buffer.buffer,
            triangle_buffer_bytes: self.resources.triangle_buffer.bytes,
            vertex_count: self.resources.vertex_count,
            triangle_count: self.resources.triangle_count,
            target_transform: self.target_transform,
        }
    }

    pub(crate) unsafe fn record_compute_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        replay: &RecordedHandReplaySummary,
        frame_count: u64,
        sdf_visual_enabled: bool,
        sdf_update_period_frames: u64,
        live_hand_frame: Option<&RecordedHandSkinningFrame>,
        allow_recorded_replay_fallback: bool,
    ) -> Result<GpuSdfFieldFrameStats, String> {
        let frame = live_hand_frame.or_else(|| {
            allow_recorded_replay_fallback
                .then(|| replay.skinning_frame_for_count(frame_count))
                .flatten()
        });
        let Some(frame) = frame else {
            return Ok(GpuSdfFieldFrameStats::unavailable(replay, frame_count));
        };
        self.write_compact_frame(device, &frame.runtime_joint_poses, &frame.tip_length_rows)?;

        let vertex_count = replay.skinning_vertices.len() as u32;
        let triangle_count = replay.skinning_triangles.len() as u32;
        if vertex_count == 0 || triangle_count == 0 {
            return Ok(GpuSdfFieldFrameStats::unavailable(replay, frame_count));
        }
        let live_compact_input_frame = live_hand_frame.is_some();
        let (target_transform, target_transform_source) =
            target_transform_for_frame(frame, self.target_transform, live_compact_input_frame);

        let host_to_compute = [
            host_to_compute_barrier(&self.resources.source_vertex_buffer),
            host_to_compute_barrier(&self.resources.triangle_buffer),
            host_to_compute_barrier(&self.resources.bind_joint_pose_buffer),
            host_to_compute_barrier(&self.resources.bind_joint_source_buffer),
            host_to_compute_barrier(&self.resources.runtime_joint_pose_buffer),
            host_to_compute_barrier(&self.resources.tip_length_buffer),
        ];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::HOST,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &host_to_compute,
            &[],
        );

        let push = SdfComputePush {
            dims: [
                vertex_count,
                triangle_count,
                SDF_GRID_WIDTH,
                SDF_GRID_HEIGHT,
            ],
            target0: [
                target_transform.center[0],
                target_transform.center[1],
                target_transform.min_z,
                target_transform.radius,
            ],
            target1: [target_transform.center[2], target_transform.depth, 0.0, 0.0],
            params: [SDF_NARROW_BAND_RADIUS, 0.0, 0.0, 0.0],
        };

        device.cmd_bind_pipeline(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.resources.skinning_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.resources.compute_pipeline_layout,
            0,
            &[self.resources.compute_descriptor_set],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.resources.compute_pipeline_layout,
            vk::ShaderStageFlags::COMPUTE,
            0,
            as_bytes(&push),
        );
        device.cmd_dispatch(cmd, vertex_count.div_ceil(64).max(1), 1, 1);

        let skin_to_read = [vk::BufferMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ)
            .buffer(self.resources.skinned_position_buffer.buffer)
            .offset(0)
            .size(self.resources.skinned_position_buffer.bytes)];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::VERTEX_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &skin_to_read,
            &[],
        );

        let mut sdf_ready = false;
        let mut field_update_dispatched = false;
        let mut field_reused = false;
        let sdf_update_period_frames = sdf_update_period_frames.max(1);
        if sdf_visual_enabled {
            let should_update_sdf =
                !self.sdf_field_initialized || frame_count % sdf_update_period_frames == 0;
            if should_update_sdf {
                self.record_tile_bins(device, cmd, &push, triangle_count);
                device.cmd_bind_pipeline(
                    cmd,
                    vk::PipelineBindPoint::COMPUTE,
                    self.resources.sdf_pipeline,
                );
                device.cmd_push_constants(
                    cmd,
                    self.resources.compute_pipeline_layout,
                    vk::ShaderStageFlags::COMPUTE,
                    0,
                    as_bytes(&push),
                );
                device.cmd_dispatch(
                    cmd,
                    (SDF_GRID_WIDTH * SDF_GRID_HEIGHT).div_ceil(64).max(1),
                    1,
                    1,
                );

                let compute_to_fragment = [field_to_fragment_barrier(&self.resources.field_buffer)];
                device.cmd_pipeline_barrier(
                    cmd,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::FRAGMENT_SHADER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &compute_to_fragment,
                    &[],
                );
                self.sdf_dispatch_count = self.sdf_dispatch_count.saturating_add(1);
                self.tile_bin_dispatch_count = self.tile_bin_dispatch_count.saturating_add(1);
                self.sdf_field_initialized = true;
                field_update_dispatched = true;
            } else {
                let cached_to_fragment = [field_to_fragment_barrier(&self.resources.field_buffer)];
                device.cmd_pipeline_barrier(
                    cmd,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::FRAGMENT_SHADER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &cached_to_fragment,
                    &[],
                );
                self.sdf_field_cache_hits = self.sdf_field_cache_hits.saturating_add(1);
                field_reused = true;
            }
            sdf_ready = true;
        }

        self.skinning_dispatch_count = self.skinning_dispatch_count.saturating_add(1);
        Ok(GpuSdfFieldFrameStats {
            ready: sdf_ready,
            skinning_ready: true,
            overlay_visible: sdf_ready,
            frame_index: frame.frame_index,
            timestamp_ns: frame.timestamp_ns,
            mesh_vertex_count: vertex_count,
            mesh_triangle_count: triangle_count,
            grid_width: SDF_GRID_WIDTH,
            grid_height: SDF_GRID_HEIGHT,
            cell_count: SDF_GRID_WIDTH * SDF_GRID_HEIGHT,
            dispatch_count: self.sdf_dispatch_count,
            field_update_dispatched,
            field_reused,
            field_cache_hits: self.sdf_field_cache_hits,
            sdf_update_period_frames,
            triangle_bounds_ready: sdf_ready && self.sdf_field_initialized,
            tile_bins_ready: sdf_ready && self.sdf_field_initialized,
            narrow_band_ready: sdf_ready && self.sdf_field_initialized,
            tile_grid_width: SDF_TILE_GRID_WIDTH,
            tile_grid_height: SDF_TILE_GRID_HEIGHT,
            max_triangles_per_tile: SDF_MAX_TRIANGLES_PER_TILE,
            tile_bin_dispatch_count: self.tile_bin_dispatch_count,
            source_vertex_buffer_bytes: self.resources.source_vertex_buffer.bytes as u64,
            source_triangle_buffer_bytes: self.resources.triangle_buffer.bytes as u64,
            bind_joint_pose_buffer_bytes: self.resources.bind_joint_pose_buffer.bytes as u64,
            bind_joint_source_buffer_bytes: self.resources.bind_joint_source_buffer.bytes as u64,
            runtime_joint_pose_buffer_bytes: self.resources.runtime_joint_pose_buffer.bytes as u64,
            tip_length_buffer_bytes: self.resources.tip_length_buffer.bytes as u64,
            compact_joint_frame_upload_bytes: compact_frame_upload_bytes(frame),
            joint_matrix_buffer_bytes: 0,
            skinned_position_buffer_bytes: self.resources.skinned_position_buffer.bytes as u64,
            field_buffer_bytes: self.resources.field_buffer.bytes as u64,
            triangle_bounds_buffer_bytes: self.resources.triangle_bounds_buffer.bytes as u64,
            tile_header_buffer_bytes: self.resources.tile_header_buffer.bytes as u64,
            tile_index_buffer_bytes: self.resources.tile_index_buffer.bytes as u64,
            source_mesh_buffers_resident: true,
            source_mesh_buffers_reused: self.skinning_dispatch_count > 1,
            derived_buffers_resident: true,
            derived_buffers_reused: self.skinning_dispatch_count > 1,
            live_compact_input_frame,
            target_transform_source,
        })
    }

    pub(crate) unsafe fn record_overlay_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        target_rect: TargetRect,
    ) {
        let push = SdfOverlayPush {
            target_rect: [
                target_rect.x,
                target_rect.y,
                target_rect.width,
                target_rect.height,
            ],
            dims: [SDF_GRID_WIDTH, SDF_GRID_HEIGHT, 0, 0],
            color: [0.05, 0.86, 1.0, 0.50],
        };
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: extent.width as f32,
            height: extent.height as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = [vk::Rect2D {
            offset: vk::Offset2D {
                x: (extent.width as f32 * target_rect.x).round() as i32,
                y: (extent.height as f32 * target_rect.y).round() as i32,
            },
            extent: vk::Extent2D {
                width: (extent.width as f32 * target_rect.width).round().max(1.0) as u32,
                height: (extent.height as f32 * target_rect.height).round().max(1.0) as u32,
            },
        }];
        device.cmd_bind_pipeline(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.resources.overlay_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.resources.overlay_pipeline_layout,
            0,
            &[self.resources.overlay_descriptor_set],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.resources.overlay_pipeline_layout,
            vk::ShaderStageFlags::FRAGMENT,
            0,
            as_bytes(&push),
        );
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_draw(cmd, 3, 1, 0, 0);
    }

    unsafe fn write_compact_frame(
        &self,
        device: &ash::Device,
        runtime_joint_poses: &[RecordedHandGpuPose],
        tip_length_rows: &[[f32; 4]],
    ) -> Result<(), String> {
        let runtime_byte_count = mem::size_of_val(runtime_joint_poses) as vk::DeviceSize;
        if runtime_byte_count > self.resources.runtime_joint_pose_buffer.bytes {
            return Err(
                "recorded hand runtime joint pose frame exceeds resident buffer".to_string(),
            );
        }
        let runtime_mapped = device
            .map_memory(
                self.resources.runtime_joint_pose_buffer.memory,
                0,
                self.resources.runtime_joint_pose_buffer.bytes,
                vk::MemoryMapFlags::empty(),
            )
            .map_err(|error| format!("map recorded hand runtime joint pose buffer: {error}"))?
            .cast::<RecordedHandGpuPose>();
        runtime_mapped
            .copy_from_nonoverlapping(runtime_joint_poses.as_ptr(), runtime_joint_poses.len());
        device.unmap_memory(self.resources.runtime_joint_pose_buffer.memory);

        let tip_byte_count = mem::size_of_val(tip_length_rows) as vk::DeviceSize;
        if tip_byte_count > self.resources.tip_length_buffer.bytes {
            return Err("recorded hand tip length frame exceeds resident buffer".to_string());
        }
        let tip_mapped = device
            .map_memory(
                self.resources.tip_length_buffer.memory,
                0,
                self.resources.tip_length_buffer.bytes,
                vk::MemoryMapFlags::empty(),
            )
            .map_err(|error| format!("map recorded hand tip length buffer: {error}"))?
            .cast::<[f32; 4]>();
        tip_mapped.copy_from_nonoverlapping(tip_length_rows.as_ptr(), tip_length_rows.len());
        device.unmap_memory(self.resources.tip_length_buffer.memory);
        Ok(())
    }

    unsafe fn record_tile_bins(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        push: &SdfComputePush,
        triangle_count: u32,
    ) {
        device.cmd_fill_buffer(
            cmd,
            self.resources.tile_header_buffer.buffer,
            0,
            self.resources.tile_header_buffer.bytes,
            0,
        );
        device.cmd_fill_buffer(
            cmd,
            self.resources.tile_index_buffer.buffer,
            0,
            self.resources.tile_index_buffer.bytes,
            u32::MAX,
        );
        let transfer_to_compute = [
            transfer_to_compute_barrier(&self.resources.tile_header_buffer),
            transfer_to_compute_barrier(&self.resources.tile_index_buffer),
        ];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &transfer_to_compute,
            &[],
        );

        device.cmd_bind_pipeline(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.resources.tile_bin_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.resources.compute_pipeline_layout,
            0,
            &[self.resources.compute_descriptor_set],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.resources.compute_pipeline_layout,
            vk::ShaderStageFlags::COMPUTE,
            0,
            as_bytes(push),
        );
        device.cmd_dispatch(cmd, triangle_count.div_ceil(64).max(1), 1, 1);

        let bins_to_sdf = [
            compute_write_to_compute_read_barrier(&self.resources.tile_header_buffer),
            compute_write_to_compute_read_barrier(&self.resources.tile_index_buffer),
            compute_write_to_compute_read_barrier(&self.resources.triangle_bounds_buffer),
        ];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &bins_to_sdf,
            &[],
        );
    }
}

fn target_transform_for_frame(
    frame: &RecordedHandSkinningFrame,
    fallback: RecordedMeshTargetTransform,
    live_compact_input_frame: bool,
) -> (RecordedMeshTargetTransform, &'static str) {
    if !live_compact_input_frame {
        return (fallback, "recorded-validation-mesh-bounds");
    }

    live_runtime_joint_target_transform(&frame.runtime_joint_poses)
        .map(|target| (target, "live-runtime-joint-bounds"))
        .unwrap_or((fallback, "live-runtime-joint-bounds-fallback-recorded"))
}

fn live_runtime_joint_target_transform(
    runtime_joint_poses: &[RecordedHandGpuPose],
) -> Option<RecordedMeshTargetTransform> {
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    let mut finite_count = 0_usize;

    for pose in runtime_joint_poses {
        let translation = [
            pose.translation_pad[0],
            pose.translation_pad[1],
            pose.translation_pad[2],
        ];
        if !translation.iter().all(|value| value.is_finite()) {
            continue;
        }
        finite_count += 1;
        for axis in 0..3 {
            min[axis] = min[axis].min(translation[axis]);
            max[axis] = max[axis].max(translation[axis]);
        }
    }

    if finite_count < 5 {
        return None;
    }

    let padding = 0.065_f32;
    let width = (max[0] - min[0] + padding * 2.0).max(0.12);
    let height = (max[1] - min[1] + padding * 2.0).max(0.12);
    let depth = (max[2] - min[2] + padding * 2.0).max(0.12);
    let radius = width.max(height).max(depth) * 0.5;

    Some(RecordedMeshTargetTransform {
        center: [
            (min[0] + max[0]) * 0.5,
            (min[1] + max[1]) * 0.5,
            (min[2] + max[2]) * 0.5,
        ],
        radius,
        min_z: min[2] - padding,
        depth,
    })
}

struct GpuSdfResources {
    descriptor_pool: vk::DescriptorPool,
    compute_descriptor_set_layout: vk::DescriptorSetLayout,
    overlay_descriptor_set_layout: vk::DescriptorSetLayout,
    compute_descriptor_set: vk::DescriptorSet,
    overlay_descriptor_set: vk::DescriptorSet,
    compute_pipeline_layout: vk::PipelineLayout,
    overlay_pipeline_layout: vk::PipelineLayout,
    skinning_pipeline: vk::Pipeline,
    tile_bin_pipeline: vk::Pipeline,
    sdf_pipeline: vk::Pipeline,
    overlay_pipeline: vk::Pipeline,
    source_vertex_buffer: OwnedBuffer,
    triangle_buffer: OwnedBuffer,
    bind_joint_pose_buffer: OwnedBuffer,
    bind_joint_source_buffer: OwnedBuffer,
    runtime_joint_pose_buffer: OwnedBuffer,
    tip_length_buffer: OwnedBuffer,
    skinned_position_buffer: OwnedBuffer,
    field_buffer: OwnedBuffer,
    triangle_bounds_buffer: OwnedBuffer,
    tile_header_buffer: OwnedBuffer,
    tile_index_buffer: OwnedBuffer,
    vertex_count: u32,
    triangle_count: u32,
}

impl GpuSdfResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        replay: &RecordedHandReplaySummary,
    ) -> Result<Self, String> {
        let source_vertex_buffer = OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF source vertices",
            &replay.skinning_vertices,
        )?;
        let triangle_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF triangles",
            &replay.skinning_triangles,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let bind_joint_pose_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF bind joint poses",
            &replay.bind_joint_poses,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let bind_joint_source_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF bind joint sources",
            &replay.bind_joint_source_rows,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                bind_joint_pose_buffer.destroy(device);
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let runtime_joint_pose_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            replay
                .runtime_joint_pose_frame_buffer_bytes()
                .max(RUNTIME_JOINT_POSE_STRIDE_BYTES),
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF runtime joint poses",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                bind_joint_source_buffer.destroy(device);
                bind_joint_pose_buffer.destroy(device);
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let tip_length_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            replay
                .tip_length_frame_buffer_bytes()
                .max(TIP_LENGTH_ROW_STRIDE_BYTES),
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF tip lengths",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                runtime_joint_pose_buffer.destroy(device);
                bind_joint_source_buffer.destroy(device);
                bind_joint_pose_buffer.destroy(device);
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let skinned_position_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            SKINNED_POSITION_STRIDE_BYTES * replay.skinning_vertices.len() as vk::DeviceSize,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF skinned positions",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                tip_length_buffer.destroy(device);
                runtime_joint_pose_buffer.destroy(device);
                bind_joint_source_buffer.destroy(device);
                bind_joint_pose_buffer.destroy(device);
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let field_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            FIELD_CELL_STRIDE_BYTES * (SDF_GRID_WIDTH * SDF_GRID_HEIGHT) as vk::DeviceSize,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF field",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                skinned_position_buffer.destroy(device);
                tip_length_buffer.destroy(device);
                runtime_joint_pose_buffer.destroy(device);
                bind_joint_source_buffer.destroy(device);
                bind_joint_pose_buffer.destroy(device);
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let triangle_bounds_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            TRIANGLE_BOUNDS_STRIDE_BYTES * replay.skinning_triangles.len() as vk::DeviceSize,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "recorded skinned SDF triangle bounds",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                field_buffer.destroy(device);
                skinned_position_buffer.destroy(device);
                tip_length_buffer.destroy(device);
                runtime_joint_pose_buffer.destroy(device);
                bind_joint_source_buffer.destroy(device);
                bind_joint_pose_buffer.destroy(device);
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let tile_header_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            TILE_HEADER_STRIDE_BYTES
                * (SDF_TILE_GRID_WIDTH * SDF_TILE_GRID_HEIGHT) as vk::DeviceSize,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            "recorded skinned SDF tile headers",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                triangle_bounds_buffer.destroy(device);
                field_buffer.destroy(device);
                skinned_position_buffer.destroy(device);
                tip_length_buffer.destroy(device);
                runtime_joint_pose_buffer.destroy(device);
                bind_joint_source_buffer.destroy(device);
                bind_joint_pose_buffer.destroy(device);
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };
        let tile_index_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            TILE_INDEX_STRIDE_BYTES
                * (SDF_TILE_GRID_WIDTH * SDF_TILE_GRID_HEIGHT * SDF_MAX_TRIANGLES_PER_TILE)
                    as vk::DeviceSize,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            "recorded skinned SDF tile indices",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                tile_header_buffer.destroy(device);
                triangle_bounds_buffer.destroy(device);
                field_buffer.destroy(device);
                skinned_position_buffer.destroy(device);
                tip_length_buffer.destroy(device);
                runtime_joint_pose_buffer.destroy(device);
                bind_joint_source_buffer.destroy(device);
                bind_joint_pose_buffer.destroy(device);
                triangle_buffer.destroy(device);
                source_vertex_buffer.destroy(device);
                return Err(error);
            }
        };

        let compute_bindings = [
            storage_binding(0, vk::ShaderStageFlags::COMPUTE),
            storage_binding(1, vk::ShaderStageFlags::COMPUTE),
            storage_binding(2, vk::ShaderStageFlags::COMPUTE),
            storage_binding(3, vk::ShaderStageFlags::COMPUTE),
            storage_binding(4, vk::ShaderStageFlags::COMPUTE),
            storage_binding(5, vk::ShaderStageFlags::COMPUTE),
            storage_binding(6, vk::ShaderStageFlags::COMPUTE),
            storage_binding(7, vk::ShaderStageFlags::COMPUTE),
            storage_binding(8, vk::ShaderStageFlags::COMPUTE),
            storage_binding(9, vk::ShaderStageFlags::COMPUTE),
            storage_binding(10, vk::ShaderStageFlags::COMPUTE),
        ];
        let compute_descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&compute_bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                destroy_buffers(
                    device,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(format!(
                    "create GPU skinned SDF compute descriptor layout: {error}"
                ));
            }
        };
        let overlay_bindings = [storage_binding(0, vk::ShaderStageFlags::FRAGMENT)];
        let overlay_descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&overlay_bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_descriptor_set_layout(compute_descriptor_set_layout, None);
                destroy_buffers(
                    device,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(format!("create GPU SDF overlay descriptor layout: {error}"));
            }
        };

        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(12)];
        let descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(2),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                device.destroy_descriptor_set_layout(overlay_descriptor_set_layout, None);
                device.destroy_descriptor_set_layout(compute_descriptor_set_layout, None);
                destroy_buffers(
                    device,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(format!("create GPU SDF descriptor pool: {error}"));
            }
        };
        let set_layouts = [compute_descriptor_set_layout, overlay_descriptor_set_layout];
        let descriptor_sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&set_layouts),
        ) {
            Ok(sets) => sets,
            Err(error) => {
                destroy_descriptor_resources(
                    device,
                    descriptor_pool,
                    compute_descriptor_set_layout,
                    overlay_descriptor_set_layout,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(format!("allocate GPU SDF descriptor sets: {error}"));
            }
        };
        let compute_descriptor_set = descriptor_sets[0];
        let overlay_descriptor_set = descriptor_sets[1];
        update_descriptors(
            device,
            compute_descriptor_set,
            overlay_descriptor_set,
            &source_vertex_buffer,
            &triangle_buffer,
            &bind_joint_pose_buffer,
            &bind_joint_source_buffer,
            &runtime_joint_pose_buffer,
            &tip_length_buffer,
            &skinned_position_buffer,
            &field_buffer,
            &triangle_bounds_buffer,
            &tile_header_buffer,
            &tile_index_buffer,
        );

        let compute_push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE)
            .offset(0)
            .size(mem::size_of::<SdfComputePush>() as u32)];
        let compute_set_layouts = [compute_descriptor_set_layout];
        let compute_pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&compute_set_layouts)
                .push_constant_ranges(&compute_push_ranges),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                destroy_descriptor_resources(
                    device,
                    descriptor_pool,
                    compute_descriptor_set_layout,
                    overlay_descriptor_set_layout,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(format!("create GPU SDF compute pipeline layout: {error}"));
            }
        };
        let overlay_push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(mem::size_of::<SdfOverlayPush>() as u32)];
        let overlay_set_layouts = [overlay_descriptor_set_layout];
        let overlay_pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&overlay_set_layouts)
                .push_constant_ranges(&overlay_push_ranges),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_pipeline_layout(compute_pipeline_layout, None);
                destroy_descriptor_resources(
                    device,
                    descriptor_pool,
                    compute_descriptor_set_layout,
                    overlay_descriptor_set_layout,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(format!("create GPU SDF overlay pipeline layout: {error}"));
            }
        };
        let skinning_pipeline = match create_compute_pipeline(
            device,
            compute_pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/gpu_hand_skinning.comp.spv")),
            "GPU hand skinning",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(overlay_pipeline_layout, None);
                device.destroy_pipeline_layout(compute_pipeline_layout, None);
                destroy_descriptor_resources(
                    device,
                    descriptor_pool,
                    compute_descriptor_set_layout,
                    overlay_descriptor_set_layout,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(error);
            }
        };
        let tile_bin_pipeline = match create_compute_pipeline(
            device,
            compute_pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/gpu_sdf_tile_bins.comp.spv")),
            "GPU SDF tile binning",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(skinning_pipeline, None);
                device.destroy_pipeline_layout(overlay_pipeline_layout, None);
                device.destroy_pipeline_layout(compute_pipeline_layout, None);
                destroy_descriptor_resources(
                    device,
                    descriptor_pool,
                    compute_descriptor_set_layout,
                    overlay_descriptor_set_layout,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(error);
            }
        };
        let sdf_pipeline = match create_compute_pipeline(
            device,
            compute_pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/gpu_sdf_field.comp.spv")),
            "GPU skinned SDF",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(tile_bin_pipeline, None);
                device.destroy_pipeline(skinning_pipeline, None);
                device.destroy_pipeline_layout(overlay_pipeline_layout, None);
                device.destroy_pipeline_layout(compute_pipeline_layout, None);
                destroy_descriptor_resources(
                    device,
                    descriptor_pool,
                    compute_descriptor_set_layout,
                    overlay_descriptor_set_layout,
                    &source_vertex_buffer,
                    &triangle_buffer,
                    &bind_joint_pose_buffer,
                    &bind_joint_source_buffer,
                    &runtime_joint_pose_buffer,
                    &tip_length_buffer,
                    &skinned_position_buffer,
                    &field_buffer,
                    &triangle_bounds_buffer,
                    &tile_header_buffer,
                    &tile_index_buffer,
                );
                return Err(error);
            }
        };
        let overlay_pipeline =
            match create_overlay_pipeline(device, render_pass, overlay_pipeline_layout) {
                Ok(pipeline) => pipeline,
                Err(error) => {
                    device.destroy_pipeline(sdf_pipeline, None);
                    device.destroy_pipeline(tile_bin_pipeline, None);
                    device.destroy_pipeline(skinning_pipeline, None);
                    device.destroy_pipeline_layout(overlay_pipeline_layout, None);
                    device.destroy_pipeline_layout(compute_pipeline_layout, None);
                    destroy_descriptor_resources(
                        device,
                        descriptor_pool,
                        compute_descriptor_set_layout,
                        overlay_descriptor_set_layout,
                        &source_vertex_buffer,
                        &triangle_buffer,
                        &bind_joint_pose_buffer,
                        &bind_joint_source_buffer,
                        &runtime_joint_pose_buffer,
                        &tip_length_buffer,
                        &skinned_position_buffer,
                        &field_buffer,
                        &triangle_bounds_buffer,
                        &tile_header_buffer,
                        &tile_index_buffer,
                    );
                    return Err(error);
                }
            };

        Ok(Self {
            descriptor_pool,
            compute_descriptor_set_layout,
            overlay_descriptor_set_layout,
            compute_descriptor_set,
            overlay_descriptor_set,
            compute_pipeline_layout,
            overlay_pipeline_layout,
            skinning_pipeline,
            tile_bin_pipeline,
            sdf_pipeline,
            overlay_pipeline,
            source_vertex_buffer,
            triangle_buffer,
            bind_joint_pose_buffer,
            bind_joint_source_buffer,
            runtime_joint_pose_buffer,
            tip_length_buffer,
            skinned_position_buffer,
            field_buffer,
            triangle_bounds_buffer,
            tile_header_buffer,
            tile_index_buffer,
            vertex_count: replay.skinning_vertices.len() as u32,
            triangle_count: replay.skinning_triangles.len() as u32,
        })
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.overlay_pipeline, None);
        device.destroy_pipeline(self.sdf_pipeline, None);
        device.destroy_pipeline(self.tile_bin_pipeline, None);
        device.destroy_pipeline(self.skinning_pipeline, None);
        device.destroy_pipeline_layout(self.overlay_pipeline_layout, None);
        device.destroy_pipeline_layout(self.compute_pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.overlay_descriptor_set_layout, None);
        device.destroy_descriptor_set_layout(self.compute_descriptor_set_layout, None);
        self.tile_index_buffer.destroy(device);
        self.tile_header_buffer.destroy(device);
        self.triangle_bounds_buffer.destroy(device);
        self.field_buffer.destroy(device);
        self.skinned_position_buffer.destroy(device);
        self.tip_length_buffer.destroy(device);
        self.runtime_joint_pose_buffer.destroy(device);
        self.bind_joint_source_buffer.destroy(device);
        self.bind_joint_pose_buffer.destroy(device);
        self.triangle_buffer.destroy(device);
        self.source_vertex_buffer.destroy(device);
    }
}

struct OwnedBuffer {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    bytes: vk::DeviceSize,
}

impl OwnedBuffer {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        bytes: vk::DeviceSize,
        usage: vk::BufferUsageFlags,
        label: &str,
    ) -> Result<Self, String> {
        if bytes == 0 {
            return Err(format!("{label} buffer requires nonzero size"));
        }
        let buffer = device
            .create_buffer(
                &vk::BufferCreateInfo::default()
                    .size(bytes)
                    .usage(usage)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(|error| format!("create {label} buffer: {error}"))?;
        let requirements = device.get_buffer_memory_requirements(buffer);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
        ) {
            Ok(index) => index,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                return Err(error);
            }
        };
        let memory = match device.allocate_memory(
            &vk::MemoryAllocateInfo::default()
                .allocation_size(requirements.size)
                .memory_type_index(memory_type_index),
            None,
        ) {
            Ok(memory) => memory,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                return Err(format!("allocate {label} memory: {error}"));
            }
        };
        if let Err(error) = device.bind_buffer_memory(buffer, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            return Err(format!("bind {label} memory: {error}"));
        }
        Ok(Self {
            buffer,
            memory,
            bytes,
        })
    }

    unsafe fn new_with_data<T: Copy>(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        usage: vk::BufferUsageFlags,
        label: &str,
        data: &[T],
    ) -> Result<Self, String> {
        let bytes = mem::size_of_val(data) as vk::DeviceSize;
        let buffer = Self::new(device, memory_properties, bytes, usage, label)?;
        let mapped =
            match device.map_memory(buffer.memory, 0, buffer.bytes, vk::MemoryMapFlags::empty()) {
                Ok(mapped) => mapped.cast::<T>(),
                Err(error) => {
                    buffer.destroy(device);
                    return Err(format!("map {label} buffer: {error}"));
                }
            };
        mapped.copy_from_nonoverlapping(data.as_ptr(), data.len());
        device.unmap_memory(buffer.memory);
        Ok(buffer)
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        if self.buffer != vk::Buffer::null() {
            device.destroy_buffer(self.buffer, None);
        }
        if self.memory != vk::DeviceMemory::null() {
            device.free_memory(self.memory, None);
        }
    }
}

fn storage_binding(
    binding: u32,
    stage_flags: vk::ShaderStageFlags,
) -> vk::DescriptorSetLayoutBinding<'static> {
    vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(stage_flags)
}

unsafe fn update_descriptors(
    device: &ash::Device,
    compute_descriptor_set: vk::DescriptorSet,
    overlay_descriptor_set: vk::DescriptorSet,
    source_vertex_buffer: &OwnedBuffer,
    triangle_buffer: &OwnedBuffer,
    bind_joint_pose_buffer: &OwnedBuffer,
    bind_joint_source_buffer: &OwnedBuffer,
    runtime_joint_pose_buffer: &OwnedBuffer,
    tip_length_buffer: &OwnedBuffer,
    skinned_position_buffer: &OwnedBuffer,
    field_buffer: &OwnedBuffer,
    triangle_bounds_buffer: &OwnedBuffer,
    tile_header_buffer: &OwnedBuffer,
    tile_index_buffer: &OwnedBuffer,
) {
    let source_vertex_info = [descriptor_info(source_vertex_buffer)];
    let triangle_info = [descriptor_info(triangle_buffer)];
    let bind_joint_pose_info = [descriptor_info(bind_joint_pose_buffer)];
    let bind_joint_source_info = [descriptor_info(bind_joint_source_buffer)];
    let runtime_joint_pose_info = [descriptor_info(runtime_joint_pose_buffer)];
    let tip_length_info = [descriptor_info(tip_length_buffer)];
    let skinned_position_info = [descriptor_info(skinned_position_buffer)];
    let field_info = [descriptor_info(field_buffer)];
    let triangle_bounds_info = [descriptor_info(triangle_bounds_buffer)];
    let tile_header_info = [descriptor_info(tile_header_buffer)];
    let tile_index_info = [descriptor_info(tile_index_buffer)];
    let writes = [
        write_descriptor(compute_descriptor_set, 0, &source_vertex_info),
        write_descriptor(compute_descriptor_set, 1, &triangle_info),
        write_descriptor(compute_descriptor_set, 2, &runtime_joint_pose_info),
        write_descriptor(compute_descriptor_set, 3, &tip_length_info),
        write_descriptor(compute_descriptor_set, 4, &bind_joint_pose_info),
        write_descriptor(compute_descriptor_set, 5, &bind_joint_source_info),
        write_descriptor(compute_descriptor_set, 6, &skinned_position_info),
        write_descriptor(compute_descriptor_set, 7, &field_info),
        write_descriptor(compute_descriptor_set, 8, &tile_header_info),
        write_descriptor(compute_descriptor_set, 9, &tile_index_info),
        write_descriptor(compute_descriptor_set, 10, &triangle_bounds_info),
        write_descriptor(overlay_descriptor_set, 0, &field_info),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

fn descriptor_info(buffer: &OwnedBuffer) -> vk::DescriptorBufferInfo {
    vk::DescriptorBufferInfo::default()
        .buffer(buffer.buffer)
        .offset(0)
        .range(buffer.bytes)
}

fn write_descriptor<'a>(
    descriptor_set: vk::DescriptorSet,
    binding: u32,
    buffer_info: &'a [vk::DescriptorBufferInfo],
) -> vk::WriteDescriptorSet<'a> {
    vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(binding)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(buffer_info)
}

fn host_to_compute_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::HOST_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn field_to_fragment_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn transfer_to_compute_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn compute_write_to_compute_read_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn compact_frame_upload_bytes(frame: &RecordedHandSkinningFrame) -> u64 {
    mem::size_of_val(frame.runtime_joint_poses.as_slice()) as u64
        + mem::size_of_val(frame.tip_length_rows.as_slice()) as u64
}

unsafe fn create_compute_pipeline(
    device: &ash::Device,
    pipeline_layout: vk::PipelineLayout,
    spirv: &[u8],
    label: &str,
) -> Result<vk::Pipeline, String> {
    let compute_words = spirv_words(spirv)?;
    let compute_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&compute_words),
            None,
        )
        .map_err(|error| format!("create {label} shader module: {error}"))?;
    let entry = CString::new("main").expect("static shader entry point is valid");
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(compute_module)
        .name(&entry);
    let create_info = [vk::ComputePipelineCreateInfo::default()
        .stage(stage)
        .layout(pipeline_layout)];
    let result = device.create_compute_pipelines(vk::PipelineCache::null(), &create_info, None);
    device.destroy_shader_module(compute_module, None);
    result
        .map(|mut pipelines| pipelines.remove(0))
        .map_err(|(_, error)| format!("create {label} compute pipeline: {error}"))
}

unsafe fn create_overlay_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/camera_projection.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/gpu_sdf_overlay.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create GPU SDF overlay vertex shader module: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create GPU SDF overlay fragment shader module: {error}"
            ));
        }
    };
    let entry = CString::new("main").expect("static shader entry point is valid");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vertex_module)
            .name(&entry),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(fragment_module)
            .name(&entry),
    ];
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
    let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let color_blend_attachment = [vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::ONE)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(vk::ColorComponentFlags::RGBA)];
    let color_blend =
        vk::PipelineColorBlendStateCreateInfo::default().attachments(&color_blend_attachment);
    let depth_stencil = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(false)
        .depth_write_enable(false)
        .depth_compare_op(vk::CompareOp::ALWAYS)
        .stencil_test_enable(false);
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let create_info = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&color_blend)
        .depth_stencil_state(&depth_stencil)
        .dynamic_state(&dynamic)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0)];
    let result = device.create_graphics_pipelines(vk::PipelineCache::null(), &create_info, None);
    device.destroy_shader_module(fragment_module, None);
    device.destroy_shader_module(vertex_module, None);
    result
        .map(|mut pipelines| pipelines.remove(0))
        .map_err(|(_, error)| format!("create GPU SDF overlay graphics pipeline: {error}"))
}

unsafe fn destroy_descriptor_resources(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    compute_descriptor_set_layout: vk::DescriptorSetLayout,
    overlay_descriptor_set_layout: vk::DescriptorSetLayout,
    source_vertex_buffer: &OwnedBuffer,
    triangle_buffer: &OwnedBuffer,
    bind_joint_pose_buffer: &OwnedBuffer,
    bind_joint_source_buffer: &OwnedBuffer,
    runtime_joint_pose_buffer: &OwnedBuffer,
    tip_length_buffer: &OwnedBuffer,
    skinned_position_buffer: &OwnedBuffer,
    field_buffer: &OwnedBuffer,
    triangle_bounds_buffer: &OwnedBuffer,
    tile_header_buffer: &OwnedBuffer,
    tile_index_buffer: &OwnedBuffer,
) {
    device.destroy_descriptor_pool(descriptor_pool, None);
    device.destroy_descriptor_set_layout(overlay_descriptor_set_layout, None);
    device.destroy_descriptor_set_layout(compute_descriptor_set_layout, None);
    destroy_buffers(
        device,
        source_vertex_buffer,
        triangle_buffer,
        bind_joint_pose_buffer,
        bind_joint_source_buffer,
        runtime_joint_pose_buffer,
        tip_length_buffer,
        skinned_position_buffer,
        field_buffer,
        triangle_bounds_buffer,
        tile_header_buffer,
        tile_index_buffer,
    );
}

unsafe fn destroy_buffers(
    device: &ash::Device,
    source_vertex_buffer: &OwnedBuffer,
    triangle_buffer: &OwnedBuffer,
    bind_joint_pose_buffer: &OwnedBuffer,
    bind_joint_source_buffer: &OwnedBuffer,
    runtime_joint_pose_buffer: &OwnedBuffer,
    tip_length_buffer: &OwnedBuffer,
    skinned_position_buffer: &OwnedBuffer,
    field_buffer: &OwnedBuffer,
    triangle_bounds_buffer: &OwnedBuffer,
    tile_header_buffer: &OwnedBuffer,
    tile_index_buffer: &OwnedBuffer,
) {
    tile_index_buffer.destroy(device);
    tile_header_buffer.destroy(device);
    triangle_bounds_buffer.destroy(device);
    field_buffer.destroy(device);
    skinned_position_buffer.destroy(device);
    tip_length_buffer.destroy(device);
    runtime_joint_pose_buffer.destroy(device);
    bind_joint_source_buffer.destroy(device);
    bind_joint_pose_buffer.destroy(device);
    triangle_buffer.destroy(device);
    source_vertex_buffer.destroy(device);
}

fn find_memory_type(
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    memory_type_bits: u32,
    required: vk::MemoryPropertyFlags,
) -> Result<u32, String> {
    for index in 0..memory_properties.memory_type_count {
        let supported = (memory_type_bits & (1 << index)) != 0;
        let flags = memory_properties.memory_types[index as usize].property_flags;
        if supported && flags.contains(required) {
            return Ok(index);
        }
    }
    Err(format!(
        "no Vulkan memory type supports {required:?} for recorded hand skinned SDF buffers"
    ))
}

fn spirv_words(bytes: &[u8]) -> Result<Vec<u32>, String> {
    if bytes.len() % 4 != 0 {
        return Err("SPIR-V bytecode length is not word-aligned".to_string());
    }
    Ok(bytes
        .chunks_exact(4)
        .map(|chunk| u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
        .collect())
}

fn as_bytes<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) }
}

#[repr(C)]
struct SdfComputePush {
    dims: [u32; 4],
    target0: [f32; 4],
    target1: [f32; 4],
    params: [f32; 4],
}

#[repr(C)]
struct SdfOverlayPush {
    target_rect: [f32; 4],
    dims: [u32; 4],
    color: [f32; 4],
}
