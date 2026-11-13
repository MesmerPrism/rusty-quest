//! Pure bounded presentation math. No source FOV is inferred from raster size.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Rect(pub [f32; 4]);
/// Closed consumer layouts. Raster dimensions never imply capture FOV or pose.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum InputLayout {
    MonoLeft,
    StereoBoth,
}
impl InputLayout {
    pub(crate) fn from_name(name: &str) -> Option<Self> {
        match name {
            "mono-left" => Some(Self::MonoLeft),
            "stereo-both" => Some(Self::StereoBoth),
            _ => None,
        }
    }
    pub(crate) fn name(self) -> &'static str {
        match self {
            Self::MonoLeft => "mono-left",
            Self::StereoBoth => "stereo-both",
        }
    }
    pub(crate) fn consumer_extent(self, height: i32) -> Option<[u32; 2]> {
        match (self, height) {
            (Self::MonoLeft, 672 | 1280) => Some([1280, height as u32]),
            (Self::StereoBoth, 1280) => Some([2560, 1280]),
            _ => None,
        }
    }
    pub(crate) fn eye_extent(self, height: i32) -> Option<[u32; 2]> {
        let [width, height] = self.consumer_extent(height)?;
        Some([
            if self == Self::StereoBoth {
                width / 2
            } else {
                width
            },
            height,
        ])
    }
    pub(crate) fn capture_eye_selection(self) -> u8 {
        match self {
            Self::MonoLeft => 0,
            Self::StereoBoth => 2,
        }
    }
    /// Capture half order is explicit; this does not calibrate physical eye labels.
    pub(crate) fn source_rect(self, output_eye: usize) -> Option<Rect> {
        if output_eye >= 2 {
            return None;
        }
        Some(Rect(match self {
            Self::MonoLeft => [0., 0., 1., 1.],
            Self::StereoBoth => [output_eye as f32 * 0.5, 0., 0.5, 1.],
        }))
    }
    /// Safe filter-center bounds [minX,minY,maxX,maxY], independent per eye.
    pub(crate) fn sample_bounds(self, output_eye: usize, height: i32) -> Option<[f32; 4]> {
        let [width, height] = self.consumer_extent(height)?;
        let Rect([x, y, w, h]) = self.source_rect(output_eye)?;
        let hx = 0.5 / width as f32;
        let hy = 0.5 / height as f32;
        Some([x + hx, y + hy, x + w - hx, y + h - hy])
    }
    /// Map already-projected eye-local UVs. Uncovered rays are rejected first.
    pub(crate) fn sample_uv(
        self,
        output_eye: usize,
        height: i32,
        uv: [f32; 2],
        flip_y: bool,
    ) -> Option<[f32; 2]> {
        if uv.iter().any(|v| !v.is_finite() || !(0. ..=1.).contains(v)) {
            return None;
        }
        let Rect([x, y, w, h]) = self.source_rect(output_eye)?;
        let bounds = self.sample_bounds(output_eye, height)?;
        let v = if flip_y { 1. - uv[1] } else { uv[1] };
        Some([
            (x + uv[0] * w).clamp(bounds[0], bounds[2]),
            (y + v * h).clamp(bounds[1], bounds[3]),
        ])
    }
}
pub(crate) fn valid_input_height(height: i32) -> bool {
    matches!(height, 672 | 1280)
}
pub(crate) fn contain(source: [u32; 2], target: [u32; 2]) -> Option<Rect> {
    if source.contains(&0) || target.contains(&0) {
        return None;
    }
    let source_aspect = source[0] as f32 / source[1] as f32;
    let target_aspect = target[0] as f32 / target[1] as f32;
    let (w, h) = if source_aspect >= target_aspect {
        (1., target_aspect / source_aspect)
    } else {
        (source_aspect / target_aspect, 1.)
    };
    Some(Rect([(1. - w) * 0.5, (1. - h) * 0.5, w, h]))
}
pub(crate) fn valid_tangents(t: [f32; 4]) -> bool {
    t.iter().all(|v| v.is_finite() && v.abs() <= 10.)
        && t[0] < 0.
        && t[1] > 0.
        && t[2] < 0.
        && t[3] > 0.
}
pub(crate) fn eye_ray(uv: [f32; 2], t: [f32; 4]) -> [f32; 3] {
    [
        t[0] + uv[0] * (t[1] - t[0]),
        t[3] + uv[1] * (t[2] - t[3]),
        -1.,
    ]
}
pub(crate) fn rectilinear_uv(ray: [f32; 3], t: [f32; 4]) -> Option<[f32; 2]> {
    if !valid_tangents(t) || ray.iter().any(|v| !v.is_finite()) || ray[2] >= -0.00001 {
        return None;
    }
    let uv = [
        (ray[0] / -ray[2] - t[0]) / (t[1] - t[0]),
        (t[3] - ray[1] / -ray[2]) / (t[3] - t[2]),
    ];
    uv.iter().all(|v| (0. ..=1.).contains(v)).then_some(uv)
}
pub(crate) fn quat_inverse(q: [f32; 4]) -> [f32; 4] {
    [-q[0], -q[1], -q[2], q[3]]
}
pub(crate) fn quat_mul(a: [f32; 4], b: [f32; 4]) -> [f32; 4] {
    [
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    ]
}
pub(crate) fn rotate(q: [f32; 4], p: [f32; 3]) -> [f32; 3] {
    let v = quat_mul(quat_mul(q, [p[0], p[1], p[2], 0.]), quat_inverse(q));
    [v[0], v[1], v[2]]
}
pub(crate) fn cylinder_uv(
    origin: [f32; 3],
    ray: [f32; 3],
    radius: f32,
    half_angle: f32,
    height: f32,
) -> Option<[f32; 2]> {
    if origin.iter().chain(ray.iter()).any(|v| !v.is_finite())
        || !radius.is_finite()
        || !half_angle.is_finite()
        || !height.is_finite()
        || radius <= 0.
        || half_angle <= 0.
        || half_angle > std::f32::consts::PI
        || height <= 0.
    {
        return None;
    }
    if origin[0] * origin[0] + origin[2] * origin[2] >= radius * radius {
        return None;
    }
    let a = ray[0] * ray[0] + ray[2] * ray[2];
    if a < 0.000001 {
        return None;
    }
    let b = 2. * (origin[0] * ray[0] + origin[2] * ray[2]);
    let c = origin[0] * origin[0] + origin[2] * origin[2] - radius * radius;
    let discriminant = b * b - 4. * a * c;
    if discriminant < 0. {
        return None;
    }
    let distance = (-b + discriminant.sqrt()) / (2. * a);
    if distance <= 0. {
        return None;
    }
    let hit = [
        origin[0] + distance * ray[0],
        origin[1] + distance * ray[1],
        origin[2] + distance * ray[2],
    ];
    let uv = [
        hit[0].atan2(-hit[2]) / (2. * half_angle) + 0.5,
        0.5 - hit[1] / height,
    ];
    uv.iter().all(|v| (0. ..=1.).contains(v)).then_some(uv)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn close(a: f32, b: f32) {
        assert!((a - b).abs() < 0.00001, "{a} != {b}");
    }
    #[test]
    fn closed_input_layouts_keep_mono_and_reject_wide_non_square_stereo() {
        assert_eq!(
            InputLayout::MonoLeft.consumer_extent(672),
            Some([1280, 672])
        );
        assert_eq!(
            InputLayout::MonoLeft.consumer_extent(1280),
            Some([1280, 1280])
        );
        assert_eq!(
            InputLayout::StereoBoth.consumer_extent(1280),
            Some([2560, 1280])
        );
        assert_eq!(InputLayout::StereoBoth.eye_extent(1280), Some([1280, 1280]));
        assert_eq!(InputLayout::StereoBoth.consumer_extent(672), None);
        assert_eq!(InputLayout::StereoBoth.consumer_extent(-1), None);
        assert_eq!(InputLayout::from_name("both"), None);
        assert_eq!(InputLayout::StereoBoth.source_rect(2), None);
    }
    #[test]
    fn all_source_centers_round_trip_to_their_own_packed_eye() {
        for eye in 0..2 {
            // Mapping is separable: these axes cover the full 1280x1280 product.
            for index in 0..1280 {
                let center = (index as f32 + 0.5) / 1280.;
                let uv = InputLayout::StereoBoth
                    .sample_uv(eye, 1280, [center, center], false)
                    .unwrap();
                assert!((uv[0] * 2560. - 0.5 - (eye * 1280 + index) as f32).abs() < 0.0003);
                assert!((uv[1] * 1280. - 0.5 - index as f32).abs() < 0.0002);
            }
        }
        for height in [672, 1280] {
            assert_eq!(
                InputLayout::MonoLeft.sample_uv(0, height, [0.3, 0.7], false),
                InputLayout::MonoLeft.sample_uv(1, height, [0.3, 0.7], false)
            );
        }
    }
    #[test]
    fn angular_projection_is_eye_local_before_half_mapping_and_filter_clamp() {
        // Deliberately asymmetric and unequal input/output frustums: no 80-degree default.
        let source = [-1.2, 0.8, -0.7, 1.1];
        let output = [-0.9, 0.6, -0.6, 0.9];
        let ray = eye_ray([0.2, 0.8], output);
        let local = rectilinear_uv(ray, source).unwrap();
        let left = InputLayout::StereoBoth
            .sample_uv(0, 1280, local, false)
            .unwrap();
        let right = InputLayout::StereoBoth
            .sample_uv(1, 1280, local, false)
            .unwrap();
        close(right[0] - left[0], 0.5);
        close(left[0] * 2., local[0]);
        close(right[1], local[1]);
        for eye in 0..2 {
            let edge = InputLayout::StereoBoth
                .sample_uv(eye, 1280, [1., 0.], false)
                .unwrap();
            assert!(edge[0] < (eye + 1) as f32 * 0.5);
            assert!(edge[0] > eye as f32 * 0.5);
            assert!(edge[1] > 0.);
            assert!(
                InputLayout::StereoBoth
                    .sample_uv(eye, 1280, [-0.01, 0.5], false)
                    .is_none()
            );
            assert!(
                InputLayout::StereoBoth
                    .sample_uv(eye, 1280, [f32::NAN, 0.5], false)
                    .is_none()
            );
        }
        assert!(rectilinear_uv([99., 0., -1.], source).is_none());
    }
    #[test]
    fn only_two_immutable_input_heights() {
        assert!(valid_input_height(672));
        assert!(valid_input_height(1280));
        for h in [0, 671, 673, 1279, 1281, -1] {
            assert!(!valid_input_height(h));
        }
    }
    #[test]
    fn contain_w08_exact_shape() {
        let Rect(r) = contain([1280, 672], [1680, 1760]).unwrap();
        close(r[0], 0.);
        close(r[1] * 1760., 439.);
        close(r[2] * 1680., 1680.);
        close(r[3] * 1760., 882.);
        assert!(contain([0, 672], [1, 1]).is_none());
    }
    #[test]
    fn asymmetric_frustum_identity_roundtrip() {
        let t = [-1.2, 0.8, -0.7, 1.1];
        for uv in [[0., 0.], [1., 1.], [0.5, 0.5], [0.2, 0.8]] {
            let out = rectilinear_uv(eye_ray(uv, t), t).unwrap();
            close(out[0], uv[0]);
            close(out[1], uv[1]);
        }
        let center = rectilinear_uv([0., 0., -1.], t).unwrap();
        close(center[0], 0.6);
        assert!(rectilinear_uv([0., 0., 1.], t).is_none());
        assert!(rectilinear_uv([99., 0., -1.], t).is_none());
        assert!(!valid_tangents([f32::NAN, 1., -1., 1.]));
    }
    #[test]
    fn quaternion_transform_preserves_center() {
        let q = [0., (0.25f32).sin(), 0., (0.25f32).cos()];
        let p = [0.3, -0.2, -1.];
        let out = rotate(quat_inverse(q), rotate(q, p));
        for i in 0..3 {
            close(out[i], p[i]);
        }
    }
    #[test]
    fn cylinder360_wraps_forward_image_once_and_exposes_the_back_seam() {
        let pi = std::f32::consts::PI;
        let sample = |ray| cylinder_uv([0.; 3], ray, 2., pi, 2.).unwrap();
        close(sample([0., 0., -1.])[0], 0.5);
        close(sample([1., 0., 0.])[0], 0.75);
        close(sample([-1., 0., 0.])[0], 0.25);
        let back = sample([0., 0., 1.])[0];
        assert!(back == 0. || back == 1.);
        assert!(sample([0.0001, 0., 1.])[0] > 0.999);
        assert!(sample([-0.0001, 0., 1.])[0] < 0.001);
        assert!(cylinder_uv([0.; 3], [0., 2., -1.], 2., pi, 2.).is_none());
        assert!(cylinder_uv([2., 0., 0.], [0., 0., -1.], 2., pi, 2.).is_none());
    }
    #[test]
    fn cylinder_window_has_no_back_fill() {
        let a = std::f32::consts::FRAC_PI_4;
        let uv = cylinder_uv([0.; 3], [0., 0., -1.], 2., a, 2.).unwrap();
        close(uv[0], 0.5);
        close(uv[1], 0.5);
        assert!(cylinder_uv([0.; 3], [0., 0., 1.], 2., a, 2.).is_none());
        assert!(cylinder_uv([0.; 3], [1., 0., -1.], 2., a, 2.).is_some());
        assert!(cylinder_uv([0.; 3], [2., 0., -1.], 2., a, 2.).is_none());
        assert!(cylinder_uv([0.; 3], [0., 2., -1.], 2., a, 2.).is_none());
    }
}
