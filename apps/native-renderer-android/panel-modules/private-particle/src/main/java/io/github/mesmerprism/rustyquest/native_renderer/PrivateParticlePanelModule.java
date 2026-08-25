package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product-owned low-rate controls for the generic private-particle renderer slot. */
public class PrivateParticlePanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID = "private-particle-controls";
    private static final String CANDIDATE_SCHEMA =
        "rusty.quest.native_renderer.private_particle_dynamics.v1";
    private static final String STATUS_FILE = "private_particle_dynamics_status.json";
    private static final int PANEL_BG = Color.rgb(17, 18, 22);
    private static final int PANEL_FG = Color.rgb(238, 240, 244);
    private static final int PANEL_MUTED = Color.rgb(170, 176, 186);

    private final ScalarControl[] drivers = new ScalarControl[8];
    private Handler statusHandler;
    private ScalarControl visualScale;
    private ScalarControl worldAnchorScale;
    private ScalarControl tracerDrawSlots;
    private ScalarControl tracerLifetime;
    private ScalarControl tracerCopies;
    private TextView requestReceipt;
    private TextView effectiveReadback;

    @Override
    public String panelModuleId() {
        return MODULE_ID;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        statusHandler = new Handler(Looper.getMainLooper());
        setContentView(buildView());
        refreshEffectiveReadback();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshEffectiveReadback();
    }

    @Override
    protected void onDestroy() {
        if (statusHandler != null) {
            statusHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    private View buildView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PANEL_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Private Particle Controls", 22, PANEL_FG);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button resume = button("Resume VR");
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { launchImmersiveRenderer(); }
        });
        header.addView(resume);
        root.addView(header);
        root.addView(text(
            "Edits are low-rate candidates. The consuming Rust renderer owns application and effective state.",
            13,
            PANEL_MUTED
        ));

        visualScale = scalar(root, "Visual scale", 0.05, 1.0, 0.70, 1000, "");
        worldAnchorScale = scalar(root, "World anchor scale", 0.05, 4.0, 0.46, 1000, " m");
        root.addView(text("Driver values", 17, PANEL_FG));
        for (int index = 0; index < drivers.length; index += 1) {
            drivers[index] = scalar(root, "Driver " + index, 0.0, 1.0, 0.0, 1000, "");
        }
        root.addView(text("Integrated tracers", 17, PANEL_FG));
        tracerDrawSlots = scalar(root, "Draw slots / oscillator", 0.0, 1024.0, 7.0, 1, "");
        tracerLifetime = scalar(root, "Lifetime", 0.016, 30.0, 0.5, 1000, " s");
        tracerCopies = scalar(root, "Copies / second", 0.0, 120.0, 14.0, 100, "");

        Button apply = button("Apply request");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { submitRequest(); }
        });
        root.addView(apply);
        requestReceipt = text("Request receipt: no request submitted", 13, PANEL_MUTED);
        root.addView(requestReceipt);
        effectiveReadback = text(
            "Native-effective readback: waiting for consuming runtime status",
            13,
            PANEL_MUTED
        );
        root.addView(effectiveReadback);
        return scroll;
    }

    private void submitRequest() {
        try {
            JSONArray driverValues = new JSONArray();
            for (ScalarControl driver : drivers) {
                driverValues.put(driver.value());
            }
            JSONObject request = new JSONObject()
                .put("schema", CANDIDATE_SCHEMA)
                .put("private_particles", new JSONObject()
                    .put("visual_scale", visualScale.value())
                    .put("world_anchor_scale_m", worldAnchorScale.value())
                    .put("driver_values01", driverValues)
                    .put("tracer", new JSONObject()
                        .put("draw_slots_per_oscillator", (int) Math.round(tracerDrawSlots.value()))
                        .put("lifetime_seconds", tracerLifetime.value())
                        .put("copies_per_second", tracerCopies.value())))
                .put("apply", new JSONObject().put("mode", "apply-on-next-safe-frame"));
            JSONObject receipt = new JSONObject(
                PrivateParticlePanelController.submitCandidate(request.toString())
            );
            requestReceipt.setText("Request receipt (not effective state):\n" + receipt.toString(2));
            scheduleEffectiveReadbackRefresh();
        } catch (Exception error) {
            requestReceipt.setText("Request rejected: " + error.getMessage());
        }
    }

    private void scheduleEffectiveReadbackRefresh() {
        refreshEffectiveReadback();
        if (statusHandler == null) {
            return;
        }
        statusHandler.postDelayed(new Runnable() {
            @Override public void run() { refreshEffectiveReadback(); }
        }, 250L);
        statusHandler.postDelayed(new Runnable() {
            @Override public void run() { refreshEffectiveReadback(); }
        }, 1000L);
    }

    private void refreshEffectiveReadback() {
        if (effectiveReadback == null) {
            return;
        }
        try {
            FileInputStream input = openFileInput(STATUS_FILE);
            try {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
                );
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                JSONObject status = new JSONObject(body.toString());
                if (status.optJSONObject("private_particles") == null) {
                    throw new IllegalStateException("status has no private_particles projection");
                }
                effectiveReadback.setText(
                    "Native-effective readback (consuming runtime):\n" + status.toString(2)
                );
            } finally {
                input.close();
            }
        } catch (Exception error) {
            effectiveReadback.setText(
                "Native-effective readback unavailable until the consuming runtime publishes "
                    + STATUS_FILE
            );
        }
    }

    private ScalarControl scalar(
        LinearLayout root,
        String label,
        double minimum,
        double maximum,
        double initial,
        int stepsPerUnit,
        String suffix
    ) {
        ScalarControl control = new ScalarControl(label, minimum, maximum, initial, stepsPerUnit, suffix);
        root.addView(control.label);
        root.addView(control.slider);
        return control;
    }

    private void launchImmersiveRenderer() {
        ControlPanelActivity.closePanelAndReturnToImmersive(this);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class ScalarControl {
        final TextView label;
        final SeekBar slider;
        final double minimum;
        final int stepsPerUnit;
        final String title;
        final String suffix;

        ScalarControl(
            String title,
            double minimum,
            double maximum,
            double initial,
            int stepsPerUnit,
            String suffix
        ) {
            this.title = title;
            this.minimum = minimum;
            this.stepsPerUnit = stepsPerUnit;
            this.suffix = suffix;
            label = text("", 14, PANEL_FG);
            slider = new SeekBar(PrivateParticlePanelModule.this);
            slider.setMax(Math.max(1, (int) Math.round((maximum - minimum) * stepsPerUnit)));
            slider.setProgress((int) Math.round((initial - minimum) * stepsPerUnit));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    updateLabel();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            updateLabel();
        }

        double value() {
            return minimum + slider.getProgress() / (double) stepsPerUnit;
        }

        void updateLabel() {
            label.setText(String.format(Locale.US, "%s: %.3f%s", title, value(), suffix));
        }
    }
}
