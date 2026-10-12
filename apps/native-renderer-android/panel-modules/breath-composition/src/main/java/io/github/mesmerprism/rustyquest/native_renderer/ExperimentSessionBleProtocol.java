package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded, versioned Web Bluetooth command authentication. No product secrets are packaged. */
final class ExperimentSessionBleProtocol {
    static final String SERVICE = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020001";
    static final String STATUS = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020002";
    static final String CHALLENGE = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020003";
    static final String COMMAND = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020004";
    static final String RECEIPT = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020005";
    static final int MAX_COMMAND_BYTES = 240;
    private static final char[] BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ234567".toCharArray();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ExperimentSessionBleProtocol() { }

    static String newCode() {
        char[] result = new char[12];
        byte[] random = new byte[result.length];
        RANDOM.nextBytes(random);
        for (int i = 0; i < result.length; i++) {
            result[i] = BASE32[(random[i] & 0xff) % BASE32.length];
        }
        return new String(result);
    }

    static String newHex(int bytes) {
        if (bytes < 1 || bytes > 32) throw new IllegalArgumentException("invalid random size");
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return hex(value, value.length);
    }

    static boolean isLowerHex(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    static boolean supportedOperation(String operation) {
        return "ping".equals(operation) || "arm".equals(operation)
            || "save-next".equals(operation) || "save-exit".equals(operation)
            || "return-vr".equals(operation) || "open-polar".equals(operation);
    }

    static boolean validCommandFields(String id, String operation, String condition,
            int bias, String nonce) {
        if (!isLowerHex(id, 16) || !isLowerHex(nonce, 16)
                || !supportedOperation(operation) || bias < 0 || bias > 100) return false;
        if ("arm".equals(operation)) {
            return "condition-a".equals(condition) || "condition-b".equals(condition);
        }
        return condition != null && condition.isEmpty() && bias == 0;
    }

    static boolean verify(String code, String challenge, String id, String operation,
            String condition, int bias, String nonce, String suppliedMac) {
        if (code == null || !isLowerHex(challenge, 32)
                || !validCommandFields(id, operation, condition, bias, nonce)
                || !isLowerHex(suppliedMac, 32)) return false;
        try {
            String input = "RQEC1|" + challenge + "|" + id + "|" + operation + "|"
                + condition + "|" + bias + "|" + nonce;
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = hmac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = fromHex(suppliedMac);
            byte[] truncated = new byte[16];
            System.arraycopy(expected, 0, truncated, 0, 16);
            return MessageDigest.isEqual(truncated, supplied);
        } catch (Exception invalid) {
            return false;
        }
    }

    static boolean validSessionFields(String id,String operation,String session,long generation,String nonce) {
        return isLowerHex(id,16) && isLowerHex(session,32) && isLowerHex(nonce,16)
            && generation>0 && generation<=9007199254740991L
            && ("start".equals(operation) || "pause".equals(operation) || "resume".equals(operation));
    }

    static boolean verifySession(String code,String challenge,String id,String operation,
            String session,long generation,String nonce,String suppliedMac) {
        if(code==null || !isLowerHex(challenge,32) || !isLowerHex(suppliedMac,32)
                || !validSessionFields(id,operation,session,generation,nonce)) return false;
        try {
            Mac hmac=Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] expected=hmac.doFinal(("RQEC2|"+challenge+"|"+id+"|"+operation+"|"+session+"|"+generation+"|"+nonce).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(java.util.Arrays.copyOf(expected,16),fromHex(suppliedMac));
        } catch(Exception denied) {return false;}
    }

    static boolean validPolarFields(String id,String operation,String reference,long revision,String nonce) {
        return isLowerHex(id,16) && isLowerHex(reference,32) && isLowerHex(nonce,16)
            && revision>=0 && revision<=9007199254740991L
            && ("stop-acc".equals(operation) || "stop-ecg".equals(operation));
    }

    static boolean verifyPolar(String code,String challenge,String id,String operation,
            String reference,long revision,String nonce,String suppliedMac) {
        if(code==null || !isLowerHex(challenge,32) || !isLowerHex(suppliedMac,32)
                || !validPolarFields(id,operation,reference,revision,nonce))return false;
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] digest=mac.doFinal(("RQPC1|"+challenge+"|"+id+"|"+operation+"|"+reference+"|"+revision+"|"+nonce).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(java.util.Arrays.copyOf(digest,16),fromHex(suppliedMac));
        } catch(Exception denied){return false;}
    }

    static String hex(byte[] bytes, int length) {
        char[] result = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 15];
        }
        return new String(result);
    }

    private static byte[] fromHex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((Character.digit(value.charAt(i * 2), 16) << 4)
                | Character.digit(value.charAt(i * 2 + 1), 16));
        }
        return result;
    }
}

/** Separate sensor capability: an experiment session reference never authorizes a Polar action. */
final class PolarStopBleCapability {
    static final String SCHEMA="rusty.quest.native_renderer.polar_projected_stop.v1";
    static final class Wire {
        final String id,operation,reference,nonce,mac;
        final long revision;
        Wire(String id,String operation,String reference,long revision,String nonce,String mac) {
            this.id=id;this.operation=operation;this.reference=reference;this.revision=revision;this.nonce=nonce;this.mac=mac;
        }
    }
    static Wire parse(org.json.JSONObject body) throws Exception {
        Object version=body.opt("v");
        if(!(version instanceof Integer)&&!(version instanceof Long))throw new IllegalArgumentException("integer-version-required");
        if(((Number)version).longValue()!=3||body.toString().getBytes(StandardCharsets.UTF_8).length>240)
            throw new IllegalArgumentException("bounded-polar-version-required");
        java.util.Set<String> fields=new java.util.HashSet<>(java.util.Arrays.asList("v","id","op","s","r","n"));
        if(body.has("mac"))fields.add("mac");
        if(body.length()!=fields.size())throw new IllegalArgumentException("closed-polar-command-required");
        for(String field:fields)if(!body.has(field))throw new IllegalArgumentException("missing-polar-command-field");
        String id=ExperimentSessionControlCapability.string(body,"id"),operation=ExperimentSessionControlCapability.string(body,"op"),
            reference=ExperimentSessionControlCapability.string(body,"s"),nonce=ExperimentSessionControlCapability.string(body,"n");
        long revision=integer(body,"r",true);String mac=body.has("mac")?ExperimentSessionControlCapability.string(body,"mac"):"";
        if(!ExperimentSessionBleProtocol.validPolarFields(id,operation,reference,revision,nonce)
                ||body.has("mac")&&!ExperimentSessionBleProtocol.isLowerHex(mac,32))throw new IllegalArgumentException("invalid-polar-command");
        return new Wire(id,operation,reference,revision,nonce,mac);
    }
    private String tuple="",reference="",consumed="";
    private org.json.JSONObject state;
    private org.json.JSONObject nativeState;
    private long observed,server,owner,ownerEpoch;
    synchronized void clear(){tuple="";reference="";state=null;nativeState=null;observed=0;}
    synchronized org.json.JSONObject nativeFence() throws Exception {
        if(nativeState==null)throw new SecurityException("polar-capability-unavailable");
        return new org.json.JSONObject(nativeState.toString());
    }
    synchronized String bindingTuple(){return state==null?"":tuple;}
    synchronized void shareReference(String ref) {
        if(state==null||!ExperimentSessionBleProtocol.isLowerHex(ref,32))throw new SecurityException("polar-offer-unavailable");
        reference=ref;
    }
    static boolean matchesNativeFence(org.json.JSONObject expected,org.json.JSONObject current) throws Exception {
        if(expected==null||current==null||!Boolean.TRUE.equals(current.opt("profile_identity_bound")))return false;
        for(String field:new String[]{"generation","runtime_epoch"})
            if(integer(expected,field,"generation".equals(field))!=integer(current,field,"generation".equals(field)))return false;
        for(String field:new String[]{"non_audio_profile_sha256","activation_binding_sha256"})
            if(!ExperimentSessionControlCapability.string(expected,field).equals(ExperimentSessionControlCapability.string(current,field)))return false;
        return true;
    }
    static long integer(org.json.JSONObject object,String field,boolean zero) throws Exception {
        Object raw=object.opt(field);
        if(!(raw instanceof Integer)&&!(raw instanceof Long))throw new IllegalArgumentException("integer-required");
        long n=((Number)raw).longValue();
        if(n<(zero?0:1)||n>9007199254740991L)throw new IllegalArgumentException("safe-integer-required");return n;
    }
    synchronized org.json.JSONObject offer(org.json.JSONObject projection,org.json.JSONObject nativeReadback,
            long server,long owner,long ownerEpoch,long now) throws Exception {
        // Clear before parsing so malformed replacement data cannot retain an old grant.
        org.json.JSONObject prior=state;String oldTuple=tuple,oldRef=reference;clear();
        if(projection==null||nativeReadback==null||server<=0||owner<=0||ownerEpoch<=0||now<=0)return null;
        // An idle/rejected session has no bound profile. Omit this optional grant
        // before parsing its nullable identity; keep the rest of status available.
        if(!Boolean.TRUE.equals(nativeReadback.opt("profile_identity_bound")))return null;
        if(!Boolean.FALSE.equals(projection.opt("closed")))return null;
        int accBits=streamBits(projection,"acc"),ecgBits=streamBits(projection,"ecg");
        long revision=integer(projection,"action_revision",true);
        integer(projection,"pid",false);integer(projection,"runtime_generation",false);
        integer(projection,"acc_revision",true);integer(projection,"ecg_revision",true);
        String epoch=ExperimentSessionControlCapability.string(projection,"owner_epoch");
        if(!ExperimentSessionBleProtocol.isLowerHex(epoch,32))throw new IllegalArgumentException("polar-owner-epoch-required");
        for(String key:new String[]{"selected_sensor","connected_sensor"}) {
            String sensor=ExperimentSessionControlCapability.string(projection,key);
            if(!"none".equals(sensor)&&!sensor.matches("polar-[a-f0-9]{12}"))throw new IllegalArgumentException("closed-sensor-required");
        }
        for(String key:new String[]{"stop_acc_operation_id","stop_ecg_operation_id"})
            if(!ExperimentSessionBleProtocol.isLowerHex(ExperimentSessionControlCapability.string(projection,key),32))
                throw new IllegalArgumentException("offered-operation-required");
        long generation=integer(nativeReadback,"generation",true),runtimeEpoch=integer(nativeReadback,"runtime_epoch",false);
        String profile=ExperimentSessionControlCapability.string(nativeReadback,"non_audio_profile_sha256");
        String binding=ExperimentSessionControlCapability.string(nativeReadback,"activation_binding_sha256");
        if(!ExperimentSessionBleProtocol.isLowerHex(profile,64)||!ExperimentSessionBleProtocol.isLowerHex(binding,64)
                ||!Boolean.TRUE.equals(nativeReadback.opt("profile_identity_bound")))return null;
        org.json.JSONObject canonical=new org.json.JSONObject();
        for(String field:new String[]{"pid","runtime_generation","owner_epoch","action_revision","selected_sensor","connected_sensor",
                "acc_revision","ecg_revision","stop_acc_operation_id","stop_ecg_operation_id"})canonical.put(field,projection.get(field));
        // Explicit field order, not JSONObject iteration order, owns the stable full tuple.
        StringBuilder key=new StringBuilder(server+"|"+owner+"|"+ownerEpoch+"|"+generation+"|"+runtimeEpoch+"|"+profile+"|"+binding);
        for(String field:new String[]{"pid","runtime_generation","owner_epoch","action_revision","selected_sensor","connected_sensor",
                "acc_revision","ecg_revision","stop_acc_operation_id","stop_ecg_operation_id"}) {
            String value=String.valueOf(canonical.get(field));key.append('|').append(value.length()).append(':').append(value);
        }
        String next=key.toString();if(next.equals(consumed))return null;
        reference=prior!=null&&next.equals(oldTuple)?oldRef:ExperimentSessionBleProtocol.newHex(16);
        tuple=next;state=canonical;observed=now;this.server=server;this.owner=owner;this.ownerEpoch=ownerEpoch;
        nativeState=new org.json.JSONObject(nativeReadback.toString());
        return new org.json.JSONObject().put("v",3).put("s",reference).put("r",revision)
            .put("a",accBits).put("e",ecgBits)
            .put("o",new org.json.JSONArray().put("stop-acc").put("stop-ecg"));
    }
    private static int streamBits(org.json.JSONObject projection,String mode) throws Exception {
        int result=0,bit=1;
        for(String suffix:new String[]{"_requested","_pmd_ack_running","_data_receiving_observed"}) {
            Object value=projection.opt(mode+suffix);
            if(!(value instanceof Boolean))throw new IllegalArgumentException("stream-boolean-required");
            if(Boolean.TRUE.equals(value))result|=bit;bit<<=1;
        }
        return result;
    }
    synchronized org.json.JSONObject admit(String ref,long revision,String operation,long now,
            long server,long owner,long ownerEpoch) throws Exception {
        if(state==null||now<observed||now-observed>1500||!reference.equals(ref)
                ||revision!=integer(state,"action_revision",true)||server!=this.server||owner!=this.owner||ownerEpoch!=this.ownerEpoch
                ||!("stop-acc".equals(operation)||"stop-ecg".equals(operation)))
            throw new SecurityException("polar-capability-stale-or-mismatched");
        String action=operation.replace('-','_');
        org.json.JSONObject request=new org.json.JSONObject().put("schema",SCHEMA).put("operation",action)
            .put("operation_id",state.getString(action+"_operation_id"));
        for(String field:new String[]{"pid","runtime_generation","owner_epoch","action_revision","selected_sensor","connected_sensor",
                "acc_revision","ecg_revision"})request.put(field,state.get(field));
        consumed=tuple;clear();return request;
    }
    synchronized byte[] projectBoundedStatus(org.json.JSONObject base,org.json.JSONObject offer) throws Exception {
        org.json.JSONObject candidate=new org.json.JSONObject(base.toString());
        // Preserve the pre-existing optional q fallback before considering the new grant.
        if(candidate.toString().getBytes(StandardCharsets.UTF_8).length>480&&candidate.has("q"))candidate.remove("q");
        if(offer!=null) {
            org.json.JSONObject compact=new org.json.JSONObject().put("r",offer.getLong("r"))
                .put("a",offer.getInt("a")).put("e",offer.getInt("e"));
            org.json.JSONObject session=candidate.optJSONObject("q");
            if(session==null||!offer.getString("s").equals(session.optString("s")))compact.put("s",offer.getString("s"));
            candidate.put("z",compact);
        }
        byte[] bytes=candidate.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length<=480)return bytes;
        clear();candidate.remove("z");bytes=candidate.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>480)throw new IllegalArgumentException("status-budget-exceeded");
        return bytes;
    }
}
