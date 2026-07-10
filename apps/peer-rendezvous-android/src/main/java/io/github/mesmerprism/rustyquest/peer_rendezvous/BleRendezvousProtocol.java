package io.github.mesmerprism.rustyquest.peer_rendezvous;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

final class BleRendezvousProtocol {
    static final UUID SERVICE_UUID = UUID.fromString("9a7b1001-7d6a-4b7f-9d4a-6f7c0a010001");
    static final UUID OFFER_UUID = UUID.fromString("9a7b1001-7d6a-4b7f-9d4a-6f7c0a010002");
    static final UUID CONTROL_UUID = UUID.fromString("9a7b1001-7d6a-4b7f-9d4a-6f7c0a010003");
    static final UUID STATUS_UUID = UUID.fromString("9a7b1001-7d6a-4b7f-9d4a-6f7c0a010004");
    static final int MAX_WIRE_BYTES = 220;
    static final int REQUESTED_MTU = 247;
    static final int CAPABILITY_GATT = 1;
    static final int CAPABILITY_WIFI_DIRECT = 1 << 1;
    static final int CAPABILITY_DIRECT_P2P = 1 << 2;
    static final int CAPABILITY_MANIFOLD = 1 << 3;
    static final int KNOWN_CAPABILITIES = CAPABILITY_GATT
            | CAPABILITY_WIFI_DIRECT
            | CAPABILITY_DIRECT_P2P
            | CAPABILITY_MANIFOLD;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ALLOWED_KEYS = new HashSet<>(Arrays.asList(
            "m", "v", "k", "sid", "pid", "e", "q", "r", "c", "ws",
            "ip", "bp", "ttl", "n", "a"));

    private BleRendezvousProtocol() {
    }

    static byte[] buildMessage(BleRendezvousConfig config, String kind, int sequence)
            throws Exception {
        JSONObject message = new JSONObject();
        message.put("m", "rqrv");
        message.put("v", 1);
        message.put("k", kind);
        message.put("sid", config.sessionTag);
        message.put("pid", config.peerTag);
        message.put("e", config.epoch);
        message.put("q", sequence);
        message.put("r", config.rolePreference);
        message.put("c", config.capabilities);
        message.put("ws", config.wifiState);
        if (!config.p2pIpv4.isEmpty()) {
            message.put("ip", config.p2pIpv4);
        }
        if (config.brokerPort > 0) {
            message.put("bp", config.brokerPort);
        }
        message.put("ttl", config.ttlMs);
        message.put("n", randomHex(8));
        message.put("a", authTag(signingInput(message), config.sharedSecret));
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("wire_message_too_large");
        }
        return bytes;
    }

    static JSONObject verify(byte[] bytes, String sharedSecret, String expectedSession)
            throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("wire_message_size_invalid");
        }
        JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        Iterator<String> keys = message.keys();
        while (keys.hasNext()) {
            if (!ALLOWED_KEYS.contains(keys.next())) {
                throw new IllegalArgumentException("wire_message_unknown_key");
            }
        }
        if (!"rqrv".equals(message.optString("m")) || message.optInt("v", 0) != 1) {
            throw new IllegalArgumentException("wire_message_version_invalid");
        }
        String kind = message.optString("k");
        if (!"offer".equals(kind)
                && !"proposal".equals(kind)
                && !"accept".equals(kind)
                && !"status".equals(kind)
                && !"close".equals(kind)) {
            throw new IllegalArgumentException("wire_message_kind_invalid");
        }
        String sessionTag = message.optString("sid");
        if (!isSafeTag(sessionTag, 4, 32) || !sessionTag.equals(expectedSession)) {
            throw new IllegalArgumentException("wire_message_session_invalid");
        }
        if (!isSafeTag(message.optString("pid"), 4, 32)
                || message.optInt("e", 0) <= 0
                || message.optInt("q", 0) <= 0) {
            throw new IllegalArgumentException("wire_message_identity_invalid");
        }
        String role = message.optString("r");
        if (!"group_owner".equals(role) && !"client".equals(role) && !"either".equals(role)) {
            throw new IllegalArgumentException("wire_message_role_invalid");
        }
        int capabilities = message.optInt("c", 0);
        if ((capabilities & CAPABILITY_GATT) == 0
                || (capabilities & ~KNOWN_CAPABILITIES) != 0
                || ((capabilities & CAPABILITY_DIRECT_P2P) != 0
                    && (capabilities & CAPABILITY_WIFI_DIRECT) == 0)) {
            throw new IllegalArgumentException("wire_message_capabilities_invalid");
        }
        validateWifiHint(message, capabilities);
        int ttl = message.optInt("ttl", 0);
        if (ttl < 1_000 || ttl > 120_000
                || !isHex(message.optString("n"), 16, 32)
                || !isHex(message.optString("a"), 16, 16)) {
            throw new IllegalArgumentException("wire_message_freshness_or_auth_shape_invalid");
        }
        String expected = authTag(signingInput(message), sharedSecret);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                message.optString("a").toLowerCase(Locale.US).getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("wire_message_authentication_failed");
        }
        return message;
    }

    static boolean selfTest(BleRendezvousConfig config) {
        try {
            byte[] message = buildMessage(config, "offer", 1);
            JSONObject verified = verify(message, config.sharedSecret, config.sessionTag);
            return config.peerTag.equals(verified.optString("pid"));
        } catch (Exception ignored) {
            return false;
        }
    }

    static String signingInput(JSONObject message) {
        return String.format(
                Locale.US,
                "RQRV1|%s|%s|%s|%d|%d|%s|%d|%s|%s|%d|%d|%s",
                message.optString("k"),
                message.optString("sid"),
                message.optString("pid"),
                message.optInt("e"),
                message.optInt("q"),
                message.optString("r"),
                message.optInt("c"),
                message.optString("ws"),
                message.has("ip") ? message.optString("ip") : "-",
                message.optInt("bp", 0),
                message.optInt("ttl"),
                message.optString("n"));
    }

    static boolean isSafeTag(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) {
            return false;
        }
        for (int index = 0; index < value.length(); index += 1) {
            char ch = value.charAt(index);
            if (!Character.isLetterOrDigit(ch) && ch != '.' && ch != '_' && ch != '-') {
                return false;
            }
        }
        return true;
    }

    static boolean isSupportedP2pIpv4(String value) {
        if (value == null) {
            return false;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            int third = Integer.parseInt(parts[2]);
            int fourth = Integer.parseInt(parts[3]);
            return first == 192
                    && second == 168
                    && (third == 49 || third == 137)
                    && fourth >= 1
                    && fourth <= 254;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void validateWifiHint(JSONObject message, int capabilities) {
        String state = message.optString("ws");
        boolean hasIp = message.has("ip");
        boolean hasPort = message.has("bp");
        if ("idle".equals(state) || "discovering".equals(state) || "failed".equals(state)) {
            if (hasIp || hasPort) {
                throw new IllegalArgumentException("wire_message_non_grouped_hint_forbidden");
            }
            return;
        }
        if ("grouped".equals(state)) {
            if (!hasIp || hasPort || !isSupportedP2pIpv4(message.optString("ip"))) {
                throw new IllegalArgumentException("wire_message_grouped_hint_invalid");
            }
            return;
        }
        if (!"ready".equals(state)
                || !hasIp
                || !isSupportedP2pIpv4(message.optString("ip"))
                || message.optInt("bp", 0) <= 0
                || message.optInt("bp", 0) > 65535
                || (capabilities & (CAPABILITY_WIFI_DIRECT | CAPABILITY_DIRECT_P2P | CAPABILITY_MANIFOLD))
                    != (CAPABILITY_WIFI_DIRECT | CAPABILITY_DIRECT_P2P | CAPABILITY_MANIFOLD)) {
            throw new IllegalArgumentException("wire_message_ready_hint_invalid");
        }
    }

    private static String authTag(String input, String sharedSecret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(16);
        for (int index = 0; index < 8; index += 1) {
            result.append(String.format(Locale.US, "%02x", digest[index] & 0xff));
        }
        return result.toString();
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder(byteCount * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean isHex(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) {
            return false;
        }
        for (int index = 0; index < value.length(); index += 1) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
