package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Exact shell-authorized operator bridge; it never launches an Activity.
 *
 * <p>The generated manifest protects this exported receiver with
 * {@code android.permission.DUMP}. That is the Android-supported boundary for
 * a host {@code adb shell am broadcast} operator command on this minSdk
 * bootclasspath. Do not replace it with a caller-UID check here: this
 * BroadcastReceiver API level does not expose a trustworthy sender UID.</p>
 */
public final class PolarSensorCommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !PolarSensorRuntime.ACTION_COMMAND.equals(intent.getAction())) {
            return;
        }
        PolarSensorRuntime.forApplication(context).dispatchFromCli(
            intent.getStringExtra(PolarSensorRuntime.EXTRA_COMMAND),
            intent.getStringExtra(PolarSensorRuntime.EXTRA_TOKEN)
        );
    }
}
