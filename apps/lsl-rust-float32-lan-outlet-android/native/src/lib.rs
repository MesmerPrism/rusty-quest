use rusty_lsl::{
    admit_runtime_activation, run_prebound_short_info_responder, run_timestamped_float32_outlet,
    ParsedShortInfoResponseEnvelope, ParsedStreamInfoObservedDocument, RawSourceTimestamp,
    RuntimeActivationSelection, RuntimeModule, Sample, SampleLimits, ShortInfoQuery,
    ShortInfoQueryWire, ShortInfoQueryWireLimits, ShortInfoResponderActivation,
    ShortInfoResponderLimits, ShortInfoResponderTermination, ShortInfoResponseEnvelopeLimits,
    StreamHandshakeActivation, StreamHandshakeIdentity, StreamHandshakeLimits,
    StreamInfoObservedDocumentParseLimit, TimestampedFloat32SampleActivation,
    TimestampedFloat32SampleLimits, TimestampedSample, ACCEPTED_FEATURE_LOCK_FINGERPRINT,
};
use std::ffi::{c_char, c_int, c_void, CString};
use std::net::{Ipv4Addr, TcpListener, UdpSocket};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use std::thread;
use std::time::Duration;

const DISCOVERY_PORT: u16 = 17670;
const UID: &str = "70000000-2222-4333-8444-555555555570";
const VALUE_BITS: u32 = 0x3fa0_0070;
const TIMESTAMP_BITS: u64 = 0x4092_5220_0000_0070;
const SELF_PROBE_QUERY_ID: u64 = 70_000_010;

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn log(priority: c_int, text: String) {
    let tag = CString::new("RLSLP70_RUST").unwrap();
    let text = CString::new(text).unwrap();
    unsafe {
        __android_log_write(priority, tag.as_ptr(), text.as_ptr());
    }
}

fn prove_responder_ready(
    xml: &str,
    query_limits: ShortInfoQueryWireLimits,
    response_limits: ShortInfoResponseEnvelopeLimits,
) -> bool {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let return_port = socket.local_addr().unwrap().port();
    let query = ShortInfoQuery::new(
        "name='p70-quest-outlet'".into(),
        return_port,
        SELF_PROBE_QUERY_ID,
        query_limits,
    )
    .unwrap();
    let wire = ShortInfoQueryWire::encode(&query, query_limits).unwrap();
    let sent = socket
        .send_to(wire.as_bytes(), (Ipv4Addr::LOCALHOST, DISCOVERY_PORT))
        .unwrap();
    if sent != wire.as_bytes().len() {
        return false;
    }
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    let mut response = vec![0; response_limits.max_envelope_bytes() + 1];
    while std::time::Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        socket
            .set_read_timeout(Some(remaining.min(Duration::from_millis(20))))
            .unwrap();
        match socket.recv_from(&mut response) {
            Ok((length, source)) => {
                if source.ip() != Ipv4Addr::LOCALHOST || source.port() != DISCOVERY_PORT {
                    return false;
                }
                let Ok(text) = std::str::from_utf8(&response[..length]) else {
                    return false;
                };
                let Ok(parsed) = ParsedShortInfoResponseEnvelope::parse(text, response_limits)
                else {
                    return false;
                };
                return parsed.query_id() == SELF_PROBE_QUERY_ID && parsed.body().source() == xml;
            }
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) => {}
            Err(_) => return false,
        }
    }
    false
}

fn log_responder_outcome(responder_run: Option<rusty_lsl::ShortInfoResponderRun>) -> bool {
    let (result, requests, termination) = match responder_run {
        Some(run) => {
            let termination = match run.termination() {
                ShortInfoResponderTermination::Cancelled => "cancelled",
                ShortInfoResponderTermination::Deadline => "deadline",
                ShortInfoResponderTermination::RequestLimit => "request-limit",
            };
            (
                run.requests() == 2
                    && run.termination() == ShortInfoResponderTermination::RequestLimit,
                run.requests(),
                termination,
            )
        }
        None => (false, 0, "error"),
    };
    log(4, format!(
        "RESPONDER schema=rusty.lsl.p70.quest_responder_result.v1 result={} requests={} termination={}",
        if result { "pass" } else { "fail" }, requests, termination
    ));
    result
}

fn execute() -> bool {
    let selections = [
        RuntimeActivationSelection::new(
            RuntimeModule::ShortInfoDiscoveryResponder.id(),
            RuntimeModule::ShortInfoDiscoveryResponder.effective_marker(),
        ),
        RuntimeActivationSelection::new(
            RuntimeModule::StreamHandshake.id(),
            RuntimeModule::StreamHandshake.effective_marker(),
        ),
        RuntimeActivationSelection::new(
            RuntimeModule::TimestampedFloat32Sample.id(),
            RuntimeModule::TimestampedFloat32Sample.effective_marker(),
        ),
    ];
    let admission = match admit_runtime_activation(
        ACCEPTED_FEATURE_LOCK_FINGERPRINT,
        "p70-rust-on-quest-lan-outlet",
        &selections,
    ) {
        Ok(value) => value,
        Err(_) => return false,
    };
    let responder_activation = ShortInfoResponderActivation::new(
        admission
            .capability(RuntimeModule::ShortInfoDiscoveryResponder)
            .unwrap(),
    )
    .unwrap();
    let handshake_activation = StreamHandshakeActivation::new(
        admission
            .capability(RuntimeModule::StreamHandshake)
            .unwrap(),
    )
    .unwrap();
    let sample_activation = TimestampedFloat32SampleActivation::new(
        admission
            .capability(RuntimeModule::TimestampedFloat32Sample)
            .unwrap(),
        handshake_activation,
    )
    .unwrap();
    let route = UdpSocket::bind("0.0.0.0:0").unwrap();
    route.connect("192.0.2.1:9").unwrap();
    let local_ip = match route.local_addr().unwrap().ip() {
        std::net::IpAddr::V4(value) if !value.is_loopback() && !value.is_unspecified() => value,
        _ => return false,
    };
    let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).unwrap();
    let service_port = listener.local_addr().unwrap().port();
    let xml = format!(
        "<?xml version=\"1.0\"?>\n<info>\n<name>p70-quest-outlet</name>\n<type>qualification</type>\n<channel_count>1</channel_count>\n<channel_format>float32</channel_format>\n<source_id>p70-quest-source</source_id>\n<nominal_srate>0</nominal_srate>\n<version>1.100000000000000</version>\n<created_at>1.0</created_at>\n<uid>{UID}</uid>\n<session_id>p70</session_id>\n<hostname>quest</hostname>\n<v4address>{local_ip}</v4address>\n<v4data_port>{service_port}</v4data_port>\n<v4service_port>{service_port}</v4service_port>\n<v6address></v6address>\n<v6data_port>0</v6data_port>\n<v6service_port>0</v6service_port>\n<desc />\n</info>\n"
    );
    let response_limit = xml.len();
    let responder_cancelled = Arc::new(AtomicBool::new(false));
    let responder_cancelled_for_thread = Arc::clone(&responder_cancelled);
    let query_limits = ShortInfoQueryWireLimits::new(256, 1024).unwrap();
    let response_limits =
        ShortInfoResponseEnvelopeLimits::new(response_limit, response_limit + 32).unwrap();
    let responder_socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, DISCOVERY_PORT)).unwrap();
    let responder_xml = xml.clone();
    let responder = thread::spawn(move || {
        let document = ParsedStreamInfoObservedDocument::parse(
            StreamInfoObservedDocumentParseLimit::new(responder_xml.len()).unwrap(),
            &responder_xml,
        )
        .unwrap();
        run_prebound_short_info_responder(
            responder_activation,
            responder_socket,
            ShortInfoResponderLimits::new(
                2048,
                2,
                Duration::from_millis(10),
                Duration::from_secs(20),
            )
            .unwrap(),
            query_limits,
            response_limits,
            &document,
            responder_cancelled_for_thread.as_ref(),
        )
    });
    let self_probe = prove_responder_ready(&xml, query_limits, response_limits);
    if !self_probe {
        responder_cancelled.store(true, Ordering::Release);
        let responder_run = responder.join().ok().and_then(Result::ok);
        log_responder_outcome(responder_run);
        log(4, "NOT_READY schema=rusty.lsl.p70.quest_outlet_ready.v2 self_probe=false stage=responder-self-probe".into());
        return false;
    }
    let identity = StreamHandshakeIdentity::new(
        UID.into(),
        "quest".into(),
        "p70-quest-source".into(),
        "p70".into(),
        StreamHandshakeLimits::new(1024, 64, Duration::from_millis(10), Duration::from_secs(10))
            .unwrap(),
    )
    .unwrap();
    let sample = TimestampedSample::new(
        Sample::new(
            SampleLimits::new(1).unwrap(),
            1,
            vec![f32::from_bits(VALUE_BITS)],
        )
        .unwrap(),
        RawSourceTimestamp::new(f64::from_bits(TIMESTAMP_BITS)).unwrap(),
        None,
    );
    log(
        4,
        "READY schema=rusty.lsl.p70.quest_outlet_ready.v2 self_probe=true stage=responder-ready"
            .into(),
    );
    let outlet = run_timestamped_float32_outlet(
        sample_activation,
        listener,
        &identity,
        StreamHandshakeLimits::new(1024, 64, Duration::from_millis(10), Duration::from_secs(10))
            .unwrap(),
        TimestampedFloat32SampleLimits::new(Duration::from_millis(10), Duration::from_secs(10))
            .unwrap(),
        &sample,
        &AtomicBool::new(false),
    );
    let responder_ok = log_responder_outcome(responder.join().ok().and_then(Result::ok));
    outlet.is_ok() && responder_ok
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_lslrustfloat32lanoutlet_Float32LanOutletActivity_runRustyLslFloat32LanOutlet(
    _environment: *mut c_void,
    _class: *mut c_void,
) -> c_int {
    let passed = execute();
    log(4, format!(
        "EFFECTIVE {{\"schema\":\"rusty.lsl.rust_on_quest_float32_lan_outlet.v1\",\"result\":\"{}\",\"direction\":\"quest-outlet-to-host-inlet\",\"channel_count\":1,\"record_count\":1,\"timestamp_bits\":\"0x{TIMESTAMP_BITS:016x}\",\"value_bits\":\"0x{VALUE_BITS:08x}\",\"socket_cleanup\":true}}",
        if passed { "pass" } else { "fail" }
    ));
    c_int::from(passed)
}
