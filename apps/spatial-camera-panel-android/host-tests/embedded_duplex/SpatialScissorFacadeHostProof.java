import android.content.Context;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialScissorModule;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class SpatialScissorFacadeHostProof {
    private static native int[] nativeProject(int width,int height,float[] rect);
    private static int checks;
    private static void yes(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static class OwnerContext extends Context {
        final File root;
        OwnerContext(File root){this.root=root;}
        public File getNoBackupFilesDir(){return new File(root,"no_backup");}
        public File getCodeCacheDir(){return new File(root,"code_cache");}
    }
    private static void selection(String json,int generation,String profile) {
        yes(json.contains("\"generation\":"+generation+","),"actual generation "+json);
        yes(json.contains("\"selected_profile_sha256\":\""+profile+"\""),"selected lineage");
        yes(json.contains("\"native_pid\":"+ProcessHandle.current().pid()+","),"native owner PID");
    }
    public static void main(String[] args)throws Exception {
        OwnerContext context=new OwnerContext(new File(args[1]));
        List<String[]> modules=new ArrayList<>();Path manifest=Path.of(args[0]);
        for(String line:Files.readAllLines(manifest))modules.add(line.split("\t"));
        String a="a".repeat(64),b="b".repeat(64),c="c".repeat(64);
        String initial=SpatialScissorModule.status(a);selection(initial,0,"");
        for(int index:new int[]{0,1,2,3}) {
            String[] row=modules.get(index);byte[] bytes=Files.readAllBytes(manifest.getParent().resolve(row[3]));
            String staged=SpatialScissorModule.stageOnOwnerWorker(context,Long.parseLong(row[1]),row[2],bytes);
            yes(staged.contains("\"staged\":true"),"fixed incoming image not staged");
        }
        byte[] originalA=Files.readAllBytes(manifest.getParent().resolve(modules.get(0)[3]));
        try {SpatialScissorModule.stageOnOwnerWorker(context,1,"0".repeat(64),originalA);throw new AssertionError("bad stage hash accepted");}catch(java.io.IOException expected){checks++;}
        try {SpatialScissorModule.stageOnOwnerWorker(context,1,modules.get(1)[2],Files.readAllBytes(manifest.getParent().resolve(modules.get(1)[3])));throw new AssertionError("immutable generation replaced");}catch(java.io.IOException expected){checks++;}
        yes(Arrays.equals(originalA,Files.readAllBytes(context.getNoBackupFilesDir().toPath().resolve("development-scissor-v1/incoming/generation-1.so"))),"rejected stage preserves bytes");
        try {SpatialScissorModule.apply(context,Runnable::run,a,"status",0,null,"product");throw new AssertionError("status policy accepted");}catch(IllegalArgumentException expected){checks++;}
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            String first=SpatialScissorModule.apply(context,worker,a,"activate",1,modules.get(0)[2],"product").get();
            yes(first.contains("\"outcome\":\"accepted\""),"actual A admission");selection(first,1,a);
            String collision=SpatialScissorModule.apply(context,worker,b,"activate",1,modules.get(0)[2],"inset_one_pixel").get();
            yes(collision.contains("GENERATION_COLLISION"),"same generation policy collision refused");selection(collision,1,a);
            yes(Arrays.equals(nativeProject(1920,1080,new float[]{.1f,.2f,.55f,.5f}),new int[]{192,216,1056,540}),"actual renderer A");
            String refused=SpatialScissorModule.apply(context,worker,b,"activate",4,modules.get(3)[2],"product").get();
            yes(refused.contains("\"outcome\":\"rejected\""),"bad table rejected");selection(refused,1,a);
            String second=SpatialScissorModule.apply(context,worker,b,"activate",2,modules.get(1)[2],"inset_one_pixel").get();selection(second,2,b);
            yes(Arrays.equals(nativeProject(1920,1080,new float[]{.1f,.2f,.55f,.5f}),new int[]{193,217,1054,538}),"actual renderer B");
            String status=SpatialScissorModule.status(b);yes(status.contains("\"last_computed\":{\"generation\":2,"),"actual renderer witness");
            selection(SpatialScissorModule.apply(context,worker,c,"select_retained",1,null,null).get(),1,c);
            yes(Arrays.equals(nativeProject(1920,1080,new float[]{.1f,.2f,.55f,.5f}),new int[]{192,216,1056,540}),"retained A rollback");
            selection(SpatialScissorModule.apply(context,worker,a,"activate",3,modules.get(2)[2],"product").get(),3,a);
            List<Runnable> queued=new ArrayList<>();
            CompletableFuture<String> cancelled=SpatialScissorModule.apply(context,queued::add,b,"select_retained",2,null,null);
            yes(!cancelled.isDone(),"borrowed worker owns queued admission");yes(cancelled.cancel(false),"queued cancellation wins");yes(cancelled.isCancelled(),"cancelled future completes even discarded");
            queued.get(0).run();selection(SpatialScissorModule.status(b),3,a);
            CompletableFuture<String> completed=SpatialScissorModule.apply(context,Runnable::run,c,"select_retained",2,null,null);
            yes(completed.isDone()&&!completed.cancel(false),"published admission cannot be falsely cancelled");selection(completed.get(),2,c);
            Executor rejecting=job->{throw new RejectedExecutionException("fixture owner closed");};
            String closed=SpatialScissorModule.apply(context,rejecting,b,"select_retained",1,null,null).get();yes(closed.contains("OWNER_EXECUTOR_REJECTED"),"owner rejection is completed without caller-runs");selection(closed,2,c);
            CompletableFuture<String> stat=SpatialScissorModule.apply(context,rejecting,b,"static",0,null,null);
            yes(stat.isDone(),"static is immediate, does not use owner executor");selection(stat.get(),0,b);
            yes(Arrays.equals(nativeProject(1920,1080,new float[]{.1f,.2f,.55f,.5f}),new int[]{192,216,1056,540}),"compiled policy restored");
            yes(SpatialScissorModule.apply(context,rejecting,a,"status",0,null,null).isDone(),"status immediate");
        } finally {worker.shutdownNow();yes(worker.awaitTermination(10,TimeUnit.SECONDS),"test owner worker joined");}
        System.out.println("PASS real JNI scissor facade checks="+checks);
    }
}
