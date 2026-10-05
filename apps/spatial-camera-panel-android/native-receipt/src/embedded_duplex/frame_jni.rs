use jni::objects::{JClass, JLongArray};
use jni::sys::{jboolean, jlong, jlongArray};
use jni::JNIEnv;

use super::frame_identity::{ReceiverFrameObservationResult, ReceiverFrameRegistrationResult};
use super::native_fence_jni::generation_current;
use crate::spatial_video_projection_native_stream::{self as stream, EmbeddedReceiverFrameRequest};

const IDENTITY_WORDS: usize = 14;
const SURFACE_PROOF_VERSION: jlong = 2;

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
    if words.iter().any(|value| *value < 0)
        || words[..6].contains(&0)
        || !generation_current(words[0] as u64)
    {
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
        let generation = request.receiver_generation;
        let accepted = stream::register_embedded_receiver_frame(request)
            == ReceiverFrameRegistrationResult::Accepted;
        accepted && generation_current(generation)
    }) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_recordReceiverFrameRendered(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    identity: JLongArray<'_>,
) -> jboolean {
    read_identity(&env, identity).is_some_and(|request| {
        let generation = request.receiver_generation;
        let accepted = stream::record_embedded_receiver_frame_rendered(request)
            == ReceiverFrameObservationResult::Accepted;
        accepted && generation_current(generation)
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
        || !generation_current(receiver as u64)
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
    if !generation_current(receiver as u64) {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_currentReceiverFrameTimed(
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
        || !generation_current(receiver as u64)
    {
        return std::ptr::null_mut();
    }
    let Some(timed) = stream::current_embedded_receiver_frame_timed_observation(
        receiver as u64,
        connection as u64,
        route as u64,
        decoder as u64,
        reader as u64,
        max_age_ns as u64,
    ) else {
        return std::ptr::null_mut();
    };
    let observation = timed.observation;
    let observed_at = timed.observed_at_monotonic_ns;
    let identity = observation.identity;
    let oldest = observation
        .registered_monotonic_ns
        .min(observation.rendered_monotonic_ns)
        .min(observation.acquired_monotonic_ns);
    if oldest == 0
        || oldest > observed_at
        || observed_at > i64::MAX as u64
        || observation.registered_monotonic_ns > observed_at
        || observation.rendered_monotonic_ns > observed_at
        || observation.acquired_monotonic_ns > observed_at
        || observation.registered_monotonic_ns > observation.rendered_monotonic_ns
        || observation.registered_monotonic_ns > observation.acquired_monotonic_ns
        || observed_at - oldest > i64::MAX as u64
    {
        return std::ptr::null_mut();
    }
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
        observed_at as i64,
        timed.witness_age_ns as i64,
    ];
    let Ok(array) = env.new_long_array(words.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_long_array_region(&array, 0, &words).is_err() {
        return std::ptr::null_mut();
    }
    if !generation_current(receiver as u64) {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

fn valid_timed_request(
    receiver: jlong,
    connection: jlong,
    route: jlong,
    decoder: jlong,
    reader: jlong,
    max_age_ns: jlong,
) -> bool {
    [receiver, connection, route, decoder, reader, max_age_ns]
        .iter()
        .all(|value| *value > 0)
        && max_age_ns <= 5_000_000_000
        && generation_current(receiver as u64)
}

fn surface_identity_words(
    identity: super::frame_identity::ReceiverFrameIdentity,
) -> Option<[jlong; 14]> {
    Some([
        jlong::try_from(identity.receiver_generation).ok()?,
        jlong::try_from(identity.connection_generation).ok()?,
        jlong::try_from(identity.route_generation).ok()?,
        jlong::try_from(identity.decoder_token).ok()?,
        jlong::try_from(identity.reader_generation).ok()?,
        identity.presentation_time_ns,
        identity.source_elapsed_ns,
        identity.source_unix_ns,
        jlong::try_from(identity.pair_id).ok()?,
        jlong::try_from(identity.left_source_frame).ok()?,
        jlong::try_from(identity.right_source_frame).ok()?,
        identity.left_sensor_timestamp_ns,
        identity.right_sensor_timestamp_ns,
        jlong::try_from(identity.pair_delta_ns).ok()?,
    ])
}

fn put_timed_words(mut env: JNIEnv<'_>, words: &[jlong], receiver: jlong) -> jlongArray {
    if !generation_current(receiver as u64) {
        return std::ptr::null_mut();
    }
    let Ok(array) = env.new_long_array(words.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_long_array_region(&array, 0, words).is_err() || !generation_current(receiver as u64)
    {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_currentReceiverAcquiredFrameTimed(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    receiver: jlong,
    connection: jlong,
    route: jlong,
    decoder: jlong,
    reader: jlong,
    max_age_ns: jlong,
) -> jlongArray {
    if !valid_timed_request(receiver, connection, route, decoder, reader, max_age_ns) {
        return std::ptr::null_mut();
    }
    let Some(timed) = stream::current_embedded_receiver_acquired_timed(
        receiver as u64,
        connection as u64,
        route as u64,
        decoder as u64,
        reader as u64,
        max_age_ns as u64,
    ) else {
        return std::ptr::null_mut();
    };
    let Ok(registered) = jlong::try_from(timed.registered_monotonic_ns) else {
        return std::ptr::null_mut();
    };
    let Ok(acquired) = jlong::try_from(timed.acquired_monotonic_ns) else {
        return std::ptr::null_mut();
    };
    let Ok(observed) = jlong::try_from(timed.observed_at_monotonic_ns) else {
        return std::ptr::null_mut();
    };
    let Ok(age) = jlong::try_from(timed.witness_age_ns) else {
        return std::ptr::null_mut();
    };
    let Some(identity) = surface_identity_words(timed.identity) else {
        return std::ptr::null_mut();
    };
    let mut words = [0_i64; 19];
    words[0] = SURFACE_PROOF_VERSION;
    words[1..15].copy_from_slice(&identity);
    words[15..].copy_from_slice(&[registered, acquired, observed, age]);
    put_timed_words(env, &words, receiver)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_currentReceiverEffectiveFrameTimed(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    receiver: jlong,
    connection: jlong,
    route: jlong,
    decoder: jlong,
    reader: jlong,
    max_age_ns: jlong,
) -> jlongArray {
    if !valid_timed_request(receiver, connection, route, decoder, reader, max_age_ns) {
        return std::ptr::null_mut();
    }
    let Some(timed) = stream::current_embedded_receiver_effective_timed(
        receiver as u64,
        connection as u64,
        route as u64,
        decoder as u64,
        reader as u64,
        max_age_ns as u64,
    ) else {
        return std::ptr::null_mut();
    };
    let Ok(registered) = jlong::try_from(timed.acquired.registered_monotonic_ns) else {
        return std::ptr::null_mut();
    };
    let Ok(acquired) = jlong::try_from(timed.acquired.acquired_monotonic_ns) else {
        return std::ptr::null_mut();
    };
    let Ok(gpu) = jlong::try_from(timed.gpu_retired_monotonic_ns) else {
        return std::ptr::null_mut();
    };
    let Ok(observed) = jlong::try_from(timed.acquired.observed_at_monotonic_ns) else {
        return std::ptr::null_mut();
    };
    let Ok(age) = jlong::try_from(timed.witness_age_ns) else {
        return std::ptr::null_mut();
    };
    let Ok(import) = jlong::try_from(timed.import_sequence) else {
        return std::ptr::null_mut();
    };
    let Some(identity) = surface_identity_words(timed.acquired.identity) else {
        return std::ptr::null_mut();
    };
    let mut words = [0_i64; 21];
    words[0] = SURFACE_PROOF_VERSION;
    words[1..15].copy_from_slice(&identity);
    words[15..].copy_from_slice(&[registered, acquired, gpu, observed, age, import]);
    put_timed_words(env, &words, receiver)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_retireReceiverGeneration(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    receiver: jlong,
) {
    if receiver > 0 && generation_current(receiver as u64) {
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
    if receiver > 0 && connection > 0 && generation_current(receiver as u64) {
        stream::retire_embedded_receiver_connection(receiver as u64, connection as u64);
    }
}
