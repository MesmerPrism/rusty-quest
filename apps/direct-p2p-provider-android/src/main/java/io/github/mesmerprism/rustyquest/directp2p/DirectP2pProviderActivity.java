package io.github.mesmerprism.rustyquest.directp2p;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public final class DirectP2pProviderActivity extends Activity {
    private static final String TAG = "RustyDirectP2p";
    private static final String MARKER = "RUSTY_DIRECT_P2P_PROVIDER";
    private String productNetworkName;
    private static final String PRODUCT_PASSPHRASE = "RustyProductP2P";
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private String role;
    private String targetDeviceAddress;
    private String runId;
    private int port;
    private boolean socketStarted;
    private boolean connectStarted;
    private int discoveryAttempts;
    private DirectP2pLifecycle lifecycle;
    private boolean topologyStarted;
    private boolean cleanupStarted;
    private boolean groupReadbackPending;
    private boolean discoveryReadbackPending;
    private boolean removalPending;
    private long cleanupDeadline;
    private AndroidNetworkBindingProvider.Selection completedSelection;
    private JSONObject completedNative;
    private volatile boolean failureRequested;
    private final long startedAt = SystemClock.elapsedRealtime();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setText("Rusty Direct P2P product provider");
        setContentView(status);
        Intent intent = getIntent();
        role = intent.getStringExtra("role");
        targetDeviceAddress = intent.getStringExtra("target_device_address");
        runId = intent.getStringExtra("run_id");
        port = intent.getIntExtra("port", 9079);
        if (role == null) role = "group_owner";
        if (runId == null || runId.isEmpty()) runId = "product-run";
        if (!authorizeTopology(intent)) {
            return;
        }
        try { productNetworkName = DirectP2pLifecycle.networkForGuardToken(intent.getStringExtra("guard_run_token")); }
        catch (IllegalArgumentException error) { fail("guard_run_token_required"); return; }
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            fail("wifi_p2p_manager_unavailable");
            return;
        }
        channel = manager.initialize(this, getMainLooper(), new WifiP2pManager.ChannelListener() {
            @Override public void onChannelDisconnected() { fail("wifi_p2p_channel_disconnected"); }
        });
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(intent.getAction())) {
                    requestConnectionInfo();
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(intent.getAction())
                        && "client".equals(role)) {
                    requestPeers();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        registerReceiver(receiver, filter);
        if (!hasNearbyPermission()) {
            fail("nearby_wifi_devices_permission_missing");
            return;
        }
        if (!"group_owner".equals(role) && !("client".equals(role) && targetDeviceAddress != null && !targetDeviceAddress.isEmpty())) {
            fail("invalid_role_or_missing_target");
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() { if (!topologyStarted && !failureRequested) fail("identity_or_baseline_deadline"); }
        }, 20_000L);
        requestDeviceIdentity();
    }

    private boolean authorizeTopology(Intent intent) {
        if (!intent.getBooleanExtra("require_peer_session_authorization", false)) {
            Log.i(TAG, MARKER + " phase=topology_gate status=not_required run_id=" + runId);
            return true;
        }
        String encoded = intent.getStringExtra("authorization_receipt_base64");
        String localPeerId = intent.getStringExtra("local_peer_id");
        long expectedRevision = intent.getLongExtra("peer_session_authority_revision", -1L);
        if (encoded == null || encoded.isEmpty() || localPeerId == null || localPeerId.isEmpty()
                || expectedRevision < 0L) {
            Log.w(TAG, MARKER + " phase=topology_gate status=blocked reason=missing_authorization_inputs run_id=" + runId);
            return false;
        }
        try {
            String receipt = new String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8);
            String result = RustDirectSocketProvider.validateTopologyAuthorization(
                    receipt, localPeerId, role, expectedRevision, System.currentTimeMillis());
            JSONObject parsed = new JSONObject(result);
            String gateStatus = parsed.optString("status", "blocked");
            String reason = parsed.optString("reason", "invalid_result");
            long actualRevision = parsed.optLong("authority_revision", -1L);
            Log.i(TAG, MARKER + " phase=topology_gate status=" + gateStatus
                    + " reason=" + reason + " local_peer_id=" + localPeerId
                    + " expected_revision=" + expectedRevision + " actual_revision=" + actualRevision
                    + " run_id=" + runId);
            return "accepted".equals(gateStatus);
        } catch (Exception error) {
            Log.w(TAG, MARKER + " phase=topology_gate status=blocked reason=" + safe(error) + " run_id=" + runId);
            return false;
        }
    }

    private boolean hasNearbyPermission() {
        return android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestDeviceIdentity() {
        try {
            manager.requestDeviceInfo(channel, new WifiP2pManager.DeviceInfoListener() {
                @Override public void onDeviceInfoAvailable(android.net.wifi.p2p.WifiP2pDevice device) {
                    if (failureRequested) return;
                    String address = device == null ? "" : device.deviceAddress;
                    if (!address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) { fail("device_identity_unavailable"); return; }
                    try {
                        lifecycle = new DirectP2pLifecycle(startedAt, productNetworkName,
                                "group_owner".equals(role) ? address : targetDeviceAddress, "group_owner".equals(role));
                    } catch (IllegalArgumentException error) { fail("lifecycle_identity_invalid"); return; }
                    Log.i(TAG, MARKER + " phase=device_identity status=pass role=" + role + " device_address=" + address + " run_id=" + runId);
                    checkIdleGroupThenStart();
                }
            });
        } catch (Exception error) {
            fail("device_identity_" + safe(error));
        }
    }

    private void checkIdleGroupThenStart() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (lifecycle != null && lifecycle.active()) {
                    if (lifecycle.expired(SystemClock.elapsedRealtime())) fail("topology_or_run_deadline");
                    else main.postDelayed(this, 100L);
                }
            }
        }, 100L);
        try {
            manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override public void onGroupInfoAvailable(WifiP2pGroup group) {
                    if (failureRequested || cleanupStarted || !lifecycle.active()) return;
                    if (!lifecycle.baseline(group != null, SystemClock.elapsedRealtime())
                            || !lifecycle.beginFormation(SystemClock.elapsedRealtime())) { fail("preexisting_group_or_expired_baseline"); return; }
                    try {
                        manager.requestDiscoveryState(channel, new WifiP2pManager.DiscoveryStateListener() {
                            @Override public void onDiscoveryStateAvailable(int state) {
                                if (failureRequested || cleanupStarted || !lifecycle.active()) return;
                                if (state != WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) { fail("preexisting_discovery"); return; }
                                topologyStarted = true;
                                if ("group_owner".equals(role)) postCreateGroup(); else postDiscover(0L);
                            }
                        });
                    } catch (Exception error) { fail("requestDiscoveryState_exception_" + safe(error)); }
                }
            });
        } catch (Exception error) { fail("requestGroupInfo_exception_" + safe(error)); }
    }

    private void createGroup() {
        if (failureRequested || lifecycle == null || !lifecycle.active()) return;
        try {
        manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override public void onGroupInfoAvailable(WifiP2pGroup group) {
                if (failureRequested || cleanupStarted) return;
                if (group != null) { fail("group_changed_before_create"); return; }
                createGroupAfterFreshBaseline();
            }
        });
        } catch (Exception error) { fail("requestGroupInfo_exception_" + safe(error)); }
    }

    private void createGroupAfterFreshBaseline() {
        if (failureRequested || lifecycle == null || !lifecycle.request(SystemClock.elapsedRealtime())) { fail("create_not_admitted"); return; }
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(productNetworkName)
                .setPassphrase(PRODUCT_PASSPHRASE)
                .enablePersistentMode(false)
                .build();
        config.groupOwnerIntent = WifiP2pConfig.GROUP_OWNER_INTENT_MAX;
        config.wps.setup = WpsInfo.PBC;
        try {
        manager.createGroup(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                lifecycle.requestAcknowledged(true);
                if (cleanupStarted) { checkCleanupReadback(); return; }
                Log.i(TAG, MARKER + " phase=topology_request status=accepted role=group_owner run_id=" + runId);
                pollConnectionInfo();
            }
            @Override public void onFailure(int reason) {
                lifecycle.requestAcknowledged(false);
                fail("create_group_rejected_" + reason);
                if (cleanupStarted) checkCleanupReadback();
            }
        });
        } catch (Exception error) { fail("createGroup_exception_" + safe(error)); }
    }

    private void discoverThenConnect() {
        if (failureRequested || lifecycle == null || !lifecycle.active() || lifecycle.expired(SystemClock.elapsedRealtime())) return;
        discoveryAttempts++;
        lifecycle.discoveryStarted();
        try {
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { postRequestPeers(1500L); }
            @Override public void onFailure(int reason) {
                Log.w(TAG, MARKER + " phase=peer_discovery status=degraded reason=" + reason + " run_id=" + runId);
                postRequestPeers(500L);
            }
        });
        } catch (Exception error) { fail("discoverPeers_exception_" + safe(error)); }
    }

    private void postCreateGroup() {
        main.postDelayed(new Runnable() { @Override public void run() { createGroup(); } }, 500L);
    }

    private void postDiscover(long delayMs) {
        main.postDelayed(new Runnable() { @Override public void run() { discoverThenConnect(); } }, delayMs);
    }

    private void postRequestPeers(long delayMs) {
        main.postDelayed(new Runnable() { @Override public void run() { requestPeers(); } }, delayMs);
    }

    private void requestPeers() {
        if (failureRequested || cleanupStarted || lifecycle == null || !lifecycle.active() || connectStarted || !"client".equals(role) || targetDeviceAddress == null) return;
        try {
        manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
            @Override public void onPeersAvailable(WifiP2pDeviceList list) {
                if (failureRequested || cleanupStarted || !lifecycle.active()) return;
                WifiP2pDevice[] peers = list == null ? new WifiP2pDevice[0]
                        : list.getDeviceList().toArray(new WifiP2pDevice[0]);
                String[] addresses = new String[peers.length];
                for (int index = 0; index < peers.length; index++) {
                    addresses[index] = peers[index] == null ? null : peers[index].deviceAddress;
                }
                int selectedIndex = DirectP2pLifecycle.selectTargetPeer(targetDeviceAddress, addresses);
                WifiP2pDevice selected = selectedIndex < 0 ? null : peers[selectedIndex];
                if (selected != null) {
                    connectToTarget(selected);
                } else if (discoveryAttempts < 12) {
                    postDiscover(1000L);
                } else {
                    fail("peer_not_discovered");
                }
            }
        });
        } catch (Exception error) { fail("requestPeers_exception_" + safe(error)); }
    }

    private void connectToTarget(WifiP2pDevice peer) {
        if (failureRequested || cleanupStarted || connectStarted || lifecycle == null || !lifecycle.active()) return;
        if (peer == null || !targetDeviceAddress.equalsIgnoreCase(peer.deviceAddress)) { fail("foreign_peer_not_admitted"); return; }
        connectStarted = true;
        try {
        manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override public void onGroupInfoAvailable(WifiP2pGroup group) {
                if (failureRequested || cleanupStarted) return;
                if (group != null) { fail("group_changed_before_connect"); return; }
                connectToTargetAfterFreshBaseline(peer);
            }
        });
        } catch (Exception error) { fail("requestGroupInfo_exception_" + safe(error)); }
    }

    private void connectToTargetAfterFreshBaseline(WifiP2pDevice peer) {
        if (failureRequested || cleanupStarted || !lifecycle.request(SystemClock.elapsedRealtime())) return;
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(productNetworkName)
                .setPassphrase(PRODUCT_PASSPHRASE)
                .setDeviceAddress(MacAddress.fromString(peer.deviceAddress))
                .enablePersistentMode(false)
                .build();
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 0;
        try {
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                lifecycle.requestAcknowledged(true);
                if (cleanupStarted) { checkCleanupReadback(); return; }
                Log.i(TAG, MARKER + " phase=topology_request status=accepted role=client target=" + targetDeviceAddress + " run_id=" + runId);
                pollConnectionInfo();
            }
            @Override public void onFailure(int reason) {
                lifecycle.requestAcknowledged(false);
                fail("connect_failed_" + reason);
                if (cleanupStarted) checkCleanupReadback();
            }
        });
        } catch (Exception error) { fail("connect_exception_" + safe(error)); }
    }

    private void pollConnectionInfo() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (socketStarted || cleanupStarted || lifecycle == null || !lifecycle.active()) return;
                requestConnectionInfo();
                if (!socketStarted) main.postDelayed(this, 500L);
            }
        }, 250L);
    }

    private void requestConnectionInfo() {
        if (failureRequested || cleanupStarted || lifecycle == null || !lifecycle.active()) return;
        try {
            manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                @Override public void onConnectionInfoAvailable(WifiP2pInfo info) { handleConnectionInfo(info); }
            });
        } catch (Exception error) {
            fail("connection_info_" + safe(error));
        }
    }

    private synchronized void handleConnectionInfo(WifiP2pInfo info) {
        if (failureRequested || cleanupStarted || lifecycle == null || !lifecycle.active() || socketStarted
                || info == null || !info.groupFormed || info.groupOwnerAddress == null) return;
        boolean owner = info.isGroupOwner;
        if (owner != "group_owner".equals(role)) {
            fail("platform_role_mismatch");
            return;
        }
        final InetAddress ownerAddress = info.groupOwnerAddress;
        try {
        manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override public void onGroupInfoAvailable(WifiP2pGroup group) {
                if (failureRequested || cleanupStarted || socketStarted || group == null || group.getOwner() == null || lifecycle.requestPending()) return;
                if (!lifecycle.exchange(group.getNetworkName(), group.getOwner().deviceAddress,
                        group.isGroupOwner(), SystemClock.elapsedRealtime())) { fail("group_identity_or_request_not_admitted"); return; }
                socketStarted = true;
                Log.i(TAG, MARKER + " phase=topology status=pass authority=android_wifi_direct_topology_provider role=" + role
                        + " group_owner_host=" + ownerAddress.getHostAddress() + " socket_creation_claimed=false run_id=" + runId);
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { runBoundedExchange(ownerAddress); }
                        catch (Exception error) { fail("exchange_thread_" + safe(error)); cleanup(null, null); }
                    }
                }, "rusty-direct-p2p-exchange").start();
            }
        });
        } catch (Exception error) { fail("requestGroupInfo_exception_" + safe(error)); }
    }

    private void runBoundedExchange(InetAddress groupOwnerAddress) {
        AndroidNetworkBindingProvider.Selection selection =
                new AndroidNetworkBindingProvider(this).awaitSelection(groupOwnerAddress, 5_000L);
        if (selection == null) {
            fail("android_network_binding_unavailable");
            cleanup(null, null);
            return;
        }
        Log.i(TAG, MARKER + " phase=network_binding status=pass authority=android_network_binding_provider network_available="
                + selection.networkAvailable + " network_handle="
                + selection.networkHandle + " interface=" + selection.interfaceName + " local_host=" + selection.localHost
                + " route_matches_group_owner=true socket_creation_claimed=false run_id=" + runId);
        String nativeReceipt;
        try {
            if ("group_owner".equals(role)) {
                nativeReceipt = RustDirectSocketProvider.runServer(selection.localHost, port, selection.networkHandle, 60_000L);
            } else {
                try { Thread.sleep(500L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                nativeReceipt = RustDirectSocketProvider.runClient(selection.localHost, groupOwnerAddress.getHostAddress(), port, runId, selection.networkHandle, 20_000L);
            }
            JSONObject nativeJson = new JSONObject(nativeReceipt);
            Log.i(TAG, MARKER + " phase=rust_socket status=" + nativeJson.optString("status", "fail") + " receipt=" + nativeReceipt);
            if (!"pass".equals(nativeJson.optString("status"))) {
                fail("rust_socket_" + nativeJson.optString("error", "failed"));
            }
            cleanup(selection, nativeJson);
        } catch (Exception error) {
            fail("native_exchange_" + safe(error));
            cleanup(selection, null);
        }
    }

    private void cleanup(AndroidNetworkBindingProvider.Selection selection, JSONObject nativeJson) {
        main.post(new Runnable() {
            @Override public void run() {
                completedSelection = selection;
                completedNative = nativeJson;
                if (lifecycle != null) lifecycle.nativeComplete();
                beginOwnedCleanup();
                if (cleanupStarted) checkCleanupReadback();
            }
        });
    }

    private void beginOwnedCleanup() {
        if (lifecycle == null || cleanupStarted || !topologyStarted || !lifecycle.beginCleanup()) return;
        cleanupStarted = true;
        main.removeCallbacksAndMessages(null);
        cleanupDeadline = SystemClock.elapsedRealtime() + 10_000L;
        if (lifecycle.discoveryOwned()) try {
            manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { lifecycle.discoveryAcknowledged(true); checkCleanupReadback(); }
                @Override public void onFailure(int reason) { lifecycle.discoveryAcknowledged(false); checkCleanupReadback(); }
            });
        } catch (Exception error) {
            lifecycle.discoveryAcknowledged(false);
            Log.e(TAG, MARKER + " phase=cleanup status=outcome_unknown reason=stop_discovery_exception run_id=" + runId);
        }
        checkCleanupReadback();
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (lifecycle.cleanupConfirmed()) { completeCleanup(); return; }
                if (SystemClock.elapsedRealtime() >= cleanupDeadline) {
                    Log.e(TAG, MARKER + " phase=cleanup status=outcome_unknown reason=cleanup_unconfirmed run_id=" + runId);
                    return;
                }
                checkCleanupReadback();
                main.postDelayed(this, 100L);
            }
        }, 100L);
    }

    private void checkCleanupReadback() {
        if (!cleanupStarted || SystemClock.elapsedRealtime() >= cleanupDeadline) return;
        if (!discoveryReadbackPending) {
            discoveryReadbackPending = true;
            final long observation = lifecycle.requestDiscoveryReadback();
            try {
                manager.requestDiscoveryState(channel, new WifiP2pManager.DiscoveryStateListener() {
                    @Override public void onDiscoveryStateAvailable(int state) {
                        discoveryReadbackPending = false;
                        lifecycle.discoveryReadback(observation, state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                    }
                });
            } catch (Exception error) { discoveryReadbackPending = false; lifecycle.readbackFailed(); }
        }
        if (!groupReadbackPending) {
            groupReadbackPending = true;
            final long observation = lifecycle.requestGroupReadback();
            try {
                manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                    @Override public void onGroupInfoAvailable(WifiP2pGroup group) {
                        groupReadbackPending = false;
                        if (!lifecycle.groupReadback(observation, group == null)) return;
                        if (group != null && !removalPending) {
                            if (group.getOwner() == null || !lifecycle.mayRemove(group.getNetworkName(),
                                    group.getOwner().deviceAddress, group.isGroupOwner())) return;
                            removalPending = true;
                            try {
                                manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                                    @Override public void onSuccess() { lifecycle.removalAcknowledged(true); checkCleanupReadback(); }
                                    @Override public void onFailure(int reason) { lifecycle.removalAcknowledged(false); checkCleanupReadback(); }
                                });
                            } catch (Exception error) {
                                lifecycle.removalAcknowledged(false);
                                Log.e(TAG, MARKER + " phase=cleanup status=outcome_unknown reason=remove_group_exception run_id=" + runId);
                            }
                        }
                    }
                });
            } catch (Exception error) { groupReadbackPending = false; lifecycle.readbackFailed(); }
        }
    }

    private void completeCleanup() {
        if (!lifecycle.finish()) return;
        main.removeCallbacksAndMessages(null);
        Log.i(TAG, MARKER + " phase=cleanup status=confirmed run_id=" + runId);
        publishFinal(completedSelection, completedNative);
    }

    private void publishFinal(AndroidNetworkBindingProvider.Selection selection, JSONObject nativeJson) {
        try {
            if (lifecycle == null || lifecycle.failure() != null || !lifecycle.ownedGroupObserved()
                    || selection == null || nativeJson == null || !"pass".equals(nativeJson.optString("status"))) {
                Log.e(TAG, MARKER + " phase=complete status=fail run_id=" + runId);
                return;
            }
            JSONObject receipt = new JSONObject();
            receipt.put("schema", "rusty.quest.product_wifi_direct_run.v1");
            receipt.put("run_id", runId);
            receipt.put("product_package", getPackageName());
            receipt.put("role", role);
            JSONObject topology = new JSONObject();
            topology.put("authority", "android_wifi_direct_topology_provider");
            topology.put("group_formed", true);
            topology.put("local_role", role);
            topology.put("group_owner_host", "group_owner".equals(role) ? selection.localHost : nativeJson.getJSONObject("socket").getString("peer_host"));
            topology.put("socket_creation_claimed", false);
            receipt.put("topology", topology);
            JSONObject binding = new JSONObject();
            binding.put("authority", "android_network_binding_provider");
            binding.put("network_available", selection.networkAvailable);
            binding.put("network_handle", selection.networkHandle);
            binding.put("interface_name", selection.interfaceName);
            binding.put("local_host", selection.localHost);
            binding.put("route_matches_group_owner", selection.routeMatchesGroupOwner);
            binding.put("socket_creation_claimed", false);
            receipt.put("network_binding", binding);
            receipt.put("socket", nativeJson.getJSONObject("socket"));
            receipt.put("exchange", nativeJson.getJSONObject("exchange"));
            JSONObject cleanup = new JSONObject();
            cleanup.put("discovery_stopped", lifecycle.discoveryStopped());
            cleanup.put("group_removed", lifecycle.groupRemoved());
            cleanup.put("socket_closed", lifecycle.socketClosed());
            receipt.put("cleanup", cleanup);
            Log.i(TAG, MARKER + " phase=complete status=pass receipt=" + receipt);
        } catch (Exception error) {
            fail("receipt_" + safe(error));
        }
    }

    private void fail(String reason) {
        failureRequested = true;
        Log.e(TAG, MARKER + " phase=failure status=fail reason=" + reason + " role=" + role + " run_id=" + runId);
        main.post(new Runnable() {
            @Override public void run() {
                if (lifecycle != null) lifecycle.fail(reason);
                beginOwnedCleanup();
            }
        });
    }

    private static String safe(Throwable error) {
        String value = error.getMessage();
        return (value == null ? error.getClass().getSimpleName() : value).replace(' ', '_');
    }

    @Override protected void onDestroy() {
        failureRequested = true;
        if (lifecycle != null && lifecycle.active()) lifecycle.fail("activity_destroyed");
        beginOwnedCleanup();
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
