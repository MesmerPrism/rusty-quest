package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.util.Arrays;
import java.util.HashSet;
import org.json.JSONObject;

/** Read-only same-process graph observation; no current media/cleanup authority. */
public final class StereoRendererTerminalStatus {
    private StereoRendererTerminalStatus() {}
    private static native String nativeRead(long expectedNativeProcess);
    private static native long nativeCurrentProcess();
    static String read(String challenge, String epoch, long app, long executor,
            long attachment, String config) throws Exception {
        long nativeProcess = nativeCurrentProcess();
        String result = project(nativeRead(nativeProcess),challenge,epoch,app,executor,attachment,config,nativeProcess);
        if (nativeCurrentProcess() != nativeProcess) throw new IllegalArgumentException("renderer diagnostic native actor changed");
        return result;
    }
    static String project(String raw, String challenge, String epoch, long app, long executor,
            long attachment, String config, long nativeProcess) throws Exception {
        if (challenge==null || !challenge.matches("[0-9a-f]{32}") || app<=0 || executor<=0
                || attachment<=0 || nativeProcess<=0 || epoch==null || !epoch.startsWith("app-process."+app+".")
                || config==null || !config.matches("[0-9a-f]{64}") || raw==null || raw.length()>4096)
            throw new IllegalArgumentException("renderer diagnostic actor context unavailable");
        JSONObject value=new JSONObject(raw);
        HashSet<String> keys=new HashSet<>(Arrays.asList("schema","availability","graph_sequence","native_process_generation",
                "route_generation","surface_generation","state","failure_phase","failure_category","carrier_cleanup","captured_at_ns","scope"));
        HashSet<String> observedKeys = new HashSet<>();
        java.util.Iterator<String> iterator = value.keys();
        while (iterator.hasNext()) observedKeys.add(iterator.next());
        if (!observedKeys.equals(keys) || !"rusty.quest.stereo.renderer_terminal_native.v1".equals(value.getString("schema"))
                || !"source_graph_observation_not_authority".equals(value.getString("scope")))
            throw new IllegalArgumentException("renderer diagnostic native contract differs");
        boolean available="available".equals(value.getString("availability"));
        if (!available && !"unavailable".equals(value.getString("availability"))) throw new IllegalArgumentException("renderer availability differs");
        for(String key:new String[]{"graph_sequence","native_process_generation","route_generation","surface_generation","captured_at_ns"})
            if (!(value.get(key) instanceof Integer || value.get(key) instanceof Long) || value.getLong(key)<0) throw new IllegalArgumentException("renderer scalar differs");
        if (available && (value.getLong("native_process_generation")!=nativeProcess || value.getLong("graph_sequence")<=0 || value.getLong("route_generation")<=0))
            throw new IllegalArgumentException("renderer graph actor differs");
        if (!Arrays.asList("unavailable","running","succeeded","failed","cleanup_pending").contains(value.getString("state"))
                || !Arrays.asList("none","seed","import_refresh","command_record","wsi_acquire","queue_submit","sdk_retirement","physical_cleanup","panic","native_other").contains(value.getString("failure_phase"))
                || !Arrays.asList("none","control_changed","source_binding","resource_or_driver","retirement_pending","panic","other_native").contains(value.getString("failure_category"))
                || !Arrays.asList("unknown","pending","terminal").contains(value.getString("carrier_cleanup")))
            throw new IllegalArgumentException("renderer closed code differs");
        if (available == "unavailable".equals(value.getString("state"))
                || (!available && (value.getLong("graph_sequence")!=0 || value.getLong("native_process_generation")!=0
                    || value.getLong("route_generation")!=0 || value.getLong("surface_generation")!=0
                    || value.getLong("captured_at_ns")!=0 || !"none".equals(value.getString("failure_phase"))
                    || !"none".equals(value.getString("failure_category")) || !"unknown".equals(value.getString("carrier_cleanup")))))
            throw new IllegalArgumentException("renderer availability fields differ");
        if (("succeeded".equals(value.getString("state")) && (!"terminal".equals(value.getString("carrier_cleanup")) || !"none".equals(value.getString("failure_category"))))
                || ("failed".equals(value.getString("state")) && (!"terminal".equals(value.getString("carrier_cleanup")) || "none".equals(value.getString("failure_category"))))
                || ("cleanup_pending".equals(value.getString("state")) && "terminal".equals(value.getString("carrier_cleanup"))))
            throw new IllegalArgumentException("renderer outcome fields differ");
        return new JSONObject().put("schema","rusty.quest.stereo.renderer_terminal_status.v1")
                .put("challenge",challenge).put("process_epoch_id",epoch).put("app_process_generation",app)
                .put("native_executor_generation",executor).put("attachment_generation",attachment)
                .put("runtime_config_sha256",config).put("runtime_join","current_actor_context_only")
                .put("observation",value).put("scope","source_graph_failure_observation_not_media_or_cleanup_authority").toString();
    }
}
