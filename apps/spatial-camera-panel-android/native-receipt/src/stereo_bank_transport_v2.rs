//! Explicit final-only ABI2. The media/input-set ABI and original banks stay ABI1.
use super::stereo_bank_transport_v1::StereoBankPolicyUniformV1;
pub(crate) const GUIDE_BINDINGS: [u32; 4] = [4, 6, 7, 8];
pub(crate) const GUIDE_TARGETS: [usize; 4] = [0, 2, 3, 4];
pub(crate) const ORIGIN_ARRAY_COUNT: u32 = 3;
#[repr(C, align(16))]
pub(crate) struct StereoBankPolicyUniformV2 {
    pub region_origins: [u32; 4],
    pub guide_origins: [u32; 4],
    pub source_state: [u32; 4],
    pub mask_words: [u32; 4],
}
impl StereoBankPolicyUniformV2 {
    pub(crate) fn from_input(v: &StereoBankPolicyUniformV1, primary_prefix: u32) -> Self {
        Self {
            region_origins: v.region_origins,
            guide_origins: [v.guide_origins[0], v.guide_origins[1], 0, 0],
            source_state: [v.source_state[0], v.source_state[1], primary_prefix, 2],
            mask_words: [v.guide_origins[2], v.guide_origins[3], v.source_state[3], 0],
        }
    }
}
pub(crate) fn final_guide_bindings() -> [ash::vk::DescriptorSetLayoutBinding<'static>; 4] {
    GUIDE_BINDINGS.map(|binding| {
        ash::vk::DescriptorSetLayoutBinding::default()
            .binding(binding)
            .descriptor_type(ash::vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .descriptor_count(ORIGIN_ARRAY_COUNT)
            .stage_flags(ash::vk::ShaderStageFlags::VERTEX | ash::vk::ShaderStageFlags::FRAGMENT)
    })
}
/// A derived primary is closed unless both exact original raw inputs completed.
/// Guide completion is never inferred from raw availability or shader selection.
pub(crate) fn primary_required(mask: [u32; 3]) -> Result<bool, String> {
    crate::stereo_bank_mask_v1::validate(mask).map_err(str::to_owned)
}
pub(crate) fn primary_input_ready(mask: [u32; 3], completed: [u32; 2]) -> Result<bool, String> {
    Ok(primary_required(mask)? && completed == [6, 6])
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_abi_and_no_blur_temp_binding() {
        assert_eq!(std::mem::size_of::<StereoBankPolicyUniformV2>(), 64);
        assert_eq!(std::mem::align_of::<StereoBankPolicyUniformV2>(), 16);
        assert_eq!(GUIDE_TARGETS, [0, 2, 3, 4]);
        assert_eq!(
            final_guide_bindings().map(|b| (b.binding, b.descriptor_count)),
            [(4, 3), (6, 3), (7, 3), (8, 3)]
        );
    }
    #[test]
    fn absent_bank_never_becomes_primary_and_disabled_never_blends() {
        let mask = crate::stereo_bank_mask_v1::pack(true, 0.5, 0.05, 1.0, true, false).unwrap();
        assert!(!primary_input_ready(mask, [6, 0]).unwrap());
        assert!(!primary_input_ready(mask, [0, 6]).unwrap());
        assert!(primary_input_ready(mask, [6, 6]).unwrap());
        assert!(!primary_input_ready(mask, [1, 1]).unwrap());
        assert!(!primary_input_ready([0; 3], [6, 6]).unwrap());
        assert!(primary_input_ready([u32::MAX; 3], [6, 6]).is_err());
    }
}
