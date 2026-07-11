//! Remote camera session contracts for Quest platform adapters.
//!
//! This crate describes low-rate session plans and validation rules. It does
//! not open sockets, start cameras, decode media, or own live Manifold command
//! authority.

mod media_stream;
mod model;
mod packed_stream;
mod profile;
mod validation;

pub use media_stream::{build_media_stream_runtime_spec, build_media_stream_session_plan};
pub use model::*;
pub use packed_stream::*;
pub use profile::build_endpoint_runtime_profile;
pub use validation::validate_remote_camera_session;

#[cfg(test)]
mod tests {
    use super::{
        build_endpoint_runtime_profile, build_media_stream_runtime_spec,
        build_media_stream_session_plan, decode_packed_pair_metadata, encode_packed_pair_metadata,
        validate_packed_pair_metadata, validate_packed_pair_sequence,
        validate_packed_stream_metadata, validate_remote_camera_session, PackedStereoPairMetadata,
        PackedStereoStreamMetadata, RemoteCameraPortBinding, RemoteCameraSessionPlan,
        MEDIA_LAYOUT_SEPARATE_EYE_STREAMS,
    };
    use rusty_quest_media_stream::{
        validate_media_stream_session, validate_media_stream_source_conformance,
        MediaStreamSessionRuntime,
    };
    use serde::Deserialize;

    #[derive(Debug, Deserialize)]
    struct DamageCase {
        id: String,
        expected_error: String,
    }

    #[derive(Debug, Deserialize)]
    struct PackedStreamFixture {
        metadata: PackedStereoStreamMetadata,
        header_width: u32,
        header_height: u32,
        pair_sequence: Vec<PackedStereoPairMetadata>,
    }

    fn parse_fixture(text: &str) -> RemoteCameraSessionPlan {
        serde_json::from_str(text).expect("remote camera fixture parses")
    }

    #[test]
    fn q2q_two_way_lan_fixture_validates() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-two-way-lan.plan.json"
        ));

        validate_remote_camera_session(&plan).expect("q2q plan validates");
        assert_eq!(plan.media_layout, MEDIA_LAYOUT_SEPARATE_EYE_STREAMS);
        assert!(plan
            .lanes
            .iter()
            .all(|lane| lane.media.frame_layout.is_none()));
    }

    #[test]
    fn q2q_fixture_maps_to_generic_media_stream_plan() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-two-way-lan.plan.json"
        ));

        let media_plan =
            build_media_stream_session_plan(&plan).expect("q2q media stream plan builds");
        validate_media_stream_session(&media_plan).expect("q2q media stream plan validates");
        assert_eq!(media_plan.schema, "rusty.quest.media_stream_session.v1");
        assert_eq!(media_plan.topology_id, "quest_to_quest_two_way");
        assert!(media_plan
            .lanes
            .iter()
            .all(|lane| lane.media.high_rate_payload_plane == "binary-media"));
    }

    #[test]
    fn camera2_source_is_an_independent_generic_consumer() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-two-way-lan.plan.json"
        ));
        let media_plan = build_media_stream_session_plan(&plan).expect("compat plan builds");
        let source_id = media_plan.sources[0].source_id.clone();
        let receipt = validate_media_stream_source_conformance(&media_plan, &source_id)
            .expect("Camera2 source conforms");
        assert!(receipt.camera_permission_required);
        assert!(!receipt.consent_required);
        assert!(!receipt.permission_or_profile_bleed);
    }

    #[test]
    fn direct_p2p_remote_camera_maps_into_generic_runtime_without_behavior_drift() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-direct-p2p-mono.plan.json"
        ));
        let spec = build_media_stream_runtime_spec(&plan, "decision.media.compat", 8)
            .expect("compat runtime builds");
        assert_eq!(spec.plan.lanes.len(), plan.lanes.len());
        assert_eq!(
            spec.plan.transport_routes.len(),
            plan.transport_routes.len()
        );
        assert!(spec
            .plan
            .transport_routes
            .iter()
            .all(|route| route.route_kind == "direct_p2p_tcp"));
        assert_eq!(spec.direct_p2p_routes.len(), plan.transport_routes.len());
        let runtime = MediaStreamSessionRuntime::new(spec).expect("generic runtime constructs");
        assert_eq!(runtime.spec().manifold_session_revision, 8);
    }

    #[test]
    fn packed_remote_camera_maps_to_generic_packed_processor() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-direct-p2p-packed-sbs.plan.json"
        ));
        let spec = build_media_stream_runtime_spec(&plan, "decision.media.packed", 9)
            .expect("packed compatibility runtime builds");
        assert_eq!(spec.processors[0].processor_kind, "packed_sbs_left_right");
        assert!(!spec.processors[0].owns_codec);
        assert!(!spec.processors[0].cpu_pixel_copy);
    }

    #[test]
    fn q2q_packed_sbs_fixture_validates_and_emits_explicit_layout() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-direct-p2p-packed-sbs.plan.json"
        ));
        validate_remote_camera_session(&plan).expect("packed SBS plan validates");

        let profile = build_endpoint_runtime_profile(
            &plan,
            "quest-a",
            "profile.quest.remote_camera.q2q_packed_sbs.quest_a",
        )
        .expect("packed profile builds");
        let value = |name: &str| {
            profile
                .set_properties
                .iter()
                .find(|property| property.name == name)
                .map(|property| property.value.as_str())
                .expect("packed property exists")
        };
        assert_eq!(
            value("debug.rustyquest.remote_camera.media_layout"),
            "side-by-side-left-right"
        );
        assert_eq!(
            value("debug.rustyquest.remote_camera.sender_source_ports"),
            "stereo:8879"
        );
        assert_eq!(
            value("debug.rustyquest.remote_camera.sender_frame_layout"),
            "sbs-lr|2560x1280|1280x1280|c2sensor|nearest|20000000|gpu|nostale"
        );
    }

    #[test]
    fn packed_sbs_damage_fixture_cases_fail_closed() {
        let baseline = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-direct-p2p-packed-sbs.plan.json"
        ));
        let cases: Vec<DamageCase> = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/remote-camera-packed-sbs-plan.damage-cases.json"
        ))
        .expect("packed plan damage cases parse");

        for case in cases {
            let mut plan = baseline.clone();
            match case.id.as_str() {
                "wrong-packed-width" => {
                    plan.lanes[0]
                        .media
                        .frame_layout
                        .as_mut()
                        .expect("layout")
                        .packed_width = 641;
                }
                "wrong-eye-order" => {
                    plan.lanes[0]
                        .media
                        .frame_layout
                        .as_mut()
                        .expect("layout")
                        .eye_order = vec!["right".to_string(), "left".to_string()];
                }
                "missing-camera-binding" => {
                    plan.runtime_endpoints[0].sender_camera_ids.pop();
                }
                "duplicate-camera-id" => {
                    plan.runtime_endpoints[0].sender_camera_ids[1].camera_id = "50".to_string();
                }
                "more-than-one-packed-port" => {
                    plan.runtime_endpoints[0]
                        .sender_source_ports
                        .push(RemoteCameraPortBinding {
                            eye: "stereo".to_string(),
                            port: 8880,
                        });
                }
                "non-binary-high-rate-payload" => {
                    plan.lanes[0].media.high_rate_payload_plane = "inline-json".to_string();
                }
                "wrong-direct-p2p-socket-authority" | "android-network-substitution" => {
                    plan.transport_routes[0].socket_authority =
                        Some("android_connectivitymanager_network".to_string());
                }
                "wlan-local-bind-fallback" => {
                    plan.transport_routes[0].local_bind_host = Some("10.0.0.5".to_string());
                    plan.runtime_endpoints[0].transport_bind_host = "10.0.0.5".to_string();
                }
                other => panic!("unknown packed plan damage case {other}"),
            }
            let messages = validate_remote_camera_session(&plan)
                .expect_err("damaged packed plan must reject")
                .into_iter()
                .map(|error| error.message)
                .collect::<Vec<_>>()
                .join("\n");
            assert!(
                messages.contains(&case.expected_error),
                "case {} expected {:?}, got:\n{}",
                case.id,
                case.expected_error,
                messages
            );
        }
    }

    #[test]
    fn rmanvid_v4_packed_stream_fixture_and_binary_pair_extension_validate() {
        let fixture: PackedStreamFixture = serde_json::from_str(include_str!(
            "../../../fixtures/remote-camera-sessions/rmanvid-v4-packed-stereo.pass.json"
        ))
        .expect("packed stream fixture parses");
        validate_packed_stream_metadata(
            &fixture.metadata,
            fixture.header_width,
            fixture.header_height,
        )
        .expect("packed stream metadata validates");
        validate_packed_pair_sequence(&fixture.pair_sequence, fixture.metadata.max_pair_delta_ns)
            .expect("packed pair sequence validates");
        let bytes = encode_packed_pair_metadata(fixture.pair_sequence[0]);
        assert_eq!(
            decode_packed_pair_metadata(&bytes).expect("pair extension decodes"),
            fixture.pair_sequence[0]
        );
    }

    #[test]
    fn rmanvid_v4_packed_stream_damage_fixture_cases_fail_closed() {
        let baseline: PackedStreamFixture = serde_json::from_str(include_str!(
            "../../../fixtures/remote-camera-sessions/rmanvid-v4-packed-stereo.pass.json"
        ))
        .expect("packed stream fixture parses");
        let cases: Vec<DamageCase> = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/rmanvid-v4-packed-stereo.damage-cases.json"
        ))
        .expect("packed stream damage cases parse");

        for case in cases {
            let message = match case.id.as_str() {
                "unknown-rmanvid-schema" => {
                    let mut metadata = baseline.metadata.clone();
                    metadata.rmanvid_schema_version = 5;
                    packed_metadata_error(&metadata, baseline.header_width, baseline.header_height)
                }
                "truncated-pair-extension" => {
                    decode_packed_pair_metadata(&[0u8; 47])
                        .expect_err("truncated pair must reject")
                        .message
                }
                "impossible-pair-delta" => {
                    let mut pair = baseline.pair_sequence[0];
                    pair.pair_delta_ns += 1;
                    validate_packed_pair_metadata(&pair, false, baseline.metadata.max_pair_delta_ns)
                        .expect_err("impossible pair delta must reject")
                        .message
                }
                "excessive-source-timestamp-skew" => {
                    validate_packed_pair_metadata(&baseline.pair_sequence[0], false, 1)
                        .expect_err("excessive skew must reject")
                        .message
                }
                "duplicate-reused-source-frame" => {
                    let mut pairs = baseline.pair_sequence.clone();
                    pairs[1].left_source_frame = pairs[0].left_source_frame;
                    validate_packed_pair_sequence(&pairs, baseline.metadata.max_pair_delta_ns)
                        .expect_err("source reuse must reject")
                        .message
                }
                "cpu-compositor-fallback" => {
                    let mut metadata = baseline.metadata.clone();
                    metadata.cpu_pixel_copy = true;
                    packed_metadata_error(&metadata, baseline.header_width, baseline.header_height)
                }
                "receiver-metadata-dimension-mismatch" => packed_metadata_error(
                    &baseline.metadata,
                    baseline.header_width - 1,
                    baseline.header_height,
                ),
                "codec-config-with-video-pair" => {
                    validate_packed_pair_metadata(
                        &baseline.pair_sequence[0],
                        true,
                        baseline.metadata.max_pair_delta_ns,
                    )
                    .expect_err("codec config pair must reject")
                    .message
                }
                other => panic!("unknown packed stream damage case {other}"),
            };
            assert!(
                message.contains(&case.expected_error),
                "case {} expected {:?}, got {:?}",
                case.id,
                case.expected_error,
                message
            );
        }
    }

    fn packed_metadata_error(
        metadata: &PackedStereoStreamMetadata,
        header_width: u32,
        header_height: u32,
    ) -> String {
        validate_packed_stream_metadata(metadata, header_width, header_height)
            .expect_err("damaged packed metadata must reject")
            .into_iter()
            .map(|error| error.message)
            .collect::<Vec<_>>()
            .join("\n")
    }

    #[test]
    fn q2q_direct_p2p_fixture_validates_and_emits_scoped_authority() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-direct-p2p-mono.plan.json"
        ));

        validate_remote_camera_session(&plan).expect("direct P2P plan validates");
        let profile = build_endpoint_runtime_profile(
            &plan,
            "quest-a",
            "profile.quest.remote_camera.q2q_direct_p2p.quest_a",
        )
        .expect("direct P2P profile builds");

        let routes = profile
            .set_properties
            .iter()
            .find(|property| property.name == "debug.rustyquest.remote_camera.transport_routes")
            .expect("transport routes property exists");
        assert_eq!(
            routes.value,
            "quest-a-mono-to-quest-b|mono|direct_p2p_tcp|192.168.49.2|9079"
        );
        let local_bind = profile
            .set_properties
            .iter()
            .find(|property| {
                property.name == "debug.rustyquest.remote_camera.transport_bind_local_address"
            })
            .expect("direct P2P local bind property exists");
        assert_eq!(local_bind.value, "192.168.49.1");
        let socket_authority = profile
            .set_properties
            .iter()
            .find(|property| {
                property.name == "debug.rustyquest.remote_camera.transport_socket_authority"
            })
            .expect("direct P2P socket authority property exists");
        assert_eq!(socket_authority.value, "rusty_direct_p2p_socket_authority");
    }

    #[test]
    fn direct_p2p_route_without_local_bind_is_rejected() {
        let mut plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-direct-p2p-mono.plan.json"
        ));
        plan.transport_routes[0].local_bind_host = None;

        let errors = validate_remote_camera_session(&plan)
            .expect_err("direct P2P route without local bind must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("requires local_bind_host")));
    }

    #[test]
    fn direct_p2p_route_with_wrong_authority_is_rejected() {
        let mut plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-direct-p2p-mono.plan.json"
        ));
        plan.transport_routes[0].socket_authority =
            Some("android_connectivitymanager_network".to_string());

        let errors = validate_remote_camera_session(&plan)
            .expect_err("direct P2P route with wrong authority must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("requires socket_authority")));
    }

    #[test]
    fn direct_p2p_route_with_wlan_address_is_rejected() {
        let mut plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-direct-p2p-mono.plan.json"
        ));
        plan.transport_routes[0].local_bind_host = Some("10.0.0.5".to_string());
        plan.runtime_endpoints[0].transport_bind_host = "10.0.0.5".to_string();

        let errors = validate_remote_camera_session(&plan)
            .expect_err("direct P2P route with WLAN address must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("supported P2P IPv4 address")));
    }

    #[test]
    fn quest_android_phone_duplex_fixture_validates() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/quest-android-phone-duplex.plan.json"
        ));

        validate_remote_camera_session(&plan).expect("quest-phone plan validates");
    }

    #[test]
    fn high_rate_json_payload_plane_is_rejected() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/damaged/remote-camera-high-rate-json.plan.json"
        ));

        let errors =
            validate_remote_camera_session(&plan).expect_err("high-rate JSON must be rejected");
        assert!(errors.iter().any(|error| {
            error
                .message
                .contains("high-rate media must use binary-media payload plane")
        }));
    }

    #[test]
    fn zero_runtime_endpoint_port_is_rejected() {
        let mut plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-two-way-lan.plan.json"
        ));
        plan.runtime_endpoints[0].receiver_ports[0].port = 0;

        let errors = validate_remote_camera_session(&plan)
            .expect_err("zero runtime endpoint port must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("port for left must be nonzero")));
    }

    #[test]
    fn q2q_endpoint_runtime_profile_matches_fixture() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-two-way-lan.plan.json"
        ));
        let expected: rusty_quest_profile::RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-remote-camera-q2q-diagnostic.profile.json"
        ))
        .expect("runtime profile fixture parses");

        let actual = build_endpoint_runtime_profile(
            &plan,
            "quest-a",
            "profile.quest.remote_camera.q2q_two_way.quest_a",
        )
        .expect("endpoint profile builds");

        assert_eq!(actual, expected);
    }

    #[test]
    fn unknown_endpoint_runtime_profile_is_rejected() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-two-way-lan.plan.json"
        ));
        let errors = build_endpoint_runtime_profile(
            &plan,
            "quest-missing",
            "profile.quest.remote_camera.missing",
        )
        .expect_err("unknown endpoint must be rejected");

        assert!(errors
            .iter()
            .any(|error| error.message.contains("is not in the session plan")));
    }
}
