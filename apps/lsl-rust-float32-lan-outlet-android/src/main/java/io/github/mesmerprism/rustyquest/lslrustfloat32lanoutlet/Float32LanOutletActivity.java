package io.github.mesmerprism.rustyquest.lslrustfloat32lanoutlet;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class Float32LanOutletActivity extends Activity {
    private static final String TAG = "RLSLP70_JAVA";
    static { System.loadLibrary("rusty_lsl_float32_lan_outlet"); }
    private static native int runRustyLslFloat32LanOutlet();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Rusty LSL LAN outlet qualification");
        setContentView(view);
        new Thread(new Runnable() {
            @Override public void run() { runBounded(); }
        }, "rlsl-p70-java-lifecycle").start();
    }

    private void runBounded() {
        int nativeResult = runRustyLslFloat32LanOutlet();
        String result = nativeResult == 1 ? "pass" : "fail";
        String marker = "{\"schema\":\"rusty.quest.lsl_rust_float32_lan_outlet.v1\",\"result\":\""
            + result + "\",\"java_role\":\"android-lifecycle-only\",\"rust_target\":\"aarch64-linux-android\""
            + ",\"native_result\":" + nativeResult + ",\"cleanup_owned_by_host\":true}";
        Log.i(TAG, "EFFECTIVE " + marker);
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "result.json"), false)) {
            out.write(marker.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) { Log.e(TAG, "result write failed", error); }
    }
}
