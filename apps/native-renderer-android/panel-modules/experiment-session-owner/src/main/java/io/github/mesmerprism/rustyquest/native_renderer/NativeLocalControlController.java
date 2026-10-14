package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local presentation admission only. Native typed queues and effective receipts own all settings. */
final class NativeLocalControlController {
    static final String PARTICLE_SCHEMA="rusty.quest.native_renderer.private_particle_dynamics.v1";
    static final String BREATH_SCHEMA="rusty.quest.breath_composition.command.v1";
    interface Host {
        boolean current(); long nowMs(); long wallMs();
        void schedule(Runnable task,long delayMs);
        JSONObject particles() throws Exception;
        JSONObject breath() throws Exception;
        JSONObject applyParticles(JSONObject command) throws Exception;
        JSONObject applyBreath(JSONObject command) throws Exception;
        void outcome(String text);
    }
    private static final AtomicLong REVISIONS=new AtomicLong();
    private final Host host;
    private final LinkedHashMap<String,Object> edits=new LinkedHashMap<String,Object>();
    private long navigation, editSerial, pendingRevision;
    private boolean closed;
    NativeLocalControlController(Host host){this.host=host;}
    long navigate(){if(navigation==Long.MAX_VALUE)throw new IllegalStateException("navigation-exhausted");navigation++;editSerial++;edits.clear();pendingRevision=0;return navigation;}
    boolean current(long page){return !closed&&host.current()&&page==navigation;}
    void close(){closed=true;navigate();}
    void edit(final long page,String field,Object value,String sizeMode){
        if(!current(page))return;
        if(field==null||"heartbeat_pulse".equals(field)){host.outcome("Unsupported local particle field");return;}
        if(field.startsWith("size."))edits.put("size.mode",sizeMode);
        edits.put(field,value);final long serial=++editSerial;
        host.schedule(new Runnable(){public void run(){if(current(page)&&serial==editSerial)flush(page);}},180);
    }
    private void flush(final long page){
        if(!current(page)||edits.isEmpty())return;
        Map<String,Object> captured=new LinkedHashMap<String,Object>(edits);edits.clear();
        try{
            long revision=nextRevision(host.wallMs());
            JSONObject command=particlePatch(captured,revision);
            if(!current(page))return;
            JSONObject response=host.applyParticles(command);
            if(!"queued".equals(response.optString("status"))||response.optLong("candidate_revision")!=revision)
                throw new IllegalStateException("particle-admission-unconfirmed-no-replay");
            pendingRevision=revision;host.outcome("Particle edit queued; waiting for renderer revision "+revision);
            poll(page,revision,host.nowMs()+4000);
        }catch(Exception failure){host.outcome("Particle edit not confirmed: "+failure.getMessage());}
    }
    private void poll(final long page,final long revision,final long deadline){
        if(!current(page)||pendingRevision!=revision)return;
        try{
            JSONObject value=host.particles();
            if(value!=null&&value.optLong("candidate_revision")==revision){
                if("applied".equals(value.optString("status"))&&value.optLong("effective_revision")==revision){
                    pendingRevision=0;host.outcome("Renderer applied particle revision "+revision);return;
                }
                if("rejected".equals(value.optString("status"))){pendingRevision=0;host.outcome("Renderer rejected particle revision "+revision+": "+value.optString("rejection_code","reason unavailable"));return;}
            }
        }catch(Exception unavailable){ /* A missing readback is never an applied receipt. */ }
        if(host.nowMs()>=deadline){pendingRevision=0;host.outcome("Particle outcome unknown; renderer receipt not observed. No replay.");return;}
        host.schedule(new Runnable(){public void run(){poll(page,revision,deadline);}},100);
    }
    void breath(long page,String operation,JSONObject selection){
        if(!current(page))return;
        try{
            JSONObject command=new JSONObject().put("schema",BREATH_SCHEMA).put("operation",operation);
            if("select".equals(operation)){
                if(selection==null||!"controller".equals(selection.optString("source")))throw new IllegalArgumentException("controller-only-selection");
                for(String field:new String[]{"source","mapping","controller_projection","polar_projection","inverted"})command.put(field,selection.get(field));
            }else if("cancel".equals(operation)){
                JSONObject snapshot=host.breath().getJSONObject("snapshot");long generation=snapshot.optLong("generation",0);
                if(generation<=0)throw new IllegalStateException("no-native-effective-calibration-generation");command.put("generation",generation);
            }else if(!"start_calibration".equals(operation)&&!"reset".equals(operation))throw new IllegalArgumentException("unsupported-local-breath-operation");
            if(current(page)){JSONObject response=host.applyBreath(command);host.outcome("Breath command: "+response.optString("command_status","outcome unknown")+". Read effective state below.");}
        }catch(Exception error){host.outcome("Breath command not confirmed: "+error.getMessage());}
    }
    private static String metric(JSONObject object,String key,int digits){
        Object raw=object==null?null:object.opt(key);if(!(raw instanceof Number))return "unavailable";
        double value=((Number)raw).doubleValue();if(!Double.isFinite(value))return "unavailable";
        return digits<0?raw.toString():String.format(java.util.Locale.ROOT,"%."+digits+"f",value);
    }
    static String breathSummary(JSONObject receipt){
        JSONObject snapshot=receipt.optJSONObject("snapshot");if(snapshot==null)return "Breath state unavailable";
        JSONObject effective=snapshot.optJSONObject("effective"),calibration=snapshot.optJSONObject("calibration_readback"),output=snapshot.optJSONObject("output");
        String value="State: "+snapshot.optString("status","unavailable");
        if(effective!=null)value+="\nSource: "+effective.optString("source","unavailable")+" | Mapping: "+effective.optString("mapping","unavailable")+"\nProjection: "+effective.optString("controller_projection","unavailable")+" | Inverted: "+effective.optBoolean("inverted",false);
        if(calibration!=null){String progress=metric(calibration,"progress01",3);value+="\nCalibration: "+calibration.optString("lifecycle","unavailable")+" | Progress (0-1): "+progress+" | Frames: "+metric(calibration,"accepted_frames",-1)+" / "+metric(calibration,"target_frames",-1);}
        if(output!=null)value+="\nLive volume: "+metric(output,"volume01",3)+" | Phase: "+output.optString("phase","unavailable");
        else value+="\nLive output unavailable";
        return value;
    }
    static String particleSummary(JSONObject receipt){
        String value="Renderer status: "+receipt.optString("status","unavailable")+"\nCandidate revision: "+metric(receipt,"candidate_revision",-1)+" | Effective revision: "+metric(receipt,"effective_revision",-1);
        JSONObject particles=effectiveParticles(receipt);
        if(particles==null)return value+"\nNo matching applied receipt; displayed controls remain provisional.";
        JSONObject size=particles.optJSONObject("size"),material=particles.optJSONObject("material");
        if(size!=null&&Boolean.TRUE.equals(size.opt("enabled"))){boolean meters="world-meters".equals(size.optString("mode"));value+="\nEffective size: "+size.optString("mode")+" | Base: "+metric(size,meters?"world_meters":"sphere_radius_percent",3)+(meters?" m":"%")+" | Oscillation: "+metric(size,"oscillation_percent",1)+"%";}
        else value+="\nEffective size: legacy payload envelope";
        return value+"\nMaterial: "+(material==null?"packaged-default":material.optString("preset","packaged-default"));
    }
    static long nextRevision(long wall){
        for(;;){long previous=REVISIONS.get();if(previous==Long.MAX_VALUE)throw new IllegalStateException("revision-exhausted");
            long next=Math.max(Math.max(1,wall),previous+1);if(REVISIONS.compareAndSet(previous,next))return next;}
    }
    static JSONObject effectiveParticles(JSONObject status){
        return status!=null&&"applied".equals(status.optString("status"))&&status.optLong("candidate_revision")>0
            &&status.optLong("candidate_revision")==status.optLong("effective_revision")?status.optJSONObject("private_particles"):null;
    }
    /** Extracted canonical field-patch ABI; only named edited fields are emitted. */
    static JSONObject particlePatch(Map<String,Object> fields,long revision)throws Exception{
        if(fields.isEmpty()||revision<=0)throw new IllegalArgumentException("empty-or-invalid-particle-patch");
        JSONObject particles=new JSONObject(),size=new JSONObject(),drivers=new JSONObject(),tracer=new JSONObject();JSONArray controls=new JSONArray();
        for(Map.Entry<String,Object> edit:fields.entrySet()){
            String field=edit.getKey();Object value=edit.getValue();
            if("visual_scale".equals(field)||"world_anchor_scale_m".equals(field))particles.put(field,value);
            else if(field.startsWith("size.")){
                String key=field.substring(5);
                if(!"mode".equals(key)&&!"world_meters".equals(key)&&!"sphere_radius_percent".equals(key)&&!"oscillation_percent".equals(key))throw new IllegalArgumentException("unsupported-size-field");
                size.put("enabled",true).put(key,value);
            }else if(field.startsWith("driver_values01.")){
                int slot=Integer.parseInt(field.substring(16));if(slot<0||slot>=8)throw new IllegalArgumentException("driver-slot-range");
                drivers.put(Integer.toString(slot),value);controls.put(new JSONObject().put("target_slot",slot).put("mode","direct"));
            }else if(field.startsWith("tracer.")){
                String key=field.substring(7);if(!"draw_slots_per_oscillator".equals(key)&&!"lifetime_seconds".equals(key)&&!"copies_per_second".equals(key))throw new IllegalArgumentException("unsupported-tracer-field");tracer.put(key,value);
            }else if("material".equals(field))particles.put(field,new JSONObject().put("preset",value));
            else if("heartbeat_pulse".equals(field))particles.put(field,new JSONObject().put("mode",value)); // Existing legacy caller only; absent from local UI.
            else throw new IllegalArgumentException("unsupported-particle-field:"+field);
        }
        if(size.length()>0)particles.put("size",size);
        if(drivers.length()>0)particles.put("driver_values01",drivers).put("driver_controls",controls);
        if(tracer.length()>0)particles.put("tracer",tracer);
        return new JSONObject().put("schema",PARTICLE_SCHEMA).put("profile_id","same-apk-private-particle-field-patch")
            .put("revision",revision).put("update_mode","field-patch")
            .put("source",new JSONObject().put("surface","same_apk_panel").put("transport","jni_live_queue"))
            .put("private_particles",particles).put("apply",new JSONObject().put("mode","apply-on-next-safe-frame").put("expected_effective_revision",-1));
    }
}
