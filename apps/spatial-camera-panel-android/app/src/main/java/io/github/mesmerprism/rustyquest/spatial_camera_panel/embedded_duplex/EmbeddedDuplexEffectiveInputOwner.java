package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraControlProfileContract;
import org.json.JSONObject;

/** Data holder owned exclusively by ProcessHost's existing command lane. */
final class EmbeddedDuplexEffectiveInputOwner {
    private FrozenEffectiveInputs.Selector selector;
    private FrozenEffectiveInputs current;
    private volatile String observation;

    FrozenEffectiveInputs retained(EmbeddedDuplexPackagedInputs.InstalledRole role, long generation) {
        if (current == null) return null;
        if (current.role() != role || current.generation != generation)
            throw new IllegalStateException("effective_input_owner_epoch_differs");
        current.requireConsumable();
        return current;
    }

    FrozenEffectiveInputs capture(EmbeddedDuplexPackagedInputs base, long generation,
            byte[] profile, FrozenEffectiveInputs.Deriver deriver) throws Exception {
        FrozenEffectiveInputs prior = retained(base.role(), generation);
        if (prior != null) return prior;
        FrozenEffectiveInputs.Selector candidate = new FrozenEffectiveInputs.Selector(base, generation, deriver);
        if (profile != null) {
            // Validate the complete shared envelope before deciding whether an endpoint override exists.
            SpatialCameraControlProfileContract.INSTANCE.parse(profile);
            if (new JSONObject(new String(profile, java.nio.charset.StandardCharsets.UTF_8)).has("peer_network_endpoints"))
                candidate.select(profile);
        }
        FrozenEffectiveInputs accepted = candidate.freeze();
        String readback = candidate.selectionReadback();
        selector = candidate;
        current = accepted;
        observation = new JSONObject().put("$schema", "rusty.quest.embedded_duplex.effective_input_owner_observation.v1")
                .put("phase", "frozen").put("selection", new JSONObject(readback))
                .put("native_configuration", JSONObject.NULL).toString();
        return accepted;
    }

    void nativeConfigured(FrozenEffectiveInputs expected, String actualConfigSha256,
            long executorGeneration, String appRecordSha256) throws Exception {
        if (current != expected || actualConfigSha256 == null || !actualConfigSha256.matches("[0-9a-f]{64}")
                || executorGeneration <= 0 || appRecordSha256 == null || !appRecordSha256.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("effective_input_native_epoch_differs");
        expected.requireConsumable();
        observation = new JSONObject().put("$schema", "rusty.quest.embedded_duplex.effective_input_owner_observation.v1")
                .put("phase", "native_configured").put("selection", new JSONObject(selector.selectionReadback()))
                .put("native_configuration", new JSONObject().put("runtime_config_sha256", actualConfigSha256)
                    .put("executor_generation", executorGeneration).put("app_record_sha256", appRecordSha256))
                .put("socket_liveness_observed", false).toString();
    }

    String observation() { return observation; }

    /** Called only at the existing owner's earned terminal cleanup/reset boundary. */
    void releaseAfterVerifiedCleanup() {
        if (selector != null) selector.retire();
        selector = null;
        current = null;
        observation = null;
    }
}
