//! JNI bridge for launching the same-APK 2D control panel.

#[cfg(target_os = "android")]
use std::sync::OnceLock;

#[cfg(target_os = "android")]
const ACTION_OPEN_PANEL: &str =
    "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_PANEL";
#[cfg(target_os = "android")]
const ACTION_TOGGLE_PANEL: &str =
    "io.github.mesmerprism.rustyquest.native_renderer.action.TOGGLE_PANEL";
#[cfg(target_os = "android")]
const ACTION_OPEN_EXPERIMENTER_PANEL: &str =
    "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_EXPERIMENTER_PANEL";
#[cfg(target_os = "android")]
const ACTION_OPEN_DEVELOPER_PANEL: &str =
    "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_DEVELOPER_PANEL";
#[cfg(target_os = "android")]
const ACTION_MAIN: &str = "android.intent.action.MAIN";
#[cfg(target_os = "android")]
const EXTRA_PANEL_ROUTE: &str = "native_renderer_panel_route";
#[cfg(target_os = "android")]
const EXTRA_PANEL_ROUTE_GENERATION: &str = "native_renderer_panel_route_generation";
#[cfg(target_os = "android")]
const EXTRA_PANEL_ROUTE_PROVENANCE: &str = "native_renderer_panel_route_provenance";
#[cfg(target_os = "android")]
const EXTRA_PANEL_SESSION_GENERATION: &str = "native_renderer_panel_session_generation";
#[cfg(target_os = "android")]
const EXTRA_PANEL_OPERATION_ID: &str = "native_renderer_panel_operation_id";
#[cfg(target_os = "android")]
const EXTRA_LAUNCH_PROVENANCE: &str = "native_renderer_launch_provenance";
#[cfg(target_os = "android")]
const EXTRA_LAUNCH_EPOCH: &str = "native_renderer_launch_epoch";
#[cfg(target_os = "android")]
const PROVENANCE_EXPLICIT_USER_LAUNCH: &str = "explicit-user-launch-v1";
#[cfg(target_os = "android")]
const PROP_CONTROL_PANEL_OPEN_TOKEN: &str =
    "debug.rustyquest.native_renderer.control_panel.open_token";
#[cfg(target_os = "android")]
const PROP_CONTROL_PANEL_MODE: &str = "debug.rustyquest.native_renderer.control_panel.mode";
#[cfg(target_os = "android")]
static PACKAGED_CONTROL_PANEL_MODE: OnceLock<Option<String>> = OnceLock::new();

#[cfg(target_os = "android")]
#[derive(Clone, Copy, Debug)]
pub(crate) struct PanelRouteReceipt<'a> {
    pub(crate) route_generation: u64,
    pub(crate) provenance: &'a str,
    pub(crate) session_generation: Option<u64>,
    pub(crate) operation_id: Option<&'a str>,
}

#[cfg(target_os = "android")]
pub(crate) fn install_packaged_control_panel_mode(
    defaults: &crate::native_app_settings::NativeAppSettingsDefaults,
) {
    let _ = PACKAGED_CONTROL_PANEL_MODE.set(defaults.lookup(PROP_CONTROL_PANEL_MODE));
}

#[cfg(target_os = "android")]
pub(crate) fn packaged_control_panel_mode_is_breath_mapping(
    defaults: &crate::native_app_settings::NativeAppSettingsDefaults,
) -> bool {
    defaults
        .lookup(PROP_CONTROL_PANEL_MODE)
        .as_deref()
        .is_some_and(|value| value == "breath-mapping")
}

#[cfg(target_os = "android")]
pub(crate) fn admit_explicit_native_activity_launch(
    state: &android_activity::OnCreateState,
) -> Result<Option<u64>, String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };

    const AUTHORITY_CLASS_NAME: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.NativeRendererExperimentLaunchAuthority";
    let vm = unsafe { JavaVM::from_raw(state.vm_as_ptr().cast()) };
    let activity = state.activity_as_ptr() as jni::sys::jobject;
    let recreation = !state.saved_state().is_empty();
    vm.attach_current_thread(|env| -> jni::errors::Result<u64> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let class_loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let class_loader: JClassLoader = env.cast_local::<JClassLoader>(class_loader)?;
        let authority_name = env.new_string(AUTHORITY_CLASS_NAME)?;
        let authority = JClass::for_name_with_loader(env, authority_name, true, class_loader)?;
        let epoch = env
            .call_static_method(
                authority,
                jni_str!("issueFromNativeActivity"),
                jni_sig!("(Landroid/app/Activity;Z)J"),
                &[JValue::Object(&activity), JValue::Bool(recreation)],
            )?
            .j()?;
        Ok(epoch.max(0) as u64)
    })
    .map(|epoch| if epoch == 0 { None } else { Some(epoch) })
    .map_err(|error| format!("experiment NativeActivity launch admission failed: {error}"))
}
#[cfg(target_os = "android")]
const EXTRA_DRIVER_PROFILE_SESSION_STARTUP_RESET: &str =
    "spatial_camera_panel_session_startup_reset";
#[cfg(target_os = "android")]
const PANEL_COMMAND_POLL_INTERVAL_FRAMES: u64 = 30;

#[cfg(target_os = "android")]
#[derive(Debug, Default)]
pub(crate) struct ControlPanelCommandPoller {
    last_control_receipt_generation: u64,
    last_control_receipt_revision: u64,
    last_open_token: String,
    startup_open_sent: bool,
    explicit_experiment_startup: ExplicitExperimentPanelStartupGate,
}

#[cfg(target_os = "android")]
impl ControlPanelCommandPoller {
    pub(crate) fn poll_and_apply(&mut self, app: &android_activity::AndroidApp, frame_count: u64) {
        if let Some((_, generation, revision, event)) =
            crate::experiment_session_runtime::current_control_receipt()
        {
            if generation > self.last_control_receipt_generation {
                self.last_control_receipt_generation = generation;
                self.last_control_receipt_revision = 0;
            }
            if generation == self.last_control_receipt_generation
                && revision > self.last_control_receipt_revision
            {
                if generation > 0 {
                    match apply_condition_audio_control_receipt(app, generation, revision, event) {
                        Ok(()) => self.last_control_receipt_revision = revision,
                        Err(error) => {
                            // Retry the same receipt; the app-lifetime Java owner deduplicates it.
                            if frame_count % PANEL_COMMAND_POLL_INTERVAL_FRAMES == 0 {
                                crate::marker(
                                    "experiment-control",
                                    format!(
                                        "status=audio-control-bridge-error reason={}",
                                        crate::sanitize(&error)
                                    ),
                                );
                            }
                        }
                    }
                }
            }
        }
        if frame_count % PANEL_COMMAND_POLL_INTERVAL_FRAMES != 0 {
            return;
        }
        if !self.startup_open_sent && control_panel_mode_is_spatial_camera_panel_session() {
            self.startup_open_sent = true;
            crate::marker(
                "stimulus-panel",
                format!(
                    "event=control-panel-startup-open status=intent-dispatching frame={} panelActivity=ControlPanelActivity action=open source=xr-startup-driver-profile-session",
                    frame_count
                ),
            );
            match open_control_panel_impl_with_startup_reset(app) {
                Ok(()) => crate::marker(
                    "stimulus-panel",
                    format!(
                        "event=control-panel-startup-open status=intent-returned frame={} panelActivity=ControlPanelActivity action=open source=xr-startup-driver-profile-session",
                        frame_count
                    ),
                ),
                Err(error) => crate::marker(
                    "stimulus-panel",
                    format!(
                        "event=control-panel-startup-open status=intent-error frame={} source=xr-startup-driver-profile-session reason={}",
                        frame_count,
                        crate::sanitize(&error)
                    ),
                ),
            }
            return;
        }
        let mut property = android_properties::getprop(PROP_CONTROL_PANEL_OPEN_TOKEN);
        let token = property
            .value()
            .map(|value| value.trim().to_string())
            .unwrap_or_default();
        if token.is_empty() || token == self.last_open_token {
            return;
        }
        self.last_open_token = token.clone();
        match open_control_panel_impl(app) {
            Ok(()) => crate::marker(
                "stimulus-panel",
                format!(
                    "event=control-panel-open-command status=intent-sent frame={} panelActivity=ControlPanelActivity action=open source=runtime-polled-property openToken={}",
                    frame_count,
                    crate::sanitize(&token)
                ),
            ),
            Err(error) => crate::marker(
                "stimulus-panel",
                format!(
                    "event=control-panel-open-command status=intent-error frame={} reason={}",
                    frame_count,
                    crate::sanitize(&error)
                ),
            ),
        }
    }

    pub(crate) fn after_current_session_frame_submitted(
        &mut self,
        app: &android_activity::AndroidApp,
        openxr_session_generation: u64,
        submitted_frame: u64,
    ) {
        if self.explicit_experiment_startup.is_closed() {
            return;
        }
        if !packaged_control_panel_mode_is_breath_mapping_installed() {
            self.explicit_experiment_startup.close();
            return;
        }
        let pending_epoch = match pending_experiment_launch_epoch(app) {
            Ok(epoch) => epoch,
            Err(error) => {
                self.explicit_experiment_startup.close();
                crate::marker(
                    "experiment-session-panel",
                    format!(
                        "event=control-panel-startup-open status=authority-error openxrSessionGeneration={} submittedFrame={} route=experimenter source=validated-immersive-cold-launch reason={}",
                        openxr_session_generation,
                        submitted_frame,
                        crate::sanitize(&error)
                    ),
                );
                return;
            }
        };
        let Some(epoch) = self
            .explicit_experiment_startup
            .after_submitted_frame(pending_epoch, openxr_session_generation)
        else {
            return;
        };
        crate::marker(
            "experiment-session-panel",
            format!(
                "event=control-panel-startup-open status=intent-dispatching openxrSessionGeneration={} submittedFrame={} panelActivity=ControlPanelActivity route=experimenter source=validated-immersive-cold-launch launchEpoch={}",
                openxr_session_generation, submitted_frame, epoch
            ),
        );
        match open_experimenter_panel_from_explicit_launch(app, epoch) {
            Ok(()) => crate::marker(
                "experiment-session-panel",
                format!(
                    "event=control-panel-startup-open status=intent-returned openxrSessionGeneration={} submittedFrame={} panelActivity=ControlPanelActivity route=experimenter source=validated-immersive-cold-launch launchEpoch={}",
                    openxr_session_generation, submitted_frame, epoch
                ),
            ),
            Err(error) => crate::marker(
                "experiment-session-panel",
                format!(
                    "event=control-panel-startup-open status=intent-error openxrSessionGeneration={} submittedFrame={} route=experimenter source=validated-immersive-cold-launch reason={}",
                    openxr_session_generation,
                    submitted_frame,
                    crate::sanitize(&error)
                ),
            ),
        }
    }
}

#[cfg(target_os = "android")]
fn apply_condition_audio_control_receipt(
    app: &android_activity::AndroidApp,
    generation: u64,
    revision: u64,
    event: &str,
) -> Result<(), String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let loader: JClassLoader = env.cast_local::<JClassLoader>(loader)?;
        let name = env
            .new_string("io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity")?;
        let class = JClass::for_name_with_loader(env, name, true, loader)?;
        let event = env.new_string(event)?;
        env.call_static_method(
            class,
            jni_str!("applyConditionAudioControlReceipt"),
            jni_sig!("(JJLjava/lang/String;)V"),
            &[
                JValue::Long(generation as i64),
                JValue::Long(revision as i64),
                JValue::Object(&event),
            ],
        )?;
        Ok(())
    })
    .map_err(|error| format!("condition-audio-control-receipt:{error}"))
}

#[derive(Debug, Default)]
struct ExplicitExperimentPanelStartupGate {
    closed: bool,
}

impl ExplicitExperimentPanelStartupGate {
    fn after_submitted_frame(
        &mut self,
        pending_epoch: Option<u64>,
        openxr_session_generation: u64,
    ) -> Option<u64> {
        if self.closed || openxr_session_generation == 0 {
            return None;
        }
        self.closed = true;
        pending_epoch.filter(|value| *value > 0)
    }

    fn close(&mut self) {
        self.closed = true;
    }

    fn is_closed(&self) -> bool {
        self.closed
    }
}

#[cfg(target_os = "android")]
fn packaged_control_panel_mode_is_breath_mapping_installed() -> bool {
    PACKAGED_CONTROL_PANEL_MODE
        .get()
        .and_then(|value| value.as_deref())
        .is_some_and(|value| value == "breath-mapping")
}

#[cfg(target_os = "android")]
pub(crate) fn toggle_control_panel(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    source: &str,
) {
    if request_close_visible_control_panel(app, frame_count, source) {
        return;
    }
    match toggle_control_panel_impl(app) {
        Ok(()) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-toggle status=intent-sent frame={} panelActivity=ControlPanelActivity action=toggle source={}",
                frame_count,
                crate::sanitize(source),
            ),
        ),
        Err(error) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-toggle status=intent-error frame={} source={} reason={}",
                frame_count,
                crate::sanitize(source),
                crate::sanitize(&error)
            ),
        ),
    }
}

#[cfg(target_os = "android")]
pub(crate) fn request_close_visible_control_panel(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    source: &str,
) -> bool {
    match request_close_visible_control_panel_impl(app) {
        Ok(true) => {
            crate::marker(
                "stimulus-panel",
                format!(
                    "event=control-panel-toggle status=close-dispatched frame={} panelActivity=ControlPanelActivity route=same-process-visible-panel source={}",
                    frame_count,
                    crate::sanitize(source)
                ),
            );
            true
        }
        Ok(false) => false,
        Err(error) => {
            crate::marker(
                "stimulus-panel",
                format!(
                    "event=control-panel-toggle status=close-probe-error frame={} source={} reason={}",
                    frame_count,
                    crate::sanitize(source),
                    crate::sanitize(&error)
                ),
            );
            false
        }
    }
}

#[cfg(target_os = "android")]
pub(crate) fn open_control_panel(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    source: &str,
) {
    match open_control_panel_impl(app) {
        Ok(()) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-sent frame={} panelActivity=ControlPanelActivity action=open source={}",
                frame_count,
                crate::sanitize(source)
            ),
        ),
        Err(error) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-error frame={} source={} reason={}",
                frame_count,
                crate::sanitize(source),
                crate::sanitize(&error)
            ),
        ),
    }
}

#[cfg(target_os = "android")]
pub(crate) fn open_experimenter_panel(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    source: &str,
) {
    match send_control_panel_intent(
        app,
        ACTION_OPEN_EXPERIMENTER_PANEL,
        false,
        Some("experimenter"),
        None,
        None,
    ) {
        Ok(()) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-sent frame={} panelActivity=ControlPanelActivity route=experimenter source={}",
                frame_count,
                crate::sanitize(source)
            ),
        ),
        Err(error) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-error frame={} route=experimenter source={} reason={}",
                frame_count,
                crate::sanitize(source),
                crate::sanitize(&error)
            ),
        ),
    }
}

#[cfg(target_os = "android")]
pub(crate) fn stop_current_condition_audio_for_terminal(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    full_exit: bool,
    source: &str,
) {
    match stop_current_condition_audio_for_terminal_impl(app, full_exit) {
        Ok(()) => crate::marker(
            "experiment-session-audio-terminal",
            format!(
                "status=stop-dispatched frame={} fullExit={} source={}",
                frame_count,
                full_exit,
                crate::sanitize(source)
            ),
        ),
        Err(error) => crate::marker(
            "experiment-session-audio-terminal",
            format!(
                "status=stop-error frame={} fullExit={} source={} reason={}",
                frame_count,
                full_exit,
                crate::sanitize(source),
                crate::sanitize(&error)
            ),
        ),
    }
}

#[cfg(target_os = "android")]
pub(crate) fn open_experimenter_panel_with_receipt(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    source: &str,
    receipt: PanelRouteReceipt<'_>,
) {
    match send_control_panel_intent(
        app,
        ACTION_OPEN_EXPERIMENTER_PANEL,
        false,
        Some("experimenter"),
        Some(receipt),
        None,
    ) {
        Ok(()) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-sent frame={} panelActivity=ControlPanelActivity route=experimenter routeGeneration={} sessionGeneration={} source={}",
                frame_count,
                receipt.route_generation,
                receipt.session_generation.unwrap_or(0),
                crate::sanitize(source)
            ),
        ),
        Err(error) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-error frame={} route=experimenter routeGeneration={} source={} reason={}",
                frame_count,
                receipt.route_generation,
                crate::sanitize(source),
                crate::sanitize(&error)
            ),
        ),
    }
}

#[cfg(target_os = "android")]
pub(crate) fn open_developer_panel(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    source: &str,
) {
    match send_control_panel_intent(
        app,
        ACTION_OPEN_DEVELOPER_PANEL,
        false,
        Some("developer"),
        None,
        None,
    ) {
        Ok(()) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-sent frame={} panelActivity=ControlPanelActivity route=developer source={}",
                frame_count,
                crate::sanitize(source)
            ),
        ),
        Err(error) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-error frame={} route=developer source={} reason={}",
                frame_count,
                crate::sanitize(source),
                crate::sanitize(&error)
            ),
        ),
    }
}

#[cfg(target_os = "android")]
pub(crate) fn open_developer_panel_with_route_generation(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    source: &str,
    route_generation: u64,
) {
    let receipt = PanelRouteReceipt {
        route_generation,
        provenance: "native-menu-recall-v1",
        session_generation: None,
        operation_id: None,
    };
    match send_control_panel_intent(
        app,
        ACTION_OPEN_DEVELOPER_PANEL,
        false,
        Some("developer"),
        Some(receipt),
        None,
    ) {
        Ok(()) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-sent frame={} panelActivity=ControlPanelActivity route=developer routeGeneration={} source={}",
                frame_count,
                route_generation,
                crate::sanitize(source)
            ),
        ),
        Err(error) => crate::marker(
            "stimulus-panel",
            format!(
                "event=control-panel-open status=intent-error frame={} route=developer routeGeneration={} source={} reason={}",
                frame_count,
                route_generation,
                crate::sanitize(source),
                crate::sanitize(&error)
            ),
        ),
    }
}

#[cfg(target_os = "android")]
pub(crate) fn right_primary_opens_control_panel() -> bool {
    matches!(
        control_panel_mode().as_deref(),
        Some("driver-profile-session" | "private-layer-selector")
    )
}

#[cfg(target_os = "android")]
pub(crate) fn right_primary_control_panel_source() -> &'static str {
    match control_panel_mode().as_deref() {
        Some("private-layer-selector") => "right-primary-private-layer-selector",
        Some("driver-profile-session") => "right-primary-driver-profile-session",
        _ => "right-primary-control-panel",
    }
}

#[cfg(target_os = "android")]
fn control_panel_mode_is_spatial_camera_panel_session() -> bool {
    control_panel_mode()
        .as_deref()
        .is_some_and(|value| value == "driver-profile-session")
}

#[cfg(target_os = "android")]
fn control_panel_mode() -> Option<String> {
    let mut property = android_properties::getprop(PROP_CONTROL_PANEL_MODE);
    let explicit = property
        .value()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());
    crate::native_app_settings::explicit_or_packaged(
        explicit,
        PACKAGED_CONTROL_PANEL_MODE.get().cloned().flatten(),
    )
}

#[cfg(target_os = "android")]
fn toggle_control_panel_impl(app: &android_activity::AndroidApp) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_TOGGLE_PANEL, false, None, None, None)
}

#[cfg(target_os = "android")]
fn request_close_visible_control_panel_impl(
    app: &android_activity::AndroidApp,
) -> Result<bool, String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject},
        JavaVM,
    };

    const PANEL_CLASS_NAME: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity";

    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<bool> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let class_loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let class_loader: JClassLoader = env.cast_local::<JClassLoader>(class_loader)?;
        let panel_class_name = env.new_string(PANEL_CLASS_NAME)?;
        let panel_class = JClass::for_name_with_loader(env, panel_class_name, true, class_loader)?;
        env.call_static_method(
            panel_class,
            jni_str!("requestCloseVisiblePanelFromNative"),
            jni_sig!("()Z"),
            &[],
        )?
        .z()
    })
    .map_err(|error| format!("visible control panel close probe failed: {error}"))
}

#[cfg(target_os = "android")]
fn open_control_panel_impl(app: &android_activity::AndroidApp) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_OPEN_PANEL, false, None, None, None)
}

#[cfg(target_os = "android")]
fn open_control_panel_impl_with_startup_reset(
    app: &android_activity::AndroidApp,
) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_OPEN_PANEL, true, None, None, None)
}

#[cfg(target_os = "android")]
fn open_experimenter_panel_from_explicit_launch(
    app: &android_activity::AndroidApp,
    epoch: u64,
) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_MAIN, false, None, None, Some(epoch))
}

#[cfg(target_os = "android")]
fn stop_current_condition_audio_for_terminal_impl(
    app: &android_activity::AndroidApp,
    full_exit: bool,
) -> Result<(), String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };

    const PANEL_CLASS_NAME: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity";

    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let class_loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let class_loader: JClassLoader = env.cast_local::<JClassLoader>(class_loader)?;
        let panel_class_name = env.new_string(PANEL_CLASS_NAME)?;
        let panel_class = JClass::for_name_with_loader(env, panel_class_name, true, class_loader)?;
        env.call_static_method(
            panel_class,
            jni_str!("stopCurrentConditionAudioForTerminal"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(full_exit)],
        )?;
        Ok(())
    })
    .map_err(|error| format!("stop current condition audio for terminal failed: {error}"))
}

#[cfg(target_os = "android")]
fn send_control_panel_intent(
    app: &android_activity::AndroidApp,
    action_name: &str,
    spatial_camera_panel_session_startup_reset: bool,
    route: Option<&str>,
    route_receipt: Option<PanelRouteReceipt<'_>>,
    explicit_launch_epoch: Option<u64>,
) -> Result<(), String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };

    const PANEL_CLASS_NAME: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.ControlPanelActivity";
    const CATEGORY_2D: &str = "com.oculus.intent.category.2D";
    const FLAG_ACTIVITY_REORDER_TO_FRONT: i32 = 0x0002_0000;
    const FLAG_ACTIVITY_SINGLE_TOP: i32 = 0x2000_0000;

    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let intent_class = env.find_class(jni_str!("android/content/Intent"))?;
        let intent = env.new_object(intent_class, jni_sig!("()V"), &[])?;

        let action = env.new_string(action_name)?;
        env.call_method(
            &intent,
            jni_str!("setAction"),
            jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
            &[JValue::Object(&JObject::from(action))],
        )?;

        let category = env.new_string(CATEGORY_2D)?;
        env.call_method(
            &intent,
            jni_str!("addCategory"),
            jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
            &[JValue::Object(&JObject::from(category))],
        )?;

        let package_name = env.call_method(
            &activity,
            jni_str!("getPackageName"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?;
        let package_name = package_name.l()?;
        let panel_class_name = env.new_string(PANEL_CLASS_NAME)?;
        env.call_method(
            &intent,
            jni_str!("setClassName"),
            jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
            &[
                JValue::Object(&package_name),
                JValue::Object(&JObject::from(panel_class_name)),
            ],
        )?;

        env.call_method(
            &intent,
            jni_str!("addFlags"),
            jni_sig!("(I)Landroid/content/Intent;"),
            &[JValue::Int(
                FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_SINGLE_TOP,
            )],
        )?;
        if spatial_camera_panel_session_startup_reset {
            let extra_name = env.new_string(EXTRA_DRIVER_PROFILE_SESSION_STARTUP_RESET)?;
            env.call_method(
                &intent,
                jni_str!("putExtra"),
                jni_sig!("(Ljava/lang/String;Z)Landroid/content/Intent;"),
                &[
                    JValue::Object(&JObject::from(extra_name)),
                    JValue::Bool(true),
                ],
            )?;
        }
        if let Some(route) = route {
            let extra_name = env.new_string(EXTRA_PANEL_ROUTE)?;
            let extra_value = env.new_string(route)?;
            env.call_method(
                &intent,
                jni_str!("putExtra"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
                &[
                    JValue::Object(&JObject::from(extra_name)),
                    JValue::Object(&JObject::from(extra_value)),
                ],
            )?;
        }
        if let Some(receipt) = route_receipt {
            put_long_extra(
                env,
                &intent,
                EXTRA_PANEL_ROUTE_GENERATION,
                receipt.route_generation,
            )?;
            put_string_extra(
                env,
                &intent,
                EXTRA_PANEL_ROUTE_PROVENANCE,
                receipt.provenance,
            )?;
            if let Some(session_generation) = receipt.session_generation {
                put_long_extra(
                    env,
                    &intent,
                    EXTRA_PANEL_SESSION_GENERATION,
                    session_generation,
                )?;
            }
            if let Some(operation_id) = receipt.operation_id {
                put_string_extra(env, &intent, EXTRA_PANEL_OPERATION_ID, operation_id)?;
            }
        }
        if let Some(epoch) = explicit_launch_epoch {
            put_string_extra(
                env,
                &intent,
                EXTRA_LAUNCH_PROVENANCE,
                PROVENANCE_EXPLICIT_USER_LAUNCH,
            )?;
            put_long_extra(env, &intent, EXTRA_LAUNCH_EPOCH, epoch)?;
        }
        // The app's panel-open command selects the watchdog recovery target
        // before Quest briefly reveals another 2D task during the handoff.
        let class_loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let class_loader: JClassLoader = env.cast_local::<JClassLoader>(class_loader)?;
        let panel_class_name = env.new_string(PANEL_CLASS_NAME)?;
        let panel_class = JClass::for_name_with_loader(env, panel_class_name, true, class_loader)?;
        let desired_route = env.new_string(route.unwrap_or("experimenter"))?;
        env.call_static_method(
            panel_class,
            jni_str!("requestPanelPresentationFromNative"),
            jni_sig!("(Ljava/lang/String;)Z"),
            &[JValue::Object(&JObject::from(desired_route))],
        )?
        .z()?;
        env.call_method(
            &activity,
            jni_str!("startActivity"),
            jni_sig!("(Landroid/content/Intent;)V"),
            &[JValue::Object(&intent)],
        )?;
        Ok(())
    })
    .map_err(|error| format!("control panel intent failed: {error}"))
}

#[cfg(target_os = "android")]
fn pending_experiment_launch_epoch(
    app: &android_activity::AndroidApp,
) -> Result<Option<u64>, String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject},
        JavaVM,
    };

    const AUTHORITY_CLASS_NAME: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.NativeRendererExperimentLaunchAuthority";
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<u64> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let class_loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let class_loader: JClassLoader = env.cast_local::<JClassLoader>(class_loader)?;
        let authority_name = env.new_string(AUTHORITY_CLASS_NAME)?;
        let authority = JClass::for_name_with_loader(env, authority_name, true, class_loader)?;
        let epoch = env
            .call_static_method(
                authority,
                jni_str!("pendingEpochForImmersiveStartup"),
                jni_sig!("()J"),
                &[],
            )?
            .j()?;
        Ok(epoch.max(0) as u64)
    })
    .map(|epoch| if epoch == 0 { None } else { Some(epoch) })
    .map_err(|error| format!("experiment launch authority readback failed: {error}"))
}

#[cfg(target_os = "android")]
fn put_long_extra(
    env: &mut jni::Env,
    intent: &jni::objects::JObject,
    name: &str,
    value: u64,
) -> jni::errors::Result<()> {
    use jni::{
        jni_sig, jni_str,
        objects::{JObject, JValue},
    };
    let name = env.new_string(name)?;
    env.call_method(
        intent,
        jni_str!("putExtra"),
        jni_sig!("(Ljava/lang/String;J)Landroid/content/Intent;"),
        &[
            JValue::Object(&JObject::from(name)),
            JValue::Long(value as i64),
        ],
    )?;
    Ok(())
}

#[cfg(target_os = "android")]
fn put_string_extra(
    env: &mut jni::Env,
    intent: &jni::objects::JObject,
    name: &str,
    value: &str,
) -> jni::errors::Result<()> {
    use jni::{
        jni_sig, jni_str,
        objects::{JObject, JValue},
    };
    let name = env.new_string(name)?;
    let value = env.new_string(value)?;
    env.call_method(
        intent,
        jni_str!("putExtra"),
        jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
        &[
            JValue::Object(&JObject::from(name)),
            JValue::Object(&JObject::from(value)),
        ],
    )?;
    Ok(())
}

#[cfg(test)]
mod startup_gate_tests {
    use super::ExplicitExperimentPanelStartupGate;

    #[test]
    fn explicit_panel_never_dispatches_without_a_current_openxr_session() {
        let mut gate = ExplicitExperimentPanelStartupGate::default();
        assert_eq!(gate.after_submitted_frame(Some(17), 0), None);
        assert_eq!(gate.after_submitted_frame(Some(17), 1), Some(17));
    }

    #[test]
    fn explicit_panel_dispatches_exactly_once_after_submission() {
        let mut gate = ExplicitExperimentPanelStartupGate::default();
        assert_eq!(gate.after_submitted_frame(Some(23), 1), Some(23));
        assert_eq!(gate.after_submitted_frame(Some(24), 2), None);
    }

    #[test]
    fn absent_ticket_at_first_submission_closes_against_later_drift() {
        let mut gate = ExplicitExperimentPanelStartupGate::default();
        assert_eq!(gate.after_submitted_frame(None, 1), None);
        assert_eq!(gate.after_submitted_frame(Some(23), 1), None);
    }

    #[test]
    fn authority_error_closes_the_startup_gate() {
        let mut gate = ExplicitExperimentPanelStartupGate::default();
        gate.close();
        assert_eq!(gate.after_submitted_frame(Some(31), 1), None);
    }
}
