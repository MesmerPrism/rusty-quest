//! Shared normalized screen-UV rectangle utilities for projection metadata.

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct TargetRect {
    pub(crate) x: f32,
    pub(crate) y: f32,
    pub(crate) width: f32,
    pub(crate) height: f32,
}

impl Default for TargetRect {
    fn default() -> Self {
        Self::UNIT
    }
}

impl TargetRect {
    pub(crate) const UNIT: Self = Self {
        x: 0.0,
        y: 0.0,
        width: 1.0,
        height: 1.0,
    };

    pub(crate) const fn new(x: f32, y: f32, width: f32, height: f32) -> Self {
        Self {
            x,
            y,
            width,
            height,
        }
    }

    /// Full-eye UV affine map from the base camera footprint to its current footprint.
    /// The result encodes offset and scale, so its offsets may be negative.
    pub(crate) fn world_eye_projection_rect(self, effective: Self, custom_camera: bool) -> Self {
        if !custom_camera || !self.is_valid() || !effective.is_finite_positive() {
            return Self::UNIT;
        }
        let sx = effective.width / self.width;
        let sy = effective.height / self.height;
        Self::new(effective.x - self.x * sx, effective.y - self.y * sy, sx, sy)
    }

    pub(crate) fn parse(text: &str) -> Option<Self> {
        let parts = text
            .split(|character| matches!(character, ',' | ';' | ' ' | '\t'))
            .filter(|part| !part.trim().is_empty())
            .filter_map(|part| part.trim().parse::<f32>().ok())
            .collect::<Vec<_>>();
        if parts.len() != 4 {
            return None;
        }
        let rect = Self::new(parts[0], parts[1], parts[2], parts[3]);
        rect.is_valid().then_some(rect)
    }

    pub(crate) fn as_xywh_token(self) -> String {
        format!(
            "{:.6},{:.6},{:.6},{:.6}",
            self.x, self.y, self.width, self.height
        )
    }

    pub(crate) fn is_valid(self) -> bool {
        self.is_finite_positive()
            && self.x >= 0.0
            && self.y >= 0.0
            && self.x + self.width <= 1.0
            && self.y + self.height <= 1.0
    }

    /// Runtime projection footprints may extend past the eye viewport. The
    /// metadata parser still requires `is_valid` bounds within the source eye.
    pub(crate) fn is_finite_positive(self) -> bool {
        self.x.is_finite()
            && self.y.is_finite()
            && self.width.is_finite()
            && self.height.is_finite()
            && self.width > 0.0
            && self.height > 0.0
    }
}

#[cfg(test)]
mod tests {
    use super::TargetRect;

    #[test]
    fn world_eye_map_preserves_camera_content_coordinates_for_each_eye() {
        for base in [
            TargetRect::new(0.12, 0.18, 0.72, 0.64),
            TargetRect::new(0.16, 0.20, 0.68, 0.60),
        ] {
            for effective in [
                base,
                TargetRect::new(0.30, 0.35, 0.36, 0.30),
                TargetRect::UNIT,
            ] {
                let map = base.world_eye_projection_rect(effective, true);
                for content in [[0.0, 0.0], [0.5, 0.5], [1.0, 1.0], [0.23, 0.81]] {
                    let actual = [
                        map.x + (base.x + content[0] * base.width) * map.width,
                        map.y + (base.y + content[1] * base.height) * map.height,
                    ];
                    let expected = [
                        effective.x + content[0] * effective.width,
                        effective.y + content[1] * effective.height,
                    ];
                    assert!((actual[0] - expected[0]).abs() < 0.000_001);
                    assert!((actual[1] - expected[1]).abs() < 0.000_001);
                }
            }
        }
    }

    #[test]
    fn world_eye_map_is_unit_without_custom_camera_or_valid_metadata() {
        let base = TargetRect::new(0.1, 0.2, 0.7, 0.6);
        assert_eq!(
            base.world_eye_projection_rect(TargetRect::UNIT, false),
            TargetRect::UNIT
        );
        assert_eq!(
            TargetRect::new(0.0, 0.0, 0.0, 1.0).world_eye_projection_rect(base, true),
            TargetRect::UNIT
        );
        assert_eq!(
            base.world_eye_projection_rect(TargetRect::new(f32::NAN, 0.0, 1.0, 1.0), true),
            TargetRect::UNIT
        );
    }

    #[test]
    fn parses_target_rect_tokens() {
        let rect = TargetRect::parse("0.171875;0.21875;0.75;0.65625").expect("rect parses");
        assert!((rect.x - 0.171875).abs() < 0.000_001);
        assert!((rect.y - 0.21875).abs() < 0.000_001);
        assert!((rect.width - 0.75).abs() < 0.000_001);
        assert!((rect.height - 0.65625).abs() < 0.000_001);
    }

    #[test]
    fn rejects_out_of_bounds_rects() {
        assert!(TargetRect::parse("0.5;0.5;0.75;0.75").is_none());
        assert!(TargetRect::parse("0.1;0.1;0.0;0.3").is_none());
    }
}
