package io.github.mesmerprism.rustyquest.native_renderer;

/** Typed identity contract implemented by one build-selected control-panel module. */
public interface PanelModule {
    /** Stable module identity baked into the resolved app lock and generated activity shell. */
    String panelModuleId();
}
