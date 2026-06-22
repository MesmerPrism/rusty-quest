//! Host-testable affine alignment for environment-depth projection sampling.
//!
//! The shader receives one render-UV to depth-UV affine transform. This module
//! composes the depth FOV transform with the projection target's reference
//! rectangle so depth stays aligned when the rendered target footprint is
//! scaled at runtime.

use crate::{
    environment_depth_alignment_state::EnvironmentDepthAlignmentEyeOffsets,
    projection_rect::TargetRect,
};

pub(crate) const IDENTITY_DEPTH_UV_TRANSFORM: [f32; 4] = [1.0, 0.0, 1.0, 0.0];
const MIN_TARGET_RECT_AXIS_UV: f32 = 0.000_001;

#[derive(Clone, Copy, Debug)]
pub(crate) struct AlignedDepthUvTransform {
    pub(crate) target_reference_uv_transform: [f32; 4],
    pub(crate) depth_uv_transform: [f32; 4],
}

pub(crate) fn aligned_depth_uv_transform(
    depth_uv_transform_base: [f32; 4],
    reference_target_rect: TargetRect,
    effective_target_rect: TargetRect,
    depth_alignment_offsets: EnvironmentDepthAlignmentEyeOffsets,
) -> AlignedDepthUvTransform {
    let target_reference_uv_transform =
        target_reference_uv_transform(reference_target_rect, effective_target_rect);
    let reference_depth_uv_transform =
        compose_uv_transform(depth_uv_transform_base, target_reference_uv_transform);
    let depth_sample_scale = if depth_alignment_offsets.sample_scale.is_finite() {
        depth_alignment_offsets.sample_scale
    } else {
        1.0
    };
    let depth_center = [
        depth_uv_transform_base[0] * 0.5 + depth_uv_transform_base[1],
        depth_uv_transform_base[2] * 0.5 + depth_uv_transform_base[3],
    ];
    AlignedDepthUvTransform {
        target_reference_uv_transform,
        depth_uv_transform: [
            reference_depth_uv_transform[0] * depth_sample_scale,
            depth_center[0]
                + (reference_depth_uv_transform[1] - depth_center[0]) * depth_sample_scale
                + depth_alignment_offsets.effective_offset_uv[0],
            reference_depth_uv_transform[2] * depth_sample_scale,
            depth_center[1]
                + (reference_depth_uv_transform[3] - depth_center[1]) * depth_sample_scale
                + depth_alignment_offsets.effective_offset_uv[1],
        ],
    }
}

pub(crate) fn target_reference_uv_transform(
    reference_target_rect: TargetRect,
    effective_target_rect: TargetRect,
) -> [f32; 4] {
    if !reference_target_rect.is_valid()
        || !effective_target_rect.is_valid()
        || reference_target_rect.width <= MIN_TARGET_RECT_AXIS_UV
        || reference_target_rect.height <= MIN_TARGET_RECT_AXIS_UV
        || effective_target_rect.width <= MIN_TARGET_RECT_AXIS_UV
        || effective_target_rect.height <= MIN_TARGET_RECT_AXIS_UV
    {
        return IDENTITY_DEPTH_UV_TRANSFORM;
    }
    let x_scale = reference_target_rect.width / effective_target_rect.width;
    let y_scale = reference_target_rect.height / effective_target_rect.height;
    [
        x_scale,
        reference_target_rect.x - effective_target_rect.x * x_scale,
        y_scale,
        reference_target_rect.y - effective_target_rect.y * y_scale,
    ]
}

fn compose_uv_transform(outer: [f32; 4], inner: [f32; 4]) -> [f32; 4] {
    [
        outer[0] * inner[0],
        outer[0] * inner[1] + outer[1],
        outer[2] * inner[2],
        outer[2] * inner[3] + outer[3],
    ]
}

#[cfg(test)]
mod tests {
    use super::{aligned_depth_uv_transform, EnvironmentDepthAlignmentEyeOffsets, TargetRect};

    fn apply_uv_transform(transform: [f32; 4], uv: [f32; 2]) -> [f32; 2] {
        [
            uv[0] * transform[0] + transform[1],
            uv[1] * transform[2] + transform[3],
        ]
    }

    fn assert_close(left: f32, right: f32) {
        assert!(
            (left - right).abs() < 0.000_01,
            "expected {left:.8} to be close to {right:.8}"
        );
    }

    fn assert_uv_close(left: [f32; 2], right: [f32; 2]) {
        assert_close(left[0], right[0]);
        assert_close(left[1], right[1]);
    }

    #[test]
    fn depth_transform_maps_scaled_target_rect_back_to_reference_content() {
        let depth_uv_transform_base = [1.2, -0.1, 0.8, 0.05];
        let reference_target_rect = TargetRect::new(0.20, 0.25, 0.60, 0.50);
        let effective_target_rect = TargetRect::new(0.35, 0.375, 0.30, 0.25);
        let alignment = EnvironmentDepthAlignmentEyeOffsets {
            base_offset_uv: [0.0, 0.0],
            manual_offset_uv: [0.0, 0.0],
            effective_offset_uv: [0.0, 0.0],
            sample_scale: 1.0,
        };
        let aligned = aligned_depth_uv_transform(
            depth_uv_transform_base,
            reference_target_rect,
            effective_target_rect,
            alignment,
        );
        let content_uv = [0.25, 0.70];
        let effective_render_uv = [
            effective_target_rect.x + content_uv[0] * effective_target_rect.width,
            effective_target_rect.y + content_uv[1] * effective_target_rect.height,
        ];
        let reference_render_uv = [
            reference_target_rect.x + content_uv[0] * reference_target_rect.width,
            reference_target_rect.y + content_uv[1] * reference_target_rect.height,
        ];

        assert_uv_close(
            apply_uv_transform(aligned.depth_uv_transform, effective_render_uv),
            apply_uv_transform(depth_uv_transform_base, reference_render_uv),
        );
        assert_close(aligned.target_reference_uv_transform[0], 2.0);
        assert_close(aligned.target_reference_uv_transform[2], 2.0);
    }

    #[test]
    fn depth_transform_preserves_default_target_scale_calibration() {
        let depth_uv_transform_base = [1.1, -0.05, 0.9, 0.07];
        let target_rect = TargetRect::new(0.171875, 0.21875, 0.75, 0.65625);
        let alignment = EnvironmentDepthAlignmentEyeOffsets {
            base_offset_uv: [0.0035, -0.011],
            manual_offset_uv: [0.0, 0.0],
            effective_offset_uv: [0.0035, -0.011],
            sample_scale: 0.899,
        };
        let aligned = aligned_depth_uv_transform(
            depth_uv_transform_base,
            target_rect,
            target_rect,
            alignment,
        );
        let expected = [
            depth_uv_transform_base[0] * alignment.sample_scale,
            depth_uv_transform_base[1]
                + 0.5 * depth_uv_transform_base[0] * (1.0 - alignment.sample_scale)
                + alignment.effective_offset_uv[0],
            depth_uv_transform_base[2] * alignment.sample_scale,
            depth_uv_transform_base[3]
                + 0.5 * depth_uv_transform_base[2] * (1.0 - alignment.sample_scale)
                + alignment.effective_offset_uv[1],
        ];

        for (actual, expected) in aligned.depth_uv_transform.into_iter().zip(expected) {
            assert_close(actual, expected);
        }
    }
}
