package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import org.json.JSONObject;

/** Standalone Polar-only panel entry; the process runtime retains acquisition ownership. */
public class PolarPanelModule extends Activity implements PanelModule, PolarSensorPanel.Host {
    public static final String MODULE_ID = "polar-controls";
    private PolarSensorPanel panel;

    @Override
    public String panelModuleId() {
        return MODULE_ID;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        panel = PolarSensorRuntime.forApplication(getApplicationContext()).attachPanel(this, this);
        setContentView(panel.buildView());
    }

    @Override
    protected void onDestroy() {
        PolarSensorRuntime.forApplication(getApplicationContext()).detachPanel(this);
        panel = null;
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (panel != null) {
            panel.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void closePanelAndReturnToImmersive() {
        ControlPanelActivity.closePanelAndReturnToImmersive(this);
    }

    @Override
    public void onPolarStreamEvent(JSONObject event) {
        // The process runtime publishes the stream event. This view does not own acquisition.
    }
}
