//! Reusable direct-P2P socket-route contract.

use std::net::Ipv4Addr;

use serde::{Deserialize, Serialize};

use crate::ValidationError;

/// Canonical direct-P2P socket-route schema.
pub const DIRECT_P2P_SOCKET_ROUTE_SCHEMA: &str = "rusty.quest.direct_p2p_socket_route.v1";
/// Ordinary direct TCP route selected by the platform network stack.
pub const DIRECT_TCP_ROUTE_KIND: &str = "direct_tcp_connect";
/// Direct TCP route whose Rusty-owned sockets retain explicit P2P authority.
pub const DIRECT_P2P_ROUTE_KIND: &str = "direct_p2p_tcp";
/// Platform/default socket selection for non-scoped routes.
pub const PLATFORM_DEFAULT_SOCKET_AUTHORITY: &str = "platform_default_socket_authority";
/// Authority that binds a Rusty-owned socket to the observed local P2P address.
pub const RUSTY_DIRECT_P2P_SOCKET_AUTHORITY: &str = "rusty_direct_p2p_socket_authority";
/// Scope of the custom authority; it never claims authority over third-party sockets.
pub const RUSTY_OWNED_SOCKET_SCOPE: &str = "rusty_owned_sockets_only";
/// Expected Quest Wi-Fi Direct interface for the current contract version.
pub const QUEST_DIRECT_P2P_INTERFACE: &str = "p2p0";

/// Canonical plan for one Rusty-owned direct-P2P TCP socket route.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DirectP2pSocketRoute {
    /// Schema id.
    pub schema: String,
    /// Stable route id within the owning stream/session plan.
    pub route_id: String,
    /// Must be `direct_p2p_tcp`.
    pub route_kind: String,
    /// Must be `rusty_direct_p2p_socket_authority`.
    pub socket_authority: String,
    /// Must remain scoped to Rusty-owned sockets.
    pub socket_scope: String,
    /// Expected local Wi-Fi Direct interface.
    pub expected_interface: String,
    /// Concrete local P2P IPv4 address to bind before connect/listen.
    pub local_bind_host: String,
    /// Concrete peer P2P IPv4 address.
    pub peer_host: String,
    /// Peer TCP port.
    pub peer_port: u16,
    /// A bindable Android `Network` may be used when present, but is not required.
    pub android_network_required: bool,
}

/// Parsed address/port result from a validated direct-P2P route.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ValidatedDirectP2pSocketRoute {
    /// Local address the adapter must bind.
    pub local_bind_ipv4: Ipv4Addr,
    /// Peer address the adapter may connect.
    pub peer_ipv4: Ipv4Addr,
    /// Peer TCP port.
    pub peer_port: u16,
}

/// Validate the reusable direct-P2P route independently of any media protocol.
pub fn validate_direct_p2p_socket_route(
    route: &DirectP2pSocketRoute,
) -> Result<ValidatedDirectP2pSocketRoute, Vec<ValidationError>> {
    let mut errors = Vec::new();
    if route.schema != DIRECT_P2P_SOCKET_ROUTE_SCHEMA {
        errors.push(ValidationError::new(
            "direct P2P socket route schema is unsupported",
        ));
    }
    if !is_safe_route_id(&route.route_id) {
        errors.push(ValidationError::new(
            "direct P2P socket route_id must be a 1..=64 character safe token",
        ));
    }
    if route.route_kind != DIRECT_P2P_ROUTE_KIND {
        errors.push(ValidationError::new(format!(
            "direct P2P route {} route_kind must be {}",
            route.route_id, DIRECT_P2P_ROUTE_KIND
        )));
    }
    if route.socket_authority != RUSTY_DIRECT_P2P_SOCKET_AUTHORITY {
        errors.push(ValidationError::new(format!(
            "direct P2P route {} requires socket_authority {}",
            route.route_id, RUSTY_DIRECT_P2P_SOCKET_AUTHORITY
        )));
    }
    if route.socket_scope != RUSTY_OWNED_SOCKET_SCOPE {
        errors.push(ValidationError::new(format!(
            "direct P2P route {} socket authority must remain scoped to Rusty-owned sockets",
            route.route_id
        )));
    }
    if route.expected_interface != QUEST_DIRECT_P2P_INTERFACE {
        errors.push(ValidationError::new(format!(
            "direct P2P route {} expected_interface must be {}",
            route.route_id, QUEST_DIRECT_P2P_INTERFACE
        )));
    }
    if route.android_network_required {
        errors.push(ValidationError::new(format!(
            "direct P2P route {} must not require or claim an Android Network substitution",
            route.route_id
        )));
    }
    if route.peer_port == 0 {
        errors.push(ValidationError::new(format!(
            "direct P2P route {} peer_port must be nonzero",
            route.route_id
        )));
    }

    let local = parse_supported_p2p_ipv4(
        &route.route_id,
        "local_bind_host",
        &route.local_bind_host,
        &mut errors,
    );
    let peer =
        parse_supported_p2p_ipv4(&route.route_id, "peer_host", &route.peer_host, &mut errors);
    if let (Some(local), Some(peer)) = (local, peer) {
        if local.octets()[..3] != peer.octets()[..3] {
            errors.push(ValidationError::new(format!(
                "direct P2P route {} local and peer addresses must share an IPv4 /24",
                route.route_id
            )));
        }
        if local == peer {
            errors.push(ValidationError::new(format!(
                "direct P2P route {} local and peer addresses must differ",
                route.route_id
            )));
        }
    }

    if !errors.is_empty() {
        return Err(errors);
    }
    let (Some(local_bind_ipv4), Some(peer_ipv4)) = (local, peer) else {
        return Err(vec![ValidationError::new(
            "direct P2P route address validation did not produce parsed addresses",
        )]);
    };
    Ok(ValidatedDirectP2pSocketRoute {
        local_bind_ipv4,
        peer_ipv4,
        peer_port: route.peer_port,
    })
}

/// Return whether an IPv4 address is in the supported Quest P2P ranges.
pub fn is_supported_direct_p2p_ipv4(address: Ipv4Addr) -> bool {
    let octets = address.octets();
    octets[0] == 192
        && octets[1] == 168
        && matches!(octets[2], 49 | 137)
        && !matches!(octets[3], 0 | 255)
}

fn parse_supported_p2p_ipv4(
    route_id: &str,
    field: &str,
    value: &str,
    errors: &mut Vec<ValidationError>,
) -> Option<Ipv4Addr> {
    if value.trim().is_empty() {
        errors.push(ValidationError::new(format!(
            "direct P2P route {route_id} requires {field}"
        )));
        return None;
    }
    let Ok(address) = value.parse::<Ipv4Addr>() else {
        errors.push(ValidationError::new(format!(
            "direct P2P route {route_id} {field} must be a concrete IPv4 address"
        )));
        return None;
    };
    if !is_supported_direct_p2p_ipv4(address) {
        errors.push(ValidationError::new(format!(
            "direct P2P route {route_id} {field} must be a supported P2P IPv4 address"
        )));
        return None;
    }
    Some(address)
}

fn is_safe_route_id(value: &str) -> bool {
    (1..=64).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_fixture(text: &str) -> DirectP2pSocketRoute {
        serde_json::from_str(text).expect("direct P2P socket route fixture parses")
    }

    #[test]
    fn canonical_route_fixture_validates() {
        let route = parse_fixture(include_str!(
            "../../../fixtures/device-link/direct-p2p-socket-route.pass.json"
        ));
        let validated = validate_direct_p2p_socket_route(&route).expect("route validates");
        assert_eq!(validated.local_bind_ipv4, Ipv4Addr::new(192, 168, 49, 1));
        assert_eq!(validated.peer_ipv4, Ipv4Addr::new(192, 168, 49, 2));
        assert_eq!(validated.peer_port, 9079);
    }

    #[test]
    fn android_network_substitution_fixture_is_rejected() {
        let route = parse_fixture(include_str!(
            "../../../fixtures/damaged/direct-p2p-socket-route-android-network-substitution.json"
        ));
        let errors = validate_direct_p2p_socket_route(&route)
            .expect_err("Android Network substitution claim must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("Android Network substitution")));
        assert!(errors
            .iter()
            .any(|error| error.message.contains("requires socket_authority")));
    }

    #[test]
    fn wlan_fallback_fixture_is_rejected() {
        let route = parse_fixture(include_str!(
            "../../../fixtures/damaged/direct-p2p-socket-route-wlan-fallback.json"
        ));
        let errors = validate_direct_p2p_socket_route(&route)
            .expect_err("WLAN fallback address must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("supported P2P IPv4 address")));
    }
}
