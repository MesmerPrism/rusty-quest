//! Product Wi-Fi Direct provider receipts.
//!
//! These contracts keep topology, Android `Network` selection, Rust-owned
//! socket I/O, and cleanup as separate authorities. They intentionally carry
//! no QCL harness or media-stream vocabulary.

use serde::{Deserialize, Serialize};

use crate::{is_supported_direct_p2p_ipv4, ValidationError, QUEST_DIRECT_P2P_INTERFACE};

/// Product provider run schema.
pub const PRODUCT_WIFI_DIRECT_RUN_SCHEMA: &str = "rusty.quest.product_wifi_direct_run.v1";
/// Android topology authority id.
pub const ANDROID_WIFI_DIRECT_TOPOLOGY_AUTHORITY: &str = "android_wifi_direct_topology_provider";
/// Android `Network` binding authority id.
pub const ANDROID_NETWORK_BINDING_AUTHORITY: &str = "android_network_binding_provider";
/// Rust-owned direct socket authority id.
pub const RUST_DIRECT_SOCKET_PROVIDER: &str = "rust_direct_socket_provider";
/// Bounded non-media exchange kind.
pub const BOUNDED_CONTROL_EXCHANGE: &str = "bounded_control_exchange";

/// Complete receipt for one product-owned two-peer Wi-Fi Direct run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProductWifiDirectRunReceipt {
    /// Schema id.
    pub schema: String,
    /// Stable run id.
    pub run_id: String,
    /// App package producing the receipt.
    pub product_package: String,
    /// Local role, `group_owner` or `client`.
    pub role: String,
    /// Topology authority evidence.
    pub topology: ProductTopologyReceipt,
    /// Android network binding evidence.
    pub network_binding: AndroidNetworkBindingReceipt,
    /// Rust socket evidence.
    pub socket: RustDirectSocketReceipt,
    /// Bounded exchange evidence.
    pub exchange: BoundedExchangeReceipt,
    /// Cleanup evidence.
    pub cleanup: ProductCleanupReceipt,
}

/// Product topology receipt owned by Android Wi-Fi P2P APIs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProductTopologyReceipt {
    /// Authority id.
    pub authority: String,
    /// True when the platform reported a formed group.
    pub group_formed: bool,
    /// Local role reported by the platform.
    pub local_role: String,
    /// Concrete group-owner IPv4 address.
    pub group_owner_host: String,
    /// True only when this provider did not create or own sockets.
    pub socket_creation_claimed: bool,
}

/// Android `Network` selection receipt; this is binding metadata, not socket authority.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidNetworkBindingReceipt {
    /// Authority id.
    pub authority: String,
    /// Whether Android exposed a bindable `Network` for the P2P route.
    pub network_available: bool,
    /// Selected Android network handle.
    pub network_handle: u64,
    /// Selected interface name.
    pub interface_name: String,
    /// Concrete local P2P IPv4 address.
    pub local_host: String,
    /// True when the network route covers the group-owner address.
    pub route_matches_group_owner: bool,
    /// True only when this provider did not create or own sockets.
    pub socket_creation_claimed: bool,
}

/// Rust-owned socket receipt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RustDirectSocketReceipt {
    /// Authority id.
    pub authority: String,
    /// Concrete local host bound before listen/connect.
    pub local_bind_host: String,
    /// Concrete peer host.
    pub peer_host: String,
    /// TCP port.
    pub peer_port: u16,
    /// True when the Rust provider performed the local bind.
    pub local_bind_applied: bool,
    /// True when the socket was closed by the Rust provider.
    pub closed: bool,
    /// Android network handle observed for correlation only.
    pub observed_android_network_handle: u64,
    /// True only if the handle substituted for Rust socket ownership; must be false.
    pub android_network_substituted_socket_authority: bool,
}

/// Bounded, non-media exchange receipt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BoundedExchangeReceipt {
    /// Exchange kind.
    pub exchange_kind: String,
    /// Payload class; must remain `control_probe`.
    pub payload_class: String,
    /// Number of messages sent.
    pub messages_sent: u32,
    /// Number of messages received.
    pub messages_received: u32,
    /// Bytes sent.
    pub bytes_sent: u64,
    /// Bytes received.
    pub bytes_received: u64,
    /// True when byte and time bounds were enforced.
    pub bounded: bool,
}

/// Product cleanup receipt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProductCleanupReceipt {
    /// True when peer discovery was stopped or already inactive.
    pub discovery_stopped: bool,
    /// True when the Wi-Fi Direct group was removed or absent.
    pub group_removed: bool,
    /// True when the Rust socket was closed.
    pub socket_closed: bool,
}

/// Validate product provider authority separation and bounded completion.
pub fn validate_product_wifi_direct_run(
    receipt: &ProductWifiDirectRunReceipt,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if receipt.schema != PRODUCT_WIFI_DIRECT_RUN_SCHEMA {
        errors.push(ValidationError::new(
            "product Wi-Fi Direct schema is unsupported",
        ));
    }
    if receipt.run_id.trim().is_empty() || receipt.product_package.trim().is_empty() {
        errors.push(ValidationError::new(
            "product Wi-Fi Direct run identity is required",
        ));
    }
    let lower_identity =
        format!("{} {}", receipt.run_id, receipt.product_package).to_ascii_lowercase();
    if lower_identity.contains("qcl") || lower_identity.contains("harness") {
        errors.push(ValidationError::new(
            "product Wi-Fi Direct receipts must not use QCL or harness identity",
        ));
    }
    if !matches!(receipt.role.as_str(), "group_owner" | "client") {
        errors.push(ValidationError::new(
            "product Wi-Fi Direct role is unsupported",
        ));
    }
    if receipt.topology.authority != ANDROID_WIFI_DIRECT_TOPOLOGY_AUTHORITY {
        errors.push(ValidationError::new(
            "topology must use the Android Wi-Fi Direct authority",
        ));
    }
    if !receipt.topology.group_formed || receipt.topology.socket_creation_claimed {
        errors.push(ValidationError::new(
            "topology must prove group formation without claiming socket creation",
        ));
    }
    if receipt.topology.local_role != receipt.role {
        errors.push(ValidationError::new(
            "topology role must match the run role",
        ));
    }
    if receipt.network_binding.authority != ANDROID_NETWORK_BINDING_AUTHORITY {
        errors.push(ValidationError::new(
            "network binding must use the Android Network authority",
        ));
    }
    if receipt.network_binding.interface_name != QUEST_DIRECT_P2P_INTERFACE {
        errors.push(ValidationError::new(
            "product network binding must select p2p0",
        ));
    }
    if (receipt.network_binding.network_available && receipt.network_binding.network_handle == 0)
        || (!receipt.network_binding.network_available
            && receipt.network_binding.network_handle != 0)
        || !receipt.network_binding.route_matches_group_owner
        || receipt.network_binding.socket_creation_claimed
    {
        errors.push(ValidationError::new(
            "Android Network evidence must truthfully report availability without claiming socket creation",
        ));
    }
    if receipt.socket.authority != RUST_DIRECT_SOCKET_PROVIDER
        || !receipt.socket.local_bind_applied
        || !receipt.socket.closed
        || receipt.socket.android_network_substituted_socket_authority
    {
        errors.push(ValidationError::new(
            "Rust must own local bind, socket close, and socket authority",
        ));
    }
    if receipt.socket.observed_android_network_handle != receipt.network_binding.network_handle {
        errors.push(ValidationError::new(
            "Rust socket correlation handle must match Android Network evidence",
        ));
    }
    for (field, value) in [
        (
            "group_owner_host",
            receipt.topology.group_owner_host.as_str(),
        ),
        (
            "network local_host",
            receipt.network_binding.local_host.as_str(),
        ),
        (
            "socket local_bind_host",
            receipt.socket.local_bind_host.as_str(),
        ),
        ("socket peer_host", receipt.socket.peer_host.as_str()),
    ] {
        match value.parse() {
            Ok(address) if is_supported_direct_p2p_ipv4(address) => {}
            _ => errors.push(ValidationError::new(format!(
                "product Wi-Fi Direct {field} must be a supported P2P IPv4 address"
            ))),
        }
    }
    if receipt.network_binding.local_host != receipt.socket.local_bind_host {
        errors.push(ValidationError::new(
            "Rust local bind must match the Android p2p0 local address",
        ));
    }
    if receipt.exchange.exchange_kind != BOUNDED_CONTROL_EXCHANGE
        || receipt.exchange.payload_class != "control_probe"
        || !receipt.exchange.bounded
        || receipt.exchange.messages_sent == 0
        || receipt.exchange.messages_received == 0
        || receipt.exchange.bytes_sent == 0
        || receipt.exchange.bytes_received == 0
        || receipt.exchange.bytes_sent > 4096
        || receipt.exchange.bytes_received > 4096
    {
        errors.push(ValidationError::new(
            "product exchange must be a bounded non-media control probe",
        ));
    }
    if !receipt.cleanup.discovery_stopped
        || !receipt.cleanup.group_removed
        || !receipt.cleanup.socket_closed
    {
        errors.push(ValidationError::new(
            "product Wi-Fi Direct cleanup is incomplete",
        ));
    }
    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> ProductWifiDirectRunReceipt {
        let text = match name {
            "pass" => {
                include_str!("../../../fixtures/device-link/product-wifi-direct-run.pass.json")
            }
            "authority" => include_str!(
                "../../../fixtures/damaged/product-wifi-direct-android-socket-authority.json"
            ),
            "cleanup" => include_str!(
                "../../../fixtures/damaged/product-wifi-direct-incomplete-cleanup.json"
            ),
            _ => unreachable!(),
        };
        serde_json::from_str(text).expect("fixture parses")
    }

    #[test]
    fn product_run_validates() {
        validate_product_wifi_direct_run(&fixture("pass")).expect("product run validates");
    }

    #[test]
    fn android_socket_authority_is_rejected() {
        let errors = validate_product_wifi_direct_run(&fixture("authority"))
            .expect_err("Android socket authority must fail");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("without claiming socket creation")));
    }

    #[test]
    fn incomplete_cleanup_is_rejected() {
        let errors = validate_product_wifi_direct_run(&fixture("cleanup"))
            .expect_err("incomplete cleanup must fail");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("cleanup is incomplete")));
    }
}
