package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Platform, Native and recovery-validator leaves; actual app methods are inserted below. */
public final class ColdPendingAppRecoveryHost {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static EmbeddedDuplexProcessHost fresh(){
        EmbeddedDuplexProcessHost h=new EmbeddedDuplexProcessHost();EmbeddedDuplexProcessHost.instance=h;
        EmbeddedDuplexColdRecovery.prepared=EmbeddedDuplexColdRecovery.retired=EmbeddedDuplexColdRecovery.completed=0;
        EmbeddedDuplexColdRecovery.latest=null;EmbeddedDuplexNative.renderer=EmbeddedDuplexNative.camera=true;
        OwnStereoCaptureRuntime.current=null;EmbeddedDuplexOriginals.current.set(new EmbeddedDuplexOriginals.Record());return h;
    }
    public static Context providerContext(){EmbeddedDuplexProcessHost h=fresh();EmbeddedDuplexOriginals.records.clear();EmbeddedDuplexOriginals.current.remove();return h.applicationContext;}
    public static void pumpProvider(){EmbeddedDuplexProcessHost.instance.commands.pump(12);}
    public static int retirements(){return EmbeddedDuplexColdRecovery.retired;}
    static CompletableFuture<String> begin(EmbeddedDuplexProcessHost h){return h.recoverColdPending("b".repeat(32),"a".repeat(32),"e".repeat(32));}
    public static void main(String[] args)throws Exception{
        EmbeddedDuplexProcessHost h=fresh();CompletableFuture<String> empty=begin(h);h.commands.pump(12);
        check(empty.isDone()&&!empty.isCompletedExceptionally(),"actual cold/absent local owners did not finish");
        check(EmbeddedDuplexColdRecovery.retired==1&&EmbeddedDuplexColdRecovery.completed==1,"retirement not exactly once");
        check(h.phase.get()==EmbeddedDuplexProcessHost.Phase.NEW&&!h.closeInFlight,"actual local completion did not release host");
        check(h.nativeExecutorGeneration==0&&EmbeddedDuplexOriginals.current.get().row.getJSONObject("actor_binding").isNull("native_generation"),"invented Native actor");
        check(EmbeddedDuplexProcessHost.originalActorSettled(EmbeddedDuplexOriginals.current.get().row.getJSONObject("actor_binding"),7),"no-Native actor was not joined");
        check(EmbeddedDuplexProcessHost.originalExchangesJoined(EmbeddedDuplexOriginals.current.get()),"completed Plan not recognized");
        EmbeddedDuplexOriginals.Record completed=EmbeddedDuplexOriginals.current.get();completed.row.put("state","succeeded");
        EmbeddedDuplexProcessHost.originalCompletion(completed);h.commands.pump(2);
        check(completed.joined==0,"unfinished Original callback was joined");
        completed.callbackTerminal.complete(null);EmbeddedDuplexProcessHost.originalCompletion(completed);h.commands.pump(2);
        check(completed.joined==1,"actual completed recovery callback was not joined");
        check(completed.commands==0,"recovery command custody escaped continuation");

        h=fresh();h.attachmentGeneration=4;h.display.attached=true;OwnStereoCaptureRuntime own=new OwnStereoCaptureRuntime();OwnStereoCaptureRuntime.current=own;
        EmbeddedDuplexPlatform platform=new EmbeddedDuplexPlatform();h.platform=platform;h.resources=new EmbeddedDuplexResources();
        CompletableFuture<String> held=begin(h);h.commands.pump(3);
        check(!held.isDone()&&EmbeddedDuplexColdRecovery.prepared==1&&own.stops==1,"pending Own lost one continuation");
        check(EmbeddedDuplexOriginals.current.get().commands==1,"pending recovery continuation was not reserved before enqueue");
        own.state="retired";EmbeddedDuplexNative.renderer=false;h.commands.pump(3);
        check(!held.isDone()&&h.display.stops==1&&!h.display.attached&&platform.closes==1,"real local cleanup order not retained");
        EmbeddedDuplexNative.renderer=true;h.commands.pump(3);
        check(!held.isDone()&&EmbeddedDuplexColdRecovery.retired==0,"blocked current callback was ignored");
        platform.exchanges=1;platform.callbacks.complete(null);h.commands.pump(4);
        check(!held.isDone()&&EmbeddedDuplexColdRecovery.retired==0,"entered outbound exchange was ignored");
        platform.exchanges=0;h.commands.pump(8);
        check(held.isDone()&&!held.isCompletedExceptionally()&&EmbeddedDuplexColdRecovery.prepared==1&&EmbeddedDuplexColdRecovery.retired==1,"actual retained owners failed to retire");
        check(h.resources==null&&h.platform==null&&h.attachmentGeneration==0,"local owner publication incomplete");

        h=fresh();platform=new EmbeddedDuplexPlatform();h.platform=platform;held=begin(h);h.commands.pump(2);
        h.resources=new EmbeddedDuplexResources();platform.callbacks.complete(null);h.commands.pump(8);
        check(held.isCompletedExceptionally()&&EmbeddedDuplexColdRecovery.retired==0&&h.closeInFlight,"late owner substitution was released");

        h=fresh();h.resources=new EmbeddedDuplexResources();h.resources.reject=true;held=begin(h);h.commands.pump(5);
        check(held.isCompletedExceptionally()&&EmbeddedDuplexColdRecovery.retired==0&&h.resources!=null&&h.closeInFlight,"failed physical cleanup lost custody");

        h=fresh();platform=new EmbeddedDuplexPlatform();h.platform=platform;held=begin(h);h.commands.pump(2);
        platform.callbacks.cancel(false);h.commands.pump(5);
        check(held.isCompletedExceptionally()&&EmbeddedDuplexColdRecovery.retired==0&&h.closeInFlight,"cancelled callback barrier was accepted");

        h=fresh();held=h.recoverColdPending("c".repeat(32),null,null);h.commands.pump(3);
        check(held.isCompletedExceptionally()&&EmbeddedDuplexColdRecovery.prepared==0,"wrong current run entered cleanup");
        h=fresh();EmbeddedDuplexOriginals.current.remove();held=begin(h);h.commands.pump(3);
        check(held.isCompletedExceptionally()&&EmbeddedDuplexColdRecovery.prepared==0,"missing carried Original entered cleanup");

        h=fresh();EmbeddedDuplexColdRecovery.latest=EmbeddedDuplexColdRecovery.pointer("f".repeat(32));
        h.installForTest("c".repeat(32),EmbeddedDuplexColdRecovery.pointer("0".repeat(32)).toString());
        JSONObject actual=h.processFence.handoff.call();
        check(actual.getString("whole_original_id").equals("f".repeat(32)),"current recovery pointer lost to inherited old marker");
        check(h.runContext.runId.equals("b".repeat(32)),"handoff inspection rewrote run marker");
        for(String disposition:List.of("clear","effects_pending","recovery_required")){
            EmbeddedDuplexColdRecovery.disposition=disposition;
            check(new JSONObject(h.statusForTest()).getString("app_fence_disposition").equals(disposition),"actual status ignored recovery publication owner");
        }
        System.out.println("Actual app recovery: cold absence, retained Own/display/resources/control/callbacks, late owners, failed cleanup, carried Original and durable handoff controls PASS");
    }
}

class EmbeddedDuplexProcessHost {
    enum Phase { NEW, CLOSING, READY }
    static EmbeddedDuplexProcessHost instance;
    static EmbeddedDuplexProcessHost forApplication(Context ignored){return instance;}
    static EmbeddedDuplexRunContext selectedContext(){return instance.runContext;}
    final Context applicationContext=new Context();
    EmbeddedDuplexRunContext runContext=new EmbeddedDuplexRunContext();
    EmbeddedDuplexProcessFence processFence=new EmbeddedDuplexProcessFence();
    final Object attachmentGate=new Object();final AtomicReference<Phase> phase=new AtomicReference<>(Phase.NEW);
    final ManualCommands commands=new ManualCommands();final Display display=new Display();
    long attachmentGeneration,nativeExecutorGeneration;boolean closeInFlight,displayDetaching,localFixture;
    EmbeddedDuplexPlatform platform;EmbeddedDuplexResources resources;
    String runtimeConfigSha256,nativeAppRecordSha256,enrollmentRecordSha256,terminalWholeReceipt;
    EmbeddedDuplexRuntimeConfigurationBinding.Context nativeConfigurationContext;JSONObject originalClosedActor,lastClosedActorProof;
    final StartIntent startIntent=new StartIntent();
    final RunSelection runSelection=new RunSelection();
    static class RetainedNoMediaClose {JSONObject proof;CompletableFuture<String> completion=new CompletableFuture<>();}
    static class RetainedWholeClose {EmbeddedDuplexOriginals.Record original;}
    RetainedWholeClose retainedWholeClose;
    static JSONObject originalActor(){return new JSONObject();}
    void ensureProcessFence(){processFence.requireLive(processFence.generation());}
    void installForTest(String next,String locator)throws Exception{installRunHandoff(next,locator);}
    String statusForTest()throws Exception{return runStatusOnLane();}
    // ACTUAL_APP_METHODS
}

class ManualCommands extends AbstractExecutorService implements ScheduledExecutorService {
    final ArrayDeque<Runnable> queue=new ArrayDeque<>();
    public void execute(Runnable action){queue.add(action);}
    void pump(int count){for(int i=0;i<count&&!queue.isEmpty();i++)queue.remove().run();}
    public ScheduledFuture<?> schedule(Runnable action,long delay,TimeUnit unit){execute(action);return new ScheduledTask();}
    public <V> ScheduledFuture<V> schedule(Callable<V> action,long delay,TimeUnit unit){throw new UnsupportedOperationException();}
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable r,long a,long b,TimeUnit u){throw new UnsupportedOperationException();}
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r,long a,long b,TimeUnit u){throw new UnsupportedOperationException();}
    public void shutdown(){}public List<Runnable> shutdownNow(){return List.of();}public boolean isShutdown(){return false;}
    public boolean isTerminated(){return false;}public boolean awaitTermination(long n,TimeUnit u){return false;}
    static class ScheduledTask extends FutureTask<Void> implements ScheduledFuture<Void>{
        ScheduledTask(){super(()->null);}public long getDelay(TimeUnit u){return 0;}public int compareTo(Delayed d){return 0;}
    }
}
class EmbeddedDuplexRunContext {String runId="b".repeat(32);long selectionGeneration=2;File root=new File(".");void requireSame(EmbeddedDuplexRunContext value){if(value!=this)throw new IllegalStateException("run changed");}}
class EmbeddedDuplexProcessFence {
    Callable<JSONObject> handoff;long generation(){return 7;}void requireLive(long gen){if(gen!=7)throw new IllegalStateException("fence changed");}
    void requireIncarnation(Object value){throw new AssertionError("ordinary incarnation gate entered");}
    void operationalHandoff(String next,Callable<JSONObject> action){handoff=action;}
}
class EmbeddedDuplexProcessIncarnation {static Object current(int pid,int uid){return null;}}
class EmbeddedDuplexCanonicalJson {static String encode(JSONObject value){return value.toString();}static boolean sameValue(Object a,Object b){return JSONObject.valueToString(a).equals(JSONObject.valueToString(b));}}
class EmbeddedDuplexRunSelectionStore {static JSONObject validateRelease(JSONObject value){return value;}}
class RunSelection {EmbeddedDuplexRunContext retained(String run){return new EmbeddedDuplexRunContext();}}
class StartIntent {void afterVerifiedCleanup(){}}
class EmbeddedDuplexRuntimeConfigurationBinding {static class Context {}}
class OwnStereoCaptureRuntime {
    static OwnStereoCaptureRuntime current;String state="pending";int stops;
    static OwnStereoCaptureRuntime currentForApplication(){return current;}void requestStopOwn(){stops++;}
    JSONObject physicalRetirementObservation(){return new JSONObject().put("state",state);}
}
class EmbeddedDuplexResources {boolean closed,reject;void closeUnstartedAndVerify(){if(reject)throw new IllegalStateException("pending resources");closed=true;}boolean productResourcesTerminal(){return closed;}}
class EmbeddedDuplexPlatform {
    int closes,exchanges;final CompletableFuture<Void> callbacks=new CompletableFuture<>();
    void closeControlForNoMedia(){ColdPendingAppRecoveryHost.check(!Thread.holdsLock(EmbeddedDuplexProcessHost.instance.attachmentGate),"control under host monitor");closes++;}
    CompletableFuture<Void> retireProcessCallbacksAsync(){return callbacks;}boolean actualOutboundExchangesJoined(){return exchanges==0;}
    boolean originalExchangesJoined(EmbeddedDuplexOriginals.Record record){return false;}
}
class Display {
    boolean attached;int stops;boolean cleanupPending(){return false;}
    Runnable retainOwnedProjectionStopTarget(long generation){if(!attached)throw new IllegalStateException("display absent");return ()->{ColdPendingAppRecoveryHost.check(!Thread.holdsLock(EmbeddedDuplexProcessHost.instance.attachmentGate),"projection stop under monitor");stops++;};}
    interface Cleanup {void run()throws Exception;}
    void detachAfterCleanup(long generation,Cleanup cleanup)throws Exception{ColdPendingAppRecoveryHost.check(!Thread.holdsLock(EmbeddedDuplexProcessHost.instance.attachmentGate),"display barrier under monitor");cleanup.run();attached=false;}
}
class EmbeddedDuplexRendererRetirement {static boolean currentTerminal(){return EmbeddedDuplexNative.renderer;}}
class EmbeddedDuplexNative {
    static boolean renderer=true,camera=true;static String inspectOperationalRun(Object fence){return new JSONObject().put("$schema","rusty.quest.embedded_duplex.operational_run_native.v1").put("native_generation",3).put("globally_eligible",false).toString();}
    static boolean localCameraQuiescent(){return camera;}
    static String retireColdPendingLocal(Object fence)throws Exception{EmbeddedDuplexColdRecovery.active.local.requireTerminal();EmbeddedDuplexColdRecovery.retired++;return "actual modeled Native retirement3";}
}
class EmbeddedDuplexColdRecovery {
    interface LocalOwner {void requireTerminal()throws Exception;JSONObject physicalReceipt()throws Exception;}
    static int prepared,retired,completed;static Plan active;static JSONObject latest;
    static String disposition="recovery_required";static String appDisposition(File root,EmbeddedDuplexProcessFence fence){return disposition;}
    static class Plan {final EmbeddedDuplexOriginals.Record record;LocalOwner local;boolean done;Plan(EmbeddedDuplexOriginals.Record r){record=r;}String originalId(){return record.id();}boolean completed(){return done;}}
    static Plan prepare(Context c,EmbeddedDuplexProcessFence f,String priorRun,String priorOriginal,long counter){
        ColdPendingAppRecoveryHost.check(counter==3,"wrong Native floor");prepared++;Plan p=new Plan(EmbeddedDuplexOriginals.capture());
        p.record.retainedCleanupOwner=p;p.record.row.put("actor_binding",new JSONObject().put("native_generation",JSONObject.NULL).put("app_record_sha256","actual pending fence"));return active=p;
    }
    static void arm(Plan p,LocalOwner local)throws Exception{local.requireTerminal();p.local=local;}
    static JSONObject complete(Plan p,String raw)throws Exception{
        p.local.requireTerminal();JSONObject physical=p.local.physicalReceipt();
        Set<String> expected=Set.of("java_own_capture_cleanup","java_peer_resources_cleanup","java_renderer_cleanup","java_control_cleanup","java_callback_cleanup","command_lane_barrier","whole_app_physical_cleanup");
        ColdPendingAppRecoveryHost.check(physical.keySet().equals(expected),"caller supplied unowned proof fields");
        p.done=true;completed++;return physical.put("$schema","rusty.quest.embedded_duplex.cold_pending_local_retirement.v1").put("app_process_fence_cleanup","terminal");
    }
    static void requireCompleted(Plan plan)throws Exception{if(!plan.done)throw new IllegalStateException("recovery incomplete");plan.local.requireTerminal();}
    static JSONObject pointer(String id){return new JSONObject().put("whole_original_id",id).put("request_sha256","r").put("outcome_sha256","o").put("physical_retirement_sha256","p").put("prior_native_generation",3).put("physical_retirement",new JSONObject());}
    static JSONObject latestHandoff(File root,String pkg){return latest==null?null:new JSONObject(latest.toString());}
    static boolean hasReceipt(File root,String id){return id.equals("f".repeat(32));}
    static JSONObject closedHandoff(File root,String id,String pkg){return pointer(id);}
}
class EmbeddedDuplexOriginals {
    static final String TRANSPORT="rusty.quest.embedded_duplex.operator_transport.v2",RUN_TRANSPORT="rusty.quest.embedded_duplex.operator_transport.v3";
    static final ThreadLocal<Record> current=new ThreadLocal<>();static final EmbeddedDuplexOriginals singleton=new EmbeddedDuplexOriginals();
    static final Map<String,android.os.Bundle> records=new HashMap<>();
    static class Record {final JSONObject row=new JSONObject().put("operation","recover_cold_pending");Object retainedCleanupOwner,persistenceFailure;final CompletableFuture<Void> callbackTerminal=new CompletableFuture<>();int joined,commands;String id(){return "c".repeat(32);}}
    static Record capture(){return current.get();}static Runnable carry(Runnable action){return carryActual(capture(),action);}
    static Runnable carryActual(Record r,Runnable action){if(r!=null)r.commands++;return ()->{try(AutoCloseable ignored=enter(r)){action.run();}catch(Exception e){throw new RuntimeException(e);}finally{if(r!=null)r.commands--;}};}
    static AutoCloseable enter(Record r){Record before=current.get();current.set(r);return ()->current.set(before);}
    public static EmbeddedDuplexOriginals forApplication(Context c){return singleton;}
    private static void id(String value){if(value==null||!value.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("id");}
    // ACTUAL_REQUEST_METHOD
    static String canonical;
    public static String canonicalForTest(){return canonical;}
    public android.os.Bundle begin(String id,String operation,String challenge,JSONObject request,java.util.function.Supplier<CompletableFuture<android.os.Bundle>> action)throws Exception{
        if(records.containsKey(id))return records.get(id);
        canonical=request.toString();
        android.os.Bundle outer=new android.os.Bundle();outer.putString("schema","rusty.quest.embedded_duplex.operator_transport.v3");outer.putString("status","pending");records.put(id,outer);
        try(AutoCloseable ignored=enter(new Record())){action.get().whenComplete((value,failure)->{if(failure==null){outer.putString("status","succeeded");outer.putString("response",new JSONObject().put("schema",value.getString("schema")).put("status",value.getString("status")).put("challenge",value.getString("challenge")).put("receipt",value.getString("receipt")).toString());}else outer.putString("status","unknown");});}
        return outer;
    }
    public static android.os.Bundle queryForTest(String id){return records.get(id);}
    void joined(Record record)throws Exception{ColdPendingAppRecoveryHost.check(record.commands==0&&record.callbackTerminal.isDone(),"early command/callback join");EmbeddedDuplexColdRecovery.requireCompleted((EmbeddedDuplexColdRecovery.Plan)record.retainedCleanupOwner);record.joined++;}
    void actualClosedAppOwnerJoined(long app,long nativeGen,String hash,JSONObject proof,Object owner)throws Exception{ColdPendingAppRecoveryHost.check(nativeGen==0,"invented Native subject");EmbeddedDuplexColdRecovery.requireCompleted((EmbeddedDuplexColdRecovery.Plan)owner);}
    static JSONObject closedLegacyV2WholeHandoff(File root,String id,String pkg){throw new AssertionError("old marker selected");}
    static JSONObject closedWholeHandoff(File root,String id,String pkg){throw new AssertionError("old Whole selected");}
}
