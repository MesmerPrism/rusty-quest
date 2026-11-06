package io.github.mesmerprism.rustyquest.displaystream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** ADB-facing control adapter; the app remains the authority that stops its private service. */
public final class DisplayStreamControlReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP =
        "io.github.mesmerprism.rustyquest.displaystream.action.STOP";
    private static final String TAG = "RustyDisplayStream";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_STOP.equals(intent.getAction())) {
            return;
        }
        boolean requested = context.stopService(new Intent(context, DisplayStreamService.class));
        Log.i(
            TAG,
            "RUSTY_QUEST_DISPLAY_STREAM source=media_projection status=stop_requested"
                + " service_active=" + requested);
    }
}
