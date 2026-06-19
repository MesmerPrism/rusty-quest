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

pub(crate) struct StimulusVolumeActions {
    action_set: xr::ActionSet,
    right_primary_randomize: xr::Action<bool>,
    right_primary_reset: xr::Action<bool>,
    right_secondary_scale_driver_toggle: xr::Action<bool>,
    right_thumbstick_y: xr::Action<f32>,
    right_grip_pose: xr::Action<xr::Posef>,
    right_grip_space: Option<xr::Space>,
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
                bindings.push(xr::Binding::new(&right_primary_randomize, input));
                bindings.push(xr::Binding::new(&right_primary_reset, input));
            }
            if let Some(input_path) = profile.right_secondary_path {
                let input = instance.string_to_path(input_path).map_err(|error| {
                    format!("create OpenXR path for secondary input {input_path}: {error}")
                })?;
                bindings.push(xr::Binding::new(
                    &right_secondary_scale_driver_toggle,
                    input,
                ));
            }
            if let Some(input_path) = profile.right_thumbstick_y_path {
                let input = instance.string_to_path(input_path).map_err(|error| {
                    format!("create OpenXR path for thumbstick Y input {input_path}: {error}")
                })?;
                bindings.push(xr::Binding::new(&right_thumbstick_y, input));
            }
            if let Some(input_path) = profile.right_grip_pose_path {
                let input = instance.string_to_path(input_path).map_err(|error| {
                    format!("create OpenXR path for right grip pose input {input_path}: {error}")
                })?;
                bindings.push(xr::Binding::new(&right_grip_pose, input));
            }
            match instance.suggest_interaction_profile_bindings(profile_path, &bindings) {
                Ok(()) => {
                    suggested_binding_count = suggested_binding_count.saturating_add(bindings.len());
                    crate::marker(
                        "projection-target-input",
                        format!(
                            "status=binding-suggested interactionProfile={} rightPrimaryInputPath={} rightSecondaryInputPath={} rightThumbstickYInputPath={} rightGripPoseInputPath={} rightControllerThumbstickYBinding={} rightControllerPrimaryResetBinding={} rightControllerSecondaryScaleDriverToggleBinding={} rightGripPoseBinding={}",
                            profile.profile_path,
                            profile.right_primary_path.unwrap_or("none"),
                            profile.right_secondary_path.unwrap_or("none"),
                            profile.right_thumbstick_y_path.unwrap_or("none"),
                            profile.right_grip_pose_path.unwrap_or("none"),
                            profile.right_thumbstick_y_path.is_some(),
                            profile.right_primary_path.is_some(),
                            profile.right_secondary_path.is_some(),
                            profile.right_grip_pose_path.is_some(),
                        ),
                    );
                }
                Err(error) => crate::marker(
                    "projection-target-input",
                    format!(
                        "status=binding-warning interactionProfile={} reason={} rightControllerThumbstickYBinding=false rightControllerPrimaryResetBinding=false rightControllerSecondaryScaleDriverToggleBinding=false rightGripPoseBinding=false",
                        profile.profile_path,
                        crate::sanitize(&error.to_string())
                    ),
                ),
            }
        }

        crate::marker(
            "stimulus-volume-input",
            format!(
                "status=config actionSet=stimulus_volume action=right_primary_randomize randomizeEnabled={} suggestedBindingCount={} rightControllerPrimaryButtonRandomize=true inputPath=/user/hand/right/input/a/click fallbackInputPath=/user/hand/right/input/select/click actionSetAttached=false",
                stimulus_settings.randomize_enabled,
                suggested_binding_count
            ),
        );
        crate::marker(
            "projection-target-input",
            format!(
                "status=config actionSet=stimulus_volume projectionTargetControlsEnabled={} rightThumbstickYAction=true rightControllerThumbstickY=/user/hand/right/input/thumbstick/y rightPrimaryResetAction=true rightControllerPrimaryReset=/user/hand/right/input/a/click rightSecondaryScaleDriverToggleAction=true rightControllerSecondaryScaleDriverToggle=/user/hand/right/input/b/click rightGripPoseAction=true optionalRightGripPose=/user/hand/right/input/grip/pose actionSetAttached=false highRatePoseViaAndroidProperties=false highRateBreathViaAndroidProperties=false",
                projection_target_settings.controls_enabled,
            ),
        );

        let manifold_pose_config =
            manifold_pose_config_from_projection_settings(&projection_target_settings);
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
            right_grip_space: None,
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
                "status=attached actionSet=stimulus_volume actionSetAttached=true suggestedBindingCount={} rightControllerPrimaryButtonRandomize=true",
                self.suggested_binding_count
            ),
        );
        crate::marker(
            "projection-target-input",
            format!(
                "status=attached actionSet=stimulus_volume actionSetAttached=true suggestedBindingCount={} rightThumbstickYAction=true rightPrimaryResetAction=true rightSecondaryScaleDriverToggleAction=true rightGripPoseAction=true",
                self.suggested_binding_count
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
        self.publish_right_grip_pose(
            reference_space,
            predicted_display_time,
            events.right_grip_pose_active,
            frame_count,
            dt_seconds,
        );
        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "projection-target-input",
                format!(
                    "status=polled frame={} rightGripPoseActive={} nativeControllerPosePublisherEnabled={} nativeControllerPosePublishedCount={} nativeControllerPoseDroppedCount={} rightControllerThumbstickYAction=true rightPrimaryResetAction=true rightSecondaryScaleDriverToggleAction=true highRatePoseViaManifold={} highRatePoseViaAndroidProperties=false",
                    frame_count,
                    events.right_grip_pose_active,
                    self.manifold_pose_config.enabled,
                    self.manifold_pose_published_count,
                    self.manifold_pose_dropped_count,
                    self.manifold_pose_config.enabled,
                ),
            );
        }
        events
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
        let triggered = pressed && !self.previous_right_primary_randomize_pressed;
        self.previous_right_primary_randomize_pressed = pressed;

        if triggered {
            crate::marker(
                "stimulus-volume-input",
                format!(
                    "event=right-primary-randomize status=triggered frame={} actionActive={} changedSinceLastSync={}",
                    frame_count,
                    state.is_active,
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
        enabled: settings.breath_bridge_mode.uses_breath_stream(),
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

struct InteractionProfileBindings {
    profile_path: &'static str,
    right_primary_path: Option<&'static str>,
    right_secondary_path: Option<&'static str>,
    right_thumbstick_y_path: Option<&'static str>,
    right_grip_pose_path: Option<&'static str>,
}

const INTERACTION_PROFILES: &[InteractionProfileBindings] = &[
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/oculus/touch_controller",
        right_primary_path: Some("/user/hand/right/input/a/click"),
        right_secondary_path: Some("/user/hand/right/input/b/click"),
        right_thumbstick_y_path: Some("/user/hand/right/input/thumbstick/y"),
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
    },
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/meta/touch_controller_plus",
        right_primary_path: Some("/user/hand/right/input/a/click"),
        right_secondary_path: Some("/user/hand/right/input/b/click"),
        right_thumbstick_y_path: Some("/user/hand/right/input/thumbstick/y"),
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
    },
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/khr/simple_controller",
        right_primary_path: Some("/user/hand/right/input/select/click"),
        right_secondary_path: None,
        right_thumbstick_y_path: None,
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
    },
];
