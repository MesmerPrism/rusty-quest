//! Hostess/Manifold PMB breath stream adapter for the native renderer.

use std::{
    io::{Read, Write},
    net::TcpStream,
    sync::{Arc, Mutex},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use serde_json::{json, Value};

use crate::projection_target_state::{
    BreathBridgeMode, ProjectionTargetBreathState, ProjectionTargetInput, ProjectionTargetSettings,
};

const MANIFOLD_COMMAND_SCHEMA: &str = "rusty.manifold.command.envelope.v1";
const CLIENT_ID: &str = "rusty-quest-native-renderer";

#[derive(Debug)]
pub(crate) struct ManifoldBreathBridge {
    settings: ProjectionTargetSettings,
    latest_input: Arc<Mutex<Option<ProjectionTargetInput>>>,
    synthetic: Option<SyntheticBreathStream>,
    _thread: Option<thread::JoinHandle<()>>,
}

impl ManifoldBreathBridge {
    pub(crate) fn start(settings: ProjectionTargetSettings) -> Option<Self> {
        match settings.breath_bridge_mode {
            BreathBridgeMode::Disabled => None,
            BreathBridgeMode::Synthetic => Some(Self {
                settings: settings.clone(),
                latest_input: Arc::new(Mutex::new(None)),
                synthetic: Some(SyntheticBreathStream::new(
                    settings.breath_synthetic_period_seconds,
                )),
                _thread: None,
            }),
            BreathBridgeMode::ManifoldState | BreathBridgeMode::ManifoldValue => {
                let latest_input = Arc::new(Mutex::new(None));
                let thread_latest_input = Arc::clone(&latest_input);
                let thread_settings = settings.clone();
                let thread = thread::Builder::new()
                    .name("rusty-quest-breath-bridge".to_string())
                    .spawn(move || {
                        run_bridge_thread(thread_settings, thread_latest_input);
                    });
                match thread {
                    Ok(thread) => Some(Self {
                        settings,
                        latest_input,
                        synthetic: None,
                        _thread: Some(thread),
                    }),
                    Err(error) => {
                        marker(
                            "projection-target",
                            format!(
                                "status=bridge-thread-error breathBridgeMode={} reason={}",
                                settings.breath_bridge_mode.marker_value(),
                                crate::sanitize(&error.to_string())
                            ),
                        );
                        None
                    }
                }
            }
        }
    }

    pub(crate) fn poll_input(
        &mut self,
        dt_seconds: f32,
        frame_count: u64,
    ) -> Option<ProjectionTargetInput> {
        if let Some(synthetic) = self.synthetic.as_mut() {
            return synthetic.poll(dt_seconds);
        }
        let mut latest = match self.latest_input.lock() {
            Ok(latest) => latest,
            Err(_) => {
                if frame_count == 0 || frame_count % 120 == 0 {
                    marker(
                        "projection-target",
                        "status=breath-bridge-lock-error breathBridgeLatestInput=false",
                    );
                }
                return None;
            }
        };
        latest.take()
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "breathBridgeMode={} breathBridgeTransport={} breathStateStream={} breathValueStream={} breathBridgeHighRateJsonPayload=false",
            self.settings.breath_bridge_mode.marker_value(),
            if self.synthetic.is_some() {
                "synthetic"
            } else {
                "manifold-websocket"
            },
            crate::projection_target_state::marker_token(&self.settings.breath_state_stream),
            crate::projection_target_state::marker_token(&self.settings.breath_value_stream),
        )
    }
}

fn run_bridge_thread(
    settings: ProjectionTargetSettings,
    latest_input: Arc<Mutex<Option<ProjectionTargetInput>>>,
) {
    marker(
        "projection-target",
        format!(
            "status=breath-bridge-starting breathBridgeMode={} transport=manifold-websocket host={} port={} path={} highRateSamplesViaAndroidProperties=false",
            settings.breath_bridge_mode.marker_value(),
            crate::projection_target_state::marker_token(&settings.manifold_broker_host),
            settings.manifold_broker_port,
            crate::projection_target_state::marker_token(&settings.manifold_broker_path),
        ),
    );

    loop {
        match connect_and_subscribe(&settings) {
            Ok(mut socket) => {
                marker(
                    "projection-target",
                    format!(
                        "status=breath-bridge-connected breathBridgeMode={} streamSubscriptions={}",
                        settings.breath_bridge_mode.marker_value(),
                        subscription_count(settings.breath_bridge_mode)
                    ),
                );
                while let Ok(Some(message)) = socket.recv_json(Duration::from_millis(500)) {
                    if let Some(input) = parse_manifold_breath_event(
                        &message,
                        &settings.breath_state_stream,
                        &settings.breath_value_stream,
                    ) {
                        if let Ok(mut latest) = latest_input.lock() {
                            *latest = Some(input);
                        }
                    }
                }
            }
            Err(error) => {
                marker(
                    "projection-target",
                    format!(
                        "status=breath-bridge-connect-warning breathBridgeMode={} reason={}",
                        settings.breath_bridge_mode.marker_value(),
                        crate::sanitize(&error)
                    ),
                );
            }
        }
        thread::sleep(Duration::from_millis(500));
    }
}

fn connect_and_subscribe(settings: &ProjectionTargetSettings) -> Result<BrokerSocket, String> {
    let mut socket = BrokerSocket::connect(
        &settings.manifold_broker_host,
        settings.manifold_broker_port,
        &settings.manifold_broker_path,
        Duration::from_secs(2),
    )?;
    for stream in streams_for_mode(settings) {
        socket.send_json(&broker_command_message(
            "subscribe",
            json!({ "stream": stream }),
        ))?;
    }
    Ok(socket)
}

fn streams_for_mode(settings: &ProjectionTargetSettings) -> Vec<&str> {
    match settings.breath_bridge_mode {
        BreathBridgeMode::ManifoldState => vec![settings.breath_state_stream.as_str()],
        BreathBridgeMode::ManifoldValue => vec![settings.breath_value_stream.as_str()],
        BreathBridgeMode::Synthetic | BreathBridgeMode::Disabled => Vec::new(),
    }
}

fn subscription_count(mode: BreathBridgeMode) -> usize {
    match mode {
        BreathBridgeMode::ManifoldState | BreathBridgeMode::ManifoldValue => 1,
        BreathBridgeMode::Disabled | BreathBridgeMode::Synthetic => 0,
    }
}

fn broker_command_message(command: &str, params: Value) -> Value {
    json!({
        "type": "command",
        "schema": MANIFOLD_COMMAND_SCHEMA,
        "request_id": format!("rusty-quest-native-{}", command.replace('.', "-")),
        "command": command,
        "params": params,
        "client_id": CLIENT_ID,
        "app_package": "rusty-quest-native-renderer",
    })
}

pub(crate) fn parse_manifold_breath_event(
    event: &Value,
    state_stream_id: &str,
    value_stream_id: &str,
) -> Option<ProjectionTargetInput> {
    let payload = event.get("payload").and_then(Value::as_object);
    let stream_id = event
        .get("stream")
        .or_else(|| event.get("stream_id"))
        .and_then(Value::as_str)
        .or_else(|| {
            payload.and_then(|payload| {
                payload
                    .get("stream_id")
                    .or_else(|| payload.get("stream"))
                    .and_then(Value::as_str)
            })
        })?;
    let sequence_id = event
        .get("sequence_id")
        .and_then(Value::as_u64)
        .or_else(|| payload.and_then(|payload| payload.get("sequence_id").and_then(Value::as_u64)));
    if stream_id == state_stream_id {
        let state = payload
            .and_then(|payload| payload.get("state").or_else(|| payload.get("phase")))
            .and_then(Value::as_str)
            .map(ProjectionTargetBreathState::from_text)
            .unwrap_or(ProjectionTargetBreathState::Unknown);
        return Some(ProjectionTargetInput::BreathState { state, sequence_id });
    }
    if stream_id == value_stream_id {
        let value01 = payload
            .and_then(|payload| {
                payload
                    .get("value01")
                    .or_else(|| payload.get("target01"))
                    .or_else(|| payload.get("volume01"))
            })
            .and_then(Value::as_f64)
            .unwrap_or(0.0) as f32;
        return Some(ProjectionTargetInput::BreathValue {
            value01,
            sequence_id,
        });
    }
    None
}

#[derive(Debug)]
struct SyntheticBreathStream {
    elapsed_seconds: f32,
    period_seconds: f32,
    sequence_id: u64,
    last_state: ProjectionTargetBreathState,
}

impl SyntheticBreathStream {
    fn new(period_seconds: f32) -> Self {
        Self {
            elapsed_seconds: 0.0,
            period_seconds: period_seconds.max(0.25),
            sequence_id: 0,
            last_state: ProjectionTargetBreathState::Unknown,
        }
    }

    fn poll(&mut self, dt_seconds: f32) -> Option<ProjectionTargetInput> {
        if dt_seconds.is_finite() && dt_seconds > 0.0 {
            self.elapsed_seconds += dt_seconds.min(1.0);
        }
        let phase = (self.elapsed_seconds % self.period_seconds) / self.period_seconds;
        let state = if phase < 0.5 {
            ProjectionTargetBreathState::Inhale
        } else {
            ProjectionTargetBreathState::Exhale
        };
        if state == self.last_state {
            return None;
        }
        self.last_state = state;
        self.sequence_id = self.sequence_id.saturating_add(1);
        Some(ProjectionTargetInput::BreathState {
            state,
            sequence_id: Some(self.sequence_id),
        })
    }
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
        let key = "cnVzdHktcXVlc3QtbmF0aXZl";
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
            0x8 => Err("broker websocket closed".to_string()),
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
            return Err("websocket handshake exceeded 64 KiB".to_string());
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

#[cfg(test)]
mod tests {
    use super::{parse_manifold_breath_event, SyntheticBreathStream};
    use crate::projection_target_state::{
        ProjectionTargetBreathState, ProjectionTargetInput, ProjectionTargetSettings,
    };
    use serde_json::json;

    #[test]
    fn parses_breath_state_event_aliases() {
        let event = json!({
            "type": "stream_event",
            "stream": "stream.breath.state",
            "sequence_id": 42,
            "payload": {
                "schema": "rusty.manifold.breath.state.v1",
                "phase": "inhale"
            }
        });
        let input =
            parse_manifold_breath_event(&event, "stream.breath.state", "stream.breath.state.value")
                .expect("state event parsed");
        match input {
            ProjectionTargetInput::BreathState { state, sequence_id } => {
                assert_eq!(state, ProjectionTargetBreathState::Inhale);
                assert_eq!(sequence_id, Some(42));
            }
            _ => panic!("expected breath state input"),
        }
    }

    #[test]
    fn parses_processed_breath_value_event() {
        let event = json!({
            "type": "stream_event",
            "payload": {
                "stream_id": "stream.breath.state.value",
                "sequence_id": 7,
                "value01": 0.75
            }
        });
        let input =
            parse_manifold_breath_event(&event, "stream.breath.state", "stream.breath.state.value")
                .expect("value event parsed");
        match input {
            ProjectionTargetInput::BreathValue {
                value01,
                sequence_id,
            } => {
                assert!((value01 - 0.75).abs() < 0.000_001);
                assert_eq!(sequence_id, Some(7));
            }
            _ => panic!("expected breath value input"),
        }
    }

    #[test]
    fn synthetic_stream_generates_state_transitions() {
        let mut stream = SyntheticBreathStream::new(2.0);
        assert!(matches!(
            stream.poll(0.0),
            Some(ProjectionTargetInput::BreathState {
                state: ProjectionTargetBreathState::Inhale,
                ..
            })
        ));
        assert!(stream.poll(0.25).is_none());
        assert!(matches!(
            stream.poll(0.80),
            Some(ProjectionTargetInput::BreathState {
                state: ProjectionTargetBreathState::Exhale,
                ..
            })
        ));
    }

    #[test]
    fn default_settings_keep_expected_stream_ids() {
        let settings = ProjectionTargetSettings::default();
        assert_eq!(settings.breath_state_stream, "stream.breath.state");
        assert_eq!(settings.breath_value_stream, "stream.breath.state.value");
    }
}
