//! JNI bridge for starting same-process stereo video playback.

use std::ffi::c_void;

use crate::native_renderer_video_projection_options::NativeVideoProjectionSettings;

const PLAYBACK_BINARY_CLASS_NAME: &str =
    "io.github.mesmerprism.rustyquest.native_renderer.StereoVideoPlayback";

pub(crate) struct StereoVideoPlaybackGuard {
    vm: *mut c_void,
}

impl Drop for StereoVideoPlaybackGuard {
    fn drop(&mut self) {
        if let Err(error) = stop_impl(self.vm) {
            crate::marker(
                "video-projection-playback",
                format!("status=stop-error reason={}", crate::sanitize(&error)),
            );
        }
    }
}

pub(crate) fn start_if_enabled(
    app: &android_activity::AndroidApp,
    settings: &NativeVideoProjectionSettings,
) -> Option<StereoVideoPlaybackGuard> {
    if !settings.active() {
        crate::marker(
            "video-projection-playback",
            format!("status=disabled active=false {}", settings.marker_fields()),
        );
        return None;
    }

    match start_impl(app, settings) {
        Ok(()) => {
            crate::marker(
                "video-projection-playback",
                format!(
                    "status=start-request-sent active=true {}",
                    settings.marker_fields()
                ),
            );
            Some(StereoVideoPlaybackGuard {
                vm: app.vm_as_ptr().cast(),
            })
        }
        Err(error) => {
            crate::marker(
                "video-projection-playback",
                format!(
                    "status=start-error reason={} active=false {}",
                    crate::sanitize(&error),
                    settings.marker_fields()
                ),
            );
            None
        }
    }
}

fn start_impl(
    app: &android_activity::AndroidApp,
    settings: &NativeVideoProjectionSettings,
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
        let class_loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let class_loader: JClassLoader = env.cast_local::<JClassLoader>(class_loader)?;
        let playback_class_name = env.new_string(PLAYBACK_BINARY_CLASS_NAME)?;
        let playback_class =
            JClass::for_name_with_loader(env, playback_class_name, true, class_loader)?;
        let path = env.new_string(settings.path.as_str())?;
        env.call_static_method(
            playback_class,
            jni_str!("start"),
            jni_sig!("(Landroid/content/Context;Ljava/lang/String;IIIIZ)V"),
            &[
                JValue::Object(&activity),
                JValue::Object(&JObject::from(path)),
                JValue::Int(settings.width as i32),
                JValue::Int(settings.height as i32),
                JValue::Int(settings.max_images as i32),
                JValue::Int(settings.fps_cap as i32),
                JValue::Bool(settings.looping),
            ],
        )?;
        Ok(())
    })
    .map_err(|error| format!("start stereo video playback failed: {error}"))
}

fn stop_impl(vm: *mut c_void) -> Result<(), String> {
    use jni::{jni_sig, jni_str, JavaVM};

    if vm.is_null() {
        return Ok(());
    }
    let vm = unsafe { JavaVM::from_raw(vm.cast()) };
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let playback_class = env.find_class(jni_str!(
            "io/github/mesmerprism/rustyquest/native_renderer/StereoVideoPlayback"
        ))?;
        env.call_static_method(playback_class, jni_str!("stop"), jni_sig!("()V"), &[])?;
        Ok(())
    })
    .map_err(|error| format!("stop stereo video playback failed: {error}"))
}
