package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

public final class ConnectionHubReceiptLogProjectionTest {
    private static int cases;

    private static void check(String json, boolean applied, String rejection) throws Exception {
        JSONObject response = new JSONObject(json);
        String original = response.toString();
        if (ConnectionHubReceiptLogProjection.applied(response) != applied
                || !rejection.equals(ConnectionHubReceiptLogProjection.rejection(response))) {
            throw new AssertionError("Unexpected receipt log projection: " + original);
        }
        if (!original.equals(response.toString())) throw new AssertionError("Receipt mutated");
        cases++;
    }

    public static void main(String[] arguments) throws Exception {
        check("{\"applied\":true,\"status\":\"surface_state_updated\"}", true, "none");
        check("{\"applied\":true,\"status\":\"surface_unregistered\"}", true, "none");
        check("{\"applied\":false,\"status\":\"surface_not_registered\"}", false, "surface_not_registered");
        check("{\"applied\":false,\"accepted\":true,\"status\":\"surface_not_registered\"}", false, "surface_not_registered");
        check("{\"receipt\":{\"applied\":true},\"applied\":false}", true, "none");
        check("{\"receipt\":{\"applied\":false,\"rejection_reason\":\"token_expired\"},\"applied\":true,\"accepted\":true}", false, "token_expired");
        check("{\"accepted\":true,\"status\":\"compatible\"}", true, "none");
        check("{\"accepted\":false,\"rejection_reason\":\"identity_mismatch\",\"status\":\"rejected\"}", false, "identity_mismatch");
        check("{}", false, "none");
        System.out.println("Receipt log projection passed " + cases + " cases");
    }
}
