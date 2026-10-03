//! Fixed signed prepare/commit cleanup; original target and fresh requester are separate.
use super::*;
use ed25519_dalek::{Signature, VerifyingKey};
use rusty_quest_media_stream_android::*;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::io::Read;
pub(super) const PREPARE_MAGIC: &[u8] = b"RQCP1\0";
const DOMAIN: &[u8] = b"rusty.quest.android.media.retained_cleanup_prepare.v1\0";
const ABORT_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_abort_prepare.v2\0";

fn prepare_domain(schema: &str) -> Result<&'static [u8], String> {
    match schema {
        "rusty.quest.android.media.retained_cleanup_prepare_request.v1" => Ok(DOMAIN),
        "rusty.quest.android.media.retained_abort_prepare_request.v2" => Ok(ABORT_DOMAIN),
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
}
#[derive(Clone, Serialize, Deserialize)]
struct Original {
    ticket: AndroidMediaExecutionTicket,
    effect: Option<AuthenticatedOwnerEffect>,
}
#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct PreparedState {
    #[serde(default)]
    revision: u64,
    originals: BTreeMap<String, Original>,
    prepared: BTreeMap<String, RetainedCleanupPreparedStop>,
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
    serial: Arc<Mutex<Option<()>>>,
    state: Arc<Mutex<PreparedState>>,
    pub(super) requester: Arc<Mutex<Option<Requester>>>,
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
    pub(super) fn new(
        authority: QuestEmbeddedDuplexAuthority,
        callbacks: JavaOwnerCallbacks,
        clock: AuthorityClock,
        local: String,
        remote: String,
        remote_key_id: String,
        remote_key: [u8; 32],
        generation: u64,
    ) -> Result<Self, String> {
        let state: PreparedState = serde_json::from_str(&callbacks.load_cleanup_preparations()?)
            .map_err(|_| "cleanup preparations decode")?;
        if state.originals.len() > 256 || state.prepared.len() > 256 {
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
            serial: Arc::new(Mutex::new(Some(()))),
            state: Arc::new(Mutex::new(state)),
            requester: Arc::new(Mutex::new(None)),
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
        self.authority.retained_cleanup_projection(
            &serde_json::from_value(json!(grant)).map_err(safe_decode)?,
            &serde_json::from_value(json!(requester)).map_err(safe_decode)?,
            &serde_json::from_value(json!(lease)).map_err(safe_decode)?,
            &serde_json::from_value(json!(authority_peer)).map_err(safe_decode)?,
            &serde_json::from_value(json!(executor)).map_err(safe_decode)?,
            now,
        )
    }
    pub(super) fn prepare_frame(&self, bytes: &[u8]) -> Result<Vec<u8>, String> {
        self.require_state()?;
        let _mutation = Checkout::take(self.serial.clone())?;
        if bytes.len() > 128 * 1024 || !bytes.starts_with(PREPARE_MAGIC) {
            return Err("cleanup prepare frame bounds".into());
        }
        let request: Prepare = serde_json::from_slice(&bytes[PREPARE_MAGIC.len()..])
            .map_err(|_| "cleanup prepare decode")?;
        let now = self.clock.now_ms()?;
        let retained_abort = request.schema_id == "rusty.quest.android.media.retained_abort_prepare_request.v2"
            && is_retained_start_abort_ticket(&request.source_ticket);
        if !(retained_abort || (request.schema_id == "rusty.quest.android.media.retained_cleanup_prepare_request.v1"
            && request.source_ticket.operation == MediaStreamPlatformOperation::Stop))
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
        if let Some(prepared) = self
            .state
            .lock()
            .map_err(|_| "cleanup state poisoned")?
            .prepared
            .get(&request.dispatch_id)
            .cloned()
        {
            if prepared.prepare_request_sha256 != hash {
                return Err("cleanup prepare replay collision".into());
            }
            return encode(&prepared);
        }
        let authority = self.projection(
            &request.route_grant_id,
            &request.requester_id,
            &request.requester_lease_id,
            &self.remote,
            &self.local,
            now,
        )?;
        let original = self
            .state
            .lock()
            .map_err(|_| "cleanup state poisoned")?
            .originals
            .get(&key(&request.source_ticket))
            .cloned()
            .ok_or("retained original owner absent")?;
        if !same_owner(&original.ticket, &request.source_ticket)
            || (retained_abort && request.source_ticket.action_id != format!("{}.abort", original.ticket.action_id))
            || authority.target_client_id != original.ticket.client_id
            || authority.target_runtime_lease_id != original.ticket.lease_id
            || authority.provider_epoch_id != original.ticket.authority_epoch_id
        {
            return Err("cleanup original target tuple differs".into());
        }
        let previous = self
            .state
            .lock()
            .map_err(|_| "cleanup state poisoned")?
            .prepared
            .values()
            .find(|p| p.source_ticket == request.source_ticket)
            .map(|p| p.target_ticket.clone());
        let entropy = fresh()?;
        let mut target = derive_retained_target_stop(&original.ticket, self.generation, &entropy)?;
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
            schema_id: if retained_abort {"rusty.quest.android.media.retained_abort_prepared_stop.v2"}
                else {"rusty.quest.android.media.retained_cleanup_prepared_stop.v1"}.into(),
            prepare_request_sha256: hash,
            dispatch_id: request.dispatch_id,
            target_preparation_revision: preparation_revision,
            source_ticket: request.source_ticket,
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
        self.persist(next)?;
        encode(&prepared)
    }
}
impl RetainedCleanupAuthoritySource for Cleanup {
    fn current_source(
        &self,
        request: &RetainedCleanupDispatchRequest,
        now: u64,
    ) -> Result<RetainedCleanupLiveSource, String> {
        self.require_state()?;
        let prepared = self
            .state
            .lock()
            .map_err(|_| "cleanup state poisoned")?
            .prepared
            .get(&request.dispatch_id)
            .cloned()
            .ok_or("independent Stop preparation absent")?;
        let mut current = self.projection(
            &prepared.authority.route_grant_id,
            &prepared.authority.requester_id,
            &prepared.authority.requester_runtime_lease_id,
            &self.remote,
            &self.local,
            now,
        )?;
        if prepared.authority.expires_at_ms > current.expires_at_ms
            || prepared.authority.expires_at_ms <= now
        {
            return Err("prepared cleanup expired".into());
        }
        current.expires_at_ms = prepared.authority.expires_at_ms;
        if current != prepared.authority || request.cleanup.target != prepared.target_ticket {
            return Err("prepared cleanup authority/target changed".into());
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
        self.cleanup.require_state()?;
        let _mutation = Checkout::take(self.cleanup.serial.clone())?;
        if ticket.operation == MediaStreamPlatformOperation::Start {
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
        if ticket.operation == MediaStreamPlatformOperation::Start {
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
    fn execute(
        &mut self,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AndroidMediaOwnerReadback, String> {
        let requester = self
            .cleanup
            .requester
            .lock()
            .map_err(|_| "cleanup requester poisoned")?
            .clone();
        let Some(requester) =
            requester.filter(|_| ticket.operation == MediaStreamPlatformOperation::Stop || is_retained_start_abort_ticket(ticket))
        else {
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
                let projection = self.cleanup.authority.retained_local_cleanup_projection(
                    &serde_json::from_value(json!(grant)).map_err(safe_decode)?,
                    &serde_json::from_value(json!(requester.id)).map_err(safe_decode)?,
                    &serde_json::from_value(json!(requester.lease)).map_err(safe_decode)?,
                    &serde_json::from_value(json!(self.cleanup.local)).map_err(safe_decode)?,
                    now,
                )?;
                // Original rollback remains a Start-operation carrier. The
                // actual target callback receives only a Stop, and its live
                // effect is verified before projecting original action fields.
                let mut target=ticket.clone();
                target.operation=MediaStreamPlatformOperation::Stop;
                let effect=RetainedCleanupRegistry::execute_and_verify(
                    &mut self.cleanup.callbacks,
                    &projection,
                    &target,
                    mode,
                )?;
                retained_local_source_readback(ticket,&target,effect)?
            }
            AndroidMediaOwnerPlacementTarget::Remote { peer_id } => {
                if peer_id != &self.cleanup.remote {
                    return Err("cleanup unbound peer".into());
                }
                let base_key = format!("{}|{}", ticket.capability, requester.lease);
                let previous = self.active.get(&base_key).and_then(|k| self.pending.get(k));
                let reusable = previous.is_some_and(|p| {
                    now.saturating_sub(p.request.issued_at_ms) < 30000 && p.response.is_none()
                });
                let pending_key = if reusable {
                    self.active
                        .get(&base_key)
                        .cloned()
                        .ok_or("cleanup active lost")?
                } else {
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
                    if self.pending.len() >= 256 {
                        return Err("cleanup pending capacity".into());
                    }
                    self.sequence = self
                        .sequence
                        .checked_add(1)
                        .ok_or("cleanup sequence exhausted")?;
                    let mut request = Prepare {
                        schema_id: if is_retained_start_abort_ticket(ticket) {"rusty.quest.android.media.retained_abort_prepare_request.v2"}
                            else {"rusty.quest.android.media.retained_cleanup_prepare_request.v1"}.into(),
                        dispatch_id: format!("cleanup.dispatch.{}", fresh()?),
                        source_ticket: ticket.clone(),
                        requester_id: requester.id,
                        requester_lease_id: requester.lease,
                        route_grant_id: grant,
                        sequence: self.sequence,
                        issued_at_ms: now,
                        signer_key_id: self.cleanup.callbacks.key_id().into(),
                        signature_base64: String::new(),
                    };
                    let mut bytes = prepare_domain(&request.schema_id)?.to_vec();
                    bytes.extend(encode(&request)?);
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
                let pending = self
                    .pending
                    .get_mut(&pending_key)
                    .ok_or("cleanup pending disappeared")?;
                if pending.prepared.is_none() {
                    let mut frame = PREPARE_MAGIC.to_vec();
                    frame.extend(encode(&pending.request)?);
                    let bytes = self.cleanup.callbacks.exchange(&frame, 128 * 1024)?;
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
                    let mut local = self.cleanup.projection(
                        &pending.request.route_grant_id,
                        &pending.request.requester_id,
                        &pending.request.requester_lease_id,
                        &self.cleanup.local,
                        &self.cleanup.remote,
                        observed_now,
                    )?;
                    if prepared.authority.expires_at_ms > local.expires_at_ms
                        || prepared.authority.expires_at_ms <= observed_now
                    {
                        return Err("cleanup prepare deadline differs".into());
                    }
                    local.expires_at_ms = prepared.authority.expires_at_ms;
                    if local != prepared.authority {
                        return Err("cleanup preparation live join differs".into());
                    }
                    pending.prepared = Some(prepared);
                }
                let prepared = pending.prepared.clone().ok_or("cleanup prepare absent")?;
                if pending.commit.is_none() {
                    let effect_sha = digest(&encode(&(
                        &prepared.authority,
                        &prepared.target_ticket,
                        commit_mode,
                    ))?)?;
                    let mut request = RetainedCleanupDispatchRequest {
                        schema_id: RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA.into(),
                        dispatch_id: prepared.dispatch_id.clone(),
                        sequence: pending.request.sequence,
                        issued_at_ms: now,
                        target_peer_id: self.cleanup.remote.clone(),
                        cleanup: RetainedCleanupExecutionTicket {
                            schema_id: RETAINED_CLEANUP_TICKET_SCHEMA.into(),
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
                let commit = pending.commit.clone().ok_or("cleanup commit absent")?;
                self.uncertain.insert(ticket.capability.clone());
                if pending.response.is_none() {
                    pending.response = Some(
                        self.cleanup
                            .callbacks
                            .exchange(&encode(&commit)?, 128 * 1024)?,
                    );
                }
                let proof = RemoteRetainedCleanupEffect {
                    schema_id: if is_retained_start_abort_ticket(ticket) {"rusty.quest.android.media.remote_retained_abort_effect.v2"}
                        else {"rusty.quest.android.media.remote_retained_cleanup_effect.v1"}.into(),
                    prepared,
                    commit,
                    response_bytes: pending.response.clone().ok_or("cleanup response absent")?,
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
        validate_readback(ticket, &readback).map_err(|_| "cleanup source readback rejected")?;
        self.verified
            .lock()
            .map_err(|_| "cleanup verification poisoned")?
            .insert(ticket.capability.clone(), readback.clone());
        Ok(readback)
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
