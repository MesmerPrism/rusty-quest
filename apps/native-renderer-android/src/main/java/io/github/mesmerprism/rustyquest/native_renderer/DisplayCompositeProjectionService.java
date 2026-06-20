package io.github.mesmerprism.rustyquest.native_renderer;

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
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Surface;
import android.util.DisplayMetrics;

public final class DisplayCompositeProjectionService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_MAX_IMAGES = "max_images";
    public static final String EXTRA_FPS_CAP = "fps_cap";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_FEEDBACK_ENABLED = "feedback_enabled";

    private static final String CHANNEL_ID = "rusty-quest-display-composite";
    private static final int NOTIFICATION_ID = 7421;
    private static final int EVENT_START_REQUESTED = 1;
    private static final int EVENT_STARTED = 2;
    private static final int EVENT_STOPPED = 3;
    private static final int EVENT_ERROR = 4;

    private static boolean nativeBridgeLoaded;

    static {
        try {
            System.loadLibrary("rusty_quest_native_renderer");
            nativeBridgeLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            nativeBridgeLoaded = false;
        }
    }

    private HandlerThread projectionThread;
    private Handler projectionHandler;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Surface displaySurface;
    private int fpsCap;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification("Display composite capture active"));
        nativeDisplayCompositeLifecycleEvent(EVENT_START_REQUESTED, 0, 0, 0, 0, 0);

        try {
            startProjection(intent);
            return START_STICKY;
        } catch (RuntimeException error) {
            nativeDisplayCompositeLifecycleEvent(EVENT_ERROR, -1, 0, 0, 0, 0);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        stopProjection();
        nativeDisplayCompositeLifecycleEvent(EVENT_STOPPED, 0, 0, 0, 0, fpsCap);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startProjection(Intent intent) {
        stopProjection();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            throw new IllegalArgumentException("MediaProjection result data missing");
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = clamp(intent.getIntExtra(EXTRA_WIDTH, 1280), 320, 4096);
        int height = clamp(intent.getIntExtra(EXTRA_HEIGHT, 720), 240, 4096);
        int maxImages = clamp(intent.getIntExtra(EXTRA_MAX_IMAGES, 3), 2, 6);
        fpsCap = clamp(intent.getIntExtra(EXTRA_FPS_CAP, 30), 1, 90);

        if (!nativeBridgeLoaded) {
            throw new IllegalStateException("native display-composite bridge unavailable");
        }

        projectionThread = new HandlerThread("RustyQuestDisplayComposite");
        projectionThread.start();
        projectionHandler = new Handler(projectionThread.getLooper());

        displaySurface = nativeCreateDisplayCompositeSurface(width, height, maxImages, fpsCap);
        if (displaySurface == null) {
            throw new IllegalStateException("native display-composite surface creation failed");
        }

        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            throw new IllegalStateException("MediaProjection token was not accepted");
        }
        mediaProjection.registerCallback(
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            },
            projectionHandler
        );

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "RustyQuestDisplayComposite",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            displaySurface,
            null,
            projectionHandler
        );
        if (virtualDisplay == null) {
            throw new IllegalStateException("VirtualDisplay creation failed");
        }

        nativeDisplayCompositeLifecycleEvent(
            EVENT_STARTED,
            resultCode,
            width,
            height,
            maxImages,
            fpsCap
        );
    }

    private void stopProjection() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (displaySurface != null) {
            displaySurface.release();
            displaySurface = null;
        }
        if (nativeBridgeLoaded) {
            nativeStopDisplayCompositeStream();
        }
        if (projectionThread != null) {
            projectionThread.quitSafely();
            projectionThread = null;
            projectionHandler = null;
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Display Composite Capture",
                NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }

        Notification.Builder builder =
            Build.VERSION.SDK_INT >= 26
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private static int clamp(int value, int minValue, int maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private static native Surface nativeCreateDisplayCompositeSurface(
        int width,
        int height,
        int maxImages,
        int fpsCap
    );

    private static native void nativeStopDisplayCompositeStream();

    private static native void nativeDisplayCompositeLifecycleEvent(
        int eventCode,
        int resultCode,
        int width,
        int height,
        int maxImages,
        int fpsCap
    );
}
