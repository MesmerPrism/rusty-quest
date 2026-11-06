package io.github.mesmerprism.rustyquest.displaystream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

public final class DisplayStreamService extends Service {
    private static final String TAG = "RustyDisplayStream";
    private static final String CHANNEL_ID = "rusty-quest-display-stream";
    private static final int NOTIFICATION_ID = 9079;
    // Survives Service object replacement in this process. Uncertain retirement stays retained.
    private static final DisplayStreamAttempts RETAINED = new DisplayStreamAttempts();
    private DisplayStreamAttempt owned;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RETAINED.available()) {
            Log.w(TAG, "RUSTY_QUEST_DISPLAY_STREAM status=prior_retirement_pending");
            return START_NOT_STICKY;
        }
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        int code = intent.getIntExtra(DisplayStreamConfig.EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(DisplayStreamConfig.EXTRA_RESULT_DATA);
        if (code == 0 || data == null) { stopSelf(startId); return START_NOT_STICKY; }
        DisplayStreamConfig config = DisplayStreamConfig.fromIntent(intent);
        startForegroundCompat(buildNotification("Headset display streaming"));
        DisplayStreamAttempt attempt = new DisplayStreamAttempt(
                java.util.UUID.randomUUID().toString().replace("-", ""),
                Integer.toString(startId), java.util.UUID.randomUUID().toString().replace("-", ""));
        RETAINED.install(attempt);
        owned = attempt;
        attempt.start(a -> runStream(a, config, code, data));
        Thread observer = new Thread(() -> {
            try {
                DisplayStreamAttempt.Snapshot result = attempt.awaitRetirement(0);
                Log.i(TAG, "RUSTY_QUEST_DISPLAY_STREAM status="
                        + (result.successful() ? "retired" : "retirement_failed")
                        + " workerRetired=" + result.workerRetired + " resourcesRetired=" + result.resourcesRetired);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (RETAINED.isCurrent(attempt) && result.retired()) {
                        stopForeground(true); stopSelf(startId);
                    }
                });
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }, "RustyQuestDisplayRetirement");
        observer.start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (owned != null) owned.stopOwned();
        super.onDestroy(); // No main-thread join, flag reset or premature owner release.
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void runStream(DisplayStreamAttempt attempt, DisplayStreamConfig config,
                           int resultCode, Intent resultData) throws Exception {
        SocketChannel channel = SocketChannel.open();
        attempt.ownChannel(channel); // Registered before a blocking connect or write.
        channel.configureBlocking(true);
        channel.socket().connect(new InetSocketAddress(config.host, config.port), 5000);
        channel.socket().setTcpNoDelay(true);
        H264SurfaceEncoder encoder = H264SurfaceEncoder.create(config.width, config.height, config.bitrateBps, config.fps);
        attempt.ownResource(encoder::close);
        Rmanvid1Writer writer = new Rmanvid1Writer(channel);
        writer.writeHeader(config.width, config.height, new JSONObject()
                .put("schema", "rusty.quest.display_stream.metadata.v1")
                .put("source_kind", "media_projection").put("transport", "adb_reverse_tcp")
                .put("encoder", "android_mediacodec_surface").put("encoder_name", encoder.encoderName())
                .put("low_latency_requested", true).put("packet_io", "gathered_bytebuffer")
                .put("width", config.width).put("height", config.height)
                .put("fps", config.fps).put("bitrate_bps", config.bitrateBps));
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("projection token rejected");
        attempt.ownResource(projection::stop);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { attempt.stopOwned(); }
        }, new Handler(Looper.getMainLooper()));
        VirtualDisplay display = projection.createVirtualDisplay("RustyQuestMediaProjectionH264",
                config.width, config.height, getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoder.inputSurface(), null, null);
        if (display == null) throw new IllegalStateException("projection display unavailable");
        attempt.ownResource(display::release);
        encoder.requestSyncFrame();
        long deadline = config.durationSeconds > 0
                ? SystemClock.elapsedRealtime() + config.durationSeconds * 1000L : Long.MAX_VALUE;
        H264SurfaceEncoder.PacketSink sink = (pts, packetFlags, payload) -> writer.writePacket(
                pts, packetFlags, payload, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis() * 1_000_000L);
        while (!attempt.stopRequested() && SystemClock.elapsedRealtime() < deadline) encoder.drainAvailable(10_000, sink);
        if (!attempt.stopRequested()) {
            encoder.signalEndOfInputStream();
            for (int i = 0; i < 50 && !encoder.drainAvailable(20_000, sink); i++) { }
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Rusty Quest display streaming",
                NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Rusty Quest")
            .setContentText(text)
            .setOngoing(true)
            .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
