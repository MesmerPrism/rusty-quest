//! The full production JNI inspection caller/projection are inserted by the runner.
//! JNI/registry/platform leaves are explicit models; no Android or physical claims.
#![allow(dead_code, non_camel_case_types, non_snake_case, improper_ctypes_definitions)]
use std::path::Path;
use std::sync::{Arc,Mutex};
use std::cell::RefCell;
use std::marker::PhantomData;
type jstring=usize;
#[derive(Default)] struct Fixture {
    pending:bool, recovery_only:bool, changed:bool, record_reads:usize, refusal:Option<&'static str>,
    registry_error:bool, process_error:bool, handoff_error:bool, directory_error:bool,
    output:Option<Result<String,String>>,
}
thread_local! {static ACTIVE:RefCell<Option<Arc<Mutex<Fixture>>>>=const{RefCell::new(None)};}
fn active()->Arc<Mutex<Fixture>>{ACTIVE.with(|a|a.borrow().as_ref().unwrap().clone())}
struct JNIEnv<'a>{state:Arc<Mutex<Fixture>>,life:PhantomData<&'a ()>}
struct JClass<'a>{life:PhantomData<&'a ()>}
struct JObject<'a>{absent:bool,life:PhantomData<&'a ()>}
impl JObject<'_>{fn is_null(&self)->bool{self.absent}}
fn callback_string(env:&mut JNIEnv<'_>,_:&JObject<'_>,method:&str,_:usize)->Result<String,String>{
    let state=env.state.lock().unwrap();
    if state.directory_error || method=="nativeFenceDirectory"&&state.recovery_only{
        return Err("native app fence callback unavailable".into());
    }
    if method!="nativeFenceDirectory"&&method!="nativeInspectionDirectory"{return Err("unexpected directory callback".into());}
    Ok("/modeled-held-private-directory".into())
}
struct Source{state:Arc<Mutex<Fixture>>}
impl Source{fn inspection_record(&self)->Result<String,String>{
    let mut state=self.state.lock().unwrap();state.record_reads+=1;
    if state.changed&&state.record_reads>1{Ok("changed".into())}else{Ok(if state.pending{"pending"}else{"clear"}.into())}
}}
fn capture_source(env:&mut JNIEnv<'_>,_:JObject<'_>)->Result<Arc<Source>,String>{Ok(Arc::new(Source{state:env.state.clone()}))}
#[derive(Clone)] struct AppBinding{operational_run_id:Option<String>}
impl AppBinding{fn inspect(record:&str)->Result<(Self,bool),String>{Ok((Self{operational_run_id:Some("b".repeat(32))},record=="pending"))}}
struct OperationalRunInspection{actual_binding:Option<AppBinding>,native_generation:Option<u64>,refusal:Option<&'static str>}
struct Registry;
fn registry()->Registry{Registry}
impl Registry{fn inspect_operational_run(&self,_:&Path,binding:&AppBinding)->Result<OperationalRunInspection,String>{
    let state=active();let state=state.lock().unwrap();
    if state.registry_error{return Err("modeled registry failure".into());}
    Ok(OperationalRunInspection{actual_binding:Some(binding.clone()),native_generation:Some(3),refusal:state.refusal})
}}
fn source_handoff_available(source:&Source,_:&AppBinding,_:bool,_:Option<u64>)->Result<bool,String>{
    if source.state.lock().unwrap().handoff_error{Err("modeled exact handoff mismatch".into())}else{Ok(false)}
}
mod runtime_host{
    use super::*;
    pub fn operational_run_eligibility()->Result<bool,String>{
        if active().lock().unwrap().process_error{Err("native_renderer_owned_or_unproven".into())}else{Ok(false)}
    }
    pub fn return_string(env:&mut JNIEnv<'_>,result:Result<String,String>)->jstring{env.state.lock().unwrap().output=Some(result);1}
}
mod actual{
    use super::*;
    // ACTUAL_PRODUCTION_INSPECTION_BODY
}
fn call(fixture:Fixture,absent:bool)->Result<serde_json::Value,String>{
    let state=Arc::new(Mutex::new(fixture));ACTIVE.with(|a|*a.borrow_mut()=Some(state.clone()));
    actual::Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_inspectOperationalRun(
        JNIEnv{state:state.clone(),life:PhantomData},JClass{life:PhantomData},JObject{absent,life:PhantomData});
    let result=state.lock().unwrap().output.take().unwrap();result.map(|raw|serde_json::from_str(&raw).unwrap())
}
#[test] fn cold_pending_returns_actual_refusal_instead_of_admission_callback_exception(){
    let value=call(Fixture{pending:true,recovery_only:true,..Default::default()},false).expect("read-only JNI caller must observe cold Pending");
    assert_eq!(value["globally_eligible"],false);assert_eq!(value["refusal_reason"],"app_global_effects_pending");assert_eq!(value["native_generation"],3);
}
#[test] fn clear_observation_preserves_eligibility(){let value=call(Fixture::default(),false).unwrap();assert_eq!(value["globally_eligible"],true);}
#[test] fn changed_held_record_cannot_be_eligible(){let value=call(Fixture{changed:true,..Default::default()},false).unwrap();assert_eq!(value["globally_eligible"],false);assert_eq!(value["refusal_reason"],"app_global_fence_changed_during_inspection");}
#[test] fn unavailable_directory_and_absent_callback_stay_errors(){assert!(call(Fixture{directory_error:true,..Default::default()},false).is_err());assert!(call(Fixture::default(),true).is_err());}
#[test] fn registry_process_and_stale_handoff_refusals_remain_typed(){
    for (fixture,reason) in [
        (Fixture{registry_error:true,..Default::default()},"native_registry_unavailable"),
        (Fixture{process_error:true,..Default::default()},"native_renderer_owned_or_unproven"),
        (Fixture{pending:true,refusal:Some("native_prior_process_owner_unreconciled"),..Default::default()},"native_prior_process_owner_unreconciled"),
        (Fixture{pending:true,refusal:Some("native_prior_process_owner_unreconciled"),handoff_error:true,..Default::default()},"native_operational_handoff_invalid"),
    ]{let value=call(fixture,false).unwrap();assert_eq!(value["globally_eligible"],false);assert_eq!(value["refusal_reason"],reason);}
}
