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
    static final String EXTRA_PROJECTED_REQUEST = "polar_sensor_projected_request_b64";
    static final String PROJECTED_STATUS_FILE = "polar_sensor_projected_action_status.json";
    static final String OPERATOR_STATUS_FILE = "polar_sensor_operator_status.json";
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static volatile PolarSensorRuntime instance;

    private final Context appContext;
    private final PolarSensorPanel panel;
    private final ExecutorService operatorReceiptWriter = Executors.newSingleThreadExecutor();
    private static long nextRuntimeGeneration = 1L;
    private final long runtimeGeneration;
    private final String projectedOwnerEpoch = java.util.UUID.randomUUID().toString().replace("-", "");
    private final PolarProjectedStopActions projectedStops = new PolarProjectedStopActions();
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
        if (nextRuntimeGeneration <= 0 || nextRuntimeGeneration == Long.MAX_VALUE)
            throw new IllegalStateException("polar-runtime-generation-exhausted");
        runtimeGeneration = nextRuntimeGeneration++;
        appContext = context.getApplicationContext();
        panel = new PolarSensorPanel(appContext,this);
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

    /** Existing DUMP-protected receiver, no new exported authority or BLE capability. */
    void dispatchProjectedFromCli(String encoded, String token) {
        dispatchProjected(encoded, token, "cli-receiver");
    }

    void dispatchProjectedFromPanel(PolarSensorPanel source,String operation) {
        if(source!=panel||closed||instance!=this) {
            if(source!=null)source.showProjectedStopRejection(operation,closed?"polar-owner-closed":"polar-owner-replaced");
            return;
        }
        try {
            JSONObject request=projectedStops.project(projectedTarget());
            JSONObject command=new JSONObject().put("schema",PolarProjectedStopActions.SCHEMA)
                .put("operation",operation).put("operation_id",request.getString(operation+"_operation_id"));
            for(String field:new String[]{"pid","runtime_generation","owner_epoch","action_revision","selected_sensor","connected_sensor","acc_revision","ecg_revision"})
                command.put(field,request.get(field));
            JSONObject response=dispatchProjected(android.util.Base64.encodeToString(command.toString().getBytes(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP),java.util.UUID.randomUUID().toString().replace("-",""),"panel-owner");
            panel.showProjectedStopFeedback(operation,response);
        } catch(Exception unavailable) {
            try { panel.showProjectedStopFeedback(operation,new JSONObject().put("dispatch_status","rejected")
                .put("reason",closed?"polar-owner-closed":"projected-state-unavailable")); } catch(Exception ignored) { }
            Log.i(TAG,MARKER_PREFIX+" channel=polar-sensor-runtime status=projected-panel-unavailable");
        }
    }

    private synchronized JSONObject dispatchProjected(String encoded, String token, String origin) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            throw new IllegalStateException("polar-main-thread-required");
        if (closed || token == null || !token.matches("[a-f0-9]{32}")) return null;
        JSONObject response = new JSONObject();
        try {
            if (encoded == null || encoded.length() > 4096) throw new IllegalArgumentException("bounded-request-required");
            byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
            if (bytes.length == 0 || bytes.length > 2048) throw new IllegalArgumentException("bounded-request-required");
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JSONObject request = new JSONObject(text);
            if (!"status".equals(request.opt("operation")) && !nativeLibraryReady)
                throw new IllegalStateException("native-library-unavailable");
            PolarProjectedStopActions.Target target = projectedTarget();
            JSONObject receipt = projectedStops.execute(request, target);
            response.put("action_receipt", receipt);
            try { response.put("projection", projectedStops.project(target)); }
            catch (Exception unavailable) { response.put("projection_unavailable", true); }
        } catch (Exception rejected) {
            try { response.put("dispatch_status", "rejected").put("reason", rejected.getMessage()); }
            catch (Exception ignored) { }
        }
        try {
            response.put("schema", "rusty.quest.native_renderer.polar_projected_invocation.v1")
                .put("token", token).put("origin", origin);
            final byte[] snapshot = response.toString().getBytes(StandardCharsets.UTF_8);
            operatorReceiptWriter.execute(new Runnable() {
                public void run() {
                    try {
                        FileOutputStream out = appContext.openFileOutput(PROJECTED_STATUS_FILE, Context.MODE_PRIVATE);
                        try { out.write(snapshot); out.flush(); } finally { out.close(); }
                    } catch (Exception failed) { Log.i(TAG, MARKER_PREFIX + " channel=polar-sensor-runtime status=projected-receipt-write-failed"); }
                }
            });
        } catch (Exception failed) { Log.i(TAG, MARKER_PREFIX + " channel=polar-sensor-runtime status=projected-receipt-unavailable"); }
        return response;
    }

    private PolarProjectedStopActions.Target projectedTarget() {
        return new PolarProjectedStopActions.Target() {
            public boolean mainThread() { return android.os.Looper.myLooper() == android.os.Looper.getMainLooper(); }
            public JSONObject state() throws Exception { return panel.projectedStopState(runtimeGeneration, projectedOwnerEpoch, android.os.Process.myPid()); }
            public void stop(String mode) { panel.stopSpecificPmd(mode); }
        };
    }

    /** BLE can observe only an existing owner; it cannot create or adopt a replacement. */
    static JSONObject existingProjectedStopState() throws Exception {
        PolarSensorRuntime owner=instance;
        if(owner==null||owner.closed||!owner.nativeLibraryReady)return null;
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new IllegalStateException("polar-main-thread-required");
        return owner.projectedStops.project(owner.projectedTarget());
    }

    static JSONObject dispatchExistingProjectedStop(JSONObject request,String token) throws Exception {
        PolarSensorRuntime owner=instance;
        if(owner==null||owner.closed)throw new SecurityException("polar-owner-unavailable");
        return owner.dispatchProjected(android.util.Base64.encodeToString(request.toString().getBytes(StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP),token,"ble-polar-v3");
    }

    private long setupRevision,setupEpoch;
    private String setupTuple="";
    private final java.util.Map<String,String> setupReceipts=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Long> setupEffectTimes=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,String> setupOutcomes=new java.util.LinkedHashMap<>();
    private String setupPendingId="",setupPendingOperation="",setupPendingCandidate="";
    private long setupPendingSince;

    synchronized JSONObject remoteSetupStatus(long epoch) throws Exception {
        if(closed||instance!=this||!nativeLibraryReady)throw new SecurityException("polar-owner-unavailable");
        if(epoch<=0)throw new SecurityException("native-epoch-unavailable");
        if(setupEpoch!=0&&setupEpoch!=epoch&&!setupPendingId.isEmpty()){setupOutcomes.put(setupPendingId,"outcome_unknown");setupPendingId="";}
        setupEpoch=epoch;
        JSONObject status=panel.remoteSetupProjection();
        String tuple=status.optBoolean("p")+"|"+status.optBoolean("b")+"|"+status.optBoolean("c")+"|"+status.optString("x")+"|"+status.getJSONArray("d").toString();
        if(!tuple.equals(setupTuple)){setupTuple=tuple;setupRevision++;}
        return status.put("s",projectedOwnerEpoch).put("g",epoch).put("r",setupRevision);
    }
    static JSONObject existingRemoteSetupStatus(long epoch) throws Exception {
        PolarSensorRuntime owner=instance;return owner==null?null:owner.remoteSetupStatus(epoch);
    }
    static JSONObject dispatchExistingRemoteSetup(JSONObject command,long epoch) throws Exception {
        PolarSensorRuntime owner=instance;if(owner==null)throw new SecurityException("polar-owner-unavailable");
        return owner.dispatchRemoteSetup(command,epoch);
    }
    synchronized JSONObject dispatchRemoteSetup(JSONObject command,long epoch) throws Exception {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new SecurityException("polar-main-thread-required");
        String id=command.getString("id"),canonical=command.toString();
        if(setupReceipts.containsKey(id)) {
            if(!setupReceipts.get(id).equals(canonical))throw new SecurityException("setup-id-conflict");
            return remoteSetupReceipt(id,epoch);
        }
        JSONObject current=remoteSetupStatus(epoch);
        if(!command.getString("s").equals(current.getString("s"))||command.getLong("g")!=epoch||command.getLong("r")!=setupRevision)
            throw new SecurityException("stale-setup-fence");
        if(!current.getBoolean("p")||!current.getBoolean("b"))throw new SecurityException("platform-setup-required");
        if(!setupPendingId.isEmpty()||setupReceipts.size()>=128)throw new SecurityException("setup-pending-or-budget");
        String operation=command.getString("op");
        if("connect".equals(operation)&&current.getString("x").isEmpty() || operation.startsWith("start-")&&!current.getBoolean("c"))throw new SecurityException("sensor-not-ready");
        if("select".equals(operation))panel.selectRemoteCandidate(command.getString("d"));
        else panel.handleCommand(operation.replace('-','_'));
        if("scan".equals(operation)&&!panel.remoteSetupProjection().getBoolean("sc"))throw new SecurityException("scan-not-started");
        setupReceipts.put(id,canonical);setupRevision++;
        setupPendingId=id;setupPendingOperation=operation;setupPendingCandidate=command.getString("d");setupPendingSince=SystemClock.elapsedRealtime();
        return remoteSetupReceipt(id,epoch);
    }
    static JSONObject existingRemoteSetupReceipt(String id,long epoch) throws Exception {
        PolarSensorRuntime owner=instance;return owner==null?null:owner.remoteSetupReceipt(id,epoch);
    }
    synchronized JSONObject remoteSetupReceipt(String id,long epoch) throws Exception {
        if(!setupReceipts.containsKey(id))throw new SecurityException("setup-operation-unknown");
        JSONObject state=remoteSetupStatus(epoch);
        boolean effective=id.equals(setupPendingId)&&(
            "scan".equals(setupPendingOperation)&&!state.getBoolean("sc")
            ||"select".equals(setupPendingOperation)&&setupPendingCandidate.equals(state.getString("x"))
            ||"connect".equals(setupPendingOperation)&&state.getBoolean("c")
            ||"start-acc".equals(setupPendingOperation)&&state.getBoolean("a")
            ||"start-ecg".equals(setupPendingOperation)&&state.getBoolean("e"));
        String receiptState=effective?"confirmed":SystemClock.elapsedRealtime()-setupPendingSince>30000L?"outcome_unknown":"pending";
        if(!id.equals(setupPendingId))receiptState=setupOutcomes.containsKey(id)?setupOutcomes.get(id):"outcome_unknown";
        if(effective&&!setupEffectTimes.containsKey(id))setupEffectTimes.put(id,panel.remoteSetupEffectNs(setupPendingOperation));
        JSONObject result=new JSONObject().put("id",id).put("state",receiptState);
        if(setupEffectTimes.containsKey(id))result.put("effect_ns",setupEffectTimes.get(id));
        // Observation retires pending work; retained IDs never dispatch a second effect.
        if(!"pending".equals(receiptState)&&id.equals(setupPendingId)){setupOutcomes.put(id,receiptState);setupPendingId="";}
        return result;
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

    /** Requests the runtime BLE/location closure from the visible startup Activity. */
    boolean requestStartupPermissions(Activity activity) {
        if (closed) return false;
        return panel.requestStartupPermissions(activity);
    }

    /** Routes the startup Activity result to the process-owned Polar state machine. */
    void onRequestPermissionsResult(
        Activity activity,
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        if (closed) return;
        panel.onRequestPermissionsResult(activity, requestCode, permissions, grantResults);
    }

    /** Rechecks prerequisites and resumes the retained automatic intent after host resume. */
    void onHostResume() {
        if (closed) return;
        panel.onHostResume();
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
                .put("projected_stop_schema", PolarProjectedStopActions.SCHEMA)
                .put("projected_stop_owner_pid", android.os.Process.myPid())
                .put("projected_stop_owner_epoch", projectedOwnerEpoch)
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
        LOCATION_SERVICES_DISABLED,
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
        String locationServicesState,
        boolean connected,
        boolean inFlight,
        int attempts,
        int maximumAttempts
    ) {
        if (connected) return Decision.CONNECTED;
        if (!permissionReady) return Decision.PERMISSION_REQUIRED;
        if (!"on".equals(bluetoothState)) return Decision.BLUETOOTH_UNAVAILABLE;
        if ("disabled".equals(locationServicesState)) return Decision.LOCATION_SERVICES_DISABLED;
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

    static boolean shouldAdmitUniqueCandidateEarly(
        long expectedGeneration,
        long observedGeneration,
        int candidateCount,
        int pairedMatches
    ) {
        return expectedGeneration > 0L
            && expectedGeneration == observedGeneration
            && candidateCount == 1
            && pairedMatches == 0;
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

    static boolean automaticRecoveryAllowed(
        boolean requested,
        boolean manualControl,
        boolean closing,
        boolean connected
    ) {
        return requested && !manualControl && !closing && !connected;
    }

    static boolean resumeAutomaticScanAfterPermission(
        boolean requested,
        boolean manualControl,
        boolean scanPending
    ) {
        return requested && !manualControl && scanPending;
    }

    static long retryDelayMs(
        int attempts,
        int maximumAttempts,
        long withinCycleDelayMs,
        long betweenCycleDelayMs
    ) {
        return attempts >= maximumAttempts
            ? Math.max(0L, betweenCycleDelayMs)
            : Math.max(0L, withinCycleDelayMs);
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

/** Process-owner intent survives transport loss, never an intentional disconnect. */
final class PolarRecoveryIntent {
    private String identity = "";
    private boolean connection;
    private boolean intentionallyDisconnected;
    private boolean acc;
    private boolean ecg;
    private boolean streamsInitialized;
    private long accRevision;
    private long ecgRevision;
    private long accStopDispatched = -1L;
    private long ecgStopDispatched = -1L;

    void connected(String exactIdentity, String initialMode) {
        if (exactIdentity == null || exactIdentity.isEmpty() || "none".equals(exactIdentity)) {
            throw new IllegalArgumentException("missing connected identity");
        }
        if ((!identity.isEmpty() && !exactIdentity.equals(identity)) || !streamsInitialized) {
            acc = "acc".equals(initialMode);
            ecg = "ecg".equals(initialMode);
            accRevision++;
            ecgRevision++;
            streamsInitialized = true;
        }
        identity = exactIdentity;
        connection = true;
        intentionallyDisconnected = false;
    }

    boolean permitsCandidate(String candidate) {
        return !connection || identity.equals(candidate);
    }

    boolean connectionWanted() { return connection; }
    boolean automaticStartupAllowed() { return !intentionallyDisconnected; }
    boolean streamWanted(String mode) { return "acc".equals(mode) ? acc : "ecg".equals(mode) && ecg; }
    long streamRevision(String mode) { return "acc".equals(mode) ? accRevision : ecgRevision; }
    boolean claimStop(String mode, long revision) {
        if (streamWanted(mode) || streamRevision(mode) != revision) return false;
        if ("acc".equals(mode)) {
            if (accStopDispatched == revision) return false;
            accStopDispatched = revision;
        } else if ("ecg".equals(mode)) {
            if (ecgStopDispatched == revision) return false;
            ecgStopDispatched = revision;
        } else return false;
        return true;
    }

    void startStream(String mode) {
        streamsInitialized = true;
        if ("acc".equals(mode)) { acc = true; accRevision++; }
        else if ("ecg".equals(mode)) { ecg = true; ecgRevision++; }
        else throw new IllegalArgumentException("unsupported stream");
    }

    void stopStream(String mode) {
        streamsInitialized = true;
        if ("acc".equals(mode)) { acc = false; accRevision++; }
        else if ("ecg".equals(mode)) { ecg = false; ecgRevision++; }
        else throw new IllegalArgumentException("unsupported stream");
    }

    void stopStreams() { stopStream("acc"); stopStream("ecg"); }
    void stopExplicitStream(String mode,String selectedDefault) {
        if(!"acc".equals(mode)&&!"ecg".equals(mode))throw new IllegalArgumentException("unsupported stream");
        if(!streamsInitialized&&!mode.equals(selectedDefault))startStream(selectedDefault);
        stopStream(mode);
    }
    void disconnect() { connection = false; intentionallyDisconnected = true; identity = ""; stopStreams(); streamsInitialized = false; }
}

/** Closed, main-thread owner adapter. A dispatch receipt never certifies a PMD STOP ACK. */
final class PolarProjectedStopActions {
    static final String SCHEMA = "rusty.quest.native_renderer.polar_projected_stop.v1";
    interface Target {
        boolean mainThread();
        JSONObject state() throws Exception;
        void stop(String mode) throws Exception;
    }
    private static final String[] FENCE = {"pid", "runtime_generation", "owner_epoch", "selected_sensor",
        "connected_sensor", "acc_revision", "ecg_revision"};
    private long actionRevision;
    private Entry latest;

    static String feedback(String operation,JSONObject response) {
        String label="stop_ecg".equals(operation)?"ECG":"ACC";
        if(response==null)return "Stop "+label+" unavailable; no confirmation.";
        if("rejected".equals(response.optString("dispatch_status"))) {
            String reason=response.optString("reason");
            if("polar-owner-closed".equals(reason))return "Stop "+label+" unavailable: acquisition owner is closed.";
            if("native-library-unavailable".equals(reason))return "Stop "+label+" unavailable: acquisition runtime is not ready.";
            return "Stop "+label+" request rejected because current acquisition state is unavailable or changed; no confirmation.";
        }
        JSONObject receipt=response.optJSONObject("action_receipt");
        if(receipt!=null&&"dispatched".equals(receipt.optString("dispatch_status")))
            return "Stop "+label+" requested; device acknowledgement and data readback are pending.";
        return "Stop "+label+" outcome unknown; no automatic retry.";
    }
    private static final class Entry {
        final String id, request;
        String receipt;
        Entry(String id, String request, String receipt) { this.id=id;this.request=request;this.receipt=receipt; }
    }

    JSONObject project(Target target) throws Exception {
        if(!target.mainThread())throw new IllegalStateException("polar-main-thread-required");
        JSONObject state=target.state();String epoch=string(state,"owner_epoch");
        state.put("action_revision",actionRevision);
        if(Boolean.FALSE.equals(state.opt("closed"))) {
            state.put("stop_acc_operation_id",offeredId(epoch,actionRevision,"stop_acc"))
                .put("stop_ecg_operation_id",offeredId(epoch,actionRevision,"stop_ecg"));
        }
        return state;
    }

    /** Namespace binding, not a new authorization grant: DUMP/app-owner admission is unchanged. */
    static String offeredId(String epoch,long revision,String operation) throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(
            (epoch+"|"+revision+"|"+operation).getBytes(StandardCharsets.UTF_8));
        StringBuilder id=new StringBuilder(32);
        for(int i=0;i<16;i++)id.append(String.format(java.util.Locale.US,"%02x",digest[i]&255));
        return id.toString();
    }

    JSONObject execute(JSONObject request, Target target) throws Exception {
        if (!target.mainThread()) throw new IllegalStateException("polar-main-thread-required");
        if (!SCHEMA.equals(string(request,"schema"))) throw new IllegalArgumentException("closed-schema-required");
        String operation=string(request,"operation");
        if ("status".equals(operation)) {
            fields(request,new String[]{"schema","operation"});
            return new JSONObject().put("dispatch_status","observed").put("effect_status","read-only");
        }
        if (!"stop_acc".equals(operation) && !"stop_ecg".equals(operation))
            throw new IllegalArgumentException("unsupported-projected-operation");
        fields(request,new String[]{"schema","operation","operation_id","pid","runtime_generation","owner_epoch","action_revision",
            "selected_sensor","connected_sensor","acc_revision","ecg_revision"});
        String id=string(request,"operation_id");
        if (!id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("operation-id-required");
        long pid=number(request,"pid",true),generation=number(request,"runtime_generation",true);
        long acc=number(request,"acc_revision",false),ecg=number(request,"ecg_revision",false);
        long expectedRevision=number(request,"action_revision",false);
        String ownerEpoch=string(request,"owner_epoch");
        if(!ownerEpoch.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("runtime-owner-epoch-required");
        String selected=identity(request,"selected_sensor"),connected=identity(request,"connected_sensor");
        // Canonical field values, independent of incoming JSON key order/whitespace.
        if(!id.equals(offeredId(ownerEpoch,expectedRevision,operation)))
            throw new SecurityException("operation-id-not-bound-to-owner-action-revision");
        String canonical=operation+"|"+pid+"|"+generation+"|"+ownerEpoch+"|"+expectedRevision+"|"+selected+"|"+connected+"|"+acc+"|"+ecg;
        Entry prior=latest!=null&&latest.id.equals(id)?latest:null;
        if (prior!=null) {
            if (!prior.request.equals(canonical)) throw new SecurityException("operation-id-reused-for-different-request");
            return new JSONObject(prior.receipt);
        }
        if(expectedRevision!=actionRevision)throw new SecurityException("consumed-or-stale-action-revision");
        JSONObject before=target.state();
        if (!Boolean.FALSE.equals(before.opt("closed"))) throw new SecurityException("polar-owner-closed");
        for (String field:FENCE) {
            if (!request.get(field).equals(before.get(field))) {
                // JSON integer storage may differ between Integer/Long, never accept boolean/floating values.
                boolean integer=field.equals("pid")||field.equals("runtime_generation")||field.endsWith("_revision");
                if (!integer || number(request,field,!field.endsWith("_revision"))!=number(before,field,!field.endsWith("_revision")))
                    throw new SecurityException("polar-state-fence-mismatch-"+field);
            }
        }
        if(actionRevision==9007199254740991L)throw new IllegalStateException("action-revision-representation-exhausted");
        JSONObject receipt=new JSONObject().put("schema","rusty.quest.native_renderer.polar_stop_dispatch.v1")
            .put("operation_id",id).put("operation",operation).put("dispatch_status","outcome-unknown")
            .put("effect_status","not-confirmed").put("expected_state",new JSONObject(request.toString()));
        Entry entry=new Entry(id,canonical,receipt.toString());latest=entry;actionRevision++;
        // Reserve the ID before invoking the owner, including a partial/throwing effect. Never reissue.
        try {
            target.stop("stop_acc".equals(operation)?"acc":"ecg");
            receipt.put("dispatch_status","dispatched");
        } catch (Exception effectUnknown) {
            receipt.put("reason","owner-stop-threw-outcome-unknown");
        }
        entry.receipt=receipt.toString();
        return new JSONObject(entry.receipt);
    }

    private static String string(JSONObject object,String field) throws Exception {
        Object value=object.get(field);if (!(value instanceof String)) throw new IllegalArgumentException("string-required-"+field);
        return (String)value;
    }
    private static String identity(JSONObject object,String field) throws Exception {
        String value=string(object,field);
        if (!value.equals("none") && !value.matches("polar-[a-f0-9]{12}")) throw new IllegalArgumentException("closed-sensor-identity-required");
        return value;
    }
    private static long number(JSONObject object,String field,boolean positive) throws Exception {
        Object value=object.get(field);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("integer-required-"+field);
        long number=((Number)value).longValue();
        if (number < (positive?1:0) || number>9007199254740991L) throw new IllegalArgumentException("safe-integer-required-"+field);
        return number;
    }
    private static void fields(JSONObject object,String[] names) throws Exception {
        java.util.HashSet<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList(names));
        if (object.length()!=allowed.size()) throw new IllegalArgumentException("closed-fields-required");
        java.util.Iterator<String> keys=object.keys();
        while(keys.hasNext()) if(!allowed.remove(keys.next())) throw new IllegalArgumentException("closed-fields-required");
        if(!allowed.isEmpty()) throw new IllegalArgumentException("closed-fields-required");
    }
}
