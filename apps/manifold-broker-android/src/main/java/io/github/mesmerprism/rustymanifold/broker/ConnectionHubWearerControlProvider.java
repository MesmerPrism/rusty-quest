package io.github.mesmerprism.rustymanifold.broker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Signature-scoped, wearer-initiated Connection Hub lifecycle adapter.
 *
 * <p>The caller selects only start, stop, or status. No endpoint, identity, capability, package,
 * component, path, session, or pairing secret crosses this surface. Android enforces the existing
 * shared-signer BROKER_ADMISSION permission before this provider is entered.</p>
 */
public final class ConnectionHubWearerControlProvider extends ContentProvider {
    public static final String AUTHORITY =
            "io.github.mesmerprism.rustymanifold.broker.connection-hub-wearer-control";
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList(
            ConnectionHubOperatorController.ACTION_START,
            ConnectionHubOperatorController.ACTION_STOP,
            ConnectionHubOperatorController.ACTION_STATUS));

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        if (!METHODS.contains(method) || argument != null
                || (extras != null && !extras.keySet().isEmpty())) {
            throw new IllegalArgumentException("connection_hub_wearer_control_request_invalid");
        }
        Context context = requireAttachedContext();
        if (ConnectionHubOperatorController.ACTION_START.equals(method)) {
            Intent intent = new Intent(context, ConnectionHubStartService.class)
                    .setAction(ConnectionHubStartService.ACTION_START_HUB);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } else if (ConnectionHubOperatorController.ACTION_STOP.equals(method)) {
            ConnectionHubOperatorController.Result stop =
                    ConnectionHubProcess.get(context).operatorController().execute(
                            ConnectionHubOperatorController.ACTION_STOP, new JSONObject());
            if (stop.receipt.optBoolean("applied", false)) {
                context.stopService(new Intent(context, ConnectionHubStartService.class));
            }
        }
        ConnectionHubOperatorController.Result status =
                ConnectionHubProcess.get(context).operatorController().execute(
                        ConnectionHubOperatorController.ACTION_STATUS, new JSONObject());
        JSONObject receipt = status.receipt;
        JSONObject effective = receipt.optJSONObject("effective_state");
        Bundle output = new Bundle();
        output.putString("schema", "rusty.quest.connection_hub.wearer_control_snapshot.v1");
        output.putString("action", method);
        output.putBoolean("status_available", effective != null);
        output.putBoolean("listener_enabled",
                effective != null && effective.optBoolean("listener_enabled", false));
        output.putString("desired_connection_state",
                effective == null ? "unavailable"
                        : effective.optString("desired_connection_state", "unavailable"));
        output.putBoolean("pairing_available",
                effective != null && effective.optBoolean("pairing_available", false));
        output.putInt("active_controller_sessions",
                effective == null ? 0 : effective.optInt("active_controller_sessions", 0));
        output.putString("transport_classification",
                effective == null ? "unavailable"
                        : effective.optString("transport_classification", "unavailable"));
        output.putString("confidentiality",
                effective == null ? "unavailable"
                        : effective.optString("confidentiality", "unavailable"));
        output.putBoolean("production_eligible",
                effective != null && effective.optBoolean("production_eligible", false));
        output.putBoolean("secrets_in_snapshot", false);
        output.putBoolean("caller_selected_authority", false);
        return output;
    }

    private Context requireAttachedContext() {
        Context value = getContext();
        if (value == null) {
            throw new IllegalStateException("connection_hub_wearer_control_context_unavailable");
        }
        return value.getApplicationContext();
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("call_only");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("call_only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("call_only");
    }
}
