//! Failure-only data owner. No HostLease, capability, callback owner or authority is retained.
use rusty_quest_media_stream::{
    DiagnosticCollector, DiagnosticCorrelation, DiagnosticLayer, DiagnosticOperation,
    DiagnosticPhase, DiagnosticScope, FailureInput, PrivateFailureChunk,
};
use std::sync::OnceLock;

static COLLECTOR: OnceLock<DiagnosticCollector> = OnceLock::new();
static SCOPE: OnceLock<DiagnosticScope> = OnceLock::new();
static ORIGINALS: OnceLock<
    std::sync::Mutex<std::collections::VecDeque<(String, DiagnosticOperation)>>,
> = OnceLock::new();

pub(super) fn scope() -> DiagnosticScope {
    SCOPE.get_or_init(DiagnosticScope::default).clone()
}

fn original_operation(
    collector: &DiagnosticCollector,
    name: &str,
    id: String,
) -> DiagnosticOperation {
    let mut originals = ORIGINALS
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if let Some((_, operation)) = originals.iter().find(|(original, _)| original == &id) {
        return operation.clone();
    }
    let operation = collector.operation(
        operation_name(name),
        DiagnosticCorrelation {
            original_id: Some(id.clone()),
            ..Default::default()
        },
    );
    if originals.len() == 32 {
        originals.pop_front();
    }
    originals.push_back((id, operation.clone()));
    operation
}

/// Names come from actual fixed Java owner actions, never arbitrary input text.
fn operation_name(value: &str) -> &'static str {
    match value {
        "start" => "start",
        "peer_stop" => "peer_stop",
        "peer_revoke" => "peer_revoke",
        "whole_app_close" => "whole_app_close",
        "renew_authority" => "renew_authority",
        "prepare_peer_start" => "prepare_peer_start",
        "abort_prepared_peer_start" => "abort_prepared_peer_start",
        "pair_session" => "pair_session",
        "fresh_pair_continue" => "fresh_pair_continue",
        "reestablish_peer_roots" => "reestablish_peer_roots",
        "concurrent_arm" => "concurrent_arm",
        "initialize_runtime" => "initialize_runtime",
        "owner_dispatch" => "owner_dispatch",
        "bootstrap_real_peer" => "bootstrap_real_peer",
        "close_no_media" => "close_no_media",
        "abandon_retained_scope" => "abandon_retained_scope",
        "confirm" => "confirm",
        "policy_update" => "policy_update",
        "blend_update" => "blend_update",
        _ => "native_operation",
    }
}

pub(super) fn failure(boundary: &'static str, error: &[u8]) {
    if let Some(operation) = scope().current() {
        operation.record(
            FailureInput::new(
                PHASE.with(|value| value.get()),
                DiagnosticLayer::Jni,
                "native_bridge",
                boundary,
                "native_bridge",
                "unclassified",
            )
            .private_context(vec![PrivateFailureChunk {
                name: "error",
                bytes: error,
            }]),
        );
    }
}

thread_local! { static PHASE: std::cell::Cell<DiagnosticPhase> = const { std::cell::Cell::new(DiagnosticPhase::Primary) }; }
pub(super) struct PhaseGuard(DiagnosticPhase, std::marker::PhantomData<std::rc::Rc<()>>);
impl Drop for PhaseGuard {
    fn drop(&mut self) {
        PHASE.with(|value| value.set(self.0));
    }
}
pub(super) fn phase(value: DiagnosticPhase) -> PhaseGuard {
    PhaseGuard(
        PHASE.with(|current| current.replace(value)),
        std::marker::PhantomData,
    )
}

/// Hex keeps arbitrary signed frames exact without another runtime dependency.
/// Truncation is explicit per chunk and at the envelope; incomplete frames cannot be replayed as exact.
fn private_envelope(
    context_id: &str,
    chunks: &[PrivateFailureChunk<'_>],
    max_bytes: u64,
) -> Option<String> {
    if context_id.len() != 64
        || !context_id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return None;
    }
    let maximum = usize::try_from(max_bytes).ok()?.min(1024 * 1024);
    if maximum < 1024 {
        return None;
    }
    let mut remaining = maximum - 512;
    let mut rows = Vec::new();
    let mut truncated = chunks.len() > 16;
    for chunk in chunks.iter().take(16) {
        let overhead = chunk.name.len() + 128;
        if remaining <= overhead {
            truncated = true;
            break;
        }
        let count = chunk
            .bytes
            .len()
            .min(128 * 1024)
            .min((remaining - overhead) / 2);
        let bytes_hex = chunk.bytes[..count]
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>();
        let cut = count != chunk.bytes.len();
        truncated |= cut;
        let row = serde_json::json!({"name":chunk.name,"encoding":"hex","source_bytes":chunk.bytes.len(),
            "bytes_hex":bytes_hex,"truncated":cut});
        let used = serde_json::to_vec(&row).ok()?.len() + 1;
        if used > remaining {
            truncated = true;
            break;
        }
        remaining -= used;
        rows.push(row);
    }
    let encoded = serde_json::json!({"schema":"rusty.quest.private_failure_context.v1",
        "context_id":context_id,"chunks":rows,"truncated":truncated})
    .to_string();
    (encoded.len() <= maximum).then_some(encoded)
}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use jni::{
        objects::{GlobalRef, JClass, JObject, JString, JValue},
        sys::jstring,
        JNIEnv, JavaVM,
    };
    use rusty_quest_media_stream::{
        DiagnosticScopeGuard, PrivateContextDescriptor, PrivateFailureSink,
    };
    use std::{cell::RefCell, sync::Arc};

    // Class-only reference and VM: no Activity, runtime host, credentials or authority lifetime extension.
    struct JavaPrivateSink {
        vm: Arc<JavaVM>,
        class: GlobalRef,
    }
    static DIAGNOSTIC_CLASS: OnceLock<GlobalRef> = OnceLock::new();

    pub(crate) fn throwable_detail(
        env: &mut JNIEnv<'_>,
        throwable: &JObject<'_>,
    ) -> Option<String> {
        if env.exception_check().ok()? {
            return None;
        }
        let result = (|| {
            let class: &JClass<'_> = DIAGNOSTIC_CLASS.get()?.as_obj().into();
            let value = env
                .call_static_method(
                    class,
                    "nativeThrowableContext",
                    "(Ljava/lang/Throwable;)Ljava/lang/String;",
                    &[JValue::Object(throwable)],
                )
                .ok()?
                .l()
                .ok()?;
            if value.is_null() {
                return None;
            }
            let detail: String = env.get_string(&JString::from(value)).ok()?.into();
            (detail.len() <= 65536).then_some(detail)
        })();
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        result
    }
    impl PrivateFailureSink for JavaPrivateSink {
        fn evict(&self, context_id: &str) -> Result<(), ()> {
            if context_id.len() != 64
                || !context_id
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            {
                return Err(());
            }
            let mut env = self.vm.attach_current_thread().map_err(|_| ())?;
            if env.exception_check().map_err(|_| ())? {
                return Err(());
            }
            let result = (|| {
                let input = JObject::from(env.new_string(context_id).map_err(|_| ())?);
                let class: &JClass<'_> = self.class.as_obj().into();
                let removed = env
                    .call_static_method(
                        class,
                        "evictNativeContext",
                        "(Ljava/lang/String;)Z",
                        &[JValue::Object(&input)],
                    )
                    .map_err(|_| ())?
                    .z()
                    .map_err(|_| ())?;
                if removed {
                    Ok(())
                } else {
                    Err(())
                }
            })();
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
            result
        }
        fn retain(
            &self,
            context_id: &str,
            chunks: &[PrivateFailureChunk<'_>],
            maximum: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            let body = private_envelope(context_id, chunks, maximum).ok_or(())?;
            let truncated = serde_json::from_str::<serde_json::Value>(&body)
                .map_err(|_| ())?
                .get("truncated")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let mut env = self.vm.attach_current_thread().map_err(|_| ())?;
            // Never consume an exception owned by the operation being diagnosed.
            if env.exception_check().map_err(|_| ())? {
                return Err(());
            }
            let result = (|| {
                let id = JObject::from(env.new_string(context_id).map_err(|_| ())?);
                let input = JObject::from(env.new_string(body).map_err(|_| ())?);
                let class: &JClass<'_> = self.class.as_obj().into();
                let result = env
                    .call_static_method(
                        class,
                        "storeNativeContext",
                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                        &[JValue::Object(&id), JValue::Object(&input)],
                    )
                    .map_err(|_| ())?
                    .l()
                    .map_err(|_| ())?;
                if result.is_null() {
                    return Err(());
                }
                let value: String = env
                    .get_string(&JString::from(result))
                    .map_err(|_| ())?
                    .into();
                if value.len() > 2048 {
                    return Err(());
                }
                let receipt: serde_json::Value = serde_json::from_str(&value).map_err(|_| ())?;
                Ok(PrivateContextDescriptor {
                    status: if truncated {
                        "truncated".into()
                    } else {
                        receipt["status"].as_str().ok_or(())?.into()
                    },
                    context_id: receipt["context_id"].as_str().map(str::to_owned),
                    bytes: receipt["bytes"].as_u64().ok_or(())?,
                    sha256: receipt["sha256"].as_str().map(str::to_owned),
                    truncated: truncated || receipt["truncated"].as_bool().ok_or(())?,
                })
            })();
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
            result
        }
    }
    fn collector(env: &mut JNIEnv<'_>) -> Option<&'static DiagnosticCollector> {
        if let Some(collector) = COLLECTOR.get() {
            return Some(collector);
        }
        if env.exception_check().ok()? {
            return None;
        }
        let result = (|| {
            let vm = Arc::new(env.get_java_vm().ok()?);
            let class = env.find_class("io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexOperationDiagnostics").ok()?;
            let class = env.new_global_ref(class).ok()?;
            let _ = DIAGNOSTIC_CLASS.set(class.clone());
            Some(DiagnosticCollector::new(Some(Arc::new(JavaPrivateSink {
                vm,
                class,
            }))))
        })();
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        if let Some(value) = result {
            let _ = COLLECTOR.set(value);
        }
        COLLECTOR.get()
    }
    thread_local! { static ENTERED: RefCell<Vec<Option<DiagnosticScopeGuard>>> = const { RefCell::new(Vec::new()) }; }

    pub(crate) fn fallback(
        env: &mut JNIEnv<'_>,
        name: &'static str,
    ) -> Option<DiagnosticScopeGuard> {
        if scope().current().is_some() {
            return None;
        }
        let operation = collector(env)?.operation(name, DiagnosticCorrelation::default());
        Some(scope().enter(operation))
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_beginOperationDiagnostics(
        mut env: JNIEnv<'_>,
        _: JClass<'_>,
        operation: JString<'_>,
        original: JString<'_>,
    ) {
        // Diagnostics are best effort and never throw or consult a live runtime.
        if env.exception_check().unwrap_or(true) {
            ENTERED.with(|entered| entered.borrow_mut().push(None));
            return;
        }
        let result = (|| {
            if original.is_null() {
                return Some(scope().suspend());
            }
            let name: String = env.get_string(&operation).ok()?.into();
            let original_id: String = env.get_string(&original).ok()?.into();
            if name.len() > 96
                || original_id.len() != 32
                || !original_id
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            {
                return None;
            }
            let operation = original_operation(collector(&mut env)?, &name, original_id);
            Some(scope().enter(operation))
        })();
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        // Every normally returning begin has one matching end, including rejected inputs.
        ENTERED.with(|entered| entered.borrow_mut().push(result));
    }
    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_endOperationDiagnostics(
        _env: JNIEnv<'_>,
        _: JClass<'_>,
    ) {
        ENTERED.with(|entered| {
            entered.borrow_mut().pop();
        });
    }
    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_operationDiagnostics(
        mut env: JNIEnv<'_>,
        _: JClass<'_>,
        original: JString<'_>,
    ) -> jstring {
        if env.exception_check().unwrap_or(true) {
            return std::ptr::null_mut();
        }
        let result = (|| {
            let original: Option<String> = if original.is_null() {
                None
            } else {
                let value: String = env.get_string(&original).ok()?.into();
                if value.len() != 32
                    || !value
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                {
                    return None;
                }
                Some(value)
            };
            let collector = collector(&mut env)?;
            let body = serde_json::to_string(&collector.snapshot(original.as_deref())).ok()?;
            env.new_string(body).ok().map(|value| value.into_raw())
        })();
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        result.unwrap_or(std::ptr::null_mut())
    }
}
#[cfg(target_os = "android")]
pub(super) use android::{fallback, throwable_detail};

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn repeated_original_scopes_keep_first_cause_and_restore_compensation_phase() {
        let collector = DiagnosticCollector::default();
        let first = original_operation(&collector, "start", "c".repeat(32));
        let current = scope();
        {
            let _scope = current.enter(first);
            failure("signed_response_proof", b"private unknown first cause");
            {
                let _phase = phase(DiagnosticPhase::Compensation);
                failure("abort_provider", b"private abort failure");
            }
            {
                let _unattributed = current.suspend();
                failure(
                    "unattributed_callback",
                    b"must not attach to outer Original",
                );
            }
        }
        let repeated = original_operation(&collector, "start", "c".repeat(32));
        {
            let _scope = current.enter(repeated);
            failure("completion_publication", b"later cause");
        }
        assert!(current.current().is_none());
        let snapshot = collector.snapshot(None);
        assert_eq!(snapshot.operations.len(), 1);
        assert_eq!(
            snapshot.operations[0].primary.boundary,
            "signed_response_proof"
        );
        assert_eq!(snapshot.operations[0].secondary.len(), 2);
        assert_eq!(
            snapshot.operations[0].secondary[0].phase,
            DiagnosticPhase::Compensation
        );
        assert_eq!(
            snapshot.operations[0].secondary[1].phase,
            DiagnosticPhase::Primary
        );
        assert!(snapshot.operations[0].primary.detail_sha256.is_some());
    }
    #[test]
    fn production_collection_fixture_keeps_raw_context_private() {
        use rusty_quest_media_stream::{PrivateContextDescriptor, PrivateFailureSink};
        use sha2::{Digest, Sha256};
        use std::sync::{Arc, Mutex};
        #[derive(Default)]
        struct Sink(Mutex<Vec<(String, String)>>);
        impl PrivateFailureSink for Sink {
            fn retain(
                &self,
                id: &str,
                chunks: &[PrivateFailureChunk<'_>],
                limit: u64,
            ) -> Result<PrivateContextDescriptor, ()> {
                let body = private_envelope(id, chunks, limit).ok_or(())?;
                let receipt = PrivateContextDescriptor {
                    status: "available".into(),
                    context_id: Some(id.into()),
                    bytes: body.len() as u64,
                    sha256: Some(format!("{:x}", Sha256::digest(body.as_bytes()))),
                    truncated: false,
                };
                self.0.lock().unwrap().push((id.into(), body));
                Ok(receipt)
            }
        }
        let sink = Arc::new(Sink::default());
        let collector = DiagnosticCollector::new(Some(sink.clone()));
        let operation = collector.operation(
            "start",
            DiagnosticCorrelation {
                original_id: Some("a".repeat(32)),
                native_generation: Some(66),
                ..Default::default()
            },
        );
        operation.record(
            FailureInput::new(
                DiagnosticPhase::Primary,
                DiagnosticLayer::Rust,
                "owner_dispatch",
                "response_proof",
                "proof",
                "unclassified",
            )
            .private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: b"MODELED unfamiliar verifier failure",
                },
                PrivateFailureChunk {
                    name: "request_frame",
                    bytes: b"MODELED transient source request; not an incident replay",
                },
            ]),
        );
        operation.record(FailureInput::new(
            DiagnosticPhase::Compensation,
            DiagnosticLayer::Rust,
            "owner_dispatch",
            "abort_readback",
            "cleanup",
            "unclassified",
        ));
        let public = serde_json::to_string_pretty(&collector.snapshot(None)).unwrap();
        assert!(!public.contains("MODELED") && !public.contains("bytes_hex"));
        assert!(public.contains("response_proof") && public.contains("abort_readback"));
        if let Some(directory) = std::env::var_os("RQ_DIAGNOSTIC_FIXTURE_DIR") {
            let directory = std::path::PathBuf::from(directory);
            std::fs::create_dir_all(&directory).unwrap();
            std::fs::write(directory.join("native-collection.json"), public).unwrap();
            for (id, body) in sink.0.lock().unwrap().iter() {
                std::fs::write(directory.join(format!("{id}.json")), body).unwrap();
            }
        }
    }
    #[test]
    fn exact_private_frames_stay_out_of_public_snapshot() {
        let chunks = [PrivateFailureChunk {
            name: "signed_request",
            bytes: b"private-frame\0\xff",
        }];
        let raw = private_envelope(&"a".repeat(64), &chunks, 1024 * 1024).unwrap();
        let value: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(
            value["chunks"][0]["bytes_hex"],
            "707269766174652d6672616d6500ff"
        );
        assert_eq!(value["truncated"], false);
        assert!(private_envelope("../unsafe", &chunks, 1024).is_none());
    }
    #[test]
    fn oversize_context_is_bounded_and_honest() {
        let bytes = vec![0xff; 256 * 1024];
        let chunks: Vec<_> = (0..16)
            .map(|_| PrivateFailureChunk {
                name: "response",
                bytes: &bytes,
            })
            .collect();
        let raw = private_envelope(&"b".repeat(64), &chunks, 1024 * 1024).unwrap();
        assert!(raw.len() <= 1024 * 1024);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&raw).unwrap()["truncated"],
            true
        );
        assert!(private_envelope(&"b".repeat(64), &chunks, 32).is_none());
    }
    #[test]
    fn untrusted_operation_name_is_never_published() {
        assert_eq!(operation_name("private://token"), "native_operation");
        assert_eq!(operation_name("whole_app_close"), "whole_app_close");
    }
}
