package io.github.mesmerprism.rustyquest.fleetagent;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class FleetAgentActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        status.setPadding(32, 32, 32, 32);
        status.setText(
                "Rusty Fleet Agent\n\n"
                        + "Inactive on ordinary launch.\n"
                        + "An app-private enrollment profile and explicit activation are required.");
        layout.addView(status);

        Button start = new Button(this);
        start.setText("Start local monitoring");
        start.setContentDescription("Start local Rusty Fleet monitoring");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestStart();
            }
        });
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop local monitoring");
        stop.setContentDescription("Stop local Rusty Fleet monitoring");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestStop();
            }
        });
        layout.addView(stop);
        setContentView(layout);
    }

    private void requestStart() {
        Intent service = new Intent(this, FleetAgentService.class)
                .setAction(FleetAgentService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        status.setText("Rusty Fleet Agent\n\nLocal monitoring start requested.");
    }

    private void requestStop() {
        Intent service = new Intent(this, FleetAgentService.class)
                .setAction(FleetAgentService.ACTION_STOP);
        startService(service);
        status.setText("Rusty Fleet Agent\n\nLocal monitoring stop requested.");
    }
}
