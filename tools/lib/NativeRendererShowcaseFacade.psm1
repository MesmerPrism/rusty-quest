# Independent selected facade shared by the APK builder and isolated Java gate.
function New-NativeRendererShowcaseFacadeSource {
    param([switch]$Vulkan)
    if ($Vulkan) {
@'
package io.github.mesmerprism.rustyquest.native_renderer;
/** Reflection adapter only: not an Activity and never an Android window. */
public final class ControlPanelActivity {
    public static AutoCloseable attachNativeMeshSessionControls(android.app.Activity actual,android.widget.LinearLayout carrier,java.util.function.BooleanSupplier current) throws Exception { return ShowcaseNativeActivity.attach(actual,carrier,current); }
    public static boolean ownsPanelActivity(android.app.Activity actual) { return actual!=null&&actual==ShowcaseNativeActivity.current(); }
    public static void resumeNativeMeshImmersive(android.app.Activity actual) { if(!ownsPanelActivity(actual))throw new SecurityException("wrong-showcase-owner"); }
    public static void openShowcasePanel(android.content.Context context) { ShowcaseNativeActivity.open(); }
    public static void toggleShowcasePanel(android.content.Context context) { ShowcaseNativeActivity.toggle(); }
    public static void closePanelAndReturnToImmersive(android.app.Activity actual) { if(!ownsPanelActivity(actual))throw new SecurityException("wrong-showcase-owner");ShowcaseNativeActivity.current().requestVisibility(false); }
}
'@
        return
    }
    @'
package io.github.mesmerprism.rustyquest.native_renderer;
public final class ControlPanelActivity extends ShowcasePanelModule {
    public static void openShowcasePanel(android.content.Context context) { context.startActivity(new android.content.Intent(context,ControlPanelActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
    public static void toggleShowcasePanel(android.content.Context context) { android.app.Activity current=ShowcaseNativeBridge.currentPanel();if(current!=null)current.finish();else openShowcasePanel(context); }
    public static void closePanelAndReturnToImmersive(android.app.Activity activity) { activity.finish(); }
}
'@
}
Export-ModuleMember -Function New-NativeRendererShowcaseFacadeSource
