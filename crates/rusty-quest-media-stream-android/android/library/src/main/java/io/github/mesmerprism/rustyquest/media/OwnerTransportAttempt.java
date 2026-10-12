package io.github.mesmerprism.rustyquest.media;

/** A bounded latest attempt, separate from the first media-owner failure latch. No effect authority. */
public final class OwnerTransportAttempt {
    private static final long MAX_SEQUENCE = 9_007_199_254_740_991L;
    private long sequence;
    private OwnerDispatchTcpEndpoint.Phase phase = OwnerDispatchTcpEndpoint.Phase.NONE;
    private OwnerDispatchTcpEndpoint.Outcome outcome = OwnerDispatchTcpEndpoint.Outcome.NONE;
    private OwnerDispatchTcpEndpoint.Failure failure = OwnerDispatchTcpEndpoint.Failure.NONE;
    public synchronized long begin() {
        if (sequence == MAX_SEQUENCE) {
            phase = OwnerDispatchTcpEndpoint.Phase.NONE;
            outcome = OwnerDispatchTcpEndpoint.Outcome.UNAVAILABLE;
            failure = OwnerDispatchTcpEndpoint.Failure.NONE;
            return 0;
        }
        sequence++;
        phase = OwnerDispatchTcpEndpoint.Phase.TARGET_BINDING;
        outcome = OwnerDispatchTcpEndpoint.Outcome.IN_PROGRESS;
        failure = OwnerDispatchTcpEndpoint.Failure.NONE;
        return sequence;
    }
    public synchronized void observe(long attempt, OwnerDispatchTcpEndpoint.Phase nextPhase,
            OwnerDispatchTcpEndpoint.Outcome nextOutcome, OwnerDispatchTcpEndpoint.Failure nextFailure) {
        if (attempt == 0 || attempt != sequence || outcome == OwnerDispatchTcpEndpoint.Outcome.UNAVAILABLE) return;
        phase = nextPhase; outcome = nextOutcome; failure = nextFailure;
    }
    public synchronized String snapshot() {
        return "{\"attempt_sequence\":" + sequence + ",\"phase\":\"" + phase.name()
                + "\",\"outcome\":\"" + outcome.name() + "\",\"failure\":\"" + failure.name() + "\"}";
    }
}
