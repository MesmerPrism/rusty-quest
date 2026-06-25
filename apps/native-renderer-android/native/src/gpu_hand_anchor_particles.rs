//! Native Vulkan hand-anchor particle billboards over resident GPU-skinned hand meshes.

use std::{ffi::CString, mem};

use ash::vk;

use crate::{
    gpu_hand_mesh_visual::{
        GpuHandMeshVisualFrameSetStats, GpuHandMeshVisualFrameStats, HandMeshVisualEyeProjection,
    },
    gpu_sdf_field::SkinnedHandMeshDrawResources,
    native_renderer_options::{
        NativeHandAnchorParticleSettings, NativeHandAnchorParticleTransparencyBlendMode,
    },
};

include!(concat!(
    env!("OUT_DIR"),
    "/private_kuramoto_payload_config.rs"
));

const PARTICLE_VERTICES_PER_INSTANCE: u32 = 6;
const PARTICLE_SORT_LOCAL_SIZE: u32 = 128;
const PARTICLE_OUTPUT_ROW_VEC4S: vk::DeviceSize = 4;
const PARTICLE_OUTPUT_ROW_BYTES: vk::DeviceSize =
    PARTICLE_OUTPUT_ROW_VEC4S * mem::size_of::<[f32; 4]>() as vk::DeviceSize;
const PARTICLE_SORT_ROW_BYTES: vk::DeviceSize = mem::size_of::<[u32; 4]>() as vk::DeviceSize;
const KURAMOTO_DEFAULT_DRIVER_VALUES01: [f32; 8] = [0.25, 0.15, 0.5, 0.0, 0.5, 0.5, 0.0, 0.0];
const KURAMOTO_PROFILE_IDS: [&str; 4] = [
    "kuramoto.private.native.profile.low-energy-low-coherence.movement-only.v1",
    "kuramoto.private.native.profile.high-energy-low-coherence.movement-only.v1",
    "kuramoto.private.native.profile.low-energy-high-coherence.movement-only.v1",
    "kuramoto.private.native.profile.high-energy-high-coherence.movement-only.v1",
];
const KURAMOTO_SURFACE_TARGET_IDS: [&str; 3] = ["real-hands", "gpu-replay-hands", "icosphere"];

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct GpuHandAnchorParticleFrameStats {
    pub(crate) ready: bool,
    pub(crate) visible: bool,
    pub(crate) handedness: &'static str,
    pub(crate) particles_drawn: u32,
    pub(crate) triangle_count: u32,
    pub(crate) skinned_position_buffer_bytes: u64,
    pub(crate) live_compact_input_frame: bool,
    pub(crate) input_source: &'static str,
    pub(crate) readiness_reason: &'static str,
    pub(crate) center_position: [f32; 4],
}

impl GpuHandAnchorParticleFrameStats {
    fn from_hand_mesh(
        hand_mesh: &GpuHandMeshVisualFrameStats,
        settings: NativeHandAnchorParticleSettings,
    ) -> Self {
        let selected_for_private_kuramoto = !settings.private_gpu_payload_requested()
            || private_kuramoto_realtime_hand_surface_selected();
        let input_source = if hand_mesh.live_compact_input_frame {
            "live-meta-openxr-hand-tracking"
        } else {
            "recorded-replay-fallback"
        };
        let readiness_reason = if !settings.enabled {
            "disabled"
        } else if !selected_for_private_kuramoto {
            "surface-target-not-real-hands"
        } else if !hand_mesh.ready {
            "awaiting-skinned-hand-mesh"
        } else if hand_mesh.triangle_count == 0 {
            "empty-hand-mesh"
        } else if hand_mesh.live_compact_input_frame {
            "ready-live-hand-frame"
        } else {
            "ready-recorded-replay-fallback"
        };
        let ready = settings.enabled
            && selected_for_private_kuramoto
            && hand_mesh.ready
            && hand_mesh.triangle_count > 0;
        Self {
            ready,
            visible: ready,
            handedness: hand_mesh.handedness,
            particles_drawn: if ready {
                settings.particles_per_hand
            } else {
                0
            },
            triangle_count: hand_mesh.triangle_count,
            skinned_position_buffer_bytes: hand_mesh.skinned_position_buffer_bytes,
            live_compact_input_frame: hand_mesh.live_compact_input_frame,
            input_source,
            readiness_reason,
            center_position: hand_mesh.center_position,
        }
    }

    fn marker_fields(&self, prefix: &str) -> String {
        format!(
            "{prefix}Ready={} {prefix}Visible={} {prefix}Hand={} {prefix}ParticleCount={} {prefix}TriangleCount={} {prefix}SkinnedPositionBufferBytes={} {prefix}LiveCompactInputFrame={} {prefix}InputSource={} {prefix}ReadinessReason={}",
            self.ready,
            self.visible,
            self.handedness,
            self.particles_drawn,
            self.triangle_count,
            self.skinned_position_buffer_bytes,
            self.live_compact_input_frame,
            self.input_source,
            self.readiness_reason
        )
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct GpuHandAnchorParticleFrameSetStats {
    pub(crate) primary: GpuHandAnchorParticleFrameStats,
    pub(crate) secondary: GpuHandAnchorParticleFrameStats,
    pub(crate) settings: NativeHandAnchorParticleSettings,
}

impl GpuHandAnchorParticleFrameSetStats {
    pub(crate) fn new(
        hand_mesh_stats: &GpuHandMeshVisualFrameSetStats,
        settings: NativeHandAnchorParticleSettings,
    ) -> Self {
        Self {
            primary: GpuHandAnchorParticleFrameStats::from_hand_mesh(
                &hand_mesh_stats.primary,
                settings,
            ),
            secondary: GpuHandAnchorParticleFrameStats::from_hand_mesh(
                &hand_mesh_stats.secondary,
                settings,
            ),
            settings,
        }
    }

    pub(crate) fn total_particles_drawn(&self) -> u32 {
        self.primary
            .particles_drawn
            .saturating_add(self.secondary.particles_drawn)
    }

    pub(crate) fn marker_fields(&self) -> String {
        let panel_dynamics = private_kuramoto_panel_dynamics();
        format!(
            "{} kuramotoSurfaceTarget={} kuramotoProfileId={} handAnchorParticleTotalCount={} handAnchorParticleBothHandsVisible={} handAnchorParticleReadinessReason={} {} {}",
            self.settings.marker_fields(),
            panel_dynamics.surface_target_id(),
            panel_dynamics.profile_id(),
            self.total_particles_drawn(),
            self.primary.visible && self.secondary.visible,
            self.primary.readiness_reason,
            self.primary.marker_fields("handAnchorParticlePrimary"),
            self.secondary.marker_fields("handAnchorParticleSecondary"),
        )
    }
}

#[derive(Clone, Copy, Debug)]
struct PrivateKuramotoPanelDynamics {
    surface_index: usize,
    condition_index: usize,
    base_frequency_hz: f32,
    frequency_spread_hz: f32,
    movement_coupling: f32,
    unit_distance_m: f32,
    noise_frequency: f32,
    noise_amplitude_m: f32,
    noise_speed_hz: f32,
}

impl PrivateKuramotoPanelDynamics {
    fn from_driver_values(driver_values01: [f32; 8]) -> Self {
        let surface_index = if driver_values01[5] < 0.25 {
            0
        } else if driver_values01[5] >= 0.75 {
            2
        } else {
            1
        };
        let condition_index = (driver_values01[6].clamp(0.0, 1.0) * 3.0).round() as usize;
        let high_energy01 = smoothstep(0.45, 0.85, driver_values01[0].clamp(0.0, 1.0));
        let base_frequency_hz = (driver_values01[2].clamp(0.0, 1.0) * 0.88).max(0.0);
        let frequency_spread_hz = ((1.0 - driver_values01[3].clamp(0.0, 1.0)) * 0.62).max(0.0);
        let movement_coupling = smoothstep(0.35, 0.85, driver_values01[1].clamp(0.0, 1.0));
        let unit_distance_m = (driver_values01[4].clamp(0.0, 1.0) * 0.004).max(0.0005);
        Self {
            surface_index,
            condition_index: condition_index.min(KURAMOTO_PROFILE_IDS.len() - 1),
            base_frequency_hz,
            frequency_spread_hz,
            movement_coupling,
            unit_distance_m,
            noise_frequency: high_energy01 * 6.7,
            noise_amplitude_m: high_energy01 * 0.004,
            noise_speed_hz: high_energy01 * 0.5,
        }
    }

    fn profile_id(self) -> &'static str {
        KURAMOTO_PROFILE_IDS[self.condition_index]
    }

    fn surface_target_id(self) -> &'static str {
        KURAMOTO_SURFACE_TARGET_IDS[self.surface_index]
    }
}

fn private_kuramoto_panel_dynamics() -> PrivateKuramotoPanelDynamics {
    PrivateKuramotoPanelDynamics::from_driver_values(private_kuramoto_driver_values01())
}

fn private_kuramoto_realtime_hand_surface_selected() -> bool {
    private_kuramoto_panel_dynamics().surface_index == 0
}

fn private_kuramoto_driver_values01() -> [f32; 8] {
    #[cfg(target_os = "android")]
    {
        if let Some(candidate) =
            crate::native_renderer_stimulus_panel::latest_live_private_particle_dynamics()
        {
            return candidate.driver_values01;
        }
    }
    KURAMOTO_DEFAULT_DRIVER_VALUES01
}

fn smoothstep(edge0: f32, edge1: f32, value: f32) -> f32 {
    let t = ((value - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

pub(crate) struct GpuHandAnchorParticleRenderer {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    sort_resources: ParticleSortResources,
    draw_resources: SkinnedHandMeshDrawResources,
    hand_code: u32,
    fallback_particle_output_buffer: Option<OwnedBuffer>,
    private_kuramoto: Option<PrivateKuramotoParticleDynamics>,
}

impl GpuHandAnchorParticleRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        draw_resources: SkinnedHandMeshDrawResources,
        handedness: &'static str,
        settings: NativeHandAnchorParticleSettings,
    ) -> Result<Self, String> {
        if draw_resources.vertex_count == 0 || draw_resources.triangle_count == 0 {
            return Err("resident skinned hand mesh particle draw resources are empty".to_string());
        }
        let mut private_kuramoto = match PrivateKuramotoParticleDynamics::new(
            device,
            memory_properties,
            draw_resources,
            handedness,
            settings,
        ) {
            Ok(runtime) => runtime,
            Err(error) => {
                crate::marker(
                    "hand-anchor-particles",
                    format!(
                        "status=private-kuramoto-unavailable reason={} handAnchorParticleHand={} privateKuramotoPayloadLinked={} handAnchorParticleFallback=deterministic-gpu-barycentric-triangle-anchors",
                        crate::sanitize(&error),
                        handedness,
                        PRIVATE_KURAMOTO_PAYLOAD_LINKED,
                    ),
                );
                None
            }
        };
        let fallback_particle_output_buffer = if private_kuramoto.is_some() {
            None
        } else {
            Some(OwnedBuffer::new(
                device,
                memory_properties,
                PARTICLE_OUTPUT_ROW_BYTES,
                vk::BufferUsageFlags::STORAGE_BUFFER,
                "fallback hand anchor particle output",
            )?)
        };
        let particle_output_buffer = private_kuramoto
            .as_ref()
            .map(PrivateKuramotoParticleDynamics::particle_output_descriptor)
            .or_else(|| {
                fallback_particle_output_buffer
                    .as_ref()
                    .map(OwnedBuffer::descriptor)
            })
            .ok_or_else(|| "hand anchor particle output buffer unavailable".to_string())?;
        let sort_capacity = settings.particles_per_hand.max(1).next_power_of_two();
        let sort_resources = match ParticleSortResources::new(
            device,
            memory_properties,
            particle_output_buffer,
            sort_capacity,
        ) {
            Ok(resources) => resources,
            Err(error) => {
                if let Some(buffer) = fallback_particle_output_buffer.as_ref() {
                    buffer.destroy(device);
                }
                if let Some(private_kuramoto) = private_kuramoto.as_mut() {
                    private_kuramoto.destroy(device);
                }
                return Err(error);
            }
        };

        let bindings = [
            storage_binding(0, vk::ShaderStageFlags::VERTEX),
            storage_binding(1, vk::ShaderStageFlags::VERTEX),
            storage_binding(2, vk::ShaderStageFlags::VERTEX),
            storage_binding(3, vk::ShaderStageFlags::VERTEX),
        ];
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                sort_resources.destroy(device);
                if let Some(buffer) = fallback_particle_output_buffer.as_ref() {
                    buffer.destroy(device);
                }
                if let Some(private_kuramoto) = private_kuramoto.as_mut() {
                    private_kuramoto.destroy(device);
                }
                return Err(format!(
                    "create hand anchor particle descriptor layout: {error}"
                ));
            }
        };

        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(4)];
        let descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(1),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                sort_resources.destroy(device);
                if let Some(buffer) = fallback_particle_output_buffer.as_ref() {
                    buffer.destroy(device);
                }
                if let Some(private_kuramoto) = private_kuramoto.as_mut() {
                    private_kuramoto.destroy(device);
                }
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(format!(
                    "create hand anchor particle descriptor pool: {error}"
                ));
            }
        };

        let set_layouts = [descriptor_set_layout];
        let descriptor_set = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&set_layouts),
        ) {
            Ok(mut sets) => sets.remove(0),
            Err(error) => {
                sort_resources.destroy(device);
                if let Some(buffer) = fallback_particle_output_buffer.as_ref() {
                    buffer.destroy(device);
                }
                if let Some(private_kuramoto) = private_kuramoto.as_mut() {
                    private_kuramoto.destroy(device);
                }
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(format!(
                    "allocate hand anchor particle descriptor set: {error}"
                ));
            }
        };
        update_descriptors(
            device,
            descriptor_set,
            draw_resources,
            particle_output_buffer,
            sort_resources.remap_descriptor(),
        );

        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX)
            .offset(0)
            .size(mem::size_of::<HandAnchorParticlePush>() as u32)];
        let pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&set_layouts)
                .push_constant_ranges(&push_ranges),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                sort_resources.destroy(device);
                if let Some(buffer) = fallback_particle_output_buffer.as_ref() {
                    buffer.destroy(device);
                }
                if let Some(private_kuramoto) = private_kuramoto.as_mut() {
                    private_kuramoto.destroy(device);
                }
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(format!(
                    "create hand anchor particle pipeline layout: {error}"
                ));
            }
        };

        let pipeline = match create_pipeline(
            device,
            render_pass,
            pipeline_layout,
            settings.transparency_blend_mode,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                sort_resources.destroy(device);
                if let Some(buffer) = fallback_particle_output_buffer.as_ref() {
                    buffer.destroy(device);
                }
                if let Some(private_kuramoto) = private_kuramoto.as_mut() {
                    private_kuramoto.destroy(device);
                }
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };

        crate::marker(
            "hand-anchor-particles",
            format!(
                "status=created handAnchorParticleHand={} handAnchorParticlePath=resident-skinned-mesh-coordinate-anchor-billboards handAnchorParticleCoordinateSource={} handAnchorParticleFallbackActive={} handAnchorParticleCoordinateSpace=openxr-reference-space handAnchorParticleMask=static-feather-dot-luminance-alpha handAnchorParticleAnimation=false handAnchorParticleTriangleCount={} handAnchorParticleVertexCount={} handAnchorParticleSkinnedPositionBufferBytes={} handAnchorParticleTriangleBufferBytes={} handAnchorParticleCpuExpandedUploadPerFrame=false handAnchorParticleMeshUploadPerFrame=false {}",
                handedness,
                if private_kuramoto.is_some() {
                    "private-kuramoto-gpu-payload"
                } else {
                    "deterministic-gpu-barycentric-triangle-anchors"
                },
                private_kuramoto.is_none(),
                draw_resources.triangle_count,
                draw_resources.vertex_count,
                draw_resources.skinned_position_buffer_bytes,
                draw_resources.triangle_buffer_bytes,
                settings.marker_fields(),
            ),
        );

        Ok(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_set,
            pipeline_layout,
            pipeline,
            sort_resources,
            draw_resources,
            hand_code: if handedness == "right" { 2 } else { 1 },
            fallback_particle_output_buffer,
            private_kuramoto,
        })
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(private_kuramoto) = self.private_kuramoto.as_mut() {
            private_kuramoto.destroy(device);
        }
        if let Some(buffer) = self.fallback_particle_output_buffer.as_ref() {
            buffer.destroy(device);
        }
        self.sort_resources.destroy(device);
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
    }

    pub(crate) unsafe fn record_compute_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        stats: &GpuHandAnchorParticleFrameStats,
        settings: NativeHandAnchorParticleSettings,
        frame_count: u64,
    ) {
        if !stats.ready || stats.particles_drawn == 0 {
            return;
        }
        if let Some(private_kuramoto) = self.private_kuramoto.as_mut() {
            private_kuramoto.record_compute_frame(
                device,
                cmd,
                stats.particles_drawn,
                settings.radius_m,
                frame_count,
                self.hand_code,
            );
        }
    }

    pub(crate) unsafe fn record_sort_frame(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        stats: &GpuHandAnchorParticleFrameStats,
        settings: NativeHandAnchorParticleSettings,
        eye_projection: HandMeshVisualEyeProjection,
        frame_count: u64,
    ) {
        if !self.resident_gpu_sort_active(stats, settings) {
            if frame_count == 0
                && settings.resident_gpu_particle_sort_requested()
                && self.private_kuramoto.is_none()
            {
                crate::marker(
                    "hand-anchor-particles",
                    format!(
                        "status=gpu-sort-unavailable reason=no-gpu-particle-output handAnchorParticleHand={} handAnchorParticleGpuSortActive=false handAnchorParticleOrderingImplementation={} handAnchorParticleOrderingCpuExpandedUploadPerFrame=false",
                        stats.handedness,
                        settings.ordering_implementation.marker_value(),
                    ),
                );
            }
            return;
        }

        self.sort_resources
            .record_sort_frame(device, cmd, stats.particles_drawn, eye_projection);
        if frame_count == 0 {
            crate::marker(
                "hand-anchor-particles",
                format!(
                    "status=gpu-sort-active handAnchorParticleHand={} handAnchorParticleGpuSortActive=true handAnchorParticleSortPath=resident-gpu-index-remap handAnchorParticleSortBasis=primary-eye-openxr-reference-space handAnchorParticleSortCount={} handAnchorParticleSortCapacity={} handAnchorParticleOrderingMode={} handAnchorParticleOrderingImplementation={} handAnchorParticleOrderingCpuExpandedUploadPerFrame=false",
                    stats.handedness,
                    stats.particles_drawn.min(self.sort_resources.capacity),
                    self.sort_resources.capacity,
                    settings.ordering_mode.marker_value(),
                    settings.ordering_implementation.marker_value(),
                ),
            );
        }
    }

    pub(crate) unsafe fn record_overlay_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_projection: HandMeshVisualEyeProjection,
        stats: &GpuHandAnchorParticleFrameStats,
        settings: NativeHandAnchorParticleSettings,
    ) {
        if !stats.ready || stats.particles_drawn == 0 {
            return;
        }
        let push = HandAnchorParticlePush {
            params0: [
                self.draw_resources.triangle_count as f32,
                stats.particles_drawn as f32,
                settings.radius_m,
                self.hand_code as f32,
            ],
            params1: [
                if self.private_kuramoto.is_some() {
                    1.0
                } else {
                    0.0
                },
                if settings.transparency_blend_mode.premultiply_rgb() {
                    1.0
                } else {
                    0.0
                },
                settings.transparency_composition_mode.shader_code(),
                settings.transparency_depth_suppression_strength,
            ],
            params2: [
                if self.resident_gpu_sort_active(stats, settings) {
                    1.0
                } else {
                    0.0
                },
                0.0,
                0.0,
                0.0,
            ],
            eye_position: eye_projection.position,
            eye_orientation_xyzw: eye_projection.orientation_xyzw,
            fov_tangents: eye_projection.fov_tangents,
        };
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
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
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
            vk::ShaderStageFlags::VERTEX,
            0,
            as_bytes(&push),
        );
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_draw(
            cmd,
            PARTICLE_VERTICES_PER_INSTANCE,
            stats.particles_drawn,
            0,
            0,
        );
    }

    fn resident_gpu_sort_active(
        &self,
        stats: &GpuHandAnchorParticleFrameStats,
        settings: NativeHandAnchorParticleSettings,
    ) -> bool {
        stats.ready
            && stats.particles_drawn > 1
            && self.private_kuramoto.is_some()
            && settings.resident_gpu_particle_sort_requested()
    }
}

struct ParticleSortResources {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    remap_buffer: OwnedBuffer,
    capacity: u32,
}

impl ParticleSortResources {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        particle_output_buffer: vk::DescriptorBufferInfo,
        capacity: u32,
    ) -> Result<Self, String> {
        let capacity = capacity.max(1);
        let remap_buffer = OwnedBuffer::new(
            device,
            memory_properties,
            capacity as vk::DeviceSize * PARTICLE_SORT_ROW_BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "hand anchor particle resident GPU sort remap",
        )?;

        let bindings = [
            storage_binding(0, vk::ShaderStageFlags::COMPUTE),
            storage_binding(1, vk::ShaderStageFlags::COMPUTE),
        ];
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                remap_buffer.destroy(device);
                return Err(format!(
                    "create hand anchor particle sort descriptor layout: {error}"
                ));
            }
        };

        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(2)];
        let descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(1),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                remap_buffer.destroy(device);
                return Err(format!(
                    "create hand anchor particle sort descriptor pool: {error}"
                ));
            }
        };

        let set_layouts = [descriptor_set_layout];
        let descriptor_set = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&set_layouts),
        ) {
            Ok(mut sets) => sets.remove(0),
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                remap_buffer.destroy(device);
                return Err(format!(
                    "allocate hand anchor particle sort descriptor set: {error}"
                ));
            }
        };
        update_particle_sort_descriptors(
            device,
            descriptor_set,
            particle_output_buffer,
            remap_buffer.descriptor(),
        );

        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE)
            .offset(0)
            .size(mem::size_of::<ParticleSortComputePush>() as u32)];
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
                remap_buffer.destroy(device);
                return Err(format!(
                    "create hand anchor particle sort pipeline layout: {error}"
                ));
            }
        };

        let pipeline = match create_compute_pipeline(
            device,
            pipeline_layout,
            include_bytes!(concat!(
                env!("OUT_DIR"),
                "/hand_anchor_particles_sort.comp.spv"
            )),
            "hand anchor particle sort",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                remap_buffer.destroy(device);
                return Err(error);
            }
        };

        Ok(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_set,
            pipeline_layout,
            pipeline,
            remap_buffer,
            capacity,
        })
    }

    fn remap_descriptor(&self) -> vk::DescriptorBufferInfo {
        self.remap_buffer.descriptor()
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        self.remap_buffer.destroy(device);
    }

    unsafe fn record_sort_frame(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        particle_count: u32,
        eye_projection: HandMeshVisualEyeProjection,
    ) {
        let particle_count = particle_count.min(self.capacity);
        if particle_count <= 1 {
            return;
        }
        let sort_count = particle_count.next_power_of_two().min(self.capacity);
        let group_count = sort_count.div_ceil(PARTICLE_SORT_LOCAL_SIZE);
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::COMPUTE, self.pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.pipeline_layout,
            0,
            &[self.descriptor_set],
            &[],
        );
        let eye_forward = rotate_by_quat(eye_projection.orientation_xyzw, [0.0, 0.0, -1.0]);
        let init_push = ParticleSortComputePush {
            params0: [particle_count as f32, sort_count as f32, 0.0, 0.0],
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
                let sort_push = ParticleSortComputePush {
                    params0: [particle_count as f32, sort_count as f32, 1.0, j as f32],
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

        let vertex_barrier = [compute_write_to_shader_read_barrier(&self.remap_buffer)];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::VERTEX_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &vertex_barrier,
            &[],
        );
    }

    unsafe fn dispatch_sort_pass(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        push: &ParticleSortComputePush,
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
            .buffer(self.remap_buffer.buffer)
            .offset(0)
            .size(self.remap_buffer.bytes)];
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
}

struct PrivateKuramotoParticleDynamics {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_sets: [vk::DescriptorSet; 2],
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    coordinate_triangle_buffer: OwnedBuffer,
    coordinate_barycentric_buffer: OwnedBuffer,
    surface_edge_buffer: OwnedBuffer,
    surface_meter_buffer: OwnedBuffer,
    surface_offset_buffer: OwnedBuffer,
    small_world_edge_buffer: OwnedBuffer,
    small_world_offset_buffer: OwnedBuffer,
    movement_phase_buffers: [OwnedBuffer; 2],
    jerk_phase_buffers: [OwnedBuffer; 2],
    particle_output_buffer: OwnedBuffer,
    sample_count: u32,
    surface_edge_count: u32,
    small_world_edge_count: u32,
    handedness: &'static str,
}

impl PrivateKuramotoParticleDynamics {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        draw_resources: SkinnedHandMeshDrawResources,
        handedness: &'static str,
        settings: NativeHandAnchorParticleSettings,
    ) -> Result<Option<Self>, String> {
        if !settings.private_gpu_payload_requested() {
            return Ok(None);
        }
        if !PRIVATE_KURAMOTO_PAYLOAD_LINKED {
            crate::marker(
                "hand-anchor-particles",
                format!(
                    "status=private-kuramoto-unavailable reason=payload-not-linked handAnchorParticleHand={} privateKuramotoPayloadLinked=false",
                    handedness
                ),
            );
            return Ok(None);
        }

        let payload = PrivateKuramotoPayload::load(handedness)?;
        let sample_count = settings
            .particles_per_hand
            .min(PRIVATE_KURAMOTO_SAMPLE_COUNT as u32)
            .max(1);
        let graph = PrivateKuramotoGraphBuffers::from_payload(&payload, sample_count as usize)?;
        let zero_phase_rows = vec![[0.0_f32; 4]; sample_count as usize];
        let zero_particle_rows =
            vec![[0.0_f32; 4]; sample_count as usize * PARTICLE_OUTPUT_ROW_VEC4S as usize];

        let coordinate_triangle_buffer = OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "private Kuramoto coordinate triangle indices",
            &payload.coordinate_triangles[..sample_count as usize],
        )?;
        let coordinate_barycentric_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "private Kuramoto coordinate barycentric rows",
            &payload.coordinate_barycentric[..sample_count as usize * 3],
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };
        let surface_edge_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "private Kuramoto surface distance edges",
            &graph.surface_edges,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                coordinate_barycentric_buffer.destroy(device);
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };
        let surface_meter_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "private Kuramoto surface distance meters",
            &graph.surface_meters,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                surface_edge_buffer.destroy(device);
                coordinate_barycentric_buffer.destroy(device);
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };
        let surface_offset_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "private Kuramoto surface distance offsets",
            &graph.surface_offsets,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                surface_meter_buffer.destroy(device);
                surface_edge_buffer.destroy(device);
                coordinate_barycentric_buffer.destroy(device);
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };
        let small_world_edge_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "private Kuramoto small-world edges",
            &graph.small_world_edges,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                surface_offset_buffer.destroy(device);
                surface_meter_buffer.destroy(device);
                surface_edge_buffer.destroy(device);
                coordinate_barycentric_buffer.destroy(device);
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };
        let small_world_offset_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "private Kuramoto small-world offsets",
            &graph.small_world_offsets,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                small_world_edge_buffer.destroy(device);
                surface_offset_buffer.destroy(device);
                surface_meter_buffer.destroy(device);
                surface_edge_buffer.destroy(device);
                coordinate_barycentric_buffer.destroy(device);
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };
        let movement_phase_buffers = match create_phase_buffer_pair(
            device,
            memory_properties,
            &zero_phase_rows,
            "private Kuramoto movement phases",
        ) {
            Ok(buffers) => buffers,
            Err(error) => {
                small_world_offset_buffer.destroy(device);
                small_world_edge_buffer.destroy(device);
                surface_offset_buffer.destroy(device);
                surface_meter_buffer.destroy(device);
                surface_edge_buffer.destroy(device);
                coordinate_barycentric_buffer.destroy(device);
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };
        let jerk_phase_buffers = match create_phase_buffer_pair(
            device,
            memory_properties,
            &zero_phase_rows,
            "private Kuramoto jerk phases",
        ) {
            Ok(buffers) => buffers,
            Err(error) => {
                destroy_buffer_pair(device, &movement_phase_buffers);
                small_world_offset_buffer.destroy(device);
                small_world_edge_buffer.destroy(device);
                surface_offset_buffer.destroy(device);
                surface_meter_buffer.destroy(device);
                surface_edge_buffer.destroy(device);
                coordinate_barycentric_buffer.destroy(device);
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };
        let particle_output_buffer = match OwnedBuffer::new_with_data(
            device,
            memory_properties,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "private Kuramoto particle output",
            &zero_particle_rows,
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                destroy_buffer_pair(device, &jerk_phase_buffers);
                destroy_buffer_pair(device, &movement_phase_buffers);
                small_world_offset_buffer.destroy(device);
                small_world_edge_buffer.destroy(device);
                surface_offset_buffer.destroy(device);
                surface_meter_buffer.destroy(device);
                surface_edge_buffer.destroy(device);
                coordinate_barycentric_buffer.destroy(device);
                coordinate_triangle_buffer.destroy(device);
                return Err(error);
            }
        };

        let bindings = (0..14)
            .map(|binding| storage_binding(binding, vk::ShaderStageFlags::COMPUTE))
            .collect::<Vec<_>>();
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                destroy_private_kuramoto_buffers(
                    device,
                    &coordinate_triangle_buffer,
                    &coordinate_barycentric_buffer,
                    &surface_edge_buffer,
                    &surface_meter_buffer,
                    &surface_offset_buffer,
                    &small_world_edge_buffer,
                    &small_world_offset_buffer,
                    &movement_phase_buffers,
                    &jerk_phase_buffers,
                    &particle_output_buffer,
                );
                return Err(format!(
                    "create private Kuramoto descriptor layout: {error}"
                ));
            }
        };
        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(28)];
        let descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(2),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_private_kuramoto_buffers(
                    device,
                    &coordinate_triangle_buffer,
                    &coordinate_barycentric_buffer,
                    &surface_edge_buffer,
                    &surface_meter_buffer,
                    &surface_offset_buffer,
                    &small_world_edge_buffer,
                    &small_world_offset_buffer,
                    &movement_phase_buffers,
                    &jerk_phase_buffers,
                    &particle_output_buffer,
                );
                return Err(format!("create private Kuramoto descriptor pool: {error}"));
            }
        };
        let set_layouts = [descriptor_set_layout, descriptor_set_layout];
        let descriptor_sets_vec = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&set_layouts),
        ) {
            Ok(sets) => sets,
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_private_kuramoto_buffers(
                    device,
                    &coordinate_triangle_buffer,
                    &coordinate_barycentric_buffer,
                    &surface_edge_buffer,
                    &surface_meter_buffer,
                    &surface_offset_buffer,
                    &small_world_edge_buffer,
                    &small_world_offset_buffer,
                    &movement_phase_buffers,
                    &jerk_phase_buffers,
                    &particle_output_buffer,
                );
                return Err(format!(
                    "allocate private Kuramoto descriptor sets: {error}"
                ));
            }
        };
        let descriptor_sets = [descriptor_sets_vec[0], descriptor_sets_vec[1]];

        update_private_kuramoto_descriptors(
            device,
            descriptor_sets[0],
            draw_resources,
            &coordinate_triangle_buffer,
            &coordinate_barycentric_buffer,
            &surface_edge_buffer,
            &surface_meter_buffer,
            &surface_offset_buffer,
            &small_world_edge_buffer,
            &small_world_offset_buffer,
            &movement_phase_buffers[0],
            &movement_phase_buffers[1],
            &jerk_phase_buffers[0],
            &jerk_phase_buffers[1],
            &particle_output_buffer,
        );
        update_private_kuramoto_descriptors(
            device,
            descriptor_sets[1],
            draw_resources,
            &coordinate_triangle_buffer,
            &coordinate_barycentric_buffer,
            &surface_edge_buffer,
            &surface_meter_buffer,
            &surface_offset_buffer,
            &small_world_edge_buffer,
            &small_world_offset_buffer,
            &movement_phase_buffers[1],
            &movement_phase_buffers[0],
            &jerk_phase_buffers[1],
            &jerk_phase_buffers[0],
            &particle_output_buffer,
        );

        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE)
            .offset(0)
            .size(mem::size_of::<PrivateKuramotoComputePush>() as u32)];
        let compute_set_layouts = [descriptor_set_layout];
        let pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&compute_set_layouts)
                .push_constant_ranges(&push_ranges),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_private_kuramoto_buffers(
                    device,
                    &coordinate_triangle_buffer,
                    &coordinate_barycentric_buffer,
                    &surface_edge_buffer,
                    &surface_meter_buffer,
                    &surface_offset_buffer,
                    &small_world_edge_buffer,
                    &small_world_offset_buffer,
                    &movement_phase_buffers,
                    &jerk_phase_buffers,
                    &particle_output_buffer,
                );
                return Err(format!("create private Kuramoto pipeline layout: {error}"));
            }
        };
        let pipeline = match create_compute_pipeline(
            device,
            pipeline_layout,
            include_bytes!(concat!(
                env!("OUT_DIR"),
                "/private_kuramoto_particles.comp.spv"
            )),
            "private Kuramoto particles",
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                destroy_private_kuramoto_buffers(
                    device,
                    &coordinate_triangle_buffer,
                    &coordinate_barycentric_buffer,
                    &surface_edge_buffer,
                    &surface_meter_buffer,
                    &surface_offset_buffer,
                    &small_world_edge_buffer,
                    &small_world_offset_buffer,
                    &movement_phase_buffers,
                    &jerk_phase_buffers,
                    &particle_output_buffer,
                );
                return Err(error);
            }
        };

        crate::marker(
            "hand-anchor-particles",
            format!(
                "status=private-kuramoto-created handAnchorParticleHand={} privateKuramotoPayloadLinked=true privateKuramotoImplementationPath={} privateKuramotoDataPath={} kuramotoRuntimeMode=quest-native-vulkan-gpu kuramotoProfileId={} kuramotoSurfaceTarget={} kuramotoDynamicsMode=movement-only kuramotoParticleCount={} kuramotoNeighborhoodMode=surface-distance-tiered kuramotoMovementNoiseSpace=local-normalized kuramotoColorMode=rgb-driver kuramotoGraphEdgeCount={} kuramotoSmallWorldEdgeCount={} kuramotoGpuBuffersResident=true kuramotoCpuUploadBytes=0 kuramotoValidationMode=live-hand-solid-black-openxr-hands-awaiting-user-evaluation",
                handedness,
                crate::sanitize(PRIVATE_KURAMOTO_IMPLEMENTATION_PATH),
                crate::sanitize(PRIVATE_KURAMOTO_DATA_PATH),
                private_kuramoto_panel_dynamics().profile_id(),
                private_kuramoto_panel_dynamics().surface_target_id(),
                sample_count,
                graph.surface_edges.len(),
                graph.small_world_edges.len(),
            ),
        );

        Ok(Some(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_sets,
            pipeline_layout,
            pipeline,
            coordinate_triangle_buffer,
            coordinate_barycentric_buffer,
            surface_edge_buffer,
            surface_meter_buffer,
            surface_offset_buffer,
            small_world_edge_buffer,
            small_world_offset_buffer,
            movement_phase_buffers,
            jerk_phase_buffers,
            particle_output_buffer,
            sample_count,
            surface_edge_count: graph.surface_edges.len() as u32,
            small_world_edge_count: graph.small_world_edges.len() as u32,
            handedness,
        }))
    }

    fn particle_output_descriptor(&self) -> vk::DescriptorBufferInfo {
        self.particle_output_buffer.descriptor()
    }

    unsafe fn record_compute_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        particle_count: u32,
        radius_m: f32,
        frame_count: u64,
        hand_code: u32,
    ) {
        let particle_count = particle_count.min(self.sample_count).max(1);
        let descriptor_index = (frame_count as usize) & 1;
        let dynamics = private_kuramoto_panel_dynamics();
        let noise_seconds = frame_count as f32 * (1.0 / 72.0) * dynamics.noise_speed_hz;
        let static_to_compute = [
            storage_to_compute_read_barrier(&self.coordinate_triangle_buffer),
            storage_to_compute_read_barrier(&self.coordinate_barycentric_buffer),
            storage_to_compute_read_barrier(&self.surface_edge_buffer),
            storage_to_compute_read_barrier(&self.surface_meter_buffer),
            storage_to_compute_read_barrier(&self.surface_offset_buffer),
            storage_to_compute_read_barrier(&self.small_world_edge_buffer),
            storage_to_compute_read_barrier(&self.small_world_offset_buffer),
        ];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::HOST | vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &static_to_compute,
            &[],
        );

        let push = PrivateKuramotoComputePush {
            params0: [particle_count as f32, 1.0 / 72.0, radius_m, noise_seconds],
            params1: [1.0, hand_code as f32, frame_count as f32, 0.0],
            movement0: [
                dynamics.base_frequency_hz,
                dynamics.frequency_spread_hz,
                1.0,
                dynamics.movement_coupling,
            ],
            movement1: [0.0, 0.5, 0.3, 0.1],
            movement2: [
                0.0,
                dynamics.noise_frequency,
                dynamics.noise_amplitude_m,
                dynamics.unit_distance_m,
            ],
            jerk0: [0.0, 0.0, 0.0, 0.0],
            jerk1: [0.0, 0.0, 0.0, 0.0],
            jerk2: [0.0, 0.0, 0.0, 0.0],
        };
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::COMPUTE, self.pipeline);
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
        device.cmd_dispatch(cmd, particle_count.div_ceil(64).max(1), 1, 1);

        let compute_to_vertex = [
            compute_write_to_shader_read_barrier(&self.particle_output_buffer),
            compute_write_to_shader_read_barrier(
                &self.movement_phase_buffers[(descriptor_index + 1) & 1],
            ),
            compute_write_to_shader_read_barrier(
                &self.jerk_phase_buffers[(descriptor_index + 1) & 1],
            ),
        ];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::VERTEX_SHADER | vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &compute_to_vertex,
            &[],
        );

        if frame_count == 0 || frame_count % 120 == 0 {
            crate::android_log(format!(
                "RUSTY_KURAMOTO_MESH_NATIVE channel=frame status=running kuramotoRuntimeMode=quest-native-vulkan-gpu kuramotoProfileId={} kuramotoSurfaceTarget={} kuramotoDynamicsMode=movement-only kuramotoParticleCount={} kuramotoActiveHands={} kuramotoMovementBaseHz={:.3} kuramotoMovementSpreadHz={:.3} kuramotoMovementCoupling={:.3} kuramotoUnitDistanceM={:.4} kuramotoNeighborhoodMode=surface-distance-tiered kuramotoMovementNoiseSpace=local-normalized kuramotoColorMode=rgb-driver kuramotoGraphEdgeCount={} kuramotoSmallWorldEdgeCount={} kuramotoCoordinateAnchorGpuMs=pending-gpu-timestamp kuramotoPhaseIntegrateGpuMs=pending-gpu-timestamp kuramotoParticleBuildGpuMs=pending-gpu-timestamp kuramotoDrawGpuMs=pending-gpu-timestamp kuramotoReadbackCadenceFrames=0 kuramotoMovementOrder=pending-readback kuramotoJerkOrder=disabled kuramotoJerkBoostRms=0 kuramotoSaturationCount=pending-readback kuramotoCpuUploadBytes=0 kuramotoGpuBuffersResident=true kuramotoValidationMode=live-hand-solid-black-openxr-hands-awaiting-user-evaluation kuramotoVisualAcceptance=not-evaluated-user-away",
                dynamics.profile_id(),
                dynamics.surface_target_id(),
                particle_count,
                self.handedness,
                dynamics.base_frequency_hz,
                dynamics.frequency_spread_hz,
                dynamics.movement_coupling,
                dynamics.unit_distance_m,
                self.surface_edge_count,
                self.small_world_edge_count,
            ));
        }
    }

    unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        destroy_private_kuramoto_buffers(
            device,
            &self.coordinate_triangle_buffer,
            &self.coordinate_barycentric_buffer,
            &self.surface_edge_buffer,
            &self.surface_meter_buffer,
            &self.surface_offset_buffer,
            &self.small_world_edge_buffer,
            &self.small_world_offset_buffer,
            &self.movement_phase_buffers,
            &self.jerk_phase_buffers,
            &self.particle_output_buffer,
        );
    }
}

unsafe fn create_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
    blend_mode: NativeHandAnchorParticleTransparencyBlendMode,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/hand_anchor_particles.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/hand_anchor_particles.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create hand anchor particle vertex shader module: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create hand anchor particle fragment shader module: {error}"
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
        .map_err(|(_, error)| format!("create hand anchor particle graphics pipeline: {error}"))
}

fn particle_color_blend_attachment(
    mode: NativeHandAnchorParticleTransparencyBlendMode,
) -> vk::PipelineColorBlendAttachmentState {
    let (src_color, dst_color, src_alpha, dst_alpha) = match mode {
        NativeHandAnchorParticleTransparencyBlendMode::LegacyAdditiveMultiply => (
            vk::BlendFactor::SRC_ALPHA,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE,
        ),
        NativeHandAnchorParticleTransparencyBlendMode::TrueAdditive => (
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE,
        ),
        NativeHandAnchorParticleTransparencyBlendMode::Fade => (
            vk::BlendFactor::SRC_ALPHA,
            vk::BlendFactor::ONE_MINUS_SRC_ALPHA,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE_MINUS_SRC_ALPHA,
        ),
        NativeHandAnchorParticleTransparencyBlendMode::Premultiplied => (
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE_MINUS_SRC_ALPHA,
            vk::BlendFactor::ONE,
            vk::BlendFactor::ONE_MINUS_SRC_ALPHA,
        ),
    };
    vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(src_color)
        .dst_color_blend_factor(dst_color)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(src_alpha)
        .dst_alpha_blend_factor(dst_alpha)
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

unsafe fn update_descriptors(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    draw_resources: SkinnedHandMeshDrawResources,
    particle_output_buffer: vk::DescriptorBufferInfo,
    sort_remap_buffer: vk::DescriptorBufferInfo,
) {
    let skinned_position_info = [descriptor_info(
        draw_resources.skinned_position_buffer,
        draw_resources.skinned_position_buffer_bytes,
    )];
    let triangle_info = [descriptor_info(
        draw_resources.triangle_buffer,
        draw_resources.triangle_buffer_bytes,
    )];
    let particle_output_info = [particle_output_buffer];
    let sort_remap_info = [sort_remap_buffer];
    let writes = [
        write_descriptor(descriptor_set, 0, &skinned_position_info),
        write_descriptor(descriptor_set, 1, &triangle_info),
        write_descriptor(descriptor_set, 2, &particle_output_info),
        write_descriptor(descriptor_set, 3, &sort_remap_info),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

unsafe fn update_particle_sort_descriptors(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    particle_output_buffer: vk::DescriptorBufferInfo,
    sort_remap_buffer: vk::DescriptorBufferInfo,
) {
    let particle_output_info = [particle_output_buffer];
    let sort_remap_info = [sort_remap_buffer];
    let writes = [
        write_descriptor(descriptor_set, 0, &particle_output_info),
        write_descriptor(descriptor_set, 1, &sort_remap_info),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

fn descriptor_info(buffer: vk::Buffer, bytes: vk::DeviceSize) -> vk::DescriptorBufferInfo {
    vk::DescriptorBufferInfo::default()
        .buffer(buffer)
        .offset(0)
        .range(bytes)
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
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
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
        label: &str,
        data: &[T],
    ) -> Result<Self, String> {
        let bytes = mem::size_of_val(data) as vk::DeviceSize;
        let buffer = Self::new(device, memory_properties, bytes, usage, label)?;
        let mapped =
            match device.map_memory(buffer.memory, 0, buffer.bytes, vk::MemoryMapFlags::empty()) {
                Ok(mapped) => mapped.cast::<T>(),
                Err(error) => {
                    buffer.destroy(device);
                    return Err(format!("map {label} buffer: {error}"));
                }
            };
        mapped.copy_from_nonoverlapping(data.as_ptr(), data.len());
        device.unmap_memory(buffer.memory);
        Ok(buffer)
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

struct PrivateKuramotoPayload {
    coordinate_triangles: Vec<u32>,
    coordinate_barycentric: Vec<f32>,
    surface_edges: Vec<[u32; 2]>,
    surface_meters: Vec<f32>,
    small_world_edges: Vec<[u32; 2]>,
}

impl PrivateKuramotoPayload {
    fn load(handedness: &str) -> Result<Self, String> {
        let (
            coordinate_triangles,
            coordinate_barycentric,
            surface_edges,
            surface_meters,
            small_world_edges,
        ) = if handedness == "right" {
            (
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_right_coordinate_triangles.u32.bin"
                ))
                .as_slice(),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_right_coordinate_barycentric.f32.bin"
                ))
                .as_slice(),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_right_surface_distance_edges.u32.bin"
                ))
                .as_slice(),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_right_surface_distance_meters.f32.bin"
                ))
                .as_slice(),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_right_small_world_edges.u32.bin"
                ))
                .as_slice(),
            )
        } else {
            (
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_left_coordinate_triangles.u32.bin"
                ))
                .as_slice(),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_left_coordinate_barycentric.f32.bin"
                ))
                .as_slice(),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_left_surface_distance_edges.u32.bin"
                ))
                .as_slice(),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_left_surface_distance_meters.f32.bin"
                ))
                .as_slice(),
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/private_kuramoto_left_small_world_edges.u32.bin"
                ))
                .as_slice(),
            )
        };

        let coordinate_triangles = parse_u32_slice(coordinate_triangles)?;
        let coordinate_barycentric = parse_f32_slice(coordinate_barycentric)?;
        let surface_edges = parse_u32_pairs(surface_edges)?;
        let surface_meters = parse_f32_slice(surface_meters)?;
        let small_world_edges = parse_u32_pairs(small_world_edges)?;
        if coordinate_triangles.len() < PRIVATE_KURAMOTO_SAMPLE_COUNT {
            return Err(format!(
                "private Kuramoto coordinate triangle payload has {} rows, expected at least {}",
                coordinate_triangles.len(),
                PRIVATE_KURAMOTO_SAMPLE_COUNT
            ));
        }
        if coordinate_barycentric.len() < PRIVATE_KURAMOTO_SAMPLE_COUNT * 3 {
            return Err(format!(
                "private Kuramoto barycentric payload has {} values, expected at least {}",
                coordinate_barycentric.len(),
                PRIVATE_KURAMOTO_SAMPLE_COUNT * 3
            ));
        }
        if surface_edges.len() != surface_meters.len() {
            return Err(format!(
                "private Kuramoto surface graph shape mismatch: {} edges, {} meters",
                surface_edges.len(),
                surface_meters.len()
            ));
        }
        Ok(Self {
            coordinate_triangles,
            coordinate_barycentric,
            surface_edges,
            surface_meters,
            small_world_edges,
        })
    }
}

struct PrivateKuramotoGraphBuffers {
    surface_edges: Vec<[u32; 2]>,
    surface_meters: Vec<f32>,
    surface_offsets: Vec<[u32; 2]>,
    small_world_edges: Vec<[u32; 2]>,
    small_world_offsets: Vec<[u32; 2]>,
}

impl PrivateKuramotoGraphBuffers {
    fn from_payload(payload: &PrivateKuramotoPayload, sample_count: usize) -> Result<Self, String> {
        let mut surface_rows = vec![Vec::<(u32, f32)>::new(); sample_count];
        for (edge, distance) in payload
            .surface_edges
            .iter()
            .copied()
            .zip(payload.surface_meters.iter().copied())
        {
            let source = edge[0] as usize;
            let target = edge[1] as usize;
            if source >= sample_count || target >= sample_count || source == target {
                continue;
            }
            push_min_distance(&mut surface_rows[source], edge[1], distance);
            push_min_distance(&mut surface_rows[target], edge[0], distance);
        }

        let mut small_world_rows = vec![Vec::<(u32, f32)>::new(); sample_count];
        for edge in payload.small_world_edges.iter().copied() {
            let source = edge[0] as usize;
            let target = edge[1] as usize;
            if source >= sample_count || target >= sample_count || source == target {
                continue;
            }
            small_world_rows[source].push((edge[1], 0.0));
        }

        let (surface_edges, surface_meters, surface_offsets) = flatten_graph_rows(&surface_rows)?;
        let (small_world_edges, _, small_world_offsets) = flatten_graph_rows(&small_world_rows)?;
        Ok(Self {
            surface_edges,
            surface_meters,
            surface_offsets,
            small_world_edges,
            small_world_offsets,
        })
    }
}

fn push_min_distance(row: &mut Vec<(u32, f32)>, target: u32, distance: f32) {
    if let Some(existing) = row
        .iter_mut()
        .find(|(existing_target, _)| *existing_target == target)
    {
        existing.1 = existing.1.min(distance);
    } else {
        row.push((target, distance));
    }
}

type FlattenedGraphRows = (Vec<[u32; 2]>, Vec<f32>, Vec<[u32; 2]>);

fn flatten_graph_rows(rows: &[Vec<(u32, f32)>]) -> Result<FlattenedGraphRows, String> {
    let mut edges = Vec::new();
    let mut meters = Vec::new();
    let mut offsets = Vec::with_capacity(rows.len());
    for (source, row) in rows.iter().enumerate() {
        let start = u32::try_from(edges.len())
            .map_err(|_| "private Kuramoto graph edge count exceeds u32".to_string())?;
        let count = u32::try_from(row.len())
            .map_err(|_| "private Kuramoto graph row count exceeds u32".to_string())?;
        offsets.push([start, count]);
        for (target, distance) in row {
            edges.push([source as u32, *target]);
            meters.push(*distance);
        }
    }
    Ok((edges, meters, offsets))
}

fn parse_u32_slice(bytes: &[u8]) -> Result<Vec<u32>, String> {
    if bytes.len() % 4 != 0 {
        return Err("private Kuramoto u32 payload is not word-aligned".to_string());
    }
    Ok(bytes
        .chunks_exact(4)
        .map(|chunk| u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
        .collect())
}

fn parse_f32_slice(bytes: &[u8]) -> Result<Vec<f32>, String> {
    if bytes.len() % 4 != 0 {
        return Err("private Kuramoto f32 payload is not word-aligned".to_string());
    }
    Ok(bytes
        .chunks_exact(4)
        .map(|chunk| f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
        .collect())
}

fn parse_u32_pairs(bytes: &[u8]) -> Result<Vec<[u32; 2]>, String> {
    let words = parse_u32_slice(bytes)?;
    if words.len() % 2 != 0 {
        return Err("private Kuramoto u32 pair payload has an odd word count".to_string());
    }
    Ok(words
        .chunks_exact(2)
        .map(|chunk| [chunk[0], chunk[1]])
        .collect())
}

fn write_descriptor<'a>(
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

unsafe fn create_phase_buffer_pair(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    zero_phase_rows: &[[f32; 4]],
    label: &str,
) -> Result<[OwnedBuffer; 2], String> {
    let first = OwnedBuffer::new_with_data(
        device,
        memory_properties,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        label,
        zero_phase_rows,
    )?;
    let second = match OwnedBuffer::new_with_data(
        device,
        memory_properties,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        label,
        zero_phase_rows,
    ) {
        Ok(buffer) => buffer,
        Err(error) => {
            first.destroy(device);
            return Err(error);
        }
    };
    Ok([first, second])
}

unsafe fn destroy_buffer_pair(device: &ash::Device, buffers: &[OwnedBuffer; 2]) {
    buffers[0].destroy(device);
    buffers[1].destroy(device);
}

#[allow(clippy::too_many_arguments)]
unsafe fn destroy_private_kuramoto_buffers(
    device: &ash::Device,
    coordinate_triangle_buffer: &OwnedBuffer,
    coordinate_barycentric_buffer: &OwnedBuffer,
    surface_edge_buffer: &OwnedBuffer,
    surface_meter_buffer: &OwnedBuffer,
    surface_offset_buffer: &OwnedBuffer,
    small_world_edge_buffer: &OwnedBuffer,
    small_world_offset_buffer: &OwnedBuffer,
    movement_phase_buffers: &[OwnedBuffer; 2],
    jerk_phase_buffers: &[OwnedBuffer; 2],
    particle_output_buffer: &OwnedBuffer,
) {
    particle_output_buffer.destroy(device);
    destroy_buffer_pair(device, jerk_phase_buffers);
    destroy_buffer_pair(device, movement_phase_buffers);
    small_world_offset_buffer.destroy(device);
    small_world_edge_buffer.destroy(device);
    surface_offset_buffer.destroy(device);
    surface_meter_buffer.destroy(device);
    surface_edge_buffer.destroy(device);
    coordinate_barycentric_buffer.destroy(device);
    coordinate_triangle_buffer.destroy(device);
}

#[allow(clippy::too_many_arguments)]
unsafe fn update_private_kuramoto_descriptors(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    draw_resources: SkinnedHandMeshDrawResources,
    coordinate_triangle_buffer: &OwnedBuffer,
    coordinate_barycentric_buffer: &OwnedBuffer,
    surface_edge_buffer: &OwnedBuffer,
    surface_meter_buffer: &OwnedBuffer,
    surface_offset_buffer: &OwnedBuffer,
    small_world_edge_buffer: &OwnedBuffer,
    small_world_offset_buffer: &OwnedBuffer,
    movement_source_buffer: &OwnedBuffer,
    movement_target_buffer: &OwnedBuffer,
    jerk_source_buffer: &OwnedBuffer,
    jerk_target_buffer: &OwnedBuffer,
    particle_output_buffer: &OwnedBuffer,
) {
    let skinned_position_info = [descriptor_info(
        draw_resources.skinned_position_buffer,
        draw_resources.skinned_position_buffer_bytes,
    )];
    let triangle_info = [descriptor_info(
        draw_resources.triangle_buffer,
        draw_resources.triangle_buffer_bytes,
    )];
    let coordinate_triangle_info = [coordinate_triangle_buffer.descriptor()];
    let coordinate_barycentric_info = [coordinate_barycentric_buffer.descriptor()];
    let surface_edge_info = [surface_edge_buffer.descriptor()];
    let surface_meter_info = [surface_meter_buffer.descriptor()];
    let surface_offset_info = [surface_offset_buffer.descriptor()];
    let small_world_edge_info = [small_world_edge_buffer.descriptor()];
    let small_world_offset_info = [small_world_offset_buffer.descriptor()];
    let movement_source_info = [movement_source_buffer.descriptor()];
    let movement_target_info = [movement_target_buffer.descriptor()];
    let jerk_source_info = [jerk_source_buffer.descriptor()];
    let jerk_target_info = [jerk_target_buffer.descriptor()];
    let particle_output_info = [particle_output_buffer.descriptor()];
    let writes = [
        write_descriptor(descriptor_set, 0, &skinned_position_info),
        write_descriptor(descriptor_set, 1, &triangle_info),
        write_descriptor(descriptor_set, 2, &coordinate_triangle_info),
        write_descriptor(descriptor_set, 3, &coordinate_barycentric_info),
        write_descriptor(descriptor_set, 4, &surface_edge_info),
        write_descriptor(descriptor_set, 5, &surface_meter_info),
        write_descriptor(descriptor_set, 6, &surface_offset_info),
        write_descriptor(descriptor_set, 7, &small_world_edge_info),
        write_descriptor(descriptor_set, 8, &small_world_offset_info),
        write_descriptor(descriptor_set, 9, &movement_source_info),
        write_descriptor(descriptor_set, 10, &movement_target_info),
        write_descriptor(descriptor_set, 11, &jerk_source_info),
        write_descriptor(descriptor_set, 12, &jerk_target_info),
        write_descriptor(descriptor_set, 13, &particle_output_info),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

fn storage_to_compute_read_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::HOST_WRITE | vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
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

unsafe fn create_compute_pipeline(
    device: &ash::Device,
    pipeline_layout: vk::PipelineLayout,
    spirv: &[u8],
    label: &str,
) -> Result<vk::Pipeline, String> {
    let compute_words = spirv_words(spirv)?;
    let compute_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&compute_words),
            None,
        )
        .map_err(|error| format!("create {label} shader module: {error}"))?;
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
        .map_err(|(_, error)| format!("create {label} compute pipeline: {error}"))
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
        "no Vulkan memory type supports {required:?} for hand anchor particle buffers"
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
    let inv_length = 1.0 / length_sq.sqrt();
    [
        quat[0] * inv_length,
        quat[1] * inv_length,
        quat[2] * inv_length,
        quat[3] * inv_length,
    ]
}

fn cross3(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

fn dot4(left: [f32; 4], right: [f32; 4]) -> f32 {
    left[0] * right[0] + left[1] * right[1] + left[2] * right[2] + left[3] * right[3]
}

fn as_bytes<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) }
}

#[repr(C)]
struct HandAnchorParticlePush {
    params0: [f32; 4],
    params1: [f32; 4],
    params2: [f32; 4],
    eye_position: [f32; 4],
    eye_orientation_xyzw: [f32; 4],
    fov_tangents: [f32; 4],
}

#[repr(C)]
struct ParticleSortComputePush {
    params0: [f32; 4],
    params1: [f32; 4],
    params2: [f32; 4],
}

#[repr(C)]
struct PrivateKuramotoComputePush {
    params0: [f32; 4],
    params1: [f32; 4],
    movement0: [f32; 4],
    movement1: [f32; 4],
    movement2: [f32; 4],
    jerk0: [f32; 4],
    jerk1: [f32; 4],
    jerk2: [f32; 4],
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::native_renderer_options::NativeHandAnchorParticleDynamics;

    #[test]
    fn live_hand_anchor_particles_do_not_require_base_mesh_visibility() {
        let hand_mesh = GpuHandMeshVisualFrameStats {
            ready: true,
            visible: false,
            handedness: "left",
            triangle_count: 2048,
            skinned_position_buffer_bytes: 65_536,
            live_compact_input_frame: true,
            ..Default::default()
        };
        let settings = NativeHandAnchorParticleSettings {
            enabled: true,
            particles_per_hand: 1024,
            dynamics: NativeHandAnchorParticleDynamics::DeterministicAnchors,
            ..Default::default()
        };

        let stats = GpuHandAnchorParticleFrameStats::from_hand_mesh(&hand_mesh, settings);

        assert!(stats.ready);
        assert!(stats.visible);
        assert_eq!(stats.particles_drawn, 1024);
        assert_eq!(stats.triangle_count, 2048);
        assert!(stats.live_compact_input_frame);
        assert_eq!(stats.input_source, "live-meta-openxr-hand-tracking");
    }

    #[test]
    fn real_hand_anchor_particles_use_recorded_fallback_skinning_before_live_joints() {
        let hand_mesh = GpuHandMeshVisualFrameStats {
            ready: true,
            visible: true,
            handedness: "left",
            triangle_count: 2048,
            skinned_position_buffer_bytes: 65_536,
            live_compact_input_frame: false,
            ..Default::default()
        };
        let settings = NativeHandAnchorParticleSettings {
            enabled: true,
            particles_per_hand: 1024,
            dynamics: NativeHandAnchorParticleDynamics::DeterministicAnchors,
            ..Default::default()
        };

        let stats = GpuHandAnchorParticleFrameStats::from_hand_mesh(&hand_mesh, settings);

        assert!(stats.ready);
        assert!(stats.visible);
        assert_eq!(stats.particles_drawn, 1024);
        assert_eq!(stats.triangle_count, 2048);
        assert!(!stats.live_compact_input_frame);
        assert_eq!(stats.input_source, "recorded-replay-fallback");
        assert_eq!(stats.readiness_reason, "ready-recorded-replay-fallback");
    }

    #[test]
    fn real_hand_anchor_particles_use_both_fallback_skinned_hands_in_parallel() {
        let primary = GpuHandMeshVisualFrameStats {
            ready: true,
            visible: true,
            handedness: "left",
            triangle_count: 2048,
            skinned_position_buffer_bytes: 65_536,
            live_compact_input_frame: false,
            ..Default::default()
        };
        let secondary = GpuHandMeshVisualFrameStats {
            ready: true,
            visible: true,
            handedness: "right",
            triangle_count: 2048,
            skinned_position_buffer_bytes: 65_536,
            live_compact_input_frame: false,
            ..Default::default()
        };
        let mesh_stats = GpuHandMeshVisualFrameSetStats::new(primary, secondary, false, 1.0);
        let settings = NativeHandAnchorParticleSettings {
            enabled: true,
            particles_per_hand: 1024,
            dynamics: NativeHandAnchorParticleDynamics::DeterministicAnchors,
            ..Default::default()
        };

        let stats = GpuHandAnchorParticleFrameSetStats::new(&mesh_stats, settings);

        assert!(stats.primary.ready);
        assert!(stats.secondary.ready);
        assert!(stats.primary.visible);
        assert!(stats.secondary.visible);
        assert_eq!(stats.primary.input_source, "recorded-replay-fallback");
        assert_eq!(stats.secondary.input_source, "recorded-replay-fallback");
        assert_eq!(stats.total_particles_drawn(), 2048);
        assert!(stats
            .marker_fields()
            .contains("handAnchorParticleBothHandsVisible=true"));
    }
}
