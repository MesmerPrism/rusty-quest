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
    private boolean enabled;
    private boolean pairingCodeUsed;
    private boolean revoked;
    private String displayedAddress;
    private Instant enableExpiresAt = Instant.EPOCH;
    private String controllerAddress;
    private Instant sessionExpiresAt = Instant.EPOCH;
    private Instant idleExpiresAt = Instant.EPOCH;
    private long authorityRevision;
    private Pending pending;

    @Override
    public synchronized PairingOffer beginWearerEnable(EnableRequest request) {
        if (!request.wearerForegroundAction()) {
            return new PairingOffer(
                    false,
                    request.displayedAddress(),
                    "",
                    request.now(),
                    authorityRevision,
                    "wearer_foreground_action_required");
        }
        Duration window = request.requestedWindow();
        if (window.isNegative()
                || window.isZero()
                || window.compareTo(TrustedLocalControlPolicy.MAX_ENABLE_WINDOW) > 0) {
            return new PairingOffer(
                    false,
                    request.displayedAddress(),
                    "",
                    request.now(),
                    authorityRevision,
                    "enable_window_out_of_bounds");
        }
        enabled = true;
        pairingCodeUsed = false;
        revoked = false;
        controllerAddress = null;
        sessionExpiresAt = Instant.EPOCH;
        idleExpiresAt = Instant.EPOCH;
        pending = null;
        displayedAddress = request.displayedAddress();
        enableExpiresAt = request.now().plus(window);
        authorityRevision++;
        return new PairingOffer(
                true,
                displayedAddress,
                TEST_CODE,
                enableExpiresAt,
                authorityRevision,
                "wearer_enabled");
    }

    @Override
    public synchronized PairDecision pair(PairAttempt attempt) {
        expire(attempt.now());
        trim(pairAttempts, attempt.now(), Duration.ofMinutes(1));
        if (pairAttempts.size() >= TrustedLocalControlPolicy.MAX_PAIR_ATTEMPTS_PER_MINUTE) {
            return pairRejected("pair_rate_limited");
        }
        pairAttempts.addLast(attempt.now());
        if (!enabled || revoked || !attempt.now().isBefore(enableExpiresAt)) {
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
        authorityRevision++;
        return new PairDecision(
                true,
                TEST_SESSION,
                "local-browser",
                sessionExpiresAt,
                authorityRevision,
                "paired");
    }

    @Override
    public synchronized SessionDecision inspectSession(
            String sessionCookie, String remoteAddress, Instant now) {
        expire(now);
        String reason = sessionReason(sessionCookie, remoteAddress, now);
        if (reason != null) {
            return new SessionDecision(false, authorityRevision, reason);
        }
        idleExpiresAt = now.plus(TrustedLocalControlPolicy.MAX_IDLE_LIFETIME);
        return new SessionDecision(true, authorityRevision, "active");
    }

    @Override
    public synchronized CommandDecision review(CommandAttempt attempt) {
        expire(attempt.now());
        String reason = sessionReason(attempt.sessionCookie(), attempt.remoteAddress(), attempt.now());
        if (reason != null) {
            return rejected(attempt.envelope(), reason);
        }
        trim(commandAttempts, attempt.now(), Duration.ofMinutes(1));
        if (commandAttempts.size() >= TrustedLocalControlPolicy.MAX_REQUESTS_PER_MINUTE) {
            return rejected(attempt.envelope(), "command_rate_limited");
        }
        commandAttempts.addLast(attempt.now());
        CommandEnvelope envelope = attempt.envelope();
        if (envelope.expectedAuthorityRevision() != authorityRevision) {
            return rejected(envelope, "stale_authority_revision");
        }
        if (envelope.expectedPlayerRevision() != attempt.currentPlayerState().revision()) {
            return rejected(envelope, "stale_player_revision");
        }
        if (requestIds.contains(envelope.requestId())) {
            return rejected(envelope, "request_replayed");
        }
        if (envelope.command().equals("play") && attempt.currentPlayerState().playing()) {
            return rejected(envelope, "already_playing");
        }
        if (envelope.command().equals("pause") && !attempt.currentPlayerState().playing()) {
            return rejected(envelope, "already_paused");
        }
        if (envelope.command().equals("select_video")
                && envelope.videoId().equals(attempt.currentPlayerState().selectedVideoId())) {
            return rejected(envelope, "already_selected");
        }
        if (pending != null) {
            return rejected(envelope, "player_effect_pending");
        }
        requestIds.add(envelope.requestId());
        idleExpiresAt = attempt.now().plus(TrustedLocalControlPolicy.MAX_IDLE_LIFETIME);
        authorityRevision++;
        if (envelope.hasPlayerEffect()) {
            pending =
                    new Pending(
                            envelope.requestId(),
                            envelope.command(),
                            authorityRevision,
                            envelope.expectedPlayerRevision() + 1);
        }
        return new CommandDecision(
                true,
                envelope.requestId(),
                envelope.command(),
                authorityRevision,
                "single-controller-lease",
                "accepted");
    }

    @Override
    public synchronized AppliedDecision recordApplied(AppliedObservation observation) {
        if (pending == null
                || !pending.requestId().equals(observation.requestId())
                || !pending.command().equals(observation.command())
                || pending.acceptedAuthorityRevision() != observation.acceptedAuthorityRevision()
                || pending.expectedAppliedPlayerRevision() != observation.playerRevision()) {
            return new AppliedDecision(false, authorityRevision, "applied_causality_mismatch");
        }
        pending = null;
        authorityRevision++;
        return new AppliedDecision(true, authorityRevision, "application_observed");
    }

    @Override
    public synchronized AuthoritySnapshot snapshot(Instant now) {
        expire(now);
        return new AuthoritySnapshot(
                enabled,
                controllerAddress != null,
                controllerAddress == null ? null : "local-browser",
                enableExpiresAt,
                sessionExpiresAt,
                authorityRevision);
    }

    @Override
    public synchronized void revokeByWearer(Instant now) {
        enabled = false;
        revoked = true;
        controllerAddress = null;
        pending = null;
        authorityRevision++;
    }

    private void expire(Instant now) {
        boolean expired =
                enabled
                        && (!now.isBefore(enableExpiresAt)
                                || (controllerAddress != null
                                        && (!now.isBefore(sessionExpiresAt)
                                                || !now.isBefore(idleExpiresAt))));
        if (expired) {
            enabled = false;
            controllerAddress = null;
            pending = null;
            authorityRevision++;
        }
    }

    private String sessionReason(String sessionCookie, String remoteAddress, Instant now) {
        if (!enabled || revoked) {
            return "listener_not_enabled";
        }
        if (!now.isBefore(enableExpiresAt)
                || !now.isBefore(sessionExpiresAt)
                || !now.isBefore(idleExpiresAt)) {
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

    private PairDecision pairRejected(String reason) {
        return new PairDecision(
                false, null, null, Instant.EPOCH, authorityRevision, reason);
    }

    private CommandDecision rejected(CommandEnvelope envelope, String reason) {
        return new CommandDecision(
                false,
                envelope.requestId(),
                envelope.command(),
                authorityRevision,
                null,
                reason);
    }

    private static void trim(ArrayDeque<Instant> values, Instant now, Duration window) {
        Instant threshold = now.minus(window);
        while (!values.isEmpty() && values.peekFirst().isBefore(threshold)) {
            values.removeFirst();
        }
    }

    private record Pending(
            String requestId,
            String command,
            long acceptedAuthorityRevision,
            long expectedAppliedPlayerRevision) {}
}
