package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ExperimentSessionAndroidShellTest {
    public static void main(String[] args) throws Exception {
        restartWaitsForAudioStopBeforeFinalizing();
        idleGenerationZeroCanFinishOnlyAfterExactShutdownReceipt();
        shutdownErrorMayCloseButNeverClaimsSaved();
        unsavedRecordingCannotBecomeSavedThroughCleanShutdown();
        recreationReplaysResultWithoutRepeatingCleanup();
        busyRetriesSameIdentityAndLateAckSurvivesTimeout();
        malformedStatusNeverFabricatesIdleStop();
        cleanupFailureDoesNotClaimSaved();
        initialBusyStatusRetriesWithoutInventingGeneration();
        terminalLaneFullRetriesSameOperation();
        admissionExhaustionRecoversOnReplacement();
        admittedReadbackBusyThenClosedWorkerAckFinishesOnce();
        delayedAudioSamplesCutoffAtDispatchAndReplaysAfterLaterActorEvents();
        definitiveClockRejectionRecoversWithFreshAttempt(false);
        definitiveClockRejectionRecoversWithFreshAttempt(true);
        definitiveRejectionRecoveryIsBoundedAndReasonSpecific();
        System.out.println("ExperimentSessionAndroidShellTest PASS");
    }

    private static void restartWaitsForAudioStopBeforeFinalizing() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.delayRestartAudio = true;
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), new FakeClock(), new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.submit(new ExperimentSessionPanelCoordinator.NativeCommand(
                "restart-to-experimenter", "panel-restart-1", 1L, ""), false, sink);
            require(bridge.restartApplied.await(3L, TimeUnit.SECONDS));
            equal(1, bridge.audioStops);
            require(bridge.restartAudioPolls >= 3);
            equal(1, bridge.submissions);
        } finally { shell.closeForTest(); }
    }

    private static void delayedAudioSamplesCutoffAtDispatchAndReplaysAfterLaterActorEvents() throws Exception {
        FakeClock clock = new FakeClock();
        FakeBridge bridge = new FakeBridge(false);
        bridge.delayedAudioClock = clock;
        bridge.admittedReadbackBusy = true;
        FakeCodec codec = new FakeCodec();
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, codec, clock, new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.beginTerminal(1L, 1L, sink);
            require(sink.terminal.await(3L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            require(sink.result.saved);
            equal(4_000_000_000L, bridge.firstDispatchAt);
            require(bridge.actorAt > bridge.firstDispatchAt);
            equal(bridge.firstTerminalBytes, bridge.lastTerminalBytes);
            equal(1, codec.encodings);
            equal(1, bridge.acceptedStops);
            equal(1, bridge.cleanups);
        } finally { shell.closeForTest(); }
    }

    private static void definitiveClockRejectionRecoversWithFreshAttempt(boolean queued) throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.eventAfterEncoding = true;
        bridge.queuedRegression = queued;
        FakeClock clock = new FakeClock();
        bridge.rebaseClock = clock;
        FakeCodec codec = new FakeCodec();
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, codec, clock, new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.beginTerminal(1L, 1L, sink);
            require(sink.terminal.await(3L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            require(sink.result.saved);
            equal(2, bridge.submissions);
            equal(2, codec.encodings);
            equal(2, bridge.bytesByOperation.size());
            require(bridge.lastDispatchAt > bridge.firstDispatchAt);
            equal(1, bridge.acceptedStops);
            equal(1, bridge.audioStops);
            equal(1, bridge.cleanups);
            reject(shell.beginTerminal(1L, 1L, sink));
        } finally { shell.closeForTest(); }
    }

    private static void definitiveRejectionRecoveryIsBoundedAndReasonSpecific() throws Exception {
        for (String reason : new String[] { "MonotonicTimeRegression", "operation-conflict" }) {
            FakeBridge bridge = new FakeBridge(false);
            bridge.rejectEveryAttempt = true;
            bridge.rejectionReason = reason;
            FakeClock clock = new FakeClock();
            bridge.rebaseClock = clock;
            FakeCodec codec = new FakeCodec();
            codec.rejectionReason = reason;
            Sink sink = new Sink();
            ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
                bridge, codec, clock, new DirectDispatcher());
            try {
                shell.beginExplicitLaunchEpoch(1L);
                shell.beginTerminal(1L, 1L, sink);
                require(sink.terminal.await(3L, TimeUnit.SECONDS));
                reject(sink.result.shouldFinish);
                reject(sink.result.saved);
                equal("terminal-definitively-rejected:" + reason, sink.result.detail);
                equal("MonotonicTimeRegression".equals(reason) ? 3 : 1, bridge.submissions);
                equal(0, bridge.acceptedStops);
                equal(0, bridge.cleanups);
                reject(shell.resumePendingTerminal());
            } finally { shell.closeForTest(); }
        }
    }

    private static void admittedReadbackBusyThenClosedWorkerAckFinishesOnce() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.admittedReadbackBusy = true;
        FakeCodec codec = new FakeCodec();
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, codec, new FakeClock(), new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.beginTerminal(1L, 1L, sink);
            require(sink.terminal.await(3L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            require(sink.result.saved);
            equal(2, bridge.submissions);
            equal(1, bridge.acceptedStops);
            equal(1, bridge.cleanups);
            equal(1, bridge.audioStops);
            equal(1, codec.encodings);
            equal(bridge.firstTerminalBytes, bridge.lastTerminalBytes);
            reject(shell.beginTerminal(1L, 1L, sink));
            equal(1, bridge.cleanups);
        } finally { shell.closeForTest(); }
    }

    private static void initialBusyStatusRetriesWithoutInventingGeneration() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.initialBusy = true;
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(7L), new FakeClock(), new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.beginTerminal(1L, 1L, sink);
            require(sink.terminal.await(3L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            equal(7L, sink.result.sessionGeneration);
            equal(2, bridge.statusReads);
            equal(1, bridge.submissions);
            equal(1, bridge.audioStops);
            equal(1, bridge.acceptedStops);
        } finally { shell.closeForTest(); }
    }

    private static void terminalLaneFullRetriesSameOperation() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.busyOnce = true;
        bridge.busyReason = "terminal-lane-full";
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), new FakeClock(), new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.beginTerminal(1L, 1L, sink);
            require(sink.terminal.await(3L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            equal(2, bridge.submissions);
            equal(1, bridge.acceptedStops);
            equal(1, bridge.audioStops);
        } finally { shell.closeForTest(); }
    }

    private static void admissionExhaustionRecoversOnReplacement() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.rejectAdmission = true;
        bridge.busyReason = "terminal-lane-full";
        FakeClock clock = new FakeClock();
        Sink first = new Sink();
        Sink replacement = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), clock, new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.beginTerminal(1L, 1L, first);
            require(bridge.rejected.await(2L, TimeUnit.SECONDS));
            clock.millis = 50_000L;
            require(first.terminal.await(3L, TimeUnit.SECONDS));
            reject(first.result.shouldFinish);
            reject(first.result.saved);
            equal("terminal-admission-unconfirmed-retryable:terminal-lane-full", first.result.detail);
            equal(0, bridge.acceptedStops);
            equal(0, bridge.cleanups);
            int observedReads = bridge.statusReads;
            Thread.sleep(300L);
            equal(observedReads, bridge.statusReads);
            bridge.rejectAdmission = false;
            shell.detach(first);
            shell.attach(replacement);
            // A repeated signal cannot create a second logical exit while resume is in flight.
            shell.beginTerminal(1L, 1L, replacement);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while ((replacement.result == null || !replacement.result.shouldFinish)
                    && System.nanoTime() < deadline) Thread.sleep(10L);
            require(replacement.result.shouldFinish);
            equal(first.result.operationId, replacement.result.operationId);
            equal(1, bridge.acceptedStops);
            equal(1, bridge.audioStops);
            equal(1, bridge.cleanups);
        } finally { shell.closeForTest(); }
    }

    private static void malformedStatusNeverFabricatesIdleStop() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.malformed = true;
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), new FakeClock(), new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.beginTerminal(1L, 1L, sink);
            require(sink.terminal.await(2L, TimeUnit.SECONDS));
            reject(sink.result.shouldFinish);
            reject(sink.result.saved);
            equal(0, bridge.submissions);
            equal(0, bridge.audioStops);
            equal(0, bridge.cleanups);
        } finally { shell.closeForTest(); }
    }

    private static void cleanupFailureDoesNotClaimSaved() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.cleanupFailure = true;
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), new FakeClock(), new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.beginTerminal(1L, 1L, sink);
            require(sink.terminal.await(2L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            reject(sink.result.saved);
            equal(1, bridge.cleanups);
        } finally { shell.closeForTest(); }
    }

    private static void unsavedRecordingCannotBecomeSavedThroughCleanShutdown() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.recordingFailure = true;
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), new FakeClock(), new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.initialize("/exact/files", sink);
            require(sink.readback.await(2L, TimeUnit.SECONDS));
            shell.beginTerminal(1L, 1L, sink);
            require(sink.terminal.await(2L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            reject(sink.result.saved);
            equal(1, bridge.cleanups);
        } finally { shell.closeForTest(); }
    }

    private static void recreationReplaysResultWithoutRepeatingCleanup() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        Sink first = new Sink();
        Sink replacement = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), new FakeClock(), new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.initialize("/exact/files", first);
            require(first.readback.await(2L, TimeUnit.SECONDS));
            shell.beginTerminal(1L, 1L, first);
            require(first.terminal.await(2L, TimeUnit.SECONDS));
            shell.detach(first);
            shell.attach(replacement);
            require(replacement.terminal.await(2L, TimeUnit.SECONDS));
            require(replacement.result.shouldFinish);
            equal(first.result.operationId, replacement.result.operationId);
            equal(1, bridge.cleanups);
            reject(shell.beginTerminal(1L, 1L, replacement));
            shell.attach(first);
            equal(1, first.terminalCallbacks);
        } finally { shell.closeForTest(); }
    }

    private static void busyRetriesSameIdentityAndLateAckSurvivesTimeout() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        bridge.busyOnce = true;
        bridge.lateAck = true;
        FakeClock clock = new FakeClock();
        Sink first = new Sink();
        Sink replacement = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), clock, new DirectDispatcher());
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.initialize("/exact/files", first);
            require(first.readback.await(2L, TimeUnit.SECONDS));
            shell.beginTerminal(1L, 1L, first);
            require(bridge.accepted.await(3L, TimeUnit.SECONDS));
            shell.detach(first);
            shell.attach(replacement);
            clock.millis = 50_000L;
            require(replacement.terminal.await(2L, TimeUnit.SECONDS));
            reject(replacement.result.shouldFinish);
            bridge.releaseAck = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (!replacement.result.shouldFinish && System.nanoTime() < deadline) Thread.sleep(10L);
            require(replacement.result.shouldFinish);
            equal(2, bridge.submissions);
            equal(1, bridge.audioStops);
            equal(1, bridge.cleanups);
        } finally { shell.closeForTest(); }
    }

    private static void idleGenerationZeroCanFinishOnlyAfterExactShutdownReceipt()
            throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        FakeClock clock = new FakeClock();
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), clock, new DirectDispatcher());
        try {
            require(shell.beginExplicitLaunchEpoch(41L));
            shell.initialize("/exact/files", sink);
            require(sink.readback.await(2L, TimeUnit.SECONDS));
            equal("/exact/files", bridge.initializedRoot);
            reject(bridge.workerThread.startsWith("main"));
            require(shell.beginTerminal(9L, 3L, sink));
            reject(shell.beginTerminal(9L, 3L, sink));
            require(sink.terminal.await(3L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            require(sink.result.saved);
            equal(0L, sink.result.sessionGeneration);
            require(sink.result.shutdownAckRevision > 0L);
        } finally {
            shell.closeForTest();
        }
    }

    private static void shutdownErrorMayCloseButNeverClaimsSaved() throws Exception {
        FakeBridge bridge = new FakeBridge(true);
        Sink sink = new Sink();
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), new FakeClock(), new DirectDispatcher());
        try {
            require(shell.beginExplicitLaunchEpoch(52L));
            shell.initialize("/exact/files", sink);
            require(sink.readback.await(2L, TimeUnit.SECONDS));
            require(shell.beginTerminal(11L, 4L, sink));
            require(sink.terminal.await(3L, TimeUnit.SECONDS));
            require(sink.result.shouldFinish);
            reject(sink.result.saved);
            equal("shutdown-error-unsaved", sink.result.detail);
        } finally {
            shell.closeForTest();
        }
    }

    private static final class FakeBridge implements ExperimentSessionAndroidShell.NativeBridge {
        final boolean failShutdown;
        volatile String initializedRoot = "";
        volatile String workerThread = "";
        volatile String operationId = "";
        volatile int cleanups;
        volatile int submissions;
        volatile int audioStops;
        volatile boolean delayRestartAudio;
        volatile int restartAudioPolls;
        final CountDownLatch restartApplied = new CountDownLatch(1);
        boolean recordingFailure;
        boolean busyOnce;
        boolean lateAck;
        boolean malformed;
        boolean cleanupFailure;
        boolean initialBusy;
        volatile boolean rejectAdmission;
        String busyReason = "terminal-admission-busy";
        volatile int statusReads;
        volatile int acceptedStops;
        boolean admittedReadbackBusy;
        String firstTerminalBytes;
        String lastTerminalBytes;
        FakeClock delayedAudioClock;
        int audioPolls;
        long actorAt;
        long firstDispatchAt;
        boolean eventAfterEncoding;
        long lastDispatchAt;
        FakeClock rebaseClock;
        boolean queuedRegression;
        boolean pendingRejectedReadback;
        boolean rejectionDelivered;
        boolean rejectEveryAttempt;
        String rejectionReason = "MonotonicTimeRegression";
        final java.util.Map<String, String> bytesByOperation = new java.util.HashMap<String, String>();
        final CountDownLatch rejected = new CountDownLatch(1);
        volatile boolean releaseAck;
        final CountDownLatch accepted = new CountDownLatch(1);

        FakeBridge(boolean failShutdown) { this.failShutdown = failShutdown; }

        @Override public String initialize(String root) {
            initializedRoot = root;
            workerThread = Thread.currentThread().getName();
            return "status";
        }

        @Override public String apply(String command) {
            workerThread = Thread.currentThread().getName();
            if (malformed) return "malformed";
            if (command.startsWith("restart-to-experimenter:")) {
                if (delayRestartAudio) require(restartAudioPolls >= 3);
                submissions++;
                restartApplied.countDown();
                return "restart-complete";
            }
            if (command.startsWith("save-and-exit:")) {
                if (firstTerminalBytes == null) firstTerminalBytes = command;
                lastTerminalBytes = command;
                String requested = command.substring("save-and-exit:".length()).split("\\|", -1)[0];
                String priorBytes = bytesByOperation.put(requested, command);
                if (priorBytes != null) equal(priorBytes, command);
                if (!operationId.isEmpty() && !operationId.equals(requested)) {
                    require(rejectionDelivered);
                    rejectionDelivered = false;
                }
                operationId = requested;
                submissions++;
                long encodedAt = Long.parseLong(command.split("\\|", -1)[1]);
                lastDispatchAt = encodedAt;
                if (submissions == 1) {
                    firstDispatchAt = encodedAt;
                    if (eventAfterEncoding) actorAt = encodedAt + 1L;
                }
                if (rejectEveryAttempt) actorAt = encodedAt + 1L;
                if (priorBytes == null && encodedAt < actorAt) {
                    if (rebaseClock != null) rebaseClock.nanos = actorAt + 1_000L;
                    if (queuedRegression) {
                        pendingRejectedReadback = true;
                        return "queued";
                    }
                    rejectionDelivered = true;
                    return "monotonic-rejected";
                }
                if (admittedReadbackBusy) {
                    if (submissions == 1) {
                        acceptedStops++;
                        actorAt = encodedAt + 1L;
                        return "readback-busy";
                    }
                    return "terminal-worker-closed-ack";
                }
                if (rejectAdmission || (busyOnce && submissions == 1)) {
                    rejected.countDown();
                    return busyReason;
                }
                acceptedStops++;
                accepted.countDown();
                if (lateAck) return "queued";
                return recordingFailure ? "terminal-unsaved"
                    : failShutdown ? "terminal-error" : "terminal-complete";
            }
            statusReads++;
            if (pendingRejectedReadback) {
                pendingRejectedReadback = false;
                rejectionDelivered = true;
                return "monotonic-rejected-status";
            }
            if (initialBusy && statusReads == 1) return "readback-busy";
            if (releaseAck) return "terminal-complete";
            return "status";
        }

        @Override public boolean requestAudioStop(long generation, String operationId) {
            audioStops++;
            return true;
        }

        @Override public boolean requestAudioRestartStop(long generation, String operationId) {
            audioStops++;
            return true;
        }

        @Override public String audioShutdownStatus() {
            if (delayRestartAudio) {
                restartAudioPolls++;
                return restartAudioPolls < 3 ? "pending" : "complete";
            }
            if (delayedAudioClock != null) {
                audioPolls++;
                if (audioPolls == 1) return "pending";
                if (audioPolls == 2) {
                    // Presentation/completion events advance the actor while audio is stopping.
                    actorAt = 3_000_000_000L;
                    delayedAudioClock.nanos = 4_000_000_000L;
                }
            }
            return "complete";
        }
        @Override public boolean cleanupAfterAcknowledgement() {
            require(!operationId.isEmpty());
            if (lateAck) require(releaseAck);
            cleanups++;
            return !cleanupFailure;
        }
    }

    private static final class FakeCodec implements ExperimentSessionAndroidShell.Codec {
        ExperimentSessionPanelCoordinator.NativeCommand last;
        final long statusGeneration;
        int encodings;
        String rejectionReason = "MonotonicTimeRegression";
        FakeCodec() { this(0L); }
        FakeCodec(long generation) { statusGeneration = generation; }
        @Override public String statusCommand() { return "status"; }

        @Override public String command(ExperimentSessionPanelCoordinator.NativeCommand command,
                boolean start, long elapsedNanos, long utcNanos) {
            last = command;
            encodings++;
            return command.operation + ":" + command.operationId + "|" + elapsedNanos
                + "|" + utcNanos + "|" + command.expectedGeneration + "|encoding=" + encodings;
        }

        @Override public ExperimentSessionAndroidShell.SessionReadback parse(String response,
                ExperimentSessionPanelCoordinator.NativeCommand expected, boolean start) {
            if ("malformed".equals(response)) return null;
            if (ExperimentSessionAndroidShell.retryableReason(response)) {
                return new ExperimentSessionAndroidShell.SessionReadback(null, "rejected",
                    "unavailable", "unavailable", "unavailable", "not-requested", 0L,
                    -1L, "", response, "unknown", "", 0L, "", "");
            }
            boolean definitiveRejected = response.startsWith("monotonic-rejected");
            if (expected == null && (response.startsWith("terminal-") || definitiveRejected)) expected = last;
            String operationId = expected == null ? "" : expected.operationId;
            long generation = expected == null ? statusGeneration : expected.expectedGeneration;
            boolean terminal = response.startsWith("terminal-");
            String shutdown = ("terminal-complete".equals(response) || "terminal-unsaved".equals(response)
                    || "terminal-worker-closed-ack".equals(response))
                ? "complete" : ("terminal-error".equals(response) ? "error" : "not-requested");
            return new ExperimentSessionAndroidShell.SessionReadback(
                receipt(operationId, generation, !definitiveRejected),
                ("busy".equals(response) || "terminal-worker-closed-ack".equals(response)
                    || "monotonic-rejected".equals(response))
                    ? "rejected" : "queued".equals(response) ? "queued" : "accepted",
                "ready",
                "inventory-unavailable",
                "ready",
                shutdown,
                terminal ? 7L : 0L,
                terminal ? generation : -1L,
                terminal ? operationId : "",
                definitiveRejected ? rejectionReason
                    : "busy".equals(response) ? "terminal-admission-busy" : shutdown,
                "terminal-unsaved".equals(response) ? "unsaved" : "none", "", 1L,
                definitiveRejected ? "rejected" : "accepted", definitiveRejected ? rejectionReason : "none");
        }

        private static ExperimentSessionPanelCoordinator.NativeReceipt receipt(
                String operationId, long generation, boolean accepted) {
            return new ExperimentSessionPanelCoordinator.NativeReceipt(
                operationId, accepted, true, generation, 1L, "idle", "", false, false,
                "ready", true, 0L, 0L, 0L, 0L, true, true, false,
                0L, 0L, 0L, "none", 0L, "accepted");
        }
    }

    private static final class FakeClock implements ExperimentSessionAndroidShell.Clock {
        volatile long millis = 1_000L;
        volatile long nanos = 1_000_000_000L;
        @Override public long elapsedRealtimeMillis() { return millis; }
        @Override public long elapsedRealtimeNanos() { return nanos; }
        @Override public long utcNanos() { return 2_000_000_000L; }
    }

    private static final class DirectDispatcher
            implements ExperimentSessionAndroidShell.UiDispatcher {
        @Override public void post(Runnable runnable) { runnable.run(); }
        @Override public boolean isUiThread() { return false; }
    }

    private static final class Sink implements ExperimentSessionAndroidShell.UiSink {
        final CountDownLatch readback = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile ExperimentSessionAndroidShell.TerminalResult result;
        volatile int terminalCallbacks;

        @Override public boolean acceptsExperimentSessionCallbacks(long launchEpoch) {
            return launchEpoch > 0L;
        }

        @Override public void onExperimentSessionReadback(
                ExperimentSessionAndroidShell.SessionReadback ignored) {
            readback.countDown();
        }

        @Override public void onExperimentSessionTerminal(
                ExperimentSessionAndroidShell.TerminalResult value) {
            result = value;
            terminalCallbacks++;
            terminal.countDown();
        }
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("expected true");
    }

    private static void reject(boolean value) {
        if (value) throw new AssertionError("expected false");
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
