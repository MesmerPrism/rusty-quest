package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;

/**
 * Fail-closed registry for the single module baked into this APK.
 *
 * <p>The registry is generated from the resolved native-app feature lock. It deliberately has
 * no property, profile, reflection, discovery, fallback, or ambient allowlist path.</p>
 */
public final class PanelModuleRegistry {
    private final String selectedModuleId;
    private final Class<? extends Activity> entryClass;

    private PanelModuleRegistry(String selectedModuleId, Class<? extends Activity> entryClass) {
        this.selectedModuleId = selectedModuleId;
        this.entryClass = entryClass;
    }

    public static PanelModuleRegistry requireExact(
        String selectedModuleId,
        String declaredModuleId,
        Class<? extends Activity> entryClass
    ) {
        if (selectedModuleId == null || selectedModuleId.length() == 0) {
            throw new IllegalStateException("Native panel selection is absent");
        }
        if (declaredModuleId == null || !selectedModuleId.equals(declaredModuleId)) {
            throw new IllegalStateException(
                "Native panel selection does not match packaged entry module: selected="
                    + selectedModuleId + " declared=" + declaredModuleId
            );
        }
        if (entryClass == null || !PanelModule.class.isAssignableFrom(entryClass)) {
            throw new IllegalStateException("Native panel entry does not implement PanelModule");
        }
        return new PanelModuleRegistry(selectedModuleId, entryClass);
    }

    public String selectedModuleId() {
        return selectedModuleId;
    }

    public Class<? extends Activity> entryClass() {
        return entryClass;
    }

    /** Runtime inputs are observations only and can never select or widen the packaged module. */
    public boolean runtimeInputCanActivate(String ignoredRuntimeModuleId) {
        return false;
    }
}
