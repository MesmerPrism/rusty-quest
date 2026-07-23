package io.github.mesmerprism.rustyquest.fleetagent;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class FleetAgentConfig {
    private static final int MAX_PROFILE_BYTES = 64 * 1024;

    final JSONObject profile;
    final URI hubEndpoint;
    final long intervalMs;

    private FleetAgentConfig(JSONObject profile, URI hubEndpoint, long intervalMs) {
        this.profile = profile;
        this.hubEndpoint = hubEndpoint;
        this.intervalMs = intervalMs;
    }

    static FleetAgentConfig load(Context context) throws IOException, JSONException {
        File profileFile = new File(
                new File(context.getFilesDir(), "fleet-agent"),
                "profile.json");
        byte[] bytes = Files.readAllBytes(profileFile.toPath());
        if (bytes.length == 0 || bytes.length > MAX_PROFILE_BYTES) {
            throw new IOException("profile_size_invalid");
        }
        JSONObject profile = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"rusty.quest.fleet_agent_profile.v1".equals(profile.optString("schema"))) {
            throw new JSONException("profile_schema_invalid");
        }
        if (!profile.optBoolean("enabled", false)) {
            throw new JSONException("profile_disabled");
        }
        long intervalMs = profile.getLong("checkin_interval_ms");
        long ttlMs = profile.getLong("checkin_ttl_ms");
        if (intervalMs < 5_000 || intervalMs >= ttlMs || ttlMs > 300_000) {
            throw new JSONException("profile_interval_invalid");
        }
        URI endpoint = parseEndpoint(profile.getString("hub_endpoint"));
        return new FleetAgentConfig(profile, endpoint, intervalMs);
    }

    String runtimeProfile(long sourceRevision, String sourceEpoch) throws JSONException {
        JSONObject runtime = new JSONObject(profile.toString());
        runtime.put("source_revision", sourceRevision);
        runtime.put("source_epoch", sourceEpoch);
        return runtime.toString();
    }

    private static URI parseEndpoint(String value) throws JSONException {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new JSONException("hub_endpoint_invalid");
            }
            if ("https".equalsIgnoreCase(scheme)) {
                return uri;
            }
            if (!"http".equalsIgnoreCase(scheme) || !isLocalAddress(host)) {
                throw new JSONException("cleartext_hub_must_be_local");
            }
            return uri;
        } catch (URISyntaxException error) {
            throw new JSONException("hub_endpoint_invalid");
        }
    }

    private static boolean isLocalAddress(String host) {
        String value = host.toLowerCase();
        if ("localhost".equals(value)
                || value.startsWith("127.")
                || value.startsWith("10.")
                || value.startsWith("192.168.")
                || value.startsWith("169.254.")
                || value.startsWith("fc")
                || value.startsWith("fd")
                || value.startsWith("fe80:")) {
            return true;
        }
        if (!value.startsWith("172.")) {
            return false;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
