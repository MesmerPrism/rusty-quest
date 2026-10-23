use super::*;
use std::{
    sync::{mpsc, Barrier},
    thread,
    time::Duration,
};
fn ticket(id: &str) -> AndroidMediaExecutionTicket {
    serde_json::from_value(json!({"$schema":"rusty.quest.android.media.execution-ticket.v1","capability":format!("capability.{id}"),"executor_generation":70,"action_id":format!("action.{id}"),"authority_epoch_id":"epoch.fixture","media_acceptance_authority_revision":1,"expected_runtime_revision":1,"client_id":"client.fixture","lease_id":"lease.fixture","sequence":1,"operation":"start","owner_kind":"cleanup","action_kind":"arm_cleanup","owner_id":"owner.cleanup","provider_kind":"cleanup.fixture","resource_id":id})).unwrap()
}
fn setup() -> (Cleanup, Arc<Leaves>) {
    let leaves = Arc::new(Leaves::default());
    leaves.live.store(true, Ordering::SeqCst);
    let callbacks = JavaOwnerCallbacks(leaves.clone());
    let cleanup = Cleanup::new(
        QuestEmbeddedDuplexAuthority::default(),
        callbacks,
        AuthorityClock(Arc::new(std::sync::atomic::AtomicU64::new(100))),
        "peer.local".into(),
        "peer.remote".into(),
        "key.remote".into(),
        [3; 32],
        70,
        Default::default(),
    )
    .unwrap();
    (cleanup, leaves)
}
fn execute(cleanup: Cleanup, id: &str) -> Result<AuthenticatedOwnerEffect, String> {
    let callbacks = cleanup.callbacks.clone();
    RetainingRegistry { cleanup, callbacks }.execute_and_verify(
        None,
        &ticket(id),
        AndroidMediaExecutionMode::Execute,
    )
}
fn overlap(persist: bool, fail: bool) {
    let (c, l) = setup();
    let entered = Arc::new(Barrier::new(2));
    let release = Arc::new(Barrier::new(2));
    let once = Arc::new(AtomicBool::new(false));
    let hook: Arc<dyn Fn() -> Result<(), String> + Send + Sync> = Arc::new({
        let entered = entered.clone();
        let release = release.clone();
        move || {
            if !once.swap(true, Ordering::SeqCst) {
                entered.wait();
                release.wait();
                if fail {
                    return Err("modeled persistence failed".into());
                }
            }
            Ok(())
        }
    });
    if persist {
        *l.persist_hook.lock().unwrap() = Some(hook)
    } else {
        *l.effect_hook.lock().unwrap() = Some(hook)
    }
    let first = thread::spawn({
        let c = c.clone();
        move || execute(c, "first")
    });
    entered.wait();
    let (tx, rx) = mpsc::channel();
    let second = thread::spawn({
        let c = c.clone();
        move || tx.send(execute(c, "second").map(|_| ())).unwrap()
    });
    let early = rx.recv_timeout(Duration::from_millis(100));
    release.wait();
    let a = first.join().unwrap();
    second.join().unwrap();
    assert!(
        early.is_err(),
        "second returned before owner joined: {early:?}"
    );
    let b = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    if fail {
        assert!(a.is_err());
        assert_eq!(b.unwrap_err(), "cleanup durable commit uncertain");
        assert!(l.effects.lock().unwrap().is_empty())
    } else {
        a.unwrap();
        b.unwrap();
        assert_eq!(*l.effects.lock().unwrap(), vec!["first", "second"]);
        let state = c.state.lock().unwrap();
        assert_eq!(state.originals.len(), 2);
        assert!(state.originals.values().all(|o| o.effect.is_some()));
        let saved: PreparedState = serde_json::from_str(&l.saved.lock().unwrap()).unwrap();
        assert_eq!(saved.originals.len(), 2)
    }
}
#[test]
fn waits_for_successful_inflight_persist() {
    overlap(true, false)
}
#[test]
fn waits_for_postpersist_owner_callback() {
    overlap(false, false)
}
#[test]
fn failed_persist_keeps_waiter_uncertain() {
    overlap(true, true)
}

#[test]
fn same_thread_reentry_is_busy_without_a_held_mutex() {
    let (c, l) = setup();
    let observed = Arc::new(AtomicBool::new(false));
    *l.persist_hook.lock().unwrap() = Some(Arc::new({
        let c = c.clone();
        let observed = observed.clone();
        move || {
            // An occupied lease is visible, but the admission mutex remains available.
            assert!(c.serial.try_enter().unwrap().is_none());
            assert_eq!(
                execute(c.clone(), "reentrant").err().as_deref(),
                Some("runtime busy")
            );
            observed.store(true, Ordering::SeqCst);
            Ok(())
        }
    }));
    execute(c, "first").unwrap();
    assert!(observed.load(Ordering::SeqCst));
    *l.persist_hook.lock().unwrap() = None;
}
fn owner_projection() -> OwnerDispatchAuthorityProjection {
    serde_json::from_value(json!({"$schema":"rusty.quest.c1.owner_projection.v1","authority_peer_id":"peer.local","executor_peer_id":"peer.local","peer_session_id":"session.fixture","route_grant_id":"grant.fixture","route_authority_revision":1,"authority_runtime_host_id":"host.fixture","authority_provider_epoch_id":"epoch.fixture","platform_runtime_spec_id":"runtime.fixture","authority_client_id":"client.fixture","authority_runtime_lease_id":"lease.fixture","signed_topology_sha256":format!("sha256:{}","1".repeat(64)),"route_configuration_sha256":format!("sha256:{}","2".repeat(64)),"route_authority_evidence_sha256":format!("sha256:{}","3".repeat(64)),"expires_at_ms":200,"authorization_kind":"current_route"})).unwrap()
}
#[test]
fn authority_expiry_and_retirement_during_wait_precede_effect() {
    for expire in [false, true] {
        NOW.store(100, Ordering::SeqCst);
        let (c, l) = setup();
        *c.authority.owner.lock().unwrap() = Some(owner_projection());
        let entered = Arc::new(Barrier::new(2));
        let release = Arc::new(Barrier::new(2));
        let once = Arc::new(AtomicBool::new(false));
        *l.effect_hook.lock().unwrap() = Some(Arc::new({
            let entered = entered.clone();
            let release = release.clone();
            move || {
                if !once.swap(true, Ordering::SeqCst) {
                    entered.wait();
                    release.wait();
                }
                Ok(())
            }
        }));
        let first = thread::spawn({
            let c = c.clone();
            move || execute(c, "first")
        });
        entered.wait();
        let (tx, rx) = mpsc::channel();
        let second = thread::spawn({
            let c = c.clone();
            move || {
                let callbacks = c.callbacks.clone();
                tx.send(
                    RetainingRegistry {
                        cleanup: c,
                        callbacks,
                    }
                    .execute_and_verify(
                        Some(&owner_projection()),
                        &ticket("second"),
                        AndroidMediaExecutionMode::Execute,
                    )
                    .map(|_| ()),
                )
                .unwrap()
            }
        });
        assert!(rx.recv_timeout(Duration::from_millis(100)).is_err());
        if expire {
            NOW.store(300, Ordering::SeqCst)
        } else {
            l.live.store(false, Ordering::SeqCst)
        }
        release.wait();
        first.join().unwrap().unwrap();
        second.join().unwrap();
        assert_eq!(
            rx.recv().unwrap().unwrap_err(),
            if expire {
                "modeled authority expired"
            } else {
                "modeled authority retired"
            }
        );
        assert_eq!(*l.effects.lock().unwrap(), vec!["first"]);
        assert!(c.state.lock().unwrap().originals[&key(&ticket("second"))]
            .effect
            .is_none());
    }
    NOW.store(100, Ordering::SeqCst);
}
fn cleanup_request(c: &Cleanup) -> RetainedCleanupDispatchRequest {
    let a:RetainedCleanupAuthorityProjection=serde_json::from_value(json!({"$schema":RETAINED_CLEANUP_PROJECTION_SCHEMA,"authority_peer_id":"peer.remote","executor_peer_id":"peer.local","peer_session_id":"session.fixture","route_grant_id":"grant.fixture","route_authority_revision":7,"provider_epoch_id":"epoch.fixture","platform_runtime_spec_id":"runtime.fixture","target_client_id":"client.fixture","target_runtime_lease_id":"lease.fixture","requester_id":"client.revoker","requester_runtime_lease_id":"lease.revoker","trusted_revoker":true,"cleanup_target_sha256":format!("sha256:{}","a".repeat(64)),"terminal_route_sha256":format!("sha256:{}","b".repeat(64)),"signed_topology_sha256":format!("sha256:{}","c".repeat(64)),"historical_topology_expires_at_ms":50,"requester_expires_at_ms":200,"expires_at_ms":180})).unwrap();
    let mut t = ticket("stopped");
    t.operation = MediaStreamPlatformOperation::Stop;
    t.action_kind = MediaStreamOwnerActionKind::Stop;
    let mode = AndroidMediaExecutionMode::Execute;
    let mut request = RetainedCleanupDispatchRequest {
        schema_id: RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA.into(),
        dispatch_id: "dispatch.cleanup".into(),
        sequence: 1,
        issued_at_ms: 100,
        target_peer_id: c.local.clone(),
        cleanup: RetainedCleanupExecutionTicket {
            schema_id: RETAINED_CLEANUP_TICKET_SCHEMA.into(),
            authority: a.clone(),
            target: t.clone(),
            mode,
            owner_effect_sha256: format!(
                "sha256:{}",
                crate::packaged_json_sha256(&serde_json::to_string(&(&a, &t, mode)).unwrap())
            ),
        },
        signer_key_id: c.remote_key_id.clone(),
        signature_base64: String::new(),
    };
    sign_retained_cleanup_request(&mut request, &c.callbacks).unwrap();
    *c.authority.cleanup.lock().unwrap() = Some(a.clone());
    c.state.lock().unwrap().prepared.insert(
        request.dispatch_id.clone(),
        RetainedCleanupPreparedStop {
            schema_id: "rusty.quest.android.media.retained_cleanup_prepared_stop.v1".into(),
            prepare_request_sha256: format!("sha256:{}", "a".repeat(64)),
            dispatch_id: request.dispatch_id.clone(),
            target_preparation_revision: 1,
            source_ticket: t.clone(),
            target_ticket: t,
            authority: a,
            signer_key_id: c.callbacks.key_id().into(),
            signature_base64: String::new(),
        },
    );
    request
}
#[test]
fn current_source_waits_and_uses_post_admission_time() {
    for expire in [false, true] {
        let (mut c, l) = setup();
        c.remote_key = c.callbacks.local_public_key().unwrap();
        c.remote_key_id = c.callbacks.key_id().into();
        let request = cleanup_request(&c);
        let entered = Arc::new(Barrier::new(2));
        let release = Arc::new(Barrier::new(2));
        let once = Arc::new(AtomicBool::new(false));
        *l.persist_hook.lock().unwrap() = Some(Arc::new({
            let entered = entered.clone();
            let release = release.clone();
            move || {
                if !once.swap(true, Ordering::SeqCst) {
                    entered.wait();
                    release.wait();
                }
                Ok(())
            }
        }));
        let first = thread::spawn({
            let c = c.clone();
            move || execute(c, "first")
        });
        entered.wait();
        let (tx, rx) = mpsc::channel();
        let second = thread::spawn({
            let c = c.clone();
            move || {
                tx.send(c.current_source(&request, 100).map(|_| ()))
                    .unwrap()
            }
        });
        assert!(rx.recv_timeout(Duration::from_millis(100)).is_err());
        c.clock
            .0
            .store(if expire { 300 } else { 120 }, Ordering::SeqCst);
        release.wait();
        first.join().unwrap().unwrap();
        second.join().unwrap();
        let result = rx.recv().unwrap();
        assert_eq!(
            PROJECTION_NOW.load(Ordering::SeqCst),
            if expire { 300 } else { 120 }
        );
        if expire {
            assert_eq!(result.unwrap_err(), "prepared cleanup expired")
        } else {
            result.unwrap()
        };
        assert_eq!(*l.effects.lock().unwrap(), vec!["first"]);
    }
}
#[test]
fn quiescence_readers_remain_nonwaiting() {
    let (c, l) = setup();
    *l.effect_hook.lock().unwrap() = Some(Arc::new({
        let c = c.clone();
        move || {
            assert!(c.construction_snapshot().is_err());
            assert!(c.fresh_setup_quiescence(&Default::default()).is_err());
            Ok(())
        }
    }));
    execute(c, "first").unwrap();
    *l.effect_hook.lock().unwrap() = None;
}

#[test]
fn prepare_frame_waits_then_rechecks_request_freshness() {
    let (c, l) = setup();
    let entered = Arc::new(Barrier::new(2));
    let release = Arc::new(Barrier::new(2));
    let once = Arc::new(AtomicBool::new(false));
    *l.persist_hook.lock().unwrap() = Some(Arc::new({
        let entered = entered.clone();
        let release = release.clone();
        move || {
            if !once.swap(true, Ordering::SeqCst) {
                entered.wait();
                release.wait();
            }
            Ok(())
        }
    }));
    let mut t = ticket("prepared");
    t.operation = MediaStreamPlatformOperation::Stop;
    t.action_kind = MediaStreamOwnerActionKind::Stop;
    let request = Prepare {
        schema_id: "rusty.quest.android.media.retained_cleanup_prepare_request.v1".into(),
        dispatch_id: "dispatch.prepare".into(),
        source_ticket: t,
        requester_id: "client.fixture".into(),
        requester_lease_id: "lease.fixture".into(),
        route_grant_id: "grant.fixture".into(),
        sequence: 1,
        issued_at_ms: 100,
        signer_key_id: c.remote_key_id.clone(),
        signature_base64: String::new(),
        authority: None,
        renewal_ancestry: vec![],
    };
    let mut frame = PREPARE_MAGIC.to_vec();
    frame.extend(encode(&request).unwrap());
    let first = thread::spawn({
        let c = c.clone();
        move || execute(c, "first")
    });
    entered.wait();
    let (tx, rx) = mpsc::channel();
    let second = thread::spawn({
        let c = c.clone();
        move || tx.send(c.prepare_frame(&frame)).unwrap()
    });
    assert!(rx.recv_timeout(Duration::from_millis(100)).is_err());
    c.clock.0.store(40_000, Ordering::SeqCst);
    release.wait();
    first.join().unwrap().unwrap();
    second.join().unwrap();
    assert_eq!(
        rx.recv().unwrap().unwrap_err(),
        "cleanup prepare identity/freshness"
    );
    assert_eq!(*l.effects.lock().unwrap(), vec!["first"]);
}
#[test]
fn current_source_rechecks_changed_authority_and_complete_signature() {
    let (mut c, _) = setup();
    c.remote_key = c.callbacks.local_public_key().unwrap();
    c.remote_key_id = c.callbacks.key_id().into();
    let request = cleanup_request(&c);
    let mut changed = c.authority.cleanup.lock().unwrap().clone().unwrap();
    changed.route_authority_revision += 1;
    *c.authority.cleanup.lock().unwrap() = Some(changed);
    assert_eq!(
        c.current_source(&request, 100).err().as_deref(),
        Some("prepared cleanup authority/target changed")
    );
    *c.authority.cleanup.lock().unwrap() = Some(request.cleanup.authority.clone());
    let mut corrupt = request.clone();
    corrupt.signature_base64 = "A".repeat(88);
    assert!(c.current_source(&corrupt, 100).is_err());
    c.current_source(&request, 100)
        .expect("complete actual signed request validates before effects");
}

#[test]
fn changed_live_route_after_wait_is_rechecked_before_persist_or_effect() {
    NOW.store(100, Ordering::SeqCst);
    let (c, l) = setup();
    *c.authority.owner.lock().unwrap() = Some(owner_projection());
    let entered = Arc::new(Barrier::new(2));
    let release = Arc::new(Barrier::new(2));
    let once = Arc::new(AtomicBool::new(false));
    *l.effect_hook.lock().unwrap() = Some(Arc::new({
        let entered = entered.clone();
        let release = release.clone();
        move || {
            if !once.swap(true, Ordering::SeqCst) {
                entered.wait();
                release.wait();
            }
            Ok(())
        }
    }));
    let first = thread::spawn({
        let c = c.clone();
        move || execute(c, "first")
    });
    entered.wait();
    let (tx, rx) = mpsc::channel();
    let second = thread::spawn({
        let c = c.clone();
        move || {
            let callbacks = c.callbacks.clone();
            tx.send(
                RetainingRegistry {
                    cleanup: c,
                    callbacks,
                }
                .execute_and_verify(
                    Some(&owner_projection()),
                    &ticket("second"),
                    AndroidMediaExecutionMode::Execute,
                )
                .map(|_| ()),
            )
            .unwrap()
        }
    });
    assert!(rx.recv_timeout(Duration::from_millis(100)).is_err());
    c.authority
        .owner
        .lock()
        .unwrap()
        .as_mut()
        .unwrap()
        .route_authority_revision += 1;
    release.wait();
    first.join().unwrap().unwrap();
    second.join().unwrap();
    assert_eq!(
        rx.recv().unwrap().err().as_deref(),
        Some("cleanup admission current authority differs")
    );
    assert_eq!(*l.effects.lock().unwrap(), vec!["first"]);
    assert!(!c
        .state
        .lock()
        .unwrap()
        .originals
        .contains_key(&key(&ticket("second"))));
}

#[test]
fn refreshed_observation_digest_is_not_a_different_route() {
    NOW.store(100, Ordering::SeqCst);
    let (c, l) = setup();
    let held = owner_projection();
    let mut refreshed = held.clone();
    refreshed.route_authority_evidence_sha256 = format!("sha256:{}", "f".repeat(64));
    *c.authority.owner.lock().unwrap() = Some(refreshed);
    let callbacks = c.callbacks.clone();
    RetainingRegistry {
        cleanup: c,
        callbacks,
    }
    .execute_and_verify(
        Some(&held),
        &ticket("same-route"),
        AndroidMediaExecutionMode::Execute,
    )
    .unwrap();
    assert_eq!(*l.effects.lock().unwrap(), vec!["same-route"]);
}

struct SignedVerifier {
    source: String,
    target: String,
    key: [u8; 32],
}
impl OwnerDispatchAuthorityVerifier for SignedVerifier {
    fn verify_current(
        &self,
        r: &OwnerDispatchRequest,
        bytes: &[u8],
        sig: &[u8; 64],
    ) -> Result<(), String> {
        if r.authority.authority_peer_id != self.source
            || r.target_peer_id != self.target
            || r.authority.executor_peer_id != self.target
            || r.authority.expires_at_ms <= 100
        {
            return Err("modeled receiving authority differs".into());
        }
        ed25519_dalek::VerifyingKey::from_bytes(&self.key)
            .unwrap()
            .verify_strict(bytes, &ed25519_dalek::Signature::from_bytes(sig))
            .map_err(|_| "actual signature differs".into())
    }
}
#[derive(Clone, Default)]
struct Replay(Arc<Mutex<OwnerDispatchReplaySnapshot>>);
impl OwnerDispatchReplayStore for Replay {
    fn commit(&mut self, s: &OwnerDispatchReplaySnapshot) -> Result<(), String> {
        *self.0.lock().unwrap() = s.clone();
        Ok(())
    }
}
type PeerServer = OwnerDispatchServer<SignedVerifier, RetainingRegistry, JavaOwnerCallbacks>;
struct InMemoryTransport {
    source: Cleanup,
    target: Arc<Mutex<PeerServer>>,
    exchanges: Arc<std::sync::atomic::AtomicU64>,
}
impl OwnerDispatchTransport for InMemoryTransport {
    fn exchange(&mut self, frame: &[u8], max: usize) -> Result<Vec<u8>, String> {
        // This actual outgoing edge must not carry a local transaction into its peer.
        let idle = self
            .source
            .serial
            .try_enter()?
            .ok_or("local cleanup lease crossed peer exchange")?;
        drop(idle);
        self.exchanges.fetch_add(1, Ordering::SeqCst);
        let bytes = self.target.lock().unwrap().handle_frame(frame)?;
        if bytes.len() > max {
            return Err("actual response exceeds bound".into());
        }
        Ok(bytes)
    }
}
struct Routes {
    local: String,
}
impl CurrentOwnerProjectionSource for Routes {
    fn current_projection(
        &self,
        _: &AndroidMediaExecutionTicket,
        target: &str,
        _: AndroidMediaExecutionMode,
        _: u64,
    ) -> Result<OwnerDispatchAuthorityProjection, String> {
        let mut p = owner_projection();
        p.authority_peer_id = self.local.clone();
        p.executor_peer_id = target.into();
        Ok(p)
    }
}
fn peer(local: &str, remote: &str) -> (Cleanup, Arc<Leaves>, Arc<Mutex<PeerServer>>, Replay) {
    let (mut c, l) = setup();
    c.local = local.into();
    c.remote = remote.into();
    c.remote_key = c.callbacks.local_public_key().unwrap();
    c.remote_key_id = c.callbacks.key_id().into();
    let mut projection = owner_projection();
    projection.authority_peer_id = local.into();
    projection.executor_peer_id = local.into();
    *c.authority.owner.lock().unwrap() = Some(projection);
    let replay = Replay::default();
    let server = OwnerDispatchServer::restore(
        local.into(),
        SignedVerifier {
            source: remote.into(),
            target: local.into(),
            key: c.remote_key,
        },
        RetainingRegistry {
            cleanup: c.clone(),
            callbacks: c.callbacks.clone(),
        },
        c.callbacks.clone(),
        Box::new(c.clock.clone()),
        Default::default(),
        Box::new(replay.clone()),
    )
    .unwrap();
    (c, l, Arc::new(Mutex::new(server)), replay)
}
fn owners(
    c: &Cleanup,
    server: Arc<Mutex<PeerServer>>,
    exchanges: Arc<std::sync::atomic::AtomicU64>,
) -> CompositeAndroidMediaOwnerExecutor {
    use rusty_quest_media_stream::MediaStreamOwnerKind as K;
    let kinds = [
        K::Source,
        K::Processor,
        K::Route,
        K::Socket,
        K::Codec,
        K::Sink,
        K::Cleanup,
    ];
    let placements = kinds
        .into_iter()
        .map(|kind| AndroidMediaOwnerPlacement {
            owner_kind: kind,
            owner_id: if kind == K::Sink {
                "owner.sink"
            } else {
                "owner.cleanup"
            }
            .into(),
            provider_kind: if kind == K::Sink {
                "sink.fixture"
            } else {
                "cleanup.fixture"
            }
            .into(),
            resource_id: if kind == K::Sink {
                "remote.sink"
            } else {
                "local.cleanup"
            }
            .into(),
            target: if kind == K::Sink {
                AndroidMediaOwnerPlacementTarget::Remote {
                    peer_id: c.remote.clone(),
                }
            } else {
                AndroidMediaOwnerPlacementTarget::Local
            },
        })
        .collect();
    let remote = RemoteOwnerDispatchExecutor::new(
        InMemoryTransport {
            source: c.clone(),
            target: server,
            exchanges,
        },
        c.callbacks.clone(),
        c.remote_key,
        c.remote_key_id.clone(),
    )
    .unwrap();
    CompositeAndroidMediaOwnerExecutor::new(
        70,
        c.local.clone(),
        placements,
        Box::new(RetainingRegistry {
            cleanup: c.clone(),
            callbacks: c.callbacks.clone(),
        }),
        Box::new(remote),
        Box::new(Routes {
            local: c.local.clone(),
        }),
        Box::new(c.clock.clone()),
    )
    .unwrap()
}
fn lane_ticket(sink: bool) -> AndroidMediaExecutionTicket {
    let mut t = ticket(if sink { "remote.sink" } else { "local.cleanup" });
    if sink {
        t.owner_kind = rusty_quest_media_stream::MediaStreamOwnerKind::Sink;
        t.action_kind = MediaStreamOwnerActionKind::ArmReceiver;
        t.owner_id = "owner.sink".into();
        t.provider_kind = "sink.fixture".into()
    }
    t
}
#[test]
fn actual_two_peer_signed_overlap_joins_without_a_cross_peer_lease() {
    NOW.store(100, Ordering::SeqCst);
    let (a, al, asrv, ar) = peer("peer.a", "peer.b");
    let (b, bl, bsrv, br) = peer("peer.b", "peer.a");
    let exchanges = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let mut ao = owners(&a, bsrv.clone(), exchanges.clone());
    let mut bo = owners(&b, asrv.clone(), exchanges.clone());
    // Both directions exercise a real incoming server waiting on its peer's real
    // local persist. No modeled remote effect/proof is substituted for production.
    for forward in [true, false] {
        let leaves = if forward { al.clone() } else { bl.clone() };
        let entered = Arc::new(Barrier::new(2));
        let release = Arc::new(Barrier::new(2));
        let once = Arc::new(AtomicBool::new(false));
        *leaves.persist_hook.lock().unwrap() = Some(Arc::new({
            let entered = entered.clone();
            let release = release.clone();
            move || {
                if !once.swap(true, Ordering::SeqCst) {
                    entered.wait();
                    release.wait();
                }
                Ok(())
            }
        }));
        let (mut local, mut remote) = if forward { (ao, bo) } else { (bo, ao) };
        let first = thread::spawn(move || {
            let t = lane_ticket(false);
            let r = local.execute(&t, AndroidMediaExecutionMode::Execute);
            assert!(r.as_ref().is_ok_and(|v| local.verify(&t, v)));
            (local, r)
        });
        entered.wait();
        let (tx, rx) = mpsc::channel();
        let second = thread::spawn(move || {
            let t = lane_ticket(true);
            let r = remote.execute(&t, AndroidMediaExecutionMode::Execute);
            assert!(r.as_ref().is_ok_and(|v| remote.verify(&t, v)));
            tx.send(r.is_ok()).unwrap();
            remote
        });
        assert!(rx.recv_timeout(Duration::from_millis(100)).is_err());
        release.wait();
        let (local, r) = first.join().unwrap();
        r.unwrap();
        let remote = second.join().unwrap();
        assert!(rx.recv().unwrap());
        *leaves.persist_hook.lock().unwrap() = None;
        if forward {
            ao = local;
            bo = remote
        } else {
            bo = local;
            ao = remote
        }
    }
    assert_eq!(exchanges.load(Ordering::SeqCst), 2);
    for (c, l, r) in [(&a, &al, &ar), (&b, &bl, &br)] {
        assert_eq!(c.state.lock().unwrap().originals.len(), 2);
        assert!(c
            .state
            .lock()
            .unwrap()
            .originals
            .values()
            .all(|o| o.effect.is_some()));
        assert_eq!(
            serde_json::from_str::<PreparedState>(&l.saved.lock().unwrap())
                .unwrap()
                .originals
                .len(),
            2
        );
        assert_eq!(r.0.lock().unwrap().terminal.len(), 1)
    }
}
#[test]
fn actual_remote_failure_then_distinct_abort_and_stop_keeps_receiving_server_available() {
    NOW.store(100, Ordering::SeqCst);
    let (a, _, _, _) = peer("peer.a", "peer.b");
    let (b, bl, bsrv, replay) = peer("peer.b", "peer.a");
    let exchanges = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let mut owner = owners(&a, bsrv, exchanges.clone());
    let once = Arc::new(AtomicBool::new(false));
    *bl.effect_hook.lock().unwrap() = Some(Arc::new(move || {
        if !once.swap(true, Ordering::SeqCst) {
            Err("modeled genuine provider failure".into())
        } else {
            Ok(())
        }
    }));
    let start = lane_ticket(true);
    assert!(owner
        .execute(&start, AndroidMediaExecutionMode::Execute)
        .is_err());
    assert_eq!(replay.0.lock().unwrap().terminal.len(), 1);
    assert!(b
        .state
        .lock()
        .unwrap()
        .originals
        .values()
        .all(|o| o.effect.is_none()));
    let mut abort = start.clone();
    abort.action_id.push_str(".abort");
    abort.action_kind = MediaStreamOwnerActionKind::Cleanup;
    let ar = owner
        .execute(&abort, AndroidMediaExecutionMode::Execute)
        .expect("same entered Start compensation uses actual new signed abort request");
    assert!(owner.verify(&abort, &ar));
    let mut stop = start.clone();
    stop.action_id.push_str(".stop");
    stop.operation = MediaStreamPlatformOperation::Stop;
    stop.action_kind = MediaStreamOwnerActionKind::Stop;
    let sr = owner
        .execute(&stop, AndroidMediaExecutionMode::Execute)
        .expect("receiving server still handles separate signed Stop after failure");
    assert!(owner.verify(&stop, &sr));
    assert_eq!(exchanges.load(Ordering::SeqCst), 3);
    assert_eq!(replay.0.lock().unwrap().terminal.len(), 3);
    assert_eq!(b.state.lock().unwrap().originals.len(), 1);
    assert!(
        b.state
            .lock()
            .unwrap()
            .originals
            .values()
            .all(|o| o.effect.is_none()),
        "abort/Stop never replace uncertain forward Original"
    );
}

#[derive(Default)]
struct PrivateContexts(Mutex<Vec<Vec<(String, Vec<u8>)>>>);
impl rusty_quest_media_stream::PrivateFailureSink for PrivateContexts {
    fn retain(
        &self,
        id: &str,
        chunks: &[rusty_quest_media_stream::PrivateFailureChunk<'_>],
        _: u64,
    ) -> Result<rusty_quest_media_stream::PrivateContextDescriptor, ()> {
        let copied = chunks
            .iter()
            .map(|c| (c.name.to_string(), c.bytes.to_vec()))
            .collect::<Vec<_>>();
        let raw = serde_json::to_vec(&copied).unwrap();
        self.0.lock().unwrap().push(copied);
        Ok(rusty_quest_media_stream::PrivateContextDescriptor {
            status: "available".into(),
            context_id: Some(id.into()),
            bytes: raw.len() as u64,
            sha256: Some(crate::packaged_json_sha256(
                std::str::from_utf8(&raw).unwrap(),
            )),
            truncated: false,
        })
    }
}
#[test]
fn receiving_current_request_error_survives_older_first_failure_latch() {
    use rusty_quest_media_stream::{DiagnosticCollector, DiagnosticCorrelation};
    let sink = Arc::new(PrivateContexts::default());
    let collector = DiagnosticCollector::new(Some(sink.clone()));
    let operation = collector.operation("owner_dispatch", DiagnosticCorrelation::default());
    let _scope = super::super::super::operation_diagnostics::scope().enter(operation);
    let failure = Mutex::new(Some("RETAINED_REMOTE_PREPARE"));
    for (boundary, stage, error, frame) in [
        (
            "incoming_retained_prepare",
            OwnerFailureStage::Prepare,
            "retained original owner absent",
            b"RQCP1\0{\"dispatch_id\":\"actual.receiving.prepare\"}".as_slice(),
        ),
        (
            "incoming_retained_dispatch",
            OwnerFailureStage::Dispatch,
            "remote cleanup retained source differs",
            b"{\"dispatch_id\":\"actual.receiving.commit\"}".as_slice(),
        ),
    ] {
        let result: Result<Vec<u8>, String> =
            observe_incoming(Err(error.into()), &failure, stage, boundary, frame);
        assert_eq!(result.unwrap_err(), error);
        assert_eq!(*failure.lock().unwrap(), Some("RETAINED_REMOTE_PREPARE"));
        let snapshot = collector.snapshot(None);
        let current = snapshot
            .operations
            .last()
            .expect("actual current receiving error must be observed even with older first latch");
        let seen = current.secondary.last().unwrap_or(&current.primary);
        assert_eq!(seen.boundary, boundary);
        assert!(seen.correlation.original_id.is_none());
        let raw = sink.0.lock().unwrap();
        let last = raw.last().unwrap();
        assert_eq!(
            last,
            &vec![
                ("error".into(), error.as_bytes().to_vec()),
                ("request_frame".into(), frame.to_vec())
            ]
        );
        let public = serde_json::to_string(&snapshot).unwrap();
        assert!(!public.contains(error));
        assert!(!public.contains("actual.receiving"));
    }
}

#[test]
fn receiving_observer_preserves_success_no_scope_and_failing_sink_results() {
    use rusty_quest_media_stream::{
        DiagnosticCollector, DiagnosticCorrelation, PrivateContextDescriptor, PrivateFailureChunk,
        PrivateFailureSink,
    };
    struct RefusingSink;
    impl PrivateFailureSink for RefusingSink {
        fn retain(
            &self,
            _: &str,
            _: &[PrivateFailureChunk<'_>],
            _: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            Err(())
        }
    }
    let failure = Mutex::new(None);
    assert!(super::super::super::operation_diagnostics::scope()
        .current()
        .is_none());
    let absent: Result<u8, String> = observe_incoming(
        Err("exact unscoped error".into()),
        &failure,
        OwnerFailureStage::Prepare,
        "incoming_retained_prepare",
        b"frame",
    );
    assert_eq!(absent.unwrap_err(), "exact unscoped error");
    let latched = *failure.lock().unwrap();
    let collector = DiagnosticCollector::new(Some(Arc::new(RefusingSink)));
    let op = collector.operation("owner_dispatch", DiagnosticCorrelation::default());
    let _guard = super::super::super::operation_diagnostics::scope().enter(op);
    let success = observe_incoming(
        Ok::<_, String>(vec![1, 2, 3]),
        &failure,
        OwnerFailureStage::Dispatch,
        "incoming_retained_dispatch",
        b"frame",
    );
    assert_eq!(success.unwrap(), vec![1, 2, 3]);
    assert!(collector.snapshot(None).operations.is_empty());
    let error: Result<u8, String> = observe_incoming(
        Err("exact sink-error result".into()),
        &failure,
        OwnerFailureStage::Dispatch,
        "incoming_retained_dispatch",
        b"frame",
    );
    assert_eq!(error.unwrap_err(), "exact sink-error result");
    assert_eq!(*failure.lock().unwrap(), latched);
    let snapshot = collector.snapshot(None);
    assert_eq!(snapshot.operations.len(), 1);
    assert_eq!(
        snapshot.operations[0].primary.private_context.status,
        "unavailable"
    );
    assert!(snapshot.operations[0]
        .primary
        .correlation
        .original_id
        .is_none());
}
