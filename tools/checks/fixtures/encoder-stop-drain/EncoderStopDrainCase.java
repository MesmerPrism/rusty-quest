package io.github.mesmerprism.rustyquest.media;

import android.content.Context;
import android.media.MediaCodec;
import android.view.Surface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Real sender/owner/worker code; only Android and native input effects are injected. */
public final class EncoderStopDrainCase {
    private EncoderStopDrainCase() { }
    public static final CountDownLatch submitted = new CountDownLatch(1);
    public static final CountDownLatch outputReleased = new CountDownLatch(1);
    public static final AtomicBoolean allowFence = new AtomicBoolean(true);
    public static volatile String mode;
    public static volatile Object runtime;
    public static volatile Thread drainThread;
    public static volatile PackedStereoEncoderWorker inputWorker;
    public static volatile int finishes, polls;

    static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
    static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    public static boolean stopping() {
        try { return (Boolean) get(runtime, "stopRequested"); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    public static void drainObserved() {
        Thread current = Thread.currentThread();
        if (drainThread != null && drainThread != current) throw new AssertionError("multiple codec consumers");
        drainThread = current;
    }
    public static void submitBlocked() {
        submitted.countDown();
        try {
            if (!outputReleased.await(15, TimeUnit.SECONDS)) throw new AssertionError("codec submission stranded");
        } catch (InterruptedException error) { throw new AssertionError("input interrupted", error); }
    }
    public static boolean inputRetired() {
        return inputWorker.isPhysicallyRetired();
    }
    private static MediaOwnerAction stopTicket(String kind, int sequence) throws Exception {
        return MediaOwnerAction.parse(new JSONObject().put("$schema", MediaOwnerAction.SCHEMA)
                .put("capability", "fixture.stop." + sequence).put("executor_generation", 1)
                .put("action_id", "fixture.stop").put("authority_epoch_id", "fixture.epoch")
                .put("media_acceptance_authority_revision", 1).put("expected_runtime_revision", 1)
                .put("client_id", "fixture.client").put("lease_id", "fixture.lease")
                .put("sequence", sequence).put("operation", "stop").put("owner_kind", kind)
                .put("action_kind", "stop").put("owner_id", "fixture." + kind)
                .put("provider_kind", "fixture." + kind).put("resource_id", "fixture.resource").toString());
    }
    private static void awaitWorker(PackedStereoEncoderWorker worker) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!worker.isPhysicallyRetired() && System.nanoTime() < end) Thread.sleep(1);
        if (!worker.isPhysicallyRetired()) throw new AssertionError("worker did not retire after injected positive fence");
    }
    public static void main(String[] args) throws Exception {
        mode = args[0]; allowFence.set(!"permanent".equals(mode));
        if ("startup-no-worker".equals(mode)) {
            PackedStereoMediaSourceRuntime.Pipeline unstarted = PackedStereoMediaSourceRuntime.createPipeline(
                    new Context(), "fixture.startup", "camera", "127.0.0.1", 41002,
                    32, 16, 16, 16, 50, 100000, "left", "right", 2_000_000);
            runtime = get(unstarted, "runtime");
            // Exercise the real initial source failure before any camera or
            // encoder input exists; no Android service is called.
            set(runtime, "context", null);
            Method start = runtime.getClass().getDeclaredMethod("startSource");
            start.setAccessible(true); start.invoke(runtime);
            Thread source = (Thread) get(runtime, "sourceThread"); source.join(5000);
            if (source.isAlive() || get(runtime, "encoderWorker") != null) throw new AssertionError("initial failure stranded worker");
            unstarted.stopAndVerify("fixture_startup_failure"); unstarted.finishCleanup();
            if (!unstarted.terminal()) throw new AssertionError("initial failure not terminal");
            System.out.println("PASS initial source startup failure with no input worker retires its absent graph");
            return;
        }
        PackedStereoCaptureOwner capture = new PackedStereoCaptureOwner(new Context(), 16, 16,
                50, "left", "right", 2_000_000, new PackedStereoPoolExecutor() {
                    public Pool createForCurrentContext(int width, int height) { throw new AssertionError("no capture GL"); }
                    public void awaitCameraOwnership() { throw new AssertionError("no camera"); }
                    public boolean ownImageFresh() { return false; }
                });
        PackedStereoMediaSourceRuntime.Pipeline pipeline = PackedStereoMediaSourceRuntime.createPipeline(
                new Context(), "fixture.stop", "camera", "127.0.0.1", 41001,
                32, 16, 16, 16, 50, 100000, "left", "right", 2_000_000);
        runtime = get(pipeline, "runtime");
        Surface surface = new Surface(); MediaCodec codec = new MediaCodec();
        set(runtime, "encoder", codec); set(runtime, "encoderSurface", surface);
        set(runtime, "sharedCapture", capture); set(runtime, "captureConsumerGeneration", 1L);
        PackedStereoEncoderWorker worker = new PackedStereoEncoderWorker(1, surface,
                new PackedStereoEncoderWorker.Listener() {
                    public void onPairSubmitting(PackedStereoPoolExecutor.PairIdentity pair, long pts) { }
                    public void onFailure(Throwable failure) { throw new AssertionError(failure); }
                });
        worker.awaitStarted(); inputWorker = worker;
        set(runtime, "encoderWorker", worker); capture.attachEncoder(1, worker);
        worker.offer(new PackedStereoEncoderInput() {
            Thread owner;
            public PackedStereoPoolExecutor.PairIdentity pair() { return new PackedStereoPoolExecutor.PairIdentity(1, 1, 1, 1, 1); }
            public Imported importOnCurrentContext() { owner = Thread.currentThread(); return new Imported(1, 32, 16); }
            public void finishAfterSubmission() {
                if (owner != Thread.currentThread() || outputReleased.getCount() != 0) throw new AssertionError("input ownership");
                finishes++;
            }
            public boolean pollRetired() {
                if (owner != Thread.currentThread()) throw new AssertionError("fence owner");
                polls++; return allowFence.get();
            }
            public void releaseUnsubmitted() { throw new AssertionError("submitted lease discarded"); }
        });
        if (!submitted.await(5, TimeUnit.SECONDS)) throw new AssertionError("submission did not block");
        boolean missingSource = "partial-source".equals(mode) || "dead-source".equals(mode) || "concurrent-stop".equals(mode);
        Thread source = null;
        if (!missingSource) {
            Method start = runtime.getClass().getDeclaredMethod("startSource"); start.setAccessible(true); start.invoke(runtime);
            source = (Thread) get(runtime, "sourceThread");
            if ("interrupted".equals(mode)) source.interrupt();
            else if (!MediaCodec.normalAttempt.await(5, TimeUnit.SECONDS)) throw new AssertionError("normal codec drain never ran");
        } else if ("dead-source".equals(mode)) {
            Thread ended = new Thread(new Runnable() { public void run() { } }, "fixture-ended-source");
            ended.start(); ended.join(); set(runtime, "sourceThread", ended);
        }
        PackedStereoMediaOwnerSet owners = new PackedStereoMediaOwnerSet(1, pipeline);
        MediaOwnerProvider owner = owners.provider("source");
        try {
            boolean pending = false;
            if ("concurrent-stop".equals(mode)) {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Runnable stop = new Runnable() {
                    public void run() {
                        try { owner.execute(stopTicket("source", 1), new CancellationHandle(1)); }
                        catch (Throwable error) { failure.compareAndSet(null, error); }
                    }
                };
                Thread first = new Thread(stop, "fixture-stop-1"), second = new Thread(stop, "fixture-stop-2");
                first.start(); second.start(); first.join(5000); second.join(5000);
                if (first.isAlive() || second.isAlive() || failure.get() != null) throw new AssertionError("concurrent Stop", failure.get());
            } else {
                try { owner.execute(stopTicket("source", 1), new CancellationHandle(1)); }
                catch (IllegalStateException expected) { pending = true; }
            }
            if (missingSource) source = drainThread;
            if ("baseline".equals(mode)) {
                if (!pending || worker.isPhysicallyRetired() || codec.releases != 0 || source.isAlive())
                    throw new AssertionError("preimage did not strand its drain");
                System.out.println("PASS preimage Source Stop strands blocked submission after stopping its only codec drain");
                return;
            }
            if ("permanent".equals(mode)) {
                if (!pending || !source.isAlive() || codec.releases != 1 || worker.isPhysicallyRetired()
                        || codec.stops != 0 || codec.destroyed != 0 || surface.releases != 0
                        || get(runtime, "encoder") != codec || get(runtime, "encoderWorker") != worker)
                    throw new AssertionError("permanent fence falsely terminalized or lost sole drain");
                try { owner.execute(stopTicket("source", 2), new CancellationHandle(1)); throw new AssertionError("retry falsely terminal"); }
                catch (IllegalStateException expected) { }
                if (codec.releases != 1 || codec.destroyed != 0 || surface.releases != 0 || !source.isAlive())
                    throw new AssertionError("retry duplicated drain/release or lost pump");
                allowFence.set(true);
            } else if (pending) throw new AssertionError("resolvable submission remained Pending");
            awaitWorker(worker); source.join(5000);
            if (source.isAlive()) throw new AssertionError("source did not finish after retirement");
            owner.execute(stopTicket("source", 3), new CancellationHandle(1));
            owners.provider("processor").execute(stopTicket("processor", 4), new CancellationHandle(1));
            pipeline.requireStopped();
            int expectedOutputs = "eos-release-failure".equals(mode) ? 2 : 1;
            if (codec.releases != expectedOutputs || codec.stops != 1 || codec.destroyed != 1 || surface.releases != 1
                    || finishes != 1 || polls == 0 || codec.eosBeforeRetirement || codec.concurrentConsumer
                    || drainThread != source) throw new AssertionError("cleanup ordering/identity/idempotence");
            if ("dequeue-failure".equals(mode) && codec.dequeueFailures != 1) throw new AssertionError("failure path untested");
            if ("output-failure".equals(mode) && codec.outputFailures != 1) throw new AssertionError("buffer finally untested");
            if ("release-failure".equals(mode) && (codec.releaseFailures != 1 || codec.releaseAttempts != 2))
                throw new AssertionError("exact acquired output retry untested");
            if ("stop-dequeue-failure".equals(mode) && codec.stopDequeueFailures != 1) throw new AssertionError("stop pump retry untested");
            if ("eos-release-failure".equals(mode) && (codec.releaseFailures != 1 || codec.releaseAttempts != 3))
                throw new AssertionError("EOS acquired output retry untested");
            if ("output-and-release-failure".equals(mode) && (codec.outputFailures != 1 || codec.releaseFailures != 1
                    || !((String) get(runtime, "error")).contains("injected output failure")
                    || !((String) get(runtime, "firstStopDrainFailure")).contains("injected output release failure")))
                throw new AssertionError("primary and release failures not retained separately");
            System.out.println("PASS " + mode + " actual Source Stop: sole codec drain, positive input retirement, EOS, physical release and repeated Stop");
        } finally {
            // Synthetic harness teardown only, including the deliberately broken
            // preimage. This is not a device or production force-retirement route.
            outputReleased.countDown(); allowFence.set(true); worker.requestStop();
            awaitWorker(worker); source.join(5000); pipeline.stopAndVerify("fixture_end"); pipeline.finishCleanup();
        }
    }
}
