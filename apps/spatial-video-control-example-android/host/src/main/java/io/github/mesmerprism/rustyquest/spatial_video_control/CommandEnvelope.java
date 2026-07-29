package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The network parser accepts only the canonical build-time envelope. Whitespace,
 * reordered keys, duplicate keys, unknown fields, and unregistered commands fail
 * closed.
 */
public record CommandEnvelope(
        String command,
        long expectedAuthorityRevision,
        long expectedPlayerRevision,
        String videoId,
        String requestId) {
    private static final Pattern NO_PAYLOAD =
            Pattern.compile(
                    "\\{\"command\":\"([a-z_]+)\",\"expected_authority_revision\":([0-9]{1,19}),"
                            + "\"expected_player_revision\":([0-9]{1,19}),\"payload\":\\{},"
                            + "\"request_id\":\"([a-z0-9][a-z0-9-]{15,63})\"\\}");
    private static final Pattern VIDEO_PAYLOAD =
            Pattern.compile(
                    "\\{\"command\":\"select_video\",\"expected_authority_revision\":([0-9]{1,19}),"
                            + "\"expected_player_revision\":([0-9]{1,19}),"
                            + "\"payload\":\\{\"video_id\":\"([a-z0-9][a-z0-9-]{1,47})\"\\},"
                            + "\"request_id\":\"([a-z0-9][a-z0-9-]{15,63})\"\\}");

    public CommandEnvelope {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(requestId, "requestId");
        if (!TrustedLocalControlPolicy.COMMANDS.contains(command)) {
            throw new IllegalArgumentException("command is not in the build-time registry");
        }
        if (expectedAuthorityRevision < 1 || expectedPlayerRevision < 0) {
            throw new IllegalArgumentException(
                    "authority revisions must be positive and player revision non-negative");
        }
        if (command.equals("select_video") != (videoId != null)) {
            throw new IllegalArgumentException("only select_video carries video_id");
        }
    }

    public static CommandEnvelope parseCanonical(String body) {
        Objects.requireNonNull(body, "body");
        if (body.getBytes(StandardCharsets.UTF_8).length > TrustedLocalControlPolicy.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("command body is too large");
        }
        Matcher video = VIDEO_PAYLOAD.matcher(body);
        if (video.matches()) {
            CommandEnvelope envelope =
                    new CommandEnvelope(
                            "select_video",
                            parseRevision(video.group(1)),
                            parseRevision(video.group(2)),
                            video.group(3),
                            video.group(4));
            requireCanonical(body, envelope);
            return envelope;
        }
        Matcher empty = NO_PAYLOAD.matcher(body);
        if (!empty.matches()) {
            throw new IllegalArgumentException("command envelope is not canonical");
        }
        String command = empty.group(1);
        if (command.equals("select_video")) {
            throw new IllegalArgumentException("select_video requires video_id");
        }
        CommandEnvelope envelope =
                new CommandEnvelope(
                        command,
                        parseRevision(empty.group(2)),
                        parseRevision(empty.group(3)),
                        null,
                        empty.group(4));
        requireCanonical(body, envelope);
        return envelope;
    }

    public boolean hasPlayerEffect() {
        return command.equals("select_video") || command.equals("play") || command.equals("pause");
    }

    public String canonicalJson() {
        String payload =
                videoId == null
                        ? "{}"
                        : "{\"video_id\":" + JsonStrings.quote(videoId) + "}";
        return "{\"command\":"
                + JsonStrings.quote(command)
                + ",\"expected_authority_revision\":"
                + expectedAuthorityRevision
                + ",\"expected_player_revision\":"
                + expectedPlayerRevision
                + ",\"payload\":"
                + payload
                + ",\"request_id\":"
                + JsonStrings.quote(requestId)
                + "}";
    }

    private static long parseRevision(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("revision is outside the signed 64-bit range", error);
        }
    }

    private static void requireCanonical(String body, CommandEnvelope envelope) {
        if (!body.equals(envelope.canonicalJson())) {
            throw new IllegalArgumentException("command envelope is not canonical");
        }
    }
}
