package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

final class BleRendezvousPermissions {
    private BleRendezvousPermissions() {
    }

    static String[] missing(Context context, String mode) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfMissing(context, missing, Manifest.permission.BLUETOOTH_CONNECT);
            if (BleRendezvousConfig.MODE_SERVER.equals(mode)) {
                addIfMissing(context, missing, Manifest.permission.BLUETOOTH_ADVERTISE);
            } else {
                addIfMissing(context, missing, Manifest.permission.BLUETOOTH_SCAN);
            }
        } else {
            addIfMissing(context, missing, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(context, missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing.toArray(new String[0]);
    }

    static boolean granted(Context context, String mode) {
        return missing(context, mode).length == 0;
    }

    private static void addIfMissing(Context context, List<String> missing, String permission) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }
}
