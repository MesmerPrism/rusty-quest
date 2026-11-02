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
    private final JSONObject schema;
    private final LinearLayout container;
    private final List<Binding> bindings=new ArrayList<>();
    private JSONObject model;
    private interface Binding {void read()throws Exception;}
    ShowcaseVisualForm(Context context,JSONObject schema,LinearLayout container){this.context=context;this.schema=schema;this.container=container;}
    void show(JSONObject value)throws Exception {
        model=new JSONObject(value.toString());bindings.clear();container.removeAllViews();
        object(model,schema,container,"");
    }
    JSONObject read()throws Exception {if(model==null)throw new IllegalStateException("Read effective visuals first");for(Binding binding:bindings)binding.read();return new JSONObject(model.toString());}
    private String label(String name){String text=name.replace('_',' ');return text.isEmpty()?text:Character.toUpperCase(text.charAt(0))+text.substring(1);}
    private void object(JSONObject value,JSONObject description,LinearLayout parent,String path)throws Exception {
        JSONObject properties=description.getJSONObject("properties");
        Iterator<String> keys=properties.keys();while(keys.hasNext()){
            String key=keys.next();JSONObject field=properties.getJSONObject(key);
            field(value,key,value.get(key),field,parent,path.isEmpty()?key:path+" / "+key);
        }
    }
    private void field(Object owner,Object key,Object value,JSONObject description,LinearLayout parent,String path)throws Exception {
        if(value instanceof JSONObject || value instanceof JSONArray){
            Button heading=new Button(context);heading.setText(label(path));parent.addView(heading);
            LinearLayout group=new LinearLayout(context);group.setOrientation(LinearLayout.VERTICAL);group.setPadding(20,0,0,12);group.setVisibility(View.GONE);parent.addView(group);
            heading.setOnClickListener(new View.OnClickListener(){public void onClick(View view){group.setVisibility(group.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);}});
            if(value instanceof JSONObject)object((JSONObject)value,description,group,path);
            else {
                JSONArray array=(JSONArray)value;JSONArray prefix=description.optJSONArray("prefixItems");
                for(int i=0;i<array.length();i++)field(array,i,array.get(i),prefix==null?description.getJSONObject("items"):prefix.getJSONObject(i),group,path+" / "+(i+1));
            }
            return;
        }
        TextView title=new TextView(context);title.setText(label(path));parent.addView(title);
        if(description.has("const")){TextView fixed=new TextView(context);fixed.setText(String.valueOf(value));parent.addView(fixed);return;}
        JSONArray choices=description.optJSONArray("enum");
        if(choices!=null){
            String[] names=new String[choices.length()];int selected=-1;
            for(int i=0;i<names.length;i++){names[i]=choices.getString(i);if(names[i].equals(value))selected=i;}
            if(selected<0)throw new IllegalArgumentException("Unknown effective choice: "+path);
            Spinner spinner=new Spinner(context);spinner.setAdapter(new ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,names));spinner.setSelection(selected);parent.addView(spinner);
            bindings.add(new Binding(){public void read()throws Exception{put(owner,key,spinner.getSelectedItem().toString());}});
        } else if(value instanceof Boolean){
            CheckBox checkbox=new CheckBox(context);checkbox.setText("Enabled");checkbox.setChecked((Boolean)value);parent.addView(checkbox);bindings.add(new Binding(){public void read()throws Exception{put(owner,key,checkbox.isChecked());}});
        } else if(value instanceof Number){
            EditText number=new EditText(context);number.setSingleLine(true);number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);number.setText(value.toString());parent.addView(number);
            if(description.has("minimum")||description.has("maximum")){TextView range=new TextView(context);range.setText("Range "+description.opt("minimum")+" to "+description.opt("maximum"));parent.addView(range);}
            bindings.add(new Binding(){public void read()throws Exception{double n=Double.parseDouble(number.getText().toString());if(!Double.isFinite(n)||description.has("minimum")&&n<description.getDouble("minimum")||description.has("maximum")&&n>description.getDouble("maximum"))throw new IllegalArgumentException("Out of range: "+label(path));if("integer".equals(description.optString("type"))){if(n!=Math.rint(n))throw new IllegalArgumentException("Whole number required: "+label(path));put(owner,key,(long)n);}else put(owner,key,n);}});
        } else throw new IllegalArgumentException("Unrepresented visual field: "+path);
    }
    private void put(Object owner,Object key,Object value)throws Exception {if(owner instanceof JSONObject)((JSONObject)owner).put((String)key,value);else ((JSONArray)owner).put((Integer)key,value);}
}
