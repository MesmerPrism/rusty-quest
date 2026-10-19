//! Descriptorless batched native vector UI on the caller's Vulkan device/render pass.
//! No XR instance, swapchain, Android producer or frame loop is created here.
use crate::{
    gpu_hand_mesh_visual::HandMeshVisualEyeProjection, native_mesh_ui::PanelPose,
    native_panel_ui_geometry::Primitive,
};
use ash::vk;
use std::{ffi::CString, mem};
const CAPACITY: usize = 1024;
struct Slot {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    mapped: *mut u8,
    count: u32,
}
pub(crate) struct GpuNativePanelUi {
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    slots: Vec<Slot>,
}
#[repr(C)]
struct PanelPush {
    center_width: [f32; 4],
    right_height: [f32; 4],
    up_near: [f32; 4],
    eye_far: [f32; 4],
    eye_orientation: [f32; 4],
    fov: [f32; 4],
}
impl GpuNativePanelUi {
    /// Caller owns the device, render pass and depth selection. Two slots match host fences.
    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        scene_depth_enabled: bool,
    ) -> Result<Self, String> {
        let ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX)
            .offset(0)
            .size(mem::size_of::<PanelPush>() as u32)];
        let layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default().push_constant_ranges(&ranges),
                None,
            )
            .map_err(|e| e.to_string())?;
        let pipeline = match pipeline(device, render_pass, layout, scene_depth_enabled) {
            Ok(p) => p,
            Err(e) => {
                device.destroy_pipeline_layout(layout, None);
                return Err(e);
            }
        };
        let mut result = Self {
            layout,
            pipeline,
            slots: Vec::with_capacity(2),
        };
        for _ in 0..2 {
            match create_slot(device, &memory) {
                Ok(s) => result.slots.push(s),
                Err(e) => {
                    result.destroy(device);
                    return Err(e);
                }
            }
        }
        Ok(result)
    }
    /// MUST only run after the caller has waited for this exact frame slot's fence.
    /// Reject oversized or invalid input atomically; never display stale geometry on failure.
    pub(crate) unsafe fn prepare_frame(
        &mut self,
        slot: usize,
        primitives: &[Primitive],
    ) -> Result<(), String> {
        let s = self
            .slots
            .get_mut(slot)
            .ok_or("native UI slot out of range")?;
        s.count = 0;
        if primitives.len() > CAPACITY {
            return Err("native UI primitive capacity exceeded".into());
        }
        if primitives.iter().any(|p| {
            p.rect
                .iter()
                .chain(p.color.iter())
                .chain(p.shape.iter())
                .any(|v| !v.is_finite())
                || p.rect[2] <= 0.0
                || p.rect[3] <= 0.0
        }) {
            return Err("native UI invalid primitive".into());
        }
        std::ptr::copy_nonoverlapping(
            primitives.as_ptr().cast::<u8>(),
            s.mapped,
            mem::size_of_val(primitives),
        );
        s.count = primitives.len() as u32;
        Ok(())
    }
    /// Inside the host render pass, after its viewport/scissor. One instanced draw per eye.
    pub(crate) unsafe fn record_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        pose: PanelPose,
        eye: HandMeshVisualEyeProjection,
    ) {
        let Some(prepared) = self.slots.get(slot) else {
            return;
        };
        self.record_eye_range(device, cmd, slot, pose, eye, 0, prepared.count);
    }
    /// Draw only a prepared instance subset at this pose. Invalid/empty ranges record nothing.
    /// Range validation uses checked addition; the instance buffer remains bound at byte offset zero.
    pub(crate) unsafe fn record_eye_range(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        pose: PanelPose,
        eye: HandMeshVisualEyeProjection,
        first: u32,
        count: u32,
    ) {
        let Some(s) = self.slots.get(slot) else {
            return;
        };
        if count == 0 || first.checked_add(count).is_none_or(|end| end > s.count) {
            return;
        }
        let p = PanelPush {
            center_width: [pose.center[0], pose.center[1], pose.center[2], pose.width],
            right_height: [pose.right[0], pose.right[1], pose.right[2], pose.height],
            up_near: [pose.up[0], pose.up[1], pose.up[2], 0.05],
            eye_far: [eye.position[0], eye.position[1], eye.position[2], 20.0],
            eye_orientation: eye.orientation_xyzw,
            fov: eye.fov_tangents,
        };
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
        device.cmd_bind_vertex_buffers(cmd, 0, &[s.buffer], &[0]);
        device.cmd_push_constants(
            cmd,
            self.layout,
            vk::ShaderStageFlags::VERTEX,
            0,
            std::slice::from_raw_parts(
                (&p as *const PanelPush).cast::<u8>(),
                mem::size_of::<PanelPush>(),
            ),
        );
        device.cmd_draw(cmd, 6, count, 0, first);
    }
    /// Caller MUST retire all referenced command buffers/fences before destruction.
    pub(crate) unsafe fn destroy(mut self, device: &ash::Device) {
        for s in self.slots.drain(..) {
            device.unmap_memory(s.memory);
            device.destroy_buffer(s.buffer, None);
            device.free_memory(s.memory, None);
        }
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.layout, None);
    }
}
unsafe fn create_slot(
    device: &ash::Device,
    properties: &vk::PhysicalDeviceMemoryProperties,
) -> Result<Slot, String> {
    let buffer = device
        .create_buffer(
            &vk::BufferCreateInfo::default()
                .size((CAPACITY * mem::size_of::<Primitive>()) as u64)
                .usage(vk::BufferUsageFlags::VERTEX_BUFFER)
                .sharing_mode(vk::SharingMode::EXCLUSIVE),
            None,
        )
        .map_err(|e| e.to_string())?;
    let req = device.get_buffer_memory_requirements(buffer);
    let flags = vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
    let Some(index) = (0..properties.memory_type_count).find(|&i| {
        req.memory_type_bits & (1 << i) != 0
            && properties.memory_types[i as usize]
                .property_flags
                .contains(flags)
    }) else {
        device.destroy_buffer(buffer, None);
        return Err("native UI needs coherent host memory".into());
    };
    let memory = match device.allocate_memory(
        &vk::MemoryAllocateInfo::default()
            .allocation_size(req.size)
            .memory_type_index(index),
        None,
    ) {
        Ok(m) => m,
        Err(e) => {
            device.destroy_buffer(buffer, None);
            return Err(e.to_string());
        }
    };
    if let Err(e) = device.bind_buffer_memory(buffer, memory, 0) {
        device.destroy_buffer(buffer, None);
        device.free_memory(memory, None);
        return Err(e.to_string());
    }
    let mapped = match device.map_memory(memory, 0, req.size, vk::MemoryMapFlags::empty()) {
        Ok(p) => p.cast::<u8>(),
        Err(e) => {
            device.destroy_buffer(buffer, None);
            device.free_memory(memory, None);
            return Err(e.to_string());
        }
    };
    Ok(Slot {
        buffer,
        memory,
        mapped,
        count: 0,
    })
}
fn words(bytes: &[u8]) -> Result<Vec<u32>, String> {
    if bytes.len() % 4 != 0 {
        return Err("invalid native UI SPIR-V".into());
    }
    Ok(bytes
        .chunks_exact(4)
        .map(|b| u32::from_le_bytes(b.try_into().unwrap()))
        .collect())
}
unsafe fn pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    scene_depth_enabled: bool,
) -> Result<vk::Pipeline, String> {
    let vertex = words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/native_panel_ui.vert.spv"
    )))?;
    let fragment = words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/native_panel_ui.frag.spv"
    )))?;
    let v = device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&vertex), None)
        .map_err(|e| e.to_string())?;
    let f = match device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&fragment), None)
    {
        Ok(f) => f,
        Err(e) => {
            device.destroy_shader_module(v, None);
            return Err(e.to_string());
        }
    };
    let name = CString::new("main").unwrap();
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(v)
            .name(&name),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(f)
            .name(&name),
    ];
    let bindings = [vk::VertexInputBindingDescription {
        binding: 0,
        stride: mem::size_of::<Primitive>() as u32,
        input_rate: vk::VertexInputRate::INSTANCE,
    }];
    let attributes = std::array::from_fn::<_, 3, _>(|i| vk::VertexInputAttributeDescription {
        location: i as u32,
        binding: 0,
        format: vk::Format::R32G32B32A32_SFLOAT,
        offset: (i * 16) as u32,
    });
    let input = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(&bindings)
        .vertex_attribute_descriptions(&attributes);
    let assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let raster = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .line_width(1.0);
    let samples = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let attachments = [vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::ONE)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(vk::ColorComponentFlags::RGBA)];
    let blend = vk::PipelineColorBlendStateCreateInfo::default().attachments(&attachments);
    let depth = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(scene_depth_enabled)
        .depth_write_enable(false)
        .depth_compare_op(if scene_depth_enabled {
            vk::CompareOp::LESS_OR_EQUAL
        } else {
            vk::CompareOp::ALWAYS
        });
    let states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&states);
    let info = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&input)
        .input_assembly_state(&assembly)
        .viewport_state(&viewport)
        .rasterization_state(&raster)
        .multisample_state(&samples)
        .color_blend_state(&blend)
        .depth_stencil_state(&depth)
        .dynamic_state(&dynamic)
        .layout(layout)
        .render_pass(render_pass)
        .subpass(0)];
    let result = device.create_graphics_pipelines(vk::PipelineCache::null(), &info, None);
    device.destroy_shader_module(v, None);
    device.destroy_shader_module(f, None);
    result.map(|p| p[0]).map_err(|(partial, e)| {
        for p in partial {
            device.destroy_pipeline(p, None);
        }
        e.to_string()
    })
}
