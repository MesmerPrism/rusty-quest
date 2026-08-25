package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Standalone low-rate driver-profile selector backed by the Rust particle-dynamics adapter. */
public class DriverProfilePanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID = "driver-profile-controls";
    private static final int PANEL_BG = Color.rgb(17, 18, 22);
    private static final int PANEL_FG = Color.rgb(238, 240, 244);
    private static final int PANEL_MUTED = Color.rgb(170, 176, 186);
    private static final String CANDIDATE_SCHEMA =
        "rusty.quest.native_renderer.private_particle_dynamics.v1";
    private static final String STATUS_SCHEMA =
        "rusty.quest.native_renderer.private_particle_dynamics_status.v1";
    private static final String STATUS_FILE = "private_particle_dynamics_status.json";
    private static final String SELECTION_SCHEMA =
        "rusty.driver_profile.mesh.native_panel_selection.v1";
    private static final String PROFILE_SET_ID =
        "rusty.quest.spatial_camera_panel.driver_profile_set.default.v1";
    private static final String DEFAULT_PROFILE_ID =
        "rusty.quest.spatial_camera_panel.driver_profile.profile-a.v1";
    private static final String[] CONDITION_LABELS = {
        "Driver profile A", "Driver profile B", "Driver profile C", "Driver profile D"
    };
    private static final String[] CONDITION_IDS = {
        "profile-a", "profile-b", "profile-c", "profile-d"
    };
    private static final String[] PROFILE_SCHEMA_IDS = {
        "rusty.quest.spatial_camera_panel.driver_profile.profile-a.v1",
        "rusty.quest.spatial_camera_panel.driver_profile.profile-b.v1",
        "rusty.quest.spatial_camera_panel.driver_profile.profile-c.v1",
        "rusty.quest.spatial_camera_panel.driver_profile.profile-d.v1"
    };
    private static final double[] DRIVER0 = { 0.44, 0.88, 0.44, 0.88 };
    private static final double[] DRIVER2 = { 0.62, 0.62, 0.03, 0.03 };
    private static final double[] DRIVER1 = { 0.0, 0.0, 1.0, 1.0 };
    private static final double[] DRIVER3 = { 0.002, 0.004, 0.002, 0.004 };
    private static final String[] SURFACE_LABELS = {
        "Real hands", "GPU replay hands", "Icosphere"
    };
    private static final String[] SURFACE_IDS = {
        "real-hands", "gpu-replay-hands", "icosphere"
    };
    private static final String[] SURFACE_TARGETS = {
        "quest-live-hand-mesh", "quest-recorded-gpu-hand-mesh", "static-icosphere-l4"
    };
    private static final String[] SOURCE_MODES = {
        "live-meta-openxr-hand-tracking",
        "recorded-replay-compact-joint-frames",
        "static-resident-surface"
    };
    private static final String[] RESOURCE_PLAN_IDS = {
        "rusty.quest.spatial_camera_panel.live-hands.1024.solid-black.resource-plan.v1",
        "rusty.quest.spatial_camera_panel.left.1024.solid-black.resource-plan.v1",
        "rusty.quest.spatial_camera_panel.icosphere-l4.solid-black.resource-plan.v1"
    };
    private static final String[] RUNTIME_PROFILE_PATHS = {
        "",
        "",
        "fixtures/native-gpu/quest-native-renderer-spatial-camera-panel-icosphere-l4-solid-black.profile.json"
    };

    private Spinner condition;
    private Spinner surface;
    private TextView receipt;
    private TextView effectiveReadback;
    private Handler statusHandler;
    private long latestRequestedRevision;

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
        surface.setSelection(1);
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
        effectiveReadback = text(
            "Native-effective readback: waiting for consuming runtime status",
            13,
            PANEL_MUTED
        );
        root.addView(effectiveReadback);
        return scroll;
    }

    private void submit() {
        try {
            int conditionIndex = condition.getSelectedItemPosition();
            int surfaceIndex = surface.getSelectedItemPosition();
            long revision = Math.max(1L, System.currentTimeMillis());
            JSONArray drivers = new JSONArray();
            double driver0High = DRIVER0[conditionIndex] > 0.5 ? 1.0 : 0.0;
            double driver1High = DRIVER1[conditionIndex] > 0.5 ? 1.0 : 0.0;
            drivers.put(driver0High > 0.5 ? 0.85 : 0.25);
            drivers.put(driver1High > 0.5 ? 0.85 : 0.15);
            drivers.put(clamp(DRIVER0[conditionIndex] / 0.88));
            drivers.put(clamp(1.0 - (DRIVER2[conditionIndex] / 0.62)));
            drivers.put(clamp(DRIVER3[conditionIndex] / 0.004));
            drivers.put((double) surfaceIndex / (SURFACE_IDS.length - 1));
            drivers.put((double) conditionIndex / (CONDITION_IDS.length - 1));
            drivers.put(0.0);
            JSONObject selection = new JSONObject()
                .put("schema_id", SELECTION_SCHEMA)
                .put("panel_role", "requester-ui-or-agent-cli")
                .put("panel_must_not_be_authority", true)
                .put("high_rate_payloads_allowed", false)
                .put("condition", CONDITION_IDS[conditionIndex])
                .put("condition_label", CONDITION_LABELS[conditionIndex])
                .put("surface_target_id", SURFACE_IDS[surfaceIndex])
                .put("surface_target", SURFACE_TARGETS[surfaceIndex])
                .put("source_mode", SOURCE_MODES[surfaceIndex])
                .put("resource_plan_id", RESOURCE_PLAN_IDS[surfaceIndex])
                .put("runtime_profile_path", RUNTIME_PROFILE_PATHS[surfaceIndex])
                .put("profile_set_id", PROFILE_SET_ID)
                .put("profile_id", PROFILE_SCHEMA_IDS[conditionIndex])
                .put("default_profile_id", DEFAULT_PROFILE_ID)
                .put("dynamics_mode", "driver-profile")
                .put("driver0_value01", DRIVER0[conditionIndex])
                .put("driver2_value01", DRIVER2[conditionIndex])
                .put("driver1_value01", DRIVER1[conditionIndex])
                .put("driver3_value01", DRIVER3[conditionIndex])
                .put("expected_markers", new JSONArray()
                    .put("driverProfileSurfaceTarget=" + SURFACE_IDS[surfaceIndex])
                    .put("driverProfileSchemaId=" + PROFILE_SCHEMA_IDS[conditionIndex]));
            JSONObject candidate = new JSONObject()
                .put("schema", CANDIDATE_SCHEMA)
                .put("revision", revision)
                .put("profile_schema", PROFILE_SCHEMA_IDS[conditionIndex])
                .put("source", "driver_profile_panel")
                .put("private_particles", new JSONObject()
                    .put("visual_scale", surfaceIndex == 2 ? 1.0 : 0.70)
                    .put("world_anchor_scale_m", surfaceIndex == 2 ? 1.0 : 0.46)
                    .put("driver_values01", drivers)
                    .put("tracer", new JSONObject()
                        .put("draw_slots_per_oscillator", 7)
                        .put("lifetime_seconds", 0.5)
                        .put("copies_per_second", 14.0))
                    .put("driver_profile_selection", selection))
                .put("driver_profile_panel", selection)
                .put("apply", new JSONObject().put("mode", "apply-on-next-safe-frame"));
            JSONObject response = new JSONObject(
                PrivateParticlePanelController.submitCandidate(candidate.toString())
            );
            if (!"queued".equals(response.optString("status", ""))
                    || response.optLong("candidate_revision", 0L) != revision) {
                throw new IllegalStateException("runtime did not queue the exact candidate revision");
            }
            latestRequestedRevision = revision;
            receipt.setText(String.format(
                Locale.US,
                "Request receipt (not effective state): %s",
                response.toString(2)
            ));
            scheduleEffectiveReadbackRefresh();
        } catch (Exception error) {
            receipt.setText("Request rejected: " + error.getMessage());
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
                if (!STATUS_SCHEMA.equals(status.optString("schema", ""))) {
                    throw new IllegalStateException("status schema mismatch");
                }
                String ownerStatus = status.optString("status", "unknown");
                long candidateRevision = status.optLong("candidate_revision", 0L);
                long effectiveRevision = status.optLong("effective_revision", 0L);
                if ("rejected".equals(ownerStatus)) {
                    effectiveReadback.setText(
                        "Request rejected by consuming runtime (not effective):\n" + status.toString(2)
                    );
                    return;
                }
                if (!"applied".equals(ownerStatus)
                        || effectiveRevision <= 0L
                        || effectiveRevision != candidateRevision
                        || (latestRequestedRevision > 0L
                            && effectiveRevision != latestRequestedRevision)) {
                    effectiveReadback.setText(
                        "Native-effective readback pending; owner has not applied the exact request revision:\n"
                            + status.toString(2)
                    );
                    return;
                }
                effectiveReadback.setText(
                    "Native-effective readback (consuming runtime):\n" + status.toString(2)
                );
            } finally {
                input.close();
            }
        } catch (Exception error) {
            effectiveReadback.setText(
                "Native-effective readback unavailable until the consuming runtime publishes " + STATUS_FILE
            );
        }
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
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
        return ControlPanelActivity.panelButton(this, label);
    }

    private TextView text(String value, int sizeSp, int color) {
        return ControlPanelActivity.panelText(this, value, sizeSp, color, dp(8));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
