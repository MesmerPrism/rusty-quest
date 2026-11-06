package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import android.os.Bundle;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Exact production Host methods; process/Native/Android/Original leaves modeled. */
public class GuardedModeCallHost implements AutoCloseable {
    enum Phase { READY }
    static final String RUN="b".repeat(32),EPOCH="app-process.69.00000000-0000-0000-0000-000000000001";
    static final String APK="c".repeat(64),RECORD="d".repeat(64),CONFIG="e".repeat(64),CHALLENGE="a".repeat(32);
    static final class EmbeddedDuplexRunContext { String runId=RUN;void requireSame(EmbeddedDuplexRunContext current){if(current!=this)throw new IllegalStateException("run changed");} }
    static final class Fence { long generation(){return 69;}String epochId(){return EPOCH;}void requireFresh(){}void requireLive(long generation){if(generation!=69)throw new IllegalStateException();}void requireIncarnation(Object actual){} }
    static final class EmbeddedDuplexProcessIncarnation { static Object current(int pid,int uid){return new Object();} }
    static final class InstalledApkDigest { String apk=APK;int reads;String read(Object context){reads++;return apk;} }
    static final class EmbeddedDuplexOriginals { static JSONObject bundleObject(Bundle value){JSONObject result=new JSONObject();for(String key:value.keySet())result.put(key,value.get(key));return result;} }
    static final class EmbeddedDuplexCanonicalJson { static String encode(Object value){return value.toString();} }
    EmbeddedDuplexRunContext runContext=new EmbeddedDuplexRunContext();
    final ExecutorService commands=Executors.newSingleThreadExecutor();
    final AtomicReference<Phase> phase=new AtomicReference<>(Phase.READY);
    boolean localFixture;Object platform=new Object(),resources=new Object(),applicationContext=new Object();
    Fence processFence=new Fence();long nativeExecutorGeneration=25;String nativeAppRecordSha256=RECORD,runtimeConfigSha256=CONFIG;
    InstalledApkDigest installedApkDigest=new InstalledApkDigest();
    int nativeReads;
    void ensureProcessFence(){}
    static JSONObject originalActor(){return new JSONObject().put("package_id","fixture.app").put("native_generation",25).put("app_record_sha256",RECORD);}
    String runStatusOnLane(){nativeReads++;return new JSONObject().put("$schema","rusty.quest.embedded_duplex.run_status.v2")
        .put("run_id",RUN).put("selection_generation",26).put("app_process_generation",69).put("app_display_generation",1)
        .put("app_fence_disposition","effects_pending").put("native",new JSONObject().put("$schema","rusty.quest.embedded_duplex.operational_run_native.v1")
            .put("operational_run_id",RUN).put("native_generation",25).put("globally_eligible",false).put("refusal_reason","native_host_owned")).toString();}
    // ACTUAL_GUARDED_METHODS
    CompletableFuture<Bundle> call(String run,long app,String apk,java.util.function.Supplier<CompletableFuture<Bundle>> body){
        return guardedModeCall(run,app,EPOCH,apk,25,RECORD,CONFIG,"blend_read",CHALLENGE,body);
    }
    static Bundle response(){Bundle value=new Bundle();value.putString("schema","rusty.quest.embedded_duplex.operator_transport.v3");value.putString("challenge",CHALLENGE);value.putString("run_id",RUN);value.putString("status","observed");value.putString("receipt","{}");return value;}
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void rejects(CompletableFuture<?> value)throws Exception{try{value.get(5,TimeUnit.SECONDS);throw new AssertionError("accepted");}catch(ExecutionException expected){}}
    public void close()throws Exception{commands.shutdown();check(commands.awaitTermination(5,TimeUnit.SECONDS));}
    public static void main(String[]args)throws Exception{
        try(GuardedModeCallHost host=new GuardedModeCallHost()){
            AtomicInteger issued=new AtomicInteger();java.util.function.Supplier<CompletableFuture<Bundle>> body=()->{issued.incrementAndGet();return CompletableFuture.completedFuture(response());};
            rejects(host.call("f".repeat(32),69,APK,body));rejects(host.call(RUN,70,APK,body));rejects(host.call(RUN,69,"0".repeat(64),body));check(issued.get()==0);
            Bundle envelope=host.call(RUN,69,APK,body).get(5,TimeUnit.SECONDS);check(issued.get()==1);
            JSONObject proof=new JSONObject(envelope.getString("guarded_json"));
            check(!proof.getJSONObject("before").getJSONObject("status").getJSONObject("native").getBoolean("globally_eligible"));
            check(proof.getJSONObject("after").getJSONObject("status").getString("app_fence_disposition").equals("effects_pending"));
            check(host.installedApkDigest.reads==3&&host.nativeReads==2);
            CompletableFuture<Bundle> actual=new CompletableFuture<>();CountDownLatch entered=new CountDownLatch(1);
            CompletableFuture<Bundle> pending=host.call(RUN,69,APK,()->{entered.countDown();return actual;});
            check(entered.await(5,TimeUnit.SECONDS)&&!pending.isDone());actual.complete(response());pending.get(5,TimeUnit.SECONDS);
            CompletableFuture<Bundle> lost=host.call(RUN,69,APK,()->{issued.incrementAndGet();host.installedApkDigest.apk="0".repeat(64);return CompletableFuture.completedFuture(response());});
            rejects(lost);check(issued.get()==2); // post failure never reruns the supplier
        }
        System.out.println("PASS exact Host pre/body/post: wrong run/generation/APK never calls body, legal active status, real pending Future, post drift no replay");
    }
}
