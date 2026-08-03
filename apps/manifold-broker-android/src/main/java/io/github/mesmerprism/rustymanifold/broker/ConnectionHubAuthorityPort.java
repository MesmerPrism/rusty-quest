package io.github.mesmerprism.rustymanifold.broker;

/**
 * Transport-neutral authority port. Implementations must delegate every trust,
 * session, provider, surface, lease, replay, expiry, and revocation decision to
 * Manifold; the Android Hub owns no acceptance policy.
 */
public interface ConnectionHubAuthorityPort {
    final class Receipt {
        public final boolean applied;
        public final String status;
        public final String authorityReceiptJson;
        public final String logicalSessionId;
        public final long transportEpoch;
        public final long expiresAtMs;
        public final String surfaceLeaseId;
        public final long nextExternalRequestSequence;

        public Receipt(
                boolean applied,
                String status,
                String authorityReceiptJson,
                String logicalSessionId,
                long transportEpoch,
                long expiresAtMs,
                String surfaceLeaseId) {
            this(applied, status, authorityReceiptJson, logicalSessionId, transportEpoch,
                    expiresAtMs, surfaceLeaseId, 0);
        }

        public Receipt(
                boolean applied,
                String status,
                String authorityReceiptJson,
                String logicalSessionId,
                long transportEpoch,
                long expiresAtMs,
                String surfaceLeaseId,
                long nextExternalRequestSequence) {
            this.applied = applied;
            this.status = status;
            this.authorityReceiptJson = authorityReceiptJson;
            this.logicalSessionId = logicalSessionId;
            this.transportEpoch = transportEpoch;
            this.expiresAtMs = expiresAtMs;
            this.surfaceLeaseId = surfaceLeaseId;
            this.nextExternalRequestSequence = nextExternalRequestSequence;
        }

        public static Receipt rejected(String status) {
            return new Receipt(false, status, "{}", null, 0, 0, null);
        }
    }

    Receipt trustAndOpenSession(
            String requestId,
            String controllerIdentitySha256,
            String wearerOperatorEvidenceId,
            long nowMs);

    Receipt replaceTransport(
            String requestId,
            String logicalSessionId,
            long expectedTransportEpoch,
            long nowMs);

    Receipt refreshAuthenticatedActivity(
            String requestId,
            String logicalSessionId,
            long transportEpoch,
            long externalRequestSequence,
            String externalRequestSha256,
            long nowMs);

    Receipt registerProvider(
            String requestId,
            HubProviderIdentity identity,
            String providerInstanceId,
            String admissionUseRequestId,
            long nowMs);

    Receipt registerSurface(
            String requestId,
            String providerInstanceId,
            HubSurfaceDescriptor descriptor,
            long nowMs);

    Receipt unregisterSurface(
            String requestId,
            String providerInstanceId,
            String surfaceId,
            long nowMs);

    Receipt unregisterProvider(
            String requestId,
            String providerInstanceId,
            String reason,
            long nowMs);

    Receipt acquireSurfaceLease(
            String requestId,
            String logicalSessionId,
            long transportEpoch,
            String providerInstanceId,
            String surfaceId,
            long nowMs);

    Receipt releaseSurfaceLease(
            String requestId,
            String logicalSessionId,
            String surfaceLeaseId,
            String reason,
            long nowMs);

    Receipt authorizeCommand(
            String requestId,
            String logicalSessionId,
            long transportEpoch,
            String surfaceLeaseId,
            String surfaceId,
            String command,
            String commandParamsSha256,
            long externalRequestSequence,
            String externalRequestSha256,
            long nowMs);

    Receipt revokeSession(
            String requestId,
            String logicalSessionId,
            String reason,
            long nowMs);

    Receipt forgetAll(String requestId, String reason, long nowMs);

    Receipt expire(String requestId, long nowMs);

    Receipt reconcileAfterRestart(String requestId, long nowMs);

    Receipt forceHistoryRollover(String requestId, long nowMs);

    /** Opaque Manifold-authored state envelope; Android persists but never edits it. */
    String exportOpaqueState();

    /** Restores only a previously exported, Manifold-authored envelope. */
    Receipt restoreOpaqueState(String opaqueState, long nowMs);
}
