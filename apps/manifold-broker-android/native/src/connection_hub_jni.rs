//! JNI owner for the durable Manifold Connection Hub authority.

use rusty_manifold_broker_product::ManifoldBrokerProductLock;
use rusty_manifold_connection_hub::{
    ManifoldConnectionHubAuthority, ManifoldConnectionHubOperationRequest,
    ManifoldConnectionHubPolicy, ManifoldConnectionHubReceipt, ManifoldConnectionHubRequest,
    ManifoldConnectionHubSurface, ManifoldConnectionHubSurfaceCommand, EMPTY_TYPED_PARAMS_SCHEMA,
    REQUEST_SCHEMA, SURFACE_SCHEMA,
};
use rusty_manifold_model::{DottedId, SchemaId};
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::{Mutex, OnceLock};

const CONFIG_SCHEMA: &str = "rusty.quest.connection_hub.native_config.v1";
const STATE_SCHEMA: &str = "rusty.quest.connection_hub.native_state.v1";
const RECEIPT_SCHEMA: &str = "rusty.quest.connection_hub.native_receipt.v1";
const EXPECTED_PRODUCT_ID: &str = "broker.connection-hub.standalone";
const VERIFIED_WEARER_EVIDENCE: &str = "evidence.operator.wearer-action";
const EMPTY_TYPED_PARAMS_SCHEMA_SHA256: &str =
    "sha256:7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51";
const EXPECTED_MANIFOLD_REVISION: &str = "661bf0ad1d95f6d17715440c23d6085e4305adeb";
const EXPECTED_MANIFOLD_TREE: &str = "22c0b9797500758c4af257eb3869e61ae229b2af";

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct HubConfig {
    #[serde(rename = "$schema")]
    schema: String,
    product_id: String,
    product_lock_id: String,
    product_lock_sha256: String,
    product_lock: ManifoldBrokerProductLock,
    packaged_product_lock_json: String,
    manifold_revision: String,
    manifold_tree: String,
    policy: ManifoldConnectionHubPolicy,
}

struct HubOwner {
    config: HubConfig,
    authority: ManifoldConnectionHubAuthority,
}

static HUB_OWNER: OnceLock<Mutex<Option<HubOwner>>> = OnceLock::new();

fn owner() -> &'static Mutex<Option<HubOwner>> {
    HUB_OWNER.get_or_init(|| Mutex::new(None))
}

pub(crate) fn initialize(config_json: &str) -> Result<String, String> {
    let config: HubConfig = serde_json::from_str(config_json).map_err(|e| e.to_string())?;
    if config.schema != CONFIG_SCHEMA
        || config.product_id != EXPECTED_PRODUCT_ID
        || !is_sha256(&config.product_lock_sha256)
        || config.product_lock_id.as_str() != "lock.broker.connection-hub.standalone"
        || config.manifold_revision != EXPECTED_MANIFOLD_REVISION
        || config.manifold_tree != EXPECTED_MANIFOLD_TREE
    {
        return Err("connection hub product binding rejected".to_owned());
    }
    let admission = crate::admission_jni::admission_authority()?;
    let authority = ManifoldConnectionHubAuthority::new(
        config.policy.clone(),
        &admission,
        &config.product_lock,
        config.packaged_product_lock_json.as_bytes(),
    )
    .map_err(|e| e.to_string())?;
    let mut guard = owner()
        .lock()
        .map_err(|_| "hub owner lock poisoned".to_owned())?;
    if let Some(existing) = guard.as_ref() {
        if existing.config.product_id != config.product_id
            || existing.config.product_lock_sha256 != config.product_lock_sha256
            || existing.config.product_lock != config.product_lock
            || existing.config.packaged_product_lock_json != config.packaged_product_lock_json
            || existing.config.policy != config.policy
        {
            return Err("connection hub reinitialization substitution rejected".to_owned());
        }
        return Ok(json!({
            "$schema": "rusty.quest.connection_hub.native_status.v1",
            "initialized": true,
            "existing_authority_preserved": true,
            "product_id": existing.config.product_id,
            "product_lock_sha256": existing.config.product_lock_sha256,
        })
        .to_string());
    }
    *guard = Some(HubOwner { config, authority });
    Ok(json!({
        "$schema": "rusty.quest.connection_hub.native_status.v1",
        "initialized": true,
        "existing_authority_preserved": false,
        "product_id": EXPECTED_PRODUCT_ID,
    })
    .to_string())
}

pub(crate) fn execute(proposal_json: &str, now_ms: u64) -> Result<String, String> {
    let proposal: Value = serde_json::from_str(proposal_json).map_err(|e| e.to_string())?;
    let operation = proposal
        .get("operation")
        .and_then(Value::as_str)
        .ok_or_else(|| "missing connection hub operation".to_owned())?;
    let mut guard = owner()
        .lock()
        .map_err(|_| "hub owner lock poisoned".to_owned())?;
    let retained = guard
        .as_mut()
        .ok_or_else(|| "hub authority not initialized".to_owned())?;
    let response = match operation {
        "trust_and_open_session" => trust_and_open(retained, &proposal, now_ms)?,
        "replace_transport" => apply_lifecycle(
            retained,
            &proposal,
            now_ms,
            ManifoldConnectionHubOperationRequest::ReplaceTransport {
                session_id: epoch_field(retained, &proposal, "session_id")?,
                expected_transport_epoch: u64_field(&proposal, "expected_transport_epoch")?,
                transport: transport(&proposal, now_ms)?,
            },
        )?,
        "register_provider" => register_provider(retained, &proposal, now_ms)?,
        "register_surface" => register_surface(retained, &proposal, now_ms)?,
        "unregister_surface" => apply_lifecycle(
            retained,
            &proposal,
            now_ms,
            ManifoldConnectionHubOperationRequest::UnregisterSurface {
                surface_id: epoch_field(retained, &proposal, "surface_id")?,
                provider_instance_id: epoch_field(retained, &proposal, "provider_instance_id")?,
                reason: DottedId::new("reason.provider.lifecycle-end")
                    .map_err(|e| e.to_string())?,
            },
        )?,
        "unregister_provider" => unregister_provider(retained, &proposal, now_ms)?,
        "acquire_surface_lease" => apply_lifecycle(
            retained,
            &proposal,
            now_ms,
            ManifoldConnectionHubOperationRequest::AcquireSurfaceLease {
                lease_id: epoch_derived(retained, "lease.hub", text(&proposal, "request_id")?)?,
                session_id: epoch_field(retained, &proposal, "session_id")?,
                expected_transport_epoch: u64_field(&proposal, "expected_transport_epoch")?,
                surface_id: epoch_field(retained, &proposal, "surface_id")?,
                requested_ttl_ms: retained.config.policy.max_surface_lease_ttl_ms,
            },
        )?,
        "release_surface_lease" => apply_lifecycle(
            retained,
            &proposal,
            now_ms,
            ManifoldConnectionHubOperationRequest::ReleaseSurfaceLease {
                lease_id: epoch_field(retained, &proposal, "lease_id")?,
                session_id: epoch_field(retained, &proposal, "session_id")?,
                reason: reason(&proposal)?,
            },
        )?,
        "authorize_surface_command" => apply_lifecycle(
            retained,
            &proposal,
            now_ms,
            ManifoldConnectionHubOperationRequest::AuthorizeSurfaceCommand {
                session_id: epoch_field(retained, &proposal, "session_id")?,
                expected_transport_epoch: u64_field(&proposal, "expected_transport_epoch")?,
                lease_id: epoch_field(retained, &proposal, "lease_id")?,
                command_id: dotted(&proposal, "command_id")?,
                typed_params_schema_id: SchemaId::new(EMPTY_TYPED_PARAMS_SCHEMA)
                    .map_err(|e| e.to_string())?,
                typed_params_schema_sha256: EMPTY_TYPED_PARAMS_SCHEMA_SHA256.to_owned(),
                typed_params_sha256: sha256_field(&proposal, "typed_params_sha256")?,
            },
        )?,
        "revoke_session" => apply_lifecycle(
            retained,
            &proposal,
            now_ms,
            ManifoldConnectionHubOperationRequest::RevokeSession {
                session_id: epoch_field(retained, &proposal, "session_id")?,
                reason: reason(&proposal)?,
            },
        )?,
        "forget_all" => forget_all(retained, &proposal, now_ms)?,
        "expire" => apply_lifecycle(
            retained,
            &proposal,
            now_ms,
            ManifoldConnectionHubOperationRequest::Expire,
        )?,
        "reconcile_restart" => reconcile_restart(retained, &proposal, now_ms)?,
        _ => return Err("unsupported connection hub operation".to_owned()),
    };
    Ok(response.to_string())
}

pub(crate) fn export_state() -> Result<String, String> {
    let guard = owner()
        .lock()
        .map_err(|_| "hub owner lock poisoned".to_owned())?;
    let retained = guard
        .as_ref()
        .ok_or_else(|| "hub authority not initialized".to_owned())?;
    let snapshot: Value = serde_json::from_str(
        &retained
            .authority
            .snapshot_json()
            .map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    Ok(json!({
        "$schema": STATE_SCHEMA,
        "product_id": retained.config.product_id,
        "product_lock_id": retained.config.product_lock_id,
        "product_lock_sha256": retained.config.product_lock_sha256,
        "manifold_revision": retained.config.manifold_revision,
        "manifold_tree": retained.config.manifold_tree,
        "authority_snapshot": snapshot,
    })
    .to_string())
}

pub(crate) fn restore_state(state_json: &str) -> Result<String, String> {
    let state: Value = serde_json::from_str(state_json).map_err(|e| e.to_string())?;
    let mut guard = owner()
        .lock()
        .map_err(|_| "hub owner lock poisoned".to_owned())?;
    let retained = guard
        .as_mut()
        .ok_or_else(|| "hub authority not initialized".to_owned())?;
    if state.get("$schema").and_then(Value::as_str) != Some(STATE_SCHEMA)
        || state.get("product_id").and_then(Value::as_str) != Some(&retained.config.product_id)
        || state.get("product_lock_id").and_then(Value::as_str)
            != Some(&retained.config.product_lock_id)
        || state.get("product_lock_sha256").and_then(Value::as_str)
            != Some(&retained.config.product_lock_sha256)
        || state.get("manifold_revision").and_then(Value::as_str)
            != Some(&retained.config.manifold_revision)
        || state.get("manifold_tree").and_then(Value::as_str)
            != Some(&retained.config.manifold_tree)
    {
        return Err("connection hub restart product substitution rejected".to_owned());
    }
    let snapshot = state
        .get("authority_snapshot")
        .ok_or_else(|| "missing authority snapshot".to_owned())?;
    let admission = crate::admission_jni::admission_authority()?;
    retained.authority = ManifoldConnectionHubAuthority::restart_from_json(
        &snapshot.to_string(),
        &admission,
        &retained.config.product_lock,
        retained.config.packaged_product_lock_json.as_bytes(),
    )
    .map_err(|e| e.to_string())?;
    Ok(json!({
        "$schema": RECEIPT_SCHEMA,
        "applied": true,
        "status": "state_restored",
        "authority_receipt": {},
    })
    .to_string())
}

fn trust_and_open(owner: &mut HubOwner, proposal: &Value, now_ms: u64) -> Result<Value, String> {
    let request_id = text(proposal, "request_id")?;
    let identity_sha = sha256_field(proposal, "controller_identity_sha256")?;
    let existing = owner
        .authority
        .snapshot()
        .state
        .trusted_controllers
        .iter()
        .find(|item| item.public_identity_sha256 == identity_sha)
        .map(|item| item.controller_id.clone());
    let controller_id = if let Some(value) = existing {
        value
    } else {
        let id = epoch_derived(
            owner,
            "controller.hub",
            identity_sha.trim_start_matches("sha256:"),
        )?;
        let evidence = DottedId::new(VERIFIED_WEARER_EVIDENCE).map_err(|e| e.to_string())?;
        let trust = request(
            owner,
            &format!("{request_id}.trust"),
            now_ms,
            ManifoldConnectionHubOperationRequest::TrustController {
                controller_id: id.clone(),
                public_identity_sha256: identity_sha.clone(),
                capabilities: owner.config.policy.allowed_controller_capabilities.clone(),
                operator_evidence_id: evidence.clone(),
                requested_ttl_ms: owner.config.policy.max_controller_ttl_ms,
            },
        )?;
        let receipt = owner
            .authority
            .owner()
            .apply_operator_decision(&trust, now_ms, &evidence);
        if !receipt.applied {
            return Ok(native_receipt(receipt));
        }
        id
    };
    let session_id = epoch_derived(owner, "session.hub", request_id)?;
    let open = request(
        owner,
        &format!("{request_id}.open"),
        now_ms,
        ManifoldConnectionHubOperationRequest::OpenSession {
            session_id,
            controller_id,
            public_identity_sha256: identity_sha,
            transport: transport(proposal, now_ms)?,
            requested_ttl_ms: owner.config.policy.max_session_ttl_ms,
        },
    )?;
    Ok(native_receipt(
        owner.authority.owner().apply_lifecycle(&open, now_ms),
    ))
}

fn register_provider(owner: &mut HubOwner, proposal: &Value, now_ms: u64) -> Result<Value, String> {
    let admission = crate::admission_jni::admission_authority()?;
    let package_name = text(proposal, "package_name")?;
    let proposed_signer = sha256_field(proposal, "signer_sha256")?;
    let client_id = admission
        .snapshot()
        .grants
        .iter()
        .find(|grant| {
            grant.identity.platform_subject == package_name
                && grant.identity.signing_fingerprint == proposed_signer
        })
        .map(|grant| grant.identity.client_id.clone())
        .ok_or_else(|| "platform identity has no retained admission grant".to_owned())?;
    let provider_id = owner
        .config
        .policy
        .provider_grants
        .iter()
        .find(|grant| grant.client_id == client_id)
        .map(|grant| grant.provider_id.clone())
        .ok_or_else(|| "platform identity has no connection hub provider grant".to_owned())?;
    let mutation = request(
        owner,
        text(proposal, "request_id")?,
        now_ms,
        ManifoldConnectionHubOperationRequest::RegisterProvider {
            provider_id,
            provider_instance_id: epoch_field(owner, proposal, "provider_instance_id")?,
            admission_use_request_id: dotted(proposal, "admission_use_request_id")?,
        },
    )?;
    Ok(native_receipt(
        owner
            .authority
            .owner()
            .register_provider(&mutation, now_ms, &admission),
    ))
}

fn register_surface(owner: &mut HubOwner, proposal: &Value, now_ms: u64) -> Result<Value, String> {
    let instance = epoch_field(owner, proposal, "provider_instance_id")?;
    let surface_id = epoch_field(owner, proposal, "surface_id")?;
    let provider_id = owner
        .authority
        .snapshot()
        .state
        .providers
        .iter()
        .find(|item| item.provider_instance_id == instance)
        .map(|item| item.provider_id.clone())
        .ok_or_else(|| "provider instance is not active".to_owned())?;
    let commands = proposal
        .get("commands")
        .and_then(Value::as_array)
        .ok_or_else(|| "missing commands".to_owned())?
        .iter()
        .map(|command| {
            Ok(ManifoldConnectionHubSurfaceCommand {
                command_id: dotted(command, "command_id")?,
                typed_params_schema_id: SchemaId::new(EMPTY_TYPED_PARAMS_SCHEMA)
                    .map_err(|e| e.to_string())?,
                typed_params_schema_sha256: EMPTY_TYPED_PARAMS_SCHEMA_SHA256.to_owned(),
                required_controller_capability: dotted(command, "required_controller_capability")?,
            })
        })
        .collect::<Result<Vec<_>, String>>()?;
    let surface = ManifoldConnectionHubSurface {
        schema_id: SchemaId::new(SURFACE_SCHEMA).map_err(|e| e.to_string())?,
        surface_id,
        provider_id,
        provider_instance_id: instance,
        display_label: text(proposal, "display_label")?.to_owned(),
        description: text(proposal, "description")?.to_owned(),
        surface_contract_sha256: sha256_field(proposal, "surface_contract_sha256")?,
        commands,
        registered_at_ms: now_ms,
    };
    apply_lifecycle(
        owner,
        proposal,
        now_ms,
        ManifoldConnectionHubOperationRequest::RegisterSurface { surface },
    )
}

fn unregister_provider(
    owner: &mut HubOwner,
    proposal: &Value,
    now_ms: u64,
) -> Result<Value, String> {
    let instance = epoch_field(owner, proposal, "provider_instance_id")?;
    let provider_id = owner
        .authority
        .snapshot()
        .state
        .providers
        .iter()
        .find(|item| item.provider_instance_id == instance)
        .map(|item| item.provider_id.clone())
        .ok_or_else(|| "provider instance is not active".to_owned())?;
    apply_lifecycle(
        owner,
        proposal,
        now_ms,
        ManifoldConnectionHubOperationRequest::UnregisterProvider {
            provider_id,
            provider_instance_id: instance,
            reason: reason(proposal)?,
        },
    )
}

fn forget_all(owner: &mut HubOwner, proposal: &Value, now_ms: u64) -> Result<Value, String> {
    let evidence = DottedId::new(VERIFIED_WEARER_EVIDENCE).map_err(|e| e.to_string())?;
    let controllers = owner
        .authority
        .snapshot()
        .state
        .trusted_controllers
        .iter()
        .map(|item| item.controller_id.clone())
        .collect::<Vec<_>>();
    let mut receipts = Vec::new();
    for (index, controller_id) in controllers.into_iter().enumerate() {
        let mutation = request(
            owner,
            &format!("{}.forget.{index}", text(proposal, "request_id")?),
            now_ms,
            ManifoldConnectionHubOperationRequest::ForgetController {
                controller_id,
                operator_evidence_id: evidence.clone(),
                reason: reason(proposal)?,
            },
        )?;
        let receipt = owner
            .authority
            .owner()
            .apply_operator_decision(&mutation, now_ms, &evidence);
        let applied = receipt.applied;
        receipts.push(serde_json::to_value(&receipt).map_err(|e| e.to_string())?);
        if !applied {
            return Ok(native_receipt(receipt));
        }
    }
    Ok(json!({"$schema": RECEIPT_SCHEMA, "applied": true,
        "status": "controllers_forgotten", "authority_receipts": receipts,
        "authority_receipt": receipts.last().cloned().unwrap_or_else(|| json!({}))}))
}

fn reconcile_restart(owner: &mut HubOwner, proposal: &Value, now_ms: u64) -> Result<Value, String> {
    let providers = owner
        .authority
        .snapshot()
        .state
        .providers
        .iter()
        .map(|item| (item.provider_id.clone(), item.provider_instance_id.clone()))
        .collect::<Vec<_>>();
    let mut receipts = Vec::new();
    for (index, (provider_id, provider_instance_id)) in providers.into_iter().enumerate() {
        let mutation = request(
            owner,
            &format!("{}.provider.{index}", text(proposal, "request_id")?),
            now_ms,
            ManifoldConnectionHubOperationRequest::UnregisterProvider {
                provider_id,
                provider_instance_id,
                reason: DottedId::new("reason.provider.process-restart")
                    .map_err(|e| e.to_string())?,
            },
        )?;
        let receipt = owner.authority.owner().apply_lifecycle(&mutation, now_ms);
        let applied = receipt.applied;
        receipts.push(serde_json::to_value(&receipt).map_err(|e| e.to_string())?);
        if !applied {
            return Ok(native_receipt(receipt));
        }
    }
    Ok(json!({"$schema": RECEIPT_SCHEMA, "applied": true,
        "status": "restart_reconciled", "authority_receipts": receipts,
        "authority_receipt": receipts.last().cloned().unwrap_or_else(|| json!({}))}))
}

fn apply_lifecycle(
    owner: &mut HubOwner,
    proposal: &Value,
    now_ms: u64,
    operation: ManifoldConnectionHubOperationRequest,
) -> Result<Value, String> {
    let mutation = request(owner, text(proposal, "request_id")?, now_ms, operation)?;
    Ok(native_receipt(
        owner.authority.owner().apply_lifecycle(&mutation, now_ms),
    ))
}

fn request(
    owner: &HubOwner,
    request_id: &str,
    now_ms: u64,
    operation: ManifoldConnectionHubOperationRequest,
) -> Result<ManifoldConnectionHubRequest, String> {
    Ok(ManifoldConnectionHubRequest {
        schema_id: SchemaId::new(REQUEST_SCHEMA).map_err(|e| e.to_string())?,
        request_id: epoch_derived(owner, "request", request_id)?,
        authority_epoch: owner.authority.snapshot().state.authority_epoch,
        expected_authority_revision: owner.authority.snapshot().state.authority_revision,
        requested_at_ms: now_ms,
        operation,
    })
}

fn native_receipt(receipt: ManifoldConnectionHubReceipt) -> Value {
    let status = if receipt.applied {
        "applied"
    } else {
        "rejected"
    };
    let authority_receipt = serde_json::to_value(&receipt).unwrap_or_else(|_| json!({}));
    let mut output = json!({
        "$schema": RECEIPT_SCHEMA,
        "applied": receipt.applied,
        "status": status,
        "authority_receipt": authority_receipt,
    });
    if let Some(session) = receipt.session {
        output["logical_session_id"] = json!(session.session_id);
        output["transport_epoch"] = json!(session.transport_epoch);
        output["expires_at_ms"] = json!(session.expires_at_ms);
    }
    if let Some(lease) = receipt.surface_lease {
        output["surface_lease_id"] = json!(lease.lease_id);
        output["expires_at_ms"] = json!(lease.expires_at_ms);
    }
    if !receipt.applied {
        output["status"] =
            json!(format!("rejected_{:?}", receipt.rejection_reason).to_ascii_lowercase());
    }
    output
}

fn transport(
    proposal: &Value,
    now_ms: u64,
) -> Result<rusty_manifold_connection_hub::ManifoldConnectionHubTransportBinding, String> {
    let request_id = text(proposal, "request_id")?;
    Ok(
        rusty_manifold_connection_hub::ManifoldConnectionHubTransportBinding {
            transport_id: derived("transport.hub", request_id)?,
            evidence_id: derived("evidence.transport.hub", request_id)?,
            attached_at_ms: now_ms,
        },
    )
}

fn dotted(value: &Value, field: &str) -> Result<DottedId, String> {
    DottedId::new(text(value, field)?).map_err(|e| e.to_string())
}

fn epoch_field(owner: &HubOwner, value: &Value, field: &str) -> Result<DottedId, String> {
    epoch_derived(owner, field, text(value, field)?)
}

fn epoch_derived(owner: &HubOwner, prefix: &str, source: &str) -> Result<DottedId, String> {
    let epoch = owner.authority.snapshot().state.authority_epoch;
    let expected_prefix = format!("epoch-{epoch}.");
    if source.starts_with(&expected_prefix) {
        return DottedId::new(source).map_err(|e| e.to_string());
    }
    let normalized_prefix = prefix.replace('_', "-");
    let suffix: String = source
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .take(40)
        .collect();
    if suffix.is_empty() {
        return Err("connection hub epoch identity source is empty".to_owned());
    }
    DottedId::new(format!("epoch-{epoch}.{normalized_prefix}.{suffix}")).map_err(|e| e.to_string())
}

fn derived(prefix: &str, source: &str) -> Result<DottedId, String> {
    let suffix: String = source
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .take(48)
        .collect();
    DottedId::new(&format!("{prefix}.{suffix}")).map_err(|e| e.to_string())
}

fn reason(value: &Value) -> Result<DottedId, String> {
    let raw = value
        .get("reason")
        .and_then(Value::as_str)
        .unwrap_or("user-request");
    derived("reason.hub", raw)
}

fn text<'a>(value: &'a Value, field: &str) -> Result<&'a str, String> {
    value
        .get(field)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("missing {field}"))
}

fn u64_field(value: &Value, field: &str) -> Result<u64, String> {
    value
        .get(field)
        .and_then(Value::as_u64)
        .ok_or_else(|| format!("missing {field}"))
}

fn sha256_field(value: &Value, field: &str) -> Result<String, String> {
    let raw = text(value, field)?;
    let normalized = if raw.len() == 64 {
        format!("sha256:{raw}")
    } else {
        raw.to_owned()
    };
    if is_sha256(&normalized) {
        Ok(normalized)
    } else {
        Err(format!("invalid {field}"))
    }
}

fn is_sha256(value: &str) -> bool {
    value.len() == 71
        && value.starts_with("sha256:")
        && value[7..]
            .bytes()
            .all(|item| item.is_ascii_digit() || (b'a'..=b'f').contains(&item))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retained_owner_opens_replaces_and_restores_one_logical_session() {
        let runtime_config = crate::admission_jni::tests::runtime_config().to_string();
        let runtime_config_sha =
            rusty_quest_broker_authority::canonical_runtime_config_sha256(&runtime_config)
                .expect("runtime config sha");
        crate::admission_jni::initialize(
            &runtime_config,
            &runtime_config_sha,
            &"31".repeat(32),
            1_000,
            1_000_000_000,
        )
        .expect("admission runtime");
        let manifold_root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../rusty-manifold");
        let product_lock_json = std::fs::read_to_string(
            manifold_root.join("fixtures/broker-product/connection-hub-standalone.lock.json"),
        )
        .expect("connection hub product lock");
        let product_lock: Value =
            serde_json::from_str(&product_lock_json).expect("connection hub product lock json");
        let product_lock_sha = format!(
            "sha256:{}",
            rusty_quest_broker_authority::packaged_json_sha256(&product_lock_json)
        );
        let digest = format!("sha256:{}", "a".repeat(64));
        let policy = json!({
            "$schema": "rusty.manifold.connection_hub.policy.v2",
            "authority_id": "authority.connection-hub.quest",
            "admission_authority_id": "authority.admission.quest",
            "broker_product_lock_id": "lock.broker.connection-hub.standalone",
            "broker_product_lock_fingerprint": product_lock["spec_fingerprint"],
            "broker_product_lock_sha256": product_lock_sha.clone(),
            "trusted_operator_evidence_ids": [VERIFIED_WEARER_EVIDENCE],
            "allowed_controller_capabilities": ["capability.sample.toggle"],
            "provider_grants": [{
                "provider_id": "provider.sample",
                "client_id": "client.sample",
                "client_lock_id": "lock.client.sample",
                "client_lock_sha256": digest,
                "surface_contract_sha256": format!("sha256:{}", "b".repeat(64)),
                "allowed_commands": [{
                    "command_id": "command.sample.toggle",
                    "typed_params_schema_id": EMPTY_TYPED_PARAMS_SCHEMA,
                    "typed_params_schema_sha256": EMPTY_TYPED_PARAMS_SCHEMA_SHA256,
                    "required_controller_capability": "capability.sample.toggle"
                }]
            }],
            "max_controller_ttl_ms": 100_000,
            "max_session_ttl_ms": 80_000,
            "max_surface_lease_ttl_ms": 60_000
        });
        let config = json!({
            "$schema": CONFIG_SCHEMA,
            "product_id": EXPECTED_PRODUCT_ID,
            "product_lock_id": "lock.broker.connection-hub.standalone",
            "product_lock_sha256": product_lock_sha,
            "product_lock": product_lock,
            "packaged_product_lock_json": product_lock_json,
            "manifold_revision": EXPECTED_MANIFOLD_REVISION,
            "manifold_tree": EXPECTED_MANIFOLD_TREE,
            "policy": policy
        });
        let initialized: Value =
            serde_json::from_str(&initialize(&config.to_string()).expect("initialize"))
                .expect("status");
        assert_eq!(initialized["initialized"], true);
        let opened: Value = serde_json::from_str(
            &execute(
                &json!({
                    "operation": "trust_and_open_session",
                    "request_id": "request.hub.test.open",
                    "controller_identity_sha256": "d".repeat(64)
                })
                .to_string(),
                1_000,
            )
            .expect("open"),
        )
        .expect("open receipt");
        assert_eq!(opened["applied"], true);
        assert_eq!(opened["transport_epoch"], 1);
        let session = opened["logical_session_id"].as_str().expect("session");
        let replaced: Value = serde_json::from_str(
            &execute(
                &json!({
                    "operation": "replace_transport",
                    "request_id": "request.hub.test.replace",
                    "session_id": session,
                    "expected_transport_epoch": 1
                })
                .to_string(),
                2_000,
            )
            .expect("replace"),
        )
        .expect("replace receipt");
        assert_eq!(replaced["applied"], true);
        assert_eq!(replaced["transport_epoch"], 2);
        let exported = export_state().expect("export");
        let restored: Value = serde_json::from_str(&restore_state(&exported).expect("restore"))
            .expect("restore receipt");
        assert_eq!(restored["applied"], true);
    }
}

#[cfg(target_os = "android")]
fn jni_string(
    mut env: jni::EnvUnowned,
    input: jni::objects::JString,
    operation: impl FnOnce(&str) -> Result<String, String>,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let input = input.try_to_string(env)?;
            let response = operation(&input).unwrap_or_else(|_| String::new());
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldConnectionHubNativeBridge_nativeInitialize(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    config: jni::objects::JString,
) -> jni::sys::jstring {
    jni_string(env, config, initialize)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldConnectionHubNativeBridge_nativeExecute(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    proposal: jni::objects::JString,
    now_ms: jni::sys::jlong,
) -> jni::sys::jstring {
    jni_string(env, proposal, |value| {
        u64::try_from(now_ms)
            .map_err(|_| "negative hub clock".to_owned())
            .and_then(|now| execute(value, now))
    })
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldConnectionHubNativeBridge_nativeRestore(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    state: jni::objects::JString,
) -> jni::sys::jstring {
    jni_string(env, state, restore_state)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldConnectionHubNativeBridge_nativeExport(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            env.new_string(export_state().unwrap_or_default())
                .map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}
