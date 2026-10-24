//! Fault injection into the actual stopped lifecycle, with exact restoration.
use super::*;
pub(crate) fn run(host:&Host,session:&str)->Result<Value,String>{
    let observe=||super::pair_ceremony::fixture_archive_plan(host,session);
    let before={let s=host.peer_lifecycle.lock().map_err(|_|"test lifetime busy")?;serde_json::to_value(s.as_ref().ok_or("test lifetime absent")?).map_err(safe_decode)?};
    let mut checks=Vec::new();
    for name in ["pending_renewal","whole_close","failed_stop"]{
        let saved={let mut slot=host.peer_lifecycle.lock().unwrap();let s=slot.as_mut().unwrap();
            match name{"pending_renewal"=>{s.renewal_pending=true;None},"whole_close"=>{s.whole_close_requested=true;None},_=>s.last_failure.replace("modeled failed Stop".into())}};
        let refused=observe().is_err();
        {let mut slot=host.peer_lifecycle.lock().unwrap();let s=slot.as_mut().unwrap();match name{"pending_renewal"=>s.renewal_pending=false,"whole_close"=>s.whole_close_requested=false,_=>s.last_failure=saved}}
        if !refused{return Err(format!("lifetime fault admitted: {name}"))}checks.push(name);
    }
    let saved=host.peer_lifecycle.lock().unwrap().as_mut().unwrap().terminal_stop_original_id.take();
    let refused=observe().is_err();host.peer_lifecycle.lock().unwrap().as_mut().unwrap().terminal_stop_original_id=saved;
    if !refused{return Err("missing terminal Original admitted".into())}checks.push("missing_stop_original");
    let saved={let mut slot=host.peer_lifecycle.lock().unwrap();let s=slot.as_mut().unwrap();std::mem::replace(&mut s.renewals.last_mut().unwrap()["accepted_pair"],Value::Null)};
    let refused=observe().is_err();host.peer_lifecycle.lock().unwrap().as_mut().unwrap().renewals.last_mut().unwrap()["accepted_pair"]=saved;
    if !refused{return Err("foreign provider join admitted".into())}checks.push("provider_join_drift");
    let after={let s=host.peer_lifecycle.lock().unwrap();serde_json::to_value(s.as_ref().unwrap()).map_err(safe_decode)?};
    if before!=after{return Err("lifetime negative controls changed actual state".into())}
    Ok(json!({"checks":checks,"actual_state_preserved":true,"limit":"Injected lifecycle metadata faults after genuine production Stop"}))
}
