package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exact v2 canonical-frame and legacy-byte compatibility conformance. */
public final class ConnectionHubProtocolV2VectorsTest {
    private final JSONObject root;
    private final JSONObject messages;

    private ConnectionHubProtocolV2VectorsTest(JSONObject root) throws Exception {
        this.root = root;
        this.messages = root.getJSONObject("messages");
        requireEquals("rusty.quest.connection_hub.protocol_vectors.v2", root.getString("$schema"));
        requireEquals("rusty-quest", root.getString("owner"));
        requireEquals("rusty.quest.connection_hub.v2", root.getString("protocol_id"));
        requireEquals(ConnectionHubProtocol.SOCKET_PATH, root.getString("socket_path"));
    }

    private void validate(String id, JSONObject value) throws Exception {
        JSONObject vector = messages.getJSONObject(id);
        Set<String> required = names(vector.getJSONArray("required_fields"));
        Set<String> allowed = new LinkedHashSet<>(required);
        allowed.addAll(names(vector.getJSONArray("optional_fields")));
        for (String key : required) {
            if (!value.has(key)) throw new IllegalArgumentException("missing field " + key);
        }
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw new IllegalArgumentException("unknown field " + key);
        }
        requireEquals(vector.getString("schema"), value.getString("$schema"));
        requireEquals(vector.getString("type"), value.getString("type"));
    }

    private void validateAllExamplesAndDamage() throws Exception {
        java.util.Iterator<String> ids = messages.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject example = messages.getJSONObject(id).getJSONObject("example");
            validate(id, example);
            JSONObject unknown = new JSONObject(example.toString()).put("unexpected", true);
            try {
                validate(id, unknown);
                throw new AssertionError("damaged v2 vector accepted: " + id);
            } catch (IllegalArgumentException expected) {}
        }
        validate("socket_authentication_receipt",
                ConnectionHubProtocol.socketAuthenticationReceiptV2(2, 7, 1_893_456_000_000L));
    }

    private void validateCanonicalFrames() throws Exception {
        JSONObject frames = root.getJSONObject("canonical_frames");
        java.util.Iterator<String> ids = frames.keys();
        while (ids.hasNext()) {
            JSONObject frame = frames.getJSONObject(ids.next());
            String exact = frame.getString("utf8");
            requireEquals(exact, ConnectionHubRuntime.canonicalJson(new JSONObject(exact)));
            requireEquals(frame.getString("sha256"), sha256(exact.getBytes(StandardCharsets.UTF_8)));
        }
        String command = frames.getJSONObject("surface_command").getString("utf8");
        JSONObject commandJson = new JSONObject(command);
        ConnectionHubRuntime.validateV2CommandFrame(commandJson, command);
        expectRejected(new CheckedAction() {
            @Override public void run() throws Exception {
                ConnectionHubRuntime.validateV2CommandFrame(commandJson, command + " ");
            }
        });
        String keepalive = frames.getJSONObject("keepalive").getString("utf8");
        ConnectionHubRuntime.validateV2KeepaliveFrame(new JSONObject(keepalive), keepalive);
        expectRejected(new CheckedAction() {
            @Override public void run() throws Exception {
                JSONObject damaged = new JSONObject(keepalive).put("unexpected", true);
                ConnectionHubRuntime.validateV2KeepaliveFrame(
                        damaged, ConnectionHubRuntime.canonicalJson(damaged));
            }
        });
    }

    private void validateLegacyBytes(byte[] legacyBytes) throws Exception {
        requireEquals(root.getString("legacy_protocol_sha256"), sha256(legacyBytes));
        JSONObject legacy = new JSONObject(new String(legacyBytes, StandardCharsets.UTF_8));
        requireEquals("rusty.quest.connection_hub.protocol_vectors.v1", legacy.getString("$schema"));
        requireEquals("connection-hub-protocol-v1.json", root.getString("legacy_protocol_vector"));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder("sha256:");
        for (byte item : digest) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static Set<String> names(JSONArray values) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.length(); index += 1) {
            if (!result.add(values.getString(index))) {
                throw new IllegalArgumentException("duplicate vector field");
            }
        }
        return result;
    }

    private static void requireEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalArgumentException(expected + " != " + actual);
    }

    private static void expectRejected(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("damaged canonical v2 frame accepted");
        } catch (IllegalArgumentException expected) {}
    }

    private interface CheckedAction { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("v1 and v2 vector paths required");
        byte[] v2Bytes = Files.readAllBytes(Paths.get(args[1]));
        ConnectionHubProtocolV2VectorsTest test = new ConnectionHubProtocolV2VectorsTest(
                new JSONObject(new String(v2Bytes, StandardCharsets.UTF_8)));
        test.validateLegacyBytes(Files.readAllBytes(Paths.get(args[0])));
        test.validateAllExamplesAndDamage();
        test.validateCanonicalFrames();
        System.out.println("Connection Hub v2 protocol vectors passed");
    }
}
