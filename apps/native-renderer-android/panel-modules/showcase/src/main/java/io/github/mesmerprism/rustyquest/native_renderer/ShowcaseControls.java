package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Retained actual-owner form. Views carry presentation; native JNI owns settings. */
final class ShowcaseControls implements PolarSensorPanel.Host {
    interface Host { void closePanel(); boolean current(); }
    static final String[] TABS={"Inputs","Visuals","Profiles","Setup"};
    static final String[] VISUAL_PAGES={"Scale & radius","Particle size","Orbit geometry","Tracers","Material & transparency","Radius coupling","Visual drivers"};
    static final String[] TARGETS={"Breathing progress · input owned","Appearance / base","Particle size","Depth wave","Spin speed","Orbit radius · heartbeat owned","Orbit angle","Animation"};
    static final int BG=0xff111216,FG=0xffeef0f4,MUTED=0xffaab0ba,SURFACE=0xff23262d,ACCENT=0xffffd644;
    private final Activity activity;
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final DraftState state=new DraftState();
    private final Map<String,View> pages=new LinkedHashMap<>();
    private final Map<String,ShowcaseVisualForm> forms=new LinkedHashMap<>();
    private final Map<String,Long> pageRevisions=new LinkedHashMap<>();
    private final Map<String,Button> tabButtons=new LinkedHashMap<>();
    private Context context;
    private LinearLayout frame,body,actions;
    private TextView contextLine,outcome;
    private ScrollView scroll;
    private JSONObject schema;
    private String page="Inputs",message="Waiting for current headset settings",pendingId="",pendingOp="";
    private boolean resumed,retired,unknown,syncing;
    private boolean readbackAvailable;
    private int driverTarget;
    private long awaitingRendered;
    private PolarSensorPanel polar;
    private EditText inputBpm;
    private Runnable inputVisibility;
    private TextView lastRadius;

    ShowcaseControls(Activity activity,Host host){if(activity==null||host==null)throw new IllegalArgumentException("actual-owner-required");this.activity=activity;this.host=host;}
    static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    static JSONObject visualPayload(JSONObject snapshot)throws Exception{return new JSONObject().put("visual",copy(snapshot));}
    static final class DraftState {
        JSONObject applied,inputs,visual;
        JSONObject inputBaseline,visualBaseline;
        String epoch="";
        long inputRevision,visualRevision;
        boolean inputDirty,visualDirty;
        void observe(JSONObject next)throws Exception {
            if(!next.optBoolean("ready")||!ShowcaseNativeBridge.PRODUCT.equals(next.optString("product")))throw new IllegalArgumentException("current-showcase-status-required");
            if(applied!=null&&!epoch.equals(next.getString("epoch")))throw new IllegalStateException("native-owner-changed");
            applied=copy(next);if(inputs==null)reload();
        }
        void reload()throws Exception {epoch=applied.getString("epoch");inputs=copy(applied.getJSONObject("inputs"));visual=copy(applied.getJSONObject("visual"));inputBaseline=copy(inputs);visualBaseline=copy(visual);inputRevision=visualRevision=applied.getLong("revision");inputDirty=visualDirty=false;}
        void acknowledgeLocal(JSONObject next,String operation,long receiptRevision)throws Exception {
            observe(next);if(next.getLong("revision")!=receiptRevision)return;
            if(operation.equals("set-inputs")){inputs=copy(next.getJSONObject("inputs"));inputBaseline=copy(inputs);inputDirty=false;inputRevision=receiptRevision;}
            else if(inputBaseline.toString().equals(next.getJSONObject("inputs").toString()))inputRevision=receiptRevision;
            if(operation.equals("apply-visual")||operation.equals("apply-profile")){visual=copy(next.getJSONObject("visual"));visualBaseline=copy(visual);visualDirty=false;visualRevision=receiptRevision;}
            else if(visualBaseline.toString().equals(next.getJSONObject("visual").toString()))visualRevision=receiptRevision;
        }
        boolean conflict(boolean visualDomain){return applied==null||(visualDomain?visualRevision:inputRevision)!=applied.optLong("revision");}
        long revision(boolean visualDomain){return visualDomain?visualRevision:inputRevision;}
    }

    View attach(Context presentationContext)throws Exception {
        if(retired)throw new IllegalStateException("retired-showcase-owner");
        context=presentationContext;
        if(frame!=null)return frame;
        schema=new JSONObject(NativeAppSettingsReader.readAsset(activity,"showcase-visual-schema.json"));
        frame=column();frame.setBackgroundColor(BG);frame.setPadding(dp(18),dp(12),dp(18),dp(12));
        LinearLayout title=row();TextView heading=text(activity.getApplicationInfo().loadLabel(activity.getPackageManager()).toString(),22,FG);title.addView(heading,new LinearLayout.LayoutParams(0,-2,1));title.addView(button("Close",new Action(){public void run(){host.closePanel();}}));frame.addView(title);
        contextLine=text("Current inputs unavailable",13,MUTED);frame.addView(contextLine);
        LinearLayout tabs=row();for(final String tab:TABS){Button control=button(tab,new Action(){public void run()throws Exception{navigate(tab);}});tabButtons.put(tab,control);tabs.addView(control,new LinearLayout.LayoutParams(0,dp(48),1));}frame.addView(tabs);
        scroll=new ScrollView(context);scroll.setFillViewport(true);body=column();scroll.addView(body);frame.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        outcome=text(message,13,ACCENT);frame.addView(outcome);actions=row();frame.addView(actions);buildPage();return frame;
    }
    void resumed(){resumed=true;main.removeCallbacks(poll);main.post(poll);}
    void paused(){resumed=false;main.removeCallbacks(poll);}
    void close(){retired=true;paused();main.removeCallbacksAndMessages(null);if(polar!=null)PolarSensorRuntime.forApplication(activity).detachPanel(activity);pages.clear();forms.clear();}
    void visibility(boolean visible){if(!visible&&frame!=null)frame.clearFocus();}
    private interface Action{void run()throws Exception;}
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(context);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(context);view.setOrientation(LinearLayout.HORIZONTAL);return view;}
    private TextView text(String value,int size,int color){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(4),0,dp(4));return view;}
    private Button button(String label,final Action action){Button view=new Button(context);view.setText(label);view.setTextSize(12);view.setAllCaps(false);view.setMinHeight(dp(48));view.setTextColor(FG);GradientDrawable paint=new GradientDrawable();paint.setColor(SURFACE);paint.setCornerRadius(dp(3));paint.setStroke(dp(1),Color.rgb(80,86,98));view.setBackground(paint);view.setOnClickListener(new View.OnClickListener(){public void onClick(View ignored){try{action.run();}catch(Exception error){message=friendlyError(error);updateFrame();}}});return view;}
    private static String friendlyError(Exception error){String reason=String.valueOf(error.getMessage());if(reason.contains("settings-changed"))return "Settings changed on the headset. Read applied settings before applying your edits.";if(reason.contains("reconcile-original"))return "Waiting for the earlier action's outcome.";if(error instanceof NumberFormatException)return "Enter a valid number before applying.";if(reason.startsWith("Out of range:")||reason.startsWith("Whole number required:"))return reason;return "This action is unavailable. Read applied settings and try again.";}
    static String displayChoice(String value){if(value.equals("automatic"))return "Automatic";if(value.equals("polar-acc"))return "Polar ACC";if(value.equals("xz"))return "XZ";if(value.equals("3d"))return "3D";if(value.equals("reference-material-emulation"))return "Reference material";if(value.equals("packaged-default"))return "Built-in material";String text=value.replace('-',' ');return text.isEmpty()?text:Character.toUpperCase(text.charAt(0))+text.substring(1);}
    static ArrayAdapter<String> adapter(Context context,String[] values){return new ArrayAdapter<String>(context,android.R.layout.simple_spinner_item,values){private View style(View view,int position){if(view instanceof TextView){TextView text=(TextView)view;text.setText(displayChoice(getItem(position)));text.setTextColor(FG);text.setTextSize(14);text.setBackgroundColor(SURFACE);}return view;}public View getView(int position,View convert,ViewGroup parent){return style(super.getView(position,convert,parent),position);}public View getDropDownView(int position,View convert,ViewGroup parent){return style(super.getDropDownView(position,convert,parent),position);}};}
    private void add(LinearLayout parent,String label,Action action){parent.addView(button(label,action));}
    private LinearLayout foldout(LinearLayout parent,String label){final LinearLayout group=column();group.setVisibility(View.GONE);add(parent,"▸ "+label,new Action(){public void run(){group.setVisibility(group.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);}});parent.addView(group);return group;}
    private void navigate(String next)throws Exception {page=next;buildPage();}
    private void buildPage()throws Exception {
        if(body==null)return;body.removeAllViews();actions.removeAllViews();
        if(state.applied==null){body.addView(text("Waiting for the showcase to start",16,MUTED));return;}
        View cached=pages.get(page);if(cached==null){LinearLayout content=column();content.addView(text(page,22,FG));
            if(!isMain(page))add(content,"Back",new Action(){public void run()throws Exception{navigate(page.startsWith("Visual / ")?"Visuals":page.equals("Polar")||page.equals("Calibration")?"Inputs":"Profiles");}});
            if(page.equals("Inputs"))inputs(content);else if(page.equals("Visuals"))visuals(content);else if(page.startsWith("Visual / "))visualPage(content);else if(page.equals("Profiles"))profiles(content);else if(page.equals("Setup"))setup(content);else if(page.equals("Polar"))polar(content);else if(page.equals("Calibration"))calibration(content);else throw new IllegalArgumentException("unknown-page");
            pages.put(page,content);pageRevisions.put(page,state.applied.getLong("revision"));cached=content;
        }
        ViewGroup previous=(ViewGroup)cached.getParent();if(previous!=null)previous.removeView(cached);body.addView(cached);scroll.scrollTo(0,0);
        add(actions,"Read applied",new Action(){public void run()throws Exception{if(state.inputDirty||state.visualDirty){message="Discard unapplied edits?";actions.removeAllViews();add(actions,"Keep editing",new Action(){public void run()throws Exception{buildPage();}});add(actions,"Discard edits and read applied",new Action(){public void run()throws Exception{reload();}});}else reload();}});
        if(page.equals("Inputs"))add(actions,"Apply inputs",new Action(){public void run()throws Exception{if(state.inputs.optString("heartbeat_source").equals("synthetic"))state.inputs.put("synthetic_bpm",Integer.parseInt(inputBpm.getText().toString()));send("set-inputs",copy(state.inputs),false);}});
        if(page.startsWith("Visual / ")||page.equals("Visuals"))add(actions,"Apply visuals",new Action(){public void run()throws Exception{flushVisuals();send("apply-visual",visualPayload(state.visual),true);}});
        if(unknown)add(actions,"Acknowledge current state",new Action(){public void run()throws Exception{state.observe(ShowcaseNativeBridge.status());message="Original outcome remains unknown; current state acknowledged";pendingId="";pendingOp="";unknown=false;buildPage();}});
        updateFrame();
    }
    static boolean isMain(String page){for(String tab:TABS)if(tab.equals(page))return true;return false;}
    private void reload()throws Exception {state.observe(ShowcaseNativeBridge.status());readbackAvailable=true;state.reload();pages.clear();forms.clear();message="Applied settings read";buildPage();}
    private static String operationLabel(String op){if(op.equals("set-inputs"))return "input settings";if(op.equals("apply-visual"))return "visual settings";if(op.equals("apply-profile"))return "visual profile";if(op.equals("set-phone-control"))return "phone control";if(op.equals("save-profile"))return "profile save";if(op.equals("delete-profile"))return "profile deletion";if(op.equals("calibrate"))return "calibration";return op.replace("polar-","Polar ").replace('-',' ');}
    /** Availability is a transient overlay; successful polls never replace action outcomes. */
    static String currentReadMessage(String previous,boolean wasAvailable,String pending,boolean unknown){
        return !wasAvailable&&pending.isEmpty()&&!unknown&&previous.equals("Waiting for current headset settings")?"Applied settings":previous;
    }
    private void updateFrame(){
        if(frame==null)return;
        JSONObject s=state.applied;
        if(s!=null){
            JSONObject input=s.optJSONObject("inputs"),availability=s.optJSONObject("availability");
            contextLine.setText((readbackAvailable?"":"Last applied · ")+"Breathing: "+displayChoice(input.optString("breath_source"))+" · Heartbeat: "+displayChoice(input.optString("heartbeat_source"))+(input.optString("heartbeat_source").equals("synthetic")?" "+input.optInt("synthetic_bpm")+" BPM":"")+" · Phone control "+(ShowcaseNativeBridge.phoneControl()?"on":"off")+(availability!=null&&availability.optBoolean("selected_polar_unavailable")?" · Waiting for selected Polar input":""));
            if(awaitingRendered>0&&s.optLong("rendered_visual_revision")>=awaitingRendered){awaitingRendered=0;message="Applied visual settings";}
            if(lastRadius!=null)lastRadius.setText("Last applied sphere radius: "+(s.isNull("rendered_radius_m")?"unavailable":s.opt("rendered_radius_m")+" m"));
        }
        outcome.setText((!readbackAvailable&&s!=null?(pendingId.isEmpty()?"Waiting for current headset settings · ":"Waiting for the headset; the earlier action will not be sent again · "):"")+message+(state.inputDirty?" · Unapplied input edits":"")+(state.visualDirty?" · Unapplied visual edits":"")+((state.inputDirty&&state.conflict(false))||(state.visualDirty&&state.conflict(true))?" · Settings changed elsewhere — read applied settings":""));
        String selected=page.startsWith("Visual / ")?"Visuals":page.equals("Polar")||page.equals("Calibration")?"Inputs":page;
        for(Map.Entry<String,Button> tab:tabButtons.entrySet())tab.getValue().setTextColor(tab.getKey().equals(selected)?ACCENT:FG);
        enableEditors(body,readbackAvailable&&pendingId.isEmpty()&&!unknown);enableEditors(actions,readbackAvailable&&pendingId.isEmpty()&&!unknown);
    }
    private static boolean navigationLabel(String label){if(isMain(label)||label.equals("Close")||label.equals("Back")||label.equals("Polar setup")||label.equals("Calibration")||label.startsWith("▸")||label.equals("Read applied")||label.equals("Acknowledge current state"))return true;for(String section:VISUAL_PAGES)if(section.equals(label))return true;return false;}
    private void enableEditors(View view,boolean enabled){if(view instanceof Button)view.setEnabled(enabled||navigationLabel(((Button)view).getText().toString()));else if(view instanceof EditText||view instanceof Spinner||view instanceof CompoundButton)view.setEnabled(enabled&&!Boolean.FALSE.equals(view.getTag()));if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)enableEditors(group.getChildAt(i),enabled);}}
    private Spinner choice(LinearLayout parent,String label,final JSONObject object,final String key,String[] values,final boolean input){parent.addView(text(label,13,MUTED));Spinner spinner=new Spinner(context);spinner.setAdapter(adapter(context,values));int selected=0;for(int i=0;i<values.length;i++)if(values[i].equals(object.optString(key)))selected=i;spinner.setSelection(selected);parent.addView(spinner);spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean initial=true;public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(initial){initial=false;return;}try{object.put(key,p.getItemAtPosition(position));if(input){state.inputDirty=true;if(inputVisibility!=null)inputVisibility.run();}else state.visualDirty=true;updateFrame();}catch(Exception ignored){}}});return spinner;}
    private EditText number(LinearLayout parent,String label,final JSONObject object,final String key){parent.addView(text(label,13,MUTED));final EditText view=new EditText(context);view.setTextColor(FG);view.setSingleLine(true);view.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);view.setText(object.optString(key));parent.addView(view);view.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){state.inputDirty=true;try{object.put(key,Integer.parseInt(s.toString()));}catch(Exception ignored){}updateFrame();}public void afterTextChanged(android.text.Editable value){}});return view;}
    private void inputs(LinearLayout parent)throws Exception {
        choice(parent,"Breathing",state.inputs,"breath_source",new String[]{"automatic","controller","polar-acc"},true);
        final TextView automatic=text("Automatic: 4 s inhale · 4 s full hold · 4 s exhale · 4 s empty hold",13,MUTED);parent.addView(automatic);
        final LinearLayout mapping=column();parent.addView(mapping);choice(mapping,"Sensor mapping",state.inputs,"breath_mapping",new String[]{"volume","state"},true);
        choice(parent,"Heartbeat",state.inputs,"heartbeat_source",new String[]{"off","polar","synthetic"},true);final LinearLayout rate=column();parent.addView(rate);inputBpm=number(rate,"Synthetic rate (30–240 BPM)",state.inputs,"synthetic_bpm");
        inputVisibility=new Runnable(){public void run(){boolean auto=state.inputs.optString("breath_source").equals("automatic");mapping.setVisibility(auto?View.GONE:View.VISIBLE);automatic.setVisibility(auto?View.VISIBLE:View.GONE);rate.setVisibility(state.inputs.optString("heartbeat_source").equals("synthetic")?View.VISIBLE:View.GONE);}};inputVisibility.run();
        LinearLayout advanced=foldout(parent,"Advanced input mapping");choice(advanced,"Controller projection",state.inputs,"controller_projection",new String[]{"dynamic-axis","fixed-orientation"},true);choice(advanced,"Polar projection",state.inputs,"polar_projection",new String[]{"xz","3d"},true);
        CheckBox inverse=new CheckBox(context);inverse.setText("Invert breathing mapping");inverse.setTextColor(FG);inverse.setChecked(state.inputs.getBoolean("inverted"));advanced.addView(inverse);inverse.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton b,boolean value){try{state.inputs.put("inverted",value);state.inputDirty=true;updateFrame();}catch(Exception ignored){}}});
        LinearLayout links=row();add(links,"Polar setup",new Action(){public void run()throws Exception{navigate("Polar");}});add(links,"Calibration",new Action(){public void run()throws Exception{navigate("Calibration");}});parent.addView(links);
    }
    private void visuals(LinearLayout parent){for(final String section:VISUAL_PAGES)add(parent,section,new Action(){public void run()throws Exception{navigate("Visual / "+section);}});}
    private void visualPage(LinearLayout parent)throws Exception {
        String section=page.substring("Visual / ".length());
        if(section.equals("Visual drivers")){String[] labels=TARGETS;final Spinner targets=new Spinner(context);targets.setAdapter(adapter(context,labels));targets.setSelection(driverTarget);parent.addView(targets);targets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean initial=true;public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(initial){initial=false;return;}try{flushVisuals();driverTarget=position;pages.remove(page);forms.remove(page);buildPage();}catch(Exception invalid){message="Fix the current driver value before changing target";updateFrame();}}});}
        if(section.equals("Scale & radius")){lastRadius=text("Last applied sphere radius: unavailable",13,MUTED);parent.addView(lastRadius);}
        LinearLayout fields=column();parent.addView(fields);ShowcaseVisualForm form=new ShowcaseVisualForm(context,schema,fields);form.setMaterialPresets(state.applied.optJSONObject("material_presets"));form.showSection(state.visual,section,driverTarget,new Runnable(){public void run(){state.visualDirty=true;updateFrame();}});forms.put(page,form);
    }
    private void flushVisuals()throws Exception {for(ShowcaseVisualForm form:forms.values())form.writeInto(state.visual);}
    private void profiles(LinearLayout parent)throws Exception {
        parent.addView(text("Profiles contain visuals only. Breathing and heartbeat stay selected.",13,MUTED));final JSONObject saved=state.applied.getJSONObject("profiles");ArrayList<String> names=new ArrayList<>();java.util.Iterator<String> it=saved.keys();while(it.hasNext())names.add(it.next());final Spinner picker=new Spinner(context);picker.setAdapter(adapter(context,names.toArray(new String[0])));parent.addView(picker);
        add(parent,"Apply selected profile",new Action(){public void run()throws Exception{final String name=picker.getSelectedItem().toString();if(state.visualDirty){confirm("Replace unapplied visual edits with "+name+"?",new Action(){public void run()throws Exception{send("apply-profile",new JSONObject().put("name",name),false);}});}else send("apply-profile",new JSONObject().put("name",name),false);}});
        LinearLayout save=foldout(parent,"Save visual draft as a profile");final EditText name=new EditText(context);name.setTextColor(FG);name.setHint("Profile name");name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});save.addView(name);add(save,"Save draft",new Action(){public void run()throws Exception{flushVisuals();final String selected=name.getText().toString();final Action operation=new Action(){public void run()throws Exception{send("save-profile",new JSONObject().put("name",selected).put("visual",copy(state.visual)),true);}};if(saved.has(selected))confirm("Replace saved profile "+selected+"?",operation);else operation.run();}});
        LinearLayout remove=foldout(parent,"Delete saved profile");add(remove,"Delete selected saved profile",new Action(){public void run()throws Exception{final String selected=picker.getSelectedItem().toString();if(selected.equals("default"))throw new IllegalArgumentException("Default is immutable");confirm("Delete "+selected+"?",new Action(){public void run()throws Exception{send("delete-profile",new JSONObject().put("name",selected),false);}});}});
    }
    private void confirm(String prompt,final Action action){message=prompt;actions.removeAllViews();add(actions,"Cancel",new Action(){public void run()throws Exception{buildPage();}});add(actions,"Confirm",action);updateFrame();}
    private void setup(LinearLayout parent)throws Exception {
        final CheckBox consent=new CheckBox(context);consent.setText("Allow phone control on this trusted local network");consent.setTextColor(FG);consent.setChecked(ShowcaseNativeBridge.phoneControl());parent.addView(consent);consent.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton view,boolean enabled){if(syncing)return;try{send("set-phone-control",new JSONObject().put("enabled",enabled),false);if(enabled)ShowcaseNativeBridge.requestDiscoveryPermissions(activity);}catch(Exception error){message=friendlyError(error);updateFrame();}finally{syncing=true;consent.setChecked(ShowcaseNativeBridge.phoneControl());syncing=false;}}});
        JSONObject details=ShowcaseNativeBridge.connectionDetails();parent.addView(text("Phone and headset must share Wi-Fi or a phone hotspot.",14,FG));parent.addView(text("Headset serial: "+(details.optString("serial").isEmpty()?"unavailable":details.optString("serial")),14,FG));JSONArray urls=details.optJSONArray("gateways");if(urls==null||urls.length()==0)parent.addView(text("No private IPv4 address available",14,MUTED));else for(int i=0;i<urls.length();i++)parent.addView(text(urls.getString(i),14,FG));
        LinearLayout info=foldout(parent,"Connection details");info.addView(text("Local HTTP authenticates commands but does not encrypt this LAN leg. Bluetooth reads identity and endpoint only. The headset remains settings authority.",13,MUTED));
    }
    private void polar(LinearLayout parent)throws Exception {
        JSONObject setup=state.applied.optJSONObject("polar_setup");parent.addView(text(setup==null?"Polar setup is waiting for the headset":(setup.optBoolean("p")?"Permissions ready":"Permissions needed")+" · "+(setup.optBoolean("b")?"Bluetooth on":"Bluetooth off")+" · "+(setup.optBoolean("c")?"Sensor connected":"Sensor disconnected")+" · "+(setup.optBoolean("a")?"Motion data fresh":"Waiting for motion data"),14,FG));
        add(parent,"Prepare permissions",new Action(){public void run(){if(polar==null)polar=PolarSensorRuntime.forApplication(activity).attachPanel(activity,ShowcaseControls.this);polar.requestStartupPermissions(activity);polar.onHostResume();}});add(parent,"Scan for Polar",new Action(){public void run()throws Exception{send("polar-scan",new JSONObject(),false);}});
        final Spinner sensors=new Spinner(context);ArrayList<String> names=new ArrayList<>();JSONArray found=setup==null?null:setup.optJSONArray("d");if(found!=null)for(int i=0;i<found.length();i++)names.add(found.getString(i));if(names.isEmpty())names.add("No discovered sensor");sensors.setAdapter(adapter(context,names.toArray(new String[0])));parent.addView(sensors);
        add(parent,"Select sensor",new Action(){public void run()throws Exception{send("polar-select",new JSONObject().put("device_id",sensors.getSelectedItem().toString()),false);}});
        for(final String op:new String[]{"polar-connect","polar-disconnect"})add(parent,op.equals("polar-connect")?"Connect Polar":"Disconnect Polar",new Action(){public void run()throws Exception{send(op,new JSONObject(),false);}});
        LinearLayout streams=foldout(parent,"ACC stream management");for(final String op:new String[]{"polar-start-acc","polar-stop-acc"})add(streams,op.equals("polar-start-acc")?"Start ACC":"Stop ACC",new Action(){public void run()throws Exception{send(op,new JSONObject(),false);}});
    }
    private void calibration(LinearLayout parent)throws Exception {
        String source=state.applied.getJSONObject("inputs").getString("breath_source");parent.addView(text("Applied target: "+source,16,FG));if(source.equals("automatic")){parent.addView(text("Automatic breathing has no sensor calibration.",14,MUTED));return;}if(state.inputDirty){parent.addView(text("Apply input edits before calibrating their target.",14,MUTED));return;}
        for(final String action:new String[]{"start","cancel"})add(parent,action.equals("start")?"Start calibration":"Cancel calibration",new Action(){public void run()throws Exception{send("calibrate",new JSONObject().put("action",action),false);}});
        LinearLayout reset=foldout(parent,"Reset calibration");add(reset,"Reset selected source calibration",new Action(){public void run()throws Exception{send("calibrate",new JSONObject().put("action","reset"),false);}});
    }
    private void send(String op,JSONObject payload,boolean visualDomain)throws Exception {
        if(retired||!host.current()||!resumed)throw new IllegalStateException("owner-not-current");
        if(!pendingId.isEmpty()||unknown)throw new IllegalStateException("reconcile-original-outcome-first");
        boolean edits=op.equals("apply-visual")||op.equals("set-inputs")||op.equals("save-profile");
        if(edits&&state.conflict(visualDomain))throw new IllegalStateException("settings-changed-elsewhere-read-applied");
        JSONObject current=ShowcaseNativeBridge.status();state.observe(current);readbackAvailable=true;
        long revision=edits?state.revision(visualDomain):pageRevisions.containsKey(page)?pageRevisions.get(page):current.getLong("revision");
        if(revision!=current.getLong("revision"))throw new IllegalStateException("settings-changed-elsewhere-read-applied");
        String id=java.util.UUID.randomUUID().toString();JSONObject command=new JSONObject().put("schema","rusty.quest.visual_showcase.command.v1").put("product",ShowcaseNativeBridge.PRODUCT).put("epoch",state.epoch).put("expected_revision",revision).put("id",id).put("op",op).put("payload",payload);
        pendingId=id;pendingOp=op;message="Headset applying "+operationLabel(op);updateFrame();
        try{settle(ShowcaseNativeBridge.command(command));}catch(Exception failure){unknown=true;message="The earlier action could not be confirmed. Read applied settings to check the headset.";buildPage();throw failure;}
    }
    private void settle(JSONObject receipt)throws Exception {
        String result=receipt.optString("status");if(result.equals("pending")){message="Waiting for the headset to confirm "+operationLabel(pendingOp);return;}if(result.equals("unknown")){unknown=true;message="The earlier action could not be confirmed. It will not be sent again.";buildPage();return;}
        if(result.equals("accepted")){
            JSONObject current=ShowcaseNativeBridge.status();long confirmed=receipt.optLong("revision");
            state.acknowledgeLocal(current,pendingOp,confirmed);readbackAvailable=true;
            if(current.getLong("revision")==confirmed){
                for(String cached:pages.keySet())pageRevisions.put(cached,confirmed);
                if(pendingOp.equals("set-inputs")){pages.remove("Inputs");pages.remove("Calibration");}
                if(pendingOp.equals("apply-visual")||pendingOp.equals("apply-profile")){
                    java.util.Iterator<String> keys=pages.keySet().iterator();while(keys.hasNext())if(keys.next().startsWith("Visual"))keys.remove();forms.clear();lastRadius=null;pages.remove("Profiles");
                }
                if(pendingOp.equals("save-profile")||pendingOp.equals("delete-profile"))pages.remove("Profiles");
                if(pendingOp.startsWith("polar-"))pages.remove("Polar");
                if(pendingOp.equals("calibrate"))pages.remove("Calibration");
                if(pendingOp.equals("set-phone-control"))pages.remove("Setup");
                message="Applied "+operationLabel(pendingOp);
                if((pendingOp.equals("apply-visual")||pendingOp.equals("apply-profile"))&&current.optLong("rendered_visual_revision")<confirmed){awaitingRendered=confirmed;message="Headset applying visual changes…";}
            }else message="Settings changed on the headset. Read applied settings to continue.";
        }
        else message="The headset could not apply "+operationLabel(pendingOp)+". Check the edited values and read applied settings.";pendingId="";pendingOp="";unknown=false;buildPage();
    }
    public void closePanelAndReturnToImmersive(){host.closePanel();}
    public void onPolarStreamEvent(JSONObject event){}
    void permissions(int request,String[] permissions,int[] results){ShowcaseNativeBridge.discoveryPermissionResult();if(polar!=null)polar.onRequestPermissionsResult(request,permissions,results);}
    private final Runnable poll=new Runnable(){public void run(){if(!resumed||retired)return;try{boolean first=state.applied==null;JSONObject status=ShowcaseNativeBridge.status();state.observe(status);message=currentReadMessage(message,readbackAvailable,pendingId,unknown);readbackAvailable=true;if(first&&frame!=null)buildPage();if(!pendingId.isEmpty())settle(new JSONObject(ShowcaseNativeBridge.nativeShowcaseReceipt(pendingId)));updateFrame();}catch(Exception|UnsatisfiedLinkError unavailable){readbackAvailable=false;updateFrame();}main.postDelayed(this,500);}};
}
