//! Compatibility of GPU normalization resources, never identity of frame content.
//! Each renderer owns exactly two slots. Fresh imports and retained content are
//! still replaced on every refresh after the common submission fence retires.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct NormalizerCompatibility {
    pub epoch: [u64; 2],
    pub producer_generations: [u64; 3],
    pub control_revision: u64,
    pub processing_policy: [u32; 4],
    pub descriptor: [u64; 6],
    pub format: [u64; 2],
    pub conversion: [i32; 9],
    pub bottom_up: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RefreshDisposition {
    Pending,
    Clear,
    Replace,
    Reuse,
}

pub(crate) fn refresh_disposition(
    retired: bool,
    previous: Option<&NormalizerCompatibility>,
    incoming: Option<&NormalizerCompatibility>,
) -> RefreshDisposition {
    if !retired {
        return RefreshDisposition::Pending;
    }
    match incoming {
        None => RefreshDisposition::Clear,
        Some(key) if previous == Some(key) => RefreshDisposition::Reuse,
        Some(_) => RefreshDisposition::Replace,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn key() -> NormalizerCompatibility {
        NormalizerCompatibility {
            epoch: [1, 2],
            producer_generations: [3, 4, 5],
            control_revision: 6,
            processing_policy: [0, 0, 0, 1],
            descriptor: [1920, 1080, 1, 34, 0x300, 1920],
            format: [37, 0],
            conversion: [0, 1, 2, 3, 4, 5, 6, 7, 8],
            bottom_up: true,
        }
    }
    #[test]
    fn fresh_content_with_same_resource_contract_reuses_resources() {
        // PTS, pair sequence, buffer address and slot serial deliberately do not
        // participate: reuse never supplies or certifies the next frame content.
        assert_eq!(
            refresh_disposition(true, Some(&key()), Some(&key())),
            RefreshDisposition::Reuse
        );
    }
    #[test]
    fn every_descriptor_generation_conversion_and_orientation_change_replaces() {
        let baseline = key();
        let mut variants = Vec::new();
        for i in 0..2 {
            let mut k = baseline;
            k.epoch[i] += 1;
            variants.push(k);
        }
        for i in 0..3 {
            let mut k = baseline;
            k.producer_generations[i] += 1;
            variants.push(k);
        }
        for i in 0..4 {
            let mut k = baseline;
            k.processing_policy[i] += 1;
            variants.push(k);
        }
        for i in 0..6 {
            let mut k = baseline;
            k.descriptor[i] += 1;
            variants.push(k);
        }
        for i in 0..2 {
            let mut k = baseline;
            k.format[i] += 1;
            variants.push(k);
        }
        for i in 0..9 {
            let mut k = baseline;
            k.conversion[i] += 1;
            variants.push(k);
        }
        let mut k = baseline;
        k.control_revision += 1;
        variants.push(k);
        let mut k = baseline;
        k.bottom_up = false;
        variants.push(k);
        for k in variants {
            assert_eq!(
                refresh_disposition(true, Some(&baseline), Some(&k)),
                RefreshDisposition::Replace
            );
        }
    }
    #[test]
    fn pending_fence_prevents_reuse_replacement_and_expiry_clear() {
        let mut other = key();
        other.epoch[1] += 1;
        for incoming in [None, Some(key()), Some(other)] {
            assert_eq!(
                refresh_disposition(false, Some(&key()), incoming.as_ref()),
                RefreshDisposition::Pending
            );
        }
    }
    #[test]
    fn absent_or_expired_source_clears_without_output_fallback() {
        assert_eq!(
            refresh_disposition(true, Some(&key()), None),
            RefreshDisposition::Clear
        );
        assert_eq!(
            refresh_disposition(true, None, Some(&key())),
            RefreshDisposition::Replace
        );
    }
}
