package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Process-owned bootstrap state. This contains no peer, route, or media acceptance claim. */
public final class EmbeddedDuplexRuntimeStatus {
    public final String state;
    public final boolean displayAttached;
    public final String runtimeConfigSha256;
    public final String enrollmentRecordSha256;

    EmbeddedDuplexRuntimeStatus(String state, boolean displayAttached,
            String runtimeConfigSha256, String enrollmentRecordSha256) {
        if (!("uninitialized".equals(state) || "bootstrapping".equals(state)
                || "bootstrapped_route_unverified".equals(state) || "local_fixture".equals(state)
                || "cleanup_pending".equals(state))
                || runtimeConfigSha256 != null
                        && !runtimeConfigSha256.matches("[0-9a-f]{64}")
                || enrollmentRecordSha256 != null
                        && !enrollmentRecordSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("runtime status invalid");
        }
        this.state = state;
        this.displayAttached = displayAttached;
        this.runtimeConfigSha256 = runtimeConfigSha256;
        this.enrollmentRecordSha256 = enrollmentRecordSha256;
    }
}
