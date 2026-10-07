use std::sync::{Arc, Mutex};

/// Checks the runtime out without holding its mutex across platform callbacks.
pub(super) struct Checkout<T> {
    slot: Arc<Mutex<Option<T>>>,
    value: Option<T>,
}

impl<T> Checkout<T> {
    pub(super) fn take(slot: Arc<Mutex<Option<T>>>) -> Result<Self, String> {
        let value = slot
            .lock()
            .map_err(|_| "runtime slot poisoned")?
            .take()
            .ok_or("runtime busy")?;
        Ok(Self {
            slot,
            value: Some(value),
        })
    }

    pub(super) fn get(&mut self) -> &mut T {
        self.value.as_mut().expect("checked-out runtime")
    }
}

impl<T> Drop for Checkout<T> {
    fn drop(&mut self) {
        if let Ok(mut slot) = self.slot.lock() {
            *slot = self.value.take();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn callback_reentry_is_busy_without_holding_the_slot_mutex() {
        let slot = Arc::new(Mutex::new(Some(7)));
        let mut active = Checkout::take(slot.clone()).unwrap();
        *active.get() = 9;
        let reentrant = std::thread::spawn({
            let slot = slot.clone();
            move || {
                assert!(
                    slot.try_lock().is_ok(),
                    "callback must not inherit a held owner mutex"
                );
                assert!(matches!(Checkout::take(slot), Err(reason) if reason == "runtime busy"));
            }
        });
        reentrant.join().unwrap();
        drop(active);
        assert_eq!(*Checkout::take(slot).unwrap().get(), 9);
    }

    #[test]
    fn failed_operation_preserves_mutated_cleanup_state() {
        let slot = Arc::new(Mutex::new(Some(vec!["live"])));
        let operation = || -> Result<(), String> {
            let mut active = Checkout::take(slot.clone())?;
            active.get().push("cleanup_pending");
            Err("lost platform response".into())
        };
        assert!(operation().is_err());
        assert_eq!(
            Checkout::take(slot).unwrap().get(),
            &["live", "cleanup_pending"]
        );
    }
}
