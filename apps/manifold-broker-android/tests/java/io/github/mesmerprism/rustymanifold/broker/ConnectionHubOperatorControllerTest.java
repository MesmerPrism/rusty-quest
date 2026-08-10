package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only typed operator parity, redaction, and effective-state tests. */
public final class ConnectionHubOperatorControllerTest {
    private static final String SESSION = repeat("s", 43);
    public static void main(String[] args) {
        testLifecycleAndStatusConfirmation();
        testPairAndRevokeRedactCredentials();
        testTypedArgumentsFailClosed();
        testStartWithoutForegroundReadinessIsRejected();
        testAmbiguousMutationIsNotRetriedOrClaimed();
        testEffectiveReadbackCanConfirmAfterTransportFailure();
        System.out.println("Connection Hub operator controller tests passed");
    }

    private static void testLifecycleAndStatusConfirmation() {
        FakePort port = new FakePort();
        ConnectionHubOperatorController controller = controller(port);

        JSONObject start = controller.execute("start", new JSONObject()).receipt;
        require(start.getBoolean("applied"), "start was not confirmed");
        require("running".equals(
                start.getJSONObject("effective_state")
                        .getString("desired_connection_state")),
                "start effective state mismatch");
        requireTransitions(start, "sent", "pending", "confirmed");

        JSONObject status = controller.execute("status", new JSONObject()).receipt;
        require(status.getBoolean("applied"), "status was not confirmed");

        JSONObject stop = controller.execute("stop", new JSONObject()).receipt;
        require(stop.getBoolean("applied"), "stop was not confirmed");
        requireTransitions(stop, "sent", "pending", "confirmed");

        JSONObject forget = controller.execute("forget", new JSONObject()).receipt;
        require(forget.getBoolean("applied"), "forget was not confirmed");
        require(port.forgetCalls == 1, "forget did not route exactly once");
    }

    private static void testPairAndRevokeRedactCredentials() {
        FakePort port = new FakePort();
        ConnectionHubOperatorController controller = controller(port);
        JSONObject pairArgs = new JSONObject()
                .put("pairing_code", "123456")
                .put("controller_identity_sha256", repeat("a", 64));

        ConnectionHubOperatorController.Result paired = controller.execute("pair", pairArgs);
        require(paired.receipt.getBoolean("applied"), "pair was not confirmed");
        require(SESSION.equals(paired.credential), "pair credential missing");
        String receipt = paired.receipt.toString();
        require(!receipt.contains("123456"), "pairing code leaked into receipt");
        require(!receipt.contains(SESSION), "session leaked into receipt");
        require(paired.receipt.getBoolean("secrets_in_receipt") == false,
                "redaction marker mismatch");

        JSONObject revokeArgs = new JSONObject()
                .put("session", SESSION)
                .put("reason", "operator_request");
        JSONObject revoked = controller.execute("revoke", revokeArgs).receipt;
        require(revoked.getBoolean("applied"), "revoke was not confirmed");
        require(!revoked.toString().contains(SESSION), "revoke secret leaked");
    }

    private static void testTypedArgumentsFailClosed() {
        ConnectionHubOperatorController controller = controller(new FakePort());
        expectFailure(() -> controller.execute("start", new JSONObject().put("path", "/tmp")));
        expectFailure(() -> controller.execute("pair",
                new JSONObject().put("pairing_code", "123456")));
        expectFailure(() -> controller.execute("revoke",
                new JSONObject().put("session", "short")));
        expectFailure(() -> controller.execute("force-rollover", new JSONObject()));
    }

    private static void testAmbiguousMutationIsNotRetriedOrClaimed() {
        FakePort port = new FakePort();
        port.startThrowsBeforeState = true;
        JSONObject receipt = controller(port).execute("start", new JSONObject()).receipt;
        require(!receipt.getBoolean("applied"), "ambiguous start was claimed");
        require("outcome_unknown".equals(receipt.getString("effect_status")),
                "ambiguous start status mismatch");
        require(port.startCalls == 1, "ambiguous start was retried");
        requireTransitions(receipt, "sent", "pending", "outcome_unknown");
    }

    private static void testStartWithoutForegroundReadinessIsRejected() {
        FakePort port = new FakePort();
        port.startLeavesStopped = true;
        JSONObject receipt = controller(port).execute("start", new JSONObject()).receipt;
        require(!receipt.getBoolean("applied"), "not-ready start was claimed");
        require("rejected".equals(receipt.getString("effect_status")),
                "not-ready start terminal status mismatch");
        require(port.startCalls == 1, "not-ready start was retried");
        requireTransitions(receipt, "sent", "pending", "rejected");
    }

    private static void testEffectiveReadbackCanConfirmAfterTransportFailure() {
        FakePort port = new FakePort();
        port.startThrowsAfterState = true;
        JSONObject receipt = controller(port).execute("start", new JSONObject()).receipt;
        require(receipt.getBoolean("applied"), "effective state did not confirm start");
        require("confirmed".equals(receipt.getString("effect_status")),
                "post-error confirmation status mismatch");
        require(port.startCalls == 1, "confirmed start was retried");
    }

    private static ConnectionHubOperatorController controller(FakePort port) {
        return new ConnectionHubOperatorController(port, () -> "operator.test-0001");
    }

    private static void requireTransitions(JSONObject receipt, String... expected) {
        JSONArray values = receipt.getJSONArray("transitions");
        require(values.length() == expected.length, "transition count mismatch");
        for (int index = 0; index < expected.length; index++) {
            require(expected[index].equals(values.getJSONObject(index).getString("state")),
                    "transition mismatch at " + index);
        }
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakePort implements ConnectionHubOperatorController.Port {
        boolean running;
        boolean startThrowsBeforeState;
        boolean startThrowsAfterState;
        boolean startLeavesStopped;
        int startCalls;
        int forgetCalls;

        @Override public JSONObject status() {
            return new JSONObject()
                    .put("$schema", ConnectionHubProtocol.STATUS_SCHEMA)
                    .put("listener_enabled", running)
                    .put("desired_connection_state", running ? "running" : "stopped")
                    .put("pairing_available", running)
                    .put("status", running ? "running" : "stopped")
                    .put("transport_classification", "trusted_lan_experimental")
                    .put("confidentiality", "none")
                    .put("production_eligible", false);
        }

        @Override public void start() throws Exception {
            startCalls += 1;
            if (startThrowsBeforeState) throw new Exception("before-state");
            if (startLeavesStopped) return;
            running = true;
            if (startThrowsAfterState) throw new Exception("after-state");
        }

        @Override public void stop() {
            running = false;
        }

        @Override public JSONObject pair(String code, String identity) {
            return new JSONObject()
                    .put("accepted", true)
                    .put("status", "paired")
                    .put("session", SESSION);
        }

        @Override public JSONObject revoke(String session, String reason) {
            return new JSONObject().put("applied", true).put("status", "applied");
        }

        @Override public JSONObject forget() {
            forgetCalls += 1;
            return new JSONObject().put("applied", true).put("status", "applied");
        }
    }
}
