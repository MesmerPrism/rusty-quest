package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only projection of an already published cache. Never creates a ProcessHost. */
public final class EmbeddedDuplexCachedInputs {
    private EmbeddedDuplexCachedInputs() {}
    public static final String SCHEMA = "rusty.quest.embedded_duplex.cached_effective_inputs.v1";
    static final int MAX_BYTES = 64 * 1024;
    static final class Snapshot {
        final String runId, payload;
        final long selectionGeneration;
        Snapshot(String runId,long selectionGeneration,String payload) {
            this.runId=runId;this.selectionGeneration=selectionGeneration;this.payload=payload;
        }
    }
    static final class Actor {
        final int pid,uid;
        final String startTicks,bootId;
        Actor(int pid,int uid,String startTicks,String bootId) {
            if(pid<=0 || uid<0 || startTicks==null || !startTicks.matches("[1-9][0-9]{0,19}") || bootId==null
                    || !bootId.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("cached_input_actor_invalid");
            this.pid=pid;this.uid=uid;this.startTicks=startTicks;this.bootId=bootId;
        }
        boolean same(Actor other) {
            return other!=null && pid==other.pid && uid==other.uid && startTicks.equals(other.startTicks) && bootId.equals(other.bootId);
        }
        static Actor actual() throws Exception {
            String stat=readSmall("/proc/self/stat");int boundary=stat.lastIndexOf(") ");
            if(boundary<0)throw new IllegalStateException("cached_input_process_unavailable");
            String[] fields=stat.substring(boundary+2).trim().split(" +");
            if(fields.length<20)throw new IllegalStateException("cached_input_process_unavailable");
            return new Actor(android.os.Process.myPid(),android.os.Process.myUid(),fields[19],
                    readSmall("/proc/sys/kernel/random/boot_id").trim());
        }
    }
    private static String readSmall(String path) throws Exception {
        try(FileInputStream input=new FileInputStream(path)) {
            byte[] bytes=new byte[4097];int length=0;
            while(length<bytes.length) {int n=input.read(bytes,length,bytes.length-length);if(n<0)break;length+=n;}
            if(length==bytes.length)throw new IllegalStateException("cached_input_process_bounds");
            return new String(bytes,0,length,StandardCharsets.UTF_8);
        }
    }
    public static String read(String challenge,String runId,int pid,int uid,String startTicks,String bootId) throws Exception {
        return read(challenge,runId,new Actor(pid,uid,startTicks,bootId),Actor.actual());
    }
    static String read(String challenge,String runId,Actor expected,Actor actual) throws Exception {
        if(challenge==null || !challenge.matches("[0-9a-f]{32}") || runId==null || !runId.matches("[0-9a-f]{32}")
                || expected==null || !expected.same(actual))throw new IllegalArgumentException("cached_input_request_differs");
        return project(challenge,runId,actual,EmbeddedDuplexProcessHost.cachedEffectiveInputs(runId));
    }
    private static String hash(JSONObject value,String name) throws Exception {
        Object raw=value.get(name);
        if(!(raw instanceof String) || !((String)raw).matches("[0-9a-f]{64}"))throw new IllegalStateException("cached_input_hash_invalid");
        return (String)raw;
    }
    private static String token(JSONObject value,String name) throws Exception {
        Object raw=value.get(name);
        if(!(raw instanceof String) || !((String)raw).matches("[a-zA-Z0-9_.-]{1,128}"))throw new IllegalStateException("cached_input_token_invalid");
        return (String)raw;
    }
    private static JSONObject endpoint(JSONObject value,String protocol) throws Exception {
        String host=value.getString("host");String[] octets=host.split("\\.",-1);
        if(octets.length!=4 || !protocol.equals(value.getString("protocol")))throw new IllegalStateException("cached_input_endpoint_invalid");
        for(String octet:octets)if(!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet)>255)
            throw new IllegalStateException("cached_input_endpoint_invalid");
        Object port=value.get("port");
        if(!(port instanceof Integer) || (Integer)port<1 || (Integer)port>65535)throw new IllegalStateException("cached_input_endpoint_invalid");
        return new JSONObject().put("protocol",protocol).put("host",host).put("port",port);
    }
    static String project(String challenge,String runId,Actor actor,Snapshot snapshot) throws Exception {
        JSONObject result=new JSONObject().put("$schema",SCHEMA).put("challenge",challenge)
                .put("requested_run_id",runId).put("observation_scope","cached_only")
                .put("process",new JSONObject().put("pid",actor.pid).put("uid",actor.uid)
                    .put("start_ticks",actor.startTicks).put("boot_id",actor.bootId))
                .put("selected_run_id",snapshot.runId==null?JSONObject.NULL:snapshot.runId)
                .put("run_selection_generation",snapshot.selectionGeneration)
                .put("phase","uninitialized").put("selection",JSONObject.NULL).put("native_configuration",JSONObject.NULL)
                .put("socket_liveness_observed",false);
        if(snapshot.payload!=null) {
            if(snapshot.payload.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalStateException("cached_input_payload_bounds");
            JSONObject cached=new JSONObject(snapshot.payload);String phase=cached.getString("phase");
            if(!"rusty.quest.embedded_duplex.effective_input_owner_observation.v1".equals(cached.getString("$schema"))
                    || !("frozen".equals(phase)||"native_configured".equals(phase)) || !runId.equals(snapshot.runId)
                    || !runId.equals(cached.getString("operational_run_id")))throw new IllegalStateException("cached_input_owner_differs");
            JSONObject selected=cached.getJSONObject("selection"),source=selected.getJSONObject("lineage"),lineage=new JSONObject();
            if(!"rusty.quest.embedded_duplex.effective_input_selection.v1".equals(selected.getString("$schema"))
                    || !"frozen".equals(selected.getString("phase")) || !source.has("profile_sha256")
                    || !"rusty.quest.embedded_duplex.effective_input_lineage.v1".equals(source.getString("$schema")))
                throw new IllegalStateException("cached_input_selection_invalid");
            for(String key:new String[]{"base_manifest_sha256","base_route_sha256","effective_route_sha256"})lineage.put(key,hash(source,key));
            boolean packaged=source.isNull("profile_sha256");
            lineage.put("profile_sha256",packaged?JSONObject.NULL:hash(source,"profile_sha256"));
            JSONArray peers=selected.getJSONArray("peers"),endpoints=new JSONArray();
            if(peers.length()!=2)throw new IllegalStateException("cached_input_peer_count");
            for(int i=0;i<peers.length();i++) {
                JSONObject peer=peers.getJSONObject(i);
                endpoints.put(new JSONObject().put("peer_id",token(peer,"peer_id"))
                    .put("media_endpoint",endpoint(peer.getJSONObject("media_endpoint"),"rmanvid-v4-packed-stereo"))
                    .put("control_endpoint",endpoint(peer.getJSONObject("control_endpoint"),"rqod1")));
            }
            long generation=selected.getLong("generation");
            if(generation<=0)throw new IllegalStateException("cached_input_generation_invalid");
            result.put("phase",phase).put("selection",new JSONObject().put("generation",generation)
                .put("installed_role_id",token(selected,"installed_role_id"))
                .put("source",packaged?"packaged":"control_profile").put("lineage",lineage).put("peers",endpoints));
            if("native_configured".equals(phase)) {
                JSONObject nativeState=cached.getJSONObject("native_configuration");long executor=nativeState.getLong("executor_generation");
                if(executor<=0)throw new IllegalStateException("cached_input_executor_invalid");
                result.put("native_configuration",new JSONObject().put("runtime_config_sha256",hash(nativeState,"runtime_config_sha256"))
                    .put("executor_generation",executor).put("app_record_sha256",hash(nativeState,"app_record_sha256")));
            }
        }
        String encoded=EmbeddedDuplexCanonicalJson.encode(result);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalStateException("cached_input_result_bounds");
        return encoded;
    }
}
