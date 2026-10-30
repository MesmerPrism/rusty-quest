package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Optional developer scissor client. Existing addon transport owns dispatch. */
public final class SpatialScissorModule {
    static { System.loadLibrary("spatial_camera_panel_native_receipt"); }
    private static final int MAX_IMAGE=4*1024*1024;
    private static final Object STAGING=new Object();
    private static File sessionRoot,session;
    private SpatialScissorModule() {}
    /** Existing addon upload owner calls this on its worker, before publishing a profile. */
    public static String stageOnOwnerWorker(Context context,long generation,String sha256,byte[] bytes)throws IOException {
        if(generation<=0||!hex(sha256)||bytes==null||bytes.length==0||bytes.length>MAX_IMAGE)throw new IllegalArgumentException("scissor artifact shape");
        byte[] immutable=bytes.clone();
        if(!digest(immutable).equals(sha256))throw new StageFailure("INCOMING_SHA256");
        File root=directory(context.getNoBackupFilesDir(),"development-scissor-v1");
        File incoming=directory(root,"incoming");
        File destination=new File(incoming,"generation-"+generation+".so");
        synchronized(STAGING){publishFile(destination,immutable,sha256);}
        return "{\"schema\":\"rusty.quest.development.scissor.stage.v1\",\"staged\":true,\"generation\":"+generation+",\"sha256\":\""+sha256+"\",\"bytes\":"+immutable.length+"}";
    }
    public static CompletableFuture<String> apply(Context context,Executor ownerExecutor,String profile,String mode,long generation,String sha256,String policy) {
        validate(profile,mode,generation,sha256,policy);
        if(mode.equals("status"))return CompletableFuture.completedFuture(nativeStatus(profile));
        if(mode.equals("static"))return CompletableFuture.completedFuture(nativeStatic(profile));
        if(ownerExecutor==null||context==null)throw new IllegalArgumentException("scissor owner");
        long request=nativeBegin();
        if(request<=0)return CompletableFuture.completedFuture(rejected(profile,"BEGIN"));
        AdmissionFuture result=new AdmissionFuture(request);
        try {
            ownerExecutor.execute(()->{
                try {
                    if(result.isCancelled())return;
                    String receipt;
                    if(mode.equals("activate")) {
                        File staged=prepareImage(context,generation,sha256);
                        if(result.isCancelled())return;
                        receipt=nativeActivate(request,staged.getCanonicalPath(),sha256,generation,policy.equals("inset_one_pixel")?1:0,profile);
                    } else receipt=nativeSelect(request,generation,profile);
                    if(receipt==null)throw new StageFailure("NATIVE_RECEIPT");
                    result.complete(receipt);
                } catch(Exception failure) {
                    nativeCancel(request);
                    result.complete(rejected(profile,failure instanceof StageFailure?((StageFailure)failure).code:"STAGING_IO"));
                } catch(Error failure) {
                    nativeCancel(request);
                    result.completeExceptionally(failure);
                } finally {nativeEnd(request);}
            });
        } catch(RuntimeException rejected) {
            nativeCancel(request);nativeEnd(request);
            result.complete(rejected(profile,"OWNER_EXECUTOR_REJECTED"));
        }
        return result;
    }
    public static String status(String profile) {
        if(!hex(profile))throw new IllegalArgumentException("scissor profile hash");
        return nativeStatus(profile);
    }
    private static final class AdmissionFuture extends CompletableFuture<String> {
        private final long request;
        AdmissionFuture(long request){this.request=request;}
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if(isDone())return isCancelled();
            // Native publication and cancellation share one linearization point.
            // No caller-runs, interrupt, unload or guessed cancellation result.
            if(!nativeCancel(request))return false;
            boolean cancelled=super.cancel(false);nativeEnd(request);return cancelled;
        }
    }
    private static void validate(String profile,String mode,long generation,String sha,String policy) {
        if(!hex(profile)||mode==null)throw new IllegalArgumentException("scissor directive");
        if(mode.equals("activate")) {
            if(generation<=0||!hex(sha)||!("product".equals(policy)||"inset_one_pixel".equals(policy)))throw new IllegalArgumentException("scissor activate");
        } else if(mode.equals("select_retained")) {
            if(generation<=0||sha!=null||policy!=null)throw new IllegalArgumentException("scissor retained selection");
        } else if(mode.equals("status")||mode.equals("static")) {
            if(generation!=0||sha!=null||policy!=null)throw new IllegalArgumentException("scissor static/status");
        } else throw new IllegalArgumentException("scissor mode");
    }
    private static File prepareImage(Context context,long generation,String sha)throws IOException {
        File incoming=new File(directory(directory(context.getNoBackupFilesDir(),"development-scissor-v1"),"incoming"),"generation-"+generation+".so");
        File code=directory(context.getCodeCacheDir(),"development-scissor-v1");
        synchronized(STAGING) {
            if(session==null) {
                sessionRoot=code;session=new File(code,"process-"+UUID.randomUUID());
                if(!session.mkdir())throw new StageFailure("STAGING_SESSION");
            }
            if(!sessionRoot.equals(code))throw new StageFailure("CONTEXT_ROOT_CHANGED");
            File staged=new File(session,"generation-"+generation+"-"+sha+".so");
            if(Files.exists(staged.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                if(!digest(readImage(staged)).equals(sha))throw new StageFailure("STAGED_SHA256");
            } else publishFile(staged,readImage(incoming),sha);
            return staged.getCanonicalFile();
        }
    }
    private static File directory(File parent,String child)throws IOException {
        if(parent==null)throw new StageFailure("STAGING_DIRECTORY");
        File base=parent.getCanonicalFile();
        if(!base.isDirectory()&&!base.mkdirs())throw new StageFailure("STAGING_DIRECTORY");
        File directory=new File(base,child);
        if(Files.isSymbolicLink(directory.toPath()))throw new StageFailure("STAGING_DIRECTORY");
        if(!directory.isDirectory()&&!directory.mkdir())throw new StageFailure("STAGING_DIRECTORY");
        if(!directory.getCanonicalFile().getParentFile().equals(base))throw new StageFailure("STAGING_DIRECTORY");
        return directory.getCanonicalFile();
    }
    private static byte[] readImage(File file)throws IOException {
        if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.canWrite()||file.length()==0||file.length()>MAX_IMAGE)throw new StageFailure("IMAGE_FILE");
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192];int count;
            while((count=in.read(block))!=-1){if(out.size()+count>MAX_IMAGE)throw new StageFailure("IMAGE_SIZE");out.write(block,0,count);}
            return out.toByteArray();
        }
    }
    // STAGING serializes this facade's sole writers. No module/native callback
    // occurs while it is held. Temp bytes remain inspectable on failed I/O.
    private static void publishFile(File destination,byte[] bytes,String sha)throws IOException {
        if(!digest(bytes).equals(sha))throw new StageFailure("INCOMING_SHA256");
        if(Files.exists(destination.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            if(!digest(readImage(destination)).equals(sha))throw new StageFailure("GENERATION_COLLISION");
            return;
        }
        File temporary=new File(destination.getParentFile(),".pending-"+UUID.randomUUID());
        if(!temporary.createNewFile())throw new StageFailure("STAGING_TEMP");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
        if(!temporary.setReadOnly())throw new StageFailure("STAGING_READONLY");
        if(!digest(readImage(temporary)).equals(sha))throw new StageFailure("STAGING_SHA256");
        // Destination is absent and this facade is its sole writer. No replace
        // option is used; existing immutable images are never rewritten.
        Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    private static boolean hex(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static String digest(byte[] bytes) {
        try {byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder(64);for(byte b:hash)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable",unavailable);}
    }
    private static String rejected(String profile,String reason) {
        return "{\"schema\":\"rusty.quest.development.scissor.failure.v1\",\"outcome\":\"rejected\",\"reason\":\""+reason+"\",\"request_profile_sha256\":\""+profile+"\",\"selection\":"+nativeStatus(profile)+"}";
    }
    private static final class StageFailure extends IOException {
        private static final long serialVersionUID=1L;
        final String code;StageFailure(String code){super(code);this.code=code;}
    }
    private static native long nativeBegin();
    private static native boolean nativeCancel(long request);
    private static native void nativeEnd(long request);
    private static native String nativeActivate(long request,String path,String sha256,long generation,int policy,String profile);
    private static native String nativeSelect(long request,long generation,String profile);
    private static native String nativeStatic(String profile);
    private static native String nativeStatus(String profile);
}
