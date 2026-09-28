// PROSPECTIVE bookkeeping core. L is the actual retained AHB allocation handle.
// No physical observation is invented here; the producer registry creates a
// record only after its actual owned producer fence is observed complete.
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ContentVersion {
    pub process_generation: u64,
    pub source_generation: u64,
    pub pool_generation: u64,
    pub slot_serial: u64,
}
pub(crate) struct PackedContents<L, M> {
    pub version: ContentVersion,
    pub allocation: L,
    pub pair: M, // immutable accepted pair identity, exact ns PTS
}

// This owner reference remains until the registry recycles the slot. AHB import
// caches may retain L independently; only content refs pin the current bytes.
pub(crate) struct ContentOwner<L, M> { record: Arc<PackedContents<L, M>> }
// Opaque retained wrapper exposes contents, never Arc or Weak. This prevents
// resurrection racing with a registry's exclusive reusable-content check.
pub(crate) struct PinnedPackedLease<L, M> { record: Arc<PackedContents<L, M>> }
impl<L, M> Clone for PinnedPackedLease<L, M> {
    fn clone(&self) -> Self { Self { record: Arc::clone(&self.record) } }
}
impl<L, M> PinnedPackedLease<L, M> {
    pub(crate) fn contents(&self) -> &PackedContents<L, M> { &self.record }
}
impl<L, M> ContentOwner<L, M> {
    pub(crate) fn from_observed_producer(record: PackedContents<L, M>) -> Self {
        Self { record: Arc::new(record) }
    }
    // Consumer adapter must require accepted exact epoch/quota before cloning.
    pub(crate) fn retain(&mut self) -> PinnedPackedLease<L, M> {
        PinnedPackedLease { record: Arc::clone(&self.record) }
    }
    pub(crate) fn unreferenced(&mut self) -> bool {
        // Exclusive owner access covers new retain. Existing leases can clone
        // only while at least one external strong ref already prevents reuse.
        // No Weak creation/Arc escape is exposed through the wrapper.
        Arc::strong_count(&self.record) == 1 && Arc::weak_count(&self.record) == 0
    }
}

// This is retained in the owning executor's fixed-capacity pending-use table,
// never solely in a render worker stack. Worker unwind does not delete table.
// A complete physical fence/ownership release removes that exact table entry;
// no Drop implementation converts a CPU release into GPU completion.
pub(crate) struct PendingGpuUse<L, M, F> {
    pub contents: PinnedPackedLease<L, M>,
    pub physical_fence: F,
    pub submission_serial: u64,
}

#[cfg(test)]
mod tests {
    use super::*;
    fn owner() -> ContentOwner<(), u64> {
        ContentOwner::from_observed_producer(PackedContents {
            version: ContentVersion { process_generation: 1, source_generation: 1,
                pool_generation: 1, slot_serial: 1 }, allocation: (), pair: 12 })
    }
    #[test] fn gpu_table_pins_contents_after_worker_reference_drops() {
        let mut owner = owner();
        let worker = owner.retain();
        let pending = PendingGpuUse { contents: owner.retain(), physical_fence: (), submission_serial: 1 };
        drop(worker);
        assert!(!owner.unreferenced());
        // Test means removal of mock table entry, NOT observed GPU completion.
        drop(pending);
        assert!(owner.unreferenced());
    }
    #[test] fn every_retained_alias_keeps_content_pinned_until_last_release() {
        let mut owner = owner();
        let frame = owner.retain();
        let retained_alias = frame.clone();
        drop(frame);
        assert!(!owner.unreferenced());
        assert_eq!(retained_alias.contents().pair, 12);
        drop(retained_alias);
        assert!(owner.unreferenced());
    }
}
