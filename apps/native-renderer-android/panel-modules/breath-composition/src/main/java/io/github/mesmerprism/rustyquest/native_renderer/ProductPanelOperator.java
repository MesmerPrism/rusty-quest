package io.github.mesmerprism.rustyquest.native_renderer;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Typed presentation adapter. Original owners retain validation, policy and effective state. */
final class ProductPanelOperator {
    static final String COMMAND_SCHEMA = "rusty.quest.product_panel.command.v1";
    static final String RECEIPT_SCHEMA = "rusty.quest.product_panel.receipt.v1";
    private static final int MAX_REQUESTS = 256;
    private static WeakReference<Registration> current = new WeakReference<Registration>(null);

    interface Owner {
        boolean available();
        JSONObject observe() throws Exception;
        JSONObject invoke(String action, JSONObject parameters) throws Exception;
    }

    static synchronized AutoCloseable register(Owner owner) {
        if (owner == null) throw new IllegalArgumentException("owner-required");
        Registration registration = new Registration(owner);
        current = new WeakReference<Registration>(registration);
        return registration;
    }

    private static final class Registration implements AutoCloseable {
        final Owner owner;
        final Thread thread = Thread.currentThread();
        final String token = UUID.randomUUID().toString().replace("-", "");
        final LinkedHashMap<String, Entry> requests = new LinkedHashMap<String, Entry>();
        boolean closed;
        Registration(Owner owner) { this.owner = owner; }
        public void close() {
            synchronized (ProductPanelOperator.class) {
                closed = true;
                if (current.get() == this) current = new WeakReference<Registration>(null);
            }
        }
    }
    private static final class Entry {
        final String raw;
        String receipt;
        Entry(String raw) { this.raw = raw; }
    }

    static String dispatch(String raw) {
        Registration registration;
        synchronized (ProductPanelOperator.class) { registration = current.get(); }
        try {
            if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384)
                return reject("request-size", "");
            JSONObject command = new JSONObject(raw);
            if (!COMMAND_SCHEMA.equals(command.optString("schema"))) return reject("schema", "");
            String operation = command.optString("operation");
            if (!Arrays.asList("status", "result", "invoke").contains(operation))
                return reject("operation", "");
            if (registration == null || registration.closed) return reject("panel-owner-unavailable", "");
            if (Thread.currentThread() != registration.thread) return reject("owner-thread-required", "");
            if ("status".equals(operation)) {
                keys(command, "schema", "operation");
                return envelope(registration, "current", "", "", observe(registration)).toString();
            }
            Object ownerToken = command.opt("owner_token"), request = command.opt("request_id");
            if (!(ownerToken instanceof String) || !registration.token.equals(ownerToken))
                return reject("panel-owner-replaced", "");
            if (!(request instanceof String) || !((String)request).matches("[0-9a-f]{16}"))
                return reject("request-id", "");
            String requestId = (String)request;
            Entry previous = registration.requests.get(requestId);
            if ("result".equals(operation)) {
                keys(command, "schema", "operation", "owner_token", "request_id");
                if (previous == null) return reject("request-not-observed", requestId);
                return observedResult(registration, previous, requestId);
            }
            keys(command, "schema", "operation", "owner_token", "request_id", "action", "parameters");
            if (!(command.opt("action") instanceof String) || !(command.opt("parameters") instanceof JSONObject))
                return reject("action-parameters", requestId);
            String action = command.getString("action");
            JSONObject parameters = command.getJSONObject("parameters");
            validate(action, parameters);
            if (previous != null) {
                if (!previous.raw.equals(raw)) return reject("request-id-conflict-no-replay", requestId);
                return observedResult(registration, previous, requestId);
            }
            if (!registration.owner.available()) return reject("panel-owner-not-presented", requestId);
            if (registration.requests.size() >= MAX_REQUESTS) return reject("request-capacity-exhausted", requestId);
            Entry entry = new Entry(raw);
            registration.requests.put(requestId, entry); // Record before any callback/re-entrant effect.
            try {
                JSONObject result = registration.owner.invoke(action, parameters);
                entry.receipt = envelope(registration, "dispatched", requestId, action, null)
                    .put("result", result == null ? JSONObject.NULL : result).toString();
            } catch (Exception unknown) {
                entry.receipt = envelope(registration, "unknown", requestId, action, null)
                    .put("reason", "owner-call-failed-no-replay").toString();
            }
            return observedResult(registration, entry, requestId);
        } catch (Exception invalid) { return reject("invalid-command", ""); }
    }

    private static JSONObject observe(Registration registration) throws Exception {
        if (registration.closed || !registration.owner.available())
            return new JSONObject().put("status", "unavailable");
        JSONObject result = registration.owner.observe();
        return result == null ? new JSONObject().put("status", "unavailable") : result;
    }
    private static String observedResult(Registration registration, Entry entry, String id) throws Exception {
        JSONObject result = entry.receipt == null
            ? envelope(registration, "pending", id, "", null) : new JSONObject(entry.receipt);
        // Fresh owner observation is separate from cached dispatch; it never reissues an action.
        try { result.put("observation", observe(registration)); }
        catch (Exception unavailable) { result.put("observation", new JSONObject().put("status", "unavailable")); }
        return result.toString();
    }
    private static JSONObject envelope(Registration registration, String status, String id,
            String action, JSONObject observation) throws Exception {
        JSONObject result = new JSONObject().put("schema", RECEIPT_SCHEMA).put("status", status)
            .put("owner_token", registration.token).put("request_id", id).put("action", action)
            .put("observed_at_unix_ms", System.currentTimeMillis());
        if (observation != null) result.put("observation", observation);
        return result;
    }
    private static String reject(String reason, String id) {
        try { return new JSONObject().put("schema", RECEIPT_SCHEMA).put("status", "rejected")
            .put("reason", reason).put("request_id", id).toString(); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void keys(JSONObject object, String... expected) {
        Set<String> names = new HashSet<String>(Arrays.asList(expected));
        if (object.length() != names.size()) throw new IllegalArgumentException("closed-shape");
        for (Iterator<String> it = object.keys(); it.hasNext();) if (!names.contains(it.next()))
            throw new IllegalArgumentException("closed-shape");
    }
    private static String text(JSONObject object, String key, int limit) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length() > limit)
            throw new IllegalArgumentException("text");
        return (String)value;
    }
    private static void bool(JSONObject object, String key) {
        if (!(object.opt(key) instanceof Boolean)) throw new IllegalArgumentException("boolean");
    }
    private static long integer(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("integer");
        return ((Number)value).longValue();
    }
    private static void validate(String action, JSONObject p) {
        switch (action) {
            case "panel-visible": keys(p,"visible"); bool(p,"visible"); break;
            case "navigate": {
                keys(p, "surface", "page");
                String surface = text(p, "surface", 24), page = text(p, "page", 24);
                boolean allowed = "experimenter".equals(surface)
                    ? Arrays.asList("prepare", "controls", "condition", "session", "polar").contains(page)
                    : "developer".equals(surface) && Arrays.asList("home", "particles", "breath", "polar", "lsl", "status").contains(page);
                if (!allowed) throw new IllegalArgumentException("page");
                break;
            }
            case "scroll": {
                keys(p, "delta_y"); long delta = integer(p,"delta_y");
                if (delta < -8192L || delta > 8192L) throw new IllegalArgumentException("scroll"); break;
            }
            case "particle-patch":
                keys(p,"patch");
                if (!(p.opt("patch") instanceof JSONObject) || ((JSONObject)p.opt("patch")).length() == 0)
                    throw new IllegalArgumentException("patch");
                // The existing particle owner validates field keys, values, bounds and revision.
                break;
            case "ble-enabled": case "ble-open-control": keys(p,"enabled"); bool(p,"enabled"); break;
            case "network-enroll": keys(p,"trusted_lan","consent"); bool(p,"trusted_lan"); bool(p,"consent");
                if (!p.optBoolean("consent",false)) throw new IllegalArgumentException("consent"); break;
            case "network-direct-start": keys(p,"route");
                if (!Arrays.asList("hotspot","wifi_direct").contains(text(p,"route",24))) throw new IllegalArgumentException("route"); break;
            case "network-relay-start": keys(p,"session_id","token","expiry"); text(p,"session_id",256); text(p,"token",4096);
                if(integer(p,"expiry") <= 0L) throw new IllegalArgumentException("expiry"); break;
            case "network-join-details": case "network-stop": case "network-revoke": keys(p); break;
            default: throw new IllegalArgumentException("unsupported-action");
        }
    }
    private ProductPanelOperator() { }
}
