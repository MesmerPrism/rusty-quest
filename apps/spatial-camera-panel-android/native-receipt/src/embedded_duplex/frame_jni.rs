use jni::objects::{JClass, JLongArray};
use jni::sys::{jboolean, jlong, jlongArray};
use jni::JNIEnv;

use super::frame_identity::{ReceiverFrameObservationResult, ReceiverFrameRegistrationResult};
use crate::spatial_video_projection_native_stream::{self as stream, EmbeddedReceiverFrameRequest};

const IDENTITY_WORDS: usize = 14;

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_localCameraQuiescent(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    crate::camera_hwb_probe::local_camera_acquisition_quiescent() as jboolean
}

fn read_identity(env: &JNIEnv<'_>, array: JLongArray<'_>) -> Option<EmbeddedReceiverFrameRequest> {
    if array.is_null() || env.get_array_length(&array).ok()? != IDENTITY_WORDS as i32 {
        return None;
    }
    let mut words = [0_i64; IDENTITY_WORDS];
    env.get_long_array_region(&array, 0, &mut words).ok()?;
    if words.iter().any(|value| *value < 0) || words[..6].contains(&0) {
        return None;
    }
    Some(EmbeddedReceiverFrameRequest {
        receiver_generation: words[0] as u64,
        connection_generation: words[1] as u64,
        route_generation: words[2] as u64,
        decoder_token: words[3] as u64,
        reader_generation: words[4] as u64,
        presentation_time_ns: words[5],
        source_elapsed_ns: words[6],
        source_unix_ns: words[7],
        pair_id: words[8] as u64,
        left_source_frame: words[9] as u64,
        right_source_frame: words[10] as u64,
        left_sensor_timestamp_ns: words[11],
        right_sensor_timestamp_ns: words[12],
        pair_delta_ns: words[13] as u64,
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_registerReceiverFrame(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    identity: JLongArray<'_>,
) -> jboolean {
    read_identity(&env, identity).is_some_and(|request| {
        stream::register_embedded_receiver_frame(request)
            == ReceiverFrameRegistrationResult::Accepted
    }) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_recordReceiverFrameRendered(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    identity: JLongArray<'_>,
) -> jboolean {
    read_identity(&env, identity).is_some_and(|request| {
        stream::record_embedded_receiver_frame_rendered(request)
            == ReceiverFrameObservationResult::Accepted
    }) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_currentReceiverFrame(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    receiver: jlong,
    connection: jlong,
    route: jlong,
    decoder: jlong,
    reader: jlong,
    max_age_ns: jlong,
) -> jlongArray {
    if [receiver, connection, route, decoder, reader, max_age_ns]
        .iter()
        .any(|value| *value <= 0)
        || max_age_ns > 5_000_000_000
    {
        return std::ptr::null_mut();
    }
    let Some(observation) = stream::current_embedded_receiver_frame_observation(
        receiver as u64,
        connection as u64,
        route as u64,
        decoder as u64,
        reader as u64,
        max_age_ns as u64,
    ) else {
        return std::ptr::null_mut();
    };
    let identity = observation.identity;
    let words = [
        receiver,
        connection,
        route,
        decoder,
        reader,
        identity.presentation_time_ns,
        identity.source_elapsed_ns,
        identity.source_unix_ns,
        identity.pair_id as i64,
        identity.left_source_frame as i64,
        identity.right_source_frame as i64,
        identity.left_sensor_timestamp_ns,
        identity.right_sensor_timestamp_ns,
        identity.pair_delta_ns as i64,
        observation.registered_monotonic_ns as i64,
        observation.rendered_monotonic_ns as i64,
        observation.acquired_monotonic_ns as i64,
    ];
    let Ok(array) = env.new_long_array(words.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_long_array_region(&array, 0, &words).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_retireReceiverGeneration(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    receiver: jlong,
) {
    if receiver > 0 {
        stream::retire_embedded_receiver_generation(receiver as u64);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_retireReceiverConnection(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    receiver: jlong,
    connection: jlong,
) {
    if receiver > 0 && connection > 0 {
        stream::retire_embedded_receiver_connection(receiver as u64, connection as u64);
    }
}
