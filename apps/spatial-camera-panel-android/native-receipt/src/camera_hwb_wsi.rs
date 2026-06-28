use std::ffi::{CStr, CString};
use std::mem;

use ash::vk;

use crate::ahardware_buffer_vulkan::{
    create_ahb_sampler_ycbcr_conversion, import_ahb_sampled_image,
    query_ahb_vulkan_import_properties, transition_ahb_sampled_image_to_shader_read,
    AhbVulkanFormatKey, AhbVulkanSampledImage, AhbVulkanSampledImageCreateInfo,
};
use crate::bool_token;
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;
use crate::camera_hwb_probe::CameraHwbProbeMode;
use crate::camera_hwb_projection_target::{
    camera_hwb_projection_marker_fields, camera_hwb_projection_push, CameraHwbProjectionPush,
};
use crate::camera_hwb_stream::CameraProbeFrame;
use crate::spatial_public_multistack::public_multistack_marker_fields;
use crate::spatial_public_multistack_runtime::SpatialPublicGuideTargets;

pub(crate) struct CameraHwbProbeResources {
    pub(crate) sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    sampler: vk::Sampler,
    pub(crate) descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    pub(crate) descriptor_shape: &'static str,
}

impl CameraHwbProbeResources {
    pub(crate) unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        device.destroy_sampler(self.sampler, None);
        if let Some(conversion) = self.sampler_ycbcr_conversion {
            device.destroy_sampler_ycbcr_conversion(conversion, None);
        }
    }
}

pub(crate) unsafe fn create_camera_hwb_probe_resources(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    format_key: AhbVulkanFormatKey,
    format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
    mode: CameraHwbProbeMode,
) -> Result<CameraHwbProbeResources, String> {
    let sampler_ycbcr = create_ahb_sampler_ycbcr_conversion(
        device,
        format_key,
        format_props,
        "camera-hwb-spatial-probe",
    )?;
    let sampler_ycbcr_handle = sampler_ycbcr.as_ref().map(|conversion| conversion.handle);
    let sampler_ycbcr_metadata = sampler_ycbcr
        .as_ref()
        .map(|conversion| conversion.metadata.clone());
    let linear_supported = sampler_ycbcr_metadata
        .as_ref()
        .map(|metadata| metadata.sampler_linear_filter_supported)
        .unwrap_or_else(|| {
            format_props
                .format_features
                .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR)
        });
    let sampler_filter = sampler_ycbcr_metadata
        .as_ref()
        .map(|metadata| metadata.sampler_filter)
        .unwrap_or(if linear_supported {
            vk::Filter::LINEAR
        } else {
            vk::Filter::NEAREST
        });
    let mut sampler_conversion_info = vk::SamplerYcbcrConversionInfo::default();
    let mut sampler_info = vk::SamplerCreateInfo::default()
        .mag_filter(sampler_filter)
        .min_filter(sampler_filter)
        .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
        .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
        .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE);
    if let Some(conversion) = sampler_ycbcr_handle {
        sampler_conversion_info = sampler_conversion_info.conversion(conversion);
        sampler_info = sampler_info.push_next(&mut sampler_conversion_info);
    }
    let sampler = match device.create_sampler(&sampler_info, None) {
        Ok(sampler) => sampler,
        Err(error) => {
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create-camera-sampler-{error:?}"));
        }
    };

    let descriptor_uses_immutable_sampler = sampler_ycbcr_handle.is_some();
    let descriptor_binding_count = mode.descriptor_binding_count();
    let immutable_samplers = [sampler, sampler];
    let mut descriptor_bindings = Vec::with_capacity(descriptor_binding_count as usize);
    for binding_index in 0..descriptor_binding_count {
        let mut descriptor_binding = vk::DescriptorSetLayoutBinding::default()
            .binding(binding_index)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(1)
            .stage_flags(vk::ShaderStageFlags::FRAGMENT);
        if descriptor_uses_immutable_sampler {
            let sampler_index = binding_index as usize;
            descriptor_binding = descriptor_binding
                .immutable_samplers(&immutable_samplers[sampler_index..sampler_index + 1]);
        }
        descriptor_bindings.push(descriptor_binding);
    }
    let descriptor_set_layout = match device.create_descriptor_set_layout(
        &vk::DescriptorSetLayoutCreateInfo::default().bindings(&descriptor_bindings),
        None,
    ) {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create-camera-descriptor-set-layout-{error:?}"));
        }
    };
    let descriptor_pool = match device.create_descriptor_pool(
        &vk::DescriptorPoolCreateInfo::default()
            .pool_sizes(&[vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(descriptor_binding_count)])
            .max_sets(1),
        None,
    ) {
        Ok(pool) => pool,
        Err(error) => {
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create-camera-descriptor-pool-{error:?}"));
        }
    };
    let set_layouts = [descriptor_set_layout];
    let push_constant_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(mem::size_of::<CameraHwbProjectionPush>() as u32)];
    let pipeline_layout = match device.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default()
            .set_layouts(&set_layouts)
            .push_constant_ranges(&push_constant_ranges),
        None,
    ) {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create-camera-pipeline-layout-{error:?}"));
        }
    };
    let pipeline =
        match create_camera_hwb_probe_pipeline(device, render_pass, pipeline_layout, mode) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                if let Some(conversion) = sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                return Err(error);
            }
        };

    let descriptor_shape = if descriptor_uses_immutable_sampler && descriptor_binding_count == 2 {
        "dual-combined-immutable-sampler-ycbcr-conversion"
    } else if descriptor_uses_immutable_sampler {
        "single-combined-immutable-sampler-ycbcr-conversion"
    } else if descriptor_binding_count == 2 {
        "dual-combined-rgba-sampler"
    } else {
        "single-combined-rgba-sampler"
    };
    let ycbcr_fields = sampler_ycbcr_metadata
        .as_ref()
        .map(|metadata| metadata.marker_fields())
        .unwrap_or_else(|| "ahbSamplerYcbcrConversion=false".to_string());
    log_marker(format!(
        "status=probe-resources-created externalFormat={} vkFormat={:?} descriptorShape={} descriptorBindingCount={} samplerMode={} samplerFilter={:?} samplerLinearFilterSupported={} {} sampledCameraTexture=true sampledLeftCameraTexture=true sampledRightCameraTexture={} outputMode={} rawCameraProjectionProbe={} stereoSource={} {}",
        format_key.external_format,
        format_key.format,
        descriptor_shape,
        descriptor_binding_count,
        if format_key.external_format != 0 { "external-format-ycbcr" } else { "concrete-vk-format" },
        sampler_filter,
        linear_supported,
        ycbcr_fields,
        bool_token(matches!(mode, CameraHwbProbeMode::RawColorProjection)),
        mode.output_mode(),
        mode.raw_projection_token(),
        mode.stereo_source(),
        if matches!(mode, CameraHwbProbeMode::RawColorProjection) {
            format!(
                "{} {}",
                camera_hwb_projection_marker_fields(),
                public_multistack_marker_fields()
            )
        } else {
            "monoDuplicated=false publicMultiStackActive=false".to_string()
        },
    ));
    Ok(CameraHwbProbeResources {
        sampler_ycbcr_conversion: sampler_ycbcr_handle,
        sampler,
        descriptor_set_layout,
        descriptor_pool,
        pipeline_layout,
        pipeline,
        descriptor_shape,
    })
}

pub(crate) unsafe fn allocate_camera_hwb_probe_descriptor_set(
    device: &ash::Device,
    resources: &CameraHwbProbeResources,
    left_image_view: vk::ImageView,
    right_image_view: Option<vk::ImageView>,
    mode: CameraHwbProbeMode,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [resources.descriptor_set_layout];
    let descriptor_set = device
        .allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(resources.descriptor_pool)
                .set_layouts(&set_layouts),
        )
        .map_err(|error| format!("allocate-camera-descriptor-set-{error:?}"))?
        .pop()
        .ok_or_else(|| "allocate-camera-descriptor-set-empty".to_string())?;
    update_camera_hwb_probe_descriptor_set(
        device,
        resources,
        descriptor_set,
        left_image_view,
        right_image_view,
        mode,
    );
    Ok(descriptor_set)
}

pub(crate) unsafe fn update_camera_hwb_probe_descriptor_set(
    device: &ash::Device,
    resources: &CameraHwbProbeResources,
    descriptor_set: vk::DescriptorSet,
    left_image_view: vk::ImageView,
    right_image_view: Option<vk::ImageView>,
    mode: CameraHwbProbeMode,
) {
    let right_image_view = right_image_view.unwrap_or(left_image_view);
    let image_infos = [
        vk::DescriptorImageInfo::default()
            .sampler(resources.sampler)
            .image_view(left_image_view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL),
        vk::DescriptorImageInfo::default()
            .sampler(resources.sampler)
            .image_view(right_image_view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL),
    ];
    let mut writes = Vec::with_capacity(mode.descriptor_binding_count() as usize);
    writes.push(
        vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&image_infos[0..1]),
    );
    if mode.descriptor_binding_count() > 1 {
        writes.push(
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(1)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .image_info(&image_infos[1..2]),
        );
    }
    device.update_descriptor_sets(&writes, &[]);
}

pub(crate) unsafe fn import_replacement_camera_frame(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    ahb_device: &ash::android::external_memory_android_hardware_buffer::Device,
    resources: &CameraHwbProbeResources,
    expected_format_key: AhbVulkanFormatKey,
    frame: &CameraProbeFrame,
) -> Result<AhbVulkanSampledImage, String> {
    let (import_properties, _format_props) =
        query_ahb_vulkan_import_properties(ahb_device, &frame.hardware_buffer)?;
    if import_properties.format_key != expected_format_key {
        return Err(format!(
            "format-key-changed-expected-external-{}-vk-{:?}-actual-external-{}-vk-{:?}",
            expected_format_key.external_format,
            expected_format_key.format,
            import_properties.format_key.external_format,
            import_properties.format_key.format,
        ));
    }
    let sampled_image = import_ahb_sampled_image(
        device,
        memory_properties,
        &frame.hardware_buffer,
        AhbVulkanSampledImageCreateInfo {
            width: frame.descriptor.width.max(1),
            height: frame.descriptor.height.max(1),
            format_key: expected_format_key,
            allocation_size: import_properties.allocation_size,
            memory_type_bits: import_properties.memory_type_bits,
            sampler_ycbcr_conversion: resources.sampler_ycbcr_conversion,
            debug_label: if frame.side_label == "right" {
                "camera-hwb-raw-projection-right-frame"
            } else {
                "camera-hwb-raw-projection-left-frame"
            },
        },
    )?;
    Ok(sampled_image)
}

unsafe fn create_camera_hwb_probe_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
    mode: CameraHwbProbeMode,
) -> Result<vk::Pipeline, String> {
    let vert_module = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/camera_hwb_probe.vert.spv")),
    )?;
    let frag_module = match mode {
        CameraHwbProbeMode::LumaChecker => create_shader_module(
            device,
            include_bytes!(concat!(env!("OUT_DIR"), "/camera_hwb_probe.frag.spv")),
        )?,
        CameraHwbProbeMode::RawColorProjection => create_shader_module(
            device,
            include_bytes!(concat!(env!("OUT_DIR"), "/camera_hwb_raw_color.frag.spv")),
        )?,
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
        .color_write_mask(
            vk::ColorComponentFlags::R
                | vk::ColorComponentFlags::G
                | vk::ColorComponentFlags::B
                | vk::ColorComponentFlags::A,
        )
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
        .map_err(|(_, error)| format!("create-camera-pipeline-{error:?}"))?
        .remove(0);
    device.destroy_shader_module(frag_module, None);
    device.destroy_shader_module(vert_module, None);
    Ok(pipeline)
}

pub(crate) unsafe fn record_camera_hwb_probe_command_buffer(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
    resources: &CameraHwbProbeResources,
    descriptor_set: vk::DescriptorSet,
    sampled_left_image: &AhbVulkanSampledImage,
    sampled_right_image: Option<&AhbVulkanSampledImage>,
    transition_left_camera_image: bool,
    transition_right_camera_image: bool,
    public_guide_targets: Option<&mut SpatialPublicGuideTargets>,
    elapsed_seconds: f32,
) -> Result<bool, String> {
    device
        .reset_command_buffer(command_buffer, vk::CommandBufferResetFlags::empty())
        .map_err(|error| format!("reset-command-buffer-{error:?}"))?;
    device
        .begin_command_buffer(command_buffer, &vk::CommandBufferBeginInfo::default())
        .map_err(|error| format!("begin-command-buffer-{error:?}"))?;
    if transition_left_camera_image {
        transition_ahb_sampled_image_to_shader_read(
            device,
            command_buffer,
            sampled_left_image.image,
        );
    }
    if transition_right_camera_image {
        if let Some(sampled_right_image) = sampled_right_image {
            transition_ahb_sampled_image_to_shader_read(
                device,
                command_buffer,
                sampled_right_image.image,
            );
        }
    }
    let projected_by_public_stack = if let Some(public_guide_targets) = public_guide_targets {
        public_guide_targets.record_spatial_public_guide_passes(
            device,
            command_buffer,
            descriptor_set,
            elapsed_seconds,
        )?;
        public_guide_targets.record_spatial_public_projection(
            device,
            command_buffer,
            render_pass,
            framebuffer,
            extent,
            descriptor_set,
            elapsed_seconds,
        )?
    } else {
        false
    };
    if projected_by_public_stack {
        device
            .end_command_buffer(command_buffer)
            .map_err(|error| format!("end-command-buffer-{error:?}"))?;
        return Ok(true);
    }
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: [0.0, 0.0, 0.0, 0.0],
        },
    }];
    let render_area = vk::Rect2D {
        offset: vk::Offset2D { x: 0, y: 0 },
        extent,
    };
    let render_pass_info = vk::RenderPassBeginInfo::default()
        .render_pass(render_pass)
        .framebuffer(framebuffer)
        .render_area(render_area)
        .clear_values(&clear_values);
    device.cmd_begin_render_pass(
        command_buffer,
        &render_pass_info,
        vk::SubpassContents::INLINE,
    );
    let viewport = [vk::Viewport {
        x: 0.0,
        y: 0.0,
        width: extent.width as f32,
        height: extent.height as f32,
        min_depth: 0.0,
        max_depth: 1.0,
    }];
    let scissor = [vk::Rect2D {
        offset: vk::Offset2D { x: 0, y: 0 },
        extent,
    }];
    device.cmd_set_viewport(command_buffer, 0, &viewport);
    device.cmd_set_scissor(command_buffer, 0, &scissor);
    device.cmd_bind_pipeline(
        command_buffer,
        vk::PipelineBindPoint::GRAPHICS,
        resources.pipeline,
    );
    device.cmd_bind_descriptor_sets(
        command_buffer,
        vk::PipelineBindPoint::GRAPHICS,
        resources.pipeline_layout,
        0,
        &[descriptor_set],
        &[],
    );
    let projection_push = camera_hwb_projection_push();
    let projection_push_bytes = std::slice::from_raw_parts(
        (&projection_push as *const CameraHwbProjectionPush).cast::<u8>(),
        mem::size_of::<CameraHwbProjectionPush>(),
    );
    device.cmd_push_constants(
        command_buffer,
        resources.pipeline_layout,
        vk::ShaderStageFlags::FRAGMENT,
        0,
        projection_push_bytes,
    );
    device.cmd_draw(command_buffer, 3, 1, 0, 0);
    device.cmd_end_render_pass(command_buffer);
    device
        .end_command_buffer(command_buffer)
        .map_err(|error| format!("end-command-buffer-{error:?}"))?;
    Ok(false)
}

unsafe fn create_shader_module(
    device: &ash::Device,
    bytes: &[u8],
) -> Result<vk::ShaderModule, String> {
    if bytes.len() % mem::size_of::<u32>() != 0 {
        return Err("shader-bytes-not-u32-aligned".to_string());
    }
    let code = std::slice::from_raw_parts(
        bytes.as_ptr().cast::<u32>(),
        bytes.len() / mem::size_of::<u32>(),
    );
    device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(code), None)
        .map_err(|error| format!("create-shader-module-{error:?}"))
}

#[derive(Clone, Copy)]
pub(crate) struct CameraVulkanExtensionStatus {
    pub(crate) external_hwb_extension_ready: bool,
    pub(crate) sampler_ycbcr_extension_ready: bool,
    pub(crate) sampler_ycbcr_feature_ready: bool,
}

pub(crate) unsafe fn select_camera_surface_device(
    instance: &ash::Instance,
    surface_loader: &ash::khr::surface::Instance,
    surface: vk::SurfaceKHR,
    physical_devices: &[vk::PhysicalDevice],
) -> Option<(vk::PhysicalDevice, u32, CameraVulkanExtensionStatus)> {
    for physical_device in physical_devices {
        let external_hwb_extension_ready = physical_device_supports_extension(
            instance,
            *physical_device,
            ash::android::external_memory_android_hardware_buffer::NAME,
        );
        let sampler_ycbcr_extension_ready = physical_device_supports_extension(
            instance,
            *physical_device,
            ash::khr::sampler_ycbcr_conversion::NAME,
        );
        let mut sampler_ycbcr_features =
            vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
        let mut feature_query =
            vk::PhysicalDeviceFeatures2::default().push_next(&mut sampler_ycbcr_features);
        instance.get_physical_device_features2(*physical_device, &mut feature_query);
        let status = CameraVulkanExtensionStatus {
            external_hwb_extension_ready,
            sampler_ycbcr_extension_ready,
            sampler_ycbcr_feature_ready: sampler_ycbcr_features.sampler_ycbcr_conversion
                == vk::TRUE,
        };
        let queue_family_properties =
            instance.get_physical_device_queue_family_properties(*physical_device);
        for (index, family) in queue_family_properties.iter().enumerate() {
            let present_supported = surface_loader
                .get_physical_device_surface_support(*physical_device, index as u32, surface)
                .unwrap_or(false);
            if family.queue_flags.contains(vk::QueueFlags::GRAPHICS) && present_supported {
                return Some((*physical_device, index as u32, status));
            }
        }
    }
    None
}

unsafe fn physical_device_supports_extension(
    instance: &ash::Instance,
    physical_device: vk::PhysicalDevice,
    extension_name: &'static CStr,
) -> bool {
    instance
        .enumerate_device_extension_properties(physical_device)
        .map(|extensions| {
            extensions.iter().any(|extension| {
                let name = CStr::from_ptr(extension.extension_name.as_ptr());
                name == extension_name
            })
        })
        .unwrap_or(false)
}

pub(crate) fn choose_surface_format(formats: &[vk::SurfaceFormatKHR]) -> vk::SurfaceFormatKHR {
    formats
        .iter()
        .copied()
        .find(|format| {
            format.format == vk::Format::R8G8B8A8_UNORM
                && format.color_space == vk::ColorSpaceKHR::SRGB_NONLINEAR
        })
        .unwrap_or_else(|| {
            formats.first().copied().unwrap_or(vk::SurfaceFormatKHR {
                format: vk::Format::R8G8B8A8_UNORM,
                color_space: vk::ColorSpaceKHR::SRGB_NONLINEAR,
            })
        })
}

pub(crate) fn choose_present_mode(present_modes: &[vk::PresentModeKHR]) -> vk::PresentModeKHR {
    if present_modes.contains(&vk::PresentModeKHR::FIFO) {
        vk::PresentModeKHR::FIFO
    } else {
        present_modes
            .first()
            .copied()
            .unwrap_or(vk::PresentModeKHR::FIFO)
    }
}

pub(crate) fn choose_extent(
    capabilities: &vk::SurfaceCapabilitiesKHR,
    requested_width: u32,
    requested_height: u32,
) -> vk::Extent2D {
    if capabilities.current_extent.width != u32::MAX {
        return capabilities.current_extent;
    }
    let min = capabilities.min_image_extent;
    let max = capabilities.max_image_extent;
    vk::Extent2D {
        width: requested_width.clamp(min.width.max(1), max.width.max(min.width.max(1))),
        height: requested_height.clamp(min.height.max(1), max.height.max(min.height.max(1))),
    }
}

pub(crate) fn choose_image_count(capabilities: &vk::SurfaceCapabilitiesKHR) -> u32 {
    let requested = capabilities.min_image_count.saturating_add(1).max(2);
    if capabilities.max_image_count > 0 {
        requested.min(capabilities.max_image_count)
    } else {
        requested
    }
}

pub(crate) fn choose_composite_alpha(
    flags: vk::CompositeAlphaFlagsKHR,
) -> vk::CompositeAlphaFlagsKHR {
    for candidate in [
        vk::CompositeAlphaFlagsKHR::INHERIT,
        vk::CompositeAlphaFlagsKHR::OPAQUE,
        vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED,
        vk::CompositeAlphaFlagsKHR::POST_MULTIPLIED,
    ] {
        if flags.contains(candidate) {
            return candidate;
        }
    }
    vk::CompositeAlphaFlagsKHR::OPAQUE
}

pub(crate) unsafe fn create_image_views(
    device: &ash::Device,
    format: vk::Format,
    images: &[vk::Image],
) -> Result<Vec<vk::ImageView>, String> {
    images
        .iter()
        .map(|image| {
            device
                .create_image_view(
                    &vk::ImageViewCreateInfo::default()
                        .image(*image)
                        .view_type(vk::ImageViewType::TYPE_2D)
                        .format(format)
                        .subresource_range(vk::ImageSubresourceRange {
                            aspect_mask: vk::ImageAspectFlags::COLOR,
                            base_mip_level: 0,
                            level_count: 1,
                            base_array_layer: 0,
                            layer_count: 1,
                        }),
                    None,
                )
                .map_err(|error| format!("create-image-view-{error:?}"))
        })
        .collect()
}

pub(crate) unsafe fn create_render_pass(
    device: &ash::Device,
    format: vk::Format,
) -> Result<vk::RenderPass, String> {
    let color_attachment = [vk::AttachmentDescription::default()
        .format(format)
        .samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR)
        .store_op(vk::AttachmentStoreOp::STORE)
        .initial_layout(vk::ImageLayout::UNDEFINED)
        .final_layout(vk::ImageLayout::PRESENT_SRC_KHR)];
    let color_attachment_ref = [vk::AttachmentReference::default()
        .attachment(0)
        .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)];
    let subpass = [vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_attachment_ref)];
    let dependency = [vk::SubpassDependency::default()
        .src_subpass(vk::SUBPASS_EXTERNAL)
        .dst_subpass(0)
        .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)];
    device
        .create_render_pass(
            &vk::RenderPassCreateInfo::default()
                .attachments(&color_attachment)
                .subpasses(&subpass)
                .dependencies(&dependency),
            None,
        )
        .map_err(|error| format!("create-render-pass-{error:?}"))
}

pub(crate) unsafe fn create_framebuffers(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    extent: vk::Extent2D,
    image_views: &[vk::ImageView],
) -> Result<Vec<vk::Framebuffer>, String> {
    image_views
        .iter()
        .map(|view| {
            device
                .create_framebuffer(
                    &vk::FramebufferCreateInfo::default()
                        .render_pass(render_pass)
                        .attachments(&[*view])
                        .width(extent.width)
                        .height(extent.height)
                        .layers(1),
                    None,
                )
                .map_err(|error| format!("create-framebuffer-{error:?}"))
        })
        .collect()
}
