package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** One process-local, immutable review. The host alone decides whether it can be consumed. */
public final class EmbeddedDuplexEnrollmentReview {
    private static final long LIFETIME_MS = 5L * 60L * 1000L;
    final EmbeddedDuplexEnrollmentRequest installed;
    final EmbeddedDuplexEnrollmentDraft draft;
    final EmbeddedDuplexEnrollmentFence fence;
    final long expiresAtElapsedMs;
    public final String remoteKeyId;
    public final String reviewSha256;
    public final String details;

    EmbeddedDuplexEnrollmentReview(EmbeddedDuplexEnrollmentRequest installed,
            EmbeddedDuplexEnrollmentDraft draft, EmbeddedDuplexEnrollmentFence fence)
            throws Exception {
        if (installed == null || draft == null || fence == null
                || !installed.roleId.equals(draft.roleId)) {
            throw new IllegalArgumentException("enrollment review inputs");
        }
        this.installed = installed;
        this.draft = draft;
        this.fence = fence;
        this.remoteKeyId = fence.remoteKeyId;
        this.details = "Role: " + draft.roleId + "\nPackage: " + installed.packageId
                + "\nSigner SHA-256: " + installed.signerSha256
                + "\nManifest SHA-256: " + installed.manifestSha256
                + "\nRoute SHA-256: " + installed.routeSha256
                + "\nLocal key ID: " + installed.localKeyId
                + "\nLocal public key: " + installed.localPublicKeyHex
                + "\nLocal placement: " + installed.localDeviceId + " / " + installed.localPeerId
                + "\nRemote placement: " + installed.remoteDeviceId + " / " + installed.remotePeerId
                + "\nRemote public key: " + draft.remotePublicKeyHex
                + "\nRuntime host: " + draft.runtimeHostId
                + "\nTrusted operator: " + draft.trustedOperatorId
                + "\nAdapter: " + draft.adapterId
                + "\nMedia revoker: " + draft.mediaRevokerId
                + "\nAdmission authority: " + draft.admissionAuthorityId
                + "\nMaximum token TTL ms: " + draft.maxTokenTtlMs
                + "\nLocal fixture: " + draft.localFixture
                + "\nPrior revision: " + fence.revision
                + "\nPrior record SHA-256: " + (fence.recordSha256 == null ? "absent" : fence.recordSha256);
        this.expiresAtElapsedMs = SystemClock.elapsedRealtime() + LIFETIME_MS;
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (String value : new String[] { "rusty.quest.enrollment.review.v1", installed.roleId,
                installed.packageId, installed.signerSha256, installed.manifestSha256,
                installed.routeSha256, installed.localKeyId, installed.localPublicKeyHex,
                installed.localDeviceId, installed.localPeerId, installed.remoteDeviceId,
                installed.remotePeerId, draft.remotePublicKeyHex, draft.runtimeHostId,
                draft.trustedOperatorId, draft.adapterId, draft.mediaRevokerId,
                draft.admissionAuthorityId, fence.recordSha256 == null ? "" : fence.recordSha256,
                fence.remoteKeyId }) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(encoded.length);
            out.write(encoded);
        }
        out.writeLong(draft.maxTokenTtlMs);
        out.writeBoolean(draft.localFixture);
        out.writeLong(fence.revision);
        out.writeLong(expiresAtElapsedMs);
        out.write(nonce);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        StringBuilder hex = new StringBuilder(64);
        for (byte item : hash) {
            hex.append(Character.forDigit((item >>> 4) & 15, 16));
            hex.append(Character.forDigit(item & 15, 16));
        }
        reviewSha256 = hex.toString();
    }

    boolean expired() { return SystemClock.elapsedRealtime() >= expiresAtElapsedMs; }
}
