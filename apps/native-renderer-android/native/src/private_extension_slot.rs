//! Public ABI slot for an optional externally linked private layer graph.

use std::{ffi::CString, mem};

use ash::vk::{self, Handle};

use crate::{
    camera_projection::PreparedCameraProjection,
    camera_projection_metadata::{CameraProjectionMetadata, TargetRect},
    environment_depth_alignment_state::EnvironmentDepthAlignmentEyeOffsets,
    environment_depth_projection_alignment::{
        aligned_depth_uv_transform, IDENTITY_DEPTH_UV_TRANSFORM,
    },
    gpu_environment_depth_particle_stats::{
        DEPTH_FLAG_INFINITE_FAR, META_ENVIRONMENT_DEPTH_FORMAT, META_ENVIRONMENT_DEPTH_LAYER_COUNT,
        META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_FLAGS,
    },
    native_renderer_options::{
        NativeEnvironmentDepthSettings, NativePrivateLayerSettings,
        NativeProjectionBorderStretchSettings,
    },
    openxr_environment_depth::OpenXrEnvironmentDepthFrame,
};

include!(concat!(env!("OUT_DIR"), "/private_layer_payload_config.rs"));

pub(crate) const PRIVATE_LAYER_SLOT_ID: &str = "private-layer-slot-0";
pub(crate) const PRIVATE_LAYER_SLOT_ABI_ID: &str =
    "rusty.quest.native_renderer.private_layer_slot.v1";

const PRIVATE_GUIDE_WIDTH: u32 = 384;
const PRIVATE_GUIDE_HEIGHT: u32 = 384;
const PRIVATE_EYE_COUNT: usize = 2;
const PRIVATE_GUIDE_TARGET_COUNT: usize = 5;
const PRIVATE_GUIDE_PASS_COUNT: usize = 6;
const PRIVATE_LAYER_COUNT: u32 = 7;
const ASSUMED_DISPLAY_HZ: f32 = 90.0;

fn marker_vec4(values: [f32; 4]) -> String {
    format!(
        "{:.6},{:.6},{:.6},{:.6}",
        values[0], values[1], values[2], values[3]
    )
}

fn marker_vec3(values: [f32; 4]) -> String {
    format!("{:.6},{:.6},{:.6}", values[0], values[1], values[2])
}

fn marker_vec2(values: [f32; 4]) -> String {
    format!("{:.6},{:.6}", values[0], values[1])
}

fn marker_uv2(values: [f32; 2]) -> String {
    format!("{:.6},{:.6}", values[0], values[1])
}

fn marker_option_vec4(values: Option<[f32; 4]>) -> String {
    values
        .map(marker_vec4)
        .unwrap_or_else(|| "none".to_string())
}

fn marker_option_vec3(values: Option<[f32; 4]>) -> String {
    values
        .map(marker_vec3)
        .unwrap_or_else(|| "none".to_string())
}

fn marker_option_vec2(values: Option<[f32; 4]>) -> String {
    values
        .map(marker_vec2)
        .unwrap_or_else(|| "none".to_string())
}

fn marker_option_f32(value: Option<f32>, precision: usize) -> String {
    value
        .map(|value| format!("{value:.precision$}", precision = precision))
        .unwrap_or_else(|| "none".to_string())
}

fn depth_uv_transform_for_frame(frame: Option<&OpenXrEnvironmentDepthFrame>) -> [f32; 4] {
    frame
        .map(|frame| fov_uv_transform(frame.render_fov_tangents, frame.depth_fov_tangents))
        .unwrap_or(IDENTITY_DEPTH_UV_TRANSFORM)
}

fn fov_uv_transform(render_fov: [f32; 4], depth_fov: [f32; 4]) -> [f32; 4] {
    let render_width = render_fov[1] - render_fov[0];
    let render_height = render_fov[3] - render_fov[2];
    let depth_width = depth_fov[1] - depth_fov[0];
    let depth_height = depth_fov[3] - depth_fov[2];
    if !render_fov
        .iter()
        .chain(depth_fov.iter())
        .all(|value| value.is_finite())
        || render_width <= 0.000_001
        || render_height <= 0.000_001
        || depth_width <= 0.000_001
        || depth_height <= 0.000_001
    {
        return IDENTITY_DEPTH_UV_TRANSFORM;
    }
    [
        render_width / depth_width,
        (render_fov[0] - depth_fov[0]) / depth_width,
        render_height / depth_height,
        (render_fov[2] - depth_fov[2]) / depth_height,
    ]
}

pub(crate) struct PrivateExtensionSlotRuntime {
    invocation_sequence: u64,
    renderer: PrivateLayerGraphRenderer,
}

impl PrivateExtensionSlotRuntime {
    pub(crate) fn new(
        memory_properties: vk::PhysicalDeviceMemoryProperties,
        color_format: vk::Format,
        projection_render_pass: vk::RenderPass,
        environment_depth_image_handles: &[u64],
        environment_depth_width: u32,
        environment_depth_height: u32,
    ) -> Self {
        Self {
            invocation_sequence: 0,
            renderer: PrivateLayerGraphRenderer::new(
                memory_properties,
                color_format,
                projection_render_pass,
                environment_depth_image_handles,
                environment_depth_width,
                environment_depth_height,
            ),
        }
    }

    pub(crate) fn config_marker_fields(settings: NativePrivateLayerSettings) -> String {
        format!(
            "privateLayerSlotReady=true privateLayerSlotId={} privateLayerAbiId={} privateLayerPublicAbiOnly={} privateLayerPayloadLinked={} privateLayerImplementationPath={} privateLayerOutput={} privateLayerColorEffectActive={} privateLayerGuideResolution={}x{} privateLayerGuideTargets={} privateLayerGuidePasses={} {}",
            PRIVATE_LAYER_SLOT_ID,
            PRIVATE_LAYER_SLOT_ABI_ID,
            !PRIVATE_LAYER_PAYLOAD_LINKED,
            PRIVATE_LAYER_PAYLOAD_LINKED,
            PRIVATE_LAYER_IMPLEMENTATION_PATH,
            if PRIVATE_LAYER_PAYLOAD_LINKED && settings.enabled {
                "resident-private-guide-texture-final"
            } else {
                "identity-public-abi-resource"
            },
            PRIVATE_LAYER_PAYLOAD_LINKED && settings.enabled,
            PRIVATE_GUIDE_WIDTH,
            PRIVATE_GUIDE_HEIGHT,
            PRIVATE_GUIDE_TARGET_COUNT,
            PRIVATE_GUIDE_PASS_COUNT,
            settings.marker_fields()
        )
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        self.renderer.destroy(device);
    }

    pub(crate) unsafe fn set_environment_depth_images(
        &mut self,
        device: &ash::Device,
        image_handles: &[u64],
        width: u32,
        height: u32,
    ) {
        self.renderer
            .set_environment_depth_images(device, image_handles, width, height);
    }

    pub(crate) unsafe fn record_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_count: u64,
        guide_graph_ready: bool,
        sdf_field_ready: bool,
        prepared: Option<&PreparedCameraProjection>,
        projection_metadata: &CameraProjectionMetadata,
        settings: NativePrivateLayerSettings,
    ) -> PrivateExtensionSlotFrameStats {
        self.invocation_sequence = self.invocation_sequence.saturating_add(1);
        let mut stats = PrivateExtensionSlotFrameStats::new(
            frame_count,
            self.invocation_sequence,
            guide_graph_ready,
            sdf_field_ready,
            settings,
        );

        if !PRIVATE_LAYER_PAYLOAD_LINKED || !settings.enabled {
            return stats;
        }
        let Some(prepared) = prepared else {
            stats.payload_requested_without_camera = true;
            return stats;
        };

        match self.renderer.record_frame(
            device,
            cmd,
            prepared,
            projection_metadata,
            frame_count,
            settings,
        ) {
            Ok(render_stats) => {
                stats.ready = true;
                stats.rendered = render_stats.rendered;
                stats.render_count = render_stats.render_count;
                stats.cache_hits = render_stats.cache_hits;
                stats.left_source_frame = prepared.stats.left_source_frame;
                stats.right_source_frame = prepared.stats.right_source_frame;
                stats.left_hardware_buffer_id = prepared.stats.left_hardware_buffer_id;
                stats.right_hardware_buffer_id = prepared.stats.right_hardware_buffer_id;
            }
            Err(error) => {
                stats.error = true;
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "private-extension-slot",
                        format!(
                            "status=error reason={} {}",
                            crate::sanitize(&error),
                            stats.marker_fields()
                        ),
                    );
                }
            }
        }

        stats
    }

    pub(crate) unsafe fn record_projection_eye(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_index: usize,
        target_rect: TargetRect,
        prepared: &PreparedCameraProjection,
        projection_metadata: &CameraProjectionMetadata,
        frame_count: u64,
        settings: NativePrivateLayerSettings,
        projection_settings: NativeProjectionBorderStretchSettings,
        environment_depth_settings: NativeEnvironmentDepthSettings,
        environment_depth_frame: Option<&OpenXrEnvironmentDepthFrame>,
        depth_alignment_offsets: EnvironmentDepthAlignmentEyeOffsets,
    ) {
        if !PRIVATE_LAYER_PAYLOAD_LINKED || !settings.enabled {
            return;
        }
        self.renderer.record_projection_eye(
            device,
            cmd,
            extent,
            eye_index,
            target_rect,
            prepared,
            projection_metadata,
            frame_count,
            settings,
            projection_settings,
            environment_depth_settings,
            environment_depth_frame,
            depth_alignment_offsets,
        );
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct PrivateExtensionSlotFrameStats {
    frame_count: u64,
    invocation_sequence: u64,
    guide_graph_ready: bool,
    sdf_field_ready: bool,
    settings: NativePrivateLayerSettings,
    pub(crate) ready: bool,
    rendered: bool,
    error: bool,
    payload_requested_without_camera: bool,
    render_count: u64,
    cache_hits: u64,
    left_source_frame: u64,
    right_source_frame: u64,
    left_hardware_buffer_id: u64,
    right_hardware_buffer_id: u64,
}

impl PrivateExtensionSlotFrameStats {
    fn new(
        frame_count: u64,
        invocation_sequence: u64,
        guide_graph_ready: bool,
        sdf_field_ready: bool,
        settings: NativePrivateLayerSettings,
    ) -> Self {
        Self {
            frame_count,
            invocation_sequence,
            guide_graph_ready,
            sdf_field_ready,
            settings,
            ready: false,
            rendered: false,
            error: false,
            payload_requested_without_camera: false,
            render_count: 0,
            cache_hits: 0,
            left_source_frame: 0,
            right_source_frame: 0,
            left_hardware_buffer_id: 0,
            right_hardware_buffer_id: 0,
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        let output = if self.ready {
            "resident-private-guide-texture-final"
        } else {
            "identity-public-abi-resource"
        };
        let visual_acceptance = if self.ready {
            "pending-headset-screenshot"
        } else {
            "not-applicable-public-noop"
        };
        format!(
            "privateLayerSlotReady=true privateLayerSlotId={} privateLayerAbiId={} privateLayerPublicAbiOnly={} privateLayerPayloadLinked={} privateLayerImplementationPath={} privateLayerFrame={} privateLayerInvocationSequence={} privateLayerInputGuideGraphReady={} privateLayerInputSdfFieldReady={} privateLayerEnabled={} privateLayerReady={} privateLayerRendered={} privateLayerError={} privateLayerRequestedWithoutCamera={} privateLayerOutput={} privateLayerColorEffectActive={} privateLayerGuideResolution={}x{} privateLayerGuideTargets={} privateLayerGuidePasses={} privateLayerLayerSeconds={:.3} privateLayerActiveLayer={} privateLayerOverride={:.1} privateLayerFinalExternalHwbSamples={} privateLayerGuideTextureSamples={} privateLayerRenderCount={} privateLayerCacheHits={} privateLayerLeftSourceFrame={} privateLayerRightSourceFrame={} privateLayerLeftHardwareBufferId={} privateLayerRightHardwareBufferId={} privateLayerVisualAcceptance={} {}",
            PRIVATE_LAYER_SLOT_ID,
            PRIVATE_LAYER_SLOT_ABI_ID,
            !PRIVATE_LAYER_PAYLOAD_LINKED,
            PRIVATE_LAYER_PAYLOAD_LINKED,
            PRIVATE_LAYER_IMPLEMENTATION_PATH,
            self.frame_count,
            self.invocation_sequence,
            self.guide_graph_ready,
            self.sdf_field_ready,
            self.settings.enabled,
            self.ready,
            self.rendered,
            self.error,
            self.payload_requested_without_camera,
            output,
            PRIVATE_LAYER_PAYLOAD_LINKED && self.settings.enabled,
            PRIVATE_GUIDE_WIDTH,
            PRIVATE_GUIDE_HEIGHT,
            PRIVATE_GUIDE_TARGET_COUNT,
            PRIVATE_GUIDE_PASS_COUNT,
            self.settings.layer_seconds,
            active_layer_for_frame(self.frame_count, self.settings),
            self.settings.layer_override,
            if self.ready { 1 } else { 0 },
            if self.ready { PRIVATE_GUIDE_TARGET_COUNT } else { 0 },
            self.render_count,
            self.cache_hits,
            self.left_source_frame,
            self.right_source_frame,
            self.left_hardware_buffer_id,
            self.right_hardware_buffer_id,
            visual_acceptance,
            self.settings.marker_fields()
        )
    }
}

impl Default for PrivateExtensionSlotFrameStats {
    fn default() -> Self {
        Self::new(0, 0, false, false, NativePrivateLayerSettings::default())
    }
}

struct PrivateLayerGraphRenderer {
    memory_properties: vk::PhysicalDeviceMemoryProperties,
    color_format: vk::Format,
    projection_render_pass: vk::RenderPass,
    environment_depth_image_handles: Vec<u64>,
    environment_depth_width: u32,
    environment_depth_height: u32,
    resources: Option<PrivateLayerResources>,
    render_count: u64,
    cache_hits: u64,
}

impl PrivateLayerGraphRenderer {
    fn new(
        memory_properties: vk::PhysicalDeviceMemoryProperties,
        color_format: vk::Format,
        projection_render_pass: vk::RenderPass,
        environment_depth_image_handles: &[u64],
        environment_depth_width: u32,
        environment_depth_height: u32,
    ) -> Self {
        Self {
            memory_properties,
            color_format,
            projection_render_pass,
            environment_depth_image_handles: environment_depth_image_handles.to_vec(),
            environment_depth_width,
            environment_depth_height,
            resources: None,
            render_count: 0,
            cache_hits: 0,
        }
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(resources) = self.resources.take() {
            resources.destroy(device);
        }
    }

    unsafe fn set_environment_depth_images(
        &mut self,
        device: &ash::Device,
        image_handles: &[u64],
        width: u32,
        height: u32,
    ) {
        if self.environment_depth_image_handles.as_slice() == image_handles
            && self.environment_depth_width == width
            && self.environment_depth_height == height
        {
            return;
        }
        if let Some(resources) = self.resources.take() {
            resources.destroy(device);
        }
        self.environment_depth_image_handles = image_handles.to_vec();
        self.environment_depth_width = width;
        self.environment_depth_height = height;
        crate::marker(
            "private-extension-slot",
            format!(
                "status=depth-resources-updated privateLayerEnvironmentDepthImages={} privateLayerEnvironmentDepthImageSize={}x{}",
                self.environment_depth_image_handles.len(),
                self.environment_depth_width,
                self.environment_depth_height
            ),
        );
    }

    unsafe fn record_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        prepared: &PreparedCameraProjection,
        projection_metadata: &CameraProjectionMetadata,
        frame_count: u64,
        settings: NativePrivateLayerSettings,
    ) -> Result<PrivateLayerRenderStats, String> {
        self.ensure_resources(device, prepared.descriptor_set_layout)?;
        let resources = self
            .resources
            .as_ref()
            .ok_or_else(|| "private layer graph resources were not initialized".to_string())?;
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: PRIVATE_GUIDE_WIDTH as f32,
            height: PRIVATE_GUIDE_HEIGHT as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = [vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent: private_guide_extent(),
        }];
        let pass_targets = [(0, 0), (1, 1), (2, 2), (3, 3), (4, 1), (5, 4)];
        let elapsed_seconds = elapsed_seconds_for_frame(frame_count);
        let cycle_phase = elapsed_seconds / settings.layer_seconds.max(0.001);

        for eye_index in 0..PRIVATE_EYE_COUNT {
            let eye = &resources.eyes[eye_index];
            for (pass_index, target_index) in pass_targets {
                let target = eye
                    .targets
                    .get(target_index)
                    .ok_or_else(|| "private layer target index out of range".to_string())?;
                begin_private_pass(device, cmd, resources.render_pass, target.framebuffer);
                device.cmd_set_viewport(cmd, 0, &viewport);
                device.cmd_set_scissor(cmd, 0, &scissor);
                device.cmd_bind_pipeline(
                    cmd,
                    vk::PipelineBindPoint::GRAPHICS,
                    resources.guide_pipelines[pass_index],
                );
                device.cmd_bind_descriptor_sets(
                    cmd,
                    vk::PipelineBindPoint::GRAPHICS,
                    resources.guide_pipeline_layout,
                    0,
                    &[prepared.descriptor_set, eye.descriptor_set],
                    &[],
                );
                let push = PrivateLayerGuidePush {
                    params0: [
                        eye_index as f32,
                        projection_metadata.source_sample_y_flip,
                        PRIVATE_GUIDE_WIDTH as f32,
                        PRIVATE_GUIDE_HEIGHT as f32,
                    ],
                    effect: settings.effect,
                    cycle: [
                        cycle_phase,
                        active_layer_for_frame(frame_count, settings) as f32,
                        settings.layer_seconds,
                        0.0,
                    ],
                };
                push_fragment_constants(device, cmd, resources.guide_pipeline_layout, &push);
                device.cmd_draw(cmd, 3, 1, 0, 0);
                device.cmd_end_render_pass(cmd);
                transition_private_image_for_sampling(device, cmd, target.image);
            }
        }

        self.render_count = self.render_count.saturating_add(1);
        Ok(PrivateLayerRenderStats {
            rendered: true,
            render_count: self.render_count,
            cache_hits: self.cache_hits,
        })
    }

    unsafe fn record_projection_eye(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_index: usize,
        target_rect: TargetRect,
        prepared: &PreparedCameraProjection,
        projection_metadata: &CameraProjectionMetadata,
        frame_count: u64,
        settings: NativePrivateLayerSettings,
        projection_settings: NativeProjectionBorderStretchSettings,
        environment_depth_settings: NativeEnvironmentDepthSettings,
        environment_depth_frame: Option<&OpenXrEnvironmentDepthFrame>,
        depth_alignment_offsets: EnvironmentDepthAlignmentEyeOffsets,
    ) {
        let Some(resources) = self.resources.as_mut() else {
            return;
        };
        let Some(eye) = resources.eyes.get(eye_index) else {
            return;
        };
        let depth_binding = resources
            .depth_resources
            .descriptor_for_frame(environment_depth_frame);
        if !depth_binding.real_depth_bound {
            resources
                .depth_resources
                .transition_fallback_for_sampling(device, cmd);
        }
        let depth_source_layer = environment_depth_settings
            .source_view_index()
            .min((META_ENVIRONMENT_DEPTH_LAYER_COUNT - 1) as usize)
            as f32;
        let depth_uv_transform_base = depth_uv_transform_for_frame(
            environment_depth_frame.filter(|_| depth_binding.real_depth_bound),
        );
        let reference_target_rect = projection_metadata.rect_for_eye(eye_index);
        let depth_sample_scale = depth_alignment_offsets.sample_scale;
        let aligned_depth_transform = aligned_depth_uv_transform(
            depth_uv_transform_base,
            reference_target_rect,
            target_rect,
            depth_alignment_offsets,
        );
        let depth_uv_transform = aligned_depth_transform.depth_uv_transform;
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: extent.width as f32,
            height: extent.height as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = [target_rect_to_scissor(extent, target_rect)];
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_bind_pipeline(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            resources.projection_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            resources.projection_pipeline_layout,
            0,
            &[
                prepared.descriptor_set,
                eye.descriptor_set,
                depth_binding.descriptor_set,
            ],
            &[],
        );
        let elapsed_seconds = elapsed_seconds_for_frame(frame_count);
        let projection_push = projection_settings.push_params();
        let depth_near_z = environment_depth_frame
            .filter(|_| depth_binding.real_depth_bound)
            .map(|frame| frame.near_z.max(0.001))
            .unwrap_or(0.001);
        let depth_infinite_far = environment_depth_frame
            .filter(|_| depth_binding.real_depth_bound)
            .map(|frame| !frame.far_z.is_finite() || frame.far_z <= frame.near_z)
            .unwrap_or(false);
        let depth_far_z = environment_depth_frame
            .filter(|_| depth_binding.real_depth_bound)
            .map(|frame| {
                if frame.far_z.is_finite() && frame.far_z > frame.near_z {
                    frame.far_z
                } else {
                    environment_depth_settings.far_m.max(depth_near_z + 0.001)
                }
            })
            .unwrap_or(environment_depth_settings.far_m.max(depth_near_z + 0.001));
        let depth_flags = if depth_infinite_far {
            DEPTH_FLAG_INFINITE_FAR
        } else {
            0
        };
        if frame_count == 0 || frame_count % 120 == 0 {
            let (swapchain_index, depth_width, depth_height) = environment_depth_frame
                .filter(|_| depth_binding.real_depth_bound)
                .map(|frame| {
                    (
                        frame.swapchain_index as i32,
                        frame.depth_width,
                        frame.depth_height,
                    )
                })
                .unwrap_or((-1, 0, 0));
            let source_view_index = environment_depth_frame
                .filter(|_| depth_binding.real_depth_bound)
                .map(|frame| frame.source_view_index.to_string())
                .unwrap_or_else(|| "none".to_string());
            let depth_fov_tangents = marker_option_vec4(
                environment_depth_frame
                    .filter(|_| depth_binding.real_depth_bound)
                    .map(|frame| frame.depth_fov_tangents),
            );
            let render_fov_tangents = marker_option_vec4(
                environment_depth_frame
                    .filter(|_| depth_binding.real_depth_bound)
                    .map(|frame| frame.render_fov_tangents),
            );
            let depth_fov_span_deg = marker_option_vec2(
                environment_depth_frame
                    .filter(|_| depth_binding.real_depth_bound)
                    .map(|frame| frame.depth_fov_span_deg),
            );
            let render_fov_span_deg = marker_option_vec2(
                environment_depth_frame
                    .filter(|_| depth_binding.real_depth_bound)
                    .map(|frame| frame.render_fov_span_deg),
            );
            let depth_pose_position = marker_option_vec3(
                environment_depth_frame
                    .filter(|_| depth_binding.real_depth_bound)
                    .map(|frame| frame.depth_eye_position),
            );
            let render_pose_position = marker_option_vec3(
                environment_depth_frame
                    .filter(|_| depth_binding.real_depth_bound)
                    .map(|frame| frame.render_eye_position),
            );
            let depth_to_render_position_delta_m = marker_option_f32(
                environment_depth_frame
                    .filter(|_| depth_binding.real_depth_bound)
                    .map(|frame| frame.depth_to_render_position_delta_m),
                5,
            );
            let depth_to_render_yaw_delta_deg = marker_option_f32(
                environment_depth_frame
                    .filter(|_| depth_binding.real_depth_bound)
                    .map(|frame| frame.depth_to_render_yaw_delta_deg),
                3,
            );
            crate::marker(
                "private-extension-slot",
                format!(
                    "status=projection-depth frame={} privateLayerEyeIndex={} privateLayerEnvironmentDepthBound={} privateLayerEnvironmentDepthFallbackActive={} privateLayerEnvironmentDepthSourceLayer={} privateLayerEnvironmentDepthSourceViewIndex={} privateLayerEnvironmentDepthLayerPolicy={} privateLayerEnvironmentDepthProjectionLayerPolicy=runtime-layer-policy privateLayerEnvironmentDepthSampledLayerMask={} privateLayerEnvironmentDepthUvMapping=render-view-uv-target-reference-fov-affine-texture-transform+manual-offset+centered-scale privateLayerEnvironmentDepthPoseFovShaderInput=fov-affine privateLayerEnvironmentDepthPoseDeltaShaderInput=false privateLayerEnvironmentDepthRenderUvSource=full-eye-fragment-uv privateLayerEnvironmentDepthTargetUvSource=camera-target-content-uv privateLayerEnvironmentDepthReferenceTargetRect={} privateLayerEnvironmentDepthEffectiveTargetRect={} privateLayerEnvironmentDepthTargetReferenceUvTransform={} privateLayerEnvironmentDepthProjectionScaleCompensation={:.6},{:.6} privateLayerEnvironmentDepthAlignmentMode=manual-uv-offset+sample-scale privateLayerEnvironmentDepthBaseOffsetUv={} privateLayerEnvironmentDepthManualOffsetUv={} privateLayerEnvironmentDepthEffectiveOffsetUv={} privateLayerEnvironmentDepthSampleScale={:.4} privateLayerEnvironmentDepthScaleAppliesTo=environment-depth-sampler-only privateLayerEnvironmentDepthUvTransformBase={} privateLayerEnvironmentDepthUvTransform={} privateLayerEnvironmentDepthViewFovTangents={} privateLayerRenderViewFovTangents={} privateLayerEnvironmentDepthViewFovSpanDeg={} privateLayerEnvironmentDepthViewPosePositionM={} privateLayerRenderViewFovSpanDeg={} privateLayerRenderViewPosePositionM={} privateLayerDepthToRenderPositionDeltaM={} privateLayerDepthToRenderYawDeltaDeg={} privateLayerEnvironmentDepthSwapchainIndex={} privateLayerEnvironmentDepthImageSize={}x{} privateLayerEnvironmentDepthNearZ={:.3} privateLayerEnvironmentDepthFarZ={:.3} privateLayerEnvironmentDepthInfiniteFar={} privateLayerEnvironmentDepthTextureTransform=rotate0+flipY",
                    frame_count,
                    eye_index,
                    depth_binding.real_depth_bound,
                    !depth_binding.real_depth_bound,
                    depth_source_layer as u32,
                    source_view_index,
                    environment_depth_settings.layer_policy_marker_value(),
                    environment_depth_settings.sampled_layer_mask(),
                    reference_target_rect.as_xywh_token(),
                    target_rect.as_xywh_token(),
                    marker_vec4(aligned_depth_transform.target_reference_uv_transform),
                    aligned_depth_transform.target_reference_uv_transform[0],
                    aligned_depth_transform.target_reference_uv_transform[2],
                    marker_uv2(depth_alignment_offsets.base_offset_uv),
                    marker_uv2(depth_alignment_offsets.manual_offset_uv),
                    marker_uv2(depth_alignment_offsets.effective_offset_uv),
                    depth_sample_scale,
                    marker_vec4(depth_uv_transform_base),
                    marker_vec4(depth_uv_transform),
                    depth_fov_tangents,
                    render_fov_tangents,
                    depth_fov_span_deg,
                    depth_pose_position,
                    render_fov_span_deg,
                    render_pose_position,
                    depth_to_render_position_delta_m,
                    depth_to_render_yaw_delta_deg,
                    swapchain_index,
                    depth_width,
                    depth_height,
                    depth_near_z,
                    depth_far_z,
                    depth_infinite_far,
                ),
            );
        }
        let push = PrivateLayerProjectionPush {
            target_rect: [
                target_rect.x,
                target_rect.y,
                target_rect.width,
                target_rect.height,
            ],
            params0: [
                eye_index as f32,
                projection_metadata.source_sample_y_flip,
                elapsed_seconds,
                settings.layer_override,
            ],
            effect: settings.effect,
            cycle: [
                elapsed_seconds / settings.layer_seconds.max(0.001),
                settings.layer_seconds,
                1.0,
                0.0,
            ],
            border_blend: [
                projection_push.params[0],
                projection_push.stretch1[0],
                projection_push.stretch1[1],
                projection_settings.video_border_blend_mode.shader_code(),
            ],
            depth: [
                if depth_binding.real_depth_bound {
                    1.0
                } else {
                    0.0
                },
                depth_near_z,
                depth_far_z,
                depth_source_layer,
            ],
            depth_aux: [
                depth_flags as f32,
                META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_FLAGS,
                environment_depth_settings.near_m,
                environment_depth_settings.far_m,
            ],
            depth_uv_transform,
        };
        push_fragment_constants(device, cmd, resources.projection_pipeline_layout, &push);
        device.cmd_draw(cmd, 3, 1, 0, 0);
    }

    unsafe fn ensure_resources(
        &mut self,
        device: &ash::Device,
        camera_descriptor_set_layout: vk::DescriptorSetLayout,
    ) -> Result<(), String> {
        if self
            .resources
            .as_ref()
            .map(|resources| resources.camera_descriptor_set_layout == camera_descriptor_set_layout)
            .unwrap_or(false)
        {
            return Ok(());
        }
        if let Some(resources) = self.resources.take() {
            resources.destroy(device);
        }
        self.resources = Some(PrivateLayerResources::new(
            device,
            &self.memory_properties,
            self.color_format,
            self.projection_render_pass,
            camera_descriptor_set_layout,
            &self.environment_depth_image_handles,
        )?);
        crate::marker(
            "private-extension-slot",
            format!(
                "status=created privateLayerSlotId={} privateLayerAbiId={} privateLayerPayloadLinked={} privateLayerImplementationPath={} privateLayerGraphPath=external-fragment-payload privateLayerGuideResolution={}x{} privateLayerGuideTargets={} privateLayerGuidePasses={} privateLayerDescriptorShape=set0-camera-2-samplers,set1-guide-5-samplers,set2-environment-depth-1-sampler privateLayerEnvironmentDepthFallback=true privateLayerEnvironmentDepthImages={} privateLayerEnvironmentDepthImageSize={}x{} privateLayerFinalProjectionSource=camera-plus-resident-guide-textures",
                PRIVATE_LAYER_SLOT_ID,
                PRIVATE_LAYER_SLOT_ABI_ID,
                PRIVATE_LAYER_PAYLOAD_LINKED,
                PRIVATE_LAYER_IMPLEMENTATION_PATH,
                PRIVATE_GUIDE_WIDTH,
                PRIVATE_GUIDE_HEIGHT,
                PRIVATE_GUIDE_TARGET_COUNT,
                PRIVATE_GUIDE_PASS_COUNT,
                self.environment_depth_image_handles.len(),
                self.environment_depth_width,
                self.environment_depth_height
            ),
        );
        Ok(())
    }
}

#[derive(Clone, Copy, Debug)]
struct PrivateLayerRenderStats {
    rendered: bool,
    render_count: u64,
    cache_hits: u64,
}

struct PrivateLayerResources {
    camera_descriptor_set_layout: vk::DescriptorSetLayout,
    render_pass: vk::RenderPass,
    sampler: vk::Sampler,
    guide_descriptor_set_layout: vk::DescriptorSetLayout,
    depth_descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    depth_resources: PrivateLayerDepthResources,
    guide_pipeline_layout: vk::PipelineLayout,
    projection_pipeline_layout: vk::PipelineLayout,
    guide_pipelines: [vk::Pipeline; PRIVATE_GUIDE_PASS_COUNT],
    projection_pipeline: vk::Pipeline,
    eyes: Vec<PrivateLayerEyeResources>,
}

impl PrivateLayerResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        color_format: vk::Format,
        projection_render_pass: vk::RenderPass,
        camera_descriptor_set_layout: vk::DescriptorSetLayout,
        environment_depth_image_handles: &[u64],
    ) -> Result<Self, String> {
        let render_pass = create_private_render_pass(device, color_format)?;
        let sampler = device
            .create_sampler(
                &vk::SamplerCreateInfo::default()
                    .mag_filter(vk::Filter::LINEAR)
                    .min_filter(vk::Filter::LINEAR)
                    .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                    .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE),
                None,
            )
            .map_err(|error| {
                device.destroy_render_pass(render_pass, None);
                format!("create private layer sampler: {error}")
            })?;
        let guide_descriptor_set_layout = match create_guide_descriptor_set_layout(device) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_sampler(sampler, None);
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let depth_descriptor_set_layout = match create_depth_descriptor_set_layout(device) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_descriptor_set_layout(guide_descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let descriptor_pool = match create_descriptor_pool(device) {
            Ok(pool) => pool,
            Err(error) => {
                device.destroy_descriptor_set_layout(depth_descriptor_set_layout, None);
                device.destroy_descriptor_set_layout(guide_descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let depth_resources = match PrivateLayerDepthResources::new(
            device,
            memory_properties,
            depth_descriptor_set_layout,
            environment_depth_image_handles,
        ) {
            Ok(resources) => resources,
            Err(error) => {
                destroy_descriptor_scaffold(
                    device,
                    descriptor_pool,
                    guide_descriptor_set_layout,
                    depth_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                return Err(error);
            }
        };
        let guide_pipeline_layout = match create_pipeline_layout::<PrivateLayerGuidePush>(
            device,
            &[camera_descriptor_set_layout, guide_descriptor_set_layout],
        ) {
            Ok(layout) => layout,
            Err(error) => {
                destroy_descriptor_scaffold(
                    device,
                    descriptor_pool,
                    guide_descriptor_set_layout,
                    depth_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                depth_resources.destroy(device);
                return Err(format!(
                    "create private layer guide pipeline layout: {error}"
                ));
            }
        };
        let projection_pipeline_layout = match create_pipeline_layout::<PrivateLayerProjectionPush>(
            device,
            &[
                camera_descriptor_set_layout,
                guide_descriptor_set_layout,
                depth_descriptor_set_layout,
            ],
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_pipeline_layout(guide_pipeline_layout, None);
                destroy_descriptor_scaffold(
                    device,
                    descriptor_pool,
                    guide_descriptor_set_layout,
                    depth_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                depth_resources.destroy(device);
                return Err(format!(
                    "create private layer projection pipeline layout: {error}"
                ));
            }
        };

        let guide_spirv: [&[u8]; PRIVATE_GUIDE_PASS_COUNT] = [
            &include_bytes!(concat!(
                env!("OUT_DIR"),
                "/private_layer_guide_analysis0.frag.spv"
            ))[..],
            &include_bytes!(concat!(
                env!("OUT_DIR"),
                "/private_layer_guide_scratch_horizontal.frag.spv"
            ))[..],
            &include_bytes!(concat!(
                env!("OUT_DIR"),
                "/private_layer_guide_analysis1.frag.spv"
            ))[..],
            &include_bytes!(concat!(
                env!("OUT_DIR"),
                "/private_layer_guide_control0.frag.spv"
            ))[..],
            &include_bytes!(concat!(
                env!("OUT_DIR"),
                "/private_layer_guide_scratch_strength.frag.spv"
            ))[..],
            &include_bytes!(concat!(
                env!("OUT_DIR"),
                "/private_layer_guide_control1.frag.spv"
            ))[..],
        ];
        let mut guide_pipeline_vec = Vec::with_capacity(PRIVATE_GUIDE_PASS_COUNT);
        for (index, spirv) in guide_spirv.iter().enumerate() {
            match create_graphics_pipeline(
                device,
                render_pass,
                guide_pipeline_layout,
                include_bytes!(concat!(env!("OUT_DIR"), "/camera_projection.vert.spv")),
                spirv,
                false,
                &format!("private layer guide pass {index}"),
            ) {
                Ok(pipeline) => guide_pipeline_vec.push(pipeline),
                Err(error) => {
                    for pipeline in guide_pipeline_vec {
                        device.destroy_pipeline(pipeline, None);
                    }
                    destroy_layout_scaffold(
                        device,
                        projection_pipeline_layout,
                        guide_pipeline_layout,
                        descriptor_pool,
                        guide_descriptor_set_layout,
                        depth_descriptor_set_layout,
                        sampler,
                        render_pass,
                    );
                    depth_resources.destroy(device);
                    return Err(error);
                }
            }
        }
        let guide_pipelines: [vk::Pipeline; PRIVATE_GUIDE_PASS_COUNT] =
            guide_pipeline_vec.try_into().map_err(|_| {
                "private layer guide pipeline count mismatch after creation".to_string()
            })?;
        let projection_pipeline = match create_graphics_pipeline(
            device,
            projection_render_pass,
            projection_pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/camera_projection.vert.spv")),
            include_bytes!(concat!(
                env!("OUT_DIR"),
                "/private_layer_projection.frag.spv"
            )),
            true,
            "private layer projection",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                for pipeline in guide_pipelines {
                    device.destroy_pipeline(pipeline, None);
                }
                destroy_layout_scaffold(
                    device,
                    projection_pipeline_layout,
                    guide_pipeline_layout,
                    descriptor_pool,
                    guide_descriptor_set_layout,
                    depth_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                depth_resources.destroy(device);
                return Err(error);
            }
        };

        let mut eyes = Vec::with_capacity(PRIVATE_EYE_COUNT);
        for eye_index in 0..PRIVATE_EYE_COUNT {
            match PrivateLayerEyeResources::new(
                device,
                memory_properties,
                color_format,
                render_pass,
                descriptor_pool,
                guide_descriptor_set_layout,
                sampler,
                eye_index,
            ) {
                Ok(eye) => eyes.push(eye),
                Err(error) => {
                    for eye in eyes.drain(..) {
                        eye.destroy(device);
                    }
                    device.destroy_pipeline(projection_pipeline, None);
                    for pipeline in guide_pipelines {
                        device.destroy_pipeline(pipeline, None);
                    }
                    destroy_layout_scaffold(
                        device,
                        projection_pipeline_layout,
                        guide_pipeline_layout,
                        descriptor_pool,
                        guide_descriptor_set_layout,
                        depth_descriptor_set_layout,
                        sampler,
                        render_pass,
                    );
                    depth_resources.destroy(device);
                    return Err(error);
                }
            }
        }

        Ok(Self {
            camera_descriptor_set_layout,
            render_pass,
            sampler,
            guide_descriptor_set_layout,
            depth_descriptor_set_layout,
            descriptor_pool,
            depth_resources,
            guide_pipeline_layout,
            projection_pipeline_layout,
            guide_pipelines,
            projection_pipeline,
            eyes,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        for eye in self.eyes {
            eye.destroy(device);
        }
        device.destroy_pipeline(self.projection_pipeline, None);
        for pipeline in self.guide_pipelines {
            device.destroy_pipeline(pipeline, None);
        }
        device.destroy_pipeline_layout(self.projection_pipeline_layout, None);
        device.destroy_pipeline_layout(self.guide_pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        self.depth_resources.destroy(device);
        device.destroy_descriptor_set_layout(self.depth_descriptor_set_layout, None);
        device.destroy_descriptor_set_layout(self.guide_descriptor_set_layout, None);
        device.destroy_sampler(self.sampler, None);
        device.destroy_render_pass(self.render_pass, None);
    }
}

struct PrivateLayerEyeResources {
    targets: Vec<PrivateLayerImage>,
    descriptor_set: vk::DescriptorSet,
}

impl PrivateLayerEyeResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        color_format: vk::Format,
        render_pass: vk::RenderPass,
        descriptor_pool: vk::DescriptorPool,
        descriptor_set_layout: vk::DescriptorSetLayout,
        sampler: vk::Sampler,
        eye_index: usize,
    ) -> Result<Self, String> {
        let mut targets = Vec::with_capacity(PRIVATE_GUIDE_TARGET_COUNT);
        for target_index in 0..PRIVATE_GUIDE_TARGET_COUNT {
            match PrivateLayerImage::new(
                device,
                memory_properties,
                color_format,
                render_pass,
                &format!("private layer eye {eye_index} target {target_index}"),
            ) {
                Ok(image) => targets.push(image),
                Err(error) => {
                    for target in targets.drain(..) {
                        target.destroy(device);
                    }
                    return Err(error);
                }
            }
        }
        let descriptor_set =
            match allocate_guide_descriptor_set(device, descriptor_pool, descriptor_set_layout) {
                Ok(set) => set,
                Err(error) => {
                    for target in targets.drain(..) {
                        target.destroy(device);
                    }
                    return Err(error);
                }
            };
        write_guide_descriptor_set(device, descriptor_set, sampler, &targets);
        Ok(Self {
            targets,
            descriptor_set,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        for target in self.targets {
            target.destroy(device);
        }
    }
}

struct PrivateLayerDepthBinding {
    descriptor_set: vk::DescriptorSet,
    real_depth_bound: bool,
}

struct PrivateLayerDepthResources {
    descriptor_pool: vk::DescriptorPool,
    sampler: vk::Sampler,
    fallback: PrivateLayerDepthFallbackImage,
    fallback_ready: bool,
    fallback_descriptor_set: vk::DescriptorSet,
    real_image_views: Vec<vk::ImageView>,
    real_descriptor_sets: Vec<vk::DescriptorSet>,
}

impl PrivateLayerDepthResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        descriptor_set_layout: vk::DescriptorSetLayout,
        depth_image_handles: &[u64],
    ) -> Result<Self, String> {
        let sampler = match device.create_sampler(
            &vk::SamplerCreateInfo::default()
                .mag_filter(vk::Filter::NEAREST)
                .min_filter(vk::Filter::NEAREST)
                .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .border_color(vk::BorderColor::FLOAT_OPAQUE_BLACK),
            None,
        ) {
            Ok(sampler) => sampler,
            Err(error) => {
                return Err(format!("create private layer depth sampler: {error}"));
            }
        };
        let fallback = match PrivateLayerDepthFallbackImage::new(device, memory_properties) {
            Ok(image) => image,
            Err(error) => {
                device.destroy_sampler(sampler, None);
                return Err(error);
            }
        };
        let descriptor_count = depth_image_handles.len().saturating_add(1) as u32;
        let descriptor_pool = match create_depth_descriptor_pool(device, descriptor_count) {
            Ok(pool) => pool,
            Err(error) => {
                fallback.destroy(device);
                device.destroy_sampler(sampler, None);
                return Err(error);
            }
        };

        let mut real_image_views = Vec::with_capacity(depth_image_handles.len());
        for (index, image_handle) in depth_image_handles.iter().copied().enumerate() {
            let image = vk::Image::from_raw(image_handle);
            match device.create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .image(image)
                    .view_type(vk::ImageViewType::TYPE_2D_ARRAY)
                    .format(META_ENVIRONMENT_DEPTH_FORMAT)
                    .subresource_range(depth_subresource_range()),
                None,
            ) {
                Ok(view) => real_image_views.push(view),
                Err(error) => {
                    for view in real_image_views {
                        device.destroy_image_view(view, None);
                    }
                    device.destroy_descriptor_pool(descriptor_pool, None);
                    fallback.destroy(device);
                    device.destroy_sampler(sampler, None);
                    return Err(format!(
                        "create private layer environment depth image view index={index}: {error}"
                    ));
                }
            }
        }

        let set_layouts = vec![descriptor_set_layout; descriptor_count as usize];
        let descriptor_sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&set_layouts),
        ) {
            Ok(sets) => sets,
            Err(error) => {
                for view in real_image_views {
                    device.destroy_image_view(view, None);
                }
                device.destroy_descriptor_pool(descriptor_pool, None);
                fallback.destroy(device);
                device.destroy_sampler(sampler, None);
                return Err(format!(
                    "allocate private layer depth descriptor sets: {error}"
                ));
            }
        };
        let fallback_descriptor_set = descriptor_sets[0];
        write_depth_descriptor_set(device, fallback_descriptor_set, sampler, fallback.view);
        let real_descriptor_sets = descriptor_sets.iter().copied().skip(1).collect::<Vec<_>>();
        for (descriptor_set, image_view) in real_descriptor_sets
            .iter()
            .copied()
            .zip(real_image_views.iter().copied())
        {
            write_depth_descriptor_set(device, descriptor_set, sampler, image_view);
        }

        Ok(Self {
            descriptor_pool,
            sampler,
            fallback,
            fallback_ready: false,
            fallback_descriptor_set,
            real_image_views,
            real_descriptor_sets,
        })
    }

    fn descriptor_for_frame(
        &self,
        frame: Option<&OpenXrEnvironmentDepthFrame>,
    ) -> PrivateLayerDepthBinding {
        if let Some(frame) = frame {
            if let Some(descriptor_set) = self
                .real_descriptor_sets
                .get(frame.swapchain_index as usize)
                .copied()
            {
                return PrivateLayerDepthBinding {
                    descriptor_set,
                    real_depth_bound: true,
                };
            }
        }
        PrivateLayerDepthBinding {
            descriptor_set: self.fallback_descriptor_set,
            real_depth_bound: false,
        }
    }

    unsafe fn transition_fallback_for_sampling(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
    ) {
        if self.fallback_ready {
            return;
        }
        let barrier = [vk::ImageMemoryBarrier::default()
            .image(self.fallback.image)
            .subresource_range(depth_subresource_range())
            .old_layout(vk::ImageLayout::UNDEFINED)
            .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
            .src_access_mask(vk::AccessFlags::empty())
            .dst_access_mask(vk::AccessFlags::SHADER_READ)];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::TOP_OF_PIPE,
            vk::PipelineStageFlags::FRAGMENT_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &barrier,
        );
        self.fallback_ready = true;
    }

    unsafe fn destroy(self, device: &ash::Device) {
        for view in self.real_image_views {
            device.destroy_image_view(view, None);
        }
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        self.fallback.destroy(device);
        device.destroy_sampler(self.sampler, None);
    }
}

struct PrivateLayerImage {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    framebuffer: vk::Framebuffer,
}

impl PrivateLayerImage {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        format: vk::Format,
        render_pass: vk::RenderPass,
        label: &str,
    ) -> Result<Self, String> {
        let image = device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(format)
                    .extent(vk::Extent3D {
                        width: PRIVATE_GUIDE_WIDTH,
                        height: PRIVATE_GUIDE_HEIGHT,
                        depth: 1,
                    })
                    .mip_levels(1)
                    .array_layers(1)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::SAMPLED)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .initial_layout(vk::ImageLayout::UNDEFINED),
                None,
            )
            .map_err(|error| format!("create {label} image: {error}"))?;
        let requirements = device.get_image_memory_requirements(image);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        ) {
            Ok(index) => index,
            Err(error) => {
                device.destroy_image(image, None);
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
                device.destroy_image(image, None);
                return Err(format!("allocate {label} image memory: {error}"));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("bind {label} image memory: {error}"));
        }
        let view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(format)
                .subresource_range(color_subresource_range()),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!("create {label} image view: {error}"));
            }
        };
        let framebuffer = match device.create_framebuffer(
            &vk::FramebufferCreateInfo::default()
                .render_pass(render_pass)
                .attachments(&[view])
                .width(PRIVATE_GUIDE_WIDTH)
                .height(PRIVATE_GUIDE_HEIGHT)
                .layers(1),
            None,
        ) {
            Ok(framebuffer) => framebuffer,
            Err(error) => {
                device.destroy_image_view(view, None);
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!("create {label} framebuffer: {error}"));
            }
        };

        Ok(Self {
            image,
            memory,
            view,
            framebuffer,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_framebuffer(self.framebuffer, None);
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

struct PrivateLayerDepthFallbackImage {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
}

impl PrivateLayerDepthFallbackImage {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
    ) -> Result<Self, String> {
        let image = device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(META_ENVIRONMENT_DEPTH_FORMAT)
                    .extent(vk::Extent3D {
                        width: 1,
                        height: 1,
                        depth: 1,
                    })
                    .mip_levels(1)
                    .array_layers(META_ENVIRONMENT_DEPTH_LAYER_COUNT)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(vk::ImageUsageFlags::SAMPLED)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .initial_layout(vk::ImageLayout::UNDEFINED),
                None,
            )
            .map_err(|error| format!("create private layer fallback depth image: {error}"))?;
        let requirements = device.get_image_memory_requirements(image);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        ) {
            Ok(index) => index,
            Err(error) => {
                device.destroy_image(image, None);
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
                device.destroy_image(image, None);
                return Err(format!(
                    "allocate private layer fallback depth image memory: {error}"
                ));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!(
                "bind private layer fallback depth image memory: {error}"
            ));
        }
        let view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D_ARRAY)
                .format(META_ENVIRONMENT_DEPTH_FORMAT)
                .subresource_range(depth_subresource_range()),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!(
                    "create private layer fallback depth image view: {error}"
                ));
            }
        };
        Ok(Self {
            image,
            memory,
            view,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

#[repr(C)]
struct PrivateLayerGuidePush {
    params0: [f32; 4],
    effect: [f32; 4],
    cycle: [f32; 4],
}

#[repr(C)]
struct PrivateLayerProjectionPush {
    target_rect: [f32; 4],
    params0: [f32; 4],
    effect: [f32; 4],
    cycle: [f32; 4],
    border_blend: [f32; 4],
    depth: [f32; 4],
    depth_aux: [f32; 4],
    depth_uv_transform: [f32; 4],
}

unsafe fn begin_private_pass(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
) {
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: [0.0, 0.0, 0.0, 1.0],
        },
    }];
    device.cmd_begin_render_pass(
        cmd,
        &vk::RenderPassBeginInfo::default()
            .render_pass(render_pass)
            .framebuffer(framebuffer)
            .render_area(vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent: private_guide_extent(),
            })
            .clear_values(&clear_values),
        vk::SubpassContents::INLINE,
    );
}

unsafe fn transition_private_image_for_sampling(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    image: vk::Image,
) {
    let barrier = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(color_subresource_range())
        .old_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
        .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
        .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)];
    device.cmd_pipeline_barrier(
        cmd,
        vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
        vk::PipelineStageFlags::FRAGMENT_SHADER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &barrier,
    );
}

unsafe fn create_private_render_pass(
    device: &ash::Device,
    color_format: vk::Format,
) -> Result<vk::RenderPass, String> {
    let color_attachment = vk::AttachmentDescription {
        format: color_format,
        samples: vk::SampleCountFlags::TYPE_1,
        load_op: vk::AttachmentLoadOp::CLEAR,
        store_op: vk::AttachmentStoreOp::STORE,
        initial_layout: vk::ImageLayout::UNDEFINED,
        final_layout: vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL,
        ..Default::default()
    };
    let color_refs = [vk::AttachmentReference {
        attachment: 0,
        layout: vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL,
    }];
    let subpasses = [vk::SubpassDescription::default()
        .color_attachments(&color_refs)
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)];
    let dependencies = [
        vk::SubpassDependency {
            src_subpass: vk::SUBPASS_EXTERNAL,
            dst_subpass: 0,
            src_stage_mask: vk::PipelineStageFlags::FRAGMENT_SHADER,
            dst_stage_mask: vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
            src_access_mask: vk::AccessFlags::SHADER_READ,
            dst_access_mask: vk::AccessFlags::COLOR_ATTACHMENT_WRITE,
            ..Default::default()
        },
        vk::SubpassDependency {
            src_subpass: 0,
            dst_subpass: vk::SUBPASS_EXTERNAL,
            src_stage_mask: vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
            dst_stage_mask: vk::PipelineStageFlags::FRAGMENT_SHADER,
            src_access_mask: vk::AccessFlags::COLOR_ATTACHMENT_WRITE,
            dst_access_mask: vk::AccessFlags::SHADER_READ,
            ..Default::default()
        },
    ];
    device
        .create_render_pass(
            &vk::RenderPassCreateInfo::default()
                .attachments(&[color_attachment])
                .subpasses(&subpasses)
                .dependencies(&dependencies),
            None,
        )
        .map_err(|error| format!("create private layer render pass: {error}"))
}

fn create_guide_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let bindings = [4_u32, 5, 6, 7, 8].map(|binding| {
        vk::DescriptorSetLayoutBinding::default()
            .binding(binding)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(1)
            .stage_flags(vk::ShaderStageFlags::FRAGMENT)
    });
    unsafe {
        device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|error| format!("create private layer guide descriptor layout: {error}"))
    }
}

fn create_depth_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let bindings = [vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)];
    unsafe {
        device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|error| format!("create private layer depth descriptor layout: {error}"))
    }
}

fn create_descriptor_pool(device: &ash::Device) -> Result<vk::DescriptorPool, String> {
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count((PRIVATE_EYE_COUNT * PRIVATE_GUIDE_TARGET_COUNT) as u32)];
    unsafe {
        device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .pool_sizes(&pool_sizes)
                    .max_sets(PRIVATE_EYE_COUNT as u32),
                None,
            )
            .map_err(|error| format!("create private layer descriptor pool: {error}"))
    }
}

fn create_depth_descriptor_pool(
    device: &ash::Device,
    descriptor_count: u32,
) -> Result<vk::DescriptorPool, String> {
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(descriptor_count)];
    unsafe {
        device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .pool_sizes(&pool_sizes)
                    .max_sets(descriptor_count),
                None,
            )
            .map_err(|error| format!("create private layer depth descriptor pool: {error}"))
    }
}

unsafe fn allocate_guide_descriptor_set(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [descriptor_set_layout];
    device
        .allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&set_layouts),
        )
        .map(|mut sets| sets.remove(0))
        .map_err(|error| format!("allocate private layer guide descriptor set: {error}"))
}

unsafe fn write_guide_descriptor_set(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    sampler: vk::Sampler,
    targets: &[PrivateLayerImage],
) {
    for (index, target) in targets.iter().enumerate() {
        let image_info = [vk::DescriptorImageInfo::default()
            .sampler(sampler)
            .image_view(target.view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
        let writes = [vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(4 + index as u32)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&image_info)];
        device.update_descriptor_sets(&writes, &[]);
    }
}

unsafe fn write_depth_descriptor_set(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    sampler: vk::Sampler,
    image_view: vk::ImageView,
) {
    let image_info = [vk::DescriptorImageInfo::default()
        .sampler(sampler)
        .image_view(image_view)
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let writes = [vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .image_info(&image_info)];
    device.update_descriptor_sets(&writes, &[]);
}

unsafe fn create_pipeline_layout<T>(
    device: &ash::Device,
    set_layouts: &[vk::DescriptorSetLayout],
) -> Result<vk::PipelineLayout, vk::Result> {
    let push_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(mem::size_of::<T>() as u32)];
    device.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default()
            .set_layouts(set_layouts)
            .push_constant_ranges(&push_ranges),
        None,
    )
}

unsafe fn create_graphics_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
    vertex_spirv: &[u8],
    fragment_spirv: &[u8],
    blend: bool,
    label: &str,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(vertex_spirv)?;
    let fragment_words = spirv_words(fragment_spirv)?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create {label} vertex shader module: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!("create {label} fragment shader module: {error}"));
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
        .blend_enable(blend)
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
        .map_err(|(_, error)| format!("create {label} graphics pipeline: {error}"))
}

fn private_guide_extent() -> vk::Extent2D {
    vk::Extent2D {
        width: PRIVATE_GUIDE_WIDTH,
        height: PRIVATE_GUIDE_HEIGHT,
    }
}

fn color_subresource_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: 1,
    }
}

fn depth_subresource_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::DEPTH,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: META_ENVIRONMENT_DEPTH_LAYER_COUNT,
    }
}

fn target_rect_to_scissor(extent: vk::Extent2D, rect: TargetRect) -> vk::Rect2D {
    let (x, width) = normalized_interval_to_pixels(extent.width, rect.x, rect.x + rect.width);
    let (y, height) = normalized_interval_to_pixels(extent.height, rect.y, rect.y + rect.height);
    vk::Rect2D {
        offset: vk::Offset2D { x, y },
        extent: vk::Extent2D { width, height },
    }
}

fn normalized_interval_to_pixels(size: u32, start: f32, end: f32) -> (i32, u32) {
    let size = size.max(1);
    let max = size as f32;
    let start = (max * start).round().clamp(0.0, max - 1.0);
    let end = (max * end).round().clamp(start + 1.0, max);
    (start as i32, (end - start).round().max(1.0) as u32)
}

unsafe fn push_fragment_constants<T>(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    pipeline_layout: vk::PipelineLayout,
    value: &T,
) {
    device.cmd_push_constants(
        cmd,
        pipeline_layout,
        vk::ShaderStageFlags::FRAGMENT,
        0,
        as_bytes(value),
    );
}

fn as_bytes<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) }
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
        "no Vulkan memory type supports {required:?} for private layer graph"
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

fn elapsed_seconds_for_frame(frame_count: u64) -> f32 {
    frame_count as f32 / ASSUMED_DISPLAY_HZ
}

fn active_layer_for_frame(frame_count: u64, settings: NativePrivateLayerSettings) -> u32 {
    if settings.layer_override >= 0.0 {
        return (settings.layer_override.round() as u32).min(PRIVATE_LAYER_COUNT - 1);
    }
    let elapsed_seconds = elapsed_seconds_for_frame(frame_count);
    let layer_seconds = settings.layer_seconds.max(0.001);
    ((elapsed_seconds / layer_seconds).floor() as u32) % PRIVATE_LAYER_COUNT
}

unsafe fn destroy_descriptor_scaffold(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    guide_descriptor_set_layout: vk::DescriptorSetLayout,
    depth_descriptor_set_layout: vk::DescriptorSetLayout,
    sampler: vk::Sampler,
    render_pass: vk::RenderPass,
) {
    device.destroy_descriptor_pool(descriptor_pool, None);
    device.destroy_descriptor_set_layout(depth_descriptor_set_layout, None);
    device.destroy_descriptor_set_layout(guide_descriptor_set_layout, None);
    device.destroy_sampler(sampler, None);
    device.destroy_render_pass(render_pass, None);
}

unsafe fn destroy_layout_scaffold(
    device: &ash::Device,
    projection_pipeline_layout: vk::PipelineLayout,
    guide_pipeline_layout: vk::PipelineLayout,
    descriptor_pool: vk::DescriptorPool,
    guide_descriptor_set_layout: vk::DescriptorSetLayout,
    depth_descriptor_set_layout: vk::DescriptorSetLayout,
    sampler: vk::Sampler,
    render_pass: vk::RenderPass,
) {
    device.destroy_pipeline_layout(projection_pipeline_layout, None);
    device.destroy_pipeline_layout(guide_pipeline_layout, None);
    destroy_descriptor_scaffold(
        device,
        descriptor_pool,
        guide_descriptor_set_layout,
        depth_descriptor_set_layout,
        sampler,
        render_pass,
    );
}
