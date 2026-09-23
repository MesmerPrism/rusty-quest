package io.github.mesmerprism.rustyquest.media;

/** Host-only checks that drive the production owner transition implementation. */
public final class MediaOwnerLifecycleAdversarialMain {
    public static void main(String[] args) {
        partialStartCompensatesAndStopIsIdempotent();
        asynchronousFailureInvalidatesReadback();
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

    private static MediaOwnerAction action(long generation, String kind, String actionKind) {
        return MediaOwnerAction.parse("{\"$schema\":\"rusty.quest.android.media.execution-ticket.v1\","
                + "\"capability\":\"cap.lifecycle\",\"executor_generation\":"+generation+","
                + "\"action_id\":\"action.lifecycle\",\"authority_epoch_id\":\"epoch.lifecycle\","
                + "\"media_acceptance_authority_revision\":1,\"expected_runtime_revision\":1,"
                + "\"client_id\":\"client.lifecycle\",\"lease_id\":\"lease.lifecycle\","
                + "\"sequence\":1,\"operation\":\""+actionKind+"\",\"owner_kind\":\""+kind+"\","
                + "\"action_kind\":\""+actionKind+"\",\"owner_id\":\"owner.lifecycle\","
                + "\"provider_kind\":\"provider.lifecycle\",\"resource_id\":\"resource.lifecycle\"}");
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
        @Override public void validateRoute() { }
        @Override public void startSocket() { }
        @Override public void startCodec() { }
        @Override public void startProcessor() {
            processorStarts++;
            if (failProcessor) throw new IllegalStateException("injected processor failure");
        }
        @Override public void startSource() { checkFreshness=true;now=100L;freshness.progress(now); }
        @Override public void stopAndVerify(String reason) { stopCalls++;closed=true;terminal=true; }
        @Override public void requireStopped() { if (!closed) throw new IllegalStateException("live"); }
        @Override public void finishCleanup() { requireStopped(); }
        @Override public boolean failed() { return failed || (checkFreshness && !freshness.fresh(now)); }
        @Override public boolean terminal() { return terminal; }
        @Override public String observedState() { return failed?"failed":(closed?"stopped":"started"); }
        @Override public String handleId() { return "fake-pipeline"; }
        @Override public void close() { stopAndVerify("close"); }
    }
}
