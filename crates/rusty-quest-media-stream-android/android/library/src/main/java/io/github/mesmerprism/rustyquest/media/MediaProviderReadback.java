package io.github.mesmerprism.rustyquest.media;

import org.json.JSONObject;

/** Android-owned evidence for one platform effect. */
public final class MediaProviderReadback {
    public static final String SCHEMA = "rusty.quest.android.media.readback.v1";
    private final MediaOwnerAction action;
    private final String providerHandleId;
    private final long providerStateRevision;
    private final String observedState;
    private final String receiptId;

    public MediaProviderReadback(MediaOwnerAction action, String providerHandleId,
            long providerStateRevision, String observedState, String receiptId) {
        if (action == null) throw new NullPointerException("action");
        this.action = action;
        this.providerHandleId = require(providerHandleId, "providerHandleId");
        if (providerStateRevision <= 0) throw new IllegalArgumentException("providerStateRevision");
        this.providerStateRevision = providerStateRevision;
        this.observedState = require(observedState, "observedState");
        this.receiptId = require(receiptId, "receiptId");
    }
    private static String require(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > 4096) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }
    public MediaOwnerAction action() { return action; }
    public String providerHandleId() { return providerHandleId; }
    public long providerStateRevision() { return providerStateRevision; }
    public String observedState() { return observedState; }
    public String receiptId() { return receiptId; }
    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("$schema", SCHEMA);
            action.copyBindingsTo(json);
            json.put("provider_handle_id", providerHandleId);
            json.put("provider_state_revision", providerStateRevision);
            json.put("observed_state", observedState);
            json.put("receipt_id", receiptId);
            return json.toString();
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("readback JSON encoding failed", impossible);
        }
    }
}
