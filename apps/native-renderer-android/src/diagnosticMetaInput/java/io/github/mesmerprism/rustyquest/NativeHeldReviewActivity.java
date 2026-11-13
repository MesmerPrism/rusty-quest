package io.github.mesmerprism.rustyquest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Nonexported 2D viewer of one completed output pair, after the same process's native drain. */
public final class NativeHeldReviewActivity extends Activity {
    private static final AtomicBoolean SHOWN=new AtomicBoolean();
    private static volatile long retiredGeneration;
    private final Bitmap[] images=new Bitmap[2];
    public static void showAfterNativeDrain(Context context,long generation) {
        if (!NativeMetaInputDiagnostic.nativeHeldReviewEnabled() || generation<=0 || !SHOWN.compareAndSet(false,true)) return;
        try {
            JSONObject status=new JSONObject(NativeMetaInputDiagnostic.nativeHeldReviewStatus());
            if (!"Ready".equals(status.getString("state")) || status.getLong("generation")!=generation) throw new IllegalStateException("native app drain generation not ready");
            retiredGeneration=generation;
            if (!new Handler(Looper.getMainLooper()).post(()->{
                Intent intent=new Intent(context,NativeHeldReviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            })) throw new IllegalStateException("review UI dispatch failed");
        } catch(Exception e) {Log.e("RustyQuestHeldReview","review held",e);}
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            if (retiredGeneration<=0 || !NativeMetaInputDiagnostic.nativeHeldReviewEnabled()) throw new SecurityException("same-process retired token required");
            JSONObject receipt=new JSONObject(new String(read(new File(getFilesDir(),"held-review/"+retiredGeneration+"/retirement.json"),32768),java.nio.charset.StandardCharsets.UTF_8));
            if (!receipt.getBoolean("ready") || receipt.getLong("generation")!=retiredGeneration || receipt.getBoolean("live_freshness") || receipt.getBoolean("native_retirement_acknowledged")) throw new IllegalStateException("invalid app drain receipt");
            JSONObject output=receipt.getJSONObject("output");
            if(output.getJSONArray("eyes").length()!=2 || output.getLong("generation")!=retiredGeneration)throw new IllegalStateException("two matching eyes required");
            LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
            TextView label=new TextView(this);label.setText("SOURCE STOPPED / HELD OUTPUT\nCompleted mode 2 · not live media · no vendor retirement ACK");root.addView(label);
            LinearLayout eyes=new LinearLayout(this);root.addView(eyes,new LinearLayout.LayoutParams(-1,0,1));
            for(int eye=0;eye<2;eye++) {
                JSONObject e=output.getJSONArray("eyes").getJSONObject(eye);
                String fixed="presentation-witness/"+retiredGeneration+"/eye-"+eye+"-mode-2-seq-2.rgba";
                int w=e.getInt("width"),h=e.getInt("height"),format=e.getInt("vk_format");
                if(e.getInt("eye")!=eye || !fixed.equals(e.getString("file")) || w<=0 || h<=0 || w>4096 || h>4096 || (format!=37&&format!=44))throw new IllegalStateException("closed completed eye required");
                File file=new File(getFilesDir(),fixed);long expected=(long)w*h*4;
                if(e.getLong("bytes")!=expected || file.length()!=expected)throw new IllegalStateException("held output byte count changed");
                byte[] rgba=read(file,(int)expected);
                if(!NativeHeldReviewContract.hashBytes(rgba).equals(e.getString("sha256")))throw new IllegalStateException("held output bytes changed");
                int[] argb=NativeHeldReviewPixels.decode(w,h,format,rgba);
                images[eye]=Bitmap.createBitmap(argb,w,h,Bitmap.Config.ARGB_8888);
                LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
                TextView info=new TextView(this);info.setText("Eye "+eye+" · source frame "+e.getLong("source_frame")+" · timestamp "+e.getLong("source_timestamp_ns")+" · "+w+"×"+h+" · fit scaling");column.addView(info);
                ImageView view=new ImageView(this);view.setScaleType(ImageView.ScaleType.FIT_CENTER);view.setImageBitmap(images[eye]);column.addView(view,new LinearLayout.LayoutParams(-1,0,1));eyes.addView(column,new LinearLayout.LayoutParams(0,-1,1));
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);setContentView(root);
            Log.i("RustyQuestHeldReview","status=visible generation="+retiredGeneration+" sourceStopped=operator-attested liveFreshness=false eyes=2 hmdPixelProof=false");
        } catch(Exception e) {Log.e("RustyQuestHeldReview","review rejected",e);finish();}
    }
    private static byte[] read(File file,int bound)throws Exception {
        try(FileInputStream f=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[65536];int n;while((n=f.read(b))!=-1){if(out.size()+n>bound)throw new IllegalStateException("bounded own file required");out.write(b,0,n);}return out.toByteArray();
        }
    }
    @Override public void onDestroy(){super.onDestroy();for(Bitmap image:images)if(image!=null)image.recycle();}
}
