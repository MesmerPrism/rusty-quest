//! Runtime-controlled live OpenXR compact-joint capture.
//!
//! The capture rows intentionally match the existing recorded replay
//! `clip_jsonl` shape so a pulled session can drive the same CPU/GPU skinning
//! path as a build-embedded recorded hand bundle.

use std::{
    fs::{self, File, OpenOptions},
    io::{BufWriter, Write},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use serde_json::json;

use crate::{
    live_hand_compact::{LiveHandCompactFrameSet, LiveHandCompactStats},
    native_renderer_visual_options::HandMeshVisualMaterialSettings,
    recorded_hand_replay::RecordedHandSkinningFrame,
};

const CONTROL_FILE_NAME: &str = "hand-joint-capture-control.json";
const CAPTURE_ROOT_NAME: &str = "hand-joint-captures";
const CONTROL_SCHEMA: &str = "rusty.quest.native_renderer.hand_joint_capture_control.v1";
const MANIFEST_SCHEMA: &str = "rusty.quest.native_renderer.hand_joint_capture_manifest.v1";
const CLIP_ROW_SCHEMA: &str = "rusty.quest.native_renderer.hand_joint_frame.v1";
const DEFAULT_MAX_FRAMES: u64 = 900;
const DEFAULT_SAMPLE_PERIOD_FRAMES: u64 = 1;
const TIP_LENGTH_COUNT: usize = 5;

pub(crate) struct LiveHandJointCaptureRecorder {
    control_path: Option<PathBuf>,
    capture_root: Option<PathBuf>,
    current: Option<LiveHandJointCaptureSession>,
    last_control_error: Option<String>,
}

impl LiveHandJointCaptureRecorder {
    pub(crate) fn new(
        app: &android_activity::AndroidApp,
        material_settings: HandMeshVisualMaterialSettings,
    ) -> Self {
        let Some(data_path) = app.external_data_path() else {
            crate::marker(
                "hand-joint-capture",
                "status=unavailable reason=missing-external-app-data-path controlSchema=rusty.quest.native_renderer.hand_joint_capture_control.v1 captureSchema=rusty.quest.native_renderer.hand_joint_capture_manifest.v1 replayMode=recorded-joints-skin-live",
            );
            return Self {
                control_path: None,
                capture_root: None,
                current: None,
                last_control_error: None,
            };
        };
        let capture_root = data_path.join(CAPTURE_ROOT_NAME);
        let control_path = data_path.join(CONTROL_FILE_NAME);
        if let Err(error) = fs::create_dir_all(&capture_root) {
            crate::marker(
                "hand-joint-capture",
                format!(
                    "status=unavailable reason=create-capture-root-failed error={} captureRoot={}",
                    crate::sanitize(&error.to_string()),
                    crate::sanitize(&path_marker(&capture_root))
                ),
            );
            return Self {
                control_path: None,
                capture_root: None,
                current: None,
                last_control_error: None,
            };
        }
        crate::marker(
            "hand-joint-capture",
            format!(
                "status=ready controlSchema={} captureSchema={} clipRowSchema={} controlFile={} captureRoot={} replayModes=recorded-mesh-validation-frames,recorded-joints-skin-live materialProfile={} materialAlpha={:.2} materialRimStrength={:.2} materialWireframeEnabled={} materialWireframeWidthPx={:.2}",
                CONTROL_SCHEMA,
                MANIFEST_SCHEMA,
                CLIP_ROW_SCHEMA,
                crate::sanitize(&path_marker(&control_path)),
                crate::sanitize(&path_marker(&capture_root)),
                material_settings.profile.marker_value(),
                material_settings.alpha,
                material_settings.rim_strength,
                material_settings.wireframe_enabled,
                material_settings.wireframe_width_px,
            ),
        );
        Self {
            control_path: Some(control_path),
            capture_root: Some(capture_root),
            current: None,
            last_control_error: None,
        }
    }

    pub(crate) fn update_and_record(
        &mut self,
        frame_count: u64,
        live_frames: &LiveHandCompactFrameSet,
        live_stats: &LiveHandCompactStats,
        material_settings: HandMeshVisualMaterialSettings,
    ) {
        let Some(control_path) = self.control_path.as_ref() else {
            return;
        };
        let Some(capture_root) = self.capture_root.as_ref() else {
            return;
        };
        let control = match read_control(control_path) {
            Ok(control) => control,
            Err(ControlReadError::Missing) => {
                if let Some(session) = self.current.as_mut() {
                    session.finish("control-file-missing");
                }
                self.current = None;
                return;
            }
            Err(ControlReadError::Malformed(error)) => {
                if self.last_control_error.as_deref() != Some(error.as_str()) {
                    crate::marker(
                        "hand-joint-capture",
                        format!(
                            "status=control-error reason=malformed-control-file error={} controlFile={}",
                            crate::sanitize(&error),
                            crate::sanitize(&path_marker(control_path))
                        ),
                    );
                    self.last_control_error = Some(error);
                }
                return;
            }
        };
        self.last_control_error = None;
        if !control.enabled {
            if let Some(session) = self.current.as_mut() {
                session.finish("control-disabled");
            }
            self.current = None;
            return;
        }

        let should_start = self
            .current
            .as_ref()
            .map_or(true, |session| session.session_id != control.session_id);
        if should_start {
            if let Some(session) = self.current.as_mut() {
                session.finish("session-replaced");
            }
            self.current = match LiveHandJointCaptureSession::start(
                capture_root,
                control,
                material_settings,
            ) {
                Ok(session) => Some(session),
                Err(error) => {
                    crate::marker(
                        "hand-joint-capture",
                        format!(
                            "status=start-error reason={} captureRoot={}",
                            crate::sanitize(&error),
                            crate::sanitize(&path_marker(capture_root))
                        ),
                    );
                    None
                }
            };
        }

        if let Some(session) = self.current.as_mut() {
            session.record_frame(frame_count, live_frames, live_stats, material_settings);
            if session.frame_limit_reached() {
                session.finish("max-frames-reached");
                self.current = None;
            }
        }
    }

    pub(crate) fn finish_active(&mut self, reason: &'static str) {
        if let Some(session) = self.current.as_mut() {
            session.finish(reason);
        }
        self.current = None;
    }
}

struct LiveHandJointCaptureControl {
    enabled: bool,
    session_id: String,
    max_frames: u64,
    sample_period_frames: u64,
}

enum ControlReadError {
    Missing,
    Malformed(String),
}

fn read_control(path: &Path) -> Result<LiveHandJointCaptureControl, ControlReadError> {
    if !path.exists() {
        return Err(ControlReadError::Missing);
    }
    let text = fs::read_to_string(path).map_err(|error| {
        ControlReadError::Malformed(format!("read control file failed: {error}"))
    })?;
    let value: serde_json::Value = serde_json::from_str(&text)
        .map_err(|error| ControlReadError::Malformed(format!("parse control JSON: {error}")))?;
    let schema = value
        .get("schema")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default();
    if schema != CONTROL_SCHEMA {
        return Err(ControlReadError::Malformed(format!(
            "unsupported control schema {schema}"
        )));
    }
    let enabled = value
        .get("enabled")
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false);
    let session_id = sanitize_session_id(
        value
            .get("session_id")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("hand-joints-live"),
    );
    let max_frames = value
        .get("max_frames")
        .and_then(serde_json::Value::as_u64)
        .unwrap_or(DEFAULT_MAX_FRAMES)
        .clamp(1, 36_000);
    let sample_period_frames = value
        .get("sample_period_frames")
        .and_then(serde_json::Value::as_u64)
        .unwrap_or(DEFAULT_SAMPLE_PERIOD_FRAMES)
        .clamp(1, 600);
    Ok(LiveHandJointCaptureControl {
        enabled,
        session_id,
        max_frames,
        sample_period_frames,
    })
}

struct LiveHandJointCaptureSession {
    session_id: String,
    dir: PathBuf,
    left: BufWriter<File>,
    right: BufWriter<File>,
    max_frames: u64,
    sample_period_frames: u64,
    started_unix_ms: u128,
    last_frame_count: u64,
    left_frames: u64,
    right_frames: u64,
    skipped_frames: u64,
    marker_period: u64,
    material_settings: HandMeshVisualMaterialSettings,
    finished: bool,
}

impl LiveHandJointCaptureSession {
    fn start(
        capture_root: &Path,
        control: LiveHandJointCaptureControl,
        material_settings: HandMeshVisualMaterialSettings,
    ) -> Result<Self, String> {
        let dir = capture_root.join(&control.session_id);
        fs::create_dir_all(&dir).map_err(|error| format!("create capture dir: {error}"))?;
        let left_path = dir.join("left.clip.jsonl");
        let right_path = dir.join("right.clip.jsonl");
        let left = truncate_writer(&left_path)?;
        let right = truncate_writer(&right_path)?;
        let session = Self {
            session_id: control.session_id,
            dir,
            left,
            right,
            max_frames: control.max_frames,
            sample_period_frames: control.sample_period_frames,
            started_unix_ms: unix_ms(),
            last_frame_count: 0,
            left_frames: 0,
            right_frames: 0,
            skipped_frames: 0,
            marker_period: 60,
            material_settings,
            finished: false,
        };
        session.write_manifest(None);
        crate::marker(
            "hand-joint-capture",
            format!(
                "status=started captureId={} captureDir={} replayMode=recorded-joints-skin-live animatedMeshReplayCompanion=validation_mesh_jsonl jointReplayClipFiles=left.clip.jsonl,right.clip.jsonl maxFrames={} samplePeriodFrames={} materialProfile={} materialAlpha={:.2} materialRimStrength={:.2} materialWireframeEnabled={} materialWireframeWidthPx={:.2}",
                crate::sanitize(&session.session_id),
                crate::sanitize(&path_marker(&session.dir)),
                session.max_frames,
                session.sample_period_frames,
                session.material_settings.profile.marker_value(),
                session.material_settings.alpha,
                session.material_settings.rim_strength,
                session.material_settings.wireframe_enabled,
                session.material_settings.wireframe_width_px,
            ),
        );
        Ok(session)
    }

    fn record_frame(
        &mut self,
        frame_count: u64,
        live_frames: &LiveHandCompactFrameSet,
        live_stats: &LiveHandCompactStats,
        material_settings: HandMeshVisualMaterialSettings,
    ) {
        if self.finished {
            return;
        }
        self.material_settings = material_settings;
        self.last_frame_count = frame_count;
        if frame_count % self.sample_period_frames != 0 {
            self.skipped_frames = self.skipped_frames.saturating_add(1);
            return;
        }
        let mut wrote_any = false;
        if let Some(left) = live_frames.left.as_ref() {
            if write_clip_row(&mut self.left, "left", left).is_ok() {
                self.left_frames = self.left_frames.saturating_add(1);
                wrote_any = true;
            }
        }
        if let Some(right) = live_frames.right.as_ref() {
            if write_clip_row(&mut self.right, "right", right).is_ok() {
                self.right_frames = self.right_frames.saturating_add(1);
                wrote_any = true;
            }
        }
        if !wrote_any {
            self.skipped_frames = self.skipped_frames.saturating_add(1);
        }
        let total_frames = self.left_frames.saturating_add(self.right_frames);
        if total_frames == 1 || frame_count % self.marker_period == 0 {
            self.write_manifest(None);
            crate::marker(
                "hand-joint-capture",
                format!(
                    "status=recording captureId={} replayMode=recorded-joints-skin-live liveFrameReady={} leftFrames={} rightFrames={} skippedFrames={} latestFrame={} latestTimestampNs={} materialProfile={} materialAlpha={:.2} materialRimStrength={:.2} materialWireframeEnabled={} captureDir={}",
                    crate::sanitize(&self.session_id),
                    live_stats.frame_ready,
                    self.left_frames,
                    self.right_frames,
                    self.skipped_frames,
                    live_stats.frame_index,
                    live_stats.timestamp_ns,
                    self.material_settings.profile.marker_value(),
                    self.material_settings.alpha,
                    self.material_settings.rim_strength,
                    self.material_settings.wireframe_enabled,
                    crate::sanitize(&path_marker(&self.dir)),
                ),
            );
        }
    }

    fn frame_limit_reached(&self) -> bool {
        self.left_frames.saturating_add(self.right_frames) >= self.max_frames
    }

    fn finish(&mut self, reason: &'static str) {
        if self.finished {
            return;
        }
        let _ = self.left.flush();
        let _ = self.right.flush();
        self.write_manifest(Some(reason));
        self.finished = true;
        crate::marker(
            "hand-joint-capture",
            format!(
                "status=stopped reason={} captureId={} replayMode=recorded-joints-skin-live leftFrames={} rightFrames={} skippedFrames={} maxFrames={} samplePeriodFrames={} captureDir={}",
                reason,
                crate::sanitize(&self.session_id),
                self.left_frames,
                self.right_frames,
                self.skipped_frames,
                self.max_frames,
                self.sample_period_frames,
                crate::sanitize(&path_marker(&self.dir)),
            ),
        );
    }

    fn write_manifest(&self, finished_reason: Option<&'static str>) {
        let manifest = json!({
            "schema": MANIFEST_SCHEMA,
            "capture_id": self.session_id,
            "source_kind": "live-openxr-compact-joint-recording",
            "recorded_input_equivalent": true,
            "replay_mode": "recorded-joints-skin-live",
            "animated_mesh_replay_companion": "validation_mesh_jsonl",
            "runtime_provider": "XR_EXT_hand_tracking",
            "reference_space": "openxr-local-space",
            "runtime_joint_count": 21,
            "tip_length_count": TIP_LENGTH_COUNT,
            "clip_files": {
                "left": "left.clip.jsonl",
                "right": "right.clip.jsonl",
            },
            "requires_hand_mesh_rig_for_skinning": true,
            "compatible_replay_source_schema": "rusty.quest.native_renderer.recorded_hand_replay_source.v1",
            "control_schema": CONTROL_SCHEMA,
            "clip_row_schema": CLIP_ROW_SCHEMA,
            "started_unix_ms": self.started_unix_ms,
            "finished_unix_ms": finished_reason.map(|_| unix_ms()),
            "finished_reason": finished_reason.unwrap_or("active"),
            "last_renderer_frame": self.last_frame_count,
            "left_clip_frame_count": self.left_frames,
            "right_clip_frame_count": self.right_frames,
            "skipped_frame_count": self.skipped_frames,
            "max_frames": self.max_frames,
            "sample_period_frames": self.sample_period_frames,
            "hand_material": {
                "profile": self.material_settings.profile.marker_value(),
                "base_color": self.material_settings.base_color,
                "alpha": self.material_settings.alpha,
                "rim_strength": self.material_settings.rim_strength,
                "wireframe_enabled": self.material_settings.wireframe_enabled,
                "wireframe_width_px": self.material_settings.wireframe_width_px,
                "source": "procedural-reference-not-unity-asset"
            }
        });
        let _ = fs::write(
            self.dir.join("capture.manifest.json"),
            serde_json::to_string_pretty(&manifest).unwrap_or_else(|_| "{}".to_string()),
        );
    }
}

fn truncate_writer(path: &Path) -> Result<BufWriter<File>, String> {
    let file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(path)
        .map_err(|error| format!("open {}: {error}", path_marker(path)))?;
    Ok(BufWriter::new(file))
}

fn write_clip_row(
    writer: &mut BufWriter<File>,
    handedness: &'static str,
    frame: &RecordedHandSkinningFrame,
) -> Result<(), String> {
    let joints = frame
        .runtime_joint_poses
        .iter()
        .enumerate()
        .map(|(index, pose)| {
            json!({
                "joint_index": index,
                "pose": {
                    "translation": [
                        pose.translation_pad[0],
                        pose.translation_pad[1],
                        pose.translation_pad[2],
                    ],
                    "rotation": pose.rotation_xyzw,
                }
            })
        })
        .collect::<Vec<_>>();
    let tip_lengths = frame
        .tip_length_rows
        .iter()
        .flat_map(|row| row.iter().copied())
        .take(TIP_LENGTH_COUNT)
        .collect::<Vec<_>>();
    let row = json!({
        "schema": CLIP_ROW_SCHEMA,
        "handedness": handedness,
        "frame_index": frame.frame_index,
        "timestamp_ns": frame.timestamp_ns,
        "runtime_provider": "XR_EXT_hand_tracking",
        "reference_space": "openxr-local-space",
        "joints": joints,
        "tip_lengths_m": tip_lengths,
    });
    writer
        .write_all(row.to_string().as_bytes())
        .map_err(|error| format!("write {handedness} clip row: {error}"))?;
    writer
        .write_all(b"\n")
        .map_err(|error| format!("write {handedness} clip newline: {error}"))?;
    writer
        .flush()
        .map_err(|error| format!("flush {handedness} clip row: {error}"))
}

fn sanitize_session_id(value: &str) -> String {
    let mut sanitized = value
        .chars()
        .filter_map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_' | '.') {
                Some(ch)
            } else if ch.is_whitespace() {
                Some('-')
            } else {
                None
            }
        })
        .take(96)
        .collect::<String>();
    if sanitized.is_empty() {
        sanitized = "hand-joints-live".to_string();
    }
    sanitized
}

fn unix_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0)
}

fn path_marker(path: &Path) -> String {
    path.display().to_string().replace('\\', "/")
}
