//! Neutral, opt-in three-pass graph contract. Effect sources remain external.
pub(crate) const ATLAS: [u32; 2] = [3072, 1664];
pub(crate) const EYE: [u32; 2] = [1280, 1280];
pub(crate) const EYE_STRIDE: u32 = 1536;
pub(crate) const GUIDE: u32 = 384;

pub(crate) fn bundle_available() -> bool {
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_FULL3_VERIFIED") == Some("true")
}

/// Texel-center limits in the packed input; linear filtering cannot cross eyes.
pub(crate) fn input_bounds(eye: usize) -> Option<[f32; 4]> {
    if eye > 1 {
        return None;
    }
    let x = (eye as u32 * EYE[0]) as f32;
    Some([
        (x + 0.5) / 2560.,
        0.5 / 1280.,
        (x + 1279.5) / 2560.,
        1279.5 / 1280.,
    ])
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn atlas_has_disjoint_native_rasters_and_four_guides() {
        assert_eq!(ATLAS, [2 * EYE_STRIDE, EYE[1] + GUIDE]);
        assert_eq!(4 * GUIDE, EYE_STRIDE);
        let l = input_bounds(0).unwrap();
        let r = input_bounds(1).unwrap();
        assert!(l[2] < 0.5 && r[0] > 0.5);
        assert_eq!(input_bounds(2), None);
        for eye in 0..2u32 {
            for tile in 0..4u32 {
                let x = eye * EYE_STRIDE + tile * GUIDE;
                assert!(x + GUIDE <= (eye + 1) * EYE_STRIDE);
            }
        }
    }
}
