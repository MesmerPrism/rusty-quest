package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import org.json.JSONObject;
import java.util.function.BooleanSupplier;

/** Explicit platform, process-executor and SDK resource leaves; owners compile whole. */
class EmbeddedDuplexProcessHost {
    static Context context;
    static boolean exchanges=true;
    static boolean nativeActor=true;
    static boolean appRecord=true;
    static long appGeneration=74;
    static EmbeddedDuplexOriginals.Record completed;
    static final JSONObject ACTOR=new JSONObject().put("app_process_generation",74).put("app_record_sha256","a".repeat(64))
        .put("native_executor_generation",73).put("config_sha256","b".repeat(64));
    static void forApplication(Context c){context=c;}
    static Context application(){return context;}
    static long currentAppGeneration(){return appGeneration;}
    static JSONObject originalActor(){return new JSONObject().put("package_id","host.fixture")
        .put("native_generation",nativeActor?73:JSONObject.NULL).put("app_record_sha256",appRecord?"a".repeat(64):JSONObject.NULL);}
    static void originalCompletion(EmbeddedDuplexOriginals.Record r){completed=r;}
    static boolean originalExchangesJoined(EmbeddedDuplexOriginals.Record r){return exchanges;}
    static boolean originalIncomingExchangesJoined(EmbeddedDuplexOriginals.Record r){return true;}
    static boolean priorOriginalScopeDisposed(JSONObject r){return false;}
    static boolean continuationIngressQuiescent(EmbeddedDuplexOriginals.Record r){return true;}
    static JSONObject continuationJoinIdentity(EmbeddedDuplexOriginals.Record r){return ACTOR;}
    static void requireCurrentOriginalStopActor(EmbeddedDuplexOriginals.Record r){}
    static void requireUnknownPeerRootCustodySpan(EmbeddedDuplexOriginals.Record r,JSONObject a){}
    static void requirePreparedCleanupArchive(String id){}
    static void requirePreparedAbortContext(JSONObject request){}
    static void releaseClosedOwnerAfterCustody(Object o){}
    static String signalOriginalStop(EmbeddedDuplexOriginals.Record r){return null;}
    static void requireCompletedNoMediaOriginal(EmbeddedDuplexOriginals.Record r,JSONObject v){}
    static void requirePreparedOriginal(EmbeddedDuplexOriginals.Record r,JSONObject v)throws Exception{
        if(!r.id().equals(v.getString("original_id")))throw new IllegalStateException("modeled JNI Original differs");
    }
}
class EmbeddedDuplexNative {static void beginOperationDiagnostics(String op,String id){}static void endOperationDiagnostics(){}}
class EmbeddedDuplexOperationDiagnostics {
    static void initialize(Context c){}static void completed(EmbeddedDuplexOriginals.Record r){}
    static void observe(EmbeddedDuplexOriginals.Record r,String phase,String owner,String stage,Throwable t,JSONObject safe){}
}
class EmbeddedDuplexConstructionClosure {static final String WHOLE="not-selected";}
class EmbeddedDuplexRuntimeConfigurationBinding {
    static final String WHOLE_V2="not-selected";
    static class Context {void require(JSONObject v)throws Exception {if(!v.getBoolean("actual_constructor"))throw new IllegalStateException();}}
}
class EmbeddedDuplexFreshHandoffStore {
    EmbeddedDuplexFreshHandoffStore(Context c){}
    String priorBody(String id){return null;}String operatorBody(String id){return null;}
    JSONObject retainOperator(String s,JSONObject a){throw new AssertionError("not a fresh-setup fixture");}
    static void keys(JSONObject v,String...k)throws Exception{EmbeddedDuplexPeerRoots.keys(v,k);}
}
class EmbeddedDuplexFreshPhaseContexts {
    EmbeddedDuplexFreshPhaseContexts(Context c){}
    void bindOperator(String a,String b,JSONObject c,JSONObject d){throw new AssertionError();}
    void requireOperator(String a,String b,JSONObject c,JSONObject d){throw new AssertionError();}
}
class EmbeddedDuplexFreshPhaseArchive {
    EmbeddedDuplexFreshPhaseArchive(Context c){}JSONObject requireRetained(JSONObject v,JSONObject a){throw new AssertionError();}
}
class EmbeddedDuplexIncomingFreshPhases {static void id(String id){if(!id.matches("[0-9a-f]{32}"))throw new IllegalArgumentException();}}

class EmbeddedDuplexResources {
    boolean terminal,retired,opened;String rendererId;
    JSONObject preparationTarget(String config){return new JSONObject().put("own_capture_selected",true);}
    String preparationTargetSha256(String config){return "sha256:"+"c".repeat(64);}
    long generation(){return 73;}
    JSONObject prepareSender(BooleanSupplier stop){return new JSONObject().put("modeled_format",true);}
    boolean preparedSenderClosed(){return !opened;}boolean preparedDecoderClosed(){return !opened;}
    String preparedSenderHandle(){return "modeled.sender";}String preparedDecoderHandle(){return "modeled.decoder";}
    boolean preparedSenderOwned(){return !terminal;}boolean preparedDecoderOwned(){return !terminal;}
    boolean preparedTransportConnected(){return true;}
    void finalizePreparedTransport(){}
    boolean preparedDeliveryOpened(){return opened;}long preparedPeerDeliveryCount(){return opened?1:0;}
    boolean productResourcesTerminal(){return terminal;}
    JSONObject prepareDecoder(String id,JSONObject format,BooleanSupplier stop){rendererId=id;return renderer(false);}
    JSONObject abortPreparedResources(String id){if(!id.equals(rendererId))throw new IllegalStateException();terminal=true;return renderer(true);}
    void requestPreparedStop(){}
    JSONObject preparedTerminalOwners(){return new JSONObject();}
    void retireArchivedPreparedMetadata(String id){if(!terminal||!id.equals(rendererId))throw new IllegalStateException();retired=true;}
    JSONObject renderer(boolean released){return PreparedSettledCustodyHost.renderer(rendererId,released);}
}
