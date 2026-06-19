package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class ControlPanelActivity extends Activity {
    public static final String ACTION_TOGGLE_PANEL =
        "io.github.mesmerprism.rustyquest.native_renderer.action.TOGGLE_PANEL";
    private static final String CANDIDATE_FILE = "stimulus_volume_candidate.json";
    private static final String STATUS_FILE = "stimulus_volume_status.json";
    private static final String PROFILE_SCHEMA = "rusty.quest.stimulus_volume.profile.v1";

    private CheckBox safetyAck;
    private CheckBox enabledRequested;
    private CheckBox randomizeEnabled;
    private EditText minHz;
    private EditText maxHz;
    private EditText raymarchSamples;
    private EditText centralFovFraction;
    private EditText gradientSmoothing;
    private Spinner renderTarget;
    private Spinner patternFamily;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        updateStatus("Panel ready. Candidate path: " + new File(getFilesDir(), CANDIDATE_FILE));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_TOGGLE_PANEL.equals(intent.getAction())) {
            finish();
        }
    }

    private View buildContentView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = text("Rusty Quest Stimulus Panel", 22);
        root.addView(title);
        root.addView(text("Stages a low-rate stimulus candidate for the native OpenXR/Vulkan renderer.", 14));

        safetyAck = new CheckBox(this);
        safetyAck.setText("Photosensitive-risk acknowledgement");
        root.addView(safetyAck);

        enabledRequested = new CheckBox(this);
        enabledRequested.setText("Request active stimulus after launch");
        enabledRequested.setChecked(false);
        root.addView(enabledRequested);

        randomizeEnabled = new CheckBox(this);
        randomizeEnabled.setText("Enable right-primary randomize");
        randomizeEnabled.setChecked(true);
        root.addView(randomizeEnabled);

        renderTarget = spinner(new String[] {
            "512x512x2-rgba16f",
            "768x768x2-rgba16f",
            "1024x1024x2-rgba16f"
        });
        root.addView(label("Render target"));
        root.addView(renderTarget);

        patternFamily = spinner(new String[] {
            "randomized-trevor-vocabulary",
            "trevor-mix",
            "stripes",
            "ripples",
            "rays",
            "checker",
            "spiral",
            "noise-field"
        });
        root.addView(label("Pattern family"));
        root.addView(patternFamily);

        minHz = decimalInput("3.0");
        maxHz = decimalInput("40.0");
        raymarchSamples = integerInput("12");
        centralFovFraction = decimalInput("0.78");
        gradientSmoothing = decimalInput("0.65");
        root.addView(label("Randomize min Hz"));
        root.addView(minHz);
        root.addView(label("Randomize max Hz"));
        root.addView(maxHz);
        root.addView(label("Raymarch samples"));
        root.addView(raymarchSamples);
        root.addView(label("Central FOV fraction"));
        root.addView(centralFovFraction);
        root.addView(label("Gradient smoothing"));
        root.addView(gradientSmoothing);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(14), 0, dp(10));
        Button validate = button("Validate");
        validate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    buildCandidateJson();
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
        buttons.addView(validate);
        buttons.addView(stage);
        buttons.addView(stageLaunch);
        buttons.addView(close);
        root.addView(buttons);

        status = text("", 13);
        root.addView(status);
        return scroll;
    }

    private void stageCandidate(boolean launchAfterStage) {
        try {
            JSONObject candidate = buildCandidateJson();
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

    private JSONObject buildCandidateJson() throws Exception {
        boolean active = enabledRequested.isChecked();
        boolean acknowledged = safetyAck.isChecked();
        if (active && !acknowledged) {
            throw new IllegalArgumentException("acknowledgement is required before requesting active stimulus");
        }
        float min = parseFloat(minHz, "min Hz");
        float max = parseFloat(maxHz, "max Hz");
        if (min < 3.0f || max > 40.0f || min > max) {
            throw new IllegalArgumentException("randomize Hz must stay within 3.0-40.0 and min <= max");
        }
        int samples = parseInt(raymarchSamples, "raymarch samples");
        if (samples < 1 || samples > 48) {
            throw new IllegalArgumentException("raymarch samples must stay within 1-48");
        }
        float fov = parseFloat(centralFovFraction, "central FOV fraction");
        if (fov < 0.45f || fov > 1.0f) {
            throw new IllegalArgumentException("central FOV fraction must stay within 0.45-1.0");
        }
        float smoothing = parseFloat(gradientSmoothing, "gradient smoothing");
        if (smoothing < 0.0f || smoothing > 1.0f) {
            throw new IllegalArgumentException("gradient smoothing must stay within 0.0-1.0");
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
            .put("raymarch_samples", samples)
            .put("central_fov_fraction", fov)
            .put("gradient_smoothing", smoothing)
            .put("pattern_family", selected(patternFamily))
            .put("randomize", randomize);
        JSONObject apply = new JSONObject()
            .put("mode", "stage")
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
        if (status != null) {
            status.setText(message);
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 13);
        view.setPadding(0, dp(10), 0, dp(2));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        return button;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter =
            new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private EditText decimalInput(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(value);
        return input;
    }

    private EditText integerInput(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(value);
        return input;
    }

    private float parseFloat(EditText input, String label) {
        String value = input.getText().toString().trim();
        if (value.length() == 0) {
            throw new IllegalArgumentException(label + " is empty");
        }
        return Float.parseFloat(value);
    }

    private int parseInt(EditText input, String label) {
        String value = input.getText().toString().trim();
        if (value.length() == 0) {
            throw new IllegalArgumentException(label + " is empty");
        }
        return Integer.parseInt(value);
    }

    private String selected(Spinner spinner) {
        return String.valueOf(spinner.getSelectedItem());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
