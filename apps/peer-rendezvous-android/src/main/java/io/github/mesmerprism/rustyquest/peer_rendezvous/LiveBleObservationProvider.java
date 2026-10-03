package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;

/** Signature-protected read-only live process observation; no receipt reader or mutator. */
public final class LiveBleObservationProvider extends ContentProvider {
    public static final String PERMISSION="io.github.mesmerprism.rustyquest.peer_rendezvous.READ_LIVE_OBSERVATION";
    private static BleRendezvousConfig active;
    private static BleRendezvousEvidence activeEvidence;
    static synchronized void bind(BleRendezvousConfig c,BleRendezvousEvidence e){unbind();active=c;activeEvidence=e;}
    static synchronized void unbind(){if(active!=null&&active.liveState!=null)active.liveState.clear();active=null;activeEvidence=null;}
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        int uid=Binder.getCallingUid();String[] callers=getContext().getPackageManager().getPackagesForUid(uid);
        String caller=callers!=null&&callers.length==1?callers[0]:"";
        LiveBleObservationState.requireCaller(caller,getContext().getPackageManager().checkSignatures(uid,android.os.Process.myUid())==PackageManager.SIGNATURE_MATCH,
                getContext().checkCallingPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED);
        if(!"read-current".equals(method)||arg!=null||extras==null||extras.size()!=5
                ||!extras.containsKey("run_id")||!extras.containsKey("session_tag")||!extras.containsKey("coordination_epoch")
                ||!extras.containsKey("peer_tag")||!extras.containsKey("expected_peer_tag"))throw new IllegalArgumentException("closed_live_call");
        Bundle result=new Bundle();
        synchronized(LiveBleObservationProvider.class) {
            if(active==null||active.liveState==null||activeEvidence==null)return result;
            synchronized(activeEvidence){if(activeEvidence.coordinationFailed||activeEvidence.authenticationFailures>0){active.liveState.clear();return result;}}
            try {
                String[] v=active.liveState.read(extras.getString("run_id"),extras.getString("session_tag"),extras.getLong("coordination_epoch"),
                        extras.getString("peer_tag"),extras.getString("expected_peer_tag"),active.observation,SystemClock.elapsedRealtime());
                String[] keys={"run_id","session_tag","coordination_epoch","peer_tag","expected_peer_tag","boot_tag","group_tag","observed_role","owner_ipv4","local_ipv4","remote_boot_tag","remote_role","authenticated_elapsed_ms","observed_elapsed_ms"};
                JSONObject json=new JSONObject();json.put("schema","rusty.quest.live_ble_observation.v1");
                for(int i=0;i<v.length;i++)json.put(keys[i],v[i]);result.putString("observation",json.toString());
            }catch(Exception unavailable){/* Closed empty result is unavailable, never historical PASS. */}
        }
        return result;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new SecurityException("read_current_only");}
    @Override public String getType(Uri u){throw new SecurityException("read_current_only");}
    @Override public Uri insert(Uri u,ContentValues v){throw new SecurityException("read_current_only");}
    @Override public int delete(Uri u,String s,String[] a){throw new SecurityException("read_current_only");}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new SecurityException("read_current_only");}
}
