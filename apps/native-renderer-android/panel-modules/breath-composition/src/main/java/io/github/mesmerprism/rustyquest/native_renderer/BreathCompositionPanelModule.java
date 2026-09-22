package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Product-composable breath/particle/Polar/LSL control surface.
 *
 * <p>The generated {@code ControlPanelActivity} is deliberately only the Android entry
 * shell and JNI name anchor. This module owns the product pages, local presentation state,
 * low-rate command adapters, and native-effective readback projection. It is compiled only
 * when the resolved native-app lock selects the {@code breath-composition-controls} module.</p>
 */
public class BreathCompositionPanelModule extends Activity implements PanelModule,
        ExperimentSessionAndroidShell.UiSink {
    public static final String MODULE_ID = "breath-composition-controls";

    @Override
    public final String panelModuleId() {
        return MODULE_ID;
    }
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static final String LSL_PANEL_COMMAND_SCHEMA =
        "rusty.quest.native_renderer.lsl.panel_command.v1";
    public static final String ACTION_TOGGLE_PANEL =
        "io.github.mesmerprism.rustyquest.native_renderer.action.TOGGLE_PANEL";
    public static final String ACTION_OPEN_PANEL =
        "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_PANEL";
    public static final String ACTION_OPEN_EXPERIMENTER_PANEL =
        "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_EXPERIMENTER_PANEL";
    public static final String ACTION_OPEN_DEVELOPER_PANEL =
        "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_DEVELOPER_PANEL";
    public static final String EXTRA_PANEL_ROUTE = "native_renderer_panel_route";
    public static final String EXTRA_PANEL_ROUTE_GENERATION = "native_renderer_panel_route_generation";
    public static final String EXTRA_PANEL_ROUTE_PROVENANCE = "native_renderer_panel_route_provenance";
    public static final String EXTRA_PANEL_SESSION_GENERATION = "native_renderer_panel_session_generation";
    public static final String EXTRA_PANEL_OPERATION_ID = "native_renderer_panel_operation_id";
    public static final String EXTRA_LAUNCH_PROVENANCE = "native_renderer_launch_provenance";
    public static final String EXTRA_LAUNCH_EPOCH = "native_renderer_launch_epoch";
    public static final String PROVENANCE_EXPLICIT_USER_LAUNCH = "explicit-user-launch-v1";
    public static final String PROVENANCE_NATIVE_B_RESTART = "native-b-restart-v1";
    public static final String PROVENANCE_NATIVE_MENU_RECALL = "native-menu-recall-v1";
    public static final String PANEL_ROUTE_EXPERIMENTER = "experimenter";
    public static final String PANEL_ROUTE_DEVELOPER = "developer";
    public static final String ACTION_REQUEST_DISPLAY_COMPOSITE_CAPTURE =
        "io.github.mesmerprism.rustyquest.native_renderer.action.REQUEST_DISPLAY_COMPOSITE_CAPTURE";
    public static final String ACTION_POLAR_SENSOR_PANEL_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.POLAR_SENSOR_PANEL_COMMAND";
    public static final String ACTION_BREATH_COMPOSITION_PANEL_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.BREATH_COMPOSITION_PANEL_COMMAND";
    public static final String EXTRA_POLAR_SENSOR_PANEL_COMMAND = "polar_sensor_panel_command";
    public static final String EXTRA_POLAR_SENSOR_PANEL_COMMAND_TOKEN =
        "polar_sensor_panel_command_token";
    public static final String EXTRA_BREATH_COMPOSITION_OPERATION =
        "breath_composition_operation";
    public static final String EXTRA_BREATH_COMPOSITION_SOURCE = "breath_composition_source";
    public static final String EXTRA_BREATH_COMPOSITION_MAPPING = "breath_composition_mapping";
    public static final String EXTRA_BREATH_COMPOSITION_CONTROLLER_PROJECTION =
        "breath_composition_controller_projection";
    public static final String EXTRA_BREATH_COMPOSITION_POLAR_PROJECTION =
        "breath_composition_polar_projection";
    public static final String EXTRA_BREATH_COMPOSITION_INVERTED =
        "breath_composition_inverted";
    public static final String EXTRA_BREATH_COMPOSITION_RETURN_TO_IMMERSIVE =
        "breath_composition_return_to_immersive";
    public static final String EXTRA_BREATH_COMPOSITION_COMMAND_TOKEN =
        "breath_composition_command_token";
    private static final int REQUEST_DISPLAY_COMPOSITE_CAPTURE = 7401;
    private static final String PRIVATE_PARTICLE_DYNAMICS_STATUS_FILE =
        "private_particle_dynamics_status.json";
    private static final String BREATH_COMPOSITION_OPERATOR_STATUS_FILE =
        "breath_composition_operator_status.json";
    private static final String POLAR_SENSOR_OPERATOR_STATUS_FILE =
        "polar_sensor_operator_status.json";
    private static final String POLAR_SENSOR_STATUS_FILE = "polar_sensor_status.json";
    private static final String PRIVATE_PARTICLE_DYNAMICS_SCHEMA =
        "rusty.quest.native_renderer.private_particle_dynamics.v1";
    private static final String PRIVATE_PARTICLE_DYNAMICS_STATUS_SCHEMA =
        "rusty.quest.native_renderer.private_particle_dynamics_status.v1";
    private static final String BREATH_COMPOSITION_COMMAND_SCHEMA =
        "rusty.quest.breath_composition.command.v1";
    private static final String PROP_PRIVATE_PARTICLE_VISUAL_SCALE =
        "debug.rustyquest.native_renderer.private_particles.visual.scale";
    private static final String PROP_PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE =
        "debug.rustyquest.native_renderer.private_particles.world_anchor.scale_m";
    private static final String PROP_PRIVATE_PARTICLE_TRACER_DRAW_SLOTS =
        "debug.rustyquest.native_renderer.private_particles.tracer.draw_slots_per_oscillator";
    private static final String PROP_PRIVATE_PARTICLE_TRACER_LIFETIME =
        "debug.rustyquest.native_renderer.private_particles.tracer.lifetime_seconds";
    private static final String PROP_PRIVATE_PARTICLE_TRACER_COPIES =
        "debug.rustyquest.native_renderer.private_particles.tracer.copies_per_second";
    private static final String PROP_PRIVATE_PARTICLE_TRANSPARENCY_OPACITY =
        "debug.rustyquest.native_renderer.private_particles.transparency.opacity";
    private static final String PROP_PRIVATE_PARTICLE_TRANSPARENCY_OUTPUT_ALPHA_SCALE =
        "debug.rustyquest.native_renderer.private_particles.transparency.output_alpha_scale";
    private static final String PROP_PRIVATE_PARTICLE_TRANSPARENCY_DEPTH_SUPPRESSION =
        "debug.rustyquest.native_renderer.private_particles.transparency.depth_suppression_strength";
    private static final String PROP_PRIVATE_PARTICLE_TRANSPARENCY_RGB_ALPHA_COUPLING =
        "debug.rustyquest.native_renderer.private_particles.transparency.rgb_alpha_coupling";
    private static final String PROP_PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION =
        "debug.rustyquest.native_renderer.private_particles.color.facing_attenuation_strength";
    private static final String[] PROP_PRIVATE_PARTICLE_DRIVERS = new String[] {
        "debug.rustyquest.native_renderer.private_particles.driver0.value01",
        "debug.rustyquest.native_renderer.private_particles.driver1.value01",
        "debug.rustyquest.native_renderer.private_particles.driver2.value01",
        "debug.rustyquest.native_renderer.private_particles.driver3.value01",
        "debug.rustyquest.native_renderer.private_particles.driver4.value01",
        "debug.rustyquest.native_renderer.private_particles.driver5.value01",
        "debug.rustyquest.native_renderer.private_particles.driver6.value01",
        "debug.rustyquest.native_renderer.private_particles.driver7.value01"
    };
    private static final String PROP_DISPLAY_COMPOSITE_WIDTH =
        "debug.rustyquest.native_renderer.display_composite.width";
    private static final String PROP_DISPLAY_COMPOSITE_HEIGHT =
        "debug.rustyquest.native_renderer.display_composite.height";
    private static final String PROP_DISPLAY_COMPOSITE_MAX_IMAGES =
        "debug.rustyquest.native_renderer.display_composite.max_images";
    private static final String PROP_DISPLAY_COMPOSITE_FPS_CAP =
        "debug.rustyquest.native_renderer.display_composite.fps_cap";
    private static final String PROP_DISPLAY_COMPOSITE_MODE =
        "debug.rustyquest.native_renderer.display_composite.mode";
    private static final String PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED =
        "debug.rustyquest.native_renderer.display_composite.feedback.enabled";
    private static final int PANEL_BG = Color.rgb(17, 18, 22);
    private static final int PANEL_FG = Color.rgb(238, 240, 244);
    private static final int PANEL_MUTED = Color.rgb(170, 176, 186);
    private static final int PANEL_SURFACE = Color.rgb(35, 38, 45);
    private static final int PANEL_ACCENT = Color.rgb(255, 214, 68);
    private static final String[] PRIVATE_PARTICLE_DRIVER_LABELS = new String[] {
        "Driver 0 deformation",
        "Driver 1 blend",
        "Driver 2 particle size",
        "Driver 3 depth wave",
        "Driver 4 spin",
        "Driver 5 orbit radius",
        "Driver 6 orbit angle",
        "Driver 7 animation frame"
    };
    private static boolean nativeBridgeLoaded;
    private static String nativeBridgeLoadError;
    private static final ExperimentSessionPanelCoordinator EXPERIMENT_SESSION_PANEL =
        new ExperimentSessionPanelCoordinator();
    private static final ExperimentSessionAndroidShell EXPERIMENT_SESSION_SHELL =
        ExperimentSessionAndroidShell.installProcess(
            new ExperimentSessionAndroidShell.NativeBridge() {
                @Override public String initialize(String appPrivateFilesRoot) {
                    return ControlPanelActivity.initializeExperimentSessionRuntimeFromOwner(
                        appPrivateFilesRoot);
                }

                @Override public String apply(String commandJson) {
                    return ControlPanelActivity.applyExperimentSessionCommand(commandJson);
                }

                @Override public boolean requestAudioStop(
                        long sessionGeneration, String operationId) {
                    return ControlPanelActivity.requestConditionAudioStop(
                        sessionGeneration, operationId);
                }

                @Override public boolean requestAudioRestartStop(
                        long sessionGeneration, String operationId) {
                    return ControlPanelActivity.requestConditionAudioRestartStop(
                        sessionGeneration, operationId);
                }

                @Override public String audioShutdownStatus() {
                    return ControlPanelActivity.conditionAudioShutdownStatus();
                }
                @Override public boolean cleanupAfterAcknowledgement() {
                    return ControlPanelActivity.closeExperimentResourcesFromOwner();
                }
            },
            new ExperimentSessionJsonCodec(),
            new ExperimentSessionAndroidShell.Clock() {
                @Override public long elapsedRealtimeMillis() {
                    return SystemClock.elapsedRealtime();
                }

                @Override public long elapsedRealtimeNanos() {
                    return SystemClock.elapsedRealtimeNanos();
                }

                @Override public long utcNanos() {
                    return System.currentTimeMillis() * 1_000_000L;
                }
            },
            new ExperimentSessionAndroidShell.UiDispatcher() {
                private final Handler main = new Handler(Looper.getMainLooper());

                @Override public void post(Runnable runnable) {
                    main.post(runnable);
                }

                @Override public boolean isUiThread() {
                    return Looper.myLooper() == Looper.getMainLooper();
                }
            }
        );

    static {
        try {
            System.loadLibrary("rusty_quest_native_renderer");
            nativeBridgeLoaded = true;
            nativeBridgeLoadError = "";
        } catch (UnsatisfiedLinkError error) {
            nativeBridgeLoaded = false;
            nativeBridgeLoadError = error.getMessage();
        }
    }

    private TextView status;
    private Handler liveApplyHandler;
    private CheckBox liveAutoApply;
    private Runnable pendingPrivateParticleDynamicsApply;
    private final LinkedHashSet<String> pendingPrivateParticlePatchFields =
        new LinkedHashSet<String>();
    private String handledDisplayCompositeIntentToken = "";
    private String handledPolarSensorPanelCommandToken = "";
    private String handledBreathCompositionCommandToken = "";
    private boolean displayCompositeRequestInFlight;
    private SliderControl privateParticleVisualScale;
    private Spinner privateParticleSizeMode;
    private SliderControl privateParticleSizeWorldMeters;
    private SliderControl privateParticleSizeSpherePercent;
    private SliderControl privateParticleSizeOscillationPercent;
    private boolean privateParticleSizeOverrideEnabled;
    private int privateParticleSizeModeSelection = -1;
    private SliderControl privateParticleWorldAnchorScale;
    private SliderControl[] privateParticleDrivers = new SliderControl[8];
    private SliderControl privateParticleTracerDrawSlots;
    private SliderControl privateParticleTracerLifetime;
    private SliderControl privateParticleTracerCopies;
    private Spinner privateParticleMaterialPreset;
    private CheckBox privateParticlePolarRrOrbitBoost;
    private TextView privateParticleEffectiveReadback;
    // This is deliberately separate from the legacy liveAutoApply control.  The
    // Breath-composition panel always sends one closed, debounced JSON candidate for
    // an intentional particle edit; it must not inherit another panel's toggle.
    private boolean privateParticlePanelLiveApply;
    private boolean privateParticleControlsHydrating;
    private long breathCompositionPanelNavigationEpoch;
    private long privateParticleControlEpoch = -1L;
    private int privateParticleMaterialSelection = -1;
    private long privateParticlePendingRevision;
    private long privateParticlePendingReadbackDeadlineMs;
    private Runnable pendingPrivateParticleEffectReadback;
    private String breathCompositionPanelTopic = "home";
    private String panelRoute = PANEL_ROUTE_EXPERIMENTER;
    private Runnable experimenterProjectionRefresh;
    private TextView experimenterBluetoothReadback;
    private TextView experimenterPolarReadback;
    private TextView experimenterCountReadback;
    private TextView experimenterStatusReadback;
    private TextView experimenterKioskReadback;
    private TextView experimenterKioskDetail;
    private Button experimenterPolarFallback;
    private Button experimenterStartOne;
    private Button experimenterStartTwo;
    private final ExperimentSessionPanelViewPolicy.Navigation experimenterNavigation =
        new ExperimentSessionPanelViewPolicy.Navigation();
    private ScrollView panelScrollView;
    private String renderedPanelRoute;
    private String renderedPanelTopic;
    private ExperimentSessionPanelViewPolicy.Page renderedExperimenterPage;
    private int polarPageScrollY;
    private final java.util.Map<String, Integer> developerPageScroll = new java.util.HashMap<>();
    private TextView experimenterStageReadback;
    private TextView experimenterInstructionReadback;
    private TextView experimenterStorageReadback;
    private TextView experimenterStorageDetail;
    private TextView experimenterFooterReadback;
    private TextView experimenterAudioOneReadback;
    private TextView experimenterAudioTwoReadback;
    private TextView experimenterGuidanceOneReadback;
    private TextView experimenterGuidanceTwoReadback;
    private SliderControl experimenterGuidanceBias;
    private TextView developerGuidanceOneReadback;
    private TextView developerGuidanceTwoReadback;
    private SliderControl developerGuidanceBias;
    private int experimenterGuidanceBiasPercent = -1;
    private Button experimenterResume;
    private Button experimenterNext;
    private Button experimenterSaveNext;
    private Button experimenterDeveloper;
    private PolarSensorPanel polarSensorPanel;
    private long breathCompositionGeneration;
    private TextView breathOverviewReadback;
    private TextView breathCalibrationReadback;
    private TextView breathOutputReadback;
    private TextView breathDiagnosticsReadback;
    private ProgressBar breathCalibrationProgress;
    private Runnable breathCompositionRefresh;
    private long experimentShellLaunchEpoch;
    private boolean experimentShellDestroyed;
    private boolean terminalFinishDispatched;
    private boolean startupPermissionFlowEligible;
    private final ExperimenterPanelShortcutPolicy experimenterPanelShortcut =
        new ExperimenterPanelShortcutPolicy();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liveApplyHandler = new Handler(Looper.getMainLooper());
        Intent initialIntent = getIntent();
        long requestedLaunchEpoch = initialIntent == null
            ? 0L : initialIntent.getLongExtra(EXTRA_LAUNCH_EPOCH, 0L);
        boolean launchAuthorityConsumed = ControlPanelActivity.consumeExplicitColdUserLaunch(
            this,
            savedInstanceState != null,
            initialIntent
        );
        boolean panelColdLaunchAdmitted = launchAuthorityConsumed
            && EXPERIMENT_SESSION_SHELL.canBeginExplicitLaunchEpoch(requestedLaunchEpoch)
            && EXPERIMENT_SESSION_PANEL.admitTrustedColdLaunch(requestedLaunchEpoch);
        boolean trustedColdMain = panelColdLaunchAdmitted
            && ControlPanelActivity.admitExplicitColdExperimentShell(
                this,
                requestedLaunchEpoch,
                PANEL_ROUTE_EXPERIMENTER
            );
        if (trustedColdMain) {
            EXPERIMENT_SESSION_SHELL.beginExplicitLaunchEpoch(requestedLaunchEpoch);
        }
        experimentShellLaunchEpoch = EXPERIMENT_SESSION_SHELL.currentLaunchEpochForUi();
        boolean freshGestureRoute = savedInstanceState == null
            && initialIntent != null
            && (ACTION_OPEN_EXPERIMENTER_PANEL.equals(initialIntent.getAction())
                || ACTION_OPEN_DEVELOPER_PANEL.equals(initialIntent.getAction()));
        EXPERIMENT_SESSION_PANEL.onLaunch(
            trustedColdMain
                ? ExperimentSessionPanelCoordinator.LaunchKind.EXPLICIT_COLD_ROOT
                : (savedInstanceState == null
                    ? ExperimentSessionPanelCoordinator.LaunchKind.INTERNAL_MAIN
                    : ExperimentSessionPanelCoordinator.LaunchKind.RECREATION_MAIN),
            freshGestureRoute || Intent.ACTION_MAIN.equals(initialIntent == null ? null : initialIntent.getAction())
                ? null : requestedPanelRoute(initialIntent),
            freshGestureRoute || Intent.ACTION_MAIN.equals(initialIntent == null ? null : initialIntent.getAction())
                ? 0L : routeGeneration(initialIntent)
        );
        applyPanelRouteIntent(initialIntent, freshGestureRoute);
        if (savedInstanceState != null) {
            experimenterNavigation.restore(savedInstanceState.getString("experimenter-page"),
                savedInstanceState.getIntArray("experimenter-page-scroll"));
            breathCompositionPanelTopic = savedInstanceState.getString("panel-topic", "home");
            polarPageScrollY = savedInstanceState.getInt("polar-page-scroll", 0);
            experimenterGuidanceBiasPercent = savedInstanceState.getInt(
                "experimenter-guidance-bias-percent", -1);
            Bundle savedDeveloperScroll = savedInstanceState.getBundle("developer-page-scroll");
            if (savedDeveloperScroll != null) {
                for (String key : savedDeveloperScroll.keySet()) {
                    developerPageScroll.put(key, Math.max(0, savedDeveloperScroll.getInt(key)));
                }
            }
        } else if (EXPERIMENT_SESSION_PANEL.snapshot().hasActiveSession()) {
            // The panel Activity may have finished when VR resumed. Recall an active
            // run at its session page without turning ordinary readbacks into navigation.
            experimenterNavigation.select(ExperimentSessionPanelViewPolicy.Page.SESSION);
        }
        if (experimenterGuidanceBiasPercent < 0) {
            experimenterGuidanceBiasPercent =
                EXPERIMENT_SESSION_PANEL.pendingBreathGuidanceBiasPercent();
        }
        startupPermissionFlowEligible = trustedColdMain
            && PANEL_ROUTE_EXPERIMENTER.equals(panelRoute);
        PolarSensorRuntime polarRuntime =
            PolarSensorRuntime.forApplication(getApplicationContext());
        if (startupPermissionFlowEligible) polarRuntime.ensureAutoConnection();
        setContentView(buildContentView());
        if (startupPermissionFlowEligible) polarRuntime.requestStartupPermissions(this);
        restorePanelScrollAfterLayout();
        EXPERIMENT_SESSION_SHELL.initialize(getFilesDir().getAbsolutePath(), this);
        scheduleExperimenterProjectionRefresh();
        updateReadyStatusForPanelMode();
        handleDisplayCompositeIntent(getIntent());
        handlePolarSensorPanelCommandIntent(getIntent());
        handleBreathCompositionCommandIntent(getIntent());
        handleTerminalSaveAndExitIntent(initialIntent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        rememberPanelScroll();
        outState.putString("experimenter-page", experimenterNavigation.page().name());
        outState.putIntArray("experimenter-page-scroll", experimenterNavigation.savedScroll());
        outState.putString("panel-topic", breathCompositionPanelTopic);
        outState.putInt("polar-page-scroll", polarPageScrollY);
        outState.putInt("experimenter-guidance-bias-percent",
            Math.max(-1, Math.min(100, experimenterGuidanceBiasPercent)));
        Bundle savedDeveloperScroll = new Bundle();
        for (java.util.Map.Entry<String, Integer> entry : developerPageScroll.entrySet()) {
            savedDeveloperScroll.putInt(entry.getKey(), entry.getValue());
        }
        outState.putBundle("developer-page-scroll", savedDeveloperScroll);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleTerminalSaveAndExitIntent(intent)) {
            return;
        }
        if (intent != null && ACTION_TOGGLE_PANEL.equals(intent.getAction())) {
            closePanelAndReturnToImmersive();
        } else if (intent != null && (ACTION_OPEN_PANEL.equals(intent.getAction())
                || ACTION_OPEN_EXPERIMENTER_PANEL.equals(intent.getAction())
                || ACTION_OPEN_DEVELOPER_PANEL.equals(intent.getAction()))) {
            applyPanelRouteIntent(intent, true);
            rebuildContentViewForCurrentMode();
            handleDisplayCompositeIntent(intent);
            handlePolarSensorPanelCommandIntent(intent);
            handleBreathCompositionCommandIntent(intent);
        } else {
            handleDisplayCompositeIntent(intent);
            handlePolarSensorPanelCommandIntent(intent);
            handleBreathCompositionCommandIntent(intent);
        }
    }

    private void rebuildContentViewForCurrentMode() {
        // This product's integrated Polar owner survives page rebuilds. Runtime profile input
        // cannot replace the baked breath-composition module with a legacy product mode.
        replaceBreathCompositionPanelContent(breathCompositionPanelTopic);
        updateReadyStatusForPanelMode();
    }

    private void updateReadyStatusForPanelMode() {
        if (PANEL_ROUTE_EXPERIMENTER.equals(panelRoute)) {
            updateStatus("Experimenter menu ready; native-effective session receipts remain authoritative.");
        } else {
            updateStatus("Direct breath mapping panel ready; native-effective readback required.");
        }
    }

    private boolean applyPanelRouteIntent(Intent intent, boolean interactiveRoute) {
        if (intent == null) {
            return false;
        }
        String requested = intent.getStringExtra(EXTRA_PANEL_ROUTE);
        long generation = routeGeneration(intent);
        boolean admitted = true;
        if (ACTION_OPEN_EXPERIMENTER_PANEL.equals(intent.getAction())) {
            requested = PANEL_ROUTE_EXPERIMENTER;
            if (interactiveRoute) {
                String provenance = intent.getStringExtra(EXTRA_PANEL_ROUTE_PROVENANCE);
                if (PROVENANCE_NATIVE_B_RESTART.equals(provenance)) {
                    admitted = EXPERIMENT_SESSION_PANEL.awaitNativeRestartRoute(
                        intent.getLongExtra(EXTRA_PANEL_ROUTE_GENERATION, 0L),
                        intent.getLongExtra(EXTRA_PANEL_SESSION_GENERATION, 0L),
                        intent.getStringExtra(EXTRA_PANEL_OPERATION_ID)
                    );
                    if (admitted) {
                        ControlPanelActivity.requestConditionAudioRestartStop(
                            intent.getLongExtra(EXTRA_PANEL_SESSION_GENERATION, 0L),
                            intent.getStringExtra(EXTRA_PANEL_OPERATION_ID) + "-audio-stop"
                        );
                        EXPERIMENT_SESSION_SHELL.requestStatus(this);
                    }
                } else if (PROVENANCE_NATIVE_MENU_RECALL.equals(provenance)) {
                    admitted = EXPERIMENT_SESSION_PANEL.admitIdleExperimenterRecall(
                        intent.getLongExtra(EXTRA_PANEL_ROUTE_GENERATION, 0L)
                    );
                    if (admitted) {
                        EXPERIMENT_SESSION_SHELL.requestStatus(this);
                    }
                } else {
                    admitted = false;
                }
            }
        } else if (ACTION_OPEN_DEVELOPER_PANEL.equals(intent.getAction())) {
            requested = PANEL_ROUTE_DEVELOPER;
            if (interactiveRoute) {
                EXPERIMENT_SESSION_PANEL.openDeveloper(generation);
            }
        }
        if (admitted && (PANEL_ROUTE_EXPERIMENTER.equals(requested)
                || PANEL_ROUTE_DEVELOPER.equals(requested))) {
            panelRoute = PANEL_ROUTE_EXPERIMENTER.equals(requested)
                ? PANEL_ROUTE_EXPERIMENTER : PANEL_ROUTE_DEVELOPER;
            breathCompositionPanelTopic = "home";
            ControlPanelActivity.beginPanelTransition(this, panelRoute, generation);
        }
        synchronizePanelRouteFromCoordinator();
        return admitted;
    }

    private ExperimentSessionPanelState.Route requestedPanelRoute(Intent intent) {
        if (intent == null) {
            return null;
        }
        String requested = intent.getStringExtra(EXTRA_PANEL_ROUTE);
        if (ACTION_OPEN_EXPERIMENTER_PANEL.equals(intent.getAction())) {
            requested = PANEL_ROUTE_EXPERIMENTER;
        } else if (ACTION_OPEN_DEVELOPER_PANEL.equals(intent.getAction())) {
            requested = PANEL_ROUTE_DEVELOPER;
        }
        if (PANEL_ROUTE_EXPERIMENTER.equals(requested)) {
            return ExperimentSessionPanelState.Route.EXPERIMENTER;
        }
        if (PANEL_ROUTE_DEVELOPER.equals(requested)) {
            return ExperimentSessionPanelState.Route.DEVELOPER;
        }
        return null;
    }

    private long routeGeneration(Intent intent) {
        long supplied = intent == null ? 0L : intent.getLongExtra(EXTRA_PANEL_ROUTE_GENERATION, 0L);
        if (supplied > 0L) {
            return supplied;
        }
        return EXPERIMENT_SESSION_PANEL.allocateRouteEvent();
    }

    private void synchronizePanelRouteFromCoordinator() {
        panelRoute = EXPERIMENT_SESSION_PANEL.snapshot().route
            == ExperimentSessionPanelState.Route.DEVELOPER
            ? PANEL_ROUTE_DEVELOPER : PANEL_ROUTE_EXPERIMENTER;
    }

    @Override
    protected void onResume() {
        super.onResume();
        PolarSensorRuntime.forApplication(getApplicationContext()).onHostResume();
        handleDisplayCompositeIntent(getIntent());
        if ("breath-mapping".equals(readControlPanelMode())) {
            breathOperatorMarker(
                "status=panel-foreground panelVisibility=foreground "
                    + "controllerPoseOwner=openxr-session controllerPanelForegroundProof=pending-device "
                    + "polarAccCompositionAdvance=jni-same-process"
            );
            scheduleBreathCompositionRefresh();
        }
        scheduleExperimenterProjectionRefresh();
    }

    @Override
    protected void onPause() {
        experimenterPanelShortcut.cancel();
        cancelExperimenterProjectionRefresh();
        cancelBreathCompositionRefresh();
        if ("breath-mapping".equals(readControlPanelMode())) {
            breathOperatorMarker(
                "status=panel-background panelVisibility=background "
                    + "calibrationStateOwner=native-process-shared polarOwnerRetained=true"
            );
        }
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean rightSecondary = event != null
            && (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_2);
        if (!rightSecondary) {
            return super.dispatchKeyEvent(event);
        }
        boolean triggered = experimenterPanelShortcut.onKeySecondary(
            event.getAction() == KeyEvent.ACTION_DOWN,
            event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0,
            SystemClock.uptimeMillis()
        );
        if (triggered) {
            experimenterPanelShortcutMarker("status=close-requested source=android-key-event");
            closePanelAndReturnToImmersive();
        }
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event == null) {
            return false;
        }
        boolean secondaryDown = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
        boolean secondaryEvent = secondaryDown
            || event.getActionButton() == MotionEvent.BUTTON_SECONDARY
            || experimenterPanelShortcut.isMotionDown();
        if (!secondaryEvent) {
            return super.dispatchGenericMotionEvent(event);
        }
        boolean triggered = experimenterPanelShortcut.onMotionSecondary(
            secondaryDown,
            SystemClock.uptimeMillis()
        );
        if (triggered) {
            experimenterPanelShortcutMarker("status=close-requested source=android-motion-event");
            closePanelAndReturnToImmersive();
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        experimentShellDestroyed = true;
        EXPERIMENT_SESSION_SHELL.detach(this);
        cancelExperimenterProjectionRefresh();
        cancelPendingPrivateParticleDynamicsApply();
        cancelPendingPrivateParticleEffectReadback();
        invalidatePrivateParticleControls();
        if (polarSensorPanel != null) {
            PolarSensorRuntime.forApplication(getApplicationContext()).detachPanel(this);
            polarSensorPanel = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DISPLAY_COMPOSITE_CAPTURE) {
            return;
        }
        displayCompositeRequestInFlight = false;
        if (resultCode != RESULT_OK || data == null) {
            setStatusText("Display composite capture was not approved.");
            return;
        }
        Intent serviceIntent = new Intent(this, DisplayCompositeProjectionService.class);
        serviceIntent.putExtra(DisplayCompositeProjectionService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(DisplayCompositeProjectionService.EXTRA_RESULT_DATA, data);
        serviceIntent.putExtra(
            DisplayCompositeProjectionService.EXTRA_WIDTH,
            readIntProperty(PROP_DISPLAY_COMPOSITE_WIDTH, 1280, 320, 4096)
        );
        serviceIntent.putExtra(
            DisplayCompositeProjectionService.EXTRA_HEIGHT,
            readIntProperty(PROP_DISPLAY_COMPOSITE_HEIGHT, 720, 240, 4096)
        );
        serviceIntent.putExtra(
            DisplayCompositeProjectionService.EXTRA_MAX_IMAGES,
            readIntProperty(PROP_DISPLAY_COMPOSITE_MAX_IMAGES, 3, 2, 6)
        );
        serviceIntent.putExtra(
            DisplayCompositeProjectionService.EXTRA_FPS_CAP,
            readIntProperty(PROP_DISPLAY_COMPOSITE_FPS_CAP, 30, 1, 90)
        );
        serviceIntent.putExtra(
            DisplayCompositeProjectionService.EXTRA_MODE,
            readSystemProperty(PROP_DISPLAY_COMPOSITE_MODE)
        );
        serviceIntent.putExtra(
            DisplayCompositeProjectionService.EXTRA_FEEDBACK_ENABLED,
            readBooleanProperty(PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED, false)
        );
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        setStatusText("Display composite capture token accepted; hardware-buffer service starting.");
        launchImmersiveRenderer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PolarSensorRuntime.forApplication(getApplicationContext()).onRequestPermissionsResult(
            this,
            requestCode,
            permissions,
            grantResults
        );
    }

    private View buildContentView() {
        // The packaged module is authoritative. Runtime mode strings are legacy renderer hints,
        // not a factory for alternate Java product panels.
        return buildBreathCompositionControlPanelView();
    }

    private View buildBreathCompositionControlPanelView() {
        clearExperimenterProjectionViews();
        renderedPanelRoute = panelRoute;
        renderedPanelTopic = breathCompositionPanelTopic;
        renderedExperimenterPage = experimenterNavigation.page();
        if (PANEL_ROUTE_EXPERIMENTER.equals(panelRoute)) {
            return buildExperimenterWorkflowView();
        }
        ScrollView scroll = new ScrollView(this);
        panelScrollView = scroll;
        scroll.setBackgroundColor(PANEL_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        appendBreathCompositionPanelHeader(root);
        if ("particles".equals(breathCompositionPanelTopic)) {
            appendUnifiedParticleControls(root);
        } else if ("breath".equals(breathCompositionPanelTopic)) {
            appendBreathCompositionBreathControls(root);
        } else if ("polar".equals(breathCompositionPanelTopic)) {
            appendBreathCompositionPolarControls(root);
        } else if ("lsl".equals(breathCompositionPanelTopic)) {
            appendBreathCompositionLslControls(root);
        } else if ("status".equals(breathCompositionPanelTopic)) {
            appendBreathCompositionStatus(root);
        } else {
            appendBreathCompositionPanelHome(root);
        }
        return scroll;
    }

    private void appendBreathCompositionPanelHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Breath composition · " + breathCompositionTopicTitle(), 22, PANEL_FG);
        header.addView(
            title,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        );
        Button resume = button("Resume VR");
        resume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        header.addView(resume);
        Button experimenter = button("Experimenter");
        experimenter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                EXPERIMENT_SESSION_PANEL.returnFromDeveloper();
                synchronizePanelRouteFromCoordinator();
                replaceBreathCompositionPanelContent("home");
            }
        });
        header.addView(experimenter);
        root.addView(header);
        root.addView(text(breathCompositionTopicSubtitle(), 13, PANEL_MUTED));

        HorizontalScrollView topicScroll = new HorizontalScrollView(this);
        topicScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout topics = new LinearLayout(this);
        topics.setOrientation(LinearLayout.HORIZONTAL);
        String[] ids = new String[] {"home", "particles", "breath", "polar", "lsl", "status"};
        String[] titles = new String[] {"Home", "Particles", "Breath", "Polar", "LSL", "Status"};
        for (int i = 0; i < ids.length; i++) {
            final String topic = ids[i];
            Button choice = button(titles[i]);
            choice.setEnabled(!topic.equals(breathCompositionPanelTopic));
            choice.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectBreathCompositionPanelTopic(topic);
                }
            });
            topics.addView(choice);
        }
        topicScroll.addView(topics);
        root.addView(topicScroll);
    }

    private View buildExperimenterWorkflowView() {
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(PANEL_BG);
        frame.setPadding(dp(18), dp(12), dp(18), dp(12));
        // Consume initial focus on the stable frame. Recreated child buttons must not ask
        // ScrollView to reveal themselves after an asynchronous status update.
        frame.setFocusableInTouchMode(true);
        frame.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        frame.requestFocus();
        appendExperimenterPanelHeader(frame);
        ScrollView scroll = new ScrollView(this);
        panelScrollView = scroll;
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        frame.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        if ("polar".equals(breathCompositionPanelTopic)) {
            appendBreathCompositionPolarControls(body);
        } else {
            appendExperimenterPanelHome(body);
        }
        appendExperimenterWorkflowFooter(frame);
        return frame;
    }

    private void appendExperimenterPanelHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        String titleText = "polar".equals(breathCompositionPanelTopic)
            ? "Polar connection" : experimenterNavigation.page().title;
        header.addView(
            text(titleText, 22, PANEL_FG),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        );
        ExperimentSessionPanelState state = EXPERIMENT_SESSION_PANEL.snapshot();
        ExperimentSessionPanelViewPolicy.ViewState view =
            ExperimentSessionPanelViewPolicy.project(state);
        if (state.hasActiveSession()) {
            experimenterResume = button("Return to VR");
            experimenterResume.setMinHeight(dp(52));
            experimenterResume.setEnabled(
                ExperimentSessionPanelViewPolicy.canReturnToImmersive(state) && !view.saving
            );
            experimenterResume.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) { closePanelAndReturnToImmersive(); }
            });
            header.addView(experimenterResume);
        }
        root.addView(header);
        root.addView(text(
            "1 Prepare  →  2 Controls  →  3 Choose condition  →  4 Session",
            13,
            PANEL_MUTED
        ));
    }

    private void appendExperimenterPanelHome(LinearLayout root) {
        refreshExperimenterPolarProjection();
        final ExperimentSessionPanelState state = EXPERIMENT_SESSION_PANEL.snapshot();
        ExperimentSessionPanelViewPolicy.ViewState view =
            ExperimentSessionPanelViewPolicy.project(state);

        ExperimentSessionPanelViewPolicy.Page page = experimenterNavigation.page();
        if (page == ExperimentSessionPanelViewPolicy.Page.PREPARE) {
            root.addView(text("Check the headset and sensor, then review the controls with the participant.", 16, PANEL_FG));
            appendExperimenterConnection(root, state, view);
            appendExperimenterStorage(root, state);
            appendExperimenterKiosk(root);
        } else if (page == ExperimentSessionPanelViewPolicy.Page.CONTROLS) {
            appendExperimenterControls(root);
        } else if (page == ExperimentSessionPanelViewPolicy.Page.CONDITION) {
            appendExperimenterCondition(root, state, view);
        } else {
            appendExperimenterSession(root, state, view);
        }
    }

    private void appendExperimenterConnection(LinearLayout root,
            ExperimentSessionPanelState state, ExperimentSessionPanelViewPolicy.ViewState view) {
        LinearLayout connection = panelCard("Sensor connection");
        experimenterBluetoothReadback = text(view.bluetoothLine, 14, PANEL_FG);
        experimenterPolarReadback = text(view.polarLine, 14, PANEL_FG);
        styleExperimenterIndicator(experimenterBluetoothReadback, ExperimentSessionPanelViewPolicy.bluetoothTone(state));
        styleExperimenterIndicator(experimenterPolarReadback, ExperimentSessionPanelViewPolicy.polarTone(state));
        connection.addView(experimenterBluetoothReadback);
        connection.addView(experimenterPolarReadback);
        experimenterPolarFallback = button("Open Polar connection");
        // Keep this row stable as Bluetooth/Polar callbacks arrive.
        experimenterPolarFallback.setVisibility(View.VISIBLE);
        experimenterPolarFallback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                replaceBreathCompositionPanelContent("polar");
            }
        });
        connection.addView(experimenterPolarFallback);
        root.addView(connection);
    }

    private void appendExperimenterStorage(LinearLayout root,
            ExperimentSessionPanelState state) {
        ExperimentSessionPanelViewPolicy.ReadinessCard presentation =
            ExperimentSessionPanelViewPolicy.storageCard(state);
        LinearLayout storage = panelCard("Recording");
        experimenterStorageReadback = experimenterIndicator(
            storage, presentation.label, presentation.tone);
        experimenterStorageDetail = text(presentation.detail, 12, PANEL_MUTED);
        experimenterStorageDetail.setVisibility(
            presentation.detail.isEmpty() ? View.GONE : View.VISIBLE);
        storage.addView(experimenterStorageDetail);
        root.addView(storage);
    }

    private void appendExperimenterKiosk(LinearLayout root) {
        ExperimentSessionPanelViewPolicy.ReadinessCard presentation =
            ExperimentSessionPanelViewPolicy.kioskCard(
                ControlPanelActivity.softKioskUiState(this));
        LinearLayout kiosk = panelCard("Kiosk mode");
        experimenterKioskReadback = experimenterIndicator(
            kiosk, presentation.label, presentation.tone);
        experimenterKioskDetail = text(presentation.detail, 12, PANEL_MUTED);
        experimenterKioskDetail.setVisibility(
            presentation.detail.isEmpty() ? View.GONE : View.VISIBLE);
        kiosk.addView(experimenterKioskDetail);
        root.addView(kiosk);
    }

    private void appendExperimenterControls(LinearLayout root) {
        root.addView(text("Review these controls before arming a condition. Release the buttons between gestures.", 16, PANEL_FG));
        appendExperimenterControl(root, "Start / resume", "Hold Right Grip + A for at least 0.75 seconds, then release after the confirmation pulse. Starts the official run when armed; resumes it when paused.");
        appendExperimenterControl(root, "Pause", "Hold Right Grip + B for at least 0.75 seconds, then release after the confirmation pulse.");
        appendExperimenterControl(root, "Recenter", "Press A without holding the grip to recenter.");
        appendExperimenterControl(root, "Open or close this menu", "Press B three times without holding the grip. In VR it opens the experimenter menu; in the panel it returns to VR. This does not start, pause, or end the run.");
        appendExperimenterControl(root, "Developer settings", "Press the trigger three times to open the developer panel.");
        appendExperimenterControl(root, "Save and exit the app", "Use Save and exit in this panel. The app can also request exit after three recovered departures within 5 seconds; this depends on background return and is not guaranteed physical Home-button interception.");
    }

    private void appendExperimenterControl(LinearLayout root, String title, String description) {
        LinearLayout row = panelCard(title);
        row.addView(text(description, 15, PANEL_FG));
        root.addView(row);
    }

    private void appendExperimenterCondition(LinearLayout root,
            ExperimentSessionPanelState state, ExperimentSessionPanelViewPolicy.ViewState view) {
        LinearLayout start = panelCard("Arm one condition");
        start.addView(text(
            "Arming prepares the selected condition while audio remains silent. After fitting and checking the headset, the experimenter starts the official run in VR with Right Grip + A. Both conditions use a 30 second completion threshold; natural audio end does not finish the run.",
            15,
            PANEL_MUTED
        ));
        experimenterAudioOneReadback = experimenterIndicator(start, "Condition 1 audio: checking…", ExperimentSessionPanelViewPolicy.Tone.WAITING);
        experimenterAudioTwoReadback = experimenterIndicator(start, "Condition 2 audio: checking…", ExperimentSessionPanelViewPolicy.Tone.WAITING);
        experimenterGuidanceOneReadback = experimenterIndicator(start,
            "Condition 1 breathing pattern: checking…",
            ExperimentSessionPanelViewPolicy.Tone.WAITING);
        experimenterGuidanceTwoReadback = experimenterIndicator(start,
            "Condition 2 breathing pattern: checking…",
            ExperimentSessionPanelViewPolicy.Tone.WAITING);
        String patternOneReadiness = ControlPanelActivity.conditionBreathGuidanceReadiness(
            ExperimentSessionPanelCoordinator.CONDITION_ONE);
        String patternTwoReadiness = ControlPanelActivity.conditionBreathGuidanceReadiness(
            ExperimentSessionPanelCoordinator.CONDITION_TWO);
        boolean patternOne = "pattern-ready".equals(patternOneReadiness);
        boolean patternTwo = "pattern-ready".equals(patternTwoReadiness);
        if (experimenterGuidanceBiasPercent < 0) {
            int firstDefault = ControlPanelActivity.conditionBreathGuidanceDefaultBias(
                ExperimentSessionPanelCoordinator.CONDITION_ONE);
            int secondDefault = ControlPanelActivity.conditionBreathGuidanceDefaultBias(
                ExperimentSessionPanelCoordinator.CONDITION_TWO);
            experimenterGuidanceBiasPercent = patternOne ? firstDefault
                : patternTwo ? secondDefault
                : "no-pattern".equals(patternOneReadiness)
                    && "no-pattern".equals(patternTwoReadiness) ? 0 : -1;
            if (experimenterGuidanceBiasPercent >= 0) {
                EXPERIMENT_SESSION_PANEL.updatePendingBreathGuidanceBiasPercent(
                    experimenterGuidanceBiasPercent);
            }
        }
        experimenterGuidanceBias = new SliderControl(
            "Breathing-pattern bias", 0.0, 100.0,
            Math.max(0, experimenterGuidanceBiasPercent), 100, "%", true,
            new Runnable() {
                @Override public void run() {
                    int requested = experimenterGuidanceBias.intValue();
                    if (EXPERIMENT_SESSION_PANEL.updatePendingBreathGuidanceBiasPercent(requested)) {
                        experimenterGuidanceBiasPercent = requested;
                    }
                }
            });
        experimenterGuidanceBias.setInteractive(patternOne || patternTwo);
        start.addView(experimenterGuidanceBias.view);
        start.addView(text(
            "0% is fully signal-driven feedback. Higher values increasingly favor the timestamped guided-breathing phase; 100% is guidance-dominant but still requires valid Polar tracking. The selected value is locked when the condition is armed.",
            13, PANEL_MUTED));
        experimenterStartOne = button("Arm Condition 1");
        experimenterStartTwo = button("Arm Condition 2");
        experimenterStartOne.setMinHeight(dp(52));
        experimenterStartTwo.setMinHeight(dp(52));
        experimenterStartOne.setEnabled(view.startEnabled);
        experimenterStartTwo.setEnabled(view.startEnabled);
        experimenterStartOne.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                startExperimentSession(ExperimentSessionPanelCoordinator.CONDITION_ONE);
            }
        });
        experimenterStartTwo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                startExperimentSession(ExperimentSessionPanelCoordinator.CONDITION_TWO);
            }
        });
        start.addView(experimenterStartOne);
        start.addView(experimenterStartTwo);
        root.addView(start);
        appendExperimenterConnection(root, state, view);
        refreshExperimenterProjectionViews();
    }

    private void appendExperimenterSession(LinearLayout root,
            ExperimentSessionPanelState state, ExperimentSessionPanelViewPolicy.ViewState view) {
        experimenterStageReadback = experimenterIndicator(root,
            ExperimentSessionPanelViewPolicy.stageTitle(state), ExperimentSessionPanelViewPolicy.stageTone(state));
        experimenterInstructionReadback = text(ExperimentSessionPanelViewPolicy.stageInstruction(state), 16, PANEL_FG);
        root.addView(experimenterInstructionReadback);
        root.addView(text("Start / resume: Right Grip + A · Pause: Right Grip + B · Hold at least 0.75 seconds, then release after the pulse.", 14, PANEL_MUTED));
        root.addView(text("Use Return to VR above, or press B three times again, without changing the session state.", 14, PANEL_MUTED));
        LinearLayout sessions = panelCard("Session status");
        experimenterCountReadback = text(view.countLine, 14, PANEL_FG);
        experimenterStatusReadback = text(view.statusLine, 14, PANEL_MUTED);
        sessions.addView(experimenterCountReadback);
        sessions.addView(experimenterStatusReadback);
        Button refresh = button("Refresh status");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) { requestExperimentSessionStatus(); }
        });
        sessions.addView(refresh);
        if (state.hasActiveSession()) {
            experimenterSaveNext = button("Save session & prepare next");
            experimenterSaveNext.setMinHeight(dp(52));
            experimenterSaveNext.setEnabled(ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(state));
            experimenterSaveNext.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    saveExperimentSessionAndPrepareNext();
                }
            });
            sessions.addView(experimenterSaveNext);
        }
        root.addView(sessions);
    }

    private void appendExperimenterWorkflowFooter(LinearLayout root) {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(10), 0, 0);
        final ExperimentSessionPanelViewPolicy.Page page = experimenterNavigation.page();
        Button back = button("Back");
        back.setEnabled("polar".equals(breathCompositionPanelTopic) || page != ExperimentSessionPanelViewPolicy.Page.PREPARE);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) {
                if ("polar".equals(breathCompositionPanelTopic)) replaceBreathCompositionPanelContent("home");
                else selectExperimenterPage(ExperimentSessionPanelViewPolicy.Page.values()[page.ordinal() - 1]);
            }
        });
        footer.addView(back);
        experimenterFooterReadback = text("", 13, PANEL_MUTED);
        footer.addView(experimenterFooterReadback, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!"polar".equals(breathCompositionPanelTopic)) {
            final ExperimentSessionPanelState sessionState = EXPERIMENT_SESSION_PANEL.snapshot();
            String nextText = page == ExperimentSessionPanelViewPolicy.Page.PREPARE ? "Next: Controls"
                : page == ExperimentSessionPanelViewPolicy.Page.CONTROLS ? "Next: Choose condition"
                : page == ExperimentSessionPanelViewPolicy.Page.CONDITION ? "Session status"
                : sessionState.hasActiveSession() ? "Save session & prepare next" : "Prepare next run";
            Button next = button(nextText);
            experimenterNext = next;
            next.setMinHeight(dp(48));
            next.setEnabled(page != ExperimentSessionPanelViewPolicy.Page.SESSION
                || !sessionState.hasActiveSession()
                || ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(sessionState));
            next.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    if (page == ExperimentSessionPanelViewPolicy.Page.SESSION) {
                        if (EXPERIMENT_SESSION_PANEL.snapshot().hasActiveSession()) {
                            saveExperimentSessionAndPrepareNext();
                        } else {
                            selectExperimenterPage(ExperimentSessionPanelViewPolicy.Page.PREPARE);
                        }
                    } else {
                        selectExperimenterPage(ExperimentSessionPanelViewPolicy.Page.values()[page.ordinal() + 1]);
                    }
                }
            });
            footer.addView(next);
        }
        root.addView(footer);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        experimenterDeveloper = button("Open developer settings");
        experimenterDeveloper.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                EXPERIMENT_SESSION_PANEL.openDeveloper(
                    EXPERIMENT_SESSION_PANEL.allocateRouteEvent()
                );
                synchronizePanelRouteFromCoordinator();
                replaceBreathCompositionPanelContent("home");
            }
        });
        tools.addView(experimenterDeveloper);
        Button saveExit = button("Save and exit");
        saveExit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) {
                NativeRendererSelfKioskApplication.requestSaveAndExit(BreathCompositionPanelModule.this);
            }
        });
        tools.addView(saveExit);
        root.addView(tools);
        refreshExperimenterProjectionViews();
    }

    private String breathCompositionTopicTitle() {
        if ("particles".equals(breathCompositionPanelTopic)) {
            return "Particles";
        }
        if ("breath".equals(breathCompositionPanelTopic)) {
            return "Breath";
        }
        if ("polar".equals(breathCompositionPanelTopic)) {
            return "Polar";
        }
        if ("lsl".equals(breathCompositionPanelTopic)) {
            return "LSL streaming";
        }
        if ("status".equals(breathCompositionPanelTopic)) {
            return "Effective state";
        }
        return "Control panel";
    }

    private String breathCompositionTopicSubtitle() {
        if ("particles".equals(breathCompositionPanelTopic)) {
            return "Visual shape, dynamics, trails, material, and RR orbit response.";
        }
        if ("breath".equals(breathCompositionPanelTopic)) {
            return "Choose and calibrate the live controller or Polar ACC breath input.";
        }
        if ("polar".equals(breathCompositionPanelTopic)) {
            return "Pairing and acquisition remain owned by the same-APK Polar runtime.";
        }
        if ("lsl".equals(breathCompositionPanelTopic)) {
            return "Persistent, panel-controlled LAN outlets and one bounded Float32 inlet.";
        }
        if ("status".equals(breathCompositionPanelTopic)) {
            return "Requested values never replace the renderer's effective readback.";
        }
        return "Organized by the live system that owns each setting.";
    }

    private void selectBreathCompositionPanelTopic(String topic) {
        if (topic.equals(breathCompositionPanelTopic)) {
            return;
        }
        replaceBreathCompositionPanelContent(topic);
    }

    private void replaceBreathCompositionPanelContent(String topic) {
        rememberPanelScroll();
        cancelPendingPrivateParticleDynamicsApply();
        cancelPendingPrivateParticleEffectReadback();
        cancelBreathCompositionRefresh();
        invalidatePrivateParticleControls();
        breathCompositionPanelNavigationEpoch += 1L;
        breathCompositionPanelTopic = topic;
        setContentView(buildBreathCompositionControlPanelView());
        restorePanelScrollAfterLayout();
        scheduleExperimenterProjectionRefresh();
    }

    private void selectExperimenterPage(ExperimentSessionPanelViewPolicy.Page page) {
        rememberPanelScroll();
        experimenterNavigation.select(page);
        replaceBreathCompositionPanelContent("home");
    }

    private void rememberPanelScroll() {
        if (panelScrollView == null) return;
        int y = panelScrollView.getScrollY();
        if (PANEL_ROUTE_EXPERIMENTER.equals(renderedPanelRoute)) {
            if ("polar".equals(renderedPanelTopic)) polarPageScrollY = y;
            else experimenterNavigation.rememberScroll(renderedExperimenterPage, y);
        } else if (renderedPanelTopic != null) developerPageScroll.put(renderedPanelTopic, y);
    }

    private void restorePanelScrollAfterLayout() {
        final ScrollView scroll = panelScrollView;
        if (scroll == null) return;
        final long epoch = breathCompositionPanelNavigationEpoch;
        Integer developerY = developerPageScroll.get(breathCompositionPanelTopic);
        final int y = PANEL_ROUTE_EXPERIMENTER.equals(panelRoute)
            ? ("polar".equals(breathCompositionPanelTopic) ? polarPageScrollY : experimenterNavigation.scrollY())
            : (developerY == null ? 0 : developerY);
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                scroll.removeOnLayoutChangeListener(this);
                if (panelScrollView == scroll && epoch == breathCompositionPanelNavigationEpoch) {
                    scroll.scrollTo(0, y);
                }
            }
        });
    }

    /** Native callbacks update current views; only explicit navigation replaces the body. */
    private void refreshExperimenterAfterReadback() {
        if (PANEL_ROUTE_EXPERIMENTER.equals(panelRoute)) refreshExperimenterProjectionViews();
    }

    /** Call after an accepted arm, before returning to VR, so panel recall shows run controls. */
    private void showExperimenterSessionPage() {
        selectExperimenterPage(ExperimentSessionPanelViewPolicy.Page.SESSION);
    }

    private TextView experimenterIndicator(LinearLayout root, String value,
            ExperimentSessionPanelViewPolicy.Tone tone) {
        TextView label = text(value, 15, PANEL_FG);
        styleExperimenterIndicator(label, tone);
        root.addView(label);
        return label;
    }

    private void styleExperimenterIndicator(TextView label, ExperimentSessionPanelViewPolicy.Tone tone) {
        if (label.getTag() == tone) return;
        label.setTag(tone);
        int color = tone == ExperimentSessionPanelViewPolicy.Tone.READY ? Color.rgb(139, 223, 174)
            : tone == ExperimentSessionPanelViewPolicy.Tone.ATTENTION ? Color.rgb(255, 158, 154)
            : tone == ExperimentSessionPanelViewPolicy.Tone.WAITING ? PANEL_ACCENT : PANEL_FG;
        label.setTextColor(color);
        label.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL_SURFACE);
        background.setCornerRadius(dp(3));
        background.setStroke(dp(1), color);
        label.setBackground(background);
    }

    private void setExperimenterText(TextView view, String value) {
        if (view != null && !value.contentEquals(view.getText())) view.setText(value);
    }

    private void invalidatePrivateParticleControls() {
        privateParticlePanelLiveApply = false;
        privateParticleControlsHydrating = true;
        privateParticleControlEpoch = -1L;
        privateParticleMaterialSelection = -1;
        privateParticleVisualScale = null;
        privateParticleSizeMode = null;
        privateParticleSizeWorldMeters = null;
        privateParticleSizeSpherePercent = null;
        privateParticleSizeOscillationPercent = null;
        privateParticleSizeOverrideEnabled = false;
        privateParticleSizeModeSelection = -1;
        privateParticleWorldAnchorScale = null;
        privateParticleDrivers = new SliderControl[8];
        privateParticleTracerDrawSlots = null;
        privateParticleTracerLifetime = null;
        privateParticleTracerCopies = null;
        privateParticleMaterialPreset = null;
        privateParticlePolarRrOrbitBoost = null;
        privateParticleEffectiveReadback = null;
    }

    private void appendBreathCompositionPanelHome(LinearLayout root) {
        root.addView(
            text(
                "Use a focused topic rather than one long mixed control surface. Each topic reports the state actually accepted by its runtime owner.",
                13,
                PANEL_MUTED
            )
        );
        appendBreathCompositionTopicRow(
            root,
            "Particles",
            "Shape, oscillator drivers, trails, material A/B, and Polar RR orbit boost.",
            "particles"
        );
        appendBreathCompositionTopicRow(
            root,
            "Breath",
            "Controller or Polar ACC mapping, calibration, and current live output.",
            "breath"
        );
        appendBreathCompositionTopicRow(
            root,
            "Polar",
            "Connection, ACC acquisition, and RR availability for the optional orbit boost.",
            "polar"
        );
        appendBreathCompositionTopicRow(
            root,
            "LSL",
            "Stream Polar, controller, and headset samples out; map one normalized Float32 stream into driver slots 1–7.",
            "lsl"
        );
        appendBreathCompositionTopicRow(
            root,
            "Effective state",
            "Read the renderer-confirmed particle revision and the current breath composition.",
            "status"
        );
    }

    private void appendBreathCompositionTopicRow(
        LinearLayout root,
        String title,
        String summary,
        final String topic
    ) {
        LinearLayout card = panelCard(title);
        card.addView(text(summary, 13, PANEL_MUTED));
        Button open = button("Open " + title);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectBreathCompositionPanelTopic(topic);
            }
        });
        card.addView(open);
        root.addView(card);
    }

    private void appendBreathCompositionBreathControls(LinearLayout root) {
        LinearLayout statusCard = panelCard("Live breath state");
        breathOverviewReadback = text("Reading native-effective selection…", 15, PANEL_FG);
        breathCalibrationReadback = text("Calibration: waiting for readback", 13, PANEL_MUTED);
        breathCalibrationProgress = new ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        );
        breathCalibrationProgress.setMax(1000);
        breathCalibrationProgress.setProgress(0);
        breathOutputReadback = text("Live output: none", 13, PANEL_MUTED);
        statusCard.addView(breathOverviewReadback);
        statusCard.addView(breathCalibrationReadback);
        statusCard.addView(breathCalibrationProgress);
        statusCard.addView(breathOutputReadback);
        root.addView(statusCard);

        LinearLayout guidanceCard = panelCard("Guided-breathing bias for next arm");
        guidanceCard.addView(text(
            "This is the same 0–100% value shown on the experimenter condition page. It is runtime-only and is locked into the recording when a condition is armed.",
            12,
            PANEL_MUTED
        ));
        developerGuidanceOneReadback = experimenterIndicator(
            guidanceCard,
            "Condition 1 breathing pattern: checking…",
            ExperimentSessionPanelViewPolicy.Tone.WAITING
        );
        developerGuidanceTwoReadback = experimenterIndicator(
            guidanceCard,
            "Condition 2 breathing pattern: checking…",
            ExperimentSessionPanelViewPolicy.Tone.WAITING
        );
        String developerGuidanceOne = ControlPanelActivity.conditionBreathGuidanceReadiness(
            ExperimentSessionPanelCoordinator.CONDITION_ONE);
        String developerGuidanceTwo = ControlPanelActivity.conditionBreathGuidanceReadiness(
            ExperimentSessionPanelCoordinator.CONDITION_TWO);
        boolean developerPatternOne = "pattern-ready".equals(developerGuidanceOne);
        boolean developerPatternTwo = "pattern-ready".equals(developerGuidanceTwo);
        if (experimenterGuidanceBiasPercent < 0) {
            int firstDefault = ControlPanelActivity.conditionBreathGuidanceDefaultBias(
                ExperimentSessionPanelCoordinator.CONDITION_ONE);
            int secondDefault = ControlPanelActivity.conditionBreathGuidanceDefaultBias(
                ExperimentSessionPanelCoordinator.CONDITION_TWO);
            experimenterGuidanceBiasPercent = developerPatternOne ? firstDefault
                : developerPatternTwo ? secondDefault
                : "no-pattern".equals(developerGuidanceOne)
                    && "no-pattern".equals(developerGuidanceTwo) ? 0 : -1;
            if (experimenterGuidanceBiasPercent >= 0) {
                EXPERIMENT_SESSION_PANEL.updatePendingBreathGuidanceBiasPercent(
                    experimenterGuidanceBiasPercent);
            }
        }
        developerGuidanceBias = new SliderControl(
            "Breathing-pattern bias", 0.0, 100.0,
            Math.max(0, experimenterGuidanceBiasPercent), 100, "%", true,
            new Runnable() {
                @Override public void run() {
                    int requested = developerGuidanceBias.intValue();
                    if (EXPERIMENT_SESSION_PANEL.updatePendingBreathGuidanceBiasPercent(requested)) {
                        experimenterGuidanceBiasPercent = requested;
                    }
                }
            });
        developerGuidanceBias.setInteractive(
            (developerPatternOne || developerPatternTwo)
                && !EXPERIMENT_SESSION_PANEL.snapshot().hasActiveSession());
        guidanceCard.addView(developerGuidanceBias.view);
        guidanceCard.addView(text(
            "0% is fully signal-driven; 100% is guidance-dominant while still requiring valid Polar tracking. Finish the current recording before changing the value for another arm.",
            12,
            PANEL_MUTED
        ));
        root.addView(guidanceCard);

        LinearLayout mappingCard = panelCard("Input mapping");
        final Spinner source = spinner(new String[] {"Controller", "Polar ACC"}, 0);
        final Spinner mapping = spinner(new String[] {"Volume", "State"}, 0);
        final Spinner controllerProjection = spinner(
            new String[] {"Dynamic axis", "Fixed orientation"},
            0
        );
        final Spinner polarProjection = spinner(new String[] {"XZ", "3D"}, 0);
        final CheckBox inverted = checkBox("Invert assessed direction", false);
        mappingCard.addView(label("Input source"));
        mappingCard.addView(source);
        mappingCard.addView(label("Movement mapping"));
        mappingCard.addView(mapping);
        mappingCard.addView(label("Controller volume calibration"));
        mappingCard.addView(controllerProjection);
        mappingCard.addView(label("Polar ACC calibration space"));
        mappingCard.addView(polarProjection);
        mappingCard.addView(inverted);

        final TextView readback = text("Structured diagnostics pending.", 12, PANEL_MUTED);
        breathDiagnosticsReadback = readback;
        Button applySelection = button("Apply breath mapping");
        applySelection.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    JSONObject command = new JSONObject()
                        .put("schema", BREATH_COMPOSITION_COMMAND_SCHEMA)
                        .put("operation", "select")
                        .put("source", "Controller".equals(selected(source)) ? "controller" : "polar-acc")
                        .put("mapping", "Volume".equals(selected(mapping)) ? "volume" : "state")
                        .put("controller_projection", "Fixed orientation".equals(selected(controllerProjection))
                            ? "fixed-orientation" : "dynamic-axis")
                        .put("polar_projection", "3D".equals(selected(polarProjection)) ? "3d" : "xz")
                        .put("inverted", inverted.isChecked());
                    applyBreathCompositionCommand(command, readback);
                } catch (Exception error) {
                    readback.setText("Selection request failed: " + markerToken(error.getMessage()));
                }
            }
        });
        mappingCard.addView(applySelection);
        root.addView(mappingCard);

        JSONObject polarTuning = readPolarStateTuningSettings();
        LinearLayout polarStateCard = panelCard("Polar state sensitivity");
        polarStateCard.addView(text(
            "These controls affect only Polar ACC state classification. Controller assessment and Polar RR orbit pulses remain independent.",
            12,
            PANEL_MUTED
        ));
        final EditText polarInhaleEntry = tuningField(
            polarStateCard,
            "Inhale entry (/s)",
            polarTuning.optDouble("inhale_entry_per_second", 0.030)
        );
        final EditText polarExhaleEntry = tuningField(
            polarStateCard,
            "Exhale entry (/s)",
            polarTuning.optDouble("exhale_entry_per_second", 0.030)
        );
        final EditText polarHoldBand = tuningField(
            polarStateCard,
            "Hold band (/s)",
            polarTuning.optDouble("hold_band_per_second", 0.025)
        );
        final EditText polarSmoothing = tuningField(
            polarStateCard,
            "Derivative smoothing (ms)",
            polarTuning.optDouble("smoothing_millis", 400.0)
        );
        final EditText polarConfirmation = tuningField(
            polarStateCard,
            "State confirmation (ms)",
            polarTuning.optDouble("confirmation_millis", 400.0)
        );
        final EditText polarDwell = tuningField(
            polarStateCard,
            "Minimum phase dwell (ms)",
            polarTuning.optDouble("minimum_dwell_millis", 400.0)
        );
        final EditText polarStale = tuningField(
            polarStateCard,
            "Stale gap (ms)",
            polarTuning.optDouble("stale_millis", 500.0)
        );
        final EditText polarMotion = tuningField(
            polarStateCard,
            "Motion admission (mg)",
            polarTuning.optDouble("motion_admission_mg", 2.0)
        );
        final EditText polarLeaveContraction = tuningField(
            polarStateCard,
            "Leave full contraction (/s)",
            polarTuning.optDouble("leave_full_contraction_per_second", 0.030)
        );
        final EditText polarLeaveExpansion = tuningField(
            polarStateCard,
            "Leave full expansion (/s)",
            polarTuning.optDouble("leave_full_expansion_per_second", 0.030)
        );
        final EditText polarLateWindow = tuningField(
            polarStateCard,
            "Late-sample window (ms)",
            polarTuning.optDouble("late_sample_window_millis", 120.0)
        );
        Button applyPolarTuning = button("Apply Polar state tuning");
        applyPolarTuning.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    JSONObject statusJson = new JSONObject(nativeReadBreathCompositionStatus());
                    JSONObject snapshot = statusJson.getJSONObject("snapshot");
                    JSONObject tuning = snapshot.getJSONObject("polar_state_tuning");
                    JSONObject settings = new JSONObject()
                        .put("inhale_entry_per_second", tuningDouble(polarInhaleEntry))
                        .put("exhale_entry_per_second", tuningDouble(polarExhaleEntry))
                        .put("hold_band_per_second", tuningDouble(polarHoldBand))
                        .put("smoothing_millis", tuningLong(polarSmoothing))
                        .put("confirmation_millis", tuningLong(polarConfirmation))
                        .put("minimum_dwell_millis", tuningLong(polarDwell))
                        .put("stale_millis", tuningLong(polarStale))
                        .put("motion_admission_mg", tuningDouble(polarMotion))
                        .put("leave_full_contraction_per_second", tuningDouble(polarLeaveContraction))
                        .put("leave_full_expansion_per_second", tuningDouble(polarLeaveExpansion))
                        .put("late_sample_window_millis", tuningLong(polarLateWindow));
                    JSONObject command = new JSONObject()
                        .put("schema", BREATH_COMPOSITION_COMMAND_SCHEMA)
                        .put("operation", "configure_polar_state")
                        .put("session_id", tuning.getString("session_id"))
                        .put("generation", tuning.optLong("generation", 0L) + 1L)
                        .put("request_id", UUID.randomUUID().toString().replace("-", ""))
                        .put("settings", settings);
                    applyBreathCompositionCommand(command, readback);
                } catch (Exception error) {
                    readback.setText("Polar tuning request failed: " + markerToken(error.getMessage()));
                }
            }
        });
        polarStateCard.addView(applyPolarTuning);
        root.addView(polarStateCard);

        LinearLayout calibrationCard = panelCard("Calibration");
        calibrationCard.addView(text(
            "Move through a comfortable full breath range. Start here or hold right B for 1.25 seconds.",
            12,
            PANEL_MUTED
        ));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        String[] operationNames = new String[] {"start_calibration", "cancel", "reset"};
        String[] operationLabels = new String[] {"Start", "Cancel", "Reset"};
        for (int i = 0; i < operationNames.length; i++) {
            final String operation = operationNames[i];
            Button action = button(operationLabels[i]);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    applyBreathCompositionOperation(
                        operation,
                        readback,
                        "cancel".equals(operation)
                    );
                }
            });
            actions.addView(action, rowButtonParams());
        }
        calibrationCard.addView(actions);
        root.addView(calibrationCard);

        LinearLayout diagnostics = panelCard("Effective readback");
        diagnostics.addView(readback);
        Button refresh = button("Refresh breath state");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshBreathCompositionReadback(readback);
            }
        });
        diagnostics.addView(refresh);
        root.addView(diagnostics);
        refreshBreathCompositionReadback(readback);
        scheduleBreathCompositionRefresh();
    }

    private JSONObject readPolarStateTuningSettings() {
        try {
            JSONObject statusJson = new JSONObject(nativeReadBreathCompositionStatus());
            JSONObject tuning = statusJson.getJSONObject("snapshot")
                .getJSONObject("polar_state_tuning");
            JSONObject effective = tuning.optJSONObject("effective");
            JSONObject settings = effective == null ? null : effective.optJSONObject("settings");
            return settings == null ? new JSONObject() : settings;
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    private EditText tuningField(LinearLayout parent, String title, double value) {
        parent.addView(label(title));
        EditText field = editText(String.format(Locale.US, "%.6f", value), title, false);
        field.setInputType(
            android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        );
        parent.addView(field);
        return field;
    }

    private double tuningDouble(EditText field) {
        return Double.parseDouble(field.getText().toString().trim());
    }

    private long tuningLong(EditText field) {
        double value = tuningDouble(field);
        if (!Double.isFinite(value) || value < 0.0 || value != Math.rint(value)) {
            throw new IllegalArgumentException("integer-milliseconds-required");
        }
        return (long) value;
    }

    private void appendBreathCompositionPolarControls(LinearLayout root) {
        LinearLayout polar = panelCard("Polar connection & acquisition");
        polar.addView(text(
            "Scan, connect, select ACC, and start PMD. RR events remain separate from the breath mapping and are consumed only when the Particle topic explicitly enables RR orbit boost.",
            12,
            PANEL_MUTED
        ));
        polar.addView(ensurePolarSensorPanel().buildEmbeddedAcquisitionView());
        root.addView(polar);
    }

    private void appendBreathCompositionLslControls(LinearLayout root) {
        JSONObject status = readLslTransportStatusJson();
        JSONObject config = status.optJSONObject("config");
        if (config == null) {
            config = new JSONObject();
        }
        JSONObject outlets = config.optJSONObject("outlets");
        JSONObject inlet = config.optJSONObject("inlet");
        JSONObject rustyLsl = config.optJSONObject("rusty_lsl");

        LinearLayout overview = panelCard("Effective LSL state");
        final TextView readback = text(formatLslStatus(status), 12, PANEL_MUTED);
        overview.addView(readback);
        Button refresh = button("Refresh effective state");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                readback.setText(formatLslStatus(readLslTransportStatusJson()));
            }
        });
        overview.addView(refresh);
        root.addView(overview);

        LinearLayout identity = panelCard("Session identity");
        final EditText prefix = editText(config.optString("stream_prefix", "rustyquest"), "stream prefix", false);
        final EditText participant = editText(config.optString("participant_id", "participant"), "participant id", false);
        final EditText session = editText(config.optString("session_id", "session"), "session id", false);
        identity.addView(label("Stream prefix"));
        identity.addView(prefix);
        identity.addView(label("Participant ID"));
        identity.addView(participant);
        identity.addView(label("Session ID"));
        identity.addView(session);
        root.addView(identity);

        LinearLayout directions = panelCard("Transport directions");
        final CheckBox enabled = checkBox("Enable LSL runtime", config.optBoolean("enabled", false));
        final CheckBox outletEnabled = checkBox("Enable selected outlets", config.optBoolean("outlet_enabled", false));
        final CheckBox inletEnabled = checkBox("Enable Float32 inlet", config.optBoolean("inlet_enabled", false));
        directions.addView(enabled);
        directions.addView(outletEnabled);
        directions.addView(inletEnabled);
        root.addView(directions);

        LinearLayout backendCard = panelCard("Transport backend A/B");
        backendCard.addView(text(
            "LibLSL remains the default for outlets and inlets. Rusty-LSL is an experimental outlet-only backend; it uses the same producer queue and stream schemas. A Rusty-LSL inlet or mixed Rusty-LSL outlet + LibLSL inlet request is rejected rather than silently changing the comparison.",
            12,
            PANEL_MUTED
        ));
        String outletBackendValue = config.optString("outlet_backend", "liblsl");
        String inletBackendValue = config.optString("inlet_backend", "liblsl");
        final Spinner outletBackend = spinner(
            new String[] {"LibLSL", "Rusty-LSL (experimental outlet)"},
            "rusty-lsl".equals(outletBackendValue) ? 1 : 0
        );
        final Spinner inletBackend = spinner(
            new String[] {"LibLSL", "Rusty-LSL (unavailable inlet)"},
            "rusty-lsl".equals(inletBackendValue) ? 1 : 0
        );
        final EditText rustyLslInterface = editText(
            rustyLsl == null ? "0.0.0.0" : rustyLsl.optString("interface_ipv4", "0.0.0.0"),
            "Quest LAN IPv4, required for Rusty-LSL",
            false
        );
        backendCard.addView(label("Outlet backend"));
        backendCard.addView(outletBackend);
        backendCard.addView(label("Inlet backend"));
        backendCard.addView(inletBackend);
        backendCard.addView(label("Rusty-LSL interface IPv4"));
        backendCard.addView(rustyLslInterface);
        root.addView(backendCard);

        LinearLayout outletCard = panelCard("Outlet streams");
        final CheckBox polarHr = checkBox("Polar heart rate (BPM)", outlets == null || outlets.optBoolean("polar_hr", true));
        final CheckBox polarRr = checkBox("Polar RR intervals (ms)", outlets == null || outlets.optBoolean("polar_rr", true));
        final CheckBox polarAcc = checkBox("Polar ACC (x/y/z mg, nominal 200 Hz)", outlets == null || outlets.optBoolean("polar_acc", true));
        final CheckBox polarEcg = checkBox("Polar ECG (microvolts, nominal 130 Hz)", outlets == null || outlets.optBoolean("polar_ecg", true));
        final CheckBox controller = checkBox("Right controller grip pose", outlets == null || outlets.optBoolean("controller_right_grip", true));
        final CheckBox headset = checkBox("Headset stereo OpenXR view poses", outlets == null || outlets.optBoolean("headset_views", true));
        outletCard.addView(polarHr);
        outletCard.addView(polarRr);
        outletCard.addView(polarAcc);
        outletCard.addView(polarEcg);
        outletCard.addView(controller);
        outletCard.addView(headset);
        root.addView(outletCard);

        LinearLayout inletCard = panelCard("Float32 inlet → particle driver");
        inletCard.addView(text(
            "The first finite sample channel must be normalized to 0…1. Slot 0 remains reserved for the breathing composition; LSL may target only slots 1–7.",
            12,
            PANEL_MUTED
        ));
        String resolveByValue = inlet == null ? "source_id" : inlet.optString("resolve_by", "source_id");
        final Spinner resolveBy = spinner(
            new String[] {"Source ID", "Name", "Type"},
            "name".equals(resolveByValue) ? 1 : ("type".equals(resolveByValue) ? 2 : 0)
        );
        final EditText resolveValue = editText(
            inlet == null ? "rustyquest.input.driver1" : inlet.optString("resolve_value", "rustyquest.input.driver1"),
            "exact source id, name, or type",
            false
        );
        int driverSlot = inlet == null ? 1 : Math.max(1, Math.min(7, inlet.optInt("driver_slot", 1)));
        final Spinner driver = spinner(new String[] {"1", "2", "3", "4", "5", "6", "7"}, driverSlot - 1);
        final EditText hold = editText(
            String.format(Locale.US, "%.3f", inlet == null ? 1.0 : inlet.optDouble("sample_hold_seconds", 1.0)),
            "sample hold seconds",
            false
        );
        final CheckBox recover = checkBox("Recover after sender loss", inlet == null || inlet.optBoolean("recover", true));
        inletCard.addView(label("Resolve by"));
        inletCard.addView(resolveBy);
        inletCard.addView(label("Exact selector"));
        inletCard.addView(resolveValue);
        inletCard.addView(label("Driver slot"));
        inletCard.addView(driver);
        inletCard.addView(label("Sample hold (seconds)"));
        inletCard.addView(hold);
        inletCard.addView(recover);
        root.addView(inletCard);

        LinearLayout actions = panelCard("Apply for this process");
        actions.addView(text(
            "Developer edits are runtime-only. They do not replay on the next launch; use the read-only effective snapshot when asking for reviewed packaged-default changes.",
            12,
            PANEL_MUTED
        ));
        Button apply = button("Apply LSL configuration");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    String resolve = selected(resolveBy);
                    String outletBackendSelection = selected(outletBackend);
                    String inletBackendSelection = selected(inletBackend);
                    JSONObject requested = new JSONObject()
                        .put("schema", "rusty.quest.native_renderer.lsl.persisted_config.v1")
                        .put("enabled", enabled.isChecked())
                        .put("outlet_enabled", outletEnabled.isChecked())
                        .put("inlet_enabled", inletEnabled.isChecked())
                        .put("outlet_backend", outletBackendSelection.startsWith("Rusty-LSL") ? "rusty-lsl" : "liblsl")
                        .put("inlet_backend", inletBackendSelection.startsWith("Rusty-LSL") ? "rusty-lsl" : "liblsl")
                        .put("rusty_lsl", new JSONObject()
                            .put("interface_ipv4", rustyLslInterface.getText().toString().trim())
                            .put("source_commit", "8b6b2a6cd0c0e5147b7e1cc076a116ef226cddbd"))
                        .put("stream_prefix", prefix.getText().toString().trim())
                        .put("participant_id", participant.getText().toString().trim())
                        .put("session_id", session.getText().toString().trim())
                        .put("outlets", new JSONObject()
                            .put("polar_hr", polarHr.isChecked())
                            .put("polar_rr", polarRr.isChecked())
                            .put("polar_acc", polarAcc.isChecked())
                            .put("polar_ecg", polarEcg.isChecked())
                            .put("controller_right_grip", controller.isChecked())
                            .put("headset_views", headset.isChecked()))
                        .put("inlet", new JSONObject()
                            .put("resolve_by", "Name".equals(resolve) ? "name" : ("Type".equals(resolve) ? "type" : "source_id"))
                            .put("resolve_value", resolveValue.getText().toString().trim())
                            .put("driver_slot", Integer.parseInt(selected(driver)))
                            .put("sample_hold_seconds", Double.parseDouble(hold.getText().toString().trim()))
                            .put("recover", recover.isChecked()));
                    applyLslPanelOperation("apply", requested, readback);
                } catch (Exception error) {
                    readback.setText("LSL request rejected locally: " + markerToken(error.getMessage()));
                }
            }
        });
        actions.addView(apply);
        Button stop = button("Disable LSL");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                applyLslPanelOperation("disable", null, readback);
            }
        });
        actions.addView(stop);
        Button reset = button("Reset LSL defaults");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                applyLslPanelOperation("reset", null, readback);
            }
        });
        actions.addView(reset);
        root.addView(actions);
    }

    private JSONObject readLslTransportStatusJson() {
        try {
            return new JSONObject(nativeReadLslTransportStatus());
        } catch (Exception error) {
            try {
                return new JSONObject()
                    .put("schema", "rusty.quest.native_renderer.lsl.status.v1")
                    .put("response_status", "unavailable")
                    .put("response_reason", markerToken(error.getMessage()))
                    .put("panel_available", false)
                    .put("library_linked", false)
                    .put("app_session_id", "none")
                    .put("generation", 0)
                    .put("config", new JSONObject());
            } catch (Exception impossible) {
                return new JSONObject();
            }
        }
    }

    private String formatLslStatus(JSONObject status) {
        JSONObject effective = status.optJSONObject("effective");
        return "Packaged: " + status.optBoolean("panel_available", false)
            + " | liblsl: " + status.optBoolean("library_linked", false)
            + " | rusty-lsl: " + status.optBoolean("rusty_lsl_compiled", false)
            + " | state: " + (effective == null ? "unknown" : effective.optString("state", "unknown"))
            + "\nbackend out=" + (effective == null ? "unknown" : effective.optString("outlet_backend", "unknown"))
            + " in=" + (effective == null ? "unknown" : effective.optString("inlet_backend", "unknown"))
            + " | outlets: " + (effective == null ? 0 : effective.optInt("outlet_count", 0))
            + " | inlet: " + (effective == null ? "unknown" : effective.optString("inlet_state", "unknown"))
            + "\npushed=" + (effective == null ? 0 : effective.optLong("samples_pushed", 0))
            + " pulled=" + (effective == null ? 0 : effective.optLong("samples_pulled", 0))
            + " dropped=" + (effective == null ? 0 : effective.optLong("samples_dropped", 0))
            + " rejectedInlet=" + (effective == null ? 0 : effective.optLong("inlet_samples_rejected", 0))
            + "\npushNsTotal=" + (effective == null ? 0 : effective.optLong("push_elapsed_ns_total", 0))
            + " pushNsMax=" + (effective == null ? 0 : effective.optLong("push_elapsed_ns_max", 0))
            + " deliveries=" + (effective == null ? 0 : effective.optLong("complete_deliveries", 0))
            + " consumers=" + (effective == null ? 0 : effective.optInt("connected_consumers", 0))
            + " discoveries=" + (effective == null ? 0 : effective.optLong("discovery_queries", 0))
            + "\nsession=" + status.optString("app_session_id", "none")
            + " generation=" + status.optLong("generation", 0)
            + " reason=" + (effective == null ? status.optString("response_reason", "unknown") : effective.optString("reason", "none"));
    }

    private void applyLslPanelOperation(String operation, JSONObject config, TextView readback) {
        try {
            JSONObject current = readLslTransportStatusJson();
            JSONObject command = new JSONObject()
                .put("schema", LSL_PANEL_COMMAND_SCHEMA)
                .put("operation", operation)
                .put("request_id", UUID.randomUUID().toString())
                .put("app_session_id", current.optString("app_session_id", ""))
                .put("generation", current.optLong("generation", 0) + 1L);
            if (config != null) {
                command.put("config", config);
            }
            JSONObject response = new JSONObject(nativeApplyLslTransportCommand(command.toString()));
            if (!"accepted".equals(response.optString("response_status", ""))) {
                readback.setText("LSL request rejected: " + formatLslStatus(response));
                return;
            }
            JSONObject acceptedConfig = response.optJSONObject("config");
            boolean effectiveEnabled = acceptedConfig != null
                && acceptedConfig.optBoolean("enabled", false);
            LslMulticastLockManager.setFromPanel(this, effectiveEnabled);
            readback.setText(formatLslStatus(response) + "\nephemeral=true");
        } catch (Exception error) {
            readback.setText("LSL request failed: " + markerToken(error.getMessage()));
        }
    }

    private void appendBreathCompositionStatus(LinearLayout root) {
        LinearLayout particle = panelCard("Particle renderer effective state");
        privateParticleEffectiveReadback = text(
            "Particle effective receipt: waiting for renderer readback.",
            13,
            PANEL_MUTED
        );
        particle.addView(privateParticleEffectiveReadback);
        Button particleRefresh = button("Refresh particle state");
        particleRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshPrivateParticleDynamicsFromStatus(true);
            }
        });
        particle.addView(particleRefresh);
        root.addView(particle);

        LinearLayout breath = panelCard("Breath composition state");
        final TextView readback = text("Structured diagnostics pending.", 12, PANEL_MUTED);
        breathDiagnosticsReadback = readback;
        breath.addView(readback);
        Button breathRefresh = button("Refresh breath state");
        breathRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshBreathCompositionReadback(readback);
            }
        });
        breath.addView(breathRefresh);
        final TextView effectiveSnapshot = text(
            "Canonical effective snapshot is read only on request.",
            12,
            PANEL_MUTED
        );
        Button snapshotRead = button("Read canonical effective snapshot");
        snapshotRead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                effectiveSnapshot.setText(readCanonicalEffectiveSnapshot());
            }
        });
        breath.addView(snapshotRead);
        breath.addView(effectiveSnapshot);
        root.addView(breath);
        refreshPrivateParticleDynamicsFromStatus(false);
        refreshBreathCompositionReadback(readback);
    }

    private String readCanonicalEffectiveSnapshot() {
        try {
            JSONObject breath = new JSONObject(nativeReadBreathCompositionStatus());
            JSONObject particle = readPrivateParticleDynamicsStatusJson();
            JSONObject result = new JSONObject()
                .put("schema", "rusty.quest.native_renderer.effective_settings_export.v1")
                .put("read_only", true)
                .put("replay_supported", false)
                .put("breath", breath)
                .put("particles", particle == null ? JSONObject.NULL : particle);
            return result.toString(2);
        } catch (Throwable error) {
            return "Effective snapshot unavailable: " + markerToken(error.getMessage());
        }
    }

    private View buildBreathMappingPanelView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PANEL_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Direct Breath Mapping", 22, PANEL_FG);
        header.addView(
            title,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        );
        Button close = button("Return to VR");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launchImmersiveRenderer();
            }
        });
        header.addView(close);
        root.addView(header);
        root.addView(text("Four-way direct breath control · same process · RR excluded", 13, PANEL_MUTED));

        LinearLayout statusCard = panelCard("Active status");
        breathOverviewReadback = text("Reading native-effective selection…", 15, PANEL_FG);
        breathCalibrationReadback = text("Calibration: waiting for readback", 13, PANEL_MUTED);
        breathCalibrationProgress = new ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        );
        breathCalibrationProgress.setMax(1000);
        breathCalibrationProgress.setProgress(0);
        breathOutputReadback = text("Live output: none", 13, PANEL_MUTED);
        statusCard.addView(breathOverviewReadback);
        statusCard.addView(breathCalibrationReadback);
        statusCard.addView(breathCalibrationProgress);
        statusCard.addView(breathOutputReadback);
        root.addView(statusCard);

        LinearLayout mappingCard = panelCard("Mapping");
        final Spinner source = spinner(new String[] {"Controller", "Polar ACC"}, 0);
        final Spinner mapping = spinner(new String[] {"Volume", "State"}, 0);
        final Spinner controllerProjection = spinner(
            new String[] {"Dynamic axis", "Fixed orientation"},
            0
        );
        final Spinner polarProjection = spinner(new String[] {"XZ", "3D"}, 0);
        final CheckBox inverted = checkBox("Invert assessed direction", false);
        mappingCard.addView(label("Input source"));
        mappingCard.addView(source);
        mappingCard.addView(label("Movement mapping"));
        mappingCard.addView(mapping);
        mappingCard.addView(label("Controller volume calibration"));
        mappingCard.addView(controllerProjection);
        mappingCard.addView(label("Polar ACC calibration space"));
        mappingCard.addView(polarProjection);
        mappingCard.addView(inverted);

        final TextView readback = text("Structured diagnostics pending.", 12, PANEL_MUTED);
        breathDiagnosticsReadback = readback;
        Button applySelection = button("Apply selection");
        applySelection.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    JSONObject command = new JSONObject()
                        .put("schema", BREATH_COMPOSITION_COMMAND_SCHEMA)
                        .put("operation", "select")
                        .put(
                            "source",
                            "Controller".equals(selected(source)) ? "controller" : "polar-acc"
                        )
                        .put(
                            "mapping",
                            "Volume".equals(selected(mapping)) ? "volume" : "state"
                        )
                        .put(
                            "controller_projection",
                            "Fixed orientation".equals(selected(controllerProjection))
                                ? "fixed-orientation"
                                : "dynamic-axis"
                        )
                        .put(
                            "polar_projection",
                            "3D".equals(selected(polarProjection)) ? "3d" : "xz"
                        )
                        .put("inverted", inverted.isChecked());
                    applyBreathCompositionCommand(command, readback);
                } catch (Exception error) {
                    readback.setText(
                        "Selection request failed: " + markerToken(error.getMessage())
                    );
                }
            }
        });
        mappingCard.addView(applySelection);
        root.addView(mappingCard);

        LinearLayout calibrationCard = panelCard("Calibration");
        calibrationCard.addView(
            text(
                "Move through a comfortable full breath range. Start here or hold the right B button for 1.25 seconds. The same native calibration state remains authoritative when this panel closes.",
                12,
                PANEL_MUTED
            )
        );
        Button start = button("Start calibration");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                applyBreathCompositionOperation("start_calibration", readback, false);
            }
        });
        Button cancel = button("Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                applyBreathCompositionOperation("cancel", readback, true);
            }
        });
        Button reset = button("Reset");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                applyBreathCompositionOperation("reset", readback, false);
            }
        });
        LinearLayout calibrationActions = new LinearLayout(this);
        calibrationActions.setOrientation(LinearLayout.HORIZONTAL);
        calibrationActions.addView(start, rowButtonParams());
        calibrationActions.addView(cancel, rowButtonParams());
        calibrationActions.addView(reset, rowButtonParams());
        calibrationCard.addView(calibrationActions);
        root.addView(calibrationCard);

        appendUnifiedParticleControls(root);

        LinearLayout diagnosticsCard = panelCard("Diagnostics");
        diagnosticsCard.addView(readback);
        Button refresh = button("Refresh readback");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshBreathCompositionReadback(readback);
            }
        });
        diagnosticsCard.addView(refresh);
        diagnosticsCard.addView(
            text(
                "Polar raw acquisition remains owned by the existing same-APK Polar sensor path. RR/heartbeat events are separate and never enter this breath composition.",
                12,
                PANEL_MUTED
            )
        );
        root.addView(diagnosticsCard);

        LinearLayout polarCard = panelCard("Polar connection");
        polarCard.addView(
            text(
                "Scan, connect, select ACC, and start PMD. Return to VR keeps this Activity's single BLE owner alive in the background. Bluetooth/location readiness and PMD state are written as structured app-owned status.",
                12,
                PANEL_MUTED
            )
        );
        polarCard.addView(ensurePolarSensorPanel().buildEmbeddedAcquisitionView());
        root.addView(polarCard);
        refreshBreathCompositionReadback(readback);
        scheduleBreathCompositionRefresh();
        return scroll;
    }

    private void appendUnifiedParticleControls(LinearLayout root) {
        // Keep the private-particle surface autonomous.  It deliberately
        // does not reuse the legacy panel's liveAutoApply checkbox, which may
        // be absent when this view is built and previously made edits inert.
        liveAutoApply = null;
        privateParticlePanelLiveApply = true;
        privateParticleControlsHydrating = true;
        privateParticleControlEpoch = breathCompositionPanelNavigationEpoch;
        final long controlEpoch = privateParticleControlEpoch;
        LinearLayout particle = panelCard("Particles");
        root.addView(particle);
        particle.addView(
            text(
                "Each edit applies only its named field through a debounced, renderer-safe JSON patch. Unedited particle, material, driver, tracer, anchor, and heartbeat state is preserved. The effective receipt—not a slider position—is authoritative.",
                12,
                PANEL_MUTED
            )
        );

        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        JSONArray driverStatus = privateParticles == null
            ? null
            : privateParticles.optJSONArray("driver_values01");
        JSONObject tracerStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("tracer");
        JSONObject materialStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("material");
        JSONObject heartbeatStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("heartbeat_pulse");
        JSONObject sizeStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("size");

        LinearLayout shape = panelCard("Shape");
        particle.addView(shape);
        double legacyVisualScale = readPrivateParticleStatusDouble(
            privateParticles,
            "visual_scale",
            readDoubleProperty(PROP_PRIVATE_PARTICLE_VISUAL_SCALE, 0.70)
        );
        privateParticleVisualScale = privateParticleSlider(
            "Legacy particle visual scale",
            0.05,
            1.0,
            legacyVisualScale,
            1000,
            "",
            false,
            "visual_scale"
        );
        double legacyMinPercent = 4.0 * legacyVisualScale;
        double legacyMaxPercent = 11.5 * legacyVisualScale;
        double legacyBasePercent = Math.sqrt(legacyMinPercent * legacyMaxPercent);
        double legacyOscillationPercent = 100.0
            * (1.0 - Math.sqrt(legacyMinPercent / legacyMaxPercent));
        String sizeMode = sizeStatus == null
            ? "sphere-radius-percent"
            : sizeStatus.optString("mode", "sphere-radius-percent");
        privateParticleSizeOverrideEnabled = sizeStatus != null
            && sizeStatus.optBoolean("enabled", false);
        privateParticleSizeModeSelection = "world-meters".equals(sizeMode) ? 1 : 0;
        shape.addView(label("Particle size unit"));
        privateParticleSizeMode = spinner(
            new String[] { "Percent of sphere radius", "Raw world size (meters)" },
            privateParticleSizeModeSelection
        );
        shape.addView(privateParticleSizeMode);
        privateParticleSizeSpherePercent = privateParticleSizeSlider(
            "Base particle size",
            0.1,
            50.0,
            sizeStatus == null
                ? legacyBasePercent
                : sizeStatus.optDouble("sphere_radius_percent", legacyBasePercent),
            49900,
            " % of sphere radius",
            false,
            "size.sphere_radius_percent"
        );
        privateParticleSizeWorldMeters = privateParticleSizeSlider(
            "Base particle size",
            0.001,
            0.5,
            sizeStatus == null
                ? legacyBasePercent * 0.01
                : sizeStatus.optDouble("world_meters", legacyBasePercent * 0.01),
            4990,
            " m",
            false,
            "size.world_meters"
        );
        privateParticleSizeOscillationPercent = privateParticleSizeSlider(
            "Size oscillation",
            0.0,
            90.0,
            sizeStatus == null
                ? legacyOscillationPercent
                : sizeStatus.optDouble("oscillation_percent", legacyOscillationPercent),
            9000,
            " %",
            false,
            "size.oscillation_percent"
        );
        shape.addView(privateParticleSizeSpherePercent.view);
        shape.addView(privateParticleSizeWorldMeters.view);
        shape.addView(privateParticleSizeOscillationPercent.view);
        shape.addView(
            text(
                "The percentage mode follows the current deformed sphere radius; meter mode stays constant in world space. Oscillation is reciprocal: 50% means 0.5× at minimum and 2× at maximum.",
                12,
                PANEL_MUTED
            )
        );
        privateParticleWorldAnchorScale = privateParticleSlider(
            "Sphere radius / anchor scale",
            0.05,
            4.0,
            readPrivateParticleStatusDouble(
                privateParticles,
                "world_anchor_scale_m",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE, 0.46)
            ),
            1000,
            " m",
            false,
            "world_anchor_scale_m"
        );
        shape.addView(privateParticleWorldAnchorScale.view);
        updatePrivateParticleSizeModeVisibility();
        privateParticleSizeMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updatePrivateParticleSizeModeVisibility();
                if (privateParticleControlsHydrating || position == privateParticleSizeModeSelection) {
                    privateParticleSizeModeSelection = position;
                    return;
                }
                privateParticleSizeModeSelection = position;
                privateParticleSizeOverrideEnabled = true;
                schedulePrivateParticleDynamicsApplyFromControl(controlEpoch, "size.mode");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        LinearLayout dynamics = panelCard("Oscillator drivers");
        particle.addView(dynamics);
        for (int i = 0; i < privateParticleDrivers.length; i++) {
            double fallback = readDoubleProperty(PROP_PRIVATE_PARTICLE_DRIVERS[i], 0.0);
            double initial = driverStatus == null ? fallback : driverStatus.optDouble(i, fallback);
            privateParticleDrivers[i] = privateParticleSlider(
                PRIVATE_PARTICLE_DRIVER_LABELS[i],
                0.0,
                1.0,
                initial,
                1000,
                "",
                false,
                "driver_values01." + i
            );
            dynamics.addView(privateParticleDrivers[i].view);
        }

        LinearLayout tracer = panelCard("Tracers");
        particle.addView(tracer);
        privateParticleTracerDrawSlots = privateParticleSlider(
            "Tracer draw slots",
            0.0,
            1024.0,
            readPrivateParticleStatusTracerDouble(
                tracerStatus,
                "draw_slots_per_oscillator",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_DRAW_SLOTS, 7.0)
            ),
            1024,
            "",
            true,
            "tracer.draw_slots_per_oscillator"
        );
        privateParticleTracerLifetime = privateParticleSlider(
            "Tracer lifetime",
            0.016,
            30.0,
            readPrivateParticleStatusTracerDouble(
                tracerStatus,
                "lifetime_seconds",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_LIFETIME, 0.5)
            ),
            1000,
            " s",
            false,
            "tracer.lifetime_seconds"
        );
        privateParticleTracerCopies = privateParticleSlider(
            "Tracer copies/sec",
            0.0,
            120.0,
            readPrivateParticleStatusTracerDouble(
                tracerStatus,
                "copies_per_second",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_COPIES, 14.0)
            ),
            1000,
            "",
            false,
            "tracer.copies_per_second"
        );
        tracer.addView(privateParticleTracerDrawSlots.view);
        tracer.addView(privateParticleTracerLifetime.view);
        tracer.addView(privateParticleTracerCopies.view);

        LinearLayout material = panelCard("Material & Polar RR orbit boost");
        particle.addView(material);
        material.addView(label("Material A/B preset"));
        privateParticleMaterialPreset = spinner(
            new String[] {
                "Keep packaged/default material",
                "Current additive",
                "Premultiplied alpha over",
                "Premultiplied alpha over + depth fade",
                "Premultiplied alpha over + depth + facing fade",
                "Reference material emulation"
            },
            privateParticleMaterialPresetIndex(
                materialStatus == null
                    ? "packaged-default"
                    : materialStatus.optString("preset", "packaged-default")
            )
        );
        privateParticleMaterialSelection = privateParticleMaterialPreset.getSelectedItemPosition();
        material.addView(privateParticleMaterialPreset);
        privateParticlePolarRrOrbitBoost = checkBox(
            "Enable Polar RR orbit boost",
            heartbeatStatus != null
                && "polar-rr-orbit-boost".equals(heartbeatStatus.optString("mode", "disabled"))
        );
        material.addView(privateParticlePolarRrOrbitBoost);
        material.addView(
            text(
                "Uses only valid Polar RR events. No synthetic beat, custom threshold, amplitude, or decay control is exposed here; those remain in the private GPU envelope.",
                12,
                PANEL_MUTED
            )
        );

        privateParticleMaterialPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (admitPrivateParticleMaterialSelection(controlEpoch, position)) {
                    schedulePrivateParticleDynamicsApplyFromControl(controlEpoch, "material");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        privateParticlePolarRrOrbitBoost.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                schedulePrivateParticleDynamicsApplyFromControl(controlEpoch, "heartbeat_pulse");
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("Refresh effective");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshPrivateParticleDynamicsFromStatus(true);
            }
        });
        actions.addView(refresh, rowButtonParams());
        particle.addView(actions);
        privateParticleEffectiveReadback = text(
            "Particle effective receipt: waiting for renderer readback.",
            12,
            PANEL_MUTED
        );
        particle.addView(privateParticleEffectiveReadback);
        refreshPrivateParticleDynamicsFromStatus(false);
        privateParticleControlsHydrating = false;
    }

    private void applyBreathCompositionOperation(
        String operation,
        TextView readback,
        boolean includeGeneration
    ) {
        try {
            JSONObject command = new JSONObject()
                .put("schema", BREATH_COMPOSITION_COMMAND_SCHEMA)
                .put("operation", operation);
            if (includeGeneration) {
                if (breathCompositionGeneration <= 0L) {
                    refreshBreathCompositionReadback(readback);
                }
                if (breathCompositionGeneration <= 0L) {
                    readback.setText("Cancel rejected: no native-effective generation.");
                    return;
                }
                command.put("generation", breathCompositionGeneration);
            }
            applyBreathCompositionCommand(command, readback);
        } catch (Exception error) {
            readback.setText("Lifecycle request failed: " + markerToken(error.getMessage()));
        }
    }

    private void refreshExperimenterPolarProjection() {
        try {
            JSONObject projection = PolarSensorRuntime.forApplication(getApplicationContext())
                .experimenterStatusProjection();
            JSONObject ble = projection.optJSONObject("ble_runtime");
            String adapter = ble == null
                ? "unknown" : ble.optString("bluetooth_adapter_state", "unknown");
            ExperimentSessionPanelState.Bluetooth bluetooth;
            if (ble != null && !ble.optBoolean("runtime_permission_ready", false)) {
                bluetooth = ExperimentSessionPanelState.Bluetooth.PERMISSION_REQUIRED;
            } else if ("on".equals(adapter)) {
                bluetooth = ExperimentSessionPanelState.Bluetooth.ON;
            } else if ("off".equals(adapter)) {
                bluetooth = ExperimentSessionPanelState.Bluetooth.OFF;
            } else if ("unavailable".equals(adapter)) {
                bluetooth = ExperimentSessionPanelState.Bluetooth.UNSUPPORTED;
            } else if ("turning-on".equals(adapter) || "turning-off".equals(adapter)) {
                bluetooth = ExperimentSessionPanelState.Bluetooth.TURNING;
            } else if ("permission-blocked".equals(adapter)) {
                bluetooth = ExperimentSessionPanelState.Bluetooth.PERMISSION_REQUIRED;
            } else {
                bluetooth = ExperimentSessionPanelState.Bluetooth.UNKNOWN;
            }
            String automatic = projection.optString("automatic_connection_state", "not-started");
            boolean scanInFlight = projection.optBoolean("scanning", false);
            ExperimentSessionPanelState.Polar polar;
            if ("connected".equals(automatic)) {
                polar = ExperimentSessionPanelState.Polar.CONNECTED;
            } else if (scanInFlight || "scanning".equals(automatic)) {
                polar = ExperimentSessionPanelState.Polar.SCANNING;
            } else if ("connecting".equals(automatic)) {
                polar = ExperimentSessionPanelState.Polar.CONNECTING;
            } else if ("multiple-candidates".equals(automatic)) {
                polar = ExperimentSessionPanelState.Polar.MULTIPLE;
            } else if ("not-found".equals(automatic)) {
                polar = ExperimentSessionPanelState.Polar.NOT_FOUND;
            } else if ("location-services-disabled".equals(automatic)) {
                polar = ExperimentSessionPanelState.Polar.LOCATION_SERVICES_DISABLED;
            } else if ("failed".equals(automatic)) {
                polar = ExperimentSessionPanelState.Polar.FAILED;
            } else {
                polar = ExperimentSessionPanelState.Polar.UNKNOWN;
            }
            long observedAt = projection.optLong("automatic_connection_updated_at_unix_ms", 0L);
            long connectionGeneration = projection.optLong(
                "automatic_connection_generation",
                0L
            );
            long deadlineElapsedMs = projection.optLong(
                "automatic_connection_deadline_elapsed_ms",
                0L
            );
            boolean currentPlatformPrerequisite =
                polar == ExperimentSessionPanelState.Polar.LOCATION_SERVICES_DISABLED;
            boolean fresh = currentPlatformPrerequisite || scanInFlight || PolarAutoConnectionPolicy.evidenceFresh(
                connectionGeneration,
                projection.optLong("automatic_connection_evidence_generation", 0L),
                observedAt,
                System.currentTimeMillis(),
                10_000L,
                polar == ExperimentSessionPanelState.Polar.CONNECTING
                    ? deadlineElapsedMs : 0L,
                SystemClock.elapsedRealtime()
            );
            if (!fresh && polar != ExperimentSessionPanelState.Polar.UNKNOWN) {
                polar = ExperimentSessionPanelState.Polar.STALE;
            }
            EXPERIMENT_SESSION_PANEL.updatePolar(
                new ExperimentSessionPanelState.PolarProjection(
                    bluetooth,
                    polar,
                    projection.optInt("candidate_count", 0),
                    connectionGeneration,
                    observedAt,
                    deadlineElapsedMs,
                    fresh,
                    scanInFlight
                        ? projection.optString("detail", "Scanning for a Polar sensor.")
                        : projection.optString(
                            "automatic_connection_detail",
                            projection.optString("detail", automatic)
                        )
                )
            );
        } catch (Throwable ignored) {
            EXPERIMENT_SESSION_PANEL.updatePolar(ExperimentSessionPanelState.PolarProjection.unknown());
        }
    }

    private void startExperimentSession(String condition) {
        String audioReadiness = ControlPanelActivity.conditionAudioReadiness(condition);
        if (!"track-ready".equals(audioReadiness)) {
            updateStatus("Start rejected: " + audioReadiness + ".");
            rebuildContentViewForCurrentMode();
            return;
        }
        ExperimentSessionPanelCoordinator.NativeCommand command =
            EXPERIMENT_SESSION_PANEL.arm(condition,
                "pattern-ready".equals(
                    ControlPanelActivity.conditionBreathGuidanceReadiness(condition))
                    ? Math.max(0, Math.min(100, experimenterGuidanceBiasPercent)) : 0);
        if (command == null) {
            updateStatus("Start rejected: a session or operation is already active.");
            rebuildContentViewForCurrentMode();
            return;
        }
        showExperimenterSessionPage();
        submitExperimentSessionCommand(command, true);
    }

    private void saveExperimentSessionAndPrepareNext() {
        ExperimentSessionPanelState state = EXPERIMENT_SESSION_PANEL.snapshot();
        if (!ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(state)) {
            updateStatus("Wait for the current recording operation before preparing another run.");
            return;
        }
        ExperimentSessionPanelCoordinator.NativeCommand command =
            EXPERIMENT_SESSION_PANEL.restartToExperimenter(
                EXPERIMENT_SESSION_PANEL.allocateRouteEvent());
        if (command == null) {
            updateStatus("Could not begin saving the current session. Refresh its status.");
            return;
        }
        updateStatus("Saving this session before the next run…");
        refreshExperimenterAfterReadback();
        EXPERIMENT_SESSION_SHELL.submit(command, false, this);
    }

    private void requestExperimentSessionStatus() {
        if (!nativeBridgeLoaded) {
            updateStatus("Session status unavailable: native bridge is not loaded.");
            return;
        }
        EXPERIMENT_SESSION_SHELL.requestStatus(this);
    }

    private void submitExperimentSessionCommand(
        ExperimentSessionPanelCoordinator.NativeCommand command,
        boolean start
    ) {
        if (command == null) {
            return;
        }
        EXPERIMENT_SESSION_SHELL.submit(command, start, this);
        refreshExperimenterAfterReadback();
    }

    @Override
    public boolean acceptsExperimentSessionCallbacks(long launchEpoch) {
        return !experimentShellDestroyed && launchEpoch == experimentShellLaunchEpoch;
    }

    @Override
    public void onExperimentSessionReadback(
            ExperimentSessionAndroidShell.SessionReadback readback) {
        if (readback == null || readback.receipt == null) return;
        EXPERIMENT_SESSION_PANEL.acceptRuntimeEpoch(readback.runtimeEpoch);
        ExperimentSessionPanelState before = EXPERIMENT_SESSION_PANEL.snapshot();
        boolean changed = EXPERIMENT_SESSION_PANEL.accept(readback.receipt);
        ExperimentSessionPanelState after = EXPERIMENT_SESSION_PANEL.snapshot();
        // Audio preparation is an idempotent arm-stage effect. Reassert it from
        // every authoritative arming readback so a reordered status callback or
        // panel recreation cannot leave the native session permanently arming.
        if (after.phase == ExperimentSessionPanelState.Phase.ARMING && after.recording) {
            if (!ControlPanelActivity.startConditionAudio(
                    after.generation,
                    "session-arm-" + after.generation,
                    after.activeCondition)) {
                updateStatus("Session armed, but condition audio preparation was rejected.");
            }
        }
        if (changed && before.phase != ExperimentSessionPanelState.Phase.ARMED
                && after.phase == ExperimentSessionPanelState.Phase.ARMED) {
            closePanelAndReturnToImmersive();
            return;
        }
        if (changed && before.phase == ExperimentSessionPanelState.Phase.SAVING
                && after.phase == ExperimentSessionPanelState.Phase.IDLE
                && "saved".equals(readback.recordingResult)
                && after.pendingOperationId.isEmpty()) {
            updateStatus("Session saved. Prepare the next run.");
            selectExperimenterPage(ExperimentSessionPanelViewPolicy.Page.PREPARE);
            return;
        }
        refreshExperimenterAfterReadback();
    }

    @Override
    public void onExperimentSessionTerminal(
            ExperimentSessionAndroidShell.TerminalResult result) {
        if (result == null) return;
        if (result.shouldFinish && !terminalFinishDispatched) {
            terminalFinishDispatched = true;
            ControlPanelActivity.finishTerminalSaveAndExit(
                this,
                result.saved,
                result.sessionGeneration,
                result.operationId,
                result.shutdownAckRevision
            );
            return;
        }
        updateStatus("Save and exit pending: " + result.detail + "; saved=false");
    }

    private boolean handleTerminalSaveAndExitIntent(Intent intent) {
        if (!ControlPanelActivity.admitTerminalSaveAndExit(this, intent)) {
            return EXPERIMENT_SESSION_SHELL.resumePendingTerminal();
        }
        long guardGeneration = intent.getLongExtra(
            NativeRendererSoftKioskCoordinator.EXTRA_GUARD_GENERATION,
            0L
        );
        long homeEpisode = intent.getLongExtra(
            NativeRendererSoftKioskCoordinator.EXTRA_HOME_EPISODE,
            0L
        );
        if (EXPERIMENT_SESSION_SHELL.beginTerminal(
                guardGeneration, homeEpisode, this)) {
            updateStatus("Saving recording and closing…");
        }
        return true;
    }

    private void scheduleExperimenterProjectionRefresh() {
        cancelExperimenterProjectionRefresh();
        if (liveApplyHandler == null
                || !PANEL_ROUTE_EXPERIMENTER.equals(panelRoute)
                || !"home".equals(breathCompositionPanelTopic)) {
            return;
        }
        experimenterProjectionRefresh = new Runnable() {
            @Override
            public void run() {
                refreshExperimenterPolarProjection();
                refreshExperimenterProjectionViews();
                EXPERIMENT_SESSION_SHELL.requestStatus(BreathCompositionPanelModule.this);
                if (experimenterProjectionRefresh == this && liveApplyHandler != null) {
                    liveApplyHandler.postDelayed(this, 1000L);
                }
            }
        };
        liveApplyHandler.postDelayed(experimenterProjectionRefresh, 500L);
    }

    private void cancelExperimenterProjectionRefresh() {
        if (liveApplyHandler != null && experimenterProjectionRefresh != null) {
            liveApplyHandler.removeCallbacks(experimenterProjectionRefresh);
        }
        experimenterProjectionRefresh = null;
    }

    private void clearExperimenterProjectionViews() {
        experimenterBluetoothReadback = null;
        experimenterPolarReadback = null;
        experimenterCountReadback = null;
        experimenterStatusReadback = null;
        experimenterKioskReadback = null;
        experimenterKioskDetail = null;
        experimenterPolarFallback = null;
        experimenterStartOne = null;
        experimenterStartTwo = null;
        experimenterStageReadback = null;
        experimenterInstructionReadback = null;
        experimenterStorageReadback = null;
        experimenterStorageDetail = null;
        experimenterFooterReadback = null;
        experimenterAudioOneReadback = null;
        experimenterAudioTwoReadback = null;
        experimenterGuidanceOneReadback = null;
        experimenterGuidanceTwoReadback = null;
        experimenterGuidanceBias = null;
        developerGuidanceOneReadback = null;
        developerGuidanceTwoReadback = null;
        developerGuidanceBias = null;
        experimenterResume = null;
        experimenterNext = null;
        experimenterSaveNext = null;
        experimenterDeveloper = null;
    }

    private void refreshExperimenterProjectionViews() {
        ExperimentSessionPanelState state = EXPERIMENT_SESSION_PANEL.snapshot();
        ExperimentSessionPanelViewPolicy.ViewState projected =
            ExperimentSessionPanelViewPolicy.project(state);
        if (experimenterBluetoothReadback != null) {
            setExperimenterText(experimenterBluetoothReadback, projected.bluetoothLine);
            styleExperimenterIndicator(experimenterBluetoothReadback, ExperimentSessionPanelViewPolicy.bluetoothTone(state));
        }
        if (experimenterPolarReadback != null) {
            setExperimenterText(experimenterPolarReadback, projected.polarLine);
            styleExperimenterIndicator(experimenterPolarReadback, ExperimentSessionPanelViewPolicy.polarTone(state));
        }
        if (experimenterCountReadback != null) {
            setExperimenterText(experimenterCountReadback, projected.countLine);
        }
        if (experimenterStatusReadback != null) {
            setExperimenterText(experimenterStatusReadback, projected.statusLine);
            experimenterStatusReadback.setTextColor(projected.saving ? PANEL_ACCENT : PANEL_MUTED);
        }
        if (experimenterKioskReadback != null) {
            ExperimentSessionPanelViewPolicy.ReadinessCard kiosk =
                ExperimentSessionPanelViewPolicy.kioskCard(
                    ControlPanelActivity.softKioskUiState(this));
            setExperimenterText(experimenterKioskReadback, kiosk.label);
            styleExperimenterIndicator(experimenterKioskReadback, kiosk.tone);
            setExperimenterText(experimenterKioskDetail, kiosk.detail);
            if (experimenterKioskDetail != null) experimenterKioskDetail.setVisibility(
                kiosk.detail.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (experimenterPolarFallback != null) {
            setExperimenterText(experimenterPolarFallback, projected.showPolarFallback
                ? "Open Polar connection" : "Inspect Polar connection");
        }
        String audioOne = experimenterStartOne == null ? ""
            : ControlPanelActivity.conditionAudioReadiness(ExperimentSessionPanelCoordinator.CONDITION_ONE);
        String audioTwo = experimenterStartTwo == null ? ""
            : ControlPanelActivity.conditionAudioReadiness(ExperimentSessionPanelCoordinator.CONDITION_TWO);
        if (experimenterStartOne != null) {
            experimenterStartOne.setEnabled(projected.startEnabled && "track-ready".equals(audioOne));
        }
        if (experimenterStartTwo != null) {
            experimenterStartTwo.setEnabled(projected.startEnabled && "track-ready".equals(audioTwo));
        }
        updateExperimenterAudioReadback(experimenterAudioOneReadback, "Condition 1", audioOne);
        updateExperimenterAudioReadback(experimenterAudioTwoReadback, "Condition 2", audioTwo);
        boolean guidanceControlsVisible = experimenterStartOne != null
            || developerGuidanceBias != null;
        String guidanceOne = !guidanceControlsVisible ? "" :
            ControlPanelActivity.conditionBreathGuidanceReadiness(
                ExperimentSessionPanelCoordinator.CONDITION_ONE);
        String guidanceTwo = !guidanceControlsVisible ? "" :
            ControlPanelActivity.conditionBreathGuidanceReadiness(
                ExperimentSessionPanelCoordinator.CONDITION_TWO);
        updateExperimenterGuidanceReadback(
            experimenterGuidanceOneReadback, "Condition 1", guidanceOne);
        updateExperimenterGuidanceReadback(
            experimenterGuidanceTwoReadback, "Condition 2", guidanceTwo);
        updateExperimenterGuidanceReadback(
            developerGuidanceOneReadback, "Condition 1", guidanceOne);
        updateExperimenterGuidanceReadback(
            developerGuidanceTwoReadback, "Condition 2", guidanceTwo);
        if (experimenterGuidanceBias != null || developerGuidanceBias != null) {
            boolean oneReady = "pattern-ready".equals(guidanceOne);
            boolean twoReady = "pattern-ready".equals(guidanceTwo);
            if (experimenterGuidanceBiasPercent < 0 && (oneReady || twoReady)) {
                experimenterGuidanceBiasPercent = ControlPanelActivity
                    .conditionBreathGuidanceDefaultBias(oneReady
                        ? ExperimentSessionPanelCoordinator.CONDITION_ONE
                        : ExperimentSessionPanelCoordinator.CONDITION_TWO);
                EXPERIMENT_SESSION_PANEL.updatePendingBreathGuidanceBiasPercent(
                    experimenterGuidanceBiasPercent);
                if (experimenterGuidanceBias != null) {
                    experimenterGuidanceBias.setValue(experimenterGuidanceBiasPercent);
                }
                if (developerGuidanceBias != null) {
                    developerGuidanceBias.setValue(experimenterGuidanceBiasPercent);
                }
            } else if (experimenterGuidanceBiasPercent < 0
                    && "no-pattern".equals(guidanceOne)
                    && "no-pattern".equals(guidanceTwo)) {
                experimenterGuidanceBiasPercent = 0;
                EXPERIMENT_SESSION_PANEL.updatePendingBreathGuidanceBiasPercent(0);
                if (experimenterGuidanceBias != null) experimenterGuidanceBias.setValue(0);
                if (developerGuidanceBias != null) developerGuidanceBias.setValue(0);
            }
            if (experimenterGuidanceBias != null) {
                experimenterGuidanceBias.setInteractive(oneReady || twoReady);
            }
            if (developerGuidanceBias != null) {
                developerGuidanceBias.setInteractive(
                    (oneReady || twoReady) && !state.hasActiveSession());
            }
        }
        if (experimenterStageReadback != null) {
            setExperimenterText(experimenterStageReadback, ExperimentSessionPanelViewPolicy.stageTitle(state));
            styleExperimenterIndicator(experimenterStageReadback, ExperimentSessionPanelViewPolicy.stageTone(state));
        }
        setExperimenterText(experimenterInstructionReadback, ExperimentSessionPanelViewPolicy.stageInstruction(state));
        if (experimenterStorageReadback != null) {
            ExperimentSessionPanelViewPolicy.ReadinessCard storage =
                ExperimentSessionPanelViewPolicy.storageCard(state);
            setExperimenterText(experimenterStorageReadback, storage.label);
            styleExperimenterIndicator(experimenterStorageReadback, storage.tone);
            setExperimenterText(experimenterStorageDetail, storage.detail);
            if (experimenterStorageDetail != null) experimenterStorageDetail.setVisibility(
                storage.detail.isEmpty() ? View.GONE : View.VISIBLE);
        }
        setExperimenterText(experimenterFooterReadback, ExperimentSessionPanelViewPolicy.stageTitle(state));
        if (experimenterResume != null) {
            experimenterResume.setEnabled(
                ExperimentSessionPanelViewPolicy.canReturnToImmersive(state) && !projected.saving
            );
            experimenterResume.setText(state.phase == ExperimentSessionPanelState.Phase.ERROR
                ? "Return to VR to finish" : "Return to VR");
        }
        if (experimenterNext != null
                && experimenterNavigation.page() == ExperimentSessionPanelViewPolicy.Page.SESSION) {
            experimenterNext.setEnabled(!state.hasActiveSession()
                || ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(state));
            experimenterNext.setText(projected.saving ? "Saving session…"
                : state.hasActiveSession() ? "Save session & prepare next" : "Prepare next run");
        }
        if (experimenterSaveNext != null) {
            experimenterSaveNext.setEnabled(
                ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(state));
            experimenterSaveNext.setText(projected.saving
                ? "Saving session…" : "Save session & prepare next");
        }
        if (experimenterDeveloper != null) experimenterDeveloper.setEnabled(!projected.saving);
    }

    private void updateExperimenterAudioReadback(TextView readback, String label, String readiness) {
        if (readback != null) {
            boolean ready = "track-ready".equals(readiness);
            setExperimenterText(readback, label + " audio: " + (ready ? "ready" : readiness));
            styleExperimenterIndicator(readback, ready
                ? ExperimentSessionPanelViewPolicy.Tone.READY : ExperimentSessionPanelViewPolicy.Tone.ATTENTION);
        }
    }

    private void updateExperimenterGuidanceReadback(
            TextView readback, String label, String readiness) {
        if (readback == null) return;
        boolean ready = "pattern-ready".equals(readiness);
        boolean absent = "no-pattern".equals(readiness);
        setExperimenterText(readback, label + " breathing pattern: " + (ready
            ? "ready" : absent ? "not supplied · signal-only" : "checking…"));
        styleExperimenterIndicator(readback, ready
            ? ExperimentSessionPanelViewPolicy.Tone.READY : absent
                ? ExperimentSessionPanelViewPolicy.Tone.NEUTRAL
                : ExperimentSessionPanelViewPolicy.Tone.WAITING);
    }

    private void applyBreathCompositionCommand(JSONObject command, TextView readback) {
        try {
            String response = nativeApplyBreathCompositionCommand(command.toString());
            renderBreathCompositionResponse(response, readback);
            writeBreathCompositionOperatorReceipt(
                "ui-" + SystemClock.elapsedRealtimeNanos(),
                command.optString("operation", "unknown"),
                response
            );
        } catch (Throwable error) {
            readback.setText("Native command unavailable: " + markerToken(error.getMessage()));
        }
    }

    private void refreshBreathCompositionReadback(TextView readback) {
        try {
            renderBreathCompositionResponse(nativeReadBreathCompositionStatus(), readback);
        } catch (Throwable error) {
            readback.setText("Native readback unavailable: " + markerToken(error.getMessage()));
        }
    }

    private void renderBreathCompositionResponse(String responseJson, TextView readback)
        throws Exception {
        JSONObject response = new JSONObject(responseJson == null ? "{}" : responseJson);
        JSONObject snapshot = response.optJSONObject("snapshot");
        if (snapshot == null) {
            breathCompositionGeneration = 0L;
            readback.setText(
                "Rejected: " + response.optString("reason_code", "missing-native-snapshot")
            );
            return;
        }
        breathCompositionGeneration = snapshot.optLong("generation", 0L);
        JSONObject requested = snapshot.optJSONObject("requested");
        JSONObject effective = snapshot.optJSONObject("effective");
        JSONObject output = snapshot.optJSONObject("output");
        JSONObject assessment = snapshot.optJSONObject("latest_assessment");
        JSONObject calibration = snapshot.optJSONObject("calibration_readback");
        JSONObject telemetry = snapshot.optJSONObject("telemetry");
        JSONObject polarStateTuning = snapshot.optJSONObject("polar_state_tuning");
        JSONObject polarStateDiagnostics = snapshot.optJSONObject("polar_state_diagnostics");
        String requestedSummary = requested == null
            ? "none"
            : requested.optString("source") + " × " + requested.optString("mapping");
        String effectiveSummary = effective == null
            ? "none"
            : effective.optString("source") + " × " + effective.optString("mapping");
        if (breathOverviewReadback != null) {
            breathOverviewReadback.setText(
                "Active: " + effectiveSummary
                    + "\nRequested: " + requestedSummary
                    + " · status " + snapshot.optString("status", "unknown")
                    + " · generation " + (breathCompositionGeneration > 0L
                        ? String.valueOf(breathCompositionGeneration)
                        : "none")
            );
        }
        double progress01 = calibration == null ? 0.0 : calibration.optDouble("progress01", 0.0);
        progress01 = Math.max(0.0, Math.min(1.0, progress01));
        if (breathCalibrationProgress != null) {
            breathCalibrationProgress.setProgress((int) Math.round(progress01 * 1000.0));
        }
        if (breathCalibrationReadback != null) {
            breathCalibrationReadback.setText(
                calibration == null
                    ? "Calibration: not started · hold right B or use Start calibration"
                    : "Calibration: " + calibration.optString("lifecycle", "unknown")
                        + " · " + Math.round(progress01 * 100.0) + "% · "
                        + calibration.opt("accepted_frames") + "/"
                        + calibration.opt("target_frames") + " accepted frames"
                        + (calibration.isNull("failure_code")
                            ? ""
                            : " · " + calibration.optString("failure_code"))
            );
        }
        if (breathOutputReadback != null) {
            breathOutputReadback.setText(
                output == null
                    ? "Live output: none"
                    : "Live output: volume " + output.opt("volume01")
                        + " · phase " + output.opt("phase")
                        + " · sequence " + output.opt("sequence_id")
            );
        }
        StringBuilder lines = new StringBuilder();
        lines.append("command=")
            .append(response.optString("command_status", "unknown"))
            .append(" reason=")
            .append(response.optString("reason_code", "none"));
        lines.append("\nlock active=")
            .append(snapshot.optBoolean("feature_lock_active", false))
            .append(" binding match=")
            .append(snapshot.optBoolean("activation_binding_matches", false))
            .append(" status=")
            .append(snapshot.optString("status", "unknown"))
            .append(" generation=")
            .append(
                breathCompositionGeneration > 0L
                    ? String.valueOf(breathCompositionGeneration)
                    : "none"
            );
        lines.append("\nrequested=")
            .append(requested == null ? "none" : requested.optString("source") + "/" + requested.optString("mapping"));
        lines.append(" effective=")
            .append(effective == null ? "none" : effective.optString("source") + "/" + effective.optString("mapping"));
        lines.append("\ncalibration=")
            .append(calibration == null ? "none" : calibration.optString("lifecycle", "none"))
            .append(" generation=")
            .append(calibration == null ? "none" : calibration.opt("generation"))
            .append(" progress=")
            .append(calibration == null ? "none" : calibration.opt("progress01"))
            .append(" frames=")
            .append(
                calibration == null
                    ? "none"
                    : calibration.opt("accepted_frames") + "/" + calibration.opt("target_frames")
            )
            .append(" failure=")
            .append(calibration == null ? "none" : calibration.opt("failure_code"))
            .append(" tracking=")
            .append(assessment == null ? "none" : assessment.optString("tracking", "none"))
            .append(" quality=")
            .append(assessment == null ? "none" : assessment.opt("quality01"));
        lines.append("\nvolume=")
            .append(output == null ? "none" : output.opt("volume01"))
            .append(" phase=")
            .append(output == null ? "none" : output.opt("phase"))
            .append(" inputAgeMicros=")
            .append(assessment == null ? "none" : assessment.opt("input_age_micros"));
        lines.append("\naccepted/rejected=")
            .append(telemetry == null ? "0/0" : telemetry.optLong("accepted_assessments") + "/" + telemetry.optLong("rejected_assessments"));
        lines.append(" rejection=").append(snapshot.optString("rejection", "none"));
        if (polarStateTuning != null) {
            JSONObject polarEffective = polarStateTuning.optJSONObject("effective");
            lines.append("\npolarState session=")
                .append(polarStateTuning.optString("session_id", "none"))
                .append(" generation=")
                .append(polarStateTuning.optLong("generation", 0L))
                .append(" effectiveRequest=")
                .append(polarEffective == null ? "none" : polarEffective.optString("request_id", "none"))
                .append(" reason=")
                .append(polarStateTuning.optString("reason", "none"));
        }
        if (polarStateDiagnostics != null) {
            lines.append("\npolarClassifier=")
                .append(polarStateDiagnostics.optString("classifier", "none"))
                .append(" phase=")
                .append(polarStateDiagnostics.optString("phase", "unknown"))
                .append(" transitions=")
                .append(polarStateDiagnostics.optLong("phase_transitions", 0L))
                .append(" holdTransitions=")
                .append(polarStateDiagnostics.optLong("hold_transitions", 0L));
            lines.append("\npolarLateDrops=")
                .append(polarStateDiagnostics.optLong("late_sample_drops", 0L))
                .append(" outOfWindow=")
                .append(polarStateDiagnostics.optLong("out_of_window_disorder", 0L))
                .append(" staleGaps=")
                .append(polarStateDiagnostics.optLong("stale_gaps", 0L));
        }
        readback.setText(lines.toString());
    }

    private void scheduleBreathCompositionRefresh() {
        if (liveApplyHandler == null || !"breath-mapping".equals(readControlPanelMode())) {
            return;
        }
        cancelBreathCompositionRefresh();
        breathCompositionRefresh = new Runnable() {
            @Override
            public void run() {
                if (breathDiagnosticsReadback != null && !isFinishing()) {
                    refreshBreathCompositionReadback(breathDiagnosticsReadback);
                    liveApplyHandler.postDelayed(this, 500L);
                }
            }
        };
        liveApplyHandler.postDelayed(breathCompositionRefresh, 500L);
    }

    private void cancelBreathCompositionRefresh() {
        if (liveApplyHandler != null && breathCompositionRefresh != null) {
            liveApplyHandler.removeCallbacks(breathCompositionRefresh);
        }
        breathCompositionRefresh = null;
    }


    private PolarSensorPanel ensurePolarSensorPanel() {
        if (polarSensorPanel == null) {
            polarSensorPanel = PolarSensorRuntime.forApplication(getApplicationContext()).attachPanel(this, new PolarSensorPanel.Host() {
                @Override
                public void closePanelAndReturnToImmersive() {
                    BreathCompositionPanelModule.this.closePanelAndReturnToImmersive();
                }

                @Override
                public void onPolarStreamEvent(JSONObject event) {
                    // Process-owned Polar and LSL runtimes already receive the event.
                }
            });
        }
        return polarSensorPanel;
    }

    private EditText editText(String value, String hint, boolean multiline) {
        EditText editText = new EditText(this);
        editText.setText(value == null ? "" : value);
        editText.setHint(hint == null ? "" : hint);
        editText.setSingleLine(!multiline);
        editText.setMinLines(multiline ? 3 : 1);
        editText.setTextColor(Color.WHITE);
        editText.setHintTextColor(PANEL_MUTED);
        editText.setBackgroundColor(PANEL_SURFACE);
        editText.setPadding(dp(8), dp(6), dp(8), dp(6));
        return editText;
    }

    private SliderControl privateParticleSlider(
        String title,
        double min,
        double max,
        double initial,
        int steps,
        String suffix,
        boolean integer,
        final String patchField
    ) {
        final long controlEpoch = privateParticleControlEpoch;
        return slider(
            title,
            min,
            max,
            initial,
            steps,
            suffix,
            integer,
            new Runnable() {
                @Override
                public void run() {
                    schedulePrivateParticleDynamicsApplyFromControl(controlEpoch, patchField);
                }
            }
        );
    }

    private SliderControl privateParticleSizeSlider(
        String title,
        double min,
        double max,
        double initial,
        int steps,
        String suffix,
        boolean integer,
        final String patchField
    ) {
        final long controlEpoch = privateParticleControlEpoch;
        return slider(
            title,
            min,
            max,
            initial,
            steps,
            suffix,
            integer,
            new Runnable() {
                @Override
                public void run() {
                    privateParticleSizeOverrideEnabled = true;
                    schedulePrivateParticleDynamicsApplyFromControl(controlEpoch, patchField);
                }
            }
        );
    }

    private void updatePrivateParticleSizeModeVisibility() {
        if (privateParticleSizeMode == null
                || privateParticleSizeSpherePercent == null
                || privateParticleSizeWorldMeters == null) {
            return;
        }
        boolean meters = privateParticleSizeMode.getSelectedItemPosition() == 1;
        privateParticleSizeSpherePercent.view.setVisibility(meters ? View.GONE : View.VISIBLE);
        privateParticleSizeWorldMeters.view.setVisibility(meters ? View.VISIBLE : View.GONE);
    }

    private boolean isCurrentPrivateParticleControlSurface(long controlEpoch) {
        return privateParticlePanelLiveApply
            && !privateParticleControlsHydrating
            && "particles".equals(breathCompositionPanelTopic)
            && controlEpoch >= 0L
            && controlEpoch == privateParticleControlEpoch
            && controlEpoch == breathCompositionPanelNavigationEpoch;
    }

    private boolean admitPrivateParticleMaterialSelection(long controlEpoch, int position) {
        if (controlEpoch != privateParticleControlEpoch
                || controlEpoch != breathCompositionPanelNavigationEpoch
                || !"particles".equals(breathCompositionPanelTopic)
                || privateParticleControlsHydrating) {
            if (controlEpoch == privateParticleControlEpoch) {
                privateParticleMaterialSelection = position;
            }
            return false;
        }
        if (position == privateParticleMaterialSelection) {
            return false;
        }
        privateParticleMaterialSelection = position;
        return true;
    }

    private void setPrivateParticleMaterialSelectionFromRuntime(int position) {
        privateParticleMaterialSelection = position;
        if (privateParticleMaterialPreset != null
                && privateParticleMaterialPreset.getSelectedItemPosition() != position) {
            privateParticleMaterialPreset.setSelection(position);
        }
    }

    private void schedulePrivateParticleDynamicsApplyFromControl(
        final long controlEpoch,
        final String patchField
    ) {
        if (!isCurrentPrivateParticleControlSurface(controlEpoch)) {
            return;
        }
        if (patchField == null || patchField.length() == 0) {
            return;
        }
        if (liveApplyHandler != null && pendingPrivateParticleDynamicsApply != null) {
            liveApplyHandler.removeCallbacks(pendingPrivateParticleDynamicsApply);
        }
        pendingPrivateParticlePatchFields.add(patchField);
        pendingPrivateParticleDynamicsApply = new Runnable() {
            @Override
            public void run() {
                pendingPrivateParticleDynamicsApply = null;
                if (!isCurrentPrivateParticleControlSurface(controlEpoch)) {
                    pendingPrivateParticlePatchFields.clear();
                    return;
                }
                LinkedHashSet<String> patchFields =
                    new LinkedHashSet<String>(pendingPrivateParticlePatchFields);
                pendingPrivateParticlePatchFields.clear();
                submitLivePrivateParticleDynamics(false, patchFields);
            }
        };
        liveApplyHandler.postDelayed(pendingPrivateParticleDynamicsApply, 180);
        setStatusText("Particle edit pending renderer-safe apply.");
    }

    private void cancelPendingPrivateParticleDynamicsApply() {
        if (liveApplyHandler != null && pendingPrivateParticleDynamicsApply != null) {
            liveApplyHandler.removeCallbacks(pendingPrivateParticleDynamicsApply);
            pendingPrivateParticleDynamicsApply = null;
        }
        pendingPrivateParticlePatchFields.clear();
    }

    private void submitLivePrivateParticleDynamics(
        boolean userVisible,
        Set<String> patchFields
    ) {
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            JSONObject candidate = buildPrivateParticleDynamicsPatchJson(patchFields);
            String responseText = nativeSubmitLivePrivateParticleDynamics(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            long revision = candidate.optLong("revision", 0L);
            if (!"queued".equals(responseStatus)
                    || revision <= 0L
                    || response.optLong("candidate_revision", 0L) != revision) {
                throw new IllegalStateException(responseText);
            }
            String message = "Particle dynamics queued: " + privateParticleDynamicsSummary() + ".";
            if (response.optBoolean("overwrote_pending", false)) {
                message = "Particle dynamics queued; older pending edit was replaced.";
            }
            if (userVisible) {
                updateStatus(message);
            } else {
                setStatusText(message);
            }
            awaitPrivateParticleEffectiveRevision(revision);
        } catch (Exception error) {
            if (userVisible) {
                updateStatus("Particle dynamics failed: " + error.getMessage());
            } else {
                setStatusText("Particle dynamics update failed: " + error.getMessage());
            }
        }
    }

    private void awaitPrivateParticleEffectiveRevision(long revision) {
        if (revision <= 0L || liveApplyHandler == null) {
            return;
        }
        cancelPendingPrivateParticleEffectReadback();
        privateParticlePendingRevision = revision;
        privateParticlePendingReadbackDeadlineMs = SystemClock.elapsedRealtime() + 4000L;
        pollPrivateParticleEffectiveRevision();
    }

    private void pollPrivateParticleEffectiveRevision() {
        if (privateParticlePendingRevision <= 0L) {
            return;
        }
        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        if (statusJson != null
            && "applied".equals(statusJson.optString("status", ""))
            && statusJson.optLong("candidate_revision", 0L) == privateParticlePendingRevision
            && statusJson.optLong("effective_revision", 0L) == privateParticlePendingRevision) {
            privateParticlePendingRevision = 0L;
            cancelPendingPrivateParticleEffectReadback();
            refreshPrivateParticleDynamicsFromStatus(false);
            return;
        }
        if (statusJson != null
                && "rejected".equals(statusJson.optString("status", ""))
                && statusJson.optLong("candidate_revision", 0L) == privateParticlePendingRevision) {
            privateParticlePendingRevision = 0L;
            cancelPendingPrivateParticleEffectReadback();
            refreshPrivateParticleDynamicsFromStatus(false);
            return;
        }
        if (SystemClock.elapsedRealtime() >= privateParticlePendingReadbackDeadlineMs) {
            privateParticlePendingRevision = 0L;
            cancelPendingPrivateParticleEffectReadback();
            setStatusText("Particle edit remains queued; renderer effective receipt has not arrived yet.");
            return;
        }
        pendingPrivateParticleEffectReadback = new Runnable() {
            @Override
            public void run() {
                pollPrivateParticleEffectiveRevision();
            }
        };
        liveApplyHandler.postDelayed(pendingPrivateParticleEffectReadback, 100L);
    }

    private void cancelPendingPrivateParticleEffectReadback() {
        if (liveApplyHandler != null && pendingPrivateParticleEffectReadback != null) {
            liveApplyHandler.removeCallbacks(pendingPrivateParticleEffectReadback);
        }
        pendingPrivateParticleEffectReadback = null;
    }

    private JSONObject buildPrivateParticleDynamicsPatchJson(Set<String> patchFields)
        throws Exception {
        if (patchFields == null || patchFields.isEmpty()) {
            throw new IllegalArgumentException("particle field patch must not be empty");
        }
        JSONObject privateParticles = new JSONObject();
        JSONObject size = new JSONObject();
        JSONObject driverValues = new JSONObject();
        JSONArray driverControls = new JSONArray();
        JSONObject tracer = new JSONObject();
        for (String field : patchFields) {
            if ("visual_scale".equals(field)) {
                privateParticles.put("visual_scale", privateParticleVisualScale.value());
            } else if ("world_anchor_scale_m".equals(field)) {
                privateParticles.put(
                    "world_anchor_scale_m",
                    privateParticleWorldAnchorScale.value()
                );
            } else if ("size.mode".equals(field)) {
                size.put("enabled", true);
                size.put("mode", privateParticleSizeModeWire());
            } else if ("size.world_meters".equals(field)) {
                size.put("enabled", true);
                size.put("mode", privateParticleSizeModeWire());
                size.put("world_meters", privateParticleSizeWorldMeters.value());
            } else if ("size.sphere_radius_percent".equals(field)) {
                size.put("enabled", true);
                size.put("mode", privateParticleSizeModeWire());
                size.put(
                    "sphere_radius_percent",
                    privateParticleSizeSpherePercent.value()
                );
            } else if ("size.oscillation_percent".equals(field)) {
                size.put("enabled", true);
                size.put("mode", privateParticleSizeModeWire());
                size.put(
                    "oscillation_percent",
                    privateParticleSizeOscillationPercent.value()
                );
            } else if (field.startsWith("driver_values01.")) {
                int index = Integer.parseInt(field.substring("driver_values01.".length()));
                if (index < 0 || index >= privateParticleDrivers.length) {
                    throw new IllegalArgumentException("particle driver patch index out of range");
                }
                driverValues.put(Integer.toString(index), privateParticleDrivers[index].value());
                driverControls.put(
                    new JSONObject()
                        .put("target_slot", index)
                        .put("mode", "direct")
                );
            } else if ("tracer.draw_slots_per_oscillator".equals(field)) {
                tracer.put(
                    "draw_slots_per_oscillator",
                    privateParticleTracerDrawSlots.intValue()
                );
            } else if ("tracer.lifetime_seconds".equals(field)) {
                tracer.put("lifetime_seconds", privateParticleTracerLifetime.value());
            } else if ("tracer.copies_per_second".equals(field)) {
                tracer.put("copies_per_second", privateParticleTracerCopies.value());
            } else if ("material".equals(field)) {
                privateParticles.put(
                    "material",
                    new JSONObject().put("preset", privateParticleMaterialPresetWire())
                );
            } else if ("heartbeat_pulse".equals(field)) {
                privateParticles.put(
                    "heartbeat_pulse",
                    new JSONObject().put(
                        "mode",
                        privateParticlePolarRrOrbitBoost != null
                                && privateParticlePolarRrOrbitBoost.isChecked()
                            ? "polar-rr-orbit-boost"
                            : "disabled"
                    )
                );
            } else {
                throw new IllegalArgumentException("unsupported particle patch field: " + field);
            }
        }
        if (size.length() > 0) {
            privateParticles.put("size", size);
        }
        if (driverValues.length() > 0) {
            privateParticles.put("driver_values01", driverValues);
            privateParticles.put("driver_controls", driverControls);
        }
        if (tracer.length() > 0) {
            privateParticles.put("tracer", tracer);
        }
        JSONObject source = new JSONObject()
            .put("surface", "same_apk_panel")
            .put("transport", "jni_live_queue");
        JSONObject apply = new JSONObject()
            .put("mode", "apply-on-next-safe-frame")
            .put("expected_effective_revision", -1);
        return new JSONObject()
            .put("schema", PRIVATE_PARTICLE_DYNAMICS_SCHEMA)
            .put("profile_id", "same-apk-private-particle-field-patch")
            .put("revision", System.currentTimeMillis())
            .put("update_mode", "field-patch")
            .put("source", source)
            .put("private_particles", privateParticles)
            .put("apply", apply);
    }

    private String privateParticleSizeModeWire() {
        return privateParticleSizeMode != null
                && privateParticleSizeMode.getSelectedItemPosition() == 1
            ? "world-meters"
            : "sphere-radius-percent";
    }

    private JSONObject buildPrivateParticleDynamicsJson() throws Exception {
        double[] drivers = new double[privateParticleDrivers.length];
        for (int i = 0; i < privateParticleDrivers.length; i++) {
            drivers[i] = privateParticleDrivers[i].value();
        }
        JSONObject candidate = buildPrivateParticleDynamicsJsonFromValues(
            "same-apk-private-particle-dynamics",
            "same_apk_panel",
            privateParticleVisualScale.value(),
            privateParticleWorldAnchorScale.value(),
            drivers,
            privateParticleTracerDrawSlots.intValue(),
            privateParticleTracerLifetime.value(),
            privateParticleTracerCopies.value()
        );
        if (privateParticleMaterialPreset != null) {
            JSONObject privateParticles = candidate.getJSONObject("private_particles");
            privateParticles.put(
                "material",
                new JSONObject().put("preset", privateParticleMaterialPresetWire())
            );
            privateParticles.put(
                "heartbeat_pulse",
                new JSONObject().put(
                    "mode",
                    privateParticlePolarRrOrbitBoost != null && privateParticlePolarRrOrbitBoost.isChecked()
                        ? "polar-rr-orbit-boost"
                        : "disabled"
                )
            );
        }
        return candidate;
    }

    private String privateParticleMaterialPresetWire() {
        if (privateParticleMaterialPreset == null) {
            return "packaged-default";
        }
        switch (privateParticleMaterialPreset.getSelectedItemPosition()) {
            case 1:
                return "current-additive";
            case 2:
                return "premultiplied-alpha-over";
            case 3:
                return "premultiplied-alpha-over-depth";
            case 4:
                return "premultiplied-alpha-over-depth-facing";
            case 5:
                return "reference-material-emulation";
            default:
                return "packaged-default";
        }
    }

    private int privateParticleMaterialPresetIndex(String preset) {
        if ("current-additive".equals(preset)) {
            return 1;
        }
        if ("premultiplied-alpha-over".equals(preset)) {
            return 2;
        }
        if ("premultiplied-alpha-over-depth".equals(preset)) {
            return 3;
        }
        if ("premultiplied-alpha-over-depth-facing".equals(preset)) {
            return 4;
        }
        if ("reference-material-emulation".equals(preset)) {
            return 5;
        }
        return 0;
    }

    private JSONObject buildPrivateParticleDynamicsJsonFromValues(
        String profileId,
        String surface,
        double visualScale,
        double worldAnchorScale,
        double[] driverValues01,
        int tracerDrawSlotsPerOscillator,
        double tracerLifetimeSeconds,
        double tracerCopiesPerSecond
    ) throws Exception {
        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        JSONObject transparencyStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("transparency");
        JSONObject colorStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("color");
        return buildPrivateParticleDynamicsJsonFromValues(
            profileId,
            surface,
            visualScale,
            worldAnchorScale,
            driverValues01,
            tracerDrawSlotsPerOscillator,
            tracerLifetimeSeconds,
            tracerCopiesPerSecond,
            readNestedPrivateParticleStatusDouble(
                transparencyStatus,
                "opacity",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRANSPARENCY_OPACITY, 1.0)
            ),
            readNestedPrivateParticleStatusDouble(
                transparencyStatus,
                "output_alpha_scale",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRANSPARENCY_OUTPUT_ALPHA_SCALE, 1.0)
            ),
            readNestedPrivateParticleStatusDouble(
                transparencyStatus,
                "depth_suppression_strength",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRANSPARENCY_DEPTH_SUPPRESSION, 0.0)
            ),
            readNestedPrivateParticleStatusDouble(
                transparencyStatus,
                "rgb_alpha_coupling",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRANSPARENCY_RGB_ALPHA_COUPLING, 1.0)
            ),
            readNestedPrivateParticleStatusDouble(
                colorStatus,
                "facing_attenuation_strength",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION, 0.0)
            )
        );
    }

    private JSONObject buildPrivateParticleDynamicsJsonFromValues(
        String profileId,
        String surface,
        double visualScale,
        double worldAnchorScale,
        double[] driverValues01,
        int tracerDrawSlotsPerOscillator,
        double tracerLifetimeSeconds,
        double tracerCopiesPerSecond,
        double transparencyOpacity,
        double transparencyOutputAlphaScale,
        double transparencyDepthSuppressionStrength,
        double transparencyRgbAlphaCoupling,
        double colorFacingAttenuationStrength
    ) throws Exception {
        JSONObject source = new JSONObject()
            .put("surface", surface)
            .put("transport", "jni_live_queue");
        JSONArray drivers = new JSONArray();
        for (int i = 0; i < driverValues01.length; i++) {
            drivers.put(driverValues01[i]);
        }
        JSONObject tracer = new JSONObject()
            .put("draw_slots_per_oscillator", tracerDrawSlotsPerOscillator)
            .put("lifetime_seconds", tracerLifetimeSeconds)
            .put("copies_per_second", tracerCopiesPerSecond);
        JSONObject transparency = new JSONObject()
            .put("opacity", transparencyOpacity)
            .put("output_alpha_scale", transparencyOutputAlphaScale)
            .put("depth_suppression_strength", transparencyDepthSuppressionStrength)
            .put("rgb_alpha_coupling", transparencyRgbAlphaCoupling);
        JSONObject color = new JSONObject()
            .put("facing_attenuation_strength", colorFacingAttenuationStrength);
        JSONObject size = new JSONObject()
            .put("enabled", privateParticleSizeOverrideEnabled)
            .put("mode", privateParticleSizeModeWire())
            .put("world_meters", privateParticleSizeWorldMeters.value())
            .put("sphere_radius_percent", privateParticleSizeSpherePercent.value())
            .put("oscillation_percent", privateParticleSizeOscillationPercent.value());
        JSONObject privateParticles = new JSONObject()
            .put("visual_scale", visualScale)
            .put("size", size)
            .put("world_anchor_scale_m", worldAnchorScale)
            .put("driver_values01", drivers)
            .put("tracer", tracer)
            .put("transparency", transparency)
            .put("color", color);
        JSONObject apply = new JSONObject()
            .put("mode", "apply-on-next-safe-frame")
            .put("expected_effective_revision", -1);
        return new JSONObject()
            .put("schema", PRIVATE_PARTICLE_DYNAMICS_SCHEMA)
            .put("profile_id", profileId)
            .put("revision", System.currentTimeMillis())
            .put("source", source)
            .put("private_particles", privateParticles)
            .put("apply", apply);
    }

    private void refreshPrivateParticleDynamicsFromStatus(boolean userVisible) {
        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        if (privateParticles == null) {
            String ownerStatus = statusJson == null
                ? "unavailable"
                : statusJson.optString("status", "unknown");
            if (privateParticleEffectiveReadback != null) {
                if ("rejected".equals(ownerStatus)) {
                    privateParticleEffectiveReadback.setText(
                        "Request rejected by consuming runtime (not effective)."
                    );
                } else {
                    privateParticleEffectiveReadback.setText(
                        "Native-effective readback pending; owner has not applied an exact revision."
                    );
                }
            }
            if (userVisible) {
                updateStatus("Particle dynamics effective status is " + ownerStatus + ".");
            } else {
                setStatusText("Particle dynamics effective status is " + ownerStatus + ".");
            }
            return;
        }
        JSONObject materialStatus = privateParticles.optJSONObject("material");
        JSONObject heartbeatStatus = privateParticles.optJSONObject("heartbeat_pulse");
        JSONObject sizeStatus = privateParticles.optJSONObject("size");
        boolean hydrateControls = isCurrentPrivateParticleControlSurface(privateParticleControlEpoch);
        if (hydrateControls) {
            boolean wasHydrating = privateParticleControlsHydrating;
            privateParticleControlsHydrating = true;
            try {
                if (privateParticleVisualScale != null) {
                    setSliderValue(
                        privateParticleVisualScale,
                        readPrivateParticleStatusDouble(
                            privateParticles,
                            "visual_scale",
                            privateParticleVisualScale.value()
                        )
                    );
                    setSliderValue(
                        privateParticleWorldAnchorScale,
                        readPrivateParticleStatusDouble(
                            privateParticles,
                            "world_anchor_scale_m",
                            privateParticleWorldAnchorScale.value()
                        )
                    );
                    if (sizeStatus != null) {
                        privateParticleSizeOverrideEnabled = sizeStatus.optBoolean("enabled", false);
                        int sizeModeSelection = "world-meters".equals(
                            sizeStatus.optString("mode", "sphere-radius-percent")
                        ) ? 1 : 0;
                        privateParticleSizeModeSelection = sizeModeSelection;
                        if (privateParticleSizeMode != null
                                && privateParticleSizeMode.getSelectedItemPosition()
                                    != sizeModeSelection) {
                            privateParticleSizeMode.setSelection(sizeModeSelection);
                        }
                        setSliderValue(
                            privateParticleSizeWorldMeters,
                            sizeStatus.optDouble(
                                "world_meters",
                                privateParticleSizeWorldMeters.value()
                            )
                        );
                        setSliderValue(
                            privateParticleSizeSpherePercent,
                            sizeStatus.optDouble(
                                "sphere_radius_percent",
                                privateParticleSizeSpherePercent.value()
                            )
                        );
                        setSliderValue(
                            privateParticleSizeOscillationPercent,
                            sizeStatus.optDouble(
                                "oscillation_percent",
                                privateParticleSizeOscillationPercent.value()
                            )
                        );
                        updatePrivateParticleSizeModeVisibility();
                    }
                    JSONArray driverStatus = privateParticles.optJSONArray("driver_values01");
                    if (driverStatus != null) {
                        for (int i = 0; i < privateParticleDrivers.length; i++) {
                            setSliderValue(
                                privateParticleDrivers[i],
                                driverStatus.optDouble(i, privateParticleDrivers[i].value())
                            );
                        }
                    }
                    JSONObject tracerStatus = privateParticles.optJSONObject("tracer");
                    if (tracerStatus != null) {
                        setSliderValue(
                            privateParticleTracerDrawSlots,
                            readPrivateParticleStatusTracerDouble(
                                tracerStatus,
                                "draw_slots_per_oscillator",
                                privateParticleTracerDrawSlots.value()
                            )
                        );
                        setSliderValue(
                            privateParticleTracerLifetime,
                            readPrivateParticleStatusTracerDouble(
                                tracerStatus,
                                "lifetime_seconds",
                                privateParticleTracerLifetime.value()
                            )
                        );
                        setSliderValue(
                            privateParticleTracerCopies,
                            readPrivateParticleStatusTracerDouble(
                                tracerStatus,
                                "copies_per_second",
                                privateParticleTracerCopies.value()
                            )
                        );
                    }
                    if (privateParticleMaterialPreset != null) {
                        setPrivateParticleMaterialSelectionFromRuntime(
                            privateParticleMaterialPresetIndex(
                                materialStatus == null
                                    ? "packaged-default"
                                    : materialStatus.optString("preset", "packaged-default")
                            )
                        );
                    }
                    if (privateParticlePolarRrOrbitBoost != null) {
                        privateParticlePolarRrOrbitBoost.setChecked(
                            heartbeatStatus != null
                                && "polar-rr-orbit-boost".equals(
                                    heartbeatStatus.optString("mode", "disabled")
                                )
                        );
                    }
                }
            } finally {
                privateParticleControlsHydrating = wasHydrating;
            }
        }
        if (privateParticleEffectiveReadback != null) {
            String material = materialStatus == null
                ? "packaged-default"
                : materialStatus.optString("preset", "packaged-default");
            String heartbeat = heartbeatStatus == null
                ? "disabled"
                : heartbeatStatus.optString("mode", "disabled");
            String effectiveSize = sizeStatus == null
                ? "legacy payload envelope"
                : sizeStatus.optString("mode", "legacy-payload-envelope")
                    + " · base "
                    + String.format(
                        Locale.US,
                        "%.3f",
                        "world-meters".equals(sizeStatus.optString("mode", ""))
                            ? sizeStatus.optDouble("world_meters", 0.0)
                            : sizeStatus.optDouble("sphere_radius_percent", 0.0)
                    )
                    + ("world-meters".equals(sizeStatus.optString("mode", "")) ? " m" : "%")
                    + " · oscillation "
                    + String.format(
                        Locale.US,
                        "%.1f%%",
                        sizeStatus.optDouble("oscillation_percent", 0.0)
                    )
                    + " · range "
                    + String.format(
                        Locale.US,
                        "%.3f–%.3f",
                        sizeStatus.optDouble("derived_min", 0.0),
                        sizeStatus.optDouble("derived_max", 0.0)
                    )
                    + ("world-meters".equals(sizeStatus.optString("mode", "")) ? " m" : "%");
            privateParticleEffectiveReadback.setText(
                "Effective: size " + effectiveSize
                    + " · material " + material
                    + " · RR orbit boost " + heartbeat
                    + " · revision " + statusJson.optLong("effective_revision", 0L)
                    + " · status " + statusJson.optString("status", "unknown")
            );
        }
        String message = "Particle dynamics refreshed: " + privateParticleDynamicsSummary() + ".";
        if (userVisible) {
            updateStatus(message);
        } else {
            setStatusText(message);
        }
    }

    private JSONObject readPrivateParticleDynamicsStatusJson() {
        try {
            String text = readFile(PRIVATE_PARTICLE_DYNAMICS_STATUS_FILE);
            if (text.length() == 0) {
                return null;
            }
            JSONObject statusJson = new JSONObject(text);
            if (!PRIVATE_PARTICLE_DYNAMICS_STATUS_SCHEMA.equals(
                    statusJson.optString("schema", ""))) {
                return null;
            }
            return statusJson;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject privateParticleStatusBody(JSONObject statusJson) {
        if (!privateParticleStatusIsEffective(statusJson)) {
            return null;
        }
        return statusJson.optJSONObject("private_particles");
    }

    private boolean privateParticleStatusIsEffective(JSONObject statusJson) {
        if (statusJson == null || !"applied".equals(statusJson.optString("status", ""))) {
            return false;
        }
        long candidateRevision = statusJson.optLong("candidate_revision", 0L);
        long effectiveRevision = statusJson.optLong("effective_revision", 0L);
        return candidateRevision > 0L && candidateRevision == effectiveRevision;
    }

    private double readPrivateParticleStatusDouble(
        JSONObject privateParticles,
        String key,
        double fallback
    ) {
        if (privateParticles == null) {
            return fallback;
        }
        return privateParticles.optDouble(key, fallback);
    }

    private double readPrivateParticleStatusTracerDouble(
        JSONObject tracerStatus,
        String key,
        double fallback
    ) {
        if (tracerStatus == null) {
            return fallback;
        }
        return tracerStatus.optDouble(key, fallback);
    }

    private double readNestedPrivateParticleStatusDouble(
        JSONObject nestedStatus,
        String key,
        double fallback
    ) {
        if (nestedStatus == null) {
            return fallback;
        }
        return nestedStatus.optDouble(key, fallback);
    }

    private double privateParticleDriverValueFromStatusOrProperty(
        JSONObject privateParticles,
        int index,
        double fallback
    ) {
        JSONArray driverStatus = privateParticles == null
            ? null
            : privateParticles.optJSONArray("driver_values01");
        if (driverStatus != null && index >= 0 && index < driverStatus.length()) {
            return driverStatus.optDouble(index, fallback);
        }
        if (index >= 0 && index < PROP_PRIVATE_PARTICLE_DRIVERS.length) {
            return readDoubleProperty(PROP_PRIVATE_PARTICLE_DRIVERS[index], fallback);
        }
        return fallback;
    }

    private double[] privateParticleDriverValuesFromStatusOrProperties(JSONObject privateParticles) {
        double[] drivers = new double[PROP_PRIVATE_PARTICLE_DRIVERS.length];
        for (int i = 0; i < drivers.length; i++) {
            drivers[i] = privateParticleDriverValueFromStatusOrProperty(
                privateParticles,
                i,
                privateParticleDriverDefaultValue(i)
            );
        }
        return drivers;
    }

    private double privateParticleDriverDefaultValue(int index) {
        return index == 0 || index == 1 ? 1.0 : 0.0;
    }

    private void setSliderValue(SliderControl slider, double value) {
        if (slider != null) {
            slider.setValue(value);
        }
    }

    private String privateParticleDynamicsSummary() {
        if (privateParticleWorldAnchorScale == null
            || privateParticleSizeMode == null
            || privateParticleSizeWorldMeters == null
            || privateParticleSizeSpherePercent == null
            || privateParticleSizeOscillationPercent == null
            || privateParticleDrivers.length < 2
            || privateParticleDrivers[0] == null
            || privateParticleDrivers[1] == null
            || privateParticleTracerDrawSlots == null) {
            return "renderer effective state";
        }
        boolean meters = privateParticleSizeMode.getSelectedItemPosition() == 1;
        String size = meters
            ? String.format(Locale.US, "%.3f m", privateParticleSizeWorldMeters.value())
            : String.format(Locale.US, "%.2f%% radius", privateParticleSizeSpherePercent.value());
        return String.format(
            Locale.US,
            "sphere %.2f m, particle %s, oscillation %.1f%%, d0 %.2f, d1 %.2f, tracers %d",
            privateParticleWorldAnchorScale.value(),
            size,
            privateParticleSizeOscillationPercent.value(),
            privateParticleDrivers[0].value(),
            privateParticleDrivers[1].value(),
            privateParticleTracerDrawSlots.intValue()
        );
    }




    private LinearLayout.LayoutParams rowButtonParams() {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }








    private void handleDisplayCompositeIntent(Intent intent) {
        if (intent == null || !ACTION_REQUEST_DISPLAY_COMPOSITE_CAPTURE.equals(intent.getAction())) {
            return;
        }
        String token = intent.getAction() + ":" + intent.getLongExtra("display_composite_request_token", 0L);
        if (token.equals(handledDisplayCompositeIntentToken) || displayCompositeRequestInFlight) {
            return;
        }
        handledDisplayCompositeIntentToken = token;
        displayCompositeRequestInFlight = true;
        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            displayCompositeRequestInFlight = false;
            setStatusText("MediaProjectionManager is unavailable.");
            return;
        }
        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_DISPLAY_COMPOSITE_CAPTURE
        );
        setStatusText("Display composite capture request launched.");
    }

    private void handlePolarSensorPanelCommandIntent(Intent intent) {
        if (intent == null || !ACTION_POLAR_SENSOR_PANEL_COMMAND.equals(intent.getAction())) {
            return;
        }
        String token = intent.getStringExtra(EXTRA_POLAR_SENSOR_PANEL_COMMAND_TOKEN);
        if (token == null || token.length() == 0) {
            token = intent.toUri(0);
        }
        if (token.equals(handledPolarSensorPanelCommandToken)) {
            return;
        }
        handledPolarSensorPanelCommandToken = token;
        ensurePolarSensorPanel();
        String command = intent.getStringExtra(EXTRA_POLAR_SENSOR_PANEL_COMMAND);
        PolarSensorRuntime.forApplication(getApplicationContext()).dispatchFromPanel(command, token);
    }

    private void writePolarSensorOperatorReceipt(
        String token,
        String command,
        String dispatchStatus,
        String reasonCode
    ) {
        try {
            JSONObject receipt = new JSONObject()
                .put("schema", "rusty.quest.native_renderer.polar_sensor_operator_status.v1")
                .put("token", token == null ? "" : token)
                .put("command", command == null ? "" : command)
                .put("dispatch_status", dispatchStatus == null ? "unknown" : dispatchStatus)
                .put("reason_code", reasonCode == null ? "unknown" : reasonCode)
                .put("updated_at_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos());
            try {
                String statusText = readFile(POLAR_SENSOR_STATUS_FILE);
                if (statusText.length() > 0) {
                    receipt.put("polar_status", new JSONObject(statusText));
                }
            } catch (Exception ignored) {
                receipt.put("polar_status", JSONObject.NULL);
            }
            writeFile(POLAR_SENSOR_OPERATOR_STATUS_FILE, receipt.toString(2));
        } catch (Exception error) {
            Log.i(
                TAG,
                MARKER_PREFIX + " channel=polar-sensor-operator status=receipt-write-failed reason="
                    + markerToken(error.getMessage())
            );
        }
    }

    private void writePolarSensorOperatorReceipt(
        String token,
        PolarSensorPanel.OperatorCommandStatus commandStatus
    ) {
        try {
            JSONObject receipt = new JSONObject()
                .put("schema", "rusty.quest.native_renderer.polar_sensor_operator_status.v1")
                .put("token", token == null ? "" : token)
                .put("command", commandStatus.command == null ? "" : commandStatus.command)
                .put("dispatch_status", commandStatus.dispatchStatus)
                .put("reason_code", commandStatus.reasonCode)
                .put("effect_status", commandStatus.effectStatus)
                .put("operation_generation", commandStatus.operationGeneration)
                .put("capture_session_id", commandStatus.captureSessionId)
                .put("updated_at_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos());
            if (commandStatus.freshPolarStatus != null) {
                receipt.put("polar_status", commandStatus.freshPolarStatus);
            } else {
                receipt.put("polar_status", JSONObject.NULL);
            }
            writeFile(POLAR_SENSOR_OPERATOR_STATUS_FILE, receipt.toString(2));
        } catch (Exception error) {
            Log.i(
                TAG,
                MARKER_PREFIX + " channel=polar-sensor-operator status=receipt-write-failed reason="
                    + markerToken(error.getMessage())
            );
        }
    }

    private void handleBreathCompositionCommandIntent(Intent intent) {
        if (intent == null || !ACTION_BREATH_COMPOSITION_PANEL_COMMAND.equals(intent.getAction())) {
            return;
        }
        String token = intent.getStringExtra(EXTRA_BREATH_COMPOSITION_COMMAND_TOKEN);
        if (token == null || token.length() == 0) {
            token = intent.toUri(0);
        }
        if (token.equals(handledBreathCompositionCommandToken)) {
            return;
        }
        handledBreathCompositionCommandToken = token;
        if (!"breath-mapping".equals(readControlPanelMode())) {
            setStatusText("Breath command ignored; breath-mapping panel mode is not active.");
            breathOperatorMarker(
                "status=rejected reason=panel-mode-inactive token=" + markerToken(token)
            );
            return;
        }
        String operation = markerToken(
            intent.getStringExtra(EXTRA_BREATH_COMPOSITION_OPERATION)
        ).toLowerCase(Locale.US);
        try {
            JSONObject command = new JSONObject()
                .put("schema", BREATH_COMPOSITION_COMMAND_SCHEMA)
                .put("operation", operation);
            if ("select".equals(operation)) {
                command
                    .put("source", intent.getStringExtra(EXTRA_BREATH_COMPOSITION_SOURCE))
                    .put("mapping", intent.getStringExtra(EXTRA_BREATH_COMPOSITION_MAPPING))
                    .put(
                        "controller_projection",
                        intent.getStringExtra(EXTRA_BREATH_COMPOSITION_CONTROLLER_PROJECTION)
                    )
                    .put(
                        "polar_projection",
                        intent.getStringExtra(EXTRA_BREATH_COMPOSITION_POLAR_PROJECTION)
                    )
                    .put(
                        "inverted",
                        intent.getBooleanExtra(EXTRA_BREATH_COMPOSITION_INVERTED, false)
                    );
            } else if ("cancel".equals(operation)) {
                JSONObject statusResponse = new JSONObject(nativeReadBreathCompositionStatus());
                JSONObject snapshot = statusResponse.optJSONObject("snapshot");
                long generation = snapshot == null ? 0L : snapshot.optLong("generation", 0L);
                if (generation <= 0L) {
                    throw new IllegalStateException("no-effective-generation");
                }
                command.put("generation", generation);
            } else if (!"start_calibration".equals(operation)
                    && !"reset".equals(operation)
                    && !"disable".equals(operation)
                    && !"status".equals(operation)) {
                throw new IllegalArgumentException("unsupported-operation");
            }
            String response = "status".equals(operation)
                ? nativeReadBreathCompositionStatus()
                : nativeApplyBreathCompositionCommand(command.toString());
            renderBreathCompositionResponse(response, breathDiagnosticsReadback);
            writeBreathCompositionOperatorReceipt(token, operation, response);
            JSONObject responseObject = new JSONObject(response);
            breathOperatorMarker(
                "status=" + markerToken(responseObject.optString("command_status", "unknown"))
                    + " reason=" + markerToken(responseObject.optString("reason_code", "none"))
                    + " operation=" + markerToken(operation)
                    + " token=" + markerToken(token)
                    + " structuredReadback=true screenshotRequired=false"
            );
            if (intent.getBooleanExtra(EXTRA_BREATH_COMPOSITION_RETURN_TO_IMMERSIVE, false)) {
                launchImmersiveRenderer();
            }
        } catch (Exception error) {
            String response = "{\"schema\":\"rusty.quest.breath_composition.response.v1\","
                + "\"command_status\":\"rejected\",\"reason_code\":\"operator-command-error\"}";
            writeBreathCompositionOperatorReceipt(token, operation, response);
            breathOperatorMarker(
                "status=rejected reason=operator-command-error operation="
                    + markerToken(operation) + " token=" + markerToken(token)
                    + " detail=" + markerToken(error.getMessage())
            );
        }
    }

    private void writeBreathCompositionOperatorReceipt(
        String token,
        String operation,
        String responseJson
    ) {
        try {
            JSONObject receipt = new JSONObject()
                .put("schema", "rusty.quest.native_renderer.breath_operator_status.v1")
                .put("token", token == null ? "" : token)
                .put("operation", operation == null ? "" : operation)
                .put("response", new JSONObject(responseJson == null ? "{}" : responseJson))
                .put("updated_at_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos());
            writeFile(BREATH_COMPOSITION_OPERATOR_STATUS_FILE, receipt.toString(2));
        } catch (Exception error) {
            breathOperatorMarker(
                "status=receipt-write-failed reason=" + markerToken(error.getMessage())
            );
        }
    }

    private void launchImmersiveRenderer() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(getPackageName(), "android.app.NativeActivity"));
        intent.addCategory("com.oculus.intent.category.VR");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void closePanelAndReturnToImmersive() {
        ExperimentSessionPanelState state = EXPERIMENT_SESSION_PANEL.snapshot();
        if (state.hasActiveSession()
                && !ExperimentSessionPanelViewPolicy.canReturnToImmersive(state)) {
            updateStatus("Please wait until the current session operation completes before returning to VR.");
            refreshExperimenterAfterReadback();
            return;
        }
        ControlPanelActivity.closePanelAndReturnToImmersive(this);
    }

    private void writeFile(String name, String content) throws Exception {
        FileOutputStream out = openFileOutput(name, MODE_PRIVATE);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private String readFile(String name) throws Exception {
        FileInputStream in = openFileInput(name);
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)
            );
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } finally {
            in.close();
        }
    }

    private void updateStatus(String message) {
        setStatusText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void setStatusText(String message) {
        if (status != null) {
            status.setText(message);
        }
    }

    private static void breathOperatorMarker(String detail) {
        Log.i(
            TAG,
            MARKER_PREFIX
                + " channel=breath-operator "
                + String.valueOf(detail).replace('\n', ' ').replace('\r', ' ')
        );
    }

    private static void experimenterPanelShortcutMarker(String detail) {
        Log.i(
            TAG,
            MARKER_PREFIX
                + " channel=experiment-session-panel event=panel-toggle "
                + String.valueOf(detail).replace('\n', ' ').replace('\r', ' ')
        );
    }

    private static String markerToken(String raw) {
        if (raw == null || raw.length() == 0) {
            return "none";
        }
        return raw.replaceAll("[^A-Za-z0-9._=:/-]+", "_");
    }

    private static String emptyAs(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 13, PANEL_MUTED);
        view.setPadding(0, dp(10), 0, dp(2));
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 17, PANEL_FG);
        view.setPadding(0, dp(18), 0, dp(6));
        return view;
    }

    private LinearLayout panelCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        card.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), Color.rgb(72, 79, 91));
        background.setColor(PANEL_SURFACE);
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, dp(8));
        card.setLayoutParams(params);
        TextView heading = text(title, 17, PANEL_FG);
        heading.setPadding(0, 0, 0, dp(8));
        card.addView(heading);
        return card;
    }

    private CheckBox checkBox(String value, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(value);
        box.setTextColor(PANEL_FG);
        box.setChecked(checked);
        box.setPadding(0, dp(2), 0, dp(2));
        return box;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setAllCaps(false);
        styleButton(button, false);
        return button;
    }

    private void styleButton(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(3));
        background.setStroke(dp(selected ? 2 : 1), selected ? Color.WHITE : Color.rgb(80, 86, 98));
        background.setColor(selected ? PANEL_ACCENT : PANEL_SURFACE);
        button.setTextColor(selected ? Color.BLACK : PANEL_FG);
        button.setBackground(background);
    }

    private Spinner spinner(String[] values, int selectedIndex) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter =
            new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selectedIndex);
        return spinner;
    }

    private SliderControl slider(
        String title,
        double min,
        double max,
        double initial,
        int steps,
        String suffix,
        boolean integer
    ) {
        return slider(
            title,
            min,
            max,
            initial,
            steps,
            suffix,
            integer,
            null
        );
    }

    private SliderControl slider(
        String title,
        double min,
        double max,
        double initial,
        int steps,
        String suffix,
        boolean integer,
        Runnable onUserChange
    ) {
        return new SliderControl(title, min, max, initial, steps, suffix, integer, onUserChange);
    }

    private String selected(Spinner spinner) {
        return String.valueOf(spinner.getSelectedItem());
    }

    private int indexOf(String[] values, String requested, int fallback) {
        if (requested != null) {
            for (int i = 0; i < values.length; i++) {
                if (requested.equals(values[i])) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(values.length - 1, fallback));
    }

    private boolean readBooleanProperty(String name, boolean fallback) {
        String value = readSystemProperty(name);
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }

    private double readDoubleProperty(String name, double fallback) {
        String value = readSystemProperty(name);
        if (value == null || value.length() == 0) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private int readIntProperty(String name, int fallback, int minValue, int maxValue) {
        String value = readSystemProperty(name);
        if (value == null || value.length() == 0) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(minValue, Math.min(maxValue, parsed));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private String readSystemProperty(String name) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", name)
                .redirectErrorStream(true)
                .start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );
            String line = reader.readLine();
            int exitCode = process.waitFor();
            if (exitCode == 0 && line != null) {
                return line.trim();
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return "";
    }

    private String readControlPanelMode() {
        String packaged = NativeAppSettingsReader.readSetting(
            this,
            "native_renderer.control_panel.mode"
        );
        if ("breath-mapping".equals(packaged)) {
            return packaged;
        }
        return "breath-mapping";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** JSON adapter invoked exclusively by {@link ExperimentSessionAndroidShell}'s worker. */
    private static final class ExperimentSessionJsonCodec
            implements ExperimentSessionAndroidShell.Codec {
        @Override
        public String statusCommand() {
            try {
                return new JSONObject()
                    .put("schema", ExperimentSessionPanelCoordinator.SCHEMA)
                    .put("operation", "status")
                    .toString();
            } catch (Exception error) {
                throw new IllegalStateException("could not encode session status", error);
            }
        }

        @Override
        public String command(
                ExperimentSessionPanelCoordinator.NativeCommand command,
                boolean start,
                long elapsedRealtimeNanos,
                long utcNanos) {
            try {
                JSONObject request = new JSONObject()
                    .put("schema", ExperimentSessionPanelCoordinator.SCHEMA)
                    .put("operation", command.operation)
                    .put("operation_id", command.operationId)
                    .put("expected_generation", command.expectedGeneration)
                    .put("elapsed_realtime_ns", elapsedRealtimeNanos);
                if (start) {
                    request
                        .put("condition", command.condition)
                        .put(
                            "completion_threshold_ms",
                            ExperimentSessionPanelCoordinator.COMPLETION_THRESHOLD_MS
                        )
                        .put("breath_guidance_bias_percent",
                            command.breathGuidanceBiasPercent)
                        .put("started_at_utc_ns", utcNanos);
                }
                return request.toString();
            } catch (Exception error) {
                throw new IllegalStateException("could not encode session command", error);
            }
        }

        @Override
        public ExperimentSessionAndroidShell.SessionReadback parse(
                String responseJson,
                ExperimentSessionPanelCoordinator.NativeCommand expected,
                boolean start) {
            try {
                JSONObject response = new JSONObject(responseJson == null ? "{}" : responseJson);
                if (!"rusty.quest.experiment_session.response.v1".equals(
                        response.optString("schema", ""))) {
                    throw new IllegalArgumentException("unexpected session response schema");
                }
                String commandStatus = response.optString("command_status", "rejected");
                JSONObject projection = response.optJSONObject("readback");
                if (projection == null) {
                    throw new IllegalArgumentException("session response omitted readback");
                }
                if (!validNonNegativeLong(projection, "generation")) {
                    String unavailableReason = response.optString("reason_code", "");
                    if ("rejected".equals(commandStatus)
                            && ExperimentSessionAndroidShell.retryableReason(unavailableReason)) {
                        return new ExperimentSessionAndroidShell.SessionReadback(null,
                            commandStatus, "unavailable", "unavailable", "unavailable",
                            "not-requested", 0L, -1L, "", unavailableReason, "unknown", "", 0L, "", "");
                    }
                    throw new IllegalArgumentException("authoritative-session-generation-unavailable");
                }
                JSONObject counts = projection.optJSONObject("counts");
                JSONObject byCondition = counts == null
                    ? null : counts.optJSONObject("by_condition");
                JSONObject conditionOne = byCondition == null
                    ? null : byCondition.optJSONObject("condition-a");
                JSONObject conditionTwo = byCondition == null
                    ? null : byCondition.optJSONObject("condition-b");
                boolean countsAvailable = validNonNegativeLong(counts, "completed")
                    && validNonNegativeLong(counts, "stopped_early")
                    && validNonNegativeLong(counts, "errors");
                boolean countsInvalid = counts != null && !countsAvailable;
                boolean perConditionCounts = countsAvailable
                    && validNonNegativeLong(conditionOne, "completed")
                    && validNonNegativeLong(conditionOne, "stopped_early")
                    && validNonNegativeLong(conditionOne, "errors")
                    && validNonNegativeLong(conditionTwo, "completed")
                    && validNonNegativeLong(conditionTwo, "stopped_early")
                    && validNonNegativeLong(conditionTwo, "errors");
                String operationId = projection.optString("last_operation_id", "");
                String operationStatus = projection.optString(
                    "last_operation_status",
                    commandStatus
                );
                boolean accepted = "accepted".equals(operationStatus);
                String phase = projection.optString("phase", "");
                String routeAction = projection.optString("route_action", "none");
                JSONObject recordingResult = projection.optJSONObject("recording_result");
                String recordingStatus = recordingResult == null
                    ? "unknown" : recordingResult.optString("status", "unknown");
                boolean restartReceipt = "idle".equals(phase)
                    && "show-experimenter".equals(routeAction)
                    && operationId.equals(projection.optString("finalized_operation_id", ""))
                    && projection.optLong("finalized_session_generation", -1L)
                        == projection.optLong("generation", -2L);
                if (restartReceipt && !"saved".equals(recordingStatus)) accepted = false;
                ExperimentSessionPanelState before = EXPERIMENT_SESSION_PANEL.snapshot();
                boolean startingOperation = start && expected != null
                    && expected.operationId.equals(operationId);
                if (startingOperation && "active".equals(phase)
                        && (!projection.optBoolean("profile_identity_bound", false)
                            || !projection.optBoolean("audio_identity_bound", false))) {
                    accepted = false;
                }
                JSONObject guidanceProjection = projection.optJSONObject("breath_guidance");
                if (startingOperation && expected.breathGuidanceBiasPercent > 0
                        && (guidanceProjection == null
                            || !guidanceProjection.optBoolean("identity_bound", false)
                            || guidanceProjection.optInt("bias_percent", -1)
                                != expected.breathGuidanceBiasPercent)) {
                    accepted = false;
                }
                boolean durable = accepted && restartReceipt
                    && "saved".equals(recordingStatus);
                String responseReason = response.optString("reason_code", "");
                String projectionReason = projection.optString("last_reason", "");
                String reason = restartReceipt && !"saved".equals(recordingStatus)
                    ? "recording-not-saved" : !projectionReason.isEmpty() && !"none".equals(projectionReason)
                    ? projectionReason : responseReason;
                ExperimentSessionPanelCoordinator.NativeReceipt receipt =
                    new ExperimentSessionPanelCoordinator.NativeReceipt(
                        operationId,
                        accepted,
                        durable,
                        projection.optLong("generation", 0L),
                        projection.optLong("revision", 0L),
                        phase,
                        projection.optBoolean("audio_technical_hold", false)
                            ? "audio-error" : projection.optString("control_state", ""),
                        before.activeCondition,
                        "active".equals(phase),
                        projection.optString("completion", ""),
                        projection.optLong("active_time_ms", 0L),
                        "running".equals(projection.optString("recovery_status", "not-run"))
                            || "error".equals(
                                projection.optString("recovery_status", "not-run")),
                        projection.optString("storage_status", "unavailable"),
                        projection.optBoolean("kiosk_requested", true),
                        conditionOne == null ? 0L : conditionOne.optLong("completed", 0L),
                        conditionTwo == null ? 0L : conditionTwo.optLong("completed", 0L),
                        conditionOne == null
                            ? 0L : conditionOne.optLong("stopped_early", 0L),
                        conditionTwo == null
                            ? 0L : conditionTwo.optLong("stopped_early", 0L),
                        perConditionCounts,
                        countsAvailable,
                        countsInvalid,
                        counts == null ? 0L : counts.optLong("completed", 0L),
                        counts == null ? 0L : counts.optLong("stopped_early", 0L),
                        counts == null ? 0L : counts.optLong("errors", 0L),
                        routeAction,
                        projection.optLong("route_action_revision", 0L),
                        projection.optBoolean("audio_technical_hold", false)
                            ? "audio-technical-hold"
                            : startingOperation && !accepted
                            ? "Condition was not armed: " + emptySessionReason(reason) + "."
                            : reason
                                + " · recovery="
                                + projection.optString("recovery_status", "unavailable")
                                + " · kiosk="
                                + projection.optString("kiosk_effective", "unknown")
                                + "/"
                                + projection.optString(
                                    "kiosk_enforcement",
                                    "not-enforced")
                    );
                return new ExperimentSessionAndroidShell.SessionReadback(
                    receipt,
                    commandStatus,
                    projection.optString("initialization_status", "unavailable"),
                    projection.optString("inventory_status", "inventory-unavailable"),
                    projection.optString("recording_root_status", "unavailable"),
                    projection.optString("shutdown_status", "not-requested"),
                    projection.optLong("shutdown_ack_revision", 0L),
                    projection.optLong("finalized_session_generation", 0L),
                    projection.optString("finalized_operation_id", ""),
                    reason,
                    recordingStatus,
                    recordingResult == null ? "" : recordingResult.optString("error", ""),
                    projection.optLong("runtime_epoch", 0L),
                    projection.optString("last_operation_status", ""),
                    projection.optString("last_reason", "")
                );
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalArgumentException("could not parse session response", error);
            }
        }

        private static boolean validNonNegativeLong(JSONObject value, String field) {
            if (value == null || !value.has(field) || value.isNull(field)) return false;
            Object raw = value.opt(field);
            if (!(raw instanceof Number)) return false;
            Number number = (Number) raw;
            double floating = number.doubleValue();
            long integer = number.longValue();
            return !Double.isNaN(floating)
                && !Double.isInfinite(floating)
                && floating == (double) integer
                && integer >= 0L;
        }

        private static String emptySessionReason(String reason) {
            if ("profile-identity-not-packaged".equals(reason)) {
                return "the packaged study profile identity did not match";
            }
            if ("audio-identity-not-packaged".equals(reason)) {
                return "the packaged condition audio identity did not match";
            }
            if (reason == null || reason.isEmpty() || "none".equals(reason)) {
                return "native admission was rejected";
            }
            return reason;
        }
    }

    private final class SliderControl {
        final LinearLayout view;
        final TextView valueLabel;
        final SeekBar seekBar;
        final String title;
        final double min;
        final double max;
        final int steps;
        final String suffix;
        final boolean integer;
        final Runnable onUserChange;

        SliderControl(
            String title,
            double min,
            double max,
            double initial,
            int steps,
            String suffix,
            boolean integer,
            Runnable onUserChange
        ) {
            this.title = title;
            this.min = min;
            this.max = max;
            this.steps = Math.max(1, steps);
            this.suffix = suffix;
            this.integer = integer;
            this.onUserChange = onUserChange;
            this.view = new LinearLayout(BreathCompositionPanelModule.this);
            this.view.setOrientation(LinearLayout.VERTICAL);
            this.view.setPadding(0, dp(6), 0, dp(4));
            this.valueLabel = text("", 13, PANEL_FG);
            this.seekBar = new SeekBar(BreathCompositionPanelModule.this);
            this.seekBar.setMax(this.steps);
            this.seekBar.setProgress(progressFor(initial));
            this.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    refresh();
                    if (fromUser && SliderControl.this.onUserChange != null) {
                        SliderControl.this.onUserChange.run();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                }
            });
            this.view.addView(this.valueLabel);
            this.view.addView(this.seekBar);
            refresh();
        }

        double value() {
            if (Math.abs(max - min) <= 0.000001) {
                return min;
            }
            return min + (max - min) * ((double) seekBar.getProgress() / (double) steps);
        }

        int intValue() {
            return (int) Math.round(value());
        }

        void setValue(double requested) {
            seekBar.setProgress(progressFor(requested));
            refresh();
        }

        void setInteractive(boolean enabled) {
            seekBar.setEnabled(enabled);
            valueLabel.setTextColor(enabled ? PANEL_FG : PANEL_MUTED);
            view.setAlpha(enabled ? 1.0f : 0.55f);
        }

        private int progressFor(double requested) {
            if (Math.abs(max - min) <= 0.000001) {
                return 0;
            }
            double clamped = Math.max(min, Math.min(max, requested));
            return (int) Math.round(((clamped - min) / (max - min)) * steps);
        }

        private void refresh() {
            String formatted = integer
                ? String.format(Locale.US, "%d%s", intValue(), suffix)
                : String.format(Locale.US, "%.3f%s", value(), suffix);
            valueLabel.setText(title + ": " + formatted);
        }
    }

    private static String nativeSubmitLivePrivateParticleDynamics(String dynamicsJson) {
        return PrivateParticlePanelController.submitCandidate(dynamicsJson);
    }

    private static String nativeApplyBreathCompositionCommand(String commandJson) {
        return ControlPanelActivity.nativeApplyBreathCompositionCommand(commandJson);
    }

    private static String nativeApplyLslTransportCommand(String commandJson) {
        return ControlPanelActivity.nativeApplyLslTransportCommand(commandJson);
    }

    private static String nativeReadLslTransportStatus() {
        return ControlPanelActivity.nativeReadLslTransportStatus();
    }

    static String applyLslTransportCommandFromOwner(String commandJson) {
        return ControlPanelActivity.applyLslTransportCommandFromOwner(commandJson);
    }

    private static String nativeReadBreathCompositionStatus() {
        return ControlPanelActivity.nativeReadBreathCompositionStatus();
    }
}
