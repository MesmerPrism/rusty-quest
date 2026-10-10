package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Selected app/native owner. Panel presence conveys no lifecycle or freshness authority. */
final class ExperimentSessionAppStatusOwner {
    interface Scheduler { void post(Runnable action); void later(Runnable action, long delayMillis); }
    interface Poller {
        void read(Consumer<Observation> callback);
        default void opened(long identity) { }
        default void closed(long identity) { }
    }
    interface Clock { long nowNanos(); }
    static final class Observation {
        final ExperimentSessionPanelCoordinator.NativeReceipt receipt;
        final long epoch, completed;
        final boolean accepted, ready, closed, workerClosed;
        Observation(ExperimentSessionPanelCoordinator.NativeReceipt receipt, long epoch,
                long completed, boolean accepted, boolean ready, boolean closed, boolean workerClosed) {
            this.receipt = receipt; this.epoch = epoch; this.completed = completed;
            this.accepted = accepted; this.ready = ready; this.closed = closed; this.workerClosed = workerClosed;
        }
    }
    private final Scheduler scheduler;
    private final Poller poller;
    private final Clock clock;
    private final ExperimentSessionHubLifetime.Factory factory;
    private final Consumer<String> observer;
    // Native calls can arrive off main. Desired identity is fenced synchronously,
    // including Drop before a queued start or callback reaches the main looper.
    private long highestOwner, desiredOwner, desiredEpoch;
    private long owner, epoch;
    private ExperimentSessionPanelCoordinator coordinator;
    private ExperimentSessionHubLifetime lifetime;
    private boolean retired;
    private long lastAckNanos;
    private static final long MAX_ACK_AGE_NANOS = 1_500_000_000L;
    private int markers;

    ExperimentSessionAppStatusOwner(Scheduler scheduler, Poller poller, Clock clock,
            ExperimentSessionHubLifetime.Factory factory, Consumer<String> observer) {
        if (scheduler == null || poller == null || clock == null || factory == null)
            throw new IllegalArgumentException("owner dependencies required");
        this.scheduler=scheduler; this.poller=poller; this.clock=clock;
        this.factory=factory; this.observer=observer;
    }
    synchronized void started(final long identity, final long runtimeEpoch) {
        if (identity <= highestOwner || runtimeEpoch <= 0L) return;
        highestOwner=identity; desiredOwner=identity; desiredEpoch=runtimeEpoch;
        scheduler.post(new Runnable() { public void run() { begin(identity, runtimeEpoch); } });
    }
    synchronized void ended(final long identity, final long runtimeEpoch) {
        if (desiredOwner != identity || desiredEpoch != runtimeEpoch) return;
        desiredOwner=0L;
        scheduler.post(new Runnable() { public void run() {
            if (owner == identity && epoch == runtimeEpoch) retire("native_owner_ended");
        } });
    }
    private synchronized boolean current(long identity, long runtimeEpoch) {
        return desiredOwner == identity && desiredEpoch == runtimeEpoch;
    }
    private void begin(final long identity, final long runtimeEpoch) {
        if (!current(identity, runtimeEpoch)) return;
        retire("owner_replaced");
        owner=identity; epoch=runtimeEpoch; retired=false; lastAckNanos=0L;
        poller.opened(identity);
        coordinator=new ExperimentSessionPanelCoordinator();
        lifetime=new ExperimentSessionHubLifetime(factory,
            new Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot>() {
                public ExperimentSessionPanelCoordinator.NativeStatusSnapshot get() {
                    if (!current(identity, runtimeEpoch) || retired) return null;
                    ExperimentSessionPanelCoordinator.NativeStatusSnapshot value =
                        coordinator.nativeStatusSnapshot(clock.nowNanos());
                    return value.observed && value.ageMs >= 0L && value.ageMs <= 1500L ? value : null;
                }
            }, observer);
        mark("app_owner_started"); watchdog(identity, runtimeEpoch); poll(identity, runtimeEpoch);
    }
    private void watchdog(final long identity, final long runtimeEpoch) {
        if (!current(identity, runtimeEpoch) || retired) return;
        if (lifetime != null && lifetime.isClosed()) { retire("status_lifetime_retired"); return; }
        long now=clock.nowNanos();
        if (lastAckNanos > 0L && (now < lastAckNanos || now-lastAckNanos > MAX_ACK_AGE_NANOS)) {
            retire("worker_observation_expired"); return;
        }
        scheduler.later(new Runnable() { public void run() { watchdog(identity, runtimeEpoch); } }, 250L);
    }
    private void poll(final long identity, final long runtimeEpoch) {
        if (!current(identity, runtimeEpoch) || retired) return;
        // Exactly one request in flight. Callback scheduling, not a repeating timer,
        // bounds the queue when the shell or worker is stalled.
        poller.read(new Consumer<Observation>() { public void accept(final Observation value) {
            scheduler.post(new Runnable() { public void run() {
                if (!current(identity, runtimeEpoch) || retired) return;
                if (value != null && value.epoch == runtimeEpoch && (value.workerClosed || value.closed)) {
                    retire(value.closed ? "native_closed" : "worker_closed"); return;
                }
                long now=clock.nowNanos();
                if (value != null && value.epoch == runtimeEpoch && value.ready && value.accepted
                        && value.receipt != null && value.completed > 0L
                        && value.completed <= now && value.completed > lastAckNanos) {
                    coordinator.acceptNativeReadback(value.epoch, value.receipt,
                        value.completed, now, value.accepted);
                    ExperimentSessionPanelCoordinator.NativeStatusSnapshot snapshot =
                        coordinator.nativeStatusSnapshot(now);
                    if (snapshot.observed && snapshot.readId == value.completed) lastAckNanos=value.completed;
                    if (markers < 8) mark("app_native_readback status_response_accepted=" + value.accepted
                        + " epoch=" + value.epoch + " " + coordinator.nativeStatusReadbackDiagnostic());
                }
                // Transient unavailability cannot refresh the witness. Once its
                // existing age bound is exceeded, the admitted lifetime closes.
                lifetime.refresh();
                if (lifetime.isClosed()) { retire("status_lifetime_retired"); return; }
                scheduler.later(new Runnable() { public void run() { poll(identity, runtimeEpoch); } }, 500L);
            } });
        } });
    }
    private void retire(String reason) {
        if (retired) return;
        retired=true;
        poller.closed(owner);
        if (lifetime != null) lifetime.close();
        lifetime=null;
        mark(reason);
    }
    private void mark(String marker) {
        if (observer != null && markers++ < 32) {
            try { observer.accept(marker); } catch (RuntimeException unavailable) { }
        }
    }
}
