// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.navigatorvisibility;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Explicit, single-use subscription. This class observes; it never closes a menu. */
public final class NavigatorVisibilityObserver implements AutoCloseable {
    public enum Phase { NEW, REGISTERING, ACTIVE, UNREGISTERING, CLOSED, HELD }
    public enum Kind { INITIAL_TRUE, FALSE_BASELINE, OPENED, HIDDEN, UNCHANGED }

    public interface Listener {
        void observed(Observation observation);
        void fault(long generation, String category);
    }

    /** One platform handle per observer, including after an uncertain original result. */
    public interface Backend {
        interface Callback {
            void sample(boolean visible, long vendorSampleTime);
            void fault(String category);
        }
        void attach(Callback callback);
        boolean register();
        boolean unregister();
    }

    public static final class Observation {
        public final long generation;
        public final long sequence;
        public final boolean visible;
        public final Kind kind;
        public final long receivedUptimeMs;
        /** Vendor clock, deliberately not interpreted as Android uptime. */
        public final long vendorSampleTime;
        private Observation(long generation, long sequence, boolean visible, Kind kind,
                long receivedUptimeMs, long vendorSampleTime) {
            this.generation = generation;
            this.sequence = sequence;
            this.visible = visible;
            this.kind = kind;
            this.receivedUptimeMs = receivedUptimeMs;
            this.vendorSampleTime = vendorSampleTime;
        }
    }

    public static final class Status {
        public final long generation;
        public final Phase phase;
        public final boolean falseBaselineObserved;
        public final boolean closeRequested;
        public final String fault;
        private Status(long generation, Phase phase, boolean baseline, boolean close,
                String fault) {
            this.generation = generation;
            this.phase = phase;
            this.falseBaselineObserved = baseline;
            this.closeRequested = close;
            this.fault = fault;
        }
    }

    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final int MAX_PENDING = 32;
    private final Object lock = new Object();
    private final Backend backend;
    private final Listener listener;
    private final LongSupplier uptimeMs;
    private final long generation;
    private final ArrayDeque<Observation> pending = new ArrayDeque<>();
    private Phase phase = Phase.NEW;
    private boolean closeRequested;
    private boolean unregisterAttempted;
    private boolean registrationInFlight;
    private boolean falseBaseline;
    private Boolean lastVisible;
    private long lastTime = -1;
    private long sequence;
    private boolean draining;
    private String fault = "";

    public NavigatorVisibilityObserver(Backend backend, LongSupplier uptimeMs,
            Listener listener) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.uptimeMs = Objects.requireNonNull(uptimeMs, "uptimeMs");
        this.listener = Objects.requireNonNull(listener, "listener");
        generation = GENERATIONS.incrementAndGet();
        if (generation <= 0) throw new IllegalStateException("generation-exhausted");
        backend.attach(new Backend.Callback() {
            @Override public void sample(boolean visible, long vendorSampleTime) {
                receive(visible, vendorSampleTime);
            }
            @Override public void fault(String category) {
                synchronized (lock) {
                    if (closeRequested || (phase != Phase.REGISTERING && phase != Phase.ACTIVE)) return;
                }
                fail("platform-callback");
            }
        });
    }

    /** At most one original registration. Re-entrant callbacks are retained until return. */
    public Status start() {
        synchronized (lock) {
            if (phase != Phase.NEW) return statusLocked();
            phase = Phase.REGISTERING;
            registrationInFlight = true;
        }
        boolean registered;
        try {
            registered = backend.register();
        } catch (RuntimeException | LinkageError failure) {
            synchronized (lock) {
                // Publish the original uncertainty before allowing concurrent cleanup.
                registrationInFlight = false;
                phase = Phase.HELD;
                fault = "registration-outcome-unknown";
                pending.clear();
            }
            notifyFault();
            finishRequestedClose();
            return status();
        }
        synchronized (lock) {
            registrationInFlight = false;
            if (phase == Phase.REGISTERING) {
                if (registered) phase = Phase.ACTIVE;
                else {
                    phase = Phase.CLOSED;
                    fault = "registration-rejected";
                    pending.clear();
                }
            }
        }
        finishRequestedClose();
        drain();
        return status();
    }

    private void receive(boolean visible, long vendorTime) {
        boolean notifyFault = false;
        synchronized (lock) {
            if (closeRequested || (phase != Phase.REGISTERING && phase != Phase.ACTIVE)) return;
            long now;
            try { now = uptimeMs.getAsLong(); }
            catch (RuntimeException failure) { now = -1; }
            if (now < 0 || now < lastTime || pending.size() >= MAX_PENDING) {
                phase = Phase.HELD;
                fault = now < 0 || now < lastTime ? "receipt-clock-invalid" : "callback-overflow";
                pending.clear();
                notifyFault = true;
            } else {
                Kind kind;
                if (lastVisible == null) kind = visible ? Kind.INITIAL_TRUE : Kind.FALSE_BASELINE;
                else if (lastVisible.booleanValue() == visible) kind = Kind.UNCHANGED;
                else kind = visible ? Kind.OPENED : Kind.HIDDEN;
                if (!visible) falseBaseline = true;
                lastVisible = visible;
                lastTime = now;
                pending.add(new Observation(generation, ++sequence, visible, kind, now, vendorTime));
            }
        }
        if (notifyFault) notifyFault();
        drain();
    }

    /** No listener or platform call is made under the state lock. */
    private void drain() {
        synchronized (lock) {
            if (draining || phase != Phase.ACTIVE || closeRequested) return;
            draining = true;
        }
        try {
            while (true) {
                Observation next;
                synchronized (lock) {
                    if (phase != Phase.ACTIVE || closeRequested) return;
                    next = pending.poll();
                    if (next == null) return;
                }
                try { listener.observed(next); }
                catch (RuntimeException failure) { fail("listener-failed"); return; }
            }
        } finally {
            boolean again;
            synchronized (lock) {
                draining = false;
                again = phase == Phase.ACTIVE && !closeRequested && !pending.isEmpty();
            }
            if (again) drain();
        }
    }

    private void fail(String category) {
        synchronized (lock) {
            if (closeRequested || phase == Phase.CLOSED || phase == Phase.UNREGISTERING) return;
            phase = Phase.HELD;
            fault = category;
            pending.clear();
        }
        notifyFault();
    }

    private void notifyFault() {
        String category;
        synchronized (lock) { category = fault; }
        try { listener.fault(generation, category); }
        catch (RuntimeException ignored) { /* Original fault remains visible through status. */ }
    }

    /** Ends callback admission immediately; in-flight original lifecycle calls must return. */
    @Override public void close() {
        synchronized (lock) {
            closeRequested = true;
            pending.clear();
            if (phase == Phase.NEW) phase = Phase.CLOSED;
            if (registrationInFlight) return;
        }
        finishRequestedClose();
    }

    private void finishRequestedClose() {
        synchronized (lock) {
            if (!closeRequested || unregisterAttempted || phase == Phase.NEW
                    || phase == Phase.CLOSED || registrationInFlight) return;
            unregisterAttempted = true;
            phase = Phase.UNREGISTERING;
        }
        boolean retired;
        try { retired = backend.unregister(); }
        catch (RuntimeException | LinkageError failure) {
            synchronized (lock) {
                phase = Phase.HELD;
                fault = "unregistration-outcome-unknown";
            }
            notifyFault();
            return;
        }
        synchronized (lock) {
            phase = retired ? Phase.CLOSED : Phase.HELD;
            if (!retired) fault = "unregistration-rejected-held";
        }
        if (!retired) notifyFault();
    }

    public Status status() { synchronized (lock) { return statusLocked(); } }
    private Status statusLocked() {
        return new Status(generation, phase, falseBaseline, closeRequested, fault);
    }
}
