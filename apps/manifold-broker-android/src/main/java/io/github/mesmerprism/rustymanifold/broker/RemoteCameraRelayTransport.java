package io.github.mesmerprism.rustymanifold.broker;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Authenticated TLS adapter for the existing binary RMANVID stream. */
final class RemoteCameraRelayTransport {
    static final String ROUTE_KIND = "relay_tls_client";
    private static final byte[] MAGIC = "RQPRLY1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int SCHEMA_VERSION = 1;
    private static final int ROLE_SENDER = 1;
    private static final int ROLE_RECEIVER = 2;

    private RemoteCameraRelayTransport() {}

    static boolean applies(String routeKind) {
        return ROUTE_KIND.equals(routeKind == null ? "" : routeKind.trim());
    }

    static Socket connect(
            String host,
            int port,
            int timeoutMs,
            RemoteCameraRelayCredential.Snapshot credential) throws IOException {
        return connect(host, port, timeoutMs, credential, ROLE_SENDER);
    }

    static Socket connectReceiver(
            String host,
            int port,
            int timeoutMs,
            RemoteCameraRelayCredential.Snapshot credential) throws IOException {
        return connect(host, port, timeoutMs, credential, ROLE_RECEIVER);
    }

    private static Socket connect(
            String host,
            int port,
            int timeoutMs,
            RemoteCameraRelayCredential.Snapshot credential,
            int role) throws IOException {
        if (credential == null || !credential.ready()) {
            throw new IOException("Authenticated TLS relay credential is unavailable");
        }
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        SSLSocket socket = null;
        try {
            SSLSocketFactory factory = socketFactory(credential);
            socket = (SSLSocket) factory.createSocket(
                    raw,
                    credential.tlsServerName,
                    port,
                    true);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify(credential.tlsServerName, socket.getSession())) {
                throw new SSLHandshakeException("Relay TLS server-name verification failed");
            }
            writeAuthentication(new DataOutputStream(socket.getOutputStream()), credential, role);
            socket.setSoTimeout(1000);
            return socket;
        } catch (IOException | RuntimeException error) {
            closeQuietly(socket == null ? raw : socket);
            throw error;
        }
    }

    private static SSLSocketFactory socketFactory(
            RemoteCameraRelayCredential.Snapshot credential) throws IOException {
        if (credential.certificateSha256.length() == 0) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        final byte[] expected = decodeSha256(credential.certificateSha256);
        try {
            X509TrustManager pinnedTrust = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    throw new CertificateException("Relay client certificates are not accepted");
                }

                @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("Relay server certificate chain is empty");
                    }
                    chain[0].checkValidity();
                    try {
                        byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        if (!MessageDigest.isEqual(expected, actual)) {
                            throw new CertificateException("Relay server certificate pin mismatch");
                        }
                    } catch (GeneralSecurityException error) {
                        throw new CertificateException("Relay server certificate pin check failed", error);
                    }
                }

                @Override public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {pinnedTrust}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException error) {
            throw new IOException("Relay pinned TLS context initialization failed", error);
        }
    }

    private static byte[] decodeSha256(String value) throws IOException {
        if (value.length() != 64) {
            throw new IOException("Relay certificate SHA-256 is invalid");
        }
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IOException("Relay certificate SHA-256 is invalid");
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    static void writeAuthentication(
            DataOutputStream output,
            RemoteCameraRelayCredential.Snapshot credential) throws IOException {
        writeAuthentication(output, credential, ROLE_SENDER);
    }

    private static void writeAuthentication(
            DataOutputStream output,
            RemoteCameraRelayCredential.Snapshot credential,
            int role) throws IOException {
        output.write(MAGIC);
        output.writeInt(SCHEMA_VERSION);
        output.writeByte(role);
        writeUtf8(output, credential.sessionId);
        writeUtf8(output, credential.channel);
        writeUtf8(output, credential.authToken);
        output.flush();
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 1024) {
            throw new IOException("Relay authentication field length is invalid");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
