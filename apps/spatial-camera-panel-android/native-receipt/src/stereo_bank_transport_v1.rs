// PROSPECTIVE ONLY. One final-stack ABI; not existing wire/descriptor support.
pub(crate) const STEREO_BANK_ABI_VERSION: u32 = 1;
pub(crate) const GUIDE_SET: u32 = 1;
pub(crate) const GUIDE_BINDINGS: [u32; 5] = [4, 5, 6, 7, 8];
pub(crate) const ORIGIN_ARRAY_COUNT: u32 = 2;
pub(crate) const POLICY_SET: u32 = 5;
pub(crate) const POLICY_BINDING: u32 = 1; // new; observed binding0 is zone UBO

#[repr(u32)]
#[derive(Clone, Copy)]
pub(crate) enum Origin { Own = 0, Peer = 1 }
#[repr(u32)]
#[derive(Clone, Copy)]
pub(crate) enum GuideOrigin { Own = 0, Peer = 1, FollowRegion = 2 }

// Exact std140 counterpart: three uvec4, 48 bytes, 16-byte alignment.
#[repr(C, align(16))]
pub(crate) struct StereoBankPolicyUniformV1 {
    pub region_origins: [u32; 4], // Center, Middle, Outer, vertex geometry driver
    pub guide_origins: [u32; 4], // brightness, strength, reserved0, reserved0
    pub source_state: [u32; 4], // Own completed-prefix, Peer completed-prefix, ABI, reserved0
}

impl StereoBankPolicyUniformV1 {
    pub(crate) fn new(regions: [Origin; 4], brightness: GuideOrigin,
        strength: GuideOrigin, own_completed_prefix: u32, peer_completed_prefix: u32) -> Result<Self, String> {
        if own_completed_prefix > 6 || peer_completed_prefix > 6 {
            return Err("invalid completed guide prefix".into());
        }
        Ok(Self { region_origins: regions.map(|v| v as u32),
            guide_origins: [brightness as u32, strength as u32, 0, 0],
            source_state: [own_completed_prefix, peer_completed_prefix,
                STEREO_BANK_ABI_VERSION, 0] })
    }
}

// Physical-device limits are read before pipeline/resource activation. These
// conservative counts assume two ordinary camera samplers, dual five-guide
// bank, depth+video, RGB2 UBO, existing zone+policy UBO. YCbCr descriptor
// expansion and actual stage_flags/layout declarations must increase demand.
pub(crate) struct LayoutDemand {
    pub bound_sets: u32,
    pub samplers: u32,
    pub sampled_images: u32,
    pub uniform_buffers: u32,
    pub stage_resources: u32,
    pub push_bytes: u32,
    pub uniform_range_bytes: u32,
}
pub(crate) const ORDINARY_LAYOUT_DEMAND: LayoutDemand = LayoutDemand {
    bound_sets: 6, samplers: 14, sampled_images: 14,
    uniform_buffers: 4, stage_resources: 18, push_bytes: 128,
    // camera_hwb_projection_target.rs:575 statically asserts existing zone416.
    uniform_range_bytes: 416,
};

// Exact proposed Vulkan call shape. These layouts are NEW final-only layouts;
// existing scalar guide-pass bindings/descriptors remain untouched.
pub(crate) fn final_guide_bindings() -> [ash::vk::DescriptorSetLayoutBinding<'static>; 5] {
    GUIDE_BINDINGS.map(|binding| ash::vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(ash::vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(ORIGIN_ARRAY_COUNT)
        .stage_flags(ash::vk::ShaderStageFlags::VERTEX | ash::vk::ShaderStageFlags::FRAGMENT))
}
pub(crate) fn final_policy_bindings() -> [ash::vk::DescriptorSetLayoutBinding<'static>; 2] {
    [0, POLICY_BINDING].map(|binding| ash::vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(ash::vk::DescriptorType::UNIFORM_BUFFER)
        .descriptor_count(1)
        .stage_flags(ash::vk::ShaderStageFlags::VERTEX | ash::vk::ShaderStageFlags::FRAGMENT))
}

pub(crate) fn check_final_layout_limits(limits: &ash::vk::PhysicalDeviceLimits,
    demand: &LayoutDemand) -> Result<(), String> {
    if limits.max_bound_descriptor_sets < demand.bound_sets
        || limits.max_push_constants_size < demand.push_bytes
        || limits.max_per_stage_descriptor_samplers < demand.samplers
        || limits.max_per_stage_descriptor_sampled_images < demand.sampled_images
        || limits.max_per_stage_descriptor_uniform_buffers < demand.uniform_buffers
        || limits.max_per_stage_resources < demand.stage_resources
        || limits.max_descriptor_set_samplers < demand.samplers
        || limits.max_descriptor_set_sampled_images < demand.sampled_images
        || limits.max_descriptor_set_uniform_buffers < demand.uniform_buffers
        || limits.max_uniform_buffer_range < demand.uniform_range_bytes {
        return Err("stereo bank layout exceeds selected physical-device limits".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn policy_does_not_expand_push_and_has_exact_std140_size() {
        assert_eq!(std::mem::size_of::<StereoBankPolicyUniformV1>(), 48);
        assert_eq!(std::mem::align_of::<StereoBankPolicyUniformV1>(), 16);
        assert_eq!(ORDINARY_LAYOUT_DEMAND.push_bytes, 128);
        assert_eq!(GUIDE_BINDINGS, [4, 5, 6, 7, 8]);
    }
    #[test] fn origin_is_separate_from_raw_processed_and_independently_unavailable() {
        let policy = StereoBankPolicyUniformV1::new(
            [Origin::Own, Origin::Peer, Origin::Own, Origin::Peer],
            GuideOrigin::FollowRegion, GuideOrigin::Peer, 1, 0).unwrap();
        assert_eq!(policy.region_origins, [0, 1, 0, 1]);
        assert_eq!(policy.guide_origins, [2, 1, 0, 0]);
        assert_eq!(policy.source_state, [1, 0, 1, 0]);
    }
    #[test] fn invalid_prefix_and_insufficient_device_limits_reject() {
        assert!(StereoBankPolicyUniformV1::new([Origin::Own; 4],
            GuideOrigin::Own, GuideOrigin::Own, 7, 0).is_err());
        assert!(check_final_layout_limits(&ash::vk::PhysicalDeviceLimits::default(),
            &ORDINARY_LAYOUT_DEMAND).is_err());
    }
}
