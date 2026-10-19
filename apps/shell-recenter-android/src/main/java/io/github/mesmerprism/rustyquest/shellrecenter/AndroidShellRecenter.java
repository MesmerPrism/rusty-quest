// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.shellrecenter;

import android.app.Application;
import android.app.appsearch.GenericDocument;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.OutcomeReceiver;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Privileged shell-only fixed AppFunction binding; never usable as an APK privilege grant. */
public final class AndroidShellRecenter implements ShellRecenterOperation.Backend {
    public static final String PERMISSION = "android.permission.EXECUTE_APP_FUNCTIONS";
    private final String expectedBoot, expectedFingerprint, expectedSource;
    private final Handler handler;
    private final CancellationSignal cancellation = new CancellationSignal();
    private final JSONObject evidence = new JSONObject();
    private Object manager, request;
    private Method enabledMethod, executeMethod, resultMethod;
    private boolean admitted;
    interface BootReader { String read() throws Exception; }
    private final BootReader bootReader;

    public AndroidShellRecenter(Handler handler, String expectedBoot, String expectedFingerprint,
            String expectedSource) {
        this(handler, expectedBoot, expectedFingerprint, expectedSource, () ->
                new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")), StandardCharsets.UTF_8).trim());
    }
    AndroidShellRecenter(Handler handler, String expectedBoot, String expectedFingerprint,
            String expectedSource, BootReader bootReader) {
        if (handler == null) throw new NullPointerException("serialized handler required");
        this.handler = handler; this.expectedBoot = expectedBoot;
        this.expectedFingerprint = expectedFingerprint; this.expectedSource = expectedSource;
        this.bootReader = bootReader;
    }
    public JSONObject evidence() { return evidence; }
    public ShellRecenterOperation.Availability inspect() throws Exception {
        if (admitted) return new ShellRecenterOperation.Availability(true, "ready");
        evidence.put("sdk_int", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                .put("source_sha256", SourceIdentity.SHA256);
        if (!SourceIdentity.SHA256.equals(expectedSource)) return unavailable("source-mismatch");
        if (Build.VERSION.SDK_INT < 34) return unavailable("unsupported-firmware-sdk");
        if (expectedFingerprint == null || !Build.FINGERPRINT.equals(expectedFingerprint))
            return unavailable("firmware-fingerprint-mismatch");
        String boot = bootReader.read();
        evidence.put("boot_id", boot);
        if (expectedBoot == null || !boot.equals(expectedBoot)) return unavailable("boot-mismatch");
        try {
            Application app = ShellApplicationContext.create(evidence);
            boolean permission = app.checkSelfPermission(PERMISSION) == 0;
            evidence.put("execute_permission_granted", permission);
            if (!permission) return unavailable("normal-execute-permission-denied");
            Class<?> managerType = Class.forName("android.app.appfunctions.AppFunctionManager");
            Class<?> requestType = Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest");
            Class<?> builderType = Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest$Builder");
            Class<?> responseType = Class.forName("android.app.appfunctions.ExecuteAppFunctionResponse");
            Class.forName("android.app.appfunctions.AppFunctionException").getMethod("getErrorCode");
            manager = app.getSystemService(managerType);
            if (manager == null || !managerType.isInstance(manager)) return unavailable("app-function-service-unavailable");
            enabledMethod = managerType.getMethod("isAppFunctionEnabled", String.class, String.class,
                    Executor.class, OutcomeReceiver.class);
            executeMethod = managerType.getMethod("executeAppFunction", requestType, Executor.class,
                    CancellationSignal.class, OutcomeReceiver.class);
            resultMethod = responseType.getMethod("getResultDocument");
            request = builderType.getMethod("build").invoke(builderType.getConstructor(String.class,
                    String.class).newInstance(ShellRecenterOperation.TARGET_PACKAGE, ShellRecenterOperation.FUNCTION_ID));
            admitted = true;
            evidence.put("public_api_available", true).put("readiness", "ready-for-enabled-query");
            return new ShellRecenterOperation.Availability(true, "ready-for-enabled-query");
        } catch (ReflectiveOperationException | LinkageError | SecurityException error) {
            evidence.put("admission_error", ShellRecenterMain.error(error));
            return unavailable("identity-or-firmware-api-unavailable");
        }
    }
    private ShellRecenterOperation.Availability unavailable(String reason) throws Exception {
        evidence.put("readiness", reason);
        return new ShellRecenterOperation.Availability(false, reason);
    }
    private Executor executor() { return runnable -> { if (!handler.post(runnable)) throw new IllegalStateException("callback-handler-stopped"); }; }
    public void enabled(ShellRecenterOperation.Callback<Boolean> callback) throws Exception {
        if (!admitted) throw new IllegalStateException("fresh-admission-required");
        enabledMethod.invoke(manager, ShellRecenterOperation.FUNCTION_ID,
                ShellRecenterOperation.TARGET_PACKAGE, executor(), new OutcomeReceiver<Object, Throwable>() {
                    public void onResult(Object value) {
                        if (value instanceof Boolean) callback.result((Boolean) value);
                        else callback.error(new IllegalStateException("typed-enabled-result-required"));
                    }
                    public void onError(Throwable error) { callback.error(error); }
                });
    }
    public void execute(ShellRecenterOperation.Callback<ShellRecenterOperation.Result> callback) throws Exception {
        if (!admitted) throw new IllegalStateException("fresh-admission-required");
        executeMethod.invoke(manager, request, executor(), cancellation, new OutcomeReceiver<Object, Throwable>() {
            public void onResult(Object response) {
                try {
                    Object raw = resultMethod.invoke(response);
                    if (!(raw instanceof GenericDocument)) throw new IllegalStateException("typed-result-document-required");
                    String text = raw.toString();
                    if (text.length() > 65536) throw new IllegalStateException("bounded-original-result-required");
                    callback.result(new ShellRecenterOperation.Result(text));
                } catch (ReflectiveOperationException | RuntimeException error) {
                    callback.error(new ShellRecenterOperation.CallbackDecodeException(error));
                }
            }
            public void onError(Throwable error) { callback.error(error); }
        });
    }
    public void cancel() { cancellation.cancel(); }
}
