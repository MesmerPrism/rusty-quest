package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
public final class StereoRendererTerminalHost {
    static final String SHA=new String(new char[64]).replace('\0','a');static int checks;
    static JSONObject nativeValue() throws Exception {return new JSONObject().put("schema","rusty.quest.stereo.renderer_terminal_native.v1")
        .put("availability","available").put("graph_sequence",1).put("native_process_generation",7).put("route_generation",7)
        .put("surface_generation",3).put("state","failed").put("failure_phase","import_refresh").put("failure_category","source_binding")
        .put("carrier_cleanup","terminal").put("captured_at_ns",12).put("scope","source_graph_observation_not_authority");}
    static String project(JSONObject value)throws Exception {return StereoRendererTerminalStatus.project(value.toString(),"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","app-process.14.actor",14,2,3,SHA,7);}
    static void denied(JSONObject value)throws Exception {try{project(value);throw new AssertionError("admitted bad diagnostic");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        JSONObject projected=new JSONObject(project(nativeValue()));if(!projected.getString("runtime_join").equals("current_actor_context_only"))throw new AssertionError();checks++;
        for(String key:new String[]{"schema","availability","state","failure_phase","failure_category","carrier_cleanup","scope"})denied(nativeValue().put(key,"other"));
        denied(nativeValue().put("native_process_generation",8));denied(nativeValue().put("graph_sequence",0));denied(nativeValue().put("route_generation",0));
        denied(nativeValue().put("captured_at_ns",-1));denied(nativeValue().put("graph_sequence",1.5));denied(nativeValue().put("secret","payload"));
        denied(nativeValue().put("state","succeeded"));denied(nativeValue().put("state","cleanup_pending"));denied(nativeValue().put("availability","unavailable"));
        JSONObject unavailable=nativeValue().put("availability","unavailable").put("state","unavailable").put("failure_phase","none").put("failure_category","none").put("carrier_cleanup","unknown");
        for(String key:new String[]{"graph_sequence","native_process_generation","route_generation","surface_generation","captured_at_ns"})unavailable.put(key,0);
        project(unavailable);checks++;
        try{StereoRendererTerminalStatus.project(nativeValue().toString(),"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","app-process.15.other",14,2,3,SHA,7);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        System.out.println("Actual Java closed renderer diagnostic projection "+checks+" controls PASS; actor/resource context modeled");
    }
}
