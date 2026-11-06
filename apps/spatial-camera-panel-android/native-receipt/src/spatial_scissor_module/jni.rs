//! Named JNI adapters for the fixed app-owned scissor facade. No provider owner.
use super::{adapter, hex};
use ::jni::{
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
    JNIEnv,
};

fn text(env: &mut JNIEnv<'_>, value: &JString<'_>, limit: i32) -> Result<String, &'static str> {
    if value.is_null() {
        return Err("INPUT");
    }
    let raw = env.get_raw();
    let length = unsafe { (**raw).GetStringLength.ok_or("JNI_STRING")?(raw, value.as_raw()) };
    if length < 0 || length > limit || env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
        return Err("INPUT");
    }
    env.get_string(value).map(|v| v.into()).map_err(|_| {
        let _ = env.exception_clear();
        "JNI_STRING"
    })
}
fn reply(env: &mut JNIEnv<'_>, profile: &str, result: Result<(), &'static str>) -> jstring {
    let (outcome, reason) = match result {
        Ok(()) => ("accepted", ""),
        Err(reason) => ("rejected", reason),
    };
    let value = adapter().snapshot().json(profile, outcome, reason);
    env.new_string(value)
        .map_or(std::ptr::null_mut(), |v| v.into_raw())
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialScissorModule_nativeBegin(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    adapter().begin().map_or(0, |id| id as jlong)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialScissorModule_nativeCancel(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
) -> jboolean {
    (id > 0 && adapter().cancel(id as u64)) as jboolean
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialScissorModule_nativeEnd(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
) {
    if id > 0 {
        adapter().end(id as u64);
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialScissorModule_nativeActivate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
    path: JString<'_>,
    sha: JString<'_>,
    generation: jlong,
    policy: jint,
    profile: JString<'_>,
) -> jstring {
    let profile = text(&mut env, &profile, 64).unwrap_or_default();
    let result = (|| {
        if id <= 0 || generation <= 0 || !matches!(policy, 0 | 1) || !hex(&profile) {
            return Err("INPUT");
        }
        let path = text(&mut env, &path, 4096)?;
        let sha = text(&mut env, &sha, 64)?;
        adapter().activate_request(
            id as u64,
            std::path::Path::new(&path),
            &sha,
            generation as u64,
            policy == 1,
            &profile,
        )
    })();
    reply(&mut env, if hex(&profile) { &profile } else { "" }, result)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialScissorModule_nativeSelect(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
    generation: jlong,
    profile: JString<'_>,
) -> jstring {
    let profile = text(&mut env, &profile, 64).unwrap_or_default();
    let result = if id > 0 && generation > 0 && hex(&profile) {
        adapter().select_request(id as u64, generation as u64, &profile)
    } else {
        Err("INPUT")
    };
    reply(&mut env, if hex(&profile) { &profile } else { "" }, result)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialScissorModule_nativeStatic(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    profile: JString<'_>,
) -> jstring {
    let profile = text(&mut env, &profile, 64).unwrap_or_default();
    let result = adapter().disable(&profile);
    reply(&mut env, if hex(&profile) { &profile } else { "" }, result)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialScissorModule_nativeStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    profile: JString<'_>,
) -> jstring {
    let profile = text(&mut env, &profile, 64).unwrap_or_default();
    let result = if hex(&profile) { Ok(()) } else { Err("INPUT") };
    reply(&mut env, if hex(&profile) { &profile } else { "" }, result)
}
