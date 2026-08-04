package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PairingRequest(String pairingCode, String requestId) {
    private static final Pattern CANONICAL =
            Pattern.compile(
                    "\\{\"pairing_code\":\"([0-9]{6})\","
                            + "\"request_id\":\"([a-z0-9][a-z0-9-]{15,63})\"\\}");

    public static PairingRequest parseCanonical(String body) {
        if (body.getBytes(StandardCharsets.UTF_8).length > TrustedLocalControlPolicy.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("pairing body is too large");
        }
        Matcher matcher = CANONICAL.matcher(body);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("pairing body is not canonical");
        }
        PairingRequest request = new PairingRequest(matcher.group(1), matcher.group(2));
        if (!body.equals(request.canonicalJson())) {
            throw new IllegalArgumentException("pairing body is not canonical");
        }
        return request;
    }

    public String canonicalJson() {
        return "{\"pairing_code\":"
                + JsonStrings.quote(pairingCode)
                + ",\"request_id\":"
                + JsonStrings.quote(requestId)
                + "}";
    }
}
