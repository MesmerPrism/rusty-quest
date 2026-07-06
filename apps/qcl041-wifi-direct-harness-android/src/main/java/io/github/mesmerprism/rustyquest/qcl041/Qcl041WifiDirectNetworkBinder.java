package io.github.mesmerprism.rustyquest.qcl041;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.SystemClock;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

final class Qcl041WifiDirectNetworkBinder {
    private final Context context;
    private final Qcl041LifecycleArtifact artifact;

    Qcl041WifiDirectNetworkBinder(Context context, Qcl041LifecycleArtifact artifact) {
        this.context = context.getApplicationContext();
        this.artifact = artifact;
    }

    Socket createSocketForWifiDirectNetwork(Network network) throws IOException {
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

    boolean bindSocketToWifiDirectLocalAddress(Socket socket, InetAddress groupOwnerAddress) {
        InetAddress localAddress = findWifiDirectLocalAddress(groupOwnerAddress);
        if (localAddress != null) {
            return bindSocketToSpecificLocalAddress(socket, localAddress, groupOwnerAddress, "lifecycle");
        }
        return true;
    }

    boolean bindSocketToSpecificLocalAddress(
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
            return true;
        } catch (Exception ex) {
            artifact.diagnostic(diagnosticGroup, "socket_bind_local_address_error", ex.getMessage());
            return false;
        }
    }

    Network findWifiDirectNetwork(InetAddress groupOwnerAddress) {
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

    Network findUsableWifiDirectNetwork(
            InetAddress groupOwnerAddress,
            String section,
            String prefix,
            long timeoutMs) {
        return findUsableWifiDirectNetwork(groupOwnerAddress, section, prefix, timeoutMs, null);
    }

    Network findUsableWifiDirectNetwork(
            InetAddress groupOwnerAddress,
            String section,
            String prefix,
            long timeoutMs,
            Network preferredNetwork) {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(1L, timeoutMs);
        int attempts = 0;
        String lastRejectReason = "not_scanned";
        artifact.diagnostic(section, prefix + "required", true);
        artifact.diagnostic(section, prefix + "preferred_network_available", preferredNetwork != null);
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts++;
            if (preferredNetwork != null) {
                UsableNetworkScanResult preferredResult = findUsablePreferredWifiDirectNetwork(
                        preferredNetwork,
                        groupOwnerAddress,
                        section,
                        prefix);
                if (preferredResult.network != null) {
                    recordUsableWifiDirectNetworkSelection(
                            preferredResult,
                            section,
                            prefix,
                            started,
                            attempts,
                            true);
                    return preferredResult.network;
                }
                lastRejectReason = "preferred_" + preferredResult.rejectReason;
            }
            UsableNetworkScanResult result = findUsableWifiDirectNetworkOnce(
                    groupOwnerAddress,
                    section,
                    prefix);
            if (result.network != null) {
                recordUsableWifiDirectNetworkSelection(
                        result,
                        section,
                        prefix,
                        started,
                        attempts,
                        false);
                return result.network;
            }
            lastRejectReason = result.rejectReason;
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                lastRejectReason = "interrupted";
                break;
            }
        }
        artifact.diagnostic(section, prefix + "found", false);
        artifact.diagnostic(section, prefix + "wait_attempts", attempts);
        artifact.diagnostic(
                section,
                prefix + "wait_elapsed_ms",
                SystemClock.elapsedRealtime() - started);
        artifact.diagnostic(section, prefix + "reject_reason", lastRejectReason);
        return null;
    }

    private void recordUsableWifiDirectNetworkSelection(
            UsableNetworkScanResult result,
            String section,
            String prefix,
            long started,
            int attempts,
            boolean fromPreferred) {
        artifact.diagnostic(section, prefix + "found", true);
        artifact.diagnostic(section, prefix + "wait_attempts", attempts);
        artifact.diagnostic(
                section,
                prefix + "wait_elapsed_ms",
                SystemClock.elapsedRealtime() - started);
        artifact.diagnostic(section, prefix + "selected_network", result.network.toString());
        artifact.diagnostic(section, prefix + "selected_network_handle", result.network.getNetworkHandle());
        artifact.diagnostic(section, prefix + "selected_interface", result.interfaceName);
        artifact.diagnostic(section, prefix + "selected_handle", result.network.getNetworkHandle());
        artifact.diagnostic(section, prefix + "selected_link_properties_found", result.linkPropertiesFound);
        artifact.diagnostic(section, prefix + "selected_route_matches_group_owner", result.routeMatchesGroupOwner);
        artifact.diagnostic(section, prefix + "selected_wifi_p2p_capability", result.wifiP2pCapability);
        artifact.diagnostic(section, prefix + "selected_local_network_capability", result.localNetworkCapability);
        artifact.diagnostic(section, prefix + "selected_validated", result.validated);
        artifact.diagnostic(section, prefix + "selected_nonvalidated_fallback", !result.validated);
        artifact.diagnostic(section, prefix + "selected_from_preferred", fromPreferred);
        artifact.diagnostic(
                section,
                prefix + "selected_missing_link_properties_fallback",
                result.missingLinkPropertiesFallback);
    }

    void recordConnectivitySnapshot(InetAddress groupOwnerAddress, String section, String prefix) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            artifact.diagnostic(section, prefix + "connectivity_manager_available", false);
            return;
        }
        Network[] networks = connectivityManager.getAllNetworks();
        artifact.diagnostic(section, prefix + "network_count", networks.length);
        for (int index = 0; index < networks.length; index++) {
            Network network = networks[index];
            LinkProperties properties = connectivityManager.getLinkProperties(network);
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            String key = prefix + "network_" + index + "_";
            artifact.diagnostic(section, key + "network", network.toString());
            artifact.diagnostic(section, key + "network_handle", network.getNetworkHandle());
            if (properties != null) {
                artifact.diagnostic(section, key + "interface", properties.getInterfaceName());
                artifact.diagnostic(section, key + "link_addresses", joinLinkAddresses(properties));
                artifact.diagnostic(section, key + "routes", joinRoutes(properties, groupOwnerAddress));
            }
            if (capabilities != null) {
                artifact.diagnostic(section, key + "capabilities", capabilities.toString());
                artifact.diagnostic(
                        section,
                        key + "has_transport_wifi",
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                artifact.diagnostic(
                        section,
                        key + "has_capability_wifi_p2p",
                        hasCapabilityByName(capabilities, "NET_CAPABILITY_WIFI_P2P"));
                artifact.diagnostic(
                        section,
                        key + "has_capability_local_network",
                        hasCapabilityByName(capabilities, "NET_CAPABILITY_LOCAL_NETWORK"));
            }
        }
    }

    InetAddress findWifiDirectLocalAddress(InetAddress groupOwnerAddress) {
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

    static boolean sameIpv4Slash24(InetAddress left, InetAddress right) {
        if (!(left instanceof Inet4Address) || !(right instanceof Inet4Address)) {
            return false;
        }
        byte[] leftBytes = left.getAddress();
        byte[] rightBytes = right.getAddress();
        return leftBytes[0] == rightBytes[0]
                && leftBytes[1] == rightBytes[1]
                && leftBytes[2] == rightBytes[2];
    }

    static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
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

    private UsableNetworkScanResult findUsableWifiDirectNetworkOnce(
            InetAddress groupOwnerAddress,
            String section,
            String prefix) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            artifact.diagnostic(section, prefix + "connectivity_manager_available", false);
            return new UsableNetworkScanResult(null, "connectivity_manager_unavailable");
        }
        Network[] networks = connectivityManager.getAllNetworks();
        artifact.diagnostic(section, prefix + "candidate_count", networks.length);
        String lastRejectReason = "no_p2p_candidate";
        for (int index = 0; index < networks.length; index++) {
            Network network = networks[index];
            String key = prefix + "candidate_" + index + "_";
            UsableNetworkScanResult result = inspectUsableWifiDirectNetworkCandidate(
                    connectivityManager,
                    network,
                    groupOwnerAddress,
                    section,
                    key);
            if (result.network != null) {
                return result;
            }
            lastRejectReason = result.rejectReason;
        }
        return new UsableNetworkScanResult(null, lastRejectReason);
    }

    private UsableNetworkScanResult findUsablePreferredWifiDirectNetwork(
            Network preferredNetwork,
            InetAddress groupOwnerAddress,
            String section,
            String prefix) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            artifact.diagnostic(section, prefix + "preferred_connectivity_manager_available", false);
            return new UsableNetworkScanResult(null, "connectivity_manager_unavailable");
        }
        return inspectUsableWifiDirectNetworkCandidate(
                connectivityManager,
                preferredNetwork,
                groupOwnerAddress,
                section,
                prefix + "preferred_",
                true);
    }

    private UsableNetworkScanResult inspectUsableWifiDirectNetworkCandidate(
            ConnectivityManager connectivityManager,
            Network network,
            InetAddress groupOwnerAddress,
            String section,
            String key) {
        return inspectUsableWifiDirectNetworkCandidate(
                connectivityManager,
                network,
                groupOwnerAddress,
                section,
                key,
                false);
    }

    private UsableNetworkScanResult inspectUsableWifiDirectNetworkCandidate(
            ConnectivityManager connectivityManager,
            Network network,
            InetAddress groupOwnerAddress,
            String section,
            String key,
            boolean allowPreferredMissingLinkPropertiesFallback) {
        artifact.diagnostic(section, key + "network", network.toString());
        artifact.diagnostic(section, key + "network_handle", network.getNetworkHandle());
        LinkProperties properties = connectivityManager.getLinkProperties(network);
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (properties == null) {
            artifact.diagnostic(section, key + "reject_reason", "missing_link_properties");
            artifact.diagnostic(
                    section,
                    key + "missing_link_properties_fallback_enabled",
                    allowPreferredMissingLinkPropertiesFallback);
            if (allowPreferredMissingLinkPropertiesFallback) {
                artifact.diagnostic(
                        section,
                        key + "accepted_without_current_link_properties",
                        true);
                return new UsableNetworkScanResult(
                        network,
                        "preferred_missing_link_properties",
                        "preferred_cached_wifi_direct_network",
                        false,
                        true,
                        false,
                        false,
                        false,
                        false);
            }
            return new UsableNetworkScanResult(null, "missing_link_properties");
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
        boolean wifiP2pCapability = capabilities != null
                && hasCapabilityByName(capabilities, "NET_CAPABILITY_WIFI_P2P");
        boolean localNetworkCapability = capabilities != null
                && hasCapabilityByName(capabilities, "NET_CAPABILITY_LOCAL_NETWORK");
        boolean validated = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean partialConnectivity = capabilities != null
                && hasCapabilityByName(capabilities, "NET_CAPABILITY_PARTIAL_CONNECTIVITY");
        boolean addressSameSubnet = false;
        for (android.net.LinkAddress address : properties.getLinkAddresses()) {
            if (sameIpv4Slash24(address.getAddress(), groupOwnerAddress)) {
                addressSameSubnet = true;
                break;
            }
        }
        artifact.diagnostic(section, key + "interface", interfaceName == null ? "" : interfaceName);
        artifact.diagnostic(section, key + "link_addresses", joinLinkAddresses(properties));
        artifact.diagnostic(section, key + "routes", joinRoutes(properties, groupOwnerAddress));
        if (capabilities != null) {
            artifact.diagnostic(section, key + "capabilities", capabilities.toString());
        }
        artifact.diagnostic(section, key + "p2p_interface", p2pInterface);
        artifact.diagnostic(section, key + "route_matches_group_owner", routeMatches);
        artifact.diagnostic(section, key + "address_same_subnet_as_group_owner", addressSameSubnet);
        artifact.diagnostic(section, key + "wifi_transport", wifiTransport);
        artifact.diagnostic(section, key + "wifi_p2p_capability", wifiP2pCapability);
        artifact.diagnostic(section, key + "local_network_capability", localNetworkCapability);
        artifact.diagnostic(section, key + "validated", validated);
        artifact.diagnostic(section, key + "partial_connectivity", partialConnectivity);
        if (!p2pInterface && !wifiP2pCapability && !localNetworkCapability && !routeMatches && !addressSameSubnet) {
            artifact.diagnostic(section, key + "reject_reason", "not_wifi_direct_network");
            return new UsableNetworkScanResult(null, "not_wifi_direct_network");
        }
        if (!wifiTransport) {
            artifact.diagnostic(section, key + "reject_reason", "missing_wifi_transport");
            return new UsableNetworkScanResult(null, "missing_wifi_transport");
        }
        if (partialConnectivity) {
            artifact.diagnostic(section, key + "reject_reason", "partial_connectivity");
            return new UsableNetworkScanResult(null, "partial_connectivity");
        }
        artifact.diagnostic(section, key + "reject_reason", "");
        return new UsableNetworkScanResult(
                network,
                "",
                interfaceName == null ? "" : interfaceName,
                validated,
                false,
                true,
                routeMatches,
                wifiP2pCapability,
                localNetworkCapability);
    }

    private static final class UsableNetworkScanResult {
        final Network network;
        final String rejectReason;
        final String interfaceName;
        final boolean validated;
        final boolean missingLinkPropertiesFallback;
        final boolean linkPropertiesFound;
        final boolean routeMatchesGroupOwner;
        final boolean wifiP2pCapability;
        final boolean localNetworkCapability;

        UsableNetworkScanResult(Network network, String rejectReason) {
            this(network, rejectReason, "", false, false, false, false, false, false);
        }

        UsableNetworkScanResult(
                Network network,
                String rejectReason,
                String interfaceName,
                boolean validated,
                boolean missingLinkPropertiesFallback,
                boolean linkPropertiesFound,
                boolean routeMatchesGroupOwner,
                boolean wifiP2pCapability,
                boolean localNetworkCapability) {
            this.network = network;
            this.rejectReason = rejectReason == null ? "" : rejectReason;
            this.interfaceName = interfaceName == null ? "" : interfaceName;
            this.validated = validated;
            this.missingLinkPropertiesFallback = missingLinkPropertiesFallback;
            this.linkPropertiesFound = linkPropertiesFound;
            this.routeMatchesGroupOwner = routeMatchesGroupOwner;
            this.wifiP2pCapability = wifiP2pCapability;
            this.localNetworkCapability = localNetworkCapability;
        }
    }

    private static String joinLinkAddresses(LinkProperties properties) {
        List<String> values = new ArrayList<>();
        for (android.net.LinkAddress address : properties.getLinkAddresses()) {
            values.add(address.toString());
        }
        return join(values);
    }

    private static String joinRoutes(LinkProperties properties, InetAddress groupOwnerAddress) {
        List<String> values = new ArrayList<>();
        for (RouteInfo route : properties.getRoutes()) {
            boolean matches = false;
            try {
                matches = groupOwnerAddress != null && route.matches(groupOwnerAddress);
            } catch (Exception ignored) {
            }
            values.add(route.toString() + "|matches_group_owner=" + matches);
        }
        return join(values);
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(" ; ");
            }
            builder.append(values.get(index));
        }
        return builder.toString();
    }

    private static boolean hasCapabilityByName(NetworkCapabilities capabilities, String fieldName) {
        try {
            int capability = NetworkCapabilities.class.getField(fieldName).getInt(null);
            return capabilities.hasCapability(capability);
        } catch (Exception ignored) {
            return false;
        }
    }
}
