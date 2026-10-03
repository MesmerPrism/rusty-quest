package io.github.mesmerprism.rustyquest.directp2p;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.*;import java.util.*;
/** Only shell/DUMP may read current source-owned formation. No mutation routes. */
public final class FormationObservationProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        if(Binder.getCallingUid()!=2000||getContext().checkCallingPermission("android.permission.DUMP")!=android.content.pm.PackageManager.PERMISSION_GRANTED||!"current-formation".equals(method)||arg!=null||extras==null||!extras.keySet().equals(new HashSet<>(Arrays.asList("run_id","run_token"))))throw new SecurityException("formation_caller_or_request");
        try{Map<String,String> v=FormationObservationState.read(2000,extras.getString("run_id"),extras.getString("run_token"),SystemClock::elapsedRealtime);Bundle result=new Bundle();for(Map.Entry<String,String> e:v.entrySet())result.putString(e.getKey(),e.getValue());return result;}catch(Exception e){throw new SecurityException("formation_unavailable",e);}
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new SecurityException("unsupported");}
    @Override public String getType(Uri u){throw new SecurityException("unsupported");}
    @Override public Uri insert(Uri u,ContentValues v){throw new SecurityException("unsupported");}
    @Override public int delete(Uri u,String s,String[] a){throw new SecurityException("unsupported");}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new SecurityException("unsupported");}
}
