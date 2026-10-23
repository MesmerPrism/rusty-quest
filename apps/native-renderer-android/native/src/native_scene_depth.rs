//! Opt-in scene Z resources. These are app-owned attachments, not sensed
//! environment depth and not runtime projection-depth submission.
use ash::vk;

pub(crate) const NEAR_METERS: f32 = 0.05;
pub(crate) const FAR_METERS: f32 = 20.0;
pub(crate) const SPECIALIZATION_ID: u32 = 100;

/// Vulkan clip coordinates for OpenXR's -Z-forward eye space and a positive
/// height viewport. Keep W physical so clipping and interpolation remain valid
/// for triangles crossing the near plane.
pub(crate) fn eye_clip(eye: [f32; 3], tangents: [f32; 4]) -> [f32; 4] {
    let [left, right, down, up] = tangents;
    let forward = -eye[2];
    [
        (2.0 * eye[0] - (right + left) * forward) / (right - left),
        (-2.0 * eye[1] + (up + down) * forward) / (up - down),
        FAR_METERS / (FAR_METERS - NEAR_METERS) * forward
            - FAR_METERS * NEAR_METERS / (FAR_METERS - NEAR_METERS),
        forward,
    ]
}

#[derive(Clone, Copy)]
pub(crate) struct SceneDepthConfig {
    pub(crate) format: vk::Format,
    memory: vk::PhysicalDeviceMemoryProperties,
}

impl SceneDepthConfig {
    pub(crate) unsafe fn select(
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
        memory: vk::PhysicalDeviceMemoryProperties,
    ) -> Result<Self, String> {
        for format in [vk::Format::D32_SFLOAT, vk::Format::D16_UNORM] {
            let properties =
                instance.get_physical_device_format_properties(physical_device, format);
            if properties
                .optimal_tiling_features
                .contains(vk::FormatFeatureFlags::DEPTH_STENCIL_ATTACHMENT)
            {
                return Ok(Self { format, memory });
            }
        }
        Err("native scene requires a supported depth attachment format".into())
    }
}

pub(crate) struct SceneDepthAttachment {
    image: vk::Image,
    memory: vk::DeviceMemory,
    pub(crate) view: vk::ImageView,
}

impl SceneDepthAttachment {
    pub(crate) unsafe fn create(
        device: &ash::Device,
        config: SceneDepthConfig,
        extent: vk::Extent2D,
    ) -> Result<Self, String> {
        let image = device
            .create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(config.format)
                    .extent(vk::Extent3D {
                        width: extent.width,
                        height: extent.height,
                        depth: 1,
                    })
                    .mip_levels(1)
                    .array_layers(1)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(|error| format!("create scene depth image: {error}"))?;
        let requirements = device.get_image_memory_requirements(image);
        let memory_type = (0..config.memory.memory_type_count).find(|index| {
            requirements.memory_type_bits & (1 << index) != 0
                && config.memory.memory_types[*index as usize]
                    .property_flags
                    .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
        });
        let Some(memory_type) = memory_type else {
            device.destroy_image(image, None);
            return Err("no device-local scene depth memory type".into());
        };
        let memory = match device.allocate_memory(
            &vk::MemoryAllocateInfo::default()
                .allocation_size(requirements.size)
                .memory_type_index(memory_type),
            None,
        ) {
            Ok(memory) => memory,
            Err(error) => {
                device.destroy_image(image, None);
                return Err(format!("allocate scene depth memory: {error}"));
            }
        };
        if let Err(error) = device.bind_image_memory(image, memory, 0) {
            device.destroy_image(image, None);
            device.free_memory(memory, None);
            return Err(format!("bind scene depth memory: {error}"));
        }
        let view = match device.create_image_view(
            &vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(config.format)
                .subresource_range(vk::ImageSubresourceRange {
                    aspect_mask: vk::ImageAspectFlags::DEPTH,
                    base_mip_level: 0,
                    level_count: 1,
                    base_array_layer: 0,
                    layer_count: 1,
                }),
            None,
        ) {
            Ok(view) => view,
            Err(error) => {
                device.destroy_image(image, None);
                device.free_memory(memory, None);
                return Err(format!("create scene depth view: {error}"));
            }
        };
        Ok(Self {
            image,
            memory,
            view,
        })
    }

    /// Caller retires submitted work and destroys referencing framebuffers first.
    pub(crate) unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

pub(crate) fn attachment_index(fragment_density_map: bool) -> u32 {
    1 + u32::from(fragment_density_map)
}

pub(crate) fn clear_values(
    color: [f32; 4],
    fragment_density_map: bool,
    scene_depth: bool,
) -> Vec<vk::ClearValue> {
    let mut values = vec![vk::ClearValue {
        color: vk::ClearColorValue { float32: color },
    }];
    if scene_depth {
        if fragment_density_map {
            values.push(vk::ClearValue::default());
        }
        values.push(vk::ClearValue {
            depth_stencil: vk::ClearDepthStencilValue {
                depth: 1.0,
                stencil: 0,
            },
        });
    }
    values
}

#[cfg(test)]
mod tests {
    use super::*;
    fn close(a: f32, b: f32) {
        assert!((a - b).abs() < 0.00001, "{a} != {b}");
    }
    #[test]
    fn near_far_and_intermediate_depth_are_physical() {
        let fov = [-1.0, 1.0, -1.0, 1.0];
        let near = eye_clip([0.0, 0.0, -NEAR_METERS], fov);
        let far = eye_clip([0.0, 0.0, -FAR_METERS], fov);
        close(near[2] / near[3], 0.0);
        close(far[2] / far[3], 1.0);
        let a = eye_clip([0.0, 0.0, -0.3], fov);
        let b = eye_clip([0.0, 0.0, -3.0], fov);
        assert!(a[2] / a[3] < b[2] / b[3]);
        close(a[3], 0.3);
        close(b[3], 3.0);
    }
    #[test]
    fn asymmetric_frustum_edges_and_vulkan_y_are_preserved() {
        let fov = [-0.7, 1.1, -0.8, 0.9];
        let left = eye_clip([-1.4, 0.0, -2.0], fov);
        let right = eye_clip([2.2, 0.0, -2.0], fov);
        let down = eye_clip([0.0, -1.6, -2.0], fov);
        let up = eye_clip([0.0, 1.8, -2.0], fov);
        close(left[0] / left[3], -1.0);
        close(right[0] / right[3], 1.0);
        close(down[1] / down[3], 1.0);
        close(up[1] / up[3], -1.0);
    }
    #[test]
    fn homogeneous_near_clip_and_fdm_attachment_order_are_explicit() {
        let behind_near = eye_clip([0.0, 0.0, -0.01], [-1.0, 1.0, -1.0, 1.0]);
        assert!(behind_near[2] < 0.0 && behind_near[3] > 0.0);
        assert!(eye_clip([0.0, 0.0, 0.1], [-1.0, 1.0, -1.0, 1.0])[3] < 0.0);
        assert_eq!(attachment_index(false), 1);
        assert_eq!(attachment_index(true), 2);
    }

    #[test]
    fn clear_values_match_depth_slot_without_changing_legacy_fdm() {
        for fdm in [false, true] {
            assert_eq!(clear_values([0.0; 4], fdm, false).len(), 1);
            let values = clear_values([0.2, 0.3, 0.4, 1.0], fdm, true);
            let index = attachment_index(fdm) as usize;
            assert_eq!(values.len(), index + 1);
            unsafe {
                close(values[0].color.float32[0], 0.2);
                close(values[index].depth_stencil.depth, 1.0);
                assert_eq!(values[index].depth_stencil.stencil, 0);
            }
        }
    }

    #[test]
    fn deployed_world_shaders_share_oracle_planes_and_keep_legacy_default() {
        for shader in [
            include_str!("../shaders/hand_mesh_visual.vert.glsl"),
            include_str!("../shaders/hand_anchor_particles.vert.glsl"),
            include_str!("../shaders/private_particles.vert.glsl"),
        ] {
            assert!(shader
                .contains("layout(constant_id = 100) const bool SCENE_DEPTH_ENABLED = false;"));
            assert!(shader.contains(&format!("near_m = {NEAR_METERS}")));
            assert!(shader.contains(&format!("far_m = {FAR_METERS}.0")));
            assert!(shader.contains("return vec4(ndc_x, ndc_y, 0.0, 1.0);"));
            assert!(shader.contains("far_m / (far_m - near_m) * forward"));
        }
    }
}
