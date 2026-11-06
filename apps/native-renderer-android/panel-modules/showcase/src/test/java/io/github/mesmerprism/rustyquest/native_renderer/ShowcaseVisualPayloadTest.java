package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;

/** Calls the exact production Apply handler's payload adapter without creating a UI. */
public final class ShowcaseVisualPayloadTest {
    public static void main(String[] args)throws Exception {
        JSONObject visual=new JSONObject().put("visual_scale",0.7).put("tracer",new JSONObject().put("draw_slots_per_oscillator",7));
        JSONObject payload=ShowcaseControls.visualPayload(visual);
        if(payload.length()!=1||!payload.has("visual"))throw new AssertionError("Apply must use the closed native visual envelope");
        if(!payload.getJSONObject("visual").similar(visual))throw new AssertionError("Apply must preserve the entire edited visual snapshot");
        if(visual.has("visual"))throw new AssertionError("Apply must not mutate or recursively wrap the form snapshot");
        visual.put("visual_scale",0.9);
        if(payload.getJSONObject("visual").getDouble("visual_scale")!=0.7)throw new AssertionError("Apply payload changed after the draft was edited again");
        System.out.println("PASS production visual Apply payload");
    }
}
