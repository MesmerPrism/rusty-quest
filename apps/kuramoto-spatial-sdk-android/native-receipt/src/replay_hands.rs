use std::ffi::CStr;
use std::mem;
use std::slice;

use ash::vk;

use crate::live_hand_joints::{
    LiveHandJointInput, LiveHandJointRow, LiveHandOpenXrHandles, LIVE_HAND_JOINT_COUNT,
    LIVE_HAND_ROW_COUNT,
};
use crate::{android_log_info, bool_token, marker_token};

const RECORDED_HAND_REPLAY_JSON: &str = include_str!(concat!(
    env!("OUT_DIR"),
    "/recorded_hand_replay_source.json"
));

const REPLAY_HAND_IPD_METERS: f32 = 0.064;
const REPLAY_HAND_HEAD_Y_METERS: f32 = 1.68;
const REPLAY_HAND_HEAD_Z_METERS: f32 = 0.45;
const REPLAY_HAND_PANEL_TARGET_DISTANCE_METERS: f32 = 0.72;
const KURAMOTO_STUDY_PARTICLES_PER_HAND: u32 = 1024;
const KURAMOTO_STUDY_PARTICLE_RADIUS_METERS: f32 = 0.0065;
const PARTICLE_VERTICES_PER_INSTANCE: u32 = 6;
const KURAMOTO_STUDY_CONDITION_ID: &str = "lche";
const KURAMOTO_STUDY_PROFILE_ID: &str =
    "rusty.quest.kuramoto_spatial.condition.high-energy-low-coherence.movement-only.v1";
const KURAMOTO_STUDY_DYNAMICS_MODE: &str = "movement-only-high-energy-low-coherence";
const KURAMOTO_STUDY_MOVEMENT_BASE_HZ: f32 = 0.88;
const KURAMOTO_STUDY_MOVEMENT_COUPLING: f32 = 0.0;
const KURAMOTO_STUDY_FREQUENCY_SPREAD_HZ: f32 = 0.62;
const KURAMOTO_STUDY_NOISE_AMPLITUDE_METERS: f32 = 0.004;
const KURAMOTO_STUDY_NOISE_SPEED_HZ: f32 = 0.50;
const HAND_MESH_COMPONENT_POLICY: &str =
    "keep_two_largest_components_drop_wrist_bridge_boundaries_v1";
const HAND_MESH_KEPT_COMPONENT_RANK_COUNT: u32 = 2;

pub(crate) struct ReplayHandsRenderer {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    vertex_buffer: vk::Buffer,
    vertex_memory: vk::DeviceMemory,
    vertex_buffer_bytes: vk::DeviceSize,
    skinning_vertex_buffer: vk::Buffer,
    skinning_vertex_memory: vk::DeviceMemory,
    skinning_vertex_buffer_bytes: vk::DeviceSize,
    skinning_triangle_buffer: vk::Buffer,
    skinning_triangle_memory: vk::DeviceMemory,
    skinning_triangle_buffer_bytes: vk::DeviceSize,
    bind_joint_pose_buffer: vk::Buffer,
    bind_joint_pose_memory: vk::DeviceMemory,
    bind_joint_pose_buffer_bytes: vk::DeviceSize,
    bind_joint_source_buffer: vk::Buffer,
    bind_joint_source_memory: vk::DeviceMemory,
    bind_joint_source_buffer_bytes: vk::DeviceSize,
    live_joint_buffer: vk::Buffer,
    live_joint_memory: vk::DeviceMemory,
    live_joint_buffer_bytes: vk::DeviceSize,
    live_hands: LiveHandJointInput,
    draws: Vec<ReplayHandDraw>,
    stats: ReplayHandsStats,
}

#[derive(Clone, Debug)]
struct ReplayHandsStats {
    source_id: String,
    source_kind: String,
    right_hand_distinct: bool,
    left_frames: u32,
    right_frames: u32,
    left_vertices_per_frame: u32,
    right_vertices_per_frame: u32,
    left_triangles_per_frame: u32,
    right_triangles_per_frame: u32,
    left_particles_per_frame: u32,
    right_particles_per_frame: u32,
    total_resident_vertices: u32,
    vertex_buffer_bytes: u64,
    source_mode: &'static str,
    left_mesh_components: MeshComponentSummary,
    right_mesh_components: MeshComponentSummary,
}

impl ReplayHandsStats {
    fn marker_fields(&self) -> String {
        format!(
            "surfaceLayerMode=native-kuramoto-study-hand-anchor-particles forcedReplayHands=true forcedReplayMeshVisible=false diagnosticParticlesVisible=false nativeStudyParticlesVisible=true handAnchorParticlesVisible=true gpuReplayHandsResident=true properStereoStudyParticles=true replayStereoProjection=per-eye-spatial-sdk-panel-plane-ray-intersection handAnchorParticleCoordinateSource=live-openxr-world-joints-gpu-skinned-resident-mesh-with-forced-replay-fallback privateKuramotoPayloadActive=false studyProfileDynamicsActive=true liveHandGpuSkinningParticles=true rgbDriverColor=true jointClusterMode=false kuramotoConditionId={} kuramotoStudyProfileId={} kuramotoDynamicsMode={} kuramotoMovementBaseHz={:.2} kuramotoMovementCoupling={:.1}",
            KURAMOTO_STUDY_CONDITION_ID,
            KURAMOTO_STUDY_PROFILE_ID,
            KURAMOTO_STUDY_DYNAMICS_MODE,
            KURAMOTO_STUDY_MOVEMENT_BASE_HZ,
            KURAMOTO_STUDY_MOVEMENT_COUPLING,
        )
    }

    fn stats_marker_fields(&self) -> String {
        format!(
            "handAnchorParticlePath=resident-recorded-rig-gpu-skinned-mesh-coordinate-anchor-billboards handAnchorParticleCoordinateSpace=spatial-sdk-panel-plane-perspective-projection privateKuramotoPayloadNextStep=link-private-kuramoto-compute-payload studyProfileDynamicsSlice=lche-movement-normal-noise-rgb-driver liveMeshSkinningPolicy=native-compact-frame-gated-full-weight-skinning liveMeshSurfacePolicy={} liveMeshComponentRank0=hand-inside liveMeshComponentRank1=hand-back liveMeshComponentRank2=wrist-cap liveMeshWristCapPolicy=drop-component-rank-2 liveMeshNormalFallbackPolicy=skinned-bind-normal-for-small-triangle-area liveMeshTriangleRetryPolicy=bounded-alternate-triangle-sampling liveMeshTriangleValidationAttempts=6 gpuReplayHandSource={} gpuReplayHandSourceKind={} gpuReplayHandSourceMode={} gpuReplayRightHandDistinct={} gpuReplayHandSetReady=true compactJointSkinningKernel=true compactJointPoseUploadPerFrame=true jointMatrixUploadPerFrame=false replayTargetProjectionSpace=spatial-sdk-panel-plane-perspective-projection replayVirtualIpdMeters={:.3} replayVirtualHeadYMeters={:.2} replayVirtualHeadZMeters={:.2} panelProjectionTargetDistanceMeters={:.2} kuramotoFrequencySpreadHz={:.2} kuramotoNoiseAmplitudeMeters={:.3} kuramotoNoiseSpeedHz={:.2} leftReplayHandFrameCount={} rightReplayHandFrameCount={} leftReplayHandVerticesPerFrame={} rightReplayHandVerticesPerFrame={} leftReplayHandTrianglesPerFrame={} rightReplayHandTrianglesPerFrame={} leftHandAnchorParticlesPerFrame={} rightHandAnchorParticlesPerFrame={} replayHandResidentVertexCount={} replayHandVertexBufferBytes={} {} {}",
            marker_token(HAND_MESH_COMPONENT_POLICY),
            marker_token(&self.source_id),
            marker_token(&self.source_kind),
            self.source_mode,
            bool_token(self.right_hand_distinct),
            REPLAY_HAND_IPD_METERS,
            REPLAY_HAND_HEAD_Y_METERS,
            REPLAY_HAND_HEAD_Z_METERS,
            REPLAY_HAND_PANEL_TARGET_DISTANCE_METERS,
            KURAMOTO_STUDY_FREQUENCY_SPREAD_HZ,
            KURAMOTO_STUDY_NOISE_AMPLITUDE_METERS,
            KURAMOTO_STUDY_NOISE_SPEED_HZ,
            self.left_frames,
            self.right_frames,
            self.left_vertices_per_frame,
            self.right_vertices_per_frame,
            self.left_triangles_per_frame,
            self.right_triangles_per_frame,
            self.left_particles_per_frame,
            self.right_particles_per_frame,
            self.total_resident_vertices,
            self.vertex_buffer_bytes,
            self.left_mesh_components.marker_fields("left"),
            self.right_mesh_components.marker_fields("right"),
        )
    }

    fn component_marker_fields(&self) -> String {
        format!(
            "liveMeshSurfacePolicy={} liveMeshWristCapPolicy=drop-component-rank-2 liveMeshComponentRank0=hand-inside liveMeshComponentRank1=hand-back liveMeshComponentRank2=wrist-cap {} {}",
            marker_token(HAND_MESH_COMPONENT_POLICY),
            self.left_mesh_components.marker_fields("left"),
            self.right_mesh_components.marker_fields("right"),
        )
    }
}

#[derive(Clone, Debug, Default)]
struct MeshComponentSummary {
    component_count: usize,
    vertex_counts: Vec<usize>,
    triangle_counts: Vec<usize>,
    source_triangle_count: usize,
    sampling_triangle_count: usize,
    dropped_triangle_count: usize,
    component_filter_active: bool,
}

impl MeshComponentSummary {
    fn public_shape(triangle_count: usize) -> Self {
        Self {
            source_triangle_count: triangle_count,
            sampling_triangle_count: triangle_count,
            ..Default::default()
        }
    }

    fn marker_fields(&self, prefix: &str) -> String {
        format!(
            "{}HandMeshComponentFilterActive={} {}HandMeshComponentPolicy={} {}HandMeshSourceComponentCount={} {}HandMeshComponentVertexCounts={} {}HandMeshComponentTriangleCounts={} {}HandMeshKeptComponentRanks=0;1 {}HandMeshDroppedComponentRanks=2 {}HandMeshSourceTriangleCount={} {}HandMeshSamplingTriangleCount={} {}HandMeshDroppedTriangleCount={}",
            prefix,
            bool_token(self.component_filter_active),
            prefix,
            marker_token(HAND_MESH_COMPONENT_POLICY),
            prefix,
            self.component_count,
            prefix,
            join_usize(&self.vertex_counts),
            prefix,
            join_usize(&self.triangle_counts),
            prefix,
            prefix,
            prefix,
            self.source_triangle_count,
            prefix,
            self.sampling_triangle_count,
            prefix,
            self.dropped_triangle_count,
        )
    }
}

#[derive(Clone, Debug)]
struct ReplayHandDraw {
    base_vertex: u32,
    vertices_per_frame: u32,
    triangles_per_frame: u32,
    particles_per_frame: u32,
    frame_count: u32,
    skinning_vertex_base: u32,
    skinning_triangle_base: u32,
    skinning_triangle_count: u32,
    skinning_ready: bool,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct ReplayHandVertex {
    position: [f32; 4],
    normal_hand: [f32; 4],
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct ReplayHandSkinningVertex {
    bind_position: [f32; 4],
    bind_normal: [f32; 4],
    joint_indices: [u32; 4],
    joint_weights: [f32; 4],
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct ReplayHandGpuPose {
    translation_pad: [f32; 4],
    rotation_xyzw: [f32; 4],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct ReplayHandPanelProjection {
    pub(crate) center: [f32; 3],
    pub(crate) right: [f32; 3],
    pub(crate) up: [f32; 3],
    pub(crate) width_meters: f32,
    pub(crate) height_meters: f32,
    pub(crate) target_distance_meters: f32,
    pub(crate) valid: bool,
}

impl Default for ReplayHandPanelProjection {
    fn default() -> Self {
        Self {
            center: [0.0, 1.22, -0.72],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            width_meters: 1.44,
            height_meters: 1.44,
            target_distance_meters: REPLAY_HAND_PANEL_TARGET_DISTANCE_METERS,
            valid: false,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct ReplayHandPush {
    eye_index: u32,
    hand_index: u32,
    frame_index: u32,
    pad0: u32,
    draw: [u32; 4],
    projection: [f32; 4],
    color: [f32; 4],
    dynamics: [f32; 4],
    profile: [f32; 4],
    panel_up_height: [f32; 4],
    live_adjust: [f32; 4],
}

impl ReplayHandsRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        openxr_handles: LiveHandOpenXrHandles,
    ) -> Result<Self, String> {
        let source = ReplayHandSource::load()?;
        let gpu_data = ReplayHandGpuData::from_source(&source)?;
        if gpu_data.vertices.is_empty() {
            return Err("forced replay hand source produced no GPU vertices".to_string());
        }
        let vertex_buffer_bytes =
            (gpu_data.vertices.len() * mem::size_of::<ReplayHandVertex>()) as vk::DeviceSize;
        let (vertex_buffer, vertex_memory) = create_host_visible_buffer(
            device,
            memory_properties,
            vertex_buffer_bytes,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "forced replay hand vertex storage",
        )?;
        let mapped = device
            .map_memory(
                vertex_memory,
                0,
                vertex_buffer_bytes,
                vk::MemoryMapFlags::empty(),
            )
            .map_err(|error| format!("map forced replay hand vertex storage: {error:?}"))?
            .cast::<ReplayHandVertex>();
        mapped.copy_from_nonoverlapping(gpu_data.vertices.as_ptr(), gpu_data.vertices.len());
        device.unmap_memory(vertex_memory);

        let default_skinning_vertices = [ReplayHandSkinningVertex::default()];
        let skinning_vertex_data = if gpu_data.skinning_vertices.is_empty() {
            &default_skinning_vertices[..]
        } else {
            &gpu_data.skinning_vertices[..]
        };
        let (skinning_vertex_buffer, skinning_vertex_memory, skinning_vertex_buffer_bytes) =
            create_host_visible_buffer_with_data(
                device,
                memory_properties,
                skinning_vertex_data,
                vk::BufferUsageFlags::STORAGE_BUFFER,
                "live hand skinning vertex storage",
            )?;

        let default_skinning_triangles = [[0_u32; 4]];
        let skinning_triangle_data = if gpu_data.skinning_triangles.is_empty() {
            &default_skinning_triangles[..]
        } else {
            &gpu_data.skinning_triangles[..]
        };
        let (skinning_triangle_buffer, skinning_triangle_memory, skinning_triangle_buffer_bytes) =
            create_host_visible_buffer_with_data(
                device,
                memory_properties,
                skinning_triangle_data,
                vk::BufferUsageFlags::STORAGE_BUFFER,
                "live hand skinning triangle storage",
            )?;

        let default_bind_joint_poses = [ReplayHandGpuPose::default()];
        let bind_joint_pose_data = if gpu_data.bind_joint_poses.is_empty() {
            &default_bind_joint_poses[..]
        } else {
            &gpu_data.bind_joint_poses[..]
        };
        let (bind_joint_pose_buffer, bind_joint_pose_memory, bind_joint_pose_buffer_bytes) =
            create_host_visible_buffer_with_data(
                device,
                memory_properties,
                bind_joint_pose_data,
                vk::BufferUsageFlags::STORAGE_BUFFER,
                "live hand bind joint pose storage",
            )?;

        let default_bind_joint_sources = [[0_u32; 4]];
        let bind_joint_source_data = if gpu_data.bind_joint_source_rows.is_empty() {
            &default_bind_joint_sources[..]
        } else {
            &gpu_data.bind_joint_source_rows[..]
        };
        let (bind_joint_source_buffer, bind_joint_source_memory, bind_joint_source_buffer_bytes) =
            create_host_visible_buffer_with_data(
                device,
                memory_properties,
                bind_joint_source_data,
                vk::BufferUsageFlags::STORAGE_BUFFER,
                "live hand bind joint source storage",
            )?;

        let live_joint_buffer_bytes =
            (LIVE_HAND_ROW_COUNT * mem::size_of::<LiveHandJointRow>()) as vk::DeviceSize;
        let (live_joint_buffer, live_joint_memory) = create_host_visible_buffer(
            device,
            memory_properties,
            live_joint_buffer_bytes,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "live hand joint storage",
        )?;
        let default_live_rows = [LiveHandJointRow::default(); LIVE_HAND_ROW_COUNT];
        let live_mapped = device
            .map_memory(
                live_joint_memory,
                0,
                live_joint_buffer_bytes,
                vk::MemoryMapFlags::empty(),
            )
            .map_err(|error| format!("map live hand joint storage: {error:?}"))?
            .cast::<LiveHandJointRow>();
        live_mapped.copy_from_nonoverlapping(default_live_rows.as_ptr(), default_live_rows.len());
        device.unmap_memory(live_joint_memory);

        let descriptor_set_layout = create_descriptor_set_layout(device)?;
        let descriptor_pool = create_descriptor_pool(device)?;
        let descriptor_set = create_descriptor_set(
            device,
            descriptor_pool,
            descriptor_set_layout,
            vertex_buffer,
            vertex_buffer_bytes,
            live_joint_buffer,
            live_joint_buffer_bytes,
            skinning_vertex_buffer,
            skinning_vertex_buffer_bytes,
            skinning_triangle_buffer,
            skinning_triangle_buffer_bytes,
            bind_joint_pose_buffer,
            bind_joint_pose_buffer_bytes,
            bind_joint_source_buffer,
            bind_joint_source_buffer_bytes,
        )?;
        let pipeline_layout = create_pipeline_layout(device, descriptor_set_layout)?;
        let pipeline = create_pipeline(device, render_pass, pipeline_layout)?;
        let live_hands = LiveHandJointInput::new(openxr_handles);

        let stats = gpu_data.stats;
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=native-kuramoto-study-particles-ready renderPolicy=native-vulkan-wsi-surface-panel {}",
                stats.marker_fields(),
            ),
        );
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=native-kuramoto-study-particles-stats renderPolicy=native-vulkan-wsi-surface-panel {}",
                stats.stats_marker_fields(),
            ),
        );
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=native-kuramoto-study-hand-mesh-components renderPolicy=native-vulkan-wsi-surface-panel {}",
                stats.component_marker_fields(),
            ),
        );
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=native-kuramoto-study-left-hand-mesh-components renderPolicy=native-vulkan-wsi-surface-panel liveMeshSurfacePolicy={} liveMeshWristCapPolicy=drop-component-rank-2 {}",
                marker_token(HAND_MESH_COMPONENT_POLICY),
                stats.left_mesh_components.marker_fields("left"),
            ),
        );
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=native-kuramoto-study-right-hand-mesh-components renderPolicy=native-vulkan-wsi-surface-panel liveMeshSurfacePolicy={} liveMeshWristCapPolicy=drop-component-rank-2 {}",
                marker_token(HAND_MESH_COMPONENT_POLICY),
                stats.right_mesh_components.marker_fields("right"),
            ),
        );
        android_log_info(
            "RQKuramotoSpatialNative",
            &format!(
                "RUSTY_QUEST_KURAMOTO_SPATIAL_NATIVE channel=native-surface-particle-layer status=live-hand-joint-particle-source-ready renderPolicy=native-vulkan-wsi-surface-panel {} {} liveHandJointBufferRows={} liveHandJointBufferBytes={}",
                openxr_handles.marker_fields(),
                live_hands.status().marker_fields(),
                LIVE_HAND_ROW_COUNT,
                live_joint_buffer_bytes,
            ),
        );

        Ok(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_set,
            pipeline_layout,
            pipeline,
            vertex_buffer,
            vertex_memory,
            vertex_buffer_bytes,
            skinning_vertex_buffer,
            skinning_vertex_memory,
            skinning_vertex_buffer_bytes,
            skinning_triangle_buffer,
            skinning_triangle_memory,
            skinning_triangle_buffer_bytes,
            bind_joint_pose_buffer,
            bind_joint_pose_memory,
            bind_joint_pose_buffer_bytes,
            bind_joint_source_buffer,
            bind_joint_source_memory,
            bind_joint_source_buffer_bytes,
            live_joint_buffer,
            live_joint_memory,
            live_joint_buffer_bytes,
            live_hands,
            draws: gpu_data.draws,
            stats,
        })
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "{} {}",
            self.stats.marker_fields(),
            self.live_hands.status().marker_fields()
        )
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        self.live_hands.destroy();
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        device.destroy_buffer(self.live_joint_buffer, None);
        device.free_memory(self.live_joint_memory, None);
        device.destroy_buffer(self.bind_joint_source_buffer, None);
        device.free_memory(self.bind_joint_source_memory, None);
        device.destroy_buffer(self.bind_joint_pose_buffer, None);
        device.free_memory(self.bind_joint_pose_memory, None);
        device.destroy_buffer(self.skinning_triangle_buffer, None);
        device.free_memory(self.skinning_triangle_memory, None);
        device.destroy_buffer(self.skinning_vertex_buffer, None);
        device.free_memory(self.skinning_vertex_memory, None);
        device.destroy_buffer(self.vertex_buffer, None);
        device.free_memory(self.vertex_memory, None);
        self.live_joint_buffer = vk::Buffer::null();
        self.live_joint_memory = vk::DeviceMemory::null();
        self.live_joint_buffer_bytes = 0;
        self.bind_joint_source_buffer = vk::Buffer::null();
        self.bind_joint_source_memory = vk::DeviceMemory::null();
        self.bind_joint_source_buffer_bytes = 0;
        self.bind_joint_pose_buffer = vk::Buffer::null();
        self.bind_joint_pose_memory = vk::DeviceMemory::null();
        self.bind_joint_pose_buffer_bytes = 0;
        self.skinning_triangle_buffer = vk::Buffer::null();
        self.skinning_triangle_memory = vk::DeviceMemory::null();
        self.skinning_triangle_buffer_bytes = 0;
        self.skinning_vertex_buffer = vk::Buffer::null();
        self.skinning_vertex_memory = vk::DeviceMemory::null();
        self.skinning_vertex_buffer_bytes = 0;
        self.vertex_buffer = vk::Buffer::null();
        self.vertex_memory = vk::DeviceMemory::null();
        self.vertex_buffer_bytes = 0;
    }

    pub(crate) unsafe fn update_live_joints_buffer(
        &mut self,
        device: &ash::Device,
    ) -> Result<(), String> {
        let rows = self.live_hands.update_rows();
        let mapped = device
            .map_memory(
                self.live_joint_memory,
                0,
                self.live_joint_buffer_bytes,
                vk::MemoryMapFlags::empty(),
            )
            .map_err(|error| format!("map live hand joint upload: {error:?}"))?
            .cast::<LiveHandJointRow>();
        mapped.copy_from_nonoverlapping(rows.as_ptr(), rows.len());
        device.unmap_memory(self.live_joint_memory);
        Ok(())
    }

    pub(crate) unsafe fn record_eye(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        frame_counter: u32,
        eye_index: u32,
        time_seconds: f32,
        panel_projection: ReplayHandPanelProjection,
        driver0_value01: f32,
        driver1_value01: f32,
        point_scale: f32,
        live_hand_depth_offset_meters: f32,
        diagnostic_mode: u32,
    ) {
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipeline,
        );
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipeline_layout,
            0,
            slice::from_ref(&self.descriptor_set),
            &[],
        );
        for (hand_index, draw) in self.draws.iter().enumerate() {
            if draw.frame_count == 0
                || draw.vertices_per_frame == 0
                || draw.triangles_per_frame == 0
            {
                continue;
            }
            let frame_index = frame_counter % draw.frame_count;
            let first_vertex = draw
                .base_vertex
                .saturating_add(frame_index.saturating_mul(draw.vertices_per_frame));
            let live_hand_active = if hand_index == 0 {
                self.live_hands.status().left_active
            } else {
                self.live_hands.status().right_active
            };
            let live_mesh_ready =
                self.live_hands.status().frame_ready && live_hand_active && draw.skinning_ready;
            let push = ReplayHandPush {
                eye_index,
                hand_index: hand_index as u32,
                frame_index,
                pad0: (panel_projection.target_distance_meters.clamp(0.20, 1.50) * 1_000_000.0)
                    .round() as u32,
                draw: [
                    if live_mesh_ready {
                        draw.skinning_vertex_base
                    } else {
                        first_vertex
                    },
                    if live_mesh_ready {
                        draw.skinning_triangle_base
                    } else {
                        draw.vertices_per_frame
                    },
                    if live_mesh_ready {
                        draw.skinning_triangle_count
                    } else {
                        draw.triangles_per_frame
                    },
                    draw.particles_per_frame,
                ],
                projection: [
                    panel_projection.center[0],
                    panel_projection.center[1],
                    panel_projection.center[2],
                    KURAMOTO_STUDY_PARTICLE_RADIUS_METERS,
                ],
                color: [
                    panel_projection.right[0],
                    panel_projection.right[1],
                    panel_projection.right[2],
                    panel_projection.width_meters,
                ],
                dynamics: [
                    time_seconds,
                    KURAMOTO_STUDY_MOVEMENT_BASE_HZ,
                    driver1_value01.clamp(0.0, 1.0),
                    if live_mesh_ready { 1.0 } else { 0.0 },
                ],
                profile: [
                    KURAMOTO_STUDY_FREQUENCY_SPREAD_HZ,
                    KURAMOTO_STUDY_NOISE_AMPLITUDE_METERS * driver0_value01.clamp(0.0, 1.0),
                    KURAMOTO_STUDY_NOISE_SPEED_HZ,
                    point_scale.clamp(0.35, 2.25),
                ],
                panel_up_height: [
                    panel_projection.up[0],
                    panel_projection.up[1],
                    panel_projection.up[2],
                    panel_projection.height_meters,
                ],
                live_adjust: [
                    live_hand_depth_offset_meters.clamp(-1.5, 1.5),
                    0.25,
                    diagnostic_mode.min(4) as f32,
                    0.0,
                ],
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipeline_layout,
                vk::ShaderStageFlags::VERTEX,
                0,
                as_bytes(&push),
            );
            device.cmd_draw(
                command_buffer,
                PARTICLE_VERTICES_PER_INSTANCE,
                draw.particles_per_frame,
                0,
                0,
            );
        }
    }
}

struct ReplayHandGpuData {
    vertices: Vec<ReplayHandVertex>,
    skinning_vertices: Vec<ReplayHandSkinningVertex>,
    skinning_triangles: Vec<[u32; 4]>,
    bind_joint_poses: Vec<ReplayHandGpuPose>,
    bind_joint_source_rows: Vec<[u32; 4]>,
    draws: Vec<ReplayHandDraw>,
    stats: ReplayHandsStats,
}

impl ReplayHandGpuData {
    fn from_source(source: &ReplayHandSource) -> Result<Self, String> {
        let mut vertices = Vec::<ReplayHandVertex>::new();
        let mut skinning_vertices = Vec::<ReplayHandSkinningVertex>::new();
        let mut skinning_triangles = Vec::<[u32; 4]>::new();
        let mut bind_joint_poses = Vec::<ReplayHandGpuPose>::new();
        let mut bind_joint_source_rows = Vec::<[u32; 4]>::new();
        let mut draws = Vec::<ReplayHandDraw>::new();
        let mut left_vertices_per_frame = 0_u32;
        let mut right_vertices_per_frame = 0_u32;
        let mut left_triangles_per_frame = 0_u32;
        let mut right_triangles_per_frame = 0_u32;
        let mut left_particles_per_frame = 0_u32;
        let mut right_particles_per_frame = 0_u32;

        for hand in [&source.left, &source.right] {
            let base_vertex =
                u32::try_from(vertices.len()).map_err(|_| "forced replay vertex count overflow")?;
            let expanded = expand_hand_frames(hand)?;
            let vertices_per_frame = expanded.vertices_per_frame;
            let triangles_per_frame = vertices_per_frame / 3;
            vertices.extend_from_slice(&expanded.vertices);
            let skinning_vertex_base = u32::try_from(skinning_vertices.len())
                .map_err(|_| "skinning vertex count overflow")?;
            let skinning_triangle_base = u32::try_from(skinning_triangles.len())
                .map_err(|_| "skinning triangle count overflow")?;
            let mut skinning_triangle_count = 0_u32;
            let mut skinning_ready = false;
            if let Some(skinning) = hand.skinning.as_ref() {
                let skinning_vertex_count = u32::try_from(skinning.vertices.len())
                    .map_err(|_| "hand skinning vertex count overflow")?;
                skinning_triangle_count = u32::try_from(skinning.triangles.len())
                    .map_err(|_| "hand skinning triangle count overflow")?;
                skinning_ready = skinning_vertex_count > 0
                    && skinning_triangle_count > 0
                    && skinning.bind_joint_poses.len() == LIVE_HAND_JOINT_COUNT
                    && skinning.bind_joint_source_rows.len() == LIVE_HAND_JOINT_COUNT;
                skinning_vertices.extend_from_slice(&skinning.vertices);
                skinning_triangles.extend_from_slice(&skinning.triangles);
                bind_joint_poses.extend_from_slice(&skinning.bind_joint_poses);
                bind_joint_source_rows.extend_from_slice(&skinning.bind_joint_source_rows);
            }
            draws.push(ReplayHandDraw {
                base_vertex,
                vertices_per_frame,
                triangles_per_frame,
                particles_per_frame: KURAMOTO_STUDY_PARTICLES_PER_HAND,
                frame_count: hand.frames.len() as u32,
                skinning_vertex_base,
                skinning_triangle_base,
                skinning_triangle_count,
                skinning_ready,
            });
            match hand.handedness.as_str() {
                "right" => {
                    right_vertices_per_frame = vertices_per_frame;
                    right_triangles_per_frame = triangles_per_frame;
                    right_particles_per_frame = KURAMOTO_STUDY_PARTICLES_PER_HAND;
                }
                _ => {
                    left_vertices_per_frame = vertices_per_frame;
                    left_triangles_per_frame = triangles_per_frame;
                    left_particles_per_frame = KURAMOTO_STUDY_PARTICLES_PER_HAND;
                }
            }
        }

        let total_resident_vertices =
            u32::try_from(vertices.len()).map_err(|_| "forced replay vertex count overflow")?;
        let stats = ReplayHandsStats {
            source_id: source.source_id.clone(),
            source_kind: source.source_kind.clone(),
            right_hand_distinct: source.right_hand_distinct,
            left_frames: source.left.frames.len() as u32,
            right_frames: source.right.frames.len() as u32,
            left_vertices_per_frame,
            right_vertices_per_frame,
            left_triangles_per_frame,
            right_triangles_per_frame,
            left_particles_per_frame,
            right_particles_per_frame,
            total_resident_vertices,
            vertex_buffer_bytes: vertices.len() as u64 * mem::size_of::<ReplayHandVertex>() as u64,
            source_mode: source.source_mode,
            left_mesh_components: source.left.mesh_components.clone(),
            right_mesh_components: source.right.mesh_components.clone(),
        };
        Ok(Self {
            vertices,
            skinning_vertices,
            skinning_triangles,
            bind_joint_poses,
            bind_joint_source_rows,
            draws,
            stats,
        })
    }
}

struct ExpandedHandFrames {
    vertices: Vec<ReplayHandVertex>,
    vertices_per_frame: u32,
}

fn expand_hand_frames(hand: &ReplayHand) -> Result<ExpandedHandFrames, String> {
    if hand.frames.is_empty() {
        return Err(format!("{} replay hand has no frames", hand.handedness));
    }
    let mut vertices = Vec::new();
    let mut vertices_per_frame = None;
    for frame in &hand.frames {
        let frame_start = vertices.len();
        for triangle in &hand.triangles {
            let a = *frame
                .vertices
                .get(triangle[0] as usize)
                .ok_or_else(|| "forced replay triangle index outside vertex frame".to_string())?;
            let b = *frame
                .vertices
                .get(triangle[1] as usize)
                .ok_or_else(|| "forced replay triangle index outside vertex frame".to_string())?;
            let c = *frame
                .vertices
                .get(triangle[2] as usize)
                .ok_or_else(|| "forced replay triangle index outside vertex frame".to_string())?;
            let normal = triangle_normal(a, b, c);
            vertices.push(replay_vertex(a, normal, &hand.handedness));
            vertices.push(replay_vertex(b, normal, &hand.handedness));
            vertices.push(replay_vertex(c, normal, &hand.handedness));
        }
        let frame_vertices = u32::try_from(vertices.len() - frame_start)
            .map_err(|_| "forced replay frame vertex count overflow")?;
        if let Some(expected) = vertices_per_frame {
            if expected != frame_vertices {
                return Err(
                    "forced replay frames do not have stable expanded vertex counts".to_string(),
                );
            }
        } else {
            vertices_per_frame = Some(frame_vertices);
        }
    }
    Ok(ExpandedHandFrames {
        vertices,
        vertices_per_frame: vertices_per_frame.unwrap_or(0),
    })
}

fn replay_vertex(position: [f32; 3], normal: [f32; 3], handedness: &str) -> ReplayHandVertex {
    ReplayHandVertex {
        position: [position[0], position[1], position[2], 1.0],
        normal_hand: [
            normal[0],
            normal[1],
            normal[2],
            if handedness == "right" { 1.0 } else { 0.0 },
        ],
    }
}

fn triangle_normal(a: [f32; 3], b: [f32; 3], c: [f32; 3]) -> [f32; 3] {
    let ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    let ac = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    let cross = [
        ab[1] * ac[2] - ab[2] * ac[1],
        ab[2] * ac[0] - ab[0] * ac[2],
        ab[0] * ac[1] - ab[1] * ac[0],
    ];
    let len = (cross[0] * cross[0] + cross[1] * cross[1] + cross[2] * cross[2])
        .sqrt()
        .max(0.000001);
    [cross[0] / len, cross[1] / len, cross[2] / len]
}

struct ReplayHandSource {
    source_id: String,
    source_kind: String,
    source_mode: &'static str,
    left: ReplayHand,
    right: ReplayHand,
    right_hand_distinct: bool,
}

struct ReplayHand {
    handedness: String,
    triangles: Vec<[u32; 3]>,
    frames: Vec<ReplayHandFrame>,
    skinning: Option<ReplayHandSkinningData>,
    mesh_components: MeshComponentSummary,
}

struct ReplayHandFrame {
    vertices: Vec<[f32; 3]>,
}

struct ReplayHandSkinningData {
    vertices: Vec<ReplayHandSkinningVertex>,
    triangles: Vec<[u32; 4]>,
    bind_joint_poses: Vec<ReplayHandGpuPose>,
    bind_joint_source_rows: Vec<[u32; 4]>,
}

impl ReplayHandSource {
    fn load() -> Result<Self, String> {
        let value: serde_json::Value = serde_json::from_str(RECORDED_HAND_REPLAY_JSON)
            .map_err(|error| format!("parse forced replay hand source JSON: {error}"))?;
        let schema = text_field(&value, "schema")
            .ok_or_else(|| "forced replay source missing schema".to_string())?;
        if !schema.ends_with("recorded_hand_replay_source.v1") {
            return Err(format!("unsupported forced replay hand schema {schema}"));
        }
        let source_id = text_field(&value, "source_id").unwrap_or_else(|| "unknown".to_string());
        let source_kind =
            text_field(&value, "source_kind").unwrap_or_else(|| "unknown".to_string());
        let hands = value
            .get("hands")
            .and_then(serde_json::Value::as_array)
            .filter(|hands| !hands.is_empty())
            .ok_or_else(|| "forced replay source has no hands".to_string())?;
        let left_value = hand_by_handedness(hands, "left").unwrap_or(&hands[0]);
        let left = parse_hand(left_value, "left")?;
        let right = hand_by_handedness(hands, "right")
            .map(|hand| parse_hand(hand, "right"))
            .transpose()?;
        let right_hand_distinct = right.is_some();
        let right = right.unwrap_or_else(|| mirror_hand(&left));
        let source_mode = if source_kind == "external-recorded-capture-build-env" {
            "forced-capture-build-env"
        } else {
            "public-shape-fallback"
        };
        Ok(Self {
            source_id,
            source_kind,
            source_mode,
            left,
            right,
            right_hand_distinct,
        })
    }
}

fn parse_hand(value: &serde_json::Value, fallback_handedness: &str) -> Result<ReplayHand, String> {
    if let Some(rig_json) = value.get("rig_json").and_then(serde_json::Value::as_str) {
        return parse_validation_mesh_hand(value, rig_json, fallback_handedness);
    }
    parse_public_shape_hand(value, fallback_handedness)
}

fn parse_validation_mesh_hand(
    value: &serde_json::Value,
    rig_json: &str,
    fallback_handedness: &str,
) -> Result<ReplayHand, String> {
    let rig: serde_json::Value =
        serde_json::from_str(rig_json).map_err(|error| format!("parse replay rig: {error}"))?;
    let handedness = text_field(&rig, "handedness")
        .or_else(|| text_field(value, "handedness"))
        .unwrap_or_else(|| fallback_handedness.to_string());
    let source_triangles = parse_triangles(
        rig.get("triangle_indices")
            .ok_or_else(|| "forced replay rig missing triangle_indices".to_string())?,
    )?;
    let bind_vertex_count = rig
        .get("bind_vertices")
        .and_then(serde_json::Value::as_array)
        .map(Vec::len)
        .ok_or_else(|| "recorded hand rig missing bind_vertices".to_string())?;
    let (ranked_triangles, mesh_components) =
        component_filtered_triangles(bind_vertex_count, &source_triangles)?;
    let triangles = ranked_triangles
        .iter()
        .map(|[a, b, c, _]| [*a, *b, *c])
        .collect::<Vec<_>>();
    let skinning = parse_skinning_data(&rig, &ranked_triangles).ok();
    let lines = value
        .get("validation_mesh_jsonl")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "forced replay hand missing validation_mesh_jsonl".to_string())?;
    let mut frames = Vec::new();
    for line in lines.iter().filter_map(serde_json::Value::as_str) {
        let row: serde_json::Value = serde_json::from_str(line)
            .map_err(|error| format!("parse replay validation mesh row: {error}"))?;
        let vertices = parse_vec3_rows(
            row.get("vertices")
                .ok_or_else(|| "validation mesh row missing vertices".to_string())?,
        )?;
        frames.push(ReplayHandFrame { vertices });
    }
    if frames.is_empty() {
        return Err("forced replay validation mesh produced no frames".to_string());
    }
    Ok(ReplayHand {
        handedness,
        triangles,
        frames,
        skinning,
        mesh_components,
    })
}

fn parse_public_shape_hand(
    value: &serde_json::Value,
    fallback_handedness: &str,
) -> Result<ReplayHand, String> {
    let handedness =
        text_field(value, "handedness").unwrap_or_else(|| fallback_handedness.to_string());
    let frames = value
        .get("visual_frames")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "public replay shape missing visual_frames".to_string())?
        .iter()
        .map(|frame| public_shape_frame(frame, &handedness))
        .collect::<Result<Vec<_>, _>>()?;
    let triangles = public_shape_triangles(frames.first().map_or(0, |frame| frame.vertices.len()));
    let mesh_components = MeshComponentSummary::public_shape(triangles.len());
    Ok(ReplayHand {
        handedness,
        triangles,
        frames,
        skinning: None,
        mesh_components,
    })
}

fn public_shape_frame(
    value: &serde_json::Value,
    handedness: &str,
) -> Result<ReplayHandFrame, String> {
    let points = value
        .get("normalized_points")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "public replay visual frame missing normalized_points".to_string())?;
    let hand_offset = if handedness == "right" { 0.18 } else { -0.18 };
    let mut vertices = Vec::new();
    for point in points {
        let values = point
            .as_array()
            .ok_or_else(|| "public replay point must be an array".to_string())?;
        if values.len() != 2 {
            return Err("public replay point must have two values".to_string());
        }
        let x = f32_value(&values[0], "normalized point x")?;
        let y = f32_value(&values[1], "normalized point y")?;
        let world = [
            hand_offset + (x - 0.5) * 0.48,
            REPLAY_HAND_HEAD_Y_METERS + (0.58 - y) * 0.58,
            -0.42,
        ];
        let size = 0.012;
        let base = vertices.len() as f32 * 0.000004;
        vertices.push([world[0] - size, world[1] - size, world[2] - base]);
        vertices.push([world[0] + size, world[1] - size, world[2] - base]);
        vertices.push([world[0] + size, world[1] + size, world[2] - base]);
        vertices.push([world[0] - size, world[1] + size, world[2] - base]);
    }
    Ok(ReplayHandFrame { vertices })
}

fn public_shape_triangles(vertex_count: usize) -> Vec<[u32; 3]> {
    let mut triangles = Vec::new();
    for base in (0..vertex_count).step_by(4) {
        if base + 3 >= vertex_count {
            break;
        }
        let base = base as u32;
        triangles.push([base, base + 1, base + 2]);
        triangles.push([base, base + 2, base + 3]);
    }
    triangles
}

fn parse_skinning_data(
    rig: &serde_json::Value,
    triangles: &[[u32; 4]],
) -> Result<ReplayHandSkinningData, String> {
    let bind_vertices = parse_bind_vertices(rig)?;
    let bind_normals = parse_bind_normals(rig)?;
    let blend_indices = parse_blend_indices(rig)?;
    let blend_weights = parse_blend_weights(rig)?;
    let vertices = build_skinning_vertices(
        &bind_vertices,
        &bind_normals,
        &blend_indices,
        &blend_weights,
    )?;
    let bind_joint_poses = parse_bind_joint_poses(rig)?;
    let runtime_joint_set = rig
        .get("runtime_joint_set")
        .ok_or_else(|| "recorded hand rig missing runtime_joint_set".to_string())?;
    let bind_joint_source_rows =
        parse_bind_joint_source_rows(runtime_joint_set, bind_joint_poses.len())?;
    Ok(ReplayHandSkinningData {
        vertices,
        triangles: triangles.to_vec(),
        bind_joint_poses,
        bind_joint_source_rows,
    })
}

fn parse_bind_vertices(rig: &serde_json::Value) -> Result<Vec<[f32; 4]>, String> {
    let vertices = rig
        .get("bind_vertices")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand rig missing bind_vertices".to_string())?;
    vertices
        .iter()
        .map(|value| {
            let [x, y, z] = parse_vec3(value).ok_or_else(|| "invalid bind vertex".to_string())?;
            Ok([x, y, z, 1.0])
        })
        .collect()
}

fn parse_bind_normals(rig: &serde_json::Value) -> Result<Vec<[f32; 4]>, String> {
    let normals = rig
        .get("bind_normals")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand rig missing bind_normals".to_string())?;
    normals
        .iter()
        .map(|value| {
            let [x, y, z] = parse_vec3(value).ok_or_else(|| "invalid bind normal".to_string())?;
            Ok([x, y, z, 0.0])
        })
        .collect()
}

fn parse_bind_joint_poses(rig: &serde_json::Value) -> Result<Vec<ReplayHandGpuPose>, String> {
    let joints = rig
        .get("joints")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand rig missing joints".to_string())?;
    joints
        .iter()
        .map(|joint| {
            let pose = joint
                .get("bind_pose")
                .ok_or_else(|| "recorded hand joint missing bind_pose".to_string())?;
            parse_gpu_pose(pose)
        })
        .collect()
}

fn parse_bind_joint_source_rows(
    runtime_joint_set: &serde_json::Value,
    bind_joint_count: usize,
) -> Result<Vec<[u32; 4]>, String> {
    let values = runtime_joint_set
        .get("bind_joint_sources")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand runtime_joint_set missing bind_joint_sources".to_string())?;
    let mut rows = vec![None; bind_joint_count];
    for value in values {
        let bind_joint_index = value
            .get("bind_joint_index")
            .and_then(serde_json::Value::as_u64)
            .ok_or_else(|| "bind_joint_source missing bind_joint_index".to_string())?
            as usize;
        let source_kind = text_field(value, "source_kind")
            .ok_or_else(|| "bind_joint_source missing source_kind".to_string())?;
        let row = match source_kind.as_str() {
            "runtime_pose" => [
                0,
                optional_usize(value, "runtime_joint_index")
                    .ok_or_else(|| "runtime_pose missing runtime_joint_index".to_string())?
                    as u32,
                0,
                0,
            ],
            "tip_length_from_parent_pose" => [
                1,
                0,
                optional_usize(value, "tip_length_index")
                    .ok_or_else(|| "tip source missing tip_length_index".to_string())?
                    as u32,
                optional_usize(value, "parent_runtime_joint_index")
                    .ok_or_else(|| "tip source missing parent_runtime_joint_index".to_string())?
                    as u32,
            ],
            _ => return Err(format!("unsupported bind joint source kind {source_kind}")),
        };
        let slot = rows
            .get_mut(bind_joint_index)
            .ok_or_else(|| "bind joint source index outside bind pose array".to_string())?;
        if slot.is_some() {
            return Err("duplicate bind joint source row".to_string());
        }
        *slot = Some(row);
    }
    rows.into_iter()
        .collect::<Option<Vec<_>>>()
        .ok_or_else(|| "bind joint sources did not populate every bind pose".to_string())
}

fn parse_blend_indices(rig: &serde_json::Value) -> Result<Vec<[u32; 4]>, String> {
    parse_u32_quads(
        rig.get("vertex_blend_indices")
            .ok_or_else(|| "recorded hand rig missing vertex_blend_indices".to_string())?,
        "vertex_blend_indices",
    )
}

fn parse_blend_weights(rig: &serde_json::Value) -> Result<Vec<[f32; 4]>, String> {
    parse_f32_quads(
        rig.get("vertex_blend_weights")
            .ok_or_else(|| "recorded hand rig missing vertex_blend_weights".to_string())?,
        "vertex_blend_weights",
    )
}

fn build_skinning_vertices(
    bind_vertices: &[[f32; 4]],
    bind_normals: &[[f32; 4]],
    blend_indices: &[[u32; 4]],
    blend_weights: &[[f32; 4]],
) -> Result<Vec<ReplayHandSkinningVertex>, String> {
    if bind_vertices.len() != bind_normals.len()
        || bind_vertices.len() != blend_indices.len()
        || bind_vertices.len() != blend_weights.len()
    {
        return Err("recorded hand skinning metadata must match vertex count".to_string());
    }
    Ok(bind_vertices
        .iter()
        .copied()
        .zip(bind_normals.iter().copied())
        .zip(blend_indices.iter().copied())
        .zip(blend_weights.iter().copied())
        .map(
            |(((bind_position, bind_normal), joint_indices), joint_weights)| {
                ReplayHandSkinningVertex {
                    bind_position,
                    bind_normal,
                    joint_indices,
                    joint_weights,
                }
            },
        )
        .collect())
}

fn mirror_hand(left: &ReplayHand) -> ReplayHand {
    ReplayHand {
        handedness: "right".to_string(),
        triangles: left.triangles.clone(),
        frames: left
            .frames
            .iter()
            .map(|frame| ReplayHandFrame {
                vertices: frame
                    .vertices
                    .iter()
                    .map(|position| [-position[0], position[1], position[2]])
                    .collect(),
            })
            .collect(),
        skinning: None,
        mesh_components: left.mesh_components.clone(),
    }
}

fn hand_by_handedness<'a>(
    hands: &'a [serde_json::Value],
    handedness: &str,
) -> Option<&'a serde_json::Value> {
    hands.iter().find(|hand| {
        text_field(hand, "handedness")
            .as_deref()
            .is_some_and(|value| value == handedness)
    })
}

fn parse_triangles(value: &serde_json::Value) -> Result<Vec<[u32; 3]>, String> {
    let rows = value
        .as_array()
        .ok_or_else(|| "triangle_indices must be an array".to_string())?;
    rows.iter()
        .map(|row| {
            let values = row
                .as_array()
                .ok_or_else(|| "triangle row must be an array".to_string())?;
            if values.len() != 3 {
                return Err("triangle row must have three indices".to_string());
            }
            Ok([
                u32_value(&values[0], "triangle a")?,
                u32_value(&values[1], "triangle b")?,
                u32_value(&values[2], "triangle c")?,
            ])
        })
        .collect()
}

fn component_filtered_triangles(
    vertex_count: usize,
    triangles: &[[u32; 3]],
) -> Result<(Vec<[u32; 4]>, MeshComponentSummary), String> {
    let (triangle_component_ranks, component_count, vertex_counts, triangle_counts) =
        analyze_mesh_components(vertex_count, triangles)?;
    let filtered = triangles
        .iter()
        .copied()
        .zip(triangle_component_ranks.iter().copied())
        .filter_map(|([a, b, c], rank)| {
            (rank < HAND_MESH_KEPT_COMPONENT_RANK_COUNT).then_some([a, b, c, rank])
        })
        .collect::<Vec<_>>();
    if filtered.is_empty() && !triangles.is_empty() {
        return Err("component filtering removed every hand mesh triangle".to_string());
    }
    let summary = MeshComponentSummary {
        component_count,
        vertex_counts,
        triangle_counts,
        source_triangle_count: triangles.len(),
        sampling_triangle_count: filtered.len(),
        dropped_triangle_count: triangles.len().saturating_sub(filtered.len()),
        component_filter_active: true,
    };
    Ok((filtered, summary))
}

fn analyze_mesh_components(
    vertex_count: usize,
    triangles: &[[u32; 3]],
) -> Result<(Vec<u32>, usize, Vec<usize>, Vec<usize>), String> {
    let mut union_find = UnionFind::new(vertex_count);
    for triangle in triangles {
        let [a, b, c] = triangle_vertices(*triangle, vertex_count)?;
        union_find.union(a, b);
        union_find.union(b, c);
        union_find.union(c, a);
    }

    let mut root_ids = Vec::<usize>::new();
    let mut vertex_component_ids = Vec::with_capacity(vertex_count);
    let mut component_vertex_counts = Vec::<usize>::new();
    for vertex_index in 0..vertex_count {
        let root = union_find.find(vertex_index);
        let component_id = if let Some(index) = root_ids.iter().position(|value| *value == root) {
            index
        } else {
            let component_id = root_ids.len();
            root_ids.push(root);
            component_vertex_counts.push(0);
            component_id
        };
        vertex_component_ids.push(component_id);
        component_vertex_counts[component_id] += 1;
    }

    let mut component_triangle_counts = vec![0_usize; component_vertex_counts.len()];
    let mut triangle_component_ids = Vec::with_capacity(triangles.len());
    for triangle in triangles {
        let [a, b, c] = triangle_vertices(*triangle, vertex_count)?;
        let component_id = vertex_component_ids[a];
        if vertex_component_ids[b] != component_id || vertex_component_ids[c] != component_id {
            return Err("recorded hand mesh triangle spans multiple components".to_string());
        }
        triangle_component_ids.push(component_id);
        component_triangle_counts[component_id] += 1;
    }

    let mut ranked_component_ids = (0..component_vertex_counts.len()).collect::<Vec<_>>();
    ranked_component_ids.sort_by(|left, right| {
        component_vertex_counts[*right]
            .cmp(&component_vertex_counts[*left])
            .then(component_triangle_counts[*right].cmp(&component_triangle_counts[*left]))
            .then(left.cmp(right))
    });
    let mut component_rank_by_id = vec![0_u32; component_vertex_counts.len()];
    for (rank, component_id) in ranked_component_ids.iter().copied().enumerate() {
        component_rank_by_id[component_id] = rank as u32;
    }
    let triangle_component_ranks = triangle_component_ids
        .iter()
        .map(|component_id| component_rank_by_id[*component_id])
        .collect::<Vec<_>>();
    let vertex_counts = ranked_component_ids
        .iter()
        .map(|component_id| component_vertex_counts[*component_id])
        .collect::<Vec<_>>();
    let triangle_counts = ranked_component_ids
        .iter()
        .map(|component_id| component_triangle_counts[*component_id])
        .collect::<Vec<_>>();
    Ok((
        triangle_component_ranks,
        ranked_component_ids.len(),
        vertex_counts,
        triangle_counts,
    ))
}

fn triangle_vertices(triangle: [u32; 3], vertex_count: usize) -> Result<[usize; 3], String> {
    let [a, b, c] = triangle;
    let indices = [
        usize::try_from(a).map_err(|_| "triangle vertex index does not fit usize".to_string())?,
        usize::try_from(b).map_err(|_| "triangle vertex index does not fit usize".to_string())?,
        usize::try_from(c).map_err(|_| "triangle vertex index does not fit usize".to_string())?,
    ];
    if indices.iter().any(|index| *index >= vertex_count) {
        return Err("triangle vertex index is outside the surface vertex range".to_string());
    }
    Ok(indices)
}

struct UnionFind {
    parent: Vec<usize>,
}

impl UnionFind {
    fn new(len: usize) -> Self {
        Self {
            parent: (0..len).collect(),
        }
    }

    fn find(&mut self, value: usize) -> usize {
        let parent = self.parent[value];
        if parent == value {
            return value;
        }
        let root = self.find(parent);
        self.parent[value] = root;
        root
    }

    fn union(&mut self, a: usize, b: usize) {
        let root_a = self.find(a);
        let root_b = self.find(b);
        if root_a != root_b {
            self.parent[root_b] = root_a;
        }
    }
}

fn join_usize(values: &[usize]) -> String {
    if values.is_empty() {
        return "none".to_string();
    }
    values
        .iter()
        .map(|value| value.to_string())
        .collect::<Vec<_>>()
        .join(";")
}

fn parse_vec3_rows(value: &serde_json::Value) -> Result<Vec<[f32; 3]>, String> {
    let rows = value
        .as_array()
        .ok_or_else(|| "vec3 rows must be an array".to_string())?;
    rows.iter()
        .map(|row| {
            let values = row
                .as_array()
                .ok_or_else(|| "vec3 row must be an array".to_string())?;
            if values.len() != 3 {
                return Err("vec3 row must have three values".to_string());
            }
            Ok([
                f32_value(&values[0], "vec3 x")?,
                f32_value(&values[1], "vec3 y")?,
                f32_value(&values[2], "vec3 z")?,
            ])
        })
        .collect()
}

fn parse_gpu_pose(value: &serde_json::Value) -> Result<ReplayHandGpuPose, String> {
    let [tx, ty, tz] = value
        .get("translation")
        .and_then(parse_vec3)
        .ok_or_else(|| "pose missing translation".to_string())?;
    let rotation_xyzw = value
        .get("rotation")
        .and_then(parse_vec4)
        .ok_or_else(|| "pose missing rotation".to_string())?;
    Ok(ReplayHandGpuPose {
        translation_pad: [tx, ty, tz, 0.0],
        rotation_xyzw,
    })
}

fn parse_vec3(value: &serde_json::Value) -> Option<[f32; 3]> {
    let values = value.as_array()?;
    if values.len() != 3 {
        return None;
    }
    Some([
        f32_value(&values[0], "vec3 x").ok()?,
        f32_value(&values[1], "vec3 y").ok()?,
        f32_value(&values[2], "vec3 z").ok()?,
    ])
}

fn parse_vec4(value: &serde_json::Value) -> Option<[f32; 4]> {
    let values = value.as_array()?;
    if values.len() != 4 {
        return None;
    }
    Some([
        f32_value(&values[0], "vec4 x").ok()?,
        f32_value(&values[1], "vec4 y").ok()?,
        f32_value(&values[2], "vec4 z").ok()?,
        f32_value(&values[3], "vec4 w").ok()?,
    ])
}

fn parse_u32_quads(value: &serde_json::Value, label: &str) -> Result<Vec<[u32; 4]>, String> {
    let rows = value
        .as_array()
        .ok_or_else(|| format!("{label} must be an array"))?;
    rows.iter()
        .map(|row| {
            let values = row
                .as_array()
                .filter(|values| values.len() == 4)
                .ok_or_else(|| format!("{label} entry must be [a,b,c,d]"))?;
            Ok([
                u32_value(&values[0], label)?,
                u32_value(&values[1], label)?,
                u32_value(&values[2], label)?,
                u32_value(&values[3], label)?,
            ])
        })
        .collect()
}

fn parse_f32_quads(value: &serde_json::Value, label: &str) -> Result<Vec<[f32; 4]>, String> {
    let rows = value
        .as_array()
        .ok_or_else(|| format!("{label} must be an array"))?;
    rows.iter()
        .map(|row| {
            let values = row
                .as_array()
                .filter(|values| values.len() == 4)
                .ok_or_else(|| format!("{label} entry must be [a,b,c,d]"))?;
            Ok([
                f32_value(&values[0], label)?,
                f32_value(&values[1], label)?,
                f32_value(&values[2], label)?,
                f32_value(&values[3], label)?,
            ])
        })
        .collect()
}

fn optional_usize(value: &serde_json::Value, field: &str) -> Option<usize> {
    value.get(field)?.as_u64().map(|value| value as usize)
}

fn text_field(value: &serde_json::Value, field: &str) -> Option<String> {
    value
        .get(field)
        .and_then(serde_json::Value::as_str)
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_owned)
}

fn u32_value(value: &serde_json::Value, field: &str) -> Result<u32, String> {
    let number = value
        .as_u64()
        .ok_or_else(|| format!("missing u32 value {field}"))?;
    u32::try_from(number).map_err(|_| format!("{field} is too large"))
}

fn f32_value(value: &serde_json::Value, field: &str) -> Result<f32, String> {
    value
        .as_f64()
        .filter(|number| number.is_finite())
        .map(|number| number as f32)
        .ok_or_else(|| format!("invalid f32 value {field}"))
}

unsafe fn create_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let vertex_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::VERTEX);
    let live_joint_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(1)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::VERTEX);
    let skinning_vertex_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(2)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::VERTEX);
    let skinning_triangle_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(3)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::VERTEX);
    let bind_joint_pose_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(4)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::VERTEX);
    let bind_joint_source_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(5)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::VERTEX);
    let bindings = [
        vertex_binding,
        live_joint_binding,
        skinning_vertex_binding,
        skinning_triangle_binding,
        bind_joint_pose_binding,
        bind_joint_source_binding,
    ];
    let layout_info = vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings);
    device
        .create_descriptor_set_layout(&layout_info, None)
        .map_err(|error| format!("create replay hand descriptor layout: {error:?}"))
}

unsafe fn create_descriptor_pool(device: &ash::Device) -> Result<vk::DescriptorPool, String> {
    let pool_size = vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(6);
    let pool_info = vk::DescriptorPoolCreateInfo::default()
        .pool_sizes(slice::from_ref(&pool_size))
        .max_sets(1);
    device
        .create_descriptor_pool(&pool_info, None)
        .map_err(|error| format!("create replay hand descriptor pool: {error:?}"))
}

unsafe fn create_descriptor_set(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    vertex_buffer: vk::Buffer,
    vertex_buffer_bytes: vk::DeviceSize,
    live_joint_buffer: vk::Buffer,
    live_joint_buffer_bytes: vk::DeviceSize,
    skinning_vertex_buffer: vk::Buffer,
    skinning_vertex_buffer_bytes: vk::DeviceSize,
    skinning_triangle_buffer: vk::Buffer,
    skinning_triangle_buffer_bytes: vk::DeviceSize,
    bind_joint_pose_buffer: vk::Buffer,
    bind_joint_pose_buffer_bytes: vk::DeviceSize,
    bind_joint_source_buffer: vk::Buffer,
    bind_joint_source_buffer_bytes: vk::DeviceSize,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [descriptor_set_layout];
    let allocate_info = vk::DescriptorSetAllocateInfo::default()
        .descriptor_pool(descriptor_pool)
        .set_layouts(&set_layouts);
    let descriptor_set = device
        .allocate_descriptor_sets(&allocate_info)
        .map_err(|error| format!("allocate replay hand descriptor set: {error:?}"))?[0];
    let vertex_buffer_info = vk::DescriptorBufferInfo::default()
        .buffer(vertex_buffer)
        .offset(0)
        .range(vertex_buffer_bytes);
    let live_joint_buffer_info = vk::DescriptorBufferInfo::default()
        .buffer(live_joint_buffer)
        .offset(0)
        .range(live_joint_buffer_bytes);
    let skinning_vertex_buffer_info = vk::DescriptorBufferInfo::default()
        .buffer(skinning_vertex_buffer)
        .offset(0)
        .range(skinning_vertex_buffer_bytes);
    let skinning_triangle_buffer_info = vk::DescriptorBufferInfo::default()
        .buffer(skinning_triangle_buffer)
        .offset(0)
        .range(skinning_triangle_buffer_bytes);
    let bind_joint_pose_buffer_info = vk::DescriptorBufferInfo::default()
        .buffer(bind_joint_pose_buffer)
        .offset(0)
        .range(bind_joint_pose_buffer_bytes);
    let bind_joint_source_buffer_info = vk::DescriptorBufferInfo::default()
        .buffer(bind_joint_source_buffer)
        .offset(0)
        .range(bind_joint_source_buffer_bytes);
    let vertex_write = vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(0)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(slice::from_ref(&vertex_buffer_info));
    let live_joint_write = vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(1)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(slice::from_ref(&live_joint_buffer_info));
    let skinning_vertex_write = vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(2)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(slice::from_ref(&skinning_vertex_buffer_info));
    let skinning_triangle_write = vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(3)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(slice::from_ref(&skinning_triangle_buffer_info));
    let bind_joint_pose_write = vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(4)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(slice::from_ref(&bind_joint_pose_buffer_info));
    let bind_joint_source_write = vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(5)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(slice::from_ref(&bind_joint_source_buffer_info));
    let writes = [
        vertex_write,
        live_joint_write,
        skinning_vertex_write,
        skinning_triangle_write,
        bind_joint_pose_write,
        bind_joint_source_write,
    ];
    device.update_descriptor_sets(&writes, &[]);
    Ok(descriptor_set)
}

unsafe fn create_pipeline_layout(
    device: &ash::Device,
    descriptor_set_layout: vk::DescriptorSetLayout,
) -> Result<vk::PipelineLayout, String> {
    let push_range = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::VERTEX)
        .offset(0)
        .size(mem::size_of::<ReplayHandPush>() as u32);
    let set_layouts = [descriptor_set_layout];
    let layout_info = vk::PipelineLayoutCreateInfo::default()
        .set_layouts(&set_layouts)
        .push_constant_ranges(slice::from_ref(&push_range));
    device
        .create_pipeline_layout(&layout_info, None)
        .map_err(|error| format!("create replay hand pipeline layout: {error:?}"))
}

unsafe fn create_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vert_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/replay_hands.vert.spv")),
    )?;
    let frag_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/replay_hands.frag.spv")),
    )?;
    let main = CStr::from_bytes_with_nul_unchecked(b"main\0");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vert_module)
            .name(main),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(frag_module)
            .name(main),
    ];
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
    let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic_state =
        vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .depth_clamp_enable(false)
        .rasterizer_discard_enable(false)
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let color_blend_attachment = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::ONE)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(vk::ColorComponentFlags::RGBA);
    let color_blend = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(slice::from_ref(&color_blend_attachment));
    let pipeline_info = vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .dynamic_state(&dynamic_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&color_blend)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0);
    let pipelines = device
        .create_graphics_pipelines(
            vk::PipelineCache::null(),
            slice::from_ref(&pipeline_info),
            None,
        )
        .map_err(|(_, error)| format!("create replay hand graphics pipeline: {error:?}"))?;
    device.destroy_shader_module(frag_module, None);
    device.destroy_shader_module(vert_module, None);
    Ok(pipelines[0])
}

unsafe fn create_host_visible_buffer(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    size: vk::DeviceSize,
    usage: vk::BufferUsageFlags,
    label: &str,
) -> Result<(vk::Buffer, vk::DeviceMemory), String> {
    let buffer_info = vk::BufferCreateInfo::default()
        .size(size)
        .usage(usage)
        .sharing_mode(vk::SharingMode::EXCLUSIVE);
    let buffer = device
        .create_buffer(&buffer_info, None)
        .map_err(|error| format!("create {label}: {error:?}"))?;
    let requirements = device.get_buffer_memory_requirements(buffer);
    let memory_type_index = find_memory_type_index(
        memory_properties,
        requirements.memory_type_bits,
        vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
    )
    .ok_or_else(|| {
        device.destroy_buffer(buffer, None);
        format!("no host-visible memory type for {label}")
    })?;
    let allocate_info = vk::MemoryAllocateInfo::default()
        .allocation_size(requirements.size)
        .memory_type_index(memory_type_index);
    let memory = device
        .allocate_memory(&allocate_info, None)
        .map_err(|error| {
            device.destroy_buffer(buffer, None);
            format!("allocate {label}: {error:?}")
        })?;
    device
        .bind_buffer_memory(buffer, memory, 0)
        .map_err(|error| {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            format!("bind {label}: {error:?}")
        })?;
    Ok((buffer, memory))
}

unsafe fn create_host_visible_buffer_with_data<T>(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    data: &[T],
    usage: vk::BufferUsageFlags,
    label: &str,
) -> Result<(vk::Buffer, vk::DeviceMemory, vk::DeviceSize), String> {
    if data.is_empty() {
        return Err(format!("{label} cannot be empty"));
    }
    let buffer_bytes = mem::size_of_val(data) as vk::DeviceSize;
    let (buffer, memory) =
        create_host_visible_buffer(device, memory_properties, buffer_bytes, usage, label)?;
    let mapped = device
        .map_memory(memory, 0, buffer_bytes, vk::MemoryMapFlags::empty())
        .map_err(|error| format!("map {label}: {error:?}"))?
        .cast::<T>();
    mapped.copy_from_nonoverlapping(data.as_ptr(), data.len());
    device.unmap_memory(memory);
    Ok((buffer, memory, buffer_bytes))
}

fn find_memory_type_index(
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    type_bits: u32,
    required: vk::MemoryPropertyFlags,
) -> Option<u32> {
    for index in 0..memory_properties.memory_type_count {
        let supported = (type_bits & (1 << index)) != 0;
        let flags = memory_properties.memory_types[index as usize].property_flags;
        if supported && flags.contains(required) {
            return Some(index);
        }
    }
    None
}

unsafe fn create_shader_module(
    device: &ash::Device,
    bytes: &[u8],
) -> Result<vk::ShaderModule, String> {
    if bytes.is_empty() || bytes.len() % mem::size_of::<u32>() != 0 {
        return Err("invalid replay hand SPIR-V length".to_string());
    }
    let code = bytes
        .chunks_exact(mem::size_of::<u32>())
        .map(|word| u32::from_le_bytes([word[0], word[1], word[2], word[3]]))
        .collect::<Vec<_>>();
    let shader_info = vk::ShaderModuleCreateInfo::default().code(&code);
    device
        .create_shader_module(&shader_info, None)
        .map_err(|error| format!("create replay hand shader module: {error:?}"))
}

fn as_bytes<T>(value: &T) -> &[u8] {
    unsafe { slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) }
}
