package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.media.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Actual receiver prepare/getter boundaries; all display/reader/codec effects are fixtures. */
public final class SinkArmStageRegression {
    private static String failure;
    private static void injected(String stage) { if (stage.equals(failure)) throw new IllegalStateException("PRIVATE_SENTINEL_MUST_NOT_APPEAR_IN_STAGE"); }
    private static final EmbeddedDuplexDisplay DISPLAY = new EmbeddedDuplexDisplay() {
        public long ensureLocalCaptureStopped() { return 1; }
        public long preparePeerProjection() { injected("PEER_PROJECTION"); return 2; }
        public void bindPeerProjection(long a,long b,long c) { injected("PEER_BIND"); }
        public void activatePeerProjection(long a,long b,long c) { throw new AssertionError("no activation"); }
        public long[] currentProjection(long a) { return null; }
        public void retirePeerProjection() { }
        public void restoreLocalAfterProductCleanup() { }
    };
    private static final MediaOwnerProvider PROVIDER = new MediaOwnerProvider() {
        public MediaProviderReadback execute(MediaOwnerAction a,CancellationHandle c) { injected("RECEIVER_EFFECT"); throw new AssertionError("no provider success claim"); }
        public MediaProviderReadback compensate(MediaOwnerAction a,CancellationHandle c) { throw new IllegalStateException("PRIVATE_CLEANUP_FAILURE"); }
        public MediaRuntimeSnapshot snapshot() { return null; }
    };
    private static final EmbeddedDuplexReceiver.RuntimeFactory FACTORY = new EmbeddedDuplexReceiver.RuntimeFactory() {
        public EmbeddedDuplexReceiver.ProjectionResource stage(int w,int h,int n,int f,long g) {
            injected("READER_STAGE");
            return new EmbeddedDuplexReceiver.ProjectionResource() {
                public long routeGeneration() { return 2; }
                public long decoderToken() { return 3; }
                public long readerGeneration() { injected("READER_IDENTITY"); return 4; }
                public boolean release() { throw new AssertionError("no physical release claim"); }
            };
        }
        public EmbeddedDuplexReceiver.ReceiverRuntime create(EmbeddedDuplexReceiver.ProjectionResource p,String h,int port,long g,PackedStereoMediaReceiver.Bounds b,PackedStereoMediaReceiver.FrameLifecycleListener l) {
            injected("RECEIVER_CREATE");
            return new EmbeddedDuplexReceiver.ReceiverRuntime() {
                public MediaOwnerProvider provider() { injected("PROVIDER_GETTER"); return PROVIDER; }
                public void start() { throw new AssertionError("no media Start"); }
                public MediaRuntimeSnapshot snapshot() { return null; }
            };
        }
    };
    public static void main(String[] args) throws Exception {
        Method prepare=EmbeddedDuplexReceiver.class.getDeclaredMethod("prepare"); prepare.setAccessible(true);
        for(String stage:new String[]{"PEER_PROJECTION","READER_STAGE","READER_IDENTITY","RECEIVER_CREATE","PROVIDER_GETTER","PEER_BIND"}) {
            failure=stage;
            EmbeddedDuplexReceiver receiver=new EmbeddedDuplexReceiver(1,DISPLAY,"fixture.invalid",1,2,2,1,
                    new PackedStereoMediaReceiver.Bounds(1024,1024,2,2,1,1,1,1,1,1,0,1),FACTORY);
            try { prepare.invoke(receiver); throw new AssertionError("injection must reject"); }
            catch(InvocationTargetException expected) { if(!(expected.getCause() instanceof IllegalStateException)) throw expected; }
            if(!stage.equals(receiver.failedArmStage())) throw new AssertionError(receiver.failedArmStage());
            if(receiver.failedArmStage().contains("PRIVATE_SENTINEL")) throw new AssertionError("private exception leak");
            // A repeat cannot reinitialize or overwrite the retained original failure stage.
            try { prepare.invoke(receiver); throw new AssertionError("replay must reject"); } catch(InvocationTargetException expected) { }
            if(!stage.equals(receiver.failedArmStage())) throw new AssertionError("retained failure overwritten");
        }
        failure="RECEIVER_EFFECT";
        EmbeddedDuplexReceiver receiver=new EmbeddedDuplexReceiver(1,DISPLAY,"fixture.invalid",1,2,2,1,
                new PackedStereoMediaReceiver.Bounds(1024,1024,2,2,1,1,1,1,1,1,0,1),FACTORY);
        // Fixture-only allocation bypasses ticket parsing; this creates no native execution authority.
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field singleton=unsafeClass.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object unsafe=singleton.get(null);
        MediaOwnerAction ticket=(MediaOwnerAction)unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,MediaOwnerAction.class);
        java.lang.reflect.Field action=MediaOwnerAction.class.getDeclaredField("actionKind"); action.setAccessible(true); action.set(ticket,"arm_receiver");
        try { receiver.execute(ticket,null); throw new AssertionError("arm effect must reject"); } catch(IllegalStateException expected) { }
        if(!"RECEIVER_EFFECT".equals(receiver.failedArmStage())) throw new AssertionError("actual provider effect stage");
        try { receiver.compensate(ticket,null); throw new AssertionError("fixture cleanup must reject"); } catch(IllegalStateException expected) { }
        if(!"RECEIVER_EFFECT".equals(receiver.failedArmStage())) throw new AssertionError("cleanup erased primary receiver stage");
        EmbeddedDuplexPlatform platform=(EmbeddedDuplexPlatform)unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,EmbeddedDuplexPlatform.class);
        set(platform,"failedOwnerStage",EmbeddedDuplexPlatform.OwnerStage.PROVIDER_EXECUTION);
        set(platform,"failedSinkStage",receiver.failedArmStage());set(platform,"failedOwnerAction","ARM_RECEIVER");
        try { platform.executeAndVerify("PRIVATE_AUTHORITY_INPUT","PRIVATE_TICKET_INPUT",true); throw new AssertionError(); } catch(IllegalStateException expected) { }
        String diagnostic=platform.ownerFailureDiagnostic();
        if(!diagnostic.contains("PROVIDER_EXECUTION")||!diagnostic.contains("RECEIVER_EFFECT")||!diagnostic.contains("ARM_RECEIVER")
                ||diagnostic.contains("PRIVATE")||diagnostic.contains("CALLBACK_FENCE")) throw new AssertionError(diagnostic);
        System.out.println("Seven actual receiver failure stages and real Platform JNI getter retain primary arm failure across cleanup rejection; closed diagnostics: PASS (mock effects/getter fixture; no JNI/device/physical terminal claim)");
    }
    private static void set(Object owner,String name,Object value)throws Exception {java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
}
