package io.github.mesmerprism.rustyquest.qcl041;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class Qcl041WifiDirectHarnessActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41041;

    private Qcl041ProbeConfig config;
    private Qcl041LifecycleArtifact artifact;
    private Qcl041WifiDirectLifecycle lifecycle;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        config = Qcl041ProbeConfig.from(getIntent());
        artifact = new Qcl041LifecycleArtifact(this, config);
        statusView = new TextView(this);
        int padding = 32;
        statusView.setPadding(padding, padding, padding, padding);
        statusView.setTextSize(18.0f);
        setContentView(statusView);
        setStatus("QCL-041 Wi-Fi Direct harness\n" + config.runId);
        requestRuntimePermissionsOrStart();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (lifecycle != null) {
            lifecycle.stop();
        }
        config = Qcl041ProbeConfig.from(intent);
        artifact = new Qcl041LifecycleArtifact(this, config);
        requestRuntimePermissionsOrStart();
    }

    @Override
    protected void onDestroy() {
        if (lifecycle != null) {
            lifecycle.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (missingRuntimePermissions().isEmpty()) {
            startLifecycle();
            return;
        }
        artifact.setPermissionState(false, false, "Wi-Fi Direct runtime permission denied.");
        artifact.setCleanup(true, true, "Harness stopped before Wi-Fi Direct mutation.");
        setStatus("QCL-041 blocked: runtime permission denied");
    }

    private void requestRuntimePermissionsOrStart() {
        List<String> missing = missingRuntimePermissions();
        if (missing.isEmpty()) {
            startLifecycle();
            return;
        }
        requestPermissions(missing.toArray(new String[missing.size()]), PERMISSION_REQUEST);
    }

    private List<String> missingRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return missing;
    }

    private void startLifecycle() {
        lifecycle = new Qcl041WifiDirectLifecycle(
                this,
                config,
                artifact,
                new Qcl041WifiDirectLifecycle.StatusListener() {
                    @Override
                    public void onStatus(final String status) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatus(status);
                            }
                        });
                    }
                });
        lifecycle.start();
    }

    private void setStatus(String status) {
        statusView.setText("QCL-041 Wi-Fi Direct harness\n"
                + Qcl041ProbeConfig.ACTIVITY_NAME
                + "\n"
                + status);
    }
}
