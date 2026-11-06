package io.github.mesmerprism.rustyquest.native_renderer;

/** Closed, once-per-system-boot admission for the selected own-app front door. */
public final class NativeRendererBootLaunchPolicy {
    public static final String BOOT_ACTION = "android.intent.action.BOOT_COMPLETED";
    public static final String SELECTION_METADATA = "rusty.quest.experiment.boot_autostart";

    private NativeRendererBootLaunchPolicy() { }

    public static boolean admits(String action, boolean selected, boolean overlayGranted,
            int bootCount, int lastAttemptedBootCount) {
        return BOOT_ACTION.equals(action) && selected && overlayGranted && bootCount >= 0
            && bootCount != lastAttemptedBootCount;
    }
}
