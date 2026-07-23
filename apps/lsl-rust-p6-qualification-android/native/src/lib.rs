use rusty_lsl::{
    ACCEPTED_FEATURE_LOCK_FINGERPRINT, ChunkLimits, RawSourceTimestamp, RuntimeActivationSelection,
    RuntimeModule, Sample, SampleLimits, StreamHandshakeActivation, StreamHandshakeIdentity,
    StreamHandshakeLimits, TimestampedChunk, TimestampedFloat32SampleActivation,
    TimestampedFloat32SampleLimits, TimestampedFloat32TwoRecordChunkLimits, TimestampedSample,
    admit_runtime_activation, run_timestamped_float32_inlet, run_timestamped_float32_outlet,
    run_timestamped_float32_two_record_chunk_inlet,
    run_timestamped_float32_two_record_chunk_outlet,
};
use std::ffi::{CString, c_char, c_int, c_void};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, TcpListener, TcpStream};
use std::sync::atomic::AtomicBool;
use std::thread;
use std::time::{Duration, Instant};

const TAG: &str = "RLSLP6_RUST";
const UID: &str = "66666666-2222-4333-8444-555555555566";

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn log(priority: c_int, text: &str) {
    let tag = CString::new(TAG).unwrap();
    let text = CString::new(text).unwrap();
    unsafe {
        __android_log_write(priority, tag.as_ptr(), text.as_ptr());
    }
}

fn fail(stage: &str) -> bool {
    log(6, &format!("DIAGNOSTIC stage={stage}"));
    false
}

fn activations() -> Option<(TimestampedFloat32SampleActivation, StreamHandshakeLimits)> {
    let selections = [
        RuntimeActivationSelection::new(
            RuntimeModule::StreamHandshake.id(),
            RuntimeModule::StreamHandshake.effective_marker(),
        ),
        RuntimeActivationSelection::new(
            RuntimeModule::TimestampedFloat32Sample.id(),
            RuntimeModule::TimestampedFloat32Sample.effective_marker(),
        ),
    ];
    let admitted = admit_runtime_activation(
        ACCEPTED_FEATURE_LOCK_FINGERPRINT,
        "rlsl-p6-single-quest",
        &selections,
    )
    .ok()?;
    let handshake =
        StreamHandshakeActivation::new(admitted.capability(RuntimeModule::StreamHandshake)?)
            .ok()?;
    let activation = TimestampedFloat32SampleActivation::new(
        admitted.capability(RuntimeModule::TimestampedFloat32Sample)?,
        handshake,
    )
    .ok()?;
    Some((
        activation,
        StreamHandshakeLimits::new(1024, 64, Duration::from_millis(5), Duration::from_secs(2))
            .ok()?,
    ))
}

fn identity(limits: StreamHandshakeLimits) -> StreamHandshakeIdentity {
    StreamHandshakeIdentity::new(
        UID.into(),
        "quest-p6-loopback".into(),
        "rlsl-p6".into(),
        "finite".into(),
        limits,
    )
    .unwrap()
}

fn execute_runtime() -> bool {
    let started = Instant::now();
    let (activation, handshake_limits) = match activations() {
        Some(value) => value,
        None => return fail("activation"),
    };
    let sample_limits =
        TimestampedFloat32SampleLimits::new(Duration::from_millis(5), Duration::from_secs(2))
            .unwrap();
    let sample = TimestampedSample::new(
        Sample::new(
            SampleLimits::new(1).unwrap(),
            1,
            vec![f32::from_bits(0x7fc0_1234)],
        )
        .unwrap(),
        RawSourceTimestamp::new(-0.0).unwrap(),
        None,
    );
    let listener = match TcpListener::bind((Ipv4Addr::LOCALHOST, 0)) {
        Ok(value) => value,
        Err(_) => return fail("sample-bind"),
    };
    let selected = listener.local_addr().unwrap();
    if !selected.ip().is_loopback() {
        return fail("selection-non-loopback");
    }

    // A closed, run-owned ephemeral address is the bounded rejected candidate.
    let recovery_probe = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let rejected = recovery_probe.local_addr().unwrap();
    drop(recovery_probe);
    if TcpStream::connect_timeout(&rejected, Duration::from_millis(50)).is_ok() {
        return fail("recovery-candidate-unexpectedly-live");
    }

    let outlet_identity = identity(handshake_limits);
    let worker = thread::spawn(move || {
        run_timestamped_float32_outlet(
            activation,
            listener,
            &outlet_identity,
            handshake_limits,
            sample_limits,
            &sample,
            &AtomicBool::new(false),
        )
    });
    let received = match run_timestamped_float32_inlet(
        activation,
        selected,
        &identity(handshake_limits),
        handshake_limits,
        sample_limits,
        &AtomicBool::new(false),
    ) {
        Ok(value) => value,
        Err(_) => return fail("sample-inlet-after-recovery"),
    };
    if worker.join().ok().and_then(Result::ok) != Some(selected) {
        return fail("sample-outlet");
    }
    if received.raw_source_timestamp().value().to_bits() != (-0.0f64).to_bits()
        || received.sample().values()[0].to_bits() != 0x7fc0_1234
    {
        return fail("sample-exact-bits");
    }
    if TcpListener::bind(selected).is_err() {
        return fail("sample-port-reuse");
    }

    let chunk_limits = TimestampedFloat32TwoRecordChunkLimits::new(
        Duration::from_millis(5),
        Duration::from_secs(2),
    )
    .unwrap();
    let chunk = TimestampedChunk::new(
        ChunkLimits::new(2, 1).unwrap(),
        vec![
            TimestampedSample::new(
                Sample::new(
                    SampleLimits::new(1).unwrap(),
                    1,
                    vec![f32::from_bits(0x3f80_0000)],
                )
                .unwrap(),
                RawSourceTimestamp::new(1.25).unwrap(),
                None,
            ),
            TimestampedSample::new(
                Sample::new(
                    SampleLimits::new(1).unwrap(),
                    1,
                    vec![f32::from_bits(0xc020_0000)],
                )
                .unwrap(),
                RawSourceTimestamp::new(2.5).unwrap(),
                None,
            ),
        ],
    )
    .unwrap();
    let chunk_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let chunk_address = chunk_listener.local_addr().unwrap();
    let chunk_worker = thread::spawn(move || {
        run_timestamped_float32_two_record_chunk_outlet(
            activation,
            chunk_listener,
            &identity(handshake_limits),
            handshake_limits,
            chunk_limits,
            &chunk,
            &AtomicBool::new(false),
        )
    });
    let received_chunk = match run_timestamped_float32_two_record_chunk_inlet(
        activation,
        chunk_address,
        &identity(handshake_limits),
        handshake_limits,
        chunk_limits,
        &AtomicBool::new(false),
    ) {
        Ok(value) => value,
        Err(_) => return fail("chunk-inlet"),
    };
    if chunk_worker.join().ok().and_then(Result::ok) != Some(chunk_address) {
        return fail("chunk-outlet");
    }
    let rows = received_chunk.samples();
    if rows.len() != 2
        || rows[0].raw_source_timestamp().value().to_bits() != 1.25f64.to_bits()
        || rows[0].sample().values()[0].to_bits() != 0x3f80_0000
        || rows[1].raw_source_timestamp().value().to_bits() != 2.5f64.to_bits()
        || rows[1].sample().values()[0].to_bits() != 0xc020_0000
    {
        return fail("chunk-exact-bits");
    }
    if TcpListener::bind(chunk_address).is_err() {
        return fail("chunk-port-reuse");
    }
    let elapsed = started.elapsed();
    if elapsed.is_zero() || elapsed > Duration::from_secs(8) {
        return fail("monotonic-clock-bound");
    }
    log(
        4,
        &format!(
            "EFFECTIVE {{\"schema\":\"rusty.lsl.p6_single_quest_qualification.v1\",\"result\":\"pass\",\"runtime_owner\":\"rusty-lsl-rust\",\"transport\":\"ipv4-loopback\",\"discovery\":{{\"kind\":\"run-owned-candidate-observation\",\"candidate_count\":2,\"selected_identity\":\"{UID}\"}},\"sample\":{{\"timestamp_bits\":\"0x8000000000000000\",\"value_bits\":\"0x7fc01234\"}},\"chunk\":{{\"record_count\":2,\"timestamp_bits\":[\"0x3ff4000000000000\",\"0x4004000000000000\"],\"value_bits\":[\"0x3f800000\",\"0xc0200000\"]}},\"clock\":{{\"kind\":\"monotonic-elapsed-bound\",\"bounded\":true}},\"recovery\":{{\"kind\":\"rejected-closed-loopback-candidate-then-selected-route\",\"bounded\":true}},\"terminal_cleanup\":{{\"sample_port_reuse\":true,\"chunk_port_reuse\":true}}}}"
        ),
    );
    true
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_lslrustp6qualification_P6QualificationActivity_runQualification(
    _environment: *mut c_void,
    _class: *mut c_void,
) -> c_int {
    c_int::from(execute_runtime())
}

#[allow(dead_code)]
fn _ipv4_type_guard(value: SocketAddrV4) -> SocketAddr {
    SocketAddr::V4(value)
}
