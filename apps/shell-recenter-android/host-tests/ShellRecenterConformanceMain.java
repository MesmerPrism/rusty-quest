// SPDX-License-Identifier: AGPL-3.0-or-later
import io.github.mesmerprism.rustyquest.shellrecenter.ShellRecenterOperation;
import io.github.mesmerprism.rustyquest.shellrecenter.ShellRecenterOperation.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Calls the production controller as a consumer with injected original handles. */
public final class ShellRecenterConformanceMain {
    static final class Harness implements Backend, Journal, Listener {
        final ShellRecenterOperation operation;
        Callback<Boolean> enabled; Callback<Result> original;
        int dispatches, intents, finals, cancels; boolean available = true, failIntent, failFinal,
                throwDispatch, throwCancel, immediate, expireDuringIntent, slowIntent;
        long time;
        String reason = "ready"; Snapshot finalSnapshot;
        final List<String> events = new ArrayList<>();
        Harness(Mode mode) { operation = new ShellRecenterOperation(mode, this, this, this, () -> time); }
        public Availability inspect() { return new Availability(available, reason); }
        public void enabled(Callback<Boolean> callback) { enabled = callback; }
        public void execute(Callback<Result> callback) throws Exception {
            check(intents == 1 && operation.snapshot().executeAttempted, "intent precedes real dispatch");
            dispatches++; original = callback;
            if (immediate) callback.result(new Result("empty-document"));
            if (throwDispatch) throw new IOException("transport-uncertain");
        }
        public void cancel() throws Exception { cancels++; if (throwCancel) throw new IOException("cancel-failed"); }
        public void intent(Snapshot snapshot) throws Exception {
            check(!snapshot.executeAttempted, "durable intent before attempted"); intents++;
            if (expireDuringIntent) operation.deadline();
            if (slowIntent) time = 30001;
            if (failIntent) throw new IOException("intent-storage-failure");
        }
        public void terminal(Snapshot snapshot) throws Exception { if (failFinal) throw new IOException("final-storage-failure"); finals++; finalSnapshot = snapshot; }
        public void event(String event, Snapshot snapshot) { events.add(event); }
        void start() { operation.start(); }
        void enable() { enabled.result(true); }
    }
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    static Harness recenter() { Harness h = new Harness(Mode.RECENTER); h.start(); h.enable(); return h; }
    public static void main(String[] args) {
        for (String reason : new String[]{"ordinary-apk-uid", "permission-denied", "unsupported-firmware", "source-mismatch", "boot-mismatch"}) {
            Harness h = new Harness(Mode.RECENTER); h.available = false; h.reason = reason; h.start();
            check(h.dispatches == 0 && h.finals == 1 && h.finalSnapshot.resultDocument.equals(reason), reason);
        }
        Harness ready = new Harness(Mode.READINESS); ready.start(); ready.enable();
        check(ready.intents == 0 && ready.dispatches == 0 && ready.finalSnapshot.outcome.equals("READINESS_READY"), "readiness never mutates");
        Harness disabled = new Harness(Mode.RECENTER); disabled.start(); disabled.enabled.result(false);
        check(disabled.dispatches == 0 && disabled.finals == 1, "disabled never executes");
        Harness success = recenter(); success.original.result(new Result("original-result"));
        success.original.error(new IOException("duplicate")); success.enabled.result(true); success.operation.deadline();
        check(success.dispatches == 1 && success.finals == 1 && success.finalSnapshot.resultDocument.equals("original-result"), "duplicates cannot replace original");
        try { success.start(); throw new AssertionError("replay admitted"); } catch (IllegalStateException expected) { }
        Harness late = recenter(); late.operation.deadline(); late.operation.deadline();
        check(late.operation.snapshot().cleanupHeld && late.finals == 0 && late.cancels == 1, "deadline retains actual original");
        late.original.result(new Result("late-original"));
        check(late.finals == 1 && late.finalSnapshot.deadlineReached && late.finalSnapshot.callbackReceived && !late.operation.snapshot().cleanupHeld, "late genuine callback resolves original");
        Harness lateError = recenter(); lateError.operation.deadline(); IOException actual = new IOException("actual-platform-error"); lateError.original.error(actual);
        check(lateError.finalSnapshot.originalError == actual && lateError.finalSnapshot.outcome.equals("ORIGINAL_ERROR"), "actual error object retained");
        Harness dispatchFailure = new Harness(Mode.RECENTER); dispatchFailure.throwDispatch = true; dispatchFailure.start(); dispatchFailure.enable();
        check(dispatchFailure.operation.snapshot().cleanupHeld && dispatchFailure.finals == 0 && dispatchFailure.dispatches == 1, "dispatch throw uncertain");
        dispatchFailure.original.result(new Result("callback-after-dispatch-throw")); check(dispatchFailure.finals == 1, "late original after dispatch throw");
        Harness cancelFailure = recenter(); cancelFailure.throwCancel = true; cancelFailure.operation.deadline();
        check(cancelFailure.operation.snapshot().cleanupHeld && !cancelFailure.operation.snapshot().cancellationReturned, "cancel failure held");
        Harness finalFailure = recenter(); finalFailure.failFinal = true; finalFailure.original.result(new Result("original"));
        check(finalFailure.operation.snapshot().callbackReceived && finalFailure.operation.snapshot().cleanupHeld && finalFailure.finals == 0, "failed final keeps original ownership");
        finalFailure.original.result(new Result("duplicate")); check(finalFailure.finals == 0 && finalFailure.dispatches == 1, "no retry after failed final");
        Harness intentFailure = new Harness(Mode.RECENTER); intentFailure.failIntent = true; intentFailure.start(); intentFailure.enable();
        check(intentFailure.dispatches == 0 && intentFailure.finals == 1, "intent failure prevents call");
        Harness readTimeout = new Harness(Mode.RECENTER); readTimeout.start(); readTimeout.operation.deadline(); readTimeout.enable();
        check(readTimeout.dispatches == 0 && readTimeout.finals == 1, "late readiness never dispatches");
        Harness race = new Harness(Mode.RECENTER); race.expireDuringIntent = true; race.start(); race.enable();
        check(race.dispatches == 0 && race.finals == 1, "expiry during durable intent prevents call");
        Harness reentrant = new Harness(Mode.RECENTER); reentrant.immediate = true; reentrant.start(); reentrant.enable();
        check(reentrant.finals == 1 && reentrant.dispatches == 1, "synchronous original callback supported");
        Harness slow = new Harness(Mode.RECENTER); slow.slowIntent = true; slow.start(); slow.enable();
        check(slow.dispatches == 0 && slow.finals == 1 && slow.finalSnapshot.deadlineReached, "absolute deadline prevents dispatch after slow durable intent");
        System.out.println("Shell recenter production consumer: lifecycle/readiness cases passed; device_tested=false");
    }
}
