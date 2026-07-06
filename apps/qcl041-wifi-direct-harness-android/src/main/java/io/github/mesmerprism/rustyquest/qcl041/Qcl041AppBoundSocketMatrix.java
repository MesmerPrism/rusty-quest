package io.github.mesmerprism.rustyquest.qcl041;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

final class Qcl041AppBoundSocketMatrix {
    private static final String SECTION = "q2q_app_bound_socket_matrix";
    private static final int UDP_SENDS_PER_MODE = 4;
    private static final int UDP_RECEIVE_MS = 5_000;
    private static final int DELAYED_UDP_RECEIVE_GRACE_MS = 5_000;
    private static final int TCP_RECEIVE_MS = 12_000;
    private static final String TCP_TUNNEL_MODE = "tcp_tunnel_control_socket";
    private static final String TCP_TUNNEL_STREAM_MODE = "tcp_tunnel_stream_socket";
    private static final String UDP_LOCAL_P2P_BIND_MODE = "udp_local_p2p_bind_echo";
    private static final String TCP_LOCAL_P2P_BIND_MODE = "tcp_local_p2p_bind_socket";
    private static final String TCP_LOCAL_P2P_BIND_STREAM_MODE = "tcp_local_p2p_bind_stream_socket";
    private static final int TCP_TUNNEL_PORT_OFFSET = 2;
    private static final int TCP_TUNNEL_STREAM_PORT_OFFSET = 3;
    private static final int TCP_TUNNEL_BYTES_PER_DIRECTION = 256 * 1024;
    private static final int TCP_TUNNEL_STREAM_MAX_BYTES_PER_DIRECTION = 64 * 1024 * 1024;
    private static final int TCP_TUNNEL_STREAM_CHUNK_BYTES = 16 * 1024;
    private static final int TCP_TUNNEL_BUFFER_BYTES = 4096;
    private static final int TCP_TUNNEL_CLIENT_SALT = 0x41;
    private static final int TCP_TUNNEL_OWNER_SALT = 0x53;

    private final Qcl041LifecycleArtifact artifact;
    private final Qcl041ProbeConfig config;
    private final ConnectivityManager connectivityManager;
    private final Network wifiDirectNetwork;
    private final InetAddress groupOwnerAddress;
    private final InetAddress localAddress;
    private final Qcl041AppNetworkTrace appNetworkTrace;
    private final Map<String, Integer> udpRxByMode = new HashMap<>();
    private final Map<String, Integer> tcpAcceptsByMode = new HashMap<>();
    private volatile long tcpTunnelStreamLastProgressMs = 0L;
    private volatile boolean tcpTunnelStreamWatchdogStop = false;

    Qcl041AppBoundSocketMatrix(
            Qcl041LifecycleArtifact artifact,
            Qcl041ProbeConfig config,
            ConnectivityManager connectivityManager,
            Network wifiDirectNetwork,
            InetAddress groupOwnerAddress,
            InetAddress localAddress,
            Qcl041AppNetworkTrace appNetworkTrace) {
        this.artifact = artifact;
        this.config = config;
        this.connectivityManager = connectivityManager;
        this.wifiDirectNetwork = wifiDirectNetwork;
        this.groupOwnerAddress = groupOwnerAddress;
        this.localAddress = localAddress;
        this.appNetworkTrace = appNetworkTrace;
    }

    void runGroupOwnerReceiver() {
        long started = SystemClock.elapsedRealtime();
        boolean completed = false;
        recordCommon("group_owner_receiver");
        artifact.diagnostic(SECTION, "group_owner_receiver_started", true);
        checkpoint("group_owner_after_common");
        try {
            if (config.isQ2qAppNetworkTraceOnly()) {
                runTraceOnlyGroupOwnerReceiver();
                completed = true;
                return;
            }
            Thread tcpTunnelThread = startTcpTunnelReceiverThread();
            Thread tcpTunnelStreamThread = startTcpTunnelStreamReceiverThread();
            runUdpReceiver();
            checkpoint("group_owner_after_udp_receiver");
            joinThread(tcpTunnelThread, TCP_TUNNEL_MODE + "_receiver");
            checkpoint("group_owner_after_tcp_tunnel_control_join");
            joinThread(tcpTunnelStreamThread, TCP_TUNNEL_STREAM_MODE + "_receiver");
            checkpoint("group_owner_after_tcp_tunnel_stream_join");
            runTcpReceiver();
            completed = true;
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "group_owner_receiver_error", errorText(ex));
        } finally {
            artifact.diagnostic(SECTION, "group_owner_receiver_completed", completed);
            artifact.diagnostic(SECTION, "group_owner_receiver_duration_ms", SystemClock.elapsedRealtime() - started);
            checkpoint("group_owner_receiver_final");
        }
    }

    void runClientSender() {
        long started = SystemClock.elapsedRealtime();
        boolean completed = false;
        recordCommon("client_sender");
        artifact.diagnostic(SECTION, "client_sender_started", true);
        checkpoint("client_after_common");
        try {
            sleepQuietly(1000L);
            if (config.isQ2qAppNetworkTraceOnly()) {
                runTraceOnlyClientSender();
                completed = true;
                return;
            }
            runUdpSendMode("udp_network_bound", false, true);
            checkpoint("client_after_strict_udp_network_bound_echo");
            runTcpTunnelClient();
            checkpoint("client_after_tcp_tunnel_control");
            runTcpTunnelStreamClient();
            checkpoint("client_after_tcp_tunnel_stream");
            runUdpSendMode("udp_wildcard_unbound", false, false);
            runUdpSendMode("udp_source_bound", true, false);
            runUdpSendMode("udp_source_and_network_bound", true, true);
            runNativeUdpSendMode("udp_native_fd_network_bound");
            runUdpProcessBoundMode("udp_process_bound");
            checkpoint("client_after_immediate_udp");
            runDelayedUdpSendModesWithTimeout();
            checkpoint("client_after_delayed_udp");
            sleepQuietly(500L);
            runTcpSendMode("tcp_source_bound", true, false, false);
            runTcpSendMode("tcp_network_bind_socket", false, true, false);
            runTcpSendMode("tcp_network_factory", false, true, true);
            runNativeTcpSendMode("tcp_native_fd_network_bound");
            runTcpProcessBoundMode("tcp_process_bound");
            completed = true;
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "client_sender_error", errorText(ex));
        } finally {
            artifact.diagnostic(SECTION, "client_sender_completed", completed);
            artifact.diagnostic(SECTION, "client_sender_duration_ms", SystemClock.elapsedRealtime() - started);
            checkpoint("client_sender_final");
        }
    }

    private void recordCommon(String role) {
        artifact.diagnostic(SECTION, "enabled", true);
        artifact.diagnostic(SECTION, "role", role);
        artifact.diagnostic(SECTION, "socket_owner_package", Qcl041ProbeConfig.PACKAGE_NAME);
        artifact.diagnostic(SECTION, "socket_owner_uid", Process.myUid());
        artifact.diagnostic(SECTION, "socket_owner_pid", Process.myPid());
        artifact.diagnostic(SECTION, "group_owner_address", addressText(groupOwnerAddress));
        artifact.diagnostic(SECTION, "local_p2p_address", addressText(localAddress));
        artifact.diagnostic(SECTION, "wifi_direct_network_found", wifiDirectNetwork != null);
        artifact.diagnostic(SECTION, "process_bind_experiment_enabled", true);
        artifact.diagnostic(SECTION, "connectivity_manager_found", connectivityManager != null);
        artifact.diagnostic(SECTION, "network_visibility_only", config.isQ2qAppNetworkTraceOnly());
        artifact.diagnostic(SECTION, "app_network_trace_enabled", config.appNetworkTraceRequested());
        artifact.diagnostic(SECTION, "tcp_binding_variants", config.q2qTcpBindingVariants);
        artifact.diagnostic(
                SECTION,
                "tcp_binding_variant_delay_ms",
                Math.max(0, config.q2qTcpBindingVariantDelayMs));
        artifact.diagnostic(SECTION, "delayed_udp_enabled", delayedUdpEnabled());
        artifact.diagnostic(SECTION, "delayed_udp_delay_ms", delayedUdpDelayMs());
        artifact.diagnostic(SECTION, TCP_TUNNEL_STREAM_MODE + "_enabled", tcpTunnelStreamEnabled());
        artifact.diagnostic(
                SECTION,
                TCP_TUNNEL_STREAM_MODE + "_seconds",
                tcpTunnelStreamSeconds());
        artifact.diagnostic(
                SECTION,
                TCP_TUNNEL_STREAM_MODE + "_configured_bytes_per_direction",
                tcpTunnelStreamBytesPerDirection());
        if (wifiDirectNetwork != null) {
            artifact.diagnostic(SECTION, "network", wifiDirectNetwork.toString());
            artifact.diagnostic(SECTION, "network_handle", wifiDirectNetwork.getNetworkHandle());
        }
        recordNetworkVisibility("initial");
        recordWifiP2pNetworkRequestVisibility("initial");
        artifact.diagnostic(SECTION, "matrix_port", config.q2qAppBoundSocketMatrixPort);
        artifact.diagnostic(SECTION, "pass_condition", "receiver_observed_bytes_on_group_owner");
    }

    private void runTraceOnlyGroupOwnerReceiver() {
        artifact.diagnostic(SECTION, "network_visibility_only_group_owner_receiver", true);
        recordTraceTcpVariant("network_visibility_only_group_owner", "start");
        if (config.hasTcpBindingVariants()) {
            Thread tcpReceiverThread = startTraceOnlyTcpReceiverThread();
            Thread tcpStreamReceiverThread = localP2pTcpStreamVariantRequested()
                    ? startTraceOnlyTcpStreamReceiverThread(TCP_LOCAL_P2P_BIND_STREAM_MODE)
                    : null;
            if (localP2pUdpVariantRequested()) {
                runUdpReceiver();
                checkpoint("network_visibility_only_group_owner_after_local_p2p_udp_receiver");
            }
            joinThread(tcpReceiverThread, "network_visibility_only_group_owner_tcp_receiver");
            joinThread(tcpStreamReceiverThread, "network_visibility_only_group_owner_tcp_stream_receiver");
        } else {
            artifact.diagnostic(SECTION, "network_visibility_only_tcp_receiver_skipped", "no_tcp_binding_variants");
            sleepQuietly(traceOnlyHoldMs());
        }
        recordNetworkVisibility("network_visibility_only_group_owner_final");
        recordWifiP2pNetworkRequestVisibility("network_visibility_only_group_owner_final");
        recordTraceTcpVariant("network_visibility_only_group_owner", "final");
    }

    private void runTraceOnlyClientSender() {
        artifact.diagnostic(SECTION, "network_visibility_only_client_sender", true);
        recordTraceTcpVariant("network_visibility_only_client", "start");
        recordNetworkVisibility("network_visibility_only_client_start");
        recordWifiP2pNetworkRequestVisibility("network_visibility_only_client_start");
        if (!config.hasTcpBindingVariants()) {
            artifact.diagnostic(SECTION, "network_visibility_only_tcp_variants_skipped", "no_tcp_binding_variants");
            sleepQuietly(traceOnlyHoldMs());
        } else {
            runRequestedTcpBindingVariants();
        }
        recordNetworkVisibility("network_visibility_only_client_final");
        recordWifiP2pNetworkRequestVisibility("network_visibility_only_client_final");
        recordTraceTcpVariant("network_visibility_only_client", "final");
    }

    private Thread startTraceOnlyTcpReceiverThread() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runTcpReceiver();
            }
        }, "qcl041-trace-only-tcp-receiver");
        thread.setDaemon(true);
        thread.start();
        artifact.diagnostic(SECTION, "network_visibility_only_tcp_receiver_thread_started", true);
        checkpoint("network_visibility_only_group_owner_tcp_receiver_thread_started");
        return thread;
    }

    private Thread startTraceOnlyTcpStreamReceiverThread(final String mode) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runTcpTunnelStreamReceiver(mode);
            }
        }, "qcl041-trace-only-tcp-stream-receiver");
        thread.setDaemon(true);
        thread.start();
        artifact.diagnostic(SECTION, mode + "_receiver_thread_started", true);
        checkpoint("network_visibility_only_group_owner_tcp_stream_receiver_thread_started");
        return thread;
    }

    private void runRequestedTcpBindingVariants() {
        if (localP2pUdpVariantRequested()) {
            runUdpLocalP2pBindMode(UDP_LOCAL_P2P_BIND_MODE);
        }
        if (config.tcpBindingVariantRequested("tcp_socket_factory")) {
            runTcpSendMode("tcp_socket_factory", false, true, true);
        }
        if (config.tcpBindingVariantRequested("tcp_network_bind_socket")) {
            runTcpSendMode("tcp_network_bind_socket", false, true, false);
        }
        if (config.tcpBindingVariantRequested("tcp_network_factory")) {
            runTcpSendMode("tcp_network_factory", false, true, true);
        }
        if (config.tcpBindingVariantRequested("tcp_process_bound")) {
            runTcpProcessBoundMode("tcp_process_bound");
        }
        if (config.tcpBindingVariantRequested("tcp_native_fd_network_bound")) {
            runNativeTcpSendMode("tcp_native_fd_network_bound");
        }
        if (config.tcpBindingVariantRequested("tcp_delayed_network_bind_socket")
                || config.tcpBindingVariantRequested("delayed_network_bind_socket")) {
            runDelayedTcpBindingVariant("tcp_delayed_network_bind_socket", false);
        }
        if (config.tcpBindingVariantRequested("tcp_delayed_network_factory")
                || config.tcpBindingVariantRequested("delayed_network_factory")) {
            runDelayedTcpBindingVariant("tcp_delayed_network_factory", true);
        }
        if (localP2pTcpVariantRequested()) {
            runTcpLocalP2pBindMode(TCP_LOCAL_P2P_BIND_MODE);
        }
        if (localP2pTcpStreamVariantRequested()) {
            runTcpLocalP2pBindStreamMode(TCP_LOCAL_P2P_BIND_STREAM_MODE);
        }
    }

    private void runDelayedTcpBindingVariant(String mode, boolean networkFactory) {
        recordNetworkVisibility(mode + "_before_onavailable_wait");
        recordWifiP2pNetworkRequestVisibility(mode + "_onavailable_wait");
        int delayMs = Math.max(0, config.q2qTcpBindingVariantDelayMs);
        artifact.diagnostic(SECTION, mode + "_delay_after_onavailable_ms", delayMs);
        if (delayMs > 0) {
            sleepQuietly(delayMs);
        }
        recordNetworkVisibility(mode + "_after_delay_before_connect");
        runTcpSendMode(mode, false, true, networkFactory);
    }

    private void checkpoint(String label) {
        artifact.diagnostic(SECTION, "last_checkpoint", label);
        artifact.diagnostic(SECTION, label + "_elapsed_realtime_ms", SystemClock.elapsedRealtime());
        artifact.writeQuietly();
    }

    private Thread startTcpTunnelStreamWatchdog(final String mode, final Socket socket) {
        tcpTunnelStreamLastProgressMs = SystemClock.elapsedRealtime();
        tcpTunnelStreamWatchdogStop = false;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                long stallMs = Math.max(3, config.socketTimeoutSeconds) * 1000L;
                while (!tcpTunnelStreamWatchdogStop) {
                    sleepQuietly(1000L);
                    long idleMs = SystemClock.elapsedRealtime() - tcpTunnelStreamLastProgressMs;
                    if (idleMs > stallMs) {
                        artifact.diagnostic(SECTION, mode + "_stall_watchdog_closed_socket", true);
                        artifact.diagnostic(SECTION, mode + "_stall_watchdog_idle_ms", idleMs);
                        checkpoint(mode + "_stall_watchdog");
                        closeQuietly(socket);
                        return;
                    }
                }
            }
        }, "qcl041-app-bound-tcp-tunnel-stream-watchdog");
        thread.start();
        artifact.diagnostic(SECTION, mode + "_stall_watchdog_started", true);
        return thread;
    }

    private void stopTcpTunnelStreamWatchdog(Thread thread, String mode) {
        tcpTunnelStreamWatchdogStop = true;
        if (thread == null) {
            return;
        }
        try {
            thread.join(1500L);
            artifact.diagnostic(SECTION, mode + "_stall_watchdog_alive_after_join", thread.isAlive());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            artifact.diagnostic(SECTION, mode + "_stall_watchdog_join_interrupted", true);
        }
    }

    private void noteTcpTunnelStreamProgress(String mode, String direction, int bytes, int frames) {
        tcpTunnelStreamLastProgressMs = SystemClock.elapsedRealtime();
        artifact.diagnostic(SECTION, mode + "_" + direction + "_progress_bytes", bytes);
        artifact.diagnostic(SECTION, mode + "_" + direction + "_progress_frames", frames);
        if (frames > 0 && frames % 64 == 0) {
            checkpoint(mode + "_" + direction + "_progress");
        }
    }

    private void runUdpReceiver() {
        DatagramSocket socket = null;
        int totalPackets = 0;
        int totalBytes = 0;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(
                    localAddress == null ? InetAddress.getByName("0.0.0.0") : localAddress,
                    config.q2qAppBoundSocketMatrixPort));
            socket.setSoTimeout(500);
            artifact.diagnostic(SECTION, "udp_receiver_bound", socket.getLocalSocketAddress().toString());
            byte[] buffer = new byte[2048];
            int receiveWindowMs = udpReceiveWindowMs();
            artifact.diagnostic(SECTION, "udp_receive_window_ms", receiveWindowMs);
            artifact.diagnostic(SECTION, "udp_receive_delayed_grace_ms", DELAYED_UDP_RECEIVE_GRACE_MS);
            long deadline = SystemClock.elapsedRealtime() + receiveWindowMs;
            while (SystemClock.elapsedRealtime() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                totalPackets++;
                totalBytes += packet.getLength();
                String payload = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8);
                String mode = sanitizeMode(payloadMode(payload));
                int modePackets = increment(udpRxByMode, mode);
                artifact.diagnostic(SECTION, mode + "_rx_packets", modePackets);
                artifact.diagnostic(SECTION, mode + "_last_source", packet.getAddress().getHostAddress());
                artifact.diagnostic(SECTION, mode + "_last_source_port", packet.getPort());
                artifact.diagnostic(SECTION, mode + "_last_bytes", packet.getLength());
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "udp_receiver_error", errorText(ex));
        } finally {
            artifact.diagnostic(SECTION, "udp_rx_total_packets", totalPackets);
            artifact.diagnostic(SECTION, "udp_rx_total_bytes", totalBytes);
            closeQuietly(socket);
        }
    }

    private Thread startTcpTunnelReceiverThread() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runTcpTunnelReceiver();
            }
        }, "qcl041-app-bound-tcp-tunnel-receiver");
        thread.start();
        artifact.diagnostic(SECTION, TCP_TUNNEL_MODE + "_receiver_thread_started", true);
        return thread;
    }

    private Thread startTcpTunnelStreamReceiverThread() {
        if (!tcpTunnelStreamEnabled()) {
            artifact.diagnostic(SECTION, TCP_TUNNEL_STREAM_MODE + "_skipped", "stream_not_configured");
            return null;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runTcpTunnelStreamReceiver();
            }
        }, "qcl041-app-bound-tcp-tunnel-stream-receiver");
        thread.start();
        artifact.diagnostic(SECTION, TCP_TUNNEL_STREAM_MODE + "_receiver_thread_started", true);
        return thread;
    }

    private void joinThread(Thread thread, String label) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(Math.max(3, config.socketTimeoutSeconds) * 1000L + 1000L);
            artifact.diagnostic(SECTION, label + "_thread_alive_after_join", thread.isAlive());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            artifact.diagnostic(SECTION, label + "_join_interrupted", true);
        }
    }

    private void runTcpTunnelReceiver() {
        ServerSocket server = null;
        Socket peer = null;
        int accepts = 0;
        long started = SystemClock.elapsedRealtime();
        String mode = TCP_TUNNEL_MODE;
        try {
            recordNetworkVisibility(mode + "_receiver_start");
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(
                    localAddress == null ? InetAddress.getByName("0.0.0.0") : localAddress,
                    tcpTunnelPort()));
            server.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            artifact.diagnostic(SECTION, mode + "_receiver_bound", server.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_bytes_per_direction", TCP_TUNNEL_BYTES_PER_DIRECTION);
            peer = server.accept();
            accepts++;
            long acceptMs = SystemClock.elapsedRealtime() - started;
            peer.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            artifact.diagnostic(SECTION, mode + "_accepts", accepts);
            artifact.diagnostic(SECTION, mode + "_accept_ms", acceptMs);
            artifact.diagnostic(SECTION, mode + "_accepted_source", peer.getInetAddress().getHostAddress());
            artifact.diagnostic(SECTION, mode + "_accepted_source_port", peer.getPort());
            artifact.diagnostic(SECTION, mode + "_accepted_local", peer.getLocalSocketAddress().toString());

            InputStream input = peer.getInputStream();
            OutputStream output = peer.getOutputStream();
            String request = readAsciiLine(input, 512);
            artifact.diagnostic(SECTION, mode + "_request", request);
            int clientBytes = boundedLineInt(request, "client_bytes", TCP_TUNNEL_BYTES_PER_DIRECTION);
            ByteTransferStats clientToOwner = readExactBytesWithCrc(input, clientBytes);
            long expectedClientCrc = patternedCrc(clientBytes, TCP_TUNNEL_CLIENT_SALT);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_rx_bytes", clientToOwner.bytes);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_rx_crc32", clientToOwner.crc32);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_expected_crc32", expectedClientCrc);
            artifact.diagnostic(
                    SECTION,
                    mode + "_client_to_owner_crc32_match",
                    clientToOwner.bytes == clientBytes && clientToOwner.crc32 == expectedClientCrc);

            String response = mode
                    + "_ack;run_id=" + config.runId
                    + ";owner_bytes=" + TCP_TUNNEL_BYTES_PER_DIRECTION
                    + "\n";
            output.write(response.getBytes(StandardCharsets.UTF_8));
            long ownerCrc = writePatternedBytes(output, TCP_TUNNEL_BYTES_PER_DIRECTION, TCP_TUNNEL_OWNER_SALT);
            output.flush();
            artifact.diagnostic(SECTION, mode + "_owner_to_client_tx_bytes", TCP_TUNNEL_BYTES_PER_DIRECTION);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_tx_crc32", ownerCrc);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_tx_completed", true);
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_receiver_error", errorText(ex));
        } finally {
            artifact.diagnostic(SECTION, mode + "_receiver_accept_total", accepts);
            closeQuietly(peer);
            closeQuietly(server);
        }
    }

    private void runTcpTunnelClient() {
        Socket socket = null;
        long started = SystemClock.elapsedRealtime();
        String mode = TCP_TUNNEL_MODE;
        try {
            recordTraceTcpVariant(mode, "before_tcp_bind_connect");
            recordNetworkVisibility(mode + "_client_pre_connect");
            if (wifiDirectNetwork == null) {
                artifact.diagnostic(SECTION, mode + "_skipped", "wifi_direct_network_not_found");
                return;
            }
            if (groupOwnerAddress == null) {
                artifact.diagnostic(SECTION, mode + "_skipped", "group_owner_address_not_found");
                return;
            }
            socket = wifiDirectNetwork.getSocketFactory().createSocket();
            artifact.diagnostic(SECTION, mode + "_network_factory_socket", true);
            artifact.diagnostic(SECTION, mode + "_network_handle", wifiDirectNetwork.getNetworkHandle());
            if (localAddress != null) {
                socket.bind(new InetSocketAddress(localAddress, 0));
                artifact.diagnostic(SECTION, mode + "_source_bound", socket.getLocalSocketAddress().toString());
            } else {
                artifact.diagnostic(SECTION, mode + "_source_bound_skipped", "local_p2p_address_not_found");
            }
            artifact.diagnostic(SECTION, mode + "_local_before_connect", String.valueOf(socket.getLocalSocketAddress()));
            socket.connect(
                    new InetSocketAddress(groupOwnerAddress, tcpTunnelPort()),
                    Math.max(3, config.socketTimeoutSeconds) * 1000);
            socket.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            artifact.diagnostic(SECTION, mode + "_connected", true);
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
            artifact.diagnostic(SECTION, mode + "_local", socket.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_remote", socket.getRemoteSocketAddress().toString());
            recordTraceTcpVariant(mode, "after_tcp_connect");

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();
            String request = mode
                    + ";run_id=" + config.runId
                    + ";client_bytes=" + TCP_TUNNEL_BYTES_PER_DIRECTION
                    + "\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            long clientCrc = writePatternedBytes(output, TCP_TUNNEL_BYTES_PER_DIRECTION, TCP_TUNNEL_CLIENT_SALT);
            output.flush();
            artifact.diagnostic(SECTION, mode + "_client_to_owner_tx_bytes", TCP_TUNNEL_BYTES_PER_DIRECTION);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_tx_crc32", clientCrc);

            String response = readAsciiLine(input, 512);
            artifact.diagnostic(SECTION, mode + "_reply", response);
            int ownerBytes = boundedLineInt(response, "owner_bytes", TCP_TUNNEL_BYTES_PER_DIRECTION);
            ByteTransferStats ownerToClient = readExactBytesWithCrc(input, ownerBytes);
            long expectedOwnerCrc = patternedCrc(ownerBytes, TCP_TUNNEL_OWNER_SALT);
            boolean ownerBytesMatched =
                    ownerToClient.bytes == ownerBytes && ownerToClient.crc32 == expectedOwnerCrc;
            artifact.diagnostic(SECTION, mode + "_owner_to_client_rx_bytes", ownerToClient.bytes);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_rx_crc32", ownerToClient.crc32);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_expected_crc32", expectedOwnerCrc);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_crc32_match", ownerBytesMatched);
            artifact.diagnostic(SECTION, mode + "_bidirectional_client_observed", ownerBytesMatched);
        } catch (Exception ex) {
            recordTraceTcpFailure(mode, ex);
            artifact.diagnostic(SECTION, mode + "_connect_error", errorText(ex));
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
        } finally {
            checkpoint(mode + "_client_final");
            closeQuietly(socket);
        }
    }

    private void runTcpTunnelStreamReceiver() {
        runTcpTunnelStreamReceiver(TCP_TUNNEL_STREAM_MODE);
    }

    private void runTcpTunnelStreamReceiver(String mode) {
        ServerSocket server = null;
        Socket peer = null;
        int accepts = 0;
        long started = SystemClock.elapsedRealtime();
        int configuredBytes = tcpTunnelStreamBytesPerDirection();
        int configuredChunkBytes = tcpTunnelStreamChunkBytes(configuredBytes);
        int targetClientBytes = configuredBytes;
        int targetOwnerBytes = configuredBytes;
        int clientToOwnerBytes = 0;
        int ownerToClientBytes = 0;
        int framesRx = 0;
        int framesTx = 0;
        CRC32 clientToOwnerCrc = new CRC32();
        CRC32 ownerToClientCrc = new CRC32();
        Thread watchdogThread = null;
        try {
            recordNetworkVisibility(mode + "_receiver_start");
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(
                    localAddress == null ? InetAddress.getByName("0.0.0.0") : localAddress,
                    tcpTunnelStreamPort()));
            server.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            artifact.diagnostic(SECTION, mode + "_receiver_bound", server.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_bytes_per_direction", configuredBytes);
            artifact.diagnostic(SECTION, mode + "_chunk_bytes", configuredChunkBytes);
            peer = server.accept();
            accepts++;
            long acceptMs = SystemClock.elapsedRealtime() - started;
            peer.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            artifact.diagnostic(SECTION, mode + "_accepts", accepts);
            artifact.diagnostic(SECTION, mode + "_accept_ms", acceptMs);
            artifact.diagnostic(SECTION, mode + "_accepted_source", peer.getInetAddress().getHostAddress());
            artifact.diagnostic(SECTION, mode + "_accepted_source_port", peer.getPort());
            artifact.diagnostic(SECTION, mode + "_accepted_local", peer.getLocalSocketAddress().toString());

            InputStream input = peer.getInputStream();
            OutputStream output = peer.getOutputStream();
            String request = readAsciiLine(input, 512);
            artifact.diagnostic(SECTION, mode + "_request", request);
            targetClientBytes = boundedLineInt(
                    request,
                    "client_bytes",
                    configuredBytes,
                    TCP_TUNNEL_STREAM_MAX_BYTES_PER_DIRECTION);
            targetOwnerBytes = boundedLineInt(
                    request,
                    "owner_bytes",
                    configuredBytes,
                    TCP_TUNNEL_STREAM_MAX_BYTES_PER_DIRECTION);
            int chunkBytes = boundedLineInt(
                    request,
                    "chunk_bytes",
                    configuredChunkBytes,
                    TCP_TUNNEL_STREAM_CHUNK_BYTES);
            long transferStarted = SystemClock.elapsedRealtime();
            long deadline = transferStarted + tcpTunnelStreamDurationMs();
            artifact.diagnostic(SECTION, mode + "_transfer_started_after_accept_ms", transferStarted - started);
            watchdogThread = startTcpTunnelStreamWatchdog(mode, peer);
            while ((clientToOwnerBytes < targetClientBytes || ownerToClientBytes < targetOwnerBytes)
                    && SystemClock.elapsedRealtime() <= deadline) {
                if (clientToOwnerBytes < targetClientBytes) {
                    int chunk = Math.min(chunkBytes, targetClientBytes - clientToOwnerBytes);
                    int read = readExactBytesWithCrc(input, chunk, clientToOwnerCrc);
                    clientToOwnerBytes += read;
                    if (read > 0) {
                        framesRx++;
                        noteTcpTunnelStreamProgress(
                                mode,
                                "client_to_owner_rx",
                                clientToOwnerBytes,
                                framesRx);
                    }
                    if (read < chunk) {
                        break;
                    }
                }
                if (ownerToClientBytes < targetOwnerBytes) {
                    int chunk = Math.min(chunkBytes, targetOwnerBytes - ownerToClientBytes);
                    writePatternedBytes(output, ownerToClientBytes, chunk, TCP_TUNNEL_OWNER_SALT, ownerToClientCrc);
                    output.flush();
                    ownerToClientBytes += chunk;
                    framesTx++;
                    noteTcpTunnelStreamProgress(
                            mode,
                            "owner_to_client_tx",
                            ownerToClientBytes,
                            framesTx);
                }
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_receiver_error", errorText(ex));
        } finally {
            stopTcpTunnelStreamWatchdog(watchdogThread, mode);
            long expectedClientCrc = patternedCrc(clientToOwnerBytes, TCP_TUNNEL_CLIENT_SALT);
            boolean clientBytesMatched =
                    clientToOwnerBytes == targetClientBytes && clientToOwnerCrc.getValue() == expectedClientCrc;
            artifact.diagnostic(SECTION, mode + "_client_to_owner_target_bytes", targetClientBytes);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_target_bytes", targetOwnerBytes);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_rx_bytes", clientToOwnerBytes);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_rx_crc32", clientToOwnerCrc.getValue());
            artifact.diagnostic(SECTION, mode + "_client_to_owner_expected_crc32", expectedClientCrc);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_crc32_match", clientBytesMatched);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_tx_bytes", ownerToClientBytes);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_tx_crc32", ownerToClientCrc.getValue());
            artifact.diagnostic(SECTION, mode + "_frames_rx", framesRx);
            artifact.diagnostic(SECTION, mode + "_frames_tx", framesTx);
            artifact.diagnostic(
                    SECTION,
                    mode + "_completed",
                    clientBytesMatched && ownerToClientBytes == targetOwnerBytes);
            artifact.diagnostic(SECTION, mode + "_duration_ms", SystemClock.elapsedRealtime() - started);
            artifact.diagnostic(SECTION, mode + "_receiver_accept_total", accepts);
            checkpoint(mode + "_receiver_final");
            closeQuietly(peer);
            closeQuietly(server);
        }
    }

    private void runTcpTunnelStreamClient() {
        runTcpTunnelStreamClient(TCP_TUNNEL_STREAM_MODE, true, false);
    }

    private void runTcpTunnelStreamClient(
            String mode,
            boolean networkFactorySocket,
            boolean localP2pDiagnostic) {
        Socket socket = null;
        long started = SystemClock.elapsedRealtime();
        int configuredBytes = tcpTunnelStreamBytesPerDirection();
        int chunkBytes = tcpTunnelStreamChunkBytes(configuredBytes);
        int clientToOwnerBytes = 0;
        int ownerToClientBytes = 0;
        int framesTx = 0;
        int framesRx = 0;
        CRC32 clientToOwnerCrc = new CRC32();
        CRC32 ownerToClientCrc = new CRC32();
        boolean streamAttempted = false;
        Thread watchdogThread = null;
        try {
            if (!tcpTunnelStreamEnabled()) {
                artifact.diagnostic(SECTION, mode + "_skipped", "stream_not_configured");
                return;
            }
            recordNetworkVisibility(mode + "_client_pre_connect");
            if (groupOwnerAddress == null) {
                artifact.diagnostic(SECTION, mode + "_skipped", "group_owner_address_not_found");
                return;
            }
            streamAttempted = true;
            if (localP2pDiagnostic) {
                artifact.diagnostic(SECTION, mode + "_diagnostic_non_promoting", true);
                artifact.diagnostic(SECTION, mode + "_socket_authority", "network_interface_local_p2p_address_bind");
                artifact.diagnostic(SECTION, mode + "_local_p2p_bind_authority_attempted", true);
                recordTraceTcpVariant(mode, "before_local_p2p_bind_stream_connect");
            } else {
                recordTraceTcpVariant(mode, "before_tcp_bind_connect");
            }
            if (networkFactorySocket) {
                if (wifiDirectNetwork == null) {
                    artifact.diagnostic(SECTION, mode + "_skipped", "wifi_direct_network_not_found");
                    return;
                }
                socket = wifiDirectNetwork.getSocketFactory().createSocket();
                artifact.diagnostic(SECTION, mode + "_network_factory_socket", true);
                artifact.diagnostic(SECTION, mode + "_network_handle", wifiDirectNetwork.getNetworkHandle());
            } else {
                socket = new Socket();
            }
            if (localAddress != null) {
                socket.bind(new InetSocketAddress(localAddress, 0));
                artifact.diagnostic(SECTION, mode + "_source_bound", socket.getLocalSocketAddress().toString());
                if (localP2pDiagnostic) {
                    artifact.diagnostic(SECTION, mode + "_local_p2p_bind", socket.getLocalSocketAddress().toString());
                    artifact.diagnostic(SECTION, mode + "_local_p2p_address", localAddress.getHostAddress());
                    artifact.diagnostic(
                            SECTION,
                            mode + "_local_p2p_same_subnet_as_group_owner",
                            Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(localAddress, groupOwnerAddress));
                }
            } else {
                if (localP2pDiagnostic) {
                    artifact.diagnostic(SECTION, mode + "_skipped", "local_p2p_address_not_found");
                    return;
                } else {
                    artifact.diagnostic(SECTION, mode + "_source_bound_skipped", "local_p2p_address_not_found");
                }
            }
            artifact.diagnostic(SECTION, mode + "_local_before_connect", String.valueOf(socket.getLocalSocketAddress()));
            socket.connect(
                    new InetSocketAddress(groupOwnerAddress, tcpTunnelStreamPort()),
                    Math.max(3, config.socketTimeoutSeconds) * 1000);
            socket.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            artifact.diagnostic(SECTION, mode + "_connected", true);
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
            artifact.diagnostic(SECTION, mode + "_local", socket.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_remote", socket.getRemoteSocketAddress().toString());
            if (localP2pDiagnostic) {
                artifact.diagnostic(SECTION, mode + "_local_p2p_bind_authority_pass", true);
            }
            recordTraceTcpVariant(mode, "after_tcp_connect");

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();
            String request = mode
                    + ";run_id=" + config.runId
                    + ";client_bytes=" + configuredBytes
                    + ";owner_bytes=" + configuredBytes
                    + ";chunk_bytes=" + chunkBytes
                    + ";seconds=" + tcpTunnelStreamSeconds()
                    + "\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
            artifact.diagnostic(SECTION, mode + "_request", request.trim());
            artifact.diagnostic(SECTION, mode + "_bytes_per_direction", configuredBytes);
            artifact.diagnostic(SECTION, mode + "_chunk_bytes", chunkBytes);
            checkpoint(mode + "_client_connected");

            long transferStarted = SystemClock.elapsedRealtime();
            long deadline = transferStarted + tcpTunnelStreamDurationMs();
            artifact.diagnostic(SECTION, mode + "_transfer_started_after_connect_ms", transferStarted - started);
            watchdogThread = startTcpTunnelStreamWatchdog(mode, socket);
            while ((clientToOwnerBytes < configuredBytes || ownerToClientBytes < configuredBytes)
                    && SystemClock.elapsedRealtime() <= deadline) {
                if (clientToOwnerBytes < configuredBytes) {
                    int chunk = Math.min(chunkBytes, configuredBytes - clientToOwnerBytes);
                    writePatternedBytes(output, clientToOwnerBytes, chunk, TCP_TUNNEL_CLIENT_SALT, clientToOwnerCrc);
                    output.flush();
                    clientToOwnerBytes += chunk;
                    framesTx++;
                    noteTcpTunnelStreamProgress(
                            mode,
                            "client_to_owner_tx",
                            clientToOwnerBytes,
                            framesTx);
                }
                if (ownerToClientBytes < configuredBytes) {
                    int chunk = Math.min(chunkBytes, configuredBytes - ownerToClientBytes);
                    int read = readExactBytesWithCrc(input, chunk, ownerToClientCrc);
                    ownerToClientBytes += read;
                    if (read > 0) {
                        framesRx++;
                        noteTcpTunnelStreamProgress(
                                mode,
                                "owner_to_client_rx",
                                ownerToClientBytes,
                                framesRx);
                    }
                    if (read < chunk) {
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            recordTraceTcpFailure(mode, ex);
            artifact.diagnostic(SECTION, mode + "_connect_error", errorText(ex));
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
        } finally {
            stopTcpTunnelStreamWatchdog(watchdogThread, mode);
            long expectedClientCrc = patternedCrc(clientToOwnerBytes, TCP_TUNNEL_CLIENT_SALT);
            long expectedOwnerCrc = patternedCrc(ownerToClientBytes, TCP_TUNNEL_OWNER_SALT);
            boolean clientCrcMatched = clientToOwnerCrc.getValue() == expectedClientCrc;
            boolean ownerBytesMatched =
                    ownerToClientBytes == configuredBytes && ownerToClientCrc.getValue() == expectedOwnerCrc;
            boolean bidirectionalObserved = streamAttempted
                    && configuredBytes > 0
                    && clientToOwnerBytes == configuredBytes
                    && clientCrcMatched
                    && ownerBytesMatched;
            artifact.diagnostic(SECTION, mode + "_client_to_owner_target_bytes", configuredBytes);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_target_bytes", configuredBytes);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_tx_bytes", clientToOwnerBytes);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_tx_crc32", clientToOwnerCrc.getValue());
            artifact.diagnostic(SECTION, mode + "_client_to_owner_expected_crc32", expectedClientCrc);
            artifact.diagnostic(SECTION, mode + "_client_to_owner_crc32_match", clientCrcMatched);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_rx_bytes", ownerToClientBytes);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_rx_crc32", ownerToClientCrc.getValue());
            artifact.diagnostic(SECTION, mode + "_owner_to_client_expected_crc32", expectedOwnerCrc);
            artifact.diagnostic(SECTION, mode + "_owner_to_client_crc32_match", ownerBytesMatched);
            artifact.diagnostic(SECTION, mode + "_frames_tx", framesTx);
            artifact.diagnostic(SECTION, mode + "_frames_rx", framesRx);
            artifact.diagnostic(
                    SECTION,
                    mode + "_bidirectional_client_observed",
                    bidirectionalObserved);
            if (localP2pDiagnostic) {
                artifact.diagnostic(SECTION, mode + "_local_p2p_stream_bidirectional_bytes_pass", bidirectionalObserved);
            }
            artifact.diagnostic(SECTION, mode + "_duration_ms", SystemClock.elapsedRealtime() - started);
            checkpoint(mode + "_client_final");
            closeQuietly(socket);
        }
    }

    private void runDelayedUdpSendModesIfRequested() {
        int delayMs = delayedUdpDelayMs();
        if (delayMs <= 0) {
            artifact.diagnostic(SECTION, "delayed_udp_skipped", "delay_not_configured");
            return;
        }
        DatagramSocket earlyNetworkSocket = prepareEarlyBoundUdpSocket(
                "early_bound_delayed_udp_network_bound",
                false);
        DatagramSocket earlySourceNetworkSocket = prepareEarlyBoundUdpSocket(
                "early_bound_delayed_udp_source_and_network_bound",
                true);
        try {
            sleepQuietly(delayMs);
            artifact.diagnostic(SECTION, "delayed_udp_wait_completed_ms", delayMs);
            recordNetworkVisibility("delayed_udp_pre_send");
            sendPreparedUdpMode("early_bound_delayed_udp_network_bound", earlyNetworkSocket);
            sendPreparedUdpMode("early_bound_delayed_udp_source_and_network_bound", earlySourceNetworkSocket);
            runUdpSendMode("delayed_udp_network_bound", false, true);
            runUdpSendMode("delayed_udp_source_and_network_bound", true, true);
            runNativeUdpSendMode("delayed_udp_native_fd_network_bound");
            runUdpProcessBoundMode("delayed_udp_process_bound");
            recordNetworkVisibility("delayed_udp_post_send");
        } finally {
            closeQuietly(earlyNetworkSocket);
            closeQuietly(earlySourceNetworkSocket);
        }
    }

    private void runDelayedUdpSendModesWithTimeout() {
        int delayMs = delayedUdpDelayMs();
        if (delayMs <= 0) {
            runDelayedUdpSendModesIfRequested();
            return;
        }
        final boolean[] completed = new boolean[] { false };
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runDelayedUdpSendModesIfRequested();
                    completed[0] = true;
                } catch (Exception ex) {
                    artifact.diagnostic(SECTION, "delayed_udp_thread_error", errorText(ex));
                } finally {
                    checkpoint("delayed_udp_thread_final");
                }
            }
        }, "qcl041-app-bound-delayed-udp-sender");
        thread.setDaemon(true);
        long timeoutMs = delayMs
                + DELAYED_UDP_RECEIVE_GRACE_MS
                + Math.max(3, config.socketTimeoutSeconds) * 1000L;
        artifact.diagnostic(SECTION, "delayed_udp_thread_started", true);
        artifact.diagnostic(SECTION, "delayed_udp_thread_timeout_ms", timeoutMs);
        thread.start();
        try {
            thread.join(timeoutMs);
            artifact.diagnostic(SECTION, "delayed_udp_thread_completed", completed[0]);
            artifact.diagnostic(SECTION, "delayed_udp_thread_alive_after_join", thread.isAlive());
            if (thread.isAlive()) {
                artifact.diagnostic(SECTION, "delayed_udp_thread_timeout", true);
                checkpoint("client_delayed_udp_timeout");
            } else {
                checkpoint("client_delayed_udp_completed");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            artifact.diagnostic(SECTION, "delayed_udp_thread_join_interrupted", true);
            checkpoint("client_delayed_udp_interrupted");
        }
    }

    private void recordNetworkVisibility(String prefix) {
        artifact.diagnostic(SECTION, prefix + "_network_available", wifiDirectNetwork != null);
        artifact.diagnostic(SECTION, prefix + "_connectivity_manager_found", connectivityManager != null);
        if (wifiDirectNetwork == null || connectivityManager == null) {
            return;
        }
        artifact.diagnostic(SECTION, prefix + "_network", wifiDirectNetwork.toString());
        artifact.diagnostic(SECTION, prefix + "_network_handle", wifiDirectNetwork.getNetworkHandle());
        try {
            LinkProperties properties = connectivityManager.getLinkProperties(wifiDirectNetwork);
            artifact.diagnostic(SECTION, prefix + "_link_properties_found", properties != null);
            if (properties != null) {
                artifact.diagnostic(SECTION, prefix + "_interface", properties.getInterfaceName());
                artifact.diagnostic(SECTION, prefix + "_link_addresses", String.valueOf(properties.getLinkAddresses()));
                artifact.diagnostic(SECTION, prefix + "_routes", String.valueOf(properties.getRoutes()));
                boolean routeMatches = false;
                boolean addressSameSubnet = false;
                for (android.net.RouteInfo route : properties.getRoutes()) {
                    try {
                        if (groupOwnerAddress != null && route.matches(groupOwnerAddress)) {
                            routeMatches = true;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
                for (android.net.LinkAddress address : properties.getLinkAddresses()) {
                    if (Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(address.getAddress(), groupOwnerAddress)) {
                        addressSameSubnet = true;
                        break;
                    }
                }
                artifact.diagnostic(SECTION, prefix + "_route_matches_group_owner", routeMatches);
                artifact.diagnostic(SECTION, prefix + "_address_same_subnet_as_group_owner", addressSameSubnet);
                artifact.diagnostic(
                        SECTION,
                        prefix + "_p2p_interface",
                        properties.getInterfaceName() != null
                                && properties.getInterfaceName().toLowerCase(Locale.US).contains("p2p"));
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, prefix + "_link_properties_error", errorText(ex));
        }
        try {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(wifiDirectNetwork);
            artifact.diagnostic(SECTION, prefix + "_network_capabilities_found", capabilities != null);
            if (capabilities != null) {
                artifact.diagnostic(SECTION, prefix + "_capabilities", capabilities.toString());
                artifact.diagnostic(
                        SECTION,
                        prefix + "_has_transport_wifi",
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                artifact.diagnostic(
                        SECTION,
                        prefix + "_has_capability_wifi_p2p",
                        hasCapabilityByName(capabilities, "NET_CAPABILITY_WIFI_P2P"));
                artifact.diagnostic(
                        SECTION,
                        prefix + "_has_capability_local_network",
                        hasCapabilityByName(capabilities, "NET_CAPABILITY_LOCAL_NETWORK"));
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, prefix + "_network_capabilities_error", errorText(ex));
        }
    }

    private void recordWifiP2pNetworkRequestVisibility(String prefix) {
        String key = prefix + "_wifi_p2p_request_";
        artifact.diagnostic(SECTION, key + "attempted", true);
        artifact.diagnostic(SECTION, key + "connectivity_manager_found", connectivityManager != null);
        if (connectivityManager == null) {
            return;
        }
        int wifiP2pCapability = capabilityByName("NET_CAPABILITY_WIFI_P2P");
        artifact.diagnostic(SECTION, key + "capability_constant_found", wifiP2pCapability >= 0);
        artifact.diagnostic(SECTION, key + "capability_constant_value", wifiP2pCapability);
        if (wifiP2pCapability < 0) {
            artifact.diagnostic(SECTION, key + "skipped", "NET_CAPABILITY_WIFI_P2P_unavailable");
            return;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final Network[] matchedNetwork = new Network[1];
        final LinkProperties[] matchedProperties = new LinkProperties[1];
        final NetworkCapabilities[] matchedCapabilities = new NetworkCapabilities[1];
        final String[] firstCallback = new String[] { "" };
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                noteWifiP2pNetworkRequestCallback(
                        "onAvailable",
                        network,
                        matchedNetwork,
                        matchedProperties,
                        matchedCapabilities,
                        firstCallback,
                        latch);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (matchedCapabilities[0] == null) {
                    matchedCapabilities[0] = capabilities;
                }
                noteWifiP2pNetworkRequestCallback(
                        "onCapabilitiesChanged",
                        network,
                        matchedNetwork,
                        matchedProperties,
                        matchedCapabilities,
                        firstCallback,
                        latch);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                if (matchedProperties[0] == null) {
                    matchedProperties[0] = linkProperties;
                }
                noteWifiP2pNetworkRequestCallback(
                        "onLinkPropertiesChanged",
                        network,
                        matchedNetwork,
                        matchedProperties,
                        matchedCapabilities,
                        firstCallback,
                        latch);
            }

            @Override
            public void onUnavailable() {
                if (firstCallback[0].isEmpty()) {
                    firstCallback[0] = "onUnavailable";
                }
                latch.countDown();
            }
        };

        boolean registered = false;
        long started = SystemClock.elapsedRealtime();
        try {
            NetworkRequest request = baseNetworkRequestBuilder()
                    .addCapability(wifiP2pCapability)
                    .build();
            artifact.diagnostic(SECTION, key + "request", request.toString());
            connectivityManager.registerNetworkCallback(request, callback);
            registered = true;
            boolean observed = latch.await(1200L, TimeUnit.MILLISECONDS);
            artifact.diagnostic(SECTION, key + "callback_observed", observed);
            artifact.diagnostic(SECTION, key + "wait_elapsed_ms", SystemClock.elapsedRealtime() - started);
            artifact.diagnostic(SECTION, key + "first_callback", firstCallback[0]);
            recordWifiP2pNetworkRequestMatch(key, matchedNetwork[0], matchedProperties[0], matchedCapabilities[0]);
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, key + "error", errorText(ex));
            artifact.diagnostic(SECTION, key + "wait_elapsed_ms", SystemClock.elapsedRealtime() - started);
        } finally {
            if (registered) {
                try {
                    connectivityManager.unregisterNetworkCallback(callback);
                    artifact.diagnostic(SECTION, key + "unregistered", true);
                } catch (Exception ex) {
                    artifact.diagnostic(SECTION, key + "unregister_error", errorText(ex));
                }
            }
        }
    }

    private void noteWifiP2pNetworkRequestCallback(
            String callbackName,
            Network network,
            Network[] matchedNetwork,
            LinkProperties[] matchedProperties,
            NetworkCapabilities[] matchedCapabilities,
            String[] firstCallback,
            CountDownLatch latch) {
        if (firstCallback[0].isEmpty()) {
            firstCallback[0] = callbackName;
        }
        if (matchedNetwork[0] == null) {
            matchedNetwork[0] = network;
        }
        if (network != null && matchedProperties[0] == null) {
            try {
                matchedProperties[0] = connectivityManager.getLinkProperties(network);
            } catch (Exception ignored) {
            }
        }
        if (network != null && matchedCapabilities[0] == null) {
            try {
                matchedCapabilities[0] = connectivityManager.getNetworkCapabilities(network);
            } catch (Exception ignored) {
            }
        }
        latch.countDown();
    }

    private void recordWifiP2pNetworkRequestMatch(
            String key,
            Network network,
            LinkProperties properties,
            NetworkCapabilities capabilities) {
        artifact.diagnostic(SECTION, key + "network_found", network != null);
        if (network != null) {
            artifact.diagnostic(SECTION, key + "network", network.toString());
            artifact.diagnostic(SECTION, key + "network_handle", network.getNetworkHandle());
            artifact.diagnostic(
                    SECTION,
                    key + "matches_selected_network",
                    wifiDirectNetwork != null
                            && network.getNetworkHandle() == wifiDirectNetwork.getNetworkHandle());
        }
        artifact.diagnostic(SECTION, key + "link_properties_found", properties != null);
        if (properties != null) {
            artifact.diagnostic(SECTION, key + "interface", properties.getInterfaceName());
            artifact.diagnostic(SECTION, key + "link_addresses", String.valueOf(properties.getLinkAddresses()));
            artifact.diagnostic(SECTION, key + "routes", String.valueOf(properties.getRoutes()));
        }
        artifact.diagnostic(SECTION, key + "network_capabilities_found", capabilities != null);
        if (capabilities != null) {
            artifact.diagnostic(SECTION, key + "capabilities", capabilities.toString());
            artifact.diagnostic(
                    SECTION,
                    key + "has_transport_wifi",
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            artifact.diagnostic(
                    SECTION,
                    key + "has_capability_wifi_p2p",
                    hasCapabilityByName(capabilities, "NET_CAPABILITY_WIFI_P2P"));
            artifact.diagnostic(
                    SECTION,
                    key + "has_capability_local_network",
                    hasCapabilityByName(capabilities, "NET_CAPABILITY_LOCAL_NETWORK"));
        }
    }

    private DatagramSocket prepareEarlyBoundUdpSocket(String mode, boolean sourceBound) {
        DatagramSocket socket = null;
        try {
            if (wifiDirectNetwork == null) {
                artifact.diagnostic(SECTION, mode + "_skipped", "wifi_direct_network_not_found");
                return null;
            }
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            wifiDirectNetwork.bindSocket(socket);
            artifact.diagnostic(SECTION, mode + "_network_bound_before_delay", true);
            artifact.diagnostic(SECTION, mode + "_network_handle", wifiDirectNetwork.getNetworkHandle());
            if (sourceBound) {
                if (localAddress == null) {
                    artifact.diagnostic(SECTION, mode + "_skipped", "local_p2p_address_not_found");
                    closeQuietly(socket);
                    return null;
                }
                socket.bind(new InetSocketAddress(localAddress, 0));
                artifact.diagnostic(SECTION, mode + "_source_bound_before_delay", socket.getLocalSocketAddress().toString());
            }
            artifact.diagnostic(SECTION, mode + "_prepared_before_delay", true);
            artifact.diagnostic(SECTION, mode + "_prepared_local", String.valueOf(socket.getLocalSocketAddress()));
            return socket;
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_prepare_error", errorText(ex));
            closeQuietly(socket);
            return null;
        }
    }

    private void sendPreparedUdpMode(String mode, DatagramSocket socket) {
        if (socket == null) {
            artifact.diagnostic(SECTION, mode + "_skipped", "early_bound_socket_not_available");
            return;
        }
        try {
            InetSocketAddress target =
                    new InetSocketAddress(groupOwnerAddress, config.q2qAppBoundSocketMatrixPort);
            for (int sequence = 0; sequence < UDP_SENDS_PER_MODE; sequence++) {
                byte[] payload = (mode + ";seq=" + sequence + ";run_id=" + config.runId).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(payload, payload.length, target));
                artifact.diagnostic(SECTION, mode + "_tx_packets", sequence + 1);
                artifact.diagnostic(SECTION, mode + "_last_local", String.valueOf(socket.getLocalSocketAddress()));
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_send_error", errorText(ex));
        }
    }

    private void runUdpSendMode(String mode, boolean sourceBound, boolean networkBound) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            if (networkBound) {
                if (wifiDirectNetwork == null) {
                    artifact.diagnostic(SECTION, mode + "_skipped", "wifi_direct_network_not_found");
                    return;
                }
                artifact.diagnostic(SECTION, mode + "_socket_authority_attempted", true);
                wifiDirectNetwork.bindSocket(socket);
                artifact.diagnostic(SECTION, mode + "_network_bound", true);
                artifact.diagnostic(SECTION, mode + "_network_handle", wifiDirectNetwork.getNetworkHandle());
            }
            if (sourceBound) {
                if (localAddress == null) {
                    artifact.diagnostic(SECTION, mode + "_skipped", "local_p2p_address_not_found");
                    return;
                }
                socket.bind(new InetSocketAddress(localAddress, 0));
                artifact.diagnostic(SECTION, mode + "_source_bound", socket.getLocalSocketAddress().toString());
            }
            InetSocketAddress target =
                    new InetSocketAddress(groupOwnerAddress, config.q2qAppBoundSocketMatrixPort);
            for (int sequence = 0; sequence < UDP_SENDS_PER_MODE; sequence++) {
                byte[] payload = (mode + ";seq=" + sequence + ";run_id=" + config.runId).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(payload, payload.length, target));
                artifact.diagnostic(SECTION, mode + "_tx_packets", sequence + 1);
                artifact.diagnostic(SECTION, mode + "_last_local", socket.getLocalSocketAddress().toString());
            }
            if (networkBound) {
                artifact.diagnostic(SECTION, mode + "_socket_authority_pass", true);
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_send_error", errorText(ex));
        } finally {
            closeQuietly(socket);
        }
    }

    private void runUdpLocalP2pBindMode(String mode) {
        DatagramSocket socket = null;
        try {
            artifact.diagnostic(SECTION, mode + "_diagnostic_non_promoting", true);
            artifact.diagnostic(SECTION, mode + "_socket_authority", "network_interface_local_p2p_address_bind");
            artifact.diagnostic(SECTION, mode + "_local_p2p_bind_authority_attempted", true);
            if (localAddress == null) {
                artifact.diagnostic(SECTION, mode + "_skipped", "local_p2p_address_not_found");
                return;
            }
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(localAddress, 0));
            artifact.diagnostic(SECTION, mode + "_local_p2p_bind", socket.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_local_p2p_address", localAddress.getHostAddress());
            artifact.diagnostic(
                    SECTION,
                    mode + "_local_p2p_same_subnet_as_group_owner",
                    Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(localAddress, groupOwnerAddress));
            InetSocketAddress target =
                    new InetSocketAddress(groupOwnerAddress, config.q2qAppBoundSocketMatrixPort);
            for (int sequence = 0; sequence < UDP_SENDS_PER_MODE; sequence++) {
                byte[] payload = (mode + ";seq=" + sequence + ";run_id=" + config.runId).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(payload, payload.length, target));
                artifact.diagnostic(SECTION, mode + "_tx_packets", sequence + 1);
                artifact.diagnostic(SECTION, mode + "_last_local", socket.getLocalSocketAddress().toString());
            }
            artifact.diagnostic(SECTION, mode + "_local_p2p_bind_authority_pass", true);
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_send_error", errorText(ex));
        } finally {
            closeQuietly(socket);
        }
    }

    private void runUdpProcessBoundMode(String mode) {
        DatagramSocket socket = null;
        Network previousNetwork = null;
        boolean processBound = false;
        try {
            previousNetwork = boundNetworkForProcess(mode);
            processBound = bindProcessForMode(mode);
            if (!processBound) {
                return;
            }
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            InetSocketAddress target =
                    new InetSocketAddress(groupOwnerAddress, config.q2qAppBoundSocketMatrixPort);
            for (int sequence = 0; sequence < UDP_SENDS_PER_MODE; sequence++) {
                byte[] payload = (mode + ";seq=" + sequence + ";run_id=" + config.runId).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(payload, payload.length, target));
                artifact.diagnostic(SECTION, mode + "_tx_packets", sequence + 1);
                artifact.diagnostic(SECTION, mode + "_last_local", socket.getLocalSocketAddress().toString());
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_send_error", errorText(ex));
        } finally {
            closeQuietly(socket);
            restoreProcessBinding(mode, previousNetwork, processBound);
        }
    }

    private void runNativeUdpSendMode(String mode) {
        if (!nativePreflight(mode)) {
            return;
        }
        try {
            JSONObject report = Qcl041NativeSocketProbe.sendUdp(
                    mode,
                    wifiDirectNetwork.getNetworkHandle(),
                    groupOwnerAddress.getHostAddress(),
                    config.q2qAppBoundSocketMatrixPort,
                    config.runId,
                    UDP_SENDS_PER_MODE);
            recordNativeReport(mode, report);
            artifact.diagnostic(SECTION, mode + "_tx_packets", report.optInt("packets_sent", 0));
            artifact.diagnostic(SECTION, mode + "_bytes_sent", report.optLong("bytes_sent", 0L));
            artifact.diagnostic(SECTION, mode + "_last_local", report.optString("local_socket", ""));
            if (!"pass".equals(report.optString("status", ""))) {
                artifact.diagnostic(SECTION, mode + "_send_error", nativeErrorText(report));
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_send_error", errorText(ex));
        }
    }

    private void runTcpReceiver() {
        ServerSocket server = null;
        Socket peer = null;
        int accepts = 0;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(
                    localAddress == null ? InetAddress.getByName("0.0.0.0") : localAddress,
                    config.q2qAppBoundSocketMatrixPort + 1));
            server.setSoTimeout(500);
            artifact.diagnostic(SECTION, "tcp_receiver_bound", server.getLocalSocketAddress().toString());
            int receiveWindowMs = tcpReceiveWindowMs();
            artifact.diagnostic(SECTION, "tcp_receive_window_ms", receiveWindowMs);
            long deadline = SystemClock.elapsedRealtime() + receiveWindowMs;
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    peer = server.accept();
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                accepts++;
                peer.setSoTimeout(1000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        peer.getInputStream(),
                        StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        peer.getOutputStream(),
                        StandardCharsets.UTF_8));
                String request = reader.readLine();
                String mode = sanitizeMode(payloadMode(request == null ? "" : request));
                int modeAccepts = increment(tcpAcceptsByMode, mode);
                artifact.diagnostic(SECTION, mode + "_accepts", modeAccepts);
                artifact.diagnostic(SECTION, mode + "_accepted_source", peer.getInetAddress().getHostAddress());
                artifact.diagnostic(SECTION, mode + "_accepted_source_port", peer.getPort());
                artifact.diagnostic(SECTION, mode + "_accepted_local", peer.getLocalSocketAddress().toString());
                writer.write("ack;" + mode + "\n");
                writer.flush();
                closeQuietly(peer);
                peer = null;
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "tcp_receiver_error", errorText(ex));
        } finally {
            artifact.diagnostic(SECTION, "tcp_accept_total", accepts);
            closeQuietly(peer);
            closeQuietly(server);
        }
    }

    private void runTcpSendMode(
            String mode,
            boolean sourceBound,
            boolean networkBound,
            boolean networkFactory) {
        Socket socket = null;
        long started = SystemClock.elapsedRealtime();
        try {
            recordTraceTcpVariant(mode, "before_tcp_bind_connect");
            if (networkFactory) {
                if (wifiDirectNetwork == null) {
                    artifact.diagnostic(SECTION, mode + "_skipped", "wifi_direct_network_not_found");
                    return;
                }
                socket = wifiDirectNetwork.getSocketFactory().createSocket();
                artifact.diagnostic(SECTION, mode + "_network_factory_socket", true);
                artifact.diagnostic(SECTION, mode + "_network_handle", wifiDirectNetwork.getNetworkHandle());
                artifact.diagnostic(SECTION, mode + "_socket_authority_attempted", true);
            } else {
                socket = new Socket();
                if (networkBound) {
                    if (wifiDirectNetwork == null) {
                        artifact.diagnostic(SECTION, mode + "_skipped", "wifi_direct_network_not_found");
                        return;
                    }
                    artifact.diagnostic(SECTION, mode + "_socket_authority_attempted", true);
                    wifiDirectNetwork.bindSocket(socket);
                    artifact.diagnostic(SECTION, mode + "_network_bound", true);
                    artifact.diagnostic(SECTION, mode + "_network_handle", wifiDirectNetwork.getNetworkHandle());
                }
            }
            if (sourceBound) {
                if (localAddress == null) {
                    artifact.diagnostic(SECTION, mode + "_skipped", "local_p2p_address_not_found");
                    return;
                }
                socket.bind(new InetSocketAddress(localAddress, 0));
                artifact.diagnostic(SECTION, mode + "_source_bound", socket.getLocalSocketAddress().toString());
            }
            InetSocketAddress target =
                    new InetSocketAddress(groupOwnerAddress, config.q2qAppBoundSocketMatrixPort + 1);
            socket.connect(target, Math.max(3, config.socketTimeoutSeconds) * 1000);
            socket.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(),
                    StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8));
            writer.write(mode + ";run_id=" + config.runId + "\n");
            writer.flush();
            String reply = reader.readLine();
            artifact.diagnostic(SECTION, mode + "_connected", true);
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
            artifact.diagnostic(SECTION, mode + "_local", socket.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_remote", socket.getRemoteSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_reply", reply == null ? "" : reply);
            if (networkBound || networkFactory) {
                artifact.diagnostic(SECTION, mode + "_socket_authority_pass", true);
            }
            recordTraceTcpVariant(mode, "after_tcp_connect");
        } catch (Exception ex) {
            recordTraceTcpFailure(mode, ex);
            artifact.diagnostic(SECTION, mode + "_connect_error", errorText(ex));
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
        } finally {
            closeQuietly(socket);
        }
    }

    private void runTcpLocalP2pBindMode(String mode) {
        Socket socket = null;
        long started = SystemClock.elapsedRealtime();
        try {
            artifact.diagnostic(SECTION, mode + "_diagnostic_non_promoting", true);
            artifact.diagnostic(SECTION, mode + "_socket_authority", "network_interface_local_p2p_address_bind");
            artifact.diagnostic(SECTION, mode + "_local_p2p_bind_authority_attempted", true);
            recordTraceTcpVariant(mode, "before_local_p2p_bind_connect");
            if (localAddress == null) {
                artifact.diagnostic(SECTION, mode + "_skipped", "local_p2p_address_not_found");
                return;
            }
            if (groupOwnerAddress == null) {
                artifact.diagnostic(SECTION, mode + "_skipped", "group_owner_address_not_found");
                return;
            }
            socket = new Socket();
            socket.bind(new InetSocketAddress(localAddress, 0));
            artifact.diagnostic(SECTION, mode + "_local_p2p_bind", socket.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_local_p2p_address", localAddress.getHostAddress());
            artifact.diagnostic(
                    SECTION,
                    mode + "_local_p2p_same_subnet_as_group_owner",
                    Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(localAddress, groupOwnerAddress));
            InetSocketAddress target =
                    new InetSocketAddress(groupOwnerAddress, config.q2qAppBoundSocketMatrixPort + 1);
            socket.connect(target, Math.max(3, config.socketTimeoutSeconds) * 1000);
            socket.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(),
                    StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8));
            writer.write(mode + ";run_id=" + config.runId + "\n");
            writer.flush();
            String reply = reader.readLine();
            artifact.diagnostic(SECTION, mode + "_connected", true);
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
            artifact.diagnostic(SECTION, mode + "_local", socket.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_remote", socket.getRemoteSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_reply", reply == null ? "" : reply);
            artifact.diagnostic(SECTION, mode + "_local_p2p_bind_authority_pass", true);
            recordTraceTcpVariant(mode, "after_local_p2p_bind_connect");
        } catch (Exception ex) {
            recordTraceTcpFailure(mode, ex);
            artifact.diagnostic(SECTION, mode + "_connect_error", errorText(ex));
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
        } finally {
            closeQuietly(socket);
        }
    }

    private void runTcpLocalP2pBindStreamMode(String mode) {
        runTcpTunnelStreamClient(mode, false, true);
    }

    private void runTcpProcessBoundMode(String mode) {
        Socket socket = null;
        Network previousNetwork = null;
        boolean processBound = false;
        long started = SystemClock.elapsedRealtime();
        try {
            recordTraceTcpVariant(mode, "before_tcp_bind_connect");
            previousNetwork = boundNetworkForProcess(mode);
            processBound = bindProcessForMode(mode);
            if (!processBound) {
                return;
            }
            socket = new Socket();
            InetSocketAddress target =
                    new InetSocketAddress(groupOwnerAddress, config.q2qAppBoundSocketMatrixPort + 1);
            socket.connect(target, Math.max(3, config.socketTimeoutSeconds) * 1000);
            socket.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(),
                    StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8));
            writer.write(mode + ";run_id=" + config.runId + "\n");
            writer.flush();
            String reply = reader.readLine();
            artifact.diagnostic(SECTION, mode + "_connected", true);
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
            artifact.diagnostic(SECTION, mode + "_local", socket.getLocalSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_remote", socket.getRemoteSocketAddress().toString());
            artifact.diagnostic(SECTION, mode + "_reply", reply == null ? "" : reply);
            recordTraceTcpVariant(mode, "after_tcp_connect");
        } catch (Exception ex) {
            recordTraceTcpFailure(mode, ex);
            artifact.diagnostic(SECTION, mode + "_connect_error", errorText(ex));
            artifact.diagnostic(SECTION, mode + "_connect_ms", SystemClock.elapsedRealtime() - started);
        } finally {
            closeQuietly(socket);
            restoreProcessBinding(mode, previousNetwork, processBound);
        }
    }

    private void runNativeTcpSendMode(String mode) {
        if (!nativePreflight(mode)) {
            return;
        }
        try {
            JSONObject report = Qcl041NativeSocketProbe.connectTcp(
                    mode,
                    wifiDirectNetwork.getNetworkHandle(),
                    groupOwnerAddress.getHostAddress(),
                    config.q2qAppBoundSocketMatrixPort + 1,
                    config.runId,
                    Math.max(3, config.socketTimeoutSeconds) * 1000);
            recordNativeReport(mode, report);
            artifact.diagnostic(SECTION, mode + "_connected", report.optBoolean("connected", false));
            artifact.diagnostic(SECTION, mode + "_connect_ms", report.optLong("connect_ms", 0L));
            artifact.diagnostic(SECTION, mode + "_local", report.optString("local_socket", ""));
            artifact.diagnostic(SECTION, mode + "_reply", report.optString("reply", ""));
            if (!report.optBoolean("connected", false)) {
                artifact.diagnostic(SECTION, mode + "_connect_error", nativeErrorText(report));
            }
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_connect_error", errorText(ex));
        }
    }

    private boolean nativePreflight(String mode) {
        Qcl041NativeSocketProbe.LoadState loadState = Qcl041NativeSocketProbe.runtimeState();
        artifact.diagnostic(SECTION, mode + "_native_runtime_available", loadState.available);
        artifact.diagnostic(SECTION, mode + "_native_runtime_detail", loadState.detail);
        if (wifiDirectNetwork == null) {
            artifact.diagnostic(SECTION, mode + "_skipped", "wifi_direct_network_not_found");
            return false;
        }
        if (groupOwnerAddress == null) {
            artifact.diagnostic(SECTION, mode + "_skipped", "group_owner_address_not_found");
            return false;
        }
        artifact.diagnostic(SECTION, mode + "_network_handle", wifiDirectNetwork.getNetworkHandle());
        return true;
    }

    private void recordNativeReport(String mode, JSONObject report) {
        artifact.diagnostic(SECTION, mode + "_native_report", report);
        artifact.diagnostic(SECTION, mode + "_native_status", report.optString("status", ""));
        artifact.diagnostic(SECTION, mode + "_native_fd_android_setsocknetwork", true);
        artifact.diagnostic(SECTION, mode + "_setsocknetwork_result", report.optInt("setsocknetwork_result", -1));
        artifact.diagnostic(SECTION, mode + "_setsocknetwork_errno", report.optInt("setsocknetwork_errno", -1));
        artifact.diagnostic(SECTION, mode + "_setsocknetwork_error", report.optString("setsocknetwork_error", ""));
    }

    private Network boundNetworkForProcess(String mode) {
        if (connectivityManager == null) {
            artifact.diagnostic(SECTION, mode + "_previous_process_network", "");
            return null;
        }
        try {
            Network previous = connectivityManager.getBoundNetworkForProcess();
            artifact.diagnostic(SECTION, mode + "_previous_process_network", previous == null ? "" : previous.toString());
            if (previous != null) {
                artifact.diagnostic(SECTION, mode + "_previous_process_network_handle", previous.getNetworkHandle());
            }
            return previous;
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_previous_process_network_error", errorText(ex));
            return null;
        }
    }

    private void recordTraceTcpVariant(String mode, String phase) {
        if (appNetworkTrace == null || !config.appNetworkTraceRequested()) {
            return;
        }
        appNetworkTrace.recordTcpVariantPhase(mode, phase, groupOwnerAddress);
    }

    private void recordTraceTcpFailure(String mode, Exception ex) {
        if (appNetworkTrace == null || !config.appNetworkTraceRequested()) {
            return;
        }
        appNetworkTrace.recordTcpFailure("tcp_variant_" + mode + "_after_tcp_failure", groupOwnerAddress, ex);
    }

    private boolean bindProcessForMode(String mode) {
        if (connectivityManager == null) {
            artifact.diagnostic(SECTION, mode + "_skipped", "connectivity_manager_not_found");
            return false;
        }
        if (wifiDirectNetwork == null) {
            artifact.diagnostic(SECTION, mode + "_skipped", "wifi_direct_network_not_found");
            return false;
        }
        try {
            boolean bound = connectivityManager.bindProcessToNetwork(wifiDirectNetwork);
            artifact.diagnostic(SECTION, mode + "_process_bound_to_wifi_direct_network", bound);
            artifact.diagnostic(SECTION, mode + "_process_bound_network", wifiDirectNetwork.toString());
            artifact.diagnostic(SECTION, mode + "_process_bound_network_handle", wifiDirectNetwork.getNetworkHandle());
            return bound;
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_process_bind_error", errorText(ex));
            return false;
        }
    }

    private void restoreProcessBinding(String mode, Network previousNetwork, boolean processBound) {
        if (!processBound || connectivityManager == null) {
            return;
        }
        try {
            boolean restored = connectivityManager.bindProcessToNetwork(previousNetwork);
            artifact.diagnostic(SECTION, mode + "_process_bind_restored", restored);
            artifact.diagnostic(
                    SECTION,
                    mode + "_restored_process_network",
                    previousNetwork == null ? "" : previousNetwork.toString());
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, mode + "_process_bind_restore_error", errorText(ex));
        }
    }

    private static int increment(Map<String, Integer> counters, String key) {
        Integer current = counters.get(key);
        int next = current == null ? 1 : current + 1;
        counters.put(key, next);
        return next;
    }

    private static String payloadMode(String payload) {
        int index = payload == null ? -1 : payload.indexOf(';');
        if (index <= 0) {
            return payload == null || payload.isEmpty() ? "unknown" : payload;
        }
        return payload.substring(0, index);
    }

    private static String sanitizeMode(String mode) {
        return mode.toLowerCase(Locale.US).replaceAll("[^a-z0-9_]+", "_");
    }

    private static NetworkRequest.Builder baseNetworkRequestBuilder() {
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        try {
            builder.clearCapabilities();
        } catch (Throwable ignored) {
            removeCapabilityIfPresent(builder, "NET_CAPABILITY_INTERNET");
            removeCapabilityIfPresent(builder, "NET_CAPABILITY_TRUSTED");
            removeCapabilityIfPresent(builder, "NET_CAPABILITY_NOT_RESTRICTED");
            removeCapabilityIfPresent(builder, "NET_CAPABILITY_NOT_VPN");
        }
        return builder;
    }

    private static void removeCapabilityIfPresent(NetworkRequest.Builder builder, String fieldName) {
        int capability = capabilityByName(fieldName);
        if (capability < 0) {
            return;
        }
        try {
            builder.removeCapability(capability);
        } catch (Throwable ignored) {
        }
    }

    private static int capabilityByName(String fieldName) {
        try {
            return NetworkCapabilities.class.getField(fieldName).getInt(null);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static boolean hasCapabilityByName(NetworkCapabilities capabilities, String fieldName) {
        int capability = capabilityByName(fieldName);
        return capabilities != null && capability >= 0 && capabilities.hasCapability(capability);
    }

    private static String addressText(InetAddress address) {
        return address == null ? "" : address.getHostAddress();
    }

    private int delayedUdpDelayMs() {
        return Math.max(0, config.q2qAppBoundSocketMatrixDelayedUdpDelayMs);
    }

    private boolean delayedUdpEnabled() {
        return delayedUdpDelayMs() > 0;
    }

    private int tcpTunnelPort() {
        return config.q2qAppBoundSocketMatrixPort + TCP_TUNNEL_PORT_OFFSET;
    }

    private int tcpTunnelStreamPort() {
        return config.q2qAppBoundSocketMatrixPort + TCP_TUNNEL_STREAM_PORT_OFFSET;
    }

    private int tcpTunnelStreamSeconds() {
        return Math.max(0, config.q2qAppBoundSocketMatrixTcpTunnelStreamSeconds);
    }

    private long tcpTunnelStreamDurationMs() {
        return Math.max(1, tcpTunnelStreamSeconds()) * 1000L;
    }

    private int tcpTunnelStreamBytesPerDirection() {
        return Math.max(
                0,
                Math.min(
                        config.q2qAppBoundSocketMatrixTcpTunnelStreamBytesPerDirection,
                        TCP_TUNNEL_STREAM_MAX_BYTES_PER_DIRECTION));
    }

    private int tcpTunnelStreamChunkBytes(int targetBytes) {
        return Math.max(1, Math.min(TCP_TUNNEL_STREAM_CHUNK_BYTES, Math.max(1, targetBytes)));
    }

    private boolean tcpTunnelStreamEnabled() {
        return tcpTunnelStreamSeconds() > 0 && tcpTunnelStreamBytesPerDirection() > 0;
    }

    private long traceOnlyHoldMs() {
        return Math.max(8_000L, Math.min(20_000L, Math.max(3, config.socketTimeoutSeconds) * 1000L));
    }

    private int udpReceiveWindowMs() {
        int receiveWindowMs = UDP_RECEIVE_MS
                + delayedUdpDelayMs()
                + (delayedUdpEnabled() ? DELAYED_UDP_RECEIVE_GRACE_MS : 0);
        if (config.isQ2qAppNetworkTraceOnly() && localP2pUdpVariantRequested()) {
            receiveWindowMs = Math.max(receiveWindowMs, traceOnlyVariantReceiveWindowMs());
        }
        return receiveWindowMs;
    }

    private int tcpReceiveWindowMs() {
        if (config.isQ2qAppNetworkTraceOnly() && config.hasTcpBindingVariants()) {
            return Math.max(TCP_RECEIVE_MS, traceOnlyVariantReceiveWindowMs());
        }
        return TCP_RECEIVE_MS;
    }

    private int traceOnlyVariantReceiveWindowMs() {
        return 45_000 + Math.max(0, config.q2qTcpBindingVariantDelayMs);
    }

    private boolean localP2pUdpVariantRequested() {
        return config.tcpBindingVariantRequested(UDP_LOCAL_P2P_BIND_MODE)
                || config.tcpBindingVariantRequested("local_p2p_udp")
                || config.tcpBindingVariantRequested("udp_p2p0_bind")
                || config.tcpBindingVariantRequested("p2p0_udp_bind");
    }

    private boolean localP2pTcpVariantRequested() {
        return config.tcpBindingVariantRequested(TCP_LOCAL_P2P_BIND_MODE)
                || config.tcpBindingVariantRequested("local_p2p_tcp")
                || config.tcpBindingVariantRequested("tcp_p2p0_bind")
                || config.tcpBindingVariantRequested("p2p0_tcp_bind");
    }

    private boolean localP2pTcpStreamVariantRequested() {
        return config.tcpBindingVariantRequested(TCP_LOCAL_P2P_BIND_STREAM_MODE)
                || config.tcpBindingVariantRequested("local_p2p_tcp_stream")
                || config.tcpBindingVariantRequested("tcp_p2p0_bind_stream")
                || config.tcpBindingVariantRequested("p2p0_tcp_stream_bind");
    }

    private static String readAsciiLine(InputStream input, int maxBytes) throws IOException {
        StringBuilder builder = new StringBuilder();
        int limit = Math.max(1, maxBytes);
        while (builder.length() < limit) {
            int value = input.read();
            if (value < 0) {
                break;
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                builder.append((char) value);
            }
        }
        return builder.toString();
    }

    private static int boundedLineInt(String line, String key, int fallback) {
        return boundedLineInt(line, key, fallback, Math.max(fallback, TCP_TUNNEL_BYTES_PER_DIRECTION * 4));
    }

    private static int boundedLineInt(String line, String key, int fallback, int maxValue) {
        if (line == null || line.isEmpty() || key == null || key.isEmpty()) {
            return fallback;
        }
        String prefix = key + "=";
        String[] parts = line.split(";");
        for (String part : parts) {
            if (!part.startsWith(prefix)) {
                continue;
            }
            try {
                int value = Integer.parseInt(part.substring(prefix.length()));
                return Math.max(1, Math.min(value, Math.max(1, maxValue)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long writePatternedBytes(OutputStream output, int bytes, int salt) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[TCP_TUNNEL_BUFFER_BYTES];
        int remaining = Math.max(0, bytes);
        int offset = 0;
        while (remaining > 0) {
            int chunk = Math.min(buffer.length, remaining);
            for (int index = 0; index < chunk; index++) {
                buffer[index] = patternedByte(offset + index, salt);
            }
            output.write(buffer, 0, chunk);
            crc.update(buffer, 0, chunk);
            offset += chunk;
            remaining -= chunk;
        }
        return crc.getValue();
    }

    private static ByteTransferStats readExactBytesWithCrc(InputStream input, int bytes) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[TCP_TUNNEL_BUFFER_BYTES];
        int remaining = Math.max(0, bytes);
        int total = 0;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            crc.update(buffer, 0, read);
            total += read;
            remaining -= read;
        }
        return new ByteTransferStats(total, crc.getValue());
    }

    private static void writePatternedBytes(
            OutputStream output,
            int offset,
            int bytes,
            int salt,
            CRC32 crc) throws IOException {
        byte[] buffer = new byte[TCP_TUNNEL_BUFFER_BYTES];
        int remaining = Math.max(0, bytes);
        int currentOffset = offset;
        while (remaining > 0) {
            int chunk = Math.min(buffer.length, remaining);
            for (int index = 0; index < chunk; index++) {
                buffer[index] = patternedByte(currentOffset + index, salt);
            }
            output.write(buffer, 0, chunk);
            crc.update(buffer, 0, chunk);
            currentOffset += chunk;
            remaining -= chunk;
        }
    }

    private static int readExactBytesWithCrc(InputStream input, int bytes, CRC32 crc) throws IOException {
        byte[] buffer = new byte[TCP_TUNNEL_BUFFER_BYTES];
        int remaining = Math.max(0, bytes);
        int total = 0;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            crc.update(buffer, 0, read);
            total += read;
            remaining -= read;
        }
        return total;
    }

    private static long patternedCrc(int bytes, int salt) {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[TCP_TUNNEL_BUFFER_BYTES];
        int remaining = Math.max(0, bytes);
        int offset = 0;
        while (remaining > 0) {
            int chunk = Math.min(buffer.length, remaining);
            for (int index = 0; index < chunk; index++) {
                buffer[index] = patternedByte(offset + index, salt);
            }
            crc.update(buffer, 0, chunk);
            offset += chunk;
            remaining -= chunk;
        }
        return crc.getValue();
    }

    private static byte patternedByte(int index, int salt) {
        return (byte) ((index * 31 + salt) & 0xff);
    }

    private static String errorText(Exception ex) {
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    private static String nativeErrorText(JSONObject report) {
        String status = report.optString("status", "fail");
        String issue = report.optString("issue", "");
        String setsockError = report.optString("setsocknetwork_error", "");
        String connectError = report.optString("connect_error", "");
        String writeError = report.optString("write_error", "");
        String sendError = report.optString("last_send_error", "");
        if (!setsockError.isEmpty()) {
            return status + ": " + setsockError;
        }
        if (!connectError.isEmpty()) {
            return status + ": " + connectError;
        }
        if (!writeError.isEmpty()) {
            return status + ": " + writeError;
        }
        if (!sendError.isEmpty()) {
            return status + ": " + sendError;
        }
        return issue.isEmpty() ? status : status + ": " + issue;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        Qcl041WifiDirectNetworkBinder.closeQuietly(socket);
    }

    private static void closeQuietly(ServerSocket socket) {
        Qcl041WifiDirectNetworkBinder.closeQuietly(socket);
    }

    private static void closeQuietly(DatagramSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private static final class ByteTransferStats {
        final int bytes;
        final long crc32;

        ByteTransferStats(int bytes, long crc32) {
            this.bytes = bytes;
            this.crc32 = crc32;
        }
    }
}
