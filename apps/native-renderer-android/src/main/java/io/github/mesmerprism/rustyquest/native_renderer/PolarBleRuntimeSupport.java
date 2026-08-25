package io.github.mesmerprism.rustyquest.native_renderer;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Quest-specific runtime permission and adapter-state closure for direct Polar BLE. */
final class PolarBleRuntimeSupport {
    private PolarBleRuntimeSupport() {}

    static boolean ensureReady(Activity activity, int requestCode) {
        if (activity == null || Build.VERSION.SDK_INT < 23) {
            return true;
        }
        List<String> missing = missingPermissions(activity);
        if (missing.isEmpty()) {
            return true;
        }
        activity.requestPermissions(missing.toArray(new String[0]), requestCode);
        return false;
    }

    static boolean hasRequiredPermissions(Context context) {
        return missingPermissions(context).isEmpty();
    }

    static List<String> missingPermissions(Context context) {
        List<String> missing = new ArrayList<String>();
        if (context == null || Build.VERSION.SDK_INT < 23) {
            return missing;
        }
        for (String permission : requiredPermissions()) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
    }

    static JSONArray missingPermissionsJson(Context context) {
        JSONArray result = new JSONArray();
        for (String permission : missingPermissions(context)) {
            result.put(permission);
        }
        return result;
    }

    static JSONObject statusJson(Context context) {
        JSONObject status = new JSONObject();
        try {
            List<String> missing = missingPermissions(context);
            status.put("runtime_permission_ready", missing.isEmpty());
            status.put("missing_permissions", missingPermissionsJson(context));
            status.put("bluetooth_adapter_state", bluetoothAdapterState(context));
            status.put(
                "permission_model",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? "quest-nearby-devices-plus-location"
                    : "legacy-location-ble-scan"
            );
            status.put("scan_location_asserted", true);
        } catch (Exception ignored) {
        }
        return status;
    }

    static String bluetoothAdapterState(Context context) {
        BluetoothManager manager = context == null
            ? null
            : (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            return "unavailable";
        }
        try {
            switch (adapter.getState()) {
                case BluetoothAdapter.STATE_ON:
                    return "on";
                case BluetoothAdapter.STATE_OFF:
                    return "off";
                case BluetoothAdapter.STATE_TURNING_ON:
                    return "turning-on";
                case BluetoothAdapter.STATE_TURNING_OFF:
                    return "turning-off";
                default:
                    return "state-" + adapter.getState();
            }
        } catch (SecurityException error) {
            return "permission-blocked";
        }
    }

    static String join(List<String> values, String separator) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                result.append(separator);
            }
            result.append(values.get(index));
        }
        return result.toString();
    }

    private static List<String> requiredPermissions() {
        List<String> permissions = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        return permissions;
    }
}
