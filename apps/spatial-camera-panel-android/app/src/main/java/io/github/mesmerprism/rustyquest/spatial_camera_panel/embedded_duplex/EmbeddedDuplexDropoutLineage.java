package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;

/** Rejects stale per-eye statistics before attaching current owner context. */
final class EmbeddedDuplexDropoutLineage {
    private EmbeddedDuplexDropoutLineage() {}
    static void requireCurrent(JSONObject observation, String epoch, long appGeneration,
            long armGeneration) throws Exception {
        if (epoch == null || epoch.isEmpty() || appGeneration <= 0 || armGeneration <= 0) {
            throw new IllegalStateException("Dropout owner context unavailable");
        }
        for (String eye : new String[] {"left", "right"}) {
            JSONObject trace = observation.getJSONObject(eye);
            if (!epoch.equals(trace.getString("process_epoch_id"))
                    || trace.getLong("app_generation") != appGeneration
                    || trace.getLong("arm_generation") != armGeneration
                    || trace.getLong("trace_epoch") <= 0) {
                throw new IllegalStateException("Dropout eye context differs");
            }
        }
    }
}
