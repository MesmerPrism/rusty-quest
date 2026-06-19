//! Low-resolution public guide blur graph for native camera projection.

use std::{ffi::CString, mem};

use ash::vk;

use crate::{
    camera_projection::PreparedCameraProjection,
    camera_projection_metadata::{CameraProjectionMetadata, TargetRect},
    native_renderer_options::{NativeGuideGraphResolution, NativeProjectionBorderStretchSettings},
};

const GUIDE_EYE_COUNT: usize = 2;
const GUIDE_BLUR_TAPS_PER_AXIS: u32 = 5;

#[derive(Clone, Debug, Default)]
pub(crate) struct GuideBlurGraphFrameStats {
    pub(crate) ready: bool,
    pub(crate) rendered: bool,
    pub(crate) cache_hit: bool,
    pub(crate) blur_enabled: bool,
    pub(crate) resolution: NativeGuideGraphResolution,
    pub(crate) left_source_frame: u64,
    pub(crate) right_source_frame: u64,
    pub(crate) left_hardware_buffer_id: u64,
    pub(crate) right_hardware_buffer_id: u64,
    pub(crate) render_count: u64,
    pub(crate) cache_hits: u64,
}

impl GuideBlurGraphFrameStats {
    pub(crate) fn unavailable() -> Self {
        Self::unavailable_with_options(false, NativeGuideGraphResolution::Low384)
    }

    pub(crate) fn unavailable_with_blur(blur_enabled: bool) -> Self {
        Self::unavailable_with_options(blur_enabled, NativeGuideGraphResolution::Low384)
    }

    pub(crate) fn unavailable_with_options(
        blur_enabled: bool,
        resolution: NativeGuideGraphResolution,
    ) -> Self {
        Self {
            blur_enabled,
            resolution,
            ..Self::default()
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        let path = format!(
            "{}-{}",
            self.resolution.path_prefix(),
            if self.blur_enabled {
                "two-phase-5tap-blur"
            } else {
                "downsample-no-blur"
            }
        );
        let passes = if self.blur_enabled {
            "downsample,horizontal-5tap,vertical-5tap"
        } else {
            "downsample"
        };
        let [width, height] = self.resolution.extent();
        format!(
            "guideGraphReady={} guideGraphRendered={} guideGraphCacheHit={} guideGraphBlurEnabled={} guideGraphResolutionPolicy={} guideGraphPath={} guideGraphDownsampleResolution={}x{} guideGraphHorizontalTaps={} guideGraphVerticalTaps={} guideGraphPasses={} guideGraphSource=imported-camera-hwb-descriptor guideGraphFinalProjectionSource=guide-texture guideGraphFinalExternalHwbSamples={} guideTextureSamples={} guideGraphRenderCount={} guideGraphCacheHits={} guideGraphLeftSourceFrame={} guideGraphRightSourceFrame={} guideGraphLeftHardwareBufferId={} guideGraphRightHardwareBufferId={}",
            self.ready,
            self.rendered,
            self.cache_hit,
            self.blur_enabled,
            self.resolution.marker_value(),
            path,
            width,
            height,
            if self.blur_enabled {
                GUIDE_BLUR_TAPS_PER_AXIS
            } else {
                0
            },
            if self.blur_enabled {
                GUIDE_BLUR_TAPS_PER_AXIS
            } else {
                0
            },
            passes,
            if self.ready { 0 } else { 2 },
            if self.ready { 1 } else { 0 },
            self.render_count,
            self.cache_hits,
            self.left_source_frame,
            self.right_source_frame,
            self.left_hardware_buffer_id,
            self.right_hardware_buffer_id,
        )
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct GuideFrameKey {
    left_source_frame: u64,
    right_source_frame: u64,
    left_hardware_buffer_id: u64,
    right_hardware_buffer_id: u64,
}

impl GuideFrameKey {
    fn from_prepared(prepared: &PreparedCameraProjection) -> Self {
        Self {
            left_source_frame: prepared.stats.left_source_frame,
            right_source_frame: prepared.stats.right_source_frame,
            left_hardware_buffer_id: prepared.stats.left_hardware_buffer_id,
            right_hardware_buffer_id: prepared.stats.right_hardware_buffer_id,
        }
    }
}

pub(crate) struct GuideBlurGraphRenderer {
    memory_properties: vk::PhysicalDeviceMemoryProperties,
    color_format: vk::Format,
    projection_render_pass: vk::RenderPass,
    resolution: NativeGuideGraphResolution,
    resources: Option<GuideBlurGraphResources>,
    last_frame_key: Option<GuideFrameKey>,
    render_count: u64,
    cache_hits: u64,
}

impl GuideBlurGraphRenderer {
    pub(crate) fn new(
        memory_properties: vk::PhysicalDeviceMemoryProperties,
        color_format: vk::Format,
        projection_render_pass: vk::RenderPass,
        resolution: NativeGuideGraphResolution,
    ) -> Self {
        Self {
            memory_properties,
            color_format,
            projection_render_pass,
            resolution,
            resources: None,
            last_frame_key: None,
            render_count: 0,
            cache_hits: 0,
        }
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(resources) = self.resources.take() {
            resources.destroy(device);
        }
    }

    pub(crate) unsafe fn record_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        prepared: &PreparedCameraProjection,
        projection_metadata: &CameraProjectionMetadata,
        blur_enabled: bool,
    ) -> Result<GuideBlurGraphFrameStats, String> {
        self.ensure_resources(device, prepared.descriptor_set_layout)?;
        let key = GuideFrameKey::from_prepared(prepared);
        if self.last_frame_key == Some(key) {
            self.cache_hits = self.cache_hits.saturating_add(1);
            return Ok(self.stats_for_key(key, false, true, blur_enabled));
        }

        let resources = self
            .resources
            .as_ref()
            .ok_or_else(|| "guide blur graph resources were not initialized".to_string())?;
        let extent = guide_extent(self.resolution);
        let [width, height] = self.resolution.extent();
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: width as f32,
            height: height as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = [vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent,
        }];
        for eye_index in 0..GUIDE_EYE_COUNT {
            let eye = &resources.eyes[eye_index];
            begin_guide_pass(
                device,
                cmd,
                resources.render_pass,
                eye.downsample.framebuffer,
                extent,
            );
            device.cmd_set_viewport(cmd, 0, &viewport);
            device.cmd_set_scissor(cmd, 0, &scissor);
            device.cmd_bind_pipeline(
                cmd,
                vk::PipelineBindPoint::GRAPHICS,
                resources.downsample_pipeline,
            );
            device.cmd_bind_descriptor_sets(
                cmd,
                vk::PipelineBindPoint::GRAPHICS,
                resources.camera_pipeline_layout,
                0,
                &[prepared.descriptor_set],
                &[],
            );
            let downsample_push = GuideDownsamplePush {
                params: [
                    eye_index as f32,
                    projection_metadata.source_sample_y_flip,
                    width as f32,
                    height as f32,
                ],
            };
            push_fragment_constants(
                device,
                cmd,
                resources.camera_pipeline_layout,
                &downsample_push,
            );
            device.cmd_draw(cmd, 3, 1, 0, 0);
            device.cmd_end_render_pass(cmd);
            transition_guide_image_for_sampling(device, cmd, eye.downsample.image);

            if blur_enabled {
                self.record_blur_axis(
                    device,
                    cmd,
                    resources,
                    eye.source_descriptor_set,
                    eye.horizontal.framebuffer,
                    eye.horizontal.image,
                    [1.0 / width as f32, 0.0, 0.0, 0.0],
                );
                self.record_blur_axis(
                    device,
                    cmd,
                    resources,
                    eye.ping_descriptor_set,
                    eye.vertical.framebuffer,
                    eye.vertical.image,
                    [0.0, 1.0 / height as f32, 1.0, 0.0],
                );
            }
        }

        self.last_frame_key = Some(key);
        self.render_count = self.render_count.saturating_add(1);
        Ok(self.stats_for_key(key, true, false, blur_enabled))
    }

    pub(crate) unsafe fn record_projection_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_index: usize,
        target_rect: TargetRect,
        projection_settings: NativeProjectionBorderStretchSettings,
        blur_enabled: bool,
    ) {
        let Some(resources) = self.resources.as_ref() else {
            return;
        };
        let Some(eye) = resources.eyes.get(eye_index) else {
            return;
        };
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: extent.width as f32,
            height: extent.height as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = if projection_settings.peripheral_stretch_active() {
            [full_extent_scissor(extent)]
        } else {
            [target_rect_to_scissor(extent, target_rect)]
        };
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_bind_pipeline(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            resources.final_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            resources.final_pipeline_layout,
            0,
            &[if blur_enabled {
                eye.final_descriptor_set
            } else {
                eye.final_downsample_descriptor_set
            }],
            &[],
        );
        let projection_push = projection_settings.push_params();
        let push = GuideProjectionPush {
            target_rect: [
                target_rect.x,
                target_rect.y,
                target_rect.width,
                target_rect.height,
            ],
            params: [
                eye_index as f32,
                projection_push.params[0],
                projection_push.params[1],
                projection_push.params[2],
            ],
            stretch0: projection_push.stretch0,
            stretch1: [
                projection_push.stretch1[0],
                projection_push.stretch1[1],
                projection_push.stretch1[2],
                projection_push.stretch1[3],
            ],
            alpha: [projection_push.params[3], 0.0, 0.0, 0.0],
        };
        push_fragment_constants(device, cmd, resources.final_pipeline_layout, &push);
        device.cmd_draw(cmd, 3, 1, 0, 0);
    }

    unsafe fn record_blur_axis(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        resources: &GuideBlurGraphResources,
        descriptor_set: vk::DescriptorSet,
        framebuffer: vk::Framebuffer,
        image: vk::Image,
        texel_step: [f32; 4],
    ) {
        let extent = guide_extent(self.resolution);
        let [width, height] = self.resolution.extent();
        begin_guide_pass(device, cmd, resources.render_pass, framebuffer, extent);
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: width as f32,
            height: height as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = [vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent,
        }];
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_bind_pipeline(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            resources.blur_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            resources.blur_pipeline_layout,
            0,
            &[descriptor_set],
            &[],
        );
        push_fragment_constants(
            device,
            cmd,
            resources.blur_pipeline_layout,
            &GuideBlurPush { texel_step },
        );
        device.cmd_draw(cmd, 3, 1, 0, 0);
        device.cmd_end_render_pass(cmd);
        transition_guide_image_for_sampling(device, cmd, image);
    }

    unsafe fn ensure_resources(
        &mut self,
        device: &ash::Device,
        camera_descriptor_set_layout: vk::DescriptorSetLayout,
    ) -> Result<(), String> {
        if self
            .resources
            .as_ref()
            .map(|resources| {
                resources.camera_descriptor_set_layout == camera_descriptor_set_layout
                    && resources.resolution == self.resolution
            })
            .unwrap_or(false)
        {
            return Ok(());
        }
        if let Some(resources) = self.resources.take() {
            resources.destroy(device);
        }
        self.resources = Some(GuideBlurGraphResources::new(
            device,
            &self.memory_properties,
            self.color_format,
            self.projection_render_pass,
            camera_descriptor_set_layout,
            self.resolution,
        )?);
        self.last_frame_key = None;
        let [width, height] = self.resolution.extent();
        crate::marker(
            "guide-blur-graph",
            format!(
                "status=created guideGraphSupportedPaths=low-resolution-downsample-no-blur,low-resolution-two-phase-5tap-blur,camera-resolution-downsample-no-blur,camera-resolution-two-phase-5tap-blur guideGraphDefaultBlurEnabled=true guideGraphResolutionPolicy={} guideGraphDownsampleResolution={}x{} guideGraphHorizontalTaps={} guideGraphVerticalTaps={} guideGraphFinalProjectionSource=guide-texture cameraProjectionPath=metadata-target-guide-texture-final finalExternalHwbSamples=0 guideTextureSamples=1",
                self.resolution.marker_value(),
                width,
                height,
                GUIDE_BLUR_TAPS_PER_AXIS,
                GUIDE_BLUR_TAPS_PER_AXIS
            ),
        );
        Ok(())
    }

    fn stats_for_key(
        &self,
        key: GuideFrameKey,
        rendered: bool,
        cache_hit: bool,
        blur_enabled: bool,
    ) -> GuideBlurGraphFrameStats {
        GuideBlurGraphFrameStats {
            ready: true,
            rendered,
            cache_hit,
            blur_enabled,
            resolution: self.resolution,
            left_source_frame: key.left_source_frame,
            right_source_frame: key.right_source_frame,
            left_hardware_buffer_id: key.left_hardware_buffer_id,
            right_hardware_buffer_id: key.right_hardware_buffer_id,
            render_count: self.render_count,
            cache_hits: self.cache_hits,
        }
    }
}

struct GuideBlurGraphResources {
    camera_descriptor_set_layout: vk::DescriptorSetLayout,
    resolution: NativeGuideGraphResolution,
    render_pass: vk::RenderPass,
    sampler: vk::Sampler,
    sample_descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    camera_pipeline_layout: vk::PipelineLayout,
    blur_pipeline_layout: vk::PipelineLayout,
    final_pipeline_layout: vk::PipelineLayout,
    downsample_pipeline: vk::Pipeline,
    blur_pipeline: vk::Pipeline,
    final_pipeline: vk::Pipeline,
    eyes: Vec<GuideEyeResources>,
}

impl GuideBlurGraphResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        color_format: vk::Format,
        projection_render_pass: vk::RenderPass,
        camera_descriptor_set_layout: vk::DescriptorSetLayout,
        resolution: NativeGuideGraphResolution,
    ) -> Result<Self, String> {
        let render_pass = create_guide_render_pass(device, color_format)?;
        let sampler = match device.create_sampler(
            &vk::SamplerCreateInfo::default()
                .mag_filter(vk::Filter::LINEAR)
                .min_filter(vk::Filter::LINEAR)
                .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE),
            None,
        ) {
            Ok(sampler) => sampler,
            Err(error) => {
                device.destroy_render_pass(render_pass, None);
                return Err(format!("create guide blur sampler: {error}"));
            }
        };

        let sample_descriptor_set_layout = match create_sample_descriptor_set_layout(device) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_sampler(sampler, None);
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let descriptor_pool = match create_descriptor_pool(device) {
            Ok(pool) => pool,
            Err(error) => {
                device.destroy_descriptor_set_layout(sample_descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };

        let camera_pipeline_layout = match create_pipeline_layout::<GuideDownsamplePush>(
            device,
            &[camera_descriptor_set_layout],
        ) {
            Ok(layout) => layout,
            Err(error) => {
                destroy_descriptor_scaffold(
                    device,
                    descriptor_pool,
                    sample_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                return Err(format!("create guide camera pipeline layout: {error}"));
            }
        };
        let blur_pipeline_layout = match create_pipeline_layout::<GuideBlurPush>(
            device,
            &[sample_descriptor_set_layout],
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_pipeline_layout(camera_pipeline_layout, None);
                destroy_descriptor_scaffold(
                    device,
                    descriptor_pool,
                    sample_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                return Err(format!("create guide blur pipeline layout: {error}"));
            }
        };
        let final_pipeline_layout = match create_pipeline_layout::<GuideProjectionPush>(
            device,
            &[sample_descriptor_set_layout],
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_pipeline_layout(blur_pipeline_layout, None);
                device.destroy_pipeline_layout(camera_pipeline_layout, None);
                destroy_descriptor_scaffold(
                    device,
                    descriptor_pool,
                    sample_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                return Err(format!("create guide final pipeline layout: {error}"));
            }
        };

        let downsample_pipeline = match create_graphics_pipeline(
            device,
            render_pass,
            camera_pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/camera_projection.vert.spv")),
            include_bytes!(concat!(env!("OUT_DIR"), "/guide_blur_downsample.frag.spv")),
            false,
            "guide blur downsample",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                destroy_layout_scaffold(
                    device,
                    final_pipeline_layout,
                    blur_pipeline_layout,
                    camera_pipeline_layout,
                    descriptor_pool,
                    sample_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                return Err(error);
            }
        };
        let blur_pipeline = match create_graphics_pipeline(
            device,
            render_pass,
            blur_pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/camera_projection.vert.spv")),
            include_bytes!(concat!(env!("OUT_DIR"), "/guide_blur_5tap.frag.spv")),
            false,
            "guide blur 5tap",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(downsample_pipeline, None);
                destroy_layout_scaffold(
                    device,
                    final_pipeline_layout,
                    blur_pipeline_layout,
                    camera_pipeline_layout,
                    descriptor_pool,
                    sample_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                return Err(error);
            }
        };
        let final_pipeline = match create_graphics_pipeline(
            device,
            projection_render_pass,
            final_pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/camera_projection.vert.spv")),
            include_bytes!(concat!(env!("OUT_DIR"), "/guide_projection.frag.spv")),
            true,
            "guide texture projection",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(blur_pipeline, None);
                device.destroy_pipeline(downsample_pipeline, None);
                destroy_layout_scaffold(
                    device,
                    final_pipeline_layout,
                    blur_pipeline_layout,
                    camera_pipeline_layout,
                    descriptor_pool,
                    sample_descriptor_set_layout,
                    sampler,
                    render_pass,
                );
                return Err(error);
            }
        };

        let mut eyes = Vec::with_capacity(GUIDE_EYE_COUNT);
        for eye_index in 0..GUIDE_EYE_COUNT {
            match GuideEyeResources::new(
                device,
                memory_properties,
                color_format,
                render_pass,
                descriptor_pool,
                sample_descriptor_set_layout,
                sampler,
                eye_index,
                resolution,
            ) {
                Ok(eye) => eyes.push(eye),
                Err(error) => {
                    for eye in eyes.drain(..) {
                        eye.destroy(device);
                    }
                    device.destroy_pipeline(final_pipeline, None);
                    device.destroy_pipeline(blur_pipeline, None);
                    device.destroy_pipeline(downsample_pipeline, None);
                    destroy_layout_scaffold(
                        device,
                        final_pipeline_layout,
                        blur_pipeline_layout,
                        camera_pipeline_layout,
                        descriptor_pool,
                        sample_descriptor_set_layout,
                        sampler,
                        render_pass,
                    );
                    return Err(error);
                }
            }
        }

        Ok(Self {
            camera_descriptor_set_layout,
            resolution,
            render_pass,
            sampler,
            sample_descriptor_set_layout,
            descriptor_pool,
            camera_pipeline_layout,
            blur_pipeline_layout,
            final_pipeline_layout,
            downsample_pipeline,
            blur_pipeline,
            final_pipeline,
            eyes,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        for eye in self.eyes {
            eye.destroy(device);
        }
        device.destroy_pipeline(self.final_pipeline, None);
        device.destroy_pipeline(self.blur_pipeline, None);
        device.destroy_pipeline(self.downsample_pipeline, None);
        device.destroy_pipeline_layout(self.final_pipeline_layout, None);
        device.destroy_pipeline_layout(self.blur_pipeline_layout, None);
        device.destroy_pipeline_layout(self.camera_pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.sample_descriptor_set_layout, None);
        device.destroy_sampler(self.sampler, None);
        device.destroy_render_pass(self.render_pass, None);
    }
}

struct GuideEyeResources {
    downsample: GuideImage,
    horizontal: GuideImage,
    vertical: GuideImage,
    source_descriptor_set: vk::DescriptorSet,
    ping_descriptor_set: vk::DescriptorSet,
    final_descriptor_set: vk::DescriptorSet,
    final_downsample_descriptor_set: vk::DescriptorSet,
}

impl GuideEyeResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        color_format: vk::Format,
        render_pass: vk::RenderPass,
        descriptor_pool: vk::DescriptorPool,
        descriptor_set_layout: vk::DescriptorSetLayout,
        sampler: vk::Sampler,
        eye_index: usize,
        resolution: NativeGuideGraphResolution,
    ) -> Result<Self, String> {
        let extent = guide_extent(resolution);
        let downsample = GuideImage::new(
            device,
            memory_properties,
            color_format,
            render_pass,
            extent,
            &format!("guide eye {eye_index} downsample"),
        )?;
        let horizontal = match GuideImage::new(
            device,
            memory_properties,
            color_format,
            render_pass,
            extent,
            &format!("guide eye {eye_index} horizontal"),
        ) {
            Ok(image) => image,
            Err(error) => {
                downsample.destroy(device);
                return Err(error);
            }
        };
        let vertical = match GuideImage::new(
            device,
            memory_properties,
            color_format,
            render_pass,
            extent,
            &format!("guide eye {eye_index} vertical"),
        ) {
            Ok(image) => image,
            Err(error) => {
                horizontal.destroy(device);
                downsample.destroy(device);
                return Err(error);
            }
        };

        let source_descriptor_set =
            match allocate_sample_descriptor_set(device, descriptor_pool, descriptor_set_layout) {
                Ok(set) => set,
                Err(error) => {
                    vertical.destroy(device);
                    horizontal.destroy(device);
                    downsample.destroy(device);
                    return Err(error);
                }
            };
        let ping_descriptor_set =
            match allocate_sample_descriptor_set(device, descriptor_pool, descriptor_set_layout) {
                Ok(set) => set,
                Err(error) => {
                    vertical.destroy(device);
                    horizontal.destroy(device);
                    downsample.destroy(device);
                    return Err(error);
                }
            };
        let final_descriptor_set =
            match allocate_sample_descriptor_set(device, descriptor_pool, descriptor_set_layout) {
                Ok(set) => set,
                Err(error) => {
                    vertical.destroy(device);
                    horizontal.destroy(device);
                    downsample.destroy(device);
                    return Err(error);
                }
            };
        let final_downsample_descriptor_set =
            match allocate_sample_descriptor_set(device, descriptor_pool, descriptor_set_layout) {
                Ok(set) => set,
                Err(error) => {
                    vertical.destroy(device);
                    horizontal.destroy(device);
                    downsample.destroy(device);
                    return Err(error);
                }
            };
        write_sample_descriptor(device, source_descriptor_set, sampler, downsample.view);
        write_sample_descriptor(device, ping_descriptor_set, sampler, horizontal.view);
        write_sample_descriptor(device, final_descriptor_set, sampler, vertical.view);
        write_sample_descriptor(
            device,
            final_downsample_descriptor_set,
            sampler,
            downsample.view,
        );

        Ok(Self {
            downsample,
            horizontal,
            vertical,
            source_descriptor_set,
            ping_descriptor_set,
            final_descriptor_set,
            final_downsample_descriptor_set,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        self.vertical.destroy(device);
        self.horizontal.destroy(device);
        self.downsample.destroy(device);
    }
}

struct GuideImage {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    framebuffer: vk::Framebuffer,
}

impl GuideImage {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        format: vk::Format,
        render_pass: vk::RenderPass,
        extent: vk::Extent2D,
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
                .width(extent.width)
                .height(extent.height)
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

#[repr(C)]
struct GuideDownsamplePush {
    params: [f32; 4],
}

#[repr(C)]
struct GuideBlurPush {
    texel_step: [f32; 4],
}

#[repr(C)]
struct GuideProjectionPush {
    target_rect: [f32; 4],
    params: [f32; 4],
    stretch0: [f32; 4],
    stretch1: [f32; 4],
    alpha: [f32; 4],
}

unsafe fn begin_guide_pass(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
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
        cmd,
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

unsafe fn create_guide_render_pass(
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
        .map_err(|error| format!("create guide blur render pass: {error}"))
}

fn create_sample_descriptor_set_layout(
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
            .map_err(|error| format!("create guide sample descriptor layout: {error}"))
    }
}

fn create_descriptor_pool(device: &ash::Device) -> Result<vk::DescriptorPool, String> {
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count((GUIDE_EYE_COUNT as u32) * 4)];
    unsafe {
        device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .pool_sizes(&pool_sizes)
                    .max_sets((GUIDE_EYE_COUNT as u32) * 4),
                None,
            )
            .map_err(|error| format!("create guide descriptor pool: {error}"))
    }
}

unsafe fn allocate_sample_descriptor_set(
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
        .map_err(|error| format!("allocate guide sample descriptor set: {error}"))
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

fn guide_extent(resolution: NativeGuideGraphResolution) -> vk::Extent2D {
    let [width, height] = resolution.extent();
    vk::Extent2D { width, height }
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

fn target_rect_to_scissor(extent: vk::Extent2D, rect: TargetRect) -> vk::Rect2D {
    let (x, width) = normalized_interval_to_pixels(extent.width, rect.x, rect.x + rect.width);
    let (y, height) = normalized_interval_to_pixels(extent.height, rect.y, rect.y + rect.height);
    vk::Rect2D {
        offset: vk::Offset2D { x, y },
        extent: vk::Extent2D { width, height },
    }
}

fn full_extent_scissor(extent: vk::Extent2D) -> vk::Rect2D {
    vk::Rect2D {
        offset: vk::Offset2D { x: 0, y: 0 },
        extent,
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
        "no Vulkan memory type supports {required:?} for guide blur graph"
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

unsafe fn destroy_descriptor_scaffold(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    sample_descriptor_set_layout: vk::DescriptorSetLayout,
    sampler: vk::Sampler,
    render_pass: vk::RenderPass,
) {
    device.destroy_descriptor_pool(descriptor_pool, None);
    device.destroy_descriptor_set_layout(sample_descriptor_set_layout, None);
    device.destroy_sampler(sampler, None);
    device.destroy_render_pass(render_pass, None);
}

unsafe fn destroy_layout_scaffold(
    device: &ash::Device,
    final_pipeline_layout: vk::PipelineLayout,
    blur_pipeline_layout: vk::PipelineLayout,
    camera_pipeline_layout: vk::PipelineLayout,
    descriptor_pool: vk::DescriptorPool,
    sample_descriptor_set_layout: vk::DescriptorSetLayout,
    sampler: vk::Sampler,
    render_pass: vk::RenderPass,
) {
    device.destroy_pipeline_layout(final_pipeline_layout, None);
    device.destroy_pipeline_layout(blur_pipeline_layout, None);
    device.destroy_pipeline_layout(camera_pipeline_layout, None);
    destroy_descriptor_scaffold(
        device,
        descriptor_pool,
        sample_descriptor_set_layout,
        sampler,
        render_pass,
    );
}
