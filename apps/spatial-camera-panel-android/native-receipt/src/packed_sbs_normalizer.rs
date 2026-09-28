//! GPU-only packed side-by-side input normalization for the common camera graph.

use std::{ffi::CString, mem, slice};

use ash::vk;

use crate::ahardware_buffer_vulkan::{
    create_ahb_sampler_ycbcr_conversion, transition_ahb_sampled_image_to_shader_read,
    AhbVulkanFormatKey, AhbVulkanSampledImage,
};
use crate::peer_projection_ingress::PACKED_SBS_YCBCR_DESCRIPTOR_POOL_SLOTS;

pub(crate) const NORMALIZED_EYE_FORMAT: vk::Format = vk::Format::R8G8B8A8_UNORM;

struct EyeTarget {
    image: vk::Image,
    memory: vk::DeviceMemory,
    image_view: vk::ImageView,
    framebuffer: vk::Framebuffer,
}

struct PackedSbsNormalizerBuild {
    render_pass: Option<vk::RenderPass>,
    descriptor_set_layout: Option<vk::DescriptorSetLayout>,
    descriptor_pool: Option<vk::DescriptorPool>,
    pipeline_layout: Option<vk::PipelineLayout>,
    pipeline: Option<vk::Pipeline>,
    sampler: Option<vk::Sampler>,
    sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    eyes: [Option<EyeTarget>; 2],
}

impl PackedSbsNormalizerBuild {
    fn new() -> Self {
        Self {
            render_pass: None,
            descriptor_set_layout: None,
            descriptor_pool: None,
            pipeline_layout: None,
            pipeline: None,
            sampler: None,
            sampler_ycbcr_conversion: None,
            eyes: [None, None],
        }
    }

    unsafe fn destroy(self, device: &ash::Device) {
        if let Some(pipeline) = self.pipeline {
            device.destroy_pipeline(pipeline, None);
        }
        if let Some(layout) = self.pipeline_layout {
            device.destroy_pipeline_layout(layout, None);
        }
        for eye in self.eyes.into_iter().flatten() {
            eye.destroy(device);
        }
        if let Some(render_pass) = self.render_pass {
            device.destroy_render_pass(render_pass, None);
        }
        if let Some(pool) = self.descriptor_pool {
            device.destroy_descriptor_pool(pool, None);
        }
        if let Some(layout) = self.descriptor_set_layout {
            device.destroy_descriptor_set_layout(layout, None);
        }
        if let Some(sampler) = self.sampler {
            device.destroy_sampler(sampler, None);
        }
        if let Some(conversion) = self.sampler_ycbcr_conversion {
            device.destroy_sampler_ycbcr_conversion(conversion, None);
        }
    }
}

pub(crate) struct PackedSbsNormalizer {
    extent: vk::Extent2D,
    render_pass: vk::RenderPass,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    sampler: vk::Sampler,
    sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    eyes: [EyeTarget; 2],
    outputs_initialized: bool,
    source_bottom_up: bool,
}

impl PackedSbsNormalizer {
    pub(crate) unsafe fn create(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        packed_width: u32,
        packed_height: u32,
        source_format_key: AhbVulkanFormatKey,
        source_format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
    ) -> Result<Self, String> {
        if packed_width < 2 || packed_height == 0 || packed_width % 2 != 0 {
            return Err(format!(
                "packed-sbs-invalid-extent-{}x{}",
                packed_width, packed_height
            ));
        }
        let extent = vk::Extent2D {
            width: packed_width / 2,
            height: packed_height,
        };
        let conversion = create_ahb_sampler_ycbcr_conversion(
            device,
            source_format_key,
            source_format_props,
            "packed-sbs-normalizer",
        )?;
        let conversion_handle = conversion.as_ref().map(|value| value.handle);
        let filter = conversion
            .as_ref()
            .map(|value| value.metadata.sampler_filter)
            .unwrap_or(vk::Filter::LINEAR);
        let mut conversion_info = vk::SamplerYcbcrConversionInfo::default();
        let mut sampler_info = vk::SamplerCreateInfo::default()
            .mag_filter(filter)
            .min_filter(filter)
            .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
            .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE);
        if let Some(handle) = conversion_handle {
            conversion_info = conversion_info.conversion(handle);
            sampler_info = sampler_info.push_next(&mut conversion_info);
        }
        let mut build = PackedSbsNormalizerBuild::new();
        build.sampler_ycbcr_conversion = conversion_handle;
        let result = (|| -> Result<Self, String> {
            let sampler = device
                .create_sampler(&sampler_info, None)
                .map_err(|error| format!("create-packed-sbs-sampler-{error:?}"))?;
            build.sampler = Some(sampler);
            let immutable_samplers = [sampler];
            let mut binding = vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT);
            if conversion_handle.is_some() {
                binding = binding.immutable_samplers(&immutable_samplers);
            }
            let descriptor_set_layout = device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default()
                        .bindings(slice::from_ref(&binding)),
                    None,
                )
                .map_err(|error| format!("create-packed-sbs-descriptor-layout-{error:?}"))?;
            build.descriptor_set_layout = Some(descriptor_set_layout);
            let descriptor_pool = device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .pool_sizes(&[vk::DescriptorPoolSize::default()
                            .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                            // A combined image sampler backed by YCbCr conversion may
                            // consume multiple implementation descriptors. Three is the
                            // conservative Vulkan minimum-bound allocation for one binding.
                            .descriptor_count(PACKED_SBS_YCBCR_DESCRIPTOR_POOL_SLOTS)])
                        .max_sets(1),
                    None,
                )
                .map_err(|error| format!("create-packed-sbs-descriptor-pool-{error:?}"))?;
            build.descriptor_pool = Some(descriptor_pool);
            let descriptor_set = device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(descriptor_pool)
                        .set_layouts(&[descriptor_set_layout]),
                )
                .map_err(|error| format!("allocate-packed-sbs-descriptor-set-{error:?}"))?
                .pop()
                .ok_or_else(|| "allocate-packed-sbs-descriptor-set-empty".to_string())?;

            let render_pass = create_render_pass(device)?;
            build.render_pass = Some(render_pass);
            let left = EyeTarget::create(device, memory_properties, render_pass, extent, "left")?;
            build.eyes[0] = Some(left);
            let right = EyeTarget::create(device, memory_properties, render_pass, extent, "right")?;
            build.eyes[1] = Some(right);
            let layouts = [descriptor_set_layout];
            let push_ranges = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::FRAGMENT)
                .offset(0)
                .size(mem::size_of::<[u32; 2]>() as u32)];
            let pipeline_layout = device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&layouts)
                        .push_constant_ranges(&push_ranges),
                    None,
                )
                .map_err(|error| format!("create-packed-sbs-pipeline-layout-{error:?}"))?;
            build.pipeline_layout = Some(pipeline_layout);
            let pipeline = create_pipeline(device, render_pass, pipeline_layout)?;
            build.pipeline = Some(pipeline);
            Ok(Self {
                extent,
                render_pass: build.render_pass.take().expect("render pass initialized"),
                descriptor_set_layout: build
                    .descriptor_set_layout
                    .take()
                    .expect("descriptor layout initialized"),
                descriptor_pool: build
                    .descriptor_pool
                    .take()
                    .expect("descriptor pool initialized"),
                descriptor_set,
                pipeline_layout: build
                    .pipeline_layout
                    .take()
                    .expect("pipeline layout initialized"),
                pipeline: build.pipeline.take().expect("pipeline initialized"),
                sampler: build.sampler.take().expect("sampler initialized"),
                sampler_ycbcr_conversion: build.sampler_ycbcr_conversion.take(),
                eyes: [
                    build.eyes[0].take().expect("left eye initialized"),
                    build.eyes[1].take().expect("right eye initialized"),
                ],
                outputs_initialized: false,
                source_bottom_up: false,
            })
        })();
        if result.is_err() {
            build.destroy(device);
        }
        result
    }

    // GL-rendered Own AHBs and decoder Peer AImages have distinct raster origins.
    pub(crate) fn set_source_bottom_up(&mut self, bottom_up: bool) {
        self.source_bottom_up = bottom_up;
    }

    pub(crate) fn extent(&self) -> vk::Extent2D {
        self.extent
    }

    pub(crate) fn image_views(&self) -> [vk::ImageView; 2] {
        [self.eyes[0].image_view, self.eyes[1].image_view]
    }

    pub(crate) fn images(&self) -> [vk::Image; 2] {
        [self.eyes[0].image, self.eyes[1].image]
    }

    pub(crate) fn source_sampler_ycbcr_conversion(&self) -> Option<vk::SamplerYcbcrConversion> {
        self.sampler_ycbcr_conversion
    }

    pub(crate) unsafe fn update_source(
        &mut self,
        device: &ash::Device,
        source_image_view: vk::ImageView,
    ) {
        let image_info = [vk::DescriptorImageInfo::default()
            .sampler(self.sampler)
            .image_view(source_image_view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
        let writes = [vk::WriteDescriptorSet::default()
            .dst_set(self.descriptor_set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&image_info)];
        device.update_descriptor_sets(&writes, &[]);
    }

    pub(crate) unsafe fn record(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        packed_source: &AhbVulkanSampledImage,
        transition_source: bool,
    ) {
        if transition_source {
            transition_ahb_sampled_image_to_shader_read(
                device,
                command_buffer,
                packed_source.image,
            );
        }
        let old_layout = if self.outputs_initialized {
            vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL
        } else {
            vk::ImageLayout::UNDEFINED
        };
        let barriers = self.eyes.each_ref().map(|eye| {
            vk::ImageMemoryBarrier::default()
                .old_layout(old_layout)
                .new_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)
                .src_access_mask(if self.outputs_initialized {
                    vk::AccessFlags::SHADER_READ
                } else {
                    vk::AccessFlags::empty()
                })
                .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                .image(eye.image)
                .subresource_range(color_range())
        });
        device.cmd_pipeline_barrier(
            command_buffer,
            if self.outputs_initialized {
                vk::PipelineStageFlags::FRAGMENT_SHADER
            } else {
                vk::PipelineStageFlags::TOP_OF_PIPE
            },
            vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &barriers,
        );
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
            &[self.descriptor_set],
            &[],
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
        for eye_index in 0_u32..2 {
            let clear = [vk::ClearValue {
                color: vk::ClearColorValue {
                    float32: [0.0, 0.0, 0.0, 1.0],
                },
            }];
            device.cmd_begin_render_pass(
                command_buffer,
                &vk::RenderPassBeginInfo::default()
                    .render_pass(self.render_pass)
                    .framebuffer(self.eyes[eye_index as usize].framebuffer)
                    .render_area(scissor[0])
                    .clear_values(&clear),
                vk::SubpassContents::INLINE,
            );
            let words = [eye_index, u32::from(self.source_bottom_up)];
            let push = slice::from_raw_parts(
                words.as_ptr().cast::<u8>(),
                mem::size_of::<[u32; 2]>(),
            );
            device.cmd_push_constants(
                command_buffer,
                self.pipeline_layout,
                vk::ShaderStageFlags::FRAGMENT,
                0,
                push,
            );
            device.cmd_draw(command_buffer, 3, 1, 0, 0);
            device.cmd_end_render_pass(command_buffer);
        }
        let ready_barriers = self.eyes.each_ref().map(|eye| {
            vk::ImageMemoryBarrier::default()
                .old_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)
                .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)
                .image(eye.image)
                .subresource_range(color_range())
        });
        device.cmd_pipeline_barrier(
            command_buffer,
            vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
            vk::PipelineStageFlags::FRAGMENT_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &ready_barriers,
        );
        self.outputs_initialized = true;
    }

    pub(crate) unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        for eye in self.eyes {
            eye.destroy(device);
        }
        device.destroy_render_pass(self.render_pass, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        device.destroy_sampler(self.sampler, None);
        if let Some(conversion) = self.sampler_ycbcr_conversion {
            device.destroy_sampler_ycbcr_conversion(conversion, None);
        }
    }
}

impl EyeTarget {
    unsafe fn create(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        extent: vk::Extent2D,
        side: &str,
    ) -> Result<Self, String> {
        let image = device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(NORMALIZED_EYE_FORMAT)
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
            .map_err(|error| format!("create-packed-sbs-{side}-image-{error:?}"))?;
        let requirements = device.get_image_memory_requirements(image);
        let memory_type_index = match find_memory_type_index(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        ) {
            Some(index) => index,
            None => {
                device.destroy_image(image, None);
                return Err(format!("packed-sbs-{side}-memory-type-unavailable"));
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
                return Err(format!("allocate-packed-sbs-{side}-memory-{error:?}"));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("bind-packed-sbs-{side}-memory-{error:?}"));
        }
        let image_view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(NORMALIZED_EYE_FORMAT)
                .subresource_range(color_range()),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.destroy_image(image, None);
                device.free_memory(memory, None);
                return Err(format!("create-packed-sbs-{side}-view-{error:?}"));
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
                return Err(format!("create-packed-sbs-{side}-framebuffer-{error:?}"));
            }
        };
        Ok(Self {
            image,
            memory,
            image_view,
            framebuffer,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_framebuffer(self.framebuffer, None);
        device.destroy_image_view(self.image_view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

unsafe fn create_render_pass(device: &ash::Device) -> Result<vk::RenderPass, String> {
    let attachment = [vk::AttachmentDescription::default()
        .format(NORMALIZED_EYE_FORMAT)
        .samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR)
        .store_op(vk::AttachmentStoreOp::STORE)
        .initial_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)
        .final_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)];
    let color_ref = [vk::AttachmentReference {
        attachment: 0,
        layout: vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL,
    }];
    let subpass = [vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_ref)];
    device
        .create_render_pass(
            &vk::RenderPassCreateInfo::default()
                .attachments(&attachment)
                .subpasses(&subpass),
            None,
        )
        .map_err(|error| format!("create-packed-sbs-render-pass-{error:?}"))
}

unsafe fn create_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vertex = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/camera_hwb_probe.vert.spv")),
        "vertex",
    )?;
    let fragment = match create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/packed_sbs_normalize.frag.spv")),
        "fragment",
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex, None);
            return Err(error);
        }
    };
    let entry = CString::new("main").expect("static shader entry");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vertex)
            .name(&entry),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(fragment)
            .name(&entry),
    ];
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let raster = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let blend_attachments = [vk::PipelineColorBlendAttachmentState::default()
        .color_write_mask(vk::ColorComponentFlags::RGBA)];
    let blend = vk::PipelineColorBlendStateCreateInfo::default().attachments(&blend_attachments);
    let dynamic = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic_state = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic);
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
    let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let infos = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&raster)
        .multisample_state(&multisample)
        .color_blend_state(&blend)
        .dynamic_state(&dynamic_state)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0)];
    let result = device
        .create_graphics_pipelines(vk::PipelineCache::null(), &infos, None)
        .map_err(|(_, error)| format!("create-packed-sbs-pipeline-{error:?}"))
        .and_then(|mut pipelines| {
            pipelines
                .pop()
                .ok_or_else(|| "create-packed-sbs-pipeline-empty".to_string())
        });
    device.destroy_shader_module(fragment, None);
    device.destroy_shader_module(vertex, None);
    result
}

unsafe fn create_shader_module(
    device: &ash::Device,
    bytes: &[u8],
    stage: &str,
) -> Result<vk::ShaderModule, String> {
    if bytes.len() % mem::size_of::<u32>() != 0 {
        return Err(format!("packed-sbs-{stage}-shader-unaligned"));
    }
    let words = slice::from_raw_parts(bytes.as_ptr().cast::<u32>(), bytes.len() / 4);
    device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(words), None)
        .map_err(|error| format!("create-packed-sbs-{stage}-shader-{error:?}"))
}

fn color_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: 1,
    }
}

fn find_memory_type_index(
    properties: &vk::PhysicalDeviceMemoryProperties,
    supported_bits: u32,
    preferred: vk::MemoryPropertyFlags,
) -> Option<u32> {
    let count = properties
        .memory_type_count
        .min(properties.memory_types.len() as u32) as usize;
    properties.memory_types[..count]
        .iter()
        .enumerate()
        .find_map(|(index, memory_type)| {
            ((supported_bits & (1_u32 << index)) != 0
                && memory_type.property_flags.contains(preferred))
            .then_some(index as u32)
        })
}
