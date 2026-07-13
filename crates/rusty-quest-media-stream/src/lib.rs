//! Source-neutral media stream session contracts for Quest platform adapters.
//!
//! This crate describes low-rate plans and validation rules for reusable H.264
//! media streaming. It does not open sockets, start encoders, run ADB, call
//! hidden Android APIs, or carry frame payload bytes.

mod failure_recovery;
mod model;
mod product_runtime;
mod runtime;
mod validation;

pub use failure_recovery::*;
pub use model::*;
pub use product_runtime::*;
pub use runtime::*;
pub use validation::validate_media_stream_session;

#[cfg(test)]
mod tests {
    use super::*;

    fn runtime_spec() -> MediaStreamRuntimeSpec {
        let mut plan = parse_fixture(include_str!(
            "../../../fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json"
        ));
        plan.transport_routes[0].connect_host = "192.168.49.2".to_string();
        MediaStreamRuntimeSpec {
            schema: MEDIA_STREAM_RUNTIME_SPEC_SCHEMA.to_string(),
            runtime_spec_id: "runtime.media.display-example".to_string(),
            manifold_session_revision: 4,
            plan,
            processors: vec![MediaStreamProcessorDescriptor {
                processor_id: "processor.display.passthrough".to_string(),
                processor_kind: "passthrough_h264".to_string(),
                input_track_roles: vec!["display".to_string()],
                output_track_roles: vec!["display".to_string()],
                owns_codec: false,
                cpu_pixel_copy: false,
                application_policy_fields: Vec::new(),
            }],
            sinks: vec![MediaStreamSinkDescriptor {
                sink_id: "sink.pc.display".to_string(),
                device_id: "pc-host".to_string(),
                sink_kind: "hostess_h264_receiver".to_string(),
                required_permissions: Vec::new(),
                application_policy_fields: Vec::new(),
            }],
            lane_bindings: vec![MediaStreamLaneRuntimeBinding {
                lane_id: "quest-a-display-to-pc-host".to_string(),
                processor_ids: vec!["processor.display.passthrough".to_string()],
                sink_id: "sink.pc.display".to_string(),
            }],
            direct_p2p_routes: vec![MediaStreamDirectP2pRouteBinding {
                lane_id: "quest-a-display-to-pc-host".to_string(),
                route: serde_json::from_str(include_str!(
                    "../../../fixtures/device-link/direct-p2p-socket-route.pass.json"
                ))
                .expect("direct route parses"),
            }],
            owner_selections: vec![
                MediaStreamOwnerSelection {
                    owner_kind: MediaStreamOwnerKind::Source,
                    owner_id: "owner.quest.display-capture".to_string(),
                    resource_id: "quest-a-display-composite".to_string(),
                    lane_id: Some("quest-a-display-to-pc-host".to_string()),
                    provider_kind: "android_mediaprojection_surface".to_string(),
                },
                MediaStreamOwnerSelection {
                    owner_kind: MediaStreamOwnerKind::Processor,
                    owner_id: "owner.quest.display-passthrough".to_string(),
                    resource_id: "processor.display.passthrough".to_string(),
                    lane_id: Some("quest-a-display-to-pc-host".to_string()),
                    provider_kind: "rust_passthrough_h264".to_string(),
                },
                MediaStreamOwnerSelection {
                    owner_kind: MediaStreamOwnerKind::Route,
                    owner_id: "owner.manifold.route".to_string(),
                    resource_id: "quest-a-display-to-pc-host".to_string(),
                    lane_id: Some("quest-a-display-to-pc-host".to_string()),
                    provider_kind: "manifold_accepted_route".to_string(),
                },
                MediaStreamOwnerSelection {
                    owner_kind: MediaStreamOwnerKind::Socket,
                    owner_id: "owner.rust.direct-p2p-socket".to_string(),
                    resource_id: "quest-a-display-to-pc-host".to_string(),
                    lane_id: Some("quest-a-display-to-pc-host".to_string()),
                    provider_kind: "rust_p2p0_bound_socket".to_string(),
                },
                MediaStreamOwnerSelection {
                    owner_kind: MediaStreamOwnerKind::Codec,
                    owner_id: "owner.android.h264-codec".to_string(),
                    resource_id: "quest-a-display-to-pc-host".to_string(),
                    lane_id: Some("quest-a-display-to-pc-host".to_string()),
                    provider_kind: "android_mediacodec_h264".to_string(),
                },
                MediaStreamOwnerSelection {
                    owner_kind: MediaStreamOwnerKind::Sink,
                    owner_id: "owner.hostess.h264-sink".to_string(),
                    resource_id: "sink.pc.display".to_string(),
                    lane_id: Some("quest-a-display-to-pc-host".to_string()),
                    provider_kind: "hostess_h264_receiver".to_string(),
                },
                MediaStreamOwnerSelection {
                    owner_kind: MediaStreamOwnerKind::Cleanup,
                    owner_id: "owner.quest.media-cleanup".to_string(),
                    resource_id: "runtime.media.display-example".to_string(),
                    lane_id: None,
                    provider_kind: "quest_media_cleanup".to_string(),
                },
            ],
            compatibility_adapter_id: None,
        }
    }

    fn parse_fixture(text: &str) -> MediaStreamSessionPlan {
        serde_json::from_str(text).expect("media stream fixture parses")
    }

    fn product_binding() -> MediaStreamRuntimeProductBinding {
        cross_repo_binding_fixture(include_str!(
            "../../../fixtures/media-runtime-products/display-composite.binding.json"
        ))
    }

    fn cross_repo_binding_fixture(text: &str) -> MediaStreamRuntimeProductBinding {
        let value: serde_json::Value = serde_json::from_str(text).expect("cross binding fixture");
        serde_json::from_value(value["quest"].clone()).expect("Quest runtime binding")
    }

    fn current_session_fixture(
        text: &str,
        epoch: &str,
        suffix: &str,
    ) -> rusty_manifold_media_session::ManifoldMediaSessionCurrentReceipt {
        let value: serde_json::Value = serde_json::from_str(text).expect("cross binding fixture");
        let descriptor = &value["manifold"]["descriptor"];
        let accepted = serde_json::json!({
            "$schema": "rusty.manifold.media.accepted_session.v1",
            "decision_id": format!("decision.media-session.{suffix}"),
            "request_id": format!("request.media-session.{suffix}"),
            "session_id": descriptor["session_id"],
            "session_authority_revision": descriptor["authority_revision"],
            "product_descriptor_canonical_sha256": value["manifold"]["descriptor_canonical_sha256"],
            "provider_epoch_id": epoch,
            "platform_runtime_spec_id": descriptor["platform_runtime_spec_id"],
            "product_id": "broker.media-session.test",
            "feature_lock_id": "lock.broker-client.media-test.v1",
            "feature_lock_fingerprint": format!("sha256:{}", "22".repeat(32)),
            "capability_id": "capability.command.media.session.start",
            "admission_grant_id": "grant.media-session.test",
            "runtime_authority_host_id": "host.quest.media-test",
            "runtime_command_request_id": format!("request.runtime.media-session.{suffix}"),
            "runtime_command_id": "rusty.manifold.media.session.accept",
            "runtime_client_id": "client.quest.media-test",
            "runtime_lease_id": "lease.media.session.quest-test",
            "runtime_params_digest": {
                "$schema": "rusty.manifold.runtime_host.typed_params_digest.v1",
                "params_type_id": "rusty.manifold.media.session_acceptance_params.v1",
                "canonical_sha256": format!("sha256:{}", "11".repeat(32)),
                "canonical_size_bytes": 128
            },
            "runtime_dispatch_id": format!("dispatch.runtime.media-session.{suffix}"),
            "runtime_application_receipt_id": format!("receipt.runtime.media-session.{suffix}"),
            "runtime_resulting_authority_revision": 2,
            "lifecycle_status": "current",
            "accepted_at_ms": 1_000,
            "expires_at_ms": 60_000,
            "ended_at_ms": null,
            "ended_by_id": null,
            "product_binding": value["manifold"]
        });
        serde_json::from_value(serde_json::json!({
            "$schema": "rusty.manifold.media.session_current_receipt.v1",
            "decision_id": accepted["decision_id"],
            "acceptance_state_authority_revision": 2,
            "current": true,
            "rejection_reason": null,
            "session": accepted,
            "validated_at_ms": 2_000
        }))
        .expect("accepted media session")
    }

    fn product_acceptance(
        epoch: &str,
        suffix: &str,
    ) -> rusty_manifold_media_session::ManifoldMediaSessionCurrentReceipt {
        current_session_fixture(
            include_str!("../../../fixtures/media-runtime-products/display-composite.binding.json"),
            epoch,
            suffix,
        )
    }

    fn readback_for(
        action: &MediaStreamPlatformAction,
        owner: &MediaStreamOwnerAction,
        index: usize,
    ) -> MediaStreamOwnerProviderReadback {
        let observed_state = match owner.action_kind {
            MediaStreamOwnerActionKind::ArmReceiver => "receiver_armed",
            MediaStreamOwnerActionKind::ArmCleanup => "cleanup_armed",
            MediaStreamOwnerActionKind::Start => "started",
            MediaStreamOwnerActionKind::Stop => "stopped",
            MediaStreamOwnerActionKind::Cleanup => "cleaned",
        };
        MediaStreamOwnerProviderReadback {
            action_id: action.action_id.clone(),
            authority_epoch_id: action.authority_epoch_id.clone(),
            media_acceptance_authority_revision: action.media_acceptance_authority_revision,
            client_id: action.client_authority.client_id.clone(),
            lease_id: action.client_authority.lease_id.clone(),
            provider_kind: owner.selection.provider_kind.clone(),
            resource_id: owner.selection.resource_id.clone(),
            provider_handle_id: format!(
                "handle.{}.{}",
                owner.selection.owner_id, owner.selection.resource_id
            ),
            provider_state_revision: match action.operation {
                MediaStreamPlatformOperation::Start => {
                    u64::try_from(index + 1).expect("small fixture")
                }
                MediaStreamPlatformOperation::Stop => {
                    u64::try_from(index + 101).expect("small fixture")
                }
            },
            observed_state: observed_state.to_string(),
            receipt_id: format!("receipt.{}.{}", action.action_id, index + 1),
        }
    }

    struct TestOwnerProvider {
        owner_kind: MediaStreamOwnerKind,
        readback: MediaStreamOwnerProviderReadback,
        verify: bool,
    }

    impl MediaStreamTrustedOwnerProvider for TestOwnerProvider {
        fn owner_kind(&self) -> MediaStreamOwnerKind {
            self.owner_kind
        }

        fn execute_and_readback(
            &mut self,
            _action: &MediaStreamPlatformAction,
            _owner_action: &MediaStreamOwnerAction,
        ) -> Result<MediaStreamOwnerProviderReadback, String> {
            Ok(self.readback.clone())
        }

        fn compensate_uncertain_attempt(
            &mut self,
            _abort_action: &MediaStreamPlatformAction,
            _owner_action: &MediaStreamOwnerAction,
        ) -> Result<MediaStreamOwnerProviderReadback, String> {
            Ok(self.readback.clone())
        }

        fn verify_readback(
            &self,
            _action: &MediaStreamPlatformAction,
            _owner_action: &MediaStreamOwnerAction,
            readback: &MediaStreamOwnerProviderReadback,
        ) -> bool {
            self.verify && readback == &self.readback
        }
    }

    #[derive(Clone, Copy)]
    enum UncertainAttemptMode {
        SideEffectThenError,
        SideEffectThenDamagedReadback,
    }

    struct UncertainTestOwnerProvider {
        owner_kind: MediaStreamOwnerKind,
        mode: UncertainAttemptMode,
        attempted_readback: MediaStreamOwnerProviderReadback,
        compensation_readback: Option<MediaStreamOwnerProviderReadback>,
    }

    impl MediaStreamTrustedOwnerProvider for UncertainTestOwnerProvider {
        fn owner_kind(&self) -> MediaStreamOwnerKind {
            self.owner_kind
        }

        fn execute_and_readback(
            &mut self,
            _action: &MediaStreamPlatformAction,
            _owner_action: &MediaStreamOwnerAction,
        ) -> Result<MediaStreamOwnerProviderReadback, String> {
            match self.mode {
                UncertainAttemptMode::SideEffectThenError => {
                    Err("side effect occurred before provider error".to_string())
                }
                UncertainAttemptMode::SideEffectThenDamagedReadback => {
                    Ok(self.attempted_readback.clone())
                }
            }
        }

        fn compensate_uncertain_attempt(
            &mut self,
            _abort_action: &MediaStreamPlatformAction,
            _owner_action: &MediaStreamOwnerAction,
        ) -> Result<MediaStreamOwnerProviderReadback, String> {
            self.compensation_readback
                .clone()
                .ok_or_else(|| "compensation readback not armed".to_string())
        }

        fn verify_readback(
            &self,
            _action: &MediaStreamPlatformAction,
            _owner_action: &MediaStreamOwnerAction,
            _readback: &MediaStreamOwnerProviderReadback,
        ) -> bool {
            true
        }
    }

    fn complete_owner_with<P: MediaStreamTrustedOwnerProvider>(
        runtime: &mut MediaStreamSessionProductRuntime,
        owner_kind: MediaStreamOwnerKind,
        provider: &mut P,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        match owner_kind {
            MediaStreamOwnerKind::Source => runtime.complete_source(provider, 2_000),
            MediaStreamOwnerKind::Processor => runtime.complete_processor(provider, 2_000),
            MediaStreamOwnerKind::Route => runtime.complete_route(provider, 2_000),
            MediaStreamOwnerKind::Socket => runtime.complete_socket(provider, 2_000),
            MediaStreamOwnerKind::Codec => runtime.complete_codec(provider, 2_000),
            MediaStreamOwnerKind::Sink => runtime.complete_sink(provider, 2_000),
            MediaStreamOwnerKind::Cleanup => runtime.complete_cleanup(provider, 2_000),
        }
    }

    fn record_owner(
        runtime: &mut MediaStreamSessionProductRuntime,
        owner: &MediaStreamOwnerAction,
        readback: MediaStreamOwnerProviderReadback,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        let mut provider = TestOwnerProvider {
            owner_kind: owner.selection.owner_kind,
            readback,
            verify: true,
        };
        complete_owner_with(runtime, owner.selection.owner_kind, &mut provider)
    }

    fn record_all(
        runtime: &mut MediaStreamSessionProductRuntime,
        action: &MediaStreamPlatformAction,
    ) {
        for (index, owner) in action.owner_actions.iter().enumerate() {
            record_owner(runtime, owner, readback_for(action, owner, index))
                .expect("owner-specific readback records");
        }
    }

    fn client_authority(
        operation: MediaStreamPlatformOperation,
    ) -> MediaStreamClientAuthorityBinding {
        MediaStreamClientAuthorityBinding {
            client_id: "client.quest.media-test".to_string(),
            lease_id: "lease.media.session.quest-test".to_string(),
            product_id: "broker.media-session.test".to_string(),
            feature_lock_id: "lock.broker-client.media-test.v1".to_string(),
            feature_lock_fingerprint: format!("sha256:{}", "22".repeat(32)),
            session_capability_id: "capability.command.media.session.start".to_string(),
            session_admission_grant_id: "grant.media-session.test".to_string(),
            operation_capability_id: match operation {
                MediaStreamPlatformOperation::Start => "capability.command.media.session.start",
                MediaStreamPlatformOperation::Stop => "capability.command.media.session.stop",
            }
            .to_string(),
            operation_admission_grant_id: "grant.media-session.test".to_string(),
            operation_admission_use_request_id: match operation {
                MediaStreamPlatformOperation::Start => "use.media-session-start.test",
                MediaStreamPlatformOperation::Stop => "use.media-session-stop.test",
            }
            .to_string(),
        }
    }

    #[test]
    fn display_composite_fixture_validates() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json"
        ));

        validate_media_stream_session(&plan).expect("display-composite plan validates");
    }

    #[test]
    fn shell_display_mirror_fixture_validates_as_lab_only() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/media-stream-sessions/shell-display-mirror-h264.plan.json"
        ));

        validate_media_stream_session(&plan).expect("shell display mirror plan validates");
    }

    #[test]
    fn high_rate_json_payload_plane_is_rejected() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/damaged/media-stream-high-rate-json.plan.json"
        ));

        let errors =
            validate_media_stream_session(&plan).expect_err("high-rate JSON must be rejected");
        assert!(errors.iter().any(|error| {
            error
                .message
                .contains("high-rate media must use binary-media payload plane")
        }));
    }

    #[test]
    fn shell_hidden_display_cannot_be_production_candidate() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/damaged/media-stream-shell-display-production.plan.json"
        ));

        let errors =
            validate_media_stream_session(&plan).expect_err("production shell route is rejected");
        assert!(errors.iter().any(|error| {
            error
                .message
                .contains("shell display mirror sources must be lab_developer_only")
        }));
    }

    #[test]
    fn display_source_requires_display_descriptor() {
        let mut plan = parse_fixture(include_str!(
            "../../../fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json"
        ));
        plan.sources[0].display = None;

        let errors = validate_media_stream_session(&plan)
            .expect_err("display source without descriptor is rejected");
        assert!(errors.iter().any(|error| {
            error
                .message
                .contains("display_composite_mediaprojection_mediacodec_surface source")
                && error.message.contains("requires display capture metadata")
        }));
    }

    #[test]
    fn display_source_rejects_camera_permission_bleed() {
        let mut plan = parse_fixture(include_str!(
            "../../../fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json"
        ));
        plan.sources[0].camera = Some(CameraCaptureDescriptor {
            camera_id: "50".to_string(),
            camera_ids: Vec::new(),
            camera_facing: "external".to_string(),
            permission_policy: CAMERA_PERMISSION_REQUIRED.to_string(),
        });
        let errors = validate_media_stream_session(&plan)
            .expect_err("display source must reject camera permission bleed");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("must not inherit camera")));
    }

    #[test]
    fn zero_transport_port_is_rejected() {
        let mut plan = parse_fixture(include_str!(
            "../../../fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json"
        ));
        plan.runtime_endpoints[1].transport_receive_ports[0].port = 0;

        let errors =
            validate_media_stream_session(&plan).expect_err("zero transport port is rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("port for display must be nonzero")));
    }

    #[test]
    fn display_source_conformance_has_consent_without_camera_permission() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json"
        ));
        let receipt = validate_media_stream_source_conformance(&plan, "quest-a-display-composite")
            .expect("display source conforms");
        assert!(receipt.consent_required);
        assert!(!receipt.camera_permission_required);
        assert!(!receipt.permission_or_profile_bleed);
    }

    #[test]
    fn dual_lane_and_packed_processors_conform_without_codec_ownership() {
        for processor in [
            MediaStreamProcessorDescriptor {
                processor_id: "processor.dual".to_string(),
                processor_kind: "dual_lane_independent".to_string(),
                input_track_roles: vec!["left".to_string(), "right".to_string()],
                output_track_roles: vec!["left".to_string(), "right".to_string()],
                owns_codec: false,
                cpu_pixel_copy: false,
                application_policy_fields: Vec::new(),
            },
            MediaStreamProcessorDescriptor {
                processor_id: "processor.packed".to_string(),
                processor_kind: "packed_sbs_left_right".to_string(),
                input_track_roles: vec!["left".to_string(), "right".to_string()],
                output_track_roles: vec!["stereo".to_string()],
                owns_codec: false,
                cpu_pixel_copy: false,
                application_policy_fields: Vec::new(),
            },
        ] {
            let receipt = validate_media_stream_processor(&processor).expect("processor conforms");
            assert!(receipt.codec_owner_external);
            assert!(receipt.application_policy_absent);
        }
    }

    #[test]
    fn runtime_is_receiver_first_revisioned_and_cleanup_gated() {
        let spec = runtime_spec();
        validate_media_stream_runtime_spec(&spec).expect("runtime spec validates");
        let mut runtime = MediaStreamSessionRuntime::new(spec).expect("runtime constructs");
        let premature = runtime.execute(&MediaStreamRuntimeRequest {
            request_id: "request.start.premature".to_string(),
            expected_runtime_revision: 1,
            action: MediaStreamRuntimeAction::StartSources,
            evidence: MediaStreamRuntimeEvidence {
                sources_started: true,
                ..Default::default()
            },
        });
        assert!(!premature.accepted);
        assert_eq!(
            premature.rejection_reason.as_deref(),
            Some("receivers_not_armed")
        );
        assert_eq!(runtime.state().runtime_revision, 1);

        for (request_id, action, evidence) in [
            (
                "request.arm",
                MediaStreamRuntimeAction::ArmReceivers,
                MediaStreamRuntimeEvidence {
                    receivers_armed: true,
                    ..Default::default()
                },
            ),
            (
                "request.start",
                MediaStreamRuntimeAction::StartSources,
                MediaStreamRuntimeEvidence {
                    sources_started: true,
                    ..Default::default()
                },
            ),
            (
                "request.streaming",
                MediaStreamRuntimeAction::ConfirmStreaming,
                MediaStreamRuntimeEvidence {
                    received_media_frames: 2,
                    ..Default::default()
                },
            ),
            (
                "request.stop",
                MediaStreamRuntimeAction::Stop,
                MediaStreamRuntimeEvidence {
                    cleanup_complete: true,
                    ..Default::default()
                },
            ),
        ] {
            let decision = runtime.execute(&MediaStreamRuntimeRequest {
                request_id: request_id.to_string(),
                expected_runtime_revision: runtime.state().runtime_revision,
                action,
                evidence,
            });
            assert!(decision.accepted && decision.applied);
        }
        assert_eq!(runtime.state().phase, MediaStreamRuntimePhase::Stopped);
        assert_eq!(runtime.state().runtime_revision, 5);
    }

    #[test]
    fn android_socket_substitution_route_is_rejected() {
        let mut spec = runtime_spec();
        spec.direct_p2p_routes[0].route = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/direct-p2p-socket-route-android-network-substitution.json"
        ))
        .expect("damaged route parses");
        let errors = validate_media_stream_runtime_spec(&spec)
            .expect_err("Android socket authority substitution rejects");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("Android Network substitution")));
    }

    #[test]
    fn packaged_start_requires_exact_receiver_first_owner_completion() {
        let binding = product_binding();
        binding.validate().expect("product binding validates");
        let mut runtime = MediaStreamSessionProductRuntime::new_for_test(
            binding,
            product_acceptance("epoch.media.1", "start-one"),
            "epoch.media.1".to_string(),
        )
        .expect("product runtime");
        let action = runtime
            .prepare(
                "action.media.start.1".to_string(),
                MediaStreamPlatformOperation::Start,
                client_authority(MediaStreamPlatformOperation::Start),
                2_000,
            )
            .expect("start prepares");
        assert_eq!(runtime.runtime().state().runtime_revision, 1);
        assert_eq!(
            action
                .owner_actions
                .iter()
                .map(|owner| owner.selection.owner_kind)
                .collect::<Vec<_>>(),
            vec![
                MediaStreamOwnerKind::Cleanup,
                MediaStreamOwnerKind::Sink,
                MediaStreamOwnerKind::Route,
                MediaStreamOwnerKind::Socket,
                MediaStreamOwnerKind::Codec,
                MediaStreamOwnerKind::Processor,
                MediaStreamOwnerKind::Source,
            ]
        );

        for (index, owner) in action.owner_actions.iter().enumerate().take(6) {
            record_owner(&mut runtime, owner, readback_for(&action, owner, index))
                .expect("partial owner readback");
        }
        assert!(matches!(
            runtime.apply_recorded_owner_completions(2_000),
            Err(MediaStreamProductRuntimeError::OwnerCallbacksIncomplete)
        ));
        assert_eq!(runtime.runtime().state().runtime_revision, 1);

        let last = action.owner_actions.last().expect("source owner");
        record_owner(
            &mut runtime,
            last,
            readback_for(&action, last, action.owner_actions.len() - 1),
        )
        .expect("final source readback");
        let receipt = runtime
            .apply_recorded_owner_completions(2_000)
            .expect("exact owner readbacks apply");
        assert!(receipt.platform_effect_completed);
        assert_eq!(
            receipt.resulting_phase,
            MediaStreamRuntimePhase::SourcesStarted
        );
        assert_eq!(receipt.resulting_runtime_revision, 3);
        assert!(matches!(
            runtime.prepare(
                "action.media.start.1".to_string(),
                MediaStreamPlatformOperation::Start,
                client_authority(MediaStreamPlatformOperation::Start),
                2_000,
            ),
            Err(MediaStreamProductRuntimeError::ReplayedAction)
        ));
    }

    #[test]
    fn partial_start_abort_reverses_every_owner_boundary_before_release() {
        let owner_count = product_binding().spec.owner_selections.len();
        for boundary in 0..=owner_count {
            let mut runtime = MediaStreamSessionProductRuntime::new_for_test(
                product_binding(),
                product_acceptance(
                    &format!("epoch.media.abort.{boundary}"),
                    &format!("abort-{boundary}"),
                ),
                format!("epoch.media.abort.{boundary}"),
            )
            .expect("abort runtime");
            let action_id = format!("action.media.abort.{boundary}");
            let action = runtime
                .prepare(
                    action_id.clone(),
                    MediaStreamPlatformOperation::Start,
                    client_authority(MediaStreamPlatformOperation::Start),
                    2_000,
                )
                .expect("start prepares");
            for (index, owner) in action.owner_actions.iter().enumerate().take(boundary) {
                record_owner(&mut runtime, owner, readback_for(&action, owner, index))
                    .expect("partial start owner records");
            }

            let abort = runtime
                .begin_partial_start_abort()
                .expect("partial start enters rollback");
            assert_eq!(abort.action_id, format!("{action_id}.abort"));
            assert_eq!(abort.owner_actions.len(), boundary);
            assert_eq!(
                abort
                    .owner_actions
                    .iter()
                    .map(|owner| owner.selection.clone())
                    .collect::<Vec<_>>(),
                action
                    .owner_actions
                    .iter()
                    .take(boundary)
                    .rev()
                    .map(|owner| owner.selection.clone())
                    .collect::<Vec<_>>()
            );
            if boundary != 0 {
                assert!(matches!(
                    runtime.finalize_partial_start_abort(),
                    Err(MediaStreamProductRuntimeError::AbortCallbacksIncomplete)
                ));
            }
            assert!(matches!(
                runtime.apply_recorded_owner_completions(2_000),
                Err(MediaStreamProductRuntimeError::AbortInProgress)
            ));

            for (index, owner) in abort.owner_actions.iter().enumerate() {
                let original_index = boundary - index - 1;
                let original = readback_for(
                    &action,
                    &action.owner_actions[original_index],
                    original_index,
                );
                let observed_state = match owner.action_kind {
                    MediaStreamOwnerActionKind::Stop => "stopped",
                    MediaStreamOwnerActionKind::Cleanup => "cleaned",
                    _ => panic!("rollback emits only stop/cleanup"),
                };
                let readback = MediaStreamOwnerProviderReadback {
                    action_id: abort.action_id.clone(),
                    authority_epoch_id: abort.authority_epoch_id.clone(),
                    media_acceptance_authority_revision: abort.media_acceptance_authority_revision,
                    client_id: abort.client_authority.client_id.clone(),
                    lease_id: abort.client_authority.lease_id.clone(),
                    provider_kind: owner.selection.provider_kind.clone(),
                    resource_id: owner.selection.resource_id.clone(),
                    provider_handle_id: original.provider_handle_id,
                    provider_state_revision: 1_000 + u64::try_from(index).expect("small boundary"),
                    observed_state: observed_state.to_string(),
                    receipt_id: format!("receipt.{}.{}", abort.action_id, index + 1),
                };
                let mut provider = TestOwnerProvider {
                    owner_kind: owner.selection.owner_kind,
                    readback,
                    verify: true,
                };
                runtime
                    .complete_next_abort_owner(&mut provider)
                    .expect("reverse owner cleanup records");
            }
            let receipt = runtime
                .finalize_partial_start_abort()
                .expect("all observed effects reversed");
            assert!(receipt.cleanup_complete);
            assert!(!receipt.platform_effect_completed);
            assert_eq!(receipt.resulting_phase, MediaStreamRuntimePhase::Planned);
            assert_eq!(receipt.resulting_runtime_revision, 1);
            assert_eq!(receipt.rollback_receipts.len(), boundary);
            assert!(runtime.pending_action().is_none());
            assert!(matches!(
                runtime.prepare(
                    action_id,
                    MediaStreamPlatformOperation::Start,
                    client_authority(MediaStreamPlatformOperation::Start),
                    2_000,
                ),
                Err(MediaStreamProductRuntimeError::ReplayedAction)
            ));
        }
    }

    #[test]
    fn uncertain_owner_attempts_require_idempotent_compensation_at_every_boundary() {
        let owner_count = product_binding().spec.owner_selections.len();
        for mode in [
            UncertainAttemptMode::SideEffectThenError,
            UncertainAttemptMode::SideEffectThenDamagedReadback,
        ] {
            for attempted_index in 0..owner_count {
                let mut runtime = MediaStreamSessionProductRuntime::new_for_test(
                    product_binding(),
                    product_acceptance(
                        &format!("epoch.media.uncertain.{attempted_index}"),
                        &format!("uncertain-{attempted_index}"),
                    ),
                    format!("epoch.media.uncertain.{attempted_index}"),
                )
                .expect("uncertain runtime");
                let action = runtime
                    .prepare(
                        format!("action.media.uncertain.{attempted_index}"),
                        MediaStreamPlatformOperation::Start,
                        client_authority(MediaStreamPlatformOperation::Start),
                        2_000,
                    )
                    .expect("start prepares");
                for (index, owner) in action
                    .owner_actions
                    .iter()
                    .enumerate()
                    .take(attempted_index)
                {
                    record_owner(&mut runtime, owner, readback_for(&action, owner, index))
                        .expect("prior owner records");
                }
                let attempted = &action.owner_actions[attempted_index];
                let mut damaged = readback_for(&action, attempted, attempted_index);
                damaged.receipt_id.clear();
                let mut provider = UncertainTestOwnerProvider {
                    owner_kind: attempted.selection.owner_kind,
                    mode,
                    attempted_readback: damaged,
                    compensation_readback: None,
                };
                let failure = complete_owner_with(
                    &mut runtime,
                    attempted.selection.owner_kind,
                    &mut provider,
                );
                assert!(matches!(
                    failure,
                    Err(MediaStreamProductRuntimeError::OwnerProviderFailed)
                        | Err(MediaStreamProductRuntimeError::OwnerReadbackMismatch)
                ));
                assert!(matches!(
                    complete_owner_with(
                        &mut runtime,
                        attempted.selection.owner_kind,
                        &mut provider,
                    ),
                    Err(MediaStreamProductRuntimeError::UncertainOwnerAttemptPending)
                ));

                let abort = runtime
                    .begin_partial_start_abort()
                    .expect("uncertain attempt enters abort");
                assert_eq!(abort.owner_actions.len(), attempted_index + 1);
                assert_eq!(abort.owner_actions[0].selection, attempted.selection);
                let uncertain_abort = &abort.owner_actions[0];
                provider.compensation_readback = Some(MediaStreamOwnerProviderReadback {
                    action_id: abort.action_id.clone(),
                    authority_epoch_id: abort.authority_epoch_id.clone(),
                    media_acceptance_authority_revision: abort.media_acceptance_authority_revision,
                    client_id: abort.client_authority.client_id.clone(),
                    lease_id: abort.client_authority.lease_id.clone(),
                    provider_kind: uncertain_abort.selection.provider_kind.clone(),
                    resource_id: uncertain_abort.selection.resource_id.clone(),
                    provider_handle_id: format!("handle.uncertain-compensation.{attempted_index}"),
                    provider_state_revision: 10_000,
                    observed_state: match uncertain_abort.action_kind {
                        MediaStreamOwnerActionKind::Stop => "stopped",
                        MediaStreamOwnerActionKind::Cleanup => "cleaned",
                        _ => panic!("uncertain compensation must stop/clean"),
                    }
                    .to_string(),
                    receipt_id: format!("receipt.uncertain-compensation.{attempted_index}"),
                });
                runtime
                    .complete_next_abort_owner(&mut provider)
                    .expect("uncertain owner compensation records");

                for (abort_index, owner) in abort.owner_actions.iter().enumerate().skip(1) {
                    let original_index = attempted_index - abort_index;
                    let original = readback_for(
                        &action,
                        &action.owner_actions[original_index],
                        original_index,
                    );
                    let mut provider = TestOwnerProvider {
                        owner_kind: owner.selection.owner_kind,
                        readback: MediaStreamOwnerProviderReadback {
                            action_id: abort.action_id.clone(),
                            authority_epoch_id: abort.authority_epoch_id.clone(),
                            media_acceptance_authority_revision: abort
                                .media_acceptance_authority_revision,
                            client_id: abort.client_authority.client_id.clone(),
                            lease_id: abort.client_authority.lease_id.clone(),
                            provider_kind: owner.selection.provider_kind.clone(),
                            resource_id: owner.selection.resource_id.clone(),
                            provider_handle_id: original.provider_handle_id,
                            provider_state_revision: 20_000
                                + u64::try_from(abort_index).expect("small index"),
                            observed_state: match owner.action_kind {
                                MediaStreamOwnerActionKind::Stop => "stopped",
                                MediaStreamOwnerActionKind::Cleanup => "cleaned",
                                _ => panic!("rollback must stop/clean"),
                            }
                            .to_string(),
                            receipt_id: format!(
                                "receipt.uncertain-abort.{attempted_index}.{abort_index}"
                            ),
                        },
                        verify: true,
                    };
                    runtime
                        .complete_next_abort_owner(&mut provider)
                        .expect("prior owner rollback records");
                }
                let receipt = runtime
                    .finalize_partial_start_abort()
                    .expect("uncertain and prior effects compensated");
                assert_eq!(receipt.uncertain_attempts_compensated, 1);
                assert_eq!(receipt.rollback_receipts.len(), attempted_index + 1);
                assert!(receipt.cleanup_complete);
                assert!(runtime.pending_action().is_none());
            }
        }
    }

    #[test]
    fn stale_and_restarted_platform_completions_fail_closed() {
        let binding = product_binding();
        let mut original = MediaStreamSessionProductRuntime::new_for_test(
            binding.clone(),
            product_acceptance("epoch.media.original", "restart-original"),
            "epoch.media.original".to_string(),
        )
        .expect("original runtime");
        let old_action = original
            .prepare(
                "action.media.start.restart".to_string(),
                MediaStreamPlatformOperation::Start,
                client_authority(MediaStreamPlatformOperation::Start),
                2_000,
            )
            .expect("old action");
        let mut restarted = MediaStreamSessionProductRuntime::new_for_test(
            binding,
            product_acceptance("epoch.media.restarted", "restart-fresh"),
            "epoch.media.restarted".to_string(),
        )
        .expect("restarted runtime");
        restarted
            .prepare(
                "action.media.start.restart".to_string(),
                MediaStreamPlatformOperation::Start,
                client_authority(MediaStreamPlatformOperation::Start),
                2_000,
            )
            .expect("fresh action");
        let expected = restarted
            .pending_action()
            .expect("fresh pending")
            .owner_actions[0]
            .clone();
        let copied = readback_for(&old_action, &old_action.owner_actions[0], 0);
        assert!(matches!(
            record_owner(&mut restarted, &expected, copied),
            Err(MediaStreamProductRuntimeError::OwnerReadbackMismatch)
        ));
        assert_eq!(restarted.runtime().state().runtime_revision, 1);

        assert!(matches!(
            record_owner(
                &mut restarted,
                &expected,
                readback_for(&old_action, &old_action.owner_actions[0], 0)
            ),
            Err(MediaStreamProductRuntimeError::UncertainOwnerAttemptPending)
        ));

        let mut cross_app = MediaStreamSessionProductRuntime::new_for_test(
            product_binding(),
            product_acceptance("epoch.media.cross-app", "restart-cross-app"),
            "epoch.media.cross-app".to_string(),
        )
        .expect("cross-app runtime");
        let fresh_action = cross_app
            .prepare(
                "action.media.start.cross-app".to_string(),
                MediaStreamPlatformOperation::Start,
                client_authority(MediaStreamPlatformOperation::Start),
                2_000,
            )
            .expect("cross-app action");
        let mut stale = readback_for(&fresh_action, &fresh_action.owner_actions[0], 0);
        stale.provider_kind = "copied.cross-app.provider".to_string();
        assert!(matches!(
            record_owner(&mut cross_app, &fresh_action.owner_actions[0], stale),
            Err(MediaStreamProductRuntimeError::OwnerReadbackMismatch)
        ));
    }

    #[test]
    fn terminal_cleanup_is_exact_last_and_runtime_cannot_restart() {
        let mut runtime = MediaStreamSessionProductRuntime::new_for_test(
            product_binding(),
            product_acceptance("epoch.media.cleanup", "cleanup"),
            "epoch.media.cleanup".to_string(),
        )
        .expect("runtime");
        let start = runtime
            .prepare(
                "action.media.start.cleanup".to_string(),
                MediaStreamPlatformOperation::Start,
                client_authority(MediaStreamPlatformOperation::Start),
                2_000,
            )
            .expect("start");
        record_all(&mut runtime, &start);
        runtime
            .apply_recorded_owner_completions(2_000)
            .expect("start completion");
        let revision_before_cross_client = runtime.runtime().state().runtime_revision;
        assert!(matches!(
            runtime.prepare(
                "action.media.stop.cross-client".to_string(),
                MediaStreamPlatformOperation::Stop,
                MediaStreamClientAuthorityBinding {
                    client_id: "client.quest.copied".to_string(),
                    lease_id: "lease.media.session.copied".to_string(),
                    product_id: "broker.media-session.test".to_string(),
                    feature_lock_id: "lock.broker-client.media-test.v1".to_string(),
                    feature_lock_fingerprint: format!("sha256:{}", "22".repeat(32)),
                    session_capability_id: "capability.command.media.session.start".to_string(),
                    session_admission_grant_id: "grant.media-session.test".to_string(),
                    operation_capability_id: "capability.command.media.session.stop".to_string(),
                    operation_admission_grant_id: "grant.media-session.test".to_string(),
                    operation_admission_use_request_id: "use.media-session-stop.copied".to_string(),
                },
                2_000,
            ),
            Err(MediaStreamProductRuntimeError::ClientAuthorityMismatch)
        ));
        assert_eq!(
            runtime.runtime().state().runtime_revision,
            revision_before_cross_client
        );
        assert!(runtime.pending_action().is_none());
        let stop = runtime
            .prepare(
                "action.media.stop.cleanup".to_string(),
                MediaStreamPlatformOperation::Stop,
                client_authority(MediaStreamPlatformOperation::Stop),
                2_000,
            )
            .expect("stop");
        assert_eq!(
            stop.owner_actions
                .iter()
                .map(|owner| owner.selection.owner_kind)
                .collect::<Vec<_>>(),
            vec![
                MediaStreamOwnerKind::Source,
                MediaStreamOwnerKind::Processor,
                MediaStreamOwnerKind::Codec,
                MediaStreamOwnerKind::Socket,
                MediaStreamOwnerKind::Route,
                MediaStreamOwnerKind::Sink,
                MediaStreamOwnerKind::Cleanup,
            ]
        );
        let cleanup = stop
            .owner_actions
            .iter()
            .find(|owner| owner.selection.owner_kind == MediaStreamOwnerKind::Cleanup)
            .expect("cleanup owner");
        assert!(matches!(
            record_owner(&mut runtime, cleanup, readback_for(&stop, cleanup, 0)),
            Err(MediaStreamProductRuntimeError::OwnerCallbackOrderMismatch)
        ));
        record_all(&mut runtime, &stop);
        let receipt = runtime
            .apply_recorded_owner_completions(2_000)
            .expect("cleanup applies");
        assert_eq!(receipt.resulting_phase, MediaStreamRuntimePhase::Stopped);
        assert!(matches!(
            runtime.prepare(
                "action.media.restart.forbidden".to_string(),
                MediaStreamPlatformOperation::Start,
                client_authority(MediaStreamPlatformOperation::Start),
                2_000,
            ),
            Err(MediaStreamProductRuntimeError::TerminalRuntime)
        ));
    }

    #[test]
    fn generic_spec_rejects_remote_camera_identity_bleed() {
        let mut spec = runtime_spec();
        spec.owner_selections[0].provider_kind = "remote_camera_hidden_default".to_string();
        let errors = validate_media_stream_runtime_spec(&spec)
            .expect_err("generic runtime rejects compatibility identity bleed");
        assert!(errors.iter().any(|error| {
            error
                .message
                .contains("must not inherit remote-camera identities")
        }));
    }

    #[test]
    fn camera2_and_display_product_bindings_conform_independently() {
        let camera = cross_repo_binding_fixture(include_str!(
            "../../../fixtures/media-runtime-products/camera2-surface.binding.json"
        ));
        let display = cross_repo_binding_fixture(include_str!(
            "../../../fixtures/media-runtime-products/display-composite.binding.json"
        ));
        camera.validate().expect("Camera2 binding");
        display.validate().expect("display binding");

        let camera_source =
            validate_media_stream_source_conformance(&camera.spec.plan, "quest-a-camera2-main")
                .expect("Camera2 source");
        assert!(camera_source.camera_permission_required);
        assert!(!camera_source.consent_required);
        let display_source = validate_media_stream_source_conformance(
            &display.spec.plan,
            "quest-a-display-composite",
        )
        .expect("display source");
        assert!(!display_source.camera_permission_required);
        assert!(display_source.consent_required);
        assert_ne!(
            camera.spec.owner_selections[0].provider_kind,
            display.spec.owner_selections[0].provider_kind
        );
    }

    #[test]
    fn canonical_runtime_binding_rejects_unknown_nested_app_fields() {
        let source: serde_json::Value = serde_json::from_str(include_str!(
            "../../../fixtures/media-runtime-products/native-renderer-display.binding.json"
        ))
        .expect("binding value");
        for pointer in [
            "/quest/spec",
            "/quest/spec/plan/sources/0",
            "/quest/spec/plan/transport_routes/0",
            "/quest/spec/sinks/0",
            "/quest/spec/owner_selections/0",
        ] {
            let mut damaged = source.clone();
            damaged
                .pointer_mut(pointer)
                .and_then(serde_json::Value::as_object_mut)
                .expect("authoritative object")
                .insert(
                    "application_private_default".to_string(),
                    serde_json::json!(true),
                );
            assert!(serde_json::from_value::<MediaStreamRuntimeProductBinding>(
                damaged["quest"].clone()
            )
            .is_err());
        }
    }

    #[test]
    fn camera2_damage_and_both_product_cleanup_paths_fail_closed() {
        let mut camera = cross_repo_binding_fixture(include_str!(
            "../../../fixtures/media-runtime-products/camera2-surface.binding.json"
        ));
        camera.spec.plan.sources[0].camera = None;
        camera.runtime_spec_canonical_sha256 =
            canonical_media_stream_runtime_sha256(&camera.spec).expect("damaged digest");
        assert!(matches!(
            camera.validate(),
            Err(MediaStreamProductRuntimeError::RuntimeSpecInvalid(_))
        ));

        for (index, binding) in [
            cross_repo_binding_fixture(include_str!(
                "../../../fixtures/media-runtime-products/camera2-surface.binding.json"
            )),
            cross_repo_binding_fixture(include_str!(
                "../../../fixtures/media-runtime-products/display-composite.binding.json"
            )),
        ]
        .into_iter()
        .enumerate()
        {
            let source = if index == 0 {
                include_str!(
                    "../../../fixtures/media-runtime-products/camera2-surface.binding.json"
                )
            } else {
                include_str!(
                    "../../../fixtures/media-runtime-products/display-composite.binding.json"
                )
            };
            let mut runtime = MediaStreamSessionProductRuntime::new_for_test(
                binding,
                current_session_fixture(
                    source,
                    &format!("epoch.media.matrix.{index}"),
                    &format!("matrix-{index}"),
                ),
                format!("epoch.media.matrix.{index}"),
            )
            .expect("matrix runtime");
            let start = runtime
                .prepare(
                    format!("action.media.matrix.start.{index}"),
                    MediaStreamPlatformOperation::Start,
                    client_authority(MediaStreamPlatformOperation::Start),
                    2_000,
                )
                .expect("matrix start");
            record_all(&mut runtime, &start);
            runtime
                .apply_recorded_owner_completions(2_000)
                .expect("matrix start completion");
            let stop = runtime
                .prepare(
                    format!("action.media.matrix.stop.{index}"),
                    MediaStreamPlatformOperation::Stop,
                    client_authority(MediaStreamPlatformOperation::Stop),
                    2_000,
                )
                .expect("matrix stop");
            record_all(&mut runtime, &stop);
            let receipt = runtime
                .apply_recorded_owner_completions(2_000)
                .expect("matrix cleanup completion");
            assert_eq!(receipt.resulting_phase, MediaStreamRuntimePhase::Stopped);
        }
    }
}
