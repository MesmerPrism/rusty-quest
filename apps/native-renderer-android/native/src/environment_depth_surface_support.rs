//! Source-only surface-support mirror for environment-depth normals.
//!
//! The Android runtime path stays GPU-owned. This module gives host tests a
//! small reference for the future compute shader: reconstruct local depth
//! neighborhoods in OpenXR reference-space meters, prefer invalid normals over
//! noisy edge normals, and expose only aggregate counters.

use crate::environment_depth_geometry::{
    reconstruct_reference_space_point, FovTangents, ReferencePose,
};
use crate::native_renderer_environment_depth_options::{
    NativeEnvironmentDepthSurfaceComponentMode, NativeEnvironmentDepthSurfaceSmallComponentPolicy,
};

const DEFAULT_MAX_DEPTH_STEP_M: f32 = 0.18;
const DEFAULT_MIN_NORMAL_AREA_M2: f32 = 0.000_001;
pub(crate) const LOOSE_NORMAL_COHERENCE_MIN_DOT: f32 = 0.75;
pub(crate) const STRICT_NORMAL_COHERENCE_MIN_DOT: f32 = 0.92;

#[derive(Clone, Copy, Debug)]
pub(crate) struct DepthNeighborhoodNormalPolicy {
    pub(crate) max_depth_step_m: f32,
    pub(crate) min_normal_area_m2: f32,
}

impl Default for DepthNeighborhoodNormalPolicy {
    fn default() -> Self {
        Self {
            max_depth_step_m: DEFAULT_MAX_DEPTH_STEP_M,
            min_normal_area_m2: DEFAULT_MIN_NORMAL_AREA_M2,
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct SurfaceNormalGrid {
    pub(crate) width: usize,
    pub(crate) height: usize,
    pub(crate) estimates: Vec<SurfaceNormalEstimate>,
    pub(crate) counters: SurfaceNormalCounters,
}

impl SurfaceNormalGrid {
    pub(crate) fn estimate(&self, x: usize, y: usize) -> Option<&SurfaceNormalEstimate> {
        (x < self.width && y < self.height).then(|| &self.estimates[y * self.width + x])
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct SurfaceNormalCounters {
    pub(crate) valid_cells: u32,
    pub(crate) invalid_cells: u32,
    pub(crate) rejected_cells: u32,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct SurfaceComponentPolicy {
    pub(crate) component_mode: NativeEnvironmentDepthSurfaceComponentMode,
    pub(crate) min_component_cells: usize,
    pub(crate) small_component_policy: NativeEnvironmentDepthSurfaceSmallComponentPolicy,
}

impl Default for SurfaceComponentPolicy {
    fn default() -> Self {
        Self {
            component_mode: NativeEnvironmentDepthSurfaceComponentMode::Off,
            min_component_cells: 1,
            small_component_policy: NativeEnvironmentDepthSurfaceSmallComponentPolicy::Dim,
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct SurfaceComponentGrid {
    pub(crate) width: usize,
    pub(crate) height: usize,
    pub(crate) cells: Vec<SurfaceComponentCell>,
    pub(crate) counters: SurfaceComponentCounters,
}

impl SurfaceComponentGrid {
    pub(crate) fn cell(&self, x: usize, y: usize) -> Option<&SurfaceComponentCell> {
        (x < self.width && y < self.height).then(|| &self.cells[y * self.width + x])
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct SurfaceComponentCell {
    pub(crate) state: SurfaceComponentCellState,
    pub(crate) component_id: u32,
    pub(crate) component_size_cells: u32,
}

impl SurfaceComponentCell {
    fn empty() -> Self {
        Self {
            state: SurfaceComponentCellState::Empty,
            component_id: 0,
            component_size_cells: 0,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct SurfaceComponentCounters {
    pub(crate) supported_cells: u32,
    pub(crate) component_candidate_cells: u32,
    pub(crate) confirmed_component_cells: u32,
    pub(crate) small_component_cells: u32,
    pub(crate) small_component_rejected_cells: u32,
    pub(crate) largest_component_cells: u32,
    pub(crate) component_count: u32,
    pub(crate) small_component_count: u32,
    pub(crate) normal_visible_cells: u32,
    pub(crate) debug_visible_cells: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SurfaceComponentCellState {
    Empty,
    ComponentModeOff,
    ConfirmedComponent,
    SmallComponentDimmed,
    SmallComponentHidden,
    SmallComponentDebugOnly,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct SurfaceLifecyclePolicy {
    pub(crate) min_observations: u32,
    pub(crate) min_neighbors: u32,
    pub(crate) min_source_layers: u32,
}

impl Default for SurfaceLifecyclePolicy {
    fn default() -> Self {
        Self {
            min_observations: 1,
            min_neighbors: 0,
            min_source_layers: 1,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct SurfaceLifecycleSample {
    pub(crate) observed: bool,
    pub(crate) was_candidate: bool,
    pub(crate) observation_count: u32,
    pub(crate) neighbor_count: u32,
    pub(crate) source_layer_mask: u32,
    pub(crate) free_space_contradicted: bool,
}

#[derive(Clone, Debug)]
pub(crate) struct SurfaceLifecycleGrid {
    pub(crate) width: usize,
    pub(crate) height: usize,
    pub(crate) cells: Vec<SurfaceLifecycleCell>,
    pub(crate) counters: SurfaceLifecycleCounters,
}

impl SurfaceLifecycleGrid {
    pub(crate) fn cell(&self, x: usize, y: usize) -> Option<&SurfaceLifecycleCell> {
        (x < self.width && y < self.height).then(|| &self.cells[y * self.width + x])
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct SurfaceLifecycleCell {
    pub(crate) state: SurfaceLifecycleCellState,
    pub(crate) source_layer_count: u32,
    pub(crate) support_count: u32,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct SurfaceLifecycleCounters {
    pub(crate) supported_cells: u32,
    pub(crate) candidate_cells: u32,
    pub(crate) confirmed_cells: u32,
    pub(crate) promoted_cells: u32,
    pub(crate) retired_candidate_cells: u32,
    pub(crate) source_layer_agreement_cells: u32,
    pub(crate) single_layer_only_cells: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SurfaceLifecycleCellState {
    Empty,
    Candidate,
    Confirmed,
    RetiredCandidate,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct SurfaceNormalEstimate {
    pub(crate) normal: Option<[f32; 3]>,
    pub(crate) support_count: u32,
    pub(crate) residual_m: f32,
    pub(crate) reason: SurfaceNormalRejectReason,
}

impl SurfaceNormalEstimate {
    fn invalid(reason: SurfaceNormalRejectReason) -> Self {
        Self {
            normal: None,
            support_count: 0,
            residual_m: 0.0,
            reason,
        }
    }

    fn valid(normal: [f32; 3], residual_m: f32) -> Self {
        Self {
            normal: Some(normal),
            support_count: 4,
            residual_m,
            reason: SurfaceNormalRejectReason::Valid,
        }
    }

    pub(crate) fn is_valid(self) -> bool {
        matches!(self.reason, SurfaceNormalRejectReason::Valid) && self.normal.is_some()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SurfaceNormalRejectReason {
    Valid,
    Boundary,
    InvalidDepth,
    MissingNeighbor,
    DepthDiscontinuity,
    DegenerateNeighborhood,
}

impl SurfaceNormalRejectReason {
    fn is_rejected(self) -> bool {
        matches!(self, Self::DepthDiscontinuity)
    }
}

pub(crate) fn estimate_depth_neighborhood_normals(
    depth_meters: &[f32],
    width: usize,
    height: usize,
    depth_view_fov: FovTangents,
    depth_view_pose: ReferencePose,
    policy: DepthNeighborhoodNormalPolicy,
) -> Result<SurfaceNormalGrid, String> {
    if width == 0 || height == 0 {
        return Err("depth normal grid dimensions must be nonzero".to_string());
    }
    if depth_meters.len() != width.saturating_mul(height) {
        return Err(format!(
            "depth normal grid length mismatch: got {} expected {}",
            depth_meters.len(),
            width.saturating_mul(height)
        ));
    }

    let mut estimates = Vec::with_capacity(depth_meters.len());
    let mut counters = SurfaceNormalCounters::default();
    let policy = DepthNeighborhoodNormalPolicy {
        max_depth_step_m: if policy.max_depth_step_m.is_finite() && policy.max_depth_step_m > 0.0 {
            policy.max_depth_step_m
        } else {
            DEFAULT_MAX_DEPTH_STEP_M
        },
        min_normal_area_m2: if policy.min_normal_area_m2.is_finite()
            && policy.min_normal_area_m2 > 0.0
        {
            policy.min_normal_area_m2
        } else {
            DEFAULT_MIN_NORMAL_AREA_M2
        },
    };

    for y in 0..height {
        for x in 0..width {
            let estimate = estimate_depth_neighborhood_normal(
                depth_meters,
                width,
                height,
                x,
                y,
                depth_view_fov,
                depth_view_pose,
                policy,
            );
            if estimate.is_valid() {
                counters.valid_cells = counters.valid_cells.saturating_add(1);
            } else if estimate.reason.is_rejected() {
                counters.rejected_cells = counters.rejected_cells.saturating_add(1);
            } else {
                counters.invalid_cells = counters.invalid_cells.saturating_add(1);
            }
            estimates.push(estimate);
        }
    }

    Ok(SurfaceNormalGrid {
        width,
        height,
        estimates,
        counters,
    })
}

pub(crate) fn normals_are_coherent(a: [f32; 3], b: [f32; 3], min_dot: f32) -> bool {
    let Some(a) = normalize3(a) else {
        return false;
    };
    let Some(b) = normalize3(b) else {
        return false;
    };
    dot3(a, b) >= min_dot.clamp(-1.0, 1.0)
}

pub(crate) fn label_surface_components(
    supported_cells: &[bool],
    width: usize,
    height: usize,
    policy: SurfaceComponentPolicy,
) -> Result<SurfaceComponentGrid, String> {
    if width == 0 || height == 0 {
        return Err("surface component grid dimensions must be nonzero".to_string());
    }
    if supported_cells.len() != width.saturating_mul(height) {
        return Err(format!(
            "surface component grid length mismatch: got {} expected {}",
            supported_cells.len(),
            width.saturating_mul(height)
        ));
    }

    let supported_count = usize_to_u32(
        supported_cells
            .iter()
            .filter(|supported| **supported)
            .count(),
    );
    if policy.component_mode == NativeEnvironmentDepthSurfaceComponentMode::Off {
        let cells = supported_cells
            .iter()
            .map(|supported| {
                if *supported {
                    SurfaceComponentCell {
                        state: SurfaceComponentCellState::ComponentModeOff,
                        component_id: 0,
                        component_size_cells: 0,
                    }
                } else {
                    SurfaceComponentCell::empty()
                }
            })
            .collect();
        return Ok(SurfaceComponentGrid {
            width,
            height,
            cells,
            counters: SurfaceComponentCounters {
                supported_cells: supported_count,
                normal_visible_cells: supported_count,
                debug_visible_cells: supported_count,
                ..SurfaceComponentCounters::default()
            },
        });
    }

    let min_component_cells = policy.min_component_cells.max(1);
    let mut visited = vec![false; supported_cells.len()];
    let mut cells = vec![SurfaceComponentCell::empty(); supported_cells.len()];
    let mut counters = SurfaceComponentCounters {
        supported_cells: supported_count,
        component_candidate_cells: supported_count,
        ..SurfaceComponentCounters::default()
    };
    let mut component_id = 0_u32;

    for start in 0..supported_cells.len() {
        if !supported_cells[start] || visited[start] {
            continue;
        }
        component_id = component_id.saturating_add(1);
        counters.component_count = counters.component_count.saturating_add(1);

        let mut stack = vec![start];
        let mut component_cells = Vec::new();
        visited[start] = true;
        while let Some(index) = stack.pop() {
            component_cells.push(index);
            let x = index % width;
            let y = index / width;
            for neighbor in component_neighbors(x, y, width, height) {
                if supported_cells[neighbor] && !visited[neighbor] {
                    visited[neighbor] = true;
                    stack.push(neighbor);
                }
            }
        }

        let component_size = component_cells.len();
        let component_size_u32 = usize_to_u32(component_size);
        counters.largest_component_cells = counters.largest_component_cells.max(component_size_u32);
        let confirmed = component_size >= min_component_cells;
        let state = if confirmed {
            counters.confirmed_component_cells = counters
                .confirmed_component_cells
                .saturating_add(component_size_u32);
            counters.normal_visible_cells = counters
                .normal_visible_cells
                .saturating_add(component_size_u32);
            counters.debug_visible_cells = counters
                .debug_visible_cells
                .saturating_add(component_size_u32);
            SurfaceComponentCellState::ConfirmedComponent
        } else {
            counters.small_component_count = counters.small_component_count.saturating_add(1);
            counters.small_component_cells = counters
                .small_component_cells
                .saturating_add(component_size_u32);
            counters.small_component_rejected_cells = counters
                .small_component_rejected_cells
                .saturating_add(component_size_u32);
            match policy.small_component_policy {
                NativeEnvironmentDepthSurfaceSmallComponentPolicy::Dim => {
                    counters.normal_visible_cells = counters
                        .normal_visible_cells
                        .saturating_add(component_size_u32);
                    counters.debug_visible_cells = counters
                        .debug_visible_cells
                        .saturating_add(component_size_u32);
                    SurfaceComponentCellState::SmallComponentDimmed
                }
                NativeEnvironmentDepthSurfaceSmallComponentPolicy::Hide => {
                    SurfaceComponentCellState::SmallComponentHidden
                }
                NativeEnvironmentDepthSurfaceSmallComponentPolicy::DebugOnly => {
                    counters.debug_visible_cells = counters
                        .debug_visible_cells
                        .saturating_add(component_size_u32);
                    SurfaceComponentCellState::SmallComponentDebugOnly
                }
            }
        };

        for index in component_cells {
            cells[index] = SurfaceComponentCell {
                state,
                component_id,
                component_size_cells: component_size_u32,
            };
        }
    }

    Ok(SurfaceComponentGrid {
        width,
        height,
        cells,
        counters,
    })
}

pub(crate) fn classify_surface_lifecycle(
    samples: &[SurfaceLifecycleSample],
    width: usize,
    height: usize,
    policy: SurfaceLifecyclePolicy,
) -> Result<SurfaceLifecycleGrid, String> {
    if width == 0 || height == 0 {
        return Err("surface lifecycle grid dimensions must be nonzero".to_string());
    }
    if samples.len() != width.saturating_mul(height) {
        return Err(format!(
            "surface lifecycle grid length mismatch: got {} expected {}",
            samples.len(),
            width.saturating_mul(height)
        ));
    }

    let policy = SurfaceLifecyclePolicy {
        min_observations: policy.min_observations.max(1),
        min_neighbors: policy.min_neighbors.min(26),
        min_source_layers: policy.min_source_layers.clamp(1, 2),
    };
    let mut counters = SurfaceLifecycleCounters::default();
    let cells = samples
        .iter()
        .map(|sample| classify_surface_lifecycle_cell(*sample, policy, &mut counters))
        .collect();

    Ok(SurfaceLifecycleGrid {
        width,
        height,
        cells,
        counters,
    })
}

fn classify_surface_lifecycle_cell(
    sample: SurfaceLifecycleSample,
    policy: SurfaceLifecyclePolicy,
    counters: &mut SurfaceLifecycleCounters,
) -> SurfaceLifecycleCell {
    let source_layer_count = sample.source_layer_mask.count_ones().min(2);
    if source_layer_count >= 2 {
        counters.source_layer_agreement_cells =
            counters.source_layer_agreement_cells.saturating_add(1);
    } else if source_layer_count == 1 {
        counters.single_layer_only_cells = counters.single_layer_only_cells.saturating_add(1);
    }

    if !sample.observed {
        return SurfaceLifecycleCell {
            state: SurfaceLifecycleCellState::Empty,
            source_layer_count,
            support_count: sample.neighbor_count,
        };
    }

    counters.supported_cells = counters.supported_cells.saturating_add(1);
    let source_layers_ok = source_layer_count >= policy.min_source_layers;
    let observation_ok = sample.observation_count >= policy.min_observations;
    let neighbor_ok = sample.neighbor_count >= policy.min_neighbors;
    let confirmed = source_layers_ok && observation_ok && neighbor_ok;
    let state = if confirmed {
        counters.confirmed_cells = counters.confirmed_cells.saturating_add(1);
        if sample.was_candidate {
            counters.promoted_cells = counters.promoted_cells.saturating_add(1);
        }
        SurfaceLifecycleCellState::Confirmed
    } else if sample.free_space_contradicted && sample.was_candidate {
        counters.retired_candidate_cells = counters.retired_candidate_cells.saturating_add(1);
        SurfaceLifecycleCellState::RetiredCandidate
    } else {
        counters.candidate_cells = counters.candidate_cells.saturating_add(1);
        SurfaceLifecycleCellState::Candidate
    };

    SurfaceLifecycleCell {
        state,
        source_layer_count,
        support_count: sample.neighbor_count,
    }
}

fn estimate_depth_neighborhood_normal(
    depth_meters: &[f32],
    width: usize,
    height: usize,
    x: usize,
    y: usize,
    depth_view_fov: FovTangents,
    depth_view_pose: ReferencePose,
    policy: DepthNeighborhoodNormalPolicy,
) -> SurfaceNormalEstimate {
    if x == 0 || y == 0 || x + 1 >= width || y + 1 >= height {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::Boundary);
    }

    let center_depth = depth_meters[y * width + x];
    if !valid_depth(center_depth) {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::InvalidDepth);
    }

    let neighbor_coords = [(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)];
    let mut neighbor_depths = [0.0_f32; 4];
    for (index, (nx, ny)) in neighbor_coords.iter().copied().enumerate() {
        let depth = depth_meters[ny * width + nx];
        if !valid_depth(depth) {
            return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
        }
        if (depth - center_depth).abs() > policy.max_depth_step_m {
            return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::DepthDiscontinuity);
        }
        neighbor_depths[index] = depth;
    }

    let Some(center) = reconstruct_grid_point(
        x,
        y,
        center_depth,
        width,
        height,
        depth_view_fov,
        depth_view_pose,
    ) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::InvalidDepth);
    };
    let Some(left) = reconstruct_grid_point(
        x - 1,
        y,
        neighbor_depths[0],
        width,
        height,
        depth_view_fov,
        depth_view_pose,
    ) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
    };
    let Some(right) = reconstruct_grid_point(
        x + 1,
        y,
        neighbor_depths[1],
        width,
        height,
        depth_view_fov,
        depth_view_pose,
    ) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
    };
    let Some(down) = reconstruct_grid_point(
        x,
        y - 1,
        neighbor_depths[2],
        width,
        height,
        depth_view_fov,
        depth_view_pose,
    ) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
    };
    let Some(up) = reconstruct_grid_point(
        x,
        y + 1,
        neighbor_depths[3],
        width,
        height,
        depth_view_fov,
        depth_view_pose,
    ) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
    };

    let dx = sub3(right, left);
    let dy = sub3(up, down);
    let normal_area = cross3(dx, dy);
    if dot3(normal_area, normal_area) < policy.min_normal_area_m2 {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::DegenerateNeighborhood);
    }
    let Some(mut normal) = normalize3(normal_area) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::DegenerateNeighborhood);
    };

    let center_to_eye = sub3(depth_view_pose.position_m, center);
    if dot3(normal, center_to_eye) < 0.0 {
        normal = mul3(normal, -1.0);
    }

    let residual_m = [left, right, down, up]
        .iter()
        .map(|point| dot3(sub3(*point, center), normal).abs())
        .fold(0.0_f32, f32::max);

    SurfaceNormalEstimate::valid(normal, residual_m)
}

fn component_neighbors(x: usize, y: usize, width: usize, height: usize) -> [usize; 4] {
    [
        if x > 0 {
            y * width + (x - 1)
        } else {
            y * width + x
        },
        if x + 1 < width {
            y * width + (x + 1)
        } else {
            y * width + x
        },
        if y > 0 {
            (y - 1) * width + x
        } else {
            y * width + x
        },
        if y + 1 < height {
            (y + 1) * width + x
        } else {
            y * width + x
        },
    ]
}

fn usize_to_u32(value: usize) -> u32 {
    value.min(u32::MAX as usize) as u32
}

fn reconstruct_grid_point(
    x: usize,
    y: usize,
    depth_meters: f32,
    width: usize,
    height: usize,
    fov: FovTangents,
    pose: ReferencePose,
) -> Option<[f32; 3]> {
    let u = if width > 1 {
        x as f32 / (width as f32 - 1.0)
    } else {
        0.5
    };
    let v = if height > 1 {
        y as f32 / (height as f32 - 1.0)
    } else {
        0.5
    };
    reconstruct_reference_space_point([u, v], depth_meters, fov, pose)
}

fn valid_depth(value: f32) -> bool {
    value.is_finite() && value > 0.0
}

fn sub3(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

fn mul3(a: [f32; 3], scale: f32) -> [f32; 3] {
    [a[0] * scale, a[1] * scale, a[2] * scale]
}

fn cross3(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

fn dot3(a: [f32; 3], b: [f32; 3]) -> f32 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

fn normalize3(value: [f32; 3]) -> Option<[f32; 3]> {
    let length_sq = dot3(value, value);
    if !length_sq.is_finite() || length_sq <= 0.000_000_000_001 {
        return None;
    }
    let inv = length_sq.sqrt().recip();
    Some([value[0] * inv, value[1] * inv, value[2] * inv])
}

#[cfg(test)]
mod tests {
    use super::{
        classify_surface_lifecycle, estimate_depth_neighborhood_normals, label_surface_components,
        normals_are_coherent, DepthNeighborhoodNormalPolicy, SurfaceComponentCellState,
        SurfaceComponentPolicy, SurfaceLifecycleCellState, SurfaceLifecyclePolicy,
        SurfaceLifecycleSample, SurfaceNormalRejectReason, LOOSE_NORMAL_COHERENCE_MIN_DOT,
        STRICT_NORMAL_COHERENCE_MIN_DOT,
    };
    use crate::environment_depth_geometry::{FovTangents, ReferencePose};
    use crate::native_renderer_environment_depth_options::{
        NativeEnvironmentDepthSurfaceComponentMode,
        NativeEnvironmentDepthSurfaceSmallComponentPolicy,
    };

    fn default_policy() -> DepthNeighborhoodNormalPolicy {
        DepthNeighborhoodNormalPolicy {
            max_depth_step_m: 0.25,
            min_normal_area_m2: 0.000_001,
        }
    }

    fn flat_grid(width: usize, height: usize, depth_m: f32) -> Vec<f32> {
        vec![depth_m; width * height]
    }

    fn component_policy(
        min_component_cells: usize,
        small_component_policy: NativeEnvironmentDepthSurfaceSmallComponentPolicy,
    ) -> SurfaceComponentPolicy {
        SurfaceComponentPolicy {
            component_mode: NativeEnvironmentDepthSurfaceComponentMode::ConnectedLabels,
            min_component_cells,
            small_component_policy,
        }
    }

    #[test]
    fn flat_depth_plane_produces_coherent_camera_facing_normals() {
        let grid = estimate_depth_neighborhood_normals(
            &flat_grid(5, 5, 2.0),
            5,
            5,
            FovTangents::symmetric(0.5),
            ReferencePose::identity(),
            default_policy(),
        )
        .expect("flat grid estimates");

        assert_eq!(grid.counters.valid_cells, 9);
        assert_eq!(grid.counters.invalid_cells, 16);
        assert_eq!(grid.counters.rejected_cells, 0);

        let center = grid.estimate(2, 2).expect("center estimate");
        assert!(center.is_valid());
        assert_eq!(center.support_count, 4);
        assert!(center.residual_m < 0.0001, "residual {}", center.residual_m);
        let normal = center.normal.expect("center normal");
        assert!(normal[2] > 0.999, "normal {normal:?}");

        let neighbor = grid
            .estimate(2, 3)
            .and_then(|estimate| estimate.normal)
            .expect("neighbor normal");
        assert!(normals_are_coherent(
            normal,
            neighbor,
            STRICT_NORMAL_COHERENCE_MIN_DOT
        ));
    }

    #[test]
    fn depth_step_rejects_normals_along_discontinuity_without_erasing_planes() {
        let mut depths = Vec::new();
        for _y in 0..5 {
            for x in 0..7 {
                depths.push(if x <= 2 { 2.0 } else { 4.0 });
            }
        }

        let grid = estimate_depth_neighborhood_normals(
            &depths,
            7,
            5,
            FovTangents::symmetric(0.5),
            ReferencePose::identity(),
            default_policy(),
        )
        .expect("step grid estimates");

        assert_eq!(grid.counters.valid_cells, 9);
        assert_eq!(grid.counters.invalid_cells, 20);
        assert_eq!(grid.counters.rejected_cells, 6);
        assert_eq!(
            grid.estimate(2, 2).expect("step-left estimate").reason,
            SurfaceNormalRejectReason::DepthDiscontinuity
        );
        assert_eq!(
            grid.estimate(3, 2).expect("step-right estimate").reason,
            SurfaceNormalRejectReason::DepthDiscontinuity
        );
        assert!(grid.estimate(1, 2).expect("left plane").is_valid());
        assert!(grid.estimate(5, 2).expect("right plane").is_valid());
    }

    #[test]
    fn holes_make_neighbor_normals_invalid_not_accepted() {
        let mut depths = flat_grid(5, 5, 2.0);
        depths[2 * 5 + 2] = f32::NAN;

        let grid = estimate_depth_neighborhood_normals(
            &depths,
            5,
            5,
            FovTangents::symmetric(0.5),
            ReferencePose::identity(),
            default_policy(),
        )
        .expect("hole grid estimates");

        assert_eq!(grid.counters.valid_cells, 4);
        assert_eq!(grid.counters.invalid_cells, 21);
        assert_eq!(grid.counters.rejected_cells, 0);
        assert_eq!(
            grid.estimate(2, 2).expect("hole center").reason,
            SurfaceNormalRejectReason::InvalidDepth
        );
        assert_eq!(
            grid.estimate(2, 1).expect("hole neighbor").reason,
            SurfaceNormalRejectReason::MissingNeighbor
        );
    }

    #[test]
    fn coherence_gate_rejects_opposed_normals_and_accepts_loose_tilt() {
        assert!(normals_are_coherent(
            [0.0, 0.0, 1.0],
            [0.60, 0.0, 0.80],
            LOOSE_NORMAL_COHERENCE_MIN_DOT
        ));
        assert!(!normals_are_coherent(
            [0.0, 0.0, 1.0],
            [0.60, 0.0, 0.80],
            STRICT_NORMAL_COHERENCE_MIN_DOT
        ));
        assert!(!normals_are_coherent(
            [0.0, 0.0, 1.0],
            [0.0, 0.0, -1.0],
            LOOSE_NORMAL_COHERENCE_MIN_DOT
        ));
    }

    #[test]
    fn mismatched_grid_dimensions_are_rejected_before_estimation() {
        let error = estimate_depth_neighborhood_normals(
            &[1.0, 1.0, 1.0],
            2,
            2,
            FovTangents::symmetric(0.5),
            ReferencePose::identity(),
            default_policy(),
        )
        .expect_err("mismatched grid rejects");

        assert!(error.contains("length mismatch"));
    }

    #[test]
    fn connected_components_keep_large_plane_and_classify_isolated_floaters() {
        let mut supported = vec![false; 8 * 6];
        for y in 1..5 {
            for x in 1..5 {
                supported[y * 8 + x] = true;
            }
        }
        supported[6] = true;
        supported[5 * 8 + 7] = true;

        let grid = label_surface_components(
            &supported,
            8,
            6,
            component_policy(
                4,
                NativeEnvironmentDepthSurfaceSmallComponentPolicy::DebugOnly,
            ),
        )
        .expect("component labels");

        assert_eq!(grid.counters.supported_cells, 18);
        assert_eq!(grid.counters.component_count, 3);
        assert_eq!(grid.counters.largest_component_cells, 16);
        assert_eq!(grid.counters.confirmed_component_cells, 16);
        assert_eq!(grid.counters.small_component_cells, 2);
        assert_eq!(grid.counters.small_component_rejected_cells, 2);
        assert_eq!(grid.counters.normal_visible_cells, 16);
        assert_eq!(grid.counters.debug_visible_cells, 18);
        assert_eq!(
            grid.cell(2, 2).expect("plane cell").state,
            SurfaceComponentCellState::ConfirmedComponent
        );
        assert_eq!(
            grid.cell(6, 0).expect("floater").state,
            SurfaceComponentCellState::SmallComponentDebugOnly
        );
    }

    #[test]
    fn small_component_hide_policy_removes_tiny_clusters_from_normal_view() {
        let supported = [
            true, true, false, false, //
            false, false, false, false, //
            false, false, true, false, //
            false, false, false, true, //
        ];

        let grid = label_surface_components(
            &supported,
            4,
            4,
            component_policy(3, NativeEnvironmentDepthSurfaceSmallComponentPolicy::Hide),
        )
        .expect("component labels");

        assert_eq!(grid.counters.supported_cells, 4);
        assert_eq!(grid.counters.confirmed_component_cells, 0);
        assert_eq!(grid.counters.small_component_rejected_cells, 4);
        assert_eq!(grid.counters.normal_visible_cells, 0);
        assert_eq!(grid.counters.debug_visible_cells, 0);
        assert_eq!(
            grid.cell(0, 0).expect("small cluster").state,
            SurfaceComponentCellState::SmallComponentHidden
        );
    }

    #[test]
    fn component_mode_off_preserves_supported_cells_without_labels() {
        let supported = [true, false, false, true];

        let grid = label_surface_components(
            &supported,
            2,
            2,
            SurfaceComponentPolicy {
                component_mode: NativeEnvironmentDepthSurfaceComponentMode::Off,
                min_component_cells: 8,
                small_component_policy: NativeEnvironmentDepthSurfaceSmallComponentPolicy::Hide,
            },
        )
        .expect("component labels");

        assert_eq!(grid.counters.supported_cells, 2);
        assert_eq!(grid.counters.component_count, 0);
        assert_eq!(grid.counters.normal_visible_cells, 2);
        assert_eq!(
            grid.cell(0, 0).expect("supported").state,
            SurfaceComponentCellState::ComponentModeOff
        );
        assert_eq!(
            grid.cell(1, 0).expect("empty").state,
            SurfaceComponentCellState::Empty
        );
    }

    #[test]
    fn lifecycle_promotes_supported_candidates_and_counts_layer_agreement() {
        let samples = [
            SurfaceLifecycleSample {
                observed: true,
                was_candidate: true,
                observation_count: 3,
                neighbor_count: 4,
                source_layer_mask: 0b11,
                free_space_contradicted: false,
            },
            SurfaceLifecycleSample {
                observed: true,
                was_candidate: false,
                observation_count: 1,
                neighbor_count: 1,
                source_layer_mask: 0b01,
                free_space_contradicted: false,
            },
            SurfaceLifecycleSample {
                observed: true,
                was_candidate: true,
                observation_count: 1,
                neighbor_count: 0,
                source_layer_mask: 0b01,
                free_space_contradicted: true,
            },
            SurfaceLifecycleSample::default(),
        ];

        let grid = classify_surface_lifecycle(
            &samples,
            2,
            2,
            SurfaceLifecyclePolicy {
                min_observations: 2,
                min_neighbors: 2,
                min_source_layers: 2,
            },
        )
        .expect("lifecycle classification");

        assert_eq!(grid.counters.supported_cells, 3);
        assert_eq!(grid.counters.confirmed_cells, 1);
        assert_eq!(grid.counters.candidate_cells, 1);
        assert_eq!(grid.counters.promoted_cells, 1);
        assert_eq!(grid.counters.retired_candidate_cells, 1);
        assert_eq!(grid.counters.source_layer_agreement_cells, 1);
        assert_eq!(grid.counters.single_layer_only_cells, 2);
        assert_eq!(
            grid.cell(0, 0).expect("promoted cell").state,
            SurfaceLifecycleCellState::Confirmed
        );
        assert_eq!(
            grid.cell(1, 0).expect("candidate cell").state,
            SurfaceLifecycleCellState::Candidate
        );
        assert_eq!(
            grid.cell(0, 1).expect("retired cell").state,
            SurfaceLifecycleCellState::RetiredCandidate
        );
    }

    #[test]
    fn lifecycle_rejects_mismatched_grid_dimensions() {
        let error = classify_surface_lifecycle(
            &[SurfaceLifecycleSample::default()],
            2,
            2,
            SurfaceLifecyclePolicy::default(),
        )
        .expect_err("mismatched grid rejects");

        assert!(error.contains("length mismatch"));
    }

    #[test]
    fn lifecycle_sequence_retires_dynamic_object_ghost_on_free_space() {
        let policy = SurfaceLifecyclePolicy {
            min_observations: 2,
            min_neighbors: 2,
            min_source_layers: 1,
        };

        let mut appearing = vec![SurfaceLifecycleSample::default(); 9];
        appearing[4] = SurfaceLifecycleSample {
            observed: true,
            was_candidate: false,
            observation_count: 1,
            neighbor_count: 1,
            source_layer_mask: 0b01,
            free_space_contradicted: false,
        };
        let appearing_grid =
            classify_surface_lifecycle(&appearing, 3, 3, policy).expect("appearing frame");
        assert_eq!(appearing_grid.counters.candidate_cells, 1);
        assert_eq!(
            appearing_grid.cell(1, 1).expect("appearing object").state,
            SurfaceLifecycleCellState::Candidate
        );

        let mut confirmed = vec![SurfaceLifecycleSample::default(); 9];
        confirmed[4] = SurfaceLifecycleSample {
            observed: true,
            was_candidate: true,
            observation_count: 3,
            neighbor_count: 4,
            source_layer_mask: 0b11,
            free_space_contradicted: false,
        };
        let confirmed_grid =
            classify_surface_lifecycle(&confirmed, 3, 3, policy).expect("confirmed frame");
        assert_eq!(confirmed_grid.counters.confirmed_cells, 1);
        assert_eq!(confirmed_grid.counters.promoted_cells, 1);
        assert_eq!(
            confirmed_grid.cell(1, 1).expect("confirmed object").state,
            SurfaceLifecycleCellState::Confirmed
        );

        let mut moved = vec![SurfaceLifecycleSample::default(); 9];
        moved[4] = SurfaceLifecycleSample {
            observed: true,
            was_candidate: true,
            observation_count: 1,
            neighbor_count: 0,
            source_layer_mask: 0b01,
            free_space_contradicted: true,
        };
        moved[5] = SurfaceLifecycleSample {
            observed: true,
            was_candidate: false,
            observation_count: 1,
            neighbor_count: 1,
            source_layer_mask: 0b01,
            free_space_contradicted: false,
        };
        let moved_grid = classify_surface_lifecycle(&moved, 3, 3, policy).expect("moved frame");
        assert_eq!(moved_grid.counters.retired_candidate_cells, 1);
        assert_eq!(moved_grid.counters.candidate_cells, 1);
        assert_eq!(moved_grid.counters.confirmed_cells, 0);
        assert_eq!(
            moved_grid.cell(1, 1).expect("old object cell").state,
            SurfaceLifecycleCellState::RetiredCandidate
        );
        assert_eq!(
            moved_grid.cell(2, 1).expect("new object cell").state,
            SurfaceLifecycleCellState::Candidate
        );
    }
}
