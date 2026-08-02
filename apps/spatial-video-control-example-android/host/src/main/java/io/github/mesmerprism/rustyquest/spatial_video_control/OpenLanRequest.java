package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical request to claim the sole controller lease in explicit Open LAN mode. */
public record OpenLanRequest(String requestId) {
    private static final Pattern CANONICAL =
            Pattern.compile("\\{\"request_id\":\"([a-z0-9][a-z0-9-]{15,63})\"\\}");

    public static OpenLanRequest parseCanonical(String body) {
        if (body.getBytes(StandardCharsets.UTF_8).length
                > TrustedLocalControlPolicy.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Open LAN body is too large");
        }
        Matcher matcher = CANONICAL.matcher(body);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Open LAN body is not canonical");
        }
        OpenLanRequest request = new OpenLanRequest(matcher.group(1));
        if (!body.equals(request.canonicalJson())) {
            throw new IllegalArgumentException("Open LAN body is not canonical");
        }
        return request;
    }

    public String canonicalJson() {
        return "{\"request_id\":" + JsonStrings.quote(requestId) + "}";
    }
}
