//! Capability-free native panel lifecycle adapter for the visual composition.
//! No experiment routing, packaged session loading or file polling lives here.
use crate::native_app_settings::NativeAppSettingsDefaults;
pub(crate) fn install_packaged_control_panel_mode(_: &NativeAppSettingsDefaults) {}
pub(crate) fn packaged_control_panel_mode_is_breath_mapping(_: &NativeAppSettingsDefaults) -> bool {
    cfg!(feature = "showcase-vulkan-ui")
}
pub(crate) fn packaged_control_panel_mode_is_breath_mapping_installed() -> bool {
    // Compatibility query used only for same-owner presentation/input routing.
    // This does not select an experiment owner or session policy.
    cfg!(feature = "showcase-vulkan-ui")
}
pub(crate) fn right_primary_opens_control_panel() -> bool {
    false
}
pub(crate) fn right_primary_control_panel_source() -> &'static str {
    "showcase"
}

#[cfg(test)]
mod presentation_selection_tests {
    #[test]
    fn showcase_ui_selects_same_owner_product_pointer_routing_only_when_opted_in() {
        assert_eq!(
            super::packaged_control_panel_mode_is_breath_mapping_installed(),
            cfg!(feature = "showcase-vulkan-ui")
        );
    }
}
#[derive(Default)]
pub(crate) struct ControlPanelCommandPoller;
#[cfg(target_os = "android")]
impl ControlPanelCommandPoller {
    pub(crate) fn poll_and_apply(&mut self, _: &android_activity::AndroidApp, _: u64) {}
    pub(crate) fn after_current_session_frame_submitted(
        &mut self,
        _: &android_activity::AndroidApp,
        _: u64,
        _: u64,
    ) {
    }
}
#[cfg(target_os = "android")]
pub(crate) fn call(app: &android_activity::AndroidApp, method: &str) -> Result<(), String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let raw = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&raw)? };
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
            .new_string("io.github.mesmerprism.rustyquest.native_renderer.ShowcaseNativeBridge")?;
        let class = JClass::for_name_with_loader(env, name, true, loader)?;
        // Closed call names; JNI never resolves caller-provided methods.
        match method {
            "initialize" => {
                env.call_static_method(
                    class,
                    jni_str!("initialize"),
                    jni_sig!("(Landroid/content/Context;)V"),
                    &[JValue::Object(&activity)],
                )?;
            }
            "open" => {
                env.call_static_method(
                    class,
                    jni_str!("openPanel"),
                    jni_sig!("(Landroid/content/Context;)V"),
                    &[JValue::Object(&activity)],
                )?;
            }
            "toggle" => {
                env.call_static_method(
                    class,
                    jni_str!("togglePanel"),
                    jni_sig!("(Landroid/content/Context;)V"),
                    &[JValue::Object(&activity)],
                )?;
            }
            "resume" => {
                env.call_static_method(class, jni_str!("resume"), jni_sig!("()V"), &[])?;
            }
            "suspend" => {
                env.call_static_method(class, jni_str!("suspend"), jni_sig!("()V"), &[])?;
            }
            "shutdown" => {
                env.call_static_method(class, jni_str!("shutdown"), jni_sig!("()V"), &[])?;
            }
            _ => {}
        }
        Ok(())
    })
    .map_err(|e| format!("showcase_panel:{e}"))
}
#[cfg(target_os = "android")]
pub(crate) fn prepare_app_owner(app: &android_activity::AndroidApp) -> Result<(), String> {
    call(app, "initialize")
}
#[cfg(target_os = "android")]
pub(crate) fn open_control_panel(app: &android_activity::AndroidApp, _: u64, _: &str) {
    let _ = call(app, "open");
}
#[cfg(target_os = "android")]
pub(crate) fn toggle_control_panel(app: &android_activity::AndroidApp, _: u64, _: &str) {
    let _ = call(app, "toggle");
}
#[cfg(target_os = "android")]
pub(crate) fn request_close_visible_control_panel(
    _: &android_activity::AndroidApp,
    _: u64,
    _: &str,
) {
}
#[cfg(target_os = "android")]
pub(crate) fn observed_activity_class_name(
    app: &android_activity::AndroidApp,
) -> Result<String, String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JObject, JString},
        JavaVM,
    };
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let raw = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<String> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&raw)? };
        let class = env
            .call_method(
                activity,
                jni_str!("getClass"),
                jni_sig!("()Ljava/lang/Class;"),
                &[],
            )?
            .l()?;
        let name = env
            .call_method(
                class,
                jni_str!("getName"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        env.cast_local::<JString>(name)?.try_to_string(env)
    })
    .map_err(|e| e.to_string())
}
