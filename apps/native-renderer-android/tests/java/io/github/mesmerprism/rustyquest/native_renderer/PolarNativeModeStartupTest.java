package io.github.mesmerprism.rustyquest.native_renderer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

// Platform/JNI effects are inert; tokens below receive unmodified production bodies.
class Activity { boolean finishing, destroyed; }
final class NativeProductPanelActivity extends Activity {}
final class NativeRendererActivityComponents {
    static boolean isNative(Activity activity) { return activity instanceof NativeProductPanelActivity; }
}
final class Looper {
    static final Looper MAIN = new Looper();
    static Looper myLooper() { return MAIN; }
    static Looper getMainLooper() { return MAIN; }
}
final class Handler {
    final List<Runnable> tasks = new ArrayList<>();
    void post(Runnable action) { tasks.add(action); }
    void postDelayed(Runnable action, long delay) { tasks.add(action); }
    void drain() { List<Runnable> ready = new ArrayList<>(tasks); tasks.clear(); for (Runnable action : ready) action.run(); }
}
final class PolarBleRuntimeSupport {
    static boolean ready = true;
    static int prompts;
    static JSONObject statusJson(Object context) {
        try { return new JSONObject().put("runtime_permission_ready", ready)
            .put("bluetooth_adapter_state", "on").put("location_services_state", "enabled"); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    static String[] missingPermissions(Object context) { return new String[]{"fixture.permission"}; }
    static String join(String[] values, String separator) { return "fixture.permission"; }
    static String locationServicesState(Object context) { return "enabled"; }
    static void ensureReady(Activity activity, int request) { prompts++; }
}
final class NativeRendererSelfKioskApplication {
    static void beginSystemPrompt(Activity activity) {}
    static void endSystemPrompt(Activity activity) {}
}
final class NativeMeshUiProducer {
    static int hides;
    static boolean requestPanelVisibility(Activity activity, boolean visible) { if (!visible) hides++; return true; }
    static void productOwnerReady(Activity activity) {}
}
final class ExperimentSessionCommandGateway {
    static ExperimentSessionCommandGateway existing() { return null; }
    void requestDiscoveryPermissions(Activity activity) {}
}
final class PolarSensorRuntime {
    static PolarSensorRuntime instance;
    boolean closed, nativeLibraryReady = true;
    String nativeLibraryReason = "fixture";
    long runtimeGeneration = 1;
    final PolarSensorPanel panel = new PolarSensorPanel();
    static PolarSensorRuntime forApplication(Object context) { return instance; }
    __RUNTIME__
}
final class PolarSensorPanel {
    boolean closing, connected, scanning, automaticConnectionRequested, manualConnectionControl;
    boolean automaticConnectionRetryScheduled, automaticConnectionCycleActive, locationSettingsPromptPending;
    long automaticRecoveryGeneration, autoUniqueCandidateScheduledGeneration, autoScanGeneration, scanGeneration;
    long generation;
    int autoConnectionAttempts, pendingBleAction, scans;
    static final int AUTO_CONNECTION_MAX_ATTEMPTS = 2, PENDING_BLE_SCAN = 1, REQUEST_BLE_PERMISSIONS = 19;
    static final long AUTO_CONNECTION_RETRY_DELAY_MS = 2000, AUTO_CONNECTION_CYCLE_DELAY_MS = 10000;
    String automaticConnectionState = "not-started";
    final Object appContext = new Object();
    Activity activity;
    final Handler handler = new Handler();
    final PolarRecoveryIntent recoveryIntent = new PolarRecoveryIntent();
    boolean hasRequiredPermissions() { return PolarBleRuntimeSupport.ready; }
    void setStatusState(String state, String detail) {}
    void setAutomaticConnectionState(String state, String detail) { automaticConnectionState = state; }
    void marker(String detail) {}
    String markerToken(String detail) { return detail; }
    void ensureAutoConnectionGeneration() { if (generation == 0) generation = 1; }
    void advanceAutoConnectionGeneration() { generation++; }
    void startScan() { scans++; scanning = true; scanGeneration++; }
    void stopScan() { scanning = false; }
    void closeGatt() { connected = false; }
    void updateCounters() {}
    JSONObject experimenterStatusProjection() { return new JSONObject(); }
    void deliberateDisconnect() { disconnect(); }
    __PANEL__
}
class PanelBase { protected void onResume() {} }
final class BreathCompositionPanelModule extends PanelBase {
    static WeakReference<BreathCompositionPanelModule> remotePanel = new WeakReference<>(null);
    static final class NativeFixture implements ExperimentControlMode.NativeAccess {
        long value = 20;
        boolean reject;
        public long initialize(boolean selected) { return value; }
        public long read() { return value; }
        public long select(long expected, int requested) {
            if (!reject && value == expected && (value & 3) != requested)
                value = ((value >>> 3) + 1) * 8 + 4 + requested;
            return value;
        }
    }
    final NativeFixture nativeOwner = new NativeFixture();
    final ExperimentControlMode controlMode = new ExperimentControlMode(nativeOwner);
    final Activity activity;
    boolean nativeHost = true, retired, activityPanelResumed;
    int rebuilds;
    static final class Handoff { boolean pending() { return false; } void cancel() {} }
    Handoff uiControlHandoff;
    static final class RemotePending { String operation; }
    RemotePending remotePending;
    static final class Attachment {
        boolean visible;
        boolean presentationActive() { return false; }
        void showInActivity() {}
    }
    Attachment meshPanelAttachment;
    BreathCompositionPanelModule(Activity activity) { this.activity = activity; remotePanel = new WeakReference<>(this); }
    boolean hostCurrent() { return !retired && !activity.finishing && !activity.destroyed; }
    Activity hostActivity() { return activity; }
    Object getApplicationContext() { return activity; }
    boolean nativePresentation() { return nativeHost; }
    boolean rejectRemotePolarForMode(RemotePending pending) { return true; }
    void updateStatus(String text) {}
    void replaceBreathCompositionPanelContent(String topic) { rebuilds++; }
    void pausePanelProjection() {}
    void resumePanelProjection() { PolarSensorRuntime.forApplication(getApplicationContext()).onHostResume(); }
    void choose(int requested) { selectExperimentControlMode(controlMode.readSnapshot(), requested); }
    void choose(ExperimentControlMode.Snapshot captured, int requested) { selectExperimentControlMode(captured, requested); }
    __MODULE__
}
__SUPPORT__

public final class PolarNativeModeStartupTest {
    static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    static BreathCompositionPanelModule fresh() {
        PolarSensorRuntime.instance = new PolarSensorRuntime();
        PolarBleRuntimeSupport.ready = true;
        PolarBleRuntimeSupport.prompts = 0;
        return new BreathCompositionPanelModule(new NativeProductPanelActivity());
    }
    static PolarSensorPanel polar() { return PolarSensorRuntime.instance.panel; }
    interface Action { void run(); }
    static void invalid(Action action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid native choice was not rejected");
    }
    public static void main(String[] ignored) {
        check(NativeRendererExperimentLauncherPolicy.admits(false, "android.intent.action.MAIN", true, 1, false,
            "fixture.app", "fixture.Native", "fixture.app", "fixture.Native"), "normal exact launcher refused");
        check(!NativeRendererExperimentLauncherPolicy.admits(false, null, false, 0, false,
            "fixture.app", "fixture.Native", "fixture.app", "fixture.Native"), "component-only launch gained cold authority");
        check(!NativeRendererExperimentLauncherPolicy.admits(false, "android.intent.action.MAIN", true, 2, false,
            "fixture.app", "fixture.Native", "fixture.app", "fixture.Native"), "internal VR recovery route gained cold authority");
        check(!NativeRendererExperimentLauncherPolicy.admits(true, "android.intent.action.MAIN", true, 1, false,
            "fixture.app", "fixture.Native", "fixture.app", "fixture.Native"), "recreation gained cold authority");
        for (int mode : new int[]{1, 2}) {
            BreathCompositionPanelModule owner = fresh();
            // Untrusted component-only launch has no cold request. Resume/status remain inert.
            owner.onResume(); PolarSensorRuntime.instance.experimenterStatusProjection();
            check(!polar().automaticConnectionRequested && polar().scans == 0, "resume/status invented startup authority");
            ExperimentControlMode.Snapshot choose = owner.controlMode.readSnapshot();
            owner.choose(choose, mode);
            check(polar().automaticConnectionRequested && polar().scans == 1 && polar().autoConnectionAttempts == 1,
                "accepted native " + (mode == 1 ? "Direct" : "Remote") + " choice did not request startup scan");
            owner.choose(choose, mode); // stale callback
            owner.choose(mode); // native no-op selection
            owner.choose(mode == 1 ? 2 : 1); // actual accepted switch
            owner.onResume(); owner.onResume();
            check(polar().scans == 1 && PolarBleRuntimeSupport.prompts == 0, "choice/resume duplicated acquisition or permission UI");
        }
        BreathCompositionPanelModule owner = fresh(); owner.nativeOwner.reject = true; owner.choose(2);
        check(polar().scans == 0 && !polar().automaticConnectionRequested, "rejected native CAS started acquisition");
        owner = fresh(); owner.nativeOwner.value = 9;
        final BreathCompositionPanelModule disabled = owner; invalid(() -> disabled.choose(2));
        check(polar().scans == 0, "unselected composition started acquisition");
        owner = fresh(); final BreathCompositionPanelModule invalidOwner = owner;
        invalid(() -> invalidOwner.choose(0)); invalid(() -> invalidOwner.choose(3));
        check(polar().scans == 0, "invalid choice started acquisition");
        for (int source = 0; source < 6; source++) {
            owner = fresh();
            if (source == 0) owner.nativeHost = false;
            if (source == 1) owner = new BreathCompositionPanelModule(new Activity());
            if (source == 2) owner.retired = true;
            if (source == 3) owner.activity.finishing = true;
            if (source == 4) owner.activity.destroyed = true;
            if (source == 5) new BreathCompositionPanelModule(new NativeProductPanelActivity());
            owner.choose(2);
            check(polar().scans == 0 && !polar().automaticConnectionRequested, "unrelated/retired host started acquisition: " + source);
        }
        owner = fresh(); PolarSensorRuntime.instance.closed = true; owner.choose(1); owner.onResume();
        check(polar().scans == 0 && !polar().automaticConnectionRequested, "closed runtime restarted");
        owner = fresh(); polar().closing = true; owner.choose(2);
        check(polar().scans == 0 && !polar().automaticConnectionRequested, "closing acquisition restarted");
        owner = fresh(); PolarBleRuntimeSupport.ready = false; owner.choose(1);
        check(polar().automaticConnectionRequested && polar().scans == 0 && PolarBleRuntimeSupport.prompts == 1,
            "accepted native choice bypassed or failed existing runtime permission flow");
        owner.choose(2); owner.choose(1); owner.onResume();
        check(PolarBleRuntimeSupport.prompts == 1, "mode switch repeated permission prompt");
        BreathCompositionPanelModule replacement = new BreathCompositionPanelModule(new NativeProductPanelActivity());
        replacement.nativeOwner.value = owner.nativeOwner.value; replacement.choose(2);
        check(PolarBleRuntimeSupport.prompts == 1 && polar().scans == 0,
            "panel recreation repeated retained startup permission flow");
        PolarBleRuntimeSupport.ready = true; polar().handler.drain(); owner.onResume();
        check(polar().scans == 1, "permission-ready retained startup failed to scan exactly once");
        owner = fresh(); PolarBleRuntimeSupport.ready = false;
        PolarSensorRuntime.instance.ensureAutoConnection(); PolarSensorRuntime.instance.requestStartupPermissions(owner.activity);
        owner.choose(2); owner.choose(1);
        check(PolarBleRuntimeSupport.prompts == 1, "mode choice repeated already initiated cold-start permission flow");
        for (boolean inFlight : new boolean[]{false, true}) {
            owner = fresh(); polar().manualConnectionControl = true; polar().scanning = inFlight;
            owner.choose(1); owner.choose(2);
            check(polar().manualConnectionControl && !polar().automaticConnectionRequested && polar().scans == 0,
                "mode choice replaced manual sensor control");
        }
        owner = fresh(); polar().manualConnectionControl = true;
        new BreathCompositionPanelModule(new NativeProductPanelActivity()).choose(2);
        check(polar().manualConnectionControl && polar().scans == 0 && !polar().automaticConnectionRequested,
            "panel recreation replaced retained manual sensor control");
        owner = fresh(); polar().recoveryIntent.connected("fixture-sensor", "acc");
        polar().automaticConnectionRequested = true; polar().connected = true; owner.choose(2);
        check(polar().recoveryIntent.connectionWanted() && polar().recoveryIntent.permitsCandidate("fixture-sensor")
            && !polar().recoveryIntent.permitsCandidate("other") && polar().scans == 0,
            "mode choice replaced connected recovery identity");
        owner = fresh(); polar().deliberateDisconnect(); owner.choose(2); owner.choose(1); owner.onResume(); polar().handler.drain();
        check(!polar().automaticConnectionRequested && !polar().recoveryIntent.automaticStartupAllowed() && polar().scans == 0,
            "deliberate Disconnect was rearmed by mode choice/resume");
        owner = fresh(); owner.choose(1);
        check(polar().scans == 1, "new application owner did not restore startup eligibility");
        System.out.println("PASS production native selection/resume -> sole Polar startup: Direct/Remote, idempotence, cold request, permissions, manual intent, closed/stale/rejected/unrelated denial, Disconnect suppression");
    }
}
