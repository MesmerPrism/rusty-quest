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
    private static final String PROFILE_SCHEMA = "rusty.quest.stimulus_volume.profile.v1";
    private static final String PRIVATE_LAYER_SELECTION_SCHEMA =
        "rusty.quest.native_renderer.private_layer_selection.v1";
    private static final String PROP_CONTROL_PANEL_MODE =
        "debug.rustyquest.native_renderer.control_panel.mode";
    private static final String PROP_PRIVATE_LAYER_OVERRIDE =
        "debug.rustyquest.native_renderer.private_layer.layer_override";
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liveApplyHandler = new Handler(Looper.getMainLooper());
        setContentView(buildContentView());
        if ("private-layer-selector".equals(readControlPanelMode())) {
            updateStatus("Layer selector ready.");
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
            finish();
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
        if ("private-layer-selector".equals(readControlPanelMode())) {
            return buildPrivateLayerSelectorView();
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
                finish();
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
        liveAutoApply = checkBox("Live auto update", false);
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
                finish();
            }
        });
        header.addView(headerClose);
        root.addView(header);
        root.addView(text("Select the active private rendering layer.", 13, PANEL_MUTED));
        root.addView(privateLayerPreviewBand());
        selectedPrivateLayerIndex = readPrivateLayerOverride();

        root.addView(sectionTitle("Active Rendering"));
        root.addView(buildPrivateLayerChoiceGrid());

        Button close = button("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
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
            {"Displacement", "5"}
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

    private JSONObject buildPrivateLayerSelectionJson(int layerIndex) throws Exception {
        if (layerIndex < 0 || layerIndex > 5) {
            throw new IllegalArgumentException("layer index must be 0-5");
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
        if (layerIndex < 0 || layerIndex > 5) {
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
                finish();
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
        if ("private-layer-selector".equals(readControlPanelMode())) {
            setStatusText("Stimulus diagnostic self-test ignored in layer selector mode.");
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
        startActivity(intent);
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
        return new SliderControl(title, min, max, initial, steps, suffix, integer);
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

        SliderControl(
            String title,
            double min,
            double max,
            double initial,
            int steps,
            String suffix,
            boolean integer
        ) {
            this.title = title;
            this.min = min;
            this.max = max;
            this.steps = Math.max(1, steps);
            this.suffix = suffix;
            this.integer = integer;
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
                    if (fromUser) {
                        scheduleLiveApplyFromControl();
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
}
