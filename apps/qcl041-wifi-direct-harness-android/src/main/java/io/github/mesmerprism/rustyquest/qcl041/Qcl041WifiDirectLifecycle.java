package io.github.mesmerprism.rustyquest.qcl041;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkInfo;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

final class Qcl041WifiDirectLifecycle {
    private static final String QCL082_P2P_FANOUT_PREFIX = "p2p-fanout-";
    private static final int QCL082_UDP_MAGIC = 0x51383255; // Q82U
    private static final int QCL082_UDP_HEADER_BYTES = 12;
    private static final int QCL082_UDP_PAYLOAD_BYTES = 1000;
    private static final int QCL082_UDP_SOCKET_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final long QCL082_UDP_SEND_PACE_MS = 2L;
    private static final long QCL082_PEER_ADDRESS_WAIT_MS = 12_000L;
    private static final long QCL082_PEER_ADDRESS_ANNOUNCE_MS = 12_000L;
    private static final long QCL082_UDP_PEER_PROOF_MS = 30_000L;
    private static final String QCL082_UDP_PEER_HELLO = "QCL082_UDP_PEER_HELLO";
    private static final String QCL082_UDP_PEER_ACK = "QCL082_UDP_PEER_ACK";
    private static final long QCL082_TCP_PEER_PROOF_MS = 5_000L;
    private static final String QCL082_TCP_PEER_HELLO = "QCL082_TCP_PEER_HELLO";
    private static final String QCL082_TCP_PEER_ACK = "QCL082_TCP_PEER_ACK";
    private static final String QCL082_CONTROL_KEEPALIVE = "QCL082_CONTROL_KEEPALIVE";
    private static final String QCL082_CONTROL_KEEPALIVE_ACK = "QCL082_CONTROL_KEEPALIVE_ACK";
    private static final long QCL082_CONTROL_KEEPALIVE_INTERVAL_MS = 1000L;
    private static final String QCL082_CONTROL_MEDIA_STREAM = "QCL082_CONTROL_MEDIA_STREAM";
    private static final String QCL082_CONTROL_MEDIA_STREAM_ACK = "QCL082_CONTROL_MEDIA_STREAM_ACK";
    private static final int QCL082_CONTROL_MEDIA_STREAM_MAX_BYTES_PER_DIRECTION = 64 * 1024 * 1024;
    private static final int QCL082_CONTROL_MEDIA_STREAM_MAX_CHUNK_BYTES = 16 * 1024;
    private static final int QCL082_CONTROL_MEDIA_STREAM_CLIENT_SALT = 0x41;
    private static final int QCL082_CONTROL_MEDIA_STREAM_OWNER_SALT = 0x53;
    private static final String QCL082_DEFERRED_TARGET_MISSING_HOST =
            "qcl082-deferred-target-missing.invalid";
    private static final int Q2Q_CONNECT_MAX_ATTEMPTS = 5;
    private static final long Q2Q_CONNECT_RETRY_DELAY_MS = 70_000L;
    private static final int Q2Q_REMOVE_GROUP_MAX_ATTEMPTS = 4;
    private static final long Q2Q_REMOVE_GROUP_RETRY_DELAY_MS = 1_000L;

    interface StatusListener {
        void onStatus(String status);
    }

    private final Context context;
    private final Qcl041ProbeConfig config;
    private final Qcl041LifecycleArtifact artifact;
    private final StatusListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Qcl041WifiDirectNetworkBinder networkBinder;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;
    private boolean connectStarted;
    private boolean socketStarted;
    private boolean cleanupStarted;
    private boolean createGroupBusyRetryAttempted;
    private boolean questGroupPreclearAttempted;
    private boolean groupOwnerMediaPathStartPending;
    private long groupFormationStartMs;
    private int peerDiscoveryPollCount;
    private int questConnectAttemptCount;
    private int maxPeerCountObserved;
    private int connectionInfoPollCount;

    Qcl041WifiDirectLifecycle(
            Context context,
            Qcl041ProbeConfig config,
            Qcl041LifecycleArtifact artifact,
            StatusListener listener) {
        this.context = context.getApplicationContext();
        this.networkBinder = new Qcl041WifiDirectNetworkBinder(this.context, artifact);
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
        maybeApplyP2pDeviceNameOverride(new Runnable() {
            @Override
            public void run() {
                startConfiguredRoute();
            }
        });
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

    private void startConfiguredRoute() {
        if (config.isQuestPeerRoute() && config.isQuestGroupOwnerRole()) {
            startQuestGroupOwner();
        } else if (!config.isQuestPeerRoute() && config.windowsPeerPreclearPersistentGroups) {
            preclearPersistentGroupsThen(new Runnable() {
                @Override
                public void run() {
                    startPeerDiscovery();
                }
            });
        } else {
            if (!config.isQuestPeerRoute()) {
                artifact.diagnostic(
                        "lifecycle",
                        "windows_peer_persistent_group_preclear_skipped",
                        "disabled");
            }
            startPeerDiscovery();
        }
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

    private void maybeApplyP2pDeviceNameOverride(final Runnable next) {
        if (!config.hasP2pDeviceNameOverride()) {
            artifact.diagnostic("lifecycle", "p2p_device_name_override_status", "not_requested");
            next.run();
            return;
        }
        final String requestedName = config.p2pDeviceNameOverride.trim();
        final AtomicBoolean completed = new AtomicBoolean(false);
        artifact.diagnostic("lifecycle", "p2p_device_name_override_requested", requestedName);
        artifact.diagnostic("lifecycle", "p2p_device_name_override_reflection", "setDeviceName");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (completed.compareAndSet(false, true)) {
                    artifact.diagnostic("lifecycle", "p2p_device_name_override_status", "timeout");
                    next.run();
                }
            }
        }, 3000L);
        try {
            Method method = WifiP2pManager.class.getMethod(
                    "setDeviceName",
                    WifiP2pManager.Channel.class,
                    String.class,
                    WifiP2pManager.ActionListener.class);
            method.invoke(manager, channel, requestedName, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (completed.compareAndSet(false, true)) {
                        artifact.diagnostic("lifecycle", "p2p_device_name_override_status", "accepted");
                        handler.postDelayed(next, 500L);
                    }
                }

                @Override
                public void onFailure(int reason) {
                    if (completed.compareAndSet(false, true)) {
                        artifact.diagnostic("lifecycle", "p2p_device_name_override_status", "rejected");
                        artifact.diagnostic("lifecycle", "p2p_device_name_override_failure_reason", reason);
                        handler.postDelayed(next, 500L);
                    }
                }
            });
        } catch (NoSuchMethodException ex) {
            if (completed.compareAndSet(false, true)) {
                artifact.diagnostic("lifecycle", "p2p_device_name_override_status", "method_missing");
                next.run();
            }
        } catch (Exception ex) {
            if (completed.compareAndSet(false, true)) {
                artifact.diagnostic(
                        "lifecycle",
                        "p2p_device_name_override_status",
                        ex.getClass().getSimpleName());
                artifact.diagnostic("lifecycle", "p2p_device_name_override_error", ex.getMessage());
                next.run();
            }
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
                    } else if (config.isQuestPeerRoute()
                            && !config.isQuestGroupOwnerRole()
                            && connectStarted
                            && !socketStarted) {
                        if (questConnectBroadcastIsStillInFlight(networkInfo)) {
                            artifact.diagnostic(
                                    "lifecycle",
                                    "p2p_connection_changed_connect_in_progress",
                                    networkInfo == null
                                            ? "networkInfo=null"
                                            : networkInfo.getState() + "/" + networkInfo.getDetailedState());
                            requestConnectionInfo();
                            return;
                        }
                        artifact.diagnostic(
                                "lifecycle",
                                "p2p_connection_changed_not_connected",
                                networkInfo == null
                                        ? "networkInfo=null"
                                        : networkInfo.getState() + "/" + networkInfo.getDetailedState());
                        restartPeerDiscoveryForConnectRetry();
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

    private boolean questConnectBroadcastIsStillInFlight(NetworkInfo networkInfo) {
        if (networkInfo == null) {
            return false;
        }
        NetworkInfo.State state = networkInfo.getState();
        NetworkInfo.DetailedState detailedState = networkInfo.getDetailedState();
        return state == NetworkInfo.State.CONNECTING
                || detailedState == NetworkInfo.DetailedState.CONNECTING
                || detailedState == NetworkInfo.DetailedState.AUTHENTICATING
                || detailedState == NetworkInfo.DetailedState.OBTAINING_IPADDR;
    }

    private void preclearPersistentGroupsThen(final Runnable next) {
        if (manager == null || channel == null) {
            artifact.diagnostic(
                    "lifecycle",
                    "windows_peer_persistent_group_preclear_skipped",
                    "manager_or_channel_missing");
            next.run();
            return;
        }
        updateStatus("pre-clearing stale Windows Wi-Fi Direct persistent groups");
        artifact.diagnostic("lifecycle", "windows_peer_persistent_group_preclear_requested", true);
        final AtomicBoolean completed = new AtomicBoolean(false);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (completed.compareAndSet(false, true)) {
                    artifact.diagnostic(
                            "lifecycle",
                            "windows_peer_persistent_group_preclear_timeout",
                            true);
                    next.run();
                }
            }
        }, 4000L);
        try {
            Class<?> listenerClass =
                    Class.forName("android.net.wifi.p2p.WifiP2pManager$PersistentGroupInfoListener");
            Method requestMethod = WifiP2pManager.class.getMethod(
                    "requestPersistentGroupInfo",
                    WifiP2pManager.Channel.class,
                    listenerClass);
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] {listenerClass},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onPersistentGroupInfoAvailable".equals(method.getName())) {
                                Object groupList = args == null || args.length == 0 ? null : args[0];
                                handlePersistentGroupInfo(groupList, completed, next);
                            }
                            return null;
                        }
                    });
            requestMethod.invoke(manager, channel, listener);
        } catch (Exception ex) {
            artifact.diagnostic(
                    "lifecycle",
                    "windows_peer_persistent_group_preclear_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            if (completed.compareAndSet(false, true)) {
                next.run();
            }
        }
    }

    private void handlePersistentGroupInfo(
            Object groupList,
            AtomicBoolean completed,
            Runnable next) {
        if (groupList == null) {
            artifact.diagnostic("lifecycle", "windows_peer_persistent_group_count", 0);
            if (completed.compareAndSet(false, true)) {
                next.run();
            }
            return;
        }
        try {
            Method getGroupListMethod = groupList.getClass().getMethod("getGroupList");
            Object rawGroups = getGroupListMethod.invoke(groupList);
            Collection<?> groups = rawGroups instanceof Collection ? (Collection<?>) rawGroups : null;
            int count = groups == null ? 0 : groups.size();
            artifact.diagnostic("lifecycle", "windows_peer_persistent_group_count", count);
            if (groups == null || groups.isEmpty()) {
                if (completed.compareAndSet(false, true)) {
                    next.run();
                }
                return;
            }
            List<Integer> networkIds = new ArrayList<>();
            for (Object rawGroup : groups) {
                if (rawGroup instanceof WifiP2pGroup) {
                    WifiP2pGroup group = (WifiP2pGroup) rawGroup;
                    networkIds.add(group.getNetworkId());
                    artifact.diagnostic(
                            "lifecycle",
                            "windows_peer_persistent_group_" + group.getNetworkId() + "_network_name",
                            group.getNetworkName());
                }
            }
            artifact.diagnostic("lifecycle", "windows_peer_persistent_group_network_ids", networkIds.toString());
            deletePersistentGroups(networkIds, completed, next);
        } catch (Exception ex) {
            artifact.diagnostic(
                    "lifecycle",
                    "windows_peer_persistent_group_info_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            if (completed.compareAndSet(false, true)) {
                next.run();
            }
        }
    }

    private void deletePersistentGroups(
            List<Integer> networkIds,
            AtomicBoolean completed,
            Runnable next) {
        List<Integer> validNetworkIds = new ArrayList<>();
        for (Integer networkId : networkIds) {
            if (networkId != null && networkId >= 0) {
                validNetworkIds.add(networkId);
            }
        }
        if (validNetworkIds.isEmpty()) {
            if (completed.compareAndSet(false, true)) {
                next.run();
            }
            return;
        }
        final int[] remaining = new int[] {validNetworkIds.size()};
        try {
            Method deleteMethod = WifiP2pManager.class.getMethod(
                    "deletePersistentGroup",
                    WifiP2pManager.Channel.class,
                    int.class,
                    WifiP2pManager.ActionListener.class);
            for (Integer networkId : validNetworkIds) {
                deleteMethod.invoke(
                        manager,
                        channel,
                        networkId,
                        new WifiP2pManager.ActionListener() {
                            @Override
                            public void onSuccess() {
                                artifact.diagnostic(
                                        "lifecycle",
                                        "windows_peer_persistent_group_delete_" + networkId,
                                        "success");
                                finishPersistentGroupDeleteIfDone(remaining, completed, next);
                            }

                            @Override
                            public void onFailure(int reason) {
                                artifact.diagnostic(
                                        "lifecycle",
                                        "windows_peer_persistent_group_delete_" + networkId + "_reason",
                                        reason);
                                finishPersistentGroupDeleteIfDone(remaining, completed, next);
                            }
                        });
            }
        } catch (Exception ex) {
            artifact.diagnostic(
                    "lifecycle",
                    "windows_peer_persistent_group_delete_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            if (completed.compareAndSet(false, true)) {
                next.run();
            }
        }
    }

    private void finishPersistentGroupDeleteIfDone(
            int[] remaining,
            AtomicBoolean completed,
            Runnable next) {
        remaining[0] -= 1;
        if (remaining[0] <= 0 && completed.compareAndSet(false, true)) {
            handler.postDelayed(next, 750L);
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
                        recordWifiP2pGroupDiagnostics("lifecycle", "startup_group", group);
                    }
                }
            });
        } catch (SecurityException ex) {
            artifact.recordFailure("requestGroupInfo", ex.getMessage());
        }
    }

    private void startPeerDiscovery() {
        updateStatus("discovering " + config.peerClass + " Wi-Fi Direct peer");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                artifact.diagnostic("lifecycle", "discoverPeers", "success");
                requestPeers();
                if (config.isQuestPeerRoute()) {
                    pollPeerDiscoveryUntilConnectStarts();
                }
            }

            @Override
            public void onFailure(int reason) {
                artifact.recordFailure("discoverPeers", "discoverPeers failed reason=" + reason);
                if (config.isQuestPeerRoute()) {
                    artifact.diagnostic(
                            "lifecycle",
                            "discoverPeers_retry_after_failure_reason",
                            reason);
                    pollPeerDiscoveryUntilConnectStarts();
                    return;
                }
                artifact.setPeerDiscovery(0, "discoverPeers failed reason=" + reason);
                finishWithCleanup("discoverPeers failed reason=" + reason);
            }
        });
    }

    private void pollPeerDiscoveryUntilConnectStarts() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cleanupStarted || connectStarted || socketStarted) {
                    return;
                }
                peerDiscoveryPollCount++;
                artifact.diagnostic("lifecycle", "peer_discovery_poll_count", peerDiscoveryPollCount);
                requestPeers();
                try {
                    manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            artifact.diagnostic("lifecycle", "discoverPeers_retry", "success");
                        }

                        @Override
                        public void onFailure(int reason) {
                            artifact.diagnostic(
                                    "lifecycle",
                                    "discoverPeers_retry_failure_reason",
                                    reason);
                        }
                    });
                } catch (SecurityException ex) {
                    artifact.recordFailure("discoverPeers_retry", ex.getMessage());
                }
                pollPeerDiscoveryUntilConnectStarts();
            }
        }, 1500L);
    }

    private void startQuestGroupOwner() {
        if (config.q2qPreclearOnly && !questGroupPreclearAttempted) {
            questGroupPreclearAttempted = true;
            removeStaleQuestGroupOnlyAndFinish();
            return;
        }
        if (config.q2qPreclearStaleGroup && !questGroupPreclearAttempted) {
            questGroupPreclearAttempted = true;
            removeStaleQuestGroupBeforeInitialCreate();
            return;
        }
        groupFormationStartMs = SystemClock.elapsedRealtime();
        updateStatus("creating Quest-to-Quest Wi-Fi Direct group");
        final WifiP2pConfig groupConfig = buildQuestGroupOwnerConfig();
        final WifiP2pManager.ActionListener createGroupListener = new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                artifact.diagnostic("q2q", "createGroup", "accepted");
                artifact.setPeerDiscovery(
                        1,
                        "Quest group-owner route created an AP-less Wi-Fi Direct group for peer discovery.");
                requestConnectionInfo();
                pollConnectionInfoUntilSocketStarts();
            }

            @Override
            public void onFailure(int reason) {
                artifact.recordFailure("createGroup", "createGroup failed reason=" + reason);
                if (reason == WifiP2pManager.BUSY) {
                    artifact.diagnostic(
                            "q2q",
                            "createGroup_busy_treated_as_existing_group_candidate",
                            true);
                    requestConnectionInfo();
                    requestGroupInfoAfterFormation();
                    pollConnectionInfoUntilSocketStarts();
                    return;
                }
                if (createGroupBusyRetryAttempted) {
                    artifact.diagnostic(
                            "q2q",
                            "createGroup_retry_failure_treated_as_existing_group_candidate",
                            reason);
                    requestConnectionInfo();
                    requestGroupInfoAfterFormation();
                    pollConnectionInfoUntilSocketStarts();
                    return;
                }
                finishWithCleanup("createGroup failed reason=" + reason);
            }
        };
        try {
            if (groupConfig == null) {
                artifact.diagnostic("q2q", "createGroup_config_source", "default_no_config");
                manager.createGroup(channel, createGroupListener);
            } else {
                artifact.diagnostic("q2q", "createGroup_config_source", "credentialed_temporary_config");
                manager.createGroup(channel, groupConfig, createGroupListener);
            }
        } catch (SecurityException ex) {
            artifact.recordFailure("createGroup", ex.getMessage());
            finishWithCleanup("createGroup threw SecurityException");
        }
    }

    private void removeStaleQuestGroupOnlyAndFinish() {
        if (manager == null || channel == null) {
            artifact.diagnostic("q2q", "preclear_only_remove_group_skipped", "manager_or_channel_missing");
            finishWithCleanup("Quest-to-Quest preclear-only skipped because manager or channel was missing");
            return;
        }
        updateStatus("pre-clearing stale Quest-to-Quest Wi-Fi Direct group only");
        stopPeerDiscoveryThen("q2q", "preclear_only_stop_peer_discovery", new Runnable() {
            @Override
            public void run() {
                removeGroupWithRetry(
                        "q2q",
                        "preclear_only_remove_group",
                        Q2Q_REMOVE_GROUP_MAX_ATTEMPTS,
                        new Runnable() {
                            @Override
                            public void run() {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        finishWithCleanup("Quest-to-Quest preclear-only completed");
                                    }
                                }, 500L);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        finishWithCleanup(
                                                "Quest-to-Quest preclear-only completed after removeGroup failure");
                                    }
                                }, 500L);
                            }
                        });
            }
        });
    }

    private void removeStaleQuestGroupBeforeInitialCreate() {
        if (manager == null || channel == null) {
            artifact.diagnostic("q2q", "initial_stale_group_preclear_skipped", "manager_or_channel_missing");
            startQuestGroupOwner();
            return;
        }
        updateStatus("pre-clearing stale Quest-to-Quest Wi-Fi Direct group before create");
        stopPeerDiscoveryThen("q2q", "initial_stale_group_stop_peer_discovery", new Runnable() {
            @Override
            public void run() {
                removeGroupWithRetry(
                        "q2q",
                        "initial_stale_group_preclear",
                        Q2Q_REMOVE_GROUP_MAX_ATTEMPTS,
                        new Runnable() {
                            @Override
                            public void run() {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!cleanupStarted && !socketStarted) {
                                            startQuestGroupOwner();
                                        }
                                    }
                                }, 750L);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!cleanupStarted && !socketStarted) {
                                            startQuestGroupOwner();
                                        }
                                    }
                                }, 500L);
                            }
                        });
            }
        });
    }

    private void removeStaleQuestGroupBeforeCreateRetry() {
        if (manager == null || channel == null) {
            artifact.diagnostic("q2q", "createGroup_busy_retry_removeGroup_skipped", "manager_or_channel_missing");
            requestConnectionInfo();
            pollConnectionInfoUntilSocketStarts();
            return;
        }
        updateStatus("removing stale Quest-to-Quest Wi-Fi Direct group before retry");
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    artifact.diagnostic("q2q", "createGroup_busy_retry_removeGroup", "success");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!cleanupStarted && !socketStarted) {
                                startQuestGroupOwner();
                            }
                        }
                    }, 750L);
                }

                @Override
                public void onFailure(int reason) {
                    artifact.diagnostic("q2q", "createGroup_busy_retry_removeGroup_reason", reason);
                    requestConnectionInfo();
                    pollConnectionInfoUntilSocketStarts();
                }
            });
        } catch (SecurityException ex) {
            artifact.diagnostic("q2q", "createGroup_busy_retry_removeGroup_error", ex.getMessage());
            requestConnectionInfo();
            pollConnectionInfoUntilSocketStarts();
        }
    }

    private void pollConnectionInfoUntilSocketStarts() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cleanupStarted || socketStarted) {
                    return;
                }
                requestConnectionInfo();
                pollConnectionInfoUntilSocketStarts();
            }
        }, 500L);
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
        if (count > maxPeerCountObserved) {
            maxPeerCountObserved = count;
            artifact.diagnostic("lifecycle", "peer_count_max_observed", maxPeerCountObserved);
        }
        if (config.isQuestPeerRoute() && config.isQuestGroupOwnerRole()) {
            artifact.diagnostic("q2q", "group_owner_peer_connect_suppressed", true);
            return;
        }
        if (count == 0 || connectStarted) {
            return;
        }
        WifiP2pDevice selected = selectPeer(devices);
        if (selected == null) {
            artifact.recordFailure(
                    "peer_discovery",
                    "No peer matched qcl041.peer_name_contains=" + config.effectivePeerNameContains());
            return;
        }
        artifact.diagnostic("peer", "selected_device_name", selected.deviceName);
        artifact.diagnostic("peer", "selected_device_status", selected.status);
        artifact.diagnostic("peer", "selected_device_address_present", selected.deviceAddress != null);
        artifact.diagnostic("lifecycle", "peer_count_at_connect_start", count);
        connectToPeer(selected);
    }

    private WifiP2pDevice selectPeer(Collection<WifiP2pDevice> devices) {
        String needle = config.effectivePeerNameContains().toLowerCase(Locale.US);
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

    private WifiP2pConfig buildQuestGroupOwnerConfig() {
        WifiP2pConfig groupConfig = buildQuestCredentialedConfig("q2q", "create_group_config", null);
        if (groupConfig == null) {
            return null;
        }
        groupConfig.groupOwnerIntent = WifiP2pConfig.GROUP_OWNER_INTENT_MAX;
        configurePbc(groupConfig);
        recordQuestWifiP2pConfig("q2q", "create_group_config", groupConfig);
        return groupConfig;
    }

    private WifiP2pConfig buildQuestPeerConfig(WifiP2pDevice peer) {
        if (config.isQuestPeerRoute()) {
            WifiP2pConfig credentialedConfig = buildQuestCredentialedConfig(
                    "lifecycle",
                    "connect_config",
                    peer == null ? null : peer.deviceAddress);
            if (credentialedConfig != null) {
                credentialedConfig.groupOwnerIntent = Math.max(
                        WifiP2pConfig.GROUP_OWNER_INTENT_MIN,
                        Math.min(WifiP2pConfig.GROUP_OWNER_INTENT_MAX, config.groupOwnerIntent));
                configurePbc(credentialedConfig);
                recordQuestWifiP2pConfig("lifecycle", "connect_config", credentialedConfig);
                return credentialedConfig;
            }
        } else if (config.useLegacyWindowsPeerConnectConfig()) {
            WifiP2pConfig legacyPeerConfig = buildLegacyWindowsPeerConfig(
                    peer == null ? null : peer.deviceAddress);
            if (legacyPeerConfig != null) {
                return legacyPeerConfig;
            }
        } else {
            WifiP2pConfig temporaryPeerConfig = buildTemporaryPeerConfig(
                    "lifecycle",
                    "connect_config",
                    peer == null ? null : peer.deviceAddress);
            if (temporaryPeerConfig != null) {
                return temporaryPeerConfig;
            }
        }

        WifiP2pConfig peerConfig = new WifiP2pConfig();
        peerConfig.deviceAddress = peer.deviceAddress;
        peerConfig.groupOwnerIntent = Math.max(0, Math.min(15, config.groupOwnerIntent));
        configurePbc(peerConfig);
        applyTemporaryNetworkIdViaReflection(peerConfig, "lifecycle", "connect_config");
        if (config.isQuestPeerRoute()) {
            recordQuestWifiP2pConfig("lifecycle", "connect_fallback_config", peerConfig);
        } else {
            recordPeerWifiP2pConfig("lifecycle", "connect_config", peerConfig);
        }
        return peerConfig;
    }

    private WifiP2pConfig buildLegacyWindowsPeerConfig(String peerDeviceAddress) {
        WifiP2pConfig peerConfig = new WifiP2pConfig();
        if (peerDeviceAddress != null && !peerDeviceAddress.trim().isEmpty()) {
            peerConfig.deviceAddress = peerDeviceAddress;
            artifact.diagnostic("lifecycle", "connect_config_peer_device_address_set", true);
        } else {
            artifact.diagnostic("lifecycle", "connect_config_peer_device_address_set", false);
        }
        peerConfig.groupOwnerIntent = Math.max(0, Math.min(15, config.groupOwnerIntent));
        configurePbc(peerConfig);
        artifact.diagnostic("lifecycle", "connect_config_mode", config.windowsPeerConnectMode);
        artifact.diagnostic("lifecycle", "connect_config_builder", "legacy_mutable_default");
        artifact.diagnostic("lifecycle", "connect_config_persistent_mode_request", "platform_default");
        recordPeerWifiP2pConfig("lifecycle", "connect_config", peerConfig);
        return peerConfig;
    }

    private WifiP2pConfig buildTemporaryPeerConfig(
            String diagnosticSection,
            String diagnosticPrefix,
            String peerDeviceAddress) {
        try {
            WifiP2pConfig.Builder builder = new WifiP2pConfig.Builder()
                    .enablePersistentMode(false);
            artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_mode", config.windowsPeerConnectMode);
            if (peerDeviceAddress != null && !peerDeviceAddress.trim().isEmpty()) {
                try {
                    builder.setDeviceAddress(MacAddress.fromString(peerDeviceAddress));
                    artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_peer_device_address_set", true);
                } catch (IllegalArgumentException ex) {
                    artifact.diagnostic(
                            diagnosticSection,
                            diagnosticPrefix + "_peer_device_address_error",
                            ex.getMessage());
                    return null;
                }
            }
            WifiP2pConfig built = builder.build();
            built.groupOwnerIntent = Math.max(0, Math.min(15, config.groupOwnerIntent));
            configurePbc(built);
            artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_builder", "device_address_temporary");
            recordPeerWifiP2pConfig(diagnosticSection, diagnosticPrefix, built);
            return built;
        } catch (RuntimeException ex) {
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_builder_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }

    private WifiP2pConfig buildQuestCredentialedConfig(
            String diagnosticSection,
            String diagnosticPrefix,
            String peerDeviceAddress) {
        try {
            WifiP2pConfig.Builder builder = new WifiP2pConfig.Builder()
                    .setNetworkName(config.q2qNetworkName)
                    .setPassphrase(config.q2qPassphrase)
                    .enablePersistentMode(false);
            if (peerDeviceAddress != null && !peerDeviceAddress.trim().isEmpty()) {
                try {
                    builder.setDeviceAddress(MacAddress.fromString(peerDeviceAddress));
                    artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_peer_device_address_set", true);
                } catch (IllegalArgumentException ex) {
                    artifact.diagnostic(
                            diagnosticSection,
                            diagnosticPrefix + "_peer_device_address_error",
                            ex.getMessage());
                }
            }
            WifiP2pConfig built = builder.build();
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_builder",
                    "network_name_passphrase_temporary");
            return built;
        } catch (RuntimeException ex) {
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_builder_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }

    private void configurePbc(WifiP2pConfig p2pConfig) {
        if (p2pConfig != null && p2pConfig.wps != null) {
            p2pConfig.wps.setup = WpsInfo.PBC;
        }
    }

    private boolean applyTemporaryNetworkIdViaReflection(
            WifiP2pConfig p2pConfig,
            String diagnosticSection,
            String diagnosticPrefix) {
        String[] candidateFields = new String[] {"mNetworkId", "networkId", "mNetId", "netId"};
        List<String> errors = new ArrayList<>();
        for (String candidateField : candidateFields) {
            try {
                java.lang.reflect.Field field = WifiP2pConfig.class.getDeclaredField(candidateField);
                field.setAccessible(true);
                field.setInt(p2pConfig, WifiP2pGroup.NETWORK_ID_TEMPORARY);
                artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_temporary_network_id_reflection", true);
                artifact.diagnostic(
                        diagnosticSection,
                        diagnosticPrefix + "_temporary_network_id_reflection_field",
                        candidateField);
                return true;
            } catch (Exception ex) {
                errors.add(candidateField + "=" + ex.getClass().getSimpleName());
            }
        }
        artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_temporary_network_id_reflection", false);
        artifact.diagnostic(
                diagnosticSection,
                diagnosticPrefix + "_temporary_network_id_reflection_error",
                String.join(";", errors));
        return false;
    }

    private void recordQuestWifiP2pConfig(
            String diagnosticSection,
            String diagnosticPrefix,
            WifiP2pConfig p2pConfig) {
        artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_network_name", config.q2qNetworkName);
        artifact.diagnostic(
                diagnosticSection,
                diagnosticPrefix + "_passphrase_length",
                config.q2qPassphrase == null ? 0 : config.q2qPassphrase.length());
        artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_persistent_mode_requested", false);
        try {
            int networkId = p2pConfig.getNetworkId();
            artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_network_id", networkId);
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_temporary_network_id",
                    networkId == WifiP2pGroup.NETWORK_ID_TEMPORARY);
        } catch (RuntimeException ex) {
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_network_id_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void recordPeerWifiP2pConfig(
            String diagnosticSection,
            String diagnosticPrefix,
            WifiP2pConfig p2pConfig) {
        artifact.diagnostic(
                diagnosticSection,
                diagnosticPrefix + "_device_address_present",
                p2pConfig.deviceAddress != null && !p2pConfig.deviceAddress.isEmpty());
        artifact.diagnostic(
                diagnosticSection,
                diagnosticPrefix + "_group_owner_intent",
                p2pConfig.groupOwnerIntent);
        artifact.diagnostic(
                diagnosticSection,
                diagnosticPrefix + "_wps_setup",
                p2pConfig.wps == null ? -1 : p2pConfig.wps.setup);
        artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_persistent_mode_requested", false);
        try {
            int networkId = p2pConfig.getNetworkId();
            artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_network_id", networkId);
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_temporary_network_id",
                    networkId == WifiP2pGroup.NETWORK_ID_TEMPORARY);
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_persistent_network_id",
                    networkId == WifiP2pGroup.NETWORK_ID_PERSISTENT);
        } catch (RuntimeException ex) {
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_network_id_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void connectToPeer(WifiP2pDevice peer) {
        connectStarted = true;
        questConnectAttemptCount++;
        groupFormationStartMs = SystemClock.elapsedRealtime();
        updateStatus("connecting to " + config.peerClass + " Wi-Fi Direct peer");
        artifact.diagnostic("lifecycle", "connect_attempt", questConnectAttemptCount);
        WifiP2pConfig peerConfig = buildQuestPeerConfig(peer);
        try {
            manager.connect(channel, peerConfig, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    artifact.diagnostic("lifecycle", "connect", "accepted");
                    if (!config.isQuestPeerRoute()) {
                        artifact.diagnostic("lifecycle", "windows_peer_connection_info_polling_started", true);
                        requestConnectionInfo();
                        pollConnectionInfoUntilSocketStarts();
                    }
                    scheduleQuestConnectRetryIfNeeded(questConnectAttemptCount);
                }

                @Override
                public void onFailure(int reason) {
                    if (shouldRetryQuestConnectFailure(reason)) {
                        artifact.diagnostic("lifecycle", "connect_busy_retry_after_ms", Q2Q_CONNECT_RETRY_DELAY_MS);
                        artifact.diagnostic("lifecycle", "connect_busy_retry_next_attempt", questConnectAttemptCount + 1);
                        scheduleQuestConnectFailureRetry(questConnectAttemptCount);
                        return;
                    }
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

    private boolean shouldRetryQuestConnectFailure(int reason) {
        return config.isQuestPeerRoute()
                && !config.isQuestGroupOwnerRole()
                && reason == WifiP2pManager.BUSY
                && questConnectAttemptCount < Q2Q_CONNECT_MAX_ATTEMPTS
                && !cleanupStarted
                && !socketStarted;
    }

    private void scheduleQuestConnectFailureRetry(final int attempt) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cleanupStarted || socketStarted || attempt != questConnectAttemptCount) {
                    return;
                }
                restartPeerDiscoveryForConnectRetry();
            }
        }, Q2Q_CONNECT_RETRY_DELAY_MS);
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
        connectionInfoPollCount++;
        artifact.diagnostic("lifecycle", "connection_info_callback_count", connectionInfoPollCount);
        artifact.diagnostic(
                "lifecycle",
                "connection_info_group_formed",
                info != null && info.groupFormed);
        if (info != null) {
            artifact.diagnostic("lifecycle", "connection_info_is_group_owner", info.isGroupOwner);
            artifact.diagnostic(
                    "lifecycle",
                    "connection_info_group_owner_address_present",
                    info.groupOwnerAddress != null);
        }
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
        recordConnectivitySnapshot(info.groupOwnerAddress, "connectivity", "after_group_formation_");
        requestGroupInfoAfterFormation();

        if (config.isQuestPeerRoute() && info.isGroupOwner) {
            startQuestGroupOwnerSocketExchange(info);
            return;
        }
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
        startSocketExchange(info);
    }

    private boolean qcl082MediaPathEnabled() {
        return config.qcl082RelayEnabled || config.qcl082ReceiveProxyEnabled;
    }

    private void startQuestGroupOwnerMediaPathAfterClientJoin(final WifiP2pInfo info) {
        if (socketStarted || cleanupStarted || groupOwnerMediaPathStartPending) {
            return;
        }
        groupOwnerMediaPathStartPending = true;
        pollQuestGroupOwnerClientThenStartMedia(info, SystemClock.elapsedRealtime(), 1);
    }

    private void pollQuestGroupOwnerClientThenStartMedia(
            final WifiP2pInfo info,
            final long startedMs,
            final int attempt) {
        if (socketStarted || cleanupStarted) {
            groupOwnerMediaPathStartPending = false;
            return;
        }
        try {
            manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    int clientCount = group == null || group.getClientList() == null
                            ? 0
                            : group.getClientList().size();
                    artifact.diagnostic("q2q", "group_owner_client_wait_attempt", attempt);
                    artifact.diagnostic("q2q", "group_owner_client_wait_client_count", clientCount);
                    artifact.diagnostic(
                            "q2q",
                            "group_owner_client_wait_elapsed_ms",
                            SystemClock.elapsedRealtime() - startedMs);
                    if (group != null) {
                        artifact.diagnostic("q2q", "group_owner_client_wait_network_name", group.getNetworkName());
                        recordWifiP2pGroupDiagnostics("q2q", "group_owner_client_wait", group);
                    }
                    if (clientCount > 0) {
                        artifact.diagnostic("q2q", "group_owner_client_wait_result", "client_joined");
                        groupOwnerMediaPathStartPending = false;
                        startQuestGroupOwnerQcl082MediaPath(info);
                        return;
                    }
                    if (SystemClock.elapsedRealtime() - startedMs
                            >= Math.max(5, config.timeoutSeconds - 2) * 1000L) {
                        artifact.diagnostic("q2q", "group_owner_client_wait_result", "timed_out_without_client");
                        groupOwnerMediaPathStartPending = false;
                        return;
                    }
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            pollQuestGroupOwnerClientThenStartMedia(info, startedMs, attempt + 1);
                        }
                    }, 500L);
                }
            });
        } catch (SecurityException ex) {
            groupOwnerMediaPathStartPending = false;
            artifact.diagnostic("q2q", "group_owner_client_wait_error", ex.getMessage());
        }
    }

    private void startQuestGroupOwnerQcl082MediaPath(WifiP2pInfo info) {
        if (socketStarted || cleanupStarted) {
            return;
        }
        socketStarted = true;
        Network wifiDirectNetwork = findWifiDirectNetwork(info.groupOwnerAddress);
        artifact.setSocketExchange(
                false,
                0,
                0,
                null,
                "QCL-094 bounded TCP probe skipped because QCL-082 media path owns the first Quest-to-Quest Wi-Fi Direct socket.");
        artifact.diagnostic("qcl082_media_path", "started_before_socket_probe", true);
        artifact.diagnostic("qcl082_media_path", "quest_group_owner_media_path", true);
        startQcl082MediaPathThenFinish(wifiDirectNetwork, info.groupOwnerAddress);
    }

    private void startQcl082MediaPathThenFinish(final Network wifiDirectNetwork, final InetAddress groupOwnerAddress) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                startQcl082MediaPath(wifiDirectNetwork, groupOwnerAddress);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishAfterSocketExchange();
                    }
                });
            }
        }, "qcl082-media-path-start").start();
    }

    private void announceQcl082PeerAddress(Network wifiDirectNetwork, InetAddress groupOwnerAddress) {
        if (groupOwnerAddress == null) {
            artifact.diagnostic("qcl082_peer_address", "announce_skipped", "missing_group_owner_address");
            return;
        }
        InetAddress localAddress = findWifiDirectLocalAddress(groupOwnerAddress);
        if (localAddress == null) {
            artifact.diagnostic("qcl082_peer_address", "announce_skipped", "missing_local_address");
            return;
        }
        long started = SystemClock.elapsedRealtime();
        long deadline = started + QCL082_PEER_ADDRESS_ANNOUNCE_MS;
        int attempts = 0;
        Exception lastError = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts++;
            Socket socket = null;
            try {
                socket = new Socket();
                artifact.diagnostic("qcl082_peer_address", "announce_socket_created_from_wifi_direct_network", false);
                artifact.diagnostic(
                        "qcl082_peer_address",
                        "announce_socket_network_factory_skipped",
                        wifiDirectNetwork == null
                                ? "wifi_direct_network_not_found"
                                : "local_address_bind_used_for_peer_announce");
                if (!bindSocketToSpecificLocalAddress(
                        socket,
                        localAddress,
                        groupOwnerAddress,
                        "qcl082_peer_address")) {
                    throw new IOException("failed to bind peer-address announce socket to Wi-Fi Direct local address");
                }
                socket.connect(new InetSocketAddress(groupOwnerAddress, config.listenPort), 1000);
                socket.setSoTimeout(1000);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(),
                        StandardCharsets.UTF_8));
                writer.write("QCL082_PEER_ADDRESS;run_id=");
                writer.write(config.runId);
                writer.write(";address=");
                writer.write(localAddress.getHostAddress());
                writer.write("\n");
                writer.flush();
                artifact.diagnostic("qcl082_peer_address", "announce_sent", true);
                artifact.diagnostic("qcl082_peer_address", "announce_attempts", attempts);
                artifact.diagnostic("qcl082_peer_address", "announce_local_address", localAddress.getHostAddress());
                artifact.diagnostic("qcl082_peer_address", "announce_group_owner", groupOwnerAddress.getHostAddress());
                closeQuietly(socket);
                return;
            } catch (Exception ex) {
                lastError = ex;
                closeQuietly(socket);
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        artifact.diagnostic("qcl082_peer_address", "announce_sent", false);
        artifact.diagnostic("qcl082_peer_address", "announce_attempts", attempts);
        if (lastError != null) {
            artifact.diagnostic("qcl082_peer_address", "announce_error", lastError.getMessage());
        }
    }

    private String waitForQcl082PeerAddressAnnouncement(InetAddress localAddress) {
        if (localAddress == null) {
            artifact.diagnostic("qcl082_peer_address", "listen_skipped", "missing_local_address");
            return null;
        }
        long started = SystemClock.elapsedRealtime();
        long deadline = started + QCL082_PEER_ADDRESS_WAIT_MS;
        int accepts = 0;
        ServerSocket server = null;
        Socket peer = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(localAddress, config.listenPort));
            server.setSoTimeout(500);
            artifact.diagnostic("qcl082_peer_address", "listen_bound_local_address", localAddress.getHostAddress());
            artifact.diagnostic("qcl082_peer_address", "listen_port", config.listenPort);
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    peer = server.accept();
                    accepts++;
                    InetAddress peerAddress = peer.getInetAddress();
                    if (peerAddress != null) {
                        artifact.diagnostic(
                                "qcl082_peer_address",
                                "accepted_peer_address",
                                peerAddress.getHostAddress());
                    }
                    peer.setSoTimeout(500);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            peer.getInputStream(),
                            StandardCharsets.UTF_8));
                    String payload = reader.readLine();
                    if (payload != null) {
                        artifact.diagnostic("qcl082_peer_address", "accepted_payload", payload);
                    }
                    if (peerAddress != null && !peerAddress.equals(localAddress)) {
                        artifact.diagnostic("qcl082_peer_address", "accepted", true);
                        artifact.diagnostic("qcl082_peer_address", "accept_count", accepts);
                        return peerAddress.getHostAddress();
                    }
                } catch (SocketTimeoutException ignored) {
                } finally {
                    closeQuietly(peer);
                    peer = null;
                }
            }
        } catch (Exception ex) {
            artifact.diagnostic("qcl082_peer_address", "listen_error", ex.getMessage());
        } finally {
            closeQuietly(peer);
            closeQuietly(server);
        }
        artifact.diagnostic("qcl082_peer_address", "accepted", false);
        artifact.diagnostic("qcl082_peer_address", "accept_count", accepts);
        artifact.diagnostic(
                "qcl082_peer_address",
                "wait_elapsed_ms",
                SystemClock.elapsedRealtime() - started);
        return null;
    }

    private void startQcl082MediaPath(final Network wifiDirectNetwork, final InetAddress groupOwnerAddress) {
        artifact.diagnostic("qcl082_media_path", "relay_enabled", config.qcl082RelayEnabled);
        artifact.diagnostic("qcl082_media_path", "receive_proxy_enabled", config.qcl082ReceiveProxyEnabled);
        artifact.diagnostic("qcl082_media_path", "ack_pacing_enabled", config.qcl082AckPacingEnabled);
        artifact.diagnostic("qcl082_media_path", "ack_chunk_bytes", config.qcl082AckChunkBytes);
        artifact.diagnostic(
                "qcl082_media_path",
                "ack_soft_timeout_limit",
                Math.max(0, config.qcl082AckSoftTimeoutLimit));
        artifact.diagnostic(
                "qcl082_media_path",
                "process_network_bind_required",
                qcl082RelayTransportProtocolUdp() && wifiDirectNetwork != null);
        artifact.diagnostic(
                "qcl082_media_path",
                "process_network_bind_reason",
                qcl082RelayTransportProtocolUdp()
                        ? "deferred_until_udp_relay_after_loopback_source_connect"
                        : "not_udp_relay_transport");
        artifact.writeQuietly();
        if (config.isQuestPeerRoute() && !config.isQuestGroupOwnerRole()) {
            artifact.diagnostic(
                    "qcl082_peer_address",
                    "announce_skipped",
                    "tcp_announce_path_timed_out_in_prior_qcl100_runs");
        }
        if (config.qcl082ReceiveProxyEnabled) {
            artifact.diagnostic("qcl082_receive_proxy", "started_before_socket_probe", true);
            startQcl082ReceiveProxy(wifiDirectNetwork, groupOwnerAddress);
        }
        if (config.qcl082RelayEnabled) {
            artifact.diagnostic("qcl082_relay", "started_before_socket_probe", true);
            startQcl082MediaRelay(wifiDirectNetwork, groupOwnerAddress);
        }
    }

    private void scheduleQuestConnectRetryIfNeeded(final int attempt) {
        if (!config.isQuestPeerRoute() || config.isQuestGroupOwnerRole()) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cleanupStarted || socketStarted || !connectStarted || attempt != questConnectAttemptCount) {
                    return;
                }
                requestConnectionInfo();
                if (questConnectAttemptCount >= Q2Q_CONNECT_MAX_ATTEMPTS) {
                    artifact.diagnostic("lifecycle", "connect_retry_exhausted", questConnectAttemptCount);
                    return;
                }
                artifact.diagnostic("lifecycle", "connect_retry_after_ms", Q2Q_CONNECT_RETRY_DELAY_MS);
                artifact.diagnostic("lifecycle", "connect_retry_next_attempt", questConnectAttemptCount + 1);
                cancelPendingQuestConnectAndRediscover();
            }
        }, Q2Q_CONNECT_RETRY_DELAY_MS);
    }

    private void cancelPendingQuestConnectAndRediscover() {
        if (manager == null || channel == null) {
            connectStarted = false;
            requestPeers();
            pollPeerDiscoveryUntilConnectStarts();
            return;
        }
        updateStatus("retrying Quest-to-Quest Wi-Fi Direct connect");
        try {
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    artifact.diagnostic("lifecycle", "cancel_connect_for_retry", "success");
                    restartPeerDiscoveryForConnectRetry();
                }

                @Override
                public void onFailure(int reason) {
                    artifact.diagnostic("lifecycle", "cancel_connect_for_retry_reason", reason);
                    restartPeerDiscoveryForConnectRetry();
                }
            });
        } catch (SecurityException ex) {
            artifact.diagnostic("lifecycle", "cancel_connect_for_retry_error", ex.getMessage());
            restartPeerDiscoveryForConnectRetry();
        }
    }

    private void restartPeerDiscoveryForConnectRetry() {
        if (cleanupStarted || socketStarted) {
            return;
        }
        connectStarted = false;
        requestPeers();
        pollPeerDiscoveryUntilConnectStarts();
    }

    private void bindQcl082UdpRelayProcessToWifiDirectNetwork(Network wifiDirectNetwork, String section) {
        if (wifiDirectNetwork == null) {
            artifact.diagnostic(section, "relay_udp_process_bound_to_wifi_direct_network", false);
            artifact.diagnostic(section, "relay_udp_process_network_bind_reason", "wifi_direct_network_not_found");
            return;
        }
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            artifact.diagnostic(section, "relay_udp_process_bound_to_wifi_direct_network", false);
            artifact.diagnostic(section, "relay_udp_process_network_bind_reason", "connectivity_manager_unavailable");
            return;
        }
        try {
            boolean bound = connectivityManager.bindProcessToNetwork(wifiDirectNetwork);
            artifact.diagnostic(section, "relay_udp_process_bound_to_wifi_direct_network", bound);
            artifact.diagnostic(
                    section,
                    "relay_udp_process_network_bind_reason",
                    bound ? "selected_wifi_direct_network_after_source_connect" : "bind_returned_false");
        } catch (Exception ex) {
            artifact.diagnostic(section, "relay_udp_process_bound_to_wifi_direct_network", false);
            artifact.diagnostic(section, "relay_udp_process_network_bind_error", ex.getMessage());
        }
    }

    private void recordWifiP2pGroupDiagnostics(
            String diagnosticSection,
            String diagnosticPrefix,
            WifiP2pGroup group) {
        if (group == null) {
            return;
        }
        artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_network_name", group.getNetworkName());
        try {
            int networkId = group.getNetworkId();
            artifact.diagnostic(diagnosticSection, diagnosticPrefix + "_network_id", networkId);
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_temporary_network_id",
                    networkId == WifiP2pGroup.NETWORK_ID_TEMPORARY);
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_persistent_network_id",
                    networkId == WifiP2pGroup.NETWORK_ID_PERSISTENT);
        } catch (RuntimeException ex) {
            artifact.diagnostic(
                    diagnosticSection,
                    diagnosticPrefix + "_network_id_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        Collection<WifiP2pDevice> clients = group.getClientList();
        artifact.diagnostic(
                diagnosticSection,
                diagnosticPrefix + "_client_count",
                clients == null ? 0 : clients.size());
    }

    private void requestGroupInfoAfterFormation() {
        try {
            manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    if (group != null) {
                        artifact.diagnostic("lifecycle", "group_network_name", group.getNetworkName());
                        recordWifiP2pGroupDiagnostics("lifecycle", "group", group);
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
                InetAddress localAddress = findWifiDirectLocalAddress(info.groupOwnerAddress);
                boolean qcl082MediaStarted = false;
                boolean appBoundMatrixStarted = false;
                try (Socket socket = createSocketForWifiDirectNetwork(wifiDirectNetwork)) {
                    artifact.diagnostic("control_tcp", "socket_owner_package", Qcl041ProbeConfig.PACKAGE_NAME);
                    artifact.diagnostic("control_tcp", "socket_owner_uid", android.os.Process.myUid());
                    artifact.diagnostic("control_tcp", "socket_owner_pid", android.os.Process.myPid());
                    artifact.diagnostic("control_tcp", "direction", "client_to_group_owner");
                    artifact.diagnostic("control_tcp", "initiator_role", "client");
                    artifact.diagnostic("control_tcp", "listener_role", "group_owner");
                    artifact.diagnostic("control_tcp", "wifi_p2p_info_is_group_owner", info.isGroupOwner);
                    artifact.diagnostic(
                            "control_tcp",
                            "wifi_p2p_info_group_owner_address",
                            info.groupOwnerAddress == null ? "" : info.groupOwnerAddress.getHostAddress());
                    artifact.diagnostic("control_tcp", "wifi_direct_network_found", wifiDirectNetwork != null);
                    if (wifiDirectNetwork != null) {
                        artifact.diagnostic("control_tcp", "network", wifiDirectNetwork.toString());
                        artifact.diagnostic("control_tcp", "network_handle", wifiDirectNetwork.getNetworkHandle());
                    }
                    if (localAddress != null) {
                        bindSocketToSpecificLocalAddress(
                                socket,
                                localAddress,
                                info.groupOwnerAddress,
                                "control_tcp");
                    } else {
                        artifact.diagnostic("control_tcp", "local_p2p_address", "");
                    }
                    artifact.diagnostic(
                            "control_tcp",
                            "local_socket_before_connect",
                            String.valueOf(socket.getLocalSocketAddress()));
                    socket.connect(
                            new InetSocketAddress(info.groupOwnerAddress, config.listenPort),
                            Math.max(3, config.socketTimeoutSeconds) * 1000);
                    long connectMs = SystemClock.elapsedRealtime() - started;
                    artifact.diagnostic(
                            "control_tcp",
                            "local_socket_after_connect",
                            String.valueOf(socket.getLocalSocketAddress()));
                    artifact.diagnostic(
                            "control_tcp",
                            "remote_socket_after_connect",
                            String.valueOf(socket.getRemoteSocketAddress()));
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
                            "Bounded TCP request/ack completed against "
                                    + config.peerClass
                                    + " group owner.");
                    if (sent > 0 && received > 0 && config.qcl081LslEnabled) {
                        publishQcl081Lsl(wifiDirectNetwork, info.groupOwnerAddress);
                    }
                    if (sent > 0 && received > 0 && config.qcl081LslEchoEnabled) {
                        runQcl081LslEcho(wifiDirectNetwork, info.groupOwnerAddress);
                    }
                    if (sent > 0 && received > 0 && qcl082MediaPathEnabled()) {
                        artifact.diagnostic("qcl082_media_path", "started_before_socket_probe", false);
                        artifact.diagnostic("qcl082_media_path", "started_after_socket_probe", true);
                    }
                    if (sent > 0 && received > 0 && config.qcl082ReceiveProxyEnabled) {
                        startQcl082ReceiveProxy(wifiDirectNetwork, info.groupOwnerAddress);
                        qcl082MediaStarted = true;
                    }
                    if (sent > 0 && received > 0 && config.qcl082RelayEnabled) {
                        startQcl082MediaRelay(wifiDirectNetwork, info.groupOwnerAddress);
                        qcl082MediaStarted = true;
                    }
                    if (config.q2qAppBoundSocketMatrixEnabled && config.isQuestPeerRoute()) {
                        appBoundMatrixStarted = runQ2qAppBoundSocketMatrix(
                                wifiDirectNetwork,
                                info.groupOwnerAddress,
                                localAddress,
                                false,
                                "after_bounded_tcp_success");
                    }
                    socket.setSoTimeout(1000);
                    runQcl082ControlTcpMediaHold(
                            socket,
                            reader,
                            writer,
                            "client",
                            qcl082MediaStarted);
                } catch (Exception ex) {
                    artifact.setSocketExchange(
                            false,
                            sent,
                            received,
                            null,
                            "Bounded TCP probe failed: " + ex.getMessage());
                    if (qcl082MediaPathEnabled() && !qcl082MediaStarted) {
                        artifact.diagnostic(
                                "qcl082_media_path",
                                "started_after_socket_probe_status",
                                "blocked");
                    }
                    if (config.qcl082ReceiveProxyEnabled && !qcl082MediaStarted) {
                        artifact.diagnostic(
                                "qcl082_receive_proxy",
                                "started_after_socket_probe_status",
                                "blocked");
                    }
                    if (config.qcl082RelayEnabled && !qcl082MediaStarted) {
                        artifact.diagnostic(
                                "qcl082_relay",
                                "started_after_socket_probe_status",
                                "blocked");
                    }
                    if (!appBoundMatrixStarted) {
                        appBoundMatrixStarted = runQ2qAppBoundSocketMatrix(
                                wifiDirectNetwork,
                                info.groupOwnerAddress,
                                localAddress,
                                false,
                                "after_bounded_tcp_failure");
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

    private void startQuestGroupOwnerSocketExchange(final WifiP2pInfo info) {
        socketStarted = true;
        updateStatus("serving Quest-to-Quest bounded TCP probe");
        new Thread(new Runnable() {
            @Override
            public void run() {
                long started = SystemClock.elapsedRealtime();
                int sent = 0;
                int received = 0;
                ServerSocket server = null;
                Socket client = null;
                boolean qcl082MediaStarted = false;
                boolean appBoundMatrixStarted = false;
                Network wifiDirectNetwork = null;
                InetAddress localAddress = null;
                try {
                    wifiDirectNetwork = findWifiDirectNetwork(info.groupOwnerAddress);
                    localAddress = findWifiDirectLocalAddress(info.groupOwnerAddress);
                    artifact.diagnostic("control_tcp", "socket_owner_package", Qcl041ProbeConfig.PACKAGE_NAME);
                    artifact.diagnostic("control_tcp", "socket_owner_uid", android.os.Process.myUid());
                    artifact.diagnostic("control_tcp", "socket_owner_pid", android.os.Process.myPid());
                    artifact.diagnostic("control_tcp", "direction", "client_to_group_owner");
                    artifact.diagnostic("control_tcp", "initiator_role", "client");
                    artifact.diagnostic("control_tcp", "listener_role", "group_owner");
                    artifact.diagnostic("control_tcp", "wifi_p2p_info_is_group_owner", info.isGroupOwner);
                    artifact.diagnostic(
                            "control_tcp",
                            "wifi_p2p_info_group_owner_address",
                            info.groupOwnerAddress == null ? "" : info.groupOwnerAddress.getHostAddress());
                    artifact.diagnostic("q2q_server", "wifi_direct_network_found", wifiDirectNetwork != null);
                    artifact.diagnostic("control_tcp", "wifi_direct_network_found", wifiDirectNetwork != null);
                    if (wifiDirectNetwork != null) {
                        artifact.diagnostic("control_tcp", "network", wifiDirectNetwork.toString());
                        artifact.diagnostic("control_tcp", "network_handle", wifiDirectNetwork.getNetworkHandle());
                    }
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    if (localAddress != null) {
                        server.bind(new InetSocketAddress(localAddress, config.listenPort));
                        artifact.diagnostic(
                                "q2q_server",
                                "server_bound_local_address",
                                localAddress.getHostAddress());
                        artifact.diagnostic(
                                "control_tcp",
                                "local_socket_listen",
                                server.getLocalSocketAddress().toString());
                    } else {
                        server.bind(new InetSocketAddress(config.listenPort));
                        artifact.diagnostic("q2q_server", "server_bound_local_address", "0.0.0.0");
                        artifact.diagnostic(
                                "control_tcp",
                                "local_socket_listen",
                                server.getLocalSocketAddress().toString());
                    }
                    server.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
                    client = server.accept();
                    long acceptMs = SystemClock.elapsedRealtime() - started;
                    client.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
                    InetAddress peerAddress = client.getInetAddress();
                    if (peerAddress != null) {
                        artifact.diagnostic("q2q_server", "accepted_peer_address", peerAddress.getHostAddress());
                        artifact.diagnostic("control_tcp", "accepted_peer_address", peerAddress.getHostAddress());
                        artifact.diagnostic("control_tcp", "accepted_peer_port", client.getPort());
                        artifact.diagnostic(
                                "control_tcp",
                                "accepted_local_socket",
                                String.valueOf(client.getLocalSocketAddress()));
                    }
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            client.getInputStream(),
                            StandardCharsets.UTF_8));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            client.getOutputStream(),
                            StandardCharsets.UTF_8));
                    String request = reader.readLine();
                    if (request != null && !request.isEmpty()) {
                        received = 1;
                        artifact.diagnostic("q2q_server", "request_payload", request);
                    }
                    writer.write("RMANVID1_ACK;qcl=QCL-094;run_id=" + config.runId);
                    writer.write("\n");
                    writer.flush();
                    sent = 1;
                    artifact.diagnostic("q2q_server", "accept_ms", acceptMs);
                    artifact.setSocketExchange(
                            sent > 0 && received > 0,
                            sent,
                            received,
                            acceptMs,
                            "Quest group owner accepted bounded TCP request and returned ack.");
                    if (sent > 0 && received > 0 && qcl082MediaPathEnabled()) {
                        artifact.diagnostic("qcl082_media_path", "started_before_socket_probe", false);
                        artifact.diagnostic("qcl082_media_path", "started_after_socket_probe", true);
                    }
                    if (sent > 0 && received > 0 && config.qcl082ReceiveProxyEnabled) {
                        startQcl082ReceiveProxy(wifiDirectNetwork, info.groupOwnerAddress);
                        qcl082MediaStarted = true;
                    }
                    if (sent > 0 && received > 0 && config.qcl082RelayEnabled) {
                        startQcl082MediaRelay(wifiDirectNetwork, info.groupOwnerAddress);
                        qcl082MediaStarted = true;
                    }
                    if (config.q2qAppBoundSocketMatrixEnabled && config.isQuestPeerRoute()) {
                        appBoundMatrixStarted = runQ2qAppBoundSocketMatrix(
                                wifiDirectNetwork,
                                info.groupOwnerAddress,
                                localAddress,
                                true,
                                "after_bounded_tcp_success");
                    }
                    client.setSoTimeout(1000);
                    runQcl082ControlTcpMediaHold(
                            client,
                            reader,
                            writer,
                            "group_owner",
                            qcl082MediaStarted);
                } catch (SocketTimeoutException ex) {
                    artifact.setSocketExchange(
                            false,
                            sent,
                            received,
                            null,
                            "Quest group owner TCP accept/read timed out: " + ex.getMessage());
                    if (qcl082MediaPathEnabled() && !qcl082MediaStarted) {
                        artifact.diagnostic(
                                "qcl082_media_path",
                                "started_after_socket_probe_status",
                                "blocked");
                    }
                    if (!appBoundMatrixStarted) {
                        appBoundMatrixStarted = runQ2qAppBoundSocketMatrix(
                                wifiDirectNetwork,
                                info.groupOwnerAddress,
                                localAddress,
                                true,
                                "after_bounded_tcp_timeout");
                    }
                } catch (Exception ex) {
                    artifact.setSocketExchange(
                            false,
                            sent,
                            received,
                            null,
                            "Quest group owner TCP probe failed: " + ex.getMessage());
                    if (qcl082MediaPathEnabled() && !qcl082MediaStarted) {
                        artifact.diagnostic(
                                "qcl082_media_path",
                                "started_after_socket_probe_status",
                                "blocked");
                    }
                    if (!appBoundMatrixStarted) {
                        appBoundMatrixStarted = runQ2qAppBoundSocketMatrix(
                                wifiDirectNetwork,
                                info.groupOwnerAddress,
                                localAddress,
                                true,
                                "after_bounded_tcp_failure");
                    }
                } finally {
                    closeQuietly(client);
                    closeQuietly(server);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            finishAfterSocketExchange();
                        }
                    });
                }
            }
        }, "qcl094-q2q-owner-socket").start();
    }

    private boolean runQ2qAppBoundSocketMatrix(
            Network wifiDirectNetwork,
            InetAddress groupOwnerAddress,
            InetAddress localAddress,
            boolean groupOwnerReceiver,
            String trigger) {
        if (!config.q2qAppBoundSocketMatrixEnabled || !config.isQuestPeerRoute()) {
            return false;
        }
        artifact.diagnostic("q2q_app_bound_socket_matrix", "trigger", trigger);
        artifact.diagnostic(
                "q2q_app_bound_socket_matrix",
                "runs_after_bounded_tcp_failure",
                trigger != null && trigger.contains("failure"));
        artifact.diagnostic(
                "q2q_app_bound_socket_matrix",
                "runs_after_bounded_tcp_timeout",
                trigger != null && trigger.contains("timeout"));
        try {
            Qcl041AppBoundSocketMatrix matrix = new Qcl041AppBoundSocketMatrix(
                    artifact,
                    config,
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE),
                    wifiDirectNetwork,
                    groupOwnerAddress,
                    localAddress);
            if (groupOwnerReceiver) {
                matrix.runGroupOwnerReceiver();
            } else {
                matrix.runClientSender();
            }
            return true;
        } catch (Exception ex) {
            artifact.diagnostic(
                    "q2q_app_bound_socket_matrix",
                    "run_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    private void startQcl082MediaRelay(final Network wifiDirectNetwork, final InetAddress groupOwnerAddress) {
        final InetAddress relayLocalAddress = findWifiDirectLocalAddress(groupOwnerAddress);
        final Qcl082RelayLane[] lanes = qcl082RelayTransportProtocolControlTcp()
                ? Qcl082MediaLanes.relayLanes(config, artifact)
                : qcl082RelayLanes(groupOwnerAddress, relayLocalAddress);
        artifact.diagnostic("qcl082_relay", "enabled", true);
        artifact.diagnostic("qcl082_relay", "source_host", config.qcl082RelaySourceHost);
        artifact.diagnostic("qcl082_relay", "source_port", config.qcl082RelaySourcePort);
        artifact.diagnostic("qcl082_relay", "receiver_host", config.qcl082RelayReceiverHost);
        artifact.diagnostic("qcl082_relay", "receiver_port", config.qcl082RelayReceiverPort);
        artifact.diagnostic("qcl082_relay", "lane_count", lanes.length);
        artifact.diagnostic("qcl082_relay", "lane_spec", relayLaneSummary(lanes));
        artifact.diagnostic("qcl082_relay", "socket_owner", "qcl041_wifi_direct_harness");
        artifact.diagnostic("qcl082_relay", "source_owner", "rusty_manifold_broker_media_stream_runtime");
        artifact.diagnostic("qcl082_relay", "transport_protocol", qcl082RelayTransportProtocol());
        artifact.diagnostic("qcl082_relay", "ack_pacing_enabled", config.qcl082AckPacingEnabled);
        artifact.diagnostic("qcl082_relay", "ack_chunk_bytes", config.qcl082AckChunkBytes);
        artifact.diagnostic(
                "qcl082_relay",
                "write_stall_timeout_ms",
                Math.max(0, config.qcl082RelayWriteStallTimeoutMs));
        artifact.diagnostic(
                "qcl082_relay",
                "receiver_progress_timeout_ms",
                Math.max(0, config.qcl082RelayReceiverProgressTimeoutMs));
        artifact.diagnostic(
                "qcl082_relay",
                "port_rotation_count",
                Math.max(1, config.qcl082RelayPortRotationCount));
        if (relayLocalAddress != null) {
            artifact.diagnostic("qcl082_relay", "cached_local_bind_address", relayLocalAddress.getHostAddress());
        }
        artifact.diagnostic("qcl082_relay", "start_delay_ms", Math.max(0, config.qcl082RelayStartDelayMs));
        artifact.writeQuietly();
        if (qcl082RelayTransportProtocolControlTcp()) {
            artifact.diagnostic("qcl082_relay", "handled_by_control_tcp_media_carrier", true);
            for (int index = 0; index < lanes.length; index++) {
                Qcl082RelayLane lane = lanes[index];
                boolean multiLane = lanes.length > 1;
                String section = relaySection(lane, multiLane);
                artifact.diagnostic(section, "label", lane.label);
                artifact.diagnostic(section, "source_host", lane.sourceHost);
                artifact.diagnostic(section, "source_port", lane.sourcePort);
                artifact.diagnostic(section, "receiver_host", lane.receiverHost);
                artifact.diagnostic(section, "receiver_port", lane.receiverPort);
                artifact.diagnostic(section, "transport_protocol", Qcl082ControlTcpMediaCarrier.TRANSPORT_PROTOCOL);
                artifact.diagnostic(section, "status", "deferred-to-control-tcp-carrier");
            }
            artifact.writeQuietly();
            return;
        }
        for (int index = 0; index < lanes.length; index++) {
            final Qcl082RelayLane lane = lanes[index];
            final boolean multiLane = lanes.length > 1;
            final int startDelayMs = Math.max(0, config.qcl082RelayStartDelayMs);
            artifact.diagnostic(relaySection(lane, multiLane), "label", lane.label);
            artifact.diagnostic(relaySection(lane, multiLane), "source_host", lane.sourceHost);
            artifact.diagnostic(relaySection(lane, multiLane), "source_port", lane.sourcePort);
            artifact.diagnostic(relaySection(lane, multiLane), "receiver_host", lane.receiverHost);
            artifact.diagnostic(relaySection(lane, multiLane), "receiver_port", lane.receiverPort);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String section = relaySection(lane, multiLane);
                    artifact.diagnostic(section, "relay_thread_started", true);
                    artifact.diagnostic(section, "relay_thread_start_delay_ms", startDelayMs);
                    artifact.writeQuietly();
                    if (startDelayMs > 0) {
                        try {
                            Thread.sleep(startDelayMs);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            recordQcl082RelayResult(
                                    lane,
                                    multiLane,
                                    "blocked",
                                    0L,
                                    "InterruptedException: relay start delay interrupted",
                                    0L,
                                    new Qcl082CopyProgress());
                            return;
                        }
                    }
                    if (qcl082RelayTransportProtocolReverseTcp()) {
                        runQcl082ReverseTcpSourceExport(
                                relayLocalAddress,
                                lane,
                                multiLane);
                    } else if (qcl082RelayTransportProtocolUdp()) {
                        runQcl082UdpMediaRelay(
                                wifiDirectNetwork,
                                groupOwnerAddress,
                                relayLocalAddress,
                                lane,
                                multiLane);
                    } else {
                        runQcl082MediaRelay(
                                wifiDirectNetwork,
                                groupOwnerAddress,
                                relayLocalAddress,
                                lane,
                                multiLane,
                                lanes.length);
                    }
                }
            }, "qcl082-wifi-direct-media-relay-" + lane.label).start();
        }
    }

    private void startQcl082ReceiveProxy(final Network wifiDirectNetwork, final InetAddress groupOwnerAddress) {
        final InetAddress discoveredProxyLocalAddress = findWifiDirectLocalAddress(groupOwnerAddress);
        final InetAddress proxyLocalAddress = qcl082ReceiveProxyBindAddress(
                wifiDirectNetwork,
                discoveredProxyLocalAddress);
        final Qcl082ReceiveProxyLane[] lanes = qcl082ReceiveProxyLanes();
        artifact.diagnostic("qcl082_receive_proxy", "enabled", true);
        artifact.diagnostic("qcl082_receive_proxy", "listen_port", config.qcl082ReceiveProxyListenPort);
        artifact.diagnostic("qcl082_receive_proxy", "target_host", config.qcl082ReceiveProxyTargetHost);
        artifact.diagnostic("qcl082_receive_proxy", "target_port", config.qcl082ReceiveProxyTargetPort);
        artifact.diagnostic("qcl082_receive_proxy", "lane_count", lanes.length);
        artifact.diagnostic("qcl082_receive_proxy", "lane_spec", receiveProxyLaneSummary(lanes));
        artifact.diagnostic("qcl082_receive_proxy", "socket_owner", "qcl041_wifi_direct_harness");
        artifact.diagnostic("qcl082_receive_proxy", "target_owner", "rusty_manifold_broker_transport_receiver_loopback");
        artifact.diagnostic("qcl082_receive_proxy", "wifi_direct_network_found", wifiDirectNetwork != null);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                "transport_protocol",
                qcl082ReceiveProxyTransportProtocol());
        artifact.diagnostic("qcl082_receive_proxy", "ack_pacing_enabled", config.qcl082AckPacingEnabled);
        artifact.diagnostic("qcl082_receive_proxy", "ack_chunk_bytes", config.qcl082AckChunkBytes);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                "peer_idle_timeout_ms",
                config.qcl082ReceiveProxyPeerIdleTimeoutMs);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                "receiver_progress_timeout_ms",
                Math.max(0, config.qcl082RelayReceiverProgressTimeoutMs));
        artifact.diagnostic(
                "qcl082_receive_proxy",
                "port_rotation_count",
                Math.max(1, config.qcl082RelayPortRotationCount));
        if (discoveredProxyLocalAddress != null) {
            artifact.diagnostic(
                    "qcl082_receive_proxy",
                    "cached_local_bind_address",
                    discoveredProxyLocalAddress.getHostAddress());
        }
        artifact.diagnostic(
                "qcl082_receive_proxy",
                "effective_socket_bind_address",
                proxyLocalAddress == null ? "0.0.0.0" : proxyLocalAddress.getHostAddress());
        String advertisedReceiveAddress = discoveredProxyLocalAddress == null
                ? ""
                : discoveredProxyLocalAddress.getHostAddress();
        artifact.diagnostic(
                "qcl082_receive_proxy",
                "advertised_receive_address",
                advertisedReceiveAddress);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                "advertised_receive_address_source",
                advertisedReceiveAddress.isEmpty()
                        ? "missing_wifi_direct_local_address"
                        : "wifi_direct_local_address");
        artifact.writeQuietly();
        if (qcl082ReceiveProxyTransportProtocolControlTcp()) {
            artifact.diagnostic("qcl082_receive_proxy", "handled_by_control_tcp_media_carrier", true);
            for (int index = 0; index < lanes.length; index++) {
                Qcl082ReceiveProxyLane lane = lanes[index];
                boolean multiLane = lanes.length > 1;
                String section = receiveProxySection(lane, multiLane);
                artifact.diagnostic(section, "label", lane.label);
                artifact.diagnostic(section, "listen_port", lane.listenPort);
                artifact.diagnostic(section, "target_host", lane.targetHost);
                artifact.diagnostic(section, "target_port", lane.targetPort);
                artifact.diagnostic(section, "advertised_receive_address", advertisedReceiveAddress);
                artifact.diagnostic(section, "advertised_receive_port", lane.listenPort);
                artifact.diagnostic(section, "transport_protocol", Qcl082ControlTcpMediaCarrier.TRANSPORT_PROTOCOL);
                artifact.diagnostic(section, "status", "deferred-to-control-tcp-carrier");
            }
            artifact.writeQuietly();
            return;
        }
        for (int index = 0; index < lanes.length; index++) {
            final Qcl082ReceiveProxyLane lane = lanes[index];
            final boolean multiLane = lanes.length > 1;
            final String section = receiveProxySection(lane, multiLane);
            artifact.diagnostic(section, "label", lane.label);
            artifact.diagnostic(section, "listen_port", lane.listenPort);
            artifact.diagnostic(section, "target_host", lane.targetHost);
            artifact.diagnostic(section, "target_port", lane.targetPort);
            artifact.diagnostic(section, "advertised_receive_address", advertisedReceiveAddress);
            artifact.diagnostic(section, "advertised_receive_port", lane.listenPort);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    artifact.diagnostic(section, "receive_proxy_thread_started", true);
                    artifact.writeQuietly();
                    if (qcl082ReceiveProxyTransportProtocolReverseTcp()) {
                        runQcl082ReverseTcpReceiveProxy(
                                wifiDirectNetwork,
                                groupOwnerAddress,
                                proxyLocalAddress,
                                lane,
                                multiLane);
                    } else if (qcl082ReceiveProxyTransportProtocolUdp()) {
                        runQcl082UdpReceiveProxy(
                                wifiDirectNetwork,
                                groupOwnerAddress,
                                proxyLocalAddress,
                                lane,
                                multiLane);
                    } else {
                        runQcl082ReceiveProxy(
                                wifiDirectNetwork,
                                groupOwnerAddress,
                                proxyLocalAddress,
                                lane,
                                multiLane,
                                lanes.length);
                    }
                }
            }, "qcl082-wifi-direct-receive-proxy-" + lane.label).start();
        }
    }

    private InetAddress qcl082ReceiveProxyBindAddress(
            Network wifiDirectNetwork,
            InetAddress discoveredLocalAddress) {
        if (qcl082ReceiveProxyTransportProtocolUdp()
                && config.isQuestPeerRoute()
                && config.isQuestGroupOwnerRole()
                && wifiDirectNetwork == null) {
            if (discoveredLocalAddress != null) {
                artifact.diagnostic(
                        "qcl082_receive_proxy",
                        "socket_bind_policy",
                        "group_owner_wifi_direct_local_address_without_connectivity_network");
                return discoveredLocalAddress;
            }
            artifact.diagnostic(
                    "qcl082_receive_proxy",
                    "socket_bind_policy",
                    "wildcard_for_group_owner_udp_without_connectivity_network");
            return null;
        }
        artifact.diagnostic("qcl082_receive_proxy", "socket_bind_policy", "wifi_direct_local_address");
        return discoveredLocalAddress;
    }

    private void runQcl082ReceiveProxy(
            Network wifiDirectNetwork,
            InetAddress groupOwnerAddress,
            InetAddress proxyLocalAddress,
            Qcl082ReceiveProxyLane lane,
            boolean multiLane,
            int laneCount) {
        final String section = receiveProxySection(lane, multiLane);
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(5, config.qcl082ReceiveProxyTimeoutSeconds) * 1000L;
        long bytesCopied = 0L;
        Qcl082CopyProgress progress = new Qcl082CopyProgress();
        ServerSocket server = null;
        Socket peer = null;
        Socket target = null;
        try {
            artifact.diagnostic(section, "wifi_direct_network_found", wifiDirectNetwork != null);
            while (SystemClock.elapsedRealtime() < deadline
                    && progress.totalBytes < Math.max(1, config.qcl082ReceiveProxyMaxBytes)) {
                int segmentIndex = Math.max(0, (int) progress.copySegments);
                int activeListenPort = qcl082RotatedPort(lane.listenPort, segmentIndex, laneCount);
                server = createQcl082ReceiveProxyServerSocket(
                        proxyLocalAddress,
                        lane,
                        section,
                        activeListenPort,
                        segmentIndex,
                        laneCount);
                peer = acceptReceiveProxyPeerWithRetry(server, deadline, groupOwnerAddress, proxyLocalAddress, section);
                progress.receiveProxyAcceptedPeers++;
                progress.copySegments++;
                peer.setTcpNoDelay(true);
                peer.setSoTimeout(1000);
                target = connectReceiveProxyTargetWithRetry(deadline, lane, section);
                target.setTcpNoDelay(true);
                publishQcl082ReceiveProxyProgress(section, lane, multiLane, progress, started, "streaming");
                bytesCopied = copyReceiveProxyBytes(
                        peer.getInputStream(),
                        target.getOutputStream(),
                        peer.getOutputStream(),
                        Math.max(1, config.qcl082ReceiveProxyMaxBytes),
                        deadline,
                        started,
                        lane,
                        multiLane,
                        progress);
                closeSocketForQcl082Recycle(peer);
                peer = null;
                closeSocketForQcl082Recycle(target);
                target = null;
                closeQuietly(server);
                server = null;
                if (!shouldRecycleQcl082ReceiveProxySegment(progress.completedReason)) {
                    break;
                }
                progress.receiveProxyPeerReconnects++;
                progress.receiverReconnects++;
                progress.completedReason = "running";
                progress.bytesSinceAck = 0L;
                publishQcl082ReceiveProxyProgress(section, lane, multiLane, progress, started, "waiting-for-reconnect");
            }
            recordQcl082ReceiveProxyResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass" : "blocked",
                    bytesCopied,
                    null,
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } catch (Exception ex) {
            bytesCopied = Math.max(bytesCopied, progress.totalBytes);
            recordQcl082ReceiveProxyResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass_with_peer_close" : "blocked",
                    bytesCopied,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } finally {
            closeSocketForQcl082Recycle(peer);
            closeSocketForQcl082Recycle(target);
            closeQuietly(server);
            artifact.writeQuietly();
        }
    }

    private void runQcl082MediaRelay(
            Network wifiDirectNetwork,
            InetAddress groupOwnerAddress,
            InetAddress relayLocalAddress,
            Qcl082RelayLane lane,
            boolean multiLane,
            int laneCount) {
        final String section = relaySection(lane, multiLane);
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(5, config.qcl082RelayTimeoutSeconds) * 1000L;
        long bytesCopied = 0L;
        Qcl082CopyProgress progress = new Qcl082CopyProgress();
        Socket source = null;
        Socket receiver = null;
        try {
            while (SystemClock.elapsedRealtime() < deadline
                    && progress.totalBytes < Math.max(1, config.qcl082RelayMaxBytes)) {
                int segmentIndex = Math.max(0, (int) progress.copySegments);
                int activeReceiverPort = qcl082RotatedPort(lane.receiverPort, segmentIndex, laneCount);
                receiver = connectReceiverWithRetry(
                        deadline,
                        wifiDirectNetwork,
                        relayLocalAddress,
                        groupOwnerAddress,
                        lane,
                        section,
                        activeReceiverPort,
                        segmentIndex,
                        laneCount);
                progress.copySegments++;
                receiver.setTcpNoDelay(true);
                receiver.setSoTimeout(Math.max(250, config.qcl082AckTimeoutMs));
                artifact.diagnostic(section, "receiver_connected_before_source", true);
                source = connectSourceWithRetry(deadline, lane, section);
                artifact.diagnostic(section, "source_connected_after_receiver", true);
                artifact.writeQuietly();
                source.setTcpNoDelay(true);
                source.setSoTimeout(1000);
                publishQcl082RelayProgress(section, lane, multiLane, progress, started, "streaming");
                AtomicBoolean copyActive = new AtomicBoolean(true);
                Thread writeStallWatchdog = startQcl082RelayWriteStallWatchdog(
                        receiver,
                        progress,
                        started,
                        section,
                        lane,
                        multiLane,
                        copyActive);
                Thread receiverProgressWatchdog = startQcl082RelayReceiverProgressWatchdog(
                        receiver,
                        receiver.getInputStream(),
                        progress,
                        started,
                        section,
                        lane,
                        multiLane,
                        copyActive);
                try {
                    bytesCopied = copyMediaBytes(
                            source.getInputStream(),
                            receiver.getOutputStream(),
                            receiver.getInputStream(),
                            Math.max(1, config.qcl082RelayMaxBytes),
                            deadline,
                            started,
                            lane,
                            multiLane,
                            progress);
                } finally {
                    copyActive.set(false);
                    if (writeStallWatchdog != null) {
                        writeStallWatchdog.interrupt();
                    }
                    if (receiverProgressWatchdog != null) {
                        receiverProgressWatchdog.interrupt();
                    }
                }
                closeSocketForQcl082Recycle(receiver);
                receiver = null;
                if (!shouldRecycleQcl082RelaySegment(progress.completedReason)) {
                    break;
                }
                closeSocketForQcl082Recycle(source);
                source = null;
                progress.receiverReconnects++;
                progress.sourceReconnects++;
                progress.completedReason = "running";
                progress.consecutiveAckTimeouts = 0L;
                progress.bytesSinceAck = 0L;
                publishQcl082RelayProgress(section, lane, multiLane, progress, started, "reconnecting-source-and-receiver");
                Thread.sleep(250L);
            }
            recordQcl082RelayResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass" : "blocked",
                    bytesCopied,
                    null,
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } catch (Exception ex) {
            bytesCopied = Math.max(bytesCopied, progress.totalBytes);
            recordQcl082RelayResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass_with_peer_close" : "blocked",
                    bytesCopied,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } finally {
            closeSocketForQcl082Recycle(source);
            closeSocketForQcl082Recycle(receiver);
            artifact.writeQuietly();
        }
    }

    private void runQcl082ReverseTcpSourceExport(
            InetAddress relayLocalAddress,
            Qcl082RelayLane lane,
            boolean multiLane) {
        final String section = relaySection(lane, multiLane);
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(5, config.qcl082RelayTimeoutSeconds) * 1000L;
        long bytesCopied = 0L;
        Qcl082CopyProgress progress = new Qcl082CopyProgress();
        ServerSocket server = null;
        Socket receiver = null;
        Socket source = null;
        try {
            artifact.diagnostic(section, "transport_protocol", "reverse-tcp");
            artifact.diagnostic(section, "reverse_tcp_export_port", lane.receiverPort);
            server = new ServerSocket();
            server.setReuseAddress(true);
            if (relayLocalAddress != null) {
                artifact.diagnostic(
                        section,
                        "reverse_tcp_discovered_local_address",
                        relayLocalAddress.getHostAddress());
                try {
                    server.bind(new InetSocketAddress(relayLocalAddress, lane.receiverPort));
                    artifact.diagnostic(
                            section,
                            "reverse_tcp_server_bound_local_address",
                            relayLocalAddress.getHostAddress());
                    artifact.diagnostic(section, "reverse_tcp_server_bound_port", lane.receiverPort);
                } catch (IOException ex) {
                    artifact.diagnostic(section, "reverse_tcp_server_bind_local_address_error", ex.getMessage());
                    closeQuietly(server);
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new InetSocketAddress(lane.receiverPort));
                    artifact.diagnostic(section, "reverse_tcp_server_bound_local_address", "0.0.0.0");
                    artifact.diagnostic(section, "reverse_tcp_server_bound_port", lane.receiverPort);
                    artifact.diagnostic(section, "reverse_tcp_server_bind_fallback", "wildcard");
                }
            } else {
                server.bind(new InetSocketAddress(lane.receiverPort));
                artifact.diagnostic(section, "reverse_tcp_server_bound_local_address", "0.0.0.0");
                artifact.diagnostic(section, "reverse_tcp_server_bound_port", lane.receiverPort);
            }
            server.setSoTimeout(1000);
            while (SystemClock.elapsedRealtime() < deadline
                    && progress.totalBytes < Math.max(1, config.qcl082RelayMaxBytes)) {
                publishQcl082RelayProgress(section, lane, multiLane, progress, started, "reverse-tcp-listening");
                int acceptAttempts = 0;
                while (SystemClock.elapsedRealtime() < deadline) {
                    acceptAttempts++;
                    try {
                        receiver = server.accept();
                        artifact.diagnostic(section, "reverse_tcp_accept_attempts", acceptAttempts);
                        artifact.diagnostic(section, "reverse_tcp_peer_connected", true);
                        if (receiver.getInetAddress() != null) {
                            artifact.diagnostic(
                                    section,
                                    "reverse_tcp_peer_address",
                                    receiver.getInetAddress().getHostAddress());
                        }
                        break;
                    } catch (SocketTimeoutException timeout) {
                        progress.timeoutPolls++;
                        progress.updateIdle(started);
                        publishQcl082RelayProgress(section, lane, multiLane, progress, started, "reverse-tcp-listening");
                    }
                }
                if (receiver == null) {
                    throw new SocketTimeoutException("reverse TCP source export accept timed out");
                }
                progress.copySegments++;
                receiver.setTcpNoDelay(true);
                receiver.setSoTimeout(Math.max(250, config.qcl082AckTimeoutMs));
                source = connectSourceWithRetry(deadline, lane, section);
                source.setTcpNoDelay(true);
                source.setSoTimeout(1000);
                publishQcl082RelayProgress(section, lane, multiLane, progress, started, "reverse-tcp-streaming");
                AtomicBoolean copyActive = new AtomicBoolean(true);
                Thread writeStallWatchdog = startQcl082RelayWriteStallWatchdog(
                        receiver,
                        progress,
                        started,
                        section,
                        lane,
                        multiLane,
                        copyActive);
                Thread receiverProgressWatchdog = startQcl082RelayReceiverProgressWatchdog(
                        receiver,
                        receiver.getInputStream(),
                        progress,
                        started,
                        section,
                        lane,
                        multiLane,
                        copyActive);
                try {
                    bytesCopied = copyMediaBytes(
                            source.getInputStream(),
                            receiver.getOutputStream(),
                            receiver.getInputStream(),
                            Math.max(1, config.qcl082RelayMaxBytes),
                            deadline,
                            started,
                            lane,
                            multiLane,
                            progress);
                    bytesCopied = Math.max(bytesCopied, progress.totalBytes);
                } finally {
                    copyActive.set(false);
                    if (writeStallWatchdog != null) {
                        writeStallWatchdog.interrupt();
                    }
                    if (receiverProgressWatchdog != null) {
                        receiverProgressWatchdog.interrupt();
                    }
                }
                closeSocketForQcl082Recycle(receiver);
                receiver = null;
                closeSocketForQcl082Recycle(source);
                source = null;
                if (!shouldRecycleQcl082RelaySegment(progress.completedReason)) {
                    break;
                }
                progress.receiverReconnects++;
                progress.sourceReconnects++;
                progress.completedReason = "running";
                progress.consecutiveAckTimeouts = 0L;
                progress.bytesSinceAck = 0L;
                publishQcl082RelayProgress(
                        section,
                        lane,
                        multiLane,
                        progress,
                        started,
                        "reverse-tcp-recycling");
                Thread.sleep(250L);
            }
            recordQcl082RelayResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass" : "blocked",
                    bytesCopied,
                    null,
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } catch (Exception ex) {
            bytesCopied = Math.max(bytesCopied, progress.totalBytes);
            recordQcl082RelayResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass_with_reverse_tcp_error" : "blocked",
                    bytesCopied,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } finally {
            closeSocketForQcl082Recycle(source);
            closeSocketForQcl082Recycle(receiver);
            closeQuietly(server);
            artifact.writeQuietly();
        }
    }

    private void runQcl082ReverseTcpReceiveProxy(
            Network wifiDirectNetwork,
            InetAddress groupOwnerAddress,
            InetAddress proxyLocalAddress,
            Qcl082ReceiveProxyLane lane,
            boolean multiLane) {
        final String section = receiveProxySection(lane, multiLane);
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(5, config.qcl082ReceiveProxyTimeoutSeconds) * 1000L;
        long bytesCopied = 0L;
        Qcl082CopyProgress progress = new Qcl082CopyProgress();
        Socket peer = null;
        Socket target = null;
        try {
            artifact.diagnostic(section, "transport_protocol", "reverse-tcp");
            artifact.diagnostic(section, "reverse_tcp_peer_host", config.qcl082RelayReceiverHost);
            artifact.diagnostic(section, "reverse_tcp_peer_port", lane.listenPort);
            while (SystemClock.elapsedRealtime() < deadline
                    && progress.totalBytes < Math.max(1, config.qcl082ReceiveProxyMaxBytes)) {
                peer = connectReverseTcpSourceExportWithRetry(
                        deadline,
                        wifiDirectNetwork,
                        proxyLocalAddress,
                        groupOwnerAddress,
                        config.qcl082RelayReceiverHost,
                        lane.listenPort,
                        section);
                progress.copySegments++;
                progress.receiveProxyAcceptedPeers++;
                peer.setTcpNoDelay(true);
                peer.setSoTimeout(1000);
                target = connectReceiveProxyTargetWithRetry(deadline, lane, section);
                target.setTcpNoDelay(true);
                publishQcl082ReceiveProxyProgress(section, lane, multiLane, progress, started, "reverse-tcp-streaming");
                bytesCopied = copyReceiveProxyBytes(
                        peer.getInputStream(),
                        target.getOutputStream(),
                        peer.getOutputStream(),
                        Math.max(1, config.qcl082ReceiveProxyMaxBytes),
                        deadline,
                        started,
                        lane,
                        multiLane,
                        progress);
                bytesCopied = Math.max(bytesCopied, progress.totalBytes);
                closeSocketForQcl082Recycle(peer);
                peer = null;
                closeSocketForQcl082Recycle(target);
                target = null;
                if (!shouldRecycleQcl082ReceiveProxySegment(progress.completedReason)) {
                    break;
                }
                progress.receiveProxyPeerReconnects++;
                progress.receiverReconnects++;
                progress.completedReason = "running";
                progress.consecutiveAckTimeouts = 0L;
                progress.bytesSinceAck = 0L;
                publishQcl082ReceiveProxyProgress(
                        section,
                        lane,
                        multiLane,
                        progress,
                        started,
                        "reverse-tcp-reconnecting-peer");
                Thread.sleep(250L);
            }
            recordQcl082ReceiveProxyResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass" : "blocked",
                    bytesCopied,
                    null,
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } catch (Exception ex) {
            bytesCopied = Math.max(bytesCopied, progress.totalBytes);
            recordQcl082ReceiveProxyResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass_with_reverse_tcp_error" : "blocked",
                    bytesCopied,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } finally {
            closeSocketForQcl082Recycle(peer);
            closeSocketForQcl082Recycle(target);
            artifact.writeQuietly();
        }
    }

    private InetAddress[] qcl082UdpReceiverAddresses(
            Qcl082RelayLane lane,
            InetAddress relayLocalAddress,
            String section) throws IOException {
        String receiverHost = lane.receiverHost == null ? "" : lane.receiverHost.trim();
        artifact.diagnostic(section, "configured_receiver_host", config.qcl082RelayReceiverHost);
        artifact.diagnostic(section, "configured_receiver_port", config.qcl082RelayReceiverPort);
        if (QCL082_DEFERRED_TARGET_MISSING_HOST.equals(receiverHost)) {
            artifact.diagnostic(section, "promotion_failure_reason", "deferred_receiver_target_missing");
            throw new IOException("QCL-082 deferred receiver target missing for current topology epoch");
        }
        if (!receiverHost.startsWith(QCL082_P2P_FANOUT_PREFIX)) {
            InetAddress receiverAddress = InetAddress.getByName(receiverHost);
            artifact.diagnostic(section, "udp_receiver_fanout_enabled", false);
            artifact.diagnostic(section, "udp_receiver_address_count", 1);
            artifact.diagnostic(section, "udp_receiver_address", receiverAddress.getHostAddress());
            artifact.diagnostic(section, "final_target_host", receiverAddress.getHostAddress());
            artifact.diagnostic(section, "final_target_port", lane.receiverPort);
            return new InetAddress[] { receiverAddress };
        }

        int dash = receiverHost.lastIndexOf('-');
        if (dash <= QCL082_P2P_FANOUT_PREFIX.length()) {
            throw new IOException("invalid QCL-082 P2P fanout receiver host: " + receiverHost);
        }
        int rangeStart = parsePositiveInt(
                receiverHost.substring(QCL082_P2P_FANOUT_PREFIX.length(), dash),
                -1);
        int rangeEnd = parsePositiveInt(receiverHost.substring(dash + 1), -1);
        if (rangeStart <= 0 || rangeEnd <= 0 || rangeStart > rangeEnd || rangeEnd > 254) {
            throw new IOException("invalid QCL-082 P2P fanout receiver range: " + receiverHost);
        }

        String subnetPrefix = "192.168.49";
        int localHostOctet = -1;
        if (relayLocalAddress instanceof Inet4Address) {
            byte[] bytes = relayLocalAddress.getAddress();
            subnetPrefix = (bytes[0] & 0xff)
                    + "."
                    + (bytes[1] & 0xff)
                    + "."
                    + (bytes[2] & 0xff);
            localHostOctet = bytes[3] & 0xff;
        }

        List<InetAddress> receiverAddresses = new ArrayList<>();
        for (int hostOctet = rangeStart; hostOctet <= rangeEnd; hostOctet++) {
            if (hostOctet == localHostOctet) {
                continue;
            }
            receiverAddresses.add(InetAddress.getByName(subnetPrefix + "." + hostOctet));
        }
        if (receiverAddresses.isEmpty()) {
            throw new IOException("QCL-082 P2P fanout receiver range produced no peer addresses");
        }
        artifact.diagnostic(section, "udp_receiver_fanout_enabled", true);
        artifact.diagnostic(section, "udp_receiver_fanout_spec", receiverHost);
        artifact.diagnostic(section, "udp_receiver_fanout_subnet_prefix", subnetPrefix);
        artifact.diagnostic(section, "udp_receiver_address_count", receiverAddresses.size());
        artifact.diagnostic(section, "udp_receiver_address_first", receiverAddresses.get(0).getHostAddress());
        artifact.diagnostic(
                section,
                "udp_receiver_address_last",
                receiverAddresses.get(receiverAddresses.size() - 1).getHostAddress());
        return receiverAddresses.toArray(new InetAddress[receiverAddresses.size()]);
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void runQcl082UdpMediaRelay(
            Network wifiDirectNetwork,
            InetAddress groupOwnerAddress,
            InetAddress relayLocalAddress,
            Qcl082RelayLane lane,
            boolean multiLane) {
        final String section = relaySection(lane, multiLane);
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(5, config.qcl082RelayTimeoutSeconds) * 1000L;
        long bytesCopied = 0L;
        Qcl082CopyProgress progress = new Qcl082CopyProgress();
        Socket source = null;
        DatagramSocket udpSocket = null;
        try {
            artifact.diagnostic(section, "transport_protocol", "udp");
            artifact.diagnostic(section, "udp_payload_bytes", QCL082_UDP_PAYLOAD_BYTES);
            artifact.diagnostic(section, "udp_send_pace_ms", QCL082_UDP_SEND_PACE_MS);
            boolean requireNetworkBinding = qcl082UdpMediaRelayRequiresWifiDirectNetworkBinding();
            artifact.diagnostic(section, "udp_sender_wifi_direct_network_binding_required", requireNetworkBinding);
            Network selectedWifiDirectNetwork = wifiDirectNetwork;
            if (requireNetworkBinding) {
                artifact.diagnostic(section, "relay_udp_process_bound_to_wifi_direct_network", false);
                artifact.diagnostic(
                        section,
                        "relay_udp_process_network_bind_reason",
                        "skipped_for_required_socket_level_binding");
                selectedWifiDirectNetwork = findUsableWifiDirectNetwork(
                        groupOwnerAddress,
                        section,
                        "relay_udp_required_usable_network_",
                        QCL082_UDP_PEER_PROOF_MS,
                        wifiDirectNetwork);
                if (selectedWifiDirectNetwork == null) {
                    throw new IOException(
                            "QCL-082 UDP media relay requires route-matched non-partial Wi-Fi Direct Network");
                }
            } else {
                bindQcl082UdpRelayProcessToWifiDirectNetwork(selectedWifiDirectNetwork, section);
            }
            InetAddress udpRelayLocalAddress = requireNetworkBinding ? null : relayLocalAddress;
            artifact.diagnostic(
                    section,
                    "relay_udp_required_binding_mode",
                    requireNetworkBinding ? "network_bound_auto_bind_source" : "legacy_local_bind");
            udpSocket = createQcl082DatagramSocket(
                    selectedWifiDirectNetwork,
                    udpRelayLocalAddress,
                    0,
                    section,
                    "relay_udp",
                    requireNetworkBinding);
            if (requireNetworkBinding) {
                artifact.diagnostic(section, "tcp_peer_proof_warmup_enabled", false);
                artifact.diagnostic(
                        section,
                        "tcp_peer_proof_warmup_disabled_reason",
                        "qcl041_app_bound_udp_matrix_passed_but_tcp_warmup_failed");
            }
            InetAddress[] receiverAddresses = qcl082UdpReceiverAddresses(lane, relayLocalAddress, section);
            boolean peerProofAckReceived = sendQcl082UdpPeerProofProbe(
                    udpSocket,
                    receiverAddresses,
                    lane,
                    section,
                    requireNetworkBinding);
            if (requireNetworkBinding && !peerProofAckReceived) {
                throw new IOException("QCL-082 UDP peer-proof ACK not received before media source connect");
            }
            source = connectSourceWithRetry(deadline, lane, section);
            source.setTcpNoDelay(true);
            source.setSoTimeout(1000);
            byte[] inputBuffer = new byte[QCL082_UDP_PAYLOAD_BYTES];
            byte[] packetBuffer = new byte[QCL082_UDP_HEADER_BYTES + QCL082_UDP_PAYLOAD_BYTES];
            InputStream input = source.getInputStream();
            long total = progress.totalBytes;
            int sequence = 0;
            long datagramsSent = 0L;
            int udpSendErrors = 0;
            int udpSocketRecreates = 0;
            long nextProgressWriteMs = 0L;
            publishQcl082RelayProgress(section, lane, multiLane, progress, started, "udp-streaming");
            while (total < Math.max(1, config.qcl082RelayMaxBytes)
                    && SystemClock.elapsedRealtime() < deadline) {
                int read;
                try {
                    read = input.read(
                            inputBuffer,
                            0,
                            Math.min(inputBuffer.length, Math.max(1, config.qcl082RelayMaxBytes - (int) total)));
                } catch (SocketTimeoutException timeout) {
                    progress.timeoutPolls++;
                    progress.updateIdle(started);
                    long now = SystemClock.elapsedRealtime();
                    if (now >= nextProgressWriteMs) {
                        publishQcl082RelayProgress(section, lane, multiLane, progress, started, "udp-streaming");
                        nextProgressWriteMs = now + 1000L;
                    }
                    continue;
                }
                if (read < 0) {
                    progress.completedReason = "source_eof";
                    break;
                }
                if (read == 0) {
                    continue;
                }
                progress.noteRead(read, started);
                writeQcl082UdpHeader(packetBuffer, sequence, read);
                System.arraycopy(inputBuffer, 0, packetBuffer, QCL082_UDP_HEADER_BYTES, read);
                for (InetAddress receiverAddress : receiverAddresses) {
                    DatagramPacket packet = new DatagramPacket(
                            packetBuffer,
                            QCL082_UDP_HEADER_BYTES + read,
                            receiverAddress,
                            lane.receiverPort);
                    boolean sentPacket = false;
                    while (!sentPacket) {
                        try {
                            udpSocket.send(packet);
                            datagramsSent++;
                            sentPacket = true;
                        } catch (IOException ex) {
                            udpSendErrors++;
                            artifact.diagnostic(section, "udp_send_errors", udpSendErrors);
                            artifact.diagnostic(
                                    section,
                                    "udp_last_send_error",
                                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
                            if (!requireNetworkBinding
                                    || !shouldRecycleQcl082UdpSendSocket(ex)
                                    || udpSocketRecreates >= Math.max(1, config.qcl082RelayPortRotationCount)
                                    || SystemClock.elapsedRealtime() >= deadline) {
                                throw ex;
                            }
                            udpSocketRecreates++;
                            artifact.diagnostic(section, "udp_socket_recreate_count", udpSocketRecreates);
                            artifact.diagnostic(
                                    section,
                                    "udp_socket_recreate_reason",
                                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
                            closeQuietly(udpSocket);
                            selectedWifiDirectNetwork = findUsableWifiDirectNetwork(
                                    groupOwnerAddress,
                                    section,
                                    "relay_udp_recreate_" + udpSocketRecreates + "_usable_network_",
                                    1500L);
                            if (selectedWifiDirectNetwork == null) {
                                throw ex;
                            }
                            udpSocket = createQcl082DatagramSocket(
                                    selectedWifiDirectNetwork,
                                    null,
                                    0,
                                    section,
                                    "relay_udp_recreate_" + udpSocketRecreates,
                                    true);
                            artifact.diagnostic(
                                    section,
                                    "udp_socket_recreate_last_local_socket",
                                    String.valueOf(udpSocket.getLocalSocketAddress()));
                            Thread.sleep(25L);
                        }
                    }
                }
                sequence++;
                if (QCL082_UDP_SEND_PACE_MS > 0L) {
                    Thread.sleep(QCL082_UDP_SEND_PACE_MS);
                }
                total += read;
                progress.noteWrite(total, read, started);
                long now = SystemClock.elapsedRealtime();
                if (now >= nextProgressWriteMs) {
                    artifact.diagnostic(section, "udp_source_packets", sequence);
                    artifact.diagnostic(section, "udp_sent_datagrams", datagramsSent);
                    publishQcl082RelayProgress(section, lane, multiLane, progress, started, "udp-streaming");
                    nextProgressWriteMs = now + 1000L;
                }
            }
            if ("running".equals(progress.completedReason)) {
                progress.completedReason = total >= Math.max(1, config.qcl082RelayMaxBytes)
                        ? "max_bytes"
                        : "deadline_elapsed";
            }
            artifact.diagnostic(section, "udp_source_packets", sequence);
            artifact.diagnostic(section, "udp_sent_datagrams", datagramsSent);
            artifact.diagnostic(section, "udp_send_errors", udpSendErrors);
            artifact.diagnostic(section, "udp_socket_recreate_count", udpSocketRecreates);
            publishQcl082RelayProgress(section, lane, multiLane, progress, started, "udp-completed");
            bytesCopied = total;
            recordQcl082RelayResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass" : "blocked",
                    bytesCopied,
                    null,
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } catch (Exception ex) {
            bytesCopied = Math.max(bytesCopied, progress.totalBytes);
            recordQcl082RelayResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass_with_udp_error" : "blocked",
                    bytesCopied,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } finally {
            closeSocketForQcl082Recycle(source);
            closeQuietly(udpSocket);
            artifact.writeQuietly();
        }
    }

    private boolean qcl082UdpMediaRelayRequiresWifiDirectNetworkBinding() {
        return config.isQuestPeerRoute() && !config.isQuestGroupOwnerRole();
    }

    private boolean shouldRecycleQcl082UdpSendSocket(IOException ex) {
        String text = ex == null ? "" : String.valueOf(ex.getMessage()).toLowerCase(Locale.US);
        return text.contains("enetunreach")
                || text.contains("ehostunreach")
                || text.contains("network is unreachable")
                || text.contains("no route to host");
    }

    private boolean qcl082UdpReceiveProxyAllowsNetworkBinding() {
        return qcl082ReceiveProxyTransportProtocolUdp();
    }

    private boolean qcl082UdpReceiveProxyRequiresNetworkBinding() {
        return config.qcl082UdpReceiveProxyRequireWifiDirectNetworkBinding
                && qcl082ReceiveProxyTransportProtocolUdp();
    }

    private boolean qcl082UdpPeerProofAckAllowedForReceiveProxy() {
        return config.isQuestPeerRoute()
                && config.isQuestGroupOwnerRole()
                && qcl082ReceiveProxyTransportProtocolUdp();
    }

    private boolean sendQcl082UdpPeerProofProbe(
            DatagramSocket udpSocket,
            InetAddress[] receiverAddresses,
            Qcl082RelayLane lane,
            String section,
            boolean requireAck) throws IOException, InterruptedException {
        if (!config.isQuestPeerRoute()
                || udpSocket == null
                || receiverAddresses == null
                || receiverAddresses.length == 0) {
            artifact.diagnostic(section, "udp_peer_proof_tx_enabled", false);
            return false;
        }
        int sent = 0;
        int attempts = 0;
        long started = SystemClock.elapsedRealtime();
        long deadline = requireAck ? started + QCL082_UDP_PEER_PROOF_MS : started;
        int originalTimeoutMs = 1000;
        artifact.diagnostic(section, "udp_peer_proof_tx_enabled", true);
        artifact.diagnostic(section, "udp_peer_proof_tx_role", config.q2qRole);
        artifact.diagnostic(section, "udp_peer_proof_ack_required", requireAck);
        artifact.diagnostic(section, "udp_peer_proof_tx_packet_count", requireAck ? -1 : 8);
        artifact.diagnostic(section, "udp_peer_proof_topology_epoch", config.qcl082TopologyEpoch);
        try {
            originalTimeoutMs = udpSocket.getSoTimeout();
        } catch (Exception ignored) {
        }
        byte[] receiveBuffer = new byte[512];
        int sendErrors = 0;
        try {
            if (requireAck) {
                udpSocket.setSoTimeout(250);
            }
            int sequence = 0;
            while (requireAck
                    ? SystemClock.elapsedRealtime() < deadline
                    : sequence < 8) {
                attempts++;
                String payload = QCL082_UDP_PEER_HELLO
                        + ";run_id=" + config.runId
                        + ";epoch=" + config.qcl082TopologyEpoch
                        + ";lane=" + lane.label
                        + ";seq=" + sequence;
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                for (InetAddress receiverAddress : receiverAddresses) {
                    try {
                        udpSocket.send(new DatagramPacket(bytes, bytes.length, receiverAddress, lane.receiverPort));
                        sent++;
                        artifact.diagnostic(
                                section,
                                "udp_peer_proof_last_send_target",
                                receiverAddress.getHostAddress() + ":" + lane.receiverPort);
                        artifact.diagnostic(
                                section,
                                "udp_peer_proof_last_local_socket",
                                String.valueOf(udpSocket.getLocalSocketAddress()));
                    } catch (IOException ex) {
                        if (!requireAck) {
                            throw ex;
                        }
                        sendErrors++;
                        artifact.diagnostic(section, "udp_peer_proof_tx_send_errors", sendErrors);
                        artifact.diagnostic(
                                section,
                                "udp_peer_proof_last_send_error",
                                ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    }
                }
                sequence++;
                if (!requireAck) {
                    Thread.sleep(25L);
                    continue;
                }
                long waitUntil = SystemClock.elapsedRealtime() + 250L;
                while (SystemClock.elapsedRealtime() < waitUntil) {
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    try {
                        udpSocket.receive(response);
                    } catch (SocketTimeoutException timeout) {
                        break;
                    }
                    String ack = new String(
                            response.getData(),
                            response.getOffset(),
                            response.getLength(),
                            StandardCharsets.UTF_8);
                    artifact.diagnostic(section, "udp_peer_proof_ack_payload", ack);
                    if (!ack.startsWith(QCL082_UDP_PEER_ACK)
                            || !ack.contains("run_id=" + config.runId)
                            || !ack.contains("epoch=" + config.qcl082TopologyEpoch)
                            || !ack.contains("lane=" + lane.label)) {
                        artifact.diagnostic(section, "udp_peer_proof_ack_rejected_payload", ack);
                        continue;
                    }
                    InetAddress ackAddress = response.getAddress();
                    artifact.diagnostic(
                            section,
                            "udp_peer_proof_ack_source_address",
                            ackAddress == null ? "" : ackAddress.getHostAddress());
                    artifact.diagnostic(section, "udp_peer_proof_ack_source_port", response.getPort());
                    artifact.diagnostic(section, "udp_peer_proof_ack_received", true);
                    artifact.diagnostic(section, "udp_peer_proof_ack_elapsed_ms",
                            SystemClock.elapsedRealtime() - started);
                    artifact.diagnostic(section, "udp_peer_proof_media_start_gate", "ack_received");
                    artifact.diagnostic(section, "udp_peer_proof_tx_attempts", attempts);
                    artifact.diagnostic(section, "udp_peer_proof_tx_sent", sent);
                    return true;
                }
                Thread.sleep(25L);
            }
        } finally {
            if (requireAck) {
                try {
                    udpSocket.setSoTimeout(Math.max(1, originalTimeoutMs));
                } catch (Exception ignored) {
                }
            }
        }
        artifact.diagnostic(section, "udp_peer_proof_tx_attempts", attempts);
        artifact.diagnostic(section, "udp_peer_proof_tx_sent", sent);
        artifact.diagnostic(section, "udp_peer_proof_tx_send_errors", sendErrors);
        artifact.diagnostic(section, "udp_peer_proof_ack_received", false);
        artifact.diagnostic(section, "udp_peer_proof_ack_elapsed_ms", SystemClock.elapsedRealtime() - started);
        artifact.diagnostic(
                section,
                "udp_peer_proof_media_start_gate",
                requireAck ? "ack_timeout" : "ack_not_required");
        return !requireAck;
    }

    private void sendQcl082UdpPeerProofHelloIfNeeded(
            DatagramSocket udpSocket,
            InetAddress groupOwnerAddress,
            Qcl082ReceiveProxyLane lane,
            String section) {
        if (!config.isQuestPeerRoute()
                || config.isQuestGroupOwnerRole()
                || !qcl082ReceiveProxyTransportProtocolUdp()
                || groupOwnerAddress == null
                || udpSocket == null) {
            artifact.diagnostic(section, "udp_peer_proof_hello_enabled", false);
            return;
        }
        long started = SystemClock.elapsedRealtime();
        long deadline = started + QCL082_UDP_PEER_PROOF_MS;
        int attempts = 0;
        int originalTimeoutMs = 1000;
        try {
            originalTimeoutMs = udpSocket.getSoTimeout();
        } catch (Exception ignored) {
        }
        try {
            udpSocket.setSoTimeout(500);
            artifact.diagnostic(section, "udp_peer_proof_hello_enabled", true);
            artifact.diagnostic(section, "udp_peer_proof_hello_target_host", groupOwnerAddress.getHostAddress());
            artifact.diagnostic(section, "udp_peer_proof_hello_target_port", lane.listenPort);
            artifact.diagnostic(section, "udp_peer_proof_hello_local_port", udpSocket.getLocalPort());
            byte[] receiveBuffer = new byte[512];
            while (SystemClock.elapsedRealtime() < deadline) {
                attempts++;
                String hello = QCL082_UDP_PEER_HELLO
                        + ";run_id=" + config.runId
                        + ";lane=" + lane.label
                        + ";listen_port=" + lane.listenPort;
                byte[] helloBytes = hello.getBytes(StandardCharsets.UTF_8);
                udpSocket.send(new DatagramPacket(
                        helloBytes,
                        helloBytes.length,
                        groupOwnerAddress,
                        lane.listenPort));
                artifact.diagnostic(section, "udp_peer_proof_hello_attempts", attempts);
                DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                try {
                    udpSocket.receive(response);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                String payload = new String(
                        response.getData(),
                        response.getOffset(),
                        response.getLength(),
                        StandardCharsets.UTF_8);
                artifact.diagnostic(section, "udp_peer_proof_ack_payload", payload);
                if (!payload.startsWith(QCL082_UDP_PEER_ACK)
                        || !payload.contains("run_id=" + config.runId)
                        || !payload.contains("lane=" + lane.label)) {
                    artifact.diagnostic(section, "udp_peer_proof_ack_rejected_payload", payload);
                    continue;
                }
                InetAddress ackAddress = response.getAddress();
                int ackPort = response.getPort();
                artifact.diagnostic(
                        section,
                        "udp_peer_proof_ack_source_address",
                        ackAddress == null ? "" : ackAddress.getHostAddress());
                artifact.diagnostic(section, "udp_peer_proof_ack_source_port", ackPort);
                artifact.diagnostic(
                        section,
                        "udp_peer_proof_ack_source_matches_group_owner",
                        ackAddress != null && ackAddress.equals(groupOwnerAddress));
                artifact.diagnostic(section, "udp_peer_proof_ack_received", true);
                artifact.diagnostic(section, "udp_peer_proof_elapsed_ms", SystemClock.elapsedRealtime() - started);
                return;
            }
        } catch (Exception ex) {
            artifact.diagnostic(section, "udp_peer_proof_hello_error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            try {
                udpSocket.setSoTimeout(Math.max(1, originalTimeoutMs));
            } catch (Exception ignored) {
            }
        }
        artifact.diagnostic(section, "udp_peer_proof_ack_received", false);
        artifact.diagnostic(section, "udp_peer_proof_elapsed_ms", SystemClock.elapsedRealtime() - started);
    }

    private void runQcl082UdpReceiveProxy(
            Network wifiDirectNetwork,
            InetAddress groupOwnerAddress,
            InetAddress proxyLocalAddress,
            Qcl082ReceiveProxyLane lane,
            boolean multiLane) {
        final String section = receiveProxySection(lane, multiLane);
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(5, config.qcl082ReceiveProxyTimeoutSeconds) * 1000L;
        long bytesCopied = 0L;
        Qcl082CopyProgress progress = new Qcl082CopyProgress();
        DatagramSocket udpSocket = null;
        Socket target = null;
        try {
            artifact.diagnostic(section, "transport_protocol", "udp");
            artifact.diagnostic(section, "active_listen_port", lane.listenPort);
            artifact.diagnostic(section, "udp_payload_bytes", QCL082_UDP_PAYLOAD_BYTES);
            boolean allowNetworkBinding = qcl082UdpReceiveProxyAllowsNetworkBinding();
            boolean requireNetworkBinding = qcl082UdpReceiveProxyRequiresNetworkBinding();
            artifact.diagnostic(
                    section,
                    "udp_receive_proxy_wifi_direct_network_binding_required",
                    requireNetworkBinding);
            artifact.diagnostic(
                    section,
                    "udp_receive_proxy_wifi_direct_network_binding_skipped",
                    !allowNetworkBinding);
            artifact.diagnostic(
                    section,
                    "udp_receive_proxy_wifi_direct_network_binding_mode",
                    requireNetworkBinding
                            ? "required_socket_level_network_bind"
                            : allowNetworkBinding
                            ? "socket_level_network_bind_with_local_p2p_fallback"
                            : "local_p2p_bind_only");
            Network selectedWifiDirectNetwork = wifiDirectNetwork;
            if (requireNetworkBinding) {
                selectedWifiDirectNetwork = findUsableWifiDirectNetwork(
                        groupOwnerAddress,
                        section,
                        "receive_proxy_udp_required_usable_network_",
                        QCL082_UDP_PEER_PROOF_MS,
                        wifiDirectNetwork);
                if (selectedWifiDirectNetwork == null) {
                    throw new IOException(
                            "QCL-082 UDP receive proxy requires route-matched non-partial Wi-Fi Direct Network");
                }
            }
            artifact.diagnostic(
                    section,
                    "receive_proxy_udp_required_binding_mode",
                    requireNetworkBinding ? "network_bound_listen_socket" : "legacy_local_p2p_listener");
            udpSocket = createQcl082DatagramSocket(
                    selectedWifiDirectNetwork,
                    proxyLocalAddress,
                    lane.listenPort,
                    section,
                    "receive_proxy_udp",
                    requireNetworkBinding,
                    allowNetworkBinding);
            udpSocket.setReceiveBufferSize(QCL082_UDP_SOCKET_BUFFER_BYTES);
            artifact.diagnostic(section, "udp_receive_buffer_bytes", udpSocket.getReceiveBufferSize());
            InetAddress udpLocalAddress = udpSocket.getLocalAddress();
            artifact.diagnostic(
                    section,
                    "effective_receiver_bind_address",
                    udpLocalAddress == null ? "" : udpLocalAddress.getHostAddress());
            artifact.diagnostic(section, "effective_receiver_bind_port", udpSocket.getLocalPort());
            udpSocket.setSoTimeout(1000);
            artifact.diagnostic(
                    section,
                    "udp_peer_proof_legacy_reverse_hello_skipped",
                    "client_to_group_owner_udp_unproven");
            waitForQcl082UdpPeerProofBeforeTargetIfNeeded(udpSocket, lane, section, started);
            target = connectReceiveProxyTargetWithRetry(deadline, lane, section);
            target.setTcpNoDelay(true);
            OutputStream output = target.getOutputStream();
            byte[] packetBuffer = new byte[QCL082_UDP_HEADER_BYTES + QCL082_UDP_PAYLOAD_BYTES];
            long total = progress.totalBytes;
            int expectedSequence = 0;
            long acceptedDatagrams = 0L;
            long sequenceResyncs = 0L;
            long nextProgressWriteMs = 0L;
            publishQcl082ReceiveProxyProgress(section, lane, multiLane, progress, started, "udp-listening");
            while (total < Math.max(1, config.qcl082ReceiveProxyMaxBytes)
                    && SystemClock.elapsedRealtime() < deadline) {
                DatagramPacket packet = new DatagramPacket(packetBuffer, packetBuffer.length);
                try {
                    udpSocket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    progress.timeoutPolls++;
                    progress.updateIdle(started);
                    long now = SystemClock.elapsedRealtime();
                    if (now >= nextProgressWriteMs) {
                        publishQcl082ReceiveProxyProgress(
                                section,
                                lane,
                                multiLane,
                                progress,
                                started,
                                "udp-listening");
                        nextProgressWriteMs = now + 1000L;
                    }
                    continue;
                }
                int packetLength = packet.getLength();
                if (recordQcl082UdpPeerProofProbeIfPresent(udpSocket, packet, lane, section, started)) {
                    continue;
                }
                if (packetLength < QCL082_UDP_HEADER_BYTES
                        || readQcl082UdpInt(packetBuffer, 0) != QCL082_UDP_MAGIC) {
                    artifact.diagnostic(section, "udp_invalid_datagrams", progress.timeoutPolls + 1);
                    continue;
                }
                InetAddress observedSenderAddress = packet.getAddress();
                if (observedSenderAddress != null) {
                    artifact.diagnostic(
                            section,
                            "udp_last_sender_source_address",
                            observedSenderAddress.getHostAddress());
                }
                artifact.diagnostic(section, "udp_last_sender_source_port", packet.getPort());
                int sequence = readQcl082UdpInt(packetBuffer, 4);
                int payloadLength = readQcl082UdpInt(packetBuffer, 8);
                if (payloadLength < 0
                        || payloadLength > QCL082_UDP_PAYLOAD_BYTES
                        || payloadLength + QCL082_UDP_HEADER_BYTES > packetLength) {
                    artifact.diagnostic(section, "udp_invalid_payload_length", payloadLength);
                    continue;
                }
                if (sequence != expectedSequence) {
                    artifact.diagnostic(section, "udp_sequence_gap_expected", expectedSequence);
                    artifact.diagnostic(section, "udp_sequence_gap_observed", sequence);
                    sequenceResyncs++;
                    artifact.diagnostic(section, "udp_sequence_resync_count", sequenceResyncs);
                    if (expectedSequence == 0 && acceptedDatagrams == 0L) {
                        artifact.diagnostic(section, "udp_initial_sequence_resync", true);
                        artifact.diagnostic(section, "udp_initial_sequence_observed", sequence);
                        artifact.diagnostic(section, "udp_initial_sequence_missing_count", sequence);
                        artifact.diagnostic(section, "udp_waiting_for_sequence_zero", false);
                    } else {
                        artifact.diagnostic(section, "udp_sequence_gap_resync", true);
                    }
                    expectedSequence = sequence;
                }
                progress.noteRead(payloadLength, started);
                output.write(packetBuffer, QCL082_UDP_HEADER_BYTES, payloadLength);
                output.flush();
                total += payloadLength;
                progress.noteWrite(total, payloadLength, started);
                expectedSequence++;
                acceptedDatagrams++;
                long now = SystemClock.elapsedRealtime();
                if (now >= nextProgressWriteMs) {
                    artifact.diagnostic(section, "udp_received_datagrams", acceptedDatagrams);
                    artifact.diagnostic(section, "udp_next_expected_sequence", expectedSequence);
                    publishQcl082ReceiveProxyProgress(
                            section,
                            lane,
                            multiLane,
                            progress,
                            started,
                            "udp-streaming");
                    nextProgressWriteMs = now + 1000L;
                }
            }
            if ("running".equals(progress.completedReason)) {
                progress.completedReason = total >= Math.max(1, config.qcl082ReceiveProxyMaxBytes)
                        ? "max_bytes"
                        : "deadline_elapsed";
            }
            artifact.diagnostic(section, "udp_received_datagrams", acceptedDatagrams);
            artifact.diagnostic(section, "udp_next_expected_sequence", expectedSequence);
            publishQcl082ReceiveProxyProgress(section, lane, multiLane, progress, started, "udp-completed");
            bytesCopied = total;
            recordQcl082ReceiveProxyResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass" : "blocked",
                    bytesCopied,
                    null,
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } catch (Exception ex) {
            bytesCopied = Math.max(bytesCopied, progress.totalBytes);
            recordQcl082ReceiveProxyResult(
                    lane,
                    multiLane,
                    bytesCopied > 0 ? "pass_with_udp_error" : "blocked",
                    bytesCopied,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    SystemClock.elapsedRealtime() - started,
                    progress);
        } finally {
            closeQuietly(udpSocket);
            closeSocketForQcl082Recycle(target);
            artifact.writeQuietly();
        }
    }

    private boolean recordQcl082UdpPeerProofProbeIfPresent(
            DatagramSocket socket,
            DatagramPacket packet,
            Qcl082ReceiveProxyLane lane,
            String section,
            long startedMs) {
        String payload;
        try {
            payload = new String(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getLength(),
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return false;
        }
        if (!payload.startsWith(QCL082_UDP_PEER_HELLO)) {
            return false;
        }
        artifact.diagnostic(section, "udp_peer_proof_rx_payload", payload);
        if (!payload.contains("run_id=" + config.runId)
                || !payload.contains("epoch=" + config.qcl082TopologyEpoch)
                || !payload.contains("lane=" + lane.label)) {
            artifact.diagnostic(section, "udp_peer_proof_rejected_payload", payload);
            return true;
        }
        InetAddress sourceAddress = packet.getAddress();
        int sourcePort = packet.getPort();
        int sequence = parsePeerProofSequence(payload);
        artifact.diagnostic(section, "udp_peer_proof_source_address",
                sourceAddress == null ? "" : sourceAddress.getHostAddress());
        artifact.diagnostic(section, "udp_peer_proof_source_port", sourcePort);
        artifact.diagnostic(section, "udp_peer_proof_last_seq", sequence);
        artifact.diagnostic(section, "udp_peer_proof_last_rx_elapsed_ms",
                SystemClock.elapsedRealtime() - startedMs);
        artifact.diagnostic(section, "udp_peer_proof_last_rx_unix_ms",
                System.currentTimeMillis());
        artifact.diagnostic(section, "udp_peer_proof_observed_source", sourceAddress != null);
        artifact.diagnostic(section, "udp_peer_proof_rx_count", Math.max(1, sequence + 1));
        if (!qcl082UdpPeerProofAckAllowedForReceiveProxy()) {
            artifact.diagnostic(section, "udp_peer_proof_ack_sent", false);
            artifact.diagnostic(
                    section,
                    "udp_peer_proof_ack_skipped",
                    "ack_not_required_for_go_to_client_probe");
            artifact.writeQuietly();
            return true;
        }
        if (socket != null && sourceAddress != null) {
            try {
                InetAddress localAddress = socket.getLocalAddress();
                String ack = QCL082_UDP_PEER_ACK
                        + ";run_id=" + config.runId
                        + ";epoch=" + config.qcl082TopologyEpoch
                        + ";lane=" + lane.label
                        + ";seq=" + sequence
                        + ";receiver=" + (localAddress == null ? "" : localAddress.getHostAddress())
                        + ";observed_source=" + sourceAddress.getHostAddress()
                        + ";observed_port=" + sourcePort;
                byte[] ackBytes = ack.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(ackBytes, ackBytes.length, sourceAddress, sourcePort));
                artifact.diagnostic(section, "udp_peer_proof_ack_sent", true);
            } catch (Exception ex) {
                artifact.diagnostic(
                        section,
                        "udp_peer_proof_ack_error",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        artifact.writeQuietly();
        return true;
    }

    private void waitForQcl082UdpPeerProofBeforeTargetIfNeeded(
            DatagramSocket udpSocket,
            Qcl082ReceiveProxyLane lane,
            String section,
            long startedMs) {
        if (!config.isQuestPeerRoute()
                || !config.isQuestGroupOwnerRole()
                || !qcl082ReceiveProxyTransportProtocolUdp()
                || udpSocket == null) {
            artifact.diagnostic(section, "udp_peer_proof_pre_target_wait_enabled", false);
            return;
        }
        long waitStarted = SystemClock.elapsedRealtime();
        long deadline = waitStarted + QCL082_UDP_PEER_PROOF_MS;
        int originalTimeoutMs = 1000;
        int nonProofDatagrams = 0;
        AtomicBoolean tcpWarmupStop = new AtomicBoolean(false);
        Thread tcpWarmupThread = null;
        artifact.diagnostic(section, "tcp_peer_proof_warmup_server_enabled", false);
        artifact.diagnostic(
                section,
                "tcp_peer_proof_warmup_server_disabled_reason",
                "qcl041_app_bound_udp_matrix_passed_but_tcp_warmup_failed");
        try {
            originalTimeoutMs = udpSocket.getSoTimeout();
        } catch (Exception ignored) {
        }
        artifact.diagnostic(section, "udp_peer_proof_pre_target_wait_enabled", true);
        try {
            udpSocket.setSoTimeout(500);
            byte[] buffer = new byte[QCL082_UDP_HEADER_BYTES + QCL082_UDP_PAYLOAD_BYTES];
            while (SystemClock.elapsedRealtime() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    udpSocket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                if (recordQcl082UdpPeerProofProbeIfPresent(udpSocket, packet, lane, section, startedMs)) {
                    artifact.diagnostic(section, "udp_peer_proof_pre_target_received", true);
                    artifact.diagnostic(
                            section,
                            "udp_peer_proof_pre_target_elapsed_ms",
                            SystemClock.elapsedRealtime() - waitStarted);
                    return;
                }
                nonProofDatagrams++;
                artifact.diagnostic(section, "udp_peer_proof_pre_target_non_proof_datagrams", nonProofDatagrams);
            }
        } catch (Exception ex) {
            artifact.diagnostic(
                    section,
                    "udp_peer_proof_pre_target_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            tcpWarmupStop.set(true);
            try {
                udpSocket.setSoTimeout(Math.max(1, originalTimeoutMs));
            } catch (Exception ignored) {
            }
            if (tcpWarmupThread != null) {
                tcpWarmupThread.interrupt();
            }
        }
        artifact.diagnostic(section, "udp_peer_proof_pre_target_received", false);
        artifact.diagnostic(
                section,
                "udp_peer_proof_pre_target_elapsed_ms",
                SystemClock.elapsedRealtime() - waitStarted);
    }

    private Thread startQcl082TcpPeerProofWarmupServer(
            final DatagramSocket udpSocket,
            final Qcl082ReceiveProxyLane lane,
            final String section,
            final long startedMs,
            final AtomicBoolean stopRequested) {
        if (udpSocket == null) {
            artifact.diagnostic(section, "tcp_peer_proof_warmup_server_enabled", false);
            return null;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket server = null;
                Socket peer = null;
                int accepts = 0;
                int errors = 0;
                int listenPort = config.listenPort;
                long waitStarted = SystemClock.elapsedRealtime();
                long deadline = waitStarted + QCL082_UDP_PEER_PROOF_MS;
                try {
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    InetAddress bindAddress = udpSocket.getLocalAddress();
                    if (bindAddress != null && !bindAddress.isAnyLocalAddress()) {
                        server.bind(new InetSocketAddress(bindAddress, listenPort));
                        artifact.diagnostic(
                                section,
                                "tcp_peer_proof_warmup_server_bound",
                                bindAddress.getHostAddress() + ":" + listenPort);
                    } else {
                        server.bind(new InetSocketAddress(listenPort));
                        artifact.diagnostic(section, "tcp_peer_proof_warmup_server_bound", "0.0.0.0:" + listenPort);
                    }
                    server.setSoTimeout(500);
                    artifact.diagnostic(section, "tcp_peer_proof_warmup_server_enabled", true);
                    artifact.diagnostic(section, "tcp_peer_proof_warmup_server_port", listenPort);
                    while (!stopRequested.get() && SystemClock.elapsedRealtime() < deadline) {
                        try {
                            peer = server.accept();
                        } catch (SocketTimeoutException timeout) {
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
                        String payload = reader.readLine();
                        artifact.diagnostic(section, "tcp_peer_proof_warmup_accepts", accepts);
                        artifact.diagnostic(
                                section,
                                "tcp_peer_proof_warmup_accepted_source",
                                peer.getInetAddress() == null ? "" : peer.getInetAddress().getHostAddress());
                        artifact.diagnostic(section, "tcp_peer_proof_warmup_accepted_source_port", peer.getPort());
                        artifact.diagnostic(
                                section,
                                "tcp_peer_proof_warmup_accepted_local",
                                String.valueOf(peer.getLocalSocketAddress()));
                        artifact.diagnostic(section, "tcp_peer_proof_warmup_rx_payload", payload == null ? "" : payload);
                        if (payload != null
                                && payload.startsWith(QCL082_TCP_PEER_HELLO)
                                && payload.contains("run_id=" + config.runId)
                                && payload.contains("epoch=" + config.qcl082TopologyEpoch)
                                && payload.contains("lane=" + lane.label)) {
                            String ack = QCL082_TCP_PEER_ACK
                                    + ";run_id=" + config.runId
                                    + ";epoch=" + config.qcl082TopologyEpoch
                                    + ";lane=" + lane.label
                                    + ";elapsed_ms=" + (SystemClock.elapsedRealtime() - startedMs);
                            writer.write(ack);
                            writer.write("\n");
                            writer.flush();
                            artifact.diagnostic(section, "tcp_peer_proof_warmup_ack_sent", true);
                        }
                        closeSocketForQcl082Recycle(peer);
                        peer = null;
                    }
                } catch (Exception ex) {
                    errors++;
                    artifact.diagnostic(section, "tcp_peer_proof_warmup_server_errors", errors);
                    artifact.diagnostic(
                            section,
                            "tcp_peer_proof_warmup_server_last_error",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } finally {
                    artifact.diagnostic(section, "tcp_peer_proof_warmup_server_accepts", accepts);
                    artifact.diagnostic(
                            section,
                            "tcp_peer_proof_warmup_server_elapsed_ms",
                            SystemClock.elapsedRealtime() - waitStarted);
                    closeSocketForQcl082Recycle(peer);
                    closeQuietly(server);
                    artifact.writeQuietly();
                }
            }
        }, "qcl082-tcp-peer-proof-warmup-" + lane.label);
        thread.start();
        return thread;
    }

    private boolean sendQcl082TcpPeerProofWarmup(
            Network wifiDirectNetwork,
            InetAddress relayLocalAddress,
            InetAddress[] receiverAddresses,
            Qcl082RelayLane lane,
            String section) throws InterruptedException {
        if (!config.isQuestPeerRoute()
                || wifiDirectNetwork == null
                || receiverAddresses == null
                || receiverAddresses.length == 0) {
            artifact.diagnostic(section, "tcp_peer_proof_warmup_enabled", false);
            return false;
        }
        long started = SystemClock.elapsedRealtime();
        long deadline = started + QCL082_TCP_PEER_PROOF_MS;
        int attempts = 0;
        int connectErrors = 0;
        int targetPort = config.listenPort;
        artifact.diagnostic(section, "tcp_peer_proof_warmup_enabled", true);
        artifact.diagnostic(section, "tcp_peer_proof_warmup_target_port", targetPort);
        artifact.diagnostic(section, "tcp_peer_proof_warmup_mode", "network_factory_source_bound");
        while (SystemClock.elapsedRealtime() < deadline) {
            for (InetAddress receiverAddress : receiverAddresses) {
                attempts++;
                Socket socket = null;
                try {
                    socket = wifiDirectNetwork.getSocketFactory().createSocket();
                    artifact.diagnostic(section, "tcp_peer_proof_warmup_network_factory_socket", true);
                    if (relayLocalAddress != null) {
                        socket.bind(new InetSocketAddress(relayLocalAddress, 0));
                        artifact.diagnostic(
                                section,
                                "tcp_peer_proof_warmup_source_bound",
                                socket.getLocalSocketAddress().toString());
                    }
                    socket.connect(new InetSocketAddress(receiverAddress, targetPort), 1000);
                    socket.setSoTimeout(1000);
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8));
                    String payload = QCL082_TCP_PEER_HELLO
                            + ";run_id=" + config.runId
                            + ";epoch=" + config.qcl082TopologyEpoch
                            + ";lane=" + lane.label;
                    writer.write(payload);
                    writer.write("\n");
                    writer.flush();
                    String reply = reader.readLine();
                    artifact.diagnostic(
                            section,
                            "tcp_peer_proof_warmup_target_host",
                            receiverAddress.getHostAddress());
                    artifact.diagnostic(
                            section,
                            "tcp_peer_proof_warmup_local_socket",
                            String.valueOf(socket.getLocalSocketAddress()));
                    artifact.diagnostic(
                            section,
                            "tcp_peer_proof_warmup_remote_socket",
                            String.valueOf(socket.getRemoteSocketAddress()));
                    artifact.diagnostic(section, "tcp_peer_proof_warmup_reply", reply == null ? "" : reply);
                    if (reply != null
                            && reply.startsWith(QCL082_TCP_PEER_ACK)
                            && reply.contains("run_id=" + config.runId)
                            && reply.contains("epoch=" + config.qcl082TopologyEpoch)
                            && reply.contains("lane=" + lane.label)) {
                        artifact.diagnostic(section, "tcp_peer_proof_warmup_ack_received", true);
                        artifact.diagnostic(section, "tcp_peer_proof_warmup_attempts", attempts);
                        artifact.diagnostic(
                                section,
                                "tcp_peer_proof_warmup_elapsed_ms",
                                SystemClock.elapsedRealtime() - started);
                        return true;
                    }
                } catch (Exception ex) {
                    connectErrors++;
                    artifact.diagnostic(section, "tcp_peer_proof_warmup_connect_errors", connectErrors);
                    artifact.diagnostic(
                            section,
                            "tcp_peer_proof_warmup_last_error",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } finally {
                    closeSocketForQcl082Recycle(socket);
                }
            }
            Thread.sleep(250L);
        }
        artifact.diagnostic(section, "tcp_peer_proof_warmup_ack_received", false);
        artifact.diagnostic(section, "tcp_peer_proof_warmup_attempts", attempts);
        artifact.diagnostic(
                section,
                "tcp_peer_proof_warmup_elapsed_ms",
                SystemClock.elapsedRealtime() - started);
        return false;
    }

    private int parsePeerProofSequence(String payload) {
        String marker = ";seq=";
        int start = payload.indexOf(marker);
        if (start < 0) {
            return -1;
        }
        start += marker.length();
        int end = payload.indexOf(';', start);
        String value = end < 0 ? payload.substring(start) : payload.substring(start, end);
        return parsePositiveInt(value, -1);
    }

    private Thread startQcl082RelayWriteStallWatchdog(
            final Socket receiver,
            final Qcl082CopyProgress progress,
            final long startedMs,
            final String section,
            final Qcl082RelayLane lane,
            final boolean multiLane,
            final AtomicBoolean copyActive) {
        final long timeoutMs = Math.max(0L, (long) config.qcl082RelayWriteStallTimeoutMs);
        progress.writeStallTimeoutMs = timeoutMs;
        if (timeoutMs <= 0L) {
            artifact.diagnostic(section, "write_stall_watchdog_enabled", false);
            artifact.writeQuietly();
            return null;
        }
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                artifact.diagnostic(section, "write_stall_watchdog_enabled", true);
                artifact.diagnostic(section, "write_stall_timeout_ms", timeoutMs);
                artifact.writeQuietly();
                long sleepMs = Math.max(100L, Math.min(500L, timeoutMs / 2L));
                while (copyActive.get() && !Thread.currentThread().isInterrupted()) {
                    if (progress.isWriteStalled(startedMs, timeoutMs)) {
                        progress.noteWriteStallClose(startedMs, timeoutMs);
                        artifact.diagnostic(section, "write_stall_socket_closed", true);
                        artifact.diagnostic(section, "write_stall_socket_close_count", progress.writeStallSocketCloses);
                        publishQcl082RelayProgress(
                                section,
                                lane,
                                multiLane,
                                progress,
                                startedMs,
                                "write-stall-timeout");
                        closeQuietly(receiver);
                        copyActive.set(false);
                        return;
                    }
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "qcl082-relay-write-stall-watchdog-" + lane.label);
        watchdog.start();
        return watchdog;
    }

    private Thread startQcl082RelayReceiverProgressWatchdog(
            final Socket receiver,
            final InputStream progressInput,
            final Qcl082CopyProgress progress,
            final long startedMs,
            final String section,
            final Qcl082RelayLane lane,
            final boolean multiLane,
            final AtomicBoolean copyActive) {
        final long timeoutMs = Math.max(0L, (long) config.qcl082RelayReceiverProgressTimeoutMs);
        progress.noteReceiverProgressWatchdogStarted(startedMs, timeoutMs);
        if (timeoutMs <= 0L || config.qcl082AckPacingEnabled || progressInput == null) {
            artifact.diagnostic(section, "receiver_progress_watchdog_enabled", false);
            artifact.diagnostic(section, "receiver_progress_timeout_ms", timeoutMs);
            artifact.writeQuietly();
            return null;
        }
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                artifact.diagnostic(section, "receiver_progress_watchdog_enabled", true);
                artifact.diagnostic(section, "receiver_progress_timeout_ms", timeoutMs);
                artifact.writeQuietly();
                while (copyActive.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        int progressByte = progressInput.read();
                        if (progressByte < 0) {
                            progress.noteReceiverProgressTimeoutClose(
                                    startedMs,
                                    timeoutMs,
                                    "receiver_progress_eof");
                            publishQcl082RelayProgress(
                                    section,
                                    lane,
                                    multiLane,
                                    progress,
                                    startedMs,
                                    "receiver-progress-eof");
                            closeSocketForQcl082Recycle(receiver);
                            copyActive.set(false);
                            return;
                        }
                        progress.noteReceiverProgressReceived(startedMs);
                    } catch (SocketTimeoutException ex) {
                        if (progress.isReceiverProgressStalled(startedMs, timeoutMs)) {
                            progress.noteReceiverProgressTimeoutClose(
                                    startedMs,
                                    timeoutMs,
                                    "receiver_progress_timeout");
                            publishQcl082RelayProgress(
                                    section,
                                    lane,
                                    multiLane,
                                    progress,
                                    startedMs,
                                    "receiver-progress-timeout");
                            closeSocketForQcl082Recycle(receiver);
                            copyActive.set(false);
                            return;
                        }
                    } catch (IOException ex) {
                        if (copyActive.get()) {
                            progress.noteReceiverProgressTimeoutClose(
                                    startedMs,
                                    timeoutMs,
                                    "receiver_progress_read_error");
                            publishQcl082RelayProgress(
                                    section,
                                    lane,
                                    multiLane,
                                    progress,
                                    startedMs,
                                    "receiver-progress-read-error");
                            closeSocketForQcl082Recycle(receiver);
                        }
                        copyActive.set(false);
                        return;
                    }
                }
            }
        }, "qcl082-relay-receiver-progress-watchdog-" + lane.label);
        watchdog.start();
        return watchdog;
    }

    private Socket connectReceiverWithRetry(
            long deadlineMs,
            Network wifiDirectNetwork,
            InetAddress relayLocalAddress,
            InetAddress groupOwnerAddress,
            Qcl082RelayLane lane,
            String section,
            int activeReceiverPort,
            int segmentIndex,
            int laneCount) throws IOException, InterruptedException {
        IOException last = null;
        int attempts = 0;
        InetAddress receiverAddress = InetAddress.getByName(lane.receiverHost);
        artifact.diagnostic(section, "receiver_active_port", activeReceiverPort);
        artifact.diagnostic(section, "receiver_port_segment_index", segmentIndex);
        artifact.diagnostic(section, "receiver_port_rotation_stride", Math.max(1, laneCount));
        artifact.diagnostic(section, "receiver_port_rotation_count", Math.max(1, config.qcl082RelayPortRotationCount));
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            attempts++;
            ReceiverSocketCandidate candidate = createQcl082ReceiverSocket(wifiDirectNetwork, section);
            Socket socket = candidate.socket;
            try {
                if (candidate.createdFromWifiDirectNetwork) {
                    artifact.diagnostic(
                            section,
                            "receiver_socket_local_address_bind_skipped",
                            "socket created from selected Wi-Fi Direct Network");
                } else if (relayLocalAddress != null) {
                    if (!bindSocketToSpecificLocalAddress(socket, relayLocalAddress, groupOwnerAddress, section)) {
                        artifact.diagnostic(
                                section,
                                "receiver_socket_local_address_bind_fallback",
                                "fresh_unbound_socket_after_bind_failure");
                        closeQuietly(socket);
                        socket = new Socket();
                    }
                } else {
                    if (!bindSocketToWifiDirectLocalAddress(socket, groupOwnerAddress)) {
                        artifact.diagnostic(
                                section,
                                "receiver_socket_local_address_bind_fallback",
                                "fresh_unbound_socket_after_bind_failure");
                        closeQuietly(socket);
                        socket = new Socket();
                    }
                }
                socket.connect(
                        new InetSocketAddress(receiverAddress, activeReceiverPort),
                        1000);
                InetAddress connectedLocalAddress = socket.getLocalAddress();
                if (connectedLocalAddress != null) {
                    artifact.diagnostic(
                            section,
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
                artifact.diagnostic(section, "receiver_connect_attempts", attempts);
                artifact.diagnostic(section, "receiver_connected", true);
                return socket;
            } catch (IOException ex) {
                last = ex;
                if (attempts == 1 || attempts % 4 == 0) {
                    artifact.diagnostic(section, "receiver_connect_attempts", attempts);
                    artifact.diagnostic(section, "receiver_last_connect_error", ex.getMessage());
                    artifact.writeQuietly();
                }
                closeQuietly(socket);
                Thread.sleep(250L);
            }
        }
        artifact.diagnostic(section, "receiver_connect_attempts", attempts);
        throw last == null ? new IOException("receiver connect timed out") : last;
    }

    private ReceiverSocketCandidate createQcl082ReceiverSocket(
            Network wifiDirectNetwork,
            String section) throws IOException {
        if (wifiDirectNetwork == null) {
            artifact.diagnostic(section, "receiver_socket_created_from_wifi_direct_network", false);
            artifact.diagnostic(section, "receiver_socket_bound_to_wifi_direct_network", false);
            return new ReceiverSocketCandidate(new Socket(), false);
        }
        try {
            Socket socket = wifiDirectNetwork.getSocketFactory().createSocket();
            artifact.diagnostic(section, "receiver_socket_created_from_wifi_direct_network", true);
            artifact.diagnostic(section, "receiver_socket_bound_to_wifi_direct_network", true);
            return new ReceiverSocketCandidate(socket, true);
        } catch (IOException ex) {
            artifact.diagnostic(section, "receiver_socket_create_from_network_error", ex.getMessage());
            artifact.diagnostic(section, "receiver_socket_created_from_wifi_direct_network", false);
            artifact.diagnostic(section, "receiver_socket_bound_to_wifi_direct_network", false);
            artifact.diagnostic(section, "receiver_socket_network_factory_fallback", "local_address_bind");
            return new ReceiverSocketCandidate(new Socket(), false);
        }
    }

    private Socket connectReverseTcpSourceExportWithRetry(
            long deadlineMs,
            Network wifiDirectNetwork,
            InetAddress localAddress,
            InetAddress groupOwnerAddress,
            String peerHost,
            int peerPort,
            String section) throws IOException, InterruptedException {
        IOException last = null;
        int attempts = 0;
        InetAddress peerAddress = InetAddress.getByName(peerHost);
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            attempts++;
            ReceiverSocketCandidate candidate = createQcl082ReceiverSocket(wifiDirectNetwork, section);
            Socket socket = candidate.socket;
            try {
                if (candidate.createdFromWifiDirectNetwork) {
                    artifact.diagnostic(
                            section,
                            "reverse_tcp_socket_local_address_bind_skipped",
                            "socket created from selected Wi-Fi Direct Network");
                } else if (localAddress != null) {
                    if (!bindSocketToSpecificLocalAddress(socket, localAddress, groupOwnerAddress, section)) {
                        closeQuietly(socket);
                        socket = new Socket();
                    }
                } else if (!bindSocketToWifiDirectLocalAddress(socket, groupOwnerAddress)) {
                    closeQuietly(socket);
                    socket = new Socket();
                }
                socket.connect(new InetSocketAddress(peerAddress, peerPort), 1000);
                InetAddress connectedLocalAddress = socket.getLocalAddress();
                if (connectedLocalAddress != null) {
                    artifact.diagnostic(
                            section,
                            "reverse_tcp_connected_local_address",
                            connectedLocalAddress.getHostAddress());
                }
                artifact.diagnostic(section, "reverse_tcp_connect_attempts", attempts);
                artifact.diagnostic(section, "reverse_tcp_connected", true);
                return socket;
            } catch (IOException ex) {
                last = ex;
                if (attempts == 1 || attempts % 4 == 0) {
                    artifact.diagnostic(section, "reverse_tcp_connect_attempts", attempts);
                    artifact.diagnostic(section, "reverse_tcp_last_connect_error", ex.getMessage());
                    artifact.writeQuietly();
                }
                closeQuietly(socket);
                Thread.sleep(250L);
            }
        }
        artifact.diagnostic(section, "reverse_tcp_connect_attempts", attempts);
        throw last == null ? new IOException("reverse TCP connect timed out") : last;
    }

    private ServerSocket createQcl082ReceiveProxyServerSocket(
            InetAddress proxyLocalAddress,
            Qcl082ReceiveProxyLane lane,
            String section,
            int activeListenPort,
            int segmentIndex,
            int laneCount) throws IOException {
        artifact.diagnostic(section, "active_listen_port", activeListenPort);
        artifact.diagnostic(section, "listen_port_segment_index", segmentIndex);
        artifact.diagnostic(section, "listen_port_rotation_stride", Math.max(1, laneCount));
        artifact.diagnostic(section, "listen_port_rotation_count", Math.max(1, config.qcl082RelayPortRotationCount));
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        if (proxyLocalAddress != null) {
            try {
                server.bind(new InetSocketAddress(proxyLocalAddress, activeListenPort));
                artifact.diagnostic(section, "server_bound_local_address", proxyLocalAddress.getHostAddress());
                artifact.diagnostic(section, "server_bound_port", activeListenPort);
                return server;
            } catch (IOException ex) {
                artifact.diagnostic(section, "server_bind_local_address_error", ex.getMessage());
                closeQuietly(server);
                server = new ServerSocket();
                server.setReuseAddress(true);
            }
        }
        server.bind(new InetSocketAddress(activeListenPort));
        artifact.diagnostic(section, "server_bound_local_address", "0.0.0.0");
        artifact.diagnostic(section, "server_bound_port", activeListenPort);
        return server;
    }

    private DatagramSocket createQcl082DatagramSocket(
            Network wifiDirectNetwork,
            InetAddress localAddress,
            int port,
            String section,
            String label) throws IOException {
        return createQcl082DatagramSocket(
                wifiDirectNetwork,
                localAddress,
                port,
                section,
                label,
                false);
    }

    private DatagramSocket createQcl082DatagramSocket(
            Network wifiDirectNetwork,
            InetAddress localAddress,
            int port,
            String section,
            String label,
            boolean requireNetworkBinding) throws IOException {
        return createQcl082DatagramSocket(
                wifiDirectNetwork,
                localAddress,
                port,
                section,
                label,
                requireNetworkBinding,
                true);
    }

    private DatagramSocket createQcl082DatagramSocket(
            Network wifiDirectNetwork,
            InetAddress localAddress,
            int port,
            String section,
            String label,
            boolean requireNetworkBinding,
            boolean allowNetworkBinding) throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        maybeBindQcl082DatagramSocketToWifiDirectNetwork(
                socket,
                wifiDirectNetwork,
                section,
                label,
                requireNetworkBinding,
                allowNetworkBinding);
        if (localAddress != null) {
            try {
                socket.bind(new InetSocketAddress(localAddress, Math.max(0, port)));
                artifact.diagnostic(section, label + "_bound_local_address", localAddress.getHostAddress());
                artifact.diagnostic(section, label + "_bound_port", socket.getLocalPort());
                return socket;
            } catch (IOException ex) {
                artifact.diagnostic(section, label + "_bind_local_address_error", ex.getMessage());
                closeQuietly(socket);
                if (requireNetworkBinding) {
                    throw ex;
                }
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                maybeBindQcl082DatagramSocketToWifiDirectNetwork(
                        socket,
                        wifiDirectNetwork,
                        section,
                        label,
                        false,
                        allowNetworkBinding);
            }
        }
        if (requireNetworkBinding && Math.max(0, port) == 0) {
            artifact.diagnostic(section, label + "_auto_bind_on_first_send", true);
            artifact.diagnostic(section, label + "_bound_local_address", "auto");
            artifact.diagnostic(section, label + "_bound_port", 0);
            return socket;
        }
        artifact.diagnostic(section, label + "_auto_bind_on_first_send", false);
        socket.bind(new InetSocketAddress(Math.max(0, port)));
        artifact.diagnostic(section, label + "_bound_local_address", "0.0.0.0");
        artifact.diagnostic(section, label + "_bound_port", socket.getLocalPort());
        return socket;
    }

    private void maybeBindQcl082DatagramSocketToWifiDirectNetwork(
            DatagramSocket socket,
            Network wifiDirectNetwork,
            String section,
            String label,
            boolean requireNetworkBinding,
            boolean allowNetworkBinding) throws IOException {
        artifact.diagnostic(section, label + "_wifi_direct_network_binding_allowed", allowNetworkBinding);
        if (allowNetworkBinding) {
            bindQcl082DatagramSocketToWifiDirectNetwork(
                    socket,
                    wifiDirectNetwork,
                    section,
                    label,
                    requireNetworkBinding);
            return;
        }
        artifact.diagnostic(
                section,
                label + "_wifi_direct_network_binding_required",
                requireNetworkBinding);
        artifact.diagnostic(section, label + "_wifi_direct_network_found", wifiDirectNetwork != null);
        artifact.diagnostic(section, label + "_bound_to_wifi_direct_network", false);
        artifact.diagnostic(
                section,
                label + "_wifi_direct_network_binding_skipped",
                "socket_role_uses_local_p2p_bind");
    }

    private void bindQcl082DatagramSocketToWifiDirectNetwork(
            DatagramSocket socket,
            Network wifiDirectNetwork,
            String section,
            String label,
            boolean requireNetworkBinding) throws IOException {
        artifact.diagnostic(
                section,
                label + "_wifi_direct_network_binding_required",
                requireNetworkBinding);
        artifact.diagnostic(section, label + "_wifi_direct_network_found", wifiDirectNetwork != null);
        if (wifiDirectNetwork == null) {
            artifact.diagnostic(section, label + "_bound_to_wifi_direct_network", false);
            if (requireNetworkBinding) {
                throw new IOException("Wi-Fi Direct Network required for QCL-082 UDP datagram socket");
            }
            return;
        }
        artifact.diagnostic(section, label + "_wifi_direct_network", wifiDirectNetwork.toString());
        artifact.diagnostic(section, label + "_wifi_direct_network_handle", wifiDirectNetwork.getNetworkHandle());
        try {
            wifiDirectNetwork.bindSocket(socket);
            artifact.diagnostic(section, label + "_bound_to_wifi_direct_network", true);
        } catch (IOException ex) {
            artifact.diagnostic(section, label + "_bound_to_wifi_direct_network", false);
            artifact.diagnostic(section, label + "_bind_wifi_direct_network_error", ex.getMessage());
            if (requireNetworkBinding) {
                throw new IOException("required Wi-Fi Direct datagram socket binding failed: " + ex.getMessage(), ex);
            }
        }
    }

    private Socket acceptReceiveProxyPeerWithRetry(
            ServerSocket server,
            long deadlineMs,
            InetAddress groupOwnerAddress,
            InetAddress proxyLocalAddress,
            String section) throws IOException, InterruptedException {
        int attempts = 0;
        server.setSoTimeout(1000);
        publishQcl082ReceiveProxyListening(section, attempts);
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            attempts++;
            try {
                Socket socket = server.accept();
                InetAddress peerAddress = socket.getInetAddress();
                artifact.diagnostic(section, "accept_attempts", attempts);
                artifact.diagnostic(section, "peer_connected", true);
                if (peerAddress != null) {
                    artifact.diagnostic(section, "accepted_peer_address", peerAddress.getHostAddress());
                    artifact.diagnostic(
                            section,
                            "accepted_peer_same_subnet_as_group_owner",
                            sameIpv4Slash24(peerAddress, groupOwnerAddress));
                    artifact.diagnostic(
                            section,
                            "accepted_peer_same_subnet_as_proxy",
                            sameIpv4Slash24(peerAddress, proxyLocalAddress));
                }
                return socket;
            } catch (SocketTimeoutException ex) {
                publishQcl082ReceiveProxyListening(section, attempts);
            }
        }
        artifact.diagnostic(section, "accept_attempts", attempts);
        throw new SocketTimeoutException("receive proxy peer accept timed out");
    }

    private void publishQcl082ReceiveProxyListening(String section, int attempts) {
        artifact.diagnostic(section, "status", "listening");
        artifact.diagnostic(section, "accept_attempts", attempts);
        artifact.writeQuietly();
    }

    private static final class Qcl082UdpPeerProof {
        final String host;
        final int port;

        Qcl082UdpPeerProof(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static final class Qcl082DeferredReceiverTarget {
        final String host;
        final int port;
        final String source;

        Qcl082DeferredReceiverTarget(String host, int port, String source) {
            this.host = host;
            this.port = port;
            this.source = source;
        }
    }

    private List<File> qcl082DeferredReceiverTargetFiles() {
        String configured = config.qcl082RelayReceiverTargetFile == null
                ? ""
                : config.qcl082RelayReceiverTargetFile.trim();
        List<File> candidates = new ArrayList<>();
        if (configured.isEmpty()) {
            return candidates;
        }
        File file = new File(configured);
        if (file.isAbsolute()) {
            candidates.add(file);
            return candidates;
        }
        candidates.add(new File(context.getFilesDir(), configured));
        File externalRoot = context.getExternalFilesDir(null);
        if (externalRoot != null) {
            candidates.add(new File(externalRoot, configured));
        }
        return candidates;
    }

    private Qcl082RelayLane[] resolveQcl082GroupOwnerDeferredTargetLanes(
            Qcl082RelayLane[] lanes,
            InetAddress relayLocalAddress) {
        List<File> targetFiles = qcl082DeferredReceiverTargetFiles();
        boolean enabled = config.isQuestPeerRoute()
                && config.isQuestGroupOwnerRole()
                && qcl082RelayTransportProtocolUdp()
                && !targetFiles.isEmpty();
        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_enabled", enabled);
        if (!enabled) {
            return null;
        }
        StringBuilder targetFileList = new StringBuilder();
        for (File candidate : targetFiles) {
            if (targetFileList.length() > 0) {
                targetFileList.append(';');
            }
            targetFileList.append(candidate.getAbsolutePath());
        }
        artifact.diagnostic(
                "qcl082_relay",
                "deferred_receiver_target_files",
                targetFileList.toString());
        long started = SystemClock.elapsedRealtime();
        long waitMs = Math.max(0L, (long) config.qcl082RelayReceiverTargetWaitMs);
        long deadline = started + waitMs;
        int attempts = 0;
        Exception lastError = null;
        while (SystemClock.elapsedRealtime() <= deadline || attempts == 0) {
            attempts++;
            for (File targetFile : targetFiles) {
                try {
                    Qcl082RelayLane[] rewritten = readQcl082DeferredReceiverTargetLanes(
                            targetFile,
                            lanes,
                            relayLocalAddress);
                    if (rewritten != null) {
                        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_found", true);
                        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_file", targetFile.getAbsolutePath());
                        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_attempts", attempts);
                        artifact.diagnostic(
                                "qcl082_relay",
                                "deferred_receiver_target_wait_elapsed_ms",
                                SystemClock.elapsedRealtime() - started);
                        return rewritten;
                    }
                } catch (Exception ex) {
                    lastError = ex;
                    artifact.diagnostic(
                            "qcl082_relay",
                            "deferred_receiver_target_last_error",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                artifact.diagnostic("qcl082_relay", "deferred_receiver_target_interrupted", true);
                break;
            }
        }
        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_found", false);
        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_attempts", attempts);
        artifact.diagnostic(
                "qcl082_relay",
                "deferred_receiver_target_wait_elapsed_ms",
                SystemClock.elapsedRealtime() - started);
        if (lastError != null) {
            artifact.diagnostic(
                    "qcl082_relay",
                    "deferred_receiver_target_error",
                    lastError.getClass().getSimpleName() + ": " + lastError.getMessage());
        }
        if (!config.qcl082RelayRequireDeferredReceiverTarget) {
            return null;
        }
        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_required", true);
        Qcl082RelayLane[] blocked = new Qcl082RelayLane[lanes.length];
        for (int index = 0; index < lanes.length; index++) {
            Qcl082RelayLane lane = lanes[index];
            String section = relaySection(lane, lanes.length > 1);
            artifact.diagnostic(section, "final_target_source", "deferred_receiver_target_missing");
            artifact.diagnostic(section, "final_target_host", QCL082_DEFERRED_TARGET_MISSING_HOST);
            artifact.diagnostic(section, "target_matches_advertised_receive_address", false);
            blocked[index] = new Qcl082RelayLane(
                    lane.label,
                    lane.sourceHost,
                    lane.sourcePort,
                    QCL082_DEFERRED_TARGET_MISSING_HOST,
                    lane.receiverPort);
        }
        return blocked;
    }

    private Qcl082RelayLane[] readQcl082DeferredReceiverTargetLanes(
            File targetFile,
            Qcl082RelayLane[] lanes,
            InetAddress relayLocalAddress) throws Exception {
        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_file", targetFile.getPath());
        if (!targetFile.exists()) {
            return null;
        }
        JSONObject json = new JSONObject(readTextFile(targetFile));
        String runId = json.optString("run_id", "");
        String epoch = json.optString("topology_epoch", "");
        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_run_id", runId);
        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_epoch", epoch);
        if (!config.runId.equals(runId)) {
            artifact.diagnostic("qcl082_relay", "deferred_receiver_target_rejected", "run_id_mismatch");
            return null;
        }
        if (!config.qcl082TopologyEpoch.equals(epoch)) {
            artifact.diagnostic("qcl082_relay", "deferred_receiver_target_rejected", "topology_epoch_mismatch");
            return null;
        }
        JSONObject laneTargets = json.optJSONObject("lanes");
        Qcl082RelayLane[] rewritten = new Qcl082RelayLane[lanes.length];
        boolean multiLane = lanes.length > 1;
        for (int index = 0; index < lanes.length; index++) {
            Qcl082RelayLane lane = lanes[index];
            Qcl082DeferredReceiverTarget target = parseQcl082DeferredReceiverTarget(
                    json,
                    laneTargets,
                    lane);
            String section = relaySection(lane, multiLane);
            if (!validateQcl082DeferredReceiverTarget(target, relayLocalAddress, section)) {
                artifact.diagnostic("qcl082_relay", "deferred_receiver_target_rejected", "invalid_target");
                return null;
            }
            artifact.diagnostic(section, "configured_receiver_host", config.qcl082RelayReceiverHost);
            artifact.diagnostic(section, "configured_receiver_port", config.qcl082RelayReceiverPort);
            artifact.diagnostic(section, "advertised_receive_address", target.host);
            artifact.diagnostic(section, "advertised_receive_port", target.port);
            artifact.diagnostic(section, "final_target_host", target.host);
            artifact.diagnostic(section, "final_target_port", target.port);
            artifact.diagnostic(section, "final_target_source", target.source);
            artifact.diagnostic(section, "target_matches_advertised_receive_address", true);
            rewritten[index] = new Qcl082RelayLane(
                    lane.label,
                    lane.sourceHost,
                    lane.sourcePort,
                    target.host,
                    target.port);
        }
        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_override", true);
        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_source", "host_receiver_artifact_deferred_target");
        artifact.diagnostic("qcl082_relay", "deferred_receiver_target_required", config.qcl082RelayRequireDeferredReceiverTarget);
        return rewritten;
    }

    private Qcl082DeferredReceiverTarget parseQcl082DeferredReceiverTarget(
            JSONObject root,
            JSONObject laneTargets,
            Qcl082RelayLane lane) {
        JSONObject laneTarget = laneTargets == null ? null : laneTargets.optJSONObject(lane.label);
        String host = laneTarget == null
                ? root.optString("advertised_receive_address", "")
                : laneTarget.optString("host", root.optString("advertised_receive_address", ""));
        int port = laneTarget == null
                ? root.optInt("advertised_receive_port", lane.receiverPort)
                : laneTarget.optInt("port", root.optInt("advertised_receive_port", lane.receiverPort));
        String source = root.optString("source", "host_receiver_artifact_deferred_target");
        return new Qcl082DeferredReceiverTarget(host, port, source);
    }

    private boolean validateQcl082DeferredReceiverTarget(
            Qcl082DeferredReceiverTarget target,
            InetAddress relayLocalAddress,
            String section) {
        if (target == null || target.host == null || target.host.trim().isEmpty()) {
            artifact.diagnostic(section, "deferred_receiver_target_rejected", "missing_host");
            return false;
        }
        if (target.port <= 0 || target.port > 65535) {
            artifact.diagnostic(section, "deferred_receiver_target_rejected", "invalid_port");
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(target.host.trim());
            if (address.isAnyLocalAddress()) {
                artifact.diagnostic(section, "deferred_receiver_target_rejected", "any_local_address");
                return false;
            }
            if (address.isLoopbackAddress()) {
                artifact.diagnostic(section, "deferred_receiver_target_rejected", "loopback_address");
                return false;
            }
            if (isLocalAddress(address)) {
                artifact.diagnostic(section, "deferred_receiver_target_rejected", "sender_local_address");
                return false;
            }
            if (relayLocalAddress != null && !sameIpv4Slash24(address, relayLocalAddress)) {
                artifact.diagnostic(section, "deferred_receiver_target_rejected", "outside_sender_p2p_subnet");
                return false;
            }
            artifact.diagnostic(section, "deferred_receiver_target_validated", true);
            return true;
        } catch (Exception ex) {
            artifact.diagnostic(
                    section,
                    "deferred_receiver_target_rejected",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    private Qcl082UdpPeerProof waitForQcl082UdpPeerProof(
            Qcl082RelayLane lane,
            InetAddress relayLocalAddress,
            boolean multiLane) {
        if (!config.isQuestPeerRoute()
                || !config.isQuestGroupOwnerRole()
                || !qcl082RelayTransportProtocolUdp()
                || relayLocalAddress == null) {
            return null;
        }
        String section = relaySection(lane, multiLane);
        long started = SystemClock.elapsedRealtime();
        long deadline = started + QCL082_UDP_PEER_PROOF_MS;
        DatagramSocket socket = null;
        int attempts = 0;
        try {
            socket = createQcl082DatagramSocket(
                    null,
                    relayLocalAddress,
                    lane.receiverPort,
                    section,
                    "udp_peer_proof_listener");
            socket.setSoTimeout(500);
            artifact.diagnostic(section, "udp_peer_proof_listener_enabled", true);
            artifact.diagnostic(section, "udp_peer_proof_listen_port", lane.receiverPort);
            artifact.diagnostic(section, "udp_peer_proof_listen_local_address", relayLocalAddress.getHostAddress());
            byte[] buffer = new byte[512];
            while (SystemClock.elapsedRealtime() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    attempts++;
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                String payload = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8);
                artifact.diagnostic(section, "udp_peer_proof_rx_count", attempts);
                artifact.diagnostic(section, "udp_peer_proof_last_payload", payload);
                if (!payload.startsWith(QCL082_UDP_PEER_HELLO)
                        || !payload.contains("run_id=" + config.runId)
                        || !payload.contains("lane=" + lane.label)) {
                    artifact.diagnostic(section, "udp_peer_proof_rejected_payload", payload);
                    continue;
                }
                InetAddress observedAddress = packet.getAddress();
                int observedPort = packet.getPort();
                if (observedAddress == null || observedAddress.equals(relayLocalAddress)) {
                    artifact.diagnostic(section, "udp_peer_proof_rejected_source", "missing_or_self");
                    continue;
                }
                if (!sameIpv4Slash24(observedAddress, relayLocalAddress)) {
                    artifact.diagnostic(
                            section,
                            "udp_peer_proof_rejected_source",
                            "outside_p2p_subnet:" + observedAddress.getHostAddress());
                    continue;
                }
                String ack = QCL082_UDP_PEER_ACK
                        + ";run_id=" + config.runId
                        + ";lane=" + lane.label
                        + ";observed_source=" + observedAddress.getHostAddress()
                        + ";observed_port=" + observedPort;
                byte[] ackBytes = ack.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(ackBytes, ackBytes.length, observedAddress, observedPort));
                artifact.diagnostic(section, "udp_peer_proof_source_address", observedAddress.getHostAddress());
                artifact.diagnostic(section, "udp_peer_proof_source_port", observedPort);
                artifact.diagnostic(section, "udp_peer_proof_ack_sent", true);
                artifact.diagnostic(section, "udp_peer_proof_elapsed_ms", SystemClock.elapsedRealtime() - started);
                artifact.diagnostic(section, "final_target_host", observedAddress.getHostAddress());
                artifact.diagnostic(section, "final_target_port", observedPort);
                artifact.diagnostic(section, "final_target_source", "udp_peer_proof_observed_source");
                artifact.diagnostic(section, "target_matches_udp_peer_proof", true);
                return new Qcl082UdpPeerProof(observedAddress.getHostAddress(), observedPort);
            }
        } catch (Exception ex) {
            artifact.diagnostic(section, "udp_peer_proof_error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            closeQuietly(socket);
        }
        artifact.diagnostic(section, "udp_peer_proof_source_address", "");
        artifact.diagnostic(section, "udp_peer_proof_source_port", 0);
        artifact.diagnostic(section, "udp_peer_proof_ack_sent", false);
        artifact.diagnostic(section, "udp_peer_proof_elapsed_ms", SystemClock.elapsedRealtime() - started);
        return null;
    }

    private Socket connectReceiveProxyTargetWithRetry(
            long deadlineMs,
            Qcl082ReceiveProxyLane lane,
            String section) throws IOException, InterruptedException {
        IOException last = null;
        int attempts = 0;
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            attempts++;
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(lane.targetHost, lane.targetPort), 1000);
                InetAddress localAddress = socket.getLocalAddress();
                artifact.diagnostic(section, "target_connect_attempts", attempts);
                artifact.diagnostic(section, "target_connected", true);
                if (localAddress != null) {
                    artifact.diagnostic(section, "target_connected_local_address", localAddress.getHostAddress());
                }
                return socket;
            } catch (IOException ex) {
                last = ex;
                if (attempts == 1 || attempts % 4 == 0) {
                    artifact.diagnostic(section, "source_connect_attempts", attempts);
                    artifact.diagnostic(section, "source_last_connect_error", ex.getMessage());
                    artifact.writeQuietly();
                }
                closeQuietly(socket);
                Thread.sleep(250L);
            }
        }
        artifact.diagnostic(section, "target_connect_attempts", attempts);
        throw last == null ? new IOException("receive proxy target connect timed out") : last;
    }


    private Socket connectSourceWithRetry(
            long deadlineMs,
            Qcl082RelayLane lane,
            String section) throws IOException, InterruptedException {
        IOException last = null;
        int attempts = 0;
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            attempts++;
            Socket socket = new Socket();
            try {
                socket.connect(
                        new InetSocketAddress(lane.sourceHost, lane.sourcePort),
                        1000);
                artifact.diagnostic(section, "source_connect_attempts", attempts);
                artifact.diagnostic(section, "source_connected", true);
                return socket;
            } catch (IOException ex) {
                last = ex;
                closeQuietly(socket);
                Thread.sleep(250L);
            }
        }
        artifact.diagnostic(section, "source_connect_attempts", attempts);
        throw last == null ? new IOException("source connect timed out") : last;
    }

    private Qcl082RelayLane[] qcl082RelayLanes(
            InetAddress groupOwnerAddress,
            InetAddress relayLocalAddress) {
        Qcl082RelayLane[] lanes = Qcl082MediaLanes.relayLanes(config, artifact);
        Qcl082RelayLane[] deferredTargetLanes =
                resolveQcl082GroupOwnerDeferredTargetLanes(lanes, relayLocalAddress);
        if (deferredTargetLanes != null) {
            return deferredTargetLanes;
        }
        Qcl082RelayLane[] proofLanes = resolveQcl082GroupOwnerUdpPeerProofLanes(lanes, relayLocalAddress);
        if (proofLanes != null) {
            return proofLanes;
        }
        String discoveredReceiverHost = resolveQcl082GroupOwnerRelayReceiverHost(
                groupOwnerAddress,
                relayLocalAddress);
        if (discoveredReceiverHost == null || discoveredReceiverHost.trim().isEmpty()) {
            artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_override", false);
            return lanes;
        }
        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_override", true);
        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host", discoveredReceiverHost);
        Qcl082RelayLane[] rewritten = new Qcl082RelayLane[lanes.length];
        for (int index = 0; index < lanes.length; index++) {
            Qcl082RelayLane lane = lanes[index];
            rewritten[index] = new Qcl082RelayLane(
                    lane.label,
                    lane.sourceHost,
                    lane.sourcePort,
                    discoveredReceiverHost,
                    lane.receiverPort);
        }
        return rewritten;
    }

    private Qcl082RelayLane[] resolveQcl082GroupOwnerUdpPeerProofLanes(
            Qcl082RelayLane[] lanes,
            InetAddress relayLocalAddress) {
        artifact.diagnostic(
                "qcl082_relay",
                "legacy_reverse_udp_peer_proof_skipped",
                "group_client_to_group_owner_udp_unproven");
        return null;
    }

    private Qcl082ReceiveProxyLane[] qcl082ReceiveProxyLanes() {
        return Qcl082MediaLanes.receiveProxyLanes(config, artifact);
    }

    private String resolveQcl082GroupOwnerRelayReceiverHost(
            InetAddress groupOwnerAddress,
            InetAddress relayLocalAddress) {
        if (!config.isQuestPeerRoute() || !config.isQuestGroupOwnerRole()) {
            return null;
        }
        if (relayLocalAddress == null) {
            artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_skipped", "missing_local_address");
            return null;
        }
        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_configured", config.qcl082RelayReceiverHost);
        String announcedAddress = waitForQcl082PeerAddressAnnouncement(relayLocalAddress);
        if (announcedAddress != null) {
            artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_found", true);
            artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_source", "qcl082_peer_address_announcement");
            return announcedAddress;
        }
        long started = SystemClock.elapsedRealtime();
        String candidate = null;
        int attempts = 0;
        for (attempts = 1; attempts <= 20; attempts++) {
            candidate = findQcl082P2pPeerAddressFromArp(groupOwnerAddress, relayLocalAddress);
            if (candidate != null) {
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_interrupted", true);
                break;
            }
        }
        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_wait_attempts", Math.min(attempts, 20));
        artifact.diagnostic(
                "qcl082_relay",
                "dynamic_receiver_host_wait_elapsed_ms",
                SystemClock.elapsedRealtime() - started);
        if (candidate == null) {
            artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_found", false);
            return null;
        }
        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_found", true);
        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_source", "proc_net_arp_p2p0");
        return candidate;
    }

    private String findQcl082P2pPeerAddressFromArp(
            InetAddress groupOwnerAddress,
            InetAddress relayLocalAddress) {
        InetAddress subnetAnchor = groupOwnerAddress == null ? relayLocalAddress : groupOwnerAddress;
        File arp = new File("/proc/net/arp");
        if (!arp.exists()) {
            artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_arp_missing", true);
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(arp),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("IP address")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 6) {
                    continue;
                }
                String ip = parts[0];
                String device = parts[5];
                if (device == null || !device.toLowerCase(Locale.US).contains("p2p")) {
                    continue;
                }
                try {
                    InetAddress candidate = InetAddress.getByName(ip);
                    if (candidate.equals(relayLocalAddress)
                            || (groupOwnerAddress != null && candidate.equals(groupOwnerAddress))) {
                        continue;
                    }
                    if (!sameIpv4Slash24(candidate, subnetAnchor)
                            && !sameIpv4Slash24(candidate, relayLocalAddress)) {
                        artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_arp_rejected_subnet", ip);
                        continue;
                    }
                    artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_arp_device", device);
                    return ip;
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ex) {
            artifact.diagnostic("qcl082_relay", "dynamic_receiver_host_arp_error", ex.getMessage());
        }
        return null;
    }


    private void recordQcl082RelayResult(
            Qcl082RelayLane lane,
            boolean multiLane,
            String status,
            long bytesCopied,
            String error,
            long elapsedMs,
            Qcl082CopyProgress progress) {
        String section = relaySection(lane, multiLane);
        artifact.diagnostic(section, "status", status);
        artifact.diagnostic(section, "bytes_copied", bytesCopied);
        artifact.diagnostic(section, "elapsed_ms", elapsedMs);
        artifact.diagnostic(section, "copy_completed_reason", progress.completedReason);
        artifact.diagnostic(section, "copy_read_operations", progress.readOperations);
        artifact.diagnostic(section, "copy_write_operations", progress.writeOperations);
        artifact.diagnostic(section, "copy_timeout_polls", progress.timeoutPolls);
        artifact.diagnostic(section, "copy_first_byte_elapsed_ms", progress.firstByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic(section, "copy_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic(section, "copy_max_bytes", progress.maxBytes);
        artifact.diagnostic(section, "ack_pacing_enabled", config.qcl082AckPacingEnabled);
        artifact.diagnostic(section, "ack_chunk_bytes", config.qcl082AckChunkBytes);
        artifact.diagnostic(section, "ack_waits", progress.ackWaits);
        artifact.diagnostic(section, "ack_receives", progress.ackReceives);
        artifact.diagnostic(section, "ack_sends", progress.ackSends);
        artifact.diagnostic(section, "ack_timeouts", progress.ackTimeouts);
        artifact.diagnostic(section, "ack_bytes_pending", progress.bytesSinceAck);
        artifact.diagnostic(section, "ack_last_elapsed_ms", progress.lastAckElapsedMs);
        artifact.diagnostic(section, "ack_last_age_ms", progress.lastAckAgeMs);
        if (error != null) {
            artifact.diagnostic(section, "error", error);
        }
        artifact.diagnostic("qcl082_relay", lane.label + "_status", status);
        artifact.diagnostic("qcl082_relay", lane.label + "_bytes_copied", bytesCopied);
        artifact.diagnostic("qcl082_relay", lane.label + "_elapsed_ms", elapsedMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_copy_completed_reason", progress.completedReason);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_ack_receives", progress.ackReceives);
        artifact.diagnostic("qcl082_relay", lane.label + "_ack_timeouts", progress.ackTimeouts);
        artifact.diagnostic("qcl082_relay", "status", status);
        artifact.diagnostic("qcl082_relay", "bytes_copied", bytesCopied);
        artifact.diagnostic("qcl082_relay", "elapsed_ms", elapsedMs);
        artifact.diagnostic("qcl082_relay", "copy_completed_reason", progress.completedReason);
        artifact.diagnostic("qcl082_relay", "last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic("qcl082_relay", "last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic("qcl082_relay", "last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic("qcl082_relay", "ack_receives", progress.ackReceives);
        artifact.diagnostic("qcl082_relay", "ack_timeouts", progress.ackTimeouts);
    }

    private void recordQcl082ReceiveProxyResult(
            Qcl082ReceiveProxyLane lane,
            boolean multiLane,
            String status,
            long bytesCopied,
            String error,
            long elapsedMs,
            Qcl082CopyProgress progress) {
        String section = receiveProxySection(lane, multiLane);
        artifact.diagnostic(section, "status", status);
        artifact.diagnostic(section, "bytes_copied", bytesCopied);
        artifact.diagnostic(section, "elapsed_ms", elapsedMs);
        artifact.diagnostic(section, "copy_completed_reason", progress.completedReason);
        artifact.diagnostic(section, "copy_read_operations", progress.readOperations);
        artifact.diagnostic(section, "copy_write_operations", progress.writeOperations);
        artifact.diagnostic(section, "copy_timeout_polls", progress.timeoutPolls);
        artifact.diagnostic(section, "copy_first_byte_elapsed_ms", progress.firstByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic(section, "copy_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic(section, "copy_max_bytes", progress.maxBytes);
        artifact.diagnostic(section, "copy_bytes_read_from_peer", progress.bytesReadFromSource);
        artifact.diagnostic(section, "copy_bytes_written_to_target", progress.bytesWrittenToReceiver);
        artifact.diagnostic(section, "ack_pacing_enabled", config.qcl082AckPacingEnabled);
        artifact.diagnostic(section, "ack_chunk_bytes", config.qcl082AckChunkBytes);
        artifact.diagnostic(section, "ack_waits", progress.ackWaits);
        artifact.diagnostic(section, "ack_receives", progress.ackReceives);
        artifact.diagnostic(section, "ack_sends", progress.ackSends);
        artifact.diagnostic(section, "ack_timeouts", progress.ackTimeouts);
        artifact.diagnostic(section, "ack_bytes_pending", progress.bytesSinceAck);
        artifact.diagnostic(section, "ack_last_elapsed_ms", progress.lastAckElapsedMs);
        artifact.diagnostic(section, "ack_last_age_ms", progress.lastAckAgeMs);
        if (error != null) {
            artifact.diagnostic(section, "error", error);
        }
        artifact.diagnostic("qcl082_receive_proxy", lane.label + "_status", status);
        artifact.diagnostic("qcl082_receive_proxy", lane.label + "_bytes_copied", bytesCopied);
        artifact.diagnostic("qcl082_receive_proxy", lane.label + "_elapsed_ms", elapsedMs);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_copy_completed_reason",
                progress.completedReason);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_last_byte_elapsed_ms",
                progress.lastByteElapsedMs);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_last_byte_unix_ms",
                progress.lastByteUnixMs);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_last_byte_age_ms",
                progress.lastByteAgeMs);
        artifact.diagnostic("qcl082_receive_proxy", lane.label + "_ack_sends", progress.ackSends);
        artifact.diagnostic("qcl082_receive_proxy", "status", status);
        artifact.diagnostic("qcl082_receive_proxy", "bytes_copied", bytesCopied);
        artifact.diagnostic("qcl082_receive_proxy", "elapsed_ms", elapsedMs);
        artifact.diagnostic("qcl082_receive_proxy", "copy_completed_reason", progress.completedReason);
        artifact.diagnostic("qcl082_receive_proxy", "last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic("qcl082_receive_proxy", "last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic("qcl082_receive_proxy", "last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic("qcl082_receive_proxy", "ack_sends", progress.ackSends);
    }

    private String relaySection(Qcl082RelayLane lane, boolean multiLane) {
        return Qcl082MediaLanes.relaySection(lane, multiLane);
    }

    private String receiveProxySection(Qcl082ReceiveProxyLane lane, boolean multiLane) {
        return Qcl082MediaLanes.receiveProxySection(lane, multiLane);
    }

    private String relayLaneSummary(Qcl082RelayLane[] lanes) {
        return Qcl082MediaLanes.relayLaneSummary(lanes);
    }

    private String receiveProxyLaneSummary(Qcl082ReceiveProxyLane[] lanes) {
        return Qcl082MediaLanes.receiveProxyLaneSummary(lanes);
    }

    private int qcl082RotatedPort(int basePort, int segmentIndex, int laneCount) {
        int rotationCount = Math.max(1, config.qcl082RelayPortRotationCount);
        int stride = Math.max(1, laneCount);
        int normalizedSegment = Math.max(0, segmentIndex) % rotationCount;
        int port = basePort + (normalizedSegment * stride);
        return port > 0 && port <= 65535 ? port : basePort;
    }


    private long copyMediaBytes(
            InputStream input,
            OutputStream output,
            InputStream ackInput,
            int maxBytes,
            long deadlineMs,
            long startedMs,
            Qcl082RelayLane lane,
            boolean multiLane,
            Qcl082CopyProgress progress) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        long nextProgressWriteMs = 0L;
        progress.maxBytes = maxBytes;
        total = progress.totalBytes;
        while (total < maxBytes && SystemClock.elapsedRealtime() < deadlineMs) {
            int limit = qcl082CopyReadLimit(buffer.length, maxBytes - total, progress);
            int read;
            try {
                read = input.read(buffer, 0, limit);
            } catch (SocketTimeoutException timeout) {
                progress.timeoutPolls++;
                progress.updateIdle(startedMs);
                long now = SystemClock.elapsedRealtime();
                if (now >= nextProgressWriteMs) {
                    publishQcl082RelayProgress(
                            relaySection(lane, multiLane),
                            lane,
                            multiLane,
                            progress,
                            startedMs,
                            "streaming");
                    nextProgressWriteMs = now + 1000L;
                }
                continue;
            }
            if (read < 0) {
                progress.completedReason = "source_eof";
                break;
            }
            if (read == 0) {
                continue;
            }
            progress.noteRead(read, startedMs);
            try {
                progress.noteWriteStarted(read, startedMs);
                output.write(buffer, 0, read);
                output.flush();
                total += read;
                progress.noteWrite(total, read, startedMs);
                progress.bytesSinceAck += read;
                if (!waitForQcl082AckIfNeeded(ackInput, progress, startedMs)) {
                    publishQcl082RelayProgress(
                            relaySection(lane, multiLane),
                            lane,
                            multiLane,
                            progress,
                            startedMs,
                            "ack-timeout");
                    return total;
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextProgressWriteMs) {
                    publishQcl082RelayProgress(
                            relaySection(lane, multiLane),
                            lane,
                            multiLane,
                            progress,
                            startedMs,
                            "streaming");
                    nextProgressWriteMs = now + 1000L;
                }
            } catch (IOException ex) {
                progress.noteWriteFailed(startedMs);
                if (!isQcl082RelayWatchdogRecycleReason(progress.completedReason)) {
                    progress.completedReason = total > 0L ? "receiver_write_error_after_bytes" : "receiver_write_error";
                }
                publishQcl082RelayProgress(
                        relaySection(lane, multiLane),
                        lane,
                        multiLane,
                        progress,
                        startedMs,
                        "write-error");
                if (total > 0L || isQcl082RelayWatchdogRecycleReason(progress.completedReason)) {
                    return total;
                }
                throw ex;
            }
        }
        if ("running".equals(progress.completedReason)) {
            progress.completedReason = total >= maxBytes ? "max_bytes" : "deadline_elapsed";
        }
        progress.updateIdle(startedMs);
        publishQcl082RelayProgress(
                relaySection(lane, multiLane),
                lane,
                multiLane,
                progress,
                startedMs,
                "completed");
        return total;
    }

    private boolean shouldRecycleQcl082RelaySegment(String reason) {
        return "receiver_ack_timeout".equals(reason)
                || "receiver_ack_eof".equals(reason)
                || "receiver_write_error_after_bytes".equals(reason)
                || "receiver_write_stall_timeout".equals(reason)
                || "receiver_progress_timeout".equals(reason)
                || "receiver_progress_eof".equals(reason)
                || "receiver_progress_read_error".equals(reason)
                || "source_eof".equals(reason);
    }

    private boolean isQcl082RelayWatchdogRecycleReason(String reason) {
        return "receiver_write_stall_timeout".equals(reason)
                || "receiver_progress_timeout".equals(reason)
                || "receiver_progress_eof".equals(reason)
                || "receiver_progress_read_error".equals(reason);
    }

    private String qcl082RelayTransportProtocol() {
        if (qcl082RelayTransportProtocolControlTcp()) {
            return Qcl082ControlTcpMediaCarrier.TRANSPORT_PROTOCOL;
        }
        if (qcl082RelayTransportProtocolUdp()) {
            return "udp";
        }
        return qcl082RelayTransportProtocolReverseTcp() ? "reverse-tcp" : "tcp";
    }

    private boolean qcl082RelayTransportProtocolUdp() {
        return "udp".equals(config.qcl082RelayTransportProtocol);
    }

    private boolean qcl082RelayTransportProtocolReverseTcp() {
        return "reverse-tcp".equals(config.qcl082RelayTransportProtocol);
    }

    private String qcl082ReceiveProxyTransportProtocol() {
        if (qcl082ReceiveProxyTransportProtocolControlTcp()) {
            return Qcl082ControlTcpMediaCarrier.TRANSPORT_PROTOCOL;
        }
        if (qcl082ReceiveProxyTransportProtocolUdp()) {
            return "udp";
        }
        return qcl082ReceiveProxyTransportProtocolReverseTcp() ? "reverse-tcp" : "tcp";
    }

    private boolean qcl082ReceiveProxyTransportProtocolUdp() {
        return "udp".equals(config.qcl082ReceiveProxyTransportProtocol);
    }

    private boolean qcl082ReceiveProxyTransportProtocolReverseTcp() {
        return "reverse-tcp".equals(config.qcl082ReceiveProxyTransportProtocol);
    }

    private boolean qcl082ControlTcpMediaCarrierEnabled() {
        return qcl082RelayTransportProtocolControlTcp()
                || qcl082ReceiveProxyTransportProtocolControlTcp();
    }

    private boolean qcl082RelayTransportProtocolControlTcp() {
        return Qcl082ControlTcpMediaCarrier.TRANSPORT_PROTOCOL.equals(config.qcl082RelayTransportProtocol);
    }

    private boolean qcl082ReceiveProxyTransportProtocolControlTcp() {
        return Qcl082ControlTcpMediaCarrier.TRANSPORT_PROTOCOL.equals(config.qcl082ReceiveProxyTransportProtocol);
    }

    private static void writeQcl082UdpHeader(byte[] buffer, int sequence, int payloadLength) {
        writeQcl082UdpInt(buffer, 0, QCL082_UDP_MAGIC);
        writeQcl082UdpInt(buffer, 4, sequence);
        writeQcl082UdpInt(buffer, 8, payloadLength);
    }

    private static void writeQcl082UdpInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) ((value >>> 24) & 0xff);
        buffer[offset + 1] = (byte) ((value >>> 16) & 0xff);
        buffer[offset + 2] = (byte) ((value >>> 8) & 0xff);
        buffer[offset + 3] = (byte) (value & 0xff);
    }

    private static int readQcl082UdpInt(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xff) << 24)
                | ((buffer[offset + 1] & 0xff) << 16)
                | ((buffer[offset + 2] & 0xff) << 8)
                | (buffer[offset + 3] & 0xff);
    }

    private int qcl082CopyReadLimit(int bufferLength, long remainingBytes, Qcl082CopyProgress progress) {
        long limit = Math.min((long) bufferLength, Math.max(1L, remainingBytes));
        if (config.qcl082AckPacingEnabled) {
            int chunkBytes = Math.max(1, config.qcl082AckChunkBytes);
            long ackRemaining = chunkBytes - progress.bytesSinceAck;
            if (ackRemaining <= 0L) {
                ackRemaining = chunkBytes;
            }
            limit = Math.min(limit, ackRemaining);
        }
        return (int) Math.max(1L, limit);
    }

    private boolean waitForQcl082AckIfNeeded(
            InputStream ackInput,
            Qcl082CopyProgress progress,
            long startedMs) throws IOException {
        if (!config.qcl082AckPacingEnabled || ackInput == null) {
            return true;
        }
        int chunkBytes = Math.max(1, config.qcl082AckChunkBytes);
        if (progress.bytesSinceAck < chunkBytes) {
            return true;
        }
        progress.noteAckWait();
        try {
            int ack = ackInput.read();
            if (ack < 0) {
                progress.completedReason = "receiver_ack_eof";
                return false;
            }
            progress.noteAckReceived(startedMs);
            return true;
        } catch (SocketTimeoutException ex) {
            progress.noteAckTimeout();
            if (progress.consecutiveAckTimeouts <= Math.max(0, (long) config.qcl082AckSoftTimeoutLimit)) {
                progress.ackSoftTimeoutContinues++;
                progress.bytesSinceAck = 0L;
                return true;
            }
            progress.completedReason = "receiver_ack_timeout";
            return false;
        }
    }

    private long copyReceiveProxyBytes(
            InputStream input,
            OutputStream output,
            OutputStream ackOutput,
            int maxBytes,
            long deadlineMs,
            long startedMs,
            Qcl082ReceiveProxyLane lane,
            boolean multiLane,
            Qcl082CopyProgress progress) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        long nextProgressWriteMs = 0L;
        progress.maxBytes = maxBytes;
        total = progress.totalBytes;
        while (total < maxBytes && SystemClock.elapsedRealtime() < deadlineMs) {
            int limit = qcl082CopyReadLimit(buffer.length, maxBytes - total, progress);
            int read;
            try {
                read = input.read(buffer, 0, limit);
            } catch (SocketTimeoutException timeout) {
                progress.timeoutPolls++;
                progress.updateIdle(startedMs);
                long peerIdleTimeoutMs = Math.max(0L, (long) config.qcl082ReceiveProxyPeerIdleTimeoutMs);
                if (peerIdleTimeoutMs > 0L
                        && progress.lastByteElapsedMs >= 0L
                        && progress.lastByteAgeMs >= peerIdleTimeoutMs) {
                    progress.completedReason = "peer_idle_timeout";
                    break;
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextProgressWriteMs) {
                    publishQcl082ReceiveProxyProgress(
                            receiveProxySection(lane, multiLane),
                            lane,
                            multiLane,
                            progress,
                            startedMs,
                            "streaming");
                    nextProgressWriteMs = now + 1000L;
                }
                continue;
            } catch (IOException ex) {
                progress.updateIdle(startedMs);
                progress.completedReason = total > 0L ? "peer_read_error_after_bytes" : "peer_read_error";
                publishQcl082ReceiveProxyProgress(
                        receiveProxySection(lane, multiLane),
                        lane,
                        multiLane,
                        progress,
                        startedMs,
                        "read-error");
                if (total > 0L) {
                    return total;
                }
                throw ex;
            }
            if (read < 0) {
                progress.completedReason = "peer_eof";
                break;
            }
            if (read == 0) {
                continue;
            }
            progress.noteRead(read, startedMs);
            try {
                output.write(buffer, 0, read);
                output.flush();
                total += read;
                progress.noteWrite(total, read, startedMs);
                progress.bytesSinceAck += read;
                if (config.qcl082AckPacingEnabled) {
                    sendQcl082AckIfNeeded(ackOutput, progress, startedMs);
                } else {
                    sendQcl082ReceiverProgressIfNeeded(ackOutput, progress, startedMs);
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextProgressWriteMs) {
                    publishQcl082ReceiveProxyProgress(
                            receiveProxySection(lane, multiLane),
                            lane,
                            multiLane,
                            progress,
                            startedMs,
                            "streaming");
                    nextProgressWriteMs = now + 1000L;
                }
            } catch (IOException ex) {
                progress.completedReason = total > 0L ? "target_write_error_after_bytes" : "target_write_error";
                publishQcl082ReceiveProxyProgress(
                        receiveProxySection(lane, multiLane),
                        lane,
                        multiLane,
                        progress,
                        startedMs,
                        "write-error");
                if (total > 0L) {
                    return total;
                }
                throw ex;
            }
        }
        if ("running".equals(progress.completedReason)) {
            progress.completedReason = total >= maxBytes ? "max_bytes" : "deadline_elapsed";
        }
        progress.updateIdle(startedMs);
        publishQcl082ReceiveProxyProgress(
                receiveProxySection(lane, multiLane),
                lane,
                multiLane,
                progress,
                startedMs,
                "completed");
        return total;
    }

    private boolean shouldRecycleQcl082ReceiveProxySegment(String reason) {
        return "peer_eof".equals(reason)
                || "peer_idle_timeout".equals(reason)
                || "peer_read_error_after_bytes".equals(reason)
                || "target_write_error_after_bytes".equals(reason);
    }

    private void sendQcl082AckIfNeeded(
            OutputStream ackOutput,
            Qcl082CopyProgress progress,
            long startedMs) throws IOException {
        if (!config.qcl082AckPacingEnabled || ackOutput == null) {
            return;
        }
        int chunkBytes = Math.max(1, config.qcl082AckChunkBytes);
        if (progress.bytesSinceAck < chunkBytes) {
            return;
        }
        ackOutput.write(0x41);
        ackOutput.flush();
        progress.noteAckSent(startedMs);
    }

    private void sendQcl082ReceiverProgressIfNeeded(
            OutputStream progressOutput,
            Qcl082CopyProgress progress,
            long startedMs) throws IOException {
        if (progressOutput == null || config.qcl082RelayReceiverProgressTimeoutMs <= 0) {
            return;
        }
        progressOutput.write(0x52);
        progressOutput.flush();
        progress.noteReceiverProgressSent(startedMs);
    }

    private void publishQcl082RelayProgress(
            String section,
            Qcl082RelayLane lane,
            boolean multiLane,
            Qcl082CopyProgress progress,
            long startedMs,
            String status) {
        progress.updateIdle(startedMs);
        artifact.diagnostic(section, "status", status);
        artifact.diagnostic(section, "bytes_copied", progress.totalBytes);
        artifact.diagnostic(section, "copy_current_elapsed_ms", progress.currentElapsedMs);
        artifact.diagnostic(section, "copy_first_byte_elapsed_ms", progress.firstByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic(section, "copy_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic(section, "copy_bytes_read_from_source", progress.bytesReadFromSource);
        artifact.diagnostic(section, "copy_bytes_written_to_receiver", progress.bytesWrittenToReceiver);
        artifact.diagnostic(section, "copy_read_operations", progress.readOperations);
        artifact.diagnostic(section, "copy_write_operations", progress.writeOperations);
        artifact.diagnostic(section, "copy_last_read_size", progress.lastReadSize);
        artifact.diagnostic(section, "copy_last_write_size", progress.lastWriteSize);
        artifact.diagnostic(section, "copy_last_read_elapsed_ms", progress.lastReadElapsedMs);
        artifact.diagnostic(section, "copy_last_write_elapsed_ms", progress.lastWriteElapsedMs);
        artifact.diagnostic(section, "copy_last_read_age_ms", progress.lastReadAgeMs);
        artifact.diagnostic(section, "copy_last_write_age_ms", progress.lastWriteAgeMs);
        artifact.diagnostic(section, "copy_write_in_flight", progress.writeInFlight);
        artifact.diagnostic(section, "copy_write_start_elapsed_ms", progress.writeStartElapsedMs);
        artifact.diagnostic(section, "copy_write_in_flight_age_ms", progress.writeInFlightAgeMs);
        artifact.diagnostic(section, "copy_write_in_flight_size", progress.writeInFlightSize);
        artifact.diagnostic(section, "write_stall_timeout_ms", progress.writeStallTimeoutMs);
        artifact.diagnostic(section, "write_stall_socket_closes", progress.writeStallSocketCloses);
        artifact.diagnostic(section, "ack_pacing_enabled", config.qcl082AckPacingEnabled);
        artifact.diagnostic(section, "ack_chunk_bytes", config.qcl082AckChunkBytes);
        artifact.diagnostic(section, "ack_waits", progress.ackWaits);
        artifact.diagnostic(section, "ack_receives", progress.ackReceives);
        artifact.diagnostic(section, "ack_sends", progress.ackSends);
        artifact.diagnostic(section, "ack_timeouts", progress.ackTimeouts);
        artifact.diagnostic(section, "ack_consecutive_timeouts", progress.consecutiveAckTimeouts);
        artifact.diagnostic(section, "ack_soft_timeout_continues", progress.ackSoftTimeoutContinues);
        artifact.diagnostic(section, "ack_soft_timeout_limit", Math.max(0, config.qcl082AckSoftTimeoutLimit));
        artifact.diagnostic(section, "ack_bytes_pending", progress.bytesSinceAck);
        artifact.diagnostic(section, "ack_last_elapsed_ms", progress.lastAckElapsedMs);
        artifact.diagnostic(section, "ack_last_age_ms", progress.lastAckAgeMs);
        artifact.diagnostic(section, "receiver_progress_timeout_ms", progress.receiverProgressTimeoutMs);
        artifact.diagnostic(section, "receiver_progress_sends", progress.receiverProgressSends);
        artifact.diagnostic(section, "receiver_progress_receives", progress.receiverProgressReceives);
        artifact.diagnostic(section, "receiver_progress_timeouts", progress.receiverProgressTimeouts);
        artifact.diagnostic(section, "receiver_progress_socket_closes", progress.receiverProgressSocketCloses);
        artifact.diagnostic(section, "receiver_progress_last_elapsed_ms", progress.lastReceiverProgressElapsedMs);
        artifact.diagnostic(section, "receiver_progress_last_age_ms", progress.lastReceiverProgressAgeMs);
        artifact.diagnostic(section, "copy_segments", progress.copySegments);
        artifact.diagnostic(section, "source_reconnects", progress.sourceReconnects);
        artifact.diagnostic(section, "receiver_reconnects", progress.receiverReconnects);
        artifact.diagnostic(section, "receive_proxy_accepted_peers", progress.receiveProxyAcceptedPeers);
        artifact.diagnostic(section, "receive_proxy_peer_reconnects", progress.receiveProxyPeerReconnects);
        artifact.diagnostic(section, "copy_timeout_polls", progress.timeoutPolls);
        artifact.diagnostic(section, "copy_completed_reason", progress.completedReason);
        artifact.diagnostic("qcl082_relay", lane.label + "_bytes_copied", progress.totalBytes);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_ack_receives", progress.ackReceives);
        artifact.diagnostic("qcl082_relay", lane.label + "_ack_timeouts", progress.ackTimeouts);
        artifact.diagnostic("qcl082_relay", lane.label + "_source_reconnects", progress.sourceReconnects);
        artifact.diagnostic("qcl082_relay", lane.label + "_write_stall_socket_closes", progress.writeStallSocketCloses);
        artifact.diagnostic("qcl082_relay", lane.label + "_copy_current_elapsed_ms", progress.currentElapsedMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_copy_completed_reason", progress.completedReason);
        if (!multiLane || "left".equals(lane.label)) {
            artifact.diagnostic("qcl082_relay", "bytes_copied", progress.totalBytes);
            artifact.diagnostic("qcl082_relay", "last_byte_elapsed_ms", progress.lastByteElapsedMs);
            artifact.diagnostic("qcl082_relay", "last_byte_unix_ms", progress.lastByteUnixMs);
            artifact.diagnostic("qcl082_relay", "last_byte_age_ms", progress.lastByteAgeMs);
            artifact.diagnostic("qcl082_relay", "ack_receives", progress.ackReceives);
            artifact.diagnostic("qcl082_relay", "ack_timeouts", progress.ackTimeouts);
            artifact.diagnostic("qcl082_relay", "source_reconnects", progress.sourceReconnects);
            artifact.diagnostic("qcl082_relay", "write_stall_socket_closes", progress.writeStallSocketCloses);
            artifact.diagnostic("qcl082_relay", "copy_current_elapsed_ms", progress.currentElapsedMs);
            artifact.diagnostic("qcl082_relay", "copy_completed_reason", progress.completedReason);
        }
        artifact.writeQuietly();
    }

    private void publishQcl082ReceiveProxyProgress(
            String section,
            Qcl082ReceiveProxyLane lane,
            boolean multiLane,
            Qcl082CopyProgress progress,
            long startedMs,
            String status) {
        progress.updateIdle(startedMs);
        artifact.diagnostic(section, "status", status);
        artifact.diagnostic(section, "bytes_copied", progress.totalBytes);
        artifact.diagnostic(section, "copy_current_elapsed_ms", progress.currentElapsedMs);
        artifact.diagnostic(section, "copy_first_byte_elapsed_ms", progress.firstByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic(section, "copy_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic(section, "copy_bytes_read_from_peer", progress.bytesReadFromSource);
        artifact.diagnostic(section, "copy_bytes_written_to_target", progress.bytesWrittenToReceiver);
        artifact.diagnostic(section, "copy_read_operations", progress.readOperations);
        artifact.diagnostic(section, "copy_write_operations", progress.writeOperations);
        artifact.diagnostic(section, "copy_last_read_size", progress.lastReadSize);
        artifact.diagnostic(section, "copy_last_write_size", progress.lastWriteSize);
        artifact.diagnostic(section, "copy_last_read_elapsed_ms", progress.lastReadElapsedMs);
        artifact.diagnostic(section, "copy_last_write_elapsed_ms", progress.lastWriteElapsedMs);
        artifact.diagnostic(section, "copy_last_read_age_ms", progress.lastReadAgeMs);
        artifact.diagnostic(section, "copy_last_write_age_ms", progress.lastWriteAgeMs);
        artifact.diagnostic(section, "ack_pacing_enabled", config.qcl082AckPacingEnabled);
        artifact.diagnostic(section, "ack_chunk_bytes", config.qcl082AckChunkBytes);
        artifact.diagnostic(section, "ack_waits", progress.ackWaits);
        artifact.diagnostic(section, "ack_receives", progress.ackReceives);
        artifact.diagnostic(section, "ack_sends", progress.ackSends);
        artifact.diagnostic(section, "ack_timeouts", progress.ackTimeouts);
        artifact.diagnostic(section, "ack_consecutive_timeouts", progress.consecutiveAckTimeouts);
        artifact.diagnostic(section, "ack_soft_timeout_continues", progress.ackSoftTimeoutContinues);
        artifact.diagnostic(section, "ack_bytes_pending", progress.bytesSinceAck);
        artifact.diagnostic(section, "ack_last_elapsed_ms", progress.lastAckElapsedMs);
        artifact.diagnostic(section, "ack_last_age_ms", progress.lastAckAgeMs);
        artifact.diagnostic(section, "receiver_progress_timeout_ms", progress.receiverProgressTimeoutMs);
        artifact.diagnostic(section, "receiver_progress_sends", progress.receiverProgressSends);
        artifact.diagnostic(section, "receiver_progress_receives", progress.receiverProgressReceives);
        artifact.diagnostic(section, "receiver_progress_timeouts", progress.receiverProgressTimeouts);
        artifact.diagnostic(section, "receiver_progress_socket_closes", progress.receiverProgressSocketCloses);
        artifact.diagnostic(section, "receiver_progress_last_elapsed_ms", progress.lastReceiverProgressElapsedMs);
        artifact.diagnostic(section, "receiver_progress_last_age_ms", progress.lastReceiverProgressAgeMs);
        artifact.diagnostic(section, "copy_segments", progress.copySegments);
        artifact.diagnostic(section, "source_reconnects", progress.sourceReconnects);
        artifact.diagnostic(section, "receiver_reconnects", progress.receiverReconnects);
        artifact.diagnostic(section, "receive_proxy_accepted_peers", progress.receiveProxyAcceptedPeers);
        artifact.diagnostic(section, "receive_proxy_peer_reconnects", progress.receiveProxyPeerReconnects);
        artifact.diagnostic(section, "copy_timeout_polls", progress.timeoutPolls);
        artifact.diagnostic(section, "copy_completed_reason", progress.completedReason);
        artifact.diagnostic("qcl082_receive_proxy", lane.label + "_bytes_copied", progress.totalBytes);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_last_byte_elapsed_ms",
                progress.lastByteElapsedMs);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_last_byte_unix_ms",
                progress.lastByteUnixMs);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_last_byte_age_ms",
                progress.lastByteAgeMs);
        artifact.diagnostic("qcl082_receive_proxy", lane.label + "_ack_sends", progress.ackSends);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_copy_current_elapsed_ms",
                progress.currentElapsedMs);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                lane.label + "_copy_completed_reason",
                progress.completedReason);
        if (!multiLane || "left".equals(lane.label)) {
            artifact.diagnostic("qcl082_receive_proxy", "bytes_copied", progress.totalBytes);
            artifact.diagnostic("qcl082_receive_proxy", "last_byte_elapsed_ms", progress.lastByteElapsedMs);
            artifact.diagnostic("qcl082_receive_proxy", "last_byte_unix_ms", progress.lastByteUnixMs);
            artifact.diagnostic("qcl082_receive_proxy", "last_byte_age_ms", progress.lastByteAgeMs);
            artifact.diagnostic("qcl082_receive_proxy", "ack_sends", progress.ackSends);
            artifact.diagnostic("qcl082_receive_proxy", "copy_current_elapsed_ms", progress.currentElapsedMs);
            artifact.diagnostic("qcl082_receive_proxy", "copy_completed_reason", progress.completedReason);
        }
        artifact.writeQuietly();
    }

    private void runQcl082ControlTcpMediaHold(
            Socket socket,
            BufferedReader reader,
            BufferedWriter writer,
            String role,
            boolean qcl082MediaStarted) {
        if (!qcl082MediaStarted || !qcl082MediaPathEnabled()) {
            runQcl082ControlTcpMediaKeepalive(reader, writer, role, qcl082MediaStarted);
            return;
        }
        if (qcl082ControlTcpMediaCarrierEnabled()) {
            Qcl082RelayLane[] relayLanes = config.qcl082RelayEnabled
                    ? Qcl082MediaLanes.relayLanes(config, artifact)
                    : new Qcl082RelayLane[0];
            Qcl082ReceiveProxyLane[] receiveProxyLanes = config.qcl082ReceiveProxyEnabled
                    ? Qcl082MediaLanes.receiveProxyLanes(config, artifact)
                    : new Qcl082ReceiveProxyLane[0];
            new Qcl082ControlTcpMediaCarrier(artifact, config, socket, role)
                    .run(relayLanes, receiveProxyLanes);
            return;
        }
        if (!qcl082ControlTcpMediaStreamEnabled()) {
            runQcl082ControlTcpMediaKeepalive(reader, writer, role, qcl082MediaStarted);
            return;
        }
        runQcl082ControlTcpMediaStream(socket, reader, writer, role);
    }

    private void runQcl082ControlTcpMediaStream(
            Socket socket,
            BufferedReader reader,
            BufferedWriter writer,
            String role) {
        long started = SystemClock.elapsedRealtime();
        int bytesPerDirection = qcl082ControlTcpMediaStreamBytesPerDirection();
        int chunkBytes = qcl082ControlTcpMediaStreamChunkBytes(bytesPerDirection);
        long holdMs = qcl082ControlTcpMediaStreamHoldMs();
        long deadline = started + holdMs;
        Qcl082ControlTcpMediaStreamStats stats = new Qcl082ControlTcpMediaStreamStats();
        artifact.diagnostic("control_tcp", "media_stream_enabled", true);
        artifact.diagnostic("control_tcp", "media_stream_role", role);
        artifact.diagnostic("control_tcp", "media_stream_bytes_per_direction", bytesPerDirection);
        artifact.diagnostic("control_tcp", "media_stream_chunk_bytes", chunkBytes);
        artifact.diagnostic("control_tcp", "media_stream_hold_ms", holdMs);
        artifact.diagnostic("control_tcp", "media_stream_protocol", QCL082_CONTROL_MEDIA_STREAM);
        artifact.writeQuietly();
        if (socket == null) {
            artifact.diagnostic("control_tcp", "media_stream_completed", false);
            artifact.diagnostic("control_tcp", "media_stream_error", "control socket missing");
            artifact.writeQuietly();
            return;
        }
        try {
            socket.setSoTimeout(Math.max(3, config.socketTimeoutSeconds) * 1000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            if ("client".equals(role)) {
                String request = QCL082_CONTROL_MEDIA_STREAM
                        + ";run_id=" + config.runId
                        + ";epoch=" + config.qcl082TopologyEpoch
                        + ";client_bytes=" + bytesPerDirection
                        + ";owner_bytes=" + bytesPerDirection
                        + ";chunk_bytes=" + chunkBytes;
                writer.write(request);
                writer.write("\n");
                writer.flush();
                artifact.diagnostic("control_tcp", "media_stream_request", request);
                String response = reader.readLine();
                artifact.diagnostic("control_tcp", "media_stream_reply", response == null ? "" : response);
                if (response == null || !response.startsWith(QCL082_CONTROL_MEDIA_STREAM_ACK)) {
                    throw new IOException("control TCP media stream ACK not received");
                }
                stats = runQcl082ControlTcpMediaStreamClient(
                        input,
                        output,
                        bytesPerDirection,
                        bytesPerDirection,
                        chunkBytes,
                        deadline);
                recordQcl082ControlTcpMediaStreamStats(
                        role,
                        stats,
                        bytesPerDirection,
                        bytesPerDirection,
                        started);
            } else {
                String request = reader.readLine();
                artifact.diagnostic("control_tcp", "media_stream_request", request == null ? "" : request);
                if (request == null || !request.startsWith(QCL082_CONTROL_MEDIA_STREAM)) {
                    throw new IOException("control TCP media stream request not received");
                }
                int clientBytes = qcl082BoundedLineInt(
                        request,
                        "client_bytes",
                        bytesPerDirection,
                        QCL082_CONTROL_MEDIA_STREAM_MAX_BYTES_PER_DIRECTION);
                int ownerBytes = qcl082BoundedLineInt(
                        request,
                        "owner_bytes",
                        bytesPerDirection,
                        QCL082_CONTROL_MEDIA_STREAM_MAX_BYTES_PER_DIRECTION);
                int requestedChunkBytes = qcl082BoundedLineInt(
                        request,
                        "chunk_bytes",
                        chunkBytes,
                        QCL082_CONTROL_MEDIA_STREAM_MAX_CHUNK_BYTES);
                chunkBytes = qcl082ControlTcpMediaStreamChunkBytes(
                        Math.max(clientBytes, ownerBytes),
                        requestedChunkBytes);
                String response = QCL082_CONTROL_MEDIA_STREAM_ACK
                        + ";run_id=" + config.runId
                        + ";epoch=" + config.qcl082TopologyEpoch
                        + ";client_bytes=" + clientBytes
                        + ";owner_bytes=" + ownerBytes
                        + ";chunk_bytes=" + chunkBytes;
                writer.write(response);
                writer.write("\n");
                writer.flush();
                artifact.diagnostic("control_tcp", "media_stream_reply", response);
                stats = runQcl082ControlTcpMediaStreamGroupOwner(
                        input,
                        output,
                        clientBytes,
                        ownerBytes,
                        chunkBytes,
                        deadline);
                recordQcl082ControlTcpMediaStreamStats(
                        role,
                        stats,
                        clientBytes,
                        ownerBytes,
                        started);
            }
        } catch (Exception ex) {
            recordQcl082ControlTcpMediaStreamStats(
                    role,
                    stats,
                    bytesPerDirection,
                    bytesPerDirection,
                    started);
            artifact.diagnostic(
                    "control_tcp",
                    "media_stream_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            artifact.diagnostic("control_tcp", "media_stream_completed", false);
            artifact.writeQuietly();
        }
    }

    private Qcl082ControlTcpMediaStreamStats runQcl082ControlTcpMediaStreamClient(
            InputStream input,
            OutputStream output,
            int clientBytes,
            int ownerBytes,
            int chunkBytes,
            long deadlineMs) throws IOException {
        Qcl082ControlTcpMediaStreamStats stats = new Qcl082ControlTcpMediaStreamStats();
        while (SystemClock.elapsedRealtime() <= deadlineMs
                && (stats.clientToOwnerBytes < clientBytes || stats.ownerToClientBytes < ownerBytes)) {
            if (stats.clientToOwnerBytes < clientBytes) {
                int chunk = Math.min(chunkBytes, clientBytes - stats.clientToOwnerBytes);
                writeQcl082ControlTcpPatternedBytes(
                        output,
                        stats.clientToOwnerBytes,
                        chunk,
                        QCL082_CONTROL_MEDIA_STREAM_CLIENT_SALT,
                        stats.clientToOwnerCrc);
                output.flush();
                stats.clientToOwnerBytes += chunk;
                stats.clientToOwnerFrames++;
            }
            if (stats.ownerToClientBytes < ownerBytes) {
                int chunk = Math.min(chunkBytes, ownerBytes - stats.ownerToClientBytes);
                int read = readQcl082ControlTcpMediaBytes(input, chunk, stats.ownerToClientCrc, deadlineMs);
                stats.ownerToClientBytes += read;
                if (read > 0) {
                    stats.ownerToClientFrames++;
                }
                if (read < chunk) {
                    break;
                }
            }
        }
        return stats;
    }

    private Qcl082ControlTcpMediaStreamStats runQcl082ControlTcpMediaStreamGroupOwner(
            InputStream input,
            OutputStream output,
            int clientBytes,
            int ownerBytes,
            int chunkBytes,
            long deadlineMs) throws IOException {
        Qcl082ControlTcpMediaStreamStats stats = new Qcl082ControlTcpMediaStreamStats();
        while (SystemClock.elapsedRealtime() <= deadlineMs
                && (stats.clientToOwnerBytes < clientBytes || stats.ownerToClientBytes < ownerBytes)) {
            if (stats.clientToOwnerBytes < clientBytes) {
                int chunk = Math.min(chunkBytes, clientBytes - stats.clientToOwnerBytes);
                int read = readQcl082ControlTcpMediaBytes(input, chunk, stats.clientToOwnerCrc, deadlineMs);
                stats.clientToOwnerBytes += read;
                if (read > 0) {
                    stats.clientToOwnerFrames++;
                }
                if (read < chunk) {
                    break;
                }
            }
            if (stats.ownerToClientBytes < ownerBytes) {
                int chunk = Math.min(chunkBytes, ownerBytes - stats.ownerToClientBytes);
                writeQcl082ControlTcpPatternedBytes(
                        output,
                        stats.ownerToClientBytes,
                        chunk,
                        QCL082_CONTROL_MEDIA_STREAM_OWNER_SALT,
                        stats.ownerToClientCrc);
                output.flush();
                stats.ownerToClientBytes += chunk;
                stats.ownerToClientFrames++;
            }
        }
        return stats;
    }

    private int readQcl082ControlTcpMediaBytes(
            InputStream input,
            int targetBytes,
            CRC32 crc,
            long deadlineMs) throws IOException {
        byte[] buffer = new byte[Math.max(1, Math.min(targetBytes, QCL082_CONTROL_MEDIA_STREAM_MAX_CHUNK_BYTES))];
        int total = 0;
        while (total < targetBytes && SystemClock.elapsedRealtime() <= deadlineMs) {
            int read;
            try {
                read = input.read(buffer, 0, Math.min(buffer.length, targetBytes - total));
            } catch (SocketTimeoutException timeout) {
                if (SystemClock.elapsedRealtime() >= deadlineMs) {
                    break;
                }
                continue;
            }
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            crc.update(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private void writeQcl082ControlTcpPatternedBytes(
            OutputStream output,
            int offset,
            int length,
            int salt,
            CRC32 crc) throws IOException {
        byte[] buffer = new byte[Math.max(1, Math.min(length, QCL082_CONTROL_MEDIA_STREAM_MAX_CHUNK_BYTES))];
        int written = 0;
        while (written < length) {
            int chunk = Math.min(buffer.length, length - written);
            for (int index = 0; index < chunk; index++) {
                buffer[index] = (byte) ((offset + written + index + salt) & 0xff);
            }
            output.write(buffer, 0, chunk);
            crc.update(buffer, 0, chunk);
            written += chunk;
        }
    }

    private void recordQcl082ControlTcpMediaStreamStats(
            String role,
            Qcl082ControlTcpMediaStreamStats stats,
            int clientToOwnerTargetBytes,
            int ownerToClientTargetBytes,
            long startedMs) {
        long expectedClientCrc = qcl082ControlTcpPatternedCrc(
                stats.clientToOwnerBytes,
                QCL082_CONTROL_MEDIA_STREAM_CLIENT_SALT);
        long expectedOwnerCrc = qcl082ControlTcpPatternedCrc(
                stats.ownerToClientBytes,
                QCL082_CONTROL_MEDIA_STREAM_OWNER_SALT);
        boolean clientCrcMatched = stats.clientToOwnerCrc.getValue() == expectedClientCrc;
        boolean ownerCrcMatched = stats.ownerToClientCrc.getValue() == expectedOwnerCrc;
        boolean completed = stats.clientToOwnerBytes >= clientToOwnerTargetBytes
                && stats.ownerToClientBytes >= ownerToClientTargetBytes
                && clientCrcMatched
                && ownerCrcMatched;
        artifact.diagnostic("control_tcp", "media_stream_client_to_owner_target_bytes", clientToOwnerTargetBytes);
        artifact.diagnostic("control_tcp", "media_stream_owner_to_client_target_bytes", ownerToClientTargetBytes);
        artifact.diagnostic("control_tcp", "media_stream_client_to_owner_bytes", stats.clientToOwnerBytes);
        artifact.diagnostic("control_tcp", "media_stream_client_to_owner_crc32", stats.clientToOwnerCrc.getValue());
        artifact.diagnostic("control_tcp", "media_stream_client_to_owner_expected_crc32", expectedClientCrc);
        artifact.diagnostic("control_tcp", "media_stream_client_to_owner_crc32_match", clientCrcMatched);
        artifact.diagnostic("control_tcp", "media_stream_owner_to_client_bytes", stats.ownerToClientBytes);
        artifact.diagnostic("control_tcp", "media_stream_owner_to_client_crc32", stats.ownerToClientCrc.getValue());
        artifact.diagnostic("control_tcp", "media_stream_owner_to_client_expected_crc32", expectedOwnerCrc);
        artifact.diagnostic("control_tcp", "media_stream_owner_to_client_crc32_match", ownerCrcMatched);
        artifact.diagnostic("control_tcp", "media_stream_client_to_owner_frames", stats.clientToOwnerFrames);
        artifact.diagnostic("control_tcp", "media_stream_owner_to_client_frames", stats.ownerToClientFrames);
        if ("client".equals(role)) {
            artifact.diagnostic("control_tcp", "media_stream_client_to_owner_tx_bytes", stats.clientToOwnerBytes);
            artifact.diagnostic("control_tcp", "media_stream_owner_to_client_rx_bytes", stats.ownerToClientBytes);
        } else {
            artifact.diagnostic("control_tcp", "media_stream_client_to_owner_rx_bytes", stats.clientToOwnerBytes);
            artifact.diagnostic("control_tcp", "media_stream_owner_to_client_tx_bytes", stats.ownerToClientBytes);
        }
        artifact.diagnostic("control_tcp", "media_stream_bidirectional_observed", completed);
        artifact.diagnostic("control_tcp", "media_stream_completed", completed);
        artifact.diagnostic(
                "control_tcp",
                "media_stream_elapsed_ms",
                SystemClock.elapsedRealtime() - startedMs);
        artifact.writeQuietly();
    }

    private long qcl082ControlTcpPatternedCrc(int byteCount, int salt) {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[Math.min(
                QCL082_CONTROL_MEDIA_STREAM_MAX_CHUNK_BYTES,
                Math.max(1, byteCount))];
        int written = 0;
        while (written < byteCount) {
            int chunk = Math.min(buffer.length, byteCount - written);
            for (int index = 0; index < chunk; index++) {
                buffer[index] = (byte) ((written + index + salt) & 0xff);
            }
            crc.update(buffer, 0, chunk);
            written += chunk;
        }
        return crc.getValue();
    }

    private int qcl082BoundedLineInt(String line, String key, int fallback, int maxValue) {
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
                int parsed = Integer.parseInt(part.substring(prefix.length()));
                return Math.max(0, Math.min(parsed, maxValue));
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean qcl082ControlTcpMediaStreamEnabled() {
        return qcl082ControlTcpMediaStreamBytesPerDirection() > 0;
    }

    private int qcl082ControlTcpMediaStreamBytesPerDirection() {
        return Math.max(
                0,
                Math.min(
                        config.qcl082ControlTcpMediaStreamBytesPerDirection,
                        QCL082_CONTROL_MEDIA_STREAM_MAX_BYTES_PER_DIRECTION));
    }

    private int qcl082ControlTcpMediaStreamChunkBytes(int configuredBytes) {
        return qcl082ControlTcpMediaStreamChunkBytes(
                configuredBytes,
                config.qcl082ControlTcpMediaStreamChunkBytes);
    }

    private int qcl082ControlTcpMediaStreamChunkBytes(int targetBytes, int requestedChunkBytes) {
        int cappedBytes = Math.max(1, targetBytes);
        int configuredChunkBytes = Math.max(1, requestedChunkBytes);
        return Math.max(1, Math.min(
                Math.min(configuredChunkBytes, QCL082_CONTROL_MEDIA_STREAM_MAX_CHUNK_BYTES),
                cappedBytes));
    }

    private long qcl082ControlTcpMediaStreamHoldMs() {
        long configuredHoldMs = Math.max(0, config.holdAfterSocketMs);
        if (configuredHoldMs > 0L) {
            return configuredHoldMs;
        }
        return Math.max(3, config.socketTimeoutSeconds) * 1000L;
    }

    private void runQcl082ControlTcpMediaKeepalive(
            BufferedReader reader,
            BufferedWriter writer,
            String role,
            boolean qcl082MediaStarted) {
        if (!qcl082MediaStarted || !qcl082MediaPathEnabled()) {
            artifact.diagnostic("control_tcp", "media_keepalive_enabled", false);
            artifact.diagnostic(
                    "control_tcp",
                    "media_keepalive_skipped",
                    qcl082MediaStarted ? "media_path_disabled" : "media_not_started");
            artifact.writeQuietly();
            return;
        }
        long holdMs = qcl082ControlTcpMediaKeepaliveHoldMs();
        artifact.diagnostic("control_tcp", "media_keepalive_enabled", holdMs > 0L);
        artifact.diagnostic("control_tcp", "media_keepalive_role", role);
        artifact.diagnostic("control_tcp", "media_keepalive_hold_ms", holdMs);
        artifact.diagnostic("control_tcp", "media_keepalive_interval_ms", QCL082_CONTROL_KEEPALIVE_INTERVAL_MS);
        if (holdMs <= 0L) {
            artifact.writeQuietly();
            return;
        }
        long started = SystemClock.elapsedRealtime();
        long deadline = started + holdMs;
        int sent = 0;
        int received = 0;
        int timeouts = 0;
        int errors = 0;
        artifact.diagnostic("control_tcp", "media_keepalive_started", true);
        artifact.writeQuietly();
        while (SystemClock.elapsedRealtime() < deadline) {
            int sequence = "client".equals(role) ? sent : received;
            try {
                if ("client".equals(role)) {
                    writer.write(QCL082_CONTROL_KEEPALIVE
                            + ";run_id=" + config.runId
                            + ";epoch=" + config.qcl082TopologyEpoch
                            + ";seq=" + sequence
                            + "\n");
                    writer.flush();
                    sent++;
                    String response = reader.readLine();
                    if (response != null && response.startsWith(QCL082_CONTROL_KEEPALIVE_ACK)) {
                        received++;
                    } else if (response != null) {
                        artifact.diagnostic("control_tcp", "media_keepalive_rejected_payload", response);
                    }
                    Thread.sleep(QCL082_CONTROL_KEEPALIVE_INTERVAL_MS);
                } else {
                    String request = reader.readLine();
                    if (request != null && request.startsWith(QCL082_CONTROL_KEEPALIVE)) {
                        received++;
                        writer.write(QCL082_CONTROL_KEEPALIVE_ACK
                                + ";run_id=" + config.runId
                                + ";epoch=" + config.qcl082TopologyEpoch
                                + ";seq=" + sequence
                                + "\n");
                        writer.flush();
                        sent++;
                    } else if (request != null) {
                        artifact.diagnostic("control_tcp", "media_keepalive_rejected_payload", request);
                    }
                }
            } catch (SocketTimeoutException timeout) {
                timeouts++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                artifact.diagnostic("control_tcp", "media_keepalive_interrupted", true);
                break;
            } catch (Exception ex) {
                errors++;
                artifact.diagnostic(
                        "control_tcp",
                        "media_keepalive_last_error",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
                break;
            }
            if ((sent + received + timeouts + errors) % 5 == 0) {
                artifact.diagnostic("control_tcp", "media_keepalive_sent", sent);
                artifact.diagnostic("control_tcp", "media_keepalive_received", received);
                artifact.diagnostic("control_tcp", "media_keepalive_timeouts", timeouts);
                artifact.diagnostic("control_tcp", "media_keepalive_errors", errors);
                artifact.writeQuietly();
            }
        }
        artifact.diagnostic("control_tcp", "media_keepalive_sent", sent);
        artifact.diagnostic("control_tcp", "media_keepalive_received", received);
        artifact.diagnostic("control_tcp", "media_keepalive_timeouts", timeouts);
        artifact.diagnostic("control_tcp", "media_keepalive_errors", errors);
        artifact.diagnostic(
                "control_tcp",
                "media_keepalive_elapsed_ms",
                SystemClock.elapsedRealtime() - started);
        artifact.diagnostic("control_tcp", "media_keepalive_completed", true);
        artifact.writeQuietly();
    }

    private static final class Qcl082ControlTcpMediaStreamStats {
        int clientToOwnerBytes = 0;
        int ownerToClientBytes = 0;
        int clientToOwnerFrames = 0;
        int ownerToClientFrames = 0;
        final CRC32 clientToOwnerCrc = new CRC32();
        final CRC32 ownerToClientCrc = new CRC32();
    }

    private long qcl082ControlTcpMediaKeepaliveHoldMs() {
        long configuredHoldMs = Math.max(0, config.holdAfterSocketMs);
        if (configuredHoldMs <= 0L) {
            return 0L;
        }
        return Math.min(configuredHoldMs, 5000L);
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
        applyQcl081LslApiConfig("qcl081_lsl", groupOwnerAddress);
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

    private void runQcl081LslEcho(Network wifiDirectNetwork, InetAddress groupOwnerAddress) {
        artifact.diagnostic("qcl081_lsl_echo", "enabled", true);
        artifact.diagnostic("qcl081_lsl_echo", "backend", config.qcl081LslBackend);
        artifact.diagnostic("qcl081_lsl_echo", "command_stream_name", config.qcl081LslEchoCommandStreamName);
        artifact.diagnostic("qcl081_lsl_echo", "command_stream_type", config.qcl081LslEchoCommandStreamType);
        artifact.diagnostic("qcl081_lsl_echo", "command_source_id", config.qcl081LslEchoCommandSourceId);
        artifact.diagnostic("qcl081_lsl_echo", "echo_stream_name", config.qcl081LslEchoStreamName);
        artifact.diagnostic("qcl081_lsl_echo", "echo_stream_type", config.qcl081LslEchoStreamType);
        artifact.diagnostic("qcl081_lsl_echo", "echo_source_id", config.qcl081LslEchoSourceId);
        artifact.diagnostic("qcl081_lsl_echo", "sample_count", Math.max(1, config.qcl081LslEchoSampleCount));
        if (groupOwnerAddress != null) {
            artifact.diagnostic(
                    "qcl081_lsl_echo",
                    "windows_group_owner_address",
                    groupOwnerAddress.getHostAddress());
        }
        applyQcl081LslApiConfig("qcl081_lsl_echo", groupOwnerAddress);
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean processBound = false;
        if (wifiDirectNetwork != null && connectivityManager != null) {
            try {
                processBound = connectivityManager.bindProcessToNetwork(wifiDirectNetwork);
                artifact.diagnostic("qcl081_lsl_echo", "process_bound_to_wifi_direct_network", processBound);
            } catch (Exception ex) {
                artifact.diagnostic("qcl081_lsl_echo", "process_bind_to_wifi_direct_network_error", ex.getMessage());
            }
        } else {
            artifact.diagnostic("qcl081_lsl_echo", "process_bound_to_wifi_direct_network", false);
        }

        Qcl081LslNativeBridge.LoadState loadState = Qcl081LslNativeBridge.runtimeState();
        artifact.diagnostic("qcl081_lsl_echo", "native_runtime_available", loadState.available);
        artifact.diagnostic("qcl081_lsl_echo", "native_runtime_detail", loadState.detail);
        try {
            JSONObject report = Qcl081LslNativeBridge.echoRoundTrip(
                    config.qcl081LslEchoCommandStreamName,
                    config.qcl081LslEchoCommandStreamType,
                    config.qcl081LslEchoCommandSourceId,
                    config.qcl081LslEchoStreamName,
                    config.qcl081LslEchoStreamType,
                    config.qcl081LslEchoSourceId,
                    Math.max(1, config.qcl081LslEchoSampleCount),
                    Math.max(0, config.qcl081LslEchoWarmupMs),
                    Math.max(0, config.qcl081LslEchoOutletHoldAfterMs),
                    Math.max(1, config.qcl081LslEchoTimeoutSeconds));
            artifact.diagnostic("qcl081_lsl_echo", "roundtrip_report", report);
            artifact.diagnostic("qcl081_lsl_echo", "roundtrip_status", report.optString("status", "blocked"));
            artifact.diagnostic(
                    "qcl081_lsl_echo",
                    "command_samples_received",
                    report.optInt("command_samples_received", 0));
            artifact.diagnostic(
                    "qcl081_lsl_echo",
                    "echo_samples_published",
                    report.optInt("echo_samples_published", 0));
            artifact.diagnostic(
                    "qcl081_lsl_echo",
                    "quest_processing_ms_summary",
                    report.optJSONObject("quest_processing_ms_summary"));
        } catch (Exception ex) {
            artifact.diagnostic("qcl081_lsl_echo", "roundtrip_error", ex.getMessage());
        } finally {
            if (connectivityManager != null && processBound) {
                try {
                    connectivityManager.bindProcessToNetwork(null);
                    artifact.diagnostic("qcl081_lsl_echo", "process_unbound_after_echo", true);
                } catch (Exception ex) {
                    artifact.diagnostic("qcl081_lsl_echo", "process_unbind_error", ex.getMessage());
                }
            }
        }
    }

    private void applyQcl081LslApiConfig(String diagnosticGroup, InetAddress groupOwnerAddress) {
        InetAddress localAddress = findWifiDirectLocalAddress(groupOwnerAddress);
        String localAddressText = localAddress == null ? "" : localAddress.getHostAddress();
        String groupOwnerAddressText = groupOwnerAddress == null ? "" : groupOwnerAddress.getHostAddress();
        String peers = localAddressText;
        if (!groupOwnerAddressText.isEmpty()) {
            peers = peers.isEmpty() ? groupOwnerAddressText : peers + ", " + groupOwnerAddressText;
        }
        String configFileStem = config.runId.replaceAll("[^A-Za-z0-9._-]", "-");
        String sessionId = "default";
        String apiConfig = "[ports]\n"
                + "IPv6 = disable\n"
                + "\n"
                + "[multicast]\n"
                + "ResolveScope = link\n"
                + (localAddressText.isEmpty() ? "" : "ListenAddress = " + localAddressText + "\n")
                + "\n"
                + "[lab]\n"
                + (peers.isEmpty() ? "" : "KnownPeers = {" + peers + "}\n")
                + "SessionID = " + sessionId + "\n"
                + "\n"
                + "[log]\n"
                + "level = 0\n";
        artifact.diagnostic(diagnosticGroup, "lsl_api_config_content", apiConfig);
        artifact.diagnostic(diagnosticGroup, "lsl_api_config_local_address", localAddressText);
        artifact.diagnostic(diagnosticGroup, "lsl_api_config_known_peers", peers);
        artifact.diagnostic(diagnosticGroup, "lsl_api_config_session_id", sessionId);
        boolean pathApplied = false;
        try {
            File configDir = new File(context.getFilesDir(), "qcl081-lsl-api");
            if (!configDir.exists() && !configDir.mkdirs()) {
                throw new IOException("Could not create " + configDir.getAbsolutePath());
            }
            File configFile = new File(configDir, configFileStem + "-lsl_api.cfg");
            try (FileOutputStream output = new FileOutputStream(configFile, false)) {
                output.write(apiConfig.getBytes(StandardCharsets.UTF_8));
            }
            artifact.diagnostic(diagnosticGroup, "lsl_api_config_path", configFile.getAbsolutePath());
            pathApplied = Qcl081LslNativeBridge.setConfigPath(configFile.getAbsolutePath());
            artifact.diagnostic(diagnosticGroup, "lsl_api_config_path_applied", pathApplied);
        } catch (Exception ex) {
            artifact.diagnostic(diagnosticGroup, "lsl_api_config_path_error", ex.getMessage());
        }
        boolean contentApplied = Qcl081LslNativeBridge.setConfigContent(apiConfig);
        artifact.diagnostic(diagnosticGroup, "lsl_api_config_content_api_applied", contentApplied);
        artifact.diagnostic(diagnosticGroup, "lsl_api_config_applied", pathApplied || contentApplied);
    }

    private Socket createSocketForWifiDirectNetwork(Network network) throws IOException {
        return networkBinder.createSocketForWifiDirectNetwork(network);
    }

    private boolean bindSocketToWifiDirectLocalAddress(Socket socket, InetAddress groupOwnerAddress) {
        return networkBinder.bindSocketToWifiDirectLocalAddress(socket, groupOwnerAddress);
    }

    private boolean bindSocketToSpecificLocalAddress(
            Socket socket,
            InetAddress localAddress,
            InetAddress groupOwnerAddress,
            String diagnosticGroup) {
        return networkBinder.bindSocketToSpecificLocalAddress(
                socket,
                localAddress,
                groupOwnerAddress,
                diagnosticGroup);
    }

    private Network findWifiDirectNetwork(InetAddress groupOwnerAddress) {
        return networkBinder.findWifiDirectNetwork(groupOwnerAddress);
    }

    private Network findUsableWifiDirectNetwork(
            InetAddress groupOwnerAddress,
            String section,
            String prefix,
            long timeoutMs) {
        return networkBinder.findUsableWifiDirectNetwork(groupOwnerAddress, section, prefix, timeoutMs);
    }

    private Network findUsableWifiDirectNetwork(
            InetAddress groupOwnerAddress,
            String section,
            String prefix,
            long timeoutMs,
            Network preferredNetwork) {
        return networkBinder.findUsableWifiDirectNetwork(
                groupOwnerAddress,
                section,
                prefix,
                timeoutMs,
                preferredNetwork);
    }

    private void recordConnectivitySnapshot(InetAddress groupOwnerAddress, String section, String prefix) {
        networkBinder.recordConnectivitySnapshot(groupOwnerAddress, section, prefix);
    }

    private InetAddress findWifiDirectLocalAddress(InetAddress groupOwnerAddress) {
        return networkBinder.findWifiDirectLocalAddress(groupOwnerAddress);
    }

    private static boolean sameIpv4Slash24(InetAddress left, InetAddress right) {
        return Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(left, right);
    }

    private static void closeSocketForQcl082Recycle(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.setSoLinger(true, 0);
        } catch (Exception ignored) {
        }
        closeQuietly(socket);
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

    private static String readTextFile(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static boolean isLocalAddress(InetAddress target) {
        if (target == null) {
            return false;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (target.equals(addresses.nextElement())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void stopPeerDiscoveryThen(
            final String diagnosticSection,
            final String diagnosticKey,
            final Runnable next) {
        if (manager == null || channel == null) {
            artifact.diagnostic(diagnosticSection, diagnosticKey + "_skipped", "manager_or_channel_missing");
            next.run();
            return;
        }
        try {
            manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    artifact.diagnostic(diagnosticSection, diagnosticKey, "success");
                    next.run();
                }

                @Override
                public void onFailure(int reason) {
                    artifact.diagnostic(diagnosticSection, diagnosticKey + "_reason", reason);
                    next.run();
                }
            });
        } catch (SecurityException ex) {
            artifact.diagnostic(diagnosticSection, diagnosticKey + "_error", ex.getMessage());
            next.run();
        }
    }

    private void removeGroupWithRetry(
            final String diagnosticSection,
            final String diagnosticKey,
            final int maxAttempts,
            final Runnable onSuccess,
            final Runnable onFailure) {
        removeGroupWithRetryAttempt(
                diagnosticSection,
                diagnosticKey,
                1,
                Math.max(1, maxAttempts),
                onSuccess,
                onFailure);
    }

    private void removeGroupWithRetryAttempt(
            final String diagnosticSection,
            final String diagnosticKey,
            final int attempt,
            final int maxAttempts,
            final Runnable onSuccess,
            final Runnable onFailure) {
        if (manager == null || channel == null) {
            artifact.diagnostic(diagnosticSection, diagnosticKey + "_skipped", "manager_or_channel_missing");
            onFailure.run();
            return;
        }
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    artifact.diagnostic(diagnosticSection, diagnosticKey, "success");
                    artifact.diagnostic(diagnosticSection, diagnosticKey + "_attempts", attempt);
                    onSuccess.run();
                }

                @Override
                public void onFailure(final int reason) {
                    artifact.diagnostic(
                            diagnosticSection,
                            diagnosticKey + "_reason_attempt_" + attempt,
                            reason);
                    if (attempt >= maxAttempts) {
                        artifact.diagnostic(diagnosticSection, diagnosticKey + "_final_reason", reason);
                        onFailure.run();
                        return;
                    }
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            removeGroupWithRetryAttempt(
                                    diagnosticSection,
                                    diagnosticKey,
                                    attempt + 1,
                                    maxAttempts,
                                    onSuccess,
                                    onFailure);
                        }
                    }, Q2Q_REMOVE_GROUP_RETRY_DELAY_MS);
                }
            });
        } catch (SecurityException ex) {
            artifact.diagnostic(diagnosticSection, diagnosticKey + "_error", ex.getMessage());
            onFailure.run();
        }
    }

    private void cancelPendingConnectThen(final Runnable next) {
        if (!connectStarted || socketStarted || manager == null || channel == null) {
            next.run();
            return;
        }
        try {
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    artifact.diagnostic("cleanup", "cancelConnect", "success");
                    handler.postDelayed(next, 500L);
                }

                @Override
                public void onFailure(int reason) {
                    artifact.diagnostic("cleanup", "cancelConnect_reason", reason);
                    handler.postDelayed(next, 500L);
                }
            });
        } catch (SecurityException ex) {
            artifact.diagnostic("cleanup", "cancelConnect_error", ex.getMessage());
            next.run();
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
        final boolean[] stopOk = new boolean[] { true };
        final boolean[] removeOk = new boolean[] { true };
        final boolean[] cleanupFinished = new boolean[] { false };
        Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (cleanupFinished[0]) {
                    return;
                }
                cleanupFinished[0] = true;
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
                cancelPendingConnectThen(new Runnable() {
                    @Override
                    public void run() {
                        removeGroupWithRetry(
                                "cleanup",
                                "cleanup_remove_group",
                                Q2Q_REMOVE_GROUP_MAX_ATTEMPTS,
                                finish,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        removeOk[0] = false;
                                        finish.run();
                                    }
                                });
                    }
                });
            }

            @Override
            public void onFailure(int reasonCode) {
                stopOk[0] = false;
                artifact.diagnostic("cleanup", "stopPeerDiscovery_reason", reasonCode);
                cancelPendingConnectThen(new Runnable() {
                    @Override
                    public void run() {
                        removeGroupWithRetry(
                                "cleanup",
                                "cleanup_remove_group",
                                Q2Q_REMOVE_GROUP_MAX_ATTEMPTS,
                                finish,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        removeOk[0] = false;
                                        finish.run();
                                    }
                                });
                    }
                });
            }
        });
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cleanupFinished[0]) {
                    return;
                }
                cleanupFinished[0] = true;
                artifact.setCleanup(false, false, "Wi-Fi Direct cleanup callback timed out.");
                unregisterReceiver();
            }
        }, 12_000L);
    }

    private void updateStatus(String status) {
        if (listener != null) {
            listener.onStatus(status);
        }
    }
}
