use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, LazyLock, Mutex,
};
#[derive(Clone)]
struct Host {
    capability: Arc<Capability>,
    peer_lifecycle: Arc<()>,
}
struct Capability {
    live: AtomicBool,
}
impl Capability {
    fn require_live(&self) -> Result<(), String> {
        if self.live.load(Ordering::SeqCst) {
            Ok(())
        } else {
            Err("Native counter/owner no longer live".into())
        }
    }
}
struct HostLease(Host);
impl std::ops::Deref for HostLease {
    type Target = Host;
    fn deref(&self) -> &Host {
        &self.0
    }
}
impl Drop for HostLease {
    fn drop(&mut self) {
        process().lock().unwrap().host_leases -= 1;
    }
}
struct Prepared(bool);
impl Prepared {
    fn permits_general_operation(&self) -> bool {
        self.0
    }
}
#[derive(Default)]
struct ProcessState {
    host: Option<Host>,
    closing: bool,
    initializing: bool,
    lease_integrity_failed: bool,
    root_reestablishment: Option<()>,
    root_terminal_custody: Option<()>,
    retired_fresh_owner_fenced: bool,
    prepared_peer_start: Option<Prepared>,
    fresh_setup_request: Option<()>,
    host_leases: usize,
}
static PROCESS: LazyLock<Mutex<ProcessState>> =
    LazyLock::new(|| Mutex::new(ProcessState::default()));
fn process() -> &'static Mutex<ProcessState> {
    &PROCESS
}
fn reset() -> Host {
    let h = Host {
        capability: Arc::new(Capability {
            live: AtomicBool::new(true),
        }),
        peer_lifecycle: Arc::new(()),
    };
    *process().lock().unwrap() = ProcessState {
        host: Some(h.clone()),
        ..Default::default()
    };
    h
}
/* ACTUAL_GATE_METHODS */
#[test]
fn stopped_unconsumed_prepared_owner_can_be_looked_up_but_general_effect_cannot_enter() {
    let h = reset();
    process().lock().unwrap().prepared_peer_start = Some(Prepared(false));
    let held = host_for_cleanup_lookup().unwrap();
    assert_eq!(process().lock().unwrap().host_leases, 1);
    assert!(require_general_host_admission(&h, false).is_err());
    assert!(require_general_host_admission(&h, true).is_err());
    drop(held);
    assert_eq!(process().lock().unwrap().host_leases, 0);
}
#[test]
fn fresh_pending_lookup_does_not_admit_start() {
    let h = reset();
    process().lock().unwrap().fresh_setup_request = Some(());
    let held = host_for_cleanup_lookup().unwrap();
    assert!(require_general_host_admission(&h, false).is_err());
    assert!(require_general_host_admission(&h, true).is_ok());
    drop(held);
}
#[test]
fn only_exact_current_root_can_admit_ordinary_effect() {
    let old = reset();
    let now = reset();
    assert!(require_general_host_admission(&old, false).is_err());
    assert!(require_general_host_admission(&now, false).is_ok());
    now.capability.live.store(false, Ordering::SeqCst);
    assert!(host_for_cleanup_lookup().is_err());
    assert!(require_general_host_admission(&now, false).is_err());
    assert_eq!(process().lock().unwrap().host_leases, 0);
}
#[test]
fn lookup_never_crosses_unsafe_root_transition_or_integrity_failure() {
    for kind in 0..5 {
        reset();
        {
            let mut p = process().lock().unwrap();
            match kind {
                0 => p.initializing = true,
                1 => p.lease_integrity_failed = true,
                2 => p.root_reestablishment = Some(()),
                3 => p.closing = true,
                _ => p.host = None,
            }
        }
        assert!(host_for_cleanup_lookup().is_err());
        assert_eq!(process().lock().unwrap().host_leases, 0);
    }
}
#[test]
fn actual_terminal_root_custody_retains_cleanup_lookup_without_general_admission() {
    let h = reset();
    {
        let mut p = process().lock().unwrap();
        p.closing = true;
        p.root_terminal_custody = Some(());
        p.retired_fresh_owner_fenced = true;
    }
    let held = host_for_cleanup_lookup().unwrap();
    assert!(require_general_host_admission(&h, false).is_err());
    drop(held);
}
