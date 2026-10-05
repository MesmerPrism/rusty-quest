//! Opt-in dual producer banks feeding one retained final graph.
use super::*;
use crate::stereo_bank_transport_v1::{self as abi, StereoBankPolicyUniformV1};
use crate::stereo_input_set::StereoFrameIdentity;
use std::any::Any;
static SOURCE_BANKS_ACTIVE: AtomicBool = AtomicBool::new(false);
static CONTROL_POLICY: Mutex<([u32; 6], u64, [u32; 3])> =
    Mutex::new(([0, 0, 0, 0, 2, 2], 1, [0; 3]));
pub(crate) fn source_banks_enabled() -> bool {
    SOURCE_BANKS_ACTIVE.load(Ordering::Acquire)
}
pub(crate) fn read_control_policy() -> ([u32; 6], u64) {
    let p = CONTROL_POLICY
        .lock()
        .unwrap_or_else(|poison| poison.into_inner());
    (p.0, p.1)
}
pub(crate) fn update_control_policy(values: [u32; 6]) -> Result<u64, String> {
    if !source_banks_enabled() {
        return Err("source-banks-disabled".into());
    }
    if values[..4].iter().any(|&v| v > 1) || values[4..].iter().any(|&v| v > 2) {
        return Err("source-bank-policy-invalid".into());
    }
    let mut policy = CONTROL_POLICY
        .lock()
        .map_err(|_| "source-bank-policy-poisoned")?;
    if policy.0 == values {
        return Ok(policy.1);
    }
    let revision = policy
        .1
        .checked_add(1)
        .ok_or("source-bank-policy-revision-exhausted")?;
    policy.0 = values;
    policy.1 = revision;
    Ok(revision)
}

pub(crate) fn read_mask_policy() -> ([u32; 6], u64, [u32; 3]) {
    *CONTROL_POLICY.lock().unwrap_or_else(|p| p.into_inner())
}
pub(crate) fn update_mask_policy(words: [u32; 3]) -> Result<u64, String> {
    if !source_banks_enabled() {
        return Err("source-banks-disabled".into());
    }
    crate::stereo_bank_mask_v1::validate(words).map_err(str::to_owned)?;
    let mut p = CONTROL_POLICY
        .lock()
        .map_err(|_| "source-bank-policy-poisoned")?;
    if p.2 == words {
        return Ok(p.1);
    }
    let revision =
        p.1.checked_add(1)
            .ok_or("source-bank-policy-revision-exhausted")?;
    p.2 = words;
    p.1 = revision;
    Ok(revision)
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct StereoGuideKey {
    pub origin: usize,
    pub frame: StereoFrameIdentity,
    pub geometry_revision: Option<u64>,
    pub processing_revision: u64,
    pub processing_policy: crate::spatial_guide_processing::SpatialGuideProcessingPolicy,
}
/// Already imported packed source and its scalar eye normalizer. The retained
/// lease pins actual contents, independently of import/allocation caches.
#[cfg(target_os = "android")]
pub(crate) struct StereoRecordingSource<'a> {
    pub key: StereoGuideKey,
    pub lease: Box<dyn Any>,
    pub descriptor_set: vk::DescriptorSet,
    pub normalizer: &'a mut crate::packed_sbs_normalizer::PackedSbsNormalizer,
    pub packed_image: &'a crate::ahardware_buffer_vulkan::AhbVulkanSampledImage,
    pub transition_source: bool,
    pub foreign_queue_family: Option<u32>,
    pub reprojection: CameraLatencyStereoReprojection,
    pub overscan_uv: f32,
    pub observed_at_ns: u64,
    pub content_serial: u64,
}
#[cfg(target_os = "android")]
pub(crate) struct StereoRecordingInputs<'a> {
    pub sources: [Option<StereoRecordingSource<'a>>; 2],
    pub policy: StereoBankPolicyUniformV1,
    pub demanded_prefixes: [usize; 2],
    pub retirement_fence: vk::Fence,
    pub processing_policy: crate::spatial_guide_processing::SpatialGuideProcessingPolicy,
    pub frame_ordinal: u64,
    pub surface_generation: u64,
    pub control_revision: u64,
}

struct ScalarProducerBank {
    targets: Vec<SpatialPublicGuideTarget>,
    sample_sets: Vec<vk::DescriptorSet>,
    scalar_set: vk::DescriptorSet,
    sample_pool: vk::DescriptorPool,
    scalar_pool: vk::DescriptorPool,
}
impl ScalarProducerBank {
    unsafe fn create(
        owner: &SpatialPublicGuideTargets,
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
    ) -> Result<Self, String> {
        let mut bank = Self {
            targets: Vec::new(),
            sample_sets: Vec::new(),
            scalar_set: vk::DescriptorSet::null(),
            sample_pool: vk::DescriptorPool::null(),
            scalar_pool: vk::DescriptorPool::null(),
        };
        let result = (|| {
            bank.sample_pool = create_descriptor_pool(device)?;
            bank.scalar_pool = create_opaque_guide_descriptor_pool(device)?;
            for index in 0..SPATIAL_PUBLIC_GUIDE_TARGET_COUNT {
                bank.targets.push(SpatialPublicGuideTarget::create(
                    device,
                    memory,
                    owner.render_pass,
                    owner.extent,
                    owner.format,
                    &format!("stereo-producer-target-{index}"),
                )?);
            }
            bank.sample_sets = allocate_sample_descriptor_sets(
                device,
                bank.sample_pool,
                owner.descriptor_set_layout,
                5,
            )?;
            for (set, target) in bank.sample_sets.iter().zip(&bank.targets) {
                write_sample_descriptor(device, *set, owner.sampler, target.image_view);
            }
            bank.scalar_set = allocate_opaque_guide_descriptor_set(
                device,
                bank.scalar_pool,
                owner.opaque_guide_descriptor_set_layout,
            )?;
            write_opaque_guide_descriptor_set(
                device,
                bank.scalar_set,
                owner.sampler,
                &bank.targets,
            );
            Ok(())
        })();
        match result {
            Ok(()) => Ok(bank),
            Err(error) => {
                bank.destroy(device);
                Err(error)
            }
        }
    }
    fn swap_into(&mut self, owner: &mut SpatialPublicGuideTargets) {
        mem::swap(&mut self.targets, &mut owner.targets);
        mem::swap(&mut self.sample_sets, &mut owner.sample_descriptor_sets);
        mem::swap(&mut self.scalar_set, &mut owner.opaque_guide_descriptor_set);
    }
    unsafe fn destroy(self, device: &ash::Device) {
        for target in self.targets {
            target.destroy(device);
        }
        if self.sample_pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.sample_pool, None);
        }
        if self.scalar_pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.scalar_pool, None);
        }
    }
}

pub(crate) struct StereoBankResources {
    peer: Option<ScalarProducerBank>,
    guides_layout: vk::DescriptorSetLayout,
    policy_layout: vk::DescriptorSetLayout,
    empty_layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    guides: vk::DescriptorSet,
    policy_set: vk::DescriptorSet,
    policy_buffer: vk::Buffer,
    policy_memory: vk::DeviceMemory,
    pub(super) pipeline_layout: vk::PipelineLayout,
    pub(super) pipeline: vk::Pipeline,
    pub(super) displacement_pipeline: vk::Pipeline,
    pub(super) video_layout: vk::DescriptorSetLayout,
    vertex_spirv: Vec<u8>,
    fragment_spirv: Vec<u8>,
    pending_fence: Option<vk::Fence>,
    retained: [Option<Box<dyn Any>>; 2],
    recorded_keys: [Option<StereoGuideKey>; 2],
    vk_hold_tokens: [Option<u64>; 2],
    submission_entered: bool,
    sdk_submission: Option<(u64, u64)>,
    receipt_frame: Option<(u64, u64)>,
    recorded_mask: [u32; 3],
    recorded_prefixes: [u32; 2],
}
impl Drop for StereoBankResources {
    fn drop(&mut self) {
        // Unknown submission/retirement retains actual contents fail-closed.
        // Explicit retirement clears these before ordinary destruction.
        if self.pending_fence.is_some() {
            for lease in &mut self.retained {
                if let Some(lease) = lease.take() {
                    mem::forget(lease);
                }
            }
        }
    }
}

impl SpatialPublicGuideTargets {
    pub(crate) unsafe fn activate_stereo_banks(
        &mut self,
        device: &ash::Device,
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
        memory: &vk::PhysicalDeviceMemoryProperties,
        video_layout: vk::DescriptorSetLayout,
        video_sampler_count: u32,
        fragment_spirv: &[u8],
        vertex_spirv: &[u8],
    ) -> Result<(), String> {
        if self.stereo_banks.is_some() {
            return Err("stereo-banks-already-active".into());
        }
        if video_layout == vk::DescriptorSetLayout::null() {
            return Err("stereo-video-layout-missing".into());
        }
        let limits = instance
            .get_physical_device_properties(physical_device)
            .limits;
        // Actual empty camera set0 removes YCbCr descriptor expansion from final
        // demand. Scalar producers retain their validated camera layouts.
        if !(1..=2).contains(&video_sampler_count) {
            return Err("stereo-video-layout-count-invalid".into());
        }
        let demand = abi::LayoutDemand {
            bound_sets: 6,
            samplers: 11 + video_sampler_count,
            sampled_images: 11 + video_sampler_count,
            uniform_buffers: 4,
            stage_resources: 16 + video_sampler_count,
            push_bytes: 128,
            uniform_range_bytes: mem::size_of::<ProjectionZoneUniform>() as u32,
        };
        abi::check_final_layout_limits(&limits, &demand)?;
        let mut state = StereoBankResources {
            peer: None,
            guides_layout: vk::DescriptorSetLayout::null(),
            policy_layout: vk::DescriptorSetLayout::null(),
            empty_layout: vk::DescriptorSetLayout::null(),
            pool: vk::DescriptorPool::null(),
            guides: vk::DescriptorSet::null(),
            policy_set: vk::DescriptorSet::null(),
            policy_buffer: vk::Buffer::null(),
            policy_memory: vk::DeviceMemory::null(),
            pipeline_layout: vk::PipelineLayout::null(),
            pipeline: vk::Pipeline::null(),
            displacement_pipeline: vk::Pipeline::null(),
            video_layout,
            vertex_spirv: vertex_spirv.to_vec(),
            fragment_spirv: fragment_spirv.to_vec(),
            pending_fence: None,
            retained: [None, None],
            recorded_keys: [None, None],
            vk_hold_tokens: [None, None],
            submission_entered: false,
            sdk_submission: None,
            receipt_frame: None,
            recorded_mask: [0; 3],
            recorded_prefixes: [0; 2],
        };
        let result = (|| {
            state.peer = Some(ScalarProducerBank::create(self, device, memory)?);
            state.guides_layout = device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default()
                        .bindings(&abi::final_guide_bindings()),
                    None,
                )
                .map_err(|e| format!("stereo-guide-layout-{e:?}"))?;
            state.policy_layout = device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default()
                        .bindings(&abi::final_policy_bindings()),
                    None,
                )
                .map_err(|e| format!("stereo-policy-layout-{e:?}"))?;
            state.empty_layout = device
                .create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default(), None)
                .map_err(|e| format!("stereo-empty-layout-{e:?}"))?;
            let (buffer, allocation) =
                create_host_coherent_uniform_buffer(device, memory, 48, "stereo-policy")?;
            state.policy_buffer = buffer;
            state.policy_memory = allocation;
            let sizes = [
                vk::DescriptorPoolSize::default()
                    .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                    .descriptor_count(10),
                vk::DescriptorPoolSize::default()
                    .ty(vk::DescriptorType::UNIFORM_BUFFER)
                    .descriptor_count(2),
            ];
            state.pool = device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .pool_sizes(&sizes)
                        .max_sets(2),
                    None,
                )
                .map_err(|e| format!("stereo-pool-{e:?}"))?;
            let layouts = [state.guides_layout, state.policy_layout];
            let sets = device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(state.pool)
                        .set_layouts(&layouts),
                )
                .map_err(|e| format!("stereo-sets-{e:?}"))?;
            state.guides = sets[0];
            state.policy_set = sets[1];
            let zone = [vk::DescriptorBufferInfo::default()
                .buffer(self.projection_zone_uniform.buffer)
                .range(mem::size_of::<ProjectionZoneUniform>() as u64)];
            let policy = [vk::DescriptorBufferInfo::default()
                .buffer(state.policy_buffer)
                .range(48)];
            device.update_descriptor_sets(
                &[
                    vk::WriteDescriptorSet::default()
                        .dst_set(state.policy_set)
                        .dst_binding(0)
                        .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                        .buffer_info(&zone),
                    vk::WriteDescriptorSet::default()
                        .dst_set(state.policy_set)
                        .dst_binding(1)
                        .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                        .buffer_info(&policy),
                ],
                &[],
            );
            let layouts = [
                state.empty_layout,
                state.guides_layout,
                self.depth_descriptor_set_layout,
                self.rgb_channel_transform_uniform.descriptor_set_layout,
                video_layout,
                state.policy_layout,
            ];
            let ranges = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
                .size(128)];
            state.pipeline_layout = device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&layouts)
                        .push_constant_ranges(&ranges),
                    None,
                )
                .map_err(|e| format!("stereo-final-layout-{e:?}"))?;
            state.pipeline = create_fullscreen_fragment_pipeline(
                device,
                self.projection_render_pass,
                state.pipeline_layout,
                fragment_spirv,
                "stereo-final",
                true,
            )?;
            state.displacement_pipeline = create_fragment_pipeline_with_vertex(
                device,
                self.projection_render_pass,
                state.pipeline_layout,
                vertex_spirv,
                fragment_spirv,
                "stereo-final-displacement",
                true,
            )?;
            Ok(())
        })();
        match result {
            Ok(()) => {
                self.stereo_banks = Some(Box::new(state));
                SOURCE_BANKS_ACTIVE.store(true, Ordering::Release);
                Ok(())
            }
            Err(error) => {
                state.destroy(device);
                Err(error)
            }
        }
    }

    /// Called for the exact prepared or retained video descriptor selected for
    /// this frame, before submission entry. Prior frame retirement is required
    /// by the source-set recorder, so replaced pipelines cannot be in flight.
    pub(crate) unsafe fn prepare_stereo_video_layout(
        &mut self,
        device: &ash::Device,
        video_layout: vk::DescriptorSetLayout,
    ) -> Result<(), String> {
        let Some(state) = self.stereo_banks.as_mut() else {
            return Ok(());
        };
        if state.video_layout == video_layout {
            return Ok(());
        }
        if state.submission_entered || video_layout == vk::DescriptorSetLayout::null() {
            return Err("stereo-video-layout-not-prepared".into());
        }
        let layouts = [
            state.empty_layout,
            state.guides_layout,
            self.depth_descriptor_set_layout,
            self.rgb_channel_transform_uniform.descriptor_set_layout,
            video_layout,
            state.policy_layout,
        ];
        let ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .size(128)];
        let layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default()
                    .set_layouts(&layouts)
                    .push_constant_ranges(&ranges),
                None,
            )
            .map_err(|e| format!("stereo-video-final-layout-{e:?}"))?;
        let pipeline = match create_fullscreen_fragment_pipeline(
            device,
            self.projection_render_pass,
            layout,
            &state.fragment_spirv,
            "stereo-final",
            true,
        ) {
            Ok(value) => value,
            Err(error) => {
                device.destroy_pipeline_layout(layout, None);
                return Err(error);
            }
        };
        let displacement = match create_fragment_pipeline_with_vertex(
            device,
            self.projection_render_pass,
            layout,
            &state.vertex_spirv,
            &state.fragment_spirv,
            "stereo-final-displacement",
            true,
        ) {
            Ok(value) => value,
            Err(error) => {
                device.destroy_pipeline(pipeline, None);
                device.destroy_pipeline_layout(layout, None);
                return Err(error);
            }
        };
        device.destroy_pipeline(state.pipeline, None);
        device.destroy_pipeline(state.displacement_pipeline, None);
        device.destroy_pipeline_layout(state.pipeline_layout, None);
        state.pipeline = pipeline;
        state.displacement_pipeline = displacement;
        state.pipeline_layout = layout;
        state.video_layout = video_layout;
        Ok(())
    }

    /// Called only after the owner observed the actual shared VkFence signaled,
    /// before resetting it. All images, descriptors, policy and leases stay
    /// immutable until this check succeeds.
    pub(crate) unsafe fn retire_stereo_banks_after_fence(
        &mut self,
        device: &ash::Device,
    ) -> Result<(), String> {
        if let Some(state) = self.stereo_banks.as_mut() {
            if let Some(fence) = state.pending_fence {
                if !device
                    .get_fence_status(fence)
                    .map_err(|e| format!("stereo-retirement-fence-{e:?}"))?
                {
                    return Err("stereo-fence-pending".into());
                }
                #[cfg(target_os = "android")]
                for token in &mut state.vk_hold_tokens {
                    if let Some(value) = *token {
                        if !crate::own_packed_gpu_holds::poll_vk_retired(value)? {
                            return Err("stereo-pool-vk-retirement-pending".into());
                        }
                        *token = None;
                    }
                }
                if let Some((ordinal, surface)) = state.receipt_frame.take() {
                    crate::spatial_stereo_qualification::gpu_retired(ordinal, surface);
                }
                state.pending_fence = None;
                state.retained = [None, None];
                state.recorded_keys = [None, None];
                state.submission_entered = false;
                state.sdk_submission = None;
            }
        }
        Ok(())
    }

    /// Synchronous gate immediately before direct queue submission or actual
    /// SDK broker enqueue. Caller may not reset/reuse the fence afterwards.
    pub(crate) fn mark_stereo_submission_entered(&mut self) -> Result<(), String> {
        if let Some(state) = self.stereo_banks.as_mut() {
            if state.pending_fence.is_none() || state.submission_entered {
                return Err("stereo-submit-state-invalid".into());
            }
            #[cfg(target_os = "android")]
            for token in state.vk_hold_tokens.iter().flatten() {
                crate::own_packed_gpu_holds::mark_vk_submission_entered(*token)?;
            }
            state.submission_entered = true;
            crate::spatial_stereo_qualification::observe_submission_entry();
        }
        Ok(())
    }

    pub(crate) fn mark_stereo_sdk_submission_entered(
        &mut self,
        session: u64,
        request: u64,
    ) -> Result<(), String> {
        if let Some(state) = self.stereo_banks.as_mut() {
            if state.pending_fence.is_none()
                || state.submission_entered
                || session == 0
                || request == 0
            {
                return Err("stereo SDK submit state invalid".into());
            }
            #[cfg(target_os = "android")]
            for token in state.vk_hold_tokens.iter().flatten() {
                crate::own_packed_gpu_holds::mark_vk_sdk_submission_entered(
                    *token, session, request,
                )?;
            }
            state.submission_entered = true;
            state.sdk_submission = Some((session, request));
            crate::spatial_stereo_qualification::observe_submission_entry();
        }
        Ok(())
    }
    pub(crate) fn cancel_stereo_sdk_unsubmitted(
        &mut self,
        proof: &crate::spatial_sdk_depth_handoff::SpatialUnsubmittedProof,
    ) -> Result<(), String> {
        use ash::vk::Handle;
        if let Some(state) = self.stereo_banks.as_mut() {
            let (session, request) = state
                .sdk_submission
                .ok_or("stereo SDK identity unavailable")?;
            let fence = state.pending_fence.ok_or("stereo SDK fence unavailable")?;
            if !state.submission_entered || !proof.matches(session, request, fence.as_raw()) {
                return Err("stereo SDK proof differs".into());
            }
            #[cfg(target_os = "android")]
            for token in &mut state.vk_hold_tokens {
                if let Some(value) = *token {
                    if !crate::own_packed_gpu_holds::cancel_vk_sdk_unsubmitted(value, proof)? {
                        return Err("stereo SDK hold proof rejected".into());
                    }
                    *token = None;
                }
            }
            if state.vk_hold_tokens.iter().any(Option::is_some) {
                return Err("stereo SDK holds pending".into());
            }
            state.pending_fence = None;
            state.retained = [None, None];
            state.recorded_keys = [None, None];
            if let Some((ordinal, surface)) = state.receipt_frame.take() {
                crate::spatial_stereo_qualification::cancel_sdk_unsubmitted(
                    ordinal, surface, proof,
                );
            }
            state.submission_entered = false;
            state.sdk_submission = None;
        }
        Ok(())
    }

    #[cfg(target_os = "android")]
    pub(crate) unsafe fn record_stereo_source_banks(
        &mut self,
        device: &ash::Device,
        command: vk::CommandBuffer,
        timestamps: &mut CameraHwbGpuTimestampTracker,
        slot: usize,
        elapsed: f32,
        mut inputs: StereoRecordingInputs<'_>,
    ) -> Result<SpatialPublicGuidePassRecord, String> {
        validate_recording_inputs(&inputs)?;
        let mut state = self
            .stereo_banks
            .take()
            .ok_or("stereo-final-not-activated")?;
        if state.pending_fence.is_some() {
            self.stereo_banks = Some(state);
            return Err("stereo-immutable-frame-pending".into());
        }
        // Install retention before any native recording. If later execution is
        // uncertain, pending_fence keeps these exact byte leases alive.
        state.pending_fence = Some(inputs.retirement_fence);
        let mut receipt = crate::spatial_stereo_qualification::FrameFact {
            arm_generation: crate::spatial_stereo_qualification::arm_generation(),
            ordinal: inputs.frame_ordinal,
            surface: inputs.surface_generation,
            revision: inputs.control_revision,
            policies: [
                inputs.policy.region_origins[0],
                inputs.policy.region_origins[1],
                inputs.policy.region_origins[2],
                inputs.policy.region_origins[3],
                inputs.policy.guide_origins[0],
                inputs.policy.guide_origins[1],
            ],
            mask: [
                inputs.policy.guide_origins[2],
                inputs.policy.guide_origins[3],
                inputs.policy.source_state[3],
            ],
            sources: [None, None],
            demanded: inputs.demanded_prefixes,
            final_draw: false,
            geometry_sampled: false,
            available: [inputs.sources[0].is_some(), inputs.sources[1].is_some()],
        };
        let result = crate::spatial_guide_processing::with_source_bank_processing_policy(
            inputs.processing_policy,
            || {
                // Reserve every sampled Own content hold before any native command
                // recording, while the exact common fence is reset and unsignaled.
                #[cfg(target_os = "android")]
                for origin in 0..2 {
                    if inputs.demanded_prefixes[origin] == 0 {
                        continue;
                    }
                    let Some(source) = inputs.sources[origin].as_ref() else {
                        continue;
                    };
                    if let Some(crate::stereo_source_payload::StereoSourceLease::Own(lease))=source.lease.downcast_ref::<
                    crate::stereo_source_payload::StereoSourceLease<crate::own_packed_pool::PackedLease,
                    crate::spatial_video_projection_native_stream::SpatialVideoProjectionFrame>>() {
                    state.vk_hold_tokens[origin]=Some(crate::own_packed_gpu_holds::register_vk_pending(lease.clone(),device.clone(),inputs.retirement_fence)?);
                }
                }
                let mut completed = [0u32; 2];
                let mut empty_plan = current_spatial_public_guide_pass_plan();
                empty_plan.requested_pass_count = 0;
                let mut aggregate = SpatialPublicGuidePassRecord::recorded(empty_plan, 0);
                // Initialize every actual attachment, including unavailable banks.
                // Render-pass final layout and clear/store make legal safe bindings
                // without partially-bound descriptor features or stale-frame bytes.
                for target in self
                    .targets
                    .iter()
                    .chain(state.peer.as_ref().unwrap().targets.iter())
                {
                    begin_guide_pass(
                        device,
                        command,
                        self.render_pass,
                        target.framebuffer,
                        self.extent,
                    );
                    device.cmd_end_render_pass(command);
                }
                for origin in 0..2 {
                    let Some(source) = inputs.sources[origin].take() else {
                        continue;
                    };
                    state.retained[origin] = Some(source.lease);
                    if inputs.demanded_prefixes[origin] == 0 {
                        continue;
                    }
                    if let Some(queue_family) = source.foreign_queue_family {
                        record_foreign_source_ownership(
                            device,
                            command,
                            source.packed_image.image,
                            queue_family,
                            true,
                        );
                    }
                    source.normalizer.record(
                        device,
                        command,
                        source.packed_image,
                        source.transition_source && source.foreign_queue_family.is_none(),
                    );
                    if let Some(queue_family) = source.foreign_queue_family {
                        record_foreign_source_ownership(
                            device,
                            command,
                            source.packed_image.image,
                            queue_family,
                            false,
                        );
                    }
                    let mut plan = current_spatial_public_guide_pass_plan();
                    plan.requested_pass_count = inputs.demanded_prefixes[origin];
                    plan.requested_downstream_effect_pass_count =
                        plan.requested_pass_count.saturating_sub(1);
                    // Reuse the exact scalar executor and pipelines; only the
                    // producer target/descriptor views are exchanged temporarily.
                    if origin == 1 {
                        state.peer.as_mut().unwrap().swap_into(self);
                    }
                    let recording = self.record_spatial_public_guide_passes_with_profiling(
                        device,
                        command,
                        timestamps,
                        slot,
                        source.descriptor_set,
                        elapsed,
                        source.reprojection,
                        source.overscan_uv,
                        plan,
                        origin == 0,
                    );
                    if origin == 1 {
                        state.peer.as_mut().unwrap().swap_into(self);
                    }
                    let recording = recording?;
                    if !recording.complete() {
                        return Err("stereo-producer-prefix-incomplete".into());
                    }
                    completed[origin] = recording.recorded_pass_count as u32;
                    state.recorded_keys[origin] = Some(source.key);
                    receipt.sources[origin] =
                        Some(crate::spatial_stereo_qualification::SourceFact {
                            identity: source.key.frame,
                            geometry_revision: source.key.geometry_revision,
                            config_revision: source.key.processing_revision,
                            prefix: completed[origin],
                            observed_at_ns: source.observed_at_ns,
                            content_serial: source.content_serial,
                            processing_codes: [
                                inputs.processing_policy.preblur_kernel as u32,
                                inputs.processing_policy.preblur_input as u32,
                                inputs.processing_policy.postblur_kernel as u32,
                                inputs.processing_policy.camera_sampling as u32,
                            ],
                        });
                    aggregate = recording;
                }
                // Every descriptor is legal even when prefix0 suppresses sampling.
                // Both banks own real image views; explicit layout transitions cover
                // untouched images too. They never borrow another origin's pixels.
                for target in &self.targets {
                    transition_guide_image_for_sampling(device, command, target.image);
                }
                for target in &state.peer.as_ref().unwrap().targets {
                    transition_guide_image_for_sampling(device, command, target.image);
                }
                let visibility = self
                    .targets
                    .iter()
                    .chain(state.peer.as_ref().unwrap().targets.iter())
                    .map(|target| {
                        vk::ImageMemoryBarrier::default()
                            .image(target.image)
                            .subresource_range(color_subresource_range())
                            .old_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                            .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                            .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                            .dst_access_mask(vk::AccessFlags::SHADER_READ)
                    })
                    .collect::<Vec<_>>();
                device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
                    vk::PipelineStageFlags::VERTEX_SHADER | vk::PipelineStageFlags::FRAGMENT_SHADER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &[],
                    &visibility,
                );
                for index in 0..5 {
                    let images = [
                        vk::DescriptorImageInfo::default()
                            .sampler(self.sampler)
                            .image_view(self.targets[index].image_view)
                            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL),
                        vk::DescriptorImageInfo::default()
                            .sampler(self.sampler)
                            .image_view(state.peer.as_ref().unwrap().targets[index].image_view)
                            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL),
                    ];
                    device.update_descriptor_sets(
                        &[vk::WriteDescriptorSet::default()
                            .dst_set(state.guides)
                            .dst_binding(abi::GUIDE_BINDINGS[index])
                            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                            .image_info(&images)],
                        &[],
                    );
                }
                inputs.policy.source_state =
                    [completed[0], completed[1], 1, inputs.policy.source_state[3]];
                let mapped = device
                    .map_memory(state.policy_memory, 0, 48, vk::MemoryMapFlags::empty())
                    .map_err(|e| format!("stereo-policy-map-{e:?}"))?;
                std::ptr::copy_nonoverlapping(
                    (&inputs.policy as *const StereoBankPolicyUniformV1).cast::<u8>(),
                    mapped.cast::<u8>(),
                    48,
                );
                device.unmap_memory(state.policy_memory);
                state.recorded_mask = receipt.mask;
                state.recorded_prefixes = completed;
                state.receipt_frame = Some((receipt.ordinal, receipt.surface));
                crate::spatial_stereo_qualification::record_frame(receipt);
                Ok(aggregate)
            },
        );
        if result.is_err() {
            // This function cannot enter submission. Only the registry's typed
            // Prepared phase permits cancellation; driver-entry uncertainty is
            // never converted into a caller assertion of no GPU use.
            #[cfg(target_os = "android")]
            for token in &mut state.vk_hold_tokens {
                if let Some(value) = *token {
                    if matches!(
                        crate::own_packed_gpu_holds::cancel_vk_unsubmitted(value),
                        Ok(true)
                    ) {
                        *token = None;
                    }
                }
            }
            if state.vk_hold_tokens.iter().all(Option::is_none) {
                state.pending_fence = None;
                state.retained = [None, None];
                state.recorded_keys = [None, None];
            }
        }
        self.stereo_banks = Some(state);
        result
    }
}
unsafe fn record_foreign_source_ownership(
    device: &ash::Device,
    command: vk::CommandBuffer,
    image: vk::Image,
    queue_family: u32,
    acquire: bool,
) {
    let barrier = vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(color_subresource_range())
        .src_queue_family_index(if acquire {
            vk::QUEUE_FAMILY_FOREIGN_EXT
        } else {
            queue_family
        })
        .dst_queue_family_index(if acquire {
            queue_family
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
        command,
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

#[cfg(target_os = "android")]
fn validate_recording_inputs(inputs: &StereoRecordingInputs<'_>) -> Result<(), String> {
    if inputs.retirement_fence == vk::Fence::null() {
        return Err("stereo-retirement-fence-missing".into());
    }
    if inputs.policy.region_origins.iter().any(|&v| v > 1)
        || inputs.policy.guide_origins[..2].iter().any(|&v| v > 2)
        || crate::stereo_bank_mask_v1::validate([
            inputs.policy.guide_origins[2],
            inputs.policy.guide_origins[3],
            inputs.policy.source_state[3],
        ])
        .is_err()
    {
        return Err("stereo-policy-invalid".into());
    }
    if crate::stereo_bank_mask_v1::validate([
        inputs.policy.guide_origins[2],
        inputs.policy.guide_origins[3],
        inputs.policy.source_state[3],
    ]) == Ok(true)
        && inputs.demanded_prefixes != [6, 6]
    {
        return Err("mask-current-bank-demand-required".into());
    }
    for origin in 0..2 {
        if ![0, 1, 3, 4, 6].contains(&inputs.demanded_prefixes[origin]) {
            return Err("stereo-prefix-invalid".into());
        }
        if let Some(source) = &inputs.sources[origin] {
            if source.key.origin != origin
                || source.key.processing_revision == 0
                || source.key.frame.epoch.process_generation == 0
                || source.key.frame.epoch.source_generation == 0
                || source.key.processing_policy != inputs.processing_policy
                || source.descriptor_set == vk::DescriptorSet::null()
            {
                return Err("stereo-source-binding-invalid".into());
            }
        }
    }
    Ok(())
}
impl StereoBankResources {
    pub(super) fn mask_diagnostic_plan(
        &self,
        extent: vk::Extent2D,
    ) -> Option<crate::stereo_bank_mask_v1::DiagnosticDrawPlan> {
        if self.pending_fence.is_none() || self.recorded_keys.iter().any(Option::is_none) {
            return None;
        }
        crate::stereo_bank_mask_v1::DiagnosticDrawPlan::for_frame(
            self.recorded_mask,
            self.recorded_prefixes,
            [extent.width, extent.height],
        )
    }
    pub(super) unsafe fn bind(
        &self,
        device: &ash::Device,
        command: vk::CommandBuffer,
        depth: vk::DescriptorSet,
        rgb: vk::DescriptorSet,
        video: vk::DescriptorSet,
    ) {
        device.cmd_bind_descriptor_sets(
            command,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipeline_layout,
            1,
            &[self.guides, depth, rgb, video, self.policy_set],
            &[],
        );
    }
    pub(super) unsafe fn destroy(mut self, device: &ash::Device) {
        SOURCE_BANKS_ACTIVE.store(false, Ordering::Release);
        if self.pending_fence.is_some() {
            crate::spatial_stereo_qualification::carrier_cleanup(false);
            return;
        } // physical retirement required
        if let Some(bank) = self.peer.take() {
            bank.destroy(device);
        }
        for pipeline in [self.pipeline, self.displacement_pipeline] {
            if pipeline != vk::Pipeline::null() {
                device.destroy_pipeline(pipeline, None);
            }
        }
        if self.pipeline_layout != vk::PipelineLayout::null() {
            device.destroy_pipeline_layout(self.pipeline_layout, None);
        }
        if self.pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.pool, None);
        }
        if self.policy_buffer != vk::Buffer::null() {
            device.destroy_buffer(self.policy_buffer, None);
        }
        if self.policy_memory != vk::DeviceMemory::null() {
            device.free_memory(self.policy_memory, None);
        }
        for layout in [self.guides_layout, self.policy_layout, self.empty_layout] {
            if layout != vk::DescriptorSetLayout::null() {
                device.destroy_descriptor_set_layout(layout, None);
            }
        }
    }
}
