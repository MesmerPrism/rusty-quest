//! Small public procedural cube and particle field on the host's Vulkan pass.
//! The Android panel supplies a real sampled texture; the cube uses a procedural UV material.
use crate::{gpu_hand_mesh_visual::HandMeshVisualEyeProjection, native_mesh_ui::ControlSnapshot};
use ash::vk;
use std::{ffi::CString, mem};

#[repr(C)]
struct ScenePush {
    center_width: [f32; 4],
    right_height: [f32; 4],
    up_near: [f32; 4],
    eye_far: [f32; 4],
    eye_orientation: [f32; 4],
    fov: [f32; 4],
    parameters: [f32; 4],
}
pub(crate) struct GpuDevelopmentFeedbackScene {
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
}
impl GpuDevelopmentFeedbackScene {
    pub(crate) unsafe fn new(
        device: &ash::Device,
        render_pass: vk::RenderPass,
    ) -> Result<Self, String> {
        let range = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX)
            .offset(0)
            .size(mem::size_of::<ScenePush>() as u32)];
        let layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default().push_constant_ranges(&range),
                None,
            )
            .map_err(|e| e.to_string())?;
        match pipeline(
            device,
            render_pass,
            layout,
            include_bytes!(concat!(
                env!("OUT_DIR"),
                "/development_feedback_scene.vert.spv"
            )),
            include_bytes!(concat!(
                env!("OUT_DIR"),
                "/development_feedback_scene.frag.spv"
            )),
        ) {
            Ok(pipeline) => Ok(Self { layout, pipeline }),
            Err(e) => {
                device.destroy_pipeline_layout(layout, None);
                Err(e)
            }
        }
    }
    /// The current frame slot was already waited/reset by the host. Only this
    /// explicit developer command waits for the other referencing slots.
    pub(crate) unsafe fn replace(
        &mut self,
        device: &ash::Device,
        render_pass: vk::RenderPass,
        candidate: crate::native_development_feedback::ShaderCandidate,
        other_fences: &[vk::Fence],
    ) -> Result<(), String> {
        let prepared = pipeline(
            device,
            render_pass,
            self.layout,
            &candidate.vertex,
            &candidate.fragment,
        )?;
        if !other_fences.is_empty() {
            if let Err(error) = device.wait_for_fences(other_fences, true, 1_000_000_000) {
                device.destroy_pipeline(prepared, None);
                return Err(format!(
                    "shader retirement refused; prior pipeline retained: {error}"
                ));
            }
        }
        let previous = std::mem::replace(&mut self.pipeline, prepared);
        device.destroy_pipeline(previous, None);
        crate::marker(
            "development-feedback-shader",
            format!(
                "generation={} priorPipelineRetired=true replacementCreated=true",
                candidate.generation
            ),
        );
        Ok(())
    }
    pub(crate) unsafe fn record_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        eye: HandMeshVisualEyeProjection,
        controls: ControlSnapshot,
        frame: u64,
        value: i64,
        viewport: vk::Viewport,
    ) {
        let pose = controls.pose;
        let parameters = [
            if controls.animation {
                frame as f32 / 72.0
            } else {
                0.0
            },
            value.rem_euclid(40) as f32,
            controls.particle_scale,
            if controls.particles { 1.0 } else { 0.0 },
        ];
        let push = ScenePush {
            center_width: [pose.center[0], pose.center[1], pose.center[2], pose.width],
            right_height: [pose.right[0], pose.right[1], pose.right[2], pose.height],
            up_near: [
                pose.up[0],
                pose.up[1],
                pose.up[2],
                crate::native_scene_depth::NEAR_METERS,
            ],
            eye_far: [
                eye.position[0],
                eye.position[1],
                eye.position[2],
                crate::native_scene_depth::FAR_METERS,
            ],
            eye_orientation: eye.orientation_xyzw,
            fov: eye.fov_tangents,
            parameters,
        };
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
        device.cmd_set_viewport(cmd, 0, &[viewport]);
        device.cmd_set_scissor(
            cmd,
            0,
            &[vk::Rect2D {
                offset: vk::Offset2D {
                    x: viewport.x as i32,
                    y: viewport.y as i32,
                },
                extent: vk::Extent2D {
                    width: viewport.width as u32,
                    height: viewport.height as u32,
                },
            }],
        );
        let bytes = std::slice::from_raw_parts(
            (&push as *const ScenePush).cast::<u8>(),
            mem::size_of::<ScenePush>(),
        );
        device.cmd_push_constants(cmd, self.layout, vk::ShaderStageFlags::VERTEX, 0, bytes);
        device.cmd_draw(cmd, 36, 65, 0, 0);
    }
    /// Caller must retire every host frame fence before destruction.
    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.layout, None);
    }
}
unsafe fn pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    vertex: &[u8],
    fragment: &[u8],
) -> Result<vk::Pipeline, String> {
    crate::development_feedback_shader::validate(
        vertex,
        crate::development_feedback_shader::Stage::Vertex,
    )?;
    crate::development_feedback_shader::validate(
        fragment,
        crate::development_feedback_shader::Stage::Fragment,
    )?;
    let vertex = words(vertex);
    let fragment = words(fragment);
    let vert = device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&vertex), None)
        .map_err(|e| e.to_string())?;
    let frag = match device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&fragment), None)
    {
        Ok(v) => v,
        Err(e) => {
            device.destroy_shader_module(vert, None);
            return Err(e.to_string());
        }
    };
    let main = CString::new("main").unwrap();
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vert)
            .name(&main),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(frag)
            .name(&main),
    ];
    let input = vk::PipelineVertexInputStateCreateInfo::default();
    let assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let raster = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let samples = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let depth = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(true)
        .depth_write_enable(true)
        .depth_compare_op(vk::CompareOp::LESS_OR_EQUAL);
    let attachments = [vk::PipelineColorBlendAttachmentState::default()
        .color_write_mask(vk::ColorComponentFlags::RGBA)];
    let blend = vk::PipelineColorBlendStateCreateInfo::default().attachments(&attachments);
    let dynamic = vk::PipelineDynamicStateCreateInfo::default()
        .dynamic_states(&[vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR]);
    let info = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&input)
        .input_assembly_state(&assembly)
        .viewport_state(&viewport)
        .rasterization_state(&raster)
        .multisample_state(&samples)
        .depth_stencil_state(&depth)
        .color_blend_state(&blend)
        .dynamic_state(&dynamic)
        .layout(layout)
        .render_pass(render_pass)
        .subpass(0)];
    let result = device.create_graphics_pipelines(vk::PipelineCache::null(), &info, None);
    device.destroy_shader_module(vert, None);
    device.destroy_shader_module(frag, None);
    result.map(|v| v[0]).map_err(|(partial, e)| {
        for pipeline in partial {
            device.destroy_pipeline(pipeline, None);
        }
        e.to_string()
    })
}
fn words(bytes: &[u8]) -> Vec<u32> {
    bytes
        .chunks_exact(4)
        .map(|p| u32::from_le_bytes([p[0], p[1], p[2], p[3]]))
        .collect()
}
