//! Vulkan fullscreen projection for decoded stereo video frames.

use std::ffi::CString;

use ash::vk;

use crate::{
    ahardware_buffer_vulkan::{
        create_ahb_sampler_ycbcr_conversion, import_ahb_sampled_image,
        query_ahb_vulkan_import_properties, transition_ahb_sampled_image_to_shader_read,
        AhbVulkanDevice, AhbVulkanFormatKey, AhbVulkanSampledImage,
        AhbVulkanSampledImageCreateInfo,
    },
    native_renderer_video_projection_options::NativeVideoProjectionSettings,
    projection_rect::TargetRect,
    video_projection_metadata::VideoProjectionMetadata,
    video_projection_native_stream::VideoProjectionFrame,
};

const VIDEO_PROJECTION_IMPORT_CACHE_LIMIT: usize = 8;

#[derive(Clone, Debug)]
pub(crate) struct VideoProjectionFrameStats {
    pub(crate) ready: bool,
    pub(crate) rendered: bool,
    pub(crate) reason: &'static str,
    pub(crate) frame_index: u64,
    pub(crate) import_sequence: u64,
    pub(crate) timestamp_ns: i64,
    pub(crate) hardware_buffer_id: u64,
    pub(crate) descriptor_width: u32,
    pub(crate) descriptor_height: u32,
    pub(crate) descriptor_format: u32,
    pub(crate) descriptor_usage: u64,
    pub(crate) descriptor_stride: u32,
    pub(crate) configured_width: i32,
    pub(crate) configured_height: i32,
    pub(crate) fps_cap: i32,
    pub(crate) dropped_frames: u64,
    pub(crate) buffer_removed_count: u64,
    pub(crate) external_format: u64,
    pub(crate) vk_format: vk::Format,
    pub(crate) descriptor_shape: &'static str,
    pub(crate) external_format_sampling: bool,
    pub(crate) sampler_ycbcr_conversion: bool,
    pub(crate) descriptor_uses_immutable_sampler: bool,
    pub(crate) allocation_size: vk::DeviceSize,
    pub(crate) memory_type_bits: u32,
    pub(crate) import_cache_hits: u64,
    pub(crate) import_cache_misses: u64,
    pub(crate) opacity: f32,
    pub(crate) stereo_layout: &'static str,
}

impl VideoProjectionFrameStats {
    pub(crate) fn unavailable(
        settings: &NativeVideoProjectionSettings,
        reason: &'static str,
    ) -> Self {
        Self {
            ready: false,
            rendered: false,
            reason,
            frame_index: 0,
            import_sequence: 0,
            timestamp_ns: 0,
            hardware_buffer_id: 0,
            descriptor_width: 0,
            descriptor_height: 0,
            descriptor_format: 0,
            descriptor_usage: 0,
            descriptor_stride: 0,
            configured_width: 0,
            configured_height: 0,
            fps_cap: 0,
            dropped_frames: 0,
            buffer_removed_count: 0,
            external_format: 0,
            vk_format: vk::Format::UNDEFINED,
            descriptor_shape: "unavailable",
            external_format_sampling: false,
            sampler_ycbcr_conversion: false,
            descriptor_uses_immutable_sampler: false,
            allocation_size: 0,
            memory_type_bits: 0,
            import_cache_hits: 0,
            import_cache_misses: 0,
            opacity: settings.opacity,
            stereo_layout: settings.stereo_layout.marker_value(),
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "videoProjectionReady={} videoProjectionRendered={} videoProjectionReason={} videoProjectionStereoLayout={} videoProjectionFrameIndex={} videoProjectionImportSequence={} videoProjectionTimestampNs={} videoProjectionHardwareBufferId={} videoProjectionDescriptorWidth={} videoProjectionDescriptorHeight={} videoProjectionDescriptorFormat={} videoProjectionDescriptorUsage={} videoProjectionDescriptorStride={} videoProjectionConfiguredWidth={} videoProjectionConfiguredHeight={} videoProjectionFpsCap={} videoProjectionDroppedFrames={} videoProjectionBufferRemovedCount={} videoProjectionExternalFormat={} videoProjectionVkFormat={:?} descriptorShape={} videoProjectionExternalFormatSampling={} videoProjectionSamplerYcbcrConversion={} videoProjectionDescriptorUsesImmutableSampler={} videoProjectionOpacity={:.3} videoProjectionAllocationSize={} videoProjectionMemoryTypeBits=0x{:x} videoProjectionImportCacheHits={} videoProjectionImportCacheMisses={} videoProjectionGpuImportReady={} videoProjectionGpuAdoptionPath=android-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false highRateJsonPayload=false sourceAuthority=android-mediacodec-surface-decoder rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false",
            self.ready,
            self.rendered,
            self.reason,
            self.stereo_layout,
            self.frame_index,
            self.import_sequence,
            self.timestamp_ns,
            self.hardware_buffer_id,
            self.descriptor_width,
            self.descriptor_height,
            self.descriptor_format,
            self.descriptor_usage,
            self.descriptor_stride,
            self.configured_width,
            self.configured_height,
            self.fps_cap,
            self.dropped_frames,
            self.buffer_removed_count,
            self.external_format,
            self.vk_format,
            self.descriptor_shape,
            self.external_format_sampling,
            self.sampler_ycbcr_conversion,
            self.descriptor_uses_immutable_sampler,
            self.opacity,
            self.allocation_size,
            self.memory_type_bits,
            self.import_cache_hits,
            self.import_cache_misses,
            self.ready
        )
    }
}

pub(crate) struct PreparedVideoProjection {
    pub(crate) descriptor_set: vk::DescriptorSet,
    pub(crate) descriptor_set_layout: vk::DescriptorSetLayout,
    pub(crate) pipeline_layout: vk::PipelineLayout,
    pub(crate) pipeline: vk::Pipeline,
    pub(crate) stats: VideoProjectionFrameStats,
}

pub(crate) struct VideoProjectionRenderer {
    ahb: Option<AhbVulkanDevice>,
    memory_properties: vk::PhysicalDeviceMemoryProperties,
    render_pass: vk::RenderPass,
    resources: Option<VideoProjectionResources>,
    imports: Vec<VideoProjectionImport>,
    import_cache_hits: u64,
    import_cache_misses: u64,
    gpu_frame_hardware_buffer_ids: Vec<Vec<u64>>,
}

impl VideoProjectionRenderer {
    pub(crate) unsafe fn new(
        instance: &ash::Instance,
        device: &ash::Device,
        memory_properties: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        import_supported: bool,
    ) -> Self {
        let ahb = import_supported.then(|| {
            ash::android::external_memory_android_hardware_buffer::Device::new(instance, device)
        });
        Self {
            ahb,
            memory_properties,
            render_pass,
            resources: None,
            imports: Vec::new(),
            import_cache_hits: 0,
            import_cache_misses: 0,
            gpu_frame_hardware_buffer_ids: Vec::new(),
        }
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        self.gpu_frame_hardware_buffer_ids.clear();
        self.destroy_imports(device);
        if let Some(resources) = self.resources.take() {
            resources.destroy(device);
        }
    }

    pub(crate) fn retire_completed_frame_handles(&mut self, frame_slot: usize) {
        if let Some(ids) = self.gpu_frame_hardware_buffer_ids.get_mut(frame_slot) {
            ids.clear();
        }
    }

    pub(crate) unsafe fn prepare_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        frame: &VideoProjectionFrame,
        settings: &NativeVideoProjectionSettings,
    ) -> Result<Option<PreparedVideoProjection>, String> {
        if !settings.active() {
            return Ok(None);
        }
        let Some(ahb) = self.ahb.as_ref() else {
            return Ok(None);
        };

        let (import_properties, format_props) =
            query_ahb_vulkan_import_properties(ahb, &frame.hardware_buffer)?;
        let format_key = import_properties.format_key;
        if format_key.format == vk::Format::UNDEFINED && format_key.external_format == 0 {
            return Err(
                "video projection sampled path got no Vulkan format or Android external format"
                    .to_string(),
            );
        }

        if self
            .resources
            .as_ref()
            .map(|resources| resources.format_key != format_key)
            .unwrap_or(true)
        {
            self.destroy_imports(device);
            if let Some(resources) = self.resources.take() {
                resources.destroy(device);
            }
            self.resources = Some(create_video_projection_resources(
                device,
                self.render_pass,
                format_key,
                &format_props,
            )?);
        }

        let protected_hardware_buffer_id = frame.descriptor.hardware_buffer_id;
        let key = VideoProjectionImportKey::from_frame(frame);
        let import_index = if let Some(index) =
            self.imports.iter().position(|import| import.key == key)
        {
            self.import_cache_hits = self.import_cache_hits.saturating_add(1);
            if self.imports[index].needs_layout_transition {
                transition_ahb_sampled_image_to_shader_read(
                    device,
                    cmd,
                    self.imports[index].sampled_image.image,
                );
                self.imports[index].needs_layout_transition = false;
            }
            index
        } else {
            self.import_cache_misses = self.import_cache_misses.saturating_add(1);
            let imports_before = self.imports.len();
            let eviction_stats = self.evict_imports_to_limit(device, protected_hardware_buffer_id);
            if eviction_stats.should_log() {
                crate::marker(
                    "video-projection-cache",
                    format!(
                        "status=import-lru-eviction importCacheLimit={} importsBefore={} importsAfter={} evictionAttempts={} evictedImportCount={} inFlightSkipCount={} protectedSkipCount={} cacheEvictionApplied={} cacheEvictionDeferred={} stream=stereo_video",
                        VIDEO_PROJECTION_IMPORT_CACHE_LIMIT,
                        imports_before,
                        self.imports.len(),
                        eviction_stats.attempts,
                        eviction_stats.applied,
                        eviction_stats.in_flight_skips,
                        eviction_stats.protected_skips,
                        eviction_stats.applied > 0,
                        eviction_stats.deferred > 0,
                    ),
                );
            }

            let resources = self
                .resources
                .as_ref()
                .ok_or_else(|| "video projection resources were not initialized".to_string())?;
            let mut import = import_video_projection_hardware_buffer(
                device,
                &self.memory_properties,
                resources,
                frame,
                key,
                format_key,
                import_properties.allocation_size,
                import_properties.memory_type_bits,
            )?;
            transition_ahb_sampled_image_to_shader_read(device, cmd, import.sampled_image.image);
            import.needs_layout_transition = false;
            self.imports.push(import);
            let import_index = self.imports.len() - 1;

            crate::marker(
                "video-projection-import",
                format!(
                    "status=ok stream=stereo_video frameIndex={} importSequence={} timestampNs={} hardwareBufferId={} width={} height={} descriptorFormat={} descriptorUsage={} descriptorStride={} externalFormat={} vkFormat={:?} allocationSize={} memoryTypeBits=0x{:x} descriptorShape={} videoProjectionExternalFormatSampling={} videoProjectionSamplerYcbcrConversion={} videoProjectionDescriptorUsesImmutableSampler={} gpuImportWorked=true videoProjectionGpuImportReady=true videoProjectionGpuAdoptionPath=android-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
                    frame.frame_index,
                    frame.import_sequence,
                    frame.timestamp_ns,
                    frame.descriptor.hardware_buffer_id,
                    frame.descriptor.width,
                    frame.descriptor.height,
                    frame.descriptor.format,
                    frame.descriptor.usage,
                    frame.descriptor.stride,
                    format_key.external_format,
                    format_key.format,
                    import_properties.allocation_size,
                    import_properties.memory_type_bits,
                    resources.descriptor_shape(),
                    resources.sampler_ycbcr_conversion.is_some(),
                    resources.sampler_ycbcr_conversion.is_some(),
                    resources.descriptor_uses_immutable_sampler,
                ),
            );
            import_index
        };

        let (
            pipeline_layout,
            pipeline,
            descriptor_shape,
            external_format_sampling,
            sampler_ycbcr_conversion,
            descriptor_uses_immutable_sampler,
        ) = {
            let resources = self
                .resources
                .as_ref()
                .ok_or_else(|| "video projection resources were not initialized".to_string())?;
            (
                resources.pipeline_layout,
                resources.pipeline,
                resources.descriptor_shape(),
                resources.sampler_ycbcr_conversion.is_some(),
                resources.sampler_ycbcr_conversion.is_some(),
                resources.descriptor_uses_immutable_sampler,
            )
        };
        self.track_frame_hardware_buffer_id(frame_slot, protected_hardware_buffer_id);
        Ok(Some(PreparedVideoProjection {
            descriptor_set: self.imports[import_index].descriptor_set,
            descriptor_set_layout: self
                .resources
                .as_ref()
                .expect("video projection resources were initialized")
                .descriptor_set_layout,
            pipeline_layout,
            pipeline,
            stats: VideoProjectionFrameStats {
                ready: true,
                rendered: true,
                reason: "rendered",
                frame_index: frame.frame_index,
                import_sequence: frame.import_sequence,
                timestamp_ns: frame.timestamp_ns,
                hardware_buffer_id: frame.descriptor.hardware_buffer_id,
                descriptor_width: frame.descriptor.width,
                descriptor_height: frame.descriptor.height,
                descriptor_format: frame.descriptor.format,
                descriptor_usage: frame.descriptor.usage,
                descriptor_stride: frame.descriptor.stride,
                configured_width: frame.configured_width,
                configured_height: frame.configured_height,
                fps_cap: frame.fps_cap,
                dropped_frames: frame.dropped_frames,
                buffer_removed_count: frame.buffer_removed_count,
                external_format: format_key.external_format,
                vk_format: format_key.format,
                descriptor_shape,
                external_format_sampling,
                sampler_ycbcr_conversion,
                descriptor_uses_immutable_sampler,
                allocation_size: import_properties.allocation_size,
                memory_type_bits: import_properties.memory_type_bits,
                import_cache_hits: self.import_cache_hits,
                import_cache_misses: self.import_cache_misses,
                opacity: settings.opacity,
                stereo_layout: settings.stereo_layout.marker_value(),
            },
        }))
    }

    pub(crate) unsafe fn record_video_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_index: usize,
        projection_metadata: &VideoProjectionMetadata,
        target_rect: TargetRect,
        opacity: f32,
        prepared: &PreparedVideoProjection,
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
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, prepared.pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            prepared.pipeline_layout,
            0,
            &[prepared.descriptor_set],
            &[],
        );
        let source_uv_rect = projection_metadata.source_rect_for_eye(eye_index);
        let source_position_offset = projection_metadata.source_position_offset_for_eye(eye_index);
        let push = VideoProjectionPush {
            target_rect: [
                target_rect.x,
                target_rect.y,
                target_rect.width,
                target_rect.height,
            ],
            source_uv_rect: [
                source_uv_rect.x,
                source_uv_rect.y,
                source_uv_rect.width,
                source_uv_rect.height,
            ],
            params0: [
                projection_metadata.source_sample_y_flip,
                opacity,
                source_position_offset[0],
                source_position_offset[1],
            ],
        };
        let push_bytes = std::slice::from_raw_parts(
            (&push as *const VideoProjectionPush).cast::<u8>(),
            std::mem::size_of::<VideoProjectionPush>(),
        );
        device.cmd_push_constants(
            cmd,
            prepared.pipeline_layout,
            vk::ShaderStageFlags::FRAGMENT,
            0,
            push_bytes,
        );
        device.cmd_draw(cmd, 3, 1, 0, 0);
    }

    unsafe fn evict_imports_to_limit(
        &mut self,
        device: &ash::Device,
        protected_hardware_buffer_id: u64,
    ) -> VideoProjectionCacheEvictionStats {
        let mut stats = VideoProjectionCacheEvictionStats::default();
        while self.imports.len() >= VIDEO_PROJECTION_IMPORT_CACHE_LIMIT {
            stats.attempts += 1;
            let mut evict_index = None;
            for (index, import) in self.imports.iter().enumerate() {
                if protected_hardware_buffer_id != 0
                    && import.key.buffer_id == protected_hardware_buffer_id
                {
                    stats.protected_skips += 1;
                    continue;
                }
                if self.hardware_buffer_id_in_submitted_frame(import.key.buffer_id) {
                    stats.in_flight_skips += 1;
                    continue;
                }
                evict_index = Some(index);
                break;
            }

            let Some(index) = evict_index else {
                stats.deferred += 1;
                break;
            };
            let old = self.imports.remove(index);
            old.destroy(device);
            stats.applied += 1;
        }
        stats
    }

    fn hardware_buffer_id_in_submitted_frame(&self, hardware_buffer_id: u64) -> bool {
        hardware_buffer_id != 0
            && self
                .gpu_frame_hardware_buffer_ids
                .iter()
                .any(|ids| ids.iter().any(|id| *id == hardware_buffer_id))
    }

    fn track_frame_hardware_buffer_id(&mut self, frame_slot: usize, hardware_buffer_id: u64) {
        while self.gpu_frame_hardware_buffer_ids.len() <= frame_slot {
            self.gpu_frame_hardware_buffer_ids.push(Vec::new());
        }
        self.gpu_frame_hardware_buffer_ids[frame_slot] = (hardware_buffer_id != 0)
            .then_some(hardware_buffer_id)
            .into_iter()
            .collect();
    }

    unsafe fn destroy_imports(&mut self, device: &ash::Device) {
        for import in self.imports.drain(..) {
            import.destroy(device);
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
struct VideoProjectionCacheEvictionStats {
    attempts: usize,
    applied: usize,
    in_flight_skips: usize,
    protected_skips: usize,
    deferred: usize,
}

impl VideoProjectionCacheEvictionStats {
    fn should_log(self) -> bool {
        self.attempts > 0
            || self.applied > 0
            || self.in_flight_skips > 0
            || self.protected_skips > 0
            || self.deferred > 0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct VideoProjectionImportKey {
    buffer_id: u64,
    width: u32,
    height: u32,
    native_format: u32,
}

impl VideoProjectionImportKey {
    fn from_frame(frame: &VideoProjectionFrame) -> Self {
        Self {
            buffer_id: if frame.descriptor.hardware_buffer_id == 0 {
                frame.timestamp_ns.max(0) as u64
            } else {
                frame.descriptor.hardware_buffer_id
            },
            width: frame.descriptor.width,
            height: frame.descriptor.height,
            native_format: frame.descriptor.format,
        }
    }
}

struct VideoProjectionResources {
    format_key: AhbVulkanFormatKey,
    sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    sampler: vk::Sampler,
    descriptor_uses_immutable_sampler: bool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
}

impl VideoProjectionResources {
    fn descriptor_shape(&self) -> &'static str {
        if self.descriptor_uses_immutable_sampler {
            "combined-immutable-sampler-ycbcr-conversion"
        } else {
            "combined-rgba-sampler"
        }
    }

    unsafe fn destroy(self, device: &ash::Device) {
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

struct VideoProjectionImport {
    key: VideoProjectionImportKey,
    sampled_image: AhbVulkanSampledImage,
    descriptor_set: vk::DescriptorSet,
    descriptor_pool: vk::DescriptorPool,
    needs_layout_transition: bool,
}

impl VideoProjectionImport {
    unsafe fn destroy(self, device: &ash::Device) {
        let _ = device.free_descriptor_sets(self.descriptor_pool, &[self.descriptor_set]);
        self.sampled_image.destroy(device);
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct VideoProjectionPush {
    target_rect: [f32; 4],
    source_uv_rect: [f32; 4],
    params0: [f32; 4],
}

unsafe fn create_video_projection_resources(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    format_key: AhbVulkanFormatKey,
    format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
) -> Result<VideoProjectionResources, String> {
    let sampler_ycbcr_conversion =
        create_ahb_sampler_ycbcr_conversion(device, format_key, format_props, "video-projection")?;
    let sampler_ycbcr_handle = sampler_ycbcr_conversion
        .as_ref()
        .map(|conversion| conversion.handle);
    let sampler_ycbcr_metadata = sampler_ycbcr_conversion
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
            return Err(format!("create video projection sampler: {error}"));
        }
    };

    let descriptor_uses_immutable_sampler = sampler_ycbcr_handle.is_some();
    let immutable_samplers = [sampler];
    let mut descriptor_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::FRAGMENT);
    if descriptor_uses_immutable_sampler {
        descriptor_binding = descriptor_binding.immutable_samplers(&immutable_samplers);
    }
    let descriptor_bindings = [descriptor_binding];
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
            return Err(format!(
                "create video projection descriptor set layout: {error}"
            ));
        }
    };
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(VIDEO_PROJECTION_IMPORT_CACHE_LIMIT as u32 * 2)];
    let descriptor_pool = match device.create_descriptor_pool(
        &vk::DescriptorPoolCreateInfo::default()
            .flags(vk::DescriptorPoolCreateFlags::FREE_DESCRIPTOR_SET)
            .pool_sizes(&pool_sizes)
            .max_sets(VIDEO_PROJECTION_IMPORT_CACHE_LIMIT as u32 * 2),
        None,
    ) {
        Ok(pool) => pool,
        Err(error) => {
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create video projection descriptor pool: {error}"));
        }
    };
    let push_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(std::mem::size_of::<VideoProjectionPush>() as u32)];
    let set_layouts = [descriptor_set_layout];
    let pipeline_layout = match device.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default()
            .set_layouts(&set_layouts)
            .push_constant_ranges(&push_ranges),
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
            return Err(format!("create video projection pipeline layout: {error}"));
        }
    };
    let pipeline = match create_video_projection_pipeline(device, render_pass, pipeline_layout) {
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

    let descriptor_shape = if descriptor_uses_immutable_sampler {
        "combined-immutable-sampler-ycbcr-conversion"
    } else {
        "combined-rgba-sampler"
    };
    let ycbcr_fields = sampler_ycbcr_metadata
        .as_ref()
        .map(|metadata| {
            format!(
                "{} videoProjectionExternalFormatSampling=true videoProjectionSamplerYcbcrConversion=true videoProjectionDescriptorUsesImmutableSampler=true",
                metadata.marker_fields()
            )
        })
        .unwrap_or_else(|| {
            "videoProjectionExternalFormatSampling=false videoProjectionSamplerYcbcrConversion=false videoProjectionDescriptorUsesImmutableSampler=false".to_string()
        });
    crate::marker(
        "video-projection-resources",
        format!(
            "status=created stream=stereo_video externalFormat={} vkFormat={:?} descriptorShape={} shaderPath=fullscreen-stereo-video-projection formatFeaturesRaw=0x{:x} formatFeatures={} samplerFilter={:?} samplerLinearFilterSupported={} {} videoProjectionGpuImportReady=false videoProjectionGpuAdoptionPath=android-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image",
            format_key.external_format,
            format_key.format,
            descriptor_shape,
            format_props.format_features.as_raw(),
            format_feature_flags_marker(format_props.format_features),
            sampler_filter,
            linear_supported,
            ycbcr_fields
        ),
    );

    Ok(VideoProjectionResources {
        format_key,
        sampler_ycbcr_conversion: sampler_ycbcr_handle,
        sampler,
        descriptor_uses_immutable_sampler,
        descriptor_set_layout,
        descriptor_pool,
        pipeline_layout,
        pipeline,
    })
}

unsafe fn allocate_video_projection_descriptor_set(
    device: &ash::Device,
    resources: &VideoProjectionResources,
    image_view: vk::ImageView,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [resources.descriptor_set_layout];
    let descriptor_set = device
        .allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(resources.descriptor_pool)
                .set_layouts(&set_layouts),
        )
        .map_err(|error| format!("allocate video projection descriptor set: {error}"))?
        .pop()
        .ok_or_else(|| "video projection descriptor allocation returned no set".to_string())?;
    let image_info = [vk::DescriptorImageInfo::default()
        .sampler(resources.sampler)
        .image_view(image_view)
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let writes = [vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .image_info(&image_info)];
    device.update_descriptor_sets(&writes, &[]);
    Ok(descriptor_set)
}

unsafe fn import_video_projection_hardware_buffer(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    resources: &VideoProjectionResources,
    frame: &VideoProjectionFrame,
    key: VideoProjectionImportKey,
    format_key: AhbVulkanFormatKey,
    allocation_size: vk::DeviceSize,
    memory_type_bits: u32,
) -> Result<VideoProjectionImport, String> {
    let width = frame
        .descriptor
        .width
        .max(frame.configured_width.max(1) as u32);
    let height = frame
        .descriptor
        .height
        .max(frame.configured_height.max(1) as u32);
    let sampled_image = import_ahb_sampled_image(
        device,
        memory_properties,
        &frame.hardware_buffer,
        AhbVulkanSampledImageCreateInfo {
            width,
            height,
            format_key,
            allocation_size,
            memory_type_bits,
            sampler_ycbcr_conversion: resources.sampler_ycbcr_conversion,
            debug_label: "video-projection",
        },
    )?;
    let descriptor_set =
        allocate_video_projection_descriptor_set(device, resources, sampled_image.image_view)?;
    Ok(VideoProjectionImport {
        key,
        sampled_image,
        descriptor_set,
        descriptor_pool: resources.descriptor_pool,
        needs_layout_transition: true,
    })
}

unsafe fn create_video_projection_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/video_projection.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/video_projection.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create video projection vertex shader module: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create video projection fragment shader module: {error}"
            ));
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
        .blend_enable(false)
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
    let pipeline_result =
        device.create_graphics_pipelines(vk::PipelineCache::null(), &create_info, None);
    device.destroy_shader_module(fragment_module, None);
    device.destroy_shader_module(vertex_module, None);
    pipeline_result
        .map(|mut pipelines| pipelines.remove(0))
        .map_err(|(_, error)| format!("create video projection graphics pipeline: {error}"))
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

fn format_feature_flags_marker(flags: vk::FormatFeatureFlags) -> String {
    format!("{flags:?}").replace(' ', "")
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
