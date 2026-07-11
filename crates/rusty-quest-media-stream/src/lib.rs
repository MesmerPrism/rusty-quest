//! Source-neutral media stream session contracts for Quest platform adapters.
//!
//! This crate describes low-rate plans and validation rules for reusable H.264
//! media streaming. It does not open sockets, start encoders, run ADB, call
//! hidden Android APIs, or carry frame payload bytes.

mod model;
mod runtime;
mod validation;

pub use model::*;
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
            manifold_decision_id: "decision.media.display-example".to_string(),
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
        }
    }

    fn parse_fixture(text: &str) -> MediaStreamSessionPlan {
        serde_json::from_str(text).expect("media stream fixture parses")
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
}
