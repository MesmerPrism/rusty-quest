package io.github.mesmerprism.rustyquest.native_renderer;

import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event;
import java.util.*;
import org.json.JSONObject;
import io.github.mesmerprism.rustymanifold.broker.HubSurfaceDescriptor;

public final class ExperimentSessionHubProviderTest {
    static int checks;
    static void check(boolean yes, String why) { checks++; if (!yes) throw new AssertionError(why); }
    static final class Port implements ExperimentSessionHubProvider.Platform {
        ExperimentSessionHubProvider p; long time; int binds, unbinds, links;
        final List<Integer> sent = new ArrayList<>();
        final List<Map<String,Object>> fields = new ArrayList<>();
        final Map<String,Runnable> tasks = new HashMap<>();
        boolean sendWorks = true;
        public long now() { return time; }
        public void bind(long g) { binds++; p.event(Event.bindReturned(g,true,time)); p.event(Event.connected(g,time)); }
        public void link(long g) { links++; p.event(Event.deathLinked(g,time)); }
        public void unlink(long g) { }
        public void unbind(long g) { unbinds++; tasks.clear(); }
        public boolean send(long g,int what,Map<String,Object> data) { sent.add(what);fields.add(new LinkedHashMap<>(data));return sendWorks; }
        public void schedule(String key,long deadline,Runnable task) { tasks.put(key,task); }
        public void cancel(String key) { tasks.remove(key); }
        void answer(String body) { answer(body, "provider.epoch.test"); }
        void answer(String body,String epoch) {
            Map<String,Object> f = fields.get(fields.size()-1);
            p.reply(p.state().getBindingGeneration(),sent.get(sent.size()-1),(Long)f.get("session_generation"),
                (String)f.get("correlation_id"),epoch,"",body);
        }
    }
    static Port create() {
        return create(null);
    }
    static Port create(ExperimentSessionPanelCoordinator coordinator) {
        Port port=new Port();
        if (coordinator!=null) port.time=1L;
        port.p=new ExperimentSessionHubProvider(port,7L,"trial",9L,()->
            coordinator==null?null:coordinator.nativeStatusSnapshot(port.time*1000000L));
        return port;
    }
    static void admitted(Port p) {
        p.p.start();
        p.answer("{\"runtime\":{\"admission_snapshot\":{\"authority_revision\":1}}}");
        p.answer("{\"receipt\":{\"applied\":true,\"resulting_authority_revision\":2,\"token\":{\"token_id\":\"token.real.owner.fixture\"}}}");
        p.answer("{\"receipt\":{\"applied\":true,\"resulting_authority_revision\":3}}");
    }
    public static void main(String[] args) throws Exception {
        Port p=create(); check(p.sent.isEmpty()&&p.binds==0,"constructor inert");
        admitted(p); check(p.sent.equals(Arrays.asList(6,1,2,20)),"actual reducer ordered admission operations");
        JSONObject registration=new JSONObject((String)p.fields.get(3).get("surface_registration_json"));
        check(registration.getJSONArray("commands").length()==0,"no synthetic command");
        check(registration.getString("surface_contract_sha256").equals(ExperimentSessionHubProvider.contractSha()),"canonical owned digest");
        check(ExperimentSessionHubProvider.contractSha().equals(HubSurfaceDescriptor.contractSha256(
            ExperimentSessionHubProvider.SURFACE,ExperimentSessionHubProvider.LABEL,
            ExperimentSessionHubProvider.DESCRIPTION,Collections.emptyList())),"actual Hub canonical contract agrees");
        check(registration.getString("surface_id").equals("surface.experimenter.status"),"distinct owned identity");
        check(registration.getJSONObject("state").length()==1,"one fixed state scalar");
        check(registration.getJSONObject("state").getString("experiment_status_observation").contains("\"status\":null"),"unavailable snapshot stays unavailable");
        check(p.fields.stream().noneMatch(f->f.containsKey("package")||f.containsKey("signer")||f.containsKey("uid")),"no fabricated OS subject");
        p.answer("{\"applied\":true}"); check(p.p.state().isRegistered(),"only registration reply starts publishing");
        Runnable poll=p.tasks.get("observation_poll"); p.time=1000;poll.run();
        check(p.sent.get(p.sent.size()-1)==21,"actual registered poll sends state update only");
        p.p.close();check(p.unbinds==1&&p.sent.get(p.sent.size()-1)==22,"close unregisters own surface and unbinds");
        int count=p.sent.size();poll.run();check(count==p.sent.size(),"saved poll after close cannot publish");
        p.p.start();check(p.binds==1,"no automatic reopen after close");
        Port denied=create();denied.p.start();denied.answer("{\"runtime\":{\"admission_snapshot\":{\"authority_revision\":1}}}");
        denied.answer("{\"receipt\":{\"applied\":false}}");check(!denied.sent.contains(20)&&denied.unbinds==1,"no grant rejection bypass");
        Port stale=create();stale.p.start();
        stale.p.reply(999,6,1,"stale","provider.epoch.test","","{}");check(stale.sent.size()==1,"stale generation reply ignored");
        Map<String,Object> f=stale.fields.get(0);
        stale.p.reply(stale.p.state().getBindingGeneration(),1,(Long)f.get("session_generation"),(String)f.get("correlation_id"),"provider.epoch.test","","{}");
        check(stale.sent.size()==1,"wrong message kind ignored");
        stale.time=3001; stale.answer("{\"runtime\":{\"admission_snapshot\":{\"authority_revision\":1}}}");
        check(stale.sent.equals(Arrays.asList(6,6)),"late reply before queued timer cannot issue token");
        Port epoch=create();epoch.p.start();epoch.answer("{\"runtime\":{\"admission_snapshot\":{\"authority_revision\":1}}}");
        epoch.answer("{\"receipt\":{\"applied\":true,\"resulting_authority_revision\":2,\"token\":{\"token_id\":\"token.owner\"}}}","provider.epoch.other");
        check(!epoch.sent.contains(2)&&epoch.unbinds==1,"broker epoch replacement denied");
        Port timeout=create();admitted(timeout);String first=(String)timeout.fields.get(3).get("surface_registration_json");
        timeout.time=3001;timeout.tasks.get((String)timeout.fields.get(3).get("correlation_id")).run();
        check(first.equals(timeout.fields.get(4).get("surface_registration_json")),"equivalent registration retry exact cached bytes");
        check(timeout.fields.get(3).get("registration_id").equals(timeout.fields.get(4).get("registration_id")),"same registration identity retained");
        timeout.time=6002;timeout.tasks.get((String)timeout.fields.get(4).get("correlation_id")).run();
        check(!timeout.p.state().isRegistered()&&timeout.unbinds==1,"ambiguous registration terminates without publishing");
        Port deniedTransport=create();deniedTransport.sendWorks=false;deniedTransport.p.start();
        check(deniedTransport.unbinds==1&&!deniedTransport.p.state().isRegistered(),"send failure cannot register");
        Port bad=create();bad.p.start();bad.answer("not JSON");
        check(bad.unbinds==1&&!bad.sent.contains(1),"malformed real-boundary response cannot issue token");
        ExperimentSessionPanelCoordinator coordinator=new ExperimentSessionPanelCoordinator();
        coordinator.acceptNativeReadback(9L,new ExperimentSessionPanelCoordinator.NativeReceipt("",true,true,1L,2L,
            "idle","none",false,false,"ready",true,0L,0L,0L,0L,true,true,false,0L,0L,0L,"none",0L,"host"),1000000L,1000000L);
        Port status=create(coordinator);admitted(status);status.answer("{\"applied\":true}");
        status.time=1000;status.tasks.get("observation_poll").run();
        String firstObservation=new JSONObject((String)status.fields.get(status.fields.size()-1).get("state_json"))
            .getString("experiment_status_observation");
        check(firstObservation.contains("\"source_state\":\"fresh\""),"actual same-lock producer composed into provider state");
        status.time=2000;status.tasks.get("observation_poll").run();
        String nextObservation=new JSONObject((String)status.fields.get(status.fields.size()-1).get("state_json"))
            .getString("experiment_status_observation");
        check(new JSONObject(firstObservation).getLong("sequence")==new JSONObject(nextObservation).getLong("sequence"),"poll does not fabricate new native sequence");
        coordinator.openDeveloper(coordinator.allocateRouteEvent());status.time=3000;status.tasks.get("observation_poll").run();
        check(((String)status.fields.get(status.fields.size()-1).get("state_json")).contains("\\\"status\\\":null"),"local change propagates unavailable projection");
        System.out.println("ExperimentSessionHubProviderTest PASS controls="+checks+" actual reducer and driver; modeled Binder owner replies, no grant/network/device");
    }
}
