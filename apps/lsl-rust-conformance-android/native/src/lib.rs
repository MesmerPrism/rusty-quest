use rusty_lsl::{
    BoundDescriptorSample, ChannelFormat, DescriptorSampleInput, DescriptorSampleLimits,
    NominalSampleRate, Sample, SampleLimits, StreamDescriptor, StreamDescriptorLimits,
};
use std::ffi::{CString, c_char, c_int, c_void};

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn execute_contract() -> bool {
    let descriptor_limits = StreamDescriptorLimits::new(32, 32, 32, 1).ok();
    let descriptor = descriptor_limits.and_then(|limits| {
        StreamDescriptor::new(
            limits,
            "rust-on-quest".to_owned(),
            Some("conformance".to_owned()),
            Some("run-owned-public-test".to_owned()),
            1,
            NominalSampleRate::irregular(),
            ChannelFormat::Float32,
        )
        .ok()
    });
    let sample = SampleLimits::new(1)
        .ok()
        .and_then(|limits| Sample::new(limits, 1, vec![42.25_f32]).ok());
    let bound = descriptor.zip(sample).and_then(|(descriptor, sample)| {
        BoundDescriptorSample::new(
            DescriptorSampleLimits::new(1).ok()?,
            &descriptor,
            DescriptorSampleInput::Float32(sample),
        )
        .ok()
    });
    bound.is_some_and(|accepted| {
        accepted.channel_count() == 1 && accepted.channel_format() == ChannelFormat::Float32
    })
}

fn log_effective(passed: bool) {
    let tag = CString::new("RLSL005H_RUST").unwrap();
    let text = CString::new(format!(
        "EFFECTIVE {{\"schema\":\"rusty.lsl.rust_on_quest_core_contract.v1\",\"result\":\"{}\",\"runtime_owner\":\"rusty-lsl-rust\",\"descriptor_channels\":1,\"format\":\"float32\",\"sample_bits\":\"0x42290000\"}}",
        if passed { "pass" } else { "fail" }
    )).unwrap();
    unsafe {
        __android_log_write(4, tag.as_ptr(), text.as_ptr());
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_lslrustconformance_RustConformanceActivity_runRustyLslContract(
    _environment: *mut c_void,
    _class: *mut c_void,
) -> c_int {
    let passed = execute_contract();
    log_effective(passed);
    c_int::from(passed)
}
