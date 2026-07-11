package io.github.mesmerprism.rustyquest.directp2p;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.SystemClock;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

final class AndroidNetworkBindingProvider {
    static final class Selection {
        final long networkHandle;
        final boolean networkAvailable;
        final String interfaceName;
        final String localHost;
        final boolean routeMatchesGroupOwner;

        Selection(long networkHandle, boolean networkAvailable, String interfaceName, String localHost, boolean routeMatchesGroupOwner) {
            this.networkHandle = networkHandle;
            this.networkAvailable = networkAvailable;
            this.interfaceName = interfaceName;
            this.localHost = localHost;
            this.routeMatchesGroupOwner = routeMatchesGroupOwner;
        }
    }

    private final ConnectivityManager connectivity;

    AndroidNetworkBindingProvider(Context context) {
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    Selection awaitSelection(InetAddress groupOwner, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            Selection selection = selectOnce(groupOwner);
            if (selection != null) return selection;
            SystemClock.sleep(250L);
        }
        String localHost = findP2p0Ipv4();
        if (localHost != null && sameSlash24(localHost, groupOwner)) {
            return new Selection(0L, false, "p2p0", localHost, true);
        }
        return null;
    }

    private Selection selectOnce(InetAddress groupOwner) {
        if (connectivity == null) return null;
        for (Network network : connectivity.getAllNetworks()) {
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties == null || !"p2p0".equals(properties.getInterfaceName())) continue;
            String localHost = null;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    localHost = address.getAddress().getHostAddress();
                    break;
                }
            }
            if (localHost == null) localHost = findP2p0Ipv4();
            boolean routeMatch = false;
            for (RouteInfo route : properties.getRoutes()) {
                if (route.matches(groupOwner)) {
                    routeMatch = true;
                    break;
                }
            }
            if (localHost != null && routeMatch) {
                return new Selection(network.getNetworkHandle(), true, "p2p0", localHost, true);
            }
        }
        return null;
    }

    static String findP2p0Ipv4() {
        try {
            NetworkInterface network = NetworkInterface.getByName("p2p0");
            if (network == null) return null;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean sameSlash24(String localHost, InetAddress peer) {
        try {
            byte[] left = InetAddress.getByName(localHost).getAddress();
            byte[] right = peer.getAddress();
            return left.length == 4 && right.length == 4
                    && left[0] == right[0] && left[1] == right[1] && left[2] == right[2];
        } catch (Exception ignored) {
            return false;
        }
    }
}
