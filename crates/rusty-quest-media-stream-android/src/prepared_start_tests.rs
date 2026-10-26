//! Source-owned prepared resource owner + actual wire codec controls.
//! SDK callbacks and constructor/ARM data are modeled; no device, Native
//! singleton, full Broker currentness, or simultaneous full Start credit.
use super::*;
use std::sync::{
    atomic::{AtomicU64, AtomicUsize, Ordering},
    mpsc, Arc,
};

fn request(id: &str) -> AndroidPreparedStartRequest {
    let providers:Vec<_>=(0..7).map(|i|json!({"owner_kind":format!("kind{i}"),"owner_id":format!("owner{i}"),"provider_kind":"modeled.sdk","resource_id":format!("resource{i}")})).collect();
    let target = json!({"$schema":"rusty.quest.embedded_duplex.peer_start_resource_target.v1","runtime_config_sha256":"c".repeat(64),"executor_generation":5,
        "product_binding":{"$schema":"rusty.quest.android.media.preparation_target.v1","product_id":"product.q","providers":providers},
        "source_dimensions":{"packed_width":200,"packed_height":100,"per_eye_width":100,"per_eye_height":100,"frame_rate_hz":30,"bitrate_bps":1000000,"left_camera_id":"left","right_camera_id":"right"},
        "own_capture_selected":true,"max_pair_delta_ns":30000000,"outgoing_runtime_spec_id":"product.q","incoming_runtime_spec_id":"product.w"});
    let policy = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_build_policy.v1","selection":"source-bound-v2","policy":{},"policy_sha256":format!("sha256:{}","d".repeat(64)),"enrollment_selection_sha256":format!("sha256:{}","e".repeat(64))});
    AndroidPreparedStartRequest {
        schema_id: "rusty.quest.embedded_duplex.prepared_start_request.v1".into(),
        preparation_id: id.into(),
        original_id: "b".repeat(32),
        app_process_generation: 3,
        app_record_sha256: "a".repeat(64),
        native_executor_generation: 5,
        config_sha256: "c".repeat(64),
        arm_binding: json!({"binding":"established","challenge":"f".repeat(32),"arm_generation":1,"native_process":27,"process_epoch":"1".repeat(32),"runtime_config_sha256":"c".repeat(64)}),
        configuration_binding: json!({"$schema":"rusty.quest.embedded_duplex.runtime_configuration_binding.v1","config_sha256":"c".repeat(64),"app_process_generation":3,"app_record_sha256":"a".repeat(64),"native_executor_generation":5,"local_peer_id":"peer.q","fresh_setup_build_policy":policy}),
        resource_target_sha256: prepared_start_digest(
            "rusty.quest.embedded_duplex.peer_start_resource_target.v1",
            &target,
        )
        .unwrap(),
        resource_target: target,
    }
}
fn format() -> AndroidPreparedEncodedFormat {
    AndroidPreparedEncodedFormat {
        schema_id: "rusty.quest.android.media.prepared_encoded_format.v1".into(),
        mime: "video/avc".into(),
        width: 200,
        height: 100,
        frame_rate_hz: 30,
        csd_0_hex: "00000167".into(),
        csd_1_hex: Some("00000168".into()),
        profile: None,
        level: None,
    }
}
struct Sdk {
    identity: Arc<()>,
    sender: Option<AndroidPreparedSenderReadback>,
    decoder: Option<AndroidPreparedDecoderReadback>,
    calls: Arc<AtomicUsize>,
    fail_after_sender: bool,
    gate: Option<(mpsc::Sender<()>, mpsc::Receiver<()>)>,
    clock: Arc<AtomicU64>,
    observed: u64,
}
impl Sdk {
    fn new() -> Self {
        Self {
            identity: Arc::new(()),
            sender: None,
            decoder: None,
            calls: Arc::new(AtomicUsize::new(0)),
            fail_after_sender: false,
            gate: None,
            clock: Arc::new(AtomicU64::new(1)),
            observed: 0,
        }
    }
}
impl AndroidPreparedResourceExecutor for Sdk {
    fn preparation_identity(&self) -> &Arc<()> {
        &self.identity
    }
    fn prepare_sender(
        &mut self,
        r: &AndroidPreparedStartRequest,
    ) -> Result<AndroidPreparedSenderReadback, String> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if let Some((entered, release)) = self.gate.take() {
            entered
                .send(())
                .map_err(|_| "SDK entered observer closed")?;
            release
                .recv()
                .map_err(|_| "SDK explicit owner release closed")?;
        }
        self.observed = self.clock.load(Ordering::SeqCst);
        let v = AndroidPreparedSenderReadback {
            schema_id: "rusty.quest.android.media.prepared_sender_readback.v1".into(),
            preparation_id: r.preparation_id.clone(),
            original_id: r.original_id.clone(),
            executor_generation: r.native_executor_generation,
            request_sha256: r.digest()?,
            resource_target_sha256: r.resource_target_sha256.clone(),
            provider_handle_id: "actual.modeled.sender".into(),
            provider_state_revision: 1,
            stage: "sender_ready".into(),
            outgoing_format: format(),
            delivery_gate_closed: true,
            peer_delivery_count: 0,
        };
        self.sender = Some(v.clone());
        if self.fail_after_sender {
            return Err("modeled actual effect returned error".into());
        }
        Ok(v)
    }
    fn verify_sender(
        &self,
        _: &AndroidPreparedStartRequest,
        v: &AndroidPreparedSenderReadback,
    ) -> Result<(), String> {
        if self.sender.as_ref() != Some(v) {
            Err("actual sender changed".into())
        } else {
            Ok(())
        }
    }
    fn prepare_decoder(
        &mut self,
        r: &AndroidPreparedStartRequest,
        d: &AndroidPreparedDecoderRequest,
    ) -> Result<AndroidPreparedDecoderReadback, String> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        let v = AndroidPreparedDecoderReadback {
            schema_id: "rusty.quest.android.media.prepared_decoder_readback.v1".into(),
            preparation_id: r.preparation_id.clone(),
            executor_generation: r.native_executor_generation,
            request_sha256: r.digest()?,
            resource_target_sha256: r.resource_target_sha256.clone(),
            provider_handle_id: "actual.modeled.decoder".into(),
            provider_state_revision: 1,
            stage: "decoder_ready".into(),
            peer_sender_receipt_sha256: d.peer_sender_receipt_sha256.clone(),
            delivery_gate_closed: true,
            peer_delivery_count: 0,
        };
        self.decoder = Some(v.clone());
        Ok(v)
    }
    fn verify_decoder(
        &self,
        _: &AndroidPreparedStartRequest,
        _: &AndroidPreparedDecoderRequest,
        v: &AndroidPreparedDecoderReadback,
    ) -> Result<(), String> {
        if self.decoder.as_ref() != Some(v) {
            Err("actual decoder changed".into())
        } else {
            Ok(())
        }
    }
}
fn decoder(r: &AndroidPreparedStartRequest) -> AndroidPreparedDecoderRequest {
    AndroidPreparedDecoderRequest {
        schema_id: "rusty.quest.android.media.prepared_decoder_request.v1".into(),
        preparation_id: r.preparation_id.clone(),
        request_sha256: r.digest().unwrap(),
        peer_preparation_id: "2".repeat(32),
        peer_sender_receipt_sha256: format!("sha256:{}", "3".repeat(64)),
        peer_format: format(),
    }
}

#[test]
fn same_original_sdk_completion_survives_expiry_scale_clock_advance_and_both_workers_join() {
    let (entered_tx, entered_rx) = mpsc::channel();
    let mut releases = Vec::new();
    let mut workers = Vec::new();
    let clock = Arc::new(AtomicU64::new(1));
    for n in ["1", "2"] {
        let mut sdk = Sdk::new();
        sdk.clock = clock.clone();
        let (tx, rx) = mpsc::channel();
        releases.push(tx);
        sdk.gate = Some((entered_tx.clone(), rx));
        let r = request(&n.repeat(32));
        workers.push(std::thread::spawn(move || {
            let mut owner = AndroidPreparedStartOwner::reserve(&sdk, r.clone()).unwrap();
            owner.prepare_sender(&mut sdk).unwrap();
            owner.prepare_decoder(&mut sdk, decoder(&r)).unwrap();
            owner.require_ready(&sdk).unwrap();
            (owner, sdk)
        }));
    }
    drop(entered_tx);
    entered_rx.recv().unwrap();
    entered_rx.recv().unwrap();
    // Explicit authoritative model clock progress, not wall sleep/timeout.
    // Resource preparation has no expiring media admission to consume here.
    clock.store(900001, Ordering::SeqCst);
    for release in releases {
        release.send(()).unwrap();
    }
    for worker in workers {
        let (owner, sdk) = worker.join().unwrap();
        assert_eq!(sdk.observed, 900001);
        assert_eq!(sdk.calls.load(Ordering::SeqCst), 2);
        assert_eq!(owner.projection()["sender_entered"], true);
        assert_eq!(owner.projection()["decoder_entered"], true);
        assert_eq!(owner.sender().unwrap().peer_delivery_count, 0);
    }
}
#[test]
fn entered_sdk_failure_retains_nonclone_owner_and_never_replays() {
    let mut sdk = Sdk::new();
    sdk.fail_after_sender = true;
    let mut owner = AndroidPreparedStartOwner::reserve(&sdk, request(&"1".repeat(32))).unwrap();
    assert!(owner.prepare_sender(&mut sdk).is_err());
    assert!(sdk.sender.is_some());
    assert!(owner.sender().is_none());
    assert_eq!(owner.projection()["sender_entered"], true);
    assert!(owner.prepare_sender(&mut sdk).is_err());
    assert_eq!(sdk.calls.load(Ordering::SeqCst), 1);
    assert!(owner.require_ready(&sdk).is_err());
}
#[test]
fn real_registry_identity_and_exact_readbacks_are_required_after_prepare() {
    let mut sdk = Sdk::new();
    let r = request(&"1".repeat(32));
    let mut owner = AndroidPreparedStartOwner::reserve(&sdk, r.clone()).unwrap();
    owner.prepare_sender(&mut sdk).unwrap();
    let d = decoder(&r);
    owner.prepare_decoder(&mut sdk, d.clone()).unwrap();
    owner.prepare_decoder(&mut sdk, d.clone()).unwrap();
    assert_eq!(sdk.calls.load(Ordering::SeqCst), 2);
    let mut changed = d;
    changed.peer_sender_receipt_sha256 = format!("sha256:{}", "4".repeat(64));
    assert!(owner.prepare_decoder(&mut sdk, changed).is_err());
    assert!(owner.require_ready(&Sdk::new()).is_err());
    sdk.decoder.as_mut().unwrap().provider_state_revision = 2;
    assert!(owner.require_ready(&sdk).is_err());
}
#[test]
fn actual_source_bound_wire_codec_retains_real_time_without_invented_age_expiry() {
    use ed25519_dalek::{Signer, SigningKey};
    let key = SigningKey::from_bytes(&[9; 32]);
    let b = AndroidPreparedStartFrameBody {
        schema: "rusty.quest.embedded_duplex.prepared_start_frame.v1".into(),
        kind: "view".into(),
        request_id: "1".repeat(32),
        sender_preparation_id: "1".repeat(32),
        receiver_preparation_id: None,
        sender_peer_id: "peer.q".into(),
        receiver_peer_id: "peer.w".into(),
        route_configuration_sha256: format!("sha256:{}", "5".repeat(64)),
        issued_at_ms: 1,
        payload: json!({"view":{"actual":"modeled source metadata"}}),
    };
    let raw = encode_prepared_start_frame(
        b.clone(),
        key.sign(&prepared_start_frame_signing_bytes(&b).unwrap())
            .to_bytes(),
    )
    .unwrap();
    let observed = decode_prepared_start_frame(
        &raw,
        &key.verifying_key().to_bytes(),
        "peer.q",
        "peer.w",
        &format!("sha256:{}", "5".repeat(64)),
        900001,
    )
    .unwrap();
    assert_eq!(observed.issued_at_ms, 1);
    assert!(decode_prepared_start_frame(
        &raw,
        &key.verifying_key().to_bytes(),
        "peer.q",
        "peer.w",
        &format!("sha256:{}", "6".repeat(64)),
        900001
    )
    .is_err());
    assert!(decode_prepared_start_frame(
        &raw,
        &SigningKey::from_bytes(&[8; 32]).verifying_key().to_bytes(),
        "peer.q",
        "peer.w",
        &format!("sha256:{}", "5".repeat(64)),
        900001
    )
    .is_err());
    assert!(decode_prepared_start_frame(
        &raw,
        &key.verifying_key().to_bytes(),
        "peer.q",
        "peer.w",
        &format!("sha256:{}", "5".repeat(64)),
        0
    )
    .is_err());
    let mut damage = b;
    damage.payload["extra"] = json!(true);
    assert!(prepared_start_frame_signing_bytes(&damage).is_err());
}
#[test]
fn canonical_binding_is_recursive_sorted_and_rejects_unsigned_or_float_domains() {
    assert_eq!(
        prepared_start_canonical(&json!({"z":{"z":1,"a":2},"a":[3,2,1]})).unwrap(),
        br#"{"a":[3,2,1],"z":{"a":2,"z":1}}"#
    );
    assert!(prepared_start_canonical(&json!(u64::MAX)).is_err());
    assert!(prepared_start_canonical(&json!(0.5)).is_err());
}
