use rusty_lsl::{
    admit_runtime_activation, run_timestamped_float32_two_record_chunk_inlet,
    run_timestamped_float32_two_record_chunk_outlet, ChunkLimits, RawSourceTimestamp,
    RuntimeActivationSelection, RuntimeModule, Sample, SampleLimits, StreamHandshakeActivation,
    StreamHandshakeIdentity, StreamHandshakeLimits, TimestampedFloat32SampleActivation,
    TimestampedFloat32TwoRecordChunkLimits, TimestampedChunk, TimestampedSample,
    ACCEPTED_FEATURE_LOCK_FINGERPRINT,
};
use std::ffi::{c_char, c_int, c_void, CString};
use std::net::TcpListener;
use std::sync::atomic::AtomicBool;
use std::thread;
use std::time::Duration;

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn execute_runtime() -> bool {
    let selections = [
        RuntimeActivationSelection::new(RuntimeModule::StreamHandshake.id(), RuntimeModule::StreamHandshake.effective_marker()),
        RuntimeActivationSelection::new(RuntimeModule::TimestampedFloat32Sample.id(), RuntimeModule::TimestampedFloat32Sample.effective_marker()),
    ];
    let admitted = match admit_runtime_activation(ACCEPTED_FEATURE_LOCK_FINGERPRINT, "lslc-005s-rust-on-quest", &selections) { Ok(value) => value, Err(_) => return fail("activation-admission") };
    let handshake = match StreamHandshakeActivation::new(admitted.capability(RuntimeModule::StreamHandshake).unwrap()) { Ok(value) => value, Err(_) => return fail("handshake-activation") };
    let activation = match TimestampedFloat32SampleActivation::new(admitted.capability(RuntimeModule::TimestampedFloat32Sample).unwrap(), handshake) { Ok(value) => value, Err(_) => return fail("sample-activation") };
    let limits = match StreamHandshakeLimits::new(1024, 64, Duration::from_millis(5), Duration::from_secs(2)) { Ok(value) => value, Err(_) => return fail("handshake-limits") };
    let sample_limits = match TimestampedFloat32TwoRecordChunkLimits::new(Duration::from_millis(5), Duration::from_secs(2)) { Ok(value) => value, Err(_) => return fail("sample-limits") };
    let identity = match StreamHandshakeIdentity::new("11111111-2222-4333-8444-55555555555a".into(), "quest-loopback".into(), "lslc-005s".into(), "finite".into(), limits) { Ok(value) => value, Err(_) => return fail("identity") };
    let chunk = TimestampedChunk::new(
        ChunkLimits::new(2, 1).unwrap(),
        vec![
            TimestampedSample::new(Sample::new(SampleLimits::new(1).unwrap(), 1, vec![f32::from_bits(0x7fc0_1234)]).unwrap(), RawSourceTimestamp::new(-0.0).unwrap(), None),
            TimestampedSample::new(Sample::new(SampleLimits::new(1).unwrap(), 1, vec![f32::from_bits(0xc020_0000)]).unwrap(), RawSourceTimestamp::new(2346.875).unwrap(), None),
        ],
    ).unwrap();
    let listener = match TcpListener::bind("127.0.0.1:0") { Ok(value) => value, Err(_) => return fail("listener-bind") };
    let address = listener.local_addr().unwrap();
    let cancelled = AtomicBool::new(false);
    let worker = thread::spawn(move || run_timestamped_float32_two_record_chunk_outlet(activation, listener, &identity, limits, sample_limits, &chunk, &cancelled));
    let received = match run_timestamped_float32_two_record_chunk_inlet(activation, address, &StreamHandshakeIdentity::new("11111111-2222-4333-8444-55555555555a".into(), "quest-loopback".into(), "lslc-005s".into(), "finite".into(), limits).unwrap(), limits, sample_limits, &AtomicBool::new(false)) { Ok(value) => value, Err(_) => return fail("inlet") };
    let outlet_ok = worker.join().ok().and_then(Result::ok) == Some(address);
    let bits_ok = received.samples().len() == 2
        && received.samples()[0].raw_source_timestamp().value().to_bits() == (-0.0f64).to_bits()
        && received.samples()[0].sample().values()[0].to_bits() == 0x7fc0_1234
        && received.samples()[1].raw_source_timestamp().value().to_bits() == 2346.875f64.to_bits()
        && received.samples()[1].sample().values()[0].to_bits() == 0xc020_0000;
    if !outlet_ok { return fail("outlet"); }
    if !bits_ok { return fail("exact-bits"); }
    if TcpListener::bind(address).is_err() { return fail("port-reuse"); }
    true
}

fn fail(stage: &str) -> bool {
    let tag = CString::new("RLSL005S_RUST").unwrap();
    let text = CString::new(format!("DIAGNOSTIC stage={stage}")).unwrap();
    unsafe { __android_log_write(6, tag.as_ptr(), text.as_ptr()); }
    false
}

fn log_effective(passed: bool) {
    let tag = CString::new("RLSL005S_RUST").unwrap();
    let text = CString::new(format!("EFFECTIVE {{\"schema\":\"rusty.lsl.rust_on_quest_float32_two_record_chunk.v1\",\"result\":\"{}\",\"runtime_owner\":\"rusty-lsl-rust\",\"activation\":\"exact-lock\",\"record_count\":2,\"timestamp_bits\":[\"0x8000000000000000\",\"0x40a255c000000000\"],\"value_bits\":[\"0x7fc01234\",\"0xc0200000\"],\"port_reuse\":true}}", if passed { "pass" } else { "fail" })).unwrap();
    unsafe { __android_log_write(4, tag.as_ptr(), text.as_ptr()); }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_lslrustfloat32tworecordchunk_Float32TwoRecordChunkActivity_runRustyLslFloat32TwoRecordChunk(_environment: *mut c_void, _class: *mut c_void) -> c_int {
    let passed = execute_runtime();
    log_effective(passed);
    c_int::from(passed)
}
