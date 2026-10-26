//! Cleanup transaction admission. A live writer is not durable uncertainty.
use std::sync::{Condvar, Mutex};
use std::thread::{self, ThreadId};

#[derive(Default)]
pub(super) struct Admission {
    owner: Mutex<Option<ThreadId>>,
    joined: Condvar,
}
pub(super) struct Lease<'a>(&'a Admission);
impl Admission {
    // Wait only for another thread's existing transaction. The mutex is released
    // by Condvar::wait and before the returned lease crosses any Java callback.
    // Same-thread callback reentry must fail instead of waiting for itself.
    pub(super) fn enter(&self) -> Result<Lease<'_>, String> {
        let current = thread::current().id();
        let mut owner = self
            .owner
            .lock()
            .map_err(|_| "cleanup admission poisoned")?;
        loop {
            match *owner {
                None => {
                    *owner = Some(current);
                    return Ok(Lease(self));
                }
                Some(active) if active == current => return Err("runtime busy".into()),
                Some(_) => {
                    owner = self
                        .joined
                        .wait(owner)
                        .map_err(|_| "cleanup admission poisoned")?
                }
            }
        }
    }
    // Eligibility/snapshots remain nonwaiting and retain no authority while busy.
    pub(super) fn try_enter(&self) -> Result<Option<Lease<'_>>, String> {
        let mut owner = self
            .owner
            .try_lock()
            .map_err(|_| "cleanup admission busy")?;
        if owner.is_some() {
            return Ok(None);
        }
        *owner = Some(thread::current().id());
        Ok(Some(Lease(self)))
    }
}
impl Drop for Lease<'_> {
    fn drop(&mut self) {
        if let Ok(mut owner) = self.0.owner.lock() {
            *owner = None;
            self.0.joined.notify_all();
        }
    }
}
