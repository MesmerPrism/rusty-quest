package io.github.mesmerprism.rustymanifold.broker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
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

    private static final int COLOR_BACKGROUND = 0xff25211e;
    private static final int COLOR_SURFACE = 0xff332d29;
    private static final int COLOR_SURFACE_ELEVATED = 0xff403833;
    private static final int COLOR_BORDER = 0xff5b4e46;
    private static final int COLOR_TEXT = 0xfffff8f2;
    private static final int COLOR_TEXT_MUTED = 0xffe3d7ce;
    private static final int COLOR_TEXT_SUBTLE = 0xffb9aaa0;
    private static final int COLOR_ACCENT = 0xffef8a52;
    private static final int COLOR_ACCENT_DARK = 0xff3c281d;
    private static final int COLOR_SUCCESS = 0xff77c99a;
    private static final int COLOR_SUCCESS_DARK = 0xff1d3d2c;
    private static final int COLOR_WARNING = 0xff4a3423;
    private static final int COLOR_WARNING_BORDER = 0xff9c693e;

    private TextView status;
    private TextView endpoint;
    private TextView pairingCode;
    private TextView pairingHint;
    private TextView controllerCount;
    private TextView providerCount;
    private TextView surfaceCount;
    private TextView providerSummary;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.TOP);
        layout.setBaselineAligned(false);
        int outerPadding = dp(28);
        layout.setPadding(outerPadding, outerPadding, outerPadding, outerPadding);

        LinearLayout overviewPane = panel();
        layout.addView(overviewPane, weightedPane(0.9f));

        TextView title = text("Rusty Connection Hub", 27.0f, COLOR_TEXT, Typeface.BOLD);
        overviewPane.addView(title);
        addTop(overviewPane,
                text("Pair controllers and expose controls from the app currently running on this Quest.",
                        15.0f, COLOR_TEXT_MUTED, Typeface.NORMAL), 7);

        TextView securityLabel = sectionLabel("Security posture");
        addTop(overviewPane, securityLabel, 26);
        TextView warning = text(
                "Trusted LAN only · Experimental\nPairing authenticates control, but traffic is not encrypted. "
                        + "Use this Hub only on a network you trust. Not production eligible.",
                14.0f, COLOR_TEXT_MUTED, Typeface.NORMAL);
        warning.setLineSpacing(0.0f, 1.18f);
        warning.setPadding(dp(16), dp(14), dp(16), dp(14));
        warning.setBackground(roundedBackground(COLOR_WARNING, COLOR_WARNING_BORDER, 10));
        addTop(overviewPane, warning, 8);

        TextView addressLabel = sectionLabel("Controller address");
        addTop(overviewPane, addressLabel, 24);
        endpoint = text("Unavailable", 17.0f, COLOR_TEXT, Typeface.BOLD);
        endpoint.setTextIsSelectable(true);
        endpoint.setPadding(dp(14), dp(13), dp(14), dp(13));
        endpoint.setBackground(roundedBackground(COLOR_SURFACE_ELEVATED, COLOR_BORDER, 10));
        addTop(overviewPane, endpoint, 8);

        TextView transport = text(
                "Low-rate control and state only\nMedia, sensors, BLE, LSL, and Fleet remain in their dedicated providers.",
                13.0f, COLOR_TEXT_SUBTLE, Typeface.NORMAL);
        transport.setLineSpacing(0.0f, 1.16f);
        addTop(overviewPane, transport, 20);

        TextView compatibility = text(
                "Legacy local compatibility  ·  ws://127.0.0.1:8765/manifold/v1/events",
                11.5f, COLOR_TEXT_SUBTLE, Typeface.NORMAL);
        compatibility.setTextIsSelectable(true);
        addTop(overviewPane, compatibility, 24);

        Space paneGap = new Space(this);
        layout.addView(paneGap, new LinearLayout.LayoutParams(dp(16), 1));

        LinearLayout controlPane = panel();
        layout.addView(controlPane, weightedPane(1.1f));

        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);
        statusHeader.setBaselineAligned(false);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(sectionLabel("Connection"));
        TextView headingTitle = text("Hub status", 22.0f, COLOR_TEXT, Typeface.BOLD);
        addTop(heading, headingTitle, 5);
        statusHeader.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        status = text("Stopped", 13.0f, COLOR_TEXT_MUTED, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(14), dp(8), dp(14), dp(8));
        status.setBackground(roundedBackground(COLOR_SURFACE_ELEVATED, COLOR_BORDER, 8));
        statusHeader.addView(status);
        controlPane.addView(statusHeader);

        LinearLayout pairingCard = new LinearLayout(this);
        pairingCard.setOrientation(LinearLayout.VERTICAL);
        pairingCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        pairingCard.setBackground(roundedBackground(COLOR_ACCENT_DARK, COLOR_ACCENT, 10));
        pairingCard.addView(sectionLabel("Pairing code"));
        pairingCode = text("—", 32.0f, COLOR_TEXT, Typeface.BOLD);
        pairingCode.setLetterSpacing(0.14f);
        pairingCode.setTextIsSelectable(true);
        addTop(pairingCard, pairingCode, 5);
        pairingHint = text("Start the Hub to make a code available.",
                12.5f, COLOR_TEXT_MUTED, Typeface.NORMAL);
        addTop(pairingCard, pairingHint, 3);
        addTop(controlPane, pairingCard, 18);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setBaselineAligned(false);
        controllerCount = metric(metrics, "Controllers");
        addHorizontalGap(metrics, 8);
        providerCount = metric(metrics, "Providers");
        addHorizontalGap(metrics, 8);
        surfaceCount = metric(metrics, "Surfaces");
        addTop(controlPane, metrics, 12);

        TextView providerLabel = sectionLabel("Available app controls");
        addTop(controlPane, providerLabel, 22);
        providerSummary = text("No foreground provider app is currently offering controls.",
                14.0f, COLOR_TEXT_MUTED, Typeface.NORMAL);
        providerSummary.setMinHeight(dp(76));
        providerSummary.setLineSpacing(0.0f, 1.18f);
        providerSummary.setPadding(dp(14), dp(12), dp(14), dp(12));
        providerSummary.setBackground(roundedBackground(COLOR_SURFACE_ELEVATED, COLOR_BORDER, 10));
        addTop(controlPane, providerSummary, 8);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setBaselineAligned(false);
        actions.addView(button("Start Hub", true, new View.OnClickListener() {
            @Override public void onClick(View view) {
                startHubService(ConnectionHubStartService.ACTION_START_HUB);
            }
        }), weightedAction());
        addHorizontalGap(actions, 8);
        actions.addView(button("Stop", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                startHubService(ConnectionHubStartService.ACTION_STOP_HUB);
            }
        }), weightedAction());
        addHorizontalGap(actions, 8);
        actions.addView(button("Forget controllers", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                startHubService(ConnectionHubStartService.ACTION_FORGET_HUB);
            }
        }), weightedAction());
        addTop(controlPane, actions, 18);
        addTop(controlPane, text(
                "Forget revokes the complete authority-owned trusted-controller inventory.",
                11.5f, COLOR_TEXT_SUBTLE, Typeface.NORMAL), 8);

        scroll.addView(layout);
        setContentView(scroll);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(24), dp(22));
        panel.setBackground(roundedBackground(COLOR_SURFACE, COLOR_BORDER, 14));
        return panel;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private TextView sectionLabel(String value) {
        TextView label = text(value.toUpperCase(), 11.0f, COLOR_ACCENT, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        return label;
    }

    private TextView metric(LinearLayout parent, String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(roundedBackground(COLOR_SURFACE_ELEVATED, COLOR_BORDER, 10));
        TextView value = text("0", 21.0f, COLOR_TEXT, Typeface.BOLD);
        card.addView(value);
        addTop(card, text(label, 11.5f, COLOR_TEXT_SUBTLE, Typeface.NORMAL), 2);
        parent.addView(card, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        return value;
    }

    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13.5f);
        button.setTextColor(primary ? COLOR_BACKGROUND : COLOR_TEXT_MUTED);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(50));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(roundedBackground(
                primary ? COLOR_ACCENT : COLOR_SURFACE_ELEVATED,
                primary ? COLOR_ACCENT : COLOR_BORDER,
                8));
        button.setOnClickListener(listener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
        }
        return button;
    }

    private GradientDrawable roundedBackground(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private LinearLayout.LayoutParams weightedPane(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
    }

    private LinearLayout.LayoutParams weightedAction() {
        return new LinearLayout.LayoutParams(0, dp(50), 1.0f);
    }

    private void addTop(LinearLayout parent, View child, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        parent.addView(child, params);
    }

    private void addHorizontalGap(LinearLayout parent, int widthDp) {
        Space gap = new Space(this);
        parent.addView(gap, new LinearLayout.LayoutParams(dp(widthDp), 1));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        boolean running = runtime.listenerEnabled();
        status.setText(running ? "Running" : "Stopped");
        status.setTextColor(running ? COLOR_SUCCESS : COLOR_TEXT_MUTED);
        status.setBackground(roundedBackground(
                running ? COLOR_SUCCESS_DARK : COLOR_SURFACE_ELEVATED,
                running ? COLOR_SUCCESS : COLOR_BORDER,
                8));
        endpoint.setText(hub.displayOrigin());

        String code = runtime.pairingCodeForWearer();
        pairingCode.setText(code == null ? "—" : code);
        pairingHint.setText(code == null
                ? "Start the Hub to make a code available."
                : "Enter this six-digit code in the controller browser.");

        List<HubSurfaceRegistry.Entry> surfaces = runtime.registry().snapshot();
        Set<String> providerPackages = new LinkedHashSet<>();
        for (HubSurfaceRegistry.Entry entry : surfaces) {
            providerPackages.add(entry.descriptor.providerIdentity().packageName());
        }
        controllerCount.setText(String.valueOf(runtime.activeSessionCount()));
        providerCount.setText(String.valueOf(providerPackages.size()));
        surfaceCount.setText(String.valueOf(surfaces.size()));

        StringBuilder providers = new StringBuilder();
        for (HubSurfaceRegistry.Entry entry : surfaces) {
            if (providers.length() > 0) { providers.append('\n'); }
            providers.append("• ").append(entry.descriptor.displayLabel())
                    .append("\n  ").append(entry.descriptor.providerIdentity().packageName());
        }
        if (providers.length() == 0) {
            providers.append("No foreground provider app is currently offering controls.");
        }
        providerSummary.setText(providers.toString());
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
