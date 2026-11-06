#[cfg(test)]
mod ownership_controls {
    use super::*;
    use std::collections::HashSet;
    use std::ffi::CStr;
    use std::sync::{Arc, Condvar};

    #[derive(Default)]
    struct Sdk {
        alive: HashSet<u64>,
        creates: usize,
        destroys: usize,
        stale_dispatches: usize,
        lookups: usize,
        block_lookup: bool,
        lookup_entered: bool,
        block_create: bool,
        create_entered: bool,
    }
    static SDK: OnceLock<(Mutex<Sdk>, Condvar)> = OnceLock::new();
    static OWNER: AtomicU64 = AtomicU64::new(100);
    fn sdk() -> &'static (Mutex<Sdk>, Condvar) {
        SDK.get_or_init(|| (Mutex::new(Sdk::default()), Condvar::new()))
    }
    fn next() -> i64 {
        let id = OWNER.fetch_add(1, Ordering::SeqCst) + 1;
        sdk().0.lock().unwrap().alive.insert(id);
        id as i64
    }
    fn snapshot() -> (usize, usize, usize, usize) {
        let s = sdk().0.lock().unwrap();
        (s.creates, s.destroys, s.stale_dispatches, s.lookups)
    }
    pub unsafe extern "system" fn fake_get_proc(
        _instance: openxr_sys::Instance,
        name: *const std::os::raw::c_char,
        function: *mut Option<openxr_sys::pfn::VoidFunction>,
    ) -> openxr_sys::Result {
        let pointer = match CStr::from_ptr(name).to_bytes() {
            b"xrCreateReferenceSpace" => fake_create as *const (),
            b"xrDestroySpace" => fake_destroy as *const (),
            b"xrLocateViews" => fake_locate as *const (),
            b"xrConvertTimespecTimeToTimeKHR" => fake_convert as *const (),
            _ => return openxr_sys::Result::ERROR_FUNCTION_UNSUPPORTED,
        };
        *function = Some(mem::transmute::<*const (), openxr_sys::pfn::VoidFunction>(
            pointer,
        ));
        openxr_sys::Result::SUCCESS
    }
    unsafe extern "system" fn fake_create(
        session: openxr_sys::Session,
        _info: *const openxr_sys::ReferenceSpaceCreateInfo,
        space: *mut openxr_sys::Space,
    ) -> openxr_sys::Result {
        let mut s = sdk().0.lock().unwrap();
        s.create_entered = true;
        sdk().1.notify_all();
        while s.block_create {
            s = sdk().1.wait(s).unwrap();
        }
        if !s.alive.contains(&session.into_raw()) {
            s.stale_dispatches += 1;
            return openxr_sys::Result::ERROR_HANDLE_INVALID;
        }
        s.creates += 1;
        *space = openxr_sys::Space::from_raw(session.into_raw());
        openxr_sys::Result::SUCCESS
    }
    unsafe extern "system" fn fake_destroy(space: openxr_sys::Space) -> openxr_sys::Result {
        let mut s = sdk().0.lock().unwrap();
        s.destroys += 1;
        if !s.alive.contains(&space.into_raw()) {
            s.stale_dispatches += 1;
            return openxr_sys::Result::ERROR_HANDLE_INVALID;
        }
        openxr_sys::Result::SUCCESS
    }
    unsafe extern "system" fn fake_convert(
        _instance: openxr_sys::Instance,
        _time: *const libc::timespec,
        out: *mut openxr_sys::Time,
    ) -> openxr_sys::Result {
        *out = openxr_sys::Time::from_nanos(1_000_000);
        openxr_sys::Result::SUCCESS
    }
    unsafe extern "system" fn fake_locate(
        session: openxr_sys::Session,
        _info: *const openxr_sys::ViewLocateInfo,
        state: *mut openxr_sys::ViewState,
        _capacity: u32,
        count: *mut u32,
        views: *mut openxr_sys::View,
    ) -> openxr_sys::Result {
        let (lock, signal) = sdk();
        let mut s = lock.lock().unwrap();
        s.lookups += 1;
        s.lookup_entered = true;
        signal.notify_all();
        while s.block_lookup {
            s = signal.wait(s).unwrap();
        }
        if !s.alive.contains(&session.into_raw()) {
            s.stale_dispatches += 1;
            return openxr_sys::Result::ERROR_HANDLE_INVALID;
        }
        (*state).view_state_flags = openxr_sys::ViewStateFlags::ORIENTATION_VALID;
        *count = 1;
        (*views).pose.orientation.w = 1.0;
        openxr_sys::Result::SUCCESS
    }
    fn enable() {
        CAMERA_LATENCY_ENABLED.store(true, Ordering::Relaxed);
        CAMERA_LATENCY_PRESENTATION_POSE_MODE.store(2, Ordering::Relaxed);
        let mut history = viewer_history().lock().unwrap();
        history.clear();
        history.push_back(
            basis_from_quaternion(1_000_000, 1, [0.0; 3], [0.0, 0.0, 0.0, 1.0]).unwrap(),
        );
    }
    fn invalidate(id: i64) {
        sdk().0.lock().unwrap().alive.remove(&(id as u64));
    }

    #[test]
    fn retirement_precedes_sdk_invalidation() {
        enable();
        let old = next();
        let before = snapshot();
        assert_eq!(test_configure(old, old, old) & 4, 4);
        test_release(old, true);
        invalidate(old);
        let new = next();
        assert_eq!(test_configure(new, new, new) & 4, 4);
        assert_eq!(
            snapshot().2,
            before.2,
            "old saved destroy dispatch ran after SDK invalidation"
        );
        assert_eq!(snapshot().1, before.1 + 1);
        assert_eq!(test_release(new, true), 1);
        invalidate(new);
    }

    #[test]
    fn same_owner_is_idempotent_and_mode_clear_can_reenable() {
        enable();
        let id = next();
        let before = snapshot();
        assert_eq!(test_configure(id, id, id) & 4, 4);
        assert_eq!(test_configure(id, id, id) & 4, 4);
        assert_eq!(snapshot().0, before.0 + 1);
        assert_eq!(test_release(id, false), 1);
        assert_eq!(test_configure(id, id, id) & 4, 4);
        assert_eq!(test_release(id, true), 1);
        assert_eq!(test_release(id, true), 0);
        assert!(test_configure(id, id, id) < 0);
        invalidate(id);
    }

    #[test]
    fn overlapping_owner_waits_and_late_old_shutdown_cannot_drop_new() {
        enable();
        let old = next();
        let new = next();
        assert_eq!(test_configure(old, old, old) & 4, 4);
        let before = snapshot();
        assert_eq!(test_configure(new, new, new) & 4, 0);
        assert_eq!(snapshot(), before, "busy successor must not call the SDK");
        assert_eq!(test_configure(old, old, old) & 4, 0);
        assert_eq!(snapshot(), before, "superseded live poll must stay inert");
        current_camera_latency_presentation_viewer_basis();
        assert_eq!(
            snapshot().3,
            before.3,
            "superseded owner must not supply poses"
        );
        assert_eq!(test_release(old, true), 1);
        invalidate(old);
        assert_eq!(test_configure(new, new, new) & 4, 4);
        let new_snapshot = snapshot();
        assert_eq!(test_release(old, true), 0);
        assert_eq!(snapshot(), new_snapshot);
        assert_eq!(test_configure(old, old, old) & 4, 0);
        assert_eq!(test_release(new, true), 1);
        invalidate(new);
    }

    #[test]
    fn wrong_handles_fail_without_replacement_or_lookup() {
        enable();
        let id = next();
        let other = next();
        assert_eq!(test_configure(id, id, id) & 4, 4);
        let before = snapshot();
        assert!(test_configure(id, id, other) < 0);
        current_camera_latency_presentation_viewer_basis();
        assert_eq!(snapshot(), before);
        assert_eq!(test_release(id, true), 1);
        invalidate(id);
        invalidate(other);
    }

    #[test]
    fn disabled_and_scene_modes_neither_acquire_nor_query_openxr() {
        let id = next();
        CAMERA_LATENCY_ENABLED.store(false, Ordering::Relaxed);
        CAMERA_LATENCY_PRESENTATION_POSE_MODE.store(2, Ordering::Relaxed);
        let before = snapshot();
        assert_eq!(test_configure(id, id, id) & 4, 0);
        current_camera_latency_presentation_viewer_basis();
        assert_eq!(snapshot(), before);
        CAMERA_LATENCY_ENABLED.store(true, Ordering::Relaxed);
        CAMERA_LATENCY_PRESENTATION_POSE_MODE.store(0, Ordering::Relaxed);
        assert_eq!(test_configure(id, id, id) & 4, 0);
        assert_eq!(snapshot(), before);
        test_release(id, true);
        invalidate(id);
    }

    #[test]
    fn retirement_waits_for_actual_inflight_lookup() {
        enable();
        let id = next();
        assert_eq!(test_configure(id, id, id) & 4, 4);
        {
            let mut s = sdk().0.lock().unwrap();
            s.block_lookup = true;
            s.lookup_entered = false;
        }
        let lookup = std::thread::spawn(current_camera_latency_presentation_viewer_basis);
        {
            let mut s = sdk().0.lock().unwrap();
            while !s.lookup_entered {
                s = sdk().1.wait(s).unwrap();
            }
        }
        let done = Arc::new(AtomicBool::new(false));
        let done_thread = done.clone();
        let close = std::thread::spawn(move || {
            assert_eq!(test_release(id, true), 1);
            done_thread.store(true, Ordering::SeqCst);
        });
        // A held real lookup dispatch prevents any reference-space destruction.
        let before = snapshot().1;
        std::thread::sleep(Duration::from_millis(20));
        assert!(!done.load(Ordering::SeqCst));
        assert_eq!(snapshot().1, before);
        {
            sdk().0.lock().unwrap().block_lookup = false;
            sdk().1.notify_all();
        }
        lookup.join().unwrap();
        close.join().unwrap();
        invalidate(id);
    }

    #[test]
    fn retirement_waits_for_actual_inflight_constructor() {
        enable();
        let id = next();
        {
            let mut s = sdk().0.lock().unwrap();
            s.block_create = true;
            s.create_entered = false;
        }
        let configure = std::thread::spawn(move || test_configure(id, id, id));
        {
            let mut s = sdk().0.lock().unwrap();
            while !s.create_entered {
                s = sdk().1.wait(s).unwrap();
            }
        }
        let done = Arc::new(AtomicBool::new(false));
        let copy = done.clone();
        let close = std::thread::spawn(move || {
            assert_eq!(test_release(id, true), 1);
            copy.store(true, Ordering::SeqCst);
        });
        std::thread::sleep(Duration::from_millis(20));
        assert!(!done.load(Ordering::SeqCst));
        {
            sdk().0.lock().unwrap().block_create = false;
            sdk().1.notify_all();
        }
        assert_eq!(configure.join().unwrap() & 4, 4);
        close.join().unwrap();
        invalidate(id);
    }

    #[test]
    fn unavailable_handles_and_invalid_owner_do_not_acquire() {
        enable();
        let id = next();
        let before = snapshot();
        assert!(test_configure(0, id, id) < 0);
        assert_eq!(test_configure(id, 0, id), 1);
        assert_eq!(snapshot(), before);
        assert_eq!(test_release(id, true), 0);
        assert!(test_configure(id, id, id) < 0);
        invalidate(id);
    }
}
