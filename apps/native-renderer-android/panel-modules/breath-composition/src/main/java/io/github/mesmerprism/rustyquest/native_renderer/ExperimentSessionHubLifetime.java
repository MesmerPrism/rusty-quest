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
    ExperimentSessionHubLifetime(Factory factory,
            Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source) {
        if (factory == null || source == null) throw new IllegalArgumentException("source and factory required");
        this.factory = factory; this.source = source;
    }
    public void refresh() {
        if (closed) return;
        ExperimentSessionPanelCoordinator.NativeStatusSnapshot snapshot = source.get();
        if (snapshot == null || !snapshot.observed || snapshot.runtimeEpoch <= 0L) {
            // Once admitted, loss of eligibility retires this lifetime. A new resumed
            // panel lifetime is required; polling must not reopen a failed session.
            if (attempted) close();
            return;
        }
        if (attempted) {
            if (snapshot.runtimeEpoch != epoch) close();
            return;
        }
        attempted = true; epoch = snapshot.runtimeEpoch;
        try {
            driver = factory.create(epoch, source);
            if (driver == null) throw new IllegalStateException("driver unavailable");
            driver.start();
        } catch (RuntimeException error) { close(); }
    }
    public void close() {
        if (closed) return;
        closed = true;
        Driver old = driver; driver = null;
        if (old != null) old.close();
    }
}
