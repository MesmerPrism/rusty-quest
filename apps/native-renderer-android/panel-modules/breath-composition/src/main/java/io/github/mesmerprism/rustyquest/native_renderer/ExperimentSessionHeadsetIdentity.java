package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.nio.charset.StandardCharsets;

/** Actual hardware serial only. An unavailable Android API never becomes an app ID. */
final class ExperimentSessionHeadsetIdentity {
    private final SharedPreferences preferences;
    ExperimentSessionHeadsetIdentity(Context context){preferences=context.getSharedPreferences("experiment-session-headset-identity",Context.MODE_PRIVATE);}
    static boolean valid(String serial){
        return serial!=null&&serial.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
            &&serial.getBytes(StandardCharsets.UTF_8).length<=64&&!"unknown".equalsIgnoreCase(serial);
    }
    private String hardware(){
        try{String value=Build.VERSION.SDK_INT>=26?Build.getSerial():Build.SERIAL;return valid(value)?value:"";}
        catch(SecurityException unavailable){return "";}
    }
    String serial(){String actual=hardware();if(!actual.isEmpty())return actual;String stored=preferences.getString("hardware_serial","");return valid(stored)?stored:"";}
    void provision(String serial){
        if(!valid(serial))throw new SecurityException("hardware-serial-invalid");
        String actual=hardware(),stored=preferences.getString("hardware_serial","");
        if(!actual.isEmpty()&&!actual.equals(serial)||!stored.isEmpty()&&!stored.equals(serial))throw new SecurityException("hardware-serial-mismatch");
        if(!preferences.edit().putString("hardware_serial",serial).commit()||!serial.equals(preferences.getString("hardware_serial","")))
            throw new IllegalStateException("hardware-serial-not-persisted");
    }
}
