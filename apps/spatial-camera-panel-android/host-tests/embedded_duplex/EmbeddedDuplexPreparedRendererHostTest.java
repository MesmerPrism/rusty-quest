package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Actual Native projection bodies feed the whole Java boundary; no SDK authority. */
public final class EmbeddedDuplexPreparedRendererHostTest {
    private static int cases;
    private static final String ID = "1".repeat(32);

    private static void ready(JSONObject value) throws Exception {
        if (!EmbeddedDuplexPreparedRenderer.dormant(value.toString(), ID, 9)) {
            throw new AssertionError("actual ready producer was not dormant");
        }
        cases++;
    }

    private static void reject(JSONObject value) throws Exception {
        try {
            EmbeddedDuplexPreparedRenderer.dormant(value.toString(), ID, 9);
            throw new AssertionError("changed actual renderer witness was accepted");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            cases++;
        }
    }

    private static JSONObject copy(JSONObject value) {
        return new JSONObject(value.toString());
    }

    public static void main(String[] args) throws Exception {
        JSONObject nativeValues = new JSONObject(Files.readString(Path.of(args[0])));
        JSONObject own = nativeValues.getJSONObject("own");
        JSONObject cold = nativeValues.getJSONObject("cold");
        // Native reuse releases the construction hold; its actual Peer
        // reservation and existing Own resource lifetime remain distinct.
        ready(own);
        ready(cold);
        ready(copy(own).put("prepare_calls_joined", true));
        ready(copy(cold).put("prepare_calls_joined", true));
        reject(copy(own).put("source_hold_released", false));
        reject(copy(cold).put("source_hold_released", true));
        reject(copy(own).put("source_hold_released", "true"));
        reject(copy(cold).put("source_hold_released", "false"));
        reject(copy(own).put("$schema", EmbeddedDuplexPreparedRenderer.COLD));
        reject(copy(cold).put("$schema", EmbeddedDuplexPreparedRenderer.OWN));
        reject(copy(own).put("$schema", "unknown.renderer.schema"));
        for (JSONObject value : new JSONObject[] { own, cold }) {
            reject(copy(value).put("source_hold_acquired", false));
            reject(copy(value).put("prepare_calls_joined", "true"));
            reject(copy(value).put("preparation_id", "2".repeat(32)));
            reject(copy(value).put("unexpected", true));
            JSONObject actor = new JSONObject().put("app_process_generation", value.getLong("app_process_generation"))
                .put("app_record_sha256", value.getString("app_record_sha256"))
                .put("native_executor_generation", value.getLong("native_executor_generation"))
                .put("config_sha256", value.getString("config_sha256"))
                .put("preparation_id", ID).put("resource_target_sha256", value.getString("resource_target_sha256"))
                .put("resource_target", new JSONObject().put("own_capture_selected", true));
            EmbeddedDuplexPreparedRenderer.identity(value, actor, value.getString("request_sha256"));
            cases++;
            try {
                EmbeddedDuplexPreparedRenderer.identity(value, copy(actor).put("native_executor_generation", 61), value.getString("request_sha256"));
                throw new AssertionError("foreign native actor accepted");
            } catch (IllegalArgumentException expected) { cases++; }
        }
        reject(copy(own).put("peer_route_generation", 10));
        reject(copy(cold).put("route_generation", 10));
        for (String field : new String[] {"peer_gpu_worker_created", "peer_reservation_released", "own_resources_retired"}) {
            reject(copy(own).put(field, true));
        }
        for (String field : new String[] {"peer_frame_import_count", "peer_present_count"}) {
            reject(copy(own).put(field, 1));
        }
        for (String field : new String[] {"own_process_generation", "own_source_generation"}) {
            reject(copy(own).put(field, 0));
        }
        reject(copy(own).put("state", "committed"));
        reject(copy(cold).put("failure", "fixture construction failed"));
        reject(copy(cold).put("frame_work_admitted", true));
        reject(copy(cold).put("frame_import_count", 1));
        reject(copy(cold).put("present_count", 1));
        reject(copy(cold).put("wsi_ready", false));
        reject(copy(cold).put("worker_joined", true));
        reject(copy(cold).put("cleanup_terminal", true));
        reject(copy(cold).put("actual_width", 0));
        JSONObject terminalOwn = copy(own).put("state", "released").put("peer_reservation_released", true)
            .put("prepare_calls_joined", true);
        if (!EmbeddedDuplexPreparedRenderer.terminal(terminalOwn.toString(), ID)) {
            throw new AssertionError("actual Own reservation release refused");
        }
        cases++;
        if (EmbeddedDuplexPreparedRenderer.terminal(copy(terminalOwn).put("prepare_calls_joined", false).toString(), ID)
            || EmbeddedDuplexPreparedRenderer.terminal(copy(terminalOwn).put("source_hold_released", false).toString(), ID)) {
            throw new AssertionError("unjoined/held cleanup was certified");
        }
        cases += 2;
        System.out.println("PASS " + cases + " actual renderer contract controls");
    }
}
