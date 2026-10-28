//! Fixed signed prepare/commit cleanup; original target and fresh requester are separate.
use super::super::preparation_native_guard::{
    FailureReason as NativeReason, Guard as NativeGuard, Trace as NativeTrace,
};
use super::*;
#[path = "cleanup_admission.rs"]
mod cleanup_admission;
use ed25519_dalek::{Signature, VerifyingKey};
use rusty_quest_media_stream_android::*;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::io::Read;
pub(super) const PREPARE_MAGIC: &[u8] = b"RQCP1\0";
const DOMAIN: &[u8] = b"rusty.quest.android.media.retained_cleanup_prepare.v1\0";
const ABORT_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_abort_prepare.v2\0";
const REMOTE_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_cleanup_prepare.v3\0";
const REMOTE_ABORT_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_abort_prepare.v4\0";

fn prepare_domain(schema: &str) -> Result<&'static [u8], String> {
    match schema {
        "rusty.quest.android.media.retained_cleanup_prepare_request.v1" => Ok(DOMAIN),
        "rusty.quest.android.media.retained_abort_prepare_request.v2" => Ok(ABORT_DOMAIN),
        "rusty.quest.android.media.retained_cleanup_prepare_request.v3" => Ok(REMOTE_DOMAIN),
        "rusty.quest.android.media.retained_abort_prepare_request.v4" => Ok(REMOTE_ABORT_DOMAIN),
        _ => Err("unknown cleanup prepare schema".into()),
    }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Prepare {
    schema_id: String,
    dispatch_id: String,
    source_ticket: AndroidMediaExecutionTicket,
    requester_id: String,
    requester_lease_id: String,
    route_grant_id: String,
    sequence: u64,
    issued_at_ms: u64,
    signer_key_id: String,
    signature_base64: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    authority: Option<RetainedCleanupAuthorityProjection>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    renewal_ancestry: Vec<rusty_quest_broker_authority::QuestRetainedCleanupSessionRenewal>,
}
#[derive(Clone, Serialize, Deserialize)]
struct Original {
    ticket: AndroidMediaExecutionTicket,
    effect: Option<AuthenticatedOwnerEffect>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    source_authority: Option<OwnerDispatchAuthorityProjection>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    retained_enrollment: Option<rusty_quest_broker_authority::QuestRetainedOriginalEnrollment>,
}
#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct PreparedState {
    #[serde(default)]
    revision: u64,
    originals: BTreeMap<String, Original>,
    prepared: BTreeMap<String, RetainedCleanupPreparedStop>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    remote_requests: BTreeMap<String, Prepare>,
}
#[derive(Clone, Default)]
pub(super) struct Requester {
    pub(super) id: String,
    pub(super) lease: String,
}
#[derive(Clone)]
pub(super) struct Cleanup {
    authority: QuestEmbeddedDuplexAuthority,
    callbacks: JavaOwnerCallbacks,
    clock: AuthorityClock,
    local: String,
    remote: String,
    remote_key_id: String,
    remote_key: [u8; 32],
    generation: u64,
    poisoned: Arc<AtomicBool>,
    serial: Arc<cleanup_admission::Admission>,
    state: Arc<Mutex<PreparedState>>,
    pub(super) requester: Arc<Mutex<Option<Requester>>>,
    failure: Arc<Mutex<Option<&'static str>>>,
}
fn key(t: &AndroidMediaExecutionTicket) -> String {
    format!(
        "{:?}|{}|{}|{}",
        t.owner_kind, t.owner_id, t.provider_kind, t.resource_id
    )
}
fn digest(bytes: &[u8]) -> Result<String, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "cleanup canonical JSON UTF8")?;
    Ok(format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(text)
    ))
}
fn encode<T: Serialize>(v: &T) -> Result<Vec<u8>, String> {
    serde_json::to_vec(v).map_err(|_| "cleanup encode".into())
}
fn fresh() -> Result<String, String> {
    let mut b = [0u8; 16];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut b))
        .map_err(|_| "cleanup entropy unavailable")?;
    Ok(b.iter().map(|x| format!("{x:02x}")).collect())
}
fn join_cleanup_projection(
    current: &mut RetainedCleanupAuthorityProjection,
    prepared: &RetainedCleanupAuthorityProjection,
    now: u64,
) -> Result<(), String> {
    if prepared.schema_id == OWNED_CLEANUP_PROJECTION_SCHEMA {
        if current.schema_id != OWNED_CLEANUP_PROJECTION_SCHEMA {
            return Err("owned cleanup version changed".into());
        }
        // These timestamps describe the original authentication/requester;
        // entitlement comes from the exact retained target and identity.
        current.expires_at_ms = prepared.expires_at_ms;
        current.requester_expires_at_ms = prepared.requester_expires_at_ms;
    } else {
        if prepared.expires_at_ms <= now || prepared.expires_at_ms > current.expires_at_ms {
            return Err("prepared cleanup expired".into());
        }
        current.expires_at_ms = prepared.expires_at_ms;
    }
    if current != prepared {
        return Err("retained cleanup current source/target changed".into());
    }
    Ok(())
}

fn same_owner(a: &AndroidMediaExecutionTicket, b: &AndroidMediaExecutionTicket) -> bool {
    a.authority_epoch_id == b.authority_epoch_id
        && a.client_id == b.client_id
        && a.lease_id == b.lease_id
        && a.owner_kind == b.owner_kind
        && a.owner_id == b.owner_id
        && a.provider_kind == b.provider_kind
        && a.resource_id == b.resource_id
}
impl Cleanup {
    fn prepare_authentication_validity(&self, request: &Prepare) -> Result<u64, String> {
        if request
            .authority
            .as_ref()
            .is_some_and(|p| p.schema_id == OWNED_CLEANUP_PROJECTION_SCHEMA)
        {
            self.authority.owned_cleanup_authentication_validity_ms()
        } else {
            Ok(30_000)
        }
    }

    // Actual unpublished constructor owner only; no cleanup outcome inferred.
    pub(super) fn construction_snapshot(&self) -> Result<Value, String> {
        let _serial = self
            .serial
            .try_enter()
            .map_err(|_| "construction cleanup serial busy")?
            .ok_or("construction cleanup requester active")?;
        self.require_state()?;
        if self
            .requester
            .try_lock()
            .map_err(|_| "construction cleanup requester busy")?
            .is_some()
        {
            return Err("construction cleanup requester active".into());
        }
        serde_json::to_value(
            &*self
                .state
                .try_lock()
                .map_err(|_| "construction cleanup state busy")?,
        )
        .map_err(|_| "construction cleanup snapshot encoding".into())
    }

    /// Current actual cleanup owner; retained prepared originals must have a real
    /// completed signed server response before fresh setup can supersede authority.
    pub(super) fn fresh_setup_quiescence(
        &self,
        replay: &RetainedCleanupReplaySnapshot,
    ) -> Result<(), String> {
        let trace = NativeTrace::new(&|_, _| {});
        self.fresh_setup_quiescence_observed(replay, &trace)
    }
    pub(super) fn fresh_setup_quiescence_observed(
        &self,
        replay: &RetainedCleanupReplaySnapshot,
        trace: &NativeTrace<'_>,
    ) -> Result<(), String> {
        trace.mark(NativeGuard::CleanupSerial, NativeReason::CallFailed);
        let serial = self
            .serial
            .try_enter()
            .map_err(|_| "fresh cleanup mutation busy")?;
        if serial.is_none() {
            return Err("fresh cleanup mutation checked out".into());
        }
        trace.mark(NativeGuard::CleanupState, NativeReason::CallFailed);
        self.require_state()?;
        trace.mark(NativeGuard::CleanupPublicKey, NativeReason::CallFailed);
        let public_key = self.callbacks.local_public_key()?;
        trace.mark(NativeGuard::CleanupStateLock, NativeReason::CallFailed);
        let state = self
            .state
            .try_lock()
            .map_err(|_| "fresh cleanup retained state busy")?;
        trace.mark(
            NativeGuard::CleanupForwardOriginal,
            NativeReason::PredicateRejected,
        );
        if state.originals.values().any(|o| o.effect.is_none()) {
            return Err("fresh cleanup forward original unresolved".into());
        }
        trace.mark(
            NativeGuard::CleanupPreparedTerminal,
            NativeReason::PredicateRejected,
        );
        for id in state.prepared.keys() {
            if !replay.terminal.contains_key(id) {
                return Err("fresh cleanup Prepare lacks terminal original".into());
            }
        }
        trace.mark(NativeGuard::CleanupReplayShape, NativeReason::CallFailed);
        replay.validate().map_err(str::to_owned)?;
        trace.mark(NativeGuard::CleanupPending, NativeReason::PredicateRejected);
        if !replay.pending_request_sha256.is_empty() {
            return Err("fresh cleanup original pending".into());
        }
        for (id, terminal) in &replay.terminal {
            trace.mark(NativeGuard::CleanupResponseDecode, NativeReason::CallFailed);
            let r: RetainedCleanupDispatchResponse =
                serde_json::from_slice(&terminal.response_bytes)
                    .map_err(|_| "fresh cleanup response decode")?;
            trace.mark(
                NativeGuard::CleanupResponseIdentity,
                NativeReason::PredicateRejected,
            );
            if r.schema_id != RETAINED_CLEANUP_DISPATCH_RESPONSE_SCHEMA
                || &r.dispatch_id != id
                || r.executor_peer_id != self.local
                || r.signer_key_id != self.callbacks.key_id()
                || r.request_sha256 != terminal.request_sha256
            {
                return Err("fresh cleanup terminal original not completed".into());
            }
            trace.mark(
                NativeGuard::CleanupResponseCompleted,
                NativeReason::PredicateRejected,
            );
            if r.status != OwnerDispatchStatus::Completed || r.failure.is_some() {
                return Err("fresh cleanup terminal original not completed".into());
            }
            trace.mark(
                NativeGuard::CleanupResponseTerminal,
                NativeReason::PredicateRejected,
            );
            let effect = r
                .effect
                .as_ref()
                .filter(|e| e.verified.terminal)
                .ok_or("fresh cleanup terminal original not completed")?;
            trace.mark(
                NativeGuard::CleanupResponseGeneration,
                NativeReason::PredicateRejected,
            );
            if effect.readback.executor_generation != self.generation {
                return Err("fresh cleanup terminal original not completed".into());
            }
            trace.mark(
                NativeGuard::CleanupResponseStop,
                NativeReason::PredicateRejected,
            );
            if effect.readback.operation != MediaStreamPlatformOperation::Stop
                || effect.readback.observed_state != "stopped"
            {
                return Err("fresh cleanup terminal original not completed".into());
            }
            trace.mark(
                NativeGuard::CleanupResponseSignature,
                NativeReason::CallFailed,
            );
            VerifyingKey::from_bytes(&public_key)
                .map_err(|_| "fresh cleanup key")?
                .verify_strict(
                    &retained_cleanup_response_signing_bytes(&r)?,
                    &Signature::from_bytes(&decode_signature_base64(&r.signature_base64)?),
                )
                .map_err(|_| "fresh cleanup terminal signature")?;
        }
        Ok(())
    }

    pub(super) fn new(
        authority: QuestEmbeddedDuplexAuthority,
        callbacks: JavaOwnerCallbacks,
        clock: AuthorityClock,
        local: String,
        remote: String,
        remote_key_id: String,
        remote_key: [u8; 32],
        generation: u64,
        failure: Arc<Mutex<Option<&'static str>>>,
    ) -> Result<Self, String> {
        let state: PreparedState = serde_json::from_str(&callbacks.load_cleanup_preparations()?)
            .map_err(|_| "cleanup preparations decode")?;
        if state.originals.len() > 256
            || state.prepared.len() > 256
            || state.remote_requests.len() > 256
        {
            return Err("cleanup retained state capacity".into());
        }
        Ok(Self {
            authority,
            callbacks,
            clock,
            local,
            remote,
            remote_key_id,
            remote_key,
            generation,
            poisoned: Arc::new(AtomicBool::new(false)),
            serial: Arc::new(cleanup_admission::Admission::default()),
            state: Arc::new(Mutex::new(state)),
            requester: Arc::new(Mutex::new(None)),
            failure,
        })
    }
    fn persist(&self, next: PreparedState) -> Result<(), String> {
        self.require_state()?;
        self.poisoned.store(true, Ordering::SeqCst);
        // Keep all effects uncertain if persistence fails. Never hold state across Java.
        self.callbacks.persist_cleanup_preparations(
            &String::from_utf8(encode(&next)?).map_err(|_| "cleanup UTF8")?,
        )?;
        *self.state.lock().map_err(|_| "cleanup state poisoned")? = next;
        self.poisoned.store(false, Ordering::SeqCst);
        Ok(())
    }
    fn require_state(&self) -> Result<(), String> {
        if self.poisoned.load(Ordering::SeqCst) {
            Err("cleanup durable commit uncertain".into())
        } else {
            Ok(())
        }
    }
    fn projection(
        &self,
        grant: &str,
        requester: &str,
        lease: &str,
        authority_peer: &str,
        executor: &str,
        now: u64,
    ) -> Result<RetainedCleanupAuthorityProjection, String> {
        self.authority.owned_retained_cleanup_projection(
            &serde_json::from_value(json!(grant)).map_err(safe_decode)?,
            &serde_json::from_value(json!(requester)).map_err(safe_decode)?,
            &serde_json::from_value(json!(lease)).map_err(safe_decode)?,
            &serde_json::from_value(json!(authority_peer)).map_err(safe_decode)?,
            &serde_json::from_value(json!(executor)).map_err(safe_decode)?,
            now,
        )
    }
    fn retained_owned_prepare(&self, request: &Prepare) -> Result<bool, String> {
        if !request
            .authority
            .as_ref()
            .is_some_and(|p| p.schema_id == OWNED_CLEANUP_PROJECTION_SCHEMA)
        {
            return Ok(false);
        }
        let hash = digest(&encode(request)?)?;
        let state = self
            .state
            .lock()
            .map_err(|_| "cleanup retained preparation poisoned")?;
        Ok(state
            .prepared
            .get(&request.dispatch_id)
            .is_some_and(|p| p.prepare_request_sha256 == hash)
            && state
                .remote_requests
                .get(&request.dispatch_id)
                .is_some_and(|r| r.signature_base64 == request.signature_base64))
    }
    fn remote_projection(
        &self,
        request: &Prepare,
        original: &Original,
        now: u64,
    ) -> Result<RetainedCleanupAuthorityProjection, String> {
        let authority = request
            .authority
            .as_ref()
            .ok_or("remote cleanup projection absent")?;
        let retained = original
            .source_authority
            .as_ref()
            .ok_or("authenticated remote Start projection absent")?;
        let abort = request.schema_id
            == "rusty.quest.android.media.retained_abort_prepare_request.v4"
            && is_retained_start_abort_ticket(&request.source_ticket);
        if !(abort
            || (request.schema_id
                == "rusty.quest.android.media.retained_cleanup_prepare_request.v3"
                && request.source_ticket.operation == MediaStreamPlatformOperation::Stop))
            || !same_owner(&original.ticket, &request.source_ticket)
            || (abort
                && request.source_ticket.action_id
                    != format!("{}.abort", original.ticket.action_id))
        {
            return Err("remote cleanup retained action differs".into());
        }
        if request.signer_key_id != self.remote_key_id
            || request.issued_at_ms > now.saturating_add(2000)
            || now.saturating_sub(request.issued_at_ms)
                > self.prepare_authentication_validity(&request)?
                && !self.retained_owned_prepare(&request)?
        {
            return Err("remote cleanup request signer/freshness differs".into());
        }
        let mut unsigned = request.clone();
        unsigned.signature_base64.clear();
        let mut signing = prepare_domain(&request.schema_id)?.to_vec();
        signing.extend(encode(&unsigned)?);
        VerifyingKey::from_bytes(&self.remote_key)
            .map_err(|_| "cleanup remote key")?
            .verify_strict(
                &signing,
                &Signature::from_bytes(&decode_signature_base64(&request.signature_base64)?),
            )
            .map_err(|_| "cleanup prepare signature")?;
        if request.route_grant_id != authority.route_grant_id
            || request.requester_id != authority.requester_id
            || request.requester_lease_id != authority.requester_runtime_lease_id
            || authority.authority_peer_id != self.remote
            || authority.executor_peer_id != self.local
        {
            return Err("remote cleanup request source differs".into());
        }
        self.authority
            .validate_remote_retained_cleanup_projection_with_original_enrollment(
                authority,
                retained,
                &original.ticket,
                &request.signer_key_id,
                &self.remote_key,
                now,
                &request.renewal_ancestry,
                original.retained_enrollment.as_ref(),
            )?;
        Ok(authority.clone())
    }
    pub(super) fn prepare_frame(&self, bytes: &[u8]) -> Result<Vec<u8>, String> {
        let mut failure_stage = OwnerFailureStage::IncomingPrepare;
        let result = (|| {
            let _mutation = self.serial.enter()?;
            self.require_state()?;
            if bytes.len() > 128 * 1024 || !bytes.starts_with(PREPARE_MAGIC) {
                return Err("cleanup prepare frame bounds".into());
            }
            let request: Prepare = serde_json::from_slice(&bytes[PREPARE_MAGIC.len()..])
                .map_err(|_| "cleanup prepare decode")?;
            let now = self.clock.now_ms()?;
            let remote_projection = matches!(
                request.schema_id.as_str(),
                "rusty.quest.android.media.retained_cleanup_prepare_request.v3"
                    | "rusty.quest.android.media.retained_abort_prepare_request.v4"
            );
            let retained_abort = matches!(
                request.schema_id.as_str(),
                "rusty.quest.android.media.retained_abort_prepare_request.v2"
                    | "rusty.quest.android.media.retained_abort_prepare_request.v4"
            ) && is_retained_start_abort_ticket(&request.source_ticket);
            if !(retained_abort
                || (matches!(
                    request.schema_id.as_str(),
                    "rusty.quest.android.media.retained_cleanup_prepare_request.v1"
                        | "rusty.quest.android.media.retained_cleanup_prepare_request.v3"
                ) && request.source_ticket.operation == MediaStreamPlatformOperation::Stop))
                || remote_projection != request.authority.is_some()
                || (!remote_projection && !request.renewal_ancestry.is_empty())
                || request.sequence == 0
                || request.signer_key_id != self.remote_key_id
                || request.issued_at_ms > now.saturating_add(2000)
                || now.saturating_sub(request.issued_at_ms) > 30000
            {
                return Err("cleanup prepare identity/freshness".into());
            }
            let mut unsigned = request.clone();
            unsigned.signature_base64.clear();
            let mut signing = prepare_domain(&request.schema_id)?.to_vec();
            signing.extend(encode(&unsigned)?);
            let sig = decode_signature_base64(&request.signature_base64)?;
            VerifyingKey::from_bytes(&self.remote_key)
                .map_err(|_| "cleanup remote key")?
                .verify_strict(&signing, &Signature::from_bytes(&sig))
                .map_err(|_| "cleanup prepare signature")?;
            let hash = digest(&encode(&request)?)?;
            let previous_preparation = self
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .prepared
                .get(&request.dispatch_id)
                .cloned();
            if let Some(prepared) = previous_preparation {
                if prepared.prepare_request_sha256 != hash {
                    return Err("cleanup prepare replay collision".into());
                }
                if remote_projection {
                    let original = self
                        .state
                        .lock()
                        .map_err(|_| "cleanup state poisoned")?
                        .originals
                        .get(&key(&request.source_ticket))
                        .cloned()
                        .ok_or("retained original owner absent")?;
                    if self.remote_projection(&request, &original, now)? != prepared.authority {
                        return Err("remote cleanup replay authority differs".into());
                    }
                }
                return encode(&prepared);
            }
            failure_stage = OwnerFailureStage::IncomingRequesterAuthority;
            let original = self
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .originals
                .get(&key(&request.source_ticket))
                .cloned()
                .ok_or("retained original owner absent")?;
            let authority = if remote_projection {
                self.remote_projection(&request, &original, now)?
            } else {
                self.projection(
                    &request.route_grant_id,
                    &request.requester_id,
                    &request.requester_lease_id,
                    &self.remote,
                    &self.local,
                    now,
                )?
            };
            failure_stage = OwnerFailureStage::IncomingOriginalJoin;
            let original = self
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .originals
                .get(&key(&request.source_ticket))
                .cloned()
                .ok_or("retained original owner absent")?;
            if !same_owner(&original.ticket, &request.source_ticket)
                || (retained_abort
                    && request.source_ticket.action_id
                        != format!("{}.abort", original.ticket.action_id))
                || authority.target_client_id != original.ticket.client_id
                || authority.target_runtime_lease_id != original.ticket.lease_id
                || authority.provider_epoch_id != original.ticket.authority_epoch_id
            {
                return Err("cleanup original target tuple differs".into());
            }
            failure_stage = OwnerFailureStage::IncomingPrepare;
            let previous = self
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .prepared
                .values()
                .find(|p| p.source_ticket == request.source_ticket)
                .map(|p| p.target_ticket.clone());
            let entropy = fresh()?;
            let mut target =
                derive_retained_target_stop(&original.ticket, self.generation, &entropy)?;
            if let Some(prior) = previous {
                target = prior;
            }
            let preparation_revision = self
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .revision
                .checked_add(1)
                .ok_or("cleanup preparation revision exhausted")?;
            let mut prepared = RetainedCleanupPreparedStop {
                schema_id: if retained_abort {
                    "rusty.quest.android.media.retained_abort_prepared_stop.v2"
                } else {
                    "rusty.quest.android.media.retained_cleanup_prepared_stop.v1"
                }
                .into(),
                prepare_request_sha256: hash,
                dispatch_id: request.dispatch_id.clone(),
                target_preparation_revision: preparation_revision,
                source_ticket: request.source_ticket.clone(),
                target_ticket: target,
                authority,
                signer_key_id: self.callbacks.key_id().into(),
                signature_base64: String::new(),
            };
            prepared.signature_base64 = encode_signature_base64(
                &self
                    .callbacks
                    .sign(&retained_cleanup_prepared_signing_bytes(&prepared)?)?,
            );
            let mut next = self
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .clone();
            if next.prepared.len() >= 256 {
                return Err("cleanup preparations full".into());
            }
            next.revision = preparation_revision;
            next.prepared
                .insert(prepared.dispatch_id.clone(), prepared.clone());
            if remote_projection {
                next.remote_requests
                    .insert(request.dispatch_id.clone(), request);
            }
            self.persist(next)?;
            encode(&prepared)
        })();
        owner_failure::observe(result, &self.failure, failure_stage)
    }
}
impl RetainedCleanupAuthoritySource for Cleanup {
    fn authentication_validity_ms(&self) -> Result<u64, String> {
        self.authority.owned_cleanup_authentication_validity_ms()
    }

    fn current_source(
        &self,
        request: &RetainedCleanupDispatchRequest,
        _observed_now: u64,
    ) -> Result<RetainedCleanupLiveSource, String> {
        let _observation = self.serial.enter()?;
        self.require_state()?;
        let now = self.clock.now_ms()?;
        let prepared = self
            .state
            .lock()
            .map_err(|_| "cleanup state poisoned")?
            .prepared
            .get(&request.dispatch_id)
            .cloned()
            .ok_or("independent Stop preparation absent")?;
        let remote_request = self
            .state
            .lock()
            .map_err(|_| "cleanup state poisoned")?
            .remote_requests
            .get(&request.dispatch_id)
            .cloned();
        let mut current = if let Some(remote) = remote_request {
            if digest(&encode(&remote)?)? != prepared.prepare_request_sha256 {
                return Err("remote cleanup retained request differs".into());
            }
            let original = self
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .originals
                .get(&key(&remote.source_ticket))
                .cloned()
                .ok_or("retained original owner absent")?;
            self.remote_projection(&remote, &original, now)?
        } else {
            self.projection(
                &prepared.authority.route_grant_id,
                &prepared.authority.requester_id,
                &prepared.authority.requester_runtime_lease_id,
                &self.remote,
                &self.local,
                now,
            )?
        };
        join_cleanup_projection(&mut current, &prepared.authority, now)?;
        if request.cleanup.target != prepared.target_ticket {
            return Err("prepared cleanup authority/target changed".into());
        }
        // The server's sample preceded admission. Revalidate the complete signed
        // request against the refreshed clock before its replay/effect admission.
        if request.schema_id != OWNED_CLEANUP_DISPATCH_REQUEST_SCHEMA {
            verify_signed_retained_cleanup_request(
                request,
                &current,
                &prepared.target_ticket,
                self.generation,
                &self.local,
                &self.remote_key_id,
                &self.remote_key,
                now,
            )?;
        }
        Ok(RetainedCleanupLiveSource {
            projection: current,
            expected_target: prepared.target_ticket,
            executor_generation: self.generation,
            signer_key_id: self.remote_key_id.clone(),
            signer_key: self.remote_key,
        })
    }
}
pub(super) struct RetainingRegistry {
    pub(super) cleanup: Cleanup,
    pub(super) callbacks: JavaOwnerCallbacks,
}
impl AuthenticatedOwnerRegistry for RetainingRegistry {
    fn execute_and_verify(
        &mut self,
        authority: Option<&OwnerDispatchAuthorityProjection>,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        // A live writer sets poisoned during persistence. Join its transaction
        // before distinguishing that interval from a genuinely failed commit.
        let _mutation = self.cleanup.serial.enter()?;
        self.cleanup.require_state()?;
        if let Some(original) = authority.filter(|a| {
            a.authority_peer_id == self.cleanup.local && a.executor_peer_id == self.cleanup.local
        }) {
            // The local projection was sampled before waiting. Reborrow its
            // actual shared Broker owner through the existing C1 source, which
            // retains the ordinary Stop fallback. A receiving remote original
            // instead keeps its independent enrollment admission below.
            let now = self.cleanup.clock.now_ms()?;
            let source = rusty_quest_broker_authority::QuestEmbeddedDuplexProjectionSource::new(
                self.cleanup.authority.clone(),
                serde_json::from_value(json!(original.route_grant_id)).map_err(safe_decode)?,
                serde_json::from_value(json!(original.authority_peer_id)).map_err(safe_decode)?,
            );
            let current = source.current_projection(ticket, &self.cleanup.local, mode, now)?;
            // Observation receipt digests include their sampling time. Match
            // semantic authority and bounded held expiry, never extend it.
            if original.schema_id != current.schema_id
                || original.authority_peer_id != current.authority_peer_id
                || original.executor_peer_id != current.executor_peer_id
                || original.peer_session_id != current.peer_session_id
                || original.route_grant_id != current.route_grant_id
                || original.route_authority_revision != current.route_authority_revision
                || original.authority_runtime_host_id != current.authority_runtime_host_id
                || original.authority_provider_epoch_id != current.authority_provider_epoch_id
                || original.platform_runtime_spec_id != current.platform_runtime_spec_id
                || original.authority_client_id != current.authority_client_id
                || original.authority_runtime_lease_id != current.authority_runtime_lease_id
                || original.signed_topology_sha256 != current.signed_topology_sha256
                || original.route_configuration_sha256 != current.route_configuration_sha256
                || original.authorization_kind != current.authorization_kind
                || original.expires_at_ms <= now
                || original.expires_at_ms > current.expires_at_ms
            {
                return Err("cleanup admission current authority differs".into());
            }
        }
        // Abort carriers retain operation=Start, but Stop/Cleanup must never
        // replace the forward ticket/projection needed to authenticate cleanup.
        let retain_forward = ticket.operation == MediaStreamPlatformOperation::Start
            && matches!(
                ticket.action_kind,
                MediaStreamOwnerActionKind::ArmCleanup
                    | MediaStreamOwnerActionKind::ArmReceiver
                    | MediaStreamOwnerActionKind::Start
            );
        if retain_forward {
            // The dispatcher validated the incoming signed current-route request
            // before entering this registry. Persist its live enrollment witness
            // alongside the exact original before any uncertain provider effect.
            let retained_enrollment = match authority {
                Some(original)
                    if original.authority_peer_id == self.cleanup.remote
                        && original.executor_peer_id == self.cleanup.local =>
                {
                    Some(
                        self.cleanup
                            .authority
                            .capture_retained_original_enrollment(
                                original,
                                ticket,
                                &self.cleanup.remote_key_id,
                                &self.cleanup.remote_key,
                                self.cleanup.clock.now_ms()?,
                            )?,
                    )
                }
                Some(original)
                    if original.authority_peer_id == self.cleanup.local
                        && original.executor_peer_id == self.cleanup.local =>
                {
                    None
                }
                None => None,
                Some(_) => return Err("retained original peer not paired".into()),
            };
            let mut next = self
                .cleanup
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .clone();
            if next.originals.len() >= 256 && !next.originals.contains_key(&key(ticket)) {
                return Err("retained original capacity".into());
            }
            next.originals.insert(
                key(ticket),
                Original {
                    ticket: ticket.clone(),
                    effect: None,
                    source_authority: authority.cloned(),
                    retained_enrollment,
                },
            );
            self.cleanup.persist(next)?;
        }
        let effect = AuthenticatedOwnerRegistry::execute_and_verify(
            &mut self.callbacks,
            authority,
            ticket,
            mode,
        )?;
        if retain_forward {
            let mut next = self
                .cleanup
                .state
                .lock()
                .map_err(|_| "cleanup state poisoned")?
                .clone();
            next.originals
                .get_mut(&key(ticket))
                .ok_or("retained original lost")?
                .effect = Some(effect.clone());
            self.cleanup.persist(next)?;
        }
        Ok(effect)
    }
}
pub(super) type Server = RetainedCleanupDispatchServer<
    Cleanup,
    JavaOwnerCallbacks,
    JavaOwnerCallbacks,
    AuthorityClock,
    JavaOwnerCallbacks,
>;

struct PendingRemote {
    request: Prepare,
    prepared: Option<RetainedCleanupPreparedStop>,
    commit: Option<RetainedCleanupDispatchRequest>,
    response: Option<Vec<u8>>,
}
pub(super) struct Executor {
    pub(super) ordinary: CompositeAndroidMediaOwnerExecutor,
    pub(super) cleanup: Cleanup,
    pub(super) placements: Vec<AndroidMediaOwnerPlacement>,
    pub(super) grant: Arc<Mutex<String>>,
    pending: BTreeMap<String, PendingRemote>,
    active: BTreeMap<String, String>,
    uncertain: std::collections::BTreeSet<String>,
    verified: Mutex<BTreeMap<String, AndroidMediaOwnerReadback>>,
    sequence: u64,
}
impl Executor {
    pub(super) fn new(
        ordinary: CompositeAndroidMediaOwnerExecutor,
        cleanup: Cleanup,
        placements: Vec<AndroidMediaOwnerPlacement>,
        grant: Arc<Mutex<String>>,
    ) -> Self {
        Self {
            ordinary,
            cleanup,
            placements,
            grant,
            pending: BTreeMap::new(),
            active: BTreeMap::new(),
            uncertain: std::collections::BTreeSet::new(),
            verified: Mutex::new(BTreeMap::new()),
            sequence: 0,
        }
    }
}
impl AndroidMediaOwnerExecutor for Executor {
    fn executor_generation(&self) -> u64 {
        self.cleanup.generation
    }

    fn executor_generation_for(
        &self,
        action: &rusty_quest_media_stream::MediaStreamPlatformAction,
        owner: &rusty_quest_media_stream::MediaStreamOwnerAction,
    ) -> Result<u64, String> {
        let retained_requester = self.cleanup.requester.lock()
            .map_err(|_| "cleanup requester poisoned")?.is_some();
        // Same closed branch as execute below. Retained cleanup issues a local
        // source carrier; the recipient separately authors/signs its actual Stop.
        let retained_action = action.operation == MediaStreamPlatformOperation::Stop
            || (action.operation == MediaStreamPlatformOperation::Start
                && action.action_id.ends_with(".abort")
                && matches!(owner.action_kind,
                    rusty_quest_media_stream::MediaStreamOwnerActionKind::Stop
                    | rusty_quest_media_stream::MediaStreamOwnerActionKind::Cleanup));
        if retained_requester && retained_action {
            Ok(self.cleanup.generation)
        } else {
            owner_failure::observe(
                self.ordinary.executor_generation_for(action, owner),
                &self.cleanup.failure,
                OwnerFailureStage::OrdinaryCallback,
            )
        }
    }

    fn execute(
        &mut self,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AndroidMediaOwnerReadback, String> {
        let mut failure_stage = OwnerFailureStage::Executor;
        let result = (|| {
            let requester = self
                .cleanup
                .requester
                .lock()
                .map_err(|_| "cleanup requester poisoned")?
                .clone();
            let Some(requester) = requester.filter(|_| {
                ticket.operation == MediaStreamPlatformOperation::Stop
                    || is_retained_start_abort_ticket(ticket)
            }) else {
                failure_stage = OwnerFailureStage::OrdinaryCallback;
                return self.ordinary.execute(ticket, mode);
            };
            let placement = self
                .placements
                .iter()
                .find(|p| {
                    p.owner_kind == ticket.owner_kind
                        && p.owner_id == ticket.owner_id
                        && p.provider_kind == ticket.provider_kind
                        && p.resource_id == ticket.resource_id
                })
                .ok_or("cleanup placement absent")?;
            let grant = self
                .grant
                .lock()
                .map_err(|_| "cleanup route poisoned")?
                .clone();
            let now = self.cleanup.clock.now_ms()?;
            let readback = match &placement.target {
                AndroidMediaOwnerPlacementTarget::Local => {
                    failure_stage = OwnerFailureStage::LocalProjection;
                    let projection = self
                        .cleanup
                        .authority
                        .owned_retained_local_cleanup_projection(
                            &serde_json::from_value(json!(grant)).map_err(safe_decode)?,
                            &serde_json::from_value(json!(requester.id)).map_err(safe_decode)?,
                            &serde_json::from_value(json!(requester.lease)).map_err(safe_decode)?,
                            &serde_json::from_value(json!(self.cleanup.local))
                                .map_err(safe_decode)?,
                            now,
                        )?;
                    // Original rollback remains a Start-operation carrier. The
                    // actual target callback receives only a Stop, and its live
                    // effect is verified before projecting original action fields.
                    let mut target = ticket.clone();
                    target.operation = MediaStreamPlatformOperation::Stop;
                    failure_stage = OwnerFailureStage::LocalCallback;
                    let effect = RetainedCleanupRegistry::execute_and_verify(
                        &mut self.cleanup.callbacks,
                        &projection,
                        &target,
                        mode,
                    )?;
                    failure_stage = OwnerFailureStage::SourceReadback;
                    retained_local_source_readback(ticket, &target, effect)?
                }
                AndroidMediaOwnerPlacementTarget::Remote { peer_id } => {
                    failure_stage = OwnerFailureStage::RemotePeerBind;
                    if peer_id != &self.cleanup.remote {
                        return Err("cleanup unbound peer".into());
                    }
                    failure_stage = OwnerFailureStage::RemotePrepare;
                    let base_key = format!("{}|{}", ticket.capability, requester.lease);
                    let previous = self.active.get(&base_key).and_then(|k| self.pending.get(k));
                    let reusable = previous
                        .map(|p| {
                            Ok::<_, String>(
                                p.response.is_none()
                                    && (p.commit.is_some()
                                        || now.saturating_sub(p.request.issued_at_ms)
                                            < self
                                                .cleanup
                                                .prepare_authentication_validity(&p.request)?),
                            )
                        })
                        .transpose()?
                        .unwrap_or(false);

                    let pending_key = if reusable {
                        self.active
                            .get(&base_key)
                            .cloned()
                            .ok_or("cleanup active lost")?
                    } else {
                        failure_stage = OwnerFailureStage::RemoteSequence;
                        format!(
                            "{}|{}",
                            base_key,
                            self.sequence
                                .checked_add(1)
                                .ok_or("cleanup sequence exhausted")?
                        )
                    };
                    let commit_mode = if self.uncertain.contains(&ticket.capability) {
                        AndroidMediaExecutionMode::CompensateUncertain
                    } else {
                        mode
                    };
                    if !self.pending.contains_key(&pending_key) {
                        failure_stage = OwnerFailureStage::RemotePendingCapacity;
                        if self.pending.len() >= 256 {
                            return Err("cleanup pending capacity".into());
                        }
                        failure_stage = OwnerFailureStage::RemoteSequence;
                        self.sequence = self
                            .sequence
                            .checked_add(1)
                            .ok_or("cleanup sequence exhausted")?;
                        failure_stage = OwnerFailureStage::RemoteRequesterAuthority;
                        let authority = self.cleanup.projection(
                            &grant,
                            &requester.id,
                            &requester.lease,
                            &self.cleanup.local,
                            &self.cleanup.remote,
                            now,
                        )?;
                        let renewal_ancestry = self
                            .cleanup
                            .authority
                            .retained_cleanup_renewal_ancestry(&authority)?;
                        failure_stage = OwnerFailureStage::RemoteEntropy;
                        let mut request = Prepare {
                            schema_id: if is_retained_start_abort_ticket(ticket) {
                                "rusty.quest.android.media.retained_abort_prepare_request.v4"
                            } else {
                                "rusty.quest.android.media.retained_cleanup_prepare_request.v3"
                            }
                            .into(),
                            dispatch_id: format!("cleanup.dispatch.{}", fresh()?),
                            source_ticket: ticket.clone(),
                            requester_id: requester.id.clone(),
                            requester_lease_id: requester.lease.clone(),
                            route_grant_id: grant.clone(),
                            sequence: self.sequence,
                            issued_at_ms: now,
                            signer_key_id: self.cleanup.callbacks.key_id().into(),
                            signature_base64: String::new(),
                            authority: Some(authority),
                            renewal_ancestry,
                        };
                        failure_stage = OwnerFailureStage::RemotePrepareEncode;
                        let mut bytes = prepare_domain(&request.schema_id)?.to_vec();
                        bytes.extend(encode(&request)?);
                        if bytes.len() > 128 * 1024 - 128 {
                            return Err("cleanup signed Prepare frame bounds".into());
                        }
                        failure_stage = OwnerFailureStage::RemotePrepareSign;
                        request.signature_base64 =
                            encode_signature_base64(&self.cleanup.callbacks.sign(&bytes)?);
                        self.active.insert(base_key, pending_key.clone());
                        self.pending.insert(
                            pending_key.clone(),
                            PendingRemote {
                                request,
                                prepared: None,
                                commit: None,
                                response: None,
                            },
                        );
                    }
                    failure_stage = OwnerFailureStage::RemotePrepare;
                    let pending = self
                        .pending
                        .get_mut(&pending_key)
                        .ok_or("cleanup pending disappeared")?;
                    if pending.prepared.is_none() {
                        failure_stage = OwnerFailureStage::RemotePrepareEncode;
                        let mut frame = PREPARE_MAGIC.to_vec();
                        frame.extend(encode(&pending.request)?);
                        failure_stage = OwnerFailureStage::RemotePrepareExchange;
                        let bytes = self.cleanup.callbacks.exchange(&frame, 128 * 1024)?;
                        failure_stage = OwnerFailureStage::RemotePrepareProof;
                        let prepared: RetainedCleanupPreparedStop = serde_json::from_slice(&bytes)
                            .map_err(|_| "cleanup preparation response decode")?;
                        verify_retained_cleanup_prepared(
                            &prepared,
                            ticket,
                            &self.cleanup.remote,
                            &self.cleanup.remote_key_id,
                            &self.cleanup.remote_key,
                        )?;
                        if prepared.prepare_request_sha256 != digest(&encode(&pending.request)?)? {
                            return Err("cleanup preparation request changed".into());
                        }
                        let observed_now = self.cleanup.clock.now_ms()?;
                        failure_stage = OwnerFailureStage::RemoteProjection;
                        let mut local = self.cleanup.projection(
                            &pending.request.route_grant_id,
                            &pending.request.requester_id,
                            &pending.request.requester_lease_id,
                            &self.cleanup.local,
                            &self.cleanup.remote,
                            observed_now,
                        )?;
                        join_cleanup_projection(&mut local, &prepared.authority, observed_now)?;
                        pending.prepared = Some(prepared);
                    }
                    let prepared = pending.prepared.clone().ok_or("cleanup prepare absent")?;
                    failure_stage = OwnerFailureStage::RemoteCommit;
                    if pending.commit.is_none() {
                        let effect_sha = digest(&encode(&(
                            &prepared.authority,
                            &prepared.target_ticket,
                            commit_mode,
                        ))?)?;
                        let mut request = RetainedCleanupDispatchRequest {
                            schema_id: if prepared.authority.schema_id
                                == OWNED_CLEANUP_PROJECTION_SCHEMA
                            {
                                OWNED_CLEANUP_DISPATCH_REQUEST_SCHEMA
                            } else {
                                RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA
                            }
                            .into(),
                            dispatch_id: prepared.dispatch_id.clone(),
                            sequence: pending.request.sequence,
                            issued_at_ms: self.cleanup.clock.now_ms()?,
                            target_peer_id: self.cleanup.remote.clone(),
                            cleanup: RetainedCleanupExecutionTicket {
                                schema_id: if prepared.authority.schema_id
                                    == OWNED_CLEANUP_PROJECTION_SCHEMA
                                {
                                    OWNED_CLEANUP_TICKET_SCHEMA
                                } else {
                                    RETAINED_CLEANUP_TICKET_SCHEMA
                                }
                                .into(),
                                authority: prepared.authority.clone(),
                                target: prepared.target_ticket.clone(),
                                mode: commit_mode,
                                owner_effect_sha256: effect_sha,
                            },
                            signer_key_id: self.cleanup.callbacks.key_id().into(),
                            signature_base64: String::new(),
                        };
                        sign_retained_cleanup_request(&mut request, &self.cleanup.callbacks)?;
                        pending.commit = Some(request);
                    }
                    self.uncertain.insert(ticket.capability.clone());
                    let mut refreshes = 0;
                    let commit = loop {
                        let commit = pending.commit.clone().ok_or("cleanup commit absent")?;
                        if pending.response.is_none() {
                            failure_stage = OwnerFailureStage::RemoteProjection;
                            let current_now = self.cleanup.clock.now_ms()?;
                            let mut current = self.cleanup.projection(
                                &pending.request.route_grant_id,
                                &pending.request.requester_id,
                                &pending.request.requester_lease_id,
                                &self.cleanup.local,
                                &self.cleanup.remote,
                                current_now,
                            )?;
                            join_cleanup_projection(
                                &mut current,
                                &prepared.authority,
                                current_now,
                            )?;
                            failure_stage = OwnerFailureStage::RemoteExchange;
                            // After any uncertain transport, reuse these exact bytes/ID.
                            // The target's once table returns cached terminal/Pending
                            // observations before considering a new effect's age.
                            pending.response = Some(
                                self.cleanup
                                    .callbacks
                                    .exchange(&encode(&commit)?, 128 * 1024)?,
                            );
                        }
                        if commit.schema_id == OWNED_CLEANUP_DISPATCH_REQUEST_SCHEMA {
                            let response = verify_retained_cleanup_response(
                                pending.response.as_ref().unwrap(),
                                &commit,
                                &prepared.target_ticket,
                                &self.cleanup.remote,
                                &self.cleanup.remote_key_id,
                                &self.cleanup.remote_key,
                            )?;
                            if response.status == OwnerDispatchStatus::Rejected {
                                // This precise signed response authenticates that
                                // no reservation/SDK effect was entered for the
                                // exact old envelope. Never refresh on IO failure.
                                if response.failure.as_deref()
                                    != Some(OWNED_CLEANUP_AUTHENTICATION_EXPIRED)
                                    || refreshes != 0
                                {
                                    return Err(
                                        "owned cleanup authentication renewal remains Pending"
                                            .into(),
                                    );
                                }
                                let mut refreshed = commit.clone();
                                refreshed.issued_at_ms = self.cleanup.clock.now_ms()?;
                                refreshed.signature_base64.clear();
                                sign_retained_cleanup_request(
                                    &mut refreshed,
                                    &self.cleanup.callbacks,
                                )?;
                                pending.commit = Some(refreshed);
                                pending.response = None;
                                refreshes += 1;
                                continue;
                            }
                        }
                        break commit;
                    };
                    failure_stage = OwnerFailureStage::RemoteProof;
                    let proof = RemoteRetainedCleanupEffect {
                        schema_id: if is_retained_start_abort_ticket(ticket) {
                            "rusty.quest.android.media.remote_retained_abort_effect.v2"
                        } else {
                            "rusty.quest.android.media.remote_retained_cleanup_effect.v1"
                        }
                        .into(),
                        prepared,
                        commit,
                        response_bytes: pending
                            .response
                            .clone()
                            .ok_or("cleanup response absent")?,
                        enrolled_target_key: self.cleanup.remote_key,
                    };
                    let readback = proof.source_readback(
                        ticket,
                        &self.cleanup.remote_key_id,
                        &self.cleanup.remote_key,
                    )?;
                    self.uncertain.remove(&ticket.capability);
                    readback
                }
            };
            // Only physically verified actual local effect or signed target Stop reaches this table.
            failure_stage = OwnerFailureStage::SourceReadback;
            validate_readback(ticket, &readback).map_err(|_| "cleanup source readback rejected")?;
            self.verified
                .lock()
                .map_err(|_| "cleanup verification poisoned")?
                .insert(ticket.capability.clone(), readback.clone());
            Ok(readback)
        })();
        owner_failure::observe(result, &self.cleanup.failure, failure_stage)
    }
    fn verify(
        &self,
        ticket: &AndroidMediaExecutionTicket,
        readback: &AndroidMediaOwnerReadback,
    ) -> bool {
        if self
            .verified
            .lock()
            .ok()
            .and_then(|mut v| v.remove(&ticket.capability))
            .is_some_and(|r| r == *readback)
        {
            true
        } else {
            self.ordinary.verify(ticket, readback)
        }
    }
}
