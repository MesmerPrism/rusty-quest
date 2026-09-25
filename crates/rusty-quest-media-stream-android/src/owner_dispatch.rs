//! Bounded authenticated dispatch of exact owner tickets between two app processes.
//!
//! The server authenticates the peer and the C1 authority projection before it
//! invokes the process-local registry. The registry returns both its raw
//! readback and the one-shot Java-verified evidence in one call. That exact
//! result is cached before a response is returned, so a lost response is
//! replayed without executing or verifying the platform effect twice.

use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Mutex,
};

use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{AndroidMediaExecutionMode, AndroidMediaExecutionTicket, AndroidMediaOwnerReadback};

/// Signed request schema for a remote platform owner.
pub const OWNER_DISPATCH_REQUEST_SCHEMA: &str =
    "rusty.quest.android.media.owner_dispatch_request.v1";
/// Signed response schema for a remote platform owner.
pub const OWNER_DISPATCH_RESPONSE_SCHEMA: &str =
    "rusty.quest.android.media.owner_dispatch_response.v1";
/// Signed full-product activation notice schema.
pub const PRODUCT_ACTIVATION_REQUEST_SCHEMA: &str =
    "rusty.quest.android.media.product_activation_request.v1";
/// Signed target acknowledgement schema.
pub const PRODUCT_ACTIVATION_RESPONSE_SCHEMA: &str =
    "rusty.quest.android.media.product_activation_response.v1";
/// Target-owned activation readback schema.
pub const PRODUCT_ACTIVATION_READBACK_SCHEMA: &str =
    "rusty.quest.android.media.product_activation_readback.v1";
/// Java-verified effect evidence schema.
pub const VERIFIED_OWNER_EFFECT_SCHEMA: &str = "rusty.quest.android.media.verified_owner_effect.v1";
/// Maximum age of a newly executed signed request at its target.
pub const MAX_OWNER_DISPATCH_REQUEST_AGE_MS: u64 = 30_000;
/// Maximum positive source wall-clock skew tolerated by a target.
pub const MAX_OWNER_DISPATCH_FUTURE_SKEW_MS: u64 = 2_000;
/// Maximum encoded frame accepted from an untrusted peer.
pub const MAX_OWNER_DISPATCH_FRAME_BYTES: usize = 128 * 1024;
/// Maximum terminal responses retained for exactly-once retries.
pub const MAX_OWNER_DISPATCH_REPLAYS: usize = 256;
/// Maximum uncertain requests retained across restart.
pub const MAX_OWNER_DISPATCH_PENDING: usize = 32;
/// Maximum encoded replay snapshot size.
pub const MAX_OWNER_DISPATCH_REPLAY_BYTES: usize = 16 * 1024 * 1024;
const OWNER_DISPATCH_MAGIC: &[u8; 6] = b"RQOD1\n";
const OWNER_DISPATCH_REQUEST_DOMAIN: &[u8] =
    b"rusty.quest.android.media.owner_dispatch_envelope.v1\0request\0";
const OWNER_DISPATCH_RESPONSE_DOMAIN: &[u8] =
    b"rusty.quest.android.media.owner_dispatch_envelope.v1\0terminal_response\0";
const PRODUCT_ACTIVATION_REQUEST_DOMAIN: &[u8] =
    b"rusty.quest.android.media.owner_dispatch_envelope.v1\0product_activation\0";
const PRODUCT_ACTIVATION_RESPONSE_DOMAIN: &[u8] =
    b"rusty.quest.android.media.owner_dispatch_envelope.v1\0product_activation_ack\0";

/// Exact current C1 authorization projected by the authority process.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct OwnerDispatchAuthorityProjection {
    /// Projection schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Peer that owns and signs this projection.
    pub authority_peer_id: String,
    /// Peer allowed to execute the target owner.
    pub executor_peer_id: String,
    /// Current mixed peer-session id.
    pub peer_session_id: String,
    /// Current pair-media-route grant.
    pub route_grant_id: String,
    /// Current route authority revision.
    pub route_authority_revision: u64,
    /// Source Runtime Host that performed the live Broker join.
    pub authority_runtime_host_id: String,
    /// Current live Broker provider epoch.
    pub authority_provider_epoch_id: String,
    /// Exact platform runtime specification.
    pub platform_runtime_spec_id: String,
    /// Client authorized by the current live Broker lease.
    pub authority_client_id: String,
    /// Current live Broker runtime lease.
    pub authority_runtime_lease_id: String,
    /// Digest of the accepted signed topology evidence.
    pub signed_topology_sha256: String,
    /// Digest of the accepted route configuration.
    pub route_configuration_sha256: String,
    /// Digest of the exact current receipt or retained terminal route evidence.
    pub route_authority_evidence_sha256: String,
    /// Upper bound for this projection.
    pub expires_at_ms: u64,
    /// Whether this authorizes a current route effect or retained terminal cleanup.
    pub authorization_kind: OwnerDispatchAuthorizationKind,
}

/// Closed reason a source authority issued an owner projection.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum OwnerDispatchAuthorizationKind {
    /// The route and original live Broker leases are current.
    CurrentRoute,
    /// A terminal route has pending cleanup and a current original/revoker lease.
    RetainedCleanup,
}

/// One signed exact-ticket request.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct OwnerDispatchRequest {
    /// Schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Stable retry identity.
    pub dispatch_id: String,
    /// Strictly increasing sender sequence.
    pub sequence: u64,
    /// Current wall clock supplied to the bounded authority validator.
    pub issued_at_ms: u64,
    /// Exact target peer.
    pub target_peer_id: String,
    /// Exact authority projection.
    pub authority: OwnerDispatchAuthorityProjection,
    /// Exact registry ticket.
    pub ticket: AndroidMediaExecutionTicket,
    /// Execute or compensate an uncertain effect.
    pub mode: AndroidMediaExecutionMode,
    /// Signer key id selected by current reciprocal authority.
    pub signer_key_id: String,
    /// Standard padded base64 Ed25519 signature over the canonical unsigned request.
    pub signature_base64: String,
}

/// Java-authenticated provider result. The evidence is consumed exactly once.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AuthenticatedOwnerEffect {
    /// Exact raw registry readback.
    pub readback: AndroidMediaOwnerReadback,
    /// Exact raw readback JSON whose digest Java checked.
    pub readback_json: String,
    /// One-shot Java verification evidence.
    pub verified: VerifiedOwnerEffect,
}

/// Closed fields emitted by `verifyAndReadEvidence` after registry re-read.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct VerifiedOwnerEffect {
    /// Schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact provider receipt.
    pub receipt_id: String,
    /// SHA-256 of the exact raw readback JSON.
    pub readback_sha256: String,
    /// Installed registry generation.
    pub executor_generation: u64,
    /// Current provider revision.
    pub provider_state_revision: u64,
    /// Current provider state.
    pub observed_state: String,
    /// Whether the provider proved resource absence.
    pub terminal: bool,
    /// Current provider handle.
    pub provider_handle_id: String,
    /// SHA-256 of the provider-authored detail.
    pub detail_sha256: String,
}

/// Result status signed by the executor peer.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum OwnerDispatchStatus {
    /// The registry effect and readback were authenticated.
    Completed,
    /// The platform call may have had an effect and must be compensated.
    Uncertain,
    /// No effect was attempted because authority or input was rejected.
    Rejected,
}

/// Signed terminal response cached by dispatch id.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct OwnerDispatchResponse {
    /// Schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact request identity.
    pub dispatch_id: String,
    /// Digest of exact request bytes.
    pub request_sha256: String,
    /// Terminal status.
    pub status: OwnerDispatchStatus,
    /// Registry evidence only for completed effects.
    pub effect: Option<AuthenticatedOwnerEffect>,
    /// Non-sensitive bounded failure classification.
    pub failure: Option<String>,
    /// Executor signing-key id.
    pub signer_key_id: String,
    /// Standard padded base64 Ed25519 signature over the unsigned response.
    pub signature_base64: String,
}

/// Persisted replay state. Pending entries represent crash/timeout uncertainty.
#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct OwnerDispatchReplaySnapshot {
    /// Requests committed before entering the process-local registry.
    pub pending_request_sha256: BTreeMap<String, String>,
    /// Exact signed responses committed after registry verification.
    pub terminal: BTreeMap<String, OwnerDispatchResponse>,
}

/// Source-authority fields derived only from a completed typed product Start.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ProductActivationProof {
    /// Completed platform action.
    pub action_id: String,
    /// Live source provider epoch.
    pub provider_epoch_id: String,
    /// Exact admitted client and lease.
    pub client_id: String,
    /// Exact live media Runtime Host lease.
    pub lease_id: String,
    /// Exact outgoing runtime specification.
    pub runtime_spec_id: String,
    /// Resulting live lifecycle revision.
    pub resulting_runtime_revision: u64,
    /// Seven exact owner receipt identities in completion order.
    pub owner_receipt_ids: Vec<String>,
    /// Digest of the exact Rust-authored completion JSON retained by the source.
    pub completion_sha256: String,
}

/// Distinct signed control message released only after full product Start.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ProductActivationRequest {
    /// Schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Stable exactly-once activation identity.
    pub activation_id: String,
    /// Source trusted wall time.
    pub issued_at_ms: u64,
    /// Hard activation expiry.
    pub expires_at_ms: u64,
    /// Exact executing peer.
    pub target_peer_id: String,
    /// Current source route projected into target authority.
    pub authority: OwnerDispatchAuthorityProjection,
    /// Rust-derived full Start proof.
    pub proof: ProductActivationProof,
    /// Exact source-produced completion bytes; never accepted as authority by itself.
    pub completion_json: String,
    /// Enrolled source signing key.
    pub signer_key_id: String,
    /// Signature over exact metadata and completion bytes.
    pub signature_base64: String,
}

/// Target callback result. `activated` means the staged receiver graph accepted
/// this exact proof; it is not evidence that any media frame rendered.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ProductActivationReadback {
    /// Schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact activation identity.
    pub activation_id: String,
    /// Exact route bound to the staged receiver.
    pub route_grant_id: String,
    /// Target-local graph revision.
    pub resulting_state_revision: u64,
    /// True only after target graph activation.
    pub activated: bool,
}

/// Signed durable acknowledgement or retained uncertainty.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ProductActivationResponse {
    /// Schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact activation identity.
    pub activation_id: String,
    /// Digest of exact request frame.
    pub request_sha256: String,
    /// Completed or uncertain outcome.
    pub status: OwnerDispatchStatus,
    /// Target-owned readback on completion.
    pub readback: Option<ProductActivationReadback>,
    /// Bounded failure classification.
    pub failure: Option<String>,
    /// Target acknowledgement key.
    pub signer_key_id: String,
    /// Target signature.
    pub signature_base64: String,
}

/// Separate replay namespace for full-product activation barriers.
#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ProductActivationReplaySnapshot {
    /// Requests durably retained before graph activation.
    pub pending_request_sha256: BTreeMap<String, String>,
    /// Exact signed terminal acknowledgements.
    pub terminal: BTreeMap<String, ProductActivationResponse>,
}

/// App-owned signer; private key material never enters this crate.
pub trait OwnerDispatchSigner: Send + Sync {
    /// Stable public key id bound by current reciprocal authority.
    fn key_id(&self) -> &str;
    /// Signs exact canonical bytes.
    ///
    /// # Errors
    /// Returns an error when the app-owned signer cannot sign the bytes.
    fn sign(&self, message: &[u8]) -> Result<[u8; 64], String>;
}

/// Current authority verifier implemented over the live durable Runtime Host.
pub trait OwnerDispatchAuthorityVerifier: Send + Sync {
    /// Authenticates key, peer, projection, ticket, and current time before effect.
    ///
    /// # Errors
    /// Returns an error when any authentication or current-authority join fails.
    fn verify_current(
        &self,
        request: &OwnerDispatchRequest,
        signing_bytes: &[u8],
        signature: &[u8; 64],
    ) -> Result<(), String>;
}

/// Target-side verifier for a signed full-product activation barrier.
pub trait ProductActivationAuthorityVerifier: Send + Sync {
    /// Authenticates the source signer and current target-local C1 projection.
    ///
    /// # Errors
    /// Returns an error unless every signature and current-authority join passes.
    fn verify_activation(
        &self,
        request: &ProductActivationRequest,
        signing_bytes: &[u8],
        signature: &[u8; 64],
    ) -> Result<(), String>;
}

/// Target-owned graph activation callback. It consumes only an already verified
/// proof and must preserve staged receiver handles on uncertain failure.
pub trait ProductActivationRegistry: Send {
    /// Activates the staged exact route without re-entering Broker authority.
    ///
    /// # Errors
    /// Returns an error when the exact staged graph cannot be activated and read back.
    fn activate(
        &mut self,
        activation_id: &str,
        authority: &OwnerDispatchAuthorityProjection,
        proof: &ProductActivationProof,
    ) -> Result<ProductActivationReadback, String>;
}

/// Durable activation replay store.
pub trait ProductActivationReplayStore: Send {
    /// Atomically commits the complete replacement snapshot.
    ///
    /// # Errors
    /// Returns an error unless the snapshot is durably committed.
    fn commit(&mut self, snapshot: &ProductActivationReplaySnapshot) -> Result<(), String>;
}

/// Internal process registry bridge. Implementations must call Java
/// `execute`, then `verifyAndReadEvidence`, and return the consumed evidence.
pub trait AuthenticatedOwnerRegistry: Send {
    /// Executes and verifies exactly once without holding authority locks.
    ///
    /// # Errors
    /// Returns an error when execution or the authenticated readback fails.
    fn execute_and_verify(
        &mut self,
        authority: Option<&OwnerDispatchAuthorityProjection>,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String>;
}

/// Current time source supplied by the embedding app.
pub trait OwnerDispatchClock: Send + Sync {
    /// Current trusted wall time in milliseconds.
    ///
    /// # Errors
    /// Returns an error when trusted time is unavailable.
    fn now_ms(&self) -> Result<u64, String>;
}

/// Broker-owned source of a freshly live-validated remote projection.
pub trait CurrentOwnerProjectionSource: Send + Sync {
    /// Produces a current projection for the exact ticket and target peer.
    ///
    /// # Errors
    /// Returns an error when the route is not current or does not bind the ticket.
    fn current_projection(
        &self,
        ticket: &AndroidMediaExecutionTicket,
        target_peer_id: &str,
        mode: AndroidMediaExecutionMode,
        now_ms: u64,
    ) -> Result<OwnerDispatchAuthorityProjection, String>;
}

/// Object-safe remote dispatch client.
pub trait OwnerDispatchClient: Send {
    /// Dispatches one exact pre-authorized owner ticket.
    ///
    /// # Errors
    /// Returns an error when dispatch or authenticated verification fails.
    fn execute(
        &mut self,
        target_peer_id: String,
        authority: OwnerDispatchAuthorityProjection,
        ticket: AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
        now_ms: u64,
    ) -> Result<AuthenticatedOwnerEffect, String>;
}

/// Exact process placement for one selected owner tuple.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(tag = "placement", rename_all = "snake_case", deny_unknown_fields)]
pub enum AndroidMediaOwnerPlacementTarget {
    /// Execute in this process's installed registry.
    Local,
    /// Execute in the exact peer process over authenticated dispatch.
    Remote {
        /// Exact enrolled peer owning the registry.
        peer_id: String,
    },
}

/// Exact selected owner tuple and its process placement.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidMediaOwnerPlacement {
    /// Owner family.
    pub owner_kind: rusty_quest_media_stream::MediaStreamOwnerKind,
    /// Owner identity.
    pub owner_id: String,
    /// Concrete provider.
    pub provider_kind: String,
    /// Exact resource.
    pub resource_id: String,
    /// Local or exact remote peer.
    pub target: AndroidMediaOwnerPlacementTarget,
}

/// Product-selected device-to-enrolled-peer placement. Device identities come
/// from the exact packaged runtime binding; peer identities come from C1.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidMediaDevicePeerPlacement {
    /// Exact runtime-plan device.
    pub device_id: String,
    /// Exact enrolled peer hosting that device.
    pub peer_id: String,
}

/// Derives all seven owner placements from one validated packaged binding.
/// Source, processor, route, socket, and codec follow the selected lane source;
/// sink follows its selected sink device; cleanup follows the authority peer.
///
/// # Errors
/// Returns an error when the binding is invalid, a selected resource cannot be
/// resolved, or any required device has no unique enrolled-peer placement.
pub fn derive_embedded_duplex_owner_placements(
    binding: &rusty_quest_media_stream::MediaStreamRuntimeProductBinding,
    local_peer_id: &str,
    authority_peer_id: &str,
    device_peers: &[AndroidMediaDevicePeerPlacement],
) -> Result<Vec<AndroidMediaOwnerPlacement>, String> {
    binding.validate().map_err(|error| error.to_string())?;
    if local_peer_id.is_empty() || authority_peer_id.is_empty() {
        return Err("owner placement peer identity is empty".to_owned());
    }
    let mut peers = BTreeMap::new();
    for placement in device_peers {
        if placement.device_id.is_empty()
            || placement.peer_id.is_empty()
            || peers
                .insert(placement.device_id.as_str(), placement.peer_id.as_str())
                .is_some()
        {
            return Err("invalid or duplicate device peer placement".to_owned());
        }
    }
    let target = |peer_id: &str| {
        if peer_id == local_peer_id {
            AndroidMediaOwnerPlacementTarget::Local
        } else {
            AndroidMediaOwnerPlacementTarget::Remote {
                peer_id: peer_id.to_owned(),
            }
        }
    };
    binding
        .spec
        .owner_selections
        .iter()
        .map(|selection| {
            let peer_id = match selection.owner_kind {
                rusty_quest_media_stream::MediaStreamOwnerKind::Cleanup => authority_peer_id,
                rusty_quest_media_stream::MediaStreamOwnerKind::Sink => {
                    let device_id = binding
                        .spec
                        .sinks
                        .iter()
                        .find(|sink| sink.sink_id == selection.resource_id)
                        .map(|sink| sink.device_id.as_str())
                        .ok_or_else(|| "selected sink resource is absent".to_owned())?;
                    peers
                        .get(device_id)
                        .copied()
                        .ok_or_else(|| "selected sink device placement is absent".to_owned())?
                }
                rusty_quest_media_stream::MediaStreamOwnerKind::Source => {
                    let device_id = binding
                        .spec
                        .plan
                        .sources
                        .iter()
                        .find(|source| source.source_id == selection.resource_id)
                        .map(|source| source.device_id.as_str())
                        .ok_or_else(|| "selected source resource is absent".to_owned())?;
                    peers
                        .get(device_id)
                        .copied()
                        .ok_or_else(|| "selected source device placement is absent".to_owned())?
                }
                _ => {
                    let lane_id = selection
                        .lane_id
                        .as_deref()
                        .ok_or_else(|| "lane owner omitted lane identity".to_owned())?;
                    let device_id = binding
                        .spec
                        .plan
                        .lanes
                        .iter()
                        .find(|lane| lane.lane_id == lane_id)
                        .map(|lane| lane.source_device_id.as_str())
                        .ok_or_else(|| "selected owner lane is absent".to_owned())?;
                    peers
                        .get(device_id)
                        .copied()
                        .ok_or_else(|| "selected lane device placement is absent".to_owned())?
                }
            };
            Ok(AndroidMediaOwnerPlacement {
                owner_kind: selection.owner_kind,
                owner_id: selection.owner_id.clone(),
                provider_kind: selection.provider_kind.clone(),
                resource_id: selection.resource_id.clone(),
                target: target(peer_id),
            })
        })
        .collect()
}

/// One executor for a complete seven-family product. It snapshots C1 authority
/// before remote I/O and holds no authority/provider lock across callbacks.
pub struct CompositeAndroidMediaOwnerExecutor {
    generation: u64,
    local_peer_id: String,
    placements: BTreeMap<
        (
            rusty_quest_media_stream::MediaStreamOwnerKind,
            String,
            String,
            String,
        ),
        AndroidMediaOwnerPlacementTarget,
    >,
    local: Box<dyn AuthenticatedOwnerRegistry>,
    remote: Box<dyn OwnerDispatchClient>,
    projections: Box<dyn CurrentOwnerProjectionSource>,
    clock: Box<dyn OwnerDispatchClock>,
    verified: Mutex<BTreeMap<String, (AndroidMediaExecutionTicket, AuthenticatedOwnerEffect)>>,
}

impl CompositeAndroidMediaOwnerExecutor {
    /// Creates a complete executor and rejects missing/duplicate owner families.
    ///
    /// # Errors
    /// Returns an error for an invalid generation or incomplete placement set.
    pub fn new(
        generation: u64,
        local_peer_id: String,
        placements: Vec<AndroidMediaOwnerPlacement>,
        local: Box<dyn AuthenticatedOwnerRegistry>,
        remote: Box<dyn OwnerDispatchClient>,
        projections: Box<dyn CurrentOwnerProjectionSource>,
        clock: Box<dyn OwnerDispatchClock>,
    ) -> Result<Self, String> {
        let mut exact = BTreeMap::new();
        let mut families = BTreeSet::new();
        for placement in placements {
            if placement.owner_id.is_empty()
                || placement.provider_kind.is_empty()
                || placement.resource_id.is_empty()
                || matches!(&placement.target, AndroidMediaOwnerPlacementTarget::Remote { peer_id } if peer_id.is_empty())
            {
                return Err("invalid owner placement".to_owned());
            }
            families.insert(placement.owner_kind);
            let key = (
                placement.owner_kind,
                placement.owner_id,
                placement.provider_kind,
                placement.resource_id,
            );
            if exact.insert(key, placement.target).is_some() {
                return Err("duplicate owner placement".to_owned());
            }
        }
        let expected = [
            rusty_quest_media_stream::MediaStreamOwnerKind::Source,
            rusty_quest_media_stream::MediaStreamOwnerKind::Processor,
            rusty_quest_media_stream::MediaStreamOwnerKind::Route,
            rusty_quest_media_stream::MediaStreamOwnerKind::Socket,
            rusty_quest_media_stream::MediaStreamOwnerKind::Codec,
            rusty_quest_media_stream::MediaStreamOwnerKind::Sink,
            rusty_quest_media_stream::MediaStreamOwnerKind::Cleanup,
        ]
        .into_iter()
        .collect::<BTreeSet<_>>();
        if generation == 0 || local_peer_id.is_empty() || families != expected {
            return Err("incomplete seven-family placement".to_owned());
        }
        Ok(Self {
            generation,
            local_peer_id,
            placements: exact,
            local,
            remote,
            projections,
            clock,
            verified: Mutex::new(BTreeMap::new()),
        })
    }
}

impl crate::AndroidMediaOwnerExecutor for CompositeAndroidMediaOwnerExecutor {
    fn executor_generation(&self) -> u64 {
        self.generation
    }

    fn execute(
        &mut self,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AndroidMediaOwnerReadback, String> {
        if ticket.executor_generation != self.generation {
            return Err("executor generation mismatch".to_owned());
        }
        let key = (
            ticket.owner_kind,
            ticket.owner_id.clone(),
            ticket.provider_kind.clone(),
            ticket.resource_id.clone(),
        );
        let target = self
            .placements
            .get(&key)
            .ok_or_else(|| "owner placement absent".to_owned())?
            .clone();
        let now_ms = self.clock.now_ms()?;
        let target_peer_id = match &target {
            AndroidMediaOwnerPlacementTarget::Local => self.local_peer_id.clone(),
            AndroidMediaOwnerPlacementTarget::Remote { peer_id } => peer_id.clone(),
        };
        let projection =
            self.projections
                .current_projection(ticket, &target_peer_id, mode, now_ms)?;
        let effect = match target {
            AndroidMediaOwnerPlacementTarget::Local => {
                self.local
                    .execute_and_verify(Some(&projection), ticket, mode)?
            }
            AndroidMediaOwnerPlacementTarget::Remote { peer_id } => {
                self.remote
                    .execute(peer_id, projection, ticket.clone(), mode, now_ms)?
            }
        };
        validate_effect(ticket, mode, &effect)?;
        let readback = effect.readback.clone();
        self.verified
            .lock()
            .map_err(|_| "verified effect lock poisoned".to_owned())?
            .insert(ticket.capability.clone(), (ticket.clone(), effect));
        Ok(readback)
    }

    fn verify(
        &self,
        ticket: &AndroidMediaExecutionTicket,
        readback: &AndroidMediaOwnerReadback,
    ) -> bool {
        self.verified
            .lock()
            .ok()
            .and_then(|mut verified| verified.remove(&ticket.capability))
            .is_some_and(|(expected, effect)| expected == *ticket && effect.readback == *readback)
    }
}

/// Durable replay store. A successful return means bytes are committed strongly
/// enough to survive process loss before the next platform/network step.
pub trait OwnerDispatchReplayStore: Send {
    /// Atomically replaces the retained replay snapshot.
    ///
    /// # Errors
    /// Returns an error unless the replacement is durably committed.
    fn commit(&mut self, snapshot: &OwnerDispatchReplaySnapshot) -> Result<(), String>;
}

/// Transport used by the authority peer. Framing and byte bounds are mandatory.
pub trait OwnerDispatchTransport: Send {
    /// Exchanges one length-delimited frame and returns one bounded frame.
    ///
    /// # Errors
    /// Returns an error on transport failure or a violated response bound.
    fn exchange(
        &mut self,
        request_frame: &[u8],
        max_response_bytes: usize,
    ) -> Result<Vec<u8>, String>;
}

/// Builds a signed product-activation frame from a proof derived inside the
/// source provider. The completion payload is digest-bound and remains opaque
/// to transports.
///
/// # Errors
/// Returns an error for signer, encoding, or frame-bound violations.
pub fn encode_product_activation_request(
    mut request: ProductActivationRequest,
    signer: &dyn OwnerDispatchSigner,
) -> Result<Vec<u8>, String> {
    if request.signer_key_id != signer.key_id() || !request.signature_base64.is_empty() {
        return Err("activation signer mismatch".to_owned());
    }
    let signing = product_activation_request_signing_bytes(&request)?;
    request.signature_base64 = encode_signature_base64(&signer.sign(&signing)?);
    let (metadata, payload) = product_activation_request_wire_parts(&request, true)?;
    encode_wire_frame(&metadata, &payload)
}

/// Decodes one bounded signed product-activation request.
///
/// # Errors
/// Returns an error for malformed framing, payload, or schema bytes.
pub fn decode_product_activation_request(frame: &[u8]) -> Result<ProductActivationRequest, String> {
    let (metadata, payload) = decode_wire_frame(frame)?;
    let mut value: serde_json::Value =
        serde_json::from_slice(metadata).map_err(|_| "activation metadata".to_owned())?;
    validate_payload_digest(&mut value, payload)?;
    value
        .as_object_mut()
        .ok_or_else(|| "activation metadata shape".to_owned())?
        .insert(
            "completion_json".to_owned(),
            serde_json::Value::String(
                String::from_utf8(payload.to_vec())
                    .map_err(|_| "activation completion UTF-8".to_owned())?,
            ),
        );
    serde_json::from_value(value).map_err(|_| "invalid activation request".to_owned())
}

/// Canonical signing bytes for a product-activation request.
///
/// # Errors
/// Returns an error when canonical metadata or payload encoding fails.
pub fn product_activation_request_signing_bytes(
    request: &ProductActivationRequest,
) -> Result<Vec<u8>, String> {
    let (metadata, payload) = product_activation_request_wire_parts(request, false)?;
    wire_signing_bytes(PRODUCT_ACTIVATION_REQUEST_DOMAIN, &metadata, &payload)
}

/// Canonical signing bytes for a product-activation acknowledgement.
///
/// # Errors
/// Returns an error when canonical acknowledgement encoding fails.
pub fn product_activation_response_signing_bytes(
    response: &ProductActivationResponse,
) -> Result<Vec<u8>, String> {
    let mut value =
        serde_json::to_value(response).map_err(|_| "activation ack encoding".to_owned())?;
    value
        .as_object_mut()
        .ok_or_else(|| "activation ack shape".to_owned())?
        .remove("signature_base64");
    let metadata = serde_json::to_vec(&value).map_err(|_| "activation ack encoding".to_owned())?;
    wire_signing_bytes(PRODUCT_ACTIVATION_RESPONSE_DOMAIN, &metadata, &[])
}

/// Target-side full-product activation server with fail-closed durable replay.
pub struct ProductActivationServer<V, R, S> {
    target_peer_id: String,
    verifier: V,
    registry: R,
    signer: S,
    clock: Box<dyn OwnerDispatchClock>,
    replay: ProductActivationReplaySnapshot,
    store: Box<dyn ProductActivationReplayStore>,
}

impl<V, R, S> ProductActivationServer<V, R, S>
where
    V: ProductActivationAuthorityVerifier,
    R: ProductActivationRegistry,
    S: OwnerDispatchSigner,
{
    /// Restores one bounded activation replay namespace.
    ///
    /// # Errors
    /// Returns an error for invalid identity, replay counts, or byte bounds.
    pub fn restore(
        target_peer_id: String,
        verifier: V,
        registry: R,
        signer: S,
        clock: Box<dyn OwnerDispatchClock>,
        replay: ProductActivationReplaySnapshot,
        store: Box<dyn ProductActivationReplayStore>,
    ) -> Result<Self, String> {
        if target_peer_id.is_empty()
            || replay.pending_request_sha256.len() > MAX_OWNER_DISPATCH_PENDING
            || replay.terminal.len() > MAX_OWNER_DISPATCH_REPLAYS
            || serde_json::to_vec(&replay)
                .map_or(true, |bytes| bytes.len() > MAX_OWNER_DISPATCH_REPLAY_BYTES)
            || replay
                .pending_request_sha256
                .keys()
                .any(|id| replay.terminal.contains_key(id))
        {
            return Err("invalid activation replay state".to_owned());
        }
        Ok(Self {
            target_peer_id,
            verifier,
            registry,
            signer,
            clock,
            replay,
            store,
        })
    }

    /// Handles one activation frame. Pending is committed before the callback;
    /// an uncertain callback or failed terminal commit leaves the staged graph retained.
    ///
    /// # Errors
    /// Returns an error for authority, freshness, persistence, or callback failure.
    pub fn handle_frame(&mut self, frame: &[u8]) -> Result<Vec<u8>, String> {
        let request = decode_product_activation_request(frame)?;
        validate_product_activation_shape(&request, &self.target_peer_id)?;
        let request_sha256 = sha256_prefixed(frame);
        if let Some(response) = self.replay.terminal.get(&request.activation_id) {
            if response.request_sha256 != request_sha256 {
                return Err("activation replay identity collision".to_owned());
            }
            return encode_product_activation_response(response);
        }
        if let Some(pending) = self
            .replay
            .pending_request_sha256
            .get(&request.activation_id)
        {
            if pending != &request_sha256 {
                return Err("activation replay identity collision".to_owned());
            }
            return self.terminalize_pending(&request.activation_id, &request_sha256);
        }
        let now_ms = self.clock.now_ms()?;
        if now_ms == 0
            || request.issued_at_ms > now_ms.saturating_add(MAX_OWNER_DISPATCH_FUTURE_SKEW_MS)
            || request.expires_at_ms <= now_ms
            || now_ms.saturating_sub(request.issued_at_ms) > MAX_OWNER_DISPATCH_REQUEST_AGE_MS
        {
            return Err("activation request is not current".to_owned());
        }
        if self.replay.pending_request_sha256.len() >= MAX_OWNER_DISPATCH_PENDING
            || self.replay.terminal.len() >= MAX_OWNER_DISPATCH_REPLAYS
        {
            return Err("activation replay capacity".to_owned());
        }
        let signing = product_activation_request_signing_bytes(&request)?;
        let signature = decode_signature_base64(&request.signature_base64)?;
        self.verifier
            .verify_activation(&request, &signing, &signature)?;
        self.replay
            .pending_request_sha256
            .insert(request.activation_id.clone(), request_sha256.clone());
        if let Err(error) = self.store.commit(&self.replay) {
            self.replay
                .pending_request_sha256
                .remove(&request.activation_id);
            return Err(format!("activation pending persistence: {error}"));
        }
        let activation =
            self.registry
                .activate(&request.activation_id, &request.authority, &request.proof);
        let (status, readback, failure) = match activation {
            Ok(value)
                if value.schema_id == PRODUCT_ACTIVATION_READBACK_SCHEMA
                    && value.activation_id == request.activation_id
                    && value.route_grant_id == request.authority.route_grant_id
                    && value.resulting_state_revision > 0
                    && value.activated =>
            {
                (OwnerDispatchStatus::Completed, Some(value), None)
            }
            _ => (
                OwnerDispatchStatus::Uncertain,
                None,
                Some("activation_effect_uncertain".to_owned()),
            ),
        };
        let encoded = self.signed_response(
            &request.activation_id,
            &request_sha256,
            status,
            readback,
            failure,
        )?;
        let response = decode_product_activation_response(&encoded)?;
        let mut committed = self.replay.clone();
        committed
            .pending_request_sha256
            .remove(&request.activation_id);
        committed.terminal.insert(request.activation_id, response);
        if serde_json::to_vec(&committed)
            .map_or(true, |bytes| bytes.len() > MAX_OWNER_DISPATCH_REPLAY_BYTES)
        {
            return Err("activation replay byte capacity".to_owned());
        }
        self.store.commit(&committed)?;
        self.replay = committed;
        Ok(encoded)
    }

    fn signed_response(
        &self,
        activation_id: &str,
        request_sha256: &str,
        status: OwnerDispatchStatus,
        readback: Option<ProductActivationReadback>,
        failure: Option<String>,
    ) -> Result<Vec<u8>, String> {
        let mut response = ProductActivationResponse {
            schema_id: PRODUCT_ACTIVATION_RESPONSE_SCHEMA.to_owned(),
            activation_id: activation_id.to_owned(),
            request_sha256: request_sha256.to_owned(),
            status,
            readback,
            failure,
            signer_key_id: self.signer.key_id().to_owned(),
            signature_base64: String::new(),
        };
        response.signature_base64 = encode_signature_base64(
            &self
                .signer
                .sign(&product_activation_response_signing_bytes(&response)?)?,
        );
        encode_product_activation_response(&response)
    }

    fn terminalize_pending(
        &mut self,
        activation_id: &str,
        request_sha256: &str,
    ) -> Result<Vec<u8>, String> {
        let encoded = self.signed_response(
            activation_id,
            request_sha256,
            OwnerDispatchStatus::Uncertain,
            None,
            Some("activation_state_uncertain".to_owned()),
        )?;
        let response = decode_product_activation_response(&encoded)?;
        let mut committed = self.replay.clone();
        committed.pending_request_sha256.remove(activation_id);
        committed
            .terminal
            .insert(activation_id.to_owned(), response);
        if serde_json::to_vec(&committed)
            .map_or(true, |bytes| bytes.len() > MAX_OWNER_DISPATCH_REPLAY_BYTES)
        {
            return Err("activation replay byte capacity".to_owned());
        }
        self.store.commit(&committed)?;
        self.replay = committed;
        Ok(encoded)
    }
}

/// Fail-closed dispatch server for a single target peer process.
pub struct OwnerDispatchServer<V, R, S> {
    target_peer_id: String,
    verifier: V,
    registry: R,
    signer: S,
    clock: Box<dyn OwnerDispatchClock>,
    replay: OwnerDispatchReplaySnapshot,
    replay_store: Box<dyn OwnerDispatchReplayStore>,
}

impl<V, R, S> OwnerDispatchServer<V, R, S>
where
    V: OwnerDispatchAuthorityVerifier,
    R: AuthenticatedOwnerRegistry,
    S: OwnerDispatchSigner,
{
    /// Restores bounded replay state. Oversized or overlapping state is rejected.
    ///
    /// # Errors
    /// Returns an error when identity or replay bounds are invalid.
    pub fn restore(
        target_peer_id: String,
        verifier: V,
        registry: R,
        signer: S,
        clock: Box<dyn OwnerDispatchClock>,
        replay: OwnerDispatchReplaySnapshot,
        replay_store: Box<dyn OwnerDispatchReplayStore>,
    ) -> Result<Self, String> {
        if target_peer_id.is_empty()
            || replay.pending_request_sha256.len() > MAX_OWNER_DISPATCH_PENDING
            || replay.terminal.len() > MAX_OWNER_DISPATCH_REPLAYS
            || serde_json::to_vec(&replay)
                .map_or(true, |bytes| bytes.len() > MAX_OWNER_DISPATCH_REPLAY_BYTES)
            || replay
                .pending_request_sha256
                .keys()
                .any(|id| replay.terminal.contains_key(id))
        {
            return Err("invalid dispatch replay state".to_owned());
        }
        Ok(Self {
            target_peer_id,
            verifier,
            registry,
            signer,
            clock,
            replay,
            replay_store,
        })
    }

    /// Returns durable replay state for the app-owned persistence boundary.
    pub fn replay_snapshot(&self) -> OwnerDispatchReplaySnapshot {
        self.replay.clone()
    }

    /// Handles one exact JSON frame. No authority lock is held across registry execution.
    ///
    /// # Errors
    /// Returns an error for invalid authority, framing, persistence, or execution evidence.
    pub fn handle_frame(&mut self, frame: &[u8]) -> Result<Vec<u8>, String> {
        if frame.is_empty() || frame.len() > MAX_OWNER_DISPATCH_FRAME_BYTES {
            return Err("dispatch frame bounds".to_owned());
        }
        let request = decode_request_frame(frame)?;
        validate_request_shape(&request, &self.target_peer_id)?;
        let request_sha256 = sha256_prefixed(frame);
        if let Some(cached) = self.replay.terminal.get(&request.dispatch_id) {
            if cached.request_sha256 != request_sha256 {
                return Err("dispatch replay identity collision".to_owned());
            }
            return encode_response_frame(cached);
        }
        if let Some(pending) = self.replay.pending_request_sha256.get(&request.dispatch_id) {
            if pending != &request_sha256 {
                return Err("dispatch replay identity collision".to_owned());
            }
            return self.signed_response(
                &request.dispatch_id,
                &request_sha256,
                OwnerDispatchStatus::Uncertain,
                None,
                Some("pending_compensation_required".to_owned()),
            );
        }
        let target_now_ms = self.clock.now_ms()?;
        if target_now_ms == 0
            || request.issued_at_ms
                > target_now_ms.saturating_add(MAX_OWNER_DISPATCH_FUTURE_SKEW_MS)
            || target_now_ms.saturating_sub(request.issued_at_ms)
                > MAX_OWNER_DISPATCH_REQUEST_AGE_MS
        {
            return Err("dispatch request is not currently fresh".to_owned());
        }
        if self.replay.pending_request_sha256.len() >= MAX_OWNER_DISPATCH_PENDING
            || self.replay.terminal.len() >= MAX_OWNER_DISPATCH_REPLAYS
        {
            return Err("dispatch replay capacity".to_owned());
        }
        let signing_bytes = request_signing_bytes(&request)?;
        let signature = decode_signature_base64(&request.signature_base64)?;
        self.verifier
            .verify_current(&request, &signing_bytes, &signature)?;

        // Persistable uncertainty is established before crossing into platform code.
        self.replay
            .pending_request_sha256
            .insert(request.dispatch_id.clone(), request_sha256.clone());
        if let Err(error) = self.replay_store.commit(&self.replay) {
            self.replay
                .pending_request_sha256
                .remove(&request.dispatch_id);
            return Err(format!("dispatch pending persistence: {error}"));
        }
        let execution = self.registry.execute_and_verify(
            Some(&request.authority),
            &request.ticket,
            request.mode,
        );
        let (status, effect, failure) = match execution {
            Ok(effect) if validate_effect(&request.ticket, request.mode, &effect).is_ok() => {
                (OwnerDispatchStatus::Completed, Some(effect), None)
            }
            Ok(_) => (
                OwnerDispatchStatus::Uncertain,
                None,
                Some("platform_evidence_invalid".to_owned()),
            ),
            Err(_) => (
                OwnerDispatchStatus::Uncertain,
                None,
                Some("platform_effect_uncertain".to_owned()),
            ),
        };
        let encoded = self.signed_response(
            &request.dispatch_id,
            &request_sha256,
            status,
            effect,
            failure,
        )?;
        let response = decode_response_frame(&encoded)?;
        let mut committed = self.replay.clone();
        committed
            .pending_request_sha256
            .remove(&request.dispatch_id);
        committed
            .terminal
            .insert(request.dispatch_id.clone(), response);
        if serde_json::to_vec(&committed)
            .map_or(true, |bytes| bytes.len() > MAX_OWNER_DISPATCH_REPLAY_BYTES)
        {
            return Err("dispatch replay byte capacity".to_owned());
        }
        self.replay_store.commit(&committed)?;
        self.replay = committed;
        Ok(encoded)
    }

    fn signed_response(
        &self,
        dispatch_id: &str,
        request_sha256: &str,
        status: OwnerDispatchStatus,
        effect: Option<AuthenticatedOwnerEffect>,
        failure: Option<String>,
    ) -> Result<Vec<u8>, String> {
        let mut response = OwnerDispatchResponse {
            schema_id: OWNER_DISPATCH_RESPONSE_SCHEMA.to_owned(),
            dispatch_id: dispatch_id.to_owned(),
            request_sha256: request_sha256.to_owned(),
            status,
            effect,
            failure,
            signer_key_id: self.signer.key_id().to_owned(),
            signature_base64: String::new(),
        };
        let bytes = response_signing_bytes(&response)?;
        response.signature_base64 = encode_signature_base64(&self.signer.sign(&bytes)?);
        encode_response_frame(&response)
    }
}

/// Remote exact-ticket executor. The authority projection is captured before
/// transport I/O, so the transport cannot re-enter a checked-out provider slot.
pub struct RemoteOwnerDispatchExecutor<T, S> {
    transport: T,
    signer: S,
    executor_key: VerifyingKey,
    executor_key_id: String,
    next_sequence: u64,
}

impl<T: OwnerDispatchTransport, S: OwnerDispatchSigner> RemoteOwnerDispatchExecutor<T, S> {
    /// Creates an executor for one current remote signing identity.
    ///
    /// # Errors
    /// Returns an error for an empty key id or invalid Ed25519 public key.
    pub fn new(
        transport: T,
        signer: S,
        executor_key: [u8; 32],
        executor_key_id: String,
    ) -> Result<Self, String> {
        if executor_key_id.is_empty() {
            return Err("empty executor key id".to_owned());
        }
        let executor_key = VerifyingKey::from_bytes(&executor_key)
            .map_err(|_| "invalid executor public key".to_owned())?;
        Ok(Self {
            transport,
            signer,
            executor_key,
            executor_key_id,
            next_sequence: 1,
        })
    }

    /// Dispatches one pre-authorized ticket and authenticates the exact result.
    ///
    /// # Errors
    /// Returns an error when preparation, transport, or response verification fails.
    pub fn execute(
        &mut self,
        target_peer_id: String,
        authority: OwnerDispatchAuthorityProjection,
        ticket: AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
        now_ms: u64,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        let request = self.prepare_request(target_peer_id, authority, ticket, mode, now_ms)?;
        self.execute_prepared(&request)
    }

    /// Prepares and signs a stable request which the caller can persist before I/O.
    ///
    /// # Errors
    /// Returns an error when sequence allocation, encoding, or signing fails.
    pub fn prepare_request(
        &mut self,
        target_peer_id: String,
        authority: OwnerDispatchAuthorityProjection,
        ticket: AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
        now_ms: u64,
    ) -> Result<OwnerDispatchRequest, String> {
        let sequence = self.next_sequence;
        self.next_sequence = self
            .next_sequence
            .checked_add(1)
            .ok_or_else(|| "dispatch sequence exhausted".to_owned())?;
        let mut request = OwnerDispatchRequest {
            schema_id: OWNER_DISPATCH_REQUEST_SCHEMA.to_owned(),
            dispatch_id: format!("dispatch.{}.{}", ticket.action_id, sequence),
            sequence,
            issued_at_ms: now_ms,
            target_peer_id,
            authority,
            ticket,
            mode,
            signer_key_id: self.signer.key_id().to_owned(),
            signature_base64: String::new(),
        };
        let signing_bytes = request_signing_bytes(&request)?;
        request.signature_base64 = encode_signature_base64(&self.signer.sign(&signing_bytes)?);
        Ok(request)
    }

    /// Sends an already-persisted request. Retrying the same value preserves
    /// its dispatch identity and receives the server's cached exact result.
    ///
    /// # Errors
    /// Returns an error on transport failure, invalid response, or uncertainty.
    pub fn execute_prepared(
        &mut self,
        request: &OwnerDispatchRequest,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        let frame = encode_request_frame(request)?;
        let response_frame = self
            .transport
            .exchange(&frame, MAX_OWNER_DISPATCH_FRAME_BYTES)?;
        let response = decode_response_frame(&response_frame)?;
        if response.schema_id != OWNER_DISPATCH_RESPONSE_SCHEMA
            || response.dispatch_id != request.dispatch_id
            || response.request_sha256 != sha256_prefixed(&frame)
            || response.signer_key_id != self.executor_key_id
        {
            return Err("dispatch response binding mismatch".to_owned());
        }
        let signature =
            Signature::from_bytes(&decode_signature_base64(&response.signature_base64)?);
        self.executor_key
            .verify_strict(&response_signing_bytes(&response)?, &signature)
            .map_err(|_| "dispatch response signature".to_owned())?;
        match (response.status, response.effect) {
            (OwnerDispatchStatus::Completed, Some(effect)) => {
                validate_effect(&request.ticket, request.mode, &effect)?;
                Ok(effect)
            }
            (OwnerDispatchStatus::Uncertain, _) => {
                Err("remote platform effect uncertain".to_owned())
            }
            _ => Err("remote owner dispatch rejected".to_owned()),
        }
    }
}

impl<T: OwnerDispatchTransport, S: OwnerDispatchSigner> OwnerDispatchClient
    for RemoteOwnerDispatchExecutor<T, S>
{
    fn execute(
        &mut self,
        target_peer_id: String,
        authority: OwnerDispatchAuthorityProjection,
        ticket: AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
        now_ms: u64,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        RemoteOwnerDispatchExecutor::execute(self, target_peer_id, authority, ticket, mode, now_ms)
    }
}

/// Returns exact canonical signing bytes for a request.
///
/// # Errors
/// Returns an error if the request cannot be canonically encoded within bounds.
pub fn request_signing_bytes(request: &OwnerDispatchRequest) -> Result<Vec<u8>, String> {
    let (metadata, payload) = request_wire_parts(request, false)?;
    wire_signing_bytes(OWNER_DISPATCH_REQUEST_DOMAIN, &metadata, &payload)
}

/// Returns exact canonical signing bytes for a response.
///
/// # Errors
/// Returns an error if the response cannot be canonically encoded within bounds.
pub fn response_signing_bytes(response: &OwnerDispatchResponse) -> Result<Vec<u8>, String> {
    let (metadata, payload) = response_wire_parts(response, false)?;
    wire_signing_bytes(OWNER_DISPATCH_RESPONSE_DOMAIN, &metadata, &payload)
}

fn validate_request_shape(
    request: &OwnerDispatchRequest,
    target_peer_id: &str,
) -> Result<(), String> {
    if request.schema_id != OWNER_DISPATCH_REQUEST_SCHEMA
        || request.dispatch_id.is_empty()
        || request.sequence == 0
        || request.issued_at_ms == 0
        || request.target_peer_id != target_peer_id
        || request.signer_key_id.is_empty()
        || request.authority.executor_peer_id != target_peer_id
        || request.authority.expires_at_ms < request.issued_at_ms
        || request.authority.authority_provider_epoch_id != request.ticket.authority_epoch_id
        || request.authority.authority_client_id != request.ticket.client_id
        || request.authority.authority_runtime_lease_id != request.ticket.lease_id
        || request.authority.platform_runtime_spec_id.is_empty()
        || request.authority.authority_runtime_host_id.is_empty()
        || request.authority.route_authority_revision == 0
        || !valid_sha256(&request.authority.signed_topology_sha256)
        || !valid_sha256(&request.authority.route_configuration_sha256)
        || !valid_sha256(&request.authority.route_authority_evidence_sha256)
        || (request.authority.authorization_kind == OwnerDispatchAuthorizationKind::RetainedCleanup
            && !matches!(
                request.ticket.operation,
                rusty_quest_media_stream::MediaStreamPlatformOperation::Stop
            )
            && !matches!(request.mode, AndroidMediaExecutionMode::CompensateUncertain))
    {
        return Err("dispatch request binding mismatch".to_owned());
    }
    Ok(())
}

fn validate_effect(
    ticket: &AndroidMediaExecutionTicket,
    mode: AndroidMediaExecutionMode,
    effect: &AuthenticatedOwnerEffect,
) -> Result<(), String> {
    let verified = &effect.verified;
    let parsed: AndroidMediaOwnerReadback = serde_json::from_str(&effect.readback_json)
        .map_err(|_| "verified readback JSON is invalid".to_owned())?;
    let readback_digest = sha256_prefixed(effect.readback_json.as_bytes());
    if verified.schema_id != VERIFIED_OWNER_EFFECT_SCHEMA
        || parsed != effect.readback
        || crate::validate_readback(ticket, &effect.readback).is_err()
        || verified.receipt_id != effect.readback.receipt_id
        || verified.readback_sha256 != readback_digest
        || verified.executor_generation != ticket.executor_generation
        || verified.executor_generation != effect.readback.executor_generation
        || verified.provider_state_revision != effect.readback.provider_state_revision
        || verified.observed_state != effect.readback.observed_state
        || verified.provider_handle_id != effect.readback.provider_handle_id
        || verified.detail_sha256.len() != 71
        || !verified.detail_sha256.starts_with("sha256:")
        || ((matches!(
            ticket.operation,
            rusty_quest_media_stream::MediaStreamPlatformOperation::Stop
        ) || matches!(mode, AndroidMediaExecutionMode::CompensateUncertain))
            && !verified.terminal)
        || !valid_sha256(&verified.readback_sha256)
        || !valid_sha256(&verified.detail_sha256)
    {
        return Err("verified owner effect mismatch".to_owned());
    }
    Ok(())
}

fn encode_request_frame(request: &OwnerDispatchRequest) -> Result<Vec<u8>, String> {
    let (metadata, payload) = request_wire_parts(request, true)?;
    encode_wire_frame(&metadata, &payload)
}

fn decode_request_frame(frame: &[u8]) -> Result<OwnerDispatchRequest, String> {
    let (metadata, payload) = decode_wire_frame(frame)?;
    let mut value: serde_json::Value = serde_json::from_slice(metadata)
        .map_err(|_| "invalid dispatch request metadata".to_owned())?;
    validate_payload_digest(&mut value, payload)?;
    value
        .as_object_mut()
        .ok_or_else(|| "request metadata shape".to_owned())?
        .insert(
            "ticket".to_owned(),
            serde_json::from_slice(payload).map_err(|_| "invalid ticket payload".to_owned())?,
        );
    serde_json::from_value(value).map_err(|_| "invalid dispatch request".to_owned())
}

fn encode_response_frame(response: &OwnerDispatchResponse) -> Result<Vec<u8>, String> {
    let (metadata, payload) = response_wire_parts(response, true)?;
    encode_wire_frame(&metadata, &payload)
}

fn decode_response_frame(frame: &[u8]) -> Result<OwnerDispatchResponse, String> {
    let (metadata, payload) = decode_wire_frame(frame)?;
    let mut value: serde_json::Value = serde_json::from_slice(metadata)
        .map_err(|_| "invalid dispatch response metadata".to_owned())?;
    validate_payload_digest(&mut value, payload)?;
    if let Some(effect) = value
        .get_mut("effect")
        .and_then(serde_json::Value::as_object_mut)
    {
        let readback: serde_json::Value =
            serde_json::from_slice(payload).map_err(|_| "invalid readback payload".to_owned())?;
        effect.insert("readback".to_owned(), readback);
        effect.insert(
            "readback_json".to_owned(),
            serde_json::Value::String(
                String::from_utf8(payload.to_vec())
                    .map_err(|_| "readback payload UTF-8".to_owned())?,
            ),
        );
    } else if !payload.is_empty() {
        return Err("unexpected response payload".to_owned());
    }
    serde_json::from_value(value).map_err(|_| "invalid dispatch response".to_owned())
}

fn request_wire_parts(
    request: &OwnerDispatchRequest,
    signed: bool,
) -> Result<(Vec<u8>, Vec<u8>), String> {
    let payload = serde_json::to_vec(&request.ticket).map_err(|_| "ticket encoding".to_owned())?;
    let mut value =
        serde_json::to_value(request).map_err(|_| "request metadata encoding".to_owned())?;
    let object = value
        .as_object_mut()
        .ok_or_else(|| "request metadata shape".to_owned())?;
    object.remove("ticket");
    object.insert(
        "payload_sha256".to_owned(),
        serde_json::Value::String(sha256_prefixed(&payload)),
    );
    if !signed {
        object.remove("signature_base64");
    }
    let metadata =
        serde_json::to_vec(&value).map_err(|_| "request metadata encoding".to_owned())?;
    Ok((metadata, payload))
}

fn response_wire_parts(
    response: &OwnerDispatchResponse,
    signed: bool,
) -> Result<(Vec<u8>, Vec<u8>), String> {
    let mut value =
        serde_json::to_value(response).map_err(|_| "response metadata encoding".to_owned())?;
    let object = value
        .as_object_mut()
        .ok_or_else(|| "response metadata shape".to_owned())?;
    let payload = if let Some(effect) = object
        .get_mut("effect")
        .and_then(serde_json::Value::as_object_mut)
    {
        effect.remove("readback");
        match effect.remove("readback_json") {
            Some(serde_json::Value::String(value)) => value.into_bytes(),
            _ => return Err("completed response omitted raw readback".to_owned()),
        }
    } else {
        Vec::new()
    };
    object.insert(
        "payload_sha256".to_owned(),
        serde_json::Value::String(sha256_prefixed(&payload)),
    );
    if !signed {
        object.remove("signature_base64");
    }
    let metadata =
        serde_json::to_vec(&value).map_err(|_| "response metadata encoding".to_owned())?;
    Ok((metadata, payload))
}

fn product_activation_request_wire_parts(
    request: &ProductActivationRequest,
    signed: bool,
) -> Result<(Vec<u8>, Vec<u8>), String> {
    let payload = request.completion_json.as_bytes().to_vec();
    let mut value =
        serde_json::to_value(request).map_err(|_| "activation metadata encoding".to_owned())?;
    let object = value
        .as_object_mut()
        .ok_or_else(|| "activation metadata shape".to_owned())?;
    object.remove("completion_json");
    object.insert(
        "payload_sha256".to_owned(),
        serde_json::Value::String(sha256_prefixed(&payload)),
    );
    if !signed {
        object.remove("signature_base64");
    }
    Ok((
        serde_json::to_vec(&value).map_err(|_| "activation metadata encoding".to_owned())?,
        payload,
    ))
}

fn validate_product_activation_shape(
    request: &ProductActivationRequest,
    target_peer_id: &str,
) -> Result<(), String> {
    let proof = &request.proof;
    if request.schema_id != PRODUCT_ACTIVATION_REQUEST_SCHEMA
        || request.activation_id.is_empty()
        || request.issued_at_ms == 0
        || request.expires_at_ms <= request.issued_at_ms
        || request.target_peer_id != target_peer_id
        || request.authority.executor_peer_id != target_peer_id
        || request.signer_key_id.is_empty()
        || request.authority.authorization_kind != OwnerDispatchAuthorizationKind::CurrentRoute
        || request.authority.expires_at_ms < request.expires_at_ms
        || proof.action_id.is_empty()
        || proof.provider_epoch_id != request.authority.authority_provider_epoch_id
        || proof.client_id != request.authority.authority_client_id
        || proof.lease_id != request.authority.authority_runtime_lease_id
        || proof.runtime_spec_id != request.authority.platform_runtime_spec_id
        || proof.resulting_runtime_revision == 0
        || proof.owner_receipt_ids.len() != 7
        || proof.owner_receipt_ids.iter().any(String::is_empty)
        || proof
            .owner_receipt_ids
            .iter()
            .collect::<BTreeSet<_>>()
            .len()
            != 7
        || !valid_sha256(&proof.completion_sha256)
        || proof.completion_sha256 != sha256_prefixed(request.completion_json.as_bytes())
    {
        return Err("product activation binding mismatch".to_owned());
    }
    Ok(())
}

fn encode_product_activation_response(
    response: &ProductActivationResponse,
) -> Result<Vec<u8>, String> {
    let metadata =
        serde_json::to_vec(response).map_err(|_| "activation response encoding".to_owned())?;
    encode_wire_frame(&metadata, &[])
}

/// Decodes one bounded activation acknowledgement.
///
/// # Errors
/// Returns an error for malformed framing or acknowledgement bytes.
pub fn decode_product_activation_response(
    frame: &[u8],
) -> Result<ProductActivationResponse, String> {
    let (metadata, payload) = decode_wire_frame(frame)?;
    if !payload.is_empty() {
        return Err("activation response payload".to_owned());
    }
    serde_json::from_slice(metadata).map_err(|_| "invalid activation response".to_owned())
}

/// Authenticates an activation acknowledgement against the exact request frame.
///
/// # Errors
/// Returns an error for any identity, digest, readback, or signature mismatch.
pub fn verify_product_activation_response(
    response: &ProductActivationResponse,
    expected_activation_id: &str,
    request_frame: &[u8],
    expected_signer_key_id: &str,
    signer_key: &[u8; 32],
) -> Result<(), String> {
    if response.schema_id != PRODUCT_ACTIVATION_RESPONSE_SCHEMA
        || response.activation_id != expected_activation_id
        || response.request_sha256 != sha256_prefixed(request_frame)
        || response.signer_key_id != expected_signer_key_id
        || (response.status == OwnerDispatchStatus::Completed
            && response.readback.as_ref().map_or(true, |value| {
                value.schema_id != PRODUCT_ACTIVATION_READBACK_SCHEMA
                    || !value.activated
                    || value.activation_id != expected_activation_id
            }))
    {
        return Err("activation acknowledgement binding mismatch".to_owned());
    }
    let key = VerifyingKey::from_bytes(signer_key)
        .map_err(|_| "invalid activation acknowledgement key".to_owned())?;
    let signature = decode_signature_base64(&response.signature_base64)?;
    key.verify_strict(
        &product_activation_response_signing_bytes(response)?,
        &Signature::from_bytes(&signature),
    )
    .map_err(|_| "activation acknowledgement signature".to_owned())
}

fn wire_signing_bytes(domain: &[u8], metadata: &[u8], payload: &[u8]) -> Result<Vec<u8>, String> {
    let metadata_len = u32::try_from(metadata.len()).map_err(|_| "metadata bounds".to_owned())?;
    let payload_len = u32::try_from(payload.len()).map_err(|_| "payload bounds".to_owned())?;
    let mut bytes = Vec::with_capacity(domain.len() + 8 + metadata.len() + payload.len());
    bytes.extend_from_slice(domain);
    bytes.extend_from_slice(&metadata_len.to_be_bytes());
    bytes.extend_from_slice(&payload_len.to_be_bytes());
    bytes.extend_from_slice(metadata);
    bytes.extend_from_slice(payload);
    Ok(bytes)
}

fn encode_wire_frame(metadata: &[u8], payload: &[u8]) -> Result<Vec<u8>, String> {
    let metadata_len = u32::try_from(metadata.len()).map_err(|_| "metadata bounds".to_owned())?;
    let payload_len = u32::try_from(payload.len()).map_err(|_| "payload bounds".to_owned())?;
    let total = OWNER_DISPATCH_MAGIC
        .len()
        .checked_add(8)
        .and_then(|v| v.checked_add(metadata.len()))
        .and_then(|v| v.checked_add(payload.len()))
        .ok_or_else(|| "frame bounds".to_owned())?;
    if total > MAX_OWNER_DISPATCH_FRAME_BYTES {
        return Err("dispatch frame bounds".to_owned());
    }
    let mut frame = Vec::with_capacity(total);
    frame.extend_from_slice(OWNER_DISPATCH_MAGIC);
    frame.extend_from_slice(&metadata_len.to_be_bytes());
    frame.extend_from_slice(&payload_len.to_be_bytes());
    frame.extend_from_slice(metadata);
    frame.extend_from_slice(payload);
    Ok(frame)
}

fn decode_wire_frame(frame: &[u8]) -> Result<(&[u8], &[u8]), String> {
    if frame.len() < 14
        || frame.len() > MAX_OWNER_DISPATCH_FRAME_BYTES
        || &frame[..6] != OWNER_DISPATCH_MAGIC
    {
        return Err("dispatch frame bounds or magic".to_owned());
    }
    let metadata_len = u32::from_be_bytes(
        frame[6..10]
            .try_into()
            .map_err(|_| "metadata length".to_owned())?,
    ) as usize;
    let payload_len = u32::from_be_bytes(
        frame[10..14]
            .try_into()
            .map_err(|_| "payload length".to_owned())?,
    ) as usize;
    let end = 14usize
        .checked_add(metadata_len)
        .and_then(|v| v.checked_add(payload_len))
        .ok_or_else(|| "dispatch frame lengths".to_owned())?;
    if end != frame.len() {
        return Err("dispatch frame lengths".to_owned());
    }
    Ok((
        &frame[14..14 + metadata_len],
        &frame[14 + metadata_len..end],
    ))
}

fn validate_payload_digest(value: &mut serde_json::Value, payload: &[u8]) -> Result<(), String> {
    let object = value
        .as_object_mut()
        .ok_or_else(|| "metadata shape".to_owned())?;
    let digest = object
        .remove("payload_sha256")
        .and_then(|value| value.as_str().map(str::to_owned))
        .ok_or_else(|| "payload digest absent".to_owned())?;
    if digest != sha256_prefixed(payload) {
        return Err("payload digest mismatch".to_owned());
    }
    Ok(())
}

fn valid_sha256(value: &str) -> bool {
    value.len() == 71
        && value.starts_with("sha256:")
        && value[7..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn sha256_prefixed(bytes: &[u8]) -> String {
    format!("sha256:{}", encode_hex(&Sha256::digest(bytes)))
}

fn encode_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 15) as usize] as char);
    }
    out
}

pub(crate) fn encode_signature_base64(signature: &[u8; 64]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut encoded = String::with_capacity(88);
    for chunk in signature.chunks(3) {
        let word = (u32::from(chunk[0]) << 16)
            | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8)
            | u32::from(*chunk.get(2).unwrap_or(&0));
        encoded.push(char::from(ALPHABET[((word >> 18) & 0x3f) as usize]));
        encoded.push(char::from(ALPHABET[((word >> 12) & 0x3f) as usize]));
        encoded.push(if chunk.len() > 1 {
            char::from(ALPHABET[((word >> 6) & 0x3f) as usize])
        } else {
            '='
        });
        encoded.push(if chunk.len() > 2 {
            char::from(ALPHABET[(word & 0x3f) as usize])
        } else {
            '='
        });
    }
    encoded
}

pub(crate) fn decode_signature_base64(text: &str) -> Result<[u8; 64], String> {
    if text.len() != 88 || !text.ends_with("==") {
        return Err("invalid Ed25519 signature base64 length".to_owned());
    }
    let mut decoded = Vec::with_capacity(64);
    for (index, chunk) in text.as_bytes().chunks_exact(4).enumerate() {
        let final_chunk = index == 21;
        if (!final_chunk && chunk.contains(&b'='))
            || (final_chunk && (chunk[2] != b'=' || chunk[3] != b'='))
        {
            return Err("invalid Ed25519 signature base64 padding".to_owned());
        }
        let a = u32::from(base64_value(chunk[0])?);
        let b = u32::from(base64_value(chunk[1])?);
        let c = if chunk[2] == b'=' {
            0
        } else {
            u32::from(base64_value(chunk[2])?)
        };
        let d = if chunk[3] == b'=' {
            0
        } else {
            u32::from(base64_value(chunk[3])?)
        };
        let word = (a << 18) | (b << 12) | (c << 6) | d;
        decoded.push(u8::try_from((word >> 16) & 0xff).map_err(|_| "base64 byte bounds")?);
        if chunk[2] != b'=' {
            decoded.push(u8::try_from((word >> 8) & 0xff).map_err(|_| "base64 byte bounds")?);
        }
        if chunk[3] != b'=' {
            decoded.push(u8::try_from(word & 0xff).map_err(|_| "base64 byte bounds")?);
        }
    }
    let decoded: [u8; 64] = decoded
        .try_into()
        .map_err(|_| "invalid Ed25519 signature base64 bytes".to_owned())?;
    if encode_signature_base64(&decoded) != text {
        return Err("non-canonical Ed25519 signature base64".to_owned());
    }
    Ok(decoded)
}

fn base64_value(value: u8) -> Result<u8, String> {
    match value {
        b'A'..=b'Z' => Ok(value - b'A'),
        b'a'..=b'z' => Ok(value - b'a' + 26),
        b'0'..=b'9' => Ok(value - b'0' + 52),
        b'+' => Ok(62),
        b'/' => Ok(63),
        _ => Err("invalid Ed25519 signature base64".to_owned()),
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use ed25519_dalek::{Signer, SigningKey};
    use rusty_quest_media_stream::{
        MediaStreamOwnerActionKind, MediaStreamOwnerKind, MediaStreamPlatformOperation,
    };

    use super::*;

    #[derive(Clone)]
    struct TestSigner {
        id: String,
        key: SigningKey,
    }

    impl OwnerDispatchSigner for TestSigner {
        fn key_id(&self) -> &str {
            &self.id
        }
        fn sign(&self, message: &[u8]) -> Result<[u8; 64], String> {
            Ok(self.key.sign(message).to_bytes())
        }
    }

    struct TestVerifier {
        key: VerifyingKey,
    }

    impl OwnerDispatchAuthorityVerifier for TestVerifier {
        fn verify_current(
            &self,
            request: &OwnerDispatchRequest,
            signing_bytes: &[u8],
            signature: &[u8; 64],
        ) -> Result<(), String> {
            self.key
                .verify_strict(signing_bytes, &Signature::from_bytes(signature))
                .map_err(|_| "request signature".to_owned())?;
            if request.authority.schema_id != "rusty.quest.c1.owner_projection.v1" {
                return Err("projection schema".to_owned());
            }
            Ok(())
        }
    }
    impl ProductActivationAuthorityVerifier for TestVerifier {
        fn verify_activation(
            &self,
            request: &ProductActivationRequest,
            signing_bytes: &[u8],
            signature: &[u8; 64],
        ) -> Result<(), String> {
            self.key
                .verify_strict(signing_bytes, &Signature::from_bytes(signature))
                .map_err(|_| "activation signature".to_owned())?;
            if request.authority.schema_id != "rusty.quest.c1.owner_projection.v1" {
                return Err("projection schema".to_owned());
            }
            Ok(())
        }
    }

    struct TestRegistry {
        calls: Arc<Mutex<u64>>,
    }

    struct Loopback {
        server: OwnerDispatchServer<TestVerifier, TestRegistry, TestSigner>,
    }

    struct TestClock;
    impl OwnerDispatchClock for TestClock {
        fn now_ms(&self) -> Result<u64, String> {
            Ok(1_000)
        }
    }
    struct FixedClock(u64);
    impl OwnerDispatchClock for FixedClock {
        fn now_ms(&self) -> Result<u64, String> {
            Ok(self.0)
        }
    }
    struct TestProjection;
    impl CurrentOwnerProjectionSource for TestProjection {
        fn current_projection(
            &self,
            _ticket: &AndroidMediaExecutionTicket,
            target_peer_id: &str,
            _mode: AndroidMediaExecutionMode,
            _now_ms: u64,
        ) -> Result<OwnerDispatchAuthorityProjection, String> {
            let mut value = projection();
            value.executor_peer_id = target_peer_id.to_owned();
            Ok(value)
        }
    }
    struct TestRemote {
        calls: Arc<Mutex<u64>>,
    }
    impl OwnerDispatchClient for TestRemote {
        fn execute(
            &mut self,
            _target_peer_id: String,
            authority: OwnerDispatchAuthorityProjection,
            ticket: AndroidMediaExecutionTicket,
            mode: AndroidMediaExecutionMode,
            _now_ms: u64,
        ) -> Result<AuthenticatedOwnerEffect, String> {
            TestRegistry {
                calls: self.calls.clone(),
            }
            .execute_and_verify(Some(&authority), &ticket, mode)
        }
    }

    #[derive(Default)]
    struct TestStore {
        commits: Arc<Mutex<Vec<OwnerDispatchReplaySnapshot>>>,
    }

    impl OwnerDispatchReplayStore for TestStore {
        fn commit(&mut self, snapshot: &OwnerDispatchReplaySnapshot) -> Result<(), String> {
            self.commits.lock().expect("commits").push(snapshot.clone());
            Ok(())
        }
    }

    #[derive(Default)]
    struct TestActivationStore {
        commits: Arc<Mutex<Vec<ProductActivationReplaySnapshot>>>,
    }
    impl ProductActivationReplayStore for TestActivationStore {
        fn commit(&mut self, snapshot: &ProductActivationReplaySnapshot) -> Result<(), String> {
            self.commits
                .lock()
                .expect("activation commits")
                .push(snapshot.clone());
            Ok(())
        }
    }
    struct TestActivationRegistry {
        calls: Arc<Mutex<u64>>,
    }
    impl ProductActivationRegistry for TestActivationRegistry {
        fn activate(
            &mut self,
            activation_id: &str,
            authority: &OwnerDispatchAuthorityProjection,
            _proof: &ProductActivationProof,
        ) -> Result<ProductActivationReadback, String> {
            *self.calls.lock().expect("activation calls") += 1;
            Ok(ProductActivationReadback {
                schema_id: PRODUCT_ACTIVATION_READBACK_SCHEMA.to_owned(),
                activation_id: activation_id.to_owned(),
                route_grant_id: authority.route_grant_id.clone(),
                resulting_state_revision: 1,
                activated: true,
            })
        }
    }

    impl OwnerDispatchTransport for Loopback {
        fn exchange(
            &mut self,
            request_frame: &[u8],
            max_response_bytes: usize,
        ) -> Result<Vec<u8>, String> {
            let response = self.server.handle_frame(request_frame)?;
            if response.len() > max_response_bytes {
                return Err("response bounds".to_owned());
            }
            Ok(response)
        }
    }

    impl AuthenticatedOwnerRegistry for TestRegistry {
        fn execute_and_verify(
            &mut self,
            authority: Option<&OwnerDispatchAuthorityProjection>,
            ticket: &AndroidMediaExecutionTicket,
            _mode: AndroidMediaExecutionMode,
        ) -> Result<AuthenticatedOwnerEffect, String> {
            if authority.is_none() {
                return Err("test registry requires projected authority".to_owned());
            }
            *self.calls.lock().expect("calls") += 1;
            let readback = AndroidMediaOwnerReadback {
                schema_id: crate::ANDROID_MEDIA_READBACK_SCHEMA.to_owned(),
                capability: ticket.capability.clone(),
                executor_generation: ticket.executor_generation,
                action_id: ticket.action_id.clone(),
                authority_epoch_id: ticket.authority_epoch_id.clone(),
                media_acceptance_authority_revision: ticket.media_acceptance_authority_revision,
                expected_runtime_revision: ticket.expected_runtime_revision,
                client_id: ticket.client_id.clone(),
                lease_id: ticket.lease_id.clone(),
                sequence: ticket.sequence,
                operation: ticket.operation,
                owner_kind: ticket.owner_kind,
                action_kind: ticket.action_kind,
                owner_id: ticket.owner_id.clone(),
                provider_kind: ticket.provider_kind.clone(),
                resource_id: ticket.resource_id.clone(),
                provider_handle_id: "provider.handle.1".to_owned(),
                provider_state_revision: 1,
                observed_state: "started".to_owned(),
                receipt_id: "receipt.effect.1".to_owned(),
            };
            let readback_json = serde_json::to_string(&readback).expect("readback");
            Ok(AuthenticatedOwnerEffect {
                verified: VerifiedOwnerEffect {
                    schema_id: VERIFIED_OWNER_EFFECT_SCHEMA.to_owned(),
                    receipt_id: readback.receipt_id.clone(),
                    readback_sha256: sha256_prefixed(readback_json.as_bytes()),
                    executor_generation: readback.executor_generation,
                    provider_state_revision: readback.provider_state_revision,
                    observed_state: readback.observed_state.clone(),
                    terminal: false,
                    provider_handle_id: readback.provider_handle_id.clone(),
                    detail_sha256: format!("sha256:{}", "0".repeat(64)),
                },
                readback,
                readback_json,
            })
        }
    }

    fn ticket() -> AndroidMediaExecutionTicket {
        AndroidMediaExecutionTicket {
            schema_id: crate::ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA.to_owned(),
            capability: "capability.1".to_owned(),
            executor_generation: 7,
            action_id: "action.1".to_owned(),
            authority_epoch_id: "epoch.1".to_owned(),
            media_acceptance_authority_revision: 3,
            expected_runtime_revision: 4,
            client_id: "client.1".to_owned(),
            lease_id: "lease.1".to_owned(),
            sequence: 1,
            operation: MediaStreamPlatformOperation::Start,
            owner_kind: MediaStreamOwnerKind::Source,
            action_kind: MediaStreamOwnerActionKind::Start,
            owner_id: "owner.1".to_owned(),
            provider_kind: "camera2_packed_stereo".to_owned(),
            resource_id: "camera.left".to_owned(),
        }
    }

    fn projection() -> OwnerDispatchAuthorityProjection {
        OwnerDispatchAuthorityProjection {
            schema_id: "rusty.quest.c1.owner_projection.v1".to_owned(),
            authority_peer_id: "peer.a".to_owned(),
            executor_peer_id: "peer.b".to_owned(),
            peer_session_id: "session.1".to_owned(),
            route_grant_id: "grant.1".to_owned(),
            route_authority_revision: 1,
            authority_runtime_host_id: "host.source".to_owned(),
            authority_provider_epoch_id: "epoch.1".to_owned(),
            platform_runtime_spec_id: "runtime.1".to_owned(),
            authority_client_id: "client.1".to_owned(),
            authority_runtime_lease_id: "lease.1".to_owned(),
            signed_topology_sha256: format!("sha256:{}", "1".repeat(64)),
            route_configuration_sha256: format!("sha256:{}", "2".repeat(64)),
            route_authority_evidence_sha256: format!("sha256:{}", "3".repeat(64)),
            expires_at_ms: 10_000,
            authorization_kind: OwnerDispatchAuthorizationKind::CurrentRoute,
        }
    }

    fn signed_request(signer: &TestSigner) -> OwnerDispatchRequest {
        let mut request = OwnerDispatchRequest {
            schema_id: OWNER_DISPATCH_REQUEST_SCHEMA.to_owned(),
            dispatch_id: "dispatch.1".to_owned(),
            sequence: 1,
            issued_at_ms: 1_000,
            target_peer_id: "peer.b".to_owned(),
            authority: projection(),
            ticket: ticket(),
            mode: AndroidMediaExecutionMode::Execute,
            signer_key_id: signer.id.clone(),
            signature_base64: String::new(),
        };
        request.signature_base64 = encode_signature_base64(
            &signer
                .sign(&request_signing_bytes(&request).expect("bytes"))
                .expect("sign"),
        );
        request
    }

    #[test]
    fn exact_retry_replays_consumed_evidence_without_registry_reentry() {
        let authority_signer = TestSigner {
            id: "key.a".to_owned(),
            key: SigningKey::from_bytes(&[3; 32]),
        };
        let executor_signer = TestSigner {
            id: "key.b".to_owned(),
            key: SigningKey::from_bytes(&[4; 32]),
        };
        let calls = Arc::new(Mutex::new(0));
        let commits = Arc::new(Mutex::new(Vec::new()));
        let mut server = OwnerDispatchServer::restore(
            "peer.b".to_owned(),
            TestVerifier {
                key: authority_signer.key.verifying_key(),
            },
            TestRegistry {
                calls: calls.clone(),
            },
            executor_signer,
            Box::new(TestClock),
            OwnerDispatchReplaySnapshot::default(),
            Box::new(TestStore {
                commits: commits.clone(),
            }),
        )
        .expect("server");
        let frame = encode_request_frame(&signed_request(&authority_signer)).expect("frame");
        let first = server.handle_frame(&frame).expect("first");
        let second = server.handle_frame(&frame).expect("retry");
        assert_eq!(first, second);
        assert_eq!(*calls.lock().expect("calls"), 1);
        assert_eq!(server.replay_snapshot().terminal.len(), 1);
        let committed = commits.lock().expect("commits");
        assert_eq!(committed.len(), 2);
        assert_eq!(committed[0].pending_request_sha256.len(), 1);
        assert_eq!(committed[1].terminal.len(), 1);
    }

    #[test]
    fn replay_collision_and_tampered_signature_are_rejected_before_effect() {
        let authority_signer = TestSigner {
            id: "key.a".to_owned(),
            key: SigningKey::from_bytes(&[5; 32]),
        };
        let executor_signer = TestSigner {
            id: "key.b".to_owned(),
            key: SigningKey::from_bytes(&[6; 32]),
        };
        let calls = Arc::new(Mutex::new(0));
        let mut server = OwnerDispatchServer::restore(
            "peer.b".to_owned(),
            TestVerifier {
                key: authority_signer.key.verifying_key(),
            },
            TestRegistry {
                calls: calls.clone(),
            },
            executor_signer,
            Box::new(TestClock),
            OwnerDispatchReplaySnapshot::default(),
            Box::new(TestStore::default()),
        )
        .expect("server");
        let request = signed_request(&authority_signer);
        let frame = encode_request_frame(&request).expect("frame");
        server.handle_frame(&frame).expect("first");
        let mut collision = request.clone();
        collision.issued_at_ms += 1;
        assert!(server
            .handle_frame(&encode_request_frame(&collision).expect("collision"))
            .is_err());
        let mut fresh = signed_request(&authority_signer);
        fresh.dispatch_id = "dispatch.2".to_owned();
        fresh.signature_base64 = encode_signature_base64(&[0; 64]);
        assert!(server
            .handle_frame(&encode_request_frame(&fresh).expect("tampered"))
            .is_err());
        let mut noncanonical = signed_request(&authority_signer);
        noncanonical.dispatch_id = "dispatch.3".to_owned();
        noncanonical.signature_base64 = encode_signature_base64(&[0; 64]);
        let length = noncanonical.signature_base64.len();
        noncanonical
            .signature_base64
            .replace_range(length - 3..length - 2, "B");
        assert!(server
            .handle_frame(&encode_request_frame(&noncanonical).expect("noncanonical"))
            .is_err());
        assert_eq!(*calls.lock().expect("calls"), 1);
    }

    #[test]
    fn restored_pending_attempt_remains_uncertain_and_never_reexecutes() {
        let authority_signer = TestSigner {
            id: "key.a".to_owned(),
            key: SigningKey::from_bytes(&[7; 32]),
        };
        let executor_signer = TestSigner {
            id: "key.b".to_owned(),
            key: SigningKey::from_bytes(&[8; 32]),
        };
        let request = signed_request(&authority_signer);
        let frame = encode_request_frame(&request).expect("frame");
        let mut replay = OwnerDispatchReplaySnapshot::default();
        replay
            .pending_request_sha256
            .insert(request.dispatch_id.clone(), sha256_prefixed(&frame));
        let calls = Arc::new(Mutex::new(0));
        let mut server = OwnerDispatchServer::restore(
            "peer.b".to_owned(),
            TestVerifier {
                key: authority_signer.key.verifying_key(),
            },
            TestRegistry {
                calls: calls.clone(),
            },
            executor_signer,
            Box::new(TestClock),
            replay,
            Box::new(TestStore::default()),
        )
        .expect("server");
        let response = decode_response_frame(&server.handle_frame(&frame).expect("uncertain"))
            .expect("response");
        assert_eq!(response.status, OwnerDispatchStatus::Uncertain);
        assert_eq!(*calls.lock().expect("calls"), 0);
    }

    #[test]
    fn stale_and_future_signed_requests_reject_before_registry() {
        let authority_signer = TestSigner {
            id: "key.a".to_owned(),
            key: SigningKey::from_bytes(&[17; 32]),
        };
        let calls = Arc::new(Mutex::new(0));
        for (target_now_ms, issued_at_ms) in [(31_001, 1_000), (999, 3_000)] {
            let mut server = OwnerDispatchServer::restore(
                "peer.b".to_owned(),
                TestVerifier {
                    key: authority_signer.key.verifying_key(),
                },
                TestRegistry {
                    calls: calls.clone(),
                },
                TestSigner {
                    id: "key.b".to_owned(),
                    key: SigningKey::from_bytes(&[18; 32]),
                },
                Box::new(FixedClock(target_now_ms)),
                OwnerDispatchReplaySnapshot::default(),
                Box::new(TestStore::default()),
            )
            .expect("server");
            let mut request = signed_request(&authority_signer);
            request.issued_at_ms = issued_at_ms;
            request.signature_base64.clear();
            request.signature_base64 = encode_signature_base64(
                &authority_signer
                    .sign(&request_signing_bytes(&request).expect("future bytes"))
                    .expect("future signature"),
            );
            let frame = encode_request_frame(&request).expect("frame");
            assert!(server.handle_frame(&frame).is_err());
        }
        assert_eq!(*calls.lock().expect("calls"), 0);
    }

    #[test]
    fn product_activation_is_persisted_before_callback_and_replayed_exactly_once() {
        let source = TestSigner {
            id: "key.a".to_owned(),
            key: SigningKey::from_bytes(&[31; 32]),
        };
        let target = TestSigner {
            id: "key.b".to_owned(),
            key: SigningKey::from_bytes(&[32; 32]),
        };
        let target_key = target.key.verifying_key().to_bytes();
        let completion_json = "{\"$schema\":\"rusty.quest.broker.media_completion_response.v1\",\"platform_effect_completed\":true}".to_owned();
        let request = ProductActivationRequest {
            schema_id: PRODUCT_ACTIVATION_REQUEST_SCHEMA.to_owned(),
            activation_id: "activation.independent-identity.7".to_owned(),
            issued_at_ms: 1_000,
            expires_at_ms: 2_000,
            target_peer_id: "peer.b".to_owned(),
            authority: projection(),
            proof: ProductActivationProof {
                action_id: "action.1".to_owned(),
                provider_epoch_id: "epoch.1".to_owned(),
                client_id: "client.1".to_owned(),
                lease_id: "lease.1".to_owned(),
                runtime_spec_id: "runtime.1".to_owned(),
                resulting_runtime_revision: 2,
                owner_receipt_ids: (1..=7).map(|n| format!("receipt.owner.{n}")).collect(),
                completion_sha256: sha256_prefixed(completion_json.as_bytes()),
            },
            completion_json,
            signer_key_id: source.id.clone(),
            signature_base64: String::new(),
        };
        let frame = encode_product_activation_request(request, &source).expect("activation frame");
        let calls = Arc::new(Mutex::new(0));
        let commits = Arc::new(Mutex::new(Vec::new()));
        let mut server = ProductActivationServer::restore(
            "peer.b".to_owned(),
            TestVerifier {
                key: source.key.verifying_key(),
            },
            TestActivationRegistry {
                calls: calls.clone(),
            },
            target,
            Box::new(TestClock),
            ProductActivationReplaySnapshot::default(),
            Box::new(TestActivationStore {
                commits: commits.clone(),
            }),
        )
        .expect("activation server");
        let response_frame = server.handle_frame(&frame).expect("activation");
        let response = decode_product_activation_response(&response_frame).expect("response");
        assert_eq!(response.status, OwnerDispatchStatus::Completed);
        verify_product_activation_response(
            &response,
            "activation.independent-identity.7",
            &frame,
            "key.b",
            &target_key,
        )
        .expect("signed response");
        let commits = commits.lock().expect("commits");
        assert!(commits
            .first()
            .expect("pending")
            .pending_request_sha256
            .contains_key("activation.independent-identity.7"));
        assert!(commits
            .last()
            .expect("terminal")
            .terminal
            .contains_key("activation.independent-identity.7"));
        drop(commits);
        assert_eq!(server.handle_frame(&frame).expect("retry"), response_frame);
        assert_eq!(*calls.lock().expect("calls"), 1);
    }

    #[test]
    fn remote_executor_authenticates_and_returns_exact_consumed_effect() {
        let authority_signer = TestSigner {
            id: "key.a".to_owned(),
            key: SigningKey::from_bytes(&[9; 32]),
        };
        let executor_signer = TestSigner {
            id: "key.b".to_owned(),
            key: SigningKey::from_bytes(&[10; 32]),
        };
        let executor_key = executor_signer.key.verifying_key().to_bytes();
        let calls = Arc::new(Mutex::new(0));
        let server = OwnerDispatchServer::restore(
            "peer.b".to_owned(),
            TestVerifier {
                key: authority_signer.key.verifying_key(),
            },
            TestRegistry {
                calls: calls.clone(),
            },
            executor_signer,
            Box::new(TestClock),
            OwnerDispatchReplaySnapshot::default(),
            Box::new(TestStore::default()),
        )
        .expect("server");
        let mut remote = RemoteOwnerDispatchExecutor::new(
            Loopback { server },
            authority_signer,
            executor_key,
            "key.b".to_owned(),
        )
        .expect("remote");
        let effect = remote
            .execute(
                "peer.b".to_owned(),
                projection(),
                ticket(),
                AndroidMediaExecutionMode::Execute,
                1_000,
            )
            .expect("effect");
        assert_eq!(effect.verified.receipt_id, "receipt.effect.1");
        assert_eq!(*calls.lock().expect("calls"), 1);
    }

    #[test]
    fn rqod1_bounds_and_compensation_terminal_evidence_are_fail_closed() {
        let signer = TestSigner {
            id: "key.a".to_owned(),
            key: SigningKey::from_bytes(&[11; 32]),
        };
        let request = signed_request(&signer);
        let mut frame = encode_request_frame(&request).expect("RQOD1");
        assert_eq!(&frame[..6], b"RQOD1\n");
        frame[10..14].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(decode_request_frame(&frame).is_err());

        let calls = Arc::new(Mutex::new(0));
        let mut effect = TestRegistry { calls }
            .execute_and_verify(
                Some(&projection()),
                &ticket(),
                AndroidMediaExecutionMode::CompensateUncertain,
            )
            .expect("effect");
        assert!(validate_effect(
            &ticket(),
            AndroidMediaExecutionMode::CompensateUncertain,
            &effect,
        )
        .is_err());
        effect.verified.terminal = true;
        assert!(validate_effect(
            &ticket(),
            AndroidMediaExecutionMode::CompensateUncertain,
            &effect,
        )
        .is_ok());
        effect.verified.detail_sha256 = format!("sha256:{}", "A".repeat(64));
        assert!(validate_effect(
            &ticket(),
            AndroidMediaExecutionMode::CompensateUncertain,
            &effect,
        )
        .is_err());
    }

    #[test]
    fn composite_executor_requires_all_families_and_routes_exact_sink_remotely() {
        let families = [
            MediaStreamOwnerKind::Source,
            MediaStreamOwnerKind::Processor,
            MediaStreamOwnerKind::Route,
            MediaStreamOwnerKind::Socket,
            MediaStreamOwnerKind::Codec,
            MediaStreamOwnerKind::Sink,
            MediaStreamOwnerKind::Cleanup,
        ];
        let placements = families
            .into_iter()
            .map(|owner_kind| AndroidMediaOwnerPlacement {
                owner_kind,
                owner_id: format!("owner.{owner_kind:?}"),
                provider_kind: format!("provider.{owner_kind:?}"),
                resource_id: format!("resource.{owner_kind:?}"),
                target: if owner_kind == MediaStreamOwnerKind::Sink {
                    AndroidMediaOwnerPlacementTarget::Remote {
                        peer_id: "peer.b".to_owned(),
                    }
                } else {
                    AndroidMediaOwnerPlacementTarget::Local
                },
            })
            .collect::<Vec<_>>();
        let calls = Arc::new(Mutex::new(0));
        let mut executor = CompositeAndroidMediaOwnerExecutor::new(
            7,
            "peer.a".to_owned(),
            placements.clone(),
            Box::new(TestRegistry {
                calls: calls.clone(),
            }),
            Box::new(TestRemote {
                calls: calls.clone(),
            }),
            Box::new(TestProjection),
            Box::new(TestClock),
        )
        .expect("composite");
        let mut sink = ticket();
        sink.owner_kind = MediaStreamOwnerKind::Sink;
        sink.owner_id = "owner.Sink".to_owned();
        sink.provider_kind = "provider.Sink".to_owned();
        sink.resource_id = "resource.Sink".to_owned();
        let readback = crate::AndroidMediaOwnerExecutor::execute(
            &mut executor,
            &sink,
            AndroidMediaExecutionMode::Execute,
        )
        .expect("remote sink");
        assert!(crate::AndroidMediaOwnerExecutor::verify(
            &executor, &sink, &readback
        ));
        assert!(!crate::AndroidMediaOwnerExecutor::verify(
            &executor, &sink, &readback
        ));
        assert_eq!(*calls.lock().expect("calls"), 1);
        let mut source = ticket();
        source.owner_id = "owner.Source".to_owned();
        source.provider_kind = "provider.Source".to_owned();
        source.resource_id = "resource.Source".to_owned();
        let readback = crate::AndroidMediaOwnerExecutor::execute(
            &mut executor,
            &source,
            AndroidMediaExecutionMode::Execute,
        )
        .expect("local source with projected authority");
        assert!(crate::AndroidMediaOwnerExecutor::verify(
            &executor, &source, &readback
        ));
        assert_eq!(*calls.lock().expect("calls"), 2);
        assert!(CompositeAndroidMediaOwnerExecutor::new(
            7,
            "peer.a".to_owned(),
            placements.into_iter().take(6).collect(),
            Box::new(TestRegistry {
                calls: calls.clone()
            }),
            Box::new(TestRemote { calls }),
            Box::new(TestProjection),
            Box::new(TestClock),
        )
        .is_err());
    }
}
