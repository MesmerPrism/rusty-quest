//! Internal JNI acquisition for the native writer. The fixed app-owned callback
//! reads its held channel; no operator JSON/path/exclusivity assertion is accepted.
use super::process_fence::{AppFenceSource, NativeCapability, NativeFenceRegistry};
use jni::objects::{GlobalRef, JClass, JObject, JString};
use jni::sys::jstring;
use jni::{JNIEnv, JavaVM};
use std::path::Path;
use std::sync::{Arc, OnceLock};

static REGISTRY: OnceLock<NativeFenceRegistry> = OnceLock::new();
fn registry() -> &'static NativeFenceRegistry {
    REGISTRY.get_or_init(NativeFenceRegistry::default)
}
pub(super) fn ownership_status() -> Result<serde_json::Value, String> {
    registry().ownership_status()
}
pub(super) fn active() -> Result<Arc<NativeCapability>, String> {
    registry().active()
}
pub(super) fn generation_current(generation: u64) -> bool {
    active().is_ok_and(|cap| cap.generation == generation)
}
struct JavaAppFenceSource {
    vm: JavaVM,
    callback: GlobalRef,
}
impl AppFenceSource for JavaAppFenceSource {
    fn record(&self) -> Result<String, String> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| "native app channel attach failed")?;
        callback_string(
            &mut env,
            self.callback.as_obj(),
            "nativeAdmissionRecord",
            512,
        )
    }
}
fn callback_string(
    env: &mut JNIEnv<'_>,
    callback: &JObject<'_>,
    method: &str,
    max: usize,
) -> Result<String, String> {
    let result = env.call_method(callback, method, "()Ljava/lang/String;", &[]);
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
        return Err("native app fence callback unavailable".into());
    }
    let object = result
        .map_err(|_| "native app fence callback failed")?
        .l()
        .map_err(|_| "native app fence callback type")?;
    if object.is_null() {
        return Err("native app fence callback absent".into());
    }
    let string = JString::from(object);
    let text: String = env
        .get_string(&string)
        .map_err(|_| "native app fence callback encoding")?
        .into();
    if text.is_empty() || text.len() > max {
        return Err("native app fence callback bounds".into());
    }
    Ok(text)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_claimNativeProcessFence(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    callback: JObject<'_>,
) -> jstring {
    let result: Result<String, String> =
        (|| {
            if callback.is_null() {
                return Err("native app fence callback absent".into());
            }
            let directory = callback_string(&mut env, &callback, "nativeFenceDirectory", 4096)?;
            let source = Arc::new(JavaAppFenceSource {
                vm: env
                    .get_java_vm()
                    .map_err(|_| "native app fence VM failed")?,
                callback: env
                    .new_global_ref(callback)
                    .map_err(|_| "native app fence capture failed")?,
            });
            let cap = registry().claim(Path::new(&directory), source)?;
            cap.require_live()?;
            Ok(serde_json::json!({"$schema":"rusty.quest.embedded_duplex.native_fence_admitted.v1",
            "app_generation":cap.binding.generation,"app_record_sha256":cap.binding.record_sha256,
            "executor_generation":cap.generation}).to_string())
        })();
    match result {
        Ok(text) => env
            .new_string(text)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(reason) => {
            let _ = env.throw_new("java/lang/IllegalStateException", reason);
            std::ptr::null_mut()
        }
    }
}

/// Actual pre-claim process absence: no synthetic executor generation/capability.
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_retireNeverClaimedNoMedia(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    callback: JObject<'_>,
) -> jstring {
    let result: Result<String, String> = (|| {
        if callback.is_null() {
            return Err("never-claimed app callback absent".into());
        }
        super::runtime_host::retire_never_claimed_process_gate()?;
        let source = Arc::new(JavaAppFenceSource {
            vm: env.get_java_vm().map_err(|_| "never-claimed app VM")?,
            callback: env
                .new_global_ref(callback)
                .map_err(|_| "never-claimed app callback capture")?,
        });
        let binding = registry().retire_never_claimed(source)?;
        Ok(
            serde_json::json!({"$schema":"rusty.quest.embedded_duplex.no_media_retirement.v1",
            "native_runtime_binding":"not_claimed","disposition":"never_claimed_process_closed",
            "app_process_generation":binding.generation,"app_record_sha256":binding.record_sha256})
            .to_string(),
        )
    })();
    match result {
        Ok(text) => env
            .new_string(text)
            .map(|v| v.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(reason) => {
            let _ = env.throw_new("java/lang/IllegalStateException", reason);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_noMediaRendererRetirementStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    match crate::camera_hwb_probe::no_media_renderer_retirement_status() {
        Ok(value) => env
            .new_string(value.to_string())
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_freshSetupRendererEligibilityStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    match crate::camera_hwb_probe::fresh_setup_renderer_eligibility_status() {
        Ok(value) => env
            .new_string(value.to_string())
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            std::ptr::null_mut()
        }
    }
}
