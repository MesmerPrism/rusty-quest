package io.github.mesmerprism.rustyquest.development_feedback;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.util.*;

public final class FeedbackActivity extends Activity {
    private FeedbackController controller;
    private TextView status;
    private LinearLayout panel;
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private String activityToken;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        controller=((FeedbackApplication)getApplication()).controller();activityToken=controller.activityCreated();
        ScrollView scroll=new ScrollView(this);panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(24,24,24,24);scroll.addView(panel);
        TextView title=new TextView(this);title.setText("Development feedback — trusted debug modules");title.setTextSize(22);panel.addView(title);
        status=new TextView(this);status.setTextIsSelectable(true);panel.addView(status);
        button("Refresh status",()->invoke("status"));
        button("Recreate Activity",this::recreate);
        field("input","5");button("Compute",()->invoke("compute","input"));
        field("generation","A1");field("sha256","");
        field("probe_input","5");field("probe_counter","3");
        field("probe_initial","00000000000000000000000000000000");
        field("probe_result","9");field("probe_expected","01000000000000000000000000000000");
        button("Activate received generation",()->invoke("activate","generation","sha256","probe_input","probe_counter","probe_initial","probe_result","probe_expected"));
        button("Select validated retained generation",()->invoke("select","generation"));
        setContentView(scroll);invoke("status");
    }
    private void field(String name,String initial) {
        TextView label=new TextView(this);label.setText(name);panel.addView(label);
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setText(initial);panel.addView(input);fields.put(name,input);
    }
    private void button(String title,Runnable action) {
        Button button=new Button(this);button.setText(title);button.setOnClickListener(view->action.run());panel.addView(button);
    }
    private void invoke(String method,String... names) {
        Map<String,String> args=new LinkedHashMap<>();for(String name:names)args.put(name,fields.get(name).getText().toString());
        try {
            Map<String,String> snapshot=controller.execute(method,args);StringBuilder text=new StringBuilder("This Activity: "+activityToken+"\n");
            for(Map.Entry<String,String> entry:snapshot.entrySet())text.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');status.setText(text.toString());
        }catch(Exception failure){status.setText("Refused: "+failure.getMessage()+"\nRefresh status to inspect retained state.");}
    }
}
