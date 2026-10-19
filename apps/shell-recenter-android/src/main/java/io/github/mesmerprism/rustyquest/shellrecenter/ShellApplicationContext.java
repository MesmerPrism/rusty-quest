// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.shellrecenter;

import android.app.Application;
import android.app.AppOpsManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Genuine same-UID framework Application construction; no identity field writes. */
@SuppressWarnings("deprecation") // app_process main looper and read-only AppOps identity check.
public final class ShellApplicationContext {
    static final String PACKAGE = "com.android.shell";
    private ShellApplicationContext() {}

    public static Application create(JSONObject evidence) throws Exception {
        require(Process.myUid() == 2000 && Os.getuid() == 2000 && Os.geteuid() == 2000,
                "actual-shell-uid-required");
        require(Build.VERSION.SDK_INT >= 31, "attribution-api-unavailable");
        evidence.put("uid", Process.myUid()).put("effectiveUid", Os.geteuid())
                .put("pid", Process.myPid()).put("gid", Os.getgid())
                .put("sdkInt", Build.VERSION.SDK_INT);
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        // Ordinary hidden/framework API admission only. Never set exemptions,
        // setAccessible, mInitialApplication/mBoundApplication or ApplicationInfo fields.
        Class<?> threadType = Class.forName("android.app.ActivityThread");
        evidence.put("contextStage", "framework-systemMain-bootstrap");
        Object thread = threadType.getMethod("systemMain").invoke(null);
        Object initial = threadType.getMethod("getApplication").invoke(thread);
        evidence.put("bootstrapInitialApplicationClass", initial == null ? JSONObject.NULL
                : initial.getClass().getName());
        evidence.put("bootstrapInitialApplicationPackage", initial instanceof Context
                ? ((Context) initial).getPackageName() : JSONObject.NULL);
        evidence.put("bootstrap", "ActivityThread.systemMain; initial system Application is not passed to XR");
        Class<?> compatibilityType = Class.forName("android.content.res.CompatibilityInfo");
        Object compatibility = compatibilityType.getField("DEFAULT_COMPATIBILITY_INFO").get(null);
        evidence.put("contextStage", "framework-real-shell-loaded-package");
        Object loaded = threadType.getMethod("getPackageInfo", String.class, compatibilityType,
                int.class).invoke(thread, PACKAGE, compatibility, 0);
        require(loaded != null, "shell-loaded-package-unavailable");
        ApplicationInfo metadata = (ApplicationInfo) loaded.getClass()
                .getMethod("getApplicationInfo").invoke(loaded);
        require(metadata != null && metadata.uid == 2000 && PACKAGE.equals(metadata.packageName),
                "shell-loaded-package-uid-mismatch");
        // forceDefault=true avoids the installed shell APK's custom Application/onCreate.
        // Framework makeApplication creates its own null-container app Context and
        // populates this LoadedApk's real Application. No package-code flag is added.
        Class<?> instrumentation = Class.forName("android.app.Instrumentation");
        evidence.put("contextStage", "framework-default-shell-application");
        Object result = loaded.getClass().getMethod("makeApplication", boolean.class,
                instrumentation).invoke(loaded, true, null);
        require(result instanceof Application, "shell-application-unavailable");
        Application app = (Application) result;
        verify(app, evidence);
        return app;
    }

    static void verify(Application app, JSONObject evidence) throws Exception {
        evidence.put("identityAdmitted", false);
        evidence.put("contextStage", "normal-package-context-admission");
        require(Process.myUid() == 2000 && Os.getuid() == 2000 && Os.geteuid() == 2000,
                "actual-shell-uid-required");
        require(app.getApplicationContext() == app, "application-context-not-self");
        PackageManager pm = app.getPackageManager();
        PackageInfo info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        ApplicationInfo ai = info.applicationInfo;
        ApplicationInfo contextInfo = app.getApplicationInfo();
        require(ai != null && ai.uid == Process.myUid() && PACKAGE.equals(info.packageName)
                && PACKAGE.equals(ai.packageName), "pm-shell-uid-mismatch");
        require(contextInfo != null && contextInfo.uid == ai.uid
                && PACKAGE.equals(contextInfo.packageName), "context-application-info-mismatch");
        require(PACKAGE.equals(app.getPackageName())
                && PACKAGE.equals(app.getApplicationContext().getPackageName()), "context-package-mismatch");
        // Resolve on public Context's declaration, not package-private ContextImpl.
        String op = (String) Context.class.getMethod("getOpPackageName").invoke(app);
        AttributionSource attribution = app.getAttributionSource();
        require(PACKAGE.equals(op) && attribution != null
                && PACKAGE.equals(attribution.getPackageName())
                && attribution.getUid() == Process.myUid()
                && attribution.getPid() == Process.myPid(), "context-attribution-mismatch");
        evidence.put("packageName", app.getPackageName()).put("opPackageName", op)
                .put("packageUid", ai.uid).put("applicationContextSelf", true)
                .put("applicationClass", app.getClass().getName())
                .put("attributionPackage", attribution.getPackageName())
                .put("attributionUid", attribution.getUid()).put("attributionPid", attribution.getPid())
                .put("applicationInfoFlags", ai.flags).put("sourceDir", ai.sourceDir)
                .put("nativeLibraryDir", ai.nativeLibraryDir).put("activityPresent", false);
        require(app.getAssets() != null && app.getContentResolver() != null,
                "context-assets-or-resolver-unavailable");
        AppOpsManager ops = app.getSystemService(AppOpsManager.class);
        require(ops != null, "appops-unavailable");
        evidence.put("contextStage", "normal-appops-package-check");
        ops.checkPackage(Process.myUid(), PACKAGE); // check only; no op/grant mutation.
        evidence.put("contextStage", "normal-privileged-app-reflection");
        Method privileged = ApplicationInfo.class.getDeclaredMethod("isPrivilegedApp");
        evidence.put("privilegedObservation", "attempted-normal-reflection");
        boolean isPrivileged = (Boolean) privileged.invoke(ai); // denial aborts; no exemption.
        evidence.put("isPrivilegedApp", isPrivileged).put("privilegedObservation", "returned");
        Signature[] signatures = info.signingInfo == null ? null
                : info.signingInfo.getApkContentsSigners();
        require(signatures != null && signatures.length > 0 && signatures.length <= 16,
                "shell-signatures-unavailable");
        JSONArray certificates = new JSONArray();
        for (Signature signature : signatures) certificates.put(sha(signature.toByteArray()));
        evidence.put("packageName", app.getPackageName()).put("opPackageName", op)
                .put("packageUid", ai.uid).put("applicationContextSelf", true)
                .put("applicationClass", app.getClass().getName())
                .put("attributionPackage", attribution.getPackageName())
                .put("attributionUid", attribution.getUid()).put("attributionPid", attribution.getPid())
                .put("applicationInfoFlags", ai.flags).put("isPrivilegedApp", isPrivileged)
                .put("isSystemApp", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                .put("signingCertificateSha256", certificates)
                .put("versionCode", info.getLongVersionCode()).put("sourceDir", ai.sourceDir)
                .put("nativeLibraryDir", ai.nativeLibraryDir)
                .put("activityPresent", false).put("identityAdmitted", true)
                .put("contextStage", "identity-admitted");
    }

    static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return out.toString();
    }

    static void require(boolean condition, String reason) {
        if (!condition) throw new SecurityException(reason);
    }
}
