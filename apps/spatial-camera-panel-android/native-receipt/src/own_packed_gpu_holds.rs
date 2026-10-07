//! Process-owned bounded physical consumer table. Registration precedes driver submission.
use crate::own_packed_pool_policy::PhysicalUsePhase;
use crate::{
    own_packed_pool::{serial, PackedLease},
    stereo_input_set::SourceEpoch,
};
use ash::vk;
use std::{
    collections::HashMap,
    sync::{Mutex, OnceLock},
};
struct Quota {
    epoch: SourceEpoch,
    limit: usize,
    holds: HashMap<u64, Hold>,
}
enum Hold {
    Encoder(PackedLease),
    Vk {
        lease: PackedLease,
        device: ash::Device,
        fence: vk::Fence,
        polling: bool,
        phase: PhysicalUsePhase,
        sdk_submission: Option<(u64, u64)>,
    },
}
fn quotas() -> &'static Mutex<HashMap<u64, Quota>> {
    static Q: OnceLock<Mutex<HashMap<u64, Quota>>> = OnceLock::new();
    Q.get_or_init(|| Mutex::new(HashMap::new()))
}
pub(crate) fn install_pool(pool: u64, epoch: SourceEpoch, limit: usize) -> Result<(), String> {
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    if limit == 0 || q.contains_key(&pool) {
        return Err("GPU quota unavailable".into());
    }
    q.insert(
        pool,
        Quota {
            epoch,
            limit,
            holds: HashMap::new(),
        },
    );
    Ok(())
}
fn reserve(lease: &PackedLease) -> Result<Option<(u64, u64)>, String> {
    let v = lease.contents().version;
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    let owner = q
        .get_mut(&v.pool_generation)
        .ok_or("pool quota unavailable")?;
    if owner.epoch.process_generation != v.process_generation
        || owner.epoch.source_generation != v.source_generation
    {
        return Err("stale content or GPU hold capacity exhausted".into());
    }
    if owner.holds.len() >= owner.limit {
        return Ok(None);
    }
    let token = serial()?;
    owner.holds.insert(token, Hold::Encoder(lease.clone()));
    Ok(Some((v.pool_generation, token)))
}
pub(crate) fn reserve_encoder(lease: &PackedLease) -> Result<Option<u64>, String> {
    let v = lease.contents().version;
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    let owner = q
        .get_mut(&v.pool_generation)
        .ok_or("pool quota unavailable")?;
    if owner.epoch.process_generation != v.process_generation
        || owner.epoch.source_generation != v.source_generation
    {
        return Err("stale encoder content".into());
    }
    // One admitted GPU hold is always reserved for Own renderer progress. An encoder
    // worker/mailbox can saturate only its remaining quota, never that Own reserve.
    let encoder_holds = owner
        .holds
        .values()
        .filter(|h| matches!(h, Hold::Encoder(_)))
        .count();
    if owner.holds.len() >= owner.limit || encoder_holds >= owner.limit.saturating_sub(1) {
        return Ok(None);
    }
    let token = serial()?;
    owner.holds.insert(token, Hold::Encoder(lease.clone()));
    Ok(Some(token))
}
// Only encoder's unsubmitted-drop or actual observed-fence+import teardown path calls this.
pub(crate) fn release_encoder(token: u64) -> Result<(), String> {
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    for owner in q.values_mut() {
        if matches!(owner.holds.get(&token), Some(Hold::Encoder(_))) {
            owner.holds.remove(&token);
            return Ok(());
        }
    }
    Err("encoder reservation stale".into())
}
pub(crate) unsafe fn register_vk_pending(
    lease: PackedLease,
    device: ash::Device,
    fence: vk::Fence,
) -> Result<u64, String> {
    if fence == vk::Fence::null() {
        return Err("null submission fence".into());
    }
    match device.get_fence_status(fence) {
        Ok(false) => {}
        Ok(true) => {
            return Err("submission fence must actually be unsignaled before reservation".into())
        }
        Err(e) => return Err(format!("fence prerequisite {e:?}")),
    }
    let (pool, token) = reserve(&lease)?.ok_or("GPU hold capacity exhausted")?;
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    let owner = q.get_mut(&pool).ok_or("pool quota disappeared")?;
    owner.holds.insert(
        token,
        Hold::Vk {
            lease,
            device,
            fence,
            polling: false,
            phase: PhysicalUsePhase::Prepared,
            sdk_submission: None,
        },
    );
    Ok(token)
}
pub(crate) unsafe fn poll_vk_retired(token: u64) -> Result<bool, String> {
    let observation = {
        let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
        let mut found = None;
        for (pool, owner) in q.iter_mut() {
            if let Some(Hold::Vk {
                device,
                fence,
                polling,
                phase,
                ..
            }) = owner.holds.get_mut(&token)
            {
                if *polling || !phase.may_observe() {
                    return Ok(false);
                }
                *polling = true;
                found = Some((*pool, device.clone(), *fence));
                break;
            }
        }
        found.ok_or("Vk hold token unavailable")?
    };
    // No registry mutex is held during the platform observation. Fence remains owned/pinned.
    let observed = observation.1.get_fence_status(observation.2);
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    let owner = q.get_mut(&observation.0).ok_or("GPU pool disappeared")?;
    match observed {
        Ok(true) => {
            owner.holds.remove(&token);
            Ok(true)
        }
        Ok(false) => {
            if let Some(Hold::Vk { polling, .. }) = owner.holds.get_mut(&token) {
                *polling = false;
            }
            Ok(false)
        }
        Err(e) => {
            if let Some(Hold::Vk { polling, phase, .. }) = owner.holds.get_mut(&token) {
                *polling = false;
                *phase = PhysicalUsePhase::Quarantined;
            }
            Err(format!("Vk fence failed; hold quarantined {e:?}"))
        }
    }
}
// Renderer recovery cannot consume or waive any retained Vk use. Encoder owners remain independent.
pub(crate) fn renderer_holds_retired(epoch: SourceEpoch) -> bool {
    quotas().lock().is_ok_and(|q| {
        q.values()
            .filter(|owner| owner.epoch == epoch)
            .all(|owner| {
                owner
                    .holds
                    .values()
                    .all(|hold| matches!(hold, Hold::Encoder(_)))
            })
    })
}
pub(crate) fn retire_pool_if_empty(pool: u64) -> Result<bool, String> {
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    let Some(owner) = q.get(&pool) else {
        return Ok(true);
    };
    if !owner.holds.is_empty() {
        return Ok(false);
    }
    q.remove(&pool);
    Ok(true)
}

pub(crate) unsafe fn submit_vk_pending(
    token: u64,
    queue: vk::Queue,
    submits: &[vk::SubmitInfo<'_>],
) -> Result<(), String> {
    let platform = {
        let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
        let mut entry = None;
        for owner in q.values_mut() {
            if let Some(Hold::Vk {
                device,
                fence,
                phase,
                ..
            }) = owner.holds.get_mut(&token)
            {
                if !phase.enter() {
                    return Err("submission token already entered/quarantined".into());
                }
                entry = Some((device.clone(), *fence));
                break;
            }
        }
        entry.ok_or("submission hold unavailable")?
    };
    match platform.0.queue_submit(queue, submits, platform.1) {
        Ok(()) => Ok(()),
        Err(e) => {
            let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
            for owner in q.values_mut() {
                if let Some(Hold::Vk { phase, .. }) = owner.holds.get_mut(&token) {
                    *phase = PhysicalUsePhase::Quarantined;
                }
            }
            Err(format!(
                "native queue submission failed; exact contents remain quarantined {e:?}"
            ))
        }
    }
}
pub(crate) fn cancel_vk_unsubmitted(token: u64) -> Result<bool, String> {
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    for owner in q.values_mut() {
        let cancellable = matches!(
            owner.holds.get(&token),
            Some(Hold::Vk {
                phase: PhysicalUsePhase::Prepared,
                polling: false,
                ..
            })
        );
        if cancellable {
            owner.holds.remove(&token);
            return Ok(true);
        }
        if owner.holds.contains_key(&token) {
            return Ok(false);
        }
    }
    Err("Vk hold token unavailable".into())
}

pub(crate) fn mark_vk_submission_entered(token: u64) -> Result<(), String> {
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    for owner in q.values_mut() {
        if let Some(Hold::Vk { phase, .. }) = owner.holds.get_mut(&token) {
            if !phase.enter() {
                return Err("submission token already entered/quarantined".into());
            }
            return Ok(());
        }
    }
    Err("Vk hold token unavailable".into())
}

pub(crate) fn mark_vk_sdk_submission_entered(
    token: u64,
    session: u64,
    request: u64,
) -> Result<(), String> {
    if session == 0 || request == 0 {
        return Err("SDK submission identity invalid".into());
    }
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    for owner in q.values_mut() {
        if let Some(Hold::Vk {
            phase,
            sdk_submission,
            ..
        }) = owner.holds.get_mut(&token)
        {
            if !phase.enter() {
                return Err("submission token already entered/quarantined".into());
            }
            *sdk_submission = Some((session, request));
            return Ok(());
        }
    }
    Err("submission hold unavailable".into())
}
#[cfg(any(rq_environment_depth_spatial_sdk_api_layer, test))]
pub(crate) fn cancel_vk_sdk_unsubmitted(
    token: u64,
    proof: &crate::spatial_sdk_depth_handoff::SpatialUnsubmittedProof,
) -> Result<bool, String> {
    use ash::vk::Handle;
    let mut q = quotas().lock().map_err(|_| "GPU registry poisoned")?;
    for owner in q.values_mut() {
        let cancellable = match owner.holds.get(&token) {
            Some(Hold::Vk {
                phase: PhysicalUsePhase::Entered,
                polling: false,
                sdk_submission: Some((session, request)),
                fence,
                ..
            }) => proof.matches(*session, *request, fence.as_raw()),
            _ => false,
        };
        if cancellable {
            owner.holds.remove(&token);
            return Ok(true);
        }
        if owner.holds.contains_key(&token) {
            return Ok(false);
        }
    }
    Err("Vk hold token unavailable".into())
}
