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
    private String observedDeviceAddress;
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
    private boolean guardedAuthorization;
    private boolean bleBarrier;
    private long formedElapsed;
    private String bleRun,bleSession,blePeer,bleExpected,bleRemoteBoot;
    private long bleEpoch;
    private String authorizationReceipt, authorizationPeer;
    private long authorizationRevision;
    private DirectP2pLifecycle.AuthorizationWindow authorizationWindow;
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
        bleBarrier=intent.getBooleanExtra("require_live_ble_observation",false);
        if(bleBarrier&&!intent.getBooleanExtra("require_peer_session_authorization",false)){fail("ble_barrier_requires_owner_authorization");return;}
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
            long echoTimeout = intent.getLongExtra("guarded_echo_timeout_ms", 20_000L);
            if (bleBarrier&&echoTimeout!=5_000L)throw new IllegalArgumentException("live_barrier_echo_requires_5000_ms");
            if (echoTimeout < 1L || echoTimeout > 20_000L) throw new IllegalArgumentException("closed_echo_cap");
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
            if (!"accepted".equals(gateStatus)) return false;
            authorizationWindow = new DirectP2pLifecycle.AuthorizationWindow(System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(), parsed.getLong("expires_at_ms"), echoTimeout);
            if(bleBarrier){
                bleRun=intent.getStringExtra("ble_run_id");bleSession=intent.getStringExtra("guard_run_token");
                blePeer=intent.getStringExtra("ble_local_peer_tag");bleExpected=intent.getStringExtra("ble_expected_peer_tag");
                bleRemoteBoot=intent.getStringExtra("ble_expected_remote_boot_tag");bleEpoch=intent.getLongExtra("ble_coordination_epoch",-1L);
                if(bleRun==null||!bleRun.matches("[A-Za-z0-9_.-]{4,32}")||bleSession==null||!bleSession.matches("[0-9a-f]{32}")
                        ||blePeer==null||!blePeer.matches("[A-Za-z0-9_.-]{4,32}")||bleExpected==null||!bleExpected.matches("[A-Za-z0-9_.-]{4,32}")
                        ||blePeer.equals(bleExpected)||bleRemoteBoot==null||!bleRemoteBoot.matches("[0-9a-f]{16}")||bleEpoch<=0)throw new IllegalArgumentException("closed_ble_barrier_identity");
            }
            // Conservative supported budgets, not a measured formation-success claim.
            if (!authorizationWindow.permits(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                    BleObservationBarrier.launchReserve(bleBarrier,echoTimeout))) return false;
            authorizationReceipt=receipt; authorizationPeer=localPeerId;
            authorizationRevision=expectedRevision; guardedAuthorization=true;
            return true;
        } catch (Exception error) {
            Log.w(TAG, MARKER + " phase=topology_gate status=blocked reason=" + safe(error) + " run_id=" + runId);
            return false;
        }
    }

    private boolean freshAuthorization(long requiredRemaining) {
        if (!guardedAuthorization) return true; // Legacy diagnostic makes no owner-authority claim.
        try {
            String gate=RustDirectSocketProvider.validateTopologyAuthorization(authorizationReceipt,
                    authorizationPeer,role,authorizationRevision,System.currentTimeMillis());
            if ("accepted".equals(new JSONObject(gate).optString("status"))
                    && authorizationWindow.permits(System.currentTimeMillis(),SystemClock.elapsedRealtime(),requiredRemaining)) return true;
        } catch (Exception ignored) { }
        fail("authorization_expired_or_insufficient_remaining");
        return false;
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
                        observedDeviceAddress=address;
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
                    if (!freshAuthorization(10_000L)) return;
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
        if (!freshAuthorization(authorizationWindow == null ? 0L : authorizationWindow.echoTimeout + 10_000L)) return;
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
                if (!freshAuthorization(10_000L)) return;
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
        if (!freshAuthorization(authorizationWindow == null ? 0L : authorizationWindow.echoTimeout + 10_000L)) return;
        if (failureRequested || lifecycle == null || !lifecycle.active() || lifecycle.expired(SystemClock.elapsedRealtime())) return;
        discoveryAttempts++;
        lifecycle.discoveryStarted();
        try {
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { if (freshAuthorization(10_000L)) postRequestPeers(1500L); }
            @Override public void onFailure(int reason) {
                if (!freshAuthorization(10_000L)) return;
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
        if (!freshAuthorization(10_000L)) return;
        if (failureRequested || cleanupStarted || lifecycle == null || !lifecycle.active() || connectStarted || !"client".equals(role) || targetDeviceAddress == null) return;
        try {
        manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
            @Override public void onPeersAvailable(WifiP2pDeviceList list) {
                if (!freshAuthorization(10_000L)) return;
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
        if (!freshAuthorization(authorizationWindow == null ? 0L : authorizationWindow.echoTimeout + 10_000L)) return;
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
                if (!freshAuthorization(10_000L)) return;
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
        if(info==null||!info.groupFormed||info.isGroupOwner!="group_owner".equals(role))FormationObservationState.clear(this);
        if (!freshAuthorization(10_000L)) return;
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
                if (!freshAuthorization(10_000L)) return;
                if (!lifecycle.exchange(group.getNetworkName(), group.getOwner().deviceAddress,
                        group.isGroupOwner(), SystemClock.elapsedRealtime())) { fail("group_identity_or_request_not_admitted"); return; }
                formedElapsed=SystemClock.elapsedRealtime();
                if(guardedAuthorization)FormationObservationState.publish(DirectP2pProviderActivity.this,()->readCurrentFormation());
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

    private java.util.Map<String,String> readCurrentFormation() throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new SecurityException("formation_main_thread");
        final java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<java.util.Map<String,String>> result=new java.util.concurrent.atomic.AtomicReference<>();
        main.post(()->{
            if(failureRequested||cleanupStarted||!guardedAuthorization||lifecycle==null||!lifecycle.active()||authorizationWindow==null||!authorizationWindow.permits(System.currentTimeMillis(),SystemClock.elapsedRealtime(),1)){latch.countDown();return;}
            try{manager.requestGroupInfo(channel,g->{
                try{if(failureRequested||cleanupStarted||!guardedAuthorization||!authorizationWindow.permits(System.currentTimeMillis(),SystemClock.elapsedRealtime(),1)||g==null||g.getOwner()==null||!lifecycle.owned(g.getNetworkName(),g.getOwner().deviceAddress,g.isGroupOwner()))return;
                    String boot=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id")),StandardCharsets.UTF_8).trim();
                    String stat=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/stat")),StandardCharsets.UTF_8);int end=stat.lastIndexOf(')');if(end<0)throw new SecurityException("formation_process");String birth=stat.substring(end+2).trim().split(" +")[19];
                    java.util.Map<String,String> v=new java.util.HashMap<>();v.put("run_id",runId);v.put("run_token",getIntent().getStringExtra("guard_run_token"));v.put("boot_id",boot);v.put("pid",String.valueOf(android.os.Process.myPid()));v.put("pid_start_ticks",birth);v.put("network",g.getNetworkName());v.put("owner_mac",g.getOwner().deviceAddress.toLowerCase(java.util.Locale.US));v.put("local_owner",String.valueOf(g.isGroupOwner()));v.put("observed_elapsed_ms",String.valueOf(SystemClock.elapsedRealtime()));result.set(v);
                }catch(Exception unavailable){}finally{latch.countDown();}
            });}catch(Exception unavailable){latch.countDown();}
        });
        if(!latch.await(2,java.util.concurrent.TimeUnit.SECONDS)||result.get()==null)throw new SecurityException("formation_callback_unavailable");return result.get();
    }

    private static String shortHash(String text) throws Exception {
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();for(int i=0;i<8;i++)s.append(String.format(java.util.Locale.US,"%02x",hash[i]&255));return s.toString();
    }
    private boolean currentOwnedGroup(final InetAddress owner) throws InterruptedException {
        final java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean valid=new java.util.concurrent.atomic.AtomicBoolean(false);
        main.post(new Runnable(){public void run(){
            if(cleanupStarted||failureRequested||!freshAuthorization(authorizationWindow.echoTimeout+10_000L)){latch.countDown();return;}
            try{manager.requestConnectionInfo(channel,new WifiP2pManager.ConnectionInfoListener(){public void onConnectionInfoAvailable(final WifiP2pInfo info){
                try{manager.requestGroupInfo(channel,new WifiP2pManager.GroupInfoListener(){public void onGroupInfoAvailable(WifiP2pGroup group){
                    valid.set(!cleanupStarted&&!failureRequested&&info!=null&&info.groupFormed&&owner.equals(info.groupOwnerAddress)
                        &&info.isGroupOwner=="group_owner".equals(role)&&group!=null&&group.getOwner()!=null
                        &&lifecycle.owned(group.getNetworkName(),group.getOwner().deviceAddress,group.isGroupOwner())
                        &&freshAuthorization(authorizationWindow.echoTimeout+10_000L));latch.countDown();
                }});}catch(RuntimeException denied){latch.countDown();}
            }});}catch(RuntimeException denied){latch.countDown();}
        }});
        long budget=Math.min(2_000L,BleObservationBarrier.remaining(formedElapsed,SystemClock.elapsedRealtime()));
        return budget>0&&latch.await(budget,java.util.concurrent.TimeUnit.MILLISECONDS)&&valid.get();
    }
    private boolean readLiveBle(final AndroidNetworkBindingProvider.Selection selection,final InetAddress owner) {
        java.util.concurrent.FutureTask<Boolean> task=new java.util.concurrent.FutureTask<Boolean>(new java.util.concurrent.Callable<Boolean>(){
            public Boolean call(){return readLiveBleCurrent(selection,owner);}
        });
        long budget=Math.min(2_000L,BleObservationBarrier.remaining(formedElapsed,SystemClock.elapsedRealtime()));
        if(budget<=0)throw new IllegalStateException("live_ble_barrier_deadline");
        Thread worker=new Thread(task,"rusty-live-ble-read");worker.setDaemon(true);worker.start();
        try{return task.get(budget,java.util.concurrent.TimeUnit.MILLISECONDS);}
        catch(Exception unknown){task.cancel(true);throw new IllegalStateException("live_ble_provider_timeout_or_unknown",unknown);}
    }
    private boolean readLiveBleCurrent(AndroidNetworkBindingProvider.Selection selection,InetAddress owner) {
        try {
            android.content.pm.ProviderInfo provider=getPackageManager().resolveContentProvider("io.github.mesmerprism.rustyquest.peer_rendezvous.live-observation",0);
            if(provider==null||!"io.github.mesmerprism.rustyquest.peer_rendezvous".equals(provider.packageName)
                    ||getPackageManager().checkSignatures(getPackageName(),provider.packageName)!=PackageManager.SIGNATURE_MATCH)return false;
            Bundle request=new Bundle();request.putString("run_id",bleRun);request.putString("session_tag",bleSession);
            request.putLong("coordination_epoch",bleEpoch);request.putString("peer_tag",blePeer);request.putString("expected_peer_tag",bleExpected);
            Bundle response=getContentResolver().call(android.net.Uri.parse("content://io.github.mesmerprism.rustyquest.peer_rendezvous.live-observation"),"read-current",null,request);
            if(response==null||response.size()!=1)return false;String raw=response.getString("observation");if(raw==null||raw.length()>4_096)return false;
            JSONObject o=new JSONObject(raw);if(o.length()!=15||!"rusty.quest.live_ble_observation.v1".equals(o.getString("schema")))return false;
            String[] keys={"run_id","session_tag","coordination_epoch","peer_tag","expected_peer_tag","boot_tag","group_tag","observed_role","owner_ipv4","local_ipv4","remote_boot_tag","remote_role","authenticated_elapsed_ms","observed_elapsed_ms"};
            String[] proof=new String[keys.length];for(int i=0;i<keys.length;i++)proof[i]=o.getString(keys[i]);
            String boot=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id")),StandardCharsets.US_ASCII).trim();
            if(!boot.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))return false;
            String expectedOwner="group_owner".equals(role)?observedDeviceAddress:targetDeviceAddress;
            String group=shortHash(bleSession+"|"+productNetworkName+"|"+owner.getHostAddress()+"|"+expectedOwner.toLowerCase(java.util.Locale.US));
            BleObservationBarrier.require(proof,bleRun,bleSession,bleEpoch,blePeer,bleExpected,shortHash(boot),bleRemoteBoot,group,role,owner.getHostAddress(),selection.localHost,SystemClock.elapsedRealtime());return true;
        }catch(Exception unavailable){return false;}
    }
    private boolean awaitLiveBle(AndroidNetworkBindingProvider.Selection selection,InetAddress owner) throws InterruptedException {
        if(!bleBarrier)return true;
        while(BleObservationBarrier.waiting(formedElapsed,SystemClock.elapsedRealtime())&&!failureRequested&&!cleanupStarted) {
            if(!freshAuthorization(authorizationWindow.echoTimeout+10_000L))return false;
            if(readLiveBle(selection,owner)&&currentOwnedGroup(owner)&&readLiveBle(selection,owner)
                    &&BleObservationBarrier.waiting(formedElapsed,SystemClock.elapsedRealtime())
                    &&freshAuthorization(authorizationWindow.echoTimeout+10_000L))return true;
            Thread.sleep(100L);
        }
        return false;
    }

    private void runBoundedExchange(InetAddress groupOwnerAddress) {
        if (!freshAuthorization(authorizationWindow == null ? 0L : authorizationWindow.echoTimeout + 10_000L)) { cleanup(null,null); return; }
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
        if(bleBarrier)try{if(!awaitLiveBle(selection,groupOwnerAddress)){fail("live_ble_barrier_unavailable_or_expired");cleanup(selection,null);return;}}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();fail("live_ble_barrier_interrupted");cleanup(selection,null);return;}
        String nativeReceipt;
        try {
            if (guardedAuthorization) {
                if (!freshAuthorization(authorizationWindow.echoTimeout + 10_000L)) { cleanup(selection,null); return; }
                nativeReceipt=RustDirectSocketProvider.runGuarded(selection.localHost,
                        groupOwnerAddress.getHostAddress(),port,runId,selection.networkHandle,
                        Math.min(authorizationWindow.echoTimeout,authorizationWindow.remaining(
                                System.currentTimeMillis(),SystemClock.elapsedRealtime())-10_000L),
                        authorizationReceipt,authorizationPeer,role,authorizationRevision);
            } else if ("group_owner".equals(role)) {
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
        FormationObservationState.clear(this);
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
        FormationObservationState.clear(this);
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
        FormationObservationState.clear(this);
        failureRequested = true;
        if (lifecycle != null && lifecycle.active()) lifecycle.fail("activity_destroyed");
        beginOwnedCleanup();
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
