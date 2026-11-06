// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.shellrecenter;

import android.app.appfunctions.AppFunctionException;
import android.app.appfunctions.AppFunctionManager;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Process;
import io.github.mesmerprism.rustyquest.shellrecenter.ShellRecenterOperation.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Real production binding with independent framework doubles, never device evidence. */
public final class AndroidBindingConformanceMain {
    private static final String BOOT = "00000000-0000-0000-0000-000000000001";
    static AndroidShellRecenter backend(String source, String boot, String firmware) {
        return new AndroidShellRecenter(new Handler(), boot, firmware, source, () -> BOOT);
    }
    static AndroidShellRecenter backend() { return backend(SourceIdentity.SHA256, BOOT, Build.FINGERPRINT); }
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    static final class Journal implements ShellRecenterOperation.Journal {
        int intents, finals; Snapshot result;
        public void intent(Snapshot snapshot) { intents++; }
        public void terminal(Snapshot snapshot) { finals++; result = snapshot; }
    }
    public static void main(String[] args) throws Exception {
        check(!backend("wrong-source", BOOT, Build.FINGERPRINT).inspect().ready, "source mismatch denied");
        check(!backend(SourceIdentity.SHA256, "wrong-boot", Build.FINGERPRINT).inspect().ready, "boot mismatch denied");
        check(!backend(SourceIdentity.SHA256, BOOT, "old-firmware").inspect().ready, "firmware mismatch denied");
        Build.VERSION.SDK_INT = 33; check(!backend().inspect().ready, "unsupported firmware denied"); Build.VERSION.SDK_INT = 34;
        Process.uid = 10001; check(!backend().inspect().ready, "ordinary APK cannot gain shell context"); Process.uid = 2000;
        Context.permission = -1; check(!backend().inspect().ready, "normal permission denial remains denial"); Context.permission = 0;
        AndroidShellRecenter binding = backend(); check(binding.inspect().ready, "actual fixed reflective binding supported");
        Journal journal = new Journal();
        ShellRecenterOperation operation = new ShellRecenterOperation(Mode.RECENTER, binding, journal, (event, snapshot) -> { });
        operation.start();
        check(AppFunctionManager.dispatches == 1 && journal.intents == 1 && journal.finals == 0, "production controller invokes production binding once");
        operation.deadline(); check(operation.snapshot().cleanupHeld, "actual pending binding retained");
        AppFunctionManager.original.onResult(new ExecuteAppFunctionResponse());
        check(journal.finals == 1 && journal.result.callbackReceived && journal.result.deadlineReached && "original-empty-document".equals(journal.result.resultDocument), "actual typed late response decoded");
        AppFunctionException duplicate = new AppFunctionException("duplicate error"); AppFunctionManager.original.onError(duplicate);
        check(journal.finals == 1 && journal.result.originalError == null, "duplicate error cannot poison result");
        AndroidShellRecenter second = backend(); Journal errors = new Journal();
        ShellRecenterOperation errorOperation = new ShellRecenterOperation(Mode.RECENTER, second, errors, (event, snapshot) -> { });
        errorOperation.start(); AppFunctionException originalError = new AppFunctionException("original error");
        AppFunctionManager.original.onError(originalError);
        check(errors.result.originalError == originalError && "ORIGINAL_ERROR".equals(errors.result.outcome), "original platform exception identity retained");
        check("service_callback".equals(errors.result.callbackErrorOrigin), "actual service error origin preserved");
        JSONObject encodedError = (JSONObject)ShellRecenterMain.error(originalError).opt(0);
        check("android.app.appfunctions.AppFunctionException".equals(encodedError.opt("class")) && Integer.valueOf(17).equals(encodedError.opt("error_code")), "actual service exception class and code serialized");
        Journal malformed = new Journal();
        new ShellRecenterOperation(Mode.RECENTER, backend(), malformed, (event, snapshot) -> { }).start();
        AppFunctionManager.original.onResult(new Object());
        check("ORIGINAL_CALLBACK_UNREADABLE".equals(malformed.result.outcome) && "response_decoding".equals(malformed.result.callbackErrorOrigin), "local decode error distinct from service error");
        Journal readiness = new Journal(); int before = AppFunctionManager.dispatches;
        new ShellRecenterOperation(Mode.READINESS, backend(), readiness, (event, snapshot) -> { }).start();
        check(readiness.finals == 1 && readiness.intents == 0 && AppFunctionManager.dispatches == before, "production readiness never invokes execute");
        JSONObject record = ShellRecenterMain.record("TERMINAL", journal.result, "a", "b", binding.evidence(), true);
        check(Boolean.TRUE.equals(record.opt("intent_published")) && Boolean.TRUE.equals(record.opt("original_callback_received")) && Boolean.FALSE.equals(record.opt("pose_or_visibility_acknowledged")), "final serialization distinguishes intent callback and actual effect");
        check(SourceIdentity.SHA256.equals(record.opt("source_id")) && BOOT.equals(record.opt("boot_id")), "final serialization pins actual source and boot");
        Path directory = Files.createTempDirectory("shell-recenter-record-test");
        try {
            Path file = directory.resolve("FINAL.json"); JSONObject pin = ShellRecenterMain.publish(file.toFile(), record);
            byte[] bytes = Files.readAllBytes(file);
            check(new String(bytes, StandardCharsets.UTF_8).equals(record.toString() + "\n") && ((Number)pin.opt("bytes")).intValue() == bytes.length, "actual final bytes preserved and sized");
            StringBuilder sha = new StringBuilder(); for(byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format("%02x", b & 255));
            check(sha.toString().equals(pin.opt("sha256")), "actual durable final hash joins bytes");
            try { ShellRecenterMain.publish(file.toFile(), record); throw new AssertionError("final overwrite admitted"); } catch (IllegalStateException expected) { }
            Files.delete(file);
        } finally { Files.delete(directory); }
        System.out.println("Android production binding: privilege/permission/source/boot/firmware, fixed API, late callback/error and readiness passed; device_tested=false");
    }
}
