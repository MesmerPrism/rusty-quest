package io.github.mesmerprism.rustyquest.media;

/** Host-only checks that drive the production owner transition implementation. */
public final class MediaOwnerLifecycleAdversarialMain {
    public static void main(String[] args) {
        partialStartCompensatesAndStopIsIdempotent();
        asynchronousFailureInvalidatesReadback();
        terminalStopRecreatesBeforeFreshArm();
        pendingStopCannotRecreate();
        staleGenerationCannotRecreate();
        duplicateArmCannotRecreate();
        incomingBarrierBlocksRecreate();
        actualPipelineRecreatesDistinctRuntime();
        actualCaptureSubscriptionRejectsStaleOrLiveIncarnation();
        neverEnteredOwnerStillCleansIndependentResources();
        System.out.println("rusty.quest.android.media.owner-lifecycle.v1:pass");
    }

    private static void partialStartCompensatesAndStopIsIdempotent() {
        FakePipeline pipeline = new FakePipeline();
        pipeline.failProcessor = true;
        PackedStereoMediaOwnerSet owners = new PackedStereoMediaOwnerSet(31, pipeline);
        expectArgumentFailure(() -> owners.provider("sink"));
        MediaOwnerProvider processor = owners.provider("processor");
        CancellationHandle cancellation = new CancellationHandle(31);
        MediaOwnerAction action = action(31, "processor", "start");
        expectFailure(() -> execute(processor, action, cancellation));
        require(pipeline.processorStarts == 1, "processor stage was not attempted");
        compensate(processor, action, cancellation);
        compensate(processor, action, cancellation);
        require(pipeline.stopCalls == 2 && pipeline.closed, "partial pipeline was not idempotently aborted");
        require("stopped".equals(processor.snapshot().state()), "compensated owner is not stopped");
    }

    private static void asynchronousFailureInvalidatesReadback() {
        FakePipeline pipeline = new FakePipeline();
        PackedStereoMediaOwnerSet owners = new PackedStereoMediaOwnerSet(41, pipeline);
        MediaOwnerProvider source = owners.provider("source");
        CancellationHandle cancellation = new CancellationHandle(41);
        MediaOwnerAction action = action(41, "source", "start");
        MediaProviderReadback readback = execute(source, action, cancellation);
        require("started".equals(readback.observedState()), "source did not start");
        pipeline.now = 101L;
        require("started".equals(source.snapshot().state()), "fresh source invalidated early");
        pipeline.now = 201L;
        MediaRuntimeSnapshot snapshot = source.snapshot();
        require("failed".equals(snapshot.state()), "live pipeline failure did not invalidate owner state");
        require(!snapshot.terminal(), "live failed resources were reported terminal");
    }

    private static void terminalStopRecreatesBeforeFreshArm() {
        FakePipeline pipeline=new FakePipeline();
        PackedStereoMediaOwnerSet owners=new PackedStereoMediaOwnerSet(51,pipeline);
        MediaOwnerProvider cleanup=owners.provider("cleanup");
        CancellationHandle current=new CancellationHandle(51);
        execute(cleanup,action(51,"cleanup","arm_cleanup"),current);
        compensate(cleanup,action(51,"cleanup","stop"),current);
        compensate(cleanup,action(51,"cleanup","stop"),current);
        require(pipeline.recreations==0,"Stop unexpectedly allocated");
        execute(cleanup,action(51,"cleanup","arm_cleanup"),current);
        require(pipeline.recreations==1,"fresh arm did not allocate one new graph");
        require("cleanup_armed".equals(cleanup.snapshot().state()),"new graph arm unavailable");
        require(!cleanup.snapshot().terminal(),"new graph misreported terminal");
    }
    private static void pendingStopCannotRecreate() {
        FakePipeline pipeline=new FakePipeline();pipeline.pendingStop=true;
        PackedStereoMediaOwnerSet owners=new PackedStereoMediaOwnerSet(52,pipeline);
        MediaOwnerProvider cleanup=owners.provider("cleanup");CancellationHandle current=new CancellationHandle(52);
        execute(cleanup,action(52,"cleanup","arm_cleanup"),current);
        expectFailure(()->compensate(cleanup,action(52,"cleanup","stop"),current));
        expectFailure(()->execute(cleanup,action(52,"cleanup","arm_cleanup"),current));
        require(pipeline.recreations==0,"pending graph recreated");
    }
    private static void staleGenerationCannotRecreate() {
        FakePipeline pipeline=new FakePipeline();PackedStereoMediaOwnerSet owners=new PackedStereoMediaOwnerSet(53,pipeline);
        MediaOwnerProvider cleanup=owners.provider("cleanup");CancellationHandle current=new CancellationHandle(53);
        compensate(cleanup,action(53,"cleanup","stop"),current);
        expectArgumentFailure(()->execute(cleanup,action(52,"cleanup","arm_cleanup"),current));
        expectFailure(()->execute(cleanup,action(53,"cleanup","arm_cleanup"),new CancellationHandle(52)));
        require(pipeline.recreations==0,"stale action recreated graph");
    }
    private static void duplicateArmCannotRecreate() {
        FakePipeline pipeline=new FakePipeline();PackedStereoMediaOwnerSet owners=new PackedStereoMediaOwnerSet(54,pipeline);
        MediaOwnerProvider cleanup=owners.provider("cleanup");CancellationHandle current=new CancellationHandle(54);
        execute(cleanup,action(54,"cleanup","arm_cleanup"),current);
        expectFailure(()->execute(cleanup,action(54,"cleanup","arm_cleanup"),current));
        require(pipeline.recreations==0,"double arm allocated graph");
    }
    private static void incomingBarrierBlocksRecreate() {
        FakePipeline pipeline=new FakePipeline();
        MediaOwnerProvider incoming=new MediaOwnerProvider(){
            public MediaProviderReadback execute(MediaOwnerAction a,CancellationHandle c){throw new UnsupportedOperationException();}
            public MediaProviderReadback compensate(MediaOwnerAction a,CancellationHandle c){throw new UnsupportedOperationException();}
            public MediaRuntimeSnapshot snapshot(){return new MediaRuntimeSnapshot(55,1,"stopped",false,"","incoming");}
        };
        PackedStereoMediaOwnerSet owners=new PackedStereoMediaOwnerSet(55,pipeline,incoming);
        MediaOwnerProvider cleanup=owners.provider("cleanup");CancellationHandle current=new CancellationHandle(55);
        compensate(cleanup,action(55,"cleanup","stop"),current);
        expectFailure(()->execute(cleanup,action(55,"cleanup","arm_cleanup"),current));
        require(pipeline.recreations==0,"unresolved incoming barrier ignored");
    }
    private static void actualPipelineRecreatesDistinctRuntime() {
        try {
            Class<?> runtime=Class.forName(PackedStereoMediaSourceRuntime.class.getName()+"$Runtime");
            Class<?> profile=Class.forName(PackedStereoMediaSourceRuntime.class.getName()+"$PortProfile");
            java.lang.reflect.Constructor<?> pc=profile.getDeclaredConstructor(int.class,int.class,int.class,int.class,int.class);pc.setAccessible(true);
            Object profileValue=pc.newInstance(30401,1920,1080,60,4000000);
            PackedStereoStreamMetadata.Layout layout=new PackedStereoStreamMetadata.Layout(1920,1080,960,1080,1000000);
            java.lang.reflect.Constructor<?> rc=runtime.getDeclaredConstructors()[0];rc.setAccessible(true);
            Object prior=rc.newInstance("restart-host-fixture","session.host","source.host",null,"127.0.0.1",profileValue,layout,"left","right",false);
            java.lang.reflect.Constructor<?> constructor=PackedStereoMediaSourceRuntime.Pipeline.class.getDeclaredConstructor(runtime);constructor.setAccessible(true);
            PackedStereoMediaSourceRuntime.Pipeline pipeline=(PackedStereoMediaSourceRuntime.Pipeline)constructor.newInstance(prior);
            String oldHandle=pipeline.handleId();
            expectFailure(pipeline::recreateAfterVerifiedCleanup);
            pipeline.stopAndVerify("host_no_handles");pipeline.finishCleanup();
            pipeline.recreateAfterVerifiedCleanup();
            require(!oldHandle.equals(pipeline.handleId()),"runtime identity reused");
            require(!pipeline.terminal(),"new runtime inherited terminal latch");
            java.lang.reflect.Field stop=runtime.getDeclaredField("stopRequested");stop.setAccessible(true);
            require(stop.getBoolean(prior),"old stop latch was reset");
            expectFailure(pipeline::recreateAfterVerifiedCleanup);
            pipeline.stopAndVerify("host_no_handles");pipeline.finishCleanup();
        }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException(failure);}
    }

    private static void actualCaptureSubscriptionRejectsStaleOrLiveIncarnation() {
        try {
            java.lang.reflect.Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe=(sun.misc.Unsafe)unsafeField.get(null);
            // No Camera/GL effects: create only the actual production subscription state boundary.
            PackedStereoCaptureOwner capture=(PackedStereoCaptureOwner)unsafe.allocateInstance(PackedStereoCaptureOwner.class);
            java.lang.reflect.Field lock=PackedStereoCaptureOwner.class.getDeclaredField("subscriptionLock");lock.setAccessible(true);lock.set(capture,new Object());
            java.lang.reflect.Field generation=PackedStereoCaptureOwner.class.getDeclaredField("encoderGeneration");generation.setAccessible(true);generation.setLong(capture,7L);
            expectFailure(()->capture.reserveEncoderGenerationAfterRetirement(6L));
            long next=capture.reserveEncoderGenerationAfterRetirement(7L);require(next==8L,"encoder incarnation did not advance");
            PackedStereoCaptureOwner.EncoderConsumer consumer=frame->{throw new AssertionError("unexpected frame");};
            capture.attachEncoder(next,consumer);
            expectFailure(()->capture.attachEncoder(next,consumer));
            capture.detachEncoder(7L);
            expectFailure(()->capture.reserveEncoderGenerationAfterRetirement(8L));
            capture.detachEncoder(next);
            require(capture.reserveEncoderGenerationAfterRetirement(next)==9L,"next retired incarnation did not advance");
            java.lang.reflect.Field stopped=PackedStereoCaptureOwner.class.getDeclaredField("stopRequested");stopped.setAccessible(true);stopped.setBoolean(capture,true);
            expectFailure(()->capture.reserveEncoderGenerationAfterRetirement(next));
        }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException(failure);}
    }

    private static void neverEnteredOwnerStillCleansIndependentResources() {
        FakePipeline pipeline = new FakePipeline(); // independent graph already owns live resources
        PackedStereoMediaOwnerSet owners = new PackedStereoMediaOwnerSet(61, pipeline);
        MediaOwnerProvider source = owners.provider("source");
        MediaProductBinding binding = new MediaProductBinding.Builder("product.lifecycle")
                .bind("source", "owner.lifecycle", "provider.lifecycle", "resource.lifecycle", source).build();
        PackagedAndroidMediaOwnerRegistry registry = new PackagedAndroidMediaOwnerRegistry(61, binding);
        String ticket = actionJson(61, "source", "stop");
        pipeline.pendingStop = true;
        expectFailure(() -> registry.execute(ticket, false));
        require(!pipeline.closed, "undispatched owner inferred absence despite live resources");
        require(registry.verifyAndReadEvidence(ticket, "{}") == null, "uncertain callback became terminal");
        pipeline.pendingStop = false;
        String raw = registry.execute(ticket, false);
        require(pipeline.closed && pipeline.stopCalls == 2, "actual independent graph was not stopped");
        require(registry.verifyAndReadEvidence(ticket, raw) != null, "current terminal registry proof absent");
        require(registry.verifyAndReadEvidence(actionJson(60, "source", "stop"), raw) == null,
                "foreign generation accepted terminal effect");
        registry.close();
        require(registry.verifyAndReadEvidence(ticket, raw) == null, "retired registry accepted old effect");
    }

    private static MediaOwnerAction action(long generation, String kind, String actionKind) {
        return MediaOwnerAction.parse(actionJson(generation, kind, actionKind));
    }
    private static String actionJson(long generation, String kind, String actionKind) {
        return "{\"$schema\":\"rusty.quest.android.media.execution-ticket.v1\","
                + "\"capability\":\"cap.lifecycle\",\"executor_generation\":"+generation+","
                + "\"action_id\":\"action.lifecycle\",\"authority_epoch_id\":\"epoch.lifecycle\","
                + "\"media_acceptance_authority_revision\":1,\"expected_runtime_revision\":1,"
                + "\"client_id\":\"client.lifecycle\",\"lease_id\":\"lease.lifecycle\","
                + "\"sequence\":1,\"operation\":\""+("stop".equals(actionKind)||"cleanup".equals(actionKind)?"stop":"start")+"\",\"owner_kind\":\""+kind+"\","
                + "\"action_kind\":\""+actionKind+"\",\"owner_id\":\"owner.lifecycle\","
                + "\"provider_kind\":\"provider.lifecycle\",\"resource_id\":\"resource.lifecycle\"}";
    }

    private static MediaProviderReadback execute(MediaOwnerProvider provider, MediaOwnerAction action,
            CancellationHandle cancellation) {
        try { return provider.execute(action, cancellation); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void compensate(MediaOwnerProvider provider, MediaOwnerAction action,
            CancellationHandle cancellation) {
        try { provider.compensate(action, cancellation); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void expectArgumentFailure(Runnable call) {
        try { call.run(); throw new AssertionError("expected unsupported binding"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void expectFailure(Runnable call) {
        try { call.run(); throw new AssertionError("expected failure"); }
        catch (IllegalStateException expected) { }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class FakePipeline implements PackedStereoPipeline {
        boolean failProcessor;
        boolean failed;
        boolean checkFreshness;
        long now;
        final MonotonicFreshnessDeadline freshness = new MonotonicFreshnessDeadline(100L);
        boolean terminal;
        boolean closed;
        int processorStarts;
        int stopCalls;
        int recreations;
        boolean pendingStop;
        @Override public void validateRoute() { }
        @Override public void startSocket() { }
        @Override public void startCodec() { }
        @Override public void startProcessor() {
            processorStarts++;
            if (failProcessor) throw new IllegalStateException("injected processor failure");
        }
        @Override public void startSource() { checkFreshness=true;now=100L;freshness.progress(now); }
        @Override public void stopAndVerify(String reason) { stopCalls++;if(pendingStop)throw new IllegalStateException("physical cleanup pending");closed=true;terminal=true; }
        @Override public void requireStopped() { if (!closed) throw new IllegalStateException("live"); }
        @Override public void finishCleanup() { requireStopped(); }
        @Override public void recreateAfterVerifiedCleanup() { requireStopped();recreations++;closed=false;terminal=false;checkFreshness=false; }
        @Override public boolean failed() { return failed || (checkFreshness && !freshness.fresh(now)); }
        @Override public boolean terminal() { return terminal; }
        @Override public String observedState() { return failed?"failed":(closed?"stopped":"started"); }
        @Override public String handleId() { return "fake-pipeline"; }
        @Override public void close() { stopAndVerify("close"); }
    }
}
