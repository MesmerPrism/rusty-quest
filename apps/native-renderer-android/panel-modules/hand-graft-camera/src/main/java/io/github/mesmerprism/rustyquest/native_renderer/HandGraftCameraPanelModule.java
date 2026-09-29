package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** App-private, low-rate settings adapter for the camera and animated hand grafts. */
public class HandGraftCameraPanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID = "hand-graft-camera-controls";
    private static final String SCHEMA = "rusty.quest.hand_graft_controls.v1";
    private static final String CANDIDATE_FILE = "hand_graft_controls_candidate.json";
    private static final String STATUS_FILE = "hand_graft_controls_status.json";
    private static final int FOREGROUND = Color.rgb(238, 240, 244);
    private static final int MUTED = Color.rgb(175, 183, 193);
    private Scalar distance, offsetX, offsetY, scale, graftScale, alpha, rim;
    private CheckBox hands, originals, grafts, joystick, wireframe;
    private TextView message;
    private long latestRevision;

    @Override public String panelModuleId() { return MODULE_ID; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildView());
        loadSavedSettings();
    }

    private View buildView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(19, 23, 29));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(22));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Camera & hand grafts", 24, FOREGROUND),
            new LinearLayout.LayoutParams(0, -2, 1f));
        Button close = button("Return to VR");
        close.setOnClickListener(view -> ControlPanelActivity.closePanelAndReturnToImmersive(this));
        header.addView(close);
        root.addView(header);
        root.addView(text("Change the settings, then apply and return to VR. Press B three times in VR to reopen this panel.", 14, MUTED));

        root.addView(text("Projection", 20, FOREGROUND));
        distance = scalar(root, "Distance", .25, 4, 1, 100, " m", 1);
        offsetX = scalar(root, "Horizontal offset", -.5, .5, 0, 200, "%", 100);
        offsetY = scalar(root, "Vertical offset", -.5, .5, 0, 200, "%", 100);
        root.addView(text("Offsets are relative to the image: positive moves right or down.", 13, MUTED));
        scale = scalar(root, "Camera + hand scale", .25, 3, 1, 100, "×", 1);
        joystick = toggle(root, "Right thumbstick adjusts scale", true);

        root.addView(text("Hand visuals", 20, FOREGROUND));
        hands = toggle(root, "Show hand visuals", true);
        originals = toggle(root, "Show original hands", true);
        grafts = toggle(root, "Show fingertip grafts", true);
        graftScale = scalar(root, "Graft size", .1, 2, .85, 100, "×", 1);
        alpha = scalar(root, "Opacity", .05, 1, 1, 100, "%", 100);
        rim = scalar(root, "Rim strength", 0, 1, .2, 100, "%", 100);
        wireframe = toggle(root, "Show wireframe", false);
        root.addView(text("Untracked hands remain hidden. Grafts require both hands to be tracked.", 13, MUTED));

        LinearLayout actions = new LinearLayout(this);
        Button reset = button("Reset defaults");
        reset.setOnClickListener(view -> { setValues(null); message.setText("Defaults restored. Apply to save them."); });
        actions.addView(reset);
        Button apply = button("Apply & return to VR");
        apply.setOnClickListener(view -> saveAndResume());
        actions.addView(apply);
        root.addView(actions);
        message = text("", 14, MUTED);
        root.addView(message);
        return scroll;
    }

    private void loadSavedSettings() {
        JSONObject saved = read(CANDIDATE_FILE);
        JSONObject status = read(STATUS_FILE);
        if (saved != null && SCHEMA.equals(saved.optString("schema"))) {
            latestRevision = Math.max(0, saved.optLong("revision", 0));
            setValues(saved);
        }
        if (status != null && SCHEMA.equals(status.optString("schema"))) {
            long effectiveRevision = status.optLong("effective_revision", 0);
            latestRevision = Math.max(latestRevision, effectiveRevision);
            JSONObject effective = status.optJSONObject("effective");
            if (effective != null && effectiveRevision >= (saved == null ? 0 : saved.optLong("revision", 0))) {
                setValues(effective);
            }
            if ("rejected".equals(status.optString("adoption_status"))) {
                message.setText("Last change was rejected: " + status.optString("rejection_reason", "invalid settings"));
            } else if (status.optLong("last_submitted_frame", 0) > 0 && effectiveRevision > 0) {
                message.setText("Saved settings were used in VR. Your edits apply when you return.");
            } else {
                message.setText("Settings loaded. Apply to use them in VR.");
            }
        } else {
            message.setText("Settings loaded. Apply to use them in VR.");
        }
    }

    private void setValues(JSONObject values) {
        distance.set(value(values, "panel_distance_m", 1));
        offsetX.set(value(values, "offset_x_uv", 0));
        offsetY.set(value(values, "offset_y_uv", 0));
        scale.set(value(values, "shared_scale", 1));
        graftScale.set(value(values, "graft_scale", .85));
        alpha.set(value(values, "material_alpha", 1));
        rim.set(value(values, "rim_strength", .2));
        hands.setChecked(flag(values, "hands_enabled", true));
        originals.setChecked(flag(values, "base_hands_visible", true));
        grafts.setChecked(flag(values, "grafts_visible", true));
        joystick.setChecked(flag(values, "joystick_enabled", true));
        wireframe.setChecked(flag(values, "wireframe_enabled", false));
    }

    private void saveAndResume() {
        AtomicFile file = new AtomicFile(new File(getFilesDir(), CANDIDATE_FILE));
        FileOutputStream output = null;
        try {
            long revision = Math.max(System.currentTimeMillis(), latestRevision + 1);
            JSONObject settings = new JSONObject()
                .put("schema", SCHEMA).put("revision", revision)
                .put("panel_distance_m", distance.value())
                .put("offset_x_uv", offsetX.value()).put("offset_y_uv", offsetY.value())
                .put("shared_scale", scale.value()).put("joystick_enabled", joystick.isChecked())
                .put("hands_enabled", hands.isChecked()).put("base_hands_visible", originals.isChecked())
                .put("grafts_visible", grafts.isChecked()).put("graft_scale", graftScale.value())
                .put("material_alpha", alpha.value()).put("rim_strength", rim.value())
                .put("wireframe_enabled", wireframe.isChecked());
            output = file.startWrite();
            output.write(settings.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            output = null;
            latestRevision = revision;
            message.setText("Saved. Returning to VR to apply settings…");
            ControlPanelActivity.closePanelAndReturnToImmersive(this);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            message.setText("Could not save settings: " + error.getMessage());
        }
    }

    private JSONObject read(String name) {
        try (FileInputStream input = new AtomicFile(new File(getFilesDir(), name)).openRead()) {
            byte[] bytes = new byte[16 * 1024];
            int size = 0, count;
            while (size < bytes.length && (count = input.read(bytes, size, bytes.length - size)) > 0) size += count;
            if (size == bytes.length) return null;
            return new JSONObject(new String(bytes, 0, size, StandardCharsets.UTF_8));
        } catch (Exception absentOrInvalid) { return null; }
    }

    private static double value(JSONObject values, String key, double fallback) {
        return values == null ? fallback : values.optDouble(key, fallback);
    }
    private static boolean flag(JSONObject values, String key, boolean fallback) {
        return values == null ? fallback : values.optBoolean(key, fallback);
    }
    private CheckBox toggle(LinearLayout root, String title, boolean initial) {
        CheckBox box = new CheckBox(this);
        box.setText(title); box.setTextSize(17); box.setTextColor(FOREGROUND); box.setChecked(initial);
        root.addView(box); return box;
    }
    private Scalar scalar(LinearLayout root, String title, double min, double max,
            double initial, int steps, String suffix, double displayMultiplier) {
        Scalar result = new Scalar(title, min, max, initial, steps, suffix, displayMultiplier);
        root.addView(result.label); root.addView(result.slider); return result;
    }
    private TextView text(String title, int size, int color) {
        return ControlPanelActivity.panelText(this, title, size, color, dp(8));
    }
    private Button button(String title) { return ControlPanelActivity.panelButton(this, title); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Scalar {
        final TextView label;
        final SeekBar slider;
        final String title, suffix;
        final double minimum, multiplier;
        final int steps;
        Scalar(String title, double min, double max, double initial, int steps, String suffix, double multiplier) {
            this.title = title; this.suffix = suffix; this.minimum = min; this.steps = steps; this.multiplier = multiplier;
            label = text("", 16, FOREGROUND);
            slider = new SeekBar(HandGraftCameraPanelModule.this);
            slider.setMax((int) Math.round((max - min) * steps));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { updateLabel(); }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            set(initial);
        }
        double value() { return minimum + slider.getProgress() / (double) steps; }
        void set(double value) {
            if (!Double.isFinite(value)) value = minimum;
            slider.setProgress((int) Math.round((value - minimum) * steps));
            updateLabel();
        }
        void updateLabel() { label.setText(String.format(Locale.US, "%s: %.2f%s", title, value() * multiplier, suffix)); }
    }
}
