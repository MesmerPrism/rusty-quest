//! BLE/GATT rendezvous contracts for low-rate Quest peer coordination.

use std::net::Ipv4Addr;

use serde::{Deserialize, Serialize};

use crate::{is_supported_direct_p2p_ipv4, ValidationError};

/// Compact wire-message magic.
pub const BLE_RENDEZVOUS_MESSAGE_MAGIC: &str = "rqrv";
/// Current compact wire-message version.
pub const BLE_RENDEZVOUS_MESSAGE_VERSION: u8 = 1;
/// Maximum serialized message size before GATT framing.
pub const BLE_RENDEZVOUS_MAX_WIRE_BYTES: usize = 220;
/// Sidecar lifecycle receipt schema.
pub const BLE_RENDEZVOUS_RECEIPT_SCHEMA: &str = "rusty.quest.ble_rendezvous_sidecar_receipt.v1";
/// Two-peer role-swap acceptance artifact schema.
pub const BLE_RENDEZVOUS_PAIR_SCHEMA: &str = "rusty.quest.peer_rendezvous_android_pair.v1";

/// BLE/GATT transport capability bit.
pub const BLE_CAPABILITY_GATT: u16 = 1 << 0;
/// Wi-Fi Direct rendezvous capability bit.
pub const BLE_CAPABILITY_WIFI_DIRECT: u16 = 1 << 1;
/// Rusty direct-P2P socket authority capability bit.
pub const BLE_CAPABILITY_RUSTY_DIRECT_P2P: u16 = 1 << 2;
/// Local Manifold broker endpoint capability bit.
pub const BLE_CAPABILITY_MANIFOLD_BROKER: u16 = 1 << 3;
const BLE_KNOWN_CAPABILITIES: u16 = BLE_CAPABILITY_GATT
    | BLE_CAPABILITY_WIFI_DIRECT
    | BLE_CAPABILITY_RUSTY_DIRECT_P2P
    | BLE_CAPABILITY_MANIFOLD_BROKER;

/// Low-rate rendezvous message kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BleRendezvousMessageKind {
    /// Advertiser offers its current capabilities and role preference.
    Offer,
    /// Scanner proposes a compatible rendezvous epoch.
    Proposal,
    /// Advertiser accepts a proposal.
    Accept,
    /// Either peer reports a bounded lifecycle update.
    Status,
    /// Either peer closes the rendezvous epoch.
    Close,
}

impl BleRendezvousMessageKind {
    fn token(self) -> &'static str {
        match self {
            Self::Offer => "offer",
            Self::Proposal => "proposal",
            Self::Accept => "accept",
            Self::Status => "status",
            Self::Close => "close",
        }
    }
}

/// Preferred Wi-Fi Direct role. This is a proposal, not role authority.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BleRendezvousRolePreference {
    /// Prefer Wi-Fi Direct group owner.
    GroupOwner,
    /// Prefer Wi-Fi Direct client.
    Client,
    /// Either Wi-Fi Direct role is acceptable.
    Either,
}

impl BleRendezvousRolePreference {
    fn token(self) -> &'static str {
        match self {
            Self::GroupOwner => "group_owner",
            Self::Client => "client",
            Self::Either => "either",
        }
    }
}

/// Observed Wi-Fi Direct lifecycle phase carried as a hint.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BleRendezvousWifiState {
    /// Wi-Fi Direct is idle.
    Idle,
    /// Peer discovery or group negotiation is in progress.
    Discovering,
    /// A group exists and a local P2P address is known.
    Grouped,
    /// The P2P address and local broker endpoint are ready.
    Ready,
    /// The current rendezvous attempt failed.
    Failed,
}

impl BleRendezvousWifiState {
    fn token(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Discovering => "discovering",
            Self::Grouped => "grouped",
            Self::Ready => "ready",
            Self::Failed => "failed",
        }
    }
}

/// Compact authenticated BLE rendezvous message.
///
/// Field names serialize to short wire keys so a complete message stays
/// bounded. The adapter computes and verifies `auth_tag` with a pre-provisioned
/// ephemeral lab secret; the secret itself never appears in this message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BleRendezvousMessage {
    /// Wire magic (`rqrv`).
    #[serde(rename = "m")]
    pub magic: String,
    /// Wire version.
    #[serde(rename = "v")]
    pub version: u8,
    /// Message kind.
    #[serde(rename = "k")]
    pub kind: BleRendezvousMessageKind,
    /// Ephemeral session tag; never a device serial or Bluetooth address.
    #[serde(rename = "sid")]
    pub session_tag: String,
    /// Ephemeral peer tag; never a device serial or Bluetooth address.
    #[serde(rename = "pid")]
    pub peer_tag: String,
    /// Monotonically increasing rendezvous epoch.
    #[serde(rename = "e")]
    pub epoch: u32,
    /// Monotonically increasing message sequence within the epoch.
    #[serde(rename = "q")]
    pub sequence: u32,
    /// Proposed Wi-Fi Direct role.
    #[serde(rename = "r")]
    pub role_preference: BleRendezvousRolePreference,
    /// Capability bit set.
    #[serde(rename = "c")]
    pub capabilities: u16,
    /// Observed Wi-Fi Direct phase.
    #[serde(rename = "ws")]
    pub wifi_state: BleRendezvousWifiState,
    /// Optional local P2P IPv4 hint.
    #[serde(rename = "ip", skip_serializing_if = "Option::is_none")]
    pub p2p_ipv4: Option<Ipv4Addr>,
    /// Optional local Manifold broker port hint.
    #[serde(rename = "bp", skip_serializing_if = "Option::is_none")]
    pub broker_port: Option<u16>,
    /// Message validity window from receipt, in milliseconds.
    #[serde(rename = "ttl")]
    pub ttl_ms: u32,
    /// Per-message hexadecimal nonce.
    #[serde(rename = "n")]
    pub nonce: String,
    /// Truncated hexadecimal HMAC tag over the canonical signing input.
    #[serde(rename = "a")]
    pub auth_tag: String,
}

/// Validate a compact BLE rendezvous message without verifying its HMAC.
///
/// Cryptographic HMAC verification is adapter-owned because this data-only
/// crate never receives or stores the shared secret.
pub fn validate_ble_rendezvous_message(
    message: &BleRendezvousMessage,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if message.magic != BLE_RENDEZVOUS_MESSAGE_MAGIC {
        errors.push(ValidationError::new(
            "BLE rendezvous message magic must be rqrv",
        ));
    }
    if message.version != BLE_RENDEZVOUS_MESSAGE_VERSION {
        errors.push(ValidationError::new(
            "BLE rendezvous message version is unsupported",
        ));
    }
    validate_ephemeral_tag("session_tag", &message.session_tag, &mut errors);
    validate_ephemeral_tag("peer_tag", &message.peer_tag, &mut errors);
    if message.epoch == 0 {
        errors.push(ValidationError::new("BLE rendezvous epoch must be nonzero"));
    }
    if message.sequence == 0 {
        errors.push(ValidationError::new(
            "BLE rendezvous sequence must be nonzero",
        ));
    }
    if !(1_000..=120_000).contains(&message.ttl_ms) {
        errors.push(ValidationError::new(
            "BLE rendezvous ttl_ms must be 1000..=120000",
        ));
    }
    if message.capabilities & BLE_CAPABILITY_GATT == 0 {
        errors.push(ValidationError::new(
            "BLE rendezvous message must declare the GATT capability",
        ));
    }
    if message.capabilities & !BLE_KNOWN_CAPABILITIES != 0 {
        errors.push(ValidationError::new(
            "BLE rendezvous message contains unknown capability bits",
        ));
    }
    if message.capabilities & BLE_CAPABILITY_RUSTY_DIRECT_P2P != 0
        && message.capabilities & BLE_CAPABILITY_WIFI_DIRECT == 0
    {
        errors.push(ValidationError::new(
            "Rusty direct-P2P capability requires Wi-Fi Direct capability",
        ));
    }
    validate_wifi_hint(message, &mut errors);
    if !is_hex_token(&message.nonce, 16, 32) {
        errors.push(ValidationError::new(
            "BLE rendezvous nonce must be 16..=32 hexadecimal characters",
        ));
    }
    if !is_hex_token(&message.auth_tag, 16, 16) {
        errors.push(ValidationError::new(
            "BLE rendezvous auth_tag must be 16 hexadecimal characters",
        ));
    }
    match serde_json::to_vec(message) {
        Ok(bytes) if bytes.len() > BLE_RENDEZVOUS_MAX_WIRE_BYTES => {
            errors.push(ValidationError::new(format!(
                "BLE rendezvous message is {} bytes, above {}",
                bytes.len(),
                BLE_RENDEZVOUS_MAX_WIRE_BYTES
            )));
        }
        Err(error) => errors.push(ValidationError::new(format!(
            "BLE rendezvous message serialization failed: {error}"
        ))),
        _ => {}
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

/// Build the stable UTF-8 input that an adapter signs with HMAC-SHA256.
pub fn ble_rendezvous_signing_input(message: &BleRendezvousMessage) -> String {
    format!(
        "RQRV1|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
        message.kind.token(),
        message.session_tag,
        message.peer_tag,
        message.epoch,
        message.sequence,
        message.role_preference.token(),
        message.capabilities,
        message.wifi_state.token(),
        message
            .p2p_ipv4
            .map_or_else(|| "-".to_string(), |address| address.to_string()),
        message.broker_port.unwrap_or(0),
        message.ttl_ms,
        message.nonce,
    )
}

/// Sidecar execution role for one bounded receipt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BleRendezvousSidecarRole {
    /// GATT server and advertiser.
    Server,
    /// GATT client and scanner.
    Client,
}

/// Terminal sidecar receipt status.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BleRendezvousReceiptStatus {
    /// One-device adapter readiness was proven without a peer exchange.
    Ready,
    /// An authenticated peer exchange completed.
    Pass,
    /// Preflight or platform policy blocked execution.
    Blocked,
    /// Execution failed.
    Fail,
}

/// Terminal lifecycle receipt emitted by one Quest BLE sidecar role.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BleRendezvousSidecarReceipt {
    /// Receipt schema id.
    pub schema: String,
    /// Bounded run id.
    pub run_id: String,
    /// Ephemeral rendezvous session tag.
    pub session_tag: String,
    /// Ephemeral local peer tag.
    pub peer_tag: String,
    /// Sidecar role.
    pub role: BleRendezvousSidecarRole,
    /// Terminal status.
    pub status: BleRendezvousReceiptStatus,
    /// The feature was explicitly enabled for this run.
    pub explicit_opt_in: bool,
    /// Android BLE adapter was available.
    pub adapter_available: bool,
    /// Bluetooth was enabled.
    pub bluetooth_enabled: bool,
    /// Required role permissions were granted.
    pub permissions_granted: bool,
    /// Local encode/sign/parse/verify self-test passed without counting as peer evidence.
    pub protocol_self_test_passed: bool,
    /// Advertising started.
    pub advertising_started: bool,
    /// Advertising stopped before receipt emission.
    pub advertising_stopped: bool,
    /// Scanning started.
    pub scan_started: bool,
    /// Scanning stopped before receipt emission.
    pub scan_stopped: bool,
    /// GATT server or client opened.
    pub gatt_opened: bool,
    /// GATT server or client closed before receipt emission.
    pub gatt_closed: bool,
    /// A peer connected during the run.
    pub connected: bool,
    /// A connected peer disconnected before receipt emission.
    pub disconnected: bool,
    /// Bounded messages sent.
    pub messages_sent: u32,
    /// Bounded messages received.
    pub messages_received: u32,
    /// Messages accepted after runtime HMAC verification.
    pub authenticated_messages: u32,
    /// Runtime HMAC failures.
    pub authentication_failures: u32,
    /// Completed reconnect cycles. Clients prove the link transition; servers
    /// prove a second fresh authenticated offer/proposal/accept cycle.
    pub reconnects_completed: u32,
    /// An authenticated post-reconnect message was observed.
    pub post_reconnect_message_authenticated: bool,
    /// Raw Bluetooth addresses were excluded from the receipt.
    pub raw_bluetooth_addresses_redacted: bool,
    /// Media payload bytes carried by this sidecar; must remain zero.
    pub media_payload_bytes: u64,
    /// Wi-Fi Direct mutations executed by the sidecar; must remain zero.
    pub wifi_direct_mutations_executed: u32,
    /// Manifold commands executed by the sidecar; must remain zero.
    pub manifold_commands_executed: u32,
    /// All started role resources were cleaned up.
    pub cleanup_complete: bool,
    /// Structured low-rate issue codes.
    #[serde(default)]
    pub issue_codes: Vec<String>,
}

/// One server/client phase inside a two-peer role-swap acceptance artifact.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BleRendezvousPairPhase {
    /// Stable phase label within the pair artifact.
    pub name: String,
    /// Terminal phase result; pair acceptance requires `pass`.
    pub status: BleRendezvousReceiptStatus,
    /// Ephemeral authenticated session shared by both role receipts.
    pub session_tag: String,
    /// Device serial that hosted the GATT server for this phase.
    pub server_serial: String,
    /// Device serial that acted as the GATT client for this phase.
    pub client_serial: String,
    /// Validated terminal receipt emitted by the server role.
    pub server_receipt: BleRendezvousSidecarReceipt,
    /// Validated terminal receipt emitted by the client role.
    pub client_receipt: BleRendezvousSidecarReceipt,
}

/// Bluetooth and P2P boundary state captured before or after the pair run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BleRendezvousPairDeviceState {
    /// Device serial correlated with the pair artifact.
    pub serial: String,
    /// Android global Bluetooth-enabled readback.
    pub bluetooth_on: String,
    /// Raw bounded `p2p0` IPv4 readback; must remain unchanged by BLE.
    pub p2p0_ipv4: String,
}

/// Per-device package cleanup readback.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BleRendezvousPairDeviceCleanup {
    /// Device serial correlated with the pair artifact.
    pub serial: String,
    /// Exit code returned by the package force-stop command.
    pub force_stop_exit_code: i32,
    /// Whether the sidecar package process was absent after cleanup.
    pub package_pid_absent: bool,
}

/// Aggregate cleanup and bounded fatal-window result.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BleRendezvousPairCleanup {
    /// Per-device package cleanup readbacks.
    pub devices: Vec<BleRendezvousPairDeviceCleanup>,
    /// Whether both sidecar package processes were absent.
    pub package_processes_absent: bool,
    /// Whether Bluetooth and `p2p0` state matched the pre-run values.
    pub bluetooth_and_p2p0_state_stable: bool,
    /// App-scoped fatal lines observed in the bounded log window.
    pub app_fatal_count: u32,
    /// Aggregate cleanup result.
    pub complete: bool,
}

/// Two-peer BLE rendezvous role-swap acceptance artifact.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BleRendezvousPairReceipt {
    /// Pair artifact schema identifier.
    pub schema: String,
    /// Ephemeral identifier for this pair run.
    pub run_id: String,
    /// Terminal pair result; acceptance requires `pass`.
    pub status: BleRendezvousReceiptStatus,
    /// Serial of the first declared peer.
    pub primary_serial: String,
    /// Serial of the second declared peer.
    pub secondary_serial: String,
    /// Agent Board Quest lease correlated with the primary peer.
    pub primary_quest_lease_id: String,
    /// Agent Board Quest lease correlated with the secondary peer.
    pub secondary_quest_lease_id: String,
    /// Whether this is a hardware-free planning run.
    pub dry_run: bool,
    /// Whether acceptance required reversing the GATT roles.
    pub role_swap_required: bool,
    /// Whether both server/client layouts completed.
    pub role_swap_completed: bool,
    /// Whether every role phase required an authenticated reconnect.
    pub reconnect_required_each_phase: bool,
    /// Number of role phases with authenticated terminal receipts.
    pub authenticated_phase_count: u32,
    /// Whether the pair artifact recorded the shared secret; must be false.
    pub shared_secret_recorded: bool,
    /// Whether receipts and logs redact raw Bluetooth addresses.
    pub raw_bluetooth_addresses_redacted: bool,
    /// Media bytes carried by BLE; must remain zero.
    pub media_payload_bytes: u64,
    /// Wi-Fi Direct mutations executed by the BLE sidecar; must remain zero.
    pub wifi_direct_mutations_executed: u32,
    /// Manifold commands executed by the BLE sidecar; must remain zero.
    pub manifold_commands_executed: u32,
    /// Two authenticated phases, one for each server/client role layout.
    pub phases: Vec<BleRendezvousPairPhase>,
    /// Per-device Bluetooth and P2P state captured before the run.
    pub device_state_before: Vec<BleRendezvousPairDeviceState>,
    /// Per-device Bluetooth and P2P state captured after the run.
    pub device_state_after: Vec<BleRendezvousPairDeviceState>,
    /// Package cleanup and bounded fatal-window evidence.
    pub cleanup: BleRendezvousPairCleanup,
    /// Whether artifact scanning found a raw Bluetooth address pattern.
    pub raw_bluetooth_address_pattern_found: bool,
    /// Whether artifact scanning found the run's shared secret.
    pub shared_secret_pattern_found: bool,
}

/// Validate a terminal BLE sidecar lifecycle receipt.
pub fn validate_ble_rendezvous_sidecar_receipt(
    receipt: &BleRendezvousSidecarReceipt,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if receipt.schema != BLE_RENDEZVOUS_RECEIPT_SCHEMA {
        errors.push(ValidationError::new(
            "BLE rendezvous receipt schema is unsupported",
        ));
    }
    validate_ephemeral_tag("run_id", &receipt.run_id, &mut errors);
    validate_ephemeral_tag("session_tag", &receipt.session_tag, &mut errors);
    validate_ephemeral_tag("peer_tag", &receipt.peer_tag, &mut errors);
    if !receipt.explicit_opt_in {
        errors.push(ValidationError::new(
            "BLE rendezvous sidecar must be explicit opt-in",
        ));
    }
    if !receipt.raw_bluetooth_addresses_redacted {
        errors.push(ValidationError::new(
            "BLE rendezvous receipt must redact raw Bluetooth addresses",
        ));
    }
    if receipt.media_payload_bytes != 0 {
        errors.push(ValidationError::new(
            "BLE rendezvous sidecar must not carry media payload bytes",
        ));
    }
    if receipt.wifi_direct_mutations_executed != 0 {
        errors.push(ValidationError::new(
            "BLE rendezvous sidecar must not execute Wi-Fi Direct mutations",
        ));
    }
    if receipt.manifold_commands_executed != 0 {
        errors.push(ValidationError::new(
            "BLE rendezvous sidecar must not execute Manifold commands",
        ));
    }
    if !receipt.cleanup_complete {
        errors.push(ValidationError::new(
            "BLE rendezvous terminal receipt requires cleanup_complete",
        ));
    }
    if receipt.advertising_started && !receipt.advertising_stopped {
        errors.push(ValidationError::new(
            "started BLE advertising must be stopped before receipt emission",
        ));
    }
    if receipt.scan_started && !receipt.scan_stopped {
        errors.push(ValidationError::new(
            "started BLE scanning must be stopped before receipt emission",
        ));
    }
    if receipt.gatt_opened && !receipt.gatt_closed {
        errors.push(ValidationError::new(
            "opened GATT resources must be closed before receipt emission",
        ));
    }
    if receipt.connected && !receipt.disconnected {
        errors.push(ValidationError::new(
            "connected BLE peers must be disconnected before receipt emission",
        ));
    }
    if receipt.reconnects_completed > 0 && !receipt.post_reconnect_message_authenticated {
        errors.push(ValidationError::new(
            "completed BLE reconnect requires an authenticated post-reconnect message",
        ));
    }
    for issue_code in &receipt.issue_codes {
        if !is_safe_tag(issue_code, 1, 96) {
            errors.push(ValidationError::new(
                "BLE rendezvous issue codes must be bounded safe tokens",
            ));
        }
    }

    if matches!(
        receipt.status,
        BleRendezvousReceiptStatus::Ready | BleRendezvousReceiptStatus::Pass
    ) {
        if !receipt.adapter_available
            || !receipt.bluetooth_enabled
            || !receipt.permissions_granted
            || !receipt.protocol_self_test_passed
            || !receipt.gatt_opened
        {
            errors.push(ValidationError::new(
                "ready/pass BLE receipt requires adapter, Bluetooth, permissions, protocol self-test, and GATT",
            ));
        }
        match receipt.role {
            BleRendezvousSidecarRole::Server if !receipt.advertising_started => {
                errors.push(ValidationError::new(
                    "ready/pass BLE server receipt requires advertising",
                ));
            }
            BleRendezvousSidecarRole::Client if !receipt.scan_started => {
                errors.push(ValidationError::new(
                    "ready/pass BLE client receipt requires scanning",
                ));
            }
            _ => {}
        }
    }
    if receipt.status == BleRendezvousReceiptStatus::Pass
        && (!receipt.connected
            || receipt.messages_sent == 0
            || receipt.messages_received == 0
            || receipt.authenticated_messages == 0
            || receipt.authentication_failures != 0
            || receipt.reconnects_completed == 0
            || !receipt.post_reconnect_message_authenticated)
    {
        errors.push(ValidationError::new(
            "pass BLE receipt requires a clean authenticated bidirectional exchange and reconnect",
        ));
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

/// Validate a two-peer role-swap acceptance artifact.
pub fn validate_ble_rendezvous_pair_receipt(
    pair: &BleRendezvousPairReceipt,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if pair.schema != BLE_RENDEZVOUS_PAIR_SCHEMA {
        errors.push(ValidationError::new(
            "BLE rendezvous pair schema is unsupported",
        ));
    }
    validate_ephemeral_tag("pair run_id", &pair.run_id, &mut errors);
    if pair.status != BleRendezvousReceiptStatus::Pass || pair.dry_run {
        errors.push(ValidationError::new(
            "BLE rendezvous pair acceptance requires a live pass status",
        ));
    }
    if pair.primary_serial.is_empty()
        || pair.secondary_serial.is_empty()
        || pair.primary_serial == pair.secondary_serial
        || pair.primary_quest_lease_id.is_empty()
        || pair.secondary_quest_lease_id.is_empty()
        || pair.primary_quest_lease_id == pair.secondary_quest_lease_id
    {
        errors.push(ValidationError::new(
            "BLE rendezvous pair requires two distinct devices and lease ids",
        ));
    }
    if !pair.role_swap_required
        || !pair.role_swap_completed
        || !pair.reconnect_required_each_phase
        || pair.authenticated_phase_count != 2
        || pair.phases.len() != 2
    {
        errors.push(ValidationError::new(
            "BLE rendezvous pair requires two authenticated reconnect phases with role swap",
        ));
    }
    if pair.shared_secret_recorded
        || !pair.raw_bluetooth_addresses_redacted
        || pair.raw_bluetooth_address_pattern_found
        || pair.shared_secret_pattern_found
        || pair.media_payload_bytes != 0
        || pair.wifi_direct_mutations_executed != 0
        || pair.manifold_commands_executed != 0
    {
        errors.push(ValidationError::new(
            "BLE rendezvous pair crossed a secret, address, media, Wi-Fi, or Manifold boundary",
        ));
    }

    let mut primary_server_phase = false;
    let mut secondary_server_phase = false;
    for phase in &pair.phases {
        validate_ephemeral_tag("pair session_tag", &phase.session_tag, &mut errors);
        if phase.status != BleRendezvousReceiptStatus::Pass
            || phase.server_serial == phase.client_serial
        {
            errors.push(ValidationError::new(format!(
                "BLE rendezvous phase {} status or device correlation failed",
                phase.name
            )));
        }
        primary_server_phase |= phase.server_serial == pair.primary_serial
            && phase.client_serial == pair.secondary_serial;
        secondary_server_phase |= phase.server_serial == pair.secondary_serial
            && phase.client_serial == pair.primary_serial;

        for (label, receipt, expected_role) in [
            (
                "server",
                &phase.server_receipt,
                BleRendezvousSidecarRole::Server,
            ),
            (
                "client",
                &phase.client_receipt,
                BleRendezvousSidecarRole::Client,
            ),
        ] {
            if let Err(receipt_errors) = validate_ble_rendezvous_sidecar_receipt(receipt) {
                errors.extend(receipt_errors.into_iter().map(|error| {
                    ValidationError::new(format!(
                        "BLE rendezvous phase {} {label}: {}",
                        phase.name, error.message
                    ))
                }));
            }
            if receipt.role != expected_role
                || receipt.status != BleRendezvousReceiptStatus::Pass
                || receipt.session_tag != phase.session_tag
                || !receipt.issue_codes.is_empty()
            {
                errors.push(ValidationError::new(format!(
                    "BLE rendezvous phase {} {label} receipt correlation failed",
                    phase.name
                )));
            }
        }
        if phase.server_receipt.peer_tag == phase.client_receipt.peer_tag {
            errors.push(ValidationError::new(format!(
                "BLE rendezvous phase {} peer tags must be distinct",
                phase.name
            )));
        }
    }
    if !primary_server_phase || !secondary_server_phase {
        errors.push(ValidationError::new(
            "BLE rendezvous pair did not prove both server/client role layouts",
        ));
    }
    if pair.phases.len() == 2 && pair.phases[0].session_tag == pair.phases[1].session_tag {
        errors.push(ValidationError::new(
            "BLE rendezvous pair phases must use distinct authenticated sessions",
        ));
    }

    let expected_serials = [&pair.primary_serial, &pair.secondary_serial];
    let state_rows_correlate = |rows: &[BleRendezvousPairDeviceState]| {
        rows.len() == 2
            && expected_serials
                .iter()
                .all(|serial| rows.iter().filter(|row| &row.serial == *serial).count() == 1)
            && rows
                .iter()
                .all(|row| expected_serials.iter().any(|serial| **serial == row.serial))
    };
    if !state_rows_correlate(&pair.device_state_before)
        || !state_rows_correlate(&pair.device_state_after)
    {
        errors.push(ValidationError::new(
            "BLE rendezvous pair requires one before/after state row for each declared device",
        ));
    } else {
        for before in &pair.device_state_before {
            let after = pair
                .device_state_after
                .iter()
                .find(|candidate| candidate.serial == before.serial);
            let state_changed = match after {
                Some(after) => {
                    after.bluetooth_on != before.bluetooth_on || after.p2p0_ipv4 != before.p2p0_ipv4
                }
                None => true,
            };
            if before.bluetooth_on != "1" || state_changed {
                errors.push(ValidationError::new(format!(
                    "BLE rendezvous boundary state changed for {}",
                    before.serial
                )));
            }
        }
    }
    let cleanup_rows_correlate = pair.cleanup.devices.len() == 2
        && expected_serials.iter().all(|serial| {
            pair.cleanup
                .devices
                .iter()
                .filter(|row| &row.serial == *serial)
                .count()
                == 1
        })
        && pair
            .cleanup
            .devices
            .iter()
            .all(|row| expected_serials.iter().any(|serial| **serial == row.serial));
    if !pair.cleanup.complete
        || !pair.cleanup.package_processes_absent
        || !pair.cleanup.bluetooth_and_p2p0_state_stable
        || pair.cleanup.app_fatal_count != 0
        || !cleanup_rows_correlate
        || pair
            .cleanup
            .devices
            .iter()
            .any(|device| device.force_stop_exit_code != 0 || !device.package_pid_absent)
    {
        errors.push(ValidationError::new(
            "BLE rendezvous pair cleanup or fatal-window acceptance failed",
        ));
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_wifi_hint(message: &BleRendezvousMessage, errors: &mut Vec<ValidationError>) {
    if let Some(address) = message.p2p_ipv4 {
        if !is_supported_direct_p2p_ipv4(address) {
            errors.push(ValidationError::new(
                "BLE rendezvous p2p_ipv4 must be a supported P2P address",
            ));
        }
    }
    if message.broker_port == Some(0) {
        errors.push(ValidationError::new(
            "BLE rendezvous broker_port must be nonzero",
        ));
    }
    match message.wifi_state {
        BleRendezvousWifiState::Idle
        | BleRendezvousWifiState::Discovering
        | BleRendezvousWifiState::Failed => {
            if message.p2p_ipv4.is_some() || message.broker_port.is_some() {
                errors.push(ValidationError::new(
                    "non-grouped BLE rendezvous state must not publish endpoint hints",
                ));
            }
        }
        BleRendezvousWifiState::Grouped => {
            if message.p2p_ipv4.is_none() || message.broker_port.is_some() {
                errors.push(ValidationError::new(
                    "grouped BLE rendezvous state requires only p2p_ipv4",
                ));
            }
        }
        BleRendezvousWifiState::Ready => {
            let required = BLE_CAPABILITY_WIFI_DIRECT
                | BLE_CAPABILITY_RUSTY_DIRECT_P2P
                | BLE_CAPABILITY_MANIFOLD_BROKER;
            if message.p2p_ipv4.is_none()
                || message.broker_port.is_none()
                || message.capabilities & required != required
            {
                errors.push(ValidationError::new(
                    "ready BLE rendezvous state requires P2P address, broker port, and direct-P2P capabilities",
                ));
            }
        }
    }
}

fn validate_ephemeral_tag(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if !is_safe_tag(value, 4, 32) {
        errors.push(ValidationError::new(format!(
            "BLE rendezvous {label} must be a 4..=32 character ephemeral safe token"
        )));
    }
}

fn is_safe_tag(value: &str, min: usize, max: usize) -> bool {
    (min..=max).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn is_hex_token(value: &str, min: usize, max: usize) -> bool {
    (min..=max).contains(&value.len()) && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn message_fixture() -> BleRendezvousMessage {
        serde_json::from_str(include_str!(
            "../../../fixtures/device-link/ble-rendezvous-offer.pass.json"
        ))
        .expect("BLE rendezvous message fixture parses")
    }

    fn receipt_fixture() -> BleRendezvousSidecarReceipt {
        serde_json::from_str(include_str!(
            "../../../fixtures/device-link/ble-rendezvous-server-ready.receipt.json"
        ))
        .expect("BLE rendezvous receipt fixture parses")
    }

    fn pair_fixture() -> BleRendezvousPairReceipt {
        serde_json::from_str(include_str!(
            "../../../fixtures/device-link/ble-rendezvous-pair.pass.json"
        ))
        .expect("BLE rendezvous pair fixture parses")
    }

    #[test]
    fn compact_offer_fixture_validates() {
        let message = message_fixture();
        validate_ble_rendezvous_message(&message).expect("message validates");
        assert!(serde_json::to_vec(&message).unwrap().len() <= BLE_RENDEZVOUS_MAX_WIRE_BYTES);
        assert_eq!(
            ble_rendezvous_signing_input(&message),
            "RQRV1|offer|session-7f31|peer-w-19ac|4|1|group_owner|15|ready|192.168.49.1|8765|30000|0011223344556677"
        );
    }

    #[test]
    fn server_readiness_fixture_validates_without_peer_claim() {
        let receipt = receipt_fixture();
        validate_ble_rendezvous_sidecar_receipt(&receipt).expect("receipt validates");
        assert_eq!(receipt.status, BleRendezvousReceiptStatus::Ready);
        assert!(!receipt.connected);
    }

    #[test]
    fn message_with_invalid_auth_tag_is_rejected() {
        let mut message = message_fixture();
        message.auth_tag = "not-authenticated".to_string();
        let errors = validate_ble_rendezvous_message(&message)
            .expect_err("invalid auth tag must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("auth_tag")));
    }

    #[test]
    fn receipt_with_media_payload_is_rejected() {
        let mut receipt = receipt_fixture();
        receipt.media_payload_bytes = 1;
        let errors = validate_ble_rendezvous_sidecar_receipt(&receipt)
            .expect_err("media over BLE rendezvous must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("media payload")));
    }

    #[test]
    fn pass_receipt_without_peer_exchange_is_rejected() {
        let mut receipt = receipt_fixture();
        receipt.status = BleRendezvousReceiptStatus::Pass;
        let errors = validate_ble_rendezvous_sidecar_receipt(&receipt)
            .expect_err("pass without peer exchange must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("bidirectional exchange")));
    }

    #[test]
    fn pass_receipt_without_authenticated_reconnect_is_rejected() {
        let mut receipt = receipt_fixture();
        receipt.status = BleRendezvousReceiptStatus::Pass;
        receipt.connected = true;
        receipt.disconnected = true;
        receipt.messages_sent = 1;
        receipt.messages_received = 2;
        receipt.authenticated_messages = 2;
        let errors = validate_ble_rendezvous_sidecar_receipt(&receipt)
            .expect_err("pass without reconnect must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("reconnect")));
    }

    #[test]
    fn pair_role_swap_fixture_validates() {
        validate_ble_rendezvous_pair_receipt(&pair_fixture()).expect("pair fixture validates");
    }

    #[test]
    fn pair_without_role_swap_is_rejected() {
        let mut pair = pair_fixture();
        pair.role_swap_completed = false;
        let errors = validate_ble_rendezvous_pair_receipt(&pair)
            .expect_err("missing role swap must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("role swap")));
    }

    #[test]
    fn pair_with_duplicated_device_evidence_is_rejected() {
        let mut pair = pair_fixture();
        pair.device_state_after[1] = pair.device_state_after[0].clone();
        pair.cleanup.devices[1] = pair.cleanup.devices[0].clone();
        let errors = validate_ble_rendezvous_pair_receipt(&pair)
            .expect_err("duplicate device evidence must be rejected");
        assert!(errors.iter().any(|error| {
            error.message.contains("each declared device")
                || error.message.contains("cleanup or fatal-window")
        }));
    }

    #[test]
    fn pair_with_replayed_session_is_rejected() {
        let mut pair = pair_fixture();
        let replayed_session = pair.phases[0].session_tag.clone();
        pair.phases[1].session_tag = replayed_session.clone();
        pair.phases[1].server_receipt.session_tag = replayed_session.clone();
        pair.phases[1].client_receipt.session_tag = replayed_session;
        let errors = validate_ble_rendezvous_pair_receipt(&pair)
            .expect_err("replayed pair session must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("distinct authenticated sessions")));
    }
}
