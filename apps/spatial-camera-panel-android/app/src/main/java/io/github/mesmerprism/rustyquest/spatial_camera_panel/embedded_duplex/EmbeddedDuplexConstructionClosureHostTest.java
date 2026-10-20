package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** UNRUN. Source/owner controls; platform terminal facts are explicitly modeled.
 * runOwnedFilesystemControls requires the owning Android/host IO harness, never
 * an operator/device call. No control here claims actual camera/native cleanup. */
public final class EmbeddedDuplexConstructionClosureHostTest {
    interface Checked {void run()throws Exception;}
    static void need(boolean condition){if(!condition)throw new AssertionError("construction custody control failed");}
    static void refused(Checked operation)throws Exception{
        try{operation.run();}catch(IllegalStateException expected){return;}
        throw new AssertionError("damaged construction custody accepted");
    }
    static JSONObject body()throws Exception{
        JSONObject broker=new JSONObject().put("$schema","rusty.quest.broker.construction_custody.v1").put("factory_entered",true)
            .put("peer_owner_created",true).put("broker_owner_created",false).put("peer_source",new JSONObject().put("MODELED_SOURCE",true))
            .put("broker_source",JSONObject.NULL).put("control_lease_source",JSONObject.NULL).put("unpublished_source",JSONObject.NULL)
            .put("writable_restore","unsupported_preserve_actual_history");
        JSONObject source=new JSONObject().put("$schema","rusty.quest.embedded_duplex.partial_construction_source.v1")
            .put("attempt",1L).put("provider_entered",true).put("own_preparation",0).put("broker",broker).put("provider",JSONObject.NULL)
            .put("owner_dispatch_replay",JSONObject.NULL).put("activation_replay",JSONObject.NULL).put("cleanup_replay",JSONObject.NULL)
            .put("cleanup_preparations",JSONObject.NULL).put("host_published",false).put("new_owner_effects_dispatched",0L);
        return new JSONObject().put("$schema",EmbeddedDuplexConstructionClosure.BODY).put("app_process_generation",11L)
            .put("app_record_sha256","a".repeat(64)).put("native_executor_generation",12L).put("config_sha256","b".repeat(64))
            .put("source",source).put("construction_disposition","not_committed").put("new_media_starts",0L)
            .put("physical_source_terminal",true).put("old_outcomes_preserved",true)
            .put("future_writable_restore","fenced_pending_supported_owner_reconciliation");
    }
    static JSONObject actor(JSONObject value)throws Exception{
        JSONObject result=new JSONObject();for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})result.put(key,value.get(key));return result;
    }
    public static void runOwnedFilesystemControls(Context actualContext)throws Exception{
        EmbeddedDuplexConstructionClosure writer=new EmbeddedDuplexConstructionClosure(actualContext);JSONObject body=body();
        String raw=body.toString();JSONObject actual=actor(body);String ackRaw=writer.retain(raw,actual);
        JSONObject ack=new JSONObject(ackRaw);need(ack.getString("body_sha256").equals("sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(raw.getBytes(StandardCharsets.UTF_8))));
        need(writer.load().equals(raw));need(new JSONObject(writer.retain(raw,actual)).getString("body_sha256").equals(ack.getString("body_sha256")));
        JSONObject changed=new JSONObject(raw).put("new_media_starts",1);refused(()->writer.retain(changed.toString(),actual));
        JSONObject changedActor=actor(body).put("native_executor_generation",99L);refused(()->writer.retain(raw,changedActor));
        JSONObject changedSource=new JSONObject(raw);changedSource.getJSONObject("source").put("attempt",2L);refused(()->writer.retain(changedSource.toString(),actual));
        JSONObject proof=new JSONObject().put("$schema",EmbeddedDuplexConstructionClosure.NATIVE).put("native_runtime_binding","partial_construction")
            .put("disposition","partial_construction_closed").put("construction_disposition","not_committed")
            .put("closure_body_sha256",ack.getString("body_sha256")).put("durable_closure",ack).put("provider_entered",true)
            .put("new_media_starts",0L).put("old_outcomes_preserved",true).put("future_writable_restore","fenced_pending_supported_owner_reconciliation");
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})proof.put(key,actual.get(key));
        EmbeddedDuplexConstructionClosure.requireNative(proof,actual);
        JSONObject noAck=new JSONObject(proof.toString()).put("durable_closure",JSONObject.NULL);refused(()->EmbeddedDuplexConstructionClosure.requireNative(noAck,actual));
        JSONObject fabricated=new JSONObject(proof.toString()).put("configuration_binding",new JSONObject());refused(()->EmbeddedDuplexConstructionClosure.requireNative(fabricated,actual));
        JSONObject never=new JSONObject(proof.toString()).put("provider_entered",false).put("durable_closure",JSONObject.NULL).put("future_writable_restore","owner_factory_not_entered");
        EmbeddedDuplexConstructionClosure.requireNative(never,actual);
        never.put("provider_entered",true);refused(()->EmbeddedDuplexConstructionClosure.requireNative(never,actual));
    }
}
