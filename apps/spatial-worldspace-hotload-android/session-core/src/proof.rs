//! Exact-context submission admission. Diagnostic UI state is never an input.
use std::sync::{Arc,Mutex,OnceLock};
use serde_json::json;
use crate::scope::{ScopeGate,Lease};
use crate::{experiment_session::PresentationState, experiment_session_runtime as runtime, services};
pub(crate) const EXPIRY_NS: u64 = 250_000_000;
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Witness {
    library: u64, instance_generation: u64, session_generation: u64, lifetime: u64, focus: u64,
    sequence: u64, observed_ns: u64, poll_ns: u64, now_ns: u64, eligible: u32, reserved: u32,
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct Binding {
    epoch: u64, runtime_epoch: u64, generation: u64, instance: u64, session: u64,
    service_generation: u64, witness: Witness, advances: u8, revoked: bool,
    input_valid: bool, revision:u64, adopted_generation:u64,
}
#[derive(Clone)]
pub(crate) struct Captured { pub epoch:u64, pub runtime_epoch:u64, pub service_generation:u64, pub runtime:Arc<runtime::ExperimentSessionRuntime> }
static CAPTURED:OnceLock<Mutex<Option<Captured>>>=OnceLock::new();
static EFFECTS:OnceLock<ScopeGate>=OnceLock::new();
fn effects()-> &'static ScopeGate {EFFECTS.get_or_init(ScopeGate::default)}
fn captured_slot()-> &'static Mutex<Option<Captured>>{CAPTURED.get_or_init(||Mutex::new(None))}
pub(crate) fn capture(epoch:u64)->Result<Captured,String>{
    captured_slot().lock().map_err(|_|"sdk-capture-lock-failed")?.as_ref().filter(|v|v.epoch==epoch).cloned().ok_or("sdk-owner-stale".into())
}
pub(crate) fn current()->Result<Captured,String>{captured_slot().lock().map_err(|_|"sdk-capture-lock-failed")?.clone().ok_or("sdk-owner-unbound".into())}
pub(crate) fn effect(owner:&Captured)->Result<Lease<'static>,String>{
    let lease=effects().lease(owner.epoch)?;
    let s=state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    if s.revoked||s.epoch!=owner.epoch||s.runtime_epoch!=owner.runtime_epoch{return Err("sdk-owner-retired".into());}
    Ok(lease)
}
pub(crate) fn cleanup_effect(owner:&Captured)->Result<Lease<'static>,String>{
    let lease=effects().cleanup_lease(owner.epoch)?;
    let s=state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    if s.epoch!=owner.epoch||s.runtime_epoch!=owner.runtime_epoch{return Err("sdk-owner-replaced".into());}
    Ok(lease)
}
pub(crate) fn validate_audio(owner:&Captured,generation:u64,active:bool)->Result<(),String>{
    let _effect=cleanup_effect(owner)?;
    if generation>0&&owner.runtime.captured_generation()!=generation {
        let receipt=owner.runtime.captured_control_receipt();
        if active||!receipt.is_some_and(|(g,_,_)|g==generation){return Err("sdk-audio-generation-stale".into());}
    }
    if active {validate(owner.runtime_epoch,generation,owner.epoch,true)?;}
    Ok(())
}
static BINDING: OnceLock<Mutex<Binding>> = OnceLock::new();
fn state() -> &'static Mutex<Binding> { BINDING.get_or_init(|| Mutex::new(Binding::default())) }
#[cfg(target_os = "android")]
extern "C" {
    fn rq_session_read_witness(instance: u64, session: u64, expected_instance: u64, expected_session: u64, out: *mut Witness) -> i32;
    fn rq_session_host_monotonic_ns() -> u64;
    fn rq_session_android_elapsed_ns() -> u64;
}
pub(crate) fn elapsed_ns() -> Option<u64> {
    #[cfg(target_os = "android")] { let value = unsafe { rq_session_android_elapsed_ns() }; (value > 0).then_some(value) }
    #[cfg(not(target_os = "android"))] { None }
}
fn read(binding: Binding) -> Result<Witness, String> {
    #[cfg(target_os = "android")] {
        let mut witness = Witness::default();
        let code = unsafe { rq_session_read_witness(binding.instance, binding.session,
            binding.witness.instance_generation, binding.witness.session_generation, &mut witness) };
        if code != 0 { return Err(format!("sdk-observer-unavailable:{code}")); }
        Ok(witness)
    }
    #[cfg(not(target_os = "android"))] { let _ = binding; Err("sdk-observer-android-only".into()) }
}
fn fresh(value: Witness) -> bool {
    value.eligible == 1 && value.library != 0 && value.instance_generation > 0 && value.session_generation > 0 &&
        value.sequence > 0 && value.sequence < u64::MAX && value.observed_ns > 0 && value.poll_ns > 0 &&
        value.now_ns.checked_sub(value.observed_ns).is_some_and(|age| age < EXPIRY_NS) &&
        value.now_ns.checked_sub(value.poll_ns).is_some_and(|age| age < EXPIRY_NS)
}
fn same_context(a: Witness, b: Witness) -> bool {
    a.library == b.library && a.instance_generation == b.instance_generation && a.session_generation == b.session_generation &&
        a.lifetime == b.lifetime && a.focus == b.focus
}
fn advance(mut binding: Binding, value: Witness) -> Result<Binding, String> {
    if binding.revoked || !fresh(value) { return Err("sdk-submission-unproven".into()); }
    if binding.witness.library != 0 && !same_context(binding.witness, value) {
        return Err("sdk-submission-context-changed".into());
    }
    if value.sequence < binding.witness.sequence { return Err("sdk-submission-regressed".into()); }
    if value.sequence > binding.witness.sequence {
        let continuous = binding.witness.now_ns == 0 || value.now_ns.checked_sub(binding.witness.now_ns).is_some_and(|age| age < EXPIRY_NS);
        binding.advances = if continuous { binding.advances.saturating_add(1).min(2) } else { 1 };
        binding.witness = value;
    }
    Ok(binding)
}

#[cfg(target_os="android")]
pub(crate) fn bind(epoch:u64,instance:u64,session:u64)->Result<Captured,String>{
    if instance==0||session==0{return Err("sdk-scene-binding-invalid".into());}
    let initial_service=services::status()?; // Unavailable real GPU does not consume a scene epoch.
    effects().replace(epoch,||{
        if let Ok(old)=current(){
            let (adopted,was_revoked)={let mut s=state().lock().map_err(|_|"sdk-proof-lock-failed")?;let retired=s.revoked;s.revoked=true;s.advances=0;s.input_valid=false;(s.adopted_generation,retired)};
            if !was_revoked {services::invalidate(&old,adopted,0,0,3)?;}
            old.runtime.captured_preparation_presentation(PresentationState::Unfocused);
        }
        let (runtime_epoch,runtime)=runtime::capture_immersive_owner_runtime(epoch)?;
        let service=services::status()?;
        if service.generation!=initial_service.generation{return Err("sdk-service-changed-during-bind".into());}
        let owner=Captured{epoch,runtime_epoch,runtime,service_generation:service.generation};
        let generation=owner.runtime.captured_generation();
        services::invalidate(&owner,generation,0,0,1)?;
        crate::controller::reset_locked();
        crate::controller::ensure_running_locked(&owner)?;
        *state().lock().map_err(|_|"sdk-proof-lock-failed")?=Binding{epoch,runtime_epoch,generation,adopted_generation:generation,
            instance,session,service_generation:service.generation,..Binding::default()};
        *captured_slot().lock().map_err(|_|"sdk-capture-lock-failed")?=Some(owner.clone());
        Ok(owner)
    })
}
pub(crate) fn revoke(owner:&Captured)->Result<(),String>{
    effects().revoke(owner.epoch,||{
        let (adopted,was_revoked)={let mut s=state().lock().map_err(|_|"sdk-proof-lock-failed")?;
            if s.epoch!=owner.epoch{return Err("sdk-owner-stale".into());}
            let retired=s.revoked;s.revoked=true;s.input_valid=false;s.advances=0;(s.adopted_generation,retired)};
        crate::controller::reset_locked();
        let invalidation=if was_revoked{Ok(())}else{services::invalidate(owner,adopted,0,0,3)};
        owner.runtime.captured_preparation_presentation(PresentationState::Unfocused);
        presentation(owner,owner.runtime.captured_generation(),PresentationState::Unfocused,"pause");
        invalidation
    })
}
pub(crate) fn command_owner_token()->u64{
    // Captured Java scope may still deliver cleanup acknowledgements after revoke.
    // Critical operations independently require a live proof in admit().
    state().lock().ok().map_or(0,|s|s.epoch)
}
pub(crate) fn boundary_locked(owner:&Captured,generation:u64)->Result<(),String>{
    let adopted=state().lock().map_err(|_|"sdk-proof-lock-failed")?.adopted_generation;
    if adopted==generation{return Ok(());}
    input_invalid_locked(owner)?;
    services::invalidate(owner,generation,0,0,1)?;
    let mut s=state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    s.adopted_generation=generation;s.generation=generation;s.advances=0;s.witness.sequence=0;Ok(())
}
fn invalidate_binding(s:&mut Binding)->Result<(),String>{
    s.input_valid=false;s.revision=s.revision.checked_add(1).ok_or("sdk-proof-revision-exhausted")?;Ok(())
}
pub(crate) fn input_invalid_locked(owner:&Captured)->Result<(),String>{
    let mut s=state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    if s.epoch!=owner.epoch||s.revoked{return Err("sdk-owner-retired".into());}
    invalidate_binding(&mut s)
}
pub(crate) fn input_valid_locked(owner:&Captured,generation:u64)->Result<(),String>{
    let mut s=state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    if s.epoch!=owner.epoch||s.revoked||s.adopted_generation!=generation{return Err("sdk-owner-or-session-retired".into());}
    s.input_valid=true;s.revision=s.revision.checked_add(1).ok_or("sdk-proof-revision-exhausted")?;Ok(())
}
fn presentation(owner:&Captured,generation:u64,value:PresentationState,kind:&str){
    let Some(now)=owner.runtime.current_elapsed_realtime_ns()else{return;};
    let token=match value{PresentationState::ImmersiveActive=>"immersive-active",_=>"unfocused"};
    let raw=json!({"schema":runtime::EXPERIMENT_SESSION_COMMAND_SCHEMA,"operation":"presentation",
        "operation_id":format!("sdk-{kind}-{}-{generation}-{now}",owner.epoch),"expected_generation":generation,
        "elapsed_realtime_ns":now,"presentation":token}).to_string();
    let _=owner.runtime.apply_command_json_scoped(&raw,owner.epoch);
}
#[derive(Clone,Copy)]
struct Validated {binding:Binding,witness:Witness,service:services::Status,require_controller:bool}
fn ages_current(v:&Validated,host_now:u64,elapsed_now:u64)->bool{
    let mut frame=v.witness;frame.now_ns=host_now;
    fresh(frame)&&(!v.require_controller||elapsed_now.checked_sub(v.service.submitted_elapsed_ns).is_some_and(|age|age<EXPIRY_NS))
}
fn host_ns()->Option<u64>{
    #[cfg(target_os="android")] {let n=unsafe{rq_session_host_monotonic_ns()};(n>0).then_some(n)}
    #[cfg(not(target_os="android"))] {None}
}
fn validate_binding(runtime_epoch:u64,generation:u64,epoch:u64,require_controller:bool)->Result<Validated,String>{
    let binding=*state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    if binding.revoked||binding.epoch!=epoch||binding.runtime_epoch!=runtime_epoch||binding.generation!=generation||binding.advances<2{return Err("sdk-proof-owner-or-generation-mismatch".into());}
    let owner=capture(epoch)?;
    if owner.runtime.captured_generation()!=generation{return Err("sdk-session-changed".into());}
    let witness=read(binding)?;
    if !fresh(witness)||!same_context(binding.witness,witness)||witness.sequence<binding.witness.sequence{return Err("sdk-current-submission-unproven".into());}
    let service=services::status()?;
    if service.generation!=binding.service_generation{return Err("sdk-service-generation-changed".into());}
    if require_controller&&(!binding.input_valid||service.submitted_runtime_epoch!=runtime_epoch||service.submitted_sdk_epoch!=epoch||service.submitted_session_generation!=generation||
        service.running!=1||service.controller_ready!=1||service.submitted==0||service.submitted_sequence==0||service.submitted_sequence>service.input_sequence||
        !crate::controller::ready(service.generation,service.submitted_sequence)) {return Err("sdk-controller-or-submitted-settings-unproven".into());}
    let v=Validated{binding,witness,service,require_controller};
    if !ages_current(&v,host_ns().ok_or("sdk-host-clock-unavailable")?,elapsed_ns().ok_or("sdk-android-clock-unavailable")?){return Err("sdk-proof-expired".into());}
    if *state().lock().map_err(|_|"sdk-proof-lock-failed")?!=binding{return Err("sdk-proof-revoked-during-read".into());}Ok(v)
}
pub(crate) fn validate(runtime_epoch:u64,generation:u64,epoch:u64,require_controller:bool)->Result<(),String>{validate_binding(runtime_epoch,generation,epoch,require_controller).map(|_|())}
pub(crate) struct Admission{_guard:std::sync::MutexGuard<'static,Binding>}
pub(crate) fn admit(runtime_epoch:u64,generation:u64,epoch:u64,require_controller:bool)->Result<Admission,String>{
    let v=validate_binding(runtime_epoch,generation,epoch,require_controller)?;
    let guard=state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    if *guard!=v.binding{return Err("sdk-proof-revoked-before-mutation".into());}
    // Recheck all three independent ages after the final lock wait. No callback/I/O under this gate.
    if !ages_current(&v,host_ns().ok_or("sdk-host-clock-unavailable")?,elapsed_ns().ok_or("sdk-android-clock-unavailable")?){return Err("sdk-proof-expired-before-mutation".into());}
    Ok(Admission{_guard:guard})
}
pub(crate) fn admit_scoped(runtime_epoch:u64,epoch:u64)->Result<Admission,String>{
    let guard=state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    if guard.epoch!=epoch||guard.runtime_epoch!=runtime_epoch{return Err("sdk-command-owner-replaced".into());}
    Ok(Admission{_guard:guard})
}
pub(crate) fn poll(owner:&Captured)->Result<String,String>{
    let before=*state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    if before.epoch!=owner.epoch||before.revoked{return Err("sdk-scene-not-bound".into());}
    let generation=owner.runtime.captured_generation();
    let observed=read(before); // May block; no effect or lifecycle gate is held.
    let _effect=effect(owner)?; // Old read cannot mutate a replacement, including its error path.
    if owner.runtime.captured_generation()!=generation{return Err("sdk-session-changed-during-read".into());}
    boundary_locked(owner,generation)?;
    let current=*state().lock().map_err(|_|"sdk-proof-lock-failed")?;
    let result=observed.and_then(|w|advance(current,w));
    let next=match result{Ok(v)=>v,Err(reason)=>{
        {let mut s=state().lock().map_err(|_|"sdk-proof-lock-failed")?;s.advances=0;}
        owner.runtime.captured_preparation_presentation(PresentationState::Unfocused);
        presentation(owner,generation,PresentationState::Unfocused,"proof-loss");return Err(reason);
    }};
    *state().lock().map_err(|_|"sdk-proof-lock-failed")?=next;
    let admitted=validate(owner.runtime_epoch,generation,owner.epoch,generation>0).is_ok();
    if admitted {
        if generation==0{owner.runtime.captured_preparation_presentation(PresentationState::ImmersiveActive);}
        else if owner.runtime.note_submitted_frame(generation,next.witness.sequence)&&owner.runtime.captured_presentation()!=PresentationState::ImmersiveActive {
            presentation(owner,generation,PresentationState::ImmersiveActive,"proof");
        }
    }
    Ok(json!({"schema":"rusty.quest.sdk_session_proof.v1","sdk_epoch":owner.epoch,"runtime_epoch":owner.runtime_epoch,
        "session_generation":generation,"submission_admitted":admitted,"gpu_completion_proven":false,"sdk_pixels_proven":false}).to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn sample(sequence: u64, now: u64) -> Witness { Witness { library:1, instance_generation:2, session_generation:3,
        lifetime:4,focus:5,sequence,observed_ns:now,poll_ns:now,now_ns:now,eligible:1,reserved:0 } }
    #[test] fn two_actual_advances_required_and_replay_does_not_refresh() {
        let a=advance(Binding::default(),sample(1,100)).unwrap(); assert_eq!(a.advances,1);
        let b=advance(a,sample(1,200)).unwrap(); assert_eq!(b.advances,1); assert_eq!(b.witness.now_ns,100);
        assert_eq!(advance(b,sample(2,300)).unwrap().advances,2);
    }
    #[test] fn expiry_focus_lifetime_and_sequence_fail_closed() {
        let a=advance(Binding::default(),sample(5,100)).unwrap();
        let mut stale=sample(6,100+EXPIRY_NS); stale.observed_ns=100; assert!(advance(a,stale).is_err());
        let mut changed=sample(6,200); changed.focus+=1; assert!(advance(a,changed).is_err());
        changed.focus-=1; changed.lifetime+=1; assert!(advance(a,changed).is_err());
        assert!(advance(a,sample(4,200)).is_err());
        assert_eq!(advance(a,sample(6,100+EXPIRY_NS)).unwrap().advances,1);
    }
    #[test] fn final_queue_wait_rechecks_observed_poll_and_input_clocks_independently(){
        let mut v=Validated{binding:Binding::default(),witness:sample(1,100),service:services::Status::default(),require_controller:true};
        v.service.submitted_elapsed_ns=900;
        assert!(ages_current(&v,101,901));
        assert!(!ages_current(&v,100+EXPIRY_NS,901));
        v.witness.observed_ns=100+EXPIRY_NS;
        assert!(!ages_current(&v,100+EXPIRY_NS,901)); // poll aged out although frame is recent
        v.witness.poll_ns=100+EXPIRY_NS;
        assert!(!ages_current(&v,100+EXPIRY_NS,900+EXPIRY_NS)); // Android input aged out independently
        assert!(!ages_current(&v,100+EXPIRY_NS,899)); // future input never admitted
    }
    #[test] fn loss_invalidates_an_accepted_binding_even_with_prior_submission(){
        let mut b=Binding{input_valid:true,advances:2,..Binding::default()};
        invalidate_binding(&mut b).unwrap();assert!(!b.input_valid);assert_eq!(b.revision,1);
    }
    #[test] fn no_diagnostic_or_zero_identity_can_supply_proof() {
        let mut a=sample(1,100); a.library=0; assert!(!fresh(a));
        a=sample(1,100); a.eligible=0; assert!(!fresh(a));
        a=sample(u64::MAX,100); assert!(!fresh(a));
        assert!(advance(Binding{revoked:true,..Binding::default()},sample(1,100)).is_err());
    }
}
