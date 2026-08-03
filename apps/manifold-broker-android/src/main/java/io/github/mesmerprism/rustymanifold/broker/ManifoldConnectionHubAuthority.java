package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exact JNI-backed authority port; Java proposes transport facts but never accepts them. */
public final class ManifoldConnectionHubAuthority implements ConnectionHubAuthorityPort {
    private static final String VERIFIED_WEARER_EVIDENCE = "evidence.operator.wearer-action";

    public ManifoldConnectionHubAuthority() {
        try {
            ManifoldConnectionHubNativeBridge.initialize();
        } catch (Exception error) {
            throw new IllegalStateException("Manifold Connection Hub authority unavailable", error);
        }
    }

    @Override public Receipt trustAndOpenSession(String requestId, String controllerSha,
            String wearerEvidence, long nowMs) {
        if (!VERIFIED_WEARER_EVIDENCE.equals(wearerEvidence)) {
            return Receipt.rejected("operator_evidence_not_verified");
        }
        return execute(object(
                "operation", "trust_and_open_session",
                "request_id", requestId,
                "controller_identity_sha256", controllerSha), nowMs);
    }

    @Override public Receipt replaceTransport(String requestId, String sessionId,
            long expectedEpoch, long nowMs) {
        return execute(object(
                "operation", "replace_transport", "request_id", requestId,
                "session_id", sessionId, "expected_transport_epoch", expectedEpoch), nowMs);
    }

    @Override public Receipt refreshAuthenticatedActivity(String requestId, String sessionId,
            long epoch, long sequence, String externalRequestSha256, long nowMs) {
        return execute(object(
                "operation", "refresh_authenticated_activity", "request_id", requestId,
                "session_id", sessionId, "expected_transport_epoch", epoch,
                "external_request_sequence", sequence,
                "external_request_sha256", externalRequestSha256), nowMs);
    }

    @Override public Receipt registerProvider(String requestId, HubProviderIdentity identity,
            String providerInstanceId, String admissionUseRequestId, long nowMs) {
        return execute(object(
                "operation", "register_provider", "request_id", requestId,
                "sending_uid", identity.uid(), "package_name", identity.packageName(),
                "signer_sha256", identity.signerSha256(),
                "provider_instance_id", providerInstanceId,
                "admission_use_request_id", admissionUseRequestId), nowMs);
    }

    @Override public Receipt registerSurface(String requestId, String providerInstanceId,
            HubSurfaceDescriptor descriptor, long nowMs) {
        JSONArray commands = new JSONArray();
        for (HubSurfaceDescriptor.Command command : descriptor.commands()) {
            commands.put(object(
                    "command_id", command.commandId(),
                    "required_controller_capability", command.requiredControllerCapability()));
        }
        return execute(object(
                "operation", "register_surface", "request_id", requestId,
                "provider_instance_id", providerInstanceId,
                "surface_id", descriptor.surfaceId(),
                "display_label", descriptor.displayLabel(),
                "description", descriptor.description(),
                "surface_contract_sha256", descriptor.surfaceContractSha256(),
                "commands", commands), nowMs);
    }

    @Override public Receipt unregisterSurface(String requestId, String providerInstanceId,
            String surfaceId, long nowMs) {
        return execute(object(
                "operation", "unregister_surface", "request_id", requestId,
                "provider_instance_id", providerInstanceId, "surface_id", surfaceId), nowMs);
    }

    @Override public Receipt unregisterProvider(String requestId, String providerInstanceId,
            String reason, long nowMs) {
        return execute(object(
                "operation", "unregister_provider", "request_id", requestId,
                "provider_instance_id", providerInstanceId, "reason", reason), nowMs);
    }

    @Override public Receipt acquireSurfaceLease(String requestId, String sessionId,
            long epoch, String surfaceId, long nowMs) {
        return execute(object(
                "operation", "acquire_surface_lease", "request_id", requestId,
                "session_id", sessionId, "expected_transport_epoch", epoch,
                "surface_id", surfaceId), nowMs);
    }

    @Override public Receipt releaseSurfaceLease(String requestId, String sessionId,
            String leaseId, String reason, long nowMs) {
        return execute(object(
                "operation", "release_surface_lease", "request_id", requestId,
                "session_id", sessionId, "lease_id", leaseId, "reason", reason), nowMs);
    }

    @Override public Receipt authorizeCommand(String requestId, String sessionId,
            long epoch, String leaseId, String surfaceId, String command,
            String typedParamsSha256, long sequence, String externalRequestSha256, long nowMs) {
        return execute(object(
                "operation", "authorize_surface_command", "request_id", requestId,
                "session_id", sessionId, "expected_transport_epoch", epoch,
                "lease_id", leaseId, "surface_id", surfaceId,
                "command_id", command, "typed_params_sha256", typedParamsSha256,
                "external_request_sequence", sequence,
                "external_request_sha256", externalRequestSha256), nowMs);
    }

    @Override public Receipt revokeSession(String requestId, String sessionId,
            String reason, long nowMs) {
        return execute(object(
                "operation", "revoke_session", "request_id", requestId,
                "session_id", sessionId, "reason", reason), nowMs);
    }

    @Override public Receipt forgetAll(String requestId, String reason, long nowMs) {
        return execute(object(
                "operation", "forget_all", "request_id", requestId, "reason", reason), nowMs);
    }

    @Override public Receipt expire(String requestId, long nowMs) {
        return execute(object("operation", "expire", "request_id", requestId), nowMs);
    }

    @Override public Receipt reconcileAfterRestart(String requestId, long nowMs) {
        return execute(object("operation", "reconcile_restart", "request_id", requestId), nowMs);
    }

    @Override public Receipt forceHistoryRollover(String requestId, long nowMs) {
        return execute(object(
                "operation", "force_rollover", "request_id", requestId), nowMs);
    }

    @Override public String exportOpaqueState() {
        return ManifoldConnectionHubNativeBridge.exportState();
    }

    @Override public Receipt restoreOpaqueState(String opaqueState, long nowMs) {
        try { return parse(ManifoldConnectionHubNativeBridge.restore(opaqueState)); }
        catch (Exception error) { return Receipt.rejected("state_restore_rejected"); }
    }

    private static JSONObject object(Object... pairs) {
        if (pairs.length % 2 != 0) { throw new IllegalArgumentException("JSON pairs required"); }
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return new JSONObject(values);
    }

    private static Receipt execute(JSONObject proposal, long nowMs) {
        try { return parse(ManifoldConnectionHubNativeBridge.execute(proposal, nowMs)); }
        catch (Exception error) { return Receipt.rejected("manifold_authority_execution_failed"); }
    }

    private static Receipt parse(JSONObject value) {
        JSONObject authorityReceipt = value.optJSONObject("authority_receipt");
        return new Receipt(
                value.optBoolean("applied", false),
                value.optString("status", "rejected"),
                authorityReceipt == null ? "{}" : authorityReceipt.toString(),
                value.optString("logical_session_id", null),
                value.optLong("transport_epoch", 0),
                value.optLong("expires_at_ms", 0),
                value.optString("surface_lease_id", null),
                value.optLong("next_external_request_sequence", 0));
    }
}
