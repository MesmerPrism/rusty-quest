package io.github.mesmerprism.rustymanifold.broker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Debug-build-only, shell-UID product adapter for bounded Q2Q media QA. */
public final class RemoteCameraDebugControlProvider extends ContentProvider {
    private static final String AUTHORITY_CLIENT = "client.quest.spatial-camera-panel";
    private static final String RECEIPT_SCHEMA =
            "rusty.quest.remote_camera.debug_operator_receipt.v1";
    private static final Set<String> STRING_FIELDS = new HashSet<>(Arrays.asList(
            "session_id", "receiver_bind_host", "receiver_ports",
            "transport_bind_host", "transport_receive_ports", "transport_routes",
            "transport_bind_local_address", "transport_socket_authority",
            "sender_source_host", "sender_source_ports", "sender_source_kind",
            "sender_media_profiles", "sender_camera_id", "sender_camera_ids",
            "sender_camera_facing", "sender_quality_profile", "camera_permission_policy",
            "media_layout", "sender_frame_layout"));
    private static final String ADAPTER_INFRASTRUCTURE_LAN = "infrastructure_lan";
    private static final String ADAPTER_WIFI_DIRECT = "wifi_direct";
    private static final String ADAPTER_AUTHENTICATED_TLS_RELAY = "authenticated_tls_relay";

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw new SecurityException("remote_camera_debug_operator_requires_shell_uid");
        }
        if (!"authority-status".equals(method)
                && !"start-diagnostic-synthetic".equals(method)
                && !"start-receiver".equals(method)
                && !"start-sender".equals(method)
                && !"start-duplex".equals(method)
                && !"status".equals(method)
                && !"stop".equals(method)
                && !"stop-diagnostic".equals(method)
                && !"complete-pending".equals(method)) {
            throw new IllegalArgumentException("remote_camera_debug_method_not_registered");
        }
        try {
            JSONObject authority = ManifoldRuntimeAuthorityBridge.evidence();
            JSONObject runtime = null;
            boolean diagnostic = "start-diagnostic-synthetic".equals(method);
            if ("start-receiver".equals(method)
                    || "start-sender".equals(method)
                    || "start-duplex".equals(method)
                    || "stop".equals(method)) {
                String operation = "stop".equals(method) ? "stop" : "start";
                requirePendingAction(authority, operation);
                if ("start-duplex".equals(method)) {
                    Bundle receiverExtras = new Bundle(extras);
                    receiverExtras.putString("local_stream_port",
                            requireText(extras, "receiver_local_stream_port", 10));
                    Bundle senderExtras = new Bundle(extras);
                    senderExtras.putString("local_stream_port",
                            requireText(extras, "sender_local_stream_port", 10));
                    JSONObject receiverRuntime = RemoteCameraSessionRuntime.handleCommand(
                            getContext(), runtimeCommand("start-receiver", receiverExtras, false));
                    requireRuntimeApplied("start-receiver", receiverRuntime);
                    int senderBarrierDelayMs = requireDecimal(
                            extras, "duplex_sender_barrier_delay_ms", 500, 5000);
                    Thread.sleep(senderBarrierDelayMs);
                    JSONObject senderRuntime = RemoteCameraSessionRuntime.handleCommand(
                            getContext(), runtimeCommand("start-sender", senderExtras, false));
                    requireRuntimeApplied("start-sender", senderRuntime);
                    runtime = new JSONObject()
                            .put("schema", "rusty.quest.remote_camera.duplex_start.v1")
                            .put("status", "duplex_runtime_started")
                            .put("receiver_ready", true)
                            .put("media_socket_runtime_started", true)
                            .put("sender_barrier_delay_ms", senderBarrierDelayMs)
                            .put("receiver_runtime", receiverRuntime)
                            .put("sender_runtime", senderRuntime);
                } else {
                    runtime = RemoteCameraSessionRuntime.handleCommand(
                            getContext(), runtimeCommand(method, extras, diagnostic));
                    requireRuntimeApplied(method, runtime);
                }
            } else if (diagnostic) {
                runtime = RemoteCameraSessionRuntime.handleCommand(
                        getContext(), runtimeCommand(method, extras, true));
                requireRuntimeApplied(method, runtime);
            } else if ("stop-diagnostic".equals(method)) {
                runtime = RemoteCameraSessionRuntime.handleCommand(
                        getContext(), runtimeCommand(method, extras, true));
            } else if ("status".equals(method)) {
                runtime = RemoteCameraSessionRuntime.handleCommand(
                        getContext(), runtimeCommand(method, extras, false));
            } else if ("complete-pending".equals(method)) {
                String expectedOperation = requireText(extras, "expected_operation", 16);
                requirePendingAction(authority, expectedOperation);
                JSONObject completionRequest = new JSONObject();
                completionRequest.put("client_id", AUTHORITY_CLIENT);
                runtime = ManifoldRuntimeAuthorityBridge.completeMediaAction(completionRequest);
            }

            JSONObject receipt = new JSONObject();
            receipt.put("$schema", RECEIPT_SCHEMA);
            receipt.put("action", method);
            receipt.put("applied", true);
            receipt.put("diagnostic_without_media_acceptance",
                    diagnostic || "stop-diagnostic".equals(method));
            receipt.put("manifold_pending_action_required",
                    "start-receiver".equals(method) || "start-sender".equals(method)
                            || "start-duplex".equals(method)
                            || "stop".equals(method) || "complete-pending".equals(method));
            receipt.put("manifold_decision_owner",
                    authority.optString("decision_owner_id", ""));
            receipt.put("platform_completion_separate", !"complete-pending".equals(method));
            receipt.put("authority_status", authorityStatus(ManifoldRuntimeAuthorityBridge.evidence()));
            receipt.put("runtime", runtime == null ? JSONObject.NULL : runtime);
            receipt.put("time_utc", Instant.now().toString());
            Bundle output = new Bundle();
            output.putString("receipt_b64", Base64.encodeToString(
                    receipt.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            return output;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "remote_camera_debug_operator_action_failed_"
                            + error.getClass().getSimpleName(), error);
        }
    }

    private static JSONObject authorityStatus(JSONObject authority) throws Exception {
        JSONObject runtime = authority.getJSONObject("runtime");
        JSONObject summary = new JSONObject();
        summary.put("provider_epoch_id", runtime.getString("provider_epoch_id"));
        summary.put("runtime_authority_revision",
                runtime.getJSONObject("host_snapshot").getLong("authority_revision"));
        summary.put("admission_authority_revision",
                runtime.getJSONObject("admission_snapshot").getLong("authority_revision"));
        JSONObject pending = authority.optJSONObject("media_pending_action");
        if (pending == null) {
            summary.put("media_pending_action", JSONObject.NULL);
        } else {
            JSONObject client = pending.getJSONObject("client_authority");
            summary.put("media_pending_action", new JSONObject()
                    .put("$schema", pending.getString("$schema"))
                    .put("operation", pending.getString("operation"))
                    .put("client_id", client.getString("client_id"))
                    .put("lease_id", client.getString("lease_id"))
                    .put("authority_epoch_id", pending.getString("authority_epoch_id"))
                    .put("action_id", pending.getString("action_id")));
        }
        return summary;
    }

    private static void requirePendingAction(JSONObject authority, String operation)
            throws Exception {
        JSONObject action = authority.optJSONObject("media_pending_action");
        if (action == null
                || !"rusty.quest.media_stream_platform_action.v1".equals(
                        action.optString("$schema", ""))
                || !operation.equals(action.optString("operation", ""))) {
            throw new IllegalStateException("exact_pending_media_action_missing");
        }
        JSONObject client = action.getJSONObject("client_authority");
        if (!AUTHORITY_CLIENT.equals(client.optString("client_id", ""))
                || client.optString("lease_id", "").isEmpty()
                || action.optString("authority_epoch_id", "").isEmpty()
                || action.optString("action_id", "").isEmpty()) {
            throw new IllegalStateException("pending_media_action_identity_mismatch");
        }
    }

    private static JSONObject runtimeCommand(String method, Bundle extras, boolean diagnostic)
            throws Exception {
        JSONObject params = new JSONObject();
        Bundle safeExtras = extras == null ? Bundle.EMPTY : extras;
        for (String field : STRING_FIELDS) {
            String value = safeExtras.getString(field, "").trim();
            if (value.length() > 512) {
                throw new IllegalArgumentException("remote_camera_debug_field_too_long_" + field);
            }
            if (!value.isEmpty()) params.put(field, value);
        }
        String sessionId = requireText(safeExtras, "session_id", 160);
        if (diagnostic) {
            int packedPort = requireDecimal(safeExtras, "packed_source_port", 1024, 65535);
            int perEyeWidth = requireDecimal(safeExtras, "per_eye_width", 320, 2048);
            int perEyeHeight = requireDecimal(safeExtras, "per_eye_height", 320, 2048);
            int framesPerSecond = requireDecimal(safeExtras, "frames_per_second", 1, 60);
            int bitrate = requireDecimal(safeExtras, "bitrate", 100000, 40000000);
            int packedWidth = perEyeWidth * 2;
            params.put("sender_source_kind", "diagnostic_synthetic_mediacodec_surface");
            params.put("camera_permission_policy", "no_camera_permission_required");
            params.put("transport_routes", "none");
            params.put("sender_source_ports", "stereo:" + packedPort);
            params.put("sender_media_profiles", "stereo:" + packedWidth + "x" + perEyeHeight
                    + "@" + framesPerSecond + ":" + bitrate);
            params.put("sender_camera_ids", "left:50,right:51");
            params.put("media_layout", "side-by-side-left-right");
            params.put("sender_frame_layout", "sbs-lr|" + packedWidth + "x" + perEyeHeight
                    + "|" + perEyeWidth + "x" + perEyeHeight
                    + "|c2sensor|nearest|20000000|gpu|nostale");
        } else if ("start-receiver".equals(method) || "start-sender".equals(method)) {
            applyAtomicStereoTransport(method, safeExtras, params);
        }
        String command;
        if ("start-receiver".equals(method)) command = "command.remote_camera.start_receiver";
        else if ("start-sender".equals(method) || diagnostic)
            command = "command.remote_camera.start_sender";
        else if ("stop".equals(method) || "stop-diagnostic".equals(method))
            command = "command.remote_camera.stop";
        else command = "command.remote_camera.get_status";
        JSONObject message = new JSONObject();
        message.put("type", "command");
        message.put("schema", "rusty.manifold.command.envelope.v1");
        message.put("request_id", "request.remote_camera.debug." + sessionId + "."
                + method.replace('-', '_'));
        message.put("command_id", command);
        message.put("params", params);
        return message;
    }

    private static void applyAtomicStereoTransport(
            String method, Bundle extras, JSONObject params) throws Exception {
        String adapter = requireText(extras, "transport_adapter", 32);
        if (!ADAPTER_INFRASTRUCTURE_LAN.equals(adapter)
                && !ADAPTER_WIFI_DIRECT.equals(adapter)
                && !ADAPTER_AUTHENTICATED_TLS_RELAY.equals(adapter)) {
            throw new IllegalArgumentException("remote_camera_debug_invalid_transport_adapter");
        }
        int localStreamPort = requireDecimal(extras, "local_stream_port", 1024, 65535);
        int peerPort = requireDecimal(extras, "peer_port", 1024, 65535);
        String peerHost = requirePeerHost(extras, adapter);
        String routeKind = ADAPTER_WIFI_DIRECT.equals(adapter)
                ? RemoteCameraDirectP2pSocketAuthority.ROUTE_KIND_DIRECT_TCP
                : ADAPTER_AUTHENTICATED_TLS_RELAY.equals(adapter)
                        ? RemoteCameraRelayTransport.ROUTE_KIND
                        : "direct_tcp_connect";
        String socketAuthority = ADAPTER_WIFI_DIRECT.equals(adapter)
                ? RemoteCameraDirectP2pSocketAuthority.AUTHORITY
                : RemoteCameraDirectP2pSocketAuthority.PLATFORM_DEFAULT_AUTHORITY;
        String localBindAddress = optionalIpv4Literal(extras, "local_bind_address");
        if (ADAPTER_WIFI_DIRECT.equals(adapter) && localBindAddress.isEmpty()) {
            throw new IllegalArgumentException(
                    "remote_camera_debug_wifi_direct_local_bind_address_required");
        }
        if (!ADAPTER_WIFI_DIRECT.equals(adapter) && !localBindAddress.isEmpty()) {
            throw new IllegalArgumentException(
                    "remote_camera_debug_local_bind_address_reserved_for_wifi_direct");
        }

        params.put("transport_socket_authority", socketAuthority);
        if (!localBindAddress.isEmpty()) {
            params.put("transport_bind_local_address", localBindAddress);
        }
        if ("start-receiver".equals(method)) {
            params.put("receiver_bind_host", "127.0.0.1");
            params.put("receiver_ports", "stereo:" + localStreamPort);
            if (ADAPTER_AUTHENTICATED_TLS_RELAY.equals(adapter)) {
                params.put("transport_routes", stereoRoute(
                        routeKind, peerHost, peerPort, socketAuthority));
                params.put("transport_receive_ports", "none");
            } else {
                params.put("transport_bind_host", "0.0.0.0");
                params.put("transport_receive_ports", "stereo:" + peerPort);
                params.put("transport_routes", "none");
            }
            return;
        }

        int perEyeWidth = requireDecimal(extras, "per_eye_width", 320, 2048);
        int perEyeHeight = requireDecimal(extras, "per_eye_height", 320, 2048);
        int framesPerSecond = requireDecimal(extras, "frames_per_second", 1, 60);
        int bitrate = requireDecimal(extras, "bitrate", 100000, 40000000);
        int packedWidth = perEyeWidth * 2;
        params.put("sender_source_kind", "camera2_mediacodec_surface");
        params.put("sender_source_host", "127.0.0.1");
        params.put("sender_source_ports", "stereo:" + localStreamPort);
        params.put("sender_media_profiles", "stereo:" + packedWidth + "x" + perEyeHeight
                + "@" + framesPerSecond + ":" + bitrate);
        params.put("sender_camera_ids", "left:50,right:51");
        params.put("camera_permission_policy", "camera_permission_required");
        params.put("media_layout", "side-by-side-left-right");
        params.put("sender_frame_layout", "sbs-lr|" + packedWidth + "x" + perEyeHeight
                + "|" + perEyeWidth + "x" + perEyeHeight
                + "|c2sensor|nearest|20000000|gpu|nostale");
        params.put("transport_routes", stereoRoute(
                routeKind, peerHost, peerPort, socketAuthority));
    }

    private static String stereoRoute(
            String routeKind, String host, int port, String socketAuthority) {
        return "remote-camera-stereo|stereo|" + routeKind + "|" + host + "|" + port
                + "|" + socketAuthority;
    }

    private static String requirePeerHost(Bundle extras, String adapter) {
        String host = requireText(extras, "peer_host", 64);
        if (ADAPTER_AUTHENTICATED_TLS_RELAY.equals(adapter) && "127.0.0.1".equals(host)) {
            return host;
        }
        int[] octets = parseIpv4Literal(host);
        boolean privateAddress = octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
        if (!privateAddress) {
            throw new IllegalArgumentException("remote_camera_debug_peer_host_not_private_ipv4");
        }
        return host;
    }

    private static String optionalIpv4Literal(Bundle extras, String field) {
        if (extras == null) return "";
        String value = extras.getString(field, "").trim();
        if (value.isEmpty()) return "";
        parseIpv4Literal(value);
        return value;
    }

    private static int[] parseIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("remote_camera_debug_invalid_ipv4_literal");
        }
        int[] octets = new int[4];
        for (int index = 0; index < parts.length; index++) {
            if (!parts[index].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("remote_camera_debug_invalid_ipv4_literal");
            }
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) {
                throw new IllegalArgumentException("remote_camera_debug_invalid_ipv4_literal");
            }
        }
        return octets;
    }

    private static String requireText(Bundle extras, String field, int maximum) {
        if (extras == null) throw new IllegalArgumentException("remote_camera_debug_missing_" + field);
        String value = extras.getString(field, "").trim();
        if (value.isEmpty() || value.length() > maximum
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("remote_camera_debug_invalid_" + field);
        }
        return value;
    }

    private static int requireDecimal(Bundle extras, String field, int minimum, int maximum) {
        String value = requireText(extras, field, 10);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException("remote_camera_debug_out_of_range_" + field);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("remote_camera_debug_invalid_decimal_" + field, error);
        }
    }

    private static void requireRuntimeApplied(String method, JSONObject runtime) {
        if (runtime == null) throw new IllegalStateException("remote_camera_runtime_missing");
        if ("start-diagnostic-synthetic".equals(method)) {
            JSONObject source = runtime.optJSONObject("sender_source_runtime");
            if (source == null || !source.optBoolean("source_available", false)) {
                throw new IllegalStateException("remote_camera_diagnostic_source_not_started");
            }
        }
        if ("start-sender".equals(method)
                && !runtime.optBoolean("media_socket_runtime_started", false)) {
            throw new IllegalStateException("remote_camera_sender_not_started");
        }
        if ("start-receiver".equals(method) && !runtime.optBoolean("receiver_ready", false)) {
            throw new IllegalStateException("remote_camera_receiver_not_ready");
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read_call_only");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read_call_only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("read_call_only");
    }
}
