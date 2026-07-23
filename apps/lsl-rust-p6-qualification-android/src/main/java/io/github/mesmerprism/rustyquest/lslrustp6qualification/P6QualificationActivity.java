package io.github.mesmerprism.rustyquest.lslrustp6qualification;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class P6QualificationActivity extends Activity {
    private static final String TAG = "RLSLP6_JAVA";
    static { System.loadLibrary("rusty_lsl_p6_qualification"); }
    private static native int runQualification();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Rusty LSL P6 single-Quest qualification");
        setContentView(view);
        new Thread(new Runnable() {
            @Override public void run() { P6QualificationActivity.this.runBounded(); }
        }, "rlsl-p6-java-lifecycle").start();
    }

    private void runBounded() {
        int nativeResult = runQualification();
        String result = nativeResult == 1 ? "pass" : "fail";
        String marker = "{\"schema\":\"rusty.quest.lsl_rust_p6_qualification_result.v1\",\"result\":\"" + result
            + "\",\"java_role\":\"android-lifecycle-and-result-projection-only\",\"rust_target\":\"aarch64-linux-android\""
            + ",\"native_result\":" + nativeResult + ",\"loopback_only\":true,\"host_cleanup_required\":true}";
        Log.i(TAG, "EFFECTIVE " + marker);
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "result.json"), false)) {
            out.write(marker.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) { Log.e(TAG, "result write failed", error); }
    }
}
