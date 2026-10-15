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
    /** A carrier-scoped read-only projection; it never owns panel or command effects. */
    static final class StatusObservationTarget implements UiSink {
        private final long nativeEpoch;
        private final java.util.function.BooleanSupplier current;
        private final java.util.function.LongSupplier now;
        private final java.util.function.Consumer<String> observer;
        private final ExperimentSessionPanelCoordinator projection = new ExperimentSessionPanelCoordinator();
        private boolean retired;
        private int markers;
        StatusObservationTarget(long nativeEpoch, java.util.function.BooleanSupplier current,
                java.util.function.LongSupplier now) {
            this(nativeEpoch,current,now,null);
        }
        StatusObservationTarget(long nativeEpoch, java.util.function.BooleanSupplier current,
                java.util.function.LongSupplier now, java.util.function.Consumer<String> observer) {
            this.nativeEpoch=nativeEpoch; this.current=current; this.now=now; this.observer=observer;
        }
        synchronized void retire() { retired=true; }
        public synchronized boolean acceptsExperimentSessionCallbacks(long launchEpoch) {
            return !retired && current.getAsBoolean();
        }
        public void onExperimentSessionReadback(SessionReadback value) {
            String marker=null;
            synchronized (this) {
            long time=now.getAsLong();
            if (retired || !current.getAsBoolean() || value == null || value.receipt == null
                    || value.runtimeEpoch != nativeEpoch || !"accepted".equals(value.commandStatus)
                    || !"ready".equals(value.initializationStatus)
                    || value.statusReadCompletedNanos <= 0L || value.statusReadCompletedNanos > time) return;
            projection.acceptNativeReadback(value.runtimeEpoch, value.receipt,
                value.statusReadCompletedNanos, time, true);
            ExperimentSessionPanelCoordinator.NativeStatusSnapshot snapshot=projection.nativeStatusSnapshot(time);
            if (snapshot.observed && snapshot.readId==value.statusReadCompletedNanos && markers++<8)
                marker="status_response_accepted=true epoch="+nativeEpoch+" completed_nanos="+snapshot.readId;
            }
            if (marker!=null && observer!=null) {
                try { observer.accept(marker); } catch (RuntimeException unavailable) { }
            }
        }
        public void onExperimentSessionTerminal(TerminalResult value) { }
        synchronized ExperimentSessionPanelCoordinator.NativeStatusSnapshot snapshot() {
            return retired || !current.getAsBoolean() ? null : projection.nativeStatusSnapshot(now.getAsLong());
        }
    }
    private static final long POLL_INTERVAL_MS = 250L;
    private static final long POLL_TIMEOUT_MS = 20_000L;
    private static final int MAX_TERMINAL_REBASES = 2;
    private static final AtomicLong WORKER_SEQUENCE = new AtomicLong();
    private static ExperimentSessionAndroidShell processInstance;

    interface NativeBridge {
        String initialize(String appPrivateFilesRoot);
        String apply(String commandJson);
        boolean requestAudioStop(long sessionGeneration, String operationId);
        boolean requestAudioRestartStop(long sessionGeneration, String operationId);
        String audioShutdownStatus();
        boolean cleanupAfterAcknowledgement();
        default boolean validateLocalTerminalScope(LocalTerminalScope scope) { return false; }
        default boolean admitLocalTerminal(LocalTerminalScope scope) { return false; }
        default String applyLocalTerminal(String commandJson, LocalTerminalScope scope) {
            throw new UnsupportedOperationException("local-terminal-fenced-native-bridge-unavailable");
        }
    }

    interface Codec {
        default String statusResponseReason(String responseJson) { return "unknown"; }
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
        /** Process command observer, independent of the replaceable Activity presentation. */
        default boolean observesSubmittedCommand() { return false; }
        /** Exact process-owned durable mapping; ordinary observers retain their deadline. */
        default boolean retainsDurableCommand(String operationId, long launchEpoch) { return false; }
        boolean acceptsExperimentSessionCallbacks(long launchEpoch);
        void onExperimentSessionReadback(SessionReadback readback);
        default boolean acceptsExperimentSessionTerminalCallbacks(long epoch) {
            return acceptsExperimentSessionCallbacks(epoch);
        }
        void onExperimentSessionTerminal(TerminalResult result);
    }

    static final class SessionReadback {
        final ArmAcceptance armAcceptance;
        final ExperimentSessionPanelCoordinator.NativeReceipt receipt;
        final String commandStatus;
        final String statusResponseReason;
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
        // Local explicit status-read completion, not command ACK or physical evidence.
        final long statusReadCompletedNanos;
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
            this(receipt, commandStatus, initializationStatus, inventoryStatus,
                recordingRootStatus, shutdownStatus, shutdownAckRevision,
                finalizedSessionGeneration, finalizedOperationId, detail,
                recordingResult, recordingError, runtimeEpoch,
                lastOperationStatus, lastOperationReason, -1L, "unknown", null);
        }

        private SessionReadback(
            ExperimentSessionPanelCoordinator.NativeReceipt receipt,
            String commandStatus, String initializationStatus, String inventoryStatus,
            String recordingRootStatus, String shutdownStatus, long shutdownAckRevision,
            long finalizedSessionGeneration, String finalizedOperationId,
            String detail, String recordingResult, String recordingError, long runtimeEpoch,
            String lastOperationStatus, String lastOperationReason,
            long statusReadCompletedNanos, String statusResponseReason, ArmAcceptance armAcceptance
        ) {
            this.armAcceptance = armAcceptance;
            this.statusReadCompletedNanos = statusReadCompletedNanos;
            this.statusResponseReason = safe(statusResponseReason);
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

        private SessionReadback afterStatusRead(long completedNanos, String responseReason) {
            return new SessionReadback(receipt, commandStatus, initializationStatus,
                inventoryStatus, recordingRootStatus, shutdownStatus, shutdownAckRevision,
                finalizedSessionGeneration, finalizedOperationId, detail,
                recordingResult, recordingError, runtimeEpoch,
                lastOperationStatus, lastOperationReason, completedNanos, responseReason, armAcceptance);
        }
        SessionReadback withArmAcceptance(ArmAcceptance arm) {
            return new SessionReadback(receipt, commandStatus, initializationStatus,
                inventoryStatus, recordingRootStatus, shutdownStatus, shutdownAckRevision,
                finalizedSessionGeneration, finalizedOperationId, detail,
                recordingResult, recordingError, runtimeEpoch, lastOperationStatus,
                lastOperationReason, statusReadCompletedNanos, statusResponseReason, arm);
        }
        boolean initializationPending() {
            return "initializing".equals(initializationStatus);
        }

        boolean commandPending() {
            return "queued".equals(commandStatus)
                || (receipt != null && "queued".equals(receipt.detail));
        }
    }

    /** Projection adapter for the coordinator-owned exact Arm acknowledgement. */
    static final class ArmAcceptance extends ExperimentSessionPanelCoordinator.ArmAcceptance {
        ArmAcceptance(String id,long generation,long epoch,String condition,boolean durable) {
            super(id,generation,epoch,condition,durable);
        }
        boolean matches(String id,long generation,String condition,SessionReadback current) {
            return current!=null && matches(id,generation,condition,current.receipt,current.runtimeEpoch);
        }
    }

    /** Captured local UI/native identity, never a synthesized kiosk event. */
    static final class LocalTerminalScope {
        final long launchEpoch, ownerId, runtimeEpoch, sessionGeneration;
        final String sessionKey, condition, profileSha256, bindingSha256;
        final boolean idle, idleWithoutRecording;
        LocalTerminalScope(long launch,long ownerIdentity,long runtime,long generation,String session,String condition,String profile,String binding,String recordingResult){
            if(launch<=0||ownerIdentity<=0||runtime<=0||generation<0||session==null||condition==null
                    ||profile==null||binding==null||!binding.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("local-terminal-identity-invalid");
            idle=session.isEmpty()&&condition.isEmpty()&&profile.isEmpty();
            idleWithoutRecording=idle&&"none".equals(recordingResult);
            if(idle){
                if(!idleWithoutRecording&&!"saved".equals(recordingResult))throw new IllegalArgumentException("idle-terminal-recording-result-invalid");
            }else if(generation<=0||!session.matches("utc-ns-[0-9]{20}")||!condition.matches("condition-[ab]")||!profile.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("local-terminal-session-invalid");
            launchEpoch=launch;ownerId=ownerIdentity;runtimeEpoch=runtime;sessionGeneration=generation;sessionKey=session;
            this.condition=condition;profileSha256=profile;bindingSha256=binding;
        }
    }

    static final class TerminalResult {
        final boolean shouldFinish;
        final boolean saved;
        final long sessionGeneration;
        final String operationId;
        final long shutdownAckRevision;
        final long runtimeEpoch;
        final String detail;

        TerminalResult(boolean shouldFinish, boolean saved, long sessionGeneration,
                String operationId, long shutdownAckRevision, String detail) {
            this(shouldFinish,saved,sessionGeneration,operationId,shutdownAckRevision,detail,0L);
        }
        TerminalResult(boolean shouldFinish, boolean saved, long sessionGeneration,
                String operationId, long shutdownAckRevision, String detail,long runtimeEpoch) {
            this.shouldFinish = shouldFinish;
            this.saved = saved;
            this.sessionGeneration = sessionGeneration;
            this.operationId = safe(operationId);
            this.shutdownAckRevision = Math.max(0L, shutdownAckRevision);
            this.runtimeEpoch=runtimeEpoch;
            this.detail = safe(detail);
        }
    }

    private final NativeBridge nativeBridge;
    private final Codec codec;
    private final Clock clock;
    private final UiDispatcher uiDispatcher;
    private volatile ScheduledExecutorService owner;
    private ScheduledExecutorService statusObserver;
    private long statusObserverIdentity;
    private final AtomicLong terminalOperationSequence = new AtomicLong();
    private final Object admissionLock = new Object();
    private long launchEpoch;
    private boolean terminalAdmitted;
    private LocalTerminalScope localTerminalScope;
    private boolean localTerminalDispatched;
    private boolean localTerminalFacadeAdmitted,localTerminalAudioAttempted;
    private ExperimentSessionPanelCoordinator.NativeCommand localTerminalCommand;
    private NativeRendererWriterAcknowledgedExitPolicy localTerminalPolicy;
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
        TerminalResult cached=latestTerminal;
        if (cached != null) postTerminal(currentLaunchEpoch(),cached);
        resumePendingTerminal();
    }

    /** Presentation authority only; does not create or transfer the recording owner. */
    boolean isCurrentUiAttachment(UiSink sink, long expectedLaunchEpoch) {
        synchronized (admissionLock) {
            return sink != null && attachedSink.get() == sink
                && !retiredSinks.containsKey(sink) && !cleanupConsumed
                && launchEpoch == expectedLaunchEpoch;
        }
    }

    /** Replays only the cached receipt to its current terminal presentation; never reissues work. */
    boolean replayCurrentTerminal(UiSink sink,long expectedLaunchEpoch) {
        final TerminalResult result;
        synchronized(admissionLock) {
            if(!isCurrentTerminalUiAttachment(sink,expectedLaunchEpoch))return false;
            result=latestTerminal;
        }
        if(result==null)return false;
        postTerminal(expectedLaunchEpoch,result);
        return true;
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

    static synchronized ExperimentSessionAndroidShell process() {
        if (processInstance == null) throw new IllegalStateException("selected shell not installed");
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
            localTerminalScope = null;
            localTerminalDispatched = false;localTerminalFacadeAdmitted=false;localTerminalAudioAttempted=false;localTerminalCommand=null;localTerminalPolicy=null;
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

    /** Exact native lifetime opens an independent read-only executor. */
    synchronized void openStatusObserver(long identity) {
        if (identity <= statusObserverIdentity) return;
        if (statusObserver != null) statusObserver.shutdown();
        statusObserverIdentity=identity;
        statusObserver=newOwner();
    }
    synchronized void closeStatusObserver(long identity) {
        if (identity != statusObserverIdentity || statusObserver == null) return;
        statusObserver.shutdown(); statusObserver=null;
    }
    /** Does not install/replace a UI sink or reopen mutation/terminal authority. */
    void observeStatus(final java.util.function.Consumer<SessionReadback> observer) {
        if (observer == null) throw new IllegalArgumentException("observer required");
        final ScheduledExecutorService reader;
        synchronized (this) { reader=statusObserver; }
        if (reader == null) { observer.accept(null); return; }
        try {
            reader.execute(new Runnable() {
                @Override public void run() {
                    SessionReadback value;
                    try { value = readStatus(); } catch (Throwable unavailable) { value = null; }
                    final SessionReadback result = value;
                    uiDispatcher.post(new Runnable() {
                        @Override public void run() { observer.accept(result); }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { observer.accept(null); }
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
                    dispatchStatusReadback(weakSink, epoch, readStatus());
                } catch (Throwable error) {
                    dispatchFailure(weakSink, epoch, "status", error);
                }
            }
        });
    }

    private void dispatchStatusReadback(WeakReference<UiSink> sink, long epoch,
            SessionReadback readback) {
        if (readback == null) return;
        uiDispatcher.post(new Runnable() {
            @Override public void run() {
                UiSink target = sink.get();
                synchronized (admissionLock) {
                    if (!isCurrentEpoch(epoch) || terminalAdmitted || cleanupConsumed
                            || target == null || retiredSinks.containsKey(target)) return;
                }
                // No authority lock is held across caller eligibility or callbacks.
                if (target.acceptsExperimentSessionCallbacks(epoch)) {
                    target.onExperimentSessionReadback(readback);
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
                    if ("restart-to-experimenter".equals(command.operation)) {
                        nativeBridge.requestAudioRestartStop(command.expectedGeneration,
                            command.operationId + "-audio-stop");
                        submitAfterRestartAudio(weakSink, epoch, command,
                            clock.elapsedRealtimeMillis() + POLL_TIMEOUT_MS);
                    } else {
                        submitOnOwner(weakSink, epoch, command, start);
                    }
                } catch (Throwable error) {
                    dispatchFailure(weakSink, epoch, command.operation, error);
                }
            }
        });
    }

    private void submitAfterRestartAudio(WeakReference<UiSink> sink, long epoch,
            ExperimentSessionPanelCoordinator.NativeCommand command, long deadlineMs) {
        if (!isCurrentEpoch(epoch) || cleanupConsumed) return;
        try {
            if ("pending".equals(nativeBridge.audioShutdownStatus())
                    && clock.elapsedRealtimeMillis() <= deadlineMs) {
                owner.schedule(new Runnable() {
                    @Override public void run() {
                        submitAfterRestartAudio(sink, epoch, command, deadlineMs);
                    }
                }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                return;
            }
            submitOnOwner(sink, epoch, command, false);
        } catch (Throwable error) {
            dispatchFailure(sink, epoch, command.operation, error);
        }
    }

    private void submitOnOwner(WeakReference<UiSink> sink, long epoch,
            ExperimentSessionPanelCoordinator.NativeCommand command, boolean start) {
        SessionReadback readback = apply(command, start);
        dispatchReadback(sink, epoch, readback);
        if (needsCommandPoll(readback, command, start)) {
            scheduleStatusPoll(sink, epoch, clock.elapsedRealtimeMillis()
                + POLL_TIMEOUT_MS, command, start);
        }
    }

    boolean localTerminalRetryReady(){synchronized(admissionLock){return localTerminalScope!=null&&terminalAdmitted&&!cleanupConsumed&&terminalRecovery!=null&&!localTerminalDispatched;}}
    boolean localTerminalPending(){synchronized(admissionLock){return localTerminalScope!=null&&terminalAdmitted;}}
    boolean isCurrentTerminalUiAttachment(UiSink sink,long expectedLaunchEpoch){
        synchronized(admissionLock){return sink!=null&&attachedSink.get()==sink&&!retiredSinks.containsKey(sink)
            &&launchEpoch==expectedLaunchEpoch&&localTerminalScope!=null&&terminalAdmitted;}
    }
    boolean isLocalTerminalScopeCurrent(LocalTerminalScope scope){synchronized(admissionLock){return scope!=null&&scope==localTerminalScope&&terminalAdmitted&&scope.launchEpoch==launchEpoch;}}
    LocalTerminalScope localTerminalScope(){synchronized(admissionLock){return localTerminalScope;}}
    boolean beginLocalTerminal(LocalTerminalScope scope,UiSink sink){
        if(scope==null||sink==null)return false;
        synchronized(admissionLock){
            if(terminalAdmitted||cleanupConsumed||scope.launchEpoch!=launchEpoch
                ||attachedSink.get()!=sink||retiredSinks.containsKey(sink))return false;
            terminalAdmitted=true;localTerminalScope=scope;localTerminalDispatched=false;
            terminalInitializationDeadline=clock.elapsedRealtimeMillis()+POLL_TIMEOUT_MS;
        }
        final WeakReference<UiSink> target=new WeakReference<UiSink>(sink);
        owner.execute(new Runnable(){public void run(){beginLocalTerminalOnOwner(target,scope);}});
        return true;
    }
    private void beginLocalTerminalOnOwner(WeakReference<UiSink> target,LocalTerminalScope scope){
        requireWorkerThread();if(!terminalIdentityCurrent(scope.launchEpoch,0L,0L))return;
        try{
            if(!nativeBridge.validateLocalTerminalScope(scope))throw new SecurityException("local-terminal-owner-changed");
            if(!localTerminalFacadeAdmitted){
                if(!nativeBridge.admitLocalTerminal(scope))throw new SecurityException("local-terminal-admission-rejected");
                localTerminalFacadeAdmitted=true;
            }
            beginTerminalOnOwner(target,scope.launchEpoch,0L,0L);
        }catch(Throwable error){
            Runnable retry=new Runnable(){public void run(){beginLocalTerminalOnOwner(target,scope);}};
            retainTerminalRecovery(retry);
            dispatchTerminal(target,scope.launchEpoch,new TerminalResult(false,false,scope.sessionGeneration,"",0L,"local-terminal-before-dispatch-unavailable:"+marker(error)));
        }
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
            LocalTerminalScope local=localTerminalScope;
            if(local!=null&&(status.runtimeEpoch!=local.runtimeEpoch||status.receipt.generation!=local.sessionGeneration
                    ||!nativeBridge.validateLocalTerminalScope(local)))throw new SecurityException("local-terminal-owner-changed-before-stop");
            long sessionGeneration = status.receipt.generation;
            String operationId = local!=null&&localTerminalCommand!=null?localTerminalCommand.operationId:
                "panel-save-and-exit-" + terminalOperationSequence.incrementAndGet();
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
            if(local!=null){
                if(localTerminalCommand==null){localTerminalCommand=command;localTerminalPolicy=exitPolicy;}
                else{command=localTerminalCommand;exitPolicy=localTerminalPolicy;}
            }
            boolean audioStopAccepted=true;
            if(local==null||!localTerminalAudioAttempted){
                if(local!=null)localTerminalAudioAttempted=true;
                audioStopAccepted=nativeBridge.requestAudioStop(sessionGeneration,operationId+"-audio-stop");
            }
            if (audioStopAccepted && "pending".equals(nativeBridge.audioShutdownStatus())) {
                scheduleTerminalAudioPoll(sink, epoch, guardGeneration, homeEpisode, exitPolicy,
                    command, clock.elapsedRealtimeMillis() + POLL_TIMEOUT_MS);
                return;
            }
            continueTerminalAfterAudio(sink, epoch, guardGeneration, homeEpisode, exitPolicy,
                command, clock.elapsedRealtimeMillis() + POLL_TIMEOUT_MS);
        } catch (Throwable error) {
            if(localTerminalScope!=null){
                Runnable retry=new Runnable(){public void run(){if(terminalIdentityCurrent(epoch,guardGeneration,homeEpisode))beginTerminalOnOwner(sink,epoch,guardGeneration,homeEpisode);}};
                retainTerminalRecovery(retry);
            }
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
                try {
                    String audioStatus = nativeBridge.audioShutdownStatus();
                    if ("pending".equals(audioStatus)
                            && clock.elapsedRealtimeMillis() <= deadlineMs) {
                        scheduleTerminalAudioPoll(sink, epoch, guardGeneration, homeEpisode,
                            exitPolicy, command, deadlineMs);
                        return;
                    }
                    continueTerminalAfterAudio(sink, epoch, guardGeneration, homeEpisode,
                        exitPolicy, command, deadlineMs);
                } catch (Throwable error) {
                    if (localTerminalScope != null) {
                        retainTerminalRecovery(new Runnable() {
                            @Override public void run() {
                                if (terminalIdentityCurrent(epoch, guardGeneration, homeEpisode))
                                    scheduleTerminalAudioPoll(sink, epoch, guardGeneration, homeEpisode,
                                        exitPolicy, command, terminalInitializationDeadline);
                            }
                        });
                    }
                    dispatchTerminal(sink, epoch, new TerminalResult(false, false,
                        command.expectedGeneration, command.operationId, 0L,
                        "terminal-audio-status-unavailable:" + marker(error)));
                }
            }
        }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void continueTerminalAfterAudio(WeakReference<UiSink> sink, long epoch,
            long guardGeneration, long homeEpisode,
            NativeRendererWriterAcknowledgedExitPolicy exitPolicy,
            ExperimentSessionPanelCoordinator.NativeCommand command, long deadlineMs) {
        try {
            boolean audioClean = "complete".equals(nativeBridge.audioShutdownStatus());
            LocalTerminalScope local=localTerminalScope;
            if(local!=null){
                if(localTerminalDispatched){scheduleTerminalPoll(sink,epoch,guardGeneration,homeEpisode,exitPolicy,command,deadlineMs,audioClean);return;}
                if(!nativeBridge.validateLocalTerminalScope(local))throw new SecurityException("local-terminal-owner-changed-before-cutoff");
            }
            // All potentially blocking validation precedes the one physical cutoff sample.
            // Legacy retries retain these exact bytes; local dispatch is never replayed.
            if (terminalCommandJson == null) terminalCommandJson = codec.command(command, false,
                clock.elapsedRealtimeNanos(), clock.utcNanos());
            if(local!=null)localTerminalDispatched=true;
            SessionReadback response = codec.parse(local==null?nativeBridge.apply(terminalCommandJson):nativeBridge.applyLocalTerminal(terminalCommandJson,local), command, false);
            dispatchReadback(sink, epoch, response);
            // A replay can reach a closed worker after the original stop succeeded.
            // Its exact finalized acknowledgement takes precedence over transport rejection.
            if (finishTerminalIfReady(sink, epoch, exitPolicy, command, response, audioClean)) return;
            if (handleDefinitiveTerminalRejection(sink, epoch, guardGeneration, homeEpisode,
                    command, response, audioClean)) return;
            if(local!=null&&retryable(response)){
                dispatchTerminal(sink,epoch,new TerminalResult(false,false,command.expectedGeneration,command.operationId,0L,"terminal-admission-unknown-observe-only-no-replay"));
                scheduleTerminalPoll(sink,epoch,guardGeneration,homeEpisode,exitPolicy,command,deadlineMs,audioClean);return;
            }
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
            if(localTerminalScope!=null){
                dispatchTerminal(sink,epoch,new TerminalResult(false,false,command.expectedGeneration,command.operationId,0L,"terminal-admission-outcome-unknown-no-replay:"+marker(error)));
                if(localTerminalDispatched)scheduleTerminalPoll(sink,epoch,guardGeneration,homeEpisode,exitPolicy,command,deadlineMs,false);
                else retainTerminalRecovery(new Runnable(){public void run(){if(terminalIdentityCurrent(epoch,guardGeneration,homeEpisode))continueTerminalAfterAudio(sink,epoch,guardGeneration,homeEpisode,exitPolicy,command,terminalInitializationDeadline);}});
                return;
            }
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
                if (!isCurrentEpoch(epoch) || cleanupConsumed) return;
                UiSink observer = sink.get();
                boolean retained = expected != null
                    && ("arm".equals(expected.operation) || "restart-to-experimenter".equals(expected.operation))
                    && observer != null && observer.observesSubmittedCommand()
                    && observer.retainsDurableCommand(expected.operationId, epoch);
                if (clock.elapsedRealtimeMillis() > deadlineMs && !retained) return;
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
                    if(localTerminalScope!=null&&readback!=null&&readback.receipt!=null&&readback.runtimeEpoch>0L&&"ready".equals(readback.initializationStatus)&&readback.runtimeEpoch!=localTerminalScope.runtimeEpoch){
                        dispatchTerminal(sink,epoch,new TerminalResult(false,false,command.expectedGeneration,command.operationId,0L,"terminal-owner-changed-outcome-unknown-no-replay"));return;
                    }
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
        if(localTerminalScope!=null){
            if(readback.runtimeEpoch!=localTerminalScope.runtimeEpoch)return false;
            String audio=nativeBridge.audioShutdownStatus();
            if("pending".equals(audio))return false;
            audioClean="complete".equals(audio);
        }
        if (cleanupConsumed) return true;
        cleanupConsumed = true;
        boolean resourcesClean;
        try { resourcesClean = nativeBridge.cleanupAfterAcknowledgement(); }
        catch (Throwable error) { resourcesClean = false; }
        boolean saved = audioClean && resourcesClean && "complete".equals(readback.shutdownStatus)
            && ("saved".equals(readback.recordingResult)
                || ((localTerminalScope==null?command.expectedGeneration==0L:localTerminalScope.idleWithoutRecording) && "none".equals(readback.recordingResult)));
        String receiptId = "shutdown-ack-" + readback.shutdownAckRevision;
        if (!exitPolicy.acknowledgeWriter(
                command.expectedGeneration, command.operationId, receiptId, saved)) {
            return false;
        }
        boolean finish = (localTerminalScope==null||saved)
            && exitPolicy.requestFinish(command.expectedGeneration, command.operationId)
                == NativeRendererWriterAcknowledgedExitPolicy.Action.FINISH_AND_REMOVE_TASK;
        dispatchTerminal(sink, epoch, new TerminalResult(
            finish, saved, command.expectedGeneration, command.operationId,
            readback.shutdownAckRevision,
            saved ? "shutdown-complete" : "shutdown-error-unsaved",readback.runtimeEpoch));
        owner.shutdownNow();
        synchronized (this) { if (statusObserver != null) statusObserver.shutdownNow(); }
        return true;
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
        if (localTerminalScope!=null || !"MonotonicTimeRegression".equals(readback.lastOperationReason)
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
        String response = nativeBridge.apply(codec.statusCommand());
        long completedNanos = clock.elapsedRealtimeNanos();
        SessionReadback readback = codec.parse(response, null, false);
        return readback == null ? null : readback.afterStatusRead(completedNanos, codec.statusResponseReason(response));
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
        if ("arm".equals(expected.operation) && readback.armAcceptance != null
                && readback.armAcceptance.matches(expected.operationId,
                    expected.expectedGeneration + 1L, expected.condition, readback)
                && readback.receipt.accepted
                && "active".equals(readback.receipt.phase)
                && ("armed".equals(readback.receipt.controlState)
                    || "running".equals(readback.receipt.controlState)
                    || "paused".equals(readback.receipt.controlState))) return false;
        // Arm's active phase precedes asynchronous audio readiness. Keep the
        // original process observer polling until the retained checkpoint and
        // ready control state above agree, or this exact command is rejected.
        if ("arm".equals(expected.operation)) {
            return !expected.operationId.equals(readback.receipt.operationId)
                || readback.receipt.accepted;
        }
        if (!expected.operationId.equals(readback.receipt.operationId)) return true;
        if (!readback.receipt.accepted) return false;
        if ("restart-to-experimenter".equals(expected.operation)) {
            return !readback.receipt.durable;
        }
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
                synchronized (admissionLock) {
                    UiSink submitted = sink.get();
                    target = submitted != null && submitted.observesSubmittedCommand()
                        ? submitted : attachedSink.get();
                }
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
        synchronized(admissionLock) {
            if(epoch!=launchEpoch)return;
            latestTerminal=result;
        }
        postTerminal(epoch,result);
    }

    /** Delivery never republishes an old cached result over a newer worker receipt. */
    private void postTerminal(long epoch,TerminalResult result) {
        uiDispatcher.post(new Runnable() {
            @Override public void run() {
                UiSink target;
                synchronized (admissionLock) {
                    if(epoch!=launchEpoch||latestTerminal!=result)return;
                    target = attachedSink.get();
                }
                if (isCurrentEpoch(epoch) && target != null
                        && target.acceptsExperimentSessionTerminalCallbacks(epoch)) {
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
                && (localTerminalScope!=null || (terminalGuardGeneration == guardGeneration
                && terminalHomeEpisode == homeEpisode));
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
