//! Actual selected-fresh constructor custody. This is not a Host or an owner
//! restore. Failed construction retains every allocated gate until actual local
//! physical barriers and a fixed app-owned immutable/fsynced handoff complete.
use super::*;
use std::sync::atomic::AtomicU8;

pub(super) struct Construction {
    capability: Arc<NativeCapability>,
    expected: String,
    attempt: u64,
    pub(super) provider: Arc<Mutex<Option<QuestBrokerRuntimeProvider>>>,
    pub(super) broker: Mutex<QuestBrokerConstructionCustody>,
    callbacks: Mutex<Option<JavaOwnerCallbacks>>,
    pub(super) cleanup: Mutex<Option<retained_cleanup_host::Cleanup>>,
    pub(super) cleanup_server: Mutex<Option<Arc<Mutex<Option<retained_cleanup_host::Server>>>>>,
    pub(super) server: Mutex<Option<Arc<Mutex<Option<DispatchServer>>>>>,
    pub(super) activation: Mutex<Option<Arc<Mutex<Option<ActivationServer>>>>>,
    provider_entered: AtomicBool,
    own_preparation: AtomicU8, // 0 unentered, 1 entered, 2 returned false, 3 returned true
}
pub(super) fn current() -> Result<Option<Arc<Construction>>, String> {
    Ok(process()
        .lock()
        .map_err(|_| "construction process gate poisoned")?
        .partial_construction
        .clone())
}
pub(super) fn begin(capability: Arc<NativeCapability>, expected: &str) -> Result<(), String> {
    capability.require_live()?;
    let mut p = process()
        .lock()
        .map_err(|_| "construction process gate poisoned")?;
    if !p.initializing
        || p.closing
        || p.host.is_some()
        || p.host_leases != 0
        || p.partial_construction.is_some()
        || p.root_reestablishment.is_some()
    {
        return Err("construction original ownership unavailable".into());
    }
    p.partial_construction = Some(Arc::new(Construction::new(
        capability,
        expected,
        p.initialization_attempt,
    )));
    Ok(())
}
pub(super) fn begin_if_never_invoked(
    capability: Arc<NativeCapability>,
    expected: &str,
) -> Result<(), String> {
    capability.require_live()?;
    let mut p = process()
        .lock()
        .map_err(|_| "construction process gate poisoned")?;
    if p.partial_construction.is_some() {
        return Ok(());
    }
    if p.initialization_attempt != 0
        || p.initialization_failure.is_some()
        || p.initializing
        || p.host.is_some()
    {
        return Ok(());
    }
    if p.host_leases != 0
        || p.lease_integrity_failed
        || p.fresh_setup_request.is_some()
        || p.root_reestablishment.is_some()
    {
        return Err("uninvoked constructor actual gate busy".into());
    }
    exact_staged_route(expected)?;
    p.partial_construction = Some(Arc::new(Construction::new(capability, expected, 0)));
    Ok(())
}
impl Construction {
    fn new(capability: Arc<NativeCapability>, expected: &str, attempt: u64) -> Self {
        Self {
            capability,
            expected: expected.into(),
            attempt,
            provider: Arc::new(Mutex::new(Some(QuestBrokerRuntimeProvider::default()))),
            broker: Mutex::new(Default::default()),
            callbacks: Mutex::new(None),
            cleanup: Mutex::new(None),
            cleanup_server: Mutex::new(None),
            server: Mutex::new(None),
            activation: Mutex::new(None),
            provider_entered: AtomicBool::new(false),
            own_preparation: AtomicU8::new(0),
        }
    }
    pub(super) fn bind_callbacks(&self, callbacks: JavaOwnerCallbacks) -> Result<(), String> {
        let mut slot = self
            .callbacks
            .lock()
            .map_err(|_| "construction callback custody poisoned")?;
        if slot.is_some() {
            return Err("construction callbacks already bound".into());
        }
        *slot = Some(callbacks);
        Ok(())
    }
    pub(super) fn enter_provider(&self) -> Result<(), String> {
        if self.provider_entered.swap(true, Ordering::SeqCst) {
            return Err("construction provider reentry".into());
        }
        Ok(())
    }
    pub(super) fn enter_own_preparation(&self) -> Result<(), String> {
        self.own_preparation
            .compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst)
            .map_err(|_| "construction Own preparation reentry")?;
        Ok(())
    }
    pub(super) fn complete_own_preparation(&self, selected: bool) -> Result<(), String> {
        self.own_preparation
            .compare_exchange(
                1,
                if selected { 3 } else { 2 },
                Ordering::SeqCst,
                Ordering::SeqCst,
            )
            .map_err(|_| "construction Own preparation changed")?;
        Ok(())
    }
    pub(super) fn require_host(&self, host: &Host) -> Result<(), String> {
        if self.expected != host.config_sha256
            || !Arc::ptr_eq(&self.provider, &host.provider)
            || !self
                .server
                .lock()
                .map_err(|_| "construction server poisoned")?
                .as_ref()
                .is_some_and(|s| Arc::ptr_eq(s, &host.server))
            || !self
                .activation
                .lock()
                .map_err(|_| "construction activation poisoned")?
                .as_ref()
                .is_some_and(|s| Arc::ptr_eq(s, &host.activation_server))
            || !self
                .cleanup_server
                .lock()
                .map_err(|_| "construction cleanup server poisoned")?
                .as_ref()
                .is_some_and(|s| Arc::ptr_eq(s, &host.cleanup_server))
        {
            return Err("published Host differs from retained constructed owners".into());
        }
        Ok(())
    }
    fn require_closing(&self, expected: &str, capability: &NativeCapability) -> Result<(), String> {
        capability.require_live()?;
        let p = process()
            .lock()
            .map_err(|_| "construction process gate poisoned")?;
        if p.initializing
            || !p.closing
            || p.host.is_some()
            || p.host_leases != 0
            || p.lease_integrity_failed
            || p.fresh_setup_request.is_some()
            || p.root_reestablishment.is_some()
            || self.expected != expected
            || self.capability.generation != capability.generation
            || self.capability.binding != capability.binding
            || !p
                .partial_construction
                .as_ref()
                .is_some_and(|held| std::ptr::eq(held.as_ref(), self))
        {
            return Err("partial constructor source/gate remains busy or changed".into());
        }
        exact_staged_route(expected)?;
        Ok(())
    }
    fn snapshot(&self) -> Result<Value, String> {
        // No checked-out component is interpreted as absence. An uncreated slot
        // is only recorded from this monotonic constructor's real registration.
        let broker: Value = serde_json::from_str(
            &self
                .broker
                .try_lock()
                .map_err(|_| "construction Broker busy")?
                .retained_source_json()
                .map_err(|_| "construction Broker snapshot unavailable")?,
        )
        .map_err(safe_decode)?;
        let evidence = {
            let provider = self
                .provider
                .try_lock()
                .map_err(|_| "construction provider busy")?;
            provider
                .as_ref()
                .ok_or("construction provider checked out")?
                .retained_construction_evidence()
                .map_err(|_| "construction provider snapshot unavailable")?
                .map(|raw| serde_json::from_str::<Value>(&raw).map_err(safe_decode))
                .transpose()?
        };
        if evidence
            .as_ref()
            .is_some_and(|v| v.get("media_pending_action").is_none_or(|p| !p.is_null()))
        {
            return Err("construction pending media effect requires actual owner cleanup".into());
        }
        let dispatch = match self
            .server
            .try_lock()
            .map_err(|_| "construction dispatch registration busy")?
            .as_ref()
        {
            None => None,
            Some(slot) => Some(
                serde_json::to_value(
                    slot.try_lock()
                        .map_err(|_| "construction dispatch busy")?
                        .as_ref()
                        .ok_or("construction dispatch checked out")?
                        .replay_snapshot(),
                )
                .map_err(safe_decode)?,
            ),
        };
        let activation = match self
            .activation
            .try_lock()
            .map_err(|_| "construction activation registration busy")?
            .as_ref()
        {
            None => None,
            Some(slot) => Some(
                serde_json::to_value(
                    slot.try_lock()
                        .map_err(|_| "construction activation busy")?
                        .as_ref()
                        .ok_or("construction activation checked out")?
                        .replay_snapshot(),
                )
                .map_err(safe_decode)?,
            ),
        };
        let cleanup_server = match self
            .cleanup_server
            .try_lock()
            .map_err(|_| "construction cleanup registration busy")?
            .as_ref()
        {
            None => None,
            Some(slot) => Some(
                serde_json::to_value(
                    slot.try_lock()
                        .map_err(|_| "construction cleanup server busy")?
                        .as_ref()
                        .ok_or("construction cleanup server checked out")?
                        .replay_snapshot(),
                )
                .map_err(safe_decode)?,
            ),
        };
        let cleanup = self
            .cleanup
            .try_lock()
            .map_err(|_| "construction cleanup registration busy")?
            .as_ref()
            .map(|owner| owner.construction_snapshot())
            .transpose()?;
        Ok(
            json!({"$schema":"rusty.quest.embedded_duplex.partial_construction_source.v1",
            "attempt":self.attempt,"provider_entered":self.provider_entered.load(Ordering::SeqCst),
            "own_preparation":self.own_preparation.load(Ordering::SeqCst),"broker":broker,"provider":evidence,
            "owner_dispatch_replay":dispatch,"activation_replay":activation,"cleanup_replay":cleanup_server,
            "cleanup_preparations":cleanup,"host_published":false,"new_owner_effects_dispatched":0}),
        )
    }
}

pub(super) fn retire(expected: &str, capability: &NativeCapability) -> Result<String, String> {
    let held = current()?.ok_or("retained partial constructor absent")?;
    {
        let mut p = process()
            .lock()
            .map_err(|_| "construction process gate poisoned")?;
        if p.initializing
            || p.host.is_some()
            || p.host_leases != 0
            || p.lease_integrity_failed
            || p.root_reestablishment.is_some()
        {
            return Err("partial constructor original remains active".into());
        }
        p.closing = true;
    }
    held.require_closing(expected, capability)?;
    // Actual local carrier/camera/epoch owners, independently of constructor's
    // return status. No error text or missing Host grants this physical fact.
    crate::own_stereo_capture_runtime::require_construction_physical_terminal()?;
    let source = held.snapshot()?;
    let body = json!({"$schema":"rusty.quest.embedded_duplex.partial_construction_closure.v1",
        "app_process_generation":capability.binding.generation,"app_record_sha256":capability.binding.record_sha256,
        "native_executor_generation":capability.generation,"config_sha256":expected,"source":source,
        "construction_disposition":"not_committed","new_media_starts":0,"physical_source_terminal":true,
        "old_outcomes_preserved":true,"future_writable_restore":"fenced_pending_supported_owner_reconciliation"});
    let raw = body.to_string();
    let digest = format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(&raw)
    );
    let callbacks = held
        .callbacks
        .lock()
        .map_err(|_| "construction callbacks poisoned")?
        .clone();
    // The only no-store branch is actual factory NEVER ENTERED, with no owner
    // allocation. This is source-written sequencing, not lookup(None) inference.
    let ack = if held.provider_entered.load(Ordering::SeqCst) {
        let actual = callbacks.ok_or("entered constructor callback custody absent")?;
        let ack = actual.persist_native_construction_retirement(&raw)?;
        const FIELDS: [&str; 7] = [
            "$schema",
            "body_sha256",
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
            "storage_identity",
        ];
        if !ack
            .as_object()
            .is_some_and(|o| o.len() == FIELDS.len() && FIELDS.iter().all(|k| o.contains_key(*k)))
            || ack["$schema"]
                != "rusty.quest.embedded_duplex.partial_construction_closure_retained.v1"
            || ack["body_sha256"] != digest
            || !ack["storage_identity"]
                .as_str()
                .is_some_and(|s| !s.is_empty())
            || [
                "app_process_generation",
                "app_record_sha256",
                "native_executor_generation",
                "config_sha256",
            ]
            .iter()
            .any(|k| ack[*k] != body[*k])
        {
            return Err("partial constructor durable custody differs".into());
        }
        Some(ack)
    } else {
        None
    };
    held.require_closing(expected, capability)?;
    if held.snapshot()? != source {
        return Err("partial constructor source changed during handoff".into());
    }
    let proof = json!({"$schema":"rusty.quest.embedded_duplex.no_media_retirement.v3",
        "native_runtime_binding":"partial_construction","disposition":"partial_construction_closed",
        "app_process_generation":capability.binding.generation,"app_record_sha256":capability.binding.record_sha256,
        "native_executor_generation":capability.generation,"config_sha256":expected,
        "construction_disposition":"not_committed","closure_body_sha256":digest,"durable_closure":ack,
        "provider_entered":held.provider_entered.load(Ordering::SeqCst),"new_media_starts":0,
        "old_outcomes_preserved":true,"future_writable_restore":if ack.is_some(){"fenced_pending_supported_owner_reconciliation"}else{"owner_factory_not_entered"}});
    // Infallible bookkeeping only after exact data handoff. Native capability
    // retirement remains a separate real fence operation and may still fail.
    {
        let mut p = process()
            .lock()
            .map_err(|_| "construction process gate poisoned")?;
        let route = prepared_route()
            .lock()
            .map_err(|_| "construction staged route poisoned")?;
        if p.initializing
            || !p.closing
            || p.host.is_some()
            || p.host_leases != 0
            || p.lease_integrity_failed
            || p.root_reestablishment.is_some()
            || !p
                .partial_construction
                .as_ref()
                .is_some_and(|h| Arc::ptr_eq(h, &held))
            || route.as_ref().map(|(sha, _)| sha.as_str()) != Some(expected)
        {
            return Err("partial constructor final ownership changed".into());
        }
        // Keep arena through actual capability retirement. A failed fence never
        // discards actual owners nor reports successful retirement.
        p.last_partial_retirement = Some(proof.clone());
    }
    capability.retire()?;
    {
        let mut p = process()
            .lock()
            .map_err(|_| "construction process gate poisoned")?;
        let mut route = prepared_route()
            .lock()
            .map_err(|_| "construction staged route poisoned")?;
        p.partial_construction.take();
        route.take();
        p.last_closed_sha256 = Some(expected.into());
        p.last_closed_runtime_configuration_binding = None;
        p.initialization_failure = None;
        p.retired_fresh_owner_fenced = ack.is_some(); // old M history is retained, NOT restored
    }
    drop(held);
    Ok(proof.to_string())
}
