//! Exact packaged authority assembly for an embedded duplex Android product.
//!
//! All authority-bearing JSON is supplied by the build. This module neither
//! manufactures grants from fixtures nor changes accepted product/client/media
//! documents. It verifies their exact bytes before assembling and validating
//! the runtime configuration.

use super::packaged_route::{
    decode_and_validate_packaged_route, ExactRouteDocument, PackagedRouteExpectations,
};
use rusty_quest_broker_authority::{
    canonical_runtime_config_sha256, packaged_json_sha256, QuestBrokerAuthorityRuntime,
    QuestBrokerMediaSessionProductBinding, QuestBrokerRuntimeConfig,
    QuestEmbeddedDuplexAuthorityConfig,
};
use rusty_quest_feature_activation::{inspect_feature_lock_v2, FeatureLockV2Effect};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{collections::BTreeSet, fmt};

const RUNTIME_CONFIG_SCHEMA: &str = "rusty.quest.broker.runtime_config.v2";
const ADMISSION_CONFIG_SCHEMA: &str = "rusty.quest.broker.admission_config.v1";
const ADMISSION_SNAPSHOT_SCHEMA: &str = "rusty.manifold.admission.snapshot.v2";
const ADAPTER_CONFIG_SCHEMA: &str = "rusty.manifold.broker.adapter_config.v2";
const CLIENT_LOCK_SCHEMA: &str = "rusty.quest.broker_client_spec.v1";
const MEDIA_LIFECYCLE_SCHEMA: &str = "rusty.quest.broker_media_lifecycle_lock.v2";
const BROKER_ADMISSION_PERMISSION: &str =
    "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION";

/// Exact UTF-8 JSON bytes and their raw lowercase SHA-256.
#[derive(Clone, Copy, Debug)]
pub(crate) struct ExactPackagedJson<'a> {
    pub(crate) json: &'a str,
    pub(crate) sha256: &'a str,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct OwnedExactPackagedJson {
    json: String,
    sha256: String,
}

impl OwnedExactPackagedJson {
    fn borrowed(&self) -> ExactPackagedJson<'_> {
        ExactPackagedJson {
            json: &self.json,
            sha256: &self.sha256,
        }
    }
}

/// JNI transfer envelope. The caller must obtain documents from the
/// build-fixed packaged manifest before submitting this closed request.
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct PackagedConfigRequest {
    package_name: String,
    signing_certificate_sha256: String,
    expected_project_id: String,
    expected_activation_marker: String,
    installed_role_id: String,
    adapter_id: String,
    admission_authority_id: String,
    grant_id: String,
    grant_expires_at_ms: u64,
    lease_expires_at_ms: u64,
    max_token_ttl_ms: u64,
    product_spec: OwnedExactPackagedJson,
    product_lock: OwnedExactPackagedJson,
    client_lock: OwnedExactPackagedJson,
    media_lifecycle_lock: OwnedExactPackagedJson,
    app_feature_lock: OwnedExactPackagedJson,
    route_configuration: OwnedExactPackagedJson,
    packed_profile: OwnedExactPackagedJson,
    media_bindings: [OwnedExactPackagedJson; 2],
    embedded_duplex: QuestEmbeddedDuplexAuthorityConfig,
    validation_epoch_entropy_hex: String,
    validation_wall_unix_ms: i64,
    validation_monotonic_elapsed_ns: u64,
}

/// Parse the closed JNI envelope and return only the already revalidated
/// canonical config, digest, and exact input digest projection.
pub(crate) fn assemble_packaged_config_request_json(request_json: &str) -> Result<String, String> {
    let request: PackagedConfigRequest = serde_json::from_str(request_json)
        .map_err(|_| "invalid packaged configuration request".to_owned())?;
    let product_lock: Value = serde_json::from_str(&request.product_lock.json)
        .map_err(|_| "invalid packaged product lock".to_owned())?;
    let product_id = product_lock
        .get("product_id")
        .and_then(Value::as_str)
        .ok_or("packaged product id absent")?;
    let route = decode_and_validate_packaged_route(
        ExactRouteDocument {
            json: &request.route_configuration.json,
            sha256: &request.route_configuration.sha256,
        },
        ExactRouteDocument {
            json: &request.packed_profile.json,
            sha256: &request.packed_profile.sha256,
        },
        request
            .media_bindings
            .each_ref()
            .map(|binding| ExactRouteDocument {
                json: &binding.json,
                sha256: &binding.sha256,
            }),
        PackagedRouteExpectations {
            route_schema: "rusty.morphovision.embedded_duplex.route_configuration.v1",
            profile_schema: "rusty.morphovision.embedded_duplex.packed_profile.v1",
            product_id,
            package_name: &request.package_name,
            installed_role_id: &request.installed_role_id,
        },
    )?;
    let assembled = assemble_embedded_duplex_packaged_config(EmbeddedDuplexPackagedConfigInput {
        package_name: &request.package_name,
        signing_certificate_sha256: &request.signing_certificate_sha256,
        expected_project_id: &request.expected_project_id,
        expected_activation_marker: &request.expected_activation_marker,
        adapter_id: &request.adapter_id,
        admission_authority_id: &request.admission_authority_id,
        grant_id: &request.grant_id,
        grant_expires_at_ms: request.grant_expires_at_ms,
        lease_expires_at_ms: request.lease_expires_at_ms,
        max_token_ttl_ms: request.max_token_ttl_ms,
        product_spec: request.product_spec.borrowed(),
        product_lock: request.product_lock.borrowed(),
        client_lock: request.client_lock.borrowed(),
        media_lifecycle_lock: request.media_lifecycle_lock.borrowed(),
        app_feature_lock: request.app_feature_lock.borrowed(),
        media_bindings: request
            .media_bindings
            .each_ref()
            .map(OwnedExactPackagedJson::borrowed),
        embedded_duplex: request.embedded_duplex,
        validation_epoch_entropy_hex: &request.validation_epoch_entropy_hex,
        validation_wall_unix_ms: request.validation_wall_unix_ms,
        validation_monotonic_elapsed_ns: request.validation_monotonic_elapsed_ns,
    })
    .map_err(|error| error.to_string())?;
    serde_json::to_string(&json!({
        "$schema": "rusty.quest.embedded_duplex.packaged_config_result.v1",
        "runtime_config_json": assembled.canonical_json,
        "runtime_config_sha256": assembled.canonical_sha256,
        "exact_input_sha256": assembled.exact_input_sha256,
        "packaged_route": {
            "route_configuration_sha256": route.route_configuration_sha256,
            "local_peer_id": route.local_peer().peer_id,
            "remote_peer_id": route.peers[1 - route.installed_peer_index].peer_id,
            "outgoing_runtime_spec_id": route.runtime_spec_ids[route.installed_peer_index],
            "incoming_runtime_spec_id": route.runtime_spec_ids[1 - route.installed_peer_index],
            "max_pair_delta_ns": route.profile.max_pair_delta_ns,
            "local_control": {
                "host": route.local_peer().control.host,
                "port": route.local_peer().control.port,
            },
            "remote_control": {
                "host": route.peers[1 - route.installed_peer_index].control.host,
                "port": route.peers[1 - route.installed_peer_index].control.port,
            }
        }
    }))
    .map_err(|_| "packaged configuration result encoding failed".to_owned())
}

/// Product/operator supplied inputs for one app-local embedded authority.
#[derive(Clone, Debug)]
pub(crate) struct EmbeddedDuplexPackagedConfigInput<'a> {
    pub(crate) package_name: &'a str,
    pub(crate) signing_certificate_sha256: &'a str,
    pub(crate) expected_project_id: &'a str,
    pub(crate) expected_activation_marker: &'a str,
    pub(crate) adapter_id: &'a str,
    pub(crate) admission_authority_id: &'a str,
    pub(crate) grant_id: &'a str,
    pub(crate) grant_expires_at_ms: u64,
    pub(crate) lease_expires_at_ms: u64,
    pub(crate) max_token_ttl_ms: u64,
    pub(crate) product_spec: ExactPackagedJson<'a>,
    pub(crate) product_lock: ExactPackagedJson<'a>,
    pub(crate) client_lock: ExactPackagedJson<'a>,
    pub(crate) media_lifecycle_lock: ExactPackagedJson<'a>,
    pub(crate) app_feature_lock: ExactPackagedJson<'a>,
    pub(crate) media_bindings: [ExactPackagedJson<'a>; 2],
    pub(crate) embedded_duplex: QuestEmbeddedDuplexAuthorityConfig,
    pub(crate) validation_epoch_entropy_hex: &'a str,
    pub(crate) validation_wall_unix_ms: i64,
    pub(crate) validation_monotonic_elapsed_ns: u64,
}

/// Revalidated runtime bytes and their canonical typed digest.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct EmbeddedDuplexPackagedConfig {
    pub(crate) config: QuestBrokerRuntimeConfig,
    pub(crate) canonical_json: String,
    pub(crate) canonical_sha256: String,
    pub(crate) exact_input_sha256: ExactInputDigests,
}

/// Exact input digest projection retained by the packaging caller.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ExactInputDigests {
    pub(crate) product_spec: String,
    pub(crate) product_lock: String,
    pub(crate) client_lock: String,
    pub(crate) media_lifecycle_lock: String,
    pub(crate) app_feature_lock: String,
    pub(crate) media_bindings: [String; 2],
}

/// Closed assembly failure. No authority-bearing input bytes are included.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) enum PackagedConfigError {
    Digest(&'static str),
    Decode(&'static str),
    Binding(&'static str),
    RuntimeValidation(String),
}

impl fmt::Display for PackagedConfigError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Digest(name) => write!(formatter, "exact packaged digest mismatch: {name}"),
            Self::Decode(name) => write!(formatter, "invalid packaged JSON: {name}"),
            Self::Binding(name) => write!(formatter, "packaged authority binding mismatch: {name}"),
            Self::RuntimeValidation(error) => {
                write!(formatter, "packaged runtime validation rejected: {error}")
            }
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct ClientLock {
    schema: String,
    client_id: String,
    package_name: String,
    feature_lock_id: String,
    marker_namespace: String,
    contract_families: Vec<String>,
    capabilities: Vec<String>,
    adapter_permissions: Vec<String>,
    runtime_properties: Vec<String>,
    application_defaults: Vec<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct MediaLifecycleLock {
    #[serde(rename = "$schema")]
    schema: String,
    client_id: String,
    package_name: String,
    broker_client_lock_id: String,
    marker_namespace: String,
    project_id: String,
    product_ids: Vec<String>,
    app_feature_lock_id: String,
    app_feature_lock_path: String,
    app_feature_lock_fingerprint: String,
    app_feature_lock_sha256: String,
    app_feature_lock_revision: u64,
    app_feature_id: String,
    app_feature_module_id: String,
    app_feature_project_revision: u64,
    app_feature_resolver_fingerprint: String,
    app_feature_activation_receipt_schema: String,
    activation_effective_marker: String,
    media_binding_path: String,
    broker_runtime_lease_id: String,
    media_runtime_lease_id: String,
    session_id: String,
    stream_id: String,
    render_sink_id: String,
    render_sink_capability: String,
    runtime_spec_id: String,
    runtime_spec_canonical_sha256: String,
    manifold_descriptor_canonical_sha256: String,
}

/// Builds one exact embedded runtime configuration and proves that the normal
/// runtime constructor accepts it before returning any bytes to the app.
pub(crate) fn assemble_embedded_duplex_packaged_config(
    input: EmbeddedDuplexPackagedConfigInput<'_>,
) -> Result<EmbeddedDuplexPackagedConfig, PackagedConfigError> {
    let exact_input_sha256 = validate_exact_inputs(&input)?;
    validate_scalar_inputs(&input)?;

    let product_lock: Value = decode(input.product_lock, "product lock")?;
    let client: ClientLock = decode(input.client_lock, "client lock")?;
    let lifecycle: MediaLifecycleLock = decode(input.media_lifecycle_lock, "media lifecycle lock")?;
    validate_client_and_lifecycle(&input, &product_lock, &client, &lifecycle)?;

    let mut bindings = input
        .media_bindings
        .iter()
        .map(|exact| {
            decode::<QuestBrokerMediaSessionProductBinding>(*exact, "media binding")
                .map(|binding| (binding, *exact))
        })
        .collect::<Result<Vec<_>, _>>()?;
    bindings.sort_by(|left, right| {
        left.0
            .quest
            .spec
            .runtime_spec_id
            .cmp(&right.0.quest.spec.runtime_spec_id)
    });
    validate_reciprocal_bindings(&bindings, &lifecycle)?;

    let capabilities = derive_capabilities(&product_lock, &client)?;
    let selected_lifecycle_binding = bindings
        .iter()
        .find(|(binding, _)| binding.quest.spec.runtime_spec_id == lifecycle.runtime_spec_id)
        .ok_or(PackagedConfigError::Binding("lifecycle media binding"))?;
    let product_lock_id = required_string(&product_lock, "lock_id", "product lock id")?;
    let product_fingerprint =
        required_string(&product_lock, "spec_fingerprint", "product fingerprint")?;

    let raw_config = json!({
        "$schema": RUNTIME_CONFIG_SCHEMA,
        "bridge_kind": "embedded_in_process_jni",
        "adapter_config": {
            "$schema": ADAPTER_CONFIG_SCHEMA,
            "adapter_id": input.adapter_id,
            "mode": "embedded",
            "product_lock_id": product_lock_id,
            "product_lock_fingerprint": product_fingerprint,
            "product_lock_sha256": format!("sha256:{}", input.product_lock.sha256),
            "authority_host_id": input.embedded_duplex.runtime_host_id.clone(),
            "authority_owner_id": "module.runtime.host"
        },
        "product_lock": product_lock,
        "packaged_authority": {
            "product_spec_json": input.product_spec.json,
            "product_spec_sha256": input.product_spec.sha256,
            "product_lock_json": input.product_lock.json,
            "product_lock_sha256": input.product_lock.sha256,
            "client_locks": [{
                "grant_id": input.grant_id,
                "client_lock_json": input.client_lock.json,
                "client_lock_sha256": input.client_lock.sha256,
                "media_lifecycle_authority": {
                    "media_lifecycle_lock_json": input.media_lifecycle_lock.json,
                    "media_lifecycle_lock_sha256": input.media_lifecycle_lock.sha256,
                    "app_feature_lock_json": input.app_feature_lock.json,
                    "app_feature_lock_sha256": input.app_feature_lock.sha256,
                    "media_binding_json": selected_lifecycle_binding.1.json,
                    "media_binding_sha256": selected_lifecycle_binding.1.sha256
                }
            }]
        },
        "initial_leases": [{
            "lease_id": &lifecycle.broker_runtime_lease_id,
            "scope": "lease.media.session",
            "holder_id": &client.client_id,
            "expires_at_ms": input.lease_expires_at_ms
        }],
        "admission": {
            "$schema": ADMISSION_CONFIG_SCHEMA,
            "snapshot": {
                "$schema": ADMISSION_SNAPSHOT_SCHEMA,
                "authority_id": input.admission_authority_id,
                "authority_revision": 1,
                "grants": [{
                    "grant_id": input.grant_id,
                    "client_lock_id": &client.feature_lock_id,
                    "client_lock_fingerprint": format!("sha256:{}", input.client_lock.sha256),
                    "identity": {
                        "client_id": &client.client_id,
                        "platform_subject": input.package_name,
                        "signing_fingerprint": format!("sha256:{}", input.signing_certificate_sha256)
                    },
                    "capabilities": capabilities,
                    "expires_at_ms": input.grant_expires_at_ms,
                    "revoked": false
                }],
                "active_tokens": [],
                "revoked_token_ids": [],
                "consumed_request_ids": [],
                "consumed_use_request_ids": [],
                "reviewed_sweep_ids": [],
                "audit_events": [],
                "max_token_ttl_ms": input.max_token_ttl_ms
            }
        },
        "media_sessions": bindings.into_iter().map(|entry| entry.0).collect::<Vec<_>>(),
        "embedded_duplex": input.embedded_duplex.clone()
    });
    let config: QuestBrokerRuntimeConfig = serde_json::from_value(raw_config)
        .map_err(|_| PackagedConfigError::Decode("assembled runtime config"))?;
    let canonical_json = serde_json::to_string(&config)
        .map_err(|_| PackagedConfigError::Decode("assembled runtime config"))?;
    let canonical_sha256 = canonical_runtime_config_sha256(&canonical_json)
        .map_err(|error| PackagedConfigError::RuntimeValidation(error.to_string()))?;
    QuestBrokerAuthorityRuntime::from_config(
        config.clone(),
        input.validation_epoch_entropy_hex,
        input.validation_wall_unix_ms,
        input.validation_monotonic_elapsed_ns,
    )
    .map_err(|error| PackagedConfigError::RuntimeValidation(error.to_string()))?;
    Ok(EmbeddedDuplexPackagedConfig {
        config,
        canonical_json,
        canonical_sha256,
        exact_input_sha256,
    })
}

fn validate_exact_inputs(
    input: &EmbeddedDuplexPackagedConfigInput<'_>,
) -> Result<ExactInputDigests, PackagedConfigError> {
    let named = [
        ("product spec", input.product_spec),
        ("product lock", input.product_lock),
        ("client lock", input.client_lock),
        ("media lifecycle lock", input.media_lifecycle_lock),
        ("app feature lock", input.app_feature_lock),
        ("media binding 0", input.media_bindings[0]),
        ("media binding 1", input.media_bindings[1]),
    ];
    for (name, exact) in named {
        if !is_raw_sha256(exact.sha256) || packaged_json_sha256(exact.json) != exact.sha256 {
            return Err(PackagedConfigError::Digest(name));
        }
    }
    Ok(ExactInputDigests {
        product_spec: input.product_spec.sha256.to_owned(),
        product_lock: input.product_lock.sha256.to_owned(),
        client_lock: input.client_lock.sha256.to_owned(),
        media_lifecycle_lock: input.media_lifecycle_lock.sha256.to_owned(),
        app_feature_lock: input.app_feature_lock.sha256.to_owned(),
        media_bindings: [
            input.media_bindings[0].sha256.to_owned(),
            input.media_bindings[1].sha256.to_owned(),
        ],
    })
}

fn validate_scalar_inputs(
    input: &EmbeddedDuplexPackagedConfigInput<'_>,
) -> Result<(), PackagedConfigError> {
    if input.package_name.trim().is_empty()
        || !is_raw_sha256(input.signing_certificate_sha256)
        || input.grant_expires_at_ms
            <= u64::try_from(input.validation_wall_unix_ms).unwrap_or(u64::MAX)
        || input.lease_expires_at_ms
            <= u64::try_from(input.validation_wall_unix_ms).unwrap_or(u64::MAX)
        || input.max_token_ttl_ms == 0
    {
        return Err(PackagedConfigError::Binding("runtime scalar inputs"));
    }
    Ok(())
}

fn validate_client_and_lifecycle(
    input: &EmbeddedDuplexPackagedConfigInput<'_>,
    product_lock: &Value,
    client: &ClientLock,
    lifecycle: &MediaLifecycleLock,
) -> Result<(), PackagedConfigError> {
    let product_id = required_string(product_lock, "product_id", "product id")?;
    if client.schema != CLIENT_LOCK_SCHEMA
        || client.package_name != input.package_name
        || client.adapter_permissions.as_slice() != [BROKER_ADMISSION_PERMISSION]
        || !client.runtime_properties.is_empty()
        || !client.application_defaults.is_empty()
        || !strictly_sorted(&client.capabilities)
        || !strictly_sorted(&client.contract_families)
        || lifecycle.schema != MEDIA_LIFECYCLE_SCHEMA
        || lifecycle.client_id != client.client_id
        || lifecycle.package_name != client.package_name
        || lifecycle.broker_client_lock_id != client.feature_lock_id
        || lifecycle.marker_namespace != client.marker_namespace
        || lifecycle.project_id != input.expected_project_id
        || !lifecycle
            .product_ids
            .iter()
            .any(|value| value == product_id)
        || lifecycle.activation_effective_marker != input.expected_activation_marker
        || lifecycle.app_feature_lock_fingerprint
            != format!("sha256:{}", input.app_feature_lock.sha256)
        || lifecycle.app_feature_lock_sha256 != format!("sha256:{}", input.app_feature_lock.sha256)
        || lifecycle.app_feature_lock_id.trim().is_empty()
        || lifecycle.app_feature_lock_path.trim().is_empty()
        || lifecycle.app_feature_lock_revision == 0
        || lifecycle.app_feature_id.trim().is_empty()
        || lifecycle.app_feature_module_id.trim().is_empty()
        || lifecycle.app_feature_project_revision == 0
        || lifecycle
            .app_feature_activation_receipt_schema
            .trim()
            .is_empty()
        || lifecycle.media_binding_path.trim().is_empty()
        || lifecycle.broker_runtime_lease_id.trim().is_empty()
        || lifecycle.media_runtime_lease_id.trim().is_empty()
        || lifecycle.stream_id.trim().is_empty()
        || lifecycle.render_sink_id.trim().is_empty()
        || lifecycle.render_sink_capability.trim().is_empty()
        || !is_prefixed_sha256(&lifecycle.runtime_spec_canonical_sha256)
        || !is_prefixed_sha256(&lifecycle.manifold_descriptor_canonical_sha256)
    {
        return Err(PackagedConfigError::Binding("client/media lifecycle"));
    }
    validate_feature_lock(input, lifecycle)
}

fn validate_feature_lock(
    input: &EmbeddedDuplexPackagedConfigInput<'_>,
    lifecycle: &MediaLifecycleLock,
) -> Result<(), PackagedConfigError> {
    let inspected = inspect_feature_lock_v2(
        input.app_feature_lock.json,
        input.app_feature_lock.sha256,
        &lifecycle.app_feature_id,
    )
    .map_err(|_| PackagedConfigError::Binding("app feature lock v2 inspection"))?;
    let required_effects = [
        (FeatureLockV2Effect::Command, "command.media.session.start"),
        (FeatureLockV2Effect::Command, "command.media.session.stop"),
        (FeatureLockV2Effect::Stream, "stream.media.video"),
    ];
    if inspected.generation != 2
        || inspected.project_id != input.expected_project_id
        || inspected.project_id != lifecycle.project_id
        || inspected.project_revision != lifecycle.app_feature_project_revision
        || inspected.lock_revision != lifecycle.app_feature_lock_revision
        || inspected.feature_id != lifecycle.app_feature_id
        || inspected.module_id != lifecycle.app_feature_module_id
        || inspected.receipt_schema != lifecycle.app_feature_activation_receipt_schema
        || inspected.effective_marker != lifecycle.activation_effective_marker
        || lifecycle.app_feature_resolver_fingerprint
            != format!("sha256:{}", inspected.resolver_fingerprint)
        || required_effects.iter().any(|(kind, value)| {
            !inspected.selected_has_effect(*kind, value)
                || !inspected.union_has_effect(*kind, value)
        })
    {
        return Err(PackagedConfigError::Binding(
            "app feature v2 lifecycle/effect join",
        ));
    }
    Ok(())
}

fn validate_reciprocal_bindings(
    bindings: &[(QuestBrokerMediaSessionProductBinding, ExactPackagedJson<'_>)],
    lifecycle: &MediaLifecycleLock,
) -> Result<(), PackagedConfigError> {
    if bindings.len() != 2
        || bindings[0].0.quest.spec.runtime_spec_id == bindings[1].0.quest.spec.runtime_spec_id
        || bindings[0].0.manifold.descriptor.session_id
            == bindings[1].0.manifold.descriptor.session_id
    {
        return Err(PackagedConfigError::Binding("two distinct media bindings"));
    }
    let endpoints = bindings
        .iter()
        .map(|(binding, _)| {
            let value = serde_json::to_value(binding)
                .map_err(|_| PackagedConfigError::Decode("media binding"))?;
            let lanes = value
                .pointer("/quest/spec/plan/lanes")
                .and_then(Value::as_array)
                .ok_or(PackagedConfigError::Binding("media lane"))?;
            if lanes.len() != 1 {
                return Err(PackagedConfigError::Binding("single media lane"));
            }
            if lanes[0].get("direction").and_then(Value::as_str) != Some("outgoing")
                || lanes[0]
                    .pointer("/transport/transport_kind")
                    .and_then(Value::as_str)
                    != Some("lan_tcp")
                || lanes[0]
                    .pointer("/transport/relay_required")
                    .and_then(Value::as_bool)
                    != Some(false)
                || lanes[0]
                    .get("receiver_first_required")
                    .and_then(Value::as_bool)
                    != Some(true)
            {
                return Err(PackagedConfigError::Binding("media lane policy"));
            }
            let source = required_string(&lanes[0], "source_device_id", "media source device")?;
            let sink = required_string(&lanes[0], "sink_device_id", "media sink device")?;
            if source == sink {
                return Err(PackagedConfigError::Binding("distinct media peers"));
            }
            Ok((source.to_owned(), sink.to_owned()))
        })
        .collect::<Result<Vec<_>, _>>()?;
    if endpoints[0].0 != endpoints[1].1 || endpoints[0].1 != endpoints[1].0 {
        return Err(PackagedConfigError::Binding("reciprocal media directions"));
    }
    let lifecycle_matches = bindings
        .iter()
        .filter(|(binding, _)| {
            binding.quest.spec.runtime_spec_id == lifecycle.runtime_spec_id
                && binding.quest.spec.plan.session_id == lifecycle.session_id
                && binding.manifold.descriptor.session_id.as_str() == lifecycle.session_id
                && binding.quest.runtime_spec_canonical_sha256
                    == lifecycle.runtime_spec_canonical_sha256
                && binding.manifold.descriptor_canonical_sha256
                    == lifecycle.manifold_descriptor_canonical_sha256
        })
        .count();
    if lifecycle_matches != 1 {
        return Err(PackagedConfigError::Binding("lifecycle runtime identity"));
    }
    Ok(())
}

fn derive_capabilities(
    product_lock: &Value,
    client: &ClientLock,
) -> Result<Vec<String>, PackagedConfigError> {
    let commands = string_array(product_lock, "command_ids", "product commands")?;
    let features = string_array(product_lock, "features", "product features")?;
    let streams = string_array(product_lock, "stream_ids", "product streams")?;
    let command_capabilities = commands
        .into_iter()
        .map(|command| format!("capability.{command}"))
        .collect::<BTreeSet<_>>();
    let media = features.contains(&"media_session");
    let hub = features.contains(&"connection_hub");
    let peer = features.contains(&"direct_p2p")
        || features.contains(&"ble_rendezvous")
        || command_capabilities.contains("capability.command.peer.status.get")
        || streams.contains(&"stream.peer.status");
    Ok(client
        .capabilities
        .iter()
        .filter(|capability| {
            command_capabilities.contains(*capability)
                || (hub && capability.as_str() == "capability.connection_hub.provider.register")
                || (media
                    && (capability.as_str() == "capability.media.session.observe"
                        || capability.starts_with("capability.sink.")))
                || (peer && capability.as_str() == "capability.peer.session.observe")
        })
        .cloned()
        .collect())
}

fn decode<T: for<'de> Deserialize<'de>>(
    exact: ExactPackagedJson<'_>,
    name: &'static str,
) -> Result<T, PackagedConfigError> {
    serde_json::from_str(exact.json).map_err(|_| PackagedConfigError::Decode(name))
}

fn required_string<'a>(
    value: &'a Value,
    field: &str,
    name: &'static str,
) -> Result<&'a str, PackagedConfigError> {
    value
        .get(field)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or(PackagedConfigError::Binding(name))
}

fn string_array<'a>(
    value: &'a Value,
    field: &str,
    name: &'static str,
) -> Result<Vec<&'a str>, PackagedConfigError> {
    value
        .get(field)
        .and_then(Value::as_array)
        .ok_or(PackagedConfigError::Binding(name))?
        .iter()
        .map(|value| value.as_str().ok_or(PackagedConfigError::Binding(name)))
        .collect()
}

fn strictly_sorted(values: &[String]) -> bool {
    values.windows(2).all(|pair| pair[0] < pair[1])
}

fn is_raw_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn is_prefixed_sha256(value: &str) -> bool {
    value.strip_prefix("sha256:").map_or(false, is_raw_sha256)
}
