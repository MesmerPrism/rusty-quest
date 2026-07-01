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
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int WIFI_DIRECT_PEER_BIND_WAIT_MS = 12000;
    private static final int WIFI_DIRECT_PEER_BIND_RETRY_SLEEP_MS = 250;
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
        JSONArray started = new JSONArray();
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
            started.put(lane.toJson());
        }
        JSONObject result = baseResult(COMMAND_START_RECEIVER, sessionId, "receiver_armed", false);
        result.put("marker", "RUSTY_QUEST_REMOTE_CAMERA_RECEIVER_ARMED");
        result.put("media_socket_runtime_started", started.length() > 0);
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
        List<PeerRoute> routes = parsePeerRoutes(routeOverride(message), transportBindLocalAddress);
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
                        "camera_permission_policy"));
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

    private static List<PeerRoute> parsePeerRoutes(String value, String localBindHost) {
        List<PeerRoute> routes = new ArrayList<>();
        if (value == null || value.trim().length() == 0 || "none".equals(value.trim())) {
            return routes;
        }
        String[] entries = value.split(";");
        for (String entry : entries) {
            String[] parts = entry.trim().split("\\|");
            if (parts.length == 5) {
                try {
                    int port = Integer.parseInt(parts[4].trim());
                    if (port > 0 && port <= 65535) {
                        routes.add(new PeerRoute(
                                parts[0].trim(),
                                parts[1].trim(),
                                parts[2].trim(),
                                parts[3].trim(),
                                port,
                                localBindHost));
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
                                localBindHost));
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
        boolean wifiDirectPeerRequired = isLikelyWifiDirectPeerAddress(peerAddress);
        lane.peerSocketWifiDirectBindRequired = wifiDirectPeerRequired;
        long deadlineMs = System.currentTimeMillis() + WIFI_DIRECT_PEER_BIND_WAIT_MS;
        String lastSelection = "";
        do {
            lane.peerSocketWifiDirectBindAttempts++;
            resetPeerSocketBindingDiagnostics(lane);
            Socket socket = createTransportPeerSocketOnce(context, peerRoute, peerAddress, lane);
            boolean wifiDirectSocketReady = lane.peerSocketBoundToWifiDirectNetwork
                    || lane.peerSocketBoundLocalAddress.length() > 0;
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
            bindSocketToExplicitLocalAddress(socket, peerRoute.localBindHost, peerAddress, lane);
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
            RuntimeLane lane) {
        try {
            InetAddress localAddress = InetAddress.getByName(localBindHost);
            socket.bind(new InetSocketAddress(localAddress, 0));
            lane.peerSocketLocalInterface = "explicit_local_bind_address";
            lane.peerSocketBoundLocalAddress = localAddress.getHostAddress();
            lane.peerSocketLocalAddressSameSubnet = sameIpv4Slash24(localAddress, peerAddress);
            lane.peerSocketNetworkSelection = "explicit_local_bind_address";
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

    private static boolean isLikelyWifiDirectPeerAddress(InetAddress peerAddress) {
        if (!(peerAddress instanceof Inet4Address)) {
            return false;
        }
        byte[] address = peerAddress.getAddress();
        int first = address[0] & 0xff;
        int second = address[1] & 0xff;
        int third = address[2] & 0xff;
        return first == 192 && second == 168 && (third == 137 || third == 49);
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
                lane.peerSocketNetworkSelection = "p2p_interface";
                return network;
            }
        }
        lane.peerSocketNetworkSelection = "p2p_network_not_found";
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
            lane.peerSocketBoundLocalAddress = localAddress.getHostAddress();
        } catch (Exception ex) {
            lane.peerSocketBindLocalAddressError = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
        }
    }

    private static InetAddress findWifiDirectLocalAddress(InetAddress peerAddress, RuntimeLane lane) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                String name = networkInterface.getName();
                boolean p2pInterface = name != null && name.toLowerCase(Locale.US).contains("p2p");
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    boolean sameSubnet = sameIpv4Slash24(address, peerAddress);
                    if (p2pInterface || sameSubnet) {
                        lane.peerSocketLocalInterface = name == null ? "" : name;
                        lane.peerSocketLocalAddressSameSubnet = sameSubnet;
                        return address;
                    }
                }
            }
        } catch (Exception ex) {
            lane.peerSocketLocalAddressError = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
        }
        return null;
    }

    private static boolean sameIpv4Slash24(InetAddress left, InetAddress right) {
        if (!(left instanceof Inet4Address) || !(right instanceof Inet4Address)) {
            return false;
        }
        byte[] leftBytes = left.getAddress();
        byte[] rightBytes = right.getAddress();
        return leftBytes[0] == rightBytes[0]
                && leftBytes[1] == rightBytes[1]
                && leftBytes[2] == rightBytes[2];
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

        PeerRoute(
                String laneId,
                String eye,
                String routeKind,
                String connectHost,
                int connectPort,
                String localBindHost) {
            this.laneId = laneId;
            this.eye = eye;
            this.routeKind = routeKind;
            this.connectHost = connectHost;
            this.connectPort = connectPort;
            this.localBindHost = localBindHost == null ? "" : localBindHost.trim();
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("lane_id", laneId);
            json.put("eye", eye);
            json.put("route_kind", routeKind);
            json.put("connect_host", connectHost);
            json.put("connect_port", connectPort);
            if (localBindHost.length() > 0) {
                json.put("local_bind_host", localBindHost);
            }
            json.put("media_payload_plane", "binary-media");
            json.put("high_rate_json_payload", false);
            return json;
        }
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
        volatile boolean stopRequested;
        volatile boolean terminalCounted;
        volatile Thread thread;
        volatile ServerSocket serverSocket;
        volatile ServerSocket transportServerSocket;
        volatile Socket localClientSocket;
        volatile Socket transportSocket;
        volatile Socket localSourceSocket;
        volatile Socket peerSocket;
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
                state = "binding_local_receiver";
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getByName(host), port));
                serverSocket = server;
                if (transportPort > 0) {
                    state = "binding_transport_receiver";
                    transportServer = new ServerSocket();
                    transportServer.setReuseAddress(true);
                    transportServer.bind(new InetSocketAddress(InetAddress.getByName(transportHost), transportPort));
                    transportServerSocket = transportServer;
                }
                state = "waiting_for_local_client";
                client = server.accept();
                localClientSocket = client;
                client.setTcpNoDelay(true);
                if (transportServer == null) {
                    state = "local_client_connected_waiting_for_remote_media";
                    while (!stopRequested && !client.isClosed()) {
                        Thread.sleep(100L);
                    }
                    markStopped("stop_requested");
                    return;
                }
                state = "local_client_connected_waiting_for_transport_peer";
                remote = transportServer.accept();
                transportSocket = remote;
                remote.setTcpNoDelay(true);
                state = "transport_peer_connected_streaming_to_local_client";
                copyStream(remote.getInputStream(), client.getOutputStream(), false);
                if (!stopRequested) {
                    markStopped("transport_peer_closed");
                } else {
                    markStopped("stop_requested");
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
                peer.connect(new InetSocketAddress(peerRoute.connectHost, peerRoute.connectPort), CONNECT_TIMEOUT_MS);
                peerSocket = peer;
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
                output.write(buffer, 0, read);
                output.flush();
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
            }
            if (peerRoute != null) {
                json.put("peer_route", peerRoute.toJson());
                json.put("transport_peer_modeled", true);
                json.put("peer_socket_created_from_wifi_direct_network", peerSocketCreatedFromWifiDirectNetwork);
                json.put("peer_socket_bound_to_wifi_direct_network", peerSocketBoundToWifiDirectNetwork);
                json.put("peer_socket_network_route_matches_peer", peerSocketNetworkRouteMatchesPeer);
                json.put("peer_socket_network_wifi_transport", peerSocketNetworkWifiTransport);
                json.put("peer_socket_local_address_same_subnet", peerSocketLocalAddressSameSubnet);
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
