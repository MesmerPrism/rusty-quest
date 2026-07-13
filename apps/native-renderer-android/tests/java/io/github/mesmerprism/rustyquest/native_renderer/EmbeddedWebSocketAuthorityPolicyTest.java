package io.github.mesmerprism.rustyquest.native_renderer;

/** Host-Java damaged-case test for embedded WebSocket authority isolation. */
public final class EmbeddedWebSocketAuthorityPolicyTest {
    public static void main(String[] args) {
        require(EmbeddedWebSocketAuthorityPolicy.startRejection(
                false, "127.0.0.1", false, "") == null, "loopback readiness");
        reject(true, "0.0.0.0", false, "", "missing LAN token");
        reject(true, "0.0.0.0", true, "attacker-token", "bad LAN token");
        reject(true, "0.0.0.0", true, "configured-token", "configured LAN token");
        reject(false, "192.168.1.2", true, "configured-token", "non-loopback bind");
        require(!EmbeddedWebSocketAuthorityPolicy.allowsNetworkMutation(),
                "local other-app mutation");
        require("embedded_websocket_read_only".equals(
                EmbeddedWebSocketAuthorityPolicy.MUTATION_REJECTION),
                "stable mutation rejection");
    }

    private static void reject(
            boolean lanEnabled,
            String host,
            boolean tokenRequired,
            String token,
            String label) {
        require(EmbeddedWebSocketAuthorityPolicy.startRejection(
                lanEnabled, host, tokenRequired, token) != null, label);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
