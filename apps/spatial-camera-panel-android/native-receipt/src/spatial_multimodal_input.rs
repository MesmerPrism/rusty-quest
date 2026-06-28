use std::ffi::CString;
use std::mem;
use std::os::raw::c_void;
use std::ptr;

use openxr_sys as xr;
use openxr_sys::Handle;

const REQUEST_RECEIVED: i64 = 1 << 0;
const REQUEST_INSTANCE_NONZERO: i64 = 1 << 1;
const REQUEST_SESSION_NONZERO: i64 = 1 << 2;
const REQUEST_GET_PROC_NONZERO: i64 = 1 << 3;
const REQUEST_GET_SYSTEM_RESOLVED: i64 = 1 << 4;
const REQUEST_GET_SYSTEM_SUCCEEDED: i64 = 1 << 5;
const REQUEST_GET_SYSTEM_PROPERTIES_RESOLVED: i64 = 1 << 6;
const REQUEST_GET_SYSTEM_PROPERTIES_SUCCEEDED: i64 = 1 << 7;
const REQUEST_SYSTEM_SUPPORTS_MULTIMODAL: i64 = 1 << 8;
const REQUEST_RESUME_FUNCTION_RESOLVED: i64 = 1 << 9;
const REQUEST_RESUME_SUCCEEDED: i64 = 1 << 10;

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeRequestSpatialMultimodalInput(
    _env: *mut c_void,
    _thiz: *mut c_void,
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
) -> i64 {
    let mut mask = REQUEST_RECEIVED;
    if openxr_instance_handle != 0 {
        mask |= REQUEST_INSTANCE_NONZERO;
    }
    if openxr_session_handle != 0 {
        mask |= REQUEST_SESSION_NONZERO;
    }
    if openxr_get_instance_proc_addr_handle != 0 {
        mask |= REQUEST_GET_PROC_NONZERO;
    }

    let result = request_multimodal_input(
        openxr_instance_handle,
        openxr_session_handle,
        openxr_get_instance_proc_addr_handle,
        &mut mask,
    );
    marker(format!(
        "status={} spatialMultimodalInputRequest=true requestMask={} extension=XR_META_simultaneous_hands_and_controllers detachedControllerExtension=XR_META_detached_controllers supportsSimultaneousHandsAndControllers={} resumeFunctionResolved={} resumeResult={}",
        result.status,
        mask,
        crate::bool_token((mask & REQUEST_SYSTEM_SUPPORTS_MULTIMODAL) != 0),
        crate::bool_token((mask & REQUEST_RESUME_FUNCTION_RESOLVED) != 0),
        result.resume_result,
    ));
    mask
}

struct MultimodalInputRequestResult {
    status: &'static str,
    resume_result: String,
}

fn request_multimodal_input(
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
    mask: &mut i64,
) -> MultimodalInputRequestResult {
    if openxr_instance_handle == 0
        || openxr_session_handle == 0
        || openxr_get_instance_proc_addr_handle == 0
    {
        return MultimodalInputRequestResult {
            status: "missing-openxr-handles",
            resume_result: "not-called".to_string(),
        };
    }

    let instance = xr::Instance::from_raw(openxr_instance_handle as u64);
    let session = xr::Session::from_raw(openxr_session_handle as u64);
    let get_instance_proc_addr: xr::pfn::GetInstanceProcAddr =
        unsafe { mem::transmute(openxr_get_instance_proc_addr_handle as usize) };

    let get_system = resolve_openxr_function(instance, get_instance_proc_addr, "xrGetSystem");
    if !get_system.resolved {
        return MultimodalInputRequestResult {
            status: "xrGetSystem-unresolved",
            resume_result: get_system.result,
        };
    }
    *mask |= REQUEST_GET_SYSTEM_RESOLVED;
    let get_system_function: xr::pfn::GetSystem =
        unsafe { mem::transmute(get_system.function.expect("resolved function")) };
    let get_info = xr::SystemGetInfo {
        ty: xr::SystemGetInfo::TYPE,
        next: ptr::null(),
        form_factor: xr::FormFactor::HEAD_MOUNTED_DISPLAY,
    };
    let mut system_id = xr::SystemId::NULL;
    let get_system_result = unsafe { get_system_function(instance, &get_info, &mut system_id) };
    if get_system_result != xr::Result::SUCCESS {
        return MultimodalInputRequestResult {
            status: "xrGetSystem-failed",
            resume_result: xr_result_token(get_system_result),
        };
    }
    *mask |= REQUEST_GET_SYSTEM_SUCCEEDED;

    let get_system_properties =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrGetSystemProperties");
    if !get_system_properties.resolved {
        return MultimodalInputRequestResult {
            status: "xrGetSystemProperties-unresolved",
            resume_result: get_system_properties.result,
        };
    }
    *mask |= REQUEST_GET_SYSTEM_PROPERTIES_RESOLVED;
    let get_system_properties_function: xr::pfn::GetSystemProperties =
        unsafe { mem::transmute(get_system_properties.function.expect("resolved function")) };
    let mut multimodal_properties = xr::SystemSimultaneousHandsAndControllersPropertiesMETA {
        ty: xr::SystemSimultaneousHandsAndControllersPropertiesMETA::TYPE,
        next: ptr::null_mut(),
        supports_simultaneous_hands_and_controllers: xr::FALSE,
    };
    let mut system_properties = xr::SystemProperties::out(
        (&mut multimodal_properties
            as *mut xr::SystemSimultaneousHandsAndControllersPropertiesMETA)
            .cast::<xr::BaseOutStructure>(),
    );
    let properties_result = unsafe {
        get_system_properties_function(instance, system_id, system_properties.as_mut_ptr())
    };
    if properties_result != xr::Result::SUCCESS {
        return MultimodalInputRequestResult {
            status: "xrGetSystemProperties-failed",
            resume_result: xr_result_token(properties_result),
        };
    }
    *mask |= REQUEST_GET_SYSTEM_PROPERTIES_SUCCEEDED;
    if multimodal_properties.supports_simultaneous_hands_and_controllers != xr::TRUE {
        return MultimodalInputRequestResult {
            status: "unsupported-by-system",
            resume_result: "not-called".to_string(),
        };
    }
    *mask |= REQUEST_SYSTEM_SUPPORTS_MULTIMODAL;

    let resume = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrResumeSimultaneousHandsAndControllersTrackingMETA",
    );
    if !resume.resolved {
        return MultimodalInputRequestResult {
            status: "resume-function-unresolved",
            resume_result: resume.result,
        };
    }
    *mask |= REQUEST_RESUME_FUNCTION_RESOLVED;
    let resume_function: xr::pfn::ResumeSimultaneousHandsAndControllersTrackingMETA =
        unsafe { mem::transmute(resume.function.expect("resolved function")) };
    let resume_info = xr::SimultaneousHandsAndControllersTrackingResumeInfoMETA {
        ty: xr::SimultaneousHandsAndControllersTrackingResumeInfoMETA::TYPE,
        next: ptr::null(),
    };
    let resume_result = unsafe { resume_function(session, &resume_info) };
    if resume_result == xr::Result::SUCCESS {
        *mask |= REQUEST_RESUME_SUCCEEDED;
    }
    MultimodalInputRequestResult {
        status: if resume_result == xr::Result::SUCCESS {
            "resumed"
        } else {
            "resume-failed"
        },
        resume_result: xr_result_token(resume_result),
    }
}

struct ResolvedOpenXrFunction {
    resolved: bool,
    result: String,
    function: Option<xr::pfn::VoidFunction>,
}

fn resolve_openxr_function(
    instance: xr::Instance,
    get_instance_proc_addr: xr::pfn::GetInstanceProcAddr,
    name: &str,
) -> ResolvedOpenXrFunction {
    let function_name = CString::new(name).expect("static OpenXR symbol must not contain NUL");
    let mut function: Option<xr::pfn::VoidFunction> = None;
    let result = unsafe { get_instance_proc_addr(instance, function_name.as_ptr(), &mut function) };
    ResolvedOpenXrFunction {
        result: xr_result_token(result),
        resolved: result == xr::Result::SUCCESS && function.is_some(),
        function,
    }
}

fn xr_result_token(result: xr::Result) -> String {
    if result == xr::Result::SUCCESS {
        "success".to_string()
    } else {
        format!("code_{}", result.into_raw())
    }
}

fn marker(message: String) {
    crate::android_log_info(
        "RQSpatialMultimodalInput",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=spatial-multimodal-input {message}"
        ),
    );
}
