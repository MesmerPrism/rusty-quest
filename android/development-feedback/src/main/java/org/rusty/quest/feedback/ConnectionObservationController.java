package org.rusty.quest.feedback;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Optional resident read-only proposal adapter. No transport, scheduling or lifecycle effects. */
public final class ConnectionObservationController {
    public static final int POLL_DUE = 1, OBSERVATION_FAILED = 2, OBSERVATION_RECEIVED = 3;
    public static final long UNRESOLVED_FLAG = 256;
    public static final long MINIMUM_POLL_MS = 500;
    public enum Action { WAIT, REQUEST_STATUS }
    public enum Reason { POLICY, INELIGIBLE, MODULE_FAILURE, INVALID_RESULT }

    /** Resident-only values. Tokens never cross the implementation ABI. */
    public static final class Snapshot {
        public final long owner, operation, sample, nowMs, lastPollMs;
        public final boolean current, closed, cancelled, observing, unresolved;
        public Snapshot(long owner, long operation, long sample, long nowMs, long lastPollMs,
                        boolean current, boolean closed, boolean cancelled, boolean observing,
                        boolean unresolved) {
            if (owner < 1 || operation < 0 || sample < 0 || nowMs < 0 || lastPollMs < -1)
                throw new IllegalArgumentException("Invalid resident observation");
            this.owner=owner; this.operation=operation; this.sample=sample;
            this.nowMs=nowMs; this.lastPollMs=lastPollMs; this.current=current;
            this.closed=closed; this.cancelled=cancelled; this.observing=observing;
            this.unresolved=unresolved;
        }
    }
    public static final class Decision {
        public final Action action;
        public final Reason reason;
        public final String generation;
        private final ConnectionObservationController issuer;
        private final Snapshot snapshot;
        private final Object selection;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private Decision(ConnectionObservationController issuer, Snapshot snapshot, Object selection,
                         Action action, Reason reason, String generation) {
            this.issuer=issuer; this.snapshot=snapshot; this.selection=selection;
            this.action=action; this.reason=reason; this.generation=generation;
        }
    }
    private final ModuleHost host;
    private final AtomicReference<Object> selection = new AtomicReference<>(new Object());
    public ConnectionObservationController(GenerationLoader loader, int maximumLoads) {
        host = new ModuleHost(loader, maximumLoads, new byte[4]);
    }
    /** Call outside product authority locks. Even a rejected attempt invalidates old proposals. */
    public synchronized void activate(ArtifactStore.Artifact artifact, String entry, String id,
                                      ModuleHost.Probe probe) {
        selection.set(new Object());
        host.activate(artifact, entry, id, probe);
    }
    /** Explicit code rollback; resident resources and ModuleHost counter/buffer are not rolled back. */
    public synchronized void selectRetained(String id) {
        selection.set(new Object());
        host.selectRetained(id);
    }
    private static boolean eligible(Snapshot snapshot) {
        return snapshot != null && snapshot.current && !snapshot.closed && !snapshot.cancelled
            && !snapshot.observing && (snapshot.lastPollMs == -1
                || (snapshot.nowMs >= snapshot.lastPollMs
                    && snapshot.nowMs - snapshot.lastPollMs >= MINIMUM_POLL_MS));
    }
    /** Call outside product locks. Trusted synchronous module code can block this call/selection. */
    public synchronized Decision evaluate(int event, Snapshot snapshot) {
        if (event < POLL_DUE || event > OBSERVATION_RECEIVED || snapshot == null)
            throw new IllegalArgumentException("Unknown connection observation event");
        Object selected = selection.get();
        if (!eligible(snapshot))
            return new Decision(this, snapshot, selected, Action.WAIT, Reason.INELIGIBLE, null);
        String generation = null;
        try {
            generation = host.activeGenerationId();
            long result = host.compute(event | (snapshot.unresolved ? UNRESOLVED_FLAG : 0));
            if (result != 0 && result != 1)
                return new Decision(this, snapshot, selected, Action.WAIT, Reason.INVALID_RESULT, generation);
            return new Decision(this, snapshot, selected, result == 1 ? Action.REQUEST_STATUS : Action.WAIT,
                Reason.POLICY, generation);
        } catch (RuntimeException | LinkageError failure) {
            return new Decision(this, snapshot, selected, Action.WAIT, Reason.MODULE_FAILURE, generation);
        }
    }
    /**
     * No locks or callbacks. Under its existing gate, the resident caller supplies fresh state,
     * claims once and sets observing/lastPoll atomically before dispatching its read-only query.
     * Tokens must advance for owner/operation/sample changes; this is not authority admission.
     */
    public boolean claimStatus(Decision decision, Snapshot current) {
        if (decision == null || decision.issuer != this || decision.action != Action.REQUEST_STATUS
            || !decision.consumed.compareAndSet(false, true)) return false;
        Snapshot before = decision.snapshot;
        if (!eligible(current) || current.owner != before.owner || current.operation != before.operation
            || current.sample != before.sample || current.lastPollMs != before.lastPollMs
            || current.nowMs < before.nowMs || current.unresolved != before.unresolved) return false;
        // Fresh identity on every selection attempt avoids ABA. This CAS is the claim's
        // selection linearization point; later replacement cannot undo an already claimed query.
        return selection.compareAndSet(decision.selection, decision.selection);
    }
    public long computationCount() { return host.counter(); }
    public long bufferAllocationId() { return host.bufferAllocationId(); }
    public byte[] bufferSnapshot() { return host.bufferSnapshot(); }
    public String activeGenerationId() { return host.activeGenerationId(); }
}
