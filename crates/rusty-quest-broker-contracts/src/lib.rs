//! Neutral immutable contracts shared downward by broker authority and clients.
//!
//! This crate owns no filesystem, Android, transport, admission, session,
//! provider-handle, evidence-reduction, or test-support behavior.

use std::{collections::BTreeSet, path::Path};

use rusty_manifold_broker_product::{ManifoldBrokerFeature, ManifoldBrokerProductLock};
use rusty_manifold_media_session::ManifoldMediaSessionProductBinding;
use rusty_quest_media_stream::{MediaStreamOwnerKind, MediaStreamRuntimeProductBinding};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WorkflowFeatureLock {
    #[serde(rename = "$schema")]
    schema_uri: String,
    schema: String,
    project_id: String,
    revision: u64,
    default_activation: String,
    features: Vec<WorkflowFeatureSelection>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WorkflowFeatureSelection {
    feature_id: String,
    module_id: String,
    enabled: bool,
    requested_by: String,
    descriptor: String,
    dependencies: Vec<String>,
    conflicts: Vec<String>,
    permissions: Vec<String>,
    routes: Vec<String>,
    assets: Vec<String>,
    parameter_authorities: Vec<WorkflowParameterAuthority>,
    activation_receipt: WorkflowActivationReceipt,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WorkflowParameterAuthority {
    parameter: String,
    owner: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WorkflowActivationReceipt {
    required: bool,
    schema: String,
    effective_marker: String,
}

/// Broker client spec schema.
pub const BROKER_CLIENT_SPEC_SCHEMA: &str = "rusty.quest.broker_client_spec.v1";
/// Shared peer-session contract family.
pub const PEER_SESSION_CONTRACT: &str = "rusty.manifold.peer.session_descriptor.v1";
/// Shared generic media-session contract family.
pub const MEDIA_SESSION_CONTRACT: &str = "rusty.manifold.media.session_descriptor.v1";
/// Signature permission needed by the Android Binder adapter.
pub const BROKER_ADMISSION_PERMISSION: &str =
    "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION";
/// Legacy lifecycle schema retained for explicit migration/rejection.
pub const BROKER_MEDIA_LIFECYCLE_LOCK_SCHEMA_V1: &str =
    "rusty.quest.broker_media_lifecycle_lock.v1";
/// Lifecycle schema with distinct broker/app locks and outer/inner leases.
pub const BROKER_MEDIA_LIFECYCLE_LOCK_SCHEMA: &str = "rusty.quest.broker_media_lifecycle_lock.v2";
/// Exact packaged client/media closure schema.
pub const BROKER_MEDIA_LIFECYCLE_PACKAGE_SCHEMA: &str =
    "rusty.quest.broker_media_lifecycle_package.v1";

/// Exact per-application broker client selection.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerClientSpec {
    /// Schema id.
    pub schema: String,
    /// Stable Manifold client id.
    pub client_id: String,
    /// Exact Android package subject.
    pub package_name: String,
    /// Exact broker client-lock identity.
    pub feature_lock_id: String,
    /// App-local log marker namespace.
    pub marker_namespace: String,
    /// Accepted contract families requested through the SDK.
    pub contract_families: Vec<String>,
    /// Exact admitted capabilities.
    pub capabilities: Vec<String>,
    /// Permissions introduced by the client adapter.
    pub adapter_permissions: Vec<String>,
    /// Broker-client-owned Android properties; must remain empty.
    pub runtime_properties: Vec<String>,
    /// App defaults copied into the client; must remain empty.
    pub application_defaults: Vec<String>,
}

/// Exact client-local lock over one accepted media binding and render sink.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaLifecycleLock {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact Manifold client id.
    pub client_id: String,
    /// Exact Android package.
    pub package_name: String,
    /// Exact broker client-lock identity.
    pub broker_client_lock_id: String,
    /// App-local log marker namespace.
    pub marker_namespace: String,
    /// Portable Morphospace project identity.
    pub project_id: String,
    /// Sorted broker products allowed to package this closure.
    pub product_ids: Vec<String>,
    /// Exact app feature-lock identity, distinct from the client lock.
    pub app_feature_lock_id: String,
    /// Repo-relative app feature-lock path.
    pub app_feature_lock_path: String,
    /// Canonical app feature-lock fingerprint.
    pub app_feature_lock_fingerprint: String,
    /// Exact app feature-lock bytes digest.
    pub app_feature_lock_sha256: String,
    /// Exact feature-lock revision.
    pub app_feature_lock_revision: u64,
    /// Dotted effective activation marker, distinct from the log namespace.
    pub activation_effective_marker: String,
    /// Repo-relative canonical media-binding path.
    pub media_binding_path: String,
    /// Outer broker Runtime Host lease.
    pub broker_runtime_lease_id: String,
    /// Inner media Runtime Host lease.
    pub media_runtime_lease_id: String,
    /// Exact accepted session.
    pub session_id: String,
    /// Exact accepted stream.
    pub stream_id: String,
    /// App-local render sink.
    pub render_sink_id: String,
    /// Exact sink capability.
    pub render_sink_capability: String,
    /// Exact Quest runtime specification.
    pub runtime_spec_id: String,
    /// Canonical Quest runtime-spec digest.
    pub runtime_spec_canonical_sha256: String,
    /// Canonical Manifold descriptor digest.
    pub manifold_descriptor_canonical_sha256: String,
}

/// Cross-repo canonical media binding document.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaProductBindingDocument {
    /// Canonical Manifold descriptor binding.
    pub manifold: ManifoldMediaSessionProductBinding,
    /// Canonical Quest runtime binding.
    pub quest: MediaStreamRuntimeProductBinding,
}

/// Exact packaged bytes for one independent media client.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaLifecyclePackageBinding {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact accepted broker product-lock JSON bytes.
    pub product_lock_json: String,
    /// Prefixed SHA-256 of exact product-lock bytes.
    pub product_lock_sha256: String,
    /// Exact broker client-lock JSON bytes.
    pub client_lock_json: String,
    /// Prefixed SHA-256 of exact client-lock bytes.
    pub client_lock_sha256: String,
    /// Exact lifecycle-lock JSON bytes.
    pub media_lifecycle_lock_json: String,
    /// Prefixed SHA-256 of exact lifecycle-lock bytes.
    pub media_lifecycle_lock_sha256: String,
    /// Exact enabled app feature-lock JSON bytes.
    pub app_feature_lock_json: String,
    /// Prefixed SHA-256 of exact feature-lock bytes.
    pub app_feature_lock_sha256: String,
    /// Exact canonical media-binding JSON bytes.
    pub media_binding_json: String,
    /// Prefixed SHA-256 of exact media-binding bytes.
    pub media_binding_sha256: String,
}

/// Parsed, validated immutable package closure.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ValidatedBrokerMediaLifecyclePackage {
    /// Exact broker product lock.
    pub product_lock: ManifoldBrokerProductLock,
    /// Exact broker client lock.
    pub client: BrokerClientSpec,
    /// Exact app lifecycle lock.
    pub lifecycle: BrokerMediaLifecycleLock,
    /// Exact cross-repo media binding.
    pub media: BrokerMediaProductBindingDocument,
}

/// Validates one broker client spec without platform or filesystem state.
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
    let actual_contracts = spec
        .contract_families
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    let supported = BTreeSet::from([PEER_SESSION_CONTRACT, MEDIA_SESSION_CONTRACT]);
    if actual_contracts.is_empty()
        || actual_contracts.len() != spec.contract_families.len()
        || !actual_contracts.is_subset(&supported)
    {
        errors.push("client contract selection is empty, duplicated, or unsupported".to_string());
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
    let mut required = BTreeSet::from(["capability.command.session.list"]);
    if actual_contracts.contains(PEER_SESSION_CONTRACT) {
        required.insert("capability.peer.session.observe");
    }
    if actual_contracts.contains(MEDIA_SESSION_CONTRACT) {
        required.insert("capability.media.session.observe");
    }
    let sinks = capabilities
        .iter()
        .filter(|capability| capability.starts_with("capability.sink."))
        .copied()
        .collect::<Vec<_>>();
    if sinks.len() > 1 {
        errors.push("one client lock cannot select multiple app-local media sinks".to_string());
    }
    if !sinks.is_empty() {
        if !actual_contracts.contains(MEDIA_SESSION_CONTRACT) {
            errors.push("app-local sink capability requires media-session contract".to_string());
        }
    }
    for required in required {
        if !capabilities.contains(required) {
            errors.push(format!("client is missing shared capability {required}"));
        }
    }
    if spec.adapter_permissions != [BROKER_ADMISSION_PERMISSION] {
        errors.push("client adapter may introduce only signature admission permission".to_string());
    }
    if !spec.runtime_properties.is_empty() || !spec.application_defaults.is_empty() {
        errors.push("client spec must not own runtime properties/app defaults".to_string());
    }
    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

/// Validates exact bytes and all product/client/app/media semantic joins.
pub fn validate_media_lifecycle_package(
    package: &BrokerMediaLifecyclePackageBinding,
) -> Result<ValidatedBrokerMediaLifecyclePackage, Vec<String>> {
    let mut errors = Vec::new();
    if package.schema_id != BROKER_MEDIA_LIFECYCLE_PACKAGE_SCHEMA {
        errors.push("unsupported media lifecycle package schema".to_string());
    }
    for (label, bytes, digest) in [
        (
            "product lock",
            package.product_lock_json.as_bytes(),
            package.product_lock_sha256.as_str(),
        ),
        (
            "client lock",
            package.client_lock_json.as_bytes(),
            package.client_lock_sha256.as_str(),
        ),
        (
            "media lifecycle lock",
            package.media_lifecycle_lock_json.as_bytes(),
            package.media_lifecycle_lock_sha256.as_str(),
        ),
        (
            "app feature lock",
            package.app_feature_lock_json.as_bytes(),
            package.app_feature_lock_sha256.as_str(),
        ),
        (
            "media binding",
            package.media_binding_json.as_bytes(),
            package.media_binding_sha256.as_str(),
        ),
    ] {
        if sha256(bytes) != digest {
            errors.push(format!("{label} exact bytes digest mismatch"));
        }
    }
    let product = parse(&package.product_lock_json, "product lock", &mut errors);
    let client = parse(&package.client_lock_json, "client lock", &mut errors);
    let lifecycle = parse(
        &package.media_lifecycle_lock_json,
        "media lifecycle lock",
        &mut errors,
    );
    let media = parse(&package.media_binding_json, "media binding", &mut errors);
    if let Some(client) = &client {
        if let Err(mut client_errors) = validate_broker_client_spec(client) {
            errors.append(&mut client_errors);
        }
    }
    if let (Some(product), Some(client), Some(lifecycle), Some(media)) =
        (&product, &client, &lifecycle, &media)
    {
        validate_lifecycle_closure(
            product,
            client,
            lifecycle,
            media,
            package.app_feature_lock_json.as_bytes(),
            &mut errors,
        );
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    Ok(ValidatedBrokerMediaLifecyclePackage {
        product_lock: product.expect("validated product"),
        client: client.expect("validated client"),
        lifecycle: lifecycle.expect("validated lifecycle"),
        media: media.expect("validated media"),
    })
}

fn validate_lifecycle_closure(
    product: &ManifoldBrokerProductLock,
    client: &BrokerClientSpec,
    lock: &BrokerMediaLifecycleLock,
    media: &BrokerMediaProductBindingDocument,
    feature_bytes: &[u8],
    errors: &mut Vec<String>,
) {
    if lock.schema_id != BROKER_MEDIA_LIFECYCLE_LOCK_SCHEMA {
        errors.push("unsupported media lifecycle lock schema".to_string());
    }
    if lock.client_id != client.client_id
        || lock.package_name != client.package_name
        || lock.broker_client_lock_id != client.feature_lock_id
        || lock.marker_namespace != client.marker_namespace
    {
        errors.push("media lifecycle lock copied across client identity".to_string());
    }
    if lock.project_id.trim().is_empty()
        || lock.product_ids.is_empty()
        || !lock.product_ids.windows(2).all(|pair| pair[0] < pair[1])
        || !lock
            .product_ids
            .iter()
            .any(|id| id == product.product_id.as_str())
        || lock.app_feature_lock_id.trim().is_empty()
        || lock.app_feature_lock_id == lock.broker_client_lock_id
        || lock.broker_runtime_lease_id.trim().is_empty()
        || lock.media_runtime_lease_id.trim().is_empty()
        || lock.broker_runtime_lease_id == lock.media_runtime_lease_id
        || !product
            .features
            .contains(&ManifoldBrokerFeature::MediaSession)
        || !product
            .command_ids
            .iter()
            .any(|id| id.as_str() == "command.media.session.start")
        || !product
            .command_ids
            .iter()
            .any(|id| id.as_str() == "command.media.session.stop")
    {
        errors.push("product/project lock does not close generic media lifecycle".to_string());
    }
    for capability in [
        "capability.command.media.session.start",
        "capability.command.media.session.stop",
        lock.render_sink_capability.as_str(),
    ] {
        if !client.capabilities.iter().any(|value| value == capability) {
            errors.push(format!(
                "client lock missing lifecycle capability {capability}"
            ));
        }
    }
    if !lock.render_sink_capability.starts_with("capability.sink.")
        || !valid_relative_path(&lock.app_feature_lock_path)
        || !valid_relative_path(&lock.media_binding_path)
        || sha256(feature_bytes) != lock.app_feature_lock_sha256
        || lock.app_feature_lock_fingerprint != lock.app_feature_lock_sha256
    {
        errors.push("lifecycle path/capability/feature digest invalid".to_string());
    }
    validate_feature_lock(feature_bytes, lock, errors);
    if media.manifold.validate().is_err() || media.quest.validate().is_err() {
        errors.push("canonical media binding invalid".to_string());
        return;
    }
    let descriptor = &media.manifold.descriptor;
    let spec = &media.quest.spec;
    let sink_owner_count = spec
        .owner_selections
        .iter()
        .filter(|selection| {
            selection.owner_kind == MediaStreamOwnerKind::Sink
                && selection.resource_id == lock.render_sink_id
        })
        .count();
    if descriptor.session_id.as_str() != lock.session_id
        || descriptor
            .stream_ids
            .iter()
            .map(|id| id.as_str())
            .collect::<Vec<_>>()
            != [lock.stream_id.as_str()]
        || descriptor
            .sink_ids
            .iter()
            .map(|id| id.as_str())
            .collect::<Vec<_>>()
            != [lock.render_sink_id.as_str()]
        || descriptor.platform_runtime_spec_id.as_str() != lock.runtime_spec_id
        || descriptor.authority_revision.get() != spec.manifold_session_revision
        || media.manifold.descriptor_canonical_sha256 != lock.manifold_descriptor_canonical_sha256
        || spec.runtime_spec_id != lock.runtime_spec_id
        || media.quest.runtime_spec_canonical_sha256 != lock.runtime_spec_canonical_sha256
        || spec.owner_selections.len() != 7
        || sink_owner_count != 1
    {
        errors.push("lifecycle sink/session/stream absent from media closure".to_string());
    }
}

fn validate_feature_lock(bytes: &[u8], lock: &BrokerMediaLifecycleLock, errors: &mut Vec<String>) {
    let Ok(value) = serde_json::from_slice::<WorkflowFeatureLock>(bytes) else {
        errors.push("app feature lock JSON invalid".to_string());
        return;
    };
    let matching = value
        .features
        .iter()
        .filter(|feature| feature.feature_id == "broker-media-client")
        .collect::<Vec<_>>();
    let feature = matching.first().copied();
    let expected_descriptor = "morphospace/project.spec.json#broker-media-client";
    let feature_exact = feature.is_some_and(|feature| {
        feature.module_id == "broker-media-client"
            && feature.enabled
            && feature.requested_by.starts_with("iteration-unit:")
            && feature.descriptor == expected_descriptor
            && feature.dependencies.len() == 1
            && feature.conflicts.is_empty()
            && feature.permissions == [BROKER_ADMISSION_PERMISSION]
            && feature.routes.len() == 2
            && feature.routes[0] == "manifold-media-session"
            && !feature.routes[1].trim().is_empty()
            && feature.assets.is_empty()
            && feature.parameter_authorities.len() == 2
            && feature.parameter_authorities[0].parameter == "stream.session"
            && feature.parameter_authorities[0].owner == "manifold"
            && feature.parameter_authorities[1].parameter == "render.adoption"
            && !feature.parameter_authorities[1].owner.trim().is_empty()
            && feature.activation_receipt.required
            && feature.activation_receipt.schema == "rusty.quest.broker_media_lifecycle_receipt.v1"
            && feature.activation_receipt.effective_marker == lock.activation_effective_marker
    });
    let shell_exact = feature.is_some_and(|feature| {
        feature.dependencies.first().is_some_and(|dependency| {
            value.features.iter().any(|shell| {
                shell.feature_id == *dependency
                    && shell.module_id == *dependency
                    && shell.enabled
                    && shell.requested_by == feature.requested_by
                    && shell.descriptor
                        == format!("morphospace/project.spec.json#{}", shell.feature_id)
                    && shell.dependencies.is_empty()
                    && shell.conflicts.is_empty()
                    && shell.permissions.is_empty()
                    && shell.routes.len() == 1
                    && !shell.routes[0].trim().is_empty()
                    && shell.assets.is_empty()
                    && shell.parameter_authorities.len() == 1
                    && shell.parameter_authorities[0].parameter == "app.composition"
                    && !shell.parameter_authorities[0].owner.trim().is_empty()
                    && shell.activation_receipt.required
                    && !shell.activation_receipt.schema.trim().is_empty()
                    && !shell.activation_receipt.effective_marker.trim().is_empty()
            })
        })
    });
    let unique_features = value
        .features
        .iter()
        .map(|feature| feature.feature_id.as_str())
        .collect::<BTreeSet<_>>()
        .len()
        == value.features.len();
    if value.schema_uri
        != "https://github.com/MesmerPrism/rusty-morphospace-work-environment/schemas/feature-lock.schema.json"
        || value.schema != "rusty.morphospace.workflow.feature_lock.v1"
        || value.project_id != lock.project_id
        || value.revision != lock.app_feature_lock_revision
        || value.default_activation != "disabled"
        || matching.len() != 1
        || !feature_exact
        || !shell_exact
        || !unique_features
    {
        errors.push("app feature lock does not enable exact broker media client".to_string());
    }
}

fn parse<T: for<'de> Deserialize<'de>>(
    value: &str,
    label: &str,
    errors: &mut Vec<String>,
) -> Option<T> {
    match serde_json::from_str(value) {
        Ok(value) => Some(value),
        Err(error) => {
            errors.push(format!("{label} JSON invalid: {error}"));
            None
        }
    }
}

fn sha256(bytes: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(bytes))
}

fn valid_relative_path(value: &str) -> bool {
    !value.trim().is_empty()
        && !Path::new(value).is_absolute()
        && !value.contains(':')
        && !value.split(['/', '\\']).any(|part| part == "..")
}
