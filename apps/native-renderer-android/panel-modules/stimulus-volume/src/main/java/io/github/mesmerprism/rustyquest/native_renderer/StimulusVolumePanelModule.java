package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product-owned StrobeSim/stimulus-volume control surface. */
public class StimulusVolumePanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID = "stimulus-volume";
    public static final String ACTION_APPLY_LIVE_SELF_TEST =
        "io.github.mesmerprism.rustyquest.native_renderer.action.APPLY_LIVE_SELF_TEST";
    private static final String PROFILE_SCHEMA = "rusty.quest.stimulus_volume.profile.v1";
    private static final String CANDIDATE_FILE = "stimulus_volume_candidate.json";
    private static final String STATUS_FILE = "stimulus_volume_status.json";
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
    private static final int PANEL_BG = Color.rgb(17, 18, 22);
    private static final int PANEL_FG = Color.rgb(238, 240, 244);
    private static final int PANEL_MUTED = Color.rgb(170, 176, 186);
    private static final int PANEL_ACCENT = Color.rgb(255, 214, 68);

    private CheckBox acknowledged;
    private CheckBox enabled;
    private CheckBox randomize;
    private CheckBox liveAutoApply;
    private Spinner renderTarget;
    private Slider raymarch;
    private Slider centralFov;
    private Slider gradient;
    private Slider minHz;
    private Slider maxHz;
    private Slider temporalHz;
    private Slider oscillatorAHz;
    private Slider oscillatorBHz;
    private Slider oscillatorCHz;
    private Slider spatialScale;
    private Slider sourceShiftX;
    private Slider sourceShiftY;
    private Slider noiseScale;
    private Slider depthWarp;
    private Slider twist;
    private Slider pinch;
    private Slider scramble;
    private Slider jumble;
    private Slider stretchX;
    private Slider stretchY;
    private Slider phaseA;
    private Slider phaseB;
    private Slider phaseC;
    private Button[] patternButtons = new Button[0];
    private Button[] mirrorButtons = new Button[0];
    private String selectedPatternFamily = "randomized-trevor-vocabulary";
    private String selectedMirrorMode = "none";
    private TextView status;
    private Handler liveApplyHandler;
    private Runnable pendingLiveApply;
    private String handledDiagnosticIntentToken = "";

    @Override
    public final String panelModuleId() {
        return MODULE_ID;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liveApplyHandler = new Handler(Looper.getMainLooper());
        setContentView(buildView());
        handleDiagnosticIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        setContentView(buildView());
        handleDiagnosticIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleDiagnosticIntent(getIntent());
    }

    private View buildView() {
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
        header.addView(text("Volumetric Pattern Panel", 22, PANEL_FG), rowWeight());
        Button resume = button("Resume VR");
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { returnToImmersive(); }
        });
        header.addView(resume);
        root.addView(header);
        root.addView(text("App-private low-rate adapter for the native OpenXR/Vulkan stimulus runtime.", 13, PANEL_MUTED));
        root.addView(previewBand());

        acknowledged = checkBox(
            "Photosensitive-risk acknowledgement",
            readBooleanProperty(PROP_STIMULUS_SAFETY_ACK, false)
        );
        enabled = checkBox(
            "Request active stimulus after launch",
            readBooleanProperty(PROP_STIMULUS_ENABLED, false)
        );
        randomize = checkBox(
            "Enable right-primary randomize",
            readBooleanProperty(PROP_STIMULUS_RANDOMIZE, true)
        );
        View.OnClickListener liveListener = new View.OnClickListener() {
            @Override public void onClick(View view) { scheduleLiveApplyFromControl(); }
        };
        acknowledged.setOnClickListener(liveListener);
        enabled.setOnClickListener(liveListener);
        randomize.setOnClickListener(liveListener);
        liveAutoApply = checkBox("Live auto update", true);
        liveAutoApply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (liveAutoApply.isChecked()) {
                    scheduleLiveApplyFromControl();
                } else {
                    cancelPendingLiveApply();
                    setStatus("Live auto update off. Use Apply Live for explicit changes.");
                }
            }
        });
        root.addView(acknowledged);
        root.addView(enabled);
        root.addView(randomize);
        root.addView(liveAutoApply);

        root.addView(section("Render"));
        String[] renderTargets = new String[] {
            "512x512x2-rgba16f", "768x768x2-rgba16f", "1024x1024x2-rgba16f"
        };
        renderTarget = spinner(renderTargets, indexOf(renderTargets, readSystemProperty(PROP_STIMULUS_RENDER_TARGET), 0));
        root.addView(renderTarget);
        raymarch = slider(root, "Raymarch samples", 1.0, 48.0, readDoubleProperty(PROP_STIMULUS_RAYMARCH, 12.0), true);
        centralFov = slider(root, "Central FOV fraction", 0.45, 1.0, readDoubleProperty(PROP_STIMULUS_CENTRAL_FOV, 0.72), false);
        gradient = slider(root, "Gradient smoothing", 0.0, 1.0, readDoubleProperty(PROP_STIMULUS_GRADIENT, 0.78), false);

        root.addView(section("Pattern"));
        root.addView(buildChoiceGrid(true, new String[][] {
            {"Random", "randomized-trevor-vocabulary"}, {"Mix", "trevor-mix"},
            {"Stripes", "stripes"}, {"Ripples", "ripples"}, {"Rays", "rays"},
            {"Checker", "checker"}, {"Spiral", "spiral"}, {"Noise", "noise-field"}
        }));
        root.addView(section("Mirroring"));
        root.addView(buildChoiceGrid(false, new String[][] {
            {"None", "none"}, {"Mirror X", "mirror-x"}, {"Mirror Y", "mirror-y"},
            {"Mirror XY", "mirror-xy"}, {"Radial", "radial-wedge"}, {"Grid", "grid-fold"}
        }));

        root.addView(section("Timing"));
        minHz = slider(root, "Randomize minimum", 3.0, 40.0, 3.0, false);
        maxHz = slider(root, "Randomize maximum", 3.0, 40.0, 40.0, false);
        temporalHz = slider(root, "Temporal Hz", 3.0, 40.0, 3.083864, false);
        oscillatorAHz = slider(root, "Oscillator A", 3.0, 40.0, 6.041369, false);
        oscillatorBHz = slider(root, "Oscillator B", 3.0, 40.0, 35.362293, false);
        oscillatorCHz = slider(root, "Oscillator C", 3.0, 40.0, 37.53054, false);

        root.addView(section("Volume Field"));
        spatialScale = slider(root, "Shape size", 0.35, 3.0, 0.900433, false);
        sourceShiftX = slider(root, "Source shift X", -0.5, 0.5, -0.052117, false);
        sourceShiftY = slider(root, "Source shift Y", -0.5, 0.5, 0.099197, false);
        noiseScale = slider(root, "Noise scale", 0.0, 12.0, 6.632848, false);
        depthWarp = slider(root, "Depth warp", 0.0, 0.25, 0.103063, false);

        root.addView(section("Warp"));
        twist = slider(root, "Twist", -1.6, 1.6, -0.791351, false);
        pinch = slider(root, "Bulge/pinch", -1.2, 1.2, -0.281597, false);
        scramble = slider(root, "Scramble", 0.0, 1.0, 0.127603, false);
        jumble = slider(root, "Jumble", 0.0, 1.0, 0.165175, false);
        stretchX = slider(root, "Stretch X", 0.4, 2.0, 1.390104, false);
        stretchY = slider(root, "Stretch Y", 0.4, 2.0, 1.071787, false);

        root.addView(section("Phase"));
        phaseA = slider(root, "Phase A", 0.0, Math.PI * 2.0, 0.964848, false);
        phaseB = slider(root, "Phase B", 0.0, Math.PI * 2.0, 1.612527, false);
        phaseC = slider(root, "Phase C", 0.0, Math.PI * 2.0, 3.835902, false);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button validate = button("Validate");
        validate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                try { buildCandidate("validate-only"); setStatus("Panel validation passed."); }
                catch (Exception error) { setStatus("Validation failed: " + error.getMessage()); }
            }
        });
        Button apply = button("Apply Live");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { applyLive(); }
        });
        Button stage = button("Stage");
        stage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { stageCandidate(false); }
        });
        Button stageLaunch = button("Stage + Launch VR");
        stageLaunch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { stageCandidate(true); }
        });
        actions.addView(validate, rowWeight());
        actions.addView(apply, rowWeight());
        actions.addView(stage, rowWeight());
        root.addView(actions);
        root.addView(stageLaunch);
        status = text("Panel ready. Candidate path: " + new File(getFilesDir(), CANDIDATE_FILE), 13, PANEL_MUTED);
        root.addView(status);
        return scroll;
    }

    private JSONObject buildCandidate(String applyMode) throws Exception {
        if (enabled.isChecked() && !acknowledged.isChecked()) {
            throw new IllegalArgumentException("acknowledgement is required before active stimulus");
        }
        double minimum = minHz.value();
        double maximum = maxHz.value();
        if (minimum < 3.0 || maximum > 40.0 || minimum > maximum) {
            throw new IllegalArgumentException("randomize Hz must stay within 3.0-40.0 and min <= max");
        }
        JSONObject randomizeBody = new JSONObject()
            .put("enabled", randomize.isChecked())
            .put("min_hz", minimum)
            .put("max_hz", maximum);
        JSONObject dynamics = buildDynamicsJson();
        JSONObject stimulus = new JSONObject()
            .put("enabled_requested", enabled.isChecked())
            .put("composition", "opaque-black-projection")
            .put("render_target", String.valueOf(renderTarget.getSelectedItem()))
            .put("raymarch_samples", (int)Math.round(raymarch.value()))
            .put("central_fov_fraction", centralFov.value())
            .put("gradient_smoothing", gradient.value())
            .put("pattern_family", selectedPatternFamily)
            .put("randomize", randomizeBody)
            .put("dynamics", dynamics);
        return new JSONObject()
            .put("schema", PROFILE_SCHEMA)
            .put("profile_id", "same-apk-panel")
            .put("revision", System.currentTimeMillis())
            .put("source", new JSONObject().put("surface", "same_apk_panel").put("transport", "app_private_file"))
            .put("safety", new JSONObject()
                .put("photosensitive_risk_ack", acknowledged.isChecked())
                .put("requires_user_activation", true)
                .put("allow_autostart", false)
                .put("black_lead_in_seconds", 1.0)
                .put("max_duration_seconds", 30.0))
            .put("stimulus", stimulus)
            .put("apply", new JSONObject().put("mode", applyMode).put("expected_effective_revision", -1));
    }

    private void applyLive() {
        try {
            JSONObject candidate = buildCandidate("apply-on-next-safe-frame");
            String responseText = ControlPanelActivity.nativeSubmitLiveStimulusCandidate(candidate.toString());
            JSONObject response = new JSONObject(responseText);
            if (!"queued".equals(response.optString("status", ""))) {
                throw new IllegalStateException(responseText);
            }
            writePrivateFile(CANDIDATE_FILE, candidate.toString(2));
            setStatus("Request queued; effective revision remains owned by the native renderer.");
        } catch (Exception error) {
            setStatus("Live apply failed: " + error.getMessage());
        }
    }

    private JSONObject buildDynamicsJson() throws Exception {
        return new JSONObject()
            .put("mirror_mode", selectedMirrorMode)
            .put("temporal_frequency_hz", temporalHz.value())
            .put("spatial_oscillator_hz", new JSONArray()
                .put(oscillatorAHz.value()).put(oscillatorBHz.value()).put(oscillatorCHz.value()))
            .put("spatial_frequency_scale", spatialScale.value())
            .put("source_shift", new JSONArray().put(sourceShiftX.value()).put(sourceShiftY.value()))
            .put("noise_scale", noiseScale.value())
            .put("depth_warp", depthWarp.value())
            .put("twist", twist.value())
            .put("pinch", pinch.value())
            .put("scramble", scramble.value())
            .put("jumble", jumble.value())
            .put("stretch", new JSONArray().put(stretchX.value()).put(stretchY.value()))
            .put("phase_offsets", new JSONArray().put(phaseA.value()).put(phaseB.value()).put(phaseC.value()));
    }

    private void stageCandidate(boolean launchAfterStage) {
        try {
            JSONObject candidate = buildCandidate("stage");
            writePrivateFile(CANDIDATE_FILE, candidate.toString(2));
            writeStatus("staged_by_panel");
            setStatus("Candidate staged.");
            if (launchAfterStage) {
                returnToImmersive();
            }
        } catch (Exception error) {
            setStatus("Stage failed: " + error.getMessage());
        }
    }

    private void scheduleLiveApplyFromControl() {
        if (liveApplyHandler == null || liveAutoApply == null || !liveAutoApply.isChecked()) {
            return;
        }
        cancelPendingLiveApply();
        pendingLiveApply = new Runnable() {
            @Override public void run() {
                pendingLiveApply = null;
                applyLive();
            }
        };
        liveApplyHandler.postDelayed(pendingLiveApply, 180L);
        setStatus("Live auto update pending.");
    }

    private void cancelPendingLiveApply() {
        if (liveApplyHandler != null && pendingLiveApply != null) {
            liveApplyHandler.removeCallbacks(pendingLiveApply);
            pendingLiveApply = null;
        }
    }

    private void handleDiagnosticIntent(Intent intent) {
        if (intent == null || !ACTION_APPLY_LIVE_SELF_TEST.equals(intent.getAction())) {
            return;
        }
        String token = intent.getAction() + ":" + intent.getLongExtra("diagnostic_token", 0L);
        if (token.equals(handledDiagnosticIntentToken)) {
            return;
        }
        handledDiagnosticIntentToken = token;
        if (acknowledged != null) { acknowledged.setChecked(true); }
        if (enabled != null) { enabled.setChecked(true); }
        if (randomize != null) { randomize.setChecked(true); }
        cancelPendingLiveApply();
        liveApplyHandler.postDelayed(new Runnable() {
            @Override public void run() { applyLive(); }
        }, 120L);
        setStatus("Diagnostic Apply Live self-test pending.");
    }

    private void writeStatus(String panelStatus) throws Exception {
        JSONObject body = new JSONObject()
            .put("schema", "rusty.quest.stimulus_volume.apply_status.v1")
            .put("status", panelStatus)
            .put("candidate_file", CANDIDATE_FILE)
            .put("transport", "app_private_file")
            .put("updated_at_unix_ms", System.currentTimeMillis());
        writePrivateFile(STATUS_FILE, body.toString(2));
    }

    private void returnToImmersive() {
        ControlPanelActivity.closePanelAndReturnToImmersive(this);
    }

    private void writePrivateFile(String name, String value) throws Exception {
        File target = new File(getFilesDir(), name);
        FileOutputStream out = new FileOutputStream(target, false);
        try { out.write(value.getBytes(StandardCharsets.UTF_8)); out.flush(); }
        finally { out.close(); }
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private TextView section(String value) {
        TextView view = text(value, 17, PANEL_FG);
        view.setPadding(0, dp(18), 0, dp(6));
        return view;
    }

    private CheckBox checkBox(String value, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(value);
        box.setTextColor(PANEL_FG);
        box.setChecked(checked);
        return box;
    }

    private GridLayout buildChoiceGrid(final boolean patternGrid, String[][] choices) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        ArrayList<Button> buttons = new ArrayList<Button>();
        for (int i = 0; i < choices.length; i++) {
            Button choice = button(choices[i][0]);
            choice.setTag(choices[i][1]);
            choice.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
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

    private void updateChoiceButtons(Button[] buttons, String selectedValue) {
        for (int i = 0; i < buttons.length; i++) {
            boolean selected = selectedValue.equals(String.valueOf(buttons[i].getTag()));
            buttons[i].setAlpha(selected ? 1.0f : 0.55f);
        }
    }

    private Spinner spinner(String[] values, int selectedIndex) {
        Spinner result = new Spinner(this);
        result.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values));
        result.setSelection(selectedIndex);
        return result;
    }

    private int indexOf(String[] values, String requested, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(requested)) {
                return i;
            }
        }
        return fallback;
    }

    private boolean readBooleanProperty(String name, boolean fallback) {
        String value = readSystemProperty(name);
        if ("true".equalsIgnoreCase(value)) { return true; }
        if ("false".equalsIgnoreCase(value)) { return false; }
        return fallback;
    }

    private double readDoubleProperty(String name, double fallback) {
        try { return Double.parseDouble(readSystemProperty(name)); }
        catch (Exception ignored) { return fallback; }
    }

    private String readSystemProperty(String name) {
        Process process = null;
        try {
            process = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String value = reader.readLine();
            process.waitFor();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (process != null) { process.destroy(); }
        }
    }

    private Button button(String value) {
        Button result = new Button(this);
        result.setText(value);
        result.setTextColor(Color.BLACK);
        result.setBackgroundColor(PANEL_ACCENT);
        return result;
    }

    private Slider slider(LinearLayout root, String title, double min, double max, double initial, boolean integer) {
        Slider result = new Slider(title, min, max, initial, integer);
        root.addView(result.view);
        return result;
    }

    private View previewBand() {
        TextView preview = text("depth ramp volume", 13, Color.WHITE);
        preview.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] { Color.BLACK, Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.BLACK }
        );
        background.setCornerRadius(dp(3));
        preview.setBackground(background);
        preview.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
        return preview;
    }

    private LinearLayout.LayoutParams rowWeight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void setStatus(String value) { if (status != null) { status.setText(value); } }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private final class Slider {
        final LinearLayout view;
        final SeekBar seek;
        final TextView label;
        final String title;
        final double min;
        final double max;
        final boolean integer;

        Slider(String title, double min, double max, double initial, boolean integer) {
            this.title = title;
            this.min = min;
            this.max = max;
            this.integer = integer;
            view = new LinearLayout(StimulusVolumePanelModule.this);
            view.setOrientation(LinearLayout.VERTICAL);
            label = text("", 13, PANEL_FG);
            seek = new SeekBar(StimulusVolumePanelModule.this);
            seek.setMax(1000);
            seek.setProgress((int)Math.round((initial - min) * 1000.0 / (max - min)));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    refresh();
                    if (fromUser) { scheduleLiveApplyFromControl(); }
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            view.addView(label);
            view.addView(seek);
            refresh();
        }

        double value() { return min + (max - min) * ((double)seek.getProgress() / 1000.0); }
        void refresh() {
            label.setText(integer
                ? String.format(Locale.US, "%s: %d", title, (int)Math.round(value()))
                : String.format(Locale.US, "%s: %.3f", title, value()));
        }
    }
}
