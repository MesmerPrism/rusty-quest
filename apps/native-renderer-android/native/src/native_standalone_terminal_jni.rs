//! JNI projection of the existing session owner's local terminal and retirement seams.
//! No inventory, recording owner, or command wire schema is introduced here.

use crate::experiment_session_runtime::{self, LocalTerminalScope, RendererRetirementScope};
use jni::{
    objects::{JClass, JString},
    sys::{jlong, jstring},
    EnvUnowned, Outcome,
};

// A transport text bound only. The existing command parser and exact
// worker fence retain all semantic admission and identity validation.
const MAX_TRANSPORT_TEXT_BYTES: usize = 32 * 1024;

fn response(mut env: EnvUnowned, wire: String) -> jstring {
    match env
        .with_env(|env| env.new_string(wire).map(|s| s.into_raw()))
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeReadExperimentSessionImmersiveOwnerIdentity(
    mut env: EnvUnowned,
    _class: JClass,
) -> jlong {
    match env
        .with_env(|_| -> jni::errors::Result<jlong> {
            let identity = experiment_session_runtime::immersive_owner_identity();
            Ok(if identity > 0 && identity < i64::MAX as u64 {
                identity as jlong
            } else {
                0
            })
        })
        .into_outcome()
    {
        Outcome::Ok(identity) => identity,
        Outcome::Err(_) | Outcome::Panic(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeApplyExperimentSessionTerminalWithFence(
    mut env: EnvUnowned,
    _class: JClass,
    command: JString,
    owner: jlong,
    epoch: jlong,
    generation: jlong,
    session_key: JString,
    condition: JString,
    profile_sha256: JString,
    binding_sha256: JString,
) -> jstring {
    if owner <= 0 || owner == i64::MAX || epoch <= 0 || generation < 0 {
        return response(env, "{\"schema\":\"rusty.quest.experiment_session.response.v1\",\"command_status\":\"rejected\",\"reason_code\":\"terminal-jni-scope-invalid\",\"readback\":{}}".to_owned());
    }
    match env.with_env(|env| -> jni::errors::Result<jstring> {
        let command = command.try_to_string(env)?;
        let session_key = session_key.try_to_string(env)?;
        let condition_id = condition.try_to_string(env)?;
        let profile_sha256 = profile_sha256.try_to_string(env)?;
        let binding_sha256 = binding_sha256.try_to_string(env)?;
        if [&command, &session_key, &condition_id, &profile_sha256, &binding_sha256]
            .iter().any(|text| text.len() > MAX_TRANSPORT_TEXT_BYTES) {
            return env.new_string("{\"schema\":\"rusty.quest.experiment_session.response.v1\",\"command_status\":\"rejected\",\"reason_code\":\"terminal-jni-text-too-large\",\"readback\":{}}")
                .map(|s| s.into_raw());
        }
        let wire = experiment_session_runtime::apply_terminal_with_fence(&command, LocalTerminalScope {
                owner_identity: owner as u64, runtime_epoch: epoch as u64, generation: generation as u64,
                session_key, condition_id, profile_sha256, binding_sha256,
            });
        env.new_string(wire).map(|s| s.into_raw())
    }).into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

fn retirement(
    mut env: EnvUnowned,
    owner: jlong,
    epoch: jlong,
    generation: jlong,
    operation: JString,
    acknowledgement: jlong,
    request: bool,
) -> jstring {
    if owner <= 0 || owner == i64::MAX || epoch <= 0 || generation < 0 || acknowledgement <= 0 {
        return response(env, "{\"schema\":\"rusty.quest.native_renderer_retirement.v1\",\"status\":\"error\",\"reason_code\":\"terminal-jni-scope-invalid\"}".to_owned());
    }
    match env.with_env(|env| -> jni::errors::Result<jstring> {
        let operation_id = operation.try_to_string(env)?;
        if operation_id.len() > MAX_TRANSPORT_TEXT_BYTES {
            return env.new_string("{\"schema\":\"rusty.quest.native_renderer_retirement.v1\",\"status\":\"error\",\"reason_code\":\"terminal-jni-text-too-large\"}")
                .map(|s| s.into_raw());
        }
        let wire = experiment_session_runtime::renderer_retirement_json(RendererRetirementScope {
            owner_identity: owner as u64, runtime_epoch: epoch as u64, generation: generation as u64,
            operation_id, shutdown_ack_revision: acknowledgement as u64,
        }, request);
        env.new_string(wire).map(|s| s.into_raw())
    }).into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeRequestExperimentSessionRendererRetirement(
    env: EnvUnowned,
    _class: JClass,
    owner: jlong,
    epoch: jlong,
    generation: jlong,
    operation: JString,
    acknowledgement: jlong,
) -> jstring {
    retirement(
        env,
        owner,
        epoch,
        generation,
        operation,
        acknowledgement,
        true,
    )
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeReadExperimentSessionRendererRetirement(
    env: EnvUnowned,
    _class: JClass,
    owner: jlong,
    epoch: jlong,
    generation: jlong,
    operation: JString,
    acknowledgement: jlong,
) -> jstring {
    retirement(
        env,
        owner,
        epoch,
        generation,
        operation,
        acknowledgement,
        false,
    )
}
