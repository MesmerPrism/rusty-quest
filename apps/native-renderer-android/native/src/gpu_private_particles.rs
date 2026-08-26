//! Generic private particle payload slot for downstream GPU-resident effects.

use std::{ffi::CString, mem};

use ash::vk;

use crate::breath_composition_driver::{BreathCompositionDriver, BreathCompositionDriverSettings};
use crate::gpu_hand_mesh_visual::HandMeshVisualEyeProjection;
use crate::manifold_scalar_driver_bridge::{
    ManifoldScalarDriverBridge, ManifoldScalarDriverBridgeSettings,
};
use crate::native_controller_breath_state::NativeControllerBreathSample;
use crate::native_renderer_private_particle_heartbeat_orbit_request::{
    PrivateParticleHeartbeatOrbitRequestObservation, PrivateParticleHeartbeatOrbitRequestState,
};
use crate::native_renderer_private_particle_material_request::{
    PrivateParticleMaterialPreset, PrivateParticleMaterialRequestObservation,
    PrivateParticleMaterialRequestState,
};
use crate::native_renderer_private_particle_render_experiment_request::{
    PrivateParticleRenderExperimentPreset, PrivateParticleRenderExperimentRequestObservation,
    PrivateParticleRenderExperimentRequestState,
};
use crate::native_renderer_private_particle_visual_scale_request::{
    PrivateParticleVisualScaleRequestObservation, PrivateParticleVisualScaleRequestState,
};
use crate::native_renderer_properties::{
    PROP_PRIVATE_PARTICLES_COLOR_FACING_ATTENUATION_STRENGTH,
    PROP_PRIVATE_PARTICLES_DRIVER0_VALUE01, PROP_PRIVATE_PARTICLES_DRIVER1_VALUE01,
    PROP_PRIVATE_PARTICLES_DRIVER2_VALUE01, PROP_PRIVATE_PARTICLES_DRIVER3_VALUE01,
    PROP_PRIVATE_PARTICLES_DRIVER4_VALUE01, PROP_PRIVATE_PARTICLES_DRIVER5_VALUE01,
    PROP_PRIVATE_PARTICLES_DRIVER6_VALUE01, PROP_PRIVATE_PARTICLES_DRIVER7_VALUE01,
    PROP_PRIVATE_PARTICLES_HEARTBEAT_ORBIT_REQUEST_V1, PROP_PRIVATE_PARTICLES_MATERIAL_REQUEST_V1,
    PROP_PRIVATE_PARTICLES_OFFSCREEN_HALF_RES,
    PROP_PRIVATE_PARTICLES_OFFSCREEN_HALF_RES_TRACERS_ONLY,
    PROP_PRIVATE_PARTICLES_RENDER_EXPERIMENT_REQUEST_V1,
    PROP_PRIVATE_PARTICLES_TRACER_COPIES_PER_SECOND,
    PROP_PRIVATE_PARTICLES_TRACER_DRAW_SLOTS_PER_OSCILLATOR,
    PROP_PRIVATE_PARTICLES_TRACER_LIFETIME_SECONDS,
    PROP_PRIVATE_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
    PROP_PRIVATE_PARTICLES_TRANSPARENCY_OPACITY,
    PROP_PRIVATE_PARTICLES_TRANSPARENCY_OUTPUT_ALPHA_SCALE,
    PROP_PRIVATE_PARTICLES_TRANSPARENCY_RGB_ALPHA_COUPLING, PROP_PRIVATE_PARTICLES_VISUAL_SCALE,
    PROP_PRIVATE_PARTICLES_VISUAL_SCALE_REQUEST_V1,
};
use crate::native_renderer_property_values::{bool_value, f32_clamped_value, u32_value};
use crate::native_renderer_stimulus_panel::PrivateParticlePanelUpdateMask;
use crate::native_renderer_timing::{GpuTimestampStage, GpuTimestampTracker};
use crate::private_particle_breath_state_driver::{
    PrivateParticleBreathStateDriver, PrivateParticleBreathStateDriverSettings,
};
use crate::private_particle_heartbeat_pulse_adapter::{
    PrivateParticleHeartbeatPulseAdapter, PrivateParticleHeartbeatPulseAdapterSettings,
};
use crate::private_particle_world_basis::PrivateParticleFrameEyeProjections;

include!(concat!(
    env!("OUT_DIR"),
    "/private_particle_payload_config.rs"
));

const PARTICLE_COMPUTE_LOCAL_SIZE: u32 = 64;
const PARTICLE_SORT_LOCAL_SIZE: u32 = 128;
const PARTICLE_SORT_ROW_BYTES: vk::DeviceSize = 16;
const PARTICLE_OUTPUT_ROWS_PER_INSTANCE: usize = 4;
const PARTICLE_MAIN_STATE_ROWS_PER_INSTANCE: usize = 2;
const PARTICLE_TRACER_STATE_ROWS_PER_SLOT: usize = 4;
const PARTICLE_ANCHOR_ECHO_STATE_ROWS_PER_SLOT: usize = 4;
const PARTICLE_DESCRIPTOR_SET_COUNT: usize = 2;
const PRIVATE_PARTICLE_DRIVER_BANK_VALUE_VEC4_ROWS: usize =
    PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT / 4;
const PRIVATE_PARTICLE_DRIVER_CONTROL_ROWS_PER_SLOT: usize = 3;
const PRIVATE_PARTICLE_DRIVER_BANK_VEC4_ROWS: usize = PRIVATE_PARTICLE_DRIVER_BANK_VALUE_VEC4_ROWS
    + PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT * PRIVATE_PARTICLE_DRIVER_CONTROL_ROWS_PER_SLOT;
const PRIVATE_PARTICLE_DIAGNOSTIC_WORDS: usize = 24;
const PRIVATE_PARTICLE_DIAGNOSTIC_BYTES: vk::DeviceSize =
    (PRIVATE_PARTICLE_DIAGNOSTIC_WORDS * mem::size_of::<i32>()) as vk::DeviceSize;
const PRIVATE_PARTICLE_DIAGNOSTIC_FIXED_POINT_SCALE: f64 = 100_000.0;
const PRIVATE_PARTICLE_SETTINGS_POLL_INTERVAL_FRAMES: u64 = 30;
const PRIVATE_PARTICLE_ORDERING_BACK_TO_FRONT: u32 = 0;
const PRIVATE_PARTICLE_ORDERING_SOURCE_ORDER: u32 = 1;
const PRIVATE_PARTICLE_OFFSCREEN_RESOLUTION_SCALE: f32 = 0.5;
const PRIVATE_PARTICLE_OFFSCREEN_EYE_COUNT: usize = 2;
const PRIVATE_PARTICLE_BLEND_MODE_PACK_OFFSET: u32 = 2_000_000;
const PRIVATE_PARTICLE_GEOMETRY_PACK_OFFSET: u32 = 10_000_000;
pub(crate) const GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT: usize =
    PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT;
const PANEL_DRIVER_MODE_OSCILLATOR: u32 = 0;
const PANEL_DRIVER_MODE_MANUAL: u32 = 1;
const PANEL_DRIVER_MODE_INPUT_SLOT: u32 = 2;
const PANEL_DRIVER_MODE_DIRECT: u32 = 3;
const PANEL_DRIVER_MODE_PRIVATE_HEARTBEAT_ORBIT: u32 = 4;
const PANEL_CURVE_LINEAR: u32 = 0;
const PANEL_CURVE_AKD_HUMP: u32 = 1;
const PANEL_CURVE_SMOOTHSTEP: u32 = 2;
const PANEL_CURVE_REVERSE_LINEAR: u32 = 3;
const PANEL_CURVE_HOLD_LOW: u32 = 4;
const PANEL_CURVE_HOLD_HIGH: u32 = 5;
pub(crate) const PRIVATE_PARTICLE_SIZE_MODE_LEGACY: u32 = 0;
pub(crate) const PRIVATE_PARTICLE_SIZE_MODE_SPHERE_PERCENT: u32 = 1;
pub(crate) const PRIVATE_PARTICLE_SIZE_MODE_WORLD_METERS: u32 = 2;
const PRIVATE_PARTICLE_SIZE_MIN_SPHERE_PERCENT: f32 = 0.1;
const PRIVATE_PARTICLE_SIZE_MAX_SPHERE_PERCENT: f32 = 50.0;
const PRIVATE_PARTICLE_SIZE_MIN_WORLD_METERS: f32 = 0.001;
const PRIVATE_PARTICLE_SIZE_MAX_WORLD_METERS: f32 = 0.5;
const PRIVATE_PARTICLE_SIZE_MAX_OSCILLATION_PERCENT: f32 = 90.0;
const PRIVATE_PARTICLE_LEGACY_SIZE_MIN: f32 = 0.04;
const PRIVATE_PARTICLE_LEGACY_SIZE_MAX: f32 = 0.115;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PrivateParticleTransparencyBlendMode {
    Additive,
    PremultipliedAlphaOver,
}

impl PrivateParticleTransparencyBlendMode {
    fn from_generated_config() -> Self {
        match PRIVATE_PARTICLE_TRANSPARENCY_BLEND_MODE {
            "src-alpha-one-additive" => Self::Additive,
            _ => Self::PremultipliedAlphaOver,
        }
    }

    fn marker_name(self) -> &'static str {
        match self {
            Self::Additive => "src-alpha-one-additive",
            Self::PremultipliedAlphaOver => "src-one-one-minus-src-alpha",
        }
    }

    fn packed_code(self) -> u32 {
        match self {
            Self::Additive => 0,
            Self::PremultipliedAlphaOver => 1,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct PrivateParticleRuntimeSettings {
    visual_scale: f32,
    visual_parameter_source: &'static str,
    particle_size_override_enabled: bool,
    particle_size_mode: u32,
    particle_size_world_meters: f32,
    particle_size_sphere_percent: f32,
    particle_size_oscillation_percent: f32,
    particle_size_parameter_source: &'static str,
    driver0_value01: f32,
    driver1_value01: f32,
    driver_values01: [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    driver_bank_values01: [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    driver_control_modes: [u32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    driver_control_source_slots: [u32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    driver_control_curve_codes: [u32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    driver_control_range_mins: [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    driver_control_range_maxs: [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    driver_control_cycle_multipliers: [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    driver_parameter_source: &'static str,
    tracer_draw_slots_per_oscillator: u32,
    tracer_lifetime_seconds: f32,
    tracer_copies_per_second: f32,
    tracer_parameter_source: &'static str,
    transparency_opacity: f32,
    transparency_output_alpha_scale: f32,
    transparency_depth_suppression_strength: f32,
    transparency_rgb_alpha_coupling: f32,
    transparency_blend_mode: PrivateParticleTransparencyBlendMode,
    transparency_parameter_source: &'static str,
    color_facing_attenuation_strength: f32,
    color_parameter_source: &'static str,
    material_preset: Option<PrivateParticleMaterialPreset>,
    material_parameter_source: &'static str,
    render_experiment_preset: Option<PrivateParticleRenderExperimentPreset>,
    render_experiment_parameter_source: &'static str,
    offscreen_half_res: bool,
    offscreen_half_res_tracers_only: bool,
    offscreen_parameter_source: &'static str,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct GpuPrivateParticlePanelSettings {
    pub(crate) update_mask: PrivateParticlePanelUpdateMask,
    pub(crate) visual_scale: f32,
    pub(crate) particle_size_override_enabled: bool,
    pub(crate) particle_size_mode: u32,
    pub(crate) particle_size_world_meters: f32,
    pub(crate) particle_size_sphere_percent: f32,
    pub(crate) particle_size_oscillation_percent: f32,
    pub(crate) driver_values01: [f32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_modes: [u32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_source_slots: [u32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_curve_codes: [u32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_range_mins: [f32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_range_maxs: [f32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_cycle_multipliers: [f32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) tracer_draw_slots_per_oscillator: u32,
    pub(crate) tracer_lifetime_seconds: f32,
    pub(crate) tracer_copies_per_second: f32,
    pub(crate) transparency_opacity: f32,
    pub(crate) transparency_output_alpha_scale: f32,
    pub(crate) transparency_depth_suppression_strength: f32,
    pub(crate) transparency_rgb_alpha_coupling: f32,
    pub(crate) color_facing_attenuation_strength: f32,
    pub(crate) material_preset: Option<PrivateParticleMaterialPreset>,
    pub(crate) material_override_enabled: bool,
    pub(crate) polar_rr_orbit_boost_enabled: bool,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct GpuPrivateParticlePanelEffectiveSettings {
    pub(crate) visual_scale: f32,
    pub(crate) particle_size_override_enabled: bool,
    pub(crate) particle_size_mode: u32,
    pub(crate) particle_size_world_meters: f32,
    pub(crate) particle_size_sphere_percent: f32,
    pub(crate) particle_size_oscillation_percent: f32,
    pub(crate) particle_size_min: f32,
    pub(crate) particle_size_max: f32,
    pub(crate) particle_size_parameter_source: &'static str,
    pub(crate) driver_values01: [f32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_modes: [u32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_source_slots: [u32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_curve_codes: [u32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_range_mins: [f32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_range_maxs: [f32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_control_cycle_multipliers: [f32; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
    pub(crate) driver_parameter_source: &'static str,
    pub(crate) tracer_draw_slots_per_oscillator: u32,
    pub(crate) tracer_draw_slots_capacity: u32,
    pub(crate) tracer_lifetime_seconds: f32,
    pub(crate) tracer_copies_per_second: f32,
    pub(crate) tracer_parameter_source: &'static str,
    pub(crate) transparency_opacity: f32,
    pub(crate) transparency_output_alpha_scale: f32,
    pub(crate) transparency_depth_suppression_strength: f32,
    pub(crate) transparency_rgb_alpha_coupling: f32,
    pub(crate) transparency_parameter_source: &'static str,
    pub(crate) color_facing_attenuation_strength: f32,
    pub(crate) color_parameter_source: &'static str,
    pub(crate) material_preset: Option<PrivateParticleMaterialPreset>,
    pub(crate) material_parameter_source: &'static str,
    pub(crate) polar_rr_orbit_boost_enabled: bool,
    pub(crate) heartbeat_pulse_parameter_source: &'static str,
}

impl GpuPrivateParticlePanelSettings {
    fn merge_patch(&mut self, newer: Self) {
        let mask = newer.update_mask;
        if mask.visual_scale {
            self.visual_scale = newer.visual_scale;
        }
        if mask.particle_size_override_enabled {
            self.particle_size_override_enabled = newer.particle_size_override_enabled;
        }
        if mask.particle_size_mode {
            self.particle_size_mode = newer.particle_size_mode;
        }
        if mask.particle_size_world_meters {
            self.particle_size_world_meters = newer.particle_size_world_meters;
        }
        if mask.particle_size_sphere_percent {
            self.particle_size_sphere_percent = newer.particle_size_sphere_percent;
        }
        if mask.particle_size_oscillation_percent {
            self.particle_size_oscillation_percent = newer.particle_size_oscillation_percent;
        }
        for index in 0..GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT {
            if mask.driver_values01[index] {
                self.driver_values01[index] = newer.driver_values01[index];
            }
            if mask.driver_controls[index] {
                self.driver_control_modes[index] = newer.driver_control_modes[index];
                self.driver_control_source_slots[index] = newer.driver_control_source_slots[index];
                self.driver_control_curve_codes[index] = newer.driver_control_curve_codes[index];
                self.driver_control_range_mins[index] = newer.driver_control_range_mins[index];
                self.driver_control_range_maxs[index] = newer.driver_control_range_maxs[index];
                self.driver_control_cycle_multipliers[index] =
                    newer.driver_control_cycle_multipliers[index];
            }
        }
        if mask.tracer_draw_slots_per_oscillator {
            self.tracer_draw_slots_per_oscillator = newer.tracer_draw_slots_per_oscillator;
        }
        if mask.tracer_lifetime_seconds {
            self.tracer_lifetime_seconds = newer.tracer_lifetime_seconds;
        }
        if mask.tracer_copies_per_second {
            self.tracer_copies_per_second = newer.tracer_copies_per_second;
        }
        if mask.transparency_opacity {
            self.transparency_opacity = newer.transparency_opacity;
        }
        if mask.transparency_output_alpha_scale {
            self.transparency_output_alpha_scale = newer.transparency_output_alpha_scale;
        }
        if mask.transparency_depth_suppression_strength {
            self.transparency_depth_suppression_strength =
                newer.transparency_depth_suppression_strength;
        }
        if mask.transparency_rgb_alpha_coupling {
            self.transparency_rgb_alpha_coupling = newer.transparency_rgb_alpha_coupling;
        }
        if mask.color_facing_attenuation_strength {
            self.color_facing_attenuation_strength = newer.color_facing_attenuation_strength;
        }
        if mask.material {
            self.material_preset = newer.material_preset;
            self.material_override_enabled = newer.material_override_enabled;
        }
        if mask.polar_rr_orbit_boost {
            self.polar_rr_orbit_boost_enabled = newer.polar_rr_orbit_boost_enabled;
        }
        self.update_mask.merge(mask);
    }

    fn clamped(self) -> Self {
        let particle_size_mode = match self.particle_size_mode {
            PRIVATE_PARTICLE_SIZE_MODE_SPHERE_PERCENT | PRIVATE_PARTICLE_SIZE_MODE_WORLD_METERS => {
                self.particle_size_mode
            }
            _ => PRIVATE_PARTICLE_SIZE_MODE_LEGACY,
        };
        let mut driver_values01 = self.driver_values01;
        for value in &mut driver_values01 {
            *value = value.clamp(0.0, 1.0);
        }
        let mut driver_control_modes = self.driver_control_modes;
        for mode in &mut driver_control_modes {
            if *mode != PANEL_DRIVER_MODE_OSCILLATOR
                && *mode != PANEL_DRIVER_MODE_MANUAL
                && *mode != PANEL_DRIVER_MODE_INPUT_SLOT
                && *mode != PANEL_DRIVER_MODE_DIRECT
                && *mode != PANEL_DRIVER_MODE_PRIVATE_HEARTBEAT_ORBIT
            {
                *mode = PANEL_DRIVER_MODE_DIRECT;
            }
        }
        let mut driver_control_source_slots = self.driver_control_source_slots;
        for source_slot in &mut driver_control_source_slots {
            *source_slot = (*source_slot).min((GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT - 1) as u32);
        }
        let mut driver_control_curve_codes = self.driver_control_curve_codes;
        for curve_code in &mut driver_control_curve_codes {
            if *curve_code > PANEL_CURVE_HOLD_HIGH {
                *curve_code = PANEL_CURVE_LINEAR;
            }
        }
        let mut driver_control_range_mins = self.driver_control_range_mins;
        let mut driver_control_range_maxs = self.driver_control_range_maxs;
        for index in 0..GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT {
            let canonical = canonical_driver_value_range(index);
            driver_control_range_mins[index] =
                driver_control_range_mins[index].clamp(canonical.0, canonical.1);
            driver_control_range_maxs[index] =
                driver_control_range_maxs[index].clamp(canonical.0, canonical.1);
            if driver_control_range_maxs[index] < driver_control_range_mins[index] {
                std::mem::swap(
                    &mut driver_control_range_mins[index],
                    &mut driver_control_range_maxs[index],
                );
            }
        }
        let mut driver_control_cycle_multipliers = self.driver_control_cycle_multipliers;
        for multiplier in &mut driver_control_cycle_multipliers {
            *multiplier = multiplier.clamp(0.0, 10.0);
        }
        if self.polar_rr_orbit_boost_enabled {
            // Slot 5 is the fixed orbit-radius envelope in the private
            // payload. Its event input and envelope tuning stay private; the
            // public panel can only choose this one closed route.
            driver_control_modes[5] = PANEL_DRIVER_MODE_PRIVATE_HEARTBEAT_ORBIT;
            driver_control_source_slots[5] = 5;
            driver_control_curve_codes[5] = PANEL_CURVE_AKD_HUMP;
            driver_control_cycle_multipliers[5] = 1.0;
        }
        Self {
            update_mask: self.update_mask,
            visual_scale: self.visual_scale.clamp(0.05, 1.0),
            particle_size_override_enabled: self.particle_size_override_enabled
                && particle_size_mode != PRIVATE_PARTICLE_SIZE_MODE_LEGACY,
            particle_size_mode,
            particle_size_world_meters: self.particle_size_world_meters.clamp(
                PRIVATE_PARTICLE_SIZE_MIN_WORLD_METERS,
                PRIVATE_PARTICLE_SIZE_MAX_WORLD_METERS,
            ),
            particle_size_sphere_percent: self.particle_size_sphere_percent.clamp(
                PRIVATE_PARTICLE_SIZE_MIN_SPHERE_PERCENT,
                PRIVATE_PARTICLE_SIZE_MAX_SPHERE_PERCENT,
            ),
            particle_size_oscillation_percent: self
                .particle_size_oscillation_percent
                .clamp(0.0, PRIVATE_PARTICLE_SIZE_MAX_OSCILLATION_PERCENT),
            driver_values01,
            driver_control_modes,
            driver_control_source_slots,
            driver_control_curve_codes,
            driver_control_range_mins,
            driver_control_range_maxs,
            driver_control_cycle_multipliers,
            tracer_draw_slots_per_oscillator: self.tracer_draw_slots_per_oscillator.min(1024),
            tracer_lifetime_seconds: self.tracer_lifetime_seconds.clamp(0.016, 30.0),
            tracer_copies_per_second: self.tracer_copies_per_second.clamp(0.0, 120.0),
            transparency_opacity: self.transparency_opacity.clamp(0.0, 4.0),
            transparency_output_alpha_scale: self.transparency_output_alpha_scale.clamp(0.0, 4.0),
            transparency_depth_suppression_strength: self
                .transparency_depth_suppression_strength
                .clamp(0.0, 8.0),
            transparency_rgb_alpha_coupling: self.transparency_rgb_alpha_coupling.clamp(0.0, 1.0),
            color_facing_attenuation_strength: self
                .color_facing_attenuation_strength
                .clamp(0.0, 1.0),
            material_preset: self.material_preset,
            material_override_enabled: self.material_override_enabled,
            polar_rr_orbit_boost_enabled: self.polar_rr_orbit_boost_enabled,
        }
    }
}

fn private_particle_panel_direct_modes() -> [u32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT] {
    [PANEL_DRIVER_MODE_DIRECT; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT]
}

fn private_particle_panel_source_slots() -> [u32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT] {
    let mut slots = [0u32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT];
    for (index, slot) in slots.iter_mut().enumerate() {
        *slot = index as u32;
    }
    slots
}

fn private_particle_panel_linear_curves() -> [u32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT] {
    [PANEL_CURVE_LINEAR; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT]
}

fn private_particle_panel_range_mins() -> [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT] {
    let mut values = [0.0; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT];
    for (index, value) in values.iter_mut().enumerate() {
        *value = canonical_driver_value_range(index).0;
    }
    values
}

fn private_particle_panel_range_maxs() -> [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT] {
    let mut values = [1.0; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT];
    for (index, value) in values.iter_mut().enumerate() {
        *value = canonical_driver_value_range(index).1;
    }
    values
}

fn private_particle_panel_cycle_multipliers() -> [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT] {
    [1.0; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT]
}

fn legacy_particle_size_percent_envelope(visual_scale: f32) -> (f32, f32, f32, f32) {
    let min = PRIVATE_PARTICLE_LEGACY_SIZE_MIN * visual_scale * 100.0;
    let max = PRIVATE_PARTICLE_LEGACY_SIZE_MAX * visual_scale * 100.0;
    let base = (min * max).sqrt();
    let oscillation_percent = 100.0 * (1.0 - (min / max).sqrt());
    (base, oscillation_percent, min, max)
}

impl PrivateParticleRuntimeSettings {
    fn from_generated_defaults() -> Self {
        let driver_values01 = private_particle_driver_values01_from_generated();
        let visual_scale = PRIVATE_PARTICLE_VISUAL_SCALE.clamp(0.05, 1.0);
        let (legacy_size_base, legacy_size_oscillation, _, _) =
            legacy_particle_size_percent_envelope(visual_scale);
        Self {
            visual_scale,
            visual_parameter_source: PRIVATE_PARTICLE_VISUAL_PARAMETER_SOURCE,
            particle_size_override_enabled: false,
            particle_size_mode: PRIVATE_PARTICLE_SIZE_MODE_LEGACY,
            particle_size_world_meters: 0.05,
            particle_size_sphere_percent: legacy_size_base,
            particle_size_oscillation_percent: legacy_size_oscillation,
            particle_size_parameter_source: "payload-legacy-size-envelope",
            driver0_value01: PRIVATE_PARTICLE_DRIVER_VALUES01[0].clamp(0.0, 1.0),
            driver1_value01: PRIVATE_PARTICLE_DRIVER_VALUES01[1].clamp(0.0, 1.0),
            driver_values01,
            driver_bank_values01: driver_values01,
            driver_control_modes: private_particle_panel_direct_modes(),
            driver_control_source_slots: private_particle_panel_source_slots(),
            driver_control_curve_codes: private_particle_panel_linear_curves(),
            driver_control_range_mins: private_particle_panel_range_mins(),
            driver_control_range_maxs: private_particle_panel_range_maxs(),
            driver_control_cycle_multipliers: private_particle_panel_cycle_multipliers(),
            driver_parameter_source: PRIVATE_PARTICLE_DRIVER_PARAMETER_SOURCE,
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
            transparency_blend_mode: PrivateParticleTransparencyBlendMode::from_generated_config(),
            transparency_parameter_source: PRIVATE_PARTICLE_TRANSPARENCY_PARAMETER_SOURCE,
            color_facing_attenuation_strength: PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION_STRENGTH
                .clamp(0.0, 1.0),
            color_parameter_source: PRIVATE_PARTICLE_COLOR_PARAMETER_SOURCE,
            material_preset: None,
            material_parameter_source: "runtime-owner-default-when-unset",
            render_experiment_preset: None,
            render_experiment_parameter_source: "runtime-owner-default-build-mask-policy",
            offscreen_half_res: false,
            offscreen_half_res_tracers_only: false,
            offscreen_parameter_source: "renderer-default-direct-projection-pass",
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
        let (driver_values01, driver_overridden) = driver_bank_hotload_values(&mut lookup);
        let driver0_value01 = driver_values01[0];
        let driver1_value01 = driver_values01[1];
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
        let (offscreen_half_res, offscreen_overridden) = bool_hotload_value(
            &mut lookup,
            PROP_PRIVATE_PARTICLES_OFFSCREEN_HALF_RES,
            false,
        );
        let (offscreen_half_res_tracers_only, offscreen_tracers_only_overridden) =
            bool_hotload_value(
                &mut lookup,
                PROP_PRIVATE_PARTICLES_OFFSCREEN_HALF_RES_TRACERS_ONLY,
                false,
            );
        let tracer_overridden =
            tracer_draw_overridden || tracer_lifetime_overridden || tracer_copies_overridden;
        let transparency_overridden = transparency_opacity_overridden
            || transparency_output_alpha_overridden
            || transparency_depth_suppression_overridden
            || transparency_rgb_alpha_overridden;
        let (legacy_size_base, legacy_size_oscillation, _, _) =
            legacy_particle_size_percent_envelope(visual_scale);
        Self {
            visual_scale,
            visual_parameter_source: if visual_overridden {
                "runtime-hotload-android-property"
            } else {
                PRIVATE_PARTICLE_VISUAL_PARAMETER_SOURCE
            },
            particle_size_override_enabled: false,
            particle_size_mode: PRIVATE_PARTICLE_SIZE_MODE_LEGACY,
            particle_size_world_meters: 0.05,
            particle_size_sphere_percent: legacy_size_base,
            particle_size_oscillation_percent: legacy_size_oscillation,
            particle_size_parameter_source: "payload-legacy-size-envelope",
            driver0_value01,
            driver1_value01,
            driver_values01,
            driver_bank_values01: driver_values01,
            driver_control_modes: private_particle_panel_direct_modes(),
            driver_control_source_slots: private_particle_panel_source_slots(),
            driver_control_curve_codes: private_particle_panel_linear_curves(),
            driver_control_range_mins: private_particle_panel_range_mins(),
            driver_control_range_maxs: private_particle_panel_range_maxs(),
            driver_control_cycle_multipliers: private_particle_panel_cycle_multipliers(),
            driver_parameter_source: if driver_overridden {
                "runtime-hotload-android-property"
            } else {
                PRIVATE_PARTICLE_DRIVER_PARAMETER_SOURCE
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
            transparency_blend_mode: PrivateParticleTransparencyBlendMode::from_generated_config(),
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
            material_preset: None,
            material_parameter_source: "runtime-owner-default-when-unset",
            render_experiment_preset: None,
            render_experiment_parameter_source: "runtime-owner-default-build-mask-policy",
            offscreen_half_res,
            offscreen_half_res_tracers_only: offscreen_half_res && offscreen_half_res_tracers_only,
            offscreen_parameter_source: if offscreen_overridden || offscreen_tracers_only_overridden
            {
                "runtime-hotload-android-property"
            } else {
                "renderer-default-direct-projection-pass"
            },
        }
    }

    fn apply_panel_override(
        &mut self,
        panel: GpuPrivateParticlePanelSettings,
        source_values01: [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    ) {
        let panel = panel.clamped();
        let mask = panel.update_mask;
        if mask.visual_scale {
            self.visual_scale = panel.visual_scale;
            self.visual_parameter_source = "same-apk-panel-live";
        }
        if mask.particle_size_override_enabled {
            self.particle_size_override_enabled = panel.particle_size_override_enabled;
        }
        if mask.particle_size_mode {
            self.particle_size_mode = panel.particle_size_mode;
        }
        if mask.particle_size_world_meters {
            self.particle_size_world_meters = panel.particle_size_world_meters;
        }
        if mask.particle_size_sphere_percent {
            self.particle_size_sphere_percent = panel.particle_size_sphere_percent;
        }
        if mask.particle_size_oscillation_percent {
            self.particle_size_oscillation_percent = panel.particle_size_oscillation_percent;
        }
        let size_updated = mask.particle_size_override_enabled
            || mask.particle_size_mode
            || mask.particle_size_world_meters
            || mask.particle_size_sphere_percent
            || mask.particle_size_oscillation_percent;
        if size_updated && !self.particle_size_override_enabled {
            let (base, oscillation, _, _) =
                legacy_particle_size_percent_envelope(self.visual_scale);
            self.particle_size_mode = PRIVATE_PARTICLE_SIZE_MODE_LEGACY;
            self.particle_size_world_meters = 0.05;
            self.particle_size_sphere_percent = base;
            self.particle_size_oscillation_percent = oscillation;
        }
        if size_updated {
            self.particle_size_parameter_source = if self.particle_size_override_enabled {
                "same-apk-panel-live-explicit-size"
            } else {
                "payload-legacy-size-envelope"
            };
        }
        for slot in 0..PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT {
            if mask.driver_values01[slot] {
                self.driver_values01[slot] = panel.driver_values01[slot];
            }
            if mask.driver_controls[slot] {
                self.driver_control_modes[slot] = panel.driver_control_modes[slot];
                self.driver_control_source_slots[slot] = panel.driver_control_source_slots[slot];
                self.driver_control_curve_codes[slot] = panel.driver_control_curve_codes[slot];
                self.driver_control_range_mins[slot] = panel.driver_control_range_mins[slot];
                self.driver_control_range_maxs[slot] = panel.driver_control_range_maxs[slot];
                self.driver_control_cycle_multipliers[slot] =
                    panel.driver_control_cycle_multipliers[slot];
            }
        }
        self.driver_bank_values01 = source_values01;
        for slot in 0..PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT {
            if self.driver_control_modes[slot] == PANEL_DRIVER_MODE_DIRECT {
                self.driver_bank_values01[slot] = self.driver_values01[slot];
            }
        }
        self.driver0_value01 = self.driver_bank_values01[0];
        self.driver1_value01 = self.driver_bank_values01[1];
        if mask.driver_values01.iter().any(|selected| *selected)
            || mask.driver_controls.iter().any(|selected| *selected)
        {
            self.driver_parameter_source = "same-apk-panel-live";
        }
        if mask.tracer_draw_slots_per_oscillator {
            self.tracer_draw_slots_per_oscillator = panel.tracer_draw_slots_per_oscillator;
        }
        if mask.tracer_lifetime_seconds {
            self.tracer_lifetime_seconds = panel.tracer_lifetime_seconds;
        }
        if mask.tracer_copies_per_second {
            self.tracer_copies_per_second = panel.tracer_copies_per_second;
        }
        if mask.tracer_draw_slots_per_oscillator
            || mask.tracer_lifetime_seconds
            || mask.tracer_copies_per_second
        {
            self.tracer_parameter_source = "same-apk-panel-live";
        }
        if mask.transparency_opacity {
            self.transparency_opacity = panel.transparency_opacity;
        }
        if mask.transparency_output_alpha_scale {
            self.transparency_output_alpha_scale = panel.transparency_output_alpha_scale;
        }
        if mask.transparency_depth_suppression_strength {
            self.transparency_depth_suppression_strength =
                panel.transparency_depth_suppression_strength;
        }
        if mask.transparency_rgb_alpha_coupling {
            self.transparency_rgb_alpha_coupling = panel.transparency_rgb_alpha_coupling;
        }
        if mask.transparency_opacity
            || mask.transparency_output_alpha_scale
            || mask.transparency_depth_suppression_strength
            || mask.transparency_rgb_alpha_coupling
        {
            self.transparency_parameter_source = "same-apk-panel-live";
        }
        if mask.color_facing_attenuation_strength {
            self.color_facing_attenuation_strength = panel.color_facing_attenuation_strength;
            self.color_parameter_source = "same-apk-panel-live";
        }
    }

    fn apply_heartbeat_orbit_control(&mut self, requested: Option<bool>) {
        let Some(enabled) = requested else {
            return;
        };
        self.driver_control_modes[5] = if enabled {
            PANEL_DRIVER_MODE_PRIVATE_HEARTBEAT_ORBIT
        } else {
            PANEL_DRIVER_MODE_DIRECT
        };
        self.driver_control_curve_codes[5] = if enabled {
            PANEL_CURVE_AKD_HUMP
        } else {
            PANEL_CURVE_LINEAR
        };
        if enabled {
            self.driver_control_source_slots[5] = 5;
            self.driver_control_range_mins[5] = canonical_driver_value_range(5).0;
            self.driver_control_range_maxs[5] = canonical_driver_value_range(5).1;
            self.driver_control_cycle_multipliers[5] = 1.0;
        }
        self.driver_parameter_source = "runtime-heartbeat-orbit-control";
    }

    fn material_defaults(&self) -> PrivateParticleMaterialDefaults {
        PrivateParticleMaterialDefaults {
            transparency_opacity: self.transparency_opacity,
            transparency_output_alpha_scale: self.transparency_output_alpha_scale,
            transparency_depth_suppression_strength: self.transparency_depth_suppression_strength,
            transparency_rgb_alpha_coupling: self.transparency_rgb_alpha_coupling,
            transparency_blend_mode: self.transparency_blend_mode,
            color_facing_attenuation_strength: self.color_facing_attenuation_strength,
        }
    }

    fn apply_panel_material_override(
        &mut self,
        panel: GpuPrivateParticlePanelSettings,
        defaults: PrivateParticleMaterialDefaults,
    ) {
        if !panel.update_mask.material || !panel.material_override_enabled {
            return;
        }
        if let Some(preset) = panel.material_preset {
            self.apply_material_preset_with_source(preset, "same-apk-panel-live");
            return;
        }
        self.transparency_opacity = defaults.transparency_opacity;
        self.transparency_output_alpha_scale = defaults.transparency_output_alpha_scale;
        self.transparency_depth_suppression_strength =
            defaults.transparency_depth_suppression_strength;
        self.transparency_rgb_alpha_coupling = defaults.transparency_rgb_alpha_coupling;
        self.transparency_blend_mode = defaults.transparency_blend_mode;
        self.transparency_parameter_source = "same-apk-panel-live-packaged-default";
        self.color_facing_attenuation_strength = defaults.color_facing_attenuation_strength;
        self.color_parameter_source = "same-apk-panel-live-packaged-default";
        self.material_preset = None;
        self.material_parameter_source = "same-apk-panel-live-packaged-default";
    }

    fn apply_material_preset(&mut self, preset: PrivateParticleMaterialPreset) {
        self.apply_material_preset_with_source(preset, "runtime-fenced-material-request-v1");
    }

    fn apply_material_preset_with_source(
        &mut self,
        preset: PrivateParticleMaterialPreset,
        parameter_source: &'static str,
    ) {
        // This is the current Viscereality material, expressed explicitly so a
        // closed request also clears any earlier scalar-property experiments
        // without changing the packaged startup default. The AKD preset keeps
        // its historical 0.36 coverage, 0.45 stored alpha, depth 1.5, and
        // 0.80 + 0.20 * facing term in the parser-owned envelope.
        let parameters = preset.parameters();
        self.transparency_blend_mode = if parameters.uses_premultiplied_alpha_over {
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver
        } else {
            PrivateParticleTransparencyBlendMode::Additive
        };
        self.transparency_opacity = parameters.opacity;
        self.transparency_output_alpha_scale = parameters.output_alpha_scale;
        self.transparency_depth_suppression_strength = parameters.depth_suppression_strength;
        self.transparency_rgb_alpha_coupling = parameters.rgb_alpha_coupling;
        self.transparency_parameter_source = parameter_source;
        self.color_facing_attenuation_strength = parameters.facing_attenuation_strength;
        self.color_parameter_source = parameter_source;
        self.material_preset = Some(preset);
        self.material_parameter_source = parameter_source;
    }

    fn apply_render_experiment_preset(&mut self, preset: PrivateParticleRenderExperimentPreset) {
        self.render_experiment_preset = Some(preset);
        self.render_experiment_parameter_source = "runtime-fenced-render-experiment-request-v1";
    }

    fn apply_driver_source_values(
        &mut self,
        source_values01: [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
        parameter_source: &'static str,
    ) {
        self.driver_values01 = source_values01;
        self.driver_bank_values01 = source_values01;
        self.driver0_value01 = self.driver_bank_values01[0];
        self.driver1_value01 = self.driver_bank_values01[1];
        self.driver_parameter_source = parameter_source;
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

fn canonical_driver_value_range(target_slot: usize) -> (f32, f32) {
    match target_slot {
        2 => (0.04, 0.115),
        3 => (0.0, 0.1),
        4 => (0.1, 0.5),
        5 => (0.2, 1.5),
        6 => (0.0, std::f32::consts::TAU),
        7 => (0.0, 1.0),
        _ => (0.0, 1.0),
    }
}

fn panel_requires_input_driver_update(panel: &GpuPrivateParticlePanelSettings) -> bool {
    panel
        .driver_control_modes
        .iter()
        .enumerate()
        .any(|(index, mode)| {
            panel.update_mask.driver_controls[index] && *mode == PANEL_DRIVER_MODE_INPUT_SLOT
        })
}

#[derive(Clone, Copy, Debug)]
struct PrivateParticleMaterialDefaults {
    transparency_opacity: f32,
    transparency_output_alpha_scale: f32,
    transparency_depth_suppression_strength: f32,
    transparency_rgb_alpha_coupling: f32,
    transparency_blend_mode: PrivateParticleTransparencyBlendMode,
    color_facing_attenuation_strength: f32,
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

fn bool_hotload_value(
    lookup: &mut impl FnMut(&str) -> Option<String>,
    property_name: &str,
    default_value: bool,
) -> (bool, bool) {
    let value = lookup(property_name);
    let overridden = value.is_some();
    (bool_value(value, default_value), overridden)
}

fn private_particle_driver_values01_from_generated(
) -> [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT] {
    let mut values = [0.0_f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT];
    let count = PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT.min(PRIVATE_PARTICLE_DRIVER_VALUES01.len());
    values[..count].copy_from_slice(&PRIVATE_PARTICLE_DRIVER_VALUES01[..count]);
    for value in &mut values {
        *value = value.clamp(0.0, 1.0);
    }
    values
}

fn driver_bank_hotload_values(
    lookup: &mut impl FnMut(&str) -> Option<String>,
) -> ([f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT], bool) {
    let defaults = private_particle_driver_values01_from_generated();
    let property_names = [
        PROP_PRIVATE_PARTICLES_DRIVER0_VALUE01,
        PROP_PRIVATE_PARTICLES_DRIVER1_VALUE01,
        PROP_PRIVATE_PARTICLES_DRIVER2_VALUE01,
        PROP_PRIVATE_PARTICLES_DRIVER3_VALUE01,
        PROP_PRIVATE_PARTICLES_DRIVER4_VALUE01,
        PROP_PRIVATE_PARTICLES_DRIVER5_VALUE01,
        PROP_PRIVATE_PARTICLES_DRIVER6_VALUE01,
        PROP_PRIVATE_PARTICLES_DRIVER7_VALUE01,
    ];
    let mut values = [0.0_f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT];
    let mut overridden = false;
    for (index, property_name) in property_names.iter().enumerate() {
        let (value, is_overridden) =
            f32_hotload_value(lookup, property_name, defaults[index], 0.0, 1.0);
        values[index] = value;
        overridden |= is_overridden;
    }
    (values, overridden)
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

fn private_particle_visual_scale_request_property() -> Option<String> {
    android_property(PROP_PRIVATE_PARTICLES_VISUAL_SCALE_REQUEST_V1)
}

fn private_particle_heartbeat_orbit_request_property() -> Option<String> {
    android_property(PROP_PRIVATE_PARTICLES_HEARTBEAT_ORBIT_REQUEST_V1)
}

fn private_particle_material_request_property() -> Option<String> {
    android_property(PROP_PRIVATE_PARTICLES_MATERIAL_REQUEST_V1)
}

fn private_particle_render_experiment_request_property() -> Option<String> {
    android_property(PROP_PRIVATE_PARTICLES_RENDER_EXPERIMENT_REQUEST_V1)
}

#[derive(Clone, Copy, Debug)]
struct PrivateParticleDiagnosticSnapshot {
    status: &'static str,
    particle_count: u32,
    order: [f32; 6],
    tracer_active_count: u32,
    tracer_spawned_count: u32,
    tracer_discarded_count: u32,
    anchor_echo_active_count: u32,
    anchor_echo_spawned_count: u32,
    anchor_echo_discarded_count: u32,
    saturation_count: u32,
    active_edge_count: u32,
    pass_health_flags: u32,
    source_frame_id: Option<u64>,
    raw: [i32; PRIVATE_PARTICLE_DIAGNOSTIC_WORDS],
}

impl PrivateParticleDiagnosticSnapshot {
    const fn unavailable() -> Self {
        Self {
            status: "unavailable",
            particle_count: 0,
            order: [0.0; 6],
            tracer_active_count: 0,
            tracer_spawned_count: 0,
            tracer_discarded_count: 0,
            anchor_echo_active_count: 0,
            anchor_echo_spawned_count: 0,
            anchor_echo_discarded_count: 0,
            saturation_count: 0,
            active_edge_count: 0,
            pass_health_flags: 0,
            source_frame_id: None,
            raw: [0; PRIVATE_PARTICLE_DIAGNOSTIC_WORDS],
        }
    }

    const fn pending() -> Self {
        Self {
            status: "pending",
            particle_count: 0,
            order: [0.0; 6],
            tracer_active_count: 0,
            tracer_spawned_count: 0,
            tracer_discarded_count: 0,
            anchor_echo_active_count: 0,
            anchor_echo_spawned_count: 0,
            anchor_echo_discarded_count: 0,
            saturation_count: 0,
            active_edge_count: 0,
            pass_health_flags: 0,
            source_frame_id: None,
            raw: [0; PRIVATE_PARTICLE_DIAGNOSTIC_WORDS],
        }
    }

    fn from_raw(
        raw: [i32; PRIVATE_PARTICLE_DIAGNOSTIC_WORDS],
        source_frame_id: Option<u64>,
    ) -> Self {
        let particle_count = raw[0].max(0) as u32;
        let denominator =
            (particle_count.max(1) as f64) * PRIVATE_PARTICLE_DIAGNOSTIC_FIXED_POINT_SCALE;
        let mut order = [0.0_f32; 6];
        for dim in 0..6 {
            let cos_sum = raw[1 + dim * 2] as f64;
            let sin_sum = raw[2 + dim * 2] as f64;
            order[dim] = ((cos_sum * cos_sum + sin_sum * sin_sum).sqrt() / denominator)
                .clamp(0.0, 1.0) as f32;
        }
        let tracer_events = raw[15].max(0) as u32;
        let anchor_echo_events = raw[19].max(0) as u32;
        Self {
            status: "readback",
            particle_count,
            order,
            tracer_active_count: raw[13].max(0) as u32,
            tracer_spawned_count: tracer_events & 0x0000_ffff,
            tracer_discarded_count: (tracer_events >> 16) & 0x0000_ffff,
            anchor_echo_active_count: raw[18].max(0) as u32,
            anchor_echo_spawned_count: anchor_echo_events & 0x0000_ffff,
            anchor_echo_discarded_count: (anchor_echo_events >> 16) & 0x0000_ffff,
            saturation_count: raw[14].max(0) as u32,
            active_edge_count: raw[16].max(0) as u32,
            pass_health_flags: raw[17].max(0) as u32,
            source_frame_id,
            raw,
        }
    }

    fn marker_fields(self, submitted_frame_id: u64) -> String {
        let (measured_frame_id, measured_frame_lag_frames) =
            source_frame_id_fields(self.source_frame_id, submitted_frame_id);
        format!(
            "privateParticleDiagnosticReadbackStatus={} privateParticleDiagnosticStorageBinding=9 privateParticleDiagnosticWords={} privateParticleDiagnosticFixedPointScale={} privateParticleDiagnosticCpuFullBufferReadback=false privateParticleDiagnosticSubmittedFrameId={} privateParticleDiagnosticMeasuredFrameId={} privateParticleDiagnosticMeasuredFrameLagFrames={} privateParticleDiagnosticFrameIdentityAvailable={} privateParticleDiagnosticParticleCount={} privateParticleDiagnosticOrderDim0={:.4} privateParticleDiagnosticOrderDim1={:.4} privateParticleDiagnosticOrderDim2={:.4} privateParticleDiagnosticOrderDim3={:.4} privateParticleDiagnosticOrderDim4={:.4} privateParticleDiagnosticOrderDim5={:.4} privateParticleDiagnosticTracerActiveCount={} privateParticleDiagnosticTracerSpawnedCount={} privateParticleDiagnosticTracerDiscardedCount={} privateParticleDiagnosticAnchorEchoActiveCount={} privateParticleDiagnosticAnchorEchoSpawnedCount={} privateParticleDiagnosticAnchorEchoDiscardedCount={} privateParticleDiagnosticSaturationCount={} privateParticleDiagnosticActiveEdgeCount={} privateParticleDiagnosticPassHealthFlags={} privateParticleDiagnosticRawParticleCount={} privateParticleDiagnosticRawOrderDim0Cos={} privateParticleDiagnosticRawOrderDim0Sin={} privateParticleDiagnosticRawTracerEvents={} privateParticleDiagnosticRawAnchorEchoEvents={} privateParticleDiagnosticRawActiveEdgeCount={} privateParticleDiagnosticRawPassHealthFlags={}",
            self.status,
            PRIVATE_PARTICLE_DIAGNOSTIC_WORDS,
            PRIVATE_PARTICLE_DIAGNOSTIC_FIXED_POINT_SCALE as u32,
            submitted_frame_id,
            measured_frame_id,
            measured_frame_lag_frames,
            self.status == "readback" && self.source_frame_id.is_some(),
            self.particle_count,
            self.order[0],
            self.order[1],
            self.order[2],
            self.order[3],
            self.order[4],
            self.order[5],
            self.tracer_active_count,
            self.tracer_spawned_count,
            self.tracer_discarded_count,
            self.anchor_echo_active_count,
            self.anchor_echo_spawned_count,
            self.anchor_echo_discarded_count,
            self.saturation_count,
            self.active_edge_count,
            self.pass_health_flags,
            self.raw[0],
            self.raw[1],
            self.raw[2],
            self.raw[15],
            self.raw[19],
            self.raw[16],
            self.raw[17],
        )
    }
}

fn source_frame_id_fields(
    source_frame_id: Option<u64>,
    submitted_frame_id: u64,
) -> (String, String) {
    source_frame_id
        .map(|measured| {
            (
                measured.to_string(),
                submitted_frame_id.saturating_sub(measured).to_string(),
            )
        })
        .unwrap_or_else(|| ("unavailable".to_string(), "unavailable".to_string()))
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
    pub(crate) anchor_echo_max_count: u32,
    pub(crate) anchor_echo_draw_echo_count: u32,
    pub(crate) anchor_echo_draw_count: u32,
    pub(crate) draw_count: u32,
    pub(crate) state_ping_pong: bool,
    pub(crate) aux0_rows: u32,
    pub(crate) sort_active: bool,
    pub(crate) sort_input_count: u32,
    pub(crate) sort_count: u32,
    pub(crate) sort_capacity: u32,
    pub(crate) world_anchor_scale_m: f32,
    pub(crate) world_anchor_scale_parameter_source: &'static str,
    runtime_settings: PrivateParticleRuntimeSettings,
    tracer_draw_slots_capacity: u32,
    diagnostic_snapshot: PrivateParticleDiagnosticSnapshot,
}

impl GpuPrivateParticleFrameStats {
    pub(crate) fn unavailable() -> Self {
        Self::default()
    }

    pub(crate) fn half_res_offscreen_requested(self) -> bool {
        if !self.ready || !self.visible || !self.runtime_settings.offscreen_half_res {
            return false;
        }
        if self.half_res_offscreen_tracers_only_requested() {
            return self.tracer_draw_count > 0;
        }
        self.draw_count > 0
    }

    pub(crate) fn half_res_offscreen_tracers_only_requested(self) -> bool {
        self.runtime_settings.offscreen_half_res
            && self.runtime_settings.offscreen_half_res_tracers_only
            && !private_particle_sort_enabled()
            && self.tracer_draw_count > 0
            && self.anchor_echo_draw_count == 0
    }

    fn marker_fields(self, submitted_frame_id: u64) -> String {
        format!(
            "privateParticleReady={} privateParticleVisible={} privateParticlePayloadLinked={} privateParticleKind={} privateParticleCount={} privateParticleMainCount={} privateParticleDrawCount={} privateParticleSettingsHotload=true privateParticleHotloadPollIntervalFrames={} privateParticleWorldAnchorScaleM={:.3} privateParticleWorldAnchorScaleParameterSource={} privateParticleVisualScale={:.3} privateParticleVisualParameterSource={} {} privateParticleDriver0Value01={:.3} privateParticleDriver1Value01={:.3} {} privateParticleDriverParameterSource={} privateParticleTracerMaxCount={} privateParticleTracerStateCapacity={} privateParticleTracerDrawSlotsCapacity={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawCount={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTracerStateRows={} privateParticleTracerRadiusPolicy=snapshot-source-radius privateParticleTracerOutputMode=merged-billboard-output privateParticleDrawBudgetIncludesTracers={} privateParticleTracerCpuUploadPerFrame=false {} {} privateParticleOutputAbi=four-vec4-billboard-rows privateParticleBillboardKindAux=aux.z-main-1-tracer-2-anchor-3-anchor_echo-4 privateParticleStatePingPong={} privateParticleAux0Rows={} privateParticleOrderingMode={} privateParticleOrderingImplementation={} privateParticleOrderingParameterSource={} privateParticleOrderingBasis=per-eye-openxr-reference-space privateParticleSortActive={} privateParticleSortInputCount={} privateParticleSortCount={} privateParticleSortCapacity={} privateParticleOrderingCpuExpandedUploadPerFrame=false {} privateParticleMaskTextureLinked={} privateParticleMaskTextureMode={} privateParticleMaskDiscardMode={} privateParticleMaskAlphaCutoff={:.4} privateParticleMaskTextureFormat=R8_UNORM privateParticleMaskTextureSize={}x{}x{} privateParticleMaskTextureBytes={} {} {} {} privateParticleCpuUploadBytes=0 privateParticleGpuBuffersResident={} privateParticleMaskTextureGpuResident={}",
            self.ready,
            self.visible,
            PRIVATE_PARTICLE_PAYLOAD_LINKED,
            crate::sanitize(PRIVATE_PARTICLE_KIND),
            self.particle_count,
            self.main_particle_count,
            self.draw_count,
            PRIVATE_PARTICLE_SETTINGS_POLL_INTERVAL_FRAMES,
            self.world_anchor_scale_m,
            crate::sanitize(self.world_anchor_scale_parameter_source),
            self.runtime_settings.visual_scale,
            crate::sanitize(self.runtime_settings.visual_parameter_source),
            private_particle_size_marker_fields(self.runtime_settings),
            self.runtime_settings.driver0_value01,
            self.runtime_settings.driver1_value01,
            private_particle_driver_bank_marker_fields(self.runtime_settings),
            crate::sanitize(self.runtime_settings.driver_parameter_source),
            self.tracer_max_count,
            self.tracer_max_count,
            self.tracer_draw_slots_capacity,
            self.tracer_draw_slots_per_oscillator,
            self.tracer_draw_count,
            self.runtime_settings.tracer_lifetime_seconds,
            self.runtime_settings.tracer_copies_per_second,
            crate::sanitize(self.runtime_settings.tracer_parameter_source),
            self.tracer_max_count * PARTICLE_TRACER_STATE_ROWS_PER_SLOT as u32,
            self.tracer_draw_count > 0,
            private_particle_anchor_echo_marker_fields(
                self.anchor_echo_max_count,
                self.anchor_echo_draw_echo_count,
                self.anchor_echo_draw_count,
            ),
            self.workload_marker_fields(),
            self.state_ping_pong,
            self.aux0_rows,
            crate::sanitize(PRIVATE_PARTICLE_ORDERING_MODE),
            private_particle_ordering_implementation(),
            crate::sanitize(PRIVATE_PARTICLE_ORDERING_PARAMETER_SOURCE),
            self.sort_active,
            self.sort_input_count,
            self.sort_count,
            self.sort_capacity,
            self.diagnostic_snapshot.marker_fields(submitted_frame_id),
            PRIVATE_PARTICLE_MASK_TEXTURE_LINKED,
            crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MODE),
            crate::sanitize(PRIVATE_PARTICLE_MASK_DISCARD_MODE),
            PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF,
            PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
            PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
            PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
            PRIVATE_PARTICLE_MASK_TEXTURE_BYTES,
            private_particle_mask_texture_marker_fields(),
            private_particle_transparency_marker_fields(self.runtime_settings),
            private_particle_offscreen_marker_fields(self.runtime_settings),
            self.ready,
            self.ready
        )
    }

    fn workload_marker_fields(self) -> String {
        let compute_workgroup_count = self.particle_count.div_ceil(PARTICLE_COMPUTE_LOCAL_SIZE);
        let compute_launched_lane_count =
            compute_workgroup_count.saturating_mul(PARTICLE_COMPUTE_LOCAL_SIZE);
        let draw_budget_rows = self
            .draw_count
            .saturating_mul(PARTICLE_OUTPUT_ROWS_PER_INSTANCE as u32);
        let tracer_output_slots_capacity = self
            .particle_count
            .saturating_mul(self.tracer_draw_slots_capacity);
        let draw_budget_capacity_instances = self
            .particle_count
            .saturating_add(tracer_output_slots_capacity)
            .saturating_add(self.anchor_echo_draw_count);
        let draw_budget_capacity_rows =
            draw_budget_capacity_instances.saturating_mul(PARTICLE_OUTPUT_ROWS_PER_INSTANCE as u32);
        let tracer_state_slots_visited = self
            .particle_count
            .saturating_mul(self.tracer_max_count / self.particle_count.max(1));
        let tracer_state_rows_visited =
            tracer_state_slots_visited.saturating_mul(PARTICLE_TRACER_STATE_ROWS_PER_SLOT as u32);
        let anchor_echo_state_slots_visited = if self.anchor_echo_draw_echo_count == 0 {
            0
        } else {
            self.anchor_echo_max_count
        };
        format!(
            "privateParticleWorkloadMarkerVersion=v1 privateParticleWorkloadSource=effective-runtime-config privateParticleComputeLogicalParticleCount={} privateParticleComputeWorkgroupCount={} privateParticleComputeLocalSizeX={} privateParticleComputeLaunchedLaneCount={} privateParticleMainOutputInstances={} privateParticleTracerStateSlotsVisited={} privateParticleTracerStateRowsVisited={} privateParticleTracerOutputSlotsCleared={} privateParticleTracerOutputSlotsCapacity={} privateParticleAnchorEchoStateSlotsVisited={} privateParticleAnchorEchoOutputSlotsCleared={} privateParticleAnchorEchoOutputSlotsCapacity={} privateParticleDrawBudgetInstances={} privateParticleDrawBudgetRows={} privateParticleDrawBudgetCapacityInstances={} privateParticleDrawBudgetCapacityRows={} privateParticleWorkloadHighRatePayload=false",
            self.particle_count,
            compute_workgroup_count,
            PARTICLE_COMPUTE_LOCAL_SIZE,
            compute_launched_lane_count,
            self.main_particle_count,
            tracer_state_slots_visited,
            tracer_state_rows_visited,
            self.tracer_draw_count,
            tracer_output_slots_capacity,
            anchor_echo_state_slots_visited,
            self.anchor_echo_draw_count,
            self.anchor_echo_draw_count,
            self.draw_count,
            draw_budget_rows,
            draw_budget_capacity_instances,
            draw_budget_capacity_rows,
        )
    }
}

fn private_particle_driver_bank_marker_fields(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> String {
    format!(
        "privateParticleDriverBankSlotCount={} privateParticleDriverBankStorageBinding=8 privateParticleDriver2Value01={:.3} privateParticleDriver3Value01={:.3} privateParticleDriver4Value01={:.3} privateParticleDriver5Value01={:.3} privateParticleDriver6Value01={:.3} privateParticleDriver7Value01={:.3}",
        PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT,
        runtime_settings.driver_bank_values01[2],
        runtime_settings.driver_bank_values01[3],
        runtime_settings.driver_bank_values01[4],
        runtime_settings.driver_bank_values01[5],
        runtime_settings.driver_bank_values01[6],
        runtime_settings.driver_bank_values01[7],
    )
}

fn private_particle_size_limits(runtime_settings: PrivateParticleRuntimeSettings) -> (f32, f32) {
    if !runtime_settings.particle_size_override_enabled {
        let (_, _, min, max) = legacy_particle_size_percent_envelope(runtime_settings.visual_scale);
        return (min, max);
    }
    let base = match runtime_settings.particle_size_mode {
        PRIVATE_PARTICLE_SIZE_MODE_WORLD_METERS => runtime_settings.particle_size_world_meters,
        _ => runtime_settings.particle_size_sphere_percent,
    };
    let reciprocal = 1.0
        - (runtime_settings.particle_size_oscillation_percent / 100.0)
            .clamp(0.0, PRIVATE_PARTICLE_SIZE_MAX_OSCILLATION_PERCENT / 100.0);
    (base * reciprocal, base / reciprocal.max(0.1))
}

fn private_particle_size_marker_fields(runtime_settings: PrivateParticleRuntimeSettings) -> String {
    let (min, max) = private_particle_size_limits(runtime_settings);
    let mode = match runtime_settings.particle_size_mode {
        PRIVATE_PARTICLE_SIZE_MODE_SPHERE_PERCENT => "sphere-radius-percent",
        PRIVATE_PARTICLE_SIZE_MODE_WORLD_METERS => "world-meters",
        _ => "legacy-payload-envelope",
    };
    format!(
        "privateParticleSizeOverrideEnabled={} privateParticleSizeMode={} privateParticleSizeWorldMeters={:.4} privateParticleSizeSphereRadiusPercent={:.3} privateParticleSizeOscillationPercent={:.3} privateParticleSizeDerivedMin={:.4} privateParticleSizeDerivedMax={:.4} privateParticleSizeParameterSource={}",
        runtime_settings.particle_size_override_enabled,
        mode,
        runtime_settings.particle_size_world_meters,
        runtime_settings.particle_size_sphere_percent,
        runtime_settings.particle_size_oscillation_percent,
        min,
        max,
        crate::sanitize(runtime_settings.particle_size_parameter_source),
    )
}

fn private_particle_mask_texture_marker_fields() -> String {
    let sampler_mipmap_mode = if PRIVATE_PARTICLE_MASK_TEXTURE_MIP_LEVELS > 1 {
        "linear"
    } else {
        "nearest-base-only"
    };
    format!(
        "privateParticleMaskTextureViewType={} privateParticleMaskTextureMipMode={} privateParticleMaskTextureMipLevels={} privateParticleMaskTextureSamplerMipmapMode={} privateParticleMaskTextureImageSize={}x{}x{} privateParticleMaskTextureAtlasGrid={}x{} privateParticleMaskTextureShaderSpecialization=compile-time-mask-mode",
        crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_VIEW_TYPE),
        crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MIP_MODE),
        PRIVATE_PARTICLE_MASK_TEXTURE_MIP_LEVELS,
        sampler_mipmap_mode,
        PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_WIDTH,
        PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_HEIGHT,
        PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_LAYERS,
        PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_COLUMNS,
        PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_ROWS,
    )
}

fn private_particle_anchor_echo_draw_count(max_count: u32, draw_echo_count: u32) -> u32 {
    if max_count == 0 {
        0
    } else {
        1 + draw_echo_count.min(max_count)
    }
}

fn private_particle_anchor_echo_marker_fields(
    max_count: u32,
    draw_echo_count: u32,
    draw_count: u32,
) -> String {
    format!(
        "privateParticleAnchorEchoMaxCount={} privateParticleAnchorEchoStateCapacity={} privateParticleAnchorEchoDrawEchoCount={} privateParticleAnchorEchoDrawCount={} privateParticleAnchorEchoLifetimeSeconds={:.3} privateParticleAnchorEchoCopiesPerSecond={:.3} privateParticleAnchorEchoRadiusM={:.3} privateParticleAnchorEchoAlpha={:.3} privateParticleAnchorEchoRotationRadians={:.3} privateParticleAnchorEchoParameterSource={} privateParticleAnchorEchoStateRows={} privateParticleAnchorEchoOutputMode=merged-billboard-output-after-tracers privateParticleDrawBudgetIncludesAnchorEcho={} privateParticleAnchorEchoCpuUploadPerFrame=false",
        max_count,
        max_count,
        draw_echo_count,
        draw_count,
        PRIVATE_PARTICLE_ANCHOR_ECHO_LIFETIME_SECONDS,
        PRIVATE_PARTICLE_ANCHOR_ECHO_COPIES_PER_SECOND,
        PRIVATE_PARTICLE_ANCHOR_ECHO_RADIUS_M,
        PRIVATE_PARTICLE_ANCHOR_ECHO_ALPHA,
        PRIVATE_PARTICLE_ANCHOR_ECHO_ROTATION_RADIANS,
        crate::sanitize(PRIVATE_PARTICLE_ANCHOR_ECHO_PARAMETER_SOURCE),
        max_count * PARTICLE_ANCHOR_ECHO_STATE_ROWS_PER_SLOT as u32,
        draw_count > 0,
    )
}

fn private_particle_driver_bank_rows(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> [[f32; 4]; PRIVATE_PARTICLE_DRIVER_BANK_VEC4_ROWS] {
    let mut rows = [[0.0; 4]; PRIVATE_PARTICLE_DRIVER_BANK_VEC4_ROWS];
    for slot in 0..PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT {
        rows[slot >> 2][slot & 3] = runtime_settings.driver_bank_values01[slot];
        let control_row = PRIVATE_PARTICLE_DRIVER_BANK_VALUE_VEC4_ROWS
            + slot * PRIVATE_PARTICLE_DRIVER_CONTROL_ROWS_PER_SLOT;
        let mode = runtime_settings.driver_control_modes[slot];
        rows[control_row] = [
            mode as f32,
            runtime_settings.driver_control_source_slots[slot] as f32,
            runtime_settings.driver_control_curve_codes[slot] as f32,
            runtime_settings.driver_control_cycle_multipliers[slot],
        ];
        rows[control_row + 1] = [
            runtime_settings.driver_control_range_mins[slot],
            runtime_settings.driver_control_range_maxs[slot],
            runtime_settings.driver_values01[slot],
            if mode == PANEL_DRIVER_MODE_DIRECT {
                0.0
            } else {
                1.0
            },
        ];
        if slot == 2 && runtime_settings.particle_size_override_enabled {
            let active_base =
                if runtime_settings.particle_size_mode == PRIVATE_PARTICLE_SIZE_MODE_WORLD_METERS {
                    runtime_settings.particle_size_world_meters
                } else {
                    runtime_settings.particle_size_sphere_percent
                };
            rows[control_row + 2] = [
                1.0,
                runtime_settings.particle_size_mode as f32,
                active_base,
                runtime_settings.particle_size_oscillation_percent,
            ];
        }
    }
    rows
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
            anchor_echo_max_count: 0,
            anchor_echo_draw_echo_count: 0,
            anchor_echo_draw_count: 0,
            draw_count: 0,
            state_ping_pong: false,
            aux0_rows: 0,
            sort_active: false,
            sort_input_count: 0,
            sort_count: 0,
            sort_capacity: 0,
            world_anchor_scale_m: 0.46,
            world_anchor_scale_parameter_source: "particle-world-anchor-default",
            runtime_settings: PrivateParticleRuntimeSettings::from_generated_defaults(),
            tracer_draw_slots_capacity: 0,
            diagnostic_snapshot: PrivateParticleDiagnosticSnapshot::unavailable(),
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
    graphics_pipeline_additive: vk::Pipeline,
    graphics_pipeline_alpha_over: vk::Pipeline,
    projection_render_pass: vk::RenderPass,
    offscreen: Option<PrivateParticleOffscreenResources>,
    position_buffer: OwnedBuffer,
    normal_buffer: OwnedBuffer,
    particle_output_buffer: OwnedBuffer,
    particle_sort_buffer: OwnedBuffer,
    effect_state_buffers: [OwnedBuffer; PARTICLE_DESCRIPTOR_SET_COUNT],
    aux0_buffer: OwnedBuffer,
    driver_bank_buffer: OwnedBuffer,
    driver_bank_uploaded_rows: [[f32; 4]; PRIVATE_PARTICLE_DRIVER_BANK_VEC4_ROWS],
    diagnostic_buffers: [OwnedBuffer; PARTICLE_DESCRIPTOR_SET_COUNT],
    diagnostic_dispatched: [bool; PARTICLE_DESCRIPTOR_SET_COUNT],
    diagnostic_source_frame_ids: [Option<u64>; PARTICLE_DESCRIPTOR_SET_COUNT],
    last_diagnostic_snapshot: PrivateParticleDiagnosticSnapshot,
    mask_texture: OwnedMaskTexture,
    particle_count: u32,
    tracer_max_count: u32,
    tracer_draw_slots_per_oscillator: u32,
    anchor_echo_max_count: u32,
    anchor_echo_draw_echo_count: u32,
    anchor_echo_draw_count: u32,
    aux0_rows: u32,
    sort_input_count: u32,
    sort_capacity: u32,
    runtime_settings: PrivateParticleRuntimeSettings,
    driver_source_values01: [f32; PRIVATE_PARTICLE_DRIVER_BANK_SLOT_COUNT],
    runtime_settings_last_poll_frame: u64,
    visual_scale_request_state: PrivateParticleVisualScaleRequestState,
    heartbeat_orbit_request_state: PrivateParticleHeartbeatOrbitRequestState,
    material_request_state: PrivateParticleMaterialRequestState,
    render_experiment_request_state: PrivateParticleRenderExperimentRequestState,
    panel_settings_override: Option<GpuPrivateParticlePanelSettings>,
    pending_phase_reset_revision: i64,
    last_phase_reset_revision: i64,
    manifold_driver_bridge: Option<ManifoldScalarDriverBridge>,
    manifold_driver_connected_marker_emitted: bool,
    breath_state_driver: PrivateParticleBreathStateDriver,
    breath_state_driver_connected_marker_emitted: bool,
    breath_composition_driver: BreathCompositionDriver,
    breath_composition_driver_connected_marker_emitted: bool,
    heartbeat_pulse_adapter: PrivateParticleHeartbeatPulseAdapter,
    heartbeat_pulse_adapter_connected_marker_emitted: bool,
    heartbeat_orbit_packaged_settings: PrivateParticleHeartbeatPulseAdapterSettings,
}

impl GpuPrivateParticleRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        queue: vk::Queue,
        command_pool: vk::CommandPool,
        breath_state_driver_settings: PrivateParticleBreathStateDriverSettings,
        breath_composition_driver_settings: BreathCompositionDriverSettings,
        heartbeat_pulse_adapter_settings: PrivateParticleHeartbeatPulseAdapterSettings,
        manifold_scalar_driver_settings: ManifoldScalarDriverBridgeSettings,
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
        let anchor_echo_max_count =
            PRIVATE_PARTICLE_ANCHOR_ECHO_MAX_COUNT.min(u32::MAX as usize) as u32;
        let anchor_echo_draw_echo_count = (PRIVATE_PARTICLE_ANCHOR_ECHO_DRAW_ECHO_COUNT
            .min(u32::MAX as usize) as u32)
            .min(anchor_echo_max_count);
        let anchor_echo_draw_count = private_particle_anchor_echo_draw_count(
            anchor_echo_max_count,
            anchor_echo_draw_echo_count,
        );
        let draw_count = particle_count
            .checked_add(tracer_draw_count)
            .and_then(|value| value.checked_add(anchor_echo_draw_count))
            .ok_or_else(|| "generic private particle draw count overflowed u32".to_string())?;
        let sort_input_count = draw_count;
        let particle_output_rows = draw_count as usize * PARTICLE_OUTPUT_ROWS_PER_INSTANCE;
        let effect_state_rows = (particle_count as usize * PARTICLE_MAIN_STATE_ROWS_PER_INSTANCE)
            + (tracer_max_count as usize * PARTICLE_TRACER_STATE_ROWS_PER_SLOT)
            + (anchor_echo_max_count as usize * PARTICLE_ANCHOR_ECHO_STATE_ROWS_PER_SLOT);
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
        let runtime_settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        let breath_state_driver = PrivateParticleBreathStateDriver::new(
            breath_state_driver_settings,
            runtime_settings.driver_values01[breath_state_driver_settings.target_slot()],
        );
        let heartbeat_pulse_adapter =
            PrivateParticleHeartbeatPulseAdapter::new(heartbeat_pulse_adapter_settings);
        let breath_composition_driver =
            BreathCompositionDriver::new(breath_composition_driver_settings);
        let driver_bank_rows = private_particle_driver_bank_rows(runtime_settings);
        let driver_bank_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "generic private particle driver bank",
            &driver_bank_rows,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                aux0_buffer.destroy(device);
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
                driver_bank_buffer.destroy(device);
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
                driver_bank_buffer.destroy(device);
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
        let diagnostic_buffer_a = match OwnedBuffer::new(
            device,
            memory_properties,
            PRIVATE_PARTICLE_DIAGNOSTIC_BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "generic private particle diagnostic readback ping",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(error);
            }
        };
        let diagnostic_buffer_b = match OwnedBuffer::new(
            device,
            memory_properties,
            PRIVATE_PARTICLE_DIAGNOSTIC_BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "generic private particle diagnostic readback pong",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                diagnostic_buffer_a.destroy(device);
                destroy_buffers_mask_and_sort(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                );
                return Err(error);
            }
        };
        let diagnostic_buffers = [diagnostic_buffer_a, diagnostic_buffer_b];

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
            storage_binding(8, vk::ShaderStageFlags::COMPUTE),
            storage_binding(9, vk::ShaderStageFlags::COMPUTE),
        ];
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
                );
                return Err(format!(
                    "create generic private particle descriptor layout: {error}"
                ));
            }
        };
        let pool_sizes = [
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count((9 * PARTICLE_DESCRIPTOR_SET_COUNT) as u32),
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
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
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
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
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
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
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
            driver_bank_buffer.descriptor(),
            diagnostic_buffers[0].descriptor(),
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
            driver_bank_buffer.descriptor(),
            diagnostic_buffers[1].descriptor(),
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
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
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
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
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
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
                );
                return Err(error);
            }
        };
        let graphics_pipeline_additive = match create_graphics_pipeline(
            device,
            render_pass,
            pipeline_layout,
            PrivateParticleTransparencyBlendMode::Additive,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(sort_pipeline, None);
                device.destroy_pipeline(compute_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
                );
                return Err(error);
            }
        };
        let graphics_pipeline_alpha_over = match create_graphics_pipeline(
            device,
            render_pass,
            pipeline_layout,
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline(graphics_pipeline_additive, None);
                device.destroy_pipeline(sort_pipeline, None);
                device.destroy_pipeline(compute_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_buffers_mask_sort_and_diagnostics(
                    device,
                    &position_buffer,
                    &normal_buffer,
                    &particle_output_buffer,
                    &effect_state_buffer_a,
                    &effect_state_buffer_b,
                    &aux0_buffer,
                    &driver_bank_buffer,
                    &mask_texture,
                    &particle_sort_buffer,
                    &diagnostic_buffers,
                );
                return Err(error);
            }
        };

        let manifold_driver_bridge =
            ManifoldScalarDriverBridge::start(manifold_scalar_driver_settings);
        let sort_active = private_particle_sort_enabled();
        let marker_sort_input_count = if sort_active { sort_input_count } else { 0 };
        let marker_sort_count = if sort_active { sort_capacity } else { 0 };

        crate::marker(
            "private-particle-slot",
            format!(
                "status=linked privateParticlePayloadLinked=true privateParticleKind={} privateParticleImplementationPath={} privateParticleDataPath={} privateParticleCount={} privateParticleMainCount={} privateParticleDrawCount={} privateParticleVisualScale={:.3} privateParticleVisualParameterSource={} privateParticleDriver0Value01={:.3} privateParticleDriver1Value01={:.3} {} privateParticleDriverParameterSource={} privateParticleTracerMaxCount={} privateParticleTracerStateCapacity={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawCount={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTracerStateRows={} privateParticleTracerRadiusPolicy=snapshot-source-radius privateParticleTracerOutputMode=merged-billboard-output privateParticleDrawBudgetIncludesTracers={} privateParticleTracerCpuUploadPerFrame=false {} privateParticleStaticPositionBytes={} privateParticleStaticNormalBytes={} privateParticleAux0Bytes={} privateParticleAux0Rows={} privateParticleStateBufferBytes={} privateParticleStatePingPong=true privateParticleOutputBufferBytes={} privateParticleOutputAbi=four-vec4-billboard-rows privateParticleBillboardKindAux=aux.z-main-1-tracer-2-anchor-3-anchor_echo-4 privateParticleOrderingMode={} privateParticleOrderingImplementation={} privateParticleOrderingParameterSource={} privateParticleOrderingBasis=per-eye-openxr-reference-space privateParticleSortActive={} privateParticleSortInputCount={} privateParticleSortCount={} privateParticleSortCapacity={} privateParticleSortBufferBytes={} privateParticleOrderingCpuExpandedUploadPerFrame=false {} privateParticleMaterialPipelines=additive,alpha-over privateParticleMaskTextureLinked={} privateParticleMaskTextureMode={} privateParticleMaskDiscardMode={} privateParticleMaskAlphaCutoff={:.4} privateParticleMaskTexturePath={} privateParticleMaskTextureFormat=R8_UNORM privateParticleMaskTextureSize={}x{}x{} privateParticleMaskTextureBytes={} privateParticleMaskTextureGpuResident=true {} {} privateParticleCpuUploadBytes=0 privateParticleGpuBuffersResident=true privateParticleVisualAcceptance=pending-headset-screenshot",
                crate::sanitize(PRIVATE_PARTICLE_KIND),
                crate::sanitize(PRIVATE_PARTICLE_IMPLEMENTATION_PATH),
                crate::sanitize(PRIVATE_PARTICLE_DATA_PATH),
                particle_count,
                particle_count,
                draw_count,
                PRIVATE_PARTICLE_VISUAL_SCALE,
                crate::sanitize(PRIVATE_PARTICLE_VISUAL_PARAMETER_SOURCE),
                PRIVATE_PARTICLE_DRIVER0_VALUE01,
                PRIVATE_PARTICLE_DRIVER1_VALUE01,
                private_particle_driver_bank_marker_fields(runtime_settings),
                crate::sanitize(PRIVATE_PARTICLE_DRIVER_PARAMETER_SOURCE),
                tracer_max_count,
                tracer_max_count,
                tracer_draw_slots_per_oscillator,
                tracer_draw_count,
                PRIVATE_PARTICLE_TRACER_LIFETIME_SECONDS,
                PRIVATE_PARTICLE_TRACER_COPIES_PER_SECOND,
                crate::sanitize(PRIVATE_PARTICLE_TRACER_PARAMETER_SOURCE),
                tracer_max_count * PARTICLE_TRACER_STATE_ROWS_PER_SLOT as u32,
                tracer_draw_count > 0,
                private_particle_anchor_echo_marker_fields(
                    anchor_echo_max_count,
                    anchor_echo_draw_echo_count,
                    anchor_echo_draw_count,
                ),
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
                PrivateParticleDiagnosticSnapshot::pending().marker_fields(0),
                PRIVATE_PARTICLE_MASK_TEXTURE_LINKED,
                crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MODE),
                crate::sanitize(PRIVATE_PARTICLE_MASK_DISCARD_MODE),
                PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF,
                crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_PATH),
                PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
                PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
                PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
                PRIVATE_PARTICLE_MASK_TEXTURE_BYTES,
                private_particle_mask_texture_marker_fields(),
                private_particle_offscreen_marker_fields(runtime_settings),
            ),
        );
        crate::marker(
            "private-particle-breath-driver",
            format!("status=config {}", breath_state_driver.marker_fields()),
        );
        crate::marker(
            "breath-composition-driver",
            format!(
                "status=config {}",
                breath_composition_driver.marker_fields()
            ),
        );
        crate::marker(
            "private-particle-heartbeat-pulse",
            format!("status=config {}", heartbeat_pulse_adapter.marker_fields()),
        );
        let startup_world_anchor_stats = GpuPrivateParticleFrameStats::default();
        log_private_marker(
            "created",
            0,
            particle_count,
            tracer_max_count,
            tracer_draw_count,
            tracer_draw_slots_per_oscillator,
            anchor_echo_max_count,
            anchor_echo_draw_echo_count,
            anchor_echo_draw_count,
            draw_count,
            aux0_rows,
            marker_sort_input_count,
            marker_sort_count,
            sort_capacity,
            tracer_draw_slots_per_oscillator,
            startup_world_anchor_stats.world_anchor_scale_m,
            startup_world_anchor_stats.world_anchor_scale_parameter_source,
            runtime_settings,
            PrivateParticleDiagnosticSnapshot::pending(),
        );

        let heartbeat_orbit_packaged_settings = heartbeat_pulse_adapter.settings();
        Ok(Some(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_sets,
            pipeline_layout,
            compute_pipeline,
            sort_pipeline,
            graphics_pipeline_additive,
            graphics_pipeline_alpha_over,
            projection_render_pass: render_pass,
            offscreen: None,
            position_buffer,
            normal_buffer,
            particle_output_buffer,
            particle_sort_buffer,
            effect_state_buffers: [effect_state_buffer_a, effect_state_buffer_b],
            aux0_buffer,
            driver_bank_buffer,
            driver_bank_uploaded_rows: driver_bank_rows,
            diagnostic_buffers,
            diagnostic_dispatched: [false; PARTICLE_DESCRIPTOR_SET_COUNT],
            diagnostic_source_frame_ids: [None; PARTICLE_DESCRIPTOR_SET_COUNT],
            last_diagnostic_snapshot: PrivateParticleDiagnosticSnapshot::pending(),
            mask_texture,
            particle_count,
            tracer_max_count,
            tracer_draw_slots_per_oscillator,
            anchor_echo_max_count,
            anchor_echo_draw_echo_count,
            anchor_echo_draw_count,
            aux0_rows,
            sort_input_count,
            sort_capacity,
            runtime_settings,
            driver_source_values01: runtime_settings.driver_bank_values01,
            runtime_settings_last_poll_frame: u64::MAX,
            visual_scale_request_state: PrivateParticleVisualScaleRequestState::default(),
            heartbeat_orbit_request_state: PrivateParticleHeartbeatOrbitRequestState::default(),
            material_request_state: PrivateParticleMaterialRequestState::default(),
            render_experiment_request_state: PrivateParticleRenderExperimentRequestState::default(),
            panel_settings_override: None,
            pending_phase_reset_revision: 0,
            last_phase_reset_revision: 0,
            manifold_driver_bridge,
            manifold_driver_connected_marker_emitted: false,
            breath_state_driver,
            breath_state_driver_connected_marker_emitted: false,
            breath_composition_driver,
            breath_composition_driver_connected_marker_emitted: false,
            heartbeat_pulse_adapter,
            heartbeat_pulse_adapter_connected_marker_emitted: false,
            heartbeat_orbit_packaged_settings,
        }))
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(offscreen) = self.offscreen.take() {
            offscreen.destroy(device);
        }
        for buffer in &self.diagnostic_buffers {
            buffer.destroy(device);
        }
        self.mask_texture.destroy(device);
        self.driver_bank_buffer.destroy(device);
        self.aux0_buffer.destroy(device);
        for buffer in &self.effect_state_buffers {
            buffer.destroy(device);
        }
        self.particle_sort_buffer.destroy(device);
        self.particle_output_buffer.destroy(device);
        self.normal_buffer.destroy(device);
        self.position_buffer.destroy(device);
        device.destroy_pipeline(self.graphics_pipeline_alpha_over, None);
        device.destroy_pipeline(self.graphics_pipeline_additive, None);
        device.destroy_pipeline(self.sort_pipeline, None);
        device.destroy_pipeline(self.compute_pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
    }

    pub(crate) fn begin_runtime_session(&mut self) {
        self.visual_scale_request_state.begin_session();
        self.heartbeat_orbit_request_state.begin_session();
        self.material_request_state.begin_session();
        self.render_experiment_request_state.begin_session();
        let heartbeat_settings =
            self.panel_settings_override
                .map_or(self.heartbeat_orbit_packaged_settings, |panel| {
                    PrivateParticleHeartbeatPulseAdapterSettings::panel_polar_rr_orbit_boost(
                        panel.polar_rr_orbit_boost_enabled,
                    )
                });
        if self.heartbeat_pulse_adapter.settings() != heartbeat_settings {
            self.heartbeat_pulse_adapter.reconfigure(heartbeat_settings);
            self.heartbeat_pulse_adapter_connected_marker_emitted = false;
        }
        self.runtime_settings_last_poll_frame = u64::MAX;
        crate::marker(
            "private-particle-visual-scale-request",
            format!(
                "status=session-ready {}",
                self.visual_scale_request_state.session_marker_fields()
            ),
        );
        crate::marker(
            "private-particle-heartbeat-orbit-request",
            format!(
                "status=session-ready {}",
                self.heartbeat_orbit_request_state.session_marker_fields()
            ),
        );
        crate::marker(
            "private-particle-material-request",
            format!(
                "status=session-ready {}",
                self.material_request_state.session_marker_fields()
            ),
        );
        crate::marker(
            "private-particle-render-experiment-request",
            format!(
                "status=session-ready {}",
                self.render_experiment_request_state.session_marker_fields()
            ),
        );
    }

    pub(crate) fn confirm_submitted_frame(
        &mut self,
        frame_count: u64,
        stats: GpuPrivateParticleFrameStats,
    ) {
        if !stats.ready || !stats.visible || stats.draw_count == 0 {
            return;
        }
        if let Some(receipt) = self
            .visual_scale_request_state
            .confirm_submitted_frame(frame_count)
        {
            crate::marker(
                "private-particle-visual-scale-request",
                format!("status=effective {}", receipt.marker_fields()),
            );
        }
        if let Some(receipt) = self
            .heartbeat_orbit_request_state
            .confirm_submitted_frame(frame_count)
        {
            crate::marker(
                "private-particle-heartbeat-orbit-request",
                format!("status=effective {}", receipt.marker_fields()),
            );
        }
        if let Some(receipt) = self
            .material_request_state
            .confirm_submitted_frame(frame_count)
        {
            crate::marker(
                "private-particle-material-request",
                format!(
                    "status=effective {} {}",
                    receipt.marker_fields(),
                    private_particle_material_effective_marker_fields(stats.runtime_settings)
                ),
            );
        }
        if let Some(receipt) = self
            .render_experiment_request_state
            .confirm_submitted_frame(frame_count)
        {
            crate::marker(
                "private-particle-render-experiment-request",
                format!("status=effective {}", receipt.marker_fields()),
            );
        }
    }

    pub(crate) unsafe fn collect_completed_diagnostics(
        &mut self,
        device: &ash::Device,
        frame_slot: usize,
    ) {
        let diagnostic_slot = frame_slot % PARTICLE_DESCRIPTOR_SET_COUNT;
        if !self.diagnostic_dispatched[diagnostic_slot] {
            self.last_diagnostic_snapshot = PrivateParticleDiagnosticSnapshot::pending();
            return;
        }
        match self.diagnostic_buffers[diagnostic_slot]
            .read_i32_words::<PRIVATE_PARTICLE_DIAGNOSTIC_WORDS>(
                device,
                "generic private particle diagnostic readback",
            ) {
            Ok(raw) => {
                self.last_diagnostic_snapshot = PrivateParticleDiagnosticSnapshot::from_raw(
                    raw,
                    self.diagnostic_source_frame_ids[diagnostic_slot],
                );
            }
            Err(error) => {
                crate::marker(
                    "private-particle-slot",
                    format!(
                        "status=diagnostic-readback-failed frameSlot={} error={}",
                        frame_slot,
                        crate::sanitize(&error)
                    ),
                );
                self.last_diagnostic_snapshot = PrivateParticleDiagnosticSnapshot::pending();
            }
        }
        self.diagnostic_dispatched[diagnostic_slot] = false;
        self.diagnostic_source_frame_ids[diagnostic_slot] = None;
    }

    pub(crate) fn update_breath_state_driver(
        &mut self,
        sample: Option<NativeControllerBreathSample>,
        dt_seconds: f32,
        frame_count: u64,
    ) {
        if !self.breath_state_driver.enabled() {
            return;
        }
        if let Some(sample) = sample {
            self.breath_state_driver.apply_sample(sample);
        }
        self.breath_state_driver.update_frame(dt_seconds);
        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "private-particle-breath-driver",
                format!(
                    "status=updated frame={} {}",
                    frame_count,
                    self.breath_state_driver.marker_fields()
                ),
            );
        }
    }

    pub(crate) fn update_heartbeat_pulse_adapter(&mut self, dt_seconds: f32, frame_count: u64) {
        if !self.heartbeat_pulse_adapter.enabled() {
            return;
        }
        self.heartbeat_pulse_adapter.update_frame(dt_seconds);
        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "private-particle-heartbeat-pulse",
                format!(
                    "status=updated frame={} {}",
                    frame_count,
                    self.heartbeat_pulse_adapter.marker_fields()
                ),
            );
        }
    }

    pub(crate) fn update_breath_composition_driver(
        &mut self,
        snapshot: rusty_quest_breath_contract::composition::BreathCompositionSnapshot,
        observed_at: rusty_quest_breath_contract::BreathTimestampMicros,
        dt_seconds: f32,
        frame_count: u64,
    ) {
        let settings = self.breath_composition_driver.settings();
        self.breath_composition_driver
            .update_frame(settings, snapshot, observed_at, dt_seconds);
        crate::breath_capture::record_driver_apply(
            frame_count,
            observed_at.get(),
            settings.target_slot(),
            self.breath_composition_driver.value01(),
            snapshot,
        );
        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "breath-composition-driver",
                format!(
                    "status=updated frame={} {}",
                    frame_count,
                    self.breath_composition_driver.marker_fields()
                ),
            );
        }
    }

    pub(crate) unsafe fn record_compute_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        gpu_timestamp_tracker: &GpuTimestampTracker,
        frame_slot: usize,
        eye_projections: PrivateParticleFrameEyeProjections<
            HandMeshVisualEyeProjection,
            HandMeshVisualEyeProjection,
        >,
        world_center_scale: [f32; 4],
        world_anchor_scale_parameter_source: &'static str,
        world_anchor_forward_axis: [f32; 4],
        frame_count: u64,
    ) -> GpuPrivateParticleFrameStats {
        let runtime_settings = self.runtime_settings(frame_count);
        let driver_bank_rows = private_particle_driver_bank_rows(runtime_settings);
        if driver_bank_rows != self.driver_bank_uploaded_rows {
            match self.driver_bank_buffer.write_data(
                device,
                "generic private particle driver bank",
                &driver_bank_rows,
            ) {
                Ok(()) => {
                    self.driver_bank_uploaded_rows = driver_bank_rows;
                }
                Err(error) => {
                    crate::marker(
                        "private-particle-slot",
                        format!(
                            "status=driver-bank-update-failed frame={} error={}",
                            frame_count,
                            crate::sanitize(&error)
                        ),
                    );
                }
            }
        }
        let surface_draw_enabled = private_particle_payload_surface_draw_enabled(runtime_settings);
        let tracer_draw_slots_per_oscillator = if surface_draw_enabled {
            runtime_settings
                .tracer_draw_slots_per_oscillator
                .min(self.tracer_draw_slots_per_oscillator)
        } else {
            0
        };
        let tracer_draw_count = if surface_draw_enabled {
            self.particle_count
                .saturating_mul(tracer_draw_slots_per_oscillator)
        } else {
            0
        };
        let draw_count = if surface_draw_enabled {
            self.particle_count
                .saturating_add(tracer_draw_count)
                .saturating_add(self.anchor_echo_draw_count)
        } else {
            0
        };
        let descriptor_index = frame_count as usize & 1;
        let next_descriptor_index = (descriptor_index + 1) & 1;
        let diagnostic_buffer = &self.diagnostic_buffers[descriptor_index];
        let phase_reset = self.pending_phase_reset_revision > 0
            && self.pending_phase_reset_revision != self.last_phase_reset_revision;
        if phase_reset {
            self.last_phase_reset_revision = self.pending_phase_reset_revision;
        }
        let push = private_particle_push(
            self.particle_count,
            draw_count,
            self.tracer_max_count,
            runtime_settings,
            eye_projections.compute_basis,
            world_center_scale,
            frame_count,
            phase_reset,
            Some(world_anchor_forward_axis),
        );
        device.cmd_fill_buffer(
            cmd,
            diagnostic_buffer.buffer,
            0,
            PRIVATE_PARTICLE_DIAGNOSTIC_BYTES,
            0,
        );
        let compute_write_barrier = [
            transfer_write_to_shader_write_barrier(diagnostic_buffer),
            storage_to_compute_read_barrier(&self.position_buffer),
            storage_to_compute_read_barrier(&self.normal_buffer),
            storage_to_compute_read_barrier(&self.aux0_buffer),
            storage_to_compute_read_barrier(&self.driver_bank_buffer),
            storage_to_compute_read_barrier(&self.effect_state_buffers[descriptor_index]),
            shader_to_compute_write_barrier(&self.effect_state_buffers[next_descriptor_index]),
            shader_to_compute_write_barrier(&self.particle_output_buffer),
        ];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::HOST
                | vk::PipelineStageFlags::TRANSFER
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
        gpu_timestamp_tracker.write_compute_stage_start(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleComputeDispatch,
        );
        device.cmd_dispatch(
            cmd,
            self.particle_count.div_ceil(PARTICLE_COMPUTE_LOCAL_SIZE),
            1,
            1,
        );
        gpu_timestamp_tracker.write_compute_stage_end(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleComputeDispatch,
        );
        gpu_timestamp_tracker.write_compute_stage_start(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleComputePostDispatchSync,
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
        let diagnostic_to_host = [compute_write_to_host_read_barrier(diagnostic_buffer)];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::HOST,
            vk::DependencyFlags::empty(),
            &[],
            &diagnostic_to_host,
            &[],
        );
        self.diagnostic_dispatched[descriptor_index] = true;
        self.diagnostic_source_frame_ids[descriptor_index] = Some(frame_count);
        gpu_timestamp_tracker.write_stage_end(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleComputePostDispatchSync,
        );
        gpu_timestamp_tracker.write_stage_end(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleCompute,
        );
        let sort_active = private_particle_sort_enabled() && draw_count > 0;
        let sort_count = if sort_active {
            gpu_timestamp_tracker.write_stage_start(
                device,
                cmd,
                frame_slot,
                GpuTimestampStage::PrivateParticleSort,
            );
            let sort_count =
                self.record_sort_frame(device, cmd, eye_projections.sort_eye, draw_count);
            gpu_timestamp_tracker.write_stage_end(
                device,
                cmd,
                frame_slot,
                GpuTimestampStage::PrivateParticleSort,
            );
            sort_count
        } else {
            gpu_timestamp_tracker.write_disabled_stage(
                device,
                cmd,
                frame_slot,
                GpuTimestampStage::PrivateParticleSort,
            );
            0
        };
        let stats = GpuPrivateParticleFrameStats {
            ready: true,
            visible: surface_draw_enabled && draw_count > 0,
            particle_count: self.particle_count,
            main_particle_count: self.particle_count,
            tracer_max_count: self.tracer_max_count,
            tracer_draw_count,
            tracer_draw_slots_per_oscillator,
            anchor_echo_max_count: self.anchor_echo_max_count,
            anchor_echo_draw_echo_count: self.anchor_echo_draw_echo_count,
            anchor_echo_draw_count: self.anchor_echo_draw_count,
            draw_count,
            state_ping_pong: true,
            aux0_rows: self.aux0_rows,
            sort_active,
            sort_input_count: if sort_active { draw_count } else { 0 },
            sort_count,
            sort_capacity: self.sort_capacity,
            world_anchor_scale_m: world_center_scale[3],
            world_anchor_scale_parameter_source,
            runtime_settings,
            tracer_draw_slots_capacity: self.tracer_draw_slots_per_oscillator,
            diagnostic_snapshot: self.last_diagnostic_snapshot,
        };
        self.visual_scale_request_state
            .note_renderer_prepared_frame(
                frame_count,
                runtime_settings.visual_scale,
                stats.ready && stats.visible && stats.draw_count > 0,
            );
        self.material_request_state.note_renderer_prepared_frame(
            frame_count,
            runtime_settings.material_preset,
            stats.ready && stats.visible && stats.draw_count > 0,
        );
        self.render_experiment_request_state
            .note_renderer_prepared_frame(
                frame_count,
                runtime_settings.render_experiment_preset,
                stats.ready && stats.visible && stats.draw_count > 0,
            );
        let heartbeat_orbit_enabled = self.heartbeat_orbit_enabled();
        self.heartbeat_orbit_request_state
            .note_renderer_prepared_frame(
                frame_count,
                heartbeat_orbit_enabled,
                stats.ready && stats.visible && stats.draw_count > 0,
            );

        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "private-particle-slot",
                format!(
                    "status=compute frame={} {}",
                    frame_count,
                    stats.marker_fields(frame_count)
                ),
            );
            log_private_marker(
                "running",
                frame_count,
                self.particle_count,
                self.tracer_max_count,
                tracer_draw_count,
                tracer_draw_slots_per_oscillator,
                self.anchor_echo_max_count,
                self.anchor_echo_draw_echo_count,
                self.anchor_echo_draw_count,
                draw_count,
                self.aux0_rows,
                draw_count,
                sort_count,
                self.sort_capacity,
                self.tracer_draw_slots_per_oscillator,
                world_center_scale[3],
                world_anchor_scale_parameter_source,
                runtime_settings,
                self.last_diagnostic_snapshot,
            );
        }
        stats
    }

    pub(crate) fn apply_panel_settings(
        &mut self,
        settings: GpuPrivateParticlePanelSettings,
        frame_count: u64,
        revision: i64,
    ) -> GpuPrivateParticlePanelEffectiveSettings {
        let settings = settings.clamped();
        if settings.update_mask.polar_rr_orbit_boost {
            self.heartbeat_orbit_request_state
                .clear_request_for_panel_authority();
            let heartbeat_settings =
                PrivateParticleHeartbeatPulseAdapterSettings::panel_polar_rr_orbit_boost(
                    settings.polar_rr_orbit_boost_enabled,
                );
            if self.heartbeat_pulse_adapter.settings() != heartbeat_settings {
                self.heartbeat_pulse_adapter.reconfigure(heartbeat_settings);
                self.heartbeat_pulse_adapter_connected_marker_emitted = false;
            }
        }
        if let Some(existing) = &mut self.panel_settings_override {
            existing.merge_patch(settings);
        } else {
            self.panel_settings_override = Some(settings);
        }
        if settings.update_mask.full_snapshot && revision > 0 {
            self.pending_phase_reset_revision = revision;
        }
        self.runtime_settings_last_poll_frame = u64::MAX;
        let runtime_settings = self.runtime_settings(frame_count);
        let (particle_size_min, particle_size_max) = private_particle_size_limits(runtime_settings);
        GpuPrivateParticlePanelEffectiveSettings {
            visual_scale: runtime_settings.visual_scale,
            particle_size_override_enabled: runtime_settings.particle_size_override_enabled,
            particle_size_mode: runtime_settings.particle_size_mode,
            particle_size_world_meters: runtime_settings.particle_size_world_meters,
            particle_size_sphere_percent: runtime_settings.particle_size_sphere_percent,
            particle_size_oscillation_percent: runtime_settings.particle_size_oscillation_percent,
            particle_size_min,
            particle_size_max,
            particle_size_parameter_source: runtime_settings.particle_size_parameter_source,
            driver_values01: runtime_settings.driver_values01,
            driver_control_modes: runtime_settings.driver_control_modes,
            driver_control_source_slots: runtime_settings.driver_control_source_slots,
            driver_control_curve_codes: runtime_settings.driver_control_curve_codes,
            driver_control_range_mins: runtime_settings.driver_control_range_mins,
            driver_control_range_maxs: runtime_settings.driver_control_range_maxs,
            driver_control_cycle_multipliers: runtime_settings.driver_control_cycle_multipliers,
            driver_parameter_source: runtime_settings.driver_parameter_source,
            tracer_draw_slots_per_oscillator: runtime_settings
                .tracer_draw_slots_per_oscillator
                .min(self.tracer_draw_slots_per_oscillator),
            tracer_draw_slots_capacity: self.tracer_draw_slots_per_oscillator,
            tracer_lifetime_seconds: runtime_settings.tracer_lifetime_seconds,
            tracer_copies_per_second: runtime_settings.tracer_copies_per_second,
            tracer_parameter_source: runtime_settings.tracer_parameter_source,
            transparency_opacity: runtime_settings.transparency_opacity,
            transparency_output_alpha_scale: runtime_settings.transparency_output_alpha_scale,
            transparency_depth_suppression_strength: runtime_settings
                .transparency_depth_suppression_strength,
            transparency_rgb_alpha_coupling: runtime_settings.transparency_rgb_alpha_coupling,
            transparency_parameter_source: runtime_settings.transparency_parameter_source,
            color_facing_attenuation_strength: runtime_settings.color_facing_attenuation_strength,
            color_parameter_source: runtime_settings.color_parameter_source,
            material_preset: runtime_settings.material_preset,
            material_parameter_source: runtime_settings.material_parameter_source,
            polar_rr_orbit_boost_enabled: self.heartbeat_orbit_enabled(),
            heartbeat_pulse_parameter_source: self
                .heartbeat_pulse_adapter
                .settings()
                .parameter_source(),
        }
    }

    fn emit_breath_state_driver_connected_marker(&mut self, frame_count: u64) {
        if self.breath_state_driver_connected_marker_emitted {
            return;
        }
        crate::marker(
            "private-particle-breath-driver",
            format!(
                "status=connected frame={} privateParticleDriverParameterSource={} privateParticleBreathStateDriverReceipt=render-thread-applied-sample {}",
                frame_count,
                crate::sanitize(self.breath_state_driver.settings().parameter_source()),
                self.breath_state_driver.marker_fields(),
            ),
        );
        self.breath_state_driver_connected_marker_emitted = true;
    }

    fn emit_heartbeat_pulse_adapter_connected_marker(&mut self, frame_count: u64) {
        if self.heartbeat_pulse_adapter_connected_marker_emitted {
            return;
        }
        crate::marker(
            "private-particle-heartbeat-pulse",
            format!(
                "status=connected frame={} privateParticleDriverParameterSource={} privateParticleHeartbeatPulseReceipt=render-thread-applied-event {}",
                frame_count,
                crate::sanitize(self.heartbeat_pulse_adapter.settings().parameter_source()),
                self.heartbeat_pulse_adapter.marker_fields(),
            ),
        );
        self.heartbeat_pulse_adapter_connected_marker_emitted = true;
    }

    fn emit_breath_composition_driver_connected_marker(&mut self, frame_count: u64) {
        if self.breath_composition_driver_connected_marker_emitted {
            return;
        }
        crate::marker(
            "breath-composition-driver",
            format!(
                "status=connected frame={} breathCompositionDriverReceipt=render-thread-applied-neutral-slot {}",
                frame_count,
                self.breath_composition_driver.marker_fields(),
            ),
        );
        self.breath_composition_driver_connected_marker_emitted = true;
    }

    fn runtime_settings(&mut self, frame_count: u64) -> PrivateParticleRuntimeSettings {
        let has_input_driver = self
            .panel_settings_override
            .as_ref()
            .is_some_and(panel_requires_input_driver_update);
        let should_poll = self.runtime_settings_last_poll_frame == u64::MAX
            || frame_count.saturating_sub(self.runtime_settings_last_poll_frame)
                >= PRIVATE_PARTICLE_SETTINGS_POLL_INTERVAL_FRAMES;
        if should_poll {
            let mut next = PrivateParticleRuntimeSettings::load_from_android_properties();
            let material_defaults = next.material_defaults();
            match self
                .heartbeat_orbit_request_state
                .observe_property(private_particle_heartbeat_orbit_request_property())
            {
                PrivateParticleHeartbeatOrbitRequestObservation::NoChange => {}
                PrivateParticleHeartbeatOrbitRequestObservation::Accepted(request) => {
                    let settings = request.override_value().map_or_else(
                        || {
                            self.panel_settings_override
                                .filter(|panel| panel.update_mask.polar_rr_orbit_boost)
                                .map_or(self.heartbeat_orbit_packaged_settings, |panel| {
                                    PrivateParticleHeartbeatPulseAdapterSettings::panel_polar_rr_orbit_boost(
                                        panel.polar_rr_orbit_boost_enabled,
                                    )
                                })
                        },
                        PrivateParticleHeartbeatPulseAdapterSettings::panel_polar_rr_orbit_boost,
                    );
                    if self.heartbeat_pulse_adapter.settings() != settings {
                        self.heartbeat_pulse_adapter.reconfigure(settings);
                        self.heartbeat_pulse_adapter_connected_marker_emitted = false;
                    }
                    crate::marker(
                        "private-particle-heartbeat-orbit-request",
                        format!(
                            "status=accepted frame={} {}",
                            frame_count,
                            request.marker_fields("accepted")
                        ),
                    );
                }
                PrivateParticleHeartbeatOrbitRequestObservation::Rejected(rejection) => {
                    crate::marker(
                        "private-particle-heartbeat-orbit-request",
                        format!(
                            "status=rejected frame={} {}",
                            frame_count,
                            rejection.marker_fields()
                        ),
                    );
                }
            }
            match self
                .visual_scale_request_state
                .observe_property(private_particle_visual_scale_request_property())
            {
                PrivateParticleVisualScaleRequestObservation::NoChange => {}
                PrivateParticleVisualScaleRequestObservation::Accepted(request) => {
                    crate::marker(
                        "private-particle-visual-scale-request",
                        format!(
                            "status=accepted frame={} {}",
                            frame_count,
                            request.marker_fields("accepted")
                        ),
                    );
                }
                PrivateParticleVisualScaleRequestObservation::Rejected(rejection) => {
                    crate::marker(
                        "private-particle-visual-scale-request",
                        format!(
                            "status=rejected frame={} {}",
                            frame_count,
                            rejection.marker_fields()
                        ),
                    );
                }
            }
            match self
                .material_request_state
                .observe_property(private_particle_material_request_property())
            {
                PrivateParticleMaterialRequestObservation::NoChange => {}
                PrivateParticleMaterialRequestObservation::Accepted(request) => {
                    crate::marker(
                        "private-particle-material-request",
                        format!(
                            "status=accepted frame={} {}",
                            frame_count,
                            request.marker_fields("accepted")
                        ),
                    );
                }
                PrivateParticleMaterialRequestObservation::Rejected(rejection) => {
                    crate::marker(
                        "private-particle-material-request",
                        format!(
                            "status=rejected frame={} {}",
                            frame_count,
                            rejection.marker_fields()
                        ),
                    );
                }
            }
            match self.render_experiment_request_state.observe_property(
                private_particle_render_experiment_request_property(),
                private_particle_static_ring_frame_zero_enabled(),
            ) {
                PrivateParticleRenderExperimentRequestObservation::NoChange => {}
                PrivateParticleRenderExperimentRequestObservation::Accepted(request) => {
                    crate::marker(
                        "private-particle-render-experiment-request",
                        format!(
                            "status=accepted frame={} {}",
                            frame_count,
                            request.marker_fields("accepted")
                        ),
                    );
                }
                PrivateParticleRenderExperimentRequestObservation::Rejected(rejection) => {
                    crate::marker(
                        "private-particle-render-experiment-request",
                        format!(
                            "status=rejected frame={} {}",
                            frame_count,
                            rejection.marker_fields()
                        ),
                    );
                }
            }
            let mut driver_parameter_source = next.driver_parameter_source;
            let manifold_driver_active_count =
                self.manifold_driver_bridge.as_ref().map_or(0, |bridge| {
                    bridge.apply_to_driver_values(&mut next.driver_values01)
                });
            if manifold_driver_active_count > 0 {
                driver_parameter_source = "manifold-scalar-stream";
                if !self.manifold_driver_connected_marker_emitted {
                    crate::marker(
                        "private-particle-slot",
                        format!(
                            "status=manifold-driver-connected frame={} privateParticleDriverParameterSource=manifold-scalar-stream privateParticleManifoldDriverActiveSamples={} privateParticleManifoldDriverReceipt=render-thread-applied-sample",
                            frame_count,
                            manifold_driver_active_count,
                        ),
                    );
                    self.manifold_driver_connected_marker_emitted = true;
                }
            }
            if self
                .breath_state_driver
                .apply_to_driver_values(&mut next.driver_values01)
            {
                driver_parameter_source = self.breath_state_driver.settings().parameter_source();
                self.emit_breath_state_driver_connected_marker(frame_count);
            }
            if self
                .breath_composition_driver
                .apply_to_driver_values(&mut next.driver_values01)
            {
                driver_parameter_source = "breath-composition-closed-world";
                self.emit_breath_composition_driver_connected_marker(frame_count);
            }
            let lsl_inlet_active_count =
                crate::lsl_panel_runtime::apply_inlet_driver_values(&mut next.driver_values01);
            if lsl_inlet_active_count > 0 {
                driver_parameter_source = "lsl-float32-inlet";
            }
            if self
                .heartbeat_pulse_adapter
                .apply_to_driver_values(&mut next.driver_values01)
            {
                driver_parameter_source =
                    self.heartbeat_pulse_adapter.settings().parameter_source();
                self.emit_heartbeat_pulse_adapter_connected_marker(frame_count);
            }
            next.apply_driver_source_values(next.driver_values01, driver_parameter_source);
            self.driver_source_values01 = next.driver_bank_values01;
            if let Some(panel_override) = self.panel_settings_override {
                next.apply_panel_override(panel_override, self.driver_source_values01);
            }
            next.apply_heartbeat_orbit_control(self.heartbeat_orbit_control());
            if let Some(scale) = self.visual_scale_request_state.active_scale() {
                next.visual_scale = scale;
                next.visual_parameter_source = "runtime-fenced-visual-scale-request-v1";
            }
            if let Some(preset) = self.material_request_state.active_preset() {
                next.apply_material_preset(preset);
            }
            if let Some(panel_override) = self.panel_settings_override {
                next.apply_panel_material_override(panel_override, material_defaults);
            }
            if let Some(preset) = self.render_experiment_request_state.active_preset() {
                next.apply_render_experiment_preset(preset);
            }
            if next != self.runtime_settings {
                crate::marker(
                    "private-particle-slot",
                    format!(
                        "status=hotload-applied frame={} privateParticleSettingsHotload=true privateParticleVisualScale={:.3} privateParticleVisualParameterSource={} privateParticleDriver0Value01={:.3} privateParticleDriver1Value01={:.3} {} privateParticleDriverParameterSource={} privateParticleManifoldDriverActiveSamples={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawSlotsCapacity={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTransparencyOpacity={:.3} privateParticleTransparencyOutputAlphaScale={:.3} privateParticleTransparencyDepthSuppressionStrength={:.3} privateParticleTransparencyRgbAlphaCoupling={:.3} privateParticleTransparencyParameterSource={} privateParticleColorFacingAttenuationStrength={:.3} privateParticleColorParameterSource={} {}",
                        frame_count,
                        next.visual_scale,
                        crate::sanitize(next.visual_parameter_source),
                        next.driver0_value01,
                        next.driver1_value01,
                        private_particle_driver_bank_marker_fields(next),
                        crate::sanitize(next.driver_parameter_source),
                        manifold_driver_active_count,
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
                        crate::sanitize(next.color_parameter_source),
                        private_particle_offscreen_marker_fields(next)
                    ),
                );
            }
            self.runtime_settings = next;
            self.runtime_settings_last_poll_frame = frame_count;
        } else if has_input_driver
            || self.breath_state_driver.enabled()
            || self.breath_composition_driver.settings().enabled()
            || self.heartbeat_pulse_adapter.enabled()
        {
            let mut next = self.runtime_settings;
            let mut driver_parameter_source = next.driver_parameter_source;
            if let Some(bridge) = self.manifold_driver_bridge.as_ref() {
                if bridge.apply_to_driver_values(&mut self.driver_source_values01) > 0 {
                    driver_parameter_source = "manifold-scalar-stream";
                }
            }
            if self
                .breath_state_driver
                .apply_to_driver_values(&mut self.driver_source_values01)
            {
                driver_parameter_source = self.breath_state_driver.settings().parameter_source();
                self.emit_breath_state_driver_connected_marker(frame_count);
            }
            if self
                .breath_composition_driver
                .apply_to_driver_values(&mut self.driver_source_values01)
            {
                driver_parameter_source = "breath-composition-closed-world";
                self.emit_breath_composition_driver_connected_marker(frame_count);
            }
            if crate::lsl_panel_runtime::apply_inlet_driver_values(&mut self.driver_source_values01)
                > 0
            {
                driver_parameter_source = "lsl-float32-inlet";
            }
            if self
                .heartbeat_pulse_adapter
                .apply_to_driver_values(&mut self.driver_source_values01)
            {
                driver_parameter_source =
                    self.heartbeat_pulse_adapter.settings().parameter_source();
                self.emit_heartbeat_pulse_adapter_connected_marker(frame_count);
            }
            next.apply_driver_source_values(self.driver_source_values01, driver_parameter_source);
            if let Some(panel_override) = self.panel_settings_override {
                next.apply_panel_override(panel_override, self.driver_source_values01);
            }
            next.apply_heartbeat_orbit_control(self.heartbeat_orbit_control());
            self.runtime_settings = next;
        }
        self.runtime_settings
    }

    fn heartbeat_orbit_enabled(&self) -> bool {
        self.heartbeat_orbit_request_state
            .active_override()
            .unwrap_or_else(|| {
                self.panel_settings_override
                    .filter(|panel| panel.update_mask.polar_rr_orbit_boost)
                    .map_or_else(
                        || self.heartbeat_orbit_packaged_settings.enabled(),
                        |panel| panel.polar_rr_orbit_boost_enabled,
                    )
            })
    }

    fn heartbeat_orbit_control(&self) -> Option<bool> {
        self.heartbeat_orbit_request_state
            .active_override()
            .or_else(|| self.heartbeat_orbit_enabled().then_some(true))
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

    pub(crate) fn half_res_offscreen_active(&self, stats: &GpuPrivateParticleFrameStats) -> bool {
        stats.half_res_offscreen_requested() && self.offscreen.is_some()
    }

    pub(crate) fn half_res_offscreen_tracers_only_active(
        &self,
        stats: &GpuPrivateParticleFrameStats,
    ) -> bool {
        stats.half_res_offscreen_tracers_only_requested() && self.offscreen.is_some()
    }

    pub(crate) unsafe fn ensure_half_res_offscreen_resources(
        &mut self,
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        color_format: vk::Format,
        full_extent: vk::Extent2D,
        swapchain_image_count: usize,
    ) -> Result<(), String> {
        if self.offscreen.as_ref().is_some_and(|resources| {
            resources.matches(color_format, full_extent, swapchain_image_count)
        }) {
            return Ok(());
        }
        if let Some(resources) = self.offscreen.take() {
            resources.destroy(device);
        }
        let resources = PrivateParticleOffscreenResources::new(
            device,
            memory_properties,
            color_format,
            full_extent,
            swapchain_image_count,
            self.pipeline_layout,
            self.projection_render_pass,
        )?;
        crate::marker(
            "private-particle-slot",
            format!(
                "status=offscreen-half-res-created {}",
                resources.marker_fields()
            ),
        );
        self.offscreen = Some(resources);
        Ok(())
    }

    pub(crate) unsafe fn record_half_res_offscreen_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        image_index: usize,
        eye_index: usize,
        eye_projection: HandMeshVisualEyeProjection,
        world_center_scale: [f32; 4],
        stats: &GpuPrivateParticleFrameStats,
    ) -> bool {
        if !self.half_res_offscreen_active(stats) {
            return false;
        }
        let Some(offscreen) = self.offscreen.as_ref() else {
            return false;
        };
        let Some(target) = offscreen.target(image_index, eye_index) else {
            return false;
        };
        let clear_values = [vk::ClearValue {
            color: vk::ClearColorValue {
                float32: [0.0, 0.0, 0.0, 0.0],
            },
        }];
        device.cmd_begin_render_pass(
            cmd,
            &vk::RenderPassBeginInfo::default()
                .render_pass(offscreen.render_pass)
                .framebuffer(target.framebuffer)
                .render_area(vk::Rect2D {
                    offset: vk::Offset2D::default(),
                    extent: offscreen.extent,
                })
                .clear_values(&clear_values),
            vk::SubpassContents::INLINE,
        );
        let (instance_count, first_instance) = if stats.half_res_offscreen_tracers_only_requested()
        {
            (stats.tracer_draw_count, stats.particle_count)
        } else {
            (stats.draw_count, 0)
        };
        self.record_particle_eye_with_pipeline(
            device,
            cmd,
            offscreen.extent,
            eye_projection,
            world_center_scale,
            stats,
            offscreen.particle_pipeline_for(stats.runtime_settings.transparency_blend_mode),
            instance_count,
            first_instance,
        );
        device.cmd_end_render_pass(cmd);
        true
    }

    pub(crate) unsafe fn record_half_res_composite_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        image_index: usize,
        eye_index: usize,
        full_extent: vk::Extent2D,
        stats: &GpuPrivateParticleFrameStats,
    ) -> bool {
        if !self.half_res_offscreen_active(stats) {
            return false;
        }
        let Some(offscreen) = self.offscreen.as_ref() else {
            return false;
        };
        let Some(target) = offscreen.target(image_index, eye_index) else {
            return false;
        };
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: full_extent.width as f32,
            height: full_extent.height as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = [vk::Rect2D {
            offset: vk::Offset2D::default(),
            extent: full_extent,
        }];
        device.cmd_bind_pipeline(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            offscreen.composite_pipeline_for(stats.runtime_settings.transparency_blend_mode),
        );
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            offscreen.composite_pipeline_layout,
            0,
            &[target.descriptor_set],
            &[],
        );
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_draw(cmd, 3, 1, 0, 0);
        true
    }

    fn graphics_pipeline_for(
        &self,
        blend_mode: PrivateParticleTransparencyBlendMode,
    ) -> vk::Pipeline {
        match blend_mode {
            PrivateParticleTransparencyBlendMode::Additive => self.graphics_pipeline_additive,
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver => {
                self.graphics_pipeline_alpha_over
            }
        }
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
        self.record_particle_eye_with_pipeline(
            device,
            cmd,
            extent,
            eye_projection,
            world_center_scale,
            stats,
            self.graphics_pipeline_for(stats.runtime_settings.transparency_blend_mode),
            stats.draw_count,
            0,
        );
    }

    pub(crate) unsafe fn record_overlay_eye_sort(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        eye_projection: HandMeshVisualEyeProjection,
        stats: &GpuPrivateParticleFrameStats,
    ) {
        if !stats.visible || !stats.sort_active || stats.sort_input_count <= 1 {
            return;
        }
        self.record_sort_frame(device, cmd, eye_projection, stats.sort_input_count);
    }

    pub(crate) unsafe fn record_overlay_eye_main_particles(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_projection: HandMeshVisualEyeProjection,
        world_center_scale: [f32; 4],
        stats: &GpuPrivateParticleFrameStats,
    ) {
        self.record_particle_eye_with_pipeline(
            device,
            cmd,
            extent,
            eye_projection,
            world_center_scale,
            stats,
            self.graphics_pipeline_for(stats.runtime_settings.transparency_blend_mode),
            stats.particle_count,
            0,
        );
    }

    unsafe fn record_particle_eye_with_pipeline(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_projection: HandMeshVisualEyeProjection,
        world_center_scale: [f32; 4],
        stats: &GpuPrivateParticleFrameStats,
        pipeline: vk::Pipeline,
        instance_count: u32,
        first_instance: u32,
    ) {
        if !stats.visible || stats.draw_count == 0 || instance_count == 0 {
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
            false,
            None,
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
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipeline);
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
        device.cmd_draw(
            cmd,
            private_particle_vertices_per_instance(stats.runtime_settings),
            instance_count,
            0,
            first_instance,
        );
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
    phase_reset: bool,
    fov_tangents_override: Option<[f32; 4]>,
) -> PrivateParticlePush {
    let frame = frame_count as f32;
    let phase_frame = if phase_reset || frame_count < 2 {
        0.0
    } else {
        frame
    };
    PrivateParticlePush {
        params0: [
            particle_count as f32,
            runtime_settings.visual_scale,
            private_particle_packed_mode_code(runtime_settings),
            runtime_settings.driver0_value01,
        ],
        params1: [
            phase_frame,
            1.0 / 90.0,
            frame / 90.0,
            runtime_settings.driver1_value01,
        ],
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
        fov_tangents: fov_tangents_override.unwrap_or(eye_projection.fov_tangents),
    }
}

fn private_particle_transparency_marker_fields(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> String {
    format!(
        "privateParticleTransparencyParameterSource={} privateParticleColorParameterSource={} privateParticleMaterialParameterSource={} privateParticleMaterialPreset={} privateParticleColorFacingAttenuationStrength={:.3} privateParticleTransparencyBlendMode={} privateParticleTransparencyCompositionMode=parametric-rgb-alpha-coupling privateParticleTransparencyOpacity={:.3} privateParticleTransparencyOutputAlphaScale={:.3} privateParticleTransparencyDepthSuppressionStrength={:.3} privateParticleTransparencyRgbAlphaCoupling={:.3}",
        crate::sanitize(runtime_settings.transparency_parameter_source),
        crate::sanitize(runtime_settings.color_parameter_source),
        crate::sanitize(runtime_settings.material_parameter_source),
        runtime_settings
            .material_preset
            .map_or("unset", PrivateParticleMaterialPreset::marker_name),
        runtime_settings.color_facing_attenuation_strength,
        runtime_settings.transparency_blend_mode.marker_name(),
        runtime_settings.transparency_opacity,
        runtime_settings.transparency_output_alpha_scale,
        runtime_settings.transparency_depth_suppression_strength,
        runtime_settings.transparency_rgb_alpha_coupling
    )
}

fn private_particle_offscreen_marker_fields(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> String {
    let tracers_only = runtime_settings.offscreen_half_res
        && runtime_settings.offscreen_half_res_tracers_only
        && !private_particle_sort_enabled();
    let render_path = if tracers_only {
        "half-resolution-tracer-accumulation-main-full-resolution"
    } else if runtime_settings.offscreen_half_res {
        "half-resolution-offscreen-accumulation"
    } else {
        "projection-pass-direct"
    };
    let mode = if runtime_settings.offscreen_half_res {
        "half-resolution"
    } else {
        "direct"
    };
    let billboard_policy = if tracers_only {
        "tracers-half-res-main-full-res"
    } else if runtime_settings.offscreen_half_res {
        "all-billboards-half-res"
    } else {
        "all-billboards-direct"
    };
    format!(
        "privateParticleRenderPath={} privateParticleOffscreenHalfRes={} privateParticleOffscreenHalfResTracersOnly={} privateParticleOffscreenMode={} privateParticleOffscreenBillboardPolicy={} privateParticleOffscreenResolutionScale={:.3} privateParticleOffscreenParameterSource={} privateParticleOffscreenCompositeMode={} privateParticleOffscreenCompositeFilter=linear-upsample",
        render_path,
        runtime_settings.offscreen_half_res,
        tracers_only,
        mode,
        billboard_policy,
        PRIVATE_PARTICLE_OFFSCREEN_RESOLUTION_SCALE,
        crate::sanitize(runtime_settings.offscreen_parameter_source),
        private_particle_offscreen_composite_mode(runtime_settings),
    )
}

fn private_particle_offscreen_composite_mode(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> &'static str {
    match runtime_settings.transparency_blend_mode {
        PrivateParticleTransparencyBlendMode::Additive => "additive-resolved-color",
        PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver => "alpha-over-resolved-color",
    }
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

fn private_particle_payload_surface_draw_enabled(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> bool {
    let _ = runtime_settings;
    true
}

fn private_particle_render_experiment_preset(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> PrivateParticleRenderExperimentPreset {
    runtime_settings.render_experiment_preset.unwrap_or({
        if PRIVATE_PARTICLE_MASK_DISCARD_MODE_CODE == 1 {
            PrivateParticleRenderExperimentPreset::SquareNoDiscard
        } else {
            PrivateParticleRenderExperimentPreset::ControlSquareDiscard
        }
    })
}

fn private_particle_static_ring_frame_zero_enabled() -> bool {
    PRIVATE_PARTICLE_MARKER_FIELDS
        .contains("viscerealityStaticPhaseDimensions=rotation2,animation5")
        && PRIVATE_PARTICLE_MARKER_FIELDS.contains("viscerealityStaticPhaseCanonicalRadians=0.000")
}

fn private_particle_vertices_per_instance(runtime_settings: PrivateParticleRuntimeSettings) -> u32 {
    private_particle_render_experiment_preset(runtime_settings).vertices_per_instance()
}

fn private_particle_packed_mode_code(runtime_settings: PrivateParticleRuntimeSettings) -> f32 {
    // Keep the push constant block at 128 bytes: mask, ordering, facing color,
    // and material blend mode share params0.z as a small integer payload decoded
    // by the draw shaders.
    let facing_quantized = (runtime_settings
        .color_facing_attenuation_strength
        .clamp(0.0, 1.0)
        * 1000.0)
        .round() as u32;
    let experiment = private_particle_render_experiment_preset(runtime_settings);
    (PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE
        + PRIVATE_PARTICLE_ORDERING_MODE_CODE * 10
        + facing_quantized * 100
        + experiment.mask_discard_code() * 1_000_000
        + runtime_settings.transparency_blend_mode.packed_code()
            * PRIVATE_PARTICLE_BLEND_MODE_PACK_OFFSET
        + experiment.geometry_code() * PRIVATE_PARTICLE_GEOMETRY_PACK_OFFSET) as f32
}

fn private_particle_material_effective_marker_fields(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> String {
    format!(
        "privateParticleMaterialPresetEffective={} privateParticleMaterialBlendMode={} privateParticleMaterialOpacity={:.3} privateParticleMaterialOutputAlphaScale={:.3} privateParticleMaterialDepthSuppressionStrength={:.3} privateParticleMaterialFacingAttenuationStrength={:.3}",
        runtime_settings
            .material_preset
            .map_or("unset", PrivateParticleMaterialPreset::marker_name),
        runtime_settings.transparency_blend_mode.marker_name(),
        runtime_settings.transparency_opacity,
        runtime_settings.transparency_output_alpha_scale,
        runtime_settings.transparency_depth_suppression_strength,
        runtime_settings.color_facing_attenuation_strength,
    )
}

fn private_particle_render_experiment_marker_fields(
    runtime_settings: PrivateParticleRuntimeSettings,
) -> String {
    let preset = private_particle_render_experiment_preset(runtime_settings);
    format!(
        "privateParticleRenderExperimentParameterSource={} privateParticleRenderExperimentPresetEffective={} privateParticleRenderExperimentGeometryEffective={} privateParticleRenderExperimentMaskDiscardEffective={} privateParticleRenderExperimentVerticesPerInstance={} privateParticleRenderExperimentStaticRingRequired={}",
        crate::sanitize(runtime_settings.render_experiment_parameter_source),
        preset.marker_name(),
        preset.geometry_marker(),
        preset.discard_marker(),
        preset.vertices_per_instance(),
        preset.requires_static_ring(),
    )
}

fn log_private_marker(
    status: &str,
    frame_count: u64,
    particle_count: u32,
    tracer_max_count: u32,
    tracer_draw_count: u32,
    tracer_draw_slots_per_oscillator: u32,
    anchor_echo_max_count: u32,
    anchor_echo_draw_echo_count: u32,
    anchor_echo_draw_count: u32,
    draw_count: u32,
    aux0_rows: u32,
    sort_input_count: u32,
    sort_count: u32,
    sort_capacity: u32,
    tracer_draw_slots_capacity: u32,
    world_anchor_scale_m: f32,
    world_anchor_scale_parameter_source: &'static str,
    runtime_settings: PrivateParticleRuntimeSettings,
    diagnostic_snapshot: PrivateParticleDiagnosticSnapshot,
) {
    let sort_active = private_particle_sort_enabled();
    crate::android_log(format!(
        "{} channel=frame status={} frame={} privateParticleKind={} privateParticleCount={} privateParticleMainCount={} privateParticleDrawCount={} privateParticleSettingsHotload=true privateParticleHotloadPollIntervalFrames={} privateParticleWorldAnchorScaleM={:.3} privateParticleWorldAnchorScaleParameterSource={} privateParticleVisualScale={:.3} privateParticleVisualParameterSource={} privateParticleDriver0Value01={:.3} privateParticleDriver1Value01={:.3} {} privateParticleDriverParameterSource={} privateParticleTracerMaxCount={} privateParticleTracerStateCapacity={} privateParticleTracerDrawSlotsCapacity={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawCount={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTracerStateRows={} privateParticleTracerRadiusPolicy=snapshot-source-radius privateParticleTracerOutputMode=merged-billboard-output privateParticleDrawBudgetIncludesTracers={} privateParticleTracerCpuUploadPerFrame=false {} privateParticleOutputAbi=four-vec4-billboard-rows privateParticleBillboardKindAux=aux.z-main-1-tracer-2-anchor-3-anchor_echo-4 privateParticleStatePingPong=true privateParticleAux0Rows={} privateParticleOrderingMode={} privateParticleOrderingImplementation={} privateParticleOrderingParameterSource={} privateParticleOrderingBasis=primary-eye-openxr-reference-space privateParticleSortActive={} privateParticleSortInputCount={} privateParticleSortCount={} privateParticleSortCapacity={} privateParticleOrderingCpuExpandedUploadPerFrame=false {} privateParticleMaskTextureLinked={} privateParticleMaskTextureMode={} privateParticleMaskDiscardMode={} privateParticleMaskAlphaCutoff={:.4} privateParticleMaskTextureFormat=R8_UNORM privateParticleMaskTextureSize={}x{}x{} privateParticleMaskTextureBytes={} privateParticleMaskTextureGpuResident=true {} {} {} {}",
        PRIVATE_PARTICLE_MARKER_PREFIX,
        status,
        frame_count,
        crate::sanitize(PRIVATE_PARTICLE_KIND),
        particle_count,
        particle_count,
        draw_count,
        PRIVATE_PARTICLE_SETTINGS_POLL_INTERVAL_FRAMES,
        world_anchor_scale_m,
        crate::sanitize(world_anchor_scale_parameter_source),
        runtime_settings.visual_scale,
        crate::sanitize(runtime_settings.visual_parameter_source),
        runtime_settings.driver0_value01,
        runtime_settings.driver1_value01,
        private_particle_driver_bank_marker_fields(runtime_settings),
        crate::sanitize(runtime_settings.driver_parameter_source),
        tracer_max_count,
        tracer_max_count,
        tracer_draw_slots_capacity,
        tracer_draw_slots_per_oscillator,
        tracer_draw_count,
        runtime_settings.tracer_lifetime_seconds,
        runtime_settings.tracer_copies_per_second,
        crate::sanitize(runtime_settings.tracer_parameter_source),
        tracer_max_count * PARTICLE_TRACER_STATE_ROWS_PER_SLOT as u32,
        tracer_draw_count > 0,
        private_particle_anchor_echo_marker_fields(
            anchor_echo_max_count,
            anchor_echo_draw_echo_count,
            anchor_echo_draw_count,
        ),
        aux0_rows,
        crate::sanitize(PRIVATE_PARTICLE_ORDERING_MODE),
        private_particle_ordering_implementation(),
        crate::sanitize(PRIVATE_PARTICLE_ORDERING_PARAMETER_SOURCE),
        sort_active,
        sort_input_count,
        sort_count,
        sort_capacity,
        diagnostic_snapshot.marker_fields(frame_count),
        PRIVATE_PARTICLE_MASK_TEXTURE_LINKED,
        crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MODE),
        crate::sanitize(PRIVATE_PARTICLE_MASK_DISCARD_MODE),
        PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF,
        PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
        PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
        PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
        PRIVATE_PARTICLE_MASK_TEXTURE_BYTES,
        private_particle_mask_texture_marker_fields(),
        private_particle_transparency_marker_fields(runtime_settings),
        private_particle_offscreen_marker_fields(runtime_settings),
        PRIVATE_PARTICLE_MARKER_FIELDS,
    ));
    log_private_render_config_marker(status, frame_count, runtime_settings);
    log_private_effect_marker_fields(status, frame_count);
}

fn log_private_render_config_marker(
    status: &str,
    frame_count: u64,
    runtime_settings: PrivateParticleRuntimeSettings,
) {
    crate::android_log(format!(
        "{} channel=render-config status={} frame={} privateParticleMaskTextureLinked={} privateParticleMaskTextureMode={} privateParticleMaskDiscardMode={} privateParticleMaskAlphaCutoff={:.4} privateParticleMaskTextureFormat=R8_UNORM privateParticleMaskTextureSize={}x{}x{} {} {} {} {}",
        PRIVATE_PARTICLE_MARKER_PREFIX,
        status,
        frame_count,
        PRIVATE_PARTICLE_MASK_TEXTURE_LINKED,
        crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MODE),
        crate::sanitize(PRIVATE_PARTICLE_MASK_DISCARD_MODE),
        PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF,
        PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
        PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
        PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
        private_particle_mask_texture_marker_fields(),
        private_particle_transparency_marker_fields(runtime_settings),
        private_particle_offscreen_marker_fields(runtime_settings),
        private_particle_render_experiment_marker_fields(runtime_settings),
    ));
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

pub(crate) struct PrivateParticleMaskTexturePayload {
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
        if aux0.len() < positions.len() * PARTICLE_MAIN_STATE_ROWS_PER_INSTANCE {
            return Err(format!(
                "generic private particle aux0 payload has {} rows, expected at least {}",
                aux0.len(),
                positions.len() * PARTICLE_MAIN_STATE_ROWS_PER_INSTANCE
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
    pub(crate) fn load() -> Result<Self, String> {
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
    blend_mode: PrivateParticleTransparencyBlendMode,
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
    let color_blend_attachment = [particle_color_blend_attachment(blend_mode)];
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

unsafe fn create_offscreen_render_pass(
    device: &ash::Device,
    format: vk::Format,
) -> Result<vk::RenderPass, String> {
    let attachments = [vk::AttachmentDescription::default()
        .format(format)
        .samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR)
        .store_op(vk::AttachmentStoreOp::STORE)
        .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
        .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
        .initial_layout(vk::ImageLayout::UNDEFINED)
        .final_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let color_refs = [vk::AttachmentReference::default()
        .attachment(0)
        .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)];
    let subpasses = [vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_refs)];
    let dependencies = [
        vk::SubpassDependency::default()
            .src_subpass(vk::SUBPASS_EXTERNAL)
            .dst_subpass(0)
            .src_stage_mask(vk::PipelineStageFlags::TOP_OF_PIPE)
            .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .src_access_mask(vk::AccessFlags::empty())
            .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE),
        vk::SubpassDependency::default()
            .src_subpass(0)
            .dst_subpass(vk::SUBPASS_EXTERNAL)
            .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .dst_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER)
            .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ),
    ];
    device
        .create_render_pass(
            &vk::RenderPassCreateInfo::default()
                .attachments(&attachments)
                .subpasses(&subpasses)
                .dependencies(&dependencies),
            None,
        )
        .map_err(|error| format!("create generic private particle offscreen render pass: {error}"))
}

unsafe fn create_offscreen_composite_descriptor_set_layout(
    device: &ash::Device,
) -> Result<vk::DescriptorSetLayout, String> {
    let bindings = [sampled_image_binding(0, vk::ShaderStageFlags::FRAGMENT)];
    device
        .create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )
        .map_err(|error| {
            format!("create generic private particle offscreen descriptor layout: {error}")
        })
}

unsafe fn create_offscreen_composite_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
    blend_mode: PrivateParticleTransparencyBlendMode,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/private_particles_offscreen_composite.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/private_particles_offscreen_composite.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| {
            format!("create generic private particle offscreen composite vertex shader: {error}")
        })?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create generic private particle offscreen composite fragment shader: {error}"
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
    let color_blend_attachment = [offscreen_composite_color_blend_attachment(blend_mode)];
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
        .map_err(|(_, error)| {
            format!(
                "create generic private particle offscreen composite graphics pipeline: {error}"
            )
        })
}

fn particle_color_blend_attachment(
    blend_mode: PrivateParticleTransparencyBlendMode,
) -> vk::PipelineColorBlendAttachmentState {
    let (
        src_color_blend_factor,
        dst_color_blend_factor,
        src_alpha_blend_factor,
        dst_alpha_blend_factor,
    ) = match blend_mode {
        PrivateParticleTransparencyBlendMode::Additive => (
            vk::BlendFactor::SRC_ALPHA,
            vk::BlendFactor::ONE,
            vk::BlendFactor::SRC_ALPHA,
            vk::BlendFactor::ONE,
        ),
        PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver => (
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

fn offscreen_composite_color_blend_attachment(
    blend_mode: PrivateParticleTransparencyBlendMode,
) -> vk::PipelineColorBlendAttachmentState {
    let (
        src_color_blend_factor,
        dst_color_blend_factor,
        src_alpha_blend_factor,
        dst_alpha_blend_factor,
    ) = match blend_mode {
        PrivateParticleTransparencyBlendMode::Additive => (
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE,
        ),
        PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver => (
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
    driver_bank_buffer: vk::DescriptorBufferInfo,
    diagnostic_buffer: vk::DescriptorBufferInfo,
) {
    let position_info = [position_buffer];
    let normal_info = [normal_buffer];
    let particle_info = [particle_output_buffer];
    let effect_state_source_info = [effect_state_source_buffer];
    let effect_state_target_info = [effect_state_target_buffer];
    let aux0_info = [aux0_buffer];
    let mask_texture_info = [mask_texture];
    let sort_info = [particle_sort_buffer];
    let driver_bank_info = [driver_bank_buffer];
    let diagnostic_info = [diagnostic_buffer];
    let writes = [
        write_storage_descriptor(descriptor_set, 0, &position_info),
        write_storage_descriptor(descriptor_set, 1, &normal_info),
        write_storage_descriptor(descriptor_set, 2, &particle_info),
        write_storage_descriptor(descriptor_set, 3, &effect_state_source_info),
        write_storage_descriptor(descriptor_set, 4, &effect_state_target_info),
        write_storage_descriptor(descriptor_set, 5, &aux0_info),
        write_sampled_image_descriptor(descriptor_set, 6, &mask_texture_info),
        write_storage_descriptor(descriptor_set, 7, &sort_info),
        write_storage_descriptor(descriptor_set, 8, &driver_bank_info),
        write_storage_descriptor(descriptor_set, 9, &diagnostic_info),
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

fn compute_write_to_host_read_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::HOST_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn transfer_write_to_shader_write_barrier(
    buffer: &OwnedBuffer,
) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

struct PrivateParticleOffscreenResources {
    format: vk::Format,
    full_extent: vk::Extent2D,
    extent: vk::Extent2D,
    swapchain_image_count: usize,
    render_pass: vk::RenderPass,
    particle_pipeline_additive: vk::Pipeline,
    particle_pipeline_alpha_over: vk::Pipeline,
    composite_descriptor_pool: vk::DescriptorPool,
    composite_descriptor_set_layout: vk::DescriptorSetLayout,
    composite_pipeline_layout: vk::PipelineLayout,
    composite_pipeline_additive: vk::Pipeline,
    composite_pipeline_alpha_over: vk::Pipeline,
    sampler: vk::Sampler,
    allocation_bytes: vk::DeviceSize,
    targets: Vec<PrivateParticleOffscreenTarget>,
}

impl PrivateParticleOffscreenResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        format: vk::Format,
        full_extent: vk::Extent2D,
        swapchain_image_count: usize,
        particle_pipeline_layout: vk::PipelineLayout,
        projection_render_pass: vk::RenderPass,
    ) -> Result<Self, String> {
        if full_extent.width == 0 || full_extent.height == 0 {
            return Err("generic private particle offscreen target requires nonzero extent".into());
        }
        let target_count = swapchain_image_count
            .checked_mul(PRIVATE_PARTICLE_OFFSCREEN_EYE_COUNT)
            .ok_or_else(|| {
                "generic private particle offscreen target count overflowed usize".to_string()
            })?;
        if target_count == 0 {
            return Err("generic private particle offscreen target count must be nonzero".into());
        }
        let extent = vk::Extent2D {
            width: full_extent.width.div_ceil(2).max(1),
            height: full_extent.height.div_ceil(2).max(1),
        };
        let mut resources = Self {
            format,
            full_extent,
            extent,
            swapchain_image_count,
            render_pass: vk::RenderPass::null(),
            particle_pipeline_additive: vk::Pipeline::null(),
            particle_pipeline_alpha_over: vk::Pipeline::null(),
            composite_descriptor_pool: vk::DescriptorPool::null(),
            composite_descriptor_set_layout: vk::DescriptorSetLayout::null(),
            composite_pipeline_layout: vk::PipelineLayout::null(),
            composite_pipeline_additive: vk::Pipeline::null(),
            composite_pipeline_alpha_over: vk::Pipeline::null(),
            sampler: vk::Sampler::null(),
            allocation_bytes: 0,
            targets: Vec::with_capacity(target_count),
        };

        resources.render_pass = match create_offscreen_render_pass(device, format) {
            Ok(render_pass) => render_pass,
            Err(error) => {
                resources.destroy(device);
                return Err(error);
            }
        };
        resources.particle_pipeline_additive = match create_graphics_pipeline(
            device,
            resources.render_pass,
            particle_pipeline_layout,
            PrivateParticleTransparencyBlendMode::Additive,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                resources.destroy(device);
                return Err(error);
            }
        };
        resources.particle_pipeline_alpha_over = match create_graphics_pipeline(
            device,
            resources.render_pass,
            particle_pipeline_layout,
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                resources.destroy(device);
                return Err(error);
            }
        };
        resources.composite_descriptor_set_layout =
            match create_offscreen_composite_descriptor_set_layout(device) {
                Ok(layout) => layout,
                Err(error) => {
                    resources.destroy(device);
                    return Err(error);
                }
            };
        let composite_set_layouts = [resources.composite_descriptor_set_layout];
        resources.composite_pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default().set_layouts(&composite_set_layouts),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                resources.destroy(device);
                return Err(format!(
                    "create generic private particle offscreen composite pipeline layout: {error}"
                ));
            }
        };
        resources.composite_pipeline_additive = match create_offscreen_composite_pipeline(
            device,
            projection_render_pass,
            resources.composite_pipeline_layout,
            PrivateParticleTransparencyBlendMode::Additive,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                resources.destroy(device);
                return Err(error);
            }
        };
        resources.composite_pipeline_alpha_over = match create_offscreen_composite_pipeline(
            device,
            projection_render_pass,
            resources.composite_pipeline_layout,
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                resources.destroy(device);
                return Err(error);
            }
        };
        resources.sampler = match device.create_sampler(
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
                resources.destroy(device);
                return Err(format!(
                    "create generic private particle offscreen sampler: {error}"
                ));
            }
        };
        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(target_count as u32)];
        resources.composite_descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(target_count as u32),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                resources.destroy(device);
                return Err(format!(
                    "create generic private particle offscreen descriptor pool: {error}"
                ));
            }
        };
        let descriptor_layouts = vec![resources.composite_descriptor_set_layout; target_count];
        let descriptor_sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(resources.composite_descriptor_pool)
                .set_layouts(&descriptor_layouts),
        ) {
            Ok(sets) if sets.len() == target_count => sets,
            Ok(sets) => {
                resources.destroy(device);
                return Err(format!(
                    "allocate generic private particle offscreen descriptor sets: expected {}, got {}",
                    target_count,
                    sets.len()
                ));
            }
            Err(error) => {
                resources.destroy(device);
                return Err(format!(
                    "allocate generic private particle offscreen descriptor sets: {error}"
                ));
            }
        };
        for descriptor_set in descriptor_sets {
            let target = match PrivateParticleOffscreenTarget::new(
                device,
                memory_properties,
                resources.render_pass,
                format,
                extent,
                resources.sampler,
                descriptor_set,
            ) {
                Ok(target) => target,
                Err(error) => {
                    resources.destroy(device);
                    return Err(error);
                }
            };
            resources.allocation_bytes = resources
                .allocation_bytes
                .saturating_add(target.allocation_bytes);
            resources.targets.push(target);
        }
        Ok(resources)
    }

    fn matches(
        &self,
        format: vk::Format,
        full_extent: vk::Extent2D,
        swapchain_image_count: usize,
    ) -> bool {
        self.format == format
            && self.full_extent == full_extent
            && self.swapchain_image_count == swapchain_image_count
    }

    fn marker_fields(&self) -> String {
        format!(
            "privateParticleOffscreenHalfRes=true privateParticleOffscreenResourceKind=half-resolution-color-targets privateParticleOffscreenResolutionScale={:.3} privateParticleOffscreenFullExtent={}x{} privateParticleOffscreenTargetExtent={}x{} privateParticleOffscreenColorFormat={:?} privateParticleOffscreenSwapchainImageCount={} privateParticleOffscreenTargetCount={} privateParticleOffscreenAllocationBytes={} privateParticleOffscreenMaterialPipelines=additive,alpha-over privateParticleOffscreenCompositeFilter=linear-upsample",
            PRIVATE_PARTICLE_OFFSCREEN_RESOLUTION_SCALE,
            self.full_extent.width,
            self.full_extent.height,
            self.extent.width,
            self.extent.height,
            self.format,
            self.swapchain_image_count,
            self.targets.len(),
            self.allocation_bytes,
        )
    }

    fn particle_pipeline_for(
        &self,
        blend_mode: PrivateParticleTransparencyBlendMode,
    ) -> vk::Pipeline {
        match blend_mode {
            PrivateParticleTransparencyBlendMode::Additive => self.particle_pipeline_additive,
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver => {
                self.particle_pipeline_alpha_over
            }
        }
    }

    fn composite_pipeline_for(
        &self,
        blend_mode: PrivateParticleTransparencyBlendMode,
    ) -> vk::Pipeline {
        match blend_mode {
            PrivateParticleTransparencyBlendMode::Additive => self.composite_pipeline_additive,
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver => {
                self.composite_pipeline_alpha_over
            }
        }
    }

    fn target(
        &self,
        swapchain_image_index: usize,
        eye_index: usize,
    ) -> Option<&PrivateParticleOffscreenTarget> {
        if eye_index >= PRIVATE_PARTICLE_OFFSCREEN_EYE_COUNT {
            return None;
        }
        let target_index = swapchain_image_index
            .checked_mul(PRIVATE_PARTICLE_OFFSCREEN_EYE_COUNT)?
            .checked_add(eye_index)?;
        self.targets.get(target_index)
    }

    unsafe fn destroy(self, device: &ash::Device) {
        for target in &self.targets {
            target.destroy(device);
        }
        if self.composite_pipeline_alpha_over != vk::Pipeline::null() {
            device.destroy_pipeline(self.composite_pipeline_alpha_over, None);
        }
        if self.composite_pipeline_additive != vk::Pipeline::null() {
            device.destroy_pipeline(self.composite_pipeline_additive, None);
        }
        if self.particle_pipeline_alpha_over != vk::Pipeline::null() {
            device.destroy_pipeline(self.particle_pipeline_alpha_over, None);
        }
        if self.particle_pipeline_additive != vk::Pipeline::null() {
            device.destroy_pipeline(self.particle_pipeline_additive, None);
        }
        if self.composite_pipeline_layout != vk::PipelineLayout::null() {
            device.destroy_pipeline_layout(self.composite_pipeline_layout, None);
        }
        if self.composite_descriptor_pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.composite_descriptor_pool, None);
        }
        if self.composite_descriptor_set_layout != vk::DescriptorSetLayout::null() {
            device.destroy_descriptor_set_layout(self.composite_descriptor_set_layout, None);
        }
        if self.sampler != vk::Sampler::null() {
            device.destroy_sampler(self.sampler, None);
        }
        if self.render_pass != vk::RenderPass::null() {
            device.destroy_render_pass(self.render_pass, None);
        }
    }
}

struct PrivateParticleOffscreenTarget {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    framebuffer: vk::Framebuffer,
    descriptor_set: vk::DescriptorSet,
    allocation_bytes: vk::DeviceSize,
}

impl PrivateParticleOffscreenTarget {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        format: vk::Format,
        extent: vk::Extent2D,
        sampler: vk::Sampler,
        descriptor_set: vk::DescriptorSet,
    ) -> Result<Self, String> {
        let mut target = Self {
            image: vk::Image::null(),
            memory: vk::DeviceMemory::null(),
            view: vk::ImageView::null(),
            framebuffer: vk::Framebuffer::null(),
            descriptor_set,
            allocation_bytes: 0,
        };
        target.image = match device.create_image(
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
                .usage(vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::SAMPLED)
                .sharing_mode(vk::SharingMode::EXCLUSIVE)
                .initial_layout(vk::ImageLayout::UNDEFINED),
            None,
        ) {
            Ok(image) => image,
            Err(error) => {
                target.destroy(device);
                return Err(format!(
                    "create generic private particle offscreen image: {error}"
                ));
            }
        };
        let requirements = device.get_image_memory_requirements(target.image);
        target.allocation_bytes = requirements.size;
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        ) {
            Ok(index) => index,
            Err(error) => {
                target.destroy(device);
                return Err(error);
            }
        };
        target.memory = match device.allocate_memory(
            &vk::MemoryAllocateInfo::default()
                .allocation_size(requirements.size)
                .memory_type_index(memory_type_index),
            None,
        ) {
            Ok(memory) => memory,
            Err(error) => {
                target.destroy(device);
                return Err(format!(
                    "allocate generic private particle offscreen image memory: {error}"
                ));
            }
        };
        if let Err(error) = device.bind_image_memory(target.image, target.memory, 0) {
            target.destroy(device);
            return Err(format!(
                "bind generic private particle offscreen image memory: {error}"
            ));
        }
        target.view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(target.image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(format)
                .subresource_range(single_color_subresource_range()),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                target.destroy(device);
                return Err(format!(
                    "create generic private particle offscreen image view: {error}"
                ));
            }
        };
        let attachments = [target.view];
        target.framebuffer = match device.create_framebuffer(
            &vk::FramebufferCreateInfo::default()
                .render_pass(render_pass)
                .attachments(&attachments)
                .width(extent.width)
                .height(extent.height)
                .layers(1),
            None,
        ) {
            Ok(framebuffer) => framebuffer,
            Err(error) => {
                target.destroy(device);
                return Err(format!(
                    "create generic private particle offscreen framebuffer: {error}"
                ));
            }
        };
        let image_info = [vk::DescriptorImageInfo::default()
            .sampler(sampler)
            .image_view(target.view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
        let writes = [vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&image_info)];
        device.update_descriptor_sets(&writes, &[]);
        Ok(target)
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        if self.framebuffer != vk::Framebuffer::null() {
            device.destroy_framebuffer(self.framebuffer, None);
        }
        if self.view != vk::ImageView::null() {
            device.destroy_image_view(self.view, None);
        }
        if self.image != vk::Image::null() {
            device.destroy_image(self.image, None);
        }
        if self.memory != vk::DeviceMemory::null() {
            device.free_memory(self.memory, None);
        }
    }
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

    unsafe fn write_data<T: Copy>(
        &self,
        device: &ash::Device,
        label: &str,
        data: &[T],
    ) -> Result<(), String> {
        let bytes = mem::size_of_val(data) as vk::DeviceSize;
        if bytes > self.bytes {
            return Err(format!(
                "{label} write has {bytes} bytes, buffer only has {} bytes",
                self.bytes
            ));
        }
        let mapped = device
            .map_memory(self.memory, 0, bytes, vk::MemoryMapFlags::empty())
            .map_err(|error| format!("map {label} buffer: {error}"))?
            .cast::<T>();
        mapped.copy_from_nonoverlapping(data.as_ptr(), data.len());
        device.unmap_memory(self.memory);
        Ok(())
    }

    unsafe fn read_i32_words<const N: usize>(
        &self,
        device: &ash::Device,
        label: &str,
    ) -> Result<[i32; N], String> {
        let bytes = (N * mem::size_of::<i32>()) as vk::DeviceSize;
        if bytes > self.bytes {
            return Err(format!(
                "{label} read needs {bytes} bytes, buffer only has {} bytes",
                self.bytes
            ));
        }
        let mapped = device
            .map_memory(self.memory, 0, bytes, vk::MemoryMapFlags::empty())
            .map_err(|error| format!("map {label} buffer for read: {error}"))?
            .cast::<i32>();
        let mut values = [0_i32; N];
        values.copy_from_slice(std::slice::from_raw_parts(mapped, N));
        device.unmap_memory(self.memory);
        Ok(values)
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

pub(crate) struct OwnedMaskTexture {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    sampler: vk::Sampler,
}

struct MaskTextureUpload {
    pixels: Vec<u8>,
    copy_regions: Vec<vk::BufferImageCopy>,
    width: u32,
    height: u32,
    layers: u32,
    mip_levels: u32,
    view_type: vk::ImageViewType,
}

impl OwnedMaskTexture {
    pub(crate) unsafe fn new_with_data(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        queue: vk::Queue,
        command_pool: vk::CommandPool,
        payload: &PrivateParticleMaskTexturePayload,
    ) -> Result<Self, String> {
        let upload = build_mask_texture_upload(payload)?;
        let staging = OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::TRANSFER_SRC,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "generic private particle mask texture staging",
            upload.pixels.as_slice(),
        )?;
        let image = match device.create_image(
            &vk::ImageCreateInfo::default()
                .image_type(vk::ImageType::TYPE_2D)
                .format(vk::Format::R8_UNORM)
                .extent(vk::Extent3D {
                    width: upload.width,
                    height: upload.height,
                    depth: 1,
                })
                .mip_levels(upload.mip_levels)
                .array_layers(upload.layers)
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
            upload.layers,
            upload.mip_levels,
            &upload.copy_regions,
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
                .view_type(upload.view_type)
                .format(vk::Format::R8_UNORM)
                .subresource_range(mask_texture_subresource_range(
                    upload.layers,
                    upload.mip_levels,
                )),
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
                .mipmap_mode(if upload.mip_levels > 1 {
                    vk::SamplerMipmapMode::LINEAR
                } else {
                    vk::SamplerMipmapMode::NEAREST
                })
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .min_lod(0.0)
                .max_lod((upload.mip_levels - 1) as f32),
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

    pub(crate) fn descriptor(&self) -> vk::DescriptorImageInfo {
        vk::DescriptorImageInfo::default()
            .sampler(self.sampler)
            .image_view(self.view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
    }

    pub(crate) unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_sampler(self.sampler, None);
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

fn build_mask_texture_upload(
    payload: &PrivateParticleMaskTexturePayload,
) -> Result<MaskTextureUpload, String> {
    if private_particle_mask_texture_uses_atlas() {
        build_mask_texture_atlas_upload(payload)
    } else {
        build_mask_texture_array_upload(payload)
    }
}

fn build_mask_texture_array_upload(
    payload: &PrivateParticleMaskTexturePayload,
) -> Result<MaskTextureUpload, String> {
    let width = payload.width as usize;
    let height = payload.height as usize;
    let layers = payload.layers as usize;
    let mip_levels = PRIVATE_PARTICLE_MASK_TEXTURE_MIP_LEVELS as usize;
    let mut current_width = width;
    let mut current_height = height;
    let mut layer_images = base_mask_layers(payload)?;
    let mut pixels = Vec::with_capacity(mask_texture_upload_capacity(width, height, layers));
    let mut copy_regions = Vec::with_capacity(mip_levels);

    for mip_level in 0..mip_levels {
        let offset = pixels.len() as vk::DeviceSize;
        for layer in &layer_images {
            pixels.extend_from_slice(layer);
        }
        copy_regions.push(mask_texture_copy_region(
            offset,
            mip_level as u32,
            payload.layers,
            current_width as u32,
            current_height as u32,
        ));
        if mip_level + 1 < mip_levels {
            layer_images = layer_images
                .iter()
                .map(|layer| downsample_r8_2x(layer, current_width, current_height))
                .collect::<Result<Vec<_>, _>>()?;
            current_width = (current_width / 2).max(1);
            current_height = (current_height / 2).max(1);
        }
    }

    Ok(MaskTextureUpload {
        pixels,
        copy_regions,
        width: payload.width,
        height: payload.height,
        layers: payload.layers,
        mip_levels: PRIVATE_PARTICLE_MASK_TEXTURE_MIP_LEVELS,
        view_type: vk::ImageViewType::TYPE_2D_ARRAY,
    })
}

fn build_mask_texture_atlas_upload(
    payload: &PrivateParticleMaskTexturePayload,
) -> Result<MaskTextureUpload, String> {
    let tile_width = payload.width as usize;
    let tile_height = payload.height as usize;
    let layers = payload.layers as usize;
    let columns = PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_COLUMNS as usize;
    let rows = PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_ROWS as usize;
    let mip_levels = PRIVATE_PARTICLE_MASK_TEXTURE_MIP_LEVELS as usize;
    if columns == 0 || rows == 0 || columns.checked_mul(rows).unwrap_or(0) < layers {
        return Err(format!(
            "generic private particle mask atlas grid {}x{} cannot hold {} layers",
            columns, rows, layers
        ));
    }
    let mut current_width = tile_width;
    let mut current_height = tile_height;
    let mut layer_images = base_mask_layers(payload)?;
    let mut pixels = Vec::with_capacity(mask_texture_upload_capacity(
        PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_WIDTH as usize,
        PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_HEIGHT as usize,
        1,
    ));
    let mut copy_regions = Vec::with_capacity(mip_levels);

    for mip_level in 0..mip_levels {
        let atlas_width = current_width
            .checked_mul(columns)
            .ok_or_else(|| "generic private particle mask atlas width overflow".to_string())?;
        let atlas_height = current_height
            .checked_mul(rows)
            .ok_or_else(|| "generic private particle mask atlas height overflow".to_string())?;
        let expected_width = mip_dimension(PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_WIDTH, mip_level);
        let expected_height = mip_dimension(PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_HEIGHT, mip_level);
        if atlas_width as u32 != expected_width || atlas_height as u32 != expected_height {
            return Err(format!(
                "generic private particle mask atlas mip {} is {}x{}, expected Vulkan image mip {}x{}",
                mip_level, atlas_width, atlas_height, expected_width, expected_height
            ));
        }
        let mut atlas = vec![0_u8; atlas_width * atlas_height];
        for (layer_index, layer) in layer_images.iter().enumerate() {
            let column = layer_index % columns;
            let row = layer_index / columns;
            for y in 0..current_height {
                let src_start = y * current_width;
                let dst_start = (row * current_height + y) * atlas_width + column * current_width;
                atlas[dst_start..dst_start + current_width]
                    .copy_from_slice(&layer[src_start..src_start + current_width]);
            }
        }
        let offset = pixels.len() as vk::DeviceSize;
        pixels.extend_from_slice(&atlas);
        copy_regions.push(mask_texture_copy_region(
            offset,
            mip_level as u32,
            1,
            atlas_width as u32,
            atlas_height as u32,
        ));
        if mip_level + 1 < mip_levels {
            layer_images = layer_images
                .iter()
                .map(|layer| downsample_r8_2x(layer, current_width, current_height))
                .collect::<Result<Vec<_>, _>>()?;
            current_width = (current_width / 2).max(1);
            current_height = (current_height / 2).max(1);
        }
    }

    Ok(MaskTextureUpload {
        pixels,
        copy_regions,
        width: PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_WIDTH,
        height: PRIVATE_PARTICLE_MASK_TEXTURE_IMAGE_HEIGHT,
        layers: 1,
        mip_levels: PRIVATE_PARTICLE_MASK_TEXTURE_MIP_LEVELS,
        view_type: vk::ImageViewType::TYPE_2D,
    })
}

fn base_mask_layers(payload: &PrivateParticleMaskTexturePayload) -> Result<Vec<Vec<u8>>, String> {
    let layer_bytes = payload.width as usize * payload.height as usize;
    if layer_bytes == 0 {
        return Err("generic private particle mask texture has an empty layer".to_string());
    }
    let layers = payload.layers as usize;
    if payload.pixels.len() != layer_bytes * layers {
        return Err(format!(
            "generic private particle mask texture has {} bytes, expected {}",
            payload.pixels.len(),
            layer_bytes * layers
        ));
    }
    Ok(payload
        .pixels
        .chunks_exact(layer_bytes)
        .map(|chunk| chunk.to_vec())
        .collect())
}

fn downsample_r8_2x(source: &[u8], width: usize, height: usize) -> Result<Vec<u8>, String> {
    if source.len() != width * height {
        return Err(format!(
            "generic private particle mask mip source has {} bytes, expected {}x{}",
            source.len(),
            width,
            height
        ));
    }
    let next_width = (width / 2).max(1);
    let next_height = (height / 2).max(1);
    let mut output = vec![0_u8; next_width * next_height];
    for y in 0..next_height {
        for x in 0..next_width {
            let src_x = x * 2;
            let src_y = y * 2;
            let mut sum = 0_u32;
            let mut count = 0_u32;
            for dy in 0..2 {
                let sample_y = src_y + dy;
                if sample_y >= height {
                    continue;
                }
                for dx in 0..2 {
                    let sample_x = src_x + dx;
                    if sample_x >= width {
                        continue;
                    }
                    sum += source[sample_y * width + sample_x] as u32;
                    count += 1;
                }
            }
            output[y * next_width + x] = ((sum + count / 2) / count) as u8;
        }
    }
    Ok(output)
}

fn mask_texture_copy_region(
    offset: vk::DeviceSize,
    mip_level: u32,
    layer_count: u32,
    width: u32,
    height: u32,
) -> vk::BufferImageCopy {
    vk::BufferImageCopy::default()
        .buffer_offset(offset)
        .buffer_row_length(0)
        .buffer_image_height(0)
        .image_subresource(vk::ImageSubresourceLayers {
            aspect_mask: vk::ImageAspectFlags::COLOR,
            mip_level,
            base_array_layer: 0,
            layer_count,
        })
        .image_offset(vk::Offset3D::default())
        .image_extent(vk::Extent3D {
            width,
            height,
            depth: 1,
        })
}

fn mask_texture_upload_capacity(width: usize, height: usize, layers: usize) -> usize {
    let mut capacity = 0_usize;
    let mut current_width = width;
    let mut current_height = height;
    for _ in 0..PRIVATE_PARTICLE_MASK_TEXTURE_MIP_LEVELS {
        capacity = capacity.saturating_add(
            current_width
                .saturating_mul(current_height)
                .saturating_mul(layers),
        );
        current_width = (current_width / 2).max(1);
        current_height = (current_height / 2).max(1);
    }
    capacity
}

fn mip_dimension(base: u32, mip_level: usize) -> u32 {
    (base >> mip_level).max(1)
}

fn private_particle_mask_texture_uses_atlas() -> bool {
    matches!(PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE, 3 | 4)
}

unsafe fn upload_mask_texture_image(
    device: &ash::Device,
    queue: vk::Queue,
    command_pool: vk::CommandPool,
    staging_buffer: vk::Buffer,
    image: vk::Image,
    layers: u32,
    mip_levels: u32,
    copy_regions: &[vk::BufferImageCopy],
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
        .subresource_range(mask_texture_subresource_range(layers, mip_levels))
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
    device.cmd_copy_buffer_to_image(
        command_buffer,
        staging_buffer,
        image,
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        copy_regions,
    );
    let to_fragment = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(mask_texture_subresource_range(layers, mip_levels))
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

fn mask_texture_subresource_range(layers: u32, mip_levels: u32) -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: mip_levels,
        base_array_layer: 0,
        layer_count: layers,
    }
}

fn single_color_subresource_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: 1,
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
    driver_bank_buffer: &OwnedBuffer,
    mask_texture: &OwnedMaskTexture,
    particle_sort_buffer: &OwnedBuffer,
) {
    particle_sort_buffer.destroy(device);
    driver_bank_buffer.destroy(device);
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

unsafe fn destroy_buffers_mask_sort_and_diagnostics(
    device: &ash::Device,
    position_buffer: &OwnedBuffer,
    normal_buffer: &OwnedBuffer,
    particle_output_buffer: &OwnedBuffer,
    effect_state_buffer_a: &OwnedBuffer,
    effect_state_buffer_b: &OwnedBuffer,
    aux0_buffer: &OwnedBuffer,
    driver_bank_buffer: &OwnedBuffer,
    mask_texture: &OwnedMaskTexture,
    particle_sort_buffer: &OwnedBuffer,
    diagnostic_buffers: &[OwnedBuffer; PARTICLE_DESCRIPTOR_SET_COUNT],
) {
    for buffer in diagnostic_buffers {
        buffer.destroy(device);
    }
    destroy_buffers_mask_and_sort(
        device,
        position_buffer,
        normal_buffer,
        particle_output_buffer,
        effect_state_buffer_a,
        effect_state_buffer_b,
        aux0_buffer,
        driver_bank_buffer,
        mask_texture,
        particle_sort_buffer,
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

#[cfg(test)]
mod material_request_tests {
    use super::*;

    fn panel_settings(
        material_preset: Option<PrivateParticleMaterialPreset>,
        material_override_enabled: bool,
        polar_rr_orbit_boost_enabled: bool,
    ) -> GpuPrivateParticlePanelSettings {
        GpuPrivateParticlePanelSettings {
            update_mask: PrivateParticlePanelUpdateMask::full_snapshot(),
            visual_scale: 0.70,
            particle_size_override_enabled: false,
            particle_size_mode: PRIVATE_PARTICLE_SIZE_MODE_LEGACY,
            particle_size_world_meters: 0.05,
            particle_size_sphere_percent: 5.0,
            particle_size_oscillation_percent: 0.0,
            driver_values01: [0.0; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
            driver_control_modes: [PANEL_DRIVER_MODE_DIRECT;
                GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
            driver_control_source_slots: [0, 1, 2, 3, 4, 5, 6, 7],
            driver_control_curve_codes: [PANEL_CURVE_LINEAR;
                GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
            driver_control_range_mins: [0.0; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
            driver_control_range_maxs: [1.0; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
            driver_control_cycle_multipliers: [0.0; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT],
            tracer_draw_slots_per_oscillator: 7,
            tracer_lifetime_seconds: 0.5,
            tracer_copies_per_second: 14.0,
            transparency_opacity: 1.0,
            transparency_output_alpha_scale: 1.0,
            transparency_depth_suppression_strength: 0.0,
            transparency_rgb_alpha_coupling: 1.0,
            color_facing_attenuation_strength: 0.0,
            material_preset,
            material_override_enabled,
            polar_rr_orbit_boost_enabled,
        }
    }

    #[test]
    fn material_presets_change_only_the_closed_material_envelope() {
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        let visual_scale = settings.visual_scale;
        let driver_values01 = settings.driver_values01;
        let tracer_draw_slots = settings.tracer_draw_slots_per_oscillator;
        let tracer_lifetime = settings.tracer_lifetime_seconds;
        let tracer_copies = settings.tracer_copies_per_second;

        settings.apply_material_preset(PrivateParticleMaterialPreset::AkdMaterialEmulation);

        assert_eq!(
            settings.transparency_blend_mode,
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver
        );
        assert_eq!(settings.transparency_opacity, 0.36);
        assert_eq!(settings.transparency_output_alpha_scale, 0.45);
        assert_eq!(settings.transparency_depth_suppression_strength, 1.5);
        assert_eq!(settings.color_facing_attenuation_strength, 0.20);
        assert_eq!(settings.visual_scale, visual_scale);
        assert_eq!(settings.driver_values01, driver_values01);
        assert_eq!(settings.tracer_draw_slots_per_oscillator, tracer_draw_slots);
        assert_eq!(settings.tracer_lifetime_seconds, tracer_lifetime);
        assert_eq!(settings.tracer_copies_per_second, tracer_copies);
    }

    #[test]
    fn explicit_particle_size_uses_reciprocal_percent_envelope_and_reserved_aux_row() {
        let mut panel = panel_settings(None, false, false);
        panel.particle_size_override_enabled = true;
        panel.particle_size_mode = PRIVATE_PARTICLE_SIZE_MODE_SPHERE_PERCENT;
        panel.particle_size_sphere_percent = 6.0;
        panel.particle_size_world_meters = 0.08;
        panel.particle_size_oscillation_percent = 50.0;
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        settings.apply_panel_override(panel, [0.0; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT]);

        assert_eq!(private_particle_size_limits(settings), (3.0, 12.0));
        let rows = private_particle_driver_bank_rows(settings);
        let size_aux_row = PRIVATE_PARTICLE_DRIVER_BANK_VALUE_VEC4_ROWS
            + 2 * PRIVATE_PARTICLE_DRIVER_CONTROL_ROWS_PER_SLOT
            + 2;
        assert_eq!(
            rows[size_aux_row],
            [
                1.0,
                PRIVATE_PARTICLE_SIZE_MODE_SPHERE_PERCENT as f32,
                6.0,
                50.0
            ]
        );
    }

    #[test]
    fn size_field_patch_preserves_every_unselected_runtime_parameter() {
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        settings.apply_material_preset(PrivateParticleMaterialPreset::AkdMaterialEmulation);
        settings.driver_control_modes[2] = PANEL_DRIVER_MODE_INPUT_SLOT;
        settings.driver_control_curve_codes[2] = PANEL_CURVE_HOLD_HIGH;
        settings.driver_bank_values01[2] = 0.37;
        settings.tracer_draw_slots_per_oscillator = 19;
        settings.tracer_lifetime_seconds = 2.5;
        settings.tracer_copies_per_second = 33.0;
        let source_values = settings.driver_bank_values01;
        let before = settings;

        let mut patch = panel_settings(None, false, false);
        patch.update_mask = PrivateParticlePanelUpdateMask::empty();
        patch.update_mask.particle_size_override_enabled = true;
        patch.update_mask.particle_size_mode = true;
        patch.update_mask.particle_size_sphere_percent = true;
        patch.particle_size_override_enabled = true;
        patch.particle_size_mode = PRIVATE_PARTICLE_SIZE_MODE_SPHERE_PERCENT;
        patch.particle_size_sphere_percent = 6.25;

        settings.apply_panel_override(patch, source_values);
        let mut expected = before;
        expected.particle_size_override_enabled = true;
        expected.particle_size_mode = PRIVATE_PARTICLE_SIZE_MODE_SPHERE_PERCENT;
        expected.particle_size_sphere_percent = 6.25;
        expected.particle_size_parameter_source = "same-apk-panel-live-explicit-size";
        assert_eq!(settings, expected);

        let material_defaults = before.material_defaults();
        settings.apply_panel_material_override(patch, material_defaults);
        assert_eq!(
            settings, expected,
            "an unselected material must remain untouched"
        );
    }

    #[test]
    fn independent_field_patches_merge_without_overwriting_each_other() {
        let mut visual_patch = panel_settings(None, false, false);
        visual_patch.update_mask = PrivateParticlePanelUpdateMask::empty();
        visual_patch.update_mask.visual_scale = true;
        visual_patch.visual_scale = 0.55;

        let mut tracer_patch = panel_settings(None, false, false);
        tracer_patch.update_mask = PrivateParticlePanelUpdateMask::empty();
        tracer_patch.update_mask.tracer_lifetime_seconds = true;
        tracer_patch.tracer_lifetime_seconds = 3.25;
        tracer_patch.material_override_enabled = true;
        tracer_patch.material_preset = Some(PrivateParticleMaterialPreset::AkdMaterialEmulation);

        visual_patch.merge_patch(tracer_patch);

        assert!(visual_patch.update_mask.visual_scale);
        assert!(visual_patch.update_mask.tracer_lifetime_seconds);
        assert!(!visual_patch.update_mask.material);
        assert_eq!(visual_patch.visual_scale, 0.55);
        assert_eq!(visual_patch.tracer_lifetime_seconds, 3.25);
        assert!(!visual_patch.material_override_enabled);
        assert_eq!(visual_patch.material_preset, None);
    }

    #[test]
    fn absent_particle_size_override_keeps_reserved_aux_row_neutral() {
        let settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        let rows = private_particle_driver_bank_rows(settings);
        let size_aux_row = PRIVATE_PARTICLE_DRIVER_BANK_VALUE_VEC4_ROWS
            + 2 * PRIVATE_PARTICLE_DRIVER_CONTROL_ROWS_PER_SLOT
            + 2;
        assert_eq!(rows[size_aux_row], [0.0; 4]);
        assert_eq!(
            settings.particle_size_mode,
            PRIVATE_PARTICLE_SIZE_MODE_LEGACY
        );
    }

    #[test]
    fn panel_material_selection_wins_over_an_earlier_fenced_request() {
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        let material_defaults = settings.material_defaults();
        let panel = panel_settings(
            Some(PrivateParticleMaterialPreset::PremultipliedAlphaOver),
            true,
            false,
        );
        settings.apply_panel_override(panel, [0.0; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT]);
        assert_eq!(
            settings.material_preset,
            Some(PrivateParticleMaterialPreset::PremultipliedAlphaOver)
        );
        assert_eq!(settings.material_parameter_source, "same-apk-panel-live");

        settings.apply_material_preset(PrivateParticleMaterialPreset::AkdMaterialEmulation);
        settings.apply_panel_material_override(panel, material_defaults);
        assert_eq!(
            settings.material_preset,
            Some(PrivateParticleMaterialPreset::PremultipliedAlphaOver)
        );
        assert_eq!(settings.material_parameter_source, "same-apk-panel-live");
    }

    #[test]
    fn explicit_panel_packaged_default_clears_an_earlier_material_request() {
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        let material_defaults = settings.material_defaults();
        settings.apply_material_preset(PrivateParticleMaterialPreset::AkdMaterialEmulation);
        settings
            .apply_panel_material_override(panel_settings(None, true, false), material_defaults);
        assert_eq!(settings.material_preset, None);
        assert_eq!(
            settings.material_parameter_source,
            "same-apk-panel-live-packaged-default"
        );
        assert_eq!(
            settings.transparency_blend_mode,
            material_defaults.transparency_blend_mode
        );
    }

    #[test]
    fn panel_rr_toggle_changes_only_the_closed_orbit_radius_control_route() {
        let disabled = panel_settings(None, false, false).clamped();
        let enabled = panel_settings(None, false, true).clamped();
        assert_eq!(disabled.driver_control_modes[5], PANEL_DRIVER_MODE_DIRECT);
        assert_eq!(
            enabled.driver_control_modes[5],
            PANEL_DRIVER_MODE_PRIVATE_HEARTBEAT_ORBIT
        );
        assert_eq!(enabled.driver_control_source_slots[5], 5);
        assert_eq!(enabled.driver_control_curve_codes[5], PANEL_CURVE_AKD_HUMP);
        for index in 0..GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT {
            if index != 5 {
                assert_eq!(
                    enabled.driver_control_modes[index],
                    disabled.driver_control_modes[index]
                );
            }
        }
    }

    #[test]
    fn explicit_runtime_disable_overrides_enabled_panel_without_touching_other_slots() {
        let panel = panel_settings(None, false, true).clamped();
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        settings.apply_panel_override(panel, [0.0; GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT]);
        let modes_before = settings.driver_control_modes;
        let curves_before = settings.driver_control_curve_codes;
        let ranges_before = (
            settings.driver_control_range_mins,
            settings.driver_control_range_maxs,
        );

        settings.apply_heartbeat_orbit_control(Some(false));

        assert_eq!(settings.driver_control_modes[5], PANEL_DRIVER_MODE_DIRECT);
        assert_eq!(settings.driver_control_curve_codes[5], PANEL_CURVE_LINEAR);
        assert_eq!(settings.driver_control_range_mins, ranges_before.0);
        assert_eq!(settings.driver_control_range_maxs, ranges_before.1);
        for index in 0..GPU_PRIVATE_PARTICLE_PANEL_DRIVER_COUNT {
            if index != 5 {
                assert_eq!(settings.driver_control_modes[index], modes_before[index]);
                assert_eq!(
                    settings.driver_control_curve_codes[index],
                    curves_before[index]
                );
            }
        }
    }

    #[test]
    fn absent_runtime_heartbeat_request_preserves_packaged_controls() {
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        settings.driver_control_modes[5] = PANEL_DRIVER_MODE_INPUT_SLOT;
        settings.driver_control_curve_codes[5] = PANEL_CURVE_HOLD_HIGH;
        let modes_before = settings.driver_control_modes;
        let curves_before = settings.driver_control_curve_codes;

        settings.apply_heartbeat_orbit_control(None);

        assert_eq!(settings.driver_control_modes, modes_before);
        assert_eq!(settings.driver_control_curve_codes, curves_before);
    }

    #[test]
    fn blend_mode_has_an_exact_non_overlapping_push_constant_encoding() {
        let mut additive_settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        additive_settings.color_facing_attenuation_strength = 0.20;
        additive_settings.transparency_blend_mode = PrivateParticleTransparencyBlendMode::Additive;
        let mut alpha_over_settings = additive_settings;
        alpha_over_settings.transparency_blend_mode =
            PrivateParticleTransparencyBlendMode::PremultipliedAlphaOver;
        let additive = private_particle_packed_mode_code(additive_settings);
        let alpha_over = private_particle_packed_mode_code(alpha_over_settings);
        assert_eq!(
            alpha_over - additive,
            PRIVATE_PARTICLE_BLEND_MODE_PACK_OFFSET as f32
        );
        assert_eq!(
            alpha_over as u32 / PRIVATE_PARTICLE_BLEND_MODE_PACK_OFFSET,
            1
        );
    }
}

#[cfg(test)]
mod render_experiment_request_tests {
    use super::*;

    #[test]
    fn render_experiment_changes_only_primitive_coverage_and_discard_policy() {
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        let visual_scale = settings.visual_scale;
        let tracer_slots = settings.tracer_draw_slots_per_oscillator;
        let material = settings.material_preset;
        let blend = settings.transparency_blend_mode;

        settings.apply_render_experiment_preset(
            PrivateParticleRenderExperimentPreset::StaticRingAnnulus12,
        );

        assert_eq!(settings.visual_scale, visual_scale);
        assert_eq!(settings.tracer_draw_slots_per_oscillator, tracer_slots);
        assert_eq!(settings.material_preset, material);
        assert_eq!(settings.transparency_blend_mode, blend);
        assert_eq!(
            private_particle_vertices_per_instance(settings),
            72,
            "the static annulus is the only higher-vertex primitive",
        );
        assert_eq!(
            private_particle_render_experiment_preset(settings).mask_discard_code(),
            0,
        );
    }

    #[test]
    fn square_no_discard_keeps_the_six_vertex_control_geometry() {
        let mut settings = PrivateParticleRuntimeSettings::from_generated_defaults();
        settings
            .apply_render_experiment_preset(PrivateParticleRenderExperimentPreset::SquareNoDiscard);
        assert_eq!(private_particle_vertices_per_instance(settings), 6);
        assert_eq!(
            private_particle_render_experiment_preset(settings).mask_discard_code(),
            1,
        );
    }
}
