package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;

/** Real callback/re-entry/thread tests; no Android or synthetic applied renderer witness. */
public final class ProductPanelOperatorTest {
    private static int invocations;
    private static boolean presented = true, failAfterEffect;
    private static String reenter;
    private static JSONObject reentrantResult;
    private static final ProductPanelOperator.Owner OWNER = new ProductPanelOperator.Owner() {
        public boolean available() { return presented; }
        public JSONObject observe() throws Exception { return new JSONObject().put("revision",invocations); }
        public JSONObject invoke(String action, JSONObject parameters) throws Exception {
            invocations++;
            if (reenter != null) reentrantResult = new JSONObject(ProductPanelOperator.dispatch(reenter));
            if (failAfterEffect) throw new IllegalStateException("acknowledgement-lost");
            return new JSONObject().put("status","queued").put("candidate_revision",invocations);
        }
    };
    public static void main(String[] ignored) throws Exception {
        check("rejected".equals(call(status()).getString("status")));
        AutoCloseable first = ProductPanelOperator.register(OWNER);
        String token = call(status()).getString("owner_token");
        String command = invoke(token,"0000000000000001","scroll",new JSONObject().put("delta_y",100));
        reenter = command;
        JSONObject result = call(command);
        check(invocations==1 && "dispatched".equals(result.getString("status")));
        check("pending".equals(reentrantResult.getString("status")));
        reenter = null;
        check("queued".equals(result.getJSONObject("result").getString("status")));
        check(call(command).getJSONObject("observation").getInt("revision")==1 && invocations==1);
        JSONObject conflict = new JSONObject(command); conflict.getJSONObject("parameters").put("delta_y",101);
        check("request-id-conflict-no-replay".equals(call(conflict.toString()).getString("reason")) && invocations==1);
        failAfterEffect=true;
        String uncertain=invoke(token,"0000000000000002","network-stop",new JSONObject());
        check("unknown".equals(call(uncertain).getString("status")) && invocations==2);
        check("unknown".equals(call(uncertain).getString("status")) && invocations==2);
        JSONObject query=new JSONObject().put("schema",ProductPanelOperator.COMMAND_SCHEMA).put("operation","result")
            .put("owner_token",token).put("request_id","0000000000000002");
        check("unknown".equals(call(query.toString()).getString("status")) && invocations==2);
        failAfterEffect=false;
        presented=false;
        check("panel-owner-not-presented".equals(call(invoke(token,"0000000000000003","network-stop",new JSONObject())).getString("reason")));
        presented=true;
        final String[] other={null};
        Thread thread=new Thread(new Runnable(){public void run(){other[0]=ProductPanelOperator.dispatch(command);}});
        thread.start();thread.join();
        check("owner-thread-required".equals(new JSONObject(other[0]).getString("reason")) && invocations==2);
        check("rejected".equals(call(invoke(token,"0000000000000004","scroll",new JSONObject().put("delta_y",Long.MIN_VALUE))).getString("status")));
        check("rejected".equals(call(invoke(token,"0000000000000004","network-enroll",new JSONObject().put("trusted_lan",true).put("consent",false))).getString("status")));
        check("rejected".equals(call(invoke(token,"0000000000000004","network-stop",new JSONObject().put("arbitrary",true))).getString("status")));
        check("rejected".equals(call(invoke(token,"0000000000000004","unknown",new JSONObject())).getString("status")));
        check("rejected".equals(call(new JSONObject(command).put("owner_token","old-owner").toString()).getString("status")));
        AutoCloseable replacement=ProductPanelOperator.register(OWNER);
        String replacementToken=call(status()).getString("owner_token");
        first.close();
        check(call(status()).getString("owner_token").equals(replacementToken));
        check("panel-owner-replaced".equals(call(command).getString("reason")) && invocations==2);
        check("rejected".equals(call(invoke(replacementToken,"0000000000000005","panel-visible",new JSONObject().put("visible","true"))).getString("status")) && invocations==2);
        check("dispatched".equals(call(invoke(replacementToken,"0000000000000005","panel-visible",new JSONObject().put("visible",true))).getString("status")) && invocations==3);
        replacement.close();
        check("rejected".equals(call(status()).getString("status")));
        System.out.println("ProductPanelOperatorTest PASS actual callbacks, re-entry, lost acknowledgement/no replay, owner replacement, thread and hostile shapes");
    }
    private static String status() throws Exception { return new JSONObject().put("schema",ProductPanelOperator.COMMAND_SCHEMA).put("operation","status").toString(); }
    private static String invoke(String token,String id,String action,JSONObject parameters)throws Exception {
        return new JSONObject().put("schema",ProductPanelOperator.COMMAND_SCHEMA).put("operation","invoke")
            .put("owner_token",token).put("request_id",id).put("action",action).put("parameters",parameters).toString();
    }
    private static JSONObject call(String raw)throws Exception { return new JSONObject(ProductPanelOperator.dispatch(raw)); }
    private static void check(boolean value){if(!value)throw new AssertionError();}
}
