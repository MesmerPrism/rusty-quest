package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Actual extracted Platform/owner/fence methods; SDK/OS/Original registry leaves modeled. */
public final class EmbeddedDuplexPreparedAbortCallbackHostTest {
    static int checks;
    static void check(boolean value) { if(!value)throw new AssertionError("callback control "+checks);checks++; }
    interface Attempt { void run()throws Exception; }
    static void refuse(Attempt action)throws Exception { boolean refused=false;try{action.run();}catch(Exception expected){refused=true;}check(refused); }
    static JSONObject actor()throws Exception {return new JSONObject().put("app_process_generation",3L).put("app_record_sha256","a".repeat(64)).put("native_executor_generation",7L).put("config_sha256","b".repeat(64));}
    static JSONObject request()throws Exception {return actor().put("preparation_id","1".repeat(32)).put("resource_target_sha256","sha256:"+"c".repeat(64)).put("resource_target",new JSONObject().put("MODELED",true));}
    static JSONObject abort(EmbeddedDuplexPreparedStart held)throws Exception {return actor().put("$schema","rusty.quest.android.media.prepared_abort_request.v1").put("preparation_id",held.id()).put("cleanup_original_id","2".repeat(32)).put("request_sha256",held.requestSha).put("resource_target_sha256",held.request.getString("resource_target_sha256"));}
    static EmbeddedDuplexPlatform fresh()throws Exception {
        EmbeddedDuplexOriginals.current.set(new EmbeddedDuplexOriginals.Record("2".repeat(32),"whole_app_close"));
        EmbeddedDuplexPlatform platform=new EmbeddedDuplexPlatform();
        platform.processCallbacks=new EmbeddedDuplexProcessFence().callbacks();
        platform.preparedStart=new EmbeddedDuplexPreparedStart();
        EmbeddedDuplexProcessHost.instance=new EmbeddedDuplexProcessHost();EmbeddedDuplexProcessHost.instance.platform=platform;
        return platform;
    }
    static void terminal(JSONObject value)throws Exception {
        EmbeddedDuplexPeerRoots.keys(value,"$schema","preparation_id","original_id","cleanup_original_id","executor_generation","request_sha256","resource_target_sha256","provider_handle_id","provider_state_revision","stage","owner_receipts","delivery_gate_closed","peer_delivery_count");
        check("rusty.quest.android.media.prepared_abort_readback.v1".equals(value.getString("$schema")));
        check(value.getJSONArray("owner_receipts").length()==7 && value.getBoolean("delivery_gate_closed") && value.getLong("peer_delivery_count")==0);
    }
    public static void main(String[] ignored)throws Exception {
        EmbeddedDuplexPlatform p=fresh();JSONObject supplied=abort(p.preparedStart);
        terminal(new JSONObject(p.abortPreparedPeerStartResources(supplied.toString())));check(p.preparedStart.resources.calls==1);
        check(EmbeddedDuplexProcessFence.entered(p.processCallbacks)==0);
        p=fresh();p.processCallbacks.retire();final EmbeddedDuplexPlatform stale=p;refuse(()->stale.abortPreparedPeerStartResources(abort(stale.preparedStart).toString()));check(p.preparedStart.resources.calls==0);
        p=fresh();supplied=abort(p.preparedStart);p.preparedStart=null;final EmbeddedDuplexPlatform absent=p;final String noOwner=supplied.toString();refuse(()->absent.abortPreparedPeerStartResources(noOwner));check(EmbeddedDuplexProcessFence.entered(p.processCallbacks)==0);
        for(String fault:new String[]{"actor","preparation","request","cleanup","operation","active"}) {
            p=fresh();supplied=abort(p.preparedStart);
            switch(fault){case "actor":supplied.put("native_executor_generation",8);break;case "preparation":supplied.put("preparation_id","9".repeat(32));break;case "request":supplied.put("request_sha256","sha256:"+"9".repeat(64));break;case "cleanup":supplied.put("cleanup_original_id","9".repeat(32));break;case "operation":EmbeddedDuplexOriginals.current.set(new EmbeddedDuplexOriginals.Record("2".repeat(32),"peer_start"));break;case "active":p.preparedStart.resources.active=true;break;default:throw new AssertionError();}
            final EmbeddedDuplexPlatform selected=p;final String raw=supplied.toString();refuse(()->selected.abortPreparedPeerStartResources(raw));check(p.preparedStart.resources.calls==0);check(EmbeddedDuplexProcessFence.entered(p.processCallbacks)==0);
        }
        // Causal barrier: same callback lease stays live through actual owner
        // method waiting for modeled SDK terminal; retirement does not fake join.
        p=fresh();p.preparedStart.resources.block=true;final EmbeddedDuplexPlatform blocked=p;final String raw=abort(p.preparedStart).toString();AtomicReference<Throwable> failure=new AtomicReference<>();
        CompletableFuture<Void> completed=new CompletableFuture<>();
        Thread callback=new Thread(()->{try{EmbeddedDuplexOriginals.current.set(new EmbeddedDuplexOriginals.Record("2".repeat(32),"whole_app_close"));terminal(new JSONObject(blocked.abortPreparedPeerStartResources(raw)));}catch(Throwable error){failure.set(error);}finally{completed.complete(null);}},"actual-callback-method");callback.start();
        CompletableFuture<Void> drained=null;
        try {
            CompletableFuture.anyOf(p.preparedStart.resources.entered,completed).join();
            check(p.preparedStart.resources.entered.isDone());
            drained=p.processCallbacks.retireAndDrain();
            check(!drained.isDone());check(EmbeddedDuplexProcessFence.entered(p.processCallbacks)==1);
        } finally {
            p.preparedStart.resources.release.countDown();
            callback.join();
        }
        if(failure.get()!=null)throw new AssertionError(failure.get());check(drained!=null&&drained.isDone());check(EmbeddedDuplexProcessFence.entered(p.processCallbacks)==0);
        p=fresh();p.preparedStart.resources.fail=true;final EmbeddedDuplexPlatform failed=p;refuse(()->failed.abortPreparedPeerStartResources(abort(failed.preparedStart).toString()));check(EmbeddedDuplexProcessFence.entered(p.processCallbacks)==0);check(p.preparedStart.abortReceipt==null&&p.preparedStart.abortEntered);
        p=fresh();final EmbeddedDuplexPlatform wrongThread=p;AtomicReference<Throwable> refused=new AtomicReference<>();Thread foreign=new Thread(()->{try{wrongThread.abortPreparedPeerStartResources(abort(wrongThread.preparedStart).toString());}catch(Throwable expected){refused.set(expected);}});foreign.start();foreign.join();check(refused.get()!=null);check(p.preparedStart.resources.calls==0);
        System.out.println(checks+" actual Platform callback / owned abort / callback lease controls PASS; SDK, OS and Original registry leaves modeled; no physical cleanup proof.");
    }
}
class EmbeddedDuplexPlatform {
    EmbeddedDuplexProcessFence.CallbackGuard processCallbacks;EmbeddedDuplexPreparedStart preparedStart;
    /* ACTUAL_ENTER_CALLBACK */
    /* ACTUAL_REQUIRE_CALLBACK */
    /* ACTUAL_ABORT_CALLBACK */
}
class EmbeddedDuplexPreparedStart {
    final Resources resources=new Resources();final EmbeddedDuplexOriginals.Record original=new EmbeddedDuplexOriginals.Record("3".repeat(32),"prepare_peer_start");
    final JSONObject request=EmbeddedDuplexPreparedAbortCallbackHostTest.request();final String requestSha="sha256:"+"d".repeat(64);JSONObject abortReceipt;String exactAbort;boolean abortEntered;long revision;
    EmbeddedDuplexPreparedStart()throws Exception{}
    String id()throws Exception{return request.getString("preparation_id");}
    /* ACTUAL_OWNED_ABORT */
    final class Resources {
        int calls;boolean active,block,fail;final CompletableFuture<Void> entered=new CompletableFuture<>();final CountDownLatch release=new CountDownLatch(1);
        boolean preparedDeliveryOpened(){return active;}
        JSONObject abortPreparedResources(String id)throws Exception {calls++;entered.complete(null);if(block)release.await();if(fail)throw new IllegalStateException("MODELED SDK terminal pending");JSONObject identity=new JSONObject(request.toString()).put("request_sha256",requestSha);identity.remove("$schema");identity.remove("original_id");return new JSONObject().put("$schema",EmbeddedDuplexPreparedRenderer.NONE).put("identity",identity).put("disposition","not_entered").put("cleanup_terminal",true).put("worker_created",false).put("source_hold_acquired",false).put("source_hold_released",false).put("prepare_calls_joined",true);}
        boolean productResourcesTerminal(){return !fail;}
        long generation(){return 7;}
        String preparedSenderHandle(){return "MODELED-same-provider-owner";}
        JSONArray preparedTerminalOwners()throws Exception {JSONArray rows=new JSONArray();for(int i=0;i<7;i++)rows.put(new JSONObject().put("$schema","rusty.quest.android.media.prepared_owner_terminal.v1").put("owner_kind","MODELED-"+i).put("owner_id","MODELED-owner-"+i).put("provider_kind","MODELED-provider").put("resource_id","MODELED-resource-"+i).put("construction_state","closed_joined").put("terminal_receipt",new JSONObject().put("MODELED",true)));return rows;}
        long preparedPeerDeliveryCount(){return 0;}
    }
}
class EmbeddedDuplexProcessHost {
    static EmbeddedDuplexProcessHost instance;EmbeddedDuplexPlatform platform;Object applicationContext=new Object();
    static JSONObject freshClosureIdentity(EmbeddedDuplexPlatform platform)throws Exception {return EmbeddedDuplexPreparedAbortCallbackHostTest.actor();}
    static void requireCurrentOriginalStopActor(EmbeddedDuplexOriginals.Record actual){if(actual!=EmbeddedDuplexOriginals.capture())throw new IllegalStateException("MODELED current Original source differs");}
    /* ACTUAL_ABORT_CONTEXT */
}
class EmbeddedDuplexOriginals {
    static final ThreadLocal<Record> current=new ThreadLocal<>();
    static final class Record {final String value;final JSONObject row;Record(String id,String operation)throws Exception{value=id;row=new JSONObject().put("operation",operation);}String id(){return value;}}
    static Record capture(){return current.get();}
    static EmbeddedDuplexOriginals forApplication(Object app){return new EmbeddedDuplexOriginals();}
    void requireOldUnknownWholeCleanup(String id){throw new IllegalStateException("MODELED no earned old cleanup custody");}
}
class EmbeddedDuplexProcessFence {
    long generation=3;boolean live=true;
    void requireLive(long expected){if(!live||generation!=expected)throw new IllegalStateException("MODELED stale app actor");}
    void requireFresh(){requireLive(generation);}
    static int entered(CallbackGuard guard){return guard.entered;}
    /* ACTUAL_CALLBACK_FACTORY */
    /* ACTUAL_CALLBACK_GUARD */
    /* ACTUAL_CALLBACK_LEASE */
}
class EmbeddedDuplexPeerRoots {
    /* ACTUAL_CLOSED_KEYS */
    /* ACTUAL_ACTOR_IDENTITY */
}
