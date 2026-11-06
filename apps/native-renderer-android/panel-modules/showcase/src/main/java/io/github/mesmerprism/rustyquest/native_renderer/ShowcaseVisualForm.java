package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Complete typed form projected from the app-owned closed visual schema. */
final class ShowcaseVisualForm {
    private final Context context;
    private final ShowcaseControls.Host host;
    private final JSONObject schema;
    private final LinearLayout container;
    private final List<Binding> bindings=new ArrayList<>();
    private JSONObject model;
    private boolean sectionMode;
    private Runnable changed;
    private JSONObject materialPresets;
    private JSONArray driverRanges;
    private TextView presetLabel;
    private interface Binding {void read()throws Exception;}
    ShowcaseVisualForm(Context context,JSONObject schema,LinearLayout container,
                       ShowcaseControls.Host host) {
        this.context=context;
        this.schema=schema;
        this.container=container;
        this.host=host;
    }
    void show(JSONObject value)throws Exception {
        sectionMode=false;changed=null;
        model=new JSONObject(value.toString());bindings.clear();container.removeAllViews();
        object(model,schema,container,"");
    }
    void setMaterialPresets(JSONObject presets){materialPresets=presets;}
    void setDriverRanges(JSONArray ranges){driverRanges=ranges;}
    static String[] sectionKeys(String section){
        if(section.equals("Scale & radius"))return new String[]{"radius","visual_scale","world_anchor_scale_m"};
        if(section.equals("Particle size"))return new String[]{"size"};
        if(section.equals("Orbit geometry"))return new String[]{"orbit_distance"};
        if(section.equals("Tracers"))return new String[]{"tracer"};
        if(section.equals("Material & transparency"))return new String[]{"material","transparency","color"};
        if(section.equals("Radius coupling"))return new String[]{"frame_extension"};
        if(section.equals("Visual drivers"))return new String[]{"driver_controls","driver_values01"};
        throw new IllegalArgumentException("unknown-visual-section");
    }
    void showSection(JSONObject value,String section,int target,Runnable changed)throws Exception {
        this.model=value;this.sectionMode=true;this.changed=changed;bindings.clear();container.removeAllViews();presetLabel=null;
        JSONObject properties=schema.getJSONObject("properties");
        if(section.equals("Visual drivers")){
            if(target<0||target>7)throw new IllegalArgumentException("driver-target-bound");
            JSONObject driver=value.getJSONArray("driver_controls").getJSONObject(target),definition=properties.getJSONObject("driver_controls").getJSONArray("prefixItems").getJSONObject(target).getJSONObject("properties");
            TextView identity=new TextView(context);identity.setText("Channel "+target+" (fixed)");identity.setTextColor(ShowcaseControls.MUTED);
            for(String name:new String[]{"mode","curve","range_min","range_max","cycle_multiplier"})field(driver,name,driver.get(name),definition.getJSONObject(name),container,"driver_controls / "+target+" / "+name);
            LinearLayout advanced=foldout(container,"Source and manual/base details");advanced.addView(identity);
            field(driver,"source_slot",driver.get("source_slot"),definition.getJSONObject("source_slot"),advanced,"driver_controls / "+target+" / source_slot");
            field(value.getJSONArray("driver_values01"),target,value.getJSONArray("driver_values01").get(target),properties.getJSONObject("driver_values01").getJSONObject("items"),advanced,"driver_values01 / "+target);
            return;
        }
        LinearLayout advanced=null;
        for(String key:sectionKeys(section)){
            JSONObject definition=properties.getJSONObject(key);Object current=value.get(key);
            if(current instanceof JSONObject){JSONObject nested=(JSONObject)current,fields=definition.getJSONObject("properties");Iterator<String> keys=fields.keys();while(keys.hasNext()){
                String name=keys.next();LinearLayout destination=container;
                if(section.equals("Material & transparency")&&(name.equals("depth_suppression_strength")||name.equals("rgb_alpha_coupling")||name.equals("facing_attenuation_strength"))){if(advanced==null)advanced=foldout(container,"Advanced appearance");destination=advanced;}
                field(nested,name,nested.get(name),fields.getJSONObject(name),destination,key+" / "+name);
            }}else field(value,key,current,definition,container,key);
        }
        if(section.equals("Material & transparency")){presetLabel=new TextView(context);presetLabel.setTextColor(ShowcaseControls.MUTED);container.addView(presetLabel);refreshPresetLabel();}
    }
    private LinearLayout foldout(LinearLayout parent,String title) {
        return ShowcaseControls.foldoutGroup(parent,title);
    }
    void writeInto(JSONObject destination)throws Exception {if(destination!=model)throw new IllegalStateException("stale-visual-form-generation");for(Binding binding:bindings)binding.read();}
    static void fillPreset(JSONObject visual,JSONObject presets,String name)throws Exception {
        if(presets==null||!presets.has(name))throw new IllegalArgumentException("preset-metadata-unavailable");
        JSONObject preset=presets.getJSONObject(name),transparency=preset.getJSONObject("transparency"),color=preset.getJSONObject("color");
        if(preset.length()!=2||transparency.length()!=4||color.length()!=1)throw new IllegalArgumentException("closed-preset-metadata-required");
        for(String key:new String[]{"opacity","output_alpha_scale","depth_suppression_strength","rgb_alpha_coupling"})if(!(transparency.get(key) instanceof Number)||!Double.isFinite(transparency.getDouble(key)))throw new IllegalArgumentException("finite-preset-coefficients-required");
        if(!(color.get("facing_attenuation_strength") instanceof Number)||!Double.isFinite(color.getDouble("facing_attenuation_strength")))throw new IllegalArgumentException("finite-preset-coefficients-required");
        visual.put("transparency",new JSONObject(transparency.toString()));visual.put("color",new JSONObject(color.toString()));visual.getJSONObject("material").put("preset",name);
    }
    static boolean presetMatches(JSONObject visual,JSONObject presets)throws Exception {if(presets==null)return false;JSONObject preset=presets.optJSONObject(visual.getJSONObject("material").getString("preset"));if(preset==null)return false;for(String group:new String[]{"transparency","color"}){JSONObject values=preset.getJSONObject(group);Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();if(Float.compare((float)values.getDouble(key),(float)visual.getJSONObject(group).getDouble(key))!=0)return false;}}return true;}
    private void refreshPresetLabel(){if(presetLabel==null)return;try{presetLabel.setText(materialPresets==null?"Preset choices are waiting for the headset. Manual values remain editable.":presetMatches(model,materialPresets)?"Preset coefficients":"Preset (modified)");}catch(Exception unavailable){presetLabel.setText("Preset metadata unavailable");}}
    private void notifyChanged(){refreshPresetLabel();if(changed!=null)changed.run();}
    JSONObject read()throws Exception {if(model==null)throw new IllegalStateException("Read effective visuals first");for(Binding binding:bindings)binding.read();return new JSONObject(model.toString());}
    private String label(String name){if(name.equals("size / enabled"))return "Size control";if(name.equals("size / mode"))return "Size basis";String key=name.contains(" / ")?name.substring(name.lastIndexOf(" / ")+3):name;if(key.equals("minimum_m"))return "Minimum radius (m)";if(key.equals("maximum_m"))return "Maximum radius (m)";if(key.equals("world_anchor_scale_m"))return "Stored anchor size (m)";if(key.equals("world_meters"))return "World size (m)";if(key.equals("sphere_radius_percent"))return "Size (% of sphere radius)";if(key.equals("oscillation_percent"))return "Size oscillation (%)";if(key.equals("reference_radius_m"))return "Reference radius (m)";if(key.equals("lifetime_seconds"))return "Lifetime (s)";if(key.equals("draw_slots_per_oscillator"))return "Tracers per particle";if(key.equals("source_slot"))return "Source channel";String text=key.replace('_',' ');return text.isEmpty()?text:Character.toUpperCase(text.charAt(0))+text.substring(1);}
    private void object(JSONObject value,JSONObject description,LinearLayout parent,String path)throws Exception {
        JSONObject properties=description.getJSONObject("properties");
        Iterator<String> keys=properties.keys();while(keys.hasNext()){
            String key=keys.next();JSONObject field=properties.getJSONObject(key);
            field(value,key,value.get(key),field,parent,path.isEmpty()?key:path+" / "+key);
        }
    }
    private void field(Object owner,Object key,Object value,JSONObject description,LinearLayout parent,String path)throws Exception {
        if(value instanceof JSONObject || value instanceof JSONArray){
            Button heading=new Button(context);ShowcaseControls.styleButton(heading);heading.setText(label(path));parent.addView(heading);
            LinearLayout group=new LinearLayout(context);group.setOrientation(LinearLayout.VERTICAL);group.setPadding(20,0,0,12);group.setVisibility(View.GONE);parent.addView(group);
            heading.setOnClickListener(new View.OnClickListener(){public void onClick(View view){group.setVisibility(group.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);}});
            if(value instanceof JSONObject)object((JSONObject)value,description,group,path);
            else {
                JSONArray array=(JSONArray)value;JSONArray prefix=description.optJSONArray("prefixItems");
                for(int i=0;i<array.length();i++)field(array,i,array.get(i),prefix==null?description.getJSONObject("items"):prefix.getJSONObject(i),group,path+" / "+(i+1));
            }
            return;
        }
        boolean readOnly = description.has("const") || sectionMode &&
            (path.equals("world_anchor_scale_m") || path.startsWith("driver_values01") &&
                (Integer.valueOf(0).equals(key) || Integer.valueOf(5).equals(key)));
        if (value instanceof Number && !readOnly) {
            bindNumber(owner,key,(Number)value,description,parent,path);
            return;
        }
        TextView title=new TextView(context);
        title.setText(label(path));
        title.setTextSize(15);
        title.setTextColor(ShowcaseControls.FG);
        title.setPadding(0,ShowcaseControls.dp(context,5),0,ShowcaseControls.dp(context,5));
        if(readOnly){parent.addView(title);TextView fixed=new TextView(context);fixed.setText(String.valueOf(value)+(path.equals("world_anchor_scale_m")?" · stored; sphere radius controls the current scale":Integer.valueOf(0).equals(key)?" · controlled by breathing input":Integer.valueOf(5).equals(key)?" · controlled by heartbeat":" · fixed"));fixed.setTextColor(ShowcaseControls.MUTED);parent.addView(fixed);return;}
        JSONArray choices=description.optJSONArray("enum");
        if(choices!=null){
            String[] names=new String[choices.length()];int selected=-1;
            for(int i=0;i<names.length;i++){names[i]=choices.getString(i);if(names[i].equals(value))selected=i;}
            if(selected<0)throw new IllegalArgumentException("Unknown effective choice: "+path);
            final int selectedIndex=selected;
            Spinner spinner=host.createSpinner(context,label(path));spinner.setAdapter(ShowcaseControls.adapter(context,names));spinner.setSelection(selected);ShowcaseControls.addControlRow(parent,title,spinner,280);
            if(sectionMode){final Spinner selectedSpinner=spinner;final String currentPath=path;spinner.setEnabled(!path.equals("material / preset")||materialPresets!=null);if(!spinner.isEnabled())spinner.setTag(Boolean.FALSE);spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean initial=true;public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(initial){initial=false;return;}try{String next=selectedSpinner.getSelectedItem().toString();if(currentPath.equals("material / preset")){fillPreset(model,materialPresets,next);final JSONObject value=model;final Runnable callback=changed;container.post(new Runnable(){public void run(){try{showSection(value,"Material & transparency",0,callback);}catch(Exception ignored){}}});}else put(owner,key,next);notifyChanged();}catch(Exception rejected){selectedSpinner.setSelection(selectedIndex);}}});}
            bindings.add(new Binding(){public void read()throws Exception{put(owner,key,spinner.getSelectedItem().toString());}});
        } else if(value instanceof Boolean){
            CheckBox checkbox=ShowcaseControls.toggle(context,label(path),(Boolean)value);ShowcaseControls.addControlRow(parent,title,checkbox,140);bindings.add(new Binding(){public void read()throws Exception{put(owner,key,checkbox.isChecked());}});if(sectionMode)ShowcaseControls.onToggle(checkbox,label(path),new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton view,boolean next){try{put(owner,key,next);notifyChanged();}catch(Exception ignored){}}});
        } else throw new IllegalArgumentException("Unrepresented visual field: "+path);
    }

    private void bindNumber(final Object owner, final Object key, Number value,
                            JSONObject description, LinearLayout parent, String path) throws Exception {
        boolean driverLimit = path.startsWith("driver_controls / ")
            && ("range_min".equals(key) || "range_max".equals(key));
        double minimum=description.getDouble("minimum");
        double maximum=description.getDouble("maximum");
        if (driverLimit) {
            int target = ((JSONObject)owner).getInt("target_slot");
            if (driverRanges == null) throw new IllegalStateException("driver-ranges-unavailable");
            JSONArray bounds = driverRanges.getJSONArray(target);
            minimum = bounds.getDouble(0);
            maximum = bounds.getDouble(1);
        }
        boolean depthPercent = driverLimit && ((JSONObject)owner).getInt("target_slot")==3;
        String title=label(path);
        Number displayed=value;
        if (depthPercent) {
            title += " (% of local sphere radius)";
            displayed=java.math.BigDecimal.valueOf(value.doubleValue()).movePointRight(2).stripTrailingZeros();
            minimum=Math.round(minimum*100);
            maximum=Math.round(maximum*100);
        }
        final ShowcaseControls.NumericField field = new ShowcaseControls.NumericField(
            parent,host.createEditor(context),title,displayed,minimum,maximum,
            "integer".equals(description.optString("type")),driverLimit&&!depthPercent,new ShowcaseControls.NumericWrite() {
                @Override public void write(Number number) throws Exception {
                    Number nativeValue=depthPercent ? Double.valueOf(number.doubleValue()/100) : number;
                    if (owner instanceof JSONObject) checkOrderedRange((JSONObject)owner,key,nativeValue);
                    put(owner,key,nativeValue);
                    if (sectionMode) notifyChanged();
                }
            });
        bindings.add(new Binding() {
            @Override public void read() { field.requireCommitted(); }
        });
    }

    static void checkOrderedRange(JSONObject owner, Object key, Number value) throws Exception {
        if (("minimum_m".equals(key) && value.doubleValue() > owner.getDouble("maximum_m"))
            || ("maximum_m".equals(key) && value.doubleValue() < owner.getDouble("minimum_m"))
            || ("range_min".equals(key) && value.floatValue() > (float)owner.getDouble("range_max"))
            || ("range_max".equals(key) && value.floatValue() < (float)owner.getDouble("range_min"))) {
            throw new IllegalArgumentException("Minimum must be at or below maximum");
        }
    }

    private void put(Object owner,Object key,Object value)throws Exception {if(owner instanceof JSONObject)((JSONObject)owner).put((String)key,value);else ((JSONArray)owner).put((Integer)key,value);}
}
