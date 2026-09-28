package io.github.mesmerprism.rustyquest.media;
import android.content.Context;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;

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
        check(manager.devices[0].closed,"already-closed failed configuration retained device");
        check(!owner.pollStopped(),"configure failure invented device close callback");
        manager.devices[0].completeDeviceClose();PackedStereoGlCompositor.nativePoolRetired=true;
        check(owner.pollStopped(),"documented configure-failure terminal callback waited for nonexistent session onClosed");cases++;

        manager=new CameraManager();manager.deferOpen=true;owner=owner(manager);t=start(owner);
        final CameraManager errorOpening=manager;await(()->errorOpening.devices[0].callback!=null);
        manager.devices[0].callback.onError(manager.devices[0],1);t.join(2000);
        check(manager.devices[0].closed,"device error did not request immediate platform close");
        check(!owner.pollStopped(),"device error fabricated physical onClosed");
        manager.devices[0].completeDeviceClose();PackedStereoGlCompositor.nativePoolRetired=true;
        check(owner.pollStopped(),"pre-session device error callback did not retire");cases++;

        manager=new CameraManager();owner=owner(manager);owner.start();manager.devices[0].callback.onError(manager.devices[0],1);
        for(CameraDevice d:manager.devices){d.session.drain();d.completeDeviceClose();}
        PackedStereoGlCompositor.nativePoolRetired=true;
        check(!owner.pollStopped(),"device error with missing successful-session drain was falsely terminal");cases++;
        System.out.println("CANDIDATE_PASS: "+cases+" complete close/callback/native-proof cases; source stubs, no Quest proof");
    }
}
