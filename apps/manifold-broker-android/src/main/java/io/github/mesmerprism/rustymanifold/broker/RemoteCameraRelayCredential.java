package io.github.mesmerprism.rustymanifold.broker;

import android.content.Intent;

/** Process-memory-only relay credential provisioned by an explicit app launch. */
final class RemoteCameraRelayCredential {
    static final String EXTRA_SESSION_ID = "rustyquest.remote_camera.relay.session_id";
    static final String EXTRA_CHANNEL = "rustyquest.remote_camera.relay.channel";
    static final String EXTRA_TLS_SERVER_NAME = "rustyquest.remote_camera.relay.tls_server_name";
    static final String EXTRA_AUTH_TOKEN = "rustyquest.remote_camera.relay.auth_token";
    static final String EXTRA_CERTIFICATE_SHA256 = "rustyquest.remote_camera.relay.certificate_sha256";
    static final String EXTRA_CLEAR = "rustyquest.remote_camera.relay.clear";
    private static final int MAX_FIELD_LENGTH = 1024;
    private static final Object LOCK = new Object();
    private static Snapshot current = Snapshot.empty();

    private RemoteCameraRelayCredential() {}

    static void adopt(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            clear();
            return;
        }
        if (!intent.hasExtra(EXTRA_SESSION_ID)
                && !intent.hasExtra(EXTRA_CHANNEL)
                && !intent.hasExtra(EXTRA_TLS_SERVER_NAME)
                && !intent.hasExtra(EXTRA_AUTH_TOKEN)
                && !intent.hasExtra(EXTRA_CERTIFICATE_SHA256)) {
            return;
        }
        String sessionId = bounded(intent.getStringExtra(EXTRA_SESSION_ID), "session id");
        String channel = bounded(intent.getStringExtra(EXTRA_CHANNEL), "channel");
        String serverName = bounded(intent.getStringExtra(EXTRA_TLS_SERVER_NAME), "TLS server name");
        String authToken = bounded(intent.getStringExtra(EXTRA_AUTH_TOKEN), "authentication token");
        String certificateSha256 = optionalSha256(intent.getStringExtra(EXTRA_CERTIFICATE_SHA256));
        synchronized (LOCK) {
            current = new Snapshot(sessionId, channel, serverName, authToken, certificateSha256);
        }
    }

    static Snapshot forSession(String sessionId) {
        synchronized (LOCK) {
            if (!current.sessionId.equals(sessionId == null ? "" : sessionId.trim())) {
                return Snapshot.empty();
            }
            return current;
        }
    }

    static void clearIfSession(String sessionId) {
        synchronized (LOCK) {
            if (current.sessionId.equals(sessionId == null ? "" : sessionId.trim())) {
                current = Snapshot.empty();
            }
        }
    }

    static void clear() {
        synchronized (LOCK) {
            current = Snapshot.empty();
        }
    }

    private static String bounded(String value, String label) {
        String result = value == null ? "" : value.trim();
        if (result.length() == 0 || result.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("Relay " + label + " length is invalid");
        }
        return result;
    }

    private static String optionalSha256(String value) {
        String result = value == null ? "" : value.trim().toLowerCase();
        if (result.startsWith("sha256:")) {
            result = result.substring("sha256:".length());
        }
        if (result.length() == 0) {
            return "";
        }
        if (result.length() != 64 || !result.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Relay certificate SHA-256 is invalid");
        }
        return result;
    }

    static final class Snapshot {
        final String sessionId;
        final String channel;
        final String tlsServerName;
        final String authToken;
        final String certificateSha256;

        Snapshot(
                String sessionId,
                String channel,
                String tlsServerName,
                String authToken,
                String certificateSha256) {
            this.sessionId = sessionId;
            this.channel = channel;
            this.tlsServerName = tlsServerName;
            this.authToken = authToken;
            this.certificateSha256 = certificateSha256;
        }

        static Snapshot empty() {
            return new Snapshot("", "", "", "", "");
        }

        boolean ready() {
            return sessionId.length() > 0
                && channel.length() > 0
                && tlsServerName.length() > 0
                && authToken.length() > 0;
        }

        String safeMarker() {
            return "relayCredentialReady=" + ready()
                + " relayChannelProvided=" + (channel.length() > 0)
                + " relayTlsServerNameProvided=" + (tlsServerName.length() > 0)
                + " relayAuthenticationProvided=" + (authToken.length() > 0)
                + " relayCertificatePinProvided=" + (certificateSha256.length() > 0)
                + " relaySecretSerialized=false";
        }
    }
}
