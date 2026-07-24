package io.github.mesmerprism.rustyquest.fleetagent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class FleetAgentPublisher {
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int TIMEOUT_MS = 5_000;

    private FleetAgentPublisher() {
    }

    static Result post(URI endpoint, String envelopeJson) throws IOException {
        byte[] request = envelopeJson.getBytes(StandardCharsets.UTF_8);
        if (request.length == 0 || request.length > MAX_REQUEST_BYTES) {
            throw new IOException("request_size_invalid");
        }
        HttpURLConnection connection =
                (HttpURLConnection) endpoint.toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setFixedLengthStreamingMode(request.length);
        connection.setDoOutput(true);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }
            int statusCode = connection.getResponseCode();
            InputStream input = statusCode >= 200 && statusCode < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = input == null ? "" : readBounded(input);
            return new Result(statusCode, response);
        } finally {
            connection.disconnect();
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            while (true) {
                int read = source.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("response_size_exceeded");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static final class Result {
        final int statusCode;
        final String responseJson;

        Result(int statusCode, String responseJson) {
            this.statusCode = statusCode;
            this.responseJson = responseJson;
        }

        boolean accepted() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
