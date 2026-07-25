package io.github.mesmerprism.rustyquest.packageupdater;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

final class ApkStager {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    File downloadAndVerify(Context context, UpdateArtifact artifact) throws Exception {
        File stagingDirectory =
                new File(context.getNoBackupFilesDir(), "package-updater/staged");
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            throw new IllegalStateException("could_not_create_private_staging_directory");
        }
        String apkDigestHex = artifact.apkSha256.substring("sha256:".length());
        File finalFile = new File(stagingDirectory, apkDigestHex + ".apk");
        if (finalFile.isFile()) {
            requireFileIdentity(finalFile, artifact);
            PackageInspection.verifyArchive(context, finalFile, artifact);
            return finalFile;
        }

        File partFile = new File(stagingDirectory, apkDigestHex + ".part");
        if (partFile.exists() && !partFile.delete()) {
            throw new IllegalStateException("stale_private_stage_not_removed");
        }

        URLConnection rawConnection = artifact.apkUri.toURL().openConnection();
        if (!(rawConnection instanceof HttpsURLConnection)) {
            throw new IllegalStateException("apk_connection_not_https");
        }
        HttpsURLConnection connection = (HttpsURLConnection) rawConnection;
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty(
                "Accept", "application/vnd.android.package-archive");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setUseCaches(false);
        try {
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK) {
                throw new IllegalStateException("apk_http_status_" + status);
            }
            String contentEncoding = connection.getContentEncoding();
            if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding)) {
                throw new IllegalStateException("apk_content_encoding_not_identity");
            }
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength != -1L && declaredLength != artifact.apkSizeBytes) {
                throw new IllegalStateException("apk_content_length_mismatch");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            try (InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(partFile, false)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > artifact.apkSizeBytes) {
                        throw new IllegalStateException("apk_download_exceeded_signed_size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
            }
            if (total != artifact.apkSizeBytes) {
                throw new IllegalStateException("apk_download_size_mismatch");
            }
            if (!artifact.apkSha256.equals("sha256:" + toHex(digest.digest()))) {
                throw new IllegalStateException("apk_sha256_mismatch");
            }
            if (!partFile.renameTo(finalFile)) {
                throw new IllegalStateException("private_stage_atomic_rename_failed");
            }
            PackageInspection.verifyArchive(context, finalFile, artifact);
            return finalFile;
        } catch (Exception exception) {
            if (partFile.exists() && !partFile.delete()) {
                IllegalStateException cleanupFailure =
                        new IllegalStateException("failed_private_stage_not_removed");
                cleanupFailure.addSuppressed(exception);
                throw cleanupFailure;
            }
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    static void verifyStaged(
            Context context, File file, UpdateArtifact artifact) throws Exception {
        requireFileIdentity(file, artifact);
        PackageInspection.verifyArchive(context, file, artifact);
    }

    private static void requireFileIdentity(File file, UpdateArtifact artifact)
            throws Exception {
        if (file.length() != artifact.apkSizeBytes) {
            throw new IllegalStateException("cached_apk_size_mismatch");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        if (!artifact.apkSha256.equals("sha256:" + toHex(digest.digest()))) {
            throw new IllegalStateException("cached_apk_sha256_mismatch");
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }
}
