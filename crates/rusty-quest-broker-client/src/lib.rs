//! Shared broker-client specs for independent Quest applications.
//!
//! This crate owns no Binder calls, grants, tokens, sessions, streams, app
//! defaults, or media. Platform clients project these validated specs; Manifold
//! remains admission and accepted peer/media-session authority.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

/// Broker client spec schema.
pub const BROKER_CLIENT_SPEC_SCHEMA: &str = "rusty.quest.broker_client_spec.v1";
/// Generic QCL media receipt fold schema.
pub const GENERIC_MEDIA_RECEIPT_SCHEMA: &str = "rusty.quest.generic_media_session_evidence.v1";
/// Shared peer-session contract family.
pub const PEER_SESSION_CONTRACT: &str = "rusty.manifold.peer.session_descriptor.v1";
/// Shared generic media-session contract family.
pub const MEDIA_SESSION_CONTRACT: &str = "rusty.manifold.media.session_descriptor.v1";
/// Signature permission needed by the Android Binder adapter.
pub const BROKER_ADMISSION_PERMISSION: &str =
    "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION";

/// Exact per-application broker client selection.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerClientSpec {
    /// Schema id.
    pub schema: String,
    /// Stable Manifold client id.
    pub client_id: String,
    /// Exact Android package subject.
    pub package_name: String,
    /// Independent app feature-lock reference.
    pub feature_lock_id: String,
    /// Marker namespace owned by this client only.
    pub marker_namespace: String,
    /// Shared accepted contract families requested through the SDK.
    pub contract_families: Vec<String>,
    /// Exact admitted capabilities.
    pub capabilities: Vec<String>,
    /// Permissions introduced by the broker client adapter itself.
    pub adapter_permissions: Vec<String>,
    /// Broker-client-owned Android property names; must remain empty.
    pub runtime_properties: Vec<String>,
    /// App-specific defaults copied into the shared client; must remain empty.
    pub application_defaults: Vec<String>,
}

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

/// Generic input distilled from a QCL100 product receipt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Qcl100MediaEvidenceInput {
    /// Evidence profile reference only; never runtime API identity.
    pub validation_profile_ref: String,
    /// Accepted media layout.
    pub media_layout: String,
    /// Scoped socket authority.
    pub socket_authority: String,
    /// Receiver-observed bytes.
    pub receiver_observed_bytes: u64,
    /// Final-window submitted frames.
    pub final_window_submitted_frames: u64,
    /// Cleanup result.
    pub cleanup_complete: bool,
    /// Native/package fatal count.
    pub package_fatal_count: u32,
    /// System fatal count.
    pub system_fatal_count: u32,
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
    /// Layout preserved from the source evidence.
    pub media_layout: String,
    /// Receiver-observed bytes.
    pub receiver_observed_bytes: u64,
    /// Final-window submitted frames.
    pub final_window_submitted_frames: u64,
    /// Cleanup result.
    pub cleanup_complete: bool,
}

/// Validate one client spec.
///
/// # Errors
/// Returns messages for identity, exact-lock, contract, permission, property,
/// or app-default bleed.
pub fn validate_broker_client_spec(spec: &BrokerClientSpec) -> Result<(), Vec<String>> {
    let mut errors = Vec::new();
    if spec.schema != BROKER_CLIENT_SPEC_SCHEMA {
        errors.push("unsupported broker client spec schema".to_string());
    }
    for (label, value) in [
        ("client_id", spec.client_id.as_str()),
        ("package_name", spec.package_name.as_str()),
        ("feature_lock_id", spec.feature_lock_id.as_str()),
        ("marker_namespace", spec.marker_namespace.as_str()),
    ] {
        if value.trim().is_empty() {
            errors.push(format!("{label} must not be empty"));
        }
    }
    let required_contracts = BTreeSet::from([PEER_SESSION_CONTRACT, MEDIA_SESSION_CONTRACT]);
    let actual_contracts = spec
        .contract_families
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    if actual_contracts != required_contracts || spec.contract_families.len() != 2 {
        errors.push(
            "client must select exactly the shared peer and media session contracts".to_string(),
        );
    }
    let capabilities = spec
        .capabilities
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    if capabilities.len() != spec.capabilities.len()
        || capabilities.iter().copied().collect::<Vec<_>>()
            != spec
                .capabilities
                .iter()
                .map(String::as_str)
                .collect::<Vec<_>>()
    {
        errors.push("client capabilities must be unique and sorted".to_string());
    }
    for required in [
        "capability.peer.session.observe",
        "capability.media.session.observe",
        "capability.command.session.list",
    ] {
        if !capabilities.contains(required) {
            errors.push(format!("client is missing shared capability {required}"));
        }
    }
    if spec.adapter_permissions != [BROKER_ADMISSION_PERMISSION] {
        errors.push(
            "broker client adapter may introduce only the signature admission permission"
                .to_string(),
        );
    }
    if !spec.runtime_properties.is_empty() || !spec.application_defaults.is_empty() {
        errors.push(
            "broker client spec must not own runtime properties or application defaults"
                .to_string(),
        );
    }
    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
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
    let shared = BTreeSet::from([
        "capability.peer.session.observe",
        "capability.media.session.observe",
        "capability.command.session.list",
    ]);
    let first_specific = first_caps
        .difference(&shared)
        .copied()
        .collect::<BTreeSet<_>>();
    let second_specific = second_caps
        .difference(&shared)
        .copied()
        .collect::<BTreeSet<_>>();
    let capability_bleed_absent = first_specific.len() == 1
        && second_specific.len() == 1
        && first_specific.is_disjoint(&second_specific);
    if !capability_bleed_absent {
        errors.push("each client requires exactly one distinct app capability".to_string());
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

/// Fold QCL100 evidence into generic media-session evidence.
///
/// # Errors
/// Rejects missing media/renderer observations, wrong socket authority,
/// incomplete cleanup, fatals, and unsupported layouts.
pub fn fold_qcl100_media_evidence(
    input: &Qcl100MediaEvidenceInput,
) -> Result<GenericMediaSessionEvidence, Vec<String>> {
    let mut errors = Vec::new();
    if input.validation_profile_ref.trim().is_empty() {
        errors.push("validation profile ref is required".to_string());
    }
    if !matches!(
        input.media_layout.as_str(),
        "separate-eye-streams" | "side-by-side-left-right"
    ) {
        errors.push("unsupported generic media layout".to_string());
    }
    if input.socket_authority != "rusty_direct_p2p_socket_authority" {
        errors.push("wrong socket authority".to_string());
    }
    if input.receiver_observed_bytes == 0 || input.final_window_submitted_frames == 0 {
        errors.push("media and renderer observations are required".to_string());
    }
    if !input.cleanup_complete || input.package_fatal_count != 0 || input.system_fatal_count != 0 {
        errors.push("cleanup/fatal gate failed".to_string());
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    Ok(GenericMediaSessionEvidence {
        schema: GENERIC_MEDIA_RECEIPT_SCHEMA.to_string(),
        status: "pass".to_string(),
        media_session_contract: MEDIA_SESSION_CONTRACT.to_string(),
        route_contract: "rusty.quest.direct_p2p_socket_route.v1".to_string(),
        validation_profile_ref: input.validation_profile_ref.clone(),
        media_layout: input.media_layout.clone(),
        receiver_observed_bytes: input.receiver_observed_bytes,
        final_window_submitted_frames: input.final_window_submitted_frames,
        cleanup_complete: true,
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
        assert!(errors
            .iter()
            .any(|error| error.contains("distinct app capability")));
        assert!(errors
            .iter()
            .any(|error| error.contains("markers must be distinct")));
    }

    #[test]
    fn qcl100_fixture_folds_to_generic_media_evidence() {
        let input: Qcl100MediaEvidenceInput = serde_json::from_str(include_str!(
            "../../../fixtures/broker-clients/qcl100-generic-media.pass.json"
        ))
        .expect("evidence parses");
        let receipt = fold_qcl100_media_evidence(&input).expect("evidence folds");
        assert_eq!(receipt.media_session_contract, MEDIA_SESSION_CONTRACT);
        assert!(!receipt.validation_profile_ref.starts_with("runtime."));
    }
}
