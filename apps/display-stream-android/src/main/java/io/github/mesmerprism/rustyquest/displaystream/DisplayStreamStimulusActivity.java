package io.github.mesmerprism.rustyquest.displaystream;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/** Deterministic moving image used by host-side stream freshness tests. */
public final class DisplayStreamStimulusActivity extends Activity {
    private static final String TAG = "RustyStreamStimulus";
    private static final int DEFAULT_DURATION_SECONDS = 12;
    private static final int DEFAULT_PERIOD_MS = 250;
    private static final int MIN_DURATION_SECONDS = 2;
    private static final int MAX_DURATION_SECONDS = 120;
    private static final int MIN_PERIOD_MS = 100;
    private static final int MAX_PERIOD_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StimulusView stimulusView;
    private long startedElapsedMs;
    private int durationSeconds;
    private int periodMs;
    private int sequence;
    private String runId;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs;
            if (elapsedMs >= durationSeconds * 1000L) {
                Log.i(TAG, marker("complete"));
                finish();
                return;
            }
            sequence += 1;
            stimulusView.invalidate();
            handler.postDelayed(this, periodMs);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        durationSeconds = clamp(
            getIntent().getIntExtra("duration_seconds", DEFAULT_DURATION_SECONDS),
            MIN_DURATION_SECONDS,
            MAX_DURATION_SECONDS);
        periodMs = clamp(
            getIntent().getIntExtra("period_ms", DEFAULT_PERIOD_MS),
            MIN_PERIOD_MS,
            MAX_PERIOD_MS);
        runId = getIntent().getStringExtra("run_id");
        if (runId == null || runId.trim().isEmpty()) {
            runId = "unspecified";
        } else if (runId.length() > 80) {
            runId = runId.substring(0, 80);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stimulusView = new StimulusView();
        setContentView(stimulusView);
        startedElapsedMs = SystemClock.elapsedRealtime();
        Log.i(TAG, marker("started"));
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private String marker(String status) {
        return "RUSTY_DISPLAY_STREAM_STIMULUS schema=rusty.quest.display_stream.stimulus.v1"
            + " status=" + status
            + " run_id=" + runId
            + " sequence=" + sequence
            + " period_ms=" + periodMs
            + " duration_seconds=" + durationSeconds
            + " elapsed_ms=" + Math.max(0L, SystemClock.elapsedRealtime() - startedElapsedMs);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class StimulusView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StimulusView() {
            super(DisplayStreamStimulusActivity.this);
            paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = Math.max(1, getWidth());
            int height = Math.max(1, getHeight());
            boolean inverse = (sequence & 1) != 0;
            int background = inverse ? Color.WHITE : Color.BLACK;
            int foreground = inverse ? Color.BLACK : Color.WHITE;
            canvas.drawColor(background);

            int cell = Math.max(32, Math.min(width, height) / 8);
            paint.setColor(foreground);
            for (int y = 0; y < height; y += cell) {
                for (int x = 0; x < width; x += cell) {
                    if ((((x / cell) + (y / cell) + sequence) & 1) == 0) {
                        canvas.drawRect(x, y, Math.min(width, x + cell), Math.min(height, y + cell), paint);
                    }
                }
            }

            int barWidth = Math.max(cell, width / 5);
            int travel = Math.max(1, width - barWidth);
            int barLeft = (int) ((sequence * (long) Math.max(1, cell / 2)) % travel);
            paint.setColor(sequence % 3 == 0 ? Color.RED : sequence % 3 == 1 ? Color.GREEN : Color.BLUE);
            canvas.drawRect(barLeft, 0, Math.min(width, barLeft + barWidth), height, paint);

            paint.setColor(foreground);
            paint.setTextSize(Math.max(36.0f, Math.min(width, height) / 10.0f));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText("FRAME " + sequence, cell / 2.0f, height / 2.0f, paint);
            paint.setTextSize(Math.max(22.0f, Math.min(width, height) / 24.0f));
            canvas.drawText("UNIX_MS " + System.currentTimeMillis(), cell / 2.0f,
                height / 2.0f + paint.getTextSize() * 2.0f, paint);
            canvas.drawText("RUN " + runId, cell / 2.0f,
                height / 2.0f + paint.getTextSize() * 3.5f, paint);
        }
    }
}
