//! Actual extracted Native Local/finish/ticket bodies; modeled registry, Host close and GPU facts.
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
static RETIRE_FAULT: AtomicUsize = AtomicUsize::new(0);
#[derive(Clone)]
struct AppBinding {
    generation: u64,
    record_sha256: String,
}
struct NativeCapability {
    generation: u64,
    binding: AppBinding,
    retired: AtomicBool,
    fail: AtomicBool,
}
impl NativeCapability {
    fn require_live(&self) -> Result<(), String> {
        if self.retired.load(Ordering::SeqCst) {
            Err("capability retired".into())
        } else {
            Ok(())
        }
    }
    fn retire(&self) -> Result<(), String> {
        self.require_live()?;
        if self.fail.load(Ordering::SeqCst) {
            return Err("retire failed".into());
        }
        self.retired.store(true, Ordering::SeqCst);
        match RETIRE_FAULT.load(Ordering::SeqCst) {
            0 => {}
            1 => process().lock().unwrap().initializing = true,
            2 => process().lock().unwrap().host = Some(()),
            3 => *prepared_route().lock().unwrap() = Some(()),
            4 => {
                process()
                    .lock()
                    .unwrap()
                    .last_closed_runtime_configuration_binding = Some(json!({}))
            }
            5 => process().lock().unwrap().prepared_peer_start = Some(()),
            6 => process().lock().unwrap().root_reestablishment = Some(()),
            7 => process().lock().unwrap().fresh_setup_request = Some("pending".into()),
            8 => process().lock().unwrap().host_leases = 1,
            9 => process().lock().unwrap().lease_integrity_failed = true,
            _ => process().lock().unwrap().partial_construction = Some(()),
        };
        Ok(())
    }
}
fn cap() -> Arc<NativeCapability> {
    static C: OnceLock<Arc<NativeCapability>> = OnceLock::new();
    C.get_or_init(|| {
        Arc::new(NativeCapability {
            generation: 7,
            binding: AppBinding {
                generation: 2,
                record_sha256: "b".repeat(64),
            },
            retired: AtomicBool::new(false),
            fail: AtomicBool::new(false),
        })
    })
    .clone()
}
mod native_fence_jni {
    use super::*;
    pub fn active() -> Result<Arc<NativeCapability>, String> {
        Ok(cap())
    }
    pub fn ownership_status() -> Result<Value, String> {
        let c = cap();
        Ok(
            json!({"state":if c.retired.load(Ordering::SeqCst){"retired"}else{"admitted"},"app_process_generation":c.binding.generation,"app_record_sha256":c.binding.record_sha256,"native_executor_generation":c.generation}),
        )
    }
}
mod partial_construction {
    use super::*;
    pub fn current() -> Result<Option<()>, String> {
        Ok(None)
    }
    pub fn begin_if_never_invoked(_: Arc<NativeCapability>, _: &str) -> Result<(), String> {
        Ok(())
    }
    pub fn retire(_: &str, _: &Arc<NativeCapability>) -> Result<String, String> {
        Err("partial not selected".into())
    }
}
/* ACTUAL_TICKET */
#[derive(Default)]
struct ProcessState {
    initializing: bool,
    closing: bool,
    host_leases: usize,
    lease_integrity_failed: bool,
    host: Option<()>,
    fresh_setup_request: Option<String>,
    root_reestablishment: Option<()>,
    prepared_peer_start: Option<()>,
    partial_construction: Option<()>,
    last_closed_sha256: Option<String>,
    last_closed_runtime_configuration_binding: Option<Value>,
    completed_no_media_local: Option<CompletedNoMediaLocal>,
}
fn process() -> &'static Mutex<ProcessState> {
    static P: OnceLock<Mutex<ProcessState>> = OnceLock::new();
    P.get_or_init(|| Mutex::new(ProcessState::default()))
}
fn prepared_route() -> &'static Mutex<Option<()>> {
    static P: OnceLock<Mutex<Option<()>>> = OnceLock::new();
    P.get_or_init(|| Mutex::new(None))
}
fn safe_decode<E: std::fmt::Debug>(_: E) -> String {
    "decode".into()
}
/* ACTUAL_CONSTRUCTOR_REQUIRE */
fn close_no_media_runtime_with_capability(
    s: &str,
    _: &Arc<NativeCapability>,
) -> Result<String, String> {
    let mut p = process().lock().unwrap();
    p.host = None;
    p.host_leases = 0;
    p.closing = false;
    p.last_closed_sha256 = Some(s.into());
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.no_media_closed.v1","config_sha256":s,"disposition":"host_closed"}).to_string())
}
#[path = "ACTUAL_POLICY_PATH"]
mod local_rollback_policy;
mod own_stereo_capture_runtime {
    use super::*;
    static F: Mutex<[bool; 4]> = Mutex::new([true; 4]);
    pub fn set(f: [bool; 4]) {
        *F.lock().unwrap() = f
    }
    pub fn select_local_after_terminal(host_closed: bool) -> Result<(), String> {
        let f = *F.lock().unwrap();
        if local_rollback_policy::allow_local(local_rollback_policy::CleanupFacts {
            host_closed,
            own_unclaimed: f[0],
            epoch_retired: f[1],
            renderer_terminal: f[2],
            banks_inactive: f[3],
        }) {
            Ok(())
        } else {
            Err("Local rollback physical cleanup remains Pending".into())
        }
    }
}
/* ACTUAL_RETAIN_TICKET */
/* ACTUAL_FINISH */
/* ACTUAL_LOCAL */
fn reset() {
    RETIRE_FAULT.store(0, Ordering::SeqCst);
    *process().lock().unwrap() = ProcessState {
        host: Some(()),
        last_closed_runtime_configuration_binding: Some(
            json!({"$schema":"rusty.quest.embedded_duplex.runtime_configuration_binding.v1","config_sha256":"a".repeat(64),"app_process_generation":2,"app_record_sha256":"b".repeat(64),"native_executor_generation":7,"local_peer_id":"peer.q","fresh_setup_build_policy":{"$schema":"rusty.quest.embedded_duplex.fresh_setup_build_policy.v1","selection":"source-bound-v2","policy":{},"policy_sha256":"sha256:model","enrollment_selection_sha256":"sha256:model"}}),
        ),
        ..Default::default()
    };
    *prepared_route().lock().unwrap() = None;
    cap().retired.store(false, Ordering::SeqCst);
    cap().fail.store(false, Ordering::SeqCst);
    own_stereo_capture_runtime::set([true; 4]);
}
fn close() -> Result<String, String> {
    finish_no_media_retirement(&"a".repeat(64))
}
fn local() -> Result<String, String> {
    select_local_after_terminal(&"a".repeat(64))
}
fn main() {
    reset();
    close().unwrap();
    assert!(process().lock().unwrap().closing);
    assert!(cap().retired.load(Ordering::SeqCst));
    if std::env::args().nth(1).as_deref() == Some("red") {
        assert_eq!(
            local().unwrap_err(),
            "Local rollback physical cleanup remains Pending"
        );
        println!("EXPECTED_RED actual retired no-media Native Local denied");
        return;
    }
    assert!(local().is_ok());
    assert!(process().lock().unwrap().closing);
    let mut checks = 2;
    for i in 0..12 {
        reset();
        close().unwrap();
        match i {
            0 => process().lock().unwrap().completed_no_media_local = None,
            1 => process().lock().unwrap().initializing = true,
            2 => process().lock().unwrap().lease_integrity_failed = true,
            3 => process().lock().unwrap().host = Some(()),
            4 => process().lock().unwrap().host_leases = 1,
            5 => process().lock().unwrap().last_closed_sha256 = Some("c".repeat(64)),
            6 => cap().retired.store(false, Ordering::SeqCst),
            7 => {
                process()
                    .lock()
                    .unwrap()
                    .completed_no_media_local
                    .as_mut()
                    .unwrap()
                    .native_generation = 8
            }
            8 => {
                process()
                    .lock()
                    .unwrap()
                    .completed_no_media_local
                    .as_mut()
                    .unwrap()
                    .app_generation = 3
            }
            9 => {
                process()
                    .lock()
                    .unwrap()
                    .completed_no_media_local
                    .as_mut()
                    .unwrap()
                    .app_record_sha256 = "c".repeat(64)
            }
            10 => {
                process()
                    .lock()
                    .unwrap()
                    .last_closed_runtime_configuration_binding = Some(json!({}))
            }
            _ => process().lock().unwrap().fresh_setup_request = Some("pending".into()),
        };
        assert!(local().is_err(), "gate {i}");
        checks += 1;
    }
    for i in 0..4 {
        reset();
        close().unwrap();
        let mut f = [true; 4];
        f[i] = false;
        own_stereo_capture_runtime::set(f);
        assert!(local().is_err(), "physical {i}");
        checks += 1;
    }
    reset();
    cap().fail.store(true, Ordering::SeqCst);
    assert!(close().is_err());
    assert!(process().lock().unwrap().completed_no_media_local.is_none());
    assert!(local().is_err());
    checks += 3;
    for fault in 1..=10 {
        reset();
        RETIRE_FAULT.store(fault, Ordering::SeqCst);
        assert!(close().is_err(), "post-retire final gate {fault}");
        assert!(cap().retired.load(Ordering::SeqCst));
        assert!(process().lock().unwrap().completed_no_media_local.is_none());
        assert!(local().is_err());
        checks += 4;
    }
    reset();
    process()
        .lock()
        .unwrap()
        .last_closed_runtime_configuration_binding
        .as_mut()
        .unwrap()["config_sha256"] = json!("c".repeat(64));
    assert!(close().is_err());
    assert!(!cap().retired.load(Ordering::SeqCst));
    checks += 2;
    reset();
    close().unwrap();
    assert!(select_local_after_terminal(&"c".repeat(64)).is_err());
    checks += 1;
    reset();
    {
        let mut p = process().lock().unwrap();
        p.host = None;
        p.last_closed_sha256 = Some("a".repeat(64));
        p.closing = false;
    }
    assert!(local().is_ok());
    checks += 1;
    println!("PASS actual Native no-media finalization/Local/normal/physical controls={checks}");
}
