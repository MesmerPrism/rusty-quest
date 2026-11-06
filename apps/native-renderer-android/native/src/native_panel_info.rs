//! Optional native-only status pane. No Android producer, settings buffer or XR owner.
use crate::{
    native_panel_interaction::ControlId,
    native_panel_model::{LogicalBounds, PanelHit, PanelModel, PanelPose, Ray},
    native_panel_text::{GlyphInstance, TextAtlas},
    native_panel_toolbar::ToolbarKey,
    native_panel_ui_geometry::Primitive,
};
pub(crate) const PANEL_ID: u64 = 3;
pub(crate) const CLOSE_CONTROL: u32 = 1;
pub(crate) const CLOSE_RECT: [f32; 4] = [0.65, -0.39, 0.065, 0.065];
#[derive(Clone, Copy, Debug)]
pub(crate) struct InfoPanel {
    pub(crate) visible: bool,
    pub(crate) pose: PanelPose,
}
impl Default for InfoPanel {
    fn default() -> Self {
        Self {
            visible: false,
            pose: PanelPose {
                center: [0.0; 3],
                right: [1.0, 0.0, 0.0],
                up: [0.0, 1.0, 0.0],
                width: 0.42,
                height: 0.28,
            },
        }
    }
}
impl InfoPanel {
    /// Placement is a one-shot world pose; later main-panel movement does not silently move this pane.
    pub(crate) fn open_beside(&mut self, main: PanelPose) -> bool {
        if !main.valid() {
            return false;
        }
        let pose = PanelPose {
            center: std::array::from_fn(|i| {
                main.center[i] + main.right[i] * (main.width * 0.5 + 0.26) + main.up[i] * 0.12
            }),
            right: main.right,
            up: main.up,
            width: 0.42,
            height: 0.28,
        };
        if !pose.valid() {
            return false;
        }
        self.pose = pose;
        self.visible = true;
        true
    }
    pub(crate) fn set_visible(&mut self, visible: bool) {
        self.visible = visible && self.pose.valid();
    }
    pub(crate) fn model(self) -> PanelModel {
        PanelModel {
            id: PANEL_ID,
            pose: self.pose,
            logical: LogicalBounds {
                min: [-0.75, -0.5],
                max: [0.75, 0.5],
            },
            visible: self.visible,
            enabled: self.visible,
        }
    }
    pub(crate) fn hit_control(self, p: [f32; 2]) -> Option<ControlId> {
        (self.visible
            && self.pose.valid()
            && self.model().logical.contains(p)
            && (p[0] - CLOSE_RECT[0]).abs() <= CLOSE_RECT[2]
            && (p[1] - CLOSE_RECT[1]).abs() <= CLOSE_RECT[3])
            .then_some(ControlId {
                panel: PANEL_ID,
                control: CLOSE_CONTROL,
            })
    }
    /// Same front-facing and five-metre admission as the original form; hit bounds remain half-open.
    pub(crate) fn hit_ray(self, ray: Ray) -> Option<PanelHit> {
        let r = self.pose.right;
        let u = self.pose.up;
        let normal = [
            r[1] * u[2] - r[2] * u[1],
            r[2] * u[0] - r[0] * u[2],
            r[0] * u[1] - r[1] * u[0],
        ];
        let length = ray.direction.iter().map(|v| v * v).sum::<f32>().sqrt();
        if !length.is_finite() || length < 0.00001 {
            return None;
        }
        let facing = normal
            .iter()
            .zip(ray.direction)
            .map(|(a, b)| a * b / length)
            .sum::<f32>();
        if !facing.is_finite() || facing >= -0.00001 {
            return None;
        }
        self.model().hit(ray).filter(|h| h.distance_m <= 5.0)
    }
    pub(crate) fn shapes(self) -> Vec<Primitive> {
        if !self.visible || !self.pose.valid() {
            return Vec::new();
        }
        let mut out = vec![
            Primitive::rounded_rect([0.0, 0.0, 0.75, 0.5], 0.035, [0.022, 0.031, 0.045, 0.98]),
            Primitive::rounded_rect(CLOSE_RECT, 0.018, [0.12, 0.18, 0.23, 1.0]),
            Primitive::line(
                [-0.64, -0.23],
                [0.64, -0.23],
                0.008,
                [0.12, 0.22, 0.29, 1.0],
            ),
        ];
        for (a, b) in [
            ([0.625, -0.415], [0.675, -0.365]),
            ([0.625, -0.365], [0.675, -0.415]),
        ] {
            out.push(Primitive::line(a, b, 0.012, [0.93, 0.96, 1.0, 1.0]));
        }
        out
    }
    /// Readouts use actual caller state each refresh and the shared immutable shaped atlas.
    pub(crate) fn glyphs(self, atlas: &TextAtlas, key: &ToolbarKey) -> Vec<GlyphInstance> {
        if !self.visible || !self.pose.valid() {
            return Vec::new();
        }
        let mut out = Vec::with_capacity(64);
        let mut draw = |index: usize, x: f32, y: f32, h: f32| {
            let Some(run) = atlas.runs.get(index) else {
                return 0.0;
            };
            let baseline = y - (run.ascent + run.descent) * h * 0.5;
            out.extend(atlas.layout_run(index, [x, baseline], h, [0.93, 0.96, 1.0, 1.0]));
            run.advance * h
        };
        draw(30, -0.64, -0.39, 0.105);
        draw(16, -0.64, -0.08, 0.10);
        let mut x = -0.15;
        for d in key.fps.to_string().bytes() {
            x += draw(17 + (d - b'0') as usize, x, -0.08, 0.10);
        }
        draw(3, -0.64, 0.12, 0.082);
        draw(
            match key.detail {
                crate::native_hybrid_panel::DetailMode::Auto => 6,
                crate::native_hybrid_panel::DetailMode::Base => 7,
                crate::native_hybrid_panel::DetailMode::Medium => 8,
                crate::native_hybrid_panel::DetailMode::High => 9,
            },
            0.05,
            0.12,
            0.082,
        );
        if let Some(index) = match (key.width, key.height) {
            (1024, 768) => Some(10),
            (1536, 1152) => Some(11),
            (2048, 1536) => Some(12),
            _ => None,
        } {
            draw(index, -0.64, 0.34, 0.088);
        }
        out
    }
}
/// Append only auxiliary instances; caller caches these complete batches by owner key and visibility.
pub(crate) fn append_batches(
    info: InfoPanel,
    atlas: Option<&TextAtlas>,
    key: &ToolbarKey,
    shapes: &mut Vec<Primitive>,
    glyphs: &mut Vec<GlyphInstance>,
) -> [u32; 4] {
    let main_shapes = shapes.len() as u32;
    let main_glyphs = glyphs.len() as u32;
    shapes.extend(info.shapes());
    if let Some(atlas) = atlas {
        glyphs.extend(info.glyphs(atlas, key));
    }
    [
        main_shapes,
        shapes.len() as u32 - main_shapes,
        main_glyphs,
        glyphs.len() as u32 - main_glyphs,
    ]
}
/// Whole-pane painter order for disjoint planes; coplanar ties paint lower panel ID last.
/// Intersecting transparent planes still require per-pixel depth, outside this bounded path.
pub(crate) fn info_draws_last(main: PanelPose, info: PanelPose, eye: [f32; 3]) -> bool {
    let distance = |p: PanelPose| {
        let r = p.right;
        let u = p.up;
        let n = [
            r[1] * u[2] - r[2] * u[1],
            r[2] * u[0] - r[0] * u[2],
            r[0] * u[1] - r[1] * u[0],
        ];
        p.center
            .iter()
            .zip(eye)
            .zip(n)
            .map(|((a, b), n)| (a - b) * n)
            .sum::<f32>()
            .abs()
    };
    distance(info) < distance(main)
}
/// Nearest full body wins before control hit testing; stable panel ID resolves coplanar ties.
pub(crate) fn nearest(a: Option<PanelHit>, b: Option<PanelHit>) -> Option<PanelHit> {
    match (a, b) {
        (Some(a), Some(b)) => Some(
            if a.distance_m < b.distance_m || a.distance_m == b.distance_m && a.panel < b.panel {
                a
            } else {
                b
            },
        ),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::native_panel_interaction::CaptureState;
    fn main() -> PanelPose {
        PanelPose {
            center: [1.0, 2.0, -1.4],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            width: 0.96,
            height: 0.72,
        }
    }
    #[test]
    fn placement_parallel_with_gap_and_independent_world_pose() {
        let mut p = InfoPanel::default();
        assert!(!p.visible);
        assert!(p.open_beside(main()));
        assert_eq!(p.pose.right, main().right);
        assert_eq!(p.pose.up, main().up);
        let gap = p.pose.center[0] - p.pose.width * 0.5 - (main().center[0] + main().width * 0.5);
        assert!((gap - 0.05).abs() < 0.00001);
        assert!((p.pose.center[1] - 2.12).abs() < 0.00001);
        let retained = p.pose;
        let mut moved = main();
        moved.center[0] = 99.0;
        assert_ne!(p.pose.center, moved.center);
        assert_eq!(p.pose, retained);
        let mut invalid = main();
        invalid.width = f32::NAN;
        assert!(!p.open_beside(invalid));
        assert_eq!(p.pose, retained);
    }
    #[test]
    fn close_identity_capture_and_hidden_cancel() {
        let mut p = InfoPanel::default();
        p.open_beside(main());
        let id = p.hit_control([0.65, -0.39]).unwrap();
        assert_eq!(id.panel, 3);
        assert!(p.hit_control([0.0, 0.0]).is_none());
        assert!(p.hit_control([f32::NAN, 0.0]).is_none());
        let mut capture = CaptureState::default();
        assert_eq!(
            capture.update(true, true, Some(id), false).pressed,
            Some(id)
        );
        p.set_visible(false);
        assert!(p.hit_control([0.65, -0.39]).is_none());
        assert!(p.shapes().is_empty());
        assert_eq!(
            capture.update(p.visible, true, None, false).cancelled,
            Some(id)
        );
        p.set_visible(true);
        assert!(capture
            .update(true, true, Some(id), false)
            .pressed
            .is_none());
    }
    #[test]
    fn front_ray_maps_close_and_hidden_or_back_ray_cannot_hit() {
        let mut p = InfoPanel::default();
        p.open_beside(main());
        let target = [
            p.pose.center[0] + 0.65 * p.pose.height,
            p.pose.center[1] + 0.39 * p.pose.height,
            p.pose.center[2],
        ];
        let ray = Ray {
            origin: [target[0], target[1], 0.0],
            direction: [0.0, 0.0, -1.0],
        };
        let hit = p.hit_ray(ray).unwrap();
        assert!((hit.logical[0] - 0.65).abs() < 0.00001);
        assert!(p.hit_control(hit.logical).is_some());
        assert!(p
            .hit_ray(Ray {
                origin: [target[0], target[1], -2.0],
                direction: [0.0, 0.0, 1.0]
            })
            .is_none());
        p.set_visible(false);
        assert!(p.hit_ray(ray).is_none());
    }
    #[test]
    fn readouts_use_supplied_actual_values_and_missing_labels_fail_closed() {
        use crate::native_hybrid_panel::DetailMode;
        use crate::native_panel_text::{TextGlyph, TextRun};
        let atlas = TextAtlas {
            width: 1,
            height: 1,
            pixels: vec![0],
            runs: (0..31)
                .map(|i| TextRun {
                    advance: 0.6,
                    ascent: -0.8,
                    descent: 0.2,
                    glyphs: vec![TextGlyph {
                        bounds: [0.0, -0.8, 0.5, 0.2],
                        uv: [i as f32 / 31.0, 0.0, 1.0, 1.0],
                    }],
                })
                .collect(),
        };
        let key = ToolbarKey {
            hovered: None,
            pressed: None,
            forms: true,
            width: 1536,
            height: 1152,
            detail: DetailMode::Auto,
            physical_width: 0.96,
            wrist: false,
            anchor_valid: true,
            wrist_available: false,
            extra_hover: None,
            extra_pressed: None,
            fps: 46,
        };
        let mut p = InfoPanel::default();
        assert!(p.glyphs(&atlas, &key).is_empty());
        p.open_beside(main());
        let glyphs = p.glyphs(&atlas, &key);
        let labels: Vec<_> = glyphs
            .iter()
            .map(|g| (g.uv[0] * 31.0).round() as usize)
            .collect();
        assert_eq!(labels, [30, 16, 21, 23, 3, 6, 11]); // STATUS FPS 4 6 DETAIL AUTO effective1536x1152
        let seed = Primitive::circle([0.0, 0.0], 0.01, [1.0; 4]);
        let mut shapes = vec![seed];
        let mut text = vec![];
        let r = append_batches(p, Some(&atlas), &key, &mut shapes, &mut text);
        assert_eq!(r, [1, 5, 0, 7]);
        assert_eq!(shapes.len(), 6);
        assert_eq!(text.len(), 7);
        p.set_visible(false);
        shapes.truncate(1);
        text.clear();
        assert_eq!(
            append_batches(p, Some(&atlas), &key, &mut shapes, &mut text),
            [1, 0, 0, 0]
        );
        p.set_visible(true);
        assert_eq!(
            append_batches(p, Some(&atlas), &key, &mut shapes, &mut text),
            [1, 5, 0, 7]
        );
        let empty = TextAtlas {
            width: 1,
            height: 1,
            pixels: vec![0],
            runs: vec![],
        };
        assert!(p.glyphs(&empty, &key).is_empty());
    }

    #[test]
    fn nearest_body_occludes_control_behind_with_stable_ties() {
        let main = PanelHit {
            panel: 1,
            logical: [512.0, 384.0],
            distance_m: 1.4,
        };
        let info = PanelHit {
            panel: 3,
            logical: [0.0, 0.0],
            distance_m: 1.0,
        };
        assert_eq!(nearest(Some(main), Some(info)), Some(info));
        assert_eq!(nearest(Some(info), Some(main)), Some(info));
        let tie = PanelHit {
            distance_m: 1.4,
            ..info
        };
        assert_eq!(nearest(Some(tie), Some(main)), Some(main));
        let mut pane = InfoPanel::default();
        pane.open_beside(super::tests::main());
        assert!(pane.hit_control(info.logical).is_none()); // body still wins, independent of close hit
    }
    #[test]
    fn whole_pane_draw_order_switches_by_eye_and_ties_preserve_input_priority() {
        let a = main();
        let mut b = a;
        b.center[2] -= 0.5;
        assert!(!info_draws_last(a, b, [1.0, 2.0, 0.0]));
        assert!(info_draws_last(a, b, [1.0, 2.0, -3.0]));
        assert!(!info_draws_last(a, a, [0.0; 3]));
        let mut lateral = a;
        lateral.center = [9.0, 2.0, -1.0];
        assert!(info_draws_last(a, lateral, [1.0, 2.0, 0.0])); // nearer parallel plane despite farther centre
        lateral.center[2] = a.center[2];
        assert!(!info_draws_last(a, lateral, [1.0, 2.0, 0.0]));
    }
}
