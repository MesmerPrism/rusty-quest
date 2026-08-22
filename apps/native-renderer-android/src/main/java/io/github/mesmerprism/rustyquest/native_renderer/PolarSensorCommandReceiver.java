package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

/** Exact shell/self-only operator bridge; it never launches an Activity. */
public final class PolarSensorCommandReceiver extends BroadcastReceiver {
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !PolarSensorRuntime.ACTION_COMMAND.equals(intent.getAction())) {
            return;
        }
        int senderUid = getSendingUid();
        if (senderUid != Process.myUid() && senderUid != Process.SHELL_UID) {
            Log.i(
                TAG,
                MARKER_PREFIX + " channel=polar-sensor-runtime status=rejected reason=caller-not-authorized"
            );
            return;
        }
        PolarSensorRuntime.forApplication(context).dispatchFromCli(
            intent.getStringExtra(PolarSensorRuntime.EXTRA_COMMAND),
            intent.getStringExtra(PolarSensorRuntime.EXTRA_TOKEN)
        );
    }
}
