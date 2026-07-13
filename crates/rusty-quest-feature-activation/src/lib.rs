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
