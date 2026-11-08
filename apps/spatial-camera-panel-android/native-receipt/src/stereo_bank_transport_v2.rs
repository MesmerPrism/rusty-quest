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
pub(crate) const DISPLAY_BINDING: u32 = 9;
pub(crate) const CAMERA_COLOR_BINDING: u32 = 10;

pub(crate) fn final_camera_color_binding() -> ash::vk::DescriptorSetLayoutBinding<'static> {
    ash::vk::DescriptorSetLayoutBinding::default()
        .binding(CAMERA_COLOR_BINDING)
        .descriptor_type(ash::vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(ash::vk::ShaderStageFlags::FRAGMENT)
}

pub(crate) fn final_display_binding() -> ash::vk::DescriptorSetLayoutBinding<'static> {
    ash::vk::DescriptorSetLayoutBinding::default()
        .binding(DISPLAY_BINDING)
        .descriptor_type(ash::vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(ORIGIN_ARRAY_COUNT)
        .stage_flags(ash::vk::ShaderStageFlags::FRAGMENT)
}

/// A source route is selectable only when it can reach the normal displayed
/// region or an exact already-recorded Primary. Diagnostics always use raw.
pub(crate) fn source_color_origin(
    placement: i32,
    mask: [u32; 3],
    completed: [u32; 2],
    primary_prefix: u32,
    region_origins: [u32; 4],
) -> Option<usize> {
    let origin = match placement {
        1 => 0,
        2 => 1,
        _ => return None,
    };
    if completed[origin] == 0 || mask[0] & 0x4000_0000 != 0 {
        return None;
    }
    let required = primary_required(mask).ok()?;
    if required {
        (primary_prefix == 6 && completed == [6, 6]).then_some(origin)
    } else {
        region_origins[..3]
            .contains(&(origin as u32))
            .then_some(origin)
    }
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
    fn optional_display_selection_requires_exact_completed_visible_input() {
        let display = final_display_binding();
        assert_eq!((display.binding, display.descriptor_count), (9, 3));
        let own = [0, 0, 0, 1];
        assert_eq!(source_color_origin(1, [0; 3], [1, 0], 0, own), Some(0));
        assert_eq!(source_color_origin(2, [0; 3], [1, 6], 0, own), None);
        assert_eq!(source_color_origin(1, [0; 3], [0, 6], 0, own), None);
        assert_eq!(source_color_origin(3, [0; 3], [6, 6], 6, own), None);
        let mask = crate::stereo_bank_mask_v1::pack(true, 0.5, 0.05, 1.0, true, false).unwrap();
        assert_eq!(source_color_origin(2, mask, [6, 6], 6, own), Some(1));
        assert_eq!(source_color_origin(2, mask, [6, 6], 0, own), None);
        let diagnostic =
            crate::stereo_bank_mask_v1::pack(true, 0.5, 0.05, 1.0, true, true).unwrap();
        assert_eq!(source_color_origin(2, diagnostic, [6, 6], 6, own), None);
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
