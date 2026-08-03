package io.github.mesmerprism.rustyquest.connection_hub_sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Second-package conformance provider for persistent Hub handoff validation. */
public final class ConnectionHubSampleActivity extends Activity {
    private ConnectionHubSampleProvider provider;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(20f);
        status.setPadding(32, 32, 32, 32);
        provider = new ConnectionHubSampleProvider(this, this::render);
        render("idle", false);
        setContentView(status);
    }

    @Override protected void onStart() {
        super.onStart();
        provider.start();
    }

    @Override protected void onStop() {
        provider.stop();
        super.onStop();
    }

    private void render(String phase, boolean toggled) {
        status.setText("Connection Hub Sample\n" + phase + "\nToggle state: " + toggled);
    }
}
