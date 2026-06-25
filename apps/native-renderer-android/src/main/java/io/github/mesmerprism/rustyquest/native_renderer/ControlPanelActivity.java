package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.LinearLayout;
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
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ControlPanelActivity extends Activity {
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static final String CHANNEL_KURAMOTO_PANEL = "kuramoto-mesh-panel";
    public static final String ACTION_TOGGLE_PANEL =
        "io.github.mesmerprism.rustyquest.native_renderer.action.TOGGLE_PANEL";
    public static final String ACTION_OPEN_PANEL =
        "io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_PANEL";
    public static final String ACTION_APPLY_LIVE_SELF_TEST =
        "io.github.mesmerprism.rustyquest.native_renderer.action.APPLY_LIVE_SELF_TEST";
    public static final String ACTION_REQUEST_DISPLAY_COMPOSITE_CAPTURE =
        "io.github.mesmerprism.rustyquest.native_renderer.action.REQUEST_DISPLAY_COMPOSITE_CAPTURE";
    public static final String ACTION_POLAR_SENSOR_PANEL_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.POLAR_SENSOR_PANEL_COMMAND";
    public static final String ACTION_KURAMOTO_MESH_PANEL_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.KURAMOTO_MESH_PANEL_COMMAND";
    public static final String EXTRA_POLAR_SENSOR_PANEL_COMMAND = "polar_sensor_panel_command";
    public static final String EXTRA_POLAR_SENSOR_PANEL_COMMAND_TOKEN =
        "polar_sensor_panel_command_token";
    public static final String EXTRA_KURAMOTO_SURFACE_TARGET = "kuramoto_surface_target";
    public static final String EXTRA_KURAMOTO_CONDITION = "kuramoto_condition";
    public static final String EXTRA_KURAMOTO_RETURN_TO_IMMERSIVE =
        "kuramoto_return_to_immersive";
    public static final String EXTRA_KURAMOTO_PANEL_COMMAND_TOKEN =
        "kuramoto_panel_command_token";
    private static final int REQUEST_DISPLAY_COMPOSITE_CAPTURE = 7401;
    private static final String CANDIDATE_FILE = "stimulus_volume_candidate.json";
    private static final String STATUS_FILE = "stimulus_volume_status.json";
    private static final String DEPTH_ALIGNMENT_STATUS_FILE = "depth_alignment_status.json";
    private static final String PRIVATE_PARTICLE_DYNAMICS_STATUS_FILE =
        "private_particle_dynamics_status.json";
    private static final String KURAMOTO_MESH_PANEL_STATUS_FILE =
        "kuramoto_mesh_panel_status.json";
    private static final String PROFILE_SCHEMA = "rusty.quest.stimulus_volume.profile.v1";
    private static final String PRIVATE_LAYER_SELECTION_SCHEMA =
        "rusty.quest.native_renderer.private_layer_selection.v1";
    private static final String ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA =
        "rusty.quest.native_renderer.environment_depth_alignment.v1";
    private static final String PRIVATE_PARTICLE_DYNAMICS_SCHEMA =
        "rusty.quest.native_renderer.private_particle_dynamics.v1";
    private static final String KURAMOTO_MESH_PANEL_SELECTION_SCHEMA =
        "rusty.kuramoto.mesh.native_panel_selection.v1";
    private static final String PROP_CONTROL_PANEL_MODE =
        "debug.rustyquest.native_renderer.control_panel.mode";
    private static final String PROP_PRIVATE_LAYER_OVERRIDE =
        "debug.rustyquest.native_renderer.private_layer.layer_override";
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
    private static final String PROP_ENVIRONMENT_DEPTH_ALIGNMENT_LEFT_OFFSET_X =
        "debug.rustyquest.native_renderer.environment_depth.alignment.left.offset.x.uv";
    private static final String PROP_ENVIRONMENT_DEPTH_ALIGNMENT_LEFT_OFFSET_Y =
        "debug.rustyquest.native_renderer.environment_depth.alignment.left.offset.y.uv";
    private static final String PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_X =
        "debug.rustyquest.native_renderer.environment_depth.alignment.right.offset.x.uv";
    private static final String PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_Y =
        "debug.rustyquest.native_renderer.environment_depth.alignment.right.offset.y.uv";
    private static final String PROP_ENVIRONMENT_DEPTH_ALIGNMENT_SCALE =
        "debug.rustyquest.native_renderer.environment_depth.alignment.scale";
    private static final String PROP_STIMULUS_ENABLED =
        "debug.rustyquest.native_renderer.stimulus_volume.enabled";
    private static final String PROP_STIMULUS_SAFETY_ACK =
        "debug.rustyquest.native_renderer.stimulus_volume.safety_ack";
    private static final String PROP_STIMULUS_RANDOMIZE =
        "debug.rustyquest.native_renderer.stimulus_volume.randomize.enabled";
    private static final String PROP_STIMULUS_RENDER_TARGET =
        "debug.rustyquest.native_renderer.stimulus_volume.render_target";
    private static final String PROP_STIMULUS_RAYMARCH =
        "debug.rustyquest.native_renderer.stimulus_volume.raymarch_samples";
    private static final String PROP_STIMULUS_CENTRAL_FOV =
        "debug.rustyquest.native_renderer.stimulus_volume.central_fov_fraction";
    private static final String PROP_STIMULUS_GRADIENT =
        "debug.rustyquest.native_renderer.stimulus_volume.gradient_smoothing";
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
        "Driver 1 coupling",
        "Driver 2 particle size",
        "Driver 3 depth wave",
        "Driver 4 spin",
        "Driver 5 orbit radius",
        "Driver 6 orbit angle",
        "Driver 7 animation frame"
    };
    private static final String[] KURAMOTO_SURFACE_IDS = new String[] {
        "real-hands",
        "gpu-replay-hands",
        "icosphere"
    };
    private static final String[] KURAMOTO_SURFACE_LABELS = new String[] {
        "Real hands",
        "GPU replay hands",
        "Icosphere"
    };
    private static final String[] KURAMOTO_SURFACE_TARGETS = new String[] {
        "quest-live-hand-mesh",
        "quest-recorded-gpu-hand-mesh",
        "static-icosphere-l4"
    };
    private static final String[] KURAMOTO_SOURCE_MODES = new String[] {
        "live-meta-openxr-hand-tracking",
        "recorded-replay-compact-joint-frames",
        "static-resident-surface"
    };
    private static final String[] KURAMOTO_SURFACE_RESOURCE_PLAN_IDS = new String[] {
        "kuramoto.native.quest.live-hands.1024.solid-black.resource-plan.v1",
        "kuramoto.native.quest.left.1024.solid-black.resource-plan.v1",
        "kuramoto.native.quest.icosphere-l4.solid-black.resource-plan.v1"
    };
    private static final String[] KURAMOTO_SURFACE_RUNTIME_PROFILE_PATHS = new String[] {
        "",
        "",
        "fixtures/native-gpu/quest-native-renderer-kuramoto-icosphere-l4-solid-black.profile.json"
    };
    private static final String[] KURAMOTO_CONDITION_IDS = new String[] {
        "lowLow",
        "highLow",
        "lowHigh",
        "highHigh"
    };
    private static final String[] KURAMOTO_CONDITION_LABELS = new String[] {
        "Low energy / low coherence",
        "High energy / low coherence",
        "Low energy / high coherence",
        "High energy / high coherence"
    };
    private static final String[] KURAMOTO_PROFILE_IDS = new String[] {
        "kuramoto.private.native.profile.low-energy-low-coherence.movement-only.v1",
        "kuramoto.private.native.profile.high-energy-low-coherence.movement-only.v1",
        "kuramoto.private.native.profile.low-energy-high-coherence.movement-only.v1",
        "kuramoto.private.native.profile.high-energy-high-coherence.movement-only.v1"
    };
    private static final double[] KURAMOTO_MOVEMENT_BASE_HZ = new double[] {
        0.44,
        0.88,
        0.44,
        0.88
    };
    private static final double[] KURAMOTO_MOVEMENT_SPREAD_HZ = new double[] {
        0.62,
        0.62,
        0.03,
        0.03
    };
    private static final double[] KURAMOTO_MOVEMENT_COUPLING = new double[] {
        0.0,
        0.0,
        1.0,
        1.0
    };
    private static final double[] KURAMOTO_UNIT_DISTANCE_M = new double[] {
        0.002,
        0.004,
        0.002,
        0.004
    };
    private static final String KURAMOTO_PROFILE_SET_ID =
        "kuramoto.private.native.browser-quadrants.left.1024.recorded-gpu.v1";
    private static final String KURAMOTO_DEFAULT_PROFILE_ID =
        "kuramoto.private.native.profile.low-energy-low-coherence.movement-only.v1";
    private static final String[] PRIVATE_PARTICLE_CONFIG_PAGE_LABELS = new String[] {
        "Dynamics",
        "Visuals",
        "Tracers",
        "Backend"
    };
    private static final String[] PRIVATE_PARTICLE_CURVE_CHOICES = new String[] {
        "Linear",
        "AKD hump",
        "Smoothstep",
        "Reverse linear",
        "Hold low",
        "Hold high"
    };
    private static final String PRIVATE_PARTICLE_DRIVER_MODE_MANUAL = "Manual";
    private static final String[] PRIVATE_PARTICLE_DRIVER_MODE_CHOICES = new String[] {
        "Oscillator",
        PRIVATE_PARTICLE_DRIVER_MODE_MANUAL,
        "Input slot 0: deformation",
        "Input slot 1: coupling",
        "Input slot 2: particle size",
        "Input slot 3: depth wave",
        "Input slot 4: spin speed",
        "Input slot 5: orbit radius",
        "Input slot 6: orbit angle",
        "Input slot 7: animation"
    };
    private static final int PRIVATE_PARTICLE_DRIVER_CONTROL_OSCILLATOR = 0;
    private static final int PRIVATE_PARTICLE_DRIVER_CONTROL_MANUAL = 1;
    private static final int PRIVATE_PARTICLE_DRIVER_CONTROL_INPUT_SLOT = 2;
    private static final int PRIVATE_PARTICLE_DRIVER_CONTROL_DIRECT = 3;
    private static final int PRIVATE_PARTICLE_CURVE_LINEAR = 0;
    private static final int PRIVATE_PARTICLE_CURVE_AKD_HUMP = 1;
    private static final int PRIVATE_PARTICLE_CURVE_SMOOTHSTEP = 2;
    private static final int PRIVATE_PARTICLE_CURVE_REVERSE_LINEAR = 3;
    private static final int PRIVATE_PARTICLE_CURVE_HOLD_LOW = 4;
    private static final int PRIVATE_PARTICLE_CURVE_HOLD_HIGH = 5;
    private static final int SPHERE_DEFORMATION_DRIVER_INDEX = 0;
    private static final int COUPLING_DRIVER_INDEX = 1;
    private static final int PARTICLE_SIZE_DRIVER_INDEX = 2;
    private static final int DEPTH_WAVE_DRIVER_INDEX = 3;
    private static final int SPIN_SPEED_DRIVER_INDEX = 4;
    private static final int ORBIT_RADIUS_DRIVER_INDEX = 5;
    private static final int ORBIT_ANGLE_DRIVER_INDEX = 6;
    private static final int ANIMATION_FRAME_DRIVER_INDEX = 7;
    private static final double AKD_PARTICLE_SIZE_MIN = 0.04;
    private static final double AKD_PARTICLE_SIZE_MAX = 0.115;
    private static final double DEPTH_WAVE_MIN_PERCENT = 0.0;
    private static final double DEPTH_WAVE_MAX_PERCENT = 0.1;
    private static final double AKD_SPIN_SPEED_MIN = 0.1;
    private static final double AKD_SPIN_SPEED_MAX = 0.5;
    private static final double AKD_ORBIT_RADIUS_MIN = 0.2;
    private static final double AKD_ORBIT_RADIUS_MAX = 1.5;
    private static final double AKD_ORBIT_ANGLE_MIN = 0.0;
    private static final double AKD_ORBIT_ANGLE_MAX = Math.PI * 2.0;
    private static final double AKD_SPHERE_RADIUS_MIN_M = 1.0;
    private static final double AKD_SPHERE_RADIUS_MAX_M = 2.0;
    private static final int DEPTH_WAVE_DIMENSION_INDEX = 4;
    private static final int DEPTH_WAVE_CYCLE_MULTIPLIER = 0;
    private static final String[] DEPTH_WAVE_DRIVER_POLICIES = new String[] {
        "driver3.value01 live",
        "oscillator payload rebuild",
        "unassigned fallback payload rebuild"
    };
    private static final double[] AKD_HUMP_SAMPLES01 = new double[] {
        0.000000,
        0.148741,
        0.318815,
        0.496000,
        0.666074,
        0.814815,
        0.928000,
        0.991407,
        0.991407,
        0.928000,
        0.814815,
        0.666074,
        0.496000,
        0.318815,
        0.148741,
        0.000000
    };
    private static boolean nativeBridgeLoaded;
    private static String nativeBridgeLoadError;

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

    private CheckBox safetyAck;
    private CheckBox enabledRequested;
    private CheckBox randomizeEnabled;
    private CheckBox liveAutoApply;
    private Spinner renderTarget;
    private TextView status;
    private Handler liveApplyHandler;
    private Runnable pendingLiveApply;
    private Runnable pendingDepthAlignmentApply;
    private Runnable pendingPrivateParticleDynamicsApply;
    private String handledDiagnosticIntentToken = "";
    private String handledDisplayCompositeIntentToken = "";
    private String handledPolarSensorPanelCommandToken = "";
    private String handledKuramotoMeshPanelCommandToken = "";
    private boolean displayCompositeRequestInFlight;
    private Button[] patternButtons = new Button[0];
    private Button[] mirrorButtons = new Button[0];
    private Button[] privateLayerButtons = new Button[0];
    private String selectedPatternFamily = "randomized-trevor-vocabulary";
    private String selectedMirrorMode = "none";
    private int selectedPrivateLayerIndex;
    private SliderControl minHz;
    private SliderControl maxHz;
    private SliderControl raymarchSamples;
    private SliderControl centralFovFraction;
    private SliderControl gradientSmoothing;
    private SliderControl temporalHz;
    private SliderControl oscillatorAHz;
    private SliderControl oscillatorBHz;
    private SliderControl oscillatorCHz;
    private SliderControl spatialScale;
    private SliderControl sourceShiftX;
    private SliderControl sourceShiftY;
    private SliderControl noiseScale;
    private SliderControl depthWarp;
    private SliderControl twist;
    private SliderControl pinch;
    private SliderControl scramble;
    private SliderControl jumble;
    private SliderControl stretchX;
    private SliderControl stretchY;
    private SliderControl phaseA;
    private SliderControl phaseB;
    private SliderControl phaseC;
    private SliderControl depthLeftOffsetX;
    private SliderControl depthLeftOffsetY;
    private SliderControl depthRightOffsetX;
    private SliderControl depthRightOffsetY;
    private SliderControl depthSampleScale;
    private SliderControl privateParticleVisualScale;
    private SliderControl privateParticleWorldAnchorScale;
    private SliderControl[] privateParticleDrivers = new SliderControl[8];
    private SliderControl privateParticleTracerDrawSlots;
    private SliderControl privateParticleTracerLifetime;
    private SliderControl privateParticleTracerCopies;
    private Runnable pendingPrivateParticleDepthWaveApply;
    private Runnable pendingPrivateParticleConfigApply;
    private Button[] privateParticleConfigPageButtons = new Button[0];
    private LinearLayout[] privateParticleConfigPageViews = new LinearLayout[0];
    private int privateParticleConfigPageIndex;
    private SliderControl privateParticleConfigVisualScale;
    private SliderControl privateParticleConfigWorldAnchorScale;
    private SliderControl privateParticleConfigDeformationDriver;
    private SliderControl privateParticleConfigCouplingDriver;
    private SliderControl privateParticleConfigParticleSize;
    private SliderControl privateParticleConfigDepthWavePercent;
    private SliderControl privateParticleConfigSpinSpeed;
    private SliderControl privateParticleConfigOrbitRadius;
    private SliderControl privateParticleConfigOrbitAngle;
    private SliderControl privateParticleConfigAnimationFrame;
    private SliderControl privateParticleConfigTracerDrawSlots;
    private SliderControl privateParticleConfigTracerLifetime;
    private SliderControl privateParticleConfigTracerCopies;
    private SliderControl privateParticleTransparencyOpacity;
    private SliderControl privateParticleTransparencyOutputAlphaScale;
    private SliderControl privateParticleTransparencyDepthSuppression;
    private SliderControl privateParticleTransparencyRgbAlphaCoupling;
    private SliderControl privateParticleColorFacingAttenuation;
    private TextView privateParticleConfigResolvedLabel;
    private ArrayList<ParameterEnvelopeControl> privateParticleConfigParameterControls =
        new ArrayList<ParameterEnvelopeControl>();
    private boolean privateParticleConfigViewBuilding;
    private Spinner depthWaveDriverPolicy;
    private SliderControl depthWavePercent;
    private SliderControl depthWaveDriverValue01;
    private TextView depthWaveResolvedLabel;
    private Spinner kuramotoSurfaceTarget;
    private Spinner kuramotoCondition;
    private TextView kuramotoSelectionSummary;
    private PolarSensorPanel polarSensorPanel;
    private boolean kuramotoPanelAutoApplyArmed;
    private Runnable pendingKuramotoMeshPanelApply;
    private String lastScheduledKuramotoMeshPanelApplyKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liveApplyHandler = new Handler(Looper.getMainLooper());
        setContentView(buildContentView());
        updateReadyStatusForPanelMode();
        handleDisplayCompositeIntent(getIntent());
        handleDiagnosticIntent(getIntent());
        handlePolarSensorPanelCommandIntent(getIntent());
        handleKuramotoMeshPanelCommandIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_TOGGLE_PANEL.equals(intent.getAction())) {
            closePanelAndReturnToImmersive();
        } else if (intent != null && ACTION_OPEN_PANEL.equals(intent.getAction())) {
            rebuildContentViewForCurrentMode();
            handleDisplayCompositeIntent(intent);
            handleDiagnosticIntent(intent);
            handlePolarSensorPanelCommandIntent(intent);
            handleKuramotoMeshPanelCommandIntent(intent);
        } else {
            handleDisplayCompositeIntent(intent);
            handleDiagnosticIntent(intent);
            handlePolarSensorPanelCommandIntent(intent);
            handleKuramotoMeshPanelCommandIntent(intent);
        }
    }

    private void rebuildContentViewForCurrentMode() {
        if (polarSensorPanel != null) {
            polarSensorPanel.stop();
            polarSensorPanel = null;
        }
        setContentView(buildContentView());
        updateReadyStatusForPanelMode();
    }

    private void updateReadyStatusForPanelMode() {
        String panelMode = readControlPanelMode();
        if ("private-layer-selector".equals(panelMode)) {
            updateStatus("Layer selector ready.");
        } else if ("private-particle-dynamics".equals(panelMode)) {
            updateStatus("Particle dynamics panel ready.");
        } else if ("private-particle-depth-wave".equals(panelMode)) {
            updateStatus("Depth wave panel ready.");
        } else if ("private-particle-config".equals(panelMode)) {
            updateStatus("AKD config panel ready.");
        } else if ("polar-sensor".equals(panelMode)) {
            updateStatus("Polar sensor panel ready.");
        } else if ("kuramoto-mesh".equals(panelMode)) {
            updateStatus("Kuramoto mesh panel ready.");
        } else {
            updateStatus("Panel ready. Candidate path: " + new File(getFilesDir(), CANDIDATE_FILE));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleDisplayCompositeIntent(getIntent());
        handleDiagnosticIntent(getIntent());
    }

    @Override
    protected void onDestroy() {
        if (polarSensorPanel != null) {
            polarSensorPanel.stop();
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
        if (polarSensorPanel != null) {
            polarSensorPanel.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private View buildContentView() {
        String panelMode = readControlPanelMode();
        if ("private-layer-selector".equals(panelMode)) {
            return buildPrivateLayerSelectorView();
        }
        if ("private-particle-dynamics".equals(panelMode)) {
            return buildPrivateParticleDynamicsView();
        }
        if ("private-particle-depth-wave".equals(panelMode)) {
            return buildPrivateParticleDepthWaveView();
        }
        if ("private-particle-config".equals(panelMode)) {
            return buildPrivateParticleConfigView();
        }
        if ("kuramoto-mesh".equals(panelMode)) {
            return buildKuramotoMeshPanelView();
        }
        if ("polar-sensor".equals(panelMode)) {
            return buildPolarSensorPanelPageView(false);
        }
        return buildStimulusPanelView();
    }

    private View buildStimulusPanelView() {
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
        TextView title = text("Volumetric Pattern Panel", 22, PANEL_FG);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button headerClose = button("Close");
        headerClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        header.addView(headerClose);
        root.addView(header);
        root.addView(text("App-private candidate for the native OpenXR/Vulkan renderer.", 13, PANEL_MUTED));
        root.addView(previewBand());

        safetyAck = checkBox(
            "Photosensitive-risk acknowledgement",
            readBooleanProperty(PROP_STIMULUS_SAFETY_ACK, false)
        );
        enabledRequested = checkBox(
            "Request active stimulus after launch",
            readBooleanProperty(PROP_STIMULUS_ENABLED, false)
        );
        randomizeEnabled = checkBox(
            "Enable right-primary randomize",
            readBooleanProperty(PROP_STIMULUS_RANDOMIZE, true)
        );
        View.OnClickListener liveControlListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scheduleLiveApplyFromControl();
            }
        };
        safetyAck.setOnClickListener(liveControlListener);
        enabledRequested.setOnClickListener(liveControlListener);
        randomizeEnabled.setOnClickListener(liveControlListener);
        liveAutoApply = checkBox("Live auto update", true);
        liveAutoApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (liveAutoApply.isChecked()) {
                    scheduleLiveApplyFromControl();
                } else {
                    cancelPendingLiveApply();
                    setStatusText("Live auto update off. Use Apply Live for explicit changes.");
                }
            }
        });
        root.addView(safetyAck);
        root.addView(enabledRequested);
        root.addView(randomizeEnabled);
        root.addView(liveAutoApply);

        root.addView(sectionTitle("Render"));
        String[] renderTargets = new String[] {
            "512x512x2-rgba16f",
            "768x768x2-rgba16f",
            "1024x1024x2-rgba16f"
        };
        renderTarget = spinner(
            renderTargets,
            indexOf(renderTargets, readSystemProperty(PROP_STIMULUS_RENDER_TARGET), 0)
        );
        root.addView(label("Render target"));
        root.addView(renderTarget);
        raymarchSamples = slider(
            "Raymarch samples",
            1.0,
            48.0,
            readDoubleProperty(PROP_STIMULUS_RAYMARCH, 12.0),
            47,
            "",
            true
        );
        centralFovFraction = slider(
            "Central FOV fraction",
            0.45,
            1.0,
            readDoubleProperty(PROP_STIMULUS_CENTRAL_FOV, 0.72),
            1000,
            "",
            false
        );
        gradientSmoothing = slider(
            "Gradient smoothing",
            0.0,
            1.0,
            readDoubleProperty(PROP_STIMULUS_GRADIENT, 0.78),
            1000,
            "",
            false
        );
        root.addView(raymarchSamples.view);
        root.addView(centralFovFraction.view);
        root.addView(gradientSmoothing.view);

        root.addView(sectionTitle("Pattern"));
        root.addView(buildChoiceGrid(true, new String[][] {
            {"Random", "randomized-trevor-vocabulary"},
            {"Mix", "trevor-mix"},
            {"Stripes", "stripes"},
            {"Ripples", "ripples"},
            {"Rays", "rays"},
            {"Checker", "checker"},
            {"Spiral", "spiral"},
            {"Noise", "noise-field"}
        }));

        root.addView(sectionTitle("Mirroring"));
        root.addView(buildChoiceGrid(false, new String[][] {
            {"None", "none"},
            {"Mirror X", "mirror-x"},
            {"Mirror Y", "mirror-y"},
            {"Mirror XY", "mirror-xy"},
            {"Radial", "radial-wedge"},
            {"Grid", "grid-fold"}
        }));

        root.addView(sectionTitle("Timing"));
        minHz = slider("Randomize min Hz", 3.0, 40.0, 3.0, 1000, " Hz", false);
        maxHz = slider("Randomize max Hz", 3.0, 40.0, 40.0, 1000, " Hz", false);
        temporalHz = slider("Temporal Hz", 3.0, 40.0, 3.083864, 1000, " Hz", false);
        oscillatorAHz = slider("Oscillator A", 3.0, 40.0, 6.041369, 1000, " Hz", false);
        oscillatorBHz = slider("Oscillator B", 3.0, 40.0, 35.362293, 1000, " Hz", false);
        oscillatorCHz = slider("Oscillator C", 3.0, 40.0, 37.53054, 1000, " Hz", false);
        root.addView(minHz.view);
        root.addView(maxHz.view);
        root.addView(temporalHz.view);
        root.addView(oscillatorAHz.view);
        root.addView(oscillatorBHz.view);
        root.addView(oscillatorCHz.view);

        root.addView(sectionTitle("Volume Field"));
        spatialScale = slider("Shape size", 0.35, 3.0, 0.900433, 1000, "", false);
        sourceShiftX = slider("Source shift X", -0.5, 0.5, -0.052117, 1000, "", false);
        sourceShiftY = slider("Source shift Y", -0.5, 0.5, 0.099197, 1000, "", false);
        noiseScale = slider("Noise scale", 0.0, 12.0, 6.632848, 1000, "", false);
        depthWarp = slider("Depth warp", 0.0, 0.25, 0.103063, 1000, "", false);
        root.addView(spatialScale.view);
        root.addView(sourceShiftX.view);
        root.addView(sourceShiftY.view);
        root.addView(noiseScale.view);
        root.addView(depthWarp.view);

        root.addView(sectionTitle("Warp"));
        twist = slider("Twist", -1.6, 1.6, -0.791351, 1000, "", false);
        pinch = slider("Bulge/pinch", -1.2, 1.2, -0.281597, 1000, "", false);
        scramble = slider("Scramble", 0.0, 1.0, 0.127603, 1000, "", false);
        jumble = slider("Jumble", 0.0, 1.0, 0.165175, 1000, "", false);
        stretchX = slider("Stretch X", 0.4, 2.0, 1.390104, 1000, "", false);
        stretchY = slider("Stretch Y", 0.4, 2.0, 1.071787, 1000, "", false);
        root.addView(twist.view);
        root.addView(pinch.view);
        root.addView(scramble.view);
        root.addView(jumble.view);
        root.addView(stretchX.view);
        root.addView(stretchY.view);

        root.addView(sectionTitle("Phase"));
        phaseA = slider("Phase A", 0.0, Math.PI * 2.0, 0.964848, 1000, "", false);
        phaseB = slider("Phase B", 0.0, Math.PI * 2.0, 1.612527, 1000, "", false);
        phaseC = slider("Phase C", 0.0, Math.PI * 2.0, 3.835902, 1000, "", false);
        root.addView(phaseA.view);
        root.addView(phaseB.view);
        root.addView(phaseC.view);

        root.addView(buildActionRow());

        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(10), 0, dp(8));
        root.addView(status);
        return scroll;
    }

    private View buildPrivateLayerSelectorView() {
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
        TextView title = text("Layer Selection Panel", 22, PANEL_FG);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button headerClose = button("Close");
        headerClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        header.addView(headerClose);
        root.addView(header);
        root.addView(text("Select the active private rendering layer.", 13, PANEL_MUTED));
        root.addView(privateLayerPreviewBand());
        selectedPrivateLayerIndex = readPrivateLayerOverride();

        root.addView(sectionTitle("Active Rendering"));
        root.addView(buildPrivateLayerChoiceGrid());
        root.addView(sectionTitle("Depth Alignment"));
        addDepthAlignmentControls(root);

        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        LinearLayout closeRow = new LinearLayout(this);
        closeRow.setOrientation(LinearLayout.HORIZONTAL);
        closeRow.setPadding(0, dp(14), 0, dp(10));
        closeRow.addView(close, rowButtonParams());
        root.addView(closeRow);

        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(10), 0, dp(8));
        root.addView(status);
        return scroll;
    }

    private View buildKuramotoMeshPanelView() {
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
        TextView title = text("Kuramoto Mesh Panel", 22, PANEL_FG);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button headerClose = button("Close");
        headerClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        header.addView(headerClose);
        root.addView(header);
        root.addView(buildKuramotoSuiteTabs("kuramoto"));

        String savedSurface = readKuramotoPanelSelectionString("surface_target_id", "gpu-replay-hands");
        String savedCondition = readKuramotoPanelSelectionString("condition", "lowLow");
        kuramotoPanelAutoApplyArmed = false;
        kuramotoSurfaceTarget =
            spinner(KURAMOTO_SURFACE_LABELS, indexOf(KURAMOTO_SURFACE_IDS, savedSurface, 1));
        kuramotoCondition =
            spinner(KURAMOTO_CONDITION_LABELS, indexOf(KURAMOTO_CONDITION_IDS, savedCondition, 0));
        AdapterView.OnItemSelectedListener updateListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateKuramotoSelectionSummary();
                if (kuramotoPanelAutoApplyArmed) {
                    scheduleLiveKuramotoMeshPanelSelection();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateKuramotoSelectionSummary();
            }
        };
        kuramotoSurfaceTarget.setOnItemSelectedListener(updateListener);
        kuramotoCondition.setOnItemSelectedListener(updateListener);

        root.addView(label("Surface"));
        root.addView(kuramotoSurfaceTarget);
        root.addView(label("Condition"));
        root.addView(kuramotoCondition);

        kuramotoSelectionSummary = text("", 13, PANEL_MUTED);
        kuramotoSelectionSummary.setPadding(0, dp(12), 0, dp(8));
        root.addView(kuramotoSelectionSummary);

        LinearLayout actionBlock = new LinearLayout(this);
        actionBlock.setOrientation(LinearLayout.VERTICAL);
        actionBlock.setPadding(0, dp(14), 0, dp(10));
        Button refresh = button("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshKuramotoMeshPanelFromStatus(true);
            }
        });
        Button apply = button("Apply Live");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitLiveKuramotoMeshPanelSelection(true);
            }
        });
        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(refresh, rowButtonParams());
        row.addView(apply, rowButtonParams());
        row.addView(close, rowButtonParams());
        actionBlock.addView(row);
        root.addView(actionBlock);

        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(10), 0, dp(8));
        root.addView(status);
        updateKuramotoSelectionSummary();
        liveApplyHandler.post(new Runnable() {
            @Override
            public void run() {
                kuramotoPanelAutoApplyArmed = true;
            }
        });
        return scroll;
    }

    private View buildPolarSensorPanelPageView(boolean includeKuramotoTab) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PANEL_BG);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        if (includeKuramotoTab) {
            root.addView(buildKuramotoSuiteTabs("polar"));
        }
        View polarView = ensurePolarSensorPanel().buildView();
        root.addView(
            polarView,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );
        return root;
    }

    private LinearLayout buildKuramotoSuiteTabs(String activePage) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));
        Button kuramoto = button("Kuramoto");
        Button polar = button("Polar");
        kuramoto.setEnabled(!"kuramoto".equals(activePage));
        polar.setEnabled(!"polar".equals(activePage));
        kuramoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setContentView(buildKuramotoMeshPanelView());
                updateStatus("Kuramoto controls.");
            }
        });
        polar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setContentView(buildPolarSensorPanelPageView(true));
                updateStatus("Polar sensor controls.");
            }
        });
        row.addView(kuramoto, rowButtonParams());
        row.addView(polar, rowButtonParams());
        return row;
    }

    private PolarSensorPanel ensurePolarSensorPanel() {
        if (polarSensorPanel == null) {
            polarSensorPanel = new PolarSensorPanel(this, new PolarSensorPanel.Host() {
                @Override
                public void closePanelAndReturnToImmersive() {
                    ControlPanelActivity.this.closePanelAndReturnToImmersive();
                }
            });
        }
        return polarSensorPanel;
    }

    private void updateKuramotoSelectionSummary() {
        if (kuramotoSelectionSummary == null || kuramotoSurfaceTarget == null || kuramotoCondition == null) {
            return;
        }
        int surfaceIndex = selectedKuramotoSurfaceIndex();
        int conditionIndex = selectedKuramotoConditionIndex();
        kuramotoSelectionSummary.setText(
            KURAMOTO_SURFACE_TARGETS[surfaceIndex]
                + " | "
                + KURAMOTO_PROFILE_IDS[conditionIndex]
                + " | baseHz="
                + String.format(Locale.US, "%.2f", KURAMOTO_MOVEMENT_BASE_HZ[conditionIndex])
                + " coupling="
                + String.format(Locale.US, "%.2f", KURAMOTO_MOVEMENT_COUPLING[conditionIndex])
        );
    }

    private void submitLiveKuramotoMeshPanelSelection(boolean userVisible) {
        JSONObject selection = null;
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            selection = buildKuramotoMeshPanelSelectionJson();
            JSONObject candidate = buildKuramotoMeshPanelPrivateParticleCandidate(selection);
            String responseText = nativeSubmitLivePrivateParticleDynamics(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            if (!"queued".equals(responseStatus)) {
                throw new IllegalStateException(responseText);
            }
            writeKuramotoMeshPanelStatus("queued_by_panel", selection, candidate, responseText);
            String message = "Kuramoto selection queued: "
                + selection.optString("surface_target_id")
                + " / "
                + selection.optString("condition")
                + ".";
            kuramotoMarker(
                "status=queued surfaceTarget="
                    + selection.optString("surface_target_id")
                    + " condition="
                    + selection.optString("condition")
                    + " profileId="
                    + selection.optString("profile_id")
            );
            if (userVisible) {
                updateStatus(message);
            } else {
                setStatusText(message);
            }
        } catch (Exception error) {
            try {
                writeKuramotoMeshPanelStatus("rejected_by_panel", selection, null, error.getMessage());
            } catch (Exception ignored) {
            }
            kuramotoMarker("status=rejected reason=" + markerToken(error.getMessage()));
            if (userVisible) {
                updateStatus("Kuramoto selection failed: " + error.getMessage());
            } else {
                setStatusText("Kuramoto selection failed: " + error.getMessage());
            }
        }
    }

    private void scheduleLiveKuramotoMeshPanelSelection() {
        if (kuramotoSurfaceTarget == null || kuramotoCondition == null) {
            return;
        }
        String selectionKey = KURAMOTO_SURFACE_IDS[selectedKuramotoSurfaceIndex()]
            + ":"
            + KURAMOTO_CONDITION_IDS[selectedKuramotoConditionIndex()];
        if (selectionKey.equals(lastScheduledKuramotoMeshPanelApplyKey)) {
            return;
        }
        lastScheduledKuramotoMeshPanelApplyKey = selectionKey;
        if (pendingKuramotoMeshPanelApply != null) {
            liveApplyHandler.removeCallbacks(pendingKuramotoMeshPanelApply);
        }
        pendingKuramotoMeshPanelApply = new Runnable() {
            @Override
            public void run() {
                pendingKuramotoMeshPanelApply = null;
                submitLiveKuramotoMeshPanelSelection(false);
            }
        };
        liveApplyHandler.postDelayed(pendingKuramotoMeshPanelApply, 180L);
    }

    private JSONObject buildKuramotoMeshPanelPrivateParticleCandidate(JSONObject selection) throws Exception {
        int surfaceIndex = indexOf(
            KURAMOTO_SURFACE_IDS,
            selection.optString("surface_target_id", "gpu-replay-hands"),
            1
        );
        int conditionIndex = indexOf(
            KURAMOTO_CONDITION_IDS,
            selection.optString("condition", "lowLow"),
            0
        );
        double[] drivers = kuramotoDriverValues(surfaceIndex, conditionIndex);
        JSONObject candidate = buildPrivateParticleDynamicsJsonFromValues(
            KURAMOTO_PROFILE_IDS[conditionIndex],
            "kuramoto_mesh_panel",
            surfaceIndex == 2 ? 1.0 : 0.70,
            surfaceIndex == 2 ? 1.0 : 0.46,
            drivers,
            7,
            0.5,
            14.0
        );
        candidate.put("kuramoto_mesh", selection);
        JSONObject privateParticles = candidate.optJSONObject("private_particles");
        if (privateParticles != null) {
            privateParticles.put("kuramoto_mesh_selection", selection);
        }
        return candidate;
    }

    private JSONObject buildKuramotoMeshPanelSelectionJson() throws Exception {
        int surfaceIndex = selectedKuramotoSurfaceIndex();
        int conditionIndex = selectedKuramotoConditionIndex();
        JSONObject selection = new JSONObject()
            .put("schema_id", KURAMOTO_MESH_PANEL_SELECTION_SCHEMA)
            .put("panel_role", "requester-ui-or-agent-cli")
            .put("panel_must_not_be_authority", true)
            .put("high_rate_payloads_allowed", false)
            .put("surface_target_id", KURAMOTO_SURFACE_IDS[surfaceIndex])
            .put("surface_target", KURAMOTO_SURFACE_TARGETS[surfaceIndex])
            .put("source_mode", KURAMOTO_SOURCE_MODES[surfaceIndex])
            .put("resource_plan_id", KURAMOTO_SURFACE_RESOURCE_PLAN_IDS[surfaceIndex])
            .put("runtime_profile_path", KURAMOTO_SURFACE_RUNTIME_PROFILE_PATHS[surfaceIndex])
            .put("condition", KURAMOTO_CONDITION_IDS[conditionIndex])
            .put("condition_label", KURAMOTO_CONDITION_LABELS[conditionIndex])
            .put("profile_set_id", KURAMOTO_PROFILE_SET_ID)
            .put("profile_id", KURAMOTO_PROFILE_IDS[conditionIndex])
            .put("default_profile_id", KURAMOTO_DEFAULT_PROFILE_ID)
            .put("dynamics_mode", "movement-only")
            .put("movement_base_frequency_hz", KURAMOTO_MOVEMENT_BASE_HZ[conditionIndex])
            .put("movement_frequency_spread_hz", KURAMOTO_MOVEMENT_SPREAD_HZ[conditionIndex])
            .put("movement_coupling", KURAMOTO_MOVEMENT_COUPLING[conditionIndex])
            .put("unit_distance_m", KURAMOTO_UNIT_DISTANCE_M[conditionIndex]);
        JSONArray expectedMarkers = new JSONArray();
        expectedMarkers.put("kuramotoSurfaceTarget=" + KURAMOTO_SURFACE_IDS[surfaceIndex]);
        expectedMarkers.put("kuramotoProfileId=" + KURAMOTO_PROFILE_IDS[conditionIndex]);
        selection.put("expected_markers", expectedMarkers);
        return selection;
    }

    private double[] kuramotoDriverValues(int surfaceIndex, int conditionIndex) {
        double highEnergy = KURAMOTO_MOVEMENT_BASE_HZ[conditionIndex] > 0.5 ? 1.0 : 0.0;
        double highCoherence = KURAMOTO_MOVEMENT_COUPLING[conditionIndex] > 0.5 ? 1.0 : 0.0;
        return new double[] {
            highEnergy > 0.5 ? 0.85 : 0.25,
            highCoherence > 0.5 ? 0.85 : 0.15,
            clamp(KURAMOTO_MOVEMENT_BASE_HZ[conditionIndex] / 0.88, 0.0, 1.0),
            clamp(1.0 - (KURAMOTO_MOVEMENT_SPREAD_HZ[conditionIndex] / 0.62), 0.0, 1.0),
            clamp(KURAMOTO_UNIT_DISTANCE_M[conditionIndex] / 0.004, 0.0, 1.0),
            KURAMOTO_SURFACE_IDS.length <= 1 ? 0.0 : (double) surfaceIndex / (KURAMOTO_SURFACE_IDS.length - 1),
            KURAMOTO_CONDITION_IDS.length <= 1 ? 0.0 : (double) conditionIndex / (KURAMOTO_CONDITION_IDS.length - 1),
            0.0
        };
    }

    private void refreshKuramotoMeshPanelFromStatus(boolean userVisible) {
        String surface = readKuramotoPanelSelectionString("surface_target_id", "gpu-replay-hands");
        String condition = readKuramotoPanelSelectionString("condition", "lowLow");
        boolean previousAutoApply = kuramotoPanelAutoApplyArmed;
        kuramotoPanelAutoApplyArmed = false;
        if (kuramotoSurfaceTarget != null) {
            kuramotoSurfaceTarget.setSelection(indexOf(KURAMOTO_SURFACE_IDS, surface, 1));
        }
        if (kuramotoCondition != null) {
            kuramotoCondition.setSelection(indexOf(KURAMOTO_CONDITION_IDS, condition, 0));
        }
        kuramotoPanelAutoApplyArmed = previousAutoApply;
        updateKuramotoSelectionSummary();
        String message = "Kuramoto panel refreshed: " + surface + " / " + condition + ".";
        if (userVisible) {
            updateStatus(message);
        } else {
            setStatusText(message);
        }
    }

    private void writeKuramotoMeshPanelStatus(
        String panelStatus,
        JSONObject selection,
        JSONObject candidate,
        String resultText
    ) throws Exception {
        JSONObject body = new JSONObject()
            .put("schema", "rusty.kuramoto.mesh.native_panel_status.v1")
            .put("status", panelStatus)
            .put("transport", "same-apk-control-panel")
            .put("updated_at_unix_ms", System.currentTimeMillis())
            .put("selection", selection == null ? JSONObject.NULL : selection)
            .put("result", resultText == null ? JSONObject.NULL : resultText);
        if (candidate != null) {
            JSONObject privateParticles = candidate.optJSONObject("private_particles");
            if (privateParticles != null) {
                body.put("private_particles", privateParticles);
            }
            body.put("candidate_revision", candidate.optLong("revision", 0L));
        }
        writeFile(KURAMOTO_MESH_PANEL_STATUS_FILE, body.toString(2));
    }

    private String readKuramotoPanelSelectionString(String key, String fallback) {
        try {
            JSONObject body = new JSONObject(readFile(KURAMOTO_MESH_PANEL_STATUS_FILE));
            JSONObject selection = body.optJSONObject("selection");
            if (selection == null) {
                return fallback;
            }
            String value = selection.optString(key, fallback);
            return value == null || value.length() == 0 ? fallback : value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int selectedKuramotoSurfaceIndex() {
        if (kuramotoSurfaceTarget == null) {
            return 1;
        }
        return Math.max(0, Math.min(KURAMOTO_SURFACE_IDS.length - 1, kuramotoSurfaceTarget.getSelectedItemPosition()));
    }

    private int selectedKuramotoConditionIndex() {
        if (kuramotoCondition == null) {
            return 0;
        }
        return Math.max(0, Math.min(KURAMOTO_CONDITION_IDS.length - 1, kuramotoCondition.getSelectedItemPosition()));
    }

    private View buildPrivateParticleDynamicsView() {
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
        TextView title = text("Particle Dynamics Panel", 22, PANEL_FG);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button headerClose = button("Close");
        headerClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        header.addView(headerClose);
        root.addView(header);
        root.addView(text("Live scalar controls for the generic private-particle slot.", 13, PANEL_MUTED));
        root.addView(privateParticlePreviewBand());

        liveAutoApply = checkBox("Live auto update", true);
        liveAutoApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (liveAutoApply.isChecked()) {
                    schedulePrivateParticleDynamicsApplyFromControl();
                } else {
                    cancelPendingPrivateParticleDynamicsApply();
                    setStatusText("Live auto update off. Use Apply Live for explicit particle changes.");
                }
            }
        });
        root.addView(liveAutoApply);

        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        JSONArray driverStatus = privateParticles == null
            ? null
            : privateParticles.optJSONArray("driver_values01");
        JSONObject tracerStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("tracer");

        root.addView(sectionTitle("Particle Shape"));
        privateParticleVisualScale = privateParticleSlider(
            "Particle visual scale",
            0.05,
            1.0,
            readPrivateParticleStatusDouble(
                privateParticles,
                "visual_scale",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_VISUAL_SCALE, 0.70)
            ),
            1000,
            "",
            false
        );
        privateParticleWorldAnchorScale = privateParticleSlider(
            "Sphere scale",
            0.05,
            4.0,
            readPrivateParticleStatusDouble(
                privateParticles,
                "world_anchor_scale_m",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE, 0.46)
            ),
            1000,
            " m",
            false
        );
        root.addView(privateParticleVisualScale.view);
        root.addView(privateParticleWorldAnchorScale.view);

        root.addView(sectionTitle("Dynamics Drivers"));
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
                false
            );
            root.addView(privateParticleDrivers[i].view);
        }

        root.addView(sectionTitle("Tracers"));
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
            true
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
            false
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
            false
        );
        root.addView(privateParticleTracerDrawSlots.view);
        root.addView(privateParticleTracerLifetime.view);
        root.addView(privateParticleTracerCopies.view);

        root.addView(buildPrivateParticleDynamicsActionRow());

        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(10), 0, dp(8));
        root.addView(status);
        return scroll;
    }

    private View privateParticlePreviewBand() {
        TextView preview = text("private particle dynamics", 13, Color.WHITE);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {
                Color.rgb(18, 22, 27),
                Color.rgb(35, 170, 155),
                Color.rgb(255, 214, 68),
                Color.rgb(190, 85, 170),
                Color.rgb(18, 22, 27)
            }
        );
        background.setCornerRadius(dp(3));
        preview.setBackground(background);
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        params.setMargins(0, dp(12), 0, dp(12));
        preview.setLayoutParams(params);
        return preview;
    }

    private SliderControl privateParticleSlider(
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
            new Runnable() {
                @Override
                public void run() {
                    schedulePrivateParticleDynamicsApplyFromControl();
                }
            }
        );
    }

    private SliderControl privateParticleConfigSlider(
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
            new Runnable() {
                @Override
                public void run() {
                    updatePrivateParticleConfigResolvedLabel();
                    schedulePrivateParticleConfigApplyFromControl();
                }
            }
        );
    }

    private ParameterEnvelopeControl parameterEnvelope(
        String id,
        String title,
        String rangeLabel,
        double minValue,
        double maxValue,
        double controlMin,
        double controlMax,
        double liveValue,
        int liveSteps,
        String suffix,
        int cycleMultiplier,
        String curveChoice,
        String optionLabel,
        boolean optionDefault
    ) {
        return parameterEnvelope(
            id,
            title,
            rangeLabel,
            minValue,
            maxValue,
            controlMin,
            controlMax,
            liveValue,
            liveSteps,
            suffix,
            cycleMultiplier,
            curveChoice,
            "Oscillator",
            optionLabel,
            optionDefault
        );
    }

    private ParameterEnvelopeControl parameterEnvelope(
        String id,
        String title,
        String rangeLabel,
        double minValue,
        double maxValue,
        double controlMin,
        double controlMax,
        double liveValue,
        int liveSteps,
        String suffix,
        int cycleMultiplier,
        String curveChoice,
        String driverModeChoice,
        String optionLabel,
        boolean optionDefault
    ) {
        ParameterEnvelopeControl control = new ParameterEnvelopeControl(
            id,
            title,
            rangeLabel,
            minValue,
            maxValue,
            controlMin,
            controlMax,
            liveValue,
            liveSteps,
            suffix,
            cycleMultiplier,
            curveChoice,
            driverModeChoice,
            optionLabel,
            optionDefault
        );
        privateParticleConfigParameterControls.add(control);
        return control;
    }

    private Spinner configSpinner(String[] values, String selectedValue) {
        int selectedIndex = indexOf(values, selectedValue, 0);
        Spinner spinner = spinner(values, selectedIndex);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (privateParticleConfigViewBuilding) {
                    return;
                }
                updatePrivateParticleConfigResolvedLabel();
                schedulePrivateParticleConfigApplyFromControl();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        return spinner;
    }

    private String spinnerValue(Spinner spinner, String fallback) {
        if (spinner == null || spinner.getSelectedItem() == null) {
            return fallback;
        }
        return String.valueOf(spinner.getSelectedItem());
    }

    private void addReadOnlyLines(LinearLayout parent, String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            parent.addView(text(lines[i], 13, PANEL_MUTED));
        }
    }

    private View buildPrivateParticleDynamicsActionRow() {
        LinearLayout actionBlock = new LinearLayout(this);
        actionBlock.setOrientation(LinearLayout.VERTICAL);
        actionBlock.setPadding(0, dp(14), 0, dp(10));

        Button refresh = button("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshPrivateParticleDynamicsFromStatus(true);
            }
        });
        Button applyLive = button("Apply Live");
        applyLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitLivePrivateParticleDynamics(true);
            }
        });
        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(refresh, rowButtonParams());
        row.addView(applyLive, rowButtonParams());
        row.addView(close, rowButtonParams());
        actionBlock.addView(row);
        return actionBlock;
    }

    private View buildPrivateParticleDepthWaveView() {
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
        TextView title = text("Depth Wave Panel", 22, PANEL_FG);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button headerClose = button("Close");
        headerClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        header.addView(headerClose);
        root.addView(header);
        root.addView(text("AKD depth_wave_percent control surface.", 13, PANEL_MUTED));
        root.addView(depthWavePreviewBand());

        liveAutoApply = checkBox("Live auto update", true);
        liveAutoApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (liveAutoApply.isChecked()) {
                    schedulePrivateParticleDepthWaveApplyFromControl();
                } else {
                    cancelPendingPrivateParticleDepthWaveApply();
                    setStatusText("Live auto update off. Use Apply Live for explicit depth-wave changes.");
                }
            }
        });
        root.addView(liveAutoApply);

        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        double currentDriver = privateParticleDriverValueFromStatusOrProperty(
            privateParticleStatusBody(statusJson),
            DEPTH_WAVE_DRIVER_INDEX,
            0.0
        );
        double currentPercent = depthWavePercentForDriverValue01(currentDriver);

        root.addView(sectionTitle("Value"));
        depthWavePercent = slider(
            "Depth wave percent",
            DEPTH_WAVE_MIN_PERCENT,
            DEPTH_WAVE_MAX_PERCENT,
            currentPercent,
            1000,
            "",
            false,
            new Runnable() {
                @Override
                public void run() {
                    setSliderValue(depthWaveDriverValue01, driverValue01ForDepthWavePercent(depthWavePercent.value()));
                    updateDepthWaveResolvedLabel();
                    schedulePrivateParticleDepthWaveApplyFromControl();
                }
            }
        );
        depthWaveDriverValue01 = slider(
            "Resolved driver3.value01",
            0.0,
            1.0,
            currentDriver,
            1000,
            "",
            false,
            new Runnable() {
                @Override
                public void run() {
                    setSliderValue(depthWavePercent, depthWavePercentForDriverValue01(depthWaveDriverValue01.value()));
                    updateDepthWaveResolvedLabel();
                    schedulePrivateParticleDepthWaveApplyFromControl();
                }
            }
        );
        root.addView(depthWavePercent.view);
        root.addView(depthWaveDriverValue01.view);

        root.addView(sectionTitle("Driver Policy"));
        depthWaveDriverPolicy = new Spinner(this);
        ArrayAdapter<String> adapter =
            new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, DEPTH_WAVE_DRIVER_POLICIES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        depthWaveDriverPolicy.setAdapter(adapter);
        depthWaveDriverPolicy.setSelection(0);
        depthWaveDriverPolicy.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    schedulePrivateParticleDepthWaveApplyFromControl();
                    return;
                }
                cancelPendingPrivateParticleDepthWaveApply();
                setStatusText("Selected depth-wave policy needs a payload rebuild.");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(depthWaveDriverPolicy);

        root.addView(sectionTitle("AKD Contract"));
        root.addView(text(String.format(
            Locale.US,
            "Range: %.3f to %.3f",
            DEPTH_WAVE_MIN_PERCENT,
            DEPTH_WAVE_MAX_PERCENT
        ), 13, PANEL_MUTED));
        root.addView(text("Curve: akd-hump, first rising branch, 16 samples", 13, PANEL_MUTED));
        root.addView(text(String.format(
            Locale.US,
            "Dimension: wave index %d",
            DEPTH_WAVE_DIMENSION_INDEX
        ), 13, PANEL_MUTED));
        root.addView(text(String.format(
            Locale.US,
            "Cycle multiplier: %d, current visibility cycle-gated off",
            DEPTH_WAVE_CYCLE_MULTIPLIER
        ), 13, PANEL_MUTED));
        root.addView(text("Live transport: debug.rustyquest.native_renderer.private_particles.driver3.value01", 13, PANEL_MUTED));
        depthWaveResolvedLabel = text("", 13, PANEL_FG);
        depthWaveResolvedLabel.setPadding(0, dp(8), 0, dp(6));
        root.addView(depthWaveResolvedLabel);
        updateDepthWaveResolvedLabel();

        root.addView(buildPrivateParticleDepthWaveActionRow());

        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(10), 0, dp(8));
        root.addView(status);
        return scroll;
    }

    private View depthWavePreviewBand() {
        TextView preview = text("depth wave", 13, Color.WHITE);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {
                Color.rgb(18, 22, 27),
                Color.rgb(30, 110, 190),
                Color.rgb(35, 190, 160),
                Color.rgb(255, 214, 68),
                Color.rgb(18, 22, 27)
            }
        );
        background.setCornerRadius(dp(3));
        preview.setBackground(background);
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        params.setMargins(0, dp(12), 0, dp(12));
        preview.setLayoutParams(params);
        return preview;
    }

    private View buildPrivateParticleDepthWaveActionRow() {
        LinearLayout actionBlock = new LinearLayout(this);
        actionBlock.setOrientation(LinearLayout.VERTICAL);
        actionBlock.setPadding(0, dp(14), 0, dp(10));

        Button refresh = button("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshPrivateParticleDepthWaveFromStatus(true);
            }
        });
        Button applyLive = button("Apply Live");
        applyLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitLivePrivateParticleDepthWave(true);
            }
        });
        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(refresh, rowButtonParams());
        row.addView(applyLive, rowButtonParams());
        row.addView(close, rowButtonParams());
        actionBlock.addView(row);
        return actionBlock;
    }

    private View buildPrivateParticleConfigView() {
        privateParticleConfigViewBuilding = true;
        privateParticleConfigParameterControls.clear();
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
        TextView title = text("AKD Config Panel", 22, PANEL_FG);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button headerClose = button("Close");
        headerClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        header.addView(headerClose);
        root.addView(header);
        root.addView(text("Runtime controls organized from the AKD ViscerealityRenderProfile inspector.", 13, PANEL_MUTED));
        root.addView(privateParticlePreviewBand());

        liveAutoApply = checkBox("Live auto update", true);
        liveAutoApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (liveAutoApply.isChecked()) {
                    schedulePrivateParticleConfigApplyFromControl();
                } else {
                    cancelPendingPrivateParticleConfigApply();
                    setStatusText("Live auto update off. Use Apply Live for explicit AKD config changes.");
                }
            }
        });
        root.addView(liveAutoApply);

        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        JSONObject tracerStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("tracer");
        JSONObject transparencyStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("transparency");
        JSONObject colorStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("color");
        double[] drivers = privateParticleDriverValuesFromStatusOrProperties(privateParticles);
        JSONArray driverControls = privateParticles == null
            ? null
            : privateParticles.optJSONArray("driver_controls");
        double visualScale = readPrivateParticleStatusDouble(
            privateParticles,
            "visual_scale",
            readDoubleProperty(PROP_PRIVATE_PARTICLE_VISUAL_SCALE, 1.0)
        );
        double worldAnchorScale = readPrivateParticleStatusDouble(
            privateParticles,
            "world_anchor_scale_m",
            readDoubleProperty(PROP_PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE, AKD_SPHERE_RADIUS_MAX_M)
        );

        privateParticleConfigPageButtons = new Button[PRIVATE_PARTICLE_CONFIG_PAGE_LABELS.length];
        privateParticleConfigPageViews = new LinearLayout[PRIVATE_PARTICLE_CONFIG_PAGE_LABELS.length];
        root.addView(buildPrivateParticleConfigPageRow());

        LinearLayout pageStack = new LinearLayout(this);
        pageStack.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageStack);
        for (int i = 0; i < privateParticleConfigPageViews.length; i++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            privateParticleConfigPageViews[i] = page;
            pageStack.addView(page);
        }

        buildPrivateParticleConfigDynamicsPage(
            privateParticleConfigPageViews[0],
            drivers,
            visualScale,
            worldAnchorScale
        );
        buildPrivateParticleConfigVisualsPage(privateParticleConfigPageViews[1], drivers, driverControls);
        buildPrivateParticleConfigTracersPage(
            privateParticleConfigPageViews[2],
            tracerStatus,
            transparencyStatus,
            colorStatus
        );
        buildPrivateParticleConfigBackendPage(privateParticleConfigPageViews[3]);

        privateParticleConfigResolvedLabel = text("", 13, PANEL_FG);
        privateParticleConfigResolvedLabel.setPadding(0, dp(8), 0, dp(6));
        root.addView(privateParticleConfigResolvedLabel);
        updatePrivateParticleConfigResolvedLabel();

        root.addView(buildPrivateParticleConfigActionRow());

        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(10), 0, dp(8));
        root.addView(status);
        selectPrivateParticleConfigPage(0);
        privateParticleConfigViewBuilding = false;
        schedulePrivateParticleConfigApplyFromControl();
        return scroll;
    }

    private View buildPrivateParticleConfigPageRow() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(0, dp(10), 0, dp(8));
        for (int i = 0; i < PRIVATE_PARTICLE_CONFIG_PAGE_LABELS.length; i++) {
            final int pageIndex = i;
            Button pageButton = button(PRIVATE_PARTICLE_CONFIG_PAGE_LABELS[i]);
            pageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectPrivateParticleConfigPage(pageIndex);
                }
            });
            privateParticleConfigPageButtons[i] = pageButton;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(pageButton, params);
        }
        return grid;
    }

    private void selectPrivateParticleConfigPage(int pageIndex) {
        privateParticleConfigPageIndex = Math.max(
            0,
            Math.min(PRIVATE_PARTICLE_CONFIG_PAGE_LABELS.length - 1, pageIndex)
        );
        for (int i = 0; i < privateParticleConfigPageViews.length; i++) {
            if (privateParticleConfigPageViews[i] != null) {
                privateParticleConfigPageViews[i].setVisibility(
                    i == privateParticleConfigPageIndex ? View.VISIBLE : View.GONE
                );
            }
            if (i < privateParticleConfigPageButtons.length && privateParticleConfigPageButtons[i] != null) {
                styleButton(privateParticleConfigPageButtons[i], i == privateParticleConfigPageIndex);
            }
        }
    }

    private void buildPrivateParticleConfigDynamicsPage(
        LinearLayout page,
        double[] drivers,
        double visualScale,
        double worldAnchorScale
    ) {
        FoldoutControl particle = foldout("Particle And Oscillators", true);
        page.addView(particle.view);
        privateParticleConfigVisualScale = privateParticleConfigSlider(
            "Particle visual scale",
            0.05,
            1.0,
            visualScale,
            1000,
            "",
            false
        );
        privateParticleConfigWorldAnchorScale = privateParticleConfigSlider(
            "Sphere radius meters",
            AKD_SPHERE_RADIUS_MIN_M,
            AKD_SPHERE_RADIUS_MAX_M,
            clamp(worldAnchorScale, AKD_SPHERE_RADIUS_MIN_M, AKD_SPHERE_RADIUS_MAX_M),
            1000,
            " m",
            false
        );
        privateParticleConfigDeformationDriver = privateParticleConfigSlider(
            "Sphere deformation progress (driver0)",
            0.0,
            1.0,
            drivers[SPHERE_DEFORMATION_DRIVER_INDEX],
            1000,
            "",
            false
        );
        privateParticleConfigCouplingDriver = privateParticleConfigSlider(
            "Tier coupling / coherence (driver1)",
            0.0,
            1.0,
            drivers[COUPLING_DRIVER_INDEX],
            1000,
            "",
            false
        );
        particle.body.addView(privateParticleConfigVisualScale.view);
        particle.body.addView(privateParticleConfigWorldAnchorScale.view);
        particle.body.addView(privateParticleConfigDeformationDriver.view);
        particle.body.addView(privateParticleConfigCouplingDriver.view);
        addReadOnlyLines(particle.body, new String[] {
            "Particle count: 2562 (payload allocation)",
            "Oscillator dimensions: 6",
            "Natural frequency: 0.4 to 0.6 Hz with AKD noise seed 1",
            "Base coupling: 1.0; cross coupling matrix strength: 0.16",
            "Neighbor tiers: tier1 -1 to 1, tier2 -0.5 to 0.5, tier3 -1 to 0"
        });

        FoldoutControl routing = foldout("Dimension Routing", false);
        page.addView(routing.view);
        addReadOnlyLines(routing.body, new String[] {
            "color 0, size 1, rotation 2",
            "orbit radius 3, orbit angle 3",
            "wave 4, animation 5",
            "alpha/saturation/brightness 0"
        });

        FoldoutControl radius = foldout("Radius And Pacer Surface", false);
        page.addView(radius.view);
        addReadOnlyLines(radius.body, new String[] {
            "Sphere radius channel: enabled, drive mode Volume01",
            "Smoothing: 0.03 s; inhale/exhale defaults: 4 s / 4 s",
            "Pacer radius channel: disabled",
            "Ring overlay: BaseOnly, pacer particle count 0"
        });
        FoldoutControl deformation = foldout("Sphere Deformation Curves", false);
        radius.body.addView(deformation.view);
        addReadOnlyLines(deformation.body, new String[] {
            "Oblateness range: 0.25 to 0.5, linear payload curve",
            "Axis profile range: 2.0 to 1.0, linear payload curve",
            "Forward axis comes from the Rusty Quest world anchor"
        });

        FoldoutControl streams = foldout("Default Streams", false);
        page.addView(streams.view);
        addReadOnlyLines(streams.body, new String[] {
            "heartbeat_lsl maps to orbit radius in AKD, currently driver5 when all-visual profile is active",
            "coherence_lsl maps to tier coupling and alpha/saturation/brightness through driver1",
            "breathing_controller maps sphere radius through the world-anchor scale lane",
            "manual1 default 1.000, manual2 default 0.000, manual3 default 0.327"
        });
    }

    private void buildPrivateParticleConfigVisualsPage(
        LinearLayout page,
        double[] drivers,
        JSONArray driverControls
    ) {
        FoldoutControl color = foldout("Color And Alpha", false);
        page.addView(color.view);
        ParameterEnvelopeControl colorDriver = parameterEnvelope(
            "color_driver",
            "Color Driver",
            "Gradient weight",
            driverControlRangeMin(driverControls, 0, 0.0),
            driverControlRangeMax(driverControls, 0, 1.0),
            0.0,
            1.0,
            drivers[0],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, 0, 2),
            driverControlCurveChoice(driverControls, 0, "Linear"),
            driverControlModeChoice(driverControls, 0, "Oscillator"),
            null,
            false
        );
        color.body.addView(colorDriver.view);
        ParameterEnvelopeControl transparency = parameterEnvelope(
            "transparency",
            "Transparency",
            "Transparency limits",
            driverControlRangeMin(driverControls, COUPLING_DRIVER_INDEX, 1.0),
            driverControlRangeMax(driverControls, COUPLING_DRIVER_INDEX, 1.0),
            0.0,
            1.0,
            drivers[COUPLING_DRIVER_INDEX],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, COUPLING_DRIVER_INDEX, 1),
            driverControlCurveChoice(driverControls, COUPLING_DRIVER_INDEX, "Linear"),
            driverControlModeChoice(driverControls, COUPLING_DRIVER_INDEX, "Oscillator"),
            null,
            false
        );
        ParameterEnvelopeControl saturation = parameterEnvelope(
            "saturation",
            "Saturation",
            "Saturation limits",
            driverControlRangeMin(driverControls, COUPLING_DRIVER_INDEX, 0.3),
            driverControlRangeMax(driverControls, COUPLING_DRIVER_INDEX, 1.0),
            0.0,
            1.0,
            drivers[COUPLING_DRIVER_INDEX],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, COUPLING_DRIVER_INDEX, 1),
            driverControlCurveChoice(driverControls, COUPLING_DRIVER_INDEX, "Linear"),
            driverControlModeChoice(driverControls, COUPLING_DRIVER_INDEX, "Oscillator"),
            null,
            false
        );
        ParameterEnvelopeControl brightness = parameterEnvelope(
            "brightness",
            "Brightness",
            "Brightness limits",
            driverControlRangeMin(driverControls, COUPLING_DRIVER_INDEX, 0.3),
            driverControlRangeMax(driverControls, COUPLING_DRIVER_INDEX, 1.0),
            0.0,
            1.0,
            drivers[COUPLING_DRIVER_INDEX],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, COUPLING_DRIVER_INDEX, 1),
            driverControlCurveChoice(driverControls, COUPLING_DRIVER_INDEX, "Linear"),
            driverControlModeChoice(driverControls, COUPLING_DRIVER_INDEX, "Oscillator"),
            null,
            false
        );
        color.body.addView(transparency.view);
        color.body.addView(saturation.view);
        color.body.addView(brightness.view);

        FoldoutControl sizeWave = foldout("Size And Depth Wave", false);
        page.addView(sizeWave.view);
        ParameterEnvelopeControl size = parameterEnvelope(
            "particle_size",
            "Particle Size",
            "Particle size envelope limits",
            driverControlRangeMin(driverControls, PARTICLE_SIZE_DRIVER_INDEX, AKD_PARTICLE_SIZE_MIN),
            driverControlRangeMax(driverControls, PARTICLE_SIZE_DRIVER_INDEX, AKD_PARTICLE_SIZE_MAX),
            0.0,
            0.2,
            drivers[PARTICLE_SIZE_DRIVER_INDEX],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, PARTICLE_SIZE_DRIVER_INDEX, 1),
            driverControlCurveChoice(driverControls, PARTICLE_SIZE_DRIVER_INDEX, "AKD hump"),
            driverControlModeChoice(driverControls, PARTICLE_SIZE_DRIVER_INDEX, "Oscillator"),
            "Use percent size",
            true
        );
        privateParticleConfigParticleSize = size.liveValueSlider;
        ParameterEnvelopeControl depthWave = parameterEnvelope(
            "depth_wave",
            "Depth Wave",
            "Depth wave percent limits",
            driverControlRangeMin(driverControls, DEPTH_WAVE_DRIVER_INDEX, DEPTH_WAVE_MIN_PERCENT),
            driverControlRangeMax(driverControls, DEPTH_WAVE_DRIVER_INDEX, DEPTH_WAVE_MAX_PERCENT),
            0.0,
            0.5,
            drivers[DEPTH_WAVE_DRIVER_INDEX],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, DEPTH_WAVE_DRIVER_INDEX, DEPTH_WAVE_CYCLE_MULTIPLIER),
            driverControlCurveChoice(driverControls, DEPTH_WAVE_DRIVER_INDEX, "AKD hump"),
            driverControlModeChoice(driverControls, DEPTH_WAVE_DRIVER_INDEX, "Oscillator"),
            null,
            false
        );
        privateParticleConfigDepthWavePercent = depthWave.liveValueSlider;
        sizeWave.body.addView(size.view);
        sizeWave.body.addView(depthWave.view);

        FoldoutControl spinOrbit = foldout("Spin And Orbit", false);
        page.addView(spinOrbit.view);
        ParameterEnvelopeControl spin = parameterEnvelope(
            "spin_speed",
            "Spin Speed",
            "Spin speed limits",
            driverControlRangeMin(driverControls, SPIN_SPEED_DRIVER_INDEX, AKD_SPIN_SPEED_MIN),
            driverControlRangeMax(driverControls, SPIN_SPEED_DRIVER_INDEX, AKD_SPIN_SPEED_MAX),
            0.0,
            1.0,
            drivers[SPIN_SPEED_DRIVER_INDEX],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, SPIN_SPEED_DRIVER_INDEX, 0),
            driverControlCurveChoice(driverControls, SPIN_SPEED_DRIVER_INDEX, "AKD hump"),
            driverControlModeChoice(driverControls, SPIN_SPEED_DRIVER_INDEX, "Oscillator"),
            "Dual spin animation",
            true
        );
        privateParticleConfigSpinSpeed = spin.liveValueSlider;
        ParameterEnvelopeControl orbitRadius = parameterEnvelope(
            "orbit_radius",
            "Orbit Radius",
            "Orbit radius multiplier limits",
            driverControlRangeMin(driverControls, ORBIT_RADIUS_DRIVER_INDEX, AKD_ORBIT_RADIUS_MIN),
            driverControlRangeMax(driverControls, ORBIT_RADIUS_DRIVER_INDEX, AKD_ORBIT_RADIUS_MAX),
            0.0,
            2.0,
            drivers[ORBIT_RADIUS_DRIVER_INDEX],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, ORBIT_RADIUS_DRIVER_INDEX, 1),
            driverControlCurveChoice(driverControls, ORBIT_RADIUS_DRIVER_INDEX, "AKD hump"),
            driverControlModeChoice(driverControls, ORBIT_RADIUS_DRIVER_INDEX, "Oscillator"),
            null,
            false
        );
        privateParticleConfigOrbitRadius = orbitRadius.liveValueSlider;
        ParameterEnvelopeControl orbitAngle = parameterEnvelope(
            "orbit_angle",
            "Orbit Angle",
            "Orbit angle limits",
            driverControlRangeMin(driverControls, ORBIT_ANGLE_DRIVER_INDEX, AKD_ORBIT_ANGLE_MIN),
            driverControlRangeMax(driverControls, ORBIT_ANGLE_DRIVER_INDEX, AKD_ORBIT_ANGLE_MAX),
            0.0,
            AKD_ORBIT_ANGLE_MAX,
            drivers[ORBIT_ANGLE_DRIVER_INDEX],
            1000,
            " rad",
            driverControlCycleMultiplier(driverControls, ORBIT_ANGLE_DRIVER_INDEX, 1),
            driverControlCurveChoice(driverControls, ORBIT_ANGLE_DRIVER_INDEX, "Linear"),
            driverControlModeChoice(driverControls, ORBIT_ANGLE_DRIVER_INDEX, "Oscillator"),
            null,
            false
        );
        privateParticleConfigOrbitAngle = orbitAngle.liveValueSlider;
        ParameterEnvelopeControl animation = parameterEnvelope(
            "animation_phase",
            "Animation Phase",
            "Animation phase limits",
            driverControlRangeMin(driverControls, ANIMATION_FRAME_DRIVER_INDEX, 0.0),
            driverControlRangeMax(driverControls, ANIMATION_FRAME_DRIVER_INDEX, 1.0),
            0.0,
            1.0,
            drivers[ANIMATION_FRAME_DRIVER_INDEX],
            1000,
            "",
            driverControlCycleMultiplier(driverControls, ANIMATION_FRAME_DRIVER_INDEX, 1),
            driverControlCurveChoice(driverControls, ANIMATION_FRAME_DRIVER_INDEX, "AKD hump"),
            driverControlModeChoice(driverControls, ANIMATION_FRAME_DRIVER_INDEX, "Oscillator"),
            null,
            false
        );
        privateParticleConfigAnimationFrame = animation.liveValueSlider;
        spinOrbit.body.addView(spin.view);
        spinOrbit.body.addView(orbitRadius.view);
        spinOrbit.body.addView(orbitAngle.view);
        spinOrbit.body.addView(animation.view);
    }

    private void buildPrivateParticleConfigTracersPage(
        LinearLayout page,
        JSONObject tracerStatus,
        JSONObject transparencyStatus,
        JSONObject colorStatus
    ) {
        FoldoutControl tracers = foldout("Integrated Tracers", true);
        page.addView(tracers.view);
        privateParticleConfigTracerDrawSlots = privateParticleConfigSlider(
            "Tracer draw slots",
            0.0,
            7.0,
            readPrivateParticleStatusTracerDouble(
                tracerStatus,
                "draw_slots_per_oscillator",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_DRAW_SLOTS, 7.0)
            ),
            7,
            "",
            true
        );
        privateParticleConfigTracerLifetime = privateParticleConfigSlider(
            "Tracer lifetime",
            0.016,
            0.5,
            readPrivateParticleStatusTracerDouble(
                tracerStatus,
                "lifetime_seconds",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_LIFETIME, 0.5)
            ),
            1000,
            " s",
            false
        );
        privateParticleConfigTracerCopies = privateParticleConfigSlider(
            "Tracer copies/sec",
            0.0,
            14.0,
            readPrivateParticleStatusTracerDouble(
                tracerStatus,
                "copies_per_second",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_COPIES, 14.0)
            ),
            1000,
            "",
            false
        );
        tracers.body.addView(privateParticleConfigTracerDrawSlots.view);
        tracers.body.addView(privateParticleConfigTracerLifetime.view);
        tracers.body.addView(privateParticleConfigTracerCopies.view);
        addReadOnlyLines(tracers.body, new String[] {
            "Tracers are GPU-resident; draw slots gate the rendered live slots, not buffer capacity",
            "Capacity remains 7 slots per oscillator in the AKD payload"
        });

        FoldoutControl transparency = foldout("Overdraw And Transparency", true);
        page.addView(transparency.view);
        privateParticleTransparencyOpacity = privateParticleConfigSlider(
            "Transparency opacity",
            0.0,
            4.0,
            readNestedPrivateParticleStatusDouble(
                transparencyStatus,
                "opacity",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRANSPARENCY_OPACITY, 1.0)
            ),
            1000,
            "",
            false
        );
        privateParticleTransparencyOutputAlphaScale = privateParticleConfigSlider(
            "Output alpha scale",
            0.0,
            4.0,
            readNestedPrivateParticleStatusDouble(
                transparencyStatus,
                "output_alpha_scale",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRANSPARENCY_OUTPUT_ALPHA_SCALE, 1.0)
            ),
            1000,
            "",
            false
        );
        privateParticleTransparencyDepthSuppression = privateParticleConfigSlider(
            "Depth suppression",
            0.0,
            8.0,
            readNestedPrivateParticleStatusDouble(
                transparencyStatus,
                "depth_suppression_strength",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRANSPARENCY_DEPTH_SUPPRESSION, 0.0)
            ),
            1000,
            "",
            false
        );
        privateParticleTransparencyRgbAlphaCoupling = privateParticleConfigSlider(
            "RGB alpha coupling",
            0.0,
            1.0,
            readNestedPrivateParticleStatusDouble(
                transparencyStatus,
                "rgb_alpha_coupling",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_TRANSPARENCY_RGB_ALPHA_COUPLING, 1.0)
            ),
            1000,
            "",
            false
        );
        privateParticleColorFacingAttenuation = privateParticleConfigSlider(
            "Facing attenuation",
            0.0,
            1.0,
            readNestedPrivateParticleStatusDouble(
                colorStatus,
                "facing_attenuation_strength",
                readDoubleProperty(PROP_PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION, 0.0)
            ),
            1000,
            "",
            false
        );
        transparency.body.addView(privateParticleTransparencyOpacity.view);
        transparency.body.addView(privateParticleTransparencyOutputAlphaScale.view);
        transparency.body.addView(privateParticleTransparencyDepthSuppression.view);
        transparency.body.addView(privateParticleTransparencyRgbAlphaCoupling.view);
        transparency.body.addView(privateParticleColorFacingAttenuation.view);
        addReadOnlyLines(transparency.body, new String[] {
            "Billboard footprint: DiscPolygonNoClip, 12 segments",
            "Blend mode: LegacyAdditiveMultiply",
            "Composition: parametric RGB/alpha coupling",
            "Sort mode: MainAndCpuTracersBackToFront; implementation: GPU/private renderer path"
        });
    }

    private void buildPrivateParticleConfigBackendPage(LinearLayout page) {
        FoldoutControl shader = foldout("Pure Shader Geometry", false);
        page.addView(shader.view);
        addReadOnlyLines(shader.body, new String[] {
            "Payload mode: BakedTextureArrayPhase01 in AKD Sussex defaults",
            "Main geometry: MorphedRing; pacer geometry: TriangleFlip",
            "Edge width 0.015, outer feather 0.06, peak gain 1.4",
            "Ring radius 0.32, thickness 0.03, dual offset 180 degrees",
            "These fields require payload/shader profile rebuilds"
        });

        FoldoutControl baked = foldout("Baked Animation", false);
        page.addView(baked.view);
        addReadOnlyLines(baked.body, new String[] {
            "Auto bake on start: true",
            "Frame count 64, resolution 128, frames per update 4",
            "Premultiply alpha: true; persisted array: true",
            "Pacer baked texture source: DedicatedPacerArray",
            "Texture arrays are binary payload data, not panel hotload scalars"
        });

        FoldoutControl rebuild = foldout("Profile Rebuild Boundaries", true);
        page.addView(rebuild.view);
        addReadOnlyLines(rebuild.body, new String[] {
            "Particle count, dimensions, topology, small-world allocation, curve tables, gradients, and texture arrays need a private payload rebuild",
            "Driver policy selection needs a visual-driver activation profile rebuild",
            "Current live lanes: sphere radius, visual scale, driver0-driver7, tracers, transparency, and facing attenuation",
            "Best headset profile for this panel: all visual drivers activated"
        });
    }

    private View buildPrivateParticleConfigActionRow() {
        LinearLayout actionBlock = new LinearLayout(this);
        actionBlock.setOrientation(LinearLayout.VERTICAL);
        actionBlock.setPadding(0, dp(14), 0, dp(10));

        Button refresh = button("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshPrivateParticleConfigFromStatus(true);
            }
        });
        Button applyLive = button("Apply Live");
        applyLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitLivePrivateParticleConfig(true);
            }
        });
        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(refresh, rowButtonParams());
        row.addView(applyLive, rowButtonParams());
        row.addView(close, rowButtonParams());
        actionBlock.addView(row);
        return actionBlock;
    }

    private View privateLayerPreviewBand() {
        TextView preview = text("private layer selector", 13, Color.WHITE);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {
                Color.rgb(20, 24, 30),
                Color.rgb(45, 120, 210),
                Color.rgb(255, 214, 68),
                Color.rgb(215, 70, 150),
                Color.rgb(20, 24, 30)
            }
        );
        background.setCornerRadius(dp(3));
        preview.setBackground(background);
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        params.setMargins(0, dp(12), 0, dp(12));
        preview.setLayoutParams(params);
        return preview;
    }

    private GridLayout buildPrivateLayerChoiceGrid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        String[][] choices = new String[][] {
            {"Final", "0"},
            {"Raw brightness", "1"},
            {"Preblur brightness", "2"},
            {"Raw strength", "3"},
            {"Blurred strength", "4"},
            {"Displacement", "5"},
            {"Depth gradient", "6"}
        };
        ArrayList<Button> buttons = new ArrayList<Button>();
        for (int i = 0; i < choices.length; i++) {
            Button choice = button(choices[i][0]);
            choice.setTag(choices[i][1]);
            choice.setMinHeight(dp(46));
            choice.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    int layerIndex = Integer.parseInt(String.valueOf(view.getTag()));
                    selectedPrivateLayerIndex = layerIndex;
                    updatePrivateLayerButtons();
                    submitLivePrivateLayerSelection(layerIndex, true);
                }
            });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(choice, params);
            buttons.add(choice);
        }
        privateLayerButtons = buttons.toArray(new Button[buttons.size()]);
        updatePrivateLayerButtons();
        return grid;
    }

    private void updatePrivateLayerButtons() {
        for (int i = 0; i < privateLayerButtons.length; i++) {
            int layerIndex = Integer.parseInt(String.valueOf(privateLayerButtons[i].getTag()));
            styleButton(privateLayerButtons[i], layerIndex == selectedPrivateLayerIndex);
        }
    }

    private void submitLivePrivateLayerSelection(int layerIndex, boolean userVisible) {
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            JSONObject candidate = buildPrivateLayerSelectionJson(layerIndex);
            String responseText = nativeSubmitLivePrivateLayerSelection(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            if (!"queued".equals(responseStatus)) {
                throw new IllegalStateException(responseText);
            }
            String message = "Layer queued: " + privateLayerLabel(layerIndex) + ".";
            if (response.optBoolean("overwrote_pending", false)) {
                message = "Layer queued; older pending selection was replaced: "
                    + privateLayerLabel(layerIndex) + ".";
            }
            if (userVisible) {
                updateStatus(message);
            } else {
                setStatusText(message);
            }
        } catch (Exception error) {
            if (userVisible) {
                updateStatus("Layer selection failed: " + error.getMessage());
            } else {
                setStatusText("Layer selection failed: " + error.getMessage());
            }
        }
    }

    private void addDepthAlignmentControls(LinearLayout root) {
        JSONObject statusJson = readDepthAlignmentStatusJson();
        double leftX = readDepthAlignmentStatusOffset(
            statusJson,
            "left_offset_uv",
            0,
            readDoubleProperty(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_LEFT_OFFSET_X, 0.0)
        );
        double leftY = readDepthAlignmentStatusOffset(
            statusJson,
            "left_offset_uv",
            1,
            readDoubleProperty(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_LEFT_OFFSET_Y, 0.0)
        );
        double rightX = readDepthAlignmentStatusOffset(
            statusJson,
            "right_offset_uv",
            0,
            readDoubleProperty(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_X, 0.0)
        );
        double rightY = readDepthAlignmentStatusOffset(
            statusJson,
            "right_offset_uv",
            1,
            readDoubleProperty(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_Y, 0.0)
        );
        double sampleScale = readDepthAlignmentStatusScale(
            statusJson,
            readDoubleProperty(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_SCALE, 1.0)
        );
        depthLeftOffsetX = depthSlider("Left depth X", -0.25, 0.25, leftX);
        depthLeftOffsetY = depthSlider("Left depth Y", -0.25, 0.25, leftY);
        depthRightOffsetX = depthSlider("Right depth X", -0.25, 0.25, rightX);
        depthRightOffsetY = depthSlider("Right depth Y", -0.25, 0.25, rightY);
        depthSampleScale = depthSlider("Depth sample scale", 0.25, 3.0, sampleScale);
        root.addView(depthLeftOffsetX.view);
        root.addView(depthLeftOffsetY.view);
        root.addView(depthRightOffsetX.view);
        root.addView(depthRightOffsetY.view);
        root.addView(depthSampleScale.view);

        Button refresh = button("Refresh Depth");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshDepthAlignmentFromStatus(true);
            }
        });
        Button apply = button("Apply Depth");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitLiveDepthAlignment(true);
            }
        });
        LinearLayout depthRow = new LinearLayout(this);
        depthRow.setOrientation(LinearLayout.HORIZONTAL);
        depthRow.setPadding(0, dp(8), 0, dp(2));
        depthRow.addView(refresh, rowButtonParams());
        depthRow.addView(apply, rowButtonParams());
        root.addView(depthRow);
    }

    private SliderControl depthSlider(String title, double min, double max, double initial) {
        return slider(
            title,
            min,
            max,
            initial,
            1000,
            "",
            false,
            new Runnable() {
                @Override
                public void run() {
                    scheduleLiveDepthAlignmentApplyFromControl();
                }
            }
        );
    }

    private void scheduleLiveDepthAlignmentApplyFromControl() {
        cancelPendingDepthAlignmentApply();
        pendingDepthAlignmentApply = new Runnable() {
            @Override
            public void run() {
                pendingDepthAlignmentApply = null;
                submitLiveDepthAlignment(false);
            }
        };
        liveApplyHandler.postDelayed(pendingDepthAlignmentApply, 180);
        setStatusText("Depth alignment update pending.");
    }

    private void cancelPendingDepthAlignmentApply() {
        if (liveApplyHandler != null && pendingDepthAlignmentApply != null) {
            liveApplyHandler.removeCallbacks(pendingDepthAlignmentApply);
            pendingDepthAlignmentApply = null;
        }
    }

    private void submitLiveDepthAlignment(boolean userVisible) {
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            JSONObject candidate = buildDepthAlignmentJson();
            String responseText = nativeSubmitLiveDepthAlignment(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            if (!"queued".equals(responseStatus)) {
                throw new IllegalStateException(responseText);
            }
            String message = "Depth alignment queued: " + depthAlignmentSummary() + ".";
            if (response.optBoolean("overwrote_pending", false)) {
                message = "Depth alignment queued; older pending edit was replaced: "
                    + depthAlignmentSummary() + ".";
            }
            if (userVisible) {
                updateStatus(message);
            } else {
                setStatusText(message);
            }
        } catch (Exception error) {
            if (userVisible) {
                updateStatus("Depth alignment failed: " + error.getMessage());
            } else {
                setStatusText("Depth alignment failed: " + error.getMessage());
            }
        }
    }

    private JSONObject buildDepthAlignmentJson() throws Exception {
        JSONObject source = new JSONObject()
            .put("surface", "same_apk_panel")
            .put("transport", "jni_live_queue");
        JSONObject depthAlignment = new JSONObject()
            .put("left_offset_uv", new JSONArray()
                .put(depthLeftOffsetX.value())
                .put(depthLeftOffsetY.value()))
            .put("right_offset_uv", new JSONArray()
                .put(depthRightOffsetX.value())
                .put(depthRightOffsetY.value()))
            .put("sample_scale", depthSampleScale.value());
        JSONObject apply = new JSONObject()
            .put("mode", "apply-on-next-safe-frame")
            .put("expected_effective_revision", -1);
        return new JSONObject()
            .put("schema", ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA)
            .put("profile_id", "same-apk-depth-alignment")
            .put("revision", System.currentTimeMillis())
            .put("source", source)
            .put("depth_alignment", depthAlignment)
            .put("apply", apply);
    }

    private void refreshDepthAlignmentFromStatus(boolean userVisible) {
        JSONObject statusJson = readDepthAlignmentStatusJson();
        if (statusJson == null) {
            if (userVisible) {
                updateStatus("Depth alignment status is not available yet.");
            } else {
                setStatusText("Depth alignment status is not available yet.");
            }
            return;
        }
        setDepthSliderValue(
            depthLeftOffsetX,
            readDepthAlignmentStatusOffset(statusJson, "left_offset_uv", 0, depthLeftOffsetX.value())
        );
        setDepthSliderValue(
            depthLeftOffsetY,
            readDepthAlignmentStatusOffset(statusJson, "left_offset_uv", 1, depthLeftOffsetY.value())
        );
        setDepthSliderValue(
            depthRightOffsetX,
            readDepthAlignmentStatusOffset(statusJson, "right_offset_uv", 0, depthRightOffsetX.value())
        );
        setDepthSliderValue(
            depthRightOffsetY,
            readDepthAlignmentStatusOffset(statusJson, "right_offset_uv", 1, depthRightOffsetY.value())
        );
        setDepthSliderValue(
            depthSampleScale,
            readDepthAlignmentStatusScale(statusJson, depthSampleScale.value())
        );
        String message = "Depth alignment refreshed: " + depthAlignmentSummary() + ".";
        if (userVisible) {
            updateStatus(message);
        } else {
            setStatusText(message);
        }
    }

    private void setDepthSliderValue(SliderControl slider, double value) {
        if (slider != null) {
            slider.setValue(value);
        }
    }

    private JSONObject readDepthAlignmentStatusJson() {
        try {
            String text = readFile(DEPTH_ALIGNMENT_STATUS_FILE);
            if (text.length() == 0) {
                return null;
            }
            JSONObject statusJson = new JSONObject(text);
            JSONObject depthAlignment = statusJson.optJSONObject("depth_alignment");
            if (depthAlignment == null) {
                return null;
            }
            return statusJson;
        } catch (Exception ignored) {
            return null;
        }
    }

    private double readDepthAlignmentStatusOffset(
        JSONObject statusJson,
        String key,
        int component,
        double fallback
    ) {
        if (statusJson == null) {
            return fallback;
        }
        JSONObject depthAlignment = statusJson.optJSONObject("depth_alignment");
        if (depthAlignment == null) {
            return fallback;
        }
        JSONArray offset = depthAlignment.optJSONArray(key);
        if (offset == null || component < 0 || component >= offset.length()) {
            return fallback;
        }
        return offset.optDouble(component, fallback);
    }

    private double readDepthAlignmentStatusScale(JSONObject statusJson, double fallback) {
        if (statusJson == null) {
            return fallback;
        }
        JSONObject depthAlignment = statusJson.optJSONObject("depth_alignment");
        if (depthAlignment == null) {
            return fallback;
        }
        return depthAlignment.optDouble("sample_scale", fallback);
    }

    private String depthAlignmentSummary() {
        return String.format(
            Locale.US,
            "L %.3f,%.3f R %.3f,%.3f S %.2f",
            depthLeftOffsetX.value(),
            depthLeftOffsetY.value(),
            depthRightOffsetX.value(),
            depthRightOffsetY.value(),
            depthSampleScale.value()
        );
    }

    private void schedulePrivateParticleDynamicsApplyFromControl() {
        if (liveAutoApply == null || !liveAutoApply.isChecked()) {
            return;
        }
        cancelPendingPrivateParticleDynamicsApply();
        pendingPrivateParticleDynamicsApply = new Runnable() {
            @Override
            public void run() {
                pendingPrivateParticleDynamicsApply = null;
                submitLivePrivateParticleDynamics(false);
            }
        };
        liveApplyHandler.postDelayed(pendingPrivateParticleDynamicsApply, 180);
        setStatusText("Particle dynamics update pending.");
    }

    private void cancelPendingPrivateParticleDynamicsApply() {
        if (liveApplyHandler != null && pendingPrivateParticleDynamicsApply != null) {
            liveApplyHandler.removeCallbacks(pendingPrivateParticleDynamicsApply);
            pendingPrivateParticleDynamicsApply = null;
        }
    }

    private void schedulePrivateParticleDepthWaveApplyFromControl() {
        if (liveAutoApply == null || !liveAutoApply.isChecked()) {
            return;
        }
        cancelPendingPrivateParticleDepthWaveApply();
        pendingPrivateParticleDepthWaveApply = new Runnable() {
            @Override
            public void run() {
                pendingPrivateParticleDepthWaveApply = null;
                submitLivePrivateParticleDepthWave(false);
            }
        };
        liveApplyHandler.postDelayed(pendingPrivateParticleDepthWaveApply, 180);
        setStatusText("Depth wave update pending.");
    }

    private void cancelPendingPrivateParticleDepthWaveApply() {
        if (liveApplyHandler != null && pendingPrivateParticleDepthWaveApply != null) {
            liveApplyHandler.removeCallbacks(pendingPrivateParticleDepthWaveApply);
            pendingPrivateParticleDepthWaveApply = null;
        }
    }

    private void schedulePrivateParticleConfigApplyFromControl() {
        if (liveAutoApply == null || !liveAutoApply.isChecked()) {
            return;
        }
        cancelPendingPrivateParticleConfigApply();
        pendingPrivateParticleConfigApply = new Runnable() {
            @Override
            public void run() {
                pendingPrivateParticleConfigApply = null;
                submitLivePrivateParticleConfig(false);
            }
        };
        liveApplyHandler.postDelayed(pendingPrivateParticleConfigApply, 180);
        setStatusText("AKD config update pending.");
    }

    private void cancelPendingPrivateParticleConfigApply() {
        if (liveApplyHandler != null && pendingPrivateParticleConfigApply != null) {
            liveApplyHandler.removeCallbacks(pendingPrivateParticleConfigApply);
            pendingPrivateParticleConfigApply = null;
        }
    }

    private void submitLivePrivateParticleDynamics(boolean userVisible) {
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            JSONObject candidate = buildPrivateParticleDynamicsJson();
            String responseText = nativeSubmitLivePrivateParticleDynamics(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            if (!"queued".equals(responseStatus)) {
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
        } catch (Exception error) {
            if (userVisible) {
                updateStatus("Particle dynamics failed: " + error.getMessage());
            } else {
                setStatusText("Particle dynamics update failed: " + error.getMessage());
            }
        }
    }

    private void submitLivePrivateParticleDepthWave(boolean userVisible) {
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            if (depthWaveDriverPolicy != null && depthWaveDriverPolicy.getSelectedItemPosition() != 0) {
                throw new IllegalStateException("selected driver policy requires a payload rebuild");
            }
            JSONObject candidate = buildPrivateParticleDepthWaveJson();
            String responseText = nativeSubmitLivePrivateParticleDynamics(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            if (!"queued".equals(responseStatus)) {
                throw new IllegalStateException(responseText);
            }
            String message = "Depth wave queued: " + depthWaveSummary() + ".";
            if (response.optBoolean("overwrote_pending", false)) {
                message = "Depth wave queued; older pending edit was replaced.";
            }
            if (userVisible) {
                updateStatus(message);
            } else {
                setStatusText(message);
            }
        } catch (Exception error) {
            if (userVisible) {
                updateStatus("Depth wave failed: " + error.getMessage());
            } else {
                setStatusText("Depth wave update failed: " + error.getMessage());
            }
        }
    }

    private void submitLivePrivateParticleConfig(boolean userVisible) {
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            JSONObject candidate = buildPrivateParticleConfigJson();
            String responseText = nativeSubmitLivePrivateParticleDynamics(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            if (!"queued".equals(responseStatus)) {
                throw new IllegalStateException(responseText);
            }
            String message = "AKD config queued: " + privateParticleConfigSummary() + ".";
            if (response.optBoolean("overwrote_pending", false)) {
                message = "AKD config queued; older pending edit was replaced.";
            }
            if (userVisible) {
                updateStatus(message);
            } else {
                setStatusText(message);
            }
        } catch (Exception error) {
            if (userVisible) {
                updateStatus("AKD config failed: " + error.getMessage());
            } else {
                setStatusText("AKD config update failed: " + error.getMessage());
            }
        }
    }

    private JSONObject buildPrivateParticleDynamicsJson() throws Exception {
        double[] drivers = new double[privateParticleDrivers.length];
        for (int i = 0; i < privateParticleDrivers.length; i++) {
            drivers[i] = privateParticleDrivers[i].value();
        }
        return buildPrivateParticleDynamicsJsonFromValues(
            "same-apk-private-particle-dynamics",
            "same_apk_panel",
            privateParticleVisualScale.value(),
            privateParticleWorldAnchorScale.value(),
            drivers,
            privateParticleTracerDrawSlots.intValue(),
            privateParticleTracerLifetime.value(),
            privateParticleTracerCopies.value()
        );
    }

    private JSONObject buildPrivateParticleDepthWaveJson() throws Exception {
        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        JSONObject tracerStatus = privateParticles == null
            ? null
            : privateParticles.optJSONObject("tracer");
        double visualScale = readPrivateParticleStatusDouble(
            privateParticles,
            "visual_scale",
            readDoubleProperty(PROP_PRIVATE_PARTICLE_VISUAL_SCALE, 0.70)
        );
        double worldAnchorScale = readPrivateParticleStatusDouble(
            privateParticles,
            "world_anchor_scale_m",
            readDoubleProperty(PROP_PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE, 2.0)
        );
        double[] drivers = privateParticleDriverValuesFromStatusOrProperties(privateParticles);
        drivers[DEPTH_WAVE_DRIVER_INDEX] = depthWaveDriverValue01.value();
        int tracerDrawSlots = (int) Math.round(readPrivateParticleStatusTracerDouble(
            tracerStatus,
            "draw_slots_per_oscillator",
            readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_DRAW_SLOTS, 7.0)
        ));
        double tracerLifetime = readPrivateParticleStatusTracerDouble(
            tracerStatus,
            "lifetime_seconds",
            readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_LIFETIME, 0.5)
        );
        double tracerCopies = readPrivateParticleStatusTracerDouble(
            tracerStatus,
            "copies_per_second",
            readDoubleProperty(PROP_PRIVATE_PARTICLE_TRACER_COPIES, 14.0)
        );
        return buildPrivateParticleDynamicsJsonFromValues(
            "same-apk-private-particle-depth-wave",
            "akd_depth_wave_panel",
            visualScale,
            worldAnchorScale,
            drivers,
            tracerDrawSlots,
            tracerLifetime,
            tracerCopies
        );
    }

    private JSONObject buildPrivateParticleConfigJson() throws Exception {
        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        double[] drivers = privateParticleDriverValuesFromStatusOrProperties(privateParticles);
        drivers[SPHERE_DEFORMATION_DRIVER_INDEX] = privateParticleConfigDeformationDriver.value();
        drivers[COUPLING_DRIVER_INDEX] = privateParticleConfigCouplingDriver.value();
        drivers[PARTICLE_SIZE_DRIVER_INDEX] = privateParticleConfigParticleSize.value();
        drivers[DEPTH_WAVE_DRIVER_INDEX] = privateParticleConfigDepthWavePercent.value();
        drivers[SPIN_SPEED_DRIVER_INDEX] = privateParticleConfigSpinSpeed.value();
        drivers[ORBIT_RADIUS_DRIVER_INDEX] = privateParticleConfigOrbitRadius.value();
        drivers[ORBIT_ANGLE_DRIVER_INDEX] = privateParticleConfigOrbitAngle.value();
        drivers[ANIMATION_FRAME_DRIVER_INDEX] = privateParticleConfigAnimationFrame.value();
        JSONObject candidate = buildPrivateParticleDynamicsJsonFromValues(
            "same-apk-private-particle-akd-config",
            "akd_config_panel",
            privateParticleConfigVisualScale.value(),
            privateParticleConfigWorldAnchorScale.value(),
            drivers,
            privateParticleConfigTracerDrawSlots.intValue(),
            privateParticleConfigTracerLifetime.value(),
            privateParticleConfigTracerCopies.value(),
            privateParticleTransparencyOpacity.value(),
            privateParticleTransparencyOutputAlphaScale.value(),
            privateParticleTransparencyDepthSuppression.value(),
            privateParticleTransparencyRgbAlphaCoupling.value(),
            privateParticleColorFacingAttenuation.value()
        );
        JSONObject privateParticlesJson = candidate.optJSONObject("private_particles");
        if (privateParticlesJson != null) {
            privateParticlesJson.put("driver_controls", buildPrivateParticleDriverControlsJson());
            privateParticlesJson.put("akd_config", buildPrivateParticleConfigMetadataJson());
        }
        return candidate;
    }

    private JSONArray buildPrivateParticleDriverControlsJson() throws Exception {
        JSONArray controls = new JSONArray();
        controls.put(driverControlDirect(
            SPHERE_DEFORMATION_DRIVER_INDEX,
            privateParticleConfigDeformationDriver.value()
        ));
        controls.put(driverControlDirect(
            COUPLING_DRIVER_INDEX,
            privateParticleConfigCouplingDriver.value()
        ));
        controls.put(driverControlForParameter(PARTICLE_SIZE_DRIVER_INDEX, "particle_size"));
        controls.put(driverControlForParameter(DEPTH_WAVE_DRIVER_INDEX, "depth_wave"));
        controls.put(driverControlForParameter(SPIN_SPEED_DRIVER_INDEX, "spin_speed"));
        controls.put(driverControlForParameter(ORBIT_RADIUS_DRIVER_INDEX, "orbit_radius"));
        controls.put(driverControlForParameter(ORBIT_ANGLE_DRIVER_INDEX, "orbit_angle"));
        controls.put(driverControlForParameter(ANIMATION_FRAME_DRIVER_INDEX, "animation_phase"));
        return controls;
    }

    private JSONObject driverControlDirect(int targetSlot, double value01) throws Exception {
        return new JSONObject()
            .put("target_slot", targetSlot)
            .put("mode", "direct")
            .put("mode_code", PRIVATE_PARTICLE_DRIVER_CONTROL_DIRECT)
            .put("source_slot", targetSlot)
            .put("curve", "linear")
            .put("curve_code", PRIVATE_PARTICLE_CURVE_LINEAR)
            .put("range_min", 0.0)
            .put("range_max", 1.0)
            .put("cycle_multiplier", 0.0)
            .put("value01", clamp(value01, 0.0, 1.0));
    }

    private JSONObject driverControlForParameter(int targetSlot, String parameterId) throws Exception {
        ParameterEnvelopeControl control = privateParticleConfigParameter(parameterId);
        if (control == null) {
            return driverControlDirect(targetSlot, 0.0);
        }
        int sourceSlot = control.driverSourceSlotIndex();
        if (sourceSlot < 0) {
            sourceSlot = targetSlot;
        }
        return new JSONObject()
            .put("target_slot", targetSlot)
            .put("mode", control.driverControlModeLabel())
            .put("mode_code", control.driverControlModeCode())
            .put("source_slot", sourceSlot)
            .put("curve", control.curveControlLabel())
            .put("curve_code", control.curveCode())
            .put("range_min", control.minValue())
            .put("range_max", control.maxValue())
            .put("cycle_multiplier", control.cycleMultiplier())
            .put("value01", clamp(control.liveValue(), 0.0, 1.0));
    }

    private JSONObject buildPrivateParticleConfigMetadataJson() throws Exception {
        JSONArray parameters = new JSONArray();
        for (int i = 0; i < privateParticleConfigParameterControls.size(); i++) {
            parameters.put(privateParticleConfigParameterControls.get(i).toJson());
        }
        return new JSONObject()
            .put("schema", "rusty.quest.native_renderer.private_particle_akd_config_panel.v1")
            .put("parameter_defaults_source", "akd-pe-oscillator-config")
            .put("driver_mode_default", "Oscillator")
            .put("curve_choices", new JSONArray(PRIVATE_PARTICLE_CURVE_CHOICES))
            .put("driver_mode_choices", new JSONArray(PRIVATE_PARTICLE_DRIVER_MODE_CHOICES))
            .put("parameters", parameters);
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
        JSONObject privateParticles = new JSONObject()
            .put("visual_scale", visualScale)
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
            if (userVisible) {
                updateStatus("Particle dynamics status is not available yet.");
            } else {
                setStatusText("Particle dynamics status is not available yet.");
            }
            return;
        }
        setSliderValue(
            privateParticleVisualScale,
            readPrivateParticleStatusDouble(privateParticles, "visual_scale", privateParticleVisualScale.value())
        );
        setSliderValue(
            privateParticleWorldAnchorScale,
            readPrivateParticleStatusDouble(
                privateParticles,
                "world_anchor_scale_m",
                privateParticleWorldAnchorScale.value()
            )
        );
        JSONArray driverStatus = privateParticles.optJSONArray("driver_values01");
        if (driverStatus != null) {
            for (int i = 0; i < privateParticleDrivers.length; i++) {
                setSliderValue(privateParticleDrivers[i], driverStatus.optDouble(i, privateParticleDrivers[i].value()));
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
        String message = "Particle dynamics refreshed: " + privateParticleDynamicsSummary() + ".";
        if (userVisible) {
            updateStatus(message);
        } else {
            setStatusText(message);
        }
    }

    private void refreshPrivateParticleDepthWaveFromStatus(boolean userVisible) {
        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        if (privateParticles == null) {
            if (userVisible) {
                updateStatus("Depth wave status is not available yet.");
            } else {
                setStatusText("Depth wave status is not available yet.");
            }
            return;
        }
        double driverValue = privateParticleDriverValueFromStatusOrProperty(
            privateParticles,
            DEPTH_WAVE_DRIVER_INDEX,
            0.0
        );
        setSliderValue(depthWaveDriverValue01, driverValue);
        setSliderValue(depthWavePercent, depthWavePercentForDriverValue01(driverValue));
        updateDepthWaveResolvedLabel();
        String message = "Depth wave refreshed: " + depthWaveSummary() + ".";
        if (userVisible) {
            updateStatus(message);
        } else {
            setStatusText(message);
        }
    }

    private void refreshPrivateParticleConfigFromStatus(boolean userVisible) {
        JSONObject statusJson = readPrivateParticleDynamicsStatusJson();
        JSONObject privateParticles = privateParticleStatusBody(statusJson);
        if (privateParticles == null) {
            if (userVisible) {
                updateStatus("AKD config status is not available yet.");
            } else {
                setStatusText("AKD config status is not available yet.");
            }
            return;
        }
        setSliderValue(
            privateParticleConfigVisualScale,
            readPrivateParticleStatusDouble(privateParticles, "visual_scale", privateParticleConfigVisualScale.value())
        );
        setSliderValue(
            privateParticleConfigWorldAnchorScale,
            clamp(
                readPrivateParticleStatusDouble(
                    privateParticles,
                    "world_anchor_scale_m",
                    privateParticleConfigWorldAnchorScale.value()
                ),
                AKD_SPHERE_RADIUS_MIN_M,
                AKD_SPHERE_RADIUS_MAX_M
            )
        );
        double[] drivers = privateParticleDriverValuesFromStatusOrProperties(privateParticles);
        setSliderValue(privateParticleConfigDeformationDriver, drivers[SPHERE_DEFORMATION_DRIVER_INDEX]);
        setSliderValue(privateParticleConfigCouplingDriver, drivers[COUPLING_DRIVER_INDEX]);
        setSliderValue(
            privateParticleConfigParticleSize,
            drivers[PARTICLE_SIZE_DRIVER_INDEX]
        );
        setSliderValue(
            privateParticleConfigDepthWavePercent,
            drivers[DEPTH_WAVE_DRIVER_INDEX]
        );
        setSliderValue(
            privateParticleConfigSpinSpeed,
            drivers[SPIN_SPEED_DRIVER_INDEX]
        );
        setSliderValue(
            privateParticleConfigOrbitRadius,
            drivers[ORBIT_RADIUS_DRIVER_INDEX]
        );
        setSliderValue(
            privateParticleConfigOrbitAngle,
            drivers[ORBIT_ANGLE_DRIVER_INDEX]
        );
        setSliderValue(
            privateParticleConfigAnimationFrame,
            drivers[ANIMATION_FRAME_DRIVER_INDEX]
        );
        JSONObject tracerStatus = privateParticles.optJSONObject("tracer");
        if (tracerStatus != null) {
            setSliderValue(
                privateParticleConfigTracerDrawSlots,
                readPrivateParticleStatusTracerDouble(
                    tracerStatus,
                    "draw_slots_per_oscillator",
                    privateParticleConfigTracerDrawSlots.value()
                )
            );
            setSliderValue(
                privateParticleConfigTracerLifetime,
                readPrivateParticleStatusTracerDouble(
                    tracerStatus,
                    "lifetime_seconds",
                    privateParticleConfigTracerLifetime.value()
                )
            );
            setSliderValue(
                privateParticleConfigTracerCopies,
                readPrivateParticleStatusTracerDouble(
                    tracerStatus,
                    "copies_per_second",
                    privateParticleConfigTracerCopies.value()
                )
            );
        }
        JSONObject transparencyStatus = privateParticles.optJSONObject("transparency");
        if (transparencyStatus != null) {
            setSliderValue(
                privateParticleTransparencyOpacity,
                readNestedPrivateParticleStatusDouble(
                    transparencyStatus,
                    "opacity",
                    privateParticleTransparencyOpacity.value()
                )
            );
            setSliderValue(
                privateParticleTransparencyOutputAlphaScale,
                readNestedPrivateParticleStatusDouble(
                    transparencyStatus,
                    "output_alpha_scale",
                    privateParticleTransparencyOutputAlphaScale.value()
                )
            );
            setSliderValue(
                privateParticleTransparencyDepthSuppression,
                readNestedPrivateParticleStatusDouble(
                    transparencyStatus,
                    "depth_suppression_strength",
                    privateParticleTransparencyDepthSuppression.value()
                )
            );
            setSliderValue(
                privateParticleTransparencyRgbAlphaCoupling,
                readNestedPrivateParticleStatusDouble(
                    transparencyStatus,
                    "rgb_alpha_coupling",
                    privateParticleTransparencyRgbAlphaCoupling.value()
                )
            );
        }
        JSONObject colorStatus = privateParticles.optJSONObject("color");
        if (colorStatus != null) {
            setSliderValue(
                privateParticleColorFacingAttenuation,
                readNestedPrivateParticleStatusDouble(
                    colorStatus,
                    "facing_attenuation_strength",
                    privateParticleColorFacingAttenuation.value()
                )
            );
        }
        updatePrivateParticleConfigResolvedLabel();
        String message = "AKD config refreshed: " + privateParticleConfigSummary() + ".";
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
            if (statusJson.optJSONObject("private_particles") == null) {
                return null;
            }
            return statusJson;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject privateParticleStatusBody(JSONObject statusJson) {
        if (statusJson == null) {
            return null;
        }
        return statusJson.optJSONObject("private_particles");
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

    private double driverValue01ForDepthWavePercent(double percent) {
        double target01 = (clamp(percent, DEPTH_WAVE_MIN_PERCENT, DEPTH_WAVE_MAX_PERCENT) -
            DEPTH_WAVE_MIN_PERCENT) / (DEPTH_WAVE_MAX_PERCENT - DEPTH_WAVE_MIN_PERCENT);
        return firstRisingBranchInputForAkdHump(target01);
    }

    private double depthWavePercentForDriverValue01(double value01) {
        double curveOutput = sampleAkdHump(clamp(value01, 0.0, 1.0));
        return DEPTH_WAVE_MIN_PERCENT + curveOutput * (DEPTH_WAVE_MAX_PERCENT - DEPTH_WAVE_MIN_PERCENT);
    }

    private double driverValue01ForHumpMappedValue(double value, double min, double max) {
        if (Math.abs(max - min) <= 0.000001) {
            return 0.0;
        }
        double target01 = (clamp(value, min, max) - min) / (max - min);
        return firstRisingBranchInputForAkdHump(target01);
    }

    private double humpMappedValueForDriver(double value01, double min, double max) {
        double curveOutput = sampleAkdHump(clamp(value01, 0.0, 1.0));
        return min + curveOutput * (max - min);
    }

    private double driverValue01ForLinearMappedValue(double value, double min, double max) {
        if (Math.abs(max - min) <= 0.000001) {
            return 0.0;
        }
        return (clamp(value, min, max) - min) / (max - min);
    }

    private double linearMappedValueForDriver(double value01, double min, double max) {
        return min + clamp(value01, 0.0, 1.0) * (max - min);
    }

    private double firstRisingBranchInputForAkdHump(double requestedOutput01) {
        double target = clamp(requestedOutput01, 0.0, 1.0);
        int peakIndex = 0;
        for (int i = 1; i < AKD_HUMP_SAMPLES01.length; i++) {
            if (AKD_HUMP_SAMPLES01[i] > AKD_HUMP_SAMPLES01[peakIndex]) {
                peakIndex = i;
            }
        }
        if (target >= AKD_HUMP_SAMPLES01[peakIndex]) {
            return (double) peakIndex / (double) (AKD_HUMP_SAMPLES01.length - 1);
        }
        for (int i = 0; i < peakIndex; i++) {
            double start = AKD_HUMP_SAMPLES01[i];
            double end = AKD_HUMP_SAMPLES01[i + 1];
            if (target >= start && target <= end) {
                double segment = end - start;
                double local = segment <= 0.000001 ? 0.0 : (target - start) / segment;
                return ((double) i + local) / (double) (AKD_HUMP_SAMPLES01.length - 1);
            }
        }
        return 0.0;
    }

    private double sampleAkdHump(double value01) {
        double scaled = clamp(value01, 0.0, 1.0) * (double) (AKD_HUMP_SAMPLES01.length - 1);
        int lower = (int) Math.floor(scaled);
        int upper = Math.min(AKD_HUMP_SAMPLES01.length - 1, lower + 1);
        double local = scaled - (double) lower;
        return AKD_HUMP_SAMPLES01[lower] * (1.0 - local) + AKD_HUMP_SAMPLES01[upper] * local;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private ParameterEnvelopeControl privateParticleConfigParameter(String id) {
        for (int i = 0; i < privateParticleConfigParameterControls.size(); i++) {
            ParameterEnvelopeControl control = privateParticleConfigParameterControls.get(i);
            if (control.id.equals(id)) {
                return control;
            }
        }
        return null;
    }

    private JSONObject driverControlForSlot(JSONArray driverControls, int targetSlot) {
        if (driverControls == null) {
            return null;
        }
        for (int i = 0; i < driverControls.length(); i++) {
            JSONObject control = driverControls.optJSONObject(i);
            if (control == null) {
                continue;
            }
            if (control.optInt("target_slot", i) == targetSlot) {
                return control;
            }
        }
        return null;
    }

    private double driverControlRangeMin(JSONArray driverControls, int targetSlot, double fallback) {
        JSONObject control = driverControlForSlot(driverControls, targetSlot);
        return control == null || driverControlIsDirect(control)
            ? fallback
            : control.optDouble("range_min", fallback);
    }

    private double driverControlRangeMax(JSONArray driverControls, int targetSlot, double fallback) {
        JSONObject control = driverControlForSlot(driverControls, targetSlot);
        return control == null || driverControlIsDirect(control)
            ? fallback
            : control.optDouble("range_max", fallback);
    }

    private int driverControlCycleMultiplier(JSONArray driverControls, int targetSlot, int fallback) {
        JSONObject control = driverControlForSlot(driverControls, targetSlot);
        if (control == null || driverControlIsDirect(control)) {
            return fallback;
        }
        return (int) Math.round(clamp(control.optDouble("cycle_multiplier", fallback), 0.0, 10.0));
    }

    private String driverControlCurveChoice(JSONArray driverControls, int targetSlot, String fallback) {
        JSONObject control = driverControlForSlot(driverControls, targetSlot);
        if (control == null || driverControlIsDirect(control)) {
            return fallback;
        }
        int curveCode = control.optInt("curve_code", -1);
        if (curveCode >= 0) {
            return curveChoiceForCode(curveCode, fallback);
        }
        String curve = control.optString("curve", "").trim().toLowerCase(Locale.US);
        if ("akd hump".equals(curve) || "akd-hump".equals(curve) || "hump".equals(curve)) {
            return "AKD hump";
        }
        if ("smoothstep".equals(curve)) {
            return "Smoothstep";
        }
        if ("reverse linear".equals(curve) || "reverse-linear".equals(curve)) {
            return "Reverse linear";
        }
        if ("hold low".equals(curve) || "hold-low".equals(curve)) {
            return "Hold low";
        }
        if ("hold high".equals(curve) || "hold-high".equals(curve)) {
            return "Hold high";
        }
        if ("linear".equals(curve)) {
            return "Linear";
        }
        return fallback;
    }

    private String curveChoiceForCode(int curveCode, String fallback) {
        switch (curveCode) {
            case PRIVATE_PARTICLE_CURVE_LINEAR:
                return "Linear";
            case PRIVATE_PARTICLE_CURVE_AKD_HUMP:
                return "AKD hump";
            case PRIVATE_PARTICLE_CURVE_SMOOTHSTEP:
                return "Smoothstep";
            case PRIVATE_PARTICLE_CURVE_REVERSE_LINEAR:
                return "Reverse linear";
            case PRIVATE_PARTICLE_CURVE_HOLD_LOW:
                return "Hold low";
            case PRIVATE_PARTICLE_CURVE_HOLD_HIGH:
                return "Hold high";
            default:
                return fallback;
        }
    }

    private String driverControlModeChoice(JSONArray driverControls, int targetSlot, String fallback) {
        JSONObject control = driverControlForSlot(driverControls, targetSlot);
        if (control == null) {
            return fallback;
        }
        int modeCode = control.optInt("mode_code", -1);
        if (modeCode == PRIVATE_PARTICLE_DRIVER_CONTROL_MANUAL) {
            return PRIVATE_PARTICLE_DRIVER_MODE_MANUAL;
        }
        if (modeCode == PRIVATE_PARTICLE_DRIVER_CONTROL_INPUT_SLOT) {
            return driverModeChoiceForInputSlot(control.optInt("source_slot", targetSlot));
        }
        if (modeCode == PRIVATE_PARTICLE_DRIVER_CONTROL_OSCILLATOR) {
            return "Oscillator";
        }
        String mode = control.optString("mode", "").trim().toLowerCase(Locale.US);
        if ("manual".equals(mode)) {
            return PRIVATE_PARTICLE_DRIVER_MODE_MANUAL;
        }
        if ("input-slot".equals(mode) || "input_slot".equals(mode) || "input slot".equals(mode)) {
            return driverModeChoiceForInputSlot(control.optInt("source_slot", targetSlot));
        }
        if ("oscillator".equals(mode)) {
            return "Oscillator";
        }
        return fallback;
    }

    private boolean driverControlIsDirect(JSONObject control) {
        int modeCode = control.optInt("mode_code", -1);
        if (modeCode == PRIVATE_PARTICLE_DRIVER_CONTROL_DIRECT) {
            return true;
        }
        return "direct".equals(control.optString("mode", "").trim().toLowerCase(Locale.US));
    }

    private String driverModeChoiceForInputSlot(int sourceSlot) {
        switch (sourceSlot) {
            case 0:
                return "Input slot 0: deformation";
            case 1:
                return "Input slot 1: coupling";
            case 2:
                return "Input slot 2: particle size";
            case 3:
                return "Input slot 3: depth wave";
            case 4:
                return "Input slot 4: spin speed";
            case 5:
                return "Input slot 5: orbit radius";
            case 6:
                return "Input slot 6: orbit angle";
            case 7:
                return "Input slot 7: animation";
            default:
                return "Oscillator";
        }
    }

    private double configParameterMin(String id, double fallback) {
        ParameterEnvelopeControl control = privateParticleConfigParameter(id);
        return control == null ? fallback : control.minValue();
    }

    private double configParameterMax(String id, double fallback) {
        ParameterEnvelopeControl control = privateParticleConfigParameter(id);
        return control == null ? fallback : control.maxValue();
    }

    private double configParameterResolvedValue(
        String id,
        double sourceValue01,
        double fallbackMin,
        double fallbackMax
    ) {
        ParameterEnvelopeControl control = privateParticleConfigParameter(id);
        double min = control == null ? fallbackMin : control.minValue();
        double max = control == null ? fallbackMax : control.maxValue();
        double curveOutput = control == null
            ? clamp(sourceValue01, 0.0, 1.0)
            : sampleConfigCurve(control.curveChoice(), sourceValue01);
        return min + curveOutput * (max - min);
    }

    private double sampleConfigCurve(String curveChoice, double sourceValue01) {
        double value = clamp(sourceValue01, 0.0, 1.0);
        if ("AKD hump".equals(curveChoice)) {
            return Math.sin(Math.PI * value);
        }
        if ("Smoothstep".equals(curveChoice)) {
            return value * value * (3.0 - 2.0 * value);
        }
        if ("Reverse linear".equals(curveChoice)) {
            return 1.0 - value;
        }
        if ("Hold low".equals(curveChoice)) {
            return 0.0;
        }
        if ("Hold high".equals(curveChoice)) {
            return 1.0;
        }
        return value;
    }

    private void updateDepthWaveResolvedLabel() {
        if (depthWaveResolvedLabel == null || depthWaveDriverValue01 == null || depthWavePercent == null) {
            return;
        }
        double driver = depthWaveDriverValue01.value();
        double curveOutput = sampleAkdHump(driver);
        double effectivePercent = depthWavePercentForDriverValue01(driver);
        depthWaveResolvedLabel.setText(String.format(
            Locale.US,
            "Resolved: driver3 %.3f -> curve %.3f -> %.4f depth wave percent",
            driver,
            curveOutput,
            effectivePercent
        ));
    }

    private String depthWaveSummary() {
        return String.format(
            Locale.US,
            "%.4f percent via driver3 %.3f",
            depthWavePercent.value(),
            depthWaveDriverValue01.value()
        );
    }

    private void updatePrivateParticleConfigResolvedLabel() {
        if (privateParticleConfigResolvedLabel == null ||
            privateParticleConfigParticleSize == null ||
            privateParticleConfigDepthWavePercent == null ||
            privateParticleConfigTracerDrawSlots == null) {
            return;
        }
        double resolvedSize = configParameterResolvedValue(
            "particle_size",
            privateParticleConfigParticleSize.value(),
            AKD_PARTICLE_SIZE_MIN,
            AKD_PARTICLE_SIZE_MAX
        );
        double resolvedDepthWave = configParameterResolvedValue(
            "depth_wave",
            privateParticleConfigDepthWavePercent.value(),
            DEPTH_WAVE_MIN_PERCENT,
            DEPTH_WAVE_MAX_PERCENT
        );
        double resolvedSpin = configParameterResolvedValue(
            "spin_speed",
            privateParticleConfigSpinSpeed.value(),
            AKD_SPIN_SPEED_MIN,
            AKD_SPIN_SPEED_MAX
        );
        double resolvedOrbitRadius = configParameterResolvedValue(
            "orbit_radius",
            privateParticleConfigOrbitRadius.value(),
            AKD_ORBIT_RADIUS_MIN,
            AKD_ORBIT_RADIUS_MAX
        );
        double resolvedOrbitAngle = configParameterResolvedValue(
            "orbit_angle",
            privateParticleConfigOrbitAngle.value(),
            AKD_ORBIT_ANGLE_MIN,
            AKD_ORBIT_ANGLE_MAX
        );
        double resolvedAnimation = configParameterResolvedValue(
            "animation_phase",
            privateParticleConfigAnimationFrame.value(),
            0.0,
            1.0
        );
        privateParticleConfigResolvedLabel.setText(String.format(
            Locale.US,
            "Resolved GPU drivers: d0 %.3f, d1 %.3f, size d2 %.3f, depth d3 %.3f, spin d4 %.3f, orbit d5 %.3f, angle d6 %.3f, anim d7 %.3f; tracers %d",
            privateParticleConfigDeformationDriver.value(),
            privateParticleConfigCouplingDriver.value(),
            driverValue01ForHumpMappedValue(
                resolvedSize,
                AKD_PARTICLE_SIZE_MIN,
                AKD_PARTICLE_SIZE_MAX
            ),
            driverValue01ForDepthWavePercent(resolvedDepthWave),
            driverValue01ForHumpMappedValue(
                resolvedSpin,
                AKD_SPIN_SPEED_MIN,
                AKD_SPIN_SPEED_MAX
            ),
            driverValue01ForHumpMappedValue(
                resolvedOrbitRadius,
                AKD_ORBIT_RADIUS_MIN,
                AKD_ORBIT_RADIUS_MAX
            ),
            driverValue01ForLinearMappedValue(
                resolvedOrbitAngle,
                AKD_ORBIT_ANGLE_MIN,
                AKD_ORBIT_ANGLE_MAX
            ),
            driverValue01ForHumpMappedValue(resolvedAnimation, 0.0, 1.0),
            privateParticleConfigTracerDrawSlots.intValue()
        ));
    }

    private String privateParticleConfigSummary() {
        return String.format(
            Locale.US,
            "sphere %.2fm, size %.3fm, depth %.4f, orbit %.2f, alpha %.2f",
            privateParticleConfigWorldAnchorScale.value(),
            configParameterResolvedValue(
                "particle_size",
                privateParticleConfigParticleSize.value(),
                AKD_PARTICLE_SIZE_MIN,
                AKD_PARTICLE_SIZE_MAX
            ),
            configParameterResolvedValue(
                "depth_wave",
                privateParticleConfigDepthWavePercent.value(),
                DEPTH_WAVE_MIN_PERCENT,
                DEPTH_WAVE_MAX_PERCENT
            ),
            configParameterResolvedValue(
                "orbit_radius",
                privateParticleConfigOrbitRadius.value(),
                AKD_ORBIT_RADIUS_MIN,
                AKD_ORBIT_RADIUS_MAX
            ),
            privateParticleTransparencyOutputAlphaScale.value()
        );
    }

    private String privateParticleDynamicsSummary() {
        return String.format(
            Locale.US,
            "scale %.2f m, d0 %.2f, d1 %.2f, tracers %d",
            privateParticleWorldAnchorScale.value(),
            privateParticleDrivers[0].value(),
            privateParticleDrivers[1].value(),
            privateParticleTracerDrawSlots.intValue()
        );
    }

    private JSONObject buildPrivateLayerSelectionJson(int layerIndex) throws Exception {
        if (layerIndex < 0 || layerIndex > 6) {
            throw new IllegalArgumentException("layer index must be 0-6");
        }
        JSONObject source = new JSONObject()
            .put("surface", "same_apk_panel")
            .put("transport", "jni_live_queue");
        JSONObject privateLayer = new JSONObject()
            .put("layer_override", layerIndex)
            .put("layer_label", privateLayerLabel(layerIndex));
        JSONObject apply = new JSONObject()
            .put("mode", "apply-on-next-safe-frame")
            .put("expected_effective_revision", -1);
        return new JSONObject()
            .put("schema", PRIVATE_LAYER_SELECTION_SCHEMA)
            .put("profile_id", "same-apk-private-layer-selector")
            .put("revision", System.currentTimeMillis())
            .put("source", source)
            .put("private_layer", privateLayer)
            .put("apply", apply);
    }

    private int readPrivateLayerOverride() {
        double requested = readDoubleProperty(PROP_PRIVATE_LAYER_OVERRIDE, 0.0);
        int layerIndex = (int) Math.round(requested);
        if (layerIndex < 0 || layerIndex > 6) {
            return 0;
        }
        return layerIndex;
    }

    private String privateLayerLabel(int layerIndex) {
        switch (layerIndex) {
            case 0:
                return "final";
            case 1:
                return "raw-brightness";
            case 2:
                return "preblur-brightness";
            case 3:
                return "raw-strength";
            case 4:
                return "blurred-strength";
            case 5:
                return "displacement";
            case 6:
                return "depth-gradient";
            default:
                return "unknown";
        }
    }

    private View previewBand() {
        TextView preview = text("depth ramp volume", 13, Color.WHITE);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {
                Color.BLACK,
                Color.rgb(0, 255, 255),
                Color.rgb(255, 0, 180),
                Color.rgb(255, 230, 0),
                Color.BLACK
            }
        );
        background.setCornerRadius(dp(3));
        preview.setBackground(background);
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        params.setMargins(0, dp(12), 0, dp(12));
        preview.setLayoutParams(params);
        return preview;
    }

    private GridLayout buildChoiceGrid(final boolean patternGrid, String[][] choices) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        ArrayList<Button> buttons = new ArrayList<Button>();
        for (int i = 0; i < choices.length; i++) {
            Button choice = button(choices[i][0]);
            choice.setTag(choices[i][1]);
            choice.setMinHeight(dp(42));
            choice.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (patternGrid) {
                        selectedPatternFamily = String.valueOf(view.getTag());
                        updateChoiceButtons(patternButtons, selectedPatternFamily);
                    } else {
                        selectedMirrorMode = String.valueOf(view.getTag());
                        updateChoiceButtons(mirrorButtons, selectedMirrorMode);
                    }
                    scheduleLiveApplyFromControl();
                }
            });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(choice, params);
            buttons.add(choice);
        }
        if (patternGrid) {
            patternButtons = buttons.toArray(new Button[buttons.size()]);
            updateChoiceButtons(patternButtons, selectedPatternFamily);
        } else {
            mirrorButtons = buttons.toArray(new Button[buttons.size()]);
            updateChoiceButtons(mirrorButtons, selectedMirrorMode);
        }
        return grid;
    }

    private View buildActionRow() {
        LinearLayout actionBlock = new LinearLayout(this);
        actionBlock.setOrientation(LinearLayout.VERTICAL);
        actionBlock.setPadding(0, dp(14), 0, dp(10));

        Button validate = button("Validate");
        validate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    buildCandidateJson("validate-only");
                    writeStatus("validated_by_panel");
                    updateStatus("Panel validation passed.");
                } catch (Exception error) {
                    updateStatus("Panel validation failed: " + error.getMessage());
                }
            }
        });
        Button stage = button("Stage");
        stage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stageCandidate(false);
            }
        });
        Button applyLive = button("Apply Live");
        applyLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitLiveCandidate(true);
            }
        });
        Button stageLaunch = button("Stage + Launch VR");
        stageLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stageCandidate(true);
            }
        });
        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.addView(validate, rowButtonParams());
        firstRow.addView(applyLive, rowButtonParams());
        firstRow.addView(stage, rowButtonParams());
        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        secondRow.addView(stageLaunch, rowButtonParams());
        secondRow.addView(close, rowButtonParams());
        actionBlock.addView(firstRow);
        actionBlock.addView(secondRow);
        return actionBlock;
    }

    private LinearLayout.LayoutParams rowButtonParams() {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private void updateChoiceButtons(Button[] buttons, String selectedValue) {
        for (int i = 0; i < buttons.length; i++) {
            boolean selected = selectedValue.equals(String.valueOf(buttons[i].getTag()));
            styleButton(buttons[i], selected);
        }
    }

    private void stageCandidate(boolean launchAfterStage) {
        try {
            JSONObject candidate = buildCandidateJson("stage");
            writeFile(CANDIDATE_FILE, candidate.toString(2));
            writeStatus("staged_by_panel");
            updateStatus("Candidate staged.");
            if (launchAfterStage) {
                launchImmersiveRenderer();
            }
        } catch (Exception error) {
            updateStatus("Stage failed: " + error.getMessage());
        }
    }

    private JSONObject buildCandidateJson(String applyMode) throws Exception {
        boolean active = enabledRequested.isChecked();
        boolean acknowledged = safetyAck.isChecked();
        if (active && !acknowledged) {
            throw new IllegalArgumentException("acknowledgement is required before requesting active stimulus");
        }
        double min = minHz.value();
        double max = maxHz.value();
        if (min < 3.0 || max > 40.0 || min > max) {
            throw new IllegalArgumentException("randomize Hz must stay within 3.0-40.0 and min <= max");
        }

        JSONObject source = new JSONObject()
            .put("surface", "same_apk_panel")
            .put("transport", "app_private_file");
        JSONObject safety = new JSONObject()
            .put("photosensitive_risk_ack", acknowledged)
            .put("requires_user_activation", true)
            .put("allow_autostart", false)
            .put("black_lead_in_seconds", 1.0)
            .put("max_duration_seconds", 30.0);
        JSONObject randomize = new JSONObject()
            .put("enabled", randomizeEnabled.isChecked())
            .put("min_hz", min)
            .put("max_hz", max);
        JSONObject stimulus = new JSONObject()
            .put("enabled_requested", active)
            .put("composition", "opaque-black-projection")
            .put("render_target", selected(renderTarget))
            .put("raymarch_samples", raymarchSamples.intValue())
            .put("central_fov_fraction", centralFovFraction.value())
            .put("gradient_smoothing", gradientSmoothing.value())
            .put("pattern_family", selectedPatternFamily)
            .put("randomize", randomize)
            .put("dynamics", buildDynamicsJson());
        JSONObject apply = new JSONObject()
            .put("mode", applyMode)
            .put("expected_effective_revision", -1);
        return new JSONObject()
            .put("schema", PROFILE_SCHEMA)
            .put("profile_id", "same-apk-panel")
            .put("revision", System.currentTimeMillis())
            .put("source", source)
            .put("safety", safety)
            .put("stimulus", stimulus)
            .put("apply", apply);
    }

    private void scheduleLiveApplyFromControl() {
        if (liveAutoApply == null || !liveAutoApply.isChecked()) {
            return;
        }
        cancelPendingLiveApply();
        pendingLiveApply = new Runnable() {
            @Override
            public void run() {
                pendingLiveApply = null;
                submitLiveCandidate(false);
            }
        };
        liveApplyHandler.postDelayed(pendingLiveApply, 180);
        setStatusText("Live auto update pending.");
    }

    private void cancelPendingLiveApply() {
        if (liveApplyHandler != null && pendingLiveApply != null) {
            liveApplyHandler.removeCallbacks(pendingLiveApply);
            pendingLiveApply = null;
        }
    }

    private void submitLiveCandidate(boolean userVisible) {
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            JSONObject candidate = buildCandidateJson("apply-on-next-safe-frame");
            String responseText = nativeSubmitLiveStimulusCandidate(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            if (!"queued".equals(responseStatus)) {
                throw new IllegalStateException(responseText);
            }
            String message = "Live candidate queued for next safe frame.";
            if (response.optBoolean("overwrote_pending", false)) {
                message = "Live candidate queued; older pending edit was replaced.";
            }
            if (userVisible) {
                updateStatus(message);
            } else {
                setStatusText(message);
            }
        } catch (Exception error) {
            if (userVisible) {
                updateStatus("Live apply failed: " + error.getMessage());
            } else {
                setStatusText("Live auto update failed: " + error.getMessage());
            }
        }
    }

    private void handleDiagnosticIntent(Intent intent) {
        if (intent == null || !ACTION_APPLY_LIVE_SELF_TEST.equals(intent.getAction())) {
            return;
        }
        if (!"stimulus-volume".equals(readControlPanelMode())) {
            setStatusText("Stimulus diagnostic self-test ignored in this panel mode.");
            return;
        }
        String token = intent.getAction() + ":" + intent.getLongExtra("diagnostic_token", 0L);
        if (token.equals(handledDiagnosticIntentToken)) {
            return;
        }
        handledDiagnosticIntentToken = token;
        if (safetyAck != null) {
            safetyAck.setChecked(true);
        }
        if (enabledRequested != null) {
            enabledRequested.setChecked(true);
        }
        if (randomizeEnabled != null) {
            randomizeEnabled.setChecked(true);
        }
        cancelPendingLiveApply();
        liveApplyHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                submitLiveCandidate(true);
            }
        }, 120);
        setStatusText("Diagnostic Apply Live self-test pending.");
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
        String panelMode = readControlPanelMode();
        if (!"polar-sensor".equals(panelMode) && !"kuramoto-mesh".equals(panelMode)) {
            setStatusText("Polar command ignored; panel mode does not expose Polar controls.");
            return;
        }
        if ("kuramoto-mesh".equals(panelMode)) {
            setContentView(buildPolarSensorPanelPageView(true));
        } else {
            ensurePolarSensorPanel();
        }
        polarSensorPanel.handleCommand(
            intent.getStringExtra(EXTRA_POLAR_SENSOR_PANEL_COMMAND)
        );
    }

    private void handleKuramotoMeshPanelCommandIntent(Intent intent) {
        if (intent == null || !ACTION_KURAMOTO_MESH_PANEL_COMMAND.equals(intent.getAction())) {
            return;
        }
        String token = intent.getStringExtra(EXTRA_KURAMOTO_PANEL_COMMAND_TOKEN);
        if (token == null || token.length() == 0) {
            token = intent.toUri(0);
        }
        if (token.equals(handledKuramotoMeshPanelCommandToken)) {
            return;
        }
        handledKuramotoMeshPanelCommandToken = token;
        if (!"kuramoto-mesh".equals(readControlPanelMode())) {
            setStatusText("Kuramoto command ignored; panel is not active.");
            kuramotoMarker("status=cli-command-ignored reason=panel-not-active");
            return;
        }
        if (kuramotoSurfaceTarget == null || kuramotoCondition == null) {
            setContentView(buildKuramotoMeshPanelView());
        }
        boolean previousAutoApply = kuramotoPanelAutoApplyArmed;
        kuramotoPanelAutoApplyArmed = false;
        String requestedSurface = intent.getStringExtra(EXTRA_KURAMOTO_SURFACE_TARGET);
        if (requestedSurface != null && requestedSurface.length() > 0) {
            kuramotoSurfaceTarget.setSelection(
                indexOf(KURAMOTO_SURFACE_IDS, requestedSurface, selectedKuramotoSurfaceIndex())
            );
        }
        String requestedCondition = intent.getStringExtra(EXTRA_KURAMOTO_CONDITION);
        if (requestedCondition != null && requestedCondition.length() > 0) {
            kuramotoCondition.setSelection(
                indexOf(KURAMOTO_CONDITION_IDS, requestedCondition, selectedKuramotoConditionIndex())
            );
        }
        kuramotoPanelAutoApplyArmed = previousAutoApply;
        updateKuramotoSelectionSummary();
        kuramotoMarker(
            "status=cli-command surfaceTarget="
                + KURAMOTO_SURFACE_IDS[selectedKuramotoSurfaceIndex()]
                + " condition="
                + KURAMOTO_CONDITION_IDS[selectedKuramotoConditionIndex()]
        );
        submitLiveKuramotoMeshPanelSelection(true);
        boolean returnToImmersive = intent.getBooleanExtra(
            EXTRA_KURAMOTO_RETURN_TO_IMMERSIVE,
            "real-hands".equals(KURAMOTO_SURFACE_IDS[selectedKuramotoSurfaceIndex()])
        );
        if (returnToImmersive) {
            kuramotoMarker(
                "status=cli-command-return-to-immersive surfaceTarget="
                    + KURAMOTO_SURFACE_IDS[selectedKuramotoSurfaceIndex()]
            );
            closePanelAndReturnToImmersive();
        }
    }

    private JSONObject buildDynamicsJson() throws Exception {
        return new JSONObject()
            .put("mirror_mode", selectedMirrorMode)
            .put("temporal_frequency_hz", temporalHz.value())
            .put("spatial_oscillator_hz", new JSONArray()
                .put(oscillatorAHz.value())
                .put(oscillatorBHz.value())
                .put(oscillatorCHz.value()))
            .put("spatial_frequency_scale", spatialScale.value())
            .put("source_shift", new JSONArray().put(sourceShiftX.value()).put(sourceShiftY.value()))
            .put("noise_scale", noiseScale.value())
            .put("depth_warp", depthWarp.value())
            .put("twist", twist.value())
            .put("pinch", pinch.value())
            .put("scramble", scramble.value())
            .put("jumble", jumble.value())
            .put("stretch", new JSONArray().put(stretchX.value()).put(stretchY.value()))
            .put("phase_offsets", new JSONArray()
                .put(phaseA.value())
                .put(phaseB.value())
                .put(phaseC.value()));
    }

    private void launchImmersiveRenderer() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(getPackageName(), "android.app.NativeActivity"));
        intent.addCategory("com.oculus.intent.category.VR");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void closePanelAndReturnToImmersive() {
        launchImmersiveRenderer();
        if (Build.VERSION.SDK_INT >= 21) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    private void writeStatus(String panelStatus) throws Exception {
        JSONObject body = new JSONObject()
            .put("schema", "rusty.quest.stimulus_volume.apply_status.v1")
            .put("status", panelStatus)
            .put("candidate_file", CANDIDATE_FILE)
            .put("transport", "app_private_file")
            .put("updated_at_unix_ms", System.currentTimeMillis());
        writeFile(STATUS_FILE, body.toString(2));
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

    private static void kuramotoMarker(String detail) {
        Log.i(
            TAG,
            MARKER_PREFIX
                + " channel="
                + CHANNEL_KURAMOTO_PANEL
                + " "
                + String.valueOf(detail).replace('\n', ' ').replace('\r', ' ')
        );
    }

    private static String markerToken(String raw) {
        if (raw == null || raw.length() == 0) {
            return "none";
        }
        return raw.replaceAll("[^A-Za-z0-9._=:/-]+", "_");
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

    private FoldoutControl foldout(String title, boolean expanded) {
        return new FoldoutControl(title, expanded);
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
            new Runnable() {
                @Override
                public void run() {
                    scheduleLiveApplyFromControl();
                }
            }
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
        String requested = readSystemProperty(PROP_CONTROL_PANEL_MODE);
        if ("private-layer-selector".equals(requested)) {
            return requested;
        }
        if ("private-particle-dynamics".equals(requested)) {
            return requested;
        }
        if ("private-particle-depth-wave".equals(requested)) {
            return requested;
        }
        if ("private-particle-config".equals(requested)) {
            return requested;
        }
        if ("kuramoto-mesh".equals(requested)) {
            return requested;
        }
        if ("polar-sensor".equals(requested)) {
            return requested;
        }
        return "stimulus-volume";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class FoldoutControl {
        final LinearLayout view;
        final LinearLayout body;
        final Button header;
        final String title;
        boolean expanded;

        FoldoutControl(String title, boolean expanded) {
            this.title = title;
            this.expanded = expanded;
            this.view = new LinearLayout(ControlPanelActivity.this);
            this.view.setOrientation(LinearLayout.VERTICAL);
            this.view.setPadding(0, dp(8), 0, dp(4));
            this.header = button("");
            this.body = new LinearLayout(ControlPanelActivity.this);
            this.body.setOrientation(LinearLayout.VERTICAL);
            this.body.setPadding(dp(12), dp(4), 0, dp(4));
            this.header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    FoldoutControl.this.expanded = !FoldoutControl.this.expanded;
                    refresh();
                }
            });
            this.view.addView(this.header);
            this.view.addView(this.body);
            refresh();
        }

        void refresh() {
            header.setText((expanded ? "- " : "+ ") + title);
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
    }

    private final class ParameterEnvelopeControl {
        final String id;
        final String title;
        final String rangeLabel;
        final FoldoutControl foldout;
        final LinearLayout view;
        final CheckBox option;
        final SliderControl minSlider;
        final SliderControl maxSlider;
        final Spinner curveSpinner;
        final Spinner driverModeSpinner;
        final SliderControl cycleSlider;
        final SliderControl liveValueSlider;

        ParameterEnvelopeControl(
            String id,
            String title,
            String rangeLabel,
            double minValue,
            double maxValue,
            double controlMin,
            double controlMax,
            double liveValue,
            int liveSteps,
            String suffix,
            int cycleMultiplier,
            String curveChoice,
            String driverModeChoice,
            String optionLabel,
            boolean optionDefault
        ) {
            this.id = id;
            this.title = title;
            this.rangeLabel = rangeLabel;
            this.foldout = foldout(title, false);
            this.view = this.foldout.view;
            LinearLayout body = this.foldout.body;

            if (optionLabel != null && optionLabel.length() > 0) {
                this.option = checkBox(optionLabel, optionDefault);
                this.option.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        updatePrivateParticleConfigResolvedLabel();
                        schedulePrivateParticleConfigApplyFromControl();
                    }
                });
                body.addView(this.option);
            } else {
                this.option = null;
            }

            double lower = Math.min(controlMin, controlMax);
            double upper = Math.max(controlMin, controlMax);
            double initialLower = Math.min(minValue, maxValue);
            double initialUpper = Math.max(minValue, maxValue);
            this.minSlider = privateParticleConfigSlider(
                rangeLabel + " min",
                lower,
                upper,
                initialLower,
                1000,
                suffix,
                false
            );
            this.maxSlider = privateParticleConfigSlider(
                rangeLabel + " max",
                lower,
                upper,
                initialUpper,
                1000,
                suffix,
                false
            );
            body.addView(this.minSlider.view);
            body.addView(this.maxSlider.view);

            body.addView(label("Envelope curve"));
            this.curveSpinner = configSpinner(PRIVATE_PARTICLE_CURVE_CHOICES, curveChoice);
            body.addView(this.curveSpinner);

            body.addView(label("Driver mode"));
            this.driverModeSpinner = spinner(
                PRIVATE_PARTICLE_DRIVER_MODE_CHOICES,
                indexOf(PRIVATE_PARTICLE_DRIVER_MODE_CHOICES, driverModeChoice, 0)
            );
            body.addView(this.driverModeSpinner);

            this.cycleSlider = privateParticleConfigSlider(
                "Cycle multiplier",
                0.0,
                10.0,
                cycleMultiplier,
                10,
                "",
                true
            );
            this.liveValueSlider = privateParticleConfigSlider(
                "Driver value",
                0.0,
                1.0,
                clamp(liveValue, 0.0, 1.0),
                Math.max(liveSteps, 1000),
                "",
                false
            );
            body.addView(this.cycleSlider.view);
            body.addView(this.liveValueSlider.view);
            this.driverModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    refreshDriverValueEditableState();
                    if (privateParticleConfigViewBuilding) {
                        return;
                    }
                    updatePrivateParticleConfigResolvedLabel();
                    schedulePrivateParticleConfigApplyFromControl();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            refreshDriverValueEditableState();
        }

        double minValue() {
            return Math.min(minSlider.value(), maxSlider.value());
        }

        double maxValue() {
            return Math.max(minSlider.value(), maxSlider.value());
        }

        double liveValue() {
            return liveValueSlider.value();
        }

        int cycleMultiplier() {
            return cycleSlider.intValue();
        }

        String curveChoice() {
            return spinnerValue(curveSpinner, "Linear");
        }

        String driverMode() {
            return spinnerValue(driverModeSpinner, "Oscillator");
        }

        boolean driverValueEditable() {
            return PRIVATE_PARTICLE_DRIVER_MODE_MANUAL.equals(driverMode());
        }

        int driverControlModeCode() {
            if (PRIVATE_PARTICLE_DRIVER_MODE_MANUAL.equals(driverMode())) {
                return PRIVATE_PARTICLE_DRIVER_CONTROL_MANUAL;
            }
            if (driverSourceSlotIndex() >= 0) {
                return PRIVATE_PARTICLE_DRIVER_CONTROL_INPUT_SLOT;
            }
            return PRIVATE_PARTICLE_DRIVER_CONTROL_OSCILLATOR;
        }

        String driverControlModeLabel() {
            int modeCode = driverControlModeCode();
            if (modeCode == PRIVATE_PARTICLE_DRIVER_CONTROL_MANUAL) {
                return "manual";
            }
            if (modeCode == PRIVATE_PARTICLE_DRIVER_CONTROL_INPUT_SLOT) {
                return "input-slot";
            }
            return "oscillator";
        }

        int driverSourceSlotIndex() {
            String mode = driverMode();
            for (int i = 0; i < 8; i++) {
                if (mode.startsWith("Input slot " + i + ":")) {
                    return i;
                }
            }
            return -1;
        }

        void refreshDriverValueEditableState() {
            if (liveValueSlider != null) {
                liveValueSlider.setInteractive(driverValueEditable());
            }
        }

        int curveCode() {
            String curve = curveChoice();
            if ("AKD hump".equals(curve)) {
                return PRIVATE_PARTICLE_CURVE_AKD_HUMP;
            }
            if ("Smoothstep".equals(curve)) {
                return PRIVATE_PARTICLE_CURVE_SMOOTHSTEP;
            }
            if ("Reverse linear".equals(curve)) {
                return PRIVATE_PARTICLE_CURVE_REVERSE_LINEAR;
            }
            if ("Hold low".equals(curve)) {
                return PRIVATE_PARTICLE_CURVE_HOLD_LOW;
            }
            if ("Hold high".equals(curve)) {
                return PRIVATE_PARTICLE_CURVE_HOLD_HIGH;
            }
            return PRIVATE_PARTICLE_CURVE_LINEAR;
        }

        String curveControlLabel() {
            int curveCode = curveCode();
            if (curveCode == PRIVATE_PARTICLE_CURVE_AKD_HUMP) {
                return "akd-hump";
            }
            if (curveCode == PRIVATE_PARTICLE_CURVE_SMOOTHSTEP) {
                return "smoothstep";
            }
            if (curveCode == PRIVATE_PARTICLE_CURVE_REVERSE_LINEAR) {
                return "reverse-linear";
            }
            if (curveCode == PRIVATE_PARTICLE_CURVE_HOLD_LOW) {
                return "hold-low";
            }
            if (curveCode == PRIVATE_PARTICLE_CURVE_HOLD_HIGH) {
                return "hold-high";
            }
            return "linear";
        }

        boolean optionValue() {
            return option != null && option.isChecked();
        }

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("range_label", rangeLabel)
                .put("min", minValue())
                .put("max", maxValue())
                .put("curve", curveChoice())
                .put("curve_code", curveCode())
                .put("driver_mode", driverMode())
                .put("driver_mode_code", driverControlModeCode())
                .put("cycle_multiplier", cycleMultiplier())
                .put("driver_value", liveValue())
                .put("driver_value_editable", driverValueEditable())
                .put("live_driver_value", liveValue());
            int sourceSlot = driverSourceSlotIndex();
            if (sourceSlot >= 0) {
                object.put("driver_source_slot", sourceSlot);
            }
            if (option != null) {
                object.put("option_enabled", optionValue());
            }
            return object;
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
            this.view = new LinearLayout(ControlPanelActivity.this);
            this.view.setOrientation(LinearLayout.VERTICAL);
            this.view.setPadding(0, dp(6), 0, dp(4));
            this.valueLabel = text("", 13, PANEL_FG);
            this.seekBar = new SeekBar(ControlPanelActivity.this);
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

    private static native String nativeSubmitLiveStimulusCandidate(String candidateJson);
    private static native String nativeSubmitLivePrivateLayerSelection(String selectionJson);
    private static native String nativeSubmitLiveDepthAlignment(String alignmentJson);
    private static native String nativeSubmitLivePrivateParticleDynamics(String dynamicsJson);
}
