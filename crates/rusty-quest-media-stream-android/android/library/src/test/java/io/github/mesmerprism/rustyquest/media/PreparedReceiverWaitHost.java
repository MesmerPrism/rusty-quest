package io.github.mesmerprism.rustyquest.media;

import android.os.HandlerThread;
import android.view.Surface;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Complete production receiver; Android SDK fields are explicit host resource models. */
public final class PreparedReceiverWaitHost {
    private static final class Call {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean interrupted;
        final Thread thread;
        Call(Checked body) {
            thread = new Thread(() -> {
                try { body.run(); } catch (Throwable actual) { failure.set(actual); }
                finally { interrupted = Thread.currentThread().isInterrupted(); }
            }, "prepared-receiver-caller");
            thread.setDaemon(true); thread.start();
        }
        void done() throws Exception { thread.join(2000); require(!thread.isAlive(), "caller did not finish"); }
    }
    private interface Checked { void run() throws Exception; }
    private static PackedStereoMediaReceiver receiver(boolean prepared) throws Exception {
        PackedStereoMediaReceiver r = new PackedStereoMediaReceiver(new Surface(), "127.0.0.1", 12345, 7,
                new PackedStereoMediaReceiver.Bounds(4096, 65536, 64, 64, 2, 100, 100, 100, 60, 60, 0, 0), null);
        set(r, "preparedMode", prepared); set(r, "state", "receiver_armed");
        set(r, "connectionState", "listening"); set(r, "ready", new CountDownLatch(1));
        return r;
    }
    private static Field field(String name) throws Exception {
        Field f = PackedStereoMediaReceiver.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void set(Object r, String name, Object value) throws Exception { field(name).set(r, value); }
    private static Object get(Object r, String name) throws Exception { return field(name).get(r); }
    private static void require(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private static void pending(Call call) throws Exception { Thread.sleep(180); require(call.thread.isAlive(), "estimate abandoned actual owner"); }
    private static void receiving(PackedStereoMediaReceiver r) throws Exception {
        synchronized (get(r, "lock")) {
            set(r, "state", "receiving"); set(r, "connectionState", "receiving");
            ((CountDownLatch) get(r, "ready")).countDown();
        }
    }
    private static void localStartQualification()throws Exception {
        PackedStereoMediaReceiver r=receiver(true);CountDownLatch release=new CountDownLatch(1);
        Thread worker=new Thread(()->{try{release.await();}catch(InterruptedException ignored){}});
        HandlerThread callback=new HandlerThread("qualification-callback");callback.hostRelease=release;
        worker.setDaemon(true);callback.setDaemon(true);worker.start();callback.start();
        java.net.ServerSocket listener=new java.net.ServerSocket(); // Unbound modeled listener; no port/network work.
        try {
            Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");Field singleton=unsafeClass.getDeclaredField("theUnsafe");singleton.setAccessible(true);
            Object codec=unsafeClass.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),android.media.MediaCodec.class);
            set(r,"decoder",codec);set(r,"worker",worker);set(r,"renderThread",callback);set(r,"incomingListener",listener);
            set(r,"preparedConnection",1L);set(r,"connectionGeneration",1L);set(r,"state","prepared_decoder_ready");
            require(r.preparedDecoderStartable()&&r.preparedDecoderHeld(),"same dormant prepared decoder refused");
            set(r,"preparedDeliveryOpen",true);set(r,"liveRequested",true);
            for(String state:new String[]{"receiver_armed","receiving"}){
                set(r,"state",state);require(r.preparedDecoderStartable()&&!r.preparedDecoderHeld(),"same incoming active decoder refused");
            }
            for(String state:new String[]{"failed","stopping","stopped","new","preparing_decoder"}){
                set(r,"state",state);require(!r.preparedDecoderStartable(),"unqualified receiver state accepted: "+state);
            }
            set(r,"state","receiving");set(r,"stopRequested",true);require(!r.preparedDecoderStartable(),"stopped prepared receiver accepted");set(r,"stopRequested",false);
            set(r,"failure","modeled actual failure");require(!r.preparedDecoderStartable(),"failed prepared receiver accepted");set(r,"failure","");
            set(r,"firstFailureCode","STREAM_EOF");require(!r.preparedDecoderStartable(),"retained actual transport failure accepted");set(r,"firstFailureCode","NONE");
            set(r,"connectionGeneration",2L);require(!r.preparedDecoderStartable(),"foreign connection accepted");set(r,"connectionGeneration",1L);
            set(r,"liveRequested",false);require(!r.preparedDecoderStartable(),"unrequested open gate accepted");set(r,"liveRequested",true);
            set(r,"decoder",null);require(!r.preparedDecoderStartable(),"missing decoder accepted");set(r,"decoder",codec);
            set(r,"worker",null);require(!r.preparedDecoderStartable(),"missing worker accepted");set(r,"worker",worker);
            set(r,"renderThread",null);require(!r.preparedDecoderStartable(),"missing callback owner accepted");set(r,"renderThread",callback);
            require((boolean)get(r,"preparedDeliveryOpen")&&!(boolean)get(r,"stopRequested"),"read-only qualification changed delivery/Stop");
        } finally {release.countDown();worker.join(1000);callback.join(1000);listener.close();}
    }
    public static void main(String[] args) throws Exception {
        localStartQualification();
        PackedStereoMediaReceiver delayed = receiver(true);
        Call start = new Call(() -> delayed.start(() -> true)); pending(start);
        require((boolean)get(delayed,"preparedDeliveryOpen"), "same prepared delivery not opened");
        receiving(delayed); start.done(); require(start.failure.get() == null, "actual readiness rejected");

        for (boolean stop : new boolean[] {false, true}) {
            PackedStereoMediaReceiver r = receiver(true); AtomicBoolean authority = new AtomicBoolean(true);
            Call blocked = new Call(() -> r.start(authority::get)); pending(blocked);
            if (stop) r.requestPreparedStop(); else authority.set(false);
            blocked.done(); require(blocked.failure.get() != null, "actual invalidity ignored");
            require(!(boolean)get(r,"stopRequested") || stop, "authority invalidity performed unsolicited cleanup");
            require(!r.snapshot().terminal(), "startup invalidity invented terminal cleanup");
        }
        PackedStereoMediaReceiver interruptedStart = receiver(true);
        Call interrupted = new Call(() -> interruptedStart.start(() -> true));
        Thread.sleep(20); interrupted.thread.interrupt(); interrupted.done();
        require(interrupted.failure.get() instanceof InterruptedException, "startup interrupt not returned");
        require(!(boolean)get(interruptedStart,"stopRequested"), "startup interrupt silently stopped owner");

        for (boolean callback : new boolean[] {false, true}) {
            PackedStereoMediaReceiver r = receiver(true); CountDownLatch release = new CountDownLatch(1);
            Thread held;
            if (callback) {
                HandlerThread c = new HandlerThread("held-callback"); c.hostRelease = release; held = c;
                set(r,"renderThread",c);
            } else {
                held = new Thread(() -> {
                    boolean done = false;
                    while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                    try { set(r,"worker",null); } catch (Exception failure) { throw new AssertionError(failure); }
                },"held-worker");
                set(r,"worker",held);
            }
            held.setDaemon(true); held.start();
            Call cleanup = new Call(r::stopAndVerify); pending(cleanup);
            cleanup.thread.interrupt(); pending(cleanup);
            require(!r.snapshot().terminal(), "live resource declared terminal");
            release.countDown(); cleanup.done(); held.join(1000);
            require(cleanup.failure.get() == null && cleanup.interrupted, "cleanup interrupt abandoned custody/outcome");
            require(r.snapshot().terminal(), "real cleanup not terminal");
            if (callback) require(((HandlerThread) held).hostQuitCalls == 1, "callback shutdown dispatched twice");
        }

        PackedStereoMediaReceiver workerFinally = receiver(true);
        HandlerThread finalCallback = new HandlerThread("worker-finally-callback");
        finalCallback.hostRelease = new CountDownLatch(1); set(workerFinally,"renderThread",finalCallback); finalCallback.start();
        Call finalJoin = new Call(() -> {
            java.lang.reflect.Method method = PackedStereoMediaReceiver.class.getDeclaredMethod("releaseRenderThread");
            method.setAccessible(true); method.invoke(workerFinally);
        });
        pending(finalJoin); finalJoin.thread.interrupt(); pending(finalJoin);
        finalCallback.hostRelease.countDown(); finalJoin.done();
        require(finalJoin.failure.get() == null && finalJoin.interrupted && get(workerFinally,"renderThread") == null,
                "worker-finally callback retirement abandoned actual thread");

        PackedStereoMediaReceiver foreign = receiver(true);
        HandlerThread old = new HandlerThread("old-callback"); old.hostRelease = new CountDownLatch(1);
        set(foreign,"renderThread",old); old.start();
        Call oldCleanup = new Call(foreign::stopAndVerify); pending(oldCleanup);
        HandlerThread successor = new HandlerThread("successor-callback");
        set(foreign,"renderThread",successor); old.hostRelease.countDown(); oldCleanup.done();
        require(oldCleanup.failure.get() instanceof IllegalStateException, "foreign callback accepted");
        require(get(foreign,"renderThread") == successor && successor.hostQuitCalls == 0, "successor callback affected");

        PackedStereoMediaReceiver legacy = receiver(false);
        Call legacyStart = new Call(legacy::start); legacyStart.done();
        require(legacyStart.failure.get() instanceof java.io.IOException, "legacy startup bound changed");
        System.out.println("PASS complete receiver: selected prepared >estimate readiness, authority/Stop/interrupt controls; same actual worker/callback drains, restored interrupt, foreign owner rejection; legacy startup remains bounded");
    }
}
