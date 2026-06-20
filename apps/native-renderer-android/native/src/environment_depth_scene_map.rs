//! Source-only scene-map oracle for environment-depth spatial hashing.
//!
//! The Android runtime owns the real Vulkan buffers and atomics. This module
//! mirrors the bounded cell hash, probe, merge, stale-replace, and free-space
//! retire policy with deterministic host tests so shader policy changes have
//! a no-device regression surface.

pub(crate) const SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS: f32 = 0.06;
pub(crate) const SOURCE_ONLY_SCENE_PARTICLE_HASH_PROBE_COUNT: usize = 8;
pub(crate) const SOURCE_ONLY_SCENE_PARTICLE_STALE_REPLACE_FRAMES: u32 = 1440;

const SOURCE_ONLY_SCENE_PARTICLE_MERGE_WEIGHT: f32 = 0.18;
const SCENE_META_SOURCE_LAYER_MASK: u32 = 0x0000_0003;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SourceOnlySceneCellState {
    Empty,
    Active,
    Retired,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct SourceOnlySceneMapSlot {
    pub(crate) key: u32,
    pub(crate) state: SourceOnlySceneCellState,
    pub(crate) cell: [i32; 3],
    pub(crate) reference_space_position_m: [f32; 3],
    pub(crate) depth_m: f32,
    pub(crate) confidence: f32,
    pub(crate) last_frame: u32,
    pub(crate) source_layer_mask: u32,
    pub(crate) observation_count: u32,
    pub(crate) confirmed: bool,
}

impl Default for SourceOnlySceneMapSlot {
    fn default() -> Self {
        Self {
            key: 0,
            state: SourceOnlySceneCellState::Empty,
            cell: [0; 3],
            reference_space_position_m: [0.0; 3],
            depth_m: 0.0,
            confidence: 0.0,
            last_frame: 0,
            source_layer_mask: 0,
            observation_count: 0,
            confirmed: false,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct SourceOnlySceneMapPolicy {
    pub(crate) capacity: usize,
    pub(crate) min_observations: u32,
    pub(crate) min_source_layers: u32,
    pub(crate) record_lifecycle_counters: bool,
}

impl Default for SourceOnlySceneMapPolicy {
    fn default() -> Self {
        Self {
            capacity: 64,
            min_observations: 2,
            min_source_layers: 1,
            record_lifecycle_counters: true,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct SourceOnlySceneObservation {
    pub(crate) reference_space_position_m: [f32; 3],
    pub(crate) depth_m: f32,
    pub(crate) confidence: f32,
    pub(crate) source_layer_mask: u32,
    pub(crate) frame_index: u32,
    pub(crate) local_surface_supported: bool,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct SourceOnlySceneMapCounters {
    pub(crate) hash_insert_success_count: u32,
    pub(crate) hash_merge_count: u32,
    pub(crate) hash_stale_replace_count: u32,
    pub(crate) hash_probe_exhausted_count: u32,
    pub(crate) hash_write_conflict_count: u32,
    pub(crate) free_space_retire_attempt_count: u32,
    pub(crate) free_space_retire_success_count: u32,
    pub(crate) surface_candidate_cells: u32,
    pub(crate) surface_confirmed_cells: u32,
    pub(crate) surface_promoted_cells: u32,
    pub(crate) surface_candidate_retired_cells: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SourceOnlySceneMapWriteOutcome {
    Inserted,
    Merged,
    ReinsertedRetired,
    StaleReplaced,
    ProbeExhausted,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SourceOnlySceneMapRetireOutcome {
    Retired,
    Missing,
    NotActive,
}

#[derive(Clone, Debug)]
pub(crate) struct SourceOnlySceneMap {
    policy: SourceOnlySceneMapPolicy,
    slots: Vec<SourceOnlySceneMapSlot>,
    counters: SourceOnlySceneMapCounters,
}

impl SourceOnlySceneMap {
    pub(crate) fn new(policy: SourceOnlySceneMapPolicy) -> Result<Self, String> {
        if policy.capacity == 0 {
            return Err("source-only scene-map capacity must be nonzero".to_string());
        }
        if policy.min_observations == 0 {
            return Err("source-only scene-map min_observations must be nonzero".to_string());
        }
        if !(1..=2).contains(&policy.min_source_layers) {
            return Err("source-only scene-map min_source_layers must be 1 or 2".to_string());
        }
        Ok(Self {
            policy,
            slots: vec![SourceOnlySceneMapSlot::default(); policy.capacity],
            counters: SourceOnlySceneMapCounters::default(),
        })
    }

    pub(crate) fn counters(&self) -> SourceOnlySceneMapCounters {
        self.counters
    }

    pub(crate) fn slot_for_cell(&self, cell: [i32; 3]) -> Option<&SourceOnlySceneMapSlot> {
        let hash_value = hash_scene_cell(cell);
        let key = compact_scene_cell_key(hash_value);
        let base_slot = hash_value as usize % self.slots.len();
        for probe in 0..SOURCE_ONLY_SCENE_PARTICLE_HASH_PROBE_COUNT {
            let slot = &self.slots[(base_slot + probe) % self.slots.len()];
            if slot.key == 0 {
                return None;
            }
            if slot.key == key {
                return Some(slot);
            }
        }
        None
    }

    pub(crate) fn write_observation(
        &mut self,
        observation: SourceOnlySceneObservation,
    ) -> Result<SourceOnlySceneMapWriteOutcome, String> {
        if !observation
            .reference_space_position_m
            .iter()
            .all(|value| value.is_finite())
            || !observation.depth_m.is_finite()
            || observation.depth_m <= 0.0
            || !observation.confidence.is_finite()
        {
            return Err(
                "source-only scene-map observation must be finite and in front of the eye"
                    .to_string(),
            );
        }

        let cell = scene_cell_for_reference_space_position(observation.reference_space_position_m)
            .ok_or_else(|| "source-only scene-map position is outside cell range".to_string())?;
        let hash_value = hash_scene_cell(cell);
        let key = compact_scene_cell_key(hash_value);
        let base_slot = hash_value as usize % self.slots.len();

        for probe in 0..SOURCE_ONLY_SCENE_PARTICLE_HASH_PROBE_COUNT {
            let slot_index = (base_slot + probe) % self.slots.len();
            let observed_key = self.slots[slot_index].key;
            let observed_state = self.slots[slot_index].state;
            let same_cell = observed_key == key;
            let stale = self.slot_is_stale(slot_index, observation.frame_index);

            if observed_key == 0 {
                self.counters.hash_insert_success_count =
                    self.counters.hash_insert_success_count.saturating_add(1);
                self.write_active_scene_cell(slot_index, cell, key, probe, observation, 1, false);
                return Ok(SourceOnlySceneMapWriteOutcome::Inserted);
            }

            if same_cell {
                if observed_state == SourceOnlySceneCellState::Retired {
                    self.counters.hash_insert_success_count =
                        self.counters.hash_insert_success_count.saturating_add(1);
                    self.write_active_scene_cell(
                        slot_index,
                        cell,
                        key,
                        probe,
                        observation,
                        1,
                        false,
                    );
                    return Ok(SourceOnlySceneMapWriteOutcome::ReinsertedRetired);
                }
                if observed_state != SourceOnlySceneCellState::Active {
                    continue;
                }

                if stale {
                    self.counters.hash_stale_replace_count =
                        self.counters.hash_stale_replace_count.saturating_add(1);
                    self.write_active_scene_cell(
                        slot_index,
                        cell,
                        key,
                        probe,
                        observation,
                        1,
                        false,
                    );
                    return Ok(SourceOnlySceneMapWriteOutcome::StaleReplaced);
                }

                self.counters.hash_merge_count = self.counters.hash_merge_count.saturating_add(1);
                let merged = merged_observation(self.slots[slot_index], observation);
                let observation_count = self.slots[slot_index]
                    .observation_count
                    .saturating_add(1)
                    .min(255);
                let source_layer_mask =
                    self.slots[slot_index].source_layer_mask | observation.source_layer_mask;
                let was_confirmed = self.slots[slot_index].confirmed;
                self.write_active_scene_cell(
                    slot_index,
                    cell,
                    key,
                    probe,
                    SourceOnlySceneObservation {
                        source_layer_mask,
                        frame_index: observation.frame_index,
                        local_surface_supported: observation.local_surface_supported,
                        ..merged
                    },
                    observation_count,
                    was_confirmed,
                );
                return Ok(SourceOnlySceneMapWriteOutcome::Merged);
            }

            if observed_state == SourceOnlySceneCellState::Retired || stale {
                self.counters.hash_stale_replace_count =
                    self.counters.hash_stale_replace_count.saturating_add(1);
                self.write_active_scene_cell(slot_index, cell, key, probe, observation, 1, false);
                return Ok(SourceOnlySceneMapWriteOutcome::StaleReplaced);
            }

            self.counters.hash_write_conflict_count =
                self.counters.hash_write_conflict_count.saturating_add(1);
        }

        self.counters.hash_probe_exhausted_count =
            self.counters.hash_probe_exhausted_count.saturating_add(1);
        Ok(SourceOnlySceneMapWriteOutcome::ProbeExhausted)
    }

    pub(crate) fn retire_cell(
        &mut self,
        cell: [i32; 3],
        frame_index: u32,
    ) -> SourceOnlySceneMapRetireOutcome {
        let hash_value = hash_scene_cell(cell);
        let key = compact_scene_cell_key(hash_value);
        let base_slot = hash_value as usize % self.slots.len();
        self.counters.free_space_retire_attempt_count = self
            .counters
            .free_space_retire_attempt_count
            .saturating_add(1);

        for probe in 0..SOURCE_ONLY_SCENE_PARTICLE_HASH_PROBE_COUNT {
            let slot_index = (base_slot + probe) % self.slots.len();
            let slot = self.slots[slot_index];
            if slot.key == 0 {
                return SourceOnlySceneMapRetireOutcome::Missing;
            }
            if slot.key == key {
                if slot.state != SourceOnlySceneCellState::Active {
                    return SourceOnlySceneMapRetireOutcome::NotActive;
                }
                if !slot.confirmed && self.policy.record_lifecycle_counters {
                    self.counters.surface_candidate_retired_cells = self
                        .counters
                        .surface_candidate_retired_cells
                        .saturating_add(1);
                }
                self.slots[slot_index] = SourceOnlySceneMapSlot {
                    state: SourceOnlySceneCellState::Retired,
                    confidence: 0.0,
                    last_frame: frame_index,
                    source_layer_mask: 0,
                    observation_count: 0,
                    confirmed: false,
                    ..slot
                };
                self.counters.free_space_retire_success_count = self
                    .counters
                    .free_space_retire_success_count
                    .saturating_add(1);
                return SourceOnlySceneMapRetireOutcome::Retired;
            }
        }

        SourceOnlySceneMapRetireOutcome::Missing
    }

    fn write_active_scene_cell(
        &mut self,
        slot_index: usize,
        cell: [i32; 3],
        key: u32,
        _probe: usize,
        observation: SourceOnlySceneObservation,
        observation_count: u32,
        was_confirmed: bool,
    ) {
        let source_layer_mask = observation.source_layer_mask & SCENE_META_SOURCE_LAYER_MASK;
        let confirmed = self.scene_lifecycle_confirmed(
            observation_count,
            source_layer_mask,
            observation.local_surface_supported,
        );
        self.slots[slot_index] = SourceOnlySceneMapSlot {
            key,
            state: SourceOnlySceneCellState::Active,
            cell,
            reference_space_position_m: observation.reference_space_position_m,
            depth_m: observation.depth_m,
            confidence: observation.confidence.clamp(0.0, 1.0),
            last_frame: observation.frame_index,
            source_layer_mask,
            observation_count,
            confirmed,
        };
        if self.policy.record_lifecycle_counters {
            if confirmed {
                self.counters.surface_confirmed_cells =
                    self.counters.surface_confirmed_cells.saturating_add(1);
            } else {
                self.counters.surface_candidate_cells =
                    self.counters.surface_candidate_cells.saturating_add(1);
            }
            if confirmed && !was_confirmed && observation_count > 1 {
                self.counters.surface_promoted_cells =
                    self.counters.surface_promoted_cells.saturating_add(1);
            }
        }
    }

    fn scene_lifecycle_confirmed(
        &self,
        observation_count: u32,
        source_layer_mask: u32,
        local_surface_supported: bool,
    ) -> bool {
        scene_layer_count(source_layer_mask) >= self.policy.min_source_layers
            && (observation_count >= self.policy.min_observations || local_surface_supported)
    }

    fn slot_is_stale(&self, slot_index: usize, frame_index: u32) -> bool {
        let slot = self.slots[slot_index];
        slot.key != 0
            && frame_index.saturating_sub(slot.last_frame)
                > SOURCE_ONLY_SCENE_PARTICLE_STALE_REPLACE_FRAMES
    }
}

pub(crate) fn scene_cell_for_reference_space_position(
    reference_space_position_m: [f32; 3],
) -> Option<[i32; 3]> {
    if !reference_space_position_m
        .iter()
        .all(|value| value.is_finite())
    {
        return None;
    }
    Some([
        floor_scene_cell_component(reference_space_position_m[0]),
        floor_scene_cell_component(reference_space_position_m[1]),
        floor_scene_cell_component(reference_space_position_m[2]),
    ])
}

pub(crate) fn hash_scene_cell(cell: [i32; 3]) -> u32 {
    let mut hash = (cell[0] as u32).wrapping_mul(73_856_093)
        ^ (cell[1] as u32).wrapping_mul(19_349_663)
        ^ (cell[2] as u32).wrapping_mul(83_492_791);
    hash ^= hash >> 16;
    hash = hash.wrapping_mul(0x7feb_352d);
    hash ^= hash >> 15;
    hash = hash.wrapping_mul(0x846c_a68b);
    hash ^= hash >> 16;
    hash
}

pub(crate) fn compact_scene_cell_key(hash_value: u32) -> u32 {
    (hash_value & 0x00ff_ffff).saturating_add(1)
}

fn floor_scene_cell_component(value_m: f32) -> i32 {
    (value_m / SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS)
        .floor()
        .clamp(i32::MIN as f32, i32::MAX as f32) as i32
}

fn scene_layer_count(source_layer_mask: u32) -> u32 {
    let masked = source_layer_mask & SCENE_META_SOURCE_LAYER_MASK;
    (masked & 0x1) + ((masked & 0x2) >> 1)
}

fn merged_observation(
    existing: SourceOnlySceneMapSlot,
    observation: SourceOnlySceneObservation,
) -> SourceOnlySceneObservation {
    let confidence = observation.confidence.clamp(0.0, 1.0);
    let merge_weight = SOURCE_ONLY_SCENE_PARTICLE_MERGE_WEIGHT * confidence;
    let reference_space_position_m = [
        mix(
            existing.reference_space_position_m[0],
            observation.reference_space_position_m[0],
            merge_weight,
        ),
        mix(
            existing.reference_space_position_m[1],
            observation.reference_space_position_m[1],
            merge_weight,
        ),
        mix(
            existing.reference_space_position_m[2],
            observation.reference_space_position_m[2],
            merge_weight,
        ),
    ];
    let depth_m = mix(existing.depth_m, observation.depth_m, merge_weight);
    let merged_confidence = (existing.confidence * 0.995)
        .max(mix(existing.confidence, confidence, 0.22) + confidence * 0.035)
        .clamp(0.0, 1.0);
    SourceOnlySceneObservation {
        reference_space_position_m,
        depth_m,
        confidence: merged_confidence,
        ..observation
    }
}

fn mix(a: f32, b: f32, t: f32) -> f32 {
    a * (1.0 - t) + b * t
}

#[cfg(test)]
mod tests {
    use super::{
        compact_scene_cell_key, hash_scene_cell, scene_cell_for_reference_space_position,
        SourceOnlySceneCellState, SourceOnlySceneMap, SourceOnlySceneMapPolicy,
        SourceOnlySceneMapRetireOutcome, SourceOnlySceneMapWriteOutcome,
        SourceOnlySceneObservation, SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
        SOURCE_ONLY_SCENE_PARTICLE_HASH_PROBE_COUNT,
        SOURCE_ONLY_SCENE_PARTICLE_STALE_REPLACE_FRAMES,
    };

    fn policy(capacity: usize) -> SourceOnlySceneMapPolicy {
        SourceOnlySceneMapPolicy {
            capacity,
            min_observations: 2,
            min_source_layers: 1,
            record_lifecycle_counters: true,
        }
    }

    fn observation(cell: [i32; 3], frame_index: u32) -> SourceOnlySceneObservation {
        observation_with_layer(cell, frame_index, 0x1)
    }

    fn observation_with_layer(
        cell: [i32; 3],
        frame_index: u32,
        source_layer_mask: u32,
    ) -> SourceOnlySceneObservation {
        SourceOnlySceneObservation {
            reference_space_position_m: [
                (cell[0] as f32 + 0.25) * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
                (cell[1] as f32 + 0.25) * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
                (cell[2] as f32 + 0.25) * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
            ],
            depth_m: 2.0,
            confidence: 0.8,
            source_layer_mask,
            frame_index,
            local_surface_supported: false,
        }
    }

    #[test]
    fn scene_cell_key_matches_shader_hash_shape() {
        let cell = [3, -2, -34];
        let hash = hash_scene_cell(cell);
        let key = compact_scene_cell_key(hash);

        assert_ne!(hash, 0);
        assert!(key > 0);
        assert!(key <= 0x0100_0000);
        assert_eq!(
            scene_cell_for_reference_space_position([
                3.25 * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
                -1.75 * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
                -33.75 * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
            ]),
            Some(cell)
        );
    }

    #[test]
    fn scene_map_policy_rejects_impossible_thresholds() {
        assert!(SourceOnlySceneMap::new(SourceOnlySceneMapPolicy {
            capacity: 0,
            ..policy(8)
        })
        .unwrap_err()
        .contains("capacity"));
        assert!(SourceOnlySceneMap::new(SourceOnlySceneMapPolicy {
            min_observations: 0,
            ..policy(8)
        })
        .unwrap_err()
        .contains("min_observations"));
        assert!(SourceOnlySceneMap::new(SourceOnlySceneMapPolicy {
            min_source_layers: 3,
            ..policy(8)
        })
        .unwrap_err()
        .contains("min_source_layers"));
    }

    #[test]
    fn same_scene_cell_observations_merge_and_promote() {
        let mut map = SourceOnlySceneMap::new(policy(8)).expect("scene map");
        let cell = [0, 0, -34];

        assert_eq!(
            map.write_observation(observation(cell, 10))
                .expect("insert"),
            SourceOnlySceneMapWriteOutcome::Inserted
        );
        assert_eq!(
            map.write_observation(SourceOnlySceneObservation {
                reference_space_position_m: [
                    0.5 * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
                    0.25 * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
                    (-34.0 + 0.25) * SOURCE_ONLY_SCENE_PARTICLE_CELL_METERS,
                ],
                depth_m: 2.1,
                confidence: 0.9,
                frame_index: 11,
                ..observation(cell, 11)
            })
            .expect("merge"),
            SourceOnlySceneMapWriteOutcome::Merged
        );

        let counters = map.counters();
        assert_eq!(counters.hash_insert_success_count, 1);
        assert_eq!(counters.hash_merge_count, 1);
        assert_eq!(counters.surface_candidate_cells, 1);
        assert_eq!(counters.surface_confirmed_cells, 1);
        assert_eq!(counters.surface_promoted_cells, 1);
        let slot = map.slot_for_cell(cell).expect("merged slot");
        assert_eq!(slot.state, SourceOnlySceneCellState::Active);
        assert_eq!(slot.observation_count, 2);
        assert!(slot.confirmed);
        assert!(slot.depth_m > 2.0 && slot.depth_m < 2.1);
    }

    #[test]
    fn same_scene_cell_two_source_layers_promote_when_required() {
        let mut map = SourceOnlySceneMap::new(SourceOnlySceneMapPolicy {
            min_source_layers: 2,
            ..policy(8)
        })
        .expect("scene map");
        let cell = [0, 0, -34];

        assert_eq!(
            map.write_observation(observation_with_layer(cell, 10, 0x1))
                .expect("insert layer0"),
            SourceOnlySceneMapWriteOutcome::Inserted
        );
        assert_eq!(
            map.write_observation(observation_with_layer(cell, 11, 0x2))
                .expect("merge layer1"),
            SourceOnlySceneMapWriteOutcome::Merged
        );

        let slot = map.slot_for_cell(cell).expect("two-layer slot");
        assert_eq!(slot.source_layer_mask, 0x3);
        assert_eq!(slot.observation_count, 2);
        assert!(slot.confirmed);
        let counters = map.counters();
        assert_eq!(counters.hash_insert_success_count, 1);
        assert_eq!(counters.hash_merge_count, 1);
        assert_eq!(counters.surface_candidate_cells, 1);
        assert_eq!(counters.surface_confirmed_cells, 1);
        assert_eq!(counters.surface_promoted_cells, 1);
    }

    #[test]
    fn offset_source_layers_in_neighbor_cells_stay_single_layer_candidates() {
        let mut map = SourceOnlySceneMap::new(SourceOnlySceneMapPolicy {
            min_source_layers: 2,
            ..policy(32)
        })
        .expect("scene map");
        let layer0_cell = [0, 0, -34];
        let layer1_offset_cell = [1, 0, -34];

        assert_eq!(
            map.write_observation(observation_with_layer(layer0_cell, 10, 0x1))
                .expect("insert layer0"),
            SourceOnlySceneMapWriteOutcome::Inserted
        );
        assert_eq!(
            map.write_observation(observation_with_layer(layer1_offset_cell, 11, 0x2))
                .expect("insert offset layer1"),
            SourceOnlySceneMapWriteOutcome::Inserted
        );

        let layer0_slot = map.slot_for_cell(layer0_cell).expect("layer0 slot");
        let layer1_slot = map
            .slot_for_cell(layer1_offset_cell)
            .expect("offset layer1 slot");
        assert_eq!(layer0_slot.source_layer_mask, 0x1);
        assert_eq!(layer1_slot.source_layer_mask, 0x2);
        assert!(!layer0_slot.confirmed);
        assert!(!layer1_slot.confirmed);
        let counters = map.counters();
        assert_eq!(counters.hash_insert_success_count, 2);
        assert_eq!(counters.hash_merge_count, 0);
        assert_eq!(counters.surface_candidate_cells, 2);
        assert_eq!(counters.surface_confirmed_cells, 0);
        assert_eq!(counters.surface_promoted_cells, 0);
    }

    #[test]
    fn active_collision_probe_exhausts_without_overwriting_fresh_slot() {
        let mut map = SourceOnlySceneMap::new(policy(1)).expect("scene map");
        let first_cell = [0, 0, -34];
        let second_cell = [1, 0, -34];
        assert_ne!(
            compact_scene_cell_key(hash_scene_cell(first_cell)),
            compact_scene_cell_key(hash_scene_cell(second_cell))
        );

        map.write_observation(observation(first_cell, 10))
            .expect("insert first");
        let outcome = map
            .write_observation(observation(second_cell, 11))
            .expect("fresh conflict");

        let counters = map.counters();
        assert_eq!(outcome, SourceOnlySceneMapWriteOutcome::ProbeExhausted);
        assert_eq!(counters.hash_probe_exhausted_count, 1);
        assert_eq!(
            counters.hash_write_conflict_count,
            SOURCE_ONLY_SCENE_PARTICLE_HASH_PROBE_COUNT as u32
        );
        assert!(map.slot_for_cell(first_cell).is_some());
        assert!(map.slot_for_cell(second_cell).is_none());
    }

    #[test]
    fn stale_nonmatching_slot_is_replaced_before_probe_exhaustion() {
        let mut map = SourceOnlySceneMap::new(policy(1)).expect("scene map");
        let first_cell = [0, 0, -34];
        let second_cell = [1, 0, -34];

        map.write_observation(observation(first_cell, 10))
            .expect("insert first");
        let outcome = map
            .write_observation(observation(
                second_cell,
                10 + SOURCE_ONLY_SCENE_PARTICLE_STALE_REPLACE_FRAMES + 1,
            ))
            .expect("stale replace");

        let counters = map.counters();
        assert_eq!(outcome, SourceOnlySceneMapWriteOutcome::StaleReplaced);
        assert_eq!(counters.hash_stale_replace_count, 1);
        assert_eq!(counters.hash_probe_exhausted_count, 0);
        assert!(map.slot_for_cell(first_cell).is_none());
        assert!(map.slot_for_cell(second_cell).is_some());
    }

    #[test]
    fn free_space_retire_marks_candidate_without_erasing_key() {
        let mut map = SourceOnlySceneMap::new(SourceOnlySceneMapPolicy {
            min_observations: 4,
            ..policy(8)
        })
        .expect("scene map");
        let cell = [0, 0, -34];

        map.write_observation(observation(cell, 10))
            .expect("insert candidate");
        assert_eq!(
            map.retire_cell(cell, 12),
            SourceOnlySceneMapRetireOutcome::Retired
        );

        let counters = map.counters();
        assert_eq!(counters.free_space_retire_attempt_count, 1);
        assert_eq!(counters.free_space_retire_success_count, 1);
        assert_eq!(counters.surface_candidate_retired_cells, 1);
        let slot = map
            .slot_for_cell(cell)
            .expect("retired key remains addressable");
        assert_eq!(slot.state, SourceOnlySceneCellState::Retired);
        assert!(!slot.confirmed);
    }
}
