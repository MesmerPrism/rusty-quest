//! Bounded panel geometry and attachment policy, independent of renderer and widget owner.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PanelPose {
    pub(crate) center: [f32; 3],
    pub(crate) right: [f32; 3],
    pub(crate) up: [f32; 3],
    pub(crate) width: f32,
    pub(crate) height: f32,
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
impl PanelPose {
    pub(crate) fn valid(self) -> bool {
        self.center
            .iter()
            .chain(self.right.iter())
            .chain(self.up.iter())
            .all(|v| v.is_finite())
            && self.width.is_finite()
            && self.height.is_finite()
            && self.width > 0.0
            && self.height > 0.0
            && (dot(self.right, self.right) - 1.0).abs() < 0.01
            && (dot(self.up, self.up) - 1.0).abs() < 0.01
            && dot(self.right, self.up).abs() < 0.01
    }
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct LogicalBounds {
    pub(crate) min: [f32; 2],
    pub(crate) max: [f32; 2],
}
impl LogicalBounds {
    pub(crate) fn valid(self) -> bool {
        self.min
            .iter()
            .chain(self.max.iter())
            .all(|v| v.is_finite())
            && (0..2).all(|i| self.max[i] > self.min[i] && (self.max[i] - self.min[i]).is_finite())
    }
    pub(crate) fn contains(self, p: [f32; 2]) -> bool {
        self.valid()
            && (0..2).all(|i| p[i].is_finite() && p[i] >= self.min[i] && p[i] < self.max[i])
    }
}
#[derive(Clone, Copy, Debug)]
pub(crate) struct Ray {
    pub(crate) origin: [f32; 3],
    pub(crate) direction: [f32; 3],
}
#[derive(Clone, Copy, Debug)]
pub(crate) struct PanelModel {
    pub(crate) id: u64,
    pub(crate) pose: PanelPose,
    pub(crate) logical: LogicalBounds,
    pub(crate) visible: bool,
    pub(crate) enabled: bool,
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PanelHit {
    pub(crate) panel: u64,
    pub(crate) logical: [f32; 2],
    pub(crate) distance_m: f32,
}
impl PanelModel {
    /// Derive a separate form/toolbar surface while retaining the parent's logical coordinates.
    /// Regions may extend outside the form (e.g. native rows below y=768 or above y=0).
    pub(crate) fn region(self, id: u64, logical: LogicalBounds) -> Option<Self> {
        if id == 0 || !self.pose.valid() || !self.logical.valid() || !logical.valid() {
            return None;
        }
        let extent = std::array::from_fn::<_, 2, _>(|i| self.logical.max[i] - self.logical.min[i]);
        let size = std::array::from_fn::<_, 2, _>(|i| logical.max[i] - logical.min[i]);
        let center = std::array::from_fn::<_, 2, _>(|i| {
            (logical.min[i] + size[i] * 0.5 - self.logical.min[i]) / extent[i]
        });
        let pose = PanelPose {
            center: std::array::from_fn(|i| {
                self.pose.center[i]
                    + self.pose.right[i] * (center[0] - 0.5) * self.pose.width
                    + self.pose.up[i] * (0.5 - center[1]) * self.pose.height
            }),
            right: self.pose.right,
            up: self.pose.up,
            width: self.pose.width * size[0] / extent[0],
            height: self.pose.height * size[1] / extent[1],
        };
        pose.valid().then_some(Self {
            id,
            pose,
            logical,
            visible: self.visible,
            enabled: self.enabled,
        })
    }
    /// Unclamped plane mapping supports captured drags outside the panel's bounds.
    /// Hidden/disabled/invalid geometry never receives input, even during capture.
    pub(crate) fn map_ray(self, ray: Ray) -> Option<PanelHit> {
        if self.id == 0
            || !self.visible
            || !self.enabled
            || !self.pose.valid()
            || !self.logical.valid()
            || ray
                .origin
                .iter()
                .chain(ray.direction.iter())
                .any(|v| !v.is_finite())
        {
            return None;
        }
        let length = dot(ray.direction, ray.direction).sqrt();
        if !length.is_finite() || length < 1e-6 {
            return None;
        }
        let direction = ray.direction.map(|v| v / length);
        let normal = cross(self.pose.right, self.pose.up);
        let denominator = dot(direction, normal);
        if denominator.abs() < 1e-6 {
            return None;
        }
        let offset = std::array::from_fn(|i| self.pose.center[i] - ray.origin[i]);
        let distance = dot(offset, normal) / denominator;
        if !distance.is_finite() || distance <= 0.0 {
            return None;
        }
        let relative =
            std::array::from_fn(|i| ray.origin[i] + direction[i] * distance - self.pose.center[i]);
        let uv = [
            dot(relative, self.pose.right) / self.pose.width + 0.5,
            0.5 - dot(relative, self.pose.up) / self.pose.height,
        ];
        let logical = std::array::from_fn(|i| {
            self.logical.min[i] + uv[i] * (self.logical.max[i] - self.logical.min[i])
        });
        logical.iter().all(|v| v.is_finite()).then_some(PanelHit {
            panel: self.id,
            logical,
            distance_m: distance,
        })
    }
    pub(crate) fn hit(self, ray: Ray) -> Option<PanelHit> {
        self.map_ray(ray)
            .filter(|hit| self.logical.contains(hit.logical))
    }
}
/// Closest actual plane intersection wins. Exact coplanar ties use stable panel ID.
/// Caller retains one selected owner; list order cannot let another panel steal input.
pub(crate) const MAX_PANELS: usize = 16;
pub(crate) fn closest_hit(panels: &[PanelModel], ray: Ray) -> Option<PanelHit> {
    if panels.len() > MAX_PANELS {
        return None;
    }
    panels
        .iter()
        .filter_map(|panel| panel.hit(ray))
        .min_by(|a, b| {
            a.distance_m
                .total_cmp(&b.distance_m)
                .then(a.panel.cmp(&b.panel))
        })
}
#[derive(Clone, Copy, Debug)]
pub(crate) struct TrackedPose {
    pub(crate) position: [f32; 3],
    pub(crate) orientation_xyzw: [f32; 4],
    pub(crate) valid: bool,
    pub(crate) sequence: u64,
}
fn tracked_valid(p: TrackedPose) -> bool {
    p.valid
        && p.position
            .iter()
            .chain(p.orientation_xyzw.iter())
            .all(|v| v.is_finite())
        && (p.orientation_xyzw.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() < 0.01
}
fn rotate(q: [f32; 4], v: [f32; 3]) -> [f32; 3] {
    let xyz = [q[0], q[1], q[2]];
    let t = cross(xyz, v).map(|v| v * 2.0);
    let c = cross(xyz, t);
    std::array::from_fn(|i| v[i] + q[3] * t[i] + c[i])
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum AttachmentMode {
    World,
    Wrist { local_pose: PanelPose },
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct AttachmentSample {
    pub(crate) pose: PanelPose,
    pub(crate) input_valid: bool,
}
#[derive(Clone, Copy, Debug)]
pub(crate) struct AttachmentState {
    mode: AttachmentMode,
    last: PanelPose,
    last_sequence: Option<u64>,
    input_valid: bool,
}
impl AttachmentState {
    pub(crate) fn new(world: PanelPose) -> Option<Self> {
        world.valid().then_some(Self {
            mode: AttachmentMode::World,
            last: world,
            last_sequence: None,
            input_valid: true,
        })
    }
    pub(crate) fn sample(self) -> AttachmentSample {
        AttachmentSample {
            pose: self.last,
            input_valid: self.input_valid,
        }
    }
    pub(crate) fn mode(self) -> AttachmentMode {
        self.mode
    }
    pub(crate) fn attach_wrist(&mut self, local_pose: PanelPose) -> bool {
        if !local_pose.valid() {
            return false;
        }
        self.mode = AttachmentMode::Wrist { local_pose };
        self.last_sequence = None;
        self.input_valid = false;
        true
    }
    /// Explicit owner recall; never silently moves a lost wrist panel to a guessed head pose.
    pub(crate) fn recall_world(&mut self, pose: PanelPose) -> bool {
        if !pose.valid() {
            return false;
        }
        self.mode = AttachmentMode::World;
        self.last = pose;
        self.last_sequence = None;
        self.input_valid = true;
        true
    }
    /// Call once per current tracking sample. Loss/stale input holds last pose but disables input.
    /// The caller must additionally enforce its tracking timestamp/space/generation authority.
    pub(crate) fn observe_wrist(&mut self, tracking: Option<TrackedPose>) -> AttachmentSample {
        let AttachmentMode::Wrist { local_pose } = self.mode else {
            return self.sample();
        };
        let Some(tracking) = tracking.filter(|p| tracked_valid(*p)) else {
            self.input_valid = false;
            return self.sample();
        };
        if self
            .last_sequence
            .is_some_and(|last| tracking.sequence <= last)
        {
            self.input_valid = false;
            return self.sample();
        }
        let rotated = rotate(tracking.orientation_xyzw, local_pose.center);
        let pose = PanelPose {
            center: std::array::from_fn(|i| tracking.position[i] + rotated[i]),
            right: rotate(tracking.orientation_xyzw, local_pose.right),
            up: rotate(tracking.orientation_xyzw, local_pose.up),
            width: local_pose.width,
            height: local_pose.height,
        };
        if pose.valid() {
            self.last = pose;
            self.last_sequence = Some(tracking.sequence);
            self.input_valid = true;
        } else {
            self.input_valid = false;
        }
        self.sample()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn pose(z: f32) -> PanelPose {
        PanelPose {
            center: [0.0, 0.0, z],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            width: 1.0,
            height: 0.75,
        }
    }
    fn panel(id: u64, z: f32) -> PanelModel {
        PanelModel {
            id,
            pose: pose(z),
            logical: LogicalBounds {
                min: [0.0, 0.0],
                max: [1024.0, 768.0],
            },
            visible: true,
            enabled: true,
        }
    }
    fn ray() -> Ray {
        Ray {
            origin: [0.0; 3],
            direction: [0.0, 0.0, -2.0],
        }
    }
    #[test]
    fn independent_form_and_native_row_hit_exact_original_logical_points() {
        let form = panel(1, -1.0);
        let row = form
            .region(
                2,
                LogicalBounds {
                    min: [0.0, 768.0],
                    max: [1024.0, 1024.0],
                },
            )
            .unwrap();
        let point = Ray {
            origin: [0.0, -0.5, 0.0],
            ..ray()
        };
        let hit = closest_hit(&[form, row], point).unwrap();
        assert_eq!(hit.panel, 2);
        assert!((hit.logical[0] - 512.0).abs() < 0.01);
        assert!((hit.logical[1] - 896.0).abs() < 0.01);
        let parent_point = form.map_ray(point).unwrap().logical;
        assert!(
            (parent_point[0] - hit.logical[0]).abs() < 0.01
                && (parent_point[1] - hit.logical[1]).abs() < 0.01
        );
        let above = form
            .region(
                3,
                LogicalBounds {
                    min: [0.0, -256.0],
                    max: [1024.0, 0.0],
                },
            )
            .unwrap();
        let hit = above
            .hit(Ray {
                origin: [0.0, 0.5, 0.0],
                ..ray()
            })
            .unwrap();
        assert!((hit.logical[1] + 128.0).abs() < 0.01);
        assert!(closest_hit(&[form; MAX_PANELS + 1], ray()).is_none());
    }
    #[test]
    fn wrist_rotation_composes_offset_and_axes_without_changing_physical_size() {
        let mut attachment = AttachmentState::new(pose(-1.0)).unwrap();
        let local = pose(-0.2);
        attachment.attach_wrist(local);
        let h = 0.5_f32.sqrt();
        let sample = attachment.observe_wrist(Some(TrackedPose {
            position: [0.0; 3],
            orientation_xyzw: [0.0, h, 0.0, h],
            valid: true,
            sequence: 1,
        }));
        assert!(sample.input_valid);
        assert!((sample.pose.center[0] + 0.2).abs() < 1e-5);
        assert!((sample.pose.right[2] + 1.0).abs() < 1e-5);
        assert_eq!(sample.pose.width, local.width);
    }
    #[test]
    fn closest_panel_and_order_independent_ties() {
        assert_eq!(
            closest_hit(&[panel(4, -2.0), panel(8, -1.0)], ray())
                .unwrap()
                .panel,
            8
        );
        assert_eq!(
            closest_hit(&[panel(4, -1.0), panel(2, -1.0)], ray())
                .unwrap()
                .panel,
            2
        );
    }
    #[test]
    fn logical_mapping_does_not_depend_on_raster_and_captured_drag_is_unclamped() {
        let p = panel(1, -1.0);
        assert_eq!(p.hit(ray()).unwrap().logical, [512.0, 384.0]);
        let r = Ray {
            origin: [1.0, 0.0, 0.0],
            ..ray()
        };
        assert!(p.hit(r).is_none());
        assert_eq!(p.map_ray(r).unwrap().logical, [1536.0, 384.0]);
    }
    #[test]
    fn invalid_hidden_behind_and_parallel_fail_closed() {
        let mut p = panel(1, -1.0);
        p.visible = false;
        assert!(p.map_ray(ray()).is_none());
        p.visible = true;
        p.enabled = false;
        assert!(p.hit(ray()).is_none());
        assert!(panel(1, 1.0).hit(ray()).is_none());
        assert!(panel(1, -1.0)
            .hit(Ray {
                direction: [1.0, 0.0, 0.0],
                ..ray()
            })
            .is_none());
        p.enabled = true;
        p.pose.width = f32::NAN;
        assert!(p.hit(ray()).is_none());
    }
    #[test]
    fn wrist_loss_and_stale_sample_hold_pose_disable_input_and_recall() {
        let mut a = AttachmentState::new(pose(-1.0)).unwrap();
        let local = pose(-0.2);
        assert!(a.attach_wrist(local));
        assert!(!a.sample().input_valid);
        let tracked = TrackedPose {
            position: [1.0, 2.0, 3.0],
            orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
            valid: true,
            sequence: 1,
        };
        let good = a.observe_wrist(Some(tracked));
        assert!(good.input_valid);
        assert_eq!(good.pose.center, [1.0, 2.0, 2.8]);
        assert_eq!(a.observe_wrist(None).pose, good.pose);
        assert!(!a.sample().input_valid);
        assert!(!a.observe_wrist(Some(tracked)).input_valid);
        assert!(
            a.observe_wrist(Some(TrackedPose {
                sequence: 2,
                ..tracked
            }))
            .input_valid
        );
        assert!(a.recall_world(pose(-1.4)));
        assert_eq!(a.mode(), AttachmentMode::World);
        assert!(a.observe_wrist(None).input_valid);
    }
    #[test]
    fn invalid_wrist_quaternion_never_moves_last_valid_pose() {
        let mut a = AttachmentState::new(pose(-1.0)).unwrap();
        a.attach_wrist(pose(-0.2));
        let before = a.sample().pose;
        let sample = a.observe_wrist(Some(TrackedPose {
            position: [0.0; 3],
            orientation_xyzw: [0.0; 4],
            valid: true,
            sequence: 4,
        }));
        assert_eq!(sample.pose, before);
        assert!(!sample.input_valid);
    }
}
