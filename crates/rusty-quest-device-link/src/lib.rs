//! Quest device-link contracts.
//!
//! This crate describes observed Windows/host to Quest connectivity for
//! operator frontends. It is data-only: it does not open ADB, WebSocket, UDP,
//! LSL, or app-private transports.

mod ble_rendezvous;
mod direct_p2p_socket_authority;
mod model;
mod product_wifi_direct;
mod validation;
mod wifi_direct_lifecycle;

pub use ble_rendezvous::*;
pub use direct_p2p_socket_authority::*;
pub use model::*;
pub use product_wifi_direct::*;
pub use validation::validate_device_link_report;
pub use wifi_direct_lifecycle::*;

#[cfg(test)]
mod tests {
    use super::{validate_device_link_report, DeviceLinkReport};

    fn parse_fixture(text: &str) -> DeviceLinkReport {
        serde_json::from_str(text).expect("device-link fixture parses")
    }

    #[test]
    fn hostess_usb_broker_session_fixture_validates() {
        let report = parse_fixture(include_str!(
            "../../../fixtures/device-link/hostess-usb-broker-session.device-link.json"
        ));

        validate_device_link_report(&report).expect("device-link report validates");
    }

    #[test]
    fn high_rate_json_stream_is_rejected() {
        let report = parse_fixture(include_str!(
            "../../../fixtures/damaged/device-link-high-rate-json-stream.json"
        ));

        let errors =
            validate_device_link_report(&report).expect_err("high-rate JSON must be rejected");
        assert!(errors.iter().any(|error| {
            error
                .message
                .contains("must not carry high-rate payloads as JSON")
        }));
    }

    #[test]
    fn applied_command_without_runtime_stage_is_rejected() {
        let report = parse_fixture(include_str!(
            "../../../fixtures/damaged/device-link-applied-command-missing-runtime-stage.json"
        ));

        let errors = validate_device_link_report(&report)
            .expect_err("applied command without runtime evidence must be rejected");
        assert!(errors.iter().any(|error| {
            error
                .message
                .contains("cannot be applied without runtime_accepted")
        }));
    }
}
