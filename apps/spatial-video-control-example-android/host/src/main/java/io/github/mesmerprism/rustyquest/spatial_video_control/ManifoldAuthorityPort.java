package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.time.Duration;
import java.time.Instant;

/**
 * Production implementations must delegate to the process-local Manifold
 * authority. Quest must not implement these decisions.
 */
public interface ManifoldAuthorityPort {
    record EnableRequest(
            String displayedAddress,
            Duration requestedWindow,
            Instant now,
            boolean wearerForegroundAction) {}

    record PairingOffer(
            boolean enabled,
            String displayedAddress,
            String singleUseCode,
            Instant expiresAt,
            long authorityRevision,
            String reason) {}

    record PairAttempt(
            String remoteAddress,
            PairingRequest request,
            Instant now) {}

    record PairDecision(
            boolean accepted,
            String sessionCookie,
            String controllerLabel,
            Instant sessionExpiresAt,
            long authorityRevision,
            String reason) {}

    record CommandAttempt(
            String sessionCookie,
            String remoteAddress,
            CommandEnvelope envelope,
            PlayerPort.Snapshot currentPlayerState,
            Instant now) {}

    record SessionDecision(boolean active, long authorityRevision, String reason) {}

    record CommandDecision(
            boolean accepted,
            String requestId,
            String command,
            long authorityRevision,
            String controllerLeaseId,
            String reason) {}

    record AppliedObservation(
            String requestId,
            String command,
            long acceptedAuthorityRevision,
            long playerRevision,
            Instant observedAt) {}

    record AppliedDecision(boolean recorded, long authorityRevision, String reason) {}

    record AuthoritySnapshot(
            boolean enabled,
            boolean controllerConnected,
            String controllerLabel,
            Instant enableExpiresAt,
            Instant sessionExpiresAt,
            long authorityRevision) {}

    PairingOffer beginWearerEnable(EnableRequest request);

    PairDecision pair(PairAttempt attempt);

    SessionDecision inspectSession(String sessionCookie, String remoteAddress, Instant now);

    CommandDecision review(CommandAttempt attempt);

    AppliedDecision recordApplied(AppliedObservation observation);

    AuthoritySnapshot snapshot(Instant now);

    void revokeByWearer(Instant now);
}
