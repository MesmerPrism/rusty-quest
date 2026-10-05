//! Platform-neutral identity and invalidation policy for decoded-video imports.

use std::collections::HashSet;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub(crate) struct StableVideoImportIdentity {
    pub(crate) reader_generation: u64,
    pub(crate) hardware_buffer_id: u64,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) layers: u32,
    pub(crate) native_format: u32,
    pub(crate) usage: u64,
    pub(crate) stride: u32,
    pub(crate) vk_format_raw: i32,
    pub(crate) external_format: u64,
}

impl StableVideoImportIdentity {
    pub(crate) fn cacheable(self, hardware_buffer_id_status: i32) -> bool {
        self.reader_generation != 0
            && self.hardware_buffer_id != 0
            && hardware_buffer_id_status == 0
            && (self.vk_format_raw != 0 || self.external_format != 0)
    }
}

#[derive(Default)]
pub(crate) struct VideoImportInvalidationPolicy {
    removed: HashSet<(u64, u64)>,
    reuse_disabled: HashSet<u64>,
}

impl VideoImportInvalidationPolicy {
    pub(crate) fn observe(
        &mut self,
        reader_generation: u64,
        removed_hardware_buffer_ids: &[u64],
        buffer_reuse_disabled: bool,
    ) {
        if buffer_reuse_disabled {
            self.reuse_disabled.insert(reader_generation);
        }
        self.removed.extend(
            removed_hardware_buffer_ids
                .iter()
                .copied()
                .filter(|id| *id != 0)
                .map(|id| (reader_generation, id)),
        );
    }

    pub(crate) fn invalid(self: &Self, identity: StableVideoImportIdentity) -> bool {
        self.reuse_disabled.contains(&identity.reader_generation)
            || self
                .removed
                .contains(&(identity.reader_generation, identity.hardware_buffer_id))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn identity(reader: u64, id: u64) -> StableVideoImportIdentity {
        StableVideoImportIdentity {
            reader_generation: reader,
            hardware_buffer_id: id,
            width: 1920,
            height: 1080,
            layers: 1,
            native_format: 34,
            usage: 0x300,
            stride: 1920,
            vk_format_raw: 37,
            external_format: 0,
        }
    }

    #[test]
    fn identity_requires_real_stable_id_and_all_descriptor_fields_participate() {
        let base = identity(7, 9);
        assert!(base.cacheable(0));
        assert!(!StableVideoImportIdentity {
            hardware_buffer_id: 0,
            ..base
        }
        .cacheable(0));
        assert!(!base.cacheable(-1));
        assert_ne!(
            base,
            StableVideoImportIdentity {
                stride: 2048,
                ..base
            }
        );
        assert_ne!(
            base,
            StableVideoImportIdentity {
                reader_generation: 8,
                ..base
            }
        );
        assert_ne!(
            base,
            StableVideoImportIdentity {
                vk_format_raw: 44,
                ..base
            }
        );
    }

    #[test]
    fn removals_are_generation_scoped_and_cumulative() {
        let mut policy = VideoImportInvalidationPolicy::default();
        policy.observe(7, &[9], false);
        policy.observe(7, &[10], false);
        assert!(policy.invalid(identity(7, 9)));
        assert!(policy.invalid(identity(7, 10)));
        assert!(!policy.invalid(identity(8, 9)));
    }

    #[test]
    fn reuse_disable_is_permanent_for_one_reader_generation() {
        let mut policy = VideoImportInvalidationPolicy::default();
        policy.observe(7, &[], true);
        policy.observe(7, &[], false);
        assert!(policy.invalid(identity(7, 99)));
        assert!(!policy.invalid(identity(8, 99)));
    }
}
