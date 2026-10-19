// SPDX-License-Identifier: AGPL-3.0-or-later
package example.shellidentity;

import android.app.Application;
import android.app.AppOpsManager;
import android.app.appfunctions.AppFunctionManager;
import android.os.Build;
import android.os.Process;
import android.system.Os;
import io.github.mesmerprism.rustyquest.shellrecenter.ShellApplicationContext;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.json.JSONObject;

/** External-package consumer of the real factory; simulated framework, no device effects. */
public final class PublicShellContextConformanceMain {
    private static void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }
    private static void denied(String why) throws Exception {
        try {
            ShellApplicationContext.create(new JSONObject());
            throw new AssertionError(why);
        } catch (SecurityException expected) { }
    }
    public static void main(String[] args) throws Exception {
        int publicMethods = 0;
        for (Method method : ShellApplicationContext.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            publicMethods++;
            check("create".equals(method.getName()) && Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == Application.class
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == JSONObject.class,
                    "only the typed target-free factory is public");
        }
        check(publicMethods == 1, "no public verification bypass or operation surface");
        Process.uid = 10001; denied("ordinary APK cannot gain shell context"); Process.uid = 2000;
        Os.uidOverride = 10001; denied("real UID must also be shell"); Os.uidOverride = null;
        Os.euidOverride = 10001; denied("effective UID must also be shell"); Os.euidOverride = null;
        Build.VERSION.SDK_INT = 30; denied("missing attribution API remains unavailable"); Build.VERSION.SDK_INT = 34;
        AppOpsManager.denied = true; denied("normal AppOps denial cannot be bypassed"); AppOpsManager.denied = false;
        JSONObject evidence = new JSONObject();
        Application app = ShellApplicationContext.create(evidence);
        check(app.getApplicationContext() == app && "com.android.shell".equals(app.getPackageName()),
                "external caller receives the genuine admitted shell Application");
        check(Boolean.TRUE.equals(evidence.opt("identityAdmitted"))
                && Integer.valueOf(2000).equals(evidence.opt("attributionUid"))
                && Integer.valueOf(Process.myPid()).equals(evidence.opt("attributionPid")),
                "factory retains typed identity and provenance evidence");
        check(AppFunctionManager.dispatches == 0, "context construction never dispatches an AppFunction");
        System.out.println("External public shell context: typed factory, UID/eUID/SDK/AppOps denial and unchanged evidence passed; device_tested=false");
    }
}
