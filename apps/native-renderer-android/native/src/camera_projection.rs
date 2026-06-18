//! Minimal Vulkan camera projection proof for retained Camera2 AHardwareBuffers.

use std::{ffi::CString, sync::Arc};

use ash::vk;

use crate::camera_projection_metadata::{CameraProjectionMetadata, TargetRect};
use crate::native_camera::{NativeCameraFrame, NativeCameraImageLease, NativeStereoCameraFrame};
use crate::native_renderer_options::NativeCameraYcbcrMode;

const CAMERA_IMPORT_CACHE_LIMIT: usize = 18;
const CAMERA_STEREO_DESCRIPTOR_LIMIT: usize = 12;

#[derive(Clone, Debug, Default)]
pub(crate) struct CameraProjectionFrameStats {
    pub(crate) rendered: bool,
    pub(crate) left_source_frame: u64,
    pub(crate) right_source_frame: u64,
    pub(crate) left_hardware_buffer_id: u64,
    pub(crate) right_hardware_buffer_id: u64,
    pub(crate) left_import_sequence: u64,
    pub(crate) right_import_sequence: u64,
    pub(crate) left_camera_id: String,
    pub(crate) right_camera_id: String,
    pub(crate) pair_delta_ns: u64,
    pub(crate) import_cache_hits: u64,
    pub(crate) import_cache_misses: u64,
}

pub(crate) struct PreparedCameraProjection {
    pub(crate) descriptor_set: vk::DescriptorSet,
    pub(crate) descriptor_set_layout: vk::DescriptorSetLayout,
    pub(crate) pipeline_layout: vk::PipelineLayout,
    pub(crate) pipeline: vk::Pipeline,
    pub(crate) stats: CameraProjectionFrameStats,
}

#[derive(Clone, Copy, Debug, Default)]
struct CameraCacheEvictionStats {
    attempts: usize,
    applied: usize,
    in_flight_skips: usize,
    protected_skips: usize,
    deferred: usize,
}

impl CameraCacheEvictionStats {
    fn should_log(self) -> bool {
        self.attempts > 0
            || self.applied > 0
            || self.in_flight_skips > 0
            || self.protected_skips > 0
            || self.deferred > 0
    }
}

pub(crate) struct CameraProjectionRenderer {
    ahb: Option<ash::android::external_memory_android_hardware_buffer::Device>,
    memory_properties: vk::PhysicalDeviceMemoryProperties,
    render_pass: vk::RenderPass,
    resources: Option<CameraProjectionResources>,
    imports: Vec<CameraImport>,
    stereo_descriptors: Vec<CameraStereoDescriptor>,
    ycbcr_mode: NativeCameraYcbcrMode,
    import_cache_hits: u64,
    import_cache_misses: u64,
    gpu_frame_image_leases: Vec<Vec<Arc<NativeCameraImageLease>>>,
    gpu_frame_hardware_buffer_ids: Vec<Vec<u64>>,
}

impl CameraProjectionRenderer {
    pub(crate) unsafe fn new(
        instance: &ash::Instance,
        device: &ash::Device,
        memory_properties: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        import_supported: bool,
        ycbcr_mode: NativeCameraYcbcrMode,
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
            stereo_descriptors: Vec::new(),
            ycbcr_mode,
            import_cache_hits: 0,
            import_cache_misses: 0,
            gpu_frame_image_leases: Vec::new(),
            gpu_frame_hardware_buffer_ids: Vec::new(),
        }
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        self.gpu_frame_image_leases.clear();
        self.gpu_frame_hardware_buffer_ids.clear();
        self.destroy_stereo_descriptors(device);
        self.destroy_imports(device);
        if let Some(resources) = self.resources.take() {
            resources.destroy(device);
        }
    }

    pub(crate) fn retire_completed_frame_leases(&mut self, frame_slot: usize) -> usize {
        if let Some(buffer_ids) = self.gpu_frame_hardware_buffer_ids.get_mut(frame_slot) {
            buffer_ids.clear();
        }
        self.gpu_frame_image_leases
            .get_mut(frame_slot)
            .map(|leases| {
                let count = leases.len();
                leases.clear();
                count
            })
            .unwrap_or(0)
    }

    pub(crate) unsafe fn evict_removed_hardware_buffers(
        &mut self,
        device: &ash::Device,
        removed_hardware_buffer_ids: &[u64],
    ) -> usize {
        if removed_hardware_buffer_ids.is_empty() {
            return 0;
        }
        let mut removed_count = 0_usize;
        let mut index = 0_usize;
        while index < self.imports.len() {
            let remove = removed_hardware_buffer_ids
                .iter()
                .any(|id| *id != 0 && *id == self.imports[index].key.buffer_id)
                && !self.hardware_buffer_id_in_submitted_frame(self.imports[index].key.buffer_id);
            if remove {
                let old = self.imports.remove(index);
                self.destroy_stereo_descriptors_for_key(device, old.key);
                old.destroy(device);
                removed_count += 1;
            } else {
                index += 1;
            }
        }
        removed_count
    }

    unsafe fn evict_imports_to_limit(
        &mut self,
        device: &ash::Device,
        protected_hardware_buffer_ids: &[u64],
    ) -> CameraCacheEvictionStats {
        let mut stats = CameraCacheEvictionStats::default();
        while self.imports.len() >= CAMERA_IMPORT_CACHE_LIMIT {
            stats.attempts += 1;
            let mut evict_index = None;
            for (index, import) in self.imports.iter().enumerate() {
                if protected_hardware_buffer_ids
                    .iter()
                    .any(|id| *id != 0 && *id == import.key.buffer_id)
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
            self.destroy_stereo_descriptors_for_key(device, old.key);
            old.destroy(device);
            stats.applied += 1;
        }
        stats
    }

    unsafe fn evict_stereo_descriptors_to_limit(
        &mut self,
        device: &ash::Device,
        protected_hardware_buffer_ids: &[u64],
    ) -> CameraCacheEvictionStats {
        let mut stats = CameraCacheEvictionStats::default();
        while self.stereo_descriptors.len() >= CAMERA_STEREO_DESCRIPTOR_LIMIT {
            stats.attempts += 1;
            let mut evict_index = None;
            for (index, descriptor) in self.stereo_descriptors.iter().enumerate() {
                let descriptor_buffer_ids = [
                    descriptor.left_key.buffer_id,
                    descriptor.right_key.buffer_id,
                ];
                if descriptor_buffer_ids.iter().any(|buffer_id| {
                    protected_hardware_buffer_ids
                        .iter()
                        .any(|id| *id != 0 && *id == *buffer_id)
                }) {
                    stats.protected_skips += 1;
                    continue;
                }
                if descriptor_buffer_ids
                    .iter()
                    .any(|buffer_id| self.hardware_buffer_id_in_submitted_frame(*buffer_id))
                {
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
            let old = self.stereo_descriptors.remove(index);
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

    pub(crate) unsafe fn prepare_stereo_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        frame: &NativeStereoCameraFrame,
    ) -> Result<Option<PreparedCameraProjection>, String> {
        if self.ahb.is_none() {
            return Ok(None);
        }

        let protected_hardware_buffer_ids = [
            frame.left.hardware_buffer_id,
            frame.right.hardware_buffer_id,
        ];
        let left =
            self.prepare_frame_inner(device, cmd, &frame.left, &protected_hardware_buffer_ids)?;
        let right =
            self.prepare_frame_inner(device, cmd, &frame.right, &protected_hardware_buffer_ids)?;
        let (descriptor_set_layout, pipeline_layout, pipeline) = {
            let resources = self
                .resources
                .as_ref()
                .ok_or_else(|| "camera projection resources were not initialized".to_string())?;
            (
                resources.descriptor_set_layout,
                resources.pipeline_layout,
                resources.pipeline,
            )
        };
        let descriptor_set = if let Some(descriptor) = self
            .stereo_descriptors
            .iter()
            .find(|descriptor| descriptor.left_key == left.key && descriptor.right_key == right.key)
        {
            descriptor.descriptor_set
        } else {
            let descriptors_before = self.stereo_descriptors.len();
            let eviction_stats =
                self.evict_stereo_descriptors_to_limit(device, &protected_hardware_buffer_ids);
            if eviction_stats.should_log() {
                crate::marker(
                    "camera-projection-cache",
                    format!(
                        "status=descriptor-lru-eviction descriptorCacheLimit={} descriptorsBefore={} descriptorsAfter={} evictionAttempts={} evictedDescriptorCount={} inFlightSkipCount={} protectedSkipCount={} cacheEvictionApplied={} cacheEvictionDeferred={}",
                        CAMERA_STEREO_DESCRIPTOR_LIMIT,
                        descriptors_before,
                        self.stereo_descriptors.len(),
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
                .ok_or_else(|| "camera projection resources were not initialized".to_string())?;
            let descriptor_set = allocate_camera_descriptor_set(
                device,
                resources,
                left.image_view,
                right.image_view,
            )?;
            self.stereo_descriptors.push(CameraStereoDescriptor {
                left_key: left.key,
                right_key: right.key,
                descriptor_set,
                descriptor_pool: resources.descriptor_pool,
            });
            descriptor_set
        };

        self.track_frame_image_leases(frame_slot, frame);

        Ok(Some(PreparedCameraProjection {
            descriptor_set,
            descriptor_set_layout,
            pipeline_layout,
            pipeline,
            stats: CameraProjectionFrameStats {
                rendered: true,
                left_source_frame: frame.left.source_frame,
                right_source_frame: frame.right.source_frame,
                left_hardware_buffer_id: frame.left.hardware_buffer_id,
                right_hardware_buffer_id: frame.right.hardware_buffer_id,
                left_import_sequence: frame.left.import_sequence,
                right_import_sequence: frame.right.import_sequence,
                left_camera_id: frame.left.camera_id.clone(),
                right_camera_id: frame.right.camera_id.clone(),
                pair_delta_ns: frame.pair_delta_ns,
                import_cache_hits: self.import_cache_hits,
                import_cache_misses: self.import_cache_misses,
            },
        }))
    }

    fn track_frame_image_leases(&mut self, frame_slot: usize, frame: &NativeStereoCameraFrame) {
        let mut leases = Vec::new();
        if let Some(lease) = &frame.left.image_lease {
            leases.push(Arc::clone(lease));
        }
        if let Some(lease) = &frame.right.image_lease {
            leases.push(Arc::clone(lease));
        }
        if leases.is_empty() {
            self.track_frame_hardware_buffer_ids(frame_slot, frame);
            return;
        }
        while self.gpu_frame_image_leases.len() <= frame_slot {
            self.gpu_frame_image_leases.push(Vec::new());
        }
        self.gpu_frame_image_leases[frame_slot] = leases;
        self.track_frame_hardware_buffer_ids(frame_slot, frame);
        crate::marker(
            "camera-sync",
            format!(
                "status=gpu-frame-lease-tracked frameSlot={} leaseCount={} leftSourceFrame={} rightSourceFrame={} cameraSyncActive=hold-image-until-gpu-fence producerConsumerSync=image-slot-held-until-vulkan-frame-fence",
                frame_slot,
                self.gpu_frame_image_leases[frame_slot].len(),
                frame.left.source_frame,
                frame.right.source_frame
            ),
        );
    }

    fn track_frame_hardware_buffer_ids(
        &mut self,
        frame_slot: usize,
        frame: &NativeStereoCameraFrame,
    ) {
        while self.gpu_frame_hardware_buffer_ids.len() <= frame_slot {
            self.gpu_frame_hardware_buffer_ids.push(Vec::new());
        }
        self.gpu_frame_hardware_buffer_ids[frame_slot] = [
            frame.left.hardware_buffer_id,
            frame.right.hardware_buffer_id,
        ]
        .into_iter()
        .filter(|id| *id != 0)
        .collect();
    }

    unsafe fn prepare_frame_inner(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame: &NativeCameraFrame,
        protected_hardware_buffer_ids: &[u64],
    ) -> Result<PreparedCameraImport, String> {
        let key = CameraImportKey::from_frame(frame);
        if let Some(index) = self.imports.iter().position(|import| import.key == key) {
            self.import_cache_hits = self.import_cache_hits.saturating_add(1);
            if self.imports[index].needs_layout_transition {
                transition_imported_camera_image(device, cmd, self.imports[index].image);
                self.imports[index].needs_layout_transition = false;
            }
            return Ok(PreparedCameraImport {
                key,
                image_view: self.imports[index].image_view,
            });
        }
        self.import_cache_misses = self.import_cache_misses.saturating_add(1);

        let ahb = self
            .ahb
            .as_ref()
            .ok_or_else(|| "Android hardware-buffer Vulkan extension is unavailable".to_string())?;
        let mut format_props = vk::AndroidHardwareBufferFormatPropertiesANDROID::default();
        let (allocation_size, memory_type_bits) = {
            let mut properties =
                vk::AndroidHardwareBufferPropertiesANDROID::default().push_next(&mut format_props);
            ahb.get_android_hardware_buffer_properties(
                frame.hardware_buffer.as_ptr().cast(),
                &mut properties,
            )
            .map_err(|error| format!("query AHardwareBuffer Vulkan properties: {error}"))?;
            (properties.allocation_size, properties.memory_type_bits)
        };

        let format_key = CameraFormatKey {
            format: if format_props.external_format != 0 {
                vk::Format::UNDEFINED
            } else {
                format_props.format
            },
            external_format: format_props.external_format,
        };
        if self
            .resources
            .as_ref()
            .map(|resources| resources.format_key != format_key)
            .unwrap_or(true)
        {
            self.destroy_stereo_descriptors(device);
            self.destroy_imports(device);
            if let Some(resources) = self.resources.take() {
                resources.destroy(device);
            }
            self.resources = Some(create_camera_projection_resources(
                device,
                &self.memory_properties,
                self.render_pass,
                format_key,
                &format_props,
                self.ycbcr_mode,
            )?);
        }

        let imports_before = self.imports.len();
        let eviction_stats = self.evict_imports_to_limit(device, protected_hardware_buffer_ids);
        if eviction_stats.should_log() {
            crate::marker(
                "camera-projection-cache",
                format!(
                    "status=import-lru-eviction importCacheLimit={} importsBefore={} importsAfter={} evictionAttempts={} evictedImportCount={} inFlightSkipCount={} protectedSkipCount={} cacheEvictionApplied={} cacheEvictionDeferred={}",
                    CAMERA_IMPORT_CACHE_LIMIT,
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
            .ok_or_else(|| "camera projection resources were not initialized".to_string())?;
        let import = import_camera_hardware_buffer(
            device,
            &self.memory_properties,
            resources,
            frame,
            key,
            format_key,
            allocation_size,
            memory_type_bits,
        )?;
        self.imports.push(import);
        let index = self.imports.len() - 1;
        transition_imported_camera_image(device, cmd, self.imports[index].image);
        self.imports[index].needs_layout_transition = false;

        crate::marker(
            "camera-projection-import",
            format!(
                "status=ok side={} cameraId={} sourceFrame={} hardwareBufferId={} width={} height={} nativeFormat={} layers={} stride={} usage={} externalFormat={} vkFormat={:?} allocationSize={} memoryTypeBits=0x{:x} descriptorShape=combined-immutable-sampler-ycbcr-conversion {} gpuImportWorked=true vulkanExternalImportReady=true",
                frame.side,
                crate::sanitize(&frame.camera_id),
                frame.source_frame,
                frame.hardware_buffer_id,
                frame.width,
                frame.height,
                frame.native_format,
                frame.layers,
                frame.stride,
                frame.usage,
                format_key.external_format,
                format_key.format,
                allocation_size,
                memory_type_bits,
                resources.ycbcr_metadata.marker_fields()
            ),
        );

        Ok(PreparedCameraImport {
            key,
            image_view: self.imports[index].image_view,
        })
    }

    unsafe fn destroy_imports(&mut self, device: &ash::Device) {
        for import in self.imports.drain(..) {
            import.destroy(device);
        }
    }

    unsafe fn destroy_stereo_descriptors(&mut self, device: &ash::Device) {
        for descriptor in self.stereo_descriptors.drain(..) {
            descriptor.destroy(device);
        }
    }

    unsafe fn destroy_stereo_descriptors_for_key(
        &mut self,
        device: &ash::Device,
        key: CameraImportKey,
    ) {
        let mut index = 0;
        while index < self.stereo_descriptors.len() {
            if self.stereo_descriptors[index].left_key == key
                || self.stereo_descriptors[index].right_key == key
            {
                let descriptor = self.stereo_descriptors.remove(index);
                descriptor.destroy(device);
            } else {
                index += 1;
            }
        }
    }
}

pub(crate) unsafe fn record_camera_projection_eye(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    extent: vk::Extent2D,
    eye_index: usize,
    prepared: &PreparedCameraProjection,
    projection_metadata: &CameraProjectionMetadata,
    direct_border_opacity: f32,
) {
    let target_rect = projection_metadata.rect_for_eye(eye_index);
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
    let push = CameraProjectionPush {
        params0: [
            eye_index as f32,
            projection_metadata.source_sample_y_flip,
            0.0,
            0.0,
        ],
        target_rect: [
            target_rect.x,
            target_rect.y,
            target_rect.width,
            target_rect.height,
        ],
        params2: [direct_border_opacity.clamp(0.0, 1.0), 0.0, 0.0, 0.0],
    };
    let push_bytes = std::slice::from_raw_parts(
        (&push as *const CameraProjectionPush).cast::<u8>(),
        std::mem::size_of::<CameraProjectionPush>(),
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

#[derive(Clone, Copy)]
struct PreparedCameraImport {
    key: CameraImportKey,
    image_view: vk::ImageView,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct CameraImportKey {
    buffer_id: u64,
    width: u32,
    height: u32,
    native_format: u32,
}

impl CameraImportKey {
    fn from_frame(frame: &NativeCameraFrame) -> Self {
        Self {
            buffer_id: if frame.hardware_buffer_id == 0 {
                frame.timestamp_ns as u64
            } else {
                frame.hardware_buffer_id
            },
            width: frame.width,
            height: frame.height,
            native_format: frame.native_format,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct CameraFormatKey {
    format: vk::Format,
    external_format: u64,
}

struct CameraProjectionResources {
    format_key: CameraFormatKey,
    sampler_ycbcr_conversion: vk::SamplerYcbcrConversion,
    sampler: vk::Sampler,
    ycbcr_metadata: CameraYcbcrConversionMetadata,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
}

impl CameraProjectionResources {
    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        device.destroy_sampler(self.sampler, None);
        device.destroy_sampler_ycbcr_conversion(self.sampler_ycbcr_conversion, None);
    }
}

#[derive(Clone, Debug)]
struct CameraYcbcrConversionMetadata {
    requested_mode: NativeCameraYcbcrMode,
    format_features: vk::FormatFeatureFlags,
    chroma_filter: vk::Filter,
    chroma_linear_filter_supported: bool,
    sampler_filter: vk::Filter,
    sampler_linear_filter_supported: bool,
    suggested_model: vk::SamplerYcbcrModelConversion,
    suggested_range: vk::SamplerYcbcrRange,
    effective_model: vk::SamplerYcbcrModelConversion,
    effective_range: vk::SamplerYcbcrRange,
    components: String,
    suggested_x_chroma_offset: vk::ChromaLocation,
    suggested_y_chroma_offset: vk::ChromaLocation,
}

impl CameraYcbcrConversionMetadata {
    fn from_format_props(
        format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
        requested_mode: NativeCameraYcbcrMode,
    ) -> Self {
        let chroma_linear_filter_supported = format_props
            .format_features
            .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER);
        let sampler_linear_filter_supported = format_props
            .format_features
            .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR);
        let (effective_model, effective_range) = match requested_mode {
            NativeCameraYcbcrMode::AndroidSuggested => (
                format_props.suggested_ycbcr_model,
                format_props.suggested_ycbcr_range,
            ),
            NativeCameraYcbcrMode::ForcedBt601Narrow => (
                vk::SamplerYcbcrModelConversion::YCBCR_601,
                vk::SamplerYcbcrRange::ITU_NARROW,
            ),
        };
        Self {
            requested_mode,
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
            effective_model,
            effective_range,
            components: ycbcr_component_mapping_label(
                format_props.sampler_ycbcr_conversion_components,
            ),
            suggested_x_chroma_offset: format_props.suggested_x_chroma_offset,
            suggested_y_chroma_offset: format_props.suggested_y_chroma_offset,
        }
    }

    fn marker_fields(&self) -> String {
        format!(
            "cameraYcbcrMode={} suggestedYcbcrModel={:?} suggestedYcbcrRange={:?} effectiveYcbcrModel={:?} effectiveYcbcrRange={:?} ycbcrComponents={} suggestedXChromaOffset={:?} suggestedYChromaOffset={:?} conversionMode={} formatFeaturesRaw=0x{:x} formatFeatures={} chromaFilter={:?} chromaLinearFilterSupported={} samplerFilter={:?} samplerLinearFilterSupported={} ycbcrFeatureValidation=active samplerBindingMode=combined-immutable-sampler colorDiagnostic=direct-hwb-ycbcr",
            self.requested_mode.marker_value(),
            self.suggested_model,
            self.suggested_range,
            self.effective_model,
            self.effective_range,
            self.components,
            self.suggested_x_chroma_offset,
            self.suggested_y_chroma_offset,
            self.requested_mode.conversion_mode(),
            self.format_features.as_raw(),
            format_feature_flags_marker(self.format_features),
            self.chroma_filter,
            self.chroma_linear_filter_supported,
            self.sampler_filter,
            self.sampler_linear_filter_supported,
        )
    }
}

struct CameraImport {
    key: CameraImportKey,
    image: vk::Image,
    memory: vk::DeviceMemory,
    image_view: vk::ImageView,
    needs_layout_transition: bool,
    _hardware_buffer: crate::native_camera::AndroidHardwareBufferHandle,
}

impl CameraImport {
    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_image_view(self.image_view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

struct CameraStereoDescriptor {
    left_key: CameraImportKey,
    right_key: CameraImportKey,
    descriptor_set: vk::DescriptorSet,
    descriptor_pool: vk::DescriptorPool,
}

impl CameraStereoDescriptor {
    unsafe fn destroy(self, device: &ash::Device) {
        let _ = device.free_descriptor_sets(self.descriptor_pool, &[self.descriptor_set]);
    }
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

#[repr(C)]
#[derive(Clone, Copy)]
struct CameraProjectionPush {
    params0: [f32; 4],
    target_rect: [f32; 4],
    params2: [f32; 4],
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

unsafe fn create_camera_projection_resources(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    render_pass: vk::RenderPass,
    format_key: CameraFormatKey,
    format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
    ycbcr_mode: NativeCameraYcbcrMode,
) -> Result<CameraProjectionResources, String> {
    let ycbcr_metadata = CameraYcbcrConversionMetadata::from_format_props(format_props, ycbcr_mode);
    let mut external_format =
        vk::ExternalFormatANDROID::default().external_format(format_key.external_format);
    let mut conversion_info = vk::SamplerYcbcrConversionCreateInfo::default()
        .format(format_key.format)
        .ycbcr_model(ycbcr_metadata.effective_model)
        .ycbcr_range(ycbcr_metadata.effective_range)
        .components(format_props.sampler_ycbcr_conversion_components)
        .x_chroma_offset(format_props.suggested_x_chroma_offset)
        .y_chroma_offset(format_props.suggested_y_chroma_offset)
        .chroma_filter(ycbcr_metadata.chroma_filter);
    if format_key.external_format != 0 {
        conversion_info = conversion_info.push_next(&mut external_format);
    }
    let sampler_ycbcr_conversion = device
        .create_sampler_ycbcr_conversion(&conversion_info, None)
        .map_err(|error| format!("create camera sampler YCbCr conversion: {error}"))?;

    let mut sampler_conversion_info =
        vk::SamplerYcbcrConversionInfo::default().conversion(sampler_ycbcr_conversion);
    let sampler = match device.create_sampler(
        &vk::SamplerCreateInfo::default()
            .mag_filter(ycbcr_metadata.sampler_filter)
            .min_filter(ycbcr_metadata.sampler_filter)
            .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
            .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .push_next(&mut sampler_conversion_info),
        None,
    ) {
        Ok(sampler) => sampler,
        Err(error) => {
            device.destroy_sampler_ycbcr_conversion(sampler_ycbcr_conversion, None);
            return Err(format!("create camera sampler: {error}"));
        }
    };

    let immutable_samplers = [sampler];
    let descriptor_bindings = [
        vk::DescriptorSetLayoutBinding::default()
            .binding(0)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(1)
            .stage_flags(vk::ShaderStageFlags::FRAGMENT)
            .immutable_samplers(&immutable_samplers),
        vk::DescriptorSetLayoutBinding::default()
            .binding(1)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(1)
            .stage_flags(vk::ShaderStageFlags::FRAGMENT)
            .immutable_samplers(&immutable_samplers),
    ];
    let descriptor_set_layout = match device.create_descriptor_set_layout(
        &vk::DescriptorSetLayoutCreateInfo::default().bindings(&descriptor_bindings),
        None,
    ) {
        Ok(layout) => layout,
        Err(error) => {
            device.destroy_sampler(sampler, None);
            device.destroy_sampler_ycbcr_conversion(sampler_ycbcr_conversion, None);
            return Err(format!("create camera descriptor set layout: {error}"));
        }
    };

    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count((CAMERA_STEREO_DESCRIPTOR_LIMIT as u32) * 2)];
    let descriptor_pool = match device.create_descriptor_pool(
        &vk::DescriptorPoolCreateInfo::default()
            .flags(vk::DescriptorPoolCreateFlags::FREE_DESCRIPTOR_SET)
            .pool_sizes(&pool_sizes)
            .max_sets(CAMERA_STEREO_DESCRIPTOR_LIMIT as u32),
        None,
    ) {
        Ok(pool) => pool,
        Err(error) => {
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_sampler_ycbcr_conversion(sampler_ycbcr_conversion, None);
            return Err(format!("create camera descriptor pool: {error}"));
        }
    };

    let push_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(std::mem::size_of::<CameraProjectionPush>() as u32)];
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
            device.destroy_sampler_ycbcr_conversion(sampler_ycbcr_conversion, None);
            return Err(format!("create camera pipeline layout: {error}"));
        }
    };
    let pipeline = match create_camera_projection_pipeline(device, render_pass, pipeline_layout) {
        Ok(pipeline) => pipeline,
        Err(error) => {
            device.destroy_pipeline_layout(pipeline_layout, None);
            device.destroy_descriptor_pool(descriptor_pool, None);
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            device.destroy_sampler_ycbcr_conversion(sampler_ycbcr_conversion, None);
            return Err(error);
        }
    };

    let _ = memory_properties;
    crate::marker(
        "camera-projection-resources",
        format!(
            "status=created externalFormat={} vkFormat={:?} descriptorShape=combined-immutable-sampler-ycbcr-conversion shaderPath=metadata-target-direct-hwb-camera-projection metadataDrivenTargetFootprint=true {}",
            format_key.external_format, format_key.format, ycbcr_metadata.marker_fields()
        ),
    );

    Ok(CameraProjectionResources {
        format_key,
        sampler_ycbcr_conversion,
        sampler,
        ycbcr_metadata,
        descriptor_set_layout,
        descriptor_pool,
        pipeline_layout,
        pipeline,
    })
}

unsafe fn allocate_camera_descriptor_set(
    device: &ash::Device,
    resources: &CameraProjectionResources,
    left_image_view: vk::ImageView,
    right_image_view: vk::ImageView,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [resources.descriptor_set_layout];
    let descriptor_set = device
        .allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(resources.descriptor_pool)
                .set_layouts(&set_layouts),
        )
        .map_err(|error| format!("allocate camera descriptor set: {error}"))?
        .pop()
        .ok_or_else(|| "camera descriptor allocation returned no set".to_string())?;

    let left_info = [vk::DescriptorImageInfo::default()
        .sampler(resources.sampler)
        .image_view(left_image_view)
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let right_info = [vk::DescriptorImageInfo::default()
        .sampler(resources.sampler)
        .image_view(right_image_view)
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let writes = [
        vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&left_info),
        vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(1)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&right_info),
    ];
    device.update_descriptor_sets(&writes, &[]);
    Ok(descriptor_set)
}

unsafe fn import_camera_hardware_buffer(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    resources: &CameraProjectionResources,
    frame: &NativeCameraFrame,
    key: CameraImportKey,
    format_key: CameraFormatKey,
    allocation_size: vk::DeviceSize,
    memory_type_bits: u32,
) -> Result<CameraImport, String> {
    let mut external_memory = vk::ExternalMemoryImageCreateInfo::default()
        .handle_types(vk::ExternalMemoryHandleTypeFlags::ANDROID_HARDWARE_BUFFER_ANDROID);
    let mut external_format =
        vk::ExternalFormatANDROID::default().external_format(format_key.external_format);
    let mut image_info = vk::ImageCreateInfo::default()
        .image_type(vk::ImageType::TYPE_2D)
        .format(format_key.format)
        .extent(vk::Extent3D {
            width: frame.width,
            height: frame.height,
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
    if format_key.external_format != 0 {
        image_info = image_info.push_next(&mut external_format);
    }
    let image = device
        .create_image(&image_info, None)
        .map_err(|error| format!("create imported camera image: {error}"))?;

    let memory_type_index = match find_memory_type_relaxed(memory_properties, memory_type_bits) {
        Ok(index) => index,
        Err(error) => {
            device.destroy_image(image, None);
            return Err(error);
        }
    };
    let mut import_info = vk::ImportAndroidHardwareBufferInfoANDROID::default()
        .buffer(frame.hardware_buffer.as_ptr().cast());
    let mut dedicated = vk::MemoryDedicatedAllocateInfo::default().image(image);
    let memory = match device.allocate_memory(
        &vk::MemoryAllocateInfo::default()
            .allocation_size(allocation_size)
            .memory_type_index(memory_type_index)
            .push_next(&mut import_info)
            .push_next(&mut dedicated),
        None,
    ) {
        Ok(memory) => memory,
        Err(error) => {
            device.destroy_image(image, None);
            return Err(format!("allocate imported camera memory: {error}"));
        }
    };
    if let Err(error) = device.bind_image_memory(image, memory, 0) {
        device.free_memory(memory, None);
        device.destroy_image(image, None);
        return Err(format!("bind imported camera memory: {error}"));
    }

    let mut view_conversion =
        vk::SamplerYcbcrConversionInfo::default().conversion(resources.sampler_ycbcr_conversion);
    let image_view = match device.create_image_view(
        &vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(format_key.format)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            })
            .push_next(&mut view_conversion),
        None,
    ) {
        Ok(image_view) => image_view,
        Err(error) => {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("create imported camera image view: {error}"));
        }
    };

    Ok(CameraImport {
        key,
        image,
        memory,
        image_view,
        needs_layout_transition: true,
        _hardware_buffer: frame.hardware_buffer.clone(),
    })
}

unsafe fn create_camera_projection_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/camera_projection.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/camera_projection.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create camera vertex shader module: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!("create camera fragment shader module: {error}"));
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
        .map_err(|(_, error)| format!("create camera graphics pipeline: {error}"))
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

unsafe fn transition_imported_camera_image(
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
        "no Vulkan memory type supports {required:?} for imported camera AHardwareBuffer"
    ))
}
