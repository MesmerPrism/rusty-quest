//! OpenXR action boundary for native renderer controller controls.

use openxr as xr;

use crate::{
    manifold_pose_publisher::{
        ManifoldPosePublisher, ManifoldPosePublisherConfig, ManifoldPoseSample,
        DEFAULT_MANIFOLD_POSE_CONTROLLER, DEFAULT_MANIFOLD_POSE_KIND,
        DEFAULT_MANIFOLD_POSE_SAMPLE_HZ, DEFAULT_MANIFOLD_POSE_SOURCE,
        DEFAULT_MANIFOLD_POSE_STREAM,
    },
    native_renderer_options::NativeStimulusVolumeSettings,
    projection_target_state::{ProjectionTargetInput, ProjectionTargetSettings},
};

const RIGHT_HAND_HAPTIC_OUTPUT_PATH: &str = "/user/hand/right/output/haptic";
const RIGHT_HAND_SUBACTION_PATH: &str = "/user/hand/right";
const BREATH_HAPTIC_PULSE_PERIOD_SECONDS: f32 = 0.75;
const BREATH_HAPTIC_PULSE_DURATION_MS: i64 = 100;
const BREATH_HAPTIC_PULSE_DURATION_NANOS: i64 = BREATH_HAPTIC_PULSE_DURATION_MS * 1_000_000;
const BREATH_HAPTIC_AMPLITUDE: f32 = 0.45;

pub(crate) struct StimulusVolumeActions {
    action_set: xr::ActionSet,
    right_primary_randomize: xr::Action<bool>,
    right_primary_reset: xr::Action<bool>,
    right_secondary_scale_driver_toggle: xr::Action<bool>,
    right_thumbstick_y: xr::Action<f32>,
    right_grip_pose: xr::Action<xr::Posef>,
    right_breath_haptic: xr::Action<xr::Haptic>,
    right_hand_subaction_path: xr::Path,
    right_grip_space: Option<xr::Space>,
    breath_haptic_cadence: BreathHapticCadence,
    breath_haptic_pulse_count: u64,
    breath_haptic_error_count: u64,
    manifold_pose_publisher: Option<ManifoldPosePublisher>,
    manifold_pose_config: ManifoldPosePublisherConfig,
    manifold_pose_seconds_since_publish: f64,
    manifold_pose_sequence_id: u64,
    manifold_pose_published_count: u64,
    manifold_pose_dropped_count: u64,
    previous_right_primary_randomize_pressed: bool,
    previous_right_primary_reset_pressed: bool,
    previous_right_secondary_scale_driver_toggle_pressed: bool,
    suggested_binding_count: usize,
    stimulus_settings: NativeStimulusVolumeSettings,
    projection_target_settings: ProjectionTargetSettings,
}

#[derive(Debug, Default)]
pub(crate) struct NativeRendererControllerEvents {
    pub(crate) stimulus_randomize_triggered: bool,
    pub(crate) projection_target_inputs: Vec<ProjectionTargetInput>,
    pub(crate) right_grip_pose_active: bool,
    pub(crate) right_grip_pose_tracked: bool,
}

#[derive(Debug, Clone)]
struct BreathHapticCadence {
    seconds_since_pulse: f32,
}

impl BreathHapticCadence {
    fn new_ready() -> Self {
        Self {
            seconds_since_pulse: BREATH_HAPTIC_PULSE_PERIOD_SECONDS,
        }
    }

    fn update(&mut self, dt_seconds: f32, enabled: bool, tracked: bool) -> bool {
        if !enabled || !tracked {
            self.seconds_since_pulse = BREATH_HAPTIC_PULSE_PERIOD_SECONDS;
            return false;
        }
        if dt_seconds.is_finite() && dt_seconds > 0.0 {
            self.seconds_since_pulse += dt_seconds.min(1.0);
        }
        if self.seconds_since_pulse < BREATH_HAPTIC_PULSE_PERIOD_SECONDS {
            return false;
        }
        self.seconds_since_pulse = (self.seconds_since_pulse - BREATH_HAPTIC_PULSE_PERIOD_SECONDS)
            .clamp(0.0, BREATH_HAPTIC_PULSE_PERIOD_SECONDS);
        true
    }
}

impl StimulusVolumeActions {
    pub(crate) fn new(
        instance: &xr::Instance,
        stimulus_settings: NativeStimulusVolumeSettings,
        projection_target_settings: ProjectionTargetSettings,
    ) -> Result<Option<Self>, String> {
        if !stimulus_settings.enabled && !projection_target_settings.controls_enabled {
            crate::marker(
                "stimulus-volume-input",
                "status=disabled reason=native-controller-controls-disabled actionSetAttached=false rightPrimaryRandomizeAction=false",
            );
            crate::marker(
                "projection-target-input",
                "status=disabled reason=projection-target-controls-disabled actionSetAttached=false rightThumbstickYAction=false rightPrimaryResetAction=false rightSecondaryScaleDriverToggleAction=false rightGripPoseAction=false",
            );
            return Ok(None);
        }

        let action_set = instance
            .create_action_set("stimulus_volume", "Native Renderer Controls", 20)
            .map_err(|error| format!("create native renderer OpenXR action set: {error}"))?;
        let right_hand_subaction_path = instance
            .string_to_path(RIGHT_HAND_SUBACTION_PATH)
            .map_err(|error| {
                format!("create OpenXR path for right hand haptic subaction: {error}")
            })?;
        let right_primary_randomize = action_set
            .create_action::<bool>("right_primary_randomize", "Right Primary Randomize", &[])
            .map_err(|error| format!("create stimulus randomize action: {error}"))?;
        let right_primary_reset = action_set
            .create_action::<bool>("right_primary_reset", "Right Primary Reset", &[])
            .map_err(|error| format!("create projection target reset action: {error}"))?;
        let right_secondary_scale_driver_toggle = action_set
            .create_action::<bool>(
                "right_secondary_scale_driver_toggle",
                "Right Secondary Scale Driver Toggle",
                &[],
            )
            .map_err(|error| {
                format!("create projection target scale driver toggle action: {error}")
            })?;
        let right_thumbstick_y = action_set
            .create_action::<f32>("right_thumbstick_y", "Right Thumbstick Y", &[])
            .map_err(|error| format!("create projection target thumbstick action: {error}"))?;
        let right_grip_pose = action_set
            .create_action::<xr::Posef>("right_grip_pose", "Right Grip Pose", &[])
            .map_err(|error| format!("create right grip pose action: {error}"))?;
        let right_breath_haptic = action_set
            .create_action::<xr::Haptic>(
                "right_breath_haptic",
                "Right Breath Haptic",
                &[right_hand_subaction_path],
            )
            .map_err(|error| format!("create right breath haptic action: {error}"))?;

        let stimulus_randomize_binding_enabled =
            stimulus_settings.enabled && stimulus_settings.randomize_enabled;
        let projection_controls_enabled = projection_target_settings.controls_enabled;
        let projection_joystick_binding_enabled =
            projection_controls_enabled && projection_target_settings.joystick_controls_enabled;
        let breath_haptics_configured = projection_controls_enabled
            && projection_target_settings
                .breath_bridge_mode
                .uses_breath_stream();
        let right_grip_pose_binding_enabled = breath_haptics_configured;
        let manifold_pose_config =
            manifold_pose_config_from_projection_settings(&projection_target_settings);

        let mut suggested_binding_count = 0_usize;
        for profile in INTERACTION_PROFILES {
            let profile_path = instance
                .string_to_path(profile.profile_path)
                .map_err(|error| {
                    format!(
                        "create OpenXR path for controller profile {}: {error}",
                        profile.profile_path
                    )
                })?;
            let mut bindings = Vec::new();
            if let Some(input_path) = profile.right_primary_path {
                let input = instance.string_to_path(input_path).map_err(|error| {
                    format!("create OpenXR path for primary input {input_path}: {error}")
                })?;
                if stimulus_randomize_binding_enabled {
                    bindings.push(xr::Binding::new(&right_primary_randomize, input));
                }
                if projection_controls_enabled {
                    bindings.push(xr::Binding::new(&right_primary_reset, input));
                }
            }
            if let Some(input_path) = profile.right_secondary_path {
                let input = instance.string_to_path(input_path).map_err(|error| {
                    format!("create OpenXR path for secondary input {input_path}: {error}")
                })?;
                if projection_controls_enabled {
                    bindings.push(xr::Binding::new(
                        &right_secondary_scale_driver_toggle,
                        input,
                    ));
                }
            }
            if let Some(input_path) = profile.right_thumbstick_y_path {
                let input = instance.string_to_path(input_path).map_err(|error| {
                    format!("create OpenXR path for thumbstick Y input {input_path}: {error}")
                })?;
                if projection_joystick_binding_enabled {
                    bindings.push(xr::Binding::new(&right_thumbstick_y, input));
                }
            }
            if let Some(input_path) = profile.right_grip_pose_path {
                let input = instance.string_to_path(input_path).map_err(|error| {
                    format!("create OpenXR path for right grip pose input {input_path}: {error}")
                })?;
                if right_grip_pose_binding_enabled {
                    bindings.push(xr::Binding::new(&right_grip_pose, input));
                }
            }
            if let Some(output_path) = profile.right_haptic_output_path {
                let output = instance.string_to_path(output_path).map_err(|error| {
                    format!("create OpenXR path for right haptic output {output_path}: {error}")
                })?;
                if breath_haptics_configured {
                    bindings.push(xr::Binding::new(&right_breath_haptic, output));
                }
            }
            match instance.suggest_interaction_profile_bindings(profile_path, &bindings) {
                Ok(()) => {
                    suggested_binding_count = suggested_binding_count.saturating_add(bindings.len());
                    crate::marker(
                        "projection-target-input",
                        format!(
                        "status=binding-suggested interactionProfile={} rightPrimaryInputPath={} rightSecondaryInputPath={} rightThumbstickYInputPath={} rightGripPoseInputPath={} rightHapticOutputPath={} rightControllerThumbstickYBinding={} rightControllerPrimaryResetBinding={} rightControllerSecondaryScaleDriverToggleBinding={} rightGripPoseBinding={} rightBreathHapticBinding={}",
                            profile.profile_path,
                            profile.right_primary_path.unwrap_or("none"),
                            profile.right_secondary_path.unwrap_or("none"),
                            profile.right_thumbstick_y_path.unwrap_or("none"),
                            profile.right_grip_pose_path.unwrap_or("none"),
                            profile.right_haptic_output_path.unwrap_or("none"),
                            profile.right_thumbstick_y_path.is_some()
                                && projection_joystick_binding_enabled,
                            profile.right_primary_path.is_some() && projection_controls_enabled,
                            profile.right_secondary_path.is_some() && projection_controls_enabled,
                            profile.right_grip_pose_path.is_some() && right_grip_pose_binding_enabled,
                            profile.right_haptic_output_path.is_some() && breath_haptics_configured,
                        ),
                    );
                }
                Err(error) => crate::marker(
                    "projection-target-input",
                    format!(
                        "status=binding-warning interactionProfile={} reason={} rightControllerThumbstickYBinding=false rightControllerPrimaryResetBinding=false rightControllerSecondaryScaleDriverToggleBinding=false rightGripPoseBinding=false rightBreathHapticBinding=false",
                        profile.profile_path,
                        crate::sanitize(&error.to_string())
                    ),
                ),
            }
        }

        crate::marker(
            "stimulus-volume-input",
            format!(
                "status=config actionSet=stimulus_volume action=right_primary_randomize randomizeEnabled={} suggestedBindingCount={} rightControllerPrimaryButtonRandomize={} inputPath=/user/hand/right/input/a/click fallbackInputPath=/user/hand/right/input/select/click actionSetAttached=false",
                stimulus_settings.randomize_enabled,
                suggested_binding_count,
                stimulus_randomize_binding_enabled
            ),
        );
        crate::marker(
            "projection-target-input",
            format!(
                "status=config actionSet=stimulus_volume projectionTargetControlsEnabled={} rightThumbstickYAction={} rightControllerThumbstickY=/user/hand/right/input/thumbstick/y rightPrimaryResetAction={} rightControllerPrimaryReset=/user/hand/right/input/a/click rightSecondaryScaleDriverToggleAction={} rightControllerSecondaryScaleDriverToggle=/user/hand/right/input/b/click rightGripPoseAction={} optionalRightGripPose=/user/hand/right/input/grip/pose rightBreathHapticAction={} rightBreathHaptic={} rightBreathHapticSubaction={} breathHapticsConfigured={} breathHapticRequiresScaleDriver=pmb breathHapticRequiresRightGripTracked=true breathHapticPulseHz={:.3} breathHapticAmplitude={:.3} breathHapticDurationMs={} breathHapticFrequencyHz=runtime-default actionSetAttached=false highRatePoseViaAndroidProperties=false highRateBreathViaAndroidProperties=false",
                projection_target_settings.controls_enabled,
                projection_joystick_binding_enabled,
                projection_controls_enabled,
                projection_controls_enabled,
                right_grip_pose_binding_enabled,
                breath_haptics_configured,
                RIGHT_HAND_HAPTIC_OUTPUT_PATH,
                RIGHT_HAND_SUBACTION_PATH,
                breath_haptics_configured,
                breath_haptic_pulse_hz(),
                BREATH_HAPTIC_AMPLITUDE,
                BREATH_HAPTIC_PULSE_DURATION_MS,
            ),
        );

        crate::marker(
            "manifold-pose-provider",
            format!(
                "status=config nativeControllerPosePublisherEnabled={} stream={} source={} sourceKind=controller_pose_provider controller={} poseKind={} brokerHost={} brokerPort={} brokerPath={} sampleHz={:.3} providerBoundary=stream.motion.object_pose sourceAgnostic=true controllerSpecificEstimator=false highRatePoseViaManifold={} highRatePoseViaAndroidProperties=false",
                manifold_pose_config.enabled,
                crate::projection_target_state::marker_token(&manifold_pose_config.stream_id),
                crate::projection_target_state::marker_token(&manifold_pose_config.source_id),
                crate::projection_target_state::marker_token(&manifold_pose_config.controller),
                crate::projection_target_state::marker_token(&manifold_pose_config.pose_kind),
                crate::projection_target_state::marker_token(&manifold_pose_config.broker_host),
                manifold_pose_config.broker_port,
                crate::projection_target_state::marker_token(&manifold_pose_config.broker_path),
                manifold_pose_config.sample_hz,
                manifold_pose_config.enabled,
            ),
        );

        Ok(Some(Self {
            action_set,
            right_primary_randomize,
            right_primary_reset,
            right_secondary_scale_driver_toggle,
            right_thumbstick_y,
            right_grip_pose,
            right_breath_haptic,
            right_hand_subaction_path,
            right_grip_space: None,
            breath_haptic_cadence: BreathHapticCadence::new_ready(),
            breath_haptic_pulse_count: 0,
            breath_haptic_error_count: 0,
            manifold_pose_publisher: None,
            manifold_pose_config,
            manifold_pose_seconds_since_publish: 0.0,
            manifold_pose_sequence_id: 0,
            manifold_pose_published_count: 0,
            manifold_pose_dropped_count: 0,
            previous_right_primary_randomize_pressed: false,
            previous_right_primary_reset_pressed: false,
            previous_right_secondary_scale_driver_toggle_pressed: false,
            suggested_binding_count,
            stimulus_settings,
            projection_target_settings,
        }))
    }

    pub(crate) fn attach_session<G>(&mut self, session: &xr::Session<G>) -> Result<(), String> {
        session
            .attach_action_sets(&[&self.action_set])
            .map_err(|error| format!("attach native renderer OpenXR action set: {error}"))?;
        match self
            .right_grip_pose
            .create_space(session, xr::Path::NULL, xr::Posef::IDENTITY)
        {
            Ok(space) => {
                self.right_grip_space = Some(space);
                crate::marker(
                    "manifold-pose-provider",
                    "status=action-space-ready rightGripPoseActionSpace=true referenceSpace=native-renderer-openxr-reference-space",
                );
            }
            Err(error) => {
                self.right_grip_space = None;
                crate::marker(
                    "manifold-pose-provider",
                    format!(
                        "status=action-space-warning rightGripPoseActionSpace=false reason={}",
                        crate::sanitize(&error.to_string())
                    ),
                );
            }
        }
        crate::marker(
            "stimulus-volume-input",
            format!(
                "status=attached actionSet=stimulus_volume actionSetAttached=true suggestedBindingCount={} rightControllerPrimaryButtonRandomize={}",
                self.suggested_binding_count,
                self.stimulus_settings.enabled && self.stimulus_settings.randomize_enabled
            ),
        );
        crate::marker(
            "projection-target-input",
            format!(
                "status=attached actionSet=stimulus_volume actionSetAttached=true suggestedBindingCount={} rightThumbstickYAction={} rightPrimaryResetAction={} rightSecondaryScaleDriverToggleAction={} rightGripPoseAction={} rightBreathHapticAction={}",
                self.suggested_binding_count,
                self.projection_target_settings.controls_enabled
                    && self.projection_target_settings.joystick_controls_enabled,
                self.projection_target_settings.controls_enabled,
                self.projection_target_settings.controls_enabled,
                self.projection_target_settings.controls_enabled
                    && self
                        .projection_target_settings
                        .breath_bridge_mode
                        .uses_breath_stream(),
                self.projection_target_settings.controls_enabled
                    && self
                        .projection_target_settings
                        .breath_bridge_mode
                        .uses_breath_stream(),
            ),
        );
        Ok(())
    }

    pub(crate) fn sync_and_poll<G>(
        &mut self,
        session: &xr::Session<G>,
        reference_space: &xr::Space,
        predicted_display_time: xr::Time,
        frame_count: u64,
        dt_seconds: f32,
        breath_haptics_enabled: bool,
    ) -> NativeRendererControllerEvents {
        let mut events = NativeRendererControllerEvents::default();
        if let Err(error) = session.sync_actions(&[(&self.action_set).into()]) {
            if frame_count == 0 || frame_count % 120 == 0 {
                crate::marker(
                        "projection-target-input",
                        format!(
                            "status=sync-error frame={} reason={} rightControllerThumbstickYActive=false rightControllerPrimaryResetActive=false rightControllerSecondaryScaleDriverToggleActive=false",
                            frame_count,
                            crate::sanitize(&error.to_string())
                        ),
                );
            }
            return events;
        }

        events.stimulus_randomize_triggered = self.poll_primary_randomize(session, frame_count);
        if let Some(input) = self.poll_projection_reset(session, frame_count) {
            events.projection_target_inputs.push(input);
        }
        if let Some(input) = self.poll_scale_driver_toggle(session, frame_count) {
            events.projection_target_inputs.push(input);
        }
        if let Some(input) = self.poll_thumbstick_y(session, frame_count, dt_seconds) {
            events.projection_target_inputs.push(input);
        }
        events.right_grip_pose_active = match self
            .right_grip_pose
            .is_active(session, xr::Path::NULL)
        {
            Ok(active) => active,
            Err(error) => {
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "projection-target-input",
                        format!(
                            "status=grip-pose-state-warning frame={} reason={} rightGripPoseActive=false",
                            frame_count,
                            crate::sanitize(&error.to_string())
                        ),
                    );
                }
                false
            }
        };
        events.right_grip_pose_tracked = if breath_haptics_enabled {
            self.right_grip_pose_tracked(
                reference_space,
                predicted_display_time,
                events.right_grip_pose_active,
                frame_count,
            )
        } else {
            false
        };
        self.publish_right_grip_pose(
            reference_space,
            predicted_display_time,
            events.right_grip_pose_active,
            frame_count,
            dt_seconds,
        );
        self.pulse_breath_haptic(
            session,
            breath_haptics_enabled,
            events.right_grip_pose_tracked,
            frame_count,
            dt_seconds,
        );
        if frame_count == 0 || frame_count % 120 == 0 {
            let breath_haptic_action = self.projection_target_settings.controls_enabled
                && self
                    .projection_target_settings
                    .breath_bridge_mode
                    .uses_breath_stream();
            crate::marker(
                "projection-target-input",
                format!(
                    "status=polled frame={} rightGripPoseActive={} rightGripPoseTracked={} nativeControllerPosePublisherEnabled={} nativeControllerPosePublishedCount={} nativeControllerPoseDroppedCount={} rightControllerThumbstickYAction={} rightPrimaryResetAction={} rightSecondaryScaleDriverToggleAction={} rightBreathHapticAction={} breathHapticsEnabled={} highRatePoseViaManifold={} highRatePoseViaAndroidProperties=false",
                    frame_count,
                    events.right_grip_pose_active,
                    events.right_grip_pose_tracked,
                    self.manifold_pose_config.enabled,
                    self.manifold_pose_published_count,
                    self.manifold_pose_dropped_count,
                    self.projection_target_settings.controls_enabled
                        && self.projection_target_settings.joystick_controls_enabled,
                    self.projection_target_settings.controls_enabled,
                    self.projection_target_settings.controls_enabled,
                    breath_haptic_action,
                    breath_haptics_enabled,
                    self.manifold_pose_config.enabled,
                ),
            );
        }
        events
    }

    fn right_grip_pose_tracked(
        &self,
        reference_space: &xr::Space,
        predicted_display_time: xr::Time,
        active: bool,
        frame_count: u64,
    ) -> bool {
        let Some(space) = self.right_grip_space.as_ref() else {
            if frame_count == 0 || frame_count % 120 == 0 {
                crate::marker(
                    "projection-target-haptics",
                    format!(
                        "status=tracking-skipped frame={} reason=right-grip-action-space-unavailable breathHapticsEnabled=true rightGripPoseTracked=false rightGripPoseActionSpace=false breathHapticPulseCount={} breathHapticErrorCount={}",
                        frame_count,
                        self.breath_haptic_pulse_count,
                        self.breath_haptic_error_count,
                    ),
                );
            }
            return false;
        };
        let location = match space.locate(reference_space, predicted_display_time) {
            Ok(location) => location,
            Err(error) => {
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "projection-target-haptics",
                        format!(
                            "status=tracking-warning frame={} reason={} breathHapticsEnabled=true rightGripPoseTracked=false breathHapticPulseCount={} breathHapticErrorCount={}",
                            frame_count,
                            crate::sanitize(&error.to_string()),
                            self.breath_haptic_pulse_count,
                            self.breath_haptic_error_count,
                        ),
                    );
                }
                return false;
            }
        };
        let position = [
            location.pose.position.x,
            location.pose.position.y,
            location.pose.position.z,
        ];
        let orientation = [
            location.pose.orientation.x,
            location.pose.orientation.y,
            location.pose.orientation.z,
            location.pose.orientation.w,
        ];
        let pose_finite = position.iter().all(|value| value.is_finite())
            && orientation.iter().all(|value| value.is_finite());
        let tracked = active && pose_finite && space_location_pose_usable(location);
        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "projection-target-haptics",
                format!(
                    "status=tracking-polled frame={} breathHapticsEnabled=true rightGripPoseActive={} rightGripPoseTracked={} locationFlags={:?} breathHapticPulseCount={} breathHapticErrorCount={}",
                    frame_count,
                    active,
                    tracked,
                    location.location_flags,
                    self.breath_haptic_pulse_count,
                    self.breath_haptic_error_count,
                ),
            );
        }
        tracked
    }

    fn pulse_breath_haptic<G>(
        &mut self,
        session: &xr::Session<G>,
        enabled: bool,
        tracked: bool,
        frame_count: u64,
        dt_seconds: f32,
    ) {
        let should_pulse = self
            .breath_haptic_cadence
            .update(dt_seconds, enabled, tracked);
        if !enabled || !tracked {
            if frame_count == 0 || frame_count % 120 == 0 {
                crate::marker(
                    "projection-target-haptics",
                    format!(
                        "status=inactive frame={} breathHapticsEnabled={} rightGripPoseTracked={} breathHapticRequiresScaleDriver=pmb breathHapticRequiresRightGripTracked=true breathHapticPulseCount={} breathHapticErrorCount={}",
                        frame_count,
                        enabled,
                        tracked,
                        self.breath_haptic_pulse_count,
                        self.breath_haptic_error_count,
                    ),
                );
            }
            return;
        }
        if !should_pulse {
            return;
        }

        let vibration = xr::HapticVibration::new()
            .duration(xr::Duration::from_nanos(BREATH_HAPTIC_PULSE_DURATION_NANOS))
            .frequency(xr::FREQUENCY_UNSPECIFIED)
            .amplitude(BREATH_HAPTIC_AMPLITUDE);
        match self.right_breath_haptic.apply_feedback(
            session,
            self.right_hand_subaction_path,
            &vibration,
        ) {
            Ok(()) => {
                self.breath_haptic_pulse_count = self.breath_haptic_pulse_count.saturating_add(1);
                if self.breath_haptic_pulse_count == 1 || self.breath_haptic_pulse_count % 10 == 0 {
                    crate::marker(
                        "projection-target-haptics",
                        format!(
                            "status=pulse-applied frame={} breathHapticsEnabled=true rightGripPoseTracked=true breathHapticPulseCount={} breathHapticErrorCount={} breathHapticPulseHz={:.3} breathHapticAmplitude={:.3} breathHapticDurationMs={} breathHapticFrequencyHz=runtime-default",
                            frame_count,
                            self.breath_haptic_pulse_count,
                            self.breath_haptic_error_count,
                            breath_haptic_pulse_hz(),
                            BREATH_HAPTIC_AMPLITUDE,
                            BREATH_HAPTIC_PULSE_DURATION_MS,
                        ),
                    );
                }
            }
            Err(error) => {
                self.breath_haptic_error_count = self.breath_haptic_error_count.saturating_add(1);
                if self.breath_haptic_error_count == 1 || frame_count % 120 == 0 {
                    crate::marker(
                        "projection-target-haptics",
                        format!(
                            "status=pulse-error frame={} reason={} breathHapticsEnabled=true rightGripPoseTracked=true breathHapticPulseCount={} breathHapticErrorCount={} breathHapticPulseHz={:.3} breathHapticAmplitude={:.3} breathHapticDurationMs={}",
                            frame_count,
                            crate::sanitize(&error.to_string()),
                            self.breath_haptic_pulse_count,
                            self.breath_haptic_error_count,
                            breath_haptic_pulse_hz(),
                            BREATH_HAPTIC_AMPLITUDE,
                            BREATH_HAPTIC_PULSE_DURATION_MS,
                        ),
                    );
                }
            }
        }
    }

    fn publish_right_grip_pose(
        &mut self,
        reference_space: &xr::Space,
        predicted_display_time: xr::Time,
        active: bool,
        frame_count: u64,
        dt_seconds: f32,
    ) {
        if !self.manifold_pose_config.enabled {
            return;
        }
        if self.right_grip_space.is_none() {
            if frame_count == 0 || frame_count % 120 == 0 {
                crate::marker(
                    "manifold-pose-provider",
                    format!(
                        "status=sample-skipped frame={} reason=right-grip-action-space-unavailable nativeControllerPosePublisherEnabled=true rightGripPoseActionSpace=false nativeControllerPosePublishedCount={} nativeControllerPoseDroppedCount={} highRatePoseViaManifold=true highRatePoseViaAndroidProperties=false",
                        frame_count,
                        self.manifold_pose_published_count,
                        self.manifold_pose_dropped_count
                    ),
                );
            }
            return;
        }

        if dt_seconds.is_finite() && dt_seconds > 0.0 {
            self.manifold_pose_seconds_since_publish += f64::from(dt_seconds.min(1.0));
        }
        let interval_seconds = self.manifold_pose_config.interval_seconds();
        if self.manifold_pose_sequence_id > 0
            && self.manifold_pose_seconds_since_publish < interval_seconds
        {
            return;
        }
        self.manifold_pose_seconds_since_publish = if self.manifold_pose_sequence_id > 0 {
            (self.manifold_pose_seconds_since_publish - interval_seconds)
                .clamp(0.0, interval_seconds)
        } else {
            0.0
        };

        let needs_publisher = match self.manifold_pose_publisher.as_ref() {
            Some(publisher) => publisher.config() != &self.manifold_pose_config,
            None => true,
        };
        if needs_publisher {
            crate::marker(
                "manifold-pose-provider",
                format!(
                    "status=publisher-ready nativeControllerPosePublisherEnabled=true stream={} source={} sourceKind=controller_pose_provider brokerHost={} brokerPort={} brokerPath={} sampleHz={:.3} providerBoundary=stream.motion.object_pose sourceAgnostic=true controllerSpecificEstimator=false",
                    crate::projection_target_state::marker_token(&self.manifold_pose_config.stream_id),
                    crate::projection_target_state::marker_token(&self.manifold_pose_config.source_id),
                    crate::projection_target_state::marker_token(&self.manifold_pose_config.broker_host),
                    self.manifold_pose_config.broker_port,
                    crate::projection_target_state::marker_token(&self.manifold_pose_config.broker_path),
                    self.manifold_pose_config.sample_hz,
                ),
            );
            self.manifold_pose_publisher = Some(ManifoldPosePublisher::new(
                self.manifold_pose_config.clone(),
            ));
        }

        let space = self.right_grip_space.as_ref().expect("checked above");
        let location = match space.locate(reference_space, predicted_display_time) {
            Ok(location) => location,
            Err(error) => {
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "manifold-pose-provider",
                        format!(
                            "status=locate-warning frame={} reason={} nativeControllerPosePublisherEnabled=true rightGripPoseTracked=false nativeControllerPosePublishedCount={} nativeControllerPoseDroppedCount={}",
                            frame_count,
                            crate::sanitize(&error.to_string()),
                            self.manifold_pose_published_count,
                            self.manifold_pose_dropped_count
                        ),
                    );
                }
                return;
            }
        };

        let position = [
            location.pose.position.x,
            location.pose.position.y,
            location.pose.position.z,
        ];
        let orientation = [
            location.pose.orientation.x,
            location.pose.orientation.y,
            location.pose.orientation.z,
            location.pose.orientation.w,
        ];
        let pose_finite = position.iter().all(|value| value.is_finite())
            && orientation.iter().all(|value| value.is_finite());
        let tracked = active && pose_finite && space_location_pose_usable(location);
        self.manifold_pose_sequence_id = self.manifold_pose_sequence_id.saturating_add(1);
        let sequence_id = self.manifold_pose_sequence_id;
        let sample = ManifoldPoseSample {
            sequence_id,
            sample_time_unix_ns: ManifoldPoseSample::now_unix_ns(),
            xr_predicted_display_time_ns: predicted_display_time.as_nanos(),
            controller: self.manifold_pose_config.controller.clone(),
            pose_kind: self.manifold_pose_config.pose_kind.clone(),
            active,
            tracked,
            position_m: position,
            orientation_xyzw: orientation,
        };
        let queued = self
            .manifold_pose_publisher
            .as_ref()
            .is_some_and(|publisher| publisher.publish(sample));
        if queued {
            self.manifold_pose_published_count =
                self.manifold_pose_published_count.saturating_add(1);
        } else {
            self.manifold_pose_dropped_count = self.manifold_pose_dropped_count.saturating_add(1);
        }
        if !queued
            || self.manifold_pose_published_count == 1
            || self.manifold_pose_published_count % 120 == 0
            || frame_count == 0
        {
            crate::marker(
                "manifold-pose-provider",
                format!(
                    "status={} frame={} stream={} sequenceId={} controller={} poseKind={} rightGripPoseActive={} rightGripPoseTracked={} queued={} nativeControllerPosePublishedCount={} nativeControllerPoseDroppedCount={} locationFlags={:?} positionM={:.5},{:.5},{:.5} highRatePoseViaManifold=true highRatePoseViaAndroidProperties=false",
                    if queued { "sample-queued" } else { "sample-dropped" },
                    frame_count,
                    crate::projection_target_state::marker_token(&self.manifold_pose_config.stream_id),
                    sequence_id,
                    crate::projection_target_state::marker_token(&self.manifold_pose_config.controller),
                    crate::projection_target_state::marker_token(&self.manifold_pose_config.pose_kind),
                    active,
                    tracked,
                    queued,
                    self.manifold_pose_published_count,
                    self.manifold_pose_dropped_count,
                    location.location_flags,
                    position[0],
                    position[1],
                    position[2],
                ),
            );
        }
    }

    fn poll_primary_randomize<G>(&mut self, session: &xr::Session<G>, frame_count: u64) -> bool {
        if !self.stimulus_settings.enabled || !self.stimulus_settings.randomize_enabled {
            return false;
        }
        let state = match self.right_primary_randomize.state(session, xr::Path::NULL) {
            Ok(state) => state,
            Err(error) => {
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "stimulus-volume-input",
                        format!(
                            "status=state-error frame={} reason={} rightControllerPrimaryButtonRandomize=false",
                            frame_count,
                            crate::sanitize(&error.to_string())
                        ),
                    );
                }
                return false;
            }
        };

        let pressed = state.is_active && state.current_state;
        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "stimulus-volume-input",
                format!(
                    "status=polled frame={} rightControllerPrimaryButtonRandomize=true actionActive={} currentState={} changedSinceLastSync={} stimulusRandomizeEnabled=true",
                    frame_count,
                    state.is_active,
                    state.current_state,
                    state.changed_since_last_sync
                ),
            );
        }
        let previous_pressed = self.previous_right_primary_randomize_pressed;
        let triggered = pressed && !previous_pressed;
        self.previous_right_primary_randomize_pressed = pressed;

        if triggered {
            crate::marker(
                "stimulus-volume-input",
                format!(
                    "event=right-primary-randomize status=triggered frame={} rightControllerPrimaryButtonRandomize=true actionActive={} currentState={} previousPressed={} changedSinceLastSync={}",
                    frame_count,
                    state.is_active,
                    state.current_state,
                    previous_pressed,
                    state.changed_since_last_sync
                ),
            );
        }

        triggered
    }

    fn poll_projection_reset<G>(
        &mut self,
        session: &xr::Session<G>,
        frame_count: u64,
    ) -> Option<ProjectionTargetInput> {
        if !self.projection_target_settings.controls_enabled {
            return None;
        }
        let state = match self.right_primary_reset.state(session, xr::Path::NULL) {
            Ok(state) => state,
            Err(error) => {
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "projection-target-input",
                        format!(
                            "status=primary-reset-state-error frame={} reason={} rightPrimaryResetActive=false",
                            frame_count,
                            crate::sanitize(&error.to_string())
                        ),
                    );
                }
                return None;
            }
        };
        let pressed = state.is_active && state.current_state;
        let triggered = pressed && !self.previous_right_primary_reset_pressed;
        self.previous_right_primary_reset_pressed = pressed;
        if triggered {
            crate::marker(
                "projection-target-input",
                format!(
                    "event=right-primary-reset status=triggered frame={} actionActive={} changedSinceLastSync={}",
                    frame_count,
                    state.is_active,
                    state.changed_since_last_sync
                ),
            );
            return Some(ProjectionTargetInput::ResetBaseScale);
        }
        None
    }

    fn poll_scale_driver_toggle<G>(
        &mut self,
        session: &xr::Session<G>,
        frame_count: u64,
    ) -> Option<ProjectionTargetInput> {
        if !self.projection_target_settings.controls_enabled {
            return None;
        }
        let state = match self
            .right_secondary_scale_driver_toggle
            .state(session, xr::Path::NULL)
        {
            Ok(state) => state,
            Err(error) => {
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "projection-target-input",
                        format!(
                            "status=secondary-scale-driver-toggle-state-error frame={} reason={} rightSecondaryScaleDriverToggleActive=false",
                            frame_count,
                            crate::sanitize(&error.to_string())
                        ),
                    );
                }
                return None;
            }
        };
        let pressed = state.is_active && state.current_state;
        let triggered = pressed && !self.previous_right_secondary_scale_driver_toggle_pressed;
        self.previous_right_secondary_scale_driver_toggle_pressed = pressed;
        if triggered {
            crate::marker(
                "projection-target-input",
                format!(
                    "event=right-secondary-scale-driver-toggle status=triggered frame={} actionActive={} changedSinceLastSync={}",
                    frame_count,
                    state.is_active,
                    state.changed_since_last_sync
                ),
            );
            return Some(ProjectionTargetInput::ToggleScaleDriver);
        }
        None
    }

    fn poll_thumbstick_y<G>(
        &self,
        session: &xr::Session<G>,
        frame_count: u64,
        dt_seconds: f32,
    ) -> Option<ProjectionTargetInput> {
        if !self.projection_target_settings.controls_enabled
            || !self.projection_target_settings.joystick_controls_enabled
        {
            return None;
        }
        let state = match self.right_thumbstick_y.state(session, xr::Path::NULL) {
            Ok(state) => state,
            Err(error) => {
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "projection-target-input",
                        format!(
                            "status=thumbstick-state-error frame={} reason={} rightControllerThumbstickYActive=false",
                            frame_count,
                            crate::sanitize(&error.to_string())
                        ),
                    );
                }
                return None;
            }
        };
        if state.is_active {
            return Some(ProjectionTargetInput::JoystickScaleDelta {
                right_thumbstick_y: state.current_state,
                dt_seconds,
            });
        }
        None
    }
}

fn manifold_pose_config_from_projection_settings(
    settings: &ProjectionTargetSettings,
) -> ManifoldPosePublisherConfig {
    ManifoldPosePublisherConfig {
        enabled: settings.controls_enabled && settings.breath_bridge_mode.uses_breath_stream(),
        broker_host: settings.manifold_broker_host.clone(),
        broker_port: settings.manifold_broker_port,
        broker_path: settings.manifold_broker_path.clone(),
        stream_id: DEFAULT_MANIFOLD_POSE_STREAM.to_string(),
        source_id: DEFAULT_MANIFOLD_POSE_SOURCE.to_string(),
        controller: DEFAULT_MANIFOLD_POSE_CONTROLLER.to_string(),
        pose_kind: DEFAULT_MANIFOLD_POSE_KIND.to_string(),
        sample_hz: DEFAULT_MANIFOLD_POSE_SAMPLE_HZ,
        connect_timeout_ms: 250,
    }
}

fn space_location_pose_usable(location: xr::SpaceLocation) -> bool {
    let flags = location.location_flags;
    let position_usable = flags.intersects(
        xr::sys::SpaceLocationFlags::POSITION_VALID | xr::sys::SpaceLocationFlags::POSITION_TRACKED,
    );
    let orientation_usable = flags.intersects(
        xr::sys::SpaceLocationFlags::ORIENTATION_VALID
            | xr::sys::SpaceLocationFlags::ORIENTATION_TRACKED,
    );
    position_usable && orientation_usable
}

fn breath_haptic_pulse_hz() -> f32 {
    1.0 / BREATH_HAPTIC_PULSE_PERIOD_SECONDS
}

struct InteractionProfileBindings {
    profile_path: &'static str,
    right_primary_path: Option<&'static str>,
    right_secondary_path: Option<&'static str>,
    right_thumbstick_y_path: Option<&'static str>,
    right_grip_pose_path: Option<&'static str>,
    right_haptic_output_path: Option<&'static str>,
}

const INTERACTION_PROFILES: &[InteractionProfileBindings] = &[
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/oculus/touch_controller",
        right_primary_path: Some("/user/hand/right/input/a/click"),
        right_secondary_path: Some("/user/hand/right/input/b/click"),
        right_thumbstick_y_path: Some("/user/hand/right/input/thumbstick/y"),
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
        right_haptic_output_path: Some(RIGHT_HAND_HAPTIC_OUTPUT_PATH),
    },
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/meta/touch_controller_plus",
        right_primary_path: Some("/user/hand/right/input/a/click"),
        right_secondary_path: Some("/user/hand/right/input/b/click"),
        right_thumbstick_y_path: Some("/user/hand/right/input/thumbstick/y"),
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
        right_haptic_output_path: Some(RIGHT_HAND_HAPTIC_OUTPUT_PATH),
    },
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/khr/simple_controller",
        right_primary_path: Some("/user/hand/right/input/select/click"),
        right_secondary_path: None,
        right_thumbstick_y_path: None,
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
        right_haptic_output_path: Some(RIGHT_HAND_HAPTIC_OUTPUT_PATH),
    },
];

#[cfg(test)]
mod tests {
    use super::{
        manifold_pose_config_from_projection_settings, BreathHapticCadence,
        BREATH_HAPTIC_PULSE_PERIOD_SECONDS,
    };
    use crate::projection_target_state::{BreathBridgeMode, ProjectionTargetSettings};

    #[test]
    fn breath_haptic_cadence_pulses_immediately_then_at_period() {
        let mut cadence = BreathHapticCadence::new_ready();
        assert!(cadence.update(0.0, true, true));
        assert!(!cadence.update(BREATH_HAPTIC_PULSE_PERIOD_SECONDS * 0.5, true, true));
        assert!(cadence.update(BREATH_HAPTIC_PULSE_PERIOD_SECONDS * 0.5, true, true));
    }

    #[test]
    fn breath_haptic_cadence_requires_enabled_and_tracked() {
        let mut cadence = BreathHapticCadence::new_ready();
        assert!(!cadence.update(BREATH_HAPTIC_PULSE_PERIOD_SECONDS, false, true));
        assert!(!cadence.update(BREATH_HAPTIC_PULSE_PERIOD_SECONDS, true, false));
        assert!(cadence.update(0.0, true, true));
    }

    #[test]
    fn manifold_pose_provider_requires_projection_target_controls() {
        let mut settings = ProjectionTargetSettings::default();
        settings.breath_bridge_mode = BreathBridgeMode::ManifoldState;
        assert!(!manifold_pose_config_from_projection_settings(&settings).enabled);

        settings.controls_enabled = true;
        assert!(manifold_pose_config_from_projection_settings(&settings).enabled);
    }
}
