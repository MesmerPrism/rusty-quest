//! Native Vulkan hand-anchor particle billboards over resident GPU-skinned hand meshes.

use std::{ffi::CString, mem};

use ash::vk;

use crate::{
    gpu_hand_mesh_visual::{
        GpuHandMeshVisualFrameSetStats, GpuHandMeshVisualFrameStats, HandMeshVisualEyeProjection,
    },
    gpu_sdf_field::SkinnedHandMeshDrawResources,
    native_renderer_options::NativeHandAnchorParticleSettings,
};

const PARTICLE_VERTICES_PER_INSTANCE: u32 = 6;

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct GpuHandAnchorParticleFrameStats {
    pub(crate) ready: bool,
    pub(crate) visible: bool,
    pub(crate) handedness: &'static str,
    pub(crate) particles_drawn: u32,
    pub(crate) triangle_count: u32,
    pub(crate) skinned_position_buffer_bytes: u64,
    pub(crate) live_compact_input_frame: bool,
}

impl GpuHandAnchorParticleFrameStats {
    fn from_hand_mesh(
        hand_mesh: &GpuHandMeshVisualFrameStats,
        settings: NativeHandAnchorParticleSettings,
    ) -> Self {
        let ready = settings.enabled
            && hand_mesh.ready
            && hand_mesh.visible
            && hand_mesh.live_compact_input_frame
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
        }
    }

    fn marker_fields(&self, prefix: &str) -> String {
        format!(
            "{prefix}Ready={} {prefix}Visible={} {prefix}Hand={} {prefix}ParticleCount={} {prefix}TriangleCount={} {prefix}SkinnedPositionBufferBytes={} {prefix}LiveCompactInputFrame={}",
            self.ready,
            self.visible,
            self.handedness,
            self.particles_drawn,
            self.triangle_count,
            self.skinned_position_buffer_bytes,
            self.live_compact_input_frame
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
            "{} handAnchorParticleTotalCount={} handAnchorParticleBothHandsVisible={} {} {}",
            self.settings.marker_fields(),
            self.total_particles_drawn(),
            self.primary.visible && self.secondary.visible,
            self.primary.marker_fields("handAnchorParticlePrimary"),
            self.secondary.marker_fields("handAnchorParticleSecondary"),
        )
    }
}

pub(crate) struct GpuHandAnchorParticleRenderer {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    draw_resources: SkinnedHandMeshDrawResources,
    hand_code: u32,
}

impl GpuHandAnchorParticleRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        render_pass: vk::RenderPass,
        draw_resources: SkinnedHandMeshDrawResources,
        handedness: &'static str,
    ) -> Result<Self, String> {
        if draw_resources.vertex_count == 0 || draw_resources.triangle_count == 0 {
            return Err("resident skinned hand mesh particle draw resources are empty".to_string());
        }

        let bindings = [
            storage_binding(0, vk::ShaderStageFlags::VERTEX),
            storage_binding(1, vk::ShaderStageFlags::VERTEX),
        ];
        let descriptor_set_layout = device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|error| format!("create hand anchor particle descriptor layout: {error}"))?;

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
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(format!(
                    "allocate hand anchor particle descriptor set: {error}"
                ));
            }
        };
        update_descriptors(device, descriptor_set, draw_resources);

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
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(format!(
                    "create hand anchor particle pipeline layout: {error}"
                ));
            }
        };

        let pipeline = match create_pipeline(device, render_pass, pipeline_layout) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };

        crate::marker(
            "hand-anchor-particles",
            format!(
                "status=created handAnchorParticleHand={} handAnchorParticlePath=resident-skinned-mesh-coordinate-anchor-billboards handAnchorParticleCoordinateSource=deterministic-gpu-barycentric-triangle-anchors handAnchorParticleCoordinateSpace=openxr-reference-space handAnchorParticleMask=static-feather-dot-luminance-alpha handAnchorParticleAnimation=false handAnchorParticleTriangleCount={} handAnchorParticleVertexCount={} handAnchorParticleSkinnedPositionBufferBytes={} handAnchorParticleTriangleBufferBytes={} handAnchorParticleCpuExpandedUploadPerFrame=false handAnchorParticleMeshUploadPerFrame=false",
                handedness,
                draw_resources.triangle_count,
                draw_resources.vertex_count,
                draw_resources.skinned_position_buffer_bytes,
                draw_resources.triangle_buffer_bytes,
            ),
        );

        Ok(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_set,
            pipeline_layout,
            pipeline,
            draw_resources,
            hand_code: if handedness == "right" { 2 } else { 1 },
        })
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
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
}

unsafe fn create_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
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
    let color_blend_attachment = [vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::ONE)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
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
        .map_err(|(_, error)| format!("create hand anchor particle graphics pipeline: {error}"))
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
) {
    let skinned_position_info = [descriptor_info(
        draw_resources.skinned_position_buffer,
        draw_resources.skinned_position_buffer_bytes,
    )];
    let triangle_info = [descriptor_info(
        draw_resources.triangle_buffer,
        draw_resources.triangle_buffer_bytes,
    )];
    let writes = [
        write_descriptor(descriptor_set, 0, &skinned_position_info),
        write_descriptor(descriptor_set, 1, &triangle_info),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

fn descriptor_info(buffer: vk::Buffer, bytes: vk::DeviceSize) -> vk::DescriptorBufferInfo {
    vk::DescriptorBufferInfo::default()
        .buffer(buffer)
        .offset(0)
        .range(bytes)
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

#[repr(C)]
struct HandAnchorParticlePush {
    params0: [f32; 4],
    eye_position: [f32; 4],
    eye_orientation_xyzw: [f32; 4],
    fov_tangents: [f32; 4],
}
