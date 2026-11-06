//! The only showcase command JNI. Shared Polar ingress remains independently typed.
use jni::{
    objects::{JClass, JString},
    sys::jstring,
    EnvUnowned,
};
use serde_json::json;
fn reply(
    mut env: EnvUnowned,
    f: impl FnOnce(&mut jni::Env) -> jni::errors::Result<String>,
) -> jstring {
    match env
        .with_env(|env| {
            let text = f(env)?;
            env.new_string(text).map(|s| s.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ShowcaseNativeBridge_nativeShowcaseStatus(
    env: EnvUnowned,
    _: JClass,
) -> jstring {
    reply(env, |_| Ok(crate::showcase_runtime::status_json()))
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ShowcaseNativeBridge_nativeShowcaseSettings(
    env: EnvUnowned,
    _: JClass,
) -> jstring {
    reply(env, |_| Ok(crate::showcase_runtime::settings_json()))
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ShowcaseNativeBridge_nativeShowcaseReceipt(
    env: EnvUnowned,
    class: JClass,
    id: JString,
) -> jstring {
    reply(env, |env| {
        let id = id.try_to_string(env)?;
        Ok(crate::showcase_runtime::reconcile_receipt(
            &id,
            |acquisition_id| {
                use jni::{
                    jni_sig, jni_str,
                    objects::{JString, JValue},
                };
                let id = env.new_string(acquisition_id).map_err(|e| e.to_string())?;
                let result = env
                    .call_static_method(
                        &class,
                        jni_str!("readPlatformReceipt"),
                        jni_sig!("(Ljava/lang/String;)Ljava/lang/String;"),
                        &[JValue::Object(&id)],
                    )
                    .map_err(|e| e.to_string())?
                    .l()
                    .map_err(|e| e.to_string())?;
                let text = env
                    .cast_local::<JString>(result)
                    .map_err(|e| e.to_string())?
                    .try_to_string(env)
                    .map_err(|e| e.to_string())?;
                serde_json::from_str(&text).map_err(|e| e.to_string())
            },
        ))
    })
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ShowcaseNativeBridge_nativeShowcaseInitialize(
    env: EnvUnowned,
    _: JClass,
    defaults: JString,
    persisted: JString,
    migrations: JString,
) -> jstring {
    reply(env, |env| {
        let result = crate::showcase_runtime::initialize(
            &defaults.try_to_string(env)?,
            &persisted.try_to_string(env)?,
            &migrations.try_to_string(env)?,
        );
        Ok(match result{Ok(())=>crate::showcase_runtime::status_json(),Err(reason)=>json!({"schema":"rusty.quest.visual_showcase.status.v1","product":"visual-showcase","ready":false,"reason":reason}).to_string()})
    })
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ShowcaseNativeBridge_nativeShowcaseCommand(
    env: EnvUnowned,
    class: JClass,
    command: JString,
) -> jstring {
    reply(env, |env| {
        let raw = command.try_to_string(env)?;
        Ok(crate::showcase_runtime::command_with_executor(
            &raw,
            |op, payload| {
                use jni::{
                    jni_sig, jni_str,
                    objects::{JString, JValue},
                };
                let op = env.new_string(op).map_err(|e| e.to_string())?;
                let payload = env
                    .new_string(payload.to_string())
                    .map_err(|e| e.to_string())?;
                let result = env
                    .call_static_method(
                        &class,
                        jni_str!("executePlatform"),
                        jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                        &[JValue::Object(&op), JValue::Object(&payload)],
                    )
                    .map_err(|e| e.to_string())?
                    .l()
                    .map_err(|e| e.to_string())?;
                let text = env
                    .cast_local::<JString>(result)
                    .map_err(|e| e.to_string())?
                    .try_to_string(env)
                    .map_err(|e| e.to_string())?;
                serde_json::from_str(&text).map_err(|e| e.to_string())
            },
        ))
    })
}
