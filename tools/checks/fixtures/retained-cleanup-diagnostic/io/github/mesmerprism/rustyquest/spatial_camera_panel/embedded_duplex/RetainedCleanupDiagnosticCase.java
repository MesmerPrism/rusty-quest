package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.media.MediaOwnerAction;
import org.json.JSONObject;

/** Actual diagnostic holder/classifiers; no owner execution or terminal claim. */
public final class RetainedCleanupDiagnosticCase {
    private RetainedCleanupDiagnosticCase() { }
    private static int checks;
    private static final class UnavailableCause extends Exception {
        private static final long serialVersionUID = 1L;
        @Override public synchronized Throwable getCause() { throw new IllegalStateException("private-accessor"); }
    }
    private static void require(boolean accepted, String name) {
        if (!accepted) throw new AssertionError(name);
        checks++;
    }
    private static MediaOwnerAction ticket(String action) throws Exception {
        JSONObject value = new JSONObject().put("$schema", MediaOwnerAction.SCHEMA)
                .put("capability", "private-capability-秘密").put("executor_generation", 7)
                .put("action_id", "private-action").put("authority_epoch_id", "private-epoch")
                .put("media_acceptance_authority_revision", 3).put("expected_runtime_revision", 2)
                .put("client_id", "private-client").put("lease_id", "private-lease")
                .put("sequence", 1).put("operation", "start".equals(action) ? "start" : "stop").put("owner_kind", "sink")
                .put("action_kind", action).put("owner_id", "private-owner")
                .put("provider_kind", "private-provider").put("resource_id", "private-resource");
        return MediaOwnerAction.parse(value.toString());
    }
    public static void main(String[] args) throws Exception {
        EmbeddedDuplexPlatform.FailureState retained = new EmbeddedDuplexPlatform.FailureState();
        Exception original = new IllegalStateException("private-json-秘密" + new String(new char[100000]),
                new SecurityException("private-secret"));
        retained.record(EmbeddedDuplexPlatform.OwnerStage.PROVIDER_EXECUTION, ticket("stop"), "NONE", original);
        String bytes = retained.document();
        JSONObject first = new JSONObject(bytes);
        require("PROVIDER_EXECUTION".equals(first.getString("stage")), "Stop provider stage");
        require("STOP".equals(first.getString("action")), "Stop typed action");
        require("sink".equals(first.getString("owner")), "closed owner kind");
        require("SECURITY".equals(first.getString("cause")), "nested cause");
        require("OTHER".equals(first.getString("provider_reason")), "dynamic text unclassified");
        require(bytes.length() < 512 && !bytes.contains("private") && !bytes.contains("秘密"), "privacy and bound");
        retained.record(EmbeddedDuplexPlatform.OwnerStage.RECEIPT_VERIFICATION, ticket("cleanup"), "NONE",
                new IllegalArgumentException("later-private-error"));
        require(bytes.equals(retained.document()), "immutable first fault");
        EmbeddedDuplexPlatform.FailureState start = new EmbeddedDuplexPlatform.FailureState();
        start.record(EmbeddedDuplexPlatform.OwnerStage.INCOMING_ARM_VERIFICATION, ticket("start"), "NONE", original);
        String startFailure = start.document();
        start.record(EmbeddedDuplexPlatform.OwnerStage.PROVIDER_EXECUTION, ticket("stop"), "NONE", original);
        require(startFailure.equals(start.document()) && "START".equals(new JSONObject(startFailure).getString("action")),
                "original Start fault survives cleanup fault");
        EmbeddedDuplexPlatform.FailureState verification = new EmbeddedDuplexPlatform.FailureState();
        verification.record(EmbeddedDuplexPlatform.OwnerStage.RECEIPT_VERIFICATION, ticket("cleanup"), "NONE", original);
        JSONObject verified = new JSONObject(verification.document());
        require("RECEIPT_VERIFICATION".equals(verified.getString("stage")), "readback stage");
        require("CLEANUP".equals(verified.getString("action")) && "NONE".equals(verified.getString("cause")), "no invented provider cause");
        EmbeddedDuplexPlatform.FailureState beforeTicket = new EmbeddedDuplexPlatform.FailureState();
        beforeTicket.record(EmbeddedDuplexPlatform.OwnerStage.CALLBACK_FENCE, null, "NONE", original);
        JSONObject fence = new JSONObject(beforeTicket.document());
        require("BEFORE_TICKET".equals(fence.getString("action")) && "NONE".equals(fence.getString("owner")), "pre-ticket bounded");
        require(EmbeddedDuplexPlatform.providerReason(new IllegalStateException("wrapper",
                new IllegalStateException("ProviderBusy"))) == EmbeddedDuplexPlatform.ProviderReason.PROVIDER_BUSY, "allowlisted nested reason");
        Throwable deep = new IllegalArgumentException("private-deep");
        for (int i = 0; i < 8; i++) deep = new Exception("private-wrapper", deep);
        require("OTHER".equals(EmbeddedDuplexPlatform.failureCategory(deep)), "eight-cause traversal bound");
        require("STATE".equals(EmbeddedDuplexPlatform.failureCategory(new IllegalStateException("private"))), "state cause");
        require("ARGUMENT".equals(EmbeddedDuplexPlatform.failureCategory(new IllegalArgumentException("private"))), "argument cause");
        require(!first.has("terminal") && !first.has("accepted") && !first.has("completed"), "no acceptance assertion");
        EmbeddedDuplexPlatform.FailureState unavailable = new EmbeddedDuplexPlatform.FailureState();
        unavailable.record(EmbeddedDuplexPlatform.OwnerStage.PROVIDER_EXECUTION, ticket("stop"), "NONE", new UnavailableCause());
        JSONObject empty = new JSONObject(unavailable.document());
        require("NONE".equals(empty.getString("stage")) && "NONE".equals(empty.getString("action")),
                "throwing cause accessor is contained without partial fault publication");
        unavailable.record(EmbeddedDuplexPlatform.OwnerStage.RECEIPT_VERIFICATION, ticket("cleanup"), "NONE", original);
        require("RECEIPT_VERIFICATION".equals(new JSONObject(unavailable.document()).getString("stage")),
                "unavailable diagnostic does not manufacture first fault");
        System.out.println("PASS production retained-cleanup diagnostics checks=" + checks);
    }
}
