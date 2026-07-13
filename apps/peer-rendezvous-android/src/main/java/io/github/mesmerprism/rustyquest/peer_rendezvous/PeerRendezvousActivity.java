package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public final class PeerRendezvousActivity extends Activity {
    private static final int PERMISSION_REQUEST = 9601;
    private static final String TAG = "RustyBleRendezvous";

    private TextView statusView;
    private Intent pendingStart;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        statusView = new TextView(this);
        statusView.setTextSize(18.0f);
        statusView.setPadding(32, 32, 32, 32);
        setContentView(statusView);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && PeerAuthorityIdentity.ACTION_GENERATE.equals(intent.getAction())) {
            try {
                String path = PeerAuthorityIdentity.generate(this, intent);
                Log.i(TAG, "RUSTY_QUEST_PEER_AUTHORITY_IDENTITY status=pass path=" + path);
                statusView.setText("Authority identity ready");
            } catch (Exception error) {
                Log.e(TAG, "RUSTY_QUEST_PEER_AUTHORITY_IDENTITY status=fail issue=" + error.getMessage());
                statusView.setText("Authority identity failed");
            }
            return;
        }
        if (intent != null && PeerAuthorityIdentity.ACTION_SIGN.equals(intent.getAction())) {
            try {
                String path = PeerAuthorityIdentity.sign(this, intent);
                Log.i(TAG, "RUSTY_QUEST_PEER_AUTHORITY_SIGNATURE status=pass path=" + path);
                statusView.setText("Authority signature ready");
            } catch (Exception error) {
                Log.e(TAG, "RUSTY_QUEST_PEER_AUTHORITY_SIGNATURE status=fail issue=" + error.getMessage());
                statusView.setText("Authority signature failed");
            }
            return;
        }
        if (intent != null && PeerRendezvousService.ACTION_STOP.equals(intent.getAction())) {
            stopService(new Intent(this, PeerRendezvousService.class));
            statusView.setText("Stopped");
            return;
        }
        if (intent == null || !PeerRendezvousService.ACTION_START.equals(intent.getAction())) {
            statusView.setText("Idle");
            return;
        }
        final BleRendezvousConfig config;
        try {
            config = BleRendezvousConfig.fromIntent(intent);
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "RUSTY_QUEST_BLE_RENDEZVOUS_ACTIVITY_BLOCKED issue=" + error.getMessage());
            statusView.setText("Blocked");
            return;
        }
        String[] missing = BleRendezvousPermissions.missing(this, config.mode);
        if (missing.length > 0) {
            pendingStart = new Intent(intent);
            requestPermissions(missing, PERMISSION_REQUEST);
            statusView.setText("Permission pending");
            return;
        }
        startSidecar(intent);
    }

    private void startSidecar(Intent source) {
        Log.i(TAG, "RUSTY_QUEST_BLE_RENDEZVOUS_ACTIVITY_START runId="
                + source.getStringExtra("run_id")
                + " mode=" + source.getStringExtra("mode")
                + " durationMs=" + source.getIntExtra("duration_ms", -1));
        Intent service = new Intent(source);
        service.setClass(this, PeerRendezvousService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        statusView.setText("Running");
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST || pendingStart == null) {
            return;
        }
        Intent next = pendingStart;
        pendingStart = null;
        try {
            BleRendezvousConfig config = BleRendezvousConfig.fromIntent(next);
            if (BleRendezvousPermissions.granted(this, config.mode)) {
                startSidecar(next);
                return;
            }
        } catch (IllegalArgumentException ignored) {
        }
        statusView.setText("Blocked");
    }
}
