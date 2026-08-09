package io.github.mesmerprism.rustymanifold.broker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

public final class ConnectionHubStartService extends Service {
    public static final String ACTION_START_HUB =
            "io.github.mesmerprism.rustymanifold.broker.action.START_CONNECTION_HUB";
    public static final String ACTION_STOP_HUB =
            "io.github.mesmerprism.rustymanifold.broker.action.STOP_CONNECTION_HUB";
    public static final String ACTION_FORGET_HUB =
            "io.github.mesmerprism.rustymanifold.broker.action.FORGET_CONNECTION_HUB";
    private static final String CHANNEL_ID = "rusty_manifold_broker";
    private static final int NOTIFICATION_ID = 82082;
    private static final long EXPIRY_RECONCILE_INTERVAL_MS = 60_000L;
    private final Handler expiryHandler = new Handler(Looper.getMainLooper());
    private final Runnable expiryTask = new Runnable() {
        @Override public void run() {
            ConnectionHubProcess.get(getApplicationContext()).runtime().expireNow();
            expiryHandler.postDelayed(this, EXPIRY_RECONCILE_INTERVAL_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        initializeAuthority();
        startForegroundCompat(buildNotification());
        LocalManifoldBrokerServer.get().start(getApplicationContext());
        ConnectionHubProcess hub = ConnectionHubProcess.get(getApplicationContext());
        ConnectionHubOperatorController operator =
                hub.operatorController();
        expiryHandler.removeCallbacks(expiryTask);
        expiryHandler.postDelayed(expiryTask, EXPIRY_RECONCILE_INTERVAL_MS);
        String action = intent == null ? null : intent.getAction();
        try {
            if (ACTION_START_HUB.equals(action)) {
                requireConfirmed(operator.execute(
                        ConnectionHubOperatorController.ACTION_START, new JSONObject()));
            } else if (ACTION_STOP_HUB.equals(action)) {
                requireConfirmed(operator.execute(
                        ConnectionHubOperatorController.ACTION_STOP, new JSONObject()));
                stopForeground(true);
                stopSelf();
            } else if (ACTION_FORGET_HUB.equals(action)) {
                requireConfirmed(operator.execute(
                        ConnectionHubOperatorController.ACTION_FORGET, new JSONObject()));
            } else {
                hub.resumeDesiredListener();
            }
        } catch (Exception error) {
            hub.runtime().noteListenerFailure(
                    "listener_start_failed_" + error.getClass().getSimpleName());
        }
        BrokerLaunchEvidence.write(
                getApplicationContext(),
                BrokerLaunchEvidence.SERVICE_NAME,
                "foreground_service");
        return START_STICKY;
    }

    private static void requireConfirmed(ConnectionHubOperatorController.Result result) {
        if (!result.receipt.optBoolean("applied", false)
                || !"confirmed".equals(result.receipt.optString("effect_status"))) {
            throw new IllegalStateException("connection_hub_operator_effect_not_confirmed");
        }
    }

    @Override public void onDestroy() {
        expiryHandler.removeCallbacks(expiryTask);
        super.onDestroy();
    }

    private void initializeAuthority() {
        try {
            ManifoldRuntimeAuthorityBridge.initialize();
        } catch (Exception error) {
            throw new IllegalStateException("Manifold broker authority initialization failed", error);
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (GeneratedBrokerProductConfig.CAMERA_MEDIA_ENABLED) {
                serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    serviceTypes);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        ensureChannel();
        Intent activityIntent = new Intent(this, ConnectionHubStartActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Intent stopIntent = new Intent(this, ConnectionHubStartService.class);
        stopIntent.setAction(ACTION_STOP_HUB);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder
                .setContentTitle("Rusty Connection Hub")
                .setContentText("Connection Hub is running — trusted LAN, no encryption")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Rusty Connection Hub",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the user-started Rusty Connection Hub active.");
        manager.createNotificationChannel(channel);
    }
}
