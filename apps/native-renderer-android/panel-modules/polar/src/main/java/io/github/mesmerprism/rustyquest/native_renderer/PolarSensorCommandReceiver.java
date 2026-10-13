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
        if(intent.hasExtra("polar_sensor_setup_request_b64")) {
            if(intent.hasExtra(PolarSensorRuntime.EXTRA_COMMAND)||intent.hasExtra(PolarSensorRuntime.EXTRA_PROJECTED_REQUEST))return;
            try {
                String encoded=intent.getStringExtra("polar_sensor_setup_request_b64");
                if(encoded==null||encoded.length()>512)throw new IllegalArgumentException("bounded-setup-request");
                byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);
                if(bytes.length>240)throw new IllegalArgumentException("bounded-setup-request");
                org.json.JSONObject request=new org.json.JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                Class<?> owner=Class.forName("io.github.mesmerprism.rustyquest.native_renderer.BreathCompositionPanelModule");
                java.lang.reflect.Method route=owner.getDeclaredMethod("remoteSetupOperator",org.json.JSONObject.class);route.setAccessible(true);
                org.json.JSONObject receipt=(org.json.JSONObject)route.invoke(null,request);
                byte[] result=receipt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try(java.io.FileOutputStream out=context.openFileOutput("polar_sensor_setup_operator_status.json",Context.MODE_PRIVATE)){out.write(result);out.flush();}
            }catch(Exception rejected){android.util.Log.w("RQNativeRenderer","status=setup-operator-rejected",rejected);}
            return;
        }
        if (intent.hasExtra(PolarSensorRuntime.EXTRA_PROJECTED_REQUEST)) {
            if (intent.hasExtra(PolarSensorRuntime.EXTRA_COMMAND)) return;
            PolarSensorRuntime.forApplication(context).dispatchProjectedFromCli(
                intent.getStringExtra(PolarSensorRuntime.EXTRA_PROJECTED_REQUEST),
                intent.getStringExtra(PolarSensorRuntime.EXTRA_TOKEN));
            return;
        }
        PolarSensorRuntime.forApplication(context).dispatchFromCli(
            intent.getStringExtra(PolarSensorRuntime.EXTRA_COMMAND),
            intent.getStringExtra(PolarSensorRuntime.EXTRA_TOKEN)
        );
    }
}
