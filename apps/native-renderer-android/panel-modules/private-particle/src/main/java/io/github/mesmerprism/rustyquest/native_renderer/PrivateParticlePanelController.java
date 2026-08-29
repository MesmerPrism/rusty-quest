package io.github.mesmerprism.rustyquest.native_renderer;

/**
 * Low-rate request adapter for private-particle panel pages.
 *
 * <p>The controller never owns simulation or effective renderer state. It forwards a typed JSON
 * candidate to the consuming Rust runtime and returns that owner's response unchanged.</p>
 */
final class PrivateParticlePanelController {
    static final String MODULE_ID = "private-particle-controls";

    private PrivateParticlePanelController() { }

    static String submitCandidate(String candidateJson) {
        return ControlPanelActivity.nativeSubmitLivePrivateParticleDynamics(candidateJson);
    }
}
