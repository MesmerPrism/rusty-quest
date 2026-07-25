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

    int stageAttendedInstall(VerifiedUpdatePlan plan, File apkFile) throws Exception {
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
        try (PackageInstaller.Session session = installer.openSession(sessionId);
                FileInputStream input = new FileInputStream(apkFile);
                OutputStream output =
                        session.openWrite("base.apk", 0L, artifact.apkSizeBytes)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            session.fsync(output);

            String callbackToken = newCallbackToken();
            receiptStore.begin(sessionId, callbackToken, plan, apkFile);
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
            session.commit(pendingCallback.getIntentSender());
            committed = true;
            receiptStore.updateState(
                    sessionId, "awaiting_installer_callback", null, null);
            return sessionId;
        } finally {
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                    // Preserve the original staging failure.
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
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionInfo sessionInfo = installer.getSessionInfo(sessionId);
        if (sessionInfo != null) {
            if (System.currentTimeMillis() >= receipt.getLong("manifest_expires_at_ms")) {
                installer.abandonSession(sessionId);
                receiptStore.updateState(
                        sessionId,
                        "install_cancelled_manifest_expired",
                        PackageInstaller.STATUS_FAILURE_ABORTED,
                        "Manifest expired while Package Installer approval was pending");
                cleanupTerminalArtifacts(context, receipt);
            } else {
                receiptStore.updateState(
                        sessionId,
                        sessionInfo.isCommitted()
                                ? "session_present_awaiting_wearer"
                                : "session_present_not_committed",
                        null,
                        null);
            }
            return;
        }
        try {
            if (System.currentTimeMillis()
                    >= receipt.getLong("manifest_expires_at_ms")) {
                throw new IllegalStateException("manifest_expired_before_install_commit");
            }
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
            new UpdateStateStore(context).commitInstalled(
                    receipt.getString("package_name"),
                    receipt.getString("rollout_ring"),
                    receipt.getLong("manifest_sequence"),
                    receipt.getLong("version_code"),
                    receipt.getString("signed_manifest_sha256"));
            receiptStore.updateState(
                    sessionId,
                    "installed_readback_ok_reconciled",
                    PackageInstaller.STATUS_SUCCESS,
                    null);
            cleanupTerminalArtifacts(context, receipt);
        } catch (Exception exception) {
            receiptStore.updateState(
                    sessionId,
                    "session_missing_readback_failed",
                    PackageInstaller.STATUS_FAILURE,
                    exception.getMessage());
            cleanupTerminalArtifacts(context, receipt);
        }
    }

    void cancelPersistedSession() throws Exception {
        JSONObject receipt = receiptStore.read();
        if (receipt == null || InstallReceiptStore.isTerminal(receipt.optString("state"))) {
            throw new IllegalStateException("no_active_install_session");
        }
        int sessionId = receipt.getInt("session_id");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        if (installer.getSessionInfo(sessionId) != null) {
            installer.abandonSession(sessionId);
        }
        receiptStore.updateState(
                sessionId,
                "install_cancelled_by_wearer",
                PackageInstaller.STATUS_FAILURE_ABORTED,
                "Wearer cancelled the attended install");
        cleanupTerminalArtifacts(context, receipt);
    }

    static void cleanupTerminalArtifacts(Context context, JSONObject receipt) throws Exception {
        File stagingRoot = new File(
                context.getNoBackupFilesDir(), "package-updater/staged").getCanonicalFile();
        File stagedApk = new File(receipt.getString("staged_apk_path")).getCanonicalFile();
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
