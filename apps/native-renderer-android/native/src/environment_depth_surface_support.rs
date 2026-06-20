//! Source-only surface-support mirror for environment-depth normals.
//!
//! The Android runtime path stays GPU-owned. This module gives host tests a
//! small reference for the future compute shader: reconstruct local depth
//! neighborhoods in OpenXR reference-space meters, prefer invalid normals over
//! noisy edge normals, and expose aggregate counters plus compact descriptor
//! shapes for future GPU-owned support buffers.

use crate::environment_depth_geometry::{
    reconstruct_reference_space_point, FovTangents, ReferencePose,
};
pub(crate) use crate::environment_depth_scene_map::scene_cell_for_reference_space_position;
use crate::native_renderer_environment_depth_options::{
    NativeEnvironmentDepthSurfaceComponentMode, NativeEnvironmentDepthSurfaceSmallComponentPolicy,
};

const DEFAULT_MAX_DEPTH_STEP_M: f32 = 0.18;
const DEFAULT_MIN_NORMAL_AREA_M2: f32 = 0.000_001;
pub(crate) const LOOSE_NORMAL_COHERENCE_MIN_DOT: f32 = 0.75;
pub(crate) const STRICT_NORMAL_COHERENCE_MIN_DOT: f32 = 0.92;
pub(crate) const COMPACT_SURFACE_INVALID_PACKED_NORMAL: u32 = u32::MAX;
pub(crate) const COMPACT_SURFACE_FLAG_CONFIRMED_LIFECYCLE: u32 = 1 << 0;
pub(crate) const COMPACT_SURFACE_FLAG_CANDIDATE_LIFECYCLE: u32 = 1 << 1;
pub(crate) const COMPACT_SURFACE_FLAG_RETIRED_CANDIDATE: u32 = 1 << 2;
pub(crate) const COMPACT_SURFACE_FLAG_VALID_NORMAL: u32 = 1 << 3;
pub(crate) const COMPACT_SURFACE_FLAG_COMPONENT_CONFIRMED: u32 = 1 << 4;
pub(crate) const COMPACT_SURFACE_FLAG_SMALL_COMPONENT: u32 = 1 << 5;
pub(crate) const COMPACT_SURFACE_FLAG_SOURCE_LAYER_AGREEMENT: u32 = 1 << 6;
pub(crate) const COMPACT_SURFACE_FLAG_NORMAL_VISIBLE: u32 = 1 << 7;
pub(crate) const COMPACT_SURFACE_FLAG_DEBUG_VISIBLE: u32 = 1 << 8;
pub(crate) const COMPACT_SURFACE_FLAG_DRAWABLE_SURFACE: u32 = 1 << 9;

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

#[derive(Clone, Copy, Debug)]
pub(crate) struct RetainedCellNormalSample {
    pub(crate) observed: bool,
    pub(crate) reference_space_position_m: [f32; 3],
}

impl Default for RetainedCellNormalSample {
    fn default() -> Self {
        Self {
            observed: false,
            reference_space_position_m: [0.0; 3],
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct RetainedSceneCellSample {
    pub(crate) observed: bool,
    pub(crate) reference_space_position_m: [f32; 3],
    pub(crate) scene_cell: [i32; 3],
}

impl Default for RetainedSceneCellSample {
    fn default() -> Self {
        Self {
            observed: false,
            reference_space_position_m: [0.0; 3],
            scene_cell: [0; 3],
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

#[derive(Clone, Debug)]
pub(crate) struct CompactSurfaceDescriptorGrid {
    pub(crate) width: usize,
    pub(crate) height: usize,
    pub(crate) descriptors: Vec<CompactSurfaceDescriptor>,
    pub(crate) counters: CompactSurfaceDescriptorCounters,
}

impl CompactSurfaceDescriptorGrid {
    pub(crate) fn descriptor(&self, x: usize, y: usize) -> Option<&CompactSurfaceDescriptor> {
        (x < self.width && y < self.height).then(|| &self.descriptors[y * self.width + x])
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct CompactSurfaceDescriptor {
    pub(crate) flags: u32,
    pub(crate) packed_normal_snorm10: u32,
    pub(crate) residual_mm: u16,
    pub(crate) support_count: u16,
    pub(crate) source_layer_count: u8,
    pub(crate) component_id: u32,
    pub(crate) component_size_cells: u32,
}

impl CompactSurfaceDescriptor {
    fn empty() -> Self {
        Self {
            flags: 0,
            packed_normal_snorm10: COMPACT_SURFACE_INVALID_PACKED_NORMAL,
            residual_mm: 0,
            support_count: 0,
            source_layer_count: 0,
            component_id: 0,
            component_size_cells: 0,
        }
    }

    pub(crate) fn has_flag(self, flag: u32) -> bool {
        self.flags & flag != 0
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct CompactSurfaceDescriptorCounters {
    pub(crate) confirmed_lifecycle_cells: u32,
    pub(crate) candidate_lifecycle_cells: u32,
    pub(crate) retired_candidate_cells: u32,
    pub(crate) valid_normal_cells: u32,
    pub(crate) drawable_surface_cells: u32,
    pub(crate) normal_visible_cells: u32,
    pub(crate) debug_visible_cells: u32,
    pub(crate) small_component_cells: u32,
    pub(crate) source_layer_agreement_cells: u32,
    pub(crate) rejected_missing_normal_cells: u32,
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
    let policy = sanitize_normal_policy(policy);

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

pub(crate) fn estimate_retained_cell_neighborhood_normals(
    samples: &[RetainedCellNormalSample],
    width: usize,
    height: usize,
    observer_position_m: [f32; 3],
    policy: DepthNeighborhoodNormalPolicy,
) -> Result<SurfaceNormalGrid, String> {
    if width == 0 || height == 0 {
        return Err("retained-cell normal grid dimensions must be nonzero".to_string());
    }
    if samples.len() != width.saturating_mul(height) {
        return Err(format!(
            "retained-cell normal grid length mismatch: got {} expected {}",
            samples.len(),
            width.saturating_mul(height)
        ));
    }

    let mut estimates = Vec::with_capacity(samples.len());
    let mut counters = SurfaceNormalCounters::default();
    let policy = sanitize_normal_policy(policy);

    for y in 0..height {
        for x in 0..width {
            let estimate = estimate_retained_cell_neighborhood_normal(
                samples,
                width,
                height,
                x,
                y,
                observer_position_m,
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

pub(crate) fn reconstruct_retained_scene_cell_samples(
    depth_meters: &[f32],
    width: usize,
    height: usize,
    depth_view_fov: FovTangents,
    depth_view_pose: ReferencePose,
) -> Result<Vec<RetainedSceneCellSample>, String> {
    if width == 0 || height == 0 {
        return Err("retained scene-cell grid dimensions must be nonzero".to_string());
    }
    if depth_meters.len() != width.saturating_mul(height) {
        return Err(format!(
            "retained scene-cell grid length mismatch: got {} expected {}",
            depth_meters.len(),
            width.saturating_mul(height)
        ));
    }

    let mut samples = Vec::with_capacity(depth_meters.len());
    for y in 0..height {
        for x in 0..width {
            let depth = depth_meters[y * width + x];
            let sample = valid_depth(depth)
                .then(|| {
                    reconstruct_grid_point(
                        x,
                        y,
                        depth,
                        width,
                        height,
                        depth_view_fov,
                        depth_view_pose,
                    )
                })
                .flatten()
                .and_then(|reference_space_position_m| {
                    scene_cell_for_reference_space_position(reference_space_position_m).map(
                        |scene_cell| RetainedSceneCellSample {
                            observed: true,
                            reference_space_position_m,
                            scene_cell,
                        },
                    )
                })
                .unwrap_or_default();
            samples.push(sample);
        }
    }

    Ok(samples)
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

pub(crate) fn build_compact_surface_descriptors(
    lifecycle: &SurfaceLifecycleGrid,
    normals: &SurfaceNormalGrid,
    components: &SurfaceComponentGrid,
) -> Result<CompactSurfaceDescriptorGrid, String> {
    if lifecycle.width == 0 || lifecycle.height == 0 {
        return Err("compact surface descriptor grid dimensions must be nonzero".to_string());
    }
    if lifecycle.width != normals.width
        || lifecycle.height != normals.height
        || lifecycle.width != components.width
        || lifecycle.height != components.height
    {
        return Err(format!(
            "compact surface descriptor grid mismatch: lifecycle={}x{} normals={}x{} components={}x{}",
            lifecycle.width,
            lifecycle.height,
            normals.width,
            normals.height,
            components.width,
            components.height
        ));
    }
    let expected_len = lifecycle.width.saturating_mul(lifecycle.height);
    if lifecycle.cells.len() != expected_len
        || normals.estimates.len() != expected_len
        || components.cells.len() != expected_len
    {
        return Err(format!(
            "compact surface descriptor length mismatch: expected {} lifecycle={} normals={} components={}",
            expected_len,
            lifecycle.cells.len(),
            normals.estimates.len(),
            components.cells.len()
        ));
    }

    let mut counters = CompactSurfaceDescriptorCounters::default();
    let descriptors = (0..expected_len)
        .map(|index| {
            compact_surface_descriptor_cell(
                lifecycle.cells[index],
                normals.estimates[index],
                components.cells[index],
                &mut counters,
            )
        })
        .collect();

    Ok(CompactSurfaceDescriptorGrid {
        width: lifecycle.width,
        height: lifecycle.height,
        descriptors,
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

fn compact_surface_descriptor_cell(
    lifecycle: SurfaceLifecycleCell,
    normal: SurfaceNormalEstimate,
    component: SurfaceComponentCell,
    counters: &mut CompactSurfaceDescriptorCounters,
) -> CompactSurfaceDescriptor {
    let mut descriptor = CompactSurfaceDescriptor::empty();
    descriptor.support_count = lifecycle.support_count.min(u16::MAX as u32) as u16;
    descriptor.source_layer_count = lifecycle.source_layer_count.min(u8::MAX as u32) as u8;
    descriptor.component_id = component.component_id;
    descriptor.component_size_cells = component.component_size_cells;

    match lifecycle.state {
        SurfaceLifecycleCellState::Empty => {}
        SurfaceLifecycleCellState::Candidate => {
            descriptor.flags |= COMPACT_SURFACE_FLAG_CANDIDATE_LIFECYCLE;
            counters.candidate_lifecycle_cells =
                counters.candidate_lifecycle_cells.saturating_add(1);
        }
        SurfaceLifecycleCellState::Confirmed => {
            descriptor.flags |= COMPACT_SURFACE_FLAG_CONFIRMED_LIFECYCLE;
            counters.confirmed_lifecycle_cells =
                counters.confirmed_lifecycle_cells.saturating_add(1);
        }
        SurfaceLifecycleCellState::RetiredCandidate => {
            descriptor.flags |= COMPACT_SURFACE_FLAG_RETIRED_CANDIDATE;
            counters.retired_candidate_cells = counters.retired_candidate_cells.saturating_add(1);
        }
    }

    if lifecycle.source_layer_count >= 2 {
        descriptor.flags |= COMPACT_SURFACE_FLAG_SOURCE_LAYER_AGREEMENT;
        counters.source_layer_agreement_cells =
            counters.source_layer_agreement_cells.saturating_add(1);
    }

    let packed_normal = normal
        .normal
        .filter(|_| normal.is_valid())
        .map(pack_normal_snorm10)
        .unwrap_or(COMPACT_SURFACE_INVALID_PACKED_NORMAL);
    if packed_normal != COMPACT_SURFACE_INVALID_PACKED_NORMAL {
        descriptor.flags |= COMPACT_SURFACE_FLAG_VALID_NORMAL;
        descriptor.packed_normal_snorm10 = packed_normal;
        descriptor.residual_mm = meters_to_u16_mm(normal.residual_m);
        counters.valid_normal_cells = counters.valid_normal_cells.saturating_add(1);
    } else if matches!(
        lifecycle.state,
        SurfaceLifecycleCellState::Candidate | SurfaceLifecycleCellState::Confirmed
    ) {
        counters.rejected_missing_normal_cells =
            counters.rejected_missing_normal_cells.saturating_add(1);
    }

    let mut normal_visible = false;
    let mut debug_visible = false;
    match component.state {
        SurfaceComponentCellState::Empty => {}
        SurfaceComponentCellState::ComponentModeOff
        | SurfaceComponentCellState::ConfirmedComponent => {
            descriptor.flags |= COMPACT_SURFACE_FLAG_COMPONENT_CONFIRMED;
            normal_visible = true;
            debug_visible = true;
        }
        SurfaceComponentCellState::SmallComponentDimmed => {
            descriptor.flags |= COMPACT_SURFACE_FLAG_SMALL_COMPONENT;
            normal_visible = true;
            debug_visible = true;
        }
        SurfaceComponentCellState::SmallComponentHidden => {
            descriptor.flags |= COMPACT_SURFACE_FLAG_SMALL_COMPONENT;
        }
        SurfaceComponentCellState::SmallComponentDebugOnly => {
            descriptor.flags |= COMPACT_SURFACE_FLAG_SMALL_COMPONENT;
            debug_visible = true;
        }
    }

    if descriptor.has_flag(COMPACT_SURFACE_FLAG_SMALL_COMPONENT) {
        counters.small_component_cells = counters.small_component_cells.saturating_add(1);
    }

    if normal_visible && descriptor.has_flag(COMPACT_SURFACE_FLAG_VALID_NORMAL) {
        descriptor.flags |= COMPACT_SURFACE_FLAG_NORMAL_VISIBLE;
        counters.normal_visible_cells = counters.normal_visible_cells.saturating_add(1);
    }
    if debug_visible {
        descriptor.flags |= COMPACT_SURFACE_FLAG_DEBUG_VISIBLE;
        counters.debug_visible_cells = counters.debug_visible_cells.saturating_add(1);
    }
    if descriptor.has_flag(COMPACT_SURFACE_FLAG_CONFIRMED_LIFECYCLE)
        && descriptor.has_flag(COMPACT_SURFACE_FLAG_NORMAL_VISIBLE)
    {
        descriptor.flags |= COMPACT_SURFACE_FLAG_DRAWABLE_SURFACE;
        counters.drawable_surface_cells = counters.drawable_surface_cells.saturating_add(1);
    }

    descriptor
}

fn sanitize_normal_policy(policy: DepthNeighborhoodNormalPolicy) -> DepthNeighborhoodNormalPolicy {
    DepthNeighborhoodNormalPolicy {
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
    }
}

fn estimate_retained_cell_neighborhood_normal(
    samples: &[RetainedCellNormalSample],
    width: usize,
    height: usize,
    x: usize,
    y: usize,
    observer_position_m: [f32; 3],
    policy: DepthNeighborhoodNormalPolicy,
) -> SurfaceNormalEstimate {
    if x == 0 || y == 0 || x + 1 >= width || y + 1 >= height {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::Boundary);
    }

    let Some(center) = retained_cell_position(samples[y * width + x]) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::InvalidDepth);
    };
    let Some(left) = retained_cell_position(samples[y * width + (x - 1)]) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
    };
    let Some(right) = retained_cell_position(samples[y * width + (x + 1)]) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
    };
    let Some(down) = retained_cell_position(samples[(y - 1) * width + x]) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
    };
    let Some(up) = retained_cell_position(samples[(y + 1) * width + x]) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::MissingNeighbor);
    };

    normal_from_cross_neighbors(center, left, right, down, up, observer_position_m, policy)
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

    normal_from_cross_neighbors(
        center,
        left,
        right,
        down,
        up,
        depth_view_pose.position_m,
        policy,
    )
}

fn normal_from_cross_neighbors(
    center: [f32; 3],
    left: [f32; 3],
    right: [f32; 3],
    down: [f32; 3],
    up: [f32; 3],
    observer_position_m: [f32; 3],
    policy: DepthNeighborhoodNormalPolicy,
) -> SurfaceNormalEstimate {
    let dx = sub3(right, left);
    let dy = sub3(up, down);
    let normal_area = cross3(dx, dy);
    if dot3(normal_area, normal_area) < policy.min_normal_area_m2 {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::DegenerateNeighborhood);
    }
    let Some(mut normal) = normalize3(normal_area) else {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::DegenerateNeighborhood);
    };

    let center_to_eye = sub3(observer_position_m, center);
    if dot3(normal, center_to_eye) < 0.0 {
        normal = mul3(normal, -1.0);
    }

    let residual_m = [left, right, down, up]
        .iter()
        .map(|point| dot3(sub3(*point, center), normal).abs())
        .fold(0.0_f32, f32::max);
    if residual_m > policy.max_depth_step_m {
        return SurfaceNormalEstimate::invalid(SurfaceNormalRejectReason::DepthDiscontinuity);
    }

    SurfaceNormalEstimate::valid(normal, residual_m)
}

fn retained_cell_position(sample: RetainedCellNormalSample) -> Option<[f32; 3]> {
    if sample.observed
        && sample
            .reference_space_position_m
            .iter()
            .all(|value| value.is_finite())
    {
        Some(sample.reference_space_position_m)
    } else {
        None
    }
}

fn pack_normal_snorm10(normal: [f32; 3]) -> u32 {
    let Some(normal) = normalize3(normal) else {
        return COMPACT_SURFACE_INVALID_PACKED_NORMAL;
    };
    pack_snorm10_component(normal[0])
        | (pack_snorm10_component(normal[1]) << 10)
        | (pack_snorm10_component(normal[2]) << 20)
}

fn pack_snorm10_component(value: f32) -> u32 {
    let scaled = (value.clamp(-1.0, 1.0) * 511.0).round() as i32;
    (scaled & 0x3ff) as u32
}

fn meters_to_u16_mm(value: f32) -> u16 {
    if value.is_finite() && value > 0.0 {
        (value * 1000.0).round().clamp(0.0, u16::MAX as f32) as u16
    } else {
        0
    }
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
        build_compact_surface_descriptors, classify_surface_lifecycle,
        estimate_depth_neighborhood_normals, estimate_retained_cell_neighborhood_normals,
        label_surface_components, normals_are_coherent, reconstruct_retained_scene_cell_samples,
        scene_cell_for_reference_space_position, CompactSurfaceDescriptorGrid,
        DepthNeighborhoodNormalPolicy, RetainedCellNormalSample, SurfaceComponentCellState,
        SurfaceComponentPolicy, SurfaceLifecycleCellState, SurfaceLifecyclePolicy,
        SurfaceLifecycleSample, SurfaceNormalCounters, SurfaceNormalEstimate, SurfaceNormalGrid,
        SurfaceNormalRejectReason, COMPACT_SURFACE_FLAG_COMPONENT_CONFIRMED,
        COMPACT_SURFACE_FLAG_CONFIRMED_LIFECYCLE, COMPACT_SURFACE_FLAG_DEBUG_VISIBLE,
        COMPACT_SURFACE_FLAG_DRAWABLE_SURFACE, COMPACT_SURFACE_FLAG_NORMAL_VISIBLE,
        COMPACT_SURFACE_FLAG_SMALL_COMPONENT, COMPACT_SURFACE_FLAG_SOURCE_LAYER_AGREEMENT,
        COMPACT_SURFACE_FLAG_VALID_NORMAL, COMPACT_SURFACE_INVALID_PACKED_NORMAL,
        LOOSE_NORMAL_COHERENCE_MIN_DOT, STRICT_NORMAL_COHERENCE_MIN_DOT,
    };
    use crate::environment_depth_geometry::{FovTangents, ReferencePose};
    use crate::environment_depth_scene_map::SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS;
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

    fn retained_plane_grid(width: usize, height: usize, z_m: f32) -> Vec<RetainedCellNormalSample> {
        let center_x = (width as f32 - 1.0) * 0.5;
        let center_y = (height as f32 - 1.0) * 0.5;
        let spacing = 0.10;
        let mut cells = Vec::with_capacity(width * height);
        for y in 0..height {
            for x in 0..width {
                cells.push(RetainedCellNormalSample {
                    observed: true,
                    reference_space_position_m: [
                        (x as f32 - center_x) * spacing,
                        (y as f32 - center_y) * spacing,
                        z_m,
                    ],
                });
            }
        }
        cells
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

    fn confirmed_lifecycle_grid(width: usize, height: usize) -> Vec<SurfaceLifecycleSample> {
        vec![
            SurfaceLifecycleSample {
                observed: true,
                was_candidate: true,
                observation_count: 3,
                neighbor_count: 4,
                source_layer_mask: 0b11,
                free_space_contradicted: false,
            };
            width * height
        ]
    }

    fn descriptor_grid_from_retained_plane(
        width: usize,
        height: usize,
        min_component_cells: usize,
        small_component_policy: NativeEnvironmentDepthSurfaceSmallComponentPolicy,
    ) -> CompactSurfaceDescriptorGrid {
        let normal_grid = estimate_retained_cell_neighborhood_normals(
            &retained_plane_grid(width, height, -2.0),
            width,
            height,
            [0.0, 0.0, 0.0],
            default_policy(),
        )
        .expect("retained plane normals");
        let lifecycle_grid = classify_surface_lifecycle(
            &confirmed_lifecycle_grid(width, height),
            width,
            height,
            SurfaceLifecyclePolicy {
                min_observations: 2,
                min_neighbors: 2,
                min_source_layers: 2,
            },
        )
        .expect("confirmed lifecycle");
        let supported = normal_grid
            .estimates
            .iter()
            .map(|estimate| estimate.is_valid())
            .collect::<Vec<_>>();
        let component_grid = label_surface_components(
            &supported,
            width,
            height,
            component_policy(min_component_cells, small_component_policy),
        )
        .expect("component labels");

        build_compact_surface_descriptors(&lifecycle_grid, &normal_grid, &component_grid)
            .expect("compact descriptors")
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
    fn retained_cell_plane_produces_coherent_camera_facing_normals() {
        let grid = estimate_retained_cell_neighborhood_normals(
            &retained_plane_grid(5, 5, -2.0),
            5,
            5,
            [0.0, 0.0, 0.0],
            default_policy(),
        )
        .expect("retained plane estimates");

        assert_eq!(grid.counters.valid_cells, 9);
        assert_eq!(grid.counters.invalid_cells, 16);
        assert_eq!(grid.counters.rejected_cells, 0);
        let normal = grid
            .estimate(2, 2)
            .and_then(|estimate| estimate.normal)
            .expect("center retained-cell normal");
        assert!(normal[2] > 0.999, "normal {normal:?}");
        let neighbor = grid
            .estimate(2, 3)
            .and_then(|estimate| estimate.normal)
            .expect("neighbor retained-cell normal");
        assert!(normals_are_coherent(
            normal,
            neighbor,
            STRICT_NORMAL_COHERENCE_MIN_DOT
        ));
    }

    #[test]
    fn retained_cell_missing_neighbor_invalidates_normal() {
        let mut cells = retained_plane_grid(5, 5, -2.0);
        cells[2 * 5 + 1].observed = false;

        let grid = estimate_retained_cell_neighborhood_normals(
            &cells,
            5,
            5,
            [0.0, 0.0, 0.0],
            default_policy(),
        )
        .expect("retained missing-neighbor estimates");

        assert_eq!(
            grid.estimate(2, 2).expect("center estimate").reason,
            SurfaceNormalRejectReason::MissingNeighbor
        );
    }

    #[test]
    fn retained_cell_discontinuous_neighbor_rejects_normal() {
        let mut cells = retained_plane_grid(5, 5, -2.0);
        cells[2 * 5 + 3].reference_space_position_m[2] = -4.0;

        let grid = estimate_retained_cell_neighborhood_normals(
            &cells,
            5,
            5,
            [0.0, 0.0, 0.0],
            DepthNeighborhoodNormalPolicy {
                max_depth_step_m: 0.05,
                min_normal_area_m2: 0.000_001,
            },
        )
        .expect("retained discontinuity estimates");

        assert_eq!(
            grid.estimate(2, 2).expect("discontinuous estimate").reason,
            SurfaceNormalRejectReason::DepthDiscontinuity
        );
        assert!(grid.counters.rejected_cells > 0);
    }

    #[test]
    fn retained_scene_cells_stay_world_space_across_lateral_pose_shift() {
        let depths = flat_grid(5, 5, 2.0);
        let fov = FovTangents::symmetric(0.12);
        let anchor_samples =
            reconstruct_retained_scene_cell_samples(&depths, 5, 5, fov, ReferencePose::identity())
                .expect("anchor scene cells");
        let shifted_pose = ReferencePose {
            position_m: [SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS * 2.0, 0.0, 0.0],
            orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
        };
        let shifted_samples =
            reconstruct_retained_scene_cell_samples(&depths, 5, 5, fov, shifted_pose)
                .expect("shifted scene cells");

        let anchor_center = anchor_samples[2 * 5 + 2];
        let shifted_same_world_point = shifted_samples[2 * 5 + 1];
        let shifted_center = shifted_samples[2 * 5 + 2];

        assert!(anchor_center.observed);
        assert!(shifted_same_world_point.observed);
        assert!(shifted_center.observed);
        assert_eq!(
            shifted_same_world_point.scene_cell, anchor_center.scene_cell,
            "same reference-space point should retain its scene cell after camera motion"
        );
        assert_ne!(
            shifted_center.scene_cell, anchor_center.scene_cell,
            "screen-fixed center samples should not masquerade as retained world cells"
        );
        assert_eq!(
            shifted_center.scene_cell[0],
            anchor_center.scene_cell[0] + 2
        );
    }

    #[test]
    fn retained_scene_cells_reject_invalid_depth_and_nonfinite_positions() {
        let samples = reconstruct_retained_scene_cell_samples(
            &[2.0, f32::NAN, 2.0, 0.0],
            2,
            2,
            FovTangents::symmetric(0.12),
            ReferencePose::identity(),
        )
        .expect("scene cell samples");

        assert!(samples[0].observed);
        assert!(!samples[1].observed);
        assert!(samples[2].observed);
        assert!(!samples[3].observed);
        assert!(scene_cell_for_reference_space_position([0.0, f32::NAN, -2.0]).is_none());
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
    fn compact_surface_descriptors_pack_confirmed_plane_normals() {
        let grid = descriptor_grid_from_retained_plane(
            5,
            5,
            4,
            NativeEnvironmentDepthSurfaceSmallComponentPolicy::Hide,
        );

        assert_eq!(grid.counters.confirmed_lifecycle_cells, 25);
        assert_eq!(grid.counters.source_layer_agreement_cells, 25);
        assert_eq!(grid.counters.valid_normal_cells, 9);
        assert_eq!(grid.counters.rejected_missing_normal_cells, 16);
        assert_eq!(grid.counters.drawable_surface_cells, 9);
        assert_eq!(grid.counters.normal_visible_cells, 9);
        assert_eq!(grid.counters.debug_visible_cells, 9);
        let center = *grid.descriptor(2, 2).expect("center descriptor");
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_CONFIRMED_LIFECYCLE));
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_VALID_NORMAL));
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_COMPONENT_CONFIRMED));
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_SOURCE_LAYER_AGREEMENT));
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_NORMAL_VISIBLE));
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_DEBUG_VISIBLE));
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_DRAWABLE_SURFACE));
        assert_ne!(
            center.packed_normal_snorm10,
            COMPACT_SURFACE_INVALID_PACKED_NORMAL
        );
        assert_eq!(center.residual_mm, 0);
        assert_eq!(center.support_count, 4);
        assert_eq!(center.source_layer_count, 2);
        assert_eq!(center.component_size_cells, 9);

        let boundary = *grid.descriptor(0, 0).expect("boundary descriptor");
        assert!(boundary.has_flag(COMPACT_SURFACE_FLAG_CONFIRMED_LIFECYCLE));
        assert!(boundary.has_flag(COMPACT_SURFACE_FLAG_SOURCE_LAYER_AGREEMENT));
        assert!(!boundary.has_flag(COMPACT_SURFACE_FLAG_VALID_NORMAL));
        assert!(!boundary.has_flag(COMPACT_SURFACE_FLAG_DRAWABLE_SURFACE));
        assert_eq!(
            boundary.packed_normal_snorm10,
            COMPACT_SURFACE_INVALID_PACKED_NORMAL
        );
    }

    #[test]
    fn compact_surface_descriptors_hide_small_normal_components() {
        let normal_grid = estimate_retained_cell_neighborhood_normals(
            &retained_plane_grid(5, 5, -2.0),
            5,
            5,
            [0.0, 0.0, 0.0],
            default_policy(),
        )
        .expect("retained plane normals");
        let lifecycle_grid = classify_surface_lifecycle(
            &confirmed_lifecycle_grid(5, 5),
            5,
            5,
            SurfaceLifecyclePolicy {
                min_observations: 2,
                min_neighbors: 2,
                min_source_layers: 2,
            },
        )
        .expect("confirmed lifecycle");
        let mut supported = vec![false; 25];
        supported[2 * 5 + 2] = true;
        let component_grid = label_surface_components(
            &supported,
            5,
            5,
            component_policy(2, NativeEnvironmentDepthSurfaceSmallComponentPolicy::Hide),
        )
        .expect("component labels");

        let grid =
            build_compact_surface_descriptors(&lifecycle_grid, &normal_grid, &component_grid)
                .expect("compact descriptors");

        assert_eq!(grid.counters.valid_normal_cells, 9);
        assert_eq!(grid.counters.small_component_cells, 1);
        assert_eq!(grid.counters.drawable_surface_cells, 0);
        assert_eq!(grid.counters.normal_visible_cells, 0);
        assert_eq!(grid.counters.debug_visible_cells, 0);
        let center = *grid.descriptor(2, 2).expect("center descriptor");
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_CONFIRMED_LIFECYCLE));
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_VALID_NORMAL));
        assert!(center.has_flag(COMPACT_SURFACE_FLAG_SMALL_COMPONENT));
        assert!(!center.has_flag(COMPACT_SURFACE_FLAG_NORMAL_VISIBLE));
        assert!(!center.has_flag(COMPACT_SURFACE_FLAG_DEBUG_VISIBLE));
        assert!(!center.has_flag(COMPACT_SURFACE_FLAG_DRAWABLE_SURFACE));
    }

    #[test]
    fn compact_surface_descriptors_reject_unpacked_invalid_normals() {
        let lifecycle_grid = classify_surface_lifecycle(
            &confirmed_lifecycle_grid(1, 1),
            1,
            1,
            SurfaceLifecyclePolicy {
                min_observations: 2,
                min_neighbors: 2,
                min_source_layers: 2,
            },
        )
        .expect("confirmed lifecycle");
        let normal_grid = SurfaceNormalGrid {
            width: 1,
            height: 1,
            estimates: vec![SurfaceNormalEstimate {
                normal: Some([0.0, 0.0, 0.0]),
                support_count: 4,
                residual_m: 0.0,
                reason: SurfaceNormalRejectReason::Valid,
            }],
            counters: SurfaceNormalCounters {
                valid_cells: 1,
                invalid_cells: 0,
                rejected_cells: 0,
            },
        };
        let component_grid = label_surface_components(
            &[true],
            1,
            1,
            component_policy(1, NativeEnvironmentDepthSurfaceSmallComponentPolicy::Dim),
        )
        .expect("component labels");

        let grid =
            build_compact_surface_descriptors(&lifecycle_grid, &normal_grid, &component_grid)
                .expect("compact descriptors");

        assert_eq!(grid.counters.valid_normal_cells, 0);
        assert_eq!(grid.counters.rejected_missing_normal_cells, 1);
        assert_eq!(grid.counters.normal_visible_cells, 0);
        assert_eq!(grid.counters.drawable_surface_cells, 0);
        let descriptor = *grid.descriptor(0, 0).expect("descriptor");
        assert!(descriptor.has_flag(COMPACT_SURFACE_FLAG_CONFIRMED_LIFECYCLE));
        assert!(descriptor.has_flag(COMPACT_SURFACE_FLAG_COMPONENT_CONFIRMED));
        assert!(!descriptor.has_flag(COMPACT_SURFACE_FLAG_VALID_NORMAL));
        assert!(!descriptor.has_flag(COMPACT_SURFACE_FLAG_NORMAL_VISIBLE));
        assert!(!descriptor.has_flag(COMPACT_SURFACE_FLAG_DRAWABLE_SURFACE));
        assert_eq!(
            descriptor.packed_normal_snorm10,
            COMPACT_SURFACE_INVALID_PACKED_NORMAL
        );
    }

    #[test]
    fn compact_surface_descriptors_reject_mismatched_grids() {
        let lifecycle_grid = classify_surface_lifecycle(
            &confirmed_lifecycle_grid(2, 2),
            2,
            2,
            SurfaceLifecyclePolicy::default(),
        )
        .expect("lifecycle grid");
        let normal_grid = estimate_retained_cell_neighborhood_normals(
            &retained_plane_grid(3, 3, -2.0),
            3,
            3,
            [0.0, 0.0, 0.0],
            default_policy(),
        )
        .expect("normal grid");
        let component_grid = label_surface_components(
            &[true, true, true, true],
            2,
            2,
            component_policy(1, NativeEnvironmentDepthSurfaceSmallComponentPolicy::Dim),
        )
        .expect("component grid");

        let error =
            build_compact_surface_descriptors(&lifecycle_grid, &normal_grid, &component_grid)
                .expect_err("mismatched grids reject");

        assert!(error.contains("grid mismatch"));
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
