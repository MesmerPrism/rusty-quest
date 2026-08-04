package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects one unambiguous private IPv4 address for wearer-reviewed LAN binding.
 *
 * <p>Interface discovery is platform observation only. It never enables the
 * listener or grants controller authority.
 */
public final class PrivateAddressSelector {
    public record Candidate(InetAddress address, String status, String displayText) {
        public Candidate {
            if ((address == null) != !status.equals("private_address_ready")) {
                throw new IllegalArgumentException("ready status and address must agree");
            }
        }

        public boolean available() {
            return address != null;
        }

        public static Candidate unavailable() {
            return new Candidate(
                    null,
                    "no_private_address",
                    "No private Wi-Fi or hotspot address is available.");
        }
    }

    private PrivateAddressSelector() {}

    public static Candidate selectHostAddress() {
        try {
            List<InetAddress> observed = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return Candidate.unavailable();
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    observed.add(addresses.nextElement());
                }
            }
            return select(observed);
        } catch (SocketException | SecurityException error) {
            return new Candidate(
                    null,
                    "private_address_observation_failed",
                    "The private network address could not be verified.");
        }
    }

    static Candidate select(List<InetAddress> observed) {
        Map<String, InetAddress> eligible = new LinkedHashMap<>();
        observed.stream()
                .filter(Inet4Address.class::isInstance)
                .filter(address -> !address.isLoopbackAddress())
                .filter(TrustedLocalHttpServer::isTrustedBindAddress)
                .sorted(Comparator.comparing(InetAddress::getHostAddress))
                .forEach(address -> eligible.putIfAbsent(address.getHostAddress(), address));
        if (eligible.isEmpty()) {
            return Candidate.unavailable();
        }
        if (eligible.size() != 1) {
            return new Candidate(
                    null,
                    "ambiguous_private_addresses",
                    "Multiple private addresses were found; listener enable is blocked.");
        }
        InetAddress selected = eligible.values().iterator().next();
        return new Candidate(
                selected,
                "private_address_ready",
                "Candidate: " + selected.getHostAddress());
    }
}
