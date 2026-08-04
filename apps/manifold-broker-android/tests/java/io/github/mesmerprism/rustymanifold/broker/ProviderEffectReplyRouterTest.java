package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host lifecycle tests for bounded process-owned provider reply correlation. */
public final class ProviderEffectReplyRouterTest {
    public static void main(String[] args) throws Exception {
        responseCompletesExactlyOnce();
        timeoutRemovesAndRejectsLateReply();
        sendFailureRemovesPendingReply();
        providerCancellationIsExact();
        pendingCapacityIsBounded();
        invalidAndMismatchedBindingsFailClosed();
        System.out.println("ProviderEffectReplyRouterTest passed");
    }

    private static void responseCompletesExactlyOnce() throws Exception {
        ProviderEffectReplyRouter router = new ProviderEffectReplyRouter();
        Probe probe = new Probe();
        String binding = binding("request.response", "provider.a");
        require(router.register("request.response", "provider.a", binding, probe).accepted,
                "valid reply was not registered");
        require("provider_effect_reply_completed_observed".equals(
                        router.complete(binding, "observed", true, "{\"playing\":true}")),
                "observed reply did not complete");
        require(probe.calls.get() == 1 && probe.applied &&
                        "provider_effect_observed".equals(probe.status.get()),
                "observed completion was not exact");
        require("provider_effect_reply_late_or_unknown".equals(
                        router.complete(binding, "observed", true, "{}")),
                "duplicate reply was not rejected");
        require(probe.calls.get() == 1 && router.pendingCountForTest() == 0,
                "duplicate reply completed twice or leaked");
    }

    private static void timeoutRemovesAndRejectsLateReply() throws Exception {
        ProviderEffectReplyRouter router = new ProviderEffectReplyRouter();
        Probe probe = new Probe();
        String binding = binding("request.timeout", "provider.a");
        router.register("request.timeout", "provider.a", binding, probe);
        require("provider_effect_reply_timed_out".equals(
                        router.timeout("request.timeout", binding)),
                "timeout did not settle pending reply");
        require(probe.calls.get() == 1 &&
                        "provider_effect_receipt_timeout".equals(probe.status.get()),
                "timeout completion was not typed");
        require("provider_effect_reply_late_or_unknown".equals(
                        router.complete(binding, "observed", true, "{}")),
                "late response was not ignored");
    }

    private static void sendFailureRemovesPendingReply() throws Exception {
        ProviderEffectReplyRouter router = new ProviderEffectReplyRouter();
        Probe probe = new Probe();
        String binding = binding("request.send-failure", "provider.a");
        router.register("request.send-failure", "provider.a", binding, probe);
        require("provider_effect_reply_dispatch_failed".equals(
                        router.dispatchFailed("request.send-failure", binding)),
                "dispatch failure did not settle reply");
        require(probe.calls.get() == 1 &&
                        "provider_dispatch_failed".equals(probe.status.get()),
                "dispatch failure callback was not typed");
    }

    private static void providerCancellationIsExact() throws Exception {
        ProviderEffectReplyRouter router = new ProviderEffectReplyRouter();
        Probe first = new Probe();
        Probe second = new Probe();
        String firstBinding = binding("request.cancel.a", "provider.a");
        String secondBinding = binding("request.cancel.b", "provider.b");
        router.register("request.cancel.a", "provider.a", firstBinding, first);
        router.register("request.cancel.b", "provider.b", secondBinding, second);
        require(router.cancelProvider("provider.a", "provider_unregistered") == 1,
                "provider cancellation count was not exact");
        require(first.calls.get() == 1 && second.calls.get() == 0
                        && router.pendingCountForTest() == 1,
                "provider cancellation crossed ownership boundary");
        router.complete(secondBinding, "observed", true, "{}");
        require(second.calls.get() == 1, "uncancelled provider could not complete");
    }

    private static void pendingCapacityIsBounded() throws Exception {
        ProviderEffectReplyRouter router = new ProviderEffectReplyRouter();
        for (int index = 0; index < ProviderEffectReplyRouter.MAX_PENDING; index++) {
            String requestId = "request.capacity." + index;
            require(router.register(requestId, "provider.a",
                            binding(requestId, "provider.a"), new Probe()).accepted,
                    "reply capacity rejected an in-bound request");
        }
        ProviderEffectReplyRouter.Registration overflow = router.register(
                "request.capacity.overflow",
                "provider.a",
                binding("request.capacity.overflow", "provider.a"),
                new Probe());
        require(!overflow.accepted &&
                        "provider_effect_reply_capacity_exhausted".equals(overflow.status),
                "reply capacity was not fail-closed");
    }

    private static void invalidAndMismatchedBindingsFailClosed() throws Exception {
        ProviderEffectReplyRouter router = new ProviderEffectReplyRouter();
        Probe probe = new Probe();
        String expected = binding("request.binding", "provider.a");
        router.register("request.binding", "provider.a", expected, probe);
        require("provider_effect_reply_invalid_binding".equals(
                        router.complete("not-json", "observed", true, "{}")),
                "malformed binding was not rejected");
        require("provider_effect_reply_binding_mismatch".equals(
                        router.complete(binding("request.binding", "provider.other"),
                                "observed", true, "{}")),
                "mismatched exact binding was not rejected");
        require(probe.calls.get() == 0 && router.pendingCountForTest() == 1,
                "invalid binding consumed the authentic pending reply");
        router.complete(expected, "unexpected", false, "{}");
        require(probe.calls.get() == 1 &&
                        "provider_effect_receipt_invalid".equals(probe.status.get()),
                "invalid effect status did not fail closed");
    }

    private static String binding(String requestId, String providerInstanceId) throws Exception {
        return new JSONObject()
                .put("$schema", "rusty.quest.connection_hub.provider_effect_binding.v1")
                .put("request_id", requestId)
                .put("surface_id", "surface.test")
                .put("command", "command.test")
                .put("provider_instance_id", providerInstanceId)
                .put("transport_epoch", 1)
                .put("authorized_state_revision", 1)
                .put("authority_receipt_sha256", "sha256:test")
                .toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }

    private static final class Probe implements ProviderEffectReplyRouter.Completion {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> status = new AtomicReference<>();
        boolean applied;

        @Override public void onResult(boolean applied, String status, String stateJson) {
            this.applied = applied;
            this.status.set(status);
            calls.incrementAndGet();
        }
    }
}
