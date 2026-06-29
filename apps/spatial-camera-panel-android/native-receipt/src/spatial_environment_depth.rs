use std::ffi::CString;
use std::mem;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use openxr_sys::Handle;

use crate::{android_log_info, bool_token, marker_token};

const RECEIPT_RECEIVED: i64 = 1 << 0;
const RECEIPT_HANDLES_NONZERO: i64 = 1 << 1;
const RECEIPT_GET_SYSTEM_RESOLVED: i64 = 1 << 2;
const RECEIPT_GET_SYSTEM_PROPERTIES_RESOLVED: i64 = 1 << 3;
const RECEIPT_CREATE_PROVIDER_RESOLVED: i64 = 1 << 4;
const RECEIPT_CREATE_SWAPCHAIN_RESOLVED: i64 = 1 << 5;
const RECEIPT_GET_SWAPCHAIN_STATE_RESOLVED: i64 = 1 << 6;
const RECEIPT_ENUMERATE_IMAGES_RESOLVED: i64 = 1 << 7;
const RECEIPT_START_PROVIDER_RESOLVED: i64 = 1 << 8;
const RECEIPT_ACQUIRE_IMAGE_RESOLVED: i64 = 1 << 9;
const RECEIPT_STOP_PROVIDER_RESOLVED: i64 = 1 << 10;
const RECEIPT_DESTROY_PROVIDER_RESOLVED: i64 = 1 << 11;
const RECEIPT_DESTROY_SWAPCHAIN_RESOLVED: i64 = 1 << 12;
const RECEIPT_CREATE_REFERENCE_SPACE_RESOLVED: i64 = 1 << 13;
const RECEIPT_DESTROY_SPACE_RESOLVED: i64 = 1 << 14;
const RECEIPT_GET_SYSTEM_SUCCEEDED: i64 = 1 << 15;
const RECEIPT_PROPERTIES_SUPPORTED: i64 = 1 << 16;
const RECEIPT_PROVIDER_CREATED: i64 = 1 << 17;
const RECEIPT_SWAPCHAIN_CREATED: i64 = 1 << 18;
const RECEIPT_SWAPCHAIN_STATE_OBTAINED: i64 = 1 << 19;
const RECEIPT_IMAGES_ENUMERATED: i64 = 1 << 20;
const RECEIPT_REFERENCE_SPACE_CREATED: i64 = 1 << 21;
const RECEIPT_PROVIDER_STARTED: i64 = 1 << 22;
const RECEIPT_ACQUIRE_THREAD_STARTED: i64 = 1 << 23;
const RECEIPT_ALREADY_ACTIVE: i64 = 1 << 24;

static SPATIAL_ENVIRONMENT_DEPTH_REQUESTED: AtomicBool = AtomicBool::new(false);
static SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_BOUND: AtomicBool = AtomicBool::new(false);
static SPATIAL_ENVIRONMENT_DEPTH_VALID_DATA: AtomicBool = AtomicBool::new(false);
static SPATIAL_ENVIRONMENT_DEPTH_VALID_SAMPLE_COUNT: AtomicU64 = AtomicU64::new(0);
static SPATIAL_ENVIRONMENT_DEPTH_TOTAL_ACQUIRED: AtomicU64 = AtomicU64::new(0);
static SPATIAL_ENVIRONMENT_DEPTH_LAST_SWAPCHAIN_INDEX: AtomicU32 = AtomicU32::new(u32::MAX);
static SPATIAL_ENVIRONMENT_DEPTH_WIDTH: AtomicU32 = AtomicU32::new(0);
static SPATIAL_ENVIRONMENT_DEPTH_HEIGHT: AtomicU32 = AtomicU32::new(0);
static SPATIAL_ENVIRONMENT_DEPTH_NEAR_Z_BITS: AtomicU32 = AtomicU32::new(0.001f32.to_bits());
static SPATIAL_ENVIRONMENT_DEPTH_FAR_Z_BITS: AtomicU32 = AtomicU32::new(4.0f32.to_bits());
static SPATIAL_ENVIRONMENT_DEPTH_RUNTIME: Mutex<Option<SpatialEnvironmentDepthRuntime>> =
    Mutex::new(None);

#[derive(Clone, Debug)]
pub(crate) struct SpatialEnvironmentDepthFrameSnapshot {
    pub(crate) image_handles: Vec<u64>,
    pub(crate) swapchain_index: u32,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) near_z: f32,
    pub(crate) far_z: f32,
    pub(crate) acquired_frame_count: u64,
}

struct SpatialEnvironmentDepthRuntime {
    session_raw: u64,
    provider: openxr_sys::EnvironmentDepthProviderMETA,
    swapchain: openxr_sys::EnvironmentDepthSwapchainMETA,
    reference_space: openxr_sys::Space,
    image_handles: Vec<u64>,
    stop_provider: openxr_sys::pfn::StopEnvironmentDepthProviderMETA,
    destroy_provider: openxr_sys::pfn::DestroyEnvironmentDepthProviderMETA,
    destroy_swapchain: openxr_sys::pfn::DestroyEnvironmentDepthSwapchainMETA,
    destroy_space: openxr_sys::pfn::DestroySpace,
    stop_requested: Arc<AtomicBool>,
    acquire_thread: Option<JoinHandle<()>>,
}

pub(crate) fn spatial_environment_depth_frame_snapshot(
) -> Option<SpatialEnvironmentDepthFrameSnapshot> {
    if !SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_BOUND.load(Ordering::Acquire)
        || !SPATIAL_ENVIRONMENT_DEPTH_VALID_DATA.load(Ordering::Acquire)
    {
        return None;
    }
    let swapchain_index = SPATIAL_ENVIRONMENT_DEPTH_LAST_SWAPCHAIN_INDEX.load(Ordering::Acquire);
    if swapchain_index == u32::MAX {
        return None;
    }
    let acquired_frame_count = SPATIAL_ENVIRONMENT_DEPTH_TOTAL_ACQUIRED.load(Ordering::Acquire);
    if acquired_frame_count == 0 {
        return None;
    }
    let image_handles = {
        let guard = SPATIAL_ENVIRONMENT_DEPTH_RUNTIME.lock().ok()?;
        guard.as_ref()?.image_handles.clone()
    };
    if image_handles
        .get(swapchain_index as usize)
        .copied()
        .unwrap_or(0)
        == 0
    {
        return None;
    }
    Some(SpatialEnvironmentDepthFrameSnapshot {
        image_handles,
        swapchain_index,
        width: SPATIAL_ENVIRONMENT_DEPTH_WIDTH.load(Ordering::Acquire),
        height: SPATIAL_ENVIRONMENT_DEPTH_HEIGHT.load(Ordering::Acquire),
        near_z: f32::from_bits(SPATIAL_ENVIRONMENT_DEPTH_NEAR_Z_BITS.load(Ordering::Acquire)),
        far_z: f32::from_bits(SPATIAL_ENVIRONMENT_DEPTH_FAR_Z_BITS.load(Ordering::Acquire)),
        acquired_frame_count,
    })
}

pub(crate) fn spatial_environment_depth_marker_fields() -> String {
    let provider_bound = SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_BOUND.load(Ordering::Relaxed);
    let valid_data = SPATIAL_ENVIRONMENT_DEPTH_VALID_DATA.load(Ordering::Relaxed);
    let valid_sample_count = SPATIAL_ENVIRONMENT_DEPTH_VALID_SAMPLE_COUNT.load(Ordering::Relaxed);
    let total_acquired = SPATIAL_ENVIRONMENT_DEPTH_TOTAL_ACQUIRED.load(Ordering::Relaxed);
    let source = if provider_bound {
        "xr-meta-environment-depth"
    } else {
        "spatial-fallback-depth-descriptor"
    };
    let provider_state = if provider_bound {
        "provider-running"
    } else {
        "not-bound"
    };
    let acquire_status = if total_acquired > 0 {
        "acquired"
    } else if provider_bound {
        "waiting-for-first-acquire"
    } else {
        "not-attempted-provider-not-bound"
    };
    format!(
        "publicMultiStackDepthSource={} publicMultiStackDepthProviderRequested={} publicMultiStackDepthRealProviderBound={} publicMultiStackDepthValidData={} publicMultiStackDepthPermissionSurface=horizonos.permission.USE_SCENE+USE_SCENE_DATA environmentDepthSource={} environmentDepthProviderState={} environmentDepthProviderAvailable={} environmentDepthRealProviderBound={} environmentDepthAcquireStatus={} environmentDepthValidData={} environmentDepthDebugValidSampleCount={} environmentDepthAcquiredFrameCount={}",
        source,
        bool_token(SPATIAL_ENVIRONMENT_DEPTH_REQUESTED.load(Ordering::Relaxed)),
        bool_token(provider_bound),
        bool_token(valid_data),
        source,
        provider_state,
        bool_token(provider_bound),
        bool_token(provider_bound),
        acquire_status,
        bool_token(valid_data),
        valid_sample_count,
        total_acquired,
    )
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSpatialEnvironmentDepthProbe(
    _env: *mut std::ffi::c_void,
    _thiz: *mut std::ffi::c_void,
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
) -> i64 {
    unsafe {
        start_spatial_environment_depth_probe(
            openxr_instance_handle,
            openxr_session_handle,
            openxr_get_instance_proc_addr_handle,
        )
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStopSpatialEnvironmentDepthProbe(
    _env: *mut std::ffi::c_void,
    _thiz: *mut std::ffi::c_void,
) -> i64 {
    stop_spatial_environment_depth_probe("jni-stop")
}

unsafe fn start_spatial_environment_depth_probe(
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
) -> i64 {
    SPATIAL_ENVIRONMENT_DEPTH_REQUESTED.store(true, Ordering::Relaxed);
    let mut mask = RECEIPT_RECEIVED;
    if openxr_instance_handle == 0
        || openxr_session_handle == 0
        || openxr_get_instance_proc_addr_handle == 0
    {
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=unavailable reason=missing-openxr-handles environmentDepthProviderRequested=true environmentDepthRealProviderBound=false openXrInstanceHandleNonZero={} openXrSessionHandleNonZero={} openXrGetInstanceProcAddrHandleNonZero={}",
            bool_token(openxr_instance_handle != 0),
            bool_token(openxr_session_handle != 0),
            bool_token(openxr_get_instance_proc_addr_handle != 0),
        ));
        return mask;
    }
    mask |= RECEIPT_HANDLES_NONZERO;

    let session_raw = openxr_session_handle as u64;
    {
        let guard = match SPATIAL_ENVIRONMENT_DEPTH_RUNTIME.lock() {
            Ok(guard) => guard,
            Err(_) => {
                clear_depth_state(false);
                log_spatial_environment_depth(
                    "status=error stage=lock reason=poisoned environmentDepthProviderRequested=true environmentDepthRealProviderBound=false".to_string(),
                );
                return mask;
            }
        };
        if guard
            .as_ref()
            .map(|runtime| runtime.session_raw == session_raw)
            .unwrap_or(false)
        {
            SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_BOUND.store(true, Ordering::Relaxed);
            let active_mask = mask
                | RECEIPT_PROVIDER_CREATED
                | RECEIPT_SWAPCHAIN_CREATED
                | RECEIPT_REFERENCE_SPACE_CREATED
                | RECEIPT_PROVIDER_STARTED
                | RECEIPT_ACQUIRE_THREAD_STARTED
                | RECEIPT_ALREADY_ACTIVE;
            log_spatial_environment_depth(format!(
                "status=already-active environmentDepthProviderRequested=true environmentDepthRealProviderBound=true environmentDepthProviderState=provider-running environmentDepthAcquireStatus={} nativeReceiptMask={}",
                acquire_status_token(),
                active_mask,
            ));
            return active_mask;
        }
    }

    let _ = stop_spatial_environment_depth_probe("replace-session");

    let instance = openxr_sys::Instance::from_raw(openxr_instance_handle as u64);
    let session = openxr_sys::Session::from_raw(session_raw);
    let get_instance_proc_addr: openxr_sys::pfn::GetInstanceProcAddr =
        mem::transmute(openxr_get_instance_proc_addr_handle as usize);

    let get_system_resolution =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrGetSystem");
    let get_system_properties_resolution =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrGetSystemProperties");
    let create_provider_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrCreateEnvironmentDepthProviderMETA",
    );
    let create_swapchain_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrCreateEnvironmentDepthSwapchainMETA",
    );
    let get_swapchain_state_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrGetEnvironmentDepthSwapchainStateMETA",
    );
    let enumerate_images_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrEnumerateEnvironmentDepthSwapchainImagesMETA",
    );
    let start_provider_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrStartEnvironmentDepthProviderMETA",
    );
    let acquire_image_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrAcquireEnvironmentDepthImageMETA",
    );
    let stop_provider_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrStopEnvironmentDepthProviderMETA",
    );
    let destroy_provider_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrDestroyEnvironmentDepthProviderMETA",
    );
    let destroy_swapchain_resolution = resolve_openxr_function(
        instance,
        get_instance_proc_addr,
        "xrDestroyEnvironmentDepthSwapchainMETA",
    );
    let create_reference_space_resolution =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrCreateReferenceSpace");
    let destroy_space_resolution =
        resolve_openxr_function(instance, get_instance_proc_addr, "xrDestroySpace");

    if get_system_resolution.resolved {
        mask |= RECEIPT_GET_SYSTEM_RESOLVED;
    }
    if get_system_properties_resolution.resolved {
        mask |= RECEIPT_GET_SYSTEM_PROPERTIES_RESOLVED;
    }
    if create_provider_resolution.resolved {
        mask |= RECEIPT_CREATE_PROVIDER_RESOLVED;
    }
    if create_swapchain_resolution.resolved {
        mask |= RECEIPT_CREATE_SWAPCHAIN_RESOLVED;
    }
    if get_swapchain_state_resolution.resolved {
        mask |= RECEIPT_GET_SWAPCHAIN_STATE_RESOLVED;
    }
    if enumerate_images_resolution.resolved {
        mask |= RECEIPT_ENUMERATE_IMAGES_RESOLVED;
    }
    if start_provider_resolution.resolved {
        mask |= RECEIPT_START_PROVIDER_RESOLVED;
    }
    if acquire_image_resolution.resolved {
        mask |= RECEIPT_ACQUIRE_IMAGE_RESOLVED;
    }
    if stop_provider_resolution.resolved {
        mask |= RECEIPT_STOP_PROVIDER_RESOLVED;
    }
    if destroy_provider_resolution.resolved {
        mask |= RECEIPT_DESTROY_PROVIDER_RESOLVED;
    }
    if destroy_swapchain_resolution.resolved {
        mask |= RECEIPT_DESTROY_SWAPCHAIN_RESOLVED;
    }
    if create_reference_space_resolution.resolved {
        mask |= RECEIPT_CREATE_REFERENCE_SPACE_RESOLVED;
    }
    if destroy_space_resolution.resolved {
        mask |= RECEIPT_DESTROY_SPACE_RESOLVED;
    }

    let all_required = get_system_resolution.resolved
        && get_system_properties_resolution.resolved
        && create_provider_resolution.resolved
        && create_swapchain_resolution.resolved
        && get_swapchain_state_resolution.resolved
        && enumerate_images_resolution.resolved
        && start_provider_resolution.resolved
        && acquire_image_resolution.resolved
        && stop_provider_resolution.resolved
        && destroy_provider_resolution.resolved
        && destroy_swapchain_resolution.resolved
        && create_reference_space_resolution.resolved
        && destroy_space_resolution.resolved;
    if !all_required {
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=unavailable reason=required-openxr-functions-unavailable environmentDepthProviderRequested=true environmentDepthRealProviderBound=false xrCreateEnvironmentDepthProviderMETAResolved={} xrCreateEnvironmentDepthProviderMETAResult={} xrAcquireEnvironmentDepthImageMETAResolved={} xrAcquireEnvironmentDepthImageMETAResult={} xrCreateReferenceSpaceResolved={} xrCreateReferenceSpaceResult={} nativeReceiptMask={}",
            bool_token(create_provider_resolution.resolved),
            create_provider_resolution.result,
            bool_token(acquire_image_resolution.resolved),
            acquire_image_resolution.result,
            bool_token(create_reference_space_resolution.resolved),
            create_reference_space_resolution.result,
            mask,
        ));
        return mask;
    }

    let get_system: openxr_sys::pfn::GetSystem =
        mem::transmute(get_system_resolution.function.unwrap());
    let get_system_properties: openxr_sys::pfn::GetSystemProperties =
        mem::transmute(get_system_properties_resolution.function.unwrap());
    let create_provider: openxr_sys::pfn::CreateEnvironmentDepthProviderMETA =
        mem::transmute(create_provider_resolution.function.unwrap());
    let create_swapchain: openxr_sys::pfn::CreateEnvironmentDepthSwapchainMETA =
        mem::transmute(create_swapchain_resolution.function.unwrap());
    let get_swapchain_state: openxr_sys::pfn::GetEnvironmentDepthSwapchainStateMETA =
        mem::transmute(get_swapchain_state_resolution.function.unwrap());
    let enumerate_images: openxr_sys::pfn::EnumerateEnvironmentDepthSwapchainImagesMETA =
        mem::transmute(enumerate_images_resolution.function.unwrap());
    let start_provider: openxr_sys::pfn::StartEnvironmentDepthProviderMETA =
        mem::transmute(start_provider_resolution.function.unwrap());
    let acquire_image: openxr_sys::pfn::AcquireEnvironmentDepthImageMETA =
        mem::transmute(acquire_image_resolution.function.unwrap());
    let stop_provider: openxr_sys::pfn::StopEnvironmentDepthProviderMETA =
        mem::transmute(stop_provider_resolution.function.unwrap());
    let destroy_provider: openxr_sys::pfn::DestroyEnvironmentDepthProviderMETA =
        mem::transmute(destroy_provider_resolution.function.unwrap());
    let destroy_swapchain: openxr_sys::pfn::DestroyEnvironmentDepthSwapchainMETA =
        mem::transmute(destroy_swapchain_resolution.function.unwrap());
    let create_reference_space: openxr_sys::pfn::CreateReferenceSpace =
        mem::transmute(create_reference_space_resolution.function.unwrap());
    let destroy_space: openxr_sys::pfn::DestroySpace =
        mem::transmute(destroy_space_resolution.function.unwrap());

    let get_info = openxr_sys::SystemGetInfo {
        ty: openxr_sys::SystemGetInfo::TYPE,
        next: ptr::null(),
        form_factor: openxr_sys::FormFactor::HEAD_MOUNTED_DISPLAY,
    };
    let mut system_id = openxr_sys::SystemId::NULL;
    let get_system_result = get_system(instance, &get_info, &mut system_id);
    if get_system_result != openxr_sys::Result::SUCCESS {
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=unavailable reason=xrGetSystem-failed result={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
            xr_result_token(get_system_result),
            mask,
        ));
        return mask;
    }
    mask |= RECEIPT_GET_SYSTEM_SUCCEEDED;

    let mut depth_properties = openxr_sys::SystemEnvironmentDepthPropertiesMETA {
        ty: openxr_sys::SystemEnvironmentDepthPropertiesMETA::TYPE,
        next: ptr::null_mut(),
        supports_environment_depth: false.into(),
        supports_hand_removal: false.into(),
    };
    let mut system_properties =
        openxr_sys::SystemProperties::out(&mut depth_properties as *mut _ as *mut _);
    let properties_result =
        get_system_properties(instance, system_id, system_properties.as_mut_ptr());
    if properties_result != openxr_sys::Result::SUCCESS {
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=unavailable reason=xrGetSystemProperties-failed result={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
            xr_result_token(properties_result),
            mask,
        ));
        return mask;
    }
    let supports_environment_depth: bool = depth_properties.supports_environment_depth.into();
    let supports_hand_removal: bool = depth_properties.supports_hand_removal.into();
    log_spatial_environment_depth(format!(
        "status=properties environmentDepthExtension=XR_META_environment_depth environmentDepthProviderRequested=true environmentDepthSupported={} environmentDepthHandRemovalSupported={} environmentDepthProviderAvailable={}",
        bool_token(supports_environment_depth),
        bool_token(supports_hand_removal),
        bool_token(supports_environment_depth),
    ));
    if !supports_environment_depth {
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=unavailable reason=system-properties-unsupported environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
            mask,
        ));
        return mask;
    }
    mask |= RECEIPT_PROPERTIES_SUPPORTED;

    let provider_info = openxr_sys::EnvironmentDepthProviderCreateInfoMETA {
        ty: openxr_sys::EnvironmentDepthProviderCreateInfoMETA::TYPE,
        next: ptr::null(),
        create_flags: openxr_sys::EnvironmentDepthProviderCreateFlagsMETA::EMPTY,
    };
    let mut provider = openxr_sys::EnvironmentDepthProviderMETA::NULL;
    let create_provider_result = create_provider(session, &provider_info, &mut provider);
    if create_provider_result != openxr_sys::Result::SUCCESS
        || provider == openxr_sys::EnvironmentDepthProviderMETA::NULL
    {
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=error stage=create-provider reason={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
            xr_result_token(create_provider_result),
            mask,
        ));
        return mask;
    }
    mask |= RECEIPT_PROVIDER_CREATED;

    let swapchain_info = openxr_sys::EnvironmentDepthSwapchainCreateInfoMETA {
        ty: openxr_sys::EnvironmentDepthSwapchainCreateInfoMETA::TYPE,
        next: ptr::null(),
        create_flags: openxr_sys::EnvironmentDepthSwapchainCreateFlagsMETA::EMPTY,
    };
    let mut swapchain = openxr_sys::EnvironmentDepthSwapchainMETA::NULL;
    let create_swapchain_result = create_swapchain(provider, &swapchain_info, &mut swapchain);
    if create_swapchain_result != openxr_sys::Result::SUCCESS
        || swapchain == openxr_sys::EnvironmentDepthSwapchainMETA::NULL
    {
        let destroy_provider_result = destroy_provider(provider);
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=error stage=create-swapchain reason={} destroyProviderResult={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
            xr_result_token(create_swapchain_result),
            xr_result_token(destroy_provider_result),
            mask,
        ));
        return mask;
    }
    mask |= RECEIPT_SWAPCHAIN_CREATED;

    let mut swapchain_state = openxr_sys::EnvironmentDepthSwapchainStateMETA {
        ty: openxr_sys::EnvironmentDepthSwapchainStateMETA::TYPE,
        next: ptr::null_mut(),
        width: 0,
        height: 0,
    };
    let state_result = get_swapchain_state(swapchain, &mut swapchain_state);
    if state_result != openxr_sys::Result::SUCCESS {
        destroy_depth_objects(destroy_swapchain, destroy_provider, swapchain, provider);
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=error stage=get-swapchain-state reason={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
            xr_result_token(state_result),
            mask,
        ));
        return mask;
    }
    mask |= RECEIPT_SWAPCHAIN_STATE_OBTAINED;

    let depth_image_handles = match enumerate_depth_images(enumerate_images, swapchain) {
        Ok(handles) => handles,
        Err(error) => {
            destroy_depth_objects(destroy_swapchain, destroy_provider, swapchain, provider);
            clear_depth_state(false);
            log_spatial_environment_depth(format!(
                "status=error stage=enumerate-images reason={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
                marker_token(&error),
                mask,
            ));
            return mask;
        }
    };
    mask |= RECEIPT_IMAGES_ENUMERATED;
    let image_count = depth_image_handles.len();

    let reference_space_info = openxr_sys::ReferenceSpaceCreateInfo {
        ty: openxr_sys::ReferenceSpaceCreateInfo::TYPE,
        next: ptr::null(),
        reference_space_type: openxr_sys::ReferenceSpaceType::LOCAL,
        pose_in_reference_space: openxr_sys::Posef::IDENTITY,
    };
    let mut reference_space = openxr_sys::Space::NULL;
    let create_space_result =
        create_reference_space(session, &reference_space_info, &mut reference_space);
    if create_space_result != openxr_sys::Result::SUCCESS
        || reference_space == openxr_sys::Space::NULL
    {
        destroy_depth_objects(destroy_swapchain, destroy_provider, swapchain, provider);
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=error stage=create-reference-space reason={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false referenceSpace=LOCAL nativeReceiptMask={}",
            xr_result_token(create_space_result),
            mask,
        ));
        return mask;
    }
    mask |= RECEIPT_REFERENCE_SPACE_CREATED;

    let start_result = start_provider(provider);
    if start_result != openxr_sys::Result::SUCCESS {
        let destroy_space_result = destroy_space(reference_space);
        destroy_depth_objects(destroy_swapchain, destroy_provider, swapchain, provider);
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=error stage=start-provider reason={} destroySpaceResult={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
            xr_result_token(start_result),
            xr_result_token(destroy_space_result),
            mask,
        ));
        return mask;
    }
    mask |= RECEIPT_PROVIDER_STARTED;
    let width = swapchain_state.width;
    let height = swapchain_state.height;
    SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_BOUND.store(true, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_VALID_DATA.store(false, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_VALID_SAMPLE_COUNT.store(0, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_TOTAL_ACQUIRED.store(0, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_LAST_SWAPCHAIN_INDEX.store(u32::MAX, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_WIDTH.store(width, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_HEIGHT.store(height, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_NEAR_Z_BITS.store(0.001f32.to_bits(), Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_FAR_Z_BITS.store(4.0f32.to_bits(), Ordering::Relaxed);

    let stop_requested = Arc::new(AtomicBool::new(false));
    let acquire_stop_requested = Arc::clone(&stop_requested);
    let acquire_thread = thread::spawn(move || {
        run_acquire_thread(
            acquire_image,
            provider,
            reference_space,
            acquire_stop_requested,
            width,
            height,
        )
    });
    mask |= RECEIPT_ACQUIRE_THREAD_STARTED;

    let mut guard = match SPATIAL_ENVIRONMENT_DEPTH_RUNTIME.lock() {
        Ok(guard) => guard,
        Err(_) => {
            stop_requested.store(true, Ordering::Relaxed);
            let _ = acquire_thread.join();
            let stop_result = stop_provider(provider);
            let destroy_space_result = destroy_space(reference_space);
            let destroy_swapchain_result = destroy_swapchain(swapchain);
            let destroy_provider_result = destroy_provider(provider);
            clear_depth_state(false);
            log_spatial_environment_depth(format!(
                "status=error stage=lock-after-create reason=poisoned stopResult={} destroySpaceResult={} destroySwapchainResult={} destroyProviderResult={} environmentDepthProviderRequested=true environmentDepthRealProviderBound=false nativeReceiptMask={}",
                xr_result_token(stop_result),
                xr_result_token(destroy_space_result),
                xr_result_token(destroy_swapchain_result),
                xr_result_token(destroy_provider_result),
                mask,
            ));
            return mask;
        }
    };
    *guard = Some(SpatialEnvironmentDepthRuntime {
        session_raw,
        provider,
        swapchain,
        reference_space,
        image_handles: depth_image_handles,
        stop_provider,
        destroy_provider,
        destroy_swapchain,
        destroy_space,
        stop_requested,
        acquire_thread: Some(acquire_thread),
    });

    log_spatial_environment_depth(format!(
        "status=provider-created environmentDepthSource=xr-meta-environment-depth environmentDepthProviderRequested=true environmentDepthProviderState=provider-running environmentDepthProviderAvailable=true environmentDepthRealProviderBound=true environmentDepthSupported=true environmentDepthImageSize={}x{} environmentDepthFormat=VK_FORMAT_D16_UNORM environmentDepthLayerCount=2 environmentDepthSwapchainImages={} environmentDepthVkImagesExported=true environmentDepthAcquireStatus=waiting-for-first-acquire environmentDepthValidData=false environmentDepthDebugValidSampleCount=0 environmentDepthReferenceSpace=LOCAL environmentDepthAcquireDisplayTimePolicy=diagnostic-zero-time spatialSdkOwnsFrameLoop=true nativeReceiptMask={}",
        width,
        height,
        image_count,
        mask,
    ));
    mask
}

fn run_acquire_thread(
    acquire_image: openxr_sys::pfn::AcquireEnvironmentDepthImageMETA,
    provider: openxr_sys::EnvironmentDepthProviderMETA,
    reference_space: openxr_sys::Space,
    stop_requested: Arc<AtomicBool>,
    width: u32,
    height: u32,
) {
    let start = Instant::now();
    let mut attempts = 0_u64;
    let mut acquired = 0_u64;
    let mut unavailable = 0_u64;
    let mut errors = 0_u64;
    let mut last_error = "none".to_string();
    let mut first_acquired_logged = false;
    while !stop_requested.load(Ordering::Relaxed) {
        attempts = attempts.saturating_add(1);
        let acquire_info = openxr_sys::EnvironmentDepthImageAcquireInfoMETA {
            ty: openxr_sys::EnvironmentDepthImageAcquireInfoMETA::TYPE,
            next: ptr::null(),
            space: reference_space,
            display_time: openxr_sys::Time::from_nanos(0),
        };
        let empty_view = openxr_sys::EnvironmentDepthImageViewMETA {
            ty: openxr_sys::EnvironmentDepthImageViewMETA::TYPE,
            next: ptr::null(),
            fov: openxr_sys::Fovf::default(),
            pose: openxr_sys::Posef::IDENTITY,
        };
        let mut timestamp = openxr_sys::EnvironmentDepthImageTimestampMETA {
            ty: openxr_sys::EnvironmentDepthImageTimestampMETA::TYPE,
            next: ptr::null(),
            capture_time: openxr_sys::Time::from_nanos(0),
        };
        let mut image = openxr_sys::EnvironmentDepthImageMETA {
            ty: openxr_sys::EnvironmentDepthImageMETA::TYPE,
            next: &mut timestamp as *mut _ as *const _,
            swapchain_index: 0,
            near_z: 0.0,
            far_z: 0.0,
            views: [empty_view; 2],
        };
        let result = unsafe { acquire_image(provider, &acquire_info, &mut image) };
        if result == openxr_sys::Result::SUCCESS {
            acquired = acquired.saturating_add(1);
            let valid_sample_count = acquired;
            SPATIAL_ENVIRONMENT_DEPTH_TOTAL_ACQUIRED.store(acquired, Ordering::Relaxed);
            SPATIAL_ENVIRONMENT_DEPTH_VALID_SAMPLE_COUNT
                .store(valid_sample_count, Ordering::Relaxed);
            SPATIAL_ENVIRONMENT_DEPTH_LAST_SWAPCHAIN_INDEX
                .store(image.swapchain_index, Ordering::Relaxed);
            SPATIAL_ENVIRONMENT_DEPTH_WIDTH.store(width, Ordering::Relaxed);
            SPATIAL_ENVIRONMENT_DEPTH_HEIGHT.store(height, Ordering::Relaxed);
            SPATIAL_ENVIRONMENT_DEPTH_NEAR_Z_BITS.store(image.near_z.to_bits(), Ordering::Relaxed);
            SPATIAL_ENVIRONMENT_DEPTH_FAR_Z_BITS.store(image.far_z.to_bits(), Ordering::Relaxed);
            SPATIAL_ENVIRONMENT_DEPTH_VALID_DATA.store(true, Ordering::Relaxed);
            if !first_acquired_logged {
                first_acquired_logged = true;
                log_spatial_environment_depth(format!(
                    "status=first-frame environmentDepthSource=xr-meta-environment-depth environmentDepthProviderState=provider-running environmentDepthProviderAvailable=true environmentDepthRealProviderBound=true environmentDepthAcquireStatus=acquired environmentDepthValidData=true environmentDepthDebugValidSampleCount={} environmentDepthImageSize={}x{} environmentDepthFormat=VK_FORMAT_D16_UNORM environmentDepthLayerCount=2 environmentDepthSwapchainIndex={} environmentDepthNearM={:.3} environmentDepthFarM={:.3} environmentDepthCaptureTimeNs={} environmentDepthAcquireDisplayTimeNs=0 environmentDepthAcquireDisplayTimePolicy=diagnostic-zero-time",
                    valid_sample_count,
                    width,
                    height,
                    image.swapchain_index,
                    image.near_z,
                    image.far_z,
                    timestamp.capture_time.as_nanos(),
                ));
            }
        } else if result == openxr_sys::Result::ENVIRONMENT_DEPTH_NOT_AVAILABLE_META {
            unavailable = unavailable.saturating_add(1);
        } else {
            errors = errors.saturating_add(1);
            last_error = xr_result_token(result);
        }
        if attempts == 1 || attempts % 120 == 0 {
            let valid_data = SPATIAL_ENVIRONMENT_DEPTH_VALID_DATA.load(Ordering::Relaxed);
            log_spatial_environment_depth(format!(
                "status=runtime environmentDepthSource=xr-meta-environment-depth environmentDepthProviderState=provider-running environmentDepthProviderAvailable=true environmentDepthRealProviderBound=true environmentDepthAcquireStatus={} environmentDepthValidData={} environmentDepthDebugValidSampleCount={} acquireAttempts={} acquiredFrames={} unavailableFrames={} acquireErrors={} lastAcquireError={} elapsedMs={} environmentDepthAcquireDisplayTimeNs=0 environmentDepthAcquireDisplayTimePolicy=diagnostic-zero-time",
                if acquired > 0 { "acquired" } else if errors > 0 { "error" } else { "not-available" },
                bool_token(valid_data),
                SPATIAL_ENVIRONMENT_DEPTH_VALID_SAMPLE_COUNT.load(Ordering::Relaxed),
                attempts,
                acquired,
                unavailable,
                errors,
                last_error,
                start.elapsed().as_millis(),
            ));
        }
        thread::sleep(Duration::from_millis(33));
    }
    log_spatial_environment_depth(format!(
        "status=acquire-thread-stopped environmentDepthRealProviderBound={} environmentDepthAcquireStatus={} environmentDepthValidData={} environmentDepthDebugValidSampleCount={} acquireAttempts={} acquiredFrames={} unavailableFrames={} acquireErrors={} lastAcquireError={}",
        bool_token(SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_BOUND.load(Ordering::Relaxed)),
        acquire_status_token(),
        bool_token(SPATIAL_ENVIRONMENT_DEPTH_VALID_DATA.load(Ordering::Relaxed)),
        SPATIAL_ENVIRONMENT_DEPTH_VALID_SAMPLE_COUNT.load(Ordering::Relaxed),
        attempts,
        acquired,
        unavailable,
        errors,
        last_error,
    ));
}

fn stop_spatial_environment_depth_probe(reason: &str) -> i64 {
    let runtime = {
        let mut guard = match SPATIAL_ENVIRONMENT_DEPTH_RUNTIME.lock() {
            Ok(guard) => guard,
            Err(_) => {
                clear_depth_state(false);
                log_spatial_environment_depth(format!(
                    "status=error stage=stop-lock reason=poisoned stopReason={} environmentDepthRealProviderBound=false",
                    marker_token(reason)
                ));
                return 0;
            }
        };
        guard.take()
    };
    if let Some(mut runtime) = runtime {
        runtime.stop_requested.store(true, Ordering::Relaxed);
        if let Some(thread) = runtime.acquire_thread.take() {
            let _ = thread.join();
        }
        let stop_result = unsafe { (runtime.stop_provider)(runtime.provider) };
        let destroy_space_result = unsafe { (runtime.destroy_space)(runtime.reference_space) };
        let destroy_swapchain_result = unsafe { (runtime.destroy_swapchain)(runtime.swapchain) };
        let destroy_provider_result = unsafe { (runtime.destroy_provider)(runtime.provider) };
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=stopped stopReason={} environmentDepthRealProviderBound=false stopProviderResult={} destroySpaceResult={} destroySwapchainResult={} destroyProviderResult={}",
            marker_token(reason),
            xr_result_token(stop_result),
            xr_result_token(destroy_space_result),
            xr_result_token(destroy_swapchain_result),
            xr_result_token(destroy_provider_result),
        ));
        1
    } else {
        clear_depth_state(false);
        log_spatial_environment_depth(format!(
            "status=already-stopped stopReason={} environmentDepthRealProviderBound=false",
            marker_token(reason)
        ));
        0
    }
}

unsafe fn enumerate_depth_images(
    enumerate_images: openxr_sys::pfn::EnumerateEnvironmentDepthSwapchainImagesMETA,
    swapchain: openxr_sys::EnvironmentDepthSwapchainMETA,
) -> Result<Vec<u64>, String> {
    let mut image_count = 0_u32;
    let count_result = enumerate_images(swapchain, 0, &mut image_count, ptr::null_mut());
    if count_result != openxr_sys::Result::SUCCESS {
        return Err(format!(
            "xrEnumerateEnvironmentDepthSwapchainImagesMETA_count_{}",
            xr_result_token(count_result)
        ));
    }
    if image_count == 0 {
        return Err("zero_depth_swapchain_images".to_string());
    }
    let mut images = vec![
        openxr_sys::SwapchainImageVulkanKHR {
            ty: openxr_sys::SwapchainImageVulkanKHR::TYPE,
            next: ptr::null_mut(),
            image: 0,
        };
        image_count as usize
    ];
    let mut enumerated = 0_u32;
    let result = enumerate_images(
        swapchain,
        image_count,
        &mut enumerated,
        images.as_mut_ptr() as *mut openxr_sys::SwapchainImageBaseHeader,
    );
    if result != openxr_sys::Result::SUCCESS {
        return Err(format!(
            "xrEnumerateEnvironmentDepthSwapchainImagesMETA_{}",
            xr_result_token(result)
        ));
    }
    images.truncate(enumerated as usize);
    if images.is_empty() {
        return Err("zero_enumerated_depth_swapchain_images".to_string());
    }
    for (index, image) in images.iter().enumerate() {
        if image.image == 0 {
            return Err(format!("null_depth_swapchain_image_{index}"));
        }
        log_spatial_environment_depth(format!(
            "status=swapchain-image index={} environmentDepthVkImage=0x{:x} environmentDepthFormat=VK_FORMAT_D16_UNORM environmentDepthLayerCount=2",
            index,
            image.image,
        ));
    }
    Ok(images.into_iter().map(|image| image.image).collect())
}

unsafe fn destroy_depth_objects(
    destroy_swapchain: openxr_sys::pfn::DestroyEnvironmentDepthSwapchainMETA,
    destroy_provider: openxr_sys::pfn::DestroyEnvironmentDepthProviderMETA,
    swapchain: openxr_sys::EnvironmentDepthSwapchainMETA,
    provider: openxr_sys::EnvironmentDepthProviderMETA,
) {
    let _ = destroy_swapchain(swapchain);
    let _ = destroy_provider(provider);
}

fn clear_depth_state(keep_requested: bool) {
    if !keep_requested {
        SPATIAL_ENVIRONMENT_DEPTH_REQUESTED.store(false, Ordering::Relaxed);
    }
    SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_BOUND.store(false, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_VALID_DATA.store(false, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_VALID_SAMPLE_COUNT.store(0, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_TOTAL_ACQUIRED.store(0, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_LAST_SWAPCHAIN_INDEX.store(u32::MAX, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_WIDTH.store(0, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_HEIGHT.store(0, Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_NEAR_Z_BITS.store(0.001f32.to_bits(), Ordering::Relaxed);
    SPATIAL_ENVIRONMENT_DEPTH_FAR_Z_BITS.store(4.0f32.to_bits(), Ordering::Relaxed);
}

fn acquire_status_token() -> &'static str {
    if SPATIAL_ENVIRONMENT_DEPTH_TOTAL_ACQUIRED.load(Ordering::Relaxed) > 0 {
        "acquired"
    } else if SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_BOUND.load(Ordering::Relaxed) {
        "waiting-for-first-acquire"
    } else {
        "not-attempted-provider-not-bound"
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

fn log_spatial_environment_depth(detail: String) {
    android_log_info(
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=spatial-environment-depth {detail}"
        ),
    );
}
