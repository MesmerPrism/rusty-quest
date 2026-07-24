package io.github.mesmerprism.rustyquest.fleetagent;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import org.json.JSONException;
import org.json.JSONObject;

final class FleetAgentObservation {
    private FleetAgentObservation() {
    }

    static String capture(Context context) throws JSONException {
        BatteryManager manager = context.getSystemService(BatteryManager.class);
        int percent = manager == null
                ? -1
                : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        Intent battery = context.registerReceiver(
                null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (percent < 0 || percent > 100) {
            percent = batteryPercent(battery);
        }
        boolean charging = false;
        if (battery != null) {
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        }
        JSONObject snapshot = new JSONObject();
        snapshot.put("battery_percent", percent);
        snapshot.put("charging", charging);
        snapshot.put("agent_lifecycle", "background");
        snapshot.put("participating_application", JSONObject.NULL);
        return snapshot.toString();
    }

    private static int batteryPercent(Intent battery) {
        if (battery == null) {
            return 0;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (scale <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round(level * 100.0f / scale)));
    }
}
