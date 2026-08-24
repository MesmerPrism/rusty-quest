package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONObject;

/** Product-owned low-rate controls for the generic private-particle renderer slot. */
public class PrivateParticlePanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID = "private-particle-controls";
    private static final int PANEL_BG = Color.rgb(17, 18, 22);
    private static final int PANEL_FG = Color.rgb(238, 240, 244);
    private static final int PANEL_MUTED = Color.rgb(170, 176, 186);

    private CheckBox enabled;
    private SeekBar intensity;
    private TextView intensityLabel;
    private TextView readback;

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
        TextView title = text("Private Particle Controls", 22, PANEL_FG);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button resume = button("Resume VR");
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { launchImmersiveRenderer(); }
        });
        header.addView(resume);
        root.addView(header);
        root.addView(text(
            "Requests are low-rate adapters. The consuming Rust renderer owns effective state and returns normalized readback.",
            13,
            PANEL_MUTED
        ));

        enabled = new CheckBox(this);
        enabled.setText("Private particles enabled");
        enabled.setTextColor(PANEL_FG);
        enabled.setChecked(true);
        root.addView(enabled);

        intensityLabel = text("Intensity: 1.00", 14, PANEL_FG);
        root.addView(intensityLabel);
        intensity = new SeekBar(this);
        intensity.setMax(200);
        intensity.setProgress(100);
        intensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                intensityLabel.setText(String.format(java.util.Locale.US, "Intensity: %.2f", progress / 100.0));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(intensity);

        Button apply = button("Apply request");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { submitRequest(); }
        });
        root.addView(apply);
        readback = text("Native-effective readback: waiting for request", 13, PANEL_MUTED);
        root.addView(readback);
        return scroll;
    }

    private void submitRequest() {
        try {
            JSONObject request = new JSONObject()
                .put("schema", "rusty.quest.private_particle.panel_request.v1")
                .put("enabled", enabled.isChecked())
                .put("intensity", intensity.getProgress() / 100.0);
            JSONObject effective = new JSONObject(PrivateParticlePanelController.submitCandidate(request.toString()));
            readback.setText("Native-effective readback:\n" + effective.toString(2));
        } catch (Exception error) {
            readback.setText("Request rejected: " + error.getMessage());
        }
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
}
