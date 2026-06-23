package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

public final class LslMulticastLockManager {
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static final Object LOCK = new Object();
    private static WifiManager.MulticastLock multicastLock;

    private LslMulticastLockManager() {
    }

    public static void acquireFromNative(Activity activity, boolean enabled) {
        if (!enabled) {
            marker("status=multicast-lock-skipped lslMulticastLockAcquired=false reason=disabled");
            return;
        }
        if (activity == null) {
            marker("status=multicast-lock-error lslMulticastLockAcquired=false reason=missing-activity");
            return;
        }
        synchronized (LOCK) {
            if (multicastLock != null && multicastLock.isHeld()) {
                marker("status=multicast-lock-already-held lslMulticastLockAcquired=true");
                return;
            }
            WifiManager wifiManager = (WifiManager) activity.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                marker("status=multicast-lock-error lslMulticastLockAcquired=false reason=missing-wifi-manager");
                return;
            }
            multicastLock = wifiManager.createMulticastLock("rusty-quest-lsl");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            marker("status=multicast-lock-acquired lslMulticastLockAcquired=true");
        }
    }

    public static void releaseFromNative() {
        synchronized (LOCK) {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                marker("status=multicast-lock-released lslMulticastLockAcquired=false");
            }
            multicastLock = null;
        }
    }

    private static void marker(String detail) {
        Log.i(TAG, MARKER_PREFIX + " channel=lsl " + sanitize(detail));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
    }
}
