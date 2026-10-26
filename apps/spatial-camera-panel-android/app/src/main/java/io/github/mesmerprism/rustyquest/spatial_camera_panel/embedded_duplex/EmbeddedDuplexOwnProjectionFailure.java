package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Internal closed diagnosis only. No exception text, input or authority is exported. */
final class EmbeddedDuplexOwnProjectionFailure extends IllegalStateException {
    enum Reason { CAPTURE_UNAVAILABLE, FRESHNESS_PENDING, CARRIER_NOT_READY, SUPERSEDED, INTERRUPTED }
    final Reason reason;
    EmbeddedDuplexOwnProjectionFailure(Reason reason) {
        super("Own projection unavailable");
        if (reason == null) throw new IllegalArgumentException("closed Own projection reason required");
        this.reason = reason;
    }
}
