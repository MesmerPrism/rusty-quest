package io.github.mesmerprism.rustyquest.media;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Exact native-minted execution ticket. Callers cannot construct trusted completion evidence. */
public final class MediaOwnerAction {
    public static final String SCHEMA = "rusty.quest.android.media.execution-ticket.v1";

    private final String capability;
    private final long executorGeneration;
    private final String actionId;
    private final String authorityEpochId;
    private final long acceptanceRevision;
    private final long expectedRuntimeRevision;
    private final String clientId;
    private final String leaseId;
    private final int sequence;
    private final String operation;
    private final String ownerKind;
    private final String actionKind;
    private final String ownerId;
    private final String providerKind;
    private final String resourceId;

    private MediaOwnerAction(JSONObject json) {
        requireSchema(json);
        rejectUnknownFields(json);
        capability = required(json, "capability");
        executorGeneration = positive(json, "executor_generation");
        actionId = required(json, "action_id");
        authorityEpochId = required(json, "authority_epoch_id");
        acceptanceRevision = nonNegative(json, "media_acceptance_authority_revision");
        expectedRuntimeRevision = nonNegative(json, "expected_runtime_revision");
        clientId = required(json, "client_id");
        leaseId = required(json, "lease_id");
        long parsedSequence = nonNegative(json, "sequence");
        if (parsedSequence > Integer.MAX_VALUE) throw new IllegalArgumentException("sequence overflow");
        sequence = (int) parsedSequence;
        operation = required(json, "operation");
        ownerKind = required(json, "owner_kind");
        actionKind = required(json, "action_kind");
        ownerId = required(json, "owner_id");
        providerKind = required(json, "provider_kind");
        resourceId = required(json, "resource_id");
        if (!("start".equals(operation) || "stop".equals(operation))) {
            throw new IllegalArgumentException("invalid operation");
        }
        Set<String> owners = new HashSet<>(Arrays.asList(
                "source", "processor", "route", "socket", "codec", "sink", "cleanup"));
        if (!owners.contains(ownerKind)) throw new IllegalArgumentException("invalid owner kind");
        Set<String> actions = new HashSet<>(Arrays.asList(
                "arm_receiver", "arm_cleanup", "start", "stop", "cleanup"));
        if (!actions.contains(actionKind)) throw new IllegalArgumentException("invalid action kind");
    }

    public static MediaOwnerAction parse(String json) {
        if (json == null || json.length() > 64 * 1024) {
            throw new IllegalArgumentException("execution ticket missing or oversized");
        }
        try {
            return new MediaOwnerAction(new JSONObject(json));
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("invalid execution ticket JSON", invalid);
        }
    }

    private static void requireSchema(JSONObject json) {
        if (!SCHEMA.equals(json.optString("$schema", ""))) {
            throw new IllegalArgumentException("execution ticket schema mismatch");
        }
    }
    private static void rejectUnknownFields(JSONObject json) {
        Set<String> allowed = new HashSet<>(Arrays.asList("$schema", "capability",
                "executor_generation", "action_id", "authority_epoch_id",
                "media_acceptance_authority_revision", "expected_runtime_revision",
                "client_id", "lease_id", "sequence", "operation", "owner_kind",
                "action_kind", "owner_id", "provider_kind", "resource_id"));
        Iterator<String> names = json.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) throw new IllegalArgumentException("unknown ticket field " + name);
        }
    }
    private static String required(JSONObject json, String name) {
        String value = json.optString(name, "");
        if (value.isEmpty() || value.length() > 4096) {
            throw new IllegalArgumentException("missing or oversized " + name);
        }
        return value;
    }
    private static long positive(JSONObject json, String name) {
        long value = nonNegative(json, name);
        if (value == 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    private static long nonNegative(JSONObject json, String name) {
        if (!json.has(name)) throw new IllegalArgumentException("missing " + name);
        long value = json.optLong(name, -1L);
        if (value < 0) throw new IllegalArgumentException("invalid " + name);
        return value;
    }

    public String capability() { return capability; }
    public long executorGeneration() { return executorGeneration; }
    public String actionId() { return actionId; }
    public String authorityEpochId() { return authorityEpochId; }
    public long acceptanceRevision() { return acceptanceRevision; }
    public long expectedRuntimeRevision() { return expectedRuntimeRevision; }
    public String clientId() { return clientId; }
    public String leaseId() { return leaseId; }
    public int sequence() { return sequence; }
    public String operation() { return operation; }
    public String ownerKind() { return ownerKind; }
    public String actionKind() { return actionKind; }
    public String ownerId() { return ownerId; }
    public String providerKind() { return providerKind; }
    public String resourceId() { return resourceId; }
    public String executionKey(boolean compensate) {
        return capability + "\u0000" + sequence + "\u0000" + (compensate ? "c" : "e");
    }
    public String bindingKey() {
        return MediaProductBinding.key(ownerKind, ownerId, providerKind, resourceId);
    }

    void copyBindingsTo(JSONObject json) throws JSONException {
        json.put("capability", capability);
        json.put("executor_generation", executorGeneration);
        json.put("action_id", actionId);
        json.put("authority_epoch_id", authorityEpochId);
        json.put("media_acceptance_authority_revision", acceptanceRevision);
        json.put("expected_runtime_revision", expectedRuntimeRevision);
        json.put("client_id", clientId);
        json.put("lease_id", leaseId);
        json.put("sequence", sequence);
        json.put("operation", operation);
        json.put("owner_kind", ownerKind);
        json.put("action_kind", actionKind);
        json.put("owner_id", ownerId);
        json.put("provider_kind", providerKind);
        json.put("resource_id", resourceId);
    }

    boolean matches(JSONObject json) {
        try {
            return capability.equals(json.getString("capability"))
                    && executorGeneration == json.getLong("executor_generation")
                    && actionId.equals(json.getString("action_id"))
                    && authorityEpochId.equals(json.getString("authority_epoch_id"))
                    && acceptanceRevision == json.getLong("media_acceptance_authority_revision")
                    && expectedRuntimeRevision == json.getLong("expected_runtime_revision")
                    && clientId.equals(json.getString("client_id"))
                    && leaseId.equals(json.getString("lease_id"))
                    && sequence == json.getInt("sequence")
                    && operation.equals(json.getString("operation"))
                    && ownerKind.equals(json.getString("owner_kind"))
                    && actionKind.equals(json.getString("action_kind"))
                    && ownerId.equals(json.getString("owner_id"))
                    && providerKind.equals(json.getString("provider_kind"))
                    && resourceId.equals(json.getString("resource_id"));
        } catch (Exception invalid) {
            return false;
        }
    }
}
