//! JNI bridge for launching the same-APK 2D control panel.

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
fn toggle_control_panel_impl(app: &android_activity::AndroidApp) -> Result<(), String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JObject, JValue},
        JavaVM,
    };

    const ACTION_TOGGLE_PANEL: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.action.TOGGLE_PANEL";
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

        let action = env.new_string(ACTION_TOGGLE_PANEL)?;
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
        env.call_method(
            &activity,
            jni_str!("startActivity"),
            jni_sig!("(Landroid/content/Intent;)V"),
            &[JValue::Object(&intent)],
        )?;
        Ok(())
    })
    .map_err(|error| format!("toggle control panel failed: {error}"))
}
