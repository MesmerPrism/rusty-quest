package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Transport independent authenticated controller session. No network or platform effects. */
public final class ExperimentSessionGatewayProtocol {
    static boolean lanAllowed(boolean selected,boolean enrolled,boolean ownerLive,boolean trustedLanConsent) {
        return selected&&enrolled&&ownerLive&&trustedLanConsent;
    }
    public interface Owner { JSONObject exchange(String action, JSONObject payload) throws Exception; }
    private final String controller, key, device, epoch;
    private final Owner owner;
    private final Set<String> nonces = new HashSet<>();
    private boolean retired;

    public ExperimentSessionGatewayProtocol(String controller, String key, String device,
            long epoch, Owner owner) {
        if (!hex(controller,32) || !hex(key,64) || !hex(device,32) || epoch<=0 || owner==null)
            throw new IllegalArgumentException("invalid-controller-binding");
        this.controller=controller;this.key=key;this.device=device;this.epoch=Long.toString(epoch);this.owner=owner;
    }
    public synchronized void retire() { retired=true; nonces.clear(); }
    public JSONObject exchange(JSONObject request) throws Exception {
        final String nonce, action, payload;
        synchronized(this) {
            if(retired || request==null || request.toString().getBytes(StandardCharsets.UTF_8).length>8192
                    || request.length()!=7 || !Integer.valueOf(1).equals(request.opt("v"))
                    || !controller.equals(request.opt("controller")) || !epoch.equals(request.opt("epoch")))
                throw new SecurityException("controller-or-epoch-unavailable");
            nonce=string(request,"nonce"); action=string(request,"action"); payload=string(request,"payload");
            if(!hex(nonce,32) || !("status".equals(action)||"command".equals(action)||"receipt".equals(action))
                    || !constant(hmac(key,"RQG1|"+controller+"|"+epoch+"|"+nonce+"|"+action+"|"+payload),string(request,"mac")))
                throw new SecurityException("authentication-failed");
            // Exhaustion requires a new native owner epoch; never evict replay protection.
            if(nonces.size()>=65536 || !nonces.add(nonce))throw new SecurityException("replay-or-capacity");
        }
        JSONObject result=owner.exchange(action,new JSONObject(payload));
        synchronized(this) {
            if(retired)throw new SecurityException("owner-retired");
            String body=result.toString();
            return new JSONObject().put("v",1).put("device",device).put("epoch",epoch).put("nonce",nonce)
                .put("action",action).put("payload",body).put("mac",hmac(key,
                    "RQG1-REPLY|"+controller+"|"+device+"|"+epoch+"|"+nonce+"|"+action+"|"+body));
        }
    }
    static String string(JSONObject value,String field)throws Exception {
        Object raw=value.opt(field);if(!(raw instanceof String))throw new IllegalArgumentException("string-required");return (String)raw;
    }
    static boolean hex(String value,int length) {
        return value!=null && value.length()==length && value.matches("[0-9a-f]+");
    }
    public static String hmac(String key,String message)throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        StringBuilder out=new StringBuilder();for(byte b:mac.doFinal(message.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static boolean constant(String a,String b) {
        return hex(b,64)&&MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));
    }
}
