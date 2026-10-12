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
    public static void main(String[] args) throws Exception {
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
