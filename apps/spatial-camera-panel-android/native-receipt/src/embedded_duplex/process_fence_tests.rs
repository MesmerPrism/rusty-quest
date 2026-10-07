use super::*;
use std::io::BufRead;
use std::os::unix::fs::PermissionsExt;
use std::process::{Command, Stdio};
use std::sync::atomic::AtomicUsize;
static NEXT: AtomicUsize = AtomicUsize::new(1);
struct Source(Mutex<String>);
impl AppFenceSource for Source {
    fn record(&self) -> Result<String, String> {
        self.0
            .lock()
            .map(|v| v.clone())
            .map_err(|_| "source poisoned".into())
    }
}
fn record(generation: u64, pending: &str) -> String {
    let body = format!(
        "{APP_SCHEMA}\n{generation}\n01234567-89ab-cdef-0123-456789abcdef\n{pending}\n-\n-\n"
    );
    format!("{body}{}\n", digest(&body))
}
fn source(generation: u64) -> Arc<Source> {
    Arc::new(Source(Mutex::new(record(generation, "pending"))))
}
fn directory() -> PathBuf {
    let path = std::env::temp_dir().join(format!(
        "rq-native-fence-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::SeqCst)
    ));
    fs::create_dir(&path).unwrap();
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    path
}
#[test]
fn native_counter_and_retired_callback_never_rebind_to_successor() {
    let path = directory();
    let source = source(1);
    let registry = NativeFenceRegistry::default();
    let first = registry.claim(&path, source.clone()).unwrap();
    assert_eq!(first.generation, 1);
    first.require_live().unwrap();
    assert!(registry.claim(&path, source.clone()).is_err());
    first.retire().unwrap();
    let successor = registry.claim(&path, source.clone()).unwrap();
    assert_eq!(successor.generation, 2);
    assert!(first.require_live().is_err());
    successor.require_live().unwrap();
    successor.retire().unwrap();
    drop(first);
    drop(successor);
    drop(registry);
    let restarted = NativeFenceRegistry::default().claim(&path, source).unwrap();
    assert_eq!(restarted.generation, 3);
}
#[test]
fn independent_native_writers_are_rejected_even_in_same_process() {
    let path = directory();
    let source = source(1);
    let registry = NativeFenceRegistry::default();
    let held = registry.claim(&path, source.clone()).unwrap();
    assert!(NativeFenceRegistry::default()
        .claim(&path, source.clone())
        .is_err());
    held.require_live().unwrap(); // Closing competitor's flock fd did not release ours.
}
#[test]
fn callback_postcheck_poisoned_by_changed_app_binding_is_not_restored_by_reverting_bytes() {
    let path = directory();
    let source = source(1);
    let cap = NativeFenceRegistry::default()
        .claim(&path, source.clone())
        .unwrap();
    cap.require_live().unwrap(); // Before hypothetical external callback.
    *source.0.lock().unwrap() = record(2, "pending");
    assert!(cap.require_live().is_err()); // After hypothetical external callback.
    *source.0.lock().unwrap() = record(1, "pending");
    assert!(cap.require_live().is_err());
    assert!(NativeFenceRegistry::default().claim(&path, source).is_err());
}
#[test]
fn corrupt_missing_or_replaced_native_state_fails_closed() {
    for action in 0..3 {
        let path = directory();
        let source = source(1);
        let registry = NativeFenceRegistry::default();
        let cap = registry.claim(&path, source.clone()).unwrap();
        let lock = path.join(LOCK_NAME);
        match action {
            0 => fs::write(&lock, "damaged").unwrap(),
            1 => {
                fs::remove_file(&lock).unwrap();
            }
            _ => {
                fs::remove_file(path.join(WITNESS_NAME)).unwrap();
            }
        }
        assert!(cap.require_live().is_err());
        assert!(registry.active().is_err());
        drop(cap);
        drop(registry);
        assert!(NativeFenceRegistry::default().claim(&path, source).is_err());
    }
}
#[test]
fn app_clear_corrupt_and_invalid_generation_are_rejected_before_admission() {
    for value in [
        record(1, "clear"),
        record(0, "pending"),
        record(u64::MAX, "pending"),
        "damaged".into(),
    ] {
        let path = directory();
        let source = Arc::new(Source(Mutex::new(value)));
        assert!(NativeFenceRegistry::default().claim(&path, source).is_err());
        assert!(!path.join(LOCK_NAME).exists());
    }
}
#[test]
fn active_native_counter_tampering_poisoned_by_journal_mismatch() {
    let path = directory();
    let registry = NativeFenceRegistry::default();
    let cap = registry.claim(&path, source(1)).unwrap();
    let body = format!("{NATIVE_SCHEMA}\n{}\n", i64::MAX);
    let record = format!("{body}{}\n", digest(&body));
    fs::write(path.join(LOCK_NAME), record).unwrap();
    assert!(cap.require_live().is_err());
}
#[test]
fn released_native_counter_exhaustion_never_overwrites_or_wraps() {
    let path = directory();
    let app_source = source(1);
    let registry = NativeFenceRegistry::default();
    let capability = registry.claim(&path, app_source.clone()).unwrap();
    capability.retire().unwrap();
    drop(capability);
    drop(registry);

    // Keep the genuine initialized witness and private inode, but seed the last
    // valid incarnation after its descriptor/lease has been released.
    let body = format!("{NATIVE_SCHEMA}\n{}\n", i64::MAX);
    let record = format!("{body}{}\n", digest(&body));
    let state_path = path.join(LOCK_NAME);
    fs::write(&state_path, &record).unwrap();
    fs::set_permissions(&state_path, fs::Permissions::from_mode(0o600)).unwrap();
    let witness_before = fs::read(path.join(WITNESS_NAME)).unwrap();
    let successor_registry = NativeFenceRegistry::default();
    let failure = successor_registry.claim(&path, app_source).err().unwrap();
    assert_eq!(failure, "native executor generation exhausted");
    assert_eq!(fs::read(&state_path).unwrap(), record.as_bytes());
    assert_eq!(fs::read(path.join(WITNESS_NAME)).unwrap(), witness_before);
    assert!(successor_registry.active().is_err());
}
#[test]
fn child_hold_native_lease() {
    let Some(path) = std::env::var_os("RQ_NATIVE_FENCE_CHILD") else {
        return;
    };
    let cap = NativeFenceRegistry::default()
        .claim(Path::new(&path), source(1))
        .unwrap();
    cap.require_live().unwrap();
    println!("RQ_NATIVE_HELD");
    std::io::stdout().flush().unwrap();
    let mut byte = [0];
    let _ = std::io::stdin().read(&mut byte);
}
fn child(path: &Path) -> std::process::Child {
    let mut process = Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "process_fence::tests::child_hold_native_lease",
            "--nocapture",
        ])
        .env("RQ_NATIVE_FENCE_CHILD", path)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .unwrap();
    let stdout = process.stdout.take().unwrap();
    let mut reader = std::io::BufReader::new(stdout);
    let mut line = String::new();
    loop {
        line.clear();
        assert!(
            reader.read_line(&mut line).unwrap() > 0,
            "native child failed before ready"
        );
        if line.contains("RQ_NATIVE_HELD") {
            break;
        }
    }
    process.stdout = Some(reader.into_inner());
    process
}
#[test]
fn actual_process_exclusion_graceful_release_and_abrupt_death_reacquire() {
    let path = directory();
    let mut held = child(&path);
    assert!(NativeFenceRegistry::default()
        .claim(&path, source(1))
        .is_err());
    held.stdin.as_mut().unwrap().write_all(b"x").unwrap();
    assert!(held.wait().unwrap().success());
    let cap = NativeFenceRegistry::default()
        .claim(&path, source(1))
        .unwrap();
    assert_eq!(cap.generation, 2);
    drop(cap);
    let mut crashed = child(&path);
    assert!(NativeFenceRegistry::default()
        .claim(&path, source(1))
        .is_err());
    crashed.kill().unwrap();
    let _ = crashed.wait().unwrap();
    let cap = NativeFenceRegistry::default()
        .claim(&path, source(1))
        .unwrap();
    assert_eq!(cap.generation, 4);
    // Native reacquisition is lock/generation evidence, not old physical teardown.
}
