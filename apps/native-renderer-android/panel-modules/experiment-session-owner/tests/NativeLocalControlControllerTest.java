package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Executes the production controller and extracted canonical patch builder; no Android/JNI substitute claims. */
public final class NativeLocalControlControllerTest {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static final class Task {final long due;final Runnable action;Task(long due,Runnable action){this.due=due;this.action=action;}}
    static final class Host implements NativeLocalControlController.Host {
        boolean live=true;long now;JSONObject status=new JSONObject();final ArrayList<JSONObject> particles=new ArrayList<JSONObject>(),breaths=new ArrayList<JSONObject>();
        final ArrayList<Task> tasks=new ArrayList<Task>();String outcome="";boolean wrongAcceptance;
        public boolean current(){return live;}public long nowMs(){return now;}public long wallMs(){return 1000;}
        public void schedule(Runnable task,long delay){tasks.add(new Task(now+delay,task));}
        public JSONObject particles(){return status;}
        public JSONObject breath()throws Exception{return new JSONObject().put("snapshot",new JSONObject().put("generation",7));}
        public JSONObject applyParticles(JSONObject command)throws Exception{particles.add(command);return new JSONObject().put("status","queued").put("candidate_revision",command.getLong("revision")+(wrongAcceptance?1:0));}
        public JSONObject applyBreath(JSONObject command)throws Exception{breaths.add(command);return new JSONObject().put("command_status","accepted");}
        public void outcome(String value){outcome=value;}
        void advance(long elapsed){long end=now+elapsed;for(;;){int index=-1;long next=Long.MAX_VALUE;for(int i=0;i<tasks.size();i++)if(tasks.get(i).due<next){index=i;next=tasks.get(i).due;}if(index<0||next>end)break;Task task=tasks.remove(index);now=task.due;task.action.run();}now=end;}
    }
    static void patches()throws Exception{
        Map<String,Object> edits=new LinkedHashMap<String,Object>();edits.put("size.mode","world-meters");edits.put("size.oscillation_percent",37.0);
        JSONObject patch=NativeLocalControlController.particlePatch(edits,12),p=patch.getJSONObject("private_particles"),size=p.getJSONObject("size");
        check(p.length()==1&&!p.has("material")&&!p.has("driver_values01")&&!p.has("heartbeat_pulse"),"size patch overwrote untouched fields");
        check(size.length()==3&&size.getBoolean("enabled")&&"world-meters".equals(size.getString("mode"))&&!size.has("world_meters"),"size patch did not preserve existing base value");
        check("field-patch".equals(patch.getString("update_mode"))&&patch.getJSONObject("apply").getLong("expected_effective_revision")==-1,"canonical envelope changed");
        edits.clear();edits.put("driver_values01.3",.25);edits.put("tracer.draw_slots_per_oscillator",Integer.valueOf(9));
        p=NativeLocalControlController.particlePatch(edits,13).getJSONObject("private_particles");
        check(p.getJSONObject("driver_values01").length()==1&&p.getJSONArray("driver_controls").length()==1&&p.getJSONArray("driver_controls").getJSONObject(0).getInt("target_slot")==3,"driver edit changed another slot");
        check(p.getJSONObject("tracer").get("draw_slots_per_oscillator") instanceof Integer,"integral tracer field became floating point");
        edits.clear();edits.put("heartbeat_pulse","disabled");check(NativeLocalControlController.particlePatch(edits,14).getJSONObject("private_particles").length()==1,"legacy heartbeat extraction changed siblings");
        edits.clear();edits.put("camera.enabled",true);try{NativeLocalControlController.particlePatch(edits,15);throw new AssertionError("unknown field admitted");}catch(IllegalArgumentException expected){}
    }
    static void lifecycle()throws Exception{
        Host host=new Host();NativeLocalControlController controller=new NativeLocalControlController(host);long first=controller.navigate();host.advance(1000);
        check(host.particles.isEmpty(),"hydration/navigation wrote settings");
        controller.edit(first,"driver_values01.0",.2,"sphere-radius-percent");host.advance(50);controller.edit(first,"driver_values01.2",.4,"sphere-radius-percent");host.advance(179);check(host.particles.isEmpty(),"debounce emitted before quiet interval");host.advance(1);
        check(host.particles.size()==1&&host.particles.get(0).getJSONObject("private_particles").getJSONObject("driver_values01").length()==2,"coalesced named edits lost a field");
        long revision=host.particles.get(0).getLong("revision");
        host.status=new JSONObject().put("status","applied").put("candidate_revision",revision).put("effective_revision",revision-1);host.advance(100);check(!host.outcome.startsWith("Renderer applied"),"acceptance or wrong effective revision treated as applied");
        host.status.put("effective_revision",revision);host.advance(100);check(host.outcome.equals("Renderer applied particle revision "+revision),"matching effective receipt not observed");
        controller.edit(first,"material","current-additive","sphere-radius-percent");long second=controller.navigate();host.advance(500);check(host.particles.size()==1,"old navigation emitted queued edit");
        controller.edit(first,"visual_scale",.6,"sphere-radius-percent");controller.edit(second,"heartbeat_pulse","polar-rr-orbit-boost","sphere-radius-percent");host.advance(500);check(host.particles.size()==1,"retired or excluded local field emitted command");
        controller.edit(second,"visual_scale",.7,"sphere-radius-percent");host.live=false;host.advance(500);check(host.particles.size()==1,"retired producer emitted command");host.live=true;
        controller.edit(second,"material","reference-material-emulation","sphere-radius-percent");controller.close();host.advance(500);check(host.particles.size()==1,"closed attachment emitted command");
    }
    static void receiptsAndBreath()throws Exception{
        Host host=new Host();NativeLocalControlController controller=new NativeLocalControlController(host);long page=controller.navigate();
        controller.edit(page,"visual_scale",.6,"sphere-radius-percent");host.advance(5000);check(host.outcome.contains("unknown")&&host.particles.size()==1,"missing receipt caused replay or false success");
        host.wrongAcceptance=true;controller.edit(page,"visual_scale",.7,"sphere-radius-percent");host.advance(5000);check(host.outcome.contains("unconfirmed")&&host.particles.size()==2,"wrong acceptance became effect");
        JSONObject selection=new JSONObject().put("source","controller").put("mapping","state").put("controller_projection","fixed-orientation").put("polar_projection","xz").put("inverted",true);
        controller.breath(page,"select",selection);check(host.breaths.size()==1&&"xz".equals(host.breaths.get(0).getString("polar_projection")),"required inert selection field lost");
        controller.breath(page,"cancel",null);check(host.breaths.get(1).getLong("generation")==7,"cancel not bound to actual generation");
        controller.breath(page,"start_calibration",null);controller.breath(page,"reset",null);int accepted=host.breaths.size();
        controller.breath(page,"configure_polar_state",null);controller.breath(page,"select",selection.put("source","polar-acc"));check(host.breaths.size()==accepted,"inactive capability emitted command");
        controller.navigate();controller.breath(page,"reset",null);check(host.breaths.size()==accepted,"old page calibration emitted command");
    }
    static void honestReadback()throws Exception{
        JSONObject snapshot=new JSONObject().put("status","active").put("output",new JSONObject().put("volume01",JSONObject.NULL).put("phase","inhale")).put("calibration_readback",new JSONObject().put("accepted_frames",4).put("target_frames",JSONObject.NULL));
        String summary=NativeLocalControlController.breathSummary(new JSONObject().put("snapshot",snapshot));
        check(summary.contains("Live volume: unavailable")&&!summary.contains("Live volume: 0.000")&&summary.contains("Frames: 4 / unavailable"),"optional native metrics fabricated zeros");
        snapshot.getJSONObject("output").put("volume01",0.0);check(NativeLocalControlController.breathSummary(new JSONObject().put("snapshot",snapshot)).contains("Live volume: 0.000"),"actual zero lost");
        JSONObject size=new JSONObject().put("enabled",false).put("mode","world-meters").put("world_meters",.2);
        JSONObject receipt=new JSONObject().put("status","applied").put("candidate_revision",8).put("effective_revision",8).put("private_particles",new JSONObject().put("size",size));
        check(NativeLocalControlController.particleSummary(receipt).contains("legacy payload envelope"),"disabled size displayed as effective override");
        size.put("enabled",true);check(NativeLocalControlController.particleSummary(receipt).contains("Base: 0.200 m"),"enabled native size missing");
        receipt.put("effective_revision",7);check(NativeLocalControlController.particleSummary(receipt).contains("remain provisional"),"mismatched revision displayed as effective");
    }
    static void everyDriverAndMaterial()throws Exception{
        for(int slot=0;slot<8;slot++){
            Host host=new Host();NativeLocalControlController controller=new NativeLocalControlController(host);long page=controller.navigate();
            double value=(slot+1)/10.0;controller.edit(page,"driver_values01."+slot,value,"sphere-radius-percent");host.advance(180);
            check(host.particles.size()==1,"driver did not reach actual production queue: "+slot);
            JSONObject command=host.particles.get(0),particle=command.getJSONObject("private_particles");
            check(particle.length()==2&&particle.getJSONObject("driver_values01").length()==1
                &&particle.getJSONObject("driver_values01").getDouble(Integer.toString(slot))==value,"driver changed siblings: "+slot);
            JSONObject control=particle.getJSONArray("driver_controls").getJSONObject(0);
            check(particle.getJSONArray("driver_controls").length()==1&&control.getInt("target_slot")==slot
                &&"direct".equals(control.getString("mode")),"wrong actual driver control: "+slot);
            long revision=command.getLong("revision");host.status=new JSONObject().put("status","applied").put("candidate_revision",revision).put("effective_revision",revision);host.advance(100);
            check(host.outcome.equals("Renderer applied particle revision "+revision),"driver acknowledgement missing: "+slot);
        }
        for(String preset:new String[]{"packaged-default","current-additive","premultiplied-alpha-over","premultiplied-alpha-over-depth","premultiplied-alpha-over-depth-facing","reference-material-emulation"}){
            Host host=new Host();NativeLocalControlController controller=new NativeLocalControlController(host);long page=controller.navigate();
            controller.edit(page,"material",preset,"sphere-radius-percent");host.advance(180);
            check(host.particles.size()==1,"material did not reach actual production queue: "+preset);
            JSONObject command=host.particles.get(0),particle=command.getJSONObject("private_particles");
            check(particle.length()==1&&particle.getJSONObject("material").length()==1
                &&preset.equals(particle.getJSONObject("material").getString("preset")),"material changed siblings: "+preset);
            long revision=command.getLong("revision");host.status=new JSONObject().put("status","applied").put("candidate_revision",revision).put("effective_revision",revision).put("private_particles",particle);host.advance(100);
            check(host.outcome.equals("Renderer applied particle revision "+revision)
                &&NativeLocalControlController.particleSummary(host.status).contains("Material: "+preset),"material acknowledgement missing: "+preset);
        }
    }
    static void sessionReadbackHeader(){
        NativeLocalControlController.ReadbackHeader header=new NativeLocalControlController.ReadbackHeader();
        long t=10_000_000_000L;
        check(header.text(t,false,0,0).contains("pending"),"new panel claimed a readback");
        header.receive("accepted","ready",4,t,t,true,4,t);
        check(header.text(t,true,4,t).equals("Session readback current · epoch 4"),"matching accepted witness not displayed");
        check(header.text(t+NativeLocalControlController.ReadbackHeader.DISPLAY_FRESH_NANOS+1,true,4,t).contains("stale"),"expired readback displayed current");
        check(header.text(t,true,5,t).contains("stale")&&header.text(t,true,4,t+1).contains("stale"),"different owner/read witness displayed current");
        check(header.text(t-1,true,4,t).contains("stale"),"clock reversal displayed current");
        header.failed();header.receive("accepted","ready",4,t,t,true,4,t);
        check(header.text(t,true,4,t).contains("unavailable"),"duplicate callback cleared a later failure");
        header.receive("accepted","ready",4,t+1,t+1,true,4,t+1);
        check(header.text(t+1,true,4,t+1).contains("current"),"new accepted read did not recover");
        header.receive("rejected","ready",4,t+2,t+2,true,4,t+2);
        check(header.text(t+2,true,4,t+2).contains("unavailable"),"rejected status claimed current");
        header.receive("accepted","initializing",4,t+3,t+3,true,4,t+3);
        check(header.text(t+3,true,4,t+3).contains("pending"),"initialization pending claimed current");
        header.receive("accepted","ready",4,t+4,t+4,false,4,t+4);
        check(header.text(t+4,true,4,t+4).contains("unavailable"),"unobserved projection claimed current");
        header.receive("accepted","ready",4,t+5,t+5,true,5,t+5);
        check(header.text(t+5,true,5,t+5).contains("unavailable"),"wrong epoch witness admitted");
        header.receive("accepted","ready",4,t+6,t+6,true,4,t+5);
        check(header.text(t+6,true,4,t+5).contains("unavailable"),"old completion witness admitted");
        header.receive("accepted","ready",4,t+8,t+7,true,4,t+8);
        check(header.text(t+7,true,4,t+8).contains("unavailable"),"future completion admitted");
        header.receive("accepted","ready",0,t+9,t+9,true,0,t+9);
        check(header.text(t+9,true,0,t+9).contains("unavailable"),"zero epoch admitted");
        header.receive("accepted","ready",4,0,t+10,true,4,0);
        check(header.text(t+10,true,4,0).contains("unavailable"),"missing completion admitted");
        header.reset();check(header.text(t+11,true,4,t+1).contains("pending"),"pause/reset retained old current state");
        check(NativeLocalControlController.ReadbackHeader.readFailure("status-unavailable:test")
            &&NativeLocalControlController.ReadbackHeader.readFailure("initialize-unavailable:test")
            &&NativeLocalControlController.ReadbackHeader.readFailure("status-poll-unavailable:test")
            &&!NativeLocalControlController.ReadbackHeader.readFailure("terminal-save-pending")
            &&!NativeLocalControlController.ReadbackHeader.readFailure(null),"read failure confused with terminal authority");
    }
    public static void main(String[] args)throws Exception{patches();lifecycle();receiptsAndBreath();honestReadback();everyDriverAndMaterial();sessionReadbackHeader();System.out.println("NativeLocalControlControllerTest PASS");}
}
