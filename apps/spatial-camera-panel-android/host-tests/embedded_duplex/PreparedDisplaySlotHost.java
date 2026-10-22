package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Actual process attachment owner; the display effects alone are host leaves. */
public final class PreparedDisplaySlotHost {
    static void need(boolean value) { if (!value) throw new AssertionError("display slot host control"); }
    static void refused(Runnable action) {
        try { action.run(); throw new AssertionError("expected attachment refusal"); }
        catch (IllegalStateException expected) { }
    }
    static final class Display implements EmbeddedDuplexDisplay {
        String id; long route; BooleanSupplier stop; int prepared, aborted, retired;
        Runnable onPrepare;
        @Override public String prepareDormantPeerRenderer(String id, long route, BooleanSupplier stop) {
            prepared++; this.id = id; this.route = route; this.stop = stop;
            if (onPrepare != null) onPrepare.run();
            return "host-prepared";
        }
        @Override public String abortDormantPeerRenderer(String id) { aborted++; this.id = id; return "host-aborted"; }
        @Override public void retireArchivedPreparedRenderer(String id) { retired++; this.id = id; }
        @Override public long ensureLocalCaptureStopped() { return 1; }
        @Override public long preparePeerProjection() { return 2; }
        @Override public void bindPeerProjection(long route, long decoder, long reader) { }
        @Override public void activatePeerProjection(long route, long decoder, long reader) { }
        @Override public long[] currentProjection(long route) { return new long[0]; }
        @Override public void retirePeerProjection() { }
        @Override public void restoreLocalAfterProductCleanup() { }
    }
    public static void main(String[] args) throws Exception {
        EmbeddedDuplexDisplaySlot slot = new EmbeddedDuplexDisplaySlot();
        Display first = new Display(); long generation = slot.attach(first);
        AtomicInteger stopCalls = new AtomicInteger(); BooleanSupplier stop = () -> { stopCalls.incrementAndGet(); return false; };
        need("host-prepared".equals(slot.prepareDormantPeerRenderer("first", 23L, stop)));
        need(first.prepared == 1 && first.id.equals("first") && first.route == 23L && first.stop == stop && stopCalls.get() == 0);
        need("host-aborted".equals(slot.abortDormantPeerRenderer("first")) && first.aborted == 1);
        slot.retireArchivedPreparedRenderer("first"); need(first.retired == 1);
        // New interface defaults must not silently bypass this production wrapper.
        for (Method method : EmbeddedDuplexDisplay.class.getDeclaredMethods())
            if (method.isDefault()) EmbeddedDuplexDisplaySlot.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
        IllegalStateException primary = new IllegalStateException("host sentinel");
        first.onPrepare = () -> { throw primary; };
        try { slot.prepareDormantPeerRenderer("first", 23L, stop); throw new AssertionError("expected exact failure"); }
        catch (IllegalStateException actual) { need(actual == primary); }
        first.onPrepare = () -> slot.abortDormantPeerRenderer("first");
        refused(() -> slot.prepareDormantPeerRenderer("first", 23L, stop));
        first.onPrepare = null;
        refused(() -> { try { slot.detachAfterCleanup(generation + 1, () -> { }); }
            catch (RuntimeException e) { throw e; } catch (Exception e) { throw new AssertionError(e); } });
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), detaching = new CountDownLatch(1), cleanup = new CountDownLatch(1);
        first.onPrepare = () -> { entered.countDown(); try { need(release.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); } };
        CompletableFuture<String> preparing = CompletableFuture.supplyAsync(() -> slot.prepareDormantPeerRenderer("first", 23L, stop));
        need(entered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> detached = CompletableFuture.runAsync(() -> {
            detaching.countDown();
            try { slot.detachAfterCleanup(generation, cleanup::countDown); } catch (Exception e) { throw new AssertionError(e); }
        });
        need(detaching.await(2, TimeUnit.SECONDS)); need(!cleanup.await(50, TimeUnit.MILLISECONDS));
        release.countDown(); need(preparing.get(2, TimeUnit.SECONDS).equals("host-prepared")); detached.get(2, TimeUnit.SECONDS);
        need(cleanup.getCount() == 0);
        int before = first.prepared;
        refused(() -> slot.prepareDormantPeerRenderer("detached", 24L, stop));
        refused(() -> slot.abortDormantPeerRenderer("detached"));
        refused(() -> slot.retireArchivedPreparedRenderer("detached")); need(first.prepared == before);
        Display next = new Display(); need(slot.attach(next) == generation + 1);
        need(slot.prepareDormantPeerRenderer("next", 24L, stop).equals("host-prepared"));
        need(next.prepared == 1 && first.prepared == before);
        // A failed cleanup retains the exact old attachment and blocks fresh calls.
        try { slot.detachAfterCleanup(generation + 1, () -> { throw primary; }); throw new AssertionError("expected cleanup failure"); }
        catch (IllegalStateException actual) { need(actual == primary); }
        refused(() -> slot.prepareDormantPeerRenderer("next", 24L, stop));
        refused(() -> slot.attach(new Display()));
        slot.detachAfterCleanup(generation + 1, () -> {
            need(slot.abortDormantPeerRenderer("next").equals("host-aborted"));
            slot.retireArchivedPreparedRenderer("next");
        });
        need(next.aborted == 1 && next.retired == 1);
        System.out.println("PASS 8 actual DisplaySlot groups: prepare/abort/archive, all-default coverage, exact exception, recursion, foreign generation, inflight drain, detach/replacement and retained cleanup retry");
    }
}
