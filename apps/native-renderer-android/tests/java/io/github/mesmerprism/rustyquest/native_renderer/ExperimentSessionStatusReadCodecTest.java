package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;

/** Host wrapper: the runner inserts the exact production nested codec at the marker. */
public final class ExperimentSessionStatusReadCodecTest {
    private static ExperimentSessionPanelCoordinator EXPERIMENT_SESSION_PANEL;
    private static int controls;
    private static void check(boolean value, String detail) {
        controls++; if (!value) throw new AssertionError(detail);
    }
    // PRODUCTION_CODEC
    private static JSONObject response(String operation, String status) throws Exception {
        return new JSONObject().put("schema", "rusty.quest.experiment_session.response.v1")
            .put("command_status", status).put("reason_code", "none")
            .put("readback", new JSONObject().put("runtime_epoch", 1L)
                .put("generation", 0L).put("revision", 1L).put("phase", "idle")
                .put("control_state", "idle").put("last_operation_id", "")
                .put("last_operation_status", operation).put("last_reason", "none")
                .put("initialization_status", "ready").put("inventory_status", "ready")
                .put("recording_root_status", "ready"));
    }
    private static void read(JSONObject raw, long completed, long now) {
        ExperimentSessionAndroidShell.SessionReadback r =
            new ExperimentSessionJsonCodec().parse(raw.toString(), null, false);
        EXPERIMENT_SESSION_PANEL.acceptNativeReadback(r.runtimeEpoch, r.receipt,
            completed, now, "accepted".equals(r.commandStatus));
    }
    public static void main(String[] args) throws Exception {
        for (String last : new String[] {"none", "failed", "rejected"}) {
            EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
            JSONObject raw = response(last, "accepted");
            ExperimentSessionAndroidShell.SessionReadback r =
                new ExperimentSessionJsonCodec().parse(raw.toString(), null, false);
            check(!r.receipt.accepted && !r.receipt.durable,
                "status must not promote last command " + last);
            read(raw, 100L, 100L);
            check(EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(101L).observed,
                "successful actual codec status observes " + last);
            check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                    "state_accepted=true state_reason=accepted witness_reason=observed"),
                "diagnostic distinguishes observed state from last rejected command " + last);
        }
        for (String status : new String[] {"queued", "rejected", "unknown"}) {
            EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
            read(response("none", status), 100L, 100L);
            check(!EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(101L).observed,
                "nonaccepted envelope cannot observe " + status);
            check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                    "witness_reason=status_response_rejected"),
                "diagnostic identifies unsuccessful status envelope " + status);
        }
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        read(response("none", "accepted"), -1L, 100L);
        check(!EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(101L).observed,
            "initialization/command callback lacks completed read");
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        read(response("none", "accepted"), 102L, 100L);
        check(!EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(101L).observed,
            "future completed read denied");
        check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                "witness_reason=completion_future"), "future completion diagnostic");
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        read(response("none", "accepted"), 100L, 100L);
        JSONObject old = response("none", "accepted");
        old.getJSONObject("readback").put("revision", 0L);
        read(old, 200L, 200L);
        check(EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(201L).readId == 100L,
            "stale revision cannot advance witness");
        check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                "state_accepted=false state_reason=revision_regression witness_reason=state_rejected"),
            "diagnostic pinpoints coordinator rejection without advancing witness");
        JSONObject malformed = response("none", "accepted").put("schema", "wrong");
        boolean denied = false;
        try { read(malformed, 300L, 300L); } catch (IllegalArgumentException expected) { denied = true; }
        check(denied, "actual codec rejects wrong schema");
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        read(response("none", "accepted"), 100L, 100L);
        ExperimentSessionPanelCoordinator.NativeCommand pending =
            EXPERIMENT_SESSION_PANEL.arm("condition-a", 0);
        JSONObject rejected = response("rejected", "accepted");
        rejected.getJSONObject("readback").put("last_operation_id", pending.operationId);
        read(rejected, 400L, 400L);
        check(EXPERIMENT_SESSION_PANEL.snapshot().phase == ExperimentSessionPanelState.Phase.ERROR,
            "successful status read preserves actual rejected pending command");
        check(EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(401L).observed,
            "actual rejected command state can be observed without success credit");
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        JSONObject noEpoch = response("none", "accepted");
        noEpoch.getJSONObject("readback").put("runtime_epoch", 0L);
        read(noEpoch, 100L, 100L);
        check(!EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(101L).observed,
            "epoch zero cannot observe");
        check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                "witness_reason=epoch_invalid"), "missing epoch diagnostic");
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        JSONObject unknownPhase = response("none", "accepted");
        unknownPhase.getJSONObject("readback").put("phase", "unsupported")
            .put("control_state", "unsupported");
        read(unknownPhase, 100L, 100L);
        check(!EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(101L).observed,
            "unknown phase/control cannot create a witness");
        check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                "witness_reason=phase_unknown"), "unknown phase diagnostic is closed");
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        read(response("none", "accepted"), 100L, 100L);
        EXPERIMENT_SESSION_PANEL.arm("condition-a", 0);
        read(response("none", "accepted"), 200L, 200L);
        check(!EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(201L).observed,
            "unrelated native operation cannot refresh pending-state witness");
        check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                "state_accepted=false state_reason=pending_operation_mismatch witness_reason=state_rejected"),
            "pending-operation rejection diagnostic");
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        read(response("none", "accepted"), 100L, 100L);
        read(response("none", "accepted"), 50L, 50L);
        check(!EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(51L).observed,
            "clock rollback clears witness despite otherwise valid receipt");
        check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                "witness_reason=clock_invalid"), "clock rollback diagnostic");
        EXPERIMENT_SESSION_PANEL = new ExperimentSessionPanelCoordinator();
        read(response("none", "accepted"), 100L, 100L);
        EXPERIMENT_SESSION_PANEL.admitTrustedColdLaunch(2L);
        read(response("none", "accepted"), 200L, 200L);
        check(!EXPERIMENT_SESSION_PANEL.nativeStatusSnapshot(201L).observed,
            "expected new runtime cannot reuse old epoch witness");
        check(EXPERIMENT_SESSION_PANEL.nativeStatusReadbackDiagnostic().contains(
                "witness_reason=fresh_runtime_expected"), "fresh-runtime expectation diagnostic");
        System.out.println("StatusReadCodecTest PASS controls=" + controls
            + " exact production codec/coordinator; JNI responses modeled; no device proof");
    }
}

// Only condition-readiness dependencies of the extracted codec are modeled.
final class ControlPanelActivity {
    static String conditionBreathGuidanceReadiness(String condition) { return "not-ready"; }
}
