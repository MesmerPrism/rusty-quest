package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;

/** One actual resource incarnation. Receipts cannot reconstruct or select its owners. */
final class EmbeddedDuplexPreparedStart {
    private static final String REQUEST="rusty.quest.embedded_duplex.prepared_start_request.v1";
    private final EmbeddedDuplexResources resources;
    private final EmbeddedDuplexOriginals.Record original;
    private final JSONObject request;
    private final String requestSha;
    private final String exactRequest;
    private JSONObject sender,decoder;
    private String exactDecoder;
    private boolean senderEntered,decoderEntered,transportEntered,abortEntered;
    private String exactAbort;
    private JSONObject abortReceipt;
    private long revision;
    EmbeddedDuplexPreparedStart(EmbeddedDuplexResources held,JSONObject supplied,
            EmbeddedDuplexOriginals.Record actual,JSONObject actualActor,JSONObject actualArm,
            EmbeddedDuplexRuntimeConfigurationBinding.Context constructor)throws Exception {
        EmbeddedDuplexPeerRoots.keys(supplied,"$schema","preparation_id","original_id","app_process_generation",
            "app_record_sha256","native_executor_generation","config_sha256","arm_binding","configuration_binding",
            "resource_target","resource_target_sha256");
        if(!REQUEST.equals(supplied.getString("$schema")) || !supplied.getString("preparation_id").matches("[0-9a-f]{32}")
            ||actual==null || !"prepare_peer_start".equals(actual.row.getString("operation"))
            || !actual.id().equals(supplied.getString("original_id"))
            ||held==null ||constructor==null)
            throw new IllegalStateException("actual entered preparation Original/source absent");
        EmbeddedDuplexPeerRoots.identity(supplied,actualActor);
        if(!EmbeddedDuplexCanonicalJson.sameValue(supplied.getJSONObject("arm_binding"),actualArm))
            throw new IllegalStateException("actual immutable preparation ARM differs");
        EmbeddedDuplexPeerRoots.arm(actualArm);constructor.require(supplied.getJSONObject("configuration_binding"));
        JSONObject target=held.preparationTarget(actualActor.getString("config_sha256"));
        if(!EmbeddedDuplexCanonicalJson.sameValue(target,supplied.getJSONObject("resource_target"))
            ||!held.preparationTargetSha256(actualActor.getString("config_sha256")).equals(supplied.getString("resource_target_sha256")))
            throw new IllegalStateException("actual selected preparation resource target differs");
        resources=held;original=actual;exactRequest=EmbeddedDuplexCanonicalJson.encode(supplied);
        request=new JSONObject(exactRequest);requestSha=digest(REQUEST,request);
    }
    private static String digest(String domain,JSONObject value)throws Exception {
        byte[] prefix=(domain+'\0').getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body=EmbeddedDuplexCanonicalJson.encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes=new byte[prefix.length+body.length];System.arraycopy(prefix,0,bytes,0,prefix.length);System.arraycopy(body,0,bytes,prefix.length,body.length);
        return "sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(bytes);
    }
    String id()throws Exception{return request.getString("preparation_id");}
    boolean ownedBy(EmbeddedDuplexOriginals.Record actual){return original==actual;}
    void requireRequest(JSONObject supplied)throws Exception {
        if(!exactRequest.equals(EmbeddedDuplexCanonicalJson.encode(supplied)))throw new IllegalStateException("prepared request changed");
    }
    JSONObject sender()throws Exception {
        synchronized(this){if(senderEntered)throw new IllegalStateException("sender entered: no SDK replay");senderEntered=true;revision++;}
        if(original.desiredStop.get())throw new IllegalStateException("actual desired Stop before SDK construction");
        JSONObject format=resources.prepareSender(original.desiredStop::get);
        synchronized(this){
            if(original.desiredStop.get() ||abortEntered ||!resources.preparedSenderClosed())throw new IllegalStateException("prepared sender superseded");
            sender=new JSONObject().put("$schema","rusty.quest.android.media.prepared_sender_readback.v1")
                .put("preparation_id",id()).put("original_id",original.id()).put("executor_generation",resources.generation())
                .put("request_sha256",requestSha).put("resource_target_sha256",request.getString("resource_target_sha256"))
                .put("provider_handle_id",resources.preparedSenderHandle()).put("provider_state_revision",++revision)
                .put("stage","sender_ready").put("outgoing_format",format).put("delivery_gate_closed",true).put("peer_delivery_count",0);
            return new JSONObject(sender.toString());
        }
    }
    JSONObject decoder(JSONObject supplied)throws Exception {
        EmbeddedDuplexPeerRoots.keys(supplied,"$schema","preparation_id","request_sha256","peer_preparation_id","peer_sender_receipt_sha256","peer_format");
        if(!"rusty.quest.android.media.prepared_decoder_request.v1".equals(supplied.getString("$schema"))
            ||!id().equals(supplied.getString("preparation_id")) ||!requestSha.equals(supplied.getString("request_sha256"))
            ||!supplied.getString("peer_preparation_id").matches("[0-9a-f]{32}")
            ||!supplied.getString("peer_sender_receipt_sha256").matches("sha256:[0-9a-f]{64}"))
            throw new IllegalStateException("actual peer preparation metadata binding differs");
        synchronized(this){
            if(sender==null || decoderEntered ||original.desiredStop.get() ||abortEntered)throw new IllegalStateException("decoder entered/changed: no SDK replay");
            decoderEntered=true;exactDecoder=EmbeddedDuplexCanonicalJson.encode(supplied);revision++;
        }
        JSONObject renderer=resources.prepareDecoder(id(),supplied.getJSONObject("peer_format"),original.desiredStop::get);
        EmbeddedDuplexPreparedRenderer.identity(renderer,request,requestSha);
        synchronized(this){
            if(original.desiredStop.get() ||abortEntered ||!resources.preparedDecoderClosed())throw new IllegalStateException("prepared decoder superseded");
            decoder=new JSONObject().put("$schema","rusty.quest.android.media.prepared_decoder_readback.v1")
                .put("preparation_id",id()).put("executor_generation",resources.generation()).put("request_sha256",requestSha)
                .put("resource_target_sha256",request.getString("resource_target_sha256"))
                .put("provider_handle_id",resources.preparedDecoderHandle()).put("provider_state_revision",++revision).put("stage","decoder_ready")
                .put("peer_sender_receipt_sha256",supplied.getString("peer_sender_receipt_sha256")).put("delivery_gate_closed",true).put("peer_delivery_count",0);
            return new JSONObject(decoder.toString());
        }
    }
    void finalizeTransport()throws Exception {
        synchronized(this){
            if(sender==null ||decoder==null ||transportEntered ||original.desiredStop.get() ||abortEntered)
                throw new IllegalStateException("transport entered/changed: no SDK replay");
            transportEntered=true;revision++;
        }
        // Native enters this effect only after validating the remote decoder
        // receipt and joining the local decoder callback. Both listeners exist;
        // the connection is still real, once-only and delivery remains closed.
        resources.finalizePreparedTransport();
        synchronized(this){
            if(original.desiredStop.get() ||abortEntered ||!resources.preparedTransportConnected()
                ||!resources.preparedSenderClosed() ||!resources.preparedDecoderClosed())
                throw new IllegalStateException("prepared transport superseded");
        }
    }
    synchronized String originalId(){return original.id();}
    synchronized boolean gatedCleanupCandidate(){
        // Trusted Stop may already have closed sockets/woken SDK workers. This
        // is a retained zero-delivery source witness, never a readiness/terminal claim.
        return !resources.preparedDeliveryOpened() && !abortEntered && sender!=null && decoder!=null
            && resources.preparedPeerDeliveryCount()==0L;
    }
    synchronized void requireWholeClosure(JSONObject body)throws Exception {
        if(!resources.productResourcesTerminal()||!original.operationJoined||!EmbeddedDuplexProcessHost.originalExchangesJoined(original))
            throw new IllegalStateException("actual whole-close prepared owners remain live");
        JSONObject prepared=body.getJSONObject("prepared_start");EmbeddedDuplexPeerRoots.keys(prepared,"status","archive_cursor");
        requireNativeStatus(prepared.getJSONObject("status"),false);
        if(!"terminal".equals(body.getJSONObject("close_proof").getString("peer_physical_cleanup")))
            throw new IllegalStateException("actual whole-close Peer cleanup projection not terminal");
    }
    synchronized void requireArchiveBody(JSONObject body)throws Exception {
        if(!original.id().equals(body.getString("original_id"))||!resources.productResourcesTerminal())
            throw new IllegalStateException("actual prepared SDK terminal owner absent");
        JSONObject status=body.getJSONObject("prepared_status");requireNativeStatus(status,false);
        JSONObject lifetime=body.getJSONObject("terminal_lifetime");EmbeddedDuplexPeerRoots.keys(lifetime,"kind","original_id","receipt");
        if(!java.util.Arrays.asList("normal_peer_stop","explicit_prepared_abort","current_cleanup_abort").contains(lifetime.getString("kind")))
            throw new IllegalStateException("closed prepared terminal lifetime kind differs");
        if("normal_peer_stop".equals(lifetime.getString("kind"))) {
            if(!original.operationJoined || original.row.isNull("consumer_receipt_sha256")||!"succeeded".equals(original.row.getString("state")))
                throw new IllegalStateException("actual succeeded acknowledged preparation required");
        } else {
            if(abortReceipt==null||!EmbeddedDuplexCanonicalJson.sameValue(abortReceipt,lifetime.getJSONObject("receipt")))
                throw new IllegalStateException("actual prepared abort readback differs");
        }
        if(!EmbeddedDuplexProcessHost.originalExchangesJoined(original))throw new IllegalStateException("old prepared exchanges remain live");
    }
    synchronized void retireArchivedMetadata(JSONObject exactBody)throws Exception {
        requireArchiveBody(exactBody);
        resources.retireArchivedPreparedMetadata(id());
    }
    synchronized boolean terminalForSuccessorIngress()throws Exception {
        return resources.productResourcesTerminal()&&original.operationJoined
            &&"succeeded".equals(original.row.getString("state"))&&!original.row.isNull("consumer_receipt_sha256")
            &&EmbeddedDuplexProcessHost.originalExchangesJoined(original);
    }
    synchronized JSONObject abort(JSONObject supplied)throws Exception {
        EmbeddedDuplexPeerRoots.keys(supplied,"$schema","preparation_id","cleanup_original_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","request_sha256","resource_target_sha256");
        if(!"rusty.quest.android.media.prepared_abort_request.v1".equals(supplied.getString("$schema"))
            ||!id().equals(supplied.getString("preparation_id"))||!requestSha.equals(supplied.getString("request_sha256"))
            ||!request.getString("resource_target_sha256").equals(supplied.getString("resource_target_sha256")))throw new IllegalStateException("actual prepared abort source differs");
        EmbeddedDuplexPeerRoots.identity(supplied,request);EmbeddedDuplexProcessHost.requirePreparedAbortContext(supplied);
        String exact=EmbeddedDuplexCanonicalJson.encode(supplied);
        if(exactAbort!=null&&!exactAbort.equals(exact))throw new IllegalStateException("prepared abort Original/source changed");
        if(exactAbort==null)exactAbort=exact;
        if(resources.preparedDeliveryOpened())throw new IllegalStateException("active Peer delivery requires actual native media cleanup");
        abortEntered=true;revision++;JSONObject renderer=resources.abortPreparedResources(id());EmbeddedDuplexPreparedRenderer.identity(renderer,request,requestSha);
        if(!resources.productResourcesTerminal())throw new IllegalStateException("actual prepared SDK cleanup remains Pending");
        if(abortReceipt==null)abortReceipt=new JSONObject().put("$schema","rusty.quest.android.media.prepared_abort_readback.v1")
            .put("preparation_id",id()).put("original_id",original.id()).put("cleanup_original_id",supplied.getString("cleanup_original_id"))
            .put("executor_generation",resources.generation()).put("request_sha256",requestSha).put("resource_target_sha256",request.getString("resource_target_sha256"))
            .put("provider_handle_id",resources.preparedSenderHandle()).put("provider_state_revision",++revision).put("stage","terminal")
            .put("owner_receipts",resources.preparedTerminalOwners()).put("delivery_gate_closed",true).put("peer_delivery_count",resources.preparedPeerDeliveryCount());
        return new JSONObject(abortReceipt.toString());
    }
    synchronized boolean ready(){return !abortEntered && !original.desiredStop.get() && sender!=null &&decoder!=null
        &&transportEntered &&resources.preparedTransportConnected()
        &&resources.preparedSenderOwned() &&resources.preparedDecoderOwned();}
    synchronized void requireNativeStatus(JSONObject status,boolean requireReady)throws Exception {
        requireNativeStatus(status,requireReady,false);
    }
    synchronized void requireNativeStatusForLocalStart(JSONObject status)throws Exception {
        requireNativeStatus(status,true,true);
    }
    private void requireNativeStatus(JSONObject status,boolean requireReady,boolean localStart)throws Exception {
        EmbeddedDuplexPeerRoots.keys(status,"$schema","preparation_id","original_id","app_process_generation","app_record_sha256",
            "native_executor_generation","config_sha256","request_sha256","resource_target_sha256","owner","remote_decoder","state","local_start_consumed","media_authority_granted");
        if(!"rusty.quest.embedded_duplex.prepared_peer_start_status.v1".equals(status.getString("$schema"))
            ||!id().equals(status.getString("preparation_id"))||!original.id().equals(status.getString("original_id"))
            ||!requestSha.equals(status.getString("request_sha256"))||!request.getString("resource_target_sha256").equals(status.getString("resource_target_sha256"))
            ||!Boolean.FALSE.equals(status.get("media_authority_granted"))||!(status.get("local_start_consumed") instanceof Boolean)
            ||!java.util.Arrays.asList("preparing","ready","unknown","stop_requested","aborting","terminal").contains(status.getString("state")))
            throw new IllegalStateException("actual Native prepared status differs");
        EmbeddedDuplexPeerRoots.identity(status,request);
        if(!status.isNull("owner")) {
            JSONObject owner=status.getJSONObject("owner");EmbeddedDuplexPeerRoots.keys(owner,"$schema","request","sender_entered","decoder_entered","sender","decoder_request","decoder");
            if(!"rusty.quest.android.media.prepared_start_owner.v1".equals(owner.getString("$schema"))
                ||!EmbeddedDuplexCanonicalJson.sameValue(owner.getJSONObject("request"),request)
                ||!Boolean.valueOf(senderEntered).equals(owner.get("sender_entered"))||!Boolean.valueOf(decoderEntered).equals(owner.get("decoder_entered"))
                ||!EmbeddedDuplexCanonicalJson.sameValue(owner.get("sender"),sender==null?JSONObject.NULL:sender)
                ||!EmbeddedDuplexCanonicalJson.sameValue(owner.get("decoder"),decoder==null?JSONObject.NULL:decoder)
                ||!EmbeddedDuplexCanonicalJson.sameValue(owner.get("decoder_request"),exactDecoder==null?JSONObject.NULL:new JSONObject(exactDecoder)))
                throw new IllegalStateException("Native prepared status detached from actual SDK owner");
        } else if(requireReady)throw new IllegalStateException("actual ready Native owner absent");
        if(requireReady &&(!"ready".equals(status.getString("state")) ||status.getBoolean("local_start_consumed")
            ||status.isNull("remote_decoder")||!ready()||!resources.preparedSenderClosed()
            ||!(localStart?resources.preparedDecoderStartable(id()):resources.preparedDecoderClosed())))
            throw new IllegalStateException(localStart?"actual bilateral preparation not ready for local Start":"actual bilateral gated preparation not ready");
    }
    synchronized JSONObject status(String actualId)throws Exception {
        if(!id().equals(actualId))throw new IllegalStateException("foreign prepared incarnation");
        // Dormant held gates and a physically stopped media product are distinct.
        // Observe the real seven-owner/worker barrier once for both projections.
        final boolean terminal=resources.productResourcesTerminal();
        return new JSONObject().put("$schema","rusty.quest.android.media.prepared_start_readback.v1")
            .put("preparation_id",id()).put("request_sha256",requestSha).put("resource_target_sha256",request.getString("resource_target_sha256"))
            .put("sender",sender==null?JSONObject.NULL:new JSONObject(sender.toString()))
            .put("decoder",decoder==null?JSONObject.NULL:new JSONObject(decoder.toString()))
            .put("state",terminal||abortReceipt!=null?"terminal":abortEntered?"aborting":resources.preparedDeliveryOpened()?"active":decoder!=null?"decoder_ready":decoderEntered?"decoder_preparing":sender!=null?"sender_ready":"entered_unknown")
            .put("delivery_gate_closed",terminal || !abortEntered&&resources.preparedSenderClosed()&&(decoder==null||resources.preparedDecoderClosed()));
    }
    void requestStopOwned(){requestStop(original);}
    void requestStop(EmbeddedDuplexOriginals.Record actual) {
        if(original!=actual)throw new IllegalStateException("foreign preparation Stop Original");
        original.desiredStop.set(true);resources.requestPreparedStop();
    }
    void abortResources() {
        synchronized(this){abortEntered=true;revision++;}
        try{resources.abortPreparedResources(id());}catch(Exception failure){throw new IllegalStateException("actual prepared abort failed",failure);}
    }
}
