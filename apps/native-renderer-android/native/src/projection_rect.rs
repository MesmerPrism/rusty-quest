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
        self.x.is_finite()
            && self.y.is_finite()
            && self.width.is_finite()
            && self.height.is_finite()
            && self.x >= 0.0
            && self.y >= 0.0
            && self.width > 0.0
            && self.height > 0.0
            && self.x + self.width <= 1.0
            && self.y + self.height <= 1.0
    }
}

#[cfg(test)]
mod tests {
    use super::TargetRect;

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
