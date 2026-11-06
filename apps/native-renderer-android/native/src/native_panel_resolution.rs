//! Raster recommendations are independent of the panel's logical input and meter geometry.
//! A recommendation never replaces a live reader; apply only at a fenced producer restart.
pub(crate) const LOGICAL_WIDTH: u32 = 1024;
pub(crate) const LOGICAL_HEIGHT: u32 = 768;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct RasterExtent {
    pub(crate) width: u32,
    pub(crate) height: u32,
}
impl RasterExtent {
    pub(crate) const BASE: Self = Self {
        width: 1024,
        height: 768,
    };
    pub(crate) const MEDIUM: Self = Self {
        width: 1536,
        height: 1152,
    };
    pub(crate) const HIGH: Self = Self {
        width: 2048,
        height: 1536,
    };
    pub(crate) fn supported(self) -> bool {
        [Self::BASE, Self::MEDIUM, Self::HIGH].contains(&self)
    }
    pub(crate) fn scale(self) -> f32 {
        self.width as f32 / LOGICAL_WIDTH as f32
    }
    pub(crate) fn logical_to_raster(self, logical: [f32; 2]) -> [f32; 2] {
        [logical[0] * self.scale(), logical[1] * self.scale()]
    }
}
/// World-space forms plane, independent of logical layout and raster density.
#[derive(Clone, Copy, Debug)]
pub(crate) struct ProjectionPanel {
    pub(crate) center: [f32; 3],
    pub(crate) right: [f32; 3],
    pub(crate) up: [f32; 3],
    pub(crate) width: f32,
    pub(crate) height: f32,
}
/// Current OpenXR eye pose, tangents [left,right,down,up], and actual eye viewport pixels.
#[derive(Clone, Copy, Debug)]
pub(crate) struct ProjectionEye {
    pub(crate) position: [f32; 3],
    pub(crate) orientation_xyzw: [f32; 4],
    pub(crate) fov_tangents: [f32; 4],
    pub(crate) viewport: [u32; 2],
}
fn dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    a.iter().zip(b).map(|(a, b)| a * b).sum()
}
fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
fn eye_local(eye: ProjectionEye, point: [f32; 3]) -> [f32; 3] {
    let delta = std::array::from_fn(|i| point[i] - eye.position[i]);
    let inverse = [
        -eye.orientation_xyzw[0],
        -eye.orientation_xyzw[1],
        -eye.orientation_xyzw[2],
    ];
    let t = cross(inverse, delta).map(|v| v * 2.0);
    let c = cross(inverse, t);
    std::array::from_fn(|i| delta[i] + eye.orientation_xyzw[3] * t[i] + c[i])
}
/// Maximum intrinsic U/V edge footprint over visible eyes, including perspective magnification.
/// None means hold the existing raster: invalid tracking/FOV, near-plane crossing, or offscreen.
/// Partially offscreen panels retain their unclipped footprint so re-entry cannot cause churn.
pub(crate) fn projected_footprint(
    panel: ProjectionPanel,
    eyes: &[ProjectionEye],
) -> Option<[f32; 2]> {
    if eyes.is_empty()
        || panel
            .center
            .iter()
            .chain(panel.right.iter())
            .chain(panel.up.iter())
            .any(|v| !v.is_finite())
        || !panel.width.is_finite()
        || !panel.height.is_finite()
        || panel.width <= 0.0
        || panel.height <= 0.0
        || (dot(panel.right, panel.right) - 1.0).abs() > 0.01
        || (dot(panel.up, panel.up) - 1.0).abs() > 0.01
        || dot(panel.right, panel.up).abs() > 0.01
    {
        return None;
    }
    let mut result = [0.0_f32; 2];
    let mut visible = false;
    for &eye in eyes {
        if eye
            .position
            .iter()
            .chain(eye.orientation_xyzw.iter())
            .chain(eye.fov_tangents.iter())
            .any(|v| !v.is_finite())
            || eye.viewport.contains(&0)
            || (eye.orientation_xyzw.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() > 0.01
        {
            return None;
        }
        let [left, right, down, up] = eye.fov_tangents;
        if right <= left || up <= down {
            return None;
        }
        let mut projected = [[0.0_f32; 2]; 4];
        let mut tangents = [[0.0_f32; 2]; 4];
        let mut depth = [0.0_f32; 4];
        for i in 0..4 {
            let u = if i & 1 == 0 { -0.5 } else { 0.5 };
            let v = if i & 2 == 0 { -0.5 } else { 0.5 };
            let point = std::array::from_fn(|axis| {
                panel.center[axis]
                    + panel.right[axis] * u * panel.width
                    + panel.up[axis] * v * panel.height
            });
            let local = eye_local(eye, point);
            depth[i] = -local[2];
            // Matches the renderer's 5cm near plane; never divide a clipped/behind corner.
            if local.iter().any(|v| !v.is_finite()) || depth[i] <= 0.05 {
                return None;
            }
            tangents[i] = [local[0] / depth[i], local[1] / depth[i]];
            projected[i] = [
                (tangents[i][0] - left) / (right - left) * eye.viewport[0] as f32,
                (tangents[i][1] - down) / (up - down) * eye.viewport[1] as f32,
            ];
            if projected[i].iter().any(|v| !v.is_finite()) {
                return None;
            }
        }
        if tangents.iter().all(|p| p[0] < left)
            || tangents.iter().all(|p| p[0] > right)
            || tangents.iter().all(|p| p[1] < down)
            || tangents.iter().all(|p| p[1] > up)
        {
            continue;
        }
        visible = true;
        for (axis, edges) in [(0, [(0, 1), (2, 3)]), (1, [(0, 2), (1, 3)])] {
            for (a, b) in edges {
                let length =
                    (projected[b][0] - projected[a][0]).hypot(projected[b][1] - projected[a][1]);
                // At the nearer endpoint perspective's derivative exceeds average edge length.
                let magnification = length * (depth[a] / depth[b]).max(depth[b] / depth[a]);
                if !magnification.is_finite() {
                    return None;
                }
                result[axis] = result[axis].max(magnification);
            }
        }
    }
    (visible && result.iter().all(|v| v.is_finite() && *v > 0.0)).then_some(result)
}
/// Proposal-only quantized policy: 12 consecutive observations and 120-frame adoption dwell.
/// The owner must acknowledge a successfully committed live resize with applied().
#[derive(Clone, Copy, Debug)]
pub(crate) struct AdaptiveResolution {
    selected: RasterExtent,
    last_change: u64,
    last_frame: Option<u64>,
    candidate: RasterExtent,
    candidate_frames: u32,
}
impl AdaptiveResolution {
    pub(crate) fn new(selected: RasterExtent) -> Self {
        let selected = if selected.supported() {
            selected
        } else {
            RasterExtent::BASE
        };
        Self {
            selected,
            last_change: 0,
            last_frame: None,
            candidate: selected,
            candidate_frames: 0,
        }
    }
    pub(crate) fn selected(self) -> RasterExtent {
        self.selected
    }
    pub(crate) fn applied(&mut self, extent: RasterExtent, frame: u64) -> bool {
        if !extent.supported()
            || frame < self.last_change
            || self.last_frame.is_some_and(|last| frame < last)
        {
            return false;
        }
        if extent != self.selected {
            self.selected = extent;
            self.last_change = frame;
        }
        self.last_frame = Some(frame);
        self.candidate = extent;
        self.candidate_frames = 0;
        true
    }
    /// Invalid/absent projection must still be observed to reset consecutive-candidate stability.
    pub(crate) fn hold(&mut self, frame: u64) -> RasterExtent {
        if self.last_frame.is_none_or(|last| frame >= last) {
            self.last_frame = Some(frame);
            self.candidate = self.selected;
            self.candidate_frames = 0;
        }
        self.selected
    }
    pub(crate) fn recommend(&mut self, projected: [f32; 2], frame: u64) -> RasterExtent {
        if self.last_frame.is_some_and(|last| frame <= last) {
            return self.selected;
        }
        if projected.iter().any(|v| !v.is_finite() || *v <= 0.0) {
            return self.hold(frame);
        }
        let consecutive = self
            .last_frame
            .is_some_and(|last| last.checked_add(1) == Some(frame));
        self.last_frame = Some(frame);
        let demand =
            (projected[0] / LOGICAL_WIDTH as f32).max(projected[1] / LOGICAL_HEIGHT as f32);
        let next = if demand > self.selected.scale() {
            if demand > 1.5 {
                RasterExtent::HIGH
            } else {
                RasterExtent::MEDIUM
            }
        } else if demand <= 0.8 {
            RasterExtent::BASE
        } else if self.selected == RasterExtent::HIGH && demand <= 1.2 {
            RasterExtent::MEDIUM
        } else {
            self.selected
        };
        if next == self.selected {
            self.candidate = next;
            self.candidate_frames = 0;
            return self.selected;
        }
        if consecutive && next == self.candidate {
            self.candidate_frames = self.candidate_frames.saturating_add(1);
        } else {
            self.candidate = next;
            self.candidate_frames = 1;
        }
        if self.candidate_frames >= 12 && frame.saturating_sub(self.last_change) >= 120 {
            next
        } else {
            self.selected
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn panel() -> ProjectionPanel {
        ProjectionPanel {
            center: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            width: 1.0,
            height: 0.75,
        }
    }
    fn eye() -> ProjectionEye {
        ProjectionEye {
            position: [0.0; 3],
            orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
            fov_tangents: [-1.0, 1.0, -1.0, 1.0],
            viewport: [2000, 2000],
        }
    }
    fn close(actual: [f32; 2], expected: [f32; 2]) {
        for i in 0..2 {
            assert!(
                (actual[i] - expected[i]).abs() < 0.02,
                "{actual:?} != {expected:?}"
            );
        }
    }
    #[test]
    fn both_eyes_asymmetric_fov_and_intrinsic_aspect() {
        close(
            projected_footprint(panel(), &[eye(), eye()]).unwrap(),
            [1000.0, 750.0],
        );
        let mut sharper = eye();
        sharper.fov_tangents = [-0.5, 0.5, -1.0, 1.0];
        close(
            projected_footprint(panel(), &[eye(), sharper]).unwrap(),
            [2000.0, 750.0],
        );
        let mut taller = eye();
        taller.viewport = [2000, 3000];
        close(
            projected_footprint(panel(), &[sharper, taller]).unwrap(),
            [2000.0, 1125.0],
        );
    }
    #[test]
    fn rotation_preserves_intrinsic_axis_footprint() {
        let mut p = panel();
        p.right = [0.0, 1.0, 0.0];
        p.up = [-1.0, 0.0, 0.0];
        close(projected_footprint(p, &[eye()]).unwrap(), [1000.0, 750.0]);
        let mut e = eye();
        e.orientation_xyzw = [0.0, 0.0, 0.5_f32.sqrt(), 0.5_f32.sqrt()];
        close(projected_footprint(p, &[e]).unwrap(), [1000.0, 750.0]);
    }
    #[test]
    fn physical_size_and_distance_control_footprint() {
        let mut p = panel();
        p.center[2] = -2.0;
        close(projected_footprint(p, &[eye()]).unwrap(), [500.0, 375.0]);
        p.width = 2.0;
        p.height = 1.5;
        close(projected_footprint(p, &[eye()]).unwrap(), [1000.0, 750.0]);
    }
    #[test]
    fn invalid_near_behind_and_fully_offscreen_hold() {
        let mut p = panel();
        p.center[2] = -0.04;
        assert!(projected_footprint(p, &[eye()]).is_none());
        p.center[2] = 1.0;
        assert!(projected_footprint(p, &[eye()]).is_none());
        p = panel();
        p.center[0] = 4.0;
        assert!(projected_footprint(p, &[eye(), eye()]).is_none());
        p.center[0] = 1.2;
        assert!(projected_footprint(p, &[eye()]).is_some());
        let mut e = eye();
        e.orientation_xyzw = [0.0; 4];
        assert!(projected_footprint(panel(), &[e]).is_none());
        e = eye();
        e.fov_tangents[1] = f32::NAN;
        assert!(projected_footprint(panel(), &[e]).is_none());
        e = eye();
        e.viewport[0] = 0;
        assert!(projected_footprint(panel(), &[e]).is_none());
        assert!(projected_footprint(panel(), &[]).is_none());
    }
    #[test]
    fn tilted_perspective_near_crossing_and_single_visible_eye() {
        let mut tilted = panel();
        tilted.right = [0.8, 0.0, 0.6];
        let footprint = projected_footprint(tilted, &[eye()]).unwrap();
        // Near-end derivative is larger than its average projected U-edge footprint.
        assert!(footprint[0] > 1000.0);
        tilted.center[2] = -0.4;
        tilted.width = 1.5;
        assert!(projected_footprint(tilted, &[eye()]).is_none());
        let mut away = eye();
        away.position[0] = 10.0;
        close(
            projected_footprint(panel(), &[away, eye()]).unwrap(),
            [1000.0, 750.0],
        );
        let mut invalid = eye();
        invalid.position[0] = f32::INFINITY;
        assert!(projected_footprint(panel(), &[eye(), invalid]).is_none());
    }
    #[test]
    fn stable_proposal_deferral_and_actual_adoption_dwell() {
        let mut p = AdaptiveResolution::new(RasterExtent::MEDIUM);
        for frame in 1..120 {
            assert_eq!(p.recommend([1900.0, 1400.0], frame), RasterExtent::MEDIUM);
        }
        assert_eq!(p.recommend([1900.0, 1400.0], 120), RasterExtent::HIGH);
        assert_eq!(p.selected(), RasterExtent::MEDIUM);
        assert_eq!(p.recommend([1900.0, 1400.0], 121), RasterExtent::HIGH);
        assert!(p.applied(RasterExtent::HIGH, 121));
        for frame in 122..241 {
            assert_eq!(p.recommend([700.0, 500.0], frame), RasterExtent::HIGH);
        }
        assert_eq!(p.recommend([700.0, 500.0], 241), RasterExtent::BASE);
        assert_eq!(p.selected(), RasterExtent::HIGH);
    }
    #[test]
    fn one_frame_outliers_gaps_invalid_and_duplicate_calls_cannot_adopt() {
        let mut p = AdaptiveResolution::new(RasterExtent::MEDIUM);
        for frame in 120..131 {
            assert_eq!(p.recommend([1900.0, 1400.0], frame), RasterExtent::MEDIUM);
        }
        assert_eq!(p.recommend([1536.0, 1152.0], 131), RasterExtent::MEDIUM);
        for frame in 132..143 {
            assert_eq!(p.recommend([1900.0, 1400.0], frame), RasterExtent::MEDIUM);
        }
        assert_eq!(p.hold(143), RasterExtent::MEDIUM);
        for frame in 144..155 {
            assert_eq!(p.recommend([1900.0, 1400.0], frame), RasterExtent::MEDIUM);
        }
        assert_eq!(p.recommend([1900.0, 1400.0], 155), RasterExtent::HIGH);
        assert_eq!(p.recommend([1900.0, 1400.0], 155), RasterExtent::MEDIUM);
        assert_eq!(p.recommend([1900.0, 1400.0], 200), RasterExtent::MEDIUM);
        assert!(!p.applied(
            RasterExtent {
                width: 9,
                height: 9
            },
            200
        ));
        assert!(!p.applied(RasterExtent::HIGH, 199));
    }
    #[test]
    fn hysteresis_cap_and_physical_input_invariant() {
        let mut p = AdaptiveResolution::new(RasterExtent::HIGH);
        for frame in 120..150 {
            assert_eq!(p.recommend([1400.0, 1050.0], frame), RasterExtent::HIGH);
        }
        for frame in 150..161 {
            assert_eq!(p.recommend([1100.0, 825.0], frame), RasterExtent::HIGH);
        }
        assert_eq!(p.recommend([1100.0, 825.0], 161), RasterExtent::MEDIUM);
        assert!(p.applied(RasterExtent::MEDIUM, 161));
        assert_eq!(p.recommend([f32::NAN, 1.0], 162), RasterExtent::MEDIUM);
        for frame in 270..281 {
            assert_eq!(p.recommend([1e10, 1e10], frame), RasterExtent::MEDIUM);
        }
        assert_eq!(p.recommend([1e10, 1e10], 281), RasterExtent::HIGH);
        for extent in [RasterExtent::BASE, RasterExtent::MEDIUM, RasterExtent::HIGH] {
            let pixel = extent.logical_to_raster([240.0, 180.0]);
            assert_eq!(pixel[0] / extent.width as f32, 240.0 / 1024.0);
            assert_eq!(pixel[1] / extent.height as f32, 180.0 / 768.0);
        }
    }
}
