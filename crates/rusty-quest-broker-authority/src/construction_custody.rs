//! Non-cloneable custody of actual owners allocated by the selected constructor.
//! No mutation, restore, or owner-transfer capability is exported by this object.
use super::*;

#[derive(Default)]
/// Retains only the real constructor's owner handles through later failure.
/// It exposes complete data observation, never cloned mutation authority.
pub struct QuestBrokerConstructionCustody {
    pub(super) entered: bool,
    pub(super) peer: Option<Arc<RwLock<ManifoldPeerRuntimeHost>>>,
    pub(super) broker: Option<Arc<RwLock<ManifoldBrokerRuntime>>>,
    pub(super) control: Option<ManifoldBrokerControlLeaseAuthority>,
    pub(super) unpublished:
        Option<rusty_manifold_broker_adapter::ManifoldBrokerUnpublishedConstruction>,
}
impl QuestBrokerConstructionCustody {
    pub(super) fn capture_peer(&mut self, peer: &Option<Arc<RwLock<ManifoldPeerRuntimeHost>>>) {
        self.peer = peer.as_ref().map(Arc::clone);
    }
    pub(super) fn capture_broker(&mut self, broker: &Arc<RwLock<ManifoldBrokerRuntime>>) {
        self.broker = Some(Arc::clone(broker));
    }
    /// Complete current source observation from the SAME constructed owners.
    /// An incompletely joined peer snapshot is retained as data, never passed to
    /// a fresh constructor/restore or claimed as an ordinary current snapshot.
    pub fn retained_source_json(&self) -> Result<String, QuestBrokerRuntimeError> {
        let peer = self
            .peer
            .as_ref()
            .map(|h| {
                let held = h
                    .try_read()
                    .map_err(|_| QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
                serde_json::to_value(held.snapshot()).map_err(QuestBrokerRuntimeError::Encode)
            })
            .transpose()?;
        let broker = self
            .broker
            .as_ref()
            .map(|h| {
                let held = h
                    .try_read()
                    .map_err(|_| QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
                serde_json::to_value(held.evidence()).map_err(QuestBrokerRuntimeError::Encode)
            })
            .transpose()?;
        let broker = match broker {
            Some(value) => Some(value),
            None => self
                .unpublished
                .as_ref()
                .and_then(|u| u.runtime_evidence())
                .map(serde_json::to_value)
                .transpose()
                .map_err(QuestBrokerRuntimeError::Encode)?,
        };
        let unpublished = self
            .unpublished
            .as_ref()
            .map(|u| {
                u.source_observation()
                    .map_err(QuestBrokerRuntimeError::RuntimeState)
            })
            .transpose()?;
        serde_json::to_string(&serde_json::json!({
            "$schema":"rusty.quest.broker.construction_custody.v1",
            "factory_entered":self.entered,"peer_owner_created":self.peer.is_some(),
            "broker_owner_created":broker.is_some(),"peer_source":peer,"broker_source":broker,
            "control_lease_source":self.control.as_ref().map(|c|c.evidence()),"unpublished_source":unpublished,
            "writable_restore":"unsupported_preserve_actual_history"
        })).map_err(QuestBrokerRuntimeError::Encode)
    }
}

impl QuestBrokerRuntimeProvider {
    /// Actual provider ownership observation, including a constructor whose
    /// final response formatting failed. None is not proof of no peer owner;
    /// the separate construction custody is always retained alongside it.
    pub fn retained_construction_evidence(
        &self,
    ) -> Result<Option<String>, QuestBrokerRuntimeError> {
        self.runtime
            .as_ref()
            .map(QuestBrokerAuthorityRuntime::evidence_json)
            .transpose()
    }
}
