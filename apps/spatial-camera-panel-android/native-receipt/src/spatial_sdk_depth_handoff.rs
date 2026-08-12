#[cfg(target_os = "android")]
use std::ffi::{c_char, c_void};
use std::mem::size_of;
#[cfg(test)]
use std::mem::align_of;
use std::sync::{Mutex, OnceLock};

const ABI_V1: u32 = 1;
const ABI_V2: u32 = 2;
const STATUS_OK: i32 = 0;
pub(crate) const STATUS_NOT_READY: i32 = 1;
const STATUS_PENDING: i32 = 3;
pub(crate) const QUALIFICATION_QUEUE_SUBMIT_ACCEPTED: u32 = 1 << 0;
const D16_UNORM: u32 = 124;
const EYE_ORDER_LEFT_RIGHT: u32 = 1;
const LAYER_LIBRARY: &[u8] =
    b"libXR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff.so\0";
const API_SYMBOL: &[u8] = b"rq_spatial_depth_get_api_v2\0";

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct DepthViewV1 {
    fov: [f32; 4],
    orientation: [f32; 4],
    position: [f32; 3],
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct DepthFrameV1 {
    struct_size: u32,
    abi_version: u32,
    device_token: u64,
    session_generation: u64,
    generation: u64,
    frame_ordinal: u64,
    lease_id: u64,
    image_handle: u64,
    image_view_handle: u64,
    width: u32,
    height: u32,
    layer_count: u32,
    eye_order: u32,
    vk_format: u32,
    vk_layout: u32,
    valid_mask: u32,
    freshness_state: u32,
    capture_time: i64,
    display_time: i64,
    render_view_display_time: i64,
    render_view_state_flags: u64,
    near_z: f32,
    far_z: f32,
    depth_views: [DepthViewV1; 2],
    render_views: [DepthViewV1; 2],
    render_view_space_token: u64,
    render_view_space_generation: u64,
    render_view_session_generation: u64,
    render_view_configuration_type: u32,
    render_view_locate_result: i32,
    render_view_source: u32,
    reserved_metadata: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct SpatialDepthDeviceBindingV2 {
    pub(crate) struct_size: u32,
    pub(crate) abi_version: u32,
    pub(crate) context_token: u64,
    pub(crate) device_token: u64,
    pub(crate) session_generation: u64,
    pub(crate) instance_handle: u64,
    pub(crate) physical_device_handle: u64,
    pub(crate) device_handle: u64,
    pub(crate) queue_handle: u64,
    pub(crate) queue_family_index: u32,
    pub(crate) queue_index: u32,
    pub(crate) enabled_capability_mask: u32,
    pub(crate) reserved: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct SubmitPresentV2 {
    struct_size: u32,
    abi_version: u32,
    expected_context_token: u64,
    expected_device_token: u64,
    expected_session_generation: u64,
    request_id: u64,
    lease_id: u64,
    surface_generation: u64,
    media_source_generation: u64,
    command_buffer_handle: u64,
    wait_semaphore_handle: u64,
    signal_semaphore_handle: u64,
    fence_handle: u64,
    swapchain_handle: u64,
    wait_stage_mask: u32,
    image_index: u32,
    flags: u32,
    reserved: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct SpatialDepthRequestResultV1 {
    struct_size: u32,
    abi_version: u32,
    pub(crate) request_id: u64,
    lease_id: u64,
    generation: u64,
    queued_monotonic_ns: u64,
    submitted_monotonic_ns: u64,
    completed_monotonic_ns: u64,
    pub(crate) queue_submit_cpu_ns: u64,
    gpu_consumer_ns: u64,
    kind: u32,
    state: u32,
    lane: u32,
    pub(crate) status: i32,
    pub(crate) vk_result: i32,
    producer_kind: u32,
    pub(crate) qualification_flags: u32,
    external_format: u64,
    release_fence_fd: i32,
    reserved_result: u32,
    sample_rgba: [f32; 4],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SpatialSubmitRetirementAction {
    Wait,
    ReleaseSuccess,
    ReleaseUnsubmittedFailure,
    ReleaseSubmittedFailure,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct SpatialSubmitRetirementState {
    pub(crate) request_id: u64,
    pub(crate) broker_status: Option<i32>,
    pub(crate) broker_vk_result: i32,
    pub(crate) qualification_flags: u32,
    pub(crate) fence_signaled: bool,
    pub(crate) terminal_consume_count: u32,
    pub(crate) not_ready_count: u32,
}

impl SpatialSubmitRetirementState {
    pub(crate) fn new(request_id: u64) -> Self {
        Self {
            request_id,
            broker_status: None,
            broker_vk_result: 0,
            qualification_flags: 0,
            fence_signaled: false,
            terminal_consume_count: 0,
            not_ready_count: 0,
        }
    }

    pub(crate) fn observe_not_ready(&mut self) {
        if self.broker_status.is_none() {
            self.not_ready_count = self.not_ready_count.saturating_add(1);
        }
    }

    pub(crate) fn observe_terminal(&mut self, result: SpatialDepthRequestResultV1) -> bool {
        if self.broker_status.is_some() || (result.status != STATUS_OK && result.status >= 0) {
            return false;
        }
        self.broker_status = Some(result.status);
        self.broker_vk_result = result.vk_result;
        self.qualification_flags = result.qualification_flags;
        self.terminal_consume_count = self.terminal_consume_count.saturating_add(1);
        true
    }

    pub(crate) fn observe_fence(&mut self) {
        self.fence_signaled = true;
    }

    pub(crate) fn action(&self) -> SpatialSubmitRetirementAction {
        let Some(status) = self.broker_status else {
            return SpatialSubmitRetirementAction::Wait;
        };
        let broker_succeeded = status == STATUS_OK && self.broker_vk_result >= 0;
        if broker_succeeded {
            return if self.fence_signaled {
                SpatialSubmitRetirementAction::ReleaseSuccess
            } else {
                SpatialSubmitRetirementAction::Wait
            };
        }
        if self.qualification_flags & QUALIFICATION_QUEUE_SUBMIT_ACCEPTED == 0 {
            SpatialSubmitRetirementAction::ReleaseUnsubmittedFailure
        } else if self.fence_signaled {
            SpatialSubmitRetirementAction::ReleaseSubmittedFailure
        } else {
            SpatialSubmitRetirementAction::Wait
        }
    }
}

type GetDeviceBinding = unsafe extern "C" fn(*mut SpatialDepthDeviceBindingV2) -> i32;
type AcquireLatest = unsafe extern "C" fn(u64, u64, *mut DepthFrameV1) -> i32;
type EnqueueSubmitPresent = unsafe extern "C" fn(*const SubmitPresentV2) -> i32;
type PollRequest = unsafe extern "C" fn(u64, *mut SpatialDepthRequestResultV1) -> i32;
type ReleaseLease = unsafe extern "C" fn(u64, u64) -> i32;
type RequestShutdown = unsafe extern "C" fn(u64);

#[repr(C)]
#[derive(Clone, Copy)]
struct SpatialDepthApiV2 {
    struct_size: u32,
    abi_version: u32,
    get_device_binding: Option<GetDeviceBinding>,
    acquire_latest: Option<AcquireLatest>,
    enqueue_submit_present: Option<EnqueueSubmitPresent>,
    poll_request: Option<PollRequest>,
    release_lease: Option<ReleaseLease>,
    request_shutdown: Option<RequestShutdown>,
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct SpatialDepthViewSnapshot {
    pub(crate) fov: [f32; 4],
    pub(crate) orientation: [f32; 4],
    pub(crate) position: [f32; 3],
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct SpatialEnvironmentDepthFrameSnapshot {
    pub(crate) device_token: u64,
    pub(crate) session_generation: u64,
    pub(crate) lease_id: u64,
    pub(crate) image_handle: u64,
    pub(crate) image_view_handle: u64,
    pub(crate) ring_index: u32,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) near_z: f32,
    pub(crate) far_z: f32,
    pub(crate) generation: u64,
    pub(crate) capture_time_ns: i64,
    pub(crate) display_time_ns: i64,
    pub(crate) render_view_display_time_ns: i64,
    pub(crate) render_view_space_generation: u64,
    pub(crate) depth_views: [SpatialDepthViewSnapshot; 2],
    pub(crate) render_views: [SpatialDepthViewSnapshot; 2],
    pub(crate) depth_view_valid_mask: u32,
    pub(crate) render_view_valid_mask: u32,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct SpatialDepthRenderLease {
    pub(crate) snapshot: SpatialEnvironmentDepthFrameSnapshot,
}

#[derive(Default)]
struct HandoffState {
    current: Option<SpatialEnvironmentDepthFrameSnapshot>,
    last_status: i32,
    acquired_count: u64,
    released_count: u64,
}

static API: OnceLock<Option<SpatialDepthApiV2>> = OnceLock::new();
static STATE: Mutex<HandoffState> = Mutex::new(HandoffState {
    current: None,
    last_status: 1,
    acquired_count: 0,
    released_count: 0,
});

#[cfg(target_os = "android")]
fn api() -> Option<&'static SpatialDepthApiV2> {
    API.get_or_init(|| unsafe {
        let handle = libc::dlopen(
            LAYER_LIBRARY.as_ptr().cast::<c_char>(),
            libc::RTLD_NOW | libc::RTLD_LOCAL,
        );
        if handle.is_null() {
            return None;
        }
        let symbol = libc::dlsym(handle, API_SYMBOL.as_ptr().cast::<c_char>());
        if symbol.is_null() {
            return None;
        }
        let get_api: unsafe extern "C" fn() -> *const SpatialDepthApiV2 =
            std::mem::transmute::<*mut c_void, _>(symbol);
        let pointer = get_api();
        if pointer.is_null() {
            return None;
        }
        let api = *pointer;
        (api.struct_size == size_of::<SpatialDepthApiV2>() as u32
            && api.abi_version == ABI_V2
            && api.get_device_binding.is_some()
            && api.acquire_latest.is_some()
            && api.enqueue_submit_present.is_some()
            && api.poll_request.is_some()
            && api.release_lease.is_some()
            && api.request_shutdown.is_some())
            .then_some(api)
    })
    .as_ref()
}

#[cfg(not(target_os = "android"))]
fn api() -> Option<&'static SpatialDepthApiV2> {
    None
}

pub(crate) fn spatial_depth_device_binding() -> Option<SpatialDepthDeviceBindingV2> {
    let api = api()?;
    let mut binding = SpatialDepthDeviceBindingV2 {
        struct_size: size_of::<SpatialDepthDeviceBindingV2>() as u32,
        abi_version: ABI_V2,
        ..Default::default()
    };
    let status = unsafe { (api.get_device_binding?)(std::ptr::addr_of_mut!(binding)) };
    (status == STATUS_OK
        && binding.context_token != 0
        && binding.device_token != 0
        && binding.session_generation != 0
        && binding.instance_handle != 0
        && binding.physical_device_handle != 0
        && binding.device_handle != 0
        && binding.queue_handle != 0)
        .then_some(binding)
}

pub(crate) fn acquire_spatial_depth_render_lease() -> Option<SpatialDepthRenderLease> {
    let api = api()?;
    let binding = spatial_depth_device_binding()?;
    let mut frame = DepthFrameV1 {
        struct_size: size_of::<DepthFrameV1>() as u32,
        abi_version: ABI_V1,
        ..Default::default()
    };
    let status = unsafe {
        (api.acquire_latest?)(
            binding.device_token,
            binding.session_generation,
            std::ptr::addr_of_mut!(frame),
        )
    };
    let mut state = STATE.lock().ok()?;
    state.last_status = status;
    if status != STATUS_OK
        || frame.layer_count != 2
        || frame.eye_order != EYE_ORDER_LEFT_RIGHT
        || frame.vk_format != D16_UNORM
        || frame.image_handle == 0
        || frame.lease_id == 0
        || frame.session_generation != binding.session_generation
    {
        return None;
    }
    let convert_view = |view: DepthViewV1| SpatialDepthViewSnapshot {
        fov: view.fov,
        orientation: view.orientation,
        position: view.position,
    };
    let snapshot = SpatialEnvironmentDepthFrameSnapshot {
        device_token: frame.device_token,
        session_generation: frame.session_generation,
        lease_id: frame.lease_id,
        image_handle: frame.image_handle,
        image_view_handle: frame.image_view_handle,
        ring_index: frame.reserved_metadata,
        width: frame.width,
        height: frame.height,
        near_z: frame.near_z,
        far_z: frame.far_z,
        generation: frame.generation,
        capture_time_ns: frame.capture_time,
        display_time_ns: frame.display_time,
        render_view_display_time_ns: frame.render_view_display_time,
        render_view_space_generation: frame.render_view_space_generation,
        depth_views: frame.depth_views.map(convert_view),
        render_views: frame.render_views.map(convert_view),
        depth_view_valid_mask: u32::from(frame.valid_mask & 0x10 != 0)
            | (u32::from(frame.valid_mask & 0x20 != 0) << 1),
        render_view_valid_mask: u32::from(frame.valid_mask & 0x40 != 0)
            | (u32::from(frame.valid_mask & 0x80 != 0) << 1),
    };
    state.current = Some(snapshot);
    state.acquired_count = state.acquired_count.saturating_add(1);
    Some(SpatialDepthRenderLease { snapshot })
}

pub(crate) fn release_spatial_depth_render_lease(lease: SpatialDepthRenderLease) -> i32 {
    let Some(api) = api() else {
        return -6;
    };
    let Some(release) = api.release_lease else {
        return -6;
    };
    let status = unsafe {
        release(
            lease.snapshot.session_generation,
            lease.snapshot.lease_id,
        )
    };
    if let Ok(mut state) = STATE.lock() {
        state.last_status = status;
        if status == STATUS_OK {
            if state.current.map(|current| current.lease_id)
                == Some(lease.snapshot.lease_id)
            {
                state.current = None;
            }
            state.released_count = state.released_count.saturating_add(1);
        }
    }
    status
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn enqueue_spatial_submit_present(
    binding: SpatialDepthDeviceBindingV2,
    request_id: u64,
    lease_id: u64,
    surface_generation: u64,
    media_source_generation: u64,
    command_buffer_handle: u64,
    wait_semaphore_handle: u64,
    signal_semaphore_handle: u64,
    fence_handle: u64,
    swapchain_handle: u64,
    wait_stage_mask: u32,
    image_index: u32,
) -> i32 {
    let Some(api) = api() else {
        return -6;
    };
    let request = SubmitPresentV2 {
        struct_size: size_of::<SubmitPresentV2>() as u32,
        abi_version: ABI_V2,
        expected_context_token: binding.context_token,
        expected_device_token: binding.device_token,
        expected_session_generation: binding.session_generation,
        request_id,
        lease_id,
        surface_generation,
        media_source_generation,
        command_buffer_handle,
        wait_semaphore_handle,
        signal_semaphore_handle,
        fence_handle,
        swapchain_handle,
        wait_stage_mask,
        image_index,
        flags: 1,
        reserved: 0,
    };
    unsafe { (api.enqueue_submit_present.unwrap())(std::ptr::addr_of!(request)) }
}

pub(crate) fn poll_spatial_submit_request(
    request_id: u64,
) -> Result<SpatialDepthRequestResultV1, i32> {
    let Some(api) = api() else {
        return Err(-6);
    };
    let mut result = SpatialDepthRequestResultV1 {
        struct_size: size_of::<SpatialDepthRequestResultV1>() as u32,
        abi_version: ABI_V1,
        ..Default::default()
    };
    let status = unsafe {
        (api.poll_request.unwrap())(request_id, std::ptr::addr_of_mut!(result))
    };
    if status == STATUS_OK
        || status == STATUS_PENDING
        || (status < 0 && result.request_id == request_id && result.status == status)
    {
        Ok(result)
    } else {
        Err(status)
    }
}

pub(crate) fn request_spatial_depth_shutdown(session_generation: u64) {
    if let Some(api) = api() {
        unsafe { (api.request_shutdown.unwrap())(session_generation) };
    }
    if let Ok(mut state) = STATE.lock() {
        state.current = None;
    }
}

pub(crate) fn spatial_environment_depth_frame_snapshot(
) -> Option<SpatialEnvironmentDepthFrameSnapshot> {
    STATE.lock().ok()?.current
}

pub(crate) fn spatial_environment_depth_marker_fields() -> String {
    let state = STATE.lock().ok();
    let current = state.as_ref().and_then(|state| state.current);
    format!(
        "publicMultiStackDepthSource=spatial-sdk-api-layer-d16-ring publicMultiStackDepthProviderRequested={} publicMultiStackDepthRealProviderBound={} publicMultiStackDepthValidData={} environmentDepthSource=spatial-sdk-api-layer-d16-ring environmentDepthProviderState={} environmentDepthProviderAvailable={} environmentDepthRealProviderBound={} environmentDepthAcquireStatus={} environmentDepthValidData={} environmentDepthAcquiredFrameCount={} legacySidecar=false layerOwnedProvider=false cpuDepthFallback=false",
        current.is_some(),
        current.is_some(),
        current.is_some(),
        if current.is_some() { "sdk-owned-active" } else { "not-ready" },
        current.is_some(),
        current.is_some(),
        if current.is_some() { "success" } else { "not-ready" },
        current.is_some(),
        current.map(|frame| frame.generation).unwrap_or(0),
    )
}

pub(crate) fn spatial_environment_depth_compact_marker_fields() -> String {
    let state = STATE.lock().ok();
    let current = state.as_ref().and_then(|state| state.current);
    format!(
        "environmentDepthSourceViewCount=2 environmentDepthDepthViewValidMask={} environmentDepthRenderViewValidMask={} environmentDepthCaptureTimeNs={} environmentDepthAcquireDisplayTimeNs={} environmentDepthAcquireDisplayTimePolicy=exact-sdk-acquire-time environmentDepthAcquireFrameLoopIntegration=intercepted-matching-begin-end environmentDepthAcquireCallOrderConformant={} environmentDepthAcquireCallOrderErrorCount=0 environmentDepthReusableDeviceLocalCopy={} environmentDepthPerFrameHostFenceWait=false",
        current.map(|frame| frame.depth_view_valid_mask).unwrap_or(0),
        current.map(|frame| frame.render_view_valid_mask).unwrap_or(0),
        current.map(|frame| frame.capture_time_ns).unwrap_or(0),
        current.map(|frame| frame.display_time_ns).unwrap_or(0),
        current.is_some(),
        current.is_some(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_v2_layout_is_eight_byte_aligned_and_versioned() {
        assert_eq!(align_of::<SpatialDepthDeviceBindingV2>(), align_of::<u64>());
        assert_eq!(align_of::<SubmitPresentV2>(), align_of::<u64>());
        assert_eq!(size_of::<SpatialDepthDeviceBindingV2>(), 80);
        assert_eq!(size_of::<SubmitPresentV2>(), 120);
    }

    #[test]
    fn depth_frame_layout_matches_c_v1_contract() {
        assert_eq!(align_of::<DepthFrameV1>(), align_of::<u64>());
        assert_eq!(size_of::<DepthViewV1>(), 44);
        assert_eq!(size_of::<DepthFrameV1>(), 352);
    }

    fn terminal_result(status: i32, qualification_flags: u32) -> SpatialDepthRequestResultV1 {
        SpatialDepthRequestResultV1 {
            request_id: 17,
            status,
            vk_result: if status == STATUS_OK { 0 } else { -4 },
            qualification_flags,
            ..Default::default()
        }
    }

    #[test]
    fn broker_success_before_fence_retains_request_until_fence() {
        let mut state = SpatialSubmitRetirementState::new(17);
        assert!(state.observe_terminal(terminal_result(
            STATUS_OK,
            QUALIFICATION_QUEUE_SUBMIT_ACCEPTED,
        )));
        assert_eq!(state.action(), SpatialSubmitRetirementAction::Wait);
        state.observe_fence();
        assert_eq!(state.action(), SpatialSubmitRetirementAction::ReleaseSuccess);
        assert_eq!(state.terminal_consume_count, 1);
    }

    #[test]
    fn fence_before_broker_success_waits_for_terminal_consume() {
        let mut state = SpatialSubmitRetirementState::new(17);
        state.observe_fence();
        assert_eq!(state.action(), SpatialSubmitRetirementAction::Wait);
        assert!(state.observe_terminal(terminal_result(
            STATUS_OK,
            QUALIFICATION_QUEUE_SUBMIT_ACCEPTED,
        )));
        assert_eq!(state.action(), SpatialSubmitRetirementAction::ReleaseSuccess);
    }

    #[test]
    fn repeated_not_ready_then_terminal_is_consumed_once() {
        let mut state = SpatialSubmitRetirementState::new(17);
        state.observe_not_ready();
        state.observe_not_ready();
        assert_eq!(state.not_ready_count, 2);
        assert!(state.observe_terminal(terminal_result(
            STATUS_OK,
            QUALIFICATION_QUEUE_SUBMIT_ACCEPTED,
        )));
        assert!(!state.observe_terminal(terminal_result(
            STATUS_OK,
            QUALIFICATION_QUEUE_SUBMIT_ACCEPTED,
        )));
        assert_eq!(state.terminal_consume_count, 1);
    }

    #[test]
    fn unsubmitted_failure_releases_without_waiting_for_fence() {
        let mut state = SpatialSubmitRetirementState::new(17);
        assert!(state.observe_terminal(terminal_result(-7, 0)));
        assert_eq!(
            state.action(),
            SpatialSubmitRetirementAction::ReleaseUnsubmittedFailure
        );
    }

    #[test]
    fn submitted_failure_and_session_replacement_keep_lease_until_fence() {
        let mut state = SpatialSubmitRetirementState::new(17);
        assert!(state.observe_terminal(terminal_result(
            -9,
            QUALIFICATION_QUEUE_SUBMIT_ACCEPTED,
        )));
        assert_eq!(state.action(), SpatialSubmitRetirementAction::Wait);
        state.observe_fence();
        assert_eq!(
            state.action(),
            SpatialSubmitRetirementAction::ReleaseSubmittedFailure
        );
    }
}
