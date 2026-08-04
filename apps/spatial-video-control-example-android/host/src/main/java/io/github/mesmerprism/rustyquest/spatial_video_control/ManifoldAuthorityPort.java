package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Production implementations must delegate to the process-local Manifold
 * authority. Quest must not implement these decisions.
 */
public interface ManifoldAuthorityPort {
    enum AccessMode {
        PAIRED("paired"),
        OPEN_LAN_INSECURE("open_lan_insecure");

        private final String protocolName;

        AccessMode(String protocolName) {
            this.protocolName = protocolName;
        }

        public String protocolName() {
            return protocolName;
        }
    }

    enum EnableActor {
        WEARER("wearer"),
        DEBUG_SHELL("debug_shell");

        private final String protocolName;

        EnableActor(String protocolName) {
            this.protocolName = protocolName;
        }

        public String protocolName() {
            return protocolName;
        }
    }

    record AuthorityRevisions(
            long localRevision,
            long admissionRevision,
            long leaseAuthorityRevision,
            long hostRevision) {
        public AuthorityRevisions {
            if (localRevision < 1
                    || admissionRevision < 1
                    || leaseAuthorityRevision < 1
                    || hostRevision < 1) {
                throw new IllegalArgumentException("Manifold revisions are non-zero");
            }
        }
    }

    record EnableRequest(
            String displayedAddress,
            Duration requestedWindow,
            Instant now,
            boolean foregroundOperatorAction,
            AccessMode accessMode,
            EnableActor enableActor) {}

    record PairingOffer(
            boolean enabled,
            String displayedAddress,
            String singleUseCode,
            Instant expiresAt,
            String windowId,
            String windowRequestId,
            String wearerEvidenceId,
            AuthorityRevisions revisions,
            AccessMode accessMode,
            String reason) {}

    record PairAttempt(
            String remoteAddress,
            PairingRequest request,
            Instant now) {}

    record OpenLanAttempt(String remoteAddress, String requestId, Instant now) {}

    record PairDecision(
            boolean accepted,
            String sessionCookie,
            String controllerLabel,
            Instant sessionExpiresAt,
            String admissionReceiptId,
            String controllerLeaseId,
            AuthorityRevisions revisions,
            String reason) {}

    record CommandAttempt(String sessionCookie, String remoteAddress, CommandEnvelope envelope) {}

    record SessionDecision(boolean active, AuthoritySnapshot authority, String reason) {}

    record CommandDecision(
            boolean accepted,
            String requestId,
            String command,
            AuthorityRevisions revisions,
            String controllerLeaseId,
            String acceptedCommandReceiptId,
            String reason) {}

    record ExpiryRequest(String requestId, Instant transportObservedAt) {}

    record ExpiryDecision(
            boolean due,
            boolean enforced,
            boolean expired,
            String expiryReceiptId,
            String cause,
            AuthoritySnapshot authority,
            String reason) {}

    record RevokeRequest(String requestId, String cause) {
        public RevokeRequest {
            if (requestId == null || !requestId.matches("^[a-z0-9][a-z0-9-]{15,63}$")) {
                throw new IllegalArgumentException("revoke request id is invalid");
            }
            if (cause == null || !cause.matches("^[a-z0-9](?:[a-z0-9_]{0,62}[a-z0-9])?$")) {
                throw new IllegalArgumentException("revoke cause must be a bounded token");
            }
        }
    }

    record RevokeDecision(
            boolean revoked,
            AuthorityRevisions revisions,
            String disableReceiptId,
            String cause,
            String reason) {}

    record AuthoritySnapshot(
            String state,
            AccessMode accessMode,
            AuthorityRevisions revisions,
            String windowId,
            Instant windowExpiresAt,
            String controllerId,
            Instant sessionExpiresAt,
            Instant idleExpiresAt,
            String lastAcceptedCommandReceiptId) {
        public AuthoritySnapshot {
            if (!Set.of("disabled", "pairing_window_open", "controller_active").contains(state)) {
                throw new IllegalArgumentException("unknown local-control state");
            }
        }

        public boolean enabled() {
            return !state.equals("disabled");
        }

        public boolean controllerConnected() {
            return state.equals("controller_active");
        }
    }

    PairingOffer beginWearerEnable(EnableRequest request);

    PairDecision pair(PairAttempt attempt);

    PairDecision admitOpenLan(OpenLanAttempt attempt);

    SessionDecision inspectSession(String sessionCookie, String remoteAddress, Instant now);

    ExpiryDecision enforceExpiry(ExpiryRequest request);

    CommandDecision review(CommandAttempt attempt);

    AuthoritySnapshot snapshot();

    RevokeDecision revokeByWearer(RevokeRequest request);
}
