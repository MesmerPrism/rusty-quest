package io.github.mesmerprism.rustyquest.qcl041;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

public final class Qcl041WifiDirectHarnessService extends Service {
    private static final String CHANNEL_ID = "qcl041_wifi_direct_lifecycle";
    private static final int NOTIFICATION_ID = 41041;

    private Qcl041WifiDirectLifecycle lifecycle;
    private Qcl041ProbeConfig config;
    private Qcl041LifecycleArtifact artifact;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        if (lifecycle != null) {
            lifecycle.stop();
            lifecycle = null;
        }
        config = Qcl041ProbeConfig.from(intent);
        artifact = new Qcl041LifecycleArtifact(this, config);
        startForeground(
                NOTIFICATION_ID,
                buildNotification("QCL-041 Wi-Fi Direct lifecycle starting"));
        if (!hasRuntimeWifiDirectPermission()) {
            String permissionName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? Manifest.permission.NEARBY_WIFI_DEVICES
                    : Manifest.permission.ACCESS_FINE_LOCATION;
            artifact.setPermissionState(false, false, permissionName + "=false; foreground service cannot request UI");
            artifact.setCleanup(true, true, "Harness service stopped before Wi-Fi Direct mutation.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        lifecycle = new Qcl041WifiDirectLifecycle(
                this,
                config,
                artifact,
                new Qcl041WifiDirectLifecycle.StatusListener() {
                    @Override
                    public void onStatus(String status) {
                        updateNotification(status);
                        if ("QCL-041 lifecycle complete".equals(status)) {
                            stopForeground(true);
                            stopSelf(startId);
                        }
                    }
                });
        lifecycle.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (lifecycle != null) {
            lifecycle.stop();
            lifecycle = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean hasRuntimeWifiDirectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "QCL-041 Wi-Fi Direct",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String status) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    private Notification buildNotification(String status) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("QCL-041 Wi-Fi Direct")
                .setContentText(status)
                .setOngoing(true)
                .build();
    }
}
