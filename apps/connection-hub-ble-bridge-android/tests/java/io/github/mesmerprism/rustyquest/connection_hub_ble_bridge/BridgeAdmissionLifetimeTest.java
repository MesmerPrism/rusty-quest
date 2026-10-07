package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Unexecuted source candidate: actual production State; platform dispatch is injected. */
public final class BridgeAdmissionLifetimeTest {
    static int cases;
    static void check(boolean value) {
        if (!value) throw new AssertionError("admission case " + (cases + 1));
        cases++;
    }
    static final class Port implements BridgeController.Port {
        long clock = 100;
        boolean permissions = true, hub = true, stopResult;
        Exception startError, stopError;
        int starts, stops;
        public long now() { return clock; }
        public boolean permissionsReady() { return permissions; }
        public void requireHubCurrent() throws Exception { if (!hub) throw new Exception("private Hub detail"); }
        public void start() throws Exception { starts++; if (startError != null) throw startError; }
        public boolean stop() throws Exception { stops++; if (stopError != null) throw stopError; return stopResult; }
    }
    static BridgeController.State shell(Port port) throws Exception {
        BridgeController.State state = new BridgeController.State();
        JSONObject receipt = new BridgeController(port, state).enableShell(2000);
        check(receipt.getBoolean("request_accepted") && port.starts == 0);
        check(receipt.getString("$schema").equals("rusty.quest.hub_ble_carrier_control_receipt.v2")
            && receipt.getString("admission_policy").equals("single_use_until_consumed_cancelled_or_process_end")
            && receipt.getString("admission_state").equals("prepared")
            && receipt.getString("admission_scope").equals("shell")
            && receipt.getLong("admission_issued_elapsed_ms") == port.clock);
        check(!receipt.toString().contains("internal_token") && !receipt.getBoolean("carrier_ready_now"));
        Set<String> keys = new HashSet<>(); receipt.keys().forEachRemaining(keys::add);
        check(keys.equals(Set.of("$schema", "action", "outcome", "request_accepted", "process_instance_id", "generation",
            "carrier_state", "error_code", "service_observed", "service_live", "advertising_callback_confirmed",
            "carrier_ready_now", "deadline_elapsed_ms", "radio_cleanup_qualified", "controller_authority_claimed",
            "pairing_secret_in_receipt", "production_eligible", "admission_policy", "admission_state", "admission_scope",
            "admission_issued_elapsed_ms", "admission_age_ms")));
        return state;
    }
    static void delayed(boolean shell, long delay) throws Exception {
        Port port = new Port();
        BridgeController.State state = shell ? shell(port) : new BridgeController.State();
        BridgeController controller = new BridgeController(port, state);
        if (!shell) check(controller.enable().getBoolean("request_accepted") && port.starts == 1);
        String token = state.internalToken();
        port.clock = delay;
        JSONObject status = controller.status();
        check(status.getString("admission_state").equals("prepared")
            && status.getLong("admission_age_ms") == delay - 100
            && !status.getBoolean("service_observed") && !status.getBoolean("carrier_ready_now"));
        check(!state.consumeAdmission(shell, "00000000000000000000000000000000", 1, token, delay));
        check(!state.consumeAdmission(shell, state.processInstance, 2, token, delay));
        check(!state.consumeAdmission(!shell, state.processInstance, 1, token, delay));
        check(!state.consumeAdmission(shell, state.processInstance, 1, shell ? "extra" : "wrong", delay));
        check(!state.consumeAdmission(shell, state.processInstance, 1, token, 99));
        check(!state.consumeAdmission(shell, state.processInstance, 1, token, -1));
        check(state.consumeAdmission(shell, state.processInstance, 1, token, delay));
        check(!state.consumeAdmission(shell, state.processInstance, 1, token, delay));
        check(controller.status().getString("admission_state").equals("consumed")
            && state.internalToken() == null && !controller.status().getBoolean("carrier_ready_now"));
        check(!controller.enableShell(2000).getBoolean("request_accepted"));
    }
    static void cancelled(boolean stopThrows) throws Exception {
        Port port = new Port(); BridgeController.State state = shell(port);
        BridgeController controller = new BridgeController(port, state);
        if (stopThrows) port.stopError = new SecurityException("private stop detail");
        JSONObject receipt = controller.disable();
        check(receipt.getString("outcome").equals(stopThrows ? "stop_dispatch_failed_unknown" : "stop_dispatch_absent_unknown")
            && !receipt.getBoolean("request_accepted") && port.stops == 1);
        check(receipt.getString("admission_state").equals("cancelled")
            && receipt.getString("carrier_state").equals("stop_requested") && !receipt.getBoolean("service_observed"));
        check(!state.consumeAdmission(true, state.processInstance, 1, null, Long.MAX_VALUE));
        check(!controller.enableShell(2000).getBoolean("request_accepted"));
        check(!receipt.toString().contains("private stop detail"));
    }
    static void lifecycle() throws Exception {
        Port port = new Port(); BridgeController.State state = shell(port);
        state.failure(0, "start_dispatch_failed");
        check(state.consumeAdmission(true, state.processInstance, 1, null, 100000));
        BridgeController.State failed = shell(new Port());
        failed.failure(1, "start_dispatch_security_rejected");
        check(!failed.consumeAdmission(true, failed.processInstance, 1, null, 100000));
        check(failed.snapshot("status", "observed", false, 100000).getString("admission_state").equals("cancelled"));
        BridgeController.State destroyed = shell(new Port()); destroyed.destroyed(1);
        check(!destroyed.consumeAdmission(true, destroyed.processInstance, 1, null, 100000));
        BridgeController.State reborn = shell(new Port());
        check(!reborn.consumeAdmission(true, state.processInstance, 1, null, 100000));
        check(reborn.consumeAdmission(true, reborn.processInstance, 1, null, 100000));
        boolean denied = false;
        try { reborn.prepareAdmission(1, true, 100000); } catch (IllegalStateException expected) { denied = true; }
        check(denied); // Even the same generation cannot rearm a consumed admission.
        BridgeController.State cancelAfterConsume = shell(new Port());
        check(cancelAfterConsume.consumeAdmission(true, cancelAfterConsume.processInstance, 1, null, 100000));
        cancelAfterConsume.requestStop();
        check(!cancelAfterConsume.serviceStarted(1, 1000000)); // Cancellation wins before a delayed Service callback.
        check(!cancelAfterConsume.consumeAdmission(true, cancelAfterConsume.processInstance, 1, null, 100000));
    }
    static void dispatchErrors() throws Exception {
        for (Exception error : new Exception[] { new SecurityException("private"), new IllegalArgumentException("private"),
                new IllegalStateException("private"), new Exception("private") }) {
            Port port = new Port(); port.startError = error; BridgeController.State state = new BridgeController.State();
            BridgeController controller = new BridgeController(port, state); JSONObject result = controller.enable();
            check(!result.getBoolean("request_accepted") && result.getString("admission_state").equals("cancelled"));
            check(!state.consumeAdmission(false, state.processInstance, 1, state.internalToken(), 100000));
            check(!controller.enable().getBoolean("request_accepted") && port.starts == 1);
        }
        for (boolean permissionDenial : new boolean[] {true, false}) {
            Port port = new Port(); port.permissions = !permissionDenial; port.hub = permissionDenial;
            BridgeController.State state = new BridgeController.State();
            check(!new BridgeController(port, state).enableShell(2000).getBoolean("request_accepted"));
            check(!state.consumeAdmission(true, state.processInstance, 0, null, 100000) && port.starts == 0);
        }
    }
    static void concurrentConsume() throws Exception {
        BridgeController.State state = shell(new Port());
        CountDownLatch ready = new CountDownLatch(8), go = new CountDownLatch(1), done = new CountDownLatch(8);
        AtomicInteger accepted = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int index = 0; index < 8; index++) new Thread(() -> {
            ready.countDown();
            try { go.await(); if (state.consumeAdmission(true, state.processInstance, 1, null, Long.MAX_VALUE)) accepted.incrementAndGet(); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { done.countDown(); }
        }, "admission-consumer-" + index).start();
        ready.await(); go.countDown(); done.await(); // Completion driven; no guessed test wall-clock kill.
        if (failure.get() != null) throw new AssertionError("consumer failed", failure.get());
        check(accepted.get() == 1);
    }
    public static void main(String[] ignored) throws Exception {
        System.out.println("BEGIN production admission lifetime cases"); System.out.flush();
        for (boolean shell : new boolean[] {true, false}) for (long clock : new long[] {30100, 900100, 86400100, Long.MAX_VALUE}) delayed(shell, clock);
        System.out.println("PROGRESS delayed shell/internal complete"); System.out.flush();
        cancelled(false); cancelled(true); lifecycle(); dispatchErrors(); concurrentConsume();
        System.out.println("PASS " + cases + " production cases"); System.out.flush();
    }
}
