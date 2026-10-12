use jni::{objects::JClass, JNIEnv};

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_StereoRendererTerminalStatus_nativeRead(
    mut env: JNIEnv<'_>, _: JClass<'_>, expected: jni::sys::jlong,
) -> jni::sys::jstring {
    let current = crate::own_packed_pool_jni::process_generation().ok();
    let snapshot = if expected > 0 && current == Some(expected as u64) {
        crate::stereo_renderer_terminal::read_snapshot(expected as u64)
    } else { None };
    let value = serde_json::json!({
        "schema":"rusty.quest.stereo.renderer_terminal_native.v1",
        "availability":if snapshot.is_some(){"available"}else{"unavailable"},
        "graph_sequence":snapshot.map_or(0,|v|v.token.sequence),
        "native_process_generation":snapshot.map_or(0,|v|v.token.process),
        "route_generation":snapshot.map_or(0,|v|v.token.route),
        "surface_generation":snapshot.map_or(0,|v|v.surface),
        "state":snapshot.map_or("unavailable",|v|v.state),
        "failure_phase":snapshot.map_or("none",|v|v.phase),
        "failure_category":snapshot.map_or("none",|v|v.category),
        "carrier_cleanup":snapshot.map_or("unknown",|v|v.cleanup),
        "captured_at_ns":snapshot.map_or(0,|v|v.observed_at_ns),
        "scope":"source_graph_observation_not_authority"
    }).to_string();
    env.new_string(value).map(|v|v.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Fetch the current native actor independently of qualification arm or GPU state.
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_StereoRendererTerminalStatus_nativeCurrentProcess(
    _: JNIEnv<'_>, _: JClass<'_>,
) -> jni::sys::jlong {
    crate::own_packed_pool_jni::process_generation().ok()
        .filter(|value| *value <= i64::MAX as u64).unwrap_or(0) as jni::sys::jlong
}
