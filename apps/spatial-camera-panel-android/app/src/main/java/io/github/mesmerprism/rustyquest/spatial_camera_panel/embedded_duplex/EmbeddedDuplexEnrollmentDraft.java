package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Reviewable enrollment input; it has no authority until the process host commits it. */
public final class EmbeddedDuplexEnrollmentDraft {
    public final String roleId;
    public final String remotePublicKeyHex;
    public final String runtimeHostId;
    public final String trustedOperatorId;
    public final String adapterId;
    public final String mediaRevokerId;
    public final String admissionAuthorityId;
    public final long maxTokenTtlMs;
    public final boolean localFixture;

    public EmbeddedDuplexEnrollmentDraft(String roleId, String remotePublicKeyHex,
            String runtimeHostId, String trustedOperatorId, String adapterId,
            String mediaRevokerId, String admissionAuthorityId, long maxTokenTtlMs,
            boolean localFixture) {
        if (!("peer_a".equals(roleId) || "peer_b".equals(roleId))
                || remotePublicKeyHex == null || !remotePublicKeyHex.matches("[0-9a-f]{64}")
                || !dotted(runtimeHostId) || !runtimeHostId.startsWith("host.")
                || !dotted(trustedOperatorId) || !dotted(adapterId)
                || !dotted(mediaRevokerId) || !dotted(admissionAuthorityId)
                || maxTokenTtlMs <= 0L || maxTokenTtlMs > 24L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("enrollment draft bounds");
        }
        this.roleId = roleId;
        this.remotePublicKeyHex = remotePublicKeyHex;
        this.runtimeHostId = runtimeHostId;
        this.trustedOperatorId = trustedOperatorId;
        this.adapterId = adapterId;
        this.mediaRevokerId = mediaRevokerId;
        this.admissionAuthorityId = admissionAuthorityId;
        this.maxTokenTtlMs = maxTokenTtlMs;
        this.localFixture = localFixture;
    }

    private static boolean dotted(String value) {
        return value != null && value.matches("[a-z][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)+");
    }
}
