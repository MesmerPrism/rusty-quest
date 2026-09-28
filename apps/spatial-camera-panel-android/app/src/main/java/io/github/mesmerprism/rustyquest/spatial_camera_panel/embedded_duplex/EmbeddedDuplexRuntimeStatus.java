package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Process-owned bootstrap state. This contains no peer, route, or media acceptance claim. */
public final class EmbeddedDuplexRuntimeStatus {
    public final String state;
    public final boolean displayAttached;
    public final String ownAppCaptureState;
    public final String cleanupScope;
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
        this.ownAppCaptureState = "disabled";
        this.cleanupScope = "whole_product";
        this.state = state;
        this.displayAttached = displayAttached;
        this.runtimeConfigSha256 = runtimeConfigSha256;
        this.enrollmentRecordSha256 = enrollmentRecordSha256;
    }
    EmbeddedDuplexRuntimeStatus(EmbeddedDuplexRuntimeStatus peerStatus, String ownAppCaptureState) {
        if (!("Idle".equals(ownAppCaptureState) || "Starting".equals(ownAppCaptureState)
                || "Live".equals(ownAppCaptureState) || "StopPending".equals(ownAppCaptureState)))
            throw new IllegalArgumentException("Own app capture state invalid");
        this.state = peerStatus.state; this.displayAttached = peerStatus.displayAttached;
        this.runtimeConfigSha256 = peerStatus.runtimeConfigSha256;
        this.enrollmentRecordSha256 = peerStatus.enrollmentRecordSha256;
        this.ownAppCaptureState = ownAppCaptureState;
        this.cleanupScope = "peer_subscription_only";
    }
}
