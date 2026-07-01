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
    final String windowsPeerNameContains;
    final int groupOwnerIntent;
    final boolean allowQuestGroupOwner;
    final int listenPort;
    final int timeoutSeconds;
    final int socketTimeoutSeconds;
    final int holdAfterSocketMs;
    final String socketPayload;
    final boolean qcl082RelayEnabled;
    final String qcl082RelaySourceHost;
    final int qcl082RelaySourcePort;
    final String qcl082RelayReceiverHost;
    final int qcl082RelayReceiverPort;
    final int qcl082RelayTimeoutSeconds;
    final int qcl082RelayMaxBytes;
    final int qcl082RelayStartDelayMs;
    final boolean qcl081LslEnabled;
    final String qcl081LslBackend;
    final String qcl081LslStreamName;
    final String qcl081LslStreamType;
    final String qcl081LslSourceId;
    final int qcl081LslSampleCount;
    final int qcl081LslWarmupMs;
    final int qcl081LslIntervalMs;
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
        this.windowsPeerNameContains = stringExtra(intent, "qcl041.windows_peer_name_contains", "");
        this.groupOwnerIntent = intExtra(intent, "qcl041.group_owner_intent", 0);
        this.allowQuestGroupOwner = booleanExtra(intent, "qcl041.allow_quest_group_owner", false);
        this.listenPort = intExtra(intent, "qcl041.listen_port", 18768);
        this.timeoutSeconds = intExtra(intent, "qcl041.timeout_seconds", 45);
        this.socketTimeoutSeconds = intExtra(intent, "qcl041.socket_timeout_seconds", 20);
        this.holdAfterSocketMs = intExtra(intent, "qcl041.hold_after_socket_ms", 0);
        this.socketPayload = stringExtra(
                intent,
                "qcl041.socket_payload",
                "RMANVID1;qcl=QCL-041;run_id=" + this.runId);
        this.qcl082RelayEnabled = booleanExtra(intent, "qcl041.qcl082_relay_enabled", false);
        this.qcl082RelaySourceHost = stringExtra(intent, "qcl041.qcl082_relay_source_host", "127.0.0.1");
        this.qcl082RelaySourcePort = intExtra(intent, "qcl041.qcl082_relay_source_port", 8879);
        this.qcl082RelayReceiverHost = stringExtra(intent, "qcl041.qcl082_relay_receiver_host", "192.168.137.1");
        this.qcl082RelayReceiverPort = intExtra(intent, "qcl041.qcl082_relay_receiver_port", 9079);
        this.qcl082RelayTimeoutSeconds = intExtra(intent, "qcl041.qcl082_relay_timeout_seconds", 90);
        this.qcl082RelayMaxBytes = intExtra(intent, "qcl041.qcl082_relay_max_bytes", 8 * 1024 * 1024);
        this.qcl082RelayStartDelayMs = intExtra(intent, "qcl041.qcl082_relay_start_delay_ms", 0);
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
