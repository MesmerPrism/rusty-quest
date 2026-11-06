package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;

public final class ExperimentSessionGatewayProtocolTest {
    static final String CONTROLLER="11111111111111111111111111111111",DEVICE="22222222222222222222222222222222",
        KEY="3333333333333333333333333333333333333333333333333333333333333333";
    static int calls;
    static JSONObject request(String nonce,String action,String payload)throws Exception {
        return new JSONObject().put("v",1).put("controller",CONTROLLER).put("epoch","42").put("nonce",nonce).put("action",action).put("payload",payload)
            .put("mac",ExperimentSessionGatewayProtocol.hmac(KEY,"RQG1|"+CONTROLLER+"|42|"+nonce+"|"+action+"|"+payload));
    }
    interface Trial{void run()throws Exception;}
    static void denied(Trial trial)throws Exception {int before=calls;try{trial.run();throw new AssertionError("admitted invalid request");}catch(SecurityException|IllegalArgumentException expected){}if(calls!=before)throw new AssertionError("denied request reached owner");}
    public static void main(String[]args)throws Exception {
        if(ExperimentSessionGatewayProtocol.lanAllowed(true,true,true,false))throw new AssertionError("relay-only enrollment opened HTTP");
        if(ExperimentSessionGatewayProtocol.lanAllowed(false,true,true,true)||ExperimentSessionGatewayProtocol.lanAllowed(true,false,true,true)||ExperimentSessionGatewayProtocol.lanAllowed(true,true,false,true))throw new AssertionError("LAN missing mandatory activation gate");
        if(!ExperimentSessionGatewayProtocol.lanAllowed(true,true,true,true))throw new AssertionError("explicit trusted LAN not admitted");
        ExperimentSessionGatewayProtocol protocol=new ExperimentSessionGatewayProtocol(CONTROLLER,KEY,DEVICE,42,(action,payload)->{calls++;return new JSONObject().put("id",payload.optString("id")).put("state","pending");});
        String nonce="44444444444444444444444444444444",body="{\"id\":\"abcdef0123456789\"}";
        JSONObject request=request(nonce,"receipt",body),reply=protocol.exchange(request);
        String expected=ExperimentSessionGatewayProtocol.hmac(KEY,"RQG1-REPLY|"+CONTROLLER+"|"+DEVICE+"|42|"+nonce+"|receipt|"+reply.getString("payload"));
        if(!expected.equals(reply.getString("mac"))||calls!=1)throw new AssertionError("reply authentication or owner dispatch");
        denied(()->protocol.exchange(request));
        denied(()->protocol.exchange(request("55555555555555555555555555555555","status","{}").put("epoch","43")));
        denied(()->protocol.exchange(request("55555555555555555555555555555555","status","{}").put("payload","{\"extra\":true}")));
        denied(()->protocol.exchange(request("55555555555555555555555555555555","status","{}").put("controller",DEVICE)));
        denied(()->protocol.exchange(request("55555555555555555555555555555555","status","{}").put("extra",true)));
        denied(()->protocol.exchange(request("55555555555555555555555555555555","shell","{}")));
        denied(()->protocol.exchange(request("55555555555555555555555555555555","status","{}").put("v",1.0)));
        protocol.exchange(request("55555555555555555555555555555555","status","{}"));
        JSONObject monitor=protocol.exchange(request("88888888888888888888888888888888","breath-monitor","{\"run\":\"\",\"after\":0}"));
        if(!"breath-monitor".equals(monitor.getString("action")))throw new AssertionError("read-only monitor action unavailable");
        if(!"breath-monitor".equals(ExperimentSessionGatewayProtocol.monitorQuery(new JSONObject().put("run","").put("after",0)).getString("operation")))throw new AssertionError("monitor query not closed");
        denied(()->ExperimentSessionGatewayProtocol.monitorQuery(new JSONObject().put("run","").put("after",-1)));
        denied(()->ExperimentSessionGatewayProtocol.monitorQuery(new JSONObject().put("run","").put("after",0.0)));
        denied(()->ExperimentSessionGatewayProtocol.monitorQuery(new JSONObject().put("run","other").put("after",0)));
        denied(()->ExperimentSessionGatewayProtocol.monitorQuery(new JSONObject().put("run","").put("after",0).put("operation","pause")));
        denied(()->protocol.exchange(request("99999999999999999999999999999999","breath-monitor","{}").put("payload","{\"after\":1}")));
        protocol.retire();denied(()->protocol.exchange(request("66666666666666666666666666666666","status","{}")));
        // A process restart gets a new gateway epoch even if its native epoch repeats.
        ExperimentSessionGatewayProtocol restarted=new ExperimentSessionGatewayProtocol(CONTROLLER,KEY,DEVICE,43,(action,payload)->{calls++;return payload;});
        denied(()->restarted.exchange(request("77777777777777777777777777777777","status","{}")));
        System.out.println("PASS relay-only no HTTP/default-off consent conjunction; production gateway request/reply HMAC, exact payload, nonce replay, epoch/process restart, closed fields, wrong key scope, retire-before-dispatch");
    }
}
