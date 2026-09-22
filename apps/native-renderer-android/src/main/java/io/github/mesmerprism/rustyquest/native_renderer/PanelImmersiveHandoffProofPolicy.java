package io.github.mesmerprism.rustyquest.native_renderer;

/** Separates an accepted VR launch from a visibly focused, advancing immersive return. */
final class PanelImmersiveHandoffProofPolicy {
    private PanelImmersiveHandoffProofPolicy() { }

    static boolean qualifies(boolean panelPaused, boolean postPauseLaunchDispatched,
            boolean freshRendererState,
            boolean openXrFocused, boolean submitted, boolean immersiveWindowFocused,
            long frameCount, long baselineFrame) {
        return panelPaused && postPauseLaunchDispatched && freshRendererState
            && openXrFocused && submitted
            && immersiveWindowFocused && frameCount > Math.max(0L, baselineFrame);
    }
}
