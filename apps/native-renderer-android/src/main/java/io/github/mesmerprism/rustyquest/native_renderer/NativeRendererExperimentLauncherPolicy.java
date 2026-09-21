package io.github.mesmerprism.rustyquest.native_renderer;

/** Pure admission seam for the exported launcher trampoline. */
final class NativeRendererExperimentLauncherPolicy {
    private NativeRendererExperimentLauncherPolicy() {
    }

    static boolean admits(
            boolean recreation,
            String action,
            boolean hasLauncherCategory,
            int categoryCount,
            boolean hasDataOrSelector,
            String componentPackage,
            String componentClass,
            String ownPackage,
            String launcherClass) {
        return !recreation
            && "android.intent.action.MAIN".equals(action)
            && hasLauncherCategory
            && categoryCount == 1
            && !hasDataOrSelector
            && ownPackage != null
            && ownPackage.equals(componentPackage)
            && launcherClass != null
            && launcherClass.equals(componentClass);
    }
}
