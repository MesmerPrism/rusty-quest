use std::ffi::CString;
use std::mem;
use std::os::raw::{c_char, c_void};
use std::ptr;
use std::sync::Mutex;

use openxr_sys as xr;
use openxr_sys::Handle;

const START_RECEIVED: i64 = 1 << 0;
const START_INSTANCE_NONZERO: i64 = 1 << 1;
const START_SESSION_NONZERO: i64 = 1 << 2;
const START_GET_PROC_NONZERO: i64 = 1 << 3;
const START_FUNCTIONS_RESOLVED: i64 = 1 << 4;
const START_ACTION_SET_CREATED: i64 = 1 << 5;
const START_ACTION_CREATED: i64 = 1 << 6;
const START_BINDINGS_SUGGESTED: i64 = 1 << 7;
const START_ACTION_SET_ATTACHED: i64 = 1 << 8;

const ACTION_SET_NAME: &str = "spatial_camera_controls";
const ACTION_SET_LOCALIZED_NAME: &str = "Spatial Camera Controls";
const LEFT_THUMBSTICK_Y_ACTION: &str = "spatial_left_thumbstick_y";
const LEFT_THUMBSTICK_Y_LOCALIZED_NAME: &str = "Spatial Left Thumbstick Y";
const LEFT_THUMBSTICK_Y_PATH: &str = "/user/hand/left/input/thumbstick/y";
const INTERACTION_PROFILES: &[&str] = &[
    "/interaction_profiles/oculus/touch_controller",
    "/interaction_profiles/meta/touch_controller_plus",
];

static CONTROLLER_ACTIONS: Mutex<Option<SpatialControllerActions>> = Mutex::new(None);

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSpatialControllerActions(
    _env: *mut c_void,
    _thiz: *mut c_void,
    openxr_instance_handle: i64,
    openxr_session_handle: i64,
    openxr_get_instance_proc_addr_handle: i64,
) -> i64 {
    let mut mask = START_RECEIVED;
    if openxr_instance_handle != 0 {
        mask |= START_INSTANCE_NONZERO;
    }
    if openxr_session_handle != 0 {
        mask |= START_SESSION_NONZERO;
    }
    if openxr_get_instance_proc_addr_handle != 0 {
        mask |= START_GET_PROC_NONZERO;
    }

    let mut guard = CONTROLLER_ACTIONS
        .lock()
        .expect("controller action mutex poisoned");
    *guard = None;

    let start_result = SpatialControllerActions::start(
        openxr_instance_handle,
        openxr_session_handle,
        openxr_get_instance_proc_addr_handle,
        &mut mask,
    );
    match start_result {
        Ok(actions) => {
            marker(format!(
                "status=started nativeControllerActionBridge=true startMask={} actionSet={} leftThumbstickYAction={} leftThumbstickYInputPath={} actionSetAttached={}",
                mask,
                ACTION_SET_NAME,
                LEFT_THUMBSTICK_Y_ACTION,
                LEFT_THUMBSTICK_Y_PATH,
                crate::bool_token((mask & START_ACTION_SET_ATTACHED) != 0),
            ));
            *guard = Some(actions);
        }
        Err(reason) => {
            marker(format!(
                "status=start-failed nativeControllerActionBridge=true startMask={} reason={} actionSetAttached=false leftThumbstickYAction=false",
                mask,
                crate::marker_token(&reason),
            ));
        }
    }
    mask
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativePollSpatialControllerLeftThumbstickY(
    _env: *mut c_void,
    _thiz: *mut c_void,
) -> f32 {
    let mut guard = CONTROLLER_ACTIONS
        .lock()
        .expect("controller action mutex poisoned");
    let Some(actions) = guard.as_mut() else {
        return f32::NAN;
    };
    actions.poll_left_thumbstick_y()
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStopSpatialControllerActions(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    let mut guard = CONTROLLER_ACTIONS
        .lock()
        .expect("controller action mutex poisoned");
    *guard = None;
    marker("status=stopped nativeControllerActionBridge=true actionSetAttached=false".to_string());
}

struct SpatialControllerActions {
    session: xr::Session,
    action_set: xr::ActionSet,
    left_thumbstick_y: xr::Action,
    sync_actions: xr::pfn::SyncActions,
    get_action_state_float: xr::pfn::GetActionStateFloat,
    destroy_action: xr::pfn::DestroyAction,
    destroy_action_set: xr::pfn::DestroyActionSet,
    poll_count: u64,
}

impl SpatialControllerActions {
    fn start(
        openxr_instance_handle: i64,
        openxr_session_handle: i64,
        openxr_get_instance_proc_addr_handle: i64,
        mask: &mut i64,
    ) -> Result<Self, String> {
        if openxr_instance_handle == 0
            || openxr_session_handle == 0
            || openxr_get_instance_proc_addr_handle == 0
        {
            return Err("missing-openxr-handles".to_string());
        }

        let instance = xr::Instance::from_raw(openxr_instance_handle as u64);
        let session = xr::Session::from_raw(openxr_session_handle as u64);
        let get_instance_proc_addr: xr::pfn::GetInstanceProcAddr =
            unsafe { mem::transmute(openxr_get_instance_proc_addr_handle as usize) };

        let create_action_set: xr::pfn::CreateActionSet =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrCreateActionSet")?;
        let destroy_action_set: xr::pfn::DestroyActionSet =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrDestroyActionSet")?;
        let create_action: xr::pfn::CreateAction =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrCreateAction")?;
        let destroy_action: xr::pfn::DestroyAction =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrDestroyAction")?;
        let string_to_path: xr::pfn::StringToPath =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrStringToPath")?;
        let suggest_interaction_profile_bindings: xr::pfn::SuggestInteractionProfileBindings =
            resolve_openxr_function(
                instance,
                get_instance_proc_addr,
                "xrSuggestInteractionProfileBindings",
            )?;
        let attach_session_action_sets: xr::pfn::AttachSessionActionSets = resolve_openxr_function(
            instance,
            get_instance_proc_addr,
            "xrAttachSessionActionSets",
        )?;
        let sync_actions: xr::pfn::SyncActions =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrSyncActions")?;
        let get_action_state_float: xr::pfn::GetActionStateFloat =
            resolve_openxr_function(instance, get_instance_proc_addr, "xrGetActionStateFloat")?;
        *mask |= START_FUNCTIONS_RESOLVED;

        let action_set_info = xr::ActionSetCreateInfo {
            ty: xr::ActionSetCreateInfo::TYPE,
            next: ptr::null(),
            action_set_name: fixed_c_chars(ACTION_SET_NAME),
            localized_action_set_name: fixed_c_chars(ACTION_SET_LOCALIZED_NAME),
            priority: 30,
        };
        let mut action_set = xr::ActionSet::NULL;
        let create_set_result =
            unsafe { create_action_set(instance, &action_set_info, &mut action_set) };
        if create_set_result != xr::Result::SUCCESS {
            return Err(format!(
                "xrCreateActionSet-{}",
                xr_result_token(create_set_result)
            ));
        }
        *mask |= START_ACTION_SET_CREATED;

        let action_info = xr::ActionCreateInfo {
            ty: xr::ActionCreateInfo::TYPE,
            next: ptr::null(),
            action_name: fixed_c_chars(LEFT_THUMBSTICK_Y_ACTION),
            action_type: xr::ActionType::FLOAT_INPUT,
            count_subaction_paths: 0,
            subaction_paths: ptr::null(),
            localized_action_name: fixed_c_chars(LEFT_THUMBSTICK_Y_LOCALIZED_NAME),
        };
        let mut left_thumbstick_y = xr::Action::NULL;
        let create_action_result =
            unsafe { create_action(action_set, &action_info, &mut left_thumbstick_y) };
        if create_action_result != xr::Result::SUCCESS {
            unsafe {
                destroy_action_set(action_set);
            }
            return Err(format!(
                "xrCreateAction-{}",
                xr_result_token(create_action_result)
            ));
        }
        *mask |= START_ACTION_CREATED;

        let binding_path = string_to_openxr_path(instance, string_to_path, LEFT_THUMBSTICK_Y_PATH)?;
        let mut suggested_count = 0_u32;
        for profile in INTERACTION_PROFILES {
            let profile_path = string_to_openxr_path(instance, string_to_path, profile)?;
            let binding = xr::ActionSuggestedBinding {
                action: left_thumbstick_y,
                binding: binding_path,
            };
            let suggested = xr::InteractionProfileSuggestedBinding {
                ty: xr::InteractionProfileSuggestedBinding::TYPE,
                next: ptr::null(),
                interaction_profile: profile_path,
                count_suggested_bindings: 1,
                suggested_bindings: &binding,
            };
            let result = unsafe { suggest_interaction_profile_bindings(instance, &suggested) };
            if result == xr::Result::SUCCESS {
                suggested_count += 1;
                marker(format!(
                    "status=binding-suggested interactionProfile={} leftThumbstickYInputPath={} leftControllerThumbstickYBinding=true",
                    crate::marker_token(profile),
                    LEFT_THUMBSTICK_Y_PATH,
                ));
            } else {
                marker(format!(
                    "status=binding-warning interactionProfile={} reason={} leftControllerThumbstickYBinding=false",
                    crate::marker_token(profile),
                    xr_result_token(result),
                ));
            }
        }
        if suggested_count > 0 {
            *mask |= START_BINDINGS_SUGGESTED;
        }

        let action_sets = [action_set];
        let attach_info = xr::SessionActionSetsAttachInfo {
            ty: xr::SessionActionSetsAttachInfo::TYPE,
            next: ptr::null(),
            count_action_sets: action_sets.len() as u32,
            action_sets: action_sets.as_ptr(),
        };
        let attach_result = unsafe { attach_session_action_sets(session, &attach_info) };
        if attach_result != xr::Result::SUCCESS {
            unsafe {
                destroy_action(left_thumbstick_y);
                destroy_action_set(action_set);
            }
            return Err(format!(
                "xrAttachSessionActionSets-{}",
                xr_result_token(attach_result)
            ));
        }
        *mask |= START_ACTION_SET_ATTACHED;

        marker(format!(
            "status=attached actionSet={} actionSetAttached=true suggestedBindingCount={} leftThumbstickYAction=true leftControllerThumbstickY={}",
            ACTION_SET_NAME,
            suggested_count,
            LEFT_THUMBSTICK_Y_PATH,
        ));

        Ok(Self {
            session,
            action_set,
            left_thumbstick_y,
            sync_actions,
            get_action_state_float,
            destroy_action,
            destroy_action_set,
            poll_count: 0,
        })
    }

    fn poll_left_thumbstick_y(&mut self) -> f32 {
        let active_set = xr::ActiveActionSet {
            action_set: self.action_set,
            subaction_path: xr::Path::NULL,
        };
        let sync_info = xr::ActionsSyncInfo {
            ty: xr::ActionsSyncInfo::TYPE,
            next: ptr::null(),
            count_active_action_sets: 1,
            active_action_sets: &active_set,
        };
        let sync_result = unsafe { (self.sync_actions)(self.session, &sync_info) };
        if sync_result != xr::Result::SUCCESS {
            if self.poll_count == 0 || self.poll_count % 120 == 0 {
                marker(format!(
                    "status=sync-error pollCount={} reason={} leftControllerThumbstickYActive=false",
                    self.poll_count,
                    xr_result_token(sync_result),
                ));
            }
            self.poll_count = self.poll_count.saturating_add(1);
            return f32::NAN;
        }

        let get_info = xr::ActionStateGetInfo {
            ty: xr::ActionStateGetInfo::TYPE,
            next: ptr::null(),
            action: self.left_thumbstick_y,
            subaction_path: xr::Path::NULL,
        };
        let mut state = xr::ActionStateFloat {
            ty: xr::ActionStateFloat::TYPE,
            next: ptr::null_mut(),
            current_state: 0.0,
            changed_since_last_sync: xr::FALSE,
            last_change_time: xr::Time::from_nanos(0),
            is_active: xr::FALSE,
        };
        let state_result =
            unsafe { (self.get_action_state_float)(self.session, &get_info, &mut state) };
        if state_result != xr::Result::SUCCESS {
            if self.poll_count == 0 || self.poll_count % 120 == 0 {
                marker(format!(
                    "status=state-error pollCount={} reason={} leftControllerThumbstickYActive=false",
                    self.poll_count,
                    xr_result_token(state_result),
                ));
            }
            self.poll_count = self.poll_count.saturating_add(1);
            return f32::NAN;
        }

        let active = state.is_active == xr::TRUE;
        if self.poll_count == 0
            || self.poll_count % 120 == 0
            || state.changed_since_last_sync == xr::TRUE
        {
            marker(format!(
                "status=polled pollCount={} leftControllerThumbstickYActive={} leftControllerThumbstickY={:.3} changedSinceLastSync={}",
                self.poll_count,
                crate::bool_token(active),
                state.current_state,
                crate::bool_token(state.changed_since_last_sync == xr::TRUE),
            ));
        }
        self.poll_count = self.poll_count.saturating_add(1);
        if active {
            state.current_state.clamp(-1.0, 1.0)
        } else {
            0.0
        }
    }
}

impl Drop for SpatialControllerActions {
    fn drop(&mut self) {
        unsafe {
            (self.destroy_action)(self.left_thumbstick_y);
            (self.destroy_action_set)(self.action_set);
        }
    }
}

fn string_to_openxr_path(
    instance: xr::Instance,
    string_to_path: xr::pfn::StringToPath,
    path: &str,
) -> Result<xr::Path, String> {
    let path_c = CString::new(path).expect("static OpenXR path must not contain NUL");
    let mut output = xr::Path::NULL;
    let result = unsafe { string_to_path(instance, path_c.as_ptr(), &mut output) };
    if result == xr::Result::SUCCESS {
        Ok(output)
    } else {
        Err(format!(
            "xrStringToPath-{}-{}",
            crate::marker_token(path),
            xr_result_token(result)
        ))
    }
}

fn resolve_openxr_function<T>(
    instance: xr::Instance,
    get_instance_proc_addr: xr::pfn::GetInstanceProcAddr,
    name: &str,
) -> Result<T, String>
where
    T: Copy,
{
    let function_name = CString::new(name).expect("static OpenXR symbol must not contain NUL");
    let mut function: Option<xr::pfn::VoidFunction> = None;
    let result = unsafe { get_instance_proc_addr(instance, function_name.as_ptr(), &mut function) };
    if result != xr::Result::SUCCESS || function.is_none() {
        return Err(format!("resolve-{}-{}", name, xr_result_token(result)));
    }
    Ok(unsafe { mem::transmute_copy(&function.expect("checked")) })
}

fn fixed_c_chars<const N: usize>(value: &str) -> [c_char; N] {
    let mut output = [0 as c_char; N];
    for (index, byte) in value
        .as_bytes()
        .iter()
        .take(N.saturating_sub(1))
        .enumerate()
    {
        output[index] = *byte as c_char;
    }
    output
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
        "RQSpatialCameraPanelNative",
        &format!(
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_NATIVE channel=spatial-controller-actions {}",
            message
        ),
    );
}
