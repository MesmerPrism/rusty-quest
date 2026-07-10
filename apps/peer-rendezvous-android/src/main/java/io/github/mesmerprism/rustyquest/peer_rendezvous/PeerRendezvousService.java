package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public final class PeerRendezvousService extends Service {
    public static final String ACTION_START =
            "io.github.mesmerprism.rustyquest.peer_rendezvous.START";
    public static final String ACTION_STOP =
            "io.github.mesmerprism.rustyquest.peer_rendezvous.STOP";

    private static final String TAG = "RustyBleRendezvous";
    private static final String CHANNEL_ID = "rusty_peer_rendezvous";
    private static final int NOTIFICATION_ID = 9601;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BleRendezvousConfig config;
    private BleRendezvousEvidence evidence;
    private BleRendezvousGattServer server;
    private boolean finished = true;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            finishRun("stopped");
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!finished) {
            finishRun("superseded");
        }
        try {
            config = BleRendezvousConfig.fromIntent(intent);
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "RUSTY_QUEST_BLE_RENDEZVOUS_START_BLOCKED issue=" + error.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        Log.i(TAG, "RUSTY_QUEST_BLE_RENDEZVOUS_SERVICE_START runId=" + config.runId
                + " mode=" + config.mode
                + " durationMs=" + config.durationMs
                + " epoch=" + config.epoch
                + " wifiState=" + config.wifiState);

        startForegroundCompat(buildNotification());
        evidence = new BleRendezvousEvidence(config);
        finished = false;
        if (BleRendezvousConfig.MODE_SERVER.equals(config.mode)) {
            server = new BleRendezvousGattServer(getApplicationContext(), config, evidence);
            if (!server.start()) {
                finishRun(preflightBlocked() ? "blocked" : "fail");
                return START_NOT_STICKY;
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finishRun("auto");
                }
            }, config.durationMs);
        } else {
            BleRendezvousGattClient client = new BleRendezvousGattClient(
                    getApplicationContext(),
                    config,
                    evidence,
                    new BleRendezvousGattClient.Completion() {
                        @Override
                        public void complete(final String status) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    finishRun(status);
                                }
                            });
                        }
                    });
            client.start();
        }
        return START_NOT_STICKY;
    }

    private synchronized void finishRun(String requestedStatus) {
        if (finished) {
            return;
        }
        Log.i(TAG, "RUSTY_QUEST_BLE_RENDEZVOUS_SERVICE_FINISH runId="
                + (config == null ? "none" : config.runId)
                + " requestedStatus=" + requestedStatus);
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (server != null) {
            server.stop();
            server = null;
        }
        if (evidence == null) {
            stopForeground(true);
            stopSelf();
            return;
        }
        evidence.cleanupComplete = (!evidence.advertisingStarted || evidence.advertisingStopped)
                && (!evidence.scanStarted || evidence.scanStopped)
                && (!evidence.gattOpened || evidence.gattClosed)
                && (!evidence.connected || evidence.disconnected);
        String status = requestedStatus;
        if ("auto".equals(requestedStatus)) {
            if (evidence.connected
                    && evidence.messagesSent > 0
                    && evidence.messagesReceived > 0
                    && evidence.authenticatedMessages > 0
                    && evidence.authenticationFailures == 0) {
                status = "pass";
            } else if (evidence.adapterAvailable
                    && evidence.bluetoothEnabled
                    && evidence.permissionsGranted
                    && evidence.protocolSelfTestPassed
                    && evidence.gattOpened
                    && evidence.advertisingStarted) {
                status = "ready";
            } else {
                status = preflightBlocked() ? "blocked" : "fail";
            }
        } else if ("stopped".equals(requestedStatus) || "superseded".equals(requestedStatus)) {
            status = evidence.adapterAvailable
                    && evidence.bluetoothEnabled
                    && evidence.permissionsGranted
                    && evidence.protocolSelfTestPassed
                    && evidence.gattOpened
                    ? "ready"
                    : "blocked";
        }
        evidence.write(getApplicationContext(), status);
        stopForeground(true);
        stopSelf();
    }

    private boolean preflightBlocked() {
        return evidence == null
                || !evidence.adapterAvailable
                || !evidence.bluetoothEnabled
                || !evidence.permissionsGranted
                || !evidence.protocolSelfTestPassed;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        ensureChannel();
        Intent activityIntent = new Intent(this, PeerRendezvousActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Rusty Peer Rendezvous")
                .setContentText("BLE rendezvous active")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Rusty Peer Rendezvous",
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        if (!finished) {
            finishRun("stopped");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
