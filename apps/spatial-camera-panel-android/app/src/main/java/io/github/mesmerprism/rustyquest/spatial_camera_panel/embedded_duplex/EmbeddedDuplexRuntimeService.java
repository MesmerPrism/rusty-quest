package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/** Shared app-owned handler for the panel and the debug typed operator transport. */
public final class EmbeddedDuplexRuntimeService {
    private EmbeddedDuplexRuntimeService() {}

    /** Pure shared-profile validation; no process/native owner or identity is created. */
    public static void validateControlProfileAddons(Context context, byte[] profile) throws Exception {
        FrozenEffectiveInputs.validateVisualCandidate(context, profile);
    }

    /** Cached accepted input/native-configuration observation, never a live authority proof. */
    public static String effectiveInputObservation() {
        return EmbeddedDuplexProcessHost.effectiveInputObservation();
    }

    /** Debug adapter: only the existing cache and physical process identity are read. */
    public static String cachedEffectiveInputs(String challenge,String runId,int pid,int uid,String ticks,String bootId) throws Exception {
        return EmbeddedDuplexCachedInputs.read(challenge,runId,pid,uid,ticks,bootId);
    }

    /** One bootstrap Original exists before enqueue or Activity pre-entry evaluation. */
    public static CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeerCommand(Context context) {
        return bootstrapRealPeerFromActivity(context,()->
                io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.requestRealPeerBootstrap());
    }
    public static CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeerFromActivity(Context context,
            Supplier<CompletableFuture<EmbeddedDuplexRuntimeStatus>> actualAction) {
        try {
            EmbeddedDuplexOriginals.Record current=EmbeddedDuplexOriginals.capture();
            if(current!=null) {
                if(!"bootstrap_real_peer".equals(current.row.getString("operation")))throw new IllegalStateException("bootstrap Original operation differs");
                return actualAction.get();
            }
            String challenge=java.util.UUID.randomUUID().toString().replace("-","");
            return EmbeddedDuplexOriginals.forApplication(context).beginInternal("bootstrap_real_peer",challenge,
                    EmbeddedDuplexOriginals.request("bootstrap_real_peer",challenge,new Bundle()),actualAction,state->{
                        try{return CompletableFuture.completedFuture(EmbeddedDuplexAppAdmission.serviceBundle(challenge,state));}
                        catch(Exception failure){return CompletableFuture.failedFuture(failure);}
                    });
        } catch(Throwable failure){return CompletableFuture.failedFuture(failure);}
    }
    public static CompletableFuture<String> bootstrapReadiness(Context context,String expectedRunId) {
        try {
            // This read does not obtain ProcessHost.forApplication or acquire a writer/Native capability.
            EmbeddedDuplexRunSelectionStore store=new EmbeddedDuplexRunSelectionStore(
                    context.getApplicationContext().getNoBackupFilesDir(),directory->{throw new IllegalStateException("read-only selector sync");});
            EmbeddedDuplexRunContext captured=store.current();
            if(!java.util.Objects.equals(expectedRunId,captured.runId))throw new IllegalStateException("readiness selector differs from requested run");
            return io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.requestBootstrapReadiness()
                    .thenApply(raw->{try{
                        captured.requireSame(store.current());
                        return EmbeddedDuplexCanonicalJson.encode(new JSONObject()
                                .put("$schema","rusty.quest.embedded_duplex.bootstrap_readiness_observation.v1")
                                .put("selector_run_id",captured.runId==null?JSONObject.NULL:captured.runId)
                                .put("selector_generation",captured.selectionGeneration)
                                .put("activity",new JSONObject(raw)));
                    }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}});
        } catch(Throwable failure){return CompletableFuture.failedFuture(failure);}
    }

    public static CompletableFuture<String> newRun(Context context,String runId,String legacyWholeOriginalId){
        java.util.function.BiConsumer<String,Throwable> reconcile=io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.captureRunSelectionReconciliation();
        return EmbeddedDuplexProcessHost.forApplication(context).selectRun(runId,true,legacyWholeOriginalId).whenComplete(reconcile);
    }
    public static CompletableFuture<String> resumeRun(Context context,String runId){
        java.util.function.BiConsumer<String,Throwable> reconcile=io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.captureRunSelectionReconciliation();
        return EmbeddedDuplexProcessHost.forApplication(context).selectRun(runId,false,null).whenComplete(reconcile);
    }
    public static CompletableFuture<String> runStatus(Context context){return EmbeddedDuplexProcessHost.forApplication(context).runStatus();}
    /** Closed debug adapter; selected run request custody stays with the caller. */
    public static CompletableFuture<Bundle> guardedModeCall(Context context,String runId,long appGeneration,
            String epoch,String apkSha,long nativeGeneration,String appRecord,String runtimeSha,String method,String challenge,
            Supplier<CompletableFuture<Bundle>> action) {
        return EmbeddedDuplexProcessHost.forApplication(context).guardedModeCall(runId,appGeneration,
                epoch,apkSha,nativeGeneration,appRecord,runtimeSha,method,challenge,action);
    }
    public static CompletableFuture<String> recoverColdPending(Context context,String runId,String priorRunId,String priorOriginalId){
        return EmbeddedDuplexProcessHost.forApplication(context).recoverColdPending(runId,priorRunId,priorOriginalId);
    }
    public static String selectedRunId(Context context){return EmbeddedDuplexProcessHost.operationalContext(context).runId;}
    public static <T>T withRunRequest(Context context,String runId,java.util.concurrent.Callable<T> request)throws Exception{
        return EmbeddedDuplexProcessHost.forApplication(context).withRunRequest(runId,request);
    }
    public static Bundle queryOriginal(Context context,String runId,String originalId,String challenge)throws Exception{
        EmbeddedDuplexRunContext current=EmbeddedDuplexProcessHost.operationalContext(context);
        if(java.util.Objects.equals(runId,current.runId))return queryOriginal(context,originalId,challenge);
        EmbeddedDuplexRunContext.requireId(runId);
        return EmbeddedDuplexOriginals.retained(new EmbeddedDuplexRunSelectionStore(context.getApplicationContext().getNoBackupFilesDir(),dir->{}).retained(runId)).query(originalId,challenge);
    }
    /** Explicit source-owned custody; the supplier returns the existing real command Future. */
    public static Bundle beginOriginal(Context context, String originalId, String operation, String challenge,
            JSONObject canonicalRequest, Supplier<CompletableFuture<Bundle>> actualAction) throws Exception {
        return EmbeddedDuplexOriginals.forApplication(context).begin(
                originalId, operation, challenge, canonicalRequest, actualAction);
    }

    public static Bundle queryOriginal(Context context, String originalId, String challenge) throws Exception {
        return EmbeddedDuplexOriginals.forApplication(context).query(originalId, challenge);
    }

    public static Bundle acknowledgeOriginal(Context context, String originalId, String challenge,
            String requestSha256, String outcomeSha256, String consumerReceiptSha256) throws Exception {
        return EmbeddedDuplexOriginals.forApplication(context).acknowledge(
                originalId, challenge, requestSha256, outcomeSha256, consumerReceiptSha256);
    }

    /** Read-only token/current-actor check; NEW alone is never proof of full cleanup. */
    public static boolean wholeCloseRolloverCurrent(Context context, EmbeddedDuplexWholeCloseRollover proof) {
        return EmbeddedDuplexProcessHost.forApplication(context).wholeCloseRolloverCurrent(proof);
    }

    public static CompletableFuture<String> armConcurrentQualification(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentQualification(true, challenge);
    }
    public static CompletableFuture<String> concurrentQualificationStatus(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentQualification(false, challenge);
    }

    /** Debug shell observation only; does not advance an owner or media readiness state. */
    public static CompletableFuture<String> ownerFailureDiagnosticRead(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).ownerFailureDiagnosticRead(challenge);
    }
    public static CompletableFuture<String> ownCaptureDiagnostic(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).ownCaptureDiagnostic(challenge);
    }
    /** Read-only bounded statistics; never admission, activation or cleanup proof. */
    public static CompletableFuture<String> streamDropoutDiagnostic(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).streamDropoutDiagnostic(challenge);
    }

    public static CompletableFuture<String> concurrentHubObservation(Context context) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentHubObservation();
    }
    public static CompletableFuture<String> concurrentHubPolicy(Context context, long[] policy, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentHubPolicy(policy,cancelled);
    }
    public static CompletableFuture<String> concurrentMask(Context context, String challenge, long[] mask) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentMask(challenge,mask);
    }
    public static CompletableFuture<String> concurrentPolicy(Context context, String challenge, long[] policy) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentPolicy(challenge, policy);
    }

    public static CompletableFuture<String> peerLifecycle(Context context, EmbeddedDuplexPeerAction action, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).peerLifecycle(action, challenge);
    }

    /** App-owned composition; a missing prerequisite keeps its distinct setup/recovery action. */
    public static CompletableFuture<String> ordinaryStart(Context context, long displayGeneration,
            String expectedArmChallenge, java.util.function.BooleanSupplier currentActivity) {
        return EmbeddedDuplexProcessHost.forApplication(context).ordinaryStart(
                displayGeneration, expectedArmChallenge, currentActivity);
    }

    public static CompletableFuture<String> stopCurrentPeer(Context context, long displayGeneration) {
        return EmbeddedDuplexProcessHost.forApplication(context).stopCurrentPeer(displayGeneration);
    }

    public static CompletableFuture<EmbeddedDuplexRuntimeStatus> status(Context context) {
        return EmbeddedDuplexProcessHost.forApplication(context).runtimeStatus();
    }

    public static CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeer(
            Context context, long displayGeneration) {
        return EmbeddedDuplexProcessHost.forApplication(context)
                .bootstrapRealPeer(displayGeneration);
    }

    public static CompletableFuture<String> closeNoMedia(Context context,
            long displayGeneration) {
        return EmbeddedDuplexProcessHost.forApplication(context)
                .closeRealPeerNoMedia(displayGeneration);
    }

    public static CompletableFuture<EmbeddedDuplexPairStatus> pairStatus(Context context) {
        return EmbeddedDuplexProcessHost.forApplication(context).pairStatus();
    }

    /** The lower packaged peer ID initiates; the other Quest answers on its process endpoint. */
    public static CompletableFuture<EmbeddedDuplexPairStatus> pairSession(Context context) {
        return EmbeddedDuplexProcessHost.forApplication(context).pairSession();
    }

    /** Retains a current signed session and process lineage before any Start. */
    public static CompletableFuture<EmbeddedDuplexStartPreflight> prepareStartPreflight(
            Context context, long displayGeneration) {
        return EmbeddedDuplexProcessHost.forApplication(context)
                .prepareStartPreflight(displayGeneration);
    }

    public static boolean preflightLive(Context context, EmbeddedDuplexStartPreflight observed) {
        return EmbeddedDuplexProcessHost.forApplication(context).preflightLive(observed);
    }


    /** Explicit app Close and lifecycle fallback share the authoritative whole-product close. */
    public static CompletableFuture<String> closeProduct(Context context,long displayGeneration) {
        return EmbeddedDuplexProcessHost.forApplication(context).closeProduct(displayGeneration);
    }

    public static CompletableFuture<String> completedSetupRecoveryStatus(Context context,String challenge){
        return EmbeddedDuplexProcessHost.existingForRecovery(context).completedSetupRecoveryStatus(challenge);
    }

    public static CompletableFuture<String> preparePeerStart(Context context){
        return EmbeddedDuplexProcessHost.existingForRecovery(context).preparePeerStart();
    }
    public static CompletableFuture<String> preparedPeerStartStatus(Context context){
        return EmbeddedDuplexProcessHost.existingForRecovery(context).preparedPeerStartStatus();
    }
    public static CompletableFuture<String> peerRootStatus(Context context){
        return EmbeddedDuplexProcessHost.existingForRecovery(context).peerRootStatus();
    }
    public static CompletableFuture<String> reestablishPeerRoots(Context context){
        return EmbeddedDuplexProcessHost.existingForRecovery(context).reestablishPeerRoots();
    }
    public static CompletableFuture<EmbeddedDuplexPairStatus> continueFreshPair(Context context){
        return EmbeddedDuplexProcessHost.forApplication(context).continueFreshPair();
    }}
