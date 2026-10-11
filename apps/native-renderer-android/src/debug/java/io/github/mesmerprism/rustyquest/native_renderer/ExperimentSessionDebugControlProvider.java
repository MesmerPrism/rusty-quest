package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import org.json.JSONObject;

/** Opt-in debug source only. Synchronous Binder identity is checked before decoding. */
public final class ExperimentSessionDebugControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (getContext() == null || Binder.getCallingUid() != 2000
                || (getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
            throw new SecurityException("debug-shell-caller-required");
        getContext().enforceCallingPermission("android.permission.DUMP", "debug-shell-dump-required");
        if (!"experiment-control".equals(method) || arg == null || arg.length() > 8192
                || (extras != null && !extras.isEmpty()))
            throw new IllegalArgumentException("closed-operator-call-required");
        try {
            byte[] bytes = android.util.Base64.decode(arg,
                android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            if (bytes.length == 0 || bytes.length > 4096)
                throw new IllegalArgumentException("bounded-command-required");
            JSONObject request = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!"rusty.quest.experiment_session.operator_command.v1".equals(request.optString("schema")))
                throw new IllegalArgumentException("closed-operator-schema-required");
            Bundle result = new Bundle();
            result.putString("receipt_b64", android.util.Base64.encodeToString(
                ExperimentSessionDebugOperator.execute(getContext(), request)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING));
            return result;
        } catch (SecurityException denied) { throw denied; }
        catch (Exception invalid) { throw new IllegalArgumentException("operator-request-or-runtime-unavailable", invalid); }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
