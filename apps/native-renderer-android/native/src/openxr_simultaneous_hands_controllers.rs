//! Native-renderer OpenXR adapter for simultaneous hands and controllers.

use std::{
    ptr,
    sync::atomic::{AtomicU64, Ordering},
};

use openxr as xr;

use crate::simultaneous_hands_controllers::{
    ActivationDecision, IndependentInputReadiness, LifecycleCommand, PlatformCallResult,
    SimultaneousHandsControllersLifecycle,
};

static NEXT_SESSION_GENERATION: AtomicU64 = AtomicU64::new(1);

pub(crate) const EXTENSION_NAME: &str = "XR_META_simultaneous_hands_and_controllers";

#[derive(Clone, Debug, Default)]
pub(crate) struct SimultaneousHandsControllersProbe {
    pub(crate) selected: bool,
    pub(crate) extension_requested: bool,
    pub(crate) extension_available: bool,
    pub(crate) extension_enabled: bool,
    pub(crate) functions_resolved: bool,
    pub(crate) system_supported: bool,
}

impl SimultaneousHandsControllersProbe {
    pub(crate) fn ready(&self) -> bool {
        !self.selected
            || (self.extension_requested
                && self.extension_available
                && self.extension_enabled
                && self.functions_resolved
                && self.system_supported)
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "simultaneousHandsControllersSelected={} extensionRequested={} extensionAvailable={} extensionEnabled={} functionsResolved={} systemSupported={} simultaneousHandsControllersProbeReady={} extensionName={} detachedControllersRequested=false",
            self.selected,
            self.extension_requested,
            self.extension_available,
            self.extension_enabled,
            self.functions_resolved,
            self.system_supported,
            self.ready(),
            EXTENSION_NAME,
        )
    }
}

pub(crate) fn select_extension(
    decision: ActivationDecision,
    available: &xr::ExtensionSet,
    enabled: &mut xr::ExtensionSet,
) -> Result<(), String> {
    if !decision.is_selected() {
        enabled.meta_simultaneous_hands_and_controllers = false;
        return Ok(());
    }
    if !available.meta_simultaneous_hands_and_controllers {
        return Err(format!("{EXTENSION_NAME} is unavailable"));
    }
    enabled.meta_simultaneous_hands_and_controllers = true;
    Ok(())
}

pub(crate) fn probe_instance_system(
    decision: ActivationDecision,
    available: &xr::ExtensionSet,
    enabled: &xr::ExtensionSet,
    instance: &xr::Instance,
    system: xr::SystemId,
) -> Result<SimultaneousHandsControllersProbe, String> {
    let selected = decision.is_selected();
    let functions_resolved = instance
        .exts()
        .meta_simultaneous_hands_and_controllers
        .is_some();
    let system_supported = if selected {
        query_system_support(instance, system)?
    } else {
        false
    };
    let probe = SimultaneousHandsControllersProbe {
        selected,
        extension_requested: selected,
        extension_available: available.meta_simultaneous_hands_and_controllers,
        extension_enabled: enabled.meta_simultaneous_hands_and_controllers,
        functions_resolved,
        system_supported,
    };
    Ok(probe)
}

pub(crate) struct OpenXrSimultaneousHandsControllers {
    lifecycle: SimultaneousHandsControllersLifecycle,
    session_handle: Option<xr::sys::Session>,
}

impl OpenXrSimultaneousHandsControllers {
    pub(crate) fn new(
        decision: ActivationDecision,
        available: &xr::ExtensionSet,
        enabled: &xr::ExtensionSet,
        instance: &xr::Instance,
        system: xr::SystemId,
    ) -> Result<Self, String> {
        let mut lifecycle = SimultaneousHandsControllersLifecycle::new(decision);
        let functions_resolved = instance
            .exts()
            .meta_simultaneous_hands_and_controllers
            .is_some();
        lifecycle
            .observe_instance(
                available.meta_simultaneous_hands_and_controllers,
                enabled.meta_simultaneous_hands_and_controllers,
                functions_resolved,
            )
            .map_err(str::to_owned)?;
        if decision.is_selected() {
            let supported = query_system_support(instance, system)?;
            lifecycle
                .observe_system_support(supported)
                .map_err(str::to_owned)?;
        }
        Ok(Self {
            lifecycle,
            session_handle: None,
        })
    }

    pub(crate) fn resume<G>(
        &mut self,
        instance: &xr::Instance,
        session: &xr::Session<G>,
    ) -> Result<(), String> {
        let session_handle = session.as_raw();
        let generation = if self.session_handle == Some(session_handle) {
            self.lifecycle
                .session_generation()
                .ok_or_else(|| "current session generation is missing".to_owned())?
        } else {
            if self.session_handle.is_some() {
                if let Some(previous_generation) = self.lifecycle.session_generation() {
                    self.lifecycle.hard_reset(previous_generation);
                }
            }
            self.session_handle = Some(session_handle);
            NEXT_SESSION_GENERATION.fetch_add(1, Ordering::Relaxed)
        };
        let Some(LifecycleCommand::Resume { session_generation }) =
            self.lifecycle.begin_session(generation)
        else {
            return Ok(());
        };
        let extension = instance
            .exts()
            .meta_simultaneous_hands_and_controllers
            .as_ref()
            .ok_or_else(|| "simultaneous hands/controllers functions are unresolved".to_owned())?;
        let info = xr::sys::SimultaneousHandsAndControllersTrackingResumeInfoMETA {
            ty: xr::sys::SimultaneousHandsAndControllersTrackingResumeInfoMETA::TYPE,
            next: ptr::null(),
        };
        let raw_result = unsafe {
            (extension.resume_simultaneous_hands_and_controllers_tracking)(session.as_raw(), &info)
        };
        let result = platform_result(raw_result);
        self.lifecycle
            .record_resume(session_generation, result)
            .map_err(|reason| format!("{reason}: {raw_result:?}"))?;
        crate::marker(
            "simultaneous-hands-controllers",
            format!("status=resumed {}", self.lifecycle.marker_fields()),
        );
        Ok(())
    }

    pub(crate) fn pause_best_effort<G>(
        &mut self,
        instance: &xr::Instance,
        session: &xr::Session<G>,
        reason: &'static str,
    ) {
        let Some(LifecycleCommand::Pause { session_generation }) = self.lifecycle.request_pause()
        else {
            return;
        };
        let Some(extension) = instance
            .exts()
            .meta_simultaneous_hands_and_controllers
            .as_ref()
        else {
            self.lifecycle.hard_reset(session_generation);
            self.session_handle = None;
            crate::marker(
                "simultaneous-hands-controllers",
                format!(
                    "status=pause-error reason=functions-unresolved trigger={reason} {}",
                    self.lifecycle.marker_fields()
                ),
            );
            return;
        };
        let info = xr::sys::SimultaneousHandsAndControllersTrackingPauseInfoMETA {
            ty: xr::sys::SimultaneousHandsAndControllersTrackingPauseInfoMETA::TYPE,
            next: ptr::null(),
        };
        let raw_result = unsafe {
            (extension.pause_simultaneous_hands_and_controllers_tracking)(session.as_raw(), &info)
        };
        let result = platform_result(raw_result);
        let record = self.lifecycle.record_pause(session_generation, result);
        self.session_handle = None;
        crate::marker(
            "simultaneous-hands-controllers",
            format!(
                "status={} trigger={} platformResult={:?} typedResult={} {}",
                if record.is_ok() {
                    "paused"
                } else {
                    "pause-error"
                },
                reason,
                raw_result,
                record.err().unwrap_or("none"),
                self.lifecycle.marker_fields()
            ),
        );
    }

    pub(crate) fn hard_reset_session_loss(&mut self, reason: &'static str) {
        let generation = self.lifecycle.session_generation();
        if let Some(generation) = generation {
            self.lifecycle.hard_reset(generation);
        }
        self.session_handle = None;
        crate::marker(
            "simultaneous-hands-controllers",
            format!(
                "status=hard-reset reason={} staleHandleCall=false {}",
                reason,
                self.lifecycle.marker_fields()
            ),
        );
    }

    pub(crate) fn observe_readiness(&mut self, readiness: IndependentInputReadiness) {
        self.lifecycle.observe_readiness(readiness);
    }

    pub(crate) fn is_selected(&self) -> bool {
        self.lifecycle.is_selected()
    }

    pub(crate) fn marker_fields(&self) -> String {
        self.lifecycle.marker_fields()
    }
}

fn query_system_support(instance: &xr::Instance, system: xr::SystemId) -> Result<bool, String> {
    if instance
        .exts()
        .meta_simultaneous_hands_and_controllers
        .is_none()
    {
        return Err("simultaneous hands/controllers functions are unresolved".to_owned());
    }
    unsafe {
        let mut simultaneous =
            xr::sys::SystemSimultaneousHandsAndControllersPropertiesMETA::out(ptr::null_mut());
        let mut properties = xr::sys::SystemProperties::out(simultaneous.as_mut_ptr().cast());
        let result = (instance.fp().get_system_properties)(
            instance.as_raw(),
            system,
            properties.as_mut_ptr(),
        );
        if result != xr::sys::Result::SUCCESS {
            return Err(format!(
                "query simultaneous hands/controllers system support: {result:?}"
            ));
        }
        Ok(simultaneous
            .assume_init()
            .supports_simultaneous_hands_and_controllers
            .into())
    }
}

fn platform_result(result: xr::sys::Result) -> PlatformCallResult {
    if result == xr::sys::Result::SUCCESS {
        PlatformCallResult::Success
    } else if result == xr::sys::Result::SESSION_LOSS_PENDING {
        PlatformCallResult::SessionLossPending
    } else {
        PlatformCallResult::Failure
    }
}
