// SPDX-License-Identifier: AGPL-3.0-or-later
import io.github.mesmerprism.rustyquest.navigatorvisibility.AndroidNavigatorVisibility;
import io.github.mesmerprism.rustyquest.navigatorvisibility.NavigatorVisibilityObserver;
import io.github.mesmerprism.rustyquest.navigatorvisibility.NavigatorVisibilityObserver.*;
import android.content.Context;
import horizonos.os.resource.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Neutral consumer: records observations and cleanup; contains no menu actuator. */
public final class VisibilityConformanceMain {
    private static int checks;
    private static void check(boolean value, String detail) {
        checks++;
        if (!value) throw new AssertionError(detail);
    }
    private static final class Journal implements Listener {
        final List<Observation> samples = new ArrayList<>();
        final List<String> faults = new ArrayList<>();
        @Override public void observed(Observation value) { samples.add(value); }
        @Override public void fault(long generation, String category) { faults.add(category); }
    }
    private static class Fake implements Backend {
        Callback callback;
        int registrations, unregistrations;
        boolean registered = true, retired = true, registrationThrows, retirementThrows;
        Runnable onRegister = () -> { };
        @Override public void attach(Callback value) { callback = value; }
        @Override public boolean register() {
            registrations++; onRegister.run();
            if (registrationThrows) throw new IllegalStateException();
            return registered;
        }
        @Override public boolean unregister() {
            unregistrations++;
            callback.sample(true, 999); // Cleanup itself may deliver a late callback.
            if (retirementThrows) throw new IllegalStateException();
            return retired;
        }
        void sample(boolean value) { callback.sample(value, -17); }
    }
    private static NavigatorVisibilityObserver observer(Fake backend, Journal journal) {
        return new NavigatorVisibilityObserver(backend, () -> 100L, journal);
    }
    private static void samplesAndBaseline() {
        Fake backend = new Fake(); Journal journal = new Journal();
        NavigatorVisibilityObserver observer = observer(backend, journal);
        backend.sample(true);
        check(journal.samples.isEmpty(), "inert until explicitly started");
        backend.onRegister = () -> { backend.sample(true); backend.sample(false); };
        check(observer.start().phase == Phase.ACTIVE, "register callback retained until return");
        check(journal.samples.size() == 2, "both registration samples delivered");
        check(journal.samples.get(0).kind == Kind.INITIAL_TRUE, "initial true is diagnostic");
        check(journal.samples.get(1).kind == Kind.HIDDEN, "initial true then false preserves hidden edge");
        check(observer.status().falseBaselineObserved, "false sample establishes baseline");
        backend.sample(true); backend.sample(true); backend.sample(false);
        check(journal.samples.get(2).kind == Kind.OPENED, "false to true edge");
        check(journal.samples.get(3).kind == Kind.UNCHANGED, "duplicate true is not another edge");
        check(journal.samples.get(4).sequence == 5, "ordered sequence");
        check(journal.samples.get(0).vendorSampleTime == -17, "opaque vendor time preserved");
        observer.start(); observer.close(); observer.close(); backend.sample(true);
        check(backend.registrations == 1 && backend.unregistrations == 1, "one original per lifecycle call");
        check(journal.samples.size() == 5 && observer.status().phase == Phase.CLOSED, "close and late sample disarm");
        Fake next = new Fake(); Journal nextJournal = new Journal();
        NavigatorVisibilityObserver successor = observer(next, nextJournal);
        successor.start(); next.sample(false);
        check(nextJournal.samples.get(0).kind == Kind.FALSE_BASELINE, "initial false baseline explicit");
        check(successor.status().generation != observer.status().generation, "distinct generation");
        backend.sample(false);
        check(nextJournal.samples.size() == 1, "old callback cannot enter successor");
        successor.close();
    }
    private static void uncertaintyAndFailure() {
        for (boolean throwsOriginal : new boolean[] {false, true}) {
            Fake backend = new Fake(); Journal journal = new Journal();
            backend.registrationThrows = throwsOriginal; backend.registered = false;
            NavigatorVisibilityObserver observer = observer(backend, journal);
            check(observer.start().phase == (throwsOriginal ? Phase.HELD : Phase.CLOSED), "reject vs original unknown");
            observer.start(); backend.sample(true);
            check(backend.registrations == 1 && journal.samples.isEmpty(), "unknown original cannot be retried");
            observer.close();
            check(backend.unregistrations == (throwsOriginal ? 1 : 0), "retain and clean uncertain registration handle");
        }
        for (boolean throwsCleanup : new boolean[] {false, true}) {
            Fake backend = new Fake(); Journal journal = new Journal();
            backend.retired = false; backend.retirementThrows = throwsCleanup;
            NavigatorVisibilityObserver observer = observer(backend, journal);
            observer.start(); observer.close(); observer.close(); observer.start(); backend.sample(true);
            check(observer.status().phase == Phase.HELD, "failed cleanup remains held");
            check(backend.unregistrations == 1 && backend.registrations == 1, "unknown cleanup cannot be retried");
            check(journal.samples.isEmpty(), "held cleanup disarmed");
        }
        Fake unused = new Fake(); NavigatorVisibilityObserver closed = observer(unused, new Journal());
        closed.close(); closed.start();
        check(unused.registrations == 0 && unused.unregistrations == 0, "close before start is inert");
        Fake overflow = new Fake(); Journal overflowJournal = new Journal();
        overflow.onRegister = () -> { for (int i=0; i<33; i++) overflow.sample(i % 2 == 0); };
        NavigatorVisibilityObserver held = observer(overflow, overflowJournal);
        check(held.start().phase == Phase.HELD && overflowJournal.samples.isEmpty(), "overflow retains no incomplete stream");
        held.close(); check(overflow.unregistrations == 1, "overflow handle retained for cleanup");
        Fake clock = new Fake(); Journal clockJournal = new Journal(); final long[] now = {100};
        NavigatorVisibilityObserver clocked = new NavigatorVisibilityObserver(clock, () -> now[0], clockJournal);
        clocked.start(); clock.sample(false); now[0]=99; clock.sample(true);
        check(clocked.status().phase == Phase.HELD && clockJournal.samples.size() == 1, "backwards receipt clock holds");
        clocked.close();
        Fake listenerFailure = new Fake();
        NavigatorVisibilityObserver badListener = new NavigatorVisibilityObserver(listenerFailure, () -> 1,
                new Listener() {
                    @Override public void observed(Observation value) { throw new IllegalStateException(); }
                    @Override public void fault(long generation, String category) { throw new IllegalStateException(); }
                });
        badListener.start(); listenerFailure.sample(false);
        check(badListener.status().phase == Phase.HELD, "listener failure remains inspectable");
        badListener.close();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("bounded race wait timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void closeDuringOriginal() throws InterruptedException {
        Fake backend = new Fake(); Journal journal = new Journal();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        backend.onRegister = () -> {
            backend.callback.fault("test-fault-during-original"); entered.countDown(); await(release);
        };
        NavigatorVisibilityObserver observer = observer(backend, journal);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread original = new Thread(() -> {
            try { observer.start(); } catch (Throwable problem) { failure.set(problem); }
        });
        original.start(); await(entered); observer.close();
        check(backend.unregistrations == 0, "fault plus close cannot overtake original registration");
        release.countDown(); original.join(5000);
        check(!original.isAlive() && failure.get() == null, "original terminates cleanly");
        check(backend.unregistrations == 1 && observer.status().phase == Phase.CLOSED, "late original closes same handle once");
        backend.sample(true); check(journal.samples.isEmpty(), "late result never re-arms");
    }
    private static void androidBinding() {
        FeatureManager service = new FeatureManager(); Journal journal = new Journal();
        Context application = new Context() {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) { return "crcs".equals(name) ? service : null; }
        };
        Context activity = new Context() {
            @Override public Context getApplicationContext() { return application; }
            @Override public Object getSystemService(String name) { throw new AssertionError("Activity retained/used"); }
        };
        AndroidNavigatorVisibility.Candidate candidate = AndroidNavigatorVisibility.create(activity, journal);
        check(candidate.available && service.registrations == 0, "candidate availability inert");
        candidate.observer.start();
        check(service.info.uri.startsWith("controller://io.github.mesmerprism.rustyquest/navigator-visibility/"), "own controller identity");
        check(service.info.request.uri.equals("signal://shell/navigator/visibility")
                && service.info.request.deliveryMode == 2 && service.info.request.interval == 0, "closed subscription ABI");
        Map<String,List<SignalReceivedValue>> batch = new HashMap<>();
        batch.put(service.info.request.uri, Arrays.asList(new SignalReceivedValue(false, 1),
                new SignalReceivedValue(true, 2), new SignalReceivedValue(false, 3)));
        CurrentControllerState state = service.controller.onStateChanged(new StateChange(batch));
        check(journal.samples.size() == 3 && journal.samples.get(1).kind == Kind.OPENED
                && journal.samples.get(2).kind == Kind.HIDDEN, "batch preserves all ordered edges");
        check(state.states.isEmpty() && !state.priority.isPresent(), "no resource/priority claims");
        service.controller.onStateChanged(new StateChange(Collections.emptyMap()));
        check(journal.samples.size() == 3, "unrelated callback ignored");
        service.controller.onError(new horizonos.os.resource.Error());
        check(candidate.observer.status().phase == Phase.HELD, "vendor error disarms observation");
        candidate.observer.close(); service.controller.onStateChanged(new StateChange(batch));
        check(service.unregistrations == 1 && journal.samples.size() == 3, "same callback handle cleanup and late suppression");
    }
    private static void consumerEscapeFence() {
        // A neutral UI journal owns its queued-work fence; the platform owns no escape policy.
        List<Runnable> uiQueue = new ArrayList<>(); List<Observation> visibleJournal = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicLong current = new java.util.concurrent.atomic.AtomicLong();
        Listener queuedJournal = new Listener() {
            @Override public void observed(Observation sample) {
                uiQueue.add(() -> {
                    if (armed.get() && current.get() == sample.generation) visibleJournal.add(sample);
                });
            }
            @Override public void fault(long generation, String category) { armed.set(false); }
        };
        Fake first = new Fake();
        NavigatorVisibilityObserver original = new NavigatorVisibilityObserver(first, () -> 1, queuedJournal);
        current.set(original.status().generation); original.start(); first.sample(false);
        armed.set(false); original.close(); // Explicit escape/lifecycle stop disarms before cleanup.
        for (Runnable update : uiQueue) update.run();
        check(visibleJournal.isEmpty(), "escape suppresses already queued consumer work");
        Fake next = new Fake();
        NavigatorVisibilityObserver successor = new NavigatorVisibilityObserver(next, () -> 2, queuedJournal);
        current.set(successor.status().generation); armed.set(true); successor.start(); next.sample(false);
        for (Runnable update : uiQueue) update.run();
        check(visibleJournal.size() == 1 && visibleJournal.get(0).generation == current.get(),
                "successor generation rejects old queued consumer work");
        armed.set(false); successor.close();
    }
    public static void main(String[] args) throws InterruptedException {
        samplesAndBaseline(); uncertaintyAndFailure(); closeDuringOriginal(); androidBinding(); consumerEscapeFence();
        System.out.println("Navigator visibility host conformance: " + checks + " assertions passed (simulated platform).");
    }
}
