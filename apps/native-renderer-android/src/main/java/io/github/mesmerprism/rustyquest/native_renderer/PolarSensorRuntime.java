package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Process-local owner for direct Polar acquisition and synchronized capture.
 *
 * The optional control panel attaches a view to this owner.  Command receivers
 * use the same owner directly, so operating the acquisition cannot foreground
 * a 2D Activity or change the native OpenXR activity's input focus.
 */
final class PolarSensorRuntime {
    static final String ACTION_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.POLAR_SENSOR_RUNTIME_COMMAND";
    static final String EXTRA_COMMAND = "polar_sensor_runtime_command";
    static final String EXTRA_TOKEN = "polar_sensor_runtime_command_token";
    static final String OPERATOR_STATUS_FILE = "polar_sensor_operator_status.json";
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static volatile PolarSensorRuntime instance;

    private final Context appContext;
    private final PolarSensorPanel panel;
    private long runtimeGeneration = 1L;
    private boolean nativeLibraryReady;
    private String nativeLibraryReason = "not-loaded";

    private PolarSensorRuntime(Context context) {
        appContext = context.getApplicationContext();
        panel = new PolarSensorPanel(appContext);
        try {
            System.loadLibrary("rusty_quest_native_renderer");
            nativeLibraryReady = true;
            nativeLibraryReason = "loaded";
        } catch (UnsatisfiedLinkError error) {
            nativeLibraryReady = false;
            nativeLibraryReason = "native-library-unavailable";
        }
    }

    static PolarSensorRuntime forApplication(Context context) {
        PolarSensorRuntime current = instance;
        if (current != null) {
            return current;
        }
        synchronized (PolarSensorRuntime.class) {
            if (instance == null) {
                instance = new PolarSensorRuntime(context);
            }
            return instance;
        }
    }

    PolarSensorPanel attachPanel(Activity activity, PolarSensorPanel.Host host) {
        panel.attachPanel(activity, host);
        return panel;
    }

    void detachPanel(Activity activity) {
        panel.detachPanel(activity);
    }

    void dispatchFromCli(String rawCommand, String token) {
        dispatch(rawCommand, token, "cli-receiver");
    }

    void dispatchFromPanel(String rawCommand, String token) {
        dispatch(rawCommand, token, "panel-compatibility");
    }

    private void dispatch(String rawCommand, String token, String origin) {
        String safeToken = token == null ? "" : token;
        if (!nativeLibraryReady) {
            writeReceipt(safeToken, rawCommand, origin, null, "rejected", nativeLibraryReason);
            return;
        }
        PolarSensorPanel.OperatorCommandStatus commandStatus = panel.handleCommand(rawCommand);
        writeReceipt(
            safeToken,
            rawCommand,
            origin,
            commandStatus,
            commandStatus.dispatchStatus,
            commandStatus.reasonCode
        );
    }

    private void writeReceipt(
        String token,
        String rawCommand,
        String origin,
        PolarSensorPanel.OperatorCommandStatus commandStatus,
        String dispatchStatus,
        String reasonCode
    ) {
        try {
            JSONObject receipt = new JSONObject()
                .put("schema", "rusty.quest.native_renderer.polar_sensor_operator_status.v2")
                .put("token", token)
                .put("command", rawCommand == null ? "" : rawCommand)
                .put("command_origin", origin)
                .put("dispatch_status", dispatchStatus == null ? "unknown" : dispatchStatus)
                .put("reason_code", reasonCode == null ? "unknown" : reasonCode)
                .put("runtime_generation", runtimeGeneration)
                .put("panel_attached", panel.isPanelAttached())
                .put("native_library", nativeLibraryReady ? "ready" : nativeLibraryReason)
                .put("updated_at_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos());
            if (commandStatus != null) {
                receipt.put("effect_status", commandStatus.effectStatus);
                receipt.put("operation_generation", commandStatus.operationGeneration);
                receipt.put("capture_session_id", commandStatus.captureSessionId);
                receipt.put(
                    "polar_status",
                    commandStatus.freshPolarStatus == null ? JSONObject.NULL : commandStatus.freshPolarStatus
                );
            } else {
                receipt.put("effect_status", "not-started");
                receipt.put("operation_generation", 0L);
                receipt.put("capture_session_id", "none");
                receipt.put("polar_status", JSONObject.NULL);
            }
            FileOutputStream out = appContext.openFileOutput(OPERATOR_STATUS_FILE, Context.MODE_PRIVATE);
            try {
                out.write(receipt.toString(2).getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Exception error) {
            Log.i(
                TAG,
                MARKER_PREFIX + " channel=polar-sensor-runtime status=receipt-write-failed"
            );
        }
    }
}
