package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ExperimentSessionAndroidShellTest {
    public static void main(String[] args) throws Exception {
        localTerminalCurrentOwnerAndReplacement();
        localTerminalPausedCompletionReplaysOnSameSink();
        localTerminalBusyObservationRetainsPendingExit();
        localTerminalRetainedGenerationIdle();
        localTerminalQueuedReplayCannotReplaceNewCompletion();
        localTerminalFailureBarriers();
        localTerminalActiveRequiresSavedArchive();
        localTerminalUnknownNeverReplays();
        localTerminalPreDispatchRecovery();
        localTerminalAudioRecoveryRetainsCutoffIdentity();
        localTerminalScheduledAudioReadRecovery();
        replacedPresentationCannotDispatch();
        headlessArmWaitsForAudioReadiness();
        submittedCommandObserverSurvivesPanelReplacement();
        durableArmCorrelationSurvivesLaterAudioOperation();
        retainedDurableArmObservationSurvivesDeadline();
        headlessExplicitStatusIsDelivered();
        explicitStatusLifetimeFences();
        carrierStatusTargetFencesEligibilityToDelivery();
        explicitStatusReadCompletionIsNotAnAck();
        appObserverDoesNotReplacePanelAndRequiresNewNativeOwner();
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

    private static ExperimentSessionAndroidShell.LocalTerminalScope localScope(){
        return new ExperimentSessionAndroidShell.LocalTerminalScope(1,11,1,0,"","","",String.join("",java.util.Collections.nCopies(64,"a")),"none");
    }
    private static void awaitLocalFinish(Sink sink)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while((sink.result==null||!sink.result.shouldFinish)&&System.nanoTime()<end)Thread.sleep(10);
        require(sink.result!=null&&sink.result.shouldFinish&&sink.result.saved);
    }
    private static void localTerminalCurrentOwnerAndReplacement()throws Exception{
        FakeBridge bridge=new FakeBridge(false);FakeCodec codec=new FakeCodec();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),new DirectDispatcher());
        Sink old=new Sink(),current=new Sink(),replacement=new Sink();
        try{
            shell.beginExplicitLaunchEpoch(1);shell.attach(old);shell.attach(current);
            reject(shell.beginLocalTerminal(localScope(),old));equal(0,bridge.audioStops);
            require(shell.beginLocalTerminal(localScope(),current));reject(shell.beginLocalTerminal(localScope(),current));awaitLocalFinish(current);
            reject(shell.isCurrentUiAttachment(current,1));require(shell.isCurrentTerminalUiAttachment(current,1));
            shell.attach(replacement);awaitLocalFinish(replacement);reject(shell.isCurrentTerminalUiAttachment(current,1));
            equal(1,bridge.submissions);equal(1,bridge.audioStops);equal(1,bridge.cleanups);equal(1,codec.encodings);
            reject(shell.beginLocalTerminal(localScope(),replacement));
        }finally{shell.closeForTest();}
    }
    private static void localTerminalPausedCompletionReplaysOnSameSink()throws Exception{
        FakeBridge bridge=new FakeBridge(false);FakeCodec codec=new FakeCodec();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),new DirectDispatcher());Sink sink=new Sink(),stale=new Sink();
        try{
            shell.beginExplicitLaunchEpoch(1);shell.attach(stale);shell.attach(sink);sink.acceptTerminal=false;
            require(shell.beginLocalTerminal(localScope(),sink));
            require(sink.pausedTerminalAttempt.await(2,TimeUnit.SECONDS));
            require(shell.replayCurrentTerminal(sink,1));equal(0,sink.terminalCallbacks);reject(shell.replayCurrentTerminal(stale,1));
            sink.acceptTerminal=true;require(shell.replayCurrentTerminal(sink,1));awaitLocalFinish(sink);
            equal(1,sink.terminalCallbacks);equal(1,bridge.submissions);equal(1,bridge.cleanups);equal(1,codec.encodings);
            reject(shell.isCurrentUiAttachment(sink,1));require(shell.isCurrentTerminalUiAttachment(sink,1));
        }finally{shell.closeForTest();}
    }
    private static void localTerminalBusyObservationRetainsPendingExit()throws Exception{
        FakeBridge bridge=new FakeBridge(false);bridge.localBusyThenAck=true;FakeCodec codec=new FakeCodec();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),new DirectDispatcher());Sink sink=new Sink();
        try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(localScope(),sink));awaitLocalFinish(sink);require(bridge.localPostDispatchReads>=2);equal(1,bridge.submissions);equal(1,codec.encodings);equal(1,bridge.cleanups);}
        finally{shell.closeForTest();}
    }

    private static void localTerminalRetainedGenerationIdle()throws Exception{
        String hash=String.join("",java.util.Collections.nCopies(64,"a"));
        for(final String captured:new String[]{"none","saved"}){
            FakeBridge bridge=new FakeBridge(false);
            FakeCodec codec=new FakeCodec(1){
                @Override public ExperimentSessionAndroidShell.SessionReadback parse(String raw,ExperimentSessionPanelCoordinator.NativeCommand expected,boolean start){
                    ExperimentSessionAndroidShell.SessionReadback r=super.parse(raw,expected,start);
                    return new ExperimentSessionAndroidShell.SessionReadback(r.receipt,r.commandStatus,r.initializationStatus,r.inventoryStatus,r.recordingRootStatus,r.shutdownStatus,r.shutdownAckRevision,r.finalizedSessionGeneration,r.finalizedOperationId,r.detail,captured,r.recordingError,r.runtimeEpoch,r.lastOperationStatus,r.lastOperationReason);
                }
            };
            ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),new DirectDispatcher());Sink sink=new Sink();
            try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);ExperimentSessionAndroidShell.LocalTerminalScope scope=new ExperimentSessionAndroidShell.LocalTerminalScope(1,11,1,1,"","","",hash,captured);require(scope.idle);require(scope.idleWithoutRecording=="none".equals(captured));require(shell.beginLocalTerminal(scope,sink));awaitLocalFinish(sink);equal(1L,sink.result.sessionGeneration);equal(1,bridge.submissions);}
            finally{shell.closeForTest();}
        }
        for(String invalid:new String[]{"unsaved","error","unknown"}){
            try{new ExperimentSessionAndroidShell.LocalTerminalScope(1,11,1,1,"","","",hash,invalid);throw new AssertionError("invalid idle result admitted");}catch(IllegalArgumentException expected){}
        }
        try{new ExperimentSessionAndroidShell.LocalTerminalScope(1,11,1,1,"","condition-a",hash,hash,"saved");throw new AssertionError("partial identity admitted");}catch(IllegalArgumentException expected){}
    }

    private static void localTerminalQueuedReplayCannotReplaceNewCompletion()throws Exception{
        final java.util.concurrent.LinkedBlockingQueue<Runnable> queued=new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        final CountDownLatch posted=new CountDownLatch(5);
        ExperimentSessionAndroidShell.UiDispatcher dispatcher=new ExperimentSessionAndroidShell.UiDispatcher(){
            public void post(Runnable task){queued.add(task);posted.countDown();}public boolean isUiThread(){return false;}
        };
        FakeBridge bridge=new FakeBridge(false);bridge.throwStatusOnce=true;FakeCodec codec=new FakeCodec();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),dispatcher);Sink sink=new Sink();
        try{
            shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(localScope(),sink));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!shell.localTerminalRetryReady()&&System.nanoTime()<end)Thread.sleep(5);
            require(shell.localTerminalRetryReady());
            // Keep both the original pending delivery and its cached replay queued.
            while(!shell.replayCurrentTerminal(sink,1)&&System.nanoTime()<end)Thread.sleep(5);
            require(shell.resumePendingTerminal());require(posted.await(2,TimeUnit.SECONDS));
            Runnable task;while((task=queued.poll())!=null)task.run();
            // Only the newest receipt is delivered; stale queued pending receipts are suppressed.
            awaitLocalFinish(sink);equal(1,sink.terminalCallbacks);equal(1,bridge.submissions);equal(1,bridge.cleanups);
            require(shell.replayCurrentTerminal(sink,1));Runnable replay=queued.poll(1,TimeUnit.SECONDS);require(replay!=null);replay.run();require(sink.result.shouldFinish);equal(2,sink.terminalCallbacks);
            // Cache still owns the completed result; repeated observation never dispatches native work.
            equal(1,codec.encodings);
        }finally{shell.closeForTest();}
    }

    private static void localTerminalFailureBarriers()throws Exception{
        for(int failure=0;failure<4;failure++){
            FakeBridge bridge=new FakeBridge(failure==0);bridge.recordingFailure=failure==1;bridge.cleanupFailure=failure==2;bridge.localAudioFailure=failure==3;
            ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,new FakeCodec(),new FakeClock(),new DirectDispatcher());Sink sink=new Sink();
            try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(localScope(),sink));require(sink.terminal.await(2,TimeUnit.SECONDS));reject(sink.result.shouldFinish);reject(sink.result.saved);equal(1,bridge.cleanups);}
            finally{shell.closeForTest();}
        }
        FakeBridge bridge=new FakeBridge(false);bridge.localScopeCurrent=false;
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,new FakeCodec(),new FakeClock(),new DirectDispatcher());Sink sink=new Sink();
        try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(localScope(),sink));require(sink.terminal.await(2,TimeUnit.SECONDS));equal(0,bridge.audioStops);equal(0,bridge.submissions);reject(sink.result.saved);}
        finally{shell.closeForTest();}
    }
    private static void localTerminalActiveRequiresSavedArchive()throws Exception{
        for(final boolean saved:new boolean[]{false,true}){
            FakeBridge bridge=new FakeBridge(false);
            FakeCodec codec=new FakeCodec(1){
                @Override public ExperimentSessionAndroidShell.SessionReadback parse(String raw,ExperimentSessionPanelCoordinator.NativeCommand expected,boolean start){
                    ExperimentSessionAndroidShell.SessionReadback r=super.parse(raw,expected,start);
                    return new ExperimentSessionAndroidShell.SessionReadback(r.receipt,r.commandStatus,r.initializationStatus,r.inventoryStatus,r.recordingRootStatus,r.shutdownStatus,r.shutdownAckRevision,r.finalizedSessionGeneration,r.finalizedOperationId,r.detail,saved&&"complete".equals(r.shutdownStatus)?"saved":r.recordingResult,r.recordingError,r.runtimeEpoch,r.lastOperationStatus,r.lastOperationReason);
                }
            };
            ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),new DirectDispatcher());Sink sink=new Sink();
            String hash=String.join("",java.util.Collections.nCopies(64,"a"));
            try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(new ExperimentSessionAndroidShell.LocalTerminalScope(1,11,1,1,"utc-ns-00000000000000000042","condition-a",hash,hash,"none"),sink));require(sink.terminal.await(2,TimeUnit.SECONDS));require(sink.result.saved==saved&&sink.result.shouldFinish==saved);equal(1,bridge.submissions);}
            finally{shell.closeForTest();}
        }
    }

    private static void localTerminalUnknownNeverReplays()throws Exception{
        FakeBridge bridge=new FakeBridge(false);bridge.throwAfterLocalApply=true;FakeCodec codec=new FakeCodec();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),new DirectDispatcher());Sink sink=new Sink();
        try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(localScope(),sink));awaitLocalFinish(sink);equal(1,bridge.submissions);equal(1,codec.encodings);equal(1,bridge.cleanups);reject(shell.resumePendingTerminal());}
        finally{shell.closeForTest();}
    }
    private static void localTerminalPreDispatchRecovery()throws Exception{
        FakeBridge bridge=new FakeBridge(false);bridge.throwStatusOnce=true;FakeCodec codec=new FakeCodec();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),new DirectDispatcher());Sink sink=new Sink();
        try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(localScope(),sink));require(sink.terminal.await(2,TimeUnit.SECONDS));equal(0,bridge.submissions);reject(sink.result.saved);require(shell.resumePendingTerminal());awaitLocalFinish(sink);equal(1,bridge.audioStops);equal(1,bridge.submissions);equal(1,codec.encodings);}
        finally{shell.closeForTest();}
    }

    private static void localTerminalAudioRecoveryRetainsCutoffIdentity()throws Exception{
        FakeClock clock=new FakeClock();FakeBridge bridge=new FakeBridge(false);bridge.throwAudioOnce=true;bridge.localValidationClock=clock;FakeCodec codec=new FakeCodec();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,clock,new DirectDispatcher());Sink sink=new Sink();
        try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(localScope(),sink));require(sink.terminal.await(2,TimeUnit.SECONDS));equal(1,bridge.audioStops);equal(0,bridge.submissions);require(shell.localTerminalRetryReady());require(shell.resumePendingTerminal());awaitLocalFinish(sink);equal(1,bridge.audioStops);equal(1,bridge.submissions);equal(1,codec.encodings);equal(clock.nanos,bridge.firstDispatchAt);}
        finally{shell.closeForTest();}
    }

    private static void localTerminalScheduledAudioReadRecovery()throws Exception{
        FakeBridge bridge=new FakeBridge(false);bridge.throwScheduledAudioRead=true;FakeCodec codec=new FakeCodec();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,new FakeClock(),new DirectDispatcher());Sink sink=new Sink();
        try{shell.beginExplicitLaunchEpoch(1);shell.attach(sink);require(shell.beginLocalTerminal(localScope(),sink));require(sink.terminal.await(2,TimeUnit.SECONDS));equal(1,bridge.audioStops);equal(0,bridge.submissions);require(shell.localTerminalRetryReady());require(shell.resumePendingTerminal());awaitLocalFinish(sink);equal(1,bridge.audioStops);equal(1,bridge.submissions);equal(1,codec.encodings);}
        finally{shell.closeForTest();}
    }

    private static void replacedPresentationCannotDispatch() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), new FakeClock(), new DirectDispatcher());
        Sink panel = new Sink(), mesh = new Sink();
        final int[] dispatched = {0};
        try {
            shell.beginExplicitLaunchEpoch(1L);
            shell.attach(panel);
            require(shell.isCurrentUiAttachment(panel, 1L));
            shell.attach(mesh);
            // Both surfaces remain alive: only the attached shell presentation may dispatch.
            if (shell.isCurrentUiAttachment(panel, 1L)) dispatched[0]++;
            equal(0, dispatched[0]);
            require(shell.isCurrentUiAttachment(mesh, 1L));
            shell.attach(panel); // Retired sinks cannot recover authority by reattaching.
            reject(shell.isCurrentUiAttachment(panel, 1L));
            require(shell.isCurrentUiAttachment(mesh, 1L));
            shell.beginExplicitLaunchEpoch(2L);
            reject(shell.isCurrentUiAttachment(mesh, 1L));
            shell.detach(mesh);
            reject(shell.isCurrentUiAttachment(mesh, 2L));
        } finally { shell.closeForTest(); }
        System.out.println("Presentation replacement PASS old direct dispatch blocked / retired reattach / launch / detach");
    }

    private static void headlessArmWaitsForAudioReadiness() throws Exception {
        final java.util.concurrent.LinkedBlockingQueue<Runnable> callbacks = new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean audioReady = new java.util.concurrent.atomic.AtomicBoolean();
        final FakeClock clock = new FakeClock();
        final FakeBridge bridge = new FakeBridge(false);
        final ExperimentSessionPanelCoordinator panel = new ExperimentSessionPanelCoordinator();
        panel.acceptRuntimeEpoch(1L);
        final ExperimentSessionPanelCoordinator.NativeCommand arm = panel.arm("condition-a", 0);
        final String outerId = "1111111111111111";
        final String[] confirmed = {""};
        FakeCodec codec = new FakeCodec() {
            @Override public ExperimentSessionAndroidShell.SessionReadback parse(String raw,
                    ExperimentSessionPanelCoordinator.NativeCommand expected, boolean start) {
                boolean ready = audioReady.get();
                ExperimentSessionPanelCoordinator.NativeReceipt receipt = new ExperimentSessionPanelCoordinator.NativeReceipt(
                    ready ? "later-audio-prepared" : arm.operationId, true, false, 1L, ready ? 3L : 1L,
                    "active", ready ? "armed" : "arming", "condition-a", true, false,
                    "ready", true, 0L, 0L, 0L, 0L, true, true, false, 0L, 0L, 0L, "none", 0L, "none");
                return new ExperimentSessionAndroidShell.SessionReadback(receipt, "accepted", "ready", "ready",
                    "ready", "not-requested", 0L, -1L, "", "", "none", "", 1L, "accepted", "")
                    .withArmAcceptance(new ExperimentSessionAndroidShell.ArmAcceptance(arm.operationId, 1L, 1L, "condition-a", true));
            }
        };
        ExperimentSessionAndroidShell.UiSink processObserver = new ExperimentSessionAndroidShell.UiSink() {
            public boolean observesSubmittedCommand() { return true; }
            public boolean retainsDurableCommand(String id, long epoch) { return arm.operationId.equals(id) && epoch == 1L; }
            public boolean acceptsExperimentSessionCallbacks(long epoch) { return epoch == 1L; }
            public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback r) {
                panel.acceptNativeReadback(r.runtimeEpoch, r.receipt, r.statusReadCompletedNanos, clock.nanos, true, r.armAcceptance);
                ExperimentSessionPanelState state = panel.snapshot();
                if (r.armAcceptance.matches(arm.operationId, 1L, "condition-a", r)
                        && state.phase == ExperimentSessionPanelState.Phase.ARMED && state.pendingOperationId.isEmpty()) {
                    confirmed[0] = outerId;
                }
            }
            public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult r) { }
        };
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(bridge, codec, clock,
            new ExperimentSessionAndroidShell.UiDispatcher() {
                public void post(Runnable r) { callbacks.add(r); }
                public boolean isUiThread() { return false; }
            });
        try {
            shell.beginExplicitLaunchEpoch(1L);
            // No Activity/panel attached: only the submitted process observer owns the receipt.
            shell.submit(arm, true, processObserver);
            Runnable first = callbacks.poll(2, TimeUnit.SECONDS);
            require(first != null); first.run();
            equal("", confirmed[0]);
            require(panel.snapshot().phase == ExperimentSessionPanelState.Phase.ARMING);
            audioReady.set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (confirmed[0].isEmpty() && System.nanoTime() < deadline) {
                Runnable next = callbacks.poll(250, TimeUnit.MILLISECONDS);
                if (next != null) next.run();
            }
            if (!outerId.equals(confirmed[0])) throw new AssertionError("Headless Arm polling stopped before delayed audio readiness");
            equal(1, codec.encodings); // Polling never resubmits Arm or invents a new receipt ID.
        } finally { shell.closeForTest(); }
        System.out.println("Headless active/arming then delayed audio-ready / overwritten latest ID / original receipt / no replay PASS");
    }

    private static void retainedDurableArmObservationSurvivesDeadline() throws Exception {
        for(final String mode:new String[]{"retained","retired-carrier","wrong-id","retired-launch","ordinary"}) {
            final java.util.concurrent.LinkedBlockingQueue<Runnable> callbacks=new java.util.concurrent.LinkedBlockingQueue<>();
            final FakeClock clock=new FakeClock();clock.millis=40001L;clock.nanos=40001000000L;
            final FakeBridge bridge=new FakeBridge(false);
            final ExperimentSessionPanelCoordinator panel=new ExperimentSessionPanelCoordinator();
            panel.admitTrustedColdLaunch(1L);
            final ExperimentSessionPanelCoordinator.NativeCommand arm=panel.arm("condition-a",0);
            final String outerId="1111111111111111";final String[] confirmed={""};
            ExperimentSessionAndroidShell.Codec codec=new FakeCodec(){
                @Override public ExperimentSessionAndroidShell.SessionReadback parse(String raw,ExperimentSessionPanelCoordinator.NativeCommand expected,boolean start){
                    ExperimentSessionPanelCoordinator.NativeReceipt receipt=new ExperimentSessionPanelCoordinator.NativeReceipt(
                        "later-presentation",true,false,1L,7L,"active","running","condition-a",true,false,
                        "ready",true,0L,0L,0L,0L,true,true,false,0L,0L,0L,"none",0L,"none");
                    return new ExperimentSessionAndroidShell.SessionReadback(receipt,"accepted","ready","ready","ready","not-requested",0L,-1L,"","","none","",1L,"accepted","")
                        .withArmAcceptance(new ExperimentSessionAndroidShell.ArmAcceptance(arm.operationId,1L,1L,"condition-a",true));
                }
            };
            ExperimentSessionAndroidShell.UiSink sink=new ExperimentSessionAndroidShell.UiSink(){
                public boolean observesSubmittedCommand(){return true;}
                public boolean retainsDurableCommand(String id,long epoch){return "retained".equals(mode)&&arm.operationId.equals(id)&&epoch==1L;}
                public boolean acceptsExperimentSessionCallbacks(long epoch){return epoch==1L;}
                public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback r){
                    panel.acceptNativeReadback(r.runtimeEpoch,r.receipt,r.statusReadCompletedNanos,clock.nanos,true,r.armAcceptance);
                    require(r.armAcceptance.matches(arm.operationId,1L,"condition-a",r));
                    require(panel.snapshot().pendingOperationId.isEmpty());
                    require(panel.snapshot().phase==ExperimentSessionPanelState.Phase.RUNNING);
                    confirmed[0]=outerId;
                }
                public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult r){throw new AssertionError("Arm is not terminal save");}
            };
            ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(bridge,codec,clock,
                new ExperimentSessionAndroidShell.UiDispatcher(){public void post(Runnable r){callbacks.add(r);}public boolean isUiThread(){return false;}});
            try {
                shell.beginExplicitLaunchEpoch("retired-launch".equals(mode)?2L:1L);
                java.lang.reflect.Method poll=ExperimentSessionAndroidShell.class.getDeclaredMethod("scheduleStatusPoll",java.lang.ref.WeakReference.class,long.class,long.class,ExperimentSessionPanelCoordinator.NativeCommand.class,boolean.class);
                poll.setAccessible(true);
                ExperimentSessionPanelCoordinator.NativeCommand expected="wrong-id".equals(mode)?new ExperimentSessionPanelCoordinator.NativeCommand("arm","foreign-id",0L,"condition-a"):arm;
                poll.invoke(shell,new java.lang.ref.WeakReference<ExperimentSessionAndroidShell.UiSink>(sink),1L,20001L,expected,true);
                Runnable callback=callbacks.poll("retained".equals(mode)?2000L:600L,TimeUnit.MILLISECONDS);
                if("retained".equals(mode)){require(callback!=null);callback.run();equal(outerId,confirmed[0]);}
                else {require(callback==null);equal("",confirmed[0]);}
                equal(0,bridge.submissions); // Observing after40s never replays Arm.
            }finally{shell.closeForTest();}
        }
        System.out.println("Retained original Arm after40s / running durable witness / exactID / carrier and launch retirement / no replay PASS");
    }

    private static void durableArmCorrelationSurvivesLaterAudioOperation() throws Exception {
        ExperimentSessionPanelCoordinator.NativeReceipt receipt=new ExperimentSessionPanelCoordinator.NativeReceipt(
            "later-audio-prepared",true,false,1L,3L,"active","armed","condition-a",true,false,
            "ready",true,0L,0L,0L,0L,true,true,false,0L,0L,0L,"none",0L,"accepted");
        ExperimentSessionAndroidShell.SessionReadback readback=new ExperimentSessionAndroidShell.SessionReadback(
            receipt,"accepted","ready","ready","ready","not-requested",0L,-1L,"",
            "","none","",7L,"accepted","");
        ExperimentSessionAndroidShell.ArmAcceptance proof=new ExperimentSessionAndroidShell.ArmAcceptance("original-arm",1L,7L,"condition-a",true);
        readback=readback.withArmAcceptance(proof);
        require(proof.matches("original-arm",1L,"condition-a",readback));
        require(!receipt.durable); // Arm checkpoint never fabricates a finalized/save receipt.
        require(!proof.matches("different-arm",1L,"condition-a",readback));
        require(!proof.matches("original-arm",2L,"condition-a",readback));
        require(!proof.matches("original-arm",1L,"condition-b",readback));
        require(!new ExperimentSessionAndroidShell.ArmAcceptance("original-arm",1L,8L,"condition-a",true).matches("original-arm",1L,"condition-a",readback));
        require(!new ExperimentSessionAndroidShell.ArmAcceptance("original-arm",1L,7L,"condition-a",false).matches("original-arm",1L,"condition-a",readback));
        ExperimentSessionPanelCoordinator.NativeReceipt mismatchedCondition=new ExperimentSessionPanelCoordinator.NativeReceipt(
            "later-audio-prepared",true,false,1L,3L,"active","armed","condition-b",true,false,
            "ready",true,0L,0L,0L,0L,true,true,false,0L,0L,0L,"none",0L,"accepted");
        ExperimentSessionAndroidShell.SessionReadback mismatchedReadback=new ExperimentSessionAndroidShell.SessionReadback(
            mismatchedCondition,"accepted","ready","ready","ready","not-requested",0L,-1L,"",
            "","none","",7L,"accepted","").withArmAcceptance(proof);
        require(!proof.matches("original-arm",1L,"condition-a",mismatchedReadback));
        java.lang.reflect.Method poll=ExperimentSessionAndroidShell.class.getDeclaredMethod("needsCommandPoll",
            ExperimentSessionAndroidShell.SessionReadback.class,ExperimentSessionPanelCoordinator.NativeCommand.class,boolean.class);
        poll.setAccessible(true);
        ExperimentSessionPanelCoordinator.NativeCommand command=new ExperimentSessionPanelCoordinator.NativeCommand("arm","original-arm",0L,"condition-a");
        require(!((Boolean)poll.invoke(null,readback,command,true)));
        require((Boolean)poll.invoke(null,readback.withArmAcceptance(null),command,true));
        require((Boolean)poll.invoke(null,mismatchedReadback,command,true));
        for (boolean accepted : new boolean[]{true, false}) {
            ExperimentSessionPanelCoordinator.NativeReceipt exact = new ExperimentSessionPanelCoordinator.NativeReceipt(
                "original-arm", accepted, false, 1L, 3L, "active", "armed", "condition-a", true, false,
                "ready", true, 0L, 0L, 0L, 0L, true, true, false, 0L, 0L, 0L, "none", 0L, "none");
            ExperimentSessionAndroidShell.SessionReadback withoutCheckpoint = new ExperimentSessionAndroidShell.SessionReadback(
                exact, accepted ? "accepted" : "rejected", "ready", "ready", "ready", "not-requested", 0L, -1L, "",
                "", "none", "", 7L, accepted ? "accepted" : "rejected", "");
            // An exact latest ID alone cannot substitute for the durable checkpoint;
            // an exact definitive rejection still terminates observation.
            require(((Boolean)poll.invoke(null,withoutCheckpoint,command,true)) == accepted);
        }
        System.out.println("Durable original Arm / audio replacement / generation / runtime / condition / no save fabrication controls PASS");
    }

    private static void submittedCommandObserverSurvivesPanelReplacement() throws Exception {
        for (final boolean observer : new boolean[]{false,true}) {
            java.util.concurrent.LinkedBlockingQueue<Runnable> callbacks=new java.util.concurrent.LinkedBlockingQueue<>();
            final int[] originalReads={0},replacementReads={0};
            ExperimentSessionAndroidShell.UiSink submitted=new ExperimentSessionAndroidShell.UiSink(){
                public boolean observesSubmittedCommand(){return observer;}
                public boolean acceptsExperimentSessionCallbacks(long epoch){return epoch==1L;}
                public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback r){
                    if(!"exact-original".equals(r.receipt.operationId))throw new AssertionError("operation correlation changed");
                    originalReads[0]++;
                }
                public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult r){}
            };
            ExperimentSessionAndroidShell.UiSink replacement=new ExperimentSessionAndroidShell.UiSink(){
                public boolean acceptsExperimentSessionCallbacks(long epoch){return epoch==1L;}
                public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback r){replacementReads[0]++;}
                public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult r){}
            };
            ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(new FakeBridge(false),new FakeCodec(),new FakeClock(),
                new ExperimentSessionAndroidShell.UiDispatcher(){public void post(Runnable r){callbacks.add(r);}public boolean isUiThread(){return false;}});
            try {
                shell.beginExplicitLaunchEpoch(1L);
                if(!observer)shell.attach(submitted);
                shell.submit(new ExperimentSessionPanelCoordinator.NativeCommand("arm","exact-original",0L,""),false,submitted);
                Runnable callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);
                shell.attach(replacement);if(!observer)shell.detach(submitted);callback.run();
                equal(observer?1:0,originalReads[0]);equal(observer?0:1,replacementReads[0]);
                // A delivered command observer still cannot cross a launch replacement.
                shell.submit(new ExperimentSessionPanelCoordinator.NativeCommand("arm","exact-original",0L,""),false,submitted);
                callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);
                shell.beginExplicitLaunchEpoch(2L);callback.run();
                equal(observer?1:0,originalReads[0]);equal(observer?0:1,replacementReads[0]);
            }finally{shell.closeForTest();}
        }
        System.out.println("Submitted command observer / replacement / exact operation / launch fences PASS");
    }

    private static void headlessExplicitStatusIsDelivered() throws Exception {
        for(final long launch:new long[]{0L,1L}){
        java.util.concurrent.LinkedBlockingQueue<Runnable> callbacks=new java.util.concurrent.LinkedBlockingQueue<>();
        final int[] received={0};
        ExperimentSessionAndroidShell.UiSink remote=new ExperimentSessionAndroidShell.UiSink(){
            public boolean acceptsExperimentSessionCallbacks(long epoch){return epoch==launch;}
            public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback value){
                require(value.statusReadCompletedNanos>0L && "accepted".equals(value.commandStatus)); received[0]++;
            }
            public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult value){throw new AssertionError("status became terminal");}
        };
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(new FakeBridge(false),new FakeCodec(),new FakeClock(),
            new ExperimentSessionAndroidShell.UiDispatcher(){public void post(Runnable r){callbacks.add(r);}public boolean isUiThread(){return false;}});
        try {
            if(launch>0L)shell.beginExplicitLaunchEpoch(launch);shell.requestStatus(remote);
            Runnable callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);callback.run();
            if(received[0]!=1)throw new AssertionError("headless explicit status sink must receive completed native query exactly once");
            final int[] panelReads={0};
            shell.attach(new ExperimentSessionAndroidShell.UiSink(){
                public boolean acceptsExperimentSessionCallbacks(long epoch){return true;}
                public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback value){panelReads[0]++;}
                public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult value){}
            });
            shell.requestStatus(remote);callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);callback.run();
            equal(2,received[0]);equal(0,panelReads[0]);
        } finally {shell.closeForTest();}
        }
    }

    private static void explicitStatusLifetimeFences() throws Exception {
        for(int mode=0;mode<3;mode++){
            java.util.concurrent.LinkedBlockingQueue<Runnable> callbacks=new java.util.concurrent.LinkedBlockingQueue<>();
            final int[] reads={0}; final java.util.concurrent.atomic.AtomicLong generation=new java.util.concurrent.atomic.AtomicLong(1L);
            ExperimentSessionAndroidShell.UiSink sink=new ExperimentSessionAndroidShell.UiSink(){
                public boolean acceptsExperimentSessionCallbacks(long epoch){return epoch==1L;}
                public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback value){reads[0]++;}
                public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult value){}
            };
            ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(new FakeBridge(false),new FakeCodec(),new FakeClock(),
                new ExperimentSessionAndroidShell.UiDispatcher(){public void post(Runnable r){callbacks.add(r);}public boolean isUiThread(){return false;}});
            try{
                shell.beginExplicitLaunchEpoch(1L);
                shell.requestStatus(sink);
                Runnable callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);
                if(mode==0)shell.detach(sink);
                if(mode==1){
                    boolean cleared=false;
                    for(java.lang.reflect.Field field:callback.getClass().getDeclaredFields()){
                        if(java.lang.ref.WeakReference.class.isAssignableFrom(field.getType())){
                            field.setAccessible(true);((java.lang.ref.WeakReference<?>)field.get(callback)).clear();cleared=true;
                        }
                    }
                    require(cleared);
                }
                if(mode==2)shell.beginExplicitLaunchEpoch(2L);
                callback.run();equal(0,reads[0]);
            }finally{shell.closeForTest();}
        }
        System.out.println("ExplicitStatusLifetime PASS weak/detach/launch fences");
    }

    private static void carrierStatusTargetFencesEligibilityToDelivery() throws Exception {
        for(final int mode:new int[]{0,1,2}){
            FakeClock clock=new FakeClock();java.util.concurrent.LinkedBlockingQueue<Runnable> callbacks=new java.util.concurrent.LinkedBlockingQueue<>();
            java.util.concurrent.atomic.AtomicLong generation=new java.util.concurrent.atomic.AtomicLong(1L);
            java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
            ExperimentSessionAndroidShell.StatusObservationTarget target=new ExperimentSessionAndroidShell.StatusObservationTarget(mode==2?2L:1L,()->{
                boolean valid=generation.get()==1L;
                if(checks.incrementAndGet()==1&&mode<2)generation.set(mode==0?0L:2L);
                return valid;
            },clock::elapsedRealtimeNanos);
            ExperimentSessionAndroidShell.StatusObservationTarget replacement=new ExperimentSessionAndroidShell.StatusObservationTarget(1L,()->generation.get()==2L,clock::elapsedRealtimeNanos);
            ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(new FakeBridge(false),new FakeCodec(),clock,
                new ExperimentSessionAndroidShell.UiDispatcher(){public void post(Runnable r){callbacks.add(r);}public boolean isUiThread(){return false;}});
            try{
                shell.requestStatus(target);Runnable callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);callback.run();
                ExperimentSessionPanelCoordinator.NativeStatusSnapshot old=target.snapshot();
                require(old==null||!old.observed);require(replacement.snapshot()==null||!replacement.snapshot().observed);
                if(mode==1){shell.requestStatus(replacement);callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);callback.run();require(replacement.snapshot().observed);}
                target.retire();require(target.snapshot()==null);
            }finally{shell.closeForTest();}
        }
        System.out.println("CarrierStatusTarget PASS real callback end/replacement inside eligibility-to-delivery / wrong native epoch / replacement fresh query");
    }

    private static void appObserverDoesNotReplacePanelAndRequiresNewNativeOwner() throws Exception {
        FakeClock clock=new FakeClock();
        java.util.concurrent.LinkedBlockingQueue<Runnable> callbacks=new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.LinkedBlockingQueue<ExperimentSessionAndroidShell.SessionReadback> reads=new java.util.concurrent.LinkedBlockingQueue<>();
        ExperimentSessionAndroidShell shell=ExperimentSessionAndroidShell.createForTest(new FakeBridge(false),new FakeCodec(),clock,
            new ExperimentSessionAndroidShell.UiDispatcher(){
                public void post(Runnable action){callbacks.add(action);}
                public boolean isUiThread(){return false;}
            });
        ExperimentSessionAndroidShell.UiSink panel=new ExperimentSessionAndroidShell.UiSink(){
            public boolean acceptsExperimentSessionCallbacks(long epoch){return true;}
            public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback value){throw new AssertionError("app observation reached panel");}
            public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult value){throw new AssertionError("app observation changed terminal UI");}
        };
        try {
            shell.attach(panel);shell.openStatusObserver(1);
            shell.observeStatus(value->{require(value!=null);reads.add(value);});
            Runnable callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);callback.run();
            equal(clock.nanos,reads.poll(1,TimeUnit.SECONDS).statusReadCompletedNanos);
            shell.closeStatusObserver(1);shell.openStatusObserver(1);
            final boolean[] unavailable={false};shell.observeStatus(value->{unavailable[0]=value==null;});
            require(unavailable[0]);
            shell.openStatusObserver(2);shell.closeStatusObserver(1);
            shell.observeStatus(value->{require(value!=null);reads.add(value);});
            callback=callbacks.poll(2,TimeUnit.SECONDS);require(callback!=null);callback.run();
            require(reads.poll(1,TimeUnit.SECONDS)!=null);
            shell.closeStatusObserver(2);
        } finally {shell.closeForTest();}
    }

    private static void explicitStatusReadCompletionIsNotAnAck() throws Exception {
        FakeBridge bridge = new FakeBridge(false);
        FakeClock clock = new FakeClock();
        java.util.concurrent.LinkedBlockingQueue<Runnable> callbacks =
            new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        java.util.concurrent.LinkedBlockingQueue<ExperimentSessionAndroidShell.SessionReadback> received =
            new java.util.concurrent.LinkedBlockingQueue<ExperimentSessionAndroidShell.SessionReadback>();
        ExperimentSessionPanelCoordinator observed = new ExperimentSessionPanelCoordinator();
        ExperimentSessionAndroidShell.UiSink sink = new ExperimentSessionAndroidShell.UiSink() {
            public boolean acceptsExperimentSessionCallbacks(long epoch) { return epoch == 1L; }
            public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback value) {
                observed.acceptNativeReadback(value.runtimeEpoch, value.receipt,
                    value.statusReadCompletedNanos, clock.elapsedRealtimeNanos());
                received.add(value);
            }
            public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult value) {}
        };
        ExperimentSessionAndroidShell shell = ExperimentSessionAndroidShell.createForTest(
            bridge, new FakeCodec(), clock, new ExperimentSessionAndroidShell.UiDispatcher() {
                public void post(Runnable callback) { callbacks.add(callback); }
                public boolean isUiThread() { return false; }
            });
        try {
            shell.beginExplicitLaunchEpoch(1L); shell.attach(sink);
            shell.requestStatus(sink);
            Runnable callback = callbacks.poll(2L, TimeUnit.SECONDS); require(callback != null);
            clock.nanos = 2_000_000_000L;
            callback.run();
            ExperimentSessionAndroidShell.SessionReadback status = received.poll(1L, TimeUnit.SECONDS);
            require(status != null); equal(1_000_000_000L, status.statusReadCompletedNanos);
            equal(1_000_000_000L, observed.nativeReadbackObservation(clock.nanos).ageNanos);
            shell.submit(new ExperimentSessionPanelCoordinator.NativeCommand(
                "arm", "observation-arm", 0L, ""), false, sink);
            callback = callbacks.poll(2L, TimeUnit.SECONDS); require(callback != null); callback.run();
            ExperimentSessionAndroidShell.SessionReadback ack = received.poll(1L, TimeUnit.SECONDS);
            require(ack != null); equal(-1L, ack.statusReadCompletedNanos);
            require(!observed.nativeReadbackObservation(clock.nanos).available);
            // This new status callback is held, then fenced by an actual shell launch replacement.
            shell.requestStatus(sink);
            callback = callbacks.poll(2L, TimeUnit.SECONDS); require(callback != null);
            shell.beginExplicitLaunchEpoch(2L); callback.run();
            require(received.isEmpty());
            System.out.println("NativeStatusReadCompletion PASS worker timestamp / ACK excluded / launch fence");
        } finally { shell.closeForTest(); }
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
        boolean localBusyThenAck;int localPostDispatchReads;
        boolean throwScheduledAudioRead;int scheduledAudioReads;
        boolean localScopeCurrent=true,throwAfterLocalApply,throwStatusOnce,throwAudioOnce,localAudioFailure;FakeClock localValidationClock;
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
                if (lateAck || localBusyThenAck) return "queued";
                return recordingFailure ? "terminal-unsaved"
                    : failShutdown ? "terminal-error" : "terminal-complete";
            }
            if(throwStatusOnce){throwStatusOnce=false;throw new IllegalStateException("one-time-status-failure");}
            statusReads++;
            if (pendingRejectedReadback) {
                pendingRejectedReadback = false;
                rejectionDelivered = true;
                return "monotonic-rejected-status";
            }
            if (initialBusy && statusReads == 1) return "readback-busy";
            if(localBusyThenAck&&submissions>0)return ++localPostDispatchReads==1?"readback-busy":"terminal-complete";
            if (releaseAck) return "terminal-complete";
            return "status";
        }

        @Override public boolean validateLocalTerminalScope(ExperimentSessionAndroidShell.LocalTerminalScope scope){if(localValidationClock!=null)localValidationClock.nanos+=1000;return localScopeCurrent&&scope.runtimeEpoch==1;}
        @Override public boolean admitLocalTerminal(ExperimentSessionAndroidShell.LocalTerminalScope scope){return localScopeCurrent;}
        @Override public String applyLocalTerminal(String command,ExperimentSessionAndroidShell.LocalTerminalScope scope){
            if(!localScopeCurrent)throw new SecurityException("captured-owner-retired");String response=apply(command);
            if(throwAfterLocalApply){releaseAck=true;throw new IllegalStateException("accepted-response-lost");}return response;
        }
        @Override public boolean requestAudioStop(long generation, String operationId) {
            audioStops++;
            if(throwAudioOnce){throwAudioOnce=false;throw new IllegalStateException("audio-stop-response-unavailable");}
            return true;
        }

        @Override public boolean requestAudioRestartStop(long generation, String operationId) {
            audioStops++;
            return true;
        }

        @Override public String audioShutdownStatus() {
            if(throwScheduledAudioRead){scheduledAudioReads++;if(scheduledAudioReads==1)return "pending";if(scheduledAudioReads==2)throw new IllegalStateException("scheduled-audio-read-failed");}
            if(localAudioFailure)return "error";
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

    private static class FakeCodec implements ExperimentSessionAndroidShell.Codec {
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
        volatile boolean acceptTerminal=true;
        final CountDownLatch pausedTerminalAttempt=new CountDownLatch(1);
        @Override public boolean acceptsExperimentSessionTerminalCallbacks(long launchEpoch){boolean allowed=acceptTerminal&&acceptsExperimentSessionCallbacks(launchEpoch);if(!allowed)pausedTerminalAttempt.countDown();return allowed;}

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
