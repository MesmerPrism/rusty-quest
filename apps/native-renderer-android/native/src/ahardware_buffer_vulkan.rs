//! Reusable Vulkan import helpers for Android `AHardwareBuffer` images.
//!
//! Camera2, MediaProjection, and future Android image producers should share
//! this module for Vulkan ownership mechanics. Producer-specific policy such as
//! YCbCr selection, descriptor-set layout, cache keys, and render passes stays
//! with the consuming renderer.

use ash::vk;

use crate::android_hardware_buffer::AndroidHardwareBufferHandle;

pub(crate) type AhbVulkanDevice = ash::android::external_memory_android_hardware_buffer::Device;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct AhbVulkanFormatKey {
    pub(crate) format: vk::Format,
    pub(crate) external_format: u64,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct AhbVulkanImportProperties {
    pub(crate) allocation_size: vk::DeviceSize,
    pub(crate) memory_type_bits: u32,
    pub(crate) format_key: AhbVulkanFormatKey,
}

pub(crate) struct AhbVulkanSamplerYcbcrConversion {
    pub(crate) handle: vk::SamplerYcbcrConversion,
    pub(crate) metadata: AhbVulkanSamplerYcbcrConversionMetadata,
}

#[derive(Clone, Debug)]
pub(crate) struct AhbVulkanSamplerYcbcrConversionMetadata {
    pub(crate) format_features: vk::FormatFeatureFlags,
    pub(crate) chroma_filter: vk::Filter,
    pub(crate) chroma_linear_filter_supported: bool,
    pub(crate) sampler_filter: vk::Filter,
    pub(crate) sampler_linear_filter_supported: bool,
    pub(crate) suggested_model: vk::SamplerYcbcrModelConversion,
    pub(crate) suggested_range: vk::SamplerYcbcrRange,
    pub(crate) components: String,
    pub(crate) suggested_x_chroma_offset: vk::ChromaLocation,
    pub(crate) suggested_y_chroma_offset: vk::ChromaLocation,
}

impl AhbVulkanSamplerYcbcrConversionMetadata {
    pub(crate) fn from_format_props(
        format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
    ) -> Self {
        let chroma_linear_filter_supported = format_props
            .format_features
            .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER);
        let sampler_linear_filter_supported = format_props
            .format_features
            .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR);
        Self {
            format_features: format_props.format_features,
            chroma_filter: if chroma_linear_filter_supported {
                vk::Filter::LINEAR
            } else {
                vk::Filter::NEAREST
            },
            chroma_linear_filter_supported,
            sampler_filter: if sampler_linear_filter_supported {
                vk::Filter::LINEAR
            } else {
                vk::Filter::NEAREST
            },
            sampler_linear_filter_supported,
            suggested_model: format_props.suggested_ycbcr_model,
            suggested_range: format_props.suggested_ycbcr_range,
            components: ycbcr_component_mapping_label(
                format_props.sampler_ycbcr_conversion_components,
            ),
            suggested_x_chroma_offset: format_props.suggested_x_chroma_offset,
            suggested_y_chroma_offset: format_props.suggested_y_chroma_offset,
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "ahbSuggestedYcbcrModel={:?} ahbSuggestedYcbcrRange={:?} ahbYcbcrComponents={} ahbSuggestedXChromaOffset={:?} ahbSuggestedYChromaOffset={:?} ahbFormatFeaturesRaw=0x{:x} ahbFormatFeatures={} ahbChromaFilter={:?} ahbChromaLinearFilterSupported={} ahbSamplerFilter={:?} ahbSamplerLinearFilterSupported={}",
            self.suggested_model,
            self.suggested_range,
            self.components,
            self.suggested_x_chroma_offset,
            self.suggested_y_chroma_offset,
            self.format_features.as_raw(),
            format_feature_flags_marker(self.format_features),
            self.chroma_filter,
            self.chroma_linear_filter_supported,
            self.sampler_filter,
            self.sampler_linear_filter_supported
        )
    }
}

pub(crate) struct AhbVulkanSampledImageCreateInfo {
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) format_key: AhbVulkanFormatKey,
    pub(crate) allocation_size: vk::DeviceSize,
    pub(crate) memory_type_bits: u32,
    pub(crate) sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    pub(crate) debug_label: &'static str,
}

pub(crate) struct AhbVulkanSampledImage {
    pub(crate) image: vk::Image,
    pub(crate) memory: vk::DeviceMemory,
    pub(crate) image_view: vk::ImageView,
    _hardware_buffer: AndroidHardwareBufferHandle,
}

impl AhbVulkanSampledImage {
    pub(crate) unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_image_view(self.image_view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

pub(crate) unsafe fn query_ahb_vulkan_import_properties(
    ahb: &AhbVulkanDevice,
    hardware_buffer: &AndroidHardwareBufferHandle,
) -> Result<
    (
        AhbVulkanImportProperties,
        vk::AndroidHardwareBufferFormatPropertiesANDROID<'static>,
    ),
    String,
> {
    let mut format_props = vk::AndroidHardwareBufferFormatPropertiesANDROID::default();
    let (allocation_size, memory_type_bits) = {
        let mut properties =
            vk::AndroidHardwareBufferPropertiesANDROID::default().push_next(&mut format_props);
        ahb.get_android_hardware_buffer_properties(
            hardware_buffer.as_ptr().cast(),
            &mut properties,
        )
        .map_err(|error| format!("query AHardwareBuffer Vulkan properties: {error}"))?;
        (properties.allocation_size, properties.memory_type_bits)
    };

    let format_key = AhbVulkanFormatKey {
        format: if format_props.external_format != 0 {
            vk::Format::UNDEFINED
        } else {
            format_props.format
        },
        external_format: format_props.external_format,
    };
    Ok((
        AhbVulkanImportProperties {
            allocation_size,
            memory_type_bits,
            format_key,
        },
        format_props,
    ))
}

pub(crate) unsafe fn create_ahb_sampler_ycbcr_conversion(
    device: &ash::Device,
    format_key: AhbVulkanFormatKey,
    format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
    debug_label: &'static str,
) -> Result<Option<AhbVulkanSamplerYcbcrConversion>, String> {
    if format_key.external_format == 0 {
        return Ok(None);
    }

    let metadata = AhbVulkanSamplerYcbcrConversionMetadata::from_format_props(format_props);
    let mut external_format =
        vk::ExternalFormatANDROID::default().external_format(format_key.external_format);
    let conversion_info = vk::SamplerYcbcrConversionCreateInfo::default()
        .format(format_key.format)
        .ycbcr_model(metadata.suggested_model)
        .ycbcr_range(metadata.suggested_range)
        .components(format_props.sampler_ycbcr_conversion_components)
        .x_chroma_offset(metadata.suggested_x_chroma_offset)
        .y_chroma_offset(metadata.suggested_y_chroma_offset)
        .chroma_filter(metadata.chroma_filter)
        .push_next(&mut external_format);
    let handle = device
        .create_sampler_ycbcr_conversion(&conversion_info, None)
        .map_err(|error| {
            format!("create {debug_label} AHardwareBuffer sampler YCbCr conversion: {error}")
        })?;
    Ok(Some(AhbVulkanSamplerYcbcrConversion { handle, metadata }))
}

pub(crate) unsafe fn import_ahb_sampled_image(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    hardware_buffer: &AndroidHardwareBufferHandle,
    create_info: AhbVulkanSampledImageCreateInfo,
) -> Result<AhbVulkanSampledImage, String> {
    let mut external_memory = vk::ExternalMemoryImageCreateInfo::default()
        .handle_types(vk::ExternalMemoryHandleTypeFlags::ANDROID_HARDWARE_BUFFER_ANDROID);
    let mut external_format = vk::ExternalFormatANDROID::default()
        .external_format(create_info.format_key.external_format);
    let mut image_info = vk::ImageCreateInfo::default()
        .image_type(vk::ImageType::TYPE_2D)
        .format(create_info.format_key.format)
        .extent(vk::Extent3D {
            width: create_info.width,
            height: create_info.height,
            depth: 1,
        })
        .mip_levels(1)
        .array_layers(1)
        .samples(vk::SampleCountFlags::TYPE_1)
        .tiling(vk::ImageTiling::OPTIMAL)
        .usage(vk::ImageUsageFlags::SAMPLED)
        .sharing_mode(vk::SharingMode::EXCLUSIVE)
        .initial_layout(vk::ImageLayout::UNDEFINED)
        .push_next(&mut external_memory);
    if create_info.format_key.external_format != 0 {
        image_info = image_info.push_next(&mut external_format);
    }
    let image = device.create_image(&image_info, None).map_err(|error| {
        format!(
            "create {} AHardwareBuffer image: {error}",
            create_info.debug_label
        )
    })?;

    let memory_type_index =
        match find_memory_type_relaxed(memory_properties, create_info.memory_type_bits) {
            Ok(index) => index,
            Err(error) => {
                device.destroy_image(image, None);
                return Err(error);
            }
        };
    let mut import_info = vk::ImportAndroidHardwareBufferInfoANDROID::default()
        .buffer(hardware_buffer.as_ptr().cast());
    let mut dedicated = vk::MemoryDedicatedAllocateInfo::default().image(image);
    let memory = match device.allocate_memory(
        &vk::MemoryAllocateInfo::default()
            .allocation_size(create_info.allocation_size)
            .memory_type_index(memory_type_index)
            .push_next(&mut import_info)
            .push_next(&mut dedicated),
        None,
    ) {
        Ok(memory) => memory,
        Err(error) => {
            device.destroy_image(image, None);
            return Err(format!(
                "allocate {} AHardwareBuffer memory: {error}",
                create_info.debug_label
            ));
        }
    };
    if let Err(error) = device.bind_image_memory(image, memory, 0) {
        device.free_memory(memory, None);
        device.destroy_image(image, None);
        return Err(format!(
            "bind {} AHardwareBuffer image memory: {error}",
            create_info.debug_label
        ));
    }

    let mut view_info = vk::ImageViewCreateInfo::default()
        .image(image)
        .view_type(vk::ImageViewType::TYPE_2D)
        .format(create_info.format_key.format)
        .subresource_range(vk::ImageSubresourceRange {
            aspect_mask: vk::ImageAspectFlags::COLOR,
            base_mip_level: 0,
            level_count: 1,
            base_array_layer: 0,
            layer_count: 1,
        });
    let mut view_conversion = vk::SamplerYcbcrConversionInfo::default();
    if let Some(conversion) = create_info.sampler_ycbcr_conversion {
        view_conversion = view_conversion.conversion(conversion);
        view_info = view_info.push_next(&mut view_conversion);
    }
    let image_view = match device.create_image_view(&view_info, None) {
        Ok(image_view) => image_view,
        Err(error) => {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!(
                "create {} AHardwareBuffer image view: {error}",
                create_info.debug_label
            ));
        }
    };

    Ok(AhbVulkanSampledImage {
        image,
        memory,
        image_view,
        _hardware_buffer: hardware_buffer.clone(),
    })
}

pub(crate) unsafe fn transition_ahb_sampled_image_to_shader_read(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    image: vk::Image,
) {
    let barrier = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(vk::ImageSubresourceRange {
            aspect_mask: vk::ImageAspectFlags::COLOR,
            base_mip_level: 0,
            level_count: 1,
            base_array_layer: 0,
            layer_count: 1,
        })
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
}

fn find_memory_type_relaxed(
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    memory_type_bits: u32,
) -> Result<u32, String> {
    find_memory_type(
        memory_properties,
        memory_type_bits,
        vk::MemoryPropertyFlags::DEVICE_LOCAL,
    )
    .or_else(|_| {
        for index in 0..memory_properties.memory_type_count {
            if (memory_type_bits & (1 << index)) != 0 {
                return Ok(index);
            }
        }
        Err(format!(
            "no Vulkan memory type supports imported Android hardware buffer bits 0x{memory_type_bits:x}"
        ))
    })
}

pub(crate) fn find_memory_type(
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
        "no Vulkan memory type supports {required:?} for imported Android hardware buffer"
    ))
}

fn ycbcr_component_mapping_label(mapping: vk::ComponentMapping) -> String {
    format!(
        "r:{};g:{};b:{};a:{}",
        component_swizzle_label(mapping.r),
        component_swizzle_label(mapping.g),
        component_swizzle_label(mapping.b),
        component_swizzle_label(mapping.a)
    )
}

fn component_swizzle_label(swizzle: vk::ComponentSwizzle) -> &'static str {
    match swizzle {
        vk::ComponentSwizzle::IDENTITY => "identity",
        vk::ComponentSwizzle::ZERO => "zero",
        vk::ComponentSwizzle::ONE => "one",
        vk::ComponentSwizzle::R => "r",
        vk::ComponentSwizzle::G => "g",
        vk::ComponentSwizzle::B => "b",
        vk::ComponentSwizzle::A => "a",
        _ => "unknown",
    }
}

fn format_feature_flags_marker(flags: vk::FormatFeatureFlags) -> String {
    format!("{flags:?}").replace(' ', "")
}
