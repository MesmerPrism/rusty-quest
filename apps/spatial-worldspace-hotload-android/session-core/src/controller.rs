//! SDK pose adapter over the canonical controller assessment and composition.
//! Timestamps are actual Android elapsed acquisition times, never SDK/XrTime fiction.
use std::{collections::VecDeque, sync::{Mutex, OnceLock}};
use serde_json::{json, Value};
use rusty_quest_breath_contract::{BreathTimestampMicros, assessment::{CommonBreathPhase, BreathAssessmentObservation},
    calibration::CalibrationParameters, composition::*};
use crate::{services::{self, Effective}, proof, experiment_session_runtime as runtime,
    openxr_controller_breath_adapter::*, native_controller_breath_state::NativeControllerBreathSettings,
    breath_composition_driver::{BreathCompositionDriver, BreathCompositionDriverSettings},
    native_renderer_properties::*, session_recording_clock::SourceClock, session_recording_contract::BreathPhase};
struct Controller {
    service_generation: u64, authority: BreathCompositionAuthority, adapter: OpenXrControllerBreathAdapter,
    driver: BreathCompositionDriver, driver_settings: BreathCompositionDriverSettings,
    controls: [f32;8], visual_scale: f32, center: [f32;4],
    calibration_button: crate::controller_button::ControllerButton,
    last_sequence: u64, last_elapsed: u64, accepted: VecDeque<(u64,u64)>,
}
static CONTROLLER: OnceLock<Mutex<Option<Controller>>> = OnceLock::new();
fn state() -> &'static Mutex<Option<Controller>> { CONTROLLER.get_or_init(|| Mutex::new(None)) }
fn property(value: &Value, key: &str) -> Option<String> { value["properties"][key].as_str().map(str::to_owned) }
fn finite_array<const N: usize>(value: &Value) -> Result<[f32; N], String> {
    let a=value.as_array().filter(|a|a.len()==N).ok_or("sdk-settings-array-invalid")?;
    let mut result=[0.0;N]; for (i,v) in a.iter().enumerate() { let n=v.as_f64().ok_or("sdk-settings-number-invalid")? as f32;
        if !n.is_finite(){return Err("sdk-settings-number-invalid".into());} result[i]=n; } Ok(result)
}
fn new_controller(service_generation: u64) -> Result<Controller,String> {
    let config=services::settings()?;
    if config["radius_slot"].as_u64()!=Some(0) || config["deformation_slot"].as_u64()!=Some(0) {return Err("sdk-radius-driver-layout-unsupported".into());}
    let binding=services::expected_binding()?;
    if property(config,PROP_BREATH_COMPOSITION_ACTIVATION_BINDING_SHA256).as_deref()!=Some(binding) ||
        property(config,PROP_BREATH_COMPOSITION_SOURCE).as_deref()!=Some("controller") ||
        property(config,PROP_BREATH_COMPOSITION_ENABLED).as_deref()!=Some("true") ||
        property(config,PROP_BREATH_COMPOSITION_POLAR_ACC_ASSESSMENT_ENABLED).as_deref()!=Some("false") {
        return Err("sdk-controller-selection-not-closed".into());
    }
    let mut digest=[0;32]; for (i,byte) in digest.iter_mut().enumerate() { *byte=u8::from_str_radix(&binding[i*2..i*2+2],16).map_err(|_|"sdk-binding-invalid")?; }
    let mapping=match property(config,PROP_BREATH_COMPOSITION_MAPPING).as_deref(){Some("volume")=>BreathCompositionMapping::Volume,Some("state")=>BreathCompositionMapping::State,_=>return Err("sdk-mapping-invalid".into())};
    let projection=match property(config,PROP_BREATH_COMPOSITION_CONTROLLER_PROJECTION).as_deref(){Some("fixed-orientation")=>ControllerProjectionSelection::FixedOrientation,Some("dynamic-axis")=>ControllerProjectionSelection::DynamicAxis,_=>return Err("sdk-projection-invalid".into())};
    let inverted=match property(config,PROP_BREATH_COMPOSITION_INVERTED).as_deref(){Some("true")=>true,Some("false")|None=>false,_=>return Err("sdk-inversion-invalid".into())};
    let mut authority=BreathCompositionAuthority::new(true,BreathCompositionBinding::Digest(digest),BreathCompositionBinding::Digest(digest),
        BreathCompositionCapabilities{controller_assessment:true,polar_acc_assessment:false,volume_mapping:true,state_mapping:true,same_apk_panel:true},250_000);
    let selected=authority.select(Some(BreathCompositionRequest{source:BreathCompositionSource::Controller,mapping,controller_projection:projection,polar_projection:PolarProjectionSelection::Xz,inverted}));
    if selected.rejection.is_some(){return Err("sdk-composition-selection-rejected".into());}
    authority.action(BreathCompositionAction::Configure);
    let started=authority.action(BreathCompositionAction::Start);
    let generation=started.generation.ok_or("sdk-composition-start-rejected")?;
    let defaults=NativeControllerBreathSettings::default();
    let number=|name,default:f32| -> Result<f32,String> { match property(config,name) { Some(v)=>v.parse::<f32>().ok().filter(|v|v.is_finite()).ok_or_else(||"sdk-phase-setting-invalid".into()),None=>Ok(default) } };
    let count=|name,default:u32| -> Result<u32,String> { match property(config,name){Some(v)=>v.parse().map_err(|_|"sdk-phase-setting-invalid".into()),None=>Ok(default)} };
    let phase=NativeControllerBreathSettings::new(
        [number(PROP_CONTROLLER_BREATH_STATE_AXIS_X,defaults.orientation_axis[0])?,number(PROP_CONTROLLER_BREATH_STATE_AXIS_Y,defaults.orientation_axis[1])?,number(PROP_CONTROLLER_BREATH_STATE_AXIS_Z,defaults.orientation_axis[2])?],
        number(PROP_CONTROLLER_BREATH_STATE_INHALE_THRESHOLD,defaults.inhale_threshold)?,number(PROP_CONTROLLER_BREATH_STATE_EXHALE_THRESHOLD,defaults.exhale_threshold)?,
        number(PROP_CONTROLLER_BREATH_STATE_ROTATION_GUARD_DEGREES,defaults.rotation_guard_degrees)?,number(PROP_CONTROLLER_BREATH_STATE_MOVING_AVERAGE_GUARD,defaults.moving_average_guard)?,
        count(PROP_CONTROLLER_BREATH_STATE_SHORT_WINDOW_SAMPLES,defaults.short_window_samples)?,count(PROP_CONTROLLER_BREATH_STATE_LONG_WINDOW_SAMPLES,defaults.long_window_samples)?,
        number(PROP_CONTROLLER_BREATH_STATE_SHORT_WINDOW_SECONDS,defaults.short_window_seconds)?,number(PROP_CONTROLLER_BREATH_STATE_LONG_WINDOW_SECONDS,defaults.long_window_seconds)?);
    let volume=ControllerVolumeSettings::new(if projection==ControllerProjectionSelection::FixedOrientation{ControllerVolumeProjection::FixedOrientation}else{ControllerVolumeProjection::DynamicMotionAxis},
        phase.orientation_axis.map(f64::from),f64::from(phase.rotation_guard_degrees),CalibrationParameters::default()).map_err(|_|"sdk-volume-settings-invalid")?;
    let mut adapter=OpenXrControllerBreathAdapter::new(phase,volume);
    let at=BreathTimestampMicros::new(proof::elapsed_ns().ok_or("sdk-clock-unavailable")?/1000);
    adapter.configure(at);
    let calibration_settings=crate::breath_calibration_controller_action::BreathCalibrationControllerActionSettings::from_property_lookup(|key|property(config,key));
    if !calibration_settings.enabled(){return Err("sdk-calibration-gesture-unselected".into());}
    let calibration_button=crate::controller_button::ControllerButton::new(calibration_settings);
    let driver_settings=BreathCompositionDriverSettings::from_property_lookup(|key|property(config,key));
    let controls=finite_array(&config["controls"])?;
    if controls.iter().any(|v| !(0.0..=1.0).contains(v)){return Err("sdk-controls-invalid".into());}
    let visual_scale=config["visual_scale"].as_f64().filter(|v|v.is_finite()&&*v>0.0).ok_or("sdk-scale-invalid")? as f32;
    Ok(Controller{service_generation,authority,adapter,driver:BreathCompositionDriver::new(driver_settings),driver_settings,
        controls,visual_scale,center:finite_array(&config["center"])? ,calibration_button,last_sequence:0,last_elapsed:0,accepted:VecDeque::new()})
}
pub(crate) fn ensure_running_locked(owner:&proof::Captured)->Result<(),String>{
    services::ensure_running()?;let service=services::status()?;
    if service.generation!=owner.service_generation{return Err("sdk-service-generation-changed".into());}
    let mut guard=state().lock().map_err(|_|"sdk-controller-lock-failed")?;
    if guard.as_ref().is_some_and(|c|c.service_generation==service.generation){return Ok(());}
    *guard=Some(new_controller(service.generation)?);Ok(())
}
pub(crate) fn reset_locked(){if let Ok(mut guard)=state().lock(){*guard=None;}}
pub(crate) fn ready(service_generation:u64,sequence:u64)->bool{
    state().lock().is_ok_and(|s|s.as_ref().is_some_and(|c|c.service_generation==service_generation&&c.accepted.iter().any(|(s,_)|*s==sequence)))
}
pub(crate) fn status_json()->String {
    let service=services::status();
    let actual_binding=state().lock().ok().and_then(|guard|guard.as_ref().and_then(|c|{
        let snapshot=c.authority.snapshot();
        (snapshot.feature_lock_active&&service.as_ref().is_ok_and(|s|s.generation==c.service_generation))
            .then(||snapshot.feature_lock_sha256.iter().map(|b|format!("{b:02x}")).collect::<String>())
    }));
    json!({"schema":"rusty.quest.breath_composition.response.v1","status":if service.is_ok(){"accepted"}else{"rejected"},
        "snapshot":{"activation_binding_sha256":actual_binding,
        "source":"controller","polar_enabled":false,"running":service.as_ref().is_ok_and(|s|s.running==1)}}).to_string()
}
pub(crate) fn invalidate_input(owner:&proof::Captured)->Result<(),String>{
    let _effect=proof::effect(owner)?;
    let generation=owner.runtime.captured_generation();proof::boundary_locked(owner,generation)?;
    proof::input_invalid_locked(owner)?;
    let result=services::invalidate(owner,generation,0,0,1);reset_locked();result
}
pub(crate) fn input_source(owner:&proof::Captured,identity:u64)->Result<(),String>{
    static SOURCE:OnceLock<Mutex<(u64,u64)>>=OnceLock::new();
    let mut source=SOURCE.get_or_init(||Mutex::new((0,0))).lock().map_err(|_|"sdk-input-source-lock-failed")?;
    if *source!=(owner.epoch,identity){invalidate_input(owner)?;*source=(owner.epoch,identity);}
    Ok(())
}
pub(crate) fn button_sample(owner:&proof::Captured,sequence:u64,elapsed:u64,pressed:bool)->Result<(),String>{
    let _effect=proof::effect(owner)?;
    if !proof::elapsed_ns().and_then(|now|now.checked_sub(elapsed)).is_some_and(|age|age<proof::EXPIRY_NS){return Err("sdk-button-observation-expired".into());}
    let mut guard=state().lock().map_err(|_|"sdk-controller-lock-failed")?;
    if guard.is_none(){*guard=Some(new_controller(owner.service_generation)?);}
    let c=guard.as_mut().filter(|c|c.service_generation==owner.service_generation).ok_or("sdk-button-owner-replaced")?;
    // Release resets only the held action. It does not discard completed calibration.
    c.calibration_button.observe(sequence,elapsed,pressed)
}
pub(crate) fn pose(owner:&proof::Captured,sequence:u64,elapsed:u64,position:[f64;3],orientation:[f64;4],active:bool,tracked:bool,right_secondary_pressed:bool)->Result<(),String>{
    let _effect=proof::effect(owner)?;
    let generation=owner.runtime.captured_generation();proof::boundary_locked(owner,generation)?;
    // Known new input invalidates admission before assessment/foreign service work.
    proof::input_invalid_locked(owner)?;
    ensure_running_locked(owner)?;
    let result=pose_locked(owner,generation,sequence,elapsed,position,orientation,active,tracked,right_secondary_pressed);
    if result.is_err(){
        // Invalid tuples never advance private ordering to u64::MAX or a fabricated clock.
        let status=services::status()?;
        let ordered=sequence>status.input_sequence&&sequence<i64::MAX as u64&&elapsed>status.input_elapsed_ns&&elapsed<i64::MAX as u64;
        let reason=if ordered{2}else{1};
        services::invalidate(owner,generation,if ordered{sequence}else{0},if ordered{elapsed}else{0},reason)?;
        if let Ok(mut guard)=state().lock(){if let Some(c)=guard.as_mut(){c.accepted.clear();}}
        if result.as_ref().err().is_some_and(|reason|reason=="sdk-pose-invalid-or-untracked"||reason=="sdk-pose-order-or-service-invalid") {reset_locked();}
        // Calibration/not-ready preserves its real collection state; known tracking/malformed loss resets it.
    }
    result
}
fn pose_locked(owner:&proof::Captured,generation:u64,sequence:u64,elapsed:u64,position:[f64;3],orientation:[f64;4],active:bool,tracked:bool,right_secondary_pressed:bool)->Result<(),String>{
    let now=proof::elapsed_ns().ok_or("sdk-clock-unavailable")?;
    if sequence==0||sequence>=i64::MAX as u64||elapsed==0||elapsed>=i64::MAX as u64||
        !now.checked_sub(elapsed).is_some_and(|age|age<proof::EXPIRY_NS)||!active||!tracked||
        position.iter().chain(orientation.iter()).any(|v|!v.is_finite())||orientation.iter().map(|v|v*v).sum::<f64>()<1e-12{return Err("sdk-pose-invalid-or-untracked".into());}
    let service=services::status()?;
    let mut guard=state().lock().map_err(|_|"sdk-controller-lock-failed")?;
    let c=guard.as_mut().ok_or("sdk-controller-not-running")?;
    if c.service_generation!=owner.service_generation||service.generation!=owner.service_generation||sequence<=c.last_sequence||elapsed<=c.last_elapsed{return Err("sdk-pose-order-or-service-invalid".into());}
    let dt=if c.last_elapsed==0{0.0}else{(elapsed-c.last_elapsed) as f32/1e9};c.last_sequence=sequence;c.last_elapsed=elapsed;
    let at=BreathTimestampMicros::new(now/1000);let sampled=BreathTimestampMicros::new(elapsed/1000);
    let breath_generation=c.authority.snapshot().generation.ok_or("sdk-composition-inactive")?;
    let _=right_secondary_pressed; // Pose coalescing is never the button-history authority.
    if c.calibration_button.take_trigger(sequence,elapsed){c.adapter.start(at,breath_generation);c.accepted.clear();}
    let assessment=c.adapter.observe(at,breath_generation,OpenXrControllerPoseInput::Frame(OpenXrControllerPoseFrame{sequence_id:sequence,sampled_at:sampled,position_m:position,orientation_xyzw:orientation,active,tracked})).assessment.ok_or("sdk-controller-assessment-unavailable")?;
    let snapshot=c.authority.observe(at,BreathCompositionSource::Controller,assessment);
    if snapshot.output.is_none(){return Err("sdk-controller-output-unavailable".into());}
    c.driver.update_frame(c.driver_settings,snapshot,at,dt);
    if !c.driver.apply_to_driver_values(&mut c.controls){return Err("sdk-controller-driver-not-applied".into());}
    let mut center=c.center;
    if let Some(profile)=owner.runtime.captured_radius_profile(){if let Some(radius)=profile.radius_m_for_progress(c.controls[0]){center[3]=radius;}}
    let effective=Effective{size:std::mem::size_of::<Effective>() as u32,abi:1,generation:owner.service_generation,
        runtime_epoch:owner.runtime_epoch,sdk_epoch:owner.epoch,session_generation:generation,sequence,elapsed_ns:elapsed,controls:c.controls,visual_scale:c.visual_scale,center};
    drop(guard); // No controller or reducer/proof lock across the foreign callback.
    if owner.runtime.captured_generation()!=generation{return Err("sdk-session-changed-during-sample".into());}
    if !proof::elapsed_ns().and_then(|now|now.checked_sub(elapsed)).is_some_and(|age|age<proof::EXPIRY_NS){return Err("sdk-sample-expired-before-apply".into());}
    let revision=services::apply(&effective)?; // Effect lease serializes this with synchronous revoke.
    if !proof::elapsed_ns().and_then(|now|now.checked_sub(elapsed)).is_some_and(|age|age<proof::EXPIRY_NS){return Err("sdk-sample-expired-during-apply".into());}
    if owner.runtime.captured_generation()!=generation{return Err("sdk-session-changed-during-apply".into());}
    let mut guard=state().lock().map_err(|_|"sdk-controller-lock-failed")?;
    let c=guard.as_mut().filter(|c|c.service_generation==owner.service_generation&&c.last_sequence==sequence).ok_or("sdk-pose-replaced-during-apply")?;
    if c.accepted.len()==128{c.accepted.pop_front();}c.accepted.push_back((sequence,revision));drop(guard);
    proof::input_valid_locked(owner,generation)?;
    record_assessment(owner,generation,assessment,revision);Ok(())
}
fn record_assessment(owner:&proof::Captured,generation:u64,a:BreathAssessmentObservation,revision:u64){
    use crate::session_recording_contract::{SessionRecord,BreathObservation};
    use crate::session_recording_clock::{SourceTimestamp,MonotonicNanos};
    let Some(now)=owner.runtime.current_elapsed_realtime_ns()else{return;};
    let phase=match a.phase{CommonBreathPhase::Inhale=>BreathPhase::Inhale,CommonBreathPhase::Exhale=>BreathPhase::Exhale,CommonBreathPhase::Hold=>BreathPhase::Hold,_=>BreathPhase::Unknown};
    let _=owner.runtime.try_offer_for_generation(generation,SessionRecord::Breath(BreathObservation{
        source_sequence:a.sequence_id,sampled_at:SourceTimestamp{clock:SourceClock::AndroidElapsedRealtime,value_ns:a.sampled_at.get().saturating_mul(1000)},
        observed_source:SourceTimestamp{clock:SourceClock::AndroidElapsedRealtime,value_ns:a.observed_at.get().saturating_mul(1000)},
        observed_at:MonotonicNanos::new(now),phase,unbiased_phase:phase,guidance_phase:None,guidance_bias_percent:0,guidance_active_time_ms:None,
        volume01:a.volume01.map(|v|v as f32),quality01:a.quality01 as f32,settings_revision:revision}));
}
pub(crate) fn record_submitted(owner:&proof::Captured){
    use crate::session_recording_contract::{SessionRecord,EffectiveRadiusSnapshotObservation};
    use crate::session_recording_clock::MonotonicNanos;
    static LAST:OnceLock<Mutex<(u64,u64,u64,u64)>>=OnceLock::new();
    let Ok(_effect)=proof::effect(owner)else{return;};let Ok(s)=services::status()else{return;};
    let generation=owner.runtime.captured_generation();
    if generation==0||s.submitted_runtime_epoch!=owner.runtime_epoch||s.submitted_sdk_epoch!=owner.epoch||s.submitted_session_generation!=generation||s.submitted_sequence==0||!ready(owner.service_generation,s.submitted_sequence){return;}
    let Some(now)=owner.runtime.current_elapsed_realtime_ns()else{return;};let Some(profile)=owner.runtime.captured_radius_profile()else{return;};
    let Ok(mut last)=LAST.get_or_init(||Mutex::new((0,0,0,0))).lock()else{return;};
    let key=(owner.runtime_epoch,owner.epoch,generation,s.submitted_sequence);
    if key==*last||s.submitted_controls.iter().any(|v|!v.is_finite()||!(0.0..=1.0).contains(v))||!s.submitted_center[3].is_finite()||s.submitted_center[3]<=0.0{return;}
    let _=owner.runtime.try_offer_for_generation(generation,SessionRecord::EffectiveRadiusSnapshot(EffectiveRadiusSnapshotObservation{
        source_frame:s.submitted_sequence,observed_at:MonotonicNanos::new(now),configured_radius_min_m:profile.configured_radius_min_m,
        configured_radius_max_m:profile.configured_radius_max_m,radius_progress01:s.submitted_controls[0],resulting_radius_m:s.submitted_center[3],
        deformation_progress01:s.submitted_controls[0],oblateness:profile.oblateness,axis_profile:profile.axis_profile,settings_revision:s.submitted,
        render_session_generation:generation,resulting_radius_parameter_source:"sdk-submit-confirmed-world-anchor"}));*last=key;
}
