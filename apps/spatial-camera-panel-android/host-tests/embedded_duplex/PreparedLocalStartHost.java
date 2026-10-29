package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.Path;
import java.nio.file.Files;
import org.json.JSONObject;

/** Whole production holder and the actual ordinaryStart -> Platform validation route. */
public final class PreparedLocalStartHost {
    static int controls;
    interface Call{void run()throws Exception;}
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void refused(Call call)throws Exception{try{call.run();throw new AssertionError("invalid local Start accepted");}catch(IllegalStateException|IllegalArgumentException expected){}}
    static JSONObject copy(JSONObject value){return new JSONObject(value.toString());}
    static JSONObject actor(){return new JSONObject().put("app_process_generation",74).put("app_record_sha256","a".repeat(64))
        .put("native_executor_generation",73).put("config_sha256","b".repeat(64));}
    static JSONObject identity(JSONObject value){for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})value.put(key,actor().get(key));return value;}
    static final class Fixture{
        final EmbeddedDuplexResources resources=new EmbeddedDuplexResources();
        final EmbeddedDuplexOriginals.Record actual=new EmbeddedDuplexOriginals.Record();
        EmbeddedDuplexPreparedStart holder;JSONObject status;
        Fixture(Path path)throws Exception{Files.createDirectory(path);}
        void prepare()throws Exception{
            JSONObject arm=new JSONObject().put("binding","established").put("challenge","d".repeat(32))
                .put("arm_generation",1).put("native_process",73).put("process_epoch","modeled.epoch").put("runtime_config_sha256","b".repeat(64));
            JSONObject request=identity(new JSONObject().put("$schema","rusty.quest.embedded_duplex.prepared_start_request.v1")
                .put("preparation_id","3".repeat(32)).put("original_id",actual.id()))
                .put("arm_binding",arm).put("configuration_binding",new JSONObject().put("actual_constructor",true))
                .put("resource_target",resources.preparationTarget("b".repeat(64)))
                .put("resource_target_sha256",resources.preparationTargetSha256("b".repeat(64)));
            holder=new EmbeddedDuplexPreparedStart(resources,request,actual,actor(),arm,new EmbeddedDuplexRuntimeConfigurationBinding.Context());
            JSONObject sender=holder.sender();String requestSha=sender.getString("request_sha256");
            JSONObject decoderRequest=new JSONObject().put("$schema","rusty.quest.android.media.prepared_decoder_request.v1")
                .put("preparation_id",holder.id()).put("request_sha256",requestSha).put("peer_preparation_id","4".repeat(32))
                .put("peer_sender_receipt_sha256","sha256:"+"e".repeat(64)).put("peer_format",new JSONObject());
            JSONObject decoder=holder.decoder(decoderRequest);holder.finalizeTransport();
            status=identity(new JSONObject().put("$schema","rusty.quest.embedded_duplex.prepared_peer_start_status.v1")
                .put("preparation_id",holder.id()).put("original_id",actual.id())).put("request_sha256",requestSha)
                .put("resource_target_sha256",request.getString("resource_target_sha256")).put("owner",new JSONObject()
                    .put("$schema","rusty.quest.android.media.prepared_start_owner.v1").put("request",request)
                    .put("sender_entered",true).put("decoder_entered",true).put("sender",sender)
                    .put("decoder_request",decoderRequest).put("decoder",decoder)).put("remote_decoder",decoder)
                .put("state","ready").put("local_start_consumed",false).put("media_authority_granted",false);
            holder.requireNativeStatus(status,true);
        }
    }
    static Fixture fixture(Path root,String name)throws Exception {
        Fixture value=new Fixture(root.resolve(name));
        value.prepare();return value;
    }
    static void qualify(Fixture value,JSONObject status)throws Exception {
        new LocalStartPlatform(value.holder).qualify(status);
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createDirectories(Path.of(args[0]));
        Fixture gated=fixture(root,"gated");qualify(gated,gated.status);controls++;
        for(String state:new String[]{"receiver_armed","receiving"}) {
            Fixture incoming=fixture(root,"incoming-"+state);
            incoming.resources.incomingOpened=true;incoming.resources.receiverState=state;
            check(incoming.holder.ready(),"same physical owner readiness lost");
            // This models authenticated opposite-direction arrival before local
            // Start. No local Start was consumed and the local sender stays gated.
            qualify(incoming,incoming.status);controls++;
            refused(()->incoming.holder.requireNativeStatus(incoming.status,true));controls++;
            JSONObject consumed=copy(incoming.status).put("local_start_consumed",true);
            refused(()->qualify(incoming,consumed));controls++;
        }
        for(String state:new String[]{"failed","stopping","stopped","new","preparing_decoder"}) {
            Fixture bad=fixture(root,"bad-"+state);
            bad.resources.incomingOpened=true;bad.resources.receiverState=state;
            refused(()->qualify(bad,bad.status));controls++;
        }
        for(String invalid:new String[]{"missing","foreign","sender-open","stop"}) {
            Fixture bad=fixture(root,"invalid-"+invalid);
            bad.resources.incomingOpened=true;bad.resources.receiverState="receiving";
            if(invalid.equals("missing"))bad.resources.receiverMissing=true;
            if(invalid.equals("foreign"))bad.resources.rendererId="f".repeat(32);
            if(invalid.equals("sender-open"))bad.resources.opened=true;
            if(invalid.equals("stop"))bad.actual.desiredStop.set(true);
            refused(()->qualify(bad,bad.status));controls++;
        }
        for(String field:new String[]{"app_process_generation","native_executor_generation","config_sha256","owner","remote_decoder"}) {
            Fixture bad=fixture(root,"subject-"+field);
            bad.resources.incomingOpened=true;bad.resources.receiverState="receiving";
            JSONObject status=copy(bad.status);
            if(field.equals("owner"))status.getJSONObject("owner").getJSONObject("request").getJSONObject("arm_binding").put("arm_generation",99);
            else if(field.equals("remote_decoder"))status.put(field,JSONObject.NULL);
            else status.put(field,field.equals("config_sha256")?"f".repeat(64):99);
            refused(()->qualify(bad,status));controls++;
        }
        controls+=LocalReceiverQualification.controls();
        System.out.println("PASS production local Start qualification controls="+controls+"; exact incoming active owner accepted, gated preparation and foreign/failed/stopped/consumed guards preserved; no Native/device execution");
    }
}

/** Only SDK resource/Original custody leaves are modeled; the entire holder executes. */
class EmbeddedDuplexOriginals{static final class Record{
    final java.util.concurrent.atomic.AtomicBoolean desiredStop=new java.util.concurrent.atomic.AtomicBoolean();
    final JSONObject row=new JSONObject().put("operation","prepare_peer_start").put("state","succeeded").put("consumer_receipt_sha256","modeled");
    boolean operationJoined=true;String id(){return "1".repeat(32);}
}}
class EmbeddedDuplexProcessHost{static boolean originalExchangesJoined(EmbeddedDuplexOriginals.Record ignored){return true;}static void requirePreparedAbortContext(JSONObject ignored){}}
class EmbeddedDuplexRuntimeConfigurationBinding{static final class Context{void require(JSONObject value){if(!value.getBoolean("actual_constructor"))throw new IllegalArgumentException("constructor differs");}}}
class EmbeddedDuplexPreparedRenderer{static void identity(JSONObject ignored,JSONObject request,String sha){}}
class EmbeddedDuplexResources{
    boolean incomingOpened,opened,receiverMissing,terminal;String rendererId,receiverState="dormant";
    JSONObject preparationTarget(String config){return new JSONObject().put("modeled_target",true);}
    String preparationTargetSha256(String config){return "sha256:"+"c".repeat(64);}
    long generation(){return 73;}
    JSONObject prepareSender(java.util.function.BooleanSupplier stop){return new JSONObject();}
    JSONObject prepareDecoder(String id,JSONObject format,java.util.function.BooleanSupplier stop){rendererId=id;return new JSONObject();}
    boolean preparedSenderClosed(){return !opened;}boolean preparedDecoderClosed(){return !preparedDeliveryOpened();}
    boolean preparedSenderOwned(){return !terminal;}boolean preparedDecoderOwned(){return !terminal&&!receiverMissing;}
    boolean preparedDecoderStartable(String id){return id.equals(rendererId)&&preparedDecoderOwned()
        &&java.util.Arrays.asList("dormant","receiver_armed","receiving").contains(receiverState);}
    String preparedSenderHandle(){return "modeled.sender";}String preparedDecoderHandle(){return "modeled.decoder";}
    boolean preparedTransportConnected(){return true;}void finalizePreparedTransport(){}
    boolean preparedDeliveryOpened(){return opened||incomingOpened;}long preparedPeerDeliveryCount(){return incomingOpened?1:0;}
    boolean productResourcesTerminal(){return terminal;}void requestPreparedStop(){}
    JSONObject abortPreparedResources(String id){terminal=true;return new JSONObject();}
    JSONObject preparedTerminalOwners(){return new JSONObject();}void retireArchivedPreparedMetadata(String id){}
}
