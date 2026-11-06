package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

/** Diagnostic projection only; it never changes admission or provider effects. */
final class ConnectionHubReceiptLogProjection {
    private ConnectionHubReceiptLogProjection() { }

    static boolean applied(JSONObject response) {
        JSONObject receipt = response.optJSONObject("receipt");
        if (receipt != null) return receipt.optBoolean("applied", false);
        // Flat provider receipts own their applied field, including explicit false.
        if (response.has("applied")) return response.optBoolean("applied", false);
        return response.optBoolean("accepted", false);
    }

    static String rejection(JSONObject response) {
        JSONObject receipt = response.optJSONObject("receipt");
        if (receipt != null) return receipt.optString("rejection_reason", "none");
        if (applied(response)) return "none";
        return response.optString("rejection_reason", response.optString("status", "none"));
    }
}
