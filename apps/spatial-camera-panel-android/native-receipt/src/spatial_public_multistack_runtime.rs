#![cfg_attr(not(target_os = "android"), allow(dead_code))]

#[cfg(target_os = "android")]
use std::ffi::CStr;
use std::ffi::CString;
use std::mem;
#[cfg(target_os = "android")]
use std::os::raw::{c_char, c_int};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

use ash::vk::{self, Handle};

pub(crate) const SPATIAL_PUBLIC_GUIDE_TARGET_COUNT: usize = 5;
pub(crate) const SPATIAL_PUBLIC_GUIDE_TARGET_FORMAT: vk::Format = vk::Format::R8G8B8A8_UNORM;
const SPATIAL_PUBLIC_DEPTH_FALLBACK_FORMAT: vk::Format = vk::Format::D16_UNORM;
const SPATIAL_PUBLIC_MAX_DEPTH_DESCRIPTOR_SETS: u32 = 9;
const SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_CONFIGURED_NEAR_M: f32 = 0.3;
const SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_CONFIGURED_FAR_M: f32 = 4.0;
const SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_FLAGS: f32 = 8.0;
const SPATIAL_PUBLIC_DEPTH_FLAG_INFINITE_FAR: u32 = 1;
const SPATIAL_PUBLIC_PACKED_EYE_COUNT: usize = 2;
const SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_DEFAULT: f32 = -1.0;
const SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_MAX: f32 = 8.0;
const SPATIAL_PUBLIC_META_PASSTHROUGH_EDGE_WINDOW_LAYER: f32 = 7.0;
const SPATIAL_PUBLIC_RAW_CUSTOM_PROJECTION_LAYER: f32 = 8.0;
const SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_PROPERTY: &str =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.projection_layer_override";
const SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_PROPERTY: &str =
    "debug.rustyquest.spatial.camera_hwb_projection_probe.depth.layer_policy";
const SPATIAL_PUBLIC_DEPTH_LAYER_COMPARE_SENTINEL: f32 = 2.0;
const SPATIAL_PUBLIC_DEPTH_ALIGNMENT_OFFSET_MIN: f32 = -0.25;
const SPATIAL_PUBLIC_DEPTH_ALIGNMENT_OFFSET_MAX: f32 = 0.25;
const SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_MIN: f32 = 0.25;
const SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_MAX: f32 = 3.0;
const SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_DEFAULT: f32 = 1.0;
static SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_BITS: AtomicU32 =
    AtomicU32::new(SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_DEFAULT.to_bits());
static SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_LIVE_SET: AtomicBool =
    AtomicBool::new(false);
static SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_BITS: AtomicU32 =
    AtomicU32::new(SpatialPublicDepthLayerPolicy::EyeIndex as u32);
static SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_LIVE_SET: AtomicBool = AtomicBool::new(false);
static SPATIAL_PUBLIC_DEPTH_ALIGNMENT_LEFT_X_BITS: AtomicU32 = AtomicU32::new(0.0f32.to_bits());
static SPATIAL_PUBLIC_DEPTH_ALIGNMENT_LEFT_Y_BITS: AtomicU32 = AtomicU32::new(0.0f32.to_bits());
static SPATIAL_PUBLIC_DEPTH_ALIGNMENT_RIGHT_X_BITS: AtomicU32 = AtomicU32::new(0.0f32.to_bits());
static SPATIAL_PUBLIC_DEPTH_ALIGNMENT_RIGHT_Y_BITS: AtomicU32 = AtomicU32::new(0.0f32.to_bits());
static SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_BITS: AtomicU32 =
    AtomicU32::new(SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_DEFAULT.to_bits());

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum SpatialPublicDepthLayerPolicy {
    MonoLayer0 = 0,
    MonoLayer1 = 1,
    EyeIndex = 2,
    Compare = 3,
}

impl SpatialPublicDepthLayerPolicy {
    fn from_code(value: u32) -> Self {
        match value {
            0 => Self::MonoLayer0,
            1 => Self::MonoLayer1,
            3 => Self::Compare,
            _ => Self::EyeIndex,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::MonoLayer0 => "mono-layer0",
            Self::MonoLayer1 => "mono-layer1",
            Self::EyeIndex => "eye-index",
            Self::Compare => "compare",
        }
    }

    pub(crate) fn compare_mode_token(self) -> &'static str {
        if self == Self::Compare {
            "visual-shader"
        } else {
            "off"
        }
    }

    fn source_layer_for_eye(self, eye_index: usize) -> f32 {
        match self {
            Self::MonoLayer0 => 0.0,
            Self::MonoLayer1 => 1.0,
            Self::EyeIndex => eye_index.min((SPATIAL_PUBLIC_PACKED_EYE_COUNT - 1) as usize) as f32,
            Self::Compare => SPATIAL_PUBLIC_DEPTH_LAYER_COMPARE_SENTINEL,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct SpatialPublicDepthAlignment {
    pub(crate) left_offset_uv: [f32; 2],
    pub(crate) right_offset_uv: [f32; 2],
    pub(crate) sample_scale: f32,
}

impl SpatialPublicDepthAlignment {
    fn depth_uv_transform_for_eye(self, eye_index: usize) -> [f32; 4] {
        let offset = if eye_index == 0 {
            self.left_offset_uv
        } else {
            self.right_offset_uv
        };
        [self.sample_scale, offset[0], self.sample_scale, offset[1]]
    }
}

#[cfg(target_os = "android")]
extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
}

include!(concat!(
    env!("OUT_DIR"),
    "/spatial_public_multistack_build.rs"
));

const OPAQUE_GUIDE_PASS_SPIRV: [&[u8]; 6] = [
    include_bytes!(concat!(
        env!("OUT_DIR"),
        "/spatial_opaque_guide_pass_0.frag.spv"
    )),
    include_bytes!(concat!(
        env!("OUT_DIR"),
        "/spatial_opaque_guide_pass_1.frag.spv"
    )),
    include_bytes!(concat!(
        env!("OUT_DIR"),
        "/spatial_opaque_guide_pass_2.frag.spv"
    )),
    include_bytes!(concat!(
        env!("OUT_DIR"),
        "/spatial_opaque_guide_pass_3.frag.spv"
    )),
    include_bytes!(concat!(
        env!("OUT_DIR"),
        "/spatial_opaque_guide_pass_4.frag.spv"
    )),
    include_bytes!(concat!(
        env!("OUT_DIR"),
        "/spatial_opaque_guide_pass_5.frag.spv"
    )),
];

pub(crate) fn spatial_public_guide_target_extent() -> vk::Extent2D {
    vk::Extent2D {
        width: 384,
        height: 384,
    }
}

pub(crate) struct SpatialPublicGuideTargets {
    targets: Vec<SpatialPublicGuideTarget>,
    extent: vk::Extent2D,
    format: vk::Format,
    render_pass: vk::RenderPass,
    sampler: vk::Sampler,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    sample_descriptor_sets: Vec<vk::DescriptorSet>,
    opaque_guide_descriptor_set_layout: vk::DescriptorSetLayout,
    opaque_guide_descriptor_pool: vk::DescriptorPool,
    opaque_guide_descriptor_set: vk::DescriptorSet,
    opaque_guide_pipeline_layout: vk::PipelineLayout,
    opaque_guide_pipelines: Vec<vk::Pipeline>,
    depth_descriptor_set_layout: vk::DescriptorSetLayout,
    depth_resources: SpatialPublicDepthResources,
    opaque_projection_pipeline_layout: vk::PipelineLayout,
    opaque_projection_pipeline: Option<vk::Pipeline>,
    blur_pipeline_layout: vk::PipelineLayout,
    blur_pipeline: vk::Pipeline,
}

impl SpatialPublicGuideTargets {
    pub(crate) unsafe fn destroy(self, device: &ash::Device) {
        for pipeline in self.opaque_guide_pipelines {
            device.destroy_pipeline(pipeline, None);
        }
        if let Some(pipeline) = self.opaque_projection_pipeline {
            device.destroy_pipeline(pipeline, None);
        }
        device.destroy_pipeline_layout(self.opaque_projection_pipeline_layout, None);
        device.destroy_pipeline_layout(self.opaque_guide_pipeline_layout, None);
        device.destroy_pipeline(self.blur_pipeline, None);
        device.destroy_pipeline_layout(self.blur_pipeline_layout, None);
        for target in self.targets {
            target.destroy(device);
        }
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        device.destroy_descriptor_pool(self.opaque_guide_descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.opaque_guide_descriptor_set_layout, None);
        self.depth_resources.destroy(device);
        device.destroy_descriptor_set_layout(self.depth_descriptor_set_layout, None);
        device.destroy_sampler(self.sampler, None);
        device.destroy_render_pass(self.render_pass, None);
    }

    pub(crate) fn marker_fields(&self) -> String {
        let total_bytes = self
            .targets
            .iter()
            .map(|target| target.allocation_size)
            .sum::<vk::DeviceSize>();
        let memory_policy = if self.targets.iter().all(|target| {
            target
                .memory_flags
                .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
        }) {
            "device-local"
        } else {
            "supported"
        };
        format!(
            "publicMultiStackGuideTargetsAllocated=true publicMultiStackGuideTargetCount={} publicMultiStackGuideTargetExtent={}x{} publicMultiStackGuideTargetFormat={:?} publicMultiStackGuideTargetBytes={} publicMultiStackGuideTargetMemory={} publicMultiStackPackedStereoGuides=true publicMultiStackPassExecutionReady={} publicGuideBlurPipelineReady=true publicGuideBlurRecordFunctionReady=true publicGuideBlurRuntimeReady={} publicMultiStackOpaqueProjectionPipelineReady={} publicMultiStackOpaqueProjectionPayloadExecutionReady={} publicMultiStackOpaquePayloadExecutionReady={}",
            self.targets.len(),
            self.extent.width,
            self.extent.height,
            self.format,
            total_bytes,
            memory_policy,
            bool_marker(self.guide_pass_execution_available()),
            bool_marker(self.guide_pass_execution_available()),
            bool_marker(self.opaque_projection_pipeline.is_some()),
            bool_marker(self.projection_execution_available()),
            bool_marker(self.projection_execution_available()),
        )
        + &format!(
            " publicMultiStackGuidePassResourcesReady=true publicMultiStackGuideFramebuffers={} publicMultiStackGuideSampleDescriptorSets={} publicMultiStackGuideSampleDescriptorShape=single-combined-rgba-sampler publicMultiStackGuidePassSchedule={} publicMultiStackOpaqueGuideDescriptorReady=true publicMultiStackOpaqueGuideDescriptorBindings=4,5,6,7,8 publicMultiStackOpaqueGuideDescriptorSets={} publicMultiStackOpaqueGuidePipelinesReady={} publicMultiStackOpaqueGuidePipelines={} publicMultiStackOpaqueGuideShaderPassCount={} {} {} {} {}",
            self.targets.len(),
            self.sample_descriptor_sets.len(),
            public_guide_pass_schedule_marker(),
            if self.opaque_guide_descriptor_set == vk::DescriptorSet::null() {
                0
            } else {
                1
            },
            bool_marker(self.opaque_guide_pipelines.len() == OPAQUE_GUIDE_SHADER_PASS_COUNT),
            self.opaque_guide_pipelines.len(),
            OPAQUE_GUIDE_SHADER_PASS_COUNT,
            self.depth_resources.marker_fields(),
            spatial_environment_depth_marker_fields(),
            spatial_native_passthrough_marker_fields(),
            spatial_public_depth_layer_policy_marker_fields(),
        )
    }

    pub(crate) fn frame_marker_fields(
        &self,
        projected_by_public_stack: bool,
        elapsed_seconds: f32,
    ) -> String {
        let left_projection_rect = packed_projection_target_rect(0);
        let right_projection_rect = packed_projection_target_rect(1);
        format!(
            "publicMultiStackProjectionApplied={} publicMultiStackLayerCycleEnabled=true publicMultiStackLayerCycleElapsedSeconds={:.3} publicMultiStackOpaqueProjectionTargetSpace=packed-stereo-surface-uv publicMultiStackOpaqueProjectionLeftTargetRect={} publicMultiStackOpaqueProjectionRightTargetRect={} publicMultiStackGuideTargetsAllocated=true publicMultiStackGuidePassResourcesReady=true publicMultiStackPassExecutionReady={} publicGuideBlurRuntimeReady={} publicGuideBlurPipelineReady=true publicGuideBlurRecordFunctionReady=true publicMultiStackOpaqueGuideDescriptorReady=true publicMultiStackOpaqueGuidePipelinesReady={} publicMultiStackOpaqueGuidePipelines={} publicMultiStackOpaqueGuideShaderPassCount={} publicMultiStackOpaqueProjectionPipelineReady={} publicMultiStackOpaqueProjectionPayloadExecutionReady={} publicMultiStackOpaquePayloadExecutionReady={} {} {} {} {} publicMultiStackGuideFramebuffers={} publicMultiStackGuideSampleDescriptorSets={}",
            bool_marker(projected_by_public_stack),
            elapsed_seconds.max(0.0),
            rect_marker(left_projection_rect),
            rect_marker(right_projection_rect),
            bool_marker(self.guide_pass_execution_available()),
            bool_marker(self.guide_pass_execution_available()),
            bool_marker(self.opaque_guide_pipelines.len() == OPAQUE_GUIDE_SHADER_PASS_COUNT),
            self.opaque_guide_pipelines.len(),
            OPAQUE_GUIDE_SHADER_PASS_COUNT,
            bool_marker(self.opaque_projection_pipeline.is_some()),
            bool_marker(self.projection_execution_available()),
            bool_marker(self.projection_execution_available()),
            self.depth_resources.marker_fields(),
            spatial_environment_depth_marker_fields(),
            spatial_native_passthrough_marker_fields(),
            spatial_public_depth_layer_policy_marker_fields(),
            self.targets.len(),
            self.sample_descriptor_sets.len(),
        )
    }

    pub(crate) fn compact_projection_evidence_marker_fields(
        &self,
        projected_by_public_stack: bool,
        elapsed_seconds: f32,
    ) -> String {
        let left_projection_rect = packed_projection_target_rect(0);
        let right_projection_rect = packed_projection_target_rect(1);
        let layer_override = opaque_projection_layer_override();
        let edge_window_selected = spatial_public_meta_passthrough_edge_window_selected();
        let raw_custom_projection_selected = spatial_public_raw_custom_projection_selected();
        let depth_alignment = current_spatial_public_depth_alignment();
        format!(
            "publicMultiStackProjectionApplied={} publicMultiStackLayerCycleEnabled=true publicMultiStackLayerCycleElapsedSeconds={:.3} publicMultiStackOpaqueProjectionLayerOverride={:.3} publicMultiStackOpaqueProjectionTargetSpace=packed-stereo-surface-uv publicMultiStackOpaqueProjectionLeftTargetRect={} publicMultiStackOpaqueProjectionRightTargetRect={} metaPassthroughEdgeWindowSelected={} rawCustomProjectionSelected={} rawCustomProjectionSource=camera2-hwb-direct-sample rawCustomProjectionVideoDecodePolicy=keep-active projectionAlphaCutoutActive={} projectionAlphaCutoutValue=0.000 projectionAlphaCutoutPreservesVideoDecode=true projectionAlphaCutoutTarget=custom-stereo-projection-rect {} {} {} {} publicMultiStackDepthAlignmentLeftOffsetUv={:.6},{:.6} publicMultiStackDepthAlignmentRightOffsetUv={:.6},{:.6} publicMultiStackDepthAlignmentSampleScale={:.4}",
            bool_marker(projected_by_public_stack),
            elapsed_seconds.max(0.0),
            layer_override,
            rect_marker(left_projection_rect),
            rect_marker(right_projection_rect),
            bool_marker(edge_window_selected),
            bool_marker(raw_custom_projection_selected),
            bool_marker(edge_window_selected && projected_by_public_stack),
            self.depth_resources.marker_fields(),
            spatial_environment_depth_marker_fields(),
            spatial_native_passthrough_marker_fields(),
            spatial_public_depth_layer_policy_marker_fields(),
            depth_alignment.left_offset_uv[0],
            depth_alignment.left_offset_uv[1],
            depth_alignment.right_offset_uv[0],
            depth_alignment.right_offset_uv[1],
            depth_alignment.sample_scale,
        )
    }

    fn guide_pass_execution_available(&self) -> bool {
        self.opaque_guide_pipelines.len() == OPAQUE_GUIDE_SHADER_PASS_COUNT
    }

    fn projection_execution_available(&self) -> bool {
        self.guide_pass_execution_available() && self.opaque_projection_pipeline.is_some()
    }

    pub(crate) unsafe fn record_spatial_public_guide_passes(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        camera_descriptor_set: vk::DescriptorSet,
        elapsed_seconds: f32,
    ) -> Result<bool, String> {
        if spatial_public_meta_passthrough_edge_window_selected()
            || spatial_public_raw_custom_projection_selected()
        {
            return Ok(false);
        }
        if !self.guide_pass_execution_available() {
            return Ok(false);
        }
        for step in SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE {
            match step.kind {
                SpatialPublicGuidePassKind::Opaque { pipeline_index } => {
                    self.record_opaque_guide_pass_for_stereo(
                        device,
                        command_buffer,
                        camera_descriptor_set,
                        pipeline_index,
                        step.output_target,
                        elapsed_seconds,
                    )?;
                }
                SpatialPublicGuidePassKind::PublicBlur { direction } => {
                    let source_target = step
                        .input_target
                        .ok_or_else(|| "public-blur-source-target-missing".to_string())?;
                    self.record_public_blur_pass_for_stereo(
                        device,
                        command_buffer,
                        source_target,
                        step.output_target,
                        direction,
                    )?;
                }
            }
        }
        Ok(true)
    }

    pub(crate) unsafe fn record_spatial_public_projection(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        render_pass: vk::RenderPass,
        framebuffer: vk::Framebuffer,
        extent: vk::Extent2D,
        camera_descriptor_set: vk::DescriptorSet,
        elapsed_seconds: f32,
    ) -> Result<bool, String> {
        if !self.projection_execution_available() {
            return Ok(false);
        }
        self.prepare_spatial_public_projection_sampling(device, command_buffer);
        begin_projection_pass(device, command_buffer, render_pass, framebuffer, extent);
        let projected = self.record_spatial_public_projection_in_open_render_pass(
            device,
            command_buffer,
            extent,
            camera_descriptor_set,
            elapsed_seconds,
        )?;
        device.cmd_end_render_pass(command_buffer);
        Ok(projected)
    }

    pub(crate) unsafe fn prepare_spatial_public_projection_sampling(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
    ) -> bool {
        if !self.projection_execution_available() {
            return false;
        }
        self.depth_resources
            .try_bind_real_depth_images(device, self.depth_descriptor_set_layout);
        if !self.depth_resources.current_binding().real_depth_bound {
            self.depth_resources
                .transition_fallback_for_sampling(device, command_buffer);
        }
        true
    }

    pub(crate) unsafe fn record_spatial_public_projection_in_open_render_pass(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        extent: vk::Extent2D,
        camera_descriptor_set: vk::DescriptorSet,
        elapsed_seconds: f32,
    ) -> Result<bool, String> {
        if !self.projection_execution_available() {
            return Ok(false);
        }
        for eye_index in 0..SPATIAL_PUBLIC_PACKED_EYE_COUNT {
            let target_rect = packed_projection_target_rect(eye_index);
            set_packed_projection_target_view(device, command_buffer, extent, target_rect);
            let pipeline = self
                .opaque_projection_pipeline
                .ok_or_else(|| "opaque-projection-pipeline-missing".to_string())?;
            device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::GRAPHICS, pipeline);
            device.cmd_bind_descriptor_sets(
                command_buffer,
                vk::PipelineBindPoint::GRAPHICS,
                self.opaque_projection_pipeline_layout,
                0,
                &[
                    camera_descriptor_set,
                    self.opaque_guide_descriptor_set,
                    self.depth_resources.current_binding().descriptor_set,
                ],
                &[],
            );
            let depth_binding = self.depth_resources.current_binding();
            let push =
                OpaqueProjectionPush::for_packed_eye(eye_index, elapsed_seconds, depth_binding);
            push_fragment_constants(
                device,
                command_buffer,
                self.opaque_projection_pipeline_layout,
                &push,
            );
            device.cmd_draw(command_buffer, 3, 1, 0, 0);
        }
        Ok(true)
    }

    #[allow(dead_code)]
    pub(crate) unsafe fn record_public_blur_pass(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        source_target_index: usize,
        destination_target_index: usize,
        direction: PublicGuideBlurDirection,
    ) -> Result<(), String> {
        if source_target_index == destination_target_index {
            return Err("public-guide-blur-source-destination-alias".to_string());
        }
        let source_descriptor_set = *self
            .sample_descriptor_sets
            .get(source_target_index)
            .ok_or_else(|| "public-guide-blur-source-index-out-of-range".to_string())?;
        let destination = self
            .targets
            .get(destination_target_index)
            .ok_or_else(|| "public-guide-blur-destination-index-out-of-range".to_string())?;
        begin_guide_pass(
            device,
            command_buffer,
            self.render_pass,
            destination.framebuffer,
            self.extent,
        );
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: self.extent.width as f32,
            height: self.extent.height as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = [vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent: self.extent,
        }];
        device.cmd_set_viewport(command_buffer, 0, &viewport);
        device.cmd_set_scissor(command_buffer, 0, &scissor);
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.blur_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.blur_pipeline_layout,
            0,
            &[source_descriptor_set],
            &[],
        );
        let push = PublicGuideBlurPush {
            step_and_scale: direction.step_and_scale(self.extent),
            source_rect: [0.0, 0.0, 1.0, 1.0],
        };
        push_fragment_constants(device, command_buffer, self.blur_pipeline_layout, &push);
        device.cmd_draw(command_buffer, 3, 1, 0, 0);
        device.cmd_end_render_pass(command_buffer);
        transition_guide_image_for_sampling(device, command_buffer, destination.image);
        Ok(())
    }

    unsafe fn record_public_blur_pass_for_stereo(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        source_target_index: usize,
        destination_target_index: usize,
        direction: PublicGuideBlurDirection,
    ) -> Result<(), String> {
        if source_target_index == destination_target_index {
            return Err("public-guide-blur-source-destination-alias".to_string());
        }
        let source_descriptor_set = *self
            .sample_descriptor_sets
            .get(source_target_index)
            .ok_or_else(|| "public-guide-blur-source-index-out-of-range".to_string())?;
        let destination = self
            .targets
            .get(destination_target_index)
            .ok_or_else(|| "public-guide-blur-destination-index-out-of-range".to_string())?;
        begin_guide_pass(
            device,
            command_buffer,
            self.render_pass,
            destination.framebuffer,
            self.extent,
        );
        for eye_index in 0..SPATIAL_PUBLIC_PACKED_EYE_COUNT {
            set_packed_eye_view(device, command_buffer, self.extent, eye_index);
            device.cmd_bind_pipeline(
                command_buffer,
                vk::PipelineBindPoint::GRAPHICS,
                self.blur_pipeline,
            );
            device.cmd_bind_descriptor_sets(
                command_buffer,
                vk::PipelineBindPoint::GRAPHICS,
                self.blur_pipeline_layout,
                0,
                &[source_descriptor_set],
                &[],
            );
            let push = PublicGuideBlurPush {
                step_and_scale: direction.step_and_scale(self.extent),
                source_rect: packed_eye_source_rect(eye_index),
            };
            push_fragment_constants(device, command_buffer, self.blur_pipeline_layout, &push);
            device.cmd_draw(command_buffer, 3, 1, 0, 0);
        }
        device.cmd_end_render_pass(command_buffer);
        transition_guide_image_for_sampling(device, command_buffer, destination.image);
        Ok(())
    }

    unsafe fn record_opaque_guide_pass_for_stereo(
        &self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        camera_descriptor_set: vk::DescriptorSet,
        pipeline_index: usize,
        destination_target_index: usize,
        elapsed_seconds: f32,
    ) -> Result<(), String> {
        let pipeline = *self
            .opaque_guide_pipelines
            .get(pipeline_index)
            .ok_or_else(|| "opaque-guide-pipeline-index-out-of-range".to_string())?;
        let destination = self
            .targets
            .get(destination_target_index)
            .ok_or_else(|| "opaque-guide-destination-index-out-of-range".to_string())?;
        begin_guide_pass(
            device,
            command_buffer,
            self.render_pass,
            destination.framebuffer,
            self.extent,
        );
        for eye_index in 0..SPATIAL_PUBLIC_PACKED_EYE_COUNT {
            set_packed_eye_view(device, command_buffer, self.extent, eye_index);
            device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::GRAPHICS, pipeline);
            device.cmd_bind_descriptor_sets(
                command_buffer,
                vk::PipelineBindPoint::GRAPHICS,
                self.opaque_guide_pipeline_layout,
                0,
                &[camera_descriptor_set, self.opaque_guide_descriptor_set],
                &[],
            );
            let push = OpaqueGuidePush {
                params0: [
                    eye_index as f32,
                    0.0,
                    self.extent.width as f32,
                    self.extent.height as f32,
                ],
                effect: [1.0, 1.0, 0.0, 1.0],
                cycle: [elapsed_seconds.max(0.0) / 5.0, 0.0, 0.0, 1.0],
            };
            push_fragment_constants(
                device,
                command_buffer,
                self.opaque_guide_pipeline_layout,
                &push,
            );
            device.cmd_draw(command_buffer, 3, 1, 0, 0);
        }
        device.cmd_end_render_pass(command_buffer);
        transition_guide_image_for_sampling(device, command_buffer, destination.image);
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum PublicGuideBlurDirection {
    Horizontal,
    Vertical,
}

impl PublicGuideBlurDirection {
    fn step_and_scale(self, extent: vk::Extent2D) -> [f32; 4] {
        let width = extent.width.max(1) as f32;
        let height = extent.height.max(1) as f32;
        match self {
            Self::Horizontal => [1.0 / width, 0.0, 1.0, 0.0],
            Self::Vertical => [0.0, 1.0 / height, 1.0, 0.0],
        }
    }
}

#[repr(C)]
struct PublicGuideBlurPush {
    step_and_scale: [f32; 4],
    source_rect: [f32; 4],
}

#[repr(C)]
struct OpaqueGuidePush {
    params0: [f32; 4],
    effect: [f32; 4],
    cycle: [f32; 4],
}

#[repr(C)]
struct OpaqueProjectionPush {
    target_rect: [f32; 4],
    params0: [f32; 4],
    effect: [f32; 4],
    cycle: [f32; 4],
    border_blend: [f32; 4],
    depth: [f32; 4],
    depth_aux: [f32; 4],
    depth_uv_transform: [f32; 4],
}

impl OpaqueProjectionPush {
    fn for_packed_eye(
        eye_index: usize,
        elapsed_seconds: f32,
        depth_binding: SpatialPublicDepthBinding,
    ) -> Self {
        let layer_override = opaque_projection_layer_override();
        let depth_alignment = current_spatial_public_depth_alignment();
        let depth_layer_policy = current_spatial_public_depth_layer_policy();
        let depth_near_z = depth_binding.near_z.max(0.001);
        let depth_far_z = if depth_binding.far_z.is_finite() && depth_binding.far_z > depth_near_z {
            depth_binding.far_z
        } else {
            SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_CONFIGURED_FAR_M.max(depth_near_z + 0.001)
        };
        let depth_flags = if depth_binding.far_z.is_finite() && depth_binding.far_z > depth_near_z {
            0
        } else {
            SPATIAL_PUBLIC_DEPTH_FLAG_INFINITE_FAR
        };
        Self {
            target_rect: packed_projection_target_rect(eye_index),
            params0: [
                eye_index as f32,
                0.0,
                elapsed_seconds.max(0.0),
                layer_override,
            ],
            effect: OPAQUE_PROJECTION_EFFECT,
            cycle: [0.0, 5.0, 1.0, 1.0],
            border_blend: [0.0, 0.0, 0.0, 0.0],
            depth: [
                if depth_binding.real_depth_bound {
                    1.0
                } else {
                    0.0
                },
                depth_near_z,
                depth_far_z,
                depth_layer_policy.source_layer_for_eye(eye_index),
            ],
            depth_aux: [
                depth_flags as f32,
                SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_FLAGS,
                SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_CONFIGURED_NEAR_M,
                SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_CONFIGURED_FAR_M,
            ],
            depth_uv_transform: depth_alignment.depth_uv_transform_for_eye(eye_index),
        }
    }
}

pub(crate) fn update_spatial_public_opaque_projection_layer_override(layer_override: f32) -> f32 {
    let applied = clamp_projection_layer_override(layer_override);
    SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_BITS
        .store(applied.to_bits(), Ordering::Release);
    SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_LIVE_SET.store(true, Ordering::Release);
    applied
}

pub(crate) fn current_spatial_public_opaque_projection_layer_override() -> f32 {
    clamp_projection_layer_override(f32::from_bits(
        SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_BITS.load(Ordering::Acquire),
    ))
}

pub(crate) fn spatial_public_meta_passthrough_edge_window_selected() -> bool {
    is_meta_passthrough_edge_window_layer(opaque_projection_layer_override())
}

pub(crate) fn spatial_public_raw_custom_projection_selected() -> bool {
    is_raw_custom_projection_layer(opaque_projection_layer_override())
}

fn is_meta_passthrough_edge_window_layer(layer_override: f32) -> bool {
    (layer_override - SPATIAL_PUBLIC_META_PASSTHROUGH_EDGE_WINDOW_LAYER).abs() < f32::EPSILON
}

fn is_raw_custom_projection_layer(layer_override: f32) -> bool {
    (layer_override - SPATIAL_PUBLIC_RAW_CUSTOM_PROJECTION_LAYER).abs() < f32::EPSILON
}

pub(crate) unsafe fn record_spatial_public_meta_passthrough_edge_window_cutout(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    extent: vk::Extent2D,
) -> bool {
    if !spatial_public_meta_passthrough_edge_window_selected() {
        return false;
    }
    let attachments = [vk::ClearAttachment::default()
        .aspect_mask(vk::ImageAspectFlags::COLOR)
        .color_attachment(0)
        .clear_value(vk::ClearValue {
            color: vk::ClearColorValue {
                float32: [0.0, 0.0, 0.0, 0.0],
            },
        })];
    let clear_rects = (0..SPATIAL_PUBLIC_PACKED_EYE_COUNT)
        .map(|eye_index| {
            vk::ClearRect::default()
                .rect(target_rect_to_scissor(
                    extent,
                    packed_projection_target_rect(eye_index),
                ))
                .base_array_layer(0)
                .layer_count(1)
        })
        .collect::<Vec<_>>();
    device.cmd_clear_attachments(command_buffer, &attachments, &clear_rects);
    true
}

pub(crate) fn update_spatial_public_depth_layer_policy(
    policy_code: u32,
) -> SpatialPublicDepthLayerPolicy {
    let applied = SpatialPublicDepthLayerPolicy::from_code(policy_code);
    SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_BITS.store(applied as u32, Ordering::Release);
    SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_LIVE_SET.store(true, Ordering::Release);
    applied
}

pub(crate) fn current_spatial_public_depth_layer_policy() -> SpatialPublicDepthLayerPolicy {
    let live_policy = SpatialPublicDepthLayerPolicy::from_code(
        SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_BITS.load(Ordering::Acquire),
    );
    if SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_LIVE_SET.load(Ordering::Acquire) {
        return live_policy;
    }
    #[cfg(target_os = "android")]
    {
        if let Some(value) = android_system_property(SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_PROPERTY)
            .and_then(|raw| parse_spatial_public_depth_layer_policy(&raw))
        {
            return value;
        }
    }
    live_policy
}

pub(crate) fn update_spatial_public_depth_alignment(
    left_offset_x: f32,
    left_offset_y: f32,
    right_offset_x: f32,
    right_offset_y: f32,
    sample_scale: f32,
) -> SpatialPublicDepthAlignment {
    let alignment = SpatialPublicDepthAlignment {
        left_offset_uv: [
            clamp_depth_alignment_offset(left_offset_x),
            clamp_depth_alignment_offset(left_offset_y),
        ],
        right_offset_uv: [
            clamp_depth_alignment_offset(right_offset_x),
            clamp_depth_alignment_offset(right_offset_y),
        ],
        sample_scale: clamp_depth_alignment_sample_scale(sample_scale),
    };
    SPATIAL_PUBLIC_DEPTH_ALIGNMENT_LEFT_X_BITS
        .store(alignment.left_offset_uv[0].to_bits(), Ordering::Release);
    SPATIAL_PUBLIC_DEPTH_ALIGNMENT_LEFT_Y_BITS
        .store(alignment.left_offset_uv[1].to_bits(), Ordering::Release);
    SPATIAL_PUBLIC_DEPTH_ALIGNMENT_RIGHT_X_BITS
        .store(alignment.right_offset_uv[0].to_bits(), Ordering::Release);
    SPATIAL_PUBLIC_DEPTH_ALIGNMENT_RIGHT_Y_BITS
        .store(alignment.right_offset_uv[1].to_bits(), Ordering::Release);
    SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_BITS
        .store(alignment.sample_scale.to_bits(), Ordering::Release);
    alignment
}

pub(crate) fn current_spatial_public_depth_alignment() -> SpatialPublicDepthAlignment {
    SpatialPublicDepthAlignment {
        left_offset_uv: [
            clamp_depth_alignment_offset(f32::from_bits(
                SPATIAL_PUBLIC_DEPTH_ALIGNMENT_LEFT_X_BITS.load(Ordering::Acquire),
            )),
            clamp_depth_alignment_offset(f32::from_bits(
                SPATIAL_PUBLIC_DEPTH_ALIGNMENT_LEFT_Y_BITS.load(Ordering::Acquire),
            )),
        ],
        right_offset_uv: [
            clamp_depth_alignment_offset(f32::from_bits(
                SPATIAL_PUBLIC_DEPTH_ALIGNMENT_RIGHT_X_BITS.load(Ordering::Acquire),
            )),
            clamp_depth_alignment_offset(f32::from_bits(
                SPATIAL_PUBLIC_DEPTH_ALIGNMENT_RIGHT_Y_BITS.load(Ordering::Acquire),
            )),
        ],
        sample_scale: clamp_depth_alignment_sample_scale(f32::from_bits(
            SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_BITS.load(Ordering::Acquire),
        )),
    }
}

fn spatial_public_depth_layer_policy_marker_fields() -> String {
    let policy = current_spatial_public_depth_layer_policy();
    format!(
        "publicMultiStackDepthLayerPolicy={} publicMultiStackDepthLayerPolicyProperty={} publicMultiStackDepthLayerCompareMode={} publicMultiStackDepthLayerCompareEvidence={}",
        policy.marker_token(),
        SPATIAL_PUBLIC_DEPTH_LAYER_POLICY_PROPERTY,
        policy.compare_mode_token(),
        if policy == SpatialPublicDepthLayerPolicy::Compare {
            "shader-samples-layer0-and-layer1-at-same-depth-uv"
        } else {
            "inactive"
        }
    )
}

fn opaque_projection_layer_override() -> f32 {
    let live_override = current_spatial_public_opaque_projection_layer_override();
    if SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_LIVE_SET.load(Ordering::Acquire) {
        return live_override;
    }
    #[cfg(target_os = "android")]
    {
        if let Some(value) =
            android_system_property(SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_PROPERTY)
                .and_then(|raw| parse_projection_layer_override(&raw))
        {
            return value;
        }
    }
    SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_DEFAULT
}

fn parse_spatial_public_depth_layer_policy(
    raw_value: &str,
) -> Option<SpatialPublicDepthLayerPolicy> {
    let token = raw_value.trim().to_ascii_lowercase().replace('_', "-");
    if token.is_empty() {
        return None;
    }
    match token.as_str() {
        "mono-layer0" | "mono-left" | "layer0" | "left" | "0" => {
            Some(SpatialPublicDepthLayerPolicy::MonoLayer0)
        }
        "mono-layer1" | "mono-right" | "layer1" | "right" | "1" => {
            Some(SpatialPublicDepthLayerPolicy::MonoLayer1)
        }
        "eye-index" | "per-eye" | "stereo" | "stereo-indexed" | "2" => {
            Some(SpatialPublicDepthLayerPolicy::EyeIndex)
        }
        "compare" | "layer-compare" | "compare-layers" | "depth-compare" | "l0-l1-compare"
        | "3" => Some(SpatialPublicDepthLayerPolicy::Compare),
        _ => None,
    }
}

fn parse_projection_layer_override(raw_value: &str) -> Option<f32> {
    let value = raw_value.trim().parse::<f32>().ok()?;
    if !value.is_finite() {
        return None;
    }
    Some(clamp_projection_layer_override(value))
}

fn clamp_projection_layer_override(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(
            SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_DEFAULT,
            SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_MAX,
        )
    } else {
        SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_DEFAULT
    }
}

fn clamp_depth_alignment_offset(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(
            SPATIAL_PUBLIC_DEPTH_ALIGNMENT_OFFSET_MIN,
            SPATIAL_PUBLIC_DEPTH_ALIGNMENT_OFFSET_MAX,
        )
    } else {
        0.0
    }
}

fn clamp_depth_alignment_sample_scale(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(
            SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_MIN,
            SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_MAX,
        )
    } else {
        SPATIAL_PUBLIC_DEPTH_ALIGNMENT_SAMPLE_SCALE_DEFAULT
    }
}

#[cfg(target_os = "android")]
fn android_system_property(name: &str) -> Option<String> {
    let name = CString::new(name).ok()?;
    let mut value = [0 as c_char; 92];
    let len = unsafe { __system_property_get(name.as_ptr(), value.as_mut_ptr()) };
    if len <= 0 {
        return None;
    }
    let raw = unsafe { CStr::from_ptr(value.as_ptr()) }
        .to_string_lossy()
        .trim()
        .to_string();
    if raw.is_empty() {
        None
    } else {
        Some(raw)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SpatialPublicGuidePassKind {
    Opaque { pipeline_index: usize },
    PublicBlur { direction: PublicGuideBlurDirection },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SpatialPublicGuidePassStep {
    input_target: Option<usize>,
    output_target: usize,
    kind: SpatialPublicGuidePassKind,
}

const SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE: [SpatialPublicGuidePassStep; 6] = [
    SpatialPublicGuidePassStep {
        input_target: None,
        output_target: 0,
        kind: SpatialPublicGuidePassKind::Opaque { pipeline_index: 0 },
    },
    SpatialPublicGuidePassStep {
        input_target: Some(0),
        output_target: 1,
        kind: SpatialPublicGuidePassKind::PublicBlur {
            direction: PublicGuideBlurDirection::Horizontal,
        },
    },
    SpatialPublicGuidePassStep {
        input_target: Some(1),
        output_target: 2,
        kind: SpatialPublicGuidePassKind::PublicBlur {
            direction: PublicGuideBlurDirection::Vertical,
        },
    },
    SpatialPublicGuidePassStep {
        input_target: Some(2),
        output_target: 3,
        kind: SpatialPublicGuidePassKind::Opaque { pipeline_index: 3 },
    },
    SpatialPublicGuidePassStep {
        input_target: Some(3),
        output_target: 1,
        kind: SpatialPublicGuidePassKind::PublicBlur {
            direction: PublicGuideBlurDirection::Horizontal,
        },
    },
    SpatialPublicGuidePassStep {
        input_target: Some(1),
        output_target: 4,
        kind: SpatialPublicGuidePassKind::PublicBlur {
            direction: PublicGuideBlurDirection::Vertical,
        },
    },
];

struct SpatialPublicGuideTarget {
    image: vk::Image,
    memory: vk::DeviceMemory,
    image_view: vk::ImageView,
    framebuffer: vk::Framebuffer,
    allocation_size: vk::DeviceSize,
    memory_flags: vk::MemoryPropertyFlags,
}

struct SpatialPublicDepthFallback {
    image: vk::Image,
    memory: vk::DeviceMemory,
    image_view: vk::ImageView,
}

#[derive(Clone, Copy)]
struct SpatialPublicDepthBinding {
    descriptor_set: vk::DescriptorSet,
    real_depth_bound: bool,
    near_z: f32,
    far_z: f32,
}

struct SpatialPublicEnvironmentDepthSnapshot {
    image_handles: Vec<u64>,
    swapchain_index: u32,
    width: u32,
    height: u32,
    near_z: f32,
    far_z: f32,
    acquired_frame_count: u64,
}

struct SpatialPublicDepthResources {
    descriptor_pool: vk::DescriptorPool,
    sampler: vk::Sampler,
    fallback: SpatialPublicDepthFallback,
    fallback_ready: bool,
    fallback_descriptor_set: vk::DescriptorSet,
    real_image_views: Vec<vk::ImageView>,
    real_descriptor_sets: Vec<vk::DescriptorSet>,
    real_image_handles: Vec<u64>,
}

impl SpatialPublicDepthResources {
    unsafe fn create(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        descriptor_set_layout: vk::DescriptorSetLayout,
    ) -> Result<Self, String> {
        let sampler = match create_depth_sampler(device) {
            Ok(sampler) => sampler,
            Err(error) => return Err(error),
        };
        let fallback = match SpatialPublicDepthFallback::create(device, memory_properties) {
            Ok(fallback) => fallback,
            Err(error) => {
                device.destroy_sampler(sampler, None);
                return Err(error);
            }
        };
        let descriptor_pool = match create_depth_descriptor_pool(device) {
            Ok(pool) => pool,
            Err(error) => {
                fallback.destroy(device);
                device.destroy_sampler(sampler, None);
                return Err(error);
            }
        };
        let fallback_descriptor_set =
            match allocate_depth_descriptor_set(device, descriptor_pool, descriptor_set_layout) {
                Ok(descriptor_set) => descriptor_set,
                Err(error) => {
                    device.destroy_descriptor_pool(descriptor_pool, None);
                    fallback.destroy(device);
                    device.destroy_sampler(sampler, None);
                    return Err(error);
                }
            };
        write_depth_descriptor_set(
            device,
            fallback_descriptor_set,
            sampler,
            fallback.image_view,
        );
        let mut resources = Self {
            descriptor_pool,
            sampler,
            fallback,
            fallback_ready: false,
            fallback_descriptor_set,
            real_image_views: Vec::new(),
            real_descriptor_sets: Vec::new(),
            real_image_handles: Vec::new(),
        };
        resources.try_bind_real_depth_images(device, descriptor_set_layout);
        Ok(resources)
    }

    unsafe fn try_bind_real_depth_images(
        &mut self,
        device: &ash::Device,
        descriptor_set_layout: vk::DescriptorSetLayout,
    ) {
        let Some(snapshot) = current_spatial_environment_depth_frame_snapshot() else {
            return;
        };
        if snapshot.image_handles.is_empty()
            || self.real_image_handles == snapshot.image_handles
            || !self.real_image_handles.is_empty()
        {
            return;
        }
        if snapshot.image_handles.len().saturating_add(1)
            > SPATIAL_PUBLIC_MAX_DEPTH_DESCRIPTOR_SETS as usize
        {
            return;
        }

        let mut image_views = Vec::with_capacity(snapshot.image_handles.len());
        for image_handle in snapshot.image_handles.iter().copied() {
            let image = vk::Image::from_raw(image_handle);
            match device.create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .image(image)
                    .view_type(vk::ImageViewType::TYPE_2D_ARRAY)
                    .format(SPATIAL_PUBLIC_DEPTH_FALLBACK_FORMAT)
                    .subresource_range(depth_subresource_range()),
                None,
            ) {
                Ok(view) => image_views.push(view),
                Err(_) => {
                    for view in image_views {
                        device.destroy_image_view(view, None);
                    }
                    return;
                }
            }
        }

        let set_layouts = vec![descriptor_set_layout; image_views.len()];
        let descriptor_sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(self.descriptor_pool)
                .set_layouts(&set_layouts),
        ) {
            Ok(sets) => sets,
            Err(_) => {
                for view in image_views {
                    device.destroy_image_view(view, None);
                }
                return;
            }
        };

        for (descriptor_set, image_view) in descriptor_sets.iter().copied().zip(image_views.iter())
        {
            write_depth_descriptor_set(device, descriptor_set, self.sampler, *image_view);
        }
        self.real_image_views = image_views;
        self.real_descriptor_sets = descriptor_sets;
        self.real_image_handles = snapshot.image_handles;
    }

    fn current_binding(&self) -> SpatialPublicDepthBinding {
        if let Some(snapshot) = current_spatial_environment_depth_frame_snapshot() {
            if let Some(descriptor_set) = self
                .real_descriptor_sets
                .get(snapshot.swapchain_index as usize)
                .copied()
            {
                return SpatialPublicDepthBinding {
                    descriptor_set,
                    real_depth_bound: true,
                    near_z: snapshot.near_z,
                    far_z: snapshot.far_z,
                };
            }
        }
        SpatialPublicDepthBinding {
            descriptor_set: self.fallback_descriptor_set,
            real_depth_bound: false,
            near_z: 0.001,
            far_z: SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_CONFIGURED_FAR_M,
        }
    }

    fn marker_fields(&self) -> String {
        let current = self.current_binding();
        let snapshot = current_spatial_environment_depth_frame_snapshot();
        let (swapchain_index, image_size, acquired_frame_count) = if let Some(snapshot) = snapshot {
            (
                snapshot.swapchain_index.to_string(),
                format!("{}x{}", snapshot.width, snapshot.height),
                snapshot.acquired_frame_count,
            )
        } else {
            ("none".to_string(), "0x0".to_string(), 0)
        };
        format!(
            "publicMultiStackDepthRealDescriptorBound={} publicMultiStackDepthCurrentDescriptorSource={} publicMultiStackDepthDescriptorAcquiredFrameCount={} publicMultiStackDepthCurrentSwapchainIndex={} publicMultiStackDepthCurrentImageSize={} publicMultiStackDepthDescriptorShape=single-combined-d16-array-sampler publicMultiStackDepthFallbackDescriptorBound={} publicMultiStackDepthFallbackReady={} publicMultiStackDepthFallbackFormat={:?} publicMultiStackDepthRealDescriptorSets={} publicMultiStackDepthRealImageViews={}",
            bool_marker(current.real_depth_bound),
            if current.real_depth_bound {
                "xr-meta-environment-depth"
            } else {
                "spatial-fallback-depth-descriptor"
            },
            acquired_frame_count,
            swapchain_index,
            image_size,
            bool_marker(self.fallback_descriptor_set != vk::DescriptorSet::null()),
            bool_marker(self.fallback_ready),
            SPATIAL_PUBLIC_DEPTH_FALLBACK_FORMAT,
            self.real_descriptor_sets.len(),
            self.real_image_views.len(),
        )
    }

    unsafe fn transition_fallback_for_sampling(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
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
            command_buffer,
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
        for image_view in self.real_image_views {
            device.destroy_image_view(image_view, None);
        }
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        self.fallback.destroy(device);
        device.destroy_sampler(self.sampler, None);
    }
}

#[cfg(target_os = "android")]
fn current_spatial_environment_depth_frame_snapshot(
) -> Option<SpatialPublicEnvironmentDepthSnapshot> {
    crate::spatial_environment_depth::spatial_environment_depth_frame_snapshot().map(|snapshot| {
        SpatialPublicEnvironmentDepthSnapshot {
            image_handles: snapshot.image_handles,
            swapchain_index: snapshot.swapchain_index,
            width: snapshot.width,
            height: snapshot.height,
            near_z: snapshot.near_z,
            far_z: snapshot.far_z,
            acquired_frame_count: snapshot.acquired_frame_count,
        }
    })
}

#[cfg(not(target_os = "android"))]
fn current_spatial_environment_depth_frame_snapshot(
) -> Option<SpatialPublicEnvironmentDepthSnapshot> {
    None
}

impl SpatialPublicDepthFallback {
    unsafe fn create(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
    ) -> Result<Self, String> {
        let image = device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(SPATIAL_PUBLIC_DEPTH_FALLBACK_FORMAT)
                    .extent(vk::Extent3D {
                        width: 1,
                        height: 1,
                        depth: 1,
                    })
                    .mip_levels(1)
                    .array_layers(SPATIAL_PUBLIC_PACKED_EYE_COUNT as u32)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(vk::ImageUsageFlags::SAMPLED)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .initial_layout(vk::ImageLayout::UNDEFINED),
                None,
            )
            .map_err(|error| format!("create-spatial-public-depth-fallback-image-{error:?}"))?;
        let requirements = device.get_image_memory_requirements(image);
        let memory_type_index = match find_memory_type_index(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        ) {
            Some(index) => index,
            None => {
                device.destroy_image(image, None);
                return Err(format!(
                    "no-supported-memory-type-for-spatial-public-depth-fallback-bits-0x{:x}",
                    requirements.memory_type_bits
                ));
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
                    "allocate-spatial-public-depth-fallback-memory-{error:?}"
                ));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!(
                "bind-spatial-public-depth-fallback-memory-{error:?}"
            ));
        }
        let image_view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D_ARRAY)
                .format(SPATIAL_PUBLIC_DEPTH_FALLBACK_FORMAT)
                .subresource_range(depth_subresource_range()),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.destroy_image(image, None);
                device.free_memory(memory, None);
                return Err(format!(
                    "create-spatial-public-depth-fallback-view-{error:?}"
                ));
            }
        };
        Ok(Self {
            image,
            memory,
            image_view,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_image_view(self.image_view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

impl SpatialPublicGuideTarget {
    unsafe fn create(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        extent: vk::Extent2D,
        format: vk::Format,
        label: &str,
    ) -> Result<Self, String> {
        let image = device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(format)
                    .extent(vk::Extent3D {
                        width: extent.width,
                        height: extent.height,
                        depth: 1,
                    })
                    .mip_levels(1)
                    .array_layers(1)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(
                        vk::ImageUsageFlags::COLOR_ATTACHMENT
                            | vk::ImageUsageFlags::SAMPLED
                            | vk::ImageUsageFlags::TRANSFER_SRC
                            | vk::ImageUsageFlags::TRANSFER_DST,
                    )
                    .sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .initial_layout(vk::ImageLayout::UNDEFINED),
                None,
            )
            .map_err(|error| format!("create-{label}-image-{error:?}"))?;
        let requirements = device.get_image_memory_requirements(image);
        let memory_type_index = match find_memory_type_index(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        ) {
            Some(index) => index,
            None => {
                device.destroy_image(image, None);
                return Err(format!(
                    "no-supported-memory-type-for-{label}-bits-0x{:x}",
                    requirements.memory_type_bits
                ));
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
                return Err(format!("allocate-{label}-memory-{error:?}"));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("bind-{label}-memory-{error:?}"));
        }
        let image_view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(format)
                .subresource_range(color_subresource_range()),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.destroy_image(image, None);
                device.free_memory(memory, None);
                return Err(format!("create-{label}-view-{error:?}"));
            }
        };
        let framebuffer = match device.create_framebuffer(
            &vk::FramebufferCreateInfo::default()
                .render_pass(render_pass)
                .attachments(&[image_view])
                .width(extent.width)
                .height(extent.height)
                .layers(1),
            None,
        ) {
            Ok(framebuffer) => framebuffer,
            Err(error) => {
                device.destroy_image_view(image_view, None);
                device.destroy_image(image, None);
                device.free_memory(memory, None);
                return Err(format!("create-{label}-framebuffer-{error:?}"));
            }
        };
        Ok(Self {
            image,
            memory,
            image_view,
            framebuffer,
            allocation_size: requirements.size,
            memory_flags: memory_properties.memory_types[memory_type_index as usize].property_flags,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_framebuffer(self.framebuffer, None);
        device.destroy_image_view(self.image_view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

pub(crate) unsafe fn allocate_spatial_public_guide_targets(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    camera_descriptor_set_layout: vk::DescriptorSetLayout,
    projection_render_pass: vk::RenderPass,
) -> Result<SpatialPublicGuideTargets, String> {
    let extent = spatial_public_guide_target_extent();
    let format = SPATIAL_PUBLIC_GUIDE_TARGET_FORMAT;
    let render_pass = create_guide_render_pass(device, format)?;
    let sampler = match create_guide_sampler(device) {
        Ok(sampler) => sampler,
        Err(error) => {
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let descriptor_set_layout = match create_sample_descriptor_set_layout(device) {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let blur_pipeline_layout = match create_blur_pipeline_layout(device, descriptor_set_layout) {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let blur_pipeline = match create_public_blur_pipeline(device, render_pass, blur_pipeline_layout)
    {
        Ok(pipeline) => pipeline,
        Err(error) => {
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let descriptor_pool = match create_descriptor_pool(device) {
        Ok(pool) => pool,
        Err(error) => {
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let opaque_guide_descriptor_set_layout = match create_opaque_guide_descriptor_set_layout(device)
    {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let opaque_guide_pipeline_layout = match create_opaque_guide_pipeline_layout(
        device,
        camera_descriptor_set_layout,
        opaque_guide_descriptor_set_layout,
    ) {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let opaque_guide_pipelines =
        match create_opaque_guide_pipelines(device, render_pass, opaque_guide_pipeline_layout) {
            Ok(pipelines) => pipelines,
            Err(error) => {
                device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
                device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
                device.destroy_pipeline(blur_pipeline, None);
                device.destroy_pipeline_layout(blur_pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
    let opaque_guide_descriptor_pool = match create_opaque_guide_descriptor_pool(device) {
        Ok(pool) => pool,
        Err(error) => {
            destroy_pipelines(device, opaque_guide_pipelines);
            device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
            device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let mut targets = Vec::with_capacity(SPATIAL_PUBLIC_GUIDE_TARGET_COUNT);
    for index in 0..SPATIAL_PUBLIC_GUIDE_TARGET_COUNT {
        match SpatialPublicGuideTarget::create(
            device,
            memory_properties,
            render_pass,
            extent,
            format,
            &format!("spatial-public-guide-target-{index}"),
        ) {
            Ok(target) => targets.push(target),
            Err(error) => {
                for target in targets {
                    target.destroy(device);
                }
                device.destroy_pipeline(blur_pipeline, None);
                device.destroy_pipeline_layout(blur_pipeline_layout, None);
                destroy_pipelines(device, opaque_guide_pipelines);
                device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
                device.destroy_descriptor_pool(opaque_guide_descriptor_pool, None);
                device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        }
    }
    let sample_descriptor_sets = match allocate_sample_descriptor_sets(
        device,
        descriptor_pool,
        descriptor_set_layout,
        SPATIAL_PUBLIC_GUIDE_TARGET_COUNT,
    ) {
        Ok(sets) => sets,
        Err(error) => {
            for target in targets {
                target.destroy(device);
            }
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            destroy_pipelines(device, opaque_guide_pipelines);
            device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
            device.destroy_descriptor_pool(opaque_guide_descriptor_pool, None);
            device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    for (descriptor_set, target) in sample_descriptor_sets.iter().zip(targets.iter()) {
        write_sample_descriptor(device, *descriptor_set, sampler, target.image_view);
    }
    let opaque_guide_descriptor_set = match allocate_opaque_guide_descriptor_set(
        device,
        opaque_guide_descriptor_pool,
        opaque_guide_descriptor_set_layout,
    ) {
        Ok(descriptor_set) => descriptor_set,
        Err(error) => {
            for target in targets {
                target.destroy(device);
            }
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            destroy_pipelines(device, opaque_guide_pipelines);
            device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
            device.destroy_descriptor_pool(opaque_guide_descriptor_pool, None);
            device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    write_opaque_guide_descriptor_set(device, opaque_guide_descriptor_set, sampler, &targets);
    let depth_descriptor_set_layout = match create_depth_descriptor_set_layout(device) {
        Ok(layout) => layout,
        Err(error) => {
            for target in targets {
                target.destroy(device);
            }
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            destroy_pipelines(device, opaque_guide_pipelines);
            device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
            device.destroy_descriptor_pool(opaque_guide_descriptor_pool, None);
            device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let depth_resources = match SpatialPublicDepthResources::create(
        device,
        memory_properties,
        depth_descriptor_set_layout,
    ) {
        Ok(resources) => resources,
        Err(error) => {
            for target in targets {
                target.destroy(device);
            }
            device.destroy_descriptor_set_layout(depth_descriptor_set_layout, None);
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            destroy_pipelines(device, opaque_guide_pipelines);
            device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
            device.destroy_descriptor_pool(opaque_guide_descriptor_pool, None);
            device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let opaque_projection_pipeline_layout = match create_opaque_projection_pipeline_layout(
        device,
        camera_descriptor_set_layout,
        opaque_guide_descriptor_set_layout,
        depth_descriptor_set_layout,
    ) {
        Ok(layout) => layout,
        Err(error) => {
            for target in targets {
                target.destroy(device);
            }
            depth_resources.destroy(device);
            device.destroy_descriptor_set_layout(depth_descriptor_set_layout, None);
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            destroy_pipelines(device, opaque_guide_pipelines);
            device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
            device.destroy_descriptor_pool(opaque_guide_descriptor_pool, None);
            device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    let opaque_projection_pipeline = match create_opaque_projection_pipeline(
        device,
        projection_render_pass,
        opaque_projection_pipeline_layout,
    ) {
        Ok(pipeline) => pipeline,
        Err(error) => {
            for target in targets {
                target.destroy(device);
            }
            device.destroy_pipeline_layout(opaque_projection_pipeline_layout, None);
            depth_resources.destroy(device);
            device.destroy_descriptor_set_layout(depth_descriptor_set_layout, None);
            device.destroy_pipeline(blur_pipeline, None);
            device.destroy_pipeline_layout(blur_pipeline_layout, None);
            destroy_pipelines(device, opaque_guide_pipelines);
            device.destroy_pipeline_layout(opaque_guide_pipeline_layout, None);
            device.destroy_descriptor_pool(opaque_guide_descriptor_pool, None);
            device.destroy_descriptor_set_layout(opaque_guide_descriptor_set_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_render_pass(render_pass, None);
            return Err(error);
        }
    };
    Ok(SpatialPublicGuideTargets {
        targets,
        extent,
        format,
        render_pass,
        sampler,
        descriptor_set_layout,
        descriptor_pool,
        sample_descriptor_sets,
        opaque_guide_descriptor_set_layout,
        opaque_guide_descriptor_pool,
        opaque_guide_descriptor_set,
        opaque_guide_pipeline_layout,
        opaque_guide_pipelines,
        depth_descriptor_set_layout,
        depth_resources,
        opaque_projection_pipeline_layout,
        opaque_projection_pipeline,
        blur_pipeline_layout,
        blur_pipeline,
    })
}

pub(crate) fn public_guide_targets_pending_marker_fields(reason: &str) -> String {
    format!(
        "publicMultiStackGuideTargetsAllocated=false publicMultiStackGuidePassResourcesReady=false publicMultiStackGuideTargetCount={} publicMultiStackGuideTargetExtent={}x{} publicMultiStackGuideTargetFormat={:?} publicMultiStackGuideTargetSkipReason={} publicMultiStackProjectionApplied=false publicMultiStackPassExecutionReady=false publicGuideBlurPipelineReady=false publicGuideBlurRecordFunctionReady=false publicGuideBlurRuntimeReady=false publicMultiStackOpaqueGuideDescriptorReady=false publicMultiStackOpaqueGuidePipelinesReady=false publicMultiStackOpaqueProjectionPipelineReady=false publicMultiStackOpaqueProjectionPayloadExecutionReady=false publicMultiStackOpaquePayloadExecutionReady=false publicMultiStackDepthFallbackDescriptorBound=false publicMultiStackDepthFallbackReady=false {}",
        SPATIAL_PUBLIC_GUIDE_TARGET_COUNT,
        spatial_public_guide_target_extent().width,
        spatial_public_guide_target_extent().height,
        SPATIAL_PUBLIC_GUIDE_TARGET_FORMAT,
        crate::marker_token(reason),
        spatial_environment_depth_marker_fields(),
    )
}

#[cfg(target_os = "android")]
fn spatial_environment_depth_marker_fields() -> String {
    crate::spatial_environment_depth::spatial_environment_depth_marker_fields()
}

#[cfg(not(target_os = "android"))]
fn spatial_environment_depth_marker_fields() -> String {
    "publicMultiStackDepthSource=spatial-fallback-depth-descriptor publicMultiStackDepthProviderRequested=false publicMultiStackDepthRealProviderBound=false publicMultiStackDepthValidData=false publicMultiStackDepthPermissionSurface=horizonos.permission.USE_SCENE+USE_SCENE_DATA environmentDepthSource=spatial-fallback-depth-descriptor environmentDepthProviderState=not-bound environmentDepthProviderAvailable=false environmentDepthRealProviderBound=false environmentDepthAcquireStatus=not-attempted-provider-not-bound environmentDepthValidData=false environmentDepthDebugValidSampleCount=0 environmentDepthAcquiredFrameCount=0".to_string()
}

fn public_guide_pass_schedule_marker() -> String {
    SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE
        .iter()
        .enumerate()
        .map(|(index, step)| {
            let source = step
                .input_target
                .map(|target| target.to_string())
                .unwrap_or_else(|| "camera".to_string());
            let kind = match step.kind {
                SpatialPublicGuidePassKind::Opaque { pipeline_index } => {
                    format!("opaque{pipeline_index}")
                }
                SpatialPublicGuidePassKind::PublicBlur {
                    direction: PublicGuideBlurDirection::Horizontal,
                } => "public-blur-horizontal".to_string(),
                SpatialPublicGuidePassKind::PublicBlur {
                    direction: PublicGuideBlurDirection::Vertical,
                } => "public-blur-vertical".to_string(),
            };
            format!("{index}:{source}->{kind}->{}", step.output_target)
        })
        .collect::<Vec<_>>()
        .join(",")
}

unsafe fn begin_projection_pass(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
) {
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: [0.0, 0.0, 0.0, 0.0],
        },
    }];
    device.cmd_begin_render_pass(
        command_buffer,
        &vk::RenderPassBeginInfo::default()
            .render_pass(render_pass)
            .framebuffer(framebuffer)
            .render_area(vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent,
            })
            .clear_values(&clear_values),
        vk::SubpassContents::INLINE,
    );
}

unsafe fn set_packed_eye_view(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    extent: vk::Extent2D,
    eye_index: usize,
) {
    let half_width = extent.width / 2;
    let x = if eye_index == 0 { 0 } else { half_width };
    let width = if eye_index == 0 {
        half_width.max(1)
    } else {
        extent.width.saturating_sub(half_width).max(1)
    };
    let viewport = [vk::Viewport {
        x: x as f32,
        y: 0.0,
        width: width as f32,
        height: extent.height as f32,
        min_depth: 0.0,
        max_depth: 1.0,
    }];
    let scissor = [vk::Rect2D {
        offset: vk::Offset2D { x: x as i32, y: 0 },
        extent: vk::Extent2D {
            width,
            height: extent.height,
        },
    }];
    device.cmd_set_viewport(command_buffer, 0, &viewport);
    device.cmd_set_scissor(command_buffer, 0, &scissor);
}

unsafe fn set_packed_projection_target_view(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    extent: vk::Extent2D,
    target_rect: [f32; 4],
) {
    let viewport = [vk::Viewport {
        x: 0.0,
        y: 0.0,
        width: extent.width as f32,
        height: extent.height as f32,
        min_depth: 0.0,
        max_depth: 1.0,
    }];
    let scissor = [target_rect_to_scissor(extent, target_rect)];
    device.cmd_set_viewport(command_buffer, 0, &viewport);
    device.cmd_set_scissor(command_buffer, 0, &scissor);
}

fn packed_eye_source_rect(eye_index: usize) -> [f32; 4] {
    if eye_index == 0 {
        [0.0, 0.0, 0.5, 1.0]
    } else {
        [0.5, 0.0, 0.5, 1.0]
    }
}

fn packed_projection_target_rect(eye_index: usize) -> [f32; 4] {
    let push = crate::camera_hwb_projection_target::camera_hwb_projection_push();
    if eye_index == 0 {
        push.left_rect
    } else {
        push.right_rect
    }
}

fn rect_marker(rect: [f32; 4]) -> String {
    format!(
        "{:.6};{:.6};{:.6};{:.6}",
        rect[0], rect[1], rect[2], rect[3]
    )
}

fn target_rect_to_scissor(extent: vk::Extent2D, rect: [f32; 4]) -> vk::Rect2D {
    let (x, width) = normalized_interval_to_pixels(extent.width, rect[0], rect[0] + rect[2]);
    let (y, height) = normalized_interval_to_pixels(extent.height, rect[1], rect[1] + rect[3]);
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

unsafe fn create_opaque_guide_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let bindings = (4..=8)
        .map(|binding| {
            vk::DescriptorSetLayoutBinding::default()
                .binding(binding)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        })
        .collect::<Vec<_>>();
    device
        .create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )
        .map_err(|error| format!("create-spatial-public-opaque-guide-layout-{error:?}"))
}

unsafe fn create_opaque_guide_pipeline_layout(
    device: &ash::Device,
    camera_descriptor_set_layout: vk::DescriptorSetLayout,
    opaque_guide_descriptor_set_layout: vk::DescriptorSetLayout,
) -> Result<vk::PipelineLayout, String> {
    let set_layouts = [
        camera_descriptor_set_layout,
        opaque_guide_descriptor_set_layout,
    ];
    let push_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(mem::size_of::<OpaqueGuidePush>() as u32)];
    device
        .create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&set_layouts)
                .push_constant_ranges(&push_ranges),
            None,
        )
        .map_err(|error| format!("create-spatial-public-opaque-guide-pipeline-layout-{error:?}"))
}

unsafe fn create_opaque_projection_pipeline_layout(
    device: &ash::Device,
    camera_descriptor_set_layout: vk::DescriptorSetLayout,
    opaque_guide_descriptor_set_layout: vk::DescriptorSetLayout,
    depth_descriptor_set_layout: vk::DescriptorSetLayout,
) -> Result<vk::PipelineLayout, String> {
    let set_layouts = [
        camera_descriptor_set_layout,
        opaque_guide_descriptor_set_layout,
        depth_descriptor_set_layout,
    ];
    let push_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(mem::size_of::<OpaqueProjectionPush>() as u32)];
    device
        .create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&set_layouts)
                .push_constant_ranges(&push_ranges),
            None,
        )
        .map_err(|error| {
            format!("create-spatial-public-opaque-projection-pipeline-layout-{error:?}")
        })
}

unsafe fn create_opaque_guide_pipelines(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<Vec<vk::Pipeline>, String> {
    if !OPAQUE_GUIDE_SHADER_COMPILED {
        return Ok(Vec::new());
    }
    let mut pipelines = Vec::with_capacity(OPAQUE_GUIDE_SHADER_PASS_COUNT);
    for (pass_index, fragment_spirv) in OPAQUE_GUIDE_PASS_SPIRV.iter().enumerate() {
        match create_fullscreen_fragment_pipeline(
            device,
            render_pass,
            pipeline_layout,
            fragment_spirv,
            &format!("spatial-public-opaque-guide-pass-{pass_index}"),
        ) {
            Ok(pipeline) => pipelines.push(pipeline),
            Err(error) => {
                destroy_pipelines(device, pipelines);
                return Err(error);
            }
        }
    }
    Ok(pipelines)
}

unsafe fn create_opaque_guide_descriptor_pool(
    device: &ash::Device,
) -> Result<vk::DescriptorPool, String> {
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(SPATIAL_PUBLIC_GUIDE_TARGET_COUNT as u32)];
    device
        .create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(1),
            None,
        )
        .map_err(|error| format!("create-spatial-public-opaque-guide-pool-{error:?}"))
}

unsafe fn create_depth_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let bindings = [vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)];
    device
        .create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )
        .map_err(|error| format!("create-spatial-public-depth-layout-{error:?}"))
}

unsafe fn create_depth_descriptor_pool(device: &ash::Device) -> Result<vk::DescriptorPool, String> {
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(SPATIAL_PUBLIC_MAX_DEPTH_DESCRIPTOR_SETS)];
    device
        .create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(SPATIAL_PUBLIC_MAX_DEPTH_DESCRIPTOR_SETS),
            None,
        )
        .map_err(|error| format!("create-spatial-public-depth-pool-{error:?}"))
}

unsafe fn create_depth_sampler(device: &ash::Device) -> Result<vk::Sampler, String> {
    device
        .create_sampler(
            &vk::SamplerCreateInfo::default()
                .mag_filter(vk::Filter::NEAREST)
                .min_filter(vk::Filter::NEAREST)
                .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE),
            None,
        )
        .map_err(|error| format!("create-spatial-public-depth-sampler-{error:?}"))
}

unsafe fn allocate_depth_descriptor_set(
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
        .map_err(|error| format!("allocate-spatial-public-depth-set-{error:?}"))
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

unsafe fn allocate_opaque_guide_descriptor_set(
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
        .map_err(|error| format!("allocate-spatial-public-opaque-guide-set-{error:?}"))
}

unsafe fn write_opaque_guide_descriptor_set(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    sampler: vk::Sampler,
    targets: &[SpatialPublicGuideTarget],
) {
    let image_infos = targets
        .iter()
        .map(|target| {
            vk::DescriptorImageInfo::default()
                .sampler(sampler)
                .image_view(target.image_view)
                .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
        })
        .collect::<Vec<_>>();
    let writes = image_infos
        .iter()
        .enumerate()
        .map(|(index, image_info)| {
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding((index + 4) as u32)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .image_info(std::slice::from_ref(image_info))
        })
        .collect::<Vec<_>>();
    device.update_descriptor_sets(&writes, &[]);
}

unsafe fn begin_guide_pass(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
) {
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: [0.0, 0.0, 0.0, 1.0],
        },
    }];
    device.cmd_begin_render_pass(
        command_buffer,
        &vk::RenderPassBeginInfo::default()
            .render_pass(render_pass)
            .framebuffer(framebuffer)
            .render_area(vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent,
            })
            .clear_values(&clear_values),
        vk::SubpassContents::INLINE,
    );
}

unsafe fn transition_guide_image_for_sampling(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
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
        command_buffer,
        vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
        vk::PipelineStageFlags::FRAGMENT_SHADER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &barrier,
    );
}

unsafe fn create_guide_render_pass(
    device: &ash::Device,
    color_format: vk::Format,
) -> Result<vk::RenderPass, String> {
    let color_attachment = [vk::AttachmentDescription::default()
        .format(color_format)
        .samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR)
        .store_op(vk::AttachmentStoreOp::STORE)
        .initial_layout(vk::ImageLayout::UNDEFINED)
        .final_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let color_attachment_ref = [vk::AttachmentReference::default()
        .attachment(0)
        .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)];
    let subpass = [vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_attachment_ref)];
    let dependencies = [
        vk::SubpassDependency::default()
            .src_subpass(vk::SUBPASS_EXTERNAL)
            .dst_subpass(0)
            .src_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER)
            .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .src_access_mask(vk::AccessFlags::SHADER_READ)
            .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE),
        vk::SubpassDependency::default()
            .src_subpass(0)
            .dst_subpass(vk::SUBPASS_EXTERNAL)
            .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .dst_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER)
            .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ),
    ];
    device
        .create_render_pass(
            &vk::RenderPassCreateInfo::default()
                .attachments(&color_attachment)
                .subpasses(&subpass)
                .dependencies(&dependencies),
            None,
        )
        .map_err(|error| format!("create-spatial-public-guide-render-pass-{error:?}"))
}

unsafe fn create_guide_sampler(device: &ash::Device) -> Result<vk::Sampler, String> {
    device
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
        .map_err(|error| format!("create-spatial-public-guide-sampler-{error:?}"))
}

unsafe fn create_sample_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let bindings = [vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)];
    device
        .create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )
        .map_err(|error| format!("create-spatial-public-guide-sample-layout-{error:?}"))
}

unsafe fn create_blur_pipeline_layout(
    device: &ash::Device,
    descriptor_set_layout: vk::DescriptorSetLayout,
) -> Result<vk::PipelineLayout, String> {
    let set_layouts = [descriptor_set_layout];
    let push_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(mem::size_of::<PublicGuideBlurPush>() as u32)];
    device
        .create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&set_layouts)
                .push_constant_ranges(&push_ranges),
            None,
        )
        .map_err(|error| format!("create-spatial-public-guide-blur-pipeline-layout-{error:?}"))
}

unsafe fn create_public_blur_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    create_fullscreen_fragment_pipeline(
        device,
        render_pass,
        pipeline_layout,
        include_bytes!(concat!(env!("OUT_DIR"), "/public_guide_blur.frag.spv")),
        "public-guide-blur",
    )
}

unsafe fn create_opaque_projection_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<Option<vk::Pipeline>, String> {
    if !OPAQUE_PROJECTION_SHADER_COMPILED {
        return Ok(None);
    }
    create_fullscreen_fragment_pipeline(
        device,
        render_pass,
        pipeline_layout,
        include_bytes!(concat!(
            env!("OUT_DIR"),
            "/spatial_opaque_projection.frag.spv"
        )),
        "spatial-public-opaque-projection",
    )
    .map(Some)
}

unsafe fn create_fullscreen_fragment_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
    fragment_spirv: &[u8],
    label: &str,
) -> Result<vk::Pipeline, String> {
    let vert_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/camera_hwb_probe.vert.spv")),
        &format!("{label}-vertex"),
    )?;
    let frag_module =
        match create_shader_module(device, fragment_spirv, &format!("{label}-fragment")) {
            Ok(module) => module,
            Err(error) => {
                device.destroy_shader_module(vert_module, None);
                return Err(error);
            }
        };
    let entry_point = CString::new("main").expect("static shader entry");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vert_module)
            .name(&entry_point),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(frag_module)
            .name(&entry_point),
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
        .color_write_mask(vk::ColorComponentFlags::RGBA)
        .blend_enable(false)];
    let color_blend =
        vk::PipelineColorBlendStateCreateInfo::default().attachments(&color_blend_attachment);
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic_state =
        vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let pipeline_info = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&color_blend)
        .dynamic_state(&dynamic_state)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0)];
    let pipeline = device
        .create_graphics_pipelines(vk::PipelineCache::null(), &pipeline_info, None)
        .map_err(|(_, error)| format!("create-{label}-pipeline-{error:?}"))?
        .remove(0);
    device.destroy_shader_module(frag_module, None);
    device.destroy_shader_module(vert_module, None);
    Ok(pipeline)
}

unsafe fn destroy_pipelines(device: &ash::Device, pipelines: Vec<vk::Pipeline>) {
    for pipeline in pipelines {
        device.destroy_pipeline(pipeline, None);
    }
}

unsafe fn create_descriptor_pool(device: &ash::Device) -> Result<vk::DescriptorPool, String> {
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(SPATIAL_PUBLIC_GUIDE_TARGET_COUNT as u32)];
    device
        .create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(SPATIAL_PUBLIC_GUIDE_TARGET_COUNT as u32),
            None,
        )
        .map_err(|error| format!("create-spatial-public-guide-descriptor-pool-{error:?}"))
}

unsafe fn allocate_sample_descriptor_sets(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    count: usize,
) -> Result<Vec<vk::DescriptorSet>, String> {
    let set_layouts = vec![descriptor_set_layout; count];
    device
        .allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&set_layouts),
        )
        .map_err(|error| format!("allocate-spatial-public-guide-sample-sets-{error:?}"))
}

unsafe fn write_sample_descriptor(
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

unsafe fn push_fragment_constants<T>(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    pipeline_layout: vk::PipelineLayout,
    value: &T,
) {
    device.cmd_push_constants(
        command_buffer,
        pipeline_layout,
        vk::ShaderStageFlags::FRAGMENT,
        0,
        as_bytes(value),
    );
}

fn as_bytes<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) }
}

fn bool_marker(value: bool) -> &'static str {
    if value {
        "true"
    } else {
        "false"
    }
}

#[cfg(target_os = "android")]
fn spatial_native_passthrough_marker_fields() -> String {
    crate::spatial_native_passthrough::spatial_native_passthrough_marker_fields()
}

#[cfg(not(target_os = "android"))]
fn spatial_native_passthrough_marker_fields() -> String {
    "nativePassthroughRequested=true nativePassthroughLayerActive=false nativePassthroughActivationPath=spatial-native-receipt-xr-fb-passthrough nativePassthroughCompositionLayerSubmission=spatial-sdk-owned-end-frame".to_string()
}

unsafe fn create_shader_module(
    device: &ash::Device,
    bytes: &[u8],
    label: &str,
) -> Result<vk::ShaderModule, String> {
    if bytes.is_empty() || bytes.len() % mem::size_of::<u32>() != 0 {
        return Err(format!("{label}-spirv-invalid-length"));
    }
    let words = bytes
        .chunks_exact(mem::size_of::<u32>())
        .map(|chunk| u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
        .collect::<Vec<_>>();
    device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
        .map_err(|error| format!("create-{label}-shader-module-{error:?}"))
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
        layer_count: SPATIAL_PUBLIC_PACKED_EYE_COUNT as u32,
    }
}

fn find_memory_type_index(
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    memory_type_bits: u32,
    preferred: vk::MemoryPropertyFlags,
) -> Option<u32> {
    let memory_type_count = memory_properties
        .memory_type_count
        .min(memory_properties.memory_types.len() as u32) as usize;
    let flags = memory_properties.memory_types[..memory_type_count]
        .iter()
        .map(|memory_type| memory_type.property_flags)
        .collect::<Vec<_>>();
    select_memory_type_index(memory_type_bits, &flags, preferred)
}

fn select_memory_type_index(
    memory_type_bits: u32,
    memory_type_flags: &[vk::MemoryPropertyFlags],
    preferred: vk::MemoryPropertyFlags,
) -> Option<u32> {
    for (index, flags) in memory_type_flags.iter().enumerate() {
        if index >= u32::BITS as usize {
            break;
        }
        let supported = (memory_type_bits & (1 << index)) != 0;
        if supported && flags.contains(preferred) {
            return Some(index as u32);
        }
    }
    for (index, _flags) in memory_type_flags.iter().enumerate() {
        if index >= u32::BITS as usize {
            break;
        }
        if (memory_type_bits & (1 << index)) != 0 {
            return Some(index as u32);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memory_selection_prefers_device_local_supported_type() {
        let flags = [
            vk::MemoryPropertyFlags::HOST_VISIBLE,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
        ];
        assert_eq!(
            select_memory_type_index(0b111, &flags, vk::MemoryPropertyFlags::DEVICE_LOCAL),
            Some(1)
        );
    }

    #[test]
    fn memory_selection_falls_back_to_any_supported_type() {
        let flags = [
            vk::MemoryPropertyFlags::HOST_VISIBLE,
            vk::MemoryPropertyFlags::HOST_COHERENT,
        ];
        assert_eq!(
            select_memory_type_index(0b10, &flags, vk::MemoryPropertyFlags::DEVICE_LOCAL),
            Some(1)
        );
    }

    #[test]
    fn pending_marker_keeps_runtime_execution_inactive() {
        let marker = public_guide_targets_pending_marker_fields("not allocated yet");
        assert!(marker.contains("publicMultiStackGuideTargetsAllocated=false"));
        assert!(marker.contains("publicMultiStackGuidePassResourcesReady=false"));
        assert!(marker.contains("publicMultiStackGuideTargetCount=5"));
        assert!(marker.contains("publicMultiStackPassExecutionReady=false"));
        assert!(marker.contains("publicGuideBlurPipelineReady=false"));
        assert!(marker.contains("publicGuideBlurRecordFunctionReady=false"));
        assert!(marker.contains("publicGuideBlurRuntimeReady=false"));
        assert!(marker.contains("publicMultiStackOpaqueGuideDescriptorReady=false"));
        assert!(marker.contains("publicMultiStackOpaqueGuidePipelinesReady=false"));
        assert!(marker.contains("publicMultiStackOpaquePayloadExecutionReady=false"));
    }

    #[test]
    fn blur_direction_push_steps_match_extent_axes() {
        let extent = vk::Extent2D {
            width: 400,
            height: 200,
        };
        assert_eq!(
            PublicGuideBlurDirection::Horizontal.step_and_scale(extent),
            [0.0025, 0.0, 1.0, 0.0]
        );
        assert_eq!(
            PublicGuideBlurDirection::Vertical.step_and_scale(extent),
            [0.0, 0.005, 1.0, 0.0]
        );
    }

    #[test]
    fn packed_eye_source_rects_split_guide_texture() {
        assert_eq!(packed_eye_source_rect(0), [0.0, 0.0, 0.5, 1.0]);
        assert_eq!(packed_eye_source_rect(1), [0.5, 0.0, 0.5, 1.0]);
    }

    #[test]
    fn packed_projection_target_rects_match_camera_projection_push() {
        let push = crate::camera_hwb_projection_target::camera_hwb_projection_push();
        assert_eq!(packed_projection_target_rect(0), push.left_rect);
        assert_eq!(packed_projection_target_rect(1), push.right_rect);
        assert_eq!(
            OpaqueProjectionPush::for_packed_eye(0, 1.25, fallback_depth_binding()).target_rect,
            push.left_rect
        );
        assert_eq!(
            OpaqueProjectionPush::for_packed_eye(1, 1.25, fallback_depth_binding()).target_rect,
            push.right_rect
        );
    }

    #[test]
    fn projection_layer_override_parser_accepts_numeric_layer_indices() {
        assert_eq!(parse_projection_layer_override("5.0"), Some(5.0));
        assert_eq!(parse_projection_layer_override("7.0"), Some(7.0));
        assert_eq!(parse_projection_layer_override("8.0"), Some(8.0));
        assert_eq!(parse_projection_layer_override("9.0"), Some(8.0));
        assert_eq!(parse_projection_layer_override("-9.0"), Some(-1.0));
        assert_eq!(parse_projection_layer_override("NaN"), None);
        assert_eq!(parse_projection_layer_override("not-a-layer"), None);
    }

    #[test]
    fn passthrough_edge_window_is_an_explicit_layer_override() {
        assert!(is_meta_passthrough_edge_window_layer(7.0));
        assert!(!is_meta_passthrough_edge_window_layer(0.0));
        assert!(!is_meta_passthrough_edge_window_layer(-1.0));
    }

    #[test]
    fn raw_custom_projection_is_an_explicit_layer_override() {
        assert!(is_raw_custom_projection_layer(8.0));
        assert!(!is_raw_custom_projection_layer(7.0));
        assert!(!is_raw_custom_projection_layer(0.0));
    }

    #[test]
    fn depth_layer_policy_parser_accepts_panel_and_setprop_tokens() {
        assert_eq!(
            parse_spatial_public_depth_layer_policy("mono-layer0"),
            Some(SpatialPublicDepthLayerPolicy::MonoLayer0)
        );
        assert_eq!(
            parse_spatial_public_depth_layer_policy("layer1"),
            Some(SpatialPublicDepthLayerPolicy::MonoLayer1)
        );
        assert_eq!(
            parse_spatial_public_depth_layer_policy("per_eye"),
            Some(SpatialPublicDepthLayerPolicy::EyeIndex)
        );
        assert_eq!(
            parse_spatial_public_depth_layer_policy("compare"),
            Some(SpatialPublicDepthLayerPolicy::Compare)
        );
        assert_eq!(parse_spatial_public_depth_layer_policy("invalid"), None);
    }

    #[test]
    fn depth_layer_policy_maps_to_expected_shader_source_layers() {
        assert_eq!(
            SpatialPublicDepthLayerPolicy::MonoLayer0.source_layer_for_eye(1),
            0.0
        );
        assert_eq!(
            SpatialPublicDepthLayerPolicy::MonoLayer1.source_layer_for_eye(0),
            1.0
        );
        assert_eq!(
            SpatialPublicDepthLayerPolicy::EyeIndex.source_layer_for_eye(1),
            1.0
        );
        assert_eq!(
            SpatialPublicDepthLayerPolicy::Compare.source_layer_for_eye(0),
            SPATIAL_PUBLIC_DEPTH_LAYER_COMPARE_SENTINEL
        );
    }

    #[test]
    fn opaque_projection_push_defaults_to_layer_cycle_without_android_property() {
        assert_eq!(
            OpaqueProjectionPush::for_packed_eye(0, 1.25, fallback_depth_binding()).params0[3],
            SPATIAL_PUBLIC_OPAQUE_PROJECTION_LAYER_OVERRIDE_DEFAULT
        );
    }

    fn fallback_depth_binding() -> SpatialPublicDepthBinding {
        SpatialPublicDepthBinding {
            descriptor_set: vk::DescriptorSet::null(),
            real_depth_bound: false,
            near_z: 0.001,
            far_z: SPATIAL_PUBLIC_ENVIRONMENT_DEPTH_CONFIGURED_FAR_M,
        }
    }

    #[test]
    fn packed_projection_scissors_clip_to_native_target_footprint() {
        let extent = vk::Extent2D {
            width: 2048,
            height: 1024,
        };
        let left = target_rect_to_scissor(extent, packed_projection_target_rect(0));
        let right = target_rect_to_scissor(extent, packed_projection_target_rect(1));
        assert_eq!(left.offset.x, 228);
        assert_eq!(left.offset.y, 224);
        assert_eq!(left.extent.width, 569);
        assert_eq!(left.extent.height, 672);
        assert_eq!(right.offset.x, 1251);
        assert_eq!(right.offset.y, 224);
        assert_eq!(right.extent.width, 569);
        assert_eq!(right.extent.height, 688);
        assert!(right.offset.x >= 1024);
        assert!(left.offset.x + left.extent.width as i32 <= 1024);
    }

    #[test]
    fn rect_marker_formats_native_acceptance_tokens() {
        assert_eq!(
            rect_marker(packed_projection_target_rect(0)),
            "0.111389;0.218750;0.277778;0.656250"
        );
        assert_eq!(
            rect_marker(packed_projection_target_rect(1)),
            "0.610834;0.218750;0.277778;0.671875"
        );
    }

    #[test]
    fn guide_pass_schedule_keeps_blur_public() {
        assert_eq!(SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE.len(), 6);
        assert_eq!(
            SPATIAL_PUBLIC_GUIDE_PASS_SCHEDULE
                .iter()
                .map(|step| step.kind)
                .collect::<Vec<_>>(),
            vec![
                SpatialPublicGuidePassKind::Opaque { pipeline_index: 0 },
                SpatialPublicGuidePassKind::PublicBlur {
                    direction: PublicGuideBlurDirection::Horizontal,
                },
                SpatialPublicGuidePassKind::PublicBlur {
                    direction: PublicGuideBlurDirection::Vertical,
                },
                SpatialPublicGuidePassKind::Opaque { pipeline_index: 3 },
                SpatialPublicGuidePassKind::PublicBlur {
                    direction: PublicGuideBlurDirection::Horizontal,
                },
                SpatialPublicGuidePassKind::PublicBlur {
                    direction: PublicGuideBlurDirection::Vertical,
                },
            ]
        );
        assert!(public_guide_pass_schedule_marker().contains("public-blur-horizontal"));
        assert!(public_guide_pass_schedule_marker().contains("public-blur-vertical"));
    }
}
