const PERIODIC_RECEIPT_PRESENT_INTERVAL: u64 = 300;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraProjectionLayerState {
    Absent,
    RawSceneQuadActive,
}

impl CameraProjectionLayerState {
    fn marker_token(self) -> &'static str {
        match self {
            Self::Absent => "absent",
            Self::RawSceneQuadActive => "raw-scene-quad-active",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct CameraProjectionLaunchFence {
    pub(crate) launch_challenge: u64,
    pub(crate) layer_generation: u64,
    pub(crate) layer_switch_count: u64,
    pub(crate) layer_state: CameraProjectionLayerState,
}

impl CameraProjectionLaunchFence {
    pub(crate) fn observed_from_raw(
        launch_challenge: i64,
        layer_generation: i64,
        layer_switch_count: i64,
        layer_state_code: i32,
    ) -> Result<Self, &'static str> {
        let launch_challenge =
            u64::try_from(launch_challenge).map_err(|_| "launch-challenge-invalid")?;
        let layer_generation =
            u64::try_from(layer_generation).map_err(|_| "layer-generation-invalid")?;
        let layer_switch_count =
            u64::try_from(layer_switch_count).map_err(|_| "layer-switch-count-invalid")?;
        if launch_challenge == 0 {
            return Err("launch-challenge-missing");
        }
        if layer_generation == 0 {
            return Err("layer-generation-missing");
        }
        let layer_state = match layer_state_code {
            0 if layer_generation == layer_switch_count => CameraProjectionLayerState::Absent,
            1 if layer_generation == layer_switch_count.saturating_add(1) => {
                CameraProjectionLayerState::RawSceneQuadActive
            }
            0 | 1 => return Err("layer-generation-switch-invariant-invalid"),
            _ => return Err("raw-projection-layer-state-invalid"),
        };
        Ok(Self {
            launch_challenge,
            layer_generation,
            layer_switch_count,
            layer_state,
        })
    }

    pub(crate) fn from_raw(
        launch_challenge: i64,
        layer_generation: i64,
        layer_switch_count: i64,
        layer_state_code: i32,
    ) -> Result<Self, &'static str> {
        let launch_challenge =
            u64::try_from(launch_challenge).map_err(|_| "launch-challenge-invalid")?;
        let layer_generation =
            u64::try_from(layer_generation).map_err(|_| "layer-generation-invalid")?;
        let layer_switch_count =
            u64::try_from(layer_switch_count).map_err(|_| "layer-switch-count-invalid")?;
        if launch_challenge == 0 {
            return Err("launch-challenge-missing");
        }
        if layer_generation == 0 {
            return Err("layer-generation-missing");
        }
        if layer_generation != 1 {
            return Err("initial-layer-generation-invalid");
        }
        if layer_switch_count != 0 {
            return Err("app-owned-layer-switch-observed");
        }
        if layer_state_code != 1 {
            return Err("raw-projection-layer-not-active");
        }
        Ok(Self {
            launch_challenge,
            layer_generation,
            layer_switch_count,
            layer_state: CameraProjectionLayerState::RawSceneQuadActive,
        })
    }

    pub(crate) fn validate_monotonic_update(
        previous: Option<Self>,
        next: Self,
    ) -> Result<(), &'static str> {
        let Some(previous) = previous else {
            return if next.layer_state == CameraProjectionLayerState::RawSceneQuadActive
                && next.layer_generation == 1
                && next.layer_switch_count == 0
            {
                Ok(())
            } else {
                Err("initial-live-layer-fence-not-active")
            };
        };
        if previous == next {
            return Ok(());
        }
        match (previous.layer_state, next.layer_state) {
            (
                CameraProjectionLayerState::RawSceneQuadActive,
                CameraProjectionLayerState::Absent,
            ) if next.launch_challenge == previous.launch_challenge
                && next.layer_generation == previous.layer_generation
                && next.layer_switch_count == previous.layer_switch_count.saturating_add(1) =>
            {
                Ok(())
            }
            (
                CameraProjectionLayerState::Absent,
                CameraProjectionLayerState::RawSceneQuadActive,
            ) if next.launch_challenge != previous.launch_challenge
                && next.layer_generation == 1
                && next.layer_switch_count == 0 =>
            {
                Ok(())
            }
            _ => Err("live-layer-fence-transition-not-monotonic"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraProjectionCadenceAuthority {
    VulkanWsiPresentReturned,
}

impl CameraProjectionCadenceAuthority {
    fn marker_token(self) -> &'static str {
        match self {
            Self::VulkanWsiPresentReturned => "vulkan-wsi-queue-present-returned",
        }
    }

    fn session_authority(self) -> &'static str {
        match self {
            Self::VulkanWsiPresentReturned => "app-vulkan-wsi-run",
        }
    }

    fn present_authority(self) -> &'static str {
        match self {
            Self::VulkanWsiPresentReturned => "vulkan-wsi-queue-present-returned",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct CameraProjectionFreshnessSample {
    pub(crate) launch_challenge: u64,
    pub(crate) layer_generation: u64,
    pub(crate) layer_switch_count: u64,
    pub(crate) layer_state: CameraProjectionLayerState,
    pub(crate) run_generation: u64,
    pub(crate) session_generation: u64,
    pub(crate) cadence_authority: CameraProjectionCadenceAuthority,
    pub(crate) cadence_available: bool,
    pub(crate) cadence_ordinal: u64,
    pub(crate) present_ordinal: u64,
    pub(crate) raw_projection_selected: bool,
    pub(crate) camera_projection_visible: bool,
    pub(crate) left_frame_index: u64,
    pub(crate) right_frame_index: u64,
    pub(crate) left_timestamp_ns: i64,
    pub(crate) right_timestamp_ns: i64,
    pub(crate) left_hwb_import_sequence: u64,
    pub(crate) right_hwb_import_sequence: u64,
    pub(crate) left_hardware_buffer_id: u64,
    pub(crate) right_hardware_buffer_id: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct CameraProjectionFreshnessReceipt {
    previous: CameraProjectionFreshnessSample,
    current: CameraProjectionFreshnessSample,
}

impl CameraProjectionFreshnessReceipt {
    pub(crate) fn marker_fields(self) -> String {
        format!(
            "schema=rusty.quest.camera_hwb_projection_freshness_receipt.v1 launchChallenge={} layerGeneration={} layerSwitchCount={} layerState={} launchFenceAuthority=app-raw-carrier-live-jni-fence runGeneration={} runGenerationAuthority=camera-import-stream-generation sessionGeneration={} sessionGenerationAuthority={} cadenceAuthority={} cadenceAvailable=true previousCadenceOrdinal={} currentCadenceOrdinal={} presentOrdinalAuthority={} previousPresentOrdinal={} currentPresentOrdinal={} previousLeftFrameIndex={} currentLeftFrameIndex={} previousRightFrameIndex={} currentRightFrameIndex={} previousLeftTimestampNs={} currentLeftTimestampNs={} previousRightTimestampNs={} currentRightTimestampNs={} previousLeftHwbImportSequence={} currentLeftHwbImportSequence={} previousRightHwbImportSequence={} currentRightHwbImportSequence={} currentLeftHardwareBufferId={} currentRightHardwareBufferId={} rawProjectionSelected=true continuousRawProjection=true cameraProjectionVisible=true cameraProjectionMovingWitness=true movingWitnessAuthority=app-owned-command-buffer-camera-draw visibilityScope=app-command-buffer-not-wearer-visible intervalPolicy=first-moving-then-periodic-300-present-ordinals",
            self.current.launch_challenge,
            self.current.layer_generation,
            self.current.layer_switch_count,
            self.current.layer_state.marker_token(),
            self.current.run_generation,
            self.current.session_generation,
            self.current.cadence_authority.session_authority(),
            self.current.cadence_authority.marker_token(),
            self.previous.cadence_ordinal,
            self.current.cadence_ordinal,
            self.current.cadence_authority.present_authority(),
            self.previous.present_ordinal,
            self.current.present_ordinal,
            self.previous.left_frame_index,
            self.current.left_frame_index,
            self.previous.right_frame_index,
            self.current.right_frame_index,
            self.previous.left_timestamp_ns,
            self.current.left_timestamp_ns,
            self.previous.right_timestamp_ns,
            self.current.right_timestamp_ns,
            self.previous.left_hwb_import_sequence,
            self.current.left_hwb_import_sequence,
            self.previous.right_hwb_import_sequence,
            self.current.right_hwb_import_sequence,
            self.current.left_hardware_buffer_id,
            self.current.right_hardware_buffer_id,
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraProjectionFreshnessObservation {
    Primed,
    Pending,
    Issued(CameraProjectionFreshnessReceipt),
    Rejected(&'static str),
}

pub(crate) struct CameraProjectionFreshnessTracker {
    expected_launch_fence: CameraProjectionLaunchFence,
    expected_run_generation: u64,
    expected_session_generation: u64,
    expected_cadence_authority: CameraProjectionCadenceAuthority,
    receipt_anchor: Option<CameraProjectionFreshnessSample>,
    last_observation: Option<CameraProjectionFreshnessSample>,
    last_receipt_present_ordinal: Option<u64>,
    rejected: bool,
}

impl CameraProjectionFreshnessTracker {
    pub(crate) fn new(
        expected_launch_fence: CameraProjectionLaunchFence,
        expected_run_generation: u64,
        expected_session_generation: u64,
        expected_cadence_authority: CameraProjectionCadenceAuthority,
    ) -> Self {
        Self {
            expected_launch_fence,
            expected_run_generation,
            expected_session_generation,
            expected_cadence_authority,
            receipt_anchor: None,
            last_observation: None,
            last_receipt_present_ordinal: None,
            rejected: false,
        }
    }

    pub(crate) fn observe(
        &mut self,
        sample: CameraProjectionFreshnessSample,
    ) -> CameraProjectionFreshnessObservation {
        if self.rejected {
            return CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected",
            );
        }
        if let Err(reason) = self.validate_sample_identity(sample) {
            return self.reject(reason);
        }
        let Some(previous) = self.receipt_anchor else {
            self.receipt_anchor = Some(sample);
            self.last_observation = Some(sample);
            return CameraProjectionFreshnessObservation::Primed;
        };
        if let Some(last_observation) = self.last_observation {
            if let Err(reason) = validate_moving_transition(last_observation, sample) {
                return self.reject(reason);
            }
        }
        self.last_observation = Some(sample);
        if self.last_receipt_present_ordinal.is_some_and(|ordinal| {
            sample.present_ordinal < ordinal.saturating_add(PERIODIC_RECEIPT_PRESENT_INTERVAL)
        }) {
            return CameraProjectionFreshnessObservation::Pending;
        }
        self.receipt_anchor = Some(sample);
        self.last_receipt_present_ordinal = Some(sample.present_ordinal);
        CameraProjectionFreshnessObservation::Issued(CameraProjectionFreshnessReceipt {
            previous,
            current: sample,
        })
    }

    pub(crate) fn observe_live_fence(
        &mut self,
        observed: Result<CameraProjectionLaunchFence, &'static str>,
    ) -> CameraProjectionFreshnessObservation {
        if self.rejected {
            return CameraProjectionFreshnessObservation::Rejected(
                "freshness-launch-already-rejected",
            );
        }
        match observed {
            Ok(fence) if fence == self.expected_launch_fence => {
                CameraProjectionFreshnessObservation::Pending
            }
            Ok(_) => self.reject("live-layer-fence-mismatch"),
            Err(reason) => self.reject(reason),
        }
    }

    pub(crate) fn observe_with_live_fence(
        &mut self,
        sample: CameraProjectionFreshnessSample,
        observed: Result<CameraProjectionLaunchFence, &'static str>,
    ) -> CameraProjectionFreshnessObservation {
        match self.observe_live_fence(observed) {
            CameraProjectionFreshnessObservation::Pending => self.observe(sample),
            outcome => outcome,
        }
    }

    fn reject(&mut self, reason: &'static str) -> CameraProjectionFreshnessObservation {
        self.rejected = true;
        CameraProjectionFreshnessObservation::Rejected(reason)
    }

    fn validate_sample_identity(
        &self,
        sample: CameraProjectionFreshnessSample,
    ) -> Result<(), &'static str> {
        if sample.launch_challenge != self.expected_launch_fence.launch_challenge {
            return Err("launch-challenge-mismatch");
        }
        if sample.layer_generation != self.expected_launch_fence.layer_generation {
            return Err("layer-generation-mismatch");
        }
        if sample.layer_switch_count != self.expected_launch_fence.layer_switch_count
            || sample.layer_switch_count != 0
        {
            return Err("app-owned-layer-switch-observed");
        }
        if sample.layer_state != self.expected_launch_fence.layer_state
            || sample.layer_state != CameraProjectionLayerState::RawSceneQuadActive
        {
            return Err("raw-projection-layer-not-active");
        }
        if sample.run_generation == 0 || sample.run_generation != self.expected_run_generation {
            return Err("stale-run-generation");
        }
        if sample.session_generation == 0
            || sample.session_generation != self.expected_session_generation
        {
            return Err("stale-session-generation");
        }
        if sample.cadence_authority != self.expected_cadence_authority {
            return Err("cadence-authority-drift");
        }
        if !sample.cadence_available {
            return Err("cadence-missing");
        }
        if sample.cadence_ordinal == 0 {
            return Err("cadence-ordinal-missing");
        }
        if !sample.raw_projection_selected {
            return Err("raw-projection-not-selected");
        }
        if !sample.camera_projection_visible {
            return Err("camera-projection-visible-witness-missing");
        }
        if sample.present_ordinal == 0 {
            return Err("present-ordinal-missing");
        }
        if sample.left_timestamp_ns <= 0 || sample.right_timestamp_ns <= 0 {
            return Err("camera-timestamp-missing");
        }
        Ok(())
    }
}

fn validate_moving_transition(
    previous: CameraProjectionFreshnessSample,
    current: CameraProjectionFreshnessSample,
) -> Result<(), &'static str> {
    if current.present_ordinal <= previous.present_ordinal {
        return Err("present-ordinal-not-monotonic");
    }
    if current.cadence_ordinal <= previous.cadence_ordinal {
        return Err("cadence-ordinal-not-monotonic");
    }
    if current.left_frame_index <= previous.left_frame_index
        || current.right_frame_index <= previous.right_frame_index
    {
        return Err("camera-frame-index-not-monotonic");
    }
    if current.left_timestamp_ns <= previous.left_timestamp_ns
        || current.right_timestamp_ns <= previous.right_timestamp_ns
    {
        return Err("camera-timestamp-not-monotonic");
    }
    if current.left_hwb_import_sequence <= previous.left_hwb_import_sequence
        || current.right_hwb_import_sequence <= previous.right_hwb_import_sequence
    {
        return Err("camera-hwb-import-sequence-not-monotonic");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(present_ordinal: u64) -> CameraProjectionFreshnessSample {
        CameraProjectionFreshnessSample {
            launch_challenge: 701,
            layer_generation: 1,
            layer_switch_count: 0,
            layer_state: CameraProjectionLayerState::RawSceneQuadActive,
            run_generation: 17,
            session_generation: 31,
            cadence_authority: CameraProjectionCadenceAuthority::VulkanWsiPresentReturned,
            cadence_available: true,
            cadence_ordinal: present_ordinal,
            present_ordinal,
            raw_projection_selected: true,
            camera_projection_visible: true,
            left_frame_index: 100 + present_ordinal,
            right_frame_index: 200 + present_ordinal,
            left_timestamp_ns: 1_000_000 + present_ordinal as i64,
            right_timestamp_ns: 2_000_000 + present_ordinal as i64,
            left_hwb_import_sequence: 300 + present_ordinal,
            right_hwb_import_sequence: 400 + present_ordinal,
            left_hardware_buffer_id: 500 + present_ordinal,
            right_hardware_buffer_id: 600 + present_ordinal,
        }
    }

    fn tracker() -> CameraProjectionFreshnessTracker {
        CameraProjectionFreshnessTracker::new(
            CameraProjectionLaunchFence::from_raw(701, 1, 0, 1).expect("valid launch fence"),
            17,
            31,
            CameraProjectionCadenceAuthority::VulkanWsiPresentReturned,
        )
    }

    fn assert_rejected(outcome: CameraProjectionFreshnessObservation, expected: &'static str) {
        assert_eq!(
            outcome,
            CameraProjectionFreshnessObservation::Rejected(expected)
        );
    }

    #[test]
    fn moving_raw_projection_issues_first_and_periodic_receipts() {
        let mut tracker = tracker();
        assert_eq!(
            tracker.observe(sample(1)),
            CameraProjectionFreshnessObservation::Primed
        );
        let CameraProjectionFreshnessObservation::Issued(first) = tracker.observe(sample(2)) else {
            panic!("first moving evidence did not issue");
        };
        assert_eq!(
            tracker.observe(sample(301)),
            CameraProjectionFreshnessObservation::Pending
        );
        let CameraProjectionFreshnessObservation::Issued(periodic) = tracker.observe(sample(302))
        else {
            panic!("periodic moving evidence did not issue");
        };
        let marker = first.marker_fields();
        assert!(marker.contains("cameraProjectionVisible=true"));
        assert!(marker.contains("cameraProjectionMovingWitness=true"));
        assert!(marker.contains("continuousRawProjection=true"));
        assert!(marker.contains("visibilityScope=app-command-buffer-not-wearer-visible"));
        assert!(!marker.contains("screenshot"));
        assert!(!marker.contains("screen-recording"));
        assert_eq!(periodic.previous.present_ordinal, 2);
        assert_eq!(periodic.current.present_ordinal, 302);
    }

    #[test]
    fn stale_run_or_session_fails_closed() {
        let mut run_tracker = tracker();
        let mut stale_run = sample(1);
        stale_run.run_generation = 16;
        assert_rejected(run_tracker.observe(stale_run), "stale-run-generation");

        let mut session_tracker = tracker();
        let mut stale_session = sample(1);
        stale_session.session_generation = 30;
        assert_rejected(
            session_tracker.observe(stale_session),
            "stale-session-generation",
        );
    }

    #[test]
    fn stale_launch_challenge_or_layer_fence_fails_closed() {
        let mut challenge_tracker = tracker();
        let mut stale_challenge = sample(1);
        stale_challenge.launch_challenge = 700;
        assert_rejected(
            challenge_tracker.observe(stale_challenge),
            "launch-challenge-mismatch",
        );

        let mut generation_tracker = tracker();
        let mut wrong_generation = sample(1);
        wrong_generation.layer_generation = 2;
        assert_rejected(
            generation_tracker.observe(wrong_generation),
            "layer-generation-mismatch",
        );

        let mut state_tracker = tracker();
        let mut switched = sample(1);
        switched.layer_switch_count = 1;
        assert_rejected(
            state_tracker.observe(switched),
            "app-owned-layer-switch-observed",
        );

        assert_eq!(
            CameraProjectionLaunchFence::from_raw(701, 1, 1, 1),
            Err("app-owned-layer-switch-observed")
        );
        assert_eq!(
            CameraProjectionLaunchFence::from_raw(701, 1, 0, 0),
            Err("raw-projection-layer-not-active")
        );
        assert_eq!(
            CameraProjectionLaunchFence::from_raw(701, 2, 0, 1),
            Err("initial-layer-generation-invalid")
        );
        assert_eq!(
            CameraProjectionLaunchFence::observed_from_raw(701, 1, 1, 0),
            Ok(CameraProjectionLaunchFence {
                launch_challenge: 701,
                layer_generation: 1,
                layer_switch_count: 1,
                layer_state: CameraProjectionLayerState::Absent,
            })
        );
        assert_eq!(
            CameraProjectionLaunchFence::observed_from_raw(701, 2, 1, 1),
            Ok(CameraProjectionLaunchFence {
                launch_challenge: 701,
                layer_generation: 2,
                layer_switch_count: 1,
                layer_state: CameraProjectionLayerState::RawSceneQuadActive,
            })
        );
        assert_eq!(
            CameraProjectionLaunchFence::observed_from_raw(701, 1, 1, 1),
            Err("layer-generation-switch-invariant-invalid")
        );

        let active = CameraProjectionLaunchFence::observed_from_raw(701, 1, 0, 1).unwrap();
        let absent = CameraProjectionLaunchFence::observed_from_raw(701, 1, 1, 0).unwrap();
        let replacement = CameraProjectionLaunchFence::observed_from_raw(702, 1, 0, 1).unwrap();
        assert_eq!(
            CameraProjectionLaunchFence::validate_monotonic_update(None, active),
            Ok(())
        );
        assert_eq!(
            CameraProjectionLaunchFence::validate_monotonic_update(Some(active), absent),
            Ok(())
        );
        assert_eq!(
            CameraProjectionLaunchFence::validate_monotonic_update(Some(absent), replacement),
            Ok(())
        );
        assert_eq!(
            CameraProjectionLaunchFence::validate_monotonic_update(Some(replacement), active),
            Err("live-layer-fence-transition-not-monotonic")
        );
        assert_eq!(
            CameraProjectionLaunchFence::validate_monotonic_update(Some(absent), active),
            Err("live-layer-fence-transition-not-monotonic")
        );
        assert_eq!(
            CameraProjectionLaunchFence::validate_monotonic_update(Some(active), replacement),
            Err("live-layer-fence-transition-not-monotonic")
        );
    }

    #[test]
    fn layer_switch_missing_cadence_or_witness_fails_closed() {
        let mut layer_tracker = tracker();
        let mut switched = sample(1);
        switched.layer_switch_count = 1;
        assert_rejected(
            layer_tracker.observe(switched),
            "app-owned-layer-switch-observed",
        );

        let mut cadence_tracker = tracker();
        let mut no_cadence = sample(1);
        no_cadence.cadence_available = false;
        assert_rejected(cadence_tracker.observe(no_cadence), "cadence-missing");

        let mut cadence_ordinal_tracker = tracker();
        let mut no_cadence_ordinal = sample(1);
        no_cadence_ordinal.cadence_ordinal = 0;
        assert_rejected(
            cadence_ordinal_tracker.observe(no_cadence_ordinal),
            "cadence-ordinal-missing",
        );

        let mut witness_tracker = tracker();
        let mut no_witness = sample(1);
        no_witness.camera_projection_visible = false;
        assert_rejected(
            witness_tracker.observe(no_witness),
            "camera-projection-visible-witness-missing",
        );

        let mut raw_tracker = tracker();
        let mut not_raw = sample(1);
        not_raw.raw_projection_selected = false;
        assert_rejected(raw_tracker.observe(not_raw), "raw-projection-not-selected");
    }

    #[test]
    fn nonmonotonic_motion_fields_fail_closed() {
        let damage = [
            ("present", "present-ordinal-not-monotonic"),
            ("cadence", "cadence-ordinal-not-monotonic"),
            ("frame", "camera-frame-index-not-monotonic"),
            ("timestamp", "camera-timestamp-not-monotonic"),
            ("import", "camera-hwb-import-sequence-not-monotonic"),
        ];
        for (field, expected) in damage {
            let mut tracker = tracker();
            assert_eq!(
                tracker.observe(sample(1)),
                CameraProjectionFreshnessObservation::Primed
            );
            let mut damaged = sample(2);
            match field {
                "present" => damaged.present_ordinal = 1,
                "cadence" => damaged.cadence_ordinal = sample(1).cadence_ordinal,
                "frame" => damaged.left_frame_index = sample(1).left_frame_index,
                "timestamp" => damaged.right_timestamp_ns = sample(1).right_timestamp_ns,
                "import" => damaged.left_hwb_import_sequence = sample(1).left_hwb_import_sequence,
                _ => unreachable!(),
            }
            assert_rejected(tracker.observe(damaged), expected);
        }
    }

    #[test]
    fn nonmonotonic_intermediate_sample_cannot_hide_between_periodic_receipts() {
        let mut tracker = tracker();
        assert_eq!(
            tracker.observe(sample(1)),
            CameraProjectionFreshnessObservation::Primed
        );
        assert!(matches!(
            tracker.observe(sample(2)),
            CameraProjectionFreshnessObservation::Issued(_)
        ));
        assert_eq!(
            tracker.observe(sample(100)),
            CameraProjectionFreshnessObservation::Pending
        );
        let mut rollback = sample(101);
        rollback.left_frame_index = sample(100).left_frame_index;
        assert_rejected(
            tracker.observe(rollback),
            "camera-frame-index-not-monotonic",
        );
        assert_rejected(
            tracker.observe(sample(302)),
            "freshness-launch-already-rejected",
        );
        assert_rejected(
            tracker.observe(sample(602)),
            "freshness-launch-already-rejected",
        );
    }

    #[test]
    fn live_layer_removal_between_wsi_observations_latches_rejection() {
        let mut removal_tracker = tracker();
        let active = CameraProjectionLaunchFence::from_raw(701, 1, 0, 1).unwrap();
        let removed = CameraProjectionLaunchFence::observed_from_raw(701, 1, 1, 0).unwrap();
        assert_eq!(
            removal_tracker.observe_live_fence(Ok(active)),
            CameraProjectionFreshnessObservation::Pending
        );
        assert_rejected(
            removal_tracker.observe_with_live_fence(sample(1), Ok(removed)),
            "live-layer-fence-mismatch",
        );
        assert_rejected(
            removal_tracker.observe_with_live_fence(sample(302), Ok(active)),
            "freshness-launch-already-rejected",
        );

        let mut unavailable = tracker();
        assert_rejected(
            unavailable.observe_live_fence(Err("live-layer-fence-unavailable")),
            "live-layer-fence-unavailable",
        );
        assert_rejected(
            unavailable.observe_with_live_fence(sample(302), Ok(active)),
            "freshness-launch-already-rejected",
        );
    }
}
