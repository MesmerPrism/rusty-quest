package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Real transport/protocol harness; modeled owner deliberately provides no device acceptance. */
public final class ExperimentSessionRelayHarness {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--expiry-self-test".equals(args[0])) { expirySelfTest(); return; }
        JSONObject c = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), java.nio.charset.StandardCharsets.UTF_8));
        String device = c.getString("device");
        ExperimentSessionGatewayProtocol protocol = new ExperimentSessionGatewayProtocol(
            c.getString("controller"), c.getString("gateway_key"), device, 123L,
            (action, payload) -> {
                if ("status".equals(action)) return new JSONObject().put("status", new JSONObject().put("s", "paused")).put("owner", "modeled");
                if ("command".equals(action)) return new JSONObject().put("receipt", new JSONObject()
                    .put("action", payload.getString("action")).put("outcome", "pending").put("request_id", payload.getString("request_id")));
                return new JSONObject().put("receipt", new JSONObject().put("outcome", "outcome_unknown"));
            });
        ExperimentSessionRelayClient client = new ExperimentSessionRelayClient(
            new ExperimentSessionRelayClient.Config(c.getString("origin"), c.getString("origin"),
                c.getString("session"), c.getString("device_token"), c.getLong("expires_at_ms")),
            new ExperimentSessionRelayClient.Endpoint() {
                public JSONObject identity() throws Exception {
                    return new JSONObject().put("v", 1).put("device", device).put("epoch", "123").put("enabled", true);
                }
                public JSONObject exchange(JSONObject request) throws Exception { return protocol.exchange(request); }
            });
        try { client.start(); System.out.println("relay_client_started"); System.out.flush(); System.in.read(); }
        finally { client.close(); protocol.retire(); }
    }
    private static void expirySelfTest() throws Exception {
        ExperimentSessionRelayClient.Config config = new ExperimentSessionRelayClient.Config(
            "https://relay.example", "https://relay.example", "abcdefghijklmnop", "abcdefghijklmnopqrstuvwxyz012345", System.currentTimeMillis() + 250L);
        ExperimentSessionRelayClient client = new ExperimentSessionRelayClient(config, new ExperimentSessionRelayClient.Endpoint() {
            public JSONObject identity() { throw new AssertionError("no_network_in_expiry_self_test"); }
            public JSONObject exchange(JSONObject request) { throw new AssertionError("no_commands_in_expiry_self_test"); }
        });
        Thread.sleep(300L);
        if (!"expired".equals(client.snapshot().getString("state"))) throw new AssertionError("expired_snapshot_required");
        client.close();
        boolean rejected = false;
        try { new ExperimentSessionRelayClient.Config("https://relay.example", "https://other.example", "abcdefghijklmnop", "abcdefghijklmnopqrstuvwxyz012345", System.currentTimeMillis()+1000L); }
        catch (IllegalArgumentException expected) { rejected=true; }
        if (!rejected) throw new AssertionError("origin_binding_required");
        rejected=false;
        try { new ExperimentSessionRelayClient.Config("http://relay.example", "http://relay.example", "abcdefghijklmnop", "abcdefghijklmnopqrstuvwxyz012345", System.currentTimeMillis()+1000L); }
        catch (IllegalArgumentException expected) { rejected=true; }
        if (!rejected) throw new AssertionError("https_required");
        System.out.println("relay_expiry_and_origin_self_test_passed");
    }
}
