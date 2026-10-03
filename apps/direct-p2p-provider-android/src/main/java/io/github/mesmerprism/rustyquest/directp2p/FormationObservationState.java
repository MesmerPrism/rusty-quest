package io.github.mesmerprism.rustyquest.directp2p;
import java.util.*;
/** Live source callback only; never retains a last accepted observation. */
final class FormationObservationState {
    interface Source { Map<String,String> current() throws Exception; }
    static final Set<String> KEYS=new HashSet<>(Arrays.asList("run_id","run_token","boot_id","pid","pid_start_ticks","network","owner_mac","local_owner","observed_elapsed_ms"));
    private static Object owner; private static Source source;
    static synchronized void publish(Object actor,Source next){if(actor==null||next==null)throw new SecurityException("formation_source");owner=actor;source=next;}
    static synchronized void clear(Object actor){if(owner==actor){owner=null;source=null;}}
    static Map<String,String> read(int uid,String run,String token,java.util.function.LongSupplier clock)throws Exception{
        if(uid!=2000||run==null||!run.matches("[A-Za-z0-9_-]{1,128}")||token==null||!token.matches("[0-9a-f]{32}"))throw new SecurityException("formation_request");
        Source current;Object actor;synchronized(FormationObservationState.class){current=source;actor=owner;}
        if(current==null)throw new SecurityException("formation_unavailable");Map<String,String> v=current.current();
        synchronized(FormationObservationState.class){if(current!=source||actor!=owner)throw new SecurityException("formation_replaced");}
        if(v==null||!v.keySet().equals(KEYS)||!run.equals(v.get("run_id"))||!token.equals(v.get("run_token"))||!v.get("network").equals("DIRECT-rp-"+token.substring(0,20))||!v.get("boot_id").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!v.get("pid").matches("[1-9][0-9]{0,8}")||!v.get("pid_start_ticks").matches("[0-9]+")||!v.get("owner_mac").matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")||!Arrays.asList("true","false").contains(v.get("local_owner")))throw new SecurityException("formation_identity");
        long now=clock.getAsLong();long observed=Long.parseLong(v.get("observed_elapsed_ms"));if(observed<0||now<observed||now-observed>2000)throw new SecurityException("formation_age");return Collections.unmodifiableMap(new HashMap<>(v));
    }
}
