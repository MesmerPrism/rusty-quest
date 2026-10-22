package io.github.mesmerprism.rustyquest.displaystream;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Closed ASCII control wire. Its canonical spelling rejects duplicate/extra fields. */
final class DisplayStreamControl {
    private DisplayStreamControl() { }
    static String stop(DisplayStreamAttempt attempt) {
        return "{\"schema\":\"rusty.quest.display_stream.control.v1\",\"action\":\"stop\","
                + "\"invocation_id\":\"" + attempt.invocationId + "\",\"generation\":\""
                + attempt.generation + "\",\"stop_nonce\":\"" + attempt.stopNonce + "\"}";
    }
    static boolean accept(byte[] line, DisplayStreamAttempt attempt) {
        return matches(line, attempt)
                && attempt.requestStop(attempt.invocationId, attempt.generation, attempt.stopNonce);
    }
    static boolean matches(byte[] line, DisplayStreamAttempt attempt) {
        return line.length <= 512 && java.util.Arrays.equals(line,
                stop(attempt).getBytes(StandardCharsets.US_ASCII));
    }
    static byte[] line(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return out.toByteArray();
            if (value < 32 || value > 126 || out.size() == 512) throw new IllegalArgumentException("invalid control frame");
            out.write(value);
        }
        if (out.size() != 0) throw new IllegalArgumentException("truncated control frame");
        return null;
    }
}
