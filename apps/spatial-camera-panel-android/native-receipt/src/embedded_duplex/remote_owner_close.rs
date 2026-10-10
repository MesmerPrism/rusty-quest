//! Closure of current-process remote receiver effects, never a local media Stop.
use ed25519_dalek::{Signature, VerifyingKey};
use rusty_quest_broker_authority::packaged_json_sha256;
use rusty_quest_media_stream::MediaStreamOwnerKind;
use rusty_quest_media_stream_android::{
    decode_signature_base64, product_activation_response_signing_bytes, response_signing_bytes,
    AndroidMediaOwnerReadback, MediaStreamOwnerActionKind, MediaStreamPlatformOperation,
    OwnerDispatchReplaySnapshot, OwnerDispatchStatus, ProductActivationReplaySnapshot,
    ANDROID_MEDIA_READBACK_SCHEMA, MAX_OWNER_DISPATCH_REPLAYS, OWNER_DISPATCH_RESPONSE_SCHEMA,
    PRODUCT_ACTIVATION_RESPONSE_SCHEMA, VERIFIED_OWNER_EFFECT_SCHEMA,
};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

/// Only current, unrestored server slots may supply these records. Physical
/// resource/Own/renderer barriers are separately mandatory before host removal.
pub(crate) fn verified_receivers(
    replay: &OwnerDispatchReplaySnapshot,
    activation: &ProductActivationReplaySnapshot,
    public_key: &[u8; 32],
    key_id: &str,
    executor_generation: u64,
    restored: bool,
    local_media_attempted: bool,
) -> Result<Value, String> {
    if restored || local_media_attempted || executor_generation == 0 || key_id.is_empty()
        || !replay.pending_request_sha256.is_empty()
        || !activation.pending_request_sha256.is_empty()
        || replay.terminal.is_empty() || replay.terminal.len() > MAX_OWNER_DISPATCH_REPLAYS
    {
        return Err("remote-owner closure requires current complete receiver history".into());
    }
    let key = VerifyingKey::from_bytes(public_key).map_err(|_| "remote-owner closure key")?;
    let mut groups: BTreeMap<(String, String, String, String, String), BTreeMap<u64, (bool, String)>> = BTreeMap::new();
    let mut ids = BTreeSet::new();
    for (id, response) in &replay.terminal {
        if response.schema_id != OWNER_DISPATCH_RESPONSE_SCHEMA || &response.dispatch_id != id
            || response.signer_key_id != key_id || response.status != OwnerDispatchStatus::Completed
            || response.failure.is_some() || response.request_sha256.len() != 71
            || !response.request_sha256.starts_with("sha256:")
            || !response.request_sha256[7..].bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        { return Err("remote-owner terminal response identity".into()); }
        key.verify_strict(&response_signing_bytes(response)?,
            &Signature::from_bytes(&decode_signature_base64(&response.signature_base64)?))
            .map_err(|_| "remote-owner terminal signature")?;
        let effect = response.effect.as_ref().ok_or("remote-owner terminal effect absent")?;
        let raw: AndroidMediaOwnerReadback = serde_json::from_str(&effect.readback_json)
            .map_err(|_| "remote-owner readback JSON")?;
        let r = &effect.readback;
        let v = &effect.verified;
        if raw != *r || r.schema_id != ANDROID_MEDIA_READBACK_SCHEMA
            || r.owner_kind != MediaStreamOwnerKind::Sink || r.provider_kind != "meta_spatial_sdk_packed_stereo_sink"
            || r.executor_generation != executor_generation || r.provider_state_revision == 0
            || r.provider_handle_id.is_empty() || r.resource_id.is_empty() || r.authority_epoch_id.is_empty()
            || r.client_id.is_empty() || r.lease_id.is_empty() || r.receipt_id.is_empty()
            || v.schema_id != VERIFIED_OWNER_EFFECT_SCHEMA
            || v.readback_sha256 != format!("sha256:{}", packaged_json_sha256(&effect.readback_json))
            || v.receipt_id != r.receipt_id || v.executor_generation != r.executor_generation
            || v.provider_state_revision != r.provider_state_revision || v.observed_state != r.observed_state
            || v.provider_handle_id != r.provider_handle_id || !ids.insert(r.receipt_id.clone())
        { return Err("remote-owner authenticated registry readback differs".into()); }
        let stopped = match (r.operation, r.action_kind, r.observed_state.as_str(), v.terminal) {
            (MediaStreamPlatformOperation::Start, MediaStreamOwnerActionKind::ArmReceiver, "receiver_armed", false) => false,
            (MediaStreamPlatformOperation::Stop, MediaStreamOwnerActionKind::Stop, "stopped", true) => true,
            _ => return Err("remote-owner receiver state is not a completed Start or Stop".into()),
        };
        let group = groups.entry((r.authority_epoch_id.clone(), r.client_id.clone(), r.lease_id.clone(), r.resource_id.clone(), r.provider_handle_id.clone())).or_default();
        if group.insert(r.provider_state_revision, (stopped, r.receipt_id.clone())).is_some() {
            return Err("remote-owner duplicate provider revision".into());
        }
    }
    let mut stops = Vec::new();
    for history in groups.values() {
        let (_, last) = history.last_key_value().ok_or("remote-owner history absent")?;
        if !last.0 || !history.values().any(|entry| !entry.0) {
            return Err("remote-owner latest receiver state is not stopped after Start".into());
        }
        stops.push(last.1.clone());
    }
    for (id, response) in &activation.terminal {
        if response.schema_id != PRODUCT_ACTIVATION_RESPONSE_SCHEMA || &response.activation_id != id
            || response.signer_key_id != key_id || response.status != OwnerDispatchStatus::Completed
            || response.failure.is_some() || !response.readback.as_ref().is_some_and(|r| r.activated && &r.activation_id == id)
        { return Err("remote-owner activation history is incomplete".into()); }
        key.verify_strict(&product_activation_response_signing_bytes(response)?,
            &Signature::from_bytes(&decode_signature_base64(&response.signature_base64)?))
            .map_err(|_| "remote-owner activation signature")?;
    }
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.remote_receiver_terminal_history.v1",
        "executor_generation":executor_generation,"signer_key_id":key_id,
        "verified_response_count":replay.terminal.len(),"receiver_stop_receipt_ids":stops,
        "physical_cleanup_claimed":false}))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use rusty_quest_media_stream_android::{encode_signature_base64, OwnerDispatchResponse};

    fn response(start: bool, revision: u64) -> OwnerDispatchResponse {
        let word = if start { "start" } else { "stop" };
        let state = if start { "receiver_armed" } else { "stopped" };
        let action_kind = if start { "arm_receiver" } else { "stop" };
        let readback = json!({"$schema":ANDROID_MEDIA_READBACK_SCHEMA,"capability":"capability.host.fixture",
            "executor_generation":3,"action_id":format!("action.{word}"),"authority_epoch_id":"epoch.fixture",
            "media_acceptance_authority_revision":1,"expected_runtime_revision":1,"client_id":"client.fixture",
            "lease_id":"lease.fixture","sequence":6,"operation":word,"owner_kind":"sink","action_kind":action_kind,
            "owner_id":"owner.fixture.sink","provider_kind":"meta_spatial_sdk_packed_stereo_sink","resource_id":"sink.fixture",
            "provider_handle_id":"receiver:g3","provider_state_revision":revision,"observed_state":state,
            "receipt_id":format!("receipt.{word}.{revision}")});
        let raw = readback.to_string();
        let effect = json!({"readback":readback,"readback_json":raw,"verified":{
            "$schema":VERIFIED_OWNER_EFFECT_SCHEMA,"receipt_id":format!("receipt.{word}.{revision}"),
            "readback_sha256":format!("sha256:{}",packaged_json_sha256(&raw)),"executor_generation":3,
            "provider_state_revision":revision,"observed_state":state,"terminal":!start,
            "provider_handle_id":"receiver:g3","detail_sha256":format!("sha256:{}","a".repeat(64))}});
        let mut result: OwnerDispatchResponse = serde_json::from_value(json!({"$schema":OWNER_DISPATCH_RESPONSE_SCHEMA,
            "dispatch_id":format!("dispatch.{word}.{revision}"),"request_sha256":format!("sha256:{}","b".repeat(64)),
            "status":"completed","effect":effect,"failure":null,"signer_key_id":"ed25519.fixture","signature_base64":""})).unwrap();
        resign(&mut result); result
    }
    fn resign(value: &mut OwnerDispatchResponse) {
        value.signature_base64=encode_signature_base64(&SigningKey::from_bytes(&[7;32])
            .sign(&response_signing_bytes(value).unwrap()).to_bytes());
    }
    fn fixture() -> OwnerDispatchReplaySnapshot {
        let mut p=OwnerDispatchReplaySnapshot::default();
        for r in [response(true,1),response(false,2)] { p.terminal.insert(r.dispatch_id.clone(),r); } p
    }
    fn check(p: &OwnerDispatchReplaySnapshot, restored: bool, local: bool) -> Result<Value,String> {
        verified_receivers(p,&ProductActivationReplaySnapshot::default(),
            SigningKey::from_bytes(&[7;32]).verifying_key().as_bytes(),"ed25519.fixture",3,restored,local)
    }
    #[test] fn signed_current_start_stop_proves_history_not_physical_cleanup() {
        let result=check(&fixture(),false,false).unwrap();
        assert_eq!(result["physical_cleanup_claimed"],false);
        assert_eq!(result["receiver_stop_receipt_ids"],json!(["receipt.stop.2"]));
    }
    #[test] fn restored_or_local_media_cannot_use_remote_only_branch() {
        assert!(check(&fixture(),true,false).is_err());assert!(check(&fixture(),false,true).is_err());
    }
    #[test] fn pending_and_uncertain_stay_unclosed() {
        let mut p=fixture();p.pending_request_sha256.insert("dispatch.pending".into(),"sha256:pending".into());
        assert!(check(&p,false,false).is_err());p=fixture();p.terminal.values_mut().next().unwrap().status=OwnerDispatchStatus::Uncertain;
        assert!(check(&p,false,false).is_err());
    }
    #[test] fn old_signature_and_different_key_are_rejected() {
        let mut p=fixture();p.terminal.values_mut().next().unwrap().request_sha256=format!("sha256:{}","c".repeat(64));
        assert!(check(&p,false,false).is_err());
        assert!(verified_receivers(&fixture(),&ProductActivationReplaySnapshot::default(),
            SigningKey::from_bytes(&[8;32]).verifying_key().as_bytes(),"ed25519.fixture",3,false,false).is_err());
    }
    #[test] fn missing_start_or_stop_and_later_restart_are_rejected() {
        let mut p=fixture();p.terminal.retain(|_,r|r.effect.as_ref().unwrap().verified.terminal);assert!(check(&p,false,false).is_err());
        p=fixture();p.terminal.retain(|_,r|!r.effect.as_ref().unwrap().verified.terminal);assert!(check(&p,false,false).is_err());
        p=fixture();let r=response(true,3);p.terminal.insert(r.dispatch_id.clone(),r);assert!(check(&p,false,false).is_err());
    }
    #[test] fn wrong_registry_generation_and_raw_readback_are_rejected_even_resigned() {
        for generation in [0,2,4] { assert!(verified_receivers(&fixture(),&ProductActivationReplaySnapshot::default(),
            SigningKey::from_bytes(&[7;32]).verifying_key().as_bytes(),"ed25519.fixture",generation,false,false).is_err()); }
        let mut p=fixture();let r=p.terminal.values_mut().next().unwrap();r.effect.as_mut().unwrap().readback_json="{}".into();resign(r);
        assert!(check(&p,false,false).is_err());
    }
    #[test] fn different_handle_does_not_retire_started_receiver() {
        let mut p=fixture();let r=p.terminal.get_mut("dispatch.stop.2").unwrap();let e=r.effect.as_mut().unwrap();
        e.readback.provider_handle_id="receiver:g4".into();e.verified.provider_handle_id="receiver:g4".into();
        e.readback_json=serde_json::to_string(&e.readback).unwrap();e.verified.readback_sha256=format!("sha256:{}",packaged_json_sha256(&e.readback_json));resign(r);
        assert!(check(&p,false,false).is_err());
    }
    #[test] fn pending_activation_keeps_receiver_closure_pending() {
        let mut a=ProductActivationReplaySnapshot::default();a.pending_request_sha256.insert("activation.pending".into(),"sha256:pending".into());
        assert!(verified_receivers(&fixture(),&a,SigningKey::from_bytes(&[7;32]).verifying_key().as_bytes(),"ed25519.fixture",3,false,false).is_err());
    }
}
