package io.github.mesmerprism.rustyquest.qcl041;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class Qcl041LifecycleArtifact {
    private final Context context;
    private final Qcl041ProbeConfig config;
    private final JSONObject diagnostics = new JSONObject();

    private String featureStatus = "blocked";
    private String featureEvidence = "Quest Wi-Fi Direct feature has not been observed.";
    private String windowsApiStatus = "blocked";
    private String windowsApiEvidence = "Windows Wi-Fi Direct helper evidence has not been finalized.";
    private String permissionStatus = "blocked";
    private String permissionEvidence = "Wi-Fi Direct runtime permissions have not been accepted.";
    private String peerDiscoveryStatus = "blocked";
    private String peerDiscoveryEvidence = "No Windows Wi-Fi Direct peer has been discovered.";
    private int peerCount = 0;
    private String groupFormationStatus = "blocked";
    private String groupFormationEvidence = "Wi-Fi Direct group formation has not completed.";
    private String localRole = "group_owner_or_client";
    private String peerRole = "group_owner_or_client";
    private String socketStatus = "blocked";
    private String socketEvidence = "Bounded TCP probe has not completed.";
    private int messagesSent = 0;
    private int messagesReceived = 0;
    private Long tcpConnectMs = null;
    private Long groupFormationMs = null;
    private String cleanupStatus = "blocked";
    private String cleanupEvidence = "Wi-Fi Direct cleanup has not completed.";
    private boolean cleanupCompleted = false;

    Qcl041LifecycleArtifact(Context context, Qcl041ProbeConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
        setWindowsApiObserved(config.windowsApiObserved, config.windowsApiEvidence);
    }

    synchronized void setFeature(boolean packageFeaturePresent, Boolean wifiManagerP2pSupported) {
        boolean supported = packageFeaturePresent && Boolean.TRUE.equals(wifiManagerP2pSupported);
        featureStatus = supported ? "pass" : "blocked";
        featureEvidence = "PackageManager.FEATURE_WIFI_DIRECT=" + packageFeaturePresent
                + "; WifiManager.isP2pSupported=" + wifiManagerP2pSupported;
        diagnostic("preflight", "package_feature_wifi_direct", packageFeaturePresent);
        diagnostic("preflight", "wifi_manager_is_p2p_supported", wifiManagerP2pSupported);
        writeQuietly();
    }

    synchronized void setWindowsApiObserved(boolean observed, String evidence) {
        windowsApiStatus = observed ? "pass" : "blocked";
        windowsApiEvidence = evidence == null || evidence.trim().isEmpty()
                ? "Windows Wi-Fi Direct helper evidence is missing."
                : evidence;
        diagnostic("windows_peer", "windows_wifi_direct_api_observed", observed);
        writeQuietly();
    }

    synchronized void setPermissionState(boolean granted, boolean locationModeEnabled, String evidence) {
        boolean passed = granted && locationModeEnabled;
        permissionStatus = passed ? "pass" : "blocked";
        permissionEvidence = evidence;
        diagnostic("permissions", "runtime_permissions_granted", granted);
        diagnostic("permissions", "location_mode_enabled_for_peer_discovery", locationModeEnabled);
        writeQuietly();
    }

    synchronized void setPeerDiscovery(int count, String evidence) {
        peerCount = Math.max(0, count);
        peerDiscoveryStatus = peerCount > 0 ? "pass" : "blocked";
        peerDiscoveryEvidence = evidence;
        diagnostic("lifecycle", "peer_count", peerCount);
        writeQuietly();
    }

    synchronized void setGroupFormation(
            boolean passed,
            boolean localGroupOwner,
            String groupOwnerAddress,
            long elapsedMs,
            String evidence) {
        groupFormationStatus = passed ? "pass" : "blocked";
        groupFormationEvidence = evidence;
        localRole = localGroupOwner ? "group_owner" : "client";
        peerRole = localGroupOwner ? "client" : "group_owner";
        groupFormationMs = elapsedMs >= 0 ? elapsedMs : null;
        diagnostic("lifecycle", "group_owner_address_present", groupOwnerAddress != null);
        diagnostic("lifecycle", "quest_is_group_owner", localGroupOwner);
        writeQuietly();
    }

    synchronized void setSocketExchange(
            boolean passed,
            int sent,
            int received,
            Long connectMs,
            String evidence) {
        socketStatus = passed ? "pass" : "blocked";
        socketEvidence = evidence;
        messagesSent = Math.max(0, sent);
        messagesReceived = Math.max(0, received);
        tcpConnectMs = connectMs;
        diagnostic("lifecycle", "socket_messages_sent", messagesSent);
        diagnostic("lifecycle", "socket_messages_received", messagesReceived);
        writeQuietly();
    }

    synchronized void setCleanup(boolean passed, boolean completed, String evidence) {
        cleanupStatus = passed && completed ? "pass" : "blocked";
        cleanupCompleted = completed;
        cleanupEvidence = evidence;
        diagnostic("cleanup", "completed", completed);
        writeQuietly();
    }

    synchronized void recordFailure(String phase, String message) {
        diagnostic("errors", phase, message);
        writeQuietly();
    }

    synchronized void diagnostic(String section, String key, Object value) {
        try {
            JSONObject group = diagnostics.optJSONObject(section);
            if (group == null) {
                group = new JSONObject();
                diagnostics.put(section, group);
            }
            group.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException ignored) {
            // Diagnostics must not prevent the source artifact from being written.
        }
    }

    synchronized File writeQuietly() {
        try {
            return write();
        } catch (Exception ignored) {
            return null;
        }
    }

    synchronized File write() throws IOException, JSONException {
        String json = toJson().toString(2) + "\n";
        File internalRoot = new File(context.getFilesDir(), "qcl041");
        writeToDirectory(internalRoot, json);
        File externalRoot = context.getExternalFilesDir(null);
        if (externalRoot != null) {
            writeToDirectory(new File(externalRoot, "qcl041"), json);
        }
        return new File(internalRoot, "latest.json");
    }

    synchronized JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("$schema", Qcl041ProbeConfig.SCHEMA);
        root.put("schema_version", 1);
        root.put("probe_id", "QCL-041");
        root.put("peer_class", "windows");
        root.put("evidence_tier", config.evidenceTier);
        root.put("capture_kind", "live_wifi_direct_lifecycle");
        root.put("live_evidence", true);
        root.put("observed_at_utc", nowUtc());
        root.put("run_id", config.runId);
        root.put("harness", harnessJson());
        root.put("topology", topologyJson());
        root.put("device", deviceJson());
        root.put("host", hostJson());
        root.put("lease", leaseJson());
        root.put("lifecycle", lifecycleJson());
        root.put("measurements", measurementsJson());
        root.put("diagnostics", diagnostics);
        return root;
    }

    private JSONObject harnessJson() throws JSONException {
        JSONObject harness = new JSONObject();
        harness.put("harness_id", Qcl041ProbeConfig.HARNESS_ID);
        harness.put("owner", Qcl041ProbeConfig.HARNESS_OWNER);
        harness.put("route", Qcl041ProbeConfig.ROUTE);
        harness.put("hostess_runs_harness", false);
        harness.put("writes_live_source_artifact", true);
        return harness;
    }

    private static JSONObject topologyJson() throws JSONException {
        JSONObject topology = new JSONObject();
        topology.put("owner", "wifi_direct");
        topology.put("network_provider", "wifi_direct");
        topology.put("endpoint_direction", "peer_to_peer_group");
        topology.put("peer_class", "windows");
        return topology;
    }

    private JSONObject deviceJson() throws JSONException {
        JSONObject device = new JSONObject();
        device.put("model", config.deviceModel);
        device.put("serial", config.deviceSerial);
        device.put("adb_serial", config.deviceSerial);
        device.put("wifi_direct_role", localRole);
        return device;
    }

    private JSONObject hostJson() throws JSONException {
        JSONObject host = new JSONObject();
        host.put("os", "windows");
        host.put("toolchain_profile", config.hostToolchainProfile);
        return host;
    }

    private JSONObject leaseJson() throws JSONException {
        JSONObject lease = new JSONObject();
        lease.put("manager", "Agent Board");
        lease.put("resource", config.leaseResource);
        lease.put("lease_id", config.leaseId);
        lease.put("reserved_before_live_steps", config.leaseReservedBeforeLiveSteps);
        lease.put("released_after_live_steps", config.leaseReleasedAfterLiveSteps);
        lease.put("adb_server_lifecycle_lease_used", false);
        lease.put("reserve_command", config.reserveCommand);
        lease.put("release_command", config.releaseCommand);
        lease.put(
                "adb_server_lifecycle_policy",
                "Use adb-server:lifecycle only for disruptive daemon lifecycle or Wi-Fi ADB setup/recovery.");
        return lease;
    }

    private JSONObject lifecycleJson() throws JSONException {
        JSONObject lifecycle = new JSONObject();
        lifecycle.put("feature", phase(featureStatus, featureEvidence, true));
        lifecycle.put("windows_wifi_direct_api", phase(windowsApiStatus, windowsApiEvidence, true));
        lifecycle.put("permission_state", phase(permissionStatus, permissionEvidence, true));
        JSONObject discovery = phase(peerDiscoveryStatus, peerDiscoveryEvidence, true);
        discovery.put("peer_count", peerCount);
        lifecycle.put("peer_discovery", discovery);
        JSONObject group = phase(groupFormationStatus, groupFormationEvidence, true);
        group.put("local_role", localRole);
        group.put("peer_role", peerRole);
        lifecycle.put("group_formation", group);
        JSONObject socket = phase(socketStatus, socketEvidence, true);
        socket.put("protocol", "tcp");
        socket.put("payload_class", "bounded_tcp_probe");
        socket.put("bounded", true);
        socket.put("messages_sent", messagesSent);
        socket.put("messages_received", messagesReceived);
        lifecycle.put("socket_exchange", socket);
        JSONObject cleanup = phase(cleanupStatus, cleanupEvidence, true);
        cleanup.put("completed", cleanupCompleted);
        lifecycle.put("cleanup", cleanup);
        return lifecycle;
    }

    private JSONObject measurementsJson() throws JSONException {
        JSONObject measurements = new JSONObject();
        if (tcpConnectMs != null) {
            measurements.put("tcp_connect_ms", tcpConnectMs.longValue());
        }
        measurements.put("wifi_direct_peer_count", peerCount);
        if (groupFormationMs != null) {
            measurements.put("group_formation_ms", groupFormationMs.longValue());
        }
        return measurements;
    }

    private static JSONObject phase(String status, String evidence, boolean required) throws JSONException {
        JSONObject phase = new JSONObject();
        phase.put("status", status);
        phase.put("evidence", evidence);
        phase.put("required", required);
        return phase;
    }

    private void writeToDirectory(File directory, String json) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Failed to create " + directory);
        }
        writeFile(new File(directory, "latest.json"), json);
        writeFile(new File(directory, config.artifactFileName()), json);
    }

    private static void writeFile(File file, String json) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String nowUtc() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
