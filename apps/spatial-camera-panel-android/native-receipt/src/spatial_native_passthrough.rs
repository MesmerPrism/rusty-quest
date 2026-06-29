use std::ffi::CString;
use std::mem;
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use openxr_sys::Handle;

use crate::{android_log_info, bool_token, marker_token};

const RECEIPT_RECEIVED: i64 = 1 << 0;
const RECEIPT_HANDLES_NONZERO: i64 = 1 << 1;
const RECEIPT_CREATE_PASSTHROUGH_RESOLVED: i64 = 1 << 2;
const RECEIPT_DESTROY_PASSTHROUGH_RESOLVED: i64 = 1 << 3;
const RECEIPT_CREATE_LAYER_RESOLVED: i64 = 1 << 4;
const RECEIPT_DESTROY_LAYER_RESOLVED: i64 = 1 << 5;
const RECEIPT_LAYER_RESUME_RESOLVED: i64 = 1 << 6;
const RECEIPT_CREATE_PASSTHROUGH_SUCCEEDED: i64 = 1 << 7;
const RECEIPT_CREATE_LAYER_SUCCEEDED: i64 = 1 << 8;
const RECEIPT_LAYER_RESUME_SUCCEEDED: i64 = 1 << 9;
const RECEIPT_LAYER_ACTIVE: i64 = 1 << 10;
const RECEIPT_ALREADY_ACTIVE: i64 = 1 << 11;

static SPATIAL_NATIVE_PASSTHROUGH_ACTIVE: AtomicBool = AtomicBool::new(false);
static SPATIAL_NATIVE_PASSTHROUGH: Mutex<Option<SpatialNativePassthroughRuntime>> =
    Mutex::new(None);

struct SpatialNativePassthroughRuntime {
    session_raw: u64,
    passthrough: openxr_sys::PassthroughFB,
    layer: openxr_sys::PassthroughLayerFB,
    destroy_passthrough: openxr_sys::pfn::DestroyPassthroughFB,
    destroy_layer: openxr_sys::pfn::DestroyPassthroughLayerFB,
}

pub(crate) fn spatial_native_passthrough_marker_fields() -> String {
    format!(
        "nativePassthroughRequested=true nativePassthroughLayerActive={} nativePassthroughActivationPath=spatial-native-receipt-xr-fb-passthrough nativePassthroughCompositionLayerSubmission=spatial-sdk-owned-end-frame",
        bool_token(SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.load(Ordering::Relaxed))
    )
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSpatialNativePassthrough(
    _env: *mut std::ffi::c_void,
    _thiz: *mut std::ffi::c_void,
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
) -> i64 {
    let mask = unsafe {
        start_spatial_native_passthrough(
            openxr_instance_handle,
            openxr_session_handle,
            openxr_get_instance_proc_addr_handle,
        )
    };
    mask
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStopSpatialNativePassthrough(
    _env: *mut std::ffi::c_void,
    _thiz: *mut std::ffi::c_void,
) -> i64 {
    stop_spatial_native_passthrough("jni-stop")
}

unsafe fn start_spatial_native_passthrough(
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
) -> i64 {
    let mut mask = RECEIPT_RECEIVED;
    if openxr_instance_handle == 0
        || openxr_session_handle == 0
        || openxr_get_instance_proc_addr_handle == 0
    {
        SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
        log_spatial_native_passthrough(format!(
            "status=unavailable reason=missing-openxr-handles nativePassthroughRequested=true nativePassthroughLayerActive=false openXrInstanceHandleNonZero={} openXrSessionHandleNonZero={} openXrGetInstanceProcAddrHandleNonZero={}",
            bool_token(openxr_instance_handle != 0),
            bool_token(openxr_session_handle != 0),
            bool_token(openxr_get_instance_proc_addr_handle != 0),
        ));
        return mask;
    }
    mask |= RECEIPT_HANDLES_NONZERO;

    let session_raw = openxr_session_handle as u64;
    {
        let guard = match SPATIAL_NATIVE_PASSTHROUGH.lock() {
            Ok(guard) => guard,
            Err(_) => {
                SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
                log_spatial_native_passthrough(
                    "status=error stage=lock reason=poisoned nativePassthroughRequested=true nativePassthroughLayerActive=false".to_string(),
                );
                return mask;
            }
        };
        if guard
            .as_ref()
            .map(|runtime| runtime.session_raw == session_raw)
            .unwrap_or(false)
        {
            SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(true, Ordering::Relaxed);
            let active_mask = mask | RECEIPT_LAYER_ACTIVE | RECEIPT_ALREADY_ACTIVE;
            log_spatial_native_passthrough(format!(
                "status=already-active nativePassthroughRequested=true nativePassthroughLayerActive=true passthroughExtension=XR_FB_passthrough passthroughPurpose=RECONSTRUCTION nativeReceiptMask={} spatialSdkOwnsEndFrame=true",
                active_mask
            ));
            return active_mask;
        }
    }

    let _ = stop_spatial_native_passthrough("replace-session");

    let instance = openxr_sys::Instance::from_raw(openxr_instance_handle as u64);
    let session = openxr_sys::Session::from_raw(session_raw);
    let get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr =
        mem::transmute(openxr_get_instance_proc_addr_handle as usize);

    let create_passthrough_resolution =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrCreatePassthroughFB");
    let destroy_passthrough_resolution =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrDestroyPassthroughFB");
    let create_layer_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrCreatePassthroughLayerFB",
    );
    let destroy_layer_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrDestroyPassthroughLayerFB",
    );
    let resume_layer_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrPassthroughLayerResumeFB",
    );

    if create_passthrough_resolution.resolved {
        mask |= RECEIPT_CREATE_PASSTHROUGH_RESOLVED;
    }
    if destroy_passthrough_resolution.resolved {
        mask |= RECEIPT_DESTROY_PASSTHROUGH_RESOLVED;
    }
    if create_layer_resolution.resolved {
        mask |= RECEIPT_CREATE_LAYER_RESOLVED;
    }
    if destroy_layer_resolution.resolved {
        mask |= RECEIPT_DESTROY_LAYER_RESOLVED;
    }
    if resume_layer_resolution.resolved {
        mask |= RECEIPT_LAYER_RESUME_RESOLVED;
    }

    if !create_passthrough_resolution.resolved
        || !destroy_passthrough_resolution.resolved
        || !create_layer_resolution.resolved
        || !destroy_layer_resolution.resolved
        || !resume_layer_resolution.resolved
    {
        SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
        log_spatial_native_passthrough(format!(
            "status=unavailable reason=required-openxr-functions-unavailable nativePassthroughRequested=true nativePassthroughLayerActive=false xrCreatePassthroughFBResolved={} xrCreatePassthroughFBResult={} xrCreatePassthroughLayerFBResolved={} xrCreatePassthroughLayerFBResult={} xrPassthroughLayerResumeFBResolved={} xrPassthroughLayerResumeFBResult={}",
            bool_token(create_passthrough_resolution.resolved),
            create_passthrough_resolution.result,
            bool_token(create_layer_resolution.resolved),
            create_layer_resolution.result,
            bool_token(resume_layer_resolution.resolved),
            resume_layer_resolution.result,
        ));
        return mask;
    }

    let create_passthrough: openxr_sys::pfn::CreatePassthroughFB =
        mem::transmute(create_passthrough_resolution.function.unwrap());
    let destroy_passthrough: openxr_sys::pfn::DestroyPassthroughFB =
        mem::transmute(destroy_passthrough_resolution.function.unwrap());
    let create_layer: openxr_sys::pfn::CreatePassthroughLayerFB =
        mem::transmute(create_layer_resolution.function.unwrap());
    let destroy_layer: openxr_sys::pfn::DestroyPassthroughLayerFB =
        mem::transmute(destroy_layer_resolution.function.unwrap());
    let resume_layer: openxr_sys::pfn::PassthroughLayerResumeFB =
        mem::transmute(resume_layer_resolution.function.unwrap());

    let create_info = openxr_sys::PassthroughCreateInfoFB {
        ty: openxr_sys::PassthroughCreateInfoFB::TYPE,
        next: ptr::null(),
        flags: openxr_sys::PassthroughFlagsFB::IS_RUNNING_AT_CREATION,
    };
    let mut passthrough = openxr_sys::PassthroughFB::NULL;
    let create_result = create_passthrough(session, &create_info, &mut passthrough);
    if create_result != openxr_sys::Result::SUCCESS
        || passthrough == openxr_sys::PassthroughFB::NULL
    {
        SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
        log_spatial_native_passthrough(format!(
            "status=error stage=create-passthrough reason={} nativePassthroughRequested=true nativePassthroughLayerActive=false",
            xr_result_token(create_result),
        ));
        return mask;
    }
    mask |= RECEIPT_CREATE_PASSTHROUGH_SUCCEEDED;

    let layer_create_info = openxr_sys::PassthroughLayerCreateInfoFB {
        ty: openxr_sys::PassthroughLayerCreateInfoFB::TYPE,
        next: ptr::null(),
        passthrough,
        flags: openxr_sys::PassthroughFlagsFB::EMPTY,
        purpose: openxr_sys::PassthroughLayerPurposeFB::RECONSTRUCTION,
    };
    let mut layer = openxr_sys::PassthroughLayerFB::NULL;
    let create_layer_result = create_layer(session, &layer_create_info, &mut layer);
    if create_layer_result != openxr_sys::Result::SUCCESS
        || layer == openxr_sys::PassthroughLayerFB::NULL
    {
        let destroy_result = destroy_passthrough(passthrough);
        SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
        log_spatial_native_passthrough(format!(
            "status=error stage=create-layer reason={} nativePassthroughRequested=true nativePassthroughLayerActive=false destroyPassthroughResult={}",
            xr_result_token(create_layer_result),
            xr_result_token(destroy_result),
        ));
        return mask;
    }
    mask |= RECEIPT_CREATE_LAYER_SUCCEEDED;

    let resume_result = resume_layer(layer);
    if resume_result != openxr_sys::Result::SUCCESS {
        let destroy_layer_result = destroy_layer(layer);
        let destroy_passthrough_result = destroy_passthrough(passthrough);
        SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
        log_spatial_native_passthrough(format!(
            "status=error stage=resume-layer reason={} nativePassthroughRequested=true nativePassthroughLayerActive=false destroyLayerResult={} destroyPassthroughResult={}",
            xr_result_token(resume_result),
            xr_result_token(destroy_layer_result),
            xr_result_token(destroy_passthrough_result),
        ));
        return mask;
    }
    mask |= RECEIPT_LAYER_RESUME_SUCCEEDED | RECEIPT_LAYER_ACTIVE;

    let mut guard = match SPATIAL_NATIVE_PASSTHROUGH.lock() {
        Ok(guard) => guard,
        Err(_) => {
            let destroy_layer_result = destroy_layer(layer);
            let destroy_passthrough_result = destroy_passthrough(passthrough);
            SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
            log_spatial_native_passthrough(format!(
                "status=error stage=lock-after-create reason=poisoned nativePassthroughRequested=true nativePassthroughLayerActive=false destroyLayerResult={} destroyPassthroughResult={}",
                xr_result_token(destroy_layer_result),
                xr_result_token(destroy_passthrough_result),
            ));
            return mask & !RECEIPT_LAYER_ACTIVE;
        }
    };
    *guard = Some(SpatialNativePassthroughRuntime {
        session_raw,
        passthrough,
        layer,
        destroy_passthrough,
        destroy_layer,
    });
    SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(true, Ordering::Relaxed);
    log_spatial_native_passthrough(format!(
        "status=active nativePassthroughRequested=true nativePassthroughLayerActive=true passthroughExtension=XR_FB_passthrough passthroughPurpose=RECONSTRUCTION passthroughCreateFlags=IS_RUNNING_AT_CREATION passthroughLayerFlags=EMPTY passthroughCompositionLayer=CompositionLayerPassthroughFB passthroughCompositionLayerSubmission=spatial-sdk-owned-end-frame nativeReceiptMask={} spatialSdkOwnsEndFrame=true environmentDepthPassthroughPrerequisite=active",
        mask
    ));
    mask
}

fn stop_spatial_native_passthrough(reason: &str) -> i64 {
    let mut guard = match SPATIAL_NATIVE_PASSTHROUGH.lock() {
        Ok(guard) => guard,
        Err(_) => {
            SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
            log_spatial_native_passthrough(format!(
                "status=error stage=stop-lock reason=poisoned stopReason={} nativePassthroughLayerActive=false",
                marker_token(reason)
            ));
            return 0;
        }
    };
    let runtime = guard.take();
    if let Some(runtime) = runtime {
        let destroy_layer_result = unsafe { (runtime.destroy_layer)(runtime.layer) };
        let destroy_passthrough_result =
            unsafe { (runtime.destroy_passthrough)(runtime.passthrough) };
        SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
        log_spatial_native_passthrough(format!(
            "status=stopped stopReason={} nativePassthroughLayerActive=false destroyLayerResult={} destroyPassthroughResult={}",
            marker_token(reason),
            xr_result_token(destroy_layer_result),
            xr_result_token(destroy_passthrough_result),
        ));
        1
    } else {
        SPATIAL_NATIVE_PASSTHROUGH_ACTIVE.store(false, Ordering::Relaxed);
        log_spatial_native_passthrough(format!(
            "status=already-stopped stopReason={} nativePassthroughLayerActive=false",
            marker_token(reason)
        ));
        0
    }
}

struct OpenXrFunctionResolution {
    result: String,
    resolved: bool,
    function: Option<openxr_sys::pfn::VoidFunction>,
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

fn log_spatial_native_passthrough(detail: String) {
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=spatial-native-passthrough {detail}"
        ),
    );
}
