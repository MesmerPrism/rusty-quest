package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual cache getter/Observation/run join/projector, with only owner container/OS leaves modeled. */
public final class CachedEffectiveInputsHost {
    static int checks;
    static final String RUN="b".repeat(32), CHALLENGE="a".repeat(32), BOOT="11111111-2222-3333-4444-555555555555";
    static final EmbeddedDuplexCachedInputs.Actor ACTOR=new EmbeddedDuplexCachedInputs.Actor(42,10001,"2345",BOOT);
    static void check(boolean value){if(!value)throw new AssertionError("control "+checks);checks++;}
    interface Action { void run() throws Exception; }
    static void refuse(Action action)throws Exception{try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError("expected refusal");}
    static JSONObject read()throws Exception{return new JSONObject(EmbeddedDuplexCachedInputs.read(CHALLENGE,RUN,ACTOR,ACTOR));}
    static JSONObject cache()throws Exception{
        JSONArray peers=new JSONArray();
        for(int i=0;i<2;i++)peers.put(new JSONObject().put("peer_id","peer."+i).put("private_key","secret-value")
            .put("media_endpoint",new JSONObject().put("protocol","rmanvid-v4-packed-stereo").put("host","10.1.0."+(11+i)).put("port",5000+i))
            .put("control_endpoint",new JSONObject().put("protocol","rqod1").put("host","10.1.0."+(11+i)).put("port",6000+i)));
        return new JSONObject().put("$schema","rusty.quest.embedded_duplex.effective_input_owner_observation.v1")
            .put("phase","frozen").put("operational_run_id",RUN).put("secret","secret-value")
            .put("selection",new JSONObject().put("$schema","rusty.quest.embedded_duplex.effective_input_selection.v1")
                .put("phase","frozen").put("generation",27).put("installed_role_id","peer_a").put("peers",peers)
                .put("lineage",new JSONObject().put("$schema","rusty.quest.embedded_duplex.effective_input_lineage.v1")
                    .put("base_manifest_sha256","c".repeat(64)).put("base_route_sha256","d".repeat(64))
                    .put("profile_sha256",JSONObject.NULL).put("effective_route_sha256","d".repeat(64))))
            .put("native_configuration",JSONObject.NULL);
    }
    public static void main(String[] args)throws Exception{
        EmbeddedDuplexProcessHost.instance=null;
        JSONObject empty=read();check(empty.getString("phase").equals("uninitialized"));
        check(EmbeddedDuplexProcessHost.instance==null);check(empty.isNull("selected_run_id"));
        check(!empty.getBoolean("socket_liveness_observed"));
        EmbeddedDuplexProcessHost host=new EmbeddedDuplexProcessHost();EmbeddedDuplexProcessHost.instance=host;
        EmbeddedDuplexRunContext run=new EmbeddedDuplexRunContext(new File("app"),new File("run"),RUN,3);
        host.runContext=run;
        check(read().getString("phase").equals("uninitialized"));check(host.effectiveInputObservationSnapshot==null);
        JSONObject original=cache();String before=original.toString();
        host.effectiveInputObservationSnapshot=new EmbeddedDuplexEffectiveInputOwner.Observation(before,run);
        JSONObject frozen=read();check(frozen.getString("phase").equals("frozen"));
        check(frozen.getJSONObject("selection").getString("source").equals("packaged"));
        check(frozen.getJSONObject("selection").getJSONArray("peers").length()==2);
        check(!frozen.toString().contains("secret-value"));check(original.toString().equals(before));
        check(frozen.getLong("run_selection_generation")==3 && frozen.getJSONObject("process").getInt("uid")==10001);
        original.getJSONObject("selection").getJSONObject("lineage").put("profile_sha256","e".repeat(64));
        original.put("phase","native_configured").put("native_configuration",new JSONObject()
            .put("runtime_config_sha256","f".repeat(64)).put("executor_generation",81).put("app_record_sha256","a".repeat(64))
            .put("private_path","secret-value"));
        host.effectiveInputObservationSnapshot=new EmbeddedDuplexEffectiveInputOwner.Observation(original.toString(),run);
        JSONObject configured=read();check(configured.getString("phase").equals("native_configured"));
        check(configured.getJSONObject("selection").getString("source").equals("control_profile"));
        check(configured.getJSONObject("native_configuration").getLong("executor_generation")==81);
        check(!configured.toString().contains("private_path"));
        if(args.length==1)java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),configured.toString());
        for(EmbeddedDuplexCachedInputs.Actor wrong:new EmbeddedDuplexCachedInputs.Actor[]{
            new EmbeddedDuplexCachedInputs.Actor(43,10001,"2345",BOOT),new EmbeddedDuplexCachedInputs.Actor(42,10002,"2345",BOOT),
            new EmbeddedDuplexCachedInputs.Actor(42,10001,"2346",BOOT),
            new EmbeddedDuplexCachedInputs.Actor(42,10001,"2345","22222222-2222-3333-4444-555555555555")})
            refuse(()->EmbeddedDuplexCachedInputs.read(CHALLENGE,RUN,wrong,ACTOR));
        refuse(()->EmbeddedDuplexCachedInputs.read(CHALLENGE,"c".repeat(32),ACTOR,ACTOR));
        refuse(()->EmbeddedDuplexCachedInputs.read("bad",RUN,ACTOR,ACTOR));
        EmbeddedDuplexRunContext changed=new EmbeddedDuplexRunContext(new File("app"),new File("other"),RUN,4);
        host.runContext=changed;check(read().getString("phase").equals("uninitialized")); // stale cache cannot join a new selector.
        host.runContext=run;host.effectiveInputObservationSnapshot=null;
        check(read().getString("phase").equals("uninitialized")); // released cache is not recreated.
        JSONObject oversized=cache().put("unused_secret","x".repeat(65537));
        host.effectiveInputObservationSnapshot=new EmbeddedDuplexEffectiveInputOwner.Observation(oversized.toString(),run);
        refuse(()->read());
        JSONObject bad=cache();bad.getJSONObject("selection").getJSONObject("lineage").put("profile_sha256","not-a-hash");
        host.effectiveInputObservationSnapshot=new EmbeddedDuplexEffectiveInputOwner.Observation(bad.toString(),run);refuse(()->read());
        System.out.println("PASS cached input readback "+checks+" controls: uninitialized inertness, current join, cached phases, whitelist, bounds, secrets and identity refusals");
    }
}
