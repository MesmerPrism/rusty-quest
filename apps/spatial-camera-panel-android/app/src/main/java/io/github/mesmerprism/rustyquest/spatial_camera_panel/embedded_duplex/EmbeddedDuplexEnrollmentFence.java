package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Authenticated prior-record identity returned by the private enrollment owner. */
public final class EmbeddedDuplexEnrollmentFence {
    public final long revision;
    public final String recordSha256;
    public final String remoteKeyId;

    public EmbeddedDuplexEnrollmentFence(long revision, String recordSha256, String remoteKeyId) {
        if (revision < 0L || (revision == 0L) != (recordSha256 == null)
                || (recordSha256 != null && !recordSha256.matches("[0-9a-f]{64}"))
                || remoteKeyId == null || !remoteKeyId.matches("ed25519\\.[0-9a-f]{64}")) {
            throw new IllegalArgumentException("enrollment review fence");
        }
        this.revision = revision;
        this.recordSha256 = recordSha256;
        this.remoteKeyId = remoteKeyId;
    }
}
