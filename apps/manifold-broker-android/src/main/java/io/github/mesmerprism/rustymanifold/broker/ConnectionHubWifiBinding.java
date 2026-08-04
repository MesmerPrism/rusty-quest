package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Observes only the active Android Wi-Fi Network and its LinkProperties. */
public final class ConnectionHubWifiBinding implements AutoCloseable {
    public interface Listener {
        void onWifiBinding(InetAddress address);
        void onWifiUnavailable(String reason);
    }

    private final ConnectivityManager connectivity;
    private final Listener listener;
    private ConnectivityManager.NetworkCallback callback;
    private InetAddress currentAddress;

    public ConnectionHubWifiBinding(Context context, Listener listener) {
        this.connectivity = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public synchronized void start() {
        if (callback != null) { return; }
        if (connectivity == null) {
            listener.onWifiUnavailable("connectivity_manager_unavailable");
            return;
        }
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { inspect(network); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                inspect(network);
            }
            @Override public void onLost(Network network) { inspect(connectivity.getActiveNetwork()); }
        };
        connectivity.registerDefaultNetworkCallback(callback);
        inspect(connectivity.getActiveNetwork());
    }

    private void inspect(Network network) {
        if (network == null) {
            update(null, "active_network_unavailable");
            return;
        }
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            update(null, "active_network_is_not_wifi");
            return;
        }
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties == null) {
            update(null, "wifi_link_properties_unavailable");
            return;
        }
        InetAddress selected = null;
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress candidate = link.getAddress();
            if (candidate instanceof Inet4Address
                    && !candidate.isAnyLocalAddress()
                    && !candidate.isLoopbackAddress()
                    && !candidate.isLinkLocalAddress()) {
                if (selected != null && !selected.equals(candidate)) {
                    update(null, "wifi_ipv4_binding_ambiguous");
                    return;
                }
                selected = candidate;
            }
        }
        update(selected, selected == null ? "wifi_ipv4_unavailable" : null);
    }

    private synchronized void update(InetAddress address, String failure) {
        if (address == null) {
            if (currentAddress != null) {
                currentAddress = null;
                listener.onWifiUnavailable(failure);
            } else if (failure != null) {
                listener.onWifiUnavailable(failure);
            }
            return;
        }
        if (!address.equals(currentAddress)) {
            currentAddress = address;
            listener.onWifiBinding(address);
        }
    }

    public synchronized InetAddress currentAddress() { return currentAddress; }

    @Override
    public synchronized void close() {
        if (connectivity != null && callback != null) {
            try { connectivity.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        }
        callback = null;
        currentAddress = null;
    }
}
