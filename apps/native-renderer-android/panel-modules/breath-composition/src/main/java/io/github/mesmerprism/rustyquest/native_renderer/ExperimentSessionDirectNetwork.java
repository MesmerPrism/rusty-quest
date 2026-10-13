package io.github.mesmerprism.rustyquest.native_renderer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

/** Owns only an explicitly requested temporary Android network, never command authority. */
public final class ExperimentSessionDirectNetwork implements AutoCloseable {
    public static final String FEATURE = "rusty.quest.feature.experiment_direct_network";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation hotspot;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private boolean closed, stopping, groupOwned, pendingStart;
    private long generation;
    private volatile String mode = "none", state = "inactive", detail = "", address = "";
    private String ssid = "", passphrase = "";
    private String ownedNetworkName = "";
    public static boolean featurePackaged(Context context) {
        try {
            android.os.Bundle m = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA).metaData;
            return m != null && m.getBoolean(FEATURE, false);
        } catch (Exception error) { return false; }
    }
    public ExperimentSessionDirectNetwork(Context context) {
        this.context = context.getApplicationContext();
        if (!featurePackaged(context)) throw new IllegalStateException("direct_network_not_packaged");
    }
    public static String requiredPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }
    /** Must be called by a local attended button, never by remote command ingress. */
    public static void requestPermissions(Activity activity, int requestCode) {
        if (!featurePackaged(activity)) throw new IllegalStateException("direct_network_not_packaged");
        activity.requestPermissions(new String[]{requiredPermission()}, requestCode);
    }
    public void start(String selectedMode) {
        if (!"hotspot".equals(selectedMode) && !"wifi_direct".equals(selectedMode))
            throw new IllegalArgumentException("closed_direct_mode_required");
        main.post(new Runnable() { @Override public void run() { startOnMain(selectedMode); } });
    }
    private void startOnMain(String selectedMode) {
        if (closed || stopping || pendingStart || groupOwned || hotspot != null || channel != null
                || !"inactive".equals(state) && !"blocked".equals(state)) return;
        if (context.checkSelfPermission(requiredPermission()) != PackageManager.PERMISSION_GRANTED) {
            state = "blocked"; detail = "attended_permission_required"; return;
        }
        mode = selectedMode; state = "starting"; detail = ""; final long g = ++generation;
        main.postDelayed(new Runnable() { @Override public void run() { if (g == generation && "starting".equals(state)) { detail = "platform_start_timeout"; stopOnMain(); } } }, 20000L);
        try {
            if ("hotspot".equals(mode)) startHotspot(g); else startP2p(g);
        } catch (SecurityException error) { pendingStart = false; releaseChannel(); blocked("permission_or_platform_policy_denied"); }
        catch (RuntimeException error) { pendingStart = false; releaseChannel(); blocked("platform_unavailable"); }
    }
    private void startHotspot(final long g) {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || Build.VERSION.SDK_INT < 26) { blocked("hotspot_unavailable"); return; }
        pendingStart = true;
        wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
            @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                if (g != generation) { reservation.close(); return; }
                pendingStart = false;
                if (closed || stopping) { reservation.close(); finishStop(); return; }
                hotspot = reservation;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.net.wifi.SoftApConfiguration config = reservation.getSoftApConfiguration();
                    ssid = config.getSsid(); passphrase = config.getPassphrase();
                } else {
                    android.net.wifi.WifiConfiguration config = reservation.getWifiConfiguration();
                    ssid = config.SSID; passphrase = config.preSharedKey;
                }
                state = "active"; detail = "platform_reservation_active_join_phone_wifi_settings";
                // Android supplies no authoritative hotspot gateway address here. Do not invent one.
                address = "";
            }
            @Override public void onFailed(int reason) {
                if (g != generation) return;
                pendingStart = false;
                if (stopping) finishStop(); else blocked("hotspot_error_" + reason);
            }
            @Override public void onStopped() {
                if (g != generation) return;
                hotspot = null; finishStop();
            }
        }, main);
    }
    private void startP2p(final long g) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            blocked("wifi_direct_feature_unavailable"); return;
        }
        p2p = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) { blocked("wifi_direct_manager_unavailable"); return; }
        channel = p2p.initialize(context, main.getLooper(), new WifiP2pManager.ChannelListener() {
            @Override public void onChannelDisconnected() { if (g == generation) blocked("wifi_direct_channel_disconnected_cleanup_unknown"); }
        });
        pendingStart = true;
        // Do not remove or adopt another component's existing group.
        p2p.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() { @Override public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup existing) {
            if (g != generation) return;
            if (stopping || closed) { finishStop(); return; }
            if (existing != null) { pendingStart = false; blocked("existing_group_owned_elsewhere"); releaseChannel(); return; }
            p2p.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    if (g != generation) return;
                    pendingStart = false;
                    groupOwned = true;
                    if (stopping || closed) { removeOwnedGroup(); return; }
                    readGroup(g, 0);
                }
                @Override public void onFailure(int reason) {
                    if (g != generation) return;
                    pendingStart = false;
                    if (stopping) finishStop(); else { blocked("wifi_direct_error_" + reason); releaseChannel(); }
                }
            });
        } });
    }
    private void readGroup(long g, int attempt) {
        if (g != generation || stopping || closed || channel == null) return;
        p2p.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() { @Override public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup group) {
            if (g != generation || stopping || closed) return;
            if (group == null || !group.isGroupOwner()) {
                if (attempt < 20) scheduleRead(g, attempt + 1, 500L);
                else { detail = "group_readback_unavailable"; stopOnMain(); }
                return;
            }
            if (!ownedNetworkName.isEmpty() && !ownedNetworkName.equals(group.getNetworkName())) {
                ssid = ""; passphrase = ""; address = "";
                state = "cleanup_unknown"; detail = "owned_group_identity_changed"; return;
            }
            ssid = group.getNetworkName(); passphrase = group.getPassphrase();
            ownedNetworkName = ssid;
            p2p.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() { @Override public void onConnectionInfoAvailable(android.net.wifi.p2p.WifiP2pInfo info) {
                if (g != generation || stopping || closed) return;
                if (info != null && info.groupFormed && info.isGroupOwner && info.groupOwnerAddress != null) {
                    address = info.groupOwnerAddress.isAnyLocalAddress() || info.groupOwnerAddress.isLoopbackAddress()
                            ? "" : info.groupOwnerAddress.getHostAddress(); state = "active";
                    detail = "platform_group_owner_readback_join_phone_wifi_settings";
                    scheduleRead(g, 0, 2000L);
                } else if (attempt < 20) scheduleRead(g, attempt + 1, 500L);
                else { detail = "connection_readback_unavailable"; stopOnMain(); }
            } });
        } });
    }
    private void scheduleRead(final long g, final int attempt, long delay) {
        main.postDelayed(new Runnable() { @Override public void run() { readGroup(g, attempt); } }, delay);
    }
    private void blocked(String reason) { state = "blocked"; detail = reason; }
    public void stop() { main.post(new Runnable() { @Override public void run() { stopOnMain(); } }); }
    private void stopOnMain() {
        if ("inactive".equals(state)) return;
        stopping = true; state = "stopping"; ssid = ""; passphrase = ""; address = "";
        if (hotspot != null) {
            hotspot.close(); hotspot = null; finishStop();
        } else if (groupOwned) removeOwnedGroup();
        else if (!pendingStart) finishStop();
        // A pending platform creation keeps stopping until its callback compensates it.
    }
    private void removeOwnedGroup() {
        if (p2p == null || channel == null) { state = "cleanup_unknown"; return; }
        p2p.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() { @Override public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup observed) {
            if (observed == null) { groupOwned = false; finishStop(); return; }
            if (!observed.isGroupOwner() || !ownedNetworkName.isEmpty() && !ownedNetworkName.equals(observed.getNetworkName())) {
                state = "cleanup_unknown"; detail = "group_identity_changed_refuse_removal"; return;
            }
            removeVerifiedGroup();
        } });
    }
    private void removeVerifiedGroup() {
        p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                p2p.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() { @Override public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup group) {
                    if (group == null) { groupOwned = false; finishStop(); }
                    else { state = "cleanup_unknown"; detail = "group_still_present"; }
                } });
            }
            @Override public void onFailure(int reason) { state = "cleanup_unknown"; detail = "remove_group_error_" + reason; }
        });
    }
    private void releaseChannel() { if (channel != null) { channel.close(); channel = null; } }
    private void finishStop() {
        releaseChannel(); stopping = false; groupOwned = false; pendingStart = false; ++generation;
        ssid = ""; passphrase = ""; address = ""; state = "inactive";
        ownedNetworkName = "";
    }
    public JSONObject snapshot() throws Exception {
        return new JSONObject().put("route", "direct").put("mode", mode).put("state", state)
                .put("detail", detail).put("group_owner_address", address)
                .put("permission_granted", context.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED)
                .put("browser_control_ready", false).put("control_transport", "same_authenticated_lan_gateway");
    }
    /** Headset-local display only; never log or append these credentials to generic status. */
    public JSONObject localJoinDetails() throws Exception {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("local_ui_thread_required");
        return snapshot().put("ssid", ssid).put("passphrase", passphrase);
    }
    public void close() { main.post(new Runnable() { @Override public void run() { closed = true; stopOnMain(); } }); }
}
