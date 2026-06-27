//! JNI bridge for launching the same-APK 2D control panel.

#[cfg(target_os = "android")]
const ACTION_OPEN_PANEL: &str =
    "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_PANEL";
#[cfg(target_os = "android")]
const ACTION_TOGGLE_PANEL: &str =
    "io.github.mesmerprism.rustyquest.native_renderer.action.TOGGLE_PANEL";
#[cfg(target_os = "android")]
const PROP_CONTROL_PANEL_OPEN_TOKEN: &str =
    "debug.rustyquest.native_renderer.control_panel.open_token";
#[cfg(target_os = "android")]
const PROP_CONTROL_PANEL_MODE: &str = "debug.rustyquest.native_renderer.control_panel.mode";
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
pub(crate) fn toggle_control_panel(app: &android_activity::AndroidApp, frame_count: u64) {
    match toggle_control_panel_impl(app) {
        Ok(()) => crate::marker(
            "stimulus-panel",
            format!(
                "event=right-trigger-panel-toggle status=intent-sent frame={} panelActivity=ControlPanelActivity action=toggle",
                frame_count
            ),
        ),
        Err(error) => crate::marker(
            "stimulus-panel",
            format!(
                "event=right-trigger-panel-toggle status=intent-error frame={} reason={}",
                frame_count,
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
    property
        .value()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

#[cfg(target_os = "android")]
fn toggle_control_panel_impl(app: &android_activity::AndroidApp) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_TOGGLE_PANEL, false)
}

#[cfg(target_os = "android")]
fn open_control_panel_impl(app: &android_activity::AndroidApp) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_OPEN_PANEL, false)
}

#[cfg(target_os = "android")]
fn open_control_panel_impl_with_startup_reset(
    app: &android_activity::AndroidApp,
) -> Result<(), String> {
    send_control_panel_intent(app, ACTION_OPEN_PANEL, true)
}

#[cfg(target_os = "android")]
fn send_control_panel_intent(
    app: &android_activity::AndroidApp,
    action_name: &str,
    spatial_camera_panel_session_startup_reset: bool,
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
