package io.github.mesmerprism.rustyquest.native_renderer;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
final class ExperimentSessionDebugOperator {
    private static final java.util.Map<String,JSONObject> modeRequests=new java.util.LinkedHashMap<String,JSONObject>();
    private interface ModeRead {JSONObject read() throws Exception;}
    private static JSONObject onModeMain(final ModeRead action) throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return action.read();
        java.util.concurrent.FutureTask<JSONObject> task=new java.util.concurrent.FutureTask<JSONObject>(new java.util.concurrent.Callable<JSONObject>(){
            public JSONObject call() throws Exception{return action.read();}
        });
        if(!new android.os.Handler(android.os.Looper.getMainLooper()).post(task))throw new IllegalStateException("mode-owner-unavailable");
        try{return task.get(5,java.util.concurrent.TimeUnit.SECONDS);}
        finally{task.cancel(false);} // Read only; cancelling never dispatches a mutation.
    }
    private static JSONObject modeEnvelope(String operation,JSONObject value) throws Exception {
        return new JSONObject().put("schema","rusty.quest.experiment_session.operator_receipt.v1")
            .put("mode_operator_version",1).put("operation",operation).put("mode_result",value);
    }
    private static JSONObject executeMode(final Context context,final JSONObject request) throws Exception {
        final String operation=request.getString("operation");
        if("control-mode-status".equals(operation))return onModeMain(new ModeRead(){public JSONObject read()throws Exception{
            return modeEnvelope(operation,BreathCompositionPanelModule.experimentControlModeStatus())
                .put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid())
                .put("process_start_ticks",processStartTicks()).put("apk_sha256",installedApkSha256(context));
        }});
        final String id=request.getString("operation_id");
        if("control-mode-receipt".equals(operation))return onModeMain(new ModeRead(){public JSONObject read()throws Exception{
            return modeEnvelope(operation,modeReceipt(id));
        }});
        final JSONObject frozen=new JSONObject(request.toString());
        synchronized(modeRequests){
            JSONObject original=modeRequests.get(id);
            if(original!=null)return modeEnvelope(operation,new JSONObject(original.toString()));
            if(modeRequests.size()>=128)throw new SecurityException("mode-request-capacity-no-replay");
            modeRequests.put(id,new JSONObject().put("operation_id",id).put("state","pending")
                .put("original_request",new JSONObject(frozen.toString())));
        }
        boolean posted=new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable(){public void run(){
            JSONObject result;
            try{
                JSONObject applied=BreathCompositionPanelModule.selectExperimentOperatorMode(frozen,new Runnable(){public void run(){
                    try{validateEnrollmentActorFacts(frozen,android.os.Process.myPid(),android.os.Process.myUid(),processStartTicks(),installedApkSha256(context));}
                    catch(Exception denied){throw new SecurityException("mode-actor-apk-changed",denied);}
                }});
                result=new JSONObject().put("state","accepted").put("native_acceptance",applied);
            }catch(Exception denied){
                result=new JSONObject();try{result.put("state",denied instanceof SecurityException?"rejected":"outcome_unknown")
                    .put("reason",denied instanceof SecurityException?"mode-fence-or-owner-rejected":"mode-owner-result-unavailable");}
                catch(Exception impossible){throw new IllegalStateException(impossible);}
            }
            synchronized(modeRequests){
                try{result.put("operation_id",id).put("original_request",new JSONObject(frozen.toString()));
                    modeRequests.put(id,result);}catch(Exception impossible){throw new IllegalStateException(impossible);}
            }
        }});
        synchronized(modeRequests){
            if(!posted)modeRequests.get(id).put("state","rejected").put("reason","mode-owner-unavailable");
            return modeEnvelope(operation,new JSONObject(modeRequests.get(id).toString()));
        }
    }
    private static JSONObject modeReceipt(String id) throws Exception {
        JSONObject result;
        synchronized(modeRequests){
            JSONObject original=modeRequests.get(id);
            if(original==null)return new JSONObject().put("operation_id",id).put("state","outcome_unknown").put("reason","original-request-unavailable-no-replay");
            result=new JSONObject(original.toString());
        }
        JSONObject applied=result.optJSONObject("native_acceptance");
        if(applied==null)return result;
        JSONObject presentation=new JSONObject().put("status","unavailable");
        try{
            JSONObject current=BreathCompositionPanelModule.experimentControlModeStatus();
            JSONObject before=applied.getJSONObject("before");
            JSONObject wanted=applied.getJSONObject("panel_visibility"),actual=current.getJSONObject("panel_visibility");
            boolean visible="direct".equals(applied.getString("mode"));
            presentation.put("requested_visible",visible);
            if(current.getString("host_id").equals(before.getString("host_id"))
                    &&current.getLong("carrier_generation")==before.getLong("carrier_generation")
                    &&current.getLong("native_owner_epoch")==before.getLong("native_owner_epoch")
                    &&current.getLong("mode_packed")==applied.getLong("mode_packed")){
                presentation.put("status","pending");
                Object generation=wanted.opt("generation"),revision=wanted.opt("request_revision");
                if((generation instanceof Long||generation instanceof Integer)&&(revision instanceof Long||revision instanceof Integer)
                        &&((Number)generation).longValue()>0&&((Number)revision).longValue()>0){
                    Boolean acknowledged=NativeMeshUiProducer.VisibilityReadback.applied(actual,((Number)generation).longValue(),((Number)revision).longValue());
                    if(acknowledged!=null&&acknowledged.booleanValue()==visible)
                        presentation.put("status","acknowledged").put("effective_visible",acknowledged).put("renderer_receipt",actual);
                }
            }
        }catch(Exception unavailable){ /* Native acceptance survives unavailable rendering/retired host. */ }
        return result.put("presentation",presentation);
    }
    /** Production shell control of the real selected session owner; no clock/phase fabrication. */
    static String execute(Context context, JSONObject request) throws Exception {
        validateShape(request);
        String operation = request.getString("operation");
        if(operation.equals("control-mode-status")||operation.equals("set-control-mode")||operation.equals("control-mode-receipt"))
            return executeMode(context,new JSONObject(request.toString())).toString();
        boolean read = "status".equals(operation);
        JSONObject breath = new JSONObject(ControlPanelActivity.nativeReadBreathCompositionStatus());
        JSONObject snapshot = breath.getJSONObject("snapshot");
        if (!Boolean.TRUE.equals(snapshot.opt("feature_lock_active"))
                || !Boolean.TRUE.equals(snapshot.opt("activation_binding_matches")))
            throw new SecurityException("operator-feature-inactive");
        if (!read) {
            if (request.getLong("expected_pid") != android.os.Process.myPid()
                    || request.getLong("expected_uid") != android.os.Process.myUid()
                    || request.getLong("expected_process_start_ticks") != processStartTicks())
                throw new SecurityException("operator-actor-mismatch");
            if (!"shell-experiment-operator-v1".equals(request.getString("operator_origin")))
                throw new SecurityException("operator-origin-invalid");
            String binding = request.getString("expected_binding_sha256");
            if (!binding.matches("[0-9a-f]{64}")
                    || !binding.equals(snapshot.getString("activation_binding_sha256")))
                throw new SecurityException("operator-binding-mismatch");
            String apk = request.getString("expected_apk_sha256");
            if (!apk.matches("[0-9a-f]{64}") || !apk.equals(installedApkSha256(context)))
                throw new SecurityException("operator-apk-mismatch");
        }
        if ("enroll-trusted-lan".equals(operation)||"provision-headset-identity".equals(operation)) {
            final JSONObject frozen=new JSONObject(request.toString());
            ExperimentSessionCommandGateway gateway=ExperimentSessionCommandGateway.existing();
            if(gateway==null)throw new SecurityException("local-enrollment-owner-unavailable");
            ExperimentSessionCommandGateway.LocalEnrollmentGuard guard=new ExperimentSessionCommandGateway.LocalEnrollmentGuard(){
                @Override public void check()throws Exception {validateEnrollmentActor(context,frozen);}
            };
            JSONObject receipt="provision-headset-identity".equals(operation)?gateway.provisionHeadsetIdentity(frozen,guard):gateway.enrollFromLocalOperator(frozen,guard);
            if("provision-headset-identity".equals(operation))receipt.put("operation_id",request.getString("operation_id"));
            return new JSONObject().put("schema","rusty.quest.experiment_session.operator_receipt.v1")
                .put("operation",operation).put("native_response",receipt).toString();
        }
        boolean legacy = !read && !"set-bias".equals(operation) && !"official-start".equals(operation) && !"pause".equals(operation) && !"resume".equals(operation);
        if (legacy) return new JSONObject().put("schema","rusty.quest.experiment_session.operator_receipt.v1")
            .put("native_response",BreathCompositionPanelModule.queueExperimentOperator(request)).toString();
        JSONObject command = new JSONObject()
            .put("schema", "rusty.quest.experiment_session.command.v1")
            .put("operation", read ? "status" : "operator-" + operation);
        if (!read) {
            command.put("operation_id", request.getString("operation_id"))
                .put("expected_generation", request.getLong("expected_generation"))
                .put("elapsed_realtime_ns", android.os.SystemClock.elapsedRealtimeNanos())
                .put("operator_origin", "shell-experiment-operator-v1")
                .put("expected_runtime_epoch", request.getLong("expected_runtime_epoch"))
                .put("expected_session_key", request.getString("expected_session_key"))
                .put("expected_condition", request.getString("expected_condition"))
                .put("expected_profile_sha256", request.getString("expected_profile_sha256"))
                .put("expected_binding_sha256", request.getString("expected_binding_sha256"));
        }
        if ("set-bias".equals(operation)) command.put("bias_percent",request.getInt("bias_percent"));
        JSONObject nativeResponse = new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(command.toString()));
        JSONObject enrollment=new JSONObject().put("available",false);
        ExperimentSessionCommandGateway gateway=ExperimentSessionCommandGateway.existing();
        if(read&&gateway!=null)enrollment=gateway.localOperatorEnrollmentStatus();
        return new JSONObject().put("schema", "rusty.quest.experiment_session.operator_receipt.v1")
            .put("operation", operation).put("pid", android.os.Process.myPid())
            .put("uid", android.os.Process.myUid()).put("process_start_ticks", processStartTicks())
            .put("read_completed_elapsed_ns", android.os.SystemClock.elapsedRealtimeNanos())
            .put("local_enrollment",enrollment)
            .put("native_response", nativeResponse).put("owner_receipt",BreathCompositionPanelModule.experimentOperatorOwnerReceipt())
            .put("audio_readiness", new org.json.JSONArray()
                .put(ControlPanelActivity.conditionAudioReadiness("condition-a"))
                .put(ControlPanelActivity.conditionAudioReadiness("condition-b")))
            .toString();
    }

    static void validateShape(JSONObject request) throws Exception {
        String operation = request.getString("operation");
        boolean modeRead="control-mode-status".equals(operation),modeReceipt="control-mode-receipt".equals(operation),modeSet="set-control-mode".equals(operation);
        boolean read = "status".equals(operation)||modeRead||modeReceipt;
        if (!read && !"set-bias".equals(operation) && !"enroll-trusted-lan".equals(operation) && !"provision-headset-identity".equals(operation) && !"official-start".equals(operation) && !"pause".equals(operation)
                && !"resume".equals(operation) && !modeSet && !ExperimentSessionBleProtocol.supportedOperation(operation)) throw new SecurityException("operator-operation-invalid");
        String[] fields = read ? new String[] {"schema", "operation"} : new String[] {
            "schema", "operation", "operation_id", "expected_pid", "expected_uid",
            "expected_process_start_ticks", "expected_apk_sha256", "expected_binding_sha256",
            "expected_runtime_epoch", "expected_generation", "expected_session_key",
            "expected_condition", "expected_profile_sha256", "operator_origin"};
        if(modeReceipt)fields=new String[]{"schema","operation","operation_id"};
        if(modeSet){String[] extended=java.util.Arrays.copyOf(fields,fields.length+6);
            String[] added={"requested_mode","expected_mode_packed","expected_mode_revision","expected_host_id","expected_carrier_generation","expected_native_owner_epoch"};
            System.arraycopy(added,0,extended,fields.length,added.length);fields=extended;}
        if ("set-bias".equals(operation)) { String[] extended=java.util.Arrays.copyOf(fields,fields.length+1);extended[fields.length]="bias_percent";fields=extended; }
        if ("arm".equals(operation)) { String[] extended=java.util.Arrays.copyOf(fields,fields.length+2); extended[fields.length]="requested_condition";extended[fields.length+1]="requested_bias";fields=extended; }
        if ("enroll-trusted-lan".equals(operation)) {
            String[] extended=java.util.Arrays.copyOf(fields,fields.length+4);
            extended[fields.length]="trusted_lan_consent";extended[fields.length+1]="expected_native_owner_identity";
            extended[fields.length+2]="expected_gateway_lifecycle_revision";extended[fields.length+3]="expected_enrollment_reference";fields=extended;
        }
        if ("provision-headset-identity".equals(operation)) {
            String[] extended=java.util.Arrays.copyOf(fields,fields.length+3);
            extended[fields.length]="hardware_serial";extended[fields.length+1]="expected_native_owner_identity";
            extended[fields.length+2]="expected_gateway_lifecycle_revision";fields=extended;
        }
        if (request.length() != fields.length) throw new SecurityException("operator-unexpected-field");
        for (String field : fields) if (!request.has(field)) throw new SecurityException("operator-missing-field");
        for (String field : fields) {
            Object raw = request.opt(field);
            if (field.equals("trusted_lan_consent")) {
                if(!Boolean.TRUE.equals(raw))throw new SecurityException("trusted-lan-consent-required");
            } else if (field.equals("expected_mode_packed")||field.equals("expected_mode_revision")||field.equals("expected_carrier_generation")||field.equals("expected_native_owner_epoch")||field.equals("expected_native_owner_identity") || field.equals("expected_gateway_lifecycle_revision") || field.equals("expected_pid") || field.equals("expected_uid")
                    || field.equals("expected_process_start_ticks") || field.equals("expected_runtime_epoch")
                    || field.equals("expected_generation") || field.equals("requested_bias") || field.equals("bias_percent")) {
                if (!(raw instanceof Integer) && !(raw instanceof Long))
                    throw new SecurityException("operator-integer-field-required");
                if (((Number) raw).longValue() < 0L || (((Number) raw).longValue()==0L && !field.equals("expected_generation") && !field.equals("requested_bias") && !field.equals("bias_percent")))
                    throw new SecurityException("operator-positive-field-required");
            } else if (!(raw instanceof String)) throw new SecurityException("operator-string-field-required");
        }
        if(modeSet||modeReceipt){
            if(!ExperimentSessionBleProtocol.isLowerHex(request.getString("operation_id"),16))throw new SecurityException("operator-id-invalid");
        }
        if(modeSet&&(!request.getString("requested_mode").matches("direct|remote")
                ||!"shell-experiment-operator-v1".equals(request.getString("operator_origin"))))throw new SecurityException("operator-mode-invalid");
        if ("set-bias".equals(operation) && request.getLong("bias_percent")>100) throw new SecurityException("operator-bias-invalid");
        if ("provision-headset-identity".equals(operation)&&!ExperimentSessionHeadsetIdentity.valid(request.getString("hardware_serial")))throw new SecurityException("hardware-serial-invalid");
        if ("arm".equals(operation) && (!request.getString("requested_condition").matches("condition-[ab]") || request.getLong("requested_bias")>100)) throw new SecurityException("operator-arm-selection-invalid");
        if (!"rusty.quest.experiment_session.operator_command.v1".equals(request.getString("schema"))) throw new SecurityException("operator-schema-invalid");
    }

    static void validateEnrollmentSession(JSONObject request,JSONObject current,JSONObject breath) throws Exception {
        Object generation=current.opt("generation"),runtime=current.opt("runtime_epoch");
        if((!(generation instanceof Integer)&&!(generation instanceof Long))
                ||(!(runtime instanceof Integer)&&!(runtime instanceof Long)))throw new SecurityException("local-enrollment-native-integer-required");
        if(!Boolean.TRUE.equals(breath.opt("feature_lock_active"))||!Boolean.TRUE.equals(breath.opt("activation_binding_matches"))
                ||!request.getString("expected_binding_sha256").equals(breath.opt("activation_binding_sha256"))
                ||!"idle".equals(current.opt("phase"))||!"idle".equals(current.opt("control_state"))
                ||!current.has("condition_id")||!current.has("non_audio_profile_sha256")||!current.has("session_key")||!current.isNull("session_key")
                ||request.getLong("expected_runtime_epoch")!=current.getLong("runtime_epoch")
                ||request.getLong("expected_generation")!=current.getLong("generation")
                ||!"-".equals(request.getString("expected_session_key"))
                ||!request.getString("expected_condition").equals(current.isNull("condition_id")?"":current.getString("condition_id"))
                ||!request.getString("expected_profile_sha256").equals(current.isNull("non_audio_profile_sha256")?"":current.getString("non_audio_profile_sha256")))
            throw new SecurityException("local-enrollment-session-fence-mismatch");
    }
    static void validateEnrollmentActorFacts(JSONObject request,long pid,long uid,long birth,String apk)throws Exception {
        if(request.getLong("expected_pid")!=pid||request.getLong("expected_uid")!=uid
                ||request.getLong("expected_process_start_ticks")!=birth||!request.getString("expected_apk_sha256").equals(apk))
            throw new SecurityException("local-enrollment-actor-changed");
    }
    private static void validateEnrollmentActor(Context context,JSONObject request)throws Exception {
        validateEnrollmentActorFacts(request,android.os.Process.myPid(),android.os.Process.myUid(),processStartTicks(),installedApkSha256(context));
        JSONObject response=new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(
            new JSONObject().put("schema","rusty.quest.experiment_session.command.v1").put("operation","status").toString()));
        if(!"accepted".equals(response.opt("command_status")))throw new SecurityException("local-enrollment-readback-unavailable");
        validateEnrollmentSession(request,response.getJSONObject("readback"),
            new JSONObject(ControlPanelActivity.nativeReadBreathCompositionStatus()).getJSONObject("snapshot"));
    }

    private static long processStartTicks() throws Exception {
        byte[] raw = new byte[4096];
        int size;
        try (java.io.FileInputStream input = new java.io.FileInputStream("/proc/self/stat")) {
            size = input.read(raw);
            if (size <= 0 || size == raw.length) throw new SecurityException("operator-stat-invalid");
        }
        String stat = new String(raw, 0, size, StandardCharsets.UTF_8);
        int close = stat.lastIndexOf(')');
        if (close < 0) throw new SecurityException("operator-stat-invalid");
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        if (fields.length < 20) throw new SecurityException("operator-stat-invalid");
        long ticks = Long.parseLong(fields[19]);
        if (ticks <= 0) throw new SecurityException("operator-stat-invalid");
        return ticks;
    }

    private static String installedApkSha256(Context context) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.FileInputStream input = new java.io.FileInputStream(context.getApplicationInfo().sourceDir)) {
            byte[] block = new byte[65536]; long total = 0; int read;
            while ((read = input.read(block)) != -1) {
                total += read;
                if (total > 64L * 1024L * 1024L) throw new SecurityException("operator-apk-too-large");
                digest.update(block, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

}
