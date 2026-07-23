package io.github.mesmerprism.rustyquest.fleetagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class FleetAgentService extends Service {
    public static final String ACTION_START =
            "io.github.mesmerprism.rustyquest.fleetagent.START";
    public static final String ACTION_STOP =
            "io.github.mesmerprism.rustyquest.fleetagent.STOP";

    private static final String TAG = "RustyFleetAgent";
    private static final String CHANNEL_ID = "rusty_fleet_agent";
    private static final int NOTIFICATION_ID = 9711;
    private static final String REVISION_PREFERENCES = "fleet-agent-revisions";

    private ScheduledExecutorService scheduler;
    private FleetAgentConfig config;
    private byte[] privateSeed;
    private String sourceEpoch;

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAgent("stopped");
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            Log.w(TAG, "RUSTY_QUEST_FLEET_AGENT_START_BLOCKED reason=exact_action_required");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (scheduler != null) {
            Log.i(TAG, "RUSTY_QUEST_FLEET_AGENT_ALREADY_RUNNING");
            return START_NOT_STICKY;
        }
        try {
            config = FleetAgentConfig.load(getApplicationContext());
            privateSeed = FleetAgentPrivateKey.load(getApplicationContext());
            sourceEpoch = createSourceEpoch();
        } catch (Exception error) {
            Log.w(TAG, "RUSTY_QUEST_FLEET_AGENT_START_BLOCKED reason="
                    + safeReason(error));
            FleetAgentReceipt.write(
                    getApplicationContext(),
                    "blocked",
                    0,
                    "none",
                    System.currentTimeMillis(),
                    null,
                    safeReason(error));
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        publishOnce();
                    }
                },
                0,
                config.intervalMs,
                TimeUnit.MILLISECONDS);
        Log.i(TAG, "RUSTY_QUEST_FLEET_AGENT_SERVICE_START epoch=" + sourceEpoch
                + " intervalMs=" + config.intervalMs);
        return START_NOT_STICKY;
    }

    private void publishOnce() {
        long attemptedAtMs = System.currentTimeMillis();
        long revision = nextRevision();
        try {
            String profileJson = config.runtimeProfile(revision, sourceEpoch);
            String snapshotJson = FleetAgentObservation.capture(getApplicationContext());
            String nativeResult = FleetAgentNativeBridge.produce(
                    profileJson,
                    snapshotJson,
                    privateSeed,
                    attemptedAtMs);
            JSONObject result = new JSONObject(nativeResult);
            if (!"ok".equals(result.optString("status"))) {
                String code = result.optJSONObject("error") == null
                        ? "native_production_failed"
                        : result.optJSONObject("error").optString(
                                "code",
                                "native_production_failed");
                FleetAgentReceipt.write(
                        getApplicationContext(),
                        "rejected_local",
                        revision,
                        sourceEpoch,
                        attemptedAtMs,
                        null,
                        code);
                Log.w(TAG, "RUSTY_QUEST_FLEET_AGENT_CHECKIN_LOCAL_REJECT revision="
                        + revision + " reason=" + code);
                return;
            }
            String envelope = result.getJSONObject("envelope").toString();
            FleetAgentPublisher.Result published =
                    FleetAgentPublisher.post(config.hubEndpoint, envelope);
            String status = published.accepted() ? "accepted_by_hub" : "rejected_by_hub";
            String detail = responseStatus(published.responseJson);
            FleetAgentReceipt.write(
                    getApplicationContext(),
                    status,
                    revision,
                    sourceEpoch,
                    attemptedAtMs,
                    published.statusCode,
                    detail);
            Log.i(TAG, "RUSTY_QUEST_FLEET_AGENT_CHECKIN_RESULT revision=" + revision
                    + " status=" + status
                    + " httpStatus=" + published.statusCode);
        } catch (Exception error) {
            String reason = safeReason(error);
            FleetAgentReceipt.write(
                    getApplicationContext(),
                    "transport_failed",
                    revision,
                    sourceEpoch,
                    attemptedAtMs,
                    null,
                    reason);
            Log.w(TAG, "RUSTY_QUEST_FLEET_AGENT_CHECKIN_TRANSPORT_FAILED revision="
                    + revision + " reason=" + reason);
        }
    }

    private long nextRevision() {
        SharedPreferences preferences =
                getSharedPreferences(REVISION_PREFERENCES, Context.MODE_PRIVATE);
        synchronized (FleetAgentService.class) {
            long current = preferences.getLong("next-source-revision", 1);
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("source_revision_exhausted");
            }
            long next = current + 1;
            if (!preferences.edit().putLong("next-source-revision", next).commit()) {
                throw new IllegalStateException("revision_persistence_failed");
            }
            return current;
        }
    }

    private static String responseStatus(String responseJson) {
        if (responseJson == null || responseJson.isEmpty()) {
            return "empty_hub_response";
        }
        try {
            JSONObject response = new JSONObject(responseJson);
            return response.optString("status", "hub_response_without_status");
        } catch (Exception ignored) {
            return "non_json_hub_response";
        }
    }

    private static String safeReason(Exception error) {
        String message = error.getMessage();
        if (message == null || !message.matches("[a-zA-Z0-9_.-]{1,96}")) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private static String createSourceEpoch() {
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder("agent.");
        for (byte item : random) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }

    private synchronized void stopAgent(String status) {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    Log.w(TAG, "RUSTY_QUEST_FLEET_AGENT_STOP_TIMEOUT");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        if (privateSeed != null) {
            java.util.Arrays.fill(privateSeed, (byte) 0);
            privateSeed = null;
        }
        Log.i(TAG, "RUSTY_QUEST_FLEET_AGENT_SERVICE_STOP status=" + status);
        stopForeground(true);
        stopSelf();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        ensureChannel();
        Intent activityIntent = new Intent(this, FleetAgentActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Rusty Fleet Agent")
                .setContentText("Local fleet monitoring is active")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
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
                "Rusty Fleet Agent",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Low-rate local Rusty Fleet monitoring.");
        manager.createNotificationChannel(channel);
    }

    @Override
    public synchronized void onDestroy() {
        if (scheduler != null || privateSeed != null) {
            stopAgent("destroyed");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
