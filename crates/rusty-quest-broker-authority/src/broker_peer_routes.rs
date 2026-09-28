//! Fixed process-owned Common-LAN route joins over the actual live Broker.
use super::*;
use rusty_manifold_peer::{
    ManifoldAcceptedPeerSessionV2, ManifoldCommonLanPairMediaRouteRequest,
    ManifoldPairMediaRouteRequest, ManifoldPairMediaRouteRequestV2, ManifoldPairMediaRouteReceiptV2,
    ManifoldPairMediaRouteTerminationRequestV2, ManifoldPairMediaRouteTerminationAction,
    ManifoldPairMediaRouteMutationReceipt, ManifoldPairMediaRouteCleanupCompletionRequestV2,
    ManifoldPairMediaRouteCleanupReceiptV2, ManifoldPairMediaRouteLifecycleStatus,
    ManifoldPairMediaRouteCleanupStatus, pair_media_route_issue_params_digest_v2,
    pair_media_route_termination_params_digest_v2, pair_media_route_cleanup_params_digest_v2,
    PAIR_MEDIA_ROUTE_REQUEST_SCHEMA, PAIR_MEDIA_ROUTE_TERMINATION_REQUEST_V2_SCHEMA,
    PAIR_MEDIA_ROUTE_CLEANUP_REQUEST_V2_SCHEMA,
};
use rusty_manifold_model::{ManifoldMediaRouteLegDescriptor, MANIFOLD_MEDIA_ROUTE_LEG_SCHEMA};
use rusty_manifold_media_session::ManifoldMediaSessionLifecycleStatus;
use rusty_manifold_peer_runtime_host::{ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionRequest,
    ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionReceipt,PEER_RUNTIME_TRUSTED_MEDIA_REVOKER_LEASE_ADOPTION_REQUEST_SCHEMA};
use rusty_manifold_runtime_host::{ManifoldRuntimeControlLeaseAdoptionRequest,ManifoldRuntimeControlLeaseAuthorityApplication,HOST_CONTROL_LEASE_ADOPTION_REQUEST_SCHEMA};

/// Process-retained dedicated generic revoker authority. It never aliases the
/// Broker's occupied original-holder scope or resets an uncertain issue.
pub struct QuestConcurrentRevokerAuthority {
    current:ManifoldAuthoritySnapshot,
    pending:Option<ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionRequest>,
    last:Option<ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionReceipt>,
    retirement_pending:Option<ManifoldRuntimeControlLeaseAdoptionRequest>,
    retirement_receipts:Vec<rusty_manifold_runtime_host::ManifoldRuntimeControlLeaseAdoptionReceipt>,
}

fn rejected()->QuestBrokerRuntimeError {QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected}
fn id(text:String)->Result<DottedId,QuestBrokerRuntimeError>{DottedId::new(text).map_err(|_|rejected())}

impl QuestBrokerRuntimeProvider {
    /// Last actual revoker authority clock used by the OS-backed clock reader.
    pub fn concurrent_revoker_clock(&self)->Option<&ManifoldClockSnapshot>{
        self.runtime.as_ref()?.concurrent_revoker_authority.as_ref().map(|a|&a.current.clock_snapshot)
    }
    /// Issue and adopt one independent trusted revoker lease using a real
    /// owner-read clock and the sole authenticated enrolled revoker. The generic
    /// owner application and pending peer adoption remain held on failure.
    pub fn adopt_concurrent_peer_revoker(&mut self,clock:&ManifoldClockSnapshot,now_ms:u64,entropy_hex:&str)
        ->Result<ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionReceipt,QuestBrokerRuntimeError>{
        parse_entropy_hex(entropy_hex).map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
        let owner=self.runtime.as_mut().ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        let shared=owner.peer_runtime_host.clone().ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut peer=shared.write().map_err(|_|QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let broker=owner.runtime.read().map_err(|_|QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
        let original=&broker.control_lease_authority_snapshot().clock_snapshot;
        if clock.clock_domain!=original.clock_domain||clock.clock_epoch_id!=original.clock_epoch_id||clock.sequence<original.sequence
            ||clock.health!=ClockHealth::Healthy||clock.monotonic_elapsed_ns<original.monotonic_elapsed_ns
            ||u64::try_from(clock.wall_unix_ms).ok()!=Some(now_ms)||clock.read_uncertainty_ns>1_000_000_000{return Err(QuestBrokerRuntimeError::InvalidAuthorityClock);}
        let revokers=&peer.snapshot().trust_policy.trusted_media_revoker_ids;
        let [revoker]=revokers.as_slice() else{return Err(rejected());};let revoker=revoker.clone();
        let scope=peer.snapshot().trust_policy.media_runtime_lease_scope_id.clone();
        if owner.concurrent_revoker_authority.is_none(){
            // A distinct, process-founded authority: no copied active Broker
            // leases, caller grants, broad Broker capabilities or fresh retry.
            let suffix=owner.provider_epoch_id.as_str();
            let capability=DottedId::new("capability.quest.concurrent.media-revoker.control-lease").expect("fixed capability");
            let current=ManifoldAuthoritySnapshot{schema_id:schema("rusty.manifold.authority.snapshot.v1"),
                authority_id:id(format!("authority.quest.concurrent.revoker.{suffix}"))?,authority_revision:Revision::INITIAL,
                host_manifest:ManifoldHostManifest{schema_id:schema("rusty.manifold.host.manifest.v1"),
                    host_id:id(format!("host.quest.concurrent.revoker.{suffix}"))?,authority_role:AuthorityRole::Primary,
                    host_category:Some(DottedId::new("host.quest.concurrent.revoker-control-owner").expect("fixed category")),clock_domain:clock.clock_domain.clone(),
                    endpoints:Vec::new(),capabilities:vec![capability],supported_backends:Vec::new(),permissions:Vec::new(),lifecycle_limits:Vec::new(),missing_requirements:Vec::new()},
                clock_snapshot:clock.clone(),stream_registry:ManifoldStreamRegistrySnapshot{schema_id:schema("rusty.manifold.stream.registry_snapshot.v1"),registry_revision:Revision::INITIAL,streams:Vec::new()},
                module_runtime_states:Vec::new(),command_ids:Vec::new(),command_descriptors:Vec::new(),active_leases:Vec::new(),revoked_control_lease_tombstones:Vec::new(),active_stream_subscriptions:Vec::new()};
            current.validate_authority_links().map_err(|_|rejected())?;
            owner.concurrent_revoker_authority=Some(QuestConcurrentRevokerAuthority{current,pending:None,last:None,retirement_pending:None,retirement_receipts:Vec::new()});
        }
        let authority=owner.concurrent_revoker_authority.as_mut().ok_or_else(rejected)?;
        if let Some(last)=&authority.last{
            if last.lease.holder_id!=revoker||last.lease.scope!=scope{return Err(rejected());}
            if last.lease.expires_at_ms>now_ms&&peer.snapshot().media_command_runtime.leases.iter().any(|l|l==&last.lease){return Ok(last.clone());}
            if authority.retirement_pending.is_none() {
                let prior=authority.current.clone();
                let review=prior.review_authority_expiry_sweep(rusty_manifold_model::ManifoldAuthorityExpirySweepRequest {
                    schema_id:schema("rusty.manifold.authority.expiry_sweep_request.v1"),
                    request_id:id(format!("request.quest.concurrent.revoker-expiry.{entropy_hex}"))?,requester_id:revoker.clone(),
                    expected_authority_revision:prior.authority_revision,expected_registry_revision:prior.stream_registry.registry_revision,
                    sweep_reason:DottedId::new("reason.quest.concurrent.revoker-expired-cleanup").expect("fixed reason"),requested_at_ms:now_ms,
                },clock.clone(),vec![owner.provider_epoch_id.clone()]).map_err(|_|rejected())?;
                let application=prior.apply_authority_expiry_sweep_review(review).map_err(|_|rejected())?;
                authority.current=application.applied_snapshot.clone().ok_or_else(rejected)?;
                authority.retirement_pending=Some(ManifoldRuntimeControlLeaseAdoptionRequest {
                    schema_id:schema(HOST_CONTROL_LEASE_ADOPTION_REQUEST_SCHEMA),adoption_id:id(format!("adoption.quest.concurrent.revoker-expiry.{entropy_hex}"))?,
                    expected_host_authority_revision:peer.snapshot().media_command_runtime.authority_revision,prior_authority_snapshot:prior,
                    application:ManifoldRuntimeControlLeaseAuthorityApplication::Expiry(application),
                });
            }
            let retired=peer.retire_trusted_media_revoker_control_lease(authority.retirement_pending.as_ref().ok_or_else(rejected)?,now_ms).map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
            authority.retirement_receipts.push(retired);authority.retirement_pending=None;authority.last=None;
        }
        if authority.pending.is_none(){
            let prior=authority.current.clone();
            if clock.sequence<prior.clock_snapshot.sequence||clock.monotonic_elapsed_ns<prior.clock_snapshot.monotonic_elapsed_ns{return Err(QuestBrokerRuntimeError::InvalidAuthorityClock);}
            let review=prior.review_lease_request(ManifoldControlLeaseRequest{schema_id:schema("rusty.manifold.command.lease_request.v1"),
                request_id:id(format!("request.quest.concurrent.revoker-lease.{entropy_hex}"))?,holder_id:revoker,scope,expected_revision:prior.authority_revision,
                requested_ttl_ms:30_000,required_capability:DottedId::new("capability.quest.concurrent.media-revoker.control-lease").expect("fixed capability"),safety_class:SafetyClass::BoundedMutation},
                clock.clone(),vec![owner.provider_epoch_id.clone()]).map_err(|_|rejected())?;
            if review.accepted.is_none(){return Err(rejected());}
            let application=prior.apply_control_lease_authority_review(review).map_err(|_|rejected())?;
            authority.current=application.applied_snapshot.clone().ok_or_else(rejected)?;
            authority.pending=Some(ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionRequest{schema_id:schema(PEER_RUNTIME_TRUSTED_MEDIA_REVOKER_LEASE_ADOPTION_REQUEST_SCHEMA),
                expected_peer_event_sequence:peer.snapshot().event_sequence,runtime_adoption:ManifoldRuntimeControlLeaseAdoptionRequest{
                    schema_id:schema(HOST_CONTROL_LEASE_ADOPTION_REQUEST_SCHEMA),adoption_id:id(format!("adoption.quest.concurrent.revoker.{entropy_hex}"))?,
                    expected_host_authority_revision:peer.snapshot().media_command_runtime.authority_revision,prior_authority_snapshot:prior,
                    application:ManifoldRuntimeControlLeaseAuthorityApplication::Issue(application)}});
        }
        let request=authority.pending.as_ref().ok_or_else(rejected)?;
        let receipt=peer.adopt_trusted_media_revoker_control_lease(request,now_ms).map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        authority.last=Some(receipt.clone());authority.pending=None;Ok(receipt)
    }
    /// Derive a directional route from the current accepted media, native-selected
    /// paired session, signed transport and actual Start-admitted inner lease.
    /// No caller route leg, deadline, grant, authority revision or JSON is used.
    pub fn issue_concurrent_peer_route(&mut self,peer_session_id:&DottedId,now_ms:u64,entropy_hex:&str)
        ->Result<ManifoldPairMediaRouteReceiptV2,QuestBrokerRuntimeError>{
        parse_entropy_hex(entropy_hex).map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
        let owner=self.runtime.as_mut().ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        let shared=owner.peer_runtime_host.clone().ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut peer=shared.write().map_err(|_|QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let broker=owner.runtime.read().map_err(|_|QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
        let state=peer.snapshot();
        if !peer.validate_peer_session_v2(peer_session_id,now_ms).current{return Err(rejected());}
        let session=state.peer_sessions.sessions.iter().find_map(|s|match s{
            ManifoldAcceptedPeerSessionV2::CommonLan(s) if &s.proposal.session_id==peer_session_id&&!s.revoked=>Some(s.clone()),_=>None}).ok_or_else(rejected)?;
        let medias:Vec<_>=state.media_sessions.sessions.iter().filter(|m|m.lifecycle_status==ManifoldMediaSessionLifecycleStatus::Current&&m.expires_at_ms>now_ms).collect();
        let [media]=medias.as_slice() else{return Err(rejected());};let media=(*media).clone();
        let binding=owner.media_bindings.iter().find(|b|b.manifold.descriptor.platform_runtime_spec_id==media.platform_runtime_spec_id).ok_or_else(rejected)?;
        let descriptor=&binding.manifold.descriptor;
        if descriptor.source_ids.len()!=1||descriptor.route_ids.len()!=1||descriptor.sink_ids.len()!=1||descriptor.stream_ids.is_empty(){return Err(rejected());}
        let lease=state.media_command_runtime.leases.iter().find(|l|l.lease_id==media.runtime_lease_id&&l.holder_id==media.runtime_client_id&&l.expires_at_ms>now_ms).ok_or_else(rejected)?.clone();
        let existing=state.pair_media_routes.routes.iter().any(|r|r.platform_runtime_spec_id()==&media.platform_runtime_spec_id);
        let request_id=id(if existing{format!("request.quest.concurrent.route.r.{:020}.{entropy_hex}",state.pair_media_routes.authority_revision.get())}else{format!("request.quest.concurrent.route.{}",media.admission_grant_id)})?;
        let command_id=id(format!("request.quest.concurrent.route-command.{entropy_hex}"))?;
        let expires_at_ms=now_ms.checked_add(180_000).ok_or_else(rejected)?.min(media.expires_at_ms).min(lease.expires_at_ms).min(session.proposal.expires_at_ms);
        if expires_at_ms<=now_ms{return Err(rejected());}
        let route_leg=ManifoldMediaRouteLegDescriptor{schema_id:schema(MANIFOLD_MEDIA_ROUTE_LEG_SCHEMA),
            leg_id:descriptor.route_ids[0].clone(),leg_revision:descriptor.authority_revision,
            source_peer_id:session.proposal.subject_peer_id.clone(),sink_peer_id:session.proposal.candidate_peer_id.clone(),
            source_id:descriptor.source_ids[0].clone(),processor_ids:descriptor.processor_ids.clone(),route_id:descriptor.route_ids[0].clone(),sink_id:descriptor.sink_ids[0].clone(),stream_ids:descriptor.stream_ids.clone()};
        let request=ManifoldPairMediaRouteRequestV2::CommonLan(ManifoldCommonLanPairMediaRouteRequest{
            request:ManifoldPairMediaRouteRequest{schema_id:schema(PAIR_MEDIA_ROUTE_REQUEST_SCHEMA),request_id,
                expected_authority_revision:state.pair_media_routes.authority_revision,
                expected_peer_session_authority_revision:state.peer_sessions.authority_revision,
                expected_media_acceptance_authority_revision:state.media_sessions.authority_revision,
                runtime_command_request_id:command_id.clone(),expected_runtime_lease_expires_at_ms:lease.expires_at_ms,
                peer_session_id:peer_session_id.clone(),media_session_decision_id:media.decision_id,
                route_leg,expires_at_ms},transport:session.proposal.transport});
        let command=ManifoldRuntimeCommandRequest{schema_id:schema(HOST_COMMAND_REQUEST_SCHEMA),request_id:command_id,
            expected_authority_revision:state.media_command_runtime.authority_revision,requester_id:media.runtime_client_id,
            command_id:DottedId::new(PAIR_MEDIA_ROUTE_ISSUE_COMMAND).expect("fixed command"),lease_id:Some(lease.lease_id),
            params_digest:Some(match &request{ManifoldPairMediaRouteRequestV2::CommonLan(value)=>pair_media_route_issue_params_digest_v2(value).map_err(|_|rejected())?,_=>return Err(rejected())}),issued_at_ms:now_ms,expires_at_ms};
        let receipt=peer.review_pair_media_route_v2_with_live_broker_runtime(&broker,&request,&command,now_ms).map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        match &receipt{ManifoldPairMediaRouteReceiptV2::CommonLan(v) if v.route.is_some()&&v.rejection_reason.is_none()=>Ok(receipt),_=>Err(rejected())}
    }
    /// Terminalize the sole current route under its actual original holder, or
    /// the currently trusted revoker and live revoker lease for Revoke.
    pub fn terminate_concurrent_peer_route(&mut self,revoke:bool,now_ms:u64,entropy_hex:&str)
        ->Result<ManifoldPairMediaRouteMutationReceipt,QuestBrokerRuntimeError>{
        parse_entropy_hex(entropy_hex).map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
        let owner=self.runtime.as_mut().ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        let shared=owner.peer_runtime_host.clone().ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut peer=shared.write().map_err(|_|QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let broker=owner.runtime.read().map_err(|_|QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
        let state=peer.snapshot();let routes:Vec<_>=state.pair_media_routes.routes.iter().filter(|r|*r.lifecycle_status()==ManifoldPairMediaRouteLifecycleStatus::Current).collect();
        let [route]=routes.as_slice() else{return Err(rejected());};let route=(*route).clone();
        let leases:Vec<_>=state.media_command_runtime.leases.iter().filter(|l|l.expires_at_ms>now_ms&&l.scope==state.trust_policy.media_runtime_lease_scope_id&&
            if revoke{state.trust_policy.trusted_media_revoker_ids.contains(&l.holder_id)}else{&l.holder_id==route.authority_client_id()&&&l.lease_id==route.authority_runtime_lease_id()}).collect();
        let [lease]=leases.as_slice() else{return Err(rejected());};let lease=(*lease).clone();
        let command_id=id(format!("request.quest.concurrent.route-terminate-command.{entropy_hex}"))?;
        // The current route owner appends termination identities without sorting
        // its sorted replay guard. Use increasing actual authority revisions in
        // a prefix after issuance identities, preserving that owner invariant.
        let request=ManifoldPairMediaRouteTerminationRequestV2{schema_id:schema(PAIR_MEDIA_ROUTE_TERMINATION_REQUEST_V2_SCHEMA),request_id:id(format!("request.quest.concurrent.route.z.{:020}.{entropy_hex}",state.pair_media_routes.authority_revision.get()))?,
            expected_authority_revision:state.pair_media_routes.authority_revision,runtime_command_request_id:command_id.clone(),grant_id:route.grant_id().clone(),
            expected_authority_provider_epoch_id:route.authority_provider_epoch_id().clone(),expected_platform_runtime_spec_id:route.platform_runtime_spec_id().clone(),
            action:if revoke{ManifoldPairMediaRouteTerminationAction::Revoke}else{ManifoldPairMediaRouteTerminationAction::Stop}};
        let command=ManifoldRuntimeCommandRequest{schema_id:schema(HOST_COMMAND_REQUEST_SCHEMA),request_id:command_id,expected_authority_revision:state.media_command_runtime.authority_revision,
            requester_id:lease.holder_id,command_id:DottedId::new(if revoke{PAIR_MEDIA_ROUTE_REVOKE_COMMAND}else{PAIR_MEDIA_ROUTE_STOP_COMMAND}).expect("fixed command"),lease_id:Some(lease.lease_id),
            params_digest:Some(pair_media_route_termination_params_digest_v2(&request).map_err(|_|rejected())?),issued_at_ms:now_ms,expires_at_ms:lease.expires_at_ms};
        let receipt=peer.review_pair_media_route_termination_v2_with_live_broker_runtime(&broker,&request,&command,now_ms).map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        if !receipt.applied{return Err(rejected());}Ok(receipt)
    }
    /// Join each retained terminal route to an actual native seven-owner Stop
    /// effect. Expiry or renderer completion alone can never supply this receipt.
    pub fn complete_concurrent_peer_route_cleanup(&mut self,stop:&QuestBrokerMediaStopEffectReceipt,now_ms:u64,entropy_hex:&str)
        ->Result<Vec<ManifoldPairMediaRouteCleanupReceiptV2>,QuestBrokerRuntimeError>{
        parse_entropy_hex(entropy_hex).map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
        if stop.schema_id!=QUEST_BROKER_MEDIA_STOP_EFFECT_RECEIPT_SCHEMA||stop.owner_receipt_ids.len()!=7||stop.owner_receipt_ids.iter().collect::<BTreeSet<_>>().len()!=7{return Err(rejected());}
        let owner=self.runtime.as_mut().ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        let media=owner.media_sessions.get(&stop.target_client_id).ok_or_else(rejected)?;
        let recovery=media.recovery_snapshot().map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        if recovery.lifecycle.phase!=rusty_quest_media_stream::MediaStreamRuntimePhase::Stopped
            ||!recovery.applied_action_ids.contains(&stop.action_id)||recovery.pending_action.is_some()
            ||!recovery.active_provider_handles.is_empty(){return Err(rejected());}
        let shared=owner.peer_runtime_host.clone().ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut peer=shared.write().map_err(|_|QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let broker=owner.runtime.read().map_err(|_|QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
        let matching:Vec<_>=peer.snapshot().pair_media_routes.routes.iter().filter(|r|r.authority_client_id()==&stop.target_client_id&&r.authority_runtime_lease_id()==&stop.target_runtime_lease_id).cloned().collect();
        if matching.is_empty()||matching.iter().any(|r|*r.lifecycle_status()==ManifoldPairMediaRouteLifecycleStatus::Current){return Err(rejected());}
        let grants:BTreeSet<_>=matching.iter().map(|r|r.grant_id().clone()).collect();
        let routes:Vec<_>=matching.into_iter().filter(|r|*r.cleanup_status()==ManifoldPairMediaRouteCleanupStatus::Pending).collect();
        for (index,route) in routes.iter().enumerate(){
            let state=peer.snapshot();
            let leases:Vec<_>=state.media_command_runtime.leases.iter().filter(|l|l.expires_at_ms>now_ms&&l.scope==state.trust_policy.media_runtime_lease_scope_id&&
                ((&l.holder_id==route.authority_client_id()&&&l.lease_id==route.authority_runtime_lease_id())||state.trust_policy.trusted_media_revoker_ids.contains(&l.holder_id))).collect();
            let lease=leases.first().ok_or_else(rejected)?;let lease=(*lease).clone();
            let request_id=id(format!("request.quest.concurrent.route-cleanup.{entropy_hex}.{index}"))?;
            let command_id=id(format!("request.quest.concurrent.route-cleanup-command.{entropy_hex}.{index}"))?;
            let request=ManifoldPairMediaRouteCleanupCompletionRequestV2{schema_id:schema(PAIR_MEDIA_ROUTE_CLEANUP_REQUEST_V2_SCHEMA),request_id,
                expected_authority_revision:state.pair_media_routes.authority_revision,runtime_command_request_id:command_id.clone(),grant_id:route.grant_id().clone(),
                expected_authority_provider_epoch_id:route.authority_provider_epoch_id().clone(),expected_platform_runtime_spec_id:route.platform_runtime_spec_id().clone(),
                effect_receipt_id:stop.effect_receipt_id.clone(),effect_receipt_sha256:stop.effect_receipt_sha256.clone()};
            let command=ManifoldRuntimeCommandRequest{schema_id:schema(HOST_COMMAND_REQUEST_SCHEMA),request_id:command_id,expected_authority_revision:state.media_command_runtime.authority_revision,
                requester_id:lease.holder_id.clone(),command_id:DottedId::new(PAIR_MEDIA_ROUTE_CLEANUP_COMMAND).expect("fixed command"),lease_id:Some(lease.lease_id.clone()),
                params_digest:Some(pair_media_route_cleanup_params_digest_v2(&request).map_err(|_|rejected())?),issued_at_ms:now_ms,expires_at_ms:lease.expires_at_ms};
            let _=peer.complete_pair_media_route_cleanup_v2_with_live_broker_runtime(&broker,&request,&command,now_ms).map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        }
        if peer.snapshot().pair_media_routes.routes.iter().any(|route|route.authority_runtime_lease_id()==&stop.target_runtime_lease_id&&
            (*route.lifecycle_status()==ManifoldPairMediaRouteLifecycleStatus::Current||*route.cleanup_status()==ManifoldPairMediaRouteCleanupStatus::Pending)){return Err(rejected());}
        let released=peer.snapshot().broker_lease_admissions.iter().filter(|a|a.runtime_lease.lease_id==stop.target_runtime_lease_id).max_by_key(|a|a.admitted_at_ms).is_some_and(|a|a.released_at_ms.is_some()&&a.release_id.is_some());
        if released {
            if peer.snapshot().media_command_runtime.leases.iter().any(|l|l.lease_id==stop.target_runtime_lease_id){return Err(rejected());}
        } else {
            peer.release_media_runtime_lease_with_live_broker_runtime(&broker,&stop.target_runtime_lease_id,
                id(format!("request.quest.concurrent.released-inner.{entropy_hex}"))?,now_ms).map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        }
        // Return the actual retained receipts as well as newly completed ones:
        // a retry after partial cleanup must preserve the owner evidence join.
        Ok(peer.snapshot().pair_media_routes.cleanup_receipts.iter().filter(|r|grants.contains(r.grant_id())&&r.effect_receipt_id()==&stop.effect_receipt_id&&r.effect_receipt_sha256()==stop.effect_receipt_sha256).cloned().collect())
    }
}
