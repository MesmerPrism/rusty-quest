package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService operatorReceiptWriter = Executors.newSingleThreadExecutor();
    private long runtimeGeneration = 1L;
    private boolean nativeLibraryReady;
    private String nativeLibraryReason = "not-loaded";
    private volatile boolean closed;
    private volatile boolean cleanupComplete;

    static synchronized void reopenClosedFromExplicitLaunch() {
        if (instance != null && instance.closed && instance.cleanupComplete) instance = null;
    }

    static boolean closeExistingFromOwner() throws InterruptedException {
        PolarSensorRuntime current = instance;
        if (current == null) return true;
        synchronized (current) {
            if (!current.closed) {
                current.closed = true;
                current.panel.shutdown();
                current.operatorReceiptWriter.shutdown();
            }
        }
        boolean clean = current.panel.awaitShutdown()
            && current.operatorReceiptWriter.awaitTermination(5L, java.util.concurrent.TimeUnit.SECONDS);
        current.cleanupComplete = clean;
        return clean;
    }

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

    /** Read-only app-lifetime projection for the experimenter home. */
    JSONObject experimenterStatusProjection() {
        JSONObject projection = panel.experimenterStatusProjection();
        try {
            projection.put("runtime_generation", runtimeGeneration);
            projection.put("native_library", nativeLibraryReady ? "ready" : nativeLibraryReason);
        } catch (Exception ignored) {
        }
        return projection;
    }

    /** Idempotent, non-blocking cold-root request; the process Polar owner remains sole BLE owner. */
    void ensureAutoConnection() {
        if (closed) return;
        panel.ensureAutoConnection();
    }

    private void dispatch(String rawCommand, String token, String origin) {
        if (closed) return;
        String safeToken = token == null ? "" : token;
        if (!nativeLibraryReady) {
            enqueueReceipt(safeToken, rawCommand, origin, null, "rejected", nativeLibraryReason);
            return;
        }
        PolarSensorPanel.OperatorCommandStatus commandStatus = panel.handleCommand(rawCommand);
        enqueueReceipt(
            safeToken,
            rawCommand,
            origin,
            commandStatus,
            commandStatus.dispatchStatus,
            commandStatus.reasonCode
        );
    }

    private void enqueueReceipt(
        String token,
        String rawCommand,
        String origin,
        PolarSensorPanel.OperatorCommandStatus commandStatus,
        String dispatchStatus,
        String reasonCode
    ) {
        final OperatorReceiptSnapshot snapshot = new OperatorReceiptSnapshot(
            token,
            rawCommand,
            origin,
            dispatchStatus,
            reasonCode,
            runtimeGeneration,
            panel.isPanelAttached(),
            nativeLibraryReady ? "ready" : nativeLibraryReason,
            SystemClock.elapsedRealtimeNanos(),
            commandStatus
        );
        Log.i(
            TAG,
            MARKER_PREFIX + " channel=polar-sensor-runtime receipt_persistence=accepted receipt_effect=pending"
        );
        operatorReceiptWriter.execute(new Runnable() {
            @Override
            public void run() {
                persistReceiptOnBackgroundThread(snapshot);
            }
        });
    }

    private void persistReceiptOnBackgroundThread(OperatorReceiptSnapshot snapshot) {
        PolarStatusPersistenceThreadPolicy.requireBackgroundThread(
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        );
        try {
            JSONObject receipt = new JSONObject()
                .put("schema", "rusty.quest.native_renderer.polar_sensor_operator_status.v2")
                .put("token", snapshot.token)
                .put("command", snapshot.command)
                .put("command_origin", snapshot.origin)
                .put("dispatch_status", snapshot.dispatchStatus)
                .put("reason_code", snapshot.reasonCode)
                .put("runtime_generation", snapshot.runtimeGeneration)
                .put("panel_attached", snapshot.panelAttached)
                .put("native_library", snapshot.nativeLibrary)
                .put("updated_at_elapsed_realtime_ns", snapshot.updatedAtElapsedRealtimeNs);
            if (snapshot.commandStatus != null) {
                receipt.put("effect_status", snapshot.commandStatus.effectStatus);
                receipt.put("operation_generation", snapshot.commandStatus.operationGeneration);
                receipt.put("capture_session_id", snapshot.commandStatus.captureSessionId);
                receipt.put(
                    "polar_status",
                    snapshot.commandStatus.freshPolarStatus == null
                        ? JSONObject.NULL : snapshot.commandStatus.freshPolarStatus
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

    private static final class OperatorReceiptSnapshot {
        final String token;
        final String command;
        final String origin;
        final String dispatchStatus;
        final String reasonCode;
        final long runtimeGeneration;
        final boolean panelAttached;
        final String nativeLibrary;
        final long updatedAtElapsedRealtimeNs;
        final PolarSensorPanel.OperatorCommandStatus commandStatus;

        OperatorReceiptSnapshot(
            String token,
            String command,
            String origin,
            String dispatchStatus,
            String reasonCode,
            long runtimeGeneration,
            boolean panelAttached,
            String nativeLibrary,
            long updatedAtElapsedRealtimeNs,
            PolarSensorPanel.OperatorCommandStatus commandStatus
        ) {
            this.token = token == null ? "" : token;
            this.command = command == null ? "" : command;
            this.origin = origin == null ? "unknown" : origin;
            this.dispatchStatus = dispatchStatus == null ? "unknown" : dispatchStatus;
            this.reasonCode = reasonCode == null ? "unknown" : reasonCode;
            this.runtimeGeneration = runtimeGeneration;
            this.panelAttached = panelAttached;
            this.nativeLibrary = nativeLibrary == null ? "unknown" : nativeLibrary;
            this.updatedAtElapsedRealtimeNs = updatedAtElapsedRealtimeNs;
            this.commandStatus = commandStatus;
        }
    }
}

/** Pure admission policy exercised without Android BLE. */
final class PolarAutoConnectionPolicy {
    enum Decision {
        CONNECTED,
        PERMISSION_REQUIRED,
        BLUETOOTH_UNAVAILABLE,
        WAIT_FOR_IN_FLIGHT,
        START_SCAN,
        CONNECT_PAIRED,
        CONNECT_UNIQUE,
        REQUIRES_SELECTION,
        NOT_FOUND,
        STALE_GENERATION,
        RETRY_EXHAUSTED
    }

    private PolarAutoConnectionPolicy() {}

    static String preferredScanName(String advertisedName, String cachedName) {
        String advertised = advertisedName == null ? "" : advertisedName.trim();
        if (!advertised.isEmpty()) {
            return advertised;
        }
        return cachedName == null ? "" : cachedName.trim();
    }

    static boolean acceptsScanCandidate(
        String preferredName,
        boolean hasHeartRateService,
        boolean hasPmdService
    ) {
        String lower = preferredName == null ? "" : preferredName.toLowerCase(java.util.Locale.US);
        return lower.contains("polar")
            || lower.contains("h10")
            || lower.contains("h9")
            || hasHeartRateService
            || hasPmdService;
    }

    static Decision preflight(
        boolean permissionReady,
        String bluetoothState,
        boolean connected,
        boolean inFlight,
        int attempts,
        int maximumAttempts
    ) {
        if (connected) return Decision.CONNECTED;
        if (!permissionReady) return Decision.PERMISSION_REQUIRED;
        if (!"on".equals(bluetoothState)) return Decision.BLUETOOTH_UNAVAILABLE;
        if (inFlight) return Decision.WAIT_FOR_IN_FLIGHT;
        if (attempts >= maximumAttempts) return Decision.RETRY_EXHAUSTED;
        return Decision.START_SCAN;
    }

    static Decision afterScan(
        long expectedGeneration,
        long observedGeneration,
        int candidateCount,
        int pairedMatches
    ) {
        if (expectedGeneration != observedGeneration) return Decision.STALE_GENERATION;
        if (pairedMatches == 1) return Decision.CONNECT_PAIRED;
        if (candidateCount == 1) return Decision.CONNECT_UNIQUE;
        if (candidateCount > 1) return Decision.REQUIRES_SELECTION;
        return Decision.NOT_FOUND;
    }

    static int admittedCandidateIndex(
        Decision decision,
        int candidateCount,
        int pairedCandidateIndex
    ) {
        if (decision == Decision.CONNECT_PAIRED
                && pairedCandidateIndex >= 0
                && pairedCandidateIndex < candidateCount) {
            return pairedCandidateIndex;
        }
        if (decision == Decision.CONNECT_UNIQUE && candidateCount == 1) {
            return 0;
        }
        return -1;
    }

    static boolean evidenceFresh(
        long expectedGeneration,
        long observedGeneration,
        long evidenceAtUnixMs,
        long nowUnixMs,
        long maximumAgeMs,
        long deadlineElapsedMs,
        long nowElapsedMs
    ) {
        if (expectedGeneration <= 0L
                || expectedGeneration != observedGeneration
                || evidenceAtUnixMs <= 0L
                || nowUnixMs < evidenceAtUnixMs
                || nowUnixMs - evidenceAtUnixMs > maximumAgeMs) {
            return false;
        }
        return deadlineElapsedMs <= 0L || nowElapsedMs <= deadlineElapsedMs;
    }

    static boolean mayPublishLiveEvidence(
        Object admittedGatt,
        Object currentGatt,
        long admittedGeneration,
        long currentGeneration,
        boolean connected,
        boolean closing
    ) {
        return !closing
            && connected
            && admittedGatt != null
            && admittedGatt == currentGatt
            && admittedGeneration > 0L
            && admittedGeneration == currentGeneration;
    }
}

final class PolarStatusPersistenceThreadPolicy {
    private PolarStatusPersistenceThreadPolicy() {}

    static void requireBackgroundThread(boolean mainThread) {
        if (mainThread) {
            throw new IllegalStateException("polar-status-persistence-main-thread-forbidden");
        }
    }
}
