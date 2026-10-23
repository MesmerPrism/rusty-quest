//! Debug-selected read-only geometry of the original Compute widget.
use serde_json::{json, Value};

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Frame {
    pub generation: u64,
    pub revision: u64,
    pub center: [f32; 3],
    pub right: [f32; 3],
    pub up: [f32; 3],
    pub width: f32,
    pub height: f32,
    pub visible: bool,
    pub tracking_valid: bool,
    pub reference_space: String,
    pub instance: String,
    pub session: String,
}

#[derive(Clone, Debug, PartialEq)]
struct Layout {
    generation: u64,
    revision: u64,
    width: u32,
    height: u32,
    bounds: [i32; 4],
    clipped: [i32; 4],
    enabled: bool,
    visible: bool,
}

#[derive(Clone)]
pub(crate) struct Prepared {
    frame: Frame,
    layout: Option<Layout>,
}

#[derive(Default)]
pub(crate) struct Observer {
    layout: Option<Layout>,
    submitted: Option<(Prepared, u64)>,
}

const MAX_INTEGER: u64 = 9_007_199_254_740_991;
fn exact(value: &Value, keys: &[&str]) -> Result<(), String> {
    let object = value.as_object().ok_or("object required")?;
    if object.len() != keys.len() || object.keys().any(|k| !keys.contains(&k.as_str())) {
        return Err("exact geometry fields required".into());
    }
    Ok(())
}
fn integer(value: &Value) -> Result<u64, String> {
    value
        .as_u64()
        .filter(|n| *n <= MAX_INTEGER)
        .ok_or("bounded exact integer required".into())
}
fn rectangle(value: &Value) -> Result<[i32; 4], String> {
    let values = value
        .as_array()
        .filter(|a| a.len() == 4)
        .ok_or("rectangle required")?;
    let mut result = [0; 4];
    for (index, v) in values.iter().enumerate() {
        result[index] = v
            .as_i64()
            .filter(|n| (-16384..=32768).contains(n))
            .ok_or("bounded rectangle integer required")? as i32;
    }
    if result[0] > result[2] || result[1] > result[3] {
        return Err("reversed rectangle".into());
    }
    Ok(result)
}
impl Layout {
    fn parse(body: &str) -> Result<Self, String> {
        if body.len() > 4096 {
            return Err("bounded widget layout required".into());
        }
        let v: Value = serde_json::from_str(body).map_err(|e| e.to_string())?;
        exact(
            &v,
            &[
                "panel_generation",
                "layout_revision",
                "surface_width_pixels",
                "surface_height_pixels",
                "widget",
            ],
        )?;
        let w = &v["widget"];
        exact(
            w,
            &[
                "id",
                "enabled",
                "visible",
                "bounds_pixels",
                "clipped_bounds_pixels",
            ],
        )?;
        if w["id"] != "compute" {
            return Err("original Compute widget required".into());
        }
        let width = integer(&v["surface_width_pixels"])?;
        let height = integer(&v["surface_height_pixels"])?;
        let generation = integer(&v["panel_generation"])?;
        let revision = integer(&v["layout_revision"])?;
        if generation == 0
            || revision == 0
            || !(1..=8192).contains(&width)
            || !(1..=8192).contains(&height)
        {
            return Err("layout identity or dimensions".into());
        }
        let bounds = rectangle(&w["bounds_pixels"])?;
        let clipped = rectangle(&w["clipped_bounds_pixels"])?;
        let visible = w["visible"].as_bool().ok_or("visible boolean required")?;
        if clipped[0] < 0
            || clipped[1] < 0
            || clipped[2] > width as i32
            || clipped[3] > height as i32
            || (visible
                && (clipped[0] < bounds[0]
                    || clipped[1] < bounds[1]
                    || clipped[2] > bounds[2]
                    || clipped[3] > bounds[3]))
        {
            return Err("clipped bounds outside widget/surface".into());
        }
        Ok(Self {
            generation,
            revision,
            width: width as u32,
            height: height as u32,
            bounds,
            clipped,
            visible,
            enabled: w["enabled"].as_bool().ok_or("enabled boolean required")?,
        })
    }
    fn usable(&self) -> bool {
        self.visible
            && self.enabled
            && self.clipped[2] > self.clipped[0]
            && self.clipped[3] > self.clipped[1]
    }
}
impl Frame {
    fn valid(&self) -> bool {
        let dot = |a: [f32; 3], b: [f32; 3]| a.iter().zip(b).map(|(a, b)| a * b).sum::<f32>();
        self.generation > 0
            && self.generation <= MAX_INTEGER
            && self.revision <= MAX_INTEGER
            && self
                .center
                .iter()
                .chain(self.right.iter())
                .chain(self.up.iter())
                .all(|n| n.is_finite())
            && self.width.is_finite()
            && self.height.is_finite()
            && self.width > 0.
            && self.height > 0.
            && self.width <= 100.
            && self.height <= 100.
            && (dot(self.right, self.right) - 1.).abs() < 0.001
            && (dot(self.up, self.up) - 1.).abs() < 0.001
            && dot(self.right, self.up).abs() < 0.001
            && matches!(self.reference_space.as_str(), "LOCAL" | "STAGE")
            && !self.instance.is_empty()
            && !self.session.is_empty()
    }
}
impl Observer {
    fn update(&mut self, enabled: bool, generation: u64, body: &str) -> Result<(), String> {
        if !enabled {
            return Ok(());
        }
        let next = Layout::parse(body)?;
        if next.generation != generation {
            return Err("stale producer generation".into());
        }
        if let Some(prior) = &self.layout {
            if prior.generation == generation
                && (next.revision < prior.revision
                    || (next.revision == prior.revision && next != *prior))
            {
                return Err("stale or reused layout revision".into());
            }
        }
        self.layout = Some(next);
        Ok(())
    }
    fn freeze(&self, frame: Frame) -> Prepared {
        Prepared {
            frame,
            layout: self.layout.clone(),
        }
    }
    fn submitted(&mut self, enabled: bool, prepared: Prepared, number: u64) {
        if !enabled
            || !prepared.frame.valid()
            || number > MAX_INTEGER
            || self.submitted.as_ref().is_some_and(|(prior, n)| {
                prior.frame.generation == prepared.frame.generation && number < *n
            })
        {
            return;
        }
        self.submitted = Some((prepared, number));
    }
    fn readback(
        &self,
        enabled: bool,
        owner: &str,
        session: &str,
        current: &Frame,
    ) -> Option<Value> {
        if !enabled {
            return None;
        }
        let mut value = json!({"schema":"rusty.quest.development_input_geometry.v1","status":"pending","evidence":"native-submitted-frame","host_kind":"native-openxr","process_id":std::process::id(),"host_session_id":session,"owner_token":owner,"panel_generation":current.generation});
        let Some((seen, number)) = &self.submitted else {
            return Some(value);
        };
        let Some(layout) = &seen.layout else {
            return Some(value);
        };
        if seen.frame != *current
            || self.layout.as_ref() != Some(layout)
            || layout.generation != current.generation
        {
            value["status"] = json!("stale");
            return Some(value);
        }
        if !current.valid() || !current.visible || !current.tracking_valid || !layout.usable() {
            value["status"] = json!("unavailable");
            return Some(value);
        }
        value["status"] = json!("applied");
        let object = value.as_object_mut().unwrap();
        for (key,v) in json!({"layout_revision":layout.revision,"surface_width_pixels":layout.width,"surface_height_pixels":layout.height,
            "coordinate_system":"openxr-right-handed","reference_space":current.reference_space,"center":current.center,"right":current.right,"up":current.up,
            "width_meters":current.width,"height_meters":current.height,"tracking_valid":current.tracking_valid,"panel_visible":current.visible,
            "submitted_frame":number,"pose_revision":current.revision,"effective_pose_revision":current.revision,
            "openxr_instance_id":current.instance,"openxr_session_id":current.session,
            "widget":{"id":"compute","enabled":layout.enabled,"visible":layout.visible,"bounds_pixels":layout.bounds,"clipped_bounds_pixels":layout.clipped}}).as_object().unwrap() {object.insert(key.clone(),v.clone());}
        Some(value)
    }
}

fn layer_loaded_from_maps(enabled: bool, body: &str) -> bool {
    enabled
        && body.lines().any(|line| {
            line.split_whitespace()
                .nth(5)
                .and_then(|p| std::path::Path::new(p).file_name())
                .is_some_and(|p| p == "libXrApiLayer_METAX_operator.so")
        })
}
#[cfg(native_meta_xr_operator)]
pub(crate) fn operator_layer_loaded() -> bool {
    use std::io::Read;
    let Ok(file) = std::fs::File::open("/proc/self/maps") else {
        return false;
    };
    let mut data = Vec::new();
    if file.take(1_048_577).read_to_end(&mut data).is_err() || data.len() > 1_048_576 {
        return false;
    }
    std::str::from_utf8(&data).is_ok_and(|body| layer_loaded_from_maps(true, body))
}

#[cfg(native_meta_xr_operator)]
mod selected {
    use super::*;
    use std::sync::{Mutex, OnceLock};
    static OBSERVER: OnceLock<Mutex<Observer>> = OnceLock::new();
    fn observer() -> &'static Mutex<Observer> {
        OBSERVER.get_or_init(|| Mutex::new(Observer::default()))
    }
    pub(crate) fn invalidate() {
        observer()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .submitted = None;
    }
    pub(crate) fn reset() {
        *observer().lock().unwrap_or_else(|p| p.into_inner()) = Observer::default();
    }
    pub(crate) fn update(generation: u64, body: &str) -> Result<(), String> {
        observer()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .update(true, generation, body)
    }
    pub(crate) fn freeze(frame: Frame) -> Prepared {
        observer()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .freeze(frame)
    }
    pub(crate) fn submitted(prepared: Prepared, number: u64) {
        observer()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .submitted(true, prepared, number);
    }
    pub(crate) fn readback_current(owner: &str, session: &str) -> Option<Value> {
        let controls = crate::native_mesh_ui::snapshot();
        let guard = observer().lock().unwrap_or_else(|p| p.into_inner());
        let Some((seen, _)) = &guard.submitted else {
            return Some(
                json!({"schema":"rusty.quest.development_input_geometry.v1","status":"pending","evidence":"native-submitted-frame","host_kind":"native-openxr","process_id":std::process::id(),"host_session_id":session,"owner_token":owner}),
            );
        };
        let mut current = seen.frame.clone();
        current.generation = controls.generation;
        current.revision = controls.revision;
        current.center = controls.pose.center;
        current.right = controls.pose.right;
        current.up = controls.pose.up;
        current.width = controls.pose.width;
        current.height = controls.pose.height;
        current.visible = controls.panel_visible && controls.pose_initialized;
        guard.readback(true, owner, session, &current)
    }
}
#[cfg(native_meta_xr_operator)]
pub(crate) use selected::*;

#[cfg(test)]
mod tests {
    use super::*;
    fn frame() -> Frame {
        Frame {
            generation: 7,
            revision: 3,
            center: [0., 1., -1.4],
            right: [1., 0., 0.],
            up: [0., 1., 0.],
            width: 0.96,
            height: 0.72,
            visible: true,
            tracking_valid: true,
            reference_space: "LOCAL".into(),
            instance: "actual-instance".into(),
            session: "actual-xr-session".into(),
        }
    }
    fn layout(revision: u64) -> String {
        json!({"panel_generation":7,"layout_revision":revision,"surface_width_pixels":1024,"surface_height_pixels":768,"widget":{"id":"compute","enabled":true,"visible":true,"bounds_pixels":[40,140,984,208],"clipped_bounds_pixels":[40,140,984,208]}}).to_string()
    }
    #[test]
    fn original_widget_is_pending_until_successful_frame_and_disabled_is_inert() {
        let mut observer = Observer::default();
        let current = frame();
        observer.update(false, 7, &layout(1)).unwrap();
        observer.submitted(false, observer.freeze(current.clone()), 10);
        assert!(observer.layout.is_none() && observer.submitted.is_none());
        assert!(observer
            .readback(false, "owner", "host", &current)
            .is_none());
        observer.update(true, 7, &layout(1)).unwrap();
        assert_eq!(
            observer.readback(true, "owner", "host", &current).unwrap()["status"],
            "pending"
        );
        observer.submitted(true, observer.freeze(current.clone()), 10);
        let value = observer.readback(true, "owner", "host", &current).unwrap();
        assert_eq!(value["status"], "applied");
        assert_eq!(value["widget"]["id"], "compute");
        assert_eq!(value["submitted_frame"], 10);
    }
    #[test]
    fn stale_layout_pose_generation_and_regressing_frames_never_become_targets() {
        let mut o = Observer::default();
        let f = frame();
        o.update(true, 7, &layout(1)).unwrap();
        let frozen = o.freeze(f.clone());
        o.update(true, 7, &layout(2)).unwrap();
        o.submitted(true, frozen, 10);
        assert_eq!(
            o.readback(true, "owner", "host", &f).unwrap()["status"],
            "stale"
        );
        o.submitted(true, o.freeze(f.clone()), 11);
        assert_eq!(
            o.readback(true, "owner", "host", &f).unwrap()["status"],
            "applied"
        );
        let mut newer = f.clone();
        newer.revision += 1;
        newer.center[0] += 0.1;
        assert_eq!(
            o.readback(true, "owner", "host", &newer).unwrap()["status"],
            "stale"
        );
        o.submitted(true, o.freeze(newer.clone()), 9);
        assert_eq!(
            o.readback(true, "owner", "host", &f).unwrap()["submitted_frame"],
            11
        );
        assert!(o.update(true, 8, &layout(3)).is_err());
        let changed = layout(2).replace("984", "900");
        assert!(o.update(true, 7, &changed).is_err());
        assert!(o.update(true, 7, &layout(1)).is_err());
    }
    #[test]
    fn hidden_tracking_invalid_clipped_and_nonfinite_inputs_are_not_usable() {
        for change in ["hidden", "tracking", "space", "parallel", "nan"] {
            let mut o = Observer::default();
            let mut f = frame();
            o.update(true, 7, &layout(1)).unwrap();
            match change {
                "hidden" => f.visible = false,
                "tracking" => f.tracking_valid = false,
                "space" => f.reference_space = "LOCAL_FLOOR".into(),
                "parallel" => f.up = f.right,
                _ => f.center[0] = f32::NAN,
            }
            o.submitted(true, o.freeze(f.clone()), 0);
            assert_ne!(
                o.readback(true, "owner", "host", &f).unwrap()["status"],
                "applied"
            );
        }
        for patch in [
            json!({"id":"other"}),
            json!({"visible":false}),
            json!({"enabled":false}),
            json!({"clipped_bounds_pixels":[0,0,0,0]}),
        ] {
            let mut v: Value = serde_json::from_str(&layout(1)).unwrap();
            for (k, value) in patch.as_object().unwrap() {
                v["widget"][k] = value.clone();
            }
            let mut o = Observer::default();
            let f = frame();
            if o.update(true, 7, &v.to_string()).is_ok() {
                o.submitted(true, o.freeze(f.clone()), 1);
                assert_ne!(
                    o.readback(true, "owner", "host", &f).unwrap()["status"],
                    "applied"
                );
            }
        }
        let mut v: Value = serde_json::from_str(&layout(1)).unwrap();
        v["widget"]["text"] = json!("private notes");
        assert!(Observer::default().update(true, 7, &v.to_string()).is_err());
    }
    #[test]
    fn actual_layer_mapping_requires_selection_and_exact_file_name() {
        let line="7000-8000 r-xp 0000 01:02 42 /data/app/example/lib/arm64/libXrApiLayer_METAX_operator.so";
        assert!(layer_loaded_from_maps(true, line));
        assert!(!layer_loaded_from_maps(false, line));
        for path in [
            "libXrApiLayer_METAX_operator.so.backup",
            "prefixlibXrApiLayer_METAX_operator.so",
            "libXrApiLayer_METAX_operator.so/file",
        ] {
            assert!(!layer_loaded_from_maps(
                true,
                &line.replace("libXrApiLayer_METAX_operator.so", path)
            ));
        }
    }
}
