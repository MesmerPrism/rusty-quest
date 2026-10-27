package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.os.Bundle;
import android.os.Process;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** Actual app attachment/pre-service facts. A readiness read grants no admission. */
public final class EmbeddedDuplexAppAdmission {
    public static final String READINESS="rusty.quest.embedded_duplex.bootstrap_readiness.v1";
    public static final String RESULT="rusty.quest.embedded_duplex.bootstrap_result.v1";
    public static final String REFUSAL="rusty.quest.embedded_duplex.bootstrap_app_refusal.v1";
    public static final String JOIN="rusty.quest.embedded_duplex.original_app_admission_join.v1";
    private EmbeddedDuplexAppAdmission() {}
    public static final class CarrierFacts {
        public final boolean fencePresent,surfaceValid;
        public CarrierFacts(boolean fencePresent,boolean surfaceValid){this.fencePresent=fencePresent;this.surfaceValid=surfaceValid;}
    }

    public static final class Snapshot {
        private final JSONObject facts;
        public final boolean attachmentGateOpen, bootstrapGateOpen;
        public final String attachmentReason, bootstrapReason;
        private Snapshot(JSONObject facts,String attachment,String bootstrap) throws Exception {
            this.attachmentReason=attachment;this.bootstrapReason=bootstrap;
            attachmentGateOpen="open".equals(attachment);bootstrapGateOpen="open".equals(bootstrap);
            this.facts=facts.put("$schema",READINESS).put("attachment_gate_reason",attachment)
                    .put("bootstrap_gate_reason",bootstrap);
        }
        public String json() throws Exception { return EmbeddedDuplexCanonicalJson.encode(facts); }
        JSONObject value() throws Exception { return new JSONObject(json()); }
    }
    private static String future(CompletableFuture<?> value) {
        return value==null?"absent":value.isCancelled()?"cancelled":!value.isDone()?"pending":
                value.isCompletedExceptionally()?"failed":"completed";
    }
    private static String bootstrapReason(long attachment,boolean destroying,CompletableFuture<?> diagnostic,CompletableFuture<?> runtime) {
        return attachment==0?"attachment_absent":destroying?"activity_destroying":
                diagnostic!=null?"diagnostic_future_retained":runtime!=null?"runtime_future_retained":"open";
    }
    public static Snapshot activity(boolean inputs,boolean destroying,boolean diagnosticFinished,
            Boolean captureRoute,long attachment,boolean scene,boolean carrierFence,boolean surfaceValid,
            CompletableFuture<?> diagnostic,CompletableFuture<?> runtime) throws Exception {
        if(attachment<0)throw new IllegalArgumentException("negative Activity attachment");
        String attach=!inputs?"product_inputs_disabled":destroying?"activity_destroying":
                diagnosticFinished?"local_diagnostic_finished":!Boolean.TRUE.equals(captureRoute)?"capture_route_not_selected":
                attachment!=0?"already_attached":!scene?"scene_not_ready":!carrierFence?"carrier_fence_absent":
                !surfaceValid?"carrier_surface_invalid":"open";
        String bootstrap=bootstrapReason(attachment,destroying,diagnostic,runtime);
        return new Snapshot(new JSONObject().put("activity_present",true).put("dispatcher_accepted",true)
                .put("attachment_observed",true)
                .put("product_inputs_enabled",inputs).put("activity_destroying",destroying)
                .put("local_diagnostic_finished",diagnosticFinished).put("capture_route_selected",captureRoute==null?JSONObject.NULL:captureRoute)
                .put("attachment_generation",attachment).put("scene_ready",scene)
                .put("carrier_fence_present",carrierFence).put("carrier_surface_valid",surfaceValid)
                .put("diagnostic_future",future(diagnostic)).put("runtime_future",future(runtime)),attach,bootstrap);
    }
    public static Snapshot bootstrapOnly(boolean inputs,boolean destroying,boolean diagnosticFinished,long attachment,
            boolean scene,CompletableFuture<?> diagnostic,CompletableFuture<?> runtime)throws Exception {
        if(attachment<0)throw new IllegalArgumentException("negative Activity attachment");
        return new Snapshot(new JSONObject().put("activity_present",true).put("dispatcher_accepted",true)
                .put("attachment_observed",false).put("product_inputs_enabled",inputs).put("activity_destroying",destroying)
                .put("local_diagnostic_finished",diagnosticFinished).put("capture_route_selected",JSONObject.NULL)
                .put("attachment_generation",attachment).put("scene_ready",scene).put("carrier_fence_present",JSONObject.NULL)
                .put("carrier_surface_valid",JSONObject.NULL).put("diagnostic_future",future(diagnostic))
                .put("runtime_future",future(runtime)),"not_observed",bootstrapReason(attachment,destroying,diagnostic,runtime));
    }
    public static Snapshot noActivity() throws Exception { return unavailable(false,true,"resumed_activity_absent"); }
    public static Snapshot dispatchRejected() throws Exception { return unavailable(null,false,"activity_dispatch_rejected"); }
    public static Snapshot debugDisabled() throws Exception { return unavailable(null,false,"debug_dispatch_disabled"); }
    private static Snapshot unavailable(Boolean present,boolean accepted,String reason) throws Exception {
        JSONObject value=new JSONObject().put("activity_present",present==null?JSONObject.NULL:present)
                .put("dispatcher_accepted",accepted).put("attachment_observed",false);
        for(String key:new String[]{"product_inputs_enabled","activity_destroying","local_diagnostic_finished",
                "capture_route_selected","attachment_generation","scene_ready","carrier_fence_present",
                "carrier_surface_valid","diagnostic_future","runtime_future"})value.put(key,JSONObject.NULL);
        return new Snapshot(value,reason,reason);
    }
    static void keys(JSONObject value,String... expected) throws Exception {
        java.util.HashSet<String> actual=new java.util.HashSet<>();value.keys().forEachRemaining(actual::add);
        if(!actual.equals(Set.of(expected)))throw new IllegalArgumentException("closed app admission fields");
    }
    static void requireSnapshot(JSONObject value) throws Exception {
        keys(value,"$schema","activity_present","dispatcher_accepted","attachment_observed","product_inputs_enabled","activity_destroying",
                "local_diagnostic_finished","capture_route_selected","attachment_generation","scene_ready",
                "carrier_fence_present","carrier_surface_valid","diagnostic_future","runtime_future",
                "attachment_gate_reason","bootstrap_gate_reason");
        if(!READINESS.equals(value.getString("$schema")))throw new IllegalArgumentException("app readiness schema");
        Snapshot rebuilt;
        if(!value.isNull("activity_present")&&value.getBoolean("activity_present")) {
            for(String field:new String[]{"dispatcher_accepted","attachment_observed","product_inputs_enabled","activity_destroying",
                    "local_diagnostic_finished","scene_ready"})
                if(!(value.get(field) instanceof Boolean))throw new IllegalArgumentException("app readiness boolean");
            if(value.getBoolean("attachment_observed")&&(!value.isNull("capture_route_selected")&&!(value.get("capture_route_selected") instanceof Boolean)
                    ||value.isNull("capture_route_selected")&&value.getBoolean("product_inputs_enabled")
                        &&!value.getBoolean("activity_destroying")&&!value.getBoolean("local_diagnostic_finished")))
                throw new IllegalArgumentException("app route observation absent");
            Object generation=value.get("attachment_generation");
            if(!(generation instanceof Long||generation instanceof Integer)||value.getLong("attachment_generation")<0
                    ||!value.getBoolean("dispatcher_accepted"))throw new IllegalArgumentException("app readiness generation");
            CompletableFuture<?> diagnostic=modeledFuture(value.getString("diagnostic_future"));
            CompletableFuture<?> runtime=modeledFuture(value.getString("runtime_future"));
            if(!value.getBoolean("attachment_observed")) {
                rebuilt=bootstrapOnly(value.getBoolean("product_inputs_enabled"),value.getBoolean("activity_destroying"),
                        value.getBoolean("local_diagnostic_finished"),value.getLong("attachment_generation"),value.getBoolean("scene_ready"),diagnostic,runtime);
            } else {
            for(String field:new String[]{"carrier_fence_present","carrier_surface_valid"})
                if(!(value.get(field) instanceof Boolean))throw new IllegalArgumentException("app carrier boolean");
            rebuilt=activity(value.getBoolean("product_inputs_enabled"),value.getBoolean("activity_destroying"),
                    value.getBoolean("local_diagnostic_finished"),value.isNull("capture_route_selected")?null:value.getBoolean("capture_route_selected"),
                    value.getLong("attachment_generation"),value.getBoolean("scene_ready"),value.getBoolean("carrier_fence_present"),
                    value.getBoolean("carrier_surface_valid"),diagnostic,runtime);
            }
        } else rebuilt=value.getBoolean("dispatcher_accepted")?noActivity():
                "debug_dispatch_disabled".equals(value.getString("bootstrap_gate_reason"))?debugDisabled():dispatchRejected();
        if(!EmbeddedDuplexCanonicalJson.encode(rebuilt.facts).equals(EmbeddedDuplexCanonicalJson.encode(value)))
            throw new IllegalArgumentException("app readiness facts/reasons differ");
    }
    private static CompletableFuture<?> modeledFuture(String state) {
        switch(state) {
            case "absent":return null;
            case "pending":return new CompletableFuture<>();
            case "failed":return CompletableFuture.failedFuture(new IllegalStateException());
            case "completed":return CompletableFuture.completedFuture(null);
            case "cancelled":CompletableFuture<?> cancelled=new CompletableFuture<>();cancelled.cancel(false);return cancelled;
            default:throw new IllegalArgumentException("app retained Future state");
        }
    }
    interface ProcessSource { EmbeddedDuplexProcessIncarnation.Identity current() throws Exception; }
    public static final class Refusal extends IllegalStateException {
        final EmbeddedDuplexOriginals.Record original;
        final ProcessSource process;
        final JSONObject payload;
        private Refusal(Snapshot snapshot,ProcessSource process) throws Exception {
            super(snapshot.bootstrapReason);
            if(snapshot.bootstrapGateOpen)throw new IllegalArgumentException("open app gate is not a refusal");
            this.original=EmbeddedDuplexOriginals.capture();this.process=process;
            payload=EmbeddedDuplexOriginals.appAdmissionSubject(original)
                    .put("$schema",REFUSAL).put("reason",snapshot.bootstrapReason).put("service_entered",false)
                    .put("readiness",snapshot.value()).put("process_identity",process.current().json());
            if(original!=null)EmbeddedDuplexOriginals.retainAppAdmissionRefusal(original,this);
        }
        JSONObject payload() throws Exception { return new JSONObject(EmbeddedDuplexCanonicalJson.encode(payload)); }
        boolean currentProcess() throws Exception {
            return EmbeddedDuplexProcessIncarnation.Identity.read(payload.getJSONObject("process_identity")).binding()
                    .equals(process.current().binding());
        }
    }
    public static Refusal refuse(Snapshot snapshot) throws Exception {
        return refuse(snapshot,()->EmbeddedDuplexProcessIncarnation.current(Process.myPid(),Process.myUid()));
    }
    static Refusal refuse(Snapshot snapshot,ProcessSource process) throws Exception { return new Refusal(snapshot,process); }
    static Refusal refusal(Throwable failure) {
        for(int depth=0;depth<8&&failure!=null;depth++) {
            if(failure instanceof Refusal)return (Refusal)failure;
            if(!(failure instanceof java.util.concurrent.CompletionException||failure instanceof java.util.concurrent.ExecutionException))return null;
            failure=failure.getCause();
        }
        return null;
    }
    private static Bundle result(String challenge,String kind,JSONObject receipt) throws Exception {
        Bundle value=new Bundle();value.putString("schema",RESULT);value.putString("status","observed");
        value.putString("challenge",challenge);value.putString("kind",kind);
        value.putString("receipt",EmbeddedDuplexCanonicalJson.encode(receipt));return value;
    }
    public static Bundle refusalBundle(String challenge,Throwable failure) throws Exception {
        Refusal actual=refusal(failure);if(actual==null)throw new java.util.concurrent.CompletionException(failure);
        return result(challenge,"app_refusal",actual.payload());
    }
    public static Bundle serviceBundle(String challenge,EmbeddedDuplexRuntimeStatus state) throws Exception {
        return result(challenge,"service_result",new JSONObject().put("$schema","rusty.quest.embedded_duplex.bootstrap_service_result.v1")
                .put("runtime_state",state.state).put("display_attached",state.displayAttached)
                .put("own_app_capture_state",state.ownAppCaptureState).put("cleanup_scope",state.cleanupScope)
                .put("runtime_config_sha256",state.runtimeConfigSha256==null?JSONObject.NULL:state.runtimeConfigSha256)
                .put("enrollment_record_sha256",state.enrollmentRecordSha256==null?JSONObject.NULL:state.enrollmentRecordSha256)
                .put("peer_route_proven",false).put("media_effect_proven",false));
    }
    static boolean isResult(JSONObject value) { return RESULT.equals(value.optString("schema")); }
    static boolean isRefusal(JSONObject value) { return isResult(value)&&"app_refusal".equals(value.optString("kind")); }
    static void requireResult(String operation,String challenge,JSONObject value) throws Exception {
        keys(value,"schema","status","challenge","kind","receipt");
        if(!"bootstrap_real_peer".equals(operation)||!RESULT.equals(value.getString("schema"))
                ||!"observed".equals(value.getString("status"))||!challenge.equals(value.getString("challenge")))
            throw new IllegalArgumentException("app bootstrap result identity");
        JSONObject receipt=new JSONObject(value.getString("receipt"));
        if("app_refusal".equals(value.getString("kind"))) {
            keys(receipt,"$schema","reason","service_entered","readiness","process_identity","original_id",
                    "original_challenge","request_sha256","run_id","selection_generation");
            requireSnapshot(receipt.getJSONObject("readiness"));EmbeddedDuplexProcessIncarnation.Identity.read(receipt.getJSONObject("process_identity"));
            if(!REFUSAL.equals(receipt.getString("$schema"))||!(receipt.get("service_entered") instanceof Boolean)
                    ||receipt.getBoolean("service_entered")||"open".equals(receipt.getString("reason"))
                    ||!receipt.getString("reason").equals(receipt.getJSONObject("readiness").getString("bootstrap_gate_reason")))
                throw new IllegalArgumentException("app refusal facts");
            if(!receipt.isNull("original_id")&&(!receipt.getString("original_id").matches("[0-9a-f]{32}")
                    ||!receipt.getString("original_challenge").matches("[0-9a-f]{32}")
                    ||!receipt.getString("request_sha256").matches("[0-9a-f]{64}")))throw new IllegalArgumentException("app refusal Original subject");
            if(receipt.isNull("original_id")&&(!receipt.isNull("original_challenge")||!receipt.isNull("request_sha256")))
                throw new IllegalArgumentException("app refusal absent Original subject");
            Object generation=receipt.get("selection_generation");
            if(!(generation instanceof Integer||generation instanceof Long)||receipt.getLong("selection_generation")<0
                    ||!receipt.isNull("run_id")&&!receipt.getString("run_id").matches("[0-9a-f]{32}"))
                throw new IllegalArgumentException("app refusal run subject");
        } else if("service_result".equals(value.getString("kind"))) {
            keys(receipt,"$schema","runtime_state","display_attached","own_app_capture_state","cleanup_scope",
                    "runtime_config_sha256","enrollment_record_sha256","peer_route_proven","media_effect_proven");
            if(!"rusty.quest.embedded_duplex.bootstrap_service_result.v1".equals(receipt.getString("$schema"))
                    ||!(receipt.get("display_attached") instanceof Boolean)||!(receipt.get("peer_route_proven") instanceof Boolean)
                    ||!(receipt.get("media_effect_proven") instanceof Boolean)||receipt.getBoolean("peer_route_proven")||receipt.getBoolean("media_effect_proven"))
                throw new IllegalArgumentException("app service projection");
            if(!Set.of("uninitialized","bootstrapping","bootstrapped_route_unverified","local_fixture","cleanup_pending").contains(receipt.getString("runtime_state")))
                throw new IllegalArgumentException("app service state");
            if(!Set.of("disabled","Idle","Starting","Live","StopPending").contains(receipt.getString("own_app_capture_state"))
                    ||!Set.of("whole_product","peer_subscription_only").contains(receipt.getString("cleanup_scope")))
                throw new IllegalArgumentException("app service capture scope");
            for(String field:new String[]{"runtime_config_sha256","enrollment_record_sha256"})
                if(!receipt.isNull(field)&&!receipt.getString(field).matches("[0-9a-f]{64}"))throw new IllegalArgumentException("app service digest");
        } else throw new IllegalArgumentException("bootstrap result union kind");
    }
}
