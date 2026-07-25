package io.github.mesmerprism.rustyquest.packageupdater;

import java.net.URI;

final class UpdateArtifact {
    final String packageName;
    final long versionCode;
    final String versionName;
    final URI apkUri;
    final long apkSizeBytes;
    final String apkSha256;
    final String signerSha256;

    UpdateArtifact(
            String packageName,
            long versionCode,
            String versionName,
            URI apkUri,
            long apkSizeBytes,
            String apkSha256,
            String signerSha256) {
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUri = apkUri;
        this.apkSizeBytes = apkSizeBytes;
        this.apkSha256 = apkSha256;
        this.signerSha256 = signerSha256;
    }
}
