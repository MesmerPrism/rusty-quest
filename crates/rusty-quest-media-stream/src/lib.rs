//! Source-neutral media stream session contracts for Quest platform adapters.
//!
//! This crate describes low-rate plans and validation rules for reusable H.264
//! media streaming. It does not open sockets, start encoders, run ADB, call
//! hidden Android APIs, or carry frame payload bytes.

mod model;
mod validation;

pub use model::*;
pub use validation::validate_media_stream_session;

#[cfg(test)]
mod tests {
    use super::{validate_media_stream_session, MediaStreamSessionPlan};

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
}
