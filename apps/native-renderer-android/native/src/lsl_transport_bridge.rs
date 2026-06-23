//! Low-rate native LSL sidecar for markers, clock telemetry, and bounded scalar inlets.

use std::{
    io::{Read, Write},
    net::TcpStream,
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use serde_json::{json, Value};

use crate::{
    embedded_manifold_broker_bridge::EmbeddedManifoldBrokerSettings,
    lsl_android::{self, LslChannelFormat, LslInlet, LslOutlet},
    native_app_settings::NativeAppSettingsDefaults,
    native_renderer_properties::{
        PROP_LSL_ENABLED, PROP_LSL_INLET_ENABLED, PROP_LSL_INLET_RECOVER_LOST_STREAMS,
        PROP_LSL_INLET_ROUTES, PROP_LSL_INLET_SAMPLE_HOLD_SECONDS, PROP_LSL_INLET_SOURCE_ID,
        PROP_LSL_INLET_STREAM_NAME, PROP_LSL_INLET_STREAM_TYPE, PROP_LSL_INLET_TEST_SOURCE_ENABLED,
        PROP_LSL_INLET_TEST_SOURCE_VALUE01, PROP_LSL_MULTICAST_LOCK_ENABLED,
        PROP_LSL_OUTLET_ENABLED, PROP_LSL_PARTICIPANT_ID, PROP_LSL_SESSION_ID,
        PROP_LSL_SOURCE_ID_PREFIX, PROP_LSL_STREAM_PREFIX,
    },
    native_renderer_property_values::{bool_value, f32_clamped_value},
};

const DEFAULT_STREAM_PREFIX: &str = "quest_pps";
const DEFAULT_PARTICIPANT_ID: &str = "P001";
const DEFAULT_SESSION_ID: &str = "S001";
const DEFAULT_SOURCE_ID_PREFIX: &str = "io.github.mesmerprism.rustyquest.native_renderer";
const DEFAULT_INLET_STREAM_NAME: &str = "quest_pps_test_scalar";
const DEFAULT_INLET_STREAM_TYPE: &str = "quest.test.scalar";
const DEFAULT_INLET_SOURCE_ID: &str = "rusty-quest:test-scalar:P001:S001";
const DEFAULT_INLET_ROUTES: &str = "stream.synthetic_wave:driver1.value01";
const DEFAULT_INLET_SAMPLE_HOLD_SECONDS: f32 = 1.0;
const DEFAULT_INLET_TEST_SOURCE_ENABLED: bool = false;
const DEFAULT_INLET_TEST_SOURCE_VALUE01: f32 = 0.66;
const MARKER_STREAM_TYPE: &str = "Markers";
const CLOCK_STREAM_TYPE: &str = "Clock";
const TELEMETRY_STREAM_TYPE: &str = "Telemetry";
const MANIFOLD_COMMAND_SCHEMA: &str = "rusty.manifold.command.envelope.v1";

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct LslTransportSettings {
    pub(crate) enabled: bool,
    pub(crate) outlet_enabled: bool,
    pub(crate) inlet_enabled: bool,
    pub(crate) multicast_lock_enabled: bool,
    pub(crate) stream_prefix: String,
    pub(crate) participant_id: String,
    pub(crate) session_id: String,
    pub(crate) source_id_prefix: String,
    pub(crate) inlet_stream_name: String,
    pub(crate) inlet_stream_type: String,
    pub(crate) inlet_source_id: String,
    pub(crate) inlet_routes: Vec<LslInletRoute>,
    pub(crate) inlet_sample_hold_seconds: f32,
    pub(crate) inlet_recover_lost_streams: bool,
    pub(crate) inlet_test_source_enabled: bool,
    pub(crate) inlet_test_source_value01: f32,
}

impl LslTransportSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let outlet_enabled = bool_value(lookup(PROP_LSL_OUTLET_ENABLED), false);
        let inlet_enabled = bool_value(lookup(PROP_LSL_INLET_ENABLED), false);
        let enabled = bool_value(lookup(PROP_LSL_ENABLED), outlet_enabled || inlet_enabled);
        let multicast_lock_enabled = bool_value(
            lookup(PROP_LSL_MULTICAST_LOCK_ENABLED),
            outlet_enabled || inlet_enabled,
        );
        let stream_prefix = string_value(lookup(PROP_LSL_STREAM_PREFIX), DEFAULT_STREAM_PREFIX);
        let participant_id = string_value(lookup(PROP_LSL_PARTICIPANT_ID), DEFAULT_PARTICIPANT_ID);
        let session_id = string_value(lookup(PROP_LSL_SESSION_ID), DEFAULT_SESSION_ID);
        let source_id_prefix =
            string_value(lookup(PROP_LSL_SOURCE_ID_PREFIX), DEFAULT_SOURCE_ID_PREFIX);
        let inlet_stream_name = string_value(
            lookup(PROP_LSL_INLET_STREAM_NAME),
            DEFAULT_INLET_STREAM_NAME,
        );
        let inlet_stream_type = string_value(
            lookup(PROP_LSL_INLET_STREAM_TYPE),
            DEFAULT_INLET_STREAM_TYPE,
        );
        let inlet_source_id =
            string_value(lookup(PROP_LSL_INLET_SOURCE_ID), DEFAULT_INLET_SOURCE_ID);
        let inlet_routes = lookup(PROP_LSL_INLET_ROUTES)
            .map(|value| parse_lsl_inlet_routes(&value))
            .unwrap_or_else(|| parse_lsl_inlet_routes(DEFAULT_INLET_ROUTES));
        let inlet_sample_hold_seconds = f32_clamped_value(
            lookup(PROP_LSL_INLET_SAMPLE_HOLD_SECONDS),
            DEFAULT_INLET_SAMPLE_HOLD_SECONDS,
            0.033,
            60.0,
        );
        let inlet_recover_lost_streams =
            bool_value(lookup(PROP_LSL_INLET_RECOVER_LOST_STREAMS), true);
        let inlet_test_source_enabled = bool_value(
            lookup(PROP_LSL_INLET_TEST_SOURCE_ENABLED),
            DEFAULT_INLET_TEST_SOURCE_ENABLED,
        );
        let inlet_test_source_value01 = f32_clamped_value(
            lookup(PROP_LSL_INLET_TEST_SOURCE_VALUE01),
            DEFAULT_INLET_TEST_SOURCE_VALUE01,
            0.0,
            1.0,
        );

        Self {
            enabled,
            outlet_enabled,
            inlet_enabled,
            multicast_lock_enabled,
            stream_prefix,
            participant_id,
            session_id,
            source_id_prefix,
            inlet_stream_name,
            inlet_stream_type,
            inlet_source_id,
            inlet_routes,
            inlet_sample_hold_seconds,
            inlet_recover_lost_streams,
            inlet_test_source_enabled,
            inlet_test_source_value01,
        }
    }

    #[cfg(target_os = "android")]
    pub(crate) fn load_from_android_properties_with_defaults(
        defaults: &NativeAppSettingsDefaults,
    ) -> Self {
        Self::from_property_lookup(|name| android_property(name).or_else(|| defaults.lookup(name)))
    }

    #[cfg(not(target_os = "android"))]
    pub(crate) fn load_from_android_properties_with_defaults(
        defaults: &NativeAppSettingsDefaults,
    ) -> Self {
        Self::from_property_lookup(|name| defaults.lookup(name))
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "lslEnabled={} lslOutletEnabled={} lslInletEnabled={} lslMulticastLockRequested={} lslStreamPrefix={} lslParticipantId={} lslSessionId={} lslInletRouteCount={} lslInletSampleHoldSeconds={:.3} lslInletTestSourceEnabled={} lslInletTestSourceValue01={:.3}",
            self.enabled,
            self.outlet_enabled,
            self.inlet_enabled,
            self.multicast_lock_enabled,
            marker_token(&self.stream_prefix),
            marker_token(&self.participant_id),
            marker_token(&self.session_id),
            self.inlet_routes.len(),
            self.inlet_sample_hold_seconds,
            self.inlet_test_source_enabled,
            self.inlet_test_source_value01
        )
    }

    pub(crate) fn stream_names(&self) -> LslStreamNames {
        LslStreamNames::new(self)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct LslInletRoute {
    pub(crate) stream_id: String,
    pub(crate) driver_slot: usize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct LslStreamNames {
    pub(crate) marker_name: String,
    pub(crate) clock_name: String,
    pub(crate) telemetry_name: String,
    pub(crate) marker_source_id: String,
    pub(crate) clock_source_id: String,
    pub(crate) telemetry_source_id: String,
}

impl LslStreamNames {
    fn new(settings: &LslTransportSettings) -> Self {
        let stem = format!(
            "{}_{}_{}",
            stream_token(&settings.stream_prefix),
            stream_token(&settings.participant_id),
            stream_token(&settings.session_id)
        );
        let source_stem = format!(
            "{}:{}:{}",
            settings.source_id_prefix.trim(),
            settings.participant_id.trim(),
            settings.session_id.trim()
        );
        Self {
            marker_name: format!("{stem}_markers"),
            clock_name: format!("{stem}_clock"),
            telemetry_name: format!("{stem}_telemetry"),
            marker_source_id: format!("{source_stem}:markers"),
            clock_source_id: format!("{source_stem}:clock"),
            telemetry_source_id: format!("{source_stem}:telemetry"),
        }
    }
}

#[cfg(target_os = "android")]
pub(crate) fn start_if_enabled(
    app: &android_activity::AndroidApp,
    settings: &LslTransportSettings,
    broker_settings: &EmbeddedManifoldBrokerSettings,
) {
    if !settings.enabled {
        marker(
            "lsl",
            format!("status=disabled {}", settings.marker_fields()),
        );
        return;
    }
    if settings.outlet_enabled || settings.inlet_enabled {
        acquire_multicast_lock(app, settings.multicast_lock_enabled);
    }
    start_threads(settings, broker_settings);
}

#[cfg(not(target_os = "android"))]
pub(crate) fn start_if_enabled(
    _app: &(),
    settings: &LslTransportSettings,
    broker_settings: &EmbeddedManifoldBrokerSettings,
) {
    let _ = (settings, broker_settings);
}

fn start_threads(
    settings: &LslTransportSettings,
    broker_settings: &EmbeddedManifoldBrokerSettings,
) {
    marker(
        "lsl",
        format!(
            "status=starting lslLibraryLinked={} {}",
            lsl_android::library_linked(),
            settings.marker_fields()
        ),
    );
    if !lsl_android::library_linked() {
        marker(
            "lsl",
            format!(
                "status=lsl-error reason=missing-liblsl lslLibraryLinked=false {}",
                settings.marker_fields()
            ),
        );
        return;
    }
    match lsl_android::library_info() {
        Ok(info) => marker(
            "lsl",
            format!(
                "status=library-loaded lslLibraryLinked=true lslLibraryInfo={}",
                marker_token(&info)
            ),
        ),
        Err(error) => marker(
            "lsl",
            format!("status=lsl-error reason={}", marker_token(&error)),
        ),
    }
    if settings.outlet_enabled {
        let settings = settings.clone();
        let _ = thread::Builder::new()
            .name("rusty-quest-lsl-outlet".to_owned())
            .spawn(move || run_outlet_thread(settings))
            .map_err(|error| {
                marker(
                    "lsl",
                    format!(
                        "status=thread-error component=outlet reason={}",
                        marker_token(&error.to_string())
                    ),
                )
            });
    }
    if settings.inlet_enabled {
        if !broker_settings.enabled {
            marker(
                "lsl",
                "status=inlet-disabled reason=embedded-broker-disabled transport=manifold-websocket",
            );
            return;
        }
        if settings.inlet_routes.is_empty() {
            marker("lsl", "status=inlet-disabled reason=no-routes");
            return;
        }
        let settings = settings.clone();
        let broker_settings = broker_settings.clone();
        let _ = thread::Builder::new()
            .name("rusty-quest-lsl-inlet".to_owned())
            .spawn(move || run_inlet_thread(settings, broker_settings))
            .map_err(|error| {
                marker(
                    "lsl",
                    format!(
                        "status=thread-error component=inlet reason={}",
                        marker_token(&error.to_string())
                    ),
                )
            });
    }
}

fn run_outlet_thread(settings: LslTransportSettings) {
    let names = settings.stream_names();
    let marker_outlet = match LslOutlet::create(
        &names.marker_name,
        MARKER_STREAM_TYPE,
        6,
        0.0,
        LslChannelFormat::String,
        &names.marker_source_id,
    ) {
        Ok(outlet) => outlet,
        Err(error) => {
            marker(
                "lsl",
                format!(
                    "status=lsl-error component=marker-outlet reason={}",
                    marker_token(&error)
                ),
            );
            return;
        }
    };
    let clock_outlet = match LslOutlet::create(
        &names.clock_name,
        CLOCK_STREAM_TYPE,
        3,
        4.0,
        LslChannelFormat::Float32,
        &names.clock_source_id,
    ) {
        Ok(outlet) => outlet,
        Err(error) => {
            marker(
                "lsl",
                format!(
                    "status=lsl-error component=clock-outlet reason={}",
                    marker_token(&error)
                ),
            );
            return;
        }
    };
    let telemetry_outlet = match LslOutlet::create(
        &names.telemetry_name,
        TELEMETRY_STREAM_TYPE,
        5,
        4.0,
        LslChannelFormat::Float32,
        &names.telemetry_source_id,
    ) {
        Ok(outlet) => outlet,
        Err(error) => {
            marker(
                "lsl",
                format!(
                    "status=lsl-error component=telemetry-outlet reason={}",
                    marker_token(&error)
                ),
            );
            return;
        }
    };
    marker(
        "lsl",
        format!(
            "status=outlet-created lslMarkerOutletCreated=true lslClockOutletCreated=true lslTelemetryOutletCreated=true markerStreamName={} clockStreamName={} telemetryStreamName={} {}",
            marker_token(&names.marker_name),
            marker_token(&names.clock_name),
            marker_token(&names.telemetry_name),
            settings.marker_fields()
        ),
    );

    let started_unix = now_unix_seconds();
    let mut sequence_id = 0_u64;
    loop {
        sequence_id = sequence_id.wrapping_add(1);
        let sequence = sequence_id.to_string();
        let local_clock = lsl_android::local_clock();
        let now_unix = now_unix_seconds();
        let marker_sample = [
            "rusty-quest-native-renderer",
            "sample-pushed",
            settings.participant_id.as_str(),
            settings.session_id.as_str(),
            sequence.as_str(),
            "bounded-low-rate",
        ];
        if let Err(error) = marker_outlet.push_strings(&marker_sample) {
            marker(
                "lsl",
                format!(
                    "status=lsl-error component=marker-push reason={}",
                    marker_token(&error)
                ),
            );
            break;
        }
        if let Err(error) =
            clock_outlet.push_f32(&[local_clock as f32, now_unix as f32, sequence_id as f32])
        {
            marker(
                "lsl",
                format!(
                    "status=lsl-error component=clock-push reason={}",
                    marker_token(&error)
                ),
            );
            break;
        }
        if let Err(error) = telemetry_outlet.push_f32(&[
            sequence_id as f32,
            (now_unix - started_unix) as f32,
            settings.outlet_enabled as u8 as f32,
            settings.inlet_enabled as u8 as f32,
            settings.inlet_routes.len() as f32,
        ]) {
            marker(
                "lsl",
                format!(
                    "status=lsl-error component=telemetry-push reason={}",
                    marker_token(&error)
                ),
            );
            break;
        }
        if sequence_id == 1 || sequence_id % 16 == 0 {
            marker(
                "lsl",
                format!(
                    "status=sample-pushed component=outlet lslOutletSampleCount={} lslMarkerOutletCreated=true lslClockOutletCreated=true",
                    sequence_id
                ),
            );
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn run_inlet_thread(
    settings: LslTransportSettings,
    broker_settings: EmbeddedManifoldBrokerSettings,
) {
    marker(
        "lsl",
        format!(
            "status=inlet-starting resolveSourceId={} resolveName={} resolveType={} routeCount={} brokerHost={} brokerPort={} brokerPath={}",
            marker_token(&settings.inlet_source_id),
            marker_token(&settings.inlet_stream_name),
            marker_token(&settings.inlet_stream_type),
            settings.inlet_routes.len(),
            marker_token(&broker_settings.bind_host),
            broker_settings.port,
            marker_token(&broker_settings.path),
        ),
    );
    if settings.inlet_test_source_enabled {
        let source_settings = settings.clone();
        let _ = thread::Builder::new()
            .name("rusty-quest-lsl-inlet-test-source".to_owned())
            .spawn(move || run_inlet_test_source_thread(source_settings))
            .map_err(|error| {
                marker(
                    "lsl",
                    format!(
                        "status=thread-error component=inlet-test-source reason={}",
                        marker_token(&error.to_string())
                    ),
                )
            });
        thread::sleep(Duration::from_millis(500));
    }
    let mut sequence_id = 0_u64;
    loop {
        marker(
            "lsl",
            format!(
                "status=inlet-resolving prop=source_id value={}",
                marker_token(&settings.inlet_source_id)
            ),
        );
        let inlet = match resolve_inlet(&settings) {
            Ok(inlet) => inlet,
            Err(error) => {
                marker(
                    "lsl",
                    format!(
                        "status=inlet-resolve-warning reason={}",
                        marker_token(&error)
                    ),
                );
                thread::sleep(Duration::from_millis(750));
                continue;
            }
        };
        marker(
            "lsl",
            format!(
                "status=inlet-resolved lslInletResolved=true sourceId={} sampleHoldSeconds={:.3}",
                marker_token(&settings.inlet_source_id),
                settings.inlet_sample_hold_seconds
            ),
        );
        let mut socket = BrokerJsonSocket::new(&broker_settings);
        let mut sample = [0.0_f32; 8];
        loop {
            match inlet.pull_f32(&mut sample, 0.25) {
                Ok(Some(timestamp)) => {
                    sequence_id = sequence_id.wrapping_add(1);
                    let value01 = sample[0].clamp(0.0, 1.0);
                    for route in &settings.inlet_routes {
                        let command =
                            build_lsl_inlet_publish_command(route, value01, sequence_id, timestamp);
                        match socket.send_json(&command, sequence_id) {
                            Ok(()) => marker(
                                "lsl",
                                format!(
                                    "status=inlet-sample-published stream={} driverSlot={} sampleCount={} value01={:.3}",
                                    marker_token(&route.stream_id),
                                    route.driver_slot,
                                    sequence_id,
                                    value01
                                ),
                            ),
                            Err(error) => {
                                marker(
                                    "lsl",
                                    format!(
                                        "status=inlet-publish-warning stream={} reason={}",
                                        marker_token(&route.stream_id),
                                        marker_token(&error)
                                    ),
                                );
                                socket.reset();
                            }
                        }
                    }
                }
                Ok(None) => continue,
                Err(error) => {
                    marker(
                        "lsl",
                        format!(
                            "status=inlet-lost reason={} recoverLostStreams={}",
                            marker_token(&error),
                            settings.inlet_recover_lost_streams
                        ),
                    );
                    break;
                }
            }
        }
        thread::sleep(Duration::from_millis(500));
    }
}

fn run_inlet_test_source_thread(settings: LslTransportSettings) {
    let value01 = settings.inlet_test_source_value01.clamp(0.0, 1.0);
    marker(
        "lsl",
        format!(
            "status=inlet-test-source-starting name={} type={} sourceId={} value01={:.3}",
            marker_token(&settings.inlet_stream_name),
            marker_token(&settings.inlet_stream_type),
            marker_token(&settings.inlet_source_id),
            value01
        ),
    );
    let outlet = match LslOutlet::create(
        &settings.inlet_stream_name,
        &settings.inlet_stream_type,
        1,
        0.0,
        LslChannelFormat::Float32,
        &settings.inlet_source_id,
    ) {
        Ok(outlet) => outlet,
        Err(error) => {
            marker(
                "lsl",
                format!(
                    "status=lsl-error component=inlet-test-source reason={}",
                    marker_token(&error)
                ),
            );
            return;
        }
    };
    marker(
        "lsl",
        format!(
            "status=inlet-test-source-created name={} type={} sourceId={} value01={:.3} periodMs=100",
            marker_token(&settings.inlet_stream_name),
            marker_token(&settings.inlet_stream_type),
            marker_token(&settings.inlet_source_id),
            value01
        ),
    );
    let mut sequence_id = 0_u64;
    loop {
        sequence_id = sequence_id.wrapping_add(1);
        if let Err(error) = outlet.push_f32(&[value01]) {
            marker(
                "lsl",
                format!(
                    "status=lsl-error component=inlet-test-source-push reason={}",
                    marker_token(&error)
                ),
            );
            break;
        }
        if sequence_id == 1 || sequence_id % 32 == 0 {
            marker(
                "lsl",
                format!(
                    "status=inlet-test-source-sample-pushed sampleCount={} value01={:.3}",
                    sequence_id, value01
                ),
            );
        }
        thread::sleep(Duration::from_millis(100));
    }
}

fn resolve_inlet(settings: &LslTransportSettings) -> Result<LslInlet, String> {
    if !settings.inlet_source_id.trim().is_empty() {
        if let Ok(inlet) = LslInlet::resolve_and_open(
            "source_id",
            &settings.inlet_source_id,
            1.0,
            settings.inlet_recover_lost_streams,
        ) {
            return Ok(inlet);
        }
    }
    if !settings.inlet_stream_name.trim().is_empty() {
        if let Ok(inlet) = LslInlet::resolve_and_open(
            "name",
            &settings.inlet_stream_name,
            1.0,
            settings.inlet_recover_lost_streams,
        ) {
            return Ok(inlet);
        }
    }
    if !settings.inlet_stream_type.trim().is_empty() {
        return LslInlet::resolve_and_open(
            "type",
            &settings.inlet_stream_type,
            1.0,
            settings.inlet_recover_lost_streams,
        );
    }
    Err("no LSL inlet resolve key configured".to_owned())
}

pub(crate) fn parse_lsl_inlet_routes(value: &str) -> Vec<LslInletRoute> {
    value
        .split([';', '\n'])
        .filter_map(parse_lsl_inlet_route)
        .collect()
}

fn parse_lsl_inlet_route(route: &str) -> Option<LslInletRoute> {
    let route = route.trim();
    if route.is_empty() {
        return None;
    }
    let (stream_id, slot_text) = route
        .split_once("->")
        .or_else(|| route.split_once(':'))
        .or_else(|| route.split_once('='))?;
    let stream_id = stream_id.trim();
    let driver_slot = parse_driver_slot(slot_text.trim())?;
    if stream_id.is_empty() || stream_id.chars().any(char::is_whitespace) {
        return None;
    }
    Some(LslInletRoute {
        stream_id: stream_id.to_owned(),
        driver_slot,
    })
}

fn parse_driver_slot(value: &str) -> Option<usize> {
    let mut normalized = value
        .trim()
        .to_ascii_lowercase()
        .replace("private_particles.", "")
        .replace("private-particles.", "");
    if let Some(stripped) = normalized.strip_suffix(".value01") {
        normalized = stripped.to_owned();
    }
    if let Some(stripped) = normalized.strip_prefix("driver") {
        normalized = stripped.to_owned();
    }
    normalized.parse::<usize>().ok().filter(|slot| *slot < 8)
}

pub(crate) fn build_lsl_inlet_publish_command(
    route: &LslInletRoute,
    value01: f32,
    sequence_id: u64,
    lsl_timestamp: f64,
) -> Value {
    json!({
        "type": "command",
        "schema": MANIFOLD_COMMAND_SCHEMA,
        "request_id": format!("rusty-quest-lsl-inlet-{}", sequence_id),
        "command": "publish_stream_event",
        "params": {
            "stream": route.stream_id,
            "sequence_id": sequence_id,
            "payload": {
                "schema": "rusty.manifold.sample.scalar_f32.v1",
                "stream_id": route.stream_id,
                "value": value01,
                "value01": value01,
                "quality": "lsl-inlet",
                "lsl_timestamp": lsl_timestamp,
                "driver_slot": route.driver_slot
            }
        },
        "client_id": "rusty-quest-native-renderer-lsl-inlet",
        "app_package": "io.github.mesmerprism.rustyquest.native_renderer",
    })
}

struct BrokerJsonSocket {
    settings: EmbeddedManifoldBrokerSettings,
    stream: Option<TcpStream>,
}

impl BrokerJsonSocket {
    fn new(settings: &EmbeddedManifoldBrokerSettings) -> Self {
        Self {
            settings: settings.clone(),
            stream: None,
        }
    }

    fn reset(&mut self) {
        self.stream = None;
    }

    fn send_json(&mut self, value: &Value, sequence_id: u64) -> Result<(), String> {
        if self.stream.is_none() {
            self.stream = Some(open_broker_websocket(&self.settings)?);
        }
        let text = serde_json::to_vec(value).map_err(|error| format!("encode JSON: {error}"))?;
        let frame = websocket_client_text_frame(&text, sequence_id);
        let stream = self
            .stream
            .as_mut()
            .ok_or_else(|| "missing_websocket_stream".to_owned())?;
        stream
            .write_all(&frame)
            .map_err(|error| format!("websocket_write_failed:{error}"))?;
        stream
            .flush()
            .map_err(|error| format!("websocket_flush_failed:{error}"))?;
        let _ = read_websocket_frame(stream);
        Ok(())
    }
}

fn open_broker_websocket(settings: &EmbeddedManifoldBrokerSettings) -> Result<TcpStream, String> {
    let mut stream = TcpStream::connect((settings.bind_host.as_str(), settings.port))
        .map_err(|error| format!("broker_connect_failed:{error}"))?;
    let timeout = Duration::from_millis(750);
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: {}:{}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: cnVzdHktcXVlc3QtbmF0aXZlLWxzbA==\r\nSec-WebSocket-Version: 13\r\n\r\n",
        settings.path, settings.bind_host, settings.port
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|error| format!("websocket_handshake_write_failed:{error}"))?;
    let response = read_http_response(&mut stream)?;
    if !response.starts_with("HTTP/1.1 101") && !response.starts_with("HTTP/1.0 101") {
        return Err(format!(
            "websocket_handshake_rejected:{}",
            response.lines().next().unwrap_or("empty-response")
        ));
    }
    Ok(stream)
}

fn websocket_client_text_frame(payload: &[u8], sequence_id: u64) -> Vec<u8> {
    let mut frame = Vec::with_capacity(payload.len() + 16);
    frame.push(0x81);
    let mask = websocket_mask(sequence_id);
    if payload.len() <= 125 {
        frame.push(0x80 | payload.len() as u8);
    } else if payload.len() <= u16::MAX as usize {
        frame.push(0x80 | 126);
        frame.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    } else {
        frame.push(0x80 | 127);
        frame.extend_from_slice(&(payload.len() as u64).to_be_bytes());
    }
    frame.extend_from_slice(&mask);
    for (index, byte) in payload.iter().enumerate() {
        frame.push(*byte ^ mask[index % 4]);
    }
    frame
}

fn websocket_mask(sequence_id: u64) -> [u8; 4] {
    let bytes = sequence_id
        .wrapping_mul(0x9E37_79B9_7F4A_7C15)
        .to_le_bytes();
    [bytes[0], bytes[2], bytes[5], bytes[7]]
}

fn read_http_response(stream: &mut TcpStream) -> Result<String, String> {
    let mut data = Vec::new();
    let mut byte = [0_u8; 1];
    while data.len() < 4096 {
        stream
            .read_exact(&mut byte)
            .map_err(|error| format!("websocket_handshake_read_failed:{error}"))?;
        data.push(byte[0]);
        if data.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    Ok(String::from_utf8_lossy(&data).to_string())
}

fn read_websocket_frame(stream: &mut TcpStream) -> Result<Vec<u8>, String> {
    let mut header = [0_u8; 2];
    stream
        .read_exact(&mut header)
        .map_err(|error| format!("websocket_frame_header_read_failed:{error}"))?;
    let masked = (header[1] & 0x80) != 0;
    let mut len = (header[1] & 0x7f) as usize;
    if len == 126 {
        let mut extended = [0_u8; 2];
        stream
            .read_exact(&mut extended)
            .map_err(|error| format!("websocket_frame_len16_read_failed:{error}"))?;
        len = u16::from_be_bytes(extended) as usize;
    } else if len == 127 {
        let mut extended = [0_u8; 8];
        stream
            .read_exact(&mut extended)
            .map_err(|error| format!("websocket_frame_len64_read_failed:{error}"))?;
        len = u64::from_be_bytes(extended).min(1024 * 1024) as usize;
    }
    let mut mask = [0_u8; 4];
    if masked {
        stream
            .read_exact(&mut mask)
            .map_err(|error| format!("websocket_frame_mask_read_failed:{error}"))?;
    }
    let mut payload = vec![0_u8; len.min(1024 * 1024)];
    stream
        .read_exact(&mut payload)
        .map_err(|error| format!("websocket_frame_payload_read_failed:{error}"))?;
    if masked {
        for (index, byte) in payload.iter_mut().enumerate() {
            *byte ^= mask[index % 4];
        }
    }
    Ok(payload)
}

#[cfg(target_os = "android")]
fn acquire_multicast_lock(app: &android_activity::AndroidApp, enabled: bool) {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };

    const CLASS_NAME: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.LslMulticastLockManager";

    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    let result = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
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
        let class_name = env.new_string(CLASS_NAME)?;
        let lock_class = JClass::for_name_with_loader(env, class_name, true, class_loader)?;
        env.call_static_method(
            lock_class,
            jni_str!("acquireFromNative"),
            jni_sig!("(Landroid/app/Activity;Z)V"),
            &[JValue::Object(&activity), JValue::Bool(enabled)],
        )?;
        Ok(())
    });
    if let Err(error) = result {
        marker(
            "lsl",
            format!(
                "status=multicast-lock-error reason={}",
                marker_token(&error.to_string())
            ),
        );
    }
}

fn string_value(value: Option<String>, default_value: &str) -> String {
    value
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| default_value.to_owned())
}

fn stream_token(value: &str) -> String {
    let token = value
        .trim()
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || character == '_' || character == '-' {
                character
            } else {
                '_'
            }
        })
        .collect::<String>();
    if token.is_empty() {
        "unknown".to_owned()
    } else {
        token
    }
}

fn marker_token(value: &str) -> String {
    crate::sanitize(value.trim())
}

fn now_unix_seconds() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs_f64())
        .unwrap_or_default()
}

fn marker(channel: &str, detail: impl AsRef<str>) {
    #[cfg(target_os = "android")]
    crate::marker(channel, detail);
    #[cfg(not(target_os = "android"))]
    let _ = (channel, detail.as_ref());
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

#[cfg(test)]
mod tests {
    use super::{build_lsl_inlet_publish_command, parse_lsl_inlet_routes, LslTransportSettings};
    use crate::lsl_android;

    #[test]
    fn defaults_to_disabled_outlet_and_inlet() {
        let settings = LslTransportSettings::from_property_lookup(|_| None);
        assert!(!settings.enabled);
        assert!(!settings.outlet_enabled);
        assert!(!settings.inlet_enabled);
        assert!(!settings.inlet_test_source_enabled);
        assert_eq!(settings.inlet_test_source_value01, 0.66);
        assert_eq!(settings.stream_prefix, "quest_pps");
        assert_eq!(settings.inlet_routes[0].stream_id, "stream.synthetic_wave");
        assert_eq!(settings.inlet_routes[0].driver_slot, 1);
    }

    #[test]
    fn parses_enabled_settings_and_stream_names() {
        let settings = LslTransportSettings::from_property_lookup(|name| match name {
            "debug.rustyquest.native_renderer.lsl.enabled" => Some("true".to_owned()),
            "debug.rustyquest.native_renderer.lsl.outlet.enabled" => Some("true".to_owned()),
            "debug.rustyquest.native_renderer.lsl.inlet.enabled" => Some("true".to_owned()),
            "debug.rustyquest.native_renderer.lsl.stream_prefix" => Some("quest pps".to_owned()),
            "debug.rustyquest.native_renderer.lsl.participant_id" => Some("P099".to_owned()),
            "debug.rustyquest.native_renderer.lsl.session_id" => Some("S777".to_owned()),
            "debug.rustyquest.native_renderer.lsl.inlet.test_source.enabled" => {
                Some("true".to_owned())
            }
            "debug.rustyquest.native_renderer.lsl.inlet.test_source.value01" => {
                Some("0.42".to_owned())
            }
            "debug.rustyquest.native_renderer.lsl.inlet.routes" => Some(
                "stream.alpha->driver2.value01;stream.beta:private_particles.driver0".to_owned(),
            ),
            _ => None,
        });
        assert!(settings.enabled);
        assert!(settings.outlet_enabled);
        assert!(settings.inlet_enabled);
        assert!(settings.inlet_test_source_enabled);
        assert_eq!(settings.inlet_test_source_value01, 0.42);
        assert_eq!(settings.inlet_routes.len(), 2);
        assert_eq!(settings.inlet_routes[0].driver_slot, 2);
        let names = settings.stream_names();
        assert_eq!(names.marker_name, "quest_pps_P099_S777_markers");
        assert_eq!(names.clock_name, "quest_pps_P099_S777_clock");
        assert_eq!(names.telemetry_name, "quest_pps_P099_S777_telemetry");
    }

    #[test]
    fn parses_lsl_inlet_routes() {
        let routes = parse_lsl_inlet_routes(
            "stream.synthetic_wave:driver1.value01\nstream.radius=private-particles.driver0",
        );
        assert_eq!(routes.len(), 2);
        assert_eq!(routes[0].stream_id, "stream.synthetic_wave");
        assert_eq!(routes[0].driver_slot, 1);
        assert_eq!(routes[1].stream_id, "stream.radius");
        assert_eq!(routes[1].driver_slot, 0);
    }

    #[test]
    fn builds_bounded_scalar_publish_command() {
        let route = parse_lsl_inlet_routes("stream.synthetic_wave:driver1.value01")
            .pop()
            .unwrap();
        let command = build_lsl_inlet_publish_command(&route, 0.66, 12, 99.25);
        assert_eq!(command["command"], "publish_stream_event");
        assert_eq!(command["schema"], "rusty.manifold.command.envelope.v1");
        assert_eq!(command["params"]["stream"], "stream.synthetic_wave");
        assert_eq!(
            command["params"]["payload"]["schema"],
            "rusty.manifold.sample.scalar_f32.v1"
        );
        assert_eq!(command["params"]["payload"]["driver_slot"], 1);
    }

    #[test]
    fn host_build_reports_lsl_unavailable() {
        if !cfg!(all(
            target_os = "android",
            rusty_quest_native_renderer_lsl_android
        )) {
            assert!(!lsl_android::library_linked());
            assert!(lsl_android::library_info().is_err());
        }
    }
}
