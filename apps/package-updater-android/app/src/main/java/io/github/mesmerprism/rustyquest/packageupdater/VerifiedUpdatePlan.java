package io.github.mesmerprism.rustyquest.packageupdater;

final class VerifiedUpdatePlan {
    final String manifestId;
    final long sequence;
    final long issuedAtMs;
    final long expiresAtMs;
    final String rolloutRing;
    final String envelopeSha256;
    final String signedManifestSha256;
    final UpdateArtifact artifact;

    VerifiedUpdatePlan(
            String manifestId,
            long sequence,
            long issuedAtMs,
            long expiresAtMs,
            String rolloutRing,
            String envelopeSha256,
            String signedManifestSha256,
            UpdateArtifact artifact) {
        this.manifestId = manifestId;
        this.sequence = sequence;
        this.issuedAtMs = issuedAtMs;
        this.expiresAtMs = expiresAtMs;
        this.rolloutRing = rolloutRing;
        this.envelopeSha256 = envelopeSha256;
        this.signedManifestSha256 = signedManifestSha256;
        this.artifact = artifact;
    }
}
