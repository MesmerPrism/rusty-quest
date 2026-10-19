// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.shellrecenter;

/** One fixed original. Observer expiry never permits replay or proves reversal. */
public final class ShellRecenterOperation {
    public static final String TARGET_PACKAGE = "com.oculus.vrshell";
    public static final String FUNCTION_ID = "com.oculus.vrshell.appfunctions.ShellAppFunctions#recenterView";
    public enum Mode { READINESS, RECENTER }
    public interface Callback<T> { void result(T value); void error(Throwable error); }
    public interface Backend {
        Availability inspect() throws Exception;
        void enabled(Callback<Boolean> callback) throws Exception;
        void execute(Callback<Result> callback) throws Exception;
        void cancel() throws Exception;
    }
    public interface Journal {
        /** Must create and sync the original intent before returning. */
        void intent(Snapshot snapshot) throws Exception;
        /** Must create and sync the final; failure retains ownership. */
        void terminal(Snapshot snapshot) throws Exception;
    }
    public interface Listener { void event(String event, Snapshot snapshot); }
    public interface Clock { long elapsedMillis(); }
    public static final class Availability {
        public final boolean ready;
        public final String reason;
        public Availability(boolean ready, String reason) { this.ready = ready; this.reason = reason; }
    }
    public static final class Result {
        public final String document;
        public Result(String document) { this.document = document; }
    }
    public static final class CallbackDecodeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CallbackDecodeException(Throwable cause) { super("original-response-decoding-failed", cause); }
    }
    public static final class Snapshot {
        public final boolean executeAttempted, callbackReceived, deadlineReached, cleanupHeld,
                cancellationAttempted, cancellationReturned, terminal;
        public final String outcome, resultDocument, readinessReason, callbackErrorOrigin;
        public final Throwable originalError;
        Snapshot(ShellRecenterOperation op) {
            executeAttempted = op.attempted; callbackReceived = op.callbackReceived;
            deadlineReached = op.expired; cleanupHeld = op.held;
            cancellationAttempted = op.cancelAttempted; cancellationReturned = op.cancelReturned;
            terminal = op.terminal; outcome = op.outcome; resultDocument = op.resultDocument;
            originalError = op.originalError;
            readinessReason = op.readinessReason; callbackErrorOrigin = op.callbackErrorOrigin;
        }
    }
    private final Backend backend;
    private final Journal journal;
    private final Listener listener;
    private final Mode mode;
    private final Clock clock;
    private final long deadlineAt;
    private boolean started, enabledReceived, attempted, callbackReceived, expired, held,
            cancelAttempted, cancelReturned, terminal;
    private String outcome, resultDocument, readinessReason, callbackErrorOrigin;
    private Throwable originalError;

    public ShellRecenterOperation(Mode mode, Backend backend, Journal journal, Listener listener) {
        this(mode, backend, journal, listener, () -> System.nanoTime() / 1000000L);
    }
    public ShellRecenterOperation(Mode mode, Backend backend, Journal journal, Listener listener, Clock clock) {
        if (mode == null || backend == null || journal == null || listener == null)
            throw new NullPointerException("mode/backend/journal/listener required");
        this.mode = mode; this.backend = backend; this.journal = journal; this.listener = listener;
        if (clock == null) throw new NullPointerException("clock required");
        this.clock = clock; deadlineAt = clock.elapsedMillis() + 30000L;
    }
    // The enclosing caller serializes calls/callbacks on one executor. Reentrant callbacks
    // are supported: attempted is recorded before execute and finals cannot be overwritten.
    public void start() {
        if (started) throw new IllegalStateException("original-already-started");
        started = true;
        try {
            Availability availability = backend.inspect();
            readinessReason = availability.reason;
            if (!availability.ready) { resultDocument = availability.reason; finish("READINESS_UNAVAILABLE"); return; }
            if (expiredNow()) { finish("READ_DEADLINE_NO_EXECUTE"); return; }
            backend.enabled(new Callback<Boolean>() {
                public void result(Boolean value) { enabledResult(value); }
                public void error(Throwable error) { if (enabledReceived || terminal) { emit("DUPLICATE_ENABLED_IGNORED"); return; } enabledReceived = true; fail(error); }
            });
            emit("ENABLED_READ_CALL_RETURNED");
        } catch (Exception error) { fail(error); }
    }
    private void enabledResult(Boolean value) {
        if (enabledReceived || terminal) { emit("DUPLICATE_ENABLED_IGNORED"); return; }
        enabledReceived = true;
        if (value == null) { fail(new IllegalStateException("typed-enabled-result-required")); return; }
        emit(value ? "ENABLED_READ_TRUE" : "ENABLED_READ_FALSE");
        if (expiredNow()) { finish("READ_DEADLINE_NO_EXECUTE"); return; }
        if (!value) { readinessReason = "function-disabled"; finish("READINESS_UNAVAILABLE"); return; }
        readinessReason = "ready";
        if (mode == Mode.READINESS) { finish("READINESS_READY"); return; }
        try {
            journal.intent(snapshot());
            if (expiredNow()) { finish("INTENT_PUBLISHED_DEADLINE_NO_EXECUTE"); return; }
            attempted = true;
            emit("EXECUTE_DISPATCH_INTENT");
            // A dispatch exception is uncertain. It must never become no-execute.
            backend.execute(new Callback<Result>() {
                public void result(Result value) {
                    if (!admitOriginal()) return;
                    if (value == null || value.document == null) {
                        originalError = new IllegalStateException("typed-original-result-required");
                        finish("ORIGINAL_CALLBACK_UNREADABLE");
                    } else { resultDocument = value.document; finish("ORIGINAL_RESULT"); }
                }
                public void error(Throwable error) {
                    if (!admitOriginal()) return;
                    originalError = error;
                    callbackErrorOrigin = error instanceof CallbackDecodeException ? "response_decoding" : "service_callback";
                    finish(error instanceof CallbackDecodeException ? "ORIGINAL_CALLBACK_UNREADABLE" : "ORIGINAL_ERROR");
                }
            });
            emit("EXECUTE_CALL_RETURNED_AWAIT_ORIGINAL_CALLBACK");
        } catch (Exception error) { fail(error); }
    }
    private boolean admitOriginal() {
        if (!attempted || callbackReceived || terminal) { emit("DUPLICATE_OR_NONORIGINAL_CALLBACK_IGNORED"); return false; }
        callbackReceived = true; return true;
    }
    public void deadline() {
        if (terminal) return;
        expired = true; emit("ORIGINAL_DEADLINE");
        if (attempted && !callbackReceived) {
            hold();
            if (!cancelAttempted) {
                cancelAttempted = true;
                try { backend.cancel(); cancelReturned = true; emit("CANCELLATION_REQUEST_RETURNED_NOT_REVERSAL"); }
                catch (Exception error) { emit("CANCELLATION_FAILED_ORIGINAL_HELD"); }
            }
        } else if (!attempted) finish("READ_DEADLINE_NO_EXECUTE");
    }
    private void fail(Throwable error) {
        if (terminal) return;
        if (attempted) { hold(); }
        else { originalError = error; finish("PREEXEC_ERROR_NO_EXECUTE"); }
    }
    private void hold() { held = true; emit("ORIGINAL_UNKNOWN_HELD"); }
    private boolean expiredNow() { if (clock.elapsedMillis() >= deadlineAt) expired = true; return expired; }
    private void finish(String value) {
        if (terminal) return;
        outcome = value;
        held = false;
        try { journal.terminal(snapshot()); terminal = true; emit("FINAL_PUBLISHED"); }
        catch (Exception error) {
            held = attempted;
            // Even a completed original remains owned when durable final publication fails.
            emit(attempted ? "FINAL_PUBLICATION_FAILED_ORIGINAL_HELD" : "TERMINAL_NO_EXECUTE_OUTPUT_FAILURE");
        }
    }
    private void emit(String event) { listener.event(event, snapshot()); }
    public Snapshot snapshot() { return new Snapshot(this); }
}
