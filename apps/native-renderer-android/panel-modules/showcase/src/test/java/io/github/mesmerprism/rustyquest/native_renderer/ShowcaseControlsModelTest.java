package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Production draft/preset/page rules, with no Activity or fabricated native readback. */
public final class ShowcaseControlsModelTest {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static JSONObject visual()throws Exception{return new JSONObject("{\"material\":{\"preset\":\"test\"},\"transparency\":{\"opacity\":1,\"output_alpha_scale\":1,\"depth_suppression_strength\":0,\"rgb_alpha_coupling\":0},\"color\":{\"facing_attenuation_strength\":0}}");}
    static JSONObject status(String epoch,long revision)throws Exception{return new JSONObject().put("product","visual-showcase").put("ready",true).put("epoch",epoch).put("revision",revision).put("inputs",new JSONObject().put("breath_source","automatic")).put("visual",visual());}
    static int leaves(JSONObject schema)throws Exception {String type=schema.optString("type");if(type.equals("object")){int total=0;JSONObject p=schema.getJSONObject("properties");java.util.Iterator<String> keys=p.keys();while(keys.hasNext())total+=leaves(p.getJSONObject(keys.next()));return total;}if(type.equals("array")){if(schema.has("prefixItems")){int total=0;org.json.JSONArray items=schema.getJSONArray("prefixItems");for(int i=0;i<items.length();i++)total+=leaves(items.getJSONObject(i));return total;}return schema.getInt("maxItems")*leaves(schema.getJSONObject("items"));}return 1;}
    private static void inputChangeUsesCurrentValues() throws Exception {
        JSONObject current=new JSONObject().put("breath_source","controller").put("breath_mapping","volume").put("inverted",true);
        JSONObject next=ShowcaseControls.inputChange(current,"breath_source","automatic");
        check(next.getString("breath_mapping").equals("state"),"automatic input requires state mapping");
        check(next.getBoolean("inverted"),"input change overwrote an unrelated field");
        check(current.getString("breath_mapping").equals("volume"),"input change mutated readback");
        JSONObject polar = next.put("breath_source","polar-acc").put("polar_projection","dynamic-axis");
        JSONObject controller = ShowcaseControls.inputChange(polar,"breath_source","controller");
        check(controller.getString("polar_projection").equals("dynamic-axis"),"Controller overwrote the Polar projection");
        JSONObject restored = ShowcaseControls.inputChange(controller,"breath_source","polar-acc");
        check(restored.getString("polar_projection").equals("dynamic-axis"),"returning to Polar lost the saved projection");
    }
    private static void numericBounds() throws Exception {
        check(ShowcaseControls.parseNumericValue(" 30 ",30,240,false,false).doubleValue()==30,"lower BPM boundary");
        check(ShowcaseControls.parseNumericValue("240",30,240,false,false).doubleValue()==240,"upper BPM boundary");
        check(ShowcaseControls.parseNumericValue("60.5",30,240,false,false).doubleValue()==60.5,"supported fractional BPM");
        check(ShowcaseControls.parseNumericValue("7",0,7,true,false) instanceof Long,"tracer count type");
        check(ShowcaseControls.parseNumericValue("6.2831855",0,(float)(2*Math.PI),false,true).floatValue()
              ==(float)(2*Math.PI),"displayed float bound rejected");
        for (String invalid : new String[]{"", "-", "29.9", "241", "NaN", "Infinity"}) {
            try {
                ShowcaseControls.parseNumericValue(invalid,30,240,false,false);
                throw new AssertionError("accepted invalid BPM: "+invalid);
            } catch (NumberFormatException expected) {}
        }
        try {
            ShowcaseControls.parseNumericValue("1.5",0,7,true,false);
            throw new AssertionError("accepted fractional tracer count");
        } catch (NumberFormatException expected) {}
        JSONObject radius = new JSONObject().put("minimum_m",1).put("maximum_m",2);
        ShowcaseVisualForm.checkOrderedRange(radius,"minimum_m",2);
        try {
            ShowcaseVisualForm.checkOrderedRange(radius,"maximum_m",0.5);
            throw new AssertionError("accepted inverted radius range");
        } catch (IllegalArgumentException expected) {}
        check(radius.getDouble("maximum_m")==2,"rejected bound mutated draft");
    }
    public static void main(String[]args)throws Exception {
        numericBounds();
        inputChangeUsesCurrentValues();
        ShowcaseControls.DraftState remote=new ShowcaseControls.DraftState();
        remote.observe(status("1",1));
        JSONObject controller=status("1",2);
        controller.getJSONObject("inputs").put("breath_source","controller");
        remote.observe(controller);
        check(remote.refreshCleanDomain(false),"external input change did not invalidate the cached form");
        check(remote.inputs.getString("breath_source").equals("controller"),"clean selector kept the old source");
        check(!remote.conflict(false),"clean inputs did not adopt remote revision");
        check(!remote.refreshCleanDomain(false),"unchanged poll would rebuild the form");
        remote.inputs.put("breath_source","polar-acc");
        remote.inputDirty=true;
        remote.observe(status("1",3));
        check(!remote.refreshCleanDomain(false),"readback overwrote the local input draft");
        check(remote.inputs.getString("breath_source").equals("polar-acc")&&remote.conflict(false),"dirty draft lost its conflict");
        check(ShowcaseControls.currentReadMessage("Waiting for current headset settings",false,"",false).equals("Applied settings"),"initial ready snapshot kept waiting footer");
        check(ShowcaseControls.currentReadMessage("Waiting for current headset settings",true,"",false).equals("Waiting for current headset settings"),"ordinary poll overwrote outcome");
        for(String message:new String[]{"Applied input settings","Settings changed on the headset. Read applied settings to continue.","Discard unapplied edits?","Headset applying input settings"})check(ShowcaseControls.currentReadMessage(message,false,"",false).equals(message),"recovery overwrote retained outcome: "+message);
        check(ShowcaseControls.currentReadMessage("Waiting for current headset settings",false,"pending-id",false).equals("Waiting for current headset settings"),"pending outcome overwritten");
        check(ShowcaseControls.currentReadMessage("Waiting for current headset settings",false,"",true).equals("Waiting for current headset settings"),"unknown outcome overwritten");
        check(ShowcaseControls.currentReadMessage("Applied settings",false,"",false).equals("Applied settings"),"recovery changed completed text");
        check(Arrays.equals(ShowcaseControls.TABS,new String[]{"Inputs","Visuals","Profiles","Setup"}),"primary navigation changed");
        HashSet<String> primary=new HashSet<>();for(String section:ShowcaseControls.VISUAL_PAGES)for(String key:ShowcaseVisualForm.sectionKeys(section))check(primary.add(key),"duplicate primary editor for "+key);
        check(primary.size()==12,"visual roots missing");
        if(args.length>0){JSONObject schema=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8")),properties=schema.getJSONObject("properties");check(primary.equals(properties.keySet()),"actual schema field has no unique editor");check(leaves(schema)==87,"visual leaf count changed without reviewed page inventory");}
        ShowcaseControls.DraftState draft=new ShowcaseControls.DraftState();draft.observe(status("1",1));draft.visualDirty=true;draft.visual.getJSONObject("transparency").put("opacity",0.7);
        draft.observe(status("1",2));check(draft.conflict(true),"concurrent edit silently rebased");check(draft.visual.getJSONObject("transparency").getDouble("opacity")==0.7,"poll discarded visual draft");check(draft.visualRevision==1,"poll changed visual base");
        draft.reload();check(!draft.visualDirty&&!draft.inputDirty&&!draft.conflict(true),"explicit applied reload did not reset draft");
        try{draft.observe(status("2",1));throw new AssertionError("new owner adopted old draft");}catch(IllegalStateException expected){}
        check(draft.epoch.equals("1"),"rejected new owner replaced authority");
        JSONObject value=visual(),presets=new JSONObject().put("test",new JSONObject("{\"transparency\":{\"opacity\":0.2,\"output_alpha_scale\":0.3,\"depth_suppression_strength\":0.4,\"rgb_alpha_coupling\":0.5},\"color\":{\"facing_attenuation_strength\":0.6}}"));
        ShowcaseVisualForm.fillPreset(value,presets,"test");check(ShowcaseVisualForm.presetMatches(value,presets),"owner preset fill mismatch");
        value.getJSONObject("transparency").put("opacity",0.20000000298023224);check(ShowcaseVisualForm.presetMatches(value,presets),"f32 projection falsely marked modified");
        value.getJSONObject("transparency").put("opacity",0.9);check(!ShowcaseVisualForm.presetMatches(value,presets),"manual coefficient edit not marked modified");
        String before=value.toString();try{ShowcaseVisualForm.fillPreset(value,null,"test");throw new AssertionError("missing preset metadata invented values");}catch(IllegalArgumentException expected){}check(value.toString().equals(before),"unavailable preset mutated draft");
        JSONObject malformed=new JSONObject(presets.toString());malformed.getJSONObject("test").getJSONObject("color").remove("facing_attenuation_strength");try{ShowcaseVisualForm.fillPreset(value,malformed,"test");throw new AssertionError("incomplete metadata admitted");}catch(IllegalArgumentException expected){}check(value.toString().equals(before),"rejected metadata partially filled draft");
        System.out.println("PASS production four-page/draft conflict/owner/preset manual edit rules"+(args.length>0?" and actual 87-leaf schema coverage":""));
    }
}
