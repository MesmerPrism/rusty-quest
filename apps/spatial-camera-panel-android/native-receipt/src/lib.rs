use std::ffi::CStr;
use std::ffi::CString;
use std::fs::OpenOptions;
use std::io::Write;
#[cfg(target_os = "android")]
use std::os::raw::c_int;
use std::os::raw::{c_char, c_void};
use std::ptr;
use std::time::{SystemTime, UNIX_EPOCH};

use ash::vk;
use ash::vk::Handle as VulkanHandle;
use openxr_sys::Handle;

#[cfg(target_os = "android")]
#[path = "../../../native-renderer-android/native/src/acamera_sys.rs"]
mod acamera_sys;
#[cfg(target_os = "android")]
#[path = "../../../native-renderer-android/native/src/ahardware_buffer_vulkan.rs"]
mod ahardware_buffer_vulkan;
#[cfg(target_os = "android")]
#[path = "../../../native-renderer-android/native/src/android_hardware_buffer.rs"]
mod android_hardware_buffer;
mod camera_hwb_marker;
#[cfg(target_os = "android")]
mod camera_hwb_probe;
mod camera_hwb_projection_target;
#[cfg(target_os = "android")]
mod camera_hwb_stream;
#[cfg(target_os = "android")]
mod camera_hwb_wsi;
#[cfg(target_os = "android")]
mod live_hand_joints;
#[cfg(target_os = "android")]
mod replay_hands;
#[cfg(target_os = "android")]
mod spatial_controller_actions;
#[cfg(target_os = "android")]
mod spatial_environment_depth;
#[cfg(target_os = "android")]
mod spatial_multimodal_input;
#[cfg(target_os = "android")]
mod spatial_native_passthrough;
mod spatial_public_multistack;
mod spatial_public_multistack_runtime;
#[cfg(target_os = "android")]
mod spatial_video_projection;
mod spatial_video_projection_marker;
#[cfg(target_os = "android")]
mod spatial_video_projection_native_stream;
#[cfg(target_os = "android")]
mod spatial_video_projection_probe;
mod spatial_video_projection_settings;
#[cfg(target_os = "android")]
mod surface_particle_layer;

#[cfg(target_os = "android")]
const ANDROID_LOG_INFO: c_int = 4;
const NATIVE_MARKER_FILE: &str =
    "/data/data/io.github.mesmerprism.rustyquest.spatial_camera_panel/files/spatial_camera_panel_native_markers.log";
const RECEIPT_RECEIVED: i64 = 1 << 0;
const RECEIPT_OPENXR_INSTANCE_NONZERO: i64 = 1 << 1;
const RECEIPT_OPENXR_SESSION_NONZERO: i64 = 1 << 2;
const RECEIPT_OPENXR_GET_PROC_NONZERO: i64 = 1 << 3;
const RECEIPT_PANEL_SURFACE_VALID: i64 = 1 << 4;
const RECEIPT_OPENXR_GET_PROC_CALLABLE: i64 = 1 << 5;
const RECEIPT_XR_GET_INSTANCE_PROPERTIES_RESOLVED: i64 = 1 << 6;
const RECEIPT_XR_GET_INSTANCE_PROPERTIES_SUCCEEDED: i64 = 1 << 7;
const RECEIPT_XR_GET_SYSTEM_RESOLVED: i64 = 1 << 8;
const RECEIPT_XR_GET_SYSTEM_SUCCEEDED: i64 = 1 << 9;
const RECEIPT_XR_VULKAN_REQUIREMENTS2_RESOLVED: i64 = 1 << 10;
const RECEIPT_XR_VULKAN_REQUIREMENTS2_SUCCEEDED: i64 = 1 << 11;
const RECEIPT_XR_CREATE_VULKAN_INSTANCE_RESOLVED: i64 = 1 << 12;
const RECEIPT_XR_GET_VULKAN_GRAPHICS_DEVICE2_RESOLVED: i64 = 1 << 13;
const RECEIPT_XR_CREATE_VULKAN_DEVICE_RESOLVED: i64 = 1 << 14;
const RECEIPT_VK_INSTANCE_CREATED: i64 = 1 << 15;
const RECEIPT_VK_GRAPHICS_DEVICE_OBTAINED: i64 = 1 << 16;
const RECEIPT_VK_GRAPHICS_COMPUTE_QUEUE_FOUND: i64 = 1 << 17;
const RECEIPT_VK_DEVICE_CREATED: i64 = 1 << 18;
const RECEIPT_VK_QUEUE_OBTAINED: i64 = 1 << 19;
const RECEIPT_VK_OBJECTS_DESTROYED: i64 = 1 << 20;

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRecordNoRenderInteropReceipt(
    _env: *mut c_void,
    _thiz: *mut c_void,
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
    surface_valid: u8,
) -> i64 {
    let instance_nonzero = openxr_instance_handle != 0;
    let session_nonzero = openxr_session_handle != 0;
    let get_proc_nonzero = openxr_get_instance_proc_addr_handle != 0;
    let surface_valid = surface_valid != 0;
    let openxr_probe = probe_openxr_native_capabilities(
        openxr_instance_handle,
        openxr_get_instance_proc_addr_handle,
    );

    let mut mask = RECEIPT_RECEIVED;
    if instance_nonzero {
        mask |= RECEIPT_OPENXR_INSTANCE_NONZERO;
    }
    if session_nonzero {
        mask |= RECEIPT_OPENXR_SESSION_NONZERO;
    }
    if get_proc_nonzero {
        mask |= RECEIPT_OPENXR_GET_PROC_NONZERO;
    }
    if surface_valid {
        mask |= RECEIPT_PANEL_SURFACE_VALID;
    }
    if openxr_probe.get_proc_callable {
        mask |= RECEIPT_OPENXR_GET_PROC_CALLABLE;
    }
    if openxr_probe.instance_properties_resolved {
        mask |= RECEIPT_XR_GET_INSTANCE_PROPERTIES_RESOLVED;
    }
    if openxr_probe.instance_properties_succeeded {
        mask |= RECEIPT_XR_GET_INSTANCE_PROPERTIES_SUCCEEDED;
    }
    if openxr_probe.get_system_resolved {
        mask |= RECEIPT_XR_GET_SYSTEM_RESOLVED;
    }
    if openxr_probe.get_system_succeeded {
        mask |= RECEIPT_XR_GET_SYSTEM_SUCCEEDED;
    }
    if openxr_probe.vulkan_requirements2_resolved {
        mask |= RECEIPT_XR_VULKAN_REQUIREMENTS2_RESOLVED;
    }
    if openxr_probe.vulkan_requirements2_succeeded {
        mask |= RECEIPT_XR_VULKAN_REQUIREMENTS2_SUCCEEDED;
    }
    if openxr_probe.create_vulkan_instance_resolved {
        mask |= RECEIPT_XR_CREATE_VULKAN_INSTANCE_RESOLVED;
    }
    if openxr_probe.get_vulkan_graphics_device2_resolved {
        mask |= RECEIPT_XR_GET_VULKAN_GRAPHICS_DEVICE2_RESOLVED;
    }
    if openxr_probe.create_vulkan_device_resolved {
        mask |= RECEIPT_XR_CREATE_VULKAN_DEVICE_RESOLVED;
    }
    if openxr_probe.vulkan_instance_created {
        mask |= RECEIPT_VK_INSTANCE_CREATED;
    }
    if openxr_probe.vulkan_graphics_device_obtained {
        mask |= RECEIPT_VK_GRAPHICS_DEVICE_OBTAINED;
    }
    if openxr_probe.vulkan_graphics_compute_queue_found {
        mask |= RECEIPT_VK_GRAPHICS_COMPUTE_QUEUE_FOUND;
    }
    if openxr_probe.vulkan_device_created {
        mask |= RECEIPT_VK_DEVICE_CREATED;
    }
    if openxr_probe.vulkan_queue_obtained {
        mask |= RECEIPT_VK_QUEUE_OBTAINED;
    }
    if openxr_probe.vulkan_objects_destroyed {
        mask |= RECEIPT_VK_OBJECTS_DESTROYED;
    }

    log_receipt(
        mask,
        instance_nonzero,
        session_nonzero,
        get_proc_nonzero,
        surface_valid,
        &openxr_probe,
    );
    log_vulkan_object_probe(&openxr_probe);
    mask
}

#[derive(Debug)]
struct OpenXrNativeCapabilityProbe {
    get_proc_callable: bool,
    get_proc_result: String,
    instance_properties_resolved: bool,
    instance_properties_result: String,
    instance_properties_succeeded: bool,
    runtime_name: String,
    runtime_version: String,
    get_system_resolved: bool,
    get_system_result: String,
    get_system_succeeded: bool,
    system_id_nonzero: bool,
    vulkan_requirements2_resolved: bool,
    vulkan_requirements2_result: String,
    vulkan_requirements2_succeeded: bool,
    vulkan_min_api_version: String,
    vulkan_max_api_version: String,
    create_vulkan_instance_resolved: bool,
    get_vulkan_graphics_device2_resolved: bool,
    create_vulkan_device_resolved: bool,
    vulkan_instance_created: bool,
    vulkan_create_instance_result: String,
    vulkan_graphics_device_obtained: bool,
    vulkan_graphics_device_result: String,
    vulkan_graphics_compute_queue_found: bool,
    vulkan_queue_family_index: String,
    vulkan_device_created: bool,
    vulkan_create_device_result: String,
    vulkan_queue_obtained: bool,
    vulkan_objects_destroyed: bool,
    vulkan_device_name: String,
    vulkan_device_api_version: String,
}

impl OpenXrNativeCapabilityProbe {
    fn unavailable(reason: &'static str) -> Self {
        Self {
            get_proc_callable: false,
            get_proc_result: reason.to_string(),
            instance_properties_resolved: false,
            instance_properties_result: "not-called".to_string(),
            instance_properties_succeeded: false,
            runtime_name: "unavailable".to_string(),
            runtime_version: "0.0.0".to_string(),
            get_system_resolved: false,
            get_system_result: "not-called".to_string(),
            get_system_succeeded: false,
            system_id_nonzero: false,
            vulkan_requirements2_resolved: false,
            vulkan_requirements2_result: "not-called".to_string(),
            vulkan_requirements2_succeeded: false,
            vulkan_min_api_version: "0.0.0".to_string(),
            vulkan_max_api_version: "0.0.0".to_string(),
            create_vulkan_instance_resolved: false,
            get_vulkan_graphics_device2_resolved: false,
            create_vulkan_device_resolved: false,
            vulkan_instance_created: false,
            vulkan_create_instance_result: "not-called".to_string(),
            vulkan_graphics_device_obtained: false,
            vulkan_graphics_device_result: "not-called".to_string(),
            vulkan_graphics_compute_queue_found: false,
            vulkan_queue_family_index: "none".to_string(),
            vulkan_device_created: false,
            vulkan_create_device_result: "not-called".to_string(),
            vulkan_queue_obtained: false,
            vulkan_objects_destroyed: false,
            vulkan_device_name: "unavailable".to_string(),
            vulkan_device_api_version: "0.0.0".to_string(),
        }
    }
}

struct OpenXrFunctionResolution {
    result: String,
    resolved: bool,
    function: Option<openxr_sys::pfn::VoidFunction>,
}

fn probe_openxr_native_capabilities(
    openxr_instance_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
) -> OpenXrNativeCapabilityProbe {
    if openxr_instance_handle == 0 {
        return OpenXrNativeCapabilityProbe::unavailable("missing-instance");
    }
    if openxr_get_instance_proc_addr_handle == 0 {
        return OpenXrNativeCapabilityProbe::unavailable("missing-get-instance-proc-addr");
    }

    let instance = openxr_sys::Instance::from_raw(openxr_instance_handle as u64);
    let get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr =
        unsafe { std::mem::transmute(openxr_get_instance_proc_addr_handle as usize) };

    let instance_properties =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrGetInstanceProperties");
    if !instance_properties.resolved {
        let mut probe =
            OpenXrNativeCapabilityProbe::unavailable("xrGetInstanceProperties-unresolved");
        probe.get_proc_callable = true;
        probe.get_proc_result = instance_properties.result;
        return probe;
    }
    let get_instance_properties: openxr_sys::pfn::GetInstanceProperties =
        unsafe { std::mem::transmute(instance_properties.function.expect("resolved function")) };
    let mut properties = openxr_sys::InstanceProperties::out(ptr::null_mut());
    let properties_result = unsafe { get_instance_properties(instance, properties.as_mut_ptr()) };
    let instance_properties_result = xr_result_token(properties_result);
    if properties_result != openxr_sys::Result::SUCCESS {
        let mut probe = OpenXrNativeCapabilityProbe::unavailable("xrGetInstanceProperties-failed");
        probe.get_proc_callable = true;
        probe.get_proc_result = instance_properties.result;
        probe.instance_properties_resolved = true;
        probe.instance_properties_result = instance_properties_result;
        return probe;
    }

    let properties = unsafe { properties.assume_init() };
    let mut probe = OpenXrNativeCapabilityProbe::unavailable("initialized");
    probe.get_proc_callable = true;
    probe.get_proc_result = instance_properties.result;
    probe.instance_properties_resolved = true;
    probe.instance_properties_result = instance_properties_result;
    probe.instance_properties_succeeded = true;
    probe.runtime_name = runtime_name_from_openxr(properties.runtime_name.as_ptr());
    probe.runtime_version = version_token(properties.runtime_version);

    let get_system = resolve_openxr_function(instance, get_instance_proc_addr, "xrGetSystem");
    probe.get_system_resolved = get_system.resolved;
    probe.get_system_result = get_system.result;
    if !get_system.resolved {
        return probe;
    }
    let get_system_function: openxr_sys::pfn::GetSystem =
        unsafe { std::mem::transmute(get_system.function.expect("resolved function")) };
    let get_info = openxr_sys::SystemGetInfo {
        ty: openxr_sys::SystemGetInfo::TYPE,
        next: ptr::null(),
        form_factor: openxr_sys::FormFactor::HEAD_MOUNTED_DISPLAY,
    };
    let mut system_id = openxr_sys::SystemId::NULL;
    let get_system_result = unsafe { get_system_function(instance, &get_info, &mut system_id) };
    probe.get_system_result = xr_result_token(get_system_result);
    probe.get_system_succeeded = get_system_result == openxr_sys::Result::SUCCESS;
    probe.system_id_nonzero = system_id.into_raw() != 0;
    if !probe.get_system_succeeded {
        return probe;
    }

    let requirements = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrGetVulkanGraphicsRequirements2KHR",
    );
    probe.vulkan_requirements2_resolved = requirements.resolved;
    probe.vulkan_requirements2_result = requirements.result;
    if requirements.resolved {
        let get_vulkan_graphics_requirements: openxr_sys::pfn::GetVulkanGraphicsRequirements2KHR =
            unsafe { std::mem::transmute(requirements.function.expect("resolved function")) };
        let mut graphics_requirements =
            openxr_sys::GraphicsRequirementsVulkanKHR::out(ptr::null_mut());
        let requirements_result = unsafe {
            get_vulkan_graphics_requirements(
                instance,
                system_id,
                graphics_requirements.as_mut_ptr(),
            )
        };
        probe.vulkan_requirements2_result = xr_result_token(requirements_result);
        probe.vulkan_requirements2_succeeded = requirements_result == openxr_sys::Result::SUCCESS;
        if probe.vulkan_requirements2_succeeded {
            let graphics_requirements = unsafe { graphics_requirements.assume_init() };
            probe.vulkan_min_api_version =
                version_token(graphics_requirements.min_api_version_supported);
            probe.vulkan_max_api_version =
                version_token(graphics_requirements.max_api_version_supported);
        }
    }

    let create_vulkan_instance = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrCreateVulkanInstanceKHR",
    );
    probe.create_vulkan_instance_resolved = create_vulkan_instance.resolved;
    let get_vulkan_graphics_device2 = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrGetVulkanGraphicsDevice2KHR",
    );
    probe.get_vulkan_graphics_device2_resolved = get_vulkan_graphics_device2.resolved;
    let create_vulkan_device =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrCreateVulkanDeviceKHR");
    probe.create_vulkan_device_resolved = create_vulkan_device.resolved;

    if probe.vulkan_requirements2_succeeded
        && create_vulkan_instance.resolved
        && get_vulkan_graphics_device2.resolved
        && create_vulkan_device.resolved
    {
        probe_no_present_vulkan_objects(
            &mut probe,
            instance,
            system_id,
            create_vulkan_instance.function.expect("resolved function"),
            get_vulkan_graphics_device2
                .function
                .expect("resolved function"),
            create_vulkan_device.function.expect("resolved function"),
        );
    }

    probe
}

fn probe_no_present_vulkan_objects(
    probe: &mut OpenXrNativeCapabilityProbe,
    instance: openxr_sys::Instance,
    system_id: openxr_sys::SystemId,
    create_vulkan_instance_function: openxr_sys::pfn::VoidFunction,
    get_vulkan_graphics_device2_function: openxr_sys::pfn::VoidFunction,
    create_vulkan_device_function: openxr_sys::pfn::VoidFunction,
) {
    let vk_entry = match unsafe { ash::Entry::load() } {
        Ok(entry) => entry,
        Err(error) => {
            probe.vulkan_create_instance_result = marker_token(&format!("vulkan-loader-{error}"));
            return;
        }
    };
    let vk_get_instance_proc_addr: openxr_sys::platform::VkGetInstanceProcAddr =
        unsafe { std::mem::transmute(vk_entry.static_fn().get_instance_proc_addr) };
    let create_vulkan_instance: openxr_sys::pfn::CreateVulkanInstanceKHR =
        unsafe { std::mem::transmute(create_vulkan_instance_function) };
    let get_vulkan_graphics_device2: openxr_sys::pfn::GetVulkanGraphicsDevice2KHR =
        unsafe { std::mem::transmute(get_vulkan_graphics_device2_function) };
    let create_vulkan_device: openxr_sys::pfn::CreateVulkanDeviceKHR =
        unsafe { std::mem::transmute(create_vulkan_device_function) };

    let app_name =
        CString::new("rusty-quest-spatial-camera-panel").expect("static name must not contain NUL");
    let engine_name =
        CString::new("native-receipt-probe").expect("static name must not contain NUL");
    let vk_target_version = vk::make_api_version(0, 1, 1, 0);
    let vk_app_info = vk::ApplicationInfo::default()
        .application_name(&app_name)
        .application_version(1)
        .engine_name(&engine_name)
        .engine_version(1)
        .api_version(vk_target_version);
    let vk_instance_info = vk::InstanceCreateInfo::default().application_info(&vk_app_info);
    let xr_instance_info = openxr_sys::VulkanInstanceCreateInfoKHR {
        ty: openxr_sys::VulkanInstanceCreateInfoKHR::TYPE,
        next: ptr::null(),
        system_id,
        create_flags: Default::default(),
        pfn_get_instance_proc_addr: Some(vk_get_instance_proc_addr),
        vulkan_create_info: &vk_instance_info as *const _ as *const _,
        vulkan_allocator: ptr::null(),
    };
    let mut raw_vk_instance: openxr_sys::platform::VkInstance = ptr::null();
    let mut raw_vk_instance_result: openxr_sys::platform::VkResult = 0;
    let xr_instance_result = unsafe {
        create_vulkan_instance(
            instance,
            &xr_instance_info,
            &mut raw_vk_instance,
            &mut raw_vk_instance_result,
        )
    };
    probe.vulkan_create_instance_result = format!(
        "xr_{}_vk_{}",
        xr_result_token(xr_instance_result),
        vk_result_token(raw_vk_instance_result)
    );
    if xr_instance_result != openxr_sys::Result::SUCCESS
        || raw_vk_instance_result != vk::Result::SUCCESS.as_raw()
        || raw_vk_instance.is_null()
    {
        return;
    }
    probe.vulkan_instance_created = true;

    let vk_instance_handle = vk::Instance::from_raw(raw_vk_instance as usize as u64);
    let vk_instance = unsafe { ash::Instance::load(vk_entry.static_fn(), vk_instance_handle) };
    let graphics_device_info = openxr_sys::VulkanGraphicsDeviceGetInfoKHR {
        ty: openxr_sys::VulkanGraphicsDeviceGetInfoKHR::TYPE,
        next: ptr::null(),
        system_id,
        vulkan_instance: raw_vk_instance,
    };
    let mut raw_vk_physical_device: openxr_sys::platform::VkPhysicalDevice = ptr::null();
    let graphics_device_result = unsafe {
        get_vulkan_graphics_device2(instance, &graphics_device_info, &mut raw_vk_physical_device)
    };
    probe.vulkan_graphics_device_result = xr_result_token(graphics_device_result);
    if graphics_device_result != openxr_sys::Result::SUCCESS || raw_vk_physical_device.is_null() {
        unsafe {
            vk_instance.destroy_instance(None);
        }
        probe.vulkan_objects_destroyed = true;
        return;
    }
    probe.vulkan_graphics_device_obtained = true;

    let vk_physical_device = vk::PhysicalDevice::from_raw(raw_vk_physical_device as usize as u64);
    let device_properties =
        unsafe { vk_instance.get_physical_device_properties(vk_physical_device) };
    probe.vulkan_device_name = marker_token(&runtime_name_from_openxr(
        device_properties.device_name.as_ptr(),
    ));
    probe.vulkan_device_api_version = vk_api_version_token(device_properties.api_version);

    let queue_family_properties =
        unsafe { vk_instance.get_physical_device_queue_family_properties(vk_physical_device) };
    let Some(queue_family_index) =
        queue_family_properties
            .iter()
            .enumerate()
            .find_map(|(index, info)| {
                info.queue_flags
                    .contains(vk::QueueFlags::GRAPHICS | vk::QueueFlags::COMPUTE)
                    .then_some(index as u32)
            })
    else {
        unsafe {
            vk_instance.destroy_instance(None);
        }
        probe.vulkan_objects_destroyed = true;
        return;
    };
    probe.vulkan_graphics_compute_queue_found = true;
    probe.vulkan_queue_family_index = queue_family_index.to_string();

    let queue_priorities = [1.0_f32];
    let queue_infos = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family_index)
        .queue_priorities(&queue_priorities)];
    let vk_device_info = vk::DeviceCreateInfo::default().queue_create_infos(&queue_infos);
    let xr_device_info = openxr_sys::VulkanDeviceCreateInfoKHR {
        ty: openxr_sys::VulkanDeviceCreateInfoKHR::TYPE,
        next: ptr::null(),
        system_id,
        create_flags: Default::default(),
        pfn_get_instance_proc_addr: Some(vk_get_instance_proc_addr),
        vulkan_physical_device: raw_vk_physical_device,
        vulkan_create_info: &vk_device_info as *const _ as *const _,
        vulkan_allocator: ptr::null(),
    };
    let mut raw_vk_device: openxr_sys::platform::VkDevice = ptr::null();
    let mut raw_vk_device_result: openxr_sys::platform::VkResult = 0;
    let xr_device_result = unsafe {
        create_vulkan_device(
            instance,
            &xr_device_info,
            &mut raw_vk_device,
            &mut raw_vk_device_result,
        )
    };
    probe.vulkan_create_device_result = format!(
        "xr_{}_vk_{}",
        xr_result_token(xr_device_result),
        vk_result_token(raw_vk_device_result)
    );
    if xr_device_result != openxr_sys::Result::SUCCESS
        || raw_vk_device_result != vk::Result::SUCCESS.as_raw()
        || raw_vk_device.is_null()
    {
        unsafe {
            vk_instance.destroy_instance(None);
        }
        probe.vulkan_objects_destroyed = true;
        return;
    }
    probe.vulkan_device_created = true;

    let vk_device_handle = vk::Device::from_raw(raw_vk_device as usize as u64);
    let vk_device = unsafe { ash::Device::load(vk_instance.fp_v1_0(), vk_device_handle) };
    let vk_queue = unsafe { vk_device.get_device_queue(queue_family_index, 0) };
    probe.vulkan_queue_obtained = vk_queue != vk::Queue::null();

    unsafe {
        vk_device.destroy_device(None);
        vk_instance.destroy_instance(None);
    }
    probe.vulkan_objects_destroyed = true;
}

fn resolve_openxr_function(
    instance: openxr_sys::Instance,
    get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr,
    name: &str,
) -> OpenXrFunctionResolution {
    let function_name = CString::new(name).expect("static OpenXR symbol must not contain NUL");
    let mut function: Option<openxr_sys::pfn::VoidFunction> = None;
    let result = unsafe { get_instance_proc_addr(instance, function_name.as_ptr(), &mut function) };
    OpenXrFunctionResolution {
        result: xr_result_token(result),
        resolved: result == openxr_sys::Result::SUCCESS && function.is_some(),
        function,
    }
}

fn xr_result_token(result: openxr_sys::Result) -> String {
    if result == openxr_sys::Result::SUCCESS {
        "success".to_string()
    } else {
        format!("code_{}", result.into_raw())
    }
}

fn vk_result_token(result: openxr_sys::platform::VkResult) -> String {
    if result == vk::Result::SUCCESS.as_raw() {
        "success".to_string()
    } else {
        format!("code_{result}")
    }
}

fn version_token(version: openxr_sys::Version) -> String {
    format!(
        "{}.{}.{}",
        version.major(),
        version.minor(),
        version.patch()
    )
}

fn vk_api_version_token(version: u32) -> String {
    format!(
        "{}.{}.{}",
        vk::api_version_major(version),
        vk::api_version_minor(version),
        vk::api_version_patch(version)
    )
}

fn runtime_name_from_openxr(runtime_name: *const c_char) -> String {
    if runtime_name.is_null() {
        return "unavailable".to_string();
    }
    unsafe { CStr::from_ptr(runtime_name) }
        .to_string_lossy()
        .trim()
        .replace(' ', "_")
}

pub(crate) fn marker_token(value: &str) -> String {
    let token: String = value
        .trim()
        .chars()
        .map(|value| {
            if value.is_ascii_alphanumeric() || matches!(value, '.' | '_' | '-') {
                value
            } else {
                '_'
            }
        })
        .collect();
    if token.is_empty() {
        "none".to_string()
    } else {
        token.chars().take(96).collect()
    }
}

fn log_receipt(
    mask: i64,
    instance_nonzero: bool,
    session_nonzero: bool,
    get_proc_nonzero: bool,
    surface_valid: bool,
    openxr_probe: &OpenXrNativeCapabilityProbe,
) {
    let message = format!(
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-interop-receipt status=received renderPolicy=no-render nativeReceiptMask={} openXrInstanceHandleNonZero={} openXrSessionHandleNonZero={} openXrGetInstanceProcAddrHandleNonZero={} surfaceValid={} openXrGetInstanceProcAddrCallable={} xrGetInstancePropertiesResolved={} xrGetInstancePropertiesSucceeded={} xrGetInstanceProcAddrResult={} xrGetInstancePropertiesResult={} xrRuntimeName={} xrRuntimeVersion={} xrGetSystemResolved={} xrGetSystemSucceeded={} xrSystemIdNonZero={} xrGetSystemResult={} xrGetVulkanGraphicsRequirements2Resolved={} xrGetVulkanGraphicsRequirements2Succeeded={} xrGetVulkanGraphicsRequirements2Result={} xrVulkanMinApiVersion={} xrVulkanMaxApiVersion={} xrCreateVulkanInstanceResolved={} xrGetVulkanGraphicsDevice2Resolved={} xrCreateVulkanDeviceResolved={} vkInstanceCreated={} vkCreateInstanceResult={} vkGraphicsDeviceObtained={} vkGraphicsDeviceResult={} vkGraphicsComputeQueueFound={} vkQueueFamilyIndex={} vkDeviceCreated={} vkCreateDeviceResult={} vkQueueObtained={} vkObjectsDestroyed={} vkDeviceName={} vkDeviceApiVersion={}",
        mask,
        bool_token(instance_nonzero),
        bool_token(session_nonzero),
        bool_token(get_proc_nonzero),
        bool_token(surface_valid),
        bool_token(openxr_probe.get_proc_callable),
        bool_token(openxr_probe.instance_properties_resolved),
        bool_token(openxr_probe.instance_properties_succeeded),
        openxr_probe.get_proc_result,
        openxr_probe.instance_properties_result,
        openxr_probe.runtime_name,
        openxr_probe.runtime_version,
        bool_token(openxr_probe.get_system_resolved),
        bool_token(openxr_probe.get_system_succeeded),
        bool_token(openxr_probe.system_id_nonzero),
        openxr_probe.get_system_result,
        bool_token(openxr_probe.vulkan_requirements2_resolved),
        bool_token(openxr_probe.vulkan_requirements2_succeeded),
        openxr_probe.vulkan_requirements2_result,
        openxr_probe.vulkan_min_api_version,
        openxr_probe.vulkan_max_api_version,
        bool_token(openxr_probe.create_vulkan_instance_resolved),
        bool_token(openxr_probe.get_vulkan_graphics_device2_resolved),
        bool_token(openxr_probe.create_vulkan_device_resolved),
        bool_token(openxr_probe.vulkan_instance_created),
        openxr_probe.vulkan_create_instance_result,
        bool_token(openxr_probe.vulkan_graphics_device_obtained),
        openxr_probe.vulkan_graphics_device_result,
        bool_token(openxr_probe.vulkan_graphics_compute_queue_found),
        openxr_probe.vulkan_queue_family_index,
        bool_token(openxr_probe.vulkan_device_created),
        openxr_probe.vulkan_create_device_result,
        bool_token(openxr_probe.vulkan_queue_obtained),
        bool_token(openxr_probe.vulkan_objects_destroyed),
        openxr_probe.vulkan_device_name,
        openxr_probe.vulkan_device_api_version,
    );
    android_log_info("RQSpatialCameraPanelNative", &message);
}

fn log_vulkan_object_probe(openxr_probe: &OpenXrNativeCapabilityProbe) {
    let message = format!(
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=native-vulkan-object-probe status=observed renderPolicy=no-render vkInstanceCreated={} vkCreateInstanceResult={} vkGraphicsDeviceObtained={} vkGraphicsDeviceResult={} vkGraphicsComputeQueueFound={} vkQueueFamilyIndex={} vkDeviceCreated={} vkCreateDeviceResult={} vkQueueObtained={} vkObjectsDestroyed={} vkDeviceName={} vkDeviceApiVersion={}",
        bool_token(openxr_probe.vulkan_instance_created),
        openxr_probe.vulkan_create_instance_result,
        bool_token(openxr_probe.vulkan_graphics_device_obtained),
        openxr_probe.vulkan_graphics_device_result,
        bool_token(openxr_probe.vulkan_graphics_compute_queue_found),
        openxr_probe.vulkan_queue_family_index,
        bool_token(openxr_probe.vulkan_device_created),
        openxr_probe.vulkan_create_device_result,
        bool_token(openxr_probe.vulkan_queue_obtained),
        bool_token(openxr_probe.vulkan_objects_destroyed),
        openxr_probe.vulkan_device_name,
        openxr_probe.vulkan_device_api_version,
    );
    android_log_info("RQSpatialCameraPanelNative", &message);
}

pub(crate) fn bool_token(value: bool) -> &'static str {
    if value {
        "true"
    } else {
        "false"
    }
}

#[cfg(target_os = "android")]
pub(crate) fn android_log_info(tag: &str, message: &str) {
    let tag = CString::new(tag).expect("static Android log tag must not contain NUL");
    let format = CString::new("%s").expect("static Android log format must not contain NUL");
    let message = CString::new(message).expect("native receipt marker must not contain NUL");
    unsafe {
        __android_log_print(
            ANDROID_LOG_INFO,
            tag.as_ptr(),
            format.as_ptr(),
            message.as_ptr(),
        );
    }
    append_native_marker_file(message.to_string_lossy().as_ref());
}

#[cfg(not(target_os = "android"))]
pub(crate) fn android_log_info(_tag: &str, message: &str) {
    append_native_marker_file(message);
}

fn append_native_marker_file(message: &str) {
    let timestamp_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0);
    if let Ok(mut file) = OpenOptions::new()
        .create(true)
        .append(true)
        .open(NATIVE_MARKER_FILE)
    {
        let _ = writeln!(file, "{} {}", timestamp_ms, message);
    }
}
