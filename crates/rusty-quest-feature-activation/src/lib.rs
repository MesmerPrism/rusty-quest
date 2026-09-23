//! Generic closed-world feature-lock activation for Quest adapter facades.
//!
//! This crate owns only lock parsing, exact-byte digest binding, runtime-input
//! comparison, rejection vocabulary, and low-rate marker projection. Adapter
//! receipt schemas, marker namespaces, selected profiles, and effects remain
//! in the module-specific facade that supplies [`LockBoundActivationPolicy`].

use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};

/// Schema shared by applied and rejected lock-bound activation decisions.
pub const LOCK_BOUND_ACTIVATION_SCHEMA_ID: &str = "rusty.quest.lock_bound_activation.v1";
const WORKFLOW_FEATURE_LOCK_SCHEMA_ID: &str = "rusty.morphospace.workflow.feature_lock.v1";
const MAX_LOCK_BYTES: usize = 256 * 1024;
const MAX_FEATURES: usize = 256;
const MAX_LIST_ITEMS: usize = 128;
const MAX_IDENTITY_BYTES: usize = 256;
const MAX_DESCRIPTOR_BYTES: usize = 512;

/// Module-owned values that specialize the generic activation engine.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LockBoundActivationPolicy<'a> {
    /// Exact `requested_by` value selected by the module's conformance lock.
    pub requested_by: &'a str,
    /// Exact owner receipt schema required by the selected feature.
    pub receipt_schema: &'a str,
    /// Exact owner effective-marker namespace required by the selected feature.
    pub effective_marker: &'a str,
}

/// App-owned runtime request that must match the selected project lock exactly.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LockBoundActivationRuntimeInput {
    /// Whether the runtime profile explicitly requests activation.
    pub enabled: bool,
    /// App-approved runtime profile or explicit adapter-input identifier.
    pub profile_id: String,
    /// Project identity carried by the runtime input.
    pub project_id: String,
    /// Feature identity carried by the runtime input.
    pub feature_id: String,
    /// Exact conformance-lock revision carried by the runtime input.
    pub lock_revision: u64,
    /// Exact SHA-256 of the selected conformance-lock bytes.
    pub lock_sha256: String,
}

/// Applied or rejected runtime state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LockBoundActivationState {
    /// The selected lock and runtime input matched exactly.
    Applied,
    /// Activation was rejected before adapter effects.
    Rejected,
}

impl LockBoundActivationState {
    const fn marker_token(self) -> &'static str {
        match self {
            Self::Applied => "applied",
            Self::Rejected => "rejected",
        }
    }
}

/// Stable fail-closed reason for a rejected activation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LockBoundActivationRejection {
    /// The app- or module-owned expectation was malformed.
    InvalidExpectation,
    /// The lock was not valid JSON with the supported schema and policy.
    InvalidLock,
    /// The lock's dependency/conflict/module closure was invalid.
    InvalidFeatureClosure,
    /// The supplied lock was not the app-owned accepted lock revision.
    UnacceptedLock,
    /// The lock belonged to another project.
    ProjectMismatch,
    /// The requested feature was missing or ambiguous.
    FeatureMismatch,
    /// The requested feature was bound to another module.
    ModuleMismatch,
    /// The requested feature was disabled in the supplied lock.
    FeatureNotSelected,
    /// The feature was not selected by the module's conformance profile.
    InvalidSelection,
    /// The feature's effective-marker contract drifted.
    EffectiveMarkerMismatch,
    /// The runtime input did not explicitly request activation.
    RuntimeInputDisabled,
    /// The runtime input was not the app-approved profile/input.
    RuntimeProfileMismatch,
    /// The runtime input carried another project identity.
    RuntimeProjectMismatch,
    /// The runtime input carried another feature identity.
    RuntimeFeatureMismatch,
    /// The runtime input carried a stale or future lock revision.
    RuntimeRevisionMismatch,
    /// The runtime input did not carry the exact selected-lock fingerprint.
    RuntimeDigestMismatch,
}

impl LockBoundActivationRejection {
    /// Stable marker token.
    #[must_use]
    pub const fn marker_token(self) -> &'static str {
        match self {
            Self::InvalidExpectation => "activation-expectation-invalid",
            Self::InvalidLock => "invalid-lock",
            Self::InvalidFeatureClosure => "lock-feature-closure-invalid",
            Self::UnacceptedLock => "lock-not-accepted",
            Self::ProjectMismatch => "lock-project-mismatch",
            Self::FeatureMismatch => "lock-feature-mismatch",
            Self::ModuleMismatch => "lock-module-mismatch",
            Self::FeatureNotSelected => "lock-feature-not-selected",
            Self::InvalidSelection => "lock-selection-invalid",
            Self::EffectiveMarkerMismatch => "lock-effective-marker-mismatch",
            Self::RuntimeInputDisabled => "runtime-input-disabled",
            Self::RuntimeProfileMismatch => "runtime-profile-mismatch",
            Self::RuntimeProjectMismatch => "runtime-project-mismatch",
            Self::RuntimeFeatureMismatch => "runtime-feature-mismatch",
            Self::RuntimeRevisionMismatch => "runtime-revision-mismatch",
            Self::RuntimeDigestMismatch => "runtime-digest-mismatch",
        }
    }
}

/// Typed lock-bound decision used by app-local marker and effect gates.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LockBoundActivationDecision {
    /// Decision schema.
    schema: &'static str,
    /// Applied or rejected state.
    state: LockBoundActivationState,
    /// Project id parsed from the lock, or expected id when parsing failed.
    project_id: String,
    /// Feature id requested by the app.
    feature_id: String,
    /// Revision parsed from the selected lock.
    lock_revision: u64,
    /// SHA-256 of the exact supplied lock bytes.
    lock_sha256: String,
    /// Runtime profile/input identifier supplied by the app.
    runtime_profile_id: String,
    /// Stable rejection reason; absent only for an applied decision.
    rejection: Option<LockBoundActivationRejection>,
}

impl LockBoundActivationDecision {
    /// Decision schema.
    #[must_use]
    pub const fn schema(&self) -> &'static str {
        self.schema
    }

    /// Applied or rejected state.
    #[must_use]
    pub const fn state(&self) -> LockBoundActivationState {
        self.state
    }

    /// Project identity carried by the selected lock.
    #[must_use]
    pub fn project_id(&self) -> &str {
        &self.project_id
    }

    /// Feature identity requested by the consumer.
    #[must_use]
    pub fn feature_id(&self) -> &str {
        &self.feature_id
    }

    /// Selected lock revision.
    #[must_use]
    pub const fn lock_revision(&self) -> u64 {
        self.lock_revision
    }

    /// SHA-256 of the exact selected lock bytes.
    #[must_use]
    pub fn lock_sha256(&self) -> &str {
        &self.lock_sha256
    }

    /// Runtime profile supplied by the app.
    #[must_use]
    pub fn runtime_profile_id(&self) -> &str {
        &self.runtime_profile_id
    }

    /// Stable rejection, absent only for an applied decision.
    #[must_use]
    pub const fn rejection(&self) -> Option<LockBoundActivationRejection> {
        self.rejection
    }

    /// Whether module marker and data effects may proceed.
    #[must_use]
    pub fn is_applied(&self) -> bool {
        self.state == LockBoundActivationState::Applied && self.rejection.is_none()
    }

    /// Low-rate marker fields shared by module-specific facades.
    #[must_use]
    pub fn marker_fields(&self) -> String {
        format!(
            "lockBindingSchema={} activationState={} projectId={} featureId={} conformanceLockRevision={} conformanceLockSha256={} runtimeProfileId={} activationRejectReason={}",
            self.schema,
            self.state.marker_token(),
            marker_token(&self.project_id),
            marker_token(&self.feature_id),
            self.lock_revision,
            self.lock_sha256,
            marker_token(&self.runtime_profile_id),
            self.rejection
                .map_or("none", LockBoundActivationRejection::marker_token),
        )
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WorkflowFeatureLock {
    #[serde(rename = "$schema", default)]
    _schema_uri: Option<String>,
    schema: String,
    project_id: String,
    revision: u64,
    default_activation: String,
    features: Vec<WorkflowFeature>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WorkflowFeature {
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

/// Resolve an exact project lock and app-approved runtime input.
///
/// The SHA-256 covers `lock_json` exactly as supplied. The full v1 lock tree is
/// parsed with unknown-field denial so an application cannot smuggle policy
/// outside the portable schema while retaining a digest-valid projection.
#[must_use]
pub fn resolve_lock_bound_activation(
    lock_json: &str,
    accepted_lock_sha256: &str,
    expected_project_id: &str,
    expected_feature_id: &str,
    expected_module_id: &str,
    accepted_profile_id: &str,
    policy: LockBoundActivationPolicy<'_>,
    runtime_input: &LockBoundActivationRuntimeInput,
) -> LockBoundActivationDecision {
    let lock_sha256 = sha256_hex(lock_json.as_bytes());
    if lock_json.len() > MAX_LOCK_BYTES
        || !valid_sha256(accepted_lock_sha256)
        || !valid_id(expected_project_id)
        || !valid_id(expected_feature_id)
        || !valid_id(expected_module_id)
        || !valid_selector(accepted_profile_id)
        || !valid_selector(policy.requested_by)
        || !valid_dotted_id(policy.receipt_schema)
        || !valid_dotted_id(policy.effective_marker)
    {
        return rejected(
            expected_project_id,
            expected_feature_id,
            0,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::InvalidExpectation,
        );
    }
    let Ok(lock) = serde_json::from_str::<WorkflowFeatureLock>(lock_json) else {
        return rejected(
            expected_project_id,
            expected_feature_id,
            0,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::InvalidLock,
        );
    };
    if lock.schema != WORKFLOW_FEATURE_LOCK_SCHEMA_ID
        || lock.default_activation != "disabled"
        || lock.revision == 0
    {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::InvalidLock,
        );
    }
    if !valid_feature_lock_closure(&lock) {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::InvalidFeatureClosure,
        );
    }
    if lock.project_id != expected_project_id {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::ProjectMismatch,
        );
    }
    let matches = lock
        .features
        .iter()
        .filter(|feature| feature.feature_id == expected_feature_id)
        .collect::<Vec<_>>();
    if matches.len() != 1 {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::FeatureMismatch,
        );
    }
    let feature = matches[0];
    if feature.module_id != expected_module_id {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::ModuleMismatch,
        );
    }
    if !feature.enabled {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::FeatureNotSelected,
        );
    }
    if feature.requested_by != policy.requested_by {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::InvalidSelection,
        );
    }
    if !feature.activation_receipt.required
        || feature.activation_receipt.schema != policy.receipt_schema
        || feature.activation_receipt.effective_marker != policy.effective_marker
    {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::EffectiveMarkerMismatch,
        );
    }
    if !accepted_lock_sha256.eq_ignore_ascii_case(&lock_sha256) {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::UnacceptedLock,
        );
    }
    if !runtime_input.enabled {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::RuntimeInputDisabled,
        );
    }
    if runtime_input.profile_id != accepted_profile_id {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::RuntimeProfileMismatch,
        );
    }
    if runtime_input.project_id != lock.project_id {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::RuntimeProjectMismatch,
        );
    }
    if runtime_input.feature_id != expected_feature_id {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::RuntimeFeatureMismatch,
        );
    }
    if runtime_input.lock_revision != lock.revision {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::RuntimeRevisionMismatch,
        );
    }
    if !valid_sha256(&runtime_input.lock_sha256)
        || !runtime_input.lock_sha256.eq_ignore_ascii_case(&lock_sha256)
    {
        return rejected(
            &lock.project_id,
            expected_feature_id,
            lock.revision,
            lock_sha256,
            runtime_input,
            LockBoundActivationRejection::RuntimeDigestMismatch,
        );
    }

    LockBoundActivationDecision {
        schema: LOCK_BOUND_ACTIVATION_SCHEMA_ID,
        state: LockBoundActivationState::Applied,
        project_id: lock.project_id,
        feature_id: expected_feature_id.to_owned(),
        lock_revision: lock.revision,
        lock_sha256: accepted_lock_sha256.to_owned(),
        runtime_profile_id: runtime_input.profile_id.clone(),
        rejection: None,
    }
}

fn rejected(
    project_id: &str,
    feature_id: &str,
    lock_revision: u64,
    lock_sha256: String,
    runtime_input: &LockBoundActivationRuntimeInput,
    rejection: LockBoundActivationRejection,
) -> LockBoundActivationDecision {
    LockBoundActivationDecision {
        schema: LOCK_BOUND_ACTIVATION_SCHEMA_ID,
        state: LockBoundActivationState::Rejected,
        project_id: project_id.to_owned(),
        feature_id: feature_id.to_owned(),
        lock_revision,
        lock_sha256,
        runtime_profile_id: runtime_input.profile_id.clone(),
        rejection: Some(rejection),
    }
}

fn valid_feature_lock_closure(lock: &WorkflowFeatureLock) -> bool {
    if !valid_id(&lock.project_id)
        || lock.features.is_empty()
        || lock.features.len() > MAX_FEATURES
        || lock
            ._schema_uri
            .as_deref()
            .is_some_and(|value| !valid_bounded_string(value, MAX_DESCRIPTOR_BYTES, true))
    {
        return false;
    }

    let mut feature_index = HashMap::with_capacity(lock.features.len());
    let mut module_index = HashMap::with_capacity(lock.features.len());
    for (index, feature) in lock.features.iter().enumerate() {
        if !valid_id(&feature.feature_id)
            || !valid_id(&feature.module_id)
            || feature_index
                .insert(feature.feature_id.as_str(), index)
                .is_some()
            || module_index
                .insert(feature.module_id.as_str(), index)
                .is_some()
            || !valid_bounded_string(&feature.descriptor, MAX_DESCRIPTOR_BYTES, !feature.enabled)
            || (feature.enabled && !valid_selector(&feature.requested_by))
            || (!feature.requested_by.is_empty() && !valid_selector(&feature.requested_by))
            || !valid_id_list(&feature.dependencies)
            || !valid_id_list(&feature.conflicts)
            || !valid_value_list(&feature.permissions)
            || !valid_value_list(&feature.routes)
            || !valid_value_list(&feature.assets)
            || !valid_dotted_id(&feature.activation_receipt.schema)
            || !valid_dotted_id(&feature.activation_receipt.effective_marker)
            || feature.parameter_authorities.len() > MAX_LIST_ITEMS
        {
            return false;
        }
        let mut parameters = HashSet::with_capacity(feature.parameter_authorities.len());
        if feature.parameter_authorities.iter().any(|authority| {
            !valid_dotted_id(&authority.parameter)
                || !valid_id(&authority.owner)
                || !parameters.insert(authority.parameter.as_str())
        }) {
            return false;
        }
    }

    for feature in &lock.features {
        if feature.dependencies.iter().any(|dependency| {
            dependency == &feature.module_id
                || module_index
                    .get(dependency.as_str())
                    .is_none_or(|index| feature.enabled && !lock.features[*index].enabled)
        }) || feature.conflicts.iter().any(|conflict| {
            conflict == &feature.feature_id
                || feature_index
                    .get(conflict.as_str())
                    .is_some_and(|index| feature.enabled && lock.features[*index].enabled)
        }) {
            return false;
        }
    }

    dependencies_are_acyclic(&lock.features, &module_index)
}

fn dependencies_are_acyclic(
    features: &[WorkflowFeature],
    module_index: &HashMap<&str, usize>,
) -> bool {
    fn visit(
        index: usize,
        features: &[WorkflowFeature],
        module_index: &HashMap<&str, usize>,
        states: &mut [u8],
    ) -> bool {
        match states[index] {
            1 => return false,
            2 => return true,
            _ => {}
        }
        states[index] = 1;
        for dependency in &features[index].dependencies {
            let Some(dependency_index) = module_index.get(dependency.as_str()).copied() else {
                return false;
            };
            if !visit(dependency_index, features, module_index, states) {
                return false;
            }
        }
        states[index] = 2;
        true
    }

    let mut states = vec![0_u8; features.len()];
    (0..features.len()).all(|index| visit(index, features, module_index, &mut states))
}

fn valid_id_list(values: &[String]) -> bool {
    values.len() <= MAX_LIST_ITEMS
        && values.iter().all(|value| valid_id(value))
        && values.iter().collect::<HashSet<_>>().len() == values.len()
}

fn valid_value_list(values: &[String]) -> bool {
    values.len() <= MAX_LIST_ITEMS
        && values
            .iter()
            .all(|value| valid_bounded_string(value, MAX_DESCRIPTOR_BYTES, false))
        && values.iter().collect::<HashSet<_>>().len() == values.len()
}

fn valid_id(value: &str) -> bool {
    (2..=64).contains(&value.len())
        && value
            .bytes()
            .next()
            .is_some_and(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

fn valid_dotted_id(value: &str) -> bool {
    (3..=128).contains(&value.len())
        && value
            .bytes()
            .next()
            .is_some_and(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
        && value.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'.' | b'_' | b'-')
        })
}

fn valid_selector(value: &str) -> bool {
    value.len() <= MAX_IDENTITY_BYTES
        && value
            .bytes()
            .next()
            .is_some_and(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
        && value.bytes().all(|byte| {
            byte.is_ascii_lowercase()
                || byte.is_ascii_digit()
                || matches!(byte, b'.' | b'_' | b'-' | b':' | b'/')
        })
}

fn valid_bounded_string(value: &str, max_bytes: usize, allow_empty: bool) -> bool {
    (allow_empty || !value.is_empty())
        && value.len() <= max_bytes
        && !value.chars().any(char::is_control)
}

/// SHA-256 helper used by thin facades and their compatibility tests.
#[doc(hidden)]
#[must_use]
pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn valid_sha256(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn marker_token(value: &str) -> String {
    let token = value
        .chars()
        .filter(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '-' | '_' | ':' | '/')
        })
        .collect::<String>();
    if token.is_empty() {
        "none".to_owned()
    } else {
        token
    }
}

/// A closed v2 effect family. Membership is read-only; callers cannot amend
/// the accepted feature or the lock-wide union.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FeatureLockV2Effect {
    /// Android permission.
    Permission,
    /// Android service.
    Service,
    /// Android activity.
    Activity,
    /// Android query.
    Query,
    /// Build or runtime tool.
    Tool,
    /// Packaged asset.
    Asset,
    /// Shader.
    Shader,
    /// Native library.
    NativeLibrary,
    /// Command.
    Command,
    /// Route.
    Route,
    /// Media or other stream.
    Stream,
    /// Runtime input.
    Input,
    /// Scene.
    Scene,
    /// Effective marker.
    Marker,
}

/// Stable, fail-closed v2 inspection classes, separate from the v1 activation
/// rejection vocabulary.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FeatureLockV2Rejection {
    /// Caller-provided digest or selector is malformed.
    InvalidExpectation,
    /// Supplied raw bytes differ from the accepted digest.
    UnacceptedBytes,
    /// Unsupported or malformed v2 lock.
    InvalidLock,
    /// Neither source-pinned fingerprint representation matches.
    InvalidFingerprint,
    /// Selection, dependency, conflict, activation, or effect closure drifted.
    InvalidClosure,
    /// Requested feature is absent from the selected closure.
    FeatureNotSelected,
}

/// Authenticated, immutable facts from one selected v2 feature.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct InspectedFeatureLockV2 {
    /// Feature-lock schema generation, always two.
    pub generation: u8,
    /// Exact raw input SHA-256, lowercase without a prefix.
    pub raw_sha256: String,
    /// Project identity.
    pub project_id: String,
    /// Project-spec revision independent from the lock revision.
    pub project_revision: u64,
    /// Feature-lock revision.
    pub lock_revision: u64,
    /// Verified resolver fingerprint, lowercase without a prefix.
    pub resolver_fingerprint: String,
    /// Selected feature identity.
    pub feature_id: String,
    /// Selected feature module identity.
    pub module_id: String,
    /// Selected activation receipt schema.
    pub receipt_schema: String,
    /// Selected effective marker.
    pub effective_marker: String,
    /// Accepted explicit runtime-input identifiers.
    pub runtime_inputs: Vec<String>,
    selected_effects: V2Effects,
    union_effects: V2Effects,
}

impl InspectedFeatureLockV2 {
    /// Check one effect of the selected feature.
    #[must_use]
    pub fn selected_has_effect(&self, family: FeatureLockV2Effect, value: &str) -> bool {
        self.selected_effects
            .members(family)
            .iter()
            .any(|member| member == value)
    }

    /// Check one effect in the exact lock-wide union.
    #[must_use]
    pub fn union_has_effect(&self, family: FeatureLockV2Effect, value: &str) -> bool {
        self.union_effects
            .members(family)
            .iter()
            .any(|member| member == value)
    }
}

// Field order is the owner resolver's original ordered-compressed JSON order.
// serde_json::Value is used separately for canonical (sorted-key) projection.
#[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct V2Lock {
    #[serde(rename = "$schema")]
    schema_uri: String,
    schema: String,
    project_id: String,
    project_revision: u64,
    revision: u64,
    generated_at: String,
    resolver_version: String,
    lock_fingerprint: String,
    default_activation: String,
    activation_rule: String,
    selected_features: Vec<String>,
    denied_features: Vec<String>,
    features: Vec<V2Feature>,
    effect_union: V2Effects,
}

#[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct V2Feature {
    feature_id: String,
    module_id: String,
    version: String,
    owner_lane: String,
    selected: bool,
    run_activation_default: String,
    descriptor: V2Descriptor,
    dependencies: Vec<String>,
    conflicts: Vec<String>,
    exclusive_group: Option<String>,
    effects: V2Effects,
    parameter_authorities: Vec<V2Authority>,
    activation: V2Activation,
    validation_profile: String,
    rollback_profile: String,
}

#[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct V2Descriptor {
    path: String,
    sha256: String,
    source_repo: String,
    source_revision: String,
    source_path: String,
    source_sha256: String,
}

#[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct V2Authority {
    parameter: String,
    owner: String,
}

#[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct V2Activation {
    rule: String,
    runtime_inputs: Vec<String>,
    receipt_schema: String,
    effective_marker: String,
}

#[derive(Clone, Debug, Eq, PartialEq, serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct V2Effects {
    permissions: Vec<String>,
    services: Vec<String>,
    activities: Vec<String>,
    queries: Vec<String>,
    tools: Vec<String>,
    assets: Vec<String>,
    shaders: Vec<String>,
    native_libraries: Vec<String>,
    commands: Vec<String>,
    routes: Vec<String>,
    streams: Vec<String>,
    inputs: Vec<String>,
    scenes: Vec<String>,
    markers: Vec<String>,
}

impl V2Effects {
    fn members(&self, family: FeatureLockV2Effect) -> &[String] {
        match family {
            FeatureLockV2Effect::Permission => &self.permissions,
            FeatureLockV2Effect::Service => &self.services,
            FeatureLockV2Effect::Activity => &self.activities,
            FeatureLockV2Effect::Query => &self.queries,
            FeatureLockV2Effect::Tool => &self.tools,
            FeatureLockV2Effect::Asset => &self.assets,
            FeatureLockV2Effect::Shader => &self.shaders,
            FeatureLockV2Effect::NativeLibrary => &self.native_libraries,
            FeatureLockV2Effect::Command => &self.commands,
            FeatureLockV2Effect::Route => &self.routes,
            FeatureLockV2Effect::Stream => &self.streams,
            FeatureLockV2Effect::Input => &self.inputs,
            FeatureLockV2Effect::Scene => &self.scenes,
            FeatureLockV2Effect::Marker => &self.markers,
        }
    }

    fn families(&self) -> [&[String]; 14] {
        [
            &self.permissions,
            &self.services,
            &self.activities,
            &self.queries,
            &self.tools,
            &self.assets,
            &self.shaders,
            &self.native_libraries,
            &self.commands,
            &self.routes,
            &self.streams,
            &self.inputs,
            &self.scenes,
            &self.markers,
        ]
    }

    fn valid(&self) -> bool {
        self.families().iter().all(|items| {
            items.len() <= MAX_LIST_ITEMS
                && items
                    .iter()
                    .all(|item| valid_bounded_string(item, MAX_DESCRIPTOR_BYTES, false))
                && items.iter().collect::<HashSet<_>>().len() == items.len()
        })
    }
}

/// Inspect exact v2 lock bytes without selecting or executing a platform effect.
///
/// Both SHA-256 values are unprefixed, lowercase 64-digit hex. The legacy v1
/// resolver remains a separate API with unchanged ordering and rejection tokens.
pub fn inspect_feature_lock_v2(
    lock_json: &str,
    accepted_raw_sha256: &str,
    selected_feature_id: &str,
) -> Result<InspectedFeatureLockV2, FeatureLockV2Rejection> {
    if lock_json.len() > MAX_LOCK_BYTES
        || !valid_sha256(accepted_raw_sha256)
        || !valid_v2_id(selected_feature_id)
    {
        return Err(FeatureLockV2Rejection::InvalidExpectation);
    }
    let raw_sha256 = sha256_hex(lock_json.as_bytes());
    if accepted_raw_sha256 != raw_sha256 {
        return Err(FeatureLockV2Rejection::UnacceptedBytes);
    }
    let lock: V2Lock =
        serde_json::from_str(lock_json).map_err(|_| FeatureLockV2Rejection::InvalidLock)?;
    if lock.schema_uri != "https://github.com/MesmerPrism/rusty-morphospace-work-environment/schemas/feature-lock-v2.schema.json"
        || lock.schema != "rusty.morphospace.workflow.feature_lock.v2"
        || lock.resolver_version != "rusty-morphospace-feature-resolver/2"
        || lock.default_activation != "disabled"
        || lock.activation_rule != "selected-lock-and-runtime-input"
        || lock.project_revision == 0 || lock.revision == 0
        || !valid_v2_id(&lock.project_id)
        || !valid_v2_timestamp(&lock.generated_at)
        || !valid_sha256(&lock.lock_fingerprint)
    {
        return Err(FeatureLockV2Rejection::InvalidLock);
    }
    let fingerprint = lock.lock_fingerprint.clone();
    let mut zeroed = lock.clone();
    zeroed.lock_fingerprint = "0".repeat(64);
    let value = serde_json::to_value(&zeroed).map_err(|_| FeatureLockV2Rejection::InvalidLock)?;
    let canonical = sha256_hex(canonical_v2_json(&value).as_bytes());
    let ordered =
        serde_json::to_string(&zeroed).map_err(|_| FeatureLockV2Rejection::InvalidLock)?;
    let historic = sha256_hex(ordered.as_bytes());
    if fingerprint != canonical && fingerprint != historic {
        return Err(FeatureLockV2Rejection::InvalidFingerprint);
    }
    if !valid_v2_closure(&lock) {
        return Err(FeatureLockV2Rejection::InvalidClosure);
    }
    let selected = lock
        .features
        .iter()
        .find(|feature| feature.feature_id == selected_feature_id)
        .ok_or(FeatureLockV2Rejection::FeatureNotSelected)?;
    Ok(InspectedFeatureLockV2 {
        generation: 2,
        raw_sha256,
        project_id: lock.project_id.clone(),
        project_revision: lock.project_revision,
        lock_revision: lock.revision,
        resolver_fingerprint: fingerprint,
        feature_id: selected.feature_id.clone(),
        module_id: selected.module_id.clone(),
        receipt_schema: selected.activation.receipt_schema.clone(),
        effective_marker: selected.activation.effective_marker.clone(),
        runtime_inputs: selected.activation.runtime_inputs.clone(),
        selected_effects: selected.effects.clone(),
        union_effects: lock.effect_union,
    })
}

fn valid_v2_timestamp(value: &str) -> bool {
    let bytes = value.as_bytes();
    if !(20..=64).contains(&bytes.len())
        || !bytes.is_ascii()
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
    {
        return false;
    }
    let number = |start: usize, end: usize| -> Option<u32> {
        let text = value.get(start..end)?;
        if !text.bytes().all(|byte| byte.is_ascii_digit()) {
            return None;
        }
        text.parse().ok()
    };
    let (Some(year), Some(month), Some(day), Some(hour), Some(minute), Some(second)) = (
        number(0, 4),
        number(5, 7),
        number(8, 10),
        number(11, 13),
        number(14, 16),
        number(17, 19),
    ) else {
        return false;
    };
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let max_day = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if leap => 29,
        2 => 28,
        _ => return false,
    };
    if year == 0 || day == 0 || day > max_day || hour > 23 || minute > 59 || second > 59 {
        return false;
    }
    let mut tail = &value[19..];
    if let Some(fraction) = tail.strip_prefix('.') {
        let digits = fraction.bytes().take_while(u8::is_ascii_digit).count();
        if digits == 0 {
            return false;
        }
        tail = &fraction[digits..];
    }
    if tail == "Z" {
        return true;
    }
    let offset = tail.as_bytes();
    offset.len() == 6
        && matches!(offset[0], b'+' | b'-')
        && offset[3] == b':'
        && number_offset(&offset[1..3]).is_some_and(|hours| hours <= 23)
        && number_offset(&offset[4..6]).is_some_and(|minutes| minutes <= 59)
}

fn number_offset(bytes: &[u8]) -> Option<u32> {
    if bytes.len() != 2 || !bytes.iter().all(u8::is_ascii_digit) {
        return None;
    }
    Some(u32::from(bytes[0] - b'0') * 10 + u32::from(bytes[1] - b'0'))
}

fn valid_v2_path(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_DESCRIPTOR_BYTES
        && !value.starts_with('/')
        && !value.contains('\\')
        && !value.contains(':')
        && !value
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
        && !value.chars().any(char::is_control)
}

fn valid_v2_id(value: &str) -> bool {
    (2..=128).contains(&value.len())
        && value
            .bytes()
            .next()
            .is_some_and(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

fn valid_v2_sorted_ids(values: &[String]) -> bool {
    values.len() <= MAX_FEATURES
        && values.iter().all(|value| valid_v2_id(value))
        && values.windows(2).all(|pair| pair[0] < pair[1])
}

fn valid_v2_unique_ids(values: &[String]) -> bool {
    values.len() <= MAX_FEATURES
        && values.iter().all(|value| valid_v2_id(value))
        && values.iter().collect::<HashSet<_>>().len() == values.len()
}

fn valid_v2_closure(lock: &V2Lock) -> bool {
    // The resolver emits sorted selections. An adopted active-envelope
    // extension can retain earlier selections and append a dependent feature;
    // that shape must be dependency-first instead.
    let resolver_order = lock
        .selected_features
        .windows(2)
        .all(|pair| pair[0] < pair[1]);
    if lock.features.len() > MAX_FEATURES
        || !valid_v2_unique_ids(&lock.selected_features)
        || !valid_v2_sorted_ids(&lock.denied_features)
        || lock.selected_features.len() != lock.features.len()
        || !lock.effect_union.valid()
        || lock
            .effect_union
            .families()
            .iter()
            .any(|items| !items.windows(2).all(|pair| pair[0] < pair[1]))
    {
        return false;
    }
    let selected: HashSet<_> = lock.selected_features.iter().map(String::as_str).collect();
    if lock
        .denied_features
        .iter()
        .any(|id| selected.contains(id.as_str()))
    {
        return false;
    }
    let mut modules = HashSet::new();
    let mut groups = HashSet::new();
    let mut effects: [HashSet<&str>; 14] = std::array::from_fn(|_| HashSet::new());
    for (index, feature) in lock.features.iter().enumerate() {
        if feature.feature_id != lock.selected_features[index]
            || !valid_v2_id(&feature.module_id)
            || !modules.insert(feature.module_id.as_str())
            || !valid_v2_id(&feature.owner_lane)
            || !valid_bounded_string(&feature.version, 64, false)
            || !feature.selected
            || feature.run_activation_default != "disabled"
            || !valid_v2_path(&feature.descriptor.path)
            || !valid_v2_path(&feature.descriptor.source_path)
            || !valid_sha256(&feature.descriptor.sha256)
            || !valid_sha256(&feature.descriptor.source_sha256)
            || feature.descriptor.source_revision.len() != 40
            || !feature
                .descriptor
                .source_revision
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit())
            || !valid_v2_id(&feature.descriptor.source_repo)
            || !valid_v2_sorted_ids(&feature.dependencies)
            || !valid_v2_sorted_ids(&feature.conflicts)
            || feature.dependencies.iter().any(|id| {
                !selected.contains(id.as_str())
                    || id == &feature.feature_id
                    || (!resolver_order && !lock.selected_features[..index].contains(id))
            })
            || feature
                .conflicts
                .iter()
                .any(|id| selected.contains(id.as_str()))
            || feature
                .exclusive_group
                .as_ref()
                .is_some_and(|group| !valid_v2_id(group) || !groups.insert(group.as_str()))
            || !feature.effects.valid()
            || !valid_v2_id(&feature.validation_profile)
            || !valid_v2_id(&feature.rollback_profile)
            || feature.activation.rule != "selected-lock-and-runtime-input"
            || feature.activation.runtime_inputs.is_empty()
            || !valid_value_list(&feature.activation.runtime_inputs)
            || !valid_dotted_id(&feature.activation.receipt_schema)
            || !valid_dotted_id(&feature.activation.effective_marker)
            || !feature
                .effects
                .markers
                .contains(&feature.activation.effective_marker)
            || feature
                .activation
                .runtime_inputs
                .iter()
                .any(|input| !feature.effects.inputs.contains(input))
            || feature.parameter_authorities.len() > MAX_LIST_ITEMS
        {
            return false;
        }
        let mut params = HashSet::new();
        if feature.parameter_authorities.iter().any(|authority| {
            !valid_dotted_id(&authority.parameter)
                || !valid_v2_id(&authority.owner)
                || !params.insert(authority.parameter.as_str())
        }) {
            return false;
        }
        for (family, members) in feature.effects.families().iter().enumerate() {
            effects[family].extend(members.iter().map(String::as_str));
        }
    }
    if !v2_dependencies_acyclic(&lock.features) {
        return false;
    }
    lock.effect_union
        .families()
        .iter()
        .enumerate()
        .all(|(family, members)| {
            members.iter().map(String::as_str).collect::<HashSet<_>>() == effects[family]
        })
}

fn v2_dependencies_acyclic(features: &[V2Feature]) -> bool {
    fn visit(
        index: usize,
        features: &[V2Feature],
        by_id: &HashMap<&str, usize>,
        states: &mut [u8],
    ) -> bool {
        if states[index] == 1 {
            return false;
        }
        if states[index] == 2 {
            return true;
        }
        states[index] = 1;
        for dependency in &features[index].dependencies {
            let Some(target) = by_id.get(dependency.as_str()) else {
                return false;
            };
            if !visit(*target, features, by_id, states) {
                return false;
            }
        }
        states[index] = 2;
        true
    }
    let by_id: HashMap<_, _> = features
        .iter()
        .enumerate()
        .map(|(index, feature)| (feature.feature_id.as_str(), index))
        .collect();
    let mut states = vec![0; features.len()];
    (0..features.len()).all(|index| visit(index, features, &by_id, &mut states))
}

fn canonical_v2_json(value: &serde_json::Value) -> String {
    fn encode(value: &serde_json::Value, out: &mut String) {
        match value {
            serde_json::Value::Null => out.push_str("null"),
            serde_json::Value::Bool(value) => out.push_str(if *value { "true" } else { "false" }),
            serde_json::Value::Number(value) => out.push_str(&value.to_string()),
            serde_json::Value::String(value) => {
                out.push('"');
                for unit in value.encode_utf16() {
                    match unit {
                        8 => out.push_str("\\b"),
                        9 => out.push_str("\\t"),
                        10 => out.push_str("\\n"),
                        12 => out.push_str("\\f"),
                        13 => out.push_str("\\r"),
                        34 => out.push_str("\\\""),
                        92 => out.push_str("\\\\"),
                        0..=31 | 127..=u16::MAX => out.push_str(&format!("\\u{unit:04x}")),
                        _ => out.push(char::from_u32(u32::from(unit)).expect("ASCII unit")),
                    }
                }
                out.push('"');
            }
            serde_json::Value::Array(items) => {
                out.push('[');
                for (index, item) in items.iter().enumerate() {
                    if index > 0 {
                        out.push(',');
                    }
                    encode(item, out);
                }
                out.push(']');
            }
            serde_json::Value::Object(fields) => {
                out.push('{');
                for (index, (key, item)) in fields.iter().enumerate() {
                    if index > 0 {
                        out.push(',');
                    }
                    encode(&serde_json::Value::String(key.clone()), out);
                    out.push(':');
                    encode(item, out);
                }
                out.push('}');
            }
        }
    }
    let mut out = String::new();
    encode(value, &mut out);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const PROJECT: &str = "neutral-project";
    const FEATURE: &str = "neutral-adapter";
    const PROFILE: &str = "profile.neutral";
    const POLICY: LockBoundActivationPolicy<'static> = LockBoundActivationPolicy {
        requested_by: "conformance-profile:neutral-adapter",
        receipt_schema: "rusty.quest.neutral_adapter.receipt.v1",
        effective_marker: "rusty.quest.neutral_adapter.effective",
    };

    fn lock(extra: &str) -> String {
        format!(
            r#"{{"schema":"rusty.morphospace.workflow.feature_lock.v1","project_id":"{PROJECT}","revision":3,"default_activation":"disabled","features":[{{"feature_id":"{FEATURE}","module_id":"{FEATURE}","enabled":true,"requested_by":"conformance-profile:neutral-adapter","descriptor":"morphospace/project.spec.json#{FEATURE}","dependencies":[],"conflicts":[],"permissions":[],"routes":[],"assets":[],"parameter_authorities":[],"activation_receipt":{{"required":true,"schema":"rusty.quest.neutral_adapter.receipt.v1","effective_marker":"rusty.quest.neutral_adapter.effective"}}{extra}}}]}}"#
        )
    }

    fn input(lock: &str) -> LockBoundActivationRuntimeInput {
        LockBoundActivationRuntimeInput {
            enabled: true,
            profile_id: PROFILE.to_owned(),
            project_id: PROJECT.to_owned(),
            feature_id: FEATURE.to_owned(),
            lock_revision: 3,
            lock_sha256: sha256_hex(lock.as_bytes()),
        }
    }

    #[test]
    fn third_neutral_consumer_uses_generic_engine() {
        let lock = lock("");
        let decision = resolve_lock_bound_activation(
            &lock,
            &sha256_hex(lock.as_bytes()),
            PROJECT,
            FEATURE,
            FEATURE,
            PROFILE,
            POLICY,
            &input(&lock),
        );
        assert!(decision.is_applied());
    }

    #[test]
    fn unknown_nested_lock_field_rejects() {
        let nested = lock(r#", "application_secret":"bleed""#);
        let root = lock("").replacen(
            "{\"schema\"",
            "{\"application_secret\":\"bleed\",\"schema\"",
            1,
        );
        for lock in [nested, root] {
            let decision = resolve_lock_bound_activation(
                &lock,
                &sha256_hex(lock.as_bytes()),
                PROJECT,
                FEATURE,
                FEATURE,
                PROFILE,
                POLICY,
                &input(&lock),
            );
            assert_eq!(
                decision.rejection(),
                Some(LockBoundActivationRejection::InvalidLock)
            );
        }
    }

    #[test]
    fn module_policy_cannot_be_substituted() {
        let lock = lock("");
        let wrong = LockBoundActivationPolicy {
            requested_by: "conformance-profile:other",
            ..POLICY
        };
        let decision = resolve_lock_bound_activation(
            &lock,
            &sha256_hex(lock.as_bytes()),
            PROJECT,
            FEATURE,
            FEATURE,
            PROFILE,
            wrong,
            &input(&lock),
        );
        assert_eq!(
            decision.rejection(),
            Some(LockBoundActivationRejection::InvalidSelection)
        );
    }

    #[test]
    fn lossy_or_blank_audit_selectors_reject() {
        let lock = lock("");
        for selector in ["", "==", "profile with spaces"] {
            let decision = resolve_lock_bound_activation(
                &lock,
                &sha256_hex(lock.as_bytes()),
                PROJECT,
                FEATURE,
                FEATURE,
                selector,
                POLICY,
                &input(&lock),
            );
            assert_eq!(
                decision.rejection(),
                Some(LockBoundActivationRejection::InvalidExpectation)
            );
        }
    }

    #[test]
    fn app_owned_lock_digest_cannot_follow_mutated_bytes() {
        let accepted = lock("");
        let mutated = accepted.replace("\"routes\":[]", "\"routes\":[\"application-only-route\"]");
        let decision = resolve_lock_bound_activation(
            &mutated,
            &sha256_hex(accepted.as_bytes()),
            PROJECT,
            FEATURE,
            FEATURE,
            PROFILE,
            POLICY,
            &input(&mutated),
        );
        assert_eq!(
            decision.rejection(),
            Some(LockBoundActivationRejection::UnacceptedLock)
        );
    }

    #[test]
    fn feature_and_module_ids_are_independent_but_app_bound() {
        let accepted = lock("");
        let distinct = accepted.replace(
            &format!("\"module_id\":\"{FEATURE}\""),
            "\"module_id\":\"module-neutral\"",
        );
        let applied = resolve_lock_bound_activation(
            &distinct,
            &sha256_hex(distinct.as_bytes()),
            PROJECT,
            FEATURE,
            "module-neutral",
            PROFILE,
            POLICY,
            &input(&distinct),
        );
        assert!(applied.is_applied());

        let mismatched = resolve_lock_bound_activation(
            &distinct,
            &sha256_hex(distinct.as_bytes()),
            PROJECT,
            FEATURE,
            FEATURE,
            PROFILE,
            POLICY,
            &input(&distinct),
        );
        assert_eq!(
            mismatched.rejection(),
            Some(LockBoundActivationRejection::ModuleMismatch)
        );
    }

    #[test]
    fn dependencies_resolve_through_module_ids() {
        let lock = format!(
            r#"{{"schema":"rusty.morphospace.workflow.feature_lock.v1","project_id":"{PROJECT}","revision":3,"default_activation":"disabled","features":[{{"feature_id":"{FEATURE}","module_id":"module-app","enabled":true,"requested_by":"conformance-profile:neutral-adapter","descriptor":"app","dependencies":["module-kernel"],"conflicts":[],"permissions":[],"routes":[],"assets":[],"parameter_authorities":[],"activation_receipt":{{"required":true,"schema":"rusty.quest.neutral_adapter.receipt.v1","effective_marker":"rusty.quest.neutral_adapter.effective"}}}},{{"feature_id":"neutral-kernel","module_id":"module-kernel","enabled":true,"requested_by":"dependency-closure","descriptor":"kernel","dependencies":[],"conflicts":[],"permissions":[],"routes":[],"assets":[],"parameter_authorities":[],"activation_receipt":{{"required":true,"schema":"rusty.quest.neutral_kernel.receipt.v1","effective_marker":"rusty.quest.neutral_kernel.effective"}}}}]}}"#
        );
        let decision = resolve_lock_bound_activation(
            &lock,
            &sha256_hex(lock.as_bytes()),
            PROJECT,
            FEATURE,
            "module-app",
            PROFILE,
            POLICY,
            &input(&lock),
        );
        assert!(decision.is_applied());
    }

    #[test]
    fn module_dependency_and_feature_conflict_drift_fail_closed() {
        let accepted = lock("");
        let missing_dependency = accepted.replace(
            "\"dependencies\":[]",
            "\"dependencies\":[\"missing-module\"]",
        );
        let self_conflict = accepted.replace(
            "\"conflicts\":[]",
            &format!("\"conflicts\":[\"{FEATURE}\"]"),
        );
        for mutated in [missing_dependency, self_conflict] {
            let decision = resolve_lock_bound_activation(
                &mutated,
                &sha256_hex(mutated.as_bytes()),
                PROJECT,
                FEATURE,
                FEATURE,
                PROFILE,
                POLICY,
                &input(&mutated),
            );
            assert_eq!(
                decision.rejection(),
                Some(LockBoundActivationRejection::InvalidFeatureClosure)
            );
        }
    }
}

#[cfg(test)]
mod v2_tests {
    use super::*;

    const FEATURE: &str = "neutral-peer-input";
    const URI: &str = "https://github.com/MesmerPrism/rusty-morphospace-work-environment/schemas/feature-lock-v2.schema.json";

    fn effects() -> V2Effects {
        V2Effects {
            permissions: vec![],
            services: vec![],
            activities: vec![],
            queries: vec![],
            tools: vec![],
            assets: vec![],
            shaders: vec![],
            native_libraries: vec![],
            commands: vec![
                "command.media.session.start".into(),
                "command.media.session.stop".into(),
            ],
            routes: vec![],
            streams: vec!["stream.media.video".into()],
            inputs: vec!["profile:neutral".into()],
            scenes: vec![],
            markers: vec!["rusty.quest.neutral.effective".into()],
        }
    }

    fn fixture() -> V2Lock {
        V2Lock {
            schema_uri: URI.into(),
            schema: "rusty.morphospace.workflow.feature_lock.v2".into(),
            project_id: "neutral-project".into(),
            project_revision: 12,
            revision: 9,
            generated_at: "2026-09-16T05:34:34.4375593Z".into(),
            resolver_version: "rusty-morphospace-feature-resolver/2".into(),
            lock_fingerprint: "0".repeat(64),
            default_activation: "disabled".into(),
            activation_rule: "selected-lock-and-runtime-input".into(),
            selected_features: vec![FEATURE.into()],
            denied_features: vec!["other-feature".into()],
            features: vec![V2Feature {
                feature_id: FEATURE.into(),
                module_id: "neutral-media-module".into(),
                version: "1.0.0".into(),
                owner_lane: "quest-adapter".into(),
                selected: true,
                run_activation_default: "disabled".into(),
                descriptor: V2Descriptor {
                    path: "features/neutral-peer-input.json".into(),
                    sha256: "a".repeat(64),
                    source_repo: "neutral-repo".into(),
                    source_revision: "b".repeat(40),
                    source_path: "docs/NEUTRAL.md".into(),
                    source_sha256: "c".repeat(64),
                },
                dependencies: vec![],
                conflicts: vec!["other-feature".into()],
                exclusive_group: None,
                effects: effects(),
                parameter_authorities: vec![V2Authority {
                    parameter: "media.route".into(),
                    owner: "rusty-manifold".into(),
                }],
                activation: V2Activation {
                    rule: "selected-lock-and-runtime-input".into(),
                    runtime_inputs: vec!["profile:neutral".into()],
                    receipt_schema: "rusty.quest.neutral.receipt.v1".into(),
                    effective_marker: "rusty.quest.neutral.effective".into(),
                },
                validation_profile: "host".into(),
                rollback_profile: "rollback".into(),
            }],
            effect_union: effects(),
        }
    }

    fn bind(mut lock: V2Lock, historic: bool) -> String {
        lock.lock_fingerprint = "0".repeat(64);
        let projected = if historic {
            serde_json::to_string(&lock).expect("historic")
        } else {
            canonical_v2_json(&serde_json::to_value(&lock).expect("canonical value"))
        };
        lock.lock_fingerprint = sha256_hex(projected.as_bytes());
        serde_json::to_string(&lock).expect("lock")
    }

    fn inspect(text: &str) -> Result<InspectedFeatureLockV2, FeatureLockV2Rejection> {
        inspect_feature_lock_v2(text, &sha256_hex(text.as_bytes()), FEATURE)
    }

    #[test]
    fn canonical_and_historic_fingerprints_return_same_typed_facts() {
        let canonical: V2Lock = serde_json::from_str(&bind(fixture(), false)).expect("canonical");
        let historic: V2Lock = serde_json::from_str(&bind(fixture(), true)).expect("historic");
        assert_ne!(canonical.lock_fingerprint, historic.lock_fingerprint);
        for historic in [false, true] {
            let text = bind(fixture(), historic);
            let facts = inspect(&text).expect("accepted lock");
            assert_eq!(facts.generation, 2);
            assert_eq!((facts.project_revision, facts.lock_revision), (12, 9));
            assert_eq!(facts.module_id, "neutral-media-module");
            assert!(facts
                .selected_has_effect(FeatureLockV2Effect::Command, "command.media.session.start"));
            assert!(facts.union_has_effect(FeatureLockV2Effect::Stream, "stream.media.video"));
            assert!(!facts
                .selected_has_effect(FeatureLockV2Effect::Permission, "android.permission.CAMERA"));
        }
    }

    #[test]
    fn raw_digest_fingerprint_and_unknown_fields_fail_closed() {
        let text = bind(fixture(), false);
        assert_eq!(
            inspect_feature_lock_v2(&text, &"0".repeat(64), FEATURE),
            Err(FeatureLockV2Rejection::UnacceptedBytes)
        );
        let damaged = text.replace("profile:neutral", "profile:ambient");
        assert_eq!(
            inspect(&damaged),
            Err(FeatureLockV2Rejection::InvalidFingerprint)
        );
        let unknown: serde_json::Value = serde_json::from_str(&text).expect("value");
        for path in ["root", "nested"] {
            let mut value = unknown.clone();
            if path == "root" {
                value["extra"] = serde_json::json!(true);
            } else {
                value["features"][0]["activation"]["extra"] = serde_json::json!(true);
            }
            assert_eq!(
                inspect(&serde_json::to_string(&value).expect("text")),
                Err(FeatureLockV2Rejection::InvalidLock)
            );
        }
        let mut malformed_time = fixture();
        malformed_time.generated_at = "2026-02-30T05:34:34Z".into();
        assert_eq!(
            inspect(&bind(malformed_time, false)),
            Err(FeatureLockV2Rejection::InvalidLock)
        );
    }

    #[test]
    fn closure_effect_union_and_selection_damage_fail_closed() {
        let mut cases = Vec::new();
        let mut omitted = fixture();
        omitted.effect_union.commands.clear();
        cases.push(omitted);
        let mut added = fixture();
        added
            .effect_union
            .permissions
            .push("android.permission.CAMERA".into());
        cases.push(added);
        let mut conflict = fixture();
        conflict.features[0].conflicts = vec![FEATURE.into()];
        cases.push(conflict);
        let mut missing = fixture();
        missing.features[0].dependencies = vec!["missing-feature".into()];
        cases.push(missing);
        let mut drift = fixture();
        drift.features[0].activation.runtime_inputs = vec!["profile:ambient".into()];
        cases.push(drift);
        let mut duplicate = fixture();
        duplicate.selected_features.push(FEATURE.into());
        cases.push(duplicate);
        let mut wrong_order = fixture();
        let mut other = wrong_order.features[0].clone();
        other.feature_id = "neutral-other".into();
        other.module_id = "neutral-other-module".into();
        wrong_order
            .selected_features
            .insert(0, "neutral-other".into());
        wrong_order.features.push(other);
        cases.push(wrong_order);
        let mut cycle = fixture();
        let mut other = cycle.features[0].clone();
        other.feature_id = "neutral-other".into();
        other.module_id = "neutral-other-module".into();
        other.dependencies = vec![FEATURE.into()];
        cycle.features[0].dependencies = vec!["neutral-other".into()];
        cycle.selected_features.push("neutral-other".into());
        cycle.features.push(other);
        cases.push(cycle);
        for case in cases {
            assert_eq!(
                inspect(&bind(case, false)),
                Err(FeatureLockV2Rejection::InvalidClosure)
            );
        }
        assert_eq!(
            inspect_feature_lock_v2(
                &bind(fixture(), false),
                &sha256_hex(bind(fixture(), false).as_bytes()),
                "other-feature"
            ),
            Err(FeatureLockV2Rejection::FeatureNotSelected)
        );
    }

    #[test]
    fn optional_owner_lock_fixture_matches_source_pinned_semantics() {
        let Ok(path) = std::env::var("RUSTY_QUEST_FEATURE_LOCK_V2_FIXTURE") else {
            return;
        };
        let feature_id =
            std::env::var("RUSTY_QUEST_FEATURE_LOCK_V2_FEATURE").expect("fixture feature id");
        let text = std::fs::read_to_string(path).expect("fixture bytes");
        let facts = inspect_feature_lock_v2(&text, &sha256_hex(text.as_bytes()), &feature_id)
            .expect("owner lock");
        assert_eq!(facts.feature_id, feature_id);
        assert_eq!(facts.generation, 2);
    }
}
