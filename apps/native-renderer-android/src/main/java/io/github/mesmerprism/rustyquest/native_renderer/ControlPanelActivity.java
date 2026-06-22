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
    public static final String ACTION_TOGGLE_PANEL =
        "io.github.mesmerprism.rustyquest.native_renderer.action.TOGGLE_PANEL";
    public static final String ACTION_APPLY_LIVE_SELF_TEST =
        "io.github.mesmerprism.rustyquest.native_renderer.action.APPLY_LIVE_SELF_TEST";
    public static final String ACTION_REQUEST_DISPLAY_COMPOSITE_CAPTURE =
        "io.github.mesmerprism.rustyquest.native_renderer.action.REQUEST_DISPLAY_COMPOSITE_CAPTURE";
    private static final int REQUEST_DISPLAY_COMPOSITE_CAPTURE = 7401;
    private static final String CANDIDATE_FILE = "stimulus_volume_candidate.json";
    private static final String STATUS_FILE = "stimulus_volume_status.json";
    private static final String DEPTH_ALIGNMENT_STATUS_FILE = "depth_alignment_status.json";
    private static final String PRIVATE_PARTICLE_DYNAMICS_STATUS_FILE =
        "private_particle_dynamics_status.json";
    private static final String PROFILE_SCHEMA = "rusty.quest.stimulus_volume.profile.v1";
    private static final String PRIVATE_LAYER_SELECTION_SCHEMA =
        "rusty.quest.native_renderer.private_layer_selection.v1";
    private static final String ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA =
        "rusty.quest.native_renderer.environment_depth_alignment.v1";
    private static final String PRIVATE_PARTICLE_DYNAMICS_SCHEMA =
        "rusty.quest.native_renderer.private_particle_dynamics.v1";
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
    private static final int DEPTH_WAVE_DRIVER_INDEX = 3;
    private static final double DEPTH_WAVE_MIN_PERCENT = 0.0;
    private static final double DEPTH_WAVE_MAX_PERCENT = 0.1;
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
    private Spinner depthWaveDriverPolicy;
    private SliderControl depthWavePercent;
    private SliderControl depthWaveDriverValue01;
    private TextView depthWaveResolvedLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liveApplyHandler = new Handler(Looper.getMainLooper());
        setContentView(buildContentView());
        String panelMode = readControlPanelMode();
        if ("private-layer-selector".equals(panelMode)) {
            updateStatus("Layer selector ready.");
        } else if ("private-particle-dynamics".equals(panelMode)) {
            updateStatus("Particle dynamics panel ready.");
        } else if ("private-particle-depth-wave".equals(panelMode)) {
            updateStatus("Depth wave panel ready.");
        } else {
            updateStatus("Panel ready. Candidate path: " + new File(getFilesDir(), CANDIDATE_FILE));
        }
        handleDisplayCompositeIntent(getIntent());
        handleDiagnosticIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_TOGGLE_PANEL.equals(intent.getAction())) {
            closePanelAndReturnToImmersive();
        } else {
            handleDisplayCompositeIntent(intent);
            handleDiagnosticIntent(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleDisplayCompositeIntent(getIntent());
        handleDiagnosticIntent(getIntent());
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
        JSONObject privateParticles = new JSONObject()
            .put("visual_scale", visualScale)
            .put("world_anchor_scale_m", worldAnchorScale)
            .put("driver_values01", drivers)
            .put("tracer", tracer);
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
        return "stimulus-volume";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
            return min + (max - min) * ((double) seekBar.getProgress() / (double) steps);
        }

        int intValue() {
            return (int) Math.round(value());
        }

        void setValue(double requested) {
            seekBar.setProgress(progressFor(requested));
            refresh();
        }

        private int progressFor(double requested) {
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
