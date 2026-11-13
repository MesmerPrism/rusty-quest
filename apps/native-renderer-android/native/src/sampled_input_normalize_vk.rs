//! Neutral converted-input adapter, independent of the downstream effect graph.
//! The input layout is borrowed from its live import owner; it is never replaced
//! by a superficially identical layout lacking the immutable conversion sampler.
use super::*;

const MAX_SLOTS: usize = 12;
struct Slot {
    image: DisplayCompositeFeedbackImage,
    descriptor: vk::DescriptorSet,
    extent: vk::Extent2D,
}

pub(super) struct InputNormalizer {
    input_layout: vk::DescriptorSetLayout,
    pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    output_layout: vk::DescriptorSetLayout,
    sampler: vk::Sampler,
    pool: vk::DescriptorPool,
    slots: Vec<Option<Slot>>,
}

impl InputNormalizer {
    pub(super) unsafe fn new(
        device: &ash::Device,
        input_layout: vk::DescriptorSetLayout,
    ) -> Result<Self, String> {
        let mut result = Self {
            input_layout,
            pass: vk::RenderPass::null(),
            layout: vk::PipelineLayout::null(),
            pipeline: vk::Pipeline::null(),
            output_layout: vk::DescriptorSetLayout::null(),
            sampler: vk::Sampler::null(),
            pool: vk::DescriptorPool::null(),
            slots: Vec::new(),
        };
        let allocation = (|| {
            result.pass =
                create_display_composite_recursive_render_pass(device, vk::Format::R8G8B8A8_UNORM)?;
            // This exact layout retains the import owner's immutable sampler and
            // conversion. The adapter does not own or destroy that borrowed layout.
            result.layout = create_pipeline_layout(device, input_layout, 0, "input normalization")?;
            result.pipeline = create_display_composite_pipeline(
                device,
                result.pass,
                result.layout,
                DisplayCompositeFragmentShader::NormalizeInput,
            )?;
            result.output_layout =
                create_single_sampler_descriptor_set_layout(device, "normalized input output")?;
            result.sampler =
                create_sampler(device, vk::Filter::LINEAR, None, "normalized input sampler")?;
            result.pool = device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .max_sets(MAX_SLOTS as u32)
                        .pool_sizes(&[vk::DescriptorPoolSize::default()
                            .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                            .descriptor_count(MAX_SLOTS as u32)]),
                    None,
                )
                .map_err(|e| format!("normalized input descriptor pool: {e}"))?;
            Ok::<(), String>(())
        })();
        if let Err(e) = allocation {
            result.destroy(device);
            return Err(e);
        }
        Ok(result)
    }

    /// Caller has waited this slot's original submit fence. All fallible work
    /// precedes begin_render_pass, so rejection leaves a valid recording buffer.
    pub(super) unsafe fn record(
        &mut self,
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        cmd: vk::CommandBuffer,
        slot: usize,
        extent: vk::Extent2D,
        input_layout: vk::DescriptorSetLayout,
        input: vk::DescriptorSet,
    ) -> Result<vk::DescriptorSet, String> {
        if slot >= MAX_SLOTS || extent.width == 0 || extent.height == 0 {
            return Err("invalid normalized input slot/extent".into());
        }
        if input_layout != self.input_layout {
            return Err("normalized input owner layout changed".into());
        }
        while self.slots.len() <= slot {
            self.slots.push(None);
        }
        if let Some(current) = &self.slots[slot] {
            if current.extent != extent {
                return Err("normalized input extent changed within owner lifetime".into());
            }
        } else {
            let image = DisplayCompositeFeedbackImage::new(
                device,
                memory,
                vk::Format::R8G8B8A8_UNORM,
                self.pass,
                extent,
                "normalized input",
            )?;
            let descriptor = match device.allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(self.pool)
                    .set_layouts(&[self.output_layout]),
            ) {
                Ok(sets) => sets[0],
                Err(e) => {
                    image.destroy(device);
                    return Err(format!("normalized input descriptor: {e}"));
                }
            };
            write_single_sampler_descriptor(device, descriptor, self.sampler, image.view);
            self.slots[slot] = Some(Slot {
                image,
                descriptor,
                extent,
            });
        }
        let current = self.slots[slot].as_ref().unwrap();
        begin_recursive_feedback_pass(
            device,
            cmd,
            self.pass,
            current.image.framebuffer,
            extent,
            [0.; 4],
        );
        device.cmd_set_viewport(
            cmd,
            0,
            &[vk::Viewport {
                x: 0.,
                y: 0.,
                width: extent.width as f32,
                height: extent.height as f32,
                min_depth: 0.,
                max_depth: 1.,
            }],
        );
        device.cmd_set_scissor(
            cmd,
            0,
            &[vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent,
            }],
        );
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.layout,
            0,
            &[input],
            &[],
        );
        device.cmd_draw(cmd, 3, 1, 0, 0);
        device.cmd_end_render_pass(cmd);
        // Render-pass dependency: converted attachment write -> graph fragment read.
        // No AHB release here: parent retains FOREIGN ownership/lease until ALL
        // final/witness consumers finish and its original submit fence retires.
        Ok(current.descriptor)
    }

    pub(super) unsafe fn destroy(self, device: &ash::Device) {
        for slot in self.slots.into_iter().flatten() {
            slot.image.destroy(device);
        }
        if self.pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.pool, None);
        }
        if self.sampler != vk::Sampler::null() {
            device.destroy_sampler(self.sampler, None);
        }
        if self.output_layout != vk::DescriptorSetLayout::null() {
            device.destroy_descriptor_set_layout(self.output_layout, None);
        }
        if self.pipeline != vk::Pipeline::null() {
            device.destroy_pipeline(self.pipeline, None);
        }
        if self.layout != vk::PipelineLayout::null() {
            device.destroy_pipeline_layout(self.layout, None);
        }
        if self.pass != vk::RenderPass::null() {
            device.destroy_render_pass(self.pass, None);
        }
    }
}
