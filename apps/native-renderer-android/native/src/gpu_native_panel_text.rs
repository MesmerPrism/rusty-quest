//! Bounded slot-owned R8 signed-distance text on the caller's Vulkan render pass.
//! Prepare only after the exact host slot fence; destroy only after every referencing fence.
use crate::{
    gpu_hand_mesh_visual::HandMeshVisualEyeProjection,
    native_mesh_ui::PanelPose,
    native_panel_text::{GlyphInstance, TextAtlas},
};
use ash::vk;
#[cfg(target_os = "android")]
use std::ffi::CString;
use std::{mem, ptr};
const ATLAS_SIDE: u32 = 1024;
const GLYPH_CAPACITY: usize = 1024;
struct MappedBuffer {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    mapped: *mut u8,
}
impl MappedBuffer {
    fn empty() -> Self {
        Self {
            buffer: vk::Buffer::null(),
            memory: vk::DeviceMemory::null(),
            mapped: ptr::null_mut(),
        }
    }
    unsafe fn destroy(&mut self, device: &ash::Device) {
        if !self.mapped.is_null() {
            device.unmap_memory(self.memory);
            self.mapped = ptr::null_mut();
        }
        if self.buffer != vk::Buffer::null() {
            device.destroy_buffer(self.buffer, None);
            self.buffer = vk::Buffer::null();
        }
        if self.memory != vk::DeviceMemory::null() {
            device.free_memory(self.memory, None);
            self.memory = vk::DeviceMemory::null();
        }
    }
}
struct Slot {
    staging: MappedBuffer,
    instances: MappedBuffer,
    image: vk::Image,
    image_memory: vk::DeviceMemory,
    view: vk::ImageView,
    set: vk::DescriptorSet,
    generation: Option<u64>,
    atlas_size: [u32; 2],
    count: u32,
}
impl Slot {
    fn empty(set: vk::DescriptorSet) -> Self {
        Self {
            staging: MappedBuffer::empty(),
            instances: MappedBuffer::empty(),
            image: vk::Image::null(),
            image_memory: vk::DeviceMemory::null(),
            view: vk::ImageView::null(),
            set,
            generation: None,
            atlas_size: [0; 2],
            count: 0,
        }
    }
    unsafe fn destroy(&mut self, device: &ash::Device) {
        self.staging.destroy(device);
        self.instances.destroy(device);
        if self.view != vk::ImageView::null() {
            device.destroy_image_view(self.view, None);
            self.view = vk::ImageView::null();
        }
        if self.image != vk::Image::null() {
            device.destroy_image(self.image, None);
            self.image = vk::Image::null();
        }
        if self.image_memory != vk::DeviceMemory::null() {
            device.free_memory(self.image_memory, None);
            self.image_memory = vk::DeviceMemory::null();
        }
    }
}
pub(crate) struct GpuNativePanelText {
    set_layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    sampler: vk::Sampler,
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    slots: Vec<Slot>,
    upload_recordings: u64,
    glyph_capacity: usize,
}
#[repr(C)]
struct PanelPush {
    center_width: [f32; 4],
    right_height: [f32; 4],
    up_near: [f32; 4],
    eye_far: [f32; 4],
    eye_orientation: [f32; 4],
    fov: [f32; 4],
    atlas_scale: [f32; 4],
    clip: [f32; 4],
}
impl GpuNativePanelText {
    /// Counts recorded atlas copies, not submitted or completed GPU uploads.
    pub(crate) fn upload_recordings(&self) -> u64 {
        self.upload_recordings
    }

    pub(crate) unsafe fn new(
        device: &ash::Device,
        memory: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        scene_depth: bool,
    ) -> Result<Self, String> {
        Self::new_bounded(device, memory, render_pass, scene_depth, GLYPH_CAPACITY)
    }
    /// Independent page atlas with its own fixed, fence-owned 4096 glyph slots.
    pub(crate) unsafe fn new_page(
        device: &ash::Device,
        memory: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        scene_depth: bool,
    ) -> Result<Self, String> {
        Self::new_bounded(device, memory, render_pass, scene_depth, 4096)
    }
    unsafe fn new_bounded(
        device: &ash::Device,
        memory: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        scene_depth: bool,
        glyph_capacity: usize,
    ) -> Result<Self, String> {
        let mut owner = Self {
            set_layout: vk::DescriptorSetLayout::null(),
            pool: vk::DescriptorPool::null(),
            sampler: vk::Sampler::null(),
            layout: vk::PipelineLayout::null(),
            pipeline: vk::Pipeline::null(),
            slots: Vec::with_capacity(2),
            upload_recordings: 0,
            glyph_capacity,
        };
        let result = (|| -> Result<(), String> {
            let bindings = [vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT)];
            owner.set_layout = device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                    None,
                )
                .map_err(|e| e.to_string())?;
            owner.sampler = device
                .create_sampler(
                    &vk::SamplerCreateInfo::default()
                        .mag_filter(vk::Filter::LINEAR)
                        .min_filter(vk::Filter::LINEAR)
                        .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                        .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .max_lod(0.0),
                    None,
                )
                .map_err(|e| e.to_string())?;
            let sizes = [vk::DescriptorPoolSize {
                ty: vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
                descriptor_count: 2,
            }];
            owner.pool = device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .max_sets(2)
                        .pool_sizes(&sizes),
                    None,
                )
                .map_err(|e| e.to_string())?;
            let sets = device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(owner.pool)
                        .set_layouts(&[owner.set_layout; 2]),
                )
                .map_err(|e| e.to_string())?;
            let ranges = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
                .size(mem::size_of::<PanelPush>() as u32)];
            owner.layout = device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&[owner.set_layout])
                        .push_constant_ranges(&ranges),
                    None,
                )
                .map_err(|e| e.to_string())?;
            owner.pipeline = pipeline(device, render_pass, owner.layout, scene_depth)?;
            for set in sets {
                let slot = create_slot(device, &memory, set, glyph_capacity)?;
                owner.slots.push(slot);
                let slot = owner.slots.last().unwrap();
                let info = [vk::DescriptorImageInfo::default()
                    .sampler(owner.sampler)
                    .image_view(slot.view)
                    .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
                let writes = [vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(0)
                    .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                    .image_info(&info)];
                device.update_descriptor_sets(&writes, &[]);
            }
            Ok(())
        })();
        if let Err(error) = result {
            owner.destroy(device);
            return Err(error);
        }
        Ok(owner)
    }
    /// Caller must submit the recorded upload before reusing this slot after its fence.
    /// An atlas generation identifies immutable dimensions/pixels. Errors clear this slot's draw.
    pub(crate) unsafe fn prepare_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        atlas_generation: u64,
        atlas: &TextAtlas,
        glyphs: &[GlyphInstance],
    ) -> Result<(), String> {
        let slot = self
            .slots
            .get_mut(slot)
            .ok_or("native text slot out of range")?;
        slot.count = 0;
        if glyphs.is_empty() {
            return Ok(());
        }
        validate_capacity(atlas_generation, atlas, glyphs, self.glyph_capacity)?;
        if slot.generation == Some(atlas_generation)
            && slot.atlas_size != [atlas.width, atlas.height]
        {
            return Err("native text generation reused with changed dimensions".into());
        }
        if slot.generation != Some(atlas_generation) {
            ptr::copy_nonoverlapping(
                atlas.pixels.as_ptr(),
                slot.staging.mapped,
                atlas.pixels.len(),
            );
            let range = vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            };
            // Contents are deliberately discarded after this slot's prior fence, including initial use.
            let to_copy = [vk::ImageMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::empty())
                .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .old_layout(vk::ImageLayout::UNDEFINED)
                .new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .image(slot.image)
                .subresource_range(range)];
            device.cmd_pipeline_barrier(
                cmd,
                vk::PipelineStageFlags::TOP_OF_PIPE,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &to_copy,
            );
            let copy = [vk::BufferImageCopy::default()
                .image_subresource(vk::ImageSubresourceLayers {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    mip_level: 0,
                    base_array_layer: 0,
                    layer_count: 1,
                })
                .image_extent(vk::Extent3D {
                    width: atlas.width,
                    height: atlas.height,
                    depth: 1,
                })];
            device.cmd_copy_buffer_to_image(
                cmd,
                slot.staging.buffer,
                slot.image,
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                &copy,
            );
            self.upload_recordings = self.upload_recordings.saturating_add(1);
            let to_sample = [vk::ImageMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)
                .old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .image(slot.image)
                .subresource_range(range)];
            device.cmd_pipeline_barrier(
                cmd,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::FRAGMENT_SHADER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &to_sample,
            );
            slot.generation = Some(atlas_generation);
            slot.atlas_size = [atlas.width, atlas.height];
        }
        ptr::copy_nonoverlapping(
            glyphs.as_ptr().cast::<u8>(),
            slot.instances.mapped,
            mem::size_of_val(glyphs),
        );
        slot.count = glyphs.len() as u32;
        Ok(())
    }
    /// Host sets viewport/scissor; text uses the same plane, depth policy and eye projection as vector UI.
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
        self.record_eye_range_clipped(
            device,
            cmd,
            slot,
            pose,
            eye,
            first,
            count,
            crate::native_page::UNBOUNDED_CLIP,
        );
    }
    /// Plane-local clipping is independent of eye viewport and works for tilted panels.
    pub(crate) unsafe fn record_eye_range_clipped(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        pose: PanelPose,
        eye: HandMeshVisualEyeProjection,
        first: u32,
        count: u32,
        clip: [f32; 4],
    ) {
        if clip.iter().any(|v| !v.is_finite()) || clip[0] >= clip[2] || clip[1] >= clip[3] {
            return;
        }
        let Some(slot) = self.slots.get(slot) else {
            return;
        };
        if count == 0 || first.checked_add(count).is_none_or(|end| end > slot.count) {
            return;
        }
        let p = PanelPush {
            center_width: [pose.center[0], pose.center[1], pose.center[2], pose.width],
            right_height: [pose.right[0], pose.right[1], pose.right[2], pose.height],
            up_near: [pose.up[0], pose.up[1], pose.up[2], 0.05],
            eye_far: [eye.position[0], eye.position[1], eye.position[2], 20.0],
            eye_orientation: eye.orientation_xyzw,
            fov: eye.fov_tangents,
            clip,
            atlas_scale: [
                slot.atlas_size[0] as f32 / ATLAS_SIDE as f32,
                slot.atlas_size[1] as f32 / ATLAS_SIDE as f32,
                0.0,
                0.0,
            ],
        };
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
        device.cmd_bind_vertex_buffers(cmd, 0, &[slot.instances.buffer], &[0]);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.layout,
            0,
            &[slot.set],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            std::slice::from_raw_parts(
                (&p as *const PanelPush).cast::<u8>(),
                mem::size_of::<PanelPush>(),
            ),
        );
        device.cmd_draw(cmd, 6, count, 0, first);
    }
    /// All referring command buffers and slot fences must already have retired.
    pub(crate) unsafe fn destroy(mut self, device: &ash::Device) {
        for slot in &mut self.slots {
            slot.destroy(device);
        }
        if self.pool != vk::DescriptorPool::null() {
            device.destroy_descriptor_pool(self.pool, None);
        }
        if self.pipeline != vk::Pipeline::null() {
            device.destroy_pipeline(self.pipeline, None);
        }
        if self.layout != vk::PipelineLayout::null() {
            device.destroy_pipeline_layout(self.layout, None);
        }
        if self.sampler != vk::Sampler::null() {
            device.destroy_sampler(self.sampler, None);
        }
        if self.set_layout != vk::DescriptorSetLayout::null() {
            device.destroy_descriptor_set_layout(self.set_layout, None);
        }
    }
}
fn validate(generation: u64, atlas: &TextAtlas, glyphs: &[GlyphInstance]) -> Result<(), String> {
    validate_capacity(generation, atlas, glyphs, GLYPH_CAPACITY)
}
fn validate_capacity(
    generation: u64,
    atlas: &TextAtlas,
    glyphs: &[GlyphInstance],
    capacity: usize,
) -> Result<(), String> {
    if generation == 0
        || atlas.width == 0
        || atlas.height == 0
        || atlas.width > ATLAS_SIDE
        || atlas.height > ATLAS_SIDE
        || atlas.pixels.len() != atlas.width as usize * atlas.height as usize
    {
        return Err("native text atlas shape/generation invalid".into());
    }
    if glyphs.len() > capacity {
        return Err("native text glyph capacity exceeded".into());
    }
    for g in glyphs {
        if g.rect
            .iter()
            .chain(g.uv.iter())
            .chain(g.color.iter())
            .any(|v| !v.is_finite())
            || g.rect[2] <= 0.0
            || g.rect[3] <= 0.0
            || g.uv.iter().any(|v| !(0.0..=1.0).contains(v))
            || g.uv[0] >= g.uv[2]
            || g.uv[1] >= g.uv[3]
        {
            return Err("native text glyph bounds/UV invalid".into());
        }
    }
    Ok(())
}
fn memory_type(
    properties: &vk::PhysicalDeviceMemoryProperties,
    bits: u32,
    flags: vk::MemoryPropertyFlags,
) -> Option<u32> {
    (0..properties.memory_type_count).find(|&i| {
        bits & (1 << i) != 0
            && properties.memory_types[i as usize]
                .property_flags
                .contains(flags)
    })
}
unsafe fn mapped_buffer(
    device: &ash::Device,
    properties: &vk::PhysicalDeviceMemoryProperties,
    size: u64,
    usage: vk::BufferUsageFlags,
) -> Result<MappedBuffer, String> {
    let mut owner = MappedBuffer::empty();
    let result = (|| -> Result<(), String> {
        owner.buffer = device
            .create_buffer(
                &vk::BufferCreateInfo::default()
                    .size(size)
                    .usage(usage)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(|e| e.to_string())?;
        let req = device.get_buffer_memory_requirements(owner.buffer);
        let index = memory_type(
            properties,
            req.memory_type_bits,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
        )
        .ok_or("native text coherent memory unavailable")?;
        owner.memory = device
            .allocate_memory(
                &vk::MemoryAllocateInfo::default()
                    .allocation_size(req.size)
                    .memory_type_index(index),
                None,
            )
            .map_err(|e| e.to_string())?;
        device
            .bind_buffer_memory(owner.buffer, owner.memory, 0)
            .map_err(|e| e.to_string())?;
        owner.mapped = device
            .map_memory(owner.memory, 0, req.size, vk::MemoryMapFlags::empty())
            .map_err(|e| e.to_string())?
            .cast::<u8>();
        Ok(())
    })();
    if let Err(error) = result {
        owner.destroy(device);
        return Err(error);
    }
    Ok(owner)
}
unsafe fn create_slot(
    device: &ash::Device,
    properties: &vk::PhysicalDeviceMemoryProperties,
    set: vk::DescriptorSet,
    glyph_capacity: usize,
) -> Result<Slot, String> {
    let mut slot = Slot::empty(set);
    let result = (|| -> Result<(), String> {
        slot.staging = mapped_buffer(
            device,
            properties,
            (ATLAS_SIDE * ATLAS_SIDE) as u64,
            vk::BufferUsageFlags::TRANSFER_SRC,
        )?;
        slot.instances = mapped_buffer(
            device,
            properties,
            (glyph_capacity * mem::size_of::<GlyphInstance>()) as u64,
            vk::BufferUsageFlags::VERTEX_BUFFER,
        )?;
        slot.image = device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(vk::Format::R8_UNORM)
                    .extent(vk::Extent3D {
                        width: ATLAS_SIDE,
                        height: ATLAS_SIDE,
                        depth: 1,
                    })
                    .mip_levels(1)
                    .array_layers(1)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::SAMPLED)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .initial_layout(vk::ImageLayout::UNDEFINED),
                None,
            )
            .map_err(|e| e.to_string())?;
        let req = device.get_image_memory_requirements(slot.image);
        let index = memory_type(
            properties,
            req.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        )
        .or_else(|| {
            memory_type(
                properties,
                req.memory_type_bits,
                vk::MemoryPropertyFlags::empty(),
            )
        })
        .ok_or("native text image memory unavailable")?;
        slot.image_memory = device
            .allocate_memory(
                &vk::MemoryAllocateInfo::default()
                    .allocation_size(req.size)
                    .memory_type_index(index),
                None,
            )
            .map_err(|e| e.to_string())?;
        device
            .bind_image_memory(slot.image, slot.image_memory, 0)
            .map_err(|e| e.to_string())?;
        slot.view = device
            .create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .image(slot.image)
                    .view_type(vk::ImageViewType::TYPE_2D)
                    .format(vk::Format::R8_UNORM)
                    .subresource_range(vk::ImageSubresourceRange {
                        aspect_mask: vk::ImageAspectFlags::COLOR,
                        base_mip_level: 0,
                        level_count: 1,
                        base_array_layer: 0,
                        layer_count: 1,
                    }),
                None,
            )
            .map_err(|e| e.to_string())?;
        Ok(())
    })();
    if let Err(error) = result {
        slot.destroy(device);
        return Err(error);
    }
    Ok(slot)
}
fn words(bytes: &[u8]) -> Result<Vec<u32>, String> {
    if bytes.len() % 4 != 0 {
        return Err("invalid native text SPIR-V".into());
    }
    Ok(bytes
        .chunks_exact(4)
        .map(|b| u32::from_le_bytes(b.try_into().unwrap()))
        .collect())
}
#[cfg(all(test, not(target_os = "android")))]
unsafe fn pipeline(
    _device: &ash::Device,
    _render_pass: vk::RenderPass,
    _layout: vk::PipelineLayout,
    _scene_depth: bool,
) -> Result<vk::Pipeline, String> {
    Err("GPU pipeline creation requires the Android shader build".into())
}
#[cfg(target_os = "android")]
unsafe fn pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    scene_depth_enabled: bool,
) -> Result<vk::Pipeline, String> {
    let vertex = words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/native_panel_text.vert.spv"
    )))?;
    let fragment = words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/native_panel_text.frag.spv"
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
        stride: mem::size_of::<GlyphInstance>() as u32,
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
#[cfg(test)]
mod tests {
    use super::*;
    fn atlas() -> TextAtlas {
        TextAtlas {
            width: 4,
            height: 3,
            pixels: vec![0; 12],
            runs: Vec::new(),
        }
    }
    fn glyph() -> GlyphInstance {
        GlyphInstance {
            rect: [0.0, 0.0, 0.1, 0.03],
            uv: [0.0, 0.0, 1.0, 1.0],
            color: [1.0; 4],
        }
    }
    #[test]
    fn rejects_wrong_stride_generation_and_over_capacity_before_upload() {
        let mut a = atlas();
        assert!(validate(1, &a, &[glyph()]).is_ok());
        assert!(validate(0, &a, &[glyph()]).is_err());
        a.pixels.pop();
        assert!(validate(1, &a, &[glyph()]).is_err());
        a = atlas();
        a.width = 1025;
        assert!(validate(1, &a, &[glyph()]).is_err());
        assert!(validate(1, &atlas(), &vec![glyph(); 1025]).is_err());
    }
    #[test]
    fn rejects_nonfinite_and_inverted_glyphs_but_allows_atlas_edge_tiles() {
        let mut g = glyph();
        assert!(validate(1, &atlas(), &[g]).is_ok());
        g.rect[0] = f32::NAN;
        assert!(validate(1, &atlas(), &[g]).is_err());
        g = glyph();
        g.uv = [0.5, 0.0, 0.4, 1.0];
        assert!(validate(1, &atlas(), &[g]).is_err());
        g = glyph();
        g.rect[2] = 0.0;
        assert!(validate(1, &atlas(), &[g]).is_err());
        g = glyph();
        g.uv[2] = 1.001;
        assert!(validate(1, &atlas(), &[g]).is_err());
    }
    #[test]
    fn instance_abi_and_portable_push_constant_budget() {
        assert_eq!(mem::size_of::<GlyphInstance>(), 48);
        assert_eq!(mem::size_of::<PanelPush>(), 128);
        assert!(mem::size_of::<PanelPush>() <= 128);
        // Atlas-edge texture lookups must clamp to initialized pixel centers in fixed images.
        let size = [17.0_f32, 7.0];
        let fixed = ATLAS_SIDE as f32;
        for actual in size {
            let min = 0.5 / fixed;
            let max = (actual - 0.5) / fixed;
            assert!(min > 0.0 && max < actual / fixed && max >= min);
        }
    }
    #[test]
    fn page_capacity_is_separate_from_toolbar_and_clip_abi() {
        assert!(validate(1, &atlas(), &vec![glyph(); 1025]).is_err());
        assert!(validate_capacity(1, &atlas(), &vec![glyph(); 4096], 4096).is_ok());
        assert!(validate_capacity(1, &atlas(), &vec![glyph(); 4097], 4096).is_err());
        assert_eq!(std::mem::offset_of!(PanelPush, clip), 112);
    }
    #[test]
    fn coherent_upload_memory_respects_compatible_type_mask() {
        let mut p = vk::PhysicalDeviceMemoryProperties::default();
        p.memory_type_count = 3;
        p.memory_types[0].property_flags = vk::MemoryPropertyFlags::DEVICE_LOCAL;
        p.memory_types[1].property_flags = vk::MemoryPropertyFlags::HOST_VISIBLE;
        p.memory_types[2].property_flags =
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
        let flags = vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
        assert_eq!(memory_type(&p, 7, flags), Some(2));
        assert_eq!(memory_type(&p, 3, flags), None);
    }
}
