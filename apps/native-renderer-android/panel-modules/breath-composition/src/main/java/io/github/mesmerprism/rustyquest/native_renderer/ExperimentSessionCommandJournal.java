package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** One bounded app-owner ID journal shared by authenticated BLE and network admission. */
final class ExperimentSessionCommandJournal {
    private final Map<String,String> commands=new LinkedHashMap<>();
    private final Map<String,JSONObject> receipts=new LinkedHashMap<>();
    private boolean retired;
    synchronized void retire(){retired=true;}
    synchronized boolean known(JSONObject body)throws Exception {
        if(retired)throw new SecurityException("journal-retired");
        String id=ExperimentSessionGatewayProtocol.string(body,"id");
        if(!ExperimentSessionBleProtocol.isLowerHex(id,16))throw new IllegalArgumentException("command-id-required");
        if(!commands.containsKey(id))return false;
        if(!commands.get(id).equals(canonical(body)))throw new SecurityException("command-id-conflict");
        return true;
    }
    synchronized boolean reserve(JSONObject body)throws Exception {
        if(known(body))return false;
        if(commands.size()>=256)throw new SecurityException("command-capacity");
        commands.put(body.getString("id"),canonical(body));return true;
    }
    synchronized void observe(JSONObject receipt)throws Exception {
        if(!retired&&commands.containsKey(receipt.optString("id")))receipts.put(receipt.getString("id"),new JSONObject(receipt.toString()));
    }
    synchronized JSONObject original(String id)throws Exception {
        if(retired)throw new SecurityException("journal-retired");
        JSONObject result=receipts.get(id);
        return result==null?new JSONObject().put("v",1).put("id",id).put("state","none"):new JSONObject(result.toString());
    }
    private static String canonical(JSONObject body)throws Exception {
        java.util.List<String> keys=new java.util.ArrayList<>();java.util.Iterator<String> it=body.keys();
        while(it.hasNext()){String key=it.next();if(!"mac".equals(key)&&!"nonce".equals(key)&&!"n".equals(key))keys.add(key);}
        java.util.Collections.sort(keys);StringBuilder result=new StringBuilder();
        for(String key:keys)result.append(JSONObject.quote(key)).append(':').append(new org.json.JSONArray().put(body.get(key)).toString()).append(';');
        return result.toString();
    }
}
