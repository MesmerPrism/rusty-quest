package io.github.mesmerprism.rustymanifold.broker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Published, shell-UID-gated typed operator adapter. ADB already owns the
 * package trust surface; this provider adds bounded command and receipt
 * semantics without accepting components, paths, intents, identities, grants,
 * or capabilities.
 */
public final class ConnectionHubOperatorProvider extends ContentProvider {
    public static final String AUTHORITY =
            "io.github.mesmerprism.rustymanifold.broker.connection-hub-operator";
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList(
            ConnectionHubOperatorController.ACTION_START,
            ConnectionHubOperatorController.ACTION_STOP,
            ConnectionHubOperatorController.ACTION_STATUS,
            ConnectionHubOperatorController.ACTION_PAIR,
            ConnectionHubOperatorController.ACTION_REVOKE,
            ConnectionHubOperatorController.ACTION_FORGET));

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw new SecurityException("connection_hub_operator_requires_shell_uid");
        }
        if (!METHODS.contains(method) || argument != null) {
            throw new IllegalArgumentException("connection_hub_operator_method_not_registered");
        }
        Bundle safeExtras = extras == null ? Bundle.EMPTY : extras;
        JSONObject arguments = decodeArguments(method, safeExtras);
        Context context = requireAttachedContext();
        if (ConnectionHubOperatorController.ACTION_START.equals(method)) {
            ensureForegroundService(context);
        }
        ConnectionHubOperatorController.Result result =
                ConnectionHubProcess.get(context).operatorController().execute(method, arguments);
        if (ConnectionHubOperatorController.ACTION_STOP.equals(method)
                && result.receipt.optBoolean("applied", false)) {
            context.stopService(new Intent(context, ConnectionHubStartService.class));
        }
        Bundle output = new Bundle();
        output.putString("receipt_b64", encode(result.receipt.toString()));
        if (result.credential != null) {
            output.putString("credential_b64", encode(result.credential));
        }
        return output;
    }

    private static JSONObject decodeArguments(String method, Bundle extras) {
        JSONObject value = new JSONObject();
        if (ConnectionHubOperatorController.ACTION_PAIR.equals(method)) {
            requireOnly(extras, "pairing_code_b64", "controller_identity_sha256");
            putJson(value, "pairing_code", decode(extras.getString("pairing_code_b64", "")));
            putJson(value, "controller_identity_sha256",
                    extras.getString("controller_identity_sha256", ""));
        } else if (ConnectionHubOperatorController.ACTION_REVOKE.equals(method)) {
            requireOnly(extras, "session_b64", "reason");
            putJson(value, "session", decode(extras.getString("session_b64", "")));
            if (extras.containsKey("reason")) {
                putJson(value, "reason", extras.getString("reason", ""));
            }
        } else {
            requireOnly(extras);
        }
        return value;
    }

    private static void putJson(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception error) {
            throw new IllegalStateException("connection_hub_operator_json_encode_failed", error);
        }
    }

    private static void requireOnly(Bundle extras, String... allowedNames) {
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedNames));
        if (!allowed.equals(extras.keySet())) {
            throw new IllegalArgumentException("connection_hub_operator_extras_invalid");
        }
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String decode(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        byte[] decoded = null;
        try {
            decoded = Base64.decode(encoded, Base64.NO_WRAP);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("connection_hub_operator_secret_invalid", error);
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (decoded != null) { Arrays.fill(decoded, (byte) 0); }
        }
    }

    private static void ensureForegroundService(Context context) {
        Intent service = new Intent(context, ConnectionHubStartService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    private Context requireAttachedContext() {
        Context value = getContext();
        if (value == null) {
            throw new IllegalStateException("connection_hub_operator_context_unavailable");
        }
        return value.getApplicationContext();
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
