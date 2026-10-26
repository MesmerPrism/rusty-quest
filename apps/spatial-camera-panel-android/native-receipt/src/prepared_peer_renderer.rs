//! Independent carrier reservation until ordinary Peer source/decoder admission.
//! Included as a private child of camera_hwb_probe; Vulkan stays on one worker.
use super::prepared_renderer_gate::{
    own_reuse_phase, Gate, Phase, PrepareCall, PrepareCalls, ReusePhase, SourceHold,
};
use super::*;
use crate::peer_projection_runtime::SourceReceipt;
use std::sync::mpsc::{Receiver, TryRecvError};
#[derive(Clone)]
struct Ticket {
    id: String,
    app: u64,
    record: String,
    native: u64,
    config: String,
    request: String,
    target: String,
    route: i64,
    challenge: i64,
    surface: i64,
    switches: i64,
    width: u32,
    height: u32,
    window: usize,
    ordinal: u64,
    prior: Option<SourceReceipt>,
    target_value: serde_json::Value,
}
struct Job {
    ticket: Ticket,
    gate: Arc<Gate>,
    worker: Option<thread::JoinHandle<()>>,
    ready: Option<Receiver<Result<(), String>>>,
    joined: bool,
    source_hold_released: bool,
}
struct Registration {
    id: String,
    identity: serde_json::Value,
    entered: bool,
    pre_worker_terminal: bool,
    closed: bool,
    hold: SourceHold,
    calls: Arc<PrepareCalls>,
    stop: Arc<AtomicBool>,
}
struct OwnReuse {
    ticket: Ticket,
    epoch: crate::stereo_input_set::SourceEpoch,
    claim: i64,
    attempts: u64,
    released: bool,
    committed: bool,
}
#[derive(Default)]
struct Store {
    next: u64,
    job: Option<Job>,
    registration: Option<Registration>,
    reuse: Option<OwnReuse>,
}
static STORE: LazyLock<Mutex<Store>> = LazyLock::new(|| Mutex::new(Store::default()));
fn identity(t: &Ticket) -> bool {
    crate::embedded_duplex::prepared_renderer_identity(&t.id).is_ok_and(|v| {
        v.preparation_id == t.id
            && v.app_process_generation == t.app
            && v.app_record_sha256 == t.record
            && v.native_executor_generation == t.native
            && v.config_sha256 == t.config
            && v.request_sha256 == t.request
            && v.resource_target_sha256 == t.target
            && v.resource_target == t.target_value
            && !v.desired_stop.load(Ordering::Acquire)
    })
}
fn source(t: &Ticket) -> bool {
    let Ok(current) = crate::peer_projection_runtime::renderer_source_preimage() else {
        return false;
    };
    if current == t.prior {
        return true;
    }
    current.is_some_and(|v| {
        v.words[1] == t.route
            && v.words[2] == crate::peer_projection_runtime::SOURCE_PEER
            && v.words[5] == t.challenge
            && v.words[6] == t.surface
            && matches!(
                v.words[11],
                crate::peer_projection_runtime::RESULT_PENDING
                    | crate::peer_projection_runtime::RESULT_EFFECTIVE
            )
    })
}
fn committed_source(t: &Ticket) -> bool {
    crate::peer_projection_runtime::renderer_source_preimage()
        .ok()
        .flatten()
        .is_some_and(|v| {
            v.words[1] == t.route
                && v.words[2] == crate::peer_projection_runtime::SOURCE_PEER
                && v.words[5] == t.challenge
                && v.words[6] == t.surface
                && matches!(
                    v.words[11],
                    crate::peer_projection_runtime::RESULT_PENDING
                        | crate::peer_projection_runtime::RESULT_EFFECTIVE
                )
                && v.words[3] > 0
                && v.words[4] > 0
                && projection_peer_binding_matches(
                    t.route as u64,
                    v.words[3] as u64,
                    v.words[4] as u64,
                )
        })
}
fn release_claim(t: &Ticket) {
    let mut o = PEER_COMMON_GRAPH_SESSION
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if o.claim.generation() == Some(t.route) && o.worker.is_none() {
        o.cancellation = None;
        o.claim.release(t.route);
    }
}
fn view(job: &Job, r: &Registration) -> serde_json::Value {
    let t = &job.ticket;
    let (phase, failure) = job.gate.snapshot();
    serde_json::json!({"$schema":"rusty.quest.spatial.prepared_peer_renderer.v2","preparation_id":t.id,
  "app_process_generation":t.app,"app_record_sha256":t.record,"native_executor_generation":t.native,"config_sha256":t.config,
  "request_sha256":t.request,"resource_target_sha256":t.target,"route_generation":t.route,
  "launch_challenge":t.challenge,"surface_generation":t.surface,"layer_switch_count":t.switches,
  "requested_width":t.width,"requested_height":t.height,"actual_width":job.gate.extent().map(|v|v.0),"actual_height":job.gate.extent().map(|v|v.1),"worker_ordinal":t.ordinal,
  "state":match phase{Phase::Preparing=>"preparing",Phase::Ready=>"ready",Phase::Committed=>"committed",Phase::CleanupPending=>"cleanup_pending",Phase::Terminal=>"terminal"},
  "wsi_ready":matches!(phase,Phase::Ready|Phase::Committed),"frame_work_admitted":phase==Phase::Committed,
  "frame_import_count":if phase==Phase::Ready{Some(0)}else{None},"present_count":if phase==Phase::Ready{Some(0)}else{None},
  "source_hold_acquired":r.hold.acquired,"source_hold_released":r.hold.released,"prepare_calls_joined":r.calls.joined(),
  "cleanup_terminal":phase==Phase::Terminal&&job.joined&&job.source_hold_released&&r.hold.settled()&&r.calls.joined(),"worker_joined":job.joined,"failure":failure})
}
fn reap(id: &str) {
    let worker = {
        let mut s = STORE.lock().unwrap_or_else(|e| e.into_inner());
        s.job.as_mut().filter(|j| j.ticket.id == id).and_then(|j| {
            if j.worker.as_ref().is_some_and(|w| w.is_finished()) {
                j.worker.take()
            } else {
                None
            }
        })
    };
    if let Some(worker) = worker {
        let result = worker.join();
        let mut s = STORE.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(j) = s.job.as_mut().filter(|j| j.ticket.id == id) {
            j.joined = true;
            if result.is_err() {
                j.gate.cleanup_failed(
                    "actual prepared renderer worker panic; physical custody unresolved".into(),
                );
            }
        }
    }
    // Java's abort readback must itself be able to earn terminal before the
    // enclosing Native abort validates that Java receipt. A failed release keeps
    // the same ticket pending and is retried on the next actual read/abort.
    let mut s = STORE.lock().unwrap_or_else(|e| e.into_inner());
    let terminal = s
        .job
        .as_ref()
        .is_some_and(|j| j.ticket.id == id && j.joined && j.gate.snapshot().0 == Phase::Terminal)
        && s.registration
            .as_ref()
            .is_some_and(|r| r.id == id && r.calls.joined());
    if terminal && release_source(&mut s, id).is_ok() {
        s.job.as_mut().unwrap().source_hold_released = true;
        s.registration.as_mut().unwrap().closed = true;
    }
}
fn release_source(s: &mut Store, id: &str) -> Result<(), String> {
    let r = s
        .registration
        .as_mut()
        .filter(|r| r.id == id)
        .ok_or("exact renderer source registration absent")?;
    r.hold
        .release(|| crate::peer_projection_runtime::release_renderer_source(id))
}
pub(super) fn enter(id: &str) -> Result<PrepareCall, String> {
    let actual = crate::embedded_duplex::prepared_renderer_identity(id)?;
    let s = STORE
        .lock()
        .map_err(|_| "prepared renderer owner poisoned")?;
    let r = s
        .registration
        .as_ref()
        .filter(|r| r.id == id && !r.entered && !r.closed && r.hold.acquired && !r.hold.released)
        .ok_or("actual unused renderer entry registration absent")?;
    let body = serde_json::json!({"preparation_id":actual.preparation_id,"app_process_generation":actual.app_process_generation,
  "app_record_sha256":actual.app_record_sha256,"native_executor_generation":actual.native_executor_generation,"config_sha256":actual.config_sha256,
  "request_sha256":actual.request_sha256,"resource_target_sha256":actual.resource_target_sha256,"resource_target":actual.resource_target});
    if r.identity != body || !Arc::ptr_eq(&actual.desired_stop, &r.stop) {
        return Err("renderer JNI actual Stop owner changed".into());
    }
    r.calls.enter()
}
pub(super) fn read(id: &str) -> Result<serde_json::Value, String> {
    if let Some(v) = read_reuse(id)? {
        return Ok(v);
    }
    reap(id);
    let s = STORE
        .lock()
        .map_err(|_| "prepared renderer owner poisoned")?;
    let j = s
        .job
        .as_ref()
        .filter(|j| j.ticket.id == id)
        .ok_or("exact prepared renderer absent")?;
    Ok(view(
        j,
        s.registration
            .as_ref()
            .filter(|r| r.id == id)
            .ok_or("actual renderer registration absent")?,
    ))
}
pub(super) fn abort(id: &str) -> Result<serde_json::Value, String> {
    {
        let s = STORE
            .lock()
            .map_err(|_| "prepared renderer owner poisoned")?;
        let r = s
            .registration
            .as_ref()
            .filter(|r| r.id == id)
            .ok_or("actual renderer registration absent")?;
        r.calls.close();
        if !r.entered || r.pre_worker_terminal {
            drop(s);
            return terminal_for(id);
        }
    }
    {
        let mut s = STORE
            .lock()
            .map_err(|_| "prepared renderer owner poisoned")?;
        if let Some(r) = s.reuse.as_mut().filter(|r| r.ticket.id == id) {
            r.released = true;
            release_source(&mut s, id)?;
            drop(s);
            return read(id);
        }
    }
    {
        let s = STORE
            .lock()
            .map_err(|_| "prepared renderer owner poisoned")?;
        let j = s
            .job
            .as_ref()
            .filter(|j| j.ticket.id == id)
            .ok_or("exact prepared renderer absent")?;
        j.gate.abort();
    }
    read(id)
}
pub(super) fn stop_current() {
    let id = {
        let s = STORE.lock().unwrap_or_else(|e| e.into_inner());
        s.registration.as_ref().map(|r| r.id.clone())
    };
    if let Some(id) = id {
        let _ = abort(&id);
    }
}
pub(super) fn preserve_selected(
    words: &[i64; crate::peer_projection_runtime::SOURCE_ABI_WORDS],
    window: usize,
) -> bool {
    let ticket = {
        let s = STORE.lock().unwrap_or_else(|e| e.into_inner());
        s.job
            .as_ref()
            .filter(|j| {
                matches!(j.gate.snapshot().0, Phase::Preparing | Phase::Ready)
                    && !j.gate.stop.load(Ordering::Acquire)
            })
            .map(|j| j.ticket.clone())
    };
    ticket.is_some_and(|t| {
        identity(&t)
            && words[1] == t.route
            && words[2] == crate::peer_projection_runtime::SOURCE_PEER
            && words[5] == t.challenge
            && words[6] == t.surface
            && window == t.window
    })
}
/// Takes exactly one ANativeWindow reference; every refusal releases it.
pub(super) unsafe fn prepare(
    id: &str,
    call: &PrepareCall,
    window: *mut ANativeWindow,
    width: u32,
    height: u32,
    route: i64,
    challenge: i64,
    surface: i64,
    switches: i64,
) -> Result<serde_json::Value, String> {
    let valid_call = STORE
        .lock()
        .map(|s| {
            s.registration
                .as_ref()
                .is_some_and(|r| r.id == id && r.calls.owns(call))
        })
        .unwrap_or(false);
    if !valid_call {
        if !window.is_null() {
            ACameraNativeWindow_release(window);
        }
        return Err("actual renderer JNI call custody differs".into());
    }
    if crate::own_stereo_capture_runtime::capture_claimed() {
        let result = prepare_reuse(
            id, window, width, height, route, challenge, surface, switches,
        );
        if !window.is_null() {
            ACameraNativeWindow_release(window);
        }
        return result;
    }
    let result = (|| -> Result<(), String> {
        let actual = crate::embedded_duplex::prepared_renderer_identity(id)?;
        if window.is_null()
            || width < 64
            || height < 64
            || route <= 0
            || challenge <= 0
            || surface <= 0
            || switches < 0
            || !local_camera_acquisition_quiescent()
            || crate::own_stereo_capture_runtime::capture_claimed()
        {
            return Err("prepared renderer requires exact dormant cold-Peer carrier".into());
        }
        let prior = crate::peer_projection_runtime::renderer_source_preimage()?;
        if prior.is_some_and(|v| {
            v.words[1] >= route
                || !matches!(
                    v.words[11],
                    crate::peer_projection_runtime::RESULT_INACTIVE
                        | crate::peer_projection_runtime::RESULT_UNAVAILABLE
                )
        }) {
            return Err("prepared renderer predecessor is not actual inactive source".into());
        }
        crate::peer_projection_runtime::bind_renderer_source(id, [route, challenge, surface])?;
        let mut s = STORE
            .lock()
            .map_err(|_| "prepared renderer owner poisoned")?;
        let reg = s
            .registration
            .as_mut()
            .filter(|r| r.id == id)
            .ok_or("actual pre-callback renderer registration absent")?;
        if reg.entered
            || reg.closed
            || !reg.hold.acquired
            || reg.hold.released
            || reg.stop.load(Ordering::Acquire)
        {
            return Err("prepared renderer construction was already entered".into());
        }
        reg.entered = true;
        if s.job
            .as_ref()
            .is_some_and(|j| !j.joined || j.gate.snapshot().0 != Phase::Terminal)
        {
            return Err("actual prior prepared renderer remains owned".into());
        }
        let next = s
            .next
            .checked_add(1)
            .ok_or("prepared renderer worker ordinal overflow")?;
        {
            let mut o = PEER_COMMON_GRAPH_SESSION
                .lock()
                .map_err(|_| "prepared actual renderer claim poisoned")?;
            if ACTIVE_PEER_COMMON_GRAPH_WORKERS.load(Ordering::Acquire) != 0
                || !o.claim.claim(route)
            {
                return Err("actual prior Peer renderer remains owned".into());
            }
            let Some(n) = o.construction_attempts.checked_add(1) else {
                o.claim.release(route);
                return Err("actual renderer construction ordinal overflow".into());
            };
            o.construction_attempts = n;
            o.cancellation = Some(actual.desired_stop.clone());
        }
        let t = Ticket {
            id: id.into(),
            app: actual.app_process_generation,
            record: actual.app_record_sha256,
            native: actual.native_executor_generation,
            config: actual.config_sha256,
            request: actual.request_sha256,
            target: actual.resource_target_sha256,
            route,
            challenge,
            surface,
            switches,
            width,
            height,
            window: window as usize,
            ordinal: next,
            prior,
            target_value: actual.resource_target,
        };
        let gate = Arc::new(Gate::new(actual.desired_stop));
        let owned_gate = gate.clone();
        let owned = t.clone();
        let (tx, rx) = std::sync::mpsc::sync_channel(1);
        ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_add(1, Ordering::AcqRel);
        let worker = thread::Builder::new()
            .name(format!("prepared-peer-renderer-{next}"))
            .spawn(move || {
                let window = owned.window as *mut vk::ANativeWindow;
                // Actual retained readiness is published only after successful WSI creation.
                let run = (|| -> Result<(), String> {
                    if !identity(&owned) || !source(&owned) {
                        return Err("prepared renderer actual source changed before WSI".into());
                    }
                    let wsi = unsafe {
                        CameraHwbWsiParts::create_with_startup(
                            window,
                            owned.width,
                            owned.height,
                            true,
                            &owned_gate.stop,
                            Some(OwnedRendererStartupSource::Prepared(owned.id.clone())),
                        )
                    }?;
                    let mut resources = PeerCommonGraphResources::new(wsi);
                    let active = (|| -> Result<(), String> {
                        let extent = resources.wsi.as_ref().unwrap().extent;
                        owned_gate.ready((extent.width, extent.height))?;
                        owned_gate.wait_commit(|| identity(&owned) && source(&owned))?;
                        if !identity(&owned)
                            || !committed_source(&owned)
                            || owned_gate.stop.load(Ordering::Acquire)
                        {
                            return Err("prepared renderer changed at actual commit".into());
                        }
                        unsafe {
                            render_peer_common_graph_resources(
                                &mut resources,
                                0,
                                owned.route,
                                owned_gate.stop.clone(),
                                tx,
                            )
                        }
                    })();
                    loop {
                        match unsafe { resources.teardown_retaining() } {
                            Ok(()) => break,
                            Err(error) => {
                                let retry = owned_gate.cleanup_failed(error);
                                owned_gate.wait_cleanup_retry(retry);
                            }
                        }
                    }
                    active
                })();
                unsafe {
                    ACameraNativeWindow_release(window.cast::<ANativeWindow>());
                }
                release_claim(&owned);
                ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_sub(1, Ordering::AcqRel);
                publish_acquisition_stopped_if_quiescent();
                owned_gate.terminal(run.err());
            });
        match worker {
            Ok(w) => {
                s.next = next;
                s.job = Some(Job {
                    ticket: t,
                    gate,
                    worker: Some(w),
                    ready: Some(rx),
                    joined: false,
                    source_hold_released: false,
                });
                Ok(())
            }
            Err(error) => {
                release_claim(&t);
                ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_sub(1, Ordering::AcqRel);
                Err(format!("prepared renderer worker launch: {}", error.kind()))
            }
        }
    })();
    if result.is_err() {
        if !window.is_null() {
            ACameraNativeWindow_release(window);
        }
        let mut store = STORE.lock().unwrap_or_else(|e| e.into_inner());
        if store.job.as_ref().is_none_or(|j| j.ticket.id != id) {
            if let Some(r) = store
                .registration
                .as_mut()
                .filter(|r| r.id == id && r.entered)
            {
                r.pre_worker_terminal = true;
            }
        }
        return result.map(|_| serde_json::Value::Null);
    }
    // WSI preparation is synchronous only with this same worker; estimates warn,
    // actual source/Stop/failure decides. No media first frame is awaited here.
    let mut warned = false;
    let began = Instant::now();
    loop {
        let row = read(id)?;
        let state = row["state"].as_str().unwrap_or("invalid");
        if state == "ready" {
            return dormant_for(id);
        }
        if state != "preparing" {
            return Ok(row);
        }
        let ticket = {
            let s = STORE
                .lock()
                .map_err(|_| "prepared renderer owner poisoned")?;
            s.job.as_ref().unwrap().ticket.clone()
        };
        if !identity(&ticket) || !source(&ticket) {
            let _ = abort(id);
        }
        if !warned && began.elapsed() >= Duration::from_secs(5) {
            warned = true;
            log_marker("status=prepared-peer-renderer-estimate-elapsed sameOriginal=true".into());
        }
        thread::sleep(Duration::from_millis(10));
    }
}
/// None preserves the unchanged legacy path. Some always consumes/releases the
/// newly-acquired duplicate window reference; the original stays on its worker.
pub(super) unsafe fn commit(
    window: *mut ANativeWindow,
    width: u32,
    height: u32,
    max_frames: u32,
    route: i64,
) -> Option<i64> {
    let (ticket, entered_joined) = {
        let s = STORE.lock().unwrap_or_else(|e| e.into_inner());
        let j = s
            .job
            .as_ref()
            .filter(|j| j.ticket.route == route && !j.joined)?;
        (
            j.ticket.clone(),
            s.registration.as_ref().is_some_and(|r| {
                r.id == j.ticket.id
                    && r.calls.joined()
                    && !r.closed
                    && r.hold.acquired
                    && !r.hold.released
            }),
        )
    };
    ACameraNativeWindow_release(window);
    if !entered_joined
        || max_frames != 0
        || ticket.width != width
        || ticket.height != height
        || ticket.window != window as usize
        || !identity(&ticket)
        || !committed_source(&ticket)
    {
        let _ = abort(&ticket.id);
        return Some(0);
    }
    let (rx, gate) = {
        let mut s = STORE.lock().unwrap_or_else(|e| e.into_inner());
        let j = s.job.as_mut().unwrap();
        if j.gate.commit().is_err() {
            return Some(0);
        }
        let Some(rx) = j.ready.take() else {
            return Some(0);
        };
        (rx, j.gate.clone())
    };
    let mut warned = false;
    let began = Instant::now();
    loop {
        match rx.try_recv() {
            Ok(Ok(())) => {
                if identity(&ticket)
                    && committed_source(&ticket)
                    && !gate.stop.load(Ordering::Acquire)
                {
                    return Some(1);
                }
                gate.abort();
                return Some(0);
            }
            Ok(Err(_)) | Err(TryRecvError::Disconnected) => return Some(0),
            Err(TryRecvError::Empty) => {}
        }
        if !identity(&ticket) || !source(&ticket) || gate.stop.load(Ordering::Acquire) {
            gate.abort();
        }
        if !warned && began.elapsed() >= Duration::from_secs(5) {
            warned = true;
            log_marker(
                "status=prepared-peer-renderer-first-frame-estimate-elapsed sameOriginal=true"
                    .into(),
            );
        }
        thread::sleep(Duration::from_millis(10));
    }
}

pub(super) fn dormant_for(id: &str) -> Result<serde_json::Value, String> {
    if let Some(v) = read_reuse(id)? {
        if v["state"] != "ready" {
            return Err("actual Own reuse was released".into());
        }
        return Ok(v);
    }
    let (t, g, live) = {
        let s = STORE
            .lock()
            .map_err(|_| "prepared renderer owner poisoned")?;
        let j = s
            .job
            .as_ref()
            .filter(|j| j.ticket.id == id)
            .ok_or("exact prepared renderer absent")?;
        (
            j.ticket.clone(),
            j.gate.clone(),
            !j.joined && j.worker.as_ref().is_some_and(|w| !w.is_finished()),
        )
    };
    if !live
        || g.snapshot().0 != Phase::Ready
        || g.stop.load(Ordering::Acquire)
        || !identity(&t)
        || crate::peer_projection_runtime::renderer_source_preimage()? != t.prior
    {
        return Err("actual prepared renderer is not exact uncommitted dormant WSI".into());
    }
    read(id)
}
pub(super) fn startup_current(id: &str) -> bool {
    let t = {
        let s = STORE.lock().unwrap_or_else(|e| e.into_inner());
        s.job
            .as_ref()
            .filter(|j| j.ticket.id == id && !j.gate.stop.load(Ordering::Acquire))
            .map(|j| j.ticket.clone())
    };
    t.is_some_and(|t| identity(&t) && source(&t))
}

// Actual Native reservation calls this before any SDK callback. No absent-slot
// inference can later turn an unrecorded renderer into a terminal resource.
pub(super) fn begin(id: &str) -> Result<(), String> {
    let a = crate::embedded_duplex::prepared_renderer_identity(id)?;
    let body = serde_json::json!({"preparation_id":a.preparation_id,"app_process_generation":a.app_process_generation,
  "app_record_sha256":a.app_record_sha256,"native_executor_generation":a.native_executor_generation,"config_sha256":a.config_sha256,
  "request_sha256":a.request_sha256,"resource_target_sha256":a.resource_target_sha256,"resource_target":a.resource_target});
    let mut s = STORE
        .lock()
        .map_err(|_| "prepared renderer owner poisoned")?;
    if let Some(r) = s.registration.as_ref().filter(|r| r.id == id) {
        if r.identity != body || r.entered || r.closed {
            return Err("actual renderer registration changed or entered".into());
        }
        return Ok(());
    }
    if s.job
        .as_ref()
        .is_some_and(|j| !j.joined || j.gate.snapshot().0 != Phase::Terminal)
    {
        return Err("prior renderer actual custody remains pending".into());
    }
    if s.registration
        .as_ref()
        .is_some_and(|r| !r.closed || !r.hold.settled() || !r.calls.joined())
    {
        return Err("prior renderer source/callback custody remains pending".into());
    }
    if s.reuse.as_ref().is_some_and(|r| !r.released) {
        return Err("prior actual Own-reuse remains reserved".into());
    }
    s.reuse = None;
    s.registration = Some(Registration {
        id: id.into(),
        identity: body,
        entered: false,
        pre_worker_terminal: false,
        closed: false,
        hold: SourceHold::default(),
        calls: Arc::new(PrepareCalls::new(a.desired_stop.clone())),
        stop: a.desired_stop,
    });
    let outcome = if !local_camera_acquisition_quiescent() {
        Err("actual local camera remains owned".into())
    } else {
        crate::peer_projection_runtime::reserve_renderer_source(id)
    };
    let r = s.registration.as_mut().unwrap();
    match outcome {
        Ok(()) => {
            r.hold.acquired = true;
            Ok(())
        }
        Err(error) => {
            r.closed = true;
            r.pre_worker_terminal = true;
            Err(error)
        }
    }
}
pub(super) fn terminal_for(id: &str) -> Result<serde_json::Value, String> {
    reap(id);
    let mut s = STORE
        .lock()
        .map_err(|_| "prepared renderer owner poisoned")?;
    let r = s
        .registration
        .as_ref()
        .filter(|r| r.id == id)
        .ok_or("actual renderer registration absent; no absence proof")?;
    r.calls.close();
    if !r.calls.joined() {
        return Err("actual renderer JNI prepare call remains entered".into());
    }
    if let Some(own) = s.reuse.as_ref().filter(|r| r.ticket.id == id) {
        if !own.released {
            return Err("actual Peer Own-reuse reservation remains active".into());
        }
        release_source(&mut s, id)?;
        s.registration.as_mut().unwrap().closed = true;
        drop(s);
        return read_reuse(id)?.ok_or("actual Own reuse metadata absent".into());
    }
    if let Some(j) = s.job.as_ref().filter(|j| j.ticket.id == id) {
        if j.gate.snapshot().0 != Phase::Terminal || !j.joined {
            return Err("actual prepared renderer cleanup/worker join pending".into());
        }
        release_source(&mut s, id)?;
        s.job.as_mut().unwrap().source_hold_released = true;
        s.registration.as_mut().unwrap().closed = true;
        return Ok(view(
            s.job.as_ref().unwrap(),
            s.registration.as_ref().unwrap(),
        ));
    }
    let r = s.registration.as_ref().unwrap();
    if r.entered && !r.pre_worker_terminal {
        return Err("renderer entered construction remains unknown".into());
    }
    release_source(&mut s, id)?;
    let r = s.registration.as_mut().unwrap();
    r.closed = true;
    Ok(
        serde_json::json!({"$schema":"rusty.quest.spatial.prepared_peer_renderer_unentered.v2","identity":r.identity,
  "disposition":if r.entered{"refused_before_worker"}else if r.pre_worker_terminal{"registration_refused_before_callbacks"}else{"registered_not_entered"},
  "source_hold_acquired":r.hold.acquired,"source_hold_released":r.hold.released,"prepare_calls_joined":r.calls.joined(),"cleanup_terminal":true,"worker_created":false}),
    )
}

fn reuse_worker_current(r: &OwnReuse, o: &PeerCommonGraphSessionOwner) -> bool {
    o.claim.generation() == Some(r.claim)
        && o.own_epoch == Some(r.epoch)
        && o.window_address == r.ticket.window
        && o.construction_attempts == r.attempts
        && o.startup_readiness
            .as_ref()
            .is_some_and(|v| v.state() == crate::source_graph_startup::State::Ready)
        && o.worker.as_ref().is_some_and(|w| !w.is_finished())
        && o.cancellation
            .as_ref()
            .is_some_and(|c| !c.load(Ordering::Acquire))
}
fn reuse_current(r: &OwnReuse) -> bool {
    if r.released
        || !identity(&r.ticket)
        || !crate::own_stereo_capture_runtime::capture_epoch_is_current(r.epoch)
    {
        return false;
    }
    let Ok(o) = PEER_COMMON_GRAPH_SESSION.lock() else {
        return false;
    };
    reuse_worker_current(r, &o)
}
fn reuse_phase(r: &OwnReuse) -> Result<ReusePhase, String> {
    let current = crate::peer_projection_runtime::renderer_source_preimage()?;
    own_reuse_phase(
        r.committed,
        current == r.ticket.prior,
        committed_source(&r.ticket),
    )
}
fn read_reuse(id: &str) -> Result<Option<serde_json::Value>, String> {
    let (row, hold, calls) = {
        let s = STORE
            .lock()
            .map_err(|_| "prepared renderer owner poisoned")?;
        let Some(r) = s.reuse.as_ref().filter(|r| r.ticket.id == id) else {
            return Ok(None);
        };
        let reg = s
            .registration
            .as_ref()
            .filter(|r| r.id == id)
            .ok_or("exact Own source registration absent")?;
        (
            OwnReuse {
                ticket: r.ticket.clone(),
                epoch: r.epoch,
                claim: r.claim,
                attempts: r.attempts,
                released: r.released,
                committed: r.committed,
            },
            reg.hold,
            reg.calls.clone(),
        )
    };
    let r = row;
    let phase = if r.released {
        None
    } else {
        if !reuse_current(&r) {
            return Err("actual existing Own renderer identity/readiness changed".into());
        }
        Some(reuse_phase(&r)?)
    };
    let released = r.released && hold.acquired && hold.released && calls.joined();
    let state = if r.released {
        if released {
            "released"
        } else {
            "release_pending"
        }
    } else if !hold.released {
        "release_pending"
    } else {
        match phase.unwrap() {
            ReusePhase::Ready => "ready",
            ReusePhase::Committing => "committing",
            ReusePhase::Committed => "committed",
        }
    };
    Ok(Some(
        serde_json::json!({"$schema":"rusty.quest.spatial.prepared_peer_renderer_own_reuse.v2", "preparation_id":r.ticket.id,
  "app_process_generation":r.ticket.app,"app_record_sha256":r.ticket.record,"native_executor_generation":r.ticket.native,"config_sha256":r.ticket.config,
  "request_sha256":r.ticket.request,"resource_target_sha256":r.ticket.target,"peer_route_generation":r.ticket.route,
  "launch_challenge":r.ticket.challenge,"surface_generation":r.ticket.surface,"layer_switch_count":r.ticket.switches,
  "own_claim_generation":r.claim,"own_process_generation":r.epoch.process_generation,"own_source_generation":r.epoch.source_generation,
  "own_construction_attempts":r.attempts,"state":state,"source_hold_acquired":hold.acquired,"source_hold_released":hold.released,"prepare_calls_joined":calls.joined(),
  "peer_gpu_worker_created":false,"peer_frame_import_count":if state=="ready"{Some(0)}else{None},"peer_present_count":if state=="ready"{Some(0)}else{None},
  "peer_reservation_released":released,"own_resources_retired":false}),
    ))
}
fn prepare_reuse(
    id: &str,
    window: *mut ANativeWindow,
    width: u32,
    height: u32,
    route: i64,
    challenge: i64,
    surface: i64,
    switches: i64,
) -> Result<serde_json::Value, String> {
    let a = crate::embedded_duplex::prepared_renderer_identity(id)?;
    if a.resource_target["own_capture_selected"] != true
        || window.is_null()
        || route <= 0
        || challenge <= 0
        || surface <= 0
        || width < 64
        || height < 64
        || switches < 0
    {
        return Err("actual Own-reuse target/carrier differs".into());
    }
    let prior = crate::peer_projection_runtime::renderer_source_preimage()?;
    if prior.is_none_or(|p| p.words[1] >= route) {
        return Err("actual Own predecessor source differs".into());
    }
    let (epoch, claim, attempts) = {
        let o = PEER_COMMON_GRAPH_SESSION
            .lock()
            .map_err(|_| "actual Own renderer owner poisoned")?;
        (
            o.own_epoch.ok_or("actual Own renderer epoch absent")?,
            o.claim
                .generation()
                .ok_or("actual Own renderer claim absent")?,
            o.construction_attempts,
        )
    };
    let t = Ticket {
        id: id.into(),
        app: a.app_process_generation,
        record: a.app_record_sha256,
        native: a.native_executor_generation,
        config: a.config_sha256,
        request: a.request_sha256,
        target: a.resource_target_sha256,
        route,
        challenge,
        surface,
        switches,
        width,
        height,
        window: window as usize,
        ordinal: attempts,
        prior,
        target_value: a.resource_target,
    };
    let reuse = OwnReuse {
        ticket: t,
        epoch,
        claim,
        attempts,
        released: false,
        committed: false,
    };
    if !reuse_current(&reuse) || reuse_phase(&reuse)? != ReusePhase::Ready {
        return Err("actual Own renderer is not dormant reusable".into());
    }
    {
        let mut s = STORE
            .lock()
            .map_err(|_| "prepared renderer owner poisoned")?;
        let r = s
            .registration
            .as_mut()
            .filter(|r| {
                r.id == id
                    && !r.entered
                    && !r.closed
                    && r.hold.acquired
                    && !r.hold.released
                    && !r.stop.load(Ordering::Acquire)
            })
            .ok_or("actual unused Own-reuse registration absent")?;
        r.entered = true;
        s.reuse = Some(reuse);
        release_source(&mut s, id)?;
    }
    read(id)
}
// Matching prepared Own tickets NEVER fall back to the generic source-set
// recovery/spawn path. The original worker is inspected under its real lock.
pub(super) unsafe fn commit_reuse(
    window: *mut ANativeWindow,
    width: u32,
    height: u32,
    max_frames: u32,
    route: i64,
) -> Option<i64> {
    let mut s = STORE.lock().unwrap_or_else(|e| e.into_inner());
    let id = s
        .reuse
        .as_ref()
        .filter(|r| r.ticket.route == route)?
        .ticket
        .id
        .clone();
    let exact_hold = s.registration.as_ref().is_some_and(|r| {
        r.id == id
            && r.hold.acquired
            && r.hold.released
            && !r.closed
            && r.calls.joined()
            && !r.stop.load(Ordering::Acquire)
    });
    let r = s.reuse.as_mut().unwrap();
    if !window.is_null() {
        ACameraNativeWindow_release(window);
    }
    if !exact_hold
        || r.released
        || r.committed
        || max_frames != 0
        || r.ticket.window != window as usize
        || r.ticket.width != width
        || r.ticket.height != height
        || !identity(&r.ticket)
        || !committed_source(&r.ticket)
    {
        return Some(0);
    }
    let epoch = crate::own_stereo_capture_runtime::own_image_fresh_observed().ok();
    let o = PEER_COMMON_GRAPH_SESSION
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if epoch != Some(r.epoch)
        || !reuse_worker_current(r, &o)
        || !identity(&r.ticket)
        || !committed_source(&r.ticket)
    {
        return Some(0);
    }
    let attached = crate::peer_projection_runtime::record_peer_common_graph_attached(route);
    if attached.words[11] != crate::peer_projection_runtime::RESULT_PENDING || !identity(&r.ticket)
    {
        return Some(0);
    }
    r.committed = true;
    Some(1)
}
