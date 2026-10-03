package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Base64;
import java.nio.charset.StandardCharsets;

/** Exact local shell diagnostic transport; no caller payload, grant or Hub lifecycle API. */
public final class BridgeControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        getContext().enforceCallingPermission("android.permission.DUMP", "carrier_control_dump_permission_required");
        BridgeController.authorize(Binder.getCallingUid(), method, argument, extras != null && !extras.isEmpty());
        try {
            String receipt = new BridgeController(getContext()).invoke(method).toString();
            Bundle result = new Bundle();
            result.putString("receipt_b64", Base64.encodeToString(receipt.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            return result;
        } catch (Exception failure) { throw new IllegalStateException("carrier_control_handler_failed", failure); }
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] fields, String where, String[] values, String order) { throw new UnsupportedOperationException("typed_calls_only"); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("typed_calls_only"); }
    @Override public int delete(Uri uri, String where, String[] values) { throw new UnsupportedOperationException("typed_calls_only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException("typed_calls_only"); }
}
