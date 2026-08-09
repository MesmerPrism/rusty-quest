package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One typed operator authority shared by wearer UI/service and the shell-UID
 * provider. Transport adapters may return credentials separately, but never
 * retain them in receipts or markers.
 */
public final class ConnectionHubOperatorController {
    public static final String RECEIPT_SCHEMA =
            "rusty.quest.connection_hub.operator_receipt.v1";
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_STATUS = "status";
    public static final String ACTION_PAIR = "pair";
    public static final String ACTION_REVOKE = "revoke";
    public static final String ACTION_FORGET = "forget";

    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            ACTION_START, ACTION_STOP, ACTION_STATUS, ACTION_PAIR, ACTION_REVOKE, ACTION_FORGET));
    private final Port port;
    private final Supplier<String> requestIds;

    public ConnectionHubOperatorController(Port port, Supplier<String> requestIds) {
        if (port == null || requestIds == null) {
            throw new IllegalArgumentException("operator port and request IDs are required");
        }
        this.port = port;
        this.requestIds = requestIds;
    }

    public Result execute(String action, JSONObject arguments) {
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("operator_action_not_registered");
        }
        JSONObject safeArguments = arguments == null ? new JSONObject() : arguments;
        validateArguments(action, safeArguments);
        String requestId = requestIds.get();
        requireToken(requestId, 8, 96, "operator_request_id_invalid");
        JSONArray transitions = new JSONArray()
                .put(transition("sent"))
                .put(transition("pending"));
        String credential = null;
        boolean applied = false;
        String effectStatus;
        String operationStatus;
        try {
            JSONObject result;
            switch (action) {
                case ACTION_START:
                    port.start();
                    result = new JSONObject();
                    break;
                case ACTION_STOP:
                    port.stop();
                    result = new JSONObject();
                    break;
                case ACTION_PAIR:
                    result = port.pair(
                            safeArguments.getString("pairing_code"),
                            safeArguments.getString("controller_identity_sha256"));
                    credential = result.optString("session", null);
                    result.remove("session");
                    break;
                case ACTION_REVOKE:
                    result = port.revoke(
                            safeArguments.getString("session"),
                            safeArguments.optString("reason", "operator_request"));
                    break;
                case ACTION_FORGET:
                    result = port.forget();
                    break;
                default:
                    result = port.status();
                    break;
            }
            JSONObject effective = port.status();
            applied = ACTION_PAIR.equals(action)
                    ? result.optBoolean("accepted", false) && credential != null
                    : effectConfirmed(action, result, effective);
            effectStatus = applied ? "confirmed" : "rejected";
            operationStatus = result.optString(
                    "status", applied ? "effective_state_confirmed" : "effective_state_rejected");
            transitions.put(transition(effectStatus));
            return new Result(
                    receipt(requestId, action, applied, effectStatus, operationStatus,
                            transitions, effective),
                    credential);
        } catch (Exception error) {
            JSONObject effective = safeStatus();
            applied = effectConfirmed(action, new JSONObject(), effective);
            effectStatus = applied ? "confirmed" :
                    ACTION_STATUS.equals(action) ? "rejected" : "outcome_unknown";
            operationStatus = applied ? "effective_state_confirmed_after_error" :
                    "operator_" + effectStatus;
            transitions.put(transition(effectStatus));
            return new Result(
                    receipt(requestId, action, applied, effectStatus, operationStatus,
                            transitions, effective),
                    null);
        }
    }

    private JSONObject safeStatus() {
        try {
            return port.status();
        } catch (Exception ignored) {
            JSONObject unavailable = new JSONObject();
            putJson(unavailable, "$schema", ConnectionHubProtocol.STATUS_SCHEMA);
            putJson(unavailable, "status", "status_unavailable");
            return unavailable;
        }
    }

    private static boolean effectConfirmed(
            String action,
            JSONObject result,
            JSONObject effective) {
        if (ACTION_START.equals(action)) {
            return "running".equals(effective.optString("desired_connection_state"));
        }
        if (ACTION_STOP.equals(action)) {
            return "stopped".equals(effective.optString("desired_connection_state"));
        }
        if (ACTION_PAIR.equals(action)) { return false; }
        if (ACTION_REVOKE.equals(action)) {
            return result.optBoolean("applied", false);
        }
        if (ACTION_FORGET.equals(action)) {
            return result.optBoolean("applied", false);
        }
        return ConnectionHubProtocol.STATUS_SCHEMA.equals(effective.optString("$schema"));
    }

    private static JSONObject receipt(
            String requestId,
            String action,
            boolean applied,
            String effectStatus,
            String operationStatus,
            JSONArray transitions,
            JSONObject effective) {
        JSONObject value = new JSONObject();
        putJson(value, "$schema", RECEIPT_SCHEMA);
        putJson(value, "request_id", requestId);
        putJson(value, "action", action);
        putJson(value, "applied", applied);
        putJson(value, "effect_status", effectStatus);
        putJson(value, "status", operationStatus);
        putJson(value, "transitions", transitions);
        putJson(value, "effective_state", sanitizeStatus(effective));
        putJson(value, "secrets_in_receipt", false);
        putJson(value, "caller_selected_identity", false);
        putJson(value, "caller_selected_capability", false);
        return value;
    }

    private static JSONObject sanitizeStatus(JSONObject value) {
        JSONObject safe = new JSONObject();
        for (String key : new String[] {
                "$schema", "listener_enabled", "desired_connection_state",
                "pairing_available", "status", "transport_classification",
                "confidentiality", "production_eligible", "active_controller_sessions",
                "origin"}) {
            if (value.has(key)) {
                putJson(safe, key, value.opt(key));
            }
        }
        return safe;
    }

    private static JSONObject transition(String state) {
        JSONObject value = new JSONObject();
        putJson(value, "state", state);
        return value;
    }

    private static void putJson(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception error) {
            throw new IllegalStateException("operator_json_encode_failed", error);
        }
    }

    private static void validateArguments(String action, JSONObject arguments) {
        if (ACTION_PAIR.equals(action)) {
            requireKeys(arguments,
                    new String[] {"pairing_code", "controller_identity_sha256"},
                    new String[0]);
            String code = arguments.optString("pairing_code");
            if (!code.matches("[0-9]{6}")) {
                throw new IllegalArgumentException("operator_pairing_code_invalid");
            }
            String identity = arguments.optString("controller_identity_sha256");
            if (!identity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("operator_controller_identity_invalid");
            }
            return;
        }
        if (ACTION_REVOKE.equals(action)) {
            requireKeys(arguments, new String[] {"session"}, new String[] {"reason"});
            requireToken(arguments.optString("session"), 32, 96, "operator_session_invalid");
            if (arguments.has("reason")) {
                requireToken(arguments.optString("reason"), 1, 64, "operator_reason_invalid");
            }
            return;
        }
        requireKeys(arguments, new String[0], new String[0]);
    }

    private static void requireKeys(
            JSONObject value,
            String[] required,
            String[] optional) {
        Set<String> allowed = new HashSet<>();
        allowed.addAll(Arrays.asList(required));
        allowed.addAll(Arrays.asList(optional));
        Set<String> actual = new HashSet<>();
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            actual.add(keys.next());
        }
        if (!actual.equals(allowed)
                && (!actual.containsAll(Arrays.asList(required))
                    || !allowed.containsAll(actual))) {
            throw new IllegalArgumentException("operator_arguments_invalid");
        }
    }

    private static void requireToken(
            String value,
            int minimum,
            int maximum,
            String error) {
        if (value == null || value.length() < minimum || value.length() > maximum
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException(error);
        }
    }

    public interface Port {
        JSONObject status() throws Exception;
        void start() throws Exception;
        void stop() throws Exception;
        JSONObject pair(String pairingCode, String controllerIdentitySha256) throws Exception;
        JSONObject revoke(String session, String reason) throws Exception;
        JSONObject forget() throws Exception;
    }

    public static final class Result {
        public final JSONObject receipt;
        public final String credential;

        Result(JSONObject receipt, String credential) {
            this.receipt = receipt;
            this.credential = credential;
        }
    }

}
