package io.github.mesmerprism.rustyquest.packageupdater;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PackageUpdaterActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView permissionStatus;
    private TextView operationStatus;
    private Button checkButton;
    private Button cancelButton;
    private InstallReceiptStore receiptStore;
    private PackageInstallController installController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        receiptStore = new InstallReceiptStore(this);
        installController = new PackageInstallController(this, receiptStore);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        worker.execute(() -> {
            try {
                installController.reconcilePersistedSession();
            } catch (Exception ignored) {
                // Receipt rendering below will keep an unresolved state visible.
            }
            runOnUiThread(this::refreshReceiptStatus);
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 32, 40, 40);
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setTextSize(24);
        title.setText("Rusty Package Updater");
        layout.addView(title);

        TextView boundary = new TextView(this);
        boundary.setPadding(0, 20, 0, 20);
        boundary.setText(
                "Attended sideloaded updater\n\n"
                        + "Checks one build-fixed signed HTTPS channel. "
                        + "Every package change requires Android Package Installer approval.");
        layout.addView(boundary);

        TextView configuration = new TextView(this);
        configuration.setText(
                "Manifest\n" + BuildConfig.UPDATE_MANIFEST_URL
                        + "\n\nTrusted key id\n" + BuildConfig.TRUSTED_KEY_ID
                        + "\n\nPackage\n" + BuildConfig.EXPECTED_PACKAGE_NAME
                        + "\n\nRollout ring\n" + BuildConfig.EXPECTED_ROLLOUT_RING
                        + "\n\nDownload origin\n" + BuildConfig.EXPECTED_HTTPS_ORIGIN);
        configuration.setTextIsSelectable(true);
        layout.addView(configuration);

        permissionStatus = new TextView(this);
        permissionStatus.setPadding(0, 24, 0, 12);
        layout.addView(permissionStatus);

        Button permissionButton = new Button(this);
        permissionButton.setText("Open install permission");
        permissionButton.setContentDescription(
                "Open Android unknown app source permission for Rusty Package Updater");
        permissionButton.setOnClickListener(view -> openInstallPermission());
        layout.addView(permissionButton);

        checkButton = new Button(this);
        checkButton.setText("Check signed channel");
        checkButton.setContentDescription(
                "Check the fixed signed update channel and stage one attended update");
        checkButton.setOnClickListener(view -> checkForUpdate());
        layout.addView(checkButton);

        cancelButton = new Button(this);
        cancelButton.setText("Cancel pending install");
        cancelButton.setContentDescription(
                "Abandon the current Package Installer session without changing the package");
        cancelButton.setOnClickListener(view -> cancelPendingInstall());
        layout.addView(cancelButton);

        operationStatus = new TextView(this);
        operationStatus.setPadding(0, 24, 0, 24);
        layout.addView(operationStatus);
        refreshPermissionStatus();
        refreshReceiptStatus();
        return scroll;
    }

    private void refreshPermissionStatus() {
        boolean allowed = getPackageManager().canRequestPackageInstalls();
        permissionStatus.setText(
                allowed
                        ? "Install permission: enabled"
                        : "Install permission: wearer approval required");
    }

    private void openInstallPermission() {
        Intent settings = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        startActivity(settings);
    }

    private void checkForUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            operationStatus.setText(
                    "Enable this app as an unknown-app source before checking.");
            return;
        }
        checkButton.setEnabled(false);
        operationStatus.setText("Fetching the fixed signed manifest…");
        worker.execute(() -> {
            try {
                URI manifestUri = UpdateManifestClient.requireFixedHttpsUri(
                        URI.create(BuildConfig.UPDATE_MANIFEST_URL));
                UpdateManifestClient client = new UpdateManifestClient();
                byte[] envelopeBytes = client.fetch(manifestUri);
                long installedVersion =
                        PackageInspection.installedVersionOrMissing(
                                this, BuildConfig.EXPECTED_PACKAGE_NAME);
                if (installedVersion >= 0L
                        && BuildConfig.EXPECTED_SIGNER_SHA256
                                .matches("sha256:[0-9a-f]{64}")) {
                    PackageInspection.verifyInstalledSigner(
                            this,
                            BuildConfig.EXPECTED_PACKAGE_NAME,
                            BuildConfig.EXPECTED_SIGNER_SHA256);
                }
                UpdateEnvelopeVerifier verifier = new StrictUpdateEnvelopeVerifier(
                        BuildConfig.TRUSTED_KEY_ID,
                        BuildConfig.TRUSTED_PUBLIC_KEY_BASE64,
                        BuildConfig.EXPECTED_HTTPS_ORIGIN,
                        BuildConfig.EXPECTED_PACKAGE_NAME,
                        BuildConfig.EXPECTED_ROLLOUT_RING,
                        BuildConfig.EXPECTED_SIGNER_SHA256,
                        Math.max(installedVersion, 0L),
                        BuildConfig.MINIMUM_TARGET_VERSION_CODE,
                        BuildConfig.MAXIMUM_TARGET_VERSION_CODE,
                        BuildConfig.MAXIMUM_APK_SIZE_BYTES,
                        BuildConfig.MAXIMUM_MANIFEST_VALIDITY_MS,
                        BuildConfig.MAXIMUM_FUTURE_ISSUE_SKEW_MS,
                        new UpdateStateStore(this));
                VerifiedUpdatePlan plan =
                        verifier.verify(envelopeBytes, System.currentTimeMillis());
                UpdateArtifact selected = plan.artifact;
                showProgress(
                        "Manifest verified. Downloading "
                                + selected.packageName
                                + " version "
                                + selected.versionCode
                                + " to private staging…");
                File apkFile = new ApkStager().downloadAndVerify(this, selected);
                int sessionId = installController.stageAttendedInstall(plan, apkFile);
                showResult(
                        "Verified APK staged in Package Installer session "
                                + sessionId
                                + ". Approve the Android confirmation UI.");
            } catch (Exception exception) {
                String message = exception.getMessage();
                if (message == null || message.isBlank()) {
                    message = exception.getClass().getSimpleName();
                }
                showResult("Update rejected or failed closed: " + message);
            }
        });
    }

    private void cancelPendingInstall() {
        cancelButton.setEnabled(false);
        worker.execute(() -> {
            try {
                installController.cancelPersistedSession();
                showResult("Pending install cancelled. No rollback state was advanced.");
            } catch (Exception exception) {
                String message = exception.getMessage();
                showResult(
                        "No pending install was cancelled: "
                                + (message == null ? exception.getClass().getSimpleName() : message));
            } finally {
                runOnUiThread(() -> cancelButton.setEnabled(true));
            }
        });
    }

    private void showProgress(String message) {
        runOnUiThread(() -> operationStatus.setText(message));
    }

    private void showResult(String message) {
        runOnUiThread(() -> {
            operationStatus.setText(message);
            checkButton.setEnabled(true);
        });
    }

    private void refreshReceiptStatus() {
        try {
            JSONObject receipt = receiptStore.read();
            if (receipt == null) {
                operationStatus.setText(
                        "No install receipt yet. The default empty trust key fails closed.");
                return;
            }
            operationStatus.setText(
                    "Last persisted session\n"
                            + "Package: " + receipt.optString("package_name", "unknown")
                            + "\nVersion: " + receipt.optLong("version_code", -1L)
                            + "\nSession: " + receipt.optInt("session_id", -1)
                            + "\nState: " + receipt.optString("state", "unknown")
                            + "\nManifest sequence: "
                            + receipt.optLong("manifest_sequence", -1L));
        } catch (Exception exception) {
            operationStatus.setText("Install receipt unreadable; updates fail closed.");
        }
    }
}
