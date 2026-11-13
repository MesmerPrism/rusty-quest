package io.github.mesmerprism.rustyquest;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.Process;
import java.io.FileInputStream;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Separate closed token; the existing v2 producer transactions remain byte compatible. */
public final class NativeHeldReviewContract {
    public static final String DESCRIPTOR="io.github.mesmerprism.rustyquest.NativeHeldReview.v1";
    public static final int REQUEST_RETIRE=IBinder.FIRST_CALL_TRANSACTION+80;
    public static final int GET_REVIEW_STATUS=IBinder.FIRST_CALL_TRANSACTION+81;
    private NativeHeldReviewContract() {}
    static long validateOwnRequest(Context context,String request) {
        if (request==null || request.length()>4096 || (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)==0) throw new SecurityException("closed debug request required");
        try {
            JSONObject r=new JSONObject(request);
            if (r.getInt("app_pid")!=Process.myPid() || r.getInt("app_uid")!=Process.myUid()
                    || !hashFile(context.getApplicationInfo().sourceDir).equals(r.getString("app_apk_sha256"))) throw new SecurityException("actual app actor/artifact mismatch");
            long g=r.getLong("generation"); if (g<=0) throw new IllegalArgumentException("positive generation required"); return g;
        } catch (SecurityException e) {throw e;}
        catch (Exception e) {throw new IllegalArgumentException("invalid review request",e);}
    }
    static String hashFile(String path) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(FileInputStream f=new FileInputStream(path)) {byte[] b=new byte[65536];int n;while((n=f.read(b))!=-1)d.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
    static String hashBytes(byte[] bytes) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();
        for(byte b:hash)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
}
