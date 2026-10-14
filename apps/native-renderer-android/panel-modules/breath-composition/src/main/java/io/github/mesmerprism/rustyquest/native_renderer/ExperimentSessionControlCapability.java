package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-held opaque reference. The compact wire never substitutes for the full native tuple. */
final class ExperimentSessionControlCapability {
    static final long MAX_SAFE_INTEGER = 9007199254740991L;
    static final class Fence {
        final String reference, session, condition, profile, binding, operation;
        final long generation, epoch, server, owner, ownerEpoch;
        Fence(String ref, String session, String condition, String profile, String binding,
                String operation, long generation, long epoch, long server, long owner, long ownerEpoch) {
            this.reference=ref;this.session=session;this.condition=condition;this.profile=profile;
            this.binding=binding;this.operation=operation;this.generation=generation;this.epoch=epoch;
            this.server=server;this.owner=owner;this.ownerEpoch=ownerEpoch;
        }
        JSONObject nativeCommand(String id,long now) throws Exception {
            return new JSONObject().put("schema","rusty.quest.experiment_session.command.v1")
                .put("operation","operator-"+("start".equals(operation)?"official-start":operation))
                .put("operation_id",id).put("expected_generation",generation).put("elapsed_realtime_ns",now)
                .put("operator_origin","ble-session-v2").put("expected_runtime_epoch",epoch)
                .put("expected_session_key",session).put("expected_condition",condition)
                .put("expected_profile_sha256",profile).put("expected_binding_sha256",binding);
        }
    }
    private String tuple="", reference="", allowed="";
    private JSONObject projection;
    private long observed,server,owner,ownerEpoch;

    synchronized void clear() { tuple="";reference="";allowed="";projection=null;observed=0; }

    synchronized JSONObject offer(JSONObject status,JSONObject nativeReadback,long server,long owner,
            long ownerEpoch,long now) throws Exception {
        return offer(status,nativeReadback,server,owner,ownerEpoch,now,"");
    }
    synchronized JSONObject offer(JSONObject status,JSONObject nativeReadback,long server,long owner,
            long ownerEpoch,long now,String independentPolarTuple) throws Exception {
        if(nativeReadback==null || now<=0 || server<=0 || owner<=0 || ownerEpoch<=0
                // The native status contract expresses recording with phase=active.
                // It does not emit a separate recording member.
                || !"active".equals(nativeReadback.opt("phase"))
                || !Boolean.TRUE.equals(nativeReadback.opt("profile_identity_bound"))
                || !Boolean.TRUE.equals(nativeReadback.opt("audio_identity_bound"))
                || !Boolean.FALSE.equals(status.opt("pending"))) { clear();return null; }
        long generation=positive(nativeReadback,"generation"),epoch=positive(nativeReadback,"runtime_epoch");
        if(generation!=positive(status,"g")) {clear();return null;}
        String session=string(nativeReadback,"session_key"),condition=string(nativeReadback,"condition_id");
        String profile=string(nativeReadback,"non_audio_profile_sha256"),binding=string(nativeReadback,"activation_binding_sha256");
        if(!session.matches("utc-ns-[0-9]{20}") || !condition.matches("condition-[ab]")
                || !ExperimentSessionOpaqueIdentity.isLowerHex(profile,64)
                || !ExperimentSessionOpaqueIdentity.isLowerHex(binding,64)) {clear();return null;}
        String state=string(nativeReadback,"control_state");
        String nextAllowed="armed".equals(state)?"start":"running".equals(state)?"pause":"paused".equals(state)?"resume":"";
        if(nextAllowed.isEmpty() || !state.equalsIgnoreCase(string(status,"p"))) {clear();return null;}
        String nextTuple=server+"|"+owner+"|"+ownerEpoch+"|"+epoch+"|"+generation+"|"+session+"|"+condition+"|"+profile+"|"+binding+"|"+independentPolarTuple;
        if(!nextTuple.equals(tuple)){reference=ExperimentSessionOpaqueIdentity.newHex(16);tuple=nextTuple;}
        this.projection=new JSONObject(nativeReadback.toString());this.observed=now;
        this.server=server;this.owner=owner;this.ownerEpoch=ownerEpoch;this.allowed=nextAllowed;
        return new JSONObject().put("v",2).put("s",reference).put("g",generation)
            .put("o",new JSONArray().put(nextAllowed));
    }

    synchronized Fence admit(String ref,long gen,String operation,long now,long server,long owner,long ownerEpoch) throws Exception {
        if(projection==null || now<observed || now-observed>1500 || !reference.equals(ref)
                || !allowed.equals(operation) || gen!=positive(projection,"generation")
                || server!=this.server || owner!=this.owner || ownerEpoch!=this.ownerEpoch)
            throw new SecurityException("session-capability-stale-or-mismatched");
        return new Fence(reference,string(projection,"session_key"),string(projection,"condition_id"),
            string(projection,"non_audio_profile_sha256"),string(projection,"activation_binding_sha256"),
            operation,gen,positive(projection,"runtime_epoch"),server,owner,ownerEpoch);
    }
    static long positive(JSONObject value,String field) throws Exception {
        Object raw=value.opt(field);
        if(!(raw instanceof Integer) && !(raw instanceof Long))throw new IllegalArgumentException("integer-required");
        long n=((Number)raw).longValue();if(n<=0 || n>MAX_SAFE_INTEGER)throw new IllegalArgumentException("safe-positive-integer-required");return n;
    }
    static String string(JSONObject value,String field) throws Exception {
        Object raw=value.opt(field);if(!(raw instanceof String))throw new IllegalArgumentException("string-required");return (String)raw;
    }
}

/** Transport-independent opaque request identities; never session or recording authority. */
final class ExperimentSessionOpaqueIdentity {
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private ExperimentSessionOpaqueIdentity() { }
    static String newHex(int bytes) {
        if (bytes < 1 || bytes > 32) throw new IllegalArgumentException("invalid random size");
        byte[] value = new byte[bytes]; RANDOM.nextBytes(value);
        char[] result = new char[bytes * 2];
        for (int i=0;i<bytes;i++) { result[i*2]=HEX[(value[i]&255)>>>4]; result[i*2+1]=HEX[value[i]&15]; }
        return new String(result);
    }
    static boolean isLowerHex(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i); if ((c<'0'||c>'9')&&(c<'a'||c>'f')) return false;
        }
        return true;
    }
}

/** UI queue correlation only; native owner receipts are the sole acceptance evidence. */
final class ExperimentSessionUiCommandReceipt {
    private final ExperimentSessionControlCapability.Fence fence;
    private final String id;
    ExperimentSessionUiCommandReceipt(ExperimentSessionControlCapability.Fence fence,String id) {
        this.fence=fence; this.id=id;
    }
    String observe(org.json.JSONObject current) {
        if (current == null
                || ExperimentSessionControlCapability.positive(current,"generation")!=fence.generation
                || ExperimentSessionControlCapability.positive(current,"runtime_epoch")!=fence.epoch
                || !fence.session.equals(ExperimentSessionControlCapability.string(current,"session_key"))
                || !fence.condition.equals(ExperimentSessionControlCapability.string(current,"condition_id"))
                || !fence.profile.equals(ExperimentSessionControlCapability.string(current,"non_audio_profile_sha256"))
                || !fence.binding.equals(ExperimentSessionControlCapability.string(current,"activation_binding_sha256")))
            return "owner-changed-outcome-unknown";
        String last=current.optString("last_operation_status");
        if (id.equals(current.optString("last_operation_id"))
                && ("rejected".equals(last)||"failed".equals(last)||"duplicate".equals(last))) return "rejected";
        String expected="pause".equals(fence.operation)?"paused":"running";
        if ("active".equals(current.opt("phase")) && id.equals(current.optString("operator_operation_id"))
                && "ui-session-v2".equals(current.optString("operator_origin"))
                && "applied".equals(current.optString("operator_operation_status"))
                && expected.equals(current.optString("control_state"))) return "applied";
        return "pending-not-applied";
    }
}
