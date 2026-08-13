package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Opens the product-owned byte transport consumed by the packed-stereo decoder. */
final class SpatialPeerStereoTransport {
    static final String DIRECT_TCP_CONNECT = "direct_tcp_connect";
    static final String DIRECT_P2P_TCP = "direct_p2p_tcp";
    static final String RELAY_TLS_CLIENT = "relay_tls_client";
    private static final byte[] RELAY_MAGIC = "RQPRLY1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int RELAY_SCHEMA_VERSION = 1;
    private static final int RELAY_ROLE_RECEIVER = 2;
    private static final int MAX_FIELD_BYTES = 1024;

    private SpatialPeerStereoTransport() {}

    static Socket connect(
        String routeKind,
        String host,
        int port,
        int timeoutMs,
        String sessionId,
        String relayChannel,
        String tlsServerName,
        String authToken
    ) throws IOException {
        String route = normalizeRouteKind(routeKind);
        String endpointHost = normalizeHost(host);
        int timeout = clamp(timeoutMs, 100, 60_000);
        if (port <= 0 || port > 65_535) {
            throw new IOException("Peer stereo endpoint port is invalid");
        }
        if (RELAY_TLS_CLIENT.equals(route)) {
            return connectAuthenticatedTlsRelay(
                endpointHost,
                port,
                timeout,
                sessionId,
                relayChannel,
                tlsServerName,
                authToken
            );
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(endpointHost, port), timeout);
        socket.setSoTimeout(1000);
        return socket;
    }

    static String normalizeRouteKind(String routeKind) {
        String value = routeKind == null
            ? ""
            : routeKind.trim().toLowerCase(Locale.US).replace('-', '_');
        if (DIRECT_P2P_TCP.equals(value) || "wifi_direct".equals(value) || "qcl100".equals(value)) {
            return DIRECT_P2P_TCP;
        }
        if (RELAY_TLS_CLIENT.equals(value)
                || "relay_tls".equals(value)
                || "authenticated_tls_relay".equals(value)) {
            return RELAY_TLS_CLIENT;
        }
        return DIRECT_TCP_CONNECT;
    }

    static String safeRouteMarker(String routeKind, String sessionId, boolean authProvided) {
        String route = normalizeRouteKind(routeKind);
        return "peerRouteKind=" + route
            + " peerTransportEncrypted=" + RELAY_TLS_CLIENT.equals(route)
            + " peerSessionAccepted=" + present(sessionId)
            + " peerAuthenticationProvided=" + authProvided
            + " peerEndpointRedacted=true";
    }

    private static Socket connectAuthenticatedTlsRelay(
        String endpointHost,
        int port,
        int timeoutMs,
        String sessionId,
        String relayChannel,
        String tlsServerName,
        String authToken
    ) throws IOException {
        if (!present(sessionId)
                || !present(relayChannel)
                || !present(tlsServerName)
                || !present(authToken)) {
            throw new IOException("Authenticated TLS relay requires accepted session, channel, server name, and bearer");
        }
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(endpointHost, port), timeoutMs);
        SSLSocket socket = null;
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) factory.createSocket(
                raw,
                tlsServerName.trim(),
                port,
                true
            );
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();
            writeRelayAuthentication(
                new DataOutputStream(socket.getOutputStream()),
                RELAY_ROLE_RECEIVER,
                sessionId,
                relayChannel,
                authToken
            );
            socket.setSoTimeout(1000);
            return socket;
        } catch (IOException | RuntimeException error) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            } else {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
            }
            throw error;
        }
    }

    static void writeRelayAuthentication(
        DataOutputStream output,
        int role,
        String sessionId,
        String relayChannel,
        String authToken
    ) throws IOException {
        output.write(RELAY_MAGIC);
        output.writeInt(RELAY_SCHEMA_VERSION);
        output.writeByte(role);
        writeBoundedUtf8(output, sessionId, "session id");
        writeBoundedUtf8(output, relayChannel, "relay channel");
        writeBoundedUtf8(output, authToken, "relay bearer");
        output.flush();
    }

    private static void writeBoundedUtf8(DataOutputStream output, String value, String label)
        throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.trim().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_FIELD_BYTES) {
            throw new IOException("Peer stereo " + label + " length is invalid");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim();
        return value.isEmpty() ? "127.0.0.1" : value;
    }

    private static boolean present(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
