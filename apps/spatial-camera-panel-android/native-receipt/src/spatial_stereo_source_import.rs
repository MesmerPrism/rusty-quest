//! Actual retained source-set adapter; two bounded scalar normalizers, one WSI final.
use crate::stereo_normalizer_reuse_policy::{
    refresh_disposition, NormalizerCompatibility, RefreshDisposition,
};
use crate::{
    ahardware_buffer_vulkan::{
        self as ahb, AhbVulkanSampledImage, AhbVulkanSampledImageCreateInfo,
    },
    camera_hwb_probe::CameraHwbProbeMode,
    camera_hwb_wsi::{update_camera_hwb_probe_descriptor_set, CameraHwbProbeResources},
    own_packed_pool::PackedLease,
    packed_sbs_normalizer::PackedSbsNormalizer,
    spatial_public_multistack_runtime::{
        StereoGuideKey, StereoRecordingInputs, StereoRecordingSource,
    },
    spatial_video_projection_native_stream::{
        projection_peer_binding_matches, SpatialVideoProjectionFrame,
    },
    stereo_bank_transport_v1::StereoBankPolicyUniformV1,
    stereo_input_set::{RetainedStereoFrame, SourceEpoch, StereoFrameIdentity, StereoOrigin},
    stereo_source_payload::StereoSourceLease,
};
use ash::vk;

type Frame = RetainedStereoFrame<StereoSourceLease<PackedLease, SpatialVideoProjectionFrame>>;
fn normalizer_processing_policy() -> [u32; 4] {
    let policy = crate::spatial_guide_processing::current_spatial_guide_processing_policy();
    [
        policy.preblur_kernel as u32,
        policy.preblur_input as u32,
        policy.postblur_kernel as u32,
        policy.camera_sampling as u32,
    ]
}
pub(crate) struct SourceCarrierSeed {
    pub(crate) hardware_buffer: crate::android_hardware_buffer::AndroidHardwareBufferHandle,
    pub(crate) descriptor: crate::android_hardware_buffer::AndroidHardwareBufferDescriptor,
    pub(crate) identity: StereoFrameIdentity,
    _frame: Option<Frame>,
}
impl SourceCarrierSeed {
    pub(crate) fn release_seed_content(&mut self) {
        self._frame.take();
    }
}
pub(crate) unsafe fn source_carrier_seed() -> Result<Option<SourceCarrierSeed>, String> {
    synchronize_peer_source()?;
    let frames = crate::own_stereo_capture_runtime::shared_sources()
        .snapshot(monotonic_ns()?, 1_000_000_000)?;
    let Some(frame) = frames.into_iter().flatten().next() else {
        return Ok(None);
    };
    let (hardware_buffer, descriptor) = match &frame.lease {
        StereoSourceLease::Own(own) => (
            own.contents().allocation.clone(),
            own.contents().allocation.descriptor(),
        ),
        StereoSourceLease::Peer(peer) => (peer.hardware_buffer.clone(), peer.descriptor),
    };
    Ok(Some(SourceCarrierSeed {
        hardware_buffer,
        descriptor,
        identity: frame.identity,
        _frame: Some(frame),
    }))
}
/// Observe only the current decoder binding. Removing a subscription never
/// removes Own or leases already retained by a submission.
pub(crate) fn synchronize_peer_source() -> Result<(), String> {
    if let Some(frame) =
        crate::spatial_video_projection_native_stream::latest_projection_peer_frame()
    {
        if projection_peer_binding_matches(
            frame.route_generation,
            frame.decoder_token,
            frame.reader_generation,
        ) {
            publish_peer_source(frame)?;
        }
    }
    let frames =
        crate::own_stereo_capture_runtime::shared_sources().snapshot(monotonic_ns()?, u64::MAX)?;
    if let Some(frame) = frames[1].as_ref() {
        if let StereoSourceLease::Peer(peer) = &frame.lease {
            if !projection_peer_binding_matches(
                peer.route_generation,
                peer.decoder_token,
                peer.reader_generation,
            ) {
                crate::own_stereo_capture_runtime::shared_sources()
                    .retire(StereoOrigin::PeerStereo, frame.identity.epoch)?;
                crate::spatial_stereo_qualification::peer_removed(frame.identity.epoch);
            }
        }
    }
    Ok(())
}
fn monotonic_ns() -> Result<u64, String> {
    let mut value = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut value) } != 0 {
        return Err("source-monotonic-clock-unavailable".into());
    }
    (value.tv_sec as u64)
        .checked_mul(1_000_000_000)
        .and_then(|n| n.checked_add(value.tv_nsec as u64))
        .ok_or("source-clock-overflow".into())
}

pub(crate) fn publish_peer_source(frame: SpatialVideoProjectionFrame) -> Result<(), String> {
    if !projection_peer_binding_matches(
        frame.route_generation,
        frame.decoder_token,
        frame.reader_generation,
    ) {
        return Err("peer-source-binding-stale".into());
    }
    let pair = frame.packed_pair.ok_or("peer-exact-packed-pair-missing")?;
    let epoch = SourceEpoch {
        process_generation: crate::own_packed_pool_jni::process_generation()?,
        source_generation: frame.reader_generation,
    };
    let now = monotonic_ns()?;
    let sources = crate::own_stereo_capture_runtime::shared_sources();
    if let Some(previous) = sources.snapshot(now, u64::MAX)?[1].as_ref() {
        if previous.identity.epoch == epoch && previous.identity.pair_sequence == pair.pair_id {
            return Ok(());
        }
    }
    sources.bind(StereoOrigin::PeerStereo, epoch)?;
    // Exact AImage timestamp matched the decoder's queued packed metadata.
    let identity = StereoFrameIdentity {
        epoch,
        pair_sequence: pair.pair_id,
        left_timestamp_ns: pair.left_sensor_timestamp_ns,
        right_timestamp_ns: pair.right_sensor_timestamp_ns,
        packed_pts_ns: frame.timestamp_ns,
        calibration_revision: None,
    };
    sources.publish_peer(RetainedStereoFrame {
        identity,
        observed_at_ns: now,
        lease: frame,
    })
}
/// Registry source removal only. Decoder/GPU owner teardown remains separate;
/// previously submitted source leases stay alive until actual fence retirement.
pub(crate) fn retire_peer_source(route: u64, decoder: u64, reader: u64) -> Result<bool, String> {
    if !crate::spatial_video_projection_native_stream::projection_peer_reader_stopped(
        route, decoder, reader,
    ) {
        return Ok(false);
    }
    let epoch = SourceEpoch {
        process_generation: crate::own_packed_pool_jni::process_generation()?,
        source_generation: reader,
    };
    let Some(changed) = crate::own_stereo_capture_runtime::shared_sources()
        .retire_stopped_or_unbound(StereoOrigin::PeerStereo, epoch)?
    else {
        return Ok(false);
    };
    if changed {
        crate::spatial_stereo_qualification::peer_removed(epoch);
    }
    Ok(true)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_OwnPackedPoolNative_nativeRetirePeerSource(
    mut env: jni::JNIEnv<'_>,
    _: jni::objects::JClass<'_>,
    route: jni::sys::jlong,
    decoder: jni::sys::jlong,
    reader: jni::sys::jlong,
) -> jni::sys::jboolean {
    if route <= 0 || decoder <= 0 || reader <= 0 {
        return 0;
    }
    match retire_peer_source(route as u64, decoder as u64, reader as u64) {
        Ok(removed) => u8::from(removed),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

struct ImportedSource {
    compatibility: NormalizerCompatibility,
    normalizer: PackedSbsNormalizer,
    image: AhbVulkanSampledImage,
    frame: Frame,
}
pub(crate) struct StereoSourceImports {
    pool: vk::DescriptorPool,
    sets: [vk::DescriptorSet; 2],
    sources: [Option<ImportedSource>; 2],
    pending: Option<vk::Fence>,
    foreign_queue_family: Option<u32>,
    control_revision: u64,
    processing_policy: [u32; 4],
}
impl StereoSourceImports {
    pub(crate) unsafe fn create(
        device: &ash::Device,
        resources: &CameraHwbProbeResources,
        foreign_queue_family: Option<u32>,
    ) -> Result<Self, String> {
        if resources.descriptor_shape != "left-right"
            && resources.descriptor_shape != "two-eye-textures"
            && resources.descriptor_shape != "stereo-2-bindings"
        {
            // Binding count is explicitly verified through selected stereo mode
            // below; immutable YCbCr camera layouts must not accept RGBA eyes.
            if resources.sampler_ycbcr_conversion.is_some() {
                return Err("stereo-normalized-camera-layout-ycbcr".into());
            }
        }
        let sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(4)];
        let pool = device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .pool_sizes(&sizes)
                    .max_sets(2),
                None,
            )
            .map_err(|e| format!("stereo-source-descriptor-pool-{e:?}"))?;
        let layouts = [resources.descriptor_set_layout; 2];
        let sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(pool)
                .set_layouts(&layouts),
        ) {
            Ok(sets) => [sets[0], sets[1]],
            Err(e) => {
                device.destroy_descriptor_pool(pool, None);
                return Err(format!("stereo-source-descriptor-sets-{e:?}"));
            }
        };
        Ok(Self {
            pool,
            sets,
            sources: [None, None],
            pending: None,
            foreign_queue_family,
            control_revision: 0,
            processing_policy: [0; 4],
        })
    }
    pub(crate) unsafe fn retire_after_fence(&mut self, device: &ash::Device) -> Result<(), String> {
        if let Some(fence) = self.pending {
            if !device
                .get_fence_status(fence)
                .map_err(|e| format!("stereo-import-fence-{e:?}"))?
            {
                return Err("stereo-imports-physical-pending".into());
            }
            self.pending = None;
        }
        Ok(())
    }
    // Positive typed SDK never-entered proof is bound to this exact imported frame fence.
    // Unknown or accepted submission cannot use this path.
    #[cfg(any(rq_environment_depth_spatial_sdk_api_layer, test))]
    pub(crate) fn cancel_sdk_unsubmitted(
        &mut self,
        session: u64,
        request: u64,
        proof: &crate::spatial_sdk_depth_handoff::SpatialUnsubmittedProof,
    ) -> Result<(), String> {
        use ash::vk::Handle;
        let fence = self.pending.ok_or("stereo-import SDK fence unavailable")?;
        if !proof.matches(session, request, fence.as_raw()) {
            return Err("stereo-import SDK proof differs".into());
        }
        self.pending = None;
        Ok(())
    }
    /// Refresh only after actual common fence retirement; snapshot owns bytes
    /// before releasing source lock. Never cache an AHB pointer as frame content.
    pub(crate) unsafe fn refresh(
        &mut self,
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        ahb_device: &ash::android::external_memory_android_hardware_buffer::Device,
        resources: &CameraHwbProbeResources,
        mode: CameraHwbProbeMode,
        maximum_age_ns: u64,
        control_revision: u64,
    ) -> Result<(), String> {
        if self.pending.is_some() {
            return Err("stereo-import-replacement-before-retirement".into());
        }
        // The caller has proven common-fence retirement. Any refresh failure
        // clears both bindings rather than leaving a partial stale source set.
        let result = self.refresh_retired(
            device,
            memory,
            ahb_device,
            resources,
            mode,
            maximum_age_ns,
            control_revision,
        );
        if result.is_err() {
            self.clear_retired_sources(device);
        }
        result
    }
    unsafe fn clear_retired_sources(&mut self, device: &ash::Device) {
        debug_assert!(self.pending.is_none());
        for slot in &mut self.sources {
            if let Some(previous) = slot.take() {
                previous.image.destroy(device);
                previous.normalizer.destroy(device);
            }
        }
        self.control_revision = 0;
    }
    unsafe fn refresh_retired(
        &mut self,
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        ahb_device: &ahb::AhbVulkanDevice,
        resources: &CameraHwbProbeResources,
        mode: CameraHwbProbeMode,
        maximum_age_ns: u64,
        control_revision: u64,
    ) -> Result<(), String> {
        if control_revision == 0 {
            return Err("stereo-normalizer-control-unavailable".into());
        }
        let processing_policy = normalizer_processing_policy();
        if mode.descriptor_binding_count() != 2 || resources.sampler_ycbcr_conversion.is_some() {
            return Err("stereo-normalized-layout-invalid".into());
        }
        let frames = crate::own_stereo_capture_runtime::shared_sources()
            .snapshot(monotonic_ns()?, maximum_age_ns)?;
        for (origin, frame) in frames.into_iter().enumerate() {
            let Some(frame) = frame else {
                if let Some(previous) = self.sources[origin].take() {
                    previous.image.destroy(device);
                    previous.normalizer.destroy(device);
                }
                continue;
            };
            if matches!(&frame.lease, StereoSourceLease::Own(_))
                && self.foreign_queue_family.is_none()
            {
                return Err("own-source-foreign-ownership-unavailable".into());
            }
            let (hardware, descriptor) = match &frame.lease {
                StereoSourceLease::Own(own) => (
                    own.contents().allocation.clone(),
                    own.contents().allocation.descriptor(),
                ),
                StereoSourceLease::Peer(peer) => (peer.hardware_buffer.clone(), peer.descriptor),
            };
            let (properties, format) =
                ahb::query_ahb_vulkan_import_properties(ahb_device, &hardware)?;
            let bottom_up = matches!(&frame.lease, StereoSourceLease::Own(_));
            let producer_generations = match &frame.lease {
                StereoSourceLease::Own(own) => [own.contents().version.pool_generation, 0, 0],
                StereoSourceLease::Peer(peer) => [
                    peer.route_generation,
                    peer.decoder_token,
                    peer.reader_generation,
                ],
            };
            let compatibility = NormalizerCompatibility {
                epoch: [
                    frame.identity.epoch.process_generation,
                    frame.identity.epoch.source_generation,
                ],
                producer_generations,
                control_revision,
                processing_policy,
                descriptor: [
                    descriptor.width as u64,
                    descriptor.height as u64,
                    descriptor.layers as u64,
                    descriptor.format as u64,
                    descriptor.usage,
                    descriptor.stride as u64,
                ],
                format: [
                    properties.format_key.format.as_raw() as u64,
                    properties.format_key.external_format,
                ],
                conversion: [
                    format.format_features.as_raw() as i32,
                    format.suggested_ycbcr_model.as_raw(),
                    format.suggested_ycbcr_range.as_raw(),
                    format.sampler_ycbcr_conversion_components.r.as_raw(),
                    format.sampler_ycbcr_conversion_components.g.as_raw(),
                    format.sampler_ycbcr_conversion_components.b.as_raw(),
                    format.sampler_ycbcr_conversion_components.a.as_raw(),
                    format.suggested_x_chroma_offset.as_raw(),
                    format.suggested_y_chroma_offset.as_raw(),
                ],
                bottom_up,
            };
            let reuse = refresh_disposition(
                true,
                self.sources[origin].as_ref().map(|s| &s.compatibility),
                Some(&compatibility),
            ) == RefreshDisposition::Reuse;
            // Reuse only normalization storage; import the exact fresh leased
            // AHB on every refresh. No AHB address is cached as frame identity.
            let mut normalizer = if reuse {
                let previous = self.sources[origin]
                    .take()
                    .expect("compatible source present");
                previous.image.destroy(device);
                previous.normalizer
            } else {
                if let Some(previous) = self.sources[origin].take() {
                    previous.image.destroy(device);
                    previous.normalizer.destroy(device);
                }
                PackedSbsNormalizer::create(
                    device,
                    memory,
                    descriptor.width,
                    descriptor.height,
                    properties.format_key,
                    &format,
                )?
            };
            normalizer.set_source_bottom_up(bottom_up);
            let image = match ahb::import_ahb_sampled_image(
                device,
                memory,
                &hardware,
                AhbVulkanSampledImageCreateInfo {
                    width: descriptor.width,
                    height: descriptor.height,
                    format_key: properties.format_key,
                    allocation_size: properties.allocation_size,
                    memory_type_bits: properties.memory_type_bits,
                    sampler_ycbcr_conversion: normalizer.source_sampler_ycbcr_conversion(),
                    debug_label: "stereo-retained-packed-source",
                },
            ) {
                Ok(image) => image,
                Err(error) => {
                    normalizer.destroy(device);
                    return Err(error);
                }
            };
            normalizer.update_source(device, image.image_view);
            let eyes = normalizer.image_views();
            update_camera_hwb_probe_descriptor_set(
                device,
                resources,
                self.sets[origin],
                eyes[0],
                Some(eyes[1]),
                mode,
            );
            self.sources[origin] = Some(ImportedSource {
                compatibility,
                normalizer,
                image,
                frame,
            });
        }
        if normalizer_processing_policy() != processing_policy
            || crate::spatial_public_multistack_runtime::read_mask_policy().1 != control_revision
        {
            return Err("stereo-normalizer-control-changed-during-refresh".into());
        }
        self.control_revision = control_revision;
        self.processing_policy = processing_policy;
        Ok(())
    }
    pub(crate) fn recording_inputs(
        &mut self,
        policy: StereoBankPolicyUniformV1,
        processing_revision: u64,
        demanded_prefixes: [usize; 2],
        fence: vk::Fence,
        frame_ordinal: u64,
        surface_generation: u64,
    ) -> Result<StereoRecordingInputs<'_>, String> {
        if self.pending.is_some()
            || fence == vk::Fence::null()
            || processing_revision == 0
            || processing_revision != self.control_revision
            || normalizer_processing_policy() != self.processing_policy
        {
            return Err("stereo-recording-admission-invalid".into());
        }
        self.pending = Some(fence);
        let processing_policy =
            crate::spatial_guide_processing::current_spatial_guide_processing_policy();
        let [own, peer] = &mut self.sources;
        let sources=[own,peer].into_iter().enumerate().map(|(origin,slot)|slot.as_mut().map(|source|
            StereoRecordingSource{key:StereoGuideKey {origin,frame:source.frame.identity,geometry_revision:None,processing_revision,processing_policy},
                lease:Box::new(source.frame.lease.clone()),descriptor_set:self.sets[origin],normalizer:&mut source.normalizer,
                packed_image:&source.image,transition_source:true,
                foreign_queue_family:if matches!(&source.frame.lease,StereoSourceLease::Own(_)){self.foreign_queue_family}else{None},
                // Image-only publication cannot invent remote capture pose.
                reprojection:crate::camera_latency_diagnostics::current_camera_latency_stereo_reprojection(None,None),overscan_uv:0.0,
                observed_at_ns:source.frame.observed_at_ns,content_serial:match &source.frame.lease{StereoSourceLease::Own(lease)=>lease.contents().version.slot_serial,StereoSourceLease::Peer(peer)=>peer.import_sequence}})).collect::<Vec<_>>();
        let mut iter = sources.into_iter();
        Ok(StereoRecordingInputs {
            sources: [iter.next().unwrap(), iter.next().unwrap()],
            policy,
            demanded_prefixes,
            retirement_fence: fence,
            processing_policy,
            frame_ordinal,
            surface_generation,
            control_revision: processing_revision,
        })
    }
    pub(crate) unsafe fn destroy(mut self, device: &ash::Device) -> Result<(), String> {
        if self.pending.is_some() {
            std::mem::forget(self);
            return Err("stereo-source-imports-retained-physical-pending".into());
        }
        for source in &mut self.sources {
            if let Some(source) = source.take() {
                source.image.destroy(device);
                source.normalizer.destroy(device);
            }
        }
        device.destroy_descriptor_pool(self.pool, None);
        Ok(())
    }
}
impl Drop for StereoSourceImports {
    fn drop(&mut self) {
        if self.pending.is_some() {
            for source in &mut self.sources {
                if let Some(source) = source.take() {
                    std::mem::forget(source);
                }
            }
        }
    }
}
