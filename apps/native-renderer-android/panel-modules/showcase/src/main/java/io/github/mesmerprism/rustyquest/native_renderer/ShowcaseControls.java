package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
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
    interface Host {
        void closePanel();
        boolean current();
        EditText createEditor(Context context);
        Spinner createSpinner(Context context,String label);
    }
    static final String[] TABS={"Inputs","Visuals","Profiles","Setup"};
    static final String[] VISUAL_PAGES={"Scale & radius","Particle size","Orbit geometry","Tracers","Material & transparency","Radius coupling","Visual drivers"};
    static final String[] TARGETS={"Breathing progress · input owned","Appearance / base","Particle size","Depth wave","Spin speed","Orbit radius · heartbeat owned","Orbit angle","Animation"};
    static final int BG = 0xff090a0c, SURFACE = 0xff22262d, FG = 0xfff7f7f8,
            MUTED = 0xffbdc3cc, ACCENT = 0xff8bd1ff, SELECTED = 0xff17405c,
            FIELD = 0xff171a20, WARNING = 0xffffca79;
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
    private CheckBox remoteToggle;
    private static final String REMOTE_LABEL="Allow remote control on trusted Wi-Fi";
    private ScrollView scroll;
    private JSONObject schema;
    private String page="Inputs",message="Waiting for current headset settings",pendingId="",pendingOp="";
    private boolean resumed,retired,unknown,syncing;
    private boolean readbackAvailable;
    private int driverTarget;
    private long awaitingRendered;
    private PolarSensorPanel polar;
    private NumericField inputBpm;
    private LinearLayout advancedInputs;
    private TextView lastRadius, calibrationStatus;
    private ProgressBar calibrationProgress;

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
        boolean refreshCleanDomain(boolean visualDomain) throws Exception {
            if (visualDomain ? visualDirty : inputDirty) return false;
            JSONObject latest=applied.getJSONObject(visualDomain?"visual":"inputs");
            JSONObject baseline=visualDomain?visualBaseline:inputBaseline;
            boolean changed=!latest.toString().equals(baseline.toString());
            long revision=applied.getLong("revision");
            if (visualDomain) {
                if (changed) {
                    visual=copy(latest);
                    visualBaseline=copy(latest);
                }
                visualRevision=revision;
            } else {
                if (changed) {
                    inputs=copy(latest);
                    inputBaseline=copy(latest);
                }
                inputRevision=revision;
            }
            return changed;
        }

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
        frame=column();frame.setBackgroundColor(BG);frame.setPadding(dp(22),dp(16),dp(22),dp(14));
        LinearLayout title=row();TextView heading=text(activity.getApplicationInfo().loadLabel(activity.getPackageManager()).toString(),24,FG);heading.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);title.addView(heading,new LinearLayout.LayoutParams(0,-2,1));title.addView(button("Close",new Action(){public void run(){host.closePanel();}}));frame.addView(title);
        addRemoteHeader();
        LinearLayout tabs=row();for(final String tab:TABS){Button control=button(tab,new Action(){public void run()throws Exception{navigate(tab);}});tabButtons.put(tab,control);tabs.addView(control,tabLayout());}frame.addView(tabs);
        scroll=new ScrollView(context);scroll.setFillViewport(true);body=column();body.setPadding(0,dp(8),0,dp(8));scroll.addView(body);frame.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        outcome=text(message,13,MUTED);frame.addView(outcome);actions=row();frame.addView(actions);buildPage();return frame;
    }
    private void addRemoteHeader() {
        LinearLayout status=column();
        status.addView(text("Applied settings",15,FG));
        contextLine=text("Waiting for headset settings",13,MUTED);
        status.addView(contextLine);
        LinearLayout remote=column();
        remote.addView(text(REMOTE_LABEL,13,FG));
        remoteToggle=toggle(context,REMOTE_LABEL,ShowcaseNativeBridge.phoneControl());
        remoteToggle.setEnabled(false);
        remote.addView(remoteToggle,new LinearLayout.LayoutParams(-1,-2));
        onToggle(remoteToggle,REMOTE_LABEL,new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button,boolean enabled) {
                if (syncing) return;
                try {
                    send("set-phone-control",new JSONObject().put("enabled",enabled),false);
                    if (enabled) ShowcaseNativeBridge.requestDiscoveryPermissions(activity);
                } catch (Exception error) {
                    message=friendlyError(error);
                } finally {
                    updateFrame();
                }
            }
        });
        addControlRow(frame,status,remote,235);
    }

    void resumed(){resumed=true;main.removeCallbacks(poll);main.post(poll);}
    void paused(){resumed=false;main.removeCallbacks(poll);}
    void close(){retired=true;paused();main.removeCallbacksAndMessages(null);if(polar!=null)PolarSensorRuntime.forApplication(activity).detachPanel(activity);pages.clear();forms.clear();}
    void visibility(boolean visible){if(!visible&&frame!=null)frame.clearFocus();}
    private interface Action{void run()throws Exception;}
    private int dp(int value){return dp(context,value);}
    static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}

    private LinearLayout.LayoutParams tabLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,dp(48),1);
        params.setMargins(0,dp(6),dp(6),dp(6));
        return params;
    }

    static void paintControl(TextView view, int normal, int active) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(view.getContext(),6));
        background.setColor(new ColorStateList(new int[][]{
            {-android.R.attr.state_enabled}, {android.R.attr.state_pressed},
            {android.R.attr.state_selected}, {android.R.attr.state_focused}, {}
        },new int[]{FIELD,SELECTED,active,active,normal}));
        view.setTag("native-page-flat-background");
        view.setBackground(background);
    }

    static void styleButton(Button button) {
        button.setTextColor(FG);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(button.getContext(),44));
        button.setPadding(dp(button.getContext(),14),0,dp(button.getContext(),14),0);
        paintControl(button,SURFACE,SELECTED);
    }

    static void styleEditor(EditText editor) {
        editor.setTextColor(FG);
        editor.setHintTextColor(MUTED);
        editor.setTextSize(16);
        editor.setMinHeight(dp(editor.getContext(),44));
        editor.setPadding(dp(editor.getContext(),12),0,dp(editor.getContext(),12),0);
        paintControl(editor,FIELD,SELECTED);
    }

    static void addSpaced(LinearLayout parent, View view) {
        Context context = parent.getContext();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            parent.getOrientation()==LinearLayout.HORIZONTAL ? -2 : -1,-2);
        params.setMargins(0,dp(context,3),dp(context,6),dp(context,3));
        parent.addView(view,params);
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(context);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(context);view.setOrientation(LinearLayout.HORIZONTAL);return view;}
    private TextView text(String value,int size,int color){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(4),0,dp(4));return view;}
    private Button button(String label,final Action action){Button view=new Button(context);view.setText(label);styleButton(view);if(label.equals("Apply visuals")||label.equals("Confirm"))paintControl(view,SELECTED,SURFACE);view.setOnClickListener(new View.OnClickListener(){public void onClick(View ignored){try{action.run();}catch(Exception error){message=friendlyError(error);updateFrame();}}});return view;}
    private static String friendlyError(Exception error){String reason=String.valueOf(error.getMessage());if(reason.contains("settings-changed"))return "Settings changed on the headset. Read applied settings before applying your edits.";if(reason.contains("reconcile-original"))return "Waiting for the earlier action's outcome.";if(error instanceof NumberFormatException)return "Enter a valid number before applying.";if(reason.startsWith("Out of range:")||reason.startsWith("Whole number required:"))return reason;return "This action is unavailable. Read applied settings and try again.";}
    static String displayChoice(String value){if(value.equals("automatic"))return "Automatic";if(value.equals("polar-acc"))return "Polar ACC";if(value.equals("xz"))return "XZ";if(value.equals("3d"))return "3D";if(value.equals("sphere-radius-percent"))return "% of sphere radius";if(value.equals("world-meters"))return "World size (m)";if(value.equals("legacy-payload-envelope"))return "Original size behavior";if(value.equals("reference-material-emulation"))return "Reference material";if(value.equals("packaged-default"))return "Built-in material";String text=value.replace('-',' ');return text.isEmpty()?text:Character.toUpperCase(text.charAt(0))+text.substring(1);}
    static ArrayAdapter<String> adapter(Context context,String[] values) {
        return new ArrayAdapter<String>(context,android.R.layout.simple_spinner_item,values) {
            private void style(TextView text) {
                text.setTextColor(FG);
                text.setTextSize(15);
                text.setGravity(Gravity.CENTER_VERTICAL);
                text.setMinHeight(dp(context,44));
                text.setPadding(dp(context,12),0,dp(context,12),0);
                text.setSingleLine(true);
                text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }

            @Override public View getView(int position,View convert,ViewGroup parent) {
                LinearLayout display=new LinearLayout(context);
                display.setOrientation(LinearLayout.HORIZONTAL);
                display.setGravity(Gravity.CENTER_VERTICAL);
                display.setTag("native-page-explicit-chevron");
                GradientDrawable surface=new GradientDrawable();
                surface.setColor(SURFACE);
                surface.setCornerRadius(dp(context,6));
                display.setBackground(surface);
                TextView value=new TextView(context);
                style(value);
                value.setText(displayChoice(getItem(position)));
                display.addView(value,new LinearLayout.LayoutParams(0,dp(context,44),1));
                TextView chevron=new TextView(context);
                style(chevron);
                chevron.setText("▾");
                chevron.setTextColor(ACCENT);
                chevron.setTextSize(24);
                chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                display.addView(chevron,new LinearLayout.LayoutParams(-2,dp(context,44)));
                return display;
            }

            @Override public View getDropDownView(int position,View convert,ViewGroup parent) {
                TextView option=new TextView(context);
                style(option);
                option.setText(displayChoice(getItem(position)));
                option.setHeight(dp(context,44));
                option.setBackgroundColor(SURFACE);
                return option;
            }
        };
    }

    static CheckBox toggle(Context context,String label,boolean checked) {
        CheckBox toggle=new CheckBox(context);
        toggle.setButtonDrawable((android.graphics.drawable.Drawable)null);
        styleButton(toggle);
        toggle.setChecked(checked);
        updateToggleLabel(toggle,label);
        return toggle;
    }

    static void onToggle(CheckBox toggle,String label,CompoundButton.OnCheckedChangeListener changed) {
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button,boolean checked) {
                try {
                    changed.onCheckedChanged(button,checked);
                } finally {
                    updateToggleLabel(toggle,label);
                }
            }
        });
    }

    private static void updateToggleLabel(CheckBox toggle,String label) {
        boolean checked=toggle.isChecked();
        toggle.setText(checked?"On":"Off");
        toggle.setSelected(checked);
        toggle.setTextColor(checked?ACCENT:FG);
        toggle.setContentDescription(label+", "+toggle.getText());
    }

    private void add(LinearLayout parent,String label,Action action){addSpaced(parent,button(label,action));}
    private LinearLayout foldout(LinearLayout parent,String label) {
        return foldoutGroup(parent,label);
    }

    static LinearLayout foldoutGroup(LinearLayout parent,String label) {
        return foldoutGroup(parent,label,false);
    }

    private static LinearLayout foldoutGroup(LinearLayout parent,String label,boolean initiallyExpanded) {
        Context context=parent.getContext();
        LinearLayout group=new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(context,12),0,0,dp(context,4));
        group.setVisibility(initiallyExpanded?View.VISIBLE:View.GONE);
        Button toggle=new Button(context);
        styleButton(toggle);
        toggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        toggle.setText((initiallyExpanded?"▾ ":"▸ ")+label);
        toggle.setSelected(initiallyExpanded);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                boolean expanded=group.getVisibility()!=View.VISIBLE;
                if (!expanded && group.findFocus() instanceof EditText) group.findFocus().clearFocus();
                group.setVisibility(expanded ? View.VISIBLE : View.GONE);
                toggle.setText((expanded ? "▾ " : "▸ ")+label);
                toggle.setSelected(expanded);
            }
        });
        addSpaced(parent,toggle);
        parent.addView(group);
        return group;
    }

    static void addControlRow(LinearLayout parent,View label,View control,int width) {
        Context context=parent.getContext();
        LinearLayout line=new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(context,12),dp(context,5),dp(context,8),dp(context,5));
        GradientDrawable fill=new GradientDrawable();
        fill.setColor(0xff111318);
        fill.setCornerRadius(dp(context,6));
        line.setBackground(fill);
        line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        line.addView(control,new LinearLayout.LayoutParams(dp(context,width),-2));
        LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(-1,-2);
        row.setMargins(0,dp(context,2),0,dp(context,2));
        parent.addView(line,row);
    }

    private void navigate(String next)throws Exception {
        View focused = body.findFocus();
        if (focused instanceof EditText) focused.clearFocus();
        page=next;
        buildPage();
    }
    private void buildPage()throws Exception {
        if(body==null)return;body.removeAllViews();actions.removeAllViews();
        if(state.applied==null){body.addView(text("Waiting for the showcase to start",16,MUTED));return;}
        View cached=pages.get(page);
        if (cached==null) {
            LinearLayout content=column();
            LinearLayout pageHeading=row();
            pageHeading.setGravity(Gravity.CENTER_VERTICAL);
            TextView pageTitle=text(page.startsWith("Visual / ")?page.substring(9):page,22,FG);
            pageHeading.addView(pageTitle,new LinearLayout.LayoutParams(0,-2,1));
            if (!isMain(page)) {
                add(pageHeading,"Back",new Action() {
                    @Override public void run() throws Exception {
                        navigate(page.startsWith("Visual / ")?"Visuals":
                            page.equals("Polar")||page.equals("Calibration")?"Inputs":"Profiles");
                    }
                });
            }
            content.addView(pageHeading);
            if(page.equals("Inputs"))inputs(content);else if(page.equals("Visuals"))visuals(content);else if(page.startsWith("Visual / "))visualPage(content);else if(page.equals("Profiles"))profiles(content);else if(page.equals("Setup"))setup(content);else if(page.equals("Polar"))polar(content);else if(page.equals("Calibration"))calibration(content);else throw new IllegalArgumentException("unknown-page");
            pages.put(page,content);pageRevisions.put(page,state.applied.getLong("revision"));cached=content;
        }
        ViewGroup previous=(ViewGroup)cached.getParent();if(previous!=null)previous.removeView(cached);body.addView(cached);scroll.scrollTo(0,0);
        if(!page.equals("Inputs")&&!page.equals("Polar")&&!page.equals("Calibration"))add(actions,"Read applied",new Action(){public void run()throws Exception{if(state.inputDirty||state.visualDirty){message="Discard unapplied edits?";actions.removeAllViews();add(actions,"Keep editing",new Action(){public void run()throws Exception{buildPage();}});add(actions,"Discard edits and read applied",new Action(){public void run()throws Exception{reload();}});}else reload();}});
        if (!unknown && (page.equals("Visuals") || page.startsWith("Visual / "))) {
            actions.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
        }
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
        boolean phoneControl=ShowcaseNativeBridge.phoneControl();
        syncing=true;
        try {
            if (remoteToggle.isChecked()!=phoneControl) remoteToggle.setChecked(phoneControl);
        } finally {
            syncing=false;
        }
        remoteToggle.setEnabled(readbackAvailable&&pendingId.isEmpty()&&!unknown);
        if(s!=null){
            JSONObject input=s.optJSONObject("inputs"),availability=s.optJSONObject("availability");
            contextLine.setText((readbackAvailable?"":"Last applied · ")+"Breathing: "+displayChoice(input.optString("breath_source"))+" · Heartbeat: "+displayChoice(input.optString("heartbeat_source"))+(input.optString("heartbeat_source").equals("synthetic")?" "+input.optString("synthetic_bpm")+" BPM":"")+(availability!=null&&availability.optBoolean("selected_polar_unavailable")?" · Waiting for selected Polar input":availability!=null&&!availability.optBoolean("breath",true)?" · Waiting for breathing input":""));
            if(awaitingRendered>0&&s.optLong("rendered_visual_revision")>=awaitingRendered){awaitingRendered=0;message="Applied visual settings";}
            if(page.equals("Calibration")) updateCalibration(s.optJSONObject("calibration"));
            if(lastRadius!=null)lastRadius.setText("Last applied sphere radius: "+(s.isNull("rendered_radius_m")?"unavailable":s.opt("rendered_radius_m")+" m"));
        }
        outcome.setText((!readbackAvailable&&s!=null?(pendingId.isEmpty()?"Waiting for current headset settings · ":"Waiting for the headset; the earlier action will not be sent again · "):"")+message+(state.inputDirty?" · Unapplied input edits":"")+(state.visualDirty?" · Unapplied visual edits":"")+((state.inputDirty&&state.conflict(false))||(state.visualDirty&&state.conflict(true))?" · Settings changed elsewhere — read applied settings":""));
        String selected=page.startsWith("Visual / ")?"Visuals":page.equals("Polar")||page.equals("Calibration")?"Inputs":page;
        for (Map.Entry<String,Button> tab : tabButtons.entrySet()) {
            boolean active = tab.getKey().equals(selected);
            tab.getValue().setSelected(active);
            tab.getValue().setTextColor(active ? ACCENT : FG);
        }
        enableEditors(body,readbackAvailable&&pendingId.isEmpty()&&!unknown);enableEditors(actions,readbackAvailable&&pendingId.isEmpty()&&!unknown);
    }
    private static boolean navigationLabel(String label){if(isMain(label)||label.equals("Close")||label.equals("Back")||label.equals("Polar setup")||label.equals("Calibration")||(label.startsWith("▸")||label.startsWith("▾"))||label.equals("Read applied")||label.equals("Acknowledge current state"))return true;for(String section:VISUAL_PAGES)if(section.equals(label))return true;return false;}
    private void enableEditors(View view,boolean enabled){if(view instanceof Button)view.setEnabled(enabled||navigationLabel(((Button)view).getText().toString()));else if(view instanceof EditText||view instanceof Spinner||view instanceof CompoundButton)view.setEnabled(enabled&&!Boolean.FALSE.equals(view.getTag()));if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)enableEditors(group.getChildAt(i),enabled);}}
    private void inputChoice(LinearLayout parent,String label,final JSONObject inputs,final String key,String[] values) {
        Spinner spinner=host.createSpinner(context,label);
        spinner.setAdapter(adapter(context,values));
        int selected=0;
        for(int i=0;i<values.length;i++)if(values[i].equals(inputs.optString(key)))selected=i;
        spinner.setSelection(selected);
        addControlRow(parent,text(label,15,FG),spinner,280);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                Object value=parent.getItemAtPosition(position);
                if(!value.equals(inputs.opt(key)))queueInputEdit(key,value);
            }
        });
    }

    static JSONObject inputChange(JSONObject current,String key,Object value)throws Exception {
        JSONObject next=copy(current).put(key,value);
        if(next.optString("breath_source").equals("automatic"))next.put("breath_mapping","state");
        if(!allowsPolarDynamic(next) && next.optString("polar_projection").equals("dynamic-axis"))
            next.put("polar_projection","xz");
        return next;
    }

    private static boolean allowsPolarDynamic(JSONObject inputs) {
        return inputs.optString("breath_source").equals("polar-acc")
            && inputs.optString("breath_mapping").equals("state");
    }

    private void queueInputEdit(final String key,final Object value) {
        // A native receipt can rebuild the page. Let the widget finish dispatching first.
        main.post(new Runnable(){public void run(){
            if(retired||!resumed||!host.current()||!pendingId.isEmpty()||unknown)return;
            try {
                state.observe(ShowcaseNativeBridge.status());
                boolean changed=state.refreshCleanDomain(false);
                JSONObject payload=inputChange(state.inputs,key,value);
                if(!payload.toString().equals(state.inputs.toString()))send("set-inputs",payload,false);
                else if(changed)rebuildInputPage();
            } catch(Exception error) {
                if(unknown)return;
                try {
                    state.observe(ShowcaseNativeBridge.status());
                    state.refreshCleanDomain(false);
                    readbackAvailable=true;
                    message="Input change could not be applied. Showing current headset settings.";
                    rebuildInputPage();
                } catch(Exception unavailable) {
                    readbackAvailable=false;
                    message="Input change could not be applied. Waiting for headset settings.";
                    invalidateInputPages();
                    updateFrame();
                }
            }
        }});
    }

    private void invalidateInputPages() {
        pages.remove("Inputs");
        pages.remove("Calibration");
    }

    private void rebuildInputPage()throws Exception {
        invalidateInputPages();
        rebuildAtCurrentScroll();
    }

    private void rebuildAtCurrentScroll()throws Exception {
        final int previousScroll=scroll.getScrollY();
        final String previousPage=page;
        buildPage();
        scroll.post(new Runnable(){public void run(){
            if(page.equals(previousPage))scroll.scrollTo(0,previousScroll);
        }});
    }
    interface NumericWrite { void write(Number value) throws Exception; }

    static Number parseNumericValue(String text, double minimum, double maximum,
                                    boolean integer, boolean floatBounds) {
        double value = Double.parseDouble(text.trim());
        double compared = floatBounds ? (float)value : value;
        if (!Double.isFinite(value) || compared < minimum || compared > maximum
                || (integer && value != Math.rint(value))) {
            throw new NumberFormatException("Value outside the allowed range");
        }
        if (integer) return Long.valueOf((long)value);
        return Double.valueOf(value);
    }

    static final class NumericField {
        private final EditText editor;
        private final TextView feedback;
        private final double minimum, maximum;
        private final boolean integer, floatBounds;
        private final NumericWrite write;
        private final String range;
        private String lastValid;

        NumericField(LinearLayout parent, EditText editor, String title, Number initial,
                     double minimum, double maximum, boolean integer, boolean floatBounds,
                     NumericWrite write) {
            this.editor=editor;
            this.minimum=minimum;
            this.maximum=maximum;
            this.integer=integer;
            this.floatBounds=floatBounds;
            this.write=write;
            lastValid=initial.toString();
            range = formatBound(minimum) + " – " + formatBound(maximum)
                + (integer ? " · whole numbers" : "");

            Context context=parent.getContext();
            LinearLayout labels=new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView label=new TextView(context);
            label.setText(title);
            label.setTextSize(15);
            label.setTextColor(FG);
            labels.addView(label);
            feedback=new TextView(context);
            feedback.setTextSize(13);
            showRange();
            labels.addView(feedback);

            editor.setSingleLine(true);
            editor.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | (integer ? 0 : android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                | (minimum < 0 ? android.text.InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
            editor.setImeOptions(EditorInfo.IME_ACTION_DONE);
            editor.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            editor.setContentDescription(title + ", " + range);
            styleEditor(editor);
            editor.setText(lastValid);
            addControlRow(parent,labels,editor,140);
            editor.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean focused) {
                    if (focused) showRange();
                    else commit();
                }
            });
            editor.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView view, int action, android.view.KeyEvent event) {
                    if (action == EditorInfo.IME_ACTION_DONE) commit();
                    return false;
                }
            });
        }

        private String formatBound(double bound) {
            if (floatBounds) return new java.math.BigDecimal(Float.toString((float)bound)).stripTrailingZeros().toPlainString();
            return java.math.BigDecimal.valueOf(bound).stripTrailingZeros().toPlainString();
        }

        private void showRange() {
            feedback.setTextColor(MUTED);
            feedback.setText(range);
        }

        private boolean commit() {
            String typed=editor.getText().toString().trim();
            if (typed.equals(lastValid)) return true;
            try {
                Number value=parseNumericValue(typed,minimum,maximum,integer,floatBounds);
                write.write(value);
                lastValid=typed;
                showRange();
                return true;
            } catch (Exception rejected) {
                editor.setText(lastValid);
                editor.setSelection(editor.length());
                feedback.setTextColor(WARNING);
                String detail = rejected instanceof IllegalArgumentException
                    && !(rejected instanceof NumberFormatException) ? "\n" + rejected.getMessage() : "";
                feedback.setText(range + " · Restored " + lastValid + detail);
                return false;
            }
        }

        void requireCommitted() {
            if (!commit()) throw new IllegalArgumentException("Out of range: restored the previous value.");
        }

    }

    private void inputs(LinearLayout parent)throws Exception {
        parent.addView(text("Input changes apply automatically.",13,MUTED));
        inputChoice(parent,"Breathing",state.inputs,"breath_source",new String[]{"automatic","controller","polar-acc"});
        final TextView automatic=text("Automatic: 4 s inhale · 4 s full hold · 4 s exhale · 4 s empty hold",13,MUTED);parent.addView(automatic);
        final LinearLayout mapping=column();parent.addView(mapping);inputChoice(mapping,"Sensor mapping",state.inputs,"breath_mapping",new String[]{"volume","state"});
        inputChoice(parent,"Heartbeat",state.inputs,"heartbeat_source",new String[]{"off","polar","synthetic"});final LinearLayout rate=column();parent.addView(rate);JSONObject draftInputs = state.inputs;
        inputBpm = new NumericField(rate, host.createEditor(context), "Synthetic rate (BPM)",
            (Number)draftInputs.get("synthetic_bpm"), 30, 240, false, false, new NumericWrite() {
                @Override public void write(Number value) throws Exception {
                    queueInputEdit("synthetic_bpm",value);
                }
            });
        boolean auto=state.inputs.optString("breath_source").equals("automatic");
        mapping.setVisibility(auto?View.GONE:View.VISIBLE);
        automatic.setVisibility(auto?View.VISIBLE:View.GONE);
        rate.setVisibility(state.inputs.optString("heartbeat_source").equals("synthetic")?View.VISIBLE:View.GONE);
        boolean expanded=advancedInputs!=null&&advancedInputs.getVisibility()==View.VISIBLE;
        LinearLayout advanced=foldoutGroup(parent,"Advanced input mapping",expanded);
        advancedInputs=advanced;
        inputChoice(advanced,"Controller projection",state.inputs,"controller_projection",new String[]{"dynamic-axis","fixed-orientation"});
        inputChoice(advanced,"Polar projection",state.inputs,"polar_projection",
            allowsPolarDynamic(state.inputs)?new String[]{"xz","3d","dynamic-axis"}:new String[]{"xz","3d"});
        String inversionLabel="Invert breathing mapping";
        CheckBox inverse=toggle(context,inversionLabel,state.inputs.getBoolean("inverted"));
        addControlRow(advanced,text(inversionLabel,15,FG),inverse,140);
        onToggle(inverse,inversionLabel,new CompoundButton.OnCheckedChangeListener(){
            public void onCheckedChanged(CompoundButton button,boolean value){queueInputEdit("inverted",value);}
        });
        LinearLayout links=row();add(links,"Polar setup",new Action(){public void run()throws Exception{navigate("Polar");}});add(links,"Calibration",new Action(){public void run()throws Exception{navigate("Calibration");}});parent.addView(links);
    }
    private void visuals(LinearLayout parent) {
        LinearLayout pair = null;
        for (int i=0;i<VISUAL_PAGES.length;i++) {
            if (i%2==0) { pair=row(); parent.addView(pair); }
            final String section = VISUAL_PAGES[i];
            Button entry = button(section,new Action(){
                @Override public void run() throws Exception { navigate("Visual / "+section); }
            });
            entry.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            pair.addView(entry,tabLayout());
        }
        if (VISUAL_PAGES.length%2!=0) pair.addView(new View(context),tabLayout());
    }
    private void visualPage(LinearLayout parent)throws Exception {
        String section=page.substring("Visual / ".length());
        if(section.equals("Visual drivers")){String[] labels=TARGETS;final Spinner targets=host.createSpinner(context,"Driver target");targets.setAdapter(adapter(context,labels));targets.setSelection(driverTarget);parent.addView(targets);targets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean initial=true;public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(initial){initial=false;return;}try{flushVisuals();driverTarget=position;pages.remove(page);forms.remove(page);buildPage();}catch(Exception invalid){message="Fix the current driver value before changing target";updateFrame();}}});}
        if(section.equals("Scale & radius")){lastRadius=text("Last applied sphere radius: unavailable",13,MUTED);parent.addView(lastRadius);}
        LinearLayout fields=column();parent.addView(fields);ShowcaseVisualForm form=new ShowcaseVisualForm(context,schema,fields,host);form.setMaterialPresets(state.applied.optJSONObject("material_presets"));form.setDriverRanges(state.applied.optJSONArray("driver_ranges"));form.showSection(state.visual,section,driverTarget,new Runnable(){public void run(){state.visualDirty=true;updateFrame();}});forms.put(page,form);
    }
    private void flushVisuals()throws Exception {for(ShowcaseVisualForm form:forms.values())form.writeInto(state.visual);}
    private void profiles(LinearLayout parent)throws Exception {
        parent.addView(text("Profiles contain visuals only. Breathing and heartbeat stay selected.",13,MUTED));final JSONObject saved=state.applied.getJSONObject("profiles");ArrayList<String> names=new ArrayList<>();java.util.Iterator<String> it=saved.keys();while(it.hasNext())names.add(it.next());final Spinner picker=host.createSpinner(context,"Visual profile");picker.setAdapter(adapter(context,names.toArray(new String[0])));parent.addView(picker);
        add(parent,"Apply selected profile",new Action(){public void run()throws Exception{final String name=picker.getSelectedItem().toString();if(state.visualDirty){confirm("Replace unapplied visual edits with "+name+"?",new Action(){public void run()throws Exception{send("apply-profile",new JSONObject().put("name",name),false);}});}else send("apply-profile",new JSONObject().put("name",name),false);}});
        LinearLayout save=foldout(parent,"Save visual draft as a profile");final EditText name=host.createEditor(context);styleEditor(name);name.setSingleLine(true);name.setHint("Profile name");name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});save.addView(name);add(save,"Save draft",new Action(){public void run()throws Exception{flushVisuals();final String selected=name.getText().toString();final Action operation=new Action(){public void run()throws Exception{send("save-profile",new JSONObject().put("name",selected).put("visual",copy(state.visual)),true);}};if(saved.has(selected))confirm("Replace saved profile "+selected+"?",operation);else operation.run();}});
        LinearLayout remove=foldout(parent,"Delete saved profile");add(remove,"Delete selected saved profile",new Action(){public void run()throws Exception{final String selected=picker.getSelectedItem().toString();if(selected.equals("default"))throw new IllegalArgumentException("Default is immutable");confirm("Delete "+selected+"?",new Action(){public void run()throws Exception{send("delete-profile",new JSONObject().put("name",selected),false);}});}});
    }
    private void confirm(String prompt,final Action action){message=prompt;actions.removeAllViews();add(actions,"Cancel",new Action(){public void run()throws Exception{buildPage();}});add(actions,"Confirm",action);updateFrame();}
    private void setup(LinearLayout parent)throws Exception {
        parent.addView(text("Use the header to allow remote control. The phone and headset must share Wi-Fi or a phone hotspot.",14,MUTED));
        JSONObject details=ShowcaseNativeBridge.connectionDetails();parent.addView(text("Phone and headset must share Wi-Fi or a phone hotspot.",14,FG));parent.addView(text("Headset serial: "+(details.optString("serial").isEmpty()?"unavailable":details.optString("serial")),14,FG));JSONArray urls=details.optJSONArray("gateways");if(urls==null||urls.length()==0)parent.addView(text("No private IPv4 address available",14,MUTED));else for(int i=0;i<urls.length();i++)parent.addView(text(urls.getString(i),14,FG));
        LinearLayout info=foldout(parent,"Connection details");info.addView(text("Local HTTP authenticates commands but does not encrypt this LAN leg. Bluetooth reads identity and endpoint only. The headset remains settings authority.",13,MUTED));
    }
    private void polar(LinearLayout parent)throws Exception {
        JSONObject setup=state.applied.optJSONObject("polar_setup");parent.addView(text(setup==null?"Polar setup is waiting for the headset":(setup.optBoolean("p")?"Permissions ready":"Permissions needed")+" · "+(setup.optBoolean("b")?"Bluetooth on":"Bluetooth off")+" · "+(setup.optBoolean("c")?"Sensor connected":"Sensor disconnected")+" · "+(setup.optBoolean("a")?"Motion data fresh":"Waiting for motion data"),14,FG));
        add(parent,"Prepare permissions",new Action(){public void run(){if(polar==null)polar=PolarSensorRuntime.forApplication(activity).attachPanel(activity,ShowcaseControls.this);polar.requestStartupPermissions(activity);polar.onHostResume();}});add(parent,"Scan for Polar",new Action(){public void run()throws Exception{send("polar-scan",new JSONObject(),false);}});
        final Spinner sensors=host.createSpinner(context,"Discovered Polar sensor");ArrayList<String> names=new ArrayList<>();JSONArray found=setup==null?null:setup.optJSONArray("d");if(found!=null)for(int i=0;i<found.length();i++)names.add(found.getString(i));if(names.isEmpty())names.add("No discovered sensor");sensors.setAdapter(adapter(context,names.toArray(new String[0])));parent.addView(sensors);
        add(parent,"Select sensor",new Action(){public void run()throws Exception{send("polar-select",new JSONObject().put("device_id",sensors.getSelectedItem().toString()),false);}});
        for(final String op:new String[]{"polar-connect","polar-disconnect"})add(parent,op.equals("polar-connect")?"Connect Polar":"Disconnect Polar",new Action(){public void run()throws Exception{send(op,new JSONObject(),false);}});
        LinearLayout streams=foldout(parent,"ACC stream management");for(final String op:new String[]{"polar-start-acc","polar-stop-acc"})add(streams,op.equals("polar-start-acc")?"Start ACC":"Stop ACC",new Action(){public void run()throws Exception{send(op,new JSONObject(),false);}});
    }
    private void calibration(LinearLayout parent)throws Exception {
        JSONObject inputs=state.applied.getJSONObject("inputs");
        String source=inputs.getString("breath_source");
        calibrationStatus=null;
        calibrationProgress=null;
        parent.addView(text("Applied target: "+displayChoice(source)+" · "+displayChoice(inputs.optString("breath_mapping")),16,FG));
        if(source.equals("automatic")){
            parent.addView(text("Automatic breathing has no sensor calibration.",14,MUTED));
            return;
        }
        if(source.equals("controller") && inputs.optString("breath_mapping").equals("state")){
            boolean fixed=inputs.optString("controller_projection").equals("fixed-orientation");
            parent.addView(text(fixed?"Controller State uses Fixed orientation without calibration."
                    :"Controller State learns the movement direction continuously.",14,MUTED));
            return;
        }
        if(allowsPolarDynamic(inputs) && inputs.optString("polar_projection").equals("dynamic-axis")){
            parent.addView(text("Polar State with Dynamic axis learns the movement direction continuously.",14,MUTED));
            return;
        }
        calibrationStatus=text("",14,MUTED);
        parent.addView(calibrationStatus);
        calibrationProgress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);
        parent.addView(calibrationProgress,new LinearLayout.LayoutParams(-1,-2));
        if(state.inputDirty){
            parent.addView(text("Apply input edits before calibrating their target.",14,MUTED));
            return;
        }
        for(final String action:new String[]{"start","cancel"})add(parent,action.equals("start")?"Start calibration":"Cancel calibration",new Action(){public void run()throws Exception{send("calibrate",new JSONObject().put("action",action),false);}});
        LinearLayout reset=foldout(parent,"Reset calibration");add(reset,"Reset selected source calibration",new Action(){public void run()throws Exception{send("calibrate",new JSONObject().put("action","reset"),false);}});
    }

    private void updateCalibration(JSONObject calibration){
        if(calibrationStatus==null || calibrationProgress==null)return;
        int target=calibration==null?0:calibration.optInt("target_frames");
        int accepted=calibration==null?0:Math.max(0,calibration.optInt("accepted_frames"));
        String status=calibration==null?"Calibration status unavailable":displayChoice(calibration.optString("lifecycle","idle"));
        String failure=calibration==null||calibration.isNull("failure")?"":calibration.optString("failure");
        String detail=status+(target>0?" · "+accepted+" / "+target+" samples":"")+(failure.isEmpty()?"":" · "+displayChoice(failure));
        if(!detail.contentEquals(calibrationStatus.getText()))calibrationStatus.setText(detail);
        calibrationProgress.setVisibility(target>0?View.VISIBLE:View.GONE);
        calibrationProgress.setMax(Math.max(1,target));
        calibrationProgress.setProgress(accepted);
    }

    private void send(String op,JSONObject payload,boolean visualDomain)throws Exception {
        if(retired||!host.current()||!resumed)throw new IllegalStateException("owner-not-current");
        if(!pendingId.isEmpty()||unknown)throw new IllegalStateException("reconcile-original-outcome-first");
        boolean edits=op.equals("apply-visual")||op.equals("set-inputs")||op.equals("save-profile");
        if(edits&&state.conflict(visualDomain))throw new IllegalStateException("settings-changed-elsewhere-read-applied");
        JSONObject current=ShowcaseNativeBridge.status();state.observe(current);readbackAvailable=true;
        long revision=edits?state.revision(visualDomain):op.equals("set-phone-control")
            ?current.getLong("revision"):pageRevisions.containsKey(page)?pageRevisions.get(page):current.getLong("revision");
        if(revision!=current.getLong("revision"))throw new IllegalStateException("settings-changed-elsewhere-read-applied");
        String id=java.util.UUID.randomUUID().toString();JSONObject command=new JSONObject().put("schema","rusty.quest.visual_showcase.command.v1").put("product",ShowcaseNativeBridge.PRODUCT).put("epoch",state.epoch).put("expected_revision",revision).put("id",id).put("op",op).put("payload",payload);
        pendingId=id;pendingOp=op;message="Headset applying "+operationLabel(op);updateFrame();
        try{settle(ShowcaseNativeBridge.command(command));}catch(Exception failure){unknown=true;message="The earlier action could not be confirmed. Read applied settings to check the headset.";buildPage();throw failure;}
    }
    private void settle(JSONObject receipt)throws Exception {
        String result=receipt.optString("status");if(result.equals("pending")){message="Waiting for the headset to confirm "+operationLabel(pendingOp);return;}if(result.equals("unknown")){unknown=true;message="The earlier action could not be confirmed. It will not be sent again.";buildPage();return;}
        boolean inputEdit=pendingOp.equals("set-inputs");
        if(result.equals("accepted")){
            JSONObject current=ShowcaseNativeBridge.status();long confirmed=receipt.optLong("revision");
            state.acknowledgeLocal(current,pendingOp,confirmed);readbackAvailable=true;
            if(current.getLong("revision")==confirmed){
                for(String cached:pages.keySet())pageRevisions.put(cached,confirmed);
                if(pendingOp.equals("apply-visual")||pendingOp.equals("apply-profile")){
                    java.util.Iterator<String> keys=pages.keySet().iterator();while(keys.hasNext())if(keys.next().startsWith("Visual"))keys.remove();forms.clear();lastRadius=null;pages.remove("Profiles");
                }
                if(pendingOp.equals("save-profile")||pendingOp.equals("delete-profile"))pages.remove("Profiles");
                if(pendingOp.startsWith("polar-"))pages.remove("Polar");
                if(pendingOp.equals("set-phone-control"))pages.remove("Setup");
                message=pendingOp.equals("calibrate")?"Calibration command accepted.":"Applied "+operationLabel(pendingOp);
                if((pendingOp.equals("apply-visual")||pendingOp.equals("apply-profile"))&&current.optLong("rendered_visual_revision")<confirmed){awaitingRendered=confirmed;message="Headset applying visual changes…";}
            }else message=inputEdit?"Input change accepted. Showing the latest headset settings.":"Settings changed on the headset. Read applied settings to continue.";
        }
        else if(inputEdit){
            state.observe(ShowcaseNativeBridge.status());
            message="The headset rejected the input change. Showing current headset settings.";
        }else if(pendingOp.equals("calibrate")){
            message="Calibration command rejected ("+receipt.optString("reason","reason unavailable")+").";
        }else message="The headset could not apply "+operationLabel(pendingOp)+". Check the edited values and read applied settings.";
        pendingId="";pendingOp="";unknown=false;
        if(inputEdit){
            state.refreshCleanDomain(false);
            rebuildInputPage();
        }else buildPage();
    }
    public void closePanelAndReturnToImmersive(){host.closePanel();}
    public void onPolarStreamEvent(JSONObject event){}
    void permissions(int request,String[] permissions,int[] results){ShowcaseNativeBridge.discoveryPermissionResult();if(polar!=null)polar.onRequestPermissionsResult(request,permissions,results);}
    private void refreshCleanPages() throws Exception {
        // An editor can contain a partially typed value before its draft is marked dirty.
        if (frame==null || frame.findFocus() instanceof EditText || unknown) return;
        boolean inputsChanged=state.refreshCleanDomain(false);
        boolean visualChanged=state.refreshCleanDomain(true);
        if (inputsChanged) invalidateInputPages();
        if (visualChanged) {
            java.util.Iterator<String> keys=pages.keySet().iterator();
            while (keys.hasNext()) if (keys.next().startsWith("Visual / ")) keys.remove();
            forms.clear();
            lastRadius=null;
        }
        if (((inputsChanged || !pages.containsKey(page)) && (page.equals("Inputs") || page.equals("Calibration")))
                || (visualChanged && page.startsWith("Visual / "))) {
            rebuildAtCurrentScroll();
        }
    }

    private final Runnable poll=new Runnable() {
        @Override public void run() {
            if (!resumed || retired) return;
            try {
                boolean first=state.applied==null;
                state.observe(ShowcaseNativeBridge.status());
                message=currentReadMessage(message,readbackAvailable,pendingId,unknown);
                readbackAvailable=true;
                if (first && frame!=null) buildPage();
                if (!pendingId.isEmpty()) {
                    settle(new JSONObject(ShowcaseNativeBridge.nativeShowcaseReceipt(pendingId)));
                } else if (!first) {
                    refreshCleanPages();
                }
                updateFrame();
            } catch (Exception | UnsatisfiedLinkError unavailable) {
                readbackAvailable=false;
                updateFrame();
            }
            main.postDelayed(this,500);
        }
    };
}
