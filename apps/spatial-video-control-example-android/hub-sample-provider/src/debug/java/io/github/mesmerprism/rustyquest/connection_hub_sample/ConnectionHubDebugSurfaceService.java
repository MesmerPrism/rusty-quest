package io.github.mesmerprism.rustyquest.connection_hub_sample;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** DUMP-gated, debug-only off-head lifecycle for the sample Hub surface. */
public final class ConnectionHubDebugSurfaceService extends Service {
    public static final String ACTION_START =
            "io.github.mesmerprism.rustyquest.connection_hub_sample.action.START_CONNECTION_HUB_DEBUG_SURFACE";
    public static final String ACTION_STOP =
            "io.github.mesmerprism.rustyquest.connection_hub_sample.action.STOP_CONNECTION_HUB_DEBUG_SURFACE";
    private static final String TAG = "RQConnectionHubSample";
    private static final String CHANNEL_ID = "connection-hub-sample-debug";
    private static final int NOTIFICATION_ID = 2601;

    private ConnectionHubSampleProvider provider;

    @Override public void onCreate() {
        super.onCreate();
        provider = new ConnectionHubSampleProvider(this, (phase, toggled) ->
                Log.i(TAG, "phase=" + phase.replace(' ', '_') + " toggled=" + toggled));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_START.equals(action)) {
            startForeground(NOTIFICATION_ID, notification());
            provider.start();
            Log.i(TAG, "operator_action=start");
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            provider.stop();
            Log.i(TAG, "operator_action=stop");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        Log.w(TAG, "operator_action=rejected");
        stopSelfResult(startId);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        provider.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Connection Hub sample (debug)",
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Connection Hub sample")
                .setContentText("Debug surface provider is active")
                .setOngoing(true)
                .build();
    }
}
