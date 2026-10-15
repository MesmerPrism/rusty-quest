//! Real Android Presentation texture on a depth-tested native world-space mesh.
//! All image references and descriptor sets retire only after the owning frame-slot fence.
use crate::{
    ahardware_buffer_vulkan::{
        self as ahb, AhbVulkanFormatKey, AhbVulkanSampledImage, AhbVulkanSampledImageCreateInfo,
    },
    gpu_hand_mesh_visual::HandMeshVisualEyeProjection,
    native_alternate_input::PointerOwner,
    native_mesh_ui::{
        self,
        android::{ImageLease, Producer},
        PointerCapture,
    },
    native_pointer_visual::{segment_pose, PointerVisual},
};
use ash::vk;
use std::{cell::Cell, ffi::CString, mem, rc::Rc};

struct TextureLease {
    device: ash::Device,
    image: Option<AhbVulkanSampledImage>,
    _lease: ImageLease,
    graphics_owned: Cell<bool>,
}
impl Drop for TextureLease {
    fn drop(&mut self) {
        if let Some(image) = self.image.take() {
            unsafe { image.destroy(&self.device) }
        }
    }
}
pub(crate) struct GpuMeshUiPanel {
    producer: Option<Producer>,
    ahb: ahb::AhbVulkanDevice,
    memory: vk::PhysicalDeviceMemoryProperties,
    queue_family: u32,
    registry: Vec<Rc<TextureLease>>,
    retired: [Vec<Rc<TextureLease>>; 2],
    pending_acquires: [Vec<Rc<TextureLease>>; 2],
    sampler: vk::Sampler,
    set_layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    sets: [vk::DescriptorSet; 2],
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    pointer_pipeline: vk::Pipeline,
    pointer_owner: PointerOwner,
    visual: Option<PointerVisual>,
    latest: Option<Rc<TextureLease>>,
    slots: [Option<Rc<TextureLease>>; 2],
    poses: [native_mesh_ui::PanelPose; 2],
    frame_controls: native_mesh_ui::ControlSnapshot,
    pointer: PointerCapture,
    pub(crate) adopted_frames: u64,
}
#[repr(C)]
struct PanelPush {
    center_width: [f32; 4],
    right_height: [f32; 4],
    up_near: [f32; 4],
    eye_far: [f32; 4],
    eye_orientation: [f32; 4],
    fov: [f32; 4],
    color_mode: [f32; 4],
}
impl GpuMeshUiPanel {
    pub(crate) fn request_visibility(&self, visible: bool) -> bool {
        self.producer
            .as_ref()
            .and_then(|p| native_mesh_ui::request_visibility(p.generation, visible))
            .is_some()
    }
    pub(crate) fn toggle_visibility(&self) -> bool {
        self.request_visibility(!native_mesh_ui::snapshot().panel_visible)
    }
    pub(crate) fn initialize_pose(&mut self, eye: HandMeshVisualEyeProjection) -> bool {
        let Some(producer) = self.producer.as_ref() else {
            return false;
        };
        let initialized = native_mesh_ui::initialize_pose(
            producer.generation,
            [eye.position[0], eye.position[1], eye.position[2]],
            eye.orientation_xyzw,
        );
        if initialized {
            let pose = native_mesh_ui::snapshot().pose;
            crate::marker("native-mesh-ui",format!("event=world-pose-calibrated generation={} center={:.3},{:.3},{:.3} distanceMeters=1.4 poseSource=first-valid-central-eye headLocked=false",producer.generation,pose.center[0],pose.center[1],pose.center[2]));
        }
        initialized
    }
    /// Opt-in owner only. Caller owns renderpass and shared device; no second XR loop.
    pub(crate) unsafe fn new(
        app: &android_activity::AndroidApp,
        instance: &ash::Instance,
        device: &ash::Device,
        memory: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        scene_depth_enabled: bool,
        generation: u64,
        queue_family: u32,
    ) -> Result<Self, String> {
        if !native_mesh_ui::enabled_for_mode("native-worldspace-integration")
            || !scene_depth_enabled
            || generation == 0
        {
            return Err("mesh UI requires world-space scene depth and nonzero generation".into());
        }
        let sampler = device
            .create_sampler(
                &vk::SamplerCreateInfo::default()
                    .min_filter(vk::Filter::LINEAR)
                    .mag_filter(vk::Filter::LINEAR)
                    .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .max_lod(0.0),
                None,
            )
            .map_err(|e| format!("mesh UI sampler: {e}"))?;
        let binding = [vk::DescriptorSetLayoutBinding::default()
            .binding(0)
            .descriptor_count(1)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .stage_flags(vk::ShaderStageFlags::FRAGMENT)];
        let set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&binding),
            None,
        ) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_sampler(sampler, None);
                return Err(e.to_string());
            }
        };
        let pool_sizes = [vk::DescriptorPoolSize {
            ty: vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
            descriptor_count: 2,
        }];
        let pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .max_sets(2)
                .pool_sizes(&pool_sizes),
            None,
        ) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e.to_string());
            }
        };
        let layouts = [set_layout; 2];
        let allocated = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(pool)
                .set_layouts(&layouts),
        ) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e.to_string());
            }
        };
        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(mem::size_of::<PanelPush>() as u32)];
        let layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&[set_layout])
                .push_constant_ranges(&push_ranges),
            None,
        ) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e.to_string());
            }
        };
        let panel_pipeline = match pipeline(device, render_pass, layout, false) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_pipeline_layout(layout, None);
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e);
            }
        };
        let pointer_pipeline = match pipeline(device, render_pass, layout, true) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_pipeline(panel_pipeline, None);
                device.destroy_pipeline_layout(layout, None);
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e);
            }
        };
        let producer = match Producer::start(app, generation) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_pipeline(panel_pipeline, None);
                device.destroy_pipeline(pointer_pipeline, None);
                device.destroy_pipeline_layout(layout, None);
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e);
            }
        };
        crate::marker("native-mesh-ui",format!("event=created generation={generation} source=android-virtual-display-presentation frameOwner=native-openxr depth=scene-zbuffer"));
        Ok(Self {
            producer: Some(producer),
            ahb: ahb::AhbVulkanDevice::new(instance, device),
            memory,
            queue_family,
            registry: Vec::new(),
            retired: [Vec::new(), Vec::new()],
            pending_acquires: [Vec::new(), Vec::new()],
            sampler,
            set_layout,
            pool,
            sets: [allocated[0], allocated[1]],
            layout,
            pipeline: panel_pipeline,
            pointer_pipeline,
            pointer_owner: PointerOwner::None,
            visual: None,
            latest: None,
            slots: [None, None],
            poses: [native_mesh_ui::PanelPose::default(); 2],
            frame_controls: native_mesh_ui::ControlSnapshot::default(),
            pointer: PointerCapture::default(),
            adopted_frames: 0,
        })
    }
    /// MUST run after this slot fence completed, outside renderpass. Old image remains leased
    /// by latest/other slot while referenced; no AHB identity cache or stale descriptor reuse.
    pub(crate) unsafe fn prepare_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
    ) -> Result<bool, String> {
        if slot >= 2 {
            return Err("mesh UI frame slot out of range".into());
        }
        self.retired[slot].clear();
        self.slots[slot] = None;
        // Freeze one pose for both eyes; asynchronous Android controls apply next frame.
        self.frame_controls = native_mesh_ui::snapshot();
        self.poses[slot] = self.frame_controls.pose;
        if !self.frame_controls.pose_initialized {
            return Ok(false);
        }
        if let Some(lease) = self.producer.as_mut().and_then(|p| p.acquire()) {
            let desc = lease.buffer.descriptor();
            if desc.width != native_mesh_ui::WIDTH
                || desc.height != native_mesh_ui::HEIGHT
                || desc.layers != 1
                || desc.format != 1
            {
                return Err("mesh UI reader descriptor mismatch".into());
            }
            let (props, format) =
                ahb::query_ahb_vulkan_import_properties(&self.ahb, &lease.buffer)?;
            // An RGBA reader must expose a concrete format. Never silently reinterpret YCbCr.
            if format.format == vk::Format::UNDEFINED {
                return Err("mesh UI RGBA AHB has no concrete Vulkan format".into());
            }
            let image = ahb::import_ahb_sampled_image(
                device,
                &self.memory,
                &lease.buffer,
                AhbVulkanSampledImageCreateInfo {
                    width: desc.width,
                    height: desc.height,
                    format_key: AhbVulkanFormatKey {
                        format: format.format,
                        external_format: 0,
                    },
                    allocation_size: props.allocation_size,
                    memory_type_bits: props.memory_type_bits,
                    sampler_ycbcr_conversion: None,
                    debug_label: "native mesh UI",
                },
            )?;
            ownership_barrier(device, cmd, image.image, self.queue_family, true);
            let texture = Rc::new(TextureLease {
                device: device.clone(),
                image: Some(image),
                _lease: lease,
                graphics_owned: Cell::new(false),
            });
            self.pending_acquires[slot].push(texture.clone());
            self.registry.push(texture.clone());
            self.latest = Some(texture);
            self.adopted_frames = self.adopted_frames.saturating_add(1);
            if self.adopted_frames == 1 {
                crate::marker("native-mesh-ui","event=first-image-adopted textureSource=android-widget-surface cpuPainted=false visibleProof=false");
            }
        }
        self.slots[slot] = self.latest.clone();
        // Unused imports transfer back to Android in this frame's command buffer, but their
        // Vk resources and AImages remain pinned until this slot's submission fence retires.
        let mut index = 0;
        while index < self.registry.len() {
            if Rc::strong_count(&self.registry[index]) == 1 {
                let old = self.registry.remove(index);
                ownership_barrier(
                    device,
                    cmd,
                    old.image.as_ref().unwrap().image,
                    self.queue_family,
                    false,
                );
                self.retired[slot].push(old);
            } else {
                index += 1;
            }
        }
        if let Some(texture) = self.slots[slot].as_ref() {
            let image = [vk::DescriptorImageInfo::default()
                .sampler(self.sampler)
                .image_view(texture.image.as_ref().unwrap().image_view)
                .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
            let write = [vk::WriteDescriptorSet::default()
                .dst_set(self.sets[slot])
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .image_info(&image)];
            device.update_descriptor_sets(&write, &[]);
            Ok(true)
        } else {
            Ok(false)
        }
    }
    pub(crate) fn frame_controls(&self) -> native_mesh_ui::ControlSnapshot {
        self.frame_controls
    }
    /// Call immediately after successful submission of the command buffer recorded for slot.
    pub(crate) fn mark_submitted(&mut self, slot: usize) {
        for texture in self.pending_acquires[slot].drain(..) {
            texture.graphics_owned.set(true);
        }
        for texture in &self.retired[slot] {
            texture.graphics_owned.set(false);
        }
    }
    pub(crate) unsafe fn record_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        eye: HandMeshVisualEyeProjection,
        viewport: vk::Viewport,
        scissor: vk::Rect2D,
    ) {
        if slot >= 2
            || self.slots[slot].is_none()
            || !self.frame_controls.pose_initialized
            || !self.frame_controls.panel_visible
        {
            return;
        }
        let p = self.poses[slot];
        let push = PanelPush {
            center_width: [p.center[0], p.center[1], p.center[2], p.width],
            right_height: [p.right[0], p.right[1], p.right[2], p.height],
            up_near: [p.up[0], p.up[1], p.up[2], 0.05],
            eye_far: [eye.position[0], eye.position[1], eye.position[2], 20.0],
            eye_orientation: eye.orientation_xyzw,
            fov: eye.fov_tangents,
            color_mode: [1.0; 4],
        };
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.layout,
            0,
            &[self.sets[slot]],
            &[],
        );
        let bytes = std::slice::from_raw_parts(
            (&push as *const PanelPush).cast::<u8>(),
            mem::size_of::<PanelPush>(),
        );
        device.cmd_push_constants(
            cmd,
            self.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            bytes,
        );
        device.cmd_set_viewport(cmd, 0, &[viewport]);
        device.cmd_set_scissor(cmd, 0, &[scissor]);
        device.cmd_draw(cmd, 6, 1, 0, 0);
    }
    pub(crate) fn set_pointer_owner(&mut self, owner: PointerOwner) {
        self.pointer_owner = owner;
    }
    pub(crate) fn pointer_visual_marker(&self) -> &'static str {
        match self.visual {
            Some(v) if v.hit => "ray-and-hit-reticle",
            Some(_) => "ray",
            None => "none",
        }
    }
    /// Solid app-owned geometry, independent of UI image adoption and descriptors.
    /// Draw last inside the same scene pass; depth test preserves world occlusion.
    pub(crate) unsafe fn record_pointer_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        eye: HandMeshVisualEyeProjection,
        viewport: vk::Viewport,
        scissor: vk::Rect2D,
    ) {
        if !self.frame_controls.panel_visible {
            return;
        }
        let Some(visual) = self.visual else {
            return;
        };
        let q = eye.orientation_xyzw;
        let rotate = |v: [f32; 3]| {
            let u = [q[0], q[1], q[2]];
            let a = crate::native_pointer_visual::cross(u, v);
            let b = crate::native_pointer_visual::cross(u, a);
            std::array::from_fn(|i| v[i] + 2.0 * (q[3] * a[i] + b[i]))
        };
        let eye_right = rotate([1.0, 0.0, 0.0]);
        let eye_up = rotate([0.0, 1.0, 0.0]);
        let position = [eye.position[0], eye.position[1], eye.position[2]];
        let ray = segment_pose(visual.origin, visual.end, position, eye_right, 0.003);
        let reticle = visual.hit.then_some(native_mesh_ui::PanelPose {
            center: visual.end,
            right: eye_right,
            up: eye_up,
            width: 0.022,
            height: 0.022,
        });
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pointer_pipeline);
        device.cmd_set_viewport(cmd, 0, &[viewport]);
        device.cmd_set_scissor(cmd, 0, &[scissor]);
        for (pose, mode) in [(ray, 0.0), (reticle, 1.0)] {
            let Some(p) = pose else {
                continue;
            };
            let color = visual.color();
            let push = PanelPush {
                center_width: [p.center[0], p.center[1], p.center[2], p.width],
                right_height: [p.right[0], p.right[1], p.right[2], p.height],
                up_near: [p.up[0], p.up[1], p.up[2], 0.05],
                eye_far: [position[0], position[1], position[2], 20.0],
                eye_orientation: q,
                fov: eye.fov_tangents,
                color_mode: [color[0], color[1], color[2], mode],
            };
            let bytes = std::slice::from_raw_parts(
                (&push as *const PanelPush).cast::<u8>(),
                mem::size_of::<PanelPush>(),
            );
            device.cmd_push_constants(
                cmd,
                self.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                bytes,
            );
            device.cmd_draw(cmd, 6, 1, 0, 0);
        }
    }
    pub(crate) fn controller_ray(
        &mut self,
        app: &android_activity::AndroidApp,
        valid: bool,
        pressed: bool,
        origin: [f32; 3],
        direction: [f32; 3],
    ) -> Result<bool, String> {
        let controls = native_mesh_ui::snapshot();
        let valid = valid && controls.pose_initialized && controls.panel_visible;
        self.visual = PointerVisual::new(
            self.pointer_owner,
            valid,
            pressed,
            origin,
            direction,
            controls.pose_initialized.then_some(controls.pose),
        );
        let pixels = native_mesh_ui::ray_pixels(controls.pose, origin, direction);
        let captured_before = self.pointer.captured();
        let event = self.pointer.update(valid, pressed, pixels);
        if let Some((action, p)) = event {
            if let Some(producer) = &self.producer {
                producer.pointer(app, action, p)?;
            }
        } else if !self.pointer.captured() {
            if let Some(producer) = &self.producer {
                let hovered = pixels.filter(|p| {
                    valid
                        && (0.0..native_mesh_ui::WIDTH as f32).contains(&p[0])
                        && (0.0..native_mesh_ui::HEIGHT as f32).contains(&p[1])
                });
                producer.pointer(
                    app,
                    if hovered.is_some() { 4 } else { 5 },
                    hovered.unwrap_or([0.0, 0.0]),
                )?;
            }
        }
        Ok(captured_before || self.pointer.captured() || event.is_some())
    }
    /// Quiesce the writer but retain every GPU/image owner when retirement is unproven.
    /// The caller MUST also retain the Vulkan device/instance and loader until process exit.
    pub(crate) unsafe fn retain_unretired(mut self, app: &android_activity::AndroidApp) {
        if let Some(producer) = self.producer.take() {
            producer.quiesce(app);
        }
        std::mem::forget(self);
    }
    /// Call only after ALL referenced frame fences completed and graphics queue retired.
    /// Err means this panel and its release submission remain retained; the device owner
    /// must retain its entire device closure rather than destroying it beneath them.
    pub(crate) unsafe fn destroy(
        mut self,
        app: &android_activity::AndroidApp,
        device: &ash::Device,
    ) -> Result<(), String> {
        self.slots = [None, None];
        self.latest = None;
        self.pending_acquires[0].clear();
        self.pending_acquires[1].clear();
        self.registry.append(&mut self.retired[0]);
        self.registry.append(&mut self.retired[1]);
        // Caller already retired all draw fences. Return the still-current Android images
        // with an explicit graphics→FOREIGN release before allowing ImageReader recycling.
        if !self.registry.is_empty() {
            if let Err(error) = release_at_shutdown(device, self.queue_family, &self.registry) {
                crate::marker(
                    "native-mesh-ui",
                    format!(
                        "event=image-retirement-failed reason={}",
                        crate::sanitize(&error)
                    ),
                );
                // The new release submission may itself still be pending. Retain the whole
                // panel, including its descriptors/pipeline, and propagate to the device owner.
                self.retain_unretired(app);
                return Err(error);
            }
        }
        self.registry.clear();
        if let Some(producer) = self.producer.take() {
            if let Err(error) = producer.stop(app) {
                crate::marker(
                    "native-mesh-ui",
                    format!("event=stop-failed reason={}", crate::sanitize(&error)),
                );
            }
        }
        device.destroy_pipeline(self.pointer_pipeline, None);
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.layout, None);
        device.destroy_descriptor_pool(self.pool, None);
        device.destroy_descriptor_set_layout(self.set_layout, None);
        device.destroy_sampler(self.sampler, None);
        Ok(())
    }
}
unsafe fn pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    solid_pointer: bool,
) -> Result<vk::Pipeline, String> {
    let vertex = words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/mesh_ui_panel.vert.spv"
    )))?;
    let fragment = if solid_pointer {
        words(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/mesh_pointer.frag.spv"
        )))?
    } else {
        words(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/mesh_ui_panel.frag.spv"
        )))?
    };
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
    let input = vk::PipelineVertexInputStateCreateInfo::default();
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
        .blend_enable(false)
        .color_write_mask(vk::ColorComponentFlags::RGBA)];
    let blend = vk::PipelineColorBlendStateCreateInfo::default().attachments(&attachments);
    let depth = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(true)
        .depth_write_enable(!solid_pointer)
        .depth_compare_op(vk::CompareOp::LESS_OR_EQUAL);
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
/// Android imported image contents are preserved. FOREIGN ownership is independent from
/// the Vulkan frame fence, which protects application descriptors and image lifetimes.
unsafe fn ownership_barrier(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    image: vk::Image,
    queue_family: u32,
    acquire: bool,
) {
    let barrier = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(vk::ImageSubresourceRange {
            aspect_mask: vk::ImageAspectFlags::COLOR,
            base_mip_level: 0,
            level_count: 1,
            base_array_layer: 0,
            layer_count: 1,
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
        .src_access_mask(if acquire {
            vk::AccessFlags::empty()
        } else {
            vk::AccessFlags::SHADER_READ
        })
        .dst_access_mask(if acquire {
            vk::AccessFlags::SHADER_READ
        } else {
            vk::AccessFlags::empty()
        })];
    device.cmd_pipeline_barrier(
        cmd,
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
        &barrier,
    );
}
unsafe fn release_at_shutdown(
    device: &ash::Device,
    queue_family: u32,
    textures: &[Rc<TextureLease>],
) -> Result<(), String> {
    let pool = device
        .create_command_pool(
            &vk::CommandPoolCreateInfo::default()
                .queue_family_index(queue_family)
                .flags(vk::CommandPoolCreateFlags::TRANSIENT),
            None,
        )
        .map_err(|e| e.to_string())?;
    let result = (|| {
        let cmd = device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(1),
            )
            .map_err(|e| e.to_string())?[0];
        device
            .begin_command_buffer(
                cmd,
                &vk::CommandBufferBeginInfo::default()
                    .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
            )
            .map_err(|e| e.to_string())?;
        for texture in textures {
            if texture.graphics_owned.get() {
                ownership_barrier(
                    device,
                    cmd,
                    texture.image.as_ref().unwrap().image,
                    queue_family,
                    false,
                );
            }
        }
        device.end_command_buffer(cmd).map_err(|e| e.to_string())?;
        let fence = device
            .create_fence(&vk::FenceCreateInfo::default(), None)
            .map_err(|e| e.to_string())?;
        let buffers = [cmd];
        let submissions = [vk::SubmitInfo::default().command_buffers(&buffers)];
        if let Err(error) = device.queue_submit(
            device.get_device_queue(queue_family, 0),
            &submissions,
            fence,
        ) {
            device.destroy_fence(fence, None);
            return Err(error.to_string());
        }
        let waited = device.wait_for_fences(&[fence], true, 1_000_000_000);
        if let Err(error) = waited {
            // Do not destroy pending command pool/fence on timeout. Device owner can retire
            // its queue on shutdown; these bounded objects remain alive in the interim.
            return Err(error.to_string());
        }
        device.destroy_fence(fence, None);
        Ok(())
    })();
    if result.is_ok() {
        device.destroy_command_pool(pool, None);
    }
    result
}
fn words(bytes: &[u8]) -> Result<Vec<u32>, String> {
    if bytes.len() % 4 != 0 {
        return Err("misaligned mesh UI SPIR-V".into());
    }
    Ok(bytes
        .chunks_exact(4)
        .map(|p| u32::from_le_bytes([p[0], p[1], p[2], p[3]]))
        .collect())
}
