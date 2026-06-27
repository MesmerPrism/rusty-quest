//! Native Vulkan hand-anchor particle billboards over resident GPU-skinned hand meshes.

use std::{ffi::CString, mem};

use ash::vk;

use crate::{
    gpu_hand_mesh_visual::{
        GpuHandMeshVisualFrameSetStats, GpuHandMeshVisualFrameStats, HandMeshVisualEyeProjection,
    },
    gpu_private_particles::{OwnedMaskTexture, PrivateParticleMaskTexturePayload},
    gpu_sdf_field::SkinnedHandMeshDrawResources,
    native_renderer_options::{
        NativeHandAnchorParticleSettings, NativeHandAnchorParticleTransparencyBlendMode,
    },
    native_renderer_property_values::f32_clamped_value,
    recorded_hand_replay::RecordedMeshTargetTransform,
};

#[cfg(target_os = "android")]
use crate::native_renderer_properties::PROP_PRIVATE_PARTICLES_COLOR_FACING_ATTENUATION_STRENGTH;

include!(concat!(
    env!("OUT_DIR"),
    "/private_particle_payload_config.rs"
));

const PARTICLE_VERTICES_PER_INSTANCE: u32 = 6;
const PARTICLE_SORT_LOCAL_SIZE: u32 = 128;
const PARTICLE_OUTPUT_ROW_VEC4S: vk::DeviceSize = 4;
const PARTICLE_OUTPUT_ROW_BYTES: vk::DeviceSize =
    PARTICLE_OUTPUT_ROW_VEC4S * mem::size_of::<[f32; 4]>() as vk::DeviceSize;
const PARTICLE_SORT_ROW_BYTES: vk::DeviceSize = mem::size_of::<[u32; 4]>() as vk::DeviceSize;

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
        let input_source = if hand_mesh.live_compact_input_frame {
            "live-meta-openxr-hand-tracking"
        } else {
            "recorded-replay-fallback"
        };
        let readiness_reason = if !settings.enabled {
            "disabled"
        } else if !hand_mesh.ready {
            "awaiting-skinned-hand-mesh"
        } else if hand_mesh.triangle_count == 0 {
            "empty-hand-mesh"
        } else if hand_mesh.live_compact_input_frame {
            "ready-live-hand-frame"
        } else {
            "ready-recorded-replay-fallback"
        };
        let ready = settings.enabled && hand_mesh.ready && hand_mesh.triangle_count > 0;
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
            "{prefix}Ready={} {prefix}Visible={} {prefix}Hand={} {prefix}ParticleCount={} {prefix}TriangleCount={} {prefix}SkinnedPositionBufferBytes={} {prefix}LiveCompactInputFrame={} {prefix}InputSource={} {prefix}ReadinessReason={} {prefix}CoordinatePlacement={}",
            self.ready,
            self.visible,
            self.handedness,
            self.particles_drawn,
            self.triangle_count,
            self.skinned_position_buffer_bytes,
            self.live_compact_input_frame,
            self.input_source,
            self.readiness_reason,
            if self.live_compact_input_frame {
                "openxr-reference-space-live"
            } else {
                "current-eye-front-recorded-fallback"
            }
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
        format!(
            "{} handAnchorParticleSurfaceTarget=resident-hand-mesh handAnchorParticleProfileId=public-deterministic handAnchorParticleTotalCount={} handAnchorParticleBothHandsVisible={} handAnchorParticleReadinessReason={} {} {}",
            self.settings.marker_fields(),
            self.total_particles_drawn(),
            self.primary.visible && self.secondary.visible,
            self.primary.readiness_reason,
            self.primary.marker_fields("handAnchorParticlePrimary"),
            self.secondary.marker_fields("handAnchorParticleSecondary"),
        )
    }
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

fn hand_anchor_particle_facing_attenuation_strength() -> f32 {
    #[cfg(target_os = "android")]
    {
        f32_clamped_value(
            android_property(PROP_PRIVATE_PARTICLES_COLOR_FACING_ATTENUATION_STRENGTH),
            PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION_STRENGTH,
            0.0,
            1.0,
        )
    }
    #[cfg(not(target_os = "android"))]
    {
        PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION_STRENGTH.clamp(0.0, 1.0)
    }
}

pub(crate) struct GpuHandAnchorParticleRenderer {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    mask_texture: OwnedMaskTexture,
    sort_resources: ParticleSortResources,
    draw_resources: SkinnedHandMeshDrawResources,
    hand_code: u32,
    particle_output_buffer: OwnedBuffer,
}

impl GpuHandAnchorParticleRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        queue: vk::Queue,
        command_pool: vk::CommandPool,
        draw_resources: SkinnedHandMeshDrawResources,
        handedness: &'static str,
        settings: NativeHandAnchorParticleSettings,
    ) -> Result<Self, String> {
        if draw_resources.vertex_count == 0 || draw_resources.triangle_count == 0 {
            return Err("resident skinned hand mesh particle draw resources are empty".to_string());
        }
        let particle_output_buffer = OwnedBuffer::new(
            device,
            memory_properties,
            PARTICLE_OUTPUT_ROW_BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "fallback hand anchor particle output",
        )?;
        let sort_capacity = settings.particles_per_hand.max(1).next_power_of_two();
        let sort_resources = match ParticleSortResources::new(
            device,
            memory_properties,
            particle_output_buffer.descriptor(),
            sort_capacity,
        ) {
            Ok(resources) => resources,
            Err(error) => {
                particle_output_buffer.destroy(device);
                return Err(error);
            }
        };

        let mask_payload = match PrivateParticleMaskTexturePayload::load() {
            Ok(payload) => payload,
            Err(error) => {
                sort_resources.destroy(device);
                particle_output_buffer.destroy(device);
                return Err(format!(
                    "load hand anchor particle mask texture payload: {error}"
                ));
            }
        };
        let mask_texture = match OwnedMaskTexture::new_with_data(
            device,
            memory_properties,
            queue,
            command_pool,
            &mask_payload,
        ) {
            Ok(texture) => texture,
            Err(error) => {
                sort_resources.destroy(device);
                particle_output_buffer.destroy(device);
                return Err(format!("create hand anchor particle mask texture: {error}"));
            }
        };

        let bindings = [
            storage_binding(0, vk::ShaderStageFlags::VERTEX),
            storage_binding(1, vk::ShaderStageFlags::VERTEX),
            storage_binding(2, vk::ShaderStageFlags::VERTEX),
            storage_binding(3, vk::ShaderStageFlags::VERTEX),
            sampled_image_binding(4, vk::ShaderStageFlags::FRAGMENT),
        ];
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                mask_texture.destroy(device);
                sort_resources.destroy(device);
                particle_output_buffer.destroy(device);
                return Err(format!(
                    "create hand anchor particle descriptor layout: {error}"
                ));
            }
        };

        let pool_sizes = [
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(4),
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
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
                mask_texture.destroy(device);
                sort_resources.destroy(device);
                particle_output_buffer.destroy(device);
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
                mask_texture.destroy(device);
                sort_resources.destroy(device);
                particle_output_buffer.destroy(device);
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
            particle_output_buffer.descriptor(),
            sort_resources.remap_descriptor(),
            mask_texture.descriptor(),
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
                mask_texture.destroy(device);
                sort_resources.destroy(device);
                particle_output_buffer.destroy(device);
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
                mask_texture.destroy(device);
                sort_resources.destroy(device);
                particle_output_buffer.destroy(device);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };

        crate::marker(
            "hand-anchor-particles",
            format!(
                "status=created handAnchorParticleHand={} handAnchorParticlePath=resident-skinned-mesh-coordinate-anchor-billboards handAnchorParticleCoordinateSource={} handAnchorParticleFallbackActive={} handAnchorParticleCoordinateSpace=openxr-reference-space handAnchorParticleFallbackPlacement=current-eye-front-recorded-mesh-bounds handAnchorParticleMask=static-feather-dot-r8-texture handAnchorParticleMaskTextureSharedWithPrivateParticles=true handAnchorParticleMaskTextureSize={}x{}x{} handAnchorParticleMaskTextureMipMode={} handAnchorParticleMaskTextureMipLevels={} handAnchorParticleAnimation=false handAnchorParticleTriangleCount={} handAnchorParticleVertexCount={} handAnchorParticleSkinnedPositionBufferBytes={} handAnchorParticleTriangleBufferBytes={} handAnchorParticleCpuExpandedUploadPerFrame=false handAnchorParticleMeshUploadPerFrame=false {}",
                handedness,
                "deterministic-gpu-barycentric-triangle-anchors",
                true,
                PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH,
                PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT,
                PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS,
                crate::sanitize(PRIVATE_PARTICLE_MASK_TEXTURE_MIP_MODE),
                PRIVATE_PARTICLE_MASK_TEXTURE_MIP_LEVELS,
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
            mask_texture,
            sort_resources,
            draw_resources,
            hand_code: if handedness == "right" { 2 } else { 1 },
            particle_output_buffer,
        })
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        self.particle_output_buffer.destroy(device);
        self.mask_texture.destroy(device);
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
        let _ = (device, cmd, settings, frame_count);
        if !stats.ready || stats.particles_drawn == 0 {
            return;
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
            if frame_count == 0 && settings.resident_gpu_particle_sort_requested() {
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
                    "status=gpu-sort-active handAnchorParticleHand={} handAnchorParticleGpuSortActive=true handAnchorParticleSortPath=resident-gpu-index-remap handAnchorParticleSortBasis=per-eye-openxr-reference-space handAnchorParticleSortCount={} handAnchorParticleSortCapacity={} handAnchorParticleOrderingMode={} handAnchorParticleOrderingImplementation={} handAnchorParticleOrderingCpuExpandedUploadPerFrame=false",
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
                0.0,
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
                if stats.live_compact_input_frame {
                    0.0
                } else {
                    1.0
                },
                if self.hand_code == 2 { 1.0 } else { -1.0 },
                hand_anchor_particle_facing_attenuation_strength(),
            ],
            eye_position: eye_projection.position,
            eye_orientation_xyzw: eye_projection.orientation_xyzw,
            fov_tangents: eye_projection.fov_tangents,
            target0: recorded_mesh_target0(self.draw_resources.target_transform),
            target1: recorded_mesh_target1(self.draw_resources.target_transform),
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
        let _ = (self, stats, settings);
        false
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
    draw_resources: SkinnedHandMeshDrawResources,
    particle_output_buffer: vk::DescriptorBufferInfo,
    sort_remap_buffer: vk::DescriptorBufferInfo,
    mask_texture: vk::DescriptorImageInfo,
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
    let mask_texture_info = [mask_texture];
    let writes = [
        write_descriptor(descriptor_set, 0, &skinned_position_info),
        write_descriptor(descriptor_set, 1, &triangle_info),
        write_descriptor(descriptor_set, 2, &particle_output_info),
        write_descriptor(descriptor_set, 3, &sort_remap_info),
        write_sampled_image_descriptor(descriptor_set, 4, &mask_texture_info),
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

fn recorded_mesh_target0(transform: RecordedMeshTargetTransform) -> [f32; 4] {
    [
        transform.center[0],
        transform.center[1],
        transform.min_z,
        transform.radius,
    ]
}

fn recorded_mesh_target1(transform: RecordedMeshTargetTransform) -> [f32; 4] {
    [transform.center[2], transform.depth, 0.0, 0.0]
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

fn compute_write_to_shader_read_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn compute_write_to_compute_read_write_barrier(
    buffer: &OwnedBuffer,
) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
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
    target0: [f32; 4],
    target1: [f32; 4],
}

#[repr(C)]
struct ParticleSortComputePush {
    params0: [f32; 4],
    params1: [f32; 4],
    params2: [f32; 4],
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
        assert!(stats.marker_fields().contains(
            "handAnchorParticlePrimaryCoordinatePlacement=current-eye-front-recorded-fallback"
        ));
        assert!(stats.marker_fields().contains(
            "handAnchorParticleSecondaryCoordinatePlacement=current-eye-front-recorded-fallback"
        ));
    }

    #[test]
    fn deterministic_gpu_replay_surface_uses_recorded_hand_mesh() {
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
        assert_eq!(stats.input_source, "recorded-replay-fallback");
        assert_eq!(stats.readiness_reason, "ready-recorded-replay-fallback");
    }
}
