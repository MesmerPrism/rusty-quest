package io.github.mesmerprism.rustyquest.packageupdater;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

final class PackageInstallController {
    private final Context context;
    private final InstallReceiptStore receiptStore;

    PackageInstallController(Context context, InstallReceiptStore receiptStore) {
        this.context = context.getApplicationContext();
        this.receiptStore = receiptStore;
    }

    int stageAttendedInstall(
            VerifiedUpdatePlan plan,
            File apkFile,
            PackageUpdatePipeline.Cancellation cancellation) throws Exception {
        PackageUpdatePipeline.requireNotCancelled(cancellation);
        UpdateArtifact artifact = plan.artifact;
        JSONObject previous = receiptStore.read();
        if (previous != null
                && !InstallReceiptStore.isTerminal(previous.optString("state"))) {
            throw new IllegalStateException("install_session_already_active");
        }

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(artifact.packageName);
        params.setRequireUserAction(
                PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);

        int sessionId = installer.createSession(params);
        boolean committed = false;
        Exception primaryFailure = null;
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            PackageUpdatePipeline.requireNotCancelled(cancellation);
            try (FileInputStream input = new FileInputStream(apkFile);
                    OutputStream output =
                            session.openWrite(
                                    "base.apk", 0L, artifact.apkSizeBytes)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    PackageUpdatePipeline.requireNotCancelled(cancellation);
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }

            PackageUpdatePipeline.requireNotCancelled(cancellation);
            if (System.currentTimeMillis() >= plan.expiresAtMs) {
                throw new IllegalStateException(
                        "manifest_expired_before_install_commit");
            }
            String callbackToken = newCallbackToken();
            receiptStore.begin(sessionId, callbackToken, plan, apkFile);
            PackageUpdatePipeline.requireNotCancelled(cancellation);
            Intent callback = new Intent(context, PackageInstallCallbackReceiver.class)
                    .setAction(InstallReceiptStore.CALLBACK_ACTION)
                    .setPackage(context.getPackageName())
                    .setData(
                            new Uri.Builder()
                                    .scheme(InstallReceiptStore.CALLBACK_SCHEME)
                                    .authority(InstallReceiptStore.CALLBACK_AUTHORITY)
                                    .appendPath(Integer.toString(sessionId))
                                    .appendPath(callbackToken)
                                    .build());
            PendingIntent pendingCallback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            receiptStore.updateState(
                    sessionId, "commit_requested", null, null);
            PackageUpdatePipeline.requireNotCancelled(cancellation);
            session.commit(pendingCallback.getIntentSender());
            committed = true;
            receiptStore.compareAndSetState(
                    sessionId,
                    "commit_requested",
                    "awaiting_installer_callback",
                    null,
                    null);
            return sessionId;
        } catch (Exception exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            if (!committed) {
                Exception cleanupFailure = null;
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception exception) {
                    cleanupFailure = exception;
                }
                try {
                    JSONObject failedReceipt = receiptStore.read();
                    if (failedReceipt != null
                            && failedReceipt.optInt("session_id", -1)
                                    == sessionId
                            && !InstallReceiptStore.isTerminal(
                                    failedReceipt.optString("state"))) {
                        boolean cancelled =
                                primaryFailure
                                        instanceof PackageUpdatePipeline
                                                .UpdateCancelledException;
                        receiptStore.updateState(
                                sessionId,
                                cancelled
                                        ? "install_cancelled_before_commit"
                                        : "install_staging_failed",
                                cancelled
                                        ? PackageInstaller.STATUS_FAILURE_ABORTED
                                        : PackageInstaller.STATUS_FAILURE,
                                cancelled
                                        ? "Update cancelled before Package Installer commit"
                                        : "Package Installer session staging failed");
                        cleanupTerminalArtifacts(
                                context, receiptStore.read());
                    } else if (failedReceipt == null) {
                        cleanupStagedApk(context, apkFile);
                    }
                } catch (Exception exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = exception;
                    } else {
                        cleanupFailure.addSuppressed(exception);
                    }
                }
                if (cleanupFailure != null) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    void reconcilePersistedSession() throws Exception {
        JSONObject receipt = receiptStore.read();
        if (receipt == null || InstallReceiptStore.isTerminal(receipt.optString("state"))) {
            return;
        }
        int sessionId = receipt.getInt("session_id");
        if (InstallReceiptStore.isInstalledCheckpointPending(
                receipt.optString("state"))) {
            try {
                verifyInstalledReadback(context, receipt);
            } catch (Exception exception) {
                receiptStore.updateState(
                        sessionId,
                        "readback_failed_during_checkpoint_retry",
                        PackageInstaller.STATUS_FAILURE,
                        exception.getMessage());
                cleanupTerminalArtifacts(context, receipt);
                return;
            }
            try {
                commitInstalledCheckpoint(context, receipt);
            } catch (Exception exception) {
                receiptStore.updateState(
                        sessionId,
                        "installed_readback_checkpoint_pending",
                        null,
                        exception.getMessage());
                return;
            }
            receiptStore.updateState(
                    sessionId,
                    "installed_readback_ok_checkpoint_reconciled",
                    PackageInstaller.STATUS_SUCCESS,
                    null);
            cleanupTerminalArtifacts(context, receipt);
            return;
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionInfo sessionInfo = installer.getSessionInfo(sessionId);
        if (sessionInfo != null) {
            if (System.currentTimeMillis() >= receipt.getLong("manifest_expires_at_ms")) {
                if (sessionInfo.isCommitted()) {
                    receiptStore.updateState(
                            sessionId,
                            "cancel_requested_manifest_expired_awaiting_installer_callback",
                            null,
                            "Manifest expired while committed installer approval was pending");
                    try {
                        installer.abandonSession(sessionId);
                    } catch (Exception ignored) {
                        // Callback or exact installed readback resolves the race.
                    }
                } else {
                    installer.abandonSession(sessionId);
                    receiptStore.updateState(
                            sessionId,
                            "install_cancelled_manifest_expired",
                            PackageInstaller.STATUS_FAILURE_ABORTED,
                            "Manifest expired before Package Installer commit");
                    cleanupTerminalArtifacts(context, receipt);
                }
            } else if (!sessionInfo.isCommitted()) {
                installer.abandonSession(sessionId);
                receiptStore.updateState(
                        sessionId,
                        "install_staging_failed_interrupted",
                        PackageInstaller.STATUS_FAILURE_ABORTED,
                        "Interrupted Package Installer staging was abandoned");
                cleanupTerminalArtifacts(context, receipt);
            } else {
                receiptStore.updateState(
                        sessionId,
                        "session_present_awaiting_wearer",
                        null,
                        null);
            }
            return;
        }
        boolean cancellationPending = InstallReceiptStore.isCancellationPending(
                receipt.optString("state"));
        try {
            verifyInstalledReadback(context, receipt);
        } catch (Exception exception) {
            receiptStore.updateState(
                    sessionId,
                    cancellationPending
                            ? "install_cancelled_by_wearer_reconciled"
                            : "session_missing_readback_failed",
                    cancellationPending
                            ? PackageInstaller.STATUS_FAILURE_ABORTED
                            : PackageInstaller.STATUS_FAILURE,
                    cancellationPending
                            ? "Cancelled Package Installer session was not installed"
                            : exception.getMessage());
            cleanupTerminalArtifacts(context, receipt);
            return;
        }
        try {
            commitInstalledCheckpoint(context, receipt);
        } catch (Exception exception) {
            receiptStore.updateState(
                    sessionId,
                    "installed_readback_checkpoint_pending",
                    null,
                    exception.getMessage());
            return;
        }
        receiptStore.updateState(
                sessionId,
                "installed_readback_ok_reconciled",
                PackageInstaller.STATUS_SUCCESS,
                null);
        cleanupTerminalArtifacts(context, receipt);
    }

    void cancelPersistedSession() throws Exception {
        JSONObject receipt = receiptStore.read();
        if (receipt == null || InstallReceiptStore.isTerminal(receipt.optString("state"))) {
            throw new IllegalStateException("no_active_install_session");
        }
        if (InstallReceiptStore.isInstalledCheckpointPending(
                receipt.optString("state"))) {
            reconcilePersistedSession();
            return;
        }
        int sessionId = receipt.getInt("session_id");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionInfo sessionInfo =
                installer.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            if (InstallReceiptStore.isCancellationPending(
                            receipt.optString("state"))
                    && System.currentTimeMillis()
                            - receipt.optLong("updated_at_ms", 0L)
                            < 5_000L) {
                return;
            }
            reconcilePersistedSession();
            return;
        }
        if (sessionInfo.isCommitted()) {
            receiptStore.updateState(
                    sessionId,
                    "cancel_requested_awaiting_installer_callback",
                    null,
                    "Cancellation requested for committed installer session");
            try {
                installer.abandonSession(sessionId);
            } catch (Exception exception) {
                JSONObject latest = receiptStore.read();
                if (latest == null
                        || !InstallReceiptStore.isTerminal(
                                latest.optString("state"))) {
                    reconcilePersistedSession();
                }
            }
            return;
        }
        installer.abandonSession(sessionId);
        receiptStore.updateState(
                sessionId,
                "install_cancelled_by_wearer",
                PackageInstaller.STATUS_FAILURE_ABORTED,
                "Wearer cancelled the attended install");
        cleanupTerminalArtifacts(context, receipt);
    }

    static void cleanupTerminalArtifacts(Context context, JSONObject receipt) throws Exception {
        cleanupStagedApk(
                context, new File(receipt.getString("staged_apk_path")));
    }

    static void verifyInstalledReadback(Context context, JSONObject receipt)
            throws Exception {
        UpdateArtifact artifact = InstallReceiptStore.artifact(receipt);
        ApkStager.verifyStaged(
                context,
                new File(receipt.getString("staged_apk_path")),
                artifact);
        PackageInspection.verifyInstalled(
                context,
                artifact.packageName,
                artifact.versionCode,
                artifact.signerSha256);
    }

    static void commitInstalledCheckpoint(Context context, JSONObject receipt)
            throws Exception {
        new UpdateStateStore(context).commitInstalled(
                receipt.getString("package_name"),
                receipt.getString("rollout_ring"),
                receipt.getLong("manifest_sequence"),
                receipt.getLong("version_code"),
                receipt.getString("signed_manifest_sha256"));
    }

    private static void cleanupStagedApk(Context context, File candidate)
            throws Exception {
        File stagingRoot = new File(
                context.getNoBackupFilesDir(), "package-updater/staged").getCanonicalFile();
        File stagedApk = candidate.getCanonicalFile();
        String rootPrefix = stagingRoot.getPath() + File.separator;
        if (!stagedApk.getPath().startsWith(rootPrefix)) {
            throw new IllegalStateException("staged_apk_path_outside_private_root");
        }
        if (stagedApk.exists() && !stagedApk.delete()) {
            throw new IllegalStateException("terminal_staged_apk_not_removed");
        }
    }

    private static String newCallbackToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(
                bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }
}
