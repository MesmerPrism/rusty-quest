package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Retained real report context plus damaged lineage; no device or fake owner. */
public final class EmbeddedDuplexDropoutLineageHostTest {
    private EmbeddedDuplexDropoutLineageHostTest() {}
    public static void main(String[] arguments) throws Exception {
        JSONObject actual = new JSONObject(new String(Files.readAllBytes(Path.of(arguments[0])), StandardCharsets.UTF_8)).getJSONObject("diagnostic");
        JSONObject stage = actual.getJSONObject("own_capture_stage");
        JSONObject observation = new JSONObject().put("left",stage.getJSONObject("left_frame_trace"))
                .put("right",stage.getJSONObject("right_frame_trace"));
        String epoch=actual.getString("process_epoch_id");
        long app=actual.getLong("app_generation"), arm=actual.getLong("arm_generation");
        EmbeddedDuplexDropoutLineage.requireCurrent(observation,epoch,app,arm);
        String original=observation.toString(); int rejected=0;
        for (String eye:new String[]{"left","right"}) {
            for (String field:new String[]{"process_epoch_id","app_generation","arm_generation","trace_epoch"}) {
                JSONObject changed=new JSONObject(original);
                changed.getJSONObject(eye).put(field,field.equals("process_epoch_id") ? "foreign-process" : 0);
                try {EmbeddedDuplexDropoutLineage.requireCurrent(changed,epoch,app,arm);}
                catch (Exception expected) {rejected++;continue;}
                throw new AssertionError("Stale or missing eye context accepted");
            }
        }
        if (rejected!=8||!original.equals(observation.toString())) throw new AssertionError("Fixture mutated or case omitted");
        System.out.println("PASS: actual retained two-eye lineage and 8 independent rejection cases; no device calls");
    }
}
