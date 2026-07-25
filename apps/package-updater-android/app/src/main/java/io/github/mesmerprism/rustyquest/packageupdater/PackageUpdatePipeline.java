package io.github.mesmerprism.rustyquest.packageupdater;

import android.content.Context;

import java.io.File;
import java.net.URI;

/**
 * One shared, build-fixed update pipeline used by the visible UI and test-only
 * device automation. Callers can observe or cancel work, but cannot supply any
 * trust-policy input or approve the Android Package Installer confirmation.
 */
final class PackageUpdatePipeline {
    interface Cancellation {
        boolean isCancellationRequested();
    }

    interface Progress {
        void update(
                String state,
                UpdateArtifact artifact,
                long bytesReceived,
                long bytesExpected);
    }

    static final class Result {
        final UpdateArtifact artifact;
        final int sessionId;

        Result(UpdateArtifact artifact, int sessionId) {
            this.artifact = artifact;
            this.sessionId = sessionId;
        }
    }

    static final class UpdateCancelledException extends Exception {
        UpdateCancelledException() {
            super("update_cancelled");
        }
    }

    private final Context context;
    private final PackageInstallController installController;

    PackageUpdatePipeline(
            Context context, PackageInstallController installController) {
        this.context = context.getApplicationContext();
        this.installController = installController;
    }

    Result checkAndStage(Cancellation cancellation, Progress progress)
            throws Exception {
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            throw new IllegalStateException("install_permission_required");
        }
        requireNotCancelled(cancellation);
        progress.update("fetching_manifest", null, 0L, -1L);
        URI manifestUri = UpdateManifestClient.requireFixedHttpsUri(
                URI.create(BuildConfig.UPDATE_MANIFEST_URL));
        byte[] envelopeBytes = new UpdateManifestClient().fetch(manifestUri);

        requireNotCancelled(cancellation);
        progress.update("verifying_manifest", null, 0L, -1L);
        long installedVersion =
                PackageInspection.installedVersionOrMissing(
                        context, BuildConfig.EXPECTED_PACKAGE_NAME);
        if (installedVersion >= 0L
                && BuildConfig.EXPECTED_SIGNER_SHA256
                        .matches("sha256:[0-9a-f]{64}")) {
            PackageInspection.verifyInstalledSigner(
                    context,
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
                new UpdateStateStore(context));
        VerifiedUpdatePlan plan =
                verifier.verify(envelopeBytes, System.currentTimeMillis());
        UpdateArtifact artifact = plan.artifact;

        requireNotCancelled(cancellation);
        progress.update(
                "downloading", artifact, 0L, artifact.apkSizeBytes);
        File apkFile = new ApkStager().downloadAndVerify(
                context,
                artifact,
                cancellation,
                (bytesReceived, bytesExpected) -> progress.update(
                        "downloading",
                        artifact,
                        bytesReceived,
                        bytesExpected));

        requireNotCancelled(cancellation);
        progress.update(
                "staging_installer_session",
                artifact,
                artifact.apkSizeBytes,
                artifact.apkSizeBytes);
        int sessionId =
                installController.stageAttendedInstall(plan, apkFile, cancellation);
        progress.update(
                "awaiting_wearer_confirmation",
                artifact,
                artifact.apkSizeBytes,
                artifact.apkSizeBytes);
        return new Result(artifact, sessionId);
    }

    static void requireNotCancelled(Cancellation cancellation)
            throws UpdateCancelledException {
        if (cancellation.isCancellationRequested()) {
            throw new UpdateCancelledException();
        }
    }
}
