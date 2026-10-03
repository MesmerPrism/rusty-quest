package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;
import org.json.JSONObject;

/** Actual shared handler/state; only the Android executor is modeled. */
public final class BridgeControllerTest {
    static int cases;
    static void check(boolean value) { if (!value) throw new AssertionError("production controller case " + (cases + 1)); cases++; }
    interface Action { void run() throws Exception; }
    static void rejects(Action action) throws Exception { boolean denied=false; try{action.run();}catch(SecurityException|IllegalArgumentException expected){denied=true;}check(denied); }
    static final class Port implements BridgeController.Port {
        long clock=100;boolean permissions=true,hub=true,startFails,stopFails,stopAccepted=true;int starts,stops,hubReads;
        public long now(){return clock;}
        public boolean permissionsReady(){return permissions;}
        public void requireHubCurrent() throws Exception{hubReads++;if(!hub)throw new Exception("opaque detail must not escape");}
        public void start() throws Exception{starts++;if(startFails)throw new Exception("opaque start failure");}
        public boolean stop() throws Exception{stops++;if(stopFails)throw new Exception("opaque stop failure");return stopAccepted;}
    }
    public static void main(String[] ignored) throws Exception {
        for(String method:new String[]{"enable","disable","status"})BridgeController.authorize(2000,method,null,false);
        check(true);
        rejects(()->BridgeController.authorize(0,"enable",null,false));
        rejects(()->BridgeController.authorize(12345,"disable",null,false));
        rejects(()->BridgeController.authorize(2000,"status","payload",false));
        rejects(()->BridgeController.authorize(2000,"enable",null,true));
        rejects(()->BridgeController.authorize(2000,"start-Hub",null,false));
        BridgeController.State state=new BridgeController.State();Port port=new Port();BridgeController ui=new BridgeController(port,state),cli=new BridgeController(port,state);
        JSONObject v=cli.status();check(!v.getBoolean("service_live")&&!v.getBoolean("carrier_ready_now")&&v.getLong("generation")==0&&port.starts==0);
        port.permissions=false;v=cli.enable();check(!v.getBoolean("request_accepted")&&port.starts==0&&port.hubReads==0);
        port.permissions=true;port.hub=false;v=ui.enable();check(!v.getBoolean("request_accepted")&&port.starts==0&&v.getString("outcome").equals("hub_readiness_denied"));
        port.hub=true;v=cli.invoke("enable");long first=v.getLong("generation");check(v.getBoolean("request_accepted")&&!v.getBoolean("carrier_ready_now")&&!v.getBoolean("service_observed")&&port.starts==1);
        check(state.serviceStarted(first,900100)&&!ui.status().getBoolean("carrier_ready_now"));
        state.advertising(first);check(cli.status().getBoolean("carrier_ready_now")&&cli.status().getBoolean("advertising_callback_confirmed"));
        v=ui.enable();check(!v.getBoolean("request_accepted")&&v.getLong("generation")==first&&v.getLong("deadline_elapsed_ms")==900100&&port.starts==1);
        port.clock=900100;v=cli.status();check(!v.getBoolean("carrier_ready_now")&&v.getBoolean("advertising_callback_confirmed"));port.clock=100;
        v=ui.disable();check(v.getBoolean("request_accepted")&&v.getBoolean("service_live")&&!v.getBoolean("carrier_ready_now")&&port.stops==1);
        state.advertising(first);check(!cli.status().getBoolean("advertising_callback_confirmed"));
        state.destroyed(first);check(!cli.status().getBoolean("service_live")&&cli.status().getBoolean("service_observed"));
        v=cli.disable();check(!v.getBoolean("request_accepted")&&port.stops==1);
        v=cli.enable();long second=v.getLong("generation");check(second==first+1&&port.starts==2&&v.getLong("deadline_elapsed_ms")==0);
        state.advertising(first);state.failure(first,"advertising_failed");state.destroyed(first);check(cli.status().getString("carrier_state").equals("start_requested")&&!cli.status().getBoolean("service_observed"));
        check(!state.serviceStarted(first,999999)&&state.serviceStarted(second,900200));
        state.advertising(second);state.failure(second,"advertising_failed");v=cli.status();check(!v.getBoolean("carrier_ready_now")&&v.getBoolean("service_live")&&v.getString("error_code").equals("advertising_failed"));
        state.destroyed(second);check(!cli.status().getBoolean("service_live")&&cli.status().getString("carrier_state").equals("failed"));
        BridgeController.State failed=new BridgeController.State();Port bad=new Port();bad.startFails=true;BridgeController badController=new BridgeController(bad,failed);v=badController.enable();check(!v.getBoolean("request_accepted")&&v.getString("error_code").equals("start_dispatch_failed")&&!v.getBoolean("service_observed"));
        bad.startFails=false;v=badController.enable();check(!v.getBoolean("request_accepted")&&bad.starts==1); // Unknown dispatch cannot be replayed.
        failed.failure(failed.generation(),"secret arbitrary details");check(badController.status().getString("error_code").equals("service_failure_unknown"));
        bad.stopAccepted=false;v=badController.disable();check(!v.getBoolean("request_accepted")&&v.getString("carrier_state").equals("stop_requested"));
        bad.stopFails=true;v=badController.disable();check(!v.getBoolean("request_accepted")&&v.getString("outcome").equals("stop_dispatch_failed_unknown"));
        check(!new BridgeController.State().serviceStarted(123,900000));
        BridgeController.State process=new BridgeController.State();check(!process.processInstance.equals(state.processInstance));
        v=cli.status();check(!v.getBoolean("controller_authority_claimed")&&!v.getBoolean("pairing_secret_in_receipt")&&!v.getBoolean("production_eligible")&&!v.getBoolean("radio_cleanup_qualified"));
        rejects(()->cli.invoke("pair"));
        System.out.println("PASS "+cases+" production cases");
    }
}
