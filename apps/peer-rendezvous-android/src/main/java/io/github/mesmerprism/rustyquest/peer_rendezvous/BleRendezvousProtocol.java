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
import org.json.JSONArray;

final class BleRendezvousProtocol {
    static final UUID SERVICE_UUID = UUID.fromString("9a7b1001-7d6a-4b7f-9d4a-6f7c0a010001");
    static final UUID OFFER_UUID = UUID.fromString("9a7b1001-7d6a-4b7f-9d4a-6f7c0a010002");
    static final UUID CONTROL_UUID = UUID.fromString("9a7b1001-7d6a-4b7f-9d4a-6f7c0a010003");
    static final UUID STATUS_UUID = UUID.fromString("9a7b1001-7d6a-4b7f-9d4a-6f7c0a010004");
    static final int MAX_WIRE_BYTES = 220;
    static final int V2_MAX_WIRE_BYTES = 244;
    static long epoch(BleRendezvousConfig config) {return config.observedCoordination?config.coordinationEpoch:config.epoch;}
    static int maximumBytes(BleRendezvousConfig config) { return config.observedCoordination?V2_MAX_WIRE_BYTES:MAX_WIRE_BYTES; }
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
        if(config.observedCoordination)return buildObserved(config,kind,sequence);
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
        if (bytes == null || bytes.length == 0 || bytes.length > V2_MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("wire_message_size_invalid");
        }
        String raw=new String(bytes,StandardCharsets.UTF_8);
        if(raw.startsWith("["))return decodeObserved(raw,sharedSecret,expectedSession);
        JSONObject message = new JSONObject(raw);
        if(bytes.length>MAX_WIRE_BYTES)throw new IllegalArgumentException("wire_message_size_invalid");
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

    private static final Set<String> V2_KEYS=new HashSet<>(Arrays.asList(
            "m","v","k","sid","pid","e","q","r","ip","li","b","g","n","x","a"));
    private static byte[] buildObserved(BleRendezvousConfig c,String kind,int sequence)throws Exception {
        BleRoleReadiness.Observation o=c.observation;
        BleRoleReadiness.requireFresh(o,c.observationBoot,c.rolePreference,android.os.SystemClock.elapsedRealtime());
        JSONObject m=new JSONObject();m.put("m","rqrv");m.put("v",2);m.put("k",kind);
        m.put("sid",c.sessionTag);m.put("pid",c.peerTag);m.put("e",c.coordinationEpoch);m.put("q",sequence);
        m.put("r",o.role.equals("group_owner")?"g":"c");m.put("ip",o.ownerIp);m.put("li",o.localIp);
        m.put("b",o.boot);m.put("g",o.group);m.put("n",randomHex(8));m.put("x",c.challenge);
        m.put("a",authTag(signingObserved(m),c.sharedSecret));
        JSONArray wire=new JSONArray();wire.put("rqrv2");wire.put(kindCode(kind));
        for(String key:new String[]{"sid","pid","e","q","r","ip","li","b","g","n","x","a"})wire.put(m.get(key));
        byte[] bytes=wire.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>V2_MAX_WIRE_BYTES)throw new IllegalArgumentException("v2_wire_message_too_large");
        return bytes;
    }
    private static String kindCode(String kind) {
        switch(kind){case "offer":return "o";case "proposal":return "p";case "accept":return "a";
            case "status":return "s";case "close":return "c";default:throw new IllegalArgumentException("v2_kind_invalid");}
    }
    private static JSONObject decodeObserved(String raw,String secret,String session)throws Exception {
        JSONArray wire=new JSONArray(raw);
        if(wire.length()!=14||!raw.equals(wire.toString())||!"rqrv2".equals(wire.optString(0)))
            throw new IllegalArgumentException("v2_wire_array_shape_invalid");
        String kind;
        switch(wire.optString(1)){case "o":kind="offer";break;case "p":kind="proposal";break;
            case "a":kind="accept";break;case "s":kind="status";break;case "c":kind="close";break;
            default:throw new IllegalArgumentException("v2_kind_invalid");}
        JSONObject m=new JSONObject();m.put("m","rqrv");m.put("v",2);m.put("k",kind);
        String[] keys={"sid","pid","e","q","r","ip","li","b","g","n","x","a"};
        for(int i=0;i<keys.length;i++)m.put(keys[i],wire.get(i+2));
        return verifyObserved(m,secret,session);
    }
    private static JSONObject verifyObserved(JSONObject m,String secret,String session)throws Exception {
        for(String key:V2_KEYS) {
            Object value=m.opt(key);
            boolean number="v".equals(key)||"e".equals(key)||"q".equals(key);
            if(number?!(value instanceof Integer||("e".equals(key)&&value instanceof Long)):!(value instanceof String))
                throw new IllegalArgumentException("v2_wire_field_type_invalid");
        }
        Iterator<String> keys=m.keys();while(keys.hasNext())if(!V2_KEYS.contains(keys.next()))
            throw new IllegalArgumentException("v2_wire_unknown_key");
        if(m.length()!=V2_KEYS.size()||!"rqrv".equals(m.optString("m"))
                ||!session.equals(m.optString("sid"))||!isSafeTag(m.optString("pid"),4,32)
                ||m.optLong("e",0)<1||m.optInt("q",0)<1
                ||!("g".equals(m.optString("r"))||"c".equals(m.optString("r")))
                ||!BleRoleReadiness.address(m.optString("ip"))||!BleRoleReadiness.address(m.optString("li"))
                ||!isHex(m.optString("b"),16,16)||!isHex(m.optString("g"),16,16)
                ||!isHex(m.optString("n"),16,16)||!isHex(m.optString("a"),16,16)
                ||!(m.optString("x").isEmpty()||isHex(m.optString("x"),16,16)))
            throw new IllegalArgumentException("v2_wire_shape_invalid");
        String expected=authTag(signingObserved(m),secret);
        if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                m.optString("a").getBytes(StandardCharsets.US_ASCII)))
            throw new SecurityException("wire_message_authentication_failed");
        return m;
    }
    private static String signingObserved(JSONObject m) {
        StringBuilder s=new StringBuilder("RQRV2");
        for(String key:new String[]{"k","sid","pid","e","q","r","ip","li","b","g","n","x"})
            s.append('|').append(m.optString(key));
        return s.toString();
    }
    static void requireCurrentLocalMessage(BleRendezvousConfig c,byte[] bytes)throws Exception {
        if(!c.observedCoordination)return;
        JSONObject m=verify(bytes,c.sharedSecret,c.sessionTag);
        BleRoleReadiness.Observation o=c.observation;
        BleRoleReadiness.requireFresh(o,c.observationBoot,c.rolePreference,android.os.SystemClock.elapsedRealtime());
        if(m.optInt("v",0)!=2||m.optLong("e",0)!=c.coordinationEpoch
                ||!c.peerTag.equals(m.optString("pid"))||!o.boot.equals(m.optString("b"))
                ||!o.group.equals(m.optString("g"))||!o.ownerIp.equals(m.optString("ip"))
                ||!o.localIp.equals(m.optString("li"))
                ||!(o.role.equals("group_owner")?"g":"c").equals(m.optString("r")))
            throw new IllegalArgumentException("v2_cached_message_observation_changed");
    }
    static void requireObservedPeer(BleRendezvousConfig c,JSONObject m) {
        if(!c.observedCoordination) {
            if(m.optInt("v",0)!=1)throw new IllegalArgumentException("unexpected_coordination_version");
            String peer=m.optString("pid"),remote=m.optString("r");
            String role=BleRoleReadiness.resolveRole(c.rolePreference,remote,c.peerTag,peer);
            if(c.coordinatedPeer!=null&&(!c.coordinatedPeer.equals(peer)
                    ||!c.remoteConfiguredRole.equals(remote)||!c.resolvedRole.equals(role)))
                throw new IllegalArgumentException("configured_role_changed_across_reconnect");
            c.coordinatedPeer=peer;c.remoteConfiguredRole=remote;c.resolvedRole=role;
            return;
        }
        if(m.optInt("v",0)!=2)throw new IllegalArgumentException("observed_coordination_version_required");
        c.readiness.requirePeer(c.observation,c.rolePreference,android.os.SystemClock.elapsedRealtime(),
                c.expectedPeerTag,m.optString("pid"),m.optString("b"),m.optString("g"),
                "g".equals(m.optString("r"))?"group_owner":"client",m.optString("ip"),m.optString("li"));
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
