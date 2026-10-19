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
/// Quantized projected footprint policy with 20% downshift hysteresis and 120-frame dwell.
/// Inputs are projected panel width/height in eye-buffer pixels, not distance alone.
#[derive(Clone, Copy, Debug)]
pub(crate) struct AdaptiveResolution {
    selected: RasterExtent,
    last_change: u64,
}
impl AdaptiveResolution {
    pub(crate) fn new(selected: RasterExtent) -> Self {
        Self {
            selected: if selected.supported() {
                selected
            } else {
                RasterExtent::BASE
            },
            last_change: 0,
        }
    }
    pub(crate) fn selected(self) -> RasterExtent {
        self.selected
    }
    pub(crate) fn recommend(&mut self, projected: [f32; 2], frame: u64) -> RasterExtent {
        if projected.iter().any(|v| !v.is_finite() || *v <= 0.0)
            || frame.saturating_sub(self.last_change) < 120
        {
            return self.selected;
        }
        let demand =
            (projected[0] / LOGICAL_WIDTH as f32).max(projected[1] / LOGICAL_HEIGHT as f32);
        let next = if demand > self.selected.scale() {
            if demand > 1.5 {
                RasterExtent::HIGH
            } else {
                RasterExtent::MEDIUM
            }
        } else if demand < self.selected.scale() * 0.8 {
            if demand <= 0.8 {
                RasterExtent::BASE
            } else if demand <= 1.2 {
                RasterExtent::MEDIUM
            } else {
                self.selected
            }
        } else {
            self.selected
        };
        if next != self.selected {
            self.selected = next;
            self.last_change = frame;
        }
        self.selected
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_and_invalid_input() {
        let mut p = AdaptiveResolution::new(RasterExtent::BASE);
        assert_eq!(p.recommend([100000.0, 100000.0], 120), RasterExtent::HIGH);
        assert_eq!(p.recommend([f32::NAN, 1.0], 300), RasterExtent::HIGH);
        assert_eq!(p.recommend([0.0, 1.0], 300), RasterExtent::HIGH);
    }
    #[test]
    fn hysteresis_and_dwell() {
        let mut p = AdaptiveResolution::new(RasterExtent::BASE);
        assert_eq!(p.recommend([1300.0, 900.0], 119), RasterExtent::BASE);
        assert_eq!(p.recommend([1300.0, 900.0], 120), RasterExtent::MEDIUM);
        assert_eq!(p.recommend([900.0, 675.0], 121), RasterExtent::MEDIUM);
        assert_eq!(p.recommend([1250.0, 900.0], 240), RasterExtent::MEDIUM);
        assert_eq!(p.recommend([800.0, 600.0], 241), RasterExtent::BASE);
    }
    #[test]
    fn either_eye_footprint_axis_can_raise_density_without_jitter() {
        let mut policy = AdaptiveResolution::new(RasterExtent::BASE);
        assert_eq!(policy.recommend([800.0, 1200.0], 120), RasterExtent::HIGH);
        for frame in [240, 360, 480] {
            assert_eq!(
                policy.recommend([1500.0, 1125.0], frame),
                RasterExtent::HIGH
            );
        }
        assert_eq!(policy.recommend([1100.0, 825.0], 600), RasterExtent::MEDIUM);
        assert_eq!(policy.recommend([1100.0, 825.0], 720), RasterExtent::MEDIUM);
        assert_eq!(policy.recommend([800.0, 600.0], 840), RasterExtent::BASE);
    }
    #[test]
    fn invalid_extent_falls_back_and_rewound_clock_cannot_resize() {
        let mut policy = AdaptiveResolution::new(RasterExtent {
            width: 9000,
            height: 9000,
        });
        assert_eq!(policy.selected(), RasterExtent::BASE);
        assert_eq!(policy.recommend([1800.0, 1350.0], 120), RasterExtent::HIGH);
        assert_eq!(policy.recommend([100.0, 75.0], 1), RasterExtent::HIGH);
    }
    #[test]
    fn raster_density_preserves_physical_position_and_size() {
        for extent in [RasterExtent::BASE, RasterExtent::MEDIUM, RasterExtent::HIGH] {
            let pixel = extent.logical_to_raster([240.0, 180.0]);
            assert_eq!(pixel[0] / extent.width as f32, 240.0 / 1024.0);
            assert_eq!(pixel[1] / extent.height as f32, 180.0 / 768.0);
            assert_eq!(
                extent.logical_to_raster([64.0, 48.0])[0] / extent.width as f32,
                64.0 / 1024.0
            );
        }
    }
}
