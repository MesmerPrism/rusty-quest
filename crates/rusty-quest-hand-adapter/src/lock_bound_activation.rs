//! Hand-adapter policy facade over the application-neutral lock engine.

use rusty_quest_feature_activation::{
    resolve_lock_bound_activation, LockBoundActivationDecision, LockBoundActivationPolicy,
};

pub use rusty_quest_feature_activation::{
    LockBoundActivationRejection as HandAdapterLockRejection,
    LockBoundActivationRuntimeInput as HandAdapterRuntimeActivationInput,
    LockBoundActivationState as HandAdapterLockActivationState, LOCK_BOUND_ACTIVATION_SCHEMA_ID,
};

use crate::HAND_ADAPTER_RECEIPT_SCHEMA_ID;

const HAND_ADAPTER_EFFECTIVE_MARKER_ID: &str = "rusty.quest.hand_adapter.effective";
const HAND_ADAPTER_REQUESTED_BY: &str = "conformance-profile:hand-adapter";

/// Nominal hand-only activation authority minted by this facade.
///
/// The inner generic decision is private so a particle decision or a manually
/// assembled applied state cannot cross the hand effect gate.
///
/// ```compile_fail
/// use rusty_quest_hand_adapter::HandAdapterLockActivationDecision;
/// use rusty_quest_particle_adapter::ParticleAdapterLockActivationDecision;
///
/// fn hand_effect(_: &HandAdapterLockActivationDecision) {}
/// fn cannot_substitute(particle: &ParticleAdapterLockActivationDecision) {
///     hand_effect(particle);
/// }
/// ```
///
/// ```compile_fail
/// use rusty_quest_hand_adapter::HandAdapterLockActivationDecision;
///
/// // Facade decisions have no public constructor or public inner field.
/// let _forged = HandAdapterLockActivationDecision { inner: todo!() };
/// ```
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HandAdapterLockActivationDecision {
    inner: LockBoundActivationDecision,
}

impl HandAdapterLockActivationDecision {
    /// Whether hand marker and input effects may proceed.
    #[must_use]
    pub fn is_applied(&self) -> bool {
        self.inner.is_applied()
    }

    /// Low-rate marker fields derived by the closed generic engine.
    #[must_use]
    pub fn marker_fields(&self) -> String {
        self.inner.marker_fields()
    }

    /// Project identity carried by the selected lock.
    #[must_use]
    pub fn project_id(&self) -> &str {
        self.inner.project_id()
    }

    /// Feature identity carried by the selected lock.
    #[must_use]
    pub fn feature_id(&self) -> &str {
        self.inner.feature_id()
    }

    /// Selected lock revision.
    #[must_use]
    pub fn lock_revision(&self) -> u64 {
        self.inner.lock_revision()
    }

    /// SHA-256 of the exact selected lock bytes.
    #[must_use]
    pub fn lock_sha256(&self) -> &str {
        self.inner.lock_sha256()
    }

    /// App-owned accepted runtime profile.
    #[must_use]
    pub fn runtime_profile_id(&self) -> &str {
        self.inner.runtime_profile_id()
    }

    /// Stable rejection, absent only when applied.
    #[must_use]
    pub fn rejection(&self) -> Option<HandAdapterLockRejection> {
        self.inner.rejection()
    }
}

/// Resolve an exact project conformance lock and app-approved runtime input.
///
/// This facade deliberately owns only hand-specific selection policy. JSON
/// parsing, exact-byte digest binding, rejection vocabulary, and marker fields
/// remain in `rusty-quest-feature-activation` so another application cannot
/// fork the closed-world rules while reusing this adapter.
#[must_use]
pub fn resolve_hand_adapter_activation(
    lock_json: &str,
    accepted_lock_sha256: &str,
    expected_project_id: &str,
    expected_feature_id: &str,
    expected_module_id: &str,
    accepted_profile_id: &str,
    runtime_input: &HandAdapterRuntimeActivationInput,
) -> HandAdapterLockActivationDecision {
    HandAdapterLockActivationDecision {
        inner: resolve_lock_bound_activation(
            lock_json,
            accepted_lock_sha256,
            expected_project_id,
            expected_feature_id,
            expected_module_id,
            accepted_profile_id,
            LockBoundActivationPolicy {
                requested_by: HAND_ADAPTER_REQUESTED_BY,
                receipt_schema: HAND_ADAPTER_RECEIPT_SCHEMA_ID,
                effective_marker: HAND_ADAPTER_EFFECTIVE_MARKER_ID,
            },
            runtime_input,
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_quest_feature_activation::sha256_hex;

    const PROJECT: &str = "test-project";
    const FEATURE: &str = "hand-adapter-consumer";
    const PROFILE: &str = "profile.test.hand-adapter";

    fn lock(enabled: bool) -> String {
        format!(
            r#"{{"schema":"rusty.morphospace.workflow.feature_lock.v1","project_id":"{PROJECT}","revision":9,"default_activation":"disabled","features":[{{"feature_id":"{FEATURE}","module_id":"{FEATURE}","enabled":{enabled},"requested_by":"conformance-profile:hand-adapter","descriptor":"morphospace/project.spec.json#{FEATURE}","dependencies":[],"conflicts":[],"permissions":[],"routes":[],"assets":[],"parameter_authorities":[],"activation_receipt":{{"required":true,"schema":"rusty.quest.hand_adapter.receipt.v1","effective_marker":"rusty.quest.hand_adapter.effective"}}}}]}}"#
        )
    }

    fn input(lock: &str) -> HandAdapterRuntimeActivationInput {
        HandAdapterRuntimeActivationInput {
            enabled: true,
            profile_id: PROFILE.to_owned(),
            project_id: PROJECT.to_owned(),
            feature_id: FEATURE.to_owned(),
            lock_revision: 9,
            lock_sha256: sha256_hex(lock.as_bytes()),
        }
    }

    fn resolve(
        lock: &str,
        input: &HandAdapterRuntimeActivationInput,
    ) -> HandAdapterLockActivationDecision {
        resolve_hand_adapter_activation(
            lock,
            &sha256_hex(lock.as_bytes()),
            PROJECT,
            FEATURE,
            FEATURE,
            PROFILE,
            input,
        )
    }

    #[test]
    fn exact_selected_lock_and_profile_apply() {
        let lock = lock(true);
        let decision = resolve(&lock, &input(&lock));
        assert!(decision.is_applied());
        assert!(decision.marker_fields().contains("activationState=applied"));
        assert!(decision
            .marker_fields()
            .contains(&sha256_hex(lock.as_bytes())));
    }

    #[test]
    fn default_disabled_lock_rejects_before_runtime_input() {
        let default_lock = lock(false);
        let decision = resolve(&default_lock, &input(&default_lock));
        assert_eq!(
            decision.rejection(),
            Some(HandAdapterLockRejection::FeatureNotSelected)
        );
        assert!(!decision.is_applied());
    }

    #[test]
    fn stale_wrong_and_unaccepted_runtime_inputs_reject() {
        let lock = lock(true);
        let baseline = input(&lock);
        let mut cases = Vec::new();
        let mut disabled = baseline.clone();
        disabled.enabled = false;
        cases.push((disabled, HandAdapterLockRejection::RuntimeInputDisabled));
        let mut profile = baseline.clone();
        profile.profile_id = "profile.unaccepted".to_owned();
        cases.push((profile, HandAdapterLockRejection::RuntimeProfileMismatch));
        let mut project = baseline.clone();
        project.project_id = "other-project".to_owned();
        cases.push((project, HandAdapterLockRejection::RuntimeProjectMismatch));
        let mut feature = baseline.clone();
        feature.feature_id = "other-feature".to_owned();
        cases.push((feature, HandAdapterLockRejection::RuntimeFeatureMismatch));
        let mut revision = baseline.clone();
        revision.lock_revision = 8;
        cases.push((revision, HandAdapterLockRejection::RuntimeRevisionMismatch));
        let mut digest = baseline;
        digest.lock_sha256 = "F".repeat(64);
        cases.push((digest, HandAdapterLockRejection::RuntimeDigestMismatch));

        for (input, expected) in cases {
            let decision = resolve(&lock, &input);
            assert_eq!(decision.rejection(), Some(expected));
            assert!(!decision.is_applied());
        }
    }
}
