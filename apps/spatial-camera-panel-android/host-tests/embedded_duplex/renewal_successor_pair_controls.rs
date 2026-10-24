//! Injected metadata faults around actual completed two-role production state.
//! No guard, authority result, callback result or lifecycle receipt is replaced.
use super::*;

fn rejected(host:&Host,session:&str,name:&str)->Result<String,String>{
    if completed_for_archive(host,session).is_ok(){return Err(format!("fault admitted: {name}"))}
    Ok(name.into())
}
pub(crate) fn run(host:&Host,session:&str)->Result<Value,String>{
    let before=serde_json::to_value(&*state(host)?).map_err(safe_decode)?;
    let accepted=completed_for_archive(host,session)?.ok_or("actual completed responder absent")?;
    let mut checks=Vec::new();
    let saved=state(host)?.renewal.result.take();
    let result=rejected(host,session,"pending_without_result");state(host)?.renewal.result=saved;checks.push(result?);
    let saved=state(host)?.renewal.last_failure.replace("modeled UNKNOWN cause".into());
    let result=rejected(host,session,"unknown_failure_retained");state(host)?.renewal.last_failure=saved;checks.push(result?);
    let saved=state(host)?.renewal.session.replace("session.foreign".into());
    let result=rejected(host,session,"wrong_session");state(host)?.renewal.session=saved;checks.push(result?);
    state(host)?.renewal.initiator=true;
    let result=rejected(host,session,"initiator_not_responder_custody");state(host)?.renewal.initiator=false;checks.push(result?);
    let saved=state(host)?.renewal.replies.pop().ok_or("actual final reply absent")?;
    let result=rejected(host,session,"missing_final_reply");state(host)?.renewal.replies.push(saved);checks.push(result?);
    let saved={let mut s=state(host)?;let reply=s.renewal.replies.last_mut().unwrap();std::mem::replace(&mut reply.3["provider_renewal"],Value::Null)};
    let result=rejected(host,session,"final_reply_provider_drift");state(host)?.renewal.replies.last_mut().unwrap().3["provider_renewal"]=saved;checks.push(result?);
    // A change after the copied archive bytes must not clear any current state.
    let saved=state(host)?.renewal.last_failure.replace("modeled concurrent callback change".into());
    {let mut s=state(host)?;let changed=serde_json::to_value(&*s).map_err(safe_decode)?;
      if finish_archived(&mut s,Some(&accepted)).is_ok(){return Err("changed archive owner admitted".into())}
      if serde_json::to_value(&*s).map_err(safe_decode)?!=changed{return Err("failed archive compare mutated owner".into())}}
    state(host)?.renewal.last_failure=saved;checks.push("changed_snapshot_refuses_without_mutation".into());
    let mut ledger=CycleLedger::default();
    for n in 1..=32{ledger.complete(&format!("ceremony.duplex.{n:064x}"))?;}
    let full=serde_json::to_value(&ledger).map_err(safe_decode)?;
    let next=format!("ceremony.duplex.{:064x}",33);
    if ledger.require_completable(&next).is_ok()||ledger.complete(&next).is_ok()
        ||serde_json::to_value(&ledger).map_err(safe_decode)?!=full{return Err("full ledger changed or admitted successor".into())}
    checks.push("ledger_capacity_refusal_preserves_exact_history".into());
    if serde_json::to_value(&*state(host)?).map_err(safe_decode)?!=before{return Err("negative controls changed actual owner".into())}
    Ok(json!({"checks":checks,"actual_state_preserved":true,
        "limit":"Injected retained metadata faults; not JNI scheduling or a real concurrent Stop effect"}))
}

pub(crate) fn drift(host:&Host)->Result<Value,String>{
    state(host)?.renewal.last_failure=Some("modeled concurrent archive barrier change".into());
    Ok(json!({"modeled_retained_state_change":true}))
}
