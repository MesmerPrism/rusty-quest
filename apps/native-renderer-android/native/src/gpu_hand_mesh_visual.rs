//! Native Vulkan visual overlay for the resident GPU-skinned recorded hand mesh.

use std::{ffi::CString, mem};

use ash::vk;

use crate::{
    camera_projection_metadata::TargetRect,
    gpu_sdf_field::SkinnedHandMeshDrawResources,
    native_renderer_options::HandMeshVisualDiagnosticSettings,
    recorded_hand_replay::{RecordedHandReplaySummary, RecordedHandSkinningFrame},
};

#[derive(Clone, Debug, Default)]
pub(crate) struct GpuHandMeshVisualFrameStats {
    pub(crate) ready: bool,
    pub(crate) visible: bool,
    pub(crate) handedness: &'static str,
    pub(crate) frame_index: u32,
    pub(crate) timestamp_ns: u64,
    pub(crate) drawn_vertex_count: u32,
    pub(crate) triangle_count: u32,
    pub(crate) component_count: u64,
    pub(crate) component_vertex_counts: Vec<u64>,
    pub(crate) component_triangle_counts: Vec<u64>,
    pub(crate) skinned_position_buffer_bytes: u64,
    pub(crate) triangle_index_buffer_bytes: u64,
    pub(crate) live_compact_input_frame: bool,
    pub(crate) diagnostic_settings: HandMeshVisualDiagnosticSettings,
}

impl GpuHandMeshVisualFrameStats {
    pub(crate) fn unavailable(
        replay: &RecordedHandReplaySummary,
        frame_count: u64,
        handedness: &'static str,
        diagnostic_settings: HandMeshVisualDiagnosticSettings,
    ) -> Self {
        let frame = replay.skinning_frame_for_count(frame_count);
        Self {
            handedness,
            frame_index: frame.map(|frame| frame.frame_index).unwrap_or(0),
            timestamp_ns: frame.map(|frame| frame.timestamp_ns).unwrap_or(0),
            component_count: replay.mesh_component_summary.component_count,
            component_vertex_counts: replay.mesh_component_summary.vertex_counts.clone(),
            component_triangle_counts: replay.mesh_component_summary.triangle_counts.clone(),
            diagnostic_settings,
            ..Default::default()
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "animatedHandMeshVisualReady={} animatedHandMeshVisualVisible={} handMeshVisualPath=recorded-compact-joint-gpu-skinned-resident-triangle-draw recordedSkinnedMeshFrameSource=compact_joint_gpu_skinning gpuTriangleDraw=true cpuProjection=false validationMeshUploadPerFrame=false skinnedPositionBufferResident=true gpuNormalDepthComponentShading=true handMeshVisualHand={} handMeshFrame={} handMeshTimestampNs={} handMeshDrawnTriangles={} handMeshDrawnVertices={} handMeshSkinnedPositionBufferBytes={} handMeshTriangleIndexBufferBytes={} handMeshComponentCount={} handMeshComponentVertexCounts={} handMeshComponentTriangleCounts={} handMeshComponentRank0=hand-inside-blue-green handMeshComponentRank1=hand-back-yellow handMeshComponentRank2=wrist-cap-red {}",
            self.ready,
            self.visible,
            self.handedness,
            self.frame_index,
            self.timestamp_ns,
            self.triangle_count,
            self.drawn_vertex_count,
            self.skinned_position_buffer_bytes,
            self.triangle_index_buffer_bytes,
            self.component_count,
            join_u64(&self.component_vertex_counts),
            join_u64(&self.component_triangle_counts),
            self.diagnostic_settings.marker_fields(),
        )
        + &format!(
            " handMeshCompactInputSource={} liveHandMeshTargetLocalNormalized={} handMeshVisualDiagnosticMaterial={} liveHandMeshVisualAcceptance={}",
            if self.live_compact_input_frame {
                "live-meta-openxr-hand-tracking"
            } else {
                "recorded-replay"
            },
            self.live_compact_input_frame,
            if self.live_compact_input_frame && self.diagnostic_settings.enabled {
                "live-target-local-high-contrast-fill"
            } else if self.diagnostic_settings.enabled {
                "recorded-target-local-high-contrast-fill"
            } else {
                "component-normal-depth-fill"
            },
            if self.live_compact_input_frame {
                "pending-repeat-headset-visual-proof"
            } else {
                "not-live-input"
            }
        )
    }
}

#[derive(Clone, Debug)]
pub(crate) struct GpuHandMeshVisualFrameSetStats {
    pub(crate) primary: GpuHandMeshVisualFrameStats,
    pub(crate) secondary: GpuHandMeshVisualFrameStats,
}

impl GpuHandMeshVisualFrameSetStats {
    pub(crate) fn new(
        primary: GpuHandMeshVisualFrameStats,
        secondary: GpuHandMeshVisualFrameStats,
    ) -> Self {
        Self { primary, secondary }
    }

    pub(crate) fn any_ready(&self) -> bool {
        self.primary.ready || self.secondary.ready
    }

    pub(crate) fn diagnostic_settings(&self) -> HandMeshVisualDiagnosticSettings {
        self.primary.diagnostic_settings
    }

    pub(crate) fn marker_fields(&self) -> String {
        let left_visible = self.hand_visible("left");
        let right_visible = self.hand_visible("right");
        format!(
            "{} liveHandMeshVisualLeftVisible={} liveHandMeshVisualRightVisible={} liveHandMeshVisualBothHandsVisible={} handMeshVisualGpuSkinnedHandCount={} handMeshVisualPrimaryHand={} handMeshVisualSecondaryHand={} handMeshVisualSecondaryReady={} handMeshVisualSecondaryVisible={} handMeshSecondaryFrame={} handMeshSecondaryTimestampNs={}",
            self.primary.marker_fields(),
            left_visible,
            right_visible,
            left_visible && right_visible,
            self.visible_hand_count(),
            self.primary.handedness,
            self.secondary.handedness,
            self.secondary.ready,
            self.secondary.visible,
            self.secondary.frame_index,
            self.secondary.timestamp_ns,
        )
    }

    fn hand_visible(&self, handedness: &'static str) -> bool {
        (self.primary.handedness == handedness && self.primary.visible)
            || (self.secondary.handedness == handedness && self.secondary.visible)
    }

    fn visible_hand_count(&self) -> u32 {
        self.primary.visible as u32 + self.secondary.visible as u32
    }
}

pub(crate) struct GpuHandMeshVisualRenderer {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    draw_resources: SkinnedHandMeshDrawResources,
}

impl GpuHandMeshVisualRenderer {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        render_pass: vk::RenderPass,
        replay: &RecordedHandReplaySummary,
        draw_resources: SkinnedHandMeshDrawResources,
    ) -> Result<Self, String> {
        if draw_resources.vertex_count == 0 || draw_resources.triangle_count == 0 {
            return Err("resident skinned hand mesh draw resources are empty".to_string());
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
            .map_err(|error| format!("create hand mesh visual descriptor layout: {error}"))?;

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
                return Err(format!("create hand mesh visual descriptor pool: {error}"));
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
                return Err(format!("allocate hand mesh visual descriptor set: {error}"));
            }
        };
        update_descriptors(device, descriptor_set, draw_resources);

        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(mem::size_of::<HandMeshVisualPush>() as u32)];
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
                return Err(format!("create hand mesh visual pipeline layout: {error}"));
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
            "hand-mesh-visual",
            format!(
                "status=created handMeshVisualPath=recorded-compact-joint-gpu-skinned-resident-triangle-draw recordedSkinnedMeshFrameSource=compact_joint_gpu_skinning frameCount={} vertexCount={} triangleCount={} skinnedPositionBufferBytes={} triangleIndexBufferBytes={} componentCount={} componentVertexCounts={} componentTriangleCounts={} gpuTriangleDraw=true cpuProjection=false validationMeshUploadPerFrame=false skinnedPositionBufferResident=true gpuNormalDepthComponentShading=true",
                replay.skinning_frames.len(),
                draw_resources.vertex_count,
                draw_resources.triangle_count,
                draw_resources.skinned_position_buffer_bytes,
                draw_resources.triangle_buffer_bytes,
                replay.mesh_component_summary.component_count,
                join_u64(&replay.mesh_component_summary.vertex_counts),
                join_u64(&replay.mesh_component_summary.triangle_counts),
            ),
        );

        Ok(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_set,
            pipeline_layout,
            pipeline,
            draw_resources,
        })
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
    }

    pub(crate) fn record_frame(
        &self,
        replay: &RecordedHandReplaySummary,
        frame_count: u64,
        skinning_ready: bool,
        live_hand_frame: Option<&RecordedHandSkinningFrame>,
        allow_recorded_replay_fallback: bool,
        handedness: &'static str,
        diagnostic_settings: HandMeshVisualDiagnosticSettings,
    ) -> Result<GpuHandMeshVisualFrameStats, String> {
        let frame = live_hand_frame
            .or_else(|| {
                allow_recorded_replay_fallback
                    .then(|| replay.skinning_frame_for_count(frame_count))
                    .flatten()
            })
            .ok_or_else(|| "hand mesh visual has no compact skinning frame".to_string())?;
        if !skinning_ready {
            return Ok(GpuHandMeshVisualFrameStats::unavailable(
                replay,
                frame_count,
                handedness,
                diagnostic_settings,
            ));
        }
        let drawn_vertex_count = self.draw_resources.triangle_count.saturating_mul(3);
        Ok(GpuHandMeshVisualFrameStats {
            ready: true,
            visible: true,
            handedness,
            frame_index: frame.frame_index,
            timestamp_ns: frame.timestamp_ns,
            drawn_vertex_count,
            triangle_count: self.draw_resources.triangle_count,
            component_count: replay.mesh_component_summary.component_count,
            component_vertex_counts: replay.mesh_component_summary.vertex_counts.clone(),
            component_triangle_counts: replay.mesh_component_summary.triangle_counts.clone(),
            skinned_position_buffer_bytes: self.draw_resources.skinned_position_buffer_bytes as u64,
            triangle_index_buffer_bytes: self.draw_resources.triangle_buffer_bytes as u64,
            live_compact_input_frame: live_hand_frame.is_some(),
            diagnostic_settings,
        })
    }

    pub(crate) unsafe fn record_overlay_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        target_rect: TargetRect,
        stats: &GpuHandMeshVisualFrameStats,
    ) {
        if !stats.ready || stats.drawn_vertex_count == 0 {
            return;
        }
        let mut params = stats.diagnostic_settings.push_params();
        if stats.live_compact_input_frame && stats.diagnostic_settings.enabled {
            params[0] += live_hand_mesh_proof_offset_x(stats.handedness);
        }
        let push = HandMeshVisualPush {
            target_rect: [
                target_rect.x,
                target_rect.y,
                target_rect.width,
                target_rect.height,
            ],
            params,
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
            offset: vk::Offset2D {
                x: (extent.width as f32 * target_rect.x).round() as i32,
                y: (extent.height as f32 * target_rect.y).round() as i32,
            },
            extent: vk::Extent2D {
                width: (extent.width as f32 * target_rect.width).round().max(1.0) as u32,
                height: (extent.height as f32 * target_rect.height).round().max(1.0) as u32,
            },
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
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            as_bytes(&push),
        );
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_draw(cmd, stats.drawn_vertex_count, 1, 0, 0);
    }
}

fn live_hand_mesh_proof_offset_x(handedness: &'static str) -> f32 {
    match handedness {
        "left" => -0.16,
        "right" => 0.16,
        _ => 0.0,
    }
}

unsafe fn create_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/hand_mesh_visual.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/hand_mesh_visual.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create hand mesh visual vertex shader module: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create hand mesh visual fragment shader module: {error}"
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
        .map_err(|(_, error)| format!("create hand mesh visual graphics pipeline: {error}"))
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

fn join_u64(values: &[u64]) -> String {
    values
        .iter()
        .map(u64::to_string)
        .collect::<Vec<_>>()
        .join(",")
}

#[repr(C)]
struct HandMeshVisualPush {
    target_rect: [f32; 4],
    params: [f32; 4],
}
