//! Manifold scalar stream adapter for generic private-particle driver slots.

use std::{
    io::{Read, Write},
    net::TcpStream,
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use serde_json::{json, Map, Value};

use crate::native_renderer_properties::{
    PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_HOST,
    PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_PATH,
    PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_PORT,
    PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_ENABLED,
    PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_HIGH_RATE_JSON_PAYLOAD,
    PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_ROUTES,
    PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_SAMPLE_HOLD_SECONDS,
};
use crate::native_renderer_property_values::{bool_value, f32_clamped_value, u32_value};

pub(crate) const PRIVATE_PARTICLE_DRIVER_SLOT_COUNT: usize = 8;

const MANIFOLD_COMMAND_SCHEMA: &str = "rusty.manifold.command.envelope.v1";
const CLIENT_ID: &str = "rusty-quest-native-renderer-private-particles";
const DEFAULT_BROKER_HOST: &str = "127.0.0.1";
const DEFAULT_BROKER_PORT: u16 = 8765;
const DEFAULT_BROKER_PATH: &str = "/manifold/v1/events";
const DEFAULT_SAMPLE_HOLD_SECONDS: f32 = 1.0;

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct ManifoldScalarDriverBridgeSettings {
    pub(crate) enabled: bool,
    pub(crate) broker_host: String,
    pub(crate) broker_port: u16,
    pub(crate) broker_path: String,
    pub(crate) routes: Vec<ManifoldScalarDriverRoute>,
    pub(crate) sample_hold_seconds: f32,
    pub(crate) high_rate_json_payload: bool,
}

impl ManifoldScalarDriverBridgeSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let enabled = bool_value(
            lookup(PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_ENABLED),
            false,
        );
        let broker_host = string_value(
            lookup(PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_HOST),
            DEFAULT_BROKER_HOST,
        );
        let broker_port = u16::try_from(u32_value(
            lookup(PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_PORT),
            u32::from(DEFAULT_BROKER_PORT),
            1,
            u32::from(u16::MAX),
        ))
        .unwrap_or(DEFAULT_BROKER_PORT);
        let broker_path = string_value(
            lookup(PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_PATH),
            DEFAULT_BROKER_PATH,
        );
        let routes = lookup(PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_ROUTES)
            .map(|value| parse_route_list(&value))
            .unwrap_or_default();
        let sample_hold_seconds = f32_clamped_value(
            lookup(PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_SAMPLE_HOLD_SECONDS),
            DEFAULT_SAMPLE_HOLD_SECONDS,
            0.033,
            60.0,
        );
        let high_rate_json_payload = bool_value(
            lookup(PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_HIGH_RATE_JSON_PAYLOAD),
            false,
        );

        Self {
            enabled,
            broker_host,
            broker_port,
            broker_path,
            routes,
            sample_hold_seconds,
            high_rate_json_payload,
        }
    }

    #[cfg(target_os = "android")]
    pub(crate) fn load_from_android_properties() -> Self {
        Self::from_property_lookup(android_property)
    }

    #[cfg(not(target_os = "android"))]
    pub(crate) fn load_from_android_properties() -> Self {
        Self::from_property_lookup(|_| None)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct ManifoldScalarDriverRoute {
    pub(crate) stream_id: String,
    pub(crate) driver_slot: usize,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct ManifoldScalarDriverSample {
    pub(crate) stream_id: String,
    pub(crate) driver_slot: usize,
    pub(crate) value01: f32,
    pub(crate) sequence_id: Option<u64>,
}

#[derive(Clone, Debug)]
struct TimedDriverSample {
    sample: ManifoldScalarDriverSample,
    received_at: Instant,
}

#[derive(Debug)]
pub(crate) struct ManifoldScalarDriverBridge {
    settings: ManifoldScalarDriverBridgeSettings,
    latest_samples: Arc<Mutex<[Option<TimedDriverSample>; PRIVATE_PARTICLE_DRIVER_SLOT_COUNT]>>,
    _thread: thread::JoinHandle<()>,
}

impl ManifoldScalarDriverBridge {
    pub(crate) fn start(settings: ManifoldScalarDriverBridgeSettings) -> Option<Self> {
        if !settings.enabled {
            return None;
        }
        if settings.high_rate_json_payload {
            marker(
                "private-particle-slot",
                "status=manifold-driver-disabled reason=high-rate-json-payload-disallowed",
            );
            return None;
        }
        if settings.routes.is_empty() {
            marker(
                "private-particle-slot",
                "status=manifold-driver-disabled reason=no-routes",
            );
            return None;
        }

        let latest_samples = Arc::new(Mutex::new(empty_latest_samples()));
        let thread_latest_samples = Arc::clone(&latest_samples);
        let thread_settings = settings.clone();
        match thread::Builder::new()
            .name("rusty-quest-manifold-driver".to_owned())
            .spawn(move || run_bridge_thread(thread_settings, thread_latest_samples))
        {
            Ok(thread) => {
                marker(
                    "private-particle-slot",
                    format!(
                        "status=manifold-driver-started routeCount={} brokerHost={} brokerPort={} brokerPath={} sampleHoldSeconds={:.3}",
                        settings.routes.len(),
                        marker_token(&settings.broker_host),
                        settings.broker_port,
                        marker_token(&settings.broker_path),
                        settings.sample_hold_seconds
                    ),
                );
                Some(Self {
                    settings,
                    latest_samples,
                    _thread: thread,
                })
            }
            Err(error) => {
                marker(
                    "private-particle-slot",
                    format!(
                        "status=manifold-driver-thread-error reason={}",
                        marker_token(&error.to_string())
                    ),
                );
                None
            }
        }
    }

    pub(crate) fn apply_to_driver_values(
        &self,
        driver_values01: &mut [f32; PRIVATE_PARTICLE_DRIVER_SLOT_COUNT],
    ) -> usize {
        let Ok(latest_samples) = self.latest_samples.lock() else {
            marker(
                "private-particle-slot",
                "status=manifold-driver-lock-error activeSamples=0",
            );
            return 0;
        };
        let now = Instant::now();
        let hold = Duration::from_secs_f32(self.settings.sample_hold_seconds.max(0.033));
        let mut active_count = 0usize;
        for timed in latest_samples.iter().flatten() {
            if now.duration_since(timed.received_at) <= hold {
                driver_values01[timed.sample.driver_slot] = timed.sample.value01;
                active_count += 1;
            }
        }
        active_count
    }
}

fn run_bridge_thread(
    settings: ManifoldScalarDriverBridgeSettings,
    latest_samples: Arc<Mutex<[Option<TimedDriverSample>; PRIVATE_PARTICLE_DRIVER_SLOT_COUNT]>>,
) {
    marker(
        "private-particle-slot",
        format!(
            "status=manifold-driver-connecting transport=manifold-websocket routeCount={} brokerHost={} brokerPort={} brokerPath={} highRateSamplesViaAndroidProperties=false",
            settings.routes.len(),
            marker_token(&settings.broker_host),
            settings.broker_port,
            marker_token(&settings.broker_path),
        ),
    );

    loop {
        match connect_and_subscribe(&settings) {
            Ok(mut socket) => {
                marker(
                    "private-particle-slot",
                    format!(
                        "status=manifold-driver-connected streamSubscriptions={}",
                        unique_stream_ids(&settings.routes).len()
                    ),
                );
                loop {
                    match socket.recv_json(Duration::from_millis(250)) {
                        Ok(Some(message)) => {
                            if let Some(sample) =
                                parse_manifold_scalar_driver_event(&message, &settings.routes)
                            {
                                if let Ok(mut latest) = latest_samples.lock() {
                                    let driver_slot = sample.driver_slot;
                                    latest[driver_slot] = Some(TimedDriverSample {
                                        sample,
                                        received_at: Instant::now(),
                                    });
                                }
                            }
                        }
                        Ok(None) => continue,
                        Err(_) => break,
                    }
                }
            }
            Err(error) => {
                marker(
                    "private-particle-slot",
                    format!(
                        "status=manifold-driver-connect-warning reason={}",
                        marker_token(&error)
                    ),
                );
            }
        }
        thread::sleep(Duration::from_millis(500));
    }
}

fn connect_and_subscribe(
    settings: &ManifoldScalarDriverBridgeSettings,
) -> Result<BrokerSocket, String> {
    let mut socket = BrokerSocket::connect(
        &settings.broker_host,
        settings.broker_port,
        &settings.broker_path,
        Duration::from_secs(2),
    )?;
    for stream in unique_stream_ids(&settings.routes) {
        socket.send_json(&broker_command_message(
            "subscribe",
            json!({ "stream": stream }),
        ))?;
    }
    Ok(socket)
}

fn broker_command_message(command: &str, params: Value) -> Value {
    json!({
        "type": "command",
        "schema": MANIFOLD_COMMAND_SCHEMA,
        "request_id": format!("rusty-quest-private-particles-{}", command.replace('.', "-")),
        "command": command,
        "params": params,
        "client_id": CLIENT_ID,
        "app_package": "rusty-quest-native-renderer",
    })
}

pub(crate) fn parse_manifold_scalar_driver_event(
    event: &Value,
    routes: &[ManifoldScalarDriverRoute],
) -> Option<ManifoldScalarDriverSample> {
    if event
        .get("type")
        .and_then(Value::as_str)
        .is_some_and(|message_type| message_type != "stream_event")
    {
        return None;
    }
    let candidates = object_candidates(event);
    let stream_id = string_field(&candidates, &["stream", "stream_id"])?;
    let route = routes.iter().find(|route| route.stream_id == stream_id)?;
    let value01 = f64_field(
        &candidates,
        &[
            "value01",
            "target01",
            "driver_value01",
            "normalized_value01",
            "normalizedValue01",
            "state01",
        ],
    )?;
    let sequence_id = u64_field(&candidates, &["sequence_id", "sequenceId"]);
    Some(ManifoldScalarDriverSample {
        stream_id: stream_id.to_owned(),
        driver_slot: route.driver_slot,
        value01: (value01 as f32).clamp(0.0, 1.0),
        sequence_id,
    })
}

pub(crate) fn parse_route_list(value: &str) -> Vec<ManifoldScalarDriverRoute> {
    value.split([';', '\n']).filter_map(parse_route).collect()
}

fn parse_route(route: &str) -> Option<ManifoldScalarDriverRoute> {
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
    Some(ManifoldScalarDriverRoute {
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
    normalized
        .parse::<usize>()
        .ok()
        .filter(|slot| *slot < PRIVATE_PARTICLE_DRIVER_SLOT_COUNT)
}

fn object_candidates(value: &Value) -> Vec<&Map<String, Value>> {
    let mut candidates = Vec::new();
    collect_object_candidates(value, 0, &mut candidates);
    candidates
}

fn collect_object_candidates<'a>(
    value: &'a Value,
    depth: u8,
    candidates: &mut Vec<&'a Map<String, Value>>,
) {
    let Some(object) = value.as_object() else {
        return;
    };
    candidates.push(object);
    if depth >= 2 {
        return;
    }
    for key in ["payload", "data", "value", "sample", "event"] {
        if let Some(child) = object.get(key) {
            collect_object_candidates(child, depth + 1, candidates);
        }
    }
}

fn string_field<'a>(objects: &'a [&'a Map<String, Value>], keys: &[&str]) -> Option<&'a str> {
    objects.iter().find_map(|object| {
        keys.iter()
            .find_map(|key| object.get(*key).and_then(Value::as_str))
    })
}

fn u64_field(objects: &[&Map<String, Value>], keys: &[&str]) -> Option<u64> {
    objects.iter().find_map(|object| {
        keys.iter()
            .find_map(|key| object.get(*key).and_then(Value::as_u64))
    })
}

fn f64_field(objects: &[&Map<String, Value>], keys: &[&str]) -> Option<f64> {
    objects.iter().find_map(|object| {
        keys.iter()
            .find_map(|key| object.get(*key).and_then(Value::as_f64))
    })
}

fn unique_stream_ids(routes: &[ManifoldScalarDriverRoute]) -> Vec<&str> {
    let mut streams = Vec::new();
    for route in routes {
        if !streams.iter().any(|stream| *stream == route.stream_id) {
            streams.push(route.stream_id.as_str());
        }
    }
    streams
}

fn empty_latest_samples() -> [Option<TimedDriverSample>; PRIVATE_PARTICLE_DRIVER_SLOT_COUNT] {
    std::array::from_fn(|_| None)
}

fn string_value(value: Option<String>, default_value: &str) -> String {
    value
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| default_value.to_owned())
}

struct BrokerSocket {
    stream: TcpStream,
}

impl BrokerSocket {
    fn connect(host: &str, port: u16, path: &str, timeout: Duration) -> Result<Self, String> {
        let mut stream = TcpStream::connect((host, port))
            .map_err(|error| format!("connect {host}:{port}: {error}"))?;
        stream
            .set_read_timeout(Some(timeout))
            .map_err(|error| format!("set websocket read timeout: {error}"))?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(|error| format!("set websocket write timeout: {error}"))?;
        let key = "cnVzdHktcXVlc3QtcHJpdmF0ZS1kcml2ZXI=";
        let request = format!(
            "GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        );
        stream
            .write_all(request.as_bytes())
            .map_err(|error| format!("send websocket handshake: {error}"))?;
        let response = read_http_response(&mut stream)?;
        if !response.starts_with("HTTP/1.1 101") && !response.starts_with("HTTP/1.0 101") {
            return Err(format!(
                "broker websocket handshake failed: {}",
                response.lines().next().unwrap_or("empty-response")
            ));
        }
        Ok(Self { stream })
    }

    fn send_json(&mut self, payload: &Value) -> Result<(), String> {
        let text = serde_json::to_vec(payload).map_err(|error| format!("encode JSON: {error}"))?;
        self.send_frame(0x1, &text)
    }

    fn recv_json(&mut self, timeout: Duration) -> Result<Option<Value>, String> {
        self.stream
            .set_read_timeout(Some(timeout))
            .map_err(|error| format!("set websocket poll timeout: {error}"))?;
        let (opcode, payload) = match self.recv_frame() {
            Ok(frame) => frame,
            Err(error) if error.contains("WouldBlock") || error.contains("timed out") => {
                return Ok(None);
            }
            Err(error) => return Err(error),
        };
        match opcode {
            0x1 => serde_json::from_slice(&payload)
                .map(Some)
                .map_err(|error| format!("decode broker JSON: {error}")),
            0x8 => Err("broker websocket closed".to_owned()),
            0x9 => {
                let _ = self.send_frame(0xA, &payload);
                Ok(None)
            }
            _ => Ok(None),
        }
    }

    fn send_frame(&mut self, opcode: u8, payload: &[u8]) -> Result<(), String> {
        let mut header = Vec::new();
        header.push(0x80 | (opcode & 0x0F));
        if payload.len() < 126 {
            header.push(0x80 | payload.len() as u8);
        } else if payload.len() <= u16::MAX as usize {
            header.push(0x80 | 126);
            header.extend_from_slice(&(payload.len() as u16).to_be_bytes());
        } else {
            header.push(0x80 | 127);
            header.extend_from_slice(&(payload.len() as u64).to_be_bytes());
        }
        let mask = websocket_mask();
        let masked = payload
            .iter()
            .enumerate()
            .map(|(index, value)| value ^ mask[index % 4])
            .collect::<Vec<_>>();
        self.stream
            .write_all(&header)
            .and_then(|_| self.stream.write_all(&mask))
            .and_then(|_| self.stream.write_all(&masked))
            .map_err(|error| format!("send websocket frame: {error}"))
    }

    fn recv_frame(&mut self) -> Result<(u8, Vec<u8>), String> {
        let header = read_exact(&mut self.stream, 2)?;
        let opcode = header[0] & 0x0F;
        let masked = (header[1] & 0x80) != 0;
        let mut length = (header[1] & 0x7F) as usize;
        if length == 126 {
            length =
                u16::from_be_bytes(read_exact(&mut self.stream, 2)?.try_into().unwrap()) as usize;
        } else if length == 127 {
            length =
                u64::from_be_bytes(read_exact(&mut self.stream, 8)?.try_into().unwrap()) as usize;
        }
        let mask = if masked {
            read_exact(&mut self.stream, 4)?
        } else {
            Vec::new()
        };
        let mut payload = if length == 0 {
            Vec::new()
        } else {
            read_exact(&mut self.stream, length)?
        };
        if masked {
            for (index, byte) in payload.iter_mut().enumerate() {
                *byte ^= mask[index % 4];
            }
        }
        Ok((opcode, payload))
    }
}

fn read_http_response(stream: &mut TcpStream) -> Result<String, String> {
    let mut data = Vec::new();
    let mut buffer = [0_u8; 512];
    while !data.windows(4).any(|window| window == b"\r\n\r\n") {
        let count = stream
            .read(&mut buffer)
            .map_err(|error| format!("read websocket handshake: {error}"))?;
        if count == 0 {
            break;
        }
        data.extend_from_slice(&buffer[..count]);
        if data.len() > 65_536 {
            return Err("websocket handshake exceeded 64 KiB".to_owned());
        }
    }
    Ok(String::from_utf8_lossy(&data).to_string())
}

fn read_exact(stream: &mut TcpStream, len: usize) -> Result<Vec<u8>, String> {
    let mut data = vec![0_u8; len];
    stream
        .read_exact(&mut data)
        .map_err(|error| format!("read websocket frame: {error:?}"))?;
    Ok(data)
}

fn websocket_mask() -> [u8; 4] {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .subsec_nanos();
    nanos.to_ne_bytes()
}

fn marker(channel: &str, detail: impl AsRef<str>) {
    #[cfg(target_os = "android")]
    crate::marker(channel, detail);
    #[cfg(not(target_os = "android"))]
    let _ = (channel, detail.as_ref());
}

fn marker_token(value: &str) -> String {
    crate::sanitize(value)
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
    use super::{
        parse_manifold_scalar_driver_event, parse_route_list, ManifoldScalarDriverBridge,
        ManifoldScalarDriverBridgeSettings, PRIVATE_PARTICLE_DRIVER_SLOT_COUNT,
    };
    use serde_json::json;
    use std::{
        io::{Read, Write},
        net::{TcpListener, TcpStream},
        sync::mpsc,
        thread,
        time::{Duration, Instant},
    };

    #[test]
    fn parses_route_list_aliases() {
        let routes = parse_route_list(
            "stream.synthetic_wave:driver1.value01; stream.radius->private_particles.driver0",
        );

        assert_eq!(routes.len(), 2);
        assert_eq!(routes[0].stream_id, "stream.synthetic_wave");
        assert_eq!(routes[0].driver_slot, 1);
        assert_eq!(routes[1].stream_id, "stream.radius");
        assert_eq!(routes[1].driver_slot, 0);
    }

    #[test]
    fn parses_scalar_f32_stream_event() {
        let routes = parse_route_list("stream.synthetic_wave:driver1.value01");
        let event = json!({
            "type": "stream_event",
            "payload": {
                "$schema": "rusty.manifold.sample.scalar_f32.v1",
                "stream_id": "stream.synthetic_wave",
                "sequence_id": 3,
                "value": 0.9,
                "value01": 0.9
            }
        });

        let sample = parse_manifold_scalar_driver_event(&event, &routes).unwrap();
        assert_eq!(sample.driver_slot, 1);
        assert_eq!(sample.sequence_id, Some(3));
        assert!((sample.value01 - 0.9).abs() < 0.000_001);
    }

    #[test]
    fn clamps_normalized_value() {
        let routes = parse_route_list("stream.synthetic_wave:driver2");
        let event = json!({
            "type": "stream_event",
            "stream": "stream.synthetic_wave",
            "sample": {
                "normalized_value01": 1.5
            }
        });

        let sample = parse_manifold_scalar_driver_event(&event, &routes).unwrap();
        assert_eq!(sample.driver_slot, 2);
        assert_eq!(sample.value01, 1.0);
    }

    #[test]
    fn ignores_unrouted_or_ack_messages() {
        let routes = parse_route_list("stream.synthetic_wave:driver1.value01");
        assert!(parse_manifold_scalar_driver_event(
            &json!({"type": "command_ack", "stream": "stream.synthetic_wave", "value01": 0.5}),
            &routes
        )
        .is_none());
        assert!(parse_manifold_scalar_driver_event(
            &json!({"type": "stream_event", "stream": "stream.other", "value01": 0.5}),
            &routes
        )
        .is_none());
    }

    #[test]
    fn settings_parse_properties() {
        let settings =
            ManifoldScalarDriverBridgeSettings::from_property_lookup(|name| {
                match name {
            "debug.rustyquest.native_renderer.private_particles.manifold_driver.enabled" => {
                Some("true".to_owned())
            }
            "debug.rustyquest.native_renderer.private_particles.manifold_driver.broker.host" => {
                Some("192.0.2.10".to_owned())
            }
            "debug.rustyquest.native_renderer.private_particles.manifold_driver.broker.port" => {
                Some("9000".to_owned())
            }
            "debug.rustyquest.native_renderer.private_particles.manifold_driver.routes" => {
                Some("stream.synthetic_wave:driver1".to_owned())
            }
            _ => None,
        }
            });

        assert!(settings.enabled);
        assert_eq!(settings.broker_host, "192.0.2.10");
        assert_eq!(settings.broker_port, 9000);
        assert_eq!(settings.routes.len(), 1);
    }

    #[test]
    fn live_loopback_stream_event_updates_driver_bank() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind loopback broker");
        let port = listener.local_addr().expect("loopback address").port();
        let (subscribe_tx, subscribe_rx) = mpsc::channel();

        thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept bridge client");
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .expect("set broker read timeout");
            stream
                .set_write_timeout(Some(Duration::from_secs(2)))
                .expect("set broker write timeout");

            let request = read_http_request(&mut stream);
            assert!(request.starts_with("GET /manifold/v1/events HTTP/1.1"));
            stream
                .write_all(
                    b"HTTP/1.1 101 Switching Protocols\r\n\
                    Upgrade: websocket\r\n\
                    Connection: Upgrade\r\n\
                    Sec-WebSocket-Accept: loopback-test\r\n\r\n",
                )
                .expect("write websocket handshake");

            let subscribe = read_client_text_frame(&mut stream);
            subscribe_tx
                .send(subscribe)
                .expect("publish subscribe frame");

            write_server_text_frame(
                &mut stream,
                &json!({
                    "type": "stream_event",
                    "schema": "rusty.manifold.stream.event.v1",
                    "stream": "stream.synthetic_wave",
                    "sequence_id": 42,
                    "payload": {
                        "$schema": "rusty.manifold.sample.scalar_f32.v1",
                        "stream_id": "stream.synthetic_wave",
                        "value": 0.75,
                        "value01": 0.75,
                        "sample_time_unix_ns": 1_000_000_u64,
                        "quality": "synthetic"
                    }
                })
                .to_string(),
            );
        });

        let settings = ManifoldScalarDriverBridgeSettings {
            enabled: true,
            broker_host: "127.0.0.1".to_owned(),
            broker_port: port,
            broker_path: "/manifold/v1/events".to_owned(),
            routes: parse_route_list("stream.synthetic_wave:driver1.value01"),
            sample_hold_seconds: 1.0,
            high_rate_json_payload: false,
        };
        let bridge = ManifoldScalarDriverBridge::start(settings).expect("bridge starts");

        let subscribe = subscribe_rx
            .recv_timeout(Duration::from_secs(2))
            .expect("receive subscribe command");
        assert!(subscribe.contains("\"command\":\"subscribe\""));
        assert!(subscribe.contains("\"stream\":\"stream.synthetic_wave\""));

        let deadline = Instant::now() + Duration::from_secs(2);
        while Instant::now() < deadline {
            let mut driver_values = [0.0; PRIVATE_PARTICLE_DRIVER_SLOT_COUNT];
            if bridge.apply_to_driver_values(&mut driver_values) == 1
                && (driver_values[1] - 0.75).abs() < 0.000_001
            {
                return;
            }
            thread::sleep(Duration::from_millis(10));
        }

        panic!("loopback stream event was not adopted into driver slot 1");
    }

    fn read_http_request(stream: &mut TcpStream) -> String {
        let mut data = Vec::new();
        let mut buffer = [0_u8; 256];
        while !data.windows(4).any(|window| window == b"\r\n\r\n") {
            let count = stream.read(&mut buffer).expect("read HTTP request");
            assert_ne!(count, 0, "bridge closed before HTTP request completed");
            data.extend_from_slice(&buffer[..count]);
        }
        String::from_utf8_lossy(&data).to_string()
    }

    fn read_client_text_frame(stream: &mut TcpStream) -> String {
        let mut header = [0_u8; 2];
        stream
            .read_exact(&mut header)
            .expect("read websocket frame header");
        assert_eq!(header[0] & 0x0F, 0x1);
        assert_ne!(header[1] & 0x80, 0, "client frames must be masked");

        let mut length = (header[1] & 0x7F) as usize;
        if length == 126 {
            let mut extended = [0_u8; 2];
            stream
                .read_exact(&mut extended)
                .expect("read websocket frame u16 length");
            length = u16::from_be_bytes(extended) as usize;
        } else if length == 127 {
            let mut extended = [0_u8; 8];
            stream
                .read_exact(&mut extended)
                .expect("read websocket frame u64 length");
            length = u64::from_be_bytes(extended) as usize;
        }

        let mut mask = [0_u8; 4];
        stream
            .read_exact(&mut mask)
            .expect("read websocket frame mask");
        let mut payload = vec![0_u8; length];
        stream
            .read_exact(&mut payload)
            .expect("read websocket frame payload");
        for (index, byte) in payload.iter_mut().enumerate() {
            *byte ^= mask[index % 4];
        }
        String::from_utf8(payload).expect("text frame is UTF-8")
    }

    fn write_server_text_frame(stream: &mut TcpStream, text: &str) {
        let payload = text.as_bytes();
        let mut header = Vec::new();
        header.push(0x81);
        if payload.len() < 126 {
            header.push(payload.len() as u8);
        } else if payload.len() <= u16::MAX as usize {
            header.push(126);
            header.extend_from_slice(&(payload.len() as u16).to_be_bytes());
        } else {
            header.push(127);
            header.extend_from_slice(&(payload.len() as u64).to_be_bytes());
        }
        stream
            .write_all(&header)
            .expect("write server frame header");
        stream
            .write_all(payload)
            .expect("write server frame payload");
    }
}
