//! Vulkan resource owner for the external three-fragment bundle.
//! Child module deliberately reuses the feedback owner's image/pipeline helpers.
use super::*;

const MAX_SLOTS: usize = 12;
struct Slot {
    images: [DisplayCompositeFeedbackImage; 2],
    sets: [vk::DescriptorSet; 2],
    predicted_seconds: f32,
}
pub(super) struct FullCoreGraph {
    pass: vk::RenderPass,
    descriptor_layout: vk::DescriptorSetLayout,
    layout: vk::PipelineLayout,
    sampler: vk::Sampler,
    pool: vk::DescriptorPool,
    pipelines: [vk::Pipeline; 3],
    slots: Vec<Option<Slot>>,
}

impl FullCoreGraph {
    pub(super) unsafe fn new(
        device: &ash::Device,
        final_pass: vk::RenderPass,
    ) -> Result<Self, String> {
        let mut g = Self {
            pass: vk::RenderPass::null(),
            descriptor_layout: vk::DescriptorSetLayout::null(),
            layout: vk::PipelineLayout::null(),
            sampler: vk::Sampler::null(),
            pool: vk::DescriptorPool::null(),
            pipelines: [vk::Pipeline::null(); 3],
            slots: Vec::new(),
        };
        let result = (|| {
            g.pass =
                create_display_composite_recursive_render_pass(device, vk::Format::R8G8B8A8_UNORM)?;
            g.descriptor_layout =
                create_single_sampler_descriptor_set_layout(device, "FullCore3 descriptor layout")?;
            g.layout = create_pipeline_layout(
                device,
                g.descriptor_layout,
                128,
                "FullCore3 presentation128-v1",
            )?;
            g.sampler = create_sampler(device, vk::Filter::LINEAR, None, "FullCore3 sampler")?;
            g.pool = device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .max_sets((MAX_SLOTS * 2) as u32)
                        .pool_sizes(&[vk::DescriptorPoolSize::default()
                            .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                            .descriptor_count((MAX_SLOTS * 2) as u32)]),
                    None,
                )
                .map_err(|e| format!("FullCore3 descriptor pool: {e}"))?;
            for (i, fragment) in [
                DisplayCompositeFragmentShader::Full3Pack,
                DisplayCompositeFragmentShader::Full3Blur,
                DisplayCompositeFragmentShader::Full3Final,
            ]
            .into_iter()
            .enumerate()
            {
                g.pipelines[i] = create_display_composite_pipeline(
                    device,
                    if i == 2 { final_pass } else { g.pass },
                    g.layout,
                    fragment,
                )?;
            }
            Ok::<(), String>(())
        })();
        if let Err(e) = result {
            g.destroy(device);
            return Err(e);
        }
        Ok(g)
    }

    unsafe fn ensure_slot(
        &mut self,
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        index: usize,
    ) -> Result<(), String> {
        if index >= MAX_SLOTS {
            return Err("FullCore3 frame slot exceeds owned pool".into());
        }
        while self.slots.len() <= index {
            self.slots.push(None);
        }
        if self.slots[index].is_some() {
            return Ok(());
        }
        let extent = atlas_extent();
        let a = DisplayCompositeFeedbackImage::new(
            device,
            memory,
            vk::Format::R8G8B8A8_UNORM,
            self.pass,
            extent,
            "FullCore3 atlas A",
        )?;
        let b = match DisplayCompositeFeedbackImage::new(
            device,
            memory,
            vk::Format::R8G8B8A8_UNORM,
            self.pass,
            extent,
            "FullCore3 atlas B",
        ) {
            Ok(v) => v,
            Err(e) => {
                a.destroy(device);
                return Err(e);
            }
        };
        // One atomic descriptor allocation, so a partial allocation never consumes pool capacity.
        let sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(self.pool)
                .set_layouts(&[self.descriptor_layout; 2]),
        ) {
            Ok(v) => [v[0], v[1]],
            Err(e) => {
                b.destroy(device);
                a.destroy(device);
                return Err(format!("FullCore3 descriptors: {e}"));
            }
        };
        write_single_sampler_descriptor(device, sets[0], self.sampler, a.view);
        write_single_sampler_descriptor(device, sets[1], self.sampler, b.view);
        self.slots[index] = Some(Slot {
            images: [a, b],
            sets,
            predicted_seconds: 0.,
        });
        Ok(())
    }

    /// Called only after the owning XR frame-slot fence has completed. No atlas
    /// is shared by in-flight slots; AHB FOREIGN release stays after final/witness draws.
    pub(super) unsafe fn record(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        memory: &vk::PhysicalDeviceMemoryProperties,
        slot: usize,
        input: vk::DescriptorSet,
        frame: &crate::presentation_diagnostic::Frame,
    ) -> Result<(), String> {
        self.ensure_slot(device, memory, slot)?;
        let predicted_ns = frame.metadata["predicted_display_time_ns"]
            .as_i64()
            .ok_or("FullCore3 requires actual predicted display time")?;
        if predicted_ns <= 0 {
            return Err("FullCore3 predicted display time invalid".into());
        }
        self.slots[slot].as_mut().unwrap().predicted_seconds = (predicted_ns as f64 * 1e-9) as f32;
        let resources = self.slots[slot].as_ref().unwrap();
        for pass in 0..2 {
            begin_recursive_feedback_pass(
                device,
                cmd,
                self.pass,
                resources.images[pass].framebuffer,
                atlas_extent(),
                [0.; 4],
            );
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines[pass]);
            device.cmd_bind_descriptor_sets(
                cmd,
                vk::PipelineBindPoint::GRAPHICS,
                self.layout,
                0,
                &[if pass == 0 { input } else { resources.sets[0] }],
                &[],
            );
            for eye in 0..2 {
                let x = eye as u32 * crate::full_core_graph::EYE_STRIDE;
                device.cmd_set_viewport(
                    cmd,
                    0,
                    &[vk::Viewport {
                        x: x as f32,
                        y: 0.,
                        width: 1536.,
                        height: 1664.,
                        min_depth: 0.,
                        max_depth: 1.,
                    }],
                );
                device.cmd_set_scissor(
                    cmd,
                    0,
                    &[vk::Rect2D {
                        offset: vk::Offset2D { x: x as i32, y: 0 },
                        extent: vk::Extent2D {
                            width: 1536,
                            height: 1664,
                        },
                    }],
                );
                push(
                    device,
                    cmd,
                    self.layout,
                    eye,
                    &frame.eyes[eye],
                    resources.predicted_seconds,
                );
                device.cmd_draw(cmd, 3, 1, 0, 0);
            }
            device.cmd_end_render_pass(cmd);
            // Render-pass external dependency publishes COLOR_ATTACHMENT_WRITE to
            // FRAGMENT_SHADER/SHADER_READ before the next pass samples this atlas.
        }
        Ok(())
    }

    /// The pack pass's unprocessed eye rectangles remain intact in atlas A.
    /// Caller copies only after all graph draws, under this slot's original fence.
    pub(super) fn raw_pack_image(&self, slot: usize) -> Option<vk::Image> {
        self.slots.get(slot)?.as_ref().map(|s| s.images[0].image)
    }

    pub(super) unsafe fn record_final(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        extent: vk::Extent2D,
        eye: usize,
        presentation: &crate::presentation_diagnostic::Eye,
    ) {
        let target = TargetRect::new(
            presentation.target[0],
            presentation.target[1],
            presentation.target[2],
            presentation.target[3],
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
        device.cmd_set_scissor(cmd, 0, &[target_rect_to_scissor(extent, target)]);
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines[2]);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.layout,
            0,
            &[self.slots[slot].as_ref().unwrap().sets[1]],
            &[],
        );
        push(
            device,
            cmd,
            self.layout,
            eye,
            presentation,
            self.slots[slot].as_ref().unwrap().predicted_seconds,
        );
        device.cmd_draw(cmd, 3, 1, 0, 0);
    }

    pub(super) unsafe fn destroy(self, device: &ash::Device) {
        for slot in self.slots.into_iter().flatten() {
            for image in slot.images {
                image.destroy(device);
            }
        }
        for p in self.pipelines {
            if p != vk::Pipeline::null() {
                device.destroy_pipeline(p, None);
            }
        }
        if self.pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.pool, None);
        }
        if self.sampler != vk::Sampler::null() {
            device.destroy_sampler(self.sampler, None);
        }
        if self.layout != vk::PipelineLayout::null() {
            device.destroy_pipeline_layout(self.layout, None);
        }
        if self.descriptor_layout != vk::DescriptorSetLayout::null() {
            device.destroy_descriptor_set_layout(self.descriptor_layout, None);
        }
        if self.pass != vk::RenderPass::null() {
            device.destroy_render_pass(self.pass, None);
        }
    }
}
fn atlas_extent() -> vk::Extent2D {
    vk::Extent2D {
        width: crate::full_core_graph::ATLAS[0],
        height: crate::full_core_graph::ATLAS[1],
    }
}
unsafe fn push(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    layout: vk::PipelineLayout,
    eye: usize,
    p: &crate::presentation_diagnostic::Eye,
    predicted_seconds: f32,
) {
    let mut p = *p;
    p.origin[3] = predicted_seconds;
    push_display_constants(
        device,
        cmd,
        layout,
        DisplayCompositePush {
            target_rect: p.target,
            source_uv_rect: p.source_uv_rect,
            params0: [0., 1., 2., eye as f32],
        },
        Some(&p),
    );
}
