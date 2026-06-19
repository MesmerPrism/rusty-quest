//! Replay/live visual evidence rectangle helpers for the Quest-native frame loop.

use super::{CameraProjectionMetadata, HandMeshVisualDiagnosticSettings, TargetRect};

#[derive(Clone, Copy)]
pub(super) struct ReplayVisualStats {
    pub(super) frame_index: u32,
    pub(super) timestamp_ns: u64,
    pub(super) visual_point_count: u64,
    pub(super) local_evidence_rect: EvidenceUvRect,
}

impl Default for ReplayVisualStats {
    fn default() -> Self {
        Self {
            frame_index: 0,
            timestamp_ns: 0,
            visual_point_count: 0,
            local_evidence_rect: EvidenceUvRect::default(),
        }
    }
}

impl ReplayVisualStats {
    pub(super) fn hand_mesh_screen_rect_marker_fields(
        self,
        projection_metadata: &CameraProjectionMetadata,
    ) -> String {
        self.screen_rect_marker_fields(
            projection_metadata,
            "leftHandMeshVisualScreenUvRect",
            "rightHandMeshVisualScreenUvRect",
        )
    }

    pub(super) fn sdf_screen_rect_marker_fields(
        self,
        projection_metadata: &CameraProjectionMetadata,
    ) -> String {
        self.screen_rect_marker_fields(
            projection_metadata,
            "leftSdfVisualScreenUvRect",
            "rightSdfVisualScreenUvRect",
        )
    }

    fn screen_rect_marker_fields(
        self,
        projection_metadata: &CameraProjectionMetadata,
        left_field: &str,
        right_field: &str,
    ) -> String {
        let left = self
            .local_evidence_rect
            .to_screen_rect(projection_metadata.rect_for_eye(0));
        let right = self
            .local_evidence_rect
            .to_screen_rect(projection_metadata.rect_for_eye(1));
        format!(
            "{left_field}={} {right_field}={}",
            left.marker_value(),
            right.marker_value()
        )
    }
}

#[derive(Clone, Copy)]
pub(super) struct EvidenceUvRect {
    x: f32,
    y: f32,
    width: f32,
    height: f32,
}

impl EvidenceUvRect {
    pub(super) fn from_points(
        points: &[[f32; 2]],
        diagnostic_settings: HandMeshVisualDiagnosticSettings,
    ) -> Self {
        if points.is_empty() {
            return Self::default();
        }

        let mut min_x = 1.0_f32;
        let mut min_y = 1.0_f32;
        let mut max_x = 0.0_f32;
        let mut max_y = 0.0_f32;
        let diagnostic_scale = if diagnostic_settings.enabled {
            1.35
        } else {
            1.0
        };
        let diagnostic_offset = if diagnostic_settings.enabled {
            diagnostic_settings.offset_uv
        } else {
            [0.0, 0.0]
        };
        for point in points {
            let x =
                (0.5 + (point[0] - 0.5) * diagnostic_scale + diagnostic_offset[0]).clamp(0.0, 1.0);
            let y =
                (0.5 + (point[1] - 0.5) * diagnostic_scale + diagnostic_offset[1]).clamp(0.0, 1.0);
            min_x = min_x.min(x);
            min_y = min_y.min(y);
            max_x = max_x.max(x);
            max_y = max_y.max(y);
        }

        Self::from_bounds(min_x, min_y, max_x, max_y).padded(0.035)
    }

    pub(super) fn from_bounds(min_x: f32, min_y: f32, max_x: f32, max_y: f32) -> Self {
        let x = min_x.min(max_x).clamp(0.0, 1.0);
        let y = min_y.min(max_y).clamp(0.0, 1.0);
        let width = (max_x.max(min_x) - x).max(0.001).min(1.0 - x);
        let height = (max_y.max(min_y) - y).max(0.001).min(1.0 - y);
        Self {
            x,
            y,
            width,
            height,
        }
    }

    pub(super) fn from_bounds_for_points(points: &[[f32; 2]]) -> Self {
        if points.is_empty() {
            return Self::default();
        }

        let mut min_x = 1.0_f32;
        let mut min_y = 1.0_f32;
        let mut max_x = 0.0_f32;
        let mut max_y = 0.0_f32;
        for [x, y] in points.iter().copied() {
            min_x = min_x.min(x);
            min_y = min_y.min(y);
            max_x = max_x.max(x);
            max_y = max_y.max(y);
        }

        Self::from_bounds(min_x, min_y, max_x, max_y)
    }

    pub(super) fn union_all(rects: &[Self]) -> Option<Self> {
        let mut rects = rects.iter().copied();
        let first = rects.next()?;
        let mut min_x = first.x;
        let mut min_y = first.y;
        let mut max_x = first.x + first.width;
        let mut max_y = first.y + first.height;

        for rect in rects {
            min_x = min_x.min(rect.x);
            min_y = min_y.min(rect.y);
            max_x = max_x.max(rect.x + rect.width);
            max_y = max_y.max(rect.y + rect.height);
        }

        Some(Self::from_bounds(min_x, min_y, max_x, max_y))
    }

    pub(super) fn padded(self, padding: f32) -> Self {
        let x = (self.x - padding).max(0.0);
        let y = (self.y - padding).max(0.0);
        let max_x = (self.x + self.width + padding).min(1.0);
        let max_y = (self.y + self.height + padding).min(1.0);
        Self::from_bounds(x, y, max_x, max_y)
    }

    pub(super) fn scaled_about_center(self, scale: f32, offset: [f32; 2]) -> Self {
        let points = [
            [self.x, self.y],
            [self.x + self.width, self.y],
            [self.x, self.y + self.height],
            [self.x + self.width, self.y + self.height],
        ];
        let scaled = points.map(|[x, y]| {
            [
                (0.5 + (x - 0.5) * scale + offset[0]).clamp(0.0, 1.0),
                (0.5 + (y - 0.5) * scale + offset[1]).clamp(0.0, 1.0),
            ]
        });
        Self::from_bounds_for_points(&scaled)
    }

    pub(super) fn to_screen_rect(self, target_rect: TargetRect) -> Self {
        Self::from_bounds(
            target_rect.x + self.x * target_rect.width,
            target_rect.y + self.y * target_rect.height,
            target_rect.x + (self.x + self.width) * target_rect.width,
            target_rect.y + (self.y + self.height) * target_rect.height,
        )
    }

    pub(super) fn marker_value(self) -> String {
        format!(
            "{:.6},{:.6},{:.6},{:.6}",
            self.x, self.y, self.width, self.height
        )
    }
}

impl Default for EvidenceUvRect {
    fn default() -> Self {
        Self {
            x: 0.25,
            y: 0.25,
            width: 0.50,
            height: 0.50,
        }
    }
}
