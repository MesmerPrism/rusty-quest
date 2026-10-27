package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.os.Bundle;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Exact production callers/store/slot; Android, prepared SDK and Native effects are explicit models. */
public final class EmbeddedDuplexOrdinaryStartHostTest {
    private static final String ID="1".repeat(32), CHALLENGE="d".repeat(32);
    private static int checks;
    private static void yes(boolean value){checks++;if(!value)throw new AssertionError("control "+checks+" failed");}
    private interface Checked {void run()throws Exception;}
    private static void denied(Checked work)throws Exception{
        checks++;try{work.run();}catch(Exception expected){return;}throw new AssertionError("expected refusal "+checks);
    }
    private static void reset(Class<?> type,String name)throws Exception{
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(null,null);
    }
    private static final class Context extends android.content.Context {
        final File directory;boolean camera=true,headset=true;
        Context(File value){directory=value;}
        public File getNoBackupFilesDir(){return directory;}
        public int checkSelfPermission(String permission){
            return (permission.equals("android.permission.CAMERA")?camera:headset)?0:-1;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Context context;final EmbeddedDuplexProcessHost host;
        final AtomicBoolean resumed=new AtomicBoolean(true),scenePermissionDenied=new AtomicBoolean();
        final AtomicInteger factories=new AtomicInteger();
        final java.util.function.BooleanSupplier current=()->{
            if(scenePermissionDenied.get())throw new IllegalStateException("modeled selected scene permission unavailable");
            return resumed.get();
        };
        CompletableFuture<String> actual;
        Fixture(File root,String name)throws Exception{
            reset(EmbeddedDuplexProcessHost.class,"instance");reset(EmbeddedDuplexOriginals.class,"singleton");
            EmbeddedDuplexNative.reset();
            io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="source-bound-v2";
            File directory=new File(root,name);Files.createDirectories(new File(directory,"embedded-duplex-replay").toPath());context=new Context(directory);
            host=EmbeddedDuplexProcessHost.forApplication(context);
            host.harnessSubmit(()->{host.harnessReady("a".repeat(64),17);return null;}).get(5,TimeUnit.SECONDS);
            ConcurrentStereoQualification.epoch=host.harnessEpoch();ConcurrentStereoQualification.generation=17;
        }
        JSONObject request(boolean prepare)throws Exception{
            Bundle fields=new Bundle();if(prepare)fields.putBoolean("prepare_preflight",true);
            return EmbeddedDuplexOriginals.request("start",CHALLENGE,fields);
        }
        Bundle begin()throws Exception{
            return EmbeddedDuplexRuntimeService.beginOriginal(context,ID,"start",CHALLENGE,request(true),()->{
                factories.incrementAndGet();
                actual=EmbeddedDuplexRuntimeService.ordinaryStart(context,7,CHALLENGE,current);
                return actual.thenApply(receipt->{
                    Bundle response=new Bundle();response.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");
                    response.putString("status","observed");response.putString("challenge",CHALLENGE);response.putString("receipt",receipt);return response;
                });
            });
        }
        Bundle query()throws Exception{return EmbeddedDuplexRuntimeService.queryOriginal(context,ID,CHALLENGE);}
        Bundle finish()throws Exception{
            try{actual.get(5,TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException expected){}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            Bundle value;
            do{value=query();if(!"pending".equals(value.getString("status")))return value;Thread.sleep(2);}while(System.nanoTime()<deadline);
            throw new AssertionError("Original publication did not complete");
        }
        public void close()throws Exception{host.harnessClose();}
    }
    private static void unknown(Fixture f)throws Exception{
        Bundle observed=f.finish();yes("unknown".equals(observed.getString("status")));
        int calls=EmbeddedDuplexNative.calls;f.begin();yes(f.factories.get()==1&&EmbeddedDuplexNative.calls==calls);
        JSONObject body=new JSONObject(observed.getString("observation_json"));
        denied(()->EmbeddedDuplexRuntimeService.acknowledgeOriginal(f.context,ID,CHALLENGE,
                body.getString("request_sha256"),body.getString("outcome_sha256"),"a".repeat(64)));
    }
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);Files.createDirectories(root.toPath());
        try(Fixture f=new Fixture(root,"success")){
            f.begin();Bundle result=f.finish();yes("observed".equals(result.getString("status")));
            yes(EmbeddedDuplexNative.calls==1&&EmbeddedDuplexNative.effects==1&&f.factories.get()==1);
            yes(EmbeddedDuplexNative.entered.id().equals(ID)&&EmbeddedDuplexNative.originals.size()==2);
            yes(EmbeddedDuplexNative.originals.stream().allMatch(ID::equals));
            f.begin();yes(f.factories.get()==1&&EmbeddedDuplexNative.calls==1);
            denied(()->EmbeddedDuplexRuntimeService.beginOriginal(f.context,ID,"start",CHALLENGE,f.request(false),()->{throw new AssertionError("changed request replay");}));
        }
        for(String refusal:new String[]{"camera","headset","sdk","pair","expired","display","arm","actor","resumed","scene","late-camera","late-resume","late-scene","late-actor","stop-before-entry","finite-selection"}){
            try(Fixture f=new Fixture(root,refusal)){
                switch(refusal){
                    case "camera":f.context.camera=false;break;
                    case "headset":f.context.headset=false;break;
                    case "sdk":EmbeddedDuplexNative.sdkReady=false;break;
                    case "pair":EmbeddedDuplexNative.pairPresent=false;break;
                    case "expired":EmbeddedDuplexNative.expired=true;break;
                    case "display":f.host.harnessAttachment(8);break;
                    case "arm":ConcurrentStereoQualification.epoch="other";break;
                    case "actor":ConcurrentStereoQualification.generation=18;break;
                    case "resumed":f.resumed.set(false);break;
                    case "scene":f.scenePermissionDenied.set(true);break;
                    case "late-camera":EmbeddedDuplexNative.pairReadHook=()->f.context.camera=false;break;
                    case "late-resume":EmbeddedDuplexNative.pairReadHook=()->f.resumed.set(false);break;
                    case "late-scene":EmbeddedDuplexNative.pairReadHook=()->f.scenePermissionDenied.set(true);break;
                    case "late-actor":EmbeddedDuplexNative.pairReadHook=()->f.host.harnessNativeGeneration(18);break;
                    case "stop-before-entry":EmbeddedDuplexNative.pairReadHook=()->EmbeddedDuplexOriginals.capture().desiredStop.set(true);break;
                    case "finite-selection":io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="none";break;
                    default:throw new AssertionError(refusal);
                }
                f.begin();unknown(f);yes(EmbeddedDuplexNative.calls==0&&EmbeddedDuplexNative.effects==0);
            }
        }
        try(Fixture f=new Fixture(root,"wrong-challenge")){
            denied(()->EmbeddedDuplexRuntimeService.ordinaryStart(f.context,7,"e".repeat(32),f.resumed::get).get(5,TimeUnit.SECONDS));
            yes(EmbeddedDuplexNative.calls==0);
        }
        for(String retained:new String[]{"unknown","pending","expired-native-authority"}){
            try(Fixture f=new Fixture(root,retained)){
                EmbeddedDuplexNative.throwAfterEntry=retained.equals("unknown");EmbeddedDuplexNative.pending=retained.equals("pending");
                EmbeddedDuplexNative.grantExpired=retained.equals("expired-native-authority");
                f.begin();unknown(f);yes(EmbeddedDuplexNative.calls==1);
                yes(EmbeddedDuplexNative.effects==(EmbeddedDuplexNative.grantExpired?0:1));
                denied(()->EmbeddedDuplexRuntimeService.ordinaryStart(f.context,7,CHALLENGE,f.resumed::get).get(5,TimeUnit.SECONDS));
                yes(EmbeddedDuplexNative.calls==1);
            }
        }
        try(Fixture f=new Fixture(root,"ui-internal-original")){
            EmbeddedDuplexRuntimeService.ordinaryStart(f.context,7,null,f.resumed::get).get(5,TimeUnit.SECONDS);
            yes(EmbeddedDuplexNative.calls==1&&EmbeddedDuplexNative.entered!=null);
            yes(EmbeddedDuplexNative.originals.stream().allMatch(EmbeddedDuplexNative.entered.id()::equals));
            yes("start".equals(EmbeddedDuplexNative.entered.row.getString("operation")));
        }
        try(Fixture f=new Fixture(root,"pending-start-stop")){
            EmbeddedDuplexNative.hold=true;f.begin();yes(EmbeddedDuplexNative.enteredLatch.await(5,TimeUnit.SECONDS));
            // Cleanup must not depend on camera, Pair or successful diagnostic observation.
            f.context.camera=false;f.context.headset=false;EmbeddedDuplexNative.pairPresent=false;f.host.harnessPhaseFailed();
            CompletableFuture<String> stop=EmbeddedDuplexRuntimeService.stopCurrentPeer(f.context,7);
            yes(EmbeddedDuplexNative.entered.desiredStop.get()&&EmbeddedDuplexNative.stopSignals==1);
            yes(new JSONObject(stop.get(5,TimeUnit.SECONDS)).getString("action").equals("peer_stop"));
            f.actual.get(5,TimeUnit.SECONDS);yes(EmbeddedDuplexNative.calls==1);
        }
        System.out.println("ordinary Start caller controls passed: "+checks+" (Native/SDK/Android authority modeled; no APK/device)");
    }
}
