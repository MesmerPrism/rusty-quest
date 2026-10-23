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
    prepared_identity: Arc<()>,
}

impl JavaOwnerCallbacks {
    pub(super) fn finalize_prepared_transport(
        &self,
        request: &rusty_quest_media_stream_android::AndroidPreparedStartRequest,
    ) -> Result<(), String> {
        request.validate()?;
        let body = serde_json::to_string(request)
            .map_err(|_| "prepared transport request serialization")?;
        let current = self.fresh_closure_callback("finalizePeerStartTransport", &body)?;
        prepared_registry_readback(&current, request)?;
        if current["state"] != "decoder_ready" || current["delivery_gate_closed"] != true {
            return Err("prepared transport finalization readback differs".into());
        }
        Ok(())
    }
    pub(crate) fn fresh_setup_continuation_binding(&self) -> Result<serde_json::Value, String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "freshSetupContinuationBindingJson",
            "()Ljava/lang/String;",
            &[],
        );
        let result = self
            .checked_call(&mut env, call, "java_bridge.fresh_continuation_binding")?
            .l()
            .map_err(|_| "fresh continuation binding type")?;
        if result.is_null() {
            return Err("fresh continuation binding absent".into());
        }
        let raw: String = env
            .get_string(&JString::from(result))
            .map_err(|_| "fresh continuation binding encoding")?
            .into();
        if raw.len() > 128 * 1024 {
            return Err("fresh continuation binding bounds".into());
        }
        serde_json::from_str(&raw).map_err(|_| "fresh continuation binding JSON".into())
    }
    pub(crate) fn complete_fresh_setup_continuation_handoff(
        &self,
        proof: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("completeFreshSetupContinuationHandoff", proof)
    }
    pub(crate) fn complete_incoming_fresh_setup_handoff(
        &self,
        proof: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("completeIncomingFreshSetupHandoff", proof)
    }
    pub(crate) fn sign_validated_fresh_phase_prepare(
        &self,
        validated: &super::runtime_host::ValidatedFreshPhasePrepareSigning,
    ) -> Result<[u8; 64], String> {
        if validated.key_id() != self.key_id() {
            return Err("fresh preparation selected signing key changed".into());
        }
        self.sign_callback(validated.bytes(), "signValidatedFreshPhasePrepareBytes")
    }
    pub(crate) fn persist_manifold_fresh_history_archive(
        &self,
        raw: &[u8],
    ) -> Result<rusty_manifold_peer::ManifoldFreshHistoryCustodyAck, String> {
        let body = std::str::from_utf8(raw).map_err(|_| "Manifold history canonical UTF8")?;
        // This fixed callback persists/readbacks then constructs its own signed
        // custody context. It is deliberately NOT a generic signing callback.
        let ack = self.fresh_closure_callback("persistManifoldFreshHistoryArchive", body)?;
        serde_json::from_value(ack).map_err(|_| "Manifold history signed custody ACK shape".into())
    }
    pub(crate) fn bind_incoming_fresh_setup_phase(
        &self,
        native_id: &str,
        prior: Option<&str>,
        phase: &str,
        purpose: &str,
    ) -> Result<(), String> {
        super::incoming_fresh_phase::validate(purpose, prior.is_some()).map_err(str::to_owned)?;
        self.capability.require_live()?;
        let mut env = self.attached()?;
        let native = env
            .new_string(native_id)
            .map_err(|_| "incoming fresh native identity")?;
        let previous = prior
            .map(|p| env.new_string(p))
            .transpose()
            .map_err(|_| "incoming fresh prior identity")?;
        let previous = previous
            .map(JObject::from)
            .unwrap_or_else(|| JObject::null());
        let current = env
            .new_string(phase)
            .map_err(|_| "incoming fresh phase identity")?;
        let kind = env
            .new_string(purpose)
            .map_err(|_| "incoming fresh phase purpose")?;
        let call = env.call_method(
            self.callback.as_obj(),
            "bindIncomingFreshSetupPhase",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            &[
                JValue::Object(&JObject::from(native)),
                JValue::Object(&previous),
                JValue::Object(&JObject::from(current)),
                JValue::Object(&JObject::from(kind)),
            ],
        );
        self.checked_call(&mut env, call, "java_bridge.bind_incoming_fresh_phase")?
            .v()
            .map_err(|_| "incoming fresh phase binding result".to_owned())
    }
    pub(crate) fn assert_incoming_fresh_setup_initial_allowed(
        &self,
        native_id: &str,
        phase: &str,
    ) -> Result<(), String> {
        self.capability.require_live()?;
        let mut env = self.attached()?;
        let native = env
            .new_string(native_id)
            .map_err(|_| "incoming admission Native identity")?;
        let phase = env
            .new_string(phase)
            .map_err(|_| "incoming admission phase identity")?;
        let call = env.call_method(
            self.callback.as_obj(),
            "assertIncomingFreshSetupInitialAllowed",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[
                JValue::Object(&JObject::from(native)),
                JValue::Object(&JObject::from(phase)),
            ],
        );
        self.checked_call(&mut env, call, "java_bridge.incoming_initial_admission")?
            .v()
            .map_err(|_| "incoming admission result".to_owned())
    }
    pub(crate) fn bind_fresh_setup_operator_phase(
        &self,
        native_id: &str,
        phase: &str,
    ) -> Result<(), String> {
        self.capability.require_live()?;
        let mut env = self.attached()?;
        let native = env
            .new_string(native_id)
            .map_err(|_| "operator fresh native identity")?;
        let phase = env
            .new_string(phase)
            .map_err(|_| "operator fresh phase identity")?;
        let call = env.call_method(
            self.callback.as_obj(),
            "bindFreshSetupOperatorPhase",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[
                JValue::Object(&JObject::from(native)),
                JValue::Object(&JObject::from(phase)),
            ],
        );
        self.checked_call(&mut env, call, "java_bridge.bind_operator_fresh_phase")?
            .v()
            .map_err(|_| "operator fresh phase binding result".to_owned())
    }
    pub(crate) fn bind_fresh_setup_phase_context(
        &self,
        native_id: &str,
        phase: &str,
        transaction: &str,
        kind: &str,
        context_sha: &str,
    ) -> Result<(), String> {
        if !matches!(kind, "enrollment_preparation" | "pair_setup")
            || !context_sha.strip_prefix("sha256:").is_some_and(|s| {
                s.len() == 64
                    && s.bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            })
        {
            return Err("fresh phase context kind/digest differs".into());
        }
        self.capability.require_live()?;
        let mut env = self.attached()?;
        let native = env
            .new_string(native_id)
            .map_err(|_| "phase context native identity")?;
        let phase = env
            .new_string(phase)
            .map_err(|_| "phase context phase identity")?;
        let tx = env
            .new_string(transaction)
            .map_err(|_| "phase context transaction identity")?;
        let kind = env
            .new_string(kind)
            .map_err(|_| "phase context operation")?;
        let digest = env
            .new_string(context_sha)
            .map_err(|_| "phase context digest")?;
        let call=env.call_method(self.callback.as_obj(),"bindFreshSetupPhaseContext",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&JObject::from(native)),JValue::Object(&JObject::from(phase)),JValue::Object(&JObject::from(tx)),JValue::Object(&JObject::from(kind)),JValue::Object(&JObject::from(digest))]);
        self.checked_call(&mut env, call, "java_bridge.bind_fresh_phase_context")?
            .v()
            .map_err(|_| "fresh phase context binding result".to_owned())
    }

    fn fresh_closure_callback(
        &self,
        method: &str,
        input: &str,
    ) -> Result<serde_json::Value, String> {
        if input.len() > 16 * 1024 * 1024 {
            return Err("fresh closure callback input bounds".into());
        }
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let argument = env
            .new_string(input)
            .map_err(|_| "fresh closure callback input encoding")?;
        let object = JObject::from(argument);
        let call = env.call_method(
            self.callback.as_obj(),
            method,
            "(Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&object)],
        );
        let result = self
            .checked_call(&mut env, call, "java_bridge.fresh_closure")?
            .l()
            .map_err(|_| "fresh closure callback result type")?;
        if result.is_null() {
            return Err("fresh closure callback absent".into());
        }
        let text: String = env
            .get_string(&JString::from(result))
            .map_err(|_| "fresh closure callback encoding")?
            .into();
        if text.len() > 128 * 1024 {
            return Err("fresh closure callback result bounds".into());
        }
        serde_json::from_str(&text).map_err(|_| "fresh closure callback result JSON".into())
    }
    pub(crate) fn fresh_setup_physical_retirement(
        &self,
        binding: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("freshSetupPhysicalRetirementJson", binding)
    }
    pub(crate) fn persist_fresh_setup_closure(
        &self,
        body: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("persistFreshSetupClosure", body)
    }
    pub(crate) fn archive_completed_peer_lifetime(
        &self,
        body: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("archiveCompletedPeerLifetime", body)
    }
    pub(crate) fn archive_fresh_setup_phase(
        &self,
        body: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("archiveFreshSetupPhase", body)
    }
    pub(crate) fn persist_native_construction_retirement(
        &self,
        body: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("persistNativeConstructionRetirement", body)
    }
    pub(crate) fn persist_fresh_owner_retirement(
        &self,
        body: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("persistFreshOwnerRetirement", body)
    }
    pub(crate) fn current_operator_original_id(&self) -> Result<String, String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "currentOperatorOriginalId",
            "()Ljava/lang/String;",
            &[],
        );
        let object = self
            .checked_call(&mut env, call, "java_bridge.actual_original_id")?
            .l()
            .map_err(|_| "actual Original result type")?;
        if object.is_null() {
            return Err("actual operator Original absent".into());
        }
        let id: String = env
            .get_string(&JString::from(object))
            .map_err(|_| "actual Original encoding")?
            .into();
        if id.len() != 32
            || !id
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err("actual Original canonical identity".into());
        }
        Ok(id)
    }
    pub(crate) fn bind_fresh_setup_original(&self, id: &str) -> Result<(), String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let id = JObject::from(
            env.new_string(id)
                .map_err(|_| "fresh Original bind input")?,
        );
        let call = env.call_method(
            self.callback.as_obj(),
            "bindFreshSetupOriginal",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&id)],
        );
        self.checked_call(&mut env, call, "java_bridge.fresh_original_bind")?;
        Ok(())
    }
    pub(crate) fn bind_incoming_fresh_setup(&self, id: &str) -> Result<(), String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let id = JObject::from(
            env.new_string(id)
                .map_err(|_| "fresh incoming bind input")?,
        );
        let call = env.call_method(
            self.callback.as_obj(),
            "bindIncomingFreshSetup",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&id)],
        );
        self.checked_call(&mut env, call, "java_bridge.fresh_incoming_bind")?;
        Ok(())
    }
    pub(crate) fn bind_fresh_setup_context(&self, id: &str, digest: &str) -> Result<(), String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let id = JObject::from(env.new_string(id).map_err(|_| "fresh context bind id")?);
        let digest = JObject::from(
            env.new_string(digest)
                .map_err(|_| "fresh context bind digest")?,
        );
        let call = env.call_method(
            self.callback.as_obj(),
            "bindFreshSetupContext",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&id), JValue::Object(&digest)],
        );
        self.checked_call(&mut env, call, "java_bridge.fresh_context_bind")?;
        Ok(())
    }

    // Called only by the private borrowed FreshSetupPhase after exact source/view checks.
    pub(crate) fn sign_validated_fresh_pair_setup(
        &self,
        validated: &super::runtime_host::ValidatedFreshPairSetupSigning,
    ) -> Result<[u8; 64], String> {
        if validated.key_id() != self.key_id {
            return Err("fresh setup actual signer differs".into());
        }
        self.sign_callback(validated.bytes(), "signValidatedFreshPairSetupBytes")
    }
    pub(crate) fn sign_fresh_setup_frame(&self, bytes: &[u8]) -> Result<[u8; 64], String> {
        self.sign_callback(bytes, "signFreshPairSetupFrameBytes")
    }
    pub(crate) fn prepared_start_eligibility(
        &self,
        attempt: &super::runtime_host::preparation_diagnostic::Attempt,
    ) -> Result<serde_json::Value, String> {
        let envelope =
            self.prepared_readonly_callback("peerStartPreparationEligibilityObservationJson")?;
        let fields = envelope
            .as_object()
            .ok_or("prepared eligibility observation shape")?;
        if fields.len() != 2
            || !fields.contains_key("value")
            || !fields.contains_key("failure_guard")
        {
            return Err("prepared eligibility observation shape".into());
        }
        if let Some(name) = envelope["failure_guard"].as_str() {
            if !envelope["value"].is_null() {
                return Err("prepared eligibility observation conflict".into());
            }
            let guard = super::runtime_host::preparation_diagnostic::JavaGuard::parse(name)
                .ok_or("prepared eligibility observation guard")?;
            attempt.java_failure(guard);
            // Same primary error returned when the old callback threw. Only the
            // retained closed diagnostic gains the actual Java guard location.
            return Err("java_bridge.prepared_readonly".into());
        }
        if !envelope["failure_guard"].is_null() || !envelope["value"].is_object() {
            return Err("prepared eligibility observation value".into());
        }
        Ok(envelope["value"].clone())
    }
    pub(crate) fn peer_start_resource_target(&self) -> Result<serde_json::Value, String> {
        self.prepared_readonly_callback("peerStartResourceTargetJson")
    }
    fn prepared_readonly_callback(&self, method: &str) -> Result<serde_json::Value, String> {
        let mut env = self.attached()?;
        let call = env.call_method(self.callback.as_obj(), method, "()Ljava/lang/String;", &[]);
        let result = self
            .checked_call(&mut env, call, "java_bridge.prepared_readonly")?
            .l()
            .map_err(|_| "prepared readonly type")?;
        if result.is_null() {
            return Err("prepared readonly absent".into());
        }
        let raw: String = env
            .get_string(&JString::from(result))
            .map_err(|_| "prepared readonly UTF8")?
            .into();
        if raw.len() > 128 * 1024 {
            return Err("prepared readonly bounds".into());
        }
        serde_json::from_str(&raw).map_err(|_| "prepared readonly JSON".into())
    }
    pub(crate) fn sign_prepared_start_frame(&self, bytes: &[u8]) -> Result<[u8; 64], String> {
        self.sign_callback(bytes, "signPreparedPeerStartFrameBytes")
    }
    pub(crate) fn incoming_prepared_start_stop_requested(&self) -> Result<bool, String> {
        let mut env = self.attached()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "incomingPreparedStartStopRequested",
            "()Z",
            &[],
        );
        self.checked_call(&mut env, call, "java_bridge.prepared_incoming_stop")?
            .z()
            .map_err(|_| "prepared incoming actual Stop type".into())
    }
    pub(crate) fn incoming_prepared_start_stop_sequence(&self) -> Result<i64, String> {
        let mut env = self.attached()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "incomingPreparedStartStopSequence",
            "()J",
            &[],
        );
        self.checked_call(
            &mut env,
            call,
            "java_bridge.prepared_incoming_stop_sequence",
        )?
        .j()
        .map_err(|_| "prepared incoming Stop sequence type".into())
    }
    pub(crate) fn incoming_prepared_start_stop_requested_for(
        &self,
        remote: &str,
        sequence: i64,
        retired: bool,
    ) -> Result<bool, String> {
        let mut env = self.attached()?;
        let id = env
            .new_string(remote)
            .map_err(|_| "prepared incoming remote ID")?;
        let call = env.call_method(
            self.callback.as_obj(),
            "incomingPreparedStartStopRequestedFor",
            "(Ljava/lang/String;JZ)Z",
            &[
                JValue::Object(id.as_ref()),
                JValue::Long(sequence),
                JValue::Bool(u8::from(retired)),
            ],
        );
        self.checked_call(&mut env, call, "java_bridge.prepared_incoming_scoped_stop")?
            .z()
            .map_err(|_| "prepared incoming scoped Stop type".into())
    }
    pub(crate) fn bind_incoming_prepared_start(
        &self,
        remote: &str,
        local: &str,
    ) -> Result<(), String> {
        let mut env = self.attached()?;
        let remote = JObject::from(
            env.new_string(remote)
                .map_err(|_| "prepared remote identity")?,
        );
        let local = JObject::from(
            env.new_string(local)
                .map_err(|_| "prepared local identity")?,
        );
        let call = env.call_method(
            self.callback.as_obj(),
            "bindIncomingPreparedPeerStart",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&remote), JValue::Object(&local)],
        );
        self.checked_call(&mut env, call, "java_bridge.prepared_incoming_bind")?
            .v()
            .map_err(|_| "prepared incoming bind type".into())
    }
    pub(crate) fn abort_prepared_peer_start(
        &self,
        request: &rusty_quest_media_stream_android::AndroidPreparedAbortRequest,
    ) -> Result<rusty_quest_media_stream_android::AndroidPreparedAbortReadback, String> {
        let body = serde_json::to_string(request).map_err(|_| "prepared abort serialization")?;
        serde_json::from_value(
            self.fresh_closure_callback("abortPreparedPeerStartResources", &body)?,
        )
        .map_err(|_| "prepared actual abort readback".into())
    }
    pub(crate) fn prepared_registry_status(&self, id: &str) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("preparedPeerStartStatusJson", id)
    }
    pub(crate) fn archive_completed_prepared_start(
        &self,
        body: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("archiveCompletedPreparedPeerStart", body)
    }
    pub(crate) fn retain_unknown_peer_root_flight(
        &self,
        body: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("retainUnknownPeerRootFlight", body)
    }
    pub(crate) fn retain_completed_peer_root_outcome(
        &self,
        body: &str,
    ) -> Result<serde_json::Value, String> {
        self.fresh_closure_callback("retainCompletedPeerRootOutcome", body)
    }
    pub(crate) fn peer_root_desired_stop_requested(&self) -> Result<bool, String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "peerRootDesiredStopRequested",
            "()Z",
            &[],
        );
        self.checked_call(&mut env, call, "java_bridge.peer_root_desired_stop")?
            .z()
            .map_err(|_| "root DesiredStop type".into())
    }
    pub(crate) fn peer_root_eligibility(&self) -> Result<serde_json::Value, String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "peerRootReestablishmentEligibilityJson",
            "()Ljava/lang/String;",
            &[],
        );
        let object = self
            .checked_call(&mut env, call, "java_bridge.peer_root_eligibility")?
            .l()
            .map_err(|_| "root eligibility type")?;
        if object.is_null() {
            return Err("root eligibility absent".into());
        }
        let text: String = env
            .get_string(&JString::from(object))
            .map_err(|_| "root eligibility encoding")?
            .into();
        if text.len() > 128 * 1024 {
            return Err("root eligibility bounds".into());
        }
        serde_json::from_str(&text).map_err(|_| "root eligibility JSON".into())
    }
    pub(crate) fn fresh_setup_physical_eligibility(&self) -> Result<serde_json::Value, String> {
        let mut env = self.attached()?;
        self.capability.require_live()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "freshSetupPhysicalEligibilityJson",
            "()Ljava/lang/String;",
            &[],
        );
        let object = self
            .checked_call(&mut env, call, "java_bridge.fresh_setup_eligibility")?
            .l()
            .map_err(|_| "java_bridge.fresh_setup_eligibility_type")?;
        if object.is_null() {
            return Err("fresh setup physical observation absent".into());
        }
        let text: String = env
            .get_string(&JString::from(object))
            .map_err(|_| "fresh setup physical observation encoding")?
            .into();
        if text.len() > 128 * 1024 {
            return Err("fresh setup physical observation bounds".into());
        }
        serde_json::from_str(&text).map_err(|_| "fresh setup physical observation JSON".into())
    }

    pub(crate) fn fresh_setup_initial_physical(
        &self,
        native: &str,
        phase: &str,
    ) -> Result<serde_json::Value, String> {
        self.incoming_physical(
            "freshSetupInitialPhysicalEligibilityJson",
            native,
            phase,
            None,
        )
    }
    pub(crate) fn fresh_setup_registered_physical(
        &self,
        native: &str,
        phase: &str,
    ) -> Result<serde_json::Value, String> {
        self.incoming_physical(
            "freshSetupPhysicalEligibilityForIncomingJson",
            native,
            phase,
            None,
        )
    }
    pub(crate) fn fresh_setup_preparation_physical(
        &self,
        native: &str,
        prior: Option<&str>,
        phase: &str,
    ) -> Result<serde_json::Value, String> {
        self.incoming_physical(
            "freshSetupPreparationPhysicalEligibilityJson",
            native,
            phase,
            Some(prior),
        )
    }
    // Called only by the three fixed typed wrappers above; no caller-selected method or JSON assertion.
    fn incoming_physical(
        &self,
        method: &'static str,
        native: &str,
        phase: &str,
        preparation: Option<Option<&str>>,
    ) -> Result<serde_json::Value, String> {
        let mut env = self.attached()?;
        let n = env
            .new_string(native)
            .map_err(|_| "incoming physical native identity")?;
        let p = env
            .new_string(phase)
            .map_err(|_| "incoming physical phase identity")?;
        let n = JObject::from(n);
        let p = JObject::from(p);
        let prior = preparation
            .flatten()
            .map(|v| env.new_string(v))
            .transpose()
            .map_err(|_| "incoming physical prior identity")?
            .map(JObject::from)
            .unwrap_or_else(|| JObject::null());
        self.capability.require_live()?;
        let call = if preparation.is_some() {
            env.call_method(
                self.callback.as_obj(),
                method,
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                &[
                    JValue::Object(&n),
                    JValue::Object(&prior),
                    JValue::Object(&p),
                ],
            )
        } else {
            env.call_method(
                self.callback.as_obj(),
                method,
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&n), JValue::Object(&p)],
            )
        };
        let object = self
            .checked_call(&mut env, call, "java_bridge.incoming_physical")?
            .l()
            .map_err(|_| "incoming physical result type")?;
        if object.is_null() {
            return Err("incoming physical observation absent".into());
        }
        let text: String = env
            .get_string(&JString::from(object))
            .map_err(|_| "incoming physical encoding")?
            .into();
        if text.len() > 128 * 1024 {
            return Err("incoming physical bounds".into());
        }
        serde_json::from_str(&text).map_err(|_| "incoming physical JSON".into())
    }

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
            prepared_identity: Arc::new(()),
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
    fn current_executor_generation(&self) -> Result<u64, String> {
        self.capability.require_live()?;
        Ok(self.capability.generation)
    }

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
        parse_owner_failure_diagnostic(&self.owner_failure_diagnostic_text()?)
    }
    pub(crate) fn owner_failure_diagnostic_read(&self) -> serde_json::Value {
        let mut observation = match self.owner_failure_diagnostic_text() {
            Ok(text) => {
                super::owner_diagnostic_read::snapshot(&text, parse_owner_failure_diagnostic(&text))
            }
            Err(error) => super::owner_diagnostic_read::unavailable(&error),
        };
        observation["transport_attempt"] = self.owner_transport_attempt_read();
        observation["latest_attempt"] = match self.owner_latest_attempt_text() {
            Ok(text) => super::owner_latest_attempt_read::snapshot(&text),
            Err(_) => super::owner_latest_attempt_read::unavailable(),
        };
        observation
    }
    pub(crate) fn owner_transport_attempt_read(&self) -> serde_json::Value {
        match self.owner_transport_attempt_text() {
            Ok(text) => super::owner_transport_attempt_read::snapshot(&text),
            Err(_) => super::owner_transport_attempt_read::unavailable(),
        }
    }
    fn owner_transport_attempt_text(&self) -> Result<String, String> {
        let mut env = self.attached()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "ownerTransportAttemptDiagnostic",
            "()Ljava/lang/String;",
            &[],
        );
        let value = self
            .checked_call(&mut env, call, "java_bridge.owner_transport_attempt_call")?
            .l()
            .map_err(|_| "java_bridge.owner_transport_attempt_type")?;
        if value.is_null() {
            return Err("java_bridge.owner_transport_attempt_null".into());
        }
        let text: String = env
            .get_string(&JString::from(value))
            .map_err(|_| "java_bridge.owner_transport_attempt_string")?
            .into();
        if text.len() > 256 {
            return Err("java_bridge.owner_transport_attempt_bounds".into());
        }
        Ok(text)
    }
    fn owner_latest_attempt_text(&self) -> Result<String, String> {
        let mut env = self.attached()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "ownerLatestAttemptDiagnostic",
            "()Ljava/lang/String;",
            &[],
        );
        let value = self
            .checked_call(&mut env, call, "java_bridge.owner_latest_attempt_call")?
            .l()
            .map_err(|_| "java_bridge.owner_latest_attempt_type")?;
        if value.is_null() {
            return Err("java_bridge.owner_latest_attempt_null".into());
        }
        let text: String = env
            .get_string(&JString::from(value))
            .map_err(|_| "java_bridge.owner_latest_attempt_string")?
            .into();
        if text.len() > 512 {
            return Err("java_bridge.owner_latest_attempt_bounds".into());
        }
        Ok(text)
    }
    fn owner_failure_diagnostic_text(&self) -> Result<String, String> {
        let mut env = self.attached()?;
        let call = env.call_method(
            self.callback.as_obj(),
            "ownerFailureDiagnostic",
            "()Ljava/lang/String;",
            &[],
        );
        let value = self
            .checked_call(&mut env, call, "java_bridge.owner_diagnostic_call")?
            .l()
            .map_err(|_| "java_bridge.owner_diagnostic_type")?;
        if value.is_null() {
            return Err("java_bridge.owner_diagnostic_null".into());
        }
        let text: String = env
            .get_string(&JString::from(value))
            .map_err(|_| "java_bridge.owner_diagnostic_string")?
            .into();
        Ok(text)
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
    if text.len() > 256 {
        return Err("java_bridge.owner_diagnostic_bounds".into());
    }
    let value: serde_json::Value =
        serde_json::from_str(text).map_err(|_| "java_bridge.owner_diagnostic_json")?;
    let stage = value
        .get("stage")
        .and_then(|v| v.as_str())
        .ok_or("java_bridge.owner_diagnostic_stage")?;
    let sink = value
        .get("sink_stage")
        .and_then(|v| v.as_str())
        .ok_or("java_bridge.owner_diagnostic_sink")?;
    let action = value
        .get("action")
        .and_then(|v| v.as_str())
        .ok_or("java_bridge.owner_diagnostic_action")?;
    let code = value
        .get("code")
        .and_then(|v| v.as_str())
        .ok_or("java_bridge.owner_diagnostic_code")?;
    // Accept retained four/five-field diagnostics and the reviewed seven-field owner/cause producer.
    // This diagnostic compatibility is not a media/authority contract migration.
    let fields = value
        .as_object()
        .ok_or("java_bridge.owner_diagnostic_closed_values")?;
    let reason = if matches!(fields.len(), 5 | 7) {
        Some(
            value
                .get("provider_reason")
                .and_then(|v| v.as_str())
                .ok_or("java_bridge.owner_diagnostic_reason")?,
        )
    } else if fields.len() == 4 {
        None
    } else {
        return Err("java_bridge.owner_diagnostic_closed_values".into());
    };
    if fields.len() == 7 {
        let owner = value
            .get("owner")
            .and_then(|v| v.as_str())
            .ok_or("java_bridge.owner_diagnostic_owner")?;
        let cause = value
            .get("cause")
            .and_then(|v| v.as_str())
            .ok_or("java_bridge.owner_diagnostic_cause")?;
        if !matches!(
            owner,
            "NONE" | "source" | "processor" | "route" | "socket" | "codec" | "cleanup" | "sink"
        ) || !matches!(
            cause,
            "NONE" | "CODEC" | "TIMEOUT" | "IO" | "SECURITY" | "ARGUMENT" | "STATE" | "OTHER"
        ) || (stage == "NONE" && owner != "NONE")
            || (stage != "PROVIDER_EXECUTION" && cause != "NONE")
            || (stage == "PROVIDER_EXECUTION" && cause == "NONE")
        {
            return Err("java_bridge.owner_diagnostic_closed_values".into());
        }
    }
    if reason.is_some_and(|r| {
        let incoming = matches!(
            r,
            "INCOMING_ARM_ORDER"
                | "INCOMING_ARM_PROJECTION"
                | "INCOMING_ARM_EVIDENCE"
                | "INCOMING_ARM_UNAVAILABLE"
        );
        if incoming {
            return stage != "INCOMING_ARM_VERIFICATION"
                || fields.len() != 7
                || !(action == "ARM_RECEIVER"
                    || (action == "STOP"
                        && matches!(r, "INCOMING_ARM_PROJECTION" | "INCOMING_ARM_EVIDENCE")))
                || value.get("owner").and_then(|v| v.as_str()) != Some("sink");
        }
        !matches!(
            r,
            "NONE"
                | "TICKET_PARSE"
                | "STALE_GENERATION"
                | "UNDECLARED_BINDING"
                | "REGISTRY_CLOSED"
                | "PROVIDER_BUSY"
                | "CAPACITY"
                | "PREPARATION_ALREADY_ATTEMPTED"
                | "FOREIGN_READBACK"
                | "RECEIPT_COLLISION"
                | "DISPLAY_LOCAL_SHUTDOWN"
                | "DISPLAY_DISPATCH_FENCED"
                | "DISPLAY_TRANSITION_TIMEOUT"
                | "DISPLAY_ADMISSION_REJECTED"
                | "DISPLAY_NATIVE_ACTIVE_EPOCH"
                | "DISPLAY_NATIVE_ACTIVE_STATE"
                | "DISPLAY_NATIVE_BOUNDS"
                | "DISPLAY_NATIVE_CAPTURE"
                | "DISPLAY_NATIVE_CARRIER"
                | "DISPLAY_NATIVE_CLOCK"
                | "DISPLAY_NATIVE_FRAME_ABSENT"
                | "DISPLAY_NATIVE_FRAME_EPOCH"
                | "DISPLAY_NATIVE_FRAME_FUTURE"
                | "DISPLAY_NATIVE_FRAME_STALE"
                | "DISPLAY_NATIVE_INPUT"
                | "DISPLAY_NATIVE_LOCAL"
                | "DISPLAY_NATIVE_PROCESS_EPOCH"
                | "DISPLAY_NATIVE_SOURCE_STATE"
                | "DISPLAY_NATIVE_SUPERSEDED"
                | "DISPLAY_OWN_CAPTURE_STATE"
                | "DISPLAY_OWN_CAPTURE_FRESH"
                | "DISPLAY_OWN_CARRIER_SUPERSEDED"
                | "DISPLAY_NATIVE_SHAPE"
                | "DISPLAY_ROUTING_SUPERSEDED"
                | "OTHER"
        ) || (stage != "PROVIDER_EXECUTION" && r != "NONE")
    }) {
        return Err("java_bridge.owner_diagnostic_closed_values".into());
    }
    if !matches!(
        stage,
        "NONE"
            | "CALLBACK_FENCE"
            | "PROJECTION_BINDING"
            | "REGISTRY_BINDING"
            | "INCOMING_FENCE"
            | "LOCAL_QUIESCENCE"
            | "PROVIDER_EXECUTION"
            | "RECEIPT_VERIFICATION"
            | "INCOMING_ARM_VERIFICATION"
    ) || !matches!(
        sink,
        "NONE"
            | "PEER_PROJECTION"
            | "READER_STAGE"
            | "READER_IDENTITY"
            | "RECEIVER_CREATE"
            | "PROVIDER_GETTER"
            | "PEER_BIND"
            | "RECEIVER_EFFECT"
    ) || !matches!(
        action,
        "NONE" | "ARM_RECEIVER" | "ARM_CLEANUP" | "START" | "STOP" | "CLEANUP" | "BEFORE_TICKET"
    ) || !matches!(code, "NONE" | "OWNER_EFFECT_REJECTED")
        || (stage == "NONE") != (code == "NONE")
    {
        return Err("java_bridge.owner_diagnostic_closed_values".into());
    }
    Ok(value)
}

#[cfg(test)]
mod owner_diagnostic_tests {
    use super::parse_owner_failure_diagnostic;
    fn receipt(reason: &str) -> serde_json::Value {
        serde_json::json!({"stage":"INCOMING_ARM_VERIFICATION","sink_stage":"NONE",
            "action":"ARM_RECEIVER","code":"OWNER_EFFECT_REJECTED","provider_reason":reason,
            "owner":"sink","cause":"NONE"})
    }
    #[test]
    fn incoming_arm_exact_reasons_are_diagnostic_only() {
        for reason in [
            "INCOMING_ARM_ORDER",
            "INCOMING_ARM_PROJECTION",
            "INCOMING_ARM_EVIDENCE",
            "INCOMING_ARM_UNAVAILABLE",
        ] {
            let value = receipt(reason);
            assert_eq!(
                parse_owner_failure_diagnostic(&value.to_string()).unwrap(),
                value
            );
        }
    }
    #[test]
    fn incoming_arm_reasons_cannot_masquerade_as_other_boundaries() {
        for (key, value) in [
            ("stage", "PROVIDER_EXECUTION"),
            ("owner", "source"),
            ("action", "START"),
            ("cause", "STATE"),
            ("provider_reason", "INCOMING_ARM_ORDER;secret"),
        ] {
            let mut damaged = receipt("INCOMING_ARM_ORDER");
            damaged[key] = value.into();
            assert!(parse_owner_failure_diagnostic(&damaged.to_string()).is_err());
        }
        let mut damaged = receipt("INCOMING_ARM_ORDER");
        damaged.as_object_mut().unwrap().remove("owner");
        damaged.as_object_mut().unwrap().remove("cause");
        assert!(parse_owner_failure_diagnostic(&damaged.to_string()).is_err());
    }
    #[test]
    fn terminal_stop_projection_and_evidence_diagnostics_are_not_authority() {
        for reason in ["INCOMING_ARM_PROJECTION", "INCOMING_ARM_EVIDENCE"] {
            let mut value = receipt(reason);
            value["action"] = "STOP".into();
            assert_eq!(
                parse_owner_failure_diagnostic(&value.to_string()).unwrap(),
                value
            );
            for (key, damage) in [
                ("stage", "PROVIDER_EXECUTION"),
                ("owner", "source"),
                ("cause", "STATE"),
                ("action", "CLEANUP"),
            ] {
                let mut damaged = value.clone();
                damaged[key] = damage.into();
                assert!(parse_owner_failure_diagnostic(&damaged.to_string()).is_err());
            }
        }
        for reason in ["INCOMING_ARM_ORDER", "INCOMING_ARM_UNAVAILABLE"] {
            let mut value = receipt(reason);
            value["action"] = "STOP".into();
            assert!(parse_owner_failure_diagnostic(&value.to_string()).is_err());
        }
    }
    #[test]
    fn retained_four_five_seven_field_diagnostics_stay_compatible() {
        let mut value = receipt("NONE");
        assert!(parse_owner_failure_diagnostic(&value.to_string()).is_ok());
        value.as_object_mut().unwrap().remove("owner");
        value.as_object_mut().unwrap().remove("cause");
        assert!(parse_owner_failure_diagnostic(&value.to_string()).is_ok());
        value.as_object_mut().unwrap().remove("provider_reason");
        assert!(parse_owner_failure_diagnostic(&value.to_string()).is_ok());
        value["secret"] = "must not pass".into();
        assert!(parse_owner_failure_diagnostic(&value.to_string()).is_err());
    }
}

impl rusty_quest_media_stream_android::AndroidPreparedResourceExecutor for JavaOwnerCallbacks {
    fn preparation_identity(&self) -> &Arc<()> {
        &self.prepared_identity
    }
    fn prepare_sender(
        &mut self,
        request: &rusty_quest_media_stream_android::AndroidPreparedStartRequest,
    ) -> Result<rusty_quest_media_stream_android::AndroidPreparedSenderReadback, String> {
        request.validate()?;
        let body =
            serde_json::to_string(request).map_err(|_| "prepared sender request serialization")?;
        serde_json::from_value(self.fresh_closure_callback("preparePeerStartResources", &body)?)
            .map_err(|_| "prepared sender readback type".into())
    }
    fn verify_sender(
        &self,
        request: &rusty_quest_media_stream_android::AndroidPreparedStartRequest,
        receipt: &rusty_quest_media_stream_android::AndroidPreparedSenderReadback,
    ) -> Result<(), String> {
        receipt.validate(request)?;
        let current =
            self.fresh_closure_callback("preparedPeerStartStatusJson", &request.preparation_id)?;
        prepared_registry_readback(&current, request)?;
        if current["sender"]
            != serde_json::to_value(receipt).map_err(|_| "prepared sender readback")?
        {
            return Err("prepared actual sender owner changed".into());
        }
        Ok(())
    }
    fn prepare_decoder(
        &mut self,
        request: &rusty_quest_media_stream_android::AndroidPreparedStartRequest,
        decoder: &rusty_quest_media_stream_android::AndroidPreparedDecoderRequest,
    ) -> Result<rusty_quest_media_stream_android::AndroidPreparedDecoderReadback, String> {
        decoder.validate(request)?;
        let body=serde_json::json!({"$schema":"rusty.quest.android.media.prepared_decoder_execution.v1","request":request,"decoder":decoder}).to_string();
        serde_json::from_value(self.fresh_closure_callback("preparePeerStartDecoder", &body)?)
            .map_err(|_| "prepared decoder readback type".into())
    }
    fn verify_decoder(
        &self,
        request: &rusty_quest_media_stream_android::AndroidPreparedStartRequest,
        decoder: &rusty_quest_media_stream_android::AndroidPreparedDecoderRequest,
        receipt: &rusty_quest_media_stream_android::AndroidPreparedDecoderReadback,
    ) -> Result<(), String> {
        receipt.validate(request, decoder)?;
        let current =
            self.fresh_closure_callback("preparedPeerStartStatusJson", &request.preparation_id)?;
        prepared_registry_readback(&current, request)?;
        if current["decoder"]
            != serde_json::to_value(receipt).map_err(|_| "prepared decoder readback")?
        {
            return Err("prepared actual decoder owner changed".into());
        }
        Ok(())
    }
}
fn prepared_registry_readback(
    value: &serde_json::Value,
    r: &rusty_quest_media_stream_android::AndroidPreparedStartRequest,
) -> Result<(), String> {
    const KEYS: [&str; 8] = [
        "$schema",
        "preparation_id",
        "request_sha256",
        "resource_target_sha256",
        "sender",
        "decoder",
        "state",
        "delivery_gate_closed",
    ];
    if !value
        .as_object()
        .is_some_and(|o| o.len() == 8 && KEYS.iter().all(|k| o.contains_key(*k)))
        || value["$schema"] != "rusty.quest.android.media.prepared_start_readback.v1"
        || value["preparation_id"] != r.preparation_id
        || value["request_sha256"] != r.digest()?
        || value["resource_target_sha256"] != r.resource_target_sha256
        || !matches!(
            value["state"].as_str(),
            Some(
                "sender_ready"
                    | "decoder_preparing"
                    | "decoder_ready"
                    | "ready"
                    | "committing"
                    | "active"
            )
        )
    {
        return Err("prepared actual registry owner unavailable or terminal".into());
    }
    Ok(())
}
