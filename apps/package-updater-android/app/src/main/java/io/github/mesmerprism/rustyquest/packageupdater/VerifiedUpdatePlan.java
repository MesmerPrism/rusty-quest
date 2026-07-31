package io.github.mesmerprism.rustyquest.packageupdater;

import org.json.JSONObject;

final class VerifiedUpdatePlan {
    final String manifestId;
    final String channel;
    final long sequence;
    final long issuedAtMs;
    final long expiresAtMs;
    final String rolloutRing;
    final String signerSha256;
    final String keyId;
    final String publicKey;
    final String httpsOrigin;
    final String envelopeSha256;
    final String signedManifestSha256;
    final UpdateArtifact artifact;

    VerifiedUpdatePlan(
            String manifestId,
            String channel,
            long sequence,
            long issuedAtMs,
            long expiresAtMs,
            String rolloutRing,
            String signerSha256,
            String keyId,
            String publicKey,
            String httpsOrigin,
            String envelopeSha256,
            String signedManifestSha256,
            UpdateArtifact artifact) {
        this.manifestId = manifestId;
        this.channel = channel;
        this.sequence = sequence;
        this.issuedAtMs = issuedAtMs;
        this.expiresAtMs = expiresAtMs;
        this.rolloutRing = rolloutRing;
        this.signerSha256 = signerSha256;
        this.keyId = keyId;
        this.publicKey = publicKey;
        this.httpsOrigin = httpsOrigin;
        this.envelopeSha256 = envelopeSha256;
        this.signedManifestSha256 = signedManifestSha256;
        this.artifact = artifact;
    }

    static VerifiedUpdatePlan fromInstallReceipt(JSONObject receipt) throws Exception {
        UpdateArtifact artifact = InstallReceiptStore.artifact(receipt);
        if (!BuildConfig.UPDATE_CHANNEL.equals(receipt.getString("channel"))
                || !BuildConfig.EXPECTED_PACKAGE_NAME.equals(artifact.packageName)
                || !BuildConfig.EXPECTED_ROLLOUT_RING.equals(
                        receipt.getString("rollout_ring"))
                || !BuildConfig.EXPECTED_SIGNER_SHA256.equals(artifact.signerSha256)
                || !BuildConfig.TRUSTED_KEY_ID.equals(receipt.getString("key_id"))
                || !BuildConfig.TRUSTED_PUBLIC_KEY_BASE64.equals(
                        receipt.getString("public_key"))
                || !BuildConfig.EXPECTED_HTTPS_ORIGIN.equals(
                        receipt.getString("https_origin"))) {
            throw new IllegalStateException("install_receipt_tuple_mismatch");
        }
        return new VerifiedUpdatePlan(
                receipt.getString("manifest_id"),
                receipt.getString("channel"),
                receipt.getLong("manifest_sequence"),
                0L,
                receipt.getLong("manifest_expires_at_ms"),
                receipt.getString("rollout_ring"),
                receipt.getString("signer_sha256"),
                receipt.getString("key_id"),
                receipt.getString("public_key"),
                receipt.getString("https_origin"),
                "",
                receipt.getString("signed_manifest_sha256"),
                artifact);
    }
}
