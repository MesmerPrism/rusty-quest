//! Generic private particle payload slot for downstream GPU-resident effects.

use std::{ffi::CString, mem};

use ash::vk;

use crate::gpu_hand_mesh_visual::HandMeshVisualEyeProjection;
use crate::native_renderer_properties::{
    PROP_PRIVATE_PARTICLES_COLOR_FACING_ATTENUATION_STRENGTH,
    PROP_PRIVATE_PARTICLES_TRACER_COPIES_PER_SECOND,
    PROP_PRIVATE_PARTICLES_TRACER_DRAW_SLOTS_PER_OSCILLATOR,
    PROP_PRIVATE_PARTICLES_TRACER_LIFETIME_SECONDS,
    PROP_PRIVATE_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
    PROP_PRIVATE_PARTICLES_TRANSPARENCY_OPACITY,
    PROP_PRIVATE_PARTICLES_TRANSPARENCY_OUTPUT_ALPHA_SCALE,
    PROP_PRIVATE_PARTICLES_TRANSPARENCY_RGB_ALPHA_COUPLING, PROP_PRIVATE_PARTICLES_VISUAL_SCALE,
};
use crate::native_renderer_property_values::{f32_clamped_value, u32_value};
use crate::native_renderer_timing::{GpuTimestampStage, GpuTimestampTracker};

include!(concat!(
    env!("OUT_DIR"),
    "/private_particle_payload_config.rs"
));

const PARTICLE_VERTICES_PER_INSTANCE: u32 = 6;
const PARTICLE_COMPUTE_LOCAL_SIZE: u32 = 64;
const PARTICLE_SORT_LOCAL_SIZE: u32 = 128;
const PARTICLE_SORT_ROW_BYTES: vk::DeviceSize = 16;
const PARTICLE_OUTPUT_ROWS_PER_INSTANCE: usize = 4;
const PARTICLE_STATE_ROWS_PER_INSTANCE: usize = 2;
const PARTICLE_DESCRIPTOR_SET_COUNT: usize = 2;
const PRIVATE_PARTICLE_SETTINGS_POLL_INTERVAL_FRAMES: u64 = 30;
const PRIVATE_PARTICLE_ORDERING_BACK_TO_FRONT: u32 = 0;
const PRIVATE_PARTICLE_ORDERING_SOURCE_ORDER: u32 = 1;

#[derive(Clone, Copy, Debug, PartialEq)]
struct PrivateParticleRuntimeSettings {
    visual_scale: f32,
    visual_parameter_source: &'static str,
    tracer_draw_slots_per_oscillator: u32,
    tracer_lifetime_seconds: f32,
    tracer_copies_per_second: f32,
    tracer_parameter_source: &'static str,
    transparency_opacity: f32,
    transparency_output_alpha_scale: f32,
    transparency_depth_suppression_strength: f32,
    transparency_rgb_alpha_coupling: f32,
    transparency_parameter_source: &'static str,
    color_facing_attenuation_strength: f32,
    color_parameter_source: &'static str,
}

impl PrivateParticleRuntimeSettings {
    fn from_generated_defaults() -> Self {
        Self {
            visual_scale: PRIVATE_PARTICLE_VISUAL_SCALE.clamp(0.05, 1.0),
            visual_parameter_source: PRIVATE_PARTICLE_VISUAL_PARAMETER_SOURCE,
            tracer_draw_slots_per_oscillator: PRIVATE_PARTICLE_TRACER_DRAW_SLOTS_PER_OSCILLATOR
                .min(u32::MAX as usize) as u32,
            tracer_lifetime_seconds: PRIVATE_PARTICLE_TRACER_LIFETIME_SECONDS.clamp(0.016, 30.0),
            tracer_copies_per_second: PRIVATE_PARTICLE_TRACER_COPIES_PER_SECOND.clamp(0.0, 120.0),
            tracer_parameter_source: PRIVATE_PARTICLE_TRACER_PARAMETER_SOURCE,
            transparency_opacity: PRIVATE_PARTICLE_TRANSPARENCY_OPACITY.clamp(0.0, 4.0),
            transparency_output_alpha_scale: PRIVATE_PARTICLE_TRANSPARENCY_OUTPUT_ALPHA_SCALE
                .clamp(0.0, 4.0),
            transparency_depth_suppression_strength:
                PRIVATE_PARTICLE_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH.clamp(0.0, 8.0),
            transparency_rgb_alpha_coupling: PRIVATE_PARTICLE_TRANSPARENCY_RGB_ALPHA_COUPLING
                .clamp(0.0, 1.0),
            transparency_parameter_source: PRIVATE_PARTICLE_TRANSPARENCY_PARAMETER_SOURCE,
            color_facing_attenuation_strength: PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION_STRENGTH
                .clamp(0.0, 1.0),
            color_parameter_source: PRIVATE_PARTICLE_COLOR_PARAMETER_SOURCE,
        }
    }

    fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let (visual_scale, visual_overridden) = f32_hotload_value(
            &mut lookup,
            PROP_PRIVATE_PARTICLES_VISUAL_SCALE,
            PRIVATE_PARTICLE_VISUAL_SCALE,
            0.05,
            1.0,
        );
        let (tracer_draw_slots_per_oscillator, tracer_draw_overridden) = u32_hotload_value(
            &mut lookup,
            PROP_PRIVATE_PARTICLES_TRACER_DRAW_SLOTS_PER_OSCILLATOR,
            PRIVATE_PARTICLE_TRACER_DRAW_SLOTS_PER_OSCILLATOR.min(u32::MAX as usize) as u32,
            0,
            1024,
        );
        let (tracer_lifetime_seconds, tracer_lifetime_overridden) = f32_hotload_value(
            &mut lookup,
            PROP_PRIVATE_PARTICLES_TRACER_LIFETIME_SECONDS,
            PRIVATE_PARTICLE_TRACER_LIFETIME_SECONDS,
            0.016,
            30.0,
        );
        let (tracer_copies_per_second, tracer_copies_overridden) = f32_hotload_value(
            &mut lookup,
            PROP_PRIVATE_PARTICLES_TRACER_COPIES_PER_SECOND,
            PRIVATE_PARTICLE_TRACER_COPIES_PER_SECOND,
            0.0,
            120.0,
        );
        let (transparency_opacity, transparency_opacity_overridden) = f32_hotload_value(
            &mut lookup,
            PROP_PRIVATE_PARTICLES_TRANSPARENCY_OPACITY,
            PRIVATE_PARTICLE_TRANSPARENCY_OPACITY,
            0.0,
            4.0,
        );
        let (transparency_output_alpha_scale, transparency_output_alpha_overridden) =
            f32_hotload_value(
                &mut lookup,
                PROP_PRIVATE_PARTICLES_TRANSPARENCY_OUTPUT_ALPHA_SCALE,
                PRIVATE_PARTICLE_TRANSPARENCY_OUTPUT_ALPHA_SCALE,
                0.0,
                4.0,
            );
        let (transparency_depth_suppression_strength, transparency_depth_suppression_overridden) =
            f32_hotload_value(
                &mut lookup,
                PROP_PRIVATE_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
                PRIVATE_PARTICLE_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
                0.0,
                8.0,
            );
        let (transparency_rgb_alpha_coupling, transparency_rgb_alpha_overridden) =
            f32_hotload_value(
                &mut lookup,
                PROP_PRIVATE_PARTICLES_TRANSPARENCY_RGB_ALPHA_COUPLING,
                PRIVATE_PARTICLE_TRANSPARENCY_RGB_ALPHA_COUPLING,
                0.0,
                1.0,
            );
        let (color_facing_attenuation_strength, color_facing_overridden) = f32_hotload_value(
            &mut lookup,
            PROP_PRIVATE_PARTICLES_COLOR_FACING_ATTENUATION_STRENGTH,
            PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION_STRENGTH,
            0.0,
            1.0,
        );
        let tracer_overridden =
            tracer_draw_overridden || tracer_lifetime_overridden || tracer_copies_overridden;
        let transparency_overridden = transparency_opacity_overridden
            || transparency_output_alpha_overridden
            || transparency_depth_suppression_overridden
            || transparency_rgb_alpha_overridden;
        Self {
            visual_scale,
            visual_parameter_source: if visual_overridden {
                "runtime-hotload-android-property"
            } else {
                PRIVATE_PARTICLE_VISUAL_PARAMETER_SOURCE
            },
            tracer_draw_slots_per_oscillator,
            tracer_lifetime_seconds,
            tracer_copies_per_second,
            tracer_parameter_source: if tracer_overridden {
                "runtime-hotload-android-property"
            } else {
                PRIVATE_PARTICLE_TRACER_PARAMETER_SOURCE
            },
            transparency_opacity,
            transparency_output_alpha_scale,
            transparency_depth_suppression_strength,
            transparency_rgb_alpha_coupling,
            transparency_parameter_source: if transparency_overridden {
                "runtime-hotload-android-property"
            } else {
                PRIVATE_PARTICLE_TRANSPARENCY_PARAMETER_SOURCE
            },
            color_facing_attenuation_strength,
            color_parameter_source: if color_facing_overridden {
                "runtime-hotload-android-property"
            } else {
                PRIVATE_PARTICLE_COLOR_PARAMETER_SOURCE
            },
        }
    }

    #[cfg(target_os = "android")]
    fn load_from_android_properties() -> Self {
        Self::from_property_lookup(android_property)
    }

    #[cfg(not(target_os = "android"))]
    fn load_from_android_properties() -> Self {
        Self::from_generated_defaults()
    }
}

fn f32_hotload_value(
    lookup: &mut impl FnMut(&str) -> Option<String>,
    property_name: &str,
    default_value: f32,
    min_value: f32,
    max_value: f32,
) -> (f32, bool) {
    let value = lookup(property_name);
    let overridden = value.is_some();
    (
        f32_clamped_value(value, default_value, min_value, max_value),
        overridden,
    )
}

fn u32_hotload_value(
    lookup: &mut impl FnMut(&str) -> Option<String>,
    property_name: &str,
    default_value: u32,
    min_value: u32,
    max_value: u32,
) -> (u32, bool) {
    let value = lookup(property_name);
    let overridden = value.is_some();
    (
        u32_value(value, default_value, min_value, max_value),
        overridden,
    )
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct GpuPrivateParticleFrameStats {
    pub(crate) ready: bool,
    pub(crate) visible: bool,
    pub(crate) particle_count: u32,
    pub(crate) main_particle_count: u32,
    pub(crate) tracer_max_count: u32,
    pub(crate) tracer_draw_count: u32,
    pub(crate) tracer_draw_slots_per_oscillator: u32,
    pub(crate) draw_count: u32,
    pub(crate) state_ping_pong: bool,
    pub(crate) aux0_rows: u32,
    pub(crate) sort_active: bool,
    pub(crate) sort_input_count: u32,
    pub(crate) sort_count: u32,
    pub(crate) sort_capacity: u32,
    runtime_settings: PrivateParticleRuntimeSettings,
    tracer_draw_slots_capacity: u32,
}

impl GpuPrivateParticleFrameStats {
    pub(crate) fn unavailable() -> Self {
        Self::default()
    }

    fn marker_fields(self) -> String {
        format!(
            "privateParticleReady={} privateParticleVisible={} privateParticlePayloadLinked={} privateParticleKind={} privateParticleCount={} privateParticleMainCount={} privateParticleDrawCount={} privateParticleSettingsHotload=true privateParticleHotloadPollIntervalFrames={} privateParticleVisualScale={:.3} privateParticleVisualParameterSource={} privateParticleTracerMaxCount={} privateParticleTracerStateCapacity={} privateParticleTracerDrawSlotsCapacity={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawCount={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTracerStateRows={} privateParticleTracerRadiusPolicy=snapshot-source-radius privateParticleTracerOutputMode=merged-billboard-output privateParticleDrawBudgetIncludesTracers={} privateParticleTracerCpuUploadPerFrame=false privateParticleOutputAbi=four-vec4-billboard-rows privateParticleStatePingPong={} privateParticleAux0Rows={} privateParticleOrderingMode={} privateParticleOrderingImplementation={} privateParticleOrderingParameterSource={} privateParticleOrderingBasis=primary-eye-openxr-reference-space privateParticleSortActive={} privateParticleSortInputCount={} privateParticleSortCount={} privateParticleSortCapacity={} privateParticleOrderingCpuExpandedUploadPerFrame=false privateParticleMaskTextureLinked={} privateParticleMaskTextureMode={} privateParticleMaskTextureFormat=R8_UNORM privateParticleMaskTextureSize={}x{}x{} privateParticleMaskTextureBytes={} {} privateParticleCpuUploadBytes=0 privateParticleGpuBuffersResident={} privateParticleMaskTextureGpuResident={}",
            self.ready,
            self.visible,
            PRIVATE_PARTICLE_PAYLOAD_LINKED,
            crate::sanitize(PRIVATE_PARTICLE_KIND),
            self.particle_count,
            self.main_particle_count,
            self.draw_count,
            PRIVATE_PARTICLE_SETTINGS_POLL_INTERVAL_FRAMES,
            self.runtime_settings.visual_scale,
            crate::sanitize(self.runtime_settings.visual_parameter_source),
            self.tracer_max_count,
            self.tracer_max_count,
            self.tracer_draw_slots_capacity,
            self.tracer_draw_slots_per_oscillator,
            self.tracer_draw_count,
            self.runtime_settings.tracer_lifetime_seconds,
            self.runtime_settings.tracer_copies_per_second,
            crate::sanitize(self.runtime_settings.tracer_parameter_source),
            self.tracer_max_count * PARTICLE_STATE_ROWS_PER_INSTANCE as u32,
            self.tracer_draw_count > 0,
            self.state_ping_pong,
            self.aux0_rows,
            crate::sanitize(PRIVATE_PARTICLE_ORDERING_MODE),
            private_particle_ordering_implementation(),
            crate::sanitize(PRIVATE_PARTICLE_ORDERING_PARAMETER_SOURCE),
            self.sort_active,
            self.sort_input_count,
            self.sort_count,
            self.sort_capacity,
            PRIVATE_PARTICLE_MASK_TEXTURE_LINKED,
            crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MODE),
            PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
            PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
            PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
            PRIVATE_PARTICLE_MASK_TEXTURE_BYTES,
            private_particle_transparency_marker_fields(self.runtime_settings),
            self.ready,
            self.ready
        )
    }
}

impl Default for GpuPrivateParticleFrameStats {
    fn default() -> Self {
        Self {
            ready: false,
            visible: false,
            particle_count: 0,
            main_particle_count: 0,
            tracer_max_count: 0,
            tracer_draw_count: 0,
            tracer_draw_slots_per_oscillator: 0,
            draw_count: 0,
            state_ping_pong: false,
            aux0_rows: 0,
            sort_active: false,
            sort_input_count: 0,
            sort_count: 0,
            sort_capacity: 0,
            runtime_settings: PrivateParticleRuntimeSettings::from_generated_defaults(),
            tracer_draw_slots_capacity: 0,
        }
    }
}

pub(crate) struct GpuPrivateParticleRenderer {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_sets: [vk::DescriptorSet; PARTICLE_DESCRIPTOR_SET_COUNT],
    pipeline_layout: vk::PipelineLayout,
    compute_pipeline: vk::Pipeline,
    sort_pipeline: vk::Pipeline,
    graphics_pipeline: vk::Pipeline,
    position_buffer: OwnedBuffer,
    normal_buffer: OwnedBuffer,
    particle_output_buffer: OwnedBuffer,
    particle_sort_buffer: OwnedBuffer,
    effect_state_buffers: [OwnedBuffer; PARTICLE_DESCRIPTOR_SET_COUNT],
    aux0_buffer: OwnedBuffer,
    mask_texture: OwnedMaskTexture,
    particle_count: u32,
    tracer_max_count: u32,
    tracer_draw_slots_per_oscillator: u32,
    aux0_rows: u32,
    sort_input_count: u32,
    sort_capacity: u32,
    runtime_settings: PrivateParticleRuntimeSettings,
    runtime_settings_last_poll_frame: u64,
}

impl GpuPrivateParticleRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        queue: vk::Queue,
        command_pool: vk::CommandPool,
    ) -> Result<Option<Self>, String> {
        if !PRIVATE_PARTICLE_PAYLOAD_LINKED {
            crate::marker(
                "private-particle-slot",
                "status=unlinked privateParticlePayloadLinked=false privateParticlePublicAbiOnly=true privateParticleVisualAcceptance=not-applicable-public-noop",
            );
            return Ok(None);
        }

        let payload = PrivateParticlePayload::load()?;
        let particle_count = PRIVATE_PARTICLE_COUNT
            .min(u32::MAX as usize)
            .min(payload.positions.len()) as u32;
        if particle_count == 0 {
            return Err("generic private particle payload has zero particles".to_string());
        }
        let tracer_max_count =
            PRIVATE_PARTICLE_TRACER_MAX_COUNT.min((u32::MAX - particle_count) as usize) as u32;
        let tracer_state_slots_per_oscillator = tracer_max_count / particle_count;
        let tracer_draw_slots_per_oscillator = (PRIVATE_PARTICLE_TRACER_DRAW_SLOTS_PER_OSCILLATOR
            .min(u32::MAX as usize) as u32)
            .min(tracer_state_slots_per_oscillator);
        let tracer_draw_count = particle_count
            .checked_mul(tracer_draw_slots_per_oscillator)
            .ok_or_else(|| {
                "generic private particle tracer draw count overflowed u32".to_string()
            })?;
        let draw_count = particle_count
            .checked_add(tracer_draw_count)
            .ok_or_else(|| "generic private particle draw count overflowed u32".to_string())?;
        let sort_input_count = draw_count;
        let particle_output_rows = draw_count as usize * PARTICLE_OUTPUT_ROWS_PER_INSTANCE;
        let effect_state_rows = (particle_count as usize + tracer_max_count as usize)
            * PARTICLE_STATE_ROWS_PER_INSTANCE;
        let zero_particle_rows = vec![[0.0_f32; 4]; particle_output_rows];
        let zero_particle_state_rows = vec![[0.0_f32; 4]; effect_state_rows];
        let aux0_rows = payload.aux0.len().min(u32::MAX as usize) as u32;

        let position_buffer = OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "generic private particle positions",
            &payload.positions[..particle_count as usize],
        )?;
        let normal_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "generic private particle normals",
            &payload.normals[..particle_count as usize],
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                position_buffer.destroy(device);
                return Err(error);
            }
        };
        let particle_output_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
            "generic private particle output",
            &zero_particle_rows,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                normal_buffer.destroy(device);
                position_buffer.destroy(device);
                return Err(error);
            }
        };
        let effect_state_buffer_a = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
            "generic private particle effect state ping",
            &zero_particle_state_rows,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                particle_output_buffer.destroy(device);
                normal_buffer.destroy(device);
                position_buffer.destroy(device);
                return Err(error);
            }
        };
        let effect_state_buffer_b = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
            "generic private particle effect state pong",
            &zero_particle_state_rows,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                effect_state_buffer_a.destroy(device);
                particle_output_buffer.destroy(device);
                normal_buffer.destroy(device);
                position_buffer.destroy(device);
                return Err(error);
            }
        };
        let aux0_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "generic private particle aux0",
            &payload.aux0,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                effect_state_buffer_b.destroy(device);
                effect_state_buffer_a.destroy(device);
                particle_output_buffer.destroy(device);
                normal_buffer.destroy(device);
                position_buffer.destroy(device);
                return Err(error);
            }
        };
        let mask_texture = match OwnedMaskTexture::new_with_data(
            device,
            memory_properties,
            queue,
            command_pool,
            &payload.mask_texture,
        ) {
            Ok(texture) => texture,
            Err(error) => {
                destroy_buffers(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                );
                return Err(error);
            }
        };
        let sort_capacity = sort_input_count.max(1).next_power_of_two();
        let particle_sort_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            sort_capacity as vk::DeviceSize * PARTICLE_SORT_ROW_BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
            "generic private particle resident GPU sort remap",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                destroy_buffers_and_mask(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                );
                return Err(error);
            }
        };

        let bindings = [
            storage_binding(0, vk::ShaderStageFlags::COMPUTE),
            storage_binding(1, vk::ShaderStageFlags::COMPUTE),
            storage_binding(
                2,
                vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::VERTEX,
            ),
            storage_binding(3, vk::ShaderStageFlags::COMPUTE),
            storage_binding(4, vk::ShaderStageFlags::COMPUTE),
            storage_binding(5, vk::ShaderStageFlags::COMPUTE),
            sampled_image_binding(6, vk::ShaderStageFlags::FRAGMENT),
            storage_binding(
                7,
                vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::VERTEX,
            ),
        ];
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(format!(
                    "create generic private particle descriptor layout: {error}"
                ));
            }
        };
        let pool_sizes = [
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count((7 * PARTICLE_DESCRIPTOR_SET_COUNT) as u32),
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(PARTICLE_DESCRIPTOR_SET_COUNT as u32),
        ];
        let descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(PARTICLE_DESCRIPTOR_SET_COUNT as u32),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(format!(
                    "create generic private particle descriptor pool: {error}"
                ));
            }
        };
        let descriptor_set_layouts = [descriptor_set_layout; PARTICLE_DESCRIPTOR_SET_COUNT];
        let descriptor_sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&descriptor_set_layouts),
        ) {
            Ok(sets) if sets.len() == PARTICLE_DESCRIPTOR_SET_COUNT => [sets[0], sets[1]],
            Ok(sets) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(format!(
                    "allocate generic private particle descriptor sets: expected {}, got {}",
                    PARTICLE_DESCRIPTOR_SET_COUNT,
                    sets.len()
                ));
            }
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(format!(
                    "allocate generic private particle descriptor sets: {error}"
                ));
            }
        };
        update_descriptors(
            device,
            descriptor_sets[0],
            position_buffer.descriptor(),
            normal_buffer.descriptor(),
            particle_output_buffer.descriptor(),
            effect_state_buffer_a.descriptor(),
            effect_state_buffer_b.descriptor(),
            aux0_buffer.descriptor(),
            mask_texture.descriptor(),
            particle_sort_buffer.descriptor(),
        );
        update_descriptors(
            device,
            descriptor_sets[1],
            position_buffer.descriptor(),
            normal_buffer.descriptor(),
            particle_output_buffer.descriptor(),
            effect_state_buffer_b.descriptor(),
            effect_state_buffer_a.descriptor(),
            aux0_buffer.descriptor(),
            mask_texture.descriptor(),
            particle_sort_buffer.descriptor(),
        );

        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(
                vk::ShaderStageFlags::COMPUTE
                    | vk::ShaderStageFlags::VERTEX
                    | vk::ShaderStageFlags::FRAGMENT,
            )
            .offset(0)
            .size(mem::size_of::<PrivateParticlePush>() as u32)];
        let pipeline_set_layouts = [descriptor_set_layout];
        let pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&pipeline_set_layouts)
                .push_constant_ranges(&push_ranges),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(format!(
                    "create generic private particle pipeline layout: {error}"
                ));
            }
        };
        let compute_pipeline = match create_compute_pipeline(
            device,
            pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/private_particles.comp.spv")),
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(error);
            }
        };
        let sort_pipeline = match create_compute_pipeline(
            device,
            pipeline_layout,
            include_bytes!(concat!(env!("OUT_DIR"), "/private_particles_sort.comp.spv")),
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(compute_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(error);
            }
        };
        let graphics_pipeline = match create_graphics_pipeline(device, render_pass, pipeline_layout)
        {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(sort_pipeline, None);
                device.destroy_pipeline(compute_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(error);
            }
        };

        let runtime_settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        let sort_active = private_particle_sort_enabled();
        let marker_sort_input_count = if sort_active { sort_input_count } else { 0 };
        let marker_sort_count = if sort_active { sort_capacity } else { 0 };

        crate::marker(
            "private-particle-slot",
            format!(
                "status=linked privateParticlePayloadLinked=true privateParticleKind={} privateParticleImplementationPath={} privateParticleDataPath={} privateParticleCount={} privateParticleMainCount={} privateParticleDrawCount={} privateParticleVisualScale={:.3} privateParticleVisualParameterSource={} privateParticleTracerMaxCount={} privateParticleTracerStateCapacity={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawCount={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTracerStateRows={} privateParticleTracerRadiusPolicy=snapshot-source-radius privateParticleTracerOutputMode=merged-billboard-output privateParticleDrawBudgetIncludesTracers={} privateParticleTracerCpuUploadPerFrame=false privateParticleStaticPositionBytes={} privateParticleStaticNormalBytes={} privateParticleAux0Bytes={} privateParticleAux0Rows={} privateParticleStateBufferBytes={} privateParticleStatePingPong=true privateParticleOutputBufferBytes={} privateParticleOutputAbi=four-vec4-billboard-rows privateParticleOrderingMode={} privateParticleOrderingImplementation={} privateParticleOrderingParameterSource={} privateParticleOrderingBasis=primary-eye-openxr-reference-space privateParticleSortActive={} privateParticleSortInputCount={} privateParticleSortCount={} privateParticleSortCapacity={} privateParticleSortBufferBytes={} privateParticleOrderingCpuExpandedUploadPerFrame=false privateParticleMaskTextureLinked={} privateParticleMaskTextureMode={} privateParticleMaskTexturePath={} privateParticleMaskTextureFormat=R8_UNORM privateParticleMaskTextureSize={}x{}x{} privateParticleMaskTextureBytes={} privateParticleMaskTextureGpuResident=true privateParticleCpuUploadBytes=0 privateParticleGpuBuffersResident=true privateParticleVisualAcceptance=pending-headset-screenshot",
                crate::sanitize(PRIVATE_PARTICLE_KIND),
                crate::sanitize(PRIVATE_PARTICLE_IMPLEMENTATION_PATH),
                crate::sanitize(PRIVATE_PARTICLE_DATA_PATH),
                particle_count,
                particle_count,
                draw_count,
                PRIVATE_PARTICLE_VISUAL_SCALE,
                crate::sanitize(PRIVATE_PARTICLE_VISUAL_PARAMETER_SOURCE),
                tracer_max_count,
                tracer_max_count,
                tracer_draw_slots_per_oscillator,
                tracer_draw_count,
                PRIVATE_PARTICLE_TRACER_LIFETIME_SECONDS,
                PRIVATE_PARTICLE_TRACER_COPIES_PER_SECOND,
                crate::sanitize(PRIVATE_PARTICLE_TRACER_PARAMETER_SOURCE),
                tracer_max_count * PARTICLE_STATE_ROWS_PER_INSTANCE as u32,
                tracer_draw_count > 0,
                position_buffer.bytes,
                normal_buffer.bytes,
                aux0_buffer.bytes,
                aux0_rows,
                effect_state_buffer_a.bytes,
                particle_output_buffer.bytes,
                crate::sanitize(PRIVATE_PARTICLE_ORDERING_MODE),
                private_particle_ordering_implementation(),
                crate::sanitize(PRIVATE_PARTICLE_ORDERING_PARAMETER_SOURCE),
                sort_active,
                marker_sort_input_count,
                marker_sort_count,
                sort_capacity,
                particle_sort_buffer.bytes,
                PRIVATE_PARTICLE_MASK_TEXTURE_LINKED,
                crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MODE),
                crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_PATH),
                PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
                PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
                PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
                PRIVATE_PARTICLE_MASK_TEXTURE_BYTES,
            ),
        );
        log_private_marker(
            "created",
            0,
            particle_count,
            tracer_max_count,
            tracer_draw_count,
            tracer_draw_slots_per_oscillator,
            draw_count,
            aux0_rows,
            marker_sort_input_count,
            marker_sort_count,
            sort_capacity,
            tracer_draw_slots_per_oscillator,
            runtime_settings,
        );

        Ok(Some(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_sets,
            pipeline_layout,
            compute_pipeline,
            sort_pipeline,
            graphics_pipeline,
            position_buffer,
            normal_buffer,
            particle_output_buffer,
            particle_sort_buffer,
            effect_state_buffers: [effect_state_buffer_a, effect_state_buffer_b],
            aux0_buffer,
            mask_texture,
            particle_count,
            tracer_max_count,
            tracer_draw_slots_per_oscillator,
            aux0_rows,
            sort_input_count,
            sort_capacity,
            runtime_settings,
            runtime_settings_last_poll_frame: u64::MAX,
        }))
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        self.mask_texture.destroy(device);
        self.aux0_buffer.destroy(device);
        for buffer in &self.effect_state_buffers {
            buffer.destroy(device);
        }
        self.particle_sort_buffer.destroy(device);
        self.particle_output_buffer.destroy(device);
        self.normal_buffer.destroy(device);
        self.position_buffer.destroy(device);
        device.destroy_pipeline(self.graphics_pipeline, None);
        device.destroy_pipeline(self.sort_pipeline, None);
        device.destroy_pipeline(self.compute_pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
    }

    pub(crate) unsafe fn record_compute_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        gpu_timestamp_tracker: &GpuTimestampTracker,
        frame_slot: usize,
        eye_projection: HandMeshVisualEyeProjection,
        world_center_scale: [f32; 4],
        frame_count: u64,
    ) -> GpuPrivateParticleFrameStats {
        let runtime_settings = self.runtime_settings(frame_count);
        let tracer_draw_slots_per_oscillator = runtime_settings
            .tracer_draw_slots_per_oscillator
            .min(self.tracer_draw_slots_per_oscillator);
        let tracer_draw_count = self
            .particle_count
            .saturating_mul(tracer_draw_slots_per_oscillator);
        let draw_count = self.particle_count.saturating_add(tracer_draw_count);
        let descriptor_index = frame_count as usize & 1;
        let next_descriptor_index = (descriptor_index + 1) & 1;
        let push = private_particle_push(
            self.particle_count,
            draw_count,
            self.tracer_max_count,
            runtime_settings,
            eye_projection,
            world_center_scale,
            frame_count,
        );
        let compute_write_barrier = [
            storage_to_compute_read_barrier(&self.position_buffer),
            storage_to_compute_read_barrier(&self.normal_buffer),
            storage_to_compute_read_barrier(&self.aux0_buffer),
            storage_to_compute_read_barrier(&self.effect_state_buffers[descriptor_index]),
            shader_to_compute_write_barrier(&self.effect_state_buffers[next_descriptor_index]),
            shader_to_compute_write_barrier(&self.particle_output_buffer),
        ];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::HOST
                | vk::PipelineStageFlags::VERTEX_SHADER
                | vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &compute_write_barrier,
            &[],
        );
        gpu_timestamp_tracker.write_stage_start(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleCompute,
        );
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::COMPUTE, self.compute_pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.pipeline_layout,
            0,
            &[self.descriptor_sets[descriptor_index]],
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
            self.particle_count.div_ceil(PARTICLE_COMPUTE_LOCAL_SIZE),
            1,
            1,
        );
        let compute_to_sort = [compute_write_to_shader_read_barrier(
            &self.particle_output_buffer,
        )];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::VERTEX_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &compute_to_sort,
            &[],
        );
        gpu_timestamp_tracker.write_stage_end(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleCompute,
        );
        let sort_active = private_particle_sort_enabled();
        let sort_count = if sort_active {
            gpu_timestamp_tracker.write_stage_start(
                device,
                cmd,
                frame_slot,
                GpuTimestampStage::PrivateParticleSort,
            );
            let sort_count = self.record_sort_frame(device, cmd, eye_projection, draw_count);
            gpu_timestamp_tracker.write_stage_end(
                device,
                cmd,
                frame_slot,
                GpuTimestampStage::PrivateParticleSort,
            );
            sort_count
        } else {
            0
        };
        let stats = GpuPrivateParticleFrameStats {
            ready: true,
            visible: true,
            particle_count: self.particle_count,
            main_particle_count: self.particle_count,
            tracer_max_count: self.tracer_max_count,
            tracer_draw_count,
            tracer_draw_slots_per_oscillator,
            draw_count,
            state_ping_pong: true,
            aux0_rows: self.aux0_rows,
            sort_active,
            sort_input_count: if sort_active { draw_count } else { 0 },
            sort_count,
            sort_capacity: self.sort_capacity,
            runtime_settings,
            tracer_draw_slots_capacity: self.tracer_draw_slots_per_oscillator,
        };

        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "private-particle-slot",
                format!(
                    "status=compute frame={} {}",
                    frame_count,
                    stats.marker_fields()
                ),
            );
            log_private_marker(
                "running",
                frame_count,
                self.particle_count,
                self.tracer_max_count,
                tracer_draw_count,
                tracer_draw_slots_per_oscillator,
                draw_count,
                self.aux0_rows,
                draw_count,
                sort_count,
                self.sort_capacity,
                self.tracer_draw_slots_per_oscillator,
                runtime_settings,
            );
        }
        stats
    }

    fn runtime_settings(&mut self, frame_count: u64) -> PrivateParticleRuntimeSettings {
        let should_poll = self.runtime_settings_last_poll_frame == u64::MAX
            || frame_count.saturating_sub(self.runtime_settings_last_poll_frame)
                >= PRIVATE_PARTICLE_SETTINGS_POLL_INTERVAL_FRAMES;
        if should_poll {
            let next = PrivateParticleRuntimeSettings::load_from_android_properties();
            if next != self.runtime_settings {
                crate::marker(
                    "private-particle-slot",
                    format!(
                        "status=hotload-applied frame={} privateParticleSettingsHotload=true privateParticleVisualScale={:.3} privateParticleVisualParameterSource={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawSlotsCapacity={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTransparencyOpacity={:.3} privateParticleTransparencyOutputAlphaScale={:.3} privateParticleTransparencyDepthSuppressionStrength={:.3} privateParticleTransparencyRgbAlphaCoupling={:.3} privateParticleTransparencyParameterSource={} privateParticleColorFacingAttenuationStrength={:.3} privateParticleColorParameterSource={}",
                        frame_count,
                        next.visual_scale,
                        crate::sanitize(next.visual_parameter_source),
                        next.tracer_draw_slots_per_oscillator.min(self.tracer_draw_slots_per_oscillator),
                        self.tracer_draw_slots_per_oscillator,
                        next.tracer_lifetime_seconds,
                        next.tracer_copies_per_second,
                        crate::sanitize(next.tracer_parameter_source),
                        next.transparency_opacity,
                        next.transparency_output_alpha_scale,
                        next.transparency_depth_suppression_strength,
                        next.transparency_rgb_alpha_coupling,
                        crate::sanitize(next.transparency_parameter_source),
                        next.color_facing_attenuation_strength,
                        crate::sanitize(next.color_parameter_source)
                    ),
                );
            }
            self.runtime_settings = next;
            self.runtime_settings_last_poll_frame = frame_count;
        }
        self.runtime_settings
    }

    unsafe fn record_sort_frame(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        eye_projection: HandMeshVisualEyeProjection,
        sort_input_count: u32,
    ) -> u32 {
        let sort_input_count = sort_input_count.min(self.sort_capacity).max(1);
        let sort_count = sort_input_count.next_power_of_two().min(self.sort_capacity);
        let group_count = sort_count.div_ceil(PARTICLE_SORT_LOCAL_SIZE);
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::COMPUTE, self.sort_pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.pipeline_layout,
            0,
            &[self.descriptor_sets[0]],
            &[],
        );
        let eye_forward = rotate_by_quat(eye_projection.orientation_xyzw, [0.0, 0.0, -1.0]);
        let init_push = PrivateParticleSortPush {
            params0: [sort_input_count as f32, sort_count as f32, 0.0, 0.0],
            params1: [
                eye_projection.position[0],
                eye_projection.position[1],
                eye_projection.position[2],
                0.0,
            ],
            params2: [eye_forward[0], eye_forward[1], eye_forward[2], 0.0],
        };
        self.dispatch_sort_pass(device, cmd, &init_push, group_count);
        self.record_sort_barrier(device, cmd);

        let mut k = 2_u32;
        while k <= sort_count {
            let mut j = k / 2;
            while j > 0 {
                let sort_push = PrivateParticleSortPush {
                    params0: [sort_input_count as f32, sort_count as f32, 1.0, j as f32],
                    params1: [
                        eye_projection.position[0],
                        eye_projection.position[1],
                        eye_projection.position[2],
                        k as f32,
                    ],
                    params2: [eye_forward[0], eye_forward[1], eye_forward[2], 0.0],
                };
                self.dispatch_sort_pass(device, cmd, &sort_push, group_count);
                self.record_sort_barrier(device, cmd);
                j /= 2;
            }
            k *= 2;
        }

        let vertex_barrier = [compute_write_to_shader_read_barrier(
            &self.particle_sort_buffer,
        )];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::VERTEX_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &vertex_barrier,
            &[],
        );
        sort_count
    }

    unsafe fn dispatch_sort_pass(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        push: &PrivateParticleSortPush,
        group_count: u32,
    ) {
        device.cmd_push_constants(
            cmd,
            self.pipeline_layout,
            vk::ShaderStageFlags::COMPUTE,
            0,
            as_bytes(push),
        );
        device.cmd_dispatch(cmd, group_count, 1, 1);
    }

    unsafe fn record_sort_barrier(&self, device: &ash::Device, cmd: vk::CommandBuffer) {
        let barrier = [vk::BufferMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
            .buffer(self.particle_sort_buffer.buffer)
            .offset(0)
            .size(self.particle_sort_buffer.bytes)];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &barrier,
            &[],
        );
    }

    pub(crate) unsafe fn record_overlay_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_projection: HandMeshVisualEyeProjection,
        world_center_scale: [f32; 4],
        stats: &GpuPrivateParticleFrameStats,
    ) {
        if !stats.visible || stats.draw_count == 0 {
            return;
        }
        let push = private_particle_push(
            stats.particle_count,
            stats.draw_count,
            stats.tracer_max_count,
            stats.runtime_settings,
            eye_projection,
            world_center_scale,
            0,
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
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.graphics_pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipeline_layout,
            0,
            &[self.descriptor_sets[0]],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.pipeline_layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            as_bytes(&push),
        );
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_draw(cmd, PARTICLE_VERTICES_PER_INSTANCE, stats.draw_count, 0, 0);
    }
}

fn private_particle_push(
    particle_count: u32,
    draw_count: u32,
    tracer_max_count: u32,
    runtime_settings: PrivateParticleRuntimeSettings,
    eye_projection: HandMeshVisualEyeProjection,
    world_center_scale: [f32; 4],
    frame_count: u64,
) -> PrivateParticlePush {
    let frame = frame_count as f32;
    PrivateParticlePush {
        params0: [
            particle_count as f32,
            runtime_settings.visual_scale,
            private_particle_packed_mode_code(runtime_settings.color_facing_attenuation_strength),
            0.96,
        ],
        params1: [frame, 1.0 / 90.0, frame / 90.0, 5.8],
        transparency_params: [
            runtime_settings.transparency_opacity,
            runtime_settings.transparency_output_alpha_scale,
            runtime_settings.transparency_depth_suppression_strength,
            runtime_settings.transparency_rgb_alpha_coupling,
        ],
        tracer_params: [
            draw_count as f32,
            tracer_max_count as f32,
            runtime_settings.tracer_lifetime_seconds,
            runtime_settings.tracer_copies_per_second,
        ],
        world_center_scale,
        eye_position: eye_projection.position,
        eye_orientation_xyzw: eye_projection.orientation_xyzw,
        fov_tangents: eye_projection.fov_tangents,
    }
}

fn private_particle_transparency_marker_fields(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> String {
    format!(
        "privateParticleTransparencyBlendMode={} privateParticleTransparencyCompositionMode=parametric-rgb-alpha-coupling privateParticleTransparencyOpacity={:.3} privateParticleTransparencyOutputAlphaScale={:.3} privateParticleTransparencyDepthSuppressionStrength={:.3} privateParticleTransparencyRgbAlphaCoupling={:.3} privateParticleTransparencyParameterSource={} privateParticleColorFacingAttenuationStrength={:.3} privateParticleColorParameterSource={}",
        crate::sanitize(PRIVATE_PARTICLE_TRANSPARENCY_BLEND_MODE),
        runtime_settings.transparency_opacity,
        runtime_settings.transparency_output_alpha_scale,
        runtime_settings.transparency_depth_suppression_strength,
        runtime_settings.transparency_rgb_alpha_coupling,
        crate::sanitize(runtime_settings.transparency_parameter_source),
        runtime_settings.color_facing_attenuation_strength,
        crate::sanitize(runtime_settings.color_parameter_source)
    )
}

fn private_particle_ordering_implementation() -> &'static str {
    if !private_particle_sort_enabled() {
        "resident-gpu-direct-output-order-no-depth-sort"
    } else {
        "resident-gpu-full-index-remap"
    }
}

fn private_particle_sort_enabled() -> bool {
    match PRIVATE_PARTICLE_ORDERING_MODE_CODE {
        PRIVATE_PARTICLE_ORDERING_BACK_TO_FRONT => true,
        PRIVATE_PARTICLE_ORDERING_SOURCE_ORDER => false,
        _ => true,
    }
}

fn private_particle_packed_mode_code(color_facing_attenuation_strength: f32) -> f32 {
    // Keep the push constant block at 128 bytes: mask, ordering, and facing color
    // mode share params0.z as a small integer payload decoded by the draw shaders.
    let facing_quantized =
        (color_facing_attenuation_strength.clamp(0.0, 1.0) * 1000.0).round() as u32;
    (PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE
        + PRIVATE_PARTICLE_ORDERING_MODE_CODE * 10
        + facing_quantized * 100) as f32
}

fn log_private_marker(
    status: &str,
    frame_count: u64,
    particle_count: u32,
    tracer_max_count: u32,
    tracer_draw_count: u32,
    tracer_draw_slots_per_oscillator: u32,
    draw_count: u32,
    aux0_rows: u32,
    sort_input_count: u32,
    sort_count: u32,
    sort_capacity: u32,
    tracer_draw_slots_capacity: u32,
    runtime_settings: PrivateParticleRuntimeSettings,
) {
    let sort_active = private_particle_sort_enabled();
    crate::android_log(format!(
        "{} channel=frame status={} frame={} privateParticleKind={} privateParticleCount={} privateParticleMainCount={} privateParticleDrawCount={} privateParticleSettingsHotload=true privateParticleHotloadPollIntervalFrames={} privateParticleVisualScale={:.3} privateParticleVisualParameterSource={} privateParticleTracerMaxCount={} privateParticleTracerStateCapacity={} privateParticleTracerDrawSlotsCapacity={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawCount={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTracerStateRows={} privateParticleTracerRadiusPolicy=snapshot-source-radius privateParticleTracerOutputMode=merged-billboard-output privateParticleDrawBudgetIncludesTracers={} privateParticleTracerCpuUploadPerFrame=false privateParticleOutputAbi=four-vec4-billboard-rows privateParticleStatePingPong=true privateParticleAux0Rows={} privateParticleOrderingMode={} privateParticleOrderingImplementation={} privateParticleOrderingParameterSource={} privateParticleOrderingBasis=primary-eye-openxr-reference-space privateParticleSortActive={} privateParticleSortInputCount={} privateParticleSortCount={} privateParticleSortCapacity={} privateParticleOrderingCpuExpandedUploadPerFrame=false privateParticleMaskTextureLinked={} privateParticleMaskTextureMode={} privateParticleMaskTextureFormat=R8_UNORM privateParticleMaskTextureSize={}x{}x{} privateParticleMaskTextureBytes={} privateParticleMaskTextureGpuResident=true {} {}",
        PRIVATE_PARTICLE_MARKER_PREFIX,
        status,
        frame_count,
        crate::sanitize(PRIVATE_PARTICLE_KIND),
        particle_count,
        particle_count,
        draw_count,
        PRIVATE_PARTICLE_SETTINGS_POLL_INTERVAL_FRAMES,
        runtime_settings.visual_scale,
        crate::sanitize(runtime_settings.visual_parameter_source),
        tracer_max_count,
        tracer_max_count,
        tracer_draw_slots_capacity,
        tracer_draw_slots_per_oscillator,
        tracer_draw_count,
        runtime_settings.tracer_lifetime_seconds,
        runtime_settings.tracer_copies_per_second,
        crate::sanitize(runtime_settings.tracer_parameter_source),
        tracer_max_count * PARTICLE_STATE_ROWS_PER_INSTANCE as u32,
        tracer_draw_count > 0,
        aux0_rows,
        crate::sanitize(PRIVATE_PARTICLE_ORDERING_MODE),
        private_particle_ordering_implementation(),
        crate::sanitize(PRIVATE_PARTICLE_ORDERING_PARAMETER_SOURCE),
        sort_active,
        sort_input_count,
        sort_count,
        sort_capacity,
        PRIVATE_PARTICLE_MASK_TEXTURE_LINKED,
        crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MODE),
        PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
        PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
        PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
        PRIVATE_PARTICLE_MASK_TEXTURE_BYTES,
        private_particle_transparency_marker_fields(runtime_settings),
        PRIVATE_PARTICLE_MARKER_FIELDS,
    ));
    log_private_effect_marker_fields(status, frame_count);
}

fn log_private_effect_marker_fields(status: &str, frame_count: u64) {
    let fields = PRIVATE_PARTICLE_MARKER_FIELDS.trim();
    if fields.is_empty() {
        return;
    }

    const MAX_EFFECT_MARKER_FIELD_CHARS: usize = 2400;
    let mut chunk = String::new();
    let mut chunk_index = 0usize;
    for field in fields.split_whitespace() {
        let separator_len = if chunk.is_empty() { 0 } else { 1 };
        if !chunk.is_empty()
            && chunk.len() + separator_len + field.len() > MAX_EFFECT_MARKER_FIELD_CHARS
        {
            crate::android_log(format!(
                "{} channel=effect-marker status={} frame={} chunk={} {}",
                PRIVATE_PARTICLE_MARKER_PREFIX, status, frame_count, chunk_index, chunk
            ));
            chunk.clear();
            chunk_index += 1;
        }
        if !chunk.is_empty() {
            chunk.push(' ');
        }
        chunk.push_str(field);
    }
    if !chunk.is_empty() {
        crate::android_log(format!(
            "{} channel=effect-marker status={} frame={} chunk={} {}",
            PRIVATE_PARTICLE_MARKER_PREFIX, status, frame_count, chunk_index, chunk
        ));
    }
}

struct PrivateParticlePayload {
    positions: Vec<[f32; 4]>,
    normals: Vec<[f32; 4]>,
    aux0: Vec<[u32; 4]>,
    mask_texture: PrivateParticleMaskTexturePayload,
}

struct PrivateParticleMaskTexturePayload {
    pixels: &'static [u8],
    width: u32,
    height: u32,
    layers: u32,
}

impl PrivateParticlePayload {
    fn load() -> Result<Self, String> {
        let positions = parse_vec4_f32(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/private_particle_positions.f32.bin"
        )))?;
        let normals = parse_vec4_f32(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/private_particle_normals.f32.bin"
        )))?;
        let aux0 = parse_uvec4_u32(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/private_particle_aux0.u32.bin"
        )))?;
        let mask_texture = PrivateParticleMaskTexturePayload::load()?;
        if positions.len() != PRIVATE_PARTICLE_COUNT {
            return Err(format!(
                "generic private particle position payload has {} rows, expected {}",
                positions.len(),
                PRIVATE_PARTICLE_COUNT
            ));
        }
        if normals.len() != positions.len() {
            return Err(format!(
                "generic private particle normal payload has {} rows, expected {}",
                normals.len(),
                positions.len()
            ));
        }
        if aux0.len() != PRIVATE_PARTICLE_AUX0_VEC4_ROWS {
            return Err(format!(
                "generic private particle aux0 payload has {} rows, expected generated config row count {}",
                aux0.len(),
                PRIVATE_PARTICLE_AUX0_VEC4_ROWS
            ));
        }
        if aux0.len() < positions.len() * PARTICLE_STATE_ROWS_PER_INSTANCE {
            return Err(format!(
                "generic private particle aux0 payload has {} rows, expected at least {}",
                aux0.len(),
                positions.len() * PARTICLE_STATE_ROWS_PER_INSTANCE
            ));
        }
        Ok(Self {
            positions,
            normals,
            aux0,
            mask_texture,
        })
    }
}

impl PrivateParticleMaskTexturePayload {
    fn load() -> Result<Self, String> {
        let pixels = include_bytes!(concat!(
            env!("OUT_DIR"),
            "/private_particle_mask_texture.r8.bin"
        ));
        let expected = PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH as usize
            * PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT as usize
            * PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS as usize;
        if pixels.len() != expected {
            return Err(format!(
                "generic private particle mask texture has {} bytes, expected {} from {}x{}x{} R8",
                pixels.len(),
                expected,
                PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
                PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
                PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS
            ));
        }
        Ok(Self {
            pixels,
            width: PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
            height: PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
            layers: PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
        })
    }
}

fn parse_vec4_f32(bytes: &[u8]) -> Result<Vec<[f32; 4]>, String> {
    if bytes.len() % 16 != 0 {
        return Err("generic private particle f32 vec4 payload is not row-aligned".to_string());
    }
    Ok(bytes
        .chunks_exact(16)
        .map(|chunk| {
            [
                f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]),
                f32::from_le_bytes([chunk[4], chunk[5], chunk[6], chunk[7]]),
                f32::from_le_bytes([chunk[8], chunk[9], chunk[10], chunk[11]]),
                f32::from_le_bytes([chunk[12], chunk[13], chunk[14], chunk[15]]),
            ]
        })
        .collect())
}

fn parse_uvec4_u32(bytes: &[u8]) -> Result<Vec<[u32; 4]>, String> {
    if bytes.len() % 16 != 0 {
        return Err("generic private particle u32 uvec4 payload is not row-aligned".to_string());
    }
    Ok(bytes
        .chunks_exact(16)
        .map(|chunk| {
            [
                u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]),
                u32::from_le_bytes([chunk[4], chunk[5], chunk[6], chunk[7]]),
                u32::from_le_bytes([chunk[8], chunk[9], chunk[10], chunk[11]]),
                u32::from_le_bytes([chunk[12], chunk[13], chunk[14], chunk[15]]),
            ]
        })
        .collect())
}

unsafe fn create_compute_pipeline(
    device: &ash::Device,
    pipeline_layout: vk::PipelineLayout,
    spirv: &[u8],
) -> Result<vk::Pipeline, String> {
    let compute_words = spirv_words(spirv)?;
    let compute_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&compute_words),
            None,
        )
        .map_err(|error| format!("create generic private particle compute shader: {error}"))?;
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
        .map_err(|(_, error)| format!("create generic private particle compute pipeline: {error}"))
}

unsafe fn create_graphics_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/private_particles.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/private_particles.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create generic private particle vertex shader: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create generic private particle fragment shader: {error}"
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
    let color_blend_attachment = [particle_color_blend_attachment()];
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
        .map_err(|(_, error)| format!("create generic private particle graphics pipeline: {error}"))
}

fn particle_color_blend_attachment() -> vk::PipelineColorBlendAttachmentState {
    let (
        src_color_blend_factor,
        dst_color_blend_factor,
        src_alpha_blend_factor,
        dst_alpha_blend_factor,
    ) = match PRIVATE_PARTICLE_TRANSPARENCY_BLEND_MODE {
        "src-alpha-one-additive" => (
            vk::BlendFactor::SRC_ALPHA,
            vk::BlendFactor::ONE,
            vk::BlendFactor::SRC_ALPHA,
            vk::BlendFactor::ONE,
        ),
        "src-one-one-minus-src-alpha" => (
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE_MINUS_SRC_ALPHA,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE_MINUS_SRC_ALPHA,
        ),
        _ => (
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE_MINUS_SRC_ALPHA,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE_MINUS_SRC_ALPHA,
        ),
    };
    vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(src_color_blend_factor)
        .dst_color_blend_factor(dst_color_blend_factor)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(src_alpha_blend_factor)
        .dst_alpha_blend_factor(dst_alpha_blend_factor)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(vk::ColorComponentFlags::RGBA)
}

fn storage_binding(
    binding: u32,
    stage_flags: vk::ShaderStageFlags,
) -> vk::DescriptorSetLayoutBinding<'static> {
    vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(stage_flags)
}

fn sampled_image_binding(
    binding: u32,
    stage_flags: vk::ShaderStageFlags,
) -> vk::DescriptorSetLayoutBinding<'static> {
    vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(stage_flags)
}

unsafe fn update_descriptors(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    position_buffer: vk::DescriptorBufferInfo,
    normal_buffer: vk::DescriptorBufferInfo,
    particle_output_buffer: vk::DescriptorBufferInfo,
    effect_state_source_buffer: vk::DescriptorBufferInfo,
    effect_state_target_buffer: vk::DescriptorBufferInfo,
    aux0_buffer: vk::DescriptorBufferInfo,
    mask_texture: vk::DescriptorImageInfo,
    particle_sort_buffer: vk::DescriptorBufferInfo,
) {
    let position_info = [position_buffer];
    let normal_info = [normal_buffer];
    let particle_info = [particle_output_buffer];
    let effect_state_source_info = [effect_state_source_buffer];
    let effect_state_target_info = [effect_state_target_buffer];
    let aux0_info = [aux0_buffer];
    let mask_texture_info = [mask_texture];
    let sort_info = [particle_sort_buffer];
    let writes = [
        write_storage_descriptor(descriptor_set, 0, &position_info),
        write_storage_descriptor(descriptor_set, 1, &normal_info),
        write_storage_descriptor(descriptor_set, 2, &particle_info),
        write_storage_descriptor(descriptor_set, 3, &effect_state_source_info),
        write_storage_descriptor(descriptor_set, 4, &effect_state_target_info),
        write_storage_descriptor(descriptor_set, 5, &aux0_info),
        write_sampled_image_descriptor(descriptor_set, 6, &mask_texture_info),
        write_storage_descriptor(descriptor_set, 7, &sort_info),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

fn write_storage_descriptor<'a>(
    descriptor_set: vk::DescriptorSet,
    binding: u32,
    buffer_info: &'a [vk::DescriptorBufferInfo],
) -> vk::WriteDescriptorSet<'a> {
    vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(binding)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(buffer_info)
}

fn write_sampled_image_descriptor<'a>(
    descriptor_set: vk::DescriptorSet,
    binding: u32,
    image_info: &'a [vk::DescriptorImageInfo],
) -> vk::WriteDescriptorSet<'a> {
    vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(binding)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .image_info(image_info)
}

fn descriptor_info(buffer: vk::Buffer, bytes: vk::DeviceSize) -> vk::DescriptorBufferInfo {
    vk::DescriptorBufferInfo::default()
        .buffer(buffer)
        .offset(0)
        .range(bytes)
}

fn storage_to_compute_read_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::HOST_WRITE | vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn shader_to_compute_write_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_WRITE)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn compute_write_to_shader_read_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

struct OwnedBuffer {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    bytes: vk::DeviceSize,
}

impl OwnedBuffer {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        bytes: vk::DeviceSize,
        usage: vk::BufferUsageFlags,
        required_memory_flags: vk::MemoryPropertyFlags,
        label: &str,
    ) -> Result<Self, String> {
        if bytes == 0 {
            return Err(format!("{label} buffer requires nonzero size"));
        }
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
            required_memory_flags,
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
        })
    }

    unsafe fn new_with_data<T: Copy>(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        usage: vk::BufferUsageFlags,
        required_memory_flags: vk::MemoryPropertyFlags,
        label: &str,
        data: &[T],
    ) -> Result<Self, String> {
        let bytes = mem::size_of_val(data) as vk::DeviceSize;
        if bytes == 0 {
            return Err(format!("{label} buffer requires nonzero size"));
        }
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
            required_memory_flags,
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
        if required_memory_flags.contains(vk::MemoryPropertyFlags::HOST_VISIBLE) {
            let mapped = match device.map_memory(memory, 0, bytes, vk::MemoryMapFlags::empty()) {
                Ok(mapped) => mapped.cast::<T>(),
                Err(error) => {
                    device.free_memory(memory, None);
                    device.destroy_buffer(buffer, None);
                    return Err(format!("map {label} buffer: {error}"));
                }
            };
            mapped.copy_from_nonoverlapping(data.as_ptr(), data.len());
            device.unmap_memory(memory);
        }
        Ok(Self {
            buffer,
            memory,
            bytes,
        })
    }

    fn descriptor(&self) -> vk::DescriptorBufferInfo {
        descriptor_info(self.buffer, self.bytes)
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        if self.buffer != vk::Buffer::null() {
            device.destroy_buffer(self.buffer, None);
        }
        if self.memory != vk::DeviceMemory::null() {
            device.free_memory(self.memory, None);
        }
    }
}

struct OwnedMaskTexture {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    sampler: vk::Sampler,
}

impl OwnedMaskTexture {
    unsafe fn new_with_data(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        queue: vk::Queue,
        command_pool: vk::CommandPool,
        payload: &PrivateParticleMaskTexturePayload,
    ) -> Result<Self, String> {
        let staging = OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::TRANSFER_SRC,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "generic private particle mask texture staging",
            payload.pixels,
        )?;
        let image = match device.create_image(
            &vk::ImageCreateInfo::default()
                .image_type(vk::ImageType::TYPE_2D)
                .format(vk::Format::R8_UNORM)
                .extent(vk::Extent3D {
                    width: payload.width,
                    height: payload.height,
                    depth: 1,
                })
                .mip_levels(1)
                .array_layers(payload.layers)
                .samples(vk::SampleCountFlags::TYPE_1)
                .tiling(vk::ImageTiling::OPTIMAL)
                .usage(vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::SAMPLED)
                .sharing_mode(vk::SharingMode::EXCLUSIVE)
                .initial_layout(vk::ImageLayout::UNDEFINED),
            None,
        ) {
            Ok(image) => image,
            Err(error) => {
                staging.destroy(device);
                return Err(format!(
                    "create generic private particle mask texture image: {error}"
                ));
            }
        };
        let requirements = device.get_image_memory_requirements(image);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        ) {
            Ok(index) => index,
            Err(error) => {
                staging.destroy(device);
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
                staging.destroy(device);
                device.destroy_image(image, None);
                return Err(format!(
                    "allocate generic private particle mask texture memory: {error}"
                ));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            staging.destroy(device);
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!(
                "bind generic private particle mask texture memory: {error}"
            ));
        }
        if let Err(error) = upload_mask_texture_image(
            device,
            queue,
            command_pool,
            staging.buffer,
            image,
            payload.width,
            payload.height,
            payload.layers,
        ) {
            staging.destroy(device);
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(error);
        }
        staging.destroy(device);

        let view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D_ARRAY)
                .format(vk::Format::R8_UNORM)
                .subresource_range(mask_texture_subresource_range(payload.layers)),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!(
                    "create generic private particle mask texture view: {error}"
                ));
            }
        };
        let sampler = match device.create_sampler(
            &vk::SamplerCreateInfo::default()
                .mag_filter(vk::Filter::LINEAR)
                .min_filter(vk::Filter::LINEAR)
                .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .min_lod(0.0)
                .max_lod(0.0),
            None,
        ) {
            Ok(sampler) => sampler,
            Err(error) => {
                device.destroy_image_view(view, None);
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!(
                    "create generic private particle mask texture sampler: {error}"
                ));
            }
        };
        Ok(Self {
            image,
            memory,
            view,
            sampler,
        })
    }

    fn descriptor(&self) -> vk::DescriptorImageInfo {
        vk::DescriptorImageInfo::default()
            .sampler(self.sampler)
            .image_view(self.view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_sampler(self.sampler, None);
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

unsafe fn upload_mask_texture_image(
    device: &ash::Device,
    queue: vk::Queue,
    command_pool: vk::CommandPool,
    staging_buffer: vk::Buffer,
    image: vk::Image,
    width: u32,
    height: u32,
    layers: u32,
) -> Result<(), String> {
    let command_buffers = device
        .allocate_command_buffers(
            &vk::CommandBufferAllocateInfo::default()
                .command_pool(command_pool)
                .level(vk::CommandBufferLevel::PRIMARY)
                .command_buffer_count(1),
        )
        .map_err(|error| {
            format!("allocate generic private particle mask texture upload command: {error}")
        })?;
    let command_buffer = command_buffers[0];
    let begin_info =
        vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
    if let Err(error) = device.begin_command_buffer(command_buffer, &begin_info) {
        device.free_command_buffers(command_pool, &command_buffers);
        return Err(format!(
            "begin generic private particle mask texture upload command: {error}"
        ));
    }

    let to_transfer = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(mask_texture_subresource_range(layers))
        .old_layout(vk::ImageLayout::UNDEFINED)
        .new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .src_access_mask(vk::AccessFlags::empty())
        .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)];
    device.cmd_pipeline_barrier(
        command_buffer,
        vk::PipelineStageFlags::TOP_OF_PIPE,
        vk::PipelineStageFlags::TRANSFER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &to_transfer,
    );
    let copy_regions = [vk::BufferImageCopy::default()
        .buffer_offset(0)
        .buffer_row_length(0)
        .buffer_image_height(0)
        .image_subresource(vk::ImageSubresourceLayers {
            aspect_mask: vk::ImageAspectFlags::COLOR,
            mip_level: 0,
            base_array_layer: 0,
            layer_count: layers,
        })
        .image_offset(vk::Offset3D::default())
        .image_extent(vk::Extent3D {
            width,
            height,
            depth: 1,
        })];
    device.cmd_copy_buffer_to_image(
        command_buffer,
        staging_buffer,
        image,
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        &copy_regions,
    );
    let to_fragment = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(mask_texture_subresource_range(layers))
        .old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
        .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)];
    device.cmd_pipeline_barrier(
        command_buffer,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::FRAGMENT_SHADER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &to_fragment,
    );
    if let Err(error) = device.end_command_buffer(command_buffer) {
        device.free_command_buffers(command_pool, &command_buffers);
        return Err(format!(
            "end generic private particle mask texture upload command: {error}"
        ));
    }
    let submit_buffers = [command_buffer];
    let submit_info = [vk::SubmitInfo::default().command_buffers(&submit_buffers)];
    if let Err(error) = device.queue_submit(queue, &submit_info, vk::Fence::null()) {
        device.free_command_buffers(command_pool, &command_buffers);
        return Err(format!(
            "submit generic private particle mask texture upload command: {error}"
        ));
    }
    if let Err(error) = device.queue_wait_idle(queue) {
        device.free_command_buffers(command_pool, &command_buffers);
        return Err(format!(
            "wait generic private particle mask texture upload command: {error}"
        ));
    }
    device.free_command_buffers(command_pool, &command_buffers);
    Ok(())
}

fn mask_texture_subresource_range(layers: u32) -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: layers,
    }
}

unsafe fn destroy_buffers_and_mask(
    device: &ash::Device,
    position_buffer: &OwnedBuffer,
    normal_buffer: &OwnedBuffer,
    particle_output_buffer: &OwnedBuffer,
    effect_state_buffer_a: &OwnedBuffer,
    effect_state_buffer_b: &OwnedBuffer,
    aux0_buffer: &OwnedBuffer,
    mask_texture: &OwnedMaskTexture,
) {
    mask_texture.destroy(device);
    destroy_buffers(
        device,
        position_buffer,
        normal_buffer,
        particle_output_buffer,
        effect_state_buffer_a,
        effect_state_buffer_b,
        aux0_buffer,
    );
}

unsafe fn destroy_buffers_mask_and_sort(
    device: &ash::Device,
    position_buffer: &OwnedBuffer,
    normal_buffer: &OwnedBuffer,
    particle_output_buffer: &OwnedBuffer,
    effect_state_buffer_a: &OwnedBuffer,
    effect_state_buffer_b: &OwnedBuffer,
    aux0_buffer: &OwnedBuffer,
    mask_texture: &OwnedMaskTexture,
    particle_sort_buffer: &OwnedBuffer,
) {
    particle_sort_buffer.destroy(device);
    destroy_buffers_and_mask(
        device,
        position_buffer,
        normal_buffer,
        particle_output_buffer,
        effect_state_buffer_a,
        effect_state_buffer_b,
        aux0_buffer,
        mask_texture,
    );
}

unsafe fn destroy_buffers(
    device: &ash::Device,
    position_buffer: &OwnedBuffer,
    normal_buffer: &OwnedBuffer,
    particle_output_buffer: &OwnedBuffer,
    effect_state_buffer_a: &OwnedBuffer,
    effect_state_buffer_b: &OwnedBuffer,
    aux0_buffer: &OwnedBuffer,
) {
    aux0_buffer.destroy(device);
    effect_state_buffer_b.destroy(device);
    effect_state_buffer_a.destroy(device);
    particle_output_buffer.destroy(device);
    normal_buffer.destroy(device);
    position_buffer.destroy(device);
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
        "no Vulkan memory type supports {required:?} for generic private particle buffers"
    ))
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

fn rotate_by_quat(quat: [f32; 4], vector: [f32; 3]) -> [f32; 3] {
    let q = normalize_quat(quat);
    let uv = cross3([q[0], q[1], q[2]], vector);
    let uuv = cross3([q[0], q[1], q[2]], uv);
    [
        vector[0] + uv[0] * (2.0 * q[3]) + uuv[0] * 2.0,
        vector[1] + uv[1] * (2.0 * q[3]) + uuv[1] * 2.0,
        vector[2] + uv[2] * (2.0 * q[3]) + uuv[2] * 2.0,
    ]
}

fn normalize_quat(quat: [f32; 4]) -> [f32; 4] {
    let length_sq = dot4(quat, quat).max(0.000000000001);
    let inv_length = length_sq.sqrt().recip();
    [
        quat[0] * inv_length,
        quat[1] * inv_length,
        quat[2] * inv_length,
        quat[3] * inv_length,
    ]
}

fn dot4(a: [f32; 4], b: [f32; 4]) -> f32 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
}

fn cross3(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

#[repr(C)]
struct PrivateParticleSortPush {
    params0: [f32; 4],
    params1: [f32; 4],
    params2: [f32; 4],
}

#[repr(C)]
struct PrivateParticlePush {
    params0: [f32; 4],
    params1: [f32; 4],
    transparency_params: [f32; 4],
    tracer_params: [f32; 4],
    world_center_scale: [f32; 4],
    eye_position: [f32; 4],
    eye_orientation_xyzw: [f32; 4],
    fov_tangents: [f32; 4],
}
