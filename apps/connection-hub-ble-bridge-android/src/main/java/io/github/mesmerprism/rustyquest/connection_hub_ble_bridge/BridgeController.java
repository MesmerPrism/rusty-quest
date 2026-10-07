package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.util.UUID;
import org.json.JSONObject;

/** One app-owned human/typed-command handler. Dispatch is not advertising or authority. */
final class BridgeController {
    interface Port {
        long now();
        boolean permissionsReady();
        void requireHubCurrent() throws Exception;
        void start() throws Exception;
        boolean stop() throws Exception;
    }
    static final State CURRENT = new State();
    static final class State {
        final String processInstance = UUID.randomUUID().toString().replace("-", "");
        private long generation, deadline;
        private String phase = "idle", error = "none";
        private boolean observed, live, advertised;
        private String admissionScope, internalToken;
        private long admissionIssuedAt;
        private String admissionState = "none";
        synchronized long requestStart() {
            if (live || "start_requested".equals(phase) || "stop_requested".equals(phase) || (generation > 0 && !observed)) return 0;
            if (generation == Long.MAX_VALUE) throw new IllegalStateException("generation_exhausted");
            generation++; phase = "start_requested"; error = "none";
            observed = false; advertised = false; deadline = 0;
            admissionScope = null; internalToken = null; admissionIssuedAt = 0; admissionState = "none";
            return generation;
        }
        synchronized void prepareAdmission(long expected, boolean shell, long now) {
            if (!mayStart(expected) || now < 0 || !"none".equals(admissionState))
                throw new IllegalStateException("start_admission_invalid");
            admissionScope = shell ? "shell" : "internal";
            internalToken = shell ? null : UUID.randomUUID().toString().replace("-", "");
            admissionIssuedAt = now;
            admissionState = "prepared";
        }
        synchronized String internalToken() { return internalToken; }
        synchronized boolean consumeAdmission(boolean shell, String process, long expected, String token, long now) {
            if (!mayStart(expected) || !"prepared".equals(admissionState) || now < admissionIssuedAt
                || !processInstance.equals(process) || !(shell ? "shell" : "internal").equals(admissionScope)
                || (shell ? token != null : internalToken == null || !internalToken.equals(token))) return false;
            admissionScope = null; internalToken = null; admissionState = "consumed";
            return true;
        }
        private void cancelPendingAdmission() {
            admissionScope = null; internalToken = null;
            if ("prepared".equals(admissionState)) admissionState = "cancelled";
        }
        synchronized boolean mayStart(long expected) {
            return expected == generation && "start_requested".equals(phase);
        }
        synchronized long generation() { return generation; }
        synchronized boolean serviceStarted(long expected, long until) {
            if (!mayStart(expected) || until <= 0) return false;
            observed = true; live = true; deadline = until; phase = "starting"; return true;
        }
        synchronized void advertising(long expected) {
            if (expected == generation && live && "starting".equals(phase)) {
                advertised = true; phase = "advertising";
            }
        }
        synchronized void failure(long expected, String code) {
            if (expected != generation) return;
            cancelPendingAdmission();
            error = ("start_dispatch_failed".equals(code) || "deadline_expired".equals(code)
                || "service_permissions_denied".equals(code) || "stop_dispatch_failed".equals(code)
                || "start_dispatch_background_restricted".equals(code) || "start_dispatch_security_rejected".equals(code)
                || "start_dispatch_argument_rejected".equals(code) || "start_dispatch_state_rejected".equals(code)
                || "service_start_failed".equals(code) || "advertising_deadline_expired".equals(code)
                || "advertising_failed".equals(code) || "gatt_service_failed".equals(code)
                || "advertising_start_failed".equals(code)) ? code : "service_failure_unknown";
            advertised = false; phase = "failed";
        }
        synchronized boolean requestStop() {
            if (generation == 0 || (!live && observed && ("stopped".equals(phase) || "failed".equals(phase)))) return false;
            advertised = false; cancelPendingAdmission(); phase = "stop_requested"; return true;
        }
        synchronized void destroyed(long expected) {
            if (expected != generation) return;
            cancelPendingAdmission();
            observed = true; live = false; advertised = false;
            phase = "none".equals(error) ? "stopped" : "failed";
        }
        synchronized JSONObject snapshot(String action, String outcome, boolean accepted, long now) throws Exception {
            return new JSONObject().put("$schema", "rusty.quest.hub_ble_carrier_control_receipt.v2")
                .put("action", action).put("outcome", outcome).put("request_accepted", accepted)
                .put("process_instance_id", processInstance).put("generation", generation)
                .put("carrier_state", phase).put("error_code", error)
                .put("service_observed", observed).put("service_live", live)
                .put("advertising_callback_confirmed", advertised).put("carrier_ready_now", live && advertised && now >= 0 && now < deadline).put("deadline_elapsed_ms", deadline)
                .put("radio_cleanup_qualified", false).put("controller_authority_claimed", false)
                .put("pairing_secret_in_receipt", false).put("production_eligible", false)
                .put("admission_policy", "single_use_until_consumed_cancelled_or_process_end")
                .put("admission_state", admissionState)
                .put("admission_scope", admissionScope == null ? "none" : admissionScope)
                .put("admission_issued_elapsed_ms", admissionIssuedAt)
                .put("admission_age_ms", "none".equals(admissionState) || now < admissionIssuedAt ? 0 : now - admissionIssuedAt);
        }
    }
    static boolean acceptStartFields(State state, boolean shell, int fieldCount, Object process, Object generation, Object token, long now) {
        if(fieldCount != (shell ? 2 : 3) || !(process instanceof String) || !(generation instanceof Long)
            || (shell ? token != null : !(token instanceof String))) return false;
        return state.consumeAdmission(shell,(String)process,((Long)generation).longValue(),(String)token,now);
    }
    private final Port port;
    private final State state;
    BridgeController(Port port, State state) { this.port = port; this.state = state; }
    BridgeController(final Context context) {
        this(new Port() {
            public long now() { return SystemClock.elapsedRealtime(); }
            public boolean permissionsReady() {
                return context.checkSelfPermission(HubReadiness.PERMISSION) == PackageManager.PERMISSION_GRANTED
                    && (Build.VERSION.SDK_INT < 31 || (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED));
            }
            public void requireHubCurrent() throws Exception { new HubReadiness(context).requireCurrent(); }
            public void start() { context.startForegroundService(new Intent(context, BridgeService.class).setAction(BridgeService.START)
                .putExtra("process_instance_id", CURRENT.processInstance).putExtra("generation", CURRENT.generation())
                .putExtra("internal_token", CURRENT.internalToken())); }
            public boolean stop() { return context.stopService(new Intent(context, BridgeService.class)); }
        }, CURRENT);
    }
    // Fixed class-only categories. No message, stack, nested cause or caller text leaves the app.
    static String startFailureCode(Class<?> exceptionType) {
        String type = exceptionType.getName();
        if ("android.app.ForegroundServiceStartNotAllowedException".equals(type))
            return "start_dispatch_background_restricted";
        if ("java.lang.SecurityException".equals(type)) return "start_dispatch_security_rejected";
        if ("java.lang.IllegalArgumentException".equals(type)) return "start_dispatch_argument_rejected";
        if ("java.lang.IllegalStateException".equals(type)) return "start_dispatch_state_rejected";
        return "start_dispatch_failed";
    }
    JSONObject enable() throws Exception { return enable(false); }
    JSONObject enableShell(int uid) throws Exception { authorize(uid,"enable",null,false); return enable(true); }
    private JSONObject enable(boolean shell) throws Exception {
        if (!port.permissionsReady()) return state.snapshot("enable", "permissions_denied", false, port.now());
        try { port.requireHubCurrent(); } catch (Exception denied) { return state.snapshot("enable", "hub_readiness_denied", false, port.now()); }
        long generation = state.requestStart();
        if (generation == 0) return state.snapshot("enable", "existing_scope_not_renewed", false, port.now());
        state.prepareAdmission(generation, shell, port.now());
        if (shell) return state.snapshot("enable", "shell_start_prepared", true, port.now());
        try { port.start(); return state.snapshot("enable", "start_requested", true, port.now()); }
        catch (Exception denied) { state.failure(generation, startFailureCode(denied.getClass())); return state.snapshot("enable", "start_dispatch_failed", false, port.now()); }
    }
    JSONObject disable() throws Exception {
        if (!state.requestStop()) return state.snapshot("disable", "already_locally_stopped", false, port.now());
        try { boolean dispatched = port.stop(); return state.snapshot("disable", dispatched ? "stop_requested" : "stop_dispatch_absent_unknown", dispatched, port.now()); }
        catch (Exception denied) { return state.snapshot("disable", "stop_dispatch_failed_unknown", false, port.now()); }
    }
    JSONObject status() throws Exception { return state.snapshot("status", "observed", false, port.now()); }
    static void authorize(int uid, String method, String argument, boolean extras) {
        if (uid != 2000) throw new SecurityException("carrier_control_shell_uid_required");
        if (argument != null || extras || !("enable".equals(method) || "disable".equals(method) || "status".equals(method)))
            throw new IllegalArgumentException("carrier_control_closed_command_required");
    }
    JSONObject invokeShell(int uid, String method) throws Exception {
        authorize(uid,method,null,false);
        return "enable".equals(method) ? enableShell(uid) : invoke(method);
    }
    JSONObject invoke(String method) throws Exception {
        if ("enable".equals(method)) return enable();
        if ("disable".equals(method)) return disable();
        if ("status".equals(method)) return status();
        throw new IllegalArgumentException("carrier_control_closed_command_required");
    }
}
