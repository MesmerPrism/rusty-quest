package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Closed process-owned actions; command identities and credentials come from native authority. */
public enum EmbeddedDuplexPeerAction {
    START(1, "start"), RENEW_AUTHORITY(2, "renew_authority"), STOP(3, "peer_stop"),
    REVOKE(4, "peer_revoke"), STATUS(5, "peer_status"), WHOLE_APP_CLOSE(6, "whole_app_close");
    final int word;
    final String action;
    EmbeddedDuplexPeerAction(int word, String action) { this.word = word; this.action = action; }
}
