package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;

/** UNRUN actual helper/factory controls. Native/Android actor facts are MODELED.
 * Compile with the actual policy/canonical helper and explicitly modeled BuildConfig,
 * then run both selected and none variants. No JNI or device is loaded. */
public final class EmbeddedDuplexRuntimeConfigurationBindingHostTest {
    interface Checked {void run()throws Exception;}
    static void need(boolean ok){if(!ok)throw new AssertionError("configuration binding control failed");}
    static void refused(Checked operation)throws Exception{
        try{operation.run();}catch(IllegalStateException expected){return;}
        throw new AssertionError("actual constructor guard accepted damaged input");
    }
    public static void main(String[] ignored)throws Exception{
        if("none".equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY)){
            need(EmbeddedDuplexRuntimeConfigurationBinding.prepare("not a constructor","",null,0,null,0)==null);
            System.out.println("PASS unchanged none branch: no fresh context or claim");return;
        }
        need("source-bound-v2".equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY));
        JSONObject selection=new JSONObject(new String(Base64.getDecoder().decode(
            BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64),StandardCharsets.UTF_8));
        String local=selection.getJSONArray("keys").getJSONObject(0).getString("peer_id");
        JSONObject embedded=new JSONObject().put("ongoing_media_policy",new JSONObject().put("$schema","rusty.manifold.peer.ongoing_media_policy.v2"))
            .put("fresh_setup_local_peer_id",local).put("fresh_setup_enrollment_selection",selection);
        String config=new JSONObject().put("embedded_duplex",embedded).toString();
        String sha=EmbeddedDuplexCanonicalJson.sha256Bytes(config.getBytes(StandardCharsets.UTF_8));
        String record="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        EmbeddedDuplexRuntimeConfigurationBinding.Context context=EmbeddedDuplexRuntimeConfigurationBinding.prepare(config,sha,local,11,record,12);
        JSONObject project=new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_setup_build_policy.v1")
            .put("selection","source-bound-v2").put("policy",EmbeddedDuplexFreshSetupPolicy.policy())
            .put("policy_sha256",BuildConfig.EMBEDDED_DUPLEX_FRESH_POLICY_SHA256)
            .put("enrollment_selection_sha256",BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256);
        JSONObject binding=new JSONObject().put("$schema",EmbeddedDuplexRuntimeConfigurationBinding.SCHEMA)
            .put("config_sha256",sha).put("app_process_generation",11L).put("app_record_sha256",record)
            .put("native_executor_generation",12L).put("local_peer_id",local).put("fresh_setup_build_policy",project);
        JSONObject nativeProof=new JSONObject().put("$schema",EmbeddedDuplexRuntimeConfigurationBinding.NATIVE_V2)
            .put("config_sha256",sha).put("app_process_generation",11L).put("app_record_sha256",record)
            .put("native_executor_generation",12L).put("configuration_binding",binding);
        context.require(binding);EmbeddedDuplexRuntimeConfigurationBinding.requireNative(nativeProof,context);
        need(binding.length()==7&&project.length()==5);
        for(String field:new String[]{"local_peer_id","config_sha256","app_record_sha256","app_process_generation","native_executor_generation","extra"}){
            JSONObject bad=new JSONObject(binding.toString());bad.put(field,field.endsWith("generation")?99L:"foreign");
            refused(()->context.require(bad));
        }
        JSONObject policyBad=new JSONObject(binding.toString());policyBad.getJSONObject("fresh_setup_build_policy").put("policy_sha256","sha256:"+record);
        refused(()->context.require(policyBad));
        JSONObject targetBad=new JSONObject(binding.toString());targetBad.getJSONObject("fresh_setup_build_policy").put("enrollment_selection_sha256","sha256:"+record);
        refused(()->context.require(targetBad));
        JSONObject nativeBad=new JSONObject(nativeProof.toString());nativeBad.put("native_executor_generation",99L);
        refused(()->EmbeddedDuplexRuntimeConfigurationBinding.requireNative(nativeBad,context));
        refused(()->EmbeddedDuplexRuntimeConfigurationBinding.requireNative(nativeProof,null));
        JSONObject old=new JSONObject(nativeProof.toString());old.put("$schema","rusty.quest.embedded_duplex.no_media_retirement.v1");
        refused(()->EmbeddedDuplexRuntimeConfigurationBinding.requireNative(old,context));
        refused(()->EmbeddedDuplexRuntimeConfigurationBinding.prepare(config,record,local,11,record,12));
        refused(()->EmbeddedDuplexRuntimeConfigurationBinding.prepare(config,sha,"peer.foreign",11,record,12));
        JSONObject wrongConfig=new JSONObject(config);wrongConfig.getJSONObject("embedded_duplex").getJSONObject("fresh_setup_enrollment_selection").put("operator_id","operator.foreign");
        String wrong=wrongConfig.toString();String wrongSha=EmbeddedDuplexCanonicalJson.sha256Bytes(wrong.getBytes(StandardCharsets.UTF_8));
        refused(()->EmbeddedDuplexRuntimeConfigurationBinding.prepare(wrong,wrongSha,local,11,record,12));
        originalOwnerControls(context,binding,sha,record);
        System.out.println("PASS actual helper/factory/Original retained-owner controls; physical facts modeled, no authority constructed");
    }
    static void field(Object value,String key,Object replacement)throws Exception{
        java.lang.reflect.Field f=value.getClass().getDeclaredField(key);f.setAccessible(true);f.set(value,replacement);
    }
    static Object get(Object value,String key)throws Exception{
        java.lang.reflect.Field f=value.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(value);
    }
    static Object actualPending(EmbeddedDuplexOriginals.Record original,JSONObject receipt)throws Exception{
        java.lang.reflect.Method method=EmbeddedDuplexOriginals.class.getDeclaredMethod("nativePending",EmbeddedDuplexOriginals.Record.class,String.class,JSONObject.class);method.setAccessible(true);
        try{return method.invoke(null,original,"whole_app_close",new JSONObject().put("receipt",receipt.toString()));}
        catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw(Exception)failed.getCause();throw new AssertionError(failed.getCause());}
    }
    /** Executes actual private owner/classifier bodies against explicitly modeled owner facts.
     * Null platform is a MODEL boundary, no physical/exchange liveness credit. */
    @SuppressWarnings("unchecked")
    static void originalOwnerControls(EmbeddedDuplexRuntimeConfigurationBinding.Context context,JSONObject binding,String sha,String record)throws Exception{
        EmbeddedDuplexOriginals.Record original=new EmbeddedDuplexOriginals.Record(new JSONObject().put("original_id","ffffffffffffffffffffffffffffffff"));
        Class<?> type=Class.forName(EmbeddedDuplexProcessHost.class.getName()+"$RetainedNoMediaClose");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object held=constructor.newInstance(sha,record,"MODELED.epoch",11L,12L,1L,null,null,null,(Runnable)()->{},new JSONObject().put("binding","not_established"),original,context);
        JSONObject nativeProof=new JSONObject().put("$schema",EmbeddedDuplexRuntimeConfigurationBinding.NATIVE_V2)
            .put("native_runtime_binding","configured").put("disposition","runtime_config_closed")
            .put("config_sha256",sha).put("app_process_generation",11L).put("app_record_sha256",record).put("native_executor_generation",12L)
            .put("configuration_binding",binding).put("native_close",new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_closed.v1").put("config_sha256",sha).put("disposition","host_closed"));
        JSONObject whole=new JSONObject().put("$schema",EmbeddedDuplexRuntimeConfigurationBinding.WHOLE_V2)
            .put("action","whole_app_close").put("native_retirement",nativeProof).put("config_sha256",sha)
            .put("app_process_generation",11L).put("app_record_sha256",record).put("native_executor_generation",12L).put("process_epoch","MODELED.epoch")
            .put("arm_observation",new JSONObject().put("binding","not_established"))
            .put("own_capture",new JSONObject().put("phase","Idle").put("capture_ever_owned",false).put("state","retired"));
        for(String key:new String[]{"java_own_capture_cleanup","java_peer_resources_cleanup","java_renderer_cleanup","java_control_cleanup","java_callback_cleanup","command_lane_barrier","whole_app_physical_cleanup","app_process_fence_cleanup"})whole.put(key,"terminal");
        field(held,"nativeProof",nativeProof);field(held,"proof",whole);field(held,"callbackMarker",java.util.concurrent.CompletableFuture.completedFuture(null));
        ((java.util.concurrent.CompletableFuture<String>)get(held,"completion")).complete("MODELED terminal owner");original.retainedCleanupOwner=held;
        need(actualPending(original,whole)==null);
        original.retainedCleanupOwner=null;refused(()->actualPending(original,whole));original.retainedCleanupOwner=held;
        field(held,"callbackMarker",new java.util.concurrent.CompletableFuture<Void>());refused(()->actualPending(original,whole));
        field(held,"callbackMarker",java.util.concurrent.CompletableFuture.completedFuture(null));
        JSONObject changed=new JSONObject(whole.toString());changed.put("app_record_sha256","foreign");refused(()->actualPending(original,changed));
        JSONObject old=new JSONObject(whole.toString());old.put("$schema","rusty.quest.embedded_duplex.no_media_whole_retirement.v1");
        // Legacy classifier remains a distinct branch; it never validates configured v2 as v1.
        field(held,"configurationContext",null);refused(()->actualPending(original,whole));field(held,"configurationContext",context);
        JSONObject pending=new JSONObject(whole.toString()).put("java_callback_cleanup","pending");field(held,"proof",pending);
        refused(()->actualPending(original,pending));field(held,"proof",whole);
    }

}
