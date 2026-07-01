package io.github.mesmerprism.rustyquest.qcl041;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;

final class Qcl041WifiDirectLifecycle {
    interface StatusListener {
        void onStatus(String status);
    }

    private final Context context;
    private final Qcl041ProbeConfig config;
    private final Qcl041LifecycleArtifact artifact;
    private final StatusListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;
    private boolean connectStarted;
    private boolean socketStarted;
    private boolean cleanupStarted;
    private long groupFormationStartMs;

    Qcl041WifiDirectLifecycle(
            Context context,
            Qcl041ProbeConfig config,
            Qcl041LifecycleArtifact artifact,
            StatusListener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.artifact = artifact;
        this.listener = listener;
    }

    void start() {
        updateStatus("starting QCL-041 Wi-Fi Direct lifecycle");
        recordFeatureState();
        boolean permissionsReady = recordPermissionState();
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            artifact.recordFailure("feature", "WifiP2pManager service unavailable");
            finishWithCleanup("WifiP2pManager service unavailable");
            return;
        }
        channel = manager.initialize(context, Looper.getMainLooper(), new WifiP2pManager.ChannelListener() {
            @Override
            public void onChannelDisconnected() {
                artifact.recordFailure("channel", "WifiP2pManager channel disconnected");
                finishWithCleanup("WifiP2pManager channel disconnected");
            }
        });
        if (channel == null) {
            artifact.recordFailure("feature", "WifiP2pManager.initialize returned null channel");
            finishWithCleanup("WifiP2pManager.initialize returned null channel");
            return;
        }
        if (!permissionsReady) {
            finishWithCleanup("Wi-Fi Direct runtime permission or Location Mode is unavailable");
            return;
        }

        registerReceiver();
        requestCurrentStateAtStartup();
        startPeerDiscovery();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!cleanupStarted && !socketStarted) {
                    artifact.recordFailure("timeout", "QCL-041 Wi-Fi Direct lifecycle timed out");
                    finishWithCleanup("QCL-041 Wi-Fi Direct lifecycle timed out");
                }
            }
        }, Math.max(5, config.timeoutSeconds) * 1000L);
    }

    void stop() {
        unregisterReceiver();
    }

    private void recordFeatureState() {
        boolean packageFeature = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
        Boolean wifiManagerP2pSupported = null;
        Object service = context.getSystemService(Context.WIFI_SERVICE);
        if (service instanceof WifiManager) {
            wifiManagerP2pSupported = invokeIsP2pSupported((WifiManager) service);
        }
        artifact.setFeature(packageFeature, wifiManagerP2pSupported);
    }

    private Boolean invokeIsP2pSupported(WifiManager wifiManager) {
        try {
            Method method = WifiManager.class.getMethod("isP2pSupported");
            Object result = method.invoke(wifiManager);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Exception ex) {
            artifact.diagnostic("preflight", "wifi_manager_is_p2p_supported_error", ex.getMessage());
            return null;
        }
    }

    private boolean recordPermissionState() {
        boolean granted = hasRuntimeWifiDirectPermission();
        boolean locationModeEnabled = isLocationModeEnabled();
        String permissionName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        artifact.setPermissionState(
                granted,
                locationModeEnabled,
                permissionName + "=" + granted + "; LocationMode=" + locationModeEnabled);
        return granted && locationModeEnabled;
    }

    private boolean hasRuntimeWifiDirectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationModeEnabled() {
        try {
            LocationManager locationManager =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return locationManager.isLocationEnabled();
            }
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
            artifact.diagnostic("permissions", "location_mode_error", ex.getMessage());
            return false;
        }
    }

    private void registerReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent == null ? "" : intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    artifact.diagnostic(
                            "lifecycle",
                            "wifi_p2p_state_enabled",
                            state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    NetworkInfo networkInfo =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (networkInfo != null && networkInfo.isConnected()) {
                        requestConnectionInfo();
                    }
                } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                    WifiP2pDevice thisDevice =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    if (thisDevice != null) {
                        artifact.diagnostic("lifecycle", "this_device_name", thisDevice.deviceName);
                        artifact.diagnostic("lifecycle", "this_device_status", thisDevice.status);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
                // Receiver cleanup must not hide the lifecycle artifact.
            }
            receiverRegistered = false;
        }
    }

    private void requestCurrentStateAtStartup() {
        requestPeers();
        requestConnectionInfo();
        try {
            manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    if (group != null) {
                        artifact.diagnostic("lifecycle", "startup_group_network_name", group.getNetworkName());
                    }
                }
            });
        } catch (SecurityException ex) {
            artifact.recordFailure("requestGroupInfo", ex.getMessage());
        }
    }

    private void startPeerDiscovery() {
        updateStatus("discovering Windows Wi-Fi Direct peer");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                artifact.diagnostic("lifecycle", "discoverPeers", "success");
                requestPeers();
            }

            @Override
            public void onFailure(int reason) {
                artifact.recordFailure("discoverPeers", "discoverPeers failed reason=" + reason);
                artifact.setPeerDiscovery(0, "discoverPeers failed reason=" + reason);
                finishWithCleanup("discoverPeers failed reason=" + reason);
            }
        });
    }

    private void requestPeers() {
        if (manager == null || channel == null) {
            return;
        }
        try {
            manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                @Override
                public void onPeersAvailable(WifiP2pDeviceList peers) {
                    handlePeers(peers);
                }
            });
        } catch (SecurityException ex) {
            artifact.recordFailure("requestPeers", ex.getMessage());
        }
    }

    private void handlePeers(WifiP2pDeviceList peers) {
        Collection<WifiP2pDevice> devices = peers == null ? null : peers.getDeviceList();
        int count = devices == null ? 0 : devices.size();
        artifact.setPeerDiscovery(count, "Wi-Fi Direct peer discovery observed peer_count=" + count);
        if (count == 0 || connectStarted) {
            return;
        }
        WifiP2pDevice selected = selectWindowsPeer(devices);
        if (selected == null) {
            artifact.recordFailure(
                    "peer_discovery",
                    "No peer matched qcl041.windows_peer_name_contains=" + config.windowsPeerNameContains);
            return;
        }
        artifact.diagnostic("windows_peer", "selected_device_name", selected.deviceName);
        artifact.diagnostic("windows_peer", "selected_device_status", selected.status);
        connectToPeer(selected);
    }

    private WifiP2pDevice selectWindowsPeer(Collection<WifiP2pDevice> devices) {
        String needle = config.windowsPeerNameContains.toLowerCase(Locale.US);
        WifiP2pDevice first = null;
        for (WifiP2pDevice device : devices) {
            if (first == null) {
                first = device;
            }
            String label = (device.deviceName == null ? "" : device.deviceName).toLowerCase(Locale.US);
            if (!needle.isEmpty() && label.contains(needle)) {
                return device;
            }
        }
        return needle.isEmpty() ? first : null;
    }

    private void connectToPeer(WifiP2pDevice peer) {
        connectStarted = true;
        groupFormationStartMs = SystemClock.elapsedRealtime();
        updateStatus("connecting to Windows Wi-Fi Direct peer");
        WifiP2pConfig peerConfig = new WifiP2pConfig();
        peerConfig.deviceAddress = peer.deviceAddress;
        peerConfig.groupOwnerIntent = Math.max(0, Math.min(15, config.groupOwnerIntent));
        peerConfig.wps.setup = WpsInfo.PBC;
        try {
            manager.connect(channel, peerConfig, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    artifact.diagnostic("lifecycle", "connect", "accepted");
                }

                @Override
                public void onFailure(int reason) {
                    artifact.setGroupFormation(
                            false,
                            false,
                            null,
                            -1,
                            "WifiP2pManager.connect failed reason=" + reason);
                    finishWithCleanup("WifiP2pManager.connect failed reason=" + reason);
                }
            });
        } catch (SecurityException ex) {
            artifact.recordFailure("connect", ex.getMessage());
            finishWithCleanup("WifiP2pManager.connect threw SecurityException");
        }
    }

    private void requestConnectionInfo() {
        if (manager == null || channel == null) {
            return;
        }
        try {
            manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                @Override
                public void onConnectionInfoAvailable(WifiP2pInfo info) {
                    handleConnectionInfo(info);
                }
            });
        } catch (SecurityException ex) {
            artifact.recordFailure("requestConnectionInfo", ex.getMessage());
        }
    }

    private void handleConnectionInfo(WifiP2pInfo info) {
        if (info == null || !info.groupFormed || socketStarted) {
            return;
        }
        long elapsedMs = groupFormationStartMs == 0
                ? -1
                : SystemClock.elapsedRealtime() - groupFormationStartMs;
        String ownerAddress = info.groupOwnerAddress == null
                ? null
                : info.groupOwnerAddress.getHostAddress();
        artifact.setGroupFormation(
                true,
                info.isGroupOwner,
                ownerAddress,
                elapsedMs,
                "Wi-Fi Direct group formed; quest_group_owner=" + info.isGroupOwner);
        requestGroupInfoAfterFormation();

        if (info.isGroupOwner && !config.allowQuestGroupOwner) {
            artifact.setSocketExchange(
                    false,
                    0,
                    0,
                    null,
                    "Quest became group owner; first QCL-041 harness requires Windows group owner/TCP server.");
            finishWithCleanup("Quest became group owner; Windows helper TCP server is unreachable in this mode");
            return;
        }
        if (info.groupOwnerAddress == null) {
            artifact.setSocketExchange(false, 0, 0, null, "groupOwnerAddress was null");
            finishWithCleanup("groupOwnerAddress was null");
            return;
        }
        if (config.qcl082RelayEnabled) {
            socketStarted = true;
            Network wifiDirectNetwork = findWifiDirectNetwork(info.groupOwnerAddress);
            artifact.setSocketExchange(
                    false,
                    0,
                    0,
                    null,
                    "QCL-041 bounded TCP probe skipped because QCL-082 media relay owns the first Wi-Fi Direct socket.");
            artifact.diagnostic("qcl082_relay", "started_before_socket_probe", true);
            startQcl082MediaRelay(wifiDirectNetwork, info.groupOwnerAddress);
            finishAfterSocketExchange();
            return;
        }
        startSocketExchange(info);
    }

    private void requestGroupInfoAfterFormation() {
        try {
            manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    if (group != null) {
                        artifact.diagnostic("lifecycle", "group_network_name", group.getNetworkName());
                        WifiP2pDevice owner = group.getOwner();
                        if (owner != null) {
                            artifact.diagnostic("lifecycle", "group_owner_device_name", owner.deviceName);
                        }
                    }
                }
            });
        } catch (SecurityException ex) {
            artifact.recordFailure("requestGroupInfo", ex.getMessage());
        }
    }

    private void startSocketExchange(final WifiP2pInfo info) {
        socketStarted = true;
        updateStatus("running bounded TCP probe");
        new Thread(new Runnable() {
            @Override
            public void run() {
                long started = SystemClock.elapsedRealtime();
                int sent = 0;
                int received = 0;
                Network wifiDirectNetwork = findWifiDirectNetwork(info.groupOwnerAddress);
                boolean qcl082RelayStarted = false;
                try (Socket socket = createSocketForWifiDirectNetwork(wifiDirectNetwork)) {
                    bindSocketToWifiDirectLocalAddress(socket, info.groupOwnerAddress);
                    socket.connect(
                            new InetSocketAddress(info.groupOwnerAddress, config.listenPort),
                            Math.max(3, config.socketTimeoutSeconds) * 1000);
                    long connectMs = SystemClock.elapsedRealtime() - started;
                    socket.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8));
                    writer.write(config.socketPayload);
                    writer.write("\n");
                    writer.flush();
                    sent = 1;
                    String response = reader.readLine();
                    if (response != null && !response.isEmpty()) {
                        received = 1;
                    }
                    artifact.setSocketExchange(
                            sent > 0 && received > 0,
                            sent,
                            received,
                            connectMs,
                            "Bounded TCP request/ack completed against Windows group owner.");
                    if (sent > 0 && received > 0 && config.qcl081LslEnabled) {
                        publishQcl081Lsl(wifiDirectNetwork, info.groupOwnerAddress);
                    }
                    if (sent > 0 && received > 0 && config.qcl082RelayEnabled) {
                        startQcl082MediaRelay(wifiDirectNetwork, info.groupOwnerAddress);
                        qcl082RelayStarted = true;
                    }
                } catch (Exception ex) {
                    artifact.setSocketExchange(
                            false,
                            sent,
                            received,
                            null,
                            "Bounded TCP probe failed: " + ex.getMessage());
                    if (config.qcl082RelayEnabled && !qcl082RelayStarted) {
                        artifact.diagnostic(
                                "qcl082_relay",
                                "started_after_socket_probe_status",
                                "blocked");
                        startQcl082MediaRelay(wifiDirectNetwork, info.groupOwnerAddress);
                    }
                } finally {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            finishAfterSocketExchange();
                        }
                    });
                }
            }
        }, "qcl041-wifi-direct-socket").start();
    }

    private void startQcl082MediaRelay(final Network wifiDirectNetwork, final InetAddress groupOwnerAddress) {
        final InetAddress relayLocalAddress = findWifiDirectLocalAddress(groupOwnerAddress);
        artifact.diagnostic("qcl082_relay", "enabled", true);
        artifact.diagnostic("qcl082_relay", "source_host", config.qcl082RelaySourceHost);
        artifact.diagnostic("qcl082_relay", "source_port", config.qcl082RelaySourcePort);
        artifact.diagnostic("qcl082_relay", "receiver_host", config.qcl082RelayReceiverHost);
        artifact.diagnostic("qcl082_relay", "receiver_port", config.qcl082RelayReceiverPort);
        artifact.diagnostic("qcl082_relay", "socket_owner", "qcl041_wifi_direct_harness");
        artifact.diagnostic("qcl082_relay", "source_owner", "rusty_manifold_broker_media_stream_runtime");
        if (relayLocalAddress != null) {
            artifact.diagnostic("qcl082_relay", "cached_local_bind_address", relayLocalAddress.getHostAddress());
        }
        artifact.diagnostic("qcl082_relay", "start_delay_ms", Math.max(0, config.qcl082RelayStartDelayMs));
        artifact.writeQuietly();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runQcl082MediaRelay(wifiDirectNetwork, groupOwnerAddress, relayLocalAddress);
                    }
                }, "qcl082-wifi-direct-media-relay").start();
            }
        }, Math.max(0, config.qcl082RelayStartDelayMs));
    }

    private void runQcl082MediaRelay(
            Network wifiDirectNetwork,
            InetAddress groupOwnerAddress,
            InetAddress relayLocalAddress) {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(5, config.qcl082RelayTimeoutSeconds) * 1000L;
        long bytesCopied = 0L;
        Socket source = null;
        Socket receiver = null;
        try {
            source = connectSourceWithRetry(deadline);
            artifact.diagnostic("qcl082_relay", "source_connected_before_receiver", true);
            receiver = connectReceiverWithRetry(
                    deadline,
                    wifiDirectNetwork,
                    relayLocalAddress,
                    groupOwnerAddress);
            source.setTcpNoDelay(true);
            receiver.setTcpNoDelay(true);
            bytesCopied = copyMediaBytes(
                    source.getInputStream(),
                    receiver.getOutputStream(),
                    Math.max(1, config.qcl082RelayMaxBytes));
            artifact.diagnostic("qcl082_relay", "status", bytesCopied > 0 ? "pass" : "blocked");
            artifact.diagnostic("qcl082_relay", "bytes_copied", bytesCopied);
            artifact.diagnostic("qcl082_relay", "elapsed_ms", SystemClock.elapsedRealtime() - started);
        } catch (Exception ex) {
            artifact.diagnostic("qcl082_relay", "status", bytesCopied > 0 ? "pass_with_peer_close" : "blocked");
            artifact.diagnostic("qcl082_relay", "bytes_copied", bytesCopied);
            artifact.diagnostic("qcl082_relay", "error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            artifact.diagnostic("qcl082_relay", "elapsed_ms", SystemClock.elapsedRealtime() - started);
        } finally {
            closeQuietly(source);
            closeQuietly(receiver);
            artifact.writeQuietly();
        }
    }

    private Socket connectReceiverWithRetry(
            long deadlineMs,
            Network wifiDirectNetwork,
            InetAddress relayLocalAddress,
            InetAddress groupOwnerAddress) throws IOException, InterruptedException {
        IOException last = null;
        int attempts = 0;
        InetAddress receiverAddress = InetAddress.getByName(config.qcl082RelayReceiverHost);
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            attempts++;
            ReceiverSocketCandidate candidate = createQcl082ReceiverSocket(wifiDirectNetwork);
            Socket socket = candidate.socket;
            try {
                if (candidate.createdFromWifiDirectNetwork) {
                    artifact.diagnostic(
                            "qcl082_relay",
                            "receiver_socket_local_address_bind_skipped",
                            "socket created from selected Wi-Fi Direct Network");
                } else if (relayLocalAddress != null) {
                    bindSocketToSpecificLocalAddress(socket, relayLocalAddress, groupOwnerAddress, "qcl082_relay");
                } else {
                    bindSocketToWifiDirectLocalAddress(socket, groupOwnerAddress);
                }
                socket.connect(
                        new InetSocketAddress(receiverAddress, config.qcl082RelayReceiverPort),
                        1000);
                InetAddress connectedLocalAddress = socket.getLocalAddress();
                if (connectedLocalAddress != null) {
                    artifact.diagnostic(
                            "qcl082_relay",
                            "receiver_connected_local_address",
                            connectedLocalAddress.getHostAddress());
                }
                if (!sameIpv4Slash24(connectedLocalAddress, receiverAddress)) {
                    throw new IOException(
                            "receiver connected from non Wi-Fi Direct local address "
                                    + (connectedLocalAddress == null
                                    ? "unknown"
                                    : connectedLocalAddress.getHostAddress()));
                }
                artifact.diagnostic("qcl082_relay", "receiver_connect_attempts", attempts);
                artifact.diagnostic("qcl082_relay", "receiver_connected", true);
                return socket;
            } catch (IOException ex) {
                last = ex;
                closeQuietly(socket);
                Thread.sleep(250L);
            }
        }
        artifact.diagnostic("qcl082_relay", "receiver_connect_attempts", attempts);
        throw last == null ? new IOException("receiver connect timed out") : last;
    }

    private ReceiverSocketCandidate createQcl082ReceiverSocket(Network wifiDirectNetwork) throws IOException {
        if (wifiDirectNetwork == null) {
            artifact.diagnostic("qcl082_relay", "receiver_socket_created_from_wifi_direct_network", false);
            artifact.diagnostic("qcl082_relay", "receiver_socket_bound_to_wifi_direct_network", false);
            return new ReceiverSocketCandidate(new Socket(), false);
        }
        try {
            Socket socket = wifiDirectNetwork.getSocketFactory().createSocket();
            artifact.diagnostic("qcl082_relay", "receiver_socket_created_from_wifi_direct_network", true);
            artifact.diagnostic("qcl082_relay", "receiver_socket_bound_to_wifi_direct_network", true);
            return new ReceiverSocketCandidate(socket, true);
        } catch (IOException ex) {
            artifact.diagnostic("qcl082_relay", "receiver_socket_create_from_network_error", ex.getMessage());
            artifact.diagnostic("qcl082_relay", "receiver_socket_created_from_wifi_direct_network", false);
            artifact.diagnostic("qcl082_relay", "receiver_socket_bound_to_wifi_direct_network", false);
            artifact.diagnostic("qcl082_relay", "receiver_socket_network_factory_fallback", "local_address_bind");
            return new ReceiverSocketCandidate(new Socket(), false);
        }
    }

    private static final class ReceiverSocketCandidate {
        final Socket socket;
        final boolean createdFromWifiDirectNetwork;

        ReceiverSocketCandidate(Socket socket, boolean createdFromWifiDirectNetwork) {
            this.socket = socket;
            this.createdFromWifiDirectNetwork = createdFromWifiDirectNetwork;
        }
    }

    private Socket connectSourceWithRetry(long deadlineMs) throws IOException, InterruptedException {
        IOException last = null;
        int attempts = 0;
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            attempts++;
            Socket socket = new Socket();
            try {
                socket.connect(
                        new InetSocketAddress(config.qcl082RelaySourceHost, config.qcl082RelaySourcePort),
                        1000);
                artifact.diagnostic("qcl082_relay", "source_connect_attempts", attempts);
                artifact.diagnostic("qcl082_relay", "source_connected", true);
                return socket;
            } catch (IOException ex) {
                last = ex;
                closeQuietly(socket);
                Thread.sleep(250L);
            }
        }
        artifact.diagnostic("qcl082_relay", "source_connect_attempts", attempts);
        throw last == null ? new IOException("source connect timed out") : last;
    }

    private long copyMediaBytes(InputStream input, OutputStream output, int maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        while (total < maxBytes) {
            int limit = (int) Math.min(buffer.length, maxBytes - total);
            int read = input.read(buffer, 0, limit);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            try {
                output.write(buffer, 0, read);
                output.flush();
                total += read;
            } catch (IOException ex) {
                if (total > 0L) {
                    return total;
                }
                throw ex;
            }
        }
        return total;
    }

    private void finishAfterSocketExchange() {
        final int holdMs = Math.max(0, config.holdAfterSocketMs);
        if (holdMs <= 0) {
            finishWithCleanup("socket exchange finished");
            return;
        }
        artifact.diagnostic("dependent_live_steps", "hold_after_socket_ms", holdMs);
        artifact.diagnostic("dependent_live_steps", "hold_started_before_cleanup", true);
        artifact.diagnostic("dependent_live_steps", "intended_consumer", "QCL-082 product media receiver capture");
        artifact.writeQuietly();
        updateStatus("holding Wi-Fi Direct group for dependent live steps");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                artifact.diagnostic("dependent_live_steps", "hold_completed_before_cleanup", true);
                artifact.writeQuietly();
                finishWithCleanup("socket exchange finished after dependent live-step hold");
            }
        }, holdMs);
    }

    private void publishQcl081Lsl(Network wifiDirectNetwork, InetAddress groupOwnerAddress) {
        artifact.diagnostic("qcl081_lsl", "enabled", true);
        artifact.diagnostic("qcl081_lsl", "backend", config.qcl081LslBackend);
        artifact.diagnostic("qcl081_lsl", "stream_name", config.qcl081LslStreamName);
        artifact.diagnostic("qcl081_lsl", "stream_type", config.qcl081LslStreamType);
        artifact.diagnostic("qcl081_lsl", "source_id", config.qcl081LslSourceId);
        if (groupOwnerAddress != null) {
            artifact.diagnostic("qcl081_lsl", "windows_group_owner_address", groupOwnerAddress.getHostAddress());
        }
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean processBound = false;
        if (wifiDirectNetwork != null && connectivityManager != null) {
            try {
                processBound = connectivityManager.bindProcessToNetwork(wifiDirectNetwork);
                artifact.diagnostic("qcl081_lsl", "process_bound_to_wifi_direct_network", processBound);
            } catch (Exception ex) {
                artifact.diagnostic("qcl081_lsl", "process_bind_to_wifi_direct_network_error", ex.getMessage());
            }
        } else {
            artifact.diagnostic("qcl081_lsl", "process_bound_to_wifi_direct_network", false);
        }

        Qcl081LslNativeBridge.LoadState loadState = Qcl081LslNativeBridge.runtimeState();
        artifact.diagnostic("qcl081_lsl", "native_runtime_available", loadState.available);
        artifact.diagnostic("qcl081_lsl", "native_runtime_detail", loadState.detail);
        try {
            JSONObject report = Qcl081LslNativeBridge.publishSamples(
                    config.qcl081LslStreamName,
                    config.qcl081LslStreamType,
                    config.qcl081LslSourceId,
                    Math.max(1, config.qcl081LslSampleCount),
                    Math.max(0, config.qcl081LslWarmupMs),
                    Math.max(1, config.qcl081LslIntervalMs));
            artifact.diagnostic("qcl081_lsl", "publish_report", report);
            artifact.diagnostic("qcl081_lsl", "publish_status", report.optString("status", "blocked"));
            artifact.diagnostic("qcl081_lsl", "samples_published", report.optInt("samples_published", 0));
        } catch (Exception ex) {
            artifact.diagnostic("qcl081_lsl", "publish_error", ex.getMessage());
        } finally {
            if (connectivityManager != null && processBound) {
                try {
                    connectivityManager.bindProcessToNetwork(null);
                    artifact.diagnostic("qcl081_lsl", "process_unbound_after_publish", true);
                } catch (Exception ex) {
                    artifact.diagnostic("qcl081_lsl", "process_unbind_error", ex.getMessage());
                }
            }
        }
    }

    private Socket createSocketForWifiDirectNetwork(Network network) throws IOException {
        if (network == null) {
            artifact.diagnostic("lifecycle", "socket_created_from_wifi_direct_network", false);
            artifact.diagnostic("lifecycle", "socket_bound_to_wifi_direct_network", false);
            return new Socket();
        }
        try {
            Socket socket = network.getSocketFactory().createSocket();
            artifact.diagnostic("lifecycle", "socket_created_from_wifi_direct_network", true);
            artifact.diagnostic("lifecycle", "socket_bound_to_wifi_direct_network", true);
            return socket;
        } catch (IOException ex) {
            artifact.diagnostic("lifecycle", "socket_create_from_network_error", ex.getMessage());
            artifact.diagnostic("lifecycle", "socket_bound_to_wifi_direct_network", false);
            throw ex;
        }
    }

    private void bindSocketToWifiDirectLocalAddress(Socket socket, InetAddress groupOwnerAddress) {
        InetAddress localAddress = findWifiDirectLocalAddress(groupOwnerAddress);
        if (localAddress != null) {
            bindSocketToSpecificLocalAddress(socket, localAddress, groupOwnerAddress, "lifecycle");
        }
    }

    private void bindSocketToSpecificLocalAddress(
            Socket socket,
            InetAddress localAddress,
            InetAddress groupOwnerAddress,
            String diagnosticGroup) {
        try {
            socket.bind(new InetSocketAddress(localAddress, 0));
            artifact.diagnostic(
                    diagnosticGroup,
                    "socket_bound_to_wifi_direct_local_address",
                    localAddress.getHostAddress());
            artifact.diagnostic(
                    diagnosticGroup,
                    "wifi_direct_local_address_same_subnet",
                    sameIpv4Slash24(localAddress, groupOwnerAddress));
        } catch (Exception ex) {
            artifact.diagnostic(diagnosticGroup, "socket_bind_local_address_error", ex.getMessage());
        }
    }

    private Network findWifiDirectNetwork(InetAddress groupOwnerAddress) {
        long started = SystemClock.elapsedRealtime();
        int attempts = 0;
        while (attempts < 20) {
            attempts++;
            Network network = findWifiDirectNetworkOnce(groupOwnerAddress);
            if (network != null) {
                artifact.diagnostic("lifecycle", "wifi_direct_network_wait_attempts", attempts);
                artifact.diagnostic(
                        "lifecycle",
                        "wifi_direct_network_wait_elapsed_ms",
                        SystemClock.elapsedRealtime() - started);
                return network;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        artifact.diagnostic("lifecycle", "wifi_direct_network_wait_attempts", attempts);
        artifact.diagnostic(
                "lifecycle",
                "wifi_direct_network_wait_elapsed_ms",
                SystemClock.elapsedRealtime() - started);
        artifact.diagnostic("lifecycle", "wifi_direct_network_found", false);
        return null;
    }

    private Network findWifiDirectNetworkOnce(InetAddress groupOwnerAddress) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            artifact.diagnostic("lifecycle", "connectivity_manager_available", false);
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
                    if (groupOwnerAddress != null && route.matches(groupOwnerAddress)) {
                        routeMatches = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // Route inspection is diagnostic; keep scanning other routes.
                }
            }
            boolean wifiTransport = capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            artifact.diagnostic(
                    "lifecycle",
                    "network_candidate_" + (interfaceName == null ? "unknown" : interfaceName),
                    "p2p=" + p2pInterface + "; routeMatches=" + routeMatches + "; wifi=" + wifiTransport);
            if (p2pInterface) {
                artifact.diagnostic("lifecycle", "wifi_direct_network_interface", interfaceName);
                artifact.diagnostic("lifecycle", "wifi_direct_network_route_matches_group_owner", routeMatches);
                return network;
            }
        }
        return null;
    }

    private InetAddress findWifiDirectLocalAddress(InetAddress groupOwnerAddress) {
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
                    boolean sameSubnet = sameIpv4Slash24(address, groupOwnerAddress);
                    if (p2pInterface || sameSubnet) {
                        artifact.diagnostic("lifecycle", "wifi_direct_local_interface", name);
                        artifact.diagnostic("lifecycle", "wifi_direct_local_address", address.getHostAddress());
                        artifact.diagnostic("lifecycle", "wifi_direct_local_address_same_subnet", sameSubnet);
                        return address;
                    }
                }
            }
        } catch (Exception ex) {
            artifact.diagnostic("lifecycle", "wifi_direct_local_address_error", ex.getMessage());
        }
        artifact.diagnostic("lifecycle", "wifi_direct_local_address_found", false);
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

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private void finishWithCleanup(final String reason) {
        if (cleanupStarted) {
            return;
        }
        cleanupStarted = true;
        updateStatus("cleaning up: " + reason);
        if (manager == null || channel == null) {
            artifact.setCleanup(true, true, "No WifiP2pManager channel remained open.");
            unregisterReceiver();
            return;
        }
        final boolean[] stopDone = new boolean[] { false };
        final boolean[] removeDone = new boolean[] { false };
        final boolean[] stopOk = new boolean[] { true };
        final boolean[] removeOk = new boolean[] { true };
        Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (!stopDone[0] || !removeDone[0]) {
                    return;
                }
                boolean passed = stopOk[0] && removeOk[0];
                artifact.setCleanup(
                        passed,
                        true,
                        "stopPeerDiscovery=" + stopOk[0] + "; removeGroup=" + removeOk[0]);
                unregisterReceiver();
                updateStatus("QCL-041 lifecycle complete");
            }
        };
        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                stopDone[0] = true;
                finish.run();
            }

            @Override
            public void onFailure(int reasonCode) {
                stopDone[0] = true;
                stopOk[0] = false;
                artifact.diagnostic("cleanup", "stopPeerDiscovery_reason", reasonCode);
                finish.run();
            }
        });
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                removeDone[0] = true;
                finish.run();
            }

            @Override
            public void onFailure(int reasonCode) {
                removeDone[0] = true;
                removeOk[0] = false;
                artifact.diagnostic("cleanup", "removeGroup_reason", reasonCode);
                finish.run();
            }
        });
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!stopDone[0] || !removeDone[0]) {
                    artifact.setCleanup(false, false, "Wi-Fi Direct cleanup callback timed out.");
                    unregisterReceiver();
                }
            }
        }, 5000L);
    }

    private void updateStatus(String status) {
        if (listener != null) {
            listener.onStatus(status);
        }
    }
}
