package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Source-neutral projection of Rust-prepared media actions.
 *
 * <p>This class deliberately owns no product source, sink, codec, socket, or
 * consent policy. A product adapter may execute the exact owner actions and
 * complete it inside their own Rust/NDK provider runtime. This generic broker
 * exposes no completion callback because it cannot observe external provider
 * handles or app-local render adoption.</p>
 */
final class GenericMediaSessionPlatformAdapter {
    private static final String ACTION_SCHEMA =
            "rusty.quest.media_stream_platform_action.v1";
    private GenericMediaSessionPlatformAdapter() {
    }

    static JSONObject reportPreparedAction(JSONObject authorityResponse) throws Exception {
        if (authorityResponse.optBoolean("platform_effect_completed", true)) {
            throw new IllegalStateException("command acceptance cannot complete media effect");
        }
        JSONObject action = authorityResponse.optJSONObject("platform_action");
        JSONObject report = new JSONObject();
        report.put("runtime_family", "generic_media_session");
        report.put("platform_effect_completed", false);
        report.put("completion_synthesized", false);
        report.put("remote_camera_compatibility", false);
        if (action == null) {
            report.put("status", "platform_action_not_prepared");
            report.put("prepare_error",
                    authorityResponse.optString("platform_prepare_error", "missing_platform_action"));
            return report;
        }
        validateAction(action);
        report.put("status", "awaiting_product_owner_completions");
        report.put("action_id", action.getString("action_id"));
        report.put("authority_epoch_id", action.getString("authority_epoch_id"));
        report.put("runtime_spec_id", action.getString("runtime_spec_id"));
        report.put("operation", action.getString("operation"));
        report.put("owner_action_count", action.getJSONArray("owner_actions").length());
        return report;
    }

    private static void validateAction(JSONObject action) throws Exception {
        if (!ACTION_SCHEMA.equals(action.optString("$schema", ""))
                || action.optString("action_id", "").isEmpty()
                || action.optString("authority_epoch_id", "").isEmpty()
                || action.optString("runtime_spec_id", "").isEmpty()
                || action.optString("runtime_spec_canonical_sha256", "").isEmpty()
                || action.optString("manifold_descriptor_canonical_sha256", "").isEmpty()) {
            throw new IllegalStateException("Rust generic media action binding mismatch");
        }
        JSONObject clientAuthority = action.getJSONObject("client_authority");
        if (clientAuthority.optString("client_id", "").isEmpty()
                || clientAuthority.optString("lease_id", "").isEmpty()) {
            throw new IllegalStateException("Rust generic media client/lease binding missing");
        }
        JSONArray owners = action.getJSONArray("owner_actions");
        Set<String> families = new HashSet<>();
        for (int index = 0; index < owners.length(); index++) {
            JSONObject ownerAction = owners.getJSONObject(index);
            JSONObject selection = ownerAction.getJSONObject("selection");
            String family = selection.optString("owner_kind", "");
            String serialized = selection.toString();
            if (family.isEmpty()
                    || selection.optString("owner_id", "").isEmpty()
                    || selection.optString("resource_id", "").isEmpty()
                    || selection.optString("provider_kind", "").isEmpty()
                    || ownerAction.optString("action_kind", "").isEmpty()
                    || serialized.contains("remote_camera")
                    || serialized.contains("remote-camera")) {
                throw new IllegalStateException("generic media owner action is damaged or leaked");
            }
            families.add(family);
        }
        for (String required : new String[]{
                "source", "processor", "route", "socket", "codec", "sink", "cleanup"}) {
            if (!families.contains(required)) {
                throw new IllegalStateException("generic media owner family missing: " + required);
            }
        }
    }
}
