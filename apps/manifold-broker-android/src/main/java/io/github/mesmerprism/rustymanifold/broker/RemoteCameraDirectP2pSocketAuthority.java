package io.github.mesmerprism.rustymanifold.broker;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;

final class RemoteCameraDirectP2pSocketAuthority {
    static final String AUTHORITY = "rusty_direct_p2p_socket_authority";
    static final String PLATFORM_DEFAULT_AUTHORITY = "platform_default_socket_authority";
    static final String ROUTE_KIND = "rusty_direct_p2p_socket_authority";
    static final String ROUTE_KIND_DIRECT_TCP = "direct_p2p_tcp";
    static final String EXPLICIT_LOCAL_BIND_SELECTION = "rusty_direct_p2p_explicit_local_bind_address";
    static final String INTERFACE_FALLBACK_SELECTION = "rusty_direct_p2p_interface_address";

    private RemoteCameraDirectP2pSocketAuthority() {
    }

    static boolean requiresDirectP2pSocket(
            String routeKind,
            String socketAuthority,
            InetAddress peerAddress) {
        return isDirectP2pRouteKind(routeKind)
                || isDirectP2pSocketAuthority(socketAuthority)
                || isLikelyWifiDirectPeerAddress(peerAddress);
    }

    static boolean isDirectP2pRouteKind(String routeKind) {
        if (routeKind == null) {
            return false;
        }
        String normalized = routeKind.trim().toLowerCase(Locale.US);
        return ROUTE_KIND.equals(normalized) || ROUTE_KIND_DIRECT_TCP.equals(normalized);
    }

    static boolean isDirectP2pSocketAuthority(String socketAuthority) {
        return socketAuthority != null
                && AUTHORITY.equals(socketAuthority.trim().toLowerCase(Locale.US));
    }

    static String defaultSocketAuthority(String routeKind) {
        return isDirectP2pRouteKind(routeKind) ? AUTHORITY : PLATFORM_DEFAULT_AUTHORITY;
    }

    static boolean isValidRouteAuthorityContract(String routeKind, String socketAuthority) {
        return !isDirectP2pRouteKind(routeKind)
                || isDirectP2pSocketAuthority(socketAuthority);
    }

    static boolean isLikelyWifiDirectPeerAddress(InetAddress peerAddress) {
        if (!(peerAddress instanceof Inet4Address)) {
            return false;
        }
        byte[] address = peerAddress.getAddress();
        int first = address[0] & 0xff;
        int second = address[1] & 0xff;
        int third = address[2] & 0xff;
        return first == 192 && second == 168 && (third == 137 || third == 49);
    }

    static boolean isP2pInterfaceName(String interfaceName) {
        return interfaceName != null
                && interfaceName.trim().toLowerCase(Locale.US).contains("p2p");
    }

    static LocalAddressCandidate findLocalAddressCandidate(InetAddress peerAddress) throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            return null;
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            String name = networkInterface.getName();
            boolean p2pInterface = isP2pInterfaceName(name);
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                    continue;
                }
                boolean sameSubnet = sameIpv4Slash24(address, peerAddress);
                if (p2pInterface && sameSubnet) {
                    return new LocalAddressCandidate(
                            address,
                            name == null ? "" : name,
                            p2pInterface,
                            sameSubnet);
                }
            }
        }
        return null;
    }

    static String findInterfaceNameForAddress(InetAddress targetAddress) throws SocketException {
        if (targetAddress == null || targetAddress.isAnyLocalAddress()) {
            return "";
        }
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            return "";
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address.equals(targetAddress)) {
                    String name = networkInterface.getName();
                    return name == null ? "" : name;
                }
            }
        }
        return "";
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

    static final class LocalAddressCandidate {
        final InetAddress address;
        final String interfaceName;
        final boolean p2pInterface;
        final boolean sameSubnet;

        LocalAddressCandidate(
                InetAddress address,
                String interfaceName,
                boolean p2pInterface,
                boolean sameSubnet) {
            this.address = address;
            this.interfaceName = interfaceName;
            this.p2pInterface = p2pInterface;
            this.sameSubnet = sameSubnet;
        }
    }
}
