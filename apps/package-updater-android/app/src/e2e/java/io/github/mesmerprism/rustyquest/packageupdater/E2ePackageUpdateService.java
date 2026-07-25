package io.github.mesmerprism.rustyquest.packageupdater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-exported foreground worker for the test-only CLI. It can prepare an
 * attended session, but it has no path that approves Package Installer.
 */
public final class E2ePackageUpdateService extends Service {
    private static final String LOG_TAG = "RustyUpdaterE2e";
    static final String ACTION_CHECK =
            "io.github.mesmerprism.rustyquest.packageupdater.e2e.CHECK";
    private static final String CHANNEL_ID =
            "rusty-package-updater-e2e-preparation";
    private static final int NOTIFICATION_ID = 7315;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL_REQUESTED =
            new AtomicBoolean(false);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static void startCheck(Context context) {
        Intent intent = new Intent(
                context.getApplicationContext(),
                E2ePackageUpdateService.class).setAction(ACTION_CHECK);
        context.startForegroundService(intent);
    }

    static void requestCancel() {
        CANCEL_REQUESTED.set(true);
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Rusty updater E2E preparation",
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (intent == null || !ACTION_CHECK.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }
        CANCEL_REQUESTED.set(false);
        worker.execute(() -> runCheck(startId));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void runCheck(int startId) {
        E2eUpdateOperationStore store =
                new E2eUpdateOperationStore(this);
        String operationId = "";
        try {
            var operation = store.beginOrReuse();
            operationId = operation.getString("operation_id");
            String capturedOperationId = operationId;
            PackageInstallController controller =
                    new PackageInstallController(
                            this, new InstallReceiptStore(this));
            PackageUpdatePipeline.Result result =
                    new PackageUpdatePipeline(this, controller)
                            .checkAndStage(
                                    () -> CANCEL_REQUESTED.get()
                                            || Thread.currentThread()
                                                    .isInterrupted()
                                            || store.isCancelRequested(
                                                    capturedOperationId),
                                    (state, artifact, received, expected) -> {
                                        try {
                                            store.updateProgress(
                                                    capturedOperationId,
                                                    state,
                                                    artifact,
                                                    received,
                                                    expected);
                                        } catch (Exception exception) {
                                            CANCEL_REQUESTED.set(true);
                                        }
                                    });
            store.awaitingWearer(
                    operationId, result.artifact, result.sessionId);
            if (store.isCancelRequested(operationId)) {
                try {
                    controller.cancelPersistedSession();
                } catch (Exception ignored) {
                    // Callback/readback reconciliation owns the committed outcome.
                }
                try {
                    store.projectInstallReceipt();
                } catch (Exception ignored) {
                    // Keep the durable cancellation request nonterminal.
                }
            }
        } catch (PackageUpdatePipeline.UpdateCancelledException exception) {
            markTerminal(store, operationId, "cancelled", "update_cancelled");
        } catch (Exception exception) {
            Log.e(LOG_TAG, "update pipeline failed closed", exception);
            String message = exception.getMessage();
            markTerminal(
                    store,
                    operationId,
                    "failed",
                    E2eUpdateOperationStore.stableErrorCode(message));
        } finally {
            RUNNING.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void markTerminal(
            E2eUpdateOperationStore store,
            String operationId,
            String state,
            String errorCode) {
        if (operationId.isBlank()) {
            return;
        }
        try {
            if (store.isCancelRequested(operationId)) {
                state = "cancelled";
                errorCode = "update_cancelled";
            }
            store.terminal(operationId, state, errorCode);
        } catch (Exception ignored) {
            // A corrupt private operation store is itself a fail-closed result.
        }
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, PackageUpdaterActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Preparing attended Rusty update")
                .setContentText(
                        "Signed content is being verified; wearer approval remains required.")
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }
}
