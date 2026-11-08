#![allow(dead_code, non_snake_case)]
use std::ffi::{c_char, c_void, CStr, CString};
use std::ptr;
use std::sync::Mutex;

// Deliberately modeled OpenXR ABI types. Only the actual query/mask/JNI bodies
// use these leaves; no Vulkan loader, SDK instance or Android JNI is present.
mod openxr_sys {
    use super::*;
    #[repr(transparent)]
    #[derive(Clone, Copy)]
    pub struct Instance(pub u64);
    impl Instance {
        pub fn from_raw(v: u64) -> Self {
            Self(v)
        }
    }
    #[repr(transparent)]
    #[derive(Clone, Copy)]
    pub struct SystemId(pub u64);
    impl SystemId {
        pub const NULL: Self = Self(0);
        pub fn into_raw(self) -> u64 {
            self.0
        }
    }
    #[repr(transparent)]
    #[derive(Clone, Copy, PartialEq, Eq)]
    pub struct Result(pub i32);
    impl Result {
        pub const SUCCESS: Self = Self(0);
        pub fn into_raw(self) -> i32 {
            self.0
        }
    }
    #[repr(transparent)]
    #[derive(Clone, Copy)]
    pub struct Version(pub u64);
    impl Version {
        pub fn major(self) -> u64 {
            1
        }
        pub fn minor(self) -> u64 {
            2
        }
        pub fn patch(self) -> u64 {
            0
        }
    }
    #[repr(C)]
    pub struct InstanceProperties {
        pub runtime_name: [c_char; 128],
        pub runtime_version: Version,
    }
    impl InstanceProperties {
        pub fn out(_: *mut c_void) -> std::mem::MaybeUninit<Self> {
            std::mem::MaybeUninit::uninit()
        }
    }
    #[repr(transparent)]
    pub struct FormFactor(pub i32);
    impl FormFactor {
        pub const HEAD_MOUNTED_DISPLAY: Self = Self(1);
    }
    #[repr(C)]
    pub struct SystemGetInfo {
        pub ty: i32,
        pub next: *const c_void,
        pub form_factor: FormFactor,
    }
    impl SystemGetInfo {
        pub const TYPE: i32 = 4;
    }
    #[repr(C)]
    pub struct GraphicsRequirementsVulkanKHR {
        pub min_api_version_supported: Version,
        pub max_api_version_supported: Version,
    }
    impl GraphicsRequirementsVulkanKHR {
        pub fn out(_: *mut c_void) -> std::mem::MaybeUninit<Self> {
            std::mem::MaybeUninit::uninit()
        }
    }
    pub mod pfn {
        use super::*;
        pub type VoidFunction = unsafe extern "system" fn();
        pub type GetInstanceProcAddr =
            unsafe extern "system" fn(Instance, *const c_char, *mut Option<VoidFunction>) -> Result;
        pub type GetInstanceProperties =
            unsafe extern "system" fn(Instance, *mut InstanceProperties) -> Result;
        pub type GetSystem =
            unsafe extern "system" fn(Instance, *const SystemGetInfo, *mut SystemId) -> Result;
        pub type GetVulkanGraphicsRequirements2KHR = unsafe extern "system" fn(
            Instance,
            SystemId,
            *mut GraphicsRequirementsVulkanKHR,
        ) -> Result;
    }
}

#[derive(Default)]
struct State {
    resolved: Vec<String>,
    calls: Vec<&'static str>,
    logs: Vec<String>,
    construction_entries: usize,
    failed_query: u8,
    missing_constructor: bool,
    construction_fails: bool,
}
static STATE: Mutex<State> = Mutex::new(State {
    resolved: Vec::new(),
    calls: Vec::new(),
    logs: Vec::new(),
    construction_entries: 0,
    failed_query: 0,
    missing_constructor: false,
    construction_fails: false,
});
fn android_log_info(_: &str, message: &str) {
    STATE.lock().unwrap().logs.push(message.to_owned());
}
unsafe extern "system" fn properties(
    _: openxr_sys::Instance,
    out: *mut openxr_sys::InstanceProperties,
) -> openxr_sys::Result {
    let mut s = STATE.lock().unwrap();
    s.calls.push("properties");
    if s.failed_query == 1 {
        return openxr_sys::Result(-1);
    }
    let mut name = [0; 128];
    for (i, b) in b"modeled-runtime".iter().enumerate() {
        name[i] = *b as c_char;
    }
    out.write(openxr_sys::InstanceProperties {
        runtime_name: name,
        runtime_version: openxr_sys::Version(1),
    });
    openxr_sys::Result::SUCCESS
}
unsafe extern "system" fn system(
    _: openxr_sys::Instance,
    _: *const openxr_sys::SystemGetInfo,
    out: *mut openxr_sys::SystemId,
) -> openxr_sys::Result {
    let mut s = STATE.lock().unwrap();
    s.calls.push("system");
    if s.failed_query == 2 {
        return openxr_sys::Result(-2);
    }
    out.write(openxr_sys::SystemId(42));
    openxr_sys::Result::SUCCESS
}
unsafe extern "system" fn requirements(
    _: openxr_sys::Instance,
    _: openxr_sys::SystemId,
    out: *mut openxr_sys::GraphicsRequirementsVulkanKHR,
) -> openxr_sys::Result {
    let mut s = STATE.lock().unwrap();
    s.calls.push("requirements");
    if s.failed_query == 3 {
        return openxr_sys::Result(-3);
    }
    out.write(openxr_sys::GraphicsRequirementsVulkanKHR {
        min_api_version_supported: openxr_sys::Version(1),
        max_api_version_supported: openxr_sys::Version(1),
    });
    openxr_sys::Result::SUCCESS
}
unsafe extern "system" fn create_instance() {
    STATE.lock().unwrap().calls.push("create-instance");
}
unsafe extern "system" fn graphics_device() {
    STATE.lock().unwrap().calls.push("graphics-device");
}
unsafe extern "system" fn create_device() {
    STATE.lock().unwrap().calls.push("create-device");
}
unsafe extern "system" fn gipa(
    _: openxr_sys::Instance,
    name: *const c_char,
    out: *mut Option<openxr_sys::pfn::VoidFunction>,
) -> openxr_sys::Result {
    let name = CStr::from_ptr(name).to_str().unwrap();
    let mut s = STATE.lock().unwrap();
    s.resolved.push(name.to_owned());
    let p: openxr_sys::pfn::VoidFunction = match name {
        "xrGetInstanceProperties" => {
            std::mem::transmute(properties as openxr_sys::pfn::GetInstanceProperties)
        }
        "xrGetSystem" => std::mem::transmute(system as openxr_sys::pfn::GetSystem),
        "xrGetVulkanGraphicsRequirements2KHR" => {
            std::mem::transmute(requirements as openxr_sys::pfn::GetVulkanGraphicsRequirements2KHR)
        }
        "xrCreateVulkanInstanceKHR" if s.missing_constructor => {
            out.write(None);
            return openxr_sys::Result(-4);
        }
        "xrCreateVulkanInstanceKHR" => create_instance,
        "xrGetVulkanGraphicsDevice2KHR" => graphics_device,
        "xrCreateVulkanDeviceKHR" => create_device,
        _ => panic!("unexpected XR symbol"),
    };
    out.write(Some(p));
    openxr_sys::Result::SUCCESS
}

// Modeled allocation leaf: actual query/gate supplies actual resolved PFNs.
// These signatures intentionally model only VoidFunction invocation counters.
fn probe_no_present_vulkan_objects(
    p: &mut OpenXrNativeCapabilityProbe,
    _: openxr_sys::Instance,
    _: openxr_sys::SystemId,
    a: openxr_sys::pfn::VoidFunction,
    b: openxr_sys::pfn::VoidFunction,
    c: openxr_sys::pfn::VoidFunction,
) {
    {
        let mut s = STATE.lock().unwrap();
        s.construction_entries += 1;
        if s.construction_fails {
            p.vulkan_create_instance_result = "modeled-construction-failure".into();
            return;
        }
    }
    unsafe {
        a();
        b();
        c();
    }
    p.vulkan_instance_created = true;
    p.vulkan_graphics_device_obtained = true;
    p.vulkan_graphics_compute_queue_found = true;
    p.vulkan_device_created = true;
    p.vulkan_queue_obtained = true;
    p.vulkan_objects_destroyed = true;
}

// PRODUCTION_BODIES

// Host type checks exercise the actual exports' argument/return shapes.
const _:extern "system" fn(*mut c_void,*mut c_void,i64,i64,i64,u8)->i64=Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordNoRenderInteropReceipt;
const _:extern "system" fn(*mut c_void,*mut c_void,i64,i64,i64,u8,u8)->i64=Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordSelectedNoRenderInteropReceipt;

#[cfg(test)]
mod tests {
    use super::*;
    fn reset() {
        *STATE.lock().unwrap() = State::default();
    }
    fn selected(surface: bool, allow: bool) -> i64 {
        Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordSelectedNoRenderInteropReceipt(ptr::null_mut(),ptr::null_mut(),1,2,gipa as usize as i64,surface as u8,allow as u8)
    }
    fn marker(token: &str) {
        let s = STATE.lock().unwrap();
        assert_eq!(s.logs.len(), 2);
        assert!(
            s.logs.iter().all(|v| v.contains(token)),
            "missing {token}: {:?}",
            s.logs
        );
    }
    #[test]
    fn borrowed_only_retains_queries_resolves_constructors_but_invokes_none() {
        reset();
        assert_eq!(selected(false, false), 32751);
        let s = STATE.lock().unwrap();
        assert_eq!(s.calls, ["properties", "system", "requirements"]);
        assert_eq!(s.construction_entries, 0);
        for name in [
            "xrCreateVulkanInstanceKHR",
            "xrGetVulkanGraphicsDevice2KHR",
            "xrCreateVulkanDeviceKHR",
        ] {
            assert!(s.resolved.iter().any(|v| v == name));
        }
        drop(s);
        marker("receiptEntry=selected");
        marker("allowVulkanObjectConstruction=false");
        marker("vulkanObjectConstructionAttempted=false");
        marker("vulkanObjectConstructionSkipped=true");
        marker("vkObjectsDestroyed=false");
    }
    #[test]
    fn actual_surface_flag_changes_only_surface_bit() {
        reset();
        let a = selected(false, false);
        reset();
        let b = selected(true, false);
        assert_eq!(a ^ b, RECEIPT_PANEL_SURFACE_VALID);
        assert_eq!(b, 32767);
    }
    #[test]
    fn selected_full_retains_legacy_mask_and_constructor_calls() {
        reset();
        assert_eq!(selected(true, true), 2097151);
        assert_eq!(STATE.lock().unwrap().construction_entries, 1);
        marker("receiptEntry=selected");
        marker("allowVulkanObjectConstruction=true");
        marker("vulkanObjectConstructionAttempted=true");
        marker("vulkanObjectConstructionSkipped=false");
    }
    #[test]
    fn legacy_export_remains_full() {
        reset();
        let mask=Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordNoRenderInteropReceipt(ptr::null_mut(),ptr::null_mut(),1,2,gipa as usize as i64,1);
        assert_eq!(mask, 2097151);
        assert_eq!(STATE.lock().unwrap().construction_entries, 1);
        marker("receiptEntry=legacy");
        marker("allowVulkanObjectConstruction=true");
    }
    #[test]
    fn legacy_surface_absent_retains_original_mask() {
        reset();
        let mask=Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordNoRenderInteropReceipt(ptr::null_mut(),ptr::null_mut(),1,2,gipa as usize as i64,0);
        assert_eq!(mask, 2097135);
    }
    #[test]
    fn failed_properties_are_not_success_or_construction() {
        reset();
        STATE.lock().unwrap().failed_query = 1;
        let mask = selected(false, true);
        assert_ne!(mask & RECEIPT_XR_GET_INSTANCE_PROPERTIES_RESOLVED, 0);
        assert_eq!(
            mask & (RECEIPT_XR_GET_INSTANCE_PROPERTIES_SUCCEEDED | RECEIPT_VK_DEVICE_CREATED),
            0
        );
        assert_eq!(STATE.lock().unwrap().calls, ["properties"]);
        marker("vulkanObjectConstructionAttempted=false");
        marker("vulkanObjectConstructionSkipped=false");
    }
    #[test]
    fn failed_system_stops_queries_without_fake_success() {
        reset();
        STATE.lock().unwrap().failed_query = 2;
        let mask = selected(false, false);
        assert_ne!(mask & RECEIPT_XR_GET_SYSTEM_RESOLVED, 0);
        assert_eq!(mask & RECEIPT_XR_GET_SYSTEM_SUCCEEDED, 0);
        assert_eq!(STATE.lock().unwrap().calls, ["properties", "system"]);
    }
    #[test]
    fn failed_requirements_still_resolves_constructor_capabilities() {
        reset();
        STATE.lock().unwrap().failed_query = 3;
        let mask = selected(false, true);
        assert_eq!(mask & RECEIPT_XR_VULKAN_REQUIREMENTS2_SUCCEEDED, 0);
        assert_ne!(mask & RECEIPT_XR_CREATE_VULKAN_DEVICE_RESOLVED, 0);
        assert_eq!(STATE.lock().unwrap().construction_entries, 0);
    }
    #[test]
    fn unavailable_constructor_refuses_allocation() {
        reset();
        STATE.lock().unwrap().missing_constructor = true;
        let mask = selected(false, true);
        assert_eq!(mask & RECEIPT_XR_CREATE_VULKAN_INSTANCE_RESOLVED, 0);
        assert_ne!(mask & RECEIPT_XR_CREATE_VULKAN_DEVICE_RESOLVED, 0);
        assert_eq!(STATE.lock().unwrap().construction_entries, 0);
    }
    #[test]
    fn construction_failure_is_attempted_not_skipped_or_destroyed() {
        reset();
        STATE.lock().unwrap().construction_fails = true;
        let mask = selected(false, true);
        assert_eq!(mask, 32751);
        assert_eq!(STATE.lock().unwrap().construction_entries, 1);
        marker("vulkanObjectConstructionAttempted=true");
        marker("vulkanObjectConstructionSkipped=false");
        marker("vkObjectsDestroyed=false");
    }
    #[test]
    fn absent_instance_keeps_handle_presence_truth_without_queries() {
        reset();
        let mask=Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordSelectedNoRenderInteropReceipt(ptr::null_mut(),ptr::null_mut(),0,2,gipa as usize as i64,0,0);
        assert_eq!(
            mask,
            RECEIPT_RECEIVED | RECEIPT_OPENXR_SESSION_NONZERO | RECEIPT_OPENXR_GET_PROC_NONZERO
        );
        assert!(STATE.lock().unwrap().calls.is_empty());
    }
    #[test]
    fn absent_gipa_does_not_claim_callability() {
        reset();
        let mask=Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordSelectedNoRenderInteropReceipt(ptr::null_mut(),ptr::null_mut(),1,0,0,0,0);
        assert_eq!(mask, RECEIPT_RECEIVED | RECEIPT_OPENXR_INSTANCE_NONZERO);
        assert!(STATE.lock().unwrap().resolved.is_empty());
    }
}
