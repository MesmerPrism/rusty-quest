package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;

import io.github.mesmerprism.rustyquest.media.MediaOwnerAction;
import io.github.mesmerprism.rustyquest.media.MediaProductBinding;
import io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint;
import io.github.mesmerprism.rustyquest.media.OwnerTransportAttempt;
import io.github.mesmerprism.rustyquest.media.PackagedAndroidMediaOwnerRegistry;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** App-private JNI callbacks. Authority stays in the embedded Rust Runtime Host. */
final class EmbeddedDuplexPlatform {
    private volatile EmbeddedDuplexProcessFence.CallbackGuard processCallbacks;
    void bindProcessFence(EmbeddedDuplexProcessFence fence) {
        if (fence == null || processCallbacks != null) throw new IllegalStateException("callback fence unavailable");
        processCallbacks = fence.callbacks();
    }
    void retireProcessCallbacks() {
        EmbeddedDuplexProcessFence.CallbackGuard guard = processCallbacks;
        if (guard != null) guard.retire();
    }
    java.util.concurrent.CompletableFuture<Void> retireProcessCallbacksAsync(){
        EmbeddedDuplexProcessFence.CallbackGuard guard=processCallbacks;
        if(guard==null)throw new IllegalStateException("app callback owner absent");
        return guard.retireAndDrain();
    }
    private EmbeddedDuplexProcessFence.CallbackLease enterProcessCallback(){
        EmbeddedDuplexProcessFence.CallbackGuard guard=processCallbacks;
        if(guard==null)throw new IllegalStateException("app callback owner absent");
        return guard.enter();
    }
    private void requireProcessCallback() {
        EmbeddedDuplexProcessFence.CallbackGuard guard = processCallbacks;
        if (guard == null) throw new IllegalStateException("app callback fence absent");
        guard.requireLive();
    }
    boolean freshPeerEffectsNeverEntered(){
        PackagedAndroidMediaOwnerRegistry actual=registry;
        EmbeddedDuplexResources held=resources;
        return actual!=null && held!=null && actual.noOwnerEffectEverAttempted() && actual.ownerEffectsQuiescent() && held.incomingNeverPrepared();
    }
    boolean freshPeerControlQuiescent(){
        PackagedAndroidMediaOwnerRegistry actual=registry;EmbeddedDuplexActivationGate activation=activationGate;
        return actual!=null && actual.ownerEffectsQuiescent() && activation!=null && activation.freshSetupQuiescent();
    }
    boolean actualControlTerminal(){OwnerDispatchTcpEndpoint actual=endpoint;return actual==null?false:actual.terminal();}
    public void bindFreshSetupOriginal(String setupRequestId)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();EmbeddedDuplexOriginals.bindFreshSetupOriginal(setupRequestId);
        }
    }
    public String currentOperatorOriginalId()throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();String actual=EmbeddedDuplexOriginals.currentOriginalId();
            if(actual==null)throw new IllegalStateException("actual operator original absent");return actual;
        }
    }
    public String retainUnknownPeerRootFlight(String exactBody)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();return new EmbeddedDuplexUnknownPeerRootCustody(EmbeddedDuplexProcessHost.application())
                .retain(exactBody,EmbeddedDuplexProcessHost.freshClosureIdentity(this));
        }
    }
    public String retainCompletedPeerRootOutcome(String exactPriorNativeResultBody)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();
            // Fixed source-owned metadata writer only. No Native/M/HostLease
            // reentry while the native Broker owner retains its checkpoint plan.
            return new EmbeddedDuplexPeerRootOutcomeArchive(EmbeddedDuplexProcessHost.application())
                .retain(exactPriorNativeResultBody,EmbeddedDuplexProcessHost.freshClosureIdentity(this));
        }
    }
    public String archiveCompletedPeerLifetime(String canonicalBody)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();return new EmbeddedDuplexPeerLifetimeArchive(EmbeddedDuplexProcessHost.application())
                .retain(canonicalBody,EmbeddedDuplexProcessHost.freshClosureIdentity(this));
        }
    }
    public void bindFreshSetupContext(String setupRequestId,String contextSha256)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();EmbeddedDuplexOriginals.bindFreshSetupContext(setupRequestId,contextSha256);
        }
    }
    public String archiveFreshSetupPhase(String canonicalBody)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();JSONObject body=new JSONObject(canonicalBody);JSONObject binding=body.getJSONObject("original_binding");
            if("operator".equals(binding.getString("kind")))
                EmbeddedDuplexOriginals.forApplication(EmbeddedDuplexProcessHost.application()).requirePhaseArchiveOperator(body);
            else incomingPhases.requireArchive(body);
            JSONObject ack=new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application()).retain(canonicalBody,
                EmbeddedDuplexProcessHost.freshClosureIdentity(this));
            if("incoming_fresh_setup".equals(binding.getString("kind")))incomingPhases.archived(body,ack);
            return ack.toString();
        }
    }
    public void bindFreshSetupOperatorPhase(String nativeId,String phaseId)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();EmbeddedDuplexOriginals.bindOperatorPhase(nativeId,phaseId,EmbeddedDuplexProcessHost.freshClosureIdentity(this));
        }
    }
    public void bindFreshSetupPhaseContext(String nativeId,String phaseId,String transactionId,String kind,String digest)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();JSONObject actor=EmbeddedDuplexProcessHost.freshClosureIdentity(this);
            JSONObject binding=EmbeddedDuplexOriginals.capture()==null?incomingPhases.currentBinding(nativeId,phaseId):
                EmbeddedDuplexOriginals.forApplication(EmbeddedDuplexProcessHost.application()).requireOperatorContext(nativeId,phaseId,actor);
            new EmbeddedDuplexFreshPhaseContexts(EmbeddedDuplexProcessHost.application()).retainContext(nativeId,phaseId,transactionId,kind,digest,binding,actor);
        }
    }
    public String persistManifoldFreshHistoryArchive(String exactPlan)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();JSONObject plan=new JSONObject(exactPlan),actor=EmbeddedDuplexProcessHost.freshClosureIdentity(this);
            JSONObject operation=new EmbeddedDuplexFreshPhaseContexts(EmbeddedDuplexProcessHost.application()).requireContext(plan.getJSONObject("operation_binding"));
            for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
                if(!EmbeddedDuplexCanonicalJson.sameValue(operation.get(key),actor.get(key)))throw new IllegalStateException("Manifold archive old phase actor differs");
            JSONObject nativeArchive=new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application()).requireOperationSource(operation,actor);
            JSONObject binding=operation.getJSONObject("original_binding");
            if("operator".equals(binding.getString("kind")))
                EmbeddedDuplexOriginals.forApplication(EmbeddedDuplexProcessHost.application()).requirePhaseArchiveOperator(nativeArchive);
            else incomingPhases.requireArchive(nativeArchive);
            // No runtime/Manifold/HostLease reentry while Native retains its
            // borrowed write plan. The only JNI leaf below is dedicated crypto.
            return new EmbeddedDuplexManifoldFreshArchive(EmbeddedDuplexProcessHost.application()).retain(exactPlan,operation,nativeArchive,identity);
        }
    }
    public String completeFreshSetupContinuationHandoff(String exactBody)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();return EmbeddedDuplexOriginals.forApplication(EmbeddedDuplexProcessHost.application())
                .retainContinuationHandoff(exactBody,EmbeddedDuplexProcessHost.freshClosureIdentity(this)).toString();
        }
    }
    public String completeIncomingFreshSetupHandoff(String exactBody)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();JSONObject body=new JSONObject(exactBody),actor=EmbeddedDuplexProcessHost.freshClosureIdentity(this);
            EmbeddedDuplexFreshHandoffStore store=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application());
            String retained=store.incomingBody(body.getString("native_request_id"),body.getString("phase_request_id"));
            // A cached retry may arrive after old heavy spans were released. It
            // must still be the real registered NEW request group and exact body.
            incomingPhases.currentBinding(body.getString("native_request_id"),body.getString("phase_request_id"));
            if(retained!=null){if(!retained.equals(exactBody))throw new IllegalStateException("incoming adoption retry changed bytes");return store.retainIncoming(exactBody,actor).toString();}
            incomingPhases.incomingHandoff(body);
            JSONObject archive=new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application()).requireRetained(body.getJSONObject("local_archive"),actor);
            if(!body.getString("native_request_id").equals(archive.getString("native_request_id"))
                    || !body.getString("prior_phase_id").equals(archive.getString("phase_request_id")))
                throw new IllegalStateException("incoming adoption retained source phase differs");
            JSONObject ack=store.retainIncoming(exactBody,actor);incomingPhases.adopted(body);return ack.toString();
        }
    }
    public String freshSetupContinuationBindingJson()throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();return EmbeddedDuplexOriginals.freshSetupContinuationBinding(
                EmbeddedDuplexProcessHost.freshClosureIdentity(this)).toString();
        }
    }
    public String peerRootReestablishmentEligibilityJson()throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();return EmbeddedDuplexProcessHost.peerRootEligibility(this).toString();
        }
    }
    public boolean peerRootDesiredStopRequested()throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
            return actual==null || !"reestablish_peer_roots".equals(actual.row.getString("operation")) || actual.desiredStop.get();
        }
    }
    public String freshSetupPhysicalEligibilityJson()throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();return EmbeddedDuplexProcessHost.freshPhysical(this,null,false).toString();
        }
    }
    private void requireIncomingPhysicalObservation()throws Exception{
        requireProcessCallback();EmbeddedDuplexProcessHost.requireIncomingFreshActor(this);
        if(EmbeddedDuplexOriginals.capture()!=null || incomingFreshDesiredStop)
            throw new IllegalStateException("incoming physical observation has operator or desired Stop");
    }
    /** Native calls this only after authenticating the exact new initial frame. */
    public String freshSetupInitialPhysicalEligibilityJson(String nativeId,String phaseId)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireIncomingPhysicalObservation();assertIncomingFreshSetupInitialAllowed(nativeId,phaseId);
            JSONObject binding=new JSONObject().put("kind","incoming_fresh_setup").put("native_request_id",nativeId);
            return EmbeddedDuplexProcessHost.freshPhysical(this,binding,false).toString();
        }
    }
    /** Existing registered current/pending phase and exact endpoint task only. */
    public String freshSetupPhysicalEligibilityForIncomingJson(String nativeId,String phaseId)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireIncomingPhysicalObservation();
            incomingPhases.assertNotArchivedOrFenced(nativeId,phaseId);
            JSONObject binding=incomingPhases.currentBinding(nativeId,phaseId);
            if(new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application())
                    .archivedIncoming(nativeId,phaseId,EmbeddedDuplexProcessHost.freshClosureIdentity(this)))
                throw new IllegalStateException("archived incoming physical observation refused");
            return EmbeddedDuplexProcessHost.freshPhysical(this,binding,false).toString();
        }
    }
    /** Authenticated new preparation preflight; performs no registration/adoption. */
    public String freshSetupPreparationPhysicalEligibilityJson(String nativeId,String priorPhaseId,String phaseId)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireIncomingPhysicalObservation();
            JSONObject binding=incomingPhases.preparationObservationBinding(endpoint,nativeId,priorPhaseId,phaseId);
            if(new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application())
                    .archivedIncoming(nativeId,phaseId,EmbeddedDuplexProcessHost.freshClosureIdentity(this)))
                throw new IllegalStateException("archived incoming preparation observation refused");
            return EmbeddedDuplexProcessHost.freshPhysical(this,binding,false).toString();
        }
    }
    public String freshSetupPhysicalRetirementJson(String originalBindingJson)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();return EmbeddedDuplexProcessHost.freshPhysical(this,new JSONObject(originalBindingJson),true).toString();
        }
    }
    public String persistFreshSetupClosure(String exactJson)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();
            return new EmbeddedDuplexFreshClosure(EmbeddedDuplexProcessHost.application()).retain(exactJson,
                EmbeddedDuplexProcessHost.freshClosureIdentity(this));
        }
    }
    public String persistNativeConstructionRetirement(String exactJson)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();
            JSONObject actual=EmbeddedDuplexProcessHost.constructionPhysical(this);
            return new EmbeddedDuplexConstructionClosure(EmbeddedDuplexProcessHost.application()).retain(exactJson,actual);
        }
    }
    public String persistFreshOwnerRetirement(String exactJson)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();EmbeddedDuplexFreshOwnerClosure store=new EmbeddedDuplexFreshOwnerClosure(EmbeddedDuplexProcessHost.application());
            JSONObject body=new JSONObject(exactJson);boolean prepared="rusty.quest.embedded_duplex.fresh_owner_retirement_closure.v2".equals(body.getString("$schema"));
            if(prepared)requirePreparedWholeClosure(body,store.retainedExact(exactJson));
            String ack=store.retain(exactJson,EmbeddedDuplexProcessHost.freshClosureIdentity(this));
            if(prepared)synchronized(this){preparedStart=null;incomingPreparedRemote=null;incomingPreparedLocal=null;notifyAll();}
            return ack;
        }
    }
    public String loadFreshSetupClosure()throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();String pending=new EmbeddedDuplexFreshClosure(EmbeddedDuplexProcessHost.application()).load();
            String terminal=new EmbeddedDuplexFreshOwnerClosure(EmbeddedDuplexProcessHost.application()).load();
            // Both tagged stores are read/validated, even if one already fences bootstrap.
            String construction=new EmbeddedDuplexConstructionClosure(EmbeddedDuplexProcessHost.application()).load();
            String unknownRoot=new EmbeddedDuplexUnknownPeerRootCustody(EmbeddedDuplexProcessHost.application()).load();
            return !pending.equals("{}")?pending:!terminal.equals("{}")?terminal:!construction.equals("{}")?construction:unknownRoot;
        }
    }
    private static final int MAX_REPLAY_BYTES = 16 * 1024 * 1024;
    private final EmbeddedDuplexIdentity.Identity identity;
    private final EmbeddedDuplexDisplay display;
    private final String localPeerId;
    private final String remotePeerId;
    private final String routeConfigurationSha256;
    private final InetAddress remoteControlAddress;
    private final int remoteControlPort;
    private final File replayDirectory;
    private final AtomicFile replayFile;
    private final AtomicFile activationReplayFile;
    private final AtomicFile retainedCleanupReplayFile;
    private final AtomicFile cleanupPreparationsFile;
    private final boolean localFixture;
    private volatile PackagedAndroidMediaOwnerRegistry registry;
    private volatile OwnerDispatchTcpEndpoint endpoint;
    private volatile EmbeddedDuplexResources resources;
    private volatile EmbeddedDuplexPreparedStart preparedStart;
    private volatile Throwable preparedReservationFailure;
    private volatile boolean incomingPreparedDesiredStop;
    private long incomingPreparedStopSequence;
    private boolean incomingPreparedWholeStop;
    private final java.util.Set<String> archivedIncomingPreparedRemotes=new java.util.HashSet<>();
    private String incomingPreparedRemote,incomingPreparedLocal;
    private final java.util.Set<OwnerDispatchTcpEndpoint.RequestSpan> incomingPreparedSpans=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private JSONObject lastPreparedArchiveAck;
    private String lastPreparedArchiveId;
    void requestIncomingPreparedStop(boolean whole){
        synchronized(this){
            incomingPreparedDesiredStop=true;
            incomingPreparedWholeStop|=whole;
            if(incomingPreparedStopSequence!=Long.MAX_VALUE)incomingPreparedStopSequence++;
        }
        EmbeddedDuplexPreparedStart held=preparedStart;
        if(held!=null)try{held.requestStopOwned();EmbeddedDuplexNative.requestPreparedPeerStartStop(held.originalId());}catch(Exception retained){/* No outcome inferred. */}
        synchronized(this){notifyAll();}
    }
    public boolean incomingPreparedStartStopRequested()throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();OwnerDispatchTcpEndpoint.RequestSpan span=OwnerDispatchTcpEndpoint.currentRequestSpan();
            return incomingPreparedDesiredStop||span==null||endpoint==null||!endpoint.ownsRequestSpan(span)||span.stopRequested();
        }
    }
    public long incomingPreparedStartStopSequence()throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();OwnerDispatchTcpEndpoint.RequestSpan span=OwnerDispatchTcpEndpoint.currentRequestSpan();
            if(span==null||endpoint==null||!endpoint.ownsRequestSpan(span))throw new IllegalStateException("actual prepared request span absent");
            synchronized(this){return incomingPreparedStopSequence;}
        }
    }
    public boolean incomingPreparedStartStopRequestedFor(String remote,long sequence,boolean retiredNativeScope)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();OwnerDispatchTcpEndpoint.RequestSpan span=OwnerDispatchTcpEndpoint.currentRequestSpan();
            if(remote==null||!remote.matches("[0-9a-f]{32}")||sequence<0||span==null||endpoint==null||!endpoint.ownsRequestSpan(span)||span.stopRequested())return true;
            synchronized(this){
                if(incomingPreparedWholeStop||incomingPreparedStopSequence==Long.MAX_VALUE||sequence!=incomingPreparedStopSequence||archivedIncomingPreparedRemotes.contains(remote))return true;
                if(!incomingPreparedDesiredStop)return false;
                // Only a different authenticated successor may wait behind the
                // old completed scope. It acquires no old Java borrower/span.
                return !(retiredNativeScope&&preparedStart!=null&&incomingPreparedRemote!=null
                    &&!remote.equals(incomingPreparedRemote)&&preparedStart.terminalForSuccessorIngress());
            }
        }
    }
    public void bindIncomingPreparedPeerStart(String remote,String local)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();if(!remote.matches("[0-9a-f]{32}")||!local.matches("[0-9a-f]{32}"))throw new IllegalStateException("actual prepared incoming IDs differ");
            OwnerDispatchTcpEndpoint.RequestSpan span=OwnerDispatchTcpEndpoint.currentRequestSpan();
            if(span==null||endpoint==null||!endpoint.ownsRequestSpan(span))throw new IllegalStateException("actual prepared request span absent");
            synchronized(this) {
                boolean warned=false;
                while(preparedStart==null) {
                    if(incomingPreparedDesiredStop||span.stopRequested()||preparedReservationFailure!=null)throw new IllegalStateException("actual local prepared reservation ended");
                    wait(1000L);if(!warned){warned=true;try{android.util.Log.w("RQSpatialCameraPanel","same incoming prepared request awaits actual local holder");}catch(RuntimeException ignoredWarning){}}
                }
                if(!preparedStart.id().equals(local)||incomingPreparedDesiredStop||span.stopRequested())throw new IllegalStateException("actual incoming prepared owner superseded");
                if(incomingPreparedLocal!=null&&(!local.equals(incomingPreparedLocal)||!remote.equals(incomingPreparedRemote)))throw new IllegalStateException("prepared incoming group cannot rebind");
                incomingPreparedLocal=local;incomingPreparedRemote=remote;
                incomingPreparedSpans.removeIf(OwnerDispatchTcpEndpoint.RequestSpan::terminal);incomingPreparedSpans.add(span);
            }
        }
    }
    boolean preparedIncomingTasksJoined(){incomingPreparedSpans.removeIf(OwnerDispatchTcpEndpoint.RequestSpan::terminal);return incomingPreparedSpans.isEmpty();}
    public String peerStartPreparationEligibilityJson()throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();return EmbeddedDuplexProcessHost.preparedStartEligibility(this).toString();
        }
    }
    /** Same one actual eligibility call, with closed failure-location metadata only. */
    public String peerStartPreparationEligibilityObservationJson()throws Exception {
        EmbeddedDuplexPreparationDiagnostic.EligibilityTrace trace=new EmbeddedDuplexPreparationDiagnostic.EligibilityTrace();
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();JSONObject value=EmbeddedDuplexProcessHost.preparedStartEligibility(this,trace);
            trace.guard=EmbeddedDuplexPreparationDiagnostic.Guard.CALLBACK_CLOSE;
            return trace.result(value);
        }catch(Exception failure){return trace.result(null);}
    }
    public String abortPeerStartResources(String exactRequest)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();EmbeddedDuplexPreparedStart held=preparedStart;
            if(held==null)throw new IllegalStateException("actual prepared abort owner absent");return held.abort(new JSONObject(exactRequest)).toString();
        }
    }
    public String archiveCompletedPreparedPeerStart(String exactBody)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();EmbeddedDuplexPreparedStart held=preparedStart;
            if(held==null)return new EmbeddedDuplexPreparedArchive(EmbeddedDuplexProcessHost.application())
                .retainedExact(exactBody,EmbeddedDuplexProcessHost.freshClosureIdentity(this),lastPreparedArchiveAck).toString();
            if(!preparedIncomingTasksJoined())throw new IllegalStateException("actual old prepared handlers remain live");
            final long stopSequence;
            synchronized(this){stopSequence=incomingPreparedStopSequence;}
            EmbeddedDuplexOriginals.Record next=EmbeddedDuplexOriginals.capture();
            boolean nextPreparation=next!=null&&"prepare_peer_start".equals(next.row.getString("operation"));
            if(nextPreparation)EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(next);
            JSONObject ack=new EmbeddedDuplexPreparedArchive(EmbeddedDuplexProcessHost.application()).retain(exactBody,EmbeddedDuplexProcessHost.freshClosureIdentity(this),held);
            // Retire only metadata after durable custody. Stop alone never
            // clears an ID, projection reservation or a physical owner.
            held.retireArchivedMetadata(new JSONObject(exactBody));
            synchronized(this) {
                if(preparedStart!=held)throw new IllegalStateException("prepared archive owner changed");
                if(!preparedIncomingTasksJoined())throw new IllegalStateException("actual old prepared handlers remain live");
                lastPreparedArchiveAck=ack;lastPreparedArchiveId=held.id();
                if(incomingPreparedRemote!=null)archivedIncomingPreparedRemotes.add(incomingPreparedRemote);
                if(nextPreparation&&!next.desiredStop.get()&&!incomingPreparedWholeStop&&incomingPreparedStopSequence==stopSequence&&stopSequence!=Long.MAX_VALUE)
                    incomingPreparedDesiredStop=false;
                preparedStart=null;incomingPreparedRemote=null;incomingPreparedLocal=null;notifyAll();
            }
            return ack.toString();
        }
    }
    void requirePreparedMetadataResult(JSONObject result)throws Exception {
        EmbeddedDuplexPeerRoots.keys(result,"$schema","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","preparation_id","archive_cursor","metadata_retired","physical_cleanup_claimed");
        if(!"rusty.quest.embedded_duplex.prepared_peer_start_metadata_retirement.v1".equals(result.getString("$schema"))
            ||!Boolean.TRUE.equals(result.get("metadata_retired"))||!Boolean.FALSE.equals(result.get("physical_cleanup_claimed"))
            ||!java.util.Objects.equals(lastPreparedArchiveId,result.getString("preparation_id")))throw new IllegalStateException("prepared metadata result differs");
        EmbeddedDuplexPeerRoots.identity(result,EmbeddedDuplexProcessHost.freshClosureIdentity(this));
        JSONObject cursor=result.getJSONObject("archive_cursor");EmbeddedDuplexPeerRoots.keys(cursor,"$schema","ordinal","last","history_sha256");
        if(!"rusty.quest.embedded_duplex.prepared_start_archive_cursor.v1".equals(cursor.getString("$schema"))
            ||cursor.getLong("ordinal")<=0||!cursor.getString("history_sha256").matches("sha256:[0-9a-f]{64}")
            ||lastPreparedArchiveAck==null||!EmbeddedDuplexCanonicalJson.sameValue(cursor.getJSONObject("last"),lastPreparedArchiveAck))
            throw new IllegalStateException("actual prepared archive ACK differs");
    }
    public String peerStartResourceTargetJson()throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();return EmbeddedDuplexProcessHost.preparedStartTarget(this).toString();
        }
    }
    public String preparePeerStartResources(String exactRequest)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();JSONObject request=new JSONObject(exactRequest);
            EmbeddedDuplexPreparedStart actual;
            try {actual=EmbeddedDuplexProcessHost.reservePreparedStart(this,request);}
            catch(Exception failure){preparedReservationFailure=failure;synchronized(this){notifyAll();}throw failure;}
            synchronized(this){if(preparedStart!=null)throw new IllegalStateException("actual prepared owner already retained");preparedStart=actual;preparedReservationFailure=null;notifyAll();}
            return actual.sender().toString();
        }
    }
    public String preparePeerStartDecoder(String executionJson)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();JSONObject execution=new JSONObject(executionJson);
            EmbeddedDuplexPeerRoots.keys(execution,"$schema","request","decoder");
            if(!"rusty.quest.android.media.prepared_decoder_execution.v1".equals(execution.getString("$schema")))
                throw new IllegalStateException("closed prepared decoder execution differs");
            EmbeddedDuplexPreparedStart held=preparedStart;if(held==null)throw new IllegalStateException("actual prepared sender owner absent");
            held.requireRequest(execution.getJSONObject("request"));return held.decoder(execution.getJSONObject("decoder")).toString();
        }
    }
    public String finalizePeerStartTransport(String exactRequest)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();EmbeddedDuplexPreparedStart held=preparedStart;
            if(held==null)throw new IllegalStateException("actual prepared sender owner absent");
            held.requireRequest(new JSONObject(exactRequest));held.finalizeTransport();
            return held.status(held.id()).toString();
        }
    }
    public String abortPreparedPeerStartResources(String exactRequest)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();
            EmbeddedDuplexPreparedStart held=preparedStart;
            if(held==null)throw new IllegalStateException("actual prepared abort owner absent");
            // The same retained owner validates the closed request, current
            // cleanup Original/actor/thread, closed delivery gate and SDK joins.
            // Its actual thirteen-field terminal readback is returned unchanged.
            return held.abort(new JSONObject(exactRequest)).toString();
        }
    }
    public String preparedPeerStartStatusJson(String preparationId)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()) {
            requireProcessCallback();EmbeddedDuplexPreparedStart held=preparedStart;
            if(held==null)throw new IllegalStateException("actual prepared owner absent");return held.status(preparationId).toString();
        }
    }
    boolean hasPreparedPeerStart(){return preparedStart!=null;}
    boolean gatedPreparedCleanupCandidate(){EmbeddedDuplexPreparedStart held=preparedStart;return held!=null&&held.gatedCleanupCandidate();}
    void requirePreparedWholeClosure(JSONObject body,boolean alreadyRetained)throws Exception {
        EmbeddedDuplexProcessHost.requireCurrentWholeNativeClosure(this,body);
        EmbeddedDuplexPreparedStart held=preparedStart;
        if(held==null){if(!alreadyRetained)throw new IllegalStateException("actual whole-close prepared holder absent");}
        else held.requireWholeClosure(body);
        if(!preparedIncomingTasksJoined())throw new IllegalStateException("actual whole-close prepared handlers remain live");
        EmbeddedDuplexOriginals.forApplication(EmbeddedDuplexProcessHost.application())
            .requirePreparedWholeClosure(body.getJSONObject("prepared_start").getJSONObject("status"),EmbeddedDuplexProcessHost.freshClosureIdentity(this));
    }
    boolean preparedPeerStartReady(){EmbeddedDuplexPreparedStart held=preparedStart;return held!=null&&held.ready();}
    JSONObject preparedPeerPhysicalStatus()throws Exception {
        EmbeddedDuplexPreparedStart held=preparedStart;
        if(held==null||!held.ready())return null;
        JSONObject actual=held.status(held.id());
        if(!java.util.Arrays.asList("decoder_ready","ready").contains(actual.getString("state"))
            ||!actual.getBoolean("delivery_gate_closed"))return null;
        return actual; // Exact retained source-owned registry8. No terminal/Native authority assertion.
    }
    void requirePreparedNativeStatus(JSONObject receipt,boolean ready)throws Exception {
        EmbeddedDuplexPreparedStart held=preparedStart;if(held==null)throw new IllegalStateException("actual prepared registry owner absent");
        held.requireNativeStatus(receipt,ready);
    }
    private void stopPreparedOriginal(EmbeddedDuplexOriginals.Record actual) {
        EmbeddedDuplexPreparedStart held=preparedStart;if(held!=null&&held.ownedBy(actual))held.requestStop(actual);
    }
    private volatile EmbeddedDuplexActivationGate activationGate;
    private final java.util.concurrent.ConcurrentHashMap<OwnerDispatchTcpEndpoint.ExchangeControl,OwnedExchange> ownedExchanges=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class OwnedExchange {
        final EmbeddedDuplexOriginals.Record original;final String frameSha,nativeRequestId;final JSONObject actor;
        final OwnerDispatchTcpEndpoint.RequestSpan incomingSpan;
        final java.net.InetAddress endpoint;final int port;
        OwnedExchange(EmbeddedDuplexOriginals.Record original,OwnerDispatchTcpEndpoint.RequestSpan incomingSpan,String frameSha,String nativeRequestId,JSONObject actor,java.net.InetAddress endpoint,int port){
            this.incomingSpan=incomingSpan;this.original=original;this.frameSha=frameSha;this.nativeRequestId=nativeRequestId;this.actor=actor;this.endpoint=endpoint;this.port=port;
        }
    }
    String signalOriginalStop(EmbeddedDuplexOriginals.Record actual)throws Exception{
        if(actual==null)throw new IllegalStateException("actual Stop owner absent");actual.desiredStop.set(true);
        stopPreparedOriginal(actual);
        String nativeId=EmbeddedDuplexOriginals.nativeSetupRequestId(actual);
        try{return "prepare_peer_start".equals(actual.row.getString("operation"))
            ?EmbeddedDuplexNative.requestPreparedPeerStartStop(actual.id()): "reestablish_peer_roots".equals(actual.row.getString("operation"))
            ?EmbeddedDuplexNative.requestPeerRootStop(actual.id()):nativeId==null?null:EmbeddedDuplexNative.requestFreshSetupStop(nativeId);}
        finally{
            for(java.util.Map.Entry<OwnerDispatchTcpEndpoint.ExchangeControl,OwnedExchange> entry:ownedExchanges.entrySet())
                if(entry.getValue().original==actual)entry.getKey().requestStop();
        }
    }
    boolean originalExchangesJoined(EmbeddedDuplexOriginals.Record actual){
        for(OwnedExchange held:ownedExchanges.values())if(held.original==actual)return false;return true;
    }
    void requestDesiredStop(EmbeddedDuplexOriginals.Record actual){
        if(actual==null)return;actual.desiredStop.set(true);
        stopPreparedOriginal(actual);
        try {
            String operation=actual.row.getString("operation");
            if("reestablish_peer_roots".equals(operation))EmbeddedDuplexNative.requestPeerRootStop(actual.id());
            if("prepare_peer_start".equals(operation))EmbeddedDuplexNative.requestPreparedPeerStartStop(actual.id());
        }catch(Exception retained){/* Actual Original remains uncertain; no success or cleanup inferred. */}
        String nativeId=null;
        try{nativeId=EmbeddedDuplexOriginals.nativeSetupRequestId(actual);}
        catch(Exception retained){/* Missing proof never becomes another target. */}
        if(nativeId!=null)try{EmbeddedDuplexNative.requestFreshSetupStop(nativeId);}catch(Throwable retained){/* Original retains actual uncertainty. */}
        for(java.util.Map.Entry<OwnerDispatchTcpEndpoint.ExchangeControl,OwnedExchange> entry:ownedExchanges.entrySet())
            if(entry.getValue().original==actual)entry.getKey().requestStop();
    }
    private volatile String incomingFreshRequestId;
    private volatile boolean incomingFreshDesiredStop;
    private final EmbeddedDuplexIncomingFreshPhases incomingPhases=new EmbeddedDuplexIncomingFreshPhases();
    public void assertIncomingFreshSetupInitialAllowed(String nativeId,String phaseId)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();EmbeddedDuplexProcessHost.requireIncomingFreshActor(this);
            EmbeddedDuplexIncomingFreshPhases.id(nativeId);EmbeddedDuplexIncomingFreshPhases.id(phaseId);
            OwnerDispatchTcpEndpoint.RequestSpan actual=OwnerDispatchTcpEndpoint.currentRequestSpan();
            if(endpoint==null || !endpoint.ownsRequestSpan(actual))throw new IllegalStateException("actual verified initial request span absent");
            if(!nativeId.equals(phaseId) || incomingFreshDesiredStop)throw new IllegalStateException("actual initial phase/Stop differs");
            if(new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application())
                    .archivedIncoming(nativeId,phaseId,EmbeddedDuplexProcessHost.freshClosureIdentity(this)))
                throw new IllegalStateException("actual archived incoming initial cannot mutate history");
            incomingPhases.assertNotArchivedOrFenced(nativeId,phaseId);
            // Readonly preflight only: no registration/adoption/authority grant.
        }
    }
    public void bindIncomingFreshSetupPhase(String nativeId,String priorPhaseId,String phaseId,String purpose)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();EmbeddedDuplexProcessHost.requireIncomingFreshActor(this);
            if(incomingFreshDesiredStop)throw new IllegalStateException("actual incoming fresh desired Stop");
            if("initial".equals(purpose) && new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application())
                    .archivedIncoming(nativeId,phaseId,EmbeddedDuplexProcessHost.freshClosureIdentity(this)))
                throw new IllegalStateException("actual archived incoming initial phase cannot replay");
            if("initial_preparation".equals(purpose) && new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application())
                    .archivedIncoming(nativeId,nativeId,EmbeddedDuplexProcessHost.freshClosureIdentity(this)))
                throw new IllegalStateException("archived first preparation root cannot replay");
            incomingPhases.bind(endpoint,nativeId,priorPhaseId,phaseId,purpose);incomingFreshRequestId=nativeId;
        }
    }
    void requestIncomingFreshStop(){
        String actual;
        synchronized(this){incomingFreshDesiredStop=true;actual=incomingFreshRequestId;}
        try{if(actual!=null)EmbeddedDuplexNative.requestFreshSetupStop(actual);}catch(Throwable retained){}
        finally{for(java.util.Map.Entry<OwnerDispatchTcpEndpoint.ExchangeControl,OwnedExchange> entry:ownedExchanges.entrySet()){
            OwnedExchange held=entry.getValue();
            if(held.original==null && actual!=null && actual.equals(held.nativeRequestId) && held.incomingSpan!=null)entry.getKey().requestStop();
        }}
    }
    synchronized boolean incomingFreshBindingCurrent(String actual){return actual!=null && actual.equals(incomingFreshRequestId);}
    synchronized boolean completedSetupIncomingJoined(String nativeId){
        return nativeId.equals(incomingPhases.currentNativeId()) && incomingFreshTaskJoined(nativeId);
    }
    synchronized boolean incomingFreshTaskJoined(String actual){
        return incomingFreshBindingCurrent(actual) && incomingFreshTasksJoined();
    }
    private boolean incomingFreshTasksJoined(){return incomingPhases.allTerminal(incomingFreshRequestId);}
    synchronized boolean incomingSetupTasksJoinedFor(String nativeRequestId)throws Exception{
        if(endpoint==null || nativeRequestId==null)throw new IllegalStateException("continuation endpoint source absent");
        for(OwnedExchange held:ownedExchanges.values())if(held.incomingSpan!=null && nativeRequestId.equals(held.nativeRequestId))return false;
        return incomingPhases.admissionFor(nativeRequestId);
    }
    boolean incomingAdmissionQuiescent(){OwnerDispatchTcpEndpoint actual=endpoint;return actual!=null && actual.requestsQuiescent();}
    boolean actualOutboundExchangesJoined(){return ownedExchanges.isEmpty();}

    EmbeddedDuplexPlatform(Context context, EmbeddedDuplexDisplay display,
            EmbeddedDuplexIdentity.Identity identity, String localPeerId, String remotePeerId,
            String routeConfigurationSha256, String remoteControlIp, int remoteControlPort) throws Exception {
        this(context, display, identity, localPeerId, remotePeerId, routeConfigurationSha256,
                remoteControlIp, remoteControlPort, false);
    }

    EmbeddedDuplexPlatform(Context context, EmbeddedDuplexDisplay display,
            EmbeddedDuplexIdentity.Identity identity, String localPeerId, String remotePeerId,
            String routeConfigurationSha256, String remoteControlIp, int remoteControlPort,
            boolean localFixture) throws Exception {
        if (context == null || display == null || identity == null || localPeerId.equals(remotePeerId)
                || !routeConfigurationSha256.matches("sha256:[0-9a-f]{64}")
                || remoteControlPort <= 0 || remoteControlPort > 65535) {
            throw new IllegalArgumentException("embedded platform binding");
        }
        this.identity = identity;
        this.display = display;
        this.localPeerId = localPeerId;
        this.remotePeerId = remotePeerId;
        this.routeConfigurationSha256 = routeConfigurationSha256;
        this.remoteControlAddress = numericIpv4(remoteControlIp);
        this.remoteControlPort = remoteControlPort;
        this.localFixture = localFixture;
        replayDirectory = new File(context.getNoBackupFilesDir(), "embedded-duplex-replay");
        rejectLink(replayDirectory);
        if (!replayDirectory.isDirectory() && !replayDirectory.mkdir()) {
            throw new IllegalStateException("replay directory unavailable");
        }
        Os.chmod(replayDirectory.getAbsolutePath(), 0700);
        File base = new File(replayDirectory, "owner-dispatch.v1.json");
        rejectLink(base);
        rejectLink(new File(base.getPath() + ".new"));
        rejectLink(new File(base.getPath() + ".bak"));
        replayFile = new AtomicFile(base);
        File activationBase = new File(replayDirectory, "product-activation.v1.json");
        rejectLink(activationBase);
        rejectLink(new File(activationBase.getPath() + ".new"));
        rejectLink(new File(activationBase.getPath() + ".bak"));
        activationReplayFile = new AtomicFile(activationBase);
        File cleanupBase = new File(replayDirectory, "retained-cleanup.v2.json");
        rejectLink(cleanupBase);
        rejectLink(new File(cleanupBase.getPath() + ".new"));
        rejectLink(new File(cleanupBase.getPath() + ".bak"));
        retainedCleanupReplayFile = new AtomicFile(cleanupBase);
        File preparedBase = new File(replayDirectory, "retained-cleanup-preparations.v1.json");
        rejectLink(preparedBase);
        rejectLink(new File(preparedBase.getPath() + ".new"));
        rejectLink(new File(preparedBase.getPath() + ".bak"));
        cleanupPreparationsFile = new AtomicFile(preparedBase);
    }

    /** Called once with resource tuples derived by Rust from packaged bindings. */
    void installRegistry(long generation, MediaProductBinding binding) {
        if (registry != null || endpoint != null) throw new IllegalStateException("registry already installed");
        registry = new PackagedAndroidMediaOwnerRegistry(generation, binding);
    }

    /** Installs the exact resource closure returned by the embedded Runtime Host. */
    void installResources(EmbeddedDuplexResources installed) {
        if (installed == null || resources != null || activationGate != null) {
            throw new IllegalStateException("resources already installed");
        }
        installRegistry(installed.generation(), installed.binding());
        resources = installed;
        activationGate = new EmbeddedDuplexActivationGate(installed);
    }

    /** No Start can be issued until every peer's authenticated control endpoint is ready. */
    void startControl(String localControlIp, int localControlPort) throws Exception {
        if (registry == null || endpoint != null) throw new IllegalStateException("control initialization order");
        endpoint = new OwnerDispatchTcpEndpoint(numericIpv4(localControlIp), localControlPort,
                frame -> {
                    requireProcessCallback();
                    if (localFixture) {
                        throw new IllegalStateException("local fixture has no peer owner authority");
                    }
                    return EmbeddedDuplexNative.handleOwnerFrame(frame);
                });
        if (!endpoint.ready()) throw new IllegalStateException("control endpoint not ready");
    }

    boolean controlReady() { return endpoint != null && endpoint.ready(); }

    // Public visibility is solely for GetMethodID. This package-private object is
    // retained by native GlobalRef and is never exported through an Android component.
    enum OwnerStage { NONE, CALLBACK_FENCE, PROJECTION_BINDING, REGISTRY_BINDING,
        INCOMING_FENCE, LOCAL_QUIESCENCE, PROVIDER_EXECUTION, RECEIPT_VERIFICATION, INCOMING_ARM_VERIFICATION }
    private volatile OwnerStage failedOwnerStage = OwnerStage.NONE;
    private volatile String failedSinkStage = "NONE";
    private volatile String failedOwnerAction = "NONE";
    private volatile ProviderReason failedProviderReason = ProviderReason.NONE;
    enum ProviderReason { NONE, TICKET_PARSE, STALE_GENERATION, UNDECLARED_BINDING,
        REGISTRY_CLOSED, PROVIDER_BUSY, CAPACITY, PREPARATION_ALREADY_ATTEMPTED,
        FOREIGN_READBACK, RECEIPT_COLLISION, DISPLAY_LOCAL_SHUTDOWN, DISPLAY_DISPATCH_FENCED, DISPLAY_TRANSITION_TIMEOUT, DISPLAY_ADMISSION_REJECTED, DISPLAY_NATIVE_ACTIVE_EPOCH, DISPLAY_NATIVE_ACTIVE_STATE, DISPLAY_NATIVE_BOUNDS, DISPLAY_NATIVE_CAPTURE, DISPLAY_NATIVE_CARRIER, DISPLAY_NATIVE_CLOCK, DISPLAY_NATIVE_FRAME_ABSENT, DISPLAY_NATIVE_FRAME_EPOCH, DISPLAY_NATIVE_FRAME_FUTURE, DISPLAY_NATIVE_FRAME_STALE, DISPLAY_NATIVE_INPUT, DISPLAY_NATIVE_LOCAL, DISPLAY_NATIVE_PROCESS_EPOCH, DISPLAY_NATIVE_SOURCE_STATE, DISPLAY_NATIVE_SUPERSEDED, DISPLAY_OWN_CAPTURE_STATE, DISPLAY_OWN_CAPTURE_FRESH, DISPLAY_OWN_CARRIER_SUPERSEDED, DISPLAY_NATIVE_SHAPE, DISPLAY_ROUTING_SUPERSEDED, INCOMING_ARM_ORDER, INCOMING_ARM_PROJECTION, INCOMING_ARM_EVIDENCE, INCOMING_ARM_UNAVAILABLE, OTHER }
    // Fixed owner-local categories only. Never return or log exception messages or ticket fields.
    private volatile String failedOwnerKind = "NONE";
    private volatile String failedCause = "NONE";
    static String failureCategory(Throwable failure) {
        boolean state = false;
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof android.media.MediaCodec.CodecException) return "CODEC";
            if (failure instanceof java.util.concurrent.TimeoutException) return "TIMEOUT";
            if (failure instanceof java.io.IOException) return "IO";
            if (failure instanceof SecurityException) return "SECURITY";
            if (failure instanceof IllegalArgumentException) return "ARGUMENT";
            state |= failure instanceof IllegalStateException;
        }
        return state ? "STATE" : "OTHER";
    }
    static ProviderReason providerReason(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            String message = failure.getMessage();
            if (failure instanceof java.util.concurrent.TimeoutException) return ProviderReason.DISPLAY_TRANSITION_TIMEOUT;
            if (message == null) continue;
            if ((message.equals("embedded receiver requires actual local acquisition shutdown") || message.equals("old native local acquisition remains Pending") || message.equals("local capture still owns camera resources") || message.equals("local camera shutdown incomplete"))) return ProviderReason.DISPLAY_LOCAL_SHUTDOWN;
            if (message.equals("Activity lifecycle is already fenced")) return ProviderReason.DISPLAY_DISPATCH_FENCED;
            if (message.equals("concurrent Own native admission ACTIVE_EPOCH")) return ProviderReason.DISPLAY_NATIVE_ACTIVE_EPOCH;
            if (message.equals("concurrent Own native admission ACTIVE_STATE")) return ProviderReason.DISPLAY_NATIVE_ACTIVE_STATE;
            if (message.equals("concurrent Own native admission BOUNDS")) return ProviderReason.DISPLAY_NATIVE_BOUNDS;
            if (message.equals("concurrent Own native admission CAPTURE")) return ProviderReason.DISPLAY_NATIVE_CAPTURE;
            if (message.equals("concurrent Own native admission CARRIER")) return ProviderReason.DISPLAY_NATIVE_CARRIER;
            if (message.equals("concurrent Own native admission CLOCK")) return ProviderReason.DISPLAY_NATIVE_CLOCK;
            if (message.equals("concurrent Own native admission FRAME_ABSENT")) return ProviderReason.DISPLAY_NATIVE_FRAME_ABSENT;
            if (message.equals("concurrent Own native admission FRAME_EPOCH")) return ProviderReason.DISPLAY_NATIVE_FRAME_EPOCH;
            if (message.equals("concurrent Own native admission FRAME_FUTURE")) return ProviderReason.DISPLAY_NATIVE_FRAME_FUTURE;
            if (message.equals("concurrent Own native admission FRAME_STALE")) return ProviderReason.DISPLAY_NATIVE_FRAME_STALE;
            if (message.equals("concurrent Own native admission INPUT")) return ProviderReason.DISPLAY_NATIVE_INPUT;
            if (message.equals("concurrent Own native admission LOCAL")) return ProviderReason.DISPLAY_NATIVE_LOCAL;
            if (message.equals("concurrent Own native admission PROCESS_EPOCH")) return ProviderReason.DISPLAY_NATIVE_PROCESS_EPOCH;
            if (message.equals("concurrent Own native admission SOURCE_STATE")) return ProviderReason.DISPLAY_NATIVE_SOURCE_STATE;
            if (message.equals("concurrent Own native admission SUPERSEDED")) return ProviderReason.DISPLAY_NATIVE_SUPERSEDED;
            if (message.equals("concurrent Own capture unavailable") || message.equals("concurrent Own capture not Live") || message.equals("concurrent Own capture superseded")) return ProviderReason.DISPLAY_OWN_CAPTURE_STATE;
            if (message.equals("concurrent Own capture not fresh")) return ProviderReason.DISPLAY_OWN_CAPTURE_FRESH;
            if (message.equals("concurrent Own carrier superseded")) return ProviderReason.DISPLAY_OWN_CARRIER_SUPERSEDED;
            if (message.equals("concurrent Own native admission unavailable")) return ProviderReason.DISPLAY_NATIVE_SHAPE;
            if (message.equals("concurrent Peer reservation superseded")) return ProviderReason.DISPLAY_ROUTING_SUPERSEDED;
            if (message.startsWith("concurrent Own ") || message.equals("concurrent Peer reservation superseded")) return ProviderReason.DISPLAY_ADMISSION_REJECTED;
            if (message.equals("media execution generation is stale") || message.equals("stale registry generation")) return ProviderReason.STALE_GENERATION;
            if (message.equals("undeclared media provider binding")) return ProviderReason.UNDECLARED_BINDING;
            if (message.equals("no-media registry closed")) return ProviderReason.REGISTRY_CLOSED;
            if (message.equals("ProviderBusy")) return ProviderReason.PROVIDER_BUSY;
            if (message.equals("media execution registry full")) return ProviderReason.CAPACITY;
            if (message.equals("receiver preparation already attempted")) return ProviderReason.PREPARATION_ALREADY_ATTEMPTED;
            if (message.equals("provider returned foreign readback")) return ProviderReason.FOREIGN_READBACK;
            if (message.equals("receipt collision")) return ProviderReason.RECEIPT_COLLISION;
            if (failure instanceof IllegalArgumentException) {
                for (StackTraceElement frame : failure.getStackTrace()) {
                    if (frame.getClassName().equals(MediaOwnerAction.class.getName())) return ProviderReason.TICKET_PARSE;
                }
            }
        }
        return ProviderReason.OTHER;
    }
    static ProviderReason incomingArmReason(Throwable failure) {
        // Classify only this gate's exact static rejection, never a payload or nested owner message.
        if (!(failure instanceof IllegalStateException)) return ProviderReason.INCOMING_ARM_UNAVAILABLE;
        String message = failure.getMessage();
        if ("incoming Sink activation order".equals(message)) return ProviderReason.INCOMING_ARM_ORDER;
        if ("activation authority projection".equals(message)) return ProviderReason.INCOMING_ARM_PROJECTION;
        if ("incoming Sink arm evidence".equals(message)) return ProviderReason.INCOMING_ARM_EVIDENCE;
        return ProviderReason.INCOMING_ARM_UNAVAILABLE;
    }
    public String ownerFailureDiagnostic() throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        return new JSONObject().put("stage", failedOwnerStage.name())
                .put("sink_stage", failedSinkStage).put("action", failedOwnerAction)
                .put("provider_reason", failedProviderReason.name()).put("owner", failedOwnerKind)
                .put("cause", failedCause).put("code", failedOwnerStage == OwnerStage.NONE
                        ? "NONE" : "OWNER_EFFECT_REJECTED").toString();

        }
    }
    // Separate bounded observation. Never replace the first failure or affect dispatch admission.
    private static final long MAX_ATTEMPT_SEQUENCE = 9_007_199_254_740_991L;
    private long latestAttemptSequence;
    private String latestAttemptOutcome = "NONE";
    private boolean latestAttemptCompensate;
    private OwnerStage latestAttemptStage = OwnerStage.NONE;
    private String latestAttemptAction = "NONE", latestAttemptOwner = "NONE", latestAttemptCause = "NONE";
    private ProviderReason latestAttemptReason = ProviderReason.NONE;

    public synchronized String ownerLatestAttemptDiagnostic() throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        return new JSONObject().put("attempt_sequence", latestAttemptSequence)
                .put("outcome", latestAttemptOutcome).put("compensate", latestAttemptCompensate)
                .put("stage", latestAttemptStage.name()).put("action", latestAttemptAction)
                .put("owner", latestAttemptOwner).put("cause", latestAttemptCause)
                .put("provider_reason", latestAttemptReason.name()).toString();

        }
    }
    private synchronized long beginLatestAttempt(boolean compensate) {
        if (latestAttemptSequence == MAX_ATTEMPT_SEQUENCE) {
            latestAttemptOutcome = "UNAVAILABLE";
            latestAttemptCompensate = false;
            latestAttemptStage = OwnerStage.NONE;
            latestAttemptAction = latestAttemptOwner = latestAttemptCause = "NONE";
            latestAttemptReason = ProviderReason.NONE;
            return 0; // Diagnostic exhaustion cannot deny or replay the actual effect.
        }
        long sequence = ++latestAttemptSequence;
        latestAttemptCompensate = compensate;
        latestAttemptOutcome = "IN_PROGRESS";
        latestAttemptStage = OwnerStage.CALLBACK_FENCE;
        latestAttemptAction = "BEFORE_TICKET";
        latestAttemptOwner = latestAttemptCause = "NONE";
        latestAttemptReason = ProviderReason.NONE;
        return sequence;
    }
    private synchronized void observeLatestAttempt(long sequence, OwnerStage stage,
            MediaOwnerAction ticket, String outcome, Exception failure) {
        if (sequence == 0 || sequence != latestAttemptSequence
                || "UNAVAILABLE".equals(latestAttemptOutcome)) return;
        latestAttemptStage = stage;
        latestAttemptOutcome = outcome;
        String kind = ticket == null ? "NONE" : ticket.actionKind();
        latestAttemptAction = "arm_receiver".equals(kind) ? "ARM_RECEIVER"
                : "arm_cleanup".equals(kind) ? "ARM_CLEANUP" : "start".equals(kind) ? "START"
                : "stop".equals(kind) ? "STOP" : "cleanup".equals(kind) ? "CLEANUP" : "BEFORE_TICKET";
        latestAttemptOwner = ticket == null ? "NONE" : ticket.ownerKind();
        latestAttemptReason = failure == null ? ProviderReason.NONE : stage == OwnerStage.PROVIDER_EXECUTION
                ? providerReason(failure) : stage == OwnerStage.INCOMING_ARM_VERIFICATION
                        ? incomingArmReason(failure) : ProviderReason.NONE;
        latestAttemptCause = failure != null && stage == OwnerStage.PROVIDER_EXECUTION
                ? failureCategory(failure) : "NONE";
    }
    public String executeAndVerify(String authorityJson, String ticketJson, boolean compensate) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        final long attempt = beginLatestAttempt(compensate);
        OwnerStage stage = OwnerStage.CALLBACK_FENCE;
        MediaOwnerAction ticket = null;
        try {
        requireProcessCallback();
        JSONObject authority = new JSONObject(authorityJson);
        ticket = MediaOwnerAction.parse(ticketJson);
        stage = OwnerStage.PROJECTION_BINDING;
        observeLatestAttempt(attempt, stage, ticket, "IN_PROGRESS", null);
        if (!"rusty.quest.c1.owner_projection.v1".equals(authority.getString("$schema"))
                || !localPeerId.equals(authority.getString("executor_peer_id"))
                || !routeConfigurationSha256.equals(authority.getString("route_configuration_sha256"))
                || authority.getLong("expires_at_ms") <= System.currentTimeMillis()
                || !ticket.authorityEpochId().equals(authority.getString("authority_provider_epoch_id"))
                || !ticket.clientId().equals(authority.getString("authority_client_id"))
                || !ticket.leaseId().equals(authority.getString("authority_runtime_lease_id"))) {
            throw new IllegalStateException("platform projection binding rejected");
        }
        stage = OwnerStage.REGISTRY_BINDING;
        observeLatestAttempt(attempt, stage, ticket, "IN_PROGRESS", null);
        PackagedAndroidMediaOwnerRegistry current = registry;
        if (current == null) throw new IllegalStateException("platform registry absent");
        requireProcessCallback();
        EmbeddedDuplexActivationGate gate = activationGate;
        EmbeddedDuplexActivationGate.MediaTicket activationTicket = activationTicket(ticket);
        stage = OwnerStage.INCOMING_FENCE;
        observeLatestAttempt(attempt, stage, ticket, "IN_PROGRESS", null);
        if (gate != null) gate.beforeOwnerEffect(authority, activationTicket, compensate);
        if (!compensate && "source".equals(ticket.ownerKind()) && "start".equals(ticket.actionKind())) {
            stage = OwnerStage.LOCAL_QUIESCENCE;
            observeLatestAttempt(attempt, stage, ticket, "IN_PROGRESS", null);
            display.ensureLocalCaptureStopped();
            if (!EmbeddedDuplexNative.localCameraQuiescent()) {
                throw new IllegalStateException("local Camera2 ownership remains live");
            }
        }
        stage = OwnerStage.PROVIDER_EXECUTION;
        observeLatestAttempt(attempt, stage, ticket, "IN_PROGRESS", null);
        String readback = current.execute(ticketJson, compensate);
        stage = OwnerStage.RECEIPT_VERIFICATION;
        observeLatestAttempt(attempt, stage, ticket, "IN_PROGRESS", null);
        String verified = current.verifyAndReadEvidence(ticketJson, readback);
        if (verified == null) throw new IllegalStateException("live provider evidence rejected");
        if (gate != null) {
            stage = OwnerStage.INCOMING_ARM_VERIFICATION;
            observeLatestAttempt(attempt, stage, ticket, "IN_PROGRESS", null);
            gate.afterVerifiedOwnerEffect(authority, activationTicket,
                    new JSONObject(readback), new JSONObject(verified), compensate);
        }
        JSONObject result = new JSONObject();
        result.put("readback", new JSONObject(readback));
        result.put("readback_json", readback);
        result.put("verified", new JSONObject(verified));
        observeLatestAttempt(attempt, stage, ticket, "SUCCEEDED", null);
        return result.toString();
        } catch (Exception failure) {
            observeLatestAttempt(attempt, stage, ticket, "FAILED", failure);
            // Retain the primary failure within this incarnation. Cleanup failures must not erase it.
            recordOwnerFailure(stage, ticket, failure);
            android.util.Log.i("RQSpatialCameraPanel", "channel=embedded-duplex status=owner-effect-rejected stage="
                    + stage.name() + " primaryStage=" + failedOwnerStage.name() + " primaryAction=" + failedOwnerAction
                    + " sinkStage=" + failedSinkStage + " providerReason=" + failedProviderReason.name()
                    + " code=OWNER_EFFECT_REJECTED");
            throw failure;
        }

        }
    }

    // Diagnostic only; invoked by the catch before the original exception is rethrown.
    void recordOwnerFailure(OwnerStage stage, MediaOwnerAction ticket, Exception failure) {
        synchronized (this) {
            if (failedOwnerStage == OwnerStage.NONE) {
                EmbeddedDuplexResources currentResources = resources;
                failedSinkStage = ticket != null && "sink".equals(ticket.ownerKind())
                        && currentResources != null ? currentResources.incoming().failedArmStage() : "NONE";
                String kind = ticket == null ? "NONE" : ticket.actionKind();
                failedOwnerAction = "arm_receiver".equals(kind) ? "ARM_RECEIVER"
                        : "arm_cleanup".equals(kind) ? "ARM_CLEANUP" : "start".equals(kind) ? "START"
                        : "stop".equals(kind) ? "STOP" : "cleanup".equals(kind) ? "CLEANUP" : "BEFORE_TICKET";
                failedProviderReason = stage == OwnerStage.PROVIDER_EXECUTION
                        ? providerReason(failure) : stage == OwnerStage.INCOMING_ARM_VERIFICATION
                                ? incomingArmReason(failure) : ProviderReason.NONE;
                failedOwnerKind = ticket == null ? "NONE" : ticket.ownerKind();
                failedCause = stage == OwnerStage.PROVIDER_EXECUTION ? failureCategory(failure) : "NONE";
                failedOwnerStage = stage;
            }
        }
    }

    // Public visibility is required by the native ProductActivationRegistry callback.
    public String activateProduct(String activationId, String authorityJson, String proofJson)
            throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        EmbeddedDuplexActivationGate gate = activationGate;
        if (gate == null || resources == null) throw new IllegalStateException("resources absent");
        return gate.activate(activationId, authorityJson, proofJson);

        }
    }

    public byte[] signAuthorityBytes(byte[] exactNativeBytes) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        return EmbeddedDuplexIdentity.signExactAuthorityBytes(identity, exactNativeBytes);

        }
    }

    // Native calls this only after current route, enrollment, endpoint and time
    // validation. The generic dispatch signer cannot sign this domain.
    public byte[] signValidatedCommonLanBytes(byte[] validatedNativeBytes) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        return EmbeddedDuplexIdentity.signValidatedCommonLanBytes(identity, validatedNativeBytes);

        }
    }

    public byte[] signPairCeremonyBytes(byte[] exactNativeBytes) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        return EmbeddedDuplexIdentity.signPairCeremonyBytes(identity, exactNativeBytes);

        }
    }

    public byte[] signValidatedFreshPairSetupBytes(byte[] bytes)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
            requireProcessCallback();
            return EmbeddedDuplexIdentity.signValidatedFreshPairSetupBytes(identity,bytes);
        }
    }
    public byte[] signValidatedFreshPhasePrepareBytes(byte[] bytes)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
            requireProcessCallback();return EmbeddedDuplexIdentity.signValidatedFreshPhasePrepareBytes(identity,bytes);
        }
    }
    public byte[] signPreparedPeerStartFrameBytes(byte[] bytes)throws Exception{
        try(EmbeddedDuplexProcessFence.CallbackLease ignored=enterProcessCallback()){
            requireProcessCallback();return EmbeddedDuplexIdentity.signPreparedPeerStartFrameBytes(identity,bytes);
        }
    }
    public byte[] signFreshPairSetupFrameBytes(byte[] bytes)throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
            requireProcessCallback();
            return EmbeddedDuplexIdentity.signFreshPairSetupFrameBytes(identity,bytes);
        }
    }
    public byte[] localPublicKeyBytes() {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        return identity.rawPublicKey();

        }
    }

    private final OwnerTransportAttempt transportAttempt = new OwnerTransportAttempt();
    public String ownerTransportAttemptDiagnostic() {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        return transportAttempt.snapshot();

        }
    }
    public byte[] exchangeOwnerFrame(String targetPeerId, byte[] exactFrame) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        final long attempt = transportAttempt.begin();
        try {
            requireProcessCallback();
            if (!remotePeerId.equals(targetPeerId) || !controlReady()) {
                throw new IllegalStateException("owner control target unavailable");
            }
        } catch (Exception failure) {
            transportAttempt.observe(attempt, OwnerDispatchTcpEndpoint.Phase.TARGET_BINDING,
                    OwnerDispatchTcpEndpoint.Outcome.FAILED, OwnerDispatchTcpEndpoint.Failure.TARGET_UNAVAILABLE);
            throw failure;
        }
        EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
        OwnerDispatchTcpEndpoint.ExchangeObserver observer=(phase,outcome,failure)->transportAttempt.observe(attempt,phase,outcome,failure);
        OwnerDispatchTcpEndpoint.RequestSpan incoming=null;String nativeId;
        if(actual==null){
            synchronized(this){
                OwnerDispatchTcpEndpoint.RequestSpan current=OwnerDispatchTcpEndpoint.currentRequestSpan();
                if(current!=null && endpoint!=null && endpoint.ownsRequestSpan(current) && incomingPhases.contains(current))incoming=current;
                nativeId=incoming==null?null:incomingFreshRequestId;
            }
            if(incoming==null)return OwnerDispatchTcpEndpoint.exchange(remoteControlAddress,remoteControlPort,exactFrame,observer);
        }else nativeId=EmbeddedDuplexOriginals.nativeSetupRequestId(actual);
        OwnerDispatchTcpEndpoint.ExchangeControl control=new OwnerDispatchTcpEndpoint.ExchangeControl();
        String frameSha=EmbeddedDuplexCanonicalJson.sha256Bytes(exactFrame);
        OwnedExchange held=new OwnedExchange(actual,incoming,frameSha,nativeId,EmbeddedDuplexProcessHost.freshClosureIdentity(this),remoteControlAddress,remoteControlPort);
        ownedExchanges.put(control,held);
        if(actual!=null?actual.desiredStop.get():incomingFreshDesiredStop)control.requestStop();
        try{return OwnerDispatchTcpEndpoint.exchangeOwned(remoteControlAddress,remoteControlPort,exactFrame,observer,control);}
        finally{
            if(!control.joined().isDone())throw new IllegalStateException("actual outbound exchange not joined");
            ownedExchanges.remove(control,held);
        }

        }
    }

    /** Fixed native v2 callback; target identity is preserved independently of requester. */
    public String executeRetainedCleanupAndVerify(String authorityJson, String ticketJson,
            boolean compensate) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        final long attempt = beginLatestAttempt(compensate);
        OwnerStage stage = OwnerStage.CALLBACK_FENCE;
        OwnerStage diagnosticStage = stage;
        MediaOwnerAction ticket = null;
        try {
        requireProcessCallback();
        stage = OwnerStage.PROJECTION_BINDING;
        diagnosticStage = stage;
        observeLatestAttempt(attempt, diagnosticStage, null, "IN_PROGRESS", null);
        JSONObject authority = new JSONObject(authorityJson);
        ticket = MediaOwnerAction.parse(ticketJson);
        observeLatestAttempt(attempt, diagnosticStage, ticket, "IN_PROGRESS", null);
        if (!"rusty.quest.android.media.retained_cleanup_projection.v2".equals(authority.getString("$schema"))
                || !localPeerId.equals(authority.getString("executor_peer_id"))
                || authority.getLong("expires_at_ms") <= System.currentTimeMillis()
                || authority.getLong("requester_expires_at_ms") <= System.currentTimeMillis()
                || !ticket.authorityEpochId().equals(authority.getString("provider_epoch_id"))
                || !ticket.clientId().equals(authority.getString("target_client_id"))
                || !ticket.leaseId().equals(authority.getString("target_runtime_lease_id"))
                || !"stop".equals(ticket.operation())
                || !("stop".equals(ticket.actionKind()) || "cleanup".equals(ticket.actionKind()))) {
            throw new IllegalStateException("retained cleanup target binding rejected");
        }
        boolean distinct = authority.getBoolean("trusted_revoker");
        if (distinct != (!ticket.clientId().equals(authority.getString("requester_id"))
                && !ticket.leaseId().equals(authority.getString("requester_runtime_lease_id")))) {
            throw new IllegalStateException("retained cleanup requester binding rejected");
        }
        stage = OwnerStage.REGISTRY_BINDING;
        diagnosticStage = stage;
        observeLatestAttempt(attempt, diagnosticStage, ticket, "IN_PROGRESS", null);
        PackagedAndroidMediaOwnerRegistry current = registry;
        if (current == null) throw new IllegalStateException("platform registry absent");
        EmbeddedDuplexActivationGate gate = activationGate;
        EmbeddedDuplexActivationGate.MediaTicket activationTicket = activationTicket(ticket);
        stage = OwnerStage.INCOMING_FENCE;
        diagnosticStage = stage;
        observeLatestAttempt(attempt, diagnosticStage, ticket, "IN_PROGRESS", null);
        if (gate != null) gate.beforeOwnerEffect(authority, activationTicket, compensate);
        requireProcessCallback();
        stage = OwnerStage.PROVIDER_EXECUTION;
        diagnosticStage = stage;
        observeLatestAttempt(attempt, diagnosticStage, ticket, "IN_PROGRESS", null);
        String readback = current.execute(ticketJson, compensate);
        stage = OwnerStage.RECEIPT_VERIFICATION;
        diagnosticStage = stage;
        observeLatestAttempt(attempt, diagnosticStage, ticket, "IN_PROGRESS", null);
        String verified = current.verifyAndReadEvidence(ticketJson, readback);
        if (verified == null) throw new IllegalStateException("retained cleanup live evidence rejected");
        // Terminal eligibility remains receipt verification, never an incoming Arm effect.
        if (gate != null) {
            diagnosticStage = OwnerStage.INCOMING_ARM_VERIFICATION;
            observeLatestAttempt(attempt, diagnosticStage, ticket, "IN_PROGRESS", null);
            gate.afterVerifiedOwnerEffect(authority, activationTicket,
                    new JSONObject(readback), new JSONObject(verified), compensate);
        }
        String result = new JSONObject().put("readback", new JSONObject(readback))
                .put("readback_json", readback).put("verified", new JSONObject(verified)).toString();
        observeLatestAttempt(attempt, diagnosticStage, ticket, "SUCCEEDED", null);
        return result;
        } catch (Exception failure) {
            observeLatestAttempt(attempt, diagnosticStage, ticket, "FAILED", failure);
            recordOwnerFailure(stage, ticket, failure);
            throw failure;
        }

        }
    }

    public synchronized void persistCleanupPreparations(String snapshotJson) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        persistReplay(cleanupPreparationsFile, snapshotJson);

        }
    }
    public synchronized String loadCleanupPreparations() throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        if (!cleanupPreparationsFile.getBaseFile().exists()
                && !new File(cleanupPreparationsFile.getBaseFile().getPath() + ".bak").exists()) {
            return "{\"originals\":{},\"prepared\":{}}";
        }
        return loadReplay(cleanupPreparationsFile);

        }
    }

    public synchronized void persistRetainedCleanupReplay(String snapshotJson) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        persistReplay(retainedCleanupReplayFile, snapshotJson);

        }
    }

    public synchronized String loadRetainedCleanupReplay() throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        return loadReplay(retainedCleanupReplayFile);

        }
    }

    public synchronized void persistDispatchReplay(String snapshotJson) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        persistReplay(replayFile, snapshotJson);

        }
    }

    public synchronized void persistActivationReplay(String snapshotJson) throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        requireProcessCallback();
        persistReplay(activationReplayFile, snapshotJson);

        }
    }

    private void persistReplay(AtomicFile file, String snapshotJson) throws Exception {
        byte[] encoded = snapshotJson.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_REPLAY_BYTES) {
            throw new IllegalArgumentException("replay bounds");
        }
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            Os.fchmod(output.getFD(), 0600);
            output.write(encoded);
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            syncReplayDirectory();
        } finally {
            if (output != null) file.failWrite(output);
        }
    }

    synchronized String loadDispatchReplay() throws Exception { return loadReplay(replayFile); }

    public synchronized String loadActivationReplay() throws Exception {
        try(EmbeddedDuplexProcessFence.CallbackLease callback=enterProcessCallback()){
        return loadReplay(activationReplayFile);

        }
    }

    private String loadReplay(AtomicFile file) throws Exception {
        EmbeddedDuplexAbandonment.requireReplayRead(replayDirectory,file.getBaseFile().getName());
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return "{\"pending_request_sha256\":{},\"terminal\":{}}";
        }
        try (FileInputStream input = file.openRead()) {
            long length = input.getChannel().size();
            if (length <= 0 || length > MAX_REPLAY_BYTES) throw new IllegalStateException("replay bounds");
            byte[] encoded = new byte[(int) length];
            int offset = 0;
            while (offset < encoded.length) {
                int count = input.read(encoded, offset, encoded.length - offset);
                if (count < 0) throw new IllegalStateException("replay truncated");
                offset += count;
            }
            if (input.read() != -1) throw new IllegalStateException("replay grew during read");
            return EmbeddedDuplexAbandonment.visibleReplay(replayDirectory,file.getBaseFile().getName(),
                    new String(encoded, StandardCharsets.UTF_8));
        }
    }

    private static EmbeddedDuplexActivationGate.MediaTicket activationTicket(MediaOwnerAction ticket) {
        return new EmbeddedDuplexActivationGate.MediaTicket(ticket.executorGeneration(),
                ticket.expectedRuntimeRevision(), ticket.actionId(), ticket.authorityEpochId(),
                ticket.clientId(), ticket.leaseId(), ticket.operation(), ticket.ownerKind(),
                ticket.actionKind());
    }

    /** Only after both products' authenticated terminal cleanup, or before any effect was issued. */
    void closeControlAfterProductCleanup() {
        OwnerDispatchTcpEndpoint current = endpoint;
        if (current != null) {
            current.close();
            if (!current.terminal()) throw new IllegalStateException("owner control cleanup pending");
        }
        PackagedAndroidMediaOwnerRegistry owners = registry;
        if (owners != null) owners.close();
    }

    /** Drain authenticated ingress before proving the registry never entered a provider. */
    void closeControlForNoMedia() {
        OwnerDispatchTcpEndpoint current = endpoint;
        if (current != null) {
            current.close();
            if (!current.terminal()) throw new IllegalStateException("owner control cleanup pending");
        }
        PackagedAndroidMediaOwnerRegistry owners = registry;
        if (owners != null) owners.closeIfNeverAttempted();
    }

    private void syncReplayDirectory() throws Exception {
        FileDescriptor directory = Os.open(replayDirectory.getAbsolutePath(),
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        try {
            if ((Os.fstat(directory).st_mode & OsConstants.S_IFMT) != OsConstants.S_IFDIR) {
                throw new IllegalStateException("replay directory changed");
            }
            Os.fsync(directory);
        } finally { Os.close(directory); }
    }

    private static void rejectLink(File file) {
        if (Files.isSymbolicLink(file.toPath()) || (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalStateException("replay path type");
        }
    }

    private static InetAddress numericIpv4(String address) throws Exception {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("numeric IPv4 required");
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("canonical IPv4 required");
            int value = Integer.parseInt(parts[i]);
            if (value > 255) throw new IllegalArgumentException("IPv4 bounds");
            bytes[i] = (byte) value;
        }
        InetAddress parsed = InetAddress.getByAddress(bytes);
        if (parsed.isAnyLocalAddress() || parsed.isLoopbackAddress() || parsed.isMulticastAddress()
                || "255.255.255.255".equals(address)) throw new IllegalArgumentException("unicast peer IPv4 required");
        return parsed;
    }
}
