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
        if(nativeReadback==null || now<=0 || server<=0 || owner<=0 || ownerEpoch<=0
                || !Boolean.TRUE.equals(nativeReadback.opt("recording"))
                || !Boolean.TRUE.equals(nativeReadback.opt("profile_identity_bound"))
                || !Boolean.TRUE.equals(nativeReadback.opt("audio_identity_bound"))
                || !Boolean.FALSE.equals(status.opt("pending"))) { clear();return null; }
        long generation=positive(nativeReadback,"generation"),epoch=positive(nativeReadback,"runtime_epoch");
        if(generation!=positive(status,"g")) {clear();return null;}
        String session=string(nativeReadback,"session_key"),condition=string(nativeReadback,"condition_id");
        String profile=string(nativeReadback,"non_audio_profile_sha256"),binding=string(nativeReadback,"activation_binding_sha256");
        if(!session.matches("utc-ns-[0-9]{20}") || !condition.matches("condition-[ab]")
                || !ExperimentSessionBleProtocol.isLowerHex(profile,64)
                || !ExperimentSessionBleProtocol.isLowerHex(binding,64)) {clear();return null;}
        String state=string(nativeReadback,"control_state");
        String nextAllowed="armed".equals(state)?"start":"running".equals(state)?"pause":"paused".equals(state)?"resume":"";
        if(nextAllowed.isEmpty() || !state.equalsIgnoreCase(string(status,"p"))) {clear();return null;}
        String nextTuple=server+"|"+owner+"|"+ownerEpoch+"|"+epoch+"|"+generation+"|"+session+"|"+condition+"|"+profile+"|"+binding;
        if(!nextTuple.equals(tuple)){reference=ExperimentSessionBleProtocol.newHex(16);tuple=nextTuple;}
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
