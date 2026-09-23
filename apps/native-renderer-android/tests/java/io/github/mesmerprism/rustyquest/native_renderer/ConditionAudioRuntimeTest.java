package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.ArrayList;
import java.util.List;

public final class ConditionAudioRuntimeTest {
    private static final String SHA_A =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--failed-app-lifetime-barrier".equals(args[0])) {
            cleanupFailureSurvivesCloseAndReinstall();
            System.out.println("ConditionAudioRuntimeTest failed-barrier PASS");
            return;
        }
        runNormalSuite();
        runNormalSuite();
        System.out.println("ConditionAudioRuntimeTest PASS");
    }

    private static void runNormalSuite() throws Exception {
        explicitPauseResumeRetainsPlayerAndPosition();
        missingInventoryIsUnavailable();
        untrustedInventoryIsUnavailable();
        lifecycleNaturalEndAndNoLoop();
        prepareStopAndLateCallbackRaces();
        prepareErrorAndGenerationFences();
        startupPreloadFailureIsTyped();
        startupPreloadGatesSelectionAndRestart();
        cleanupFailureNeverPublishesStopped();
        staleReceiptKeepsOriginalGenerationIdentity();
        stoppedReceiptSurvivesNewPrepareRace();
        appLifetimeOwnerIsUniqueThroughBlockedClose();
        closeReleasesPreloadsOnceAndRejectsLateReadiness();
    }

    private static void explicitPauseResumeRetainsPlayerAndPosition() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(inventory(), factory, receipts);
        try {
            check(runtime.submit(ConditionAudioContract.Command.prepare(1L, "arm", "condition-alpha")).accepted, "arm prepares");
            FakeBackend backend = factory.awaitBackend(1);
            backend.awaitPrepare();
            backend.listener.onPrepared();
            receipts.await(ConditionAudioContract.Event.PREPARED, 1L);
            check(backend.startCount == 0, "preparation never autoplays");
            check(!runtime.submit(ConditionAudioContract.Command.pause(1L, "pause-before-start")).accepted, "armed audio cannot pause");
            check(runtime.submit(ConditionAudioContract.Command.start(1L, "official-start")).accepted, "explicit start accepted");
            backend.awaitStart();
            backend.listener.onActualStart(0L);
            receipts.await(ConditionAudioContract.Event.ACTUAL_START, 1L);
            ConditionAudioContract.Command pause = ConditionAudioContract.Command.pause(1L, "pause");
            check(runtime.submit(pause).accepted, "pause accepted");
            ConditionAudioContract.Receipt paused = receipts.await(ConditionAudioContract.Event.PAUSED, 1L);
            check(paused.positionMs == 1234L, "pause reports actual player position");
            check(runtime.submit(pause).duplicate, "pause retry is idempotent");
            check(runtime.submit(ConditionAudioContract.Command.resume(1L, "resume")).accepted, "resume accepted");
            backend.listener.onActualStart(1234L);
            ConditionAudioContract.Receipt resumed = receipts.await(ConditionAudioContract.Event.RESUMED, 1L);
            check(resumed.positionMs == 1234L && backend.prepareCount == 1 && backend.stopCount == 0
                && backend.releaseCount == 0, "resume retains exact prepared player and position");
            backend.listener.onNaturalEnd(2000L);
            receipts.await(ConditionAudioContract.Event.NATURAL_END, 1L);
            check(runtime.submit(ConditionAudioContract.Command.pause(1L, "silent-pause")).accepted, "natural-end silence can pause");
            long deadline = System.currentTimeMillis() + 3000L;
            while (runtime.snapshot().phase != ConditionAudioContract.Phase.PAUSED && System.currentTimeMillis() < deadline) Thread.sleep(5L);
            check(runtime.submit(ConditionAudioContract.Command.resume(1L, "silent-resume")).accepted, "natural-end silence can resume");
            deadline = System.currentTimeMillis() + 3000L;
            while (runtime.snapshot().phase != ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END && System.currentTimeMillis() < deadline) Thread.sleep(5L);
            check(runtime.snapshot().phase == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END && backend.startCount == 2, "resume never replays a naturally ended track");
        } finally { runtime.close(); }
    }

    private static void closeReleasesPreloadsOnceAndRejectsLateReadiness() throws Exception {
        PreloadingFakeFactory factory = new PreloadingFakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(inventory(), factory,
            new ReceiptLog());
        factory.awaitPreload();
        runtime.close();
        runtime.close();
        check(runtime.awaitClosed(3000L), "preloads close off-thread before owner releases");
        check(factory.closeCount == 1, "each preloader closes exactly once");
        factory.ready("condition-alpha");
        check(!runtime.submit(ConditionAudioContract.Command.prepare(
            1L, "late-prepare", "condition-alpha")).accepted,
            "late preload cannot resurrect a closed owner");
    }

    private static void untrustedInventoryIsUnavailable() throws Exception {
        ConditionAudioContract.TrustedPackagedInventory rejected =
            ConditionAudioContract.TrustedPackagedInventory.fromValidatedProvider(
                "runtime-caller",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                new ConditionAudioContract.Provider[] {
                    new ConditionAudioContract.Provider(
                        "condition-alpha", "asset/audio-alpha", "audio-alpha",
                        SHA_A, 101L, "audio/ogg"
                    )
                }
            );
        check(!rejected.available && "inventory-unavailable".equals(rejected.reason),
            "caller-selected inventory is never trusted as packaged authority");
    }

    private static void appLifetimeOwnerIsUniqueThroughBlockedClose() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        ConditionAudioRuntime first = ConditionAudioRuntime.installAppLifetime(
            inventory(), factory, receipts
        );
        check(first.submit(ConditionAudioContract.Command.prepare(
            90L, "prepare-app-lifetime", "condition-alpha"
        )).accepted, "app-lifetime prepare accepted");
        FakeBackend backend = factory.awaitBackend(1);
        backend.awaitPrepare();
        backend.blockRelease();
        ConditionAudioRuntime second = ConditionAudioRuntime.installAppLifetime(
            inventory(), new FakeFactory(), new ReceiptLog()
        );
        check(first == second, "exactly one app-lifetime audio owner is installed");
        first.close();
        backend.awaitReleaseEntered();
        ConditionAudioRuntime whileClosing = ConditionAudioRuntime.installAppLifetime(
            inventory(), new FakeFactory(), new ReceiptLog()
        );
        check(whileClosing == first,
            "closing singleton remains the nonblocking barrier until backend cleanup completes");
        check(!whileClosing.submit(ConditionAudioContract.Command.prepare(
            91L, "prepare-overlap", "condition-beta"
        )).accepted, "closing barrier rejects overlapping replacement work");
        backend.unblockRelease();

        ConditionAudioRuntime replacement = null;
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            ConditionAudioRuntime candidate = ConditionAudioRuntime.installAppLifetime(
                inventory(), new FakeFactory(), new ReceiptLog()
            );
            if (candidate != first) {
                replacement = candidate;
                break;
            }
            Thread.sleep(10L);
        }
        check(replacement != null && replacement != first,
            "replacement installs only after owner-thread cleanup releases the barrier");
        replacement.close();
        awaitAppLifetimeReleased();
        check(ConditionAudioRuntime.appLifetimeReleasedForTest(),
            "successful app-lifetime close clears the singleton before another test begins");
    }

    private static void cleanupFailureSurvivesCloseAndReinstall() throws Exception {
        check(ConditionAudioRuntime.appLifetimeReleasedForTest(),
            "failed-cleanup barrier test starts with no inherited app-lifetime owner");
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        factory.throwStop = true;
        factory.throwRelease = true;
        ConditionAudioRuntime first = ConditionAudioRuntime.installAppLifetime(
            inventory(), factory, receipts
        );
        check(first.submit(ConditionAudioContract.Command.prepare(
            92L, "prepare-close-cleanup-failure", "condition-alpha"
        )).accepted, "app-lifetime cleanup-failure prepare accepted");
        FakeBackend backend = factory.awaitBackend(1);
        backend.awaitPrepare();
        check(first.submit(ConditionAudioContract.Command.stop(
            92L,
            "stop-close-cleanup-failure",
            ConditionAudioContract.StopReason.SAVE_AND_EXIT
        )).accepted, "app-lifetime cleanup-failure stop admitted");
        check(receipts.awaitOperation(
            ConditionAudioContract.Event.CLEANUP_FAILED,
            "stop-close-cleanup-failure"
        ) != null, "failed stop publishes its cleanup obligation before close");

        first.close();
        backend.awaitCleanupAttempts(2);
        ConditionAudioRuntime reinstall = ConditionAudioRuntime.installAppLifetime(
            inventory(), new FakeFactory(), new ReceiptLog()
        );
        check(reinstall == first,
            "failed close cleanup retains the app-lifetime barrier against reinstall");
        check(receipts.find(
            ConditionAudioContract.Event.STOPPED, 92L, "stop-close-cleanup-failure"
        ) == null, "failed cleanup followed by close never publishes STOPPED");
        check(backend.stopCount == 2 && backend.releaseCount == 2,
            "close retries the retained failed backend instead of replacing its owner");
        check(!ConditionAudioRuntime.appLifetimeReleasedForTest(),
            "failed cleanup deliberately retains the singleton barrier until process teardown");
    }

    private static void awaitAppLifetimeReleased() throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (!ConditionAudioRuntime.appLifetimeReleasedForTest()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        check(ConditionAudioRuntime.appLifetimeReleasedForTest(),
            "successful app-lifetime cleanup did not release the singleton barrier");
    }

    private static void startupPreloadGatesSelectionAndRestart() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        PreloadingFakeFactory factory = new PreloadingFakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(
            inventory(), factory, receipts
        );
        try {
            factory.awaitPreload();
            ConditionAudioContract.Submission pending = runtime.submit(
                ConditionAudioContract.Command.prepare(40L, "prepare-before-ready", "condition-alpha")
            );
            check(!pending.accepted && "audio-track-not-ready".equals(pending.reason),
                "startup preload rejects selection before its track is ready");

            factory.ready("condition-alpha");
            factory.fail("condition-beta");
            waitForPreloadResult(runtime, 40L);
            check(runtime.trackReadiness("condition-alpha").state
                    == ConditionAudioContract.TrackState.READY,
                "startup readiness exposes the ready condition without selecting it");
            check(runtime.trackReadiness("condition-beta").state
                    == ConditionAudioContract.TrackState.FAILED,
                "startup readiness exposes the independently failed condition");
            check(factory.preloadThread != null
                    && factory.preloadThread.getName().startsWith("rq-condition-audio-owner-"),
                "startup asset preparation runs on the audio owner, never a UI/render caller");
            check(!runtime.submit(ConditionAudioContract.Command.prepare(
                40L, "prepare-failed-track", "condition-beta"
            )).accepted, "one failed startup track cannot be selected");
            ConditionAudioContract.Submission alpha = runtime.submit(
                ConditionAudioContract.Command.prepare(40L, "prepare-ready-track", "condition-alpha")
            );
            check(alpha.accepted, "one ready startup track remains selectable after peer failure");
            FakeBackend first = factory.awaitBackend(1);
            first.awaitPrepare();
            ConditionAudioContract.Submission earlyStart = runtime.submit(
                ConditionAudioContract.Command.start(40L, "start-before-ready")
            );
            check(!earlyStart.accepted && "audio-track-not-ready".equals(earlyStart.reason),
                "start cannot race a selected track before its prepared callback");
            first.listener.onPrepared();
            check(receipts.await(ConditionAudioContract.Event.PREPARED, 40L) != null,
                "ready selected track publishes prepared");
            check(runtime.submit(ConditionAudioContract.Command.start(40L, "start-ready-track")).accepted,
                "prepared selected track starts once");
            first.awaitStart();
            first.listener.onActualStart(0L);
            first.listener.onNaturalEnd(12L);
            check(receipts.await(ConditionAudioContract.Event.NATURAL_END, 40L) != null,
                "natural completion becomes silence without ending the session");
            check(runtime.submit(ConditionAudioContract.Command.stop(
                40L, "stop-natural-completion", ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER
            )).accepted, "terminal stop remains explicit after natural completion");
            check(receipts.await(ConditionAudioContract.Event.STOPPED, 40L) != null,
                "terminal stop closes the first session");

            check(runtime.submit(ConditionAudioContract.Command.prepare(
                41L, "prepare-restart-ready-track", "condition-alpha"
            )).accepted, "a terminal session may restart with an already-ready track");
        } finally {
            runtime.close();
        }
    }

    private static void startupPreloadFailureIsTyped() throws Exception {
        PreloadingFakeFactory factory = new PreloadingFakeFactory();
        factory.throwPreload = true;
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(
            inventory(), factory, new ReceiptLog()
        );
        try {
            factory.awaitPreload();
            waitForTrackState(runtime, "condition-alpha", ConditionAudioContract.TrackState.FAILED);
            ConditionAudioContract.Submission rejected = runtime.submit(
                ConditionAudioContract.Command.prepare(
                    39L, "prepare-after-preload-failure", "condition-alpha"
                )
            );
            check(!rejected.accepted && "audio-track-preload-failed".equals(rejected.reason),
                "whole startup preload failure is a typed selection rejection");
        } finally {
            runtime.close();
        }
    }

    private static void waitForPreloadResult(ConditionAudioRuntime runtime, long generation)
            throws Exception {
        waitForTrackState(runtime, "condition-alpha", ConditionAudioContract.TrackState.READY);
        waitForTrackState(runtime, "condition-beta", ConditionAudioContract.TrackState.FAILED);
    }

    private static void waitForTrackState(
        ConditionAudioRuntime runtime,
        String conditionId,
        ConditionAudioContract.TrackState expected
    ) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            ConditionAudioContract.TrackReadiness readiness = runtime.trackReadiness(conditionId);
            if (readiness.state == expected) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("startup preload readiness did not reach runtime owner");
    }

    private static void missingInventoryIsUnavailable() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(null, factory, receipts);
        try {
            ConditionAudioContract.Submission submission = runtime.submit(
                ConditionAudioContract.Command.prepare(1L, "prepare-missing", "condition-alpha")
            );
            check(!submission.accepted && "inventory-unavailable".equals(submission.reason),
                "missing packaged inventory is typed unavailable");
            check(receipts.await(ConditionAudioContract.Event.REJECTED, 1L) != null,
                "unavailable command emits bound rejection receipt");
            check(factory.backends.isEmpty(), "unavailable inventory never creates media backend");
        } finally {
            runtime.close();
        }
    }

    private static void lifecycleNaturalEndAndNoLoop() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(inventory(), factory, receipts);
        Thread caller = Thread.currentThread();
        try {
            ConditionAudioContract.Submission prepare = runtime.submit(
                ConditionAudioContract.Command.prepare(1L, "prepare-1", "condition-alpha")
            );
            check(prepare.accepted && prepare.pending, "prepare accepted asynchronously");
            FakeBackend backend = factory.awaitBackend(1);
            backend.awaitPrepare();
            check(backend.platformThread != caller, "media platform work stays off caller/UI thread");
            check(backend.platformThread.getName().startsWith("rq-condition-audio-owner-"),
                "media platform work runs on the dedicated audio owner");
            check(!backend.looping, "backend is explicitly non-looping");
            check(receipts.await(ConditionAudioContract.Event.PREPARE_ACCEPTED, 1L) != null,
                "prepare accepted receipt");

            ConditionAudioContract.Submission duplicatePrepare = runtime.submit(
                ConditionAudioContract.Command.prepare(1L, "prepare-1", "condition-alpha")
            );
            check(duplicatePrepare.accepted && duplicatePrepare.duplicate,
                "exact duplicate operation is idempotent");
            check(factory.backends.size() == 1 && backend.prepareCount == 1,
                "duplicate prepare does not replay platform work");

            backend.listener.onPrepared();
            check(receipts.await(ConditionAudioContract.Event.PREPARED, 1L) != null,
                "prepared callback receipt");
            ConditionAudioContract.Submission start = runtime.submit(
                ConditionAudioContract.Command.start(1L, "start-1")
            );
            check(start.accepted, "start accepted after exact prepare");
            backend.awaitStart();
            check(receipts.await(ConditionAudioContract.Event.START_ACCEPTED, 1L) != null,
                "start accepted receipt");
            backend.listener.onActualStart(7L);
            check(receipts.await(ConditionAudioContract.Event.ACTUAL_START, 1L) != null,
                "actual playback start receipt");
            backend.listener.onProgress(250L);
            ConditionAudioContract.Receipt progress = receipts.await(
                ConditionAudioContract.Event.PROGRESS, 1L
            );
            check(progress != null && progress.positionMs == 250L,
                "progress receipt preserves observed position");

            check(runtime.submit(
                ConditionAudioContract.Command.thresholdReached(1L, "threshold-1")
            ).accepted, "threshold observation admitted");
            check(receipts.await(ConditionAudioContract.Event.THRESHOLD_CONTINUES, 1L) != null,
                "threshold explicitly leaves audio running");
            check(backend.stopCount == 0, "threshold never stops audio");

            backend.listener.onNaturalEnd(500L);
            ConditionAudioContract.Receipt natural = receipts.await(
                ConditionAudioContract.Event.NATURAL_END, 1L
            );
            check(natural != null && runtime.snapshot().phase
                    == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END,
                "natural end becomes silence without ending session");
            check("condition-alpha".equals(natural.conditionId)
                    && SHA_A.equals(natural.sourceSha256)
                    && "start-1".equals(natural.operationId),
                "natural-end receipt binds provider, generation, and start operation");
            check(backend.startCount == 1 && backend.prepareCount == 1,
                "natural end never auto-loops or restarts");
            int afterNaturalEnd = receipts.size();
            backend.listener.onProgress(999L);
            backend.listener.onError("late-after-natural-end");
            Thread.sleep(100L);
            check(receipts.size() == afterNaturalEnd
                    && runtime.snapshot().phase
                        == ConditionAudioContract.Phase.SILENT_AFTER_NATURAL_END,
                "late callbacks after natural end cannot revive or fail silent session audio");
            check(runtime.submit(
                ConditionAudioContract.Command.thresholdReached(1L, "threshold-after-end")
            ).accepted, "session threshold remains independent after natural audio end");
            check(runtime.submit(ConditionAudioContract.Command.stop(
                1L,
                "stop-b",
                ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER
            )).accepted, "B stop admitted after natural end");
            check(receipts.await(ConditionAudioContract.Event.STOPPED, 1L) != null,
                "B stop receipt closes audio owner for the generation");
        } finally {
            runtime.close();
        }
    }

    private static void prepareStopAndLateCallbackRaces() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(inventory(), factory, receipts);
        try {
            runtime.submit(ConditionAudioContract.Command.prepare(
                1L, "prepare-race", "condition-alpha"
            ));
            FakeBackend oldBackend = factory.awaitBackend(1);
            oldBackend.awaitPrepare();
            check(runtime.submit(ConditionAudioContract.Command.stop(
                1L,
                "stop-home",
                ConditionAudioContract.StopReason.SAVE_AND_EXIT
            )).accepted, "Home stop admitted while prepare is pending");
            check(receipts.await(ConditionAudioContract.Event.STOPPED, 1L) != null,
                "pending prepare is stopped asynchronously");
            int receiptCountAtStop = receipts.size();
            oldBackend.listener.onPrepared();
            oldBackend.listener.onActualStart(1L);
            oldBackend.listener.onProgress(2L);
            oldBackend.listener.onNaturalEnd(3L);
            oldBackend.listener.onError("late-error");
            Thread.sleep(100L);
            check(receipts.size() == receiptCountAtStop,
                "all callbacks from stopped epoch are ignored");
            check(runtime.snapshot().phase == ConditionAudioContract.Phase.STOPPED,
                "late prepare/error callbacks cannot escape stopped state");

            runtime.submit(ConditionAudioContract.Command.prepare(
                2L, "prepare-new", "condition-beta"
            ));
            FakeBackend newBackend = factory.awaitBackend(2);
            newBackend.awaitPrepare();
            oldBackend.listener.onProgress(99L);
            oldBackend.listener.onError("old-generation-error");
            Thread.sleep(100L);
            check(runtime.snapshot().sessionGeneration == 2L
                    && runtime.snapshot().phase == ConditionAudioContract.Phase.PREPARING,
                "old generation callbacks cannot mutate replacement session");
            check(!runtime.submit(ConditionAudioContract.Command.start(1L, "stale-start")).accepted,
                "stale generation command rejects");
            newBackend.listener.onPrepared();
            check(receipts.await(ConditionAudioContract.Event.PREPARED, 2L) != null,
                "replacement prepare remains live");
        } finally {
            runtime.close();
        }
    }

    private static void prepareErrorAndGenerationFences() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(inventory(), factory, receipts);
        try {
            runtime.submit(ConditionAudioContract.Command.prepare(
                3L, "prepare-error", "condition-alpha"
            ));
            FakeBackend backend = factory.awaitBackend(1);
            backend.awaitPrepare();
            backend.listener.onError("decode-failed");
            ConditionAudioContract.Receipt error = receipts.await(
                ConditionAudioContract.Event.ERROR, 3L
            );
            check(error != null && "decode-failed".equals(error.reason),
                "prepare error is typed and generation-bound");
            int afterError = receipts.size();
            backend.listener.onError("duplicate-error");
            backend.listener.onPrepared();
            Thread.sleep(100L);
            check(receipts.size() == afterError,
                "error terminal epoch rejects duplicate and late callbacks");
            ConditionAudioContract.Submission conflictingReplay = runtime.submit(
                ConditionAudioContract.Command.start(3L, "prepare-error")
            );
            check(!conflictingReplay.accepted
                    && "operation-id-conflict".equals(conflictingReplay.reason),
                "operation token cannot be replayed for another command");
            check(!runtime.submit(ConditionAudioContract.Command.prepare(
                3L, "prepare-same-generation", "condition-beta"
            )).accepted, "same generation cannot replace failed audio authority");
            ConditionAudioContract.Submission unclosedReplacement = runtime.submit(
                ConditionAudioContract.Command.prepare(
                    4L, "prepare-unclosed", "condition-beta"
                )
            );
            check(!unclosedReplacement.accepted
                    && "audio-session-not-stopped".equals(unclosedReplacement.reason),
                "new generation cannot replace audio before explicit B/Home stop");
            ConditionAudioContract.Submission duplicateRejected = runtime.submit(
                ConditionAudioContract.Command.prepare(
                    3L, "prepare-same-generation", "condition-beta"
                )
            );
            check(!duplicateRejected.accepted && duplicateRejected.duplicate,
                "duplicate rejected command remains rejected");
        } finally {
            runtime.close();
        }
    }

    private static void cleanupFailureNeverPublishesStopped() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        factory.throwStop = true;
        factory.throwRelease = true;
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(
            inventory(), factory, receipts
        );
        try {
            check(runtime.submit(ConditionAudioContract.Command.prepare(
                10L, "prepare-cleanup-failure", "condition-alpha"
            )).accepted, "cleanup-failure prepare accepted");
            FakeBackend backend = factory.awaitBackend(1);
            backend.awaitPrepare();
            check(runtime.submit(ConditionAudioContract.Command.stop(
                10L,
                "stop-cleanup-failure",
                ConditionAudioContract.StopReason.SAVE_AND_EXIT
            )).accepted, "cleanup-failure stop admitted");
            ConditionAudioContract.Receipt failed = receipts.await(
                ConditionAudioContract.Event.CLEANUP_FAILED, 10L
            );
            check(failed != null
                    && "cleanup-stop-and-release-failed".equals(failed.reason)
                    && "condition-alpha".equals(failed.conditionId)
                    && SHA_A.equals(failed.sourceSha256),
                "stop and release failures aggregate into one typed bound receipt");
            check(receipts.find(
                ConditionAudioContract.Event.STOPPED, 10L, "stop-cleanup-failure"
            ) == null, "failed cleanup never reports successful STOPPED");
            check(runtime.snapshot().phase == ConditionAudioContract.Phase.ERROR
                    && "cleanup-stop-and-release-failed".equals(runtime.snapshot().reason),
                "cleanup failure leaves typed error state");
            check(runtime.submit(ConditionAudioContract.Command.stop(
                10L,
                "stop-cleanup-failure-retry",
                ConditionAudioContract.StopReason.SAVE_AND_EXIT
            )).accepted, "ERROR state permits an explicit cleanup retry");
            ConditionAudioContract.Receipt retryFailed = receipts.awaitOperation(
                ConditionAudioContract.Event.CLEANUP_FAILED,
                "stop-cleanup-failure-retry"
            );
            check(retryFailed != null
                    && "cleanup-stop-and-release-failed".equals(retryFailed.reason),
                "second STOP retries and preserves the outstanding cleanup failure");
            check(receipts.find(
                ConditionAudioContract.Event.STOPPED, 10L, "stop-cleanup-failure-retry"
            ) == null, "second STOP cannot publish STOPPED while cleanup still fails");
            check(backend.stopCount == 2 && backend.releaseCount == 2,
                "the exact retained backend is retried for both stop and release");
        } finally {
            runtime.close();
        }
    }

    private static void staleReceiptKeepsOriginalGenerationIdentity() throws Exception {
        ReceiptLog receipts = new ReceiptLog();
        FakeFactory factory = new FakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(
            inventory(), factory, receipts
        );
        try {
            runtime.submit(ConditionAudioContract.Command.prepare(
                1L, "prepare-identity-one", "condition-alpha"
            ));
            FakeBackend first = factory.awaitBackend(1);
            first.awaitPrepare();
            runtime.submit(ConditionAudioContract.Command.stop(
                1L, "stop-identity-one",
                ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER
            ));
            check(receipts.await(ConditionAudioContract.Event.STOPPED, 1L) != null,
                "generation one stopped before replacement");

            runtime.submit(ConditionAudioContract.Command.prepare(
                2L, "prepare-identity-two", "condition-beta"
            ));
            FakeBackend second = factory.awaitBackend(2);
            second.awaitPrepare();
            ConditionAudioContract.Submission stale = runtime.submit(
                ConditionAudioContract.Command.start(1L, "stale-generation-one")
            );
            check(!stale.accepted && "stale-generation".equals(stale.reason),
                "generation one command rejects while generation two is active");
            ConditionAudioContract.Receipt rejected = receipts.awaitOperation(
                ConditionAudioContract.Event.REJECTED, "stale-generation-one"
            );
            check(rejected != null
                    && rejected.sessionGeneration == 1L
                    && "condition-alpha".equals(rejected.conditionId)
                    && SHA_A.equals(rejected.sourceSha256),
                "stale rejection retains generation-one identity, never mutable generation two");
        } finally {
            runtime.close();
        }
    }

    private static void stoppedReceiptSurvivesNewPrepareRace() throws Exception {
        ReceiptLog receipts = new ReceiptLog(ConditionAudioContract.Event.STOPPED);
        FakeFactory factory = new FakeFactory();
        ConditionAudioRuntime runtime = ConditionAudioRuntime.createForTest(
            inventory(), factory, receipts
        );
        try {
            runtime.submit(ConditionAudioContract.Command.prepare(
                20L, "prepare-stop-race", "condition-alpha"
            ));
            factory.awaitBackend(1).awaitPrepare();
            runtime.submit(ConditionAudioContract.Command.stop(
                20L, "stop-race",
                ConditionAudioContract.StopReason.RESTART_TO_EXPERIMENTER
            ));
            receipts.awaitBlocked();
            check(runtime.submit(ConditionAudioContract.Command.prepare(
                21L, "prepare-during-stopped-receipt", "condition-beta"
            )).accepted, "new prepare admits while prior STOPPED callback is blocked");
            receipts.unblock();
            ConditionAudioContract.Receipt stopped = receipts.awaitOperation(
                ConditionAudioContract.Event.STOPPED, "stop-race"
            );
            check(stopped != null
                    && stopped.sessionGeneration == 20L
                    && "condition-alpha".equals(stopped.conditionId)
                    && SHA_A.equals(stopped.sourceSha256)
                    && stopped.positionMs == 0L,
                "STOPPED receipt remains immutable across concurrent replacement admission");
            factory.awaitBackend(2).awaitPrepare();
        } finally {
            receipts.unblock();
            runtime.close();
        }
    }

    private static ConditionAudioContract.TrustedPackagedInventory inventory() {
        return ConditionAudioContract.TrustedPackagedInventory.fromValidatedProvider(
            ConditionAudioContract.PACKAGED_PROVIDER_ORIGIN,
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            new ConditionAudioContract.Provider[] {
                new ConditionAudioContract.Provider(
                    "condition-alpha", "asset/audio-alpha", "audio-alpha", SHA_A, 101L, "audio/ogg"
                ),
                new ConditionAudioContract.Provider(
                    "condition-beta", "asset/audio-beta", "audio-beta", SHA_B, 202L, "audio/ogg"
                )
            }
        );
    }

    private static class FakeFactory implements ConditionAudioRuntime.MediaBackendFactory {
        final List<FakeBackend> backends = new ArrayList<FakeBackend>();
        boolean throwStop;
        boolean throwRelease;

        @Override
        public synchronized ConditionAudioRuntime.MediaBackend create(
            ConditionAudioContract.Provider provider,
            ConditionAudioRuntime.MediaBackend.Listener listener
        ) {
            FakeBackend backend = new FakeBackend(
                provider, listener, throwStop, throwRelease
            );
            backends.add(backend);
            notifyAll();
            return backend;
        }

        synchronized FakeBackend awaitBackend(int expected) throws Exception {
            long deadline = System.currentTimeMillis() + 3000L;
            while (backends.size() < expected && System.currentTimeMillis() < deadline) {
                wait(25L);
            }
            check(backends.size() >= expected, "backend creation timed out");
            return backends.get(expected - 1);
        }
    }

    private static final class PreloadingFakeFactory extends FakeFactory
            implements ConditionAudioRuntime.StartupPreloader {
        ConditionAudioRuntime.StartupPreloader.Listener listener;
        ConditionAudioContract.Provider[] providers;
        boolean preloadEntered;
        Thread preloadThread;
        boolean throwPreload;
        volatile int closeCount;
        @Override public void closePreloads() { closeCount++; }

        @Override public synchronized void preload(
            ConditionAudioContract.Provider[] providers,
            ConditionAudioRuntime.StartupPreloader.Listener listener
        ) {
            this.providers = providers;
            this.listener = listener;
            preloadEntered = true;
            preloadThread = Thread.currentThread();
            notifyAll();
            if (throwPreload) throw new IllegalStateException("deterministic-preload-failure");
        }

        synchronized void awaitPreload() throws Exception {
            long deadline = System.currentTimeMillis() + 3000L;
            while (!preloadEntered && System.currentTimeMillis() < deadline) wait(25L);
            check(preloadEntered, "startup preloader did not run on the audio owner");
        }

        synchronized void ready(String conditionId) {
            listener.onTrackReady(provider(conditionId));
        }

        synchronized void fail(String conditionId) {
            listener.onTrackFailed(provider(conditionId), "deterministic-preload-failure");
        }

        private ConditionAudioContract.Provider provider(String conditionId) {
            for (ConditionAudioContract.Provider provider : providers) {
                if (conditionId.equals(provider.conditionId)) return provider;
            }
            throw new AssertionError("missing startup provider " + conditionId);
        }
    }

    private static final class FakeBackend implements ConditionAudioRuntime.MediaBackend {
        @Override public long pause() { return 1234L; }
        final ConditionAudioContract.Provider provider;
        final Listener listener;
        volatile int prepareCount;
        volatile int startCount;
        volatile int stopCount;
        volatile int releaseCount;
        volatile boolean looping = true;
        volatile Thread platformThread;
        final boolean throwStop;
        final boolean throwRelease;
        boolean releaseBlocked;
        boolean releaseEntered;
        boolean releaseAllowed;

        FakeBackend(
            ConditionAudioContract.Provider provider,
            Listener listener,
            boolean throwStop,
            boolean throwRelease
        ) {
            this.provider = provider;
            this.listener = listener;
            this.throwStop = throwStop;
            this.throwRelease = throwRelease;
        }

        @Override public synchronized void prepare(boolean looping) {
            this.looping = looping;
            this.prepareCount += 1;
            this.platformThread = Thread.currentThread();
            notifyAll();
        }

        @Override public synchronized void start() {
            startCount += 1;
            platformThread = Thread.currentThread();
            notifyAll();
        }

        @Override public synchronized void stop() {
            stopCount += 1;
            platformThread = Thread.currentThread();
            notifyAll();
            if (throwStop) throw new IllegalStateException("deterministic-stop-failure");
        }

        @Override public synchronized void release() {
            releaseCount += 1;
            platformThread = Thread.currentThread();
            releaseEntered = true;
            notifyAll();
            while (releaseBlocked && !releaseAllowed) {
                try {
                    wait(25L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("release-interrupted", error);
                }
            }
            if (throwRelease) throw new IllegalStateException("deterministic-release-failure");
        }

        synchronized void awaitPrepare() throws Exception {
            waitForCount(true);
        }

        synchronized void awaitStart() throws Exception {
            waitForCount(false);
        }

        synchronized void blockRelease() {
            releaseBlocked = true;
            releaseAllowed = false;
        }

        synchronized void awaitReleaseEntered() throws Exception {
            long deadline = System.currentTimeMillis() + 3000L;
            while (!releaseEntered && System.currentTimeMillis() < deadline) {
                wait(25L);
            }
            check(releaseEntered, "release did not enter blocked backend");
        }

        synchronized void unblockRelease() {
            releaseAllowed = true;
            notifyAll();
        }

        synchronized void awaitCleanupAttempts(int expected) throws Exception {
            long deadline = System.currentTimeMillis() + 3000L;
            while ((stopCount < expected || releaseCount < expected)
                    && System.currentTimeMillis() < deadline) {
                wait(25L);
            }
            check(stopCount >= expected && releaseCount >= expected,
                "cleanup retry did not reach retained backend");
        }

        private void waitForCount(boolean prepare) throws Exception {
            long deadline = System.currentTimeMillis() + 3000L;
            while ((prepare ? prepareCount : startCount) == 0
                    && System.currentTimeMillis() < deadline) {
                wait(25L);
            }
            check((prepare ? prepareCount : startCount) > 0, "platform call timed out");
        }
    }

    private static final class ReceiptLog implements ConditionAudioContract.ReceiptSink {
        final List<ConditionAudioContract.Receipt> receipts =
            new ArrayList<ConditionAudioContract.Receipt>();
        final ConditionAudioContract.Event blockEvent;
        boolean callbackBlocked;
        boolean callbackReleased;

        ReceiptLog() {
            this(null);
        }

        ReceiptLog(ConditionAudioContract.Event blockEvent) {
            this.blockEvent = blockEvent;
        }

        @Override public synchronized void onReceipt(ConditionAudioContract.Receipt receipt) {
            if (receipt.event == blockEvent && !callbackReleased) {
                callbackBlocked = true;
                notifyAll();
                while (!callbackReleased) {
                    try {
                        wait(25L);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            receipts.add(receipt);
            notifyAll();
        }

        synchronized void awaitBlocked() throws Exception {
            long deadline = System.currentTimeMillis() + 3000L;
            while (!callbackBlocked && System.currentTimeMillis() < deadline) {
                wait(25L);
            }
            check(callbackBlocked, "receipt callback did not enter deterministic block");
        }

        synchronized void unblock() {
            callbackReleased = true;
            notifyAll();
        }

        synchronized ConditionAudioContract.Receipt await(
            ConditionAudioContract.Event event,
            long generation
        ) throws Exception {
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                for (ConditionAudioContract.Receipt receipt : receipts) {
                    if (receipt.event == event && receipt.sessionGeneration == generation) {
                        return receipt;
                    }
                }
                wait(25L);
            }
            return null;
        }

        synchronized int size() { return receipts.size(); }

        synchronized ConditionAudioContract.Receipt awaitOperation(
            ConditionAudioContract.Event event,
            String operationId
        ) throws Exception {
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                ConditionAudioContract.Receipt found = find(event, -1L, operationId);
                if (found != null) return found;
                wait(25L);
            }
            return null;
        }

        synchronized ConditionAudioContract.Receipt find(
            ConditionAudioContract.Event event,
            long generation,
            String operationId
        ) {
            for (ConditionAudioContract.Receipt receipt : receipts) {
                if (receipt.event == event
                        && (generation < 0L || receipt.sessionGeneration == generation)
                        && operationId.equals(receipt.operationId)) {
                    return receipt;
                }
            }
            return null;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
