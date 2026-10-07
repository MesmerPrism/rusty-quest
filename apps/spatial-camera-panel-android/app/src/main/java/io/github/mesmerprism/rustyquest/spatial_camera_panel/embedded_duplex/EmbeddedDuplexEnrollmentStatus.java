package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Verified package facts and authenticated local-record state for one installed role. */
public final class EmbeddedDuplexEnrollmentStatus {
    public final EmbeddedDuplexEnrollmentRequest installed;
    public final long revision;
    public final String recordSha256;
    public final String state;

    public EmbeddedDuplexEnrollmentStatus(EmbeddedDuplexEnrollmentRequest installed,
            long revision, String recordSha256, String state) {
        if (installed == null || revision < 0L || (revision == 0L) != (recordSha256 == null)
                || (recordSha256 != null && !recordSha256.matches("[0-9a-f]{64}"))
                || !("uninitialized".equals(state) || "context_mismatch".equals(state)
                        || "enrolled".equals(state) || "local_fixture".equals(state))
                || (revision == 0L) != "uninitialized".equals(state)) {
            throw new IllegalArgumentException("enrollment status invalid");
        }
        this.installed = installed;
        this.revision = revision;
        this.recordSha256 = recordSha256;
        this.state = state;
    }
}
