use super::common_lan_signing::ValidatedCommonLanSigning;
use super::process_fence::NativeCapability;
use std::sync::Arc;

use jni::objects::{GlobalRef, JByteArray, JObject, JString, JValue};
use jni::{JNIEnv, JavaVM};
use rusty_quest_media_stream_android::{
    AndroidMediaExecutionMode, AndroidMediaExecutionTicket, AuthenticatedOwnerEffect,
    AuthenticatedOwnerRegistry, OwnerDispatchAuthorityProjection, OwnerDispatchReplaySnapshot,
    OwnerDispatchReplayStore, OwnerDispatchSigner, OwnerDispatchTransport, ProductActivationProof,
    ProductActivationReadback, ProductActivationRegistry, ProductActivationReplaySnapshot,
    ProductActivationReplayStore,
};

const MAX_OWNER_FRAME_BYTES: usize = 128 * 1024;
const MAX_AUTHORITY_SIGNING_BYTES: usize = 131_142;
const MAX_EFFECT_JSON_BYTES: usize = 128 * 1024;
const MAX_REPLAY_JSON_BYTES: usize = 16 * 1024 * 1024;

/// App-owned Java callbacks captured for short calls outside native authority locks.
#[derive(Clone)]
pub(crate) struct JavaOwnerCallbacks {
    capability: Arc<NativeCapability>,
    vm: Arc<JavaVM>,
    callback: GlobalRef,
    key_id: String,
    target_peer_id: String,
}

impl JavaOwnerCallbacks {
    pub(crate) fn sign_pair_ceremony(&self, bytes: &[u8]) -> Result<[u8; 64], String> {
        self.sign_callback(bytes, "signPairCeremonyBytes")
    }

    pub(crate) fn local_public_key(&self) -> Result<[u8; 32], String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let call = env.call_method(self.callback.as_obj(), "localPublicKeyBytes", "()[B", &[]);
        let result = self
            .checked_call(&mut env, call, "java_bridge.local_public_key")?
            .l()
            .map_err(|_| "java_bridge.local_public_key_type".to_owned())?;
        if result.is_null() {
            return Err("java_bridge.local_public_key_null".into());
        }
        let array = JByteArray::from(result);
        let bytes_result = env.convert_byte_array(&array);
        self.checked_call(&mut env, bytes_result, "java_bridge.local_public_key_bytes")?
            .try_into()
            .map_err(|_| "java_bridge.local_public_key_length".into())
    }

    pub(crate) fn sign_validated_common_lan(
        &self,
        validated: &ValidatedCommonLanSigning,
    ) -> Result<[u8; 64], String> {
        if validated.signer_key_id != self.key_id {
            return Err("java_bridge.common_lan_signer_identity".into());
        }
        self.sign_callback(&validated.signing_bytes, "signValidatedCommonLanBytes")
    }

    fn sign_callback(&self, message: &[u8], method: &str) -> Result<[u8; 64], String> {
        if message.is_empty() || message.len() > MAX_AUTHORITY_SIGNING_BYTES {
            return Err("java_bridge.sign_input_bounds".to_owned());
        }
        let mut env = self.attached()?;
        let input_result = env.byte_array_from_slice(message);
        let input = self.checked_call(&mut env, input_result, "java_bridge.sign_input")?;
        let input_object = JObject::from(input);
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            method,
            "([B)[B",
            &[JValue::Object(&input_object)],
        );
        let result = self
            .checked_call(&mut env, call, "java_bridge.sign_call")?
            .l()
            .map_err(|_| "java_bridge.sign_type".to_owned())?;
        if result.is_null() {
            return Err("java_bridge.sign_null".to_owned());
        }
        let array = JByteArray::from(result);
        let bytes_result = env.convert_byte_array(&array);
        let bytes = self.checked_call(&mut env, bytes_result, "java_bridge.sign_bytes")?;
        bytes
            .try_into()
            .map_err(|_| "java_bridge.sign_length".to_owned())
    }

    pub(crate) fn capture(
        env: &mut JNIEnv<'_>,
        callback: JObject<'_>,
        key_id: String,
        target_peer_id: String,
        capability: Arc<NativeCapability>,
    ) -> Result<Self, String> {
        if callback.is_null()
            || !valid_key_id(&key_id)
            || !valid_dotted_id(&target_peer_id)
            || target_peer_id.len() > 128
        {
            return Err("java_bridge.identity".to_owned());
        }
        let vm = env
            .get_java_vm()
            .map(Arc::new)
            .map_err(|_| "java_bridge.vm".to_owned())?;
        let global_ref = env.new_global_ref(callback);
        let callback = checked_call(env, global_ref, "java_bridge.global_ref")?;
        capability.require_live()?;
        Ok(Self {
            capability,
            vm,
            callback,
            key_id,
            target_peer_id,
        })
    }

    fn checked_call<'local, T>(
        &self,
        env: &mut JNIEnv<'local>,
        result: jni::errors::Result<T>,
        error_class: &'static str,
    ) -> Result<T, String> {
        let result = checked_call(env, result, error_class);
        self.capability.require_live()?;
        result
    }
    fn attached(&self) -> Result<jni::AttachGuard<'_>, String> {
        self.capability.require_live()?;
        self.vm
            .attach_current_thread()
            .map_err(|_| "java_bridge.attach".to_owned())
    }

    fn execute_and_verify_java(
        &self,
        authority: Option<&OwnerDispatchAuthorityProjection>,
        ticket: &AndroidMediaExecutionTicket,
        compensate: bool,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        self.execute_json_callback(
            "executeAndVerify",
            serde_json::to_string(&authority)
                .map_err(|_| "java_bridge.authority_json".to_owned())?,
            ticket,
            compensate,
        )
    }

    fn execute_json_callback(
        &self,
        method: &str,
        authority_json: String,
        ticket: &AndroidMediaExecutionTicket,
        compensate: bool,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        let authority_json =
            Ok::<_, String>(authority_json).map_err(|_| "java_bridge.authority_json".to_owned())?;
        let ticket_json =
            serde_json::to_string(ticket).map_err(|_| "java_bridge.ticket_json".to_owned())?;
        if authority_json.len() > MAX_EFFECT_JSON_BYTES || ticket_json.len() > MAX_EFFECT_JSON_BYTES
        {
            return Err("java_bridge.execute_input_bounds".to_owned());
        }
        let mut env = self.attached()?;
        let authority_result = env.new_string(authority_json);
        let authority_string =
            self.checked_call(&mut env, authority_result, "java_bridge.authority_string")?;
        let ticket_result = env.new_string(ticket_json);
        let ticket_string =
            self.checked_call(&mut env, ticket_result, "java_bridge.ticket_string")?;
        let authority_object = JObject::from(authority_string);
        let ticket_object = JObject::from(ticket_string);
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            method,
            "(Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;",
            &[
                JValue::Object(&authority_object),
                JValue::Object(&ticket_object),
                JValue::Bool(u8::from(compensate)),
            ],
        );
        let result = self
            .checked_call(&mut env, call, "java_bridge.execute_call")?
            .l()
            .map_err(|_| "java_bridge.execute_type".to_owned())?;
        if result.is_null() {
            return Err("java_bridge.execute_null".to_owned());
        }
        let result_string = JString::from(result);
        let string_result = env.get_string(&result_string);
        let result_json: String = self
            .checked_call(&mut env, string_result, "java_bridge.execute_string")?
            .into();
        if result_json.is_empty() || result_json.len() > MAX_EFFECT_JSON_BYTES {
            return Err("java_bridge.execute_output_bounds".to_owned());
        }
        serde_json::from_str(&result_json).map_err(|_| "java_bridge.execute_json".to_owned())
    }
}

impl AuthenticatedOwnerRegistry for JavaOwnerCallbacks {
    fn execute_and_verify(
        &mut self,
        authority: Option<&OwnerDispatchAuthorityProjection>,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        self.execute_and_verify_java(
            authority,
            ticket,
            matches!(mode, AndroidMediaExecutionMode::CompensateUncertain),
        )
    }
}

impl ProductActivationRegistry for JavaOwnerCallbacks {
    fn activate(
        &mut self,
        activation_id: &str,
        authority: &OwnerDispatchAuthorityProjection,
        proof: &ProductActivationProof,
    ) -> Result<ProductActivationReadback, String> {
        let authority_json = serde_json::to_string(authority)
            .map_err(|_| "java_bridge.activation_authority".to_owned())?;
        let proof_json =
            serde_json::to_string(proof).map_err(|_| "java_bridge.activation_proof".to_owned())?;
        if activation_id.is_empty()
            || activation_id.len() > MAX_EFFECT_JSON_BYTES
            || authority_json.len() > MAX_EFFECT_JSON_BYTES
            || proof_json.len() > MAX_EFFECT_JSON_BYTES
        {
            return Err("java_bridge.activation_bounds".to_owned());
        }
        let mut env = self.attached()?;
        let id_result = env.new_string(activation_id);
        let id = self.checked_call(&mut env, id_result, "java_bridge.activation_id")?;
        let authority_result = env.new_string(authority_json);
        let authority = self.checked_call(
            &mut env,
            authority_result,
            "java_bridge.activation_authority",
        )?;
        let proof_result = env.new_string(proof_json);
        let proof = self.checked_call(&mut env, proof_result, "java_bridge.activation_proof")?;
        let id = JObject::from(id);
        let authority = JObject::from(authority);
        let proof = JObject::from(proof);
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "activateProduct",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            &[
                JValue::Object(&id),
                JValue::Object(&authority),
                JValue::Object(&proof),
            ],
        );
        let result = self
            .checked_call(&mut env, call, "java_bridge.activation_call")?
            .l()
            .map_err(|_| "java_bridge.activation_type".to_owned())?;
        if result.is_null() {
            return Err("java_bridge.activation_null".to_owned());
        }
        let result = JString::from(result);
        let text_result = env.get_string(&result);
        let text: String = self
            .checked_call(&mut env, text_result, "java_bridge.activation_readback")?
            .into();
        if text.is_empty() || text.len() > MAX_EFFECT_JSON_BYTES {
            return Err("java_bridge.activation_output_bounds".to_owned());
        }
        serde_json::from_str(&text).map_err(|_| "java_bridge.activation_json".to_owned())
    }
}

impl OwnerDispatchSigner for JavaOwnerCallbacks {
    fn key_id(&self) -> &str {
        &self.key_id
    }

    fn sign(&self, message: &[u8]) -> Result<[u8; 64], String> {
        self.sign_callback(message, "signAuthorityBytes")
    }
}

impl OwnerDispatchTransport for JavaOwnerCallbacks {
    fn exchange(
        &mut self,
        request_frame: &[u8],
        max_response_bytes: usize,
    ) -> Result<Vec<u8>, String> {
        if request_frame.is_empty()
            || request_frame.len() > MAX_OWNER_FRAME_BYTES
            || max_response_bytes == 0
            || max_response_bytes > MAX_OWNER_FRAME_BYTES
        {
            return Err("java_bridge.exchange_input_bounds".to_owned());
        }
        let mut env = self.attached()?;
        let target_result = env.new_string(&self.target_peer_id);
        let target = self.checked_call(&mut env, target_result, "java_bridge.exchange_target")?;
        let frame_result = env.byte_array_from_slice(request_frame);
        let frame = self.checked_call(&mut env, frame_result, "java_bridge.exchange_frame")?;
        let target_object = JObject::from(target);
        let frame_object = JObject::from(frame);
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "exchangeOwnerFrame",
            "(Ljava/lang/String;[B)[B",
            &[
                JValue::Object(&target_object),
                JValue::Object(&frame_object),
            ],
        );
        let result = self
            .checked_call(&mut env, call, "java_bridge.exchange_call")?
            .l()
            .map_err(|_| "java_bridge.exchange_type".to_owned())?;
        if result.is_null() {
            return Err("java_bridge.exchange_null".to_owned());
        }
        let array = JByteArray::from(result);
        let bytes_result = env.convert_byte_array(&array);
        let bytes = self.checked_call(&mut env, bytes_result, "java_bridge.exchange_bytes")?;
        if bytes.is_empty() || bytes.len() > max_response_bytes {
            return Err("java_bridge.exchange_output_bounds".to_owned());
        }
        Ok(bytes)
    }
}

impl OwnerDispatchReplayStore for JavaOwnerCallbacks {
    fn commit(&mut self, snapshot: &OwnerDispatchReplaySnapshot) -> Result<(), String> {
        let snapshot_json =
            serde_json::to_string(snapshot).map_err(|_| "java_bridge.replay_json".to_owned())?;
        self.persist_replay("persistDispatchReplay", snapshot_json)
    }
}

impl ProductActivationReplayStore for JavaOwnerCallbacks {
    fn commit(&mut self, snapshot: &ProductActivationReplaySnapshot) -> Result<(), String> {
        let snapshot_json = serde_json::to_string(snapshot)
            .map_err(|_| "java_bridge.activation_replay_json".to_owned())?;
        self.persist_replay("persistActivationReplay", snapshot_json)
    }
}

impl JavaOwnerCallbacks {
    fn persist_replay(&self, method: &str, snapshot_json: String) -> Result<(), String> {
        if snapshot_json.len() > MAX_REPLAY_JSON_BYTES {
            return Err("java_bridge.replay_bounds".to_owned());
        }
        let mut env = self.attached()?;
        let snapshot_result = env.new_string(snapshot_json);
        let snapshot_string =
            self.checked_call(&mut env, snapshot_result, "java_bridge.replay_string")?;
        let snapshot_object = JObject::from(snapshot_string);
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            method,
            "(Ljava/lang/String;)V",
            &[JValue::Object(&snapshot_object)],
        );
        self.checked_call(&mut env, call, "java_bridge.replay_call")?;
        Ok(())
    }
}

fn checked_call<'local, T>(
    env: &mut JNIEnv<'local>,
    result: jni::errors::Result<T>,
    error_class: &'static str,
) -> Result<T, String> {
    match result {
        Ok(value) => match env.exception_check() {
            Ok(false) => Ok(value),
            Ok(true) => {
                let _ = env.exception_clear();
                Err(format!("{error_class}.exception"))
            }
            Err(_) => Err(format!("{error_class}.exception_state")),
        },
        Err(_) => {
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
            Err(error_class.to_owned())
        }
    }
}

fn valid_key_id(value: &str) -> bool {
    value.len() == 72
        && value.starts_with("ed25519.")
        && value.as_bytes()[8..]
            .iter()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(byte))
}

fn valid_dotted_id(value: &str) -> bool {
    !value.is_empty()
        && value.split('.').all(|segment| {
            let bytes = segment.as_bytes();
            !bytes.is_empty()
                && is_dotted_edge(bytes[0])
                && is_dotted_edge(bytes[bytes.len() - 1])
                && bytes
                    .iter()
                    .all(|byte| is_dotted_edge(*byte) || *byte == b'_' || *byte == b'-')
        })
}

fn is_dotted_edge(value: u8) -> bool {
    value.is_ascii_lowercase() || value.is_ascii_digit()
}

impl rusty_quest_media_stream_android::RetainedCleanupRegistry for JavaOwnerCallbacks {
    fn execute_and_verify(
        &mut self,
        authority: &rusty_quest_media_stream_android::RetainedCleanupAuthorityProjection,
        target: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        self.execute_json_callback(
            "executeRetainedCleanupAndVerify",
            serde_json::to_string(authority).map_err(|_| "cleanup authority encode")?,
            target,
            mode == AndroidMediaExecutionMode::CompensateUncertain,
        )
    }
}
impl rusty_quest_media_stream_android::RetainedCleanupReplayStore for JavaOwnerCallbacks {
    fn commit(
        &mut self,
        snapshot: &rusty_quest_media_stream_android::RetainedCleanupReplaySnapshot,
    ) -> Result<(), String> {
        self.persist_replay(
            "persistRetainedCleanupReplay",
            serde_json::to_string(snapshot).map_err(|_| "cleanup replay encode")?,
        )
    }
}
impl JavaOwnerCallbacks {
    pub(crate) fn persist_cleanup_preparations(&self, text: &str) -> Result<(), String> {
        self.persist_replay("persistCleanupPreparations", text.to_owned())
    }
    pub(crate) fn owner_failure_diagnostic(&self) -> Result<serde_json::Value, String> {
        let mut env = self.attached()?;
        let call = env.call_method(self.callback.as_obj(), "ownerFailureDiagnostic", "()Ljava/lang/String;", &[]);
        let value = self.checked_call(&mut env, call, "java_bridge.owner_diagnostic_call")?
            .l().map_err(|_| "java_bridge.owner_diagnostic_type")?;
        if value.is_null() { return Err("java_bridge.owner_diagnostic_null".into()); }
        let text: String = env.get_string(&JString::from(value)).map_err(|_| "java_bridge.owner_diagnostic_string")?.into();
        parse_owner_failure_diagnostic(&text)
    }
    pub(crate) fn load_cleanup_preparations(&self) -> Result<String, String> {
        self.load_bounded_cleanup_string("loadCleanupPreparations")
    }
    pub(crate) fn load_retained_cleanup_replay(&self) -> Result<String, String> {
        self.load_bounded_cleanup_string("loadRetainedCleanupReplay")
    }
    fn load_bounded_cleanup_string(&self, method: &str) -> Result<String, String> {
        let mut env = self.attached()?;
        let call = env.call_method(self.callback.as_obj(), method, "()Ljava/lang/String;", &[]);
        let object = self
            .checked_call(&mut env, call, "cleanup replay load")?
            .l()
            .map_err(|_| "cleanup replay type")?;
        if object.is_null() {
            return Err("cleanup replay absent".into());
        }
        let value = JString::from(object);
        let result = env.get_string(&value);
        let text: String = self
            .checked_call(&mut env, result, "cleanup replay string")?
            .into();
        if text.len() > MAX_REPLAY_JSON_BYTES {
            return Err("cleanup replay bounds".into());
        }
        Ok(text)
    }
}

fn parse_owner_failure_diagnostic(text: &str) -> Result<serde_json::Value, String> {
    if text.len() > 256 { return Err("java_bridge.owner_diagnostic_bounds".into()); }
    let value: serde_json::Value = serde_json::from_str(text).map_err(|_| "java_bridge.owner_diagnostic_json")?;
    let stage = value.get("stage").and_then(|v|v.as_str()).ok_or("java_bridge.owner_diagnostic_stage")?;
    let sink = value.get("sink_stage").and_then(|v|v.as_str()).ok_or("java_bridge.owner_diagnostic_sink")?;
    let action = value.get("action").and_then(|v|v.as_str()).ok_or("java_bridge.owner_diagnostic_action")?;
    let code = value.get("code").and_then(|v|v.as_str()).ok_or("java_bridge.owner_diagnostic_code")?;
    // Accept retained four/five-field diagnostics and the reviewed seven-field owner/cause producer.
    // This diagnostic compatibility is not a media/authority contract migration.
    let fields = value.as_object().ok_or("java_bridge.owner_diagnostic_closed_values")?;
    let reason = if matches!(fields.len(), 5 | 7) {
        Some(value.get("provider_reason").and_then(|v|v.as_str()).ok_or("java_bridge.owner_diagnostic_reason")?)
    } else if fields.len() == 4 { None } else { return Err("java_bridge.owner_diagnostic_closed_values".into()); };
    if fields.len() == 7 {
        let owner = value.get("owner").and_then(|v|v.as_str()).ok_or("java_bridge.owner_diagnostic_owner")?;
        let cause = value.get("cause").and_then(|v|v.as_str()).ok_or("java_bridge.owner_diagnostic_cause")?;
        if !matches!(owner,"NONE"|"source"|"processor"|"route"|"socket"|"codec"|"cleanup"|"sink")
            || !matches!(cause,"NONE"|"CODEC"|"TIMEOUT"|"IO"|"SECURITY"|"ARGUMENT"|"STATE"|"OTHER")
            || (stage == "NONE" && owner != "NONE")
            || (stage != "PROVIDER_EXECUTION" && cause != "NONE")
            || (stage == "PROVIDER_EXECUTION" && cause == "NONE") {
            return Err("java_bridge.owner_diagnostic_closed_values".into());
        }
    }
    if reason.is_some_and(|r| !matches!(r,"NONE"|"TICKET_PARSE"|"STALE_GENERATION"|"UNDECLARED_BINDING"|"REGISTRY_CLOSED"|"PROVIDER_BUSY"|"CAPACITY"|"PREPARATION_ALREADY_ATTEMPTED"|"FOREIGN_READBACK"|"RECEIPT_COLLISION"|"DISPLAY_LOCAL_SHUTDOWN"|"DISPLAY_DISPATCH_FENCED"|"DISPLAY_TRANSITION_TIMEOUT"|"DISPLAY_ADMISSION_REJECTED"|"DISPLAY_NATIVE_ACTIVE_EPOCH"|"DISPLAY_NATIVE_ACTIVE_STATE"|"DISPLAY_NATIVE_BOUNDS"|"DISPLAY_NATIVE_CAPTURE"|"DISPLAY_NATIVE_CARRIER"|"DISPLAY_NATIVE_CLOCK"|"DISPLAY_NATIVE_FRAME_ABSENT"|"DISPLAY_NATIVE_FRAME_EPOCH"|"DISPLAY_NATIVE_FRAME_FUTURE"|"DISPLAY_NATIVE_FRAME_STALE"|"DISPLAY_NATIVE_INPUT"|"DISPLAY_NATIVE_LOCAL"|"DISPLAY_NATIVE_PROCESS_EPOCH"|"DISPLAY_NATIVE_SOURCE_STATE"|"DISPLAY_NATIVE_SUPERSEDED"|"DISPLAY_OWN_CAPTURE_STATE"|"DISPLAY_OWN_CAPTURE_FRESH"|"DISPLAY_OWN_CARRIER_SUPERSEDED"|"DISPLAY_NATIVE_SHAPE"|"DISPLAY_ROUTING_SUPERSEDED"|"OTHER")
        || (stage != "PROVIDER_EXECUTION" && r != "NONE")) { return Err("java_bridge.owner_diagnostic_closed_values".into()); }
    if !matches!(stage,"NONE"|"CALLBACK_FENCE"|"PROJECTION_BINDING"|"REGISTRY_BINDING"|"INCOMING_FENCE"|"LOCAL_QUIESCENCE"|"PROVIDER_EXECUTION"|"RECEIPT_VERIFICATION"|"INCOMING_ARM_VERIFICATION")
        || !matches!(sink,"NONE"|"PEER_PROJECTION"|"READER_STAGE"|"READER_IDENTITY"|"RECEIVER_CREATE"|"PROVIDER_GETTER"|"PEER_BIND"|"RECEIVER_EFFECT")
        || !matches!(action,"NONE"|"ARM_RECEIVER"|"ARM_CLEANUP"|"START"|"STOP"|"CLEANUP"|"BEFORE_TICKET")
        || !matches!(code,"NONE"|"OWNER_EFFECT_REJECTED")
        || (stage=="NONE") != (code=="NONE") {return Err("java_bridge.owner_diagnostic_closed_values".into());}
    Ok(value)
}
