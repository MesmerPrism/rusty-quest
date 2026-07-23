package io.github.mesmerprism.rustyquest.fleetagent;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class FleetAgentActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setPadding(32, 32, 32, 32);
        status.setText(
                "Rusty Fleet Agent\n\n"
                        + "Inactive on ordinary launch.\n"
                        + "An app-private enrollment profile and explicit start action are required.");
        setContentView(status);
    }
}
