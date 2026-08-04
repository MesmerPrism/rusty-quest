package io.github.mesmerprism.rustymanifold.broker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConnectionHubStartActivity extends Activity {
    public static final String ACTION_DEBUG_START_HUB =
            "io.github.mesmerprism.rustymanifold.broker.action.DEBUG_START_CONNECTION_HUB";
    public static final String ACTION_DEBUG_STOP_HUB =
            "io.github.mesmerprism.rustymanifold.broker.action.DEBUG_STOP_CONNECTION_HUB";
    public static final String ACTION_DEBUG_FORGET_HUB =
            "io.github.mesmerprism.rustymanifold.broker.action.DEBUG_FORGET_CONNECTION_HUB";
    private static final int CAMERA_PERMISSION_REQUEST = 4202;
    private TextView status;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        initializeAuthority();
        requestCameraPermissionIfNeeded();
        writeLaunchEvidence();
        renderManagementUi();
        dispatchDebugLifecycleAction(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void renderManagementUi() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = 32;
        layout.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Rusty Connection Hub");
        title.setTextSize(24.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(title);

        TextView warning = new TextView(this);
        warning.setText(
                "Trusted-LAN experimental mode. Pairing authenticates control but does not encrypt traffic. "
                        + "Do not use on an untrusted network. This Hub carries low-rate control and state only; "
                        + "media, sensor, BLE, LSL, Fleet, and other high-rate data stay in dedicated providers.");
        warning.setTextSize(16.0f);
        warning.setPadding(0, 16, 0, 16);
        layout.addView(warning);

        status = new TextView(this);
        status.setTextSize(17.0f);
        status.setTextIsSelectable(true);
        layout.addView(status);

        Button start = button("Start paired connection", new View.OnClickListener() {
            @Override public void onClick(View view) {
                startHubService(ConnectionHubStartService.ACTION_START_HUB);
            }
        });
        layout.addView(start);
        layout.addView(button("Stop connection", new View.OnClickListener() {
            @Override public void onClick(View view) {
                startHubService(ConnectionHubStartService.ACTION_STOP_HUB);
            }
        }));
        layout.addView(button("Forget trusted controllers", new View.OnClickListener() {
            @Override public void onClick(View view) {
                startHubService(ConnectionHubStartService.ACTION_FORGET_HUB);
            }
        }));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private void startHubService(String action) {
        Intent intent = new Intent(this, ConnectionHubStartService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        status.postDelayed(new Runnable() {
            @Override public void run() { refreshStatus(); }
        }, 250L);
    }

    private void refreshStatus() {
        if (status == null) { return; }
        ConnectionHubProcess hub = ConnectionHubProcess.get(getApplicationContext());
        ConnectionHubRuntime runtime = hub.runtime();
        String code = runtime.pairingCodeForWearer();
        StringBuilder text = new StringBuilder();
        text.append("Connection: ").append(runtime.listenerEnabled() ? "running" : "stopped");
        text.append("\nAddress: ").append(hub.displayOrigin());
        text.append("\nConfidentiality: none (trusted LAN only)");
        text.append("\nProduction eligible: no");
        text.append("\nSecurity mode: paired trusted-LAN experimental");
        if (code != null) {
            text.append("\nPairing code: ").append(code);
        }
        text.append("\nActive controller sessions: ").append(runtime.activeSessionCount());
        List<HubSurfaceRegistry.Entry> surfaces = runtime.registry().snapshot();
        Set<String> providerPackages = new LinkedHashSet<>();
        for (HubSurfaceRegistry.Entry entry : surfaces) {
            providerPackages.add(entry.descriptor.providerIdentity().packageName());
        }
        text.append("\nActive providers: ").append(providerPackages.size());
        text.append("\nRegistered surfaces: ").append(surfaces.size());
        for (HubSurfaceRegistry.Entry entry : surfaces) {
            text.append("\n  • ").append(entry.descriptor.displayLabel())
                    .append(" — ").append(entry.descriptor.providerIdentity().packageName());
        }
        if (surfaces.isEmpty()) {
            text.append("\n  No foreground provider app is currently offering controls.");
        }
        text.append("\nTrusted-controller inventory: authority-owned; Forget revokes all trusted controllers.");
        text.append("\n\nLegacy local broker compatibility: ws://127.0.0.1:8765/manifold/v1/events");
        status.setText(text.toString());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        initializeAuthority();
        requestCameraPermissionIfNeeded();
        writeLaunchEvidence();
        dispatchDebugLifecycleAction(intent);
        refreshStatus();
    }

    private void dispatchDebugLifecycleAction(Intent intent) {
        if (!GeneratedConnectionHubConfig.DEBUG_OPERATOR_ENABLED || intent == null) return;
        String action = intent.getAction();
        if (ACTION_DEBUG_START_HUB.equals(action)) {
            startHubService(ConnectionHubStartService.ACTION_START_HUB);
        } else if (ACTION_DEBUG_STOP_HUB.equals(action)) {
            startHubService(ConnectionHubStartService.ACTION_STOP_HUB);
        } else if (ACTION_DEBUG_FORGET_HUB.equals(action)) {
            startHubService(ConnectionHubStartService.ACTION_FORGET_HUB);
        }
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
