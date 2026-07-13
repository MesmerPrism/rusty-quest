package io.github.mesmerprism.rustyquest.native_renderer;

/**
 * Closed authority policy for the embedded diagnostic WebSocket.
 *
 * <p>The socket has no Android Binder caller identity. A network peer must
 * therefore never be projected as the renderer process, even when it knows a
 * settings token. Mutations use the signature-scoped Binder or direct
 * in-process path instead.</p>
 */
final class EmbeddedWebSocketAuthorityPolicy {
    static final String LOOPBACK = "127.0.0.1";
    static final String START_REJECTION = "embedded-websocket-loopback-read-only";
    static final String MUTATION_REJECTION = "embedded_websocket_read_only";

    private EmbeddedWebSocketAuthorityPolicy() {
    }

    static String startRejection(
            boolean lanEnabled,
            String bindHost,
            boolean sessionTokenRequired,
            String sessionToken) {
        // Token fields are intentionally observed but never interpreted as
        // caller identity or admission. Missing, wrong, and matching values
        // all fail closed for a LAN/non-loopback embedded socket.
        boolean tokenConfigured = sessionTokenRequired
                && sessionToken != null
                && !sessionToken.trim().isEmpty();
        if (lanEnabled || !LOOPBACK.equals(bindHost)) {
            return START_REJECTION + (tokenConfigured ? "-token-ignored" : "-no-token");
        }
        return null;
    }

    static boolean allowsNetworkMutation() {
        return false;
    }
}
