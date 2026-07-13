use rusty_quest_hand_adapter::{
    HandAdapterLockActivationDecision, HandAdapterRuntimeActivationInput,
};
use rusty_quest_particle_adapter::{
    ParticleAdapterLockActivationDecision, ParticleAdapterRuntimeActivationInput,
};

const RECEIPT_SCHEMA: &str = "rusty.quest.spatial_adapter_lock_authority_receipt.v1";

pub(crate) fn hand_authority_receipt(input: &HandAdapterRuntimeActivationInput) -> String {
    let decision = crate::hand_adapter_consumer::resolve_activation(input);
    hand_receipt(&decision)
}

pub(crate) fn particle_authority_receipt(input: &ParticleAdapterRuntimeActivationInput) -> String {
    let decision = crate::particle_adapter_consumer::resolve_activation(input);
    particle_receipt(&decision)
}

fn hand_receipt(decision: &HandAdapterLockActivationDecision) -> String {
    authority_receipt(
        decision.is_applied(),
        decision.project_id(),
        decision.feature_id(),
        decision.lock_revision(),
        decision.lock_sha256(),
        decision.runtime_profile_id(),
        decision
            .rejection()
            .map_or("none", |rejection| rejection.marker_token()),
    )
}

fn particle_receipt(decision: &ParticleAdapterLockActivationDecision) -> String {
    authority_receipt(
        decision.is_applied(),
        decision.project_id(),
        decision.feature_id(),
        decision.lock_revision(),
        decision.lock_sha256(),
        decision.runtime_profile_id(),
        decision
            .rejection()
            .map_or("none", |rejection| rejection.marker_token()),
    )
}

fn authority_receipt(
    applied: bool,
    project_id: &str,
    feature_id: &str,
    lock_revision: u64,
    lock_sha256: &str,
    runtime_profile_id: &str,
    rejection_reason: &str,
) -> String {
    [
        RECEIPT_SCHEMA.to_owned(),
        if applied { "applied" } else { "rejected" }.to_owned(),
        receipt_token(project_id),
        receipt_token(feature_id),
        lock_revision.to_string(),
        receipt_token(lock_sha256),
        receipt_token(runtime_profile_id),
        receipt_token(rejection_reason),
    ]
    .join("\t")
}

fn receipt_token(value: &str) -> String {
    let token = value
        .trim()
        .chars()
        .map(|value| {
            if value.is_ascii_alphanumeric() || matches!(value, '.' | '_' | '-') {
                value
            } else {
                '_'
            }
        })
        .take(128)
        .collect::<String>();
    if token.is_empty() {
        "none".to_owned()
    } else {
        token
    }
}

#[cfg(target_os = "android")]
mod android_jni {
    use std::ptr;

    use jni::objects::JString;
    use jni::sys::{jboolean, jclass, jlong, jstring, JNIEnv};

    use super::{hand_authority_receipt, particle_authority_receipt};

    #[no_mangle]
    #[allow(non_snake_case)]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialAdapterNativeAuthority_nativeResolveHandAdapterActivation(
        env: *mut JNIEnv,
        _class: jclass,
        runtime_enabled: jboolean,
        runtime_profile_id: jstring,
        runtime_project_id: jstring,
        runtime_feature_id: jstring,
        runtime_lock_revision: jlong,
        runtime_lock_sha256: jstring,
    ) -> jstring {
        let Some(mut env) = jni_env(env) else {
            return ptr::null_mut();
        };
        let input = rusty_quest_hand_adapter::HandAdapterRuntimeActivationInput {
            enabled: runtime_enabled != 0,
            profile_id: read_string(&mut env, runtime_profile_id),
            project_id: read_string(&mut env, runtime_project_id),
            feature_id: read_string(&mut env, runtime_feature_id),
            lock_revision: u64::try_from(runtime_lock_revision).unwrap_or(0),
            lock_sha256: read_string(&mut env, runtime_lock_sha256),
        };
        write_string(&mut env, &hand_authority_receipt(&input))
    }

    #[no_mangle]
    #[allow(non_snake_case)]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialAdapterNativeAuthority_nativeResolveParticleAdapterActivation(
        env: *mut JNIEnv,
        _class: jclass,
        runtime_enabled: jboolean,
        runtime_profile_id: jstring,
        runtime_project_id: jstring,
        runtime_feature_id: jstring,
        runtime_lock_revision: jlong,
        runtime_lock_sha256: jstring,
    ) -> jstring {
        let Some(mut env) = jni_env(env) else {
            return ptr::null_mut();
        };
        let input = rusty_quest_particle_adapter::ParticleAdapterRuntimeActivationInput {
            enabled: runtime_enabled != 0,
            profile_id: read_string(&mut env, runtime_profile_id),
            project_id: read_string(&mut env, runtime_project_id),
            feature_id: read_string(&mut env, runtime_feature_id),
            lock_revision: u64::try_from(runtime_lock_revision).unwrap_or(0),
            lock_sha256: read_string(&mut env, runtime_lock_sha256),
        };
        write_string(&mut env, &particle_authority_receipt(&input))
    }

    fn jni_env(env: *mut JNIEnv) -> Option<jni::JNIEnv<'static>> {
        if env.is_null() {
            return None;
        }
        unsafe { jni::JNIEnv::from_raw(env) }.ok()
    }

    fn read_string(env: &mut jni::JNIEnv<'_>, value: jstring) -> String {
        if value.is_null() {
            return String::new();
        }
        let value = unsafe { JString::from_raw(value) };
        env.get_string(&value)
            .map(|text| text.to_string_lossy().into_owned())
            .unwrap_or_default()
    }

    fn write_string(env: &mut jni::JNIEnv<'_>, value: &str) -> jstring {
        env.new_string(value)
            .map(JString::into_raw)
            .unwrap_or(ptr::null_mut())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_embedded_locks_authorize_and_stale_inputs_reject() {
        let hand = HandAdapterRuntimeActivationInput {
            enabled: true,
            profile_id: crate::hand_adapter_consumer::ACCEPTED_PROFILE_ID.to_owned(),
            project_id: crate::hand_adapter_consumer::PROJECT_ID.to_owned(),
            feature_id: crate::hand_adapter_consumer::FEATURE_ID.to_owned(),
            lock_revision: 1,
            lock_sha256: "FFB07E39C7290FDE8EBB154EFB94985CF4628CB2E4D098A81A5611849DFD32F1"
                .to_owned(),
        };
        let hand_receipt = hand_authority_receipt(&hand);
        assert!(hand_receipt.contains("\tapplied\tspatial-camera-panel\ttracked-hand-surface\t1\t"));

        let particle = ParticleAdapterRuntimeActivationInput {
            enabled: true,
            profile_id: crate::particle_adapter_consumer::ACCEPTED_PROFILE_ID.to_owned(),
            project_id: crate::particle_adapter_consumer::PROJECT_ID.to_owned(),
            feature_id: crate::particle_adapter_consumer::FEATURE_ID.to_owned(),
            lock_revision: 1,
            lock_sha256: "780814BE82C12A54036DE0259C6188E2D41813858C30E6B6C725EB8422F7301B"
                .to_owned(),
        };
        let particle_receipt = particle_authority_receipt(&particle);
        assert!(particle_receipt
            .contains("\tapplied\tspatial-camera-panel\tsurface-particle-runtime\t1\t"));

        let mut stale = particle;
        stale.lock_revision = 0;
        let stale_receipt = particle_authority_receipt(&stale);
        assert!(stale_receipt.contains("\trejected\t"));
        assert!(stale_receipt.ends_with("\truntime-revision-mismatch"));
    }
}
