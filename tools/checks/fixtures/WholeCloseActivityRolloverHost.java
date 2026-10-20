package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicReference;

final class EmbeddedDuplexProcessFence {
    private final String epoch; private final long app;
    EmbeddedDuplexProcessFence(String epoch,long app){this.epoch=epoch;this.app=app;}
    void requireLive(long expected){if(expected!=app)throw new IllegalStateException("actor changed");}
    String epochId(){return epoch;}
    static String digest(String text) throws Exception {
        byte[] raw=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();for(byte b:raw)s.append(String.format("%02x",b&255));return s.toString();
    }
}
public final class WholeCloseActivityRolloverHost {
    enum Phase { NEW, READY, CLOSING }
    final Object attachmentGate=new Object();
    final AtomicReference<Phase> phase=new AtomicReference<>(Phase.NEW);
    long attachmentGeneration,nativeExecutorGeneration;
    boolean displayDetaching;
    Object platform,resources;
    String runtimeConfigSha256;
    EmbeddedDuplexProcessFence processFence=new EmbeddedDuplexProcessFence("app-process.3.actor",3);
    EmbeddedDuplexWholeCloseRollover wholeCloseRollover;
    PRODUCTION_CURRENT_GUARD
    static int controls;
    static void check(boolean value){if(!value)throw new AssertionError("rollover control "+controls);controls++;}
    static final String SHA=new String(new char[64]).replace('\0','a');
    static JSONObject proof() throws Exception {
        JSONObject p=new JSONObject().put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1")
            .put("action","whole_app_close").put("config_sha256",SHA).put("app_process_generation",3)
            .put("native_executor_generation",7).put("peer_physical_cleanup","terminal");
        for(String key:new String[]{"native_host_physical_cleanup","java_own_capture_cleanup","java_renderer_cleanup",
            "java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status"})p.put(key,"terminal");
        return p;
    }
    static EmbeddedDuplexWholeCloseRollover token(JSONObject p) throws Exception {
        return EmbeddedDuplexWholeCloseRollover.verified(p,"app-process.3.actor",3,7,9,SHA,SHA);
    }
    // Shape follows receipt() in embedded_duplex/peer_lifecycle.rs. Platform,
    // native and broker effects remain modeled; these objects grant no authority.
    static JSONObject ongoingProof() throws Exception {
        return proof().put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3")
            .put("ongoing_media_policy",new JSONObject().put("$schema","rusty.manifold.peer.ongoing_media_policy.v2"))
            .put("current_lifetime_renewal_count",0)
            .put("current_owner_validation",new JSONObject()
                .put("$schema","rusty.quest.concurrent.current_ongoing_owner_validation.v1")
                .put("status","unavailable").put("validated_at_ms",100)
                .put("reason","current_owner_validation_unavailable"));
    }
    static JSONObject peerTerminal(JSONObject p) throws Exception {
        return p.put("media_stop_effect_receipt",new JSONObject())
            .put("route_cleanup",new org.json.JSONArray()).put("broker_evidence",new JSONObject());
    }
    static JSONObject freshProof(String path) throws Exception {
        return new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),java.nio.charset.StandardCharsets.UTF_8));
    }
    static JSONObject archiveAck() throws Exception {
        return new JSONObject().put("$schema","rusty.quest.embedded_duplex.peer_lifetime_archive_retained.v1")
            .put("body_sha256","sha256:"+SHA).put("app_process_generation",3).put("app_record_sha256",SHA)
            .put("native_executor_generation",7).put("config_sha256",SHA)
            .put("prior_stop_original_id",new String(new char[32]).replace('\0','1')).put("storage_identity","app-private.peer-lifetime.1");
    }
    static JSONObject archivedFresh(String path) throws Exception {
        JSONObject ack=archiveAck();return freshProof(path).put("retired_lifetimes",new JSONObject()
            .put("total",1).put("first",ack).put("last",new JSONObject(ack.toString()))
            .put("recent",new org.json.JSONArray().put(new JSONObject(ack.toString())))
            .put("history_sha256","sha256:"+SHA));
    }
    static void denied(JSONObject p) throws Exception {
        boolean rejected=false;try{token(p);}catch(Exception expected){rejected=true;}check(rejected);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("actual native v4 projection input required");
        EmbeddedDuplexWholeCloseRollover closed=token(proof());
        check(closed.matchesRuntime(9,SHA,SHA));check(!closed.matchesRuntime(10,SHA,SHA));
        check(!closed.matchesRuntime(9,"other",SHA));check(!closed.matchesRuntime(9,SHA,"other"));
        for(String key:new String[]{"native_host_physical_cleanup","java_own_capture_cleanup","java_renderer_cleanup",
            "java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status","peer_physical_cleanup"}) {
            JSONObject changed=proof().put(key,"pending");boolean denied=false;
            try{token(changed);}catch(Exception expected){denied=true;}check(denied);
            changed=proof();changed.remove(key);denied=false;try{token(changed);}catch(Exception expected){denied=true;}check(denied);
        }
        for(String key:new String[]{"$schema","action","config_sha256"}) {
            boolean denied=false;try{token(proof().put(key,"other"));}catch(Exception expected){denied=true;}check(denied);
        }
        for(String key:new String[]{"app_process_generation","native_executor_generation"}) {
            boolean denied=false;try{token(proof().put(key,8));}catch(Exception expected){denied=true;}check(denied);
        }
        // V3 admits only its actual native policy identity. Terminal cleanup does
        // not require active renewal authority after Stop, including unavailable
        // current-owner validation. Every existing physical barrier still holds.
        check(token(ongoingProof()).matchesRuntime(9,SHA,SHA));
        for(String key:new String[]{"native_host_physical_cleanup","java_own_capture_cleanup","java_renderer_cleanup",
            "java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status","peer_physical_cleanup"}) {
            denied(ongoingProof().put(key,"pending"));JSONObject missing=ongoingProof();missing.remove(key);denied(missing);
        }
        for(String schema:new String[]{"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v2",
            "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4","other"})denied(ongoingProof().put("$schema",schema));
        // v4 is produced by the ACTUAL native formatter over a labeled modeled
        // v3 base. Selecting a version alone cannot manufacture its new fields.
        check(token(freshProof(args[0])).matchesRuntime(9,SHA,SHA));
        check(token(archivedFresh(args[0])).matchesRuntime(9,SHA,SHA));
        for(String key:new String[]{"last_failure_cause","retired_lifetimes"}) {
            JSONObject damaged=freshProof(args[0]);damaged.remove(key);denied(damaged);
        }
        for(Object damage:new Object[]{JSONObject.NULL,"unknown",new org.json.JSONArray()})denied(freshProof(args[0]).put("retired_lifetimes",damage));
        for(String key:new String[]{"total","first","last","recent","history_sha256"}) {
            JSONObject damaged=archivedFresh(args[0]);damaged.getJSONObject("retired_lifetimes").remove(key);denied(damaged);
        }
        for(String key:new String[]{"$schema","body_sha256","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","prior_stop_original_id","storage_identity"}){
            JSONObject damaged=archivedFresh(args[0]);damaged.getJSONObject("retired_lifetimes").getJSONObject("last").remove(key);denied(damaged);
        }
        denied(freshProof(args[0]).put("last_failure_cause",new JSONObject().put("code","expired")));
        JSONObject noncurrent=new JSONObject().put("$schema","rusty.manifold.peer.session_current_receipt.v2")
            .put("session_id","session.fixture").put("current",false).put("rejection_reason","expired")
            .put("decision_id",JSONObject.NULL).put("peer_ids",new org.json.JSONArray())
            .put("topology_contract_id",JSONObject.NULL).put("validated_at_ms",0).put("expires_at_ms",JSONObject.NULL);
        JSONObject cause=new JSONObject().put("$schema","rusty.quest.embedded_duplex.operation_failure.v1")
            .put("code","pair_session_not_current").put("operation","renew_authority")
            .put("session_id","session.fixture").put("observed_at_ms",0).put("current_session",noncurrent);
        check(token(freshProof(args[0]).put("last_failure_cause",cause)).matchesRuntime(9,SHA,SHA));
        for(String key:new String[]{"$schema","code","operation","session_id","observed_at_ms","current_session"}){
            JSONObject bad=new JSONObject(cause.toString());bad.remove(key);denied(freshProof(args[0]).put("last_failure_cause",bad));
        }
        denied(freshProof(args[0]).put("last_failure_cause",new JSONObject(cause.toString()).put("observed_at_ms",1)));
        JSONObject negativeTotal=archivedFresh(args[0]);negativeTotal.getJSONObject("retired_lifetimes").put("total",-1);denied(negativeTotal);
        JSONObject extraCursor=archivedFresh(args[0]);extraCursor.getJSONObject("retired_lifetimes").put("unreviewed",true);denied(extraCursor);
        JSONObject wrongActor=archivedFresh(args[0]);wrongActor.getJSONObject("retired_lifetimes").getJSONObject("last").put("native_executor_generation",8);denied(wrongActor);
        JSONObject missing=ongoingProof();missing.remove("ongoing_media_policy");denied(missing);
        denied(ongoingProof().put("ongoing_media_policy",JSONObject.NULL));
        denied(ongoingProof().put("ongoing_media_policy",new JSONObject().put("$schema","rusty.manifold.peer.ongoing_media_policy.v1")));
        denied(ongoingProof().put("ongoing_media_policy",new JSONObject().put("$schema","rusty.manifold.peer.ongoing_media_policy.v2").put("extra",true)));
        for(JSONObject p:new JSONObject[]{peerTerminal(proof()),peerTerminal(ongoingProof())}) {
            check(EmbeddedDuplexWholeCloseRollover.localPeerTerminalProof(p));
            for(String key:new String[]{"media_stop_effect_receipt","route_cleanup","broker_evidence","peer_physical_cleanup"}) {
                JSONObject changed=new JSONObject(p.toString());changed.remove(key);
                check(!EmbeddedDuplexWholeCloseRollover.localPeerTerminalProof(changed));
                changed=new JSONObject(p.toString()).put(key,key.equals("peer_physical_cleanup")?"pending":"terminal");
                check(!EmbeddedDuplexWholeCloseRollover.localPeerTerminalProof(changed));
            }
        }
        // Authenticated remote-owner closure retains its distinct, non-local path.
        check(token(proof().put("$schema","rusty.quest.embedded_duplex.remote_owner_cleanup.v1")
            .put("peer_physical_cleanup","not_locally_started")).matchesRuntime(9,SHA,SHA));
        WholeCloseActivityRolloverHost h=new WholeCloseActivityRolloverHost();
        check(!h.wholeCloseRolloverCurrent(closed)); // NEW alone has no full receipt/token.
        h.wholeCloseRollover=closed;check(h.wholeCloseRolloverCurrent(closed));
        check(!h.wholeCloseRolloverCurrent(token(proof()))); // Equal bytes are not the issued object.
        h.phase.set(Phase.READY);check(!h.wholeCloseRolloverCurrent(closed));h.phase.set(Phase.NEW);
        h.attachmentGeneration=10;check(!h.wholeCloseRolloverCurrent(closed));h.attachmentGeneration=0;
        h.nativeExecutorGeneration=8;check(!h.wholeCloseRolloverCurrent(closed));h.nativeExecutorGeneration=0;
        h.resources=new Object();check(!h.wholeCloseRolloverCurrent(closed));h.resources=null;
        h.platform=new Object();check(!h.wholeCloseRolloverCurrent(closed));h.platform=null;
        h.runtimeConfigSha256=SHA;check(!h.wholeCloseRolloverCurrent(closed));h.runtimeConfigSha256=null;
        h.displayDetaching=true;check(!h.wholeCloseRolloverCurrent(closed));h.displayDetaching=false;
        h.processFence=new EmbeddedDuplexProcessFence("app-process.3.other",3);check(!h.wholeCloseRolloverCurrent(closed));
        h.processFence=new EmbeddedDuplexProcessFence("app-process.4.actor",4);check(!h.wholeCloseRolloverCurrent(closed));
        System.out.println("PASS "+controls+" actual token/current-owner controls; fence modeled; no device effects");
    }
}
