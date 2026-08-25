package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Standalone low-rate driver-profile selector backed by the Rust particle-dynamics adapter. */
public class DriverProfilePanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID = "driver-profile-controls";
    private static final int PANEL_BG = Color.rgb(17, 18, 22);
    private static final int PANEL_FG = Color.rgb(238, 240, 244);
    private static final int PANEL_MUTED = Color.rgb(170, 176, 186);
    private static final String[] CONDITION_LABELS = { "Profile A", "Profile B", "Neutral" };
    private static final String[] CONDITION_IDS = { "profile-a", "profile-b", "neutral" };
    private static final double[][] DRIVER_VALUES = {
        { 0.20, 0.80 },
        { 0.80, 0.20 },
        { 0.50, 0.50 }
    };
    private static final String[] SURFACE_LABELS = {
        "GPU replay hands", "Private particles", "Projection target"
    };
    private static final String[] SURFACE_IDS = {
        "gpu-replay-hands", "private-particles", "projection-target"
    };

    private Spinner condition;
    private Spinner surface;
    private TextView receipt;

    @Override
    public String panelModuleId() {
        return MODULE_ID;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildView());
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
        header.addView(text("Driver Profile Controls", 22, PANEL_FG),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button resume = button("Resume VR");
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                ControlPanelActivity.closePanelAndReturnToImmersive(DriverProfilePanelModule.this);
            }
        });
        header.addView(resume);
        root.addView(header);
        root.addView(text(
            "Profile selection produces a low-rate candidate; the consuming renderer owns effective state.",
            13,
            PANEL_MUTED
        ));
        root.addView(text("Surface", 14, PANEL_FG));
        surface = spinner(SURFACE_LABELS);
        root.addView(surface);
        root.addView(text("Condition", 14, PANEL_FG));
        condition = spinner(CONDITION_LABELS);
        root.addView(condition);
        Button apply = button("Apply profile request");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { submit(); }
        });
        root.addView(apply);
        receipt = text("Request receipt: no request submitted", 13, PANEL_MUTED);
        root.addView(receipt);
        return scroll;
    }

    private void submit() {
        try {
            int conditionIndex = condition.getSelectedItemPosition();
            int surfaceIndex = surface.getSelectedItemPosition();
            JSONArray drivers = new JSONArray();
            drivers.put(DRIVER_VALUES[conditionIndex][0]);
            drivers.put(DRIVER_VALUES[conditionIndex][1]);
            for (int index = 2; index < 8; index += 1) {
                drivers.put(0.0);
            }
            JSONObject selection = new JSONObject()
                .put("schema", "rusty.driver_profile.mesh.native_panel_selection.v1")
                .put("condition", CONDITION_IDS[conditionIndex])
                .put("surface_target_id", SURFACE_IDS[surfaceIndex]);
            JSONObject candidate = new JSONObject()
                .put("schema", "rusty.quest.native_renderer.private_particle_dynamics.v1")
                .put("private_particles", new JSONObject()
                    .put("visual_scale", surfaceIndex == 2 ? 1.0 : 0.70)
                    .put("world_anchor_scale_m", surfaceIndex == 2 ? 1.0 : 0.46)
                    .put("driver_values01", drivers)
                    .put("tracer", new JSONObject()
                        .put("draw_slots_per_oscillator", 7)
                        .put("lifetime_seconds", 0.5)
                        .put("copies_per_second", 14.0))
                    .put("driver_profile_selection", selection))
                .put("apply", new JSONObject().put("mode", "apply-on-next-safe-frame"));
            JSONObject response = new JSONObject(
                PrivateParticlePanelController.submitCandidate(candidate.toString())
            );
            receipt.setText(String.format(
                Locale.US,
                "Request receipt (not effective state): %s",
                response.toString(2)
            ));
        } catch (Exception error) {
            receipt.setText("Request rejected: " + error.getMessage());
        }
    }

    private Spinner spinner(String[] labels) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        ));
        return spinner;
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
}
