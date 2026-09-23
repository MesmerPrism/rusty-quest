package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable private-capsule result. Fresh session values are supplied separately. */
public final class EmbeddedDuplexEnrollment {
    public final EmbeddedDuplexEnrollmentRequest installed;
    public final String remoteKeyId;
    public final String remotePublicKeyHex;
    public final String runtimeHostId;
    public final String trustedOperatorId;
    public final String adapterId;
    public final String mediaRevokerId;
    public final String admissionAuthorityId;
    public final long maxTokenTtlMs;
    public final long revision;
    public final String recordSha256;
    public final boolean localFixture;

    public EmbeddedDuplexEnrollment(EmbeddedDuplexEnrollmentRequest installed,
            String remoteKeyId, String remotePublicKeyHex, String runtimeHostId,
            String trustedOperatorId, String adapterId, String mediaRevokerId,
            String admissionAuthorityId, long maxTokenTtlMs,
            long revision, String recordSha256, boolean localFixture) throws Exception {
        if (installed == null || remotePublicKeyHex == null
                || !remotePublicKeyHex.matches("[0-9a-f]{64}")
                || remoteKeyId == null || !remoteKeyId.equals("ed25519." + sha256(hexBytes(remotePublicKeyHex)))
                || remoteKeyId.equals(installed.localKeyId)
                || !dotted(runtimeHostId) || !runtimeHostId.startsWith("host.")
                || !dotted(trustedOperatorId) || !dotted(adapterId)
                || !dotted(mediaRevokerId) || !dotted(admissionAuthorityId)
                || maxTokenTtlMs <= 0L || maxTokenTtlMs > 24L * 60L * 60L * 1000L
                || revision <= 0L || recordSha256 == null || !recordSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("private enrollment binding");
        }
        this.installed = installed;
        this.remoteKeyId = remoteKeyId;
        this.remotePublicKeyHex = remotePublicKeyHex;
        this.runtimeHostId = runtimeHostId;
        this.trustedOperatorId = trustedOperatorId;
        this.adapterId = adapterId;
        this.mediaRevokerId = mediaRevokerId;
        this.admissionAuthorityId = admissionAuthorityId;
        this.maxTokenTtlMs = maxTokenTtlMs;
        this.revision = revision;
        this.recordSha256 = recordSha256;
        this.localFixture = localFixture;
    }

    JSONObject startupJson(long freshExecutorGeneration, String freshRouteGrantId) throws Exception {
        if (freshExecutorGeneration <= 0L || !dotted(freshRouteGrantId)) {
            throw new IllegalArgumentException("fresh session binding");
        }
        return new JSONObject()
                .put("remote_key_id", remoteKeyId)
                .put("remote_public_key_hex", remotePublicKeyHex)
                .put("route_grant_id", freshRouteGrantId)
                .put("executor_generation", freshExecutorGeneration)
                .put("device_peers", new JSONArray()
                        .put(new JSONObject().put("device_id", installed.localDeviceId)
                                .put("peer_id", installed.localPeerId))
                        .put(new JSONObject().put("device_id", installed.remoteDeviceId)
                                .put("peer_id", installed.remotePeerId)));
    }

    private static boolean dotted(String value) {
        return value != null && value.matches("[a-z][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)+");
    }

    private static byte[] hexBytes(String value) {
        byte[] decoded = new byte[value.length() / 2];
        for (int i = 0; i < decoded.length; i++) {
            decoded[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return decoded;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            hex.append(Character.forDigit((item >>> 4) & 15, 16));
            hex.append(Character.forDigit(item & 15, 16));
        }
        return hex.toString();
    }
}
