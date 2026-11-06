use jni::{EnvUnowned, objects::{JClass,JString}, sys::{jstring,jlong,jdouble,jboolean}};
use serde_json::{json,Value};
use crate::{proof,controller,experiment_session_runtime as runtime};
fn response(mut env:EnvUnowned,value:String)->jstring {
    match env.with_env(|env|->jni::errors::Result<jstring>{env.new_string(value).map(|v|v.into_raw())}).into_outcome(){
        jni::Outcome::Ok(v)=>v,_=>std::ptr::null_mut()}
}
fn result(value:Result<String,String>)->String {value.unwrap_or_else(|reason|json!({"status":"rejected","reason":reason}).to_string())}
fn positive(value:i64)->Result<u64,String>{crate::scope::positive_counter(value)}
extern "C" {fn rq_session_install_loaded_services()->i32;}
fn bind(epoch:i64,instance:i64,session:i64)->Result<String,String>{
    let installed=unsafe{rq_session_install_loaded_services()};
    if installed!=0{return Err(format!("sdk-loaded-services-unavailable:{installed}"));}
    let owner=proof::bind(positive(epoch)?,instance as u64,session as u64)?;
    Ok(json!({"status":"accepted","sdk_epoch":owner.epoch,"runtime_epoch":owner.runtime_epoch,"submission_admitted":false}).to_string())
}
fn revoke(epoch:i64,terminal:bool)->Result<String,String>{
    let owner=proof::capture(positive(epoch)?)?;proof::revoke(&owner)?;
    if terminal {let retired=owner.clone();std::thread::Builder::new().name("sdk-session-retire".into()).spawn(move||{
        let _=retired.runtime.shutdown_captured_immersive_owner(retired.epoch);
    }).map_err(|_|"sdk-retirement-worker-unavailable")?;}
    Ok(json!({"status":"accepted","sdk_epoch":owner.epoch,"retirement_queued":terminal}).to_string())
}
fn poll(epoch:i64)->Result<String,String>{
    let owner=proof::capture(positive(epoch)?)?;
    let evidence=match proof::poll(&owner){Ok(text)=>serde_json::from_str::<Value>(&text).unwrap_or(Value::Null),Err(reason)=>json!({"submission_admitted":false,"reason":reason})};
    controller::record_submitted(&owner);
    let _effect=proof::cleanup_effect(&owner)?;
    let admitted=evidence["submission_admitted"].as_bool()==Some(true);
    let control=owner.runtime.captured_control_receipt().and_then(|(generation,revision,event)|{
        let cleanup=matches!(event,"experiment-paused"|"immersive-paused"|"restart-requested"|"exit-requested"|"completion-reached"|"audio-error"|"audio-ended");
        (admitted||cleanup).then(||json!({"generation":generation,"revision":revision,"event":event}))});
    let phase=if admitted {owner.runtime.current_breath_guidance_sample().map(|(generation,index,start,end,target)|json!({
        "generation":generation,"index":index,"start":start,"end":end,"active_time_ms":target.official_active_time_ms,"phase":target.expected_phase_token}))}else{None};
    Ok(json!({"status":"accepted","sdk_epoch":owner.epoch,"runtime_epoch":owner.runtime_epoch,"proof":evidence,"control":control,"phase":phase}).to_string())
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_SdkExperimentSessionCore_nativeBind(
    env:EnvUnowned,_class:JClass,epoch:jlong,instance:jlong,session:jlong)->jstring {response(env,result(bind(epoch,instance,session)))}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_SdkExperimentSessionCore_nativeRevoke(
    env:EnvUnowned,_class:JClass,epoch:jlong,terminal:jboolean)->jstring {response(env,result(revoke(epoch,terminal)))}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_SdkExperimentSessionCore_nativePoll(
    env:EnvUnowned,_class:JClass,epoch:jlong)->jstring {response(env,result(poll(epoch)))}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_SdkExperimentSessionCore_nativeValidateAudio(
    _env:EnvUnowned,_class:JClass,epoch:jlong,runtime_epoch:jlong,generation:jlong,active:jboolean)->jboolean {
    let valid=(||->Result<(),String>{let owner=proof::capture(positive(epoch)?)?;
        if owner.runtime_epoch!=positive(runtime_epoch)?||generation<0{return Err("sdk-audio-owner-invalid".into());}
        proof::validate_audio(&owner,generation as u64,active)})();valid.is_ok()
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_SdkExperimentSessionCore_nativeInputSource(
    env:EnvUnowned,_class:JClass,epoch:jlong,identity:jlong)->jstring {
    response(env,result((||{let owner=proof::capture(positive(epoch)?)?;
        if identity==0{controller::invalidate_input(&owner)?;return Err("sdk-input-source-unbound".into());}
        controller::input_source(&owner,identity as u64)?;Ok(json!({"status":"accepted"}).to_string())})()))
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_SdkExperimentSessionCore_nativeButtonSample(
    env:EnvUnowned,_class:JClass,epoch:jlong,sequence:jlong,elapsed:jlong,pressed:jboolean)->jstring{
    response(env,result((||{let owner=proof::capture(positive(epoch)?)?;
        controller::button_sample(&owner,positive(sequence)?,positive(elapsed)?,pressed)?;
        Ok(json!({"status":"accepted"}).to_string())})()))
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_SdkExperimentSessionCore_nativeControllerPose(
    env:EnvUnowned,_class:JClass,epoch:jlong,sequence:jlong,elapsed:jlong,
    px:jdouble,py:jdouble,pz:jdouble,qx:jdouble,qy:jdouble,qz:jdouble,qw:jdouble,active:jboolean,tracked:jboolean,right_secondary_pressed:jboolean)->jstring {
    response(env,result((||{let owner=proof::capture(positive(epoch)?)?;
        let tuple=positive(sequence).and_then(|s|positive(elapsed).map(|t|(s,t)));
        let (sequence,elapsed)=match tuple{Ok(v)=>v,Err(reason)=>{controller::invalidate_input(&owner)?;return Err(reason);}};
        controller::pose(&owner,sequence,elapsed,[px,py,pz],[qx,qy,qz,qw],active,tracked,right_secondary_pressed)?;
        Ok(json!({"status":"accepted","sequence":sequence}).to_string())})()))
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeApplyBreathCompositionCommand(
    mut env:EnvUnowned,_class:JClass,command:JString)->jstring {
    let value=match env.with_env(|env|command.try_to_string(env)).into_outcome(){
        jni::Outcome::Ok(raw)=>runtime::apply_command_json(&raw),
        _=>json!({"status":"rejected","reason":"invalid-jni-string"}).to_string()}; response(env,value)
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeReadBreathCompositionStatus(
    env:EnvUnowned,_class:JClass)->jstring {response(env,controller::status_json())}
