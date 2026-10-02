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
    private static volatile EmbeddedDuplexProcessHost instance;

    /** Native observes actual retained providers; absence and uncertainty are not terminal. */
    public static boolean nativeProductResourcesTerminal() {
        EmbeddedDuplexProcessHost current = instance;
        if (current == null) return false;
        EmbeddedDuplexResources retained = current.resources;
        if (retained == null) return false;
        try { return retained.productResourcesTerminal(); }
        catch (RuntimeException unavailable) { return false; }
    }

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
    private volatile EmbeddedDuplexResources resources;
    private String runtimeConfigSha256;
    private volatile EmbeddedDuplexBootstrap.Failure lastBootstrapFailure;
    private String ownFeatureLockSha256;
    private String ownNoMediaConfigSha256;
    private String ownNoMediaFeatureSha256;
    private String ownNoMediaCloseProof;
    private long ownNoMediaExecutorGeneration;
    private long ownNoMediaAppGeneration;
    private Runnable ownNoMediaStopTarget;
    private boolean ownNoMediaCleanupAttempted;
    private String retainedWholeNativeReceipt;
    private String terminalWholeReceipt;
    private String terminalWholeChallenge;
    private String enrollmentRecordSha256;
    private volatile boolean localFixture;
    private String diagnosticChallenge;
    // A single process-local review is held by object identity and consumed once.
    private EmbeddedDuplexEnrollmentReview pendingReview;
    // A preflight intent binds the current signed session to this process and
    // display. It is never sufficient to issue a route or invoke Start.
    private final EmbeddedDuplexStartIntentSlot startIntent = new EmbeddedDuplexStartIntentSlot();

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
            EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.PROCESS_FENCE);
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
                JSONObject ownBootstrap = nativeResult.optJSONObject("own_capture_bootstrap");
                ownFeatureLockSha256 = ownBootstrap == null ? null : ownBootstrap.getString("app_feature_lock_sha256");
                if (nativeResult.getLong("executor_generation") != nativeExecutorGeneration
                        || nativeResult.getLong("app_process_generation") != processFence.generation()
                        || !nativeAppRecordSha256.equals(nativeResult.getString("app_record_sha256"))) {
                    throw new IllegalStateException("native initialized process incarnation differs");
                }
                EmbeddedDuplexResources installed = new EmbeddedDuplexResources(applicationContext,
                        display, new JSONObject(initialized), prepared.maxPairDeltaNs);
                resources = installed;
                installed.install();
                callbacks.installResources(installed);
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.CONTROL_ENDPOINT);
                callbacks.startControl(prepared.localControlHost, prepared.localControlPort);
                if (!callbacks.controlReady()) {
                    throw new IllegalStateException("authenticated control endpoint unavailable");
                }
                // Supersede retained cleanup facts only after the new authenticated host owns real resources/control.
                ownNoMediaCloseProof = null; ownNoMediaStopTarget = null;
                terminalWholeReceipt = null; terminalWholeChallenge = null; retainedWholeNativeReceipt = null;
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

    CompletableFuture<String> concurrentQualification(boolean arm, String challenge) {
        return concurrentQualificationOnLane(arm, challenge, false, null, null);
    }
    /** Bounded debug-only observation; a current arm is checked twice across lane turns. */
    CompletableFuture<String> ownCaptureDiagnostic(String challenge) {
        return captureDiagnostic(challenge, false);
    }
    CompletableFuture<String> streamDropoutDiagnostic(String challenge) {
        return captureDiagnostic(challenge, true);
    }
    private CompletableFuture<String> captureDiagnostic(String challenge, boolean dropoutOnly) {
        return concurrentQualificationOnLane(false, challenge, false, null, null)
                .thenCompose(firstReceipt -> submit(() -> {
                    requireFreshProcess();
                    Phase currentPhase = phase.get();
                    EmbeddedDuplexResources retained = resources;
                    if ((currentPhase != Phase.READY && currentPhase != Phase.FAILED)
                            || localFixture || retained == null || !retained.ownAppCaptureEnabled()) {
                        throw new IllegalStateException("Own capture diagnostic unavailable");
                    }
                    processFence.requireLive(processFence.generation());
                    JSONObject first = new JSONObject(firstReceipt);
                    String epoch = processFence.epochId();
                    if (!challenge.equals(first.getString("challenge"))
                            || !epoch.equals(first.getString("process_epoch_id"))
                            || !runtimeConfigSha256.equals(first.getString("runtime_config_sha256"))
                            || !ownFeatureLockSha256.equals(first.getString("feature_lock_sha256"))) {
                        throw new IllegalStateException("Own capture diagnostic lineage changed");
                    }
                    JSONObject qualification = new JSONObject(ConcurrentStereoQualification.status(
                            challenge, epoch, runtimeConfigSha256, ownFeatureLockSha256,
                            first.getString("apk_sha256")));
                    if (qualification.getLong("arm_generation") != first.getLong("arm_generation")
                            || qualification.getLong("native_process_generation")
                            != first.getLong("native_process_generation")) {
                        throw new IllegalStateException("Own capture diagnostic native process or arm changed");
                    }
                    long sampleStartNs = android.os.SystemClock.elapsedRealtimeNanos();
                    JSONObject armContext = new JSONObject(ConcurrentStereoQualification.diagnosticArmContext(challenge, epoch));
                    if (dropoutOnly) {
                        JSONObject nativeObservation = new JSONObject(ConcurrentStereoQualification.dropoutObservation(challenge, epoch));
                        JSONObject javaObservation = retained.sourceDropoutSnapshot();
                        EmbeddedDuplexDropoutLineage.requireCurrent(javaObservation, epoch,
                                processFence.generation(), qualification.getLong("arm_generation"));
                        JSONObject report = new JSONObject()
                                .put("schema", "rusty.quest.embedded_duplex.stream_dropout_diagnostic.v1")
                                .put("qualification_claimed", false)
                                .put("challenge", challenge).put("process_epoch_id", epoch)
                                .put("app_generation", processFence.generation())
                                .put("arm_generation", qualification.getLong("arm_generation"))
                                .put("consistency", "sequential_non_atomic_observation")
                                .put("sample_start_elapsed_ns", sampleStartNs)
                                .put("sample_end_elapsed_ns", android.os.SystemClock.elapsedRealtimeNanos())
                                .put("qualification", qualification)
                                .put("native_dropout_observation", nativeObservation)
                                .put("java_dropout_observation", javaObservation);
                        String exact = report.toString();
                        if (exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024) {
                            throw new IllegalStateException("Stream dropout diagnostic bounds");
                        }
                        return exact;
                    }
                    JSONObject source = retained.sourceSnapshot();
                    long sampleEndNs = android.os.SystemClock.elapsedRealtimeNanos();
                    if (!source.getBoolean("shared_app_capture")) {
                        throw new IllegalStateException("Own capture diagnostic source differs");
                    }
                    JSONObject report = new JSONObject()
                            .put("schema", "rusty.quest.embedded_duplex.own_capture_diagnostic.v1")
                            .put("challenge", challenge)
                            .put("process_epoch_id", epoch)
                            .put("app_generation", processFence.generation())
                            .put("arm_generation", qualification.getLong("arm_generation"))
                            .put("arm_entry_elapsed_ns", armContext.getLong("arm_entry_elapsed_ns"))
                            .put("arm_exit_elapsed_ns", armContext.getLong("arm_exit_elapsed_ns"))
                            .put("consistency", "sequential_non_atomic_observation")
                            .put("java_clock", "android_elapsedRealtimeNanos")
                            .put("native_clock", "CLOCK_MONOTONIC")
                            .put("sample_start_elapsed_ns", sampleStartNs)
                            .put("sample_end_elapsed_ns", sampleEndNs)
                            .put("sample_wall_ms", System.currentTimeMillis())
                            .put("qualification", qualification)
                            .put("own_capture_stage", source.getJSONObject("own_capture_stage_diagnostic"))
                            .put("codec_output_with_pair", source.getJSONObject("codec_output_with_pair"))
                            .put("encoded_frames", source.getLong("encoded_frames"))
                            .put("video_packet_count", source.getLong("video_packet_count"))
                            .put("last_packet_age_ms", source.getLong("last_packet_age_ms"));
                    String exact = report.toString();
                    if (exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024) {
                        throw new IllegalStateException("Own capture diagnostic bounds");
                    }
                    return exact;
                }));
    }
    CompletableFuture<String> concurrentPolicy(String challenge, long[] policy) {
        return concurrentQualificationOnLane(false, challenge, true, policy == null ? null : policy.clone(), null);
    }
    CompletableFuture<String> peerLifecycle(EmbeddedDuplexPeerAction action, String challenge) {
        if (action == null) return failed(new IllegalArgumentException("peer action required"));
        return concurrentQualificationOnLane(false, challenge, false, null, action);
    }
    private CompletableFuture<String> concurrentQualificationOnLane(boolean arm, String challenge, boolean policyAction, long[] policy, EmbeddedDuplexPeerAction peerAction) {
        return submit(() -> {
            requireFreshProcess();
            if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && terminalWholeReceipt != null) {
                if (!challenge.equals(terminalWholeChallenge)) throw new IllegalStateException("closed challenge differs");
                processFence.requireLive(processFence.generation());
                return terminalWholeReceipt;
            }
            Phase observedPhase = phase.get();
            boolean cleanupObservation = !policyAction && (peerAction == null
                    || peerAction == EmbeddedDuplexPeerAction.STOP
                    || peerAction == EmbeddedDuplexPeerAction.REVOKE
                    || peerAction == EmbeddedDuplexPeerAction.STATUS
                    || peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE);
            boolean noMediaFallback = observedPhase == Phase.NEW && runtimeConfigSha256 == null
                    && nativeExecutorGeneration == 0L && ownNoMediaCloseProof != null
                    && ownNoMediaAppGeneration == processFence.generation();
            boolean fallbackAction = peerAction == null || peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE;
            boolean phaseAllowed = noMediaFallback && cleanupObservation && fallbackAction
                    || observedPhase == Phase.READY || cleanupObservation
                    && (observedPhase == Phase.FAILED || observedPhase == Phase.CLOSING);
            if (!phaseAllowed || localFixture || processFence == null
                    || (noMediaFallback ? ownNoMediaConfigSha256 == null || ownNoMediaFeatureSha256 == null
                        : runtimeConfigSha256 == null || ownFeatureLockSha256 == null)) {
                throw new IllegalStateException("selected process observation unavailable");
            }
            processFence.requireLive(processFence.generation());
            String epoch = processFence.epochId();
            final String receiptConfig = noMediaFallback ? ownNoMediaConfigSha256 : runtimeConfigSha256;
            final String receiptFeature = noMediaFallback ? ownNoMediaFeatureSha256 : ownFeatureLockSha256;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = new java.io.FileInputStream(applicationContext.getApplicationInfo().sourceDir)) {
                byte[] block = new byte[65536]; int count;
                while ((count = input.read(block)) != -1) digest.update(block, 0, count);
            }
            StringBuilder apk = new StringBuilder();
            for (byte b : digest.digest()) apk.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (peerAction != null) {
                // Check the retained process/challenge/arm before any authority mutation.
                ConcurrentStereoQualification.status(challenge, epoch, receiptConfig,
                        receiptFeature, apk.toString());
                EmbeddedDuplexStartPreflight starting = startIntent.pending();
                if (peerAction == EmbeddedDuplexPeerAction.START && !preflightLive(starting))
                    throw new IllegalStateException("current paired Start intent unavailable");
                if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE) {
                    OwnStereoCaptureRuntime own = OwnStereoCaptureRuntime.currentForApplication();
                    if (noMediaFallback) {
                        if (!ownNoMediaCleanupAttempted) {
                            if (processFence.effectsPending()) throw new IllegalStateException("another durable effect is Pending");
                            processFence.beforeRuntimeEffects(checkpointSnapshot(), evidenceSnapshot());
                            ownNoMediaCleanupAttempted = true;
                        }
                        if (ownNoMediaStopTarget == null) throw new IllegalStateException("retained Own stop target unavailable");
                        if (own != null) own.requestStopOwn();
                        ownNoMediaStopTarget.run();
                    } else {
                        if (own != null) own.requestStopOwn();
                        display.requestWholeProjectionStop();
                    }
                }
                if (peerAction == EmbeddedDuplexPeerAction.START) startIntent.dispatch(starting);
                String nativeReceipt = noMediaFallback ? noMediaWholeProof(receiptConfig)
                        : retainedWholeNativeReceipt != null && peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE
                            ? retainedWholeNativeReceipt : EmbeddedDuplexNative.peerLifecycle(peerAction.word);
                if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE) {
                    OwnStereoCaptureRuntime own = OwnStereoCaptureRuntime.currentForApplication();
                    JSONObject physical = new JSONObject(nativeReceipt);
                    physical.put("java_own_capture_cleanup", own == null ? "unknown" : own.physicalCleanupState());
                    if ("terminal".equals(physical.optString("native_host_physical_cleanup")))
                        retainedWholeNativeReceipt = nativeReceipt;
                    finishWholeAppProof(physical, receiptConfig, noMediaFallback);

                    nativeReceipt = physical.toString();
                }
                String receipt = ConcurrentStereoQualification.lifecycle(peerAction.action, challenge, epoch, receiptConfig, receiptFeature, apk.toString(), nativeReceipt);
                if (peerAction == EmbeddedDuplexPeerAction.START) startIntent.acknowledge(starting, nativeReceipt);
                if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE
                        && "terminal".equals(new JSONObject(nativeReceipt).optString("whole_app_physical_cleanup"))) {
                    terminalWholeReceipt = receipt; terminalWholeChallenge = challenge;
                }
                return receipt;
            }
            if (policyAction) return ConcurrentStereoQualification.policy(challenge, epoch, receiptConfig, receiptFeature, apk.toString(), policy);
            if (arm) {
                String receipt = ConcurrentStereoQualification.arm(challenge, epoch, receiptConfig, receiptFeature, apk.toString());
                EmbeddedDuplexResources retained = resources;
                if (retained != null && retained.ownAppCaptureEnabled()) {
                    JSONObject context = new JSONObject(ConcurrentStereoQualification.diagnosticArmContext(challenge, epoch));
                    retained.armOwnCaptureTrace(epoch, processFence.generation(), context.getLong("arm_generation"));
                }
                return receipt;
            }
            return ConcurrentStereoQualification.status(challenge, epoch, receiptConfig, receiptFeature, apk.toString());
        });
    }

    CompletableFuture<String> selectLocalAfterTerminal(
            io.github.mesmerprism.rustyquest.spatial_camera_panel.LocalRollbackRequestFence fence,
            io.github.mesmerprism.rustyquest.spatial_camera_panel.LocalRollbackRequestFence.Ticket ticket,
            java.util.function.LongSupplier currentRouteGeneration,
            java.util.function.BooleanSupplier ownerAlive) {
        return submit(() -> {
            if (phase.get()!=Phase.NEW || terminalWholeReceipt==null || closeInFlight
                    || platform!=null || resources!=null || runtimeConfigSha256!=null)
                throw new IllegalStateException("verified whole-app cleanup required before Local");
            JSONObject proof=new JSONObject(terminalWholeReceipt).getJSONObject("peer_lifecycle");
            if (!"terminal".equals(proof.optString("whole_app_physical_cleanup")))
                throw new IllegalStateException("whole-app cleanup remains Pending");
            OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.currentForApplication();
            if (own==null || !"terminal".equals(own.physicalCleanupState()))
                throw new IllegalStateException("Own physical cleanup remains Pending");
            String expectedConfig=proof.getString("config_sha256");
            String nativeReceipt=fence.commitIfCurrent(ticket,currentRouteGeneration,ownerAlive,
                    () -> EmbeddedDuplexNative.selectLocalAfterTerminal(expectedConfig));
            JSONObject selected=new JSONObject(nativeReceipt);
            if (!"rusty.quest.local_rollback_native.v1".equals(selected.getString("schema"))
                    || !expectedConfig.equals(selected.getString("config_sha256"))
                    || selected.getBoolean("feature_enabled")
                    || !"terminal".equals(selected.getString("physical_cleanup"))
                    || !"route-selection-only".equals(selected.getString("scope")))
                throw new IllegalStateException("native Local feature-off proof differs");
            return selected.put("app_verified_config_sha256",expectedConfig).toString();
        });
    }

    CompletableFuture<Boolean> resumeOwnProjection(long expectedGeneration,
            java.util.function.BooleanSupplier ownerAlive) {
        return submit(() -> {
            requireFreshProcess();
            synchronized (attachmentGate) {
                if (expectedGeneration<=0L || attachmentGeneration!=expectedGeneration || displayDetaching
                        || closeInFlight || phase.get()!=Phase.READY || localFixture || resources==null
                        || runtimeConfigSha256==null || ownerAlive==null || !ownerAlive.getAsBoolean()) return false;
            }
            OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.currentForApplication();
            if (own==null || own.phase()!=OwnStereoCaptureRuntime.Phase.Live || own.retainedCapture()==null) return false;
            display.resumeOwnProjection(ownerAlive);
            return true; // Native owner acceptance only; moving pixels remain separately observed.
        });
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
        EmbeddedDuplexBootstrap.Trace trace = new EmbeddedDuplexBootstrap.Trace();
        return submit(() -> {
            lastBootstrapFailure = null;
            trace.mark(EmbeddedDuplexBootstrap.Failure.PROCESS_FENCE);
            try {
                requireFreshProcess();
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                trace.mark(EmbeddedDuplexBootstrap.Failure.ENROLLMENT_RESOLVE);
                EmbeddedDuplexEnrollment enrollment =
                        EmbeddedDuplexEnrollmentResolver.resolve(applicationContext);
                trace.mark(EmbeddedDuplexBootstrap.Failure.SESSION_INPUTS);
                EmbeddedDuplexSessionInputs inputs =
                        EmbeddedDuplexSessionInputs.createRealPeer(enrollment);
                enrollmentRecordSha256 = enrollment.recordSha256;
                initializeOnCommandLane(inputs.role, inputs.runtimeBindings.toString(),
                        inputs.startup.toString(), false, trace);
                return runtimeStatusOnCommandLane();
            } catch (Exception failure) {
                lastBootstrapFailure = trace.failure();
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
            return startIntent.prepare(next);
        });
    }

    boolean preflightLive(EmbeddedDuplexStartPreflight observed) {
        synchronized (attachmentGate) {
            try {
                if (processFence == null) return false;
                processFence.requireLive(processFence.generation());
            } catch (IllegalStateException stale) { return false; }
            return !processFence.recoveryOnly()
                    && observed != null && startIntent.pending() == observed
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
        EmbeddedDuplexRuntimeStatus status = new EmbeddedDuplexRuntimeStatus(state, attachmentGeneration != 0L,
                runtimeConfigSha256, enrollmentRecordSha256, lastBootstrapFailure);
        OwnStereoCaptureRuntime ownCapture = OwnStereoCaptureRuntime.currentForApplication();
        return ownCapture != null ? new EmbeddedDuplexRuntimeStatus(status, ownCapture.phase().name(), ownCapture.cleanupStatus()) : status;
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
                final OwnStereoCaptureRuntime ownCapture = OwnStereoCaptureRuntime.currentForApplication();
                final boolean ownScope = ownCapture != null && !ownCapture.pollStopped();
                final long closedExecutorGeneration = nativeExecutorGeneration;
                final long closedAppGeneration = processFence.generation();
                final Runnable retainedStop = ownScope ? display.retainOwnedProjectionStopTarget(expectedGeneration) : null;
                final String[] closedProof = new String[1];
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
                    if (ownScope
                            && !"peer_subscription_only".equals(closed.optString("cleanup_scope"))) {
                        throw new IllegalStateException("native no-media receipt lacks explicit Peer-only cleanup scope");
                    }
                    if (ownScope && currentResources == null) throw new IllegalStateException("Own-retained Java no-media proof unavailable");
                    if (currentResources != null) {
                        currentResources.closeUnstartedAndVerify();
                        if (ownScope && !currentResources.productResourcesTerminal())
                            throw new IllegalStateException("Own-retained Java no-media barriers Pending");
                    }
                    closedProof[0] = closed.toString();
                });
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                EmbeddedDuplexNative.finishNativeNoMediaCleanup(nativeExecutorGeneration, expectedSha);
                processFence.afterVerifiedNoMediaCleanup(checkpointSnapshot(), evidenceSnapshot());
                if (ownScope && closedProof[0] != null && ownFeatureLockSha256 != null) {
                    ownNoMediaConfigSha256 = expectedSha; ownNoMediaFeatureSha256 = ownFeatureLockSha256;
                    ownNoMediaCloseProof = closedProof[0]; ownNoMediaExecutorGeneration = closedExecutorGeneration;
                    ownNoMediaAppGeneration = closedAppGeneration; ownNoMediaStopTarget = retainedStop;
                    ownNoMediaCleanupAttempted = false;
                }
                if (platform != null) platform.retireProcessCallbacks();
                platform = null;
                resources = null;
                runtimeConfigSha256 = null;
                nativeExecutorGeneration = 0L;
                nativeAppRecordSha256 = null;
                enrollmentRecordSha256 = null;
                startIntent.afterVerifiedCleanup();
                localFixture = false;
                synchronized (attachmentGate) {
                    attachmentGeneration = 0L;
                    displayDetaching = false;
                    phase.set(Phase.NEW);
                }
                return ownScope ? "peer-no-media-closed-own-capture-scope" : "no-media-closed";
            } finally {
                synchronized (attachmentGate) { closeInFlight = false; }
            }
        });
    }

    /** Scoped proof is retained only after actual native finish and Java no-media barriers. */
    private String noMediaWholeProof(String expectedSha) throws Exception {
        JSONObject closed = new JSONObject(ownNoMediaCloseProof);
        if (!expectedSha.equals(ownNoMediaConfigSha256) || !expectedSha.equals(closed.getString("config_sha256"))
                || ownNoMediaExecutorGeneration <= 0L || ownNoMediaAppGeneration != processFence.generation()
                || !"rusty.quest.embedded_duplex.no_media_closed.v1".equals(closed.getString("$schema"))
                || !"peer_subscription_only".equals(closed.getString("cleanup_scope"))
                || !closed.getBoolean("own_app_capture_retained") || platform != null || resources != null
                || nativeExecutorGeneration != 0L || runtimeConfigSha256 != null) {
            throw new IllegalStateException("retained no-media cleanup scope differs");
        }
        return new JSONObject().put("$schema", "rusty.quest.embedded_duplex.concurrent_no_media_own_cleanup.v1")
                .put("action", "whole_app_close").put("config_sha256", expectedSha)
                .put("native_executor_generation", ownNoMediaExecutorGeneration)
                .put("app_process_generation", ownNoMediaAppGeneration)
                .put("native_no_media_close_receipt", closed)
                .put("native_no_media_finish_verified", true)
                .put("java_no_media_barriers_verified", true)
                .put("peer_physical_cleanup", "never_attempted_native_proved")
                .put("native_host_physical_cleanup", "terminal")
                .put("whole_app_physical_cleanup", "pending").toString();
    }

    private void finishWholeAppProof(JSONObject proof, String expectedSha, boolean noMedia) throws Exception {
        proof.put("whole_app_physical_cleanup", "pending");
        long[] snapshot = io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoBankControls.INSTANCE.concurrentQualification();
        boolean rendererTerminal = snapshot.length == 160 && snapshot[0] == 1L && snapshot[1] == 160L
                && snapshot[9] == 2L && snapshot[63] == 0L && snapshot[156] == 0L && snapshot[157] == 0L;
        proof.put("java_renderer_cleanup", rendererTerminal ? "terminal" : "pending");
        if (!expectedSha.equals(proof.getString("config_sha256"))
                || proof.getLong("app_process_generation") != processFence.generation()
                || proof.getLong("native_executor_generation") != (noMedia ? ownNoMediaExecutorGeneration : nativeExecutorGeneration))
            throw new IllegalStateException("whole cleanup owner binding differs");
        if (!rendererTerminal || !"terminal".equals(proof.optString("java_own_capture_cleanup"))
                || !"terminal".equals(proof.optString("native_host_physical_cleanup"))) return;
        if (!noMedia && (!"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1".equals(proof.getString("$schema"))
                || !"terminal".equals(proof.optString("peer_physical_cleanup"))
                || !(proof.opt("media_stop_effect_receipt") instanceof JSONObject)
                || !(proof.opt("route_cleanup") instanceof org.json.JSONArray)
                || !(proof.opt("broker_evidence") instanceof JSONObject))) return;
        // The typed native facade only publishes Peer terminal after cleanup succeeds.
        // An empty cleanup array is a real idempotent retry; the native facade preserves retained route/effect receipts.
        if (noMedia) {
            // Never manufacture a seven-owner Stop effect for a native-proved unstarted route.
            noMediaWholeProof(expectedSha);
        } else {
            EmbeddedDuplexPlatform held = platform;
            if (held == null || resources == null || !resources.productResourcesTerminal()) return;
            display.detachAfterCleanup(attachmentGeneration, held::closeControlAfterProductCleanup);
            held.retireProcessCallbacks();
        }
        proof.put("java_control_cleanup", "terminal");
        String joinedDigest = EmbeddedDuplexProcessFence.digest(proof.toString());
        EmbeddedDuplexStartJournal journal = new EmbeddedDuplexStartJournal(applicationContext);
        String prior = journal.read();
        if (prior != null) {
            JSONObject checkpoint = new JSONObject(prior);
            checkpoint.put("phase", "terminal").put("revision", Math.addExact(checkpoint.getLong("revision"), 1L))
                    .put("last_verified_receipt_sha256", joinedDigest);
            journal.persist(checkpoint.toString());
        }
        processFence.afterVerifiedWholeProductCleanup(checkpointSnapshot(), evidenceSnapshot(), joinedDigest);
        proof.put("app_process_fence_cleanup", "terminal").put("whole_app_physical_cleanup", "terminal").put("status", "terminal");
        platform = null; resources = null; runtimeConfigSha256 = null; nativeExecutorGeneration = 0L;
        nativeAppRecordSha256 = null; enrollmentRecordSha256 = null; startIntent.afterVerifiedCleanup();
        ownNoMediaStopTarget = null;
        synchronized (attachmentGate) { attachmentGeneration = 0L; displayDetaching = false; phase.set(Phase.NEW); }
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
