//! Execute the production incoming-owner join without JNI or a headset.
#[path = "../src/embedded_duplex/inbound_cleanup.rs"]
mod inbound_cleanup;
use inbound_cleanup::{CompletedStop, Original, terminal_owners};
use rusty_quest_media_stream_android::*;
use serde_json::json;

fn ticket(operation: &str, owner: &str) -> AndroidMediaExecutionTicket {
    serde_json::from_value(json!({"$schema":ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA,
        "capability":format!("cap.{operation}.{owner}"),"executor_generation":9,
        "action_id":format!("action.{operation}"),"authority_epoch_id":"epoch.remote",
        "media_acceptance_authority_revision":3,"expected_runtime_revision":4,
        "client_id":"client.remote","lease_id":"lease.remote","sequence":1,
        "operation":operation,"owner_kind":"source","action_kind":operation,
        "owner_id":owner,"provider_kind":"camera2","resource_id":format!("resource.{owner}")})).unwrap()
}
fn effect(target: &AndroidMediaExecutionTicket, revision: u64) -> AuthenticatedOwnerEffect {
    let readback = AndroidMediaOwnerReadback {
        remote_cleanup:None, schema_id:ANDROID_MEDIA_READBACK_SCHEMA.into(),
        capability:target.capability.clone(), executor_generation:target.executor_generation,
        action_id:target.action_id.clone(), authority_epoch_id:target.authority_epoch_id.clone(),
        media_acceptance_authority_revision:target.media_acceptance_authority_revision,
        expected_runtime_revision:target.expected_runtime_revision,
        client_id:target.client_id.clone(), lease_id:target.lease_id.clone(), sequence:target.sequence,
        operation:target.operation, owner_kind:target.owner_kind, action_kind:target.action_kind,
        owner_id:target.owner_id.clone(), provider_kind:target.provider_kind.clone(), resource_id:target.resource_id.clone(),
        provider_handle_id:format!("handle.{}",target.owner_id), provider_state_revision:revision,
        observed_state:"stopped".into(), receipt_id:format!("receipt.{}.{}",target.owner_id,revision),
    };
    let readback_json=serde_json::to_string(&readback).unwrap();
    let verified=VerifiedOwnerEffect {schema_id:VERIFIED_OWNER_EFFECT_SCHEMA.into(),
        receipt_id:readback.receipt_id.clone(),readback_sha256:format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&readback_json)),
        executor_generation:target.executor_generation,provider_state_revision:revision,
        observed_state:readback.observed_state.clone(),terminal:true,provider_handle_id:readback.provider_handle_id.clone(),
        detail_sha256:format!("sha256:{}","d".repeat(64))};
    AuthenticatedOwnerEffect {readback,readback_json,verified}
}
fn owner(name: &str) -> Original {
    let start=ticket("start",name);let stop=ticket("stop",name);
    Original {effect:Some(effect(&start,2)),ticket:start,
        completed_stop:Some(CompletedStop {effect:effect(&stop,3),ticket:stop})}
}

#[test]
fn incoming_terminal_needs_all_actual_owner_receipts_without_local_start() {
    let mut owners=vec![owner("incoming.receiver"),owner("incoming.decoder"),owner("incoming.renderer")];
    assert_eq!(terminal_owners(owners.iter(),9).unwrap().len(),3);
    owners[1].completed_stop=None;
    assert!(terminal_owners(owners.iter(),9).unwrap_err().contains("Pending"));
    owners[1]=owner("incoming.decoder");
    assert_eq!(terminal_owners(owners.iter(),9).unwrap().len(),3);
}
#[test]
fn no_incoming_target_is_not_terminal() {
    assert!(terminal_owners([].iter(),9).is_err());
}
#[test]
fn uncertain_start_can_close_only_with_its_real_completed_stop() {
    let mut incoming=owner("incoming.receiver");incoming.effect=None;
    assert_eq!(terminal_owners([&incoming].into_iter(),9).unwrap().len(),1);
    incoming.completed_stop.as_mut().unwrap().effect.verified.terminal=false;
    assert!(terminal_owners([&incoming].into_iter(),9).is_err());
}
#[test]
fn wrong_direction_client_lease_epoch_owner_or_resource_stays_pending() {
    for field in ["client_id","lease_id","authority_epoch_id","owner_id","resource_id","provider_kind"] {
        let mut incoming=owner("incoming.receiver");
        let mut changed=serde_json::to_value(&incoming.completed_stop.as_ref().unwrap().ticket).unwrap();
        changed[field]=json!("other.local");
        let changed:AndroidMediaExecutionTicket=serde_json::from_value(changed).unwrap();
        incoming.completed_stop=Some(CompletedStop {effect:effect(&changed,3),ticket:changed});
        assert!(terminal_owners([&incoming].into_iter(),9).is_err(),"{field}");
    }
}
#[test]
fn changed_executor_incarnation_cannot_reuse_terminal() {
    let mut incoming=owner("incoming.receiver");
    assert!(terminal_owners([&incoming].into_iter(),10).is_err());
    assert!(terminal_owners([&incoming].into_iter(),0).is_err());
    let target=derive_retained_target_stop(&incoming.ticket,10,&"a".repeat(32)).unwrap();
    incoming.completed_stop=Some(CompletedStop {effect:effect(&target,1),ticket:target});
    assert!(terminal_owners([&incoming].into_iter(),10).is_ok(),"actual fresh Stop joins retained original without relabeling it");
    assert_eq!(incoming.ticket.executor_generation,9);
}
#[test]
fn repeated_stop_readback_is_idempotent_but_restart_invalidates_it() {
    let mut incoming=owner("incoming.receiver");
    let before=serde_json::to_value(&incoming).unwrap();
    assert!(terminal_owners([&incoming].into_iter(),9).is_ok());
    assert!(terminal_owners([&incoming].into_iter(),9).is_ok());
    assert_eq!(before,serde_json::to_value(&incoming).unwrap());
    incoming.ticket.expected_runtime_revision=5;
    assert!(terminal_owners([&incoming].into_iter(),9).is_err());
}
#[test]
fn historical_original_without_stop_stays_pending() {
    let current=owner("incoming.receiver");
    let mut historical=serde_json::to_value(current).unwrap();
    historical.as_object_mut().unwrap().remove("completed_stop");
    let historical:Original=serde_json::from_value(historical).unwrap();
    assert!(terminal_owners([&historical].into_iter(),9).is_err());
}
#[test]
fn provider_revision_and_verified_raw_join_are_required() {
    let mut incoming=owner("incoming.receiver");
    incoming.completed_stop.as_mut().unwrap().effect=effect(&ticket("stop","incoming.receiver"),2);
    assert!(terminal_owners([&incoming].into_iter(),9).is_err());
    incoming=owner("incoming.receiver");
    incoming.completed_stop.as_mut().unwrap().effect.readback_json="{}".into();
    assert!(terminal_owners([&incoming].into_iter(),9).is_err());
    incoming=owner("incoming.receiver");
    incoming.completed_stop.as_mut().unwrap().effect.verified.receipt_id="other.receipt".into();
    assert!(terminal_owners([&incoming].into_iter(),9).is_err());
}

#[test]
fn ordinary_and_retained_pending_or_invalid_replay_prevent_close() {
    let mut ordinary=OwnerDispatchReplaySnapshot::default();
    let mut retained=RetainedCleanupReplaySnapshot::default();
    assert!(inbound_cleanup::require_quiet_replay(&ordinary,&retained).is_ok());
    ordinary.pending_request_sha256.insert("request.ordinary".into(),format!("sha256:{}","a".repeat(64)));
    assert!(inbound_cleanup::require_quiet_replay(&ordinary,&retained).is_err());
    ordinary.pending_request_sha256.clear();
    retained.pending_request_sha256.insert("request.retained".into(),format!("sha256:{}","b".repeat(64)));
    assert!(inbound_cleanup::require_quiet_replay(&ordinary,&retained).is_err());
    retained.pending_request_sha256.clear();
    retained.terminal.insert("response.bad".into(),RetainedCleanupTerminalReplay {
        request_sha256:"bad".into(),response_bytes:vec![1]});
    assert!(inbound_cleanup::require_quiet_replay(&ordinary,&retained).is_err());
}

#[test]
fn stop_for_unstarted_owner_is_preserved_without_inventing_original() {
    let stop=ticket("stop","incoming.receiver");let completed=effect(&stop,3);
    assert!(!inbound_cleanup::retain_completed_stop(None,&stop,&completed,9).unwrap());
    let mut incoming=owner("incoming.receiver");incoming.completed_stop=None;
    assert!(inbound_cleanup::retain_completed_stop(Some(&mut incoming),&stop,&completed,9).unwrap());
    assert!(terminal_owners([&incoming].into_iter(),9).is_ok());
    let before=serde_json::to_value(&incoming).unwrap();
    let mut wrong=stop.clone();wrong.client_id="client.local".into();
    assert!(inbound_cleanup::retain_completed_stop(Some(&mut incoming),&wrong,&effect(&wrong,4),9).is_err());
    assert_eq!(before,serde_json::to_value(incoming).unwrap());
}
