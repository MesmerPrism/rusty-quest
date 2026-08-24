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
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
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
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ControlPanelActivity extends Activity {
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static final String CHANNEL_DRIVER_PROFILE_PANEL = "driver-profile-panel";
    private static final String LSL_PANEL_COMMAND_SCHEMA =
        "rusty.quest.native_renderer.lsl.panel_command.v1";
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
    public static final String ACTION_DRIVER_PROFILE_PANEL_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.DRIVER_PROFILE_PANEL_COMMAND";
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
    public static final String EXTRA_DRIVER_PROFILE_SURFACE_TARGET = "driver_profile_surface_target";
    public static final String EXTRA_DRIVER_PROFILE_ID = "driver_profile_id";
    public static final String EXTRA_DRIVER_PROFILE_RETURN_TO_IMMERSIVE =
        "driver_profile_return_to_immersive";
    public static final String EXTRA_DRIVER_PROFILE_PANEL_COMMAND_TOKEN =
        "driver_profile_panel_command_token";
    public static final String EXTRA_DRIVER_PROFILE_SESSION_STARTUP_RESET =
        "spatial_camera_panel_session_startup_reset";
    private static final int REQUEST_DISPLAY_COMPOSITE_CAPTURE = 7401;
    private static final String CANDIDATE_FILE = "stimulus_volume_candidate.json";
    private static final String STATUS_FILE = "stimulus_volume_status.json";
    private static final String DEPTH_ALIGNMENT_STATUS_FILE = "depth_alignment_status.json";
    private static final String PRIVATE_PARTICLE_DYNAMICS_STATUS_FILE =
        "private_particle_dynamics_status.json";
    private static final String RENDERER_FOCUS_STATUS_FILE = "renderer_focus_state.json";
    private static final long RENDERER_RETURN_POLL_MS = 250L;
    private static final long RENDERER_RETURN_RELAUNCH_MS = 1000L;
    private static final long RENDERER_RETURN_TIMEOUT_MS = 4000L;
    private static final long RENDERER_RETURN_STABLE_FOCUS_MS = 750L;
    private static final long RENDERER_FOCUS_FRESH_MS = 2000L;
    private static final String DRIVER_PROFILE_PANEL_STATUS_FILE =
        "driver_profile_panel_status.json";
    private static final String BREATH_COMPOSITION_OPERATOR_STATUS_FILE =
        "breath_composition_operator_status.json";
    private static final String POLAR_SENSOR_OPERATOR_STATUS_FILE =
        "polar_sensor_operator_status.json";
    private static final String POLAR_SENSOR_STATUS_FILE = "polar_sensor_status.json";
    private static final String PROFILE_SCHEMA = "rusty.quest.stimulus_volume.profile.v1";
    private static final String PRIVATE_LAYER_SELECTION_SCHEMA =
        "rusty.quest.native_renderer.private_layer_selection.v1";
    private static final String ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA =
        "rusty.quest.native_renderer.environment_depth_alignment.v1";
    private static final String PRIVATE_PARTICLE_DYNAMICS_SCHEMA =
        "rusty.quest.native_renderer.private_particle_dynamics.v1";
    private static final String DRIVER_PROFILE_PANEL_SELECTION_SCHEMA =
        "rusty.driver_profile.mesh.native_panel_selection.v1";
    private static final String PROP_CONTROL_PANEL_MODE =
        "debug.rustyquest.native_renderer.control_panel.mode";
    private static final String BREATH_COMPOSITION_COMMAND_SCHEMA =
        "rusty.quest.breath_composition.command.v1";
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
        "Driver 1 blend",
        "Driver 2 particle size",
        "Driver 3 depth wave",
        "Driver 4 spin",
        "Driver 5 orbit radius",
        "Driver 6 orbit angle",
        "Driver 7 animation frame"
    };
    private static final String[] DRIVER_PROFILE_SURFACE_IDS = new String[] {
        "real-hands",
        "gpu-replay-hands",
        "icosphere"
    };
    private static final String[] DRIVER_PROFILE_SURFACE_LABELS = new String[] {
        "Real hands",
        "GPU replay hands",
        "Icosphere"
    };
    private static final String[] DRIVER_PROFILE_SURFACE_TARGETS = new String[] {
        "quest-live-hand-mesh",
        "quest-recorded-gpu-hand-mesh",
        "static-icosphere-l4"
    };
    private static final String[] DRIVER_PROFILE_SOURCE_MODES = new String[] {
        "live-meta-openxr-hand-tracking",
        "recorded-replay-compact-joint-frames",
        "static-resident-surface"
    };
    private static final String[] DRIVER_PROFILE_SURFACE_RESOURCE_PLAN_IDS = new String[] {
        "rusty.quest.spatial_camera_panel.live-hands.1024.solid-black.resource-plan.v1",
        "rusty.quest.spatial_camera_panel.left.1024.solid-black.resource-plan.v1",
        "rusty.quest.spatial_camera_panel.icosphere-l4.solid-black.resource-plan.v1"
    };
    private static final String[] DRIVER_PROFILE_SURFACE_RUNTIME_PROFILE_PATHS = new String[] {
        "",
        "",
        "fixtures/native-gpu/quest-native-renderer-spatial-camera-panel-icosphere-l4-solid-black.profile.json"
    };
    private static final String[] DRIVER_PROFILE_IDS = new String[] {
        "profile-a",
        "profile-b",
        "profile-c",
        "profile-d"
    };
    private static final String[] DRIVER_PROFILE_LABELS = new String[] {
        "Driver profile A",
        "Driver profile B",
        "Driver profile C",
        "Driver profile D"
    };
    private static final String[] DRIVER_PROFILE_SCHEMA_IDS = new String[] {
        "rusty.quest.spatial_camera_panel.driver_profile.profile-a.v1",
        "rusty.quest.spatial_camera_panel.driver_profile.profile-b.v1",
        "rusty.quest.spatial_camera_panel.driver_profile.profile-c.v1",
        "rusty.quest.spatial_camera_panel.driver_profile.profile-d.v1"
    };
    private static final double[] DRIVER_PROFILE_DRIVER0_VALUE01 = new double[] {
        0.44,
        0.88,
        0.44,
        0.88
    };
    private static final double[] DRIVER_PROFILE_DRIVER2_VALUE01 = new double[] {
        0.62,
        0.62,
        0.03,
        0.03
    };
    private static final double[] DRIVER_PROFILE_DRIVER1_VALUE01 = new double[] {
        0.0,
        0.0,
        1.0,
        1.0
    };
    private static final double[] DRIVER_PROFILE_DRIVER3_VALUE01 = new double[] {
        0.002,
        0.004,
        0.002,
        0.004
    };
    private static final String DRIVER_PROFILE_SCHEMA_SET_ID =
        "rusty.quest.spatial_camera_panel.driver_profile_set.default.v1";
    private static final String DRIVER_PROFILE_DEFAULT_PROFILE_ID =
        "rusty.quest.spatial_camera_panel.driver_profile.profile-a.v1";
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
        "Input slot 1: blend",
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
    private String handledBreathCompositionCommandToken = "";
    private String handledDriverProfileMeshPanelCommandToken = "";
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
    private Spinner privateParticleMaterialPreset;
    private CheckBox privateParticlePolarRrOrbitBoost;
    private TextView privateParticleEffectiveReadback;
    // This is deliberately separate from the legacy liveAutoApply control.  The
    // Viscereality panel always sends one closed, debounced JSON candidate for
    // an intentional particle edit; it must not inherit another panel's toggle.
    private boolean privateParticlePanelLiveApply;
    private boolean privateParticleControlsHydrating;
    private long privateParticlePendingRevision;
    private long privateParticlePendingReadbackDeadlineMs;
    private Runnable pendingPrivateParticleEffectReadback;
    private String viscerealityPanelTopic = "home";
    private boolean rendererReturnPending;
    private long rendererReturnBaselineFrame;
    private long rendererReturnStartedAtMs;
    private long rendererReturnLastLaunchAtMs;
    private long rendererReturnStableFocusStartedAtMs;
    private long rendererReturnStableFocusFrame;
    private int rendererReturnGeneration;
    private boolean rendererReturnPanelPaused;
    private Runnable rendererReturnReadinessPoll;
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
    private Spinner driverProfileSurfaceTarget;
    private Spinner driver_profileCondition;
    private TextView driver_profileSelectionSummary;
    private PolarSensorPanel polarSensorPanel;
    private DriverProfileSession experimentSession;
    private boolean driver_profilePanelAutoApplyArmed;
    private Runnable pendingDriverProfileMeshPanelApply;
    private String lastScheduledDriverProfileMeshPanelApplyKey = "";
    private long breathCompositionGeneration;
    private TextView breathOverviewReadback;
    private TextView breathCalibrationReadback;
    private TextView breathOutputReadback;
    private TextView breathDiagnosticsReadback;
    private ProgressBar breathCalibrationProgress;
    private Runnable breathCompositionRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liveApplyHandler = new Handler(Looper.getMainLooper());
        handleDriverProfileExperimentStartupResetIntent(getIntent());
        setContentView(buildContentView());
        updateReadyStatusForPanelMode();
        handleDisplayCompositeIntent(getIntent());
        handleDiagnosticIntent(getIntent());
        handlePolarSensorPanelCommandIntent(getIntent());
        handleBreathCompositionCommandIntent(getIntent());
        handleDriverProfileMeshPanelCommandIntent(getIntent());
        recordSpatialCameraPanelEvent(
            "panel_activity_created",
            "open",
            "control_panel_on_create"
        );
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDriverProfileExperimentStartupResetIntent(intent);
        if (intent != null && ACTION_TOGGLE_PANEL.equals(intent.getAction())) {
            recordSpatialCameraPanelEvent(
                "panel_toggle_command_received",
                "toggle_requested",
                "action_toggle_panel"
            );
            if ("driver-profile-session".equals(readControlPanelMode())
                    && ensureExperimentSession().isBlockRunning()) {
                rebuildContentViewForCurrentMode();
                recordSpatialCameraPanelEvent(
                    "panel_toggle_kept_open",
                    "open",
                    "action_toggle_panel_block_running"
                );
                updateStatus("Experiment block running.");
            } else {
                closePanelAndReturnToImmersive();
            }
        } else if (intent != null && ACTION_OPEN_PANEL.equals(intent.getAction())) {
            recordSpatialCameraPanelEvent(
                "panel_open_command_received",
                "open_requested",
                "action_open_panel"
            );
            rebuildContentViewForCurrentMode();
            handleDisplayCompositeIntent(intent);
            handleDiagnosticIntent(intent);
            handlePolarSensorPanelCommandIntent(intent);
            handleBreathCompositionCommandIntent(intent);
            handleDriverProfileMeshPanelCommandIntent(intent);
            recordSpatialCameraPanelEvent(
                "panel_open_command_applied",
                "open",
                "action_open_panel"
            );
        } else {
            handleDisplayCompositeIntent(intent);
            handleDiagnosticIntent(intent);
            handlePolarSensorPanelCommandIntent(intent);
            handleBreathCompositionCommandIntent(intent);
            handleDriverProfileMeshPanelCommandIntent(intent);
        }
    }

    private void rebuildContentViewForCurrentMode() {
        String panelMode = readControlPanelMode();
        boolean polarOwnerRetained = "polar-sensor".equals(panelMode)
            || "breath-mapping".equals(panelMode)
            || "driver-profile-panel".equals(panelMode)
            || "driver-profile-session".equals(panelMode);
        if (polarSensorPanel != null && !polarOwnerRetained) {
            PolarSensorRuntime.forApplication(getApplicationContext()).detachPanel(this);
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
        } else if ("breath-mapping".equals(panelMode)) {
            updateStatus("Direct breath mapping panel ready; native-effective readback required.");
        } else if ("driver-profile-panel".equals(panelMode)) {
            updateStatus("Driver profile panel ready.");
        } else if ("driver-profile-session".equals(panelMode)) {
            updateStatus("Driver profile session panel ready.");
        } else {
            updateStatus("Panel ready. Candidate path: " + new File(getFilesDir(), CANDIDATE_FILE));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleDisplayCompositeIntent(getIntent());
        handleDiagnosticIntent(getIntent());
        if ("breath-mapping".equals(readControlPanelMode())) {
            breathOperatorMarker(
                "status=panel-foreground panelVisibility=foreground "
                    + "controllerPoseOwner=openxr-session controllerPanelForegroundProof=pending-device "
                    + "polarAccCompositionAdvance=jni-same-process"
            );
            scheduleBreathCompositionRefresh();
        }
        recordSpatialCameraPanelEvent(
            "panel_visible",
            "open",
            "control_panel_on_resume"
        );
    }

    @Override
    protected void onPause() {
        cancelBreathCompositionRefresh();
        if (rendererReturnPending) {
            rendererReturnPanelPaused = true;
            rendererHandoffMarker(
                "status=panel-paused supportingEvidence=true generation="
                    + rendererReturnGeneration
            );
        }
        if ("breath-mapping".equals(readControlPanelMode())) {
            breathOperatorMarker(
                "status=panel-background panelVisibility=background "
                    + "calibrationStateOwner=native-process-shared polarOwnerRetained=true"
            );
        }
        recordSpatialCameraPanelEvent(
            "panel_hidden",
            "hidden",
            "control_panel_on_pause"
        );
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (!rendererReturnPending) {
            cancelRendererReturnReadinessPoll();
        } else {
            rendererHandoffMarker(
                "status=probe-retained-after-destroy generation=" + rendererReturnGeneration
            );
        }
        cancelPendingPrivateParticleEffectReadback();
        recordSpatialCameraPanelEvent(
            "panel_activity_destroyed",
            "destroyed",
            "control_panel_on_destroy"
        );
        if (polarSensorPanel != null) {
            PolarSensorRuntime.forApplication(getApplicationContext()).detachPanel(this);
            polarSensorPanel = null;
        }
        super.onDestroy();
    }

    private void recordSpatialCameraPanelEvent(
        String eventType,
        String panelState,
        String source
    ) {
        if (!"driver-profile-session".equals(readControlPanelMode())) {
            return;
        }
        try {
            DriverProfileSession session = ensureExperimentSession();
            if (session.hasParticipant()) {
                session.recordPanelEvent(eventType, panelState, source);
            }
        } catch (Exception ignored) {
        }
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
        if ("driver-profile-panel".equals(panelMode)) {
            return buildDriverProfileMeshPanelView();
        }
        if ("driver-profile-session".equals(panelMode)) {
            return buildDriverProfileExperimentView();
        }
        if ("polar-sensor".equals(panelMode)) {
            return buildPolarSensorPanelPageView(false);
        }
        if ("breath-mapping".equals(panelMode)) {
            return buildViscerealityControlPanelView();
        }
        return buildStimulusPanelView();
    }

    private View buildViscerealityControlPanelView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PANEL_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        appendViscerealityPanelHeader(root);
        if ("particles".equals(viscerealityPanelTopic)) {
            appendUnifiedParticleControls(root);
        } else if ("breath".equals(viscerealityPanelTopic)) {
            appendViscerealityBreathControls(root);
        } else if ("polar".equals(viscerealityPanelTopic)) {
            appendViscerealityPolarControls(root);
        } else if ("lsl".equals(viscerealityPanelTopic)) {
            appendViscerealityLslControls(root);
        } else if ("status".equals(viscerealityPanelTopic)) {
            appendViscerealityStatus(root);
        } else {
            appendViscerealityPanelHome(root);
        }
        return scroll;
    }

    private void appendViscerealityPanelHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Viscereality · " + viscerealityTopicTitle(), 22, PANEL_FG);
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
        root.addView(header);
        root.addView(text(viscerealityTopicSubtitle(), 13, PANEL_MUTED));

        HorizontalScrollView topicScroll = new HorizontalScrollView(this);
        topicScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout topics = new LinearLayout(this);
        topics.setOrientation(LinearLayout.HORIZONTAL);
        String[] ids = new String[] {"home", "particles", "breath", "polar", "lsl", "status"};
        String[] titles = new String[] {"Home", "Particles", "Breath", "Polar", "LSL", "Status"};
        for (int i = 0; i < ids.length; i++) {
            final String topic = ids[i];
            Button choice = button(titles[i]);
            choice.setEnabled(!topic.equals(viscerealityPanelTopic));
            choice.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectViscerealityPanelTopic(topic);
                }
            });
            topics.addView(choice);
        }
        topicScroll.addView(topics);
        root.addView(topicScroll);
    }

    private String viscerealityTopicTitle() {
        if ("particles".equals(viscerealityPanelTopic)) {
            return "Particles";
        }
        if ("breath".equals(viscerealityPanelTopic)) {
            return "Breath";
        }
        if ("polar".equals(viscerealityPanelTopic)) {
            return "Polar";
        }
        if ("lsl".equals(viscerealityPanelTopic)) {
            return "LSL streaming";
        }
        if ("status".equals(viscerealityPanelTopic)) {
            return "Effective state";
        }
        return "Control panel";
    }

    private String viscerealityTopicSubtitle() {
        if ("particles".equals(viscerealityPanelTopic)) {
            return "Visual shape, dynamics, trails, material, and RR orbit response.";
        }
        if ("breath".equals(viscerealityPanelTopic)) {
            return "Choose and calibrate the live controller or Polar ACC breath input.";
        }
        if ("polar".equals(viscerealityPanelTopic)) {
            return "Pairing and acquisition remain owned by the same-APK Polar runtime.";
        }
        if ("lsl".equals(viscerealityPanelTopic)) {
            return "Persistent, panel-controlled LAN outlets and one bounded Float32 inlet.";
        }
        if ("status".equals(viscerealityPanelTopic)) {
            return "Requested values never replace the renderer's effective readback.";
        }
        return "Organized by the live system that owns each setting.";
    }

    private void selectViscerealityPanelTopic(String topic) {
        if (topic.equals(viscerealityPanelTopic) || rendererReturnPending) {
            return;
        }
        cancelPendingPrivateParticleDynamicsApply();
        cancelPendingPrivateParticleEffectReadback();
        cancelBreathCompositionRefresh();
        viscerealityPanelTopic = topic;
        setContentView(buildViscerealityControlPanelView());
    }

    private void appendViscerealityPanelHome(LinearLayout root) {
        root.addView(
            text(
                "Use a focused topic rather than one long mixed control surface. Each topic reports the state actually accepted by its runtime owner.",
                13,
                PANEL_MUTED
            )
        );
        appendViscerealityTopicRow(
            root,
            "Particles",
            "Shape, oscillator drivers, trails, material A/B, and Polar RR orbit boost.",
            "particles"
        );
        appendViscerealityTopicRow(
            root,
            "Breath",
            "Controller or Polar ACC mapping, calibration, and current live output.",
            "breath"
        );
        appendViscerealityTopicRow(
            root,
            "Polar",
            "Connection, ACC acquisition, and RR availability for the optional orbit boost.",
            "polar"
        );
        appendViscerealityTopicRow(
            root,
            "LSL",
            "Stream Polar, controller, and headset samples out; map one normalized Float32 stream into driver slots 1–7.",
            "lsl"
        );
        appendViscerealityTopicRow(
            root,
            "Effective state",
            "Read the renderer-confirmed particle revision and the current breath composition.",
            "status"
        );
    }

    private void appendViscerealityTopicRow(
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
                selectViscerealityPanelTopic(topic);
            }
        });
        card.addView(open);
        root.addView(card);
    }

    private void appendViscerealityBreathControls(LinearLayout root) {
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

    private void appendViscerealityPolarControls(LinearLayout root) {
        LinearLayout polar = panelCard("Polar connection & acquisition");
        polar.addView(text(
            "Scan, connect, select ACC, and start PMD. RR events remain separate from the breath mapping and are consumed only when the Particle topic explicitly enables RR orbit boost.",
            12,
            PANEL_MUTED
        ));
        polar.addView(ensurePolarSensorPanel().buildEmbeddedAcquisitionView());
        root.addView(polar);
    }

    private void appendViscerealityLslControls(LinearLayout root) {
        JSONObject status = readLslTransportStatusJson();
        JSONObject config = status.optJSONObject("config");
        if (config == null) {
            config = LslPanelConfigStore.read(getApplicationContext());
        }
        JSONObject outlets = config.optJSONObject("outlets");
        JSONObject inlet = config.optJSONObject("inlet");

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
        final EditText prefix = editText(config.optString("stream_prefix", "viscereality"), "stream prefix", false);
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
            inlet == null ? "viscereality.input.driver1" : inlet.optString("resolve_value", "viscereality.input.driver1"),
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

        LinearLayout actions = panelCard("Apply and persistence");
        actions.addView(text(
            "Apply writes only the native-accepted normalized configuration to app-private storage. It survives direct headset launches; no capsule or Android property is required.",
            12,
            PANEL_MUTED
        ));
        Button apply = button("Apply LSL configuration");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    String resolve = selected(resolveBy);
                    JSONObject requested = new JSONObject()
                        .put("schema", "rusty.quest.native_renderer.lsl.persisted_config.v1")
                        .put("enabled", enabled.isChecked())
                        .put("outlet_enabled", outletEnabled.isChecked())
                        .put("inlet_enabled", inletEnabled.isChecked())
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
                    .put("config", LslPanelConfigStore.read(getApplicationContext()));
            } catch (Exception impossible) {
                return new JSONObject();
            }
        }
    }

    private String formatLslStatus(JSONObject status) {
        JSONObject effective = status.optJSONObject("effective");
        return "Packaged: " + status.optBoolean("panel_available", false)
            + " | liblsl: " + status.optBoolean("library_linked", false)
            + " | state: " + (effective == null ? "unknown" : effective.optString("state", "unknown"))
            + " | outlets: " + (effective == null ? 0 : effective.optInt("outlet_count", 0))
            + " | inlet: " + (effective == null ? "unknown" : effective.optString("inlet_state", "unknown"))
            + "\npushed=" + (effective == null ? 0 : effective.optLong("samples_pushed", 0))
            + " pulled=" + (effective == null ? 0 : effective.optLong("samples_pulled", 0))
            + " dropped=" + (effective == null ? 0 : effective.optLong("samples_dropped", 0))
            + " rejectedInlet=" + (effective == null ? 0 : effective.optLong("inlet_samples_rejected", 0))
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
            boolean persisted = acceptedConfig != null
                && LslPanelConfigStore.save(getApplicationContext(), acceptedConfig);
            if ("reset".equals(operation)) {
                persisted = LslPanelConfigStore.reset(getApplicationContext());
            }
            boolean effectiveEnabled = acceptedConfig != null
                && acceptedConfig.optBoolean("enabled", false);
            LslMulticastLockManager.setFromPanel(this, effectiveEnabled);
            readback.setText(formatLslStatus(response) + "\npersisted=" + persisted);
        } catch (Exception error) {
            readback.setText("LSL request failed: " + markerToken(error.getMessage()));
        }
    }

    private void appendViscerealityStatus(LinearLayout root) {
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
        root.addView(breath);
        refreshPrivateParticleDynamicsFromStatus(false);
        refreshBreathCompositionReadback(readback);
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
        // Keep the Viscereality particle surface autonomous.  It deliberately
        // does not reuse the legacy panel's liveAutoApply checkbox, which may
        // be absent when this view is built and previously made edits inert.
        liveAutoApply = null;
        privateParticlePanelLiveApply = true;
        privateParticleControlsHydrating = true;
        LinearLayout particle = panelCard("Particles");
        root.addView(particle);
        particle.addView(
            text(
                "Edits apply automatically as one debounced, renderer-safe JSON command. The effective receipt—not a slider position—is authoritative. Material choices are closed presets, not independent blend knobs.",
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

        LinearLayout shape = panelCard("Shape");
        particle.addView(shape);
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
            false
        );
        shape.addView(privateParticleVisualScale.view);
        shape.addView(privateParticleWorldAnchorScale.view);

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
                false
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
                "AKD material emulation"
            },
            privateParticleMaterialPresetIndex(
                materialStatus == null
                    ? "packaged-default"
                    : materialStatus.optString("preset", "packaged-default")
            )
        );
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
                if (!privateParticleControlsHydrating) {
                    schedulePrivateParticleDynamicsApplyFromControl();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        privateParticlePolarRrOrbitBoost.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                schedulePrivateParticleDynamicsApplyFromControl();
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

    private View buildDriverProfileMeshPanelView() {
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
        TextView title = text("Driver Profile Panel", 22, PANEL_FG);
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
        root.addView(buildDriverProfileSuiteTabs("driver_profile"));

        String savedSurface = readDriverProfilePanelSelectionString("surface_target_id", "gpu-replay-hands");
        String savedCondition = readDriverProfilePanelSelectionString("condition", "profile-a");
        driver_profilePanelAutoApplyArmed = false;
        driverProfileSurfaceTarget =
            spinner(DRIVER_PROFILE_SURFACE_LABELS, indexOf(DRIVER_PROFILE_SURFACE_IDS, savedSurface, 1));
        driver_profileCondition =
            spinner(DRIVER_PROFILE_LABELS, indexOf(DRIVER_PROFILE_IDS, savedCondition, 0));
        AdapterView.OnItemSelectedListener updateListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDriverProfileSelectionSummary();
                if (driver_profilePanelAutoApplyArmed) {
                    scheduleLiveDriverProfileMeshPanelSelection();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateDriverProfileSelectionSummary();
            }
        };
        driverProfileSurfaceTarget.setOnItemSelectedListener(updateListener);
        driver_profileCondition.setOnItemSelectedListener(updateListener);

        root.addView(label("Surface"));
        root.addView(driverProfileSurfaceTarget);
        root.addView(label("Condition"));
        root.addView(driver_profileCondition);

        driver_profileSelectionSummary = text("", 13, PANEL_MUTED);
        driver_profileSelectionSummary.setPadding(0, dp(12), 0, dp(8));
        root.addView(driver_profileSelectionSummary);

        LinearLayout actionBlock = new LinearLayout(this);
        actionBlock.setOrientation(LinearLayout.VERTICAL);
        actionBlock.setPadding(0, dp(14), 0, dp(10));
        Button refresh = button("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshDriverProfileMeshPanelFromStatus(true);
            }
        });
        Button apply = button("Apply Live");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitLiveDriverProfileMeshPanelSelection(true);
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
        updateDriverProfileSelectionSummary();
        liveApplyHandler.post(new Runnable() {
            @Override
            public void run() {
                driver_profilePanelAutoApplyArmed = true;
            }
        });
        return scroll;
    }

    private View buildPolarSensorPanelPageView(boolean includeDriverProfileTab) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PANEL_BG);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        if (includeDriverProfileTab) {
            root.addView(buildDriverProfileSuiteTabs("polar"));
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

    private LinearLayout buildDriverProfileSuiteTabs(String activePage) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));
        Button experiment = button("Experiment");
        Button driver_profile = button("DriverProfile");
        Button polar = button("Polar");
        experiment.setEnabled(!"experiment".equals(activePage));
        driver_profile.setEnabled(!"driver_profile".equals(activePage));
        polar.setEnabled(!"polar".equals(activePage));
        experiment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setContentView(buildDriverProfileExperimentView());
                updateStatus("Experiment workflow.");
            }
        });
        driver_profile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setContentView(buildDriverProfileMeshPanelView());
                updateStatus("DriverProfile controls.");
            }
        });
        polar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setContentView(buildPolarSensorPanelPageView(true));
                updateStatus("Polar sensor controls.");
            }
        });
        row.addView(experiment, rowButtonParams());
        row.addView(driver_profile, rowButtonParams());
        row.addView(polar, rowButtonParams());
        return row;
    }

    private PolarSensorPanel ensurePolarSensorPanel() {
        if (polarSensorPanel == null) {
            polarSensorPanel = PolarSensorRuntime.forApplication(getApplicationContext()).attachPanel(this, new PolarSensorPanel.Host() {
                @Override
                public void closePanelAndReturnToImmersive() {
                    ControlPanelActivity.this.closePanelAndReturnToImmersive();
                }

                @Override
                public void onPolarStreamEvent(JSONObject event) {
                    ensureExperimentSession().appendPolarEvent(event);
                }
            });
        }
        return polarSensorPanel;
    }

    private DriverProfileSession ensureExperimentSession() {
        if (experimentSession == null) {
            experimentSession = DriverProfileSession.load(this, driver_profileExperimentConditions());
        }
        return experimentSession;
    }

    private DriverProfileSession.Condition[] driver_profileExperimentConditions() {
        DriverProfileSession.Condition[] descriptors =
            new DriverProfileSession.Condition[DRIVER_PROFILE_IDS.length];
        for (int i = 0; i < DRIVER_PROFILE_IDS.length; i++) {
            descriptors[i] = new DriverProfileSession.Condition(
                DRIVER_PROFILE_IDS[i],
                DRIVER_PROFILE_LABELS[i],
                DRIVER_PROFILE_SCHEMA_IDS[i],
                DRIVER_PROFILE_DRIVER0_VALUE01[i],
                DRIVER_PROFILE_DRIVER1_VALUE01[i]
            );
        }
        return descriptors;
    }

    private View buildDriverProfileExperimentView() {
        DriverProfileSession session = ensureExperimentSession();
        session.syncElapsedBlock();
        if (!session.hasParticipant()) {
            return buildDriverProfileExperimentParticipantView(session);
        }
        if (session.isComplete()) {
            return buildDriverProfileExperimentCompleteView(session);
        }
        if (session.isAwaitingQuestionnaire()) {
            return buildDriverProfileExperimentQuestionnaireView(session);
        }
        if (session.isBlockRunning()) {
            return buildDriverProfileExperimentRunningView(session);
        }
        if ("ready_next_block".equals(session.stage())) {
            return buildDriverProfileExperimentReadyNextView(session);
        }
        return buildDriverProfileExperimentPolarSetupView(session);
    }

    private LinearLayout buildDriverProfileExperimentScrollRoot(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = text(title, 22, PANEL_FG);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button headerClose = button("Close");
        headerClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        header.addView(headerClose);
        root.addView(header);
        root.addView(buildDriverProfileSuiteTabs("experiment"));
        return root;
    }

    private View wrapDriverProfileExperimentScroll(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PANEL_BG);
        scroll.addView(root);
        return scroll;
    }

    private View buildDriverProfileExperimentParticipantView(final DriverProfileSession session) {
        LinearLayout root = buildDriverProfileExperimentScrollRoot("Experiment Setup");
        root.addView(text("Step 1 of 4: enter the participant ID before sensor setup.", 13, PANEL_MUTED));
        root.addView(sectionTitle("Participant"));
        final EditText participantId = editText("", "participant_id", false);
        final Spinner surfaceTarget = spinner(
            DRIVER_PROFILE_SURFACE_LABELS,
            indexOf(DRIVER_PROFILE_SURFACE_IDS, "real-hands", 0)
        );
        final Button submit = button("Submit ID");
        submit.setEnabled(false);
        participantId.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable editable) {
                submit.setEnabled(editable.toString().trim().length() > 0);
            }
        });
        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    session.beginParticipant(participantId.getText().toString());
                    int surfaceIndex = Math.max(
                        0,
                        Math.min(DRIVER_PROFILE_SURFACE_IDS.length - 1, surfaceTarget.getSelectedItemPosition())
                    );
                    session.setSurfaceTarget(
                        DRIVER_PROFILE_SURFACE_IDS[surfaceIndex],
                        DRIVER_PROFILE_SURFACE_LABELS[surfaceIndex],
                        DRIVER_PROFILE_SURFACE_TARGETS[surfaceIndex],
                        DRIVER_PROFILE_SURFACE_RESOURCE_PLAN_IDS[surfaceIndex],
                        DRIVER_PROFILE_SURFACE_RUNTIME_PROFILE_PATHS[surfaceIndex]
                    );
                    setContentView(buildDriverProfileExperimentPolarSetupView(session));
                    updateStatus("Participant files created: " + session.sessionId());
                } catch (Exception error) {
                    updateStatus("Participant setup failed: " + error.getMessage());
                }
            }
        });
        root.addView(participantId);
        root.addView(label("Experiment surface"));
        root.addView(surfaceTarget);
        root.addView(text("Condition order is randomized once for this participant: " + session.orderSummary(), 13, PANEL_MUTED));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(submit, rowButtonParams());
        root.addView(row);
        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);
        return wrapDriverProfileExperimentScroll(root);
    }

    private View buildDriverProfileExperimentPolarSetupView(final DriverProfileSession session) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PANEL_BG);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        LinearLayout header = buildDriverProfileExperimentScrollRoot("Polar Setup");
        header.addView(text("Participant: " + session.participantId() + " | Session: " + session.sessionId(), 13, PANEL_MUTED));
        header.addView(text("Surface: " + session.surfaceLabel() + " | " + session.surfaceTargetId(), 13, PANEL_MUTED));
        header.addView(text("Files: " + session.filesSummary(), 13, PANEL_MUTED));
        final TextView ecgLine = text(ensurePolarSensorPanel().ecgExperimentStatusLine(session.hasParticipant()), 14, PANEL_FG);
        ecgLine.setPadding(0, dp(10), 0, dp(8));
        header.addView(ecgLine);
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button startEcg = button("Start ECG");
        startEcg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ensurePolarSensorPanel().handleCommand("start_ecg");
                ecgLine.setText(ensurePolarSensorPanel().ecgExperimentStatusLine(session.hasParticipant()));
            }
        });
        Button refresh = button("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ecgLine.setText(ensurePolarSensorPanel().ecgExperimentStatusLine(session.hasParticipant()));
            }
        });
        Button participantWindow = button("Start participant window");
        participantWindow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setContentView(buildDriverProfileExperimentMetadataView(session));
                updateStatus("Participant metadata window.");
            }
        });
        actionRow.addView(startEcg, rowButtonParams());
        actionRow.addView(refresh, rowButtonParams());
        actionRow.addView(participantWindow, rowButtonParams());
        header.addView(actionRow);
        root.addView(header);
        root.addView(ensurePolarSensorPanel().buildView(), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));
        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(8), 0, dp(4));
        root.addView(status);
        return root;
    }

    private View buildDriverProfileExperimentMetadataView(final DriverProfileSession session) {
        LinearLayout root = buildDriverProfileExperimentScrollRoot("Participant Window");
        root.addView(text("Step 3 of 4: confirm run metadata before starting timed blocks.", 13, PANEL_MUTED));
        root.addView(sectionTitle("Metadata"));
        final EditText runLabel = editText("", "run label", false);
        final EditText operator = editText("", "operator id", false);
        final EditText notes = editText("", "notes", true);
        root.addView(label("Run label"));
        root.addView(runLabel);
        root.addView(label("Operator"));
        root.addView(operator);
        root.addView(label("Notes"));
        root.addView(notes);
        root.addView(text("Fixed block duration: "
            + (DriverProfileSession.DEFAULT_BLOCK_DURATION_MS / 1000L)
            + " seconds per condition.", 13, PANEL_MUTED));
        root.addView(text("Surface: " + session.surfaceLabel() + " | " + session.surfaceTargetId(), 13, PANEL_MUTED));
        root.addView(text("Randomized order: " + session.orderSummary(), 13, PANEL_MUTED));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("Back to Polar");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setContentView(buildDriverProfileExperimentPolarSetupView(session));
            }
        });
        Button start = button("Start experiment");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    session.saveRunMetadata(
                        runLabel.getText().toString(),
                        operator.getText().toString(),
                        notes.getText().toString()
                    );
                    startDriverProfileExperimentBlock(session);
                } catch (Exception error) {
                    updateStatus("Experiment start failed: " + error.getMessage());
                }
            }
        });
        row.addView(back, rowButtonParams());
        row.addView(start, rowButtonParams());
        root.addView(row);
        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);
        return wrapDriverProfileExperimentScroll(root);
    }

    private View buildDriverProfileExperimentRunningView(final DriverProfileSession session) {
        JSONObject block = session.activeBlock();
        LinearLayout root = buildDriverProfileExperimentScrollRoot("Block Running");
        if (block != null) {
            long remainingMs = Math.max(0L, block.optLong("deadline_unix_ms", 0L) - System.currentTimeMillis());
            root.addView(text("Block " + block.optInt("block_number", 0)
                + " of " + session.conditionCount()
                + " | " + block.optString("condition_id"), 16, PANEL_FG));
            root.addView(text("Remaining: " + (remainingMs / 1000L) + " seconds.", 13, PANEL_MUTED));
            root.addView(text(ensurePolarSensorPanel().ecgExperimentStatusLine(session.hasParticipant()), 13, PANEL_MUTED));
        }
        Button close = button("Return to immersive");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launchImmersiveRenderer();
            }
        });
        root.addView(close);
        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);
        return wrapDriverProfileExperimentScroll(root);
    }

    private View buildDriverProfileExperimentReadyNextView(final DriverProfileSession session) {
        LinearLayout root = buildDriverProfileExperimentScrollRoot("Next Block Ready");
        root.addView(text("Questionnaire saved. The next randomized condition is ready to start.", 13, PANEL_MUTED));
        Button start = button("Start next block");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startDriverProfileExperimentBlock(session);
                } catch (Exception error) {
                    updateStatus("Next block failed: " + error.getMessage());
                }
            }
        });
        root.addView(start);
        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);
        return wrapDriverProfileExperimentScroll(root);
    }

    private View buildDriverProfileExperimentQuestionnaireView(final DriverProfileSession session) {
        JSONObject block = session.activeBlock();
        LinearLayout root = buildDriverProfileExperimentScrollRoot("Block Questionnaire");
        if (block != null) {
            root.addView(text("Block " + block.optInt("block_number", 0)
                + " of " + session.conditionCount()
                + " | " + block.optString("condition_label"), 16, PANEL_FG));
            root.addView(text("Condition: " + block.optString("condition_id")
                + " | Profile: " + block.optString("profile_id"), 13, PANEL_MUTED));
        }
        root.addView(sectionTitle("Ratings"));
        final Spinner comfort = ratingSpinner();
        final Spinner intensity = ratingSpinner();
        final Spinner engagement = ratingSpinner();
        final EditText notes = editText("", "questionnaire notes", true);
        final SignaturePadView signature = new SignaturePadView(this);
        root.addView(label("Comfort"));
        root.addView(comfort);
        root.addView(label("Intensity"));
        root.addView(intensity);
        root.addView(label("Engagement"));
        root.addView(engagement);
        root.addView(label("Notes"));
        root.addView(notes);
        root.addView(label("Signature"));
        root.addView(signature, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(180)
        ));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button clearSignature = button("Clear signature");
        clearSignature.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                signature.clear();
            }
        });
        Button submit = button("Submit questionnaire");
        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    session.submitQuestionnaire(
                        ratingValue(comfort),
                        ratingValue(intensity),
                        ratingValue(engagement),
                        notes.getText().toString(),
                        signature.toJson()
                    );
                    if (session.isComplete()) {
                        setContentView(buildDriverProfileExperimentCompleteView(session));
                        updateStatus("Experiment complete.");
                    } else {
                        startDriverProfileExperimentBlock(session);
                    }
                } catch (Exception error) {
                    updateStatus("Questionnaire submit failed: " + error.getMessage());
                }
            }
        });
        row.addView(clearSignature, rowButtonParams());
        row.addView(submit, rowButtonParams());
        root.addView(row);
        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);
        return wrapDriverProfileExperimentScroll(root);
    }

    private View buildDriverProfileExperimentCompleteView(final DriverProfileSession session) {
        LinearLayout root = buildDriverProfileExperimentScrollRoot("Experiment Complete");
        root.addView(text("Participant: " + session.participantId(), 15, PANEL_FG));
        root.addView(text("Session: " + session.sessionId(), 13, PANEL_MUTED));
        root.addView(text("Directory: " + session.sessionDirectoryPath(), 13, PANEL_MUTED));
        root.addView(text("Files: " + session.filesSummary(), 13, PANEL_MUTED));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button newParticipant = button("New participant");
        newParticipant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    session.resetForNewParticipant();
                    experimentSession = null;
                    setContentView(buildDriverProfileExperimentView());
                } catch (Exception error) {
                    updateStatus("Reset failed: " + error.getMessage());
                }
            }
        });
        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closePanelAndReturnToImmersive();
            }
        });
        row.addView(newParticipant, rowButtonParams());
        row.addView(close, rowButtonParams());
        root.addView(row);
        status = text("", 13, PANEL_MUTED);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);
        return wrapDriverProfileExperimentScroll(root);
    }

    private void startDriverProfileExperimentBlock(DriverProfileSession session) throws Exception {
        JSONObject block = session.startNextBlock();
        if (block == null) {
            setContentView(buildDriverProfileExperimentCompleteView(session));
            updateStatus("Experiment complete.");
            return;
        }
        String conditionId = block.optString("condition_id", "profile-a");
        if (!submitLiveDriverProfileMeshPanelSelectionForCondition(
                conditionId,
                session.surfaceTargetId(),
                false
        )) {
            throw new IllegalStateException("condition_queue_failed");
        }
        if (!nativeBridgeLoaded) {
            throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
        }
        String responseText = nativeStartDriverProfileSessionBlock(block.toString());
        JSONObject response = new JSONObject(responseText);
        if (!"queued".equals(response.optString("status", ""))) {
            throw new IllegalStateException(responseText);
        }
        updateStatus("Block " + block.optInt("block_number", 0)
            + " started: " + conditionId + ".");
        session.recordPanelEvent(
            "condition_foreground_requested",
            "close_requested",
            "start_block_to_immersive"
        );
        launchImmersiveRenderer();
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

    private Spinner ratingSpinner() {
        return spinner(new String[] {"1", "2", "3", "4", "5", "6", "7"}, 3);
    }

    private int ratingValue(Spinner spinner) {
        return Math.max(1, Math.min(7, spinner.getSelectedItemPosition() + 1));
    }

    private static final class SignaturePoint {
        final float x;
        final float y;
        final long tMs;

        SignaturePoint(float x, float y, long tMs) {
            this.x = x;
            this.y = y;
            this.tMs = tMs;
        }
    }

    private final class SignaturePadView extends View {
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<ArrayList<SignaturePoint>> strokes =
            new ArrayList<ArrayList<SignaturePoint>>();
        private ArrayList<SignaturePoint> activeStroke;
        private long strokeStartMs;

        SignaturePadView(Activity activity) {
            super(activity);
            setMinimumHeight(dp(160));
            setBackgroundColor(Color.TRANSPARENT);
            strokePaint.setColor(Color.rgb(26, 28, 34));
            strokePaint.setStrokeWidth(dp(3));
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            strokePaint.setStyle(Paint.Style.STROKE);
            backgroundPaint.setColor(Color.rgb(246, 247, 250));
            backgroundPaint.setStyle(Paint.Style.FILL);
            borderPaint.setColor(Color.rgb(80, 86, 98));
            borderPaint.setStrokeWidth(dp(1));
            borderPaint.setStyle(Paint.Style.STROKE);
        }

        void clear() {
            strokes.clear();
            activeStroke = null;
            invalidate();
        }

        JSONObject toJson() throws Exception {
            int width = Math.max(1, getWidth());
            int height = Math.max(1, getHeight());
            JSONArray strokeRows = new JSONArray();
            int pointCount = 0;
            for (int i = 0; i < strokes.size(); i++) {
                ArrayList<SignaturePoint> stroke = strokes.get(i);
                JSONArray points = new JSONArray();
                for (int j = 0; j < stroke.size(); j++) {
                    SignaturePoint point = stroke.get(j);
                    points.put(new JSONObject()
                        .put("x", clamp01(point.x))
                        .put("y", clamp01(point.y))
                        .put("t_ms", Math.max(0L, point.tMs)));
                    pointCount++;
                }
                if (points.length() > 0) {
                    strokeRows.put(points);
                }
            }
            return new JSONObject()
                .put("format", "stroke-json-v1")
                .put("width_px", width)
                .put("height_px", height)
                .put("stroke_count", strokeRows.length())
                .put("point_count", pointCount)
                .put("is_empty", pointCount == 0)
                .put("strokes", strokeRows);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float right = Math.max(1, getWidth() - 1);
            float bottom = Math.max(1, getHeight() - 1);
            canvas.drawRect(0, 0, right, bottom, backgroundPaint);
            for (int i = 0; i < strokes.size(); i++) {
                drawStroke(canvas, strokes.get(i));
            }
            if (activeStroke != null) {
                drawStroke(canvas, activeStroke);
            }
            canvas.drawRect(0, 0, right, bottom, borderPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                strokeStartMs = SystemClock.uptimeMillis();
                activeStroke = new ArrayList<SignaturePoint>();
                addPoint(event);
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (activeStroke == null) {
                    activeStroke = new ArrayList<SignaturePoint>();
                    strokeStartMs = SystemClock.uptimeMillis();
                }
                for (int i = 0; i < event.getHistorySize(); i++) {
                    addPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalEventTime(i));
                }
                addPoint(event);
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (activeStroke != null && activeStroke.size() > 0 && action == MotionEvent.ACTION_UP) {
                    addPoint(event);
                    strokes.add(activeStroke);
                }
                activeStroke = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
            }
            return true;
        }

        private void addPoint(MotionEvent event) {
            addPoint(event.getX(), event.getY(), event.getEventTime());
        }

        private void addPoint(float xPx, float yPx, long eventTimeMs) {
            if (activeStroke == null) {
                return;
            }
            float width = Math.max(1.0f, (float)getWidth());
            float height = Math.max(1.0f, (float)getHeight());
            long tMs = Math.max(0L, eventTimeMs - strokeStartMs);
            activeStroke.add(new SignaturePoint(xPx / width, yPx / height, tMs));
        }

        private void drawStroke(Canvas canvas, ArrayList<SignaturePoint> stroke) {
            if (stroke == null || stroke.size() == 0) {
                return;
            }
            float width = Math.max(1.0f, (float)getWidth());
            float height = Math.max(1.0f, (float)getHeight());
            SignaturePoint previous = stroke.get(0);
            if (stroke.size() == 1) {
                canvas.drawPoint(previous.x * width, previous.y * height, strokePaint);
                return;
            }
            for (int i = 1; i < stroke.size(); i++) {
                SignaturePoint point = stroke.get(i);
                canvas.drawLine(
                    previous.x * width,
                    previous.y * height,
                    point.x * width,
                    point.y * height,
                    strokePaint
                );
                previous = point;
            }
        }
    }

    private static double clamp01(float value) {
        return Math.max(0.0, Math.min(1.0, (double)value));
    }

    private void updateDriverProfileSelectionSummary() {
        if (driver_profileSelectionSummary == null || driverProfileSurfaceTarget == null || driver_profileCondition == null) {
            return;
        }
        int surfaceIndex = selectedDriverProfileSurfaceIndex();
        int conditionIndex = selectedDriverProfileConditionIndex();
        driver_profileSelectionSummary.setText(
            DRIVER_PROFILE_SURFACE_TARGETS[surfaceIndex]
                + " | "
                + DRIVER_PROFILE_SCHEMA_IDS[conditionIndex]
                + " | driver0="
                + String.format(Locale.US, "%.2f", DRIVER_PROFILE_DRIVER0_VALUE01[conditionIndex])
                + " driver1="
                + String.format(Locale.US, "%.2f", DRIVER_PROFILE_DRIVER1_VALUE01[conditionIndex])
        );
    }

    private boolean submitLiveDriverProfileMeshPanelSelection(boolean userVisible) {
        JSONObject selection = null;
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            selection = buildDriverProfileMeshPanelSelectionJson();
            return submitLiveDriverProfileMeshPanelSelection(selection, userVisible);
        } catch (Exception error) {
            try {
                writeDriverProfileMeshPanelStatus("rejected_by_panel", selection, null, error.getMessage());
            } catch (Exception ignored) {
            }
            driverProfileMarker("status=rejected reason=" + markerToken(error.getMessage()));
            if (userVisible) {
                updateStatus("Driver profile selection failed: " + error.getMessage());
            } else {
                setStatusText("Driver profile selection failed: " + error.getMessage());
            }
            return false;
        }
    }

    private boolean submitLiveDriverProfileMeshPanelSelectionForCondition(
        String conditionId,
        String surfaceTargetId,
        boolean userVisible
    ) {
        JSONObject selection = null;
        try {
            int surfaceIndex = indexOf(DRIVER_PROFILE_SURFACE_IDS, surfaceTargetId, 0);
            int conditionIndex = indexOf(DRIVER_PROFILE_IDS, conditionId, 0);
            selection = buildDriverProfileMeshPanelSelectionJson(surfaceIndex, conditionIndex);
            return submitLiveDriverProfileMeshPanelSelection(selection, userVisible);
        } catch (Exception error) {
            try {
                writeDriverProfileMeshPanelStatus("rejected_by_experiment", selection, null, error.getMessage());
            } catch (Exception ignored) {
            }
            driverProfileMarker("status=experiment-selection-rejected reason=" + markerToken(error.getMessage()));
            if (userVisible) {
                updateStatus("Experiment condition failed: " + error.getMessage());
            } else {
                setStatusText("Experiment condition failed: " + error.getMessage());
            }
            return false;
        }
    }

    private boolean submitLiveDriverProfileMeshPanelSelection(JSONObject selection, boolean userVisible) {
        try {
            if (!nativeBridgeLoaded) {
                throw new IllegalStateException("native bridge unavailable: " + nativeBridgeLoadError);
            }
            JSONObject candidate = buildDriverProfileMeshPanelPrivateParticleCandidate(selection);
            String responseText = nativeSubmitLivePrivateParticleDynamics(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            String responseStatus = response.optString("status", "unknown");
            if (!"queued".equals(responseStatus)) {
                throw new IllegalStateException(responseText);
            }
            writeDriverProfileMeshPanelStatus("queued_by_panel", selection, candidate, responseText);
            String message = "Driver profile selection queued: "
                + selection.optString("surface_target_id")
                + " / "
                + selection.optString("condition")
                + ".";
            driverProfileMarker(
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
            return true;
        } catch (Exception error) {
            try {
                writeDriverProfileMeshPanelStatus("rejected_by_panel", selection, null, error.getMessage());
            } catch (Exception ignored) {
            }
            driverProfileMarker("status=rejected reason=" + markerToken(error.getMessage()));
            if (userVisible) {
                updateStatus("Driver profile selection failed: " + error.getMessage());
            } else {
                setStatusText("Driver profile selection failed: " + error.getMessage());
            }
            return false;
        }
    }

    private void scheduleLiveDriverProfileMeshPanelSelection() {
        if (driverProfileSurfaceTarget == null || driver_profileCondition == null) {
            return;
        }
        String selectionKey = DRIVER_PROFILE_SURFACE_IDS[selectedDriverProfileSurfaceIndex()]
            + ":"
            + DRIVER_PROFILE_IDS[selectedDriverProfileConditionIndex()];
        if (selectionKey.equals(lastScheduledDriverProfileMeshPanelApplyKey)) {
            return;
        }
        lastScheduledDriverProfileMeshPanelApplyKey = selectionKey;
        if (pendingDriverProfileMeshPanelApply != null) {
            liveApplyHandler.removeCallbacks(pendingDriverProfileMeshPanelApply);
        }
        pendingDriverProfileMeshPanelApply = new Runnable() {
            @Override
            public void run() {
                pendingDriverProfileMeshPanelApply = null;
                submitLiveDriverProfileMeshPanelSelection(false);
            }
        };
        liveApplyHandler.postDelayed(pendingDriverProfileMeshPanelApply, 180L);
    }

    private JSONObject buildDriverProfileMeshPanelPrivateParticleCandidate(JSONObject selection) throws Exception {
        int surfaceIndex = indexOf(
            DRIVER_PROFILE_SURFACE_IDS,
            selection.optString("surface_target_id", "gpu-replay-hands"),
            1
        );
        int conditionIndex = indexOf(
            DRIVER_PROFILE_IDS,
            selection.optString("condition", "profile-a"),
            0
        );
        double[] drivers = driverProfileValues(surfaceIndex, conditionIndex);
        JSONObject candidate = buildPrivateParticleDynamicsJsonFromValues(
            DRIVER_PROFILE_SCHEMA_IDS[conditionIndex],
            "driver_profile_panel",
            surfaceIndex == 2 ? 1.0 : 0.70,
            surfaceIndex == 2 ? 1.0 : 0.46,
            drivers,
            7,
            0.5,
            14.0
        );
        candidate.put("driver_profile_panel", selection);
        JSONObject privateParticles = candidate.optJSONObject("private_particles");
        if (privateParticles != null) {
            privateParticles.put("driver_profile_selection", selection);
        }
        return candidate;
    }

    private JSONObject buildDriverProfileMeshPanelSelectionJson() throws Exception {
        int surfaceIndex = selectedDriverProfileSurfaceIndex();
        int conditionIndex = selectedDriverProfileConditionIndex();
        return buildDriverProfileMeshPanelSelectionJson(surfaceIndex, conditionIndex);
    }

    private JSONObject buildDriverProfileMeshPanelSelectionJson(int surfaceIndex, int conditionIndex) throws Exception {
        surfaceIndex = Math.max(0, Math.min(DRIVER_PROFILE_SURFACE_IDS.length - 1, surfaceIndex));
        conditionIndex = Math.max(0, Math.min(DRIVER_PROFILE_IDS.length - 1, conditionIndex));
        JSONObject selection = new JSONObject()
            .put("schema_id", DRIVER_PROFILE_PANEL_SELECTION_SCHEMA)
            .put("panel_role", "requester-ui-or-agent-cli")
            .put("panel_must_not_be_authority", true)
            .put("high_rate_payloads_allowed", false)
            .put("surface_target_id", DRIVER_PROFILE_SURFACE_IDS[surfaceIndex])
            .put("surface_target", DRIVER_PROFILE_SURFACE_TARGETS[surfaceIndex])
            .put("source_mode", DRIVER_PROFILE_SOURCE_MODES[surfaceIndex])
            .put("resource_plan_id", DRIVER_PROFILE_SURFACE_RESOURCE_PLAN_IDS[surfaceIndex])
            .put("runtime_profile_path", DRIVER_PROFILE_SURFACE_RUNTIME_PROFILE_PATHS[surfaceIndex])
            .put("condition", DRIVER_PROFILE_IDS[conditionIndex])
            .put("condition_label", DRIVER_PROFILE_LABELS[conditionIndex])
            .put("profile_set_id", DRIVER_PROFILE_SCHEMA_SET_ID)
            .put("profile_id", DRIVER_PROFILE_SCHEMA_IDS[conditionIndex])
            .put("default_profile_id", DRIVER_PROFILE_DEFAULT_PROFILE_ID)
            .put("dynamics_mode", "driver-profile")
            .put("driver0_value01", DRIVER_PROFILE_DRIVER0_VALUE01[conditionIndex])
            .put("driver2_value01", DRIVER_PROFILE_DRIVER2_VALUE01[conditionIndex])
            .put("driver1_value01", DRIVER_PROFILE_DRIVER1_VALUE01[conditionIndex])
            .put("driver3_value01", DRIVER_PROFILE_DRIVER3_VALUE01[conditionIndex]);
        JSONArray expectedMarkers = new JSONArray();
        expectedMarkers.put("driverProfileSurfaceTarget=" + DRIVER_PROFILE_SURFACE_IDS[surfaceIndex]);
        expectedMarkers.put("driverProfileSchemaId=" + DRIVER_PROFILE_SCHEMA_IDS[conditionIndex]);
        selection.put("expected_markers", expectedMarkers);
        return selection;
    }

    private double[] driverProfileValues(int surfaceIndex, int conditionIndex) {
        double driver0High = DRIVER_PROFILE_DRIVER0_VALUE01[conditionIndex] > 0.5 ? 1.0 : 0.0;
        double driver1High = DRIVER_PROFILE_DRIVER1_VALUE01[conditionIndex] > 0.5 ? 1.0 : 0.0;
        return new double[] {
            driver0High > 0.5 ? 0.85 : 0.25,
            driver1High > 0.5 ? 0.85 : 0.15,
            clamp(DRIVER_PROFILE_DRIVER0_VALUE01[conditionIndex] / 0.88, 0.0, 1.0),
            clamp(1.0 - (DRIVER_PROFILE_DRIVER2_VALUE01[conditionIndex] / 0.62), 0.0, 1.0),
            clamp(DRIVER_PROFILE_DRIVER3_VALUE01[conditionIndex] / 0.004, 0.0, 1.0),
            DRIVER_PROFILE_SURFACE_IDS.length <= 1 ? 0.0 : (double) surfaceIndex / (DRIVER_PROFILE_SURFACE_IDS.length - 1),
            DRIVER_PROFILE_IDS.length <= 1 ? 0.0 : (double) conditionIndex / (DRIVER_PROFILE_IDS.length - 1),
            0.0
        };
    }

    private void refreshDriverProfileMeshPanelFromStatus(boolean userVisible) {
        String surface = readDriverProfilePanelSelectionString("surface_target_id", "gpu-replay-hands");
        String condition = readDriverProfilePanelSelectionString("condition", "profile-a");
        boolean previousAutoApply = driver_profilePanelAutoApplyArmed;
        driver_profilePanelAutoApplyArmed = false;
        if (driverProfileSurfaceTarget != null) {
            driverProfileSurfaceTarget.setSelection(indexOf(DRIVER_PROFILE_SURFACE_IDS, surface, 1));
        }
        if (driver_profileCondition != null) {
            driver_profileCondition.setSelection(indexOf(DRIVER_PROFILE_IDS, condition, 0));
        }
        driver_profilePanelAutoApplyArmed = previousAutoApply;
        updateDriverProfileSelectionSummary();
        String message = "Driver profile panel refreshed: " + surface + " / " + condition + ".";
        if (userVisible) {
            updateStatus(message);
        } else {
            setStatusText(message);
        }
    }

    private void writeDriverProfileMeshPanelStatus(
        String panelStatus,
        JSONObject selection,
        JSONObject candidate,
        String resultText
    ) throws Exception {
        JSONObject body = new JSONObject()
            .put("schema", "rusty.driver_profile.mesh.native_panel_status.v1")
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
        writeFile(DRIVER_PROFILE_PANEL_STATUS_FILE, body.toString(2));
    }

    private String readDriverProfilePanelSelectionString(String key, String fallback) {
        try {
            JSONObject body = new JSONObject(readFile(DRIVER_PROFILE_PANEL_STATUS_FILE));
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

    private int selectedDriverProfileSurfaceIndex() {
        if (driverProfileSurfaceTarget == null) {
            return 1;
        }
        return Math.max(0, Math.min(DRIVER_PROFILE_SURFACE_IDS.length - 1, driverProfileSurfaceTarget.getSelectedItemPosition()));
    }

    private int selectedDriverProfileConditionIndex() {
        if (driver_profileCondition == null) {
            return 0;
        }
        return Math.max(0, Math.min(DRIVER_PROFILE_IDS.length - 1, driver_profileCondition.getSelectedItemPosition()));
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
            "Driver 1 blend",
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
            "Driver controls are generic scalar inputs in this public panel",
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
            "stream slot 1 maps to driver1",
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
        if (!privateParticlePanelLiveApply || privateParticleControlsHydrating) {
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
        setStatusText("Particle edit pending renderer-safe apply.");
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
            awaitPrivateParticleEffectiveRevision(candidate.optLong("revision", 0L));
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
            && statusJson.optLong("effective_revision", 0L) >= privateParticlePendingRevision) {
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
                return "akd-material-emulation";
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
        if ("akd-material-emulation".equals(preset)) {
            return 5;
        }
        return 0;
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
        boolean wasHydrating = privateParticleControlsHydrating;
        privateParticleControlsHydrating = true;
        JSONObject materialStatus = privateParticles.optJSONObject("material");
        JSONObject heartbeatStatus = privateParticles.optJSONObject("heartbeat_pulse");
        try {
        if (privateParticleVisualScale != null) {
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
        if (privateParticleMaterialPreset != null) {
            privateParticleMaterialPreset.setSelection(
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
        if (privateParticleEffectiveReadback != null) {
            String material = materialStatus == null
                ? "packaged-default"
                : materialStatus.optString("preset", "packaged-default");
            String heartbeat = heartbeatStatus == null
                ? "disabled"
                : heartbeatStatus.optString("mode", "disabled");
            privateParticleEffectiveReadback.setText(
                "Effective: material " + material
                    + " · RR orbit boost " + heartbeat
                    + " · revision " + statusJson.optLong("effective_revision", 0L)
                    + " · status " + statusJson.optString("status", "unknown")
            );
        }
        } finally {
            privateParticleControlsHydrating = wasHydrating;
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
                return "Input slot 1: blend";
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
        if (privateParticleWorldAnchorScale == null
            || privateParticleDrivers.length < 2
            || privateParticleDrivers[0] == null
            || privateParticleDrivers[1] == null
            || privateParticleTracerDrawSlots == null) {
            return "renderer effective state";
        }
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
        if (!"polar-sensor".equals(panelMode)
                && !"breath-mapping".equals(panelMode)
                && !"driver-profile-panel".equals(panelMode)
                && !"driver-profile-session".equals(panelMode)) {
            setStatusText("Polar command ignored; panel mode does not expose Polar controls.");
            writePolarSensorOperatorReceipt(token, "", "rejected", "panel-mode-inactive");
            return;
        }
        if ("driver-profile-panel".equals(panelMode)) {
            setContentView(buildPolarSensorPanelPageView(true));
        } else if ("driver-profile-session".equals(panelMode)) {
            setContentView(buildDriverProfileExperimentPolarSetupView(ensureExperimentSession()));
        } else {
            ensurePolarSensorPanel();
        }
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

    private void handleDriverProfileMeshPanelCommandIntent(Intent intent) {
        if (intent == null || !ACTION_DRIVER_PROFILE_PANEL_COMMAND.equals(intent.getAction())) {
            return;
        }
        String token = intent.getStringExtra(EXTRA_DRIVER_PROFILE_PANEL_COMMAND_TOKEN);
        if (token == null || token.length() == 0) {
            token = intent.toUri(0);
        }
        if (token.equals(handledDriverProfileMeshPanelCommandToken)) {
            return;
        }
        handledDriverProfileMeshPanelCommandToken = token;
        String panelMode = readControlPanelMode();
        if (!"driver-profile-panel".equals(panelMode) && !"driver-profile-session".equals(panelMode)) {
            setStatusText("Driver profile command ignored; panel is not active.");
            driverProfileMarker("status=cli-command-ignored reason=panel-not-active");
            return;
        }
        if (driverProfileSurfaceTarget == null || driver_profileCondition == null) {
            setContentView(buildDriverProfileMeshPanelView());
        }
        boolean previousAutoApply = driver_profilePanelAutoApplyArmed;
        driver_profilePanelAutoApplyArmed = false;
        String requestedSurface = intent.getStringExtra(EXTRA_DRIVER_PROFILE_SURFACE_TARGET);
        if (requestedSurface != null && requestedSurface.length() > 0) {
            driverProfileSurfaceTarget.setSelection(
                indexOf(DRIVER_PROFILE_SURFACE_IDS, requestedSurface, selectedDriverProfileSurfaceIndex())
            );
        }
        String requestedCondition = intent.getStringExtra(EXTRA_DRIVER_PROFILE_ID);
        if (requestedCondition != null && requestedCondition.length() > 0) {
            driver_profileCondition.setSelection(
                indexOf(DRIVER_PROFILE_IDS, requestedCondition, selectedDriverProfileConditionIndex())
            );
        }
        driver_profilePanelAutoApplyArmed = previousAutoApply;
        updateDriverProfileSelectionSummary();
        driverProfileMarker(
            "status=cli-command surfaceTarget="
                + DRIVER_PROFILE_SURFACE_IDS[selectedDriverProfileSurfaceIndex()]
                + " condition="
                + DRIVER_PROFILE_IDS[selectedDriverProfileConditionIndex()]
        );
        submitLiveDriverProfileMeshPanelSelection(true);
        boolean returnToImmersive = intent.getBooleanExtra(
            EXTRA_DRIVER_PROFILE_RETURN_TO_IMMERSIVE,
            "real-hands".equals(DRIVER_PROFILE_SURFACE_IDS[selectedDriverProfileSurfaceIndex()])
        );
        if (returnToImmersive) {
            driverProfileMarker(
                "status=cli-command-return-to-immersive surfaceTarget="
                    + DRIVER_PROFILE_SURFACE_IDS[selectedDriverProfileSurfaceIndex()]
            );
            closePanelAndReturnToImmersive();
        }
    }

    private void handleDriverProfileExperimentStartupResetIntent(Intent intent) {
        if (intent == null
                || !intent.getBooleanExtra(EXTRA_DRIVER_PROFILE_SESSION_STARTUP_RESET, false)
                || !"driver-profile-session".equals(readControlPanelMode())) {
            return;
        }
        try {
            ensureExperimentSession().resetForNewParticipant();
            driverProfileMarker("status=experiment-startup-reset source=xr-startup-driver-profile-session");
        } catch (Exception error) {
            driverProfileMarker(
                "status=experiment-startup-reset-failed source=xr-startup-driver-profile-session reason="
                    + markerToken(error.getMessage())
            );
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
        if (rendererReturnPending) {
            return;
        }
        recordSpatialCameraPanelEvent(
            "panel_close_command_requested",
            "close_requested",
            "close_panel_and_return_to_immersive"
        );
        RendererFocusState baseline = readRendererFocusState();
        rendererReturnBaselineFrame = rendererFocusReceiptIsCurrentActivity(baseline)
            ? baseline.frameCount
            : -1L;
        rendererReturnStartedAtMs = SystemClock.elapsedRealtime();
        rendererReturnLastLaunchAtMs = 0L;
        resetRendererReturnStableFocus();
        rendererReturnPanelPaused = false;
        rendererReturnGeneration += 1;
        rendererReturnPending = true;
        int generation = rendererReturnGeneration;
        rendererHandoffMarker(
            "status=requested baselineFrame="
                + rendererReturnBaselineFrame
                + " generation="
                + generation
        );
        launchImmersiveRendererForHandoff(generation, "initial");
        scheduleRendererReturnReadinessPoll(generation);
    }

    private void launchImmersiveRendererForHandoff(int generation, String source) {
        rendererReturnLastLaunchAtMs = SystemClock.elapsedRealtime();
        launchImmersiveRenderer();
        rendererHandoffMarker(
            "status=intent-dispatched source="
                + markerToken(source)
                + " generation="
                + generation
        );
    }

    private RendererFocusState readRendererFocusState() {
        try {
            String contents = readFile(RENDERER_FOCUS_STATUS_FILE);
            if (contents.length() == 0) {
                return null;
            }
            return new RendererFocusState(new JSONObject(contents));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean rendererHasAdvancedFocusedFrame(RendererFocusState state) {
        if (!rendererFocusReceiptIsCurrentActivity(state)
                || !"FOCUSED".equals(state.sessionState)
                || !state.submitted) {
            return false;
        }
        long minimumFrame = rendererReturnBaselineFrame >= 0L
            ? rendererReturnBaselineFrame
            : 0L;
        return state.frameCount > minimumFrame;
    }

    private boolean rendererFocusReceiptIsCurrentActivity(RendererFocusState state) {
        return state != null
            && "rusty.quest.native_renderer.renderer_focus_state.v1".equals(state.schema)
            && "android.app.NativeActivity".equals(state.activity)
            && state.updatedAtUnixMs > 0L
            && state.ageMs() <= RENDERER_FOCUS_FRESH_MS;
    }

    private void pollRendererReturnReadiness(int generation) {
        if (!rendererReturnPending || generation != rendererReturnGeneration) {
            return;
        }
        RendererFocusState state = readRendererFocusState();
        long nowMs = SystemClock.elapsedRealtime();
        boolean focusedFrameQualifies = rendererReturnPanelPaused
            && rendererHasAdvancedFocusedFrame(state);
        if (focusedFrameQualifies) {
            if (rendererReturnStableFocusStartedAtMs < 0L) {
                rendererReturnStableFocusStartedAtMs = nowMs;
                rendererReturnStableFocusFrame = state.frameCount;
                rendererHandoffMarker(
                    "status=focus-stability-started frame="
                        + state.frameCount
                        + " panelPaused=true generation="
                        + generation
                );
            } else if (state.frameCount > rendererReturnStableFocusFrame
                    && nowMs - rendererReturnStableFocusStartedAtMs
                        >= RENDERER_RETURN_STABLE_FOCUS_MS) {
                long stableFocusMs = nowMs - rendererReturnStableFocusStartedAtMs;
                rendererReturnPending = false;
                cancelRendererReturnReadinessPoll();
                rendererHandoffMarker(
                    "status=verified frame="
                        + state.frameCount
                        + " focusAgeMs="
                        + state.ageMs()
                        + " stableFocusMs="
                        + stableFocusMs
                        + " panelPaused=true panelTaskRetained=true generation="
                        + generation
                );
                recordSpatialCameraPanelEvent(
                    "panel_close_renderer_ready",
                    "hidden",
                    "stable_focused_submitted_frames_panel_retained"
                );
                return;
            }
        } else {
            resetRendererReturnStableFocus();
        }
        if (nowMs - rendererReturnStartedAtMs >= RENDERER_RETURN_TIMEOUT_MS) {
            rendererReturnPending = false;
            cancelRendererReturnReadinessPoll();
            rendererHandoffMarker(
                "status=timeout panelTaskRetained=true panelPaused="
                    + rendererReturnPanelPaused
                    + " generation="
                    + generation
            );
            recordSpatialCameraPanelEvent(
                "panel_close_renderer_not_ready",
                "open",
                "focused_submitted_frame_timeout_panel_retained"
            );
            return;
        }
        if (rendererReturnStableFocusStartedAtMs < 0L
                && nowMs - rendererReturnLastLaunchAtMs >= RENDERER_RETURN_RELAUNCH_MS) {
            launchImmersiveRendererForHandoff(generation, "reassert");
        }
        scheduleRendererReturnReadinessPoll(generation);
    }

    private void resetRendererReturnStableFocus() {
        rendererReturnStableFocusStartedAtMs = -1L;
        rendererReturnStableFocusFrame = -1L;
    }

    private void scheduleRendererReturnReadinessPoll(final int generation) {
        rendererReturnReadinessPoll = new Runnable() {
            @Override
            public void run() {
                pollRendererReturnReadiness(generation);
            }
        };
        liveApplyHandler.postDelayed(rendererReturnReadinessPoll, RENDERER_RETURN_POLL_MS);
    }

    private void cancelRendererReturnReadinessPoll() {
        if (liveApplyHandler != null && rendererReturnReadinessPoll != null) {
            liveApplyHandler.removeCallbacks(rendererReturnReadinessPoll);
        }
        rendererReturnReadinessPoll = null;
    }

    private static final class RendererFocusState {
        final String schema;
        final String activity;
        final String sessionState;
        final long updatedAtUnixMs;
        final long frameCount;
        final boolean submitted;

        RendererFocusState(JSONObject json) {
            schema = json.optString("schema", "");
            activity = json.optString("activity", "");
            sessionState = json.optString("session_state", "");
            updatedAtUnixMs = json.optLong("updated_at_unix_ms", 0L);
            frameCount = json.optLong("frame_count", -1L);
            submitted = json.optBoolean("submitted", false);
        }

        long ageMs() {
            if (updatedAtUnixMs <= 0L) {
                return Long.MAX_VALUE;
            }
            return Math.max(0L, System.currentTimeMillis() - updatedAtUnixMs);
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

    private static void driverProfileMarker(String detail) {
        Log.i(
            TAG,
            MARKER_PREFIX
                + " channel="
                + CHANNEL_DRIVER_PROFILE_PANEL
                + " "
                + String.valueOf(detail).replace('\n', ' ').replace('\r', ' ')
        );
    }

    private static void breathOperatorMarker(String detail) {
        Log.i(
            TAG,
            MARKER_PREFIX
                + " channel=breath-operator "
                + String.valueOf(detail).replace('\n', ' ').replace('\r', ' ')
        );
    }

    private static void rendererHandoffMarker(String detail) {
        Log.i(
            TAG,
            MARKER_PREFIX
                + " channel=renderer-handoff "
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
        if ("driver-profile-panel".equals(requested)) {
            return requested;
        }
        if ("driver-profile-session".equals(requested)) {
            return requested;
        }
        if ("polar-sensor".equals(requested)) {
            return requested;
        }
        if ("breath-mapping".equals(requested)) {
            return requested;
        }
        String packaged = NativeAppSettingsReader.readSetting(
            this,
            "native_renderer.control_panel.mode"
        );
        if ("breath-mapping".equals(packaged)) {
            return packaged;
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
    private static native String nativeStartDriverProfileSessionBlock(String blockJson);
    private static native String nativeApplyBreathCompositionCommand(String commandJson);
    private static native String nativeApplyLslTransportCommand(String commandJson);
    private static native String nativeReadLslTransportStatus();

    static String applyLslTransportCommandFromOwner(String commandJson) {
        return nativeApplyLslTransportCommand(commandJson);
    }

    private static native String nativeReadBreathCompositionStatus();
}
