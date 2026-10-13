package io.github.mesmerprism.rustyquest.native_renderer;

public final class NativeRendererBootLaunchPolicyTest {
    private static int checks;
    private static void expect(boolean wanted, boolean actual) {
        checks++;
        if (wanted != actual) throw new AssertionError("Boot admission control " + checks);
    }
    public static void main(String[] args) {
        String boot = NativeRendererBootLaunchPolicy.BOOT_ACTION;
        expect(true, NativeRendererBootLaunchPolicy.admits(boot, true, true, 7, 6));
        expect(false, NativeRendererBootLaunchPolicy.admits(boot, true, true, 7, 7));
        expect(false, NativeRendererBootLaunchPolicy.admits(boot, false, true, 7, 6));
        expect(false, NativeRendererBootLaunchPolicy.admits(boot, true, false, 7, 6));
        expect(false, NativeRendererBootLaunchPolicy.admits(boot, true, true, -1, 6));
        expect(false, NativeRendererBootLaunchPolicy.admits(null, true, true, 7, 6));
        expect(false, NativeRendererBootLaunchPolicy.admits("", true, true, 7, 6));
        expect(false, NativeRendererBootLaunchPolicy.admits("android.intent.action.USER_PRESENT", true, true, 7, 6));
        expect(false, NativeRendererBootLaunchPolicy.admits("android.intent.action.LOCKED_BOOT_COMPLETED", true, true, 7, 6));
        expect(false, NativeRendererBootLaunchPolicy.admits("BOOT_COMPLETED", true, true, 7, 6));
        // System boot counts may reset; equality prevents replay without inventing monotonic authority.
        expect(true, NativeRendererBootLaunchPolicy.admits(boot, true, true, 0, 7));
        expect(false, NativeRendererBootLaunchPolicy.admits(boot, true, true, 0, 0));
        System.out.println("Boot launch admission controls passed: " + checks);
    }
}
