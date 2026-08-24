//! Panel-controlled, app-persistent LSL data plane for the native renderer.
//!
//! This module deliberately keeps discovery and socket work off the OpenXR and
//! Polar acquisition threads. Producers only attempt a bounded enqueue. The
//! worker owns every liblsl handle and a separate inlet worker owns resolution
//! and blocking pulls.

use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, SyncSender, TrySendError},
        Arc, Mutex, MutexGuard, OnceLock,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use serde_json::{json, Value};

use crate::lsl_android::{self, LslChannelFormat, LslInlet, LslOutlet};

pub(crate) const PANEL_COMMAND_SCHEMA: &str = "rusty.quest.native_renderer.lsl.panel_command.v1";
pub(crate) const PERSISTED_CONFIG_SCHEMA: &str =
    "rusty.quest.native_renderer.lsl.persisted_config.v1";
const STATUS_SCHEMA: &str = "rusty.quest.native_renderer.lsl.status.v1";
const OUTBOUND_QUEUE_CAPACITY: usize = 4096;
const MAX_RETAINED_REQUEST_IDS: usize = 64;
const MAX_TOKEN_LENGTH: usize = 96;
const INLET_BUFFER_CHANNELS: usize = 32;

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct LslPanelConfig {
    pub(crate) enabled: bool,
    pub(crate) outlet_enabled: bool,
    pub(crate) inlet_enabled: bool,
    pub(crate) stream_prefix: String,
    pub(crate) participant_id: String,
    pub(crate) session_id: String,
    pub(crate) polar_hr: bool,
    pub(crate) polar_rr: bool,
    pub(crate) polar_acc: bool,
    pub(crate) polar_ecg: bool,
    pub(crate) controller_right_grip: bool,
    pub(crate) headset_views: bool,
    pub(crate) inlet_resolve_by: LslInletResolveBy,
    pub(crate) inlet_resolve_value: String,
    pub(crate) inlet_driver_slot: usize,
    pub(crate) inlet_sample_hold_seconds: f32,
    pub(crate) inlet_recover: bool,
}

impl Default for LslPanelConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            outlet_enabled: false,
            inlet_enabled: false,
            stream_prefix: "viscereality".to_owned(),
            participant_id: "participant".to_owned(),
            session_id: "session".to_owned(),
            polar_hr: true,
            polar_rr: true,
            polar_acc: true,
            polar_ecg: true,
            controller_right_grip: true,
            headset_views: true,
            inlet_resolve_by: LslInletResolveBy::SourceId,
            inlet_resolve_value: "viscereality.input.driver1".to_owned(),
            inlet_driver_slot: 1,
            inlet_sample_hold_seconds: 1.0,
            inlet_recover: true,
        }
    }
}

impl LslPanelConfig {
    fn from_value(value: &Value) -> Result<Self, String> {
        let object = value
            .as_object()
            .ok_or_else(|| "config-must-be-object".to_owned())?;
        if object
            .get("schema")
            .and_then(Value::as_str)
            .is_some_and(|schema| schema != PERSISTED_CONFIG_SCHEMA)
        {
            return Err("unsupported-config-schema".to_owned());
        }
        let defaults = Self::default();
        let outlets = object.get("outlets").and_then(Value::as_object);
        let inlet = object.get("inlet").and_then(Value::as_object);
        let config = Self {
            enabled: bool_field(object, "enabled", defaults.enabled)?,
            outlet_enabled: bool_field(object, "outlet_enabled", defaults.outlet_enabled)?,
            inlet_enabled: bool_field(object, "inlet_enabled", defaults.inlet_enabled)?,
            stream_prefix: token_field(
                object.get("stream_prefix"),
                &defaults.stream_prefix,
                "stream-prefix",
            )?,
            participant_id: token_field(
                object.get("participant_id"),
                &defaults.participant_id,
                "participant-id",
            )?,
            session_id: token_field(object.get("session_id"), &defaults.session_id, "session-id")?,
            polar_hr: nested_bool(outlets, "polar_hr", defaults.polar_hr)?,
            polar_rr: nested_bool(outlets, "polar_rr", defaults.polar_rr)?,
            polar_acc: nested_bool(outlets, "polar_acc", defaults.polar_acc)?,
            polar_ecg: nested_bool(outlets, "polar_ecg", defaults.polar_ecg)?,
            controller_right_grip: nested_bool(
                outlets,
                "controller_right_grip",
                defaults.controller_right_grip,
            )?,
            headset_views: nested_bool(outlets, "headset_views", defaults.headset_views)?,
            inlet_resolve_by: LslInletResolveBy::parse(
                inlet
                    .and_then(|object| object.get("resolve_by"))
                    .and_then(Value::as_str)
                    .unwrap_or(defaults.inlet_resolve_by.marker_value()),
            )?,
            inlet_resolve_value: token_field(
                inlet.and_then(|object| object.get("resolve_value")),
                &defaults.inlet_resolve_value,
                "inlet-resolve-value",
            )?,
            inlet_driver_slot: usize_field(inlet, "driver_slot", defaults.inlet_driver_slot, 1, 7)?,
            inlet_sample_hold_seconds: f32_field(
                inlet,
                "sample_hold_seconds",
                defaults.inlet_sample_hold_seconds,
                0.033,
                60.0,
            )?,
            inlet_recover: nested_bool(inlet, "recover", defaults.inlet_recover)?,
        };
        config.validate()?;
        Ok(config)
    }

    fn from_json(value: &str) -> Result<Self, String> {
        let value: Value = serde_json::from_str(value).map_err(|_| "invalid-config-json")?;
        Self::from_value(&value)
    }

    fn validate(&self) -> Result<(), String> {
        if self.enabled && !self.outlet_enabled && !self.inlet_enabled {
            return Err("enabled-without-direction".to_owned());
        }
        if self.outlet_enabled && !self.any_outlet_selected() {
            return Err("outlet-enabled-without-stream".to_owned());
        }
        if self.inlet_enabled && self.inlet_resolve_value.trim().is_empty() {
            return Err("inlet-resolve-value-empty".to_owned());
        }
        if !(1..=7).contains(&self.inlet_driver_slot) {
            return Err("inlet-driver-slot-reserved-or-out-of-range".to_owned());
        }
        if !self.inlet_sample_hold_seconds.is_finite()
            || !(0.033..=60.0).contains(&self.inlet_sample_hold_seconds)
        {
            return Err("inlet-sample-hold-out-of-range".to_owned());
        }
        Ok(())
    }

    fn any_outlet_selected(&self) -> bool {
        self.polar_hr
            || self.polar_rr
            || self.polar_acc
            || self.polar_ecg
            || self.controller_right_grip
            || self.headset_views
    }

    fn to_value(&self) -> Value {
        json!({
            "schema": PERSISTED_CONFIG_SCHEMA,
            "enabled": self.enabled,
            "outlet_enabled": self.outlet_enabled,
            "inlet_enabled": self.inlet_enabled,
            "stream_prefix": self.stream_prefix,
            "participant_id": self.participant_id,
            "session_id": self.session_id,
            "outlets": {
                "polar_hr": self.polar_hr,
                "polar_rr": self.polar_rr,
                "polar_acc": self.polar_acc,
                "polar_ecg": self.polar_ecg,
                "controller_right_grip": self.controller_right_grip,
                "headset_views": self.headset_views,
            },
            "inlet": {
                "resolve_by": self.inlet_resolve_by.marker_value(),
                "resolve_value": self.inlet_resolve_value,
                "driver_slot": self.inlet_driver_slot,
                "sample_hold_seconds": self.inlet_sample_hold_seconds,
                "recover": self.inlet_recover,
            }
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum LslInletResolveBy {
    SourceId,
    Name,
    Type,
}

impl LslInletResolveBy {
    fn parse(value: &str) -> Result<Self, String> {
        match value.trim() {
            "source_id" => Ok(Self::SourceId),
            "name" => Ok(Self::Name),
            "type" => Ok(Self::Type),
            _ => Err("invalid-inlet-resolve-by".to_owned()),
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::SourceId => "source_id",
            Self::Name => "name",
            Self::Type => "type",
        }
    }
}

#[derive(Clone, Debug)]
struct TimedInletSample {
    value01: f32,
    received_at: Instant,
    generation: u64,
}

#[derive(Debug)]
struct WorkerStatus {
    state: &'static str,
    reason: String,
    outlet_count: usize,
    inlet_state: &'static str,
    pushed: u64,
    pulled: u64,
    rejected_inlet: u64,
    last_effective_request_id: String,
}

impl Default for WorkerStatus {
    fn default() -> Self {
        Self {
            state: "disabled",
            reason: "default-disabled".to_owned(),
            outlet_count: 0,
            inlet_state: "disabled",
            pushed: 0,
            pulled: 0,
            rejected_inlet: 0,
            last_effective_request_id: "none".to_owned(),
        }
    }
}

struct RuntimeState {
    panel_available: bool,
    app_session_id: String,
    generation: u64,
    last_request_id: String,
    retained_request_ids: VecDeque<String>,
    config: LslPanelConfig,
    sender: Option<SyncSender<OutboundSample>>,
    stop: Arc<AtomicBool>,
    dropped: Arc<AtomicU64>,
    status: Arc<Mutex<WorkerStatus>>,
    inlet_samples: Arc<Mutex<[Option<TimedInletSample>; 8]>>,
}

impl Default for RuntimeState {
    fn default() -> Self {
        Self {
            panel_available: false,
            app_session_id: "not-started".to_owned(),
            generation: 0,
            last_request_id: "none".to_owned(),
            retained_request_ids: VecDeque::new(),
            config: LslPanelConfig::default(),
            sender: None,
            stop: Arc::new(AtomicBool::new(true)),
            dropped: Arc::new(AtomicU64::new(0)),
            status: Arc::new(Mutex::new(WorkerStatus::default())),
            inlet_samples: Arc::new(Mutex::new(std::array::from_fn(|_| None))),
        }
    }
}

static RUNTIME: OnceLock<Mutex<RuntimeState>> = OnceLock::new();

fn lock_runtime() -> MutexGuard<'static, RuntimeState> {
    RUNTIME
        .get_or_init(|| Mutex::new(RuntimeState::default()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[derive(Clone, Debug)]
enum OutboundSample {
    PolarHr { timestamp: f64, bpm: f32 },
    PolarRr { timestamp: f64, rr_ms: f32 },
    PolarAcc { timestamp: f64, xyz_mg: [f32; 3] },
    PolarEcg { timestamp: f64, microvolts: f32 },
    ControllerRightGrip { timestamp: f64, values: [f32; 9] },
    HeadsetViews { timestamp: f64, values: [f32; 14] },
}

#[cfg(target_os = "android")]
pub(crate) fn initialize(app: &android_activity::AndroidApp, panel_available: bool) {
    let persisted = if panel_available {
        read_persisted_config(app).ok()
    } else {
        None
    };
    initialize_with_config(panel_available, persisted.as_deref());
    marker(&format!(
        "status=initialized lslPanelControlled={} libraryLinked={} defaultNetworkInert={}",
        panel_available,
        lsl_android::library_linked(),
        !lock_runtime().config.enabled,
    ));
    if panel_available {
        let enabled = lock_runtime().config.enabled;
        crate::lsl_transport_bridge::set_multicast_lock(app, enabled);
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) fn initialize(_app: &(), panel_available: bool) {
    initialize_with_config(panel_available, None);
}

fn initialize_with_config(panel_available: bool, persisted: Option<&str>) {
    let config_result = persisted
        .filter(|value| !value.trim().is_empty())
        .map(LslPanelConfig::from_json)
        .unwrap_or_else(|| Ok(LslPanelConfig::default()));
    let mut state = lock_runtime();
    stop_workers(&mut state);
    state.panel_available = panel_available;
    state.app_session_id = new_session_id();
    state.generation = 0;
    state.last_request_id = "startup".to_owned();
    state.retained_request_ids.clear();
    state.inlet_samples = Arc::new(Mutex::new(std::array::from_fn(|_| None)));
    match config_result {
        Ok(config) if panel_available => activate_locked(&mut state, config, "startup"),
        Ok(_) => {
            state.config = LslPanelConfig::default();
            update_status(
                &state.status,
                "unavailable",
                "feature-not-packaged",
                0,
                "disabled",
            );
        }
        Err(reason) => {
            state.config = LslPanelConfig::default();
            update_status(&state.status, "rejected", &reason, 0, "disabled");
            marker("status=persisted-config-rejected reason=invalid-or-damaged");
        }
    }
}

pub(crate) fn shutdown() {
    let mut state = lock_runtime();
    stop_workers(&mut state);
    update_status(&state.status, "stopped", "app-session-ended", 0, "stopped");
}

fn stop_workers(state: &mut RuntimeState) {
    state.stop.store(true, Ordering::Release);
    state.sender = None;
    state.stop = Arc::new(AtomicBool::new(true));
}

pub(crate) fn apply_command_json(command_json: &str) -> String {
    let command: Value = match serde_json::from_str(command_json) {
        Ok(value) => value,
        Err(_) => return response("rejected", "invalid-json", None),
    };
    let Some(object) = command.as_object() else {
        return response("rejected", "command-must-be-object", None);
    };
    if object.get("schema").and_then(Value::as_str) != Some(PANEL_COMMAND_SCHEMA) {
        return response("rejected", "unsupported-schema", None);
    }
    let operation = object
        .get("operation")
        .and_then(Value::as_str)
        .unwrap_or("");
    if operation == "status" {
        return status_json();
    }
    let request_id = match request_id(object.get("request_id")) {
        Ok(value) => value,
        Err(reason) => return response("rejected", &reason, None),
    };
    let requested_session = object
        .get("app_session_id")
        .and_then(Value::as_str)
        .unwrap_or("");
    let requested_generation = object
        .get("generation")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let config = match operation {
        "apply" => object
            .get("config")
            .ok_or_else(|| "missing-config".to_owned())
            .and_then(LslPanelConfig::from_value),
        "disable" => {
            let mut config = lock_runtime().config.clone();
            config.enabled = false;
            config.outlet_enabled = false;
            config.inlet_enabled = false;
            Ok(config)
        }
        "reset" => Ok(LslPanelConfig::default()),
        _ => Err("unsupported-operation".to_owned()),
    };
    let config = match config {
        Ok(config) => config,
        Err(reason) => return response("rejected", &reason, None),
    };
    let mut state = lock_runtime();
    if !state.panel_available {
        return response_locked(&state, "rejected", "feature-not-packaged", None);
    }
    if requested_session != state.app_session_id {
        return response_locked(&state, "rejected", "app-session-mismatch", None);
    }
    if requested_generation == 0 || requested_generation <= state.generation {
        return response_locked(&state, "rejected", "stale-or-zero-generation", None);
    }
    if state
        .retained_request_ids
        .iter()
        .any(|retained| retained == &request_id)
    {
        return response_locked(&state, "rejected", "replayed-request-id", None);
    }
    if config.enabled && !lsl_android::library_linked() {
        return response_locked(&state, "rejected", "liblsl-not-linked", None);
    }
    state.generation = requested_generation;
    state.last_request_id = request_id.clone();
    state.retained_request_ids.push_back(request_id.clone());
    while state.retained_request_ids.len() > MAX_RETAINED_REQUEST_IDS {
        state.retained_request_ids.pop_front();
    }
    marker(&format!(
        "status=request-accepted appSessionId={} generation={} requestId={} enabled={} outletEnabled={} inletEnabled={}",
        token(&state.app_session_id),
        state.generation,
        token(&request_id),
        config.enabled,
        config.outlet_enabled,
        config.inlet_enabled,
    ));
    activate_locked(&mut state, config.clone(), &request_id);
    response_locked(&state, "accepted", "none", Some(config.to_value()))
}

fn activate_locked(state: &mut RuntimeState, config: LslPanelConfig, request_id: &str) {
    stop_workers(state);
    state.config = config.clone();
    state.dropped = Arc::new(AtomicU64::new(0));
    state.status = Arc::new(Mutex::new(WorkerStatus::default()));
    if let Ok(mut samples) = state.inlet_samples.lock() {
        *samples = std::array::from_fn(|_| None);
    }
    if !config.enabled {
        update_status(
            &state.status,
            "disabled",
            "requested-disabled",
            0,
            "disabled",
        );
        set_last_effective_request_id(&state.status, request_id);
        marker(&format!(
            "status=request-effective appSessionId={} generation={} requestId={} enabled=false outletCount=0 inletState=disabled",
            token(&state.app_session_id), state.generation, token(request_id)
        ));
        return;
    }
    let stop = Arc::new(AtomicBool::new(false));
    state.stop = Arc::clone(&stop);
    update_status(
        &state.status,
        "starting",
        "none",
        0,
        if config.inlet_enabled {
            "starting"
        } else {
            "disabled"
        },
    );
    if config.outlet_enabled {
        let (sender, receiver) = mpsc::sync_channel(OUTBOUND_QUEUE_CAPACITY);
        state.sender = Some(sender);
        let worker_config = config.clone();
        let worker_stop = Arc::clone(&stop);
        let worker_status = Arc::clone(&state.status);
        let worker_request_id = request_id.to_owned();
        let app_session_id = state.app_session_id.clone();
        let generation = state.generation;
        let spawn = thread::Builder::new()
            .name("rusty-quest-lsl-panel-out".to_owned())
            .spawn(move || {
                run_outlet_worker(
                    worker_config,
                    receiver,
                    worker_stop,
                    worker_status,
                    app_session_id,
                    generation,
                    worker_request_id,
                )
            });
        if spawn.is_err() {
            state.sender = None;
            update_status(
                &state.status,
                "error",
                "outlet-thread-spawn-failed",
                0,
                "disabled",
            );
            return;
        }
    }
    if config.inlet_enabled {
        let worker_config = config.clone();
        let worker_stop = Arc::clone(&stop);
        let worker_status = Arc::clone(&state.status);
        let inlet_samples = Arc::clone(&state.inlet_samples);
        let generation = state.generation;
        let _ = thread::Builder::new()
            .name("rusty-quest-lsl-panel-in".to_owned())
            .spawn(move || {
                run_inlet_worker(
                    worker_config,
                    worker_stop,
                    worker_status,
                    inlet_samples,
                    generation,
                )
            });
    }
    if !config.outlet_enabled {
        update_status(&state.status, "effective", "none", 0, "starting");
        set_last_effective_request_id(&state.status, request_id);
        marker(&format!(
            "status=request-effective appSessionId={} generation={} requestId={} enabled=true outletCount=0 inletState=starting",
            token(&state.app_session_id), state.generation, token(request_id)
        ));
    }
}

struct OutletSet {
    polar_hr: Option<LslOutlet>,
    polar_rr: Option<LslOutlet>,
    polar_acc: Option<LslOutlet>,
    polar_ecg: Option<LslOutlet>,
    controller_right_grip: Option<LslOutlet>,
    headset_views: Option<LslOutlet>,
}

impl OutletSet {
    fn create(config: &LslPanelConfig) -> Result<Self, String> {
        let stem = stream_stem(config);
        let source = source_stem(config);
        Ok(Self {
            polar_hr: create_if(
                config.polar_hr,
                &stem,
                &source,
                "polar_hr",
                "PolarHeartRate",
                1,
                0.0,
            )?,
            polar_rr: create_if(
                config.polar_rr,
                &stem,
                &source,
                "polar_rr",
                "PolarRR",
                1,
                0.0,
            )?,
            polar_acc: create_if(
                config.polar_acc,
                &stem,
                &source,
                "polar_acc",
                "PolarACC",
                3,
                200.0,
            )?,
            polar_ecg: create_if(
                config.polar_ecg,
                &stem,
                &source,
                "polar_ecg",
                "PolarECG",
                1,
                130.0,
            )?,
            controller_right_grip: create_if(
                config.controller_right_grip,
                &stem,
                &source,
                "controller_right_grip",
                "ControllerPose",
                9,
                0.0,
            )?,
            headset_views: create_if(
                config.headset_views,
                &stem,
                &source,
                "headset_views",
                "HeadsetViews",
                14,
                0.0,
            )?,
        })
    }

    fn count(&self) -> usize {
        [
            self.polar_hr.is_some(),
            self.polar_rr.is_some(),
            self.polar_acc.is_some(),
            self.polar_ecg.is_some(),
            self.controller_right_grip.is_some(),
            self.headset_views.is_some(),
        ]
        .into_iter()
        .filter(|selected| *selected)
        .count()
    }

    fn push(&self, sample: OutboundSample) -> Result<bool, String> {
        match sample {
            OutboundSample::PolarHr { timestamp, bpm } => {
                push_if(&self.polar_hr, &[bpm], timestamp)
            }
            OutboundSample::PolarRr { timestamp, rr_ms } => {
                push_if(&self.polar_rr, &[rr_ms], timestamp)
            }
            OutboundSample::PolarAcc { timestamp, xyz_mg } => {
                push_if(&self.polar_acc, &xyz_mg, timestamp)
            }
            OutboundSample::PolarEcg {
                timestamp,
                microvolts,
            } => push_if(&self.polar_ecg, &[microvolts], timestamp),
            OutboundSample::ControllerRightGrip { timestamp, values } => {
                push_if(&self.controller_right_grip, &values, timestamp)
            }
            OutboundSample::HeadsetViews { timestamp, values } => {
                push_if(&self.headset_views, &values, timestamp)
            }
        }
    }
}

fn create_if(
    enabled: bool,
    stem: &str,
    source: &str,
    suffix: &str,
    stream_type: &str,
    channels: i32,
    nominal_srate: f64,
) -> Result<Option<LslOutlet>, String> {
    if !enabled {
        return Ok(None);
    }
    LslOutlet::create(
        &format!("{stem}_{suffix}"),
        stream_type,
        channels,
        nominal_srate,
        LslChannelFormat::Float32,
        &format!("{source}:{suffix}"),
    )
    .map(Some)
}

fn push_if(outlet: &Option<LslOutlet>, sample: &[f32], timestamp: f64) -> Result<bool, String> {
    let Some(outlet) = outlet else {
        return Ok(false);
    };
    outlet.push_f32_at(sample, timestamp, false)?;
    Ok(true)
}

fn run_outlet_worker(
    config: LslPanelConfig,
    receiver: mpsc::Receiver<OutboundSample>,
    stop: Arc<AtomicBool>,
    status: Arc<Mutex<WorkerStatus>>,
    app_session_id: String,
    generation: u64,
    request_id: String,
) {
    let outlets = match OutletSet::create(&config) {
        Ok(outlets) => outlets,
        Err(reason) => {
            update_status(&status, "error", &reason, 0, "disabled");
            marker(&format!(
                "status=outlet-create-error reason={}",
                token(&reason)
            ));
            return;
        }
    };
    let outlet_count = outlets.count();
    if let Ok(mut snapshot) = status.lock() {
        snapshot.state = "effective";
        snapshot.reason = "none".to_owned();
        snapshot.outlet_count = outlet_count;
        snapshot.last_effective_request_id = request_id.clone();
    }
    marker(&format!(
        "status=request-effective appSessionId={} generation={} requestId={} enabled=true outletCount={} inletState={}",
        token(&app_session_id),
        generation,
        token(&request_id),
        outlet_count,
        if config.inlet_enabled { "starting" } else { "disabled" }
    ));
    while !stop.load(Ordering::Acquire) {
        match receiver.recv_timeout(Duration::from_millis(50)) {
            Ok(sample) => match outlets.push(sample) {
                Ok(true) => {
                    if let Ok(mut snapshot) = status.lock() {
                        snapshot.pushed = snapshot.pushed.saturating_add(1);
                    }
                }
                Ok(false) => {}
                Err(reason) => {
                    if let Ok(mut snapshot) = status.lock() {
                        snapshot.state = "degraded";
                        snapshot.reason = reason.clone();
                    }
                }
            },
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
    marker(&format!(
        "status=outlet-worker-stopped generation={generation}"
    ));
}

fn run_inlet_worker(
    config: LslPanelConfig,
    stop: Arc<AtomicBool>,
    status: Arc<Mutex<WorkerStatus>>,
    inlet_samples: Arc<Mutex<[Option<TimedInletSample>; 8]>>,
    generation: u64,
) {
    while !stop.load(Ordering::Acquire) {
        if let Ok(mut snapshot) = status.lock() {
            snapshot.inlet_state = "resolving";
        }
        let inlet = match LslInlet::resolve_and_open(
            config.inlet_resolve_by.marker_value(),
            &config.inlet_resolve_value,
            1.0,
            config.inlet_recover,
        ) {
            Ok(inlet) => inlet,
            Err(reason) => {
                if let Ok(mut snapshot) = status.lock() {
                    snapshot.inlet_state = "waiting";
                    snapshot.reason = reason;
                }
                wait_with_stop(&stop, Duration::from_millis(500));
                continue;
            }
        };
        if let Ok(mut snapshot) = status.lock() {
            snapshot.inlet_state = "resolved";
        }
        marker(&format!(
            "status=inlet-resolved generation={} resolveBy={} driverSlot={}",
            generation,
            config.inlet_resolve_by.marker_value(),
            config.inlet_driver_slot
        ));
        let mut buffer = [0.0_f32; INLET_BUFFER_CHANNELS];
        while !stop.load(Ordering::Acquire) {
            match inlet.pull_f32(&mut buffer, 0.25) {
                Ok(Some(_timestamp)) => {
                    let value01 = buffer[0];
                    if !value01.is_finite() || !(0.0..=1.0).contains(&value01) {
                        if let Ok(mut snapshot) = status.lock() {
                            snapshot.rejected_inlet = snapshot.rejected_inlet.saturating_add(1);
                        }
                        continue;
                    }
                    if let Ok(mut samples) = inlet_samples.lock() {
                        samples[config.inlet_driver_slot] = Some(TimedInletSample {
                            value01,
                            received_at: Instant::now(),
                            generation,
                        });
                    }
                    if let Ok(mut snapshot) = status.lock() {
                        snapshot.pulled = snapshot.pulled.saturating_add(1);
                    }
                }
                Ok(None) => {}
                Err(reason) => {
                    if let Ok(mut snapshot) = status.lock() {
                        snapshot.inlet_state = "lost";
                        snapshot.reason = reason;
                    }
                    break;
                }
            }
        }
        if !config.inlet_recover {
            break;
        }
    }
    if let Ok(mut snapshot) = status.lock() {
        snapshot.inlet_state = "stopped";
    }
}

fn wait_with_stop(stop: &AtomicBool, duration: Duration) {
    let deadline = Instant::now() + duration;
    while !stop.load(Ordering::Acquire) && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(25));
    }
}

pub(crate) fn controller_outlet_requested() -> bool {
    RUNTIME
        .get()
        .and_then(|runtime| runtime.try_lock().ok())
        .is_some_and(|state| {
            state.config.enabled
                && state.config.outlet_enabled
                && state.config.controller_right_grip
                && state.sender.is_some()
        })
}

pub(crate) fn apply_inlet_driver_values(values01: &mut [f32; 8]) -> usize {
    let Some(runtime) = RUNTIME.get() else {
        return 0;
    };
    let Ok(state) = runtime.try_lock() else {
        return 0;
    };
    if !state.config.enabled || !state.config.inlet_enabled {
        return 0;
    }
    let hold = Duration::from_secs_f32(state.config.inlet_sample_hold_seconds);
    let Ok(samples) = state.inlet_samples.try_lock() else {
        return 0;
    };
    let now = Instant::now();
    let mut active = 0;
    for (slot, sample) in samples.iter().enumerate().skip(1) {
        if let Some(sample) = sample {
            if sample.generation == state.generation
                && now.saturating_duration_since(sample.received_at) <= hold
            {
                values01[slot] = sample.value01;
                active += 1;
            }
        }
    }
    active
}

pub(crate) fn submit_polar_hr(bpm: u32) {
    enqueue(|timestamp| OutboundSample::PolarHr {
        timestamp,
        bpm: bpm as f32,
    });
}

pub(crate) fn submit_polar_rr(rr_ms: f32) {
    if rr_ms.is_finite() && rr_ms > 0.0 {
        enqueue(|timestamp| OutboundSample::PolarRr { timestamp, rr_ms });
    }
}

pub(crate) fn submit_polar_acc(xyz_mg: [f32; 3]) {
    if xyz_mg.iter().all(|value| value.is_finite()) {
        enqueue(|timestamp| OutboundSample::PolarAcc { timestamp, xyz_mg });
    }
}

pub(crate) fn submit_polar_ecg(microvolts: i32) {
    enqueue(|timestamp| OutboundSample::PolarEcg {
        timestamp,
        microvolts: microvolts as f32,
    });
}

pub(crate) fn submit_controller_right_grip(
    position_m: [f32; 3],
    orientation_xyzw: [f32; 4],
    active: bool,
    tracked: bool,
) {
    if !position_m
        .iter()
        .chain(orientation_xyzw.iter())
        .all(|value| value.is_finite())
    {
        return;
    }
    let values = [
        position_m[0],
        position_m[1],
        position_m[2],
        orientation_xyzw[0],
        orientation_xyzw[1],
        orientation_xyzw[2],
        orientation_xyzw[3],
        if active { 1.0 } else { 0.0 },
        if tracked { 1.0 } else { 0.0 },
    ];
    enqueue(|timestamp| OutboundSample::ControllerRightGrip { timestamp, values });
}

pub(crate) fn submit_headset_views(left: [f32; 7], right: [f32; 7]) {
    if !left
        .iter()
        .chain(right.iter())
        .all(|value| value.is_finite())
    {
        return;
    }
    let mut values = [0.0_f32; 14];
    values[..7].copy_from_slice(&left);
    values[7..].copy_from_slice(&right);
    enqueue(|timestamp| OutboundSample::HeadsetViews { timestamp, values });
}

fn enqueue(build: impl FnOnce(f64) -> OutboundSample) {
    let Some(runtime) = RUNTIME.get() else {
        return;
    };
    let Ok(state) = runtime.try_lock() else {
        return;
    };
    let Some(sender) = state.sender.as_ref() else {
        return;
    };
    let sample = build(lsl_android::local_clock());
    match sender.try_send(sample) {
        Ok(()) => {}
        Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {
            state.dropped.fetch_add(1, Ordering::Relaxed);
        }
    }
}

pub(crate) fn status_json() -> String {
    let state = lock_runtime();
    response_locked(&state, "status", "none", None)
}

fn response(status: &str, reason: &str, config: Option<Value>) -> String {
    let state = lock_runtime();
    response_locked(&state, status, reason, config)
}

fn response_locked(
    state: &RuntimeState,
    response_status: &str,
    response_reason: &str,
    config: Option<Value>,
) -> String {
    let worker = state
        .status
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    json!({
        "schema": STATUS_SCHEMA,
        "response_status": response_status,
        "response_reason": response_reason,
        "panel_available": state.panel_available,
        "library_linked": lsl_android::library_linked(),
        "app_session_id": state.app_session_id,
        "generation": state.generation,
        "last_request_id": state.last_request_id,
        "effective": {
            "state": worker.state,
            "reason": worker.reason,
            "outlet_count": worker.outlet_count,
            "inlet_state": worker.inlet_state,
            "samples_pushed": worker.pushed,
            "samples_pulled": worker.pulled,
            "samples_dropped": state.dropped.load(Ordering::Relaxed),
            "inlet_samples_rejected": worker.rejected_inlet,
            "last_effective_request_id": worker.last_effective_request_id,
        },
        "config": config.unwrap_or_else(|| state.config.to_value()),
    })
    .to_string()
}

fn update_status(
    status: &Arc<Mutex<WorkerStatus>>,
    state: &'static str,
    reason: &str,
    outlet_count: usize,
    inlet_state: &'static str,
) {
    if let Ok(mut snapshot) = status.lock() {
        snapshot.state = state;
        snapshot.reason = reason.to_owned();
        snapshot.outlet_count = outlet_count;
        snapshot.inlet_state = inlet_state;
    }
}

fn set_last_effective_request_id(status: &Arc<Mutex<WorkerStatus>>, request_id: &str) {
    if let Ok(mut snapshot) = status.lock() {
        snapshot.last_effective_request_id = request_id.to_owned();
    }
}

fn stream_stem(config: &LslPanelConfig) -> String {
    format!(
        "{}_{}_{}",
        token(&config.stream_prefix),
        token(&config.participant_id),
        token(&config.session_id)
    )
}

fn source_stem(config: &LslPanelConfig) -> String {
    format!(
        "io.github.mesmerprism.viscereality:{}:{}",
        token(&config.participant_id),
        token(&config.session_id)
    )
}

fn token(value: &str) -> String {
    let token = value
        .trim()
        .chars()
        .filter(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.')
        })
        .take(MAX_TOKEN_LENGTH)
        .collect::<String>();
    if token.is_empty() {
        "none".to_owned()
    } else {
        token
    }
}

fn request_id(value: Option<&Value>) -> Result<String, String> {
    let value = value
        .and_then(Value::as_str)
        .ok_or_else(|| "missing-request-id".to_owned())?;
    if !(16..=128).contains(&value.len())
        || !value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.')
        })
    {
        return Err("invalid-request-id".to_owned());
    }
    Ok(value.to_owned())
}

fn bool_field(
    object: &serde_json::Map<String, Value>,
    name: &str,
    default: bool,
) -> Result<bool, String> {
    match object.get(name) {
        None => Ok(default),
        Some(Value::Bool(value)) => Ok(*value),
        Some(_) => Err(format!("invalid-{name}")),
    }
}

fn nested_bool(
    object: Option<&serde_json::Map<String, Value>>,
    name: &str,
    default: bool,
) -> Result<bool, String> {
    object.map_or(Ok(default), |object| bool_field(object, name, default))
}

fn token_field(value: Option<&Value>, default: &str, label: &str) -> Result<String, String> {
    let value = value.and_then(Value::as_str).unwrap_or(default).trim();
    if value.is_empty()
        || value.len() > MAX_TOKEN_LENGTH
        || !value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.')
        })
    {
        return Err(format!("invalid-{label}"));
    }
    Ok(value.to_owned())
}

fn usize_field(
    object: Option<&serde_json::Map<String, Value>>,
    name: &str,
    default: usize,
    min: usize,
    max: usize,
) -> Result<usize, String> {
    let Some(value) = object.and_then(|object| object.get(name)) else {
        return Ok(default);
    };
    let value = value
        .as_u64()
        .and_then(|value| usize::try_from(value).ok())
        .ok_or_else(|| format!("invalid-{name}"))?;
    if !(min..=max).contains(&value) {
        return Err(format!("invalid-{name}"));
    }
    Ok(value)
}

fn f32_field(
    object: Option<&serde_json::Map<String, Value>>,
    name: &str,
    default: f32,
    min: f32,
    max: f32,
) -> Result<f32, String> {
    let Some(value) = object.and_then(|object| object.get(name)) else {
        return Ok(default);
    };
    let value = value
        .as_f64()
        .map(|value| value as f32)
        .ok_or_else(|| format!("invalid-{name}"))?;
    if !value.is_finite() || !(min..=max).contains(&value) {
        return Err(format!("invalid-{name}"));
    }
    Ok(value)
}

fn new_session_id() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let mixed = nanos ^ ((std::process::id() as u128) << 64);
    format!("{mixed:032x}")
}

fn marker(detail: &str) {
    #[cfg(target_os = "android")]
    crate::marker("lsl-panel-runtime", detail);
    #[cfg(not(target_os = "android"))]
    let _ = detail;
}

#[cfg(target_os = "android")]
fn read_persisted_config(app: &android_activity::AndroidApp) -> Result<String, String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JString, JValue},
        JavaVM,
    };

    const STORE_CLASS: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.LslPanelConfigStore";
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<String> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let class_loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let class_loader: JClassLoader = env.cast_local::<JClassLoader>(class_loader)?;
        let class_name = env.new_string(STORE_CLASS)?;
        let store_class = JClass::for_name_with_loader(env, class_name, true, class_loader)?;
        let value = env
            .call_static_method(
                store_class,
                jni_str!("readFromNative"),
                jni_sig!("(Landroid/app/Activity;)Ljava/lang/String;"),
                &[JValue::Object(&activity)],
            )?
            .l()?;
        let value: JString = env.cast_local::<JString>(value)?;
        Ok(value.to_string())
    })
    .map_err(|error| format!("read-persisted-config:{error}"))
}

#[cfg(target_os = "android")]
fn jni_response(mut env: jni::EnvUnowned, response: String) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeApplyLslTransportCommand(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    command: jni::objects::JString,
) -> jni::sys::jstring {
    let response = match env
        .with_env(|env| command.try_to_string(env))
        .into_outcome()
    {
        jni::Outcome::Ok(command) => apply_command_json(&command),
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => {
            response("rejected", "invalid-jni-string", None)
        }
    };
    jni_response(env, response)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeReadLslTransportStatus(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    jni_response(env, status_json())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn enabled_config() -> Value {
        json!({
            "schema": PERSISTED_CONFIG_SCHEMA,
            "enabled": true,
            "outlet_enabled": true,
            "inlet_enabled": true,
            "stream_prefix": "viscereality",
            "participant_id": "P001",
            "session_id": "S001",
            "outlets": {
                "polar_hr": true,
                "polar_rr": true,
                "polar_acc": true,
                "polar_ecg": true,
                "controller_right_grip": true,
                "headset_views": true
            },
            "inlet": {
                "resolve_by": "source_id",
                "resolve_value": "sender.P001.S001",
                "driver_slot": 2,
                "sample_hold_seconds": 0.5,
                "recover": true
            }
        })
    }

    #[test]
    fn default_is_packaged_but_network_inert() {
        let config = LslPanelConfig::default();
        assert!(!config.enabled);
        assert!(!config.outlet_enabled);
        assert!(!config.inlet_enabled);
        assert!(config.any_outlet_selected());
    }

    #[test]
    fn parses_closed_output_and_inlet_contract() {
        let config = LslPanelConfig::from_value(&enabled_config()).expect("config");
        assert_eq!(config.participant_id, "P001");
        assert_eq!(config.inlet_driver_slot, 2);
        assert_eq!(config.inlet_resolve_by, LslInletResolveBy::SourceId);
        assert_eq!(config.to_value()["outlets"]["headset_views"], true);
    }

    #[test]
    fn rejects_reserved_breath_slot_and_nonfinite_hold() {
        let mut value = enabled_config();
        value["inlet"]["driver_slot"] = json!(0);
        assert_eq!(
            LslPanelConfig::from_value(&value).unwrap_err(),
            "invalid-driver_slot"
        );
        value["inlet"]["driver_slot"] = json!(2);
        value["inlet"]["sample_hold_seconds"] = json!("NaN");
        assert_eq!(
            LslPanelConfig::from_value(&value).unwrap_err(),
            "invalid-sample_hold_seconds"
        );
    }

    #[test]
    fn rejects_enabled_without_direction_or_selected_outlet() {
        let mut value = enabled_config();
        value["outlet_enabled"] = json!(false);
        value["inlet_enabled"] = json!(false);
        assert_eq!(
            LslPanelConfig::from_value(&value).unwrap_err(),
            "enabled-without-direction"
        );
        value["outlet_enabled"] = json!(true);
        for name in [
            "polar_hr",
            "polar_rr",
            "polar_acc",
            "polar_ecg",
            "controller_right_grip",
            "headset_views",
        ] {
            value["outlets"][name] = json!(false);
        }
        assert_eq!(
            LslPanelConfig::from_value(&value).unwrap_err(),
            "outlet-enabled-without-stream"
        );
    }

    #[test]
    fn headset_and_controller_samples_have_stable_channel_counts() {
        let mut headset = [0.0_f32; 14];
        headset[..7].copy_from_slice(&[1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0]);
        headset[7..].copy_from_slice(&[4.0, 5.0, 6.0, 0.0, 0.0, 0.0, 1.0]);
        let controller = [1.0_f32, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0];
        assert_eq!(headset.len(), 14);
        assert_eq!(controller.len(), 9);
    }

    #[test]
    fn stale_inlet_sample_does_not_apply() {
        let state = RuntimeState {
            panel_available: true,
            generation: 7,
            config: LslPanelConfig {
                enabled: true,
                inlet_enabled: true,
                inlet_sample_hold_seconds: 0.033,
                ..LslPanelConfig::default()
            },
            inlet_samples: Arc::new(Mutex::new(std::array::from_fn(|_| None))),
            ..RuntimeState::default()
        };
        state.inlet_samples.lock().unwrap()[2] = Some(TimedInletSample {
            value01: 0.75,
            received_at: Instant::now() - Duration::from_secs(1),
            generation: 7,
        });
        let values = [0.0; 8];
        let samples = state.inlet_samples.lock().unwrap();
        let now = Instant::now();
        let active = samples
            .iter()
            .enumerate()
            .skip(1)
            .filter(|(_, sample)| {
                sample.as_ref().is_some_and(|sample| {
                    sample.generation == state.generation
                        && now.duration_since(sample.received_at)
                            <= Duration::from_secs_f32(state.config.inlet_sample_hold_seconds)
                })
            })
            .count();
        assert_eq!(active, 0);
        assert_eq!(values[2], 0.0);
    }

    #[test]
    fn panel_requests_are_session_generation_and_request_id_fenced() {
        initialize_with_config(true, None);
        let initial = serde_json::from_str::<Value>(&status_json()).expect("initial status");
        let session = initial["app_session_id"].as_str().expect("app session");

        let reset = json!({
            "schema": PANEL_COMMAND_SCHEMA,
            "operation": "reset",
            "request_id": "00112233-4455-6677-8899-aabbccddeeff",
            "app_session_id": session,
            "generation": 1
        });
        let accepted = serde_json::from_str::<Value>(&apply_command_json(&reset.to_string()))
            .expect("accepted response");
        assert_eq!(accepted["response_status"], "accepted");
        assert_eq!(accepted["generation"], 1);
        assert_eq!(
            accepted["effective"]["last_effective_request_id"],
            "00112233-4455-6677-8899-aabbccddeeff"
        );

        let mut replay = reset.clone();
        replay["generation"] = json!(2);
        let replayed = serde_json::from_str::<Value>(&apply_command_json(&replay.to_string()))
            .expect("replay response");
        assert_eq!(replayed["response_status"], "rejected");
        assert_eq!(replayed["response_reason"], "replayed-request-id");
        assert_eq!(replayed["generation"], 1);

        let mut stale = reset.clone();
        stale["request_id"] = json!("11223344-5566-7788-99aa-bbccddeeff00");
        let stale = serde_json::from_str::<Value>(&apply_command_json(&stale.to_string()))
            .expect("stale response");
        assert_eq!(stale["response_reason"], "stale-or-zero-generation");

        initialize_with_config(true, None);
        let restarted = serde_json::from_str::<Value>(&status_json()).expect("restarted status");
        let restarted_session = restarted["app_session_id"]
            .as_str()
            .expect("restarted app session");
        assert_ne!(restarted_session, session);
        assert_eq!(restarted["generation"], 0);
        assert_eq!(restarted["config"]["enabled"], false);

        let old_session = serde_json::from_str::<Value>(&apply_command_json(&reset.to_string()))
            .expect("old-session response");
        assert_eq!(old_session["response_reason"], "app-session-mismatch");
        assert_eq!(old_session["generation"], 0);

        shutdown();
    }
}
