//! Native Vulkan compute/projection path for the bright interference stimulus volume.

use std::{ffi::CString, mem};

use ash::vk;

use crate::native_renderer_options::{
    NativeStimulusVolumePatternFamily, NativeStimulusVolumeSettings,
};

const STIMULUS_LAYERS: u32 = 2;
const STIMULUS_LOCAL_SIZE_X: u32 = 8;
const STIMULUS_LOCAL_SIZE_Y: u32 = 8;
const STIMULUS_ACTUAL_FORMAT: vk::Format = vk::Format::R8G8B8A8_UNORM;

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct GpuStimulusVolumeFrameStats {
    pub(crate) ready: bool,
    pub(crate) visible: bool,
    pub(crate) dispatch_count: u64,
    pub(crate) randomize_count: u64,
    pub(crate) render_width: u32,
    pub(crate) render_height: u32,
    pub(crate) layers: u32,
    pub(crate) requested_format: &'static str,
    pub(crate) actual_format: &'static str,
    pub(crate) resolution_tier: &'static str,
    pub(crate) central_fov_fraction: f32,
    pub(crate) gradient_smoothing: f32,
    pub(crate) temporal_frequency_hz: f32,
    pub(crate) oscillator_hz: [f32; 3],
    pub(crate) spatial_frequency_scale: f32,
    pub(crate) source_shift: [f32; 2],
    pub(crate) noise_scale: f32,
    pub(crate) depth_warp: f32,
    pub(crate) pattern_family: NativeStimulusVolumePatternFamily,
    pub(crate) mirror_mode: u32,
    pub(crate) twist: f32,
    pub(crate) pinch: f32,
    pub(crate) scramble: f32,
    pub(crate) jumble: f32,
    pub(crate) stretch: [f32; 2],
    pub(crate) phase: f32,
    pub(crate) cpu_upload_bytes: u64,
    pub(crate) gpu_buffers_resident: bool,
    pub(crate) safety_acknowledged: bool,
}

impl GpuStimulusVolumeFrameStats {
    pub(crate) fn unavailable(settings: NativeStimulusVolumeSettings) -> Self {
        let extent = settings.render_target.extent();
        Self {
            render_width: extent[0],
            render_height: extent[1],
            layers: STIMULUS_LAYERS,
            requested_format: settings.render_target.marker_value(),
            actual_format: "none",
            resolution_tier: settings.render_target.resolution_tier_marker(),
            central_fov_fraction: settings.central_fov_fraction,
            gradient_smoothing: settings.gradient_smoothing,
            safety_acknowledged: settings.safety_acknowledged,
            ..Default::default()
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "stimulusVolumeReady={} stimulusVolumeVisible={} stimulusVolumeDispatchCount={} stimulusVolumeRandomizeCount={} stimulusVolumeImageSize={}x{} stimulusVolumeLayerCount={} stimulusVolumeRequestedRenderTarget={} stimulusVolumeFormat={} stimulusVolumeFormatFallback={} stimulusVolumeResolutionTier={} stimulusVolumeCentralFovFraction={:.2} stimulusVolumeGradientSmoothing={:.2} stimulusVolumePatternFamily={} stimulusVolumeMirrorMode={} stimulusVolumeTwist={:.3} stimulusVolumePinch={:.3} stimulusVolumeScramble={:.3} stimulusVolumeJumble={:.3} stimulusVolumeStretch={:.3},{:.3} stimulusVolumeTemporalFrequencyHz={:.3} stimulusVolumeSpatialOscillatorHz={:.3},{:.3},{:.3} stimulusVolumeSpatialFrequencyScale={:.3} stimulusVolumeSpatialSourceShift={:.3},{:.3} stimulusVolumeSpatialNoiseScale={:.3} stimulusVolumeDepthWarp={:.3} stimulusVolumePhase={:.3} stimulusVolumeCpuUploadBytes={} stimulusVolumeGpuBuffersResident={} stimulusVolumeBufferMemory=device-local stimulusVolumeExpandedVolumeUploadPerFrame=false stimulusVolumeProjectionPath=central-fov-stereo-sampled-storage-image stimulusSafetyAcknowledged={}",
            self.ready,
            self.visible,
            self.dispatch_count,
            self.randomize_count,
            self.render_width,
            self.render_height,
            self.layers,
            self.requested_format,
            self.actual_format,
            format_fallback_marker(self.requested_format),
            self.resolution_tier,
            self.central_fov_fraction,
            self.gradient_smoothing,
            self.pattern_family.marker_value(),
            mirror_mode_marker(self.mirror_mode),
            self.twist,
            self.pinch,
            self.scramble,
            self.jumble,
            self.stretch[0],
            self.stretch[1],
            self.temporal_frequency_hz,
            self.oscillator_hz[0],
            self.oscillator_hz[1],
            self.oscillator_hz[2],
            self.spatial_frequency_scale,
            self.source_shift[0],
            self.source_shift[1],
            self.noise_scale,
            self.depth_warp,
            self.phase,
            self.cpu_upload_bytes,
            self.gpu_buffers_resident,
            self.safety_acknowledged,
        )
    }
}

pub(crate) struct GpuStimulusVolumeRenderer {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    compute_pipeline: vk::Pipeline,
    graphics_pipeline: vk::Pipeline,
    stereo_image: OwnedImage,
    sampler: vk::Sampler,
    uniform_buffer: OwnedBuffer,
    image_layout: vk::ImageLayout,
    dispatch_count: u64,
    randomize_count: u64,
    last_reported_randomize_count: u64,
    random_seed: u32,
    temporal_frequency_hz: f32,
    oscillator_hz: [f32; 3],
    spatial_frequency_scale: f32,
    source_shift: [f32; 2],
    noise_scale: f32,
    depth_warp: f32,
    pattern_family: NativeStimulusVolumePatternFamily,
    mirror_mode: u32,
    twist: f32,
    pinch: f32,
    scramble: f32,
    jumble: f32,
    stretch: [f32; 2],
    phase_offsets: [f32; 3],
}

impl GpuStimulusVolumeRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        settings: NativeStimulusVolumeSettings,
    ) -> Result<Self, String> {
        if !settings.enabled {
            return Err("stimulus volume renderer requires enabled settings".to_string());
        }

        let stereo_image =
            OwnedImage::new(device, memory_properties, settings.render_target.extent())?;
        let sampler = match device.create_sampler(
            &vk::SamplerCreateInfo::default()
                .mag_filter(vk::Filter::LINEAR)
                .min_filter(vk::Filter::LINEAR)
                .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .border_color(vk::BorderColor::FLOAT_OPAQUE_BLACK),
            None,
        ) {
            Ok(sampler) => sampler,
            Err(error) => {
                stereo_image.destroy(device);
                return Err(format!("create stimulus volume sampler: {error}"));
            }
        };
        let uniform_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            mem::size_of::<StimulusVolumeUniforms>() as vk::DeviceSize,
            vk::BufferUsageFlags::UNIFORM_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "stimulus volume profile uniform",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                device.destroy_sampler(sampler, None);
                stereo_image.destroy(device);
                return Err(error);
            }
        };
        uniform_buffer.write(device, &StimulusVolumeUniforms::from_settings(settings))?;

        let bindings = [
            vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            vk::DescriptorSetLayoutBinding::default()
                .binding(1)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT),
            vk::DescriptorSetLayoutBinding::default()
                .binding(2)
                .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
        ];
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                uniform_buffer.destroy(device);
                device.destroy_sampler(sampler, None);
                stereo_image.destroy(device);
                return Err(format!("create stimulus descriptor layout: {error}"));
            }
        };
        let pool_sizes = [
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::STORAGE_IMAGE)
                .descriptor_count(1),
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1),
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::UNIFORM_BUFFER)
                .descriptor_count(1),
        ];
        let descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(1),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                uniform_buffer.destroy(device);
                device.destroy_sampler(sampler, None);
                stereo_image.destroy(device);
                return Err(format!("create stimulus descriptor pool: {error}"));
            }
        };
        let descriptor_set = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&[descriptor_set_layout]),
        ) {
            Ok(mut sets) => sets.remove(0),
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                uniform_buffer.destroy(device);
                device.destroy_sampler(sampler, None);
                stereo_image.destroy(device);
                return Err(format!("allocate stimulus descriptor set: {error}"));
            }
        };
        update_descriptors(
            device,
            descriptor_set,
            sampler,
            stereo_image.view,
            uniform_buffer.descriptor(),
        );

        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(mem::size_of::<StimulusVolumePush>() as u32)];
        let pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&[descriptor_set_layout])
                .push_constant_ranges(&push_ranges),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                uniform_buffer.destroy(device);
                device.destroy_sampler(sampler, None);
                stereo_image.destroy(device);
                return Err(format!("create stimulus pipeline layout: {error}"));
            }
        };
        let compute_pipeline = match create_compute_pipeline(device, pipeline_layout) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                uniform_buffer.destroy(device);
                device.destroy_sampler(sampler, None);
                stereo_image.destroy(device);
                return Err(error);
            }
        };
        let graphics_pipeline = match create_graphics_pipeline(device, render_pass, pipeline_layout)
        {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(compute_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                uniform_buffer.destroy(device);
                device.destroy_sampler(sampler, None);
                stereo_image.destroy(device);
                return Err(error);
            }
        };

        crate::marker(
            "stimulus-volume",
            format!(
                "status=created {} stimulusVolumeImageSize={}x{} stimulusVolumeLayerCount={} stimulusVolumeFormat={} stimulusVolumeRequestedRenderTarget={} stimulusVolumeGpuBuffersResident=true stimulusVolumeCpuUploadBytes={} stimulusVolumeUniformBufferBytes={} stimulusVolumeUniformBufferMemory={} stimulusVolumeImageMemory={} stimulusVolumeExpandedVolumeUploadPerFrame=false",
                settings.marker_fields(),
                stereo_image.width,
                stereo_image.height,
                STIMULUS_LAYERS,
                actual_format_marker(),
                settings.render_target.marker_value(),
                mem::size_of::<StimulusVolumeUniforms>(),
                uniform_buffer.bytes,
                uniform_buffer.memory_marker(),
                stereo_image.memory_marker(),
            ),
        );

        let startup_dynamics = settings.startup_dynamics;
        crate::marker(
            "stimulus-volume",
            format!(
                "status=startup-dynamics source={} {}",
                crate::native_renderer_stimulus_volume_options::NativeStimulusVolumeStartupDynamics::SOURCE_MARKER,
                startup_dynamics.marker_fields(),
            ),
        );

        Ok(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_set,
            pipeline_layout,
            compute_pipeline,
            graphics_pipeline,
            stereo_image,
            sampler,
            uniform_buffer,
            image_layout: vk::ImageLayout::UNDEFINED,
            dispatch_count: 0,
            randomize_count: 0,
            last_reported_randomize_count: 0,
            random_seed: 0x51A7_5EED,
            temporal_frequency_hz: startup_dynamics.temporal_frequency_hz,
            oscillator_hz: startup_dynamics.oscillator_hz,
            spatial_frequency_scale: startup_dynamics.spatial_frequency_scale,
            source_shift: startup_dynamics.source_shift,
            noise_scale: startup_dynamics.noise_scale,
            depth_warp: startup_dynamics.depth_warp,
            pattern_family: startup_dynamics.pattern_family,
            mirror_mode: startup_dynamics.mirror_mode,
            twist: startup_dynamics.twist,
            pinch: startup_dynamics.pinch,
            scramble: startup_dynamics.scramble,
            jumble: startup_dynamics.jumble,
            stretch: startup_dynamics.stretch,
            phase_offsets: startup_dynamics.phase_offsets,
        })
    }

    pub(crate) fn randomize(&mut self, settings: NativeStimulusVolumeSettings, frame_count: u64) {
        if !settings.enabled || !settings.randomize_enabled {
            crate::marker(
                "stimulus-volume-input",
                format!(
                    "event=right-primary-randomize status=ignored frame={} stimulusVolumeEnabled={} stimulusRandomizeEnabled={} stimulusVolumeRandomizeCount={}",
                    frame_count, settings.enabled, settings.randomize_enabled, self.randomize_count
                ),
            );
            return;
        }
        let frequency01 = next_unit_float(&mut self.random_seed);
        let oscillator01 = next_unit_float(&mut self.random_seed);
        let oscillator11 = next_unit_float(&mut self.random_seed);
        let oscillator21 = next_unit_float(&mut self.random_seed);
        let spatial_scale01 = next_unit_float(&mut self.random_seed);
        let source_x01 = next_unit_float(&mut self.random_seed);
        let source_y01 = next_unit_float(&mut self.random_seed);
        let noise01 = next_unit_float(&mut self.random_seed);
        let depth_warp01 = next_unit_float(&mut self.random_seed);
        let family01 = next_unit_float(&mut self.random_seed);
        let mirror01 = next_unit_float(&mut self.random_seed);
        let twist01 = next_unit_float(&mut self.random_seed);
        let pinch01 = next_unit_float(&mut self.random_seed);
        let scramble01 = next_unit_float(&mut self.random_seed);
        let jumble01 = next_unit_float(&mut self.random_seed);
        let stretch01 = next_unit_float(&mut self.random_seed);
        let stretch11 = next_unit_float(&mut self.random_seed);
        let phase01 = next_unit_float(&mut self.random_seed);
        let phase11 = next_unit_float(&mut self.random_seed);
        let phase21 = next_unit_float(&mut self.random_seed);
        let frequency_span = settings.randomize_max_hz - settings.randomize_min_hz;
        self.temporal_frequency_hz = settings.randomize_min_hz + frequency01 * frequency_span;
        self.oscillator_hz = [
            settings.randomize_min_hz + oscillator01 * frequency_span,
            settings.randomize_min_hz + oscillator11 * frequency_span,
            settings.randomize_min_hz + oscillator21 * frequency_span,
        ];
        self.spatial_frequency_scale = 0.70 + spatial_scale01 * 0.95;
        self.source_shift = [
            (source_x01 * 2.0 - 1.0) * 0.24,
            (source_y01 * 2.0 - 1.0) * 0.18,
        ];
        self.noise_scale = 3.2 + noise01 * 4.8;
        self.depth_warp = 0.02 + depth_warp01 * 0.12;
        self.pattern_family = if settings.pattern_family.randomizes_family() {
            NativeStimulusVolumePatternFamily::from_random_unit(family01)
        } else {
            settings.pattern_family.runtime_initial_family()
        };
        self.mirror_mode = (mirror01 * 6.0).floor().min(5.0) as u32;
        self.twist = (twist01 * 2.0 - 1.0) * 1.35;
        self.pinch = (pinch01 * 2.0 - 1.0) * 0.70;
        self.scramble = scramble01 * 0.34;
        self.jumble = jumble01 * 0.30;
        self.stretch = [0.72 + stretch01 * 0.72, 0.72 + stretch11 * 0.72];
        self.phase_offsets = [
            phase01 * std::f32::consts::TAU,
            phase11 * std::f32::consts::TAU,
            phase21 * std::f32::consts::TAU,
        ];
        self.randomize_count = self.randomize_count.saturating_add(1);
        crate::marker(
            "stimulus-volume-input",
            format!(
                "event=right-primary-randomize status=applied frame={} stimulusVolumeRandomizeCount={} stimulusVolumeRandomizeMode=trevor-vocabulary-temporal-spatial stimulusVolumePatternFamily={} stimulusVolumeMirrorMode={} stimulusVolumeTwist={:.3} stimulusVolumePinch={:.3} stimulusVolumeScramble={:.3} stimulusVolumeJumble={:.3} stimulusVolumeStretch={:.3},{:.3} stimulusVolumeTemporalFrequencyHz={:.3} stimulusVolumeSpatialOscillatorHz={:.3},{:.3},{:.3} randomizeHzRange={:.3}-{:.3} stimulusVolumeSpatialFrequencyScale={:.3} stimulusVolumeSpatialSourceShift={:.3},{:.3} stimulusVolumeSpatialNoiseScale={:.3} stimulusVolumeDepthWarp={:.3} stimulusVolumePhaseOffsets={:.3},{:.3},{:.3}",
                frame_count,
                self.randomize_count,
                self.pattern_family.marker_value(),
                mirror_mode_marker(self.mirror_mode),
                self.twist,
                self.pinch,
                self.scramble,
                self.jumble,
                self.stretch[0],
                self.stretch[1],
                self.temporal_frequency_hz,
                self.oscillator_hz[0],
                self.oscillator_hz[1],
                self.oscillator_hz[2],
                settings.randomize_min_hz,
                settings.randomize_max_hz,
                self.spatial_frequency_scale,
                self.source_shift[0],
                self.source_shift[1],
                self.noise_scale,
                self.depth_warp,
                self.phase_offsets[0],
                self.phase_offsets[1],
                self.phase_offsets[2],
            ),
        );
    }

    pub(crate) unsafe fn apply_live_settings(
        &mut self,
        device: &ash::Device,
        settings: NativeStimulusVolumeSettings,
        frame_count: u64,
        candidate_revision: i64,
    ) -> Result<(), String> {
        self.uniform_buffer
            .write(device, &StimulusVolumeUniforms::from_settings(settings))?;
        let dynamics = settings.startup_dynamics;
        self.temporal_frequency_hz = dynamics.temporal_frequency_hz;
        self.oscillator_hz = dynamics.oscillator_hz;
        self.spatial_frequency_scale = dynamics.spatial_frequency_scale;
        self.source_shift = dynamics.source_shift;
        self.noise_scale = dynamics.noise_scale;
        self.depth_warp = dynamics.depth_warp;
        self.pattern_family = dynamics.pattern_family.runtime_initial_family();
        self.mirror_mode = dynamics.mirror_mode;
        self.twist = dynamics.twist;
        self.pinch = dynamics.pinch;
        self.scramble = dynamics.scramble;
        self.jumble = dynamics.jumble;
        self.stretch = dynamics.stretch;
        self.phase_offsets = dynamics.phase_offsets;
        crate::marker(
            "stimulus-volume",
            format!(
                "status=live-applied transport=jni-live-queue frame={} candidateRevision={} stimulusVolumeUniformBufferBytes={} {}",
                frame_count,
                candidate_revision,
                self.uniform_buffer.bytes,
                dynamics.marker_fields(),
            ),
        );
        Ok(())
    }

    pub(crate) unsafe fn record_compute_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        settings: NativeStimulusVolumeSettings,
        frame_count: u64,
    ) -> GpuStimulusVolumeFrameStats {
        if !settings.enabled {
            return GpuStimulusVolumeFrameStats::unavailable(settings);
        }
        let time_seconds = frame_count as f32 / 72.0;
        let phase = time_seconds * self.temporal_frequency_hz * std::f32::consts::TAU;
        let push = StimulusVolumePush {
            params0: [
                time_seconds,
                frame_count as f32,
                self.spatial_frequency_scale,
                self.temporal_frequency_hz,
            ],
            params1: [
                self.phase_offsets[0],
                self.phase_offsets[1],
                self.phase_offsets[2],
                if settings.active() { 1.0 } else { 0.0 },
            ],
            params2: [
                self.oscillator_hz[0],
                self.oscillator_hz[1],
                phase,
                self.oscillator_hz[2],
            ],
            params3: [
                self.source_shift[0],
                self.source_shift[1],
                self.noise_scale,
                self.depth_warp,
            ],
            params4: [
                self.pattern_family.shader_code(),
                self.mirror_mode as f32,
                self.twist,
                self.pinch,
            ],
            params5: [self.scramble, self.jumble, self.stretch[0], self.stretch[1]],
        };

        transition_image_to_compute_write(device, cmd, self.stereo_image.image, self.image_layout);
        self.image_layout = vk::ImageLayout::GENERAL;
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::COMPUTE, self.compute_pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.pipeline_layout,
            0,
            &[self.descriptor_set],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.pipeline_layout,
            vk::ShaderStageFlags::COMPUTE,
            0,
            as_bytes(&push),
        );
        device.cmd_dispatch(
            cmd,
            self.stereo_image.width.div_ceil(STIMULUS_LOCAL_SIZE_X),
            self.stereo_image.height.div_ceil(STIMULUS_LOCAL_SIZE_Y),
            STIMULUS_LAYERS,
        );
        transition_image_to_fragment_read(device, cmd, self.stereo_image.image);
        self.image_layout = vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL;
        self.dispatch_count = self.dispatch_count.saturating_add(1);

        let stats = self.stats(settings, phase);
        let randomize_count_changed = self.randomize_count != self.last_reported_randomize_count;
        if frame_count == 0 || frame_count % 120 == 0 || randomize_count_changed {
            crate::marker(
                "stimulus-volume",
                format!(
                    "status=frame frame={} {}",
                    frame_count,
                    stats.marker_fields()
                ),
            );
            self.last_reported_randomize_count = self.randomize_count;
        }
        stats
    }

    pub(crate) unsafe fn record_projection_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_index: usize,
        settings: NativeStimulusVolumeSettings,
    ) {
        if !settings.enabled {
            return;
        }
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
        let push = StimulusVolumePush {
            params0: [
                0.0,
                0.0,
                self.spatial_frequency_scale,
                self.temporal_frequency_hz,
            ],
            params1: [
                self.phase_offsets[0],
                self.phase_offsets[1],
                self.phase_offsets[2],
                if settings.active() { 1.0 } else { 0.0 },
            ],
            params2: [
                self.randomize_count as f32,
                eye_index as f32,
                0.0,
                settings.central_fov_fraction,
            ],
            params3: [
                self.source_shift[0],
                self.source_shift[1],
                self.noise_scale,
                self.depth_warp,
            ],
            params4: [
                self.pattern_family.shader_code(),
                self.mirror_mode as f32,
                self.twist,
                self.pinch,
            ],
            params5: [self.scramble, self.jumble, self.stretch[0], self.stretch[1]],
        };
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.graphics_pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipeline_layout,
            0,
            &[self.descriptor_set],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.pipeline_layout,
            vk::ShaderStageFlags::FRAGMENT,
            0,
            as_bytes(&push),
        );
        device.cmd_draw(cmd, 3, 1, 0, 0);
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.graphics_pipeline, None);
        device.destroy_pipeline(self.compute_pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        self.uniform_buffer.destroy(device);
        device.destroy_sampler(self.sampler, None);
        self.stereo_image.destroy(device);
    }

    fn stats(
        &self,
        settings: NativeStimulusVolumeSettings,
        phase: f32,
    ) -> GpuStimulusVolumeFrameStats {
        GpuStimulusVolumeFrameStats {
            ready: true,
            visible: settings.active(),
            dispatch_count: self.dispatch_count,
            randomize_count: self.randomize_count,
            render_width: self.stereo_image.width,
            render_height: self.stereo_image.height,
            layers: STIMULUS_LAYERS,
            requested_format: settings.render_target.marker_value(),
            actual_format: actual_format_marker(),
            resolution_tier: settings.render_target.resolution_tier_marker(),
            central_fov_fraction: settings.central_fov_fraction,
            gradient_smoothing: settings.gradient_smoothing,
            temporal_frequency_hz: self.temporal_frequency_hz,
            oscillator_hz: self.oscillator_hz,
            spatial_frequency_scale: self.spatial_frequency_scale,
            source_shift: self.source_shift,
            noise_scale: self.noise_scale,
            depth_warp: self.depth_warp,
            pattern_family: self.pattern_family,
            mirror_mode: self.mirror_mode,
            twist: self.twist,
            pinch: self.pinch,
            scramble: self.scramble,
            jumble: self.jumble,
            stretch: self.stretch,
            phase,
            cpu_upload_bytes: mem::size_of::<StimulusVolumeUniforms>() as u64,
            gpu_buffers_resident: true,
            safety_acknowledged: settings.safety_acknowledged,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct StimulusVolumeUniforms {
    source_a: [f32; 4],
    source_b: [f32; 4],
    oscillators: [f32; 4],
    depth_params: [f32; 4],
    color_near: [f32; 4],
    color_mid: [f32; 4],
    color_far: [f32; 4],
    quality_params: [f32; 4],
}

impl StimulusVolumeUniforms {
    fn from_settings(settings: NativeStimulusVolumeSettings) -> Self {
        Self {
            source_a: [-0.30, -0.06, 0.96, 0.42],
            source_b: [0.30, 0.06, 0.72, settings.emission_gain()],
            oscillators: [9.25, 13.5, 10.75, settings.black_threshold()],
            depth_params: [
                settings.depth_color_mix(),
                settings.depth_contrast(),
                settings.raymarch_samples as f32,
                settings.render_target.requested_format_code(),
            ],
            color_near: [0.02, 0.92, 1.00, 1.0],
            color_mid: [1.00, 0.02, 0.78, 1.0],
            color_far: [1.00, 0.96, 0.04, 1.0],
            quality_params: [
                settings.central_fov_fraction,
                settings.gradient_smoothing,
                settings.render_target.extent()[0] as f32,
                settings.render_target.extent()[1] as f32,
            ],
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct StimulusVolumePush {
    params0: [f32; 4],
    params1: [f32; 4],
    params2: [f32; 4],
    params3: [f32; 4],
    params4: [f32; 4],
    params5: [f32; 4],
}

struct OwnedImage {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    memory_flags: vk::MemoryPropertyFlags,
    width: u32,
    height: u32,
}

impl OwnedImage {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        extent: [u32; 2],
    ) -> Result<Self, String> {
        let image = device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(STIMULUS_ACTUAL_FORMAT)
                    .extent(vk::Extent3D {
                        width: extent[0],
                        height: extent[1],
                        depth: 1,
                    })
                    .mip_levels(1)
                    .array_layers(STIMULUS_LAYERS)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(vk::ImageUsageFlags::STORAGE | vk::ImageUsageFlags::SAMPLED)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .initial_layout(vk::ImageLayout::UNDEFINED),
                None,
            )
            .map_err(|error| format!("create stimulus stereo image: {error}"))?;
        let requirements = device.get_image_memory_requirements(image);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
            "stimulus stereo image",
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
                return Err(format!("allocate stimulus stereo image memory: {error}"));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("bind stimulus stereo image memory: {error}"));
        }
        let view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D_ARRAY)
                .format(STIMULUS_ACTUAL_FORMAT)
                .subresource_range(color_subresource_range()),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!("create stimulus stereo image view: {error}"));
            }
        };
        Ok(Self {
            image,
            memory,
            view,
            memory_flags: memory_properties.memory_types[memory_type_index as usize].property_flags,
            width: extent[0],
            height: extent[1],
        })
    }

    fn memory_marker(&self) -> &'static str {
        if self
            .memory_flags
            .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
        {
            "device-local"
        } else {
            "not-device-local"
        }
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

struct OwnedBuffer {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    bytes: vk::DeviceSize,
    memory_flags: vk::MemoryPropertyFlags,
}

impl OwnedBuffer {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        bytes: vk::DeviceSize,
        usage: vk::BufferUsageFlags,
        required: vk::MemoryPropertyFlags,
        label: &str,
    ) -> Result<Self, String> {
        let buffer = device
            .create_buffer(
                &vk::BufferCreateInfo::default()
                    .size(bytes)
                    .usage(usage)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(|error| format!("create {label} buffer: {error}"))?;
        let requirements = device.get_buffer_memory_requirements(buffer);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            required,
            label,
        ) {
            Ok(index) => index,
            Err(error) => {
                device.destroy_buffer(buffer, None);
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
                return Err(format!("allocate {label} memory: {error}"));
            }
        };
        if let Err(error) = device.bind_buffer_memory(buffer, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            return Err(format!("bind {label} memory: {error}"));
        }
        Ok(Self {
            buffer,
            memory,
            bytes,
            memory_flags: memory_properties.memory_types[memory_type_index as usize].property_flags,
        })
    }

    unsafe fn write<T>(&self, device: &ash::Device, value: &T) -> Result<(), String> {
        let mapped = device
            .map_memory(self.memory, 0, self.bytes, vk::MemoryMapFlags::empty())
            .map_err(|error| format!("map stimulus uniform buffer: {error}"))?
            .cast::<T>();
        mapped.copy_from_nonoverlapping(value, 1);
        device.unmap_memory(self.memory);
        Ok(())
    }

    fn descriptor(&self) -> vk::DescriptorBufferInfo {
        vk::DescriptorBufferInfo::default()
            .buffer(self.buffer)
            .offset(0)
            .range(self.bytes)
    }

    fn memory_marker(&self) -> &'static str {
        if self
            .memory_flags
            .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
        {
            "device-local"
        } else if self
            .memory_flags
            .contains(vk::MemoryPropertyFlags::HOST_VISIBLE)
        {
            "host-visible-low-rate-uniform"
        } else {
            "not-device-local"
        }
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_buffer(self.buffer, None);
        device.free_memory(self.memory, None);
    }
}

unsafe fn update_descriptors(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    sampler: vk::Sampler,
    image_view: vk::ImageView,
    uniform_buffer: vk::DescriptorBufferInfo,
) {
    let storage_image = [vk::DescriptorImageInfo::default()
        .image_view(image_view)
        .image_layout(vk::ImageLayout::GENERAL)];
    let sampled_image = [vk::DescriptorImageInfo::default()
        .sampler(sampler)
        .image_view(image_view)
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let uniform = [uniform_buffer];
    let writes = [
        vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
            .image_info(&storage_image),
        vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(1)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&sampled_image),
        vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(2)
            .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
            .buffer_info(&uniform),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

unsafe fn create_compute_pipeline(
    device: &ash::Device,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let compute_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/stimulus_volume_raymarch.comp.spv"
    )))?;
    let compute_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&compute_words),
            None,
        )
        .map_err(|error| format!("create stimulus volume compute shader: {error}"))?;
    let entry = CString::new("main").expect("static shader entry point is valid");
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(compute_module)
        .name(&entry);
    let create_info = [vk::ComputePipelineCreateInfo::default()
        .stage(stage)
        .layout(pipeline_layout)];
    let result = device.create_compute_pipelines(vk::PipelineCache::null(), &create_info, None);
    device.destroy_shader_module(compute_module, None);
    result
        .map(|mut pipelines| pipelines.remove(0))
        .map_err(|(_, error)| format!("create stimulus volume compute pipeline: {error}"))
}

unsafe fn create_graphics_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/stimulus_volume_projection.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/stimulus_volume_projection.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create stimulus projection vertex shader: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create stimulus projection fragment shader: {error}"
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
    let result = device.create_graphics_pipelines(vk::PipelineCache::null(), &create_info, None);
    device.destroy_shader_module(fragment_module, None);
    device.destroy_shader_module(vertex_module, None);
    result
        .map(|mut pipelines| pipelines.remove(0))
        .map_err(|(_, error)| format!("create stimulus projection graphics pipeline: {error}"))
}

unsafe fn transition_image_to_compute_write(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    image: vk::Image,
    old_layout: vk::ImageLayout,
) {
    let (src_stage, src_access) = if old_layout == vk::ImageLayout::UNDEFINED {
        (
            vk::PipelineStageFlags::TOP_OF_PIPE,
            vk::AccessFlags::empty(),
        )
    } else {
        (
            vk::PipelineStageFlags::FRAGMENT_SHADER,
            vk::AccessFlags::SHADER_READ,
        )
    };
    let barrier = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(color_subresource_range())
        .old_layout(old_layout)
        .new_layout(vk::ImageLayout::GENERAL)
        .src_access_mask(src_access)
        .dst_access_mask(vk::AccessFlags::SHADER_WRITE)];
    device.cmd_pipeline_barrier(
        cmd,
        src_stage,
        vk::PipelineStageFlags::COMPUTE_SHADER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &barrier,
    );
}

unsafe fn transition_image_to_fragment_read(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    image: vk::Image,
) {
    let barrier = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(color_subresource_range())
        .old_layout(vk::ImageLayout::GENERAL)
        .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)];
    device.cmd_pipeline_barrier(
        cmd,
        vk::PipelineStageFlags::COMPUTE_SHADER,
        vk::PipelineStageFlags::FRAGMENT_SHADER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &barrier,
    );
}

fn color_subresource_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: STIMULUS_LAYERS,
    }
}

fn find_memory_type(
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    memory_type_bits: u32,
    required: vk::MemoryPropertyFlags,
    label: &str,
) -> Result<u32, String> {
    for index in 0..memory_properties.memory_type_count {
        let supported = (memory_type_bits & (1 << index)) != 0;
        let flags = memory_properties.memory_types[index as usize].property_flags;
        if supported && flags.contains(required) {
            return Ok(index);
        }
    }
    Err(format!(
        "no Vulkan memory type supports {required:?} for {label}"
    ))
}

fn actual_format_marker() -> &'static str {
    "VK_FORMAT_R8G8B8A8_UNORM"
}

fn format_fallback_marker(requested_format: &str) -> &'static str {
    if requested_format.ends_with("rgba16f") {
        "rgba16f-requested-rgba8-unorm-storage"
    } else {
        "none"
    }
}

fn mirror_mode_marker(mode: u32) -> &'static str {
    match mode {
        1 => "mirror-x",
        2 => "mirror-y",
        3 => "mirror-xy",
        4 => "radial-wedge",
        5 => "grid-fold",
        _ => "none",
    }
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

fn as_bytes<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) }
}

fn lcg(seed: u32) -> u32 {
    seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223)
}

fn next_unit_float(seed: &mut u32) -> f32 {
    *seed = lcg(*seed);
    unit_float(*seed)
}

fn unit_float(seed: u32) -> f32 {
    ((seed >> 8) as f32) / 16_777_215.0
}
