use rusty_lsl::{
    admit_runtime_activation, run_short_info_responder, run_timestamped_float32_outlet,
    ParsedStreamInfoObservedDocument, RawSourceTimestamp, RuntimeActivationSelection,
    RuntimeModule, Sample, SampleLimits, ShortInfoQueryWireLimits,
    ShortInfoResponderActivation, ShortInfoResponderLimits, ShortInfoResponseEnvelopeLimits,
    StreamHandshakeActivation, StreamHandshakeIdentity, StreamHandshakeLimits,
    StreamInfoObservedDocumentParseLimit, TimestampedFloat32SampleActivation,
    TimestampedFloat32SampleLimits, TimestampedSample, ACCEPTED_FEATURE_LOCK_FINGERPRINT,
};
use std::ffi::{c_char, c_int, c_void, CString};
use std::net::{Ipv4Addr, TcpListener, UdpSocket};
use std::sync::atomic::AtomicBool;
use std::thread;
use std::time::Duration;

const DISCOVERY_PORT: u16 = 17670;
const UID: &str = "70000000-2222-4333-8444-555555555570";
const VALUE_BITS: u32 = 0x3fa0_0070;
const TIMESTAMP_BITS: u64 = 0x4092_5220_0000_0070;

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn log(priority: c_int, text: String) {
    let tag = CString::new("RLSLP70_RUST").unwrap();
    let text = CString::new(text).unwrap();
    unsafe { __android_log_write(priority, tag.as_ptr(), text.as_ptr()); }
}

fn execute() -> bool {
    let selections = [
        RuntimeActivationSelection::new(RuntimeModule::ShortInfoDiscoveryResponder.id(), RuntimeModule::ShortInfoDiscoveryResponder.effective_marker()),
        RuntimeActivationSelection::new(RuntimeModule::StreamHandshake.id(), RuntimeModule::StreamHandshake.effective_marker()),
        RuntimeActivationSelection::new(RuntimeModule::TimestampedFloat32Sample.id(), RuntimeModule::TimestampedFloat32Sample.effective_marker()),
    ];
    let admission = match admit_runtime_activation(
        ACCEPTED_FEATURE_LOCK_FINGERPRINT,
        "p70-rust-on-quest-lan-outlet",
        &selections,
    ) { Ok(value) => value, Err(_) => return false };
    let responder_activation = ShortInfoResponderActivation::new(
        admission.capability(RuntimeModule::ShortInfoDiscoveryResponder).unwrap(),
    ).unwrap();
    let handshake_activation = StreamHandshakeActivation::new(
        admission.capability(RuntimeModule::StreamHandshake).unwrap(),
    ).unwrap();
    let sample_activation = TimestampedFloat32SampleActivation::new(
        admission.capability(RuntimeModule::TimestampedFloat32Sample).unwrap(),
        handshake_activation,
    ).unwrap();
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
    let responder = thread::spawn(move || {
        let document = ParsedStreamInfoObservedDocument::parse(
            StreamInfoObservedDocumentParseLimit::new(xml.len()).unwrap(),
            &xml,
        ).unwrap();
        run_short_info_responder(
            responder_activation,
            (Ipv4Addr::UNSPECIFIED, DISCOVERY_PORT).into(),
            ShortInfoResponderLimits::new(2048, 1, Duration::from_millis(10), Duration::from_secs(20)).unwrap(),
            ShortInfoQueryWireLimits::new(256, 1024).unwrap(),
            ShortInfoResponseEnvelopeLimits::new(response_limit, response_limit + 32).unwrap(),
            &document,
            &AtomicBool::new(false),
        )
    });
    let identity = StreamHandshakeIdentity::new(
        UID.into(), "quest".into(), "p70-quest-source".into(), "p70".into(),
        StreamHandshakeLimits::new(1024, 64, Duration::from_millis(10), Duration::from_secs(10)).unwrap(),
    ).unwrap();
    let sample = TimestampedSample::new(
        Sample::new(SampleLimits::new(1).unwrap(), 1, vec![f32::from_bits(VALUE_BITS)]).unwrap(),
        RawSourceTimestamp::new(f64::from_bits(TIMESTAMP_BITS)).unwrap(),
        None,
    );
    log(4, format!("READY schema=rusty.lsl.p70.quest_outlet_ready.v1 discovery_port={DISCOVERY_PORT}"));
    let outlet = run_timestamped_float32_outlet(
        sample_activation,
        listener,
        &identity,
        StreamHandshakeLimits::new(1024, 64, Duration::from_millis(10), Duration::from_secs(10)).unwrap(),
        TimestampedFloat32SampleLimits::new(Duration::from_millis(10), Duration::from_secs(10)).unwrap(),
        &sample,
        &AtomicBool::new(false),
    );
    let responder_ok = responder.join().ok().and_then(Result::ok)
        .is_some_and(|run| run.requests() == 1);
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
