package io.github.mesmerprism.rustymanifold.broker;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

public final class BrokerStartActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 4202;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        initializeAuthority();
        requestCameraPermissionIfNeeded();
        LocalManifoldBrokerServer.get().start(getApplicationContext());
        writeLaunchEvidence();

        TextView status = new TextView(this);
        status.setText(
                "Rusty Manifold Broker\n"
                        + BrokerLaunchEvidence.ACTIVITY_NAME
                        + "\nws://127.0.0.1:8765/manifold/v1/events");
        status.setTextSize(18.0f);
        int padding = 32;
        status.setPadding(padding, padding, padding, padding);
        setContentView(status);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        initializeAuthority();
        requestCameraPermissionIfNeeded();
        LocalManifoldBrokerServer.get().start(getApplicationContext());
        writeLaunchEvidence();
    }

    private void initializeAuthority() {
        try {
            ManifoldRuntimeAuthorityBridge.initialize();
        } catch (Exception error) {
            throw new IllegalStateException("Manifold broker authority initialization failed", error);
        }
    }

    private void requestCameraPermissionIfNeeded() {
        if (!GeneratedBrokerProductConfig.CAMERA_MEDIA_ENABLED) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void writeLaunchEvidence() {
        BrokerLaunchEvidence.write(
                getApplicationContext(),
                BrokerLaunchEvidence.ACTIVITY_NAME,
                "activity");
    }
}
