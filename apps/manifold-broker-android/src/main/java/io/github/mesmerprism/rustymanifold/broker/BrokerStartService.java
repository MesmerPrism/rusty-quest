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

public final class BrokerStartService extends Service {
    private static final String CHANNEL_ID = "rusty_manifold_broker";
    private static final int NOTIFICATION_ID = 82082;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(buildNotification());
        LocalManifoldBrokerServer.get().start(getApplicationContext());
        BrokerLaunchEvidence.write(
                getApplicationContext(),
                BrokerLaunchEvidence.SERVICE_NAME,
                "foreground_service");
        return START_STICKY;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
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
        Intent activityIntent = new Intent(this, BrokerStartActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Rusty Manifold Broker")
                .setContentText("Local Manifold broker is running")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pendingIntent)
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
                "Rusty Manifold Broker",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the local Manifold broker active for live validation.");
        manager.createNotificationChannel(channel);
    }
}
