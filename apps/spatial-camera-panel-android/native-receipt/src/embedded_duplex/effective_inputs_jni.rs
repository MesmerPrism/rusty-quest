//! Pure data JNI leaf. Native authority construction/admission remains with runtime_host.
use jni::{
    objects::{JClass, JString},
    sys::jstring,
    JNIEnv,
};

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_deriveEffectiveInputs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let result = (|| {
        let text: String = env
            .get_string(&request)
            .map_err(|_| "effective input JNI string unavailable")?
            .into();
        super::effective_inputs::derive_effective_inputs_request_json(&text)
    })();
    match result {
        Ok(text) => match env.new_string(text) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(reason) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", reason);
            std::ptr::null_mut()
        }
    }
}
