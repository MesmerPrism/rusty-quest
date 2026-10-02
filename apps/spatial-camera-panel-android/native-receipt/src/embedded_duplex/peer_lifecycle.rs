//! Fixed installed-app operations. External callers select an enum word only;
//! all authority identity, revisions, time and randomness remain native-owned.
use super::*;
use std::fs::File;
use std::io::Read;
use rusty_quest_broker_authority::QuestConcurrentPeerCommand;

#[derive(Default)]
pub(super) struct State {
    pub(super) client: Option<String>,
    activation_id: Option<String>,
    start_mutation: Option<Value>,
    activation: Option<Value>,
    stop_mutation: Option<Value>,
    stop_completion: Option<Value>,
    stop_effect: Option<rusty_quest_broker_authority::QuestBrokerMediaStopEffectReceipt>,
    route_receipt: Option<Value>,
    route_termination: Option<Value>,
    termination_action: Option<&'static str>,
    revoker_adoption: Option<Value>,
    route_cleanup: Option<Value>,
    renewals: Vec<Value>,
    renewed_session_proofs: Vec<Value>,
    renewal_pending: bool,
    last_failure: Option<String>,
    last_failure_action: Option<&'static str>,
    incoming_cleanup: Option<Value>,
}

fn entropy() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    File::open("/dev/urandom").and_then(|mut f| f.read_exact(&mut bytes))
        .map_err(|_| "concurrent lifecycle entropy unavailable")?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

fn json_value(json: &str) -> Result<Value, String> {
    serde_json::from_str(json).map_err(safe_decode)
}

fn projection(host: &Host) -> Result<Value, String> {
    let grant = serde_json::from_value(json!(host.current_grant_id()?)).map_err(safe_decode)?;
    let local = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
    let remote = serde_json::from_value(json!(host.remote_peer_id)).map_err(safe_decode)?;
    serde_json::to_value(host.authority.current_owner_projection(&grant, &local, &remote,
        host.clock.now_ms()?)?).map_err(safe_decode)
}

fn apply_media(host: &Host, action: QuestConcurrentPeerCommand) -> Result<Value, String> {
    let random = entropy()?;
    let mut provider = Checkout::take(host.provider.clone())?;
    let mutation = provider.get().apply_concurrent_peer_command(action, unsafe {libc::getuid()},
        &host.packaged_route.package_name, &host.installed_signing_certificate_sha256,
        host.clock.now_ms()?, &random).map_err(|_| "concurrent admitted media mutation rejected")?;
    serde_json::to_value(mutation).map_err(safe_decode)
}

fn media_stop_effect(state: &State) -> Option<&Value> {
    state.stop_completion.as_ref()?.get("stop_effect_receipt")
}

fn current_broker_readback(evidence:&Value)->Result<Value,String> {
    let field=|path:&str|evidence.pointer(path).cloned().ok_or_else(||"actual current Broker readback field absent".to_owned());
    Ok(json!({"$schema":"rusty.quest.concurrent.broker_current_readback.v1",
        "provider_epoch_id":field("/runtime/provider_epoch_id")?,
        "decision_owner_id":field("/decision_owner_id")?,
        "local_acceptance_rules":field("/local_acceptance_rules")?,
        "host_authority_revision":field("/runtime/host_snapshot/authority_revision")?,
        "current_host_leases":field("/runtime/host_snapshot/leases")?,
        "current_control_lease_authority_revision":field("/runtime/control_lease_authority/current_authority_snapshot/authority_revision")?,
        "current_clock":field("/runtime/control_lease_authority/current_clock")?,
        "current_control_leases":field("/runtime/control_lease_authority/current_authority_snapshot/active_leases")?,
        "admission_authority_revision":field("/runtime/admission_snapshot/authority_revision")?,
        "current_admission_grants":field("/runtime/admission_snapshot/grants")?,
        "media_runtime_state":field("/media_runtime_state")?,
        "media_pending_action":field("/media_pending_action")?}))
}

fn receipt(host: &Host, state: &State, action: &str) -> Result<Value, String> {
    let current = projection(host).ok();
    let broker = json_value(&Checkout::take(host.provider.clone())?.get().evidence_json()
        .map_err(|_| "concurrent lifecycle evidence unavailable")?)?;
    let incoming_terminal = state.incoming_cleanup.as_ref().is_some_and(|prior|
        incoming_cleanup_receipt(host).is_ok_and(|current| &current == prior));
    let physically_stopped = (media_stop_effect(state).is_some_and(|v| !v.is_null())
        && state.route_cleanup.is_some()) || incoming_terminal;
    let active = !state.renewal_pending && state.stop_mutation.is_none() && state.last_failure.is_none()
        && state.activation.as_ref().is_some_and(|v|
        v.pointer("/activation/status").and_then(Value::as_str) == Some("completed"))
        && current.is_some();
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1",
        "action":action,"config_sha256":host.config_sha256,
        "native_executor_generation":host.capability.generation,"app_process_generation":host.capability.binding.generation,
        "observed_at_ms":host.clock.now_ms()?,
        "status":if physically_stopped{"terminal"}else if active{"active"}else{"pending"},
        "authority_projection":current,"broker_evidence":current_broker_readback(&broker)?,
        "start_mutation":state.start_mutation,"stop_mutation":state.stop_mutation,
        "route_receipt":state.route_receipt,"route_termination":state.route_termination,"route_cleanup":state.route_cleanup,
        "termination_action":state.termination_action,"revoker_adoption":state.revoker_adoption,
        "incoming_owner_cleanup":state.incoming_cleanup,
        "media_completion":state.stop_completion,"media_stop_effect_receipt":media_stop_effect(state),
        "owner_failure_diagnostic":host.callbacks.owner_failure_diagnostic().ok(),
        "native_owner_dispatch_failure":*host.owner_dispatch_failure.lock().map_err(|_| "owner diagnostic state unavailable")?,
        "activation":state.activation,"renewal_receipts":&state.renewals[state.renewals.len().saturating_sub(2)..],
        "renewal_total_completed":state.renewals.len(),
        "renewal_first_request_id":state.renewals.first().and_then(|receipt|receipt.get("request_id")),
        "renewal_last_request_id":state.renewals.last().and_then(|receipt|receipt.get("request_id")),
        "renewal_pending":state.renewal_pending,
        "peer_physical_cleanup":if physically_stopped{"terminal"}else{"pending"},
        "native_host_physical_cleanup":"pending","whole_app_physical_cleanup":"pending",
        "last_failure":state.last_failure,"last_failure_action":state.last_failure_action}))
}

fn start(host: &Host, state: &mut State) -> Result<(), String> {
    if state.termination_action==Some("revoke") {
        return Err("revoked Peer requires a fresh authenticated authority boundary".into());
    }
    if state.stop_mutation.is_some() || state.stop_completion.is_some() {
        if state.route_cleanup.is_none() {
            return Err("previous Peer transaction has retained cleanup".into());
        }
        // Full physical Stop and owner route cleanup are verified before a
        // new action can replace the previous product activation transaction.
        Checkout::take(host.activation_sender.clone())?.get().pending=None;
        *state=State::default();
    }
    if state.renewal_pending {
        return Err("retained authority renewal requires its retry or physical Stop".into());
    }
    if state.start_mutation.is_none() {
        let mutation = apply_media(host, QuestConcurrentPeerCommand::Start)?;
        state.client = Some(mutation.get("client_id").and_then(Value::as_str)
            .ok_or("native admitted client absent")?.to_owned());
        state.activation_id = Some(format!("activation.concurrent.{}",entropy()?));
        state.start_mutation = Some(mutation);
    }
    if state.route_receipt.is_none() {
        let random = entropy()?;
        let paired=json_value(&pair_ceremony::status(host)?)?;
        if paired.pointer("/native_current_session/current").and_then(Value::as_bool)!=Some(true) {
            return Err("current signed paired session absent".into());
        }
        let session=serde_json::from_value(paired.get("session_id").cloned()
            .ok_or("native paired session identity absent")?).map_err(safe_decode)?;
        let route = Checkout::take(host.provider.clone())?.get()
            .issue_concurrent_peer_route(&session,host.clock.now_ms()?, &random)
            .map_err(|_| "concurrent current route issue rejected")?;
        let route = serde_json::to_value(route).map_err(safe_decode)?;
        let grant = route.pointer("/record/route/grant_id").and_then(Value::as_str)
            .ok_or("accepted current route grant absent")?;
        let grant_typed=serde_json::from_value(json!(grant)).map_err(safe_decode)?;
        let local=serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
        let remote=serde_json::from_value(json!(host.remote_peer_id)).map_err(safe_decode)?;
        let current=host.authority.current_owner_projection(&grant_typed,&local,&remote,host.clock.now_ms()?)?;
        if current.route_configuration_sha256!=host.route_configuration_sha256
            || current.platform_runtime_spec_id!=host.packaged_route.runtime_spec_ids[host.packaged_route.installed_peer_index] {
            return Err("issued route differs from authenticated installed product".into());
        }
        // Update the shared native projection binding only from the actual
        // accepted owner receipt. No transport string can replace this grant.
        *host.route_grant_id.lock().map_err(|_| "current route binding poisoned")? = grant.to_owned();
        state.route_receipt = Some(route);
        projection(host)?;
    }
    if state.activation.is_none() {
        host.owner_effect_attempted.store(true, Ordering::SeqCst);
        let input = json!({"client_id":state.client,"activation_id":state.activation_id}).to_string();
        let acknowledged = complete_start(host,&input)?;
        let value = json_value(&acknowledged)?;
        state.activation = Some(value);
    }
    projection(host)?;
    Ok(())
}

fn require_renewable_media(state:&State)->Result<(),String> {
    if state.client.is_none()||state.stop_mutation.is_some()||state.route_cleanup.is_some()
        ||!state.activation.as_ref().is_some_and(|value|
            value.pointer("/activation/status").and_then(Value::as_str)==Some("completed")) {
        return Err("authority renewal requires the same active media transaction".into());
    }
    if state.renewals.len()>=32 {return Err("bounded renewal receipt capacity reached".into());}
    Ok(())
}

pub(super) fn require_renewable_peer(host:&Host)->Result<(),String> {
    host.capability.require_live()?;
    let mut checked=Checkout::take(host.peer_lifecycle.clone())?;
    require_renewable_media(checked.get())?;
    projection(host)?;
    Ok(())
}

fn renew_state_after_signed_pair(host:&Host,state:&mut State,pair_proof:&Value)->Result<Value,String> {
    host.capability.require_live()?;
    if let Some(index)=state.renewed_session_proofs.iter().position(|prior|prior==pair_proof) {
        return state.renewals.get(index).cloned().ok_or("retained renewal receipt missing".into());
    }
    require_renewable_media(state)?;
    let renewal=serde_json::from_value(pair_proof.clone()).map_err(safe_decode)?;
    let before=monotonic_ns()?;
    let wall=i64::try_from(SystemTime::now().duration_since(UNIX_EPOCH)
        .map_err(|_|"OS authority wall clock unavailable")?.as_millis())
        .map_err(|_|"OS authority wall clock overflow")?;
    let after=monotonic_ns()?;
    let uncertainty=after.checked_sub(before).ok_or("OS authority clock reversed")?
        .checked_add(1_000_000).ok_or("OS clock uncertainty overflow")?;
    let now=u64::try_from(wall).map_err(|_|"OS authority wall clock negative")?;
    let result={
        let mut provider=Checkout::take(host.provider.clone())?;
        let clock=provider.get().read_concurrent_peer_clock(wall,after,uncertainty)
            .map_err(|_|"current OS authority clock rejected")?;
        provider.get().renew_concurrent_peer_authority(&clock,&renewal,now,&entropy()?)
            .map_err(|_|"current signed authority renewal retained Pending")?
    };
    let value=serde_json::to_value(result).map_err(safe_decode)?;
    host.capability.require_live()?;
    projection(host)?;
    state.renewed_session_proofs.push(pair_proof.clone());
    state.renewals.push(value.clone());
    Ok(value)
}

/// Called only after the signed pair child has applied its actual local owner
/// renewal. The responder performs no network exchange while holding this slot.
pub(super) fn renew_after_signed_pair(host:&Host,pair_proof:&Value)->Result<Value,String> {
    let mut checked=Checkout::take(host.peer_lifecycle.clone())?;
    let state=checked.get();
    state.renewal_pending=true;
    let result=renew_state_after_signed_pair(host,state,pair_proof);
    match &result {
        Ok(_)=>{state.renewal_pending=false;state.last_failure=None;state.last_failure_action=None;},
        Err(error)=>{state.last_failure=Some(error.clone());state.last_failure_action=Some("renew_authority");}
    }
    result
}

fn renew(host:&Host,state:&mut State)->Result<(),String> {
    require_renewable_media(state)?;
    projection(host)?;
    state.renewal_pending=true;
    let pair=json_value(&pair_ceremony::renew(host)?)?;
    let proof=pair.get("local_session_renewal").ok_or("actual signed local session renewal absent")?;
    if pair.get("remote_session_renewal").is_none()||pair.get("remote_provider_renewal").is_none() {
        return Err("actual signed remote authority renewal absent".into());
    }
    renew_state_after_signed_pair(host,state,proof)?;
    let cycle=pair.get("renewal_id").and_then(Value::as_str)
        .ok_or("retained signed renewal cycle identity absent")?;
    pair_ceremony::finish_renewal_cycle(host,cycle)?;
    state.renewal_pending=false;
    Ok(())
}

fn stop(host: &Host, state: &mut State, revoke: bool) -> Result<(), String> {
    if state.client.is_none() {return Err("Peer Start target absent; no terminal media claim".into());}
    if revoke && state.termination_action.is_some_and(|action|action!="revoke") {
        return Err("Peer already terminalized by another typed action".into());
    }
    if state.route_cleanup.is_some() {return Ok(());}
    let client=serde_json::from_value(json!(state.client)).map_err(safe_decode)?;
    let expired=Checkout::take(host.provider.clone())?.get()
        .concurrent_peer_requires_revoker_cleanup(&client,host.clock.now_ms()?)
        .map_err(|_|"actual Peer holder expiry readback unavailable")?;
    let trusted_cleanup=revoke||expired;
    if trusted_cleanup {
        // Read both real OS clocks. The capability epoch and sequence come
        // from the retained native authority, never an operator timestamp.
        let before=monotonic_ns()?;
        let wall=i64::try_from(SystemTime::now().duration_since(UNIX_EPOCH)
            .map_err(|_|"OS authority wall clock unavailable")?.as_millis())
            .map_err(|_|"OS authority wall clock overflow")?;
        let after=monotonic_ns()?;
        let uncertainty=after.checked_sub(before).ok_or("OS authority clock reversed")?
            .checked_add(1_000_000).ok_or("OS clock uncertainty overflow")?;
        let mut provider=Checkout::take(host.provider.clone())?;
        let clock=provider.get().read_concurrent_peer_clock(wall,after,uncertainty)
            .map_err(|_|"current OS authority clock rejected")?;
        let adopted=provider.get().adopt_concurrent_peer_revoker(&clock,
            u64::try_from(wall).map_err(|_|"OS authority wall clock negative")?,&entropy()?)
            .map_err(|_|"authenticated current revoker lease adoption pending")?;
        state.revoker_adoption=Some(serde_json::to_value(adopted).map_err(safe_decode)?);
    }
    if state.stop_mutation.is_none() {
        let mutation = if trusted_cleanup {
            let adoption=serde_json::from_value(state.revoker_adoption.clone().ok_or("actual trusted revoker adoption absent")?).map_err(safe_decode)?;
            let prepared=Checkout::take(host.provider.clone())?.get()
                .prepare_concurrent_peer_revoker_cleanup(&client,&adoption,host.clock.now_ms()?,&entropy()?)
                .map_err(|_|"actual trusted Revoke and physical Stop preparation pending")?;
            serde_json::to_value(prepared).map_err(safe_decode)?
        } else {apply_media(host,QuestConcurrentPeerCommand::Stop)?};
        if mutation.get("client_id") != state.start_mutation.as_ref().and_then(|v|v.get("client_id")) {
            return Err("Stop media target differs".into());
        }
        state.stop_mutation = Some(mutation);
    }
    if state.route_termination.is_none() {
        let route = Checkout::take(host.provider.clone())?.get()
            .terminate_concurrent_peer_route(trusted_cleanup,host.clock.now_ms()?,&entropy()?)
            .map_err(|_| "concurrent route termination pending")?;
        state.route_termination = Some(serde_json::to_value(route).map_err(safe_decode)?);
        state.termination_action=Some(if trusted_cleanup{"revoke"}else{"stop"});
    }
    if trusted_cleanup {
        let adoption=state.revoker_adoption.as_ref().ok_or("actual current cleanup requester adoption absent")?;
        let requester=adoption.get("revoker_id").and_then(Value::as_str).ok_or("actual cleanup requester identity absent")?;
        let lease=adoption.pointer("/lease/lease_id").and_then(Value::as_str).ok_or("actual cleanup requester lease absent")?;
        install_retained_cleanup_requester(host,requester,lease)?;
    }
    if state.stop_completion.is_none() {
        let client = serde_json::from_value(json!(state.client)).map_err(safe_decode)?;
        let completed = Checkout::take(host.provider.clone())?.get()
            .complete_media_stop_for_cleanup_typed(&client,host.clock.now_ms()?)
            .map_err(|error| crate::embedded_duplex::cleanup_failure::describe(crate::embedded_duplex::cleanup_failure::Stage::Media, &error))?;
        state.stop_effect = completed.stop_effect_receipt.clone();
        state.stop_completion = Some(serde_json::to_value(completed).map_err(safe_decode)?);
    }
    if state.route_cleanup.is_none() {
        let effect = state.stop_effect.as_ref().ok_or("verified seven-owner Stop effect absent")?;
        let route = Checkout::take(host.provider.clone())?.get()
            .complete_concurrent_peer_route_cleanup(effect,host.clock.now_ms()?,&entropy()?)
            .map_err(|error| crate::embedded_duplex::cleanup_failure::describe(crate::embedded_duplex::cleanup_failure::Stage::Route, &error))?;
        state.route_cleanup = Some(serde_json::to_value(route).map_err(safe_decode)?);
    }
    Ok(())
}

fn incoming_cleanup_receipt(host: &Host) -> Result<Value, String> {
    // Both servers commit before returning provider completion. An uncertain
    // dispatch cannot be upgraded using a detached renderer or absent client.
    let ordinary = Checkout::take(host.server.clone())?.get().replay_snapshot();
    let retained = Checkout::take(host.cleanup_server.clone())?.get().replay_snapshot();
    inbound_cleanup::require_quiet_replay(&ordinary, &retained)?;
    host.cleanup.incoming_terminal()
}

fn close_incoming(host: &Host, state: &mut State) -> Result<(), String> {
    state.incoming_cleanup = None;
    state.incoming_cleanup = Some(incoming_cleanup_receipt(host)?);
    Ok(())
}

fn actual_java_cleanup(env:&mut JNIEnv<'_>)->Result<bool,String> {
    use jni::objects::JLongArray;
    let resources=env.call_static_method(
        "io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexProcessHost",
        "nativeProductResourcesTerminal","()Z",&[]).map_err(|_|"media resource observation unavailable")?
        .z().map_err(|_|"media resource observation type")?;
    if !resources {return Ok(false);}
    let own=env.call_static_method(
        "io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/OwnStereoCaptureRuntime",
        "currentForApplication","()Lio/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/OwnStereoCaptureRuntime;",&[])
        .map_err(|_|"Own capture cleanup observation unavailable")?.l().map_err(|_|"Own capture cleanup observation type")?;
    if own.is_null(){return Ok(false);}
    let cleanup=env.call_method(&own,"physicalCleanupState","()Ljava/lang/String;",&[])
        .map_err(|_|"Own capture cleanup state unavailable")?.l().map_err(|_|"Own capture cleanup state type")?;
    if cleanup.is_null(){return Ok(false);}
    let cleanup=JString::from(cleanup);
    let state:String=env.get_string(&cleanup).map_err(|_|"Own capture cleanup encoding")?.into();
    if state!="terminal" {return Ok(false);}
    let array=env.call_static_method("io/github/mesmerprism/rustyquest/spatial_camera_panel/StereoBankControls",
        "nativeReadConcurrentStereoQualification","()[J",&[])
        .map_err(|_|"renderer physical cleanup observation unavailable")?.l().map_err(|_|"renderer cleanup type")?;
    if array.is_null(){return Ok(false);}
    let array=JLongArray::from(array);
    if env.get_array_length(&array).map_err(|_|"renderer cleanup observation length")?!=160 {return Ok(false);}
    let mut words=[0i64;160];
    env.get_long_array_region(&array,0,&mut words).map_err(|_|"renderer cleanup observation unavailable")?;
    Ok(words[0]==1&&words[1]==160&&words[9]==2&&words[63]==0&&words[156]==0&&words[157]==0)
}

fn close_host(mut value:Value,host:HostLease,checked:Checkout<State>)->Result<String,String> {
    let expected=host.config_sha256.clone();
    host.capability.require_live()?;
    {
        let mut state=process().lock().map_err(|_|"process state poisoned")?;
        if state.initializing||state.closing||state.lease_integrity_failed||state.host_leases!=1 {
            return Ok(value.to_string());
        }
        state.closing=true;
    }
    drop(checked);
    drop(host);
    let removed={
        let mut state=process().lock().map_err(|_|"process state poisoned")?;
        if !state.closing||state.host_leases!=0||state.lease_integrity_failed {
            return Err("native host close retained Pending".into());
        }
        let mut route=prepared_route().lock().map_err(|_|"packaged route slot poisoned")?;
        if route.as_ref().map(|(sha,_)|sha.as_str())!=Some(expected.as_str()) {
            return Err("native host close configuration changed".into());
        }
        let removed=state.host.take();route.take();
        state.last_closed_sha256=Some(expected);state.closing=false;
        removed
    };
    drop(removed);
    // Only native references have now been released. Java closes its control
    // endpoint and verifies its own barriers before authoring app aggregate.
    value["native_host_physical_cleanup"]=json!("terminal");
    value["whole_app_physical_cleanup"]=json!("pending");
    value["status"]=json!("pending");
    let output=value.to_string();
    process().lock().map_err(|_|"process state poisoned")?.last_peer_close_receipt=Some(output.clone());
    Ok(output)
}

pub(super) fn operate(env:&mut JNIEnv<'_>,word:i32)->Result<String,String> {
    let action = match word {1=>"start",2=>"renew_authority",3=>"peer_stop",4=>"peer_revoke",
        5=>"peer_status",6=>"whole_app_close",_=>return Err("fixed Peer lifecycle action rejected".into())};
    if word==6 {
        let state=process().lock().map_err(|_|"process state poisoned")?;
        if state.host.is_none() {
            if let Some(receipt)=&state.last_peer_close_receipt{return Ok(receipt.clone());}
        }
    }
    let host = host()?;
    host.capability.require_live()?;
    if !crate::own_stereo_capture_runtime::capture_route_selected() {
        return Err("authenticated concurrent source feature inactive".into());
    }
    let mut checked = Checkout::take(host.peer_lifecycle.clone())?;
    let state = checked.get();
    let operation = match word {
        1=>start(&host,state),
        2=>renew(&host,state),
        6 if state.client.is_none()=>close_incoming(&host,state),
        3|6=>stop(&host,state,false),4=>stop(&host,state,true),5=>Ok(()),_=>unreachable!(),
    };
    // Errors after an admitted mutation retain every native credential and
    // report Pending. Status polling cannot turn uncertainty into cleanup.
    if word!=5 {
        state.last_failure=operation.err();
        if state.last_failure.is_none(){state.last_failure_action=None;}
        else if state.last_failure_action!=Some("renew_authority")||word!=1 {
            state.last_failure_action=Some(action);
        }
    }
    let value = receipt(&host,state,action)?;
    host.capability.require_live()?;
    if word==6 && value.get("peer_physical_cleanup").and_then(Value::as_str)==Some("terminal")
        && actual_java_cleanup(env)? {
        return close_host(value,host,checked);
    }
    Ok(value.to_string())
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_peerLifecycle(
    mut env:JNIEnv<'_>,_:JClass<'_>,word:jni::sys::jint)->jstring {
    let result=operate(&mut env,word);
    if env.exception_check().unwrap_or(true){let _=env.exception_clear();}
    return_string(&mut env,result)
}
