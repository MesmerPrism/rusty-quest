use ash::vk;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};

use super::{
    begin_guide_pass, create_fullscreen_fragment_pipeline, create_guide_render_pass,
    SpatialPublicGuideTarget,
};
use crate::camera_hwb_wsi::create_color_render_pass;

include!(concat!(env!("OUT_DIR"), "/spatial_optional_color_build.rs"));

const WORD_COUNT: usize = 10;
pub(crate) const COMBINED: i32 = 3;
pub(super) const SOURCE_COLOR_AVAILABLE: bool = SOURCE_COLOR_SHADER_COMPILED;
static SOURCE_COLOR_DEVICE_ACTIVE: AtomicBool = AtomicBool::new(false);

pub(super) fn set_source_color_device_active(active: bool) {
    SOURCE_COLOR_DEVICE_ACTIVE.store(active, Ordering::Release);
}
type Words = [u32; WORD_COUNT];

#[derive(Clone, Copy, Eq, PartialEq)]
pub(crate) struct OptionalColorRequest {
    pub(crate) words: Words,
    pub(crate) placement: i32,
    revision: u64,
}

struct Control {
    requested: Option<OptionalColorRequest>,
    revision: u64,
    active_route: Option<u64>,
    effective: bool,
}
static CONTROL: Mutex<Control> = Mutex::new(Control {
    requested: None,
    revision: 0,
    active_route: None,
    effective: false,
});

pub(crate) fn set_optional_color_pass(placement: i32, words: Words) -> bool {
    let supported = crate::spatial_optional_color_policy::placement_supported(
        placement,
        SOURCE_COLOR_DEVICE_ACTIVE.load(Ordering::Acquire),
        OPTIONAL_COLOR_SHADER_COMPILED,
        CAMERA_COLOR_ABI,
    );
    if !supported {
        return false;
    }
    let mut control = CONTROL.lock().unwrap_or_else(|e| e.into_inner());
    let request = (placement != 0).then_some((placement, words));
    if control
        .requested
        .map(|request| (request.placement, request.words))
        != request
    {
        let Some(revision) = control.revision.checked_add(1) else {
            return false;
        };
        control.revision = revision;
        control.effective = false;
        control.requested = request.map(|(placement, words)| OptionalColorRequest {
            placement,
            words,
            revision,
        });
    }
    true
}

pub(crate) fn requested() -> Option<OptionalColorRequest> {
    CONTROL.lock().unwrap_or_else(|e| e.into_inner()).requested
}

pub(crate) struct ActiveRoute(u64);

impl ActiveRoute {
    pub(crate) fn begin(route_generation: u64) -> Self {
        let mut control = CONTROL.lock().unwrap_or_else(|e| e.into_inner());
        control.active_route = Some(route_generation);
        control.effective = false;
        Self(route_generation)
    }

    pub(crate) fn observe_retired(&self, recorded: Option<OptionalColorRequest>) {
        let mut control = CONTROL.lock().unwrap_or_else(|e| e.into_inner());
        if control.active_route == Some(self.0) {
            control.effective = recorded.is_some() && recorded == control.requested;
        }
    }
}

impl Drop for ActiveRoute {
    fn drop(&mut self) {
        let mut control = CONTROL.lock().unwrap_or_else(|e| e.into_inner());
        if control.active_route == Some(self.0) {
            control.active_route = None;
            control.effective = false;
        }
    }
}

#[cfg(target_os = "android")]
mod jni_bridge {
    use super::*;
    use jni::{
        objects::{JIntArray, JObject},
        sys::{jboolean, jint},
        JNIEnv,
    };

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeSetOptionalColorPass(
        env: JNIEnv<'_>,
        _: JObject<'_>,
        placement: jint,
        input: JIntArray<'_>,
    ) -> jboolean {
        let mut words = [0; WORD_COUNT];
        if env.get_array_length(&input).ok() != Some(WORD_COUNT as i32)
            || env.get_int_array_region(&input, 0, &mut words).is_err()
        {
            return 0;
        }
        u8::from(set_optional_color_pass(
            placement,
            words.map(|word| word as u32),
        ))
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeGetOptionalColorPassEffectivePlacement(
        _: JNIEnv<'_>,
        _: JObject<'_>,
    ) -> jint {
        let control = CONTROL.lock().unwrap_or_else(|e| e.into_inner());
        control
            .requested
            .filter(|_| control.effective)
            .map_or(0, |request| request.placement)
    }
}

pub(crate) struct OptionalColorPass {
    device: ash::Device,
    render_pass: vk::RenderPass,
    input: Option<SpatialPublicGuideTarget>,
    output_render_pass: vk::RenderPass,
    output: Option<SpatialPublicGuideTarget>,
    eyes: Vec<SpatialPublicGuideTarget>,
    sampler: vk::Sampler,
    layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    sets: Vec<vk::DescriptorSet>,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    extent: vk::Extent2D,
}

impl OptionalColorPass {
    pub(super) unsafe fn new(
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        format: vk::Format,
        extent: vk::Extent2D,
        presentation_pass: vk::RenderPass,
    ) -> Result<Self, String> {
        Self::new_with_input(device, memory, format, extent, presentation_pass, true)
    }

    unsafe fn new_with_input(
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        format: vk::Format,
        extent: vk::Extent2D,
        output_pass: vk::RenderPass,
        capture_presentation: bool,
    ) -> Result<Self, String> {
        if extent.width < 2 || extent.width % 2 != 0 || extent.height == 0 {
            return Err("optional-color-packed-extent-invalid".into());
        }
        // One owner for partially constructed resources, also used on normal retirement.
        let mut pass = Self {
            device: device.clone(),
            render_pass: vk::RenderPass::null(),
            input: None,
            output_render_pass: vk::RenderPass::null(),
            output: None,
            eyes: Vec::with_capacity(2),
            sampler: vk::Sampler::null(),
            layout: vk::DescriptorSetLayout::null(),
            pool: vk::DescriptorPool::null(),
            sets: Vec::new(),
            pipeline_layout: vk::PipelineLayout::null(),
            pipeline: vk::Pipeline::null(),
            extent,
        };
        let eye_render_pass = if capture_presentation {
            pass.render_pass = create_color_render_pass(
                device,
                format,
                vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL,
            )?;
            pass.input = Some(SpatialPublicGuideTarget::create(
                device,
                memory,
                pass.render_pass,
                extent,
                format,
                "optional-color-input",
            )?);
            pass.output_render_pass = create_guide_render_pass(device, format)?;
            pass.output = Some(SpatialPublicGuideTarget::create(
                device,
                memory,
                pass.output_render_pass,
                extent,
                format,
                "optional-camera-color-display",
            )?);
            pass.render_pass
        } else {
            output_pass
        };
        let eye_extent = pass.eye_extent();
        for _ in 0..2 {
            pass.eyes.push(SpatialPublicGuideTarget::create(
                device,
                memory,
                eye_render_pass,
                eye_extent,
                format,
                "optional-color-eye",
            )?);
        }
        pass.sampler = device
            .create_sampler(
                &vk::SamplerCreateInfo::default()
                    .mag_filter(vk::Filter::LINEAR)
                    .min_filter(vk::Filter::LINEAR)
                    .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE),
                None,
            )
            .map_err(|error| format!("optional-color-sampler-{error:?}"))?;
        let bindings = [0, 1].map(|binding| {
            vk::DescriptorSetLayoutBinding::default()
                .binding(binding)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        });
        pass.layout = device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|error| format!("optional-color-descriptor-layout-{error:?}"))?;
        let sizes = [vk::DescriptorPoolSize {
            ty: vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
            descriptor_count: 4,
        }];
        pass.pool = device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .max_sets(2)
                    .pool_sizes(&sizes),
                None,
            )
            .map_err(|error| format!("optional-color-descriptor-pool-{error:?}"))?;
        pass.sets = device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(pass.pool)
                    .set_layouts(&[pass.layout; 2]),
            )
            .map_err(|error| format!("optional-color-descriptor-sets-{error:?}"))?;
        for (target, &set) in pass.eyes.iter().zip(&pass.sets) {
            let image = [vk::DescriptorImageInfo::default()
                .sampler(pass.sampler)
                .image_view(target.image_view)
                .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
            let writes = [0, 1].map(|binding| {
                vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(binding)
                    .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                    .image_info(&image)
            });
            device.update_descriptor_sets(&writes, &[]);
        }
        let range = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::FRAGMENT)
            .size(std::mem::size_of::<Words>() as u32)];
        pass.pipeline_layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default()
                    .set_layouts(&[pass.layout])
                    .push_constant_ranges(&range),
                None,
            )
            .map_err(|error| format!("optional-color-pipeline-layout-{error:?}"))?;
        pass.pipeline = create_fullscreen_fragment_pipeline(
            device,
            if capture_presentation {
                pass.output_render_pass
            } else {
                output_pass
            },
            pass.pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/spatial_optional_color.frag.spv")),
            "optional-color",
            false,
        )?;
        Ok(pass)
    }

    fn eye_extent(&self) -> vk::Extent2D {
        vk::Extent2D {
            width: self.extent.width / 2,
            height: self.extent.height,
        }
    }

    pub(crate) fn target(&self) -> (vk::RenderPass, vk::Framebuffer) {
        (self.render_pass, self.input.as_ref().unwrap().framebuffer)
    }

    pub(crate) unsafe fn begin_capture(&self, command: vk::CommandBuffer) {
        let (render_pass, framebuffer) = self.target();
        begin_guide_pass(&self.device, command, render_pass, framebuffer, self.extent);
    }

    pub(crate) fn display_view(&self) -> vk::ImageView {
        self.output
            .as_ref()
            .expect("camera color output")
            .image_view
    }

    /// Initialize the bound optional sampler before the capture draw. The capture
    /// branch cannot sample it; a real clear still gives every binding a legal
    /// shader-readable layout on the first frame and after interrupted recording.
    pub(crate) unsafe fn clear_display(&self, command: vk::CommandBuffer) {
        begin_guide_pass(
            &self.device,
            command,
            self.output_render_pass,
            self.output
                .as_ref()
                .expect("camera color output")
                .framebuffer,
            self.extent,
        );
        self.device.cmd_end_render_pass(command);
    }

    /// Only camera content enters this pass. Background and region composition
    /// happen afterwards in the normal projection draw.
    pub(crate) unsafe fn record_display(
        &self,
        command: vk::CommandBuffer,
        words: Words,
        elapsed: f32,
    ) {
        self.prepare_eyes(command);
        begin_guide_pass(
            &self.device,
            command,
            self.output_render_pass,
            self.output
                .as_ref()
                .expect("camera color output")
                .framebuffer,
            self.extent,
        );
        self.draw(command, words, elapsed);
        self.device.cmd_end_render_pass(command);
    }

    // The input was just drawn. Each eye is copied to its own clamped texture.
    // Both destinations are fully replaced on each draw, so their previous
    // contents may be discarded after the shared WSI fence retires.
    pub(crate) unsafe fn prepare_eyes(&self, command: vk::CommandBuffer) {
        self.copy_eyes(
            command,
            self.input.as_ref().unwrap().image,
            PackedInput::ColorAttachment,
        );
    }

    unsafe fn copy_eyes(&self, command: vk::CommandBuffer, source: vk::Image, input: PackedInput) {
        let device = &self.device;
        let layers = vk::ImageSubresourceLayers::default()
            .aspect_mask(vk::ImageAspectFlags::COLOR)
            .layer_count(1);
        let range = vk::ImageSubresourceRange::default()
            .aspect_mask(vk::ImageAspectFlags::COLOR)
            .level_count(1)
            .layer_count(1);
        let image_barrier = |image| {
            vk::ImageMemoryBarrier::default()
                .image(image)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .subresource_range(range)
        };
        let (old_layout, previous_access, previous_stages) = match input {
            PackedInput::ColorAttachment => (
                vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL,
                vk::AccessFlags::COLOR_ATTACHMENT_WRITE,
                vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
            ),
            PackedInput::GuideSample => (
                vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL,
                vk::AccessFlags::COLOR_ATTACHMENT_WRITE | vk::AccessFlags::SHADER_READ,
                vk::PipelineStageFlags::ALL_GRAPHICS,
            ),
        };
        let source_barrier = image_barrier(source)
            .old_layout(old_layout)
            .new_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
            .src_access_mask(previous_access)
            .dst_access_mask(vk::AccessFlags::TRANSFER_READ);
        let mut before = vec![source_barrier];
        before.extend(self.eyes.iter().map(|target| {
            image_barrier(target.image)
                .old_layout(vk::ImageLayout::UNDEFINED)
                .new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        }));
        device.cmd_pipeline_barrier(
            command,
            previous_stages,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &before,
        );
        let extent = self.eye_extent();
        for (eye, target) in self.eyes.iter().enumerate() {
            let copy = vk::ImageCopy::default()
                .src_subresource(layers)
                .dst_subresource(layers)
                .src_offset(vk::Offset3D {
                    x: eye as i32 * extent.width as i32,
                    y: 0,
                    z: 0,
                })
                .extent(vk::Extent3D {
                    width: extent.width,
                    height: extent.height,
                    depth: 1,
                });
            device.cmd_copy_image(
                command,
                source,
                vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                target.image,
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                &[copy],
            );
        }
        let mut after = self
            .eyes
            .iter()
            .map(|target| {
                image_barrier(target.image)
                    .old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                    .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                    .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .dst_access_mask(vk::AccessFlags::SHADER_READ)
            })
            .collect::<Vec<_>>();
        let mut consumers = vk::PipelineStageFlags::FRAGMENT_SHADER;
        if matches!(input, PackedInput::GuideSample) {
            after.push(
                image_barrier(source)
                    .old_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
                    .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                    .src_access_mask(vk::AccessFlags::TRANSFER_READ)
                    .dst_access_mask(vk::AccessFlags::SHADER_READ),
            );
            consumers |= vk::PipelineStageFlags::VERTEX_SHADER;
        }
        device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::TRANSFER,
            consumers,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &after,
        );
    }

    pub(crate) unsafe fn draw(&self, command: vk::CommandBuffer, mut words: Words, elapsed: f32) {
        let device = &self.device;
        let extent = self.eye_extent();
        words[0] = elapsed.to_bits();
        words[3] = 0;
        words[7] = (extent.width as f32 / extent.height as f32).to_bits();
        words[9] = 0;
        let bytes =
            std::slice::from_raw_parts(words.as_ptr().cast::<u8>(), std::mem::size_of::<Words>());
        device.cmd_bind_pipeline(command, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
        device.cmd_push_constants(
            command,
            self.pipeline_layout,
            vk::ShaderStageFlags::FRAGMENT,
            0,
            bytes,
        );
        for (eye, &set) in self.sets.iter().enumerate() {
            let x = eye as i32 * extent.width as i32;
            device.cmd_set_viewport(
                command,
                0,
                &[vk::Viewport {
                    x: x as f32,
                    y: 0.0,
                    width: extent.width as f32,
                    height: extent.height as f32,
                    min_depth: 0.0,
                    max_depth: 1.0,
                }],
            );
            device.cmd_set_scissor(
                command,
                0,
                &[vk::Rect2D {
                    offset: vk::Offset2D { x, y: 0 },
                    extent,
                }],
            );
            device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::GRAPHICS,
                self.pipeline_layout,
                0,
                &[set],
                &[],
            );
            device.cmd_draw(command, 3, 1, 0, 0);
        }
    }
}

impl Drop for OptionalColorPass {
    fn drop(&mut self) {
        unsafe {
            let device = &self.device;
            device.destroy_pipeline(self.pipeline, None);
            device.destroy_pipeline_layout(self.pipeline_layout, None);
            device.destroy_descriptor_pool(self.pool, None);
            device.destroy_descriptor_set_layout(self.layout, None);
            device.destroy_sampler(self.sampler, None);
            for target in self.eyes.drain(..) {
                target.destroy(device);
            }
            if let Some(target) = self.input.take() {
                target.destroy(device);
            }
            if let Some(target) = self.output.take() {
                target.destroy(device);
            }
            device.destroy_render_pass(self.output_render_pass, None);
            device.destroy_render_pass(self.render_pass, None);
        }
    }
}

enum PackedInput {
    ColorAttachment,
    GuideSample,
}

/// One bank's display-only output. The original guide target keeps its pixels.
/// Allocate this only for a selected source-color route and retain it through
/// the same fence as that bank's other per-frame resources.
pub(super) struct SourceColorPass {
    draw: OptionalColorPass,
    display: Option<SpatialPublicGuideTarget>,
    guide_render_pass: vk::RenderPass,
}

impl SourceColorPass {
    pub(super) unsafe fn new(
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        format: vk::Format,
        extent: vk::Extent2D,
        guide_render_pass: vk::RenderPass,
    ) -> Result<Self, String> {
        let draw = OptionalColorPass::new_with_input(
            device,
            memory,
            format,
            extent,
            guide_render_pass,
            false,
        )?;
        let display = SpatialPublicGuideTarget::create(
            device,
            memory,
            guide_render_pass,
            extent,
            format,
            "optional-source-display",
        )?;
        Ok(Self {
            draw,
            display: Some(display),
            guide_render_pass,
        })
    }

    /// The caller supplies a completed, sampleable packed bank image of the
    /// same format and extent as the owned display target. Input and output
    /// must remain separate, and the guide render pass ends shader-readable.
    pub(super) unsafe fn record(
        &self,
        command: vk::CommandBuffer,
        source: &SpatialPublicGuideTarget,
        words: Words,
        elapsed: f32,
    ) {
        let target = self.display.as_ref().expect("source display target");
        self.draw
            .copy_eyes(command, source.image, PackedInput::GuideSample);
        begin_guide_pass(
            &self.draw.device,
            command,
            self.guide_render_pass,
            target.framebuffer,
            self.draw.extent,
        );
        self.draw.draw(command, words, elapsed);
        self.draw.device.cmd_end_render_pass(command);
    }

    pub(super) fn display_view(&self) -> vk::ImageView {
        self.display
            .as_ref()
            .expect("source display target")
            .image_view
    }
}

impl Drop for SourceColorPass {
    fn drop(&mut self) {
        if let Some(display) = self.display.take() {
            unsafe { display.destroy(&self.draw.device) };
        }
    }
}
