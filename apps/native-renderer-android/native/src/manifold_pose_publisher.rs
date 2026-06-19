//! Source-agnostic Manifold controller pose publisher for native OpenXR input.

use std::{
    io::{Read, Write},
    net::TcpStream,
    sync::mpsc::{self, SyncSender},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use serde_json::{json, Value as JsonValue};

pub(crate) const DEFAULT_MANIFOLD_POSE_STREAM: &str = "stream.motion.object_pose";
pub(crate) const DEFAULT_MANIFOLD_POSE_SOURCE: &str = "provider.native_renderer.controller_pose";
pub(crate) const MANIFOLD_POSE_SOURCE_KIND: &str = "controller_pose_provider";
#[cfg(test)]
const LEGACY_MANIFOLD_POSE_SOURCE_KIND_XR_CONTROLLER: &str = "xr_controller_pose_provider";
pub(crate) const DEFAULT_MANIFOLD_POSE_CONTROLLER: &str = "right";
pub(crate) const DEFAULT_MANIFOLD_POSE_KIND: &str = "grip";
pub(crate) const DEFAULT_MANIFOLD_POSE_SAMPLE_HZ: f32 = 72.0;
pub(crate) const MANIFOLD_COMMAND_SCHEMA: &str = "rusty.manifold.command.envelope.v1";

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct ManifoldPosePublisherConfig {
    pub(crate) enabled: bool,
    pub(crate) broker_host: String,
    pub(crate) broker_port: u16,
    pub(crate) broker_path: String,
    pub(crate) stream_id: String,
    pub(crate) source_id: String,
    pub(crate) controller: String,
    pub(crate) pose_kind: String,
    pub(crate) sample_hz: f32,
    pub(crate) connect_timeout_ms: u64,
}

impl ManifoldPosePublisherConfig {
    pub(crate) fn interval_seconds(&self) -> f64 {
        1.0 / self.sample_hz.clamp(1.0, 120.0) as f64
    }
}

#[derive(Clone, Debug)]
pub(crate) struct ManifoldPoseSample {
    pub(crate) sequence_id: u64,
    pub(crate) sample_time_unix_ns: i64,
    pub(crate) xr_predicted_display_time_ns: i64,
    pub(crate) controller: String,
    pub(crate) pose_kind: String,
    pub(crate) active: bool,
    pub(crate) tracked: bool,
    pub(crate) position_m: [f32; 3],
    pub(crate) orientation_xyzw: [f32; 4],
}

impl ManifoldPoseSample {
    pub(crate) fn now_unix_ns() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos().min(i64::MAX as u128) as i64)
            .unwrap_or(0)
    }
}

pub(crate) struct ManifoldPosePublisher {
    config: ManifoldPosePublisherConfig,
    sender: SyncSender<ManifoldPoseSample>,
}

impl ManifoldPosePublisher {
    pub(crate) fn new(config: ManifoldPosePublisherConfig) -> Self {
        let (sender, receiver) = mpsc::sync_channel::<ManifoldPoseSample>(8);
        let worker_config = config.clone();
        thread::spawn(move || {
            let mut client = BrokerWebSocketClient::new(&worker_config);
            while let Ok(sample) = receiver.recv() {
                let command = build_publish_stream_command(&worker_config, &sample);
                let text = command.to_string();
                if client.send_text(&text, sample.sequence_id).is_err() {
                    client.reset();
                }
            }
        });
        Self { config, sender }
    }

    pub(crate) fn config(&self) -> &ManifoldPosePublisherConfig {
        &self.config
    }

    pub(crate) fn publish(&self, sample: ManifoldPoseSample) -> bool {
        self.sender.try_send(sample).is_ok()
    }
}

pub(crate) fn build_publish_stream_command(
    config: &ManifoldPosePublisherConfig,
    sample: &ManifoldPoseSample,
) -> JsonValue {
    json!({
        "type": "command",
        "schema": MANIFOLD_COMMAND_SCHEMA,
        "request_id": format!("native-renderer-controller-pose-{}", sample.sequence_id),
        "command": "publish_stream_event",
        "params": {
            "stream": config.stream_id,
            "sequence_id": sample.sequence_id,
            "payload": build_object_pose_payload(config, sample),
        },
        "client_id": "rusty-quest-native-renderer",
        "app_package": "io.github.mesmerprism.rustyquest.native_renderer",
    })
}

pub(crate) fn build_object_pose_payload(
    config: &ManifoldPosePublisherConfig,
    sample: &ManifoldPoseSample,
) -> JsonValue {
    let object_id = format!("controller.{}", sample.controller);
    json!({
        "schema": "rusty.manifold.motion.object_pose.sample.v1",
        "stream": config.stream_id,
        "source": config.source_id,
        "source_kind": MANIFOLD_POSE_SOURCE_KIND,
        "object_id": object_id,
        "controller": sample.controller,
        "hand": sample.controller,
        "pose_kind": sample.pose_kind,
        "connected": sample.active,
        "active": sample.active,
        "tracked": sample.tracked,
        "sample_time_unix_ns": sample.sample_time_unix_ns,
        "xr_predicted_display_time_ns": sample.xr_predicted_display_time_ns,
        "reference_space": "native-renderer-openxr-reference-space",
        "units": {
            "position": "meters",
            "orientation": "quaternion_xyzw"
        },
        "position_m": sample.position_m,
        "orientation_xyzw": sample.orientation_xyzw,
        "pose": {
            "position_m": {
                "x": sample.position_m[0],
                "y": sample.position_m[1],
                "z": sample.position_m[2]
            },
            "orientation_xyzw": sample.orientation_xyzw
        },
        "quality01": if sample.tracked { 1.0 } else { 0.0 },
        "provider_boundary": {
            "provider": config.source_id,
            "processor_stream_contract": DEFAULT_MANIFOLD_POSE_STREAM,
            "source_agnostic": true,
            "controller_specific_estimator": false
        }
    })
}

struct BrokerWebSocketClient {
    config: ManifoldPosePublisherConfig,
    stream: Option<TcpStream>,
}

impl BrokerWebSocketClient {
    fn new(config: &ManifoldPosePublisherConfig) -> Self {
        Self {
            config: config.clone(),
            stream: None,
        }
    }

    fn reset(&mut self) {
        self.stream = None;
    }

    fn send_text(&mut self, text: &str, sequence_id: u64) -> Result<(), String> {
        if self.stream.is_none() {
            self.stream = Some(open_broker_websocket(&self.config)?);
        }
        let frame = websocket_client_text_frame(text.as_bytes(), sequence_id);
        let stream = self
            .stream
            .as_mut()
            .ok_or_else(|| "missing_websocket_stream".to_string())?;
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

fn open_broker_websocket(config: &ManifoldPosePublisherConfig) -> Result<TcpStream, String> {
    let address = format!("{}:{}", config.broker_host, config.broker_port);
    let mut stream =
        TcpStream::connect(address).map_err(|error| format!("broker_connect_failed:{error}"))?;
    let timeout = Duration::from_millis(config.connect_timeout_ms.clamp(50, 5_000));
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));
    let request = format!(
        "GET {} HTTP/1.1\r\n\
         Host: {}:{}\r\n\
         Upgrade: websocket\r\n\
         Connection: Upgrade\r\n\
         Sec-WebSocket-Key: cnVzdHktcXVlc3QtbmF0aXZlLXBvc2U=\r\n\
         Sec-WebSocket-Version: 13\r\n\
         \r\n",
        config.broker_path, config.broker_host, config.broker_port
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|error| format!("websocket_handshake_write_failed:{error}"))?;

    let mut response = Vec::with_capacity(512);
    let mut byte = [0_u8; 1];
    while response.len() < 4096 {
        stream
            .read_exact(&mut byte)
            .map_err(|error| format!("websocket_handshake_read_failed:{error}"))?;
        response.push(byte[0]);
        if response.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    let response_text = String::from_utf8_lossy(&response);
    if !response_text.starts_with("HTTP/1.1 101") && !response_text.starts_with("HTTP/1.0 101") {
        return Err("websocket_handshake_rejected".to_string());
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

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> ManifoldPosePublisherConfig {
        ManifoldPosePublisherConfig {
            enabled: true,
            broker_host: "127.0.0.1".to_string(),
            broker_port: 8765,
            broker_path: "/manifold/v1/events".to_string(),
            stream_id: DEFAULT_MANIFOLD_POSE_STREAM.to_string(),
            source_id: DEFAULT_MANIFOLD_POSE_SOURCE.to_string(),
            controller: DEFAULT_MANIFOLD_POSE_CONTROLLER.to_string(),
            pose_kind: DEFAULT_MANIFOLD_POSE_KIND.to_string(),
            sample_hz: DEFAULT_MANIFOLD_POSE_SAMPLE_HZ,
            connect_timeout_ms: 250,
        }
    }

    fn sample() -> ManifoldPoseSample {
        ManifoldPoseSample {
            sequence_id: 42,
            sample_time_unix_ns: 1_777_900_000_000_000_000,
            xr_predicted_display_time_ns: 123_456_789,
            controller: "right".to_string(),
            pose_kind: "grip".to_string(),
            active: true,
            tracked: true,
            position_m: [0.12, 1.08, -0.34],
            orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
        }
    }

    #[test]
    fn builds_source_agnostic_object_pose_payload() {
        let payload = build_object_pose_payload(&config(), &sample());
        assert_eq!(
            payload["schema"],
            "rusty.manifold.motion.object_pose.sample.v1"
        );
        assert_eq!(payload["stream"], DEFAULT_MANIFOLD_POSE_STREAM);
        assert_eq!(payload["source"], DEFAULT_MANIFOLD_POSE_SOURCE);
        assert_eq!(payload["source_kind"], MANIFOLD_POSE_SOURCE_KIND);
        assert_ne!(
            payload["source_kind"],
            LEGACY_MANIFOLD_POSE_SOURCE_KIND_XR_CONTROLLER
        );
        assert_eq!(payload["controller"], "right");
        assert_eq!(
            payload["reference_space"],
            "native-renderer-openxr-reference-space"
        );
        assert_eq!(payload["provider_boundary"]["source_agnostic"], true);
        assert_eq!(
            payload["provider_boundary"]["controller_specific_estimator"],
            false
        );
        let z = payload["position_m"][2].as_f64().unwrap_or_default();
        assert!((z - -0.34).abs() < 0.0001);
    }

    #[test]
    fn wraps_pose_payload_in_publish_stream_command() {
        let command = build_publish_stream_command(&config(), &sample());
        assert_eq!(command["command"], "publish_stream_event");
        assert_eq!(command["schema"], MANIFOLD_COMMAND_SCHEMA);
        assert!(MANIFOLD_COMMAND_SCHEMA.starts_with("rusty.manifold."));
        assert_eq!(command["params"]["stream"], DEFAULT_MANIFOLD_POSE_STREAM);
        assert_eq!(command["params"]["sequence_id"], 42);
        assert_eq!(command["client_id"], "rusty-quest-native-renderer");
    }

    #[test]
    fn interval_uses_72_hz_default_and_clamps_extremes() {
        assert!((config().interval_seconds() - (1.0 / 72.0)).abs() < 0.000_001);

        let mut high = config();
        high.sample_hz = 1_000.0;
        assert!((high.interval_seconds() - (1.0 / 120.0)).abs() < 0.000_001);

        let mut low = config();
        low.sample_hz = 0.1;
        assert!((low.interval_seconds() - 1.0).abs() < 0.000_001);
    }

    #[test]
    fn client_websocket_frame_is_masked_text() {
        let frame = websocket_client_text_frame(b"{}", 7);
        assert_eq!(frame[0], 0x81);
        assert_ne!(frame[1] & 0x80, 0);
        assert_eq!(frame[1] & 0x7f, 2);
        assert_eq!(frame.len(), 8);
    }
}
