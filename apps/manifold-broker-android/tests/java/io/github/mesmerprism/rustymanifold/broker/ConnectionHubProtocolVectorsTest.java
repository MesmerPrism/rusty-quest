package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exact Quest-owned wire-vector conformance, including damaged-vector rejection. */
public final class ConnectionHubProtocolVectorsTest {
    private final JSONObject root;
    private final JSONObject messages;

    private ConnectionHubProtocolVectorsTest(JSONObject root) throws Exception {
        this.root = root;
        this.messages = root.getJSONObject("messages");
        requireEquals("rusty.quest.connection_hub.protocol_vectors.v1",
                root.getString("$schema"));
        requireEquals("rusty-quest", root.getString("owner"));
        requireEquals(ConnectionHubProtocol.PROTOCOL_SCHEMA, root.getString("protocol_id"));
        JSONObject routes = root.getJSONObject("routes");
        requireEquals(ConnectionHubProtocol.STATUS_PATH, routes.getString("status"));
        requireEquals(ConnectionHubProtocol.PAIR_PATH, routes.getString("pair"));
        requireEquals(ConnectionHubProtocol.REVOKE_PATH, routes.getString("revoke"));
        requireEquals(ConnectionHubProtocol.SOCKET_PATH, routes.getString("socket"));
    }

    public static ConnectionHubProtocolVectorsTest load(String path) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        return new ConnectionHubProtocolVectorsTest(
                new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
    }

    public void validate(String messageId, JSONObject value) throws Exception {
        JSONObject vector = messages.getJSONObject(messageId);
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
        if (vector.has("type")) requireEquals(vector.getString("type"), value.getString("type"));
    }

    private void validateAllExamplesAndDamage() throws Exception {
        java.util.Iterator<String> names = messages.keys();
        while (names.hasNext()) {
            String id = names.next();
            JSONObject example = messages.getJSONObject(id).getJSONObject("example");
            validate(id, example);

            JSONObject missing = new JSONObject(example.toString());
            String firstRequired = messages.getJSONObject(id)
                    .getJSONArray("required_fields").getString(0);
            missing.remove(firstRequired);
            expectRejected(id, missing);

            JSONObject unknown = new JSONObject(example.toString()).put("unexpected", true);
            expectRejected(id, unknown);

            JSONObject wrongSchema = new JSONObject(example.toString())
                    .put("$schema", "rusty.quest.connection_hub.damaged.v1");
            expectRejected(id, wrongSchema);
        }
        validate("socket_authentication_receipt",
                ConnectionHubProtocol.socketAuthenticationReceipt(2));
    }

    private void expectRejected(String id, JSONObject damaged) throws Exception {
        try {
            validate(id, damaged);
            throw new AssertionError("damaged vector accepted: " + id);
        } catch (IllegalArgumentException expected) {
            // Exact vectors are intentionally closed to unknown or missing fields.
        }
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

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("protocol vector path required");
        ConnectionHubProtocolVectorsTest vectors = load(args[0]);
        vectors.validateAllExamplesAndDamage();
        System.out.println("Connection Hub protocol vectors passed");
    }
}
