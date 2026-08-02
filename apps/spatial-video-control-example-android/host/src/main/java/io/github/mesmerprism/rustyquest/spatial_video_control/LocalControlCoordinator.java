package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates accepted Manifold commands with Quest-owned player effects. */
public final class LocalControlCoordinator implements PlayerPort.Listener, AutoCloseable {
    public interface EventSink {
        void emit(String canonicalJson);
    }

    private final ManifoldAuthorityPort authority;
    private final PlayerPort player;
    private final VideoCatalog catalog;
    private final long effectWaitMillis;
    private final CopyOnWriteArrayList<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final Object effectLock = new Object();
    private final AtomicLong expiryRequestSequence = new AtomicLong();
    private final String expiryRequestNamespace =
            UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final ScheduledExecutorService effectDeadline =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "local-control-effect-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private String reservedEffectRequestId;
    private PlayerPort.AcceptedEffect pendingEffect;
    private ScheduledFuture<?> pendingDeadline;

    public LocalControlCoordinator(
            ManifoldAuthorityPort authority, PlayerPort player, VideoCatalog catalog) {
        this(
                authority,
                player,
                catalog,
                TrustedLocalControlPolicy.MAX_PLAYER_EFFECT_WAIT);
    }

    LocalControlCoordinator(
            ManifoldAuthorityPort authority,
            PlayerPort player,
            VideoCatalog catalog,
            Duration effectWait) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.player = Objects.requireNonNull(player, "player");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(effectWait, "effectWait");
        if (effectWait.isNegative()
                || effectWait.isZero()
                || effectWait.compareTo(TrustedLocalControlPolicy.MAX_PLAYER_EFFECT_WAIT) > 0) {
            throw new IllegalArgumentException("player effect wait is outside the bounded policy");
        }
        this.effectWaitMillis = effectWait.toMillis();
        player.setListener(this);
    }

    public void addEventSink(EventSink sink) {
        sinks.add(Objects.requireNonNull(sink, "sink"));
    }

    public void removeEventSink(EventSink sink) {
        sinks.remove(sink);
    }

    public ManifoldAuthorityPort.PairDecision pair(
            String remoteAddress, PairingRequest request, Instant now) {
        enforceExpiry(now);
        return authority.pair(new ManifoldAuthorityPort.PairAttempt(remoteAddress, request, now));
    }

    public ManifoldAuthorityPort.PairDecision admitOpenLan(
            String remoteAddress, OpenLanRequest request, Instant now) {
        enforceExpiry(now);
        return authority.admitOpenLan(
                new ManifoldAuthorityPort.OpenLanAttempt(remoteAddress, request.requestId(), now));
    }

    public ManifoldAuthorityPort.SessionDecision inspectSession(
            String sessionCookie, String remoteAddress, Instant now) {
        enforceExpiry(now);
        return authority.inspectSession(sessionCookie, remoteAddress, now);
    }

    public void handleCommand(
            String sessionCookie,
            String remoteAddress,
            CommandEnvelope envelope,
            Instant now) {
        enforceExpiry(now);
        PlayerPort.Snapshot before = player.snapshot();
        ManifoldAuthorityPort.AuthoritySnapshot authorityBefore = authority.snapshot();
        String precondition =
                envelope.command().equals("select_video") && !catalog.contains(envelope.videoId())
                        ? "unknown_video_id"
                        : playerPrecondition(envelope, before);
        if (precondition != null) {
            emit(commandNotSubmitted(envelope, before, authorityBefore.revisions(), precondition));
            return;
        }
        boolean effectReserved = false;
        if (envelope.hasPlayerEffect()) {
            synchronized (effectLock) {
                if (reservedEffectRequestId != null) {
                    emit(
                            commandNotSubmitted(
                                    envelope,
                                    before,
                                    authorityBefore.revisions(),
                                    "player_effect_pending"));
                    return;
                }
                reservedEffectRequestId = envelope.requestId();
                effectReserved = true;
            }
        }

        ManifoldAuthorityPort.CommandDecision decision;
        try {
            decision =
                    authority.review(
                            new ManifoldAuthorityPort.CommandAttempt(
                                    sessionCookie, remoteAddress, envelope));
        } catch (RuntimeException error) {
            if (effectReserved) {
                clearReservation(envelope.requestId());
            }
            emit(
                    commandNotSubmitted(
                            envelope,
                            before,
                            authority.snapshot().revisions(),
                            "authority_bridge_" + error.getClass().getSimpleName()));
            return;
        }
        if (!decision.accepted()) {
            if (effectReserved) {
                clearReservation(envelope.requestId());
            }
            emit(commandRejected(envelope, before, decision.revisions(), decision.reason()));
            return;
        }

        emit(commandAccepted(envelope, decision));
        if (!envelope.hasPlayerEffect()) {
            emit(queryResult(envelope, decision, before));
            return;
        }

        PlayerPort.AcceptedEffect effect =
                new PlayerPort.AcceptedEffect(
                        envelope.requestId(),
                        envelope.command(),
                        envelope.expectedPlayerRevision(),
                        decision.revisions(),
                        decision.acceptedCommandReceiptId(),
                        envelope.videoId());
        synchronized (effectLock) {
            if (!envelope.requestId().equals(reservedEffectRequestId) || pendingEffect != null) {
                clearReservation(envelope.requestId());
                emit(
                        event(
                                "command_failed",
                                effect,
                                before,
                                decision.revisions(),
                                "player_effect_reservation_lost"));
                return;
            }
            pendingEffect = effect;
            pendingDeadline =
                    effectDeadline.schedule(
                            () -> expirePendingEffect(effect),
                            effectWaitMillis,
                            TimeUnit.MILLISECONDS);
        }
        try {
            switch (envelope.command()) {
                case "select_video" -> player.selectVideo(effect);
                case "play" -> player.play(effect);
                case "pause" -> player.pause(effect);
                default -> throw new IllegalStateException("unreachable registered effect command");
            }
        } catch (RuntimeException error) {
            onFailed(effect, "player_dispatch_" + error.getClass().getSimpleName());
        }
    }

    @Override
    public void onApplied(PlayerPort.AppliedEffect effect) {
        if (!clearPending(effect.cause())) {
            emit(
                    event(
                            "command_effect_unrecorded",
                            effect.cause(),
                            effect.state(),
                            authority.snapshot().revisions(),
                            "accepted_effect_causality_mismatch"));
            return;
        }
        emit(
                event(
                        "command_applied",
                        effect.cause(),
                        effect.state(),
                        authority.snapshot().revisions(),
                        "applied_from_player_callback"));
        emit(
                stateChanged(
                        effect.cause(),
                        effect.state(),
                        authority.snapshot().revisions()));
    }

    @Override
    public void onFailed(PlayerPort.AcceptedEffect effect, String reason) {
        if (!clearPending(effect)) {
            return;
        }
        emit(
                event(
                        "command_failed",
                        effect,
                        player.snapshot(),
                        authority.snapshot().revisions(),
                        reason));
    }

    @Override
    public void onStateObserved(PlayerPort.Snapshot state) {
        Map<String, Object> body = new LinkedHashMap<>();
        putRevisions(body, authority.snapshot().revisions());
        body.put("event", "state_observed");
        body.put("state", playerStateMap(state));
        emit(JsonStrings.object(body));
    }

    public String visibleState(Instant ignoredTransportTime) {
        return stateJson(player.snapshot(), authority.snapshot());
    }

    @Override
    public void close() {
        PlayerPort.AcceptedEffect effect;
        synchronized (effectLock) {
            effect = pendingEffect;
            pendingEffect = null;
            reservedEffectRequestId = null;
            if (pendingDeadline != null) {
                pendingDeadline.cancel(false);
                pendingDeadline = null;
            }
        }
        if (effect != null) {
            player.cancelPending(effect);
        }
        effectDeadline.shutdownNow();
        sinks.clear();
    }

    private void enforceExpiry(Instant transportObservedAt) {
        String requestId =
                "expiry-%s-%016x"
                        .formatted(
                                expiryRequestNamespace,
                                expiryRequestSequence.incrementAndGet());
        ManifoldAuthorityPort.ExpiryDecision decision =
                authority.enforceExpiry(
                        new ManifoldAuthorityPort.ExpiryRequest(
                                requestId, transportObservedAt));
        if (decision.due() && !decision.enforced()) {
            throw new IllegalStateException(
                    "authority rejected due expiry: " + decision.reason());
        }
    }

    private static String playerPrecondition(
            CommandEnvelope envelope, PlayerPort.Snapshot before) {
        if (!envelope.hasPlayerEffect()) {
            return null;
        }
        if (envelope.expectedPlayerRevision() != before.revision()) {
            return "stale_player_revision";
        }
        if (envelope.command().equals("play") && before.playing()) {
            return "already_playing";
        }
        if (envelope.command().equals("pause") && !before.playing()) {
            return "already_paused";
        }
        if (envelope.command().equals("select_video")
                && envelope.videoId().equals(before.selectedVideoId())) {
            return "already_selected";
        }
        return null;
    }

    private String queryResult(
            CommandEnvelope envelope,
            ManifoldAuthorityPort.CommandDecision decision,
            PlayerPort.Snapshot state) {
        Map<String, Object> body = new LinkedHashMap<>();
        putRevisions(body, decision.revisions());
        body.put("command", envelope.command());
        body.put("event", "command_result");
        body.put("expected_authority_revision", envelope.expectedAuthorityRevision());
        body.put("expected_player_revision", envelope.expectedPlayerRevision());
        body.put("request_id", envelope.requestId());
        switch (envelope.command()) {
            case "describe" -> {
                body.put(
                        "commands",
                        new ArrayList<>(TrustedLocalControlPolicy.COMMANDS).stream()
                                .sorted()
                                .toList());
                body.put("confidentiality", false);
                body.put("protocol", TrustedLocalControlPolicy.PROTOCOL);
            }
            case "get_state" -> body.put("state", stateMap(state, authority.snapshot()));
            case "list_videos" ->
                    body.put(
                            "videos",
                            catalog.videos().stream().map(VideoCatalog.Video::json).toList());
            default -> throw new IllegalStateException("unreachable registered query command");
        }
        return JsonStrings.object(body);
    }

    private static String commandAccepted(
            CommandEnvelope envelope, ManifoldAuthorityPort.CommandDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        putRevisions(body, decision.revisions());
        body.put("command", envelope.command());
        body.put("controller_lease_id", decision.controllerLeaseId());
        body.put("event", "command_accepted");
        body.put("manifold_command_receipt_id", decision.acceptedCommandReceiptId());
        body.put("expected_authority_revision", envelope.expectedAuthorityRevision());
        body.put("expected_player_revision", envelope.expectedPlayerRevision());
        body.put("request_id", envelope.requestId());
        return JsonStrings.object(body);
    }

    private static String commandNotSubmitted(
            CommandEnvelope envelope,
            PlayerPort.Snapshot state,
            ManifoldAuthorityPort.AuthorityRevisions revisions,
            String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        putRevisions(body, revisions);
        body.put("command", envelope.command());
        body.put("event", "command_not_submitted");
        body.put("expected_authority_revision", envelope.expectedAuthorityRevision());
        body.put("expected_player_revision", envelope.expectedPlayerRevision());
        body.put("player_revision", state.revision());
        body.put("reason", reason);
        body.put("request_id", envelope.requestId());
        return JsonStrings.object(body);
    }

    private static String commandRejected(
            CommandEnvelope envelope,
            PlayerPort.Snapshot state,
            ManifoldAuthorityPort.AuthorityRevisions revisions,
            String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        putRevisions(body, revisions);
        body.put("command", envelope.command());
        body.put("event", "command_rejected");
        body.put("expected_authority_revision", envelope.expectedAuthorityRevision());
        body.put("expected_player_revision", envelope.expectedPlayerRevision());
        body.put("player_revision", state.revision());
        body.put("reason", reason);
        body.put("request_id", envelope.requestId());
        return JsonStrings.object(body);
    }

    private static String event(
            String event,
            PlayerPort.AcceptedEffect cause,
            PlayerPort.Snapshot state,
            ManifoldAuthorityPort.AuthorityRevisions revisions,
            String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        putRevisions(body, revisions);
        putAcceptedRevisions(body, cause.acceptedAuthorityRevisions());
        body.put("command", cause.command());
        body.put("event", event);
        body.put("expected_player_revision", cause.expectedPlayerRevision());
        body.put("manifold_command_receipt_id", cause.acceptedCommandReceiptId());
        body.put("player_revision", state.revision());
        body.put("reason", reason);
        body.put("request_id", cause.requestId());
        body.put("state", playerStateMap(state));
        return JsonStrings.object(body);
    }

    private static String stateChanged(
            PlayerPort.AcceptedEffect cause,
            PlayerPort.Snapshot state,
            ManifoldAuthorityPort.AuthorityRevisions revisions) {
        Map<String, Object> body = new LinkedHashMap<>();
        putRevisions(body, revisions);
        putAcceptedRevisions(body, cause.acceptedAuthorityRevisions());
        body.put("caused_by_command_receipt_id", cause.acceptedCommandReceiptId());
        body.put("caused_by_request_id", cause.requestId());
        body.put("event", "state_changed");
        body.put("state", playerStateMap(state));
        return JsonStrings.object(body);
    }

    private static String stateJson(
            PlayerPort.Snapshot state, ManifoldAuthorityPort.AuthoritySnapshot authority) {
        return JsonStrings.object(stateMap(state, authority));
    }

    private static Map<String, Object> stateMap(
            PlayerPort.Snapshot state, ManifoldAuthorityPort.AuthoritySnapshot authority) {
        Map<String, Object> value = new LinkedHashMap<>();
        putRevisions(value, authority.revisions());
        value.put("controller_connected", authority.controllerConnected());
        value.put("controller_id", authority.controllerId());
        value.put("enabled", authority.enabled());
        value.put("idle_expires_at", instantText(authority.idleExpiresAt()));
        value.put(
                "last_accepted_command_receipt_id",
                authority.lastAcceptedCommandReceiptId());
        value.put("player", playerStateMap(state));
        value.put("session_expires_at", instantText(authority.sessionExpiresAt()));
        value.put("state", authority.state());
        value.put("window_expires_at", instantText(authority.windowExpiresAt()));
        value.put("window_id", authority.windowId());
        return value;
    }

    private static void putRevisions(
            Map<String, Object> body, ManifoldAuthorityPort.AuthorityRevisions revisions) {
        body.put("admission_revision", revisions.admissionRevision());
        body.put("authority_revision", revisions.localRevision());
        body.put("host_revision", revisions.hostRevision());
        body.put("lease_authority_revision", revisions.leaseAuthorityRevision());
        body.put("local_revision", revisions.localRevision());
    }

    private static void putAcceptedRevisions(
            Map<String, Object> body, ManifoldAuthorityPort.AuthorityRevisions revisions) {
        body.put("accepted_admission_revision", revisions.admissionRevision());
        body.put("accepted_authority_revision", revisions.localRevision());
        body.put("accepted_host_revision", revisions.hostRevision());
        body.put(
                "accepted_lease_authority_revision",
                revisions.leaseAuthorityRevision());
        body.put("accepted_local_revision", revisions.localRevision());
    }

    private static Map<String, Object> playerStateMap(PlayerPort.Snapshot state) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("playback_state", state.playbackState());
        value.put("playing", state.playing());
        value.put("position_ms", state.positionMs());
        value.put("revision", state.revision());
        value.put("selected_video_id", state.selectedVideoId());
        return value;
    }

    private static String instantText(Instant value) {
        return value == null ? null : value.toString();
    }

    private void expirePendingEffect(PlayerPort.AcceptedEffect expected) {
        synchronized (effectLock) {
            if (pendingEffect == null
                    || !pendingEffect.requestId().equals(expected.requestId())
                    || !pendingEffect
                            .acceptedCommandReceiptId()
                            .equals(expected.acceptedCommandReceiptId())) {
                return;
            }
            pendingEffect = null;
            reservedEffectRequestId = null;
            pendingDeadline = null;
        }
        player.cancelPending(expected);
        emit(
                event(
                        "command_failed",
                        expected,
                        player.snapshot(),
                        authority.snapshot().revisions(),
                        "player_effect_timeout"));
    }

    private void emit(String event) {
        for (EventSink sink : sinks) {
            sink.emit(event);
        }
    }

    private void clearReservation(String requestId) {
        synchronized (effectLock) {
            if (requestId.equals(reservedEffectRequestId)) {
                reservedEffectRequestId = null;
            }
        }
    }

    private boolean clearPending(PlayerPort.AcceptedEffect effect) {
        synchronized (effectLock) {
            if (pendingEffect == null
                    || !pendingEffect.requestId().equals(effect.requestId())
                    || !pendingEffect
                            .acceptedCommandReceiptId()
                            .equals(effect.acceptedCommandReceiptId())) {
                return false;
            }
            pendingEffect = null;
            reservedEffectRequestId = null;
            if (pendingDeadline != null) {
                pendingDeadline.cancel(false);
                pendingDeadline = null;
            }
            return true;
        }
    }
}
