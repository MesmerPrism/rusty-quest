package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Build-fixed media policy; never extends setup credentials or grants a route. */
final class EmbeddedDuplexMediaPolicy {
    static String schema(String selection, boolean fixture, boolean concurrentOwnPeer) {
        if (!"finite".equals(selection) && !"ongoing-v2".equals(selection)) {
            throw new IllegalStateException("unsupported embedded duplex media policy");
        }
        if (fixture || "finite".equals(selection)) return null;
        if (!concurrentOwnPeer) {
            throw new IllegalStateException("ongoing media requires concurrent real-peer owners");
        }
        return "rusty.manifold.peer.ongoing_media_policy.v2";
    }
    private EmbeddedDuplexMediaPolicy() { }
}
