package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Coordinates accepted Manifold commands with Quest-owned player effects. */
public final class LocalControlCoordinator implements PlayerPort.Listener {
    public interface EventSink {
        void emit(String canonicalJson);
    }

    private final ManifoldAuthorityPort authority;
    private final PlayerPort player;
    private final VideoCatalog catalog;
    private final CopyOnWriteArrayList<EventSink> sinks = new CopyOnWriteArrayList<>();

    public LocalControlCoordinator(
            ManifoldAuthorityPort authority, PlayerPort player, VideoCatalog catalog) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.player = Objects.requireNonNull(player, "player");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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
        return authority.pair(new ManifoldAuthorityPort.PairAttempt(remoteAddress, request, now));
    }

    public ManifoldAuthorityPort.SessionDecision inspectSession(
            String sessionCookie, String remoteAddress, Instant now) {
        return authority.inspectSession(sessionCookie, remoteAddress, now);
    }

    public void handleCommand(
            String sessionCookie,
            String remoteAddress,
            CommandEnvelope envelope,
            Instant now) {
        PlayerPort.Snapshot before = player.snapshot();
        if (envelope.command().equals("select_video") && !catalog.contains(envelope.videoId())) {
            emit(
                    commandRejected(
                            envelope,
                            before,
                            authority.snapshot(now).authorityRevision(),
                            "unknown_video_id"));
            return;
        }

        ManifoldAuthorityPort.CommandDecision decision =
                authority.review(
                        new ManifoldAuthorityPort.CommandAttempt(
                                sessionCookie, remoteAddress, envelope, before, now));
        if (!decision.accepted()) {
            emit(commandRejected(envelope, before, decision.authorityRevision(), decision.reason()));
            return;
        }

        emit(commandAccepted(envelope, decision));
        if (!envelope.hasPlayerEffect()) {
            emit(queryResult(envelope, decision, before, now));
            return;
        }

        PlayerPort.AcceptedEffect effect =
                new PlayerPort.AcceptedEffect(
                        envelope.requestId(),
                        envelope.command(),
                        envelope.expectedPlayerRevision(),
                        decision.authorityRevision(),
                        envelope.videoId());
        switch (envelope.command()) {
            case "select_video" -> player.selectVideo(effect);
            case "play" -> player.play(effect);
            case "pause" -> player.pause(effect);
            default -> throw new IllegalStateException("unreachable registered effect command");
        }
    }

    @Override
    public void onApplied(PlayerPort.AppliedEffect effect) {
        Instant observedAt = Instant.now();
        ManifoldAuthorityPort.AppliedDecision decision =
                authority.recordApplied(
                        new ManifoldAuthorityPort.AppliedObservation(
                                effect.cause().requestId(),
                                effect.cause().command(),
                                effect.cause().acceptedAuthorityRevision(),
                                effect.state().revision(),
                                observedAt));
        if (!decision.recorded()) {
            emit(
                    event(
                            "command_effect_unrecorded",
                            effect.cause(),
                            effect.state(),
                            decision.authorityRevision(),
                            decision.reason()));
            return;
        }
        emit(
                event(
                        "command_applied",
                        effect.cause(),
                        effect.state(),
                        decision.authorityRevision(),
                        "applied_from_player_callback"));
        emit(stateChanged(effect.cause(), effect.state(), decision.authorityRevision()));
    }

    @Override
    public void onFailed(PlayerPort.AcceptedEffect effect, String reason) {
        emit(
                event(
                        "command_failed",
                        effect,
                        player.snapshot(),
                        authority.snapshot(Instant.now()).authorityRevision(),
                        reason));
    }

    public String visibleState(Instant now) {
        return stateJson(player.snapshot(), authority.snapshot(now));
    }

    private String queryResult(
            CommandEnvelope envelope,
            ManifoldAuthorityPort.CommandDecision decision,
            PlayerPort.Snapshot state,
            Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authority_revision", decision.authorityRevision());
        body.put("command", envelope.command());
        body.put("event", "command_result");
        body.put("expected_authority_revision", envelope.expectedAuthorityRevision());
        body.put("expected_player_revision", envelope.expectedPlayerRevision());
        body.put("request_id", envelope.requestId());
        switch (envelope.command()) {
            case "describe" -> {
                body.put("commands", new ArrayList<>(TrustedLocalControlPolicy.COMMANDS).stream().sorted().toList());
                body.put("confidentiality", false);
                body.put("protocol", TrustedLocalControlPolicy.PROTOCOL);
            }
            case "get_state" ->
                    body.put("state", stateMap(state, authority.snapshot(now)));
            case "list_videos" ->
                    body.put("videos", catalog.videos().stream().map(VideoCatalog.Video::json).toList());
            default -> throw new IllegalStateException("unreachable registered query command");
        }
        return JsonStrings.object(body);
    }

    private static String commandAccepted(
            CommandEnvelope envelope, ManifoldAuthorityPort.CommandDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authority_revision", decision.authorityRevision());
        body.put("command", envelope.command());
        body.put("controller_lease_id", decision.controllerLeaseId());
        body.put("event", "command_accepted");
        body.put("expected_authority_revision", envelope.expectedAuthorityRevision());
        body.put("expected_player_revision", envelope.expectedPlayerRevision());
        body.put("request_id", envelope.requestId());
        return JsonStrings.object(body);
    }

    private static String commandRejected(
            CommandEnvelope envelope,
            PlayerPort.Snapshot state,
            long authorityRevision,
            String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authority_revision", authorityRevision);
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
            long authorityRevision,
            String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authority_revision", authorityRevision);
        body.put("command", cause.command());
        body.put("event", event);
        body.put("expected_player_revision", cause.expectedPlayerRevision());
        body.put("player_revision", state.revision());
        body.put("reason", reason);
        body.put("request_id", cause.requestId());
        body.put("state", playerStateMap(state));
        return JsonStrings.object(body);
    }

    private static String stateChanged(
            PlayerPort.AcceptedEffect cause,
            PlayerPort.Snapshot state,
            long authorityRevision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authority_revision", authorityRevision);
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
        value.put("authority_revision", authority.authorityRevision());
        value.put("controller_connected", authority.controllerConnected());
        value.put("controller_label", authority.controllerLabel());
        value.put("enabled", authority.enabled());
        value.put("player", playerStateMap(state));
        return value;
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

    private void emit(String event) {
        for (EventSink sink : sinks) {
            sink.emit(event);
        }
    }
}
