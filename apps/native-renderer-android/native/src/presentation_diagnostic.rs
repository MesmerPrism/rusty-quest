//! Explicit private presentation diagnostics. Startup config is closed and bounded.
use crate::presentation_geometry as math;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{io::Read, path::Path, sync::OnceLock};
static CONFIG: OnceLock<Config> = OnceLock::new();
static DIRECTORY: OnceLock<std::path::PathBuf> = OnceLock::new();
static ANCHOR: OnceLock<([f32; 3], [f32; 4])> = OnceLock::new();
#[derive(Clone, Copy, Debug, Deserialize, PartialEq)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum Mode {
    Native,
    Stretch,
    Contain,
    Angular,
    Cylinder,
}
impl Mode {
    pub(crate) fn id(self) -> f32 {
        match self {
            Self::Native => 0.,
            Self::Stretch => 1.,
            Self::Contain => 2.,
            Self::Angular => 3.,
            Self::Cylinder => 4.,
        }
    }
}
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Config {
    pub(crate) schema: String,
    pub(crate) mode: Mode,
    #[serde(default = "save_witnesses_default")]
    pub(crate) save_witnesses: bool,
    #[serde(default = "input_layout_default")]
    pub(crate) input_layout: String,
    #[serde(default = "input_height_default")]
    pub(crate) input_height: i32,
    #[serde(default)]
    pub(crate) source_tangents: Option<[f32; 4]>,
    #[serde(default)]
    pub(crate) source_tangents_per_eye: Option<[[f32; 4]; 2]>,
    #[serde(default)]
    pub(crate) source_bounds_authority: Option<String>,
    #[serde(default = "reference_default")]
    pub(crate) reference: String,
    #[serde(default)]
    pub(crate) yaw_degrees: f32,
    #[serde(default = "radius_default")]
    pub(crate) cylinder_radius_m: f32,
    #[serde(default = "angle_default")]
    pub(crate) cylinder_half_angle_degrees: f32,
    #[serde(default = "height_default")]
    pub(crate) cylinder_height_m: f32,
}
fn save_witnesses_default() -> bool {
    true
}
fn reference_default() -> String {
    "current-left-view".into()
}
fn input_layout_default() -> String {
    "mono-left".into()
}
fn input_height_default() -> i32 {
    1280
}
fn radius_default() -> f32 {
    2.
}
fn angle_default() -> f32 {
    45.
}
fn height_default() -> f32 {
    1.65
}
impl Default for Config {
    fn default() -> Self {
        Self {
            schema: "private.meta_input.presentation.v1".into(),
            mode: Mode::Native,
            save_witnesses: save_witnesses_default(),
            input_layout: input_layout_default(),
            input_height: input_height_default(),
            source_tangents: None,
            source_tangents_per_eye: None,
            source_bounds_authority: None,
            reference: reference_default(),
            yaw_degrees: 0.,
            cylinder_radius_m: radius_default(),
            cylinder_half_angle_degrees: angle_default(),
            cylinder_height_m: height_default(),
        }
    }
}
pub(crate) fn enabled() -> bool {
    !cfg!(feature = "showcase")
        && option_env!("RUSTY_QUEST_NATIVE_RENDERER_PRESENTATION_DIAGNOSTIC") == Some("true")
}
pub(crate) fn initialize(data: Option<&Path>) -> Result<(), String> {
    if !enabled() {
        return Ok(());
    }
    if !crate::meta_input_diagnostic::enabled() {
        return Err("Presentation requires selected Meta input diagnostic".into());
    }
    let root = data.ok_or("Presentation data directory missing")?;
    let path = root.join("presentation-diagnostic.json");
    let mut bytes = Vec::new();
    let c = match std::fs::File::open(&path) {
        Ok(f) => {
            f.take(2049)
                .read_to_end(&mut bytes)
                .map_err(|e| e.to_string())?;
            if bytes.len() > 2048 {
                return Err("Presentation config exceeds2048bytes".into());
            }
            serde_json::from_slice::<Config>(&bytes)
                .map_err(|e| format!("Invalid presentation config: {e}"))?
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Config::default(),
        Err(e) => return Err(e.to_string()),
    };
    c.validate()?;
    let dir = root.join("presentation-witness");
    if c.save_witnesses {
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    }
    CONFIG
        .set(c.clone())
        .map_err(|_| "Presentation config already initialized")?;
    DIRECTORY
        .set(dir)
        .map_err(|_| "Presentation directory already initialized")?;
    let config_sha256 = (!bytes.is_empty()).then(|| format!("{:x}", Sha256::digest(&bytes)));
    if !c.save_witnesses {
        write_no_save_policy_receipt(root, &c, config_sha256.as_deref(), bytes.len())?;
    }
    crate::marker(
        "presentation",
        format!(
            "status=config mode={:?} reference={} sourceBoundsAuthority={} saveWitnesses={} noPixelWitnesses={} pixelWitnessPolicy={} startupConfigSha256={} startupConfigBytes={} processId={} no360Source=true noSourceCapturePose=true projectionOnlyExclude=true",
            c.mode,
            c.reference,
            c.source_bounds_authority.as_deref().unwrap_or("none"),
            c.save_witnesses,
            !c.save_witnesses,
            if c.save_witnesses {
                "finite-witnesses"
            } else {
                "disabled-no-save"
            },
            config_sha256.as_deref().unwrap_or("default"),
            bytes.len(),
            std::process::id()
        ),
    );
    if c.mode == Mode::Cylinder && c.cylinder_half_angle_degrees >= 179.999 {
        crate::marker(
            "presentation",
            "status=description fullwrapOfForwardSource=true sourceIs360=false forwardFrameStretchedOnceAroundCylinder=true backSeam=source-left-meets-source-right noPanoramaClaim=true",
        );
    }
    Ok(())
}
fn write_no_save_policy_receipt(
    root: &Path,
    config: &Config,
    config_sha256: Option<&str>,
    config_bytes: usize,
) -> Result<(), String> {
    use std::io::Write;
    let digest = config_sha256.ok_or("No-save policy requires actual startup bytes")?;
    let value = serde_json::json!({
        "schema":"rusty.quest.meta_input.presentation_policy.v1",
        "process_id":std::process::id(),
        "startup_config_sha256":digest,
        "startup_config_bytes":config_bytes,
        "save_witnesses":config.save_witnesses,
        "pixel_witness_policy":"disabled-no-save",
        "reader_generation_available_at_config":false
    });
    let temporary = root.join(format!("presentation-policy.{}.part", std::process::id()));
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .map_err(|e| e.to_string())?;
    file.write_all(value.to_string().as_bytes())
        .map_err(|e| e.to_string())?;
    file.sync_all().map_err(|e| e.to_string())?;
    drop(file);
    std::fs::rename(&temporary, root.join("presentation-policy.json")).map_err(|e| e.to_string())
}
impl Config {
    pub(crate) fn layout(&self) -> math::InputLayout {
        math::InputLayout::from_name(&self.input_layout).expect("validated closed input layout")
    }
    pub(crate) fn input_extent(&self) -> [u32; 2] {
        self.layout()
            .consumer_extent(self.input_height)
            .expect("validated closed input extent")
    }
    pub(crate) fn eye_extent(&self) -> [u32; 2] {
        self.layout()
            .eye_extent(self.input_height)
            .expect("validated closed eye extent")
    }
    pub(crate) fn has_explicit_stereo_bounds(&self) -> bool {
        self.layout() == math::InputLayout::StereoBoth
            && self.source_tangents_per_eye.is_some()
            && self.source_bounds_authority.as_deref() == Some("manual-assumed")
            && self.reference == "current-per-eye-view"
    }
    fn source_tangents_for_eye(&self, eye: usize) -> Option<[f32; 4]> {
        self.source_tangents_per_eye
            .map(|t| t[eye])
            .or(self.source_tangents)
    }
    fn validate(&self) -> Result<(), String> {
        let layout = math::InputLayout::from_name(&self.input_layout)
            .ok_or("Unknown closed input layout")?;
        if layout.consumer_extent(self.input_height).is_none()
            || self.schema != "private.meta_input.presentation.v1"
            || ![
                "current-left-view",
                "startup-center",
                "current-per-eye-view",
            ]
            .contains(&self.reference.as_str())
            || !self.yaw_degrees.is_finite()
            || self.yaw_degrees.abs() > 180.
        {
            return Err("Invalid presentation schema/reference/yaw".into());
        }
        if let Some(t) = self.source_tangents {
            if !math::valid_tangents(t) {
                return Err("Invalid source tangent bounds".into());
            }
        }
        if self
            .source_tangents_per_eye
            .is_some_and(|ts| ts.iter().any(|&t| !math::valid_tangents(t)))
        {
            return Err("Invalid per-eye source tangent bounds".into());
        }
        if (layout == math::InputLayout::MonoLeft && self.source_tangents_per_eye.is_some())
            || (layout == math::InputLayout::StereoBoth && self.source_tangents.is_some())
            || (layout == math::InputLayout::MonoLeft && self.reference == "current-per-eye-view")
        {
            return Err("Source bounds/reference must match the selected input layout".into());
        }
        if self.mode == Mode::Angular
            && (self.source_tangents_for_eye(0).is_none()
                || self.source_bounds_authority.as_deref() != Some("manual-assumed"))
        {
            return Err("Angular mode requires explicit manual-assumed source bounds; no sourceFOV getter exists".into());
        }
        if self.mode == Mode::Angular
            && layout == math::InputLayout::StereoBoth
            && self.reference != "current-per-eye-view"
        {
            return Err("Stereo angular diagnostics require explicit assumed current-per-eye-view; source capture pose is unavailable".into());
        }
        if self
            .source_bounds_authority
            .as_deref()
            .is_some_and(|s| s != "manual-assumed")
        {
            return Err("Unsupported source bounds authority".into());
        }
        if !self.cylinder_radius_m.is_finite()
            || !(0.5..=10.).contains(&self.cylinder_radius_m)
            || !self.cylinder_height_m.is_finite()
            || !(0.1..=20.).contains(&self.cylinder_height_m)
            || !self.cylinder_half_angle_degrees.is_finite()
            || !(5. ..=180.).contains(&self.cylinder_half_angle_degrees)
        {
            return Err("Invalid finite cylinder dimensions".into());
        }
        Ok(())
    }
}
pub(crate) fn config() -> Option<&'static Config> {
    enabled().then(|| CONFIG.get()).flatten()
}
pub(crate) fn witnesses_enabled() -> bool {
    config().map_or(true, |c| c.save_witnesses)
}
pub(crate) fn visible() -> bool {
    config().map_or(true, |c| c.mode != Mode::Native)
}
#[derive(Clone, Copy, Debug)]
pub(crate) struct Eye {
    pub(crate) target: [f32; 4],
    pub(crate) tangents: [f32; 4],
    pub(crate) rotation: [f32; 4],
    pub(crate) origin: [f32; 4],
    pub(crate) source: [f32; 4],
    pub(crate) source_uv_rect: [f32; 4],
    pub(crate) source_sample_bounds: [f32; 4],
    pub(crate) parameters: [f32; 4],
}
impl Default for Eye {
    fn default() -> Self {
        Self {
            target: [0., 0., 1., 1.],
            tangents: [-1., 1., -1., 1.],
            rotation: [0., 0., 0., 1.],
            origin: [0.; 4],
            source: [-1., 1., -1., 1.],
            source_uv_rect: [0., 0., 1., 1.],
            source_sample_bounds: [0., 0., 1., 1.],
            parameters: [1., 2., std::f32::consts::FRAC_PI_4, 1.65],
        }
    }
}
#[derive(Clone, Debug)]
pub(crate) struct Frame {
    pub(crate) eyes: [Eye; 2],
    pub(crate) metadata: serde_json::Value,
}
pub(crate) fn frame(
    views: &[openxr::View],
    extent: ash::vk::Extent2D,
    time: i64,
) -> Result<Option<Frame>, String> {
    let Some(c) = config() else { return Ok(None) };
    frame_with_config(c, views, extent, time)
}
fn frame_with_config(
    c: &Config,
    views: &[openxr::View],
    extent: ash::vk::Extent2D,
    time: i64,
) -> Result<Option<Frame>, String> {
    if views.len() != 2 {
        return Err("Presentation needs exactly two valid views".into());
    }
    let position = |v: &openxr::View| [v.pose.position.x, v.pose.position.y, v.pose.position.z];
    let orientation = |v: &openxr::View| {
        [
            v.pose.orientation.x,
            v.pose.orientation.y,
            v.pose.orientation.z,
            v.pose.orientation.w,
        ]
    };
    for view in views {
        let p = position(view);
        let q = orientation(view);
        if p.iter().chain(q.iter()).any(|v| !v.is_finite())
            || (q.iter().map(|v| v * v).sum::<f32>() - 1.).abs() > 0.01
        {
            return Err("Invalid runtime eye pose".into());
        }
    }
    let l = position(&views[0]);
    let r = position(&views[1]);
    let center = [
        (l[0] + r[0]) * 0.5,
        (l[1] + r[1]) * 0.5,
        (l[2] + r[2]) * 0.5,
    ];
    let anchor = *ANCHOR.get_or_init(|| (center, orientation(&views[0])));
    let (source_position, source_orientation) = if c.reference == "startup-center" {
        anchor
    } else {
        (l, orientation(&views[0]))
    };
    let yaw = c.yaw_degrees.to_radians() * 0.5;
    let source_orientation = math::quat_mul(source_orientation, [0., yaw.sin(), 0., yaw.cos()]);
    let mut eyes = [Eye::default(); 2];
    let mut view_metadata = Vec::new();
    for i in 0..2 {
        let v = &views[i];
        let t = [
            v.fov.angle_left.tan(),
            v.fov.angle_right.tan(),
            v.fov.angle_down.tan(),
            v.fov.angle_up.tan(),
        ];
        if !math::valid_tangents(t) {
            return Err("Invalid runtime eye FOV".into());
        }
        let p = position(v);
        let (eye_source_position, eye_source_orientation) = if c.reference == "current-per-eye-view"
        {
            (
                p,
                math::quat_mul(orientation(v), [0., yaw.sin(), 0., yaw.cos()]),
            )
        } else {
            (source_position, source_orientation)
        };
        let eye_inverse = math::quat_inverse(eye_source_orientation);
        let o = math::rotate(
            eye_inverse,
            [
                p[0] - eye_source_position[0],
                p[1] - eye_source_position[1],
                p[2] - eye_source_position[2],
            ],
        );
        let target = if c.mode == Mode::Contain {
            math::contain(c.eye_extent(), [extent.width, extent.height])
                .ok_or("Invalid contain dimensions")?
                .0
        } else {
            [0., 0., 1., 1.]
        };
        eyes[i] = Eye {
            target,
            tangents: t,
            rotation: math::quat_mul(eye_inverse, orientation(v)),
            origin: [o[0], o[1], o[2], 0.],
            source: c.source_tangents_for_eye(i).unwrap_or([-1., 1., -1., 1.]),
            source_uv_rect: c.layout().source_rect(i).expect("two valid output eyes").0,
            source_sample_bounds: c
                .layout()
                .sample_bounds(i, c.input_height)
                .expect("validated input layout"),
            parameters: [
                c.mode.id(),
                c.cylinder_radius_m,
                c.cylinder_half_angle_degrees.to_radians(),
                c.cylinder_height_m,
            ],
        };
        view_metadata.push(serde_json::json!({"eye":i,"target_rect":target,"fov_tangents_lrdu":t,"pose_position":p,"pose_orientation_xyzw":orientation(v),"eye_to_source_rotation_xyzw":eyes[i].rotation,"eye_origin_in_source":o,"source_uv_rect":eyes[i].source_uv_rect,"source_sample_bounds":eyes[i].source_sample_bounds,"source_tangents":c.source_tangents_for_eye(i),"source_reference_position":eye_source_position,"source_reference_orientation_xyzw":eye_source_orientation,"source_reference_authority":"assumed-output-view-not-capture-pose"}));
    }
    Ok(Some(Frame {
        eyes,
        metadata: serde_json::json!({"schema":"private.meta_input.presentation_frame.v2","presentation_mode":format!("{:?}",c.mode),"input_layout":c.layout().name(),"source_size":c.input_extent(),"source_eye_size":c.eye_extent(),"eye_size":[extent.width,extent.height],"eyes":view_metadata,"source_tangents":c.source_tangents,"source_tangents_per_eye":c.source_tangents_per_eye,"source_bounds_authority":c.source_bounds_authority,"source_bounds_qualified":false,"reference":c.reference,"source_reference_position":if c.reference=="current-per-eye-view" {None} else {Some(source_position)},"source_reference_orientation_xyzw":if c.reference=="current-per-eye-view" {None} else {Some(source_orientation)},"predicted_display_time_ns":time,"source_capture_pose_available":false,"temporal_reprojection":false,"source_expected_eye_selection":c.layout().capture_eye_selection(),"source_eye_selection_verified_by_app":false,"same_mono_source_to_both_eyes":c.layout()==math::InputLayout::MonoLeft,"capture_half_labels_verified":false,"source_uv_orientation_qualified":false,"cylinder_radius_m":c.cylinder_radius_m,"cylinder_half_angle_degrees":c.cylinder_half_angle_degrees,"cylinder_height_m":c.cylinder_height_m,"uncovered":"transparent-native-passthrough","source_is_360":false,"sourceIs360":false,"fullwrapOfForwardSource":c.mode==Mode::Cylinder && c.cylinder_half_angle_degrees>=179.999,"hmd_pixel_proof":false}),
    }))
}
pub(crate) fn write_witness(
    completion: crate::meta_input_diagnostic::Completion,
    timing: crate::meta_input_diagnostic_state::WitnessTiming,
    eye: usize,
    format: ash::vk::Format,
    metadata: &serde_json::Value,
    bytes: &[u8],
) -> Result<(), String> {
    crate::meta_input_diagnostic_state::require_witness_saving(witnesses_enabled())?;
    let dir = DIRECTORY
        .get()
        .ok_or("Presentation witness directory missing")?
        .join(completion.generation.to_string());
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let stem = format!(
        "eye-{eye}-mode-{}-seq-{}",
        completion.mode, completion.sequence
    );
    let write = |path: &Path, data: &[u8]| -> Result<(), String> {
        use std::io::Write;
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(path)
            .map_err(|e| e.to_string())?;
        f.write_all(data).map_err(|e| e.to_string())?;
        f.sync_all().map_err(|e| e.to_string())
    };
    write(&dir.join(format!("{stem}.rgba")), bytes)?;
    let mut m = metadata.clone();
    m["witness"] =
        serde_json::json!("eye-sized-offscreen-same-private-pipeline-mapping-after-frame-fence");
    m["generation"] = completion.generation.into();
    m["effect_mode"] = completion.mode.into();
    m["sequence"] = completion.sequence.into();
    m["input_timestamp_ns"] = completion.timestamp_ns.into();
    m["source_frame"] = completion.input_frame.into();
    m["render_frame"] = completion.render_frame.into();
    m["eye"] = eye.into();
    m["vk_format"] = format.as_raw().into();
    m["byte_count"] = bytes.len().into();
    m["steady_phase_elapsed_ms"] = timing.elapsed_ms.into();
    m["steady_completed_input_advances"] = timing.input_advances.into();
    write(&dir.join(format!("{stem}.json")), m.to_string().as_bytes())?;
    crate::marker(
        "presentation-witness",
        format!(
            "status=written eye={} generation={} sequence={} renderFrame={} bytes={} hmdPixelProof=false",
            eye,
            completion.generation,
            completion.sequence,
            completion.render_frame,
            bytes.len()
        ),
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn decode(text: &str) -> Config {
        serde_json::from_str(text).unwrap()
    }
    #[test]
    fn witness_saving_is_an_explicit_boolean_with_legacy_true_default() {
        assert!(Config::default().save_witnesses);
        assert!(
            decode(r#"{"schema":"private.meta_input.presentation.v1","mode":"stretch"}"#)
                .save_witnesses
        );
        let no_save = decode(
            r#"{"schema":"private.meta_input.presentation.v1","mode":"stretch","save_witnesses":false}"#,
        );
        no_save.validate().unwrap();
        assert!(!no_save.save_witnesses);
        for invalid in ["null", "0", "\"false\""] {
            let text = format!(
                r#"{{"schema":"private.meta_input.presentation.v1","mode":"stretch","save_witnesses":{invalid}}}"#
            );
            assert!(serde_json::from_str::<Config>(&text).is_err());
        }
    }
    #[test]
    fn old_mono_configs_keep_their_shape_and_unknown_bounds() {
        let c = decode(
            r#"{"schema":"private.meta_input.presentation.v1","mode":"stretch","input_height":672}"#,
        );
        c.validate().unwrap();
        assert_eq!(c.layout(), math::InputLayout::MonoLeft);
        assert_eq!(c.input_extent(), [1280, 672]);
        assert_eq!(c.eye_extent(), [1280, 672]);
        assert_eq!(c.source_tangents_for_eye(0), None);
        assert!(!c.has_explicit_stereo_bounds());
    }
    #[test]
    fn stereo_is_closed_and_does_not_invent_capture_intrinsics() {
        let c = decode(
            r#"{"schema":"private.meta_input.presentation.v1","mode":"stretch","input_layout":"stereo-both","input_height":1280}"#,
        );
        c.validate().unwrap();
        assert_eq!(c.input_extent(), [2560, 1280]);
        assert_eq!(c.eye_extent(), [1280, 1280]);
        assert_eq!(c.source_tangents_for_eye(1), None);
        assert!(!c.has_explicit_stereo_bounds());
        let mut wrong = c.clone();
        wrong.input_height = 672;
        assert!(wrong.validate().is_err());
        wrong = c.clone();
        wrong.input_layout = "arbitrary-width".into();
        assert!(wrong.validate().is_err());
        assert!(serde_json::from_str::<Config>(
            r#"{"schema":"private.meta_input.presentation.v1","mode":"stretch","input_width":2560}"#
        )
        .is_err());
    }
    #[test]
    fn angular_stereo_requires_explicit_each_eye_bounds_and_pose_assumption() {
        let mut c = decode(
            r#"{"schema":"private.meta_input.presentation.v1","mode":"angular","input_layout":"stereo-both","input_height":1280}"#,
        );
        assert!(c.validate().is_err());
        c.source_tangents_per_eye = Some([[-1.2, 0.8, -0.7, 1.1], [-0.8, 1.3, -0.9, 1.0]]);
        c.source_bounds_authority = Some("manual-assumed".into());
        assert!(c.validate().is_err()); // Must not silently reuse the left reference for right input.
        c.reference = "current-per-eye-view".into();
        c.validate().unwrap();
        assert!(c.has_explicit_stereo_bounds());
        assert_ne!(c.source_tangents_for_eye(0), c.source_tangents_for_eye(1));
        c.source_tangents = Some([-1., 1., -1., 1.]);
        assert!(c.validate().is_err()); // Shared and per-eye bounds cannot compete.
    }
    #[test]
    fn actual_frame_readback_splits_capture_halves_without_claiming_calibration() {
        let c = decode(
            r#"{"schema":"private.meta_input.presentation.v1","mode":"angular","input_layout":"stereo-both","input_height":1280,"source_tangents_per_eye":[[-1.2,0.8,-0.7,1.1],[-0.8,1.3,-0.9,1.0]],"source_bounds_authority":"manual-assumed","reference":"current-per-eye-view"}"#,
        );
        c.validate().unwrap();
        let view = |x| openxr::View {
            pose: openxr::Posef {
                orientation: openxr::Quaternionf {
                    x: 0.,
                    y: 0.,
                    z: 0.,
                    w: 1.,
                },
                position: openxr::Vector3f { x, y: 0., z: 0. },
            },
            fov: openxr::Fovf {
                angle_left: -0.7,
                angle_right: 0.6,
                angle_down: -0.5,
                angle_up: 0.8,
            },
        };
        let frame = frame_with_config(
            &c,
            &[view(-0.03), view(0.03)],
            ash::vk::Extent2D {
                width: 1680,
                height: 1760,
            },
            55,
        )
        .unwrap()
        .unwrap();
        assert_eq!(frame.eyes[0].source_uv_rect, [0., 0., 0.5, 1.]);
        assert_eq!(frame.eyes[1].source_uv_rect, [0.5, 0., 0.5, 1.]);
        assert_eq!(frame.eyes[0].source, [-1.2, 0.8, -0.7, 1.1]);
        assert_eq!(frame.eyes[1].source, [-0.8, 1.3, -0.9, 1.0]);
        assert!(frame.eyes[0].source_sample_bounds[2] < 0.5);
        assert!(frame.eyes[1].source_sample_bounds[0] > 0.5);
        assert_eq!(
            frame.metadata["source_size"],
            serde_json::json!([2560, 1280])
        );
        assert_eq!(frame.metadata["source_expected_eye_selection"], 2);
        for key in [
            "source_capture_pose_available",
            "source_bounds_qualified",
            "capture_half_labels_verified",
            "same_mono_source_to_both_eyes",
        ] {
            assert_eq!(frame.metadata[key], false, "{key}");
        }
    }
}
