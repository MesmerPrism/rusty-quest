package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;

/** Production Java eligibility with modeled JNI reads; no native resource or device proof. */
public final class EmbeddedDuplexRendererOwnEpochHostTest {
    private static final long NATIVE = 2059246347458447610L;
    private static int controls;
    private static JSONObject own() throws Exception {
        return new JSONObject().put("$schema","rusty.quest.embedded_duplex.renderer_fresh_setup_eligibility.v1")
            .put("state","own_live_independent").put("own_process_generation",NATIVE)
            .put("own_source_generation",7).put("construction_attempts",1).put("active_workers",1)
            .put("peer_source_inactive",true).put("retained_peer_payloads",0)
            .put("cleanup_code",0).put("carrier_live",true);
    }
    private static void setup(JSONObject value,long first,long last) {
        EmbeddedDuplexNative.value=value.toString(); EmbeddedDuplexNative.reads=0;
        StereoRendererTerminalStatus.epochs=new long[]{first,last}; StereoRendererTerminalStatus.reads=0;
    }
    private static boolean current(String state) throws Exception {
        // The fallback records RED against the original app-generation call.
        // It does not supply a runtime compatibility route or change production.
        try {
            try { return (Boolean)EmbeddedDuplexRendererRetirement.class
                .getDeclaredMethod("currentFreshEligible",String.class).invoke(null,state); }
            catch(NoSuchMethodException oldApi) { return (Boolean)EmbeddedDuplexRendererRetirement.class
                .getDeclaredMethod("currentFreshEligible",String.class,long.class).invoke(null,state,63L); }
        } catch(InvocationTargetException failure) {
            Throwable cause=failure.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            throw failure;
        }
    }
    private static void rejected(JSONObject value,long first,long last) throws Exception {
        setup(value,first,last);
        try { current("live_independent"); throw new AssertionError("invalid native Own witness accepted"); }
        catch(IllegalArgumentException|IllegalStateException expected) { controls++; }
    }
    public static void main(String[] args) throws Exception {
        setup(own(),NATIVE,NATIVE);
        if(!current("live_independent")) throw new AssertionError("same native Own epoch rejected");
        if(EmbeddedDuplexNative.reads!=1 || StereoRendererTerminalStatus.reads!=2)
            throw new AssertionError("one renderer read must be bracketed by native Own epoch reads");
        controls++;
        rejected(own().put("own_process_generation",NATIVE+1),NATIVE,NATIVE);
        rejected(own(),NATIVE,NATIVE+1);
        rejected(own(),0,0);
        rejected(own(),NATIVE,0);
        rejected(own().put("own_source_generation",0),NATIVE,NATIVE);
        rejected(own().put("own_source_generation",-1),NATIVE,NATIVE);
        rejected(own().put("retained_peer_payloads",1),NATIVE,NATIVE);
        rejected(own().put("peer_source_inactive",false),NATIVE,NATIVE);
        rejected(own().put("active_workers",0),NATIVE,NATIVE);
        rejected(own().put("carrier_live",false),NATIVE,NATIVE);
        rejected(own().put("construction_attempts",0),NATIVE,NATIVE);
        setup(own(),NATIVE,NATIVE);
        if(current("pending")) throw new AssertionError("Java Own pending accepted"); controls++;
        setup(own().put("state","pending_or_unproven"),NATIVE,NATIVE);
        if(current("live_independent")) throw new AssertionError("native pending accepted"); controls++;
        JSONObject terminal=own().put("state","terminal").put("own_process_generation",JSONObject.NULL)
            .put("own_source_generation",JSONObject.NULL).put("active_workers",0)
            .put("carrier_live",false).put("cleanup_code",2);
        setup(terminal,NATIVE,NATIVE);
        if(!current("retired")) throw new AssertionError("actual terminal classification rejected"); controls++;
        setup(terminal,NATIVE,NATIVE);
        if(current("live_independent")) throw new AssertionError("Own live became whole retirement"); controls++;
        System.out.println("Production renderer Own/native epoch: "+controls+" controls PASS; JNI/resource facts modeled");
    }
}
