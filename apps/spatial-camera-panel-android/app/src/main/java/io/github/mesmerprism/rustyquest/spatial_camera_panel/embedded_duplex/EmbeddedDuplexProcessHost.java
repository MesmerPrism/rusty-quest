package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** One app-context mutation lane. Endpoint and Activity display callbacks never enter its queue. */
final class EmbeddedDuplexProcessHost {
    private enum Phase { NEW, BOOTSTRAPPING, READY, FAILED }
    private static EmbeddedDuplexProcessHost instance;

    static synchronized EmbeddedDuplexProcessHost forApplication(Context context) {
        if (instance == null) {
            if (context == null || context.getApplicationContext() == null) {
                throw new IllegalArgumentException("application context required");
            }
            instance = new EmbeddedDuplexProcessHost(context.getApplicationContext());
        }
        return instance;
    }

    private final Context applicationContext;
    private final EmbeddedDuplexDisplaySlot display = new EmbeddedDuplexDisplaySlot();
    private final Object attachmentGate = new Object();
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.NEW);
    private long attachmentGeneration;
    private boolean displayDetaching;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "embedded-duplex-command");
        thread.setDaemon(true);
        return thread;
    });
    // Retain the callback after native GlobalRef capture, including a failed
    // post-initialization resource installation, so no second host can start.
    private EmbeddedDuplexPlatform platform;
    private EmbeddedDuplexResources resources;

    private EmbeddedDuplexProcessHost(Context applicationContext) {
        this.applicationContext = applicationContext;
    }

    EmbeddedDuplexDisplaySlot displayAttachment() { return display; }
    boolean ready() { return phase.get() == Phase.READY; }

    long attachDisplay(EmbeddedDuplexDisplay next) {
        synchronized (attachmentGate) {
            if (phase.get() != Phase.NEW || attachmentGeneration != 0L || displayDetaching) {
                throw new IllegalStateException("embedded display attachment unavailable");
            }
            attachmentGeneration = display.attach(next);
            return attachmentGeneration;
        }
    }

    /** Only an uninitialized host has no product effects to clean up. A live
     * host needs typed Stop and platform cleanup before its display can move. */
    void detachUninitializedDisplay(long expectedGeneration) throws Exception {
        synchronized (attachmentGate) {
            if (phase.get() != Phase.NEW || attachmentGeneration != expectedGeneration
                    || expectedGeneration == 0L) {
                throw new IllegalStateException("embedded product cleanup required before display detach");
            }
            displayDetaching = true;
        }
        // The display barrier may wait for callbacks; never hold a host lock.
        display.detachAfterCleanup(expectedGeneration, () -> {});
        synchronized (attachmentGate) {
            attachmentGeneration = 0L;
            displayDetaching = false;
        }
    }

    CompletableFuture<String> initialize(EmbeddedDuplexPackagedInputs.InstalledRole role,
            JSONObject runtimeBindings, JSONObject startup) {
        if (role == null || runtimeBindings == null || startup == null) {
            return failed(new IllegalArgumentException("embedded initialization inputs"));
        }
        synchronized (attachmentGate) {
            if (attachmentGeneration == 0L || displayDetaching || display.cleanupPending()
                    || !phase.compareAndSet(Phase.NEW, Phase.BOOTSTRAPPING)) {
                return failed(new IllegalStateException("embedded process host or display unavailable"));
            }
        }
        // The caller may mutate its JSON after returning; command lane sees an
        // exact private copy and never retains an Activity, Intent or Bundle.
        String runtimeCopy = runtimeBindings.toString();
        String startupCopy = startup.toString();
        return submit(() -> {
            boolean nativeAttempted = false;
            try {
                JSONObject runtime = new JSONObject(runtimeCopy);
                JSONObject enrollment = new JSONObject(startupCopy);
                EmbeddedDuplexBootstrap.Prepared prepared = EmbeddedDuplexBootstrap.prepare(
                        applicationContext, role, runtime);
                EmbeddedDuplexIdentity.Identity identity = EmbeddedDuplexIdentity.loadOrCreate(
                        applicationContext);
                EmbeddedDuplexPlatform callbacks = new EmbeddedDuplexPlatform(applicationContext,
                        display, identity, prepared.localPeerId, prepared.remotePeerId,
                        prepared.routeConfigurationSha256, prepared.remoteControlHost,
                        prepared.remoteControlPort);
                JSONObject bootstrap = EmbeddedDuplexBootstrap.runtimeBootstrap(prepared,
                        identity.keyId(), enrollment,
                        new JSONObject(callbacks.loadDispatchReplay()),
                        new JSONObject(callbacks.loadActivationReplay()));
                platform = callbacks;
                nativeAttempted = true;
                String initialized = EmbeddedDuplexNative.initializeRuntime(prepared.runtimeConfigJson,
                        prepared.runtimeConfigSha256,
                        runtime.getString("validation_epoch_entropy_hex"),
                        bootstrap.toString(), callbacks);
                if (initialized == null || initialized.length() == 0
                        || initialized.length() > 2 * 1024 * 1024) {
                    throw new IllegalStateException("embedded native initialization unavailable");
                }
                EmbeddedDuplexResources installed = new EmbeddedDuplexResources(applicationContext,
                        display, new JSONObject(initialized), prepared.maxPairDeltaNs);
                resources = installed;
                callbacks.installResources(installed);
                callbacks.startControl(prepared.localControlHost, prepared.localControlPort);
                if (!callbacks.controlReady()) {
                    throw new IllegalStateException("authenticated control endpoint unavailable");
                }
                phase.set(Phase.READY);
                return initialized;
            } catch (Exception failure) {
                // Prior to native initialization no process authority exists.
                // Afterwards uncertainty is retained and a second host is barred.
                phase.set(nativeAttempted ? Phase.FAILED : Phase.NEW);
                throw failure;
            }
        });
    }

    CompletableFuture<String> command(String operation, String exactInputJson) {
        if (operation == null || exactInputJson == null || operation.length() > 64
                || exactInputJson.length() > 2 * 1024 * 1024) {
            return failed(new IllegalArgumentException("embedded command bounds"));
        }
        return submit(() -> {
            if (phase.get() != Phase.READY) {
                throw new IllegalStateException("embedded process host not ready");
            }
            return EmbeddedDuplexNative.runtimeCommand(operation, exactInputJson);
        });
    }

    private interface Work<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> submit(Work<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        commands.execute(() -> {
            try { result.complete(action.run()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }
    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }
}
