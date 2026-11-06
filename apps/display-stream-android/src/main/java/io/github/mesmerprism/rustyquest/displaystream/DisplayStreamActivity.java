package io.github.mesmerprism.rustyquest.displaystream;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class DisplayStreamActivity extends Activity {
    private static final String TAG = "RustyDisplayStream";
    private static final int REQUEST_CAPTURE = 9101;

    private DisplayStreamConfig config;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = DisplayStreamConfig.fromIntent(getIntent());
        buildUi();
        requestCapture();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        config = DisplayStreamConfig.fromIntent(intent);
        requestCapture();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Screen sharing was not accepted.");
            Log.i(TAG, "RUSTY_QUEST_DISPLAY_STREAM source=media_projection status=consent_rejected");
            return;
        }

        Intent service = new Intent(this, DisplayStreamService.class);
        config.putInto(service);
        service.putExtra(DisplayStreamConfig.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(DisplayStreamConfig.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        setStatus("Streaming to " + config.host + ":" + config.port + ". You may return to VR.");
        Log.i(TAG, "RUSTY_QUEST_DISPLAY_STREAM source=media_projection status=service_started");
    }

    private void requestCapture() {
        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        setStatus("Waiting for Android screen-sharing consentâ€¦");
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        status = new TextView(this);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        Button retry = new Button(this);
        retry.setText("Request screen sharing");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestCapture();
            }
        });
        Button stop = new Button(this);
        stop.setText("Stop streaming");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopService(new Intent(DisplayStreamActivity.this, DisplayStreamService.class));
                setStatus("Stop requested; waiting for producer retirement.");
            }
        });
        root.addView(status);
        root.addView(retry);
        root.addView(stop);
        setContentView(root);
    }

    private void setStatus(String value) {
        if (status != null) {
            status.setText(value);
        }
    }
}
