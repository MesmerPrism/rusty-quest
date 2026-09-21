package io.github.mesmerprism.rustyquest.native_renderer;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-lifetime Android shell owner for the low-rate experiment-session JNI surface.
 *
 * <p>Every codec and JNI operation runs on one serial worker. Queued work retains only a weak
 * UI sink, so Activity recreation or destruction cannot turn a response into a callback on a
 * dead Activity. The native session runtime remains the sole state and durability authority.</p>
 */
final class ExperimentSessionAndroidShell {
    private static final long POLL_INTERVAL_MS = 250L;
    private static final long POLL_TIMEOUT_MS = 20_000L;
    private static final int MAX_TERMINAL_REBASES = 2;
    private static final AtomicLong WORKER_SEQUENCE = new AtomicLong();
    private static ExperimentSessionAndroidShell processInstance;

    interface NativeBridge {
        String initialize(String appPrivateFilesRoot);
        String apply(String commandJson);
        boolean requestAudioStop(long sessionGeneration, String operationId);
        String audioShutdownStatus();
        boolean cleanupAfterAcknowledgement();
    }

    interface Codec {
        String statusCommand();
        String command(
            ExperimentSessionPanelCoordinator.NativeCommand command,
            boolean start,
            long elapsedRealtimeNanos,
            long utcNanos
        );
        SessionReadback parse(
            String responseJson,
            ExperimentSessionPanelCoordinator.NativeCommand expected,
            boolean start
        );
    }

    interface Clock {
        long elapsedRealtimeMillis();
        long elapsedRealtimeNanos();
        long utcNanos();
    }

    interface UiDispatcher {
        void post(Runnable runnable);
        boolean isUiThread();
    }

    interface UiSink {
        boolean acceptsExperimentSessionCallbacks(long launchEpoch);
        void onExperimentSessionReadback(SessionReadback readback);
        void onExperimentSessionTerminal(TerminalResult result);
    }

    static final class SessionReadback {
        final ExperimentSessionPanelCoordinator.NativeReceipt receipt;
        final String commandStatus;
        final String initializationStatus;
        final String inventoryStatus;
        final String recordingRootStatus;
        final String shutdownStatus;
        final long shutdownAckRevision;
        final long finalizedSessionGeneration;
        final String finalizedOperationId;
        final String detail;
        final String recordingResult;
        final String recordingError;
        final long runtimeEpoch;
        final String lastOperationStatus;
        final String lastOperationReason;

        SessionReadback(
            ExperimentSessionPanelCoordinator.NativeReceipt receipt,
            String commandStatus,
            String initializationStatus,
            String inventoryStatus,
            String recordingRootStatus,
            String shutdownStatus,
            long shutdownAckRevision,
            long finalizedSessionGeneration,
            String finalizedOperationId,
            String detail, String recordingResult, String recordingError, long runtimeEpoch,
            String lastOperationStatus, String lastOperationReason
        ) {
            this.receipt = receipt;
            this.commandStatus = safe(commandStatus);
            this.initializationStatus = safe(initializationStatus);
            this.inventoryStatus = safe(inventoryStatus);
            this.recordingRootStatus = safe(recordingRootStatus);
            this.shutdownStatus = safe(shutdownStatus);
            this.shutdownAckRevision = Math.max(0L, shutdownAckRevision);
            // Generation zero is the exact idle-session generation. Preserve the native value
            // so malformed negative evidence cannot be normalized into a valid idle receipt.
            this.finalizedSessionGeneration = finalizedSessionGeneration;
            this.finalizedOperationId = safe(finalizedOperationId);
            this.detail = safe(detail);
            this.recordingResult = safe(recordingResult);
            this.recordingError = safe(recordingError);
            this.runtimeEpoch = runtimeEpoch;
            this.lastOperationStatus = safe(lastOperationStatus);
            this.lastOperationReason = safe(lastOperationReason);
        }

        boolean initializationPending() {
            return "initializing".equals(initializationStatus);
        }

        boolean commandPending() {
            return "queued".equals(commandStatus)
                || (receipt != null && "queued".equals(receipt.detail));
        }
    }

    static final class TerminalResult {
        final boolean shouldFinish;
        final boolean saved;
        final long sessionGeneration;
        final String operationId;
        final long shutdownAckRevision;
        final String detail;

        TerminalResult(boolean shouldFinish, boolean saved, long sessionGeneration,
                String operationId, long shutdownAckRevision, String detail) {
            this.shouldFinish = shouldFinish;
            this.saved = saved;
            this.sessionGeneration = sessionGeneration;
            this.operationId = safe(operationId);
            this.shutdownAckRevision = Math.max(0L, shutdownAckRevision);
            this.detail = safe(detail);
        }
    }

    private final NativeBridge nativeBridge;
    private final Codec codec;
    private final Clock clock;
    private final UiDispatcher uiDispatcher;
    private volatile ScheduledExecutorService owner;
    private final AtomicLong terminalOperationSequence = new AtomicLong();
    private final Object admissionLock = new Object();
    private long launchEpoch;
    private boolean terminalAdmitted;
    private long terminalGuardGeneration;
    private long terminalHomeEpisode;
    private String initializedRoot = "";
    private long initializedEpoch;
    private WeakReference<UiSink> attachedSink = new WeakReference<UiSink>(null);
    private final java.util.WeakHashMap<UiSink, Boolean> retiredSinks =
        new java.util.WeakHashMap<UiSink, Boolean>();
    private volatile TerminalResult latestTerminal;
    private volatile boolean cleanupConsumed;
    private boolean terminalTimeoutReported;
    private long terminalInitializationDeadline;
    // A rejected/unknown admission is distinct from an admitted stop awaiting its receipt.
    // Retain its exact command in this continuation; replacement UI or repeat exit can resume it.
    private Runnable terminalRecovery;
    private String terminalCommandJson;
    private int terminalRebases;

    void attach(UiSink sink) {
        synchronized (admissionLock) {
            if (sink == null || retiredSinks.containsKey(sink)) return;
            UiSink previous = attachedSink.get();
            if (previous != null && previous != sink) retiredSinks.put(previous, Boolean.TRUE);
            attachedSink = new WeakReference<UiSink>(sink);
        }
        if (latestTerminal != null) dispatchTerminal(attachedSink, currentLaunchEpoch(), latestTerminal);
        resumePendingTerminal();
    }

    boolean resumePendingTerminal() {
        final Runnable recovery;
        synchronized (admissionLock) {
            if (!terminalAdmitted || cleanupConsumed || terminalRecovery == null) return false;
            recovery = terminalRecovery;
            terminalRecovery = null;
            terminalInitializationDeadline = clock.elapsedRealtimeMillis() + POLL_TIMEOUT_MS;
        }
        owner.execute(recovery);
        return true;
    }

    private void retainTerminalRecovery(Runnable recovery) {
        synchronized (admissionLock) { terminalRecovery = recovery; }
    }

    void detach(UiSink sink) {
        synchronized (admissionLock) {
            retiredSinks.put(sink, Boolean.TRUE);
            if (attachedSink.get() == sink) attachedSink.clear();
        }
    }

    static synchronized ExperimentSessionAndroidShell installProcess(
            NativeBridge nativeBridge, Codec codec, Clock clock, UiDispatcher uiDispatcher) {
        if (processInstance == null) {
            processInstance = new ExperimentSessionAndroidShell(
                nativeBridge, codec, clock, uiDispatcher, newOwner());
        }
        return processInstance;
    }

    static ExperimentSessionAndroidShell createForTest(
            NativeBridge nativeBridge, Codec codec, Clock clock, UiDispatcher uiDispatcher) {
        return new ExperimentSessionAndroidShell(
            nativeBridge, codec, clock, uiDispatcher, newOwner());
    }

    private ExperimentSessionAndroidShell(
            NativeBridge nativeBridge,
            Codec codec,
            Clock clock,
            UiDispatcher uiDispatcher,
            ScheduledExecutorService owner) {
        if (nativeBridge == null || codec == null || clock == null
                || uiDispatcher == null || owner == null) {
            throw new IllegalArgumentException("session shell dependencies are required");
        }
        this.nativeBridge = nativeBridge;
        this.codec = codec;
        this.clock = clock;
        this.uiDispatcher = uiDispatcher;
        this.owner = owner;
    }

    boolean canBeginExplicitLaunchEpoch(long epoch) {
        synchronized (admissionLock) {
            return epoch > launchEpoch && (!terminalAdmitted || owner.isShutdown());
        }
    }

    boolean beginExplicitLaunchEpoch(long admittedLaunchEpoch) {
        synchronized (admissionLock) {
            if (admittedLaunchEpoch <= launchEpoch || (terminalAdmitted && !owner.isShutdown())) {
                return false;
            }
            launchEpoch = admittedLaunchEpoch;
            if (owner.isShutdown()) owner = newOwner();
            terminalAdmitted = false;
            terminalGuardGeneration = 0L;
            terminalHomeEpisode = 0L;
            latestTerminal = null;
            cleanupConsumed = false;
            terminalTimeoutReported = false;
            terminalRecovery = null;
            terminalCommandJson = null;
            terminalRebases = 0;
            return true;
        }
    }

    long currentLaunchEpochForUi() {
        return currentLaunchEpoch();
    }

    void initialize(String appPrivateFilesRoot, UiSink sink) {
        attach(sink);
        if (cleanupConsumed) return;
        String root = safe(appPrivateFilesRoot).trim();
        if (root.isEmpty()) {
            throw new IllegalArgumentException("app-private files root is required");
        }
        final long epoch = currentLaunchEpoch();
        final WeakReference<UiSink> weakSink = new WeakReference<UiSink>(sink);
        owner.execute(new Runnable() {
            @Override public void run() {
                requireWorkerThread();
                if (!isCurrentEpoch(epoch)) return;
                try {
                    SessionReadback readback;
                    if (!root.equals(initializedRoot) || initializedEpoch != epoch) {
                        initializedRoot = root;
                        initializedEpoch = epoch;
                        readback = codec.parse(nativeBridge.initialize(root), null, false);
                    } else {
                        readback = readStatus();
                    }
                    dispatchReadback(weakSink, epoch, readback);
                    if (readback != null && readback.initializationPending()) {
                        scheduleStatusPoll(weakSink, epoch, clock.elapsedRealtimeMillis()
                            + POLL_TIMEOUT_MS, null, false);
                    }
                } catch (Throwable error) {
                    dispatchFailure(weakSink, epoch, "initialize", error);
                }
            }
        });
    }

    void requestStatus(UiSink sink) {
        if (cleanupConsumed) return;
        final long epoch = currentLaunchEpoch();
        final WeakReference<UiSink> weakSink = new WeakReference<UiSink>(sink);
        owner.execute(new Runnable() {
            @Override public void run() {
                requireWorkerThread();
                if (!isCurrentEpoch(epoch)) return;
                try {
                    dispatchReadback(weakSink, epoch, readStatus());
                } catch (Throwable error) {
                    dispatchFailure(weakSink, epoch, "status", error);
                }
            }
        });
    }

    void submit(
            ExperimentSessionPanelCoordinator.NativeCommand command,
            boolean start,
            UiSink sink) {
        if (command == null) return;
        synchronized (admissionLock) { if (terminalAdmitted) return; }
        final long epoch = currentLaunchEpoch();
        final WeakReference<UiSink> weakSink = new WeakReference<UiSink>(sink);
        owner.execute(new Runnable() {
            @Override public void run() {
                requireWorkerThread();
                if (!isCurrentEpoch(epoch)) return;
                try {
                    SessionReadback readback = apply(command, start);
                    dispatchReadback(weakSink, epoch, readback);
                    if (needsCommandPoll(readback, command, start)) {
                        scheduleStatusPoll(weakSink, epoch, clock.elapsedRealtimeMillis()
                            + POLL_TIMEOUT_MS, command, start);
                    }
                } catch (Throwable error) {
                    dispatchFailure(weakSink, epoch, command.operation, error);
                }
            }
        });
    }

    boolean beginTerminal(long guardGeneration, long homeEpisode, UiSink sink) {
        attach(sink);
        final long epoch;
        synchronized (admissionLock) {
            if (guardGeneration <= 0L || homeEpisode <= 0L || terminalAdmitted) {
                return false;
            }
            terminalAdmitted = true;
            terminalGuardGeneration = guardGeneration;
            terminalHomeEpisode = homeEpisode;
            terminalInitializationDeadline = clock.elapsedRealtimeMillis() + POLL_TIMEOUT_MS;
            epoch = launchEpoch;
        }
        final WeakReference<UiSink> weakSink = new WeakReference<UiSink>(sink);
        owner.execute(new Runnable() {
            @Override public void run() {
                requireWorkerThread();
                if (!terminalIdentityCurrent(epoch, guardGeneration, homeEpisode)) return;
                beginTerminalOnOwner(weakSink, epoch, guardGeneration, homeEpisode);
            }
        });
        return true;
    }

    private void beginTerminalOnOwner(WeakReference<UiSink> sink, long epoch,
            long guardGeneration, long homeEpisode) {
        try {
            SessionReadback status = readStatus();
            dispatchReadback(sink, epoch, status);
            if (status != null && (status.initializationPending() || retryable(status))) {
                Runnable retry = new Runnable() {
                    @Override public void run() {
                        if (terminalIdentityCurrent(epoch, guardGeneration, homeEpisode))
                            beginTerminalOnOwner(sink, epoch, guardGeneration, homeEpisode);
                    }
                };
                if (clock.elapsedRealtimeMillis() <= terminalInitializationDeadline) {
                    owner.schedule(retry, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } else {
                    retainTerminalRecovery(retry);
                    dispatchTerminal(sink, epoch, new TerminalResult(false, false, -1L, "", 0L,
                        "terminal-status-unavailable-retryable:" + status.detail));
                }
                return;
            }
            if (status == null || status.receipt == null || status.receipt.generation < 0L
                    || !"ready".equals(status.initializationStatus)) {
                throw new IllegalStateException("authoritative-session-generation-unavailable");
            }
            long sessionGeneration = status.receipt.generation;
            String operationId = "panel-save-and-exit-"
                + terminalOperationSequence.incrementAndGet();
            NativeRendererWriterAcknowledgedExitPolicy exitPolicy =
                new NativeRendererWriterAcknowledgedExitPolicy();
            if (exitPolicy.beginExit(sessionGeneration, operationId)
                    != NativeRendererWriterAcknowledgedExitPolicy.Action.REQUEST_WRITER_STOP) {
                dispatchTerminal(sink, epoch, new TerminalResult(
                    false, false, sessionGeneration, operationId, 0L,
                    "terminal-policy-rejected"));
                return;
            }
            ExperimentSessionPanelCoordinator.NativeCommand command =
                new ExperimentSessionPanelCoordinator.NativeCommand(
                    "save-and-exit", operationId, sessionGeneration, "");
            boolean audioStopAccepted = nativeBridge.requestAudioStop(
                sessionGeneration, operationId + "-audio-stop");
            if (audioStopAccepted && "pending".equals(nativeBridge.audioShutdownStatus())) {
                scheduleTerminalAudioPoll(sink, epoch, guardGeneration, homeEpisode, exitPolicy,
                    command, clock.elapsedRealtimeMillis() + POLL_TIMEOUT_MS);
                return;
            }
            continueTerminalAfterAudio(sink, epoch, guardGeneration, homeEpisode, exitPolicy,
                command, clock.elapsedRealtimeMillis() + POLL_TIMEOUT_MS);
        } catch (Throwable error) {
            dispatchTerminal(sink, epoch, new TerminalResult(
                false, false, 0L, "", 0L,
                "terminal-native-unavailable:" + marker(error)));
        }
    }

    private void scheduleTerminalAudioPoll(WeakReference<UiSink> sink, long epoch,
            long guardGeneration, long homeEpisode,
            NativeRendererWriterAcknowledgedExitPolicy exitPolicy,
            ExperimentSessionPanelCoordinator.NativeCommand command, long deadlineMs) {
        owner.schedule(new Runnable() {
            @Override public void run() {
                requireWorkerThread();
                if (!terminalIdentityCurrent(epoch, guardGeneration, homeEpisode)) return;
                String audioStatus = nativeBridge.audioShutdownStatus();
                if ("pending".equals(audioStatus)
                        && clock.elapsedRealtimeMillis() <= deadlineMs) {
                    scheduleTerminalAudioPoll(sink, epoch, guardGeneration, homeEpisode,
                        exitPolicy, command, deadlineMs);
                    return;
                }
                continueTerminalAfterAudio(sink, epoch, guardGeneration, homeEpisode,
                    exitPolicy, command, deadlineMs);
            }
        }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void continueTerminalAfterAudio(WeakReference<UiSink> sink, long epoch,
            long guardGeneration, long homeEpisode,
            NativeRendererWriterAcknowledgedExitPolicy exitPolicy,
            ExperimentSessionPanelCoordinator.NativeCommand command, long deadlineMs) {
        try {
            boolean audioClean = "complete".equals(nativeBridge.audioShutdownStatus());
            // Audio shutdown may yield while native presentation/completion events advance.
            // Sample the cutoff immediately before first dispatch, then freeze every replay.
            if (terminalCommandJson == null) terminalCommandJson = codec.command(command, false,
                clock.elapsedRealtimeNanos(), clock.utcNanos());
            SessionReadback response = codec.parse(nativeBridge.apply(terminalCommandJson), command, false);
            dispatchReadback(sink, epoch, response);
            // A replay can reach a closed worker after the original stop succeeded.
            // Its exact finalized acknowledgement takes precedence over transport rejection.
            if (finishTerminalIfReady(sink, epoch, exitPolicy, command, response, audioClean)) return;
            if (handleDefinitiveTerminalRejection(sink, epoch, guardGeneration, homeEpisode,
                    command, response, audioClean)) return;
            if (retryable(response)) {
                if (clock.elapsedRealtimeMillis() <= deadlineMs) {
                    owner.schedule(new Runnable() {
                        @Override public void run() {
                            if (terminalIdentityCurrent(epoch, guardGeneration, homeEpisode))
                                continueTerminalAfterAudio(sink, epoch, guardGeneration,
                                    homeEpisode, exitPolicy, command, deadlineMs);
                        }
                    }, 500L, TimeUnit.MILLISECONDS);
                    return;
                }
                retainTerminalRecovery(new Runnable() {
                    @Override public void run() {
                        if (terminalIdentityCurrent(epoch, guardGeneration, homeEpisode))
                            continueTerminalAfterAudio(sink, epoch, guardGeneration, homeEpisode,
                                exitPolicy, command, terminalInitializationDeadline);
                    }
                });
                dispatchTerminal(sink, epoch, new TerminalResult(false, false,
                    command.expectedGeneration, command.operationId, 0L,
                    "terminal-admission-unconfirmed-retryable:" + response.detail));
                return;
            } else if (response != null && "rejected".equals(response.commandStatus)) {
                dispatchTerminal(sink, epoch, new TerminalResult(false, false,
                    command.expectedGeneration, command.operationId, 0L, response.detail));
                return;
            }
            scheduleTerminalPoll(sink, epoch, guardGeneration, homeEpisode, exitPolicy,
                command, deadlineMs, audioClean);
        } catch (Throwable error) {
            retainTerminalRecovery(new Runnable() {
                @Override public void run() {
                    if (terminalIdentityCurrent(epoch, guardGeneration, homeEpisode))
                        continueTerminalAfterAudio(sink, epoch, guardGeneration, homeEpisode,
                            exitPolicy, command, terminalInitializationDeadline);
                }
            });
            dispatchTerminal(sink, epoch, new TerminalResult(
                false, false, command.expectedGeneration, command.operationId, 0L,
                "terminal-admission-outcome-unknown:" + marker(error)));
        }
    }

    private void scheduleStatusPoll(WeakReference<UiSink> sink, long epoch, long deadlineMs,
            ExperimentSessionPanelCoordinator.NativeCommand expected, boolean start) {
        owner.schedule(new Runnable() {
            @Override public void run() {
                requireWorkerThread();
                if (!isCurrentEpoch(epoch) || cleanupConsumed
                        || clock.elapsedRealtimeMillis() > deadlineMs) return;
                try {
                    SessionReadback readback = readStatus();
                    dispatchReadback(sink, epoch, readback);
                    if (readback != null && (readback.initializationPending()
                            || needsCommandPoll(readback, expected, start))) {
                        scheduleStatusPoll(sink, epoch, deadlineMs, expected, start);
                    }
                } catch (Throwable error) {
                    dispatchFailure(sink, epoch, "status-poll", error);
                }
            }
        }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void scheduleTerminalPoll(WeakReference<UiSink> sink, long epoch,
            long guardGeneration, long homeEpisode,
            NativeRendererWriterAcknowledgedExitPolicy exitPolicy,
            ExperimentSessionPanelCoordinator.NativeCommand command, long deadlineMs,
            boolean audioClean) {
        owner.schedule(new Runnable() {
            @Override public void run() {
                requireWorkerThread();
                if (!terminalIdentityCurrent(epoch, guardGeneration, homeEpisode)) return;
                if (clock.elapsedRealtimeMillis() > deadlineMs && !terminalTimeoutReported) {
                    terminalTimeoutReported = true;
                    dispatchTerminal(sink, epoch, new TerminalResult(
                        false, false, command.expectedGeneration, command.operationId, 0L,
                        "shutdown-receipt-timeout"));
                    // Retain the exact pending operation and continue low-rate observation.
                    // A timeout is not proof that an admitted writer stop was rejected.
                }
                try {
                    SessionReadback readback = readStatus();
                    dispatchReadback(sink, epoch, readback);
                    if (!finishTerminalIfReady(
                            sink, epoch, exitPolicy, command, readback, audioClean)
                            && !handleDefinitiveTerminalRejection(sink, epoch, guardGeneration,
                                homeEpisode, command, readback, audioClean)) {
                        scheduleTerminalPoll(sink, epoch, guardGeneration, homeEpisode,
                            exitPolicy, command, deadlineMs, audioClean);
                    }
                } catch (Throwable error) {
                    dispatchTerminal(sink, epoch, new TerminalResult(
                        false, false, command.expectedGeneration, command.operationId, 0L,
                        "shutdown-status-unavailable:" + marker(error)));
                    scheduleTerminalPoll(sink, epoch, guardGeneration, homeEpisode,
                        exitPolicy, command, deadlineMs, audioClean);
                }
            }
        }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private boolean finishTerminalIfReady(WeakReference<UiSink> sink, long epoch,
            NativeRendererWriterAcknowledgedExitPolicy exitPolicy,
            ExperimentSessionPanelCoordinator.NativeCommand command,
            SessionReadback readback, boolean audioClean) {
        if (readback == null
                || !("complete".equals(readback.shutdownStatus)
                    || "error".equals(readback.shutdownStatus))
                || readback.shutdownAckRevision <= 0L
                || readback.finalizedSessionGeneration != command.expectedGeneration
                || !command.operationId.equals(readback.finalizedOperationId)) {
            return false;
        }
        if (cleanupConsumed) return true;
        cleanupConsumed = true;
        boolean resourcesClean;
        try { resourcesClean = nativeBridge.cleanupAfterAcknowledgement(); }
        catch (Throwable error) { resourcesClean = false; }
        boolean saved = audioClean && resourcesClean && "complete".equals(readback.shutdownStatus)
            && ("saved".equals(readback.recordingResult)
                || (command.expectedGeneration == 0L && "none".equals(readback.recordingResult)));
        String receiptId = "shutdown-ack-" + readback.shutdownAckRevision;
        if (!exitPolicy.acknowledgeWriter(
                command.expectedGeneration, command.operationId, receiptId, saved)) {
            return false;
        }
        boolean finish = exitPolicy.requestFinish(command.expectedGeneration, command.operationId)
            == NativeRendererWriterAcknowledgedExitPolicy.Action.FINISH_AND_REMOVE_TASK;
        dispatchTerminal(sink, epoch, new TerminalResult(
            finish, saved, command.expectedGeneration, command.operationId,
            readback.shutdownAckRevision,
            saved ? "shutdown-complete" : "shutdown-error-unsaved"));
        owner.shutdownNow();
        return finish;
    }

    private boolean handleDefinitiveTerminalRejection(WeakReference<UiSink> sink, long epoch,
            long guardGeneration, long homeEpisode,
            ExperimentSessionPanelCoordinator.NativeCommand command,
            SessionReadback readback, boolean audioClean) {
        if (readback == null || readback.receipt == null
                || !"rejected".equals(readback.lastOperationStatus)
                || readback.receipt.accepted
                || readback.receipt.generation != command.expectedGeneration
                || !command.operationId.equals(readback.receipt.operationId)
                || !"not-requested".equals(readback.shutdownStatus)) return false;
        // A definitive reducer rejection proves this operation never stopped the writer.
        // Only clock regression permits a new attempt; ambiguous/admitted operations keep
        // their original identity and bytes, including their original cutoff timestamp.
        if (!"MonotonicTimeRegression".equals(readback.lastOperationReason)
                || !audioClean || terminalRebases >= MAX_TERMINAL_REBASES) {
            dispatchTerminal(sink, epoch, new TerminalResult(false, false,
                command.expectedGeneration, command.operationId, 0L,
                "terminal-definitively-rejected:" + readback.lastOperationReason));
            return true;
        }
        if (!terminalIdentityCurrent(epoch, guardGeneration, homeEpisode)) return true;
        terminalRebases++;
        terminalCommandJson = null;
        ExperimentSessionPanelCoordinator.NativeCommand fresh =
            new ExperimentSessionPanelCoordinator.NativeCommand("save-and-exit",
                "panel-save-and-exit-" + terminalOperationSequence.incrementAndGet(),
                command.expectedGeneration, "");
        NativeRendererWriterAcknowledgedExitPolicy freshPolicy =
            new NativeRendererWriterAcknowledgedExitPolicy();
        if (freshPolicy.beginExit(fresh.expectedGeneration, fresh.operationId)
                != NativeRendererWriterAcknowledgedExitPolicy.Action.REQUEST_WRITER_STOP) return true;
        owner.schedule(new Runnable() {
            @Override public void run() {
                if (terminalIdentityCurrent(epoch, guardGeneration, homeEpisode))
                    continueTerminalAfterAudio(sink, epoch, guardGeneration, homeEpisode,
                        freshPolicy, fresh, clock.elapsedRealtimeMillis() + POLL_TIMEOUT_MS);
            }
        }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        return true;
    }

    private SessionReadback readStatus() {
        requireWorkerThread();
        return codec.parse(nativeBridge.apply(codec.statusCommand()), null, false);
    }

    private SessionReadback apply(
            ExperimentSessionPanelCoordinator.NativeCommand command, boolean start) {
        requireWorkerThread();
        String request = codec.command(
            command, start, clock.elapsedRealtimeNanos(), clock.utcNanos());
        return codec.parse(nativeBridge.apply(request), command, start);
    }

    private static boolean needsCommandPoll(SessionReadback readback,
            ExperimentSessionPanelCoordinator.NativeCommand expected, boolean start) {
        if (readback == null || readback.commandPending()) return true;
        if (expected == null || readback.receipt == null) return false;
        if (!expected.operationId.equals(readback.receipt.operationId)) return true;
        if (!readback.receipt.accepted) return false;
        if (start) {
            return !("active".equals(readback.receipt.phase)
                || "recording".equals(readback.receipt.phase)
                || "error".equals(readback.receipt.phase)
                || "failed".equals(readback.receipt.phase));
        }
        return false;
    }

    private void dispatchReadback(WeakReference<UiSink> sink, long epoch,
            SessionReadback readback) {
        if (readback == null) return;
        uiDispatcher.post(new Runnable() {
            @Override public void run() {
                UiSink target;
                synchronized (admissionLock) { target = attachedSink.get(); }
                if (isCurrentEpoch(epoch) && target != null
                        && target.acceptsExperimentSessionCallbacks(epoch)) {
                    target.onExperimentSessionReadback(readback);
                }
            }
        });
    }

    private void dispatchFailure(WeakReference<UiSink> sink, long epoch,
            String operation, Throwable error) {
        dispatchTerminal(sink, epoch, new TerminalResult(
            false, false, 0L, "", 0L,
            safe(operation) + "-unavailable:" + marker(error)));
    }

    private void dispatchTerminal(WeakReference<UiSink> sink, long epoch,
            TerminalResult result) {
        if (!isCurrentEpoch(epoch)) return;
        latestTerminal = result;
        uiDispatcher.post(new Runnable() {
            @Override public void run() {
                UiSink target;
                synchronized (admissionLock) { target = attachedSink.get(); }
                if (isCurrentEpoch(epoch) && target != null
                        && target.acceptsExperimentSessionCallbacks(epoch)) {
                    target.onExperimentSessionTerminal(result);
                }
            }
        });
    }

    private long currentLaunchEpoch() {
        synchronized (admissionLock) {
            return launchEpoch;
        }
    }

    private boolean isCurrentEpoch(long expectedEpoch) {
        synchronized (admissionLock) {
            return expectedEpoch == launchEpoch;
        }
    }

    private boolean terminalIdentityCurrent(long expectedEpoch, long guardGeneration,
            long homeEpisode) {
        synchronized (admissionLock) {
            return expectedEpoch == launchEpoch && terminalAdmitted
                && terminalGuardGeneration == guardGeneration
                && terminalHomeEpisode == homeEpisode;
        }
    }

    private void requireWorkerThread() {
        if (uiDispatcher.isUiThread()) {
            throw new IllegalStateException("session JSON/JNI work reached the UI thread");
        }
    }

    void closeForTest() {
        owner.shutdownNow();
    }

    private static ScheduledExecutorService newOwner() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(
                    runnable,
                    "rq-experiment-session-shell-" + WORKER_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private static String marker(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ":" + message);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean retryable(SessionReadback readback) {
        if (readback == null) return false;
        return "rejected".equals(readback.commandStatus)
            && retryableReason(readback.detail);
    }

    static boolean retryableReason(String reason) {
        return "experiment-session-runtime-busy".equals(reason)
            || "experiment-session-runtime-uninitialized".equals(reason)
            || "readback-busy".equals(reason)
            || "terminal-lane-full".equals(reason)
            || "command-queue-full".equals(reason)
            || "terminal-admission-busy".equals(reason);
    }
}
