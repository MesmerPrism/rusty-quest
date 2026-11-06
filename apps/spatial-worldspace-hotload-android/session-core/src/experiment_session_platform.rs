//! SDK implementation: captured services and current scene evidence.
use crate::{experiment_session::{PreparationMode,SessionCommand,PresentationState},services,proof,controller};
pub(crate) fn composition_status_json()->String{controller::status_json()}
pub(crate) fn packaged_preparation_mode()->PreparationMode{PreparationMode::LocalPanel}
pub(crate) fn command_owner_token()->u64{proof::command_owner_token()}
pub(crate) fn ensure_running_for_experiment_arm(token:u64)->Result<(),String>{
    let owner=proof::capture(token)?;let _effect=proof::effect(&owner)?;
    // Already configured by real scene binding. No replacement-owner side effect.
    if services::status()?.generation!=owner.service_generation{return Err("sdk-service-generation-changed".into());}Ok(())
}
pub(crate) fn reset_packaged_defaults(token:u64)->Result<u64,String>{
    // Durable finalization cleanup belongs to this captured worker even after its
    // scene retires. A revoked/replaced owner cannot reset replacement GPU state.
    let Ok(owner)=proof::capture(token) else{return Ok(0);};
    let Ok(_effect)=proof::effect(&owner) else{return Ok(0);};
    proof::input_invalid_locked(&owner)?;controller::reset_locked();services::reset()
}
pub(crate) struct Admission{_proof:Option<proof::Admission>}
pub(crate) fn admit_command(runtime_epoch:u64,generation:u64,token:u64,command:&SessionCommand)->Result<Admission,String>{
    let owner=proof::capture(token)?;
    if owner.runtime_epoch!=runtime_epoch{return Err("sdk-command-runtime-replaced".into());}
    // Ingress identity is captured when enqueued, never acquired from a replacement at execution.
    let current=proof::current()?;
    if current.epoch!=token{return Err("sdk-command-owner-replaced".into());}
    match command{
        SessionCommand::Arm{..}=>proof::admit(runtime_epoch,generation,token,false).map(|p|Admission{_proof:Some(p)}),
        SessionCommand::Start{..}|SessionCommand::OfficialStart|SessionCommand::Resume|
        SessionCommand::PresentationChanged(PresentationState::ImmersiveActive)|SessionCommand::AudioStarted=>
            proof::admit(runtime_epoch,generation,token,true).map(|p|Admission{_proof:Some(p)}),
        _=>proof::admit_scoped(runtime_epoch,token).map(|p|Admission{_proof:Some(p)}),
    }
}
pub(crate) fn immersive_proof_current(epoch:u64,generation:u64)->bool{
    let token=proof::command_owner_token();proof::validate(epoch,generation,token,true).is_ok()
}
