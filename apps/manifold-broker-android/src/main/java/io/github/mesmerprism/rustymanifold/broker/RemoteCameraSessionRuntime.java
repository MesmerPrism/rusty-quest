package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class RemoteCameraSessionRuntime {
    private static final String COMMAND_START_RECEIVER = "command.remote_camera.start_receiver";
    private static final String COMMAND_START_SENDER = "command.remote_camera.start_sender";
    private static final String COMMAND_GET_STATUS = "command.remote_camera.get_status";
    private static final String COMMAND_STOP = "command.remote_camera.stop";
    private static final String COMMAND_MEDIA_STREAM_START_SOURCE = "command.media_stream.start_source";
    private static final String COMMAND_MEDIA_STREAM_GET_STATUS = "command.media_stream.get_status";
    private static final String COMMAND_MEDIA_STREAM_STOP = "command.media_stream.stop";
    private static final String STATUS_SCHEMA = "rusty.quest.remote_camera.android_runtime_status.v1";
    private static final String MEDIA_STREAM_STATUS_SCHEMA = "rusty.quest.media_stream.android_runtime_status.v1";
    private static final String LANE_SCHEMA = "rusty.quest.remote_camera.android_runtime_lane.v1";
    private static final String PROP_SESSION_ID = "debug.rustyquest.remote_camera.session_id";
    private static final String PROP_RECEIVER_BIND_HOST =
            "debug.rustyquest.remote_camera.receiver_bind_host";
    private static final String PROP_RECEIVER_PORTS =
            "debug.rustyquest.remote_camera.receiver_ports";
    private static final String PROP_SENDER_SOURCE_HOST =
            "debug.rustyquest.remote_camera.sender_source_host";
    private static final String PROP_SENDER_SOURCE_PORTS =
            "debug.rustyquest.remote_camera.sender_source_ports";
    private static final String PROP_SENDER_SOURCE_KIND =
            "debug.rustyquest.remote_camera.sender_source_kind";
    private static final String PROP_SENDER_MEDIA_PROFILES =
            "debug.rustyquest.remote_camera.sender_media_profiles";
    private static final String PROP_SENDER_CAMERA_ID =
            "debug.rustyquest.remote_camera.sender_camera_id";
    private static final String PROP_SENDER_CAMERA_IDS =
            "debug.rustyquest.remote_camera.sender_camera_ids";
    private static final String PROP_SENDER_CAMERA_FACING =
            "debug.rustyquest.remote_camera.sender_camera_facing";
    private static final String PROP_SENDER_QUALITY_PROFILE =
            "debug.rustyquest.remote_camera.sender_quality_profile";
    private static final String PROP_CAMERA_PERMISSION_POLICY =
            "debug.rustyquest.remote_camera.camera_permission_policy";
    private static final String PROP_TRANSPORT_BIND_HOST =
            "debug.rustyquest.remote_camera.transport_bind_host";
    private static final String PROP_TRANSPORT_RECEIVE_PORTS =
            "debug.rustyquest.remote_camera.transport_receive_ports";
    private static final String PROP_TRANSPORT_ROUTES =
            "debug.rustyquest.remote_camera.transport_routes";
    private static final String PROP_TRANSPORT_BIND_LOCAL_ADDRESS =
            "debug.rustyquest.remote_camera.transport_bind_local_address";
    private static final String PROP_TRANSPORT_SOCKET_AUTHORITY =
            "debug.rustyquest.remote_camera.transport_socket_authority";
    private static final String PROP_MEDIA_LAYOUT =
            "debug.rustyquest.remote_camera.media_layout";
    private static final String PROP_SENDER_FRAME_LAYOUT =
            "debug.rustyquest.remote_camera.sender_frame_layout";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int WIFI_DIRECT_PEER_BIND_WAIT_MS = 12000;
    private static final int WIFI_DIRECT_PEER_BIND_RETRY_SLEEP_MS = 250;
    private static final int RECEIVER_START_READY_WAIT_MS = 5000;
    private static final int RECEIVER_START_READY_POLL_MS = 50;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private static final Object LOCK = new Object();
    private static final Map<String, RuntimeLane> LANES = new LinkedHashMap<>();
    private static final AtomicLong NEXT_LANE_ID = new AtomicLong(1L);
    private static long createdLanes;
    private static long stoppedLanes;
    private static long failedLanes;

    private RemoteCameraSessionRuntime() {
    }

    static JSONObject handleCommand(Context context, JSONObject message) throws Exception {
        String command = commandId(message);
        boolean mediaStreamCommand = isMediaStreamCommand(message);
        if (COMMAND_START_RECEIVER.equals(command)) {
            return startReceiver(message);
        }
        if (COMMAND_START_SENDER.equals(command) || COMMAND_MEDIA_STREAM_START_SOURCE.equals(command)) {
            return startSender(context, message, command, mediaStreamCommand);
        }
        if (COMMAND_GET_STATUS.equals(command) || COMMAND_MEDIA_STREAM_GET_STATUS.equals(command)) {
            return status(message, mediaStreamCommand);
        }
        if (COMMAND_STOP.equals(command) || COMMAND_MEDIA_STREAM_STOP.equals(command)) {
            return stop(message, command, mediaStreamCommand);
        }
        return null;
    }

    static boolean isRemoteCameraCommand(JSONObject message) {
        String command = commandId(message);
        return COMMAND_START_RECEIVER.equals(command)
                || COMMAND_START_SENDER.equals(command)
                || COMMAND_GET_STATUS.equals(command)
                || COMMAND_STOP.equals(command);
    }

    static boolean isMediaStreamCommand(JSONObject message) {
        String command = commandId(message);
        return COMMAND_MEDIA_STREAM_START_SOURCE.equals(command)
                || COMMAND_MEDIA_STREAM_GET_STATUS.equals(command)
                || COMMAND_MEDIA_STREAM_STOP.equals(command);
    }

    private static JSONObject startReceiver(JSONObject message) throws Exception {
        String sessionId = sessionId(message);
        String bindHost = configString(message, PROP_RECEIVER_BIND_HOST, "127.0.0.1", "receiver_bind_host");
        String transportBindHost = configString(
                message,
                PROP_TRANSPORT_BIND_HOST,
                "0.0.0.0",
                "transport_bind_host");
        List<PortBinding> ports = parsePortBindings(configString(
                message,
                PROP_RECEIVER_PORTS,
                "left:8979,right:8980",
                "receiver_ports"));
        List<PortBinding> transportPorts =
                parsePortBindings(configString(
                        message,
                        PROP_TRANSPORT_RECEIVE_PORTS,
                        "left:9079,right:9080",
                        "transport_receive_ports"));
        List<RuntimeLane> receiverLanes = new ArrayList<>();
        for (PortBinding port : ports) {
            RuntimeLane lane = activeLane(sessionId, "receiver", port.eye);
            if (lane == null) {
                PortBinding transportPort = findPortBinding(transportPorts, port.eye);
                lane = RuntimeLane.receiver(
                        sessionId,
                        port.eye,
                        bindHost,
                        port.port,
                        transportBindHost,
                        transportPort != null ? transportPort.port : 0);
                registerAndStart(lane);
            }
            receiverLanes.add(lane);
        }
        JSONObject readiness = waitForReceiverLanesReady(receiverLanes, RECEIVER_START_READY_WAIT_MS);
        JSONArray started = new JSONArray();
        for (RuntimeLane lane : receiverLanes) {
            started.put(lane.toJson());
        }
        JSONObject result = baseResult(COMMAND_START_RECEIVER, sessionId, "receiver_armed", false);
        result.put("marker", "RUSTY_QUEST_REMOTE_CAMERA_RECEIVER_ARMED");
        result.put("media_socket_runtime_started", started.length() > 0);
        result.put("receiver_ready", readiness.optBoolean("ready", false));
        result.put("receiver_ready_status", readiness.optString("status", ""));
        result.put("receiver_ready_count", readiness.optInt("ready_count", 0));
        result.put("receiver_transport_ready_count", readiness.optInt("transport_ready_count", 0));
        result.put("receiver_local_ready_count", readiness.optInt("local_ready_count", 0));
        result.put("receiver_start_ready_wait_ms", readiness.optInt("wait_timeout_ms", RECEIVER_START_READY_WAIT_MS));
        result.put("receiver_start_ready_elapsed_ms", readiness.optLong("elapsed_ms", 0L));
        result.put("receiver_readiness", readiness);
        result.put("started_lanes", started);
        result.put("runtime_status", statusForSession(sessionId, false));
        return result;
    }

    private static JSONObject startSender(
            Context context,
            JSONObject message,
            String command,
            boolean mediaStreamCommand) throws Exception {
        String sessionId = sessionId(message);
        String sourceKind = configString(
                message,
                PROP_SENDER_SOURCE_KIND,
                "external_h264_socket",
                "sender_source_kind",
                "source_kind");
        String sourceHost = configString(
                message,
                PROP_SENDER_SOURCE_HOST,
                "127.0.0.1",
                "sender_source_host",
                "source_host");
        String sourcePorts = configString(
                message,
                PROP_SENDER_SOURCE_PORTS,
                "left:8879,right:8880",
                "sender_source_ports",
                "source_ports");
        List<PortBinding> ports = parsePortBindings(sourcePorts);
        String transportBindLocalAddress = configString(
                message,
                PROP_TRANSPORT_BIND_LOCAL_ADDRESS,
                "",
                "transport_bind_local_address",
                "transport_local_bind_host",
                "sender_transport_bind_local_address");
        String transportSocketAuthority = configString(
                message,
                PROP_TRANSPORT_SOCKET_AUTHORITY,
                "",
                "transport_socket_authority",
                "socket_authority",
                "peer_socket_authority");
        List<PeerRoute> routes = parsePeerRoutes(
                routeOverride(message),
                transportBindLocalAddress,
                transportSocketAuthority);
        JSONObject sourceRuntime = RemoteCameraSourceRuntime.ensureStarted(
                context,
                sessionId,
                sourceKind,
                sourceHost,
                sourcePorts,
                configString(message, PROP_SENDER_MEDIA_PROFILES, "none", "sender_media_profiles", "media_profiles"),
                configString(message, PROP_SENDER_CAMERA_ID, "none", "sender_camera_id", "camera_id"),
                configString(message, PROP_SENDER_CAMERA_IDS, "none", "sender_camera_ids", "camera_ids"),
                configString(message, PROP_SENDER_CAMERA_FACING, "none", "sender_camera_facing", "camera_facing"),
                configString(message, PROP_SENDER_QUALITY_PROFILE, "none", "sender_quality_profile", "quality_profile"),
                configString(
                        message,
                        PROP_CAMERA_PERMISSION_POLICY,
                        "no_camera_permission_required",
                        "camera_permission_policy"),
                configString(
                        message,
                        PROP_MEDIA_LAYOUT,
                        "separate-eye-streams",
                        "media_layout"),
                configString(
                        message,
                        PROP_SENDER_FRAME_LAYOUT,
                        "none",
                        "sender_frame_layout",
                        "frame_layout"));
        boolean sourceAvailable = sourceRuntime.optBoolean("source_available", true);
        JSONArray lanes = new JSONArray();
        int modeledRoutes = 0;
        Context appContext = context == null ? null : context.getApplicationContext();
        for (PortBinding port : ports) {
            RuntimeLane lane = activeLane(sessionId, "sender", port.eye);
            if (lane == null) {
                PeerRoute route = findPeerRoute(routes, port.eye);
                if (route != null && sourceAvailable) {
                    lane = RuntimeLane.senderBridge(appContext, sessionId, port.eye, sourceHost, port.port, route);
                    registerAndStart(lane);
                } else if (route != null) {
                    lane = RuntimeLane.senderPendingSource(sessionId, port.eye, sourceHost, port.port, route);
                    registerWithoutThread(lane);
                } else {
                    lane = RuntimeLane.senderPendingTransport(sessionId, port.eye, sourceHost, port.port);
                    registerWithoutThread(lane);
                }
            }
            if (lane.peerRoute != null) {
                modeledRoutes++;
            }
            lanes.put(lane.toJson());
        }
        boolean allModeled = lanes.length() > 0 && modeledRoutes == lanes.length();
        boolean bridgeStarted = sourceAvailable && allModeled;
        String status = bridgeStarted
                ? "sender_transport_bridge_started"
                : (!sourceAvailable ? "sender_source_unavailable" : "sender_transport_pending");
        JSONObject result = baseResult(
                command,
                sessionId,
                status,
                mediaStreamCommand);
        result.put(
                "marker",
                bridgeStarted
                        ? "RUSTY_QUEST_REMOTE_CAMERA_SENDER_TRANSPORT_BRIDGE_STARTED"
                        : (!sourceAvailable
                                ? "RUSTY_QUEST_REMOTE_CAMERA_SENDER_SOURCE_UNAVAILABLE"
                                : "RUSTY_QUEST_REMOTE_CAMERA_SENDER_PENDING_TRANSPORT"));
        result.put("media_socket_runtime_started", bridgeStarted);
        result.put("transport_peer_modeled", allModeled);
        result.put("sender_source_runtime", sourceRuntime);
        result.put("modeled_route_count", modeledRoutes);
        result.put("started_lanes", lanes);
        result.put("runtime_status", statusForSession(sessionId, mediaStreamCommand));
        return result;
    }

    private static JSONObject stop(JSONObject message, String command, boolean mediaStreamCommand) throws Exception {
        String sessionId = sessionId(message);
        JSONArray stopped = new JSONArray();
        List<RuntimeLane> matches = new ArrayList<>();
        synchronized (LOCK) {
            for (RuntimeLane lane : LANES.values()) {
                if (lane.sessionId.equals(sessionId)) {
                    matches.add(lane);
                }
            }
        }
        for (RuntimeLane lane : matches) {
            lane.stop("stop_command");
            stopped.put(lane.toJson());
        }
        JSONObject sourceStop = RemoteCameraSourceRuntime.stop(sessionId, "stop_command");
        JSONObject result = baseResult(command, sessionId, "stopped", mediaStreamCommand);
        result.put("marker", "RUSTY_QUEST_REMOTE_CAMERA_STOPPED");
        result.put("stopped_count", stopped.length());
        result.put("stopped_lanes", stopped);
        result.put("stopped_sources", sourceStop);
        result.put("runtime_status", statusForSession(sessionId, mediaStreamCommand));
        return result;
    }

    private static JSONObject status(JSONObject message, boolean mediaStreamCommand) throws Exception {
        return statusForSession(sessionId(message), mediaStreamCommand);
    }

    private static JSONObject statusForSession(String sessionId, boolean mediaStreamCommand) throws Exception {
        JSONArray lanes = new JSONArray();
        long created;
        long stopped;
        long failed;
        synchronized (LOCK) {
            created = createdLanes;
            stopped = stoppedLanes;
            failed = failedLanes;
            for (RuntimeLane lane : LANES.values()) {
                if (lane.sessionId.equals(sessionId)) {
                    lanes.put(lane.toJson());
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("schema", mediaStreamCommand ? MEDIA_STREAM_STATUS_SCHEMA : STATUS_SCHEMA);
        result.put("session_id", sessionId);
        result.put("active_count", activeCount(sessionId));
        result.put("matched_count", lanes.length());
        result.put("created_count", created);
        result.put("stopped_count", stopped);
        result.put("failed_count", failed);
        result.put("lanes", lanes);
        result.put("high_rate_json_payload", false);
        result.put("media_payload_plane", "binary-media");
        result.put("sender_source_runtime", RemoteCameraSourceRuntime.statusForSession(sessionId));
        if (mediaStreamCommand) {
            result.put("runtime_family", "media_stream");
            result.put("compatibility_runtime", "remote_camera");
        }
        return result;
    }

    private static JSONObject baseResult(
            String command,
            String sessionId,
            String status,
            boolean mediaStreamCommand) throws Exception {
        JSONObject result = new JSONObject();
        result.put("schema", mediaStreamCommand ? MEDIA_STREAM_STATUS_SCHEMA : STATUS_SCHEMA);
        result.put("command_id", command);
        result.put("session_id", sessionId);
        result.put("status", status);
        result.put("high_rate_json_payload", false);
        result.put("media_payload_plane", "binary-media");
        if (mediaStreamCommand) {
            result.put("runtime_family", "media_stream");
            result.put("compatibility_runtime", "remote_camera");
        }
        return result;
    }

    private static void registerAndStart(final RuntimeLane lane) {
        registerWithoutThread(lane);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                lane.run();
            }
        }, "rusty-remote-camera-" + lane.role + "-" + lane.eye);
        lane.thread = thread;
        thread.start();
    }

    private static void registerWithoutThread(RuntimeLane lane) {
        synchronized (LOCK) {
            createdLanes++;
            LANES.put(lane.laneId, lane);
        }
    }

    private static JSONObject waitForReceiverLanesReady(
            List<RuntimeLane> receiverLanes,
            int timeoutMs) throws Exception {
        long startedMs = System.currentTimeMillis();
        long deadlineMs = startedMs + Math.max(0, timeoutMs);
        ReceiverReadinessCounts counts = countReceiverReadiness(receiverLanes);
        while (receiverLanes.size() > 0
                && counts.readyCount < receiverLanes.size()
                && counts.failedCount == 0
                && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(RECEIVER_START_READY_POLL_MS);
            counts = countReceiverReadiness(receiverLanes);
        }
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedMs);
        JSONObject result = new JSONObject();
        result.put("schema", "rusty.quest.remote_camera.receiver_start_readiness.v1");
        result.put("ready", receiverLanes.size() > 0 && counts.readyCount == receiverLanes.size());
        result.put("status", counts.readyCount == receiverLanes.size()
                ? "ready"
                : counts.failedCount > 0 ? "failed" : "timeout");
        result.put("lane_count", receiverLanes.size());
        result.put("ready_count", counts.readyCount);
        result.put("transport_ready_count", counts.transportReadyCount);
        result.put("local_ready_count", counts.localReadyCount);
        result.put("failed_count", counts.failedCount);
        result.put("wait_timeout_ms", Math.max(0, timeoutMs));
        result.put("poll_ms", RECEIVER_START_READY_POLL_MS);
        result.put("elapsed_ms", elapsedMs);
        JSONArray lanes = new JSONArray();
        for (RuntimeLane lane : receiverLanes) {
            JSONObject laneReadiness = new JSONObject();
            laneReadiness.put("lane_id", lane.laneId);
            laneReadiness.put("eye", lane.eye);
            laneReadiness.put("state", lane.state);
            laneReadiness.put("receiver_ready", lane.receiverReady());
            laneReadiness.put("receiver_transport_ready", lane.receiverTransportReady());
            laneReadiness.put("receiver_local_ready", lane.receiverLocalReady());
            if (lane.error.length() > 0) {
                laneReadiness.put("error", lane.error);
            }
            lanes.put(laneReadiness);
        }
        result.put("lanes", lanes);
        return result;
    }

    private static ReceiverReadinessCounts countReceiverReadiness(List<RuntimeLane> receiverLanes) {
        ReceiverReadinessCounts counts = new ReceiverReadinessCounts();
        for (RuntimeLane lane : receiverLanes) {
            if (lane.receiverReady()) {
                counts.readyCount += 1;
            }
            if (lane.receiverTransportReady()) {
                counts.transportReadyCount += 1;
            }
            if (lane.receiverLocalReady()) {
                counts.localReadyCount += 1;
            }
            if (lane.isTerminal() && !lane.receiverReady()) {
                counts.failedCount += 1;
            }
        }
        return counts;
    }

    private static int activeCount(String sessionId) {
        int count = 0;
        synchronized (LOCK) {
            for (RuntimeLane lane : LANES.values()) {
                if (lane.sessionId.equals(sessionId) && !lane.isTerminal()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static RuntimeLane activeLane(String sessionId, String role, String eye) {
        synchronized (LOCK) {
            for (RuntimeLane lane : LANES.values()) {
                if (lane.sessionId.equals(sessionId)
                        && lane.role.equals(role)
                        && lane.eye.equals(eye)
                        && !lane.isTerminal()) {
                    return lane;
                }
            }
        }
        return null;
    }

    private static String commandId(JSONObject message) {
        String command = message.optString("command_id", "");
        if (command.length() == 0) {
            command = message.optString("command", "");
        }
        return command;
    }

    private static String sessionId(JSONObject message) {
        String target = messageString(message, "target_id", "session_id");
        if (target.length() > 0) {
            return target;
        }
        return property(PROP_SESSION_ID, "session.remote_camera.unknown");
    }

    private static List<PortBinding> parsePortBindings(String value) {
        List<PortBinding> bindings = new ArrayList<>();
        if (value == null || value.trim().length() == 0 || "none".equals(value.trim())) {
            return bindings;
        }
        String[] entries = value.split(",");
        for (String entry : entries) {
            String[] parts = entry.trim().split(":");
            if (parts.length != 2) {
                continue;
            }
            try {
                int port = Integer.parseInt(parts[1].trim());
                if (port > 0 && port <= 65535) {
                    bindings.add(new PortBinding(parts[0].trim(), port));
                }
            } catch (NumberFormatException ignored) {
                // Invalid properties are ignored and surfaced as missing lanes in status.
            }
        }
        return bindings;
    }

    private static List<PeerRoute> parsePeerRoutes(
            String value,
            String localBindHost,
            String defaultSocketAuthority) {
        List<PeerRoute> routes = new ArrayList<>();
        if (value == null || value.trim().length() == 0 || "none".equals(value.trim())) {
            return routes;
        }
        String[] entries = value.split(";");
        for (String entry : entries) {
            String[] parts = entry.trim().split("\\|");
            if (parts.length == 5 || parts.length == 6) {
                try {
                    int port = Integer.parseInt(parts[4].trim());
                    String routeKind = parts[2].trim();
                    String socketAuthority = parts.length == 6
                            ? parts[5].trim()
                            : defaultSocketAuthority == null || defaultSocketAuthority.trim().length() == 0
                                    ? RemoteCameraDirectP2pSocketAuthority.defaultSocketAuthority(routeKind)
                                    : defaultSocketAuthority.trim();
                    if (port > 0
                            && port <= 65535
                            && RemoteCameraDirectP2pSocketAuthority.isValidRouteAuthorityContract(
                                    routeKind,
                                    socketAuthority)) {
                        routes.add(new PeerRoute(
                                parts[0].trim(),
                                parts[1].trim(),
                                routeKind,
                                parts[3].trim(),
                                port,
                                localBindHost,
                                socketAuthority));
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid route properties are ignored and surfaced as pending lanes in status.
                }
                continue;
            }
            parts = entry.trim().split(":");
            if (parts.length == 3) {
                try {
                    int port = Integer.parseInt(parts[2].trim());
                    if (port > 0 && port <= 65535) {
                        routes.add(new PeerRoute(
                                "remote-camera-" + parts[0].trim(),
                                parts[0].trim(),
                                "direct_tcp_connect",
                                parts[1].trim(),
                                port,
                                localBindHost,
                                defaultSocketAuthority == null || defaultSocketAuthority.trim().length() == 0
                                        ? RemoteCameraDirectP2pSocketAuthority.PLATFORM_DEFAULT_AUTHORITY
                                        : defaultSocketAuthority.trim()));
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid route properties are ignored and surfaced as pending lanes in status.
                }
            }
        }
        return routes;
    }

    private static String routeOverride(JSONObject message) {
        String override = messageString(message, "transport_routes");
        if (override.length() > 0) {
            return override;
        }
        return property(PROP_TRANSPORT_ROUTES, "none");
    }

    private static String configString(
            JSONObject message,
            String propertyName,
            String fallback,
            String... keys) {
        String value = messageString(message, keys);
        if (value.length() > 0) {
            return value;
        }
        return property(propertyName, fallback);
    }

    private static String messageString(JSONObject message, String... keys) {
        if (message == null || keys == null) {
            return "";
        }
        JSONObject params = message.optJSONObject("params");
        JSONObject input = message.optJSONObject("input");
        for (String key : keys) {
            String value = jsonString(message, key);
            if (value.length() > 0) {
                return value;
            }
            value = jsonString(params, key);
            if (value.length() > 0) {
                return value;
            }
            value = jsonString(input, key);
            if (value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    private static String jsonString(JSONObject object, String key) {
        if (object == null || key == null || key.length() == 0 || !object.has(key) || object.isNull(key)) {
            return "";
        }
        return object.optString(key, "").trim();
    }

    private static Socket createTransportPeerSocket(
            Context context,
            PeerRoute peerRoute,
            RuntimeLane lane) throws IOException {
        InetAddress peerAddress = InetAddress.getByName(peerRoute.connectHost);
        boolean wifiDirectPeerRequired =
                RemoteCameraDirectP2pSocketAuthority.requiresDirectP2pSocket(
                        peerRoute.routeKind,
                        peerRoute.socketAuthority,
                        peerAddress);
        lane.peerSocketWifiDirectBindRequired = wifiDirectPeerRequired;
        long deadlineMs = System.currentTimeMillis() + WIFI_DIRECT_PEER_BIND_WAIT_MS;
        String lastSelection = "";
        do {
            lane.peerSocketWifiDirectBindAttempts++;
            resetPeerSocketBindingDiagnostics(lane);
            Socket socket = createTransportPeerSocketOnce(context, peerRoute, peerAddress, lane);
            boolean wifiDirectSocketReady = hasDirectP2pNetworkBinding(lane)
                    || hasDirectP2pLocalAddressBinding(lane);
            if (!wifiDirectPeerRequired || wifiDirectSocketReady) {
                return socket;
            }
            lastSelection = lane.peerSocketNetworkSelection;
            closeQuietly(socket);
            if (System.currentTimeMillis() >= deadlineMs || lane.stopRequested) {
                lane.peerSocketNetworkSelection = "wifi_direct_binding_unavailable";
                if (lane.peerSocketLocalAddressError.length() == 0) {
                    lane.peerSocketLocalAddressError = "last_selection=" + lastSelection;
                }
                throw new IOException("Wi-Fi Direct local socket binding unavailable for peer "
                        + peerAddress.getHostAddress());
            }
            lane.peerSocketNetworkSelection = "waiting_for_wifi_direct_binding";
            try {
                Thread.sleep(WIFI_DIRECT_PEER_BIND_RETRY_SLEEP_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Wi-Fi Direct local socket binding", ex);
            }
        } while (true);
    }

    private static boolean hasDirectP2pNetworkBinding(RuntimeLane lane) {
        return lane.peerSocketBoundToWifiDirectNetwork
                && lane.peerSocketNetworkRouteMatchesPeer
                && RemoteCameraDirectP2pSocketAuthority.isP2pInterfaceName(
                        lane.peerSocketNetworkInterface);
    }

    private static boolean hasDirectP2pLocalAddressBinding(RuntimeLane lane) {
        return lane.peerSocketBoundLocalAddress.length() > 0
                && lane.peerSocketLocalAddressSameSubnet
                && RemoteCameraDirectP2pSocketAuthority.isP2pInterfaceName(
                        lane.peerSocketLocalInterface);
    }

    private static Socket createTransportPeerSocketOnce(
            Context context,
            PeerRoute peerRoute,
            InetAddress peerAddress,
            RuntimeLane lane) throws IOException {
        Network wifiDirectNetwork = findWifiDirectNetworkForPeer(context, peerAddress, lane);
        Socket socket;
        if (wifiDirectNetwork != null) {
            socket = wifiDirectNetwork.getSocketFactory().createSocket();
            lane.peerSocketCreatedFromWifiDirectNetwork = true;
            lane.peerSocketBoundToWifiDirectNetwork = true;
        } else {
            socket = new Socket();
            lane.peerSocketCreatedFromWifiDirectNetwork = false;
            lane.peerSocketBoundToWifiDirectNetwork = false;
        }
        if (peerRoute.localBindHost.length() > 0) {
            bindSocketToExplicitLocalAddress(
                    socket,
                    peerRoute.localBindHost,
                    peerAddress,
                    RemoteCameraDirectP2pSocketAuthority.requiresDirectP2pSocket(
                            peerRoute.routeKind,
                            peerRoute.socketAuthority,
                            peerAddress),
                    lane);
            if (lane.peerSocketBoundLocalAddress.length() > 0) {
                return socket;
            }
        }
        bindSocketToWifiDirectLocalAddress(socket, peerAddress, lane);
        return socket;
    }

    private static void bindSocketToExplicitLocalAddress(
            Socket socket,
            String localBindHost,
            InetAddress peerAddress,
            boolean wifiDirectPeerRequired,
            RuntimeLane lane) {
        try {
            InetAddress localAddress = InetAddress.getByName(localBindHost);
            String localInterface =
                    RemoteCameraDirectP2pSocketAuthority.findInterfaceNameForAddress(localAddress);
            boolean sameSubnet =
                    RemoteCameraDirectP2pSocketAuthority.sameIpv4Slash24(localAddress, peerAddress);
            lane.peerSocketLocalInterface = localInterface;
            lane.peerSocketLocalAddressSameSubnet = sameSubnet;
            if (wifiDirectPeerRequired
                    && (!sameSubnet
                            || !RemoteCameraDirectP2pSocketAuthority.isP2pInterfaceName(
                                    localInterface))) {
                lane.peerSocketNetworkSelection = "explicit_local_bind_rejected_not_p2p_peer_subnet";
                lane.peerSocketBindLocalAddressError = "local_address="
                        + localAddress.getHostAddress()
                        + " interface="
                        + localInterface
                        + " same_subnet="
                        + sameSubnet;
                return;
            }
            socket.bind(new InetSocketAddress(localAddress, 0));
            lane.peerSocketBindLocalAddressError = "";
            lane.peerSocketBoundLocalAddress = localAddress.getHostAddress();
            lane.peerSocketNetworkSelection =
                    RemoteCameraDirectP2pSocketAuthority.EXPLICIT_LOCAL_BIND_SELECTION;
        } catch (Exception ex) {
            lane.peerSocketBindLocalAddressError = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
        }
    }

    private static void resetPeerSocketBindingDiagnostics(RuntimeLane lane) {
        lane.peerSocketCreatedFromWifiDirectNetwork = false;
        lane.peerSocketBoundToWifiDirectNetwork = false;
        lane.peerSocketNetworkRouteMatchesPeer = false;
        lane.peerSocketNetworkWifiTransport = false;
        lane.peerSocketLocalAddressSameSubnet = false;
        lane.peerSocketNetworkInterface = "";
        lane.peerSocketNetworkSelection = "";
        lane.peerSocketLocalInterface = "";
        lane.peerSocketBoundLocalAddress = "";
        lane.peerSocketBindLocalAddressError = "";
        lane.peerSocketLocalAddressError = "";
    }

    private static Network findWifiDirectNetworkForPeer(
            Context context,
            InetAddress peerAddress,
            RuntimeLane lane) {
        if (context == null) {
            lane.peerSocketNetworkSelection = "context_unavailable";
            return null;
        }
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            lane.peerSocketNetworkSelection = "connectivity_manager_unavailable";
            return null;
        }
        Network[] networks = connectivityManager.getAllNetworks();
        for (Network network : networks) {
            LinkProperties properties = connectivityManager.getLinkProperties(network);
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (properties == null) {
                continue;
            }
            String interfaceName = properties.getInterfaceName();
            boolean p2pInterface = interfaceName != null
                    && interfaceName.toLowerCase(Locale.US).contains("p2p");
            boolean routeMatches = false;
            for (RouteInfo route : properties.getRoutes()) {
                try {
                    if (peerAddress != null && route.matches(peerAddress)) {
                        routeMatches = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // Diagnostic route inspection only; keep scanning.
                }
            }
            boolean wifiTransport = capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            if (p2pInterface) {
                lane.peerSocketNetworkInterface = interfaceName == null ? "" : interfaceName;
                lane.peerSocketNetworkRouteMatchesPeer = routeMatches;
                lane.peerSocketNetworkWifiTransport = wifiTransport;
                if (routeMatches) {
                    lane.peerSocketNetworkSelection = "p2p_interface_route_match";
                    return network;
                }
                lane.peerSocketNetworkSelection = "p2p_interface_route_mismatch";
            }
        }
        if (lane.peerSocketNetworkInterface.length() == 0) {
            lane.peerSocketNetworkSelection = "p2p_network_not_found";
        }
        return null;
    }

    private static void bindSocketToWifiDirectLocalAddress(
            Socket socket,
            InetAddress peerAddress,
            RuntimeLane lane) {
        InetAddress localAddress = findWifiDirectLocalAddress(peerAddress, lane);
        if (localAddress == null) {
            return;
        }
        try {
            socket.bind(new InetSocketAddress(localAddress, 0));
            lane.peerSocketBindLocalAddressError = "";
            lane.peerSocketBoundLocalAddress = localAddress.getHostAddress();
            lane.peerSocketNetworkSelection =
                    RemoteCameraDirectP2pSocketAuthority.INTERFACE_FALLBACK_SELECTION;
        } catch (Exception ex) {
            lane.peerSocketBindLocalAddressError = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
        }
    }

    private static InetAddress findWifiDirectLocalAddress(InetAddress peerAddress, RuntimeLane lane) {
        try {
            RemoteCameraDirectP2pSocketAuthority.LocalAddressCandidate candidate =
                    RemoteCameraDirectP2pSocketAuthority.findLocalAddressCandidate(peerAddress);
            if (candidate != null) {
                lane.peerSocketLocalInterface = candidate.interfaceName;
                lane.peerSocketLocalAddressSameSubnet = candidate.sameSubnet;
                if (lane.peerSocketNetworkSelection.length() == 0
                        || "p2p_network_not_found".equals(lane.peerSocketNetworkSelection)) {
                    lane.peerSocketNetworkSelection =
                            RemoteCameraDirectP2pSocketAuthority.INTERFACE_FALLBACK_SELECTION;
                }
                return candidate.address;
            }
        } catch (Exception ex) {
            lane.peerSocketLocalAddressError = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
        }
        return null;
    }

    private static PortBinding findPortBinding(List<PortBinding> bindings, String eye) {
        for (PortBinding binding : bindings) {
            if (binding.eye.equals(eye)) {
                return binding;
            }
        }
        return null;
    }

    private static PeerRoute findPeerRoute(List<PeerRoute> routes, String eye) {
        for (PeerRoute route : routes) {
            if (route.eye.equals(eye)) {
                return route;
            }
        }
        return null;
    }

    private static String property(String name, String fallback) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties
                    .getMethod("get", String.class, String.class)
                    .invoke(null, name, fallback);
            return value != null ? value.toString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void noteStopped(RuntimeLane lane, boolean failed) {
        synchronized (LOCK) {
            if (lane.terminalCounted) {
                return;
            }
            lane.terminalCounted = true;
            if (failed) {
                failedLanes++;
            } else {
                stoppedLanes++;
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private static final class PortBinding {
        final String eye;
        final int port;

        PortBinding(String eye, int port) {
            this.eye = eye;
            this.port = port;
        }
    }

    private static final class PeerRoute {
        final String laneId;
        final String eye;
        final String routeKind;
        final String connectHost;
        final int connectPort;
        final String localBindHost;
        final String socketAuthority;

        PeerRoute(
                String laneId,
                String eye,
                String routeKind,
                String connectHost,
                int connectPort,
                String localBindHost,
                String socketAuthority) {
            this.laneId = laneId;
            this.eye = eye;
            this.routeKind = routeKind;
            this.connectHost = connectHost;
            this.connectPort = connectPort;
            this.localBindHost = localBindHost == null ? "" : localBindHost.trim();
            this.socketAuthority = socketAuthority == null
                    ? RemoteCameraDirectP2pSocketAuthority.PLATFORM_DEFAULT_AUTHORITY
                    : socketAuthority.trim();
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("lane_id", laneId);
            json.put("eye", eye);
            json.put("route_kind", routeKind);
            json.put("connect_host", connectHost);
            json.put("connect_port", connectPort);
            json.put("socket_authority", socketAuthority);
            if (localBindHost.length() > 0) {
                json.put("local_bind_host", localBindHost);
            }
            json.put("media_payload_plane", "binary-media");
            json.put("high_rate_json_payload", false);
            return json;
        }

        String socketAuthority() {
            return socketAuthority;
        }
    }

    private static final class ReceiverReadinessCounts {
        int readyCount;
        int transportReadyCount;
        int localReadyCount;
        int failedCount;
    }

    private static final class RuntimeLane {
        final String laneId;
        final String role;
        final String sessionId;
        final String eye;
        final String host;
        final int port;
        final String transportHost;
        final int transportPort;
        final PeerRoute peerRoute;
        final Context context;
        final long startedUnixMs;
        volatile String state;
        volatile String closeReason = "";
        volatile String error = "";
        volatile long bytesSent;
        volatile long bytesReceived;
        volatile long copyBytesRead;
        volatile long copyBytesWritten;
        volatile long copyReadOperations;
        volatile long copyWriteOperations;
        volatile long copyFirstReadUnixMs;
        volatile long copyLastReadUnixMs;
        volatile long copyLastWriteUnixMs;
        volatile int copyLastReadSize;
        volatile int copyLastWriteSize;
        volatile boolean stopRequested;
        volatile boolean terminalCounted;
        volatile Thread thread;
        volatile ServerSocket serverSocket;
        volatile ServerSocket transportServerSocket;
        volatile Socket localClientSocket;
        volatile Socket transportSocket;
        volatile Socket localSourceSocket;
        volatile Socket peerSocket;
        volatile long transportServerBindAttemptUnixMs;
        volatile long transportServerBoundUnixMs;
        volatile int transportServerBoundPort;
        volatile String transportServerBoundAddress = "";
        volatile String transportServerBoundInterface = "";
        volatile String transportServerBindError = "";
        volatile long localReceiverBindAttemptUnixMs;
        volatile long localReceiverBoundUnixMs;
        volatile int localReceiverBoundPort;
        volatile String localReceiverBoundAddress = "";
        volatile String localReceiverBoundInterface = "";
        volatile String localReceiverBindError = "";
        volatile boolean peerSocketCreatedFromWifiDirectNetwork;
        volatile boolean peerSocketBoundToWifiDirectNetwork;
        volatile boolean peerSocketNetworkRouteMatchesPeer;
        volatile boolean peerSocketNetworkWifiTransport;
        volatile boolean peerSocketLocalAddressSameSubnet;
        volatile boolean peerSocketWifiDirectBindRequired;
        volatile int peerSocketWifiDirectBindAttempts;
        volatile String peerSocketNetworkInterface = "";
        volatile String peerSocketNetworkSelection = "";
        volatile String peerSocketLocalInterface = "";
        volatile String peerSocketBoundLocalAddress = "";
        volatile String peerSocketBindLocalAddressError = "";
        volatile String peerSocketLocalAddressError = "";
        volatile long transportAcceptCount;
        volatile long localClientAcceptCount;
        volatile long streamRecycleCount;
        volatile long lastStreamRecycleUnixMs;

        static RuntimeLane receiver(
                String sessionId,
                String eye,
                String bindHost,
                int port,
                String transportBindHost,
                int transportPort) {
            return new RuntimeLane(
                    "receiver",
                    sessionId,
                    eye,
                    bindHost,
                    port,
                    transportBindHost,
                    transportPort,
                    null,
                    null,
                    "starting");
        }

        static RuntimeLane senderPendingTransport(String sessionId, String eye, String sourceHost, int port) {
            return new RuntimeLane(
                    "sender",
                    sessionId,
                    eye,
                    sourceHost,
                    port,
                    "",
                    0,
                    null,
                    null,
                    "transport_endpoint_pending");
        }

        static RuntimeLane senderBridge(
                Context context,
                String sessionId,
                String eye,
                String sourceHost,
                int port,
                PeerRoute peerRoute) {
            return new RuntimeLane(
                    "sender",
                    sessionId,
                    eye,
                    sourceHost,
                    port,
                    "",
                    0,
                    peerRoute,
                    context,
                    "transport_bridge_starting");
        }

        static RuntimeLane senderPendingSource(
                String sessionId,
                String eye,
                String sourceHost,
                int port,
                PeerRoute peerRoute) {
            return new RuntimeLane(
                    "sender",
                    sessionId,
                    eye,
                    sourceHost,
                    port,
                    "",
                    0,
                    peerRoute,
                    null,
                    "sender_source_unavailable");
        }

        RuntimeLane(
                String role,
                String sessionId,
                String eye,
                String host,
                int port,
                String transportHost,
                int transportPort,
                PeerRoute peerRoute,
                Context context,
                String state) {
            this.laneId = "remote-camera-" + role + "-" + eye + "-" + NEXT_LANE_ID.getAndIncrement();
            this.role = role;
            this.sessionId = sessionId;
            this.eye = eye;
            this.host = host;
            this.port = port;
            this.transportHost = transportHost;
            this.transportPort = transportPort;
            this.peerRoute = peerRoute;
            this.context = context;
            this.state = state;
            this.startedUnixMs = System.currentTimeMillis();
        }

        void run() {
            if ("receiver".equals(role)) {
                runReceiver();
            } else if (peerRoute != null) {
                runSenderBridge();
            }
        }

        void runReceiver() {
            ServerSocket server = null;
            ServerSocket transportServer = null;
            Socket client = null;
            Socket remote = null;
            try {
                if (transportPort > 0) {
                    state = "binding_transport_receiver";
                    transportServerBindAttemptUnixMs = System.currentTimeMillis();
                    transportServer = new ServerSocket();
                    transportServer.setReuseAddress(true);
                    try {
                        transportServer.bind(new InetSocketAddress(InetAddress.getByName(transportHost), transportPort));
                    } catch (IOException ex) {
                        transportServerBindError = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                        throw ex;
                    }
                    transportServerSocket = transportServer;
                    noteTransportServerBound(transportServer);
                    while (!stopRequested) {
                        try {
                            server = bindLocalReceiverServer();
                            state = streamRecycleCount == 0L
                                    ? "waiting_for_transport_peer_with_local_listener"
                                    : "waiting_for_transport_peer_recycle_with_local_listener";
                            remote = transportServer.accept();
                            transportSocket = remote;
                            remote.setTcpNoDelay(true);
                            transportAcceptCount += 1L;
                            state = "transport_peer_connected_waiting_for_local_client";
                            state = "waiting_for_local_client_after_transport_peer";
                            client = server.accept();
                            localClientSocket = client;
                            client.setTcpNoDelay(true);
                            localClientAcceptCount += 1L;
                            state = "transport_peer_connected_streaming_to_local_client";
                            closeReason = "";
                            long segmentStartBytesRead = copyBytesRead;
                            long segmentStartBytesWritten = copyBytesWritten;
                            try {
                                copyStream(remote.getInputStream(), client.getOutputStream(), false);
                            } catch (IOException ex) {
                                boolean copiedSegmentBytes = copyBytesRead > segmentStartBytesRead
                                        || copyBytesWritten > segmentStartBytesWritten;
                                closeReason = copiedSegmentBytes
                                        ? "transport_stream_copy_error_after_bytes:"
                                                + ex.getClass().getSimpleName()
                                        : "transport_stream_copy_error:" + ex.getClass().getSimpleName();
                            }
                            if (!stopRequested) {
                                if (closeReason == null || closeReason.length() == 0) {
                                    closeReason = "transport_peer_closed";
                                }
                            }
                        } finally {
                            closeQuietly(remote);
                            closeQuietly(client);
                            closeQuietly(server);
                            if (transportSocket == remote) {
                                transportSocket = null;
                            }
                            if (localClientSocket == client) {
                                localClientSocket = null;
                            }
                            if (serverSocket == server) {
                                serverSocket = null;
                            }
                            remote = null;
                            client = null;
                            server = null;
                        }
                        if (!stopRequested) {
                            streamRecycleCount += 1L;
                            lastStreamRecycleUnixMs = System.currentTimeMillis();
                            state = "transport_stream_recycle_waiting_for_peer";
                        }
                    }
                    markStopped("stop_requested");
                    return;
                } else {
                    server = bindLocalReceiverServer();
                    state = "waiting_for_local_client";
                    client = server.accept();
                    localClientSocket = client;
                    localClientAcceptCount += 1L;
                    client.setTcpNoDelay(true);
                    state = "local_client_connected_waiting_for_remote_media";
                    while (!stopRequested && !client.isClosed()) {
                        Thread.sleep(100L);
                    }
                    markStopped("stop_requested");
                    return;
                }
            } catch (Exception ex) {
                markFailed(ex);
            } finally {
                closeQuietly(remote);
                closeQuietly(client);
                closeQuietly(transportServer);
                closeQuietly(server);
            }
        }

        ServerSocket bindLocalReceiverServer() throws IOException {
            state = "binding_local_receiver";
            localReceiverBindAttemptUnixMs = System.currentTimeMillis();
            ServerSocket localServer = new ServerSocket();
            localServer.setReuseAddress(true);
            try {
                localServer.bind(new InetSocketAddress(InetAddress.getByName(host), port));
            } catch (IOException ex) {
                localReceiverBindError = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                throw ex;
            }
            serverSocket = localServer;
            noteLocalReceiverBound(localServer);
            return localServer;
        }

        void noteTransportServerBound(ServerSocket socket) {
            transportServerBoundUnixMs = System.currentTimeMillis();
            transportServerBindError = "";
            InetSocketAddress address = (InetSocketAddress) socket.getLocalSocketAddress();
            transportServerBoundAddress = address.getAddress().getHostAddress();
            transportServerBoundPort = address.getPort();
            transportServerBoundInterface = findInterfaceName(address.getAddress());
        }

        void noteLocalReceiverBound(ServerSocket socket) {
            localReceiverBoundUnixMs = System.currentTimeMillis();
            localReceiverBindError = "";
            InetSocketAddress address = (InetSocketAddress) socket.getLocalSocketAddress();
            localReceiverBoundAddress = address.getAddress().getHostAddress();
            localReceiverBoundPort = address.getPort();
            localReceiverBoundInterface = findInterfaceName(address.getAddress());
        }

        void notePeerSocketConnected(Socket socket) throws IOException {
            if (!(socket.getLocalSocketAddress() instanceof InetSocketAddress)
                    || !(socket.getRemoteSocketAddress() instanceof InetSocketAddress)) {
                throw new IOException("Connected peer socket did not expose Internet socket addresses");
            }
            InetSocketAddress localAddress = (InetSocketAddress) socket.getLocalSocketAddress();
            InetSocketAddress remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
            peerSocketBoundLocalAddress = localAddress.getAddress().getHostAddress();
            peerSocketLocalInterface = findInterfaceName(localAddress.getAddress());
            peerSocketLocalAddressSameSubnet =
                    RemoteCameraDirectP2pSocketAuthority.sameIpv4Slash24(
                            localAddress.getAddress(),
                            remoteAddress.getAddress());
        }

        String findInterfaceName(InetAddress address) {
            try {
                return RemoteCameraDirectP2pSocketAuthority.findInterfaceNameForAddress(address);
            } catch (Exception ex) {
                return "";
            }
        }

        void runSenderBridge() {
            Socket source = null;
            Socket peer = null;
            try {
                state = "connecting_local_source";
                source = new Socket();
                source.setTcpNoDelay(true);
                source.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                localSourceSocket = source;
                state = "connecting_transport_peer";
                peer = createTransportPeerSocket(context, peerRoute, this);
                peer.setTcpNoDelay(true);
                peerSocket = peer;
                peer.connect(new InetSocketAddress(peerRoute.connectHost, peerRoute.connectPort), CONNECT_TIMEOUT_MS);
                notePeerSocketConnected(peer);
                if (RemoteCameraDirectP2pSocketAuthority.requiresDirectP2pSocket(
                                peerRoute.routeKind,
                                peerRoute.socketAuthority,
                                InetAddress.getByName(peerRoute.connectHost))
                        && !hasDirectP2pNetworkBinding(this)
                        && !hasDirectP2pLocalAddressBinding(this)) {
                    throw new IOException("Connected peer socket did not retain direct P2P authority");
                }
                state = "transport_peer_connected_streaming_from_local_source";
                copyStream(source.getInputStream(), peer.getOutputStream(), true);
                if (!stopRequested) {
                    markStopped("local_source_closed");
                } else {
                    markStopped("stop_requested");
                }
            } catch (Exception ex) {
                markFailed(ex);
            } finally {
                closeQuietly(source);
                closeQuietly(peer);
            }
        }

        void copyStream(InputStream input, OutputStream output, boolean senderDirection) throws Exception {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            while (!stopRequested) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    Thread.sleep(100L);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (copyFirstReadUnixMs == 0L) {
                    copyFirstReadUnixMs = now;
                }
                copyLastReadUnixMs = now;
                copyLastReadSize = read;
                copyBytesRead += read;
                copyReadOperations += 1L;
                output.write(buffer, 0, read);
                output.flush();
                copyLastWriteUnixMs = System.currentTimeMillis();
                copyLastWriteSize = read;
                copyBytesWritten += read;
                copyWriteOperations += 1L;
                if (senderDirection) {
                    bytesSent += read;
                } else {
                    bytesReceived += read;
                }
            }
        }

        void stop(String reason) {
            stopRequested = true;
            closeReason = reason;
            state = "stopped";
            closeQuietly(localClientSocket);
            closeQuietly(serverSocket);
            closeQuietly(transportSocket);
            closeQuietly(transportServerSocket);
            closeQuietly(localSourceSocket);
            closeQuietly(peerSocket);
            Thread current = thread;
            if (current != null) {
                current.interrupt();
            }
            noteStopped(this, false);
        }

        void markStopped(String reason) {
            closeReason = reason;
            state = "stopped";
            noteStopped(this, false);
        }

        void markFailed(Exception ex) {
            if (stopRequested) {
                markStopped("stop_requested");
                return;
            }
            state = "failed";
            closeReason = "exception";
            error = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
            noteStopped(this, true);
        }

        boolean isTerminal() {
            return "stopped".equals(state) || "failed".equals(state);
        }

        boolean receiverReady() {
            return receiverTransportReady() && receiverLocalReady();
        }

        boolean receiverTransportReady() {
            if (!"receiver".equals(role) || isTerminal()) {
                return false;
            }
            if (transportPort <= 0) {
                return true;
            }
            return transportServerBoundUnixMs > 0L
                    && transportServerSocket != null
                    && !transportServerSocket.isClosed();
        }

        boolean receiverLocalReady() {
            if (!"receiver".equals(role) || isTerminal()) {
                return false;
            }
            return localReceiverBoundUnixMs > 0L
                    && serverSocket != null
                    && !serverSocket.isClosed();
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("schema", LANE_SCHEMA);
            json.put("lane_id", laneId);
            json.put("role", role);
            json.put("session_id", sessionId);
            json.put("eye", eye);
            json.put("state", state);
            json.put("host", host);
            json.put("port", port);
            if (transportPort > 0) {
                json.put("transport_host", transportHost);
                json.put("transport_port", transportPort);
                json.put("transport_server_socket_open",
                        transportServerSocket != null && !transportServerSocket.isClosed());
                if (transportServerBindAttemptUnixMs > 0L) {
                    json.put("transport_server_bind_attempt_unix_ms", transportServerBindAttemptUnixMs);
                }
                if (transportServerBoundUnixMs > 0L) {
                    json.put("transport_server_bound_unix_ms", transportServerBoundUnixMs);
                    json.put("transport_server_bound_address", transportServerBoundAddress);
                    json.put("transport_server_bound_port", transportServerBoundPort);
                    if (transportServerBoundInterface.length() > 0) {
                        json.put("transport_server_bound_interface", transportServerBoundInterface);
                    }
                }
                if (transportServerBindError.length() > 0) {
                    json.put("transport_server_bind_error", transportServerBindError);
                }
            }
            json.put("receiver_ready", receiverReady());
            json.put("receiver_transport_ready", receiverTransportReady());
            json.put("receiver_local_ready", receiverLocalReady());
            json.put("local_receiver_socket_open", serverSocket != null && !serverSocket.isClosed());
            if (localReceiverBindAttemptUnixMs > 0L) {
                json.put("local_receiver_bind_attempt_unix_ms", localReceiverBindAttemptUnixMs);
            }
            if (localReceiverBoundUnixMs > 0L) {
                json.put("local_receiver_bound_unix_ms", localReceiverBoundUnixMs);
                json.put("local_receiver_bound_address", localReceiverBoundAddress);
                json.put("local_receiver_bound_port", localReceiverBoundPort);
                if (localReceiverBoundInterface.length() > 0) {
                    json.put("local_receiver_bound_interface", localReceiverBoundInterface);
                }
            }
            if (localReceiverBindError.length() > 0) {
                json.put("local_receiver_bind_error", localReceiverBindError);
            }
            if (peerRoute != null) {
                json.put("peer_route", peerRoute.toJson());
                json.put("transport_peer_modeled", true);
                json.put("peer_socket_authority", peerRoute.socketAuthority());
                json.put("peer_socket_created_from_wifi_direct_network", peerSocketCreatedFromWifiDirectNetwork);
                json.put("peer_socket_bound_to_wifi_direct_network", peerSocketBoundToWifiDirectNetwork);
                json.put("peer_socket_network_route_matches_peer", peerSocketNetworkRouteMatchesPeer);
                json.put("peer_socket_network_wifi_transport", peerSocketNetworkWifiTransport);
                json.put("peer_socket_local_address_same_subnet", peerSocketLocalAddressSameSubnet);
                json.put("peer_socket_network_direct_p2p_ready", hasDirectP2pNetworkBinding(this));
                json.put("peer_socket_local_interface_is_p2p",
                        RemoteCameraDirectP2pSocketAuthority.isP2pInterfaceName(
                                peerSocketLocalInterface));
                json.put("peer_socket_local_address_direct_p2p_ready",
                        hasDirectP2pLocalAddressBinding(this));
                json.put("peer_socket_direct_p2p_ready",
                        hasDirectP2pNetworkBinding(this) || hasDirectP2pLocalAddressBinding(this));
                json.put("peer_socket_wifi_direct_bind_required", peerSocketWifiDirectBindRequired);
                json.put("peer_socket_wifi_direct_bind_attempts", peerSocketWifiDirectBindAttempts);
                if (peerSocketNetworkInterface.length() > 0) {
                    json.put("peer_socket_network_interface", peerSocketNetworkInterface);
                }
                if (peerSocketNetworkSelection.length() > 0) {
                    json.put("peer_socket_network_selection", peerSocketNetworkSelection);
                }
                if (peerSocketLocalInterface.length() > 0) {
                    json.put("peer_socket_local_interface", peerSocketLocalInterface);
                }
                if (peerSocketBoundLocalAddress.length() > 0) {
                    json.put("peer_socket_bound_local_address", peerSocketBoundLocalAddress);
                }
                if (peerSocketBindLocalAddressError.length() > 0) {
                    json.put("peer_socket_bind_local_address_error", peerSocketBindLocalAddressError);
                }
                if (peerSocketLocalAddressError.length() > 0) {
                    json.put("peer_socket_local_address_error", peerSocketLocalAddressError);
                }
            } else {
                json.put("transport_peer_modeled", false);
            }
            json.put("bytes_sent", bytesSent);
            json.put("bytes_received", bytesReceived);
            json.put("copy_bytes_read", copyBytesRead);
            json.put("copy_bytes_written", copyBytesWritten);
            json.put("copy_read_operations", copyReadOperations);
            json.put("copy_write_operations", copyWriteOperations);
            json.put("copy_last_read_size", copyLastReadSize);
            json.put("copy_last_write_size", copyLastWriteSize);
            json.put("transport_accept_count", transportAcceptCount);
            json.put("local_client_accept_count", localClientAcceptCount);
            json.put("stream_recycle_count", streamRecycleCount);
            if (lastStreamRecycleUnixMs > 0L) {
                json.put("last_stream_recycle_unix_ms", lastStreamRecycleUnixMs);
                json.put(
                        "last_stream_recycle_age_ms",
                        Math.max(0L, System.currentTimeMillis() - lastStreamRecycleUnixMs));
            }
            if (copyFirstReadUnixMs > 0L) {
                json.put("copy_first_read_unix_ms", copyFirstReadUnixMs);
            }
            if (copyLastReadUnixMs > 0L) {
                json.put("copy_last_read_unix_ms", copyLastReadUnixMs);
                json.put("copy_last_read_age_ms", Math.max(0L, System.currentTimeMillis() - copyLastReadUnixMs));
            }
            if (copyLastWriteUnixMs > 0L) {
                json.put("copy_last_write_unix_ms", copyLastWriteUnixMs);
                json.put("copy_last_write_age_ms", Math.max(0L, System.currentTimeMillis() - copyLastWriteUnixMs));
            }
            json.put("started_unix_ms", startedUnixMs);
            json.put("media_payload_plane", "binary-media");
            json.put("high_rate_json_payload", false);
            if (closeReason.length() > 0) {
                json.put("close_reason", closeReason);
            }
            if (error.length() > 0) {
                json.put("error", error);
            }
            return json;
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable != null ? throwable.getMessage() : "";
        return message != null ? message : "";
    }
}
