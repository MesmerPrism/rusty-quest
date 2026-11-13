//! Vulkan feedback projection for MediaProjection display-composite frames.
//!
//! The MediaProjection service remains the capture adapter. This renderer only
//! imports the latest Rust-owned `AHardwareBuffer` as a sampled RGBA image and
//! draws it into a diagnostic field-of-view target.

use std::ffi::CString;

use ash::vk;
#[path = "full_core_graph_vk.rs"]
mod full_core_graph_vk;
#[path = "paired_witness_policy.rs"]
mod paired_witness_policy;
#[path = "sampled_input_normalize_vk.rs"]
mod sampled_input_normalize_vk;

use crate::{
    ahardware_buffer_vulkan::{
        AhbVulkanDevice, AhbVulkanFormatKey, AhbVulkanSampledImage,
        AhbVulkanSampledImageCreateInfo, create_ahb_sampler_ycbcr_conversion, find_memory_type,
        import_ahb_sampled_image, query_ahb_vulkan_import_properties,
        transition_ahb_sampled_image_to_shader_read,
    },
    display_composite_capture_export,
    display_composite_native_stream::DisplayCompositeFrame,
    display_composite_projection_metadata::DisplayCompositeProjectionMetadata,
    native_renderer_display_composite_options::{
        NativeDisplayCompositeFeedbackProjection, NativeDisplayCompositeMode,
        NativeDisplayCompositeSettings,
    },
    projection_rect::TargetRect,
};

const DISPLAY_COMPOSITE_IMPORT_CACHE_LIMIT: usize = 8;
const DISPLAY_COMPOSITE_RECURSIVE_TEXTURE_COUNT: usize = 2;
const DISPLAY_COMPOSITE_RECURSIVE_MAX_WIDTH: u32 = 1024;
const DISPLAY_COMPOSITE_RECURSIVE_MIN_WIDTH: u32 = 320;
const DISPLAY_COMPOSITE_LEVEL_EXPORT_RENDER_FRAME_GAP: u64 = 30;
const DISPLAY_COMPOSITE_FINAL_BORDER_OPACITY_LEFT: f32 = 0.0;
const DISPLAY_COMPOSITE_FINAL_BORDER_OPACITY_RIGHT: f32 = 0.0;
const DISPLAY_COMPOSITE_FINAL_PLANE_OPACITY: f32 = 1.0;
const DISPLAY_COMPOSITE_RECURSIVE_PREVIOUS_ALPHA: f32 = 0.0;
const DISPLAY_COMPOSITE_RECURSIVE_INSET_SCALE: f32 = 0.78;
const DISPLAY_COMPOSITE_RECURSIVE_BORDER_OPACITY: f32 = 0.0;
const DISPLAY_COMPOSITE_RECURSIVE_BASE_GAIN: f32 = 0.50;

#[derive(Clone, Debug)]
pub(crate) struct DisplayCompositeFrameStats {
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
    pub(crate) recursive_feedback_enabled: bool,
    pub(crate) recursive_feedback_ready: bool,
    pub(crate) recursive_feedback_seeded: bool,
    pub(crate) recursive_feedback_width: u32,
    pub(crate) recursive_feedback_height: u32,
    pub(crate) recursive_feedback_write_index: u32,
    pub(crate) recursive_feedback_read_index: i32,
    pub(crate) allocation_size: vk::DeviceSize,
    pub(crate) memory_type_bits: u32,
    pub(crate) import_cache_hits: u64,
    pub(crate) import_cache_misses: u64,
    pub(crate) projection: NativeDisplayCompositeFeedbackProjection,
}

impl Default for DisplayCompositeFrameStats {
    fn default() -> Self {
        Self::unavailable(
            NativeDisplayCompositeFeedbackProjection::MetadataTargetScreenUv,
            "not-rendered",
        )
    }
}

impl DisplayCompositeFrameStats {
    pub(crate) fn unavailable(
        projection: NativeDisplayCompositeFeedbackProjection,
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
            recursive_feedback_enabled: false,
            recursive_feedback_ready: false,
            recursive_feedback_seeded: false,
            recursive_feedback_width: 0,
            recursive_feedback_height: 0,
            recursive_feedback_write_index: 0,
            recursive_feedback_read_index: -1,
            allocation_size: 0,
            memory_type_bits: 0,
            import_cache_hits: 0,
            import_cache_misses: 0,
            projection,
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        if crate::meta_input_diagnostic::enabled() {
            return format!(
                "metaInputReady={} metaInputRendered={} reason={} inputFrame={} inputTimestampNs={} hardwareBufferId={} width={} height={} vkFormat={:?} fullEye=true sourceUv=per-layout inputLayout={} monoSourceToBothEyes={} recursiveFeedback=false cpuPixelCopy=false sourceAuthority=external-meta-producer colorSpace=sampled-no-extra-conversion dataspace=producer-default-unverified",
                self.ready,
                self.rendered,
                self.reason,
                self.frame_index,
                self.timestamp_ns,
                self.hardware_buffer_id,
                self.descriptor_width,
                self.descriptor_height,
                self.vk_format,
                crate::meta_input_diagnostic::layout().name(),
                crate::meta_input_diagnostic::layout()
                    == crate::presentation_geometry::InputLayout::MonoLeft
            );
        }
        format!(
            "displayCompositeFeedbackReady={} displayCompositeFeedbackRendered={} displayCompositeFeedbackReason={} displayCompositeFeedbackProjection={} displayCompositeFrameIndex={} displayCompositeImportSequence={} displayCompositeTimestampNs={} displayCompositeHardwareBufferId={} displayCompositeDescriptorWidth={} displayCompositeDescriptorHeight={} displayCompositeDescriptorFormat={} displayCompositeDescriptorUsage={} displayCompositeDescriptorStride={} displayCompositeConfiguredWidth={} displayCompositeConfiguredHeight={} displayCompositeFpsCap={} displayCompositeDroppedFrames={} displayCompositeBufferRemovedCount={} displayCompositeExternalFormat={} displayCompositeVkFormat={:?} descriptorShape={} displayCompositeExternalFormatSampling={} displayCompositeSamplerYcbcrConversion={} displayCompositeDescriptorUsesImmutableSampler={} displayCompositeRecursiveFeedbackEnabled={} displayCompositeRecursiveFeedbackReady={} displayCompositeRecursiveFeedbackSeeded={} displayCompositeRecursiveFeedbackWidth={} displayCompositeRecursiveFeedbackHeight={} displayCompositeRecursiveFeedbackWriteIndex={} displayCompositeRecursiveFeedbackReadIndex={} displayCompositeRecursiveFeedbackSource=media-projection-current-frame-clean displayCompositeRecursiveFeedbackPreviousBlend=false displayCompositeRecursiveFeedbackPreviousAlpha={:.3} displayCompositeRecursiveFeedbackBorderOpacity={:.3} displayCompositeRecursiveFeedbackBaseGain={:.3} displayCompositeFinalBorderOpacityLeft={:.3} displayCompositeFinalBorderOpacityRight={:.3} displayCompositeFinalPlaneOpacity={:.3} displayCompositeFinalAlphaMode=premultiplied-openxr-projection-layer displayCompositeAllocationSize={} displayCompositeMemoryTypeBits=0x{:x} displayCompositeImportCacheHits={} displayCompositeImportCacheMisses={} displayCompositeGpuImportReady={} displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false highRateJsonPayload=false sourceAuthority=android-mediaprojection rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false",
            self.ready,
            self.rendered,
            self.reason,
            feedback_projection_label(self.projection),
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
            self.recursive_feedback_enabled,
            self.recursive_feedback_ready,
            self.recursive_feedback_seeded,
            self.recursive_feedback_width,
            self.recursive_feedback_height,
            self.recursive_feedback_write_index,
            self.recursive_feedback_read_index,
            DISPLAY_COMPOSITE_RECURSIVE_PREVIOUS_ALPHA,
            DISPLAY_COMPOSITE_RECURSIVE_BORDER_OPACITY,
            DISPLAY_COMPOSITE_RECURSIVE_BASE_GAIN,
            DISPLAY_COMPOSITE_FINAL_BORDER_OPACITY_LEFT,
            DISPLAY_COMPOSITE_FINAL_BORDER_OPACITY_RIGHT,
            DISPLAY_COMPOSITE_FINAL_PLANE_OPACITY,
            self.allocation_size,
            self.memory_type_bits,
            self.import_cache_hits,
            self.import_cache_misses,
            self.ready
        )
    }
}

pub(crate) struct PreparedDisplayCompositeFeedback {
    meta_completion: Option<crate::meta_input_diagnostic::Completion>,
    /// Snapshot once per frame, shared by both eye draws.
    meta_effect_mode: f32,
    full3_slot: Option<usize>,
    pub(crate) descriptor_set: vk::DescriptorSet,
    pub(crate) pipeline_layout: vk::PipelineLayout,
    pub(crate) pipeline: vk::Pipeline,
    pub(crate) stats: DisplayCompositeFrameStats,
}

pub(crate) struct DisplayCompositeFeedbackRenderer {
    full3: Option<full_core_graph_vk::FullCoreGraph>,
    input_normalizer: Option<sampled_input_normalize_vk::InputNormalizer>,
    graphics_queue_family: u32,
    presentation_readbacks: [Option<DisplayCompositeReadbackResources>; 2],
    full3_raw_readbacks: [Option<DisplayCompositeReadbackResources>; 2],
    presentation_pending: Vec<Option<PresentationWitnessPending>>,
    presentation_sequences: Vec<u64>,
    meta_pending_releases: Vec<Vec<vk::Image>>,
    meta_witness_sequences: Vec<u64>,
    meta_source_readback: Option<DisplayCompositeReadbackResources>,
    meta_source_pending: Vec<Option<DisplayCompositePendingGpuReadback>>,
    meta_submitted: Vec<Option<crate::meta_input_diagnostic::Completion>>,
    ahb: Option<AhbVulkanDevice>,
    memory_properties: vk::PhysicalDeviceMemoryProperties,
    render_pass: vk::RenderPass,
    feedback_format: vk::Format,
    resources: Option<DisplayCompositeResources>,
    recursive: Option<DisplayCompositeRecursiveResources>,
    readback: Option<DisplayCompositeReadbackResources>,
    source_level_readback: Option<DisplayCompositeReadbackResources>,
    feedback_level_readback: Option<DisplayCompositeReadbackResources>,
    pending_readbacks: Vec<Option<DisplayCompositePendingGpuReadback>>,
    pending_source_level_readbacks: Vec<Option<DisplayCompositePendingLevelReadback>>,
    pending_feedback_level_readbacks: Vec<Option<DisplayCompositePendingLevelReadback>>,
    imports: Vec<DisplayCompositeImport>,
    import_cache_hits: u64,
    import_cache_misses: u64,
    gpu_frame_hardware_buffer_ids: Vec<Vec<u64>>,
    gpu_frame_image_leases: Vec<Vec<std::sync::Arc<crate::meta_input_diagnostic::ImageLease>>>,
    last_feedback_level_export_import_sequence: u64,
    last_feedback_level_export_render_frame: u64,
}

impl DisplayCompositeFeedbackRenderer {
    pub(crate) unsafe fn new(
        instance: &ash::Instance,
        device: &ash::Device,
        memory_properties: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        feedback_format: vk::Format,
        import_supported: bool,
        graphics_queue_family: u32,
    ) -> Self {
        let ahb = import_supported.then(|| {
            ash::android::external_memory_android_hardware_buffer::Device::new(instance, device)
        });
        Self {
            full3: None,
            input_normalizer: None,
            graphics_queue_family,
            presentation_readbacks: [None, None],
            full3_raw_readbacks: [None, None],
            presentation_pending: Vec::new(),
            presentation_sequences: Vec::new(),
            meta_pending_releases: Vec::new(),
            meta_witness_sequences: Vec::new(),
            meta_source_readback: None,
            meta_source_pending: Vec::new(),
            meta_submitted: Vec::new(),
            ahb,
            memory_properties,
            render_pass,
            feedback_format,
            resources: None,
            recursive: None,
            readback: None,
            source_level_readback: None,
            feedback_level_readback: None,
            pending_readbacks: Vec::new(),
            pending_source_level_readbacks: Vec::new(),
            pending_feedback_level_readbacks: Vec::new(),
            imports: Vec::new(),
            import_cache_hits: 0,
            import_cache_misses: 0,
            gpu_frame_hardware_buffer_ids: Vec::new(),
            gpu_frame_image_leases: Vec::new(),
            last_feedback_level_export_import_sequence: 0,
            last_feedback_level_export_render_frame: 0,
        }
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(normalizer) = self.input_normalizer.take() {
            normalizer.destroy(device);
        }
        if let Some(graph) = self.full3.take() {
            graph.destroy(device);
        }
        self.gpu_frame_hardware_buffer_ids.clear();
        self.gpu_frame_image_leases.clear();
        self.presentation_pending.clear();
        for r in self.full3_raw_readbacks.iter_mut().filter_map(Option::take) {
            r.destroy(device);
        }
        for r in self
            .presentation_readbacks
            .iter_mut()
            .filter_map(Option::take)
        {
            r.destroy(device);
        }
        self.meta_source_pending.clear();
        if let Some(r) = self.meta_source_readback.take() {
            r.destroy(device);
        }
        self.pending_readbacks.clear();
        self.pending_source_level_readbacks.clear();
        self.pending_feedback_level_readbacks.clear();
        self.destroy_imports(device);
        if let Some(readback) = self.feedback_level_readback.take() {
            readback.destroy(device);
        }
        if let Some(readback) = self.source_level_readback.take() {
            readback.destroy(device);
        }
        if let Some(readback) = self.readback.take() {
            readback.destroy(device);
        }
        if let Some(recursive) = self.recursive.take() {
            recursive.destroy(device);
        }
        if let Some(resources) = self.resources.take() {
            resources.destroy(device);
        }
    }

    pub(crate) fn retire_completed_frame_handles(&mut self, frame_slot: usize) {
        if let Some(Some(completion)) = self.meta_submitted.get_mut(frame_slot).map(Option::take) {
            crate::meta_input_diagnostic::completed(completion);
        }
        if let Some(leases) = self.gpu_frame_image_leases.get_mut(frame_slot) {
            leases.clear();
        }
        if let Some(ids) = self.gpu_frame_hardware_buffer_ids.get_mut(frame_slot) {
            ids.clear();
        }
    }
    /// Owner has established device idle and reset EVERY original command buffer.
    /// Unsubmitted/reset fences are intentionally not waited: idle proves queue completion.
    pub(crate) unsafe fn retire_all_source_after_idle(
        &mut self,
        device: &ash::Device,
        slots: usize,
    ) -> crate::held_review_policy::Drain {
        for slot in 0..slots {
            self.collect_completed_diagnostic_exports(device, slot);
            self.retire_completed_frame_handles(slot);
        }
        let pending_exports = self
            .presentation_pending
            .iter()
            .filter(|x| x.is_some())
            .count()
            + self
                .meta_source_pending
                .iter()
                .filter(|x| x.is_some())
                .count()
            + self
                .pending_readbacks
                .iter()
                .filter(|x| x.is_some())
                .count()
            + self
                .pending_source_level_readbacks
                .iter()
                .filter(|x| x.is_some())
                .count()
            + self
                .pending_feedback_level_readbacks
                .iter()
                .filter(|x| x.is_some())
                .count();
        let pending_releases = self.meta_pending_releases.iter().map(Vec::len).sum();
        let image_refs = self.gpu_frame_image_leases.iter().map(Vec::len).sum();
        self.destroy(device);
        crate::held_review_policy::Drain {
            gpu_idle: true,
            recordings_reset: true,
            callbacks_sealed: crate::meta_input_diagnostic::acquisitions_sealed(),
            acquired_images: crate::meta_input_diagnostic::acquired_image_count(),
            image_refs,
            imports: self.imports.len(),
            pending_exports,
            pending_releases,
            java_cleanup: crate::held_review_policy::JavaCleanup::NotAttempted,
        }
    }
    pub(crate) fn mark_meta_submitted(
        &mut self,
        frame_slot: usize,
        prepared: Option<&PreparedDisplayCompositeFeedback>,
    ) {
        while self.meta_submitted.len() <= frame_slot {
            self.meta_submitted.push(None);
        }
        self.meta_submitted[frame_slot] = prepared.and_then(|p| p.meta_completion);
    }

    /// Always called after the final eye render pass, even if prepare_frame
    /// returned an error after recording an ownership acquire.
    pub(crate) unsafe fn release_meta_inputs(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
    ) {
        if let Some(images) = self.meta_pending_releases.get_mut(frame_slot) {
            for image in images.drain(..) {
                meta_foreign_barrier(device, cmd, image, self.graphics_queue_family, false);
            }
        }
    }

    pub(crate) unsafe fn collect_completed_diagnostic_exports(
        &mut self,
        device: &ash::Device,
        frame_slot: usize,
    ) {
        if let Some(pending) = self
            .presentation_pending
            .get_mut(frame_slot)
            .and_then(Option::take)
        {
            let identity = paired_witness_policy::Identity {
                generation: pending.completion.generation,
                sequence: pending.completion.sequence,
                input_frame: pending.completion.input_frame,
                timestamp_ns: pending.completion.timestamp_ns,
                render_frame: pending.completion.render_frame,
            };
            let mut pair = paired_witness_policy::PairCompletion::after_retired_fence(identity);
            for eye in 0..2 {
                let result = self.presentation_readbacks[eye]
                    .as_ref()
                    .ok_or_else(|| "Missing eye-sized presentation witness".to_string())
                    .and_then(|r| r.copy_completed_frame_bytes(device, frame_slot))
                    .and_then(|bytes| {
                        crate::presentation_diagnostic::write_witness(
                            pending.completion,
                            pending.timing,
                            eye,
                            self.feedback_format,
                            &pending.metadata,
                            &bytes,
                        )
                    });
                if let Err(e) = result {
                    crate::marker(
                        "presentation-witness",
                        format!("status=error reason={}", crate::sanitize(&e)),
                    );
                } else {
                    pair.mark_durable(identity, eye, paired_witness_policy::Stage::Processed);
                }
            }
            if pending.completion.mode == 2 {
                for eye in 0..2 {
                    let result = self.full3_raw_readbacks[eye]
                        .as_ref()
                        .ok_or_else(|| "Missing paired raw-pack witness".to_string())
                        .and_then(|r| r.copy_completed_frame_bytes(device, frame_slot))
                        .and_then(|bytes| {
                            crate::meta_input_diagnostic::write_full_core3_paired_raw(
                                pending.completion,
                                pending.timing,
                                eye,
                                &pending.metadata,
                                &bytes,
                            )
                        });
                    if let Err(e) = result {
                        crate::marker(
                            "presentation-witness",
                            format!("status=error stage=raw-pack reason={}", crate::sanitize(&e)),
                        );
                    } else {
                        pair.mark_durable(identity, eye, paired_witness_policy::Stage::RawPack);
                    }
                }
            }
            if pair.complete() && pending.completion.mode == 2 {
                crate::meta_input_diagnostic::full_core3_witness_completed(
                    pending.completion.generation,
                    pending.completion.sequence,
                );
            }
        }
        if crate::meta_input_diagnostic::enabled() {
            for raw_input in [false, true] {
                let pending = if raw_input {
                    self.meta_source_pending.get_mut(frame_slot)
                } else {
                    self.pending_readbacks.get_mut(frame_slot)
                }
                .and_then(Option::take);
                if let Some(pending) = pending {
                    if let Some((mode, generation, sequence)) = pending.meta_witness {
                        let readback = if raw_input {
                            self.meta_source_readback.as_ref()
                        } else {
                            self.readback.as_ref()
                        };
                        let result = readback
                            .ok_or_else(|| "Missing Meta witness target".to_string())
                            .and_then(|r| r.copy_completed_frame_bytes(device, frame_slot))
                            .and_then(|bytes| {
                                crate::meta_input_diagnostic::write_witness(
                                    mode,
                                    generation,
                                    sequence,
                                    raw_input,
                                    pending.meta_witness_timing.ok_or_else(|| {
                                        "Missing steady-phase witness timing".to_string()
                                    })?,
                                    pending.timestamp_ns,
                                    pending.frame_index,
                                    pending.render_frame_count,
                                    pending.width,
                                    pending.height,
                                    self.feedback_format,
                                    &bytes,
                                )
                            });
                        if let Err(e) = result {
                            crate::marker(
                                "meta-input-witness",
                                format!("status=error reason={}", crate::sanitize(&e)),
                            );
                        }
                    }
                }
            }
            return;
        }
        if !display_composite_capture_export::export_enabled() {
            return;
        }

        if !display_composite_capture_export::gpu_export_done() {
            if let Some(Some(pending)) =
                self.pending_readbacks.get_mut(frame_slot).map(Option::take)
            {
                if let Some(readback) = self.readback.as_ref() {
                    match readback.copy_completed_frame_bytes(device, frame_slot) {
                        Ok(bytes) => {
                            display_composite_capture_export::write_gpu_sampled_frame(
                                pending.frame_index,
                                pending.import_sequence,
                                pending.timestamp_ns,
                                pending.hardware_buffer_id,
                                pending.width,
                                pending.height,
                                self.feedback_format,
                                pending.render_frame_count,
                                pending.xr_ready_marker_active,
                                &bytes,
                            );
                        }
                        Err(error) => {
                            crate::marker(
                                "display-composite-capture-export",
                                format!(
                                    "status=error stage=gpu-sampled-frame reason={}",
                                    crate::sanitize(&error)
                                ),
                            );
                        }
                    }
                }
            }
        }

        if let Some(Some(pending)) = self
            .pending_source_level_readbacks
            .get_mut(frame_slot)
            .map(Option::take)
        {
            if let Some(readback) = self.source_level_readback.as_ref() {
                match readback.copy_completed_frame_bytes(device, frame_slot) {
                    Ok(bytes) => {
                        display_composite_capture_export::write_gpu_source_level_frame(
                            pending.level_index,
                            pending.frame_index,
                            pending.import_sequence,
                            pending.timestamp_ns,
                            pending.hardware_buffer_id,
                            pending.width,
                            pending.height,
                            self.feedback_format,
                            pending.render_frame_count,
                            pending.xr_ready_marker_active,
                            pending.recursive_feedback_seeded,
                            &bytes,
                        );
                    }
                    Err(error) => {
                        crate::marker(
                            "display-composite-capture-export",
                            format!(
                                "status=error stage=media-projection-gpu-level reason={} levelIndex={}",
                                crate::sanitize(&error),
                                pending.level_index
                            ),
                        );
                    }
                }
            }
        }

        if let Some(Some(pending)) = self
            .pending_feedback_level_readbacks
            .get_mut(frame_slot)
            .map(Option::take)
        {
            if let Some(readback) = self.feedback_level_readback.as_ref() {
                match readback.copy_completed_frame_bytes(device, frame_slot) {
                    Ok(bytes) => {
                        display_composite_capture_export::write_feedback_screen_level_frame(
                            pending.level_index,
                            pending.frame_index,
                            pending.import_sequence,
                            pending.timestamp_ns,
                            pending.hardware_buffer_id,
                            pending.width,
                            pending.height,
                            self.feedback_format,
                            pending.render_frame_count,
                            pending.xr_ready_marker_active,
                            pending.recursive_feedback_seeded,
                            pending.recursive_feedback_write_index,
                            pending.recursive_feedback_read_index,
                            &bytes,
                        );
                    }
                    Err(error) => {
                        crate::marker(
                            "display-composite-capture-export",
                            format!(
                                "status=error stage=feedback-screen-level reason={} levelIndex={}",
                                crate::sanitize(&error),
                                pending.level_index
                            ),
                        );
                    }
                }
            }
        }
    }

    pub(crate) unsafe fn prepare_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        render_frame_count: u64,
        xr_ready_marker_active: bool,
        frame: &DisplayCompositeFrame,
        settings: NativeDisplayCompositeSettings,
        projection_metadata: &DisplayCompositeProjectionMetadata,
    ) -> Result<Option<PreparedDisplayCompositeFeedback>, String> {
        let meta_selection = if frame.source_generation != 0 {
            match crate::meta_input_diagnostic::selection() {
                Some(value) if value.1 == frame.source_generation => Some(value),
                _ => return Ok(None),
            }
        } else {
            None
        };
        let meta_effect_mode = meta_selection.map_or(0, |s| s.0);
        if !display_composite_feedback_active(settings) {
            return Ok(None);
        }
        let Some(ahb) = self.ahb.as_ref() else {
            return Ok(None);
        };
        if !display_composite_capture_export::xr_gpu_upload_ready(
            render_frame_count,
            xr_ready_marker_active,
        ) {
            if frame.frame_index == 1 || frame.frame_index % 60 == 0 {
                crate::marker(
                    "display-composite-feedback-import",
                    format!(
                        "status=blocked reason=awaiting-xr-ready-marker frameIndex={} importSequence={} renderFrame={} xrReadyMarkerActive={} xrReadyMarker={} gpuImportWorked=false displayCompositeGpuImportReady=false displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image",
                        frame.frame_index,
                        frame.import_sequence,
                        render_frame_count,
                        xr_ready_marker_active,
                        crate::sanitize(display_composite_capture_export::xr_ready_marker_label())
                    ),
                );
            }
            return Ok(None);
        }
        if frame.source_generation == 0
            && !display_composite_capture_export::media_projection_source_ready(frame)
        {
            return Ok(None);
        }

        let (import_properties, format_props) =
            query_ahb_vulkan_import_properties(ahb, &frame.hardware_buffer)?;
        let format_key = import_properties.format_key;
        if frame.source_generation != 0
            && self
                .resources
                .as_ref()
                .is_some_and(|r| r.format_key != format_key)
        {
            crate::meta_input_diagnostic::stop();
            return Err(
                "Meta diagnostic rejects a Vulkan format change within its immutable generation"
                    .into(),
            );
        }
        if format_key.format == vk::Format::UNDEFINED && format_key.external_format == 0 {
            return Err(
                "display-composite sampled path got no Vulkan format or Android external format"
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
            if let Some(readback) = self.readback.take() {
                readback.destroy(device);
            }
            if let Some(readback) = self.source_level_readback.take() {
                readback.destroy(device);
            }
            if let Some(readback) = self.feedback_level_readback.take() {
                readback.destroy(device);
            }
            self.pending_readbacks.clear();
            self.pending_source_level_readbacks.clear();
            self.pending_feedback_level_readbacks.clear();
            self.last_feedback_level_export_import_sequence = 0;
            self.last_feedback_level_export_render_frame = 0;
            if let Some(recursive) = self.recursive.take() {
                recursive.destroy(device);
            }
            if let Some(normalizer) = self.input_normalizer.take() {
                normalizer.destroy(device);
            }
            if let Some(resources) = self.resources.take() {
                resources.destroy(device);
            }
            self.resources = Some(create_display_composite_resources(
                device,
                self.render_pass,
                format_key,
                &format_props,
            )?);
        }

        let protected_hardware_buffer_id = frame.descriptor.hardware_buffer_id;
        while self.meta_pending_releases.len() <= frame_slot {
            self.meta_pending_releases.push(Vec::new());
        }
        if let Some(lease) = frame.image_lease.as_ref() {
            // Retain BEFORE the first recorded transition/readback/draw. The
            // caller can submit this command buffer even if preparation fails.
            while self.gpu_frame_image_leases.len() <= frame_slot {
                self.gpu_frame_image_leases.push(Vec::new());
            }
            self.gpu_frame_image_leases[frame_slot].push(lease.clone());
            self.track_frame_hardware_buffer_id(frame_slot, protected_hardware_buffer_id);
        }
        let key = DisplayCompositeImportKey::from_frame(frame);
        let import_index = if let Some(index) =
            self.imports.iter().position(|import| import.key == key)
        {
            self.import_cache_hits = self.import_cache_hits.saturating_add(1);
            if frame.source_generation != 0 {
                let image = self.imports[index].sampled_image.image;
                self.meta_pending_releases[frame_slot].push(image);
                meta_foreign_barrier(device, cmd, image, self.graphics_queue_family, true);
            } else if self.imports[index].needs_layout_transition {
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
                    "display-composite-feedback-cache",
                    format!(
                        "status=import-lru-eviction importCacheLimit={} importsBefore={} importsAfter={} evictionAttempts={} evictedImportCount={} inFlightSkipCount={} protectedSkipCount={} cacheEvictionApplied={} cacheEvictionDeferred={} stream=display_composite",
                        DISPLAY_COMPOSITE_IMPORT_CACHE_LIMIT,
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

            let resources = self.resources.as_ref().ok_or_else(|| {
                "display-composite feedback resources were not initialized".to_string()
            })?;
            let mut import = import_display_composite_hardware_buffer(
                device,
                &self.memory_properties,
                resources,
                frame,
                key,
                format_key,
                import_properties.allocation_size,
                import_properties.memory_type_bits,
            )?;
            if frame.source_generation != 0 {
                self.meta_pending_releases[frame_slot].push(import.sampled_image.image);
                meta_foreign_barrier(
                    device,
                    cmd,
                    import.sampled_image.image,
                    self.graphics_queue_family,
                    true,
                );
            } else {
                transition_ahb_sampled_image_to_shader_read(
                    device,
                    cmd,
                    import.sampled_image.image,
                );
            }
            import.needs_layout_transition = false;
            self.imports.push(import);
            let import_index = self.imports.len() - 1;

            crate::marker(
                "display-composite-feedback-import",
                format!(
                    "status=ok stream=display_composite frameIndex={} importSequence={} timestampNs={} hardwareBufferId={} width={} height={} descriptorFormat={} descriptorUsage={} descriptorStride={} externalFormat={} vkFormat={:?} allocationSize={} memoryTypeBits=0x{:x} descriptorShape={} displayCompositeExternalFormatSampling={} displayCompositeSamplerYcbcrConversion={} displayCompositeDescriptorUsesImmutableSampler={} gpuImportWorked=true displayCompositeGpuImportReady=true displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
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

        let (mut descriptor_set, mut pipeline_layout, mut pipeline) = {
            let resources = self.resources.as_ref().ok_or_else(|| {
                "display-composite feedback resources were not initialized".to_string()
            })?;
            (
                self.imports[import_index].descriptor_set,
                resources.pipeline_layout,
                resources.pipeline,
            )
        };
        let mut recursive_feedback_enabled = false;
        let mut recursive_feedback_ready = false;
        let mut recursive_feedback_seeded = false;
        let mut recursive_feedback_width = 0;
        let mut recursive_feedback_height = 0;
        let mut recursive_feedback_write_index = 0;
        let mut recursive_feedback_read_index = -1;
        let (
            descriptor_shape,
            external_format_sampling,
            sampler_ycbcr_conversion,
            descriptor_uses_immutable_sampler,
        ) = {
            let resources = self.resources.as_ref().ok_or_else(|| {
                "display-composite feedback resources were not initialized".to_string()
            })?;
            (
                resources.descriptor_shape(),
                resources.sampler_ycbcr_conversion.is_some(),
                resources.sampler_ycbcr_conversion.is_some(),
                resources.descriptor_uses_immutable_sampler,
            )
        };
        if display_composite_recursive_feedback_active(settings) {
            recursive_feedback_enabled = true;
            let recursive_target = self.record_recursive_feedback_texture(
                device,
                cmd,
                frame_slot,
                frame,
                projection_metadata,
                format_key,
                &format_props,
                self.imports[import_index].sampled_image.image_view,
            )?;
            if display_composite_capture_export::export_enabled()
                && !display_composite_capture_export::feedback_level_export_done()
                && display_composite_capture_export::stable_export_frame_ready(
                    frame,
                    render_frame_count,
                    xr_ready_marker_active,
                )
                && self.last_feedback_level_export_import_sequence != frame.import_sequence
                && (self.last_feedback_level_export_render_frame == 0
                    || render_frame_count
                        .saturating_sub(self.last_feedback_level_export_render_frame)
                        >= DISPLAY_COMPOSITE_LEVEL_EXPORT_RENDER_FRAME_GAP)
            {
                if let Some(level_index) =
                    display_composite_capture_export::reserve_feedback_level_export_index()
                {
                    self.last_feedback_level_export_import_sequence = frame.import_sequence;
                    self.last_feedback_level_export_render_frame = render_frame_count;
                    let (source_pipeline_layout, source_pipeline) = {
                        let resources = self.resources.as_ref().ok_or_else(|| {
                            "display-composite feedback resources were not initialized".to_string()
                        })?;
                        (resources.pipeline_layout, resources.pipeline)
                    };
                    self.record_feedback_level_exports(
                        device,
                        cmd,
                        frame_slot,
                        level_index,
                        render_frame_count,
                        xr_ready_marker_active,
                        frame,
                        projection_metadata,
                        source_pipeline_layout,
                        source_pipeline,
                        self.imports[import_index].descriptor_set,
                        &recursive_target,
                    )?;
                }
            }
            descriptor_set = recursive_target.descriptor_set;
            pipeline_layout = recursive_target.pipeline_layout;
            pipeline = recursive_target.pipeline;
            recursive_feedback_ready = true;
            recursive_feedback_seeded = recursive_target.seeded;
            recursive_feedback_width = recursive_target.extent.width;
            recursive_feedback_height = recursive_target.extent.height;
            recursive_feedback_write_index = recursive_target.write_index;
            recursive_feedback_read_index = recursive_target.read_index;
        }
        // Mode 2 witnesses must use the actual graph's final pipeline below;
        // the legacy direct fragment is not a FullCore3 readback implementation.
        if let Some((mode, generation, sequence)) = meta_selection.filter(|s| s.0 != 2) {
            // Bounded diagnostic: at most four admitted control sequences,
            // once each. Colorama also records a raw pass of this SAME AImage.
            if let Some(timing) = crate::meta_input_diagnostic::witness_timing(generation, sequence)
                .filter(|_| {
                    render_frame_count >= 180
                        && self.meta_witness_sequences.len() < 4
                        && !self.meta_witness_sequences.contains(&sequence)
                })
            {
                if mode == 1 {
                    std::mem::swap(&mut self.readback, &mut self.meta_source_readback);
                    std::mem::swap(&mut self.pending_readbacks, &mut self.meta_source_pending);
                    let result = self.record_gpu_readback_export(
                        device,
                        cmd,
                        frame_slot,
                        render_frame_count,
                        xr_ready_marker_active,
                        frame,
                        projection_metadata,
                        pipeline_layout,
                        pipeline,
                        descriptor_set,
                        Some((0, generation, sequence)),
                        Some(timing),
                    );
                    // Restore buckets even if preparation failed; all sampler
                    // commands remain inside this frame's foreign ownership.
                    std::mem::swap(&mut self.readback, &mut self.meta_source_readback);
                    std::mem::swap(&mut self.pending_readbacks, &mut self.meta_source_pending);
                    result?;
                }
                self.record_gpu_readback_export(
                    device,
                    cmd,
                    frame_slot,
                    render_frame_count,
                    xr_ready_marker_active,
                    frame,
                    projection_metadata,
                    pipeline_layout,
                    pipeline,
                    descriptor_set,
                    meta_selection,
                    Some(timing),
                )?;
                self.meta_witness_sequences.push(sequence);
            }
        }
        if display_composite_gpu_readback_active(settings)
            && display_composite_capture_export::export_enabled()
            && !display_composite_capture_export::gpu_export_done()
            && display_composite_capture_export::stable_export_frame_ready(
                frame,
                render_frame_count,
                xr_ready_marker_active,
            )
        {
            let (readback_pipeline_layout, readback_pipeline) = {
                let resources = self.resources.as_ref().ok_or_else(|| {
                    "display-composite feedback resources were not initialized".to_string()
                })?;
                (resources.pipeline_layout, resources.pipeline)
            };
            display_composite_capture_export::try_export_media_projection_frame(
                frame,
                render_frame_count,
                xr_ready_marker_active,
            );
            self.record_gpu_readback_export(
                device,
                cmd,
                frame_slot,
                render_frame_count,
                xr_ready_marker_active,
                frame,
                projection_metadata,
                readback_pipeline_layout,
                readback_pipeline,
                self.imports[import_index].descriptor_set,
                None,
                None,
            )?;
        }
        self.track_frame_hardware_buffer_id(frame_slot, protected_hardware_buffer_id);

        Ok(Some(PreparedDisplayCompositeFeedback {
            meta_completion: meta_selection.map(|(mode, generation, sequence)| {
                crate::meta_input_diagnostic::Completion {
                    mode,
                    generation,
                    sequence,
                    render_frame: render_frame_count,
                    input_frame: frame.frame_index,
                    timestamp_ns: frame.timestamp_ns,
                }
            }),
            meta_effect_mode: meta_effect_mode as f32,
            full3_slot: None,
            descriptor_set,
            pipeline_layout,
            pipeline,
            stats: DisplayCompositeFrameStats {
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
                recursive_feedback_enabled,
                recursive_feedback_ready,
                recursive_feedback_seeded,
                recursive_feedback_width,
                recursive_feedback_height,
                recursive_feedback_write_index,
                recursive_feedback_read_index,
                allocation_size: import_properties.allocation_size,
                memory_type_bits: import_properties.memory_type_bits,
                import_cache_hits: self.import_cache_hits,
                import_cache_misses: self.import_cache_misses,
                projection: settings.feedback_projection,
            },
        }))
    }

    pub(crate) unsafe fn prepare_full_core3(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        prepared: &mut PreparedDisplayCompositeFeedback,
        presentation: Option<&crate::presentation_diagnostic::Frame>,
    ) -> Result<(), String> {
        if prepared.meta_effect_mode != 2.0 {
            return Ok(());
        }
        if !crate::meta_input_diagnostic::full_core3_available() {
            return Err(
                "FullCore3 refused: verified bundle and explicit stereo bounds required".into(),
            );
        }
        if !matches!(
            self.feedback_format,
            vk::Format::R8G8B8A8_UNORM | vk::Format::B8G8R8A8_UNORM
        ) {
            return Err(format!(
                "FullCore3 refused: encoded output requires UNORM, actual {:?}",
                self.feedback_format
            ));
        }
        if prepared.stats.descriptor_width != 2 * crate::full_core_graph::EYE[0]
            || prepared.stats.descriptor_height != crate::full_core_graph::EYE[1]
            || prepared.stats.recursive_feedback_ready
        {
            return Err("FullCore3 refused: exact nonrecursive 2560x1280 input required".into());
        }
        let frame = presentation.ok_or("FullCore3 requires current presentation geometry")?;
        let resources = self
            .resources
            .as_ref()
            .ok_or("FullCore3 missing import owner")?;
        let route = crate::sampled_input_adapter::route(
            matches!(
                prepared.stats.vk_format,
                vk::Format::R8G8B8A8_UNORM | vk::Format::B8G8R8A8_UNORM
            ),
            prepared.stats.vk_format == vk::Format::UNDEFINED,
            prepared.stats.external_format,
            prepared.stats.sampler_ycbcr_conversion,
            prepared.stats.descriptor_uses_immutable_sampler,
            resources.sampler_filter == vk::Filter::LINEAR,
        )
        .map_err(|e| format!("FullCore3 input refused: {e}"))?;
        for (eye, geometry) in frame.eyes.iter().enumerate() {
            if geometry.source_uv_rect != [eye as f32 * 0.5, 0., 0.5, 1.]
                || Some(geometry.source_sample_bounds) != crate::full_core_graph::input_bounds(eye)
            {
                return Err(
                    "FullCore3 refused: packed stereo bounds disagree with shader ABI".into(),
                );
            }
        }
        let input = if route == crate::sampled_input_adapter::Route::ConvertedToOwnedUnorm {
            let input_layout = self.resources.as_ref().unwrap().descriptor_set_layout;
            if self.input_normalizer.is_none() {
                self.input_normalizer = Some(sampled_input_normalize_vk::InputNormalizer::new(
                    device,
                    input_layout,
                )?);
                crate::marker(
                    "sampled-input-adapter",
                    "status=created path=import-owner-immutable-conversion-to-owned-unorm outputFormat=R8G8B8A8_UNORM orientation=unchanged perFrameSlot=true gpuCompletionProven=false",
                );
            }
            self.input_normalizer.as_mut().unwrap().record(
                device,
                &self.memory_properties,
                cmd,
                frame_slot,
                vk::Extent2D {
                    width: prepared.stats.descriptor_width,
                    height: prepared.stats.descriptor_height,
                },
                input_layout,
                prepared.descriptor_set,
            )?
        } else {
            prepared.descriptor_set
        };
        if self.full3.is_none() {
            self.full3 = Some(full_core_graph_vk::FullCoreGraph::new(
                device,
                self.render_pass,
            )?);
            crate::marker(
                "full-core-graph",
                format!(
                    "status=created abi=presentation128-v1 passes=3 atlas=3072x1664 rawEye=1280x1280 guide=384 format={:?} intermediates=R8G8B8A8_UNORM perFrameSlot=true privateBundleVerified=true noFallback=true",
                    self.feedback_format
                ),
            );
        }
        self.full3.as_mut().unwrap().record(
            device,
            cmd,
            &self.memory_properties,
            frame_slot,
            input,
            frame,
        )?;
        prepared.full3_slot = Some(frame_slot);
        Ok(())
    }

    pub(crate) unsafe fn record_feedback_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_index: usize,
        projection_metadata: &DisplayCompositeProjectionMetadata,
        target_rect: TargetRect,
        prepared: &PreparedDisplayCompositeFeedback,
        presentation: Option<&crate::presentation_diagnostic::Eye>,
    ) {
        if let Some(slot) = prepared.full3_slot {
            self.full3
                .as_ref()
                .expect("prepared FullCore3 graph retained")
                .record_final(
                    device,
                    cmd,
                    slot,
                    extent,
                    eye_index,
                    presentation.expect("FullCore3 presentation retained"),
                );
            return;
        }
        let target_rect = presentation
            .map(|p| TargetRect::new(p.target[0], p.target[1], p.target[2], p.target[3]))
            .unwrap_or(target_rect);
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
        let (source_uv_rect, sample_y_flip) =
            if crate::meta_input_diagnostic::enabled() || prepared.stats.recursive_feedback_ready {
                ([0.0, 0.0, 1.0, 1.0], 0.0)
            } else {
                (
                    [
                        projection_metadata.source_uv_rect.x,
                        projection_metadata.source_uv_rect.y,
                        projection_metadata.source_uv_rect.width,
                        projection_metadata.source_uv_rect.height,
                    ],
                    projection_metadata.source_sample_y_flip,
                )
            };
        let source_uv_rect = presentation.map_or(source_uv_rect, |p| p.source_uv_rect);
        let push = DisplayCompositePush {
            target_rect: [
                target_rect.x,
                target_rect.y,
                target_rect.width,
                target_rect.height,
            ],
            source_uv_rect,
            params0: [
                sample_y_flip,
                DISPLAY_COMPOSITE_FINAL_PLANE_OPACITY,
                if crate::meta_input_diagnostic::enabled() {
                    prepared.meta_effect_mode
                } else if eye_index == 0 {
                    DISPLAY_COMPOSITE_FINAL_BORDER_OPACITY_LEFT
                } else {
                    DISPLAY_COMPOSITE_FINAL_BORDER_OPACITY_RIGHT
                },
                eye_index as f32,
            ],
        };
        push_display_constants(device, cmd, prepared.pipeline_layout, push, presentation);
        device.cmd_draw(cmd, 3, 1, 0, 0);
    }

    pub(crate) unsafe fn record_presentation_witnesses(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        extent: vk::Extent2D,
        projection_metadata: &DisplayCompositeProjectionMetadata,
        prepared: &PreparedDisplayCompositeFeedback,
        presentation: &crate::presentation_diagnostic::Frame,
    ) -> Result<(), String> {
        if !crate::presentation_diagnostic::visible() {
            return Ok(());
        }
        let Some(completion) = prepared.meta_completion else {
            return Ok(());
        };
        if self.presentation_sequences.len() >= 4
            || self.presentation_sequences.contains(&completion.sequence)
        {
            return Ok(());
        }
        let Some(timing) = crate::meta_input_diagnostic::witness_timing(
            completion.generation,
            completion.sequence,
        ) else {
            return Ok(());
        };
        if extent.width == 0 || extent.height == 0 || extent.width > 4096 || extent.height > 4096 {
            return Err("Presentation witness extent outside bound".into());
        }
        for eye in 0..2 {
            // Do not destroy resources on resize while another frame slot may own them.
            if self.presentation_readbacks[eye]
                .as_ref()
                .is_some_and(|r| r.extent != extent)
            {
                return Err("Presentation extent changed; restart immutable diagnostic".into());
            }
            if self.presentation_readbacks[eye].is_none() {
                self.presentation_readbacks[eye] = Some(DisplayCompositeReadbackResources::new(
                    device,
                    &self.memory_properties,
                    self.render_pass,
                    self.feedback_format,
                    extent,
                )?);
            }
            self.presentation_readbacks[eye]
                .as_mut()
                .unwrap()
                .ensure_frame_slot(
                    device,
                    &self.memory_properties,
                    self.render_pass,
                    self.feedback_format,
                    frame_slot,
                )?;
        }
        // Allocate all fallible paired resources before recording any witness pass.
        let raw_image = if completion.mode == 2 {
            let image = self
                .full3
                .as_ref()
                .and_then(|graph| graph.raw_pack_image(frame_slot))
                .ok_or("FullCore3 paired witness missing original pack atlas")?;
            let raw_extent = vk::Extent2D {
                width: 1280,
                height: 1280,
            };
            for eye in 0..2 {
                // This reusable readback owner also creates an unused attachment.
                // Match that attachment to our render pass; the byte buffer is
                // formatless and receives RGBA8 bytes directly from atlas A.
                if self.full3_raw_readbacks[eye].is_none() {
                    self.full3_raw_readbacks[eye] = Some(DisplayCompositeReadbackResources::new(
                        device,
                        &self.memory_properties,
                        self.render_pass,
                        self.feedback_format,
                        raw_extent,
                    )?);
                }
                self.full3_raw_readbacks[eye]
                    .as_mut()
                    .unwrap()
                    .ensure_frame_slot(
                        device,
                        &self.memory_properties,
                        self.render_pass,
                        self.feedback_format,
                        frame_slot,
                    )?;
            }
            Some(image)
        } else {
            None
        };
        for eye in 0..2 {
            let target = self.presentation_readbacks[eye].as_ref().unwrap().targets[frame_slot]
                .as_ref()
                .unwrap();
            begin_recursive_feedback_pass(
                device,
                cmd,
                self.render_pass,
                target.image.framebuffer,
                extent,
                [0., 0., 0., 0.],
            );
            self.record_feedback_eye(
                device,
                cmd,
                extent,
                eye,
                projection_metadata,
                TargetRect::UNIT,
                prepared,
                Some(&presentation.eyes[eye]),
            );
            device.cmd_end_render_pass(cmd);
            target.record_copy_to_buffer(device, cmd, extent);
        }
        if let Some(image) = raw_image {
            for eye in 0..2 {
                let target = self.full3_raw_readbacks[eye].as_ref().unwrap().targets[frame_slot]
                    .as_ref()
                    .unwrap();
                target.record_copy_region_from_image_to_buffer(
                    device,
                    cmd,
                    image,
                    vk::Extent2D {
                        width: 1280,
                        height: 1280,
                    },
                    vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL,
                    vk::Offset3D {
                        x: (eye * 1536) as i32,
                        y: 0,
                        z: 0,
                    },
                );
            }
        }
        while self.presentation_pending.len() <= frame_slot {
            self.presentation_pending.push(None);
        }
        let mut metadata = presentation.metadata.clone();
        if completion.mode == 2 {
            metadata["paired_same_aimage"] = true.into();
            metadata["paired_same_gpu_submission"] = true.into();
            metadata["paired_raw_source_stage"] = "raw-pack-atlas-pass0".into();
            metadata["paired_raw_eye_size"] = serde_json::json!([1280, 1280]);
            metadata["paired_raw_files"] = serde_json::json!([
                format!(
                    "meta-input-witness/{}/full-core3-input-eye-0-seq-{}.rgba",
                    completion.generation, completion.sequence
                ),
                format!(
                    "meta-input-witness/{}/full-core3-input-eye-1-seq-{}.rgba",
                    completion.generation, completion.sequence
                )
            ]);
        }
        self.presentation_pending[frame_slot] = Some(PresentationWitnessPending {
            completion,
            timing,
            metadata,
        });
        self.presentation_sequences.push(completion.sequence);
        crate::marker(
            "presentation-witness",
            format!(
                "status=recorded generation={} sequence={} extent={}x{} renderFrame={} eyes=2 sameSamplerAndMapping=true hmdPixelProof=false",
                completion.generation,
                completion.sequence,
                extent.width,
                extent.height,
                completion.render_frame
            ),
        );
        Ok(())
    }

    unsafe fn record_recursive_feedback_texture(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        frame: &DisplayCompositeFrame,
        projection_metadata: &DisplayCompositeProjectionMetadata,
        format_key: AhbVulkanFormatKey,
        format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
        current_image_view: vk::ImageView,
    ) -> Result<DisplayCompositeRecursivePreparedTarget, String> {
        let extent = recursive_feedback_extent(frame);
        if self
            .recursive
            .as_ref()
            .map(|resources| resources.format_key != format_key || resources.extent != extent)
            .unwrap_or(true)
        {
            if let Some(recursive) = self.recursive.take() {
                recursive.destroy(device);
            }
            self.recursive = Some(DisplayCompositeRecursiveResources::new(
                device,
                &self.memory_properties,
                self.render_pass,
                self.feedback_format,
                format_key,
                format_props,
                extent,
            )?);
        }

        let resources = self.recursive.as_mut().ok_or_else(|| {
            "display-composite recursive resources were not initialized".to_string()
        })?;
        if !resources.seed_initialized {
            resources.record_seed_clear(device, cmd);
        }

        let write_index = frame_slot % resources.feedback_textures.len();
        let previous_index = if DISPLAY_COMPOSITE_RECURSIVE_PREVIOUS_ALPHA > 0.001 {
            resources
                .previous_feedback_texture_index
                .filter(|index| *index != write_index && *index < resources.feedback_textures.len())
        } else {
            None
        };
        let read_image_view = previous_index
            .map(|index| resources.feedback_textures[index].image.view)
            .unwrap_or(resources.seed_texture.view);
        let previous_alpha = if previous_index.is_some() {
            DISPLAY_COMPOSITE_RECURSIVE_PREVIOUS_ALPHA
        } else {
            0.0
        };
        let read_index = previous_index.map(|index| index as i32).unwrap_or(-1);
        resources.write_recursive_descriptor(
            device,
            write_index,
            current_image_view,
            read_image_view,
        );
        resources.record_recursive_pass(
            device,
            cmd,
            write_index,
            projection_metadata,
            previous_alpha,
            frame.frame_index,
        );
        resources.previous_feedback_texture_index = Some(write_index);

        Ok(DisplayCompositeRecursivePreparedTarget {
            descriptor_set: resources.feedback_textures[write_index].final_descriptor_set,
            pipeline_layout: resources.final_pipeline_layout,
            pipeline: resources.final_pipeline,
            image: resources.feedback_textures[write_index].image.image,
            extent,
            write_index: write_index as u32,
            read_index,
            seeded: resources.seed_initialized,
        })
    }

    #[allow(clippy::too_many_arguments)]
    unsafe fn record_gpu_readback_export(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        render_frame_count: u64,
        xr_ready_marker_active: bool,
        frame: &DisplayCompositeFrame,
        projection_metadata: &DisplayCompositeProjectionMetadata,
        pipeline_layout: vk::PipelineLayout,
        pipeline: vk::Pipeline,
        descriptor_set: vk::DescriptorSet,
        meta_witness: Option<(u32, u64, u64)>,
        meta_witness_timing: Option<crate::meta_input_diagnostic_state::WitnessTiming>,
    ) -> Result<(), String> {
        let extent = gpu_readback_extent(frame);
        if self
            .readback
            .as_ref()
            .map(|readback| readback.extent != extent)
            .unwrap_or(true)
        {
            if let Some(readback) = self.readback.take() {
                readback.destroy(device);
            }
            self.pending_readbacks.clear();
            self.readback = Some(DisplayCompositeReadbackResources::new(
                device,
                &self.memory_properties,
                self.render_pass,
                self.feedback_format,
                extent,
            )?);
        }

        let readback = self.readback.as_mut().ok_or_else(|| {
            "display-composite readback resources were not initialized".to_string()
        })?;
        let target = readback.ensure_frame_slot(
            device,
            &self.memory_properties,
            self.render_pass,
            self.feedback_format,
            frame_slot,
        )?;
        begin_recursive_feedback_pass(
            device,
            cmd,
            self.render_pass,
            target.image.framebuffer,
            extent,
            [0.0, 0.0, 0.0, 1.0],
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
            offset: vk::Offset2D::default(),
            extent,
        }];
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            pipeline_layout,
            0,
            &[descriptor_set],
            &[],
        );
        let push = DisplayCompositePush {
            target_rect: [0.0, 0.0, 1.0, 1.0],
            source_uv_rect: if meta_witness.is_some() {
                [0.0, 0.0, 1.0, 1.0]
            } else {
                [
                    projection_metadata.source_uv_rect.x,
                    projection_metadata.source_uv_rect.y,
                    projection_metadata.source_uv_rect.width,
                    projection_metadata.source_uv_rect.height,
                ]
            },
            params0: if let Some((mode, _, _)) = meta_witness {
                [0.0, 1.0, mode as f32, 0.0]
            } else {
                [projection_metadata.source_sample_y_flip, 1.0, 0.0, 0.0]
            },
        };
        push_display_constants(device, cmd, pipeline_layout, push, None);
        device.cmd_draw(cmd, 3, 1, 0, 0);
        device.cmd_end_render_pass(cmd);
        target.record_copy_to_buffer(device, cmd, extent);

        while self.pending_readbacks.len() <= frame_slot {
            self.pending_readbacks.push(None);
        }
        self.pending_readbacks[frame_slot] = Some(DisplayCompositePendingGpuReadback {
            meta_witness,
            meta_witness_timing,
            frame_index: frame.frame_index,
            import_sequence: frame.import_sequence,
            timestamp_ns: frame.timestamp_ns,
            hardware_buffer_id: frame.descriptor.hardware_buffer_id,
            render_frame_count,
            xr_ready_marker_active,
            width: extent.width,
            height: extent.height,
        });
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=recorded stage=gpu-sampled-frame frameSlot={} width={} height={} frameIndex={} importSequence={} renderFrame={} xrReadyMarkerActive={} renderedIntoView=false stableFrameGate=true",
                frame_slot,
                extent.width,
                extent.height,
                frame.frame_index,
                frame.import_sequence,
                render_frame_count,
                xr_ready_marker_active
            ),
        );
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    unsafe fn record_feedback_level_exports(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        level_index: u32,
        render_frame_count: u64,
        xr_ready_marker_active: bool,
        frame: &DisplayCompositeFrame,
        projection_metadata: &DisplayCompositeProjectionMetadata,
        source_pipeline_layout: vk::PipelineLayout,
        source_pipeline: vk::Pipeline,
        source_descriptor_set: vk::DescriptorSet,
        recursive_target: &DisplayCompositeRecursivePreparedTarget,
    ) -> Result<(), String> {
        self.record_source_level_readback_export(
            device,
            cmd,
            frame_slot,
            level_index,
            render_frame_count,
            xr_ready_marker_active,
            frame,
            projection_metadata,
            source_pipeline_layout,
            source_pipeline,
            source_descriptor_set,
            recursive_target.seeded,
        )?;
        self.record_feedback_screen_level_readback_export(
            device,
            cmd,
            frame_slot,
            level_index,
            render_frame_count,
            xr_ready_marker_active,
            frame,
            recursive_target,
        )?;
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    unsafe fn record_source_level_readback_export(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        level_index: u32,
        render_frame_count: u64,
        xr_ready_marker_active: bool,
        frame: &DisplayCompositeFrame,
        projection_metadata: &DisplayCompositeProjectionMetadata,
        pipeline_layout: vk::PipelineLayout,
        pipeline: vk::Pipeline,
        descriptor_set: vk::DescriptorSet,
        recursive_feedback_seeded: bool,
    ) -> Result<(), String> {
        let extent = gpu_readback_extent(frame);
        if self
            .source_level_readback
            .as_ref()
            .map(|readback| readback.extent != extent)
            .unwrap_or(true)
        {
            if let Some(readback) = self.source_level_readback.take() {
                readback.destroy(device);
            }
            self.pending_source_level_readbacks.clear();
            self.source_level_readback = Some(DisplayCompositeReadbackResources::new(
                device,
                &self.memory_properties,
                self.render_pass,
                self.feedback_format,
                extent,
            )?);
        }

        let readback = self.source_level_readback.as_mut().ok_or_else(|| {
            "display-composite source-level readback resources were not initialized".to_string()
        })?;
        let target = readback.ensure_frame_slot(
            device,
            &self.memory_properties,
            self.render_pass,
            self.feedback_format,
            frame_slot,
        )?;
        begin_recursive_feedback_pass(
            device,
            cmd,
            self.render_pass,
            target.image.framebuffer,
            extent,
            [0.0, 0.0, 0.0, 1.0],
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
            offset: vk::Offset2D::default(),
            extent,
        }];
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            pipeline_layout,
            0,
            &[descriptor_set],
            &[],
        );
        let push = DisplayCompositePush {
            target_rect: [0.0, 0.0, 1.0, 1.0],
            source_uv_rect: [
                projection_metadata.source_uv_rect.x,
                projection_metadata.source_uv_rect.y,
                projection_metadata.source_uv_rect.width,
                projection_metadata.source_uv_rect.height,
            ],
            params0: [projection_metadata.source_sample_y_flip, 1.0, 0.0, 0.0],
        };
        push_display_constants(device, cmd, pipeline_layout, push, None);
        device.cmd_draw(cmd, 3, 1, 0, 0);
        device.cmd_end_render_pass(cmd);
        target.record_copy_to_buffer(device, cmd, extent);

        while self.pending_source_level_readbacks.len() <= frame_slot {
            self.pending_source_level_readbacks.push(None);
        }
        self.pending_source_level_readbacks[frame_slot] =
            Some(DisplayCompositePendingLevelReadback {
                level_index,
                frame_index: frame.frame_index,
                import_sequence: frame.import_sequence,
                timestamp_ns: frame.timestamp_ns,
                hardware_buffer_id: frame.descriptor.hardware_buffer_id,
                render_frame_count,
                xr_ready_marker_active,
                recursive_feedback_seeded,
                recursive_feedback_write_index: 0,
                recursive_feedback_read_index: -1,
                width: extent.width,
                height: extent.height,
            });
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=recorded stage=media-projection-gpu-level levelIndex={} frameSlot={} width={} height={} frameIndex={} importSequence={} renderFrame={} xrReadyMarkerActive={} renderedIntoView=false stableFrameGate=true",
                level_index,
                frame_slot,
                extent.width,
                extent.height,
                frame.frame_index,
                frame.import_sequence,
                render_frame_count,
                xr_ready_marker_active
            ),
        );
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    unsafe fn record_feedback_screen_level_readback_export(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        frame_slot: usize,
        level_index: u32,
        render_frame_count: u64,
        xr_ready_marker_active: bool,
        frame: &DisplayCompositeFrame,
        recursive_target: &DisplayCompositeRecursivePreparedTarget,
    ) -> Result<(), String> {
        let extent = recursive_target.extent;
        if self
            .feedback_level_readback
            .as_ref()
            .map(|readback| readback.extent != extent)
            .unwrap_or(true)
        {
            if let Some(readback) = self.feedback_level_readback.take() {
                readback.destroy(device);
            }
            self.pending_feedback_level_readbacks.clear();
            self.feedback_level_readback = Some(DisplayCompositeReadbackResources::new(
                device,
                &self.memory_properties,
                self.render_pass,
                self.feedback_format,
                extent,
            )?);
        }

        let readback = self.feedback_level_readback.as_mut().ok_or_else(|| {
            "display-composite feedback-level readback resources were not initialized".to_string()
        })?;
        let target = readback.ensure_frame_slot(
            device,
            &self.memory_properties,
            self.render_pass,
            self.feedback_format,
            frame_slot,
        )?;
        target.record_copy_from_image_to_buffer(
            device,
            cmd,
            recursive_target.image,
            extent,
            vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL,
        );

        while self.pending_feedback_level_readbacks.len() <= frame_slot {
            self.pending_feedback_level_readbacks.push(None);
        }
        self.pending_feedback_level_readbacks[frame_slot] =
            Some(DisplayCompositePendingLevelReadback {
                level_index,
                frame_index: frame.frame_index,
                import_sequence: frame.import_sequence,
                timestamp_ns: frame.timestamp_ns,
                hardware_buffer_id: frame.descriptor.hardware_buffer_id,
                render_frame_count,
                xr_ready_marker_active,
                recursive_feedback_seeded: recursive_target.seeded,
                recursive_feedback_write_index: recursive_target.write_index,
                recursive_feedback_read_index: recursive_target.read_index,
                width: extent.width,
                height: extent.height,
            });
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=recorded stage=feedback-screen-level levelIndex={} frameSlot={} width={} height={} frameIndex={} importSequence={} renderFrame={} xrReadyMarkerActive={} recursiveFeedbackSeeded={} recursiveFeedbackWriteIndex={} recursiveFeedbackReadIndex={} renderedIntoView=true stableFrameGate=true",
                level_index,
                frame_slot,
                extent.width,
                extent.height,
                frame.frame_index,
                frame.import_sequence,
                render_frame_count,
                xr_ready_marker_active,
                recursive_target.seeded,
                recursive_target.write_index,
                recursive_target.read_index
            ),
        );
        Ok(())
    }

    unsafe fn evict_imports_to_limit(
        &mut self,
        device: &ash::Device,
        protected_hardware_buffer_id: u64,
    ) -> DisplayCompositeCacheEvictionStats {
        let mut stats = DisplayCompositeCacheEvictionStats::default();
        while self.imports.len() >= DISPLAY_COMPOSITE_IMPORT_CACHE_LIMIT {
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

/// Android producers are foreign unless matching device/driver UUIDs are
/// established. This diagnostic makes no such assumption. Each submitted
/// sample acquires and releases ownership, including allocation-cache hits.
unsafe fn meta_foreign_barrier(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    image: vk::Image,
    graphics: u32,
    acquire: bool,
) {
    let range = vk::ImageSubresourceRange::default()
        .aspect_mask(vk::ImageAspectFlags::COLOR)
        .level_count(1)
        .layer_count(1);
    let barrier = vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(range)
        .src_queue_family_index(if acquire {
            vk::QUEUE_FAMILY_FOREIGN_EXT
        } else {
            graphics
        })
        .dst_queue_family_index(if acquire {
            graphics
        } else {
            vk::QUEUE_FAMILY_FOREIGN_EXT
        })
        .old_layout(if acquire {
            vk::ImageLayout::GENERAL
        } else {
            vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL
        })
        .new_layout(if acquire {
            vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL
        } else {
            vk::ImageLayout::GENERAL
        })
        .src_access_mask(if acquire {
            vk::AccessFlags::empty()
        } else {
            vk::AccessFlags::SHADER_READ
        })
        .dst_access_mask(if acquire {
            vk::AccessFlags::SHADER_READ
        } else {
            vk::AccessFlags::empty()
        });
    device.cmd_pipeline_barrier(
        cmd,
        if acquire {
            vk::PipelineStageFlags::TOP_OF_PIPE
        } else {
            vk::PipelineStageFlags::FRAGMENT_SHADER
        },
        if acquire {
            vk::PipelineStageFlags::FRAGMENT_SHADER
        } else {
            vk::PipelineStageFlags::BOTTOM_OF_PIPE
        },
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &[barrier],
    );
}

#[derive(Clone, Copy, Debug, Default)]
struct DisplayCompositeCacheEvictionStats {
    attempts: usize,
    applied: usize,
    in_flight_skips: usize,
    protected_skips: usize,
    deferred: usize,
}

impl DisplayCompositeCacheEvictionStats {
    fn should_log(self) -> bool {
        self.attempts > 0
            || self.applied > 0
            || self.in_flight_skips > 0
            || self.protected_skips > 0
            || self.deferred > 0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct DisplayCompositeImportKey {
    source_generation: u64,
    layers: u32,
    usage: u64,
    stride: u32,
    buffer_id: u64,
    width: u32,
    height: u32,
    native_format: u32,
}

impl DisplayCompositeImportKey {
    fn from_frame(frame: &DisplayCompositeFrame) -> Self {
        Self {
            source_generation: frame.source_generation,
            layers: frame.descriptor.layers,
            usage: frame.descriptor.usage,
            stride: frame.descriptor.stride,
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

struct DisplayCompositeResources {
    format_key: AhbVulkanFormatKey,
    sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    sampler: vk::Sampler,
    sampler_filter: vk::Filter,
    descriptor_uses_immutable_sampler: bool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
}

impl DisplayCompositeResources {
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

struct DisplayCompositeImport {
    key: DisplayCompositeImportKey,
    sampled_image: AhbVulkanSampledImage,
    descriptor_set: vk::DescriptorSet,
    descriptor_pool: vk::DescriptorPool,
    needs_layout_transition: bool,
}

impl DisplayCompositeImport {
    unsafe fn destroy(self, device: &ash::Device) {
        let _ = device.free_descriptor_sets(self.descriptor_pool, &[self.descriptor_set]);
        self.sampled_image.destroy(device);
    }
}

#[derive(Clone, Copy, Debug)]
struct DisplayCompositePendingGpuReadback {
    meta_witness: Option<(u32, u64, u64)>,
    meta_witness_timing: Option<crate::meta_input_diagnostic_state::WitnessTiming>,
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    width: u32,
    height: u32,
}

#[derive(Clone, Copy, Debug)]
struct DisplayCompositePendingLevelReadback {
    level_index: u32,
    frame_index: u64,
    import_sequence: u64,
    timestamp_ns: i64,
    hardware_buffer_id: u64,
    render_frame_count: u64,
    xr_ready_marker_active: bool,
    recursive_feedback_seeded: bool,
    recursive_feedback_write_index: u32,
    recursive_feedback_read_index: i32,
    width: u32,
    height: u32,
}

struct DisplayCompositeReadbackResources {
    extent: vk::Extent2D,
    targets: Vec<Option<DisplayCompositeReadbackTarget>>,
}

impl DisplayCompositeReadbackResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        format: vk::Format,
        extent: vk::Extent2D,
    ) -> Result<Self, String> {
        let mut resources = Self {
            extent,
            targets: Vec::new(),
        };
        resources.ensure_frame_slot(device, memory_properties, render_pass, format, 0)?;
        crate::marker(
            "display-composite-capture-export",
            format!(
                "status=created stage=gpu-readback-resources width={} height={} format={:?} renderedIntoView=false",
                extent.width, extent.height, format
            ),
        );
        Ok(resources)
    }

    unsafe fn ensure_frame_slot(
        &mut self,
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        format: vk::Format,
        frame_slot: usize,
    ) -> Result<&DisplayCompositeReadbackTarget, String> {
        while self.targets.len() <= frame_slot {
            self.targets.push(None);
        }
        if self.targets[frame_slot].is_none() {
            self.targets[frame_slot] = Some(DisplayCompositeReadbackTarget::new(
                device,
                memory_properties,
                render_pass,
                format,
                self.extent,
            )?);
        }
        Ok(self.targets[frame_slot]
            .as_ref()
            .expect("display-composite readback target just initialized"))
    }

    unsafe fn copy_completed_frame_bytes(
        &self,
        device: &ash::Device,
        frame_slot: usize,
    ) -> Result<Vec<u8>, String> {
        let Some(Some(target)) = self.targets.get(frame_slot) else {
            return Err(format!("missing-readback-target frameSlot={frame_slot}"));
        };
        target.copy_completed_frame_bytes(device)
    }

    unsafe fn destroy(self, device: &ash::Device) {
        for target in self.targets.into_iter().flatten() {
            target.destroy(device);
        }
    }
}

struct DisplayCompositeReadbackTarget {
    image: DisplayCompositeFeedbackImage,
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    size: vk::DeviceSize,
}

impl DisplayCompositeReadbackTarget {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        format: vk::Format,
        extent: vk::Extent2D,
    ) -> Result<Self, String> {
        let image = DisplayCompositeFeedbackImage::new(
            device,
            memory_properties,
            format,
            render_pass,
            extent,
            "display-composite capture gpu readback",
        )?;
        let size = u64::from(extent.width)
            .saturating_mul(u64::from(extent.height))
            .saturating_mul(4);
        let buffer = match device.create_buffer(
            &vk::BufferCreateInfo::default()
                .size(size)
                .usage(vk::BufferUsageFlags::TRANSFER_DST)
                .sharing_mode(vk::SharingMode::EXCLUSIVE),
            None,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                image.destroy(device);
                return Err(format!("create display-composite readback buffer: {error}"));
            }
        };
        let requirements = device.get_buffer_memory_requirements(buffer);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
        ) {
            Ok(index) => index,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                image.destroy(device);
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
                device.destroy_buffer(buffer, None);
                image.destroy(device);
                return Err(format!(
                    "allocate display-composite readback memory: {error}"
                ));
            }
        };
        if let Err(error) = device.bind_buffer_memory(buffer, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            image.destroy(device);
            return Err(format!("bind display-composite readback buffer: {error}"));
        }
        Ok(Self {
            image,
            buffer,
            memory,
            size,
        })
    }

    unsafe fn record_copy_to_buffer(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
    ) {
        let barrier = [vk::ImageMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
            .dst_access_mask(vk::AccessFlags::TRANSFER_READ)
            .old_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)
            .new_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
            .image(self.image.image)
            .subresource_range(color_subresource_range())];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &barrier,
        );
        let copy = [vk::BufferImageCopy::default()
            .buffer_offset(0)
            .buffer_row_length(extent.width)
            .buffer_image_height(extent.height)
            .image_subresource(
                vk::ImageSubresourceLayers::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .mip_level(0)
                    .base_array_layer(0)
                    .layer_count(1),
            )
            .image_offset(vk::Offset3D { x: 0, y: 0, z: 0 })
            .image_extent(vk::Extent3D {
                width: extent.width,
                height: extent.height,
                depth: 1,
            })];
        device.cmd_copy_image_to_buffer(
            cmd,
            self.image.image,
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
            self.buffer,
            &copy,
        );
    }

    unsafe fn record_copy_from_image_to_buffer(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        source_image: vk::Image,
        extent: vk::Extent2D,
        old_layout: vk::ImageLayout,
    ) {
        self.record_copy_region_from_image_to_buffer(
            device,
            cmd,
            source_image,
            extent,
            old_layout,
            vk::Offset3D { x: 0, y: 0, z: 0 },
        );
    }

    unsafe fn record_copy_region_from_image_to_buffer(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        source_image: vk::Image,
        extent: vk::Extent2D,
        old_layout: vk::ImageLayout,
        source_offset: vk::Offset3D,
    ) {
        let to_transfer = [vk::ImageMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE | vk::AccessFlags::SHADER_READ)
            .dst_access_mask(vk::AccessFlags::TRANSFER_READ)
            .old_layout(old_layout)
            .new_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .image(source_image)
            .subresource_range(color_subresource_range())];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT
                | vk::PipelineStageFlags::FRAGMENT_SHADER,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &to_transfer,
        );
        let copy = [vk::BufferImageCopy::default()
            .buffer_offset(0)
            .buffer_row_length(extent.width)
            .buffer_image_height(extent.height)
            .image_subresource(
                vk::ImageSubresourceLayers::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .mip_level(0)
                    .base_array_layer(0)
                    .layer_count(1),
            )
            .image_offset(source_offset)
            .image_extent(vk::Extent3D {
                width: extent.width,
                height: extent.height,
                depth: 1,
            })];
        device.cmd_copy_image_to_buffer(
            cmd,
            source_image,
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
            self.buffer,
            &copy,
        );
        let to_shader = [vk::ImageMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::TRANSFER_READ)
            .dst_access_mask(vk::AccessFlags::SHADER_READ)
            .old_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
            .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .image(source_image)
            .subresource_range(color_subresource_range())];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::FRAGMENT_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &to_shader,
        );
    }

    unsafe fn copy_completed_frame_bytes(&self, device: &ash::Device) -> Result<Vec<u8>, String> {
        let mapped = device
            .map_memory(self.memory, 0, self.size, vk::MemoryMapFlags::empty())
            .map_err(|error| format!("map display-composite readback memory: {error}"))?;
        let mut bytes = vec![0_u8; self.size as usize];
        std::ptr::copy_nonoverlapping(mapped.cast::<u8>(), bytes.as_mut_ptr(), bytes.len());
        device.unmap_memory(self.memory);
        Ok(bytes)
    }

    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_buffer(self.buffer, None);
        device.free_memory(self.memory, None);
        self.image.destroy(device);
    }
}

struct DisplayCompositeRecursivePreparedTarget {
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    image: vk::Image,
    extent: vk::Extent2D,
    write_index: u32,
    read_index: i32,
    seeded: bool,
}

struct DisplayCompositeRecursiveResources {
    format_key: AhbVulkanFormatKey,
    extent: vk::Extent2D,
    render_pass: vk::RenderPass,
    current_sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    current_sampler: vk::Sampler,
    feedback_sampler: vk::Sampler,
    recursive_descriptor_set_layout: vk::DescriptorSetLayout,
    final_descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    recursive_pipeline_layout: vk::PipelineLayout,
    recursive_pipeline: vk::Pipeline,
    final_pipeline_layout: vk::PipelineLayout,
    final_pipeline: vk::Pipeline,
    seed_texture: DisplayCompositeFeedbackImage,
    feedback_textures: Vec<DisplayCompositeFeedbackTexture>,
    previous_feedback_texture_index: Option<usize>,
    seed_initialized: bool,
}

impl DisplayCompositeRecursiveResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        final_render_pass: vk::RenderPass,
        feedback_format: vk::Format,
        format_key: AhbVulkanFormatKey,
        format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
        extent: vk::Extent2D,
    ) -> Result<Self, String> {
        let render_pass = create_display_composite_recursive_render_pass(device, feedback_format)?;
        let current_sampler_ycbcr = create_ahb_sampler_ycbcr_conversion(
            device,
            format_key,
            format_props,
            "display-composite-recursive",
        )?;
        let current_sampler_ycbcr_handle = current_sampler_ycbcr
            .as_ref()
            .map(|conversion| conversion.handle);
        let current_sampler_filter = current_sampler_ycbcr
            .as_ref()
            .map(|conversion| conversion.metadata.sampler_filter)
            .unwrap_or_else(|| {
                if format_props
                    .format_features
                    .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR)
                {
                    vk::Filter::LINEAR
                } else {
                    vk::Filter::NEAREST
                }
            });
        let current_sampler = match create_sampler(
            device,
            current_sampler_filter,
            current_sampler_ycbcr_handle,
            "display-composite recursive current sampler",
        ) {
            Ok(sampler) => sampler,
            Err(error) => {
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let feedback_sampler = match create_sampler(
            device,
            vk::Filter::LINEAR,
            None,
            "display-composite recursive feedback sampler",
        ) {
            Ok(sampler) => sampler,
            Err(error) => {
                device.destroy_sampler(current_sampler, None);
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let current_descriptor_uses_immutable_sampler = current_sampler_ycbcr_handle.is_some();
        let recursive_descriptor_set_layout = match create_recursive_descriptor_set_layout(
            device,
            current_sampler,
            current_descriptor_uses_immutable_sampler,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_sampler(feedback_sampler, None);
                device.destroy_sampler(current_sampler, None);
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let final_descriptor_set_layout = match create_single_sampler_descriptor_set_layout(
            device,
            "display-composite recursive final descriptor layout",
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_descriptor_set_layout(recursive_descriptor_set_layout, None);
                device.destroy_sampler(feedback_sampler, None);
                device.destroy_sampler(current_sampler, None);
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let descriptor_pool = match create_recursive_descriptor_pool(device) {
            Ok(pool) => pool,
            Err(error) => {
                device.destroy_descriptor_set_layout(final_descriptor_set_layout, None);
                device.destroy_descriptor_set_layout(recursive_descriptor_set_layout, None);
                device.destroy_sampler(feedback_sampler, None);
                device.destroy_sampler(current_sampler, None);
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let recursive_pipeline_layout = match create_pipeline_layout(
            device,
            recursive_descriptor_set_layout,
            std::mem::size_of::<DisplayCompositeRecursivePush>() as u32,
            "display-composite recursive pipeline layout",
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(final_descriptor_set_layout, None);
                device.destroy_descriptor_set_layout(recursive_descriptor_set_layout, None);
                device.destroy_sampler(feedback_sampler, None);
                device.destroy_sampler(current_sampler, None);
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let recursive_pipeline = match create_display_composite_pipeline(
            device,
            render_pass,
            recursive_pipeline_layout,
            DisplayCompositeFragmentShader::RecursiveFeedback,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(recursive_pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(final_descriptor_set_layout, None);
                device.destroy_descriptor_set_layout(recursive_descriptor_set_layout, None);
                device.destroy_sampler(feedback_sampler, None);
                device.destroy_sampler(current_sampler, None);
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let final_pipeline_layout = match create_pipeline_layout(
            device,
            final_descriptor_set_layout,
            display_push_size(),
            "display-composite recursive final pipeline layout",
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_pipeline(recursive_pipeline, None);
                device.destroy_pipeline_layout(recursive_pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(final_descriptor_set_layout, None);
                device.destroy_descriptor_set_layout(recursive_descriptor_set_layout, None);
                device.destroy_sampler(feedback_sampler, None);
                device.destroy_sampler(current_sampler, None);
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let final_pipeline = match create_display_composite_pipeline(
            device,
            final_render_pass,
            final_pipeline_layout,
            DisplayCompositeFragmentShader::DirectFeedback,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(final_pipeline_layout, None);
                device.destroy_pipeline(recursive_pipeline, None);
                device.destroy_pipeline_layout(recursive_pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(final_descriptor_set_layout, None);
                device.destroy_descriptor_set_layout(recursive_descriptor_set_layout, None);
                device.destroy_sampler(feedback_sampler, None);
                device.destroy_sampler(current_sampler, None);
                if let Some(conversion) = current_sampler_ycbcr_handle {
                    device.destroy_sampler_ycbcr_conversion(conversion, None);
                }
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };

        let seed_texture = match DisplayCompositeFeedbackImage::new(
            device,
            memory_properties,
            feedback_format,
            render_pass,
            extent,
            "display-composite recursive seed",
        ) {
            Ok(texture) => texture,
            Err(error) => {
                destroy_recursive_pipeline_state(
                    device,
                    final_pipeline,
                    final_pipeline_layout,
                    recursive_pipeline,
                    recursive_pipeline_layout,
                    descriptor_pool,
                    final_descriptor_set_layout,
                    recursive_descriptor_set_layout,
                    feedback_sampler,
                    current_sampler,
                    current_sampler_ycbcr_handle,
                    render_pass,
                );
                return Err(error);
            }
        };
        let mut feedback_textures: Vec<DisplayCompositeFeedbackTexture> =
            Vec::with_capacity(DISPLAY_COMPOSITE_RECURSIVE_TEXTURE_COUNT);
        for index in 0..DISPLAY_COMPOSITE_RECURSIVE_TEXTURE_COUNT {
            let image = match DisplayCompositeFeedbackImage::new(
                device,
                memory_properties,
                feedback_format,
                render_pass,
                extent,
                "display-composite recursive feedback",
            ) {
                Ok(image) => image,
                Err(error) => {
                    for texture in feedback_textures.drain(..) {
                        texture.destroy(device);
                    }
                    seed_texture.destroy(device);
                    destroy_recursive_pipeline_state(
                        device,
                        final_pipeline,
                        final_pipeline_layout,
                        recursive_pipeline,
                        recursive_pipeline_layout,
                        descriptor_pool,
                        final_descriptor_set_layout,
                        recursive_descriptor_set_layout,
                        feedback_sampler,
                        current_sampler,
                        current_sampler_ycbcr_handle,
                        render_pass,
                    );
                    return Err(error);
                }
            };
            let recursive_descriptor_set = match allocate_descriptor_set(
                device,
                descriptor_pool,
                recursive_descriptor_set_layout,
                "display-composite recursive descriptor set",
            ) {
                Ok(set) => set,
                Err(error) => {
                    image.destroy(device);
                    for texture in feedback_textures.drain(..) {
                        texture.destroy(device);
                    }
                    seed_texture.destroy(device);
                    destroy_recursive_pipeline_state(
                        device,
                        final_pipeline,
                        final_pipeline_layout,
                        recursive_pipeline,
                        recursive_pipeline_layout,
                        descriptor_pool,
                        final_descriptor_set_layout,
                        recursive_descriptor_set_layout,
                        feedback_sampler,
                        current_sampler,
                        current_sampler_ycbcr_handle,
                        render_pass,
                    );
                    return Err(error);
                }
            };
            let final_descriptor_set = match allocate_descriptor_set(
                device,
                descriptor_pool,
                final_descriptor_set_layout,
                "display-composite recursive final descriptor set",
            ) {
                Ok(set) => set,
                Err(error) => {
                    image.destroy(device);
                    for texture in feedback_textures.drain(..) {
                        texture.destroy(device);
                    }
                    seed_texture.destroy(device);
                    destroy_recursive_pipeline_state(
                        device,
                        final_pipeline,
                        final_pipeline_layout,
                        recursive_pipeline,
                        recursive_pipeline_layout,
                        descriptor_pool,
                        final_descriptor_set_layout,
                        recursive_descriptor_set_layout,
                        feedback_sampler,
                        current_sampler,
                        current_sampler_ycbcr_handle,
                        render_pass,
                    );
                    return Err(error);
                }
            };
            write_single_sampler_descriptor(
                device,
                final_descriptor_set,
                feedback_sampler,
                image.view,
            );
            let _ = index;
            feedback_textures.push(DisplayCompositeFeedbackTexture {
                image,
                recursive_descriptor_set,
                final_descriptor_set,
            });
        }

        crate::marker(
            "display-composite-recursive-feedback-resources",
            format!(
                "status=created stream=display_composite recursiveTextureCount={} recursiveWidth={} recursiveHeight={} recursiveMaxWidth={} feedbackFormat={:?} externalFormat={} vkFormat={:?} displayCompositeRecursiveFeedbackEnabled=true displayCompositeRecursiveFeedbackReady=false displayCompositeRecursiveFeedbackSource=media-projection-current-frame-clean displayCompositeRecursiveFeedbackPreviousBlend=false displayCompositeRecursiveFeedbackPreviousAlpha={:.3} displayCompositeRecursiveFeedbackBorderOpacity={:.3} displayCompositeRecursiveFeedbackBaseGain={:.3} displayCompositeFinalBorderOpacityLeft={:.3} displayCompositeFinalBorderOpacityRight={:.3} displayCompositeFinalPlaneOpacity={:.3} displayCompositeFinalAlphaMode=premultiplied-openxr-projection-layer displayCompositeMediaProjectionSource=android-mediaprojection displayCompositeCompositorRecaptureAssumed=false currentDescriptorUsesImmutableSampler={} nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
                DISPLAY_COMPOSITE_RECURSIVE_TEXTURE_COUNT,
                extent.width,
                extent.height,
                DISPLAY_COMPOSITE_RECURSIVE_MAX_WIDTH,
                feedback_format,
                format_key.external_format,
                format_key.format,
                DISPLAY_COMPOSITE_RECURSIVE_PREVIOUS_ALPHA,
                DISPLAY_COMPOSITE_RECURSIVE_BORDER_OPACITY,
                DISPLAY_COMPOSITE_RECURSIVE_BASE_GAIN,
                DISPLAY_COMPOSITE_FINAL_BORDER_OPACITY_LEFT,
                DISPLAY_COMPOSITE_FINAL_BORDER_OPACITY_RIGHT,
                DISPLAY_COMPOSITE_FINAL_PLANE_OPACITY,
                current_descriptor_uses_immutable_sampler,
            ),
        );

        Ok(Self {
            format_key,
            extent,
            render_pass,
            current_sampler_ycbcr_conversion: current_sampler_ycbcr_handle,
            current_sampler,
            feedback_sampler,
            recursive_descriptor_set_layout,
            final_descriptor_set_layout,
            descriptor_pool,
            recursive_pipeline_layout,
            recursive_pipeline,
            final_pipeline_layout,
            final_pipeline,
            seed_texture,
            feedback_textures,
            previous_feedback_texture_index: None,
            seed_initialized: false,
        })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        for texture in self.feedback_textures {
            texture.destroy(device);
        }
        self.seed_texture.destroy(device);
        device.destroy_pipeline(self.final_pipeline, None);
        device.destroy_pipeline_layout(self.final_pipeline_layout, None);
        device.destroy_pipeline(self.recursive_pipeline, None);
        device.destroy_pipeline_layout(self.recursive_pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.final_descriptor_set_layout, None);
        device.destroy_descriptor_set_layout(self.recursive_descriptor_set_layout, None);
        device.destroy_sampler(self.feedback_sampler, None);
        device.destroy_sampler(self.current_sampler, None);
        if let Some(conversion) = self.current_sampler_ycbcr_conversion {
            device.destroy_sampler_ycbcr_conversion(conversion, None);
        }
        device.destroy_render_pass(self.render_pass, None);
    }

    unsafe fn record_seed_clear(&mut self, device: &ash::Device, cmd: vk::CommandBuffer) {
        begin_recursive_feedback_pass(
            device,
            cmd,
            self.render_pass,
            self.seed_texture.framebuffer,
            self.extent,
            [0.0, 0.0, 0.0, 1.0],
        );
        device.cmd_end_render_pass(cmd);
        self.seed_initialized = true;
    }

    unsafe fn write_recursive_descriptor(
        &self,
        device: &ash::Device,
        write_index: usize,
        current_image_view: vk::ImageView,
        previous_image_view: vk::ImageView,
    ) {
        let set = self.feedback_textures[write_index].recursive_descriptor_set;
        let current = [vk::DescriptorImageInfo::default()
            .sampler(self.current_sampler)
            .image_view(current_image_view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
        let previous = [vk::DescriptorImageInfo::default()
            .sampler(self.feedback_sampler)
            .image_view(previous_image_view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
        let writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(set)
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .image_info(&current),
            vk::WriteDescriptorSet::default()
                .dst_set(set)
                .dst_binding(1)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .image_info(&previous),
        ];
        device.update_descriptor_sets(&writes, &[]);
    }

    unsafe fn record_recursive_pass(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        write_index: usize,
        projection_metadata: &DisplayCompositeProjectionMetadata,
        previous_alpha: f32,
        frame_index: u64,
    ) {
        let target = &self.feedback_textures[write_index];
        begin_recursive_feedback_pass(
            device,
            cmd,
            self.render_pass,
            target.image.framebuffer,
            self.extent,
            [0.015, 0.018, 0.020, 1.0],
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
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_bind_pipeline(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.recursive_pipeline,
        );
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.recursive_pipeline_layout,
            0,
            &[target.recursive_descriptor_set],
            &[],
        );
        let push = DisplayCompositeRecursivePush {
            source_uv_rect: [
                projection_metadata.source_uv_rect.x,
                projection_metadata.source_uv_rect.y,
                projection_metadata.source_uv_rect.width,
                projection_metadata.source_uv_rect.height,
            ],
            params0: [
                projection_metadata.source_sample_y_flip,
                previous_alpha,
                DISPLAY_COMPOSITE_RECURSIVE_INSET_SCALE,
                (frame_index % 240) as f32 / 240.0,
            ],
            params1: [
                DISPLAY_COMPOSITE_RECURSIVE_BORDER_OPACITY,
                DISPLAY_COMPOSITE_RECURSIVE_BASE_GAIN,
                0.94,
                0.0,
            ],
        };
        let push_bytes = std::slice::from_raw_parts(
            (&push as *const DisplayCompositeRecursivePush).cast::<u8>(),
            std::mem::size_of::<DisplayCompositeRecursivePush>(),
        );
        device.cmd_push_constants(
            cmd,
            self.recursive_pipeline_layout,
            vk::ShaderStageFlags::FRAGMENT,
            0,
            push_bytes,
        );
        device.cmd_draw(cmd, 3, 1, 0, 0);
        device.cmd_end_render_pass(cmd);
    }
}

struct DisplayCompositeFeedbackTexture {
    image: DisplayCompositeFeedbackImage,
    recursive_descriptor_set: vk::DescriptorSet,
    final_descriptor_set: vk::DescriptorSet,
}

impl DisplayCompositeFeedbackTexture {
    unsafe fn destroy(self, device: &ash::Device) {
        self.image.destroy(device);
    }
}

struct DisplayCompositeFeedbackImage {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    framebuffer: vk::Framebuffer,
}

impl DisplayCompositeFeedbackImage {
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
                    .usage(
                        vk::ImageUsageFlags::COLOR_ATTACHMENT
                            | vk::ImageUsageFlags::SAMPLED
                            | vk::ImageUsageFlags::TRANSFER_SRC,
                    )
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
                return Err(format!("allocate {label} memory: {error}"));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("bind {label} memory: {error}"));
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
                return Err(format!("create {label} view: {error}"));
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
#[derive(Clone, Copy)]
struct DisplayCompositePush {
    target_rect: [f32; 4],
    source_uv_rect: [f32; 4],
    params0: [f32; 4],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct PresentationPush {
    base: DisplayCompositePush,
    eye_tangents: [f32; 4],
    source_tangents: [f32; 4],
    eye_to_source_rotation: [f32; 4],
    presentation: [f32; 4],
    eye_origin: [f32; 4],
}
const _: () = assert!(std::mem::size_of::<PresentationPush>() == 128);
fn display_push_size() -> u32 {
    if crate::presentation_diagnostic::enabled() {
        128
    } else {
        std::mem::size_of::<DisplayCompositePush>() as u32
    }
}
unsafe fn push_display_constants(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    layout: vk::PipelineLayout,
    base: DisplayCompositePush,
    presentation: Option<&crate::presentation_diagnostic::Eye>,
) {
    if crate::presentation_diagnostic::enabled() {
        let p = presentation.copied().unwrap_or_default();
        let push = PresentationPush {
            base,
            eye_tangents: p.tangents,
            source_tangents: p.source,
            eye_to_source_rotation: p.rotation,
            presentation: p.parameters,
            eye_origin: p.origin,
        };
        let bytes = std::slice::from_raw_parts(
            (&push as *const PresentationPush).cast::<u8>(),
            std::mem::size_of::<PresentationPush>(),
        );
        device.cmd_push_constants(cmd, layout, vk::ShaderStageFlags::FRAGMENT, 0, bytes);
    } else {
        let bytes = std::slice::from_raw_parts(
            (&base as *const DisplayCompositePush).cast::<u8>(),
            std::mem::size_of::<DisplayCompositePush>(),
        );
        device.cmd_push_constants(cmd, layout, vk::ShaderStageFlags::FRAGMENT, 0, bytes);
    }
}
struct PresentationWitnessPending {
    completion: crate::meta_input_diagnostic::Completion,
    timing: crate::meta_input_diagnostic_state::WitnessTiming,
    metadata: serde_json::Value,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct DisplayCompositeRecursivePush {
    source_uv_rect: [f32; 4],
    params0: [f32; 4],
    params1: [f32; 4],
}

fn display_composite_feedback_active(settings: NativeDisplayCompositeSettings) -> bool {
    settings.enabled
        && settings.feedback_enabled
        && !settings.high_rate_json_payload
        && matches!(
            settings.mode,
            NativeDisplayCompositeMode::GpuFeedbackDiagnostic
                | NativeDisplayCompositeMode::GpuRecursiveFeedbackDiagnostic
                | NativeDisplayCompositeMode::GpuReadbackDiagnostic
        )
}

fn display_composite_recursive_feedback_active(settings: NativeDisplayCompositeSettings) -> bool {
    display_composite_feedback_active(settings)
        && matches!(
            settings.mode,
            NativeDisplayCompositeMode::GpuRecursiveFeedbackDiagnostic
        )
}

fn display_composite_gpu_readback_active(settings: NativeDisplayCompositeSettings) -> bool {
    display_composite_feedback_active(settings)
        && matches!(
            settings.mode,
            NativeDisplayCompositeMode::GpuReadbackDiagnostic
        )
}

fn feedback_projection_label(projection: NativeDisplayCompositeFeedbackProjection) -> &'static str {
    match projection {
        NativeDisplayCompositeFeedbackProjection::MetadataTargetScreenUv => {
            "metadata-target-screen-uv"
        }
        NativeDisplayCompositeFeedbackProjection::FullEyePeripheralStretch => {
            "full-eye-peripheral-stretch"
        }
    }
}

unsafe fn create_display_composite_resources(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    format_key: AhbVulkanFormatKey,
    format_props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>,
) -> Result<DisplayCompositeResources, String> {
    let sampler_ycbcr_conversion =
        create_ahb_sampler_ycbcr_conversion(device, format_key, format_props, "display-composite")?;
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
            return Err(format!("create display-composite sampler: {error}"));
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
                "create display-composite descriptor set layout: {error}"
            ));
        }
    };
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(DISPLAY_COMPOSITE_IMPORT_CACHE_LIMIT as u32 * 2)];
    let descriptor_pool = match device.create_descriptor_pool(
        &vk::DescriptorPoolCreateInfo::default()
            .flags(vk::DescriptorPoolCreateFlags::FREE_DESCRIPTOR_SET)
            .pool_sizes(&pool_sizes)
            .max_sets(DISPLAY_COMPOSITE_IMPORT_CACHE_LIMIT as u32 * 2),
        None,
    ) {
        Ok(pool) => pool,
        Err(error) => {
            device.destroy_descriptor_set_layout(descriptor_set_layout, None);
            device.destroy_sampler(sampler, None);
            if let Some(conversion) = sampler_ycbcr_handle {
                device.destroy_sampler_ycbcr_conversion(conversion, None);
            }
            return Err(format!("create display-composite descriptor pool: {error}"));
        }
    };
    let push_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(display_push_size())];
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
            return Err(format!("create display-composite pipeline layout: {error}"));
        }
    };
    let pipeline = match create_display_composite_pipeline(
        device,
        render_pass,
        pipeline_layout,
        DisplayCompositeFragmentShader::DirectFeedback,
    ) {
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
                "{} displayCompositeExternalFormatSampling=true displayCompositeSamplerYcbcrConversion=true displayCompositeDescriptorUsesImmutableSampler=true",
                metadata.marker_fields()
            )
        })
        .unwrap_or_else(|| {
            "displayCompositeExternalFormatSampling=false displayCompositeSamplerYcbcrConversion=false displayCompositeDescriptorUsesImmutableSampler=false".to_string()
        });
    crate::marker(
        "display-composite-feedback-resources",
        format!(
            "status=created stream=display_composite externalFormat={} vkFormat={:?} descriptorShape={} shaderPath=metadata-target-display-composite-feedback formatFeaturesRaw=0x{:x} formatFeatures={} samplerFilter={:?} samplerLinearFilterSupported={} {} displayCompositeGpuImportReady=false displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image",
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

    Ok(DisplayCompositeResources {
        format_key,
        sampler_ycbcr_conversion: sampler_ycbcr_handle,
        sampler,
        sampler_filter,
        descriptor_uses_immutable_sampler,
        descriptor_set_layout,
        descriptor_pool,
        pipeline_layout,
        pipeline,
    })
}

unsafe fn allocate_display_composite_descriptor_set(
    device: &ash::Device,
    resources: &DisplayCompositeResources,
    image_view: vk::ImageView,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [resources.descriptor_set_layout];
    let descriptor_set = device
        .allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(resources.descriptor_pool)
                .set_layouts(&set_layouts),
        )
        .map_err(|error| format!("allocate display-composite descriptor set: {error}"))?
        .pop()
        .ok_or_else(|| "display-composite descriptor allocation returned no set".to_string())?;
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

unsafe fn import_display_composite_hardware_buffer(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    resources: &DisplayCompositeResources,
    frame: &DisplayCompositeFrame,
    key: DisplayCompositeImportKey,
    format_key: AhbVulkanFormatKey,
    allocation_size: vk::DeviceSize,
    memory_type_bits: u32,
) -> Result<DisplayCompositeImport, String> {
    let sampled_image = import_ahb_sampled_image(
        device,
        memory_properties,
        &frame.hardware_buffer,
        AhbVulkanSampledImageCreateInfo {
            width: frame.descriptor.width,
            height: frame.descriptor.height,
            format_key,
            allocation_size,
            memory_type_bits,
            sampler_ycbcr_conversion: resources.sampler_ycbcr_conversion,
            debug_label: "display-composite",
        },
    )?;
    let descriptor_set =
        allocate_display_composite_descriptor_set(device, resources, sampled_image.image_view)?;
    Ok(DisplayCompositeImport {
        key,
        sampled_image,
        descriptor_set,
        descriptor_pool: resources.descriptor_pool,
        needs_layout_transition: true,
    })
}

#[derive(Clone, Copy)]
enum DisplayCompositeFragmentShader {
    NormalizeInput,
    DirectFeedback,
    RecursiveFeedback,
    Full3Pack,
    Full3Blur,
    Full3Final,
}

impl DisplayCompositeFragmentShader {
    fn spirv_bytes(self) -> &'static [u8] {
        match self {
            Self::NormalizeInput => include_bytes!(concat!(
                env!("OUT_DIR"),
                "/sampled_input_normalize.frag.spv"
            )),
            Self::Full3Pack => include_bytes!(concat!(env!("OUT_DIR"), "/full3_0.frag.spv")),
            Self::Full3Blur => include_bytes!(concat!(env!("OUT_DIR"), "/full3_1.frag.spv")),
            Self::Full3Final => include_bytes!(concat!(env!("OUT_DIR"), "/full3_2.frag.spv")),
            Self::DirectFeedback => include_bytes!(concat!(
                env!("OUT_DIR"),
                "/display_composite_feedback.frag.spv"
            )),
            Self::RecursiveFeedback => include_bytes!(concat!(
                env!("OUT_DIR"),
                "/display_composite_recursive_feedback.frag.spv"
            )),
        }
    }
}

unsafe fn create_sampler(
    device: &ash::Device,
    filter: vk::Filter,
    sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    label: &str,
) -> Result<vk::Sampler, String> {
    let mut sampler_conversion_info = vk::SamplerYcbcrConversionInfo::default();
    let mut sampler_info = vk::SamplerCreateInfo::default()
        .mag_filter(filter)
        .min_filter(filter)
        .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
        .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
        .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE);
    if let Some(conversion) = sampler_ycbcr_conversion {
        sampler_conversion_info = sampler_conversion_info.conversion(conversion);
        sampler_info = sampler_info.push_next(&mut sampler_conversion_info);
    }
    device
        .create_sampler(&sampler_info, None)
        .map_err(|error| format!("create {label}: {error}"))
}

fn create_recursive_descriptor_set_layout(
    device: &ash::Device,
    current_sampler: vk::Sampler,
    current_descriptor_uses_immutable_sampler: bool,
) -> Result<vk::DescriptorSetLayout, String> {
    let current_immutable_samplers = [current_sampler];
    let mut current_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(0)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::FRAGMENT);
    if current_descriptor_uses_immutable_sampler {
        current_binding = current_binding.immutable_samplers(&current_immutable_samplers);
    }
    let previous_binding = vk::DescriptorSetLayoutBinding::default()
        .binding(1)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::FRAGMENT);
    let bindings = [current_binding, previous_binding];
    unsafe {
        device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|error| {
                format!("create display-composite recursive descriptor layout: {error}")
            })
    }
}

fn create_single_sampler_descriptor_set_layout(
    device: &ash::Device,
    label: &str,
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
            .map_err(|error| format!("create {label}: {error}"))
    }
}

fn create_recursive_descriptor_pool(device: &ash::Device) -> Result<vk::DescriptorPool, String> {
    let descriptor_count = (DISPLAY_COMPOSITE_RECURSIVE_TEXTURE_COUNT as u32 * 3) + 4;
    let max_sets = (DISPLAY_COMPOSITE_RECURSIVE_TEXTURE_COUNT as u32 * 2) + 2;
    let pool_sizes = [vk::DescriptorPoolSize::default()
        .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(descriptor_count)];
    unsafe {
        device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .pool_sizes(&pool_sizes)
                    .max_sets(max_sets),
                None,
            )
            .map_err(|error| format!("create display-composite recursive descriptor pool: {error}"))
    }
}

fn create_pipeline_layout(
    device: &ash::Device,
    descriptor_set_layout: vk::DescriptorSetLayout,
    push_size: u32,
    label: &str,
) -> Result<vk::PipelineLayout, String> {
    let set_layouts = [descriptor_set_layout];
    let push_ranges = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
        .offset(0)
        .size(push_size)];
    unsafe {
        device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default()
                    .set_layouts(&set_layouts)
                    .push_constant_ranges(if push_size == 0 { &[] } else { &push_ranges }),
                None,
            )
            .map_err(|error| format!("create {label}: {error}"))
    }
}

fn allocate_descriptor_set(
    device: &ash::Device,
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    label: &str,
) -> Result<vk::DescriptorSet, String> {
    let set_layouts = [descriptor_set_layout];
    unsafe {
        device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(descriptor_pool)
                    .set_layouts(&set_layouts),
            )
            .map_err(|error| format!("allocate {label}: {error}"))?
            .pop()
            .ok_or_else(|| format!("allocate {label}: no descriptor set returned"))
    }
}

unsafe fn write_single_sampler_descriptor(
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

unsafe fn create_display_composite_recursive_render_pass(
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
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_refs)];
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
        .map_err(|error| format!("create display-composite recursive render pass: {error}"))
}

unsafe fn begin_recursive_feedback_pass(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
    clear_color: [f32; 4],
) {
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: clear_color,
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

#[allow(clippy::too_many_arguments)]
unsafe fn destroy_recursive_pipeline_state(
    device: &ash::Device,
    final_pipeline: vk::Pipeline,
    final_pipeline_layout: vk::PipelineLayout,
    recursive_pipeline: vk::Pipeline,
    recursive_pipeline_layout: vk::PipelineLayout,
    descriptor_pool: vk::DescriptorPool,
    final_descriptor_set_layout: vk::DescriptorSetLayout,
    recursive_descriptor_set_layout: vk::DescriptorSetLayout,
    feedback_sampler: vk::Sampler,
    current_sampler: vk::Sampler,
    current_sampler_ycbcr_conversion: Option<vk::SamplerYcbcrConversion>,
    render_pass: vk::RenderPass,
) {
    device.destroy_pipeline(final_pipeline, None);
    device.destroy_pipeline_layout(final_pipeline_layout, None);
    device.destroy_pipeline(recursive_pipeline, None);
    device.destroy_pipeline_layout(recursive_pipeline_layout, None);
    device.destroy_descriptor_pool(descriptor_pool, None);
    device.destroy_descriptor_set_layout(final_descriptor_set_layout, None);
    device.destroy_descriptor_set_layout(recursive_descriptor_set_layout, None);
    device.destroy_sampler(feedback_sampler, None);
    device.destroy_sampler(current_sampler, None);
    if let Some(conversion) = current_sampler_ycbcr_conversion {
        device.destroy_sampler_ycbcr_conversion(conversion, None);
    }
    device.destroy_render_pass(render_pass, None);
}

unsafe fn create_display_composite_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
    fragment_shader: DisplayCompositeFragmentShader,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/display_composite_feedback.vert.spv"
    )))?;
    let fragment_words = spirv_words(fragment_shader.spirv_bytes())?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create display-composite vertex shader module: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create display-composite fragment shader module: {error}"
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
        .map_err(|(_, error)| format!("create display-composite graphics pipeline: {error}"))
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

fn recursive_feedback_extent(frame: &DisplayCompositeFrame) -> vk::Extent2D {
    let source_width = frame
        .descriptor
        .width
        .max(frame.configured_width.max(1) as u32);
    let source_height = frame
        .descriptor
        .height
        .max(frame.configured_height.max(1) as u32);
    let width = source_width
        .min(DISPLAY_COMPOSITE_RECURSIVE_MAX_WIDTH)
        .max(1);
    let height = ((width as f32) * (source_height as f32 / source_width.max(1) as f32))
        .round()
        .max(1.0) as u32;
    vk::Extent2D {
        width: width.max(DISPLAY_COMPOSITE_RECURSIVE_MIN_WIDTH.min(source_width.max(1))),
        height,
    }
}

fn gpu_readback_extent(frame: &DisplayCompositeFrame) -> vk::Extent2D {
    vk::Extent2D {
        width: frame
            .descriptor
            .width
            .max(frame.configured_width.max(1) as u32)
            .max(1),
        height: frame
            .descriptor
            .height
            .max(frame.configured_height.max(1) as u32)
            .max(1),
    }
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
