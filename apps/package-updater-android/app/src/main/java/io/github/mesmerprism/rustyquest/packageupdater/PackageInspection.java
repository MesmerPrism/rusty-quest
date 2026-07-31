package io.github.mesmerprism.rustyquest.packageupdater;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.io.File;
import java.security.MessageDigest;
import java.util.Locale;

final class PackageInspection {
    private PackageInspection() {
    }

    static long installedVersionOrMissing(Context context, String packageName) throws Exception {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_SIGNING_CERTIFICATES));
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException exception) {
            return -1L;
        }
    }

    static void verifyArchive(
            Context context, File apkFile, UpdateArtifact artifact) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo archive = packageManager.getPackageArchiveInfo(
                apkFile.getAbsolutePath(),
                PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES));
        if (archive == null) {
            throw new IllegalStateException("apk_archive_unreadable");
        }
        if (!artifact.packageName.equals(archive.packageName)) {
            throw new IllegalStateException("apk_package_mismatch");
        }
        if (archive.getLongVersionCode() != artifact.versionCode) {
            throw new IllegalStateException("apk_version_mismatch");
        }
        requireSoleSigner(archive.signingInfo, artifact.signerSha256, "apk");

        try {
            PackageInfo installed = packageManager.getPackageInfo(
                    artifact.packageName,
                    PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_SIGNING_CERTIFICATES));
            requireSoleSigner(installed.signingInfo, artifact.signerSha256, "installed");
        } catch (PackageManager.NameNotFoundException ignored) {
            // A signed, allowlisted artifact may install a missing sidecar.
        }
    }

    static void verifyInstalled(
            Context context,
            String packageName,
            long versionCode,
            String signerSha256) throws Exception {
        PackageInfo installed = context.getPackageManager().getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES));
        if (installed.getLongVersionCode() != versionCode) {
            throw new IllegalStateException("installed_version_readback_mismatch");
        }
        requireSoleSigner(installed.signingInfo, signerSha256, "installed_readback");
    }

    static void verifyInstalledSigner(
            Context context, String packageName, String signerSha256) throws Exception {
        PackageInfo installed = context.getPackageManager().getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES));
        requireSoleSigner(installed.signingInfo, signerSha256, "installed");
    }

    private static void requireSoleSigner(
            SigningInfo signingInfo, String expectedSha256, String label) throws Exception {
        if (signingInfo == null) {
            throw new IllegalStateException(label + "_signing_info_missing");
        }
        Signature[] signers = signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) {
            throw new IllegalStateException(label + "_sole_signer_required");
        }
        String actual = sha256Hex(signers[0].toByteArray());
        if (!expectedSha256.equals("sha256:" + actual)) {
            throw new IllegalStateException(label + "_signer_mismatch");
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(64);
        for (byte value : digest) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }
}
