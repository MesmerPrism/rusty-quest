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
const EXTRA_DRIVER_PROFILE_SESSION_STARTUP_RESET: &str =
    "spatial_camera_panel_session_startup_reset";
#[cfg(target_os = "android")]
const PANEL_COMMAND_POLL_INTERVAL_FRAMES: u64 = 30;

#[cfg(target_os = "android")]
#[derive(Debug, Default)]
pub(crate) struct ControlPanelCommandPoller {
    last_open_token: String,
    startup_open_sent: bool,
}

#[cfg(target_os = "android")]
impl ControlPanelCommandPoller {
    pub(crate) fn poll_and_apply(&mut self, app: &android_activity::AndroidApp, frame_count: u64) {
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
}

#[cfg(target_os = "android")]
pub(crate) fn toggle_control_panel(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    source: &str,
) {
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
    send_control_panel_intent(app, ACTION_TOGGLE_PANEL, false, None, None)
}

#[cfg(target_os = "android")]
fn open_control_panel_impl(app: &android_activity::AndroidApp) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_OPEN_PANEL, false, None, None)
}

#[cfg(target_os = "android")]
fn open_control_panel_impl_with_startup_reset(
    app: &android_activity::AndroidApp,
) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_OPEN_PANEL, true, None, None)
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
) -> Result<(), String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JObject, JValue},
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
