//! Host-only composition around complete production native owners. Platform
//! persistence below is test-owned; it never manufactures a lifecycle receipt.
use super::*;
use std::collections::BTreeMap;
use std::cell::Cell;
use std::sync::mpsc;
use std::io::{BufRead, Write};
use rusty_quest_media_stream_android::{AndroidMediaOwnerExecutor,AuthenticatedOwnerEffect,VerifiedOwnerEffect,DeterministicAndroidMediaOwnerExecutor};

thread_local! {static ACTIVE:Cell<usize>=const{Cell::new(0)};}
pub(crate) fn active()->usize{ACTIVE.with(Cell::get)}
pub(crate) fn capability()->Result<Arc<NativeCapability>,String>{
    process().lock().map_err(|_|"fixture fence observation poisoned")?.host.as_ref()
        .map(|h|h.capability.clone()).ok_or_else(||"fixture native capability absent".into())
}
fn on<T>(peer:usize,f:impl FnOnce()->T)->T{
    ACTIVE.with(|slot|{let prior=slot.replace(peer);let result=f();slot.set(prior);result})
}
static ORIGINALS:OnceLock<[Mutex<String>;2]>=OnceLock::new();
pub(crate) fn original(peer:usize)->Result<String,String>{
    let slots=ORIGINALS.get_or_init(||[Mutex::new(String::new()),Mutex::new(String::new())]);
    let id=slots[peer].lock().map_err(|_|"fixture Original poisoned")?.clone();
    if id.len()!=32{return Err("fixture actual command Original absent".into())}Ok(id)
}
static OUTPUT:Mutex<()>=Mutex::new(());
static NEXT:std::sync::atomic::AtomicU64=std::sync::atomic::AtomicU64::new(1);
static RETURNS:OnceLock<Mutex<BTreeMap<u64,mpsc::Sender<Value>>>>=OnceLock::new();
static FRAMES:OnceLock<Mutex<Vec<(usize,Vec<u8>,Vec<u8>)>>>=OnceLock::new();
fn emit(value:&Value){let _hold=OUTPUT.lock().unwrap();println!("{value}");std::io::stdout().flush().unwrap();}
pub(crate) fn signing_boundary(peer:usize,method:&str,message:&[u8],limit:usize){
    emit(&json!({"kind":"signing_boundary","peer":if peer==0{"Q"}else{"W"},
        "method":method,"bytes":message.len(),"limit":limit,"message_hex":hex(message),
        "input_within_production_bounds":!message.is_empty()&&message.len()<=limit}));
}
pub(crate) fn java(peer:usize,method:&str,payload:Value)->Result<Value,String>{
    let id=NEXT.fetch_add(1,Ordering::SeqCst);let(tx,rx)=mpsc::channel();
    RETURNS.get_or_init(Default::default).lock().map_err(|_|"fixture reply map")?.insert(id,tx);
    emit(&json!({"kind":"callback","id":id,"peer":if peer==0{"Q"}else{"W"},"method":method,"payload":payload}));
    let result=rx.recv().map_err(|_|"fixture Java callback lost")?;
    if let Some(error)=result.get("error"){return Err(format!("fixture Java callback {method}: {error}"))}Ok(result)
}

pub(crate) fn renderer(id:&str,terminal:bool)->Result<Value,String>{
    let identity=prepared_renderer_identity(id)?;
    let actual=java(active(),"renderer_status",json!({"preparation_id":id}))?;
    if actual["preparation_id"]!=id
        || actual["app_process_generation"]!=identity.app_process_generation
        || actual["native_executor_generation"]!=identity.native_executor_generation
        || actual["app_record_sha256"]!=identity.app_record_sha256
        || actual["config_sha256"]!=identity.config_sha256
        || actual["request_sha256"]!=identity.request_sha256
        || actual["resource_target_sha256"]!=identity.resource_target_sha256{
        return Err("fixture actual Java renderer identity differs".into())
    }
    if terminal{
        if actual["$schema"]!="rusty.quest.spatial.prepared_peer_renderer.v2"||actual["state"]!="terminal"||actual["source_hold_released"]!=true||actual["worker_joined"]!=true||actual["cleanup_terminal"]!=true{return Err("fixture renderer not released by real teardown".into())}
    }else if actual["state"]!="ready"||actual["source_hold_acquired"]!=true||actual["source_hold_released"]!=false||actual["prepare_calls_joined"]!=true||actual["frame_work_admitted"]!=false||actual["frame_import_count"]!=0||actual["present_count"]!=0{
        return Err("fixture renderer not dormant for actual prepared holder".into())
    }
    Ok(actual)
}

pub(crate) fn history_ack(peer:usize,raw:&str)->Result<Value,String>{
    use rusty_manifold_peer::*;
    use rusty_manifold_peer_runtime_host::{ManifoldFreshHistoryArchiveBody,fresh_history_body_checkpoint_sha256};
    use ed25519_dalek::Signer;
    let body:ManifoldFreshHistoryArchiveBody=serde_json::from_str(raw).map_err(safe_decode)?;
    if fresh_history_canonical(&body).map_err(str::to_owned)?!=raw.as_bytes(){return Err("fixture custody input not actual canonical bytes".into())}
    let owner=process().lock().map_err(|_|"fixture archive actor slot")?.host.clone().ok_or("fixture archive actor absent")?;
    if body.local_peer_id.as_str()!=owner.local_peer_id{return Err("fixture archive signer peer differs".into())}
    let hash=fresh_history_body_sha256(raw.as_bytes());
    let prior=body.prior_cursor.as_ref().map(|p|p.history_sha256.clone());
    let schema=|v:&str|serde_json::from_value(json!(v)).map_err(safe_decode);
    let context=ManifoldFreshHistoryCustodyContext{
        schema_id:schema("rusty.manifold.peer.fresh_history_durable_context.v1")?,
        archive_ordinal:body.archive_ordinal,archive_body_sha256:hash.clone(),prior_history_sha256:prior.clone(),
        history_sha256:fresh_history_chain_sha256(prior.as_deref(),body.archive_ordinal,&hash).map_err(str::to_owned)?,
        checkpoint_sha256:fresh_history_body_checkpoint_sha256(&body,&hash).map_err(|e|e.to_string())?,
        host_id:body.host_id.clone(),provider_epoch_id:body.provider_epoch_id.clone(),local_peer_id:body.local_peer_id.clone(),
        source_snapshot_sha256:body.source_snapshot_sha256.clone(),operation_binding:body.operation_binding.clone(),
        storage_identity:format!("modeled.app.private.archive.{}",body.archive_ordinal),
        signer_key_id:serde_json::from_value(json!(owner.callbacks.key_id())).map_err(safe_decode)?,
    };
    persist(peer,&format!("manifold-archive-{}",body.archive_ordinal),raw)?;
    let bytes=fresh_history_custody_signing_bytes(&context).map_err(str::to_owned)?;
    let ack=ManifoldFreshHistoryCustodyAck{schema_id:schema("rusty.manifold.peer.fresh_history_durable_ack.v1")?,context,
        signature_hex:hex(&ed25519_dalek::SigningKey::from_bytes(&[41+peer as u8;32]).sign(&bytes).to_bytes())};
    serde_json::to_value(ack).map_err(safe_decode)
}

pub(crate) fn exchange(peer:usize,frame:&[u8])->Result<Vec<u8>,String>{
    emit(&json!({"kind":"frame_boundary","peer":if peer==0{"Q"}else{"W"},"request_bytes":frame.len()}));
    let result=on(1-peer,||{
        if frame.starts_with(prepared_start::MAGIC){return prepared_start::handle_frame(frame)}
        let fresh=pair_ceremony::is_fresh_frame(frame);
        let host=host_for_setup(fresh)?;
        if fresh{return pair_ceremony::fresh_frame(&host,frame)}
        if frame.starts_with(pair_ceremony::FRAME_MAGIC){return pair_ceremony::handle_frame(&host,frame)}
        if frame.starts_with(retained_cleanup_host::PREPARE_MAGIC){return host.cleanup.prepare_frame(frame)}
        if let Ok(request)=serde_json::from_slice::<rusty_quest_media_stream_android::RetainedCleanupDispatchRequest>(frame){
            return Checkout::take(host.cleanup_server.clone())?.get().handle(&request)
        }
        host.owner_effect_attempted.store(true,Ordering::SeqCst);
        if decode_product_activation_request(frame).is_ok(){
            Checkout::take(host.activation_server.clone())?.get().handle_frame(frame)
        }else{Checkout::take(host.server.clone())?.get().handle_frame(frame)}
    });
    emit(&json!({"kind":"frame_return","peer":if peer==0{"Q"}else{"W"},"response_bytes":result.as_ref().ok().map(Vec::len),"error":result.as_ref().err()}));
    if let Ok(response)=&result{FRAMES.get_or_init(Default::default).lock().map_err(|_|"fixture frame log")?.push((peer,frame.to_vec(),response.clone()));}
    result
}

static EFFECTS:OnceLock<[Mutex<DeterministicAndroidMediaOwnerExecutor>;2]>=OnceLock::new();
pub(crate) fn execute(peer:usize,ticket:&AndroidMediaExecutionTicket,compensate:bool)->Result<AuthenticatedOwnerEffect,String>{
    // The codec/OS resource itself is modeled. Its actual typed ticket and
    // owner state are still consumed/verified by the production Composite,
    // signed dispatch server, Broker and Manifold owners.
    if ticket.owner_kind==rusty_quest_media_stream::MediaStreamOwnerKind::Sink{
        let step=if ticket.action_kind==rusty_quest_media_stream::MediaStreamOwnerActionKind::ArmReceiver{"receiver_start"}else{"receiver_stop"};
        java(peer,step,json!({"ticket":ticket}))?;
    }
    let stores=EFFECTS.get_or_init(||[Mutex::new(DeterministicAndroidMediaOwnerExecutor::new(79).unwrap()),Mutex::new(DeterministicAndroidMediaOwnerExecutor::new(80).unwrap())]);
    let mut owner=stores[peer].lock().map_err(|_|"fixture provider leaf poisoned")?;
    let readback=owner.execute(ticket,if compensate{AndroidMediaExecutionMode::CompensateUncertain}else{AndroidMediaExecutionMode::Execute})?;
    if !owner.verify(ticket,&readback){return Err("fixture provider readback differs".into())}
    let readback_json=serde_json::to_string(&readback).map_err(safe_decode)?;
    let digest=format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&readback_json));
    Ok(AuthenticatedOwnerEffect{remote_dispatch:None,verified:VerifiedOwnerEffect{
        schema_id:rusty_quest_media_stream_android::VERIFIED_OWNER_EFFECT_SCHEMA.into(),
        receipt_id:readback.receipt_id.clone(),readback_sha256:digest.clone(),executor_generation:readback.executor_generation,
        provider_state_revision:readback.provider_state_revision,observed_state:readback.observed_state.clone(),
        terminal:matches!(readback.observed_state.as_str(),"stopped"|"cleaned"),provider_handle_id:readback.provider_handle_id.clone(),detail_sha256:digest,
    },readback,readback_json})
}

static STORES: OnceLock<[Mutex<BTreeMap<String, String>>; 2]> = OnceLock::new();

pub(crate) fn persist(peer: usize, method: &str, body: &str) -> Result<(), String> {
    let stores = STORES.get_or_init(|| [Mutex::new(BTreeMap::new()), Mutex::new(BTreeMap::new())]);
    stores[peer]
        .lock()
        .map_err(|_| "fixture persistence poisoned")?
        .insert(method.to_owned(), body.to_owned());
    Ok(())
}

fn route(peer: usize) -> PackagedDuplexRoute {
    use super::super::packaged_route::{PackagedEndpoint, PackagedPeer, PackedStereoProfile};
    let peers = [0usize, 1].map(|i| PackagedPeer {
        peer_id: if i == 0 { "peer.quest-a" } else { "peer.quest-b" }.into(),
        installed_role_id: format!("role.fixture.{i}"),
        device_id: if i == 0 { "quest-a" } else { "pc-host" }.into(),
        left_camera_id: "left".into(),
        right_camera_id: "right".into(),
        media: PackagedEndpoint { protocol: "tcp".into(), host: format!("192.168.49.{}",2+i), port: 7777 + i as u16 },
        control: PackagedEndpoint { protocol: "tcp".into(), host: format!("192.168.49.{}",2+i), port: 8888 + i as u16 },
    });
    PackagedDuplexRoute {
        product_id: "broker.runtime.embedded".into(),
        package_name: "example.quest.runtime".into(),
        route_configuration_sha256: format!("sha256:{}", "5".repeat(64)),
        network_scope_id: "network.fixture".into(),
        profile: PackedStereoProfile {
            schema: "rusty.quest.packed_stereo_profile.v1".into(), profile_id: "fixture".into(),
            rmanvid_schema_version: 4, frame_layout: "side_by_side_left_right".into(), eye_order: vec!["left".into(), "right".into()],
            codec: "h264".into(), packed_width: 1920, packed_height: 1080, per_eye_width: 960, per_eye_height: 1080,
            frame_rate_hz: 60, bitrate_bps: 8_000_000, max_packet_bytes: 4_194_304,
            pair_timestamp_source: "modeled.monotonic".into(), pairing_policy: "bounded".into(),
            max_pair_delta_ns: 30_000_000, stale_eye_reuse_allowed: false, cpu_pixel_copy: false,
        },
        peers, installed_peer_index: peer,
        runtime_spec_ids: ["runtime.media.display-example".into(), "runtime.media.return-example".into()],
    }
}

fn bilateral_config(peer:usize,local:&str,now:u64)->Result<QuestBrokerRuntimeConfig,String>{
    let mut config=crate::fixture_config::with_renewal(crate::fixture_config::root_config(local,now+3_600_000,now+3_600_000));
    let mut outgoing=config.media_session.take().ok_or("fixture selected media binding absent")?;
    // The app's unchanged remote-history reader requires its Spatial SDK sink.
    // Keep the reusable display/capture source as a modeled platform leaf,
    // but select the actual typed app sink in BOTH spec/owner closure fields.
    for owner in &mut outgoing.quest.spec.owner_selections{
        if owner.owner_kind==rusty_quest_media_stream::MediaStreamOwnerKind::Sink{owner.provider_kind="meta_spatial_sdk_packed_stereo_sink".into();}
    }
    for sink in &mut outgoing.quest.spec.sinks{sink.sink_kind="meta_spatial_sdk_packed_stereo_sink".into();}
    outgoing.quest.runtime_spec_canonical_sha256=rusty_quest_media_stream::canonical_media_stream_runtime_sha256(&outgoing.quest.spec).map_err(|_|"fixture selected app sink digest")?;
    let raw=serde_json::to_string(&outgoing).map_err(safe_decode)?;
    // New independent reciprocal fixture graph. Identity substitutions are
    // test configuration DATA; canonical descriptor/spec hashes are recomputed
    // and the actual normal provider validator must accept the resulting data.
    let mut second:Value=serde_json::from_str(&raw.replace("display-example","return-example")
        .replace("quest_display_composite_to_pc","pc_return_composite_to_quest")
        .replace("quest-a-display","pc-host-return").replace("quest-a.display","pc-host.return")
        .replace("sink.pc.display","sink.quest.return").replace("owner.","owner.return.")).map_err(safe_decode)?;
    fn swap(v:&mut Value){match v{Value::String(s)=>{if s=="quest-a"{*s="pc-host".into()}else if s=="pc-host"{*s="quest-a".into()}},Value::Array(a)=>a.iter_mut().for_each(swap),Value::Object(o)=>o.values_mut().for_each(swap),_=>{}}}
    swap(&mut second);
    let mut incoming:rusty_quest_broker_authority::QuestBrokerMediaSessionProductBinding=serde_json::from_value(second).map_err(safe_decode)?;
    incoming.quest.runtime_spec_canonical_sha256=rusty_quest_media_stream::canonical_media_stream_runtime_sha256(&incoming.quest.spec).map_err(|_|"fixture reciprocal spec digest")?;
    incoming.manifold.descriptor_canonical_sha256=rusty_manifold_media_session::canonical_media_session_sha256(&incoming.manifold.descriptor).map_err(|_|"fixture reciprocal descriptor digest")?;
    let selected=if peer==0{&outgoing}else{&incoming};
    let packaged=config.packaged_authority.client_locks[0].media_lifecycle_authority.as_mut().ok_or("fixture lifecycle authority")?;
    let mut lock:Value=serde_json::from_str(&packaged.media_lifecycle_lock_json).map_err(safe_decode)?;
    lock["session_id"]=json!(selected.manifold.descriptor.session_id);
    lock["stream_id"]=json!(selected.manifold.descriptor.stream_ids[0]);
    lock["render_sink_id"]=json!(selected.manifold.descriptor.sink_ids[0]);
    lock["runtime_spec_id"]=json!(selected.quest.spec.runtime_spec_id);
    lock["runtime_spec_canonical_sha256"]=json!(selected.quest.runtime_spec_canonical_sha256);
    lock["manifold_descriptor_canonical_sha256"]=json!(selected.manifold.descriptor_canonical_sha256);
    packaged.media_lifecycle_lock_json=lock.to_string();
    packaged.media_lifecycle_lock_sha256=rusty_quest_broker_authority::packaged_json_sha256(&packaged.media_lifecycle_lock_json);
    packaged.media_binding_json=serde_json::to_string(selected).map_err(safe_decode)?;
    packaged.media_binding_sha256=rusty_quest_broker_authority::packaged_json_sha256(&packaged.media_binding_json);
    config.media_sessions=vec![outgoing,incoming];
    Ok(config)
}

pub(crate) fn initialize() -> Result<Value, String> {
    let mut actual = Vec::new();
    for peer in 0..2 {
        let route = route(peer);
        let local = route.peers[peer].peer_id.clone();
        let remote = route.peers[1-peer].peer_id.clone();
        let now = wall_ms()?;
        let mut config = bilateral_config(peer,&local,now)?;
        config.embedded_duplex.as_mut().unwrap().runtime_host_id=serde_json::from_value(json!(route.runtime_host_id(&local)?)).map_err(safe_decode)?;
        // App bootstrap requires the actual keystore convention, stricter than
        // the reusable Broker helper's arbitrary enrolled key identifiers.
        for key in &mut config.embedded_duplex.as_mut().unwrap().fresh_setup_enrollment_selection.as_mut().unwrap().keys {
            key.key_id=serde_json::from_value(json!(format!("ed25519.{}",key.public_key_sha256.strip_prefix("sha256:").unwrap()))).map_err(safe_decode)?;
            key.credential_id=serde_json::from_value(json!(format!("credential.duplex.{}.1",key.peer_id))).map_err(safe_decode)?;
        }
        let raw = serde_json::to_string(&config).map_err(safe_decode)?;
        let sha = rusty_quest_broker_authority::packaged_json_sha256(&raw);
        let capability = Arc::new(super::super::process_fence::NativeCapability {
            generation: 79+peer as u64,
            binding: super::super::process_fence::AppBinding { generation: 84+peer as u64, record_sha256: (if peer==0 {"a"}else{"b"}).repeat(64) },
        });
        let selected=&config.embedded_duplex.as_ref().unwrap().fresh_setup_enrollment_selection.as_ref().unwrap().keys;
        let bootstrap = Bootstrap {
            local_peer_id: local, remote_peer_id: remote.clone(),
            local_key_id: selected[peer].key_id.to_string(),
            remote_key_id: selected[1-peer].key_id.to_string(),
            remote_public_key_hex: hex(&ed25519_dalek::SigningKey::from_bytes(&[42-peer as u8;32]).verifying_key().to_bytes()),
            runtime_spec_id: route.runtime_spec_ids[peer].clone(), incoming_runtime_spec_id: route.runtime_spec_ids[1-peer].clone(),
            route_grant_id: "grant.fixture.unissued".into(), route_configuration_sha256: route.route_configuration_sha256.clone(),
            device_peers: route.peers.iter().map(|p|AndroidMediaDevicePeerPlacement{device_id:p.device_id.clone(),peer_id:p.peer_id.clone()}).collect(),
            replay: OwnerDispatchReplaySnapshot::default(), activation_replay: ProductActivationReplaySnapshot::default(),
        };
        let callbacks = JavaOwnerCallbacks::fixture(peer,capability.clone(),bootstrap.local_key_id.clone(),remote);
        let (host, initialized) = on(peer,||fixture_build_host(config,bootstrap,route,capability,callbacks))?;
        actual.push(json!({"peer":peer,"actor":{"app_process_generation":host.capability.binding.generation,
            "app_record_sha256":host.capability.binding.record_sha256,"native_executor_generation":host.capability.generation,
            "config_sha256":sha},"initialized":serde_json::from_str::<Value>(&initialized).map_err(safe_decode)?}));
        on(peer,||process().lock().unwrap().host=Some(host));
    }
    Ok(json!({"actual_two_role_construction":actual,"lifecycle_executed":false}))
}

pub(crate) fn run()->Result<(),String>{
    emit(&json!({"kind":"ready","result":initialize()?}));
    let mut workers=Vec::new();
    for line in std::io::stdin().lock().lines(){
        let input:Value=serde_json::from_str(&line.map_err(|_|"fixture stdin")?).map_err(safe_decode)?;
        match input["kind"].as_str(){
            Some("callback_reply")=>{
                let id=input["id"].as_u64().ok_or("fixture callback identity")?;
                let tx=RETURNS.get_or_init(Default::default).lock().map_err(|_|"fixture callback map")?.remove(&id).ok_or("unknown fixture callback")?;
                tx.send(input["result"].clone()).map_err(|_|"fixture callback no waiter")?;
            },
            Some("command")=>{
                let peer=match input["peer"].as_str(){Some("Q")=>0,Some("W")=>1,_=>return Err("fixture command peer".into())};
                workers.push(std::thread::spawn(move||on(peer,||{
                    let result=(||->Result<Value,String>{
                        let id=input["original_id"].as_str().ok_or("fixture command Original")?;
                        *ORIGINALS.get_or_init(||[Mutex::new(String::new()),Mutex::new(String::new())])[peer].lock().map_err(|_|"fixture Original")?=id.into();
                        let operation=input["operation"].as_str().ok_or("fixture command operation")?;
                        if operation=="request_fresh_stop"{
                            let id={process().lock().map_err(|_|"fixture fresh Stop observation")?.fresh_setup_request.clone().ok_or("actual fresh request absent")?};
                            return serde_json::from_str(&request_fresh_setup_stop(&id)?).map_err(safe_decode)
                        }
                        if operation=="controls"{
                            let h=host()?;let session=input["session"].as_str().ok_or("actual prior session absent")?;
                            return Ok(json!({"pair":pair_ceremony::fixture_archive_controls(&h,session)?,
                                "lifetime":peer_lifecycle::fixture_controls::run(&h,session)?}))
                        }
                        if operation=="drift"{
                            let h={process().lock().unwrap().host.clone().ok_or("fixture barrier Host absent")?};
                            return pair_ceremony::fixture_archive_drift(&h)
                        }
                        if operation=="replay_renewal"{
                            let prior={FRAMES.get_or_init(Default::default).lock().map_err(|_|"fixture frame log")?.iter().rev().find(|(from,request,_)|*from==peer&&String::from_utf8_lossy(request).contains("renew_finish_b")).cloned().ok_or("actual renewal frame absent")?};
                            return match exchange(peer,&prior.1){Ok(reply)=>Ok(json!({"exact_reply":reply==prior.2,"request_sha256":rusty_quest_broker_authority::packaged_json_sha256(&String::from_utf8_lossy(&prior.1)),"response_bytes":reply.len()})),Err(error)=>Ok(json!({"rejected":error}))}
                        }
                        if operation=="view_controls"{
                            let frames=FRAMES.get_or_init(Default::default).lock().map_err(|_|"fixture actual frames busy")?.iter()
                                .filter(|(from,frame,_)|*from==0&&frame.starts_with(b"RQFS2")&&serde_json::from_slice::<Value>(&frame[5..]).ok().and_then(|v|v.pointer("/body/kind").and_then(Value::as_str).map(str::to_owned)).as_deref()==Some("view"))
                                .map(|(_,request,response)|(request.clone(),response.clone())).collect::<Vec<_>>();
                            let h=host()?;return pair_ceremony::fixture_view_controls(&h,&frames)
                        }
                        if operation=="projection_roundtrip"{
                            let wire=input.get("wire").ok_or("fixture actual exported receipt absent")?;
                            let full=super::renewal_projection::expand(wire)?;
                            let h=host()?;
                            let local=h.peer_lifecycle.lock().map_err(|_|"fixture actual lifecycle busy")?;
                            let actual=serde_json::to_value(local.as_ref().ok_or("fixture lifecycle absent")?).map_err(safe_decode)?;
                            let retained=actual["renewals"].as_array().ok_or("actual full renewals absent")?.iter()
                                .find(|v|v.get("request_id")==full.get("request_id")).ok_or("actual full receipt not retained")?;
                            if retained!=&full{return Err("decoded receipt differs from actual full retained authority".into())}
                            let projected=super::renewal_projection::project(&full)?;
                            if &projected!=wire{return Err("actual export does not round-trip canonically".into())}
                            return Ok(json!({"full":full,"export":projected,"full_retained_equal":true,
                                "full_utf8_bytes":serde_json::to_vec(retained).map_err(safe_decode)?.len(),
                                "export_utf8_bytes":serde_json::to_vec(wire).map_err(safe_decode)?.len()}))
                        }
                        if operation=="snapshot"{
                            let h={process().lock().map_err(|_|"fixture observed process busy")?.host.clone().ok_or("fixture observed Host absent")?};
                            h.capability.require_live()?;
                            let pair=serde_json::to_value(&*h.pair_state.lock().map_err(|_|"fixture observed pair busy")?).map_err(safe_decode)?;
                            let local=h.peer_lifecycle.lock().map_err(|_|"fixture observed lifecycle busy")?;
                            let lifetime=serde_json::to_value(local.as_ref().ok_or("fixture observed lifetime checked out")?).map_err(safe_decode)?;
                            drop(local);
                            let authority:Value=serde_json::from_str(&h.authority.snapshot_json()?).map_err(safe_decode)?;
                            return Ok(json!({"actual_pair_state":pair,"actual_lifetime_state":lifetime,"actual_authority_snapshot":authority}))
                        }
                        if operation=="advance"{let to=input["wall_ms"].as_u64().ok_or("fixture wall clock")?;let old=crate::fixture_os::NOW.load(Ordering::SeqCst);if to<old{return Err("fixture clock cannot reverse".into())}crate::fixture_os::NOW.store(to,Ordering::SeqCst);return Ok(json!({"wall_ms":to}))}
                        let raw=match operation{
                            "sdk"=>{let h=host()?;prepared_start::run(&h)?},
                            "pair"=>{let h=host_for_setup(true)?;pair_ceremony::fresh_setup(&h)?},
                            "start"|"renew"|"stop"|"status"=>{
                                // The selected operate arms do not call JNIEnv;
                                // any accidental JNI access hits a null table.
                                let table:jni::sys::JNINativeInterface_=unsafe{std::mem::zeroed()};
                                let pointer=&table as *const _;let mut env=unsafe{jni::JNIEnv::from_raw(&pointer as *const _ as *mut _)}.map_err(|_|"fixture JNI interface")?;
                                peer_lifecycle::operate(&mut env,match operation{"start"=>1,"renew"=>2,"stop"=>3,_=>5})?
                            },
                            _=>return Err("unsupported fixture command".into())
                        };
                        emit(&json!({"kind":"native_return_boundary","peer":input["peer"],"operation":operation,
                            "utf8_bytes":raw.len(),"utf16_characters":raw.encode_utf16().count(),"raw":raw}));
                        serde_json::from_str(&raw).map_err(safe_decode)
                    })();
                    emit(&json!({"kind":"result","id":input["id"],"peer":input["peer"],"result":match result{Ok(v)=>v,Err(e)=>{
                        let owner={process().lock().ok().and_then(|p|p.host.clone())};
                        let observed=owner.and_then(|h|pair_ceremony::status(&h).ok()).and_then(|s|serde_json::from_str::<Value>(&s).ok());
                        json!({"error":e,"actual_pair_status":observed})
                    }}}));
                })));
            },
            _=>return Err("fixture protocol kind".into())
        }
    }
    for worker in workers{worker.join().map_err(|_|"fixture command panic")?;}
    Ok(())
}
