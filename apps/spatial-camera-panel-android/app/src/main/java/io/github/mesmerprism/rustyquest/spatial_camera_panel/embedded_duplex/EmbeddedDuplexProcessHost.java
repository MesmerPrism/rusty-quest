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
    // Held for the lifetime of this singleton, including failed bootstrap/cleanup.
    private volatile EmbeddedDuplexProcessFence processFence;
    private long nativeExecutorGeneration;
    private String nativeAppRecordSha256;
    private final EmbeddedDuplexDisplaySlot display = new EmbeddedDuplexDisplaySlot();
    private final Object attachmentGate = new Object();
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.NEW);
    private volatile long attachmentGeneration;
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
    private String enrollmentRecordSha256;
    private volatile boolean localFixture;
    private String diagnosticChallenge;
    // A single process-local review is held by object identity and consumed once.
    private EmbeddedDuplexEnrollmentReview pendingReview;
    // A preflight intent binds the current signed session to this process and
    // display. It is never sufficient to issue a route or invoke Start.
    private volatile EmbeddedDuplexStartPreflight pendingStartPreflight;

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
            if (processFence != null && processFence.recoveryOnly()) {
                throw new IllegalStateException("retained process effects require recovery; display detach is not cleanup");
            }
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
            pendingReview = null;
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
                requireFreshProcess();
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                JSONObject runtime = new JSONObject(runtimeCopy);
                JSONObject enrollment = new JSONObject(startupCopy);
                processFence.beforeRuntimeEffects(checkpointSnapshot(), evidenceSnapshot());
                JSONObject nativeFence = new JSONObject(EmbeddedDuplexNative.claimNativeProcessFence(processFence));
                String recordSha = EmbeddedDuplexProcessFence.digest(processFence.nativeAdmissionRecord());
                if (!"rusty.quest.embedded_duplex.native_fence_admitted.v1".equals(nativeFence.getString("$schema"))
                        || nativeFence.getLong("app_generation") != processFence.generation()
                        || !recordSha.equals(nativeFence.getString("app_record_sha256"))
                        || nativeFence.getLong("executor_generation") <= 0L) {
                    throw new IllegalStateException("native process fence binding differs");
                }
                nativeExecutorGeneration = nativeFence.getLong("executor_generation");
                nativeAppRecordSha256 = recordSha;
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
                callbacks.bindProcessFence(processFence);
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
                JSONObject nativeResult = new JSONObject(initialized);
                if (nativeResult.getLong("executor_generation") != nativeExecutorGeneration
                        || nativeResult.getLong("app_process_generation") != processFence.generation()
                        || !nativeAppRecordSha256.equals(nativeResult.getString("app_record_sha256"))) {
                    throw new IllegalStateException("native initialized process incarnation differs");
                }
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
                phase.set(runtimeConfigSha256 == null && (processFence == null
                        || !processFence.effectsPending()) ? Phase.NEW : Phase.FAILED);
                throw failure;
            }
    }

    CompletableFuture<String> command(String operation, String exactInputJson) {
        if (operation == null || exactInputJson == null || operation.length() > 64
                || exactInputJson.length() > 2 * 1024 * 1024) {
            return failed(new IllegalArgumentException("embedded command bounds"));
        }
        return submit(() -> {
            requireFreshProcess();
            if (phase.get() != Phase.READY || localFixture) {
                throw new IllegalStateException("embedded process host not ready");
            }
            return EmbeddedDuplexNative.runtimeCommand(operation, exactInputJson);
        });
    }

    /** Real-peer bootstrap stops before admission or any media owner effect. */
    CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeer(long expectedGeneration) {
        synchronized (attachmentGate) {
            if (expectedGeneration == 0L || attachmentGeneration != expectedGeneration
                    || displayDetaching || display.cleanupPending()
                    || !phase.compareAndSet(Phase.NEW, Phase.BOOTSTRAPPING)) {
                return failed(new IllegalStateException("real-peer display unavailable"));
            }
            pendingReview = null;
        }
        return submit(() -> {
            try {
                requireFreshProcess();
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                EmbeddedDuplexEnrollment enrollment =
                        EmbeddedDuplexEnrollmentResolver.resolve(applicationContext);
                EmbeddedDuplexSessionInputs inputs =
                        EmbeddedDuplexSessionInputs.createRealPeer(enrollment);
                enrollmentRecordSha256 = enrollment.recordSha256;
                initializeOnCommandLane(inputs.role, inputs.runtimeBindings.toString(),
                        inputs.startup.toString(), false, null);
                return runtimeStatusOnCommandLane();
            } catch (Exception failure) {
                if (phase.get() == Phase.BOOTSTRAPPING) {
                    phase.set(runtimeConfigSha256 == null && (processFence == null
                        || !processFence.effectsPending()) ? Phase.NEW : Phase.FAILED);
                }
                throw failure;
            }
        });
    }

    CompletableFuture<EmbeddedDuplexRuntimeStatus> runtimeStatus() {
        return submit(this::runtimeStatusOnCommandLane).handle((status, failure) ->
                failure == null ? status : new EmbeddedDuplexRuntimeStatus("cleanup_pending",
                        attachmentGeneration != 0L, runtimeConfigSha256, enrollmentRecordSha256));
    }

    CompletableFuture<EmbeddedDuplexPairStatus> pairStatus() {
        return submit(() -> {
            requireFreshProcess();
            if (phase.get() != Phase.READY || localFixture) {
                throw new IllegalStateException("real-peer authority unavailable");
            }
            return EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand(
                    "pair_status", "{}"));
        });
    }

    CompletableFuture<EmbeddedDuplexPairStatus> pairSession() {
        synchronized (attachmentGate) {
            if (phase.get() != Phase.READY || localFixture || attachmentGeneration == 0L
                    || displayDetaching || closeInFlight) {
                return failed(new IllegalStateException("real-peer pair ceremony unavailable"));
            }
        }
        return submit(() -> {
            requireFreshProcess();
            return EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand("pair_ceremony", "{}"));
        });
    }

    CompletableFuture<EmbeddedDuplexStartPreflight> prepareStartPreflight(long expectedGeneration) {
        synchronized (attachmentGate) {
            if (phase.get() != Phase.READY || localFixture || expectedGeneration == 0L
                    || attachmentGeneration != expectedGeneration || displayDetaching
                    || closeInFlight || display.cleanupPending()) {
                return failed(new IllegalStateException("pre-Start display unavailable"));
            }
        }
        return submit(() -> {
            synchronized (attachmentGate) {
                if (phase.get() != Phase.READY || localFixture
                        || attachmentGeneration != expectedGeneration || displayDetaching
                        || closeInFlight || display.cleanupPending()) {
                    throw new IllegalStateException("pre-Start process changed");
                }
            }
            requireFreshProcess();
            EmbeddedDuplexPairStatus pair = EmbeddedDuplexPairStatus.parse(
                    EmbeddedDuplexNative.runtimeCommand("pair_status", "{}"));
            EmbeddedDuplexStartPreflight next = EmbeddedDuplexStartPreflight.prepare(pair,
                    runtimeConfigSha256, enrollmentRecordSha256, expectedGeneration);
            if (pendingStartPreflight != null && !pendingStartPreflight.sameLineage(next)) {
                throw new IllegalStateException("pre-Start lineage changed");
            }
            pendingStartPreflight = next;
            return next;
        });
    }

    boolean preflightLive(EmbeddedDuplexStartPreflight observed) {
        synchronized (attachmentGate) {
            try {
                if (processFence == null) return false;
                processFence.requireLive(processFence.generation());
            } catch (IllegalStateException stale) { return false; }
            return !processFence.recoveryOnly()
                    && observed != null && pendingStartPreflight == observed
                    && phase.get() == Phase.READY && !localFixture && !displayDetaching
                    && !closeInFlight && attachmentGeneration == observed.displayGeneration
                    && observed.matches(runtimeConfigSha256, enrollmentRecordSha256,
                            attachmentGeneration)
                    && System.currentTimeMillis() < observed.sessionExpiresAtMs;
        }
    }

    private EmbeddedDuplexRuntimeStatus runtimeStatusOnCommandLane() {
        Phase current = phase.get();
        boolean recoveryClear;
        try {
            recoveryClear = processFence != null && !processFence.recoveryOnly()
                    && new EmbeddedDuplexRecoveryCoordinator(applicationContext)
                    .freshBootstrapAllowed();
        } catch (Exception unavailable) {
            recoveryClear = false;
        }
        String state = !recoveryClear ? "cleanup_pending"
                : current == Phase.READY && !localFixture
                ? "bootstrapped_route_unverified"
                : current == Phase.NEW ? "uninitialized"
                : current == Phase.READY ? "local_fixture"
                : current == Phase.BOOTSTRAPPING ? "bootstrapping" : "cleanup_pending";
        return new EmbeddedDuplexRuntimeStatus(state, attachmentGeneration != 0L,
                runtimeConfigSha256, enrollmentRecordSha256);
    }

    CompletableFuture<String> closeRealPeerNoMedia(long expectedGeneration) {
        synchronized (attachmentGate) {
            if (localFixture || expectedGeneration == 0L
                    || attachmentGeneration != expectedGeneration) {
                return failed(new IllegalStateException("real-peer no-media close unavailable"));
            }
            if (phase.get() == Phase.NEW && runtimeConfigSha256 == null) {
                return submit(() -> {
                    detachUninitializedDisplay(expectedGeneration);
                    enrollmentRecordSha256 = null;
                    return "uninitialized-display-detached";
                });
            }
        }
        return closeNoMediaAndDetach(expectedGeneration);
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
            pendingReview = null;
        }
        return submit(() -> {
            try {
                requireFreshProcess();
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                if (!EmbeddedDuplexNative.processIdleForEnrollment()) {
                    throw new IllegalStateException("native process or staged route is not terminal");
                }
                return EmbeddedDuplexEnrollmentResolver.replace(applicationContext, reviewedDraft);
            } finally {
                phase.set(Phase.NEW);
            }
        });
    }

    CompletableFuture<EmbeddedDuplexEnrollmentReview> reviewEnrollment(
            EmbeddedDuplexEnrollmentDraft draft) {
        synchronized (attachmentGate) { pendingReview = null; }
        if (draft == null) return failed(new IllegalArgumentException("enrollment review draft"));
        return submit(() -> {
            synchronized (attachmentGate) {
                if (displayDetaching || closeInFlight || platform != null || resources != null
                        || runtimeConfigSha256 != null || phase.get() != Phase.NEW) {
                    throw new IllegalStateException("process authority must terminate before enrollment review");
                }
            }
            requireFreshProcess();
            new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
            if (!EmbeddedDuplexNative.processIdleForEnrollment()) {
                throw new IllegalStateException("native process or staged route is not terminal");
            }
            EmbeddedDuplexEnrollmentReview review =
                    EmbeddedDuplexEnrollmentResolver.review(applicationContext, draft);
            synchronized (attachmentGate) {
                if (displayDetaching || closeInFlight || phase.get() != Phase.NEW) {
                    throw new IllegalStateException("process changed during enrollment review");
                }
                pendingReview = review;
            }
            return review;
        });
    }

    CompletableFuture<EmbeddedDuplexEnrollmentStatus> enrollmentStatus(String roleId) {
        if (!("peer_a".equals(roleId) || "peer_b".equals(roleId))) {
            return failed(new IllegalArgumentException("installed role invalid"));
        }
        return submit(() -> EmbeddedDuplexEnrollmentResolver.status(applicationContext, roleId));
    }

    CompletableFuture<EmbeddedDuplexEnrollment> confirmEnrollment(
            EmbeddedDuplexEnrollmentReview review) {
        synchronized (attachmentGate) {
            if (review == null || pendingReview != review || review.expired()
                    || displayDetaching || closeInFlight || platform != null || resources != null
                    || runtimeConfigSha256 != null
                    || !phase.compareAndSet(Phase.NEW, Phase.PROVISIONING)) {
                return failed(new IllegalStateException("enrollment review unavailable or expired"));
            }
            pendingReview = null;
        }
        return submit(() -> {
            try {
                requireFreshProcess();
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                if (review.expired() || !EmbeddedDuplexNative.processIdleForEnrollment()) {
                    throw new IllegalStateException("review expired or native process is not terminal");
                }
                return EmbeddedDuplexEnrollmentResolver.replaceReviewed(applicationContext, review);
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
                EmbeddedDuplexSessionInputs fresh =
                        EmbeddedDuplexSessionInputs.createLocalDiagnostic(enrollment);
                return initializeOnCommandLane(fresh.role, fresh.runtimeBindings.toString(),
                        fresh.startup.toString(), true, trace);
            } catch (Exception failure) {
                if (phase.get() == Phase.BOOTSTRAPPING) {
                    phase.set(runtimeConfigSha256 == null && (processFence == null
                        || !processFence.effectsPending()) ? Phase.NEW : Phase.FAILED);
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
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                EmbeddedDuplexNative.finishNativeNoMediaCleanup(nativeExecutorGeneration, expectedSha);
                processFence.afterVerifiedNoMediaCleanup(checkpointSnapshot(), evidenceSnapshot());
                if (platform != null) platform.retireProcessCallbacks();
                platform = null;
                resources = null;
                runtimeConfigSha256 = null;
                nativeExecutorGeneration = 0L;
                nativeAppRecordSha256 = null;
                enrollmentRecordSha256 = null;
                pendingStartPreflight = null;
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

    private String checkpointSnapshot() throws Exception {
        return new EmbeddedDuplexStartJournal(applicationContext).read();
    }
    private String evidenceSnapshot() throws Exception {
        return new EmbeddedDuplexRecoveryEvidenceJournal(applicationContext).readValidated();
    }
    private void requireFreshProcess() {
        if (processFence == null) throw new IllegalStateException("process fence unavailable");
        processFence.requireFresh();
    }
    private void ensureProcessFence() throws Exception {
        if (processFence != null) {
            processFence.requireLive(processFence.generation());
            return;
        }
        // Journal constructors validate the private directory and file types.
        String checkpoint = checkpointSnapshot();
        String evidence = evidenceSnapshot();
        java.io.File directory = new java.io.File(applicationContext.getNoBackupFilesDir(),
                "embedded-duplex-replay");
        processFence = EmbeddedDuplexProcessFence.acquire(directory, checkpoint, evidence, () -> {
            java.io.FileDescriptor fd = android.system.Os.open(directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY | android.system.OsConstants.O_CLOEXEC, 0);
            try { android.system.Os.fsync(fd); } finally { android.system.Os.close(fd); }
        });
        android.system.Os.chmod(new java.io.File(directory, "process-fence.v1.lock").getAbsolutePath(), 0600);
    }

    private interface Work<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> submit(Work<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        commands.execute(() -> {
            try { ensureProcessFence(); result.complete(action.run()); }
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
