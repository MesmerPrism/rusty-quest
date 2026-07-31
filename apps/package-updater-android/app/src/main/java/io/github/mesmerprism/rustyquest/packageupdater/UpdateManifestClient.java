package io.github.mesmerprism.rustyquest.packageupdater;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;

final class UpdateManifestClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    byte[] fetch(URI manifestUri) throws Exception {
        requireFixedHttpsUri(manifestUri);
        URLConnection rawConnection = manifestUri.toURL().openConnection();
        if (!(rawConnection instanceof HttpsURLConnection)) {
            throw new IllegalStateException("manifest_connection_not_https");
        }
        HttpsURLConnection connection = (HttpsURLConnection) rawConnection;
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setUseCaches(false);
        try {
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK) {
                throw new IllegalStateException("manifest_http_status_" + status);
            }
            String contentEncoding = connection.getContentEncoding();
            if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding)) {
                throw new IllegalStateException("manifest_content_encoding_not_identity");
            }
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("manifest_response_too_large");
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("manifest_response_too_large");
                    }
                    output.write(buffer, 0, read);
                }
                if (total == 0) {
                    throw new IllegalStateException("manifest_response_empty");
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    static URI requireFixedHttpsUri(URI uri) {
        if (uri == null
                || !"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("fixed_manifest_url_not_https");
        }
        return uri;
    }
}
