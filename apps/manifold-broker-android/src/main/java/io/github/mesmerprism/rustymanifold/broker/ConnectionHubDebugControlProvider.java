package io.github.mesmerprism.rustymanifold.broker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Debug-build-only, shell-UID operator projection for deterministic local QA.
 * The production product manifest never packages this provider.
 */
public final class ConnectionHubDebugControlProvider extends ContentProvider {
    private static final String RECEIPT_SCHEMA =
            "rusty.quest.connection_hub.debug_operator_receipt.v1";

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw new SecurityException("connection_hub_debug_operator_requires_shell_uid");
        }
        if (!"status".equals(method)
                && !"start".equals(method)
                && !"stop".equals(method)
                && !"forget".equals(method)
                && !"force-rollover".equals(method)
                && !"restart-process".equals(method)
                && !"pair-code".equals(method)) {
            throw new IllegalArgumentException("debug_operator_method_not_registered");
        }
        ConnectionHubProcess hub = ConnectionHubProcess.get(getContext());
        if ("pair-code".equals(method)) {
            String code = hub.runtime().pairingCodeForWearer();
            if (!hub.runtime().listenerEnabled() || code == null) {
                throw new IllegalStateException("pairing_secret_not_available");
            }
            Bundle secret = new Bundle();
            secret.putString(
                    "secret_b64",
                    Base64.encodeToString(
                            code.getBytes(StandardCharsets.US_ASCII),
                            Base64.NO_WRAP));
            return secret;
        }
        boolean applied = true;
        String operationStatus = "observed";
        try {
            if ("start".equals(method)) {
                hub.startFromWearer();
                operationStatus = "start_dispatched";
            } else if ("stop".equals(method)) {
                hub.stopFromWearer();
                operationStatus = "stopped";
            } else if ("forget".equals(method)) {
                ConnectionHubAuthorityPort.Receipt authority = hub.forgetFromWearer();
                applied = authority.applied;
                operationStatus = authority.status;
            } else if ("force-rollover".equals(method)) {
                ConnectionHubAuthorityPort.Receipt authority =
                        hub.runtime().forceHistoryRolloverForDebug();
                applied = authority.applied;
                operationStatus = authority.status;
            }
            JSONObject safeStatus = hub.runtime().status();
            JSONObject receipt = new JSONObject();
            receipt.put("$schema", RECEIPT_SCHEMA);
            receipt.put("action", method);
            receipt.put("applied", applied);
            receipt.put("status", operationStatus);
            receipt.put("listener_running", hub.runtime().listenerEnabled());
            receipt.put("desired_connection_state",
                    safeStatus.getString("desired_connection_state"));
            receipt.put("runtime_status", safeStatus.getString("status"));
            receipt.put("active_controller_sessions", hub.runtime().activeSessionCount());
            receipt.put("origin", hub.displayOrigin());
            receipt.put("confidentiality", "none");
            receipt.put("production_eligible", false);
            receipt.put("pairing_secret_in_receipt", false);
            receipt.put("pid", Process.myPid());
            receipt.put("process_restart_scheduled", "restart-process".equals(method));
            Bundle output = new Bundle();
            output.putString(
                    "receipt_b64",
                    Base64.encodeToString(
                            receipt.toString().getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP));
            if ("restart-process".equals(method)) {
                final int pid = Process.myPid();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { Process.killProcess(pid); }
                }, 350L);
            }
            return output;
        } catch (Exception error) {
            throw new IllegalStateException("debug_operator_action_failed", error);
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read_call_only");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read_call_only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("read_call_only");
    }
}
