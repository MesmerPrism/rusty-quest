pub(crate) struct FrameLeaseSlots<T> {
    slots: Vec<Option<T>>,
}

impl<T> Default for FrameLeaseSlots<T> {
    fn default() -> Self {
        Self { slots: Vec::new() }
    }
}

impl<T> FrameLeaseSlots<T> {
    pub(crate) fn retain(&mut self, frame_slot: usize, lease: T) {
        while self.slots.len() <= frame_slot {
            self.slots.push(None);
        }
        self.slots[frame_slot] = Some(lease);
    }

    pub(crate) fn retire_all(&mut self) {
        for slot in &mut self.slots {
            *slot = None;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::FrameLeaseSlots;
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };

    struct DropWitness(Arc<AtomicUsize>);

    impl Drop for DropWitness {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn submitted_lease_lives_until_frame_slot_retirement() {
        let drops = Arc::new(AtomicUsize::new(0));
        let lease = Arc::new(DropWitness(Arc::clone(&drops)));
        let mut slots = FrameLeaseSlots::default();
        slots.retain(2, Arc::clone(&lease));
        drop(lease);
        assert_eq!(drops.load(Ordering::SeqCst), 0);
        slots.retire_all();
        assert_eq!(drops.load(Ordering::SeqCst), 1);
    }
}
