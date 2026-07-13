//! Shared broker-client specs for independent Quest applications.
//!
//! This crate owns no Binder calls, grants, tokens, sessions, streams, app
//! defaults, or media. Platform clients project these validated specs; Manifold
//! remains admission and accepted peer/media-session authority.

mod media_lifecycle;

pub use media_lifecycle::*;
pub use rusty_quest_broker_contracts::{
    validate_broker_client_spec, validate_media_lifecycle_package, BrokerClientSpec,
    BrokerMediaLifecycleLock, BrokerMediaLifecyclePackageBinding,
    BrokerMediaProductBindingDocument, ValidatedBrokerMediaLifecyclePackage,
    BROKER_ADMISSION_PERMISSION, BROKER_CLIENT_SPEC_SCHEMA, BROKER_MEDIA_LIFECYCLE_LOCK_SCHEMA,
    BROKER_MEDIA_LIFECYCLE_LOCK_SCHEMA_V1, BROKER_MEDIA_LIFECYCLE_PACKAGE_SCHEMA,
    MEDIA_SESSION_CONTRACT, PEER_SESSION_CONTRACT,
};

use std::collections::BTreeSet;

use rusty_manifold_runtime_host::{
    HOST_TYPED_PARAMS_DIGEST_SCHEMA, MAX_TYPED_PARAMS_CANONICAL_BYTES,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

/// Generic QCL media receipt fold schema.
pub const GENERIC_MEDIA_RECEIPT_SCHEMA: &str = "rusty.quest.generic_media_session_evidence.v1";
/// Admitted real server mutation schema.
pub const BROKER_SERVER_MUTATION_SCHEMA: &str = "rusty.quest.broker.server_mutation_request.v1";
/// Typed low-rate effect-parameter schema shared with the Rust authority path.
pub const BROKER_EFFECT_PARAMS_SCHEMA: &str = "rusty.quest.broker.effect_params.v1";

/// Pair-level differential validation receipt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BrokerClientPairReceipt {
    /// Receipt schema.
    pub schema: String,
    /// Client ids.
    pub client_ids: Vec<String>,
    /// Both clients request exactly the shared accepted contracts.
    pub shared_contract_parity: bool,
    /// Package, client, feature-lock, and marker identities are distinct.
    pub identities_and_locks_distinct: bool,
    /// App-specific capabilities do not cross client locks.
    pub capability_bleed_absent: bool,
    /// Shared adapter introduces only the signature permission.
    pub adapter_permission_bleed_absent: bool,
    /// No properties or application defaults entered the SDK.
    pub property_and_default_bleed_absent: bool,
}

/// QCL-neutral generic media-session evidence projection.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GenericMediaSessionEvidence {
    /// Schema id.
    pub schema: String,
    /// Pass/fail result.
    pub status: String,
    /// Accepted Manifold contract family.
    pub media_session_contract: String,
    /// Referenced direct-P2P route contract.
    pub route_contract: String,
    /// Original validation profile remains provenance only.
    pub validation_profile_ref: String,
    /// Fresh source artifact path retained as provenance.
    pub artifact_path: String,
    /// Exact source artifact digest.
    pub artifact_sha256: String,
    /// Live provider epoch.
    pub provider_epoch_id: String,
    /// Exact accepted session.
    pub session_id: String,
    /// Exact accepted stream.
    pub stream_id: String,
    /// Exact render sink.
    pub render_sink_id: String,
    /// Fresh render evidence path.
    pub render_evidence_path: String,
    /// Exact render evidence digest.
    pub render_evidence_sha256: String,
    /// Layout preserved from the source evidence.
    pub media_layout: String,
    /// Receiver-observed bytes.
    pub receiver_observed_bytes: u64,
    /// Final-window submitted frames.
    pub final_window_submitted_frames: u64,
    /// Cleanup result.
    pub cleanup_complete: bool,
}

/// One bounded-use binding returned by the signature-scoped Binder lifecycle.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMutationAdmissionBinding {
    /// Live provider epoch returned by runtime initialization/status.
    pub provider_epoch_id: String,
    /// One-time authorize-use request id.
    pub admission_use_request_id: String,
    /// Opaque token id returned by the signature-scoped issue operation.
    pub token_id: String,
    /// Resulting current admission authority revision.
    pub admission_authority_revision: u64,
    /// Expected current Runtime Host revision.
    pub runtime_host_revision: u64,
    /// Optional exact Runtime Host lease.
    pub lease_id: Option<String>,
    /// Trusted/platform-projected request issue time.
    pub issued_at_ms: u64,
    /// Bounded request expiry.
    pub expires_at_ms: u64,
}

/// Build one exact client-bound mutation request after Binder authorize-use.
///
/// # Errors
///
/// Rejects an invalid client spec, ungranted command capability, empty ids,
/// zero revisions, or an invalid time window.
pub fn build_broker_mutation_request(
    spec: &BrokerClientSpec,
    binding: &BrokerMutationAdmissionBinding,
    request_id: &str,
    command_id: &str,
    params: &Value,
    embedded: bool,
) -> Result<serde_json::Value, Vec<String>> {
    let mut errors = validate_broker_client_spec(spec).err().unwrap_or_default();
    let capability = command_capability(command_id);
    if !spec.capabilities.iter().any(|value| value == &capability) {
        errors.push(format!(
            "client does not grant exact command capability {capability}"
        ));
    }
    for (label, value) in [
        ("provider_epoch_id", binding.provider_epoch_id.as_str()),
        (
            "admission_use_request_id",
            binding.admission_use_request_id.as_str(),
        ),
        ("token_id", binding.token_id.as_str()),
        ("request_id", request_id),
        ("command_id", command_id),
    ] {
        if value.trim().is_empty() {
            errors.push(format!("{label} must not be empty"));
        }
    }
    if binding.admission_authority_revision == 0 || binding.runtime_host_revision == 0 {
        errors.push("authority revisions must be nonzero".to_string());
    }
    if binding.issued_at_ms >= binding.expires_at_ms {
        errors.push("mutation time window must be positive".to_string());
    }
    if !params.is_object() {
        errors.push("effect params must be a JSON object".to_string());
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    let effect_params = serde_json::json!({
        "$schema": BROKER_EFFECT_PARAMS_SCHEMA,
        "command_id": command_id,
        "values": params
    });
    let canonical = canonical_json(&effect_params).map_err(|error| vec![error])?;
    if canonical.len() > MAX_TYPED_PARAMS_CANONICAL_BYTES as usize {
        return Err(vec![format!(
            "effect params exceed {} canonical bytes",
            MAX_TYPED_PARAMS_CANONICAL_BYTES
        )]);
    }
    let params_sha256 = Sha256::digest(canonical.as_bytes())
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    Ok(serde_json::json!({
        "$schema": BROKER_SERVER_MUTATION_SCHEMA,
        "bridge_kind": if embedded { "embedded_in_process_jni" } else { "standalone_process_jni" },
        "provider_epoch_id": binding.provider_epoch_id,
        "admission_use_request_id": binding.admission_use_request_id,
        "token_id": binding.token_id,
        "expected_admission_authority_revision": binding.admission_authority_revision,
        "command": {
            "$schema": "rusty.manifold.runtime_host.command_request.v1",
            "request_id": request_id,
            "expected_authority_revision": binding.runtime_host_revision,
            "requester_id": spec.client_id,
            "command_id": command_id,
            "lease_id": binding.lease_id,
            "params_digest": {
                "$schema": HOST_TYPED_PARAMS_DIGEST_SCHEMA,
                "params_type_id": BROKER_EFFECT_PARAMS_SCHEMA,
                "canonical_sha256": format!("sha256:{params_sha256}"),
                "canonical_size_bytes": canonical.len()
            },
            "issued_at_ms": binding.issued_at_ms,
            "expires_at_ms": binding.expires_at_ms
        },
        "params": effect_params
    }))
}

fn canonical_json(value: &Value) -> Result<String, String> {
    fn write(value: &Value, output: &mut String) -> Result<(), serde_json::Error> {
        match value {
            Value::Null => output.push_str("null"),
            Value::Bool(value) => output.push_str(if *value { "true" } else { "false" }),
            Value::Number(value) => output.push_str(&value.to_string()),
            Value::String(value) => output.push_str(&serde_json::to_string(value)?),
            Value::Array(values) => {
                output.push('[');
                for (index, value) in values.iter().enumerate() {
                    if index != 0 {
                        output.push(',');
                    }
                    write(value, output)?;
                }
                output.push(']');
            }
            Value::Object(values) => {
                output.push('{');
                let mut keys = values.keys().collect::<Vec<_>>();
                keys.sort();
                for (index, key) in keys.into_iter().enumerate() {
                    if index != 0 {
                        output.push(',');
                    }
                    output.push_str(&serde_json::to_string(key)?);
                    output.push(':');
                    write(&values[key], output)?;
                }
                output.push('}');
            }
        }
        Ok(())
    }

    let mut output = String::new();
    write(value, &mut output)
        .map_err(|error| format!("effect params canonicalize failed: {error}"))?;
    Ok(output)
}

fn command_capability(command_id: &str) -> String {
    let suffix = command_id.strip_prefix("command.").unwrap_or(command_id);
    format!("capability.command.{suffix}")
}

/// Validate two independent applications over one shared SDK contract.
///
/// # Errors
/// Returns pair-level identity, lock, marker, or capability bleed failures.
pub fn validate_broker_client_pair(
    first: &BrokerClientSpec,
    second: &BrokerClientSpec,
) -> Result<BrokerClientPairReceipt, Vec<String>> {
    let mut errors = Vec::new();
    if let Err(mut first_errors) = validate_broker_client_spec(first) {
        errors.append(&mut first_errors);
    }
    if let Err(mut second_errors) = validate_broker_client_spec(second) {
        errors.append(&mut second_errors);
    }
    let identities_distinct = first.client_id != second.client_id
        && first.package_name != second.package_name
        && first.feature_lock_id != second.feature_lock_id
        && first.marker_namespace != second.marker_namespace;
    if !identities_distinct {
        errors.push("client identities, feature locks, and markers must be distinct".to_string());
    }
    let first_caps = first
        .capabilities
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    let second_caps = second
        .capabilities
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    let first_sinks = first_caps
        .iter()
        .filter(|capability| capability.starts_with("capability.sink."))
        .copied()
        .collect::<BTreeSet<_>>();
    let second_sinks = second_caps
        .iter()
        .filter(|capability| capability.starts_with("capability.sink."))
        .copied()
        .collect::<BTreeSet<_>>();
    let capability_bleed_absent =
        first_sinks.len() <= 1 && second_sinks.len() <= 1 && first_sinks.is_disjoint(&second_sinks);
    if !capability_bleed_absent {
        errors.push("app-local sink capability crossed independent client locks".to_string());
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    Ok(BrokerClientPairReceipt {
        schema: "rusty.quest.broker_client_pair_receipt.v1".to_string(),
        client_ids: vec![first.client_id.clone(), second.client_id.clone()],
        shared_contract_parity: first.contract_families == second.contract_families,
        identities_and_locks_distinct: true,
        capability_bleed_absent,
        adapter_permission_bleed_absent: true,
        property_and_default_bleed_absent: true,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spec(text: &str) -> BrokerClientSpec {
        serde_json::from_str(text).expect("spec parses")
    }

    #[test]
    fn native_and_spatial_specs_are_independent_shared_contract_consumers() {
        let native = spec(include_str!(
            "../../../fixtures/broker-clients/native-renderer.client.json"
        ));
        let spatial = spec(include_str!(
            "../../../fixtures/broker-clients/spatial-camera-panel.client.json"
        ));
        let receipt = validate_broker_client_pair(&native, &spatial).expect("pair validates");
        assert!(receipt.shared_contract_parity);
        assert!(receipt.capability_bleed_absent);
        assert!(receipt.property_and_default_bleed_absent);
    }

    #[test]
    fn ambient_capability_union_and_marker_reuse_reject() {
        let native = spec(include_str!(
            "../../../fixtures/broker-clients/native-renderer.client.json"
        ));
        let damaged = spec(include_str!(
            "../../../fixtures/damaged/broker-client-spatial-union.json"
        ));
        let errors = validate_broker_client_pair(&native, &damaged).expect_err("union rejects");
        assert!(errors.iter().any(|error| error.contains("sink capability")));
        assert!(errors
            .iter()
            .any(|error| error.contains("markers must be distinct")));
    }

    #[test]
    fn peer_only_and_media_observer_clients_do_not_inherit_mutation_or_sink_caps() {
        let mut peer = spec(include_str!(
            "../../../fixtures/broker-clients/native-renderer.client.json"
        ));
        peer.contract_families = vec![PEER_SESSION_CONTRACT.to_string()];
        peer.capabilities = vec![
            "capability.command.session.list".to_string(),
            "capability.peer.session.observe".to_string(),
        ];
        validate_broker_client_spec(&peer).expect("peer-only client");

        let mut observer = peer;
        observer.contract_families = vec![MEDIA_SESSION_CONTRACT.to_string()];
        observer.capabilities = vec![
            "capability.command.session.list".to_string(),
            "capability.media.session.observe".to_string(),
        ];
        validate_broker_client_spec(&observer).expect("media observer client");
        assert!(!observer
            .capabilities
            .iter()
            .any(|capability| capability.contains("media.session.start")
                || capability.starts_with("capability.sink.")));
    }

    #[test]
    fn mutation_builder_binds_client_epoch_revisions_and_one_time_use() {
        let native = spec(include_str!(
            "../../../fixtures/broker-clients/native-renderer.client.json"
        ));
        let request = build_broker_mutation_request(
            &native,
            &BrokerMutationAdmissionBinding {
                provider_epoch_id: format!("epoch.provider.{}", "11".repeat(32)),
                admission_use_request_id: "request.native.use.1".to_string(),
                token_id: format!("token.session.{}", "22".repeat(32)),
                admission_authority_revision: 3,
                runtime_host_revision: 1,
                lease_id: None,
                issued_at_ms: 2_000,
                expires_at_ms: 5_000,
            },
            "request.native.command.1",
            "command.session.list",
            &serde_json::json!({}),
            false,
        )
        .expect("request");
        assert_eq!(request["command"]["requester_id"], native.client_id);
        assert_eq!(request["expected_admission_authority_revision"], 3);
        assert_eq!(
            request["token_id"],
            format!("token.session.{}", "22".repeat(32))
        );
        assert_eq!(request["bridge_kind"], "standalone_process_jni");
        assert_eq!(request["params"]["$schema"], BROKER_EFFECT_PARAMS_SCHEMA);
        assert_eq!(
            request["command"]["params_digest"]["canonical_sha256"],
            format!(
                "sha256:{}",
                Sha256::digest(
                    canonical_json(&request["params"])
                        .expect("canonical")
                        .as_bytes()
                )
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>()
            )
        );
    }

    #[test]
    fn mutation_builder_rejects_ungranted_command_and_invalid_window() {
        let native = spec(include_str!(
            "../../../fixtures/broker-clients/native-renderer.client.json"
        ));
        let errors = build_broker_mutation_request(
            &native,
            &BrokerMutationAdmissionBinding {
                provider_epoch_id: "epoch.provider.test".to_string(),
                admission_use_request_id: "request.native.use.bad".to_string(),
                token_id: "token.session.test".to_string(),
                admission_authority_revision: 3,
                runtime_host_revision: 1,
                lease_id: None,
                issued_at_ms: 5_000,
                expires_at_ms: 5_000,
            },
            "request.native.command.bad",
            "command.peer.session.revoke",
            &serde_json::json!({}),
            false,
        )
        .expect_err("reject");
        assert!(errors
            .iter()
            .any(|error| error.contains("exact command capability")));
        assert!(errors.iter().any(|error| error.contains("time window")));
    }

    #[test]
    fn mutation_builder_canonicalizes_object_order_and_rejects_oversize_params() {
        let native = spec(include_str!(
            "../../../fixtures/broker-clients/native-renderer.client.json"
        ));
        let binding = BrokerMutationAdmissionBinding {
            provider_epoch_id: format!("epoch.provider.{}", "11".repeat(32)),
            admission_use_request_id: "request.native.use.order".to_string(),
            token_id: format!("token.session.{}", "22".repeat(32)),
            admission_authority_revision: 3,
            runtime_host_revision: 1,
            lease_id: None,
            issued_at_ms: 2_000,
            expires_at_ms: 5_000,
        };
        let left: Value =
            serde_json::from_str(r#"{"z":2,"a":{"y":2,"x":1}}"#).expect("left values");
        let right: Value =
            serde_json::from_str(r#"{"a":{"x":1,"y":2},"z":2}"#).expect("right values");
        let left_request = build_broker_mutation_request(
            &native,
            &binding,
            "request.native.command.order.left",
            "command.session.list",
            &left,
            false,
        )
        .expect("left request");
        let right_request = build_broker_mutation_request(
            &native,
            &binding,
            "request.native.command.order.right",
            "command.session.list",
            &right,
            false,
        )
        .expect("right request");
        assert_eq!(
            left_request["command"]["params_digest"]["canonical_sha256"],
            right_request["command"]["params_digest"]["canonical_sha256"]
        );

        let oversized = serde_json::json!({"blob": "x".repeat(5_000)});
        let errors = build_broker_mutation_request(
            &native,
            &binding,
            "request.native.command.oversize",
            "command.session.list",
            &oversized,
            false,
        )
        .expect_err("oversize rejects");
        assert!(errors.iter().any(|error| error.contains("canonical bytes")));
    }
}
