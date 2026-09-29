//! Closed, app-private panel candidate and effective renderer state.
use crate::projection_rect::TargetRect;
use serde_json::{json, Value};
use std::{
    io::Read,
    path::PathBuf,
    time::{Duration, Instant},
};

const SCHEMA: &str = "rusty.quest.hand_graft_controls.v1";
const MAX_BYTES: u64 = 4096;
const INTERVAL: Duration = Duration::from_millis(250);

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct HandGraftControls {
    pub(crate) revision: u64,
    pub(crate) panel_distance_m: f32,
    pub(crate) offset_x_uv: f32,
    pub(crate) offset_y_uv: f32,
    pub(crate) shared_scale: f32,
    pub(crate) hands_enabled: bool,
    pub(crate) joystick_enabled: bool,
    pub(crate) base_hands_visible: bool,
    pub(crate) grafts_visible: bool,
    pub(crate) graft_scale: f32,
    pub(crate) material_alpha: f32,
    pub(crate) rim_strength: f32,
    pub(crate) wireframe_enabled: bool,
}

impl Default for HandGraftControls {
    fn default() -> Self {
        Self {
            revision: 0,
            panel_distance_m: 1.0,
            offset_x_uv: 0.0,
            offset_y_uv: 0.0,
            shared_scale: 1.0,
            hands_enabled: true,
            joystick_enabled: true,
            base_hands_visible: true,
            grafts_visible: true,
            graft_scale: 0.85,
            material_alpha: 1.0,
            rim_strength: 0.2,
            wireframe_enabled: false,
        }
    }
}

impl HandGraftControls {
    pub(crate) fn parse(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() as u64 > MAX_BYTES {
            return Err("candidate-too-large".into());
        }
        let value: Value = serde_json::from_slice(bytes).map_err(|_| "invalid-json")?;
        let object = value.as_object().ok_or("expected-object")?;
        let keys = [
            "schema",
            "revision",
            "panel_distance_m",
            "offset_x_uv",
            "offset_y_uv",
            "shared_scale",
            "hands_enabled",
            "joystick_enabled",
            "base_hands_visible",
            "grafts_visible",
            "graft_scale",
            "material_alpha",
            "rim_strength",
            "wireframe_enabled",
        ];
        if object.len() != keys.len() || object.keys().any(|key| !keys.contains(&key.as_str())) {
            return Err("unexpected-or-missing-field".into());
        }
        if value["schema"].as_str() != Some(SCHEMA) {
            return Err("unsupported-schema".into());
        }
        let number = |key: &str, min: f64, max: f64| -> Result<f32, String> {
            let n = value[key]
                .as_f64()
                .ok_or_else(|| format!("invalid-{key}"))?;
            if !n.is_finite() || n < min || n > max {
                return Err(format!("out-of-range-{key}"));
            }
            Ok(n as f32)
        };
        let boolean = |key: &str| value[key].as_bool().ok_or_else(|| format!("invalid-{key}"));
        let revision = value["revision"]
            .as_u64()
            .filter(|n| *n > 0)
            .ok_or("invalid-revision")?;
        Ok(Self {
            revision,
            panel_distance_m: number("panel_distance_m", 0.25, 4.0)?,
            offset_x_uv: number("offset_x_uv", -0.5, 0.5)?,
            offset_y_uv: number("offset_y_uv", -0.5, 0.5)?,
            shared_scale: number("shared_scale", 0.25, 3.0)?,
            hands_enabled: boolean("hands_enabled")?,
            joystick_enabled: boolean("joystick_enabled")?,
            base_hands_visible: boolean("base_hands_visible")?,
            grafts_visible: boolean("grafts_visible")?,
            graft_scale: number("graft_scale", 0.1, 2.0)?,
            material_alpha: number("material_alpha", 0.05, 1.0)?,
            rim_strength: number("rim_strength", 0.0, 1.0)?,
            wireframe_enabled: boolean("wireframe_enabled")?,
        })
    }

    pub(crate) fn as_json(self) -> Value {
        json!({"panel_distance_m":self.panel_distance_m,"offset_x_uv":self.offset_x_uv,
            "offset_y_uv":self.offset_y_uv,"shared_scale":self.shared_scale,
            "hands_enabled":self.hands_enabled,"joystick_enabled":self.joystick_enabled,"base_hands_visible":self.base_hands_visible,"grafts_visible":self.grafts_visible,
            "graft_scale":self.graft_scale,"material_alpha":self.material_alpha,
            "rim_strength":self.rim_strength,"wireframe_enabled":self.wireframe_enabled})
    }

    /// Reproject the calibrated per-eye footprint at reference depth 1m to a
    /// headlocked virtual plane. Eye displacement changes stereo disparity;
    /// scale controls reference plane extent independently of distance.
    /// Preserve offscreen extent and disparity; the eye viewport clips at rasterization.
    pub(crate) fn eye_rect(
        self,
        base: TargetRect,
        fov: [f32; 4],
        eye_from_head: [f32; 3],
    ) -> TargetRect {
        if self.panel_distance_m == 1.0
            && self.shared_scale == 1.0
            && self.offset_x_uv == 0.0
            && self.offset_y_uv == 0.0
        {
            return base;
        }
        let [left, right, up, down] = fov;
        let tangent_width = right - left;
        let tangent_height = up - down;
        if !base.is_valid()
            || tangent_width <= 0.0
            || tangent_height <= 0.0
            || fov
                .iter()
                .chain(eye_from_head.iter())
                .any(|n| !n.is_finite())
        {
            return base;
        }
        let distance = self.panel_distance_m + eye_from_head[2];
        if distance <= 0.01 {
            return base;
        }
        let center_tan_x = left + (base.x + base.width * 0.5) * tangent_width;
        let center_tan_y = up - (base.y + base.height * 0.5) * tangent_height;
        let reference_depth = 1.0 + eye_from_head[2];
        let center_x =
            ((center_tan_x * reference_depth + self.offset_x_uv * tangent_width) / distance - left)
                / tangent_width;
        let center_y = (up
            - (center_tan_y * reference_depth - self.offset_y_uv * tangent_height) / distance)
            / tangent_height;
        // Calibration already includes the per-eye reference center. Moving
        // the common head-relative plane adds the relative-eye disparity.
        let center_x =
            center_x - eye_from_head[0] * (1.0 / distance - 1.0 / reference_depth) / tangent_width;
        let center_y =
            center_y + eye_from_head[1] * (1.0 / distance - 1.0 / reference_depth) / tangent_height;
        let width = base.width * self.shared_scale * reference_depth / distance;
        let height = base.height * self.shared_scale * reference_depth / distance;
        TargetRect::new(
            center_x - width * 0.5,
            center_y - height * 0.5,
            width,
            height,
        )
    }
}

pub(crate) struct HandGraftControlPoller {
    path: Option<PathBuf>,
    pub(crate) effective: HandGraftControls,
    last_poll: Option<Instant>,
    last_status: Option<Instant>,
    last_bytes: Vec<u8>,
    candidate_revision: u64,
    adoption_status: &'static str,
    rejection_reason: String,
}

impl HandGraftControlPoller {
    pub(crate) fn new(path: Option<PathBuf>) -> Self {
        Self {
            path,
            effective: HandGraftControls::default(),
            last_poll: None,
            last_status: None,
            last_bytes: Vec::new(),
            candidate_revision: 0,
            adoption_status: "defaults",
            rejection_reason: String::new(),
        }
    }
    pub(crate) fn enabled(&self) -> bool {
        self.path.is_some()
    }
    pub(crate) fn poll(&mut self) -> Option<HandGraftControls> {
        let path = self.path.as_ref()?;
        if self.last_poll.is_some_and(|last| last.elapsed() < INTERVAL) {
            return None;
        }
        self.last_poll = Some(Instant::now());
        let file = match std::fs::File::open(path.join("hand_graft_controls_candidate.json")) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return None,
            Err(_) => {
                self.adoption_status = "rejected";
                self.rejection_reason = "candidate-read-error".into();
                return None;
            }
        };
        let mut bytes = Vec::new();
        if file.take(MAX_BYTES + 1).read_to_end(&mut bytes).is_err() {
            self.adoption_status = "rejected";
            self.rejection_reason = "candidate-read-error".into();
            return None;
        }
        if bytes == self.last_bytes {
            return None;
        }
        self.last_bytes = bytes.clone();
        match HandGraftControls::parse(&bytes) {
            Ok(candidate) => {
                self.candidate_revision = candidate.revision;
                if candidate.revision <= self.effective.revision {
                    self.adoption_status = "rejected";
                    self.rejection_reason = "stale-revision".into();
                    return None;
                }
                self.effective = candidate;
                self.adoption_status = "adopted";
                self.rejection_reason.clear();
                Some(candidate)
            }
            Err(reason) => {
                self.adoption_status = "rejected";
                self.rejection_reason = reason;
                None
            }
        }
    }
    pub(crate) fn submitted(&mut self, frame: u64, rects: [TargetRect; 2], shared_scale: f32) {
        let Some(path) = self.path.as_ref() else {
            return;
        };
        if self
            .last_status
            .is_some_and(|last| last.elapsed() < INTERVAL)
        {
            return;
        }
        self.last_status = Some(Instant::now());
        self.effective.shared_scale = shared_scale;
        let rectangle = |r: TargetRect| json!([r.x, r.y, r.width, r.height]);
        let value = json!({"schema":SCHEMA,"revision":self.effective.revision,
            "effective_revision":self.effective.revision,"candidate_revision":self.candidate_revision,
            "effective":self.effective.as_json(),"effective_target_rects":rects.map(rectangle),
            "adoption_status":self.adoption_status,"rejection_reason":self.rejection_reason,
            "distance_semantics":"calibrated-relative-headlocked-plane",
            "reference_distance_m":1.0,"footprint_policy":"clip-at-raster",
            "effective_state":"submitted-frame","last_submitted_frame":frame,
            "submission_proof":"openxr-frame-end-success-not-visual-acceptance"});
        let temp = path.join("hand_graft_controls_status.json.tmp");
        if std::fs::write(&temp, value.to_string()).is_ok() {
            let _ = std::fs::rename(&temp, path.join("hand_graft_controls_status.json"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn candidate() -> Value {
        let mut value = HandGraftControls::default().as_json();
        value["schema"] = SCHEMA.into();
        value["revision"] = 1.into();
        value
    }
    #[test]
    fn rejects_partial_unknown_nonfinite_and_out_of_bounds_without_clamping() {
        assert!(HandGraftControls::parse(candidate().to_string().as_bytes()).is_ok());
        for (key, value) in [
            ("shared_scale", json!(4)),
            ("graft_scale", json!(0)),
            ("material_alpha", json!(null)),
            ("grafts_visible", json!(1)),
            ("revision", json!(0)),
            ("unknown", json!(true)),
        ] {
            let mut c = candidate();
            c[key] = value;
            assert!(
                HandGraftControls::parse(c.to_string().as_bytes()).is_err(),
                "{key}"
            );
        }
        assert!(HandGraftControls::parse(&vec![b' '; 4097]).is_err());
    }
    #[test]
    fn accepts_exact_json_ui_extrema() {
        for (key, min, max) in [
            ("panel_distance_m", 0.25, 4.0),
            ("shared_scale", 0.25, 3.0),
            ("offset_x_uv", -0.5, 0.5),
            ("offset_y_uv", -0.5, 0.5),
            ("graft_scale", 0.1, 2.0),
            ("material_alpha", 0.05, 1.0),
            ("rim_strength", 0.0, 1.0),
        ] {
            for endpoint in [min, max] {
                let mut c = candidate();
                c[key] = json!(endpoint);
                assert!(
                    HandGraftControls::parse(c.to_string().as_bytes()).is_ok(),
                    "{key} {endpoint}"
                );
            }
        }
    }
    #[test]
    fn depth_preserves_reference_and_changes_stereo_disparity_separately_from_scale() {
        let base = TargetRect::new(0.25, 0.25, 0.5, 0.5);
        let mut c = HandGraftControls::default();
        let fov = [-1.0, 1.0, 1.0, -1.0];
        assert_eq!(c.eye_rect(base, fov, [-0.032, 0.0, 0.0]), base);
        c.panel_distance_m = 2.0;
        let left = c.eye_rect(base, fov, [-0.032, 0.0, 0.0]);
        let right = c.eye_rect(base, fov, [0.032, 0.0, 0.0]);
        assert_eq!(left.width, 0.25);
        assert!(left.x < right.x);
        c.shared_scale = 2.0;
        let scaled = c.eye_rect(base, fov, [-0.032, 0.0, 0.0]);
        assert_eq!(scaled.width, base.width);
        assert!(((scaled.x + scaled.width / 2.0) - (left.x + left.width / 2.0)).abs() < 0.000001);
    }
    #[test]
    fn effective_affine_keeps_camera_and_hand_coordinates_aligned() {
        let c = HandGraftControls {
            panel_distance_m: 2.0,
            offset_x_uv: 0.1,
            offset_y_uv: -0.1,
            ..Default::default()
        };
        for eye in [-0.032, 0.032] {
            let base = TargetRect::new(0.2, 0.15, 0.6, 0.7);
            let rect = c.eye_rect(base, [-1.1, 0.9, 0.8, -1.2], [eye, 0.0, 0.0]);
            let map = base.world_eye_projection_rect(rect, true);
            for uv in [[0.0, 0.0], [0.5, 0.5], [1.0, 1.0]] {
                assert!(
                    (map.x + (base.x + uv[0] * base.width) * map.width
                        - (rect.x + uv[0] * rect.width))
                        .abs()
                        < 0.000001
                );
                assert!(
                    (map.y + (base.y + uv[1] * base.height) * map.height
                        - (rect.y + uv[1] * rect.height))
                        .abs()
                        < 0.000001
                );
            }
        }
    }
    #[test]
    fn near_plane_keeps_offscreen_extent_offsets_and_stereo_disparity() {
        let base = TargetRect::new(0.25, 0.25, 0.5, 0.5);
        let controls = HandGraftControls {
            panel_distance_m: 0.25,
            shared_scale: 3.0,
            offset_x_uv: 0.1,
            offset_y_uv: -0.1,
            ..Default::default()
        };
        let left = controls.eye_rect(base, [-1.0, 1.0, 1.0, -1.0], [-0.032, 0.0, 0.0]);
        let right = controls.eye_rect(base, [-1.0, 1.0, 1.0, -1.0], [0.032, 0.0, 0.0]);
        assert_eq!(left.width, 6.0);
        assert!(left.x > right.x);
        assert!(left.x < 0.0 && left.y < 0.0);
        let centered = HandGraftControls {
            offset_x_uv: 0.0,
            offset_y_uv: 0.0,
            ..controls
        }
        .eye_rect(base, [-1.0, 1.0, 1.0, -1.0], [-0.032, 0.0, 0.0]);
        assert!((left.x - centered.x - 0.4).abs() < 0.000001);
        assert!((left.y - centered.y + 0.4).abs() < 0.000001);
        let map = base.world_eye_projection_rect(left, true);
        assert_eq!(map.width, 12.0);
        assert!((map.x + base.x * map.width - left.x).abs() < 0.000001);
    }
    #[test]
    fn file_adoption_retains_last_good_state_and_reports_only_submitted_revision() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path =
            std::env::temp_dir().join(format!("rusty-hand-graft-{}-{unique}", std::process::id()));
        std::fs::create_dir(&path).unwrap();
        let file = path.join("hand_graft_controls_candidate.json");
        let mut poller = HandGraftControlPoller::new(Some(path.clone()));
        assert!(poller.poll().is_none());
        std::fs::write(&file, candidate().to_string()).unwrap();
        poller.last_poll = None;
        assert_eq!(poller.poll().unwrap().revision, 1);
        std::fs::write(&file, b"{bad json").unwrap();
        poller.last_poll = None;
        assert!(poller.poll().is_none());
        assert_eq!(poller.effective.revision, 1);
        assert_eq!(poller.adoption_status, "rejected");
        let mut duplicate = candidate();
        duplicate["shared_scale"] = json!(2);
        std::fs::write(&file, duplicate.to_string()).unwrap();
        poller.last_poll = None;
        assert!(poller.poll().is_none());
        assert_eq!(poller.effective.shared_scale, 1.0);
        assert_eq!(poller.rejection_reason, "stale-revision");
        assert!(!path.join("hand_graft_controls_status.json").exists());
        poller.submitted(30, [TargetRect::UNIT; 2], 1.2);
        let status: Value = serde_json::from_slice(
            &std::fs::read(path.join("hand_graft_controls_status.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(status["effective_revision"], 1);
        assert_eq!(status["last_submitted_frame"], 30);
        assert_eq!(status["effective_state"], "submitted-frame");
        assert!((status["effective"]["shared_scale"].as_f64().unwrap() - 1.2).abs() < 0.00001);
        std::fs::remove_file(file).unwrap();
        std::fs::remove_file(path.join("hand_graft_controls_status.json")).unwrap();
        std::fs::remove_dir(path).unwrap();
    }
}
