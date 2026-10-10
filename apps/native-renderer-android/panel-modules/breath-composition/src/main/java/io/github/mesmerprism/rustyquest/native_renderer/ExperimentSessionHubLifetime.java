package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.function.Supplier;

/** One selected resumed panel lifetime, admitted only by an actual native witness. */
final class ExperimentSessionHubLifetime implements ExperimentSessionPanelCoordinator.StatusLifetime {
    interface Driver { void start(); void close(); }
    interface Factory {
        Driver create(long epoch, Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source);
    }
    private final Factory factory;
    private final Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source;
    private Driver driver;
    private long epoch;
    private boolean closed, attempted;
    private final java.util.function.Consumer<String> observer;
    private int markers;
    private Boolean observed;
    ExperimentSessionHubLifetime(Factory factory,
            Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source) {
        this(factory, source, null);
    }
    ExperimentSessionHubLifetime(Factory factory,
            Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source,
            java.util.function.Consumer<String> observer) {
        if (factory == null || source == null) throw new IllegalArgumentException("source and factory required");
        this.factory = factory; this.source = source;
        this.observer = observer;
        mark("lifetime_created");
    }
    private void mark(String marker) {
        if (markers >= 16 || observer == null) return;
        markers++;
        try { observer.accept(marker); } catch (RuntimeException unavailable) { }
    }
    public void refresh() {
        if (closed) return;
        ExperimentSessionPanelCoordinator.NativeStatusSnapshot snapshot = source.get();
        boolean eligible = snapshot != null && snapshot.observed && snapshot.runtimeEpoch > 0L;
        if (observed == null || observed.booleanValue() != eligible) {
            observed = eligible;
            mark("witness observed=" + eligible + " epoch=" + (eligible ? snapshot.runtimeEpoch : 0L)
                + " read=" + (eligible ? snapshot.readId : 0L));
        }
        if (snapshot == null || !snapshot.observed || snapshot.runtimeEpoch <= 0L) {
            // Once admitted, loss of eligibility retires this lifetime. A new resumed
            // panel lifetime is required; polling must not reopen a failed session.
            if (attempted) { mark("witness_lost"); close(); }
            return;
        }
        if (attempted) {
            if (snapshot.runtimeEpoch != epoch) { mark("epoch_changed"); close(); }
            return;
        }
        attempted = true; epoch = snapshot.runtimeEpoch;
        mark("factory_attempt");
        try {
            driver = factory.create(epoch, source);
            if (driver == null) throw new IllegalStateException("driver unavailable");
            mark("driver_created");
            driver.start();
            mark("start_returned");
        } catch (RuntimeException error) { mark("factory_or_start_failed"); close(); }
    }
    public void close() {
        if (closed) return;
        closed = true;
        mark("lifetime_closed");
        Driver old = driver; driver = null;
        if (old != null) old.close();
    }
}
