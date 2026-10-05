//! Explicit neutral mask carrier v1; private shaders own its signal formula.
//! Disabled is exactly [0;3]. Enabled: word0 bit31=v1, bit30=diagnostic strip,
//! low30=threshold IEEE754 bits; word1=softness bits; word2 bit31=invert,
//! low31=amount bits. Negative zero, nonfinite and unknown encodings reject.
pub(crate) const VERSION: u32 = 1;
pub(crate) const ENABLED: u32 = 1 << 31;
pub(crate) const DIAGNOSTIC: u32 = 1 << 30;
pub(crate) fn validate(words: [u32; 3]) -> Result<bool, &'static str> {
    if words == [0; 3] {
        return Ok(false);
    }
    if words[0] & ENABLED == 0 {
        return Err("mask-version-invalid");
    }
    let threshold = f32::from_bits(words[0] & 0x3fffffff);
    let softness = f32::from_bits(words[1]);
    let amount = f32::from_bits(words[2] & 0x7fffffff);
    if !threshold.is_finite()
        || !(0.0..=1.0).contains(&threshold)
        || !softness.is_finite()
        || !(0.001..=0.5).contains(&softness)
        || words[1] & ENABLED != 0
        || !amount.is_finite()
        || !(0.0..=1.0).contains(&amount)
    {
        return Err("mask-values-invalid");
    }
    Ok(true)
}
pub(crate) fn pack(
    enabled: bool,
    threshold: f32,
    softness: f32,
    amount: f32,
    invert: bool,
    diagnostic: bool,
) -> Result<[u32; 3], &'static str> {
    if !(0.0..=1.0).contains(&threshold)
        || !(0.001..=0.5).contains(&softness)
        || !(0.0..=1.0).contains(&amount)
        || [threshold, softness, amount]
            .iter()
            .any(|v| !v.is_finite() || v.is_sign_negative())
    {
        return Err("mask-values-invalid");
    }
    let words = [
        ENABLED | if diagnostic { DIAGNOSTIC } else { 0 } | threshold.to_bits(),
        softness.to_bits(),
        amount.to_bits() | if invert { ENABLED } else { 0 },
    ];
    validate(words)?;
    if !enabled && (threshold != 0.5 || softness != 0.1 || amount != 0.0 || invert || diagnostic) {
        return Err("disabled-mask-nondefault");
    }
    Ok(if enabled { words } else { [0; 3] })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn disabled_exact_zero_and_flags_roundtrip() {
        assert_eq!(pack(false, 0.5, 0.1, 0.0, false, false).unwrap(), [0; 3]);
        assert!(!validate([0; 3]).unwrap());
        let w = pack(true, 0.5, 0.1, 1.0, true, true).unwrap();
        assert_eq!(
            w,
            [
                0xc0000000 | 0.5f32.to_bits(),
                0.1f32.to_bits(),
                0x80000000 | 1f32.to_bits()
            ]
        );
        assert!(validate(w).unwrap());
    }
    #[test]
    fn nonfinite_negative_zero_unknown_version_and_bounds_deny() {
        for v in [f32::NAN, f32::INFINITY, -0.0, -1.0, 1.01] {
            assert!(pack(true, v, 0.1, 1.0, false, false).is_err());
        }
        for w in [
            [1, 0, 0],
            [ENABLED, 0, 0],
            [ENABLED | 0x3fffffff, 0.1f32.to_bits(), 0],
            [ENABLED, (-0.1f32).to_bits(), 0],
            [ENABLED, 0.1f32.to_bits(), 1.1f32.to_bits()],
        ] {
            assert!(validate(w).is_err());
        }
    }
}

/// Exact bounded draw geometry, consumed by the real Vulkan recorder.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct DiagnosticDrawPlan {
    pub extent: [u32; 2],
    pub scissor: [u32; 4],
    pub vertices: u32,
}
impl DiagnosticDrawPlan {
    pub(crate) fn for_frame(
        words: [u32; 3],
        completed: [u32; 2],
        extent: [u32; 2],
    ) -> Option<Self> {
        if validate(words) != Ok(true)
            || words[0] & DIAGNOSTIC == 0
            || completed != [6, 6]
            || extent[0] < 5
            || extent[1] < 1
        {
            return None;
        }
        Some(Self {
            extent,
            scissor: [0, 0, 5, 1],
            vertices: 3,
        })
    }
}
#[cfg(test)]
mod draw_tests {
    use super::*;
    #[test]
    fn actual_draw_plan_is_opted_five_pixels_only_and_partial_banks_deny() {
        let w = pack(true, 0.5, 0.1, 1.0, false, true).unwrap();
        let p = DiagnosticDrawPlan::for_frame(w, [6, 6], [640, 480]).unwrap();
        assert_eq!(p.scissor, [0, 0, 5, 1]);
        assert_eq!(p.extent, [640, 480]);
        assert_eq!(p.vertices, 3);
        for c in [[6, 0], [0, 6], [6, 3]] {
            assert!(DiagnosticDrawPlan::for_frame(w, c, [640, 480]).is_none());
        }
        assert!(DiagnosticDrawPlan::for_frame([0; 3], [6, 6], [640, 480]).is_none());
        assert!(DiagnosticDrawPlan::for_frame(
            pack(true, 0.5, 0.1, 1.0, false, false).unwrap(),
            [6, 6],
            [640, 480]
        )
        .is_none());
        assert!(DiagnosticDrawPlan::for_frame(w, [6, 6], [4, 480]).is_none());
    }
}
