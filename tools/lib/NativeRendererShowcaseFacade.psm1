# Independent selected facade shared by the APK builder and isolated Java gate.
function New-NativeRendererShowcaseFacadeSource {
    @'
package io.github.mesmerprism.rustyquest.native_renderer;
public final class ControlPanelActivity extends ShowcasePanelModule {
    public static void closePanelAndReturnToImmersive(android.app.Activity activity) { activity.finish(); }
}
'@
}
Export-ModuleMember -Function New-NativeRendererShowcaseFacadeSource
