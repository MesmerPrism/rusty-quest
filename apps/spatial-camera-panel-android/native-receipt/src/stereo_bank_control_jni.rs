//! Bounded app-owned policy transport. It never creates image readiness or enables a feature.
use jni::{JNIEnv, objects::{JClass, JLongArray}, sys::{jlong, jlongArray}};
use crate::spatial_public_multistack_runtime as runtime;

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeRead(
    env: JNIEnv<'_>, _: JClass<'_>,
) -> jlongArray {
    let (policy, revision) = runtime::read_control_policy();
    let enabled = runtime::source_banks_enabled() && revision > 0 && revision <= i64::MAX as u64;
    let mut words = [0i64; 8];
    words[0] = i64::from(enabled);
    words[1] = if enabled { revision as i64 } else { 0 };
    for (output, value) in words[2..].iter_mut().zip(policy) { *output = i64::from(value); }
    let Ok(array) = env.new_long_array(8) else { return std::ptr::null_mut() };
    if env.set_long_array_region(&array, 0, &words).is_err() { return std::ptr::null_mut() }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeApply(
    env: JNIEnv<'_>, _: JClass<'_>, input: JLongArray<'_>,
) -> jlong {
    if !runtime::source_banks_enabled() || env.get_array_length(&input).ok() != Some(6) { return 0 }
    let mut words = [0i64; 6];
    if env.get_long_array_region(&input, 0, &mut words).is_err()
        || words[..4].iter().any(|&value| !(0..=1).contains(&value))
        || words[4..].iter().any(|&value| !(0..=2).contains(&value)) { return 0 }
    let policy = words.map(|word| word as u32);
    match runtime::update_control_policy(policy) {
        Ok(revision) if revision > 0 && revision <= i64::MAX as u64 => revision as i64,
        _ => 0,
    }
}
