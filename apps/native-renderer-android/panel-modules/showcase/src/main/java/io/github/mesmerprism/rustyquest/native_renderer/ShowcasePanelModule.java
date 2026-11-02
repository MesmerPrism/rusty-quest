package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.KeyEvent;
import android.widget.*;
import org.json.JSONObject;

/** Dedicated settings view: every stimulus mutation enters the native owner. */
public class ShowcasePanelModule extends Activity implements PanelModule,PolarSensorPanel.Host {
    public static final String MODULE_ID="visual-showcase-controls";
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView status,network;
    private EditText name,bpm;
    private ShowcaseVisualForm visual;
    private Spinner breath,mapping,heartbeat,controllerProjection,polarProjection,profiles,sensors;
    private Switch phone;
    private boolean syncing;
    private CheckBox inverted;
    private LinearLayout root;
    private PolarSensorPanel polar;
    private boolean resumed;
    private long editRevision=-1;
    private TripleBSequence tripleB=new TripleBSequence(5000);
    static JSONObject visualPayload(JSONObject snapshot)throws Exception{return new JSONObject().put("visual",snapshot);}
    public String panelModuleId(){return MODULE_ID;}
    protected void onCreate(Bundle saved){
        super.onCreate(saved);ShowcaseNativeBridge.initialize(getApplicationContext());ShowcaseNativeBridge.attach(this);
        tripleB=new TripleBSequence(panelPressWindowMillis());
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,24,24,24);scroll.addView(root);setContentView(scroll);
        text(getApplicationInfo().loadLabel(getPackageManager()).toString(),24);button("Return to immersive",new Action(){public void run(){finish();}});
        status=text("Waiting for native owner",14);
        phone=new Switch(this);phone.setText("Allow phone control on this trusted local network");phone.setChecked(ShowcaseNativeBridge.phoneControl());
        phone.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton view,boolean checked){if(syncing)return;try{send("set-phone-control",new JSONObject().put("enabled",checked));if(checked)ShowcaseNativeBridge.requestDiscoveryPermissions(ShowcasePanelModule.this);}catch(Exception unavailable){status.setText("Phone control unavailable: "+unavailable.getMessage());}syncing=true;phone.setChecked(ShowcaseNativeBridge.phoneControl());syncing=false;}});root.addView(phone);
        network=text("Local phone connection unavailable",14);network.setTextIsSelectable(true);
        text("Breathing",18);breath=choices("automatic","controller","polar-acc");mapping=choices("volume","state");
        text("Controller projection",14);controllerProjection=choices("dynamic-axis","fixed-orientation");
        text("Polar projection",14);polarProjection=choices("xz","3d");
        inverted=new CheckBox(this);inverted.setText("Invert breathing mapping");root.addView(inverted);
        text("Heartbeat",18);heartbeat=choices("synthetic","polar","off");bpm=edit("60");bpm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        button("Apply inputs",new Action(){public void run()throws Exception{JSONObject input=new JSONObject().put("breath_source",selected(breath)).put("breath_mapping",selected(mapping)).put("heartbeat_source",selected(heartbeat)).put("synthetic_bpm",Integer.parseInt(bpm.getText().toString())).put("controller_projection",selected(controllerProjection)).put("polar_projection",selected(polarProjection)).put("inverted",inverted.isChecked());send("set-inputs",input);}});
        text("Calibration",18);for(String action:new String[]{"start","cancel","reset"})button(action+" calibration",new Action(){public void run()throws Exception{send("calibrate",new JSONObject().put("action",action));}});
        text("Visual settings",18);LinearLayout visualFields=new LinearLayout(this);visualFields.setOrientation(LinearLayout.VERTICAL);root.addView(visualFields);
        try(java.io.InputStream input=getAssets().open("showcase-visual-schema.json")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
            while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>131072)throw new IllegalArgumentException("Visual schema too large");}
            visual=new ShowcaseVisualForm(this,new JSONObject(bytes.toString("UTF-8")),visualFields);
        }catch(Exception unavailable){status.setText("Visual settings schema unavailable");}
        button("Read effective settings",new Action(){public void run(){readEffective();}});
        button("Apply visual settings",new Action(){public void run()throws Exception{send("apply-visual",visualPayload(visual.read()));}});
        text("Visual profiles",18);profiles=choices("default");button("Apply selected profile",new Action(){public void run()throws Exception{send("apply-profile",new JSONObject().put("name",selected(profiles)));readEffective();}});
        name=edit("My profile");name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        button("Save visual profile",new Action(){public void run()throws Exception{send("save-profile",new JSONObject().put("name",name.getText().toString()).put("visual",visual.read()));}});
        button("Apply named profile",new Action(){public void run()throws Exception{send("apply-profile",new JSONObject().put("name",name.getText().toString()));}});
        button("Delete named profile",new Action(){public void run()throws Exception{send("delete-profile",new JSONObject().put("name",name.getText().toString()));}});
        text("Polar device management",18);
        button("Prepare sensor permissions",new Action(){public void run(){showPolar();}});
        button("Scan Polar",new Action(){public void run()throws Exception{send("polar-scan",new JSONObject());}});
        sensors=choices("No discovered sensor");
        button("Select Polar",new Action(){public void run()throws Exception{send("polar-select",new JSONObject().put("device_id",selected(sensors)));}});
        for(String op:new String[]{"polar-connect","polar-disconnect","polar-start-acc","polar-stop-acc"})button(op.replace('-', ' '),new Action(){public void run()throws Exception{send(op,new JSONObject());}});
        main.postDelayed(new Runnable(){public void run(){readEffective();}},300);
    }
    private interface Action{void run()throws Exception;}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);root.addView(view);return view;}
    private EditText edit(String value){EditText view=new EditText(this);view.setText(value);root.addView(view);return view;}
    private Spinner choices(String...values){Spinner view=new Spinner(this);view.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values));root.addView(view);return view;}
    private String selected(Spinner view){return (String)view.getSelectedItem();}
    private void choose(Spinner spinner,String value){for(int i=0;i<spinner.getCount();i++)if(value.equals(spinner.getItemAtPosition(i))){spinner.setSelection(i);return;}}
    private void button(String label,Action action){Button button=new Button(this);button.setText(label);button.setOnClickListener(new View.OnClickListener(){public void onClick(View view){try{action.run();}catch(Exception error){status.setText("Unavailable: "+error.getMessage());}}});root.addView(button);}
    private void send(String operation,JSONObject payload)throws Exception {
        JSONObject current=ShowcaseNativeBridge.status();
        boolean edits="set-inputs".equals(operation)||"apply-visual".equals(operation)||"save-profile".equals(operation);
        JSONObject command=new JSONObject().put("schema","rusty.quest.visual_showcase.command.v1").put("product",ShowcaseNativeBridge.PRODUCT).put("epoch",current.getString("epoch")).put("expected_revision",edits&&editRevision>=0?editRevision:current.getLong("revision")).put("id",java.util.UUID.randomUUID().toString()).put("op",operation).put("payload",payload);
        JSONObject receipt=ShowcaseNativeBridge.command(command);status.setText(receipt.optString("status",receipt.optString("state","Requested"))+" · "+receipt.optString("reason",""));
        if("accepted".equals(receipt.optString("status")))readEffective();
    }
    private void readEffective(){try{
        JSONObject current=ShowcaseNativeBridge.status(),inputs=current.getJSONObject("inputs");
        editRevision=current.getLong("revision");
        choose(breath,inputs.getString("breath_source"));choose(mapping,inputs.getString("breath_mapping"));choose(heartbeat,inputs.getString("heartbeat_source"));bpm.setText(Integer.toString(inputs.getInt("synthetic_bpm")));
        choose(controllerProjection,inputs.getString("controller_projection"));choose(polarProjection,inputs.getString("polar_projection"));inverted.setChecked(inputs.getBoolean("inverted"));
        JSONObject saved=current.optJSONObject("profiles");if(saved!=null){java.util.ArrayList<String> names=new java.util.ArrayList<>();java.util.Iterator<String> keys=saved.keys();while(keys.hasNext())names.add(keys.next());if(!names.isEmpty())profiles.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));}
        if(visual!=null)visual.show(current.getJSONObject("visual"));status.setText(summary(current));
    }catch(Exception|UnsatisfiedLinkError unavailable){status.setText("Native settings unavailable: "+unavailable.getMessage());}}
    private void showPolar(){if(polar==null)polar=PolarSensorRuntime.forApplication(getApplicationContext()).attachPanel(this,this);polar.requestStartupPermissions(this);polar.onHostResume();}
    protected void onResume(){super.onResume();tripleB.reset();resumed=true;ShowcaseNativeBridge.attach(this);ShowcaseNativeBridge.requestDiscoveryPermissions(this);main.post(poll);if(polar!=null)polar.onHostResume();}
    protected void onPause(){resumed=false;tripleB.reset();main.removeCallbacks(poll);super.onPause();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);tripleB.reset();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()!=KeyEvent.KEYCODE_BUTTON_B)return super.dispatchKeyEvent(event);
        if(!resumed||!hasWindowFocus()||event.isCanceled()){tripleB.reset();return true;}
        if(event.getAction()==KeyEvent.ACTION_DOWN)tripleB.down(event.getEventTime(),event.getDownTime(),event.getRepeatCount());
        else if(event.getAction()==KeyEvent.ACTION_UP){if(tripleB.up(event.getEventTime(),event.getDownTime()))closePanelAndReturnToImmersive();}
        else tripleB.reset();
        return true;
    }
    private long panelPressWindowMillis(){
        // Same packaged property/default/bounds as SameApkPanelActionSettings.
        try(java.io.InputStream input=getAssets().open("native-app-settings.json")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>1048576)return 5000;}
            JSONObject values=new JSONObject(bytes.toString("UTF-8")).getJSONObject("values");
            JSONObject entry=values.optJSONObject("debug.rustyquest.native_renderer.control_panel.right_secondary_action.window_seconds");
            if(entry==null)return 5000;
            float seconds=Float.parseFloat(entry.getString("value").trim());
            if(Float.isNaN(seconds)||Float.isInfinite(seconds))return 5000;
            return (long)(Math.max(0.25f,Math.min(30f,seconds))*1000f);
        }catch(Exception unavailable){return 5000;}
    }
    /** Pure paired-press counter; it owns no Android or native effects. */
    static final class TripleBSequence {
        private final long windowMillis;
        private long firstDown=-1,pressedDown=-1,lastTime=-1;
        private int complete;
        TripleBSequence(long windowMillis){if(windowMillis<250||windowMillis>30000)throw new IllegalArgumentException("panel-window-bound");this.windowMillis=windowMillis;}
        void reset(){firstDown=-1;pressedDown=-1;lastTime=-1;complete=0;}
        private boolean observe(long now){if(now<0||lastTime>now){reset();return false;}lastTime=now;return true;}
        void down(long now,long downTime,int repeats){
            if(!observe(now)||repeats!=0||pressedDown>=0)return;
            if(downTime<0||downTime>now){reset();return;}
            if(firstDown>=0&&now-firstDown>windowMillis){complete=0;firstDown=-1;}
            if(firstDown<0)firstDown=now;
            pressedDown=downTime;
        }
        boolean up(long now,long downTime){
            if(!observe(now))return false;
            if(pressedDown<0)return false; // Includes the opening press's orphan key-up.
            if(downTime!=pressedDown||now-firstDown>windowMillis){reset();return false;}
            pressedDown=-1;
            if(++complete<3)return false;
            reset();return true;
        }
    }
    protected void onDestroy(){main.removeCallbacksAndMessages(null);if(polar!=null)PolarSensorRuntime.forApplication(getApplicationContext()).detachPanel(this);ShowcaseNativeBridge.detach(this);super.onDestroy();}
    public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);ShowcaseNativeBridge.discoveryPermissionResult();if(polar!=null)polar.onRequestPermissionsResult(code,permissions,results);}
    public void closePanelAndReturnToImmersive(){finish();}
    public void onPolarStreamEvent(JSONObject event){/* No retained history. */}
    private String summary(JSONObject state){JSONObject available=state.optJSONObject("availability");return "Settings revision "+state.optLong("revision")+" · "+(available!=null&&available.optBoolean("selected_polar_unavailable")?"Waiting for selected Polar input":"Inputs available");}
    private void sensorChoices(JSONObject state)throws Exception {JSONObject setup=state.optJSONObject("polar_setup");if(setup==null)return;org.json.JSONArray candidates=setup.getJSONArray("d");java.util.ArrayList<String> values=new java.util.ArrayList<>();for(int i=0;i<candidates.length();i++)values.add(candidates.getString(i));if(values.isEmpty())values.add("No discovered sensor");boolean same=sensors.getCount()==values.size();for(int i=0;same&&i<values.size();i++)same=values.get(i).equals(sensors.getItemAtPosition(i));if(!same){sensors.setAdapter(new ArrayAdapter<String>(ShowcasePanelModule.this,android.R.layout.simple_spinner_dropdown_item,values));choose(sensors,setup.optString("x"));}}
    private void networkDetails()throws Exception {JSONObject details=ShowcaseNativeBridge.connectionDetails();org.json.JSONArray urls=details.optJSONArray("gateways");StringBuilder display=new StringBuilder(ShowcaseNativeBridge.phoneControl()?"Phone control enabled · trusted LAN only\n":"Phone control disabled\n");display.append("Headset serial: ").append(details.optString("serial").isEmpty()?"unavailable":details.optString("serial"));if(urls==null||urls.length()==0)display.append("\nNo private IPv4 address available");else{display.append("\nManual Wi-Fi address:");for(int i=0;i<urls.length();i++)display.append('\n').append(urls.getString(i));}network.setText(display.toString());}
    private final Runnable poll=new Runnable(){public void run(){if(!resumed)return;try{JSONObject current=ShowcaseNativeBridge.status();status.setText(summary(current));sensorChoices(current);networkDetails();}catch(Exception ignored){}main.postDelayed(this,1000);}};
}
