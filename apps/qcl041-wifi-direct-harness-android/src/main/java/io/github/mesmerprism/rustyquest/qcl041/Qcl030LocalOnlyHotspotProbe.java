package io.github.mesmerprism.rustyquest.qcl041;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class Qcl030LocalOnlyHotspotProbe {
    static final String SCHEMA = "rusty.quest.qcl030.local_only_hotspot_probe.v1";
    static final String PROBE_ID = "QCL-030";

    private final Context context;
    private final Qcl041ProbeConfig config;
    private final Qcl041WifiDirectLifecycle.StatusListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long startedMs = SystemClock.elapsedRealtime();
    private final JSONObject diagnostics = new JSONObject();

    private WifiManager.LocalOnlyHotspotReservation reservation;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback clientNetworkCallback;
    private Network clientNetwork;
    private Thread udpReceiverThread;
    private Thread tcpReceiverThread;
    private volatile boolean socketReceiversRunning;
    private volatile boolean ownerUdpReceiverStarted;
    private volatile boolean ownerTcpReceiverStarted;
    private volatile int ownerUdpPackets;
    private volatile long ownerUdpBytes;
    private volatile String ownerUdpLastSender = "";
    private volatile String ownerUdpReceiverError = "";
    private volatile int ownerTcpAccepts;
    private volatile long ownerTcpBytes;
    private volatile int ownerTcpAckBytes;
    private volatile String ownerTcpReceiverError = "";
    private volatile boolean clientJoinRequested;
    private volatile boolean clientNetworkAvailable;
    private volatile boolean clientCallbackUnregistered;
    private volatile boolean clientSocketAttempted;
    private volatile int clientUdpPackets;
    private volatile long clientUdpSentBytes;
    private volatile long clientTcpSentBytes;
    private volatile int clientTcpAckBytes;
    private volatile String clientSocketError = "";
    private volatile String clientLinkProperties = "";
    private volatile String clientNetworkCapabilities = "";
    private volatile String clientActiveWifiSsidMatchStatus = "not_checked";
    private volatile boolean clientActiveWifiSsidPresent;
    private String status = "started";
    private String blockedReason = "";
    private boolean hotspotStarted;
    private boolean cleanupCompleted;
    private boolean finished;
    private String ssid = "";
    private String passphrase = "";
    private String securityType = "";

    Qcl030LocalOnlyHotspotProbe(
            Context context,
            Qcl041ProbeConfig config,
            Qcl041WifiDirectLifecycle.StatusListener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
    }

    void start() {
        updateStatus("starting QCL-030 LocalOnlyHotspot probe");
        diagnostic("qcl030_local_only_hotspot", "enabled", true);
        diagnostic("qcl030_local_only_hotspot", "role", config.qcl030LocalOnlyHotspotRole);
        diagnostic("qcl030_local_only_hotspot", "hold_ms", holdMs());
        diagnostic("qcl030_local_only_hotspot", "socket_port", config.qcl030LocalOnlyHotspotPort);
        diagnostic("qcl030_local_only_hotspot", "tcp_port", tcpPort());
        diagnostic("qcl030_local_only_hotspot", "socket_bytes", socketBytes());
        diagnostic("qcl030_local_only_hotspot", "socket_timeout_ms", socketTimeoutMs());
        diagnostic("qcl030_local_only_hotspot", "topology_owner", "quest_local_only_hotspot");
        diagnostic(
                "qcl030_local_only_hotspot",
                "owner_pass_condition",
                "hotspot_started_and_reservation_closed_cleanly");
        diagnostic(
                "qcl030_client_join",
                "client_pass_condition",
                "client_joined_and_sent_socket_bytes");
        diagnostic(
                "qcl030_client_join",
                "client_join_mode",
                config.qcl030LocalOnlyHotspotClientJoinMode);
        diagnostic(
                "qcl030_client_join",
                "require_active_wifi_ssid_match",
                config.qcl030LocalOnlyHotspotRequireSsidMatch);
        if (Qcl041ProbeConfig.QCL030_ROLE_HOTSPOT_OWNER.equals(config.qcl030LocalOnlyHotspotRole)) {
            startOwner();
            return;
        }
        if (Qcl041ProbeConfig.QCL030_ROLE_HOTSPOT_CLIENT.equals(config.qcl030LocalOnlyHotspotRole)) {
            startClient();
            return;
        }
        finishBlocked("unsupported_role_" + config.qcl030LocalOnlyHotspotRole);
    }

    void stop() {
        finishBlocked("stopped_by_service");
    }

    static void writePermissionBlocked(Context context, Qcl041ProbeConfig config, String evidence) {
        Qcl030LocalOnlyHotspotProbe probe = new Qcl030LocalOnlyHotspotProbe(
                context,
                config,
                null);
        probe.diagnostic("permissions", "runtime_permission_blocked", evidence);
        probe.finishBlocked("runtime_permission_missing");
    }

    private void startOwner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            finishBlocked("local_only_hotspot_requires_api_26");
            return;
        }
        boolean wifiFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
        diagnostic("qcl030_local_only_hotspot", "package_feature_wifi", wifiFeature);
        if (!wifiFeature) {
            finishBlocked("wifi_feature_missing");
            return;
        }
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        diagnostic("qcl030_local_only_hotspot", "wifi_manager_available", wifiManager != null);
        if (wifiManager == null) {
            finishBlocked("wifi_manager_unavailable");
            return;
        }
        try {
            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                    reservation = value;
                    hotspotStarted = true;
                    status = "holding";
                    diagnostic("qcl030_local_only_hotspot", "on_started", true);
                    recordReservation(value);
                    startOwnerSocketReceivers();
                    writeQuietly();
                    updateStatus("QCL-030 LocalOnlyHotspot holding");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            finishPass("hold_elapsed");
                        }
                    }, holdMs());
                }

                @Override
                public void onStopped() {
                    diagnostic("qcl030_local_only_hotspot", "on_stopped_callback", true);
                    writeQuietly();
                }

                @Override
                public void onFailed(int reason) {
                    diagnostic("qcl030_local_only_hotspot", "on_failed_reason", reason);
                    diagnostic(
                            "qcl030_local_only_hotspot",
                            "on_failed_reason_label",
                            localOnlyHotspotFailureLabel(reason));
                    finishBlocked("start_failed_" + localOnlyHotspotFailureLabel(reason));
                }
            }, handler);
            diagnostic("qcl030_local_only_hotspot", "startLocalOnlyHotspot_invoked", true);
            writeQuietly();
        } catch (SecurityException ex) {
            finishBlocked("security_exception_" + sanitizeReason(ex.getMessage()));
        } catch (Exception ex) {
            finishBlocked(ex.getClass().getSimpleName() + "_" + sanitizeReason(ex.getMessage()));
        }
    }

    private void startClient() {
        if (Qcl041ProbeConfig.QCL030_CLIENT_JOIN_MODE_ACTIVE_WIFI.equals(
                config.qcl030LocalOnlyHotspotClientJoinMode)) {
            startClientActiveWifiSocketMatrix();
            return;
        }
        if (!Qcl041ProbeConfig.QCL030_CLIENT_JOIN_MODE_NETWORK_SPECIFIER.equals(
                config.qcl030LocalOnlyHotspotClientJoinMode)) {
            finishBlocked("unsupported_client_join_mode_"
                    + sanitizeReason(config.qcl030LocalOnlyHotspotClientJoinMode));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finishBlocked("wifi_network_specifier_requires_api_29");
            return;
        }
        if (config.qcl030LocalOnlyHotspotSsid.isEmpty()) {
            finishBlocked("client_ssid_missing");
            return;
        }
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        diagnostic("qcl030_client_join", "connectivity_manager_available", connectivityManager != null);
        if (connectivityManager == null) {
            finishBlocked("connectivity_manager_unavailable");
            return;
        }
        try {
            WifiNetworkSpecifier.Builder specifierBuilder = new WifiNetworkSpecifier.Builder()
                    .setSsid(config.qcl030LocalOnlyHotspotSsid);
            if (!config.qcl030LocalOnlyHotspotPassphrase.isEmpty()) {
                specifierBuilder.setWpa2Passphrase(config.qcl030LocalOnlyHotspotPassphrase);
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifierBuilder.build())
                    .build();
            clientNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(final Network network) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            handleClientNetworkAvailable(network);
                        }
                    });
                }

                @Override
                public void onUnavailable() {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!finished) {
                                finishBlocked("client_network_unavailable");
                            }
                        }
                    });
                }

                @Override
                public void onLost(Network network) {
                    diagnostic("qcl030_client_join", "network_lost", true);
                    writeQuietly();
                }
            };
            clientJoinRequested = true;
            diagnostic("qcl030_client_join", "WifiNetworkSpecifier", true);
            diagnostic("qcl030_client_join", "requestNetwork", true);
            diagnostic("qcl030_client_join", "NetworkCallback", true);
            diagnostic("qcl030_client_join", "owner_host", config.qcl030LocalOnlyHotspotOwnerHost);
            status = "joining";
            connectivityManager.requestNetwork(request, clientNetworkCallback, socketTimeoutMs());
            writeQuietly();
            updateStatus("QCL-030 LocalOnlyHotspot client joining");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!finished && !clientNetworkAvailable) {
                        finishBlocked("client_join_timeout");
                    }
                }
            }, socketTimeoutMs() + 1_000L);
        } catch (SecurityException ex) {
            finishBlocked("client_security_exception_" + sanitizeReason(ex.getMessage()));
        } catch (Exception ex) {
            finishBlocked("client_" + ex.getClass().getSimpleName() + "_" + sanitizeReason(ex.getMessage()));
        }
    }

    private void startClientActiveWifiSocketMatrix() {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        diagnostic("qcl030_client_join", "connectivity_manager_available", connectivityManager != null);
        diagnostic("qcl030_client_join", "active_wifi", true);
        diagnostic("qcl030_client_join", "requestNetwork", false);
        diagnostic("qcl030_client_join", "WifiNetworkSpecifier", false);
        diagnostic("qcl030_client_join", "owner_host", config.qcl030LocalOnlyHotspotOwnerHost);
        if (connectivityManager == null) {
            finishBlocked("connectivity_manager_unavailable");
            return;
        }
        try {
            clientJoinRequested = true;
            status = "active_wifi_socket_matrix";
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) {
                finishBlocked("client_active_wifi_network_unavailable");
                return;
            }
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (capabilities != null) {
                    clientNetworkCapabilities = capabilities.toString();
                }
                finishBlocked("client_active_network_not_wifi");
                return;
            }
            recordActiveWifiIdentity();
            if (config.qcl030LocalOnlyHotspotRequireSsidMatch
                    && !"matched".equals(clientActiveWifiSsidMatchStatus)) {
                finishBlocked("client_active_wifi_ssid_" + clientActiveWifiSsidMatchStatus);
                return;
            }
            diagnostic("qcl030_client_join", "active_wifi_network_selected", true);
            handleClientNetworkAvailable(network);
        } catch (SecurityException ex) {
            finishBlocked("client_active_wifi_security_exception_" + sanitizeReason(ex.getMessage()));
        } catch (Exception ex) {
            finishBlocked("client_active_wifi_"
                    + ex.getClass().getSimpleName() + "_" + sanitizeReason(ex.getMessage()));
        }
    }

    private void handleClientNetworkAvailable(final Network network) {
        if (finished || clientSocketAttempted) {
            return;
        }
        clientNetwork = network;
        clientNetworkAvailable = true;
        status = "socket_matrix";
        recordClientNetwork(network);
        writeQuietly();
        updateStatus("QCL-030 LocalOnlyHotspot client socket matrix");
        Thread clientThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runClientSocketMatrix(network);
            }
        }, "qcl030-client-socket-matrix");
        clientThread.start();
    }

    private void runClientSocketMatrix(Network network) {
        clientSocketAttempted = true;
        try {
            sendClientUdp(network);
        } catch (Exception ex) {
            clientSocketError = appendError(clientSocketError, "udp_" + ex.getClass().getSimpleName()
                    + "_" + sanitizeReason(ex.getMessage()));
        }
        try {
            sendClientTcp(network);
        } catch (Exception ex) {
            clientSocketError = appendError(clientSocketError, "tcp_" + ex.getClass().getSimpleName()
                    + "_" + sanitizeReason(ex.getMessage()));
        }
        writeQuietly();
        if (clientUdpSentBytes > 0 || clientTcpSentBytes > 0) {
            finishPass("client_joined_and_sent_socket_bytes");
        } else if (clientSocketError.isEmpty()) {
            finishBlocked("client_socket_send_failed");
        } else {
            finishBlocked("client_socket_send_failed_" + sanitizeReason(clientSocketError));
        }
    }

    private void sendClientUdp(Network network) throws IOException {
        InetAddress address = InetAddress.getByName(config.qcl030LocalOnlyHotspotOwnerHost);
        try (DatagramSocket socket = new DatagramSocket()) {
            network.bindSocket(socket);
            socket.setSoTimeout(socketTimeoutMs());
            long remaining = socketBytes();
            int sequence = 0;
            while (remaining > 0) {
                int size = (int) Math.min(1_200L, remaining);
                byte[] payload = payloadBytes("UDP", sequence, size);
                DatagramPacket packet = new DatagramPacket(payload, payload.length, address, config.qcl030LocalOnlyHotspotPort);
                socket.send(packet);
                clientUdpPackets++;
                clientUdpSentBytes += payload.length;
                remaining -= payload.length;
                sequence++;
            }
        }
    }

    private void sendClientTcp(Network network) throws IOException {
        try (Socket socket = new Socket()) {
            network.bindSocket(socket);
            socket.connect(new InetSocketAddress(config.qcl030LocalOnlyHotspotOwnerHost, tcpPort()), socketTimeoutMs());
            socket.setSoTimeout(socketTimeoutMs());
            OutputStream output = socket.getOutputStream();
            long remaining = socketBytes();
            int sequence = 0;
            while (remaining > 0) {
                int size = (int) Math.min(4_096L, remaining);
                byte[] payload = payloadBytes("TCP", sequence, size);
                output.write(payload);
                clientTcpSentBytes += payload.length;
                remaining -= payload.length;
                sequence++;
            }
            output.flush();
            socket.shutdownOutput();
            InputStream input = socket.getInputStream();
            byte[] ack = new byte[256];
            int read = input.read(ack);
            if (read > 0) {
                clientTcpAckBytes += read;
            }
        }
    }

    private void startOwnerSocketReceivers() {
        socketReceiversRunning = true;
        udpReceiverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runOwnerUdpReceiver();
            }
        }, "qcl030-owner-udp-receiver");
        tcpReceiverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runOwnerTcpReceiver();
            }
        }, "qcl030-owner-tcp-receiver");
        udpReceiverThread.start();
        tcpReceiverThread.start();
    }

    private void runOwnerUdpReceiver() {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", config.qcl030LocalOnlyHotspotPort));
            socket.setSoTimeout(1_000);
            ownerUdpReceiverStarted = true;
            writeQuietly();
            byte[] buffer = new byte[2_048];
            while (socketReceiversRunning && ownerUdpBytes < socketBytes()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    ownerUdpPackets++;
                    ownerUdpBytes += packet.getLength();
                    if (packet.getAddress() != null) {
                        ownerUdpLastSender = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                    }
                    writeQuietly();
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception ex) {
            ownerUdpReceiverError = ex.getClass().getSimpleName() + "_" + sanitizeReason(ex.getMessage());
            writeQuietly();
        }
    }

    private void runOwnerTcpReceiver() {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", tcpPort()));
            server.setSoTimeout(1_000);
            ownerTcpReceiverStarted = true;
            writeQuietly();
            while (socketReceiversRunning && ownerTcpBytes < socketBytes()) {
                try (Socket socket = server.accept()) {
                    ownerTcpAccepts++;
                    socket.setSoTimeout(socketTimeoutMs());
                    InputStream input = socket.getInputStream();
                    byte[] buffer = new byte[4_096];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        ownerTcpBytes += read;
                        if (ownerTcpBytes >= socketBytes()) {
                            break;
                        }
                    }
                    byte[] ack = ("QCL030-TCP-ACK;bytes=" + ownerTcpBytes + ";run_id=" + config.runId)
                            .getBytes(StandardCharsets.UTF_8);
                    OutputStream output = socket.getOutputStream();
                    output.write(ack);
                    output.flush();
                    ownerTcpAckBytes += ack.length;
                    writeQuietly();
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception ex) {
            ownerTcpReceiverError = ex.getClass().getSimpleName() + "_" + sanitizeReason(ex.getMessage());
            writeQuietly();
        }
    }

    private void stopOwnerSocketReceivers() {
        socketReceiversRunning = false;
        joinReceiver(udpReceiverThread);
        joinReceiver(tcpReceiverThread);
    }

    private void joinReceiver(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1_500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordReservation(WifiManager.LocalOnlyHotspotReservation value) {
        diagnostic("qcl030_local_only_hotspot", "reservation_available", value != null);
        if (value == null) {
            return;
        }
        Object wifiConfig = invokeNoArg(value, "getWifiConfiguration");
        Object softApConfig = invokeNoArg(value, "getSoftApConfiguration");
        diagnostic("qcl030_local_only_hotspot", "wifi_configuration_available", objectAvailable(wifiConfig));
        diagnostic("qcl030_local_only_hotspot", "soft_ap_configuration_available", objectAvailable(softApConfig));
        if (objectAvailable(softApConfig)) {
            ssid = readString(softApConfig, "getSsid", "SSID");
            passphrase = readString(softApConfig, "getPassphrase", "preSharedKey");
            securityType = readValue(softApConfig, "getSecurityType", "securityType");
        }
        if (ssid.isEmpty() && objectAvailable(wifiConfig)) {
            ssid = unquote(readString(wifiConfig, "getSSID", "SSID"));
        }
        if (passphrase.isEmpty() && objectAvailable(wifiConfig)) {
            passphrase = unquote(readString(wifiConfig, "getPreSharedKey", "preSharedKey"));
        }
        diagnostic("qcl030_local_only_hotspot", "ssid", ssid);
        diagnostic("qcl030_local_only_hotspot", "passphrase", passphrase);
        diagnostic("qcl030_local_only_hotspot", "credential_sensitive", true);
        diagnostic("qcl030_local_only_hotspot", "security_type", securityType);
    }

    private void recordClientNetwork(Network network) {
        try {
            LinkProperties linkProperties = connectivityManager == null
                    ? null
                    : connectivityManager.getLinkProperties(network);
            NetworkCapabilities capabilities = connectivityManager == null
                    ? null
                    : connectivityManager.getNetworkCapabilities(network);
            clientLinkProperties = linkProperties == null ? "" : linkProperties.toString();
            clientNetworkCapabilities = capabilities == null ? "" : capabilities.toString();
            diagnostic("qcl030_client_join", "link_properties", clientLinkProperties);
            diagnostic("qcl030_client_join", "network_capabilities", clientNetworkCapabilities);
            diagnostic("qcl030_client_join", "TRANSPORT_WIFI", true);
        } catch (Exception ex) {
            diagnostic(
                    "qcl030_client_join",
                    "network_record_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void recordActiveWifiIdentity() {
        clientActiveWifiSsidMatchStatus = "unknown";
        clientActiveWifiSsidPresent = false;
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            diagnostic("qcl030_client_join", "active_wifi_manager_available", wifiManager != null);
            if (wifiManager == null) {
                return;
            }
            WifiInfo connectionInfo = wifiManager.getConnectionInfo();
            diagnostic("qcl030_client_join", "active_wifi_connection_info_available", connectionInfo != null);
            if (connectionInfo == null) {
                return;
            }
            String activeSsid = normalizeSsid(connectionInfo.getSSID());
            clientActiveWifiSsidPresent = !activeSsid.isEmpty();
            if (config.qcl030LocalOnlyHotspotSsid.isEmpty() || activeSsid.isEmpty()) {
                clientActiveWifiSsidMatchStatus = "unknown";
            } else if (config.qcl030LocalOnlyHotspotSsid.equals(activeSsid)) {
                clientActiveWifiSsidMatchStatus = "matched";
            } else {
                clientActiveWifiSsidMatchStatus = "mismatch";
            }
            diagnostic("qcl030_client_join", "active_wifi_ssid_present", clientActiveWifiSsidPresent);
            diagnostic("qcl030_client_join", "active_wifi_ssid_match_status", clientActiveWifiSsidMatchStatus);
        } catch (SecurityException ex) {
            clientActiveWifiSsidMatchStatus = "security_exception";
            diagnostic(
                    "qcl030_client_join",
                    "active_wifi_ssid_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (Exception ex) {
            clientActiveWifiSsidMatchStatus = "error";
            diagnostic(
                    "qcl030_client_join",
                    "active_wifi_ssid_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void finishPass(String reason) {
        if (isOwnerRole() && !hotspotStarted) {
            finishBlocked("hotspot_not_started");
            return;
        }
        finish("pass", "", reason);
    }

    private void finishBlocked(String reason) {
        finish("blocked", reason, reason);
    }

    private void finish(String nextStatus, String reason, String cleanupReason) {
        if (finished) {
            return;
        }
        finished = true;
        status = nextStatus;
        blockedReason = reason == null ? "" : reason;
        stopOwnerSocketReceivers();
        closeReservation(cleanupReason);
        unregisterClientNetworkCallback(cleanupReason);
        writeQuietly();
        updateStatus("QCL-030 lifecycle complete");
    }

    private void closeReservation(String reason) {
        diagnostic("cleanup", "reason", reason);
        if (reservation == null) {
            cleanupCompleted = !isOwnerRole();
            diagnostic("cleanup", "reservation_close_skipped", "reservation_not_started");
            return;
        }
        try {
            reservation.close();
            cleanupCompleted = true;
            diagnostic("cleanup", "reservation_closed", true);
        } catch (Exception ex) {
            cleanupCompleted = false;
            diagnostic(
                    "cleanup",
                    "reservation_close_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void unregisterClientNetworkCallback(String reason) {
        diagnostic("cleanup", "client_network_callback_reason", reason);
        if (connectivityManager == null || clientNetworkCallback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(clientNetworkCallback);
            clientCallbackUnregistered = true;
            cleanupCompleted = cleanupCompleted || !isOwnerRole();
            diagnostic("cleanup", "client_network_callback_unregistered", true);
        } catch (Exception ex) {
            diagnostic(
                    "cleanup",
                    "client_network_callback_unregister_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void diagnostic(String section, String key, Object value) {
        try {
            JSONObject group = diagnostics.optJSONObject(section);
            if (group == null) {
                group = new JSONObject();
                diagnostics.put(section, group);
            }
            group.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException ignored) {
        }
    }

    private File writeQuietly() {
        try {
            return write();
        } catch (Exception ignored) {
            return null;
        }
    }

    private File write() throws JSONException, IOException {
        String json = toJson().toString(2) + "\n";
        File internalRoot = new File(context.getFilesDir(), "qcl030");
        writeToDirectory(internalRoot, json);
        File externalRoot = context.getExternalFilesDir(null);
        if (externalRoot != null) {
            writeToDirectory(new File(externalRoot, "qcl030"), json);
        }
        return new File(internalRoot, "latest.json");
    }

    private JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("$schema", SCHEMA);
        root.put("schema_version", 1);
        root.put("probe_id", PROBE_ID);
        root.put("run_id", config.runId);
        root.put("status", status);
        root.put("blocked_reason", blockedReason);
        root.put("observed_at_utc", nowUtc());
        root.put("elapsed_ms", SystemClock.elapsedRealtime() - startedMs);
        root.put("harness", harnessJson());
        root.put("topology", topologyJson());
        root.put("device", deviceJson());
        root.put("local_only_hotspot", hotspotJson());
        root.put("socket_matrix", socketMatrixJson());
        root.put("cleanup", cleanupJson());
        root.put("diagnostics", diagnostics);
        return root;
    }

    private JSONObject harnessJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("harness_id", "rusty-quest-qcl030-local-only-hotspot-android-probe");
        value.put("owner", "Rusty Quest QCL-030 LocalOnlyHotspot Android probe");
        value.put("route", Qcl041ProbeConfig.ROUTE_QCL030_LOCAL_ONLY_HOTSPOT);
        value.put("writes_live_source_artifact", true);
        return value;
    }

    private JSONObject topologyJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("owner", "quest_local_only_hotspot");
        value.put("network_provider", "quest_local_only_hotspot");
        value.put("external_wifi_provider_required", false);
        value.put(
                "endpoint_direction",
                isClientRole() ? "quest_client_joined_local_ap" : "quest_hosted_local_ap");
        value.put("requires_android_network_binding", true);
        value.put("receiver_observed_bytes_required_before_media", true);
        return value;
    }

    private JSONObject deviceJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("model", config.deviceModel);
        value.put("serial", config.deviceSerial);
        value.put("adb_serial", config.deviceSerial);
        value.put("role", config.qcl030LocalOnlyHotspotRole);
        return value;
    }

    private JSONObject hotspotJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("started", hotspotStarted);
        value.put("hold_ms", holdMs());
        value.put("port", config.qcl030LocalOnlyHotspotPort);
        value.put("tcp_port", tcpPort());
        value.put("owner_host", config.qcl030LocalOnlyHotspotOwnerHost);
        value.put("ssid", effectiveSsid());
        value.put("passphrase", effectivePassphrase());
        value.put("credential_sensitive", true);
        value.put("security_type", securityType);
        value.put("client_join_and_socket_matrix_pending", !receiverObservedBytes());
        return value;
    }

    private JSONObject socketMatrixJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("role", config.qcl030LocalOnlyHotspotRole);
        value.put("client_join_mode", config.qcl030LocalOnlyHotspotClientJoinMode);
        value.put("require_active_wifi_ssid_match", config.qcl030LocalOnlyHotspotRequireSsidMatch);
        value.put("udp_port", config.qcl030LocalOnlyHotspotPort);
        value.put("tcp_port", tcpPort());
        value.put("owner_host", config.qcl030LocalOnlyHotspotOwnerHost);
        value.put("socket_bytes_requested", socketBytes());
        value.put("socket_timeout_ms", socketTimeoutMs());
        value.put("owner_udp_receiver_started", ownerUdpReceiverStarted);
        value.put("owner_udp_packets", ownerUdpPackets);
        value.put("owner_udp_bytes", ownerUdpBytes);
        value.put("owner_udp_last_sender", ownerUdpLastSender);
        value.put("owner_udp_receiver_error", ownerUdpReceiverError);
        value.put("owner_tcp_receiver_started", ownerTcpReceiverStarted);
        value.put("owner_tcp_accepts", ownerTcpAccepts);
        value.put("owner_tcp_bytes", ownerTcpBytes);
        value.put("owner_tcp_ack_bytes", ownerTcpAckBytes);
        value.put("owner_tcp_receiver_error", ownerTcpReceiverError);
        value.put("receiver_observed_bytes", receiverObservedBytes());
        value.put("receiver_observed_bytes_total", ownerUdpBytes + ownerTcpBytes);
        value.put("client_join_requested", clientJoinRequested);
        value.put("client_network_available", clientNetworkAvailable);
        value.put("client_network_bound", clientNetwork != null);
        value.put("client_link_properties", clientLinkProperties);
        value.put("client_network_capabilities", clientNetworkCapabilities);
        value.put("client_active_wifi_ssid_present", clientActiveWifiSsidPresent);
        value.put("client_active_wifi_ssid_match_status", clientActiveWifiSsidMatchStatus);
        value.put("client_socket_attempted", clientSocketAttempted);
        value.put("client_udp_packets", clientUdpPackets);
        value.put("client_udp_sent_bytes", clientUdpSentBytes);
        value.put("client_tcp_sent_bytes", clientTcpSentBytes);
        value.put("client_tcp_ack_bytes", clientTcpAckBytes);
        value.put("client_socket_error", clientSocketError);
        value.put("receiver_observed_bytes_required_before_media", true);
        return value;
    }

    private JSONObject cleanupJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("completed", cleanupCompleted);
        value.put("reservation_closed", isOwnerRole() && cleanupCompleted);
        value.put("client_network_callback_unregistered", clientCallbackUnregistered);
        return value;
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

    private long holdMs() {
        return Math.max(1_000L, Math.min(config.qcl030LocalOnlyHotspotHoldMs, 10 * 60_000));
    }

    private int socketBytes() {
        return Math.max(1_024, Math.min(config.qcl030LocalOnlyHotspotSocketBytes, 4 * 1024 * 1024));
    }

    private int socketTimeoutMs() {
        return Math.max(1_000, Math.min(config.qcl030LocalOnlyHotspotSocketTimeoutMs, 120_000));
    }

    private int tcpPort() {
        return Math.min(65_535, Math.max(1, config.qcl030LocalOnlyHotspotPort + 1));
    }

    private boolean isOwnerRole() {
        return Qcl041ProbeConfig.QCL030_ROLE_HOTSPOT_OWNER.equals(config.qcl030LocalOnlyHotspotRole);
    }

    private boolean isClientRole() {
        return Qcl041ProbeConfig.QCL030_ROLE_HOTSPOT_CLIENT.equals(config.qcl030LocalOnlyHotspotRole);
    }

    private boolean receiverObservedBytes() {
        return ownerUdpBytes > 0 || ownerTcpBytes > 0;
    }

    private String effectiveSsid() {
        return isClientRole() ? config.qcl030LocalOnlyHotspotSsid : ssid;
    }

    private String effectivePassphrase() {
        return isClientRole() ? config.qcl030LocalOnlyHotspotPassphrase : passphrase;
    }

    private void updateStatus(String message) {
        if (listener != null) {
            listener.onStatus(message);
        }
    }

    private byte[] payloadBytes(String protocol, int sequence, int size) {
        int payloadSize = Math.max(1, size);
        byte[] bytes = new byte[payloadSize];
        byte[] prefix = ("QCL030-" + protocol + ";run_id=" + config.runId + ";seq=" + sequence + ";")
                .getBytes(StandardCharsets.UTF_8);
        int prefixLength = Math.min(prefix.length, bytes.length);
        System.arraycopy(prefix, 0, bytes, 0, prefixLength);
        for (int index = prefixLength; index < bytes.length; index++) {
            bytes[index] = (byte) ('a' + (index % 26));
        }
        return bytes;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean objectAvailable(Object value) {
        return value != null && !(value instanceof String && ((String) value).startsWith("error:"));
    }

    private static String readString(Object target, String getter, String fieldName) {
        return unquote(readValue(target, getter, fieldName));
    }

    private static String readValue(Object target, String getter, String fieldName) {
        Object methodValue = invokeNoArg(target, getter);
        if (methodValue != null) {
            return String.valueOf(methodValue);
        }
        try {
            Field field = target.getClass().getField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String unquote(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static String normalizeSsid(String value) {
        String result = unquote(value).trim();
        if ("<unknown ssid>".equalsIgnoreCase(result)) {
            return "";
        }
        return result;
    }

    private static String appendError(String existing, String next) {
        if (next == null || next.isEmpty()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isEmpty()) {
            return next;
        }
        return existing + ";" + next;
    }

    private static String localOnlyHotspotFailureLabel(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
                return "generic";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "no_channel";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "incompatible_mode";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "tethering_disallowed";
            default:
                return "unknown_" + reason;
        }
    }

    private static String sanitizeReason(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
    }

    private static String nowUtc() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
