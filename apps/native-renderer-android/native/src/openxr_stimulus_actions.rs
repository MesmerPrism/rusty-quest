//! OpenXR action boundary for native renderer controller controls.

use openxr as xr;

use crate::{
    native_renderer_options::NativeStimulusVolumeSettings,
    projection_target_state::{ProjectionTargetInput, ProjectionTargetSettings},
};

pub(crate) struct StimulusVolumeActions {
    action_set: xr::ActionSet,
    right_primary_randomize: xr::Action<bool>,
    right_primary_reset: xr::Action<bool>,
    right_thumbstick_y: xr::Action<f32>,
    right_grip_pose: xr::Action<xr::Posef>,
    previous_right_primary_randomize_pressed: bool,
    previous_right_primary_reset_pressed: bool,
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
                "status=disabled reason=projection-target-controls-disabled actionSetAttached=false rightThumbstickYAction=false rightPrimaryResetAction=false rightGripPoseAction=false",
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
                            "status=binding-suggested interactionProfile={} rightPrimaryInputPath={} rightThumbstickYInputPath={} rightGripPoseInputPath={} rightControllerThumbstickYBinding={} rightControllerPrimaryResetBinding={} rightGripPoseBinding={}",
                            profile.profile_path,
                            profile.right_primary_path.unwrap_or("none"),
                            profile.right_thumbstick_y_path.unwrap_or("none"),
                            profile.right_grip_pose_path.unwrap_or("none"),
                            profile.right_thumbstick_y_path.is_some(),
                            profile.right_primary_path.is_some(),
                            profile.right_grip_pose_path.is_some(),
                        ),
                    );
                }
                Err(error) => crate::marker(
                    "projection-target-input",
                    format!(
                        "status=binding-warning interactionProfile={} reason={} rightControllerThumbstickYBinding=false rightControllerPrimaryResetBinding=false rightGripPoseBinding=false",
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
                "status=config actionSet=stimulus_volume projectionTargetControlsEnabled={} rightThumbstickYAction=true rightControllerThumbstickY=/user/hand/right/input/thumbstick/y rightPrimaryResetAction=true rightControllerPrimaryReset=/user/hand/right/input/a/click rightGripPoseAction=true optionalRightGripPose=/user/hand/right/input/grip/pose actionSetAttached=false highRatePoseViaAndroidProperties=false highRateBreathViaAndroidProperties=false",
                projection_target_settings.controls_enabled,
            ),
        );

        Ok(Some(Self {
            action_set,
            right_primary_randomize,
            right_primary_reset,
            right_thumbstick_y,
            right_grip_pose,
            previous_right_primary_randomize_pressed: false,
            previous_right_primary_reset_pressed: false,
            suggested_binding_count,
            stimulus_settings,
            projection_target_settings,
        }))
    }

    pub(crate) fn attach_session<G>(&self, session: &xr::Session<G>) -> Result<(), String> {
        session
            .attach_action_sets(&[&self.action_set])
            .map_err(|error| format!("attach native renderer OpenXR action set: {error}"))?;
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
                "status=attached actionSet=stimulus_volume actionSetAttached=true suggestedBindingCount={} rightThumbstickYAction=true rightPrimaryResetAction=true rightGripPoseAction=true",
                self.suggested_binding_count
            ),
        );
        Ok(())
    }

    pub(crate) fn sync_and_poll<G>(
        &mut self,
        session: &xr::Session<G>,
        frame_count: u64,
        dt_seconds: f32,
    ) -> NativeRendererControllerEvents {
        let mut events = NativeRendererControllerEvents::default();
        if let Err(error) = session.sync_actions(&[(&self.action_set).into()]) {
            if frame_count == 0 || frame_count % 120 == 0 {
                crate::marker(
                    "projection-target-input",
                    format!(
                        "status=sync-error frame={} reason={} rightControllerThumbstickYActive=false rightControllerPrimaryResetActive=false",
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
        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "projection-target-input",
                format!(
                    "status=polled frame={} rightGripPoseActive={} rightControllerThumbstickYAction=true rightPrimaryResetAction=true highRatePoseViaAndroidProperties=false",
                    frame_count, events.right_grip_pose_active
                ),
            );
        }
        events
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

struct InteractionProfileBindings {
    profile_path: &'static str,
    right_primary_path: Option<&'static str>,
    right_thumbstick_y_path: Option<&'static str>,
    right_grip_pose_path: Option<&'static str>,
}

const INTERACTION_PROFILES: &[InteractionProfileBindings] = &[
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/oculus/touch_controller",
        right_primary_path: Some("/user/hand/right/input/a/click"),
        right_thumbstick_y_path: Some("/user/hand/right/input/thumbstick/y"),
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
    },
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/meta/touch_controller_plus",
        right_primary_path: Some("/user/hand/right/input/a/click"),
        right_thumbstick_y_path: Some("/user/hand/right/input/thumbstick/y"),
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
    },
    InteractionProfileBindings {
        profile_path: "/interaction_profiles/khr/simple_controller",
        right_primary_path: Some("/user/hand/right/input/select/click"),
        right_thumbstick_y_path: None,
        right_grip_pose_path: Some("/user/hand/right/input/grip/pose"),
    },
];
