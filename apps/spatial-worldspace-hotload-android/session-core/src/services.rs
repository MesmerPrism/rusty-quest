use std::sync::OnceLock;
use serde_json::Value;
#[repr(C)]
#[derive(Clone, Copy)]
pub(crate) struct Status {
    pub size: u32, pub abi: u32,
    pub generation: u64, pub requested: u64, pub submitted: u64,
    pub input_sequence: u64, pub input_elapsed_ns: u64,
    pub submitted_sequence: u64, pub submitted_elapsed_ns: u64,
    pub submitted_runtime_epoch:u64, pub submitted_sdk_epoch:u64, pub submitted_session_generation:u64,
    pub available: u32, pub running: u32, pub controller_ready: u32, pub reserved: u32,
    pub binding: [u8; 65],
    pub submitted_controls: [f32;8], pub submitted_visual_scale: f32, pub submitted_center: [f32;4],
}
impl Default for Status {
    fn default() -> Self { Self { size: std::mem::size_of::<Self>() as u32, abi: 1,
        generation: 0, requested: 0, submitted: 0, input_sequence: 0, input_elapsed_ns: 0, submitted_sequence: 0, submitted_elapsed_ns: 0, submitted_runtime_epoch:0, submitted_sdk_epoch:0, submitted_session_generation:0,
        available: 0, running: 0, controller_ready: 0, reserved: 0, binding: [0; 65], submitted_controls:[0.0;8], submitted_visual_scale:0.0, submitted_center:[0.0;4] } }
}
#[repr(C)]
#[derive(Clone, Copy)]
pub(crate) struct Effective {
    pub size: u32, pub abi: u32, pub generation: u64, pub runtime_epoch:u64, pub sdk_epoch:u64, pub session_generation:u64, pub sequence: u64, pub elapsed_ns: u64,
    pub controls: [f32; 8], pub visual_scale: f32, pub center: [f32; 4],
}
#[repr(C)]
struct Invalidation {size:u32,abi:u32,service_generation:u64,runtime_epoch:u64,sdk_epoch:u64,session_generation:u64,
    sequence:u64,elapsed_ns:u64,reason:u32,reserved:u32}
#[repr(C)]
#[derive(Clone, Copy)]
pub struct Services {
    size: u32, abi: u32,
    status: Option<unsafe extern "C" fn(*mut Status) -> i32>,
    running: Option<unsafe extern "C" fn(u64, *mut u64) -> i32>,
    reset: Option<unsafe extern "C" fn(u64, *mut u64) -> i32>,
    effective: Option<unsafe extern "C" fn(*const Effective, *mut u64) -> i32>,
    invalidate: Option<unsafe extern "C" fn(*const Invalidation) -> i32>,
}
static SERVICES: OnceLock<(usize, Services)> = OnceLock::new();
/// Installs a process-lifetime table from the explicitly selected workload.
/// Caller must provide a valid, aligned table and keep callback code loaded.
#[no_mangle]
pub unsafe extern "C" fn rq_session_core_install_services_v1(table: *const Services) -> i32 {
    if table.is_null() { return -1; }
    let value = unsafe { *table };
    if value.size as usize != std::mem::size_of::<Services>() || value.abi != 1 ||
        value.status.is_none() || value.running.is_none() || value.reset.is_none() || value.effective.is_none() || value.invalidate.is_none() { return -2; }
    if let Some((identity, _)) = SERVICES.get() { return if *identity == table as usize { 0 } else { -3 }; }
    match SERVICES.set((table as usize, value)) { Ok(()) => 0, Err(_) => -3 }
}
pub(crate) fn expected_binding() -> Result<&'static str, String> {
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256")
        .filter(|v| v.len() == 64 && v.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) && *v != "0".repeat(64))
        .ok_or_else(|| "sdk-compiled-composition-binding-unavailable".into())
}
pub(crate) fn status() -> Result<Status, String> {
    let api = &SERVICES.get().ok_or("sdk-services-not-installed")?.1;
    let mut value = Status::default();
    if unsafe { api.status.unwrap()(&mut value) } != 0 || value.size as usize != std::mem::size_of::<Status>() || value.abi != 1 ||
        value.available != 1 || value.generation == 0 || value.reserved != 0 || value.binding[64] != 0 ||
        std::str::from_utf8(&value.binding[..64]).ok() != Some(expected_binding()?) || value.submitted > value.requested {
        return Err("sdk-services-unavailable-or-binding-mismatch".into());
    }
    Ok(value)
}
pub(crate) fn ensure_running() -> Result<u64, String> {
    let before = status()?; let mut revision = 0;
    let api = &SERVICES.get().ok_or("sdk-services-not-installed")?.1;
    if unsafe { api.running.unwrap()(before.generation, &mut revision) } != 0 || revision == 0 { return Err("sdk-service-run-failed".into()); }
    let after = status()?;
    if after.generation != before.generation || after.running != 1 || after.requested < revision { return Err("sdk-service-run-unconfirmed".into()); }
    Ok(revision)
}
pub(crate) fn reset() -> Result<u64, String> {
    let before = status()?; let mut revision = 0;
    let api = &SERVICES.get().ok_or("sdk-services-not-installed")?.1;
    if unsafe { api.reset.unwrap()(before.generation, &mut revision) } != 0 || revision == 0 || revision <= before.requested { return Err("sdk-service-reset-failed".into()); }
    let after = status()?;
    if after.generation != before.generation || after.requested != revision || after.controller_ready != 0 { return Err("sdk-service-reset-unconfirmed".into()); }
    Ok(revision)
}
pub(crate) fn apply(value: &Effective) -> Result<u64, String> {
    let api = &SERVICES.get().ok_or("sdk-services-not-installed")?.1; let mut revision = 0;
    if unsafe { api.effective.unwrap()(value, &mut revision) } != 0 || revision == 0 { return Err("sdk-effective-settings-rejected".into()); }
    let after = status()?;
    if after.generation != value.generation || after.input_sequence != value.sequence || after.input_elapsed_ns != value.elapsed_ns ||
        after.controller_ready != 1 || after.requested != revision { return Err("sdk-effective-settings-unconfirmed".into()); }
    Ok(revision)
}
pub(crate) fn settings() -> Result<&'static Value, String> {
    static SETTINGS: OnceLock<Result<Value, String>> = OnceLock::new();
    SETTINGS.get_or_init(|| {
        use sha2::{Digest, Sha256};
        let bytes = include_bytes!(concat!(env!("OUT_DIR"), "/session-settings.json"));
        let digest: String = Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect();
        if option_env!("RQ_SDK_SESSION_SETTINGS_SHA256") != Some(digest.as_str()) { return Err("sdk-settings-digest-mismatch".into()); }
        let value: Value = serde_json::from_slice(bytes).map_err(|_| "sdk-settings-invalid")?;
        if value["schema"].as_str() != Some("rusty.quest.sdk_session_settings.v1") { return Err("sdk-settings-schema-invalid".into()); }
        Ok(value)
    }).as_ref().map_err(Clone::clone)
}

pub(crate) fn invalidate(owner:&crate::proof::Captured,generation:u64,sequence:u64,elapsed_ns:u64,reason:u32)->Result<(),String>{
    let api=&SERVICES.get().ok_or("sdk-services-not-installed")?.1;
    let request=Invalidation{size:std::mem::size_of::<Invalidation>() as u32,abi:1,service_generation:owner.service_generation,
        runtime_epoch:owner.runtime_epoch,sdk_epoch:owner.epoch,session_generation:generation,sequence,elapsed_ns,reason,reserved:0};
    if unsafe{api.invalidate.unwrap()(&request)}!=0{return Err("sdk-controller-invalidation-rejected".into());}
    let after=status()?;
    if after.generation!=owner.service_generation||after.controller_ready!=0{return Err("sdk-controller-invalidation-unconfirmed".into());}Ok(())
}
