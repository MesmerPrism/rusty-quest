package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, process-owned correlation for asynchronous provider effect receipts. */
final class ProviderEffectReplyRouter {
    static final int MAX_PENDING = 32;
    private static final int MAX_EFFECT_BINDING_CHARS = 4096;
    private static final int MAX_REQUEST_ID_CHARS = 192;

    interface Completion {
        void onResult(boolean applied, String status, String stateJson);
    }

    static final class Registration {
        final boolean accepted;
        final String status;

        Registration(boolean accepted, String status) {
            this.accepted = accepted;
            this.status = status;
        }
    }

    private static final class Pending {
        final String requestId;
        final String providerInstanceId;
        final String expectedEffectBinding;
        final Completion completion;

        Pending(
                String requestId,
                String providerInstanceId,
                String expectedEffectBinding,
                Completion completion) {
            this.requestId = requestId;
            this.providerInstanceId = providerInstanceId;
            this.expectedEffectBinding = expectedEffectBinding;
            this.completion = completion;
        }
    }

    private final Map<String, Pending> pendingByRequestId = new LinkedHashMap<>();

    synchronized Registration register(
            String requestId,
            String providerInstanceId,
            String expectedEffectBinding,
            Completion completion) {
        if (!bounded(requestId, MAX_REQUEST_ID_CHARS)
                || !bounded(providerInstanceId, MAX_REQUEST_ID_CHARS)
                || !bounded(expectedEffectBinding, MAX_EFFECT_BINDING_CHARS)
                || completion == null) {
            return new Registration(false, "provider_effect_reply_registration_invalid");
        }
        if (pendingByRequestId.containsKey(requestId)) {
            return new Registration(false, "provider_effect_reply_duplicate_request");
        }
        if (pendingByRequestId.size() >= MAX_PENDING) {
            return new Registration(false, "provider_effect_reply_capacity_exhausted");
        }
        pendingByRequestId.put(
                requestId,
                new Pending(requestId, providerInstanceId, expectedEffectBinding, completion));
        return new Registration(true, "provider_effect_reply_registered");
    }

    String complete(
            String returnedEffectBinding,
            String effectStatus,
            boolean providerApplied,
            String stateJson) {
        final String requestId;
        try {
            if (!bounded(returnedEffectBinding, MAX_EFFECT_BINDING_CHARS)) {
                return "provider_effect_reply_invalid_binding";
            }
            JSONObject binding = new JSONObject(returnedEffectBinding);
            if (!"rusty.quest.connection_hub.provider_effect_binding.v1"
                            .equals(binding.optString("$schema", ""))) {
                return "provider_effect_reply_invalid_binding";
            }
            requestId = binding.optString("request_id", "");
            if (!bounded(requestId, MAX_REQUEST_ID_CHARS)) {
                return "provider_effect_reply_invalid_binding";
            }
        } catch (Exception malformed) {
            return "provider_effect_reply_invalid_binding";
        }

        final Pending pending;
        synchronized (this) {
            pending = pendingByRequestId.get(requestId);
            if (pending == null) { return "provider_effect_reply_late_or_unknown"; }
            if (!pending.expectedEffectBinding.equals(returnedEffectBinding)) {
                return "provider_effect_reply_binding_mismatch";
            }
            pendingByRequestId.remove(requestId);
        }

        if (!("queued".equals(effectStatus)
                || "observed".equals(effectStatus)
                || "rejected".equals(effectStatus))) {
            pending.completion.onResult(false, "provider_effect_receipt_invalid", "{}");
            return "provider_effect_reply_completed_invalid";
        }
        boolean observed = "observed".equals(effectStatus) && providerApplied;
        pending.completion.onResult(
                observed,
                observed
                        ? "provider_effect_observed"
                        : ("queued".equals(effectStatus)
                                ? "provider_effect_queued"
                                : "provider_effect_rejected"),
                stateJson == null ? "{}" : stateJson);
        return observed
                ? "provider_effect_reply_completed_observed"
                : "provider_effect_reply_completed_not_observed";
    }

    String timeout(String requestId, String expectedEffectBinding) {
        return failExact(
                requestId,
                expectedEffectBinding,
                "provider_effect_receipt_timeout",
                "provider_effect_reply_timed_out");
    }

    String dispatchFailed(String requestId, String expectedEffectBinding) {
        return failExact(
                requestId,
                expectedEffectBinding,
                "provider_dispatch_failed",
                "provider_effect_reply_dispatch_failed");
    }

    private String failExact(
            String requestId,
            String expectedEffectBinding,
            String callbackStatus,
            String outcome) {
        final Pending pending;
        synchronized (this) {
            pending = pendingByRequestId.get(requestId);
            if (pending == null
                    || !pending.expectedEffectBinding.equals(expectedEffectBinding)) {
                return "provider_effect_reply_late_or_unknown";
            }
            pendingByRequestId.remove(requestId);
        }
        pending.completion.onResult(false, callbackStatus, "{}");
        return outcome;
    }

    int cancelProvider(String providerInstanceId, String callbackStatus) {
        List<Pending> cancelled = new ArrayList<>();
        synchronized (this) {
            for (Pending pending : pendingByRequestId.values()) {
                if (pending.providerInstanceId.equals(providerInstanceId)) {
                    cancelled.add(pending);
                }
            }
            for (Pending pending : cancelled) {
                pendingByRequestId.remove(pending.requestId);
            }
        }
        for (Pending pending : cancelled) {
            pending.completion.onResult(false, callbackStatus, "{}");
        }
        return cancelled.size();
    }

    synchronized int pendingCountForTest() { return pendingByRequestId.size(); }

    private static boolean bounded(String value, int maxChars) {
        return value != null && !value.isEmpty() && value.length() <= maxChars;
    }
}
