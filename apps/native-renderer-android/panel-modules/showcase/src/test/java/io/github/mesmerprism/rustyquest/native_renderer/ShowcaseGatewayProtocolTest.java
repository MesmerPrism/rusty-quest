package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;

/** Host checks against the exact generated maintained protocol and guest registry. */
public final class ShowcaseGatewayProtocolTest {
    static final String CONTROLLER="11111111111111111111111111111111",DEVICE="22222222222222222222222222222222",KEY="3333333333333333333333333333333333333333333333333333333333333333";
    static int calls;
    static JSONObject request(int number,String action)throws Exception {
        String nonce=String.format("%032x",number),payload="{}";
        return new JSONObject().put("v",1).put("controller",CONTROLLER).put("epoch","42").put("nonce",nonce).put("action",action).put("payload",payload).put("mac",ShowcaseGatewayProtocol.hmac(KEY,"RQG1|"+CONTROLLER+"|42|"+nonce+"|"+action+"|"+payload));
    }
    interface Trial{void run()throws Exception;}
    static void denied(Trial action)throws Exception {
        int before=calls;try{action.run();throw new AssertionError("invalid request admitted");}catch(SecurityException|IllegalArgumentException expected){}
        if(before!=calls)throw new AssertionError("rejected request reached owner");
    }
    static ShowcaseGatewayProtocol protocol(){return new ShowcaseGatewayProtocol(CONTROLLER,KEY,DEVICE,42,new ShowcaseGatewayProtocol.Owner(){public JSONObject exchange(String action,JSONObject payload)throws Exception{calls++;return new JSONObject().put("product","visual-showcase");}});}
    public static void main(String[]args)throws Exception {
        ShowcaseGatewayProtocol protocol=protocol();
        JSONObject reply=protocol.exchangeLan(request(1,"status"));
        String expected=ShowcaseGatewayProtocol.hmac(KEY,"RQG1-REPLY|"+CONTROLLER+"|"+DEVICE+"|42|"+reply.getString("nonce")+"|status|"+reply.getString("payload"));
        if(!expected.equals(reply.getString("mac"))||calls!=1)throw new AssertionError("reply authentication changed");
        denied(()->protocol.exchangeLan(request(1,"status")));
        denied(()->protocol.exchangeLan(request(2,"status").put("epoch","43")));
        denied(()->protocol.exchangeLan(request(2,"status").put("controller",DEVICE)));
        denied(()->protocol.exchangeLan(request(2,"status").put("payload","{\"changed\":true}")));
        denied(()->protocol.exchangeLan(request(2,"status").put("v",1.0)));
        denied(()->protocol.exchangeLan(request(2,"status").put("extra",true)));
        denied(()->protocol.exchangeLan(request(3,"recordings")));
        denied(()->protocol.exchangeLan(request(4,"breath-monitor")));
        denied(()->protocol.exchangeLan(request(5,"shell")));
        protocol.exchangeLan(request(6,"command"));protocol.exchangeLan(request(7,"receipt"));
        protocol.retire();denied(()->protocol.exchangeLan(request(8,"status")));
        ShowcaseGuestRegistry registry=new ShowcaseGuestRegistry();
        for(int i=0;i<8;i++)registry.add("guest"+i,protocol(),1000);
        try{registry.add("overflow",protocol(),1000);throw new AssertionError("guest budget exceeded");}catch(ShowcaseGuestRegistry.CapacityException expectedCapacity){}
        ShowcaseGuestRegistry.Guest first=registry.find("guest0",119999);
        registry.authenticated("guest0",first.protocol,119999);
        if(registry.find("guest1",121000)!=null)throw new AssertionError("read-only find extended idle lease");
        if(registry.find("guest0",121000)==null)throw new AssertionError("authenticated guest expired too early");
        registry.clear();if(registry.find("guest0",121001)!=null)throw new AssertionError("retired guest retained");
        System.out.println("PASS showcase RQG1 exact payload/HMAC/replay/lifetime/retirement, excluded monitor+recordings routes, bounded authenticated-only guest lifetime");
    }
}
