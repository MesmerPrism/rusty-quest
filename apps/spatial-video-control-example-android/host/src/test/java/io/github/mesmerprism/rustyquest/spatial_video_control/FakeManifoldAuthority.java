package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Offline test double only. Production must bind the real Manifold provider. */
final class FakeManifoldAuthority implements ManifoldAuthorityPort {
    private static final String TEST_CODE = "482731";
    private static final String TEST_SESSION =
            "test_session_0123456789abcdefghijklmnopqrstuv";
    private final Set<String> requestIds = new HashSet<>();
    private final ArrayDeque<Instant> pairAttempts = new ArrayDeque<>();
    private final ArrayDeque<Instant> commandAttempts = new ArrayDeque<>();
    private String state = "disabled";
    private boolean pairingCodeUsed;
    private String displayedAddress;
    private String windowId;
    private Instant windowExpiresAt;
    private String controllerAddress;
    private Instant sessionExpiresAt;
    private Instant idleExpiresAt;
    private String lastAcceptedReceiptId;
    private long localRevision = 1;
    private long admissionRevision = 1;
    private long leaseRevision = 1;
    private long hostRevision = 1;
    private boolean rejectNextDueExpiry;

    @Override
    public synchronized PairingOffer beginWearerEnable(EnableRequest request) {
        if (!request.wearerForegroundAction()) {
            return rejectedOffer(request, "wearer_foreground_action_required");
        }
        Duration window = request.requestedWindow();
        if (window.isNegative()
                || window.isZero()
                || window.compareTo(TrustedLocalControlPolicy.MAX_ENABLE_WINDOW) > 0) {
            return rejectedOffer(request, "enable_window_out_of_bounds");
        }
        state = "pairing_window_open";
        pairingCodeUsed = false;
        controllerAddress = null;
        displayedAddress = request.displayedAddress();
        windowExpiresAt = request.now().plus(window);
        windowId = "window.local.test-" + localRevision;
        localRevision++;
        return new PairingOffer(
                true,
                displayedAddress,
                TEST_CODE,
                windowExpiresAt,
                windowId,
                "request.local.window.open-" + localRevision,
                "evidence.wearer.window.open-" + localRevision,
                revisions(),
                "wearer_enabled");
    }

    @Override
    public synchronized PairDecision pair(PairAttempt attempt) {
        trim(pairAttempts, attempt.now(), Duration.ofMinutes(1));
        if (pairAttempts.size() >= TrustedLocalControlPolicy.MAX_PAIR_ATTEMPTS_PER_MINUTE) {
            return pairRejected("pair_rate_limited");
        }
        pairAttempts.addLast(attempt.now());
        if (!state.equals("pairing_window_open")
                || windowExpiresAt == null
                || !attempt.now().isBefore(windowExpiresAt)) {
            return pairRejected("listener_not_enabled");
        }
        if (controllerAddress != null) {
            return pairRejected("controller_lease_already_held");
        }
        if (pairingCodeUsed || !TEST_CODE.equals(attempt.request().pairingCode())) {
            return pairRejected("pairing_code_rejected");
        }
        if (!requestIds.add(attempt.request().requestId())) {
            return pairRejected("request_replayed");
        }
        pairingCodeUsed = true;
        controllerAddress = attempt.remoteAddress();
        sessionExpiresAt = attempt.now().plus(TrustedLocalControlPolicy.MAX_SESSION_LIFETIME);
        idleExpiresAt = attempt.now().plus(TrustedLocalControlPolicy.MAX_IDLE_LIFETIME);
        state = "controller_active";
        localRevision++;
        admissionRevision++;
        leaseRevision++;
        hostRevision++;
        return new PairDecision(
                true,
                TEST_SESSION,
                "controller.browser.local",
                sessionExpiresAt,
                "receipt.local_control.admission.test",
                "lease.local_control.test",
                revisions(),
                "paired");
    }

    @Override
    public synchronized SessionDecision inspectSession(
            String sessionCookie, String remoteAddress, Instant now) {
        String reason = sessionReason(sessionCookie, remoteAddress, now);
        if (reason != null) {
            return new SessionDecision(false, snapshot(), reason);
        }
        return new SessionDecision(true, snapshot(), "active");
    }

    @Override
    public synchronized ExpiryDecision enforceExpiry(ExpiryRequest request) {
        Instant now = request.transportObservedAt();
        boolean windowExpired =
                state.equals("pairing_window_open")
                        && windowExpiresAt != null
                        && !now.isBefore(windowExpiresAt);
        boolean controllerExpired =
                state.equals("controller_active")
                        && (!now.isBefore(sessionExpiresAt) || !now.isBefore(idleExpiresAt));
        if (!windowExpired && !controllerExpired) {
            return new ExpiryDecision(
                    false, false, false, null, null, snapshot(), "not_due");
        }
        String cause = windowExpired ? "pairing_window_expired" : "controller_expired";
        if (rejectNextDueExpiry) {
            rejectNextDueExpiry = false;
            return new ExpiryDecision(
                    true,
                    false,
                    false,
                    "receipt.local_control.expiry.rejected",
                    cause,
                    snapshot(),
                    "authority_rejected");
        }
        disable(controllerExpired);
        return new ExpiryDecision(
                true,
                true,
                controllerExpired,
                "receipt.local_control.expiry." + request.requestId(),
                cause,
                snapshot(),
                "expired");
    }

    synchronized void rejectNextDueExpiry() {
        rejectNextDueExpiry = true;
    }

    @Override
    public synchronized CommandDecision review(CommandAttempt attempt) {
        Instant now = Instant.now();
        String reason = sessionReason(attempt.sessionCookie(), attempt.remoteAddress(), now);
        if (reason != null) {
            return rejected(attempt.envelope(), reason);
        }
        trim(commandAttempts, now, Duration.ofMinutes(1));
        if (commandAttempts.size() >= TrustedLocalControlPolicy.MAX_REQUESTS_PER_MINUTE) {
            return rejected(attempt.envelope(), "command_rate_limited");
        }
        commandAttempts.addLast(now);
        CommandEnvelope envelope = attempt.envelope();
        if (envelope.expectedAuthorityRevision() != localRevision) {
            return rejected(envelope, "stale_authority_revision");
        }
        if (requestIds.contains(envelope.requestId())) {
            return rejected(envelope, "request_replayed");
        }
        requestIds.add(envelope.requestId());
        idleExpiresAt = now.plus(TrustedLocalControlPolicy.MAX_IDLE_LIFETIME);
        localRevision++;
        admissionRevision++;
        hostRevision++;
        lastAcceptedReceiptId = "receipt.manifold.command." + envelope.requestId();
        return new CommandDecision(
                true,
                envelope.requestId(),
                envelope.command(),
                revisions(),
                "lease.local_control.test",
                lastAcceptedReceiptId,
                "accepted");
    }

    @Override
    public synchronized AuthoritySnapshot snapshot() {
        return new AuthoritySnapshot(
                state,
                revisions(),
                windowId,
                windowExpiresAt,
                controllerAddress == null ? null : "controller.browser.local",
                sessionExpiresAt,
                idleExpiresAt,
                lastAcceptedReceiptId);
    }

    @Override
    public synchronized RevokeDecision revokeByWearer(RevokeRequest request) {
        if (state.equals("disabled")) {
            return new RevokeDecision(
                    false, revisions(), null, request.cause(), "already_disabled");
        }
        boolean hadController = state.equals("controller_active");
        disable(hadController);
        return new RevokeDecision(
                true,
                revisions(),
                "receipt.local_control.disable." + request.requestId(),
                request.cause(),
                "disabled");
    }

    private void disable(boolean hadController) {
        state = "disabled";
        controllerAddress = null;
        windowId = null;
        windowExpiresAt = null;
        sessionExpiresAt = null;
        idleExpiresAt = null;
        lastAcceptedReceiptId = null;
        localRevision++;
        if (hadController) {
            admissionRevision++;
            leaseRevision++;
            hostRevision++;
        }
    }

    private PairingOffer rejectedOffer(EnableRequest request, String reason) {
        return new PairingOffer(
                false,
                request.displayedAddress(),
                "",
                request.now(),
                null,
                null,
                null,
                revisions(),
                reason);
    }

    private PairDecision pairRejected(String reason) {
        return new PairDecision(
                false, null, null, Instant.EPOCH, null, null, revisions(), reason);
    }

    private CommandDecision rejected(CommandEnvelope envelope, String reason) {
        return new CommandDecision(
                false,
                envelope.requestId(),
                envelope.command(),
                revisions(),
                null,
                null,
                reason);
    }

    private AuthorityRevisions revisions() {
        return new AuthorityRevisions(
                localRevision, admissionRevision, leaseRevision, hostRevision);
    }

    private String sessionReason(String sessionCookie, String remoteAddress, Instant now) {
        if (!state.equals("controller_active")) {
            return "listener_not_enabled";
        }
        if (!now.isBefore(sessionExpiresAt) || !now.isBefore(idleExpiresAt)) {
            return "session_expired";
        }
        if (!TEST_SESSION.equals(sessionCookie)) {
            return "session_cookie_rejected";
        }
        if (!remoteAddress.equals(controllerAddress)) {
            return "controller_address_mismatch";
        }
        return null;
    }

    private static void trim(ArrayDeque<Instant> values, Instant now, Duration window) {
        Instant threshold = now.minus(window);
        while (!values.isEmpty() && values.peekFirst().isBefore(threshold)) {
            values.removeFirst();
        }
    }
}
