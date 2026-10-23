use rusty_quest_media_stream::{MediaStreamOwnerActionKind, MediaStreamPlatformOperation};
use rusty_quest_media_stream_android::*;
use serde_json::{json, Value};
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc, Mutex,
};
#[path = "../../../apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/runtime_slot.rs"]
mod runtime_slot;
use runtime_slot::Checkout;
#[path = "../../../apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/owner_failure.rs"]
mod owner_failure;
use owner_failure::Stage as OwnerFailureStage;
#[path = "../../../apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/retained_cleanup_host.rs"]
mod cleanup;
fn safe_decode(_: serde_json::Error) -> String {
    "modeled JSON failure".into()
}
mod operation_diagnostics {
    pub fn scope() -> rusty_quest_media_stream::DiagnosticScope {
        Default::default()
    }
}
#[derive(Clone)]
struct AuthorityClock(Arc<AtomicU64>);
impl OwnerDispatchClock for AuthorityClock {
    fn now_ms(&self) -> Result<u64, String> {
        Ok(self.0.load(Ordering::SeqCst))
    }
}
#[derive(Default)]
struct Leaves {
    saved: Mutex<String>,
    effects: Mutex<Vec<String>>,
    persist_hook: Mutex<Option<Arc<dyn Fn() -> Result<(), String> + Send + Sync>>>,
    effect_hook: Mutex<Option<Arc<dyn Fn() -> Result<(), String> + Send + Sync>>>,
    live: AtomicBool,
}
#[derive(Clone)]
struct JavaOwnerCallbacks(Arc<Leaves>);
impl JavaOwnerCallbacks {
    fn load_cleanup_preparations(&self) -> Result<String, String> {
        Ok("{\"originals\":{},\"prepared\":{}}".into())
    }
    fn persist_cleanup_preparations(&self, text: &str) -> Result<(), String> {
        let hook = self.0.persist_hook.lock().unwrap().clone();
        if let Some(h) = hook {
            h()?;
        }
        *self.0.saved.lock().unwrap() = text.into();
        Ok(())
    }
    fn local_public_key(&self) -> Result<[u8; 32], String> {
        use ed25519_dalek::SigningKey;
        Ok(SigningKey::from_bytes(&[7; 32]).verifying_key().to_bytes())
    }
    fn exchange(&self, _: &[u8], _: usize) -> Result<Vec<u8>, String> {
        Err("unused modeled transport".into())
    }
}
impl OwnerDispatchSigner for JavaOwnerCallbacks {
    fn key_id(&self) -> &str {
        "key.fixture"
    }
    fn sign(&self, bytes: &[u8]) -> Result<[u8; 64], String> {
        use ed25519_dalek::{Signer, SigningKey};
        Ok(SigningKey::from_bytes(&[7; 32]).sign(bytes).to_bytes())
    }
}
impl AuthenticatedOwnerRegistry for JavaOwnerCallbacks {
    fn current_executor_generation(&self) -> Result<u64, String> {
        Ok(70)
    }
    fn execute_and_verify(
        &mut self,
        auth: Option<&OwnerDispatchAuthorityProjection>,
        t: &AndroidMediaExecutionTicket,
        _: AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        if !self.0.live.load(Ordering::SeqCst) {
            return Err("modeled authority retired".into());
        }
        if auth.is_some_and(|a| a.expires_at_ms <= NOW.load(Ordering::SeqCst)) {
            return Err("modeled authority expired".into());
        }
        let hook = self.0.effect_hook.lock().unwrap().clone();
        if let Some(h) = hook {
            h()?;
        }
        self.0.effects.lock().unwrap().push(t.resource_id.clone());
        let readback = AndroidMediaOwnerReadback {
            remote_dispatch: None,
            remote_cleanup: None,
            schema_id: ANDROID_MEDIA_READBACK_SCHEMA.into(),
            capability: t.capability.clone(),
            executor_generation: t.executor_generation,
            action_id: t.action_id.clone(),
            authority_epoch_id: t.authority_epoch_id.clone(),
            media_acceptance_authority_revision: t.media_acceptance_authority_revision,
            expected_runtime_revision: t.expected_runtime_revision,
            client_id: t.client_id.clone(),
            lease_id: t.lease_id.clone(),
            sequence: t.sequence,
            operation: t.operation,
            owner_kind: t.owner_kind,
            action_kind: t.action_kind,
            owner_id: t.owner_id.clone(),
            provider_kind: t.provider_kind.clone(),
            resource_id: t.resource_id.clone(),
            provider_handle_id: "handle.fixture".into(),
            provider_state_revision: 1,
            observed_state: match t.action_kind {
                MediaStreamOwnerActionKind::ArmCleanup => "cleanup_armed",
                MediaStreamOwnerActionKind::ArmReceiver => "receiver_armed",
                MediaStreamOwnerActionKind::Start => "started",
                MediaStreamOwnerActionKind::Stop => "stopped",
                MediaStreamOwnerActionKind::Cleanup => "cleaned",
            }
            .into(),
            receipt_id: "receipt.fixture".into(),
        };
        let readback_json = serde_json::to_string(&readback).unwrap();
        Ok(AuthenticatedOwnerEffect {
            remote_dispatch: None,
            verified: VerifiedOwnerEffect {
                schema_id: VERIFIED_OWNER_EFFECT_SCHEMA.into(),
                receipt_id: readback.receipt_id.clone(),
                readback_sha256: format!("sha256:{}", crate::packaged_json_sha256(&readback_json)),
                executor_generation: 70,
                provider_state_revision: 1,
                observed_state: readback.observed_state.clone(),
                terminal: matches!(
                    t.action_kind,
                    MediaStreamOwnerActionKind::Stop | MediaStreamOwnerActionKind::Cleanup
                ),
                provider_handle_id: readback.provider_handle_id.clone(),
                detail_sha256: format!("sha256:{}", "0".repeat(64)),
            },
            readback,
            readback_json,
        })
    }
}
impl RetainedCleanupRegistry for JavaOwnerCallbacks {
    fn execute_and_verify(
        &mut self,
        _: &RetainedCleanupAuthorityProjection,
        _: &AndroidMediaExecutionTicket,
        _: AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        Err("unused retained effect".into())
    }
}
impl RetainedCleanupReplayStore for JavaOwnerCallbacks {
    fn commit(&mut self, _: &RetainedCleanupReplaySnapshot) -> Result<(), String> {
        Ok(())
    }
}
static NOW: AtomicU64 = AtomicU64::new(100);
#[derive(Clone, Default)]
pub(crate) struct QuestEmbeddedDuplexAuthority {
    cleanup: Arc<Mutex<Option<RetainedCleanupAuthorityProjection>>>,
    owner: Arc<Mutex<Option<OwnerDispatchAuthorityProjection>>>,
}
impl QuestEmbeddedDuplexAuthority {
    fn capture_retained_original_enrollment(
        &self,
        _: &OwnerDispatchAuthorityProjection,
        _: &AndroidMediaExecutionTicket,
        _: &str,
        _: &[u8; 32],
        _: u64,
    ) -> Result<Value, String> {
        Ok(json!({"modeled":true}))
    }
    fn retained_cleanup_projection(
        &self,
        _: &String,
        _: &String,
        _: &String,
        _: &String,
        _: &String,
        now: u64,
    ) -> Result<RetainedCleanupAuthorityProjection, String> {
        PROJECTION_NOW.store(now, Ordering::SeqCst);
        self.cleanup
            .lock()
            .unwrap()
            .clone()
            .ok_or_else(|| "unused projection".into())
    }
    fn retained_local_cleanup_projection(
        &self,
        _: &String,
        _: &String,
        _: &String,
        _: &String,
        _: u64,
    ) -> Result<RetainedCleanupAuthorityProjection, String> {
        Err("unused local projection".into())
    }
    fn retained_cleanup_renewal_ancestry(
        &self,
        _: &RetainedCleanupAuthorityProjection,
    ) -> Result<Vec<Value>, String> {
        Ok(vec![])
    }
    fn validate_remote_retained_cleanup_projection_with_original_enrollment(
        &self,
        _: &RetainedCleanupAuthorityProjection,
        _: &OwnerDispatchAuthorityProjection,
        _: &AndroidMediaExecutionTicket,
        _: &str,
        _: &[u8; 32],
        _: u64,
        _: &[Value],
        _: Option<&Value>,
    ) -> Result<(), String> {
        Err("unused remote authority".into())
    }
}

static PROJECTION_NOW: AtomicU64 = AtomicU64::new(0);

pub(crate) struct QuestEmbeddedDuplexProjectionSource {
    authority: QuestEmbeddedDuplexAuthority,
}
impl QuestEmbeddedDuplexProjectionSource {
    pub(crate) fn new(authority: QuestEmbeddedDuplexAuthority, _: String, _: String) -> Self {
        Self { authority }
    }
}
impl CurrentOwnerProjectionSource for QuestEmbeddedDuplexProjectionSource {
    fn current_projection(
        &self,
        _: &AndroidMediaExecutionTicket,
        _: &str,
        _: AndroidMediaExecutionMode,
        now: u64,
    ) -> Result<OwnerDispatchAuthorityProjection, String> {
        let a = self
            .authority
            .owner
            .lock()
            .unwrap()
            .clone()
            .ok_or("modeled current route absent")?;
        if a.expires_at_ms <= now {
            return Err("modeled authority expired".into());
        }
        Ok(a)
    }
}
