package io.github.mesmerprism.rustyquest.packageupdater;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Test-build-only adb RPC boundary. Manifest permission plus the Binder UID
 * check restrict commands to the platform shell user.
 */
public final class E2ePackageUpdaterCliProvider extends ContentProvider {
    private static final int ANDROID_SHELL_UID = 2000;
    private static final String PROTOCOL =
            "rusty.quest.package_update.e2e_cli.v1";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        requireShellCaller();
        try {
            E2eUpdateOperationStore store =
                    new E2eUpdateOperationStore(requireContext());
            String command = method == null ? "" : method.trim().toLowerCase();
            boolean accepted;
            if ("check".equals(command)) {
                store.projectInstallReceipt();
                JSONObject operation = store.beginOrReuse();
                accepted = true;
                if (!E2ePackageUpdateService.isRunning()) {
                    InstallReceiptStore receiptStore =
                            new InstallReceiptStore(requireContext());
                    JSONObject receipt = receiptStore.read();
                    if (receipt != null
                            && !InstallReceiptStore.isTerminal(
                                    receipt.optString("state"))
                            && store.correlateInstallReceipt(
                                    operation.getString("operation_id"),
                                    receipt)) {
                        PackageInstallController controller =
                                new PackageInstallController(
                                        requireContext(), receiptStore);
                        if (operation.optBoolean(
                                "cancel_requested", false)) {
                            controller.cancelPersistedSession();
                        } else {
                            controller.reconcilePersistedSession();
                        }
                        store.projectInstallReceipt();
                        operation = store.beginOrReuse();
                    }
                    if (!operation.optBoolean("terminal", false)
                            && operation.isNull("session_id")
                            && !"queued".equals(
                                    operation.optString("state"))) {
                        store.terminal(
                                operation.getString("operation_id"),
                                operation.optBoolean(
                                                "cancel_requested", false)
                                        ? "cancelled"
                                        : "failed",
                                operation.optBoolean(
                                                "cancel_requested", false)
                                        ? "update_cancelled"
                                        : "interrupted_operation_retried");
                        operation = store.beginOrReuse();
                    }
                }
                if ("queued".equals(operation.optString("state"))
                        && !E2ePackageUpdateService.isRunning()) {
                    E2ePackageUpdateService.startCheck(requireContext());
                }
            } else if ("status".equals(command)) {
                store.projectInstallReceipt();
                accepted = true;
            } else if ("cancel".equals(command)) {
                store.requestCancel();
                E2ePackageUpdateService.requestCancel();
                InstallReceiptStore receiptStore =
                        new InstallReceiptStore(requireContext());
                JSONObject receipt = receiptStore.read();
                if (receipt != null
                        && !InstallReceiptStore.isTerminal(
                                receipt.optString("state"))) {
                    new PackageInstallController(
                            requireContext(), receiptStore)
                            .cancelPersistedSession();
                }
                store.projectInstallReceipt();
                accepted = true;
            } else {
                return response(
                        command,
                        false,
                        null,
                        "unsupported_cli_command");
            }
            return response(command, accepted, store.read(), null);
        } catch (Exception exception) {
            return response(
                    method,
                    false,
                    null,
                    E2eUpdateOperationStore.stableErrorCode(
                            exception.getMessage()));
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        requireShellCaller();
        throw new UnsupportedOperationException("call_only_provider");
    }

    @Override
    public String getType(Uri uri) {
        requireShellCaller();
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        requireShellCaller();
        throw new UnsupportedOperationException("call_only_provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        requireShellCaller();
        throw new UnsupportedOperationException("call_only_provider");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        requireShellCaller();
        throw new UnsupportedOperationException("call_only_provider");
    }

    private Bundle response(
            String command,
            boolean accepted,
            JSONObject operation,
            String errorCode) {
        try {
            JSONObject body = new JSONObject()
                    .put("schema",
                            "rusty.quest.package_update.e2e_cli_response.v1")
                    .put("command", command == null ? "" : command)
                    .put("accepted", accepted)
                    .put("operation",
                            operation == null ? JSONObject.NULL : operation)
                    .put("error_code",
                            errorCode == null ? JSONObject.NULL : errorCode);
            String encoded = Base64.encodeToString(
                    body.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE
                            | Base64.NO_PADDING
                            | Base64.NO_WRAP);
            Bundle result = new Bundle();
            result.putString("protocol", PROTOCOL);
            result.putString("result_b64", encoded);
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("cli_response_encoding_failed");
        }
    }

    private static void requireShellCaller() {
        if (Binder.getCallingUid() != ANDROID_SHELL_UID) {
            throw new SecurityException("e2e_cli_requires_adb_shell");
        }
    }
}
