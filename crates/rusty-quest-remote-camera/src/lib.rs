//! Remote camera session contracts for Quest platform adapters.
//!
//! This crate describes low-rate session plans and validation rules. It does
//! not open sockets, start cameras, decode media, or own live Manifold command
//! authority.

mod model;
mod profile;
mod validation;

pub use model::*;
pub use profile::build_endpoint_runtime_profile;
pub use validation::validate_remote_camera_session;

#[cfg(test)]
mod tests {
    use super::{
        build_endpoint_runtime_profile, validate_remote_camera_session, RemoteCameraSessionPlan,
    };

    fn parse_fixture(text: &str) -> RemoteCameraSessionPlan {
        serde_json::from_str(text).expect("remote camera fixture parses")
    }

    #[test]
    fn q2q_two_way_lan_fixture_validates() {
        let plan = parse_fixture(include_str!(
            "../../../fixtures/remote-camera-sessions/q2q-two-way-lan.plan.json"
        ));

        validate_remote_camera_session(&plan).expect("q2q plan validates");
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
