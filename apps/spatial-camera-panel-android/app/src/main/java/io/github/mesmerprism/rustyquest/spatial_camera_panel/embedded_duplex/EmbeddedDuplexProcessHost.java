package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** One app-context mutation lane. Endpoint and Activity display callbacks never enter its queue. */
final class EmbeddedDuplexProcessHost {
    private enum Phase { NEW, PROVISIONING, BOOTSTRAPPING, READY, CLOSING, FAILED }
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
    private boolean closeInFlight;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "embedded-duplex-command");
        thread.setDaemon(true);
        return thread;
    });
    // Retain the callback after native GlobalRef capture, including a failed
    // post-initialization resource installation, so no second host can start.
    private EmbeddedDuplexPlatform platform;
    private EmbeddedDuplexResources resources;
    private String runtimeConfigSha256;
    private volatile boolean localFixture;
    private String diagnosticChallenge;

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
            localFixture = false;
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
        return submit(() -> initializeOnCommandLane(role, runtimeCopy, startupCopy, false, null));
    }

    private String initializeOnCommandLane(EmbeddedDuplexPackagedInputs.InstalledRole role,
            String runtimeCopy, String startupCopy, boolean fixture,
            EmbeddedDuplexBootstrap.Trace trace) throws Exception {
            localFixture = fixture;
            try {
                JSONObject runtime = new JSONObject(runtimeCopy);
                JSONObject enrollment = new JSONObject(startupCopy);
                EmbeddedDuplexBootstrap.Prepared prepared = EmbeddedDuplexBootstrap.prepare(
                        applicationContext, role, runtime, trace);
                // Preparation stages this exact route in native process state.
                // Retain its digest even if a later callback or JNI step fails.
                runtimeConfigSha256 = prepared.runtimeConfigSha256;
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.IDENTITY_LOAD);
                EmbeddedDuplexIdentity.Identity identity = EmbeddedDuplexIdentity.loadOrCreate(
                        applicationContext);
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.BOOTSTRAP_BINDINGS);
                EmbeddedDuplexPlatform callbacks = new EmbeddedDuplexPlatform(applicationContext,
                        display, identity, prepared.localPeerId, prepared.remotePeerId,
                        prepared.routeConfigurationSha256, prepared.remoteControlHost,
                        prepared.remoteControlPort, fixture);
                JSONObject bootstrap = EmbeddedDuplexBootstrap.runtimeBootstrap(prepared,
                        identity.keyId(), enrollment,
                        new JSONObject(callbacks.loadDispatchReplay()),
                        new JSONObject(callbacks.loadActivationReplay()));
                platform = callbacks;
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.NATIVE_INITIALIZE);
                String initialized = EmbeddedDuplexNative.initializeRuntime(prepared.runtimeConfigJson,
                        prepared.runtimeConfigSha256,
                        runtime.getString("validation_epoch_entropy_hex"),
                        bootstrap.toString(), callbacks);
                if (initialized == null || initialized.length() == 0
                        || initialized.length() > 2 * 1024 * 1024) {
                    throw new IllegalStateException("embedded native initialization unavailable");
                }
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.RESOURCE_INSTALL);
                EmbeddedDuplexResources installed = new EmbeddedDuplexResources(applicationContext,
                        display, new JSONObject(initialized), prepared.maxPairDeltaNs);
                resources = installed;
                callbacks.installResources(installed);
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.CONTROL_ENDPOINT);
                callbacks.startControl(prepared.localControlHost, prepared.localControlPort);
                if (!callbacks.controlReady()) {
                    throw new IllegalStateException("authenticated control endpoint unavailable");
                }
                phase.set(Phase.READY);
                return initialized;
            } catch (Exception failure) {
                // A prepared native route, even without a completed host, must
                // be closed under its exact digest before another bootstrap.
                phase.set(runtimeConfigSha256 == null ? Phase.NEW : Phase.FAILED);
                throw failure;
            }
    }

    CompletableFuture<String> command(String operation, String exactInputJson) {
        if (operation == null || exactInputJson == null || operation.length() > 64
                || exactInputJson.length() > 2 * 1024 * 1024) {
            return failed(new IllegalArgumentException("embedded command bounds"));
        }
        return submit(() -> {
            if (phase.get() != Phase.READY || localFixture) {
                throw new IllegalStateException("embedded process host not ready");
            }
            return EmbeddedDuplexNative.runtimeCommand(operation, exactInputJson);
        });
    }

    CompletableFuture<EmbeddedDuplexEnrollment> replaceEnrollment(
            EmbeddedDuplexEnrollmentDraft reviewedDraft) {
        if (reviewedDraft == null) return failed(new IllegalArgumentException("reviewed enrollment draft"));
        synchronized (attachmentGate) {
            if (displayDetaching || closeInFlight || platform != null || resources != null
                    || runtimeConfigSha256 != null
                    || !phase.compareAndSet(Phase.NEW, Phase.PROVISIONING)) {
                return failed(new IllegalStateException("process authority must terminate before enrollment change"));
            }
        }
        return submit(() -> {
            try {
                if (!EmbeddedDuplexNative.processIdleForEnrollment()) {
                    throw new IllegalStateException("native process or staged route is not terminal");
                }
                return EmbeddedDuplexEnrollmentResolver.replace(applicationContext, reviewedDraft);
            } finally {
                phase.set(Phase.NEW);
            }
        });
    }

    CompletableFuture<EmbeddedDuplexEnrollment> provisionLocalDiagnosticFixture(String challenge) {
        synchronized (attachmentGate) {
            if (challenge == null || !challenge.equals(diagnosticChallenge)) {
                return failed(new IllegalStateException("local diagnostic challenge unavailable"));
            }
        }
        try {
            return replaceEnrollment(EmbeddedDuplexEnrollmentResolver.localDiagnosticDraft(
                    applicationContext));
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    void armDiagnosticChallenge(String challenge) {
        synchronized (attachmentGate) {
            if (challenge == null || !challenge.matches("[0-9a-f]{32}")
                    || phase.get() != Phase.NEW || displayDetaching || closeInFlight
                    || platform != null || resources != null || runtimeConfigSha256 != null
                    || diagnosticChallenge != null) {
                throw new IllegalStateException("local diagnostic challenge unavailable");
            }
            diagnosticChallenge = challenge;
        }
    }

    boolean hasDiagnosticChallenge(String challenge) {
        synchronized (attachmentGate) {
            return challenge != null && challenge.equals(diagnosticChallenge)
                    && phase.get() == Phase.NEW && !displayDetaching && !closeInFlight;
        }
    }

    /** One-Quest diagnostic: resolve the signed fixture in process scope,
     * bootstrap without Start, then prove terminal closure before reporting. */
    CompletableFuture<String> diagnoseLocalFixture(long expectedGeneration) {
        final String challenge;
        synchronized (attachmentGate) {
            if (expectedGeneration == 0L || attachmentGeneration != expectedGeneration
                    || displayDetaching || display.cleanupPending()
                    || diagnosticChallenge == null
                    || !phase.compareAndSet(Phase.NEW, Phase.BOOTSTRAPPING)) {
                return failed(new IllegalStateException("local diagnostic display unavailable"));
            }
            challenge = diagnosticChallenge;
            diagnosticChallenge = null;
        }
        AtomicReference<String> enrollmentSha = new AtomicReference<>();
        EmbeddedDuplexBootstrap.Trace trace = new EmbeddedDuplexBootstrap.Trace();
        CompletableFuture<String> bootstrap = submit(() -> {
            try {
                trace.mark(EmbeddedDuplexBootstrap.Failure.ENROLLMENT_RESOLVE);
                EmbeddedDuplexEnrollment enrollment =
                        EmbeddedDuplexEnrollmentResolver.resolve(applicationContext);
                enrollmentSha.set(enrollment.recordSha256);
                trace.mark(EmbeddedDuplexBootstrap.Failure.FIXTURE_INPUTS);
                EmbeddedDuplexLocalDiagnosticInputs fresh =
                        EmbeddedDuplexLocalDiagnosticInputs.create(enrollment);
                return initializeOnCommandLane(fresh.role, fresh.runtimeBindings.toString(),
                        fresh.startup.toString(), true, trace);
            } catch (Exception failure) {
                if (phase.get() == Phase.BOOTSTRAPPING) {
                    phase.set(runtimeConfigSha256 == null ? Phase.NEW : Phase.FAILED);
                }
                throw failure;
            }
        });
        return bootstrap.handle((initialized, failure) -> {
            Phase current = phase.get();
            String configSha = runtimeConfigSha256;
            if (current == Phase.NEW) {
                try {
                    detachUninitializedDisplay(expectedGeneration);
                    return CompletableFuture.completedFuture(
                            terminalDiagnosticResult(challenge, "bootstrap_unavailable_closed",
                                    enrollmentSha.get(), configSha, trace.failure()));
                } catch (Exception cleanup) { return EmbeddedDuplexProcessHost.<String>failed(cleanup); }
            }
            if (current != Phase.READY && current != Phase.FAILED) {
                return EmbeddedDuplexProcessHost.<String>failed(
                        new IllegalStateException("local diagnostic cleanup state unavailable"));
            }
            return closeNoMediaAndDetach(expectedGeneration).thenApply(closed ->
                    terminalDiagnosticResult(challenge,
                            failure == null ? "bootstrap_closed" : "bootstrap_failed_closed",
                            enrollmentSha.get(), configSha,
                            failure == null ? null : trace.failure()));
        }).thenCompose(next -> next);
    }

    private String terminalDiagnosticResult(String challenge, String status,
            String enrollmentSha, String configSha, EmbeddedDuplexBootstrap.Failure failure) {
        try {
            return EmbeddedDuplexDiagnosticService.finalizeReceipt(applicationContext,
                    challenge, status, enrollmentSha, configSha, failure);
        } catch (Exception receiptFailure) {
            // Cleanup is already terminal. Do not strand the old Activity
            // attachment merely because the optional diagnostic file failed.
            return "{\"$schema\":\"rusty.quest.embedded_duplex.local_diagnostic.v1\","
                    + "\"status\":\"receipt_unavailable_closed\","
                    + "\"display_detached\":true}";
        }
    }

    /** Retry the same attachment after a diagnostic failure. The Activity must
     * not retire its display executor until this future has completed. */
    CompletableFuture<String> retryLocalDiagnosticCleanup(long expectedGeneration) {
        synchronized (attachmentGate) {
            if (expectedGeneration == 0L || attachmentGeneration != expectedGeneration
                    || !localFixture && runtimeConfigSha256 != null) {
                return failed(new IllegalStateException("local fixture cleanup unavailable"));
            }
            if (phase.get() == Phase.NEW && runtimeConfigSha256 == null) {
                return submit(() -> {
                    detachUninitializedDisplay(expectedGeneration);
                    return "uninitialized-display-detached";
                });
            }
        }
        return closeNoMediaAndDetach(expectedGeneration);
    }

    /** The no-Start path releases one process attachment only after all native,
     * Java and display resources prove terminal. A failed close retains exactly
     * the same attachment and objects for retry. */
    CompletableFuture<String> closeNoMediaAndDetach(long expectedGeneration) {
        synchronized (attachmentGate) {
            Phase current = phase.get();
            if (expectedGeneration == 0L || attachmentGeneration != expectedGeneration
                    || closeInFlight || (current != Phase.READY && current != Phase.FAILED
                    && current != Phase.CLOSING)) {
                return failed(new IllegalStateException("embedded no-media close unavailable"));
            }
            closeInFlight = true;
            displayDetaching = true;
            phase.set(Phase.CLOSING);
        }
        return submit(() -> {
            try {
                final String expectedSha = runtimeConfigSha256;
                if (expectedSha == null || !expectedSha.matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("prepared native route identity unavailable");
                }
                display.detachAfterCleanup(expectedGeneration, () -> {
                    EmbeddedDuplexPlatform currentPlatform = platform;
                    if (currentPlatform != null) currentPlatform.closeControlForNoMedia();
                    // Native refuses a busy host or any media/activation effect.
                    // If Java resources later fail to close, an exact retry sees
                    // already_closed and still retains these Java objects.
                    JSONObject closed = new JSONObject(EmbeddedDuplexNative.closeNoMediaRuntime(expectedSha));
                    String disposition = closed.getString("disposition");
                    if (!"rusty.quest.embedded_duplex.no_media_closed.v1".equals(
                            closed.getString("$schema"))
                            || !expectedSha.equals(closed.getString("config_sha256"))
                            || !("host_closed".equals(disposition)
                                    || "staged_route_closed".equals(disposition)
                                    || "already_closed".equals(disposition))) {
                        throw new IllegalStateException("native no-media close proof differs");
                    }
                    EmbeddedDuplexResources currentResources = resources;
                    if (currentResources != null) currentResources.closeUnstartedAndVerify();
                });
                platform = null;
                resources = null;
                runtimeConfigSha256 = null;
                localFixture = false;
                synchronized (attachmentGate) {
                    attachmentGeneration = 0L;
                    displayDetaching = false;
                    phase.set(Phase.NEW);
                }
                return "no-media-closed";
            } finally {
                synchronized (attachmentGate) { closeInFlight = false; }
            }
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
