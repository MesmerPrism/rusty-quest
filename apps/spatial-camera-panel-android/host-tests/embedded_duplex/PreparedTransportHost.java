package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;
import io.github.mesmerprism.rustyquest.media.PreparedTransportPipeline;

/** Whole production prepared holder; exact Platform/Resources/Pipeline methods.
 * Android decoder/renderer/source allocation, Original store and callback fence
 * leaves are explicit models. LAN transport and loopback sockets are real. */
public final class PreparedTransportHost {
    static int checks;
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);checks++;}
    interface Action{void run()throws Exception;}
    static void refuse(Action f)throws Exception{try{f.run();}catch(Exception expected){checks++;return;}throw new AssertionError("expected refusal");}
    static int port()throws Exception{try(ServerSocket s=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){return s.getLocalPort();}}
    static JSONObject format()throws Exception{return new JSONObject().put("mime","video/avc").put("width",16).put("height",16);}
    static JSONObject actor()throws Exception{return new JSONObject().put("app_process_generation",3L).put("app_record_sha256","a".repeat(64)).put("native_executor_generation",7L).put("config_sha256","b".repeat(64));}
    static final class Fixture implements AutoCloseable {
        final EmbeddedDuplexResources resources;final EmbeddedDuplexOriginals.Record original;
        final EmbeddedDuplexPreparedStart held;final EmbeddedDuplexPlatform platform=new EmbeddedDuplexPlatform();final JSONObject request;
        Fixture(int local,int remote,String id)throws Exception{
            resources=new EmbeddedDuplexResources(local,remote);original=new EmbeddedDuplexOriginals.Record(id);
            JSONObject arm=new JSONObject().put("MODELED_ARM",true);
            request=actor().put("$schema","rusty.quest.embedded_duplex.prepared_start_request.v1").put("preparation_id",id)
                .put("original_id",id).put("arm_binding",arm).put("configuration_binding",new JSONObject())
                .put("resource_target",resources.preparationTarget("unused")).put("resource_target_sha256",resources.preparationTargetSha256("unused"));
            held=new EmbeddedDuplexPreparedStart(resources,request,original,actor(),arm,new EmbeddedDuplexRuntimeConfigurationBinding.Context());platform.preparedStart=held;
            held.sender();
        }
        void decoder(String peer)throws Exception{
            held.decoder(new JSONObject().put("$schema","rusty.quest.android.media.prepared_decoder_request.v1")
                .put("preparation_id",held.id()).put("request_sha256",digest(request))
                .put("peer_preparation_id",peer).put("peer_sender_receipt_sha256","sha256:"+"c".repeat(64)).put("peer_format",format()));
        }
        String digest(JSONObject r)throws Exception{
            byte[] prefix=("rusty.quest.embedded_duplex.prepared_start_request.v1\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] b=EmbeddedDuplexCanonicalJson.encode(r).getBytes(java.nio.charset.StandardCharsets.UTF_8),all=Arrays.copyOf(prefix,prefix.length+b.length);System.arraycopy(b,0,all,prefix.length,b.length);
            return "sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(all);
        }
        void finalizeTransport()throws Exception{platform.finalizePeerStartTransport(request.toString());}
        public void close()throws Exception{resources.requestPreparedStop();resources.pipeline.close();resources.incoming.close();}
    }
    static void listenerFirstAndTwoLifetimes()throws Exception{
        int qp=port(),wp=port();
        for(int life=0;life<2;life++)try(Fixture q=new Fixture(qp,wp,(life==0?"1":"3").repeat(32));Fixture w=new Fixture(wp,qp,(life==0?"2":"4").repeat(32))){
            q.decoder(w.held.id());check(q.resources.incoming.listener!=null&&w.resources.incoming.listener==null,"local callback returns before peer decoder enters");
            check(!q.held.ready()&&!q.resources.pipeline.entered(),"no early transport/ready");
            w.decoder(q.held.id());check(!w.held.ready(),"decoder receipts alone do not authorize ready");
            q.finalizeTransport();w.finalizeTransport();check(q.held.ready()&&w.held.ready(),"actual bilateral connected owners ready");
            check(q.held.status(q.held.id()).getBoolean("delivery_gate_closed"),"closed readback");
            check(q.resources.pipeline.zeroDelivery()&&w.resources.pipeline.zeroDelivery(),"no media opened by connect");
            refuse(q::finalizeTransport);check(q.platform.leases==0,"callback lease released after refusal");
            q.resources.pipeline.requestStop();check(!q.held.ready(),"actual lost connection revokes readiness");
        }
        System.out.println("PASS listener-first callbacks/finalize, real connections/closed gates, two lifetimes and no replay");
    }
    static void requestAndMissingOwnerRefusals()throws Exception{
        try(Fixture q=new Fixture(port(),port(),"5".repeat(32))){
            refuse(q::finalizeTransport);check(!q.resources.pipeline.entered(),"no transport before decoder");
            JSONObject altered=new JSONObject(q.request.toString()).put("original_id","6".repeat(32));
            refuse(()->q.platform.finalizePeerStartTransport(altered.toString()));check(!q.resources.pipeline.entered(),"foreign request no effect");
            q.platform.retired=true;refuse(q::finalizeTransport);check(q.platform.leases==0,"retired callback no effect");
            q.platform.retired=false;q.platform.preparedStart=null;refuse(q::finalizeTransport);check(q.platform.leases==0,"missing owner no effect");
        }
        System.out.println("PASS request/Original/fence/absent-owner/readiness preconditions");
    }
    static void realRefusedConnectionRetainsEntered()throws Exception{
        try(Fixture q=new Fixture(port(),port(),"7".repeat(32))){
            q.decoder("8".repeat(32));refuse(q::finalizeTransport);check(!q.held.ready(),"failed connect is not ready");
            check(q.resources.pipeline.diagnostic().contains("firstStage=SINK_CONNECT,firstCause=REFUSED"),"actual retained refused cause");
            refuse(q::finalizeTransport);check(q.platform.leases==0,"no replay after real entered failure");
        }
        System.out.println("PASS real refused-connect retains entered/no replay and actual cleanup joins");
    }
    static void concurrentAndStop(boolean stop)throws Exception{
        int qp=port(),wp=port();try(Fixture q=new Fixture(qp,wp,"9".repeat(32));Fixture w=new Fixture(wp,qp,"a".repeat(32))){
            q.decoder(w.held.id());w.decoder(q.held.id());q.resources.pipeline.block();
            AtomicReference<Throwable> failure=new AtomicReference<>();Thread first=new Thread(()->{try{q.finalizeTransport();}catch(Throwable e){failure.set(e);}});
            first.start();check(q.resources.pipeline.awaitEntered(),"first finalizer reached modeled source-allocation barrier");
            try{refuse(q::finalizeTransport);check(q.platform.leases==1,"first callback lease remains entered");
                if(stop){q.original.desiredStop.set(true);q.resources.requestPreparedStop();}
            }finally{q.resources.pipeline.release();first.join(5000);}
            check(!first.isAlive()&&q.platform.leases==0,"all callbacks joined");
            check(stop?(failure.get()!=null&&!q.held.ready()):(failure.get()==null&&q.held.ready()),"Stop/concurrent outcome");
            refuse(q::finalizeTransport);
        }
        System.out.println("PASS concurrent finalization / Stop="+stop+" / same entered effect and callback join");
    }
    public static void main(String[] args)throws Exception{
        listenerFirstAndTwoLifetimes();requestAndMissingOwnerRefusals();realRefusedConnectionRetainsEntered();concurrentAndStop(false);concurrentAndStop(true);
        System.out.println(checks+" production holder/callback/resource/pipeline controls PASS; Android/Original leaves modeled");
    }
}
class EmbeddedDuplexPlatform {
    EmbeddedDuplexPreparedStart preparedStart;boolean retired;volatile int leases;
    EmbeddedDuplexProcessFence.CallbackLease enterProcessCallback(){synchronized(this){if(retired)throw new IllegalStateException("MODELED retired fence");leases++;}return new EmbeddedDuplexProcessFence.CallbackLease(this);}
    void requireProcessCallback(){if(retired)throw new IllegalStateException("MODELED retired fence");}
    PRODUCTION_FINALIZE_CALLBACK
}
class EmbeddedDuplexProcessFence {static class CallbackLease implements AutoCloseable {final EmbeddedDuplexPlatform p;CallbackLease(EmbeddedDuplexPlatform p){this.p=p;}public void close(){synchronized(p){p.leases--;}}}}
class EmbeddedDuplexResources {
    final Incoming incoming;final PreparedTransportPipeline pipeline;
    EmbeddedDuplexResources(int local,int remote)throws Exception{incoming=new Incoming(local);pipeline=new PreparedTransportPipeline(remote);}
    PRODUCTION_RESOURCE_METHODS
    static final class Incoming implements AutoCloseable{final int port;ServerSocket listener;boolean stopped;Incoming(int p){port=p;}
        JSONObject prepareDormantDecoder(String id,JSONObject peer,BooleanSupplier stop)throws Exception{if(stop.getAsBoolean()||listener!=null)throw new IllegalStateException("MODELED decoder reentry/Stop");listener=new ServerSocket(port,1,InetAddress.getLoopbackAddress());return new JSONObject().put("MODELED_RENDERER",true);}
        public void close()throws Exception{stopped=true;if(listener!=null)listener.close();}
    }
    JSONObject preparationTarget(String c)throws Exception{return new JSONObject().put("MODELED_TARGET",true);}
    String preparationTargetSha256(String c){return "sha256:"+"d".repeat(64);}
    JSONObject prepareSender(BooleanSupplier stop)throws Exception{return PreparedTransportHost.format();}
    long generation(){return 7;}boolean preparedSenderOwned(){return pipeline.held();}boolean preparedDecoderOwned(){return incoming.listener!=null&&!incoming.listener.isClosed();}
    boolean preparedSenderClosed(){return pipeline.zeroDelivery();}boolean preparedDecoderClosed(){return preparedDecoderOwned()&&!incoming.stopped;}
    String preparedSenderHandle(){return "MODELED_SENDER";}String preparedDecoderHandle(){return "MODELED_DECODER";}
    boolean preparedDeliveryOpened(){return false;}long preparedPeerDeliveryCount(){return 0;}
    boolean productResourcesTerminal(){return pipeline.terminal()&&incoming.stopped;}
    void requestPreparedStop(){pipeline.requestStop();try{incoming.close();}catch(Exception e){throw new IllegalStateException(e);}}
    JSONObject abortPreparedResources(String id)throws Exception{requestPreparedStop();pipeline.close();return new JSONObject();}
    void retireArchivedPreparedMetadata(String id){if(!productResourcesTerminal())throw new IllegalStateException("MODELED terminal archive required");}
    JSONArray preparedTerminalOwners()throws Exception{JSONArray v=new JSONArray();for(int i=0;i<7;i++)v.put(new JSONObject().put("MODELED_OWNER",i));return v;}
}
class EmbeddedDuplexOriginals {static class Record{final String id;final JSONObject row;final AtomicBoolean desiredStop=new AtomicBoolean();boolean operationJoined=true;Record(String i)throws Exception{id=i;row=new JSONObject().put("operation","prepare_peer_start").put("state","succeeded").put("consumer_receipt_sha256","MODELED");}String id(){return id;}}static EmbeddedDuplexOriginals forApplication(Object o){return new EmbeddedDuplexOriginals();}void requirePreparedWholeClosure(JSONObject a,JSONObject b){}}
class EmbeddedDuplexRuntimeConfigurationBinding {static class Context{void require(JSONObject v){}}}
class EmbeddedDuplexPreparedRenderer {static void identity(JSONObject a,JSONObject b,String c){}}
class EmbeddedDuplexProcessHost {static boolean originalExchangesJoined(EmbeddedDuplexOriginals.Record r){return true;}static void requirePreparedAbortContext(JSONObject v){}static Object application(){return null;}static JSONObject freshClosureIdentity(EmbeddedDuplexPlatform p)throws Exception{return PreparedTransportHost.actor();}}
class EmbeddedDuplexPeerRoots {
    static void keys(JSONObject o,String... keys){Set<String> expected=new HashSet<>(Arrays.asList(keys));Set<String> got=new HashSet<>();o.keys().forEachRemaining(got::add);if(!got.equals(expected))throw new IllegalStateException("MODELED closed keys");}
    static void identity(JSONObject a,JSONObject b)throws Exception{for(String k:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})if(!EmbeddedDuplexCanonicalJson.sameValue(a.get(k),b.get(k)))throw new IllegalStateException("MODELED actor mismatch");}
    static void arm(JSONObject a){}
}
