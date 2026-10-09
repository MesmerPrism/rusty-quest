package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded, versioned Web Bluetooth command authentication. No product secrets are packaged. */
final class ExperimentSessionBleProtocol {
    static final String SERVICE = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020001";
    static final String STATUS = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020002";
    static final String CHALLENGE = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020003";
    static final String COMMAND = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020004";
    static final String RECEIPT = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020005";
    static final int MAX_COMMAND_BYTES = 240;
    private static final char[] BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ234567".toCharArray();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ExperimentSessionBleProtocol() { }

    static String newCode() {
        char[] result = new char[12];
        byte[] random = new byte[result.length];
        RANDOM.nextBytes(random);
        for (int i = 0; i < result.length; i++) {
            result[i] = BASE32[(random[i] & 0xff) % BASE32.length];
        }
        return new String(result);
    }

    static String newHex(int bytes) {
        if (bytes < 1 || bytes > 32) throw new IllegalArgumentException("invalid random size");
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return hex(value, value.length);
    }

    static boolean isLowerHex(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    static boolean supportedOperation(String operation) {
        return "ping".equals(operation) || "arm".equals(operation)
            || "save-next".equals(operation) || "save-exit".equals(operation)
            || "return-vr".equals(operation) || "open-polar".equals(operation);
    }

    static boolean validCommandFields(String id, String operation, String condition,
            int bias, String nonce) {
        if (!isLowerHex(id, 16) || !isLowerHex(nonce, 16)
                || !supportedOperation(operation) || bias < 0 || bias > 100) return false;
        if ("arm".equals(operation)) {
            return "condition-a".equals(condition) || "condition-b".equals(condition);
        }
        return condition != null && condition.isEmpty() && bias == 0;
    }

    static boolean verify(String code, String challenge, String id, String operation,
            String condition, int bias, String nonce, String suppliedMac) {
        if (code == null || !isLowerHex(challenge, 32)
                || !validCommandFields(id, operation, condition, bias, nonce)
                || !isLowerHex(suppliedMac, 32)) return false;
        try {
            String input = "RQEC1|" + challenge + "|" + id + "|" + operation + "|"
                + condition + "|" + bias + "|" + nonce;
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = hmac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = fromHex(suppliedMac);
            byte[] truncated = new byte[16];
            System.arraycopy(expected, 0, truncated, 0, 16);
            return MessageDigest.isEqual(truncated, supplied);
        } catch (Exception invalid) {
            return false;
        }
    }

    static String hex(byte[] bytes, int length) {
        char[] result = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 15];
        }
        return new String(result);
    }

    private static byte[] fromHex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((Character.digit(value.charAt(i * 2), 16) << 4)
                | Character.digit(value.charAt(i * 2 + 1), 16));
        }
        return result;
    }
}
