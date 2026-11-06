//! Independently authored vector geometry for the native toolbar. No font/texture owner.
const GLYPH_ADVANCE: f32 = 1.0;
const GLYPH_CORE_WIDTH: f32 = 4.0 / 6.0;
const GLYPH_STROKE_WIDTH: f32 = 0.075;
fn text_width(chars: usize, height: f32) -> f32 {
    if chars == 0 {
        0.0
    } else {
        ((chars - 1) as f32 * GLYPH_ADVANCE + GLYPH_CORE_WIDTH) * height
    }
}
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct Primitive {
    /// Center and half extent in panel-height units; panel center is the origin, +Y down.
    pub(crate) rect: [f32; 4],
    pub(crate) color: [f32; 4],
    /// Corner radius, rotation radians, reserved, reserved.
    pub(crate) shape: [f32; 4],
}
impl Primitive {
    pub(crate) fn rounded_rect(rect: [f32; 4], radius: f32, color: [f32; 4]) -> Self {
        Self {
            rect,
            color,
            shape: [radius, 0.0, 0.0, 0.0],
        }
    }
    pub(crate) fn circle(center: [f32; 2], radius: f32, color: [f32; 4]) -> Self {
        Self::rounded_rect([center[0], center[1], radius, radius], radius, color)
    }
    pub(crate) fn line(a: [f32; 2], b: [f32; 2], width: f32, color: [f32; 4]) -> Self {
        let d = [b[0] - a[0], b[1] - a[1]];
        let radius = width * 0.5;
        let mut p = Self::rounded_rect(
            [
                (a[0] + b[0]) * 0.5,
                (a[1] + b[1]) * 0.5,
                d[0].hypot(d[1]) * 0.5 + radius,
                radius,
            ],
            radius,
            color,
        );
        p.shape[1] = d[1].atan2(d[0]);
        p
    }
}
/// Bounds in panel-height units; actions stay with the caller. FORMS/CENTER/SIZE/DETAIL.
pub(crate) const BUTTONS: [[f32; 4]; 4] = [
    [-0.45, 0.59, 0.135, 0.035],
    [-0.15, 0.59, 0.135, 0.035],
    [0.15, 0.59, 0.135, 0.035],
    [0.45, 0.59, 0.135, 0.035],
];
pub(crate) fn hit_button(point: [f32; 2]) -> Option<usize> {
    BUTTONS
        .iter()
        .position(|r| (point[0] - r[0]).abs() <= r[2] && (point[1] - r[1]).abs() <= r[3])
}
pub(crate) struct ToolbarState<'a> {
    pub(crate) hovered: Option<usize>,
    pub(crate) pressed: Option<usize>,
    /// Actual application state supplied by its owner, never inferred from drawing.
    pub(crate) status: &'a str,
}
pub(crate) fn toolbar_geometry(state: ToolbarState<'_>) -> Vec<Primitive> {
    toolbar_geometry_with_text(state, true)
}
pub(crate) fn toolbar_shapes(state: ToolbarState<'_>) -> Vec<Primitive> {
    toolbar_geometry_with_text(state, false)
}
fn toolbar_geometry_with_text(state: ToolbarState<'_>, include_text: bool) -> Vec<Primitive> {
    let mut out = Vec::with_capacity(256);
    out.push(Primitive::rounded_rect(
        [0.0, 0.62, 0.61, 0.085],
        0.018,
        [0.022, 0.031, 0.045, 0.97],
    ));
    for (i, label) in ["FORMS", "CENTER", "SIZE", "DETAIL"].iter().enumerate() {
        let c = if state.pressed == Some(i) {
            [0.08, 0.40, 0.57, 1.0]
        } else if state.hovered == Some(i) {
            [0.16, 0.25, 0.34, 1.0]
        } else {
            [0.065, 0.09, 0.12, 1.0]
        };
        out.push(Primitive::rounded_rect(BUTTONS[i], 0.012, c));
        let size = 0.032;
        if include_text {
            text(
                &mut out,
                label,
                [
                    BUTTONS[i][0] - text_width(label.len(), size) * 0.5,
                    BUTTONS[i][1] - size * 0.5,
                ],
                size,
                [0.93, 0.96, 1.0, 1.0],
            );
        }
    }
    if include_text {
        text(
            &mut out,
            state.status,
            [-0.57, 0.665],
            0.024,
            [0.82, 0.88, 0.94, 1.0],
        );
    }
    out
}
/// Small single-line stroke alphabet, authored here on a 4x6 grid. Unknown glyphs draw '?'.
/// Clamps status length to keep bounded work; labels retain their full supported spelling.
pub(crate) fn text(
    out: &mut Vec<Primitive>,
    value: &str,
    origin: [f32; 2],
    height: f32,
    color: [f32; 4],
) {
    for (index, c) in value.chars().take(48).enumerate() {
        let strokes: &[[u8; 4]] = match c.to_ascii_uppercase() {
            'A' => &[[0, 6, 2, 0], [2, 0, 4, 6], [1, 4, 3, 4]],
            'B' => &[
                [0, 0, 0, 6],
                [0, 0, 3, 0],
                [3, 0, 4, 1],
                [4, 1, 3, 3],
                [0, 3, 3, 3],
                [3, 3, 4, 4],
                [4, 4, 4, 5],
                [4, 5, 3, 6],
                [0, 6, 3, 6],
            ],
            'C' => &[[4, 0, 0, 0], [0, 0, 0, 6], [0, 6, 4, 6]],
            'D' => &[
                [0, 0, 0, 6],
                [0, 0, 3, 0],
                [3, 0, 4, 1],
                [4, 1, 4, 5],
                [4, 5, 3, 6],
                [3, 6, 0, 6],
            ],
            'E' => &[[4, 0, 0, 0], [0, 0, 0, 6], [0, 3, 3, 3], [0, 6, 4, 6]],
            'F' => &[[4, 0, 0, 0], [0, 0, 0, 6], [0, 3, 3, 3]],
            'G' => &[
                [4, 0, 0, 0],
                [0, 0, 0, 6],
                [0, 6, 4, 6],
                [4, 6, 4, 3],
                [4, 3, 2, 3],
            ],
            'H' => &[[0, 0, 0, 6], [4, 0, 4, 6], [0, 3, 4, 3]],
            'I' => &[[0, 0, 4, 0], [2, 0, 2, 6], [0, 6, 4, 6]],
            'J' => &[[0, 0, 4, 0], [4, 0, 4, 6], [4, 6, 0, 6], [0, 6, 0, 4]],
            'K' => &[[0, 0, 0, 6], [4, 0, 0, 3], [0, 3, 4, 6]],
            'L' => &[[0, 0, 0, 6], [0, 6, 4, 6]],
            'M' => &[[0, 6, 0, 0], [0, 0, 2, 3], [2, 3, 4, 0], [4, 0, 4, 6]],
            'N' => &[[0, 6, 0, 0], [0, 0, 4, 6], [4, 6, 4, 0]],
            'O' | '0' => &[[0, 0, 4, 0], [4, 0, 4, 6], [4, 6, 0, 6], [0, 6, 0, 0]],
            'P' => &[[0, 6, 0, 0], [0, 0, 4, 0], [4, 0, 4, 3], [4, 3, 0, 3]],
            'Q' => &[
                [0, 0, 4, 0],
                [4, 0, 4, 6],
                [4, 6, 0, 6],
                [0, 6, 0, 0],
                [2, 4, 4, 6],
            ],
            'R' => &[
                [0, 6, 0, 0],
                [0, 0, 4, 0],
                [4, 0, 4, 3],
                [4, 3, 0, 3],
                [2, 3, 4, 6],
            ],
            'S' | '5' => &[
                [4, 0, 0, 0],
                [0, 0, 0, 3],
                [0, 3, 4, 3],
                [4, 3, 4, 6],
                [4, 6, 0, 6],
            ],
            'T' => &[[0, 0, 4, 0], [2, 0, 2, 6]],
            'U' => &[[0, 0, 0, 6], [0, 6, 4, 6], [4, 6, 4, 0]],
            'V' => &[[0, 0, 2, 6], [2, 6, 4, 0]],
            'W' => &[[0, 0, 0, 6], [0, 6, 2, 3], [2, 3, 4, 6], [4, 6, 4, 0]],
            'X' => &[[0, 0, 4, 6], [4, 0, 0, 6]],
            'Y' => &[[0, 0, 2, 3], [4, 0, 2, 3], [2, 3, 2, 6]],
            'Z' | '2' => &[[0, 0, 4, 0], [4, 0, 0, 6], [0, 6, 4, 6]],
            '1' => &[[0, 2, 2, 0], [2, 0, 2, 6], [0, 6, 4, 6]],
            '3' => &[[0, 0, 4, 0], [4, 0, 4, 6], [4, 3, 0, 3], [4, 6, 0, 6]],
            '4' => &[[0, 0, 0, 3], [0, 3, 4, 3], [4, 0, 4, 6]],
            '6' => &[
                [4, 0, 0, 0],
                [0, 0, 0, 6],
                [0, 6, 4, 6],
                [4, 6, 4, 3],
                [4, 3, 0, 3],
            ],
            '7' => &[[0, 0, 4, 0], [4, 0, 1, 6]],
            '8' => &[
                [0, 0, 4, 0],
                [4, 0, 4, 6],
                [4, 6, 0, 6],
                [0, 6, 0, 0],
                [0, 3, 4, 3],
            ],
            '9' => &[
                [0, 0, 4, 0],
                [4, 0, 4, 6],
                [4, 6, 0, 6],
                [0, 0, 0, 3],
                [0, 3, 4, 3],
            ],
            '-' => &[[0, 3, 4, 3]],
            '.' => &[[2, 6, 2, 6]],
            ':' => &[[2, 2, 2, 2], [2, 5, 2, 5]],
            '/' => &[[0, 6, 4, 0]],
            ' ' => &[],
            _ => &[
                [0, 0, 4, 0],
                [4, 0, 4, 2],
                [4, 2, 2, 3],
                [2, 3, 2, 4],
                [2, 6, 2, 6],
            ],
        };
        for s in strokes {
            let point = |x: u8, y: u8| {
                [
                    origin[0] + index as f32 * height * GLYPH_ADVANCE + x as f32 * height / 6.0,
                    origin[1] + y as f32 * height / 6.0,
                ]
            };
            out.push(Primitive::line(
                point(s[0], s[1]),
                point(s[2], s[3]),
                height * GLYPH_STROKE_WIDTH,
                color,
            ));
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn toolbar_is_below_form_and_within_instance_capacity() {
        assert_eq!(std::mem::size_of::<Primitive>(), 48);
        let primitives = toolbar_geometry(ToolbarState {
            hovered: Some(0),
            pressed: Some(2),
            status: "SIZE AUTO 2X",
        });
        assert!(primitives.len() < 1024);
        assert!(primitives.iter().all(|p| p.rect[1] - p.rect[3] > 0.5));
        let circle = Primitive::circle([0.0, 0.0], 0.1, [1.0; 4]);
        assert_eq!(circle.rect[2], circle.shape[0]);
    }
    #[test]
    fn adjacent_glyph_capsule_cores_leave_visible_spacing() {
        let height = 0.032;
        let mut single = Vec::new();
        text(&mut single, "M", [0.0, 0.0], height, [1.0; 4]);
        let mut pair = Vec::new();
        text(&mut pair, "MM", [0.0, 0.0], height, [1.0; 4]);
        // Exact rotated capsule x-extent, including rounded stroke end caps.
        let bounds = |p: &Primitive| {
            let extent = (p.rect[2] - p.shape[0]) * p.shape[1].cos().abs() + p.shape[0];
            [p.rect[0] - extent, p.rect[0] + extent]
        };
        let right = pair[..single.len()]
            .iter()
            .map(|p| bounds(p)[1])
            .fold(f32::NEG_INFINITY, f32::max);
        let left = pair[single.len()..]
            .iter()
            .map(|p| bounds(p)[0])
            .fold(f32::INFINITY, f32::min);
        assert!(
            left - right > height * 0.2,
            "stroke cores must have real interglyph space"
        );
        for label in ["FORMS", "CENTER", "SIZE", "DETAIL"] {
            assert!(
                text_width(label.len(), height) + height * GLYPH_STROKE_WIDTH < BUTTONS[0][2] * 2.0
            );
        }
    }
    #[test]
    fn hit_bounds_match_every_button_and_gaps() {
        for (i, r) in BUTTONS.iter().enumerate() {
            assert_eq!(hit_button([r[0], r[1]]), Some(i));
        }
        assert_eq!(hit_button([-0.30, 0.59]), None);
        assert_eq!(hit_button([f32::NAN, 0.0]), None);
    }
    #[test]
    fn glyphs_have_finite_nonzero_capsules_and_bounded_extent() {
        for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-.:/?".chars() {
            let mut p = Vec::new();
            text(&mut p, &c.to_string(), [0.0, 0.0], 1.0, [1.0; 4]);
            assert!(!p.is_empty());
            for p in p {
                assert!(p.rect.iter().chain(p.shape.iter()).all(|v| v.is_finite()));
                assert!(p.rect[2] > 0.0 && p.rect[3] > 0.0);
                assert!((-0.1..0.8).contains(&p.rect[0]));
                assert!((-0.1..1.1).contains(&p.rect[1]));
            }
        }
    }
}
