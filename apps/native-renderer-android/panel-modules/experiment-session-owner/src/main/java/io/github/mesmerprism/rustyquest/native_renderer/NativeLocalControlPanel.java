package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Applicable canonical topic pages, shared by the real Activity and mesh Presentation. No popup windows. */
final class NativeLocalControlPanel implements AutoCloseable {
    interface Host extends NativeLocalPanelBridge.Target {
        ExperimentSessionPanelState state();
        boolean commandReady(); boolean settingsReady(); boolean canSaveExit(); void action(String operation); void navigationChanged();
        int bias(); boolean bias(int value); void returnToVr();
    }
    private final Context context;private final Host host;private final NativeLocalPanelBridge bridge;
    private final NativeLocalControlController controller;
    private final LinearLayout root,body;private final ScrollView scroll;private final TextView message,ownerReadback;
    private final Map<String,Integer> positions=new LinkedHashMap<String,Integer>();
    private final Map<String,Button> sessionButtons=new LinkedHashMap<String,Button>();
    private TextView sessionReadback,storageReadback,patternReadback,breathReadback,particleReadback,combinedReadback;
    private SeekBar biasSlider;private TextView biasLabel;private Button nextButton;
    private String page="home";private long pageEpoch;private boolean closed;
    NativeLocalControlPanel(Context context,Context application,Host host){
        this.context=context;this.host=host;root=column();root.setPadding(24,16,24,16);root.setBackgroundColor(Color.rgb(17,18,22));
        LinearLayout header=column();header.addView(text("Breath composition",24));root.addView(header);
        LinearLayout topics=new LinearLayout(context);topics.setOrientation(LinearLayout.HORIZONTAL);
        for(final String topic:new String[]{"home","particles","breath","status","prepare"}){
            Button tab=button("prepare".equals(topic)?"Experimenter":title(topic),new Runnable(){public void run(){show(topic);}});
            topics.addView(tab,new LinearLayout.LayoutParams(0,64,1));
        }root.addView(topics);
        root.addView(button("Return to VR",new Runnable(){public void run(){if(host.current())host.returnToVr();}}));
        ownerReadback=text("Session readback pending",14);root.addView(ownerReadback);
        message=text("",14);message.setVisibility(View.GONE);root.addView(message);
        scroll=new ScrollView(context);scroll.setFillViewport(true);body=column();scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        bridge=new NativeLocalPanelBridge(application,new NativeLocalPanelBridge.Target(){public boolean current(){return !closed&&NativeLocalControlPanel.this.host.current()&&NativeLocalControlPanel.this.host.settingsReady();}public void outcome(String value){NativeLocalControlPanel.this.outcome(value);}});
        controller=new NativeLocalControlController(bridge);show("home");
    }
    View view(){return root;}
    void outcome(String value){if(!closed){message.setText(value);message.setVisibility(View.VISIBLE);}}
    void ownerReadback(String value){if(!closed)ownerReadback.setText(value);}
    private static String title(String value){return Character.toUpperCase(value.charAt(0))+value.substring(1);}
    void show(final String topic){
        if(closed)return;
        host.navigationChanged();positions.put(page,scroll.getScrollY());page=topic;pageEpoch=controller.navigate();final long captured=pageEpoch;
        body.removeAllViews();sessionButtons.clear();sessionReadback=null;storageReadback=null;patternReadback=null;breathReadback=null;particleReadback=null;combinedReadback=null;biasSlider=null;nextButton=null;
        body.addView(text("condition".equals(page)?"Choose condition":title(page),22));
        if("particles".equals(page))particles();else if("breath".equals(page))breath();else if("status".equals(page))status();
        else if("prepare".equals(page)||"controls".equals(page)||"condition".equals(page)||"session".equals(page))workflow();else home();
        refresh();
        final int y=positions.containsKey(page)?positions.get(page):0;
        scroll.post(new Runnable(){public void run(){if(!closed&&pageEpoch==captured)scroll.scrollTo(0,y);}});
    }
    private void home(){
        body.addView(text("Each page reports the state accepted by its runtime owner.",15));
        row("Particles","Shape, oscillator drivers, tracers and material.","particles");
        row("Breath","Controller mapping, calibration, guidance bias and live output.","breath");
        row("Effective state","Particle revisions, breath state and canonical local snapshot.","status");
        row("Experimenter","Prepare, controls, choose condition and session.","prepare");
    }
    private void row(String label,String detail,final String destination){LinearLayout card=card(label);card.addView(text(detail,15));card.addView(button("Open "+label,new Runnable(){public void run(){show(destination);}}));}
    private JSONObject particleValues(){try{JSONObject value=NativeLocalControlController.effectiveParticles(bridge.particles());return value==null?new JSONObject():value;}catch(Exception unavailable){return new JSONObject();}}
    private void particles(){
        final long epoch=pageEpoch;JSONObject p=particleValues(),size=p.optJSONObject("size"),tracer=p.optJSONObject("tracer"),material=p.optJSONObject("material");JSONArray drivers=p.optJSONArray("driver_values01");
        body.addView(text("Only edited fields are submitted. Slider positions are provisional until a matching renderer receipt arrives.",14));
        LinearLayout shape=card("Shape");
        final String[] mode={size==null?"sphere-radius-percent":size.optString("mode","sphere-radius-percent")};
        final LinearLayout percent=column(),meters=column();
        choice(shape,"Particle size unit",new String[]{"Percent of sphere radius","Raw world size (meters)"},new String[]{"sphere-radius-percent","world-meters"},mode[0],new Choice(){public void select(String value){mode[0]=value;percent.setVisibility("world-meters".equals(value)?View.GONE:View.VISIBLE);meters.setVisibility("world-meters".equals(value)?View.VISIBLE:View.GONE);controller.edit(epoch,"size.mode",value,value);}});
        double legacy=p.optDouble("visual_scale",bridge.setting("visual.scale",.70)),base=Math.sqrt(4*legacy*11.5*legacy),oscillation=100*(1-Math.sqrt(4/11.5));
        slider(percent,"Base particle size",.1,50,size==null?base:size.optDouble("sphere_radius_percent",base),49900," % of sphere radius",false,edit(epoch,"size.sphere_radius_percent",mode));
        slider(meters,"Base particle size",.001,.5,size==null?base*.01:size.optDouble("world_meters",base*.01),4990," m",false,edit(epoch,"size.world_meters",mode));
        shape.addView(percent);shape.addView(meters);percent.setVisibility("world-meters".equals(mode[0])?View.GONE:View.VISIBLE);meters.setVisibility("world-meters".equals(mode[0])?View.VISIBLE:View.GONE);
        slider(shape,"Size oscillation",0,90,size==null?oscillation:size.optDouble("oscillation_percent",oscillation),9000," %",false,edit(epoch,"size.oscillation_percent",mode));
        shape.addView(text("Percent mode follows the deformed sphere radius; meter mode is constant in world space. Oscillation is reciprocal: 50% means 0.5× minimum and 2× maximum.",13));
        slider(shape,"Sphere radius / anchor scale",.05,4,p.optDouble("world_anchor_scale_m",bridge.setting("world_anchor.scale_m",.46)),1000," m",false,edit(epoch,"world_anchor_scale_m",mode));
        LinearLayout dynamics=card("Oscillator drivers");String[] labels={"Driver 0 deformation","Driver 1 blend","Driver 2 particle size","Driver 3 depth wave","Driver 4 spin","Driver 5 orbit radius","Driver 6 orbit angle","Driver 7 animation frame"};
        for(int i=0;i<8;i++)slider(dynamics,labels[i],0,1,drivers==null?bridge.setting("driver"+i+".value01",0):drivers.optDouble(i,bridge.setting("driver"+i+".value01",0)),1000,"",false,edit(epoch,"driver_values01."+i,mode));
        LinearLayout trails=card("Tracers");
        slider(trails,"Tracer draw slots",0,1024,tracer==null?bridge.setting("tracer.draw_slots_per_oscillator",7):tracer.optDouble("draw_slots_per_oscillator",bridge.setting("tracer.draw_slots_per_oscillator",7)),1024,"",true,edit(epoch,"tracer.draw_slots_per_oscillator",mode));
        slider(trails,"Tracer lifetime",.016,30,tracer==null?bridge.setting("tracer.lifetime_seconds",.5):tracer.optDouble("lifetime_seconds",bridge.setting("tracer.lifetime_seconds",.5)),1000," s",false,edit(epoch,"tracer.lifetime_seconds",mode));
        slider(trails,"Tracer copies/sec",0,120,tracer==null?bridge.setting("tracer.copies_per_second",14):tracer.optDouble("copies_per_second",bridge.setting("tracer.copies_per_second",14)),1000,"",false,edit(epoch,"tracer.copies_per_second",mode));
        LinearLayout materials=card("Material");
        choice(materials,"Material A/B preset",new String[]{"Keep packaged/default material","Current additive","Premultiplied alpha over","Premultiplied alpha over + depth fade","Premultiplied alpha over + depth + facing fade","Reference material emulation"},
            new String[]{"packaged-default","current-additive","premultiplied-alpha-over","premultiplied-alpha-over-depth","premultiplied-alpha-over-depth-facing","reference-material-emulation"},material==null?"packaged-default":material.optString("preset","packaged-default"),new Choice(){public void select(String value){controller.edit(epoch,"material",value,mode[0]);}});
        particleReadback=text("Renderer readback pending",13);body.addView(particleReadback);
        body.addView(button("Refresh effective values",new Runnable(){public void run(){show("particles");}}));
    }
    private void breath(){
        final long epoch=pageEpoch;LinearLayout live=card("Live breath state");breathReadback=text("Reading native state",14);live.addView(breathReadback);
        guidance(card("Guided-breathing bias for next arm"));
        JSONObject request=null;try{request=bridge.breath().getJSONObject("snapshot").optJSONObject("effective");}catch(Exception ignored){}
        final String[] mapping={request==null?"volume":request.optString("mapping","volume")};
        final String[] projection={request==null?"dynamic-axis":request.optString("controller_projection","dynamic-axis")};
        final boolean[] inverted={request!=null&&request.optBoolean("inverted",false)};
        LinearLayout controls=card("Input mapping");controls.addView(text("Input source: Controller",15));
        choice(controls,"Movement mapping",new String[]{"Volume","State"},new String[]{"volume","state"},mapping[0],new Choice(){public void select(String value){mapping[0]=value;}});
        choice(controls,"Controller volume calibration",new String[]{"Dynamic axis","Fixed orientation"},new String[]{"dynamic-axis","fixed-orientation"},projection[0],new Choice(){public void select(String value){projection[0]=value;}});
        final CheckBox invert=new CheckBox(context);invert.setText("Invert assessed direction");invert.setTextColor(Color.rgb(238,240,244));invert.setChecked(inverted[0]);controls.addView(invert);
        controls.addView(button("Apply breath mapping",new Runnable(){public void run(){try{controller.breath(epoch,"select",new JSONObject().put("source","controller").put("mapping",mapping[0]).put("controller_projection",projection[0]).put("polar_projection","xz").put("inverted",invert.isChecked()));}catch(Exception error){outcome(error.getMessage());}}}));
        LinearLayout calibration=card("Calibration");calibration.addView(text("Move through a comfortable full breath range. Start here or hold right B for 1.25 seconds.",15));
        final String[] operations={"start_calibration","cancel","reset"};String[] labels={"Start calibration","Cancel calibration","Reset calibration"};
        for(int i=0;i<operations.length;i++){final String operation=operations[i];calibration.addView(button(labels[i],new Runnable(){public void run(){controller.breath(epoch,operation,null);}}));}
        calibration.addView(button("Refresh breath state",new Runnable(){public void run(){refresh();}}));
    }
    private void status(){
        combinedReadback=text("Canonical local effective snapshot pending",13);body.addView(combinedReadback);
        body.addView(button("Read canonical effective snapshot",new Runnable(){public void run(){refresh();}}));
    }
    private void guidance(LinearLayout container){
        final long captured=pageEpoch;
        patternReadback=text("Condition guidance readiness pending",14);container.addView(patternReadback);
        biasLabel=text("Breathing-pattern bias: "+host.bias()+"%",15);container.addView(biasLabel);
        biasSlider=new SeekBar(context);biasSlider.setMax(100);biasSlider.setProgress(Math.max(0,host.bias()));
        biasSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean user){if(user&&pageEpoch==captured&&host.current()&&host.bias(value))biasLabel.setText("Breathing-pattern bias: "+value+"%");}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});container.addView(biasSlider);
        container.addView(text("0% is signal-driven; 100% is guidance-dominant. A valid selected input remains required. The value is locked into the recording when armed.",13));
    }
    private void workflow(){
        if("prepare".equals(page)){storageReadback=text("Checking recording storage",16);card("Recording").addView(storageReadback);card("Input").addView(text("Controller breath input; optional hand pointer only controls this panel.",15));}
        else if("controls".equals(page)){LinearLayout card=card("Controller controls");card.addView(text("Calibration: hold right B for 1.25 seconds. Start/resume: hold Right Grip + A for at least 0.75 seconds, then release after the pulse. Pause: Right Grip + B. Release between gestures.",16));card.addView(text("Use the pointer trigger or hand pinch to click, drag sliders and scroll. Session state changes only after authoritative receipts.",15));}
        else if("condition".equals(page)){guidance(card("Choose condition"));sessionButton("arm-a","Arm condition A");sessionButton("arm-b","Arm condition B");}
        else{sessionButton("start","Start official run");sessionButton("pause","Pause official run");sessionButton("resume","Resume official run");sessionButton("save-next","Save session & prepare next");}
        sessionButton("save-exit","Save and exit");
        sessionReadback=text("Waiting for session owner",15);body.addView(sessionReadback);
        body.addView(button("Refresh status",new Runnable(){public void run(){refresh();}}));
        final String[] pages={"prepare","controls","condition","session"};int index=0;while(index<pages.length-1&&!pages[index].equals(page))index++;
        if(index>0){final String previous=pages[index-1];body.addView(button("Back",new Runnable(){public void run(){show(previous);}}));}
        if(index<pages.length-1){final String next=pages[index+1];nextButton=button("Next",new Runnable(){public void run(){show(next);}});body.addView(nextButton);}
        body.addView(button("Open developer settings",new Runnable(){public void run(){show("home");}}));
    }
    private void sessionButton(final String operation,String label){final long captured=pageEpoch;Button button=button(label,new Runnable(){public void run(){if(pageEpoch==captured&&host.current()&&("save-exit".equals(operation)||host.commandReady()))host.action(operation);}});sessionButtons.put(operation,button);body.addView(button);}
    void refresh(){
        if(closed||!host.current())return;
        ExperimentSessionPanelState state=host.state();ExperimentSessionPanelViewPolicy.ViewState view=ExperimentSessionPanelViewPolicy.project(state);
        if(sessionReadback!=null)sessionReadback.setText(ExperimentSessionPanelViewPolicy.stageTitle(state)+"\n"+ExperimentSessionPanelViewPolicy.stageInstruction(state)+"\n"+view.countLine+"\n"+view.statusLine+"\n"+state.detail);
        if(storageReadback!=null){ExperimentSessionPanelViewPolicy.ReadinessCard card=ExperimentSessionPanelViewPolicy.storageCard(state);storageReadback.setText(card.label+"\n"+card.detail);}
        boolean patternA="pattern-ready".equals(ControlPanelActivity.conditionBreathGuidanceReadiness(ExperimentSessionPanelCoordinator.CONDITION_ONE));
        boolean patternB="pattern-ready".equals(ControlPanelActivity.conditionBreathGuidanceReadiness(ExperimentSessionPanelCoordinator.CONDITION_TWO));
        if(patternReadback!=null)patternReadback.setText("Condition A audio: "+ControlPanelActivity.conditionAudioReadiness(ExperimentSessionPanelCoordinator.CONDITION_ONE)+" · guidance: "+ControlPanelActivity.conditionBreathGuidanceReadiness(ExperimentSessionPanelCoordinator.CONDITION_ONE)+"\nCondition B audio: "+ControlPanelActivity.conditionAudioReadiness(ExperimentSessionPanelCoordinator.CONDITION_TWO)+" · guidance: "+ControlPanelActivity.conditionBreathGuidanceReadiness(ExperimentSessionPanelCoordinator.CONDITION_TWO));
        if(biasSlider!=null){biasSlider.setEnabled(!state.hasActiveSession()&&(patternA||patternB));biasSlider.setProgress(Math.max(0,host.bias()));biasLabel.setText("Breathing-pattern bias: "+host.bias()+"%");}
        for(Map.Entry<String,Button> entry:sessionButtons.entrySet()){
            String operation=entry.getKey();boolean allowed=operation.startsWith("arm-")?view.startEnabled&&"track-ready".equals(ControlPanelActivity.conditionAudioReadiness("arm-a".equals(operation)?ExperimentSessionPanelCoordinator.CONDITION_ONE:ExperimentSessionPanelCoordinator.CONDITION_TWO)):
                "start".equals(operation)?state.phase==ExperimentSessionPanelState.Phase.ARMED:"pause".equals(operation)?state.phase==ExperimentSessionPanelState.Phase.RUNNING:"resume".equals(operation)?state.phase==ExperimentSessionPanelState.Phase.PAUSED:ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(state);
            // Terminal requests always reach host admission and its current feedback.
            entry.getValue().setEnabled("save-exit".equals(operation)?host.current():host.commandReady()&&allowed);
        }
        if(nextButton!=null)nextButton.setEnabled(!view.saving&&(!"condition".equals(page)||state.hasActiveSession()));
        try{
            if(breathReadback!=null)breathReadback.setText(NativeLocalControlController.breathSummary(bridge.breath()));
            if(particleReadback!=null)particleReadback.setText(NativeLocalControlController.particleSummary(bridge.particles()));
            if(combinedReadback!=null)combinedReadback.setText(new JSONObject().put("particles",bridge.particles()).put("breath",bridge.breath()).toString(2));
        }catch(Exception unavailable){if(breathReadback!=null)breathReadback.setText("Live breath state unavailable; refresh pending.");if(particleReadback!=null)particleReadback.setText("Renderer state unavailable; displayed controls remain provisional.");if(combinedReadback!=null)combinedReadback.setText("Effective snapshot unavailable: "+unavailable.getMessage());}
    }
    private LinearLayout column(){LinearLayout value=new LinearLayout(context);value.setOrientation(LinearLayout.VERTICAL);return value;}
    private LinearLayout card(String title){LinearLayout value=column();value.setPadding(12,12,12,16);value.addView(text(title,19));body.addView(value);return value;}
    private TextView text(String value,int size){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(238,240,244));view.setPadding(0,5,0,5);return view;}
    private Button button(String label,final Runnable action){Button value=new Button(context);value.setText(label);value.setAllCaps(false);value.setMinHeight(60);value.setOnClickListener(new View.OnClickListener(){public void onClick(View view){if(!closed)action.run();}});return value;}
    interface Choice {void select(String value);}
    private void choice(LinearLayout parent,String label,String[] labels,final String[] values,String selected,final Choice action){
        parent.addView(text(label,16));final Button[] buttons=new Button[values.length];
        for(int i=0;i<values.length;i++){final int index=i;buttons[i]=button(labels[i],new Runnable(){public void run(){for(int j=0;j<buttons.length;j++)buttons[j].setEnabled(j!=index);action.select(values[index]);}});buttons[i].setEnabled(!values[i].equals(selected));parent.addView(buttons[i]);}
    }
    interface Value {void changed(double value);}
    private Value edit(final long epoch,final String field,final String[] mode){return new Value(){public void changed(double value){Object typed;if("tracer.draw_slots_per_oscillator".equals(field))typed=Integer.valueOf((int)Math.round(value));else typed=Double.valueOf(value);controller.edit(epoch,field,typed,mode[0]);}};}
    private void slider(LinearLayout parent,final String label,final double min,final double max,double initial,int steps,final String unit,final boolean integral,final Value changed){
        final TextView display=text("",15);final SeekBar bar=new SeekBar(context);bar.setMax(steps);bar.setProgress((int)Math.round((Math.max(min,Math.min(max,initial))-min)/(max-min)*steps));
        final Runnable render=new Runnable(){public void run(){double value=min+(max-min)*bar.getProgress()/bar.getMax();display.setText(label+": "+(integral?Long.toString(Math.round(value)):String.format(java.util.Locale.ROOT,"%.3f",value))+unit);}};render.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar value,int progress,boolean user){render.run();if(user)changed.changed(min+(max-min)*progress/bar.getMax());}public void onStartTrackingTouch(SeekBar value){}public void onStopTrackingTouch(SeekBar value){}});parent.addView(display);parent.addView(bar,new LinearLayout.LayoutParams(-1,64));
    }
    void suspend(){if(!closed){pageEpoch=controller.navigate();bridge.cancelQueued();ownerReadback.setText("Session readback paused");outcome("Panel paused; queued local edits retired. Accepted native commands are not replayed.");}}
    void resume(){if(!closed){ownerReadback.setText("Session readback pending");show(page);}}
    public void close(){if(closed)return;closed=true;controller.close();bridge.close();}
}
