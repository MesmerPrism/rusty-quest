package io.github.mesmerprism.rustyquest.media.conformance;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Lifecycle-only host for the bounded synthetic stereo conformance native harness. */
public final class MediaStreamConformanceActivity extends Activity {
  static { System.loadLibrary("rusty_quest_media_stream_conformance"); }
  private static native String runNativeConformanceReport();
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    TextView view = new TextView(this); view.setText("Media stream conformance pending"); setContentView(view);
    new Thread(() -> { final String nativeReport = runNativeConformanceReport(); final String javaReport = JavaMediaConformance.run(); String safe; try { safe = new JSONObject().put("$schema", "rusty.quest.android.media.conformance.combined.v1").put("native", new JSONObject(nativeReport == null ? "{\"result\":\"fail\",\"error\":\"native-null\"}" : nativeReport)).put("java", new JSONObject(javaReport)).toString(); } catch (Exception error) { safe = "{\"result\":\"fail\",\"error\":\"report-composition\"}"; } final String shown=safe; Log.i("MEDIA_CONFORMANCE", shown); try(FileOutputStream out=openFileOutput("conformance-report.json", MODE_PRIVATE)){out.write(shown.getBytes(StandardCharsets.UTF_8));}catch(Exception error){Log.e("MEDIA_CONFORMANCE","report write failed",error);} runOnUiThread(() -> view.setText(shown)); }, "media-stream-conformance").start();
  }
}
