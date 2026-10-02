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

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeReadMask(
    env:JNIEnv<'_>,_:JClass<'_>)->jlongArray {
    let (policy,revision,mask)=runtime::read_mask_policy();
    let mut words=[0i64;12];words[..6].copy_from_slice(&[1,i64::from(runtime::source_banks_enabled() && revision>0 && revision<=i64::MAX as u64),revision as i64,mask[0] as i64,mask[1] as i64,mask[2] as i64]);
    for (out,value) in words[6..].iter_mut().zip(policy){*out=value as i64;}
    let Ok(array)=env.new_long_array(12)else{return std::ptr::null_mut()};
    if env.set_long_array_region(&array,0,&words).is_err(){return std::ptr::null_mut()};array.into_raw()
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeApplyMask(
    env:JNIEnv<'_>,_:JClass<'_>,input:JLongArray<'_>)->jlong {
    if env.get_array_length(&input).ok()!=Some(7){return 0;}
    let mut w=[0i64;7];if env.get_long_array_region(&input,0,&mut w).is_err()
        ||w[0]!=i64::from(crate::stereo_bank_mask_v1::VERSION)||![w[1],w[5],w[6]].iter().all(|v|*v==0||*v==1)
        ||w[2..5].iter().any(|v|!(0..=u32::MAX as i64).contains(v)){return 0;}
    let Ok(mask)=crate::stereo_bank_mask_v1::pack(w[1]==1,f32::from_bits(w[2] as u32),f32::from_bits(w[3] as u32),
        f32::from_bits(w[4] as u32),w[5]==1,w[6]==1)else{return 0;};
    match runtime::update_mask_policy(mask){Ok(r)if r>0&&r<=i64::MAX as u64=>r as i64,_=>0}
}
