package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One app-lifetime, serial, asynchronous condition-audio owner. The future Android packaged
 * provider supplies a MediaPlayer-style backend; UI, OpenXR, and BLE callers only submit typed
 * commands and consume receipts.
 */
final class ConditionAudioRuntime implements AutoCloseable {
    private static volatile ConditionAudioRuntime appLifetime;
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(0L);

    interface MediaBackendFactory {
        MediaBackend create(ConditionAudioContract.Provider provider, MediaBackend.Listener listener)
            throws Exception;
    }

    /**
     * Optional production extension: startup prepares every build-verified packaged track before
     * a session can select one. Implementations must report from worker/platform callbacks only.
     */
    interface StartupPreloader extends MediaBackendFactory {
        default void disableReplacement() {}
        default void closePreloads() throws Exception {}
        interface Listener {
            void onTrackPending(ConditionAudioContract.Provider provider);
            void onTrackReady(ConditionAudioContract.Provider provider);
            void onTrackFailed(ConditionAudioContract.Provider provider, String reason);
        }

        void preload(ConditionAudioContract.Provider[] providers, Listener listener) throws Exception;
    }

    interface MediaBackend {
        interface Listener {
            void onPrepared();
            void onActualStart(long positionMs);
            void onProgress(long positionMs);
            void onNaturalEnd(long positionMs);
            void onError(String reason);
        }

        void prepare(boolean looping) throws Exception;
        void start() throws Exception;
        /** Pause must retain the prepared player and return its actual media position. */
        long pause() throws Exception;
        void stop() throws Exception;
        void release();
    }

    private final Object authorityLock = new Object();
    private final ExecutorService owner;
    private final ConditionAudioContract.TrustedPackagedInventory inventory;
    private final MediaBackendFactory backendFactory;
    private final ConditionAudioContract.ReceiptSink receiptSink;
    private final Map<String, OperationAdmission> admittedOperations =
        new HashMap<String, OperationAdmission>();
    private final Map<Long, SessionIdentity> sessionIdentities =
        new HashMap<Long, SessionIdentity>();
    private final Map<String, TrackReadiness> trackReadiness =
        new HashMap<String, TrackReadiness>();
    private final StartupPreloader startupPreloader;

    private ConditionAudioContract.Phase phase;
    private long sessionGeneration;
    private long lifecycleEpoch;
    private long receiptRevision;
    private long positionMs;
    private String conditionId = "";
    private String activeOperationId = "";
    private String reason;
    private ConditionAudioContract.Provider provider;
    private MediaBackend backend;
    private boolean closed;
    private boolean pausedAfterNaturalEnd;

    private ConditionAudioRuntime(
        ConditionAudioContract.TrustedPackagedInventory inventory,
        MediaBackendFactory backendFactory,
        ConditionAudioContract.ReceiptSink receiptSink
    ) {
        this.inventory = inventory == null
            ? ConditionAudioContract.TrustedPackagedInventory.unavailable("inventory-missing")
            : inventory;
        this.backendFactory = backendFactory;
        this.startupPreloader = backendFactory instanceof StartupPreloader
            ? (StartupPreloader) backendFactory : null;
        this.receiptSink = receiptSink;
        this.owner = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                return new Thread(
                    runnable,
                    "rq-condition-audio-owner-" + OWNER_SEQUENCE.incrementAndGet()
                );
            }
        });
        this.phase = this.inventory.available
            ? ConditionAudioContract.Phase.IDLE : ConditionAudioContract.Phase.UNAVAILABLE;
        this.reason = this.inventory.available ? "ready" : this.inventory.reason;
        if (startupPreloader != null && this.inventory.available) {
            for (ConditionAudioContract.Provider entry : this.inventory.providersInPackagedOrder()) {
                trackReadiness.put(entry.conditionId, TrackReadiness.pending());
            }
            scheduleStartupPreload();
        }
    }

    static ConditionAudioRuntime installAppLifetime(
        ConditionAudioContract.TrustedPackagedInventory inventory,
        MediaBackendFactory backendFactory,
        ConditionAudioContract.ReceiptSink receiptSink
    ) {
        ConditionAudioRuntime current = appLifetime;
        if (current != null) return current;
        synchronized (ConditionAudioRuntime.class) {
            if (appLifetime == null) {
                appLifetime = new ConditionAudioRuntime(inventory, backendFactory, receiptSink);
            }
            return appLifetime;
        }
    }

    static ConditionAudioRuntime createForTest(
        ConditionAudioContract.TrustedPackagedInventory inventory,
        MediaBackendFactory backendFactory,
        ConditionAudioContract.ReceiptSink receiptSink
    ) {
        return new ConditionAudioRuntime(inventory, backendFactory, receiptSink);
    }

    /** Read-only test observation; it cannot release or replace an outstanding owner. */
    static boolean appLifetimeReleasedForTest() {
        return appLifetime == null;
    }

    ConditionAudioContract.Submission submit(final ConditionAudioContract.Command command) {
        if (command == null || command.kind == null || command.sessionGeneration <= 0L
                || command.operationId.isEmpty()) {
            return rejected(command, "command-invalid");
        }
        final String fingerprint = fingerprint(command);
        final long admittedEpoch;
        final ReceiptContext admittedReceipt;
        synchronized (authorityLock) {
            if (closed) return rejected(command, "owner-closed");
            OperationAdmission previous = admittedOperations.get(command.operationId);
            if (previous != null) {
                if (previous.fingerprint.equals(fingerprint)) {
                    return new ConditionAudioContract.Submission(
                        previous.accepted,
                        previous.accepted && isPending(phase),
                        true,
                        previous.accepted ? "duplicate-idempotent" : previous.reason,
                        command.sessionGeneration, command.operationId
                    );
                }
                return rejected(command, "operation-id-conflict");
            }
            String rejection = admissionRejection(command);
            if (!rejection.isEmpty()) {
                admittedOperations.put(
                    command.operationId,
                    new OperationAdmission(fingerprint, false, rejection)
                );
                emitRejectedAsync(receiptForCommandLocked(
                    ConditionAudioContract.Event.REJECTED,
                    command,
                    rejection
                ));
                return rejected(command, rejection);
            }
            admittedOperations.put(
                command.operationId,
                new OperationAdmission(fingerprint, true, "accepted-pending")
            );
            if (command.kind == ConditionAudioContract.CommandKind.PREPARE) {
                sessionGeneration = command.sessionGeneration;
                conditionId = command.conditionId;
                provider = inventory.providerFor(conditionId);
                sessionIdentities.put(
                    command.sessionGeneration,
                    new SessionIdentity(conditionId, provider == null ? "" : provider.sourceSha256)
                );
                activeOperationId = command.operationId;
                phase = ConditionAudioContract.Phase.PREPARING;
                pausedAfterNaturalEnd = false;
                positionMs = 0L;
                reason = "prepare-pending";
                lifecycleEpoch += 1L;
            } else if (command.kind == ConditionAudioContract.CommandKind.START) {
                activeOperationId = command.operationId;
                phase = ConditionAudioContract.Phase.STARTING;
                reason = "start-pending";
            } else if (command.kind == ConditionAudioContract.CommandKind.PAUSE) {
                pausedAfterNaturalEnd = phase == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END;
                activeOperationId = command.operationId;
                phase = ConditionAudioContract.Phase.PAUSING;
                reason = "pause-pending";
            } else if (command.kind == ConditionAudioContract.CommandKind.RESUME) {
                activeOperationId = command.operationId;
                phase = ConditionAudioContract.Phase.RESUMING;
                reason = "resume-pending";
            } else if (command.kind == ConditionAudioContract.CommandKind.STOP) {
                activeOperationId = command.operationId;
                phase = ConditionAudioContract.Phase.STOPPING;
                reason = "stop-pending";
                lifecycleEpoch += 1L;
            }
            admittedEpoch = lifecycleEpoch;
            admittedReceipt = receiptForCommandLocked(
                acceptedEvent(command.kind),
                command,
                acceptedReason(command.kind)
            );
        }
        try {
            owner.execute(new Runnable() {
                @Override public void run() { apply(command, admittedEpoch, admittedReceipt); }
            });
        } catch (RejectedExecutionException error) {
            synchronized (authorityLock) {
                phase = ConditionAudioContract.Phase.ERROR;
                reason = "owner-executor-unavailable";
            }
            return rejected(command, "owner-executor-unavailable");
        }
        return new ConditionAudioContract.Submission(
            true, true, false, "accepted-pending", command.sessionGeneration, command.operationId
        );
    }

    ConditionAudioContract.Snapshot snapshot() {
        synchronized (authorityLock) {
            return new ConditionAudioContract.Snapshot(
                phase, sessionGeneration, receiptRevision, conditionId,
                activeOperationId, positionMs, reason
            );
        }
    }

    ConditionAudioContract.TrackReadiness trackReadiness(String requestedConditionId) {
        synchronized (authorityLock) {
            ConditionAudioContract.Provider selected = inventory.providerFor(requestedConditionId);
            if (!inventory.available || selected == null) {
                return new ConditionAudioContract.TrackReadiness(
                    false, requestedConditionId, ConditionAudioContract.TrackState.UNAVAILABLE,
                    inventory.available ? "condition-provider-unavailable" : inventory.reason
                );
            }
            if (startupPreloader == null) {
                return new ConditionAudioContract.TrackReadiness(
                    true, selected.conditionId, ConditionAudioContract.TrackState.READY, "track-ready"
                );
            }
            TrackReadiness local = trackReadiness.get(selected.conditionId);
            if (local == null || local.pending) {
                return new ConditionAudioContract.TrackReadiness(
                    true, selected.conditionId, ConditionAudioContract.TrackState.PENDING,
                    "audio-track-not-ready"
                );
            }
            return new ConditionAudioContract.TrackReadiness(
                true, selected.conditionId,
                local.ready ? ConditionAudioContract.TrackState.READY
                    : ConditionAudioContract.TrackState.FAILED,
                local.ready ? "track-ready" : "audio-track-preload-failed"
            );
        }
    }

    @Override
    public void close() {
        synchronized (authorityLock) {
            if (closed) return;
            closed = true;
            lifecycleEpoch += 1L;
        }
        try {
            owner.execute(new Runnable() {
                @Override public void run() {
                    final MediaBackend captured;
                    synchronized (authorityLock) {
                        // Capture on the owner thread after all older queued work. A stale prepare
                        // whose first cleanup failed can therefore publish its obligation here.
                        captured = backend;
                    }
                    boolean preloadsClean = true;
                    try {
                        if (startupPreloader != null) startupPreloader.closePreloads();
                    } catch (Exception error) { preloadsClean = false; }
                    CleanupResult cleanup = release(captured, true);
                    if (!preloadsClean) cleanup = CleanupResult.from(false, true);
                    synchronized (authorityLock) {
                        if (cleanup.success) {
                            if (backend == captured) backend = null;
                            reason = "closed";
                        } else {
                            // Retain the exact backend obligation and singleton barrier. A new
                            // owner must never overlap cleanup that was not confirmed.
                            backend = captured;
                            reason = cleanup.reason;
                        }
                    }
                    if (cleanup.success) {
                        synchronized (ConditionAudioRuntime.class) {
                            if (appLifetime == ConditionAudioRuntime.this) appLifetime = null;
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Fail closed: retain the singleton barrier rather than overlap an unconfirmed owner.
            synchronized (authorityLock) {
                reason = "close-cleanup-not-scheduled";
            }
        }
        owner.shutdown();
    }

    boolean awaitClosed(long timeoutMs) throws InterruptedException {
        if (!owner.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return false;
        synchronized (authorityLock) { return closed && "closed".equals(reason); }
    }

    private String admissionRejection(ConditionAudioContract.Command command) {
        if (!inventory.available || backendFactory == null) return "inventory-unavailable";
        if (command.kind == ConditionAudioContract.CommandKind.PREPARE) {
            if (command.sessionGeneration <= sessionGeneration) return "stale-generation";
            if (phase != ConditionAudioContract.Phase.IDLE
                    && phase != ConditionAudioContract.Phase.STOPPED) {
                return "audio-session-not-stopped";
            }
            ConditionAudioContract.Provider selected = inventory.providerFor(command.conditionId);
            if (selected == null) return "condition-provider-unavailable";
            if (startupPreloader == null) return "";
            TrackReadiness readiness = trackReadiness.get(selected.conditionId);
            if (readiness == null || readiness.pending) return "audio-track-not-ready";
            return readiness.ready ? "" : "audio-track-preload-failed";
        }
        if (command.sessionGeneration != sessionGeneration) return "stale-generation";
        if (command.kind == ConditionAudioContract.CommandKind.START) {
            if (phase == ConditionAudioContract.Phase.PREPARED && backend != null) return "";
            return startupPreloader != null && phase == ConditionAudioContract.Phase.PREPARING
                ? "audio-track-not-ready" : "audio-not-prepared";
        }
        if (command.kind == ConditionAudioContract.CommandKind.PAUSE) {
            return phase == ConditionAudioContract.Phase.PLAYING
                || phase == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END
                ? "" : "audio-not-playing";
        }
        if (command.kind == ConditionAudioContract.CommandKind.RESUME) {
            return phase == ConditionAudioContract.Phase.PAUSED ? "" : "audio-not-paused";
        }
        if (command.kind == ConditionAudioContract.CommandKind.THRESHOLD_REACHED) {
            return phase == ConditionAudioContract.Phase.PLAYING
                    || phase == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END
                ? "" : "session-audio-not-active";
        }
        if (command.kind == ConditionAudioContract.CommandKind.STOP) {
            if (command.stopReason == null) return "stop-reason-missing";
            return phase == ConditionAudioContract.Phase.PREPARING
                    || phase == ConditionAudioContract.Phase.PREPARED
                    || phase == ConditionAudioContract.Phase.STARTING
                    || phase == ConditionAudioContract.Phase.PLAYING
                    || phase == ConditionAudioContract.Phase.PAUSING
                    || phase == ConditionAudioContract.Phase.PAUSED
                    || phase == ConditionAudioContract.Phase.RESUMING
                    || phase == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END
                    || phase == ConditionAudioContract.Phase.ERROR
                ? "" : "audio-not-stoppable";
        }
        return "command-unsupported";
    }

    private void scheduleStartupPreload() {
        try {
            owner.execute(new Runnable() {
                @Override public void run() {
                    try {
                        startupPreloader.preload(
                            inventory.providersInPackagedOrder(),
                            new StartupPreloader.Listener() {
                                @Override public void onTrackPending(
                                    ConditionAudioContract.Provider provider
                                ) {
                                    publishTrackPending(provider);
                                }

                                @Override public void onTrackReady(
                                    ConditionAudioContract.Provider provider
                                ) {
                                    publishTrackReadiness(provider, true, "track-ready");
                                }

                                @Override public void onTrackFailed(
                                    ConditionAudioContract.Provider provider, String failureReason
                                ) {
                                    publishTrackReadiness(provider, false, failureReason);
                                }
                            }
                        );
                    } catch (Exception error) {
                        for (ConditionAudioContract.Provider entry
                                : inventory.providersInPackagedOrder()) {
                            publishTrackReadiness(entry, false, "track-preload-failed");
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            for (ConditionAudioContract.Provider entry : inventory.providersInPackagedOrder()) {
                trackReadiness.put(entry.conditionId, TrackReadiness.failed("track-preload-unscheduled"));
            }
        }
    }

    private void publishTrackReadiness(
        final ConditionAudioContract.Provider entry,
        final boolean ready,
        final String failureReason
    ) {
        if (entry == null) return;
        Runnable apply = new Runnable() {
            @Override public void run() {
                synchronized (authorityLock) {
                    if (closed || inventory.providerFor(entry.conditionId) != entry) return;
                    trackReadiness.put(entry.conditionId, ready
                        ? TrackReadiness.ready()
                        : TrackReadiness.failed(failureReason));
                }
            }
        };
        try {
            owner.execute(apply);
        } catch (RejectedExecutionException ignored) {
            synchronized (authorityLock) {
                if (!closed) {
                    trackReadiness.put(entry.conditionId,
                        TrackReadiness.failed("track-preload-unscheduled"));
                }
            }
        }
    }

    private void publishTrackPending(final ConditionAudioContract.Provider entry) {
        if (entry == null) return;
        Runnable apply = new Runnable() {
            @Override public void run() {
                synchronized (authorityLock) {
                    if (!closed && inventory.providerFor(entry.conditionId) == entry) {
                        trackReadiness.put(entry.conditionId, TrackReadiness.pending());
                    }
                }
            }
        };
        try {
            owner.execute(apply);
        } catch (RejectedExecutionException ignored) {
            synchronized (authorityLock) {
                if (!closed) trackReadiness.put(entry.conditionId, TrackReadiness.pending());
            }
        }
    }

    private void apply(
        ConditionAudioContract.Command command,
        long admittedEpoch,
        ReceiptContext admittedReceipt
    ) {
        if (command.kind == ConditionAudioContract.CommandKind.PREPARE) {
            applyPrepare(command, admittedEpoch, admittedReceipt);
        } else if (command.kind == ConditionAudioContract.CommandKind.START) {
            applyStart(command, admittedEpoch, admittedReceipt);
        } else if (command.kind == ConditionAudioContract.CommandKind.PAUSE
                || command.kind == ConditionAudioContract.CommandKind.RESUME) {
            applyPauseResume(command, admittedEpoch, admittedReceipt);
        } else if (command.kind == ConditionAudioContract.CommandKind.THRESHOLD_REACHED) {
            emit(admittedReceipt);
        } else if (command.kind == ConditionAudioContract.CommandKind.STOP) {
            if (startupPreloader != null
                    && command.stopReason == ConditionAudioContract.StopReason.SAVE_AND_EXIT)
                startupPreloader.disableReplacement();
            applyStop(command, admittedReceipt);
        }
    }

    private void applyPauseResume(ConditionAudioContract.Command command, long epoch, ReceiptContext accepted) {
        final MediaBackend captured;
        final boolean silent;
        synchronized (authorityLock) {
            if (!current(command.sessionGeneration, epoch)) return;
            captured = backend;
            silent = pausedAfterNaturalEnd;
        }
        emit(accepted);
        try {
            if (command.kind == ConditionAudioContract.CommandKind.RESUME && !silent) {
                captured.start(); // MediaPlayer.start resumes its retained position; no seek/reprepare.
                return; // Effective receipt is emitted by onActualStart.
            }
            long pausedPosition = silent ? positionMs : captured.pause();
            final ReceiptContext receipt;
            synchronized (authorityLock) {
                if (!current(command.sessionGeneration, epoch)
                        || phase == ConditionAudioContract.Phase.STOPPING) return;
                positionMs = Math.max(positionMs, pausedPosition);
                boolean pause = command.kind == ConditionAudioContract.CommandKind.PAUSE;
                phase = pause ? ConditionAudioContract.Phase.PAUSED
                    : ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END;
                reason = pause ? "paused" : "resumed-after-natural-end-silence";
                receipt = receiptLocked(pause ? ConditionAudioContract.Event.PAUSED
                    : ConditionAudioContract.Event.RESUMED, command.sessionGeneration,
                    command.operationId, reason, null);
            }
            emit(receipt);
        } catch (Exception error) {
            publishError(command.sessionGeneration, command.operationId, epoch, "media-pause-resume-failed");
        }
    }

    private void applyPrepare(
        final ConditionAudioContract.Command command,
        final long admittedEpoch,
        ReceiptContext admittedReceipt
    ) {
        emit(admittedReceipt);
        try {
            final MediaBackend candidate = backendFactory.create(
                provider,
                listener(command.sessionGeneration, command.operationId, admittedEpoch)
            );
            boolean rejectedAsStale;
            synchronized (authorityLock) {
                rejectedAsStale = !current(command.sessionGeneration, admittedEpoch)
                    || phase != ConditionAudioContract.Phase.PREPARING;
                if (!rejectedAsStale) backend = candidate;
            }
            if (rejectedAsStale) {
                CleanupResult staleCleanup = release(candidate, false);
                if (!staleCleanup.success) {
                    synchronized (authorityLock) {
                        if (backend == null) backend = candidate;
                        reason = staleCleanup.reason;
                    }
                }
                return;
            }
            candidate.prepare(false);
        } catch (Exception error) {
            publishError(command.sessionGeneration, command.operationId, admittedEpoch, "prepare-error");
        }
    }

    private void applyStart(
        ConditionAudioContract.Command command,
        long admittedEpoch,
        ReceiptContext admittedReceipt
    ) {
        emit(admittedReceipt);
        MediaBackend currentBackend;
        synchronized (authorityLock) {
            if (!current(command.sessionGeneration, admittedEpoch)
                    || phase != ConditionAudioContract.Phase.STARTING) return;
            currentBackend = backend;
        }
        try {
            currentBackend.start();
        } catch (Exception error) {
            publishError(command.sessionGeneration, command.operationId, admittedEpoch, "start-error");
        }
    }

    private void applyStop(
        ConditionAudioContract.Command command,
        ReceiptContext admittedReceipt
    ) {
        emit(admittedReceipt);
        MediaBackend captured;
        synchronized (authorityLock) {
            captured = backend;
        }
        CleanupResult cleanup = release(captured, true);
        final ReceiptContext terminalReceipt;
        synchronized (authorityLock) {
            if (command.sessionGeneration != sessionGeneration
                    || phase != ConditionAudioContract.Phase.STOPPING) return;
            if (cleanup.success) {
                if (backend == captured) backend = null;
                phase = ConditionAudioContract.Phase.STOPPED;
                reason = "stopped";
                positionMs = 0L;
                terminalReceipt = receiptForCommandLocked(
                    ConditionAudioContract.Event.STOPPED,
                    command,
                    "stopped"
                );
            } else {
                backend = captured;
                phase = ConditionAudioContract.Phase.ERROR;
                reason = cleanup.reason;
                terminalReceipt = receiptForCommandLocked(
                    ConditionAudioContract.Event.CLEANUP_FAILED,
                    command,
                    cleanup.reason
                );
            }
        }
        emit(terminalReceipt);
    }

    private MediaBackend.Listener listener(
        final long generation,
        final String prepareOperationId,
        final long epoch
    ) {
        return new MediaBackend.Listener() {
            @Override public void onPrepared() {
                enqueueCallback(new Runnable() {
                    @Override public void run() {
                        final ReceiptContext receipt;
                        synchronized (authorityLock) {
                            if (!current(generation, epoch)
                                    || phase != ConditionAudioContract.Phase.PREPARING) return;
                            phase = ConditionAudioContract.Phase.PREPARED;
                            reason = "prepared";
                            receipt = receiptLocked(
                                ConditionAudioContract.Event.PREPARED,
                                generation,
                                prepareOperationId,
                                "prepared",
                                null
                            );
                        }
                        emit(receipt);
                    }
                });
            }

            @Override public void onActualStart(final long observedPositionMs) {
                enqueueCallback(new Runnable() {
                    @Override public void run() {
                        final ReceiptContext receipt;
                        synchronized (authorityLock) {
                            if (!current(generation, epoch)
                                    || (phase != ConditionAudioContract.Phase.STARTING
                                        && phase != ConditionAudioContract.Phase.RESUMING)) return;
                            boolean resuming = phase == ConditionAudioContract.Phase.RESUMING;
                            phase = ConditionAudioContract.Phase.PLAYING;
                            reason = "playing";
                            positionMs = Math.max(resuming ? positionMs : 0L, observedPositionMs);
                            receipt = receiptLocked(
                                resuming ? ConditionAudioContract.Event.RESUMED : ConditionAudioContract.Event.ACTUAL_START,
                                generation,
                                activeOperationId,
                                resuming ? "resumed" : "actual-start",
                                null
                            );
                        }
                        emit(receipt);
                    }
                });
            }

            @Override public void onProgress(final long observedPositionMs) {
                enqueueCallback(new Runnable() {
                    @Override public void run() {
                        final ReceiptContext receipt;
                        synchronized (authorityLock) {
                            if (!current(generation, epoch)
                                    || phase != ConditionAudioContract.Phase.PLAYING) return;
                            positionMs = Math.max(positionMs, observedPositionMs);
                            receipt = receiptLocked(
                                ConditionAudioContract.Event.PROGRESS,
                                generation,
                                activeOperationId,
                                "progress",
                                null
                            );
                        }
                        emit(receipt);
                    }
                });
            }

            @Override public void onNaturalEnd(final long observedPositionMs) {
                enqueueCallback(new Runnable() {
                    @Override public void run() {
                        final MediaBackend ended;
                        synchronized (authorityLock) {
                            if (!current(generation, epoch)
                                    || (phase != ConditionAudioContract.Phase.PLAYING
                                        && phase != ConditionAudioContract.Phase.PAUSED
                                        && phase != ConditionAudioContract.Phase.PAUSING)) return;
                            boolean paused = phase != ConditionAudioContract.Phase.PLAYING;
                            pausedAfterNaturalEnd = paused;
                            phase = paused ? ConditionAudioContract.Phase.PAUSED
                                : ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END;
                            reason = "natural-end-silence";
                            positionMs = Math.max(positionMs, observedPositionMs);
                            ended = backend;
                            lifecycleEpoch += 1L;
                        }
                        CleanupResult cleanup = release(ended, false);
                        final ReceiptContext receipt;
                        synchronized (authorityLock) {
                            if (cleanup.success) {
                                if (backend == ended) backend = null;
                                receipt = (phase == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END
                                        || phase == ConditionAudioContract.Phase.PAUSED)
                                    ? receiptLocked(
                                        ConditionAudioContract.Event.NATURAL_END,
                                        generation,
                                        activeOperationId,
                                        "natural-end-silence-session-continues",
                                        null
                                    ) : null;
                            } else {
                                backend = ended;
                                if (phase == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END
                                        || phase == ConditionAudioContract.Phase.PAUSED) {
                                    phase = ConditionAudioContract.Phase.ERROR;
                                    reason = cleanup.reason;
                                    receipt = receiptLocked(
                                        ConditionAudioContract.Event.CLEANUP_FAILED,
                                        generation,
                                        activeOperationId,
                                        cleanup.reason,
                                        null
                                    );
                                } else {
                                    receipt = null;
                                }
                            }
                        }
                        if (receipt != null) emit(receipt);
                    }
                });
            }

            @Override public void onError(final String callbackReason) {
                enqueueCallback(new Runnable() {
                    @Override public void run() {
                        publishError(generation, prepareOperationId, epoch,
                            callbackReason == null ? "media-error" : callbackReason);
                    }
                });
            }
        };
    }

    private void publishError(long generation, String operationId, long epoch, String errorReason) {
        final MediaBackend failed;
        final String boundOperationId;
        synchronized (authorityLock) {
            if (!current(generation, epoch) || phase == ConditionAudioContract.Phase.STOPPING) return;
            phase = ConditionAudioContract.Phase.ERROR;
            reason = errorReason;
            failed = backend;
            boundOperationId = activeOperationId.isEmpty() ? operationId : activeOperationId;
            lifecycleEpoch += 1L;
        }
        CleanupResult cleanup = release(failed, false);
        final ReceiptContext receipt;
        synchronized (authorityLock) {
            if (cleanup.success) {
                if (backend == failed) backend = null;
                receipt = phase == ConditionAudioContract.Phase.ERROR
                    ? receiptLocked(
                        ConditionAudioContract.Event.ERROR,
                        generation,
                        boundOperationId,
                        errorReason,
                        null
                    ) : null;
            } else {
                backend = failed;
                if (phase == ConditionAudioContract.Phase.ERROR) {
                    reason = cleanup.reason;
                    receipt = receiptLocked(
                        ConditionAudioContract.Event.CLEANUP_FAILED,
                        generation,
                        boundOperationId,
                        cleanup.reason,
                        null
                    );
                } else {
                    receipt = null;
                }
            }
        }
        if (receipt == null) return;
        emit(receipt);
    }

    private void enqueueCallback(Runnable callback) {
        try {
            owner.execute(callback);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void emitRejectedAsync(final ReceiptContext receipt) {
        try {
            owner.execute(new Runnable() {
                @Override public void run() {
                    emit(receipt);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void emit(ReceiptContext context) {
        final ConditionAudioContract.Receipt receipt;
        synchronized (authorityLock) {
            receiptRevision += 1L;
            receipt = new ConditionAudioContract.Receipt(
                context.event,
                receiptRevision,
                context.sessionGeneration,
                context.operationId,
                context.conditionId,
                context.sourceSha256,
                context.positionMs,
                context.reason,
                context.stopReason
            );
        }
        if (receiptSink != null) {
            try { receiptSink.onReceipt(receipt); } catch (RuntimeException ignored) {}
        }
    }

    private boolean current(long generation, long epoch) {
        return !closed && generation == sessionGeneration && epoch == lifecycleEpoch;
    }

    private ReceiptContext receiptForCommandLocked(
        ConditionAudioContract.Event event,
        ConditionAudioContract.Command command,
        String receiptReason
    ) {
        String boundCondition = "";
        String boundSourceSha256 = "";
        long boundPositionMs = 0L;
        if (command.kind == ConditionAudioContract.CommandKind.PREPARE) {
            boundCondition = command.conditionId;
            ConditionAudioContract.Provider commandProvider = inventory.providerFor(boundCondition);
            boundSourceSha256 = commandProvider == null ? "" : commandProvider.sourceSha256;
        } else if (command.sessionGeneration == sessionGeneration) {
            boundCondition = conditionId;
            boundSourceSha256 = provider == null ? "" : provider.sourceSha256;
            boundPositionMs = positionMs;
        } else {
            SessionIdentity identity = sessionIdentities.get(command.sessionGeneration);
            if (identity != null) {
                boundCondition = identity.conditionId;
                boundSourceSha256 = identity.sourceSha256;
            }
        }
        return new ReceiptContext(
            event,
            command.sessionGeneration,
            command.operationId,
            boundCondition,
            boundSourceSha256,
            boundPositionMs,
            receiptReason,
            command.stopReason
        );
    }

    private ReceiptContext receiptLocked(
        ConditionAudioContract.Event event,
        long generation,
        String operationId,
        String receiptReason,
        ConditionAudioContract.StopReason stopReason
    ) {
        SessionIdentity identity = sessionIdentities.get(generation);
        String boundCondition = generation == sessionGeneration
            ? conditionId : identity == null ? "" : identity.conditionId;
        String boundSourceSha256 = generation == sessionGeneration
            ? provider == null ? "" : provider.sourceSha256
            : identity == null ? "" : identity.sourceSha256;
        long boundPositionMs = generation == sessionGeneration ? positionMs : 0L;
        return new ReceiptContext(
            event,
            generation,
            operationId,
            boundCondition,
            boundSourceSha256,
            boundPositionMs,
            receiptReason,
            stopReason
        );
    }

    private CleanupResult release(MediaBackend target, boolean stopFirst) {
        if (target == null) return CleanupResult.success();
        boolean stopFailed = false;
        boolean releaseFailed = false;
        if (stopFirst) {
            try { target.stop(); } catch (Exception error) { stopFailed = true; }
        }
        try { target.release(); } catch (RuntimeException error) { releaseFailed = true; }
        return CleanupResult.from(stopFailed, releaseFailed);
    }

    private static boolean isPending(ConditionAudioContract.Phase value) {
        return value == ConditionAudioContract.Phase.PREPARING
            || value == ConditionAudioContract.Phase.PAUSING
            || value == ConditionAudioContract.Phase.RESUMING
            || value == ConditionAudioContract.Phase.STARTING
            || value == ConditionAudioContract.Phase.STOPPING;
    }

    private static String fingerprint(ConditionAudioContract.Command command) {
        return command.kind + "|" + command.sessionGeneration + "|" + command.conditionId
            + "|" + (command.stopReason == null ? "" : command.stopReason.name());
    }

    private static ConditionAudioContract.Event acceptedEvent(
        ConditionAudioContract.CommandKind kind
    ) {
        if (kind == ConditionAudioContract.CommandKind.PREPARE) {
            return ConditionAudioContract.Event.PREPARE_ACCEPTED;
        }
        if (kind == ConditionAudioContract.CommandKind.START) {
            return ConditionAudioContract.Event.START_ACCEPTED;
        }
        if (kind == ConditionAudioContract.CommandKind.PAUSE) return ConditionAudioContract.Event.PAUSE_ACCEPTED;
        if (kind == ConditionAudioContract.CommandKind.RESUME) return ConditionAudioContract.Event.RESUME_ACCEPTED;
        if (kind == ConditionAudioContract.CommandKind.STOP) {
            return ConditionAudioContract.Event.STOP_ACCEPTED;
        }
        return ConditionAudioContract.Event.THRESHOLD_CONTINUES;
    }

    private static String acceptedReason(ConditionAudioContract.CommandKind kind) {
        if (kind == ConditionAudioContract.CommandKind.PREPARE) return "prepare-accepted";
        if (kind == ConditionAudioContract.CommandKind.START) return "start-accepted";
        if (kind == ConditionAudioContract.CommandKind.PAUSE) return "pause-accepted";
        if (kind == ConditionAudioContract.CommandKind.RESUME) return "resume-accepted";
        if (kind == ConditionAudioContract.CommandKind.STOP) return "stop-accepted";
        return "threshold-does-not-stop-audio";
    }

    private static ConditionAudioContract.Submission rejected(
        ConditionAudioContract.Command command,
        String reason
    ) {
        return new ConditionAudioContract.Submission(
            false,
            false,
            false,
            reason,
            command == null ? 0L : command.sessionGeneration,
            command == null ? "" : command.operationId
        );
    }

    private static final class OperationAdmission {
        final String fingerprint;
        final boolean accepted;
        final String reason;

        OperationAdmission(String fingerprint, boolean accepted, String reason) {
            this.fingerprint = fingerprint;
            this.accepted = accepted;
            this.reason = reason;
        }
    }

    private static final class SessionIdentity {
        final String conditionId;
        final String sourceSha256;

        SessionIdentity(String conditionId, String sourceSha256) {
            this.conditionId = conditionId;
            this.sourceSha256 = sourceSha256;
        }
    }

    private static final class TrackReadiness {
        final boolean pending;
        final boolean ready;
        final String reason;

        private TrackReadiness(boolean pending, boolean ready, String reason) {
            this.pending = pending;
            this.ready = ready;
            this.reason = reason;
        }

        static TrackReadiness pending() { return new TrackReadiness(true, false, "track-pending"); }
        static TrackReadiness ready() { return new TrackReadiness(false, true, "track-ready"); }
        static TrackReadiness failed(String reason) {
            return new TrackReadiness(false, false, reason == null ? "track-preload-failed" : reason);
        }
    }

    private static final class ReceiptContext {
        final ConditionAudioContract.Event event;
        final long sessionGeneration;
        final String operationId;
        final String conditionId;
        final String sourceSha256;
        final long positionMs;
        final String reason;
        final ConditionAudioContract.StopReason stopReason;

        ReceiptContext(
            ConditionAudioContract.Event event,
            long sessionGeneration,
            String operationId,
            String conditionId,
            String sourceSha256,
            long positionMs,
            String reason,
            ConditionAudioContract.StopReason stopReason
        ) {
            this.event = event;
            this.sessionGeneration = sessionGeneration;
            this.operationId = operationId;
            this.conditionId = conditionId;
            this.sourceSha256 = sourceSha256;
            this.positionMs = positionMs;
            this.reason = reason;
            this.stopReason = stopReason;
        }
    }

    private static final class CleanupResult {
        final boolean success;
        final String reason;

        private CleanupResult(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }

        static CleanupResult success() {
            return new CleanupResult(true, "cleanup-confirmed");
        }

        static CleanupResult from(boolean stopFailed, boolean releaseFailed) {
            if (!stopFailed && !releaseFailed) return success();
            if (stopFailed && releaseFailed) {
                return new CleanupResult(false, "cleanup-stop-and-release-failed");
            }
            return new CleanupResult(
                false,
                stopFailed ? "cleanup-stop-failed" : "cleanup-release-failed"
            );
        }
    }
}
