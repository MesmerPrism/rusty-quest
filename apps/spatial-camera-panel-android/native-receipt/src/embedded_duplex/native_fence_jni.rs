//! Internal JNI acquisition for the native writer. The fixed app-owned callback
//! reads its held channel; no operator JSON/path/exclusivity assertion is accepted.
use super::process_fence::{
    validate_operational_run_handoff, AppBinding, AppFenceSource, NativeCapability,
    NativeFenceRegistry, OperationalRunInspection,
};
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
    record_method: &'static str,
}
impl AppFenceSource for JavaAppFenceSource {
    fn record(&self) -> Result<String, String> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| "native app channel attach failed")?;
        callback_string(&mut env, self.callback.as_obj(), self.record_method, 512)
    }
    fn inspection_record(&self) -> Result<String, String> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| "native app inspection attach failed")?;
        callback_string(
            &mut env,
            self.callback.as_obj(),
            "nativeInspectionRecord",
            512,
        )
    }
    fn operational_run_handoff(&self) -> Result<Option<String>, String> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| "native handoff callback attach failed")?;
        let result = env.call_method(
            self.callback.as_obj(),
            "nativeOperationalRunHandoff",
            "()Ljava/lang/String;",
            &[],
        );
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
            return Err("native handoff source unavailable".into());
        }
        let object = result
            .map_err(|_| "native handoff callback failed")?
            .l()
            .map_err(|_| "native handoff callback type")?;
        if object.is_null() {
            return Ok(None);
        }
        let raw: String = env
            .get_string(&JString::from(object))
            .map_err(|_| "native handoff encoding")?
            .into();
        if raw.is_empty() {
            return Ok(None);
        }
        if raw.len() > 256 * 1024 {
            return Err("native handoff callback bounds".into());
        }
        Ok(Some(raw))
    }
}

fn source_handoff_available(
    source: &dyn AppFenceSource,
    binding: &AppBinding,
    pending: bool,
    counter: Option<u64>,
) -> Result<bool, String> {
    let Some(counter) = counter else {
        return Ok(false);
    };
    let Some(raw) = source.operational_run_handoff()? else {
        return Ok(false);
    };
    validate_operational_run_handoff(&raw, binding, pending, counter)?;
    Ok(true)
}

fn admitted_value(cap: &NativeCapability) -> Result<String, String> {
    cap.require_live()?;
    let mut value = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.native_fence_admitted.v1",
        "app_generation":cap.binding.generation,"app_record_sha256":cap.binding.record_sha256,"executor_generation":cap.generation});
    if let Some(run) = &cap.binding.operational_run_id {
        value["operational_run_id"] = serde_json::json!(run);
    }
    Ok(value.to_string())
}

fn capture_source(
    env: &mut JNIEnv<'_>,
    callback: JObject<'_>,
) -> Result<Arc<JavaAppFenceSource>, String> {
    if callback.is_null() {
        return Err("native app fence callback absent".into());
    }
    Ok(Arc::new(JavaAppFenceSource {
        record_method: "nativeAdmissionRecord",
        vm: env
            .get_java_vm()
            .map_err(|_| "native app fence VM failed")?,
        callback: env
            .new_global_ref(callback)
            .map_err(|_| "native app fence capture failed")?,
    }))
}

fn inspection_projection(
    inspected: Result<OperationalRunInspection, String>,
    process: Result<bool, String>,
    pending: bool,
) -> serde_json::Value {
    let mut value = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.operational_run_native.v1",
        "operational_run_id":null,"globally_eligible":false,"native_generation":null});
    let mut refusal = pending.then(|| "app_global_effects_pending".to_owned());
    match inspected {
        Ok(actual) => {
            value["operational_run_id"] = serde_json::json!(actual
                .actual_binding
                .as_ref()
                .and_then(|b| b.operational_run_id.as_deref()));
            value["native_generation"] = serde_json::json!(actual.native_generation);
            if let Some(reason) = actual.refusal {
                let completed = process.as_ref().is_ok_and(|completed| *completed);
                if !(reason == "native_capability_owned" && completed && !pending) {
                    refusal = Some(reason.into());
                }
            }
        }
        Err(_) => refusal = Some("native_registry_unavailable".into()),
    }
    if let Err(reason) = process {
        refusal = Some(reason);
    }
    if let Some(reason) = refusal {
        value["refusal_reason"] = serde_json::json!(reason);
    } else {
        value["globally_eligible"] = serde_json::json!(true);
    }
    value
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_inspectOperationalRun(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    callback: JObject<'_>,
) -> jstring {
    let result = (|| {
        if callback.is_null() {
            return Err("native app fence callback absent".into());
        }
        let directory = callback_string(&mut env, &callback, "nativeInspectionDirectory", 4096)?;
        let source = capture_source(&mut env, callback)?;
        let first = source.inspection_record()?;
        let (binding, pending) = AppBinding::inspect(&first)?;
        let mut inspected = registry().inspect_operational_run(Path::new(&directory), &binding);
        if let Ok(actual) = &mut inspected {
            if actual.refusal == Some("native_prior_process_owner_unreconciled") {
                match source_handoff_available(
                    source.as_ref(),
                    &binding,
                    pending,
                    actual.native_generation,
                ) {
                    Ok(true) => actual.refusal = None,
                    Ok(false) => {}
                    Err(_) => actual.refusal = Some("native_operational_handoff_invalid"),
                }
            }
        }
        let process = super::runtime_host::operational_run_eligibility();
        let mut value = inspection_projection(inspected, process, pending);
        // The same live held app channel must bracket all Native observations.
        if source.inspection_record()? != first {
            value["globally_eligible"] = serde_json::json!(false);
            value["refusal_reason"] =
                serde_json::json!("app_global_fence_changed_during_inspection");
        }
        Ok(value.to_string())
    })();
    super::runtime_host::return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_readColdPendingOwner(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    callback: JObject<'_>,
) -> jstring {
    let result = (|| {
        let directory = callback_string(&mut env, &callback, "nativeInspectionDirectory", 4096)?;
        let source = capture_source(&mut env, callback)?;
        let before = source.inspection_record()?;
        let value = super::process_fence::cold_pending_owner(Path::new(&directory))?;
        if before != source.inspection_record()? {
            return Err("cold owner app fence changed".into());
        }
        Ok(value.to_string())
    })();
    super::runtime_host::return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_retireColdPendingLocal(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    callback: JObject<'_>,
) -> jstring {
    let result = (|| {
        let directory = callback_string(&mut env, &callback, "nativeInspectionDirectory", 4096)?;
        let link = callback_string(&mut env, &callback, "nativeColdRecoveryEvidence", 16384)?;
        let mut source = capture_source(&mut env, callback)?;
        Arc::get_mut(&mut source)
            .ok_or("cold source unexpectedly shared")?
            .record_method = "nativeColdRecoveryRecord";
        let record = source.record()?;
        // These are current Native owners, never a recreated registry/old counter absence inference.
        super::runtime_host::retire_never_claimed_process_gate()?;
        super::runtime_host::operational_run_eligibility()?;
        let receipt =
            registry().retire_cold_pending_local(Path::new(&directory), &record, &link)?;
        // All Java/physical callbacks are outside registry/flock commit locks.
        if source.record()? != record || source.inspection_record()? != record {
            return Err("cold retirement held app source changed".into());
        }
        let mut check = source
            .vm
            .attach_current_thread()
            .map_err(|_| "cold verification attach")?;
        if callback_string(
            &mut check,
            source.callback.as_obj(),
            "nativeColdRecoveryEvidence",
            16384,
        )? != link
        {
            return Err("cold retirement local owner changed".into());
        }
        Ok(receipt)
    })();
    super::runtime_host::return_string(&mut env, result)
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
    let result: Result<String, String> = (|| {
        if callback.is_null() {
            return Err("native app fence callback absent".into());
        }
        let directory = callback_string(&mut env, &callback, "nativeFenceDirectory", 4096)?;
        let source = capture_source(&mut env, callback)?;
        let binding = AppBinding::parse(&source.record()?)?;
        let cap = if binding.operational_run_id.is_some() {
            if let Some(cap) = registry().retry_selected_run(source.clone())? {
                super::runtime_host::adopt_operational_run_claim(&cap)?;
                return admitted_value(&cap);
            }
            let completed = super::runtime_host::operational_run_eligibility()?;
            let inspection = registry().inspect_operational_run(Path::new(&directory), &binding)?;
            if let Some(reason) = inspection.refusal {
                let handoff = reason == "native_prior_process_owner_unreconciled"
                    && source_handoff_available(
                        source.as_ref(),
                        &binding,
                        true,
                        inspection.native_generation,
                    )?;
                if !(completed && reason == "native_capability_owned") && !handoff {
                    return Err(reason.into());
                }
            }
            let cap = if completed {
                registry()
                    .claim_selected_run_after_completed_close(Path::new(&directory), source)?
            } else {
                registry().claim_selected_run(Path::new(&directory), source)?
            };
            super::runtime_host::adopt_operational_run_claim(&cap)?;
            cap
        } else {
            registry().claim(Path::new(&directory), source)?
        };
        admitted_value(&cap)
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
            record_method: "nativeAdmissionRecord",
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

#[cfg(test)]
mod operational_inspection_tests {
    use super::super::process_fence::OperationalRunInspection;
    use super::*;

    #[test]
    fn inspection_reports_actual_native_binding_and_never_a_selection_label() {
        let value = inspection_projection(
            Ok(OperationalRunInspection {
                actual_binding: None,
                native_generation: None,
                refusal: None,
            }),
            Ok(false),
            false,
        );
        assert!(value["operational_run_id"].is_null());
        assert!(value["native_generation"].is_null());
        assert_eq!(value["globally_eligible"], true);
    }

    #[test]
    fn retained_capability_requires_both_completed_native_owner_and_clear_app_fence() {
        let observation = || {
            Ok(OperationalRunInspection {
                actual_binding: None,
                native_generation: Some(7),
                refusal: Some("native_capability_owned"),
            })
        };
        assert_eq!(
            inspection_projection(observation(), Ok(false), false)["globally_eligible"],
            false
        );
        assert_eq!(
            inspection_projection(observation(), Ok(true), true)["globally_eligible"],
            false
        );
        assert_eq!(
            inspection_projection(observation(), Ok(true), false)["globally_eligible"],
            true
        );
        let poison = inspection_projection(Err("poisoned".into()), Ok(true), false);
        assert_eq!(poison["globally_eligible"], false);
        assert_eq!(poison["refusal_reason"], "native_registry_unavailable");
        let occupied = inspection_projection(
            observation(),
            Err("native_prepared_sdk_owned".into()),
            false,
        );
        assert_eq!(occupied["globally_eligible"], false);
        assert_eq!(occupied["refusal_reason"], "native_prepared_sdk_owned");
    }
}
