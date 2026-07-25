package io.github.mesmerprism.rustyquest.packageupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONObject;

public final class PackageInstallCallbackReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        InstallReceiptStore receiptStore = new InstallReceiptStore(context);
        try {
            if (!receiptStore.matchesCallback(intent)) {
                return;
            }
            JSONObject receipt = receiptStore.read();
            if (receipt == null) {
                return;
            }
            int sessionId = receipt.getInt("session_id");
            int status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
            String statusMessage = intent.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                if (InstallReceiptStore.isCancellationPending(
                                receipt.optString("state"))
                        || InstallReceiptStore.isInstalledCheckpointPending(
                                receipt.optString("state"))) {
                    return;
                }
                Intent confirmation =
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirmation == null) {
                    receiptStore.updateState(
                            sessionId,
                            "install_failed_missing_confirmation_intent",
                            PackageInstaller.STATUS_FAILURE_INVALID,
                            "Package Installer did not provide confirmation UI");
                    PackageInstallController.cleanupTerminalArtifacts(context, receipt);
                    return;
                }
                receiptStore.updateState(
                        sessionId,
                        "pending_user_confirmation",
                        status,
                        statusMessage);
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                try {
                    PackageInstallController.verifyInstalledReadback(
                            context, receipt);
                } catch (Exception exception) {
                    receiptStore.updateState(
                            sessionId,
                            "readback_failed_after_installer_success",
                            PackageInstaller.STATUS_FAILURE_INVALID,
                            exception.getMessage());
                    PackageInstallController.cleanupTerminalArtifacts(context, receipt);
                    return;
                }
                try {
                    PackageInstallController.commitInstalledCheckpoint(
                            context, receipt);
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
                        "installed_readback_ok",
                        status,
                        statusMessage);
                PackageInstallController.cleanupTerminalArtifacts(context, receipt);
                return;
            }
            String terminalState = status == PackageInstaller.STATUS_FAILURE_ABORTED
                    ? "install_cancelled_by_wearer"
                    : "install_failed_status_" + status;
            receiptStore.updateState(
                    sessionId,
                    terminalState,
                    status,
                    statusMessage);
            PackageInstallController.cleanupTerminalArtifacts(context, receipt);
        } catch (Exception ignored) {
            // A malformed or unauthenticated callback cannot widen installation authority.
        }
    }
}
