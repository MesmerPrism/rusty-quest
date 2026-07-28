package io.github.mesmerprism.rustyquest.lslrustfloat32loopback;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class Float32LoopbackActivity extends Activity {
    private static final String TAG = "RLSL005L_JAVA";
    static { System.loadLibrary("rusty_lsl_float32_loopback"); }
    private static native int runRustyLslFloat32Loopback();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Rusty LSL Float32 Rust-on-Quest loopback");
        setContentView(view);
        new Thread(new Runnable() {
            @Override public void run() { Float32LoopbackActivity.this.runBounded(); }
        }, "rlsl-005l-java-lifecycle").start();
    }

    private void runBounded() {
        int nativeResult = runRustyLslFloat32Loopback();
        String result = nativeResult == 1 ? "pass" : "fail";
        String marker = "{\"schema\":\"rusty.quest.lsl_rust_float32_loopback.v1\",\"result\":\"" + result
            + "\",\"java_role\":\"android-lifecycle-only\",\"rust_target\":\"aarch64-linux-android\""
            + ",\"native_result\":" + nativeResult + ",\"cleanup_owned_by_host\":true}";
        Log.i(TAG, "EFFECTIVE " + marker);
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "result.json"), false)) {
            out.write(marker.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) { Log.e(TAG, "result write failed", error); }
    }
}
