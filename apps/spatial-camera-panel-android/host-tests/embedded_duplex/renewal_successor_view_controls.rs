//! Actual signed handler probes on retained two-role state. Only negative
//! test input/one unresolved metadata bit is changed; exact state is restored.
use super::*;
use ed25519_dalek::Signer;

fn resigned(mut frame:Envelope, mutation:impl FnOnce(&mut Body))->Result<Vec<u8>,String>{
    mutation(&mut frame.body);
    frame.signature_hex=hex(&ed25519_dalek::SigningKey::from_bytes(&[41;32]).sign(&bytes(&frame.body)?).to_bytes());
    let mut encoded=MAGIC_V2.to_vec();encoded.extend(serde_json::to_vec(&frame).map_err(safe_decode)?);Ok(encoded)
}
fn envelope(raw:&[u8])->Result<Envelope,String>{serde_json::from_slice(&raw[MAGIC_V2.len()..]).map_err(safe_decode)}
pub(crate) fn run(host:&Host,frames:&[(Vec<u8>,Vec<u8>)])->Result<Value,String>{
    if frames.len()<3{return Err("three actual view exchanges required".into())}
    let before=serde_json::to_value(&*state(host)?).map_err(safe_decode)?;
    let authority_before=host.authority.snapshot_json()?;
    let current=&frames.last().unwrap().0;
    let old=&frames.first().unwrap().0;
    let current_body=decode(host,current)?;
    let old_body=decode(host,old)?;
    if current_body.request_id==old_body.request_id{return Err("actual distinct view history absent".into())}
    let mut checks=Vec::new();
    // An earned completed retry retains its exact payload/cache. Clock is held
    // fixed, so both deterministic signed replies also remain byte-exact.
    let a=handle_frame(host,current)?;let b=handle_frame(host,current)?;
    if a!=b||envelope(&a)?.body.payload!=envelope(&frames.last().unwrap().1)?.body.payload{return Err("current cached view retry differs".into())}
    checks.push(json!({"kind":"current_completed_view_retry","exact_reply":true}));
    let snapshot:rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot=serde_json::from_str(&authority_before).map_err(safe_decode)?;
    let pair=peers(host)?;
    let floor=snapshot.reciprocal_ed25519.fresh_setup_fences.iter().find(|f|f.peer_ids==pair).ok_or("actual current floor absent")?;
    let floor_value=serde_json::to_value(floor).map_err(safe_decode)?;
    for kind in ["old_archived_view","current_id_changed_payload","changed_floor_epoch","changed_floor_request","changed_floor_context","wrong_signed_sender_view","unresolved_no_apply","old_fenced_phase"]{
        let mut frame=envelope(current)?;
        if kind=="old_archived_view"{frame=envelope(old)?;}
        let raw=resigned(frame,|body|{
            match kind{
                "old_archived_view"=>{},
                "current_id_changed_payload"=>body.payload["nonce_a"]=json!("ee".repeat(32)),
                "old_fenced_phase"=>body.phase_request_id=old_body.phase_request_id.clone(),
                _=>{
                    body.request_id=format!("ceremony.duplex.{}","f1".repeat(32));
                    body.phase_request_id=Some(body.request_id.clone());
                    body.payload["view"]["prior_fence"]=floor_value.clone();
                    body.payload["view"]["prior_setup_epoch"]=json!(floor.setup_epoch);
                    match kind{
                        "changed_floor_epoch"=>body.payload["view"]["prior_fence"]["setup_epoch"]=json!(floor.setup_epoch+1),
                        "changed_floor_request"=>body.payload["view"]["prior_fence"]["setup_request_id"]=json!("transaction.foreign"),
                        "changed_floor_context"=>body.payload["view"]["prior_fence"]["context_sha256"]=json!("sha256:".to_owned()+&"e1".repeat(32)),
                        "wrong_signed_sender_view"=>body.payload["view"]["local_peer_id"]=json!(host.local_peer_id),
                        _=>{}
                    }
                }
            }
        })?;
        let saved=if kind=="unresolved_no_apply"{let mut state=state(host)?;let a=state.fresh.active.as_mut().ok_or("actual active absent")?;let old=a.completed;a.completed=false;Some(old)}else{None};
        let result=handle_frame(host,&raw);
        if let Some(old)=saved{state(host)?.fresh.active.as_mut().ok_or("actual active lost")?.completed=old;}
        let error=result.err().ok_or_else(||format!("bad view admitted: {kind}"))?;
        let expected=match kind{"current_id_changed_payload"=>"fresh retry changed original payload","old_fenced_phase"=>"old fresh phase is fenced","unresolved_no_apply"=>"fresh original unresolved","wrong_signed_sender_view"=>"fresh incoming view selected peer differs",_=>"fresh incoming view current pair floor differs"};
        if !error.contains(expected){return Err(format!("wrong view refusal for {kind}: {error}"))}
        if serde_json::to_value(&*state(host)?).map_err(safe_decode)?!=before||host.authority.snapshot_json()?!=authority_before{return Err(format!("view probe changed actual state: {kind}"))}
        checks.push(json!({"kind":kind,"error":error,"actual_pair_and_authority_unchanged":true}));
    }
    Ok(json!({"checks":checks,"actual_state_preserved":true,"scope":"Actual captured/resigned fixture-key sender frames through production decode/phase/cache/view handlers; unresolved bit test-restored, no apply/history absence fabricated"}))
}
