package io.github.mesmerprism.rustyquest.qcl041;

import android.content.Intent;

final class Qcl041ProbeConfig {
    static final String PACKAGE_NAME = "io.github.mesmerprism.rustyquest.qcl041";
    static final String ACTIVITY_NAME =
            "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessActivity";
    static final String SERVICE_NAME =
            "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessService";
    static final String SCHEMA = "rusty.quest.connectivity_wifi_direct_lifecycle.v1";
    static final String HARNESS_ID = "rusty-quest-wifi-direct-qcl041-android-harness";
    static final String HARNESS_OWNER = "Rusty Quest QCL-041 Wi-Fi Direct Android harness";
    static final String ROUTE = "quest_windows_wifi_direct_lifecycle";
    static final String ROUTE_Q2Q = "quest_quest_wifi_direct_lifecycle";
    static final String ROUTE_QCL030_LOCAL_ONLY_HOTSPOT = "quest_local_only_hotspot_probe";
    static final String PEER_CLASS_WINDOWS = "windows";
    static final String PEER_CLASS_QUEST = "quest";
    static final String Q2Q_ROLE_CLIENT = "client";
    static final String Q2Q_ROLE_GROUP_OWNER = "group_owner";
    static final String WINDOWS_PEER_CONNECT_MODE_TEMPORARY = "temporary";
    static final String WINDOWS_PEER_CONNECT_MODE_LEGACY_DEFAULT = "legacy_default";
    static final String QCL030_ROLE_HOTSPOT_OWNER = "hotspot_owner";
    static final String QCL030_ROLE_HOTSPOT_CLIENT = "hotspot_client";
    static final String QCL030_CLIENT_JOIN_MODE_NETWORK_SPECIFIER = "network_specifier";
    static final String QCL030_CLIENT_JOIN_MODE_ACTIVE_WIFI = "active_wifi";
    static final String DEFAULT_Q2Q_NETWORK_NAME = "DIRECT-rq-QCL100";
    static final String DEFAULT_Q2Q_PASSPHRASE = "RustyQcl100Pass";

    final String runId;
    final String deviceSerial;
    final String deviceModel;
    final String leaseId;
    final String leaseResource;
    final String reserveCommand;
    final String releaseCommand;
    final boolean leaseReservedBeforeLiveSteps;
    final boolean leaseReleasedAfterLiveSteps;
    final boolean windowsApiObserved;
    final String windowsApiEvidence;
    final String peerClass;
    final String peerNameContains;
    final String q2qRole;
    final String windowsPeerNameContains;
    final boolean windowsPeerPreclearPersistentGroups;
    final String windowsPeerConnectMode;
    final String p2pDeviceNameOverride;
    final int groupOwnerIntent;
    final boolean allowQuestGroupOwner;
    final boolean q2qPreclearStaleGroup;
    final boolean q2qPreclearOnly;
    final String q2qNetworkName;
    final String q2qPassphrase;
    final int listenPort;
    final int timeoutSeconds;
    final int socketTimeoutSeconds;
    final int holdAfterSocketMs;
    final String socketPayload;
    final boolean q2qAppBoundSocketMatrixEnabled;
    final int q2qAppBoundSocketMatrixPort;
    final int q2qAppBoundSocketMatrixDelayedUdpDelayMs;
    final int q2qAppBoundSocketMatrixTcpTunnelStreamSeconds;
    final int q2qAppBoundSocketMatrixTcpTunnelStreamBytesPerDirection;
    final boolean qcl030LocalOnlyHotspotEnabled;
    final String qcl030LocalOnlyHotspotRole;
    final int qcl030LocalOnlyHotspotHoldMs;
    final int qcl030LocalOnlyHotspotPort;
    final String qcl030LocalOnlyHotspotSsid;
    final String qcl030LocalOnlyHotspotPassphrase;
    final String qcl030LocalOnlyHotspotOwnerHost;
    final int qcl030LocalOnlyHotspotSocketBytes;
    final int qcl030LocalOnlyHotspotSocketTimeoutMs;
    final String qcl030LocalOnlyHotspotClientJoinMode;
    final boolean qcl030LocalOnlyHotspotRequireSsidMatch;
    final boolean qcl082RelayEnabled;
    final String qcl082RelaySourceHost;
    final int qcl082RelaySourcePort;
    final String qcl082RelayReceiverHost;
    final int qcl082RelayReceiverPort;
    final int qcl082RelayTimeoutSeconds;
    final int qcl082RelayMaxBytes;
    final int qcl082RelayStartDelayMs;
    final int qcl082RelayWriteStallTimeoutMs;
    final int qcl082RelayReceiverProgressTimeoutMs;
    final int qcl082RelayPortRotationCount;
    final String qcl082TopologyEpoch;
    final String qcl082RelayReceiverTargetFile;
    final int qcl082RelayReceiverTargetWaitMs;
    final boolean qcl082RelayRequireDeferredReceiverTarget;
    final String qcl082TransportProtocol;
    final String qcl082RelayTransportProtocol;
    final String qcl082RelayLanes;
    final boolean qcl082AckPacingEnabled;
    final int qcl082AckChunkBytes;
    final int qcl082AckTimeoutMs;
    final int qcl082AckSoftTimeoutLimit;
    final int qcl082ControlTcpMediaStreamBytesPerDirection;
    final int qcl082ControlTcpMediaStreamChunkBytes;
    final boolean qcl082ReceiveProxyEnabled;
    final int qcl082ReceiveProxyListenPort;
    final String qcl082ReceiveProxyTargetHost;
    final int qcl082ReceiveProxyTargetPort;
    final int qcl082ReceiveProxyTimeoutSeconds;
    final int qcl082ReceiveProxyMaxBytes;
    final int qcl082ReceiveProxyPeerIdleTimeoutMs;
    final String qcl082ReceiveProxyTransportProtocol;
    final String qcl082ReceiveProxyLanes;
    final boolean qcl082UdpReceiveProxyRequireWifiDirectNetworkBinding;
    final boolean qcl081LslEnabled;
    final String qcl081LslBackend;
    final String qcl081LslStreamName;
    final String qcl081LslStreamType;
    final String qcl081LslSourceId;
    final int qcl081LslSampleCount;
    final int qcl081LslWarmupMs;
    final int qcl081LslIntervalMs;
    final boolean qcl081LslEchoEnabled;
    final String qcl081LslEchoCommandStreamName;
    final String qcl081LslEchoCommandStreamType;
    final String qcl081LslEchoCommandSourceId;
    final String qcl081LslEchoStreamName;
    final String qcl081LslEchoStreamType;
    final String qcl081LslEchoSourceId;
    final int qcl081LslEchoSampleCount;
    final int qcl081LslEchoWarmupMs;
    final int qcl081LslEchoOutletHoldAfterMs;
    final int qcl081LslEchoTimeoutSeconds;
    final String evidenceTier;
    final String hostToolchainProfile;

    private Qcl041ProbeConfig(Intent intent) {
        String defaultRunId = "qcl041-quest-windows-" + System.currentTimeMillis();
        this.runId = stringExtra(intent, "qcl041.run_id", defaultRunId);
        this.deviceSerial = stringExtra(intent, "qcl041.device_serial", "QUEST_SERIAL_FROM_INTENT");
        this.deviceModel = stringExtra(intent, "qcl041.device_model", "Quest");
        this.leaseId = stringExtra(intent, "qcl041.lease_id", "LEASE_ID_FROM_RESERVE_OUTPUT");
        this.leaseResource = stringExtra(intent, "qcl041.lease_resource", "quest:" + this.deviceSerial);
        this.reserveCommand = stringExtra(intent, "qcl041.reserve_command", "");
        this.releaseCommand = stringExtra(intent, "qcl041.release_command", "");
        this.leaseReservedBeforeLiveSteps =
                booleanExtra(intent, "qcl041.lease_reserved_before_live_steps", false);
        this.leaseReleasedAfterLiveSteps =
                booleanExtra(intent, "qcl041.lease_released_after_live_steps", false);
        this.windowsApiObserved = booleanExtra(intent, "qcl041.windows_api_observed", false);
        this.windowsApiEvidence = stringExtra(
                intent,
                "qcl041.windows_api_evidence",
                "Windows Wi-Fi Direct helper evidence is finalized by the host wrapper.");
        this.peerClass = normalizePeerClass(stringExtra(
                intent,
                "qcl041.peer_class",
                PEER_CLASS_WINDOWS));
        this.peerNameContains = stringExtra(intent, "qcl041.peer_name_contains", "");
        this.q2qRole = normalizeQ2qRole(stringExtra(intent, "qcl041.q2q_role", Q2Q_ROLE_CLIENT));
        this.windowsPeerNameContains = stringExtra(intent, "qcl041.windows_peer_name_contains", "");
        this.windowsPeerPreclearPersistentGroups =
                booleanExtra(intent, "qcl041.windows_peer_preclear_persistent_groups", false);
        this.windowsPeerConnectMode = normalizeWindowsPeerConnectMode(stringExtra(
                intent,
                "qcl041.windows_peer_connect_mode",
                WINDOWS_PEER_CONNECT_MODE_TEMPORARY));
        this.p2pDeviceNameOverride =
                stringExtra(intent, "qcl041.p2p_device_name_override", "");
        this.groupOwnerIntent = intExtra(intent, "qcl041.group_owner_intent", 0);
        this.allowQuestGroupOwner = booleanExtra(intent, "qcl041.allow_quest_group_owner", false);
        this.q2qPreclearStaleGroup =
                booleanExtra(intent, "qcl041.q2q_preclear_stale_group", false);
        this.q2qPreclearOnly = booleanExtra(intent, "qcl041.q2q_preclear_only", false);
        this.q2qNetworkName = stringExtra(
                intent,
                "qcl041.q2q_network_name",
                DEFAULT_Q2Q_NETWORK_NAME);
        this.q2qPassphrase = stringExtra(
                intent,
                "qcl041.q2q_passphrase",
                DEFAULT_Q2Q_PASSPHRASE);
        this.listenPort = intExtra(intent, "qcl041.listen_port", 18768);
        this.timeoutSeconds = intExtra(intent, "qcl041.timeout_seconds", 45);
        this.socketTimeoutSeconds = intExtra(intent, "qcl041.socket_timeout_seconds", 20);
        this.holdAfterSocketMs = intExtra(intent, "qcl041.hold_after_socket_ms", 0);
        this.socketPayload = stringExtra(
                intent,
                "qcl041.socket_payload",
                "RMANVID1;qcl=QCL-041;run_id=" + this.runId);
        this.q2qAppBoundSocketMatrixEnabled =
                booleanExtra(intent, "qcl041.q2q_app_bound_socket_matrix_enabled", false);
        this.q2qAppBoundSocketMatrixPort =
                intExtra(intent, "qcl041.q2q_app_bound_socket_matrix_port", this.listenPort + 100);
        this.q2qAppBoundSocketMatrixDelayedUdpDelayMs =
                intExtra(intent, "qcl041.q2q_app_bound_socket_matrix_delayed_udp_delay_ms", 0);
        this.q2qAppBoundSocketMatrixTcpTunnelStreamSeconds =
                intExtra(intent, "qcl041.q2q_app_bound_socket_matrix_tcp_tunnel_stream_seconds", 0);
        this.q2qAppBoundSocketMatrixTcpTunnelStreamBytesPerDirection =
                intExtra(
                        intent,
                        "qcl041.q2q_app_bound_socket_matrix_tcp_tunnel_stream_bytes_per_direction",
                        0);
        this.qcl030LocalOnlyHotspotEnabled =
                booleanExtra(intent, "qcl041.qcl030_local_only_hotspot_enabled", false);
        this.qcl030LocalOnlyHotspotRole = stringExtra(
                intent,
                "qcl041.qcl030_local_only_hotspot_role",
                QCL030_ROLE_HOTSPOT_OWNER).toLowerCase();
        this.qcl030LocalOnlyHotspotHoldMs =
                intExtra(intent, "qcl041.qcl030_local_only_hotspot_hold_ms", 60_000);
        this.qcl030LocalOnlyHotspotPort =
                intExtra(intent, "qcl041.qcl030_local_only_hotspot_port", this.listenPort + 300);
        this.qcl030LocalOnlyHotspotSsid =
                stringExtra(intent, "qcl041.qcl030_local_only_hotspot_ssid", "");
        this.qcl030LocalOnlyHotspotPassphrase =
                stringExtra(intent, "qcl041.qcl030_local_only_hotspot_passphrase", "");
        this.qcl030LocalOnlyHotspotOwnerHost =
                stringExtra(intent, "qcl041.qcl030_local_only_hotspot_owner_host", "192.168.43.1");
        this.qcl030LocalOnlyHotspotSocketBytes =
                intExtra(intent, "qcl041.qcl030_local_only_hotspot_socket_bytes", 65_536);
        this.qcl030LocalOnlyHotspotSocketTimeoutMs =
                intExtra(intent, "qcl041.qcl030_local_only_hotspot_socket_timeout_ms", 15_000);
        this.qcl030LocalOnlyHotspotClientJoinMode = stringExtra(
                intent,
                "qcl041.qcl030_local_only_hotspot_client_join_mode",
                QCL030_CLIENT_JOIN_MODE_NETWORK_SPECIFIER).toLowerCase();
        this.qcl030LocalOnlyHotspotRequireSsidMatch =
                booleanExtra(intent, "qcl041.qcl030_local_only_hotspot_require_ssid_match", false);
        this.qcl082RelayEnabled = booleanExtra(intent, "qcl041.qcl082_relay_enabled", false);
        this.qcl082RelaySourceHost = stringExtra(intent, "qcl041.qcl082_relay_source_host", "127.0.0.1");
        this.qcl082RelaySourcePort = intExtra(intent, "qcl041.qcl082_relay_source_port", 8879);
        this.qcl082RelayReceiverHost = stringExtra(intent, "qcl041.qcl082_relay_receiver_host", "192.168.137.1");
        this.qcl082RelayReceiverPort = intExtra(intent, "qcl041.qcl082_relay_receiver_port", 9079);
        this.qcl082RelayTimeoutSeconds = intExtra(intent, "qcl041.qcl082_relay_timeout_seconds", 90);
        this.qcl082RelayMaxBytes = intExtra(intent, "qcl041.qcl082_relay_max_bytes", 8 * 1024 * 1024);
        this.qcl082RelayStartDelayMs = intExtra(intent, "qcl041.qcl082_relay_start_delay_ms", 0);
        this.qcl082RelayWriteStallTimeoutMs =
                intExtra(intent, "qcl041.qcl082_relay_write_stall_timeout_ms", 3000);
        this.qcl082RelayReceiverProgressTimeoutMs =
                intExtra(intent, "qcl041.qcl082_relay_receiver_progress_timeout_ms", 0);
        this.qcl082RelayPortRotationCount =
                intExtra(intent, "qcl041.qcl082_relay_port_rotation_count", 1);
        this.qcl082TopologyEpoch =
                stringExtra(intent, "qcl041.qcl082_topology_epoch", this.runId);
        this.qcl082RelayReceiverTargetFile =
                stringExtra(intent, "qcl041.qcl082_relay_receiver_target_file", "");
        this.qcl082RelayReceiverTargetWaitMs =
                intExtra(intent, "qcl041.qcl082_relay_receiver_target_wait_ms", 0);
        this.qcl082RelayRequireDeferredReceiverTarget =
                booleanExtra(intent, "qcl041.qcl082_relay_require_deferred_receiver_target", false);
        this.qcl082TransportProtocol =
                stringExtra(intent, "qcl041.qcl082_transport_protocol", "tcp").toLowerCase();
        this.qcl082RelayTransportProtocol =
                stringExtra(
                        intent,
                        "qcl041.qcl082_relay_transport_protocol",
                        this.qcl082TransportProtocol).toLowerCase();
        this.qcl082RelayLanes = stringExtra(intent, "qcl041.qcl082_relay_lanes", "");
        this.qcl082AckPacingEnabled = booleanExtra(intent, "qcl041.qcl082_ack_pacing_enabled", false);
        this.qcl082AckChunkBytes = intExtra(intent, "qcl041.qcl082_ack_chunk_bytes", 8192);
        this.qcl082AckTimeoutMs = intExtra(intent, "qcl041.qcl082_ack_timeout_ms", 1000);
        this.qcl082AckSoftTimeoutLimit = intExtra(intent, "qcl041.qcl082_ack_soft_timeout_limit", 2);
        this.qcl082ControlTcpMediaStreamBytesPerDirection =
                intExtra(intent, "qcl041.qcl082_control_tcp_media_stream_bytes_per_direction", 0);
        this.qcl082ControlTcpMediaStreamChunkBytes =
                intExtra(intent, "qcl041.qcl082_control_tcp_media_stream_chunk_bytes", 16 * 1024);
        this.qcl082ReceiveProxyEnabled =
                booleanExtra(intent, "qcl041.qcl082_receive_proxy_enabled", false);
        this.qcl082ReceiveProxyListenPort =
                intExtra(intent, "qcl041.qcl082_receive_proxy_listen_port", 9079);
        this.qcl082ReceiveProxyTargetHost =
                stringExtra(intent, "qcl041.qcl082_receive_proxy_target_host", "127.0.0.1");
        this.qcl082ReceiveProxyTargetPort =
                intExtra(intent, "qcl041.qcl082_receive_proxy_target_port", 9179);
        this.qcl082ReceiveProxyTimeoutSeconds =
                intExtra(intent, "qcl041.qcl082_receive_proxy_timeout_seconds", 90);
        this.qcl082ReceiveProxyMaxBytes =
                intExtra(intent, "qcl041.qcl082_receive_proxy_max_bytes", 8 * 1024 * 1024);
        this.qcl082ReceiveProxyPeerIdleTimeoutMs =
                intExtra(intent, "qcl041.qcl082_receive_proxy_peer_idle_timeout_ms", 0);
        this.qcl082ReceiveProxyTransportProtocol =
                stringExtra(
                        intent,
                        "qcl041.qcl082_receive_proxy_transport_protocol",
                        this.qcl082TransportProtocol).toLowerCase();
        this.qcl082ReceiveProxyLanes =
                stringExtra(intent, "qcl041.qcl082_receive_proxy_lanes", "");
        this.qcl082UdpReceiveProxyRequireWifiDirectNetworkBinding = booleanExtra(
                intent,
                "qcl041.qcl082_udp_receive_proxy_require_wifi_direct_network_binding",
                false);
        this.qcl081LslEnabled = booleanExtra(intent, "qcl041.qcl081_lsl_enabled", false);
        this.qcl081LslBackend = "liblsl";
        this.qcl081LslStreamName = stringExtra(
                intent,
                "qcl041.qcl081_lsl_stream_name",
                "RustyQCL081WifiDirect");
        this.qcl081LslStreamType = stringExtra(
                intent,
                "qcl041.qcl081_lsl_stream_type",
                "rusty.quest.qcl081.wifi_direct");
        this.qcl081LslSourceId = stringExtra(
                intent,
                "qcl041.qcl081_lsl_source_id",
                "rusty-quest-qcl081-wifi-direct-" + this.runId);
        this.qcl081LslSampleCount = intExtra(intent, "qcl041.qcl081_lsl_sample_count", 16);
        this.qcl081LslWarmupMs = intExtra(intent, "qcl041.qcl081_lsl_warmup_ms", 1200);
        this.qcl081LslIntervalMs = intExtra(intent, "qcl041.qcl081_lsl_interval_ms", 10);
        this.qcl081LslEchoEnabled = booleanExtra(intent, "qcl041.qcl081_lsl_echo_enabled", false);
        this.qcl081LslEchoCommandStreamName = stringExtra(
                intent,
                "qcl041.qcl081_lsl_echo_command_stream_name",
                "RustyQCL081WifiDirectCommand");
        this.qcl081LslEchoCommandStreamType = stringExtra(
                intent,
                "qcl041.qcl081_lsl_echo_command_stream_type",
                "rusty.quest.qcl081.wifi_direct.command");
        this.qcl081LslEchoCommandSourceId = stringExtra(
                intent,
                "qcl041.qcl081_lsl_echo_command_source_id",
                "rusty-host-qcl081-wifi-direct-command-" + this.runId);
        this.qcl081LslEchoStreamName = stringExtra(
                intent,
                "qcl041.qcl081_lsl_echo_stream_name",
                "RustyQCL081WifiDirectEcho");
        this.qcl081LslEchoStreamType = stringExtra(
                intent,
                "qcl041.qcl081_lsl_echo_stream_type",
                "rusty.quest.qcl081.wifi_direct.echo");
        this.qcl081LslEchoSourceId = stringExtra(
                intent,
                "qcl041.qcl081_lsl_echo_source_id",
                "rusty-quest-qcl081-wifi-direct-echo-" + this.runId);
        this.qcl081LslEchoSampleCount = intExtra(intent, "qcl041.qcl081_lsl_echo_sample_count", 300);
        this.qcl081LslEchoWarmupMs = intExtra(intent, "qcl041.qcl081_lsl_echo_warmup_ms", 250);
        this.qcl081LslEchoOutletHoldAfterMs =
                intExtra(intent, "qcl041.qcl081_lsl_echo_outlet_hold_after_ms", 5000);
        this.qcl081LslEchoTimeoutSeconds = intExtra(intent, "qcl041.qcl081_lsl_echo_timeout_seconds", 45);
        this.evidenceTier = stringExtra(intent, "qcl041.evidence_tier", "product_harness");
        this.hostToolchainProfile = stringExtra(
                intent,
                "qcl041.host_toolchain_profile",
                "rusty_quest_qcl041_wifi_direct_windows_helper");
    }

    static Qcl041ProbeConfig from(Intent intent) {
        return new Qcl041ProbeConfig(intent == null ? new Intent() : intent);
    }

    String artifactFileName() {
        return runId.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }

    boolean isQuestPeerRoute() {
        return PEER_CLASS_QUEST.equals(peerClass);
    }

    boolean isQuestGroupOwnerRole() {
        return Q2Q_ROLE_GROUP_OWNER.equals(q2qRole);
    }

    boolean useLegacyWindowsPeerConnectConfig() {
        return !isQuestPeerRoute()
                && WINDOWS_PEER_CONNECT_MODE_LEGACY_DEFAULT.equals(windowsPeerConnectMode);
    }

    boolean hasP2pDeviceNameOverride() {
        return !p2pDeviceNameOverride.trim().isEmpty();
    }

    boolean isQcl030LocalOnlyHotspotRoute() {
        return qcl030LocalOnlyHotspotEnabled;
    }

    String routeId() {
        if (isQcl030LocalOnlyHotspotRoute()) {
            return ROUTE_QCL030_LOCAL_ONLY_HOTSPOT;
        }
        return isQuestPeerRoute() ? ROUTE_Q2Q : ROUTE;
    }

    String effectivePeerNameContains() {
        if (!peerNameContains.isEmpty()) {
            return peerNameContains;
        }
        return windowsPeerNameContains;
    }

    private static String normalizePeerClass(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return PEER_CLASS_QUEST.equals(normalized) ? PEER_CLASS_QUEST : PEER_CLASS_WINDOWS;
    }

    private static String normalizeQ2qRole(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (Q2Q_ROLE_GROUP_OWNER.equals(normalized)
                || "owner".equals(normalized)
                || "go".equals(normalized)) {
            return Q2Q_ROLE_GROUP_OWNER;
        }
        return Q2Q_ROLE_CLIENT;
    }

    private static String normalizeWindowsPeerConnectMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (WINDOWS_PEER_CONNECT_MODE_LEGACY_DEFAULT.equals(normalized)
                || "legacy".equals(normalized)
                || "default".equals(normalized)
                || "platform_default".equals(normalized)) {
            return WINDOWS_PEER_CONNECT_MODE_LEGACY_DEFAULT;
        }
        return WINDOWS_PEER_CONNECT_MODE_TEMPORARY;
    }

    private static String stringExtra(Intent intent, String key, String fallback) {
        String value = intent.getStringExtra(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int intExtra(Intent intent, String key, int fallback) {
        return intent.hasExtra(key) ? intent.getIntExtra(key, fallback) : fallback;
    }

    private static boolean booleanExtra(Intent intent, String key, boolean fallback) {
        return intent.hasExtra(key) ? intent.getBooleanExtra(key, fallback) : fallback;
    }
}
