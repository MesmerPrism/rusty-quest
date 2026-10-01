package io.github.mesmerprism.rustyquest.media;
import android.content.Context;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import org.json.JSONObject;

public final class CaptureCloseTest {
    private static int cases;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static PackedStereoCaptureOwner owner(CameraManager manager){
        Context.manager=manager;PackedStereoGlCompositor.nativePoolRetired=false;
        return new PackedStereoCaptureOwner(new Context(),16,16,30,"left","right",1000000,new PackedStereoPoolExecutor());
    }
    private static void await(java.util.function.BooleanSupplier ready)throws Exception{
        long end=System.nanoTime()+2000000000L;
        while(!ready.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("fixture callback wait");Thread.sleep(1);}
    }
    private static Thread start(PackedStereoCaptureOwner owner){Thread t=new Thread(()->{try{owner.start();}catch(Exception expected){/* stop/configuration/error cases */}});t.start();return t;}
    public static void main(String[] args)throws Exception{
        boolean baseline=args.length!=0;
        CameraManager manager=new CameraManager();PackedStereoCaptureOwner owner=owner(manager);owner.start();
        check(manager.devices[0].session.inflight&&manager.devices[1].session.inflight,"actual repeating capture not installed");
        JSONObject before = owner.captureDiagnosticSnapshot();
        check(before.getJSONObject("left_camera_result").getLong("count") == 0L,
                "unobserved camera callback invented progress");
        manager.devices[0].session.emitCapture(7L, 123L);
        manager.devices[0].session.emitCapture(8L, null);
        JSONObject after = owner.captureDiagnosticSnapshot();
        check(after.getJSONObject("left_camera_result").getLong("count") == 2L
                && after.getJSONObject("left_camera_metadata").getLong("count") == 1L
                && after.getJSONObject("right_camera_result").getLong("count") == 0L,
                "raw camera callbacks, valid metadata, and eyes were conflated");
        owner.requestStop();owner.requestStop();
        check(!owner.pollStopped(),"close request falsely proved physical retirement");
        if(baseline){
            check(manager.devices[0].closed,"baseline did not immediately close device");
            for(CameraDevice d:manager.devices){d.completeDeviceClose();d.session.drain();}
            PackedStereoGlCompositor.nativePoolRetired=true;
            check(!owner.pollStopped(),"baseline must retain suppressed session callback");
            check("SESSION_CLOSE_CALLBACK_PENDING".equals(owner.cleanupStatus().leftCallbackBarrier),"motivating pending barrier differs");
            System.out.println("BASELINE_REPRODUCED: in-flight close suppresses sequence drain; device callback true, session pending");return;
        }
        check(!manager.devices[0].closed&&!manager.devices[1].closed,"device closed before session drain");
        for(CameraDevice d:manager.devices){d.session.drain();check(d.closed,"session onClosed failed to close device");}
        check(!owner.pollStopped(),"device onClosed callback fabricated");
        for(CameraDevice d:manager.devices)d.completeDeviceClose();
        check(!owner.pollStopped(),"camera callbacks bypassed native pool/compositor proof");
        PackedStereoGlCompositor.nativePoolRetired=true;check(owner.pollStopped(),"complete callbacks did not retire capture");
        owner.requestStop();check(owner.pollStopped(),"idempotent stop resurrected capture");cases++;

        manager=new CameraManager();manager.devices[0].deferConfigure=true;owner=owner(manager);Thread t=start(owner);
        final CameraManager configuring=manager;await(()->configuring.devices[0].session!=null);
        owner.requestStop();check(!manager.devices[0].closed,"device closed while configuration callback pending");
        manager.devices[0].configure();t.join(2000);check(!t.isAlive(),"capture-control worker blocked after late configured callback");
        check(!owner.pollStopped(),"late configured callback falsely proved close");
        manager.devices[0].session.drain();manager.devices[0].completeDeviceClose();
        PackedStereoGlCompositor.nativePoolRetired=true;check(owner.pollStopped(),"late configured session was not accounted for");cases++;

        manager=new CameraManager();manager.deferOpen=true;owner=owner(manager);t=start(owner);
        final CameraManager opening=manager;await(()->opening.devices[0].callback!=null);
        owner.requestStop();manager.opened(0);t.join(2000);
        check(manager.devices[0].closed&&manager.devices[0].session==null,"late onOpened created a stopped session");
        manager.devices[0].completeDeviceClose();PackedStereoGlCompositor.nativePoolRetired=true;
        check(owner.pollStopped(),"late open close callback was not accounted for");cases++;

        manager=new CameraManager();manager.devices[0].failConfigure=true;owner=owner(manager);try{owner.start();}catch(Exception expected){}
        check(owner.firstFailureOriginCode()==PackedStereoCaptureOwner.FAILURE_SESSION_REJECTED,
                "startup catch replaced the earlier camera session failure");
        check(owner.captureDiagnosticSnapshot().getLong("first_failure_elapsed_ns")>0L,
                "first capture fault missing from retained diagnostic");
        check(manager.devices[0].closed,"already-closed failed configuration retained device");
        check(!owner.pollStopped(),"configure failure invented device close callback");
        manager.devices[0].completeDeviceClose();PackedStereoGlCompositor.nativePoolRetired=true;
        check(owner.pollStopped(),"documented configure-failure terminal callback waited for nonexistent session onClosed");cases++;

        manager=new CameraManager();manager.deferOpen=true;owner=owner(manager);t=start(owner);
        final CameraManager errorOpening=manager;await(()->errorOpening.devices[0].callback!=null);
        manager.devices[0].callback.onError(manager.devices[0],1);t.join(2000);
        check(owner.firstFailureOriginCode()==PackedStereoCaptureOwner.FAILURE_CAMERA_ERROR,
                "camera callback first fault replaced by startup catch");
        manager.devices[0].callback.onDisconnected(manager.devices[0]);
        check(owner.firstFailureOriginCode()==PackedStereoCaptureOwner.FAILURE_CAMERA_ERROR,
                "late disconnect replaced first fault");
        check(manager.devices[0].closed,"device error did not request immediate platform close");
        check(!owner.pollStopped(),"device error fabricated physical onClosed");
        manager.devices[0].completeDeviceClose();PackedStereoGlCompositor.nativePoolRetired=true;
        check(owner.pollStopped(),"pre-session device error callback did not retire");cases++;

        manager=new CameraManager();owner=owner(manager);owner.start();manager.devices[0].callback.onError(manager.devices[0],1);
        for(CameraDevice d:manager.devices){d.session.drain();d.completeDeviceClose();}
        PackedStereoGlCompositor.nativePoolRetired=true;
        check(!owner.pollStopped(),"device error with missing successful-session drain was falsely terminal");cases++;

        manager=new CameraManager();owner=owner(manager);owner.start();
        android.util.Log.firstFailure=null;
        java.lang.reflect.Field compositorField=PackedStereoCaptureOwner.class.getDeclaredField("compositor");
        compositorField.setAccessible(true);
        PackedStereoGlCompositor gl=(PackedStereoGlCompositor)compositorField.get(owner);
        gl.simulateFailure(new IllegalArgumentException("fixture private text"));
        check(owner.firstFailureOriginCode()==PackedStereoCaptureOwner.FAILURE_COMPOSITOR
                && owner.firstFailureCauseCode()==2,
                "compositor first fault not classified before teardown");
        JSONObject compositorFault=owner.captureDiagnosticSnapshot();
        check(compositorFault.getLong("first_failure_origin_code")==PackedStereoCaptureOwner.FAILURE_COMPOSITOR
                && compositorFault.getLong("first_failure_cause_code")==2,
                "diagnostic does not retain exact first fault");
        check(android.util.Log.firstFailure!=null
                && android.util.Log.firstFailure.contains("originCode=1")
                && !android.util.Log.firstFailure.contains("fixture private text"),
                "first-fault event missing or leaked exception text");
        manager.devices[0].callback.onError(manager.devices[0],1);
        check(owner.firstFailureOriginCode()==PackedStereoCaptureOwner.FAILURE_COMPOSITOR,
                "late camera error overwrote compositor first fault");cases++;

        manager=new CameraManager();owner=owner(manager);owner.start();
        compositorField.setAccessible(true);
        gl=(PackedStereoGlCompositor)compositorField.get(owner);
        owner.requestStop();
        gl.simulateFailure(new IllegalStateException("late cleanup callback"));
        check(owner.firstFailureOriginCode()==PackedStereoCaptureOwner.FAILURE_NONE
                && owner.captureDiagnosticSnapshot().getLong("first_failure_elapsed_ns")==0L,
                "post-stop callback invented a first runtime failure");cases++;

        java.lang.reflect.Method classify=PackedStereoCaptureOwner.class
                .getDeclaredMethod("detailCode",Throwable.class);
        classify.setAccessible(true);
        check(((Number)classify.invoke(null,new IllegalStateException("source publication Replay"))).intValue()
                ==PackedStereoCaptureOwner.DETAIL_PUBLICATION_REPLAY,
                "exact native Replay rejection not classified");
        check(((Number)classify.invoke(null,new IllegalStateException("source publication RegressedClock"))).intValue()
                ==PackedStereoCaptureOwner.DETAIL_PUBLICATION_REGRESSED_CLOCK,
                "exact native clock rejection not classified");
        check(((Number)classify.invoke(null,new IllegalStateException("source publication UnboundEpoch"))).intValue()
                ==PackedStereoCaptureOwner.DETAIL_PUBLICATION_UNBOUND,
                "exact native epoch rejection not classified");
        check(((Number)classify.invoke(null,new IllegalStateException("source publication InvalidIdentity"))).intValue()
                ==PackedStereoCaptureOwner.DETAIL_PUBLICATION_IDENTITY,
                "exact native identity rejection not classified");
        check(((Number)classify.invoke(null,new IllegalStateException("source publication Replay unknown"))).intValue()
                ==PackedStereoCaptureOwner.DETAIL_OTHER,
                "unrecognized native text must remain unclassified");cases++;
        System.out.println("CANDIDATE_PASS: "+cases+" complete close/callback/native-proof cases; source stubs, no Quest proof");
    }
}
