package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** One app-context mutation lane. Endpoint and Activity display callbacks never enter its queue. */
final class EmbeddedDuplexProcessHost {
    private enum Phase { NEW, PROVISIONING, BOOTSTRAPPING, READY, CLOSING, FAILED }
    private static volatile EmbeddedDuplexProcessHost instance;

    /** Native observes actual retained providers; absence and uncertainty are not terminal. */
    public static boolean nativeProductResourcesTerminal() {
        EmbeddedDuplexProcessHost current = instance;
        if (current == null) return false;
        EmbeddedDuplexResources retained = current.resources;
        if (retained == null) return false;
        try { return retained.productResourcesTerminal(); }
        catch (RuntimeException unavailable) { return false; }
    }

    static synchronized EmbeddedDuplexProcessHost forApplication(Context context) {
        if (instance == null) {
            if (context == null || context.getApplicationContext() == null) {
                throw new IllegalArgumentException("application context required");
            }
            instance = new EmbeddedDuplexProcessHost(context.getApplicationContext());
        }
        return instance;
    }

    private final Context applicationContext;
    private final InstalledApkDigest installedApkDigest = new InstalledApkDigest();
    // Held for the lifetime of this singleton, including failed bootstrap/cleanup.
    private volatile EmbeddedDuplexProcessFence processFence;
    private long nativeExecutorGeneration;
    private String nativeAppRecordSha256;
    private final EmbeddedDuplexDisplaySlot display = new EmbeddedDuplexDisplaySlot();
    private final Object attachmentGate = new Object();
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.NEW);
    private volatile long attachmentGeneration;
    private java.util.function.Consumer<EmbeddedDuplexWholeCloseRollover> wholeCloseActivity;
    private EmbeddedDuplexWholeCloseRollover wholeCloseRollover;
    private boolean displayDetaching;
    private boolean closeInFlight;
    private EmbeddedDuplexOriginals.Record activeWholeNativeOriginal;
    private final java.util.concurrent.ScheduledExecutorService commands = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "embedded-duplex-command");
        thread.setDaemon(true);
        return thread;
    });
    // Retain the callback after native GlobalRef capture, including a failed
    // post-initialization resource installation, so no second host can start.
    private EmbeddedDuplexPlatform platform;
    private volatile EmbeddedDuplexResources resources;
    private String runtimeConfigSha256;
    private EmbeddedDuplexRuntimeConfigurationBinding.Context nativeConfigurationContext;
    private EmbeddedDuplexSetupCredentials setupCredentials;
    private volatile EmbeddedDuplexBootstrap.Failure lastBootstrapFailure;
    private volatile RetainedWholeClose retainedWholeClose;
    static final class RetainedWholeClose {
        final String challenge, epoch, config, feature, apk, initialReceipt;
        final long appGeneration, executorGeneration, attachment;
        EmbeddedDuplexResources resources;
        EmbeddedDuplexPlatform platform;
        final CompletableFuture<String> completion = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
        OwnStereoCaptureRuntime.CleanupObserverRegistration observer;
        CompletableFuture<Void> observerMarker;
        EmbeddedDuplexOriginals.Record original=EmbeddedDuplexOriginals.capture();
        RetainedWholeClose(String challenge, String epoch, String config, String feature, String apk,
                String receipt, long app, long executor, long attachment,
                EmbeddedDuplexResources resources, EmbeddedDuplexPlatform platform) {
            this.challenge=challenge; this.epoch=epoch; this.config=config; this.feature=feature;
            this.apk=apk; this.initialReceipt=receipt; this.appGeneration=app;
            this.executorGeneration=executor; this.attachment=attachment;
            this.resources=resources; this.platform=platform;
        }
    }
    private RetainedNoMediaClose noMediaClose;
    private static final class RetainedNoMediaClose {
        final String config,appRecord,epoch; final long appGeneration,nativeGeneration,attachment;
        EmbeddedDuplexRuntimeConfigurationBinding.Context configurationContext;
        EmbeddedDuplexPlatform platform; EmbeddedDuplexResources resources;
        OwnStereoCaptureRuntime own; Runnable stopProjection; final JSONObject arm;
        EmbeddedDuplexOriginals.Record original;
        final CompletableFuture<String> completion=new CompletableFuture<>();
        JSONObject nativeProof,proof; EmbeddedDuplexRuntimeStatus closedStatus;
        boolean pendingFresh,stopIssued,displayDetached,controlClosed,resourcesClosed;
        boolean pendingUnknownRoot,pendingPrepared,projectionStopIssued;
        JSONObject preparedMetadata;
        JSONObject unknownRootMetadata;
        CompletableFuture<Void> callbackMarker; OwnStereoCaptureRuntime.CleanupObserverRegistration observer;
        RetainedNoMediaClose(String config,String appRecord,String epoch,long app,long nativeGeneration,long attachment,
                EmbeddedDuplexPlatform platform,EmbeddedDuplexResources resources,OwnStereoCaptureRuntime own,
                Runnable stopProjection,JSONObject arm,EmbeddedDuplexOriginals.Record original,EmbeddedDuplexRuntimeConfigurationBinding.Context configurationContext){
            this.config=config;this.appRecord=appRecord;this.epoch=epoch;this.appGeneration=app;
            this.nativeGeneration=nativeGeneration;this.attachment=attachment;this.platform=platform;
            this.resources=resources;this.own=own;this.stopProjection=stopProjection;this.arm=arm;this.original=original;this.configurationContext=configurationContext;
        }
    }
    private String ownFeatureLockSha256;
    private String ownNoMediaConfigSha256;
    private String ownNoMediaFeatureSha256;
    private String ownNoMediaCloseProof;
    private long ownNoMediaExecutorGeneration;
    private long ownNoMediaAppGeneration;
    private Runnable ownNoMediaStopTarget;
    private boolean ownNoMediaCleanupAttempted;
    private String retainedWholeNativeReceipt;
    private JSONObject lastClosedActorProof;
    private CompletableFuture<Void> wholeCallbackMarker;
    private EmbeddedDuplexPlatform wholeCallbackPlatform;
    private String terminalWholeReceipt;
    private String terminalWholeChallenge;
    private String enrollmentRecordSha256;
    private volatile EmbeddedDuplexOriginals.Record activeCommandOriginal;
    private void requestDesiredStopOutsideCommandLane(boolean whole){
        EmbeddedDuplexPlatform actual=platform;EmbeddedDuplexOriginals.Record entered=activeCommandOriginal;
        if(actual!=null){
            if(entered!=null)actual.requestDesiredStop(entered);
            actual.requestIncomingFreshStop();actual.requestIncomingPreparedStop();
        }else if(entered!=null)entered.desiredStop.set(true);
        if(whole){OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.currentForApplication();if(own!=null)own.requestStopOwn();}
    }
    private volatile boolean localFixture;
    private String diagnosticChallenge;
    // A single process-local review is held by object identity and consumed once.
    private EmbeddedDuplexEnrollmentReview pendingReview;
    // A preflight intent binds the current signed session to this process and
    // display. It is never sufficient to issue a route or invoke Start.
    private final EmbeddedDuplexStartIntentSlot startIntent = new EmbeddedDuplexStartIntentSlot();

    private EmbeddedDuplexProcessHost(Context applicationContext) {
        this.applicationContext = applicationContext;
    }

    static void requireIncomingFreshActor(EmbeddedDuplexPlatform actual)throws Exception{
        EmbeddedDuplexProcessHost current=instance;
        freshClosureIdentity(actual);
        if(current.phase.get()!=Phase.READY || current.closeInFlight || current.localFixture)
            throw new IllegalStateException("incoming fresh actor is closing or unavailable");
    }
    static void requireIncomingFreshRetirementBinding(String actualRequestId)throws Exception{
        EmbeddedDuplexProcessHost current=instance;
        if(current==null || current.phase.get()!=Phase.CLOSING || current.platform==null
                || !current.platform.incomingFreshTaskJoined(actualRequestId))
            throw new IllegalStateException("incoming fresh actual retired request tasks not joined");
        freshClosureIdentity(current.platform);
    }
    static JSONObject freshClosureIdentity(EmbeddedDuplexPlatform actual)throws Exception{
        EmbeddedDuplexProcessHost current=instance;
        if(current==null || current.platform!=actual || current.processFence==null || current.nativeExecutorGeneration<=0
                || current.runtimeConfigSha256==null || current.nativeAppRecordSha256==null)
            throw new IllegalStateException("fresh current actor absent");
        current.processFence.requireLive(current.processFence.generation());
        return new JSONObject().put("app_process_generation",current.processFence.generation())
            .put("app_record_sha256",current.nativeAppRecordSha256).put("native_executor_generation",current.nativeExecutorGeneration)
            .put("config_sha256",current.runtimeConfigSha256);
    }
    static JSONObject constructionPhysical(EmbeddedDuplexPlatform actual)throws Exception{
        EmbeddedDuplexProcessHost current=instance;
        RetainedNoMediaClose held=current==null?null:current.noMediaClose;
        if(held==null||current.phase.get()!=Phase.CLOSING||held.platform!=actual||current.platform!=actual
                ||held.original==null||EmbeddedDuplexOriginals.capture()!=held.original
                ||held.resources!=null||current.resources!=null||!held.controlClosed||!held.resourcesClosed||!held.displayDetached
                ||held.config==null||held.nativeGeneration<=0||held.configurationContext==null
                ||!EmbeddedDuplexRendererRetirement.currentTerminal()||!EmbeddedDuplexNative.localCameraQuiescent())
            throw new IllegalStateException("actual partial-construction physical owners remain Pending");
        JSONObject own=held.own==null?new JSONObject().put("state","not_created"):held.own.physicalRetirementObservation();
        if(!java.util.Arrays.asList("retired","not_created").contains(own.getString("state")))
            throw new IllegalStateException("actual partial-construction Own remains Pending");
        current.processFence.requireLive(held.appGeneration);
        return new JSONObject().put("app_process_generation",held.appGeneration).put("app_record_sha256",held.appRecord)
            .put("native_executor_generation",held.nativeGeneration).put("config_sha256",held.config);
    }
    static JSONObject freshPhysical(EmbeddedDuplexPlatform actual,JSONObject originalBinding,boolean retirement)throws Exception{
        EmbeddedDuplexProcessHost current=instance;JSONObject identity=freshClosureIdentity(actual);
        boolean correctPhase=retirement?current.phase.get()==Phase.CLOSING:current.phase.get()==Phase.READY;
        EmbeddedDuplexOriginals originals=EmbeddedDuplexOriginals.forApplication(current.applicationContext);
        EmbeddedDuplexOriginals.Record entered=EmbeddedDuplexOriginals.capture();
        if(!retirement && entered!=null)originalBinding=new JSONObject().put("kind","operator").put("original_id",entered.id());
        int unresolved=retirement?originals.unresolvedForRetirement(originalBinding):originals.unresolvedExceptCurrent();
        OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.currentForApplication();
        JSONObject ownObservation=own==null?new JSONObject().put("phase","NotConstructed").put("state","not_created").put("capture_ever_owned",false):own.physicalRetirementObservation();
        String ownState=ownObservation.getString("state");
        boolean ownAllowed=retirement?java.util.Arrays.asList("retired","not_created").contains(ownState)
            :java.util.Arrays.asList("retired","not_created","live_independent").contains(ownState);
        boolean never=actual.freshPeerEffectsNeverEntered();
        boolean peer=current.resources!=null && (never || current.resources.productResourcesTerminal());
        boolean control=retirement?actual.actualControlTerminal():actual.freshPeerControlQuiescent();
        boolean rendererTerminal=retirement?EmbeddedDuplexRendererRetirement.currentTerminal()
            :EmbeddedDuplexRendererRetirement.currentFreshEligible(ownState,current.processFence.generation());
        JSONObject out=new JSONObject(identity.toString()).put("$schema",retirement
            ?"rusty.quest.embedded_duplex.fresh_setup_physical_retirement.v1":"rusty.quest.embedded_duplex.fresh_setup_physical_eligibility.v1")
            .put("eligible",correctPhase && ownAllowed && peer && control && rendererTerminal && unresolved==0)
            .put("own_capture_state",ownState).put("peer_resources_terminal",peer).put("renderer_terminal",rendererTerminal)
            .put("control_terminal",control).put("unresolved_original_count",unresolved)
            .put("original_binding",originalBinding==null?JSONObject.NULL:originalBinding);
        if(!retirement) {
            JSONObject prepared=actual.preparedPeerPhysicalStatus();
            if(prepared!=null) {
                out.put("$schema","rusty.quest.embedded_duplex.fresh_setup_physical_eligibility.v2")
                    .put("classification","prepared_uncommitted").put("prepared_start",prepared)
                    .put("peer_resources_terminal",false)
                    .put("eligible",correctPhase&&ownAllowed&&control&&unresolved==0);
                return out; // Dormant owned resources are truthful nonterminal; Native joins SAME actual handle.
            }
        }
        if(retirement){
            if(originalBinding==null)throw new IllegalStateException("retirement original absent");
            boolean workerTerminal;
            if("operator".equals(originalBinding.getString("kind"))){
                originals.requireTerminalUnknown(originalBinding.getString("original_id"));workerTerminal=true;
            }else workerTerminal="incoming_fresh_setup".equals(originalBinding.getString("kind")) && control
                && actual.incomingFreshTaskJoined(originalBinding.getString("native_request_id"));
            out.put("original_worker_terminal",workerTerminal).put("transaction_outcome","unknown");
        }else out.put("classification",never?"never_started":"fully_stopped");
        return out;
    }
    static void requireCurrentOriginalStopActor(EmbeddedDuplexOriginals.Record actual)throws Exception{
        EmbeddedDuplexProcessHost current=instance;
        if(current==null || current.processFence==null || actual.actualAppGeneration!=current.processFence.generation())
            throw new IllegalStateException("desired Stop actual app owner differs");
        current.processFence.requireLive(actual.actualAppGeneration);
        JSONObject actor=actual.row.getJSONObject("actor_binding");
        if(!actor.getString("package_id").equals(current.applicationContext.getPackageName()))throw new IllegalStateException("desired Stop package differs");
        if(!actor.isNull("native_generation") && (actor.getLong("native_generation")!=current.nativeExecutorGeneration
                || !actor.getString("app_record_sha256").equals(current.nativeAppRecordSha256)))
            throw new IllegalStateException("desired Stop native actor differs");
        if(actor.isNull("native_generation") && !"bootstrap_real_peer".equals(actual.row.getString("operation")))
            throw new IllegalStateException("desired Stop native binding absent");
    }
    static String signalOriginalStop(EmbeddedDuplexOriginals.Record actual)throws Exception{
        EmbeddedDuplexProcessHost current=instance;requireCurrentOriginalStopActor(actual);
        if("bootstrap_real_peer".equals(actual.row.getString("operation"))){
            current.phase.compareAndSet(Phase.BOOTSTRAPPING,Phase.CLOSING);
            OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.currentForApplication();if(own!=null)own.requestStopOwn();
        }
        EmbeddedDuplexPlatform held=current.platform;
        return held==null?null:held.signalOriginalStop(actual);
    }
    static boolean originalExchangesJoined(EmbeddedDuplexOriginals.Record actual){
        EmbeddedDuplexProcessHost current=instance;EmbeddedDuplexPlatform held=current==null?null:current.platform;
        return held==null || held.originalExchangesJoined(actual);
    }
    static JSONObject continuationJoinIdentity(EmbeddedDuplexOriginals.Record actual)throws Exception{
        requireCurrentOriginalStopActor(actual);EmbeddedDuplexProcessHost current=instance;
        if(current.phase.get()!=Phase.READY || current.closeInFlight || current.platform==null || current.localFixture)
            throw new IllegalStateException("continuation current app source is closing or unavailable");
        return freshClosureIdentity(current.platform);
    }
    static boolean originalIncomingExchangesJoined(EmbeddedDuplexOriginals.Record actual)throws Exception{
        continuationJoinIdentity(actual);String id=EmbeddedDuplexOriginals.nativeSetupRequestId(actual);
        return id!=null && instance.platform.incomingSetupTasksJoinedFor(id);
    }
    static boolean continuationIngressQuiescent(EmbeddedDuplexOriginals.Record actual)throws Exception{
        continuationJoinIdentity(actual);return instance.platform.incomingAdmissionQuiescent();
    }
    static Context application(){
        EmbeddedDuplexProcessHost current=instance;
        if(current==null)throw new IllegalStateException("app owner absent");return current.applicationContext;
    }
    static void releaseClosedOwnerAfterCustody(Object retiredOwner){
        EmbeddedDuplexProcessHost current=instance;if(current==null)return;
        current.commands.execute(()->{
            try{
                EmbeddedDuplexOriginals originals=EmbeddedDuplexOriginals.forApplication(current.applicationContext);
                if(originals.hasLiveCleanupOwner(retiredOwner))return;
                if(retiredOwner instanceof RetainedNoMediaClose){
                    RetainedNoMediaClose held=(RetainedNoMediaClose)retiredOwner;
                    if(held.proof==null || !held.completion.isDone() || held.completion.isCompletedExceptionally()
                            || held.callbackMarker==null || !held.callbackMarker.isDone()
                            || held.platform!=null && !held.platform.actualOutboundExchangesJoined())return;
                    held.platform=null;held.resources=null;held.own=null;held.stopProjection=null;held.original=null;held.observer=null;held.configurationContext=null;
                }else if(retiredOwner instanceof RetainedWholeClose){
                    RetainedWholeClose held=(RetainedWholeClose)retiredOwner;
                    if(!held.completion.isDone() || held.completion.isCompletedExceptionally()
                            || held.observerMarker==null || !held.observerMarker.isDone()
                            || held.platform!=null && !held.platform.actualOutboundExchangesJoined())return;
                    held.platform=null;held.resources=null;held.original=null;held.observer=null;
                }
                // Only joined, consumed Android owner references are released. No external source/EOF is certified.
            }catch(Exception retained){/* Missing actual custody/join keeps the same owner references. */}
        });
    }
    static void originalCompletion(EmbeddedDuplexOriginals.Record original){
        EmbeddedDuplexProcessHost current=instance;
        if(current==null)return; // Enrollment-only acknowledgement is finalized by its actual service owner below.
        current.commands.execute(()->{
            try{
                if(!original.callbackJoined.isDone() || original.persistenceFailure!=null)return;
                String operation=original.row.getString("operation");
                if(!"succeeded".equals(original.row.getString("state")))return;
                boolean retainedByLiveOwner=current.phase.get()==Phase.READY && current.resources!=null && current.platform!=null
                    && original.row.getJSONObject("actor_binding").optLong("native_generation",-1)==current.nativeExecutorGeneration
                    && java.util.Objects.equals(original.row.getJSONObject("actor_binding").optString("app_record_sha256"),current.nativeAppRecordSha256);
                boolean terminalAppClose=original.retainedCleanupOwner instanceof RetainedNoMediaClose
                    && ((RetainedNoMediaClose)original.retainedCleanupOwner).proof!=null
                    && ((RetainedNoMediaClose)original.retainedCleanupOwner).completion.isDone();
                boolean terminalStartedClose=operation.equals("whole_app_close") && current.lastClosedActorProof!=null
                    && original.row.getJSONObject("actor_binding").optLong("native_generation",-1)==current.lastClosedActorProof.optLong("native_executor_generation",-2)
                    && original.row.getJSONObject("actor_binding").optString("app_record_sha256").equals(current.lastClosedActorProof.optString("app_record_sha256"));
                boolean completedPersistence=operation.equals("confirm") || operation.equals("abandon_retained_scope");
                if(retainedByLiveOwner || terminalAppClose || terminalStartedClose || completedPersistence)
                    EmbeddedDuplexOriginals.forApplication(current.applicationContext).joined(original);
            }catch(Exception retained){/* Persistence/join failure retains the same heavy original; never invents compaction. */}
        });
    }
    static long currentAppGeneration(){
        EmbeddedDuplexProcessHost actual=instance;return actual==null || actual.processFence==null?0L:actual.processFence.generation();
    }
    static JSONObject originalActor() throws Exception {
        EmbeddedDuplexProcessHost current = instance;
        JSONObject actor = new JSONObject().put("package_id", current == null
            ? io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig.APPLICATION_ID
            : current.applicationContext.getPackageName());
        actor.put("app_record_sha256", current == null || current.nativeAppRecordSha256 == null
            ? JSONObject.NULL : current.nativeAppRecordSha256);
        actor.put("native_generation", current == null || current.nativeExecutorGeneration <= 0
            ? JSONObject.NULL : current.nativeExecutorGeneration);
        return actor;
    }

    EmbeddedDuplexDisplaySlot displayAttachment() { return display; }
    boolean ready() { return phase.get() == Phase.READY; }

    long attachDisplay(EmbeddedDuplexDisplay next) { return attachDisplay(next, null); }
    long attachDisplay(EmbeddedDuplexDisplay next,
            java.util.function.Consumer<EmbeddedDuplexWholeCloseRollover> onWholeClose) {
        synchronized (attachmentGate) {
            if (phase.get() != Phase.NEW || attachmentGeneration != 0L || displayDetaching) {
                throw new IllegalStateException("embedded display attachment unavailable");
            }
            attachmentGeneration = display.attach(next);
            wholeCloseActivity = onWholeClose;
            wholeCloseRollover = null; // A successor attachment fences a delayed old Activity callback.
            return attachmentGeneration;
        }
    }

    boolean wholeCloseRolloverCurrent(EmbeddedDuplexWholeCloseRollover proof) {
        synchronized (attachmentGate) {
            if (proof == null || proof != wholeCloseRollover || phase.get() != Phase.NEW
                    || attachmentGeneration != 0L || displayDetaching || platform != null || resources != null
                    || runtimeConfigSha256 != null || nativeExecutorGeneration != 0L || processFence == null) return false;
            try {
                processFence.requireLive(proof.appGeneration);
                return proof.processEpochId.equals(processFence.epochId());
            } catch (Exception changed) { return false; }
        }
    }

    /** Only an uninitialized host has no product effects to clean up. A live
     * host needs typed Stop and platform cleanup before its display can move. */
    void detachUninitializedDisplay(long expectedGeneration) throws Exception {
        synchronized (attachmentGate) {
            if (processFence != null && processFence.recoveryOnly()) {
                throw new IllegalStateException("retained process effects require recovery; display detach is not cleanup");
            }
            if (phase.get() != Phase.NEW || attachmentGeneration != expectedGeneration
                    || expectedGeneration == 0L) {
                throw new IllegalStateException("embedded product cleanup required before display detach");
            }
            displayDetaching = true;
        }
        // The display barrier may wait for callbacks; never hold a host lock.
        display.detachAfterCleanup(expectedGeneration, () -> {});
        synchronized (attachmentGate) {
            attachmentGeneration = 0L;
            displayDetaching = false;
            localFixture = false;
        }
    }

    CompletableFuture<String> initialize(EmbeddedDuplexPackagedInputs.InstalledRole role,
            JSONObject runtimeBindings, JSONObject startup) {
        if (role == null || runtimeBindings == null || startup == null) {
            return failed(new IllegalArgumentException("embedded initialization inputs"));
        }
        synchronized (attachmentGate) {
            if (attachmentGeneration == 0L || displayDetaching || display.cleanupPending()
                    || !phase.compareAndSet(Phase.NEW, Phase.BOOTSTRAPPING)) {
                return failed(new IllegalStateException("embedded process host or display unavailable"));
            }
            pendingReview = null;
            if(noMediaClose!=null && noMediaClose.proof!=null && noMediaClose.completion.isDone())noMediaClose=null;
            if(retainedWholeClose!=null && retainedWholeClose.completion.isDone())retainedWholeClose=null;
            wholeCallbackMarker=null;wholeCallbackPlatform=null;
            terminalWholeReceipt=null;terminalWholeChallenge=null;retainedWholeNativeReceipt=null;
            lastClosedActorProof=null;
        }
        // The caller may mutate its JSON after returning; command lane sees an
        // exact private copy and never retains an Activity, Intent or Bundle.
        String runtimeCopy = runtimeBindings.toString();
        String startupCopy = startup.toString();
        return submit(() -> initializeOnCommandLane(role, runtimeCopy, startupCopy, false, null)).thenCompose(next -> next);
    }

    private CompletableFuture<String> initializeOnCommandLane(EmbeddedDuplexPackagedInputs.InstalledRole role,
            String runtimeCopy, String startupCopy, boolean fixture,
            EmbeddedDuplexBootstrap.Trace trace) throws Exception {
            // Retired owners remain retained by their originals until ACK+joins, never become the next actor's proof.
            if(noMediaClose!=null && noMediaClose.proof!=null && noMediaClose.completion.isDone())noMediaClose=null;
            if(retainedWholeClose!=null && retainedWholeClose.completion.isDone())retainedWholeClose=null;
            wholeCallbackMarker=null;wholeCallbackPlatform=null;terminalWholeReceipt=null;terminalWholeChallenge=null;
            retainedWholeNativeReceipt=null;lastClosedActorProof=null;
            localFixture = fixture;
            EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.PROCESS_FENCE);
            try {
                requireFreshProcess();
                nativeConfigurationContext=null;
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                JSONObject runtime = new JSONObject(runtimeCopy);
                JSONObject enrollment = new JSONObject(startupCopy);
                processFence.beforeRuntimeEffects(checkpointSnapshot(), evidenceSnapshot());
                JSONObject nativeFence = new JSONObject(EmbeddedDuplexNative.claimNativeProcessFence(processFence));
                String recordSha = EmbeddedDuplexProcessFence.digest(processFence.nativeAdmissionRecord());
                if (!"rusty.quest.embedded_duplex.native_fence_admitted.v1".equals(nativeFence.getString("$schema"))
                        || nativeFence.getLong("app_generation") != processFence.generation()
                        || !recordSha.equals(nativeFence.getString("app_record_sha256"))
                        || nativeFence.getLong("executor_generation") <= 0L) {
                    throw new IllegalStateException("native process fence binding differs");
                }
                nativeExecutorGeneration = nativeFence.getLong("executor_generation");
                nativeAppRecordSha256 = recordSha;
                EmbeddedDuplexOriginals.bindActualActor(originalActor());
                EmbeddedDuplexBootstrap.Prepared prepared = EmbeddedDuplexBootstrap.prepare(
                        applicationContext, role, runtime, trace);
                // Preparation stages this exact route in native process state.
                // Retain its digest even if a later callback or JNI step fails.
                runtimeConfigSha256 = prepared.runtimeConfigSha256;
                nativeConfigurationContext=EmbeddedDuplexRuntimeConfigurationBinding.prepare(prepared.runtimeConfigJson,
                    prepared.runtimeConfigSha256,prepared.localPeerId,processFence.generation(),nativeAppRecordSha256,nativeExecutorGeneration);
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.IDENTITY_LOAD);
                EmbeddedDuplexIdentity.Identity identity = EmbeddedDuplexIdentity.loadOrCreate(
                        applicationContext);
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.BOOTSTRAP_BINDINGS);
                EmbeddedDuplexPlatform callbacks = new EmbeddedDuplexPlatform(applicationContext,
                        display, identity, prepared.localPeerId, prepared.remotePeerId,
                        prepared.routeConfigurationSha256, prepared.remoteControlHost,
                        prepared.remoteControlPort, fixture);
                callbacks.bindProcessFence(processFence);
                JSONObject bootstrap = EmbeddedDuplexBootstrap.runtimeBootstrap(prepared,
                        identity.keyId(), enrollment,
                        new JSONObject(callbacks.loadDispatchReplay()),
                        new JSONObject(callbacks.loadActivationReplay()));
                platform = callbacks;
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.NATIVE_INITIALIZE);
                String initialized = EmbeddedDuplexNative.initializeRuntime(prepared.runtimeConfigJson,
                        prepared.runtimeConfigSha256,
                        runtime.getString("validation_epoch_entropy_hex"),
                        bootstrap.toString(), callbacks);
                if (initialized == null || initialized.length() == 0
                        || initialized.length() > 2 * 1024 * 1024) {
                    throw new IllegalStateException("embedded native initialization unavailable");
                }
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.RESOURCE_INSTALL);
                JSONObject nativeResult = new JSONObject(initialized);
                JSONObject ownBootstrap = nativeResult.optJSONObject("own_capture_bootstrap");
                ownFeatureLockSha256 = ownBootstrap == null ? null : ownBootstrap.getString("app_feature_lock_sha256");
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.RESOURCE_IDENTITY);
                if (nativeResult.getLong("executor_generation") != nativeExecutorGeneration
                        || nativeResult.getLong("app_process_generation") != processFence.generation()
                        || !nativeAppRecordSha256.equals(nativeResult.getString("app_record_sha256"))) {
                    throw new IllegalStateException("native initialized process incarnation differs");
                }
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.RESOURCE_CONSTRUCT);
                EmbeddedDuplexResources installed = new EmbeddedDuplexResources(applicationContext,
                        display, new JSONObject(initialized), prepared.maxPairDeltaNs);
                resources = installed;
                final long startupExecutor=nativeExecutorGeneration;
                final long startupAttachment=attachmentGeneration;
                final String startupConfig=runtimeConfigSha256;
                java.util.function.BooleanSupplier startupCurrent=()->phase.get()==Phase.BOOTSTRAPPING
                        && resources==installed && platform==callbacks && nativeExecutorGeneration==startupExecutor
                        && attachmentGeneration==startupAttachment && startupConfig.equals(runtimeConfigSha256);
                EmbeddedDuplexOriginals.Record startupOriginal=EmbeddedDuplexOriginals.capture();
                java.util.concurrent.Executor startupContinuation=action->commands.execute(EmbeddedDuplexOriginals.carryActual(startupOriginal,action));
                return installed.installAsync(trace,startupContinuation,startupCurrent).thenCompose(ignored->submit(()->{
                    requireFreshProcess();
                    if(!startupCurrent.getAsBoolean())throw new IllegalStateException("resource continuation superseded");
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.RESOURCE_REGISTRY);
                callbacks.installResources(installed);
                EmbeddedDuplexBootstrap.mark(trace, EmbeddedDuplexBootstrap.Failure.CONTROL_ENDPOINT);
                callbacks.startControl(prepared.localControlHost, prepared.localControlPort);
                if (!callbacks.controlReady()) {
                    throw new IllegalStateException("authenticated control endpoint unavailable");
                }
                // Supersede retained cleanup facts only after the new authenticated host owns real resources/control.
                ownNoMediaCloseProof = null; ownNoMediaStopTarget = null;
                terminalWholeReceipt = null; terminalWholeChallenge = null; retainedWholeNativeReceipt = null;
                if(noMediaClose!=null){
                    if(noMediaClose.proof==null || !noMediaClose.completion.isDone() || noMediaClose.completion.isCompletedExceptionally())
                        throw new IllegalStateException("prior no-media owner remains unresolved");
                    noMediaClose=null;
                }
                retainedWholeClose = null;
                phase.set(Phase.READY);
                return initialized;
                })).whenComplete((value,failure)->{
                    if(failure!=null)commands.execute(()->{
                        if(phase.get()==Phase.BOOTSTRAPPING)phase.set(Phase.FAILED);
                    });
                });
            } catch (Exception failure) {
                // A prepared native route, even without a completed host, must
                // be closed under its exact digest before another bootstrap.
                phase.set(runtimeConfigSha256 == null && (processFence == null
                        || !processFence.effectsPending()) ? Phase.NEW : Phase.FAILED);
                throw failure;
            }
    }

    CompletableFuture<String> concurrentQualification(boolean arm, String challenge) {
        return concurrentQualificationOnLane(arm, challenge, false, null, null);
    }
    /** Bounded debug-only observation; a current arm is checked twice across lane turns. */
    CompletableFuture<String> ownerFailureDiagnosticRead(String challenge) {
        return submit(() -> {
            if (challenge == null || !challenge.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("diagnostic challenge");
            requireFreshProcess();
            if ((phase.get() != Phase.READY && phase.get() != Phase.FAILED) || localFixture
                    || platform == null || processFence == null) throw new IllegalStateException("owner diagnostic unavailable");
            processFence.requireLive(processFence.generation());
            JSONObject nativeRead = new JSONObject(EmbeddedDuplexNative.ownerFailureDiagnosticRead());
            if (!"rusty.quest.embedded_duplex.owner_diagnostic_native_read.v1".equals(nativeRead.getString("schema"))
                    || nativeRead.getLong("native_executor_generation") != nativeExecutorGeneration
                    || nativeRead.getLong("app_process_generation") != processFence.generation()) {
                throw new IllegalStateException("owner diagnostic lineage changed");
            }
            if (nativeRead.has("initialization_failure")) {
                JSONObject failure = nativeRead.getJSONObject("initialization_failure");
                if (phase.get() != Phase.FAILED
                        || !"rusty.quest.embedded_duplex.initialization_failure.v1".equals(failure.getString("schema"))
                        || failure.getLong("attempt") <= 0
                        || !runtimeConfigSha256.equals(failure.getString("runtime_config_sha256"))
                        || !nativeAppRecordSha256.equals(failure.getString("app_record_sha256"))
                        || failure.getBoolean("host_published") || failure.getBoolean("cleanup_certified")
                        || failure.getBoolean("authority_granted")) {
                    throw new IllegalStateException("initialization diagnostic lineage changed");
                }
            }
            processFence.requireLive(processFence.generation());
            return new JSONObject().put("schema", "rusty.quest.embedded_duplex.owner_failure_diagnostic_read.v1")
                    .put("challenge", challenge).put("process_epoch_id", processFence.epochId())
                    .put("app_generation", processFence.generation()).put("runtime_config_sha256", runtimeConfigSha256)
                    .put("feature_lock_sha256", ownFeatureLockSha256).put("qualification_claimed", false)
                    .put("native_observation", nativeRead).toString();
        });
    }
    CompletableFuture<String> ownCaptureDiagnostic(String challenge) {
        return captureDiagnostic(challenge, false);
    }
    CompletableFuture<String> streamDropoutDiagnostic(String challenge) {
        return captureDiagnostic(challenge, true);
    }
    private CompletableFuture<String> captureDiagnostic(String challenge, boolean dropoutOnly) {
        return concurrentQualificationOnLane(false, challenge, false, null, null)
                .thenCompose(firstReceipt -> submit(() -> {
                    requireFreshProcess();
                    Phase currentPhase = phase.get();
                    EmbeddedDuplexResources retained = resources;
                    if ((currentPhase != Phase.READY && currentPhase != Phase.FAILED)
                            || localFixture || retained == null || !retained.ownAppCaptureEnabled()) {
                        throw new IllegalStateException("Own capture diagnostic unavailable");
                    }
                    processFence.requireLive(processFence.generation());
                    JSONObject first = new JSONObject(firstReceipt);
                    String epoch = processFence.epochId();
                    if (!challenge.equals(first.getString("challenge"))
                            || !epoch.equals(first.getString("process_epoch_id"))
                            || !runtimeConfigSha256.equals(first.getString("runtime_config_sha256"))
                            || !ownFeatureLockSha256.equals(first.getString("feature_lock_sha256"))) {
                        throw new IllegalStateException("Own capture diagnostic lineage changed");
                    }
                    JSONObject qualification = new JSONObject(ConcurrentStereoQualification.status(
                            challenge, epoch, runtimeConfigSha256, ownFeatureLockSha256,
                            first.getString("apk_sha256")));
                    if (qualification.getLong("arm_generation") != first.getLong("arm_generation")
                            || qualification.getLong("native_process_generation")
                            != first.getLong("native_process_generation")) {
                        throw new IllegalStateException("Own capture diagnostic native process or arm changed");
                    }
                    long sampleStartNs = android.os.SystemClock.elapsedRealtimeNanos();
                    JSONObject armContext = new JSONObject(ConcurrentStereoQualification.diagnosticArmContext(challenge, epoch));
                    if (dropoutOnly) {
                        JSONObject nativeObservation = new JSONObject(ConcurrentStereoQualification.dropoutObservation(challenge, epoch));
                        JSONObject javaObservation = retained.sourceDropoutSnapshot();
                        EmbeddedDuplexDropoutLineage.requireCurrent(javaObservation, epoch,
                                processFence.generation(), qualification.getLong("arm_generation"));
                        JSONObject report = new JSONObject()
                                .put("schema", "rusty.quest.embedded_duplex.stream_dropout_diagnostic.v1")
                                .put("qualification_claimed", false)
                                .put("challenge", challenge).put("process_epoch_id", epoch)
                                .put("app_generation", processFence.generation())
                                .put("arm_generation", qualification.getLong("arm_generation"))
                                .put("consistency", "sequential_non_atomic_observation")
                                .put("sample_start_elapsed_ns", sampleStartNs)
                                .put("sample_end_elapsed_ns", android.os.SystemClock.elapsedRealtimeNanos())
                                .put("qualification", qualification)
                                .put("native_dropout_observation", nativeObservation)
                                .put("java_dropout_observation", javaObservation)
                                .put("receiver_stage_observation", retained.receiverStageSnapshot());
                        String exact = report.toString();
                        if (exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024) {
                            throw new IllegalStateException("Stream dropout diagnostic bounds");
                        }
                        return exact;
                    }
                    JSONObject source = retained.sourceSnapshot();
                    long sampleEndNs = android.os.SystemClock.elapsedRealtimeNanos();
                    if (!source.getBoolean("shared_app_capture")) {
                        throw new IllegalStateException("Own capture diagnostic source differs");
                    }
                    JSONObject report = new JSONObject()
                            .put("schema", "rusty.quest.embedded_duplex.own_capture_diagnostic.v1")
                            .put("challenge", challenge)
                            .put("process_epoch_id", epoch)
                            .put("app_generation", processFence.generation())
                            .put("arm_generation", qualification.getLong("arm_generation"))
                            .put("arm_entry_elapsed_ns", armContext.getLong("arm_entry_elapsed_ns"))
                            .put("arm_exit_elapsed_ns", armContext.getLong("arm_exit_elapsed_ns"))
                            .put("consistency", "sequential_non_atomic_observation")
                            .put("java_clock", "android_elapsedRealtimeNanos")
                            .put("native_clock", "CLOCK_MONOTONIC")
                            .put("sample_start_elapsed_ns", sampleStartNs)
                            .put("sample_end_elapsed_ns", sampleEndNs)
                            .put("sample_wall_ms", System.currentTimeMillis())
                            .put("qualification", qualification)
                            .put("own_capture_stage", source.getJSONObject("own_capture_stage_diagnostic"))
                            .put("codec_output_with_pair", source.getJSONObject("codec_output_with_pair"))
                            .put("encoded_frames", source.getLong("encoded_frames"))
                            .put("video_packet_count", source.getLong("video_packet_count"))
                            .put("last_packet_age_ms", source.getLong("last_packet_age_ms"));
                    String exact = report.toString();
                    if (exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024) {
                        throw new IllegalStateException("Own capture diagnostic bounds");
                    }
                    return exact;
                }));
    }
    private void requireCurrentHubAttachment() {
        synchronized (attachmentGate) {
            if (phase.get()!=Phase.READY || localFixture || attachmentGeneration==0L || displayDetaching || closeInFlight || display.cleanupPending())
                throw new IllegalStateException("current duplex display unavailable");
        }
    }
    CompletableFuture<String> concurrentHubObservation() {
        return submit(() -> {
            requireFreshProcess();
            if (phase.get()!=Phase.READY || localFixture || processFence==null || runtimeConfigSha256==null || ownFeatureLockSha256==null)
                throw new IllegalStateException("current duplex surface unavailable");
            processFence.requireLive(processFence.generation());
            requireCurrentHubAttachment();
            String life=EmbeddedDuplexNative.peerLifecycle(EmbeddedDuplexPeerAction.STATUS.word);
            JSONObject identity=new JSONObject(life);
            if(identity.getLong("native_executor_generation")!=nativeExecutorGeneration || identity.getLong("app_process_generation")!=processFence.generation())
                throw new IllegalStateException("current duplex owner generation differs");
            EmbeddedDuplexPairStatus pair=EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand("pair_status","{}"));
            return ConcurrentStereoQualification.hubSnapshot(processFence.epochId(),runtimeConfigSha256,ownFeatureLockSha256,life,pair);
        });
    }
    CompletableFuture<String> concurrentHubPolicy(long[] policy, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        return submit(() -> {
            requireFreshProcess();
            if(phase.get()!=Phase.READY || localFixture || processFence==null)throw new IllegalStateException("current duplex surface unavailable");
            return ConcurrentStereoQualification.currentHubChallenge(processFence.epochId(),runtimeConfigSha256,ownFeatureLockSha256);
        }).thenCompose(challenge -> concurrentQualificationOnLane(false,challenge,true,policy.clone(),null,false,true,cancelled));
    }

    CompletableFuture<String> concurrentPolicy(String challenge, long[] policy) {
        return concurrentQualificationOnLane(false, challenge, true, policy == null ? null : policy.clone(), null);
    }
    CompletableFuture<String> concurrentMaskConfiguration(String challenge) {
        return submit(() -> {
            requireFreshProcess();
            if (localFixture || processFence == null || runtimeConfigSha256 == null || ownFeatureLockSha256 == null
                    || nativeExecutorGeneration <= 0L || attachmentGeneration <= 0L)
                throw new IllegalStateException("mask configuration actor context unavailable");
            String result = ConcurrentStereoQualification.maskConfiguration(challenge, processFence.epochId(),
                    runtimeConfigSha256, ownFeatureLockSha256, installedApkDigest.read(applicationContext));
            requireFreshProcess();
            return result;
        });
    }
    CompletableFuture<String> concurrentMask(String challenge, long[] mask) {
        return concurrentQualificationOnLane(false,challenge,true,mask == null ? null : mask.clone(),null,true);
    }
    CompletableFuture<String> peerLifecycle(EmbeddedDuplexPeerAction action, String challenge) {
        if (action == null) return failed(new IllegalArgumentException("peer action required"));
        if(action!=EmbeddedDuplexPeerAction.STATUS && EmbeddedDuplexOriginals.capture()==null)return appOriginal(action.action,challenge,
            ()->peerLifecycle(action,challenge),receipt->CompletableFuture.completedFuture(EmbeddedDuplexOperatorResponses.receipt(challenge,receipt)));
        return peerLifecycleEntered(action,challenge,false);
    }
    private CompletableFuture<String> peerLifecycleEntered(EmbeddedDuplexPeerAction action,String challenge,boolean wholeClassified){
        RetainedNoMediaClose noMedia=noMediaClose;
        if(action==EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && noMedia==null && terminalWholeReceipt!=null){
            if(!challenge.equals(terminalWholeChallenge))return failed(new IllegalStateException("closed challenge differs"));
            try{processFence.requireLive(processFence.generation());return CompletableFuture.completedFuture(terminalWholeReceipt);}
            catch(Exception failure){return failed(failure);}
        }
        if(action==EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && noMedia==null && retainedWholeClose!=null){
            RetainedWholeClose actual=retainedWholeClose;
            if(!challenge.equals(actual.challenge))return failed(new IllegalStateException("retained whole-close challenge differs"));
            return actual.completion;
        }
        if(action==EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && noMedia==null && !wholeClassified){
            final long actualAttachment;
            synchronized(attachmentGate){
                if(localFixture || attachmentGeneration<=0 || displayDetaching || closeInFlight)
                    return failed(new IllegalStateException("whole close actual app owner unavailable"));
                actualAttachment=attachmentGeneration;
            }
            // Explicit trusted whole-close intent stops only the actual held original/Own producer;
            // it need not renew a media pair or fabricate an ARM solely to retire no-media resources.
            try{if(processFence!=null)processFence.requireLive(processFence.generation());requestDesiredStopOutsideCommandLane(true);}
            catch(Exception failure){return failed(failure);}
            return submit(()->{
                synchronized(attachmentGate){if(attachmentGeneration!=actualAttachment || localFixture)
                    throw new IllegalStateException("whole close attachment superseded");}
                JSONObject classifier=new JSONObject(EmbeddedDuplexNative.noMediaRetirementStatus());
                if(!"rusty.quest.embedded_duplex.no_media_retirement_status.v1".equals(classifier.getString("$schema")))
                    throw new IllegalStateException("whole close native classifier schema");
                return classifier.getString("classification");
            }).thenCompose(classification->{
                if(java.util.Arrays.asList("busy","pending_fresh_setup","configured_no_media_candidate","staged_no_media_candidate",
                        "configured_closed","never_claimed","uninitialized_capability","partial_construction_candidate","pending_peer_root_reestablishment","pending_prepared_peer_start").contains(classification)){
                    EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
                    return closeNoMediaAndDetach(actualAttachment).thenApply(closed->{
                        RetainedNoMediaClose held=noMediaClose;
                        if(held==null || held.proof==null || !held.completion.isDone())throw new IllegalStateException("actual no-media whole proof absent");
                        if(actual!=null)actual.retainedCleanupOwner=held;
                        return held.proof.toString();
                    });
                }
                if("media_owned_or_uncertain".equals(classification))return peerLifecycleEntered(action,challenge,true);
                return failed(new IllegalStateException("whole close native disposition refuses: "+classification));
            });
        }
        if((action==EmbeddedDuplexPeerAction.STOP || action==EmbeddedDuplexPeerAction.REVOKE || action==EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE)
                && noMedia==null){
            try{
                if(processFence==null || !ConcurrentStereoQualification.currentArmActor(processFence.epochId(),challenge,runtimeConfigSha256,nativeExecutorGeneration))
                    return failed(new IllegalStateException("desired Stop current ARM actor differs"));
                processFence.requireLive(processFence.generation());
                requestDesiredStopOutsideCommandLane(action==EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE);
            }catch(Exception failure){return failed(failure);}
        }
        if(action==EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && noMedia!=null){
            EmbeddedDuplexOriginals.Record original=EmbeddedDuplexOriginals.capture();
            if(original!=null)original.retainedCleanupOwner=noMedia;
            return noMedia.completion.thenApply(closed->{try{
                return new JSONObject(noMedia.proof.toString()).toString();
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}});
        }
        CompletableFuture<String> observed = concurrentQualificationOnLane(false, challenge, false, null, action);
        if (action != EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE) return observed;
        return observed.thenCompose(receipt -> {
            RetainedWholeClose held = retainedWholeClose;
            return held != null && held.challenge.equals(challenge)
                    ? held.completion : CompletableFuture.completedFuture(receipt);
        });
    }
    private void retainWholeClose(String challenge, String epoch, String apk, String receipt) {
        OwnStereoCaptureRuntime own = OwnStereoCaptureRuntime.currentForApplication();
        if (own == null) return; // Missing physical owner is unknown, never completion.
        final RetainedWholeClose held = new RetainedWholeClose(challenge, epoch, runtimeConfigSha256,
                ownFeatureLockSha256, apk, receipt, processFence.generation(), nativeExecutorGeneration,
                attachmentGeneration, resources, platform);
        retainedWholeClose = held;
        phase.set(Phase.CLOSING);
        try { held.observer = own.observePhysicalCleanup(new Runnable() {
            @Override public void run() {
                if (held.completion.isDone() || !held.queued.compareAndSet(false, true)) return;
                try {
                    commands.execute(new Runnable() {
                        @Override public void run() {
                            try { continueWholeClose(held); }
                            catch (Throwable failure) { held.completion.completeExceptionally(failure); }
                            finally {
                                held.queued.set(false);
                                if (held.completion.isDone()) releaseWholeCloseObserver(held);
                            }
                        }
                    });
                } catch (RuntimeException rejected) {
                    held.queued.set(false);
                    held.completion.completeExceptionally(rejected);
                    releaseWholeCloseObserver(held);
                }
            }
        }); } catch (RuntimeException failure) {
            held.completion.completeExceptionally(failure);
            throw failure;
        }
    }
    private void releaseWholeCloseObserver(RetainedWholeClose held) {
        AutoCloseable observer = held.observer;
        if (observer != null) {
            try { observer.close(); }
            catch (Exception ignored) { /* A done future fences any stale observer callback. */ }
        }
    }
    private void continueWholeClose(RetainedWholeClose held) throws Exception {
        if (held.completion.isDone()) return;
        requireFreshProcess();
        if (retainedWholeClose != held || phase.get() != Phase.CLOSING
                || processFence.generation() != held.appGeneration || !processFence.epochId().equals(held.epoch)
                || !held.config.equals(runtimeConfigSha256) || !held.feature.equals(ownFeatureLockSha256)
                || nativeExecutorGeneration != held.executorGeneration || attachmentGeneration != held.attachment
                || resources != held.resources || platform != held.platform
                || !held.apk.equals(installedApkDigest.read(applicationContext)))
            throw new IllegalStateException("retained whole-close owner changed");
        processFence.requireLive(held.appGeneration);
        OwnStereoCaptureRuntime own = OwnStereoCaptureRuntime.currentForApplication();
        if (own == null || !"terminal".equals(own.physicalCleanupState())) return;
        // Same arm is checked before native retirement. No Stop, route or projection action is repeated.
        ConcurrentStereoQualification.status(held.challenge, held.epoch, held.config, held.feature, held.apk);
        String nativeReceipt = retainedWholeNativeReceipt != null ? retainedWholeNativeReceipt
                : inCurrentWholeNativeClose(()->EmbeddedDuplexNative.completeRetainedWholeClose(held.config, held.executorGeneration, held.appGeneration));
        if (nativeReceipt == null) return;
        JSONObject physical = new JSONObject(nativeReceipt);
        if (!"terminal".equals(physical.optString("native_host_physical_cleanup"))) return;
        retainedWholeNativeReceipt = nativeReceipt;
        physical.put("java_own_capture_cleanup", own.physicalCleanupState());
        finishWholeAppProof(physical, held.config, false);
        if (!"terminal".equals(physical.optString("whole_app_physical_cleanup"))) return;
        String completed = ConcurrentStereoQualification.lifecycle("whole_app_close", held.challenge,
                held.epoch, held.config, held.feature, held.apk, physical.toString());
        terminalWholeReceipt = completed; terminalWholeChallenge = held.challenge;
        held.completion.complete(completed);
    }
    private boolean joinWholeCloseObserver(RetainedWholeClose held, OwnStereoCaptureRuntime own) {
        if(held.observerMarker==null){
            held.observerMarker=(held.observer==null?CompletableFuture.<Void>completedFuture(null)
                :own.unregisterPhysicalCleanup(held.observer)).thenRunAsync(()->{},commands);
            held.observerMarker.whenComplete((done,error)->{
                try { commands.execute(()->{
                    try(AutoCloseable ignored=EmbeddedDuplexOriginals.enter(held.original)){continueWholeClose(held);}
                    catch(Throwable failure){held.completion.completeExceptionally(failure);}
                }); } catch(Throwable failure) { held.completion.completeExceptionally(failure); }
            });
        }
        if(!held.observerMarker.isDone())return false;
        held.observerMarker.join();
        return true;
    }
    private CompletableFuture<String> concurrentQualificationOnLane(boolean arm, String challenge, boolean policyAction, long[] policy, EmbeddedDuplexPeerAction peerAction) {
        return concurrentQualificationOnLane(arm,challenge,policyAction,policy,peerAction,false);
    }
    private CompletableFuture<String> concurrentQualificationOnLane(boolean arm, String challenge, boolean policyAction, long[] policy, EmbeddedDuplexPeerAction peerAction, boolean maskAction) {
        return concurrentQualificationOnLane(arm,challenge,policyAction,policy,peerAction,maskAction,false,null);
    }
    private CompletableFuture<String> concurrentQualificationOnLane(boolean arm, String challenge, boolean policyAction, long[] policy, EmbeddedDuplexPeerAction peerAction, boolean maskAction, boolean hubAction, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        return submit(() -> {
            requireFreshProcess();
            if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && terminalWholeReceipt != null) {
                if (!challenge.equals(terminalWholeChallenge)) throw new IllegalStateException("closed challenge differs");
                processFence.requireLive(processFence.generation());
                return terminalWholeReceipt;
            }
            if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && retainedWholeClose != null) {
                if (!retainedWholeClose.challenge.equals(challenge)) throw new IllegalStateException("retained whole-close challenge differs");
                return retainedWholeClose.initialReceipt;
            }
            Phase observedPhase = phase.get();
            boolean cleanupObservation = !policyAction && (peerAction == null
                    || peerAction == EmbeddedDuplexPeerAction.STOP
                    || peerAction == EmbeddedDuplexPeerAction.REVOKE
                    || peerAction == EmbeddedDuplexPeerAction.STATUS
                    || peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE);
            boolean noMediaFallback = observedPhase == Phase.NEW && runtimeConfigSha256 == null
                    && nativeExecutorGeneration == 0L && ownNoMediaCloseProof != null
                    && ownNoMediaAppGeneration == processFence.generation();
            boolean fallbackAction = peerAction == null || peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE;
            boolean phaseAllowed = noMediaFallback && cleanupObservation && fallbackAction
                    || observedPhase == Phase.READY || cleanupObservation
                    && (observedPhase == Phase.FAILED || observedPhase == Phase.CLOSING);
            if (!phaseAllowed || localFixture || processFence == null
                    || (noMediaFallback ? ownNoMediaConfigSha256 == null || ownNoMediaFeatureSha256 == null
                        : runtimeConfigSha256 == null || ownFeatureLockSha256 == null)) {
                throw new IllegalStateException("selected process observation unavailable");
            }
            processFence.requireLive(processFence.generation());
            String epoch = processFence.epochId();
            final String receiptConfig = noMediaFallback ? ownNoMediaConfigSha256 : runtimeConfigSha256;
            final String receiptFeature = noMediaFallback ? ownNoMediaFeatureSha256 : ownFeatureLockSha256;
            String apk = installedApkDigest.read(applicationContext);
            if (peerAction != null) {
                // Check the retained process/challenge/arm before any authority mutation.
                ConcurrentStereoQualification.status(challenge, epoch, receiptConfig,
                        receiptFeature, apk);
                EmbeddedDuplexStartPreflight starting = startIntent.pending();
                if (peerAction == EmbeddedDuplexPeerAction.START && !preflightLive(starting))
                    throw new IllegalStateException("current paired Start intent unavailable");
                if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE) {
                    OwnStereoCaptureRuntime own = OwnStereoCaptureRuntime.currentForApplication();
                    if (noMediaFallback) {
                        if (!ownNoMediaCleanupAttempted) {
                            if (processFence.effectsPending()) throw new IllegalStateException("another durable effect is Pending");
                            processFence.beforeRuntimeEffects(checkpointSnapshot(), evidenceSnapshot());
                            ownNoMediaCleanupAttempted = true;
                        }
                        if (ownNoMediaStopTarget == null) throw new IllegalStateException("retained Own stop target unavailable");
                        if (own != null) own.requestStopOwn();
                        ownNoMediaStopTarget.run();
                    } else {
                        if (own != null) own.requestStopOwn();
                        display.requestWholeProjectionStop();
                        EmbeddedDuplexResources retained = resources;
                        if (retained != null) retained.closeUnusedIncomingForWholeProduct();
                    }
                }
                if (peerAction == EmbeddedDuplexPeerAction.START) startIntent.dispatch(starting);
                EmbeddedDuplexResources observedReceiverResources = peerAction == EmbeddedDuplexPeerAction.STATUS ? resources : null;
                JSONObject receiverBefore = receiverPhysicalObservation(observedReceiverResources);
                String nativeReceipt = noMediaFallback ? noMediaWholeProof(receiptConfig)
                        : retainedWholeNativeReceipt != null && peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE
                            ? retainedWholeNativeReceipt : peerAction==EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE
                                ?inCurrentWholeNativeClose(()->EmbeddedDuplexNative.peerLifecycle(peerAction.word)):EmbeddedDuplexNative.peerLifecycle(peerAction.word);
                if (peerAction == EmbeddedDuplexPeerAction.STATUS && observedReceiverResources != null) {
                    JSONObject firstStatus = new JSONObject(nativeReceipt);
                    JSONObject candidate = firstStatus.optJSONObject("remote_receiver_observation");
                    if (candidate != null && candidate.has("signed_history")) {
                        try {
                            JSONObject receiverMiddle = receiverPhysicalObservation(observedReceiverResources);
                            String secondStatus = EmbeddedDuplexNative.peerLifecycle(EmbeddedDuplexPeerAction.STATUS.word);
                            JSONObject receiverAfter = receiverPhysicalObservation(observedReceiverResources);
                            nativeReceipt = composeRemoteReceiverObservation(firstStatus, new JSONObject(secondStatus),
                                    receiverBefore, receiverMiddle, receiverAfter, observedReceiverResources == resources,
                                    nativeExecutorGeneration, processFence.generation(), receiptConfig).toString();
                        } catch (Exception unavailable) {
                            firstStatus.put("remote_receiver_observation", pendingReceiverObservation());
                            nativeReceipt = firstStatus.toString();
                        }
                    }
                    JSONObject boundedStatus = new JSONObject(nativeReceipt);
                    if (boundedStatus.toString().length() > 256 * 1024) {
                        boundedStatus.remove("remote_receiver_observation");
                        nativeReceipt = boundedStatus.toString();
                    }
                }
                if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE) {
                    OwnStereoCaptureRuntime own = OwnStereoCaptureRuntime.currentForApplication();
                    JSONObject physical = new JSONObject(nativeReceipt);
                    physical.put("java_own_capture_cleanup", own == null ? "unknown" : own.physicalCleanupState());
                    if ("terminal".equals(physical.optString("native_host_physical_cleanup")))
                        retainedWholeNativeReceipt = nativeReceipt;
                    finishWholeAppProof(physical, receiptConfig, noMediaFallback);

                    nativeReceipt = physical.toString();
                }
                String receipt = ConcurrentStereoQualification.lifecycle(peerAction.action, challenge, epoch, receiptConfig, receiptFeature, apk, nativeReceipt);
                if (peerAction == EmbeddedDuplexPeerAction.START) startIntent.acknowledge(starting, nativeReceipt);
                if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE
                        && "terminal".equals(new JSONObject(nativeReceipt).optString("whole_app_physical_cleanup"))) {
                    terminalWholeReceipt = receipt; terminalWholeChallenge = challenge;
                }
                if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && !noMediaFallback
                        && "terminal".equals(new JSONObject(nativeReceipt).optString("peer_physical_cleanup"))
                        && new JSONObject(nativeReceipt).isNull("last_failure")
                        && !"terminal".equals(new JSONObject(nativeReceipt).optString("whole_app_physical_cleanup"))) {
                    retainWholeClose(challenge, epoch, apk, receipt);
                }
                return receipt;
            }
            if (hubAction) {
                if (cancelled == null || cancelled.get()) throw new IllegalStateException("current duplex command cancelled");
                requireCurrentHubAttachment();
                if (io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoBankControls.INSTANCE.maskSnapshot()[3] != 0L)
                    throw new IllegalStateException("current duplex source command unavailable while mask enabled");
                String life=EmbeddedDuplexNative.peerLifecycle(EmbeddedDuplexPeerAction.STATUS.word);
                JSONObject owner=new JSONObject(life);
                if(owner.getLong("native_executor_generation")!=nativeExecutorGeneration || owner.getLong("app_process_generation")!=processFence.generation())
                    throw new IllegalStateException("current duplex owner generation differs");
                EmbeddedDuplexPairStatus pair=EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand("pair_status","{}"));
                ConcurrentStereoQualification.hubSnapshot(epoch,receiptConfig,receiptFeature,life,pair);
            }
            if (maskAction) return ConcurrentStereoQualification.mask(challenge,epoch,receiptConfig,receiptFeature,apk,policy);
            if (policyAction) return ConcurrentStereoQualification.policy(challenge, epoch, receiptConfig, receiptFeature, apk, policy);
            if (arm) {
                String receipt = ConcurrentStereoQualification.arm(challenge, epoch, receiptConfig, receiptFeature, apk);
                ConcurrentStereoQualification.bindCurrentArmActor(epoch,challenge,receiptConfig,nativeExecutorGeneration);
                EmbeddedDuplexResources retained = resources;
                if (retained != null && retained.ownAppCaptureEnabled()) {
                    JSONObject context = new JSONObject(ConcurrentStereoQualification.diagnosticArmContext(challenge, epoch));
                    retained.armOwnCaptureTrace(epoch, processFence.generation(), context.getLong("arm_generation"));
                }
                return receipt;
            }
            return ConcurrentStereoQualification.status(challenge, epoch, receiptConfig, receiptFeature, apk);
        });
    }

    CompletableFuture<String> selectLocalAfterTerminal(
            io.github.mesmerprism.rustyquest.spatial_camera_panel.LocalRollbackRequestFence fence,
            io.github.mesmerprism.rustyquest.spatial_camera_panel.LocalRollbackRequestFence.Ticket ticket,
            java.util.function.LongSupplier currentRouteGeneration,
            java.util.function.BooleanSupplier ownerAlive) {
        return submit(() -> {
            if (phase.get()!=Phase.NEW || terminalWholeReceipt==null || closeInFlight
                    || platform!=null || resources!=null || runtimeConfigSha256!=null)
                throw new IllegalStateException("verified whole-app cleanup required before Local");
            JSONObject proof=new JSONObject(terminalWholeReceipt).getJSONObject("peer_lifecycle");
            if (!"terminal".equals(proof.optString("whole_app_physical_cleanup")))
                throw new IllegalStateException("whole-app cleanup remains Pending");
            OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.currentForApplication();
            if (own==null || !"terminal".equals(own.physicalCleanupState()))
                throw new IllegalStateException("Own physical cleanup remains Pending");
            String expectedConfig=proof.getString("config_sha256");
            String nativeReceipt=fence.commitIfCurrent(ticket,currentRouteGeneration,ownerAlive,
                    () -> EmbeddedDuplexNative.selectLocalAfterTerminal(expectedConfig));
            JSONObject selected=new JSONObject(nativeReceipt);
            if (!"rusty.quest.local_rollback_native.v1".equals(selected.getString("schema"))
                    || !expectedConfig.equals(selected.getString("config_sha256"))
                    || selected.getBoolean("feature_enabled")
                    || !"terminal".equals(selected.getString("physical_cleanup"))
                    || !"route-selection-only".equals(selected.getString("scope")))
                throw new IllegalStateException("native Local feature-off proof differs");
            return selected.put("app_verified_config_sha256",expectedConfig).toString();
        });
    }

    CompletableFuture<Boolean> resumeOwnProjection(long expectedGeneration,
            java.util.function.BooleanSupplier ownerAlive) {
        return submit(() -> {
            requireFreshProcess();
            synchronized (attachmentGate) {
                if (expectedGeneration<=0L || attachmentGeneration!=expectedGeneration || displayDetaching
                        || closeInFlight || phase.get()!=Phase.READY || localFixture || resources==null
                        || runtimeConfigSha256==null || ownerAlive==null || !ownerAlive.getAsBoolean()) return false;
            }
            OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.currentForApplication();
            if (own==null || own.phase()!=OwnStereoCaptureRuntime.Phase.Live || own.retainedCapture()==null) return false;
            display.resumeOwnProjection(ownerAlive);
            return true; // Native owner acceptance only; moving pixels remain separately observed.
        });
    }

    CompletableFuture<String> command(String operation, String exactInputJson) {
        if (operation == null || exactInputJson == null || operation.length() > 64
                || exactInputJson.length() > 2 * 1024 * 1024) {
            return failed(new IllegalArgumentException("embedded command bounds"));
        }
        return submit(() -> {
            requireFreshProcess();
            if (phase.get() != Phase.READY || localFixture) {
                throw new IllegalStateException("embedded process host not ready");
            }
            return EmbeddedDuplexNative.runtimeCommand(operation, exactInputJson);
        });
    }

    /** Real-peer bootstrap stops before admission or any media owner effect. */
    CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeer(long expectedGeneration) {
        if(EmbeddedDuplexOriginals.capture()==null){String challenge=internalChallenge();return appOriginal("bootstrap_real_peer",challenge,
            ()->bootstrapRealPeer(expectedGeneration),actual->CompletableFuture.completedFuture(EmbeddedDuplexOperatorResponses.runtimeBundle(challenge,actual)));}
        synchronized (attachmentGate) {
            if (expectedGeneration == 0L || attachmentGeneration != expectedGeneration
                    || displayDetaching || display.cleanupPending()
                    || !phase.compareAndSet(Phase.NEW, Phase.BOOTSTRAPPING)) {
                return failed(new IllegalStateException("real-peer display unavailable"));
            }
            pendingReview = null;
        }
        EmbeddedDuplexBootstrap.Trace trace = new EmbeddedDuplexBootstrap.Trace();
        return submit(() -> {
            lastBootstrapFailure = null;
            trace.mark(EmbeddedDuplexBootstrap.Failure.PROCESS_FENCE);
            try {
                requireFreshProcess();
                nativeConfigurationContext=null;
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                trace.mark(EmbeddedDuplexBootstrap.Failure.ENROLLMENT_RESOLVE);
                EmbeddedDuplexEnrollment enrollment =
                        EmbeddedDuplexEnrollmentResolver.resolve(applicationContext);
                trace.mark(EmbeddedDuplexBootstrap.Failure.SESSION_INPUTS);
                EmbeddedDuplexSessionInputs inputs =
                        EmbeddedDuplexSessionInputs.createRealPeer(enrollment);
                setupCredentials = inputs.setupCredentials;
                enrollmentRecordSha256 = enrollment.recordSha256;
                return initializeOnCommandLane(inputs.role, inputs.runtimeBindings.toString(),
                        inputs.startup.toString(), false, trace).thenCompose(ignored->submit(this::runtimeStatusOnCommandLane));
            } catch (Exception failure) {
                trace.captureFailure(failure);
                lastBootstrapFailure = trace.failure();
                if (phase.get() == Phase.BOOTSTRAPPING) {
                    phase.set(runtimeConfigSha256 == null && (processFence == null
                        || !processFence.effectsPending()) ? Phase.NEW : Phase.FAILED);
                }
                throw failure;
            }
        }).thenCompose(next->next).whenComplete((value,failure)->{
            if(failure!=null){trace.captureFailure(failure);lastBootstrapFailure=trace.failure();}
        });
    }

    CompletableFuture<String> rendererTerminalStatus(String challenge) {
        return submit(() -> {
            requireFreshProcess();
            if (localFixture || runtimeConfigSha256 == null || nativeExecutorGeneration <= 0L || attachmentGeneration <= 0L)
                throw new IllegalStateException("renderer diagnostic runtime context unavailable");
            String result = StereoRendererTerminalStatus.read(challenge, processFence.epochId(), processFence.generation(),
                    nativeExecutorGeneration, attachmentGeneration, runtimeConfigSha256);
            requireFreshProcess();
            return result;
        });
    }

    EmbeddedDuplexRuntimeStatus closedNoMediaStatus(){
        RetainedNoMediaClose held=noMediaClose;
        if(held==null || held.proof==null || held.closedStatus==null || !held.completion.isDone())
            throw new IllegalStateException("actual no-media close result unavailable");
        return held.closedStatus;
    }
    private <T> CompletableFuture<T> appOriginal(String operation,String challenge,
            java.util.function.Supplier<CompletableFuture<T>> factory,java.util.function.Function<T,CompletableFuture<android.os.Bundle>> projection){
        try{return EmbeddedDuplexOriginals.forApplication(applicationContext).beginInternal(operation,challenge,
            EmbeddedDuplexOriginals.request(operation,challenge,new android.os.Bundle()),factory,projection);}
        catch(Exception failure){return failed(failure);}
    }
    private static String internalChallenge(){return java.util.UUID.randomUUID().toString().replace("-","");}
    CompletableFuture<EmbeddedDuplexRuntimeStatus> runtimeStatus() {
        return submit(this::runtimeStatusOnCommandLane).handle((status, failure) ->
                failure == null ? status : new EmbeddedDuplexRuntimeStatus("cleanup_pending",
                        attachmentGeneration != 0L, runtimeConfigSha256, enrollmentRecordSha256));
    }

    static EmbeddedDuplexProcessHost existingForRecovery(Context context){
        EmbeddedDuplexProcessHost current=instance;
        if(current==null||current.applicationContext!=context.getApplicationContext())
            throw new IllegalStateException("no actual entered app Host for setup recovery");
        return current;
    }
    CompletableFuture<String> completedSetupRecoveryStatus(String challenge){
        return submit(()->{
            if(phase.get()!=Phase.READY||platform==null||resources==null)throw new IllegalStateException("completed setup host not Ready");
            JSONObject identity=freshClosureIdentity(platform);
            String pairRaw=EmbeddedDuplexNative.runtimeCommand("pair_status","{}");
            EmbeddedDuplexPairStatus pair=EmbeddedDuplexPairStatus.parse(pairRaw);
            if(!pair.selectedFreshPolicy||pair.freshSetupObservation==null
                ||!"completed".equals(pair.freshSetupObservation.getString("state")))
                throw new IllegalStateException("actual completed fresh setup absent");
            String nativeId=pair.freshSetupObservation.getString("request_id");
            String noMediaRaw=EmbeddedDuplexNative.noMediaRetirementStatus();JSONObject noMedia=new JSONObject(noMediaRaw);
            if(!"rusty.quest.embedded_duplex.no_media_retirement_status.v1".equals(noMedia.getString("$schema"))
                ||!"configured_no_media_candidate".equals(noMedia.getString("classification"))
                ||noMedia.getBoolean("native_initializing")||noMedia.getBoolean("native_closing")
                ||noMedia.getLong("native_host_leases")!=0||!noMedia.isNull("fresh_setup_request_id")
                ||noMedia.getBoolean("physical_cleanup_claimed"))throw new IllegalStateException("actual Native no-Start boundary absent");
            for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
                if(!EmbeddedDuplexCanonicalJson.sameValue(identity.get(key),noMedia.get(key)))throw new IllegalStateException("completed setup Native actor/config changed");
            JSONObject physical=freshPhysical(platform,null,false);
            if(!physical.getBoolean("eligible"))throw new IllegalStateException("actual completed setup physical owners not joined");
            JSONObject original=EmbeddedDuplexOriginals.forApplication(applicationContext).completedSetupJoinForNative(nativeId,challenge);
            boolean incoming=platform.completedSetupIncomingJoined(nativeId);
            if(original==null&&!incoming)throw new IllegalStateException("actual completed setup operator/incoming custody absent");
            return EmbeddedDuplexCanonicalJson.encode(new JSONObject().put("schema","rusty.quest.embedded_duplex.completed_setup_recovery_snapshot.v1")
                .put("challenge",challenge).put("actor_binding",originalActor()).put("identity",identity)
                .put("native_pair_status_json",pairRaw).put("native_no_media_status_json",noMediaRaw)
                .put("physical_eligibility",physical).put("completed_original_join",original==null?JSONObject.NULL:original)
                .put("incoming_setup_joined",incoming));
        });
    }

    CompletableFuture<EmbeddedDuplexPairStatus> pairStatus() {
        return submit(() -> {
            requireFreshProcess();
            if (phase.get() != Phase.READY || localFixture) {
                throw new IllegalStateException("real-peer authority unavailable");
            }
            return EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand(
                    "pair_status", "{}"));
        });
    }

    CompletableFuture<EmbeddedDuplexPairStatus> pairSession() {
        if(EmbeddedDuplexOriginals.capture()==null){String challenge=internalChallenge();return appOriginal("pair_session",challenge,
            this::pairSession,actual->CompletableFuture.completedFuture(EmbeddedDuplexOperatorResponses.pairBundle(challenge,actual)));}
        synchronized (attachmentGate) {
            if (phase.get() != Phase.READY || localFixture || attachmentGeneration == 0L
                    || displayDetaching || closeInFlight) {
                return failed(new IllegalStateException("real-peer pair ceremony unavailable"));
            }
        }
        return submit(() -> {
            requireFreshProcess();
            EmbeddedDuplexPairStatus actualPolicy=EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand("pair_status","{}"));
            return EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand(
                actualPolicy.selectedFreshPolicy?"fresh_pair_setup":"pair_ceremony", "{}"));
        });
    }

    private String inCurrentWholeNativeClose(java.util.concurrent.Callable<String> operation)throws Exception {
        EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
        if(actual==null||!"whole_app_close".equals(actual.row.getString("operation"))||activeWholeNativeOriginal!=null)
            throw new IllegalStateException("actual sole whole Native Original absent");
        requireCurrentOriginalStopActor(actual);activeWholeNativeOriginal=actual;
        try{return operation.call();}finally{activeWholeNativeOriginal=null;}
    }
    static void requireCurrentWholeNativeClosure(EmbeddedDuplexPlatform platform,JSONObject body)throws Exception {
        EmbeddedDuplexProcessHost host=instance;EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
        if(host==null||actual==null||host.activeWholeNativeOriginal!=actual||host.platform!=platform||host.resources==null
            ||!host.resources.productResourcesTerminal()||!"whole_app_close".equals(actual.row.getString("operation"))
            ||!"normal_peer_whole_close".equals(body.getString("retirement_scope"))
            ||!"terminal".equals(body.getJSONObject("close_proof").getString("peer_physical_cleanup")))
            throw new IllegalStateException("actual current whole Native closing/source/SDK span absent");
        requireCurrentOriginalStopActor(actual);EmbeddedDuplexPeerRoots.identity(body,freshClosureIdentity(platform));
    }
    static void requireUnknownPeerRootCustodySpan(EmbeddedDuplexOriginals.Record current,JSONObject actor)throws Exception {
        EmbeddedDuplexProcessHost host=instance;RetainedNoMediaClose held=host==null?null:host.noMediaClose;
        if(current==null||host==null||held==null||held.original!=current||host.phase.get()!=Phase.CLOSING||!host.closeInFlight
            ||!"whole_app_close".equals(current.row.getString("operation"))||!held.pendingUnknownRoot
            ||!held.stopIssued||!held.controlClosed||held.platform!=host.platform
            ||held.resources!=host.resources||held.nativeGeneration<=0||held.config==null)
            throw new IllegalStateException("unknown root metadata requires actual retained whole cleanup span");
        boolean dormantPrepared=held.pendingPrepared&&held.resources!=null&&held.platform.gatedPreparedCleanupCandidate();
        if(!dormantPrepared && (!held.resourcesClosed||!held.displayDetached||!EmbeddedDuplexRendererRetirement.currentTerminal()))
            throw new IllegalStateException("unknown root custody lacks actual physical or held gated-preparation branch");
        EmbeddedDuplexPeerRoots.identity(actor,freshClosureIdentity(held.platform));
    }
    static JSONObject preparedStartTarget(EmbeddedDuplexPlatform actual)throws Exception {
        EmbeddedDuplexProcessHost host=instance;freshClosureIdentity(actual);
        EmbeddedDuplexOriginals.Record original=EmbeddedDuplexOriginals.capture();
        if(host==null||host.phase.get()!=Phase.READY||host.resources==null||host.nativeConfigurationContext==null
            ||original==null||!"prepare_peer_start".equals(original.row.getString("operation"))||original.desiredStop.get())
            throw new IllegalStateException("actual selected prepared resource owner absent");
        requireCurrentOriginalStopActor(original);
        return host.resources.preparationTarget(host.runtimeConfigSha256);
    }
    static EmbeddedDuplexPreparedStart reservePreparedStart(EmbeddedDuplexPlatform actual,JSONObject request)throws Exception {
        EmbeddedDuplexProcessHost host=instance;freshClosureIdentity(actual);
        EmbeddedDuplexOriginals.Record entered=EmbeddedDuplexOriginals.capture();
        if(host==null||host.resources==null||host.nativeConfigurationContext==null||entered==null
            ||!"prepare_peer_start".equals(entered.row.getString("operation")))throw new IllegalStateException("actual prepared reservation source absent");
        requireCurrentOriginalStopActor(entered);
        JSONObject arm=ConcurrentStereoQualification.retirementArmObservation(host.processFence.epochId(),host.runtimeConfigSha256,host.nativeExecutorGeneration);
        return new EmbeddedDuplexPreparedStart(host.resources,request,EmbeddedDuplexOriginals.capture(),freshClosureIdentity(actual),arm,host.nativeConfigurationContext);
    }
    static JSONObject peerRootEligibility(EmbeddedDuplexPlatform actual)throws Exception{
        EmbeddedDuplexProcessHost current=instance;JSONObject identity=freshClosureIdentity(actual);
        EmbeddedDuplexOriginals.Record original=EmbeddedDuplexOriginals.capture();
        if(original==null || !"reestablish_peer_roots".equals(original.row.getString("operation")) || original.desiredStop.get())
            throw new IllegalStateException("actual peer roots Original absent or desired Stop");
        requireCurrentOriginalStopActor(original);
        JSONObject arm=ConcurrentStereoQualification.retirementArmObservation(current.processFence.epochId(),current.runtimeConfigSha256,current.nativeExecutorGeneration);
        EmbeddedDuplexPeerRoots.arm(arm);
        JSONObject physical=freshPhysical(actual,null,false);
        return new JSONObject(identity.toString()).put("$schema","rusty.quest.embedded_duplex.peer_root_reestablishment_eligibility.v1")
            .put("original_binding",new JSONObject().put("kind","operator").put("original_id",original.id()))
            .put("arm_binding",arm).put("physical_eligibility",physical);
    }
    static JSONObject preparedStartEligibility(EmbeddedDuplexPlatform actual)throws Exception {
        preparedStartTarget(actual);EmbeddedDuplexProcessHost host=instance;
        JSONObject identity=freshClosureIdentity(actual),arm=ConcurrentStereoQualification.retirementArmObservation(host.processFence.epochId(),host.runtimeConfigSha256,host.nativeExecutorGeneration);
        EmbeddedDuplexPeerRoots.arm(arm);
        return new JSONObject(identity.toString()).put("$schema","rusty.quest.embedded_duplex.peer_start_preparation_eligibility.v1")
            .put("original_binding",new JSONObject().put("kind","operator").put("original_id",EmbeddedDuplexOriginals.capture().id()))
            .put("arm_binding",arm).put("physical_eligibility",freshPhysical(actual,null,false));
    }
    static void requirePreparedAbortContext(JSONObject request)throws Exception {
        EmbeddedDuplexProcessHost host=instance;EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
        if(host==null||host.platform==null||actual==null||!java.util.Arrays.asList("whole_app_close","abort_prepared_peer_start").contains(actual.row.getString("operation")))
            throw new IllegalStateException("actual prepared cleanup Original absent");
        requireCurrentOriginalStopActor(actual);EmbeddedDuplexPeerRoots.identity(request,freshClosureIdentity(host.platform));
        if(!actual.id().equals(request.getString("cleanup_original_id")))
            EmbeddedDuplexOriginals.forApplication(host.applicationContext).requireOldUnknownWholeCleanup(request.getString("cleanup_original_id"));
    }
    static void requirePreparedCleanupArchive(String originalId)throws Exception {
        EmbeddedDuplexProcessHost host=instance;RetainedNoMediaClose held=host==null?null:host.noMediaClose;
        EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
        if(held==null||host.phase.get()!=Phase.CLOSING||!held.pendingPrepared||held.platform!=host.platform||held.resources!=host.resources
            ||actual==null||!"whole_app_close".equals(actual.row.getString("operation"))||!held.controlClosed
            ||held.resources==null||!held.resources.productResourcesTerminal())throw new IllegalStateException("actual prepared cleanup archive span absent");
        requireCurrentOriginalStopActor(actual);
        if(!actual.id().equals(originalId))EmbeddedDuplexOriginals.forApplication(host.applicationContext).requireOldUnknownWholeCleanup(originalId);
    }
    static void requirePreparedOriginal(EmbeddedDuplexOriginals.Record original,JSONObject receipt)throws Exception {
        EmbeddedDuplexProcessHost host=instance;
        if(host==null||host.platform==null||original==null||!"prepare_peer_start".equals(original.row.getString("operation"))
            ||!original.id().equals(receipt.getString("original_id")))throw new IllegalStateException("actual prepared result Original differs");
        EmbeddedDuplexPeerRoots.identity(receipt,freshClosureIdentity(host.platform));host.platform.requirePreparedNativeStatus(receipt,true);
    }
    CompletableFuture<String> preparePeerStart(){
        if(EmbeddedDuplexOriginals.capture()==null)return failed(new IllegalStateException("actual prepared wrapper Original absent"));
        return submit(()->{
            requireFreshProcess();preparedStartTarget(platform);
            EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
            String raw=EmbeddedDuplexNative.runtimeCommand("prepare_peer_start","{}");
            JSONObject receipt=new JSONObject(raw);requirePreparedOriginal(actual,receipt);return raw;
        });
    }
    CompletableFuture<String> preparedPeerStartStatus(){
        return submit(()->{
            requireFreshProcess();if(platform==null||phase.get()!=Phase.READY)throw new IllegalStateException("actual prepared source unavailable");
            String raw=EmbeddedDuplexNative.runtimeCommand("prepared_peer_start_status","{}");JSONObject receipt=new JSONObject(raw);
            EmbeddedDuplexPeerRoots.identity(receipt,freshClosureIdentity(platform));platform.requirePreparedNativeStatus(receipt,false);return raw;
        });
    }
    CompletableFuture<String> peerRootStatus(){
        return submit(()->{
            requireFreshProcess();
            if(phase.get()!=Phase.READY || localFixture || platform==null)throw new IllegalStateException("actual peer roots configured owner unavailable");
            EmbeddedDuplexPairStatus policy=EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand("pair_status","{}"));
            if(!policy.selectedFreshPolicy)throw new IllegalStateException("peer roots selected fresh policy absent");
            JSONObject identity=freshClosureIdentity(platform);
            return EmbeddedDuplexPeerRoots.status(EmbeddedDuplexNative.runtimeCommand("peer_root_status","{}"),identity);
        });
    }
    CompletableFuture<String> reestablishPeerRoots(){
        if(EmbeddedDuplexOriginals.capture()==null)return failed(new IllegalStateException("peer roots require actual wrapper Original"));
        synchronized(attachmentGate){
            if(phase.get()!=Phase.READY || localFixture || platform==null || attachmentGeneration==0L || displayDetaching || closeInFlight)
                return failed(new IllegalStateException("peer roots actual owner unavailable"));
        }
        return submit(()->{
            requireFreshProcess();
            EmbeddedDuplexOriginals.Record original=EmbeddedDuplexOriginals.capture();
            if(original==null || !"reestablish_peer_roots".equals(original.row.getString("operation")) || original.desiredStop.get())
                throw new IllegalStateException("peer roots current Original/Stop differs");
            JSONObject eligibility=peerRootEligibility(platform);
            if(!eligibility.getJSONObject("physical_eligibility").getBoolean("eligible"))throw new IllegalStateException("actual peer physical boundary pending");
            EmbeddedDuplexPairStatus policy=EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand("pair_status","{}"));
            if(!policy.selectedFreshPolicy || original.desiredStop.get())throw new IllegalStateException("peer roots selected source/Stop differs");
            String raw=EmbeddedDuplexNative.runtimeCommand("reestablish_peer_roots","{}");
            return EmbeddedDuplexPeerRoots.result(raw,freshClosureIdentity(platform),original.id(),eligibility.getJSONObject("arm_binding"));
        });
    }
    CompletableFuture<EmbeddedDuplexPairStatus> continueFreshPair(){
        if(EmbeddedDuplexOriginals.capture()==null)return failed(new IllegalStateException("fresh continuation requires actual wrapper Original"));
        synchronized(attachmentGate){
            if(phase.get()!=Phase.READY || localFixture || attachmentGeneration==0L || displayDetaching || closeInFlight)
                return failed(new IllegalStateException("fresh continuation actor unavailable"));
        }
        return submit(()->{
            requireFreshProcess();
            EmbeddedDuplexPairStatus policy=EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand("pair_status","{}"));
            if(!policy.selectedFreshPolicy)throw new IllegalStateException("fresh continuation actual policy absent");
            return EmbeddedDuplexPairStatus.parse(EmbeddedDuplexNative.runtimeCommand("fresh_pair_continue","{}"));
        });
    }
    CompletableFuture<EmbeddedDuplexStartPreflight> prepareStartPreflight(long expectedGeneration) {
        synchronized (attachmentGate) {
            if (phase.get() != Phase.READY || localFixture || expectedGeneration == 0L
                    || attachmentGeneration != expectedGeneration || displayDetaching
                    || closeInFlight || display.cleanupPending()) {
                return failed(new IllegalStateException("pre-Start display unavailable"));
            }
        }
        return submit(() -> {
            synchronized (attachmentGate) {
                if (phase.get() != Phase.READY || localFixture
                        || attachmentGeneration != expectedGeneration || displayDetaching
                        || closeInFlight || display.cleanupPending()) {
                    throw new IllegalStateException("pre-Start process changed");
                }
            }
            requireFreshProcess();
            EmbeddedDuplexPairStatus pair = EmbeddedDuplexPairStatus.parse(
                    EmbeddedDuplexNative.runtimeCommand("pair_status", "{}"));
            EmbeddedDuplexStartPreflight next = EmbeddedDuplexStartPreflight.prepare(pair,
                    runtimeConfigSha256, enrollmentRecordSha256, expectedGeneration);
            return startIntent.prepare(next);
        });
    }

    boolean preflightLive(EmbeddedDuplexStartPreflight observed) {
        synchronized (attachmentGate) {
            try {
                if (processFence == null) return false;
                processFence.requireLive(processFence.generation());
            } catch (IllegalStateException stale) { return false; }
            return !processFence.recoveryOnly()
                    && observed != null && startIntent.pending() == observed
                    && phase.get() == Phase.READY && !localFixture && !displayDetaching
                    && !closeInFlight && attachmentGeneration == observed.displayGeneration
                    && observed.matches(runtimeConfigSha256, enrollmentRecordSha256,
                            attachmentGeneration)
                    && System.currentTimeMillis() < observed.sessionExpiresAtMs;
        }
    }

    private EmbeddedDuplexRuntimeStatus runtimeStatusOnCommandLane() {
        Phase current = phase.get();
        boolean recoveryClear;
        try {
            recoveryClear = processFence != null && !processFence.recoveryOnly()
                    && new EmbeddedDuplexRecoveryCoordinator(applicationContext)
                    .freshBootstrapAllowed();
        } catch (Exception unavailable) {
            recoveryClear = false;
        }
        String state = !recoveryClear ? "cleanup_pending"
                : current == Phase.READY && !localFixture
                ? "bootstrapped_route_unverified"
                : current == Phase.NEW ? "uninitialized"
                : current == Phase.READY ? "local_fixture"
                : current == Phase.BOOTSTRAPPING ? "bootstrapping" : "cleanup_pending";
        EmbeddedDuplexRuntimeStatus status = new EmbeddedDuplexRuntimeStatus(state, attachmentGeneration != 0L,
                runtimeConfigSha256, enrollmentRecordSha256, lastBootstrapFailure, setupCredentials);
        OwnStereoCaptureRuntime ownCapture = OwnStereoCaptureRuntime.currentForApplication();
        return ownCapture != null ? new EmbeddedDuplexRuntimeStatus(status, ownCapture.phase().name(), ownCapture.cleanupStatus()) : status;
    }

    /** Ordinary app Close uses the same actual whole-product owner as the typed operator.
     * It never creates an ARM, renews a peer session, or substitutes no-media cleanup for Stop. */
    CompletableFuture<String> closeProduct(long expectedGeneration) {
        synchronized(attachmentGate){
            if(expectedGeneration<=0 || attachmentGeneration!=expectedGeneration || displayDetaching || localFixture)
                return failed(new IllegalStateException("ordinary Close attachment unavailable"));
        }
        try{requestDesiredStopOutsideCommandLane(true);}catch(Exception failure){return failed(failure);}
        return submit(()->{
            synchronized(attachmentGate){
                if(attachmentGeneration!=expectedGeneration || displayDetaching || localFixture)
                    throw new IllegalStateException("ordinary Close attachment superseded");
            }
            if(retainedWholeClose!=null)return retainedWholeClose.challenge;
            JSONObject actual=ConcurrentStereoQualification.retirementArmObservation(processFence.epochId(),runtimeConfigSha256,nativeExecutorGeneration);
            return "established".equals(actual.getString("binding"))?actual.getString("challenge"):internalChallenge();
        }).thenCompose(challenge->peerLifecycle(EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE,challenge)).thenApply(receipt->{
            try{
                JSONObject result=new JSONObject(receipt);
                JSONObject physical=result.optJSONObject("peer_lifecycle");
                if(physical==null)physical=result; // No-media whole retirement returns its direct joined proof.
                if(!"whole_app_close".equals(physical.optString("action")))
                    throw new IllegalStateException("ordinary Close owner proof unavailable");
                if(physical.has("status") && !"terminal".equals(physical.optString("status")))
                    throw new IllegalStateException("ordinary Close owner result remains pending or failed");
                for(String key:new String[]{"whole_app_physical_cleanup","app_process_fence_cleanup",
                        "java_own_capture_cleanup","java_renderer_cleanup","java_control_cleanup"})
                    if(!"terminal".equals(physical.optString(key)))
                        throw new IllegalStateException("ordinary Close physical cleanup remains pending");
                return receipt;
            }catch(Exception pendingOrInvalid){throw new java.util.concurrent.CompletionException(pendingOrInvalid);}
        });
    }

    CompletableFuture<String> closeRealPeerNoMedia(long expectedGeneration) {
        if(EmbeddedDuplexOriginals.capture()==null){String challenge=internalChallenge();return appOriginal("close_no_media",challenge,
            ()->closeRealPeerNoMedia(expectedGeneration),closed->{android.os.Bundle response=EmbeddedDuplexOperatorResponses.runtimeBundle(challenge,closedNoMediaStatus());
                response.putString("close_disposition",closed);return CompletableFuture.completedFuture(response);});}
        synchronized (attachmentGate) {
            if (localFixture || expectedGeneration == 0L
                    || attachmentGeneration != expectedGeneration) {
                return failed(new IllegalStateException("real-peer no-media close unavailable"));
            }

        }
        requestDesiredStopOutsideCommandLane(true);
        return closeNoMediaAndDetach(expectedGeneration);
    }

    CompletableFuture<EmbeddedDuplexEnrollment> replaceEnrollment(
            EmbeddedDuplexEnrollmentDraft reviewedDraft) {
        if (reviewedDraft == null) return failed(new IllegalArgumentException("reviewed enrollment draft"));
        synchronized (attachmentGate) {
            if (displayDetaching || closeInFlight || platform != null || resources != null
                    || runtimeConfigSha256 != null
                    || !phase.compareAndSet(Phase.NEW, Phase.PROVISIONING)) {
                return failed(new IllegalStateException("process authority must terminate before enrollment change"));
            }
            pendingReview = null;
        }
        return submit(() -> {
            try {
                requireFreshProcess();
                nativeConfigurationContext=null;
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                if (!EmbeddedDuplexNative.processIdleForEnrollment()) {
                    throw new IllegalStateException("native process or staged route is not terminal");
                }
                return EmbeddedDuplexEnrollmentResolver.replace(applicationContext, reviewedDraft);
            } finally {
                phase.set(Phase.NEW);
            }
        });
    }

    CompletableFuture<EmbeddedDuplexEnrollmentReview> reviewEnrollment(
            EmbeddedDuplexEnrollmentDraft draft) {
        synchronized (attachmentGate) { pendingReview = null; }
        if (draft == null) return failed(new IllegalArgumentException("enrollment review draft"));
        return submit(() -> {
            synchronized (attachmentGate) {
                if (displayDetaching || closeInFlight || platform != null || resources != null
                        || runtimeConfigSha256 != null || phase.get() != Phase.NEW) {
                    throw new IllegalStateException("process authority must terminate before enrollment review");
                }
            }
            requireFreshProcess();
            new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
            if (!EmbeddedDuplexNative.processIdleForEnrollment()) {
                throw new IllegalStateException("native process or staged route is not terminal");
            }
            EmbeddedDuplexEnrollmentReview review =
                    EmbeddedDuplexEnrollmentResolver.review(applicationContext, draft);
            synchronized (attachmentGate) {
                if (displayDetaching || closeInFlight || phase.get() != Phase.NEW) {
                    throw new IllegalStateException("process changed during enrollment review");
                }
                pendingReview = review;
            }
            return review;
        });
    }

    CompletableFuture<EmbeddedDuplexEnrollmentStatus> enrollmentStatus(String roleId) {
        if (!("peer_a".equals(roleId) || "peer_b".equals(roleId))) {
            return failed(new IllegalArgumentException("installed role invalid"));
        }
        return submit(() -> EmbeddedDuplexEnrollmentResolver.status(applicationContext, roleId));
    }

    CompletableFuture<EmbeddedDuplexEnrollment> confirmEnrollment(
            EmbeddedDuplexEnrollmentReview review) {
        synchronized (attachmentGate) {
            if (review == null || pendingReview != review || review.expired()
                    || displayDetaching || closeInFlight || platform != null || resources != null
                    || runtimeConfigSha256 != null
                    || !phase.compareAndSet(Phase.NEW, Phase.PROVISIONING)) {
                return failed(new IllegalStateException("enrollment review unavailable or expired"));
            }
            pendingReview = null;
        }
        return submit(() -> {
            try {
                requireFreshProcess();
                nativeConfigurationContext=null;
                new EmbeddedDuplexRecoveryCoordinator(applicationContext).requireFreshBootstrap();
                if (review.expired() || !EmbeddedDuplexNative.processIdleForEnrollment()) {
                    throw new IllegalStateException("review expired or native process is not terminal");
                }
                return EmbeddedDuplexEnrollmentResolver.replaceReviewed(applicationContext, review);
            } finally {
                phase.set(Phase.NEW);
            }
        });
    }

    CompletableFuture<EmbeddedDuplexEnrollment> provisionLocalDiagnosticFixture(String challenge) {
        synchronized (attachmentGate) {
            if (challenge == null || !challenge.equals(diagnosticChallenge)) {
                return failed(new IllegalStateException("local diagnostic challenge unavailable"));
            }
        }
        try {
            return replaceEnrollment(EmbeddedDuplexEnrollmentResolver.localDiagnosticDraft(
                    applicationContext));
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    void armDiagnosticChallenge(String challenge) {
        synchronized (attachmentGate) {
            if (challenge == null || !challenge.matches("[0-9a-f]{32}")
                    || phase.get() != Phase.NEW || displayDetaching || closeInFlight
                    || platform != null || resources != null || runtimeConfigSha256 != null
                    || diagnosticChallenge != null) {
                throw new IllegalStateException("local diagnostic challenge unavailable");
            }
            diagnosticChallenge = challenge;
        }
    }

    boolean hasDiagnosticChallenge(String challenge) {
        synchronized (attachmentGate) {
            return challenge != null && challenge.equals(diagnosticChallenge)
                    && phase.get() == Phase.NEW && !displayDetaching && !closeInFlight;
        }
    }

    /** One-Quest diagnostic: resolve the signed fixture in process scope,
     * bootstrap without Start, then prove terminal closure before reporting. */
    CompletableFuture<String> diagnoseLocalFixture(long expectedGeneration) {
        final String challenge;
        synchronized (attachmentGate) {
            if (expectedGeneration == 0L || attachmentGeneration != expectedGeneration
                    || displayDetaching || display.cleanupPending()
                    || diagnosticChallenge == null
                    || !phase.compareAndSet(Phase.NEW, Phase.BOOTSTRAPPING)) {
                return failed(new IllegalStateException("local diagnostic display unavailable"));
            }
            challenge = diagnosticChallenge;
            diagnosticChallenge = null;
        }
        AtomicReference<String> enrollmentSha = new AtomicReference<>();
        EmbeddedDuplexBootstrap.Trace trace = new EmbeddedDuplexBootstrap.Trace();
        CompletableFuture<String> bootstrap = submit(() -> {
            try {
                trace.mark(EmbeddedDuplexBootstrap.Failure.ENROLLMENT_RESOLVE);
                EmbeddedDuplexEnrollment enrollment =
                        EmbeddedDuplexEnrollmentResolver.resolve(applicationContext);
                enrollmentSha.set(enrollment.recordSha256);
                trace.mark(EmbeddedDuplexBootstrap.Failure.FIXTURE_INPUTS);
                EmbeddedDuplexSessionInputs fresh =
                        EmbeddedDuplexSessionInputs.createLocalDiagnostic(enrollment);
                return initializeOnCommandLane(fresh.role, fresh.runtimeBindings.toString(),
                        fresh.startup.toString(), true, trace);
            } catch (Exception failure) {
                if (phase.get() == Phase.BOOTSTRAPPING) {
                    phase.set(runtimeConfigSha256 == null && (processFence == null
                        || !processFence.effectsPending()) ? Phase.NEW : Phase.FAILED);
                }
                throw failure;
            }
        }).thenCompose(next->next);
        return bootstrap.handle((initialized, failure) -> {
            Phase current = phase.get();
            String configSha = runtimeConfigSha256;
            if (current == Phase.NEW) {
                try {
                    detachUninitializedDisplay(expectedGeneration);
                    return CompletableFuture.completedFuture(
                            terminalDiagnosticResult(challenge, "bootstrap_unavailable_closed",
                                    enrollmentSha.get(), configSha, trace.failure()));
                } catch (Exception cleanup) { return EmbeddedDuplexProcessHost.<String>failed(cleanup); }
            }
            if (current != Phase.READY && current != Phase.FAILED) {
                return EmbeddedDuplexProcessHost.<String>failed(
                        new IllegalStateException("local diagnostic cleanup state unavailable"));
            }
            return closeNoMediaAndDetach(expectedGeneration).thenApply(closed ->
                    terminalDiagnosticResult(challenge,
                            failure == null ? "bootstrap_closed" : "bootstrap_failed_closed",
                            enrollmentSha.get(), configSha,
                            failure == null ? null : trace.failure()));
        }).thenCompose(next -> next);
    }

    private String terminalDiagnosticResult(String challenge, String status,
            String enrollmentSha, String configSha, EmbeddedDuplexBootstrap.Failure failure) {
        try {
            return EmbeddedDuplexDiagnosticService.finalizeReceipt(applicationContext,
                    challenge, status, enrollmentSha, configSha, failure);
        } catch (Exception receiptFailure) {
            // Cleanup is already terminal. Do not strand the old Activity
            // attachment merely because the optional diagnostic file failed.
            return "{\"$schema\":\"rusty.quest.embedded_duplex.local_diagnostic.v1\","
                    + "\"status\":\"receipt_unavailable_closed\","
                    + "\"display_detached\":true}";
        }
    }

    /** Retry the same attachment after a diagnostic failure. The Activity must
     * not retire its display executor until this future has completed. */
    CompletableFuture<String> retryLocalDiagnosticCleanup(long expectedGeneration) {
        synchronized (attachmentGate) {
            if (expectedGeneration == 0L || attachmentGeneration != expectedGeneration
                    || !localFixture && runtimeConfigSha256 != null) {
                return failed(new IllegalStateException("local fixture cleanup unavailable"));
            }
            if (phase.get() == Phase.NEW && runtimeConfigSha256 == null) {
                return submit(() -> {
                    detachUninitializedDisplay(expectedGeneration);
                    return "uninitialized-display-detached";
                });
            }
        }
        return closeNoMediaAndDetach(expectedGeneration);
    }

    /** The no-Start path releases one process attachment only after all native,
     * Java and display resources prove terminal. A failed close retains exactly
     * the same attachment and objects for retry. */
    CompletableFuture<String> closeNoMediaAndDetach(long expectedGeneration) {
        // Own startup/display futures see CLOSING and finish with their actual Stop/supersession, freeing the command lane.
        synchronized(attachmentGate){
            if(noMediaClose!=null){
                if(noMediaClose.attachment!=expectedGeneration)return failed(new IllegalStateException("no-media attachment changed"));
                return noMediaClose.completion;
            }
            Phase current=phase.get();
            if(expectedGeneration<=0 || attachmentGeneration!=expectedGeneration || closeInFlight
                    || !(current==Phase.NEW || current==Phase.FAILED || current==Phase.READY || current==Phase.BOOTSTRAPPING))
                return failed(new IllegalStateException("no-media close owner unavailable"));
            closeInFlight=true;phase.set(Phase.CLOSING);
        }
        return submit(()->{
            processFence.beforeNativeAbsenceRetirement(checkpointSnapshot(),evidenceSnapshot());
            String record=EmbeddedDuplexProcessFence.digest(processFence.nativeAdmissionRecord());
            final RetainedNoMediaClose held=new RetainedNoMediaClose(runtimeConfigSha256,record,processFence.epochId(),
                processFence.generation(),nativeExecutorGeneration,expectedGeneration,platform,resources,
                OwnStereoCaptureRuntime.currentForApplication(),display.retainOwnedProjectionStopTarget(expectedGeneration),
                ConcurrentStereoQualification.retirementArmObservation(processFence.epochId(),runtimeConfigSha256,nativeExecutorGeneration),
                EmbeddedDuplexOriginals.capture(),nativeConfigurationContext);
            // Fixed actual held AppFenceSource before native absence/capability retirement.
            // A never-claimed process has no native executor; its app binding is still real.
            EmbeddedDuplexOriginals.bindActualActor(new JSONObject().put("package_id",applicationContext.getPackageName())
                .put("app_record_sha256",held.appRecord).put("native_generation",held.nativeGeneration==0?JSONObject.NULL:held.nativeGeneration));
            noMediaClose=held;
            continueNoMediaClose(held);return held.completion;
        }).thenCompose(next->next);
    }
    private void continueNoMediaClose(RetainedNoMediaClose held){
        if(held.completion.isDone())return;
        try(AutoCloseable ignored=EmbeddedDuplexOriginals.enter(held.original)){
            processFence.requireLive(held.appGeneration);
            if(noMediaClose!=held || phase.get()!=Phase.CLOSING || !processFence.epochId().equals(held.epoch)
                    || resources!=held.resources || platform!=held.platform || attachmentGeneration!=held.attachment
                    || !java.util.Objects.equals(runtimeConfigSha256,held.config) || nativeExecutorGeneration!=held.nativeGeneration
                    || nativeConfigurationContext!=held.configurationContext)
                throw new IllegalStateException("retained no-media owner changed");
            if(!held.stopIssued){
                JSONObject candidate=new JSONObject(EmbeddedDuplexNative.noMediaRetirementStatus());
                if(!"rusty.quest.embedded_duplex.no_media_retirement_status.v1".equals(candidate.getString("$schema")))
                    throw new IllegalStateException("native no-media classifier schema");
                String classification=candidate.getString("classification");
                if(classification.equals("busy")){scheduleNoMedia(held);return;}
                if(!java.util.Arrays.asList("pending_fresh_setup","configured_no_media_candidate","staged_no_media_candidate",
                    "configured_closed","never_claimed","uninitialized_capability","partial_construction_candidate","pending_peer_root_reestablishment","pending_prepared_peer_start").contains(classification))
                    throw new IllegalStateException("native no-media classification refuses retirement: "+classification);
                held.pendingFresh=classification.equals("pending_fresh_setup");
                held.pendingUnknownRoot=classification.equals("pending_peer_root_reestablishment");
                held.pendingPrepared=classification.equals("pending_prepared_peer_start")||held.platform!=null&&held.platform.hasPreparedPeerStart();
                held.stopIssued=true;
                if(held.own!=null)held.own.requestStopOwn();
                if(!held.pendingPrepared){held.stopProjection.run();held.projectionStopIssued=true;}
            }
            // Stop and join entered control handlers before any Native metadata retirement.
            // Prepared SDK owners are still real/nonterminal at this point.
            if(!held.controlClosed){if(held.platform!=null)held.platform.closeControlForNoMedia();held.controlClosed=true;}
            if(held.pendingUnknownRoot&&held.unknownRootMetadata==null&&held.pendingPrepared){
                if(!held.platform.gatedPreparedCleanupCandidate())throw new IllegalStateException("root/prepared cleanup requires exact dormant closed-gate owners");
                String metadata=EmbeddedDuplexNative.retireUnknownPeerRootMetadata(held.config);
                held.unknownRootMetadata=new EmbeddedDuplexUnknownPeerRootCustody(applicationContext)
                    .requireMetadataResult(metadata,freshClosureIdentity(held.platform));
            }
            if(held.pendingPrepared&&held.preparedMetadata==null){
                String raw=EmbeddedDuplexNative.retirePreparedPeerStartMetadata(held.config);
                JSONObject metadata=new JSONObject(raw);held.platform.requirePreparedMetadataResult(metadata);held.preparedMetadata=metadata;
            }
            if(!held.projectionStopIssued){held.stopProjection.run();held.projectionStopIssued=true;}
            JSONObject own=held.own==null?new JSONObject().put("phase","NotConstructed").put("state","not_created").put("capture_ever_owned",false)
                :held.own.physicalRetirementObservation();
            if(!java.util.Arrays.asList("retired","not_created").contains(own.getString("state"))){scheduleNoMedia(held);return;}
            if(!held.resourcesClosed){
                if(held.resources!=null){
                    if(held.pendingPrepared){if(!held.resources.productResourcesTerminal())throw new IllegalStateException("actual prepared SDK resources remain Pending");}
                    else held.resources.closeUnstartedAndVerify();
                }
                held.resourcesClosed=true;
            }
            if(!held.displayDetached){display.detachAfterCleanup(held.attachment,()->{});held.displayDetached=true;displayDetaching=true;}
            // The real renderer carrier stop/readback remains strict, independently of Java ARM establishment.
            boolean rendererTerminal=EmbeddedDuplexRendererRetirement.currentTerminal()
                && EmbeddedDuplexNative.localCameraQuiescent();
            if(!rendererTerminal){scheduleNoMedia(held);return;}
            if(held.pendingUnknownRoot && held.unknownRootMetadata==null){
                // This distinct effect archives only an actually joined UNKNOWN
                // RootFlight after the local barriers above. It neither proves
                // physical cleanup nor changes the prior Original outcome.
                String metadata=EmbeddedDuplexNative.retireUnknownPeerRootMetadata(held.config);
                held.unknownRootMetadata=new EmbeddedDuplexUnknownPeerRootCustody(applicationContext)
                    .requireMetadataResult(metadata,freshClosureIdentity(held.platform));
            }
            if(held.nativeProof==null){
                String actual;
                if(held.nativeGeneration==0L)actual=EmbeddedDuplexNative.retireNeverClaimedNoMedia(processFence);
                else if(held.config==null)actual=EmbeddedDuplexNative.retireUninitializedNoMedia();
                else actual=held.pendingFresh?EmbeddedDuplexNative.retirePendingFreshNoMedia(held.config)
                    :EmbeddedDuplexNative.finishNoMediaRetirement(held.config);
                held.nativeProof=new JSONObject(actual);
                verifyNoMediaNative(held,held.nativeProof);
            }
            if(held.callbackMarker==null){
                CompletableFuture<Void> platformDrain=held.platform==null?CompletableFuture.completedFuture(null):held.platform.retireProcessCallbacksAsync();
                CompletableFuture<Void> ownDrain=held.observer==null?CompletableFuture.completedFuture(null):held.own.unregisterPhysicalCleanup(held.observer);
                held.callbackMarker=CompletableFuture.allOf(platformDrain,ownDrain).thenRunAsync(()->{},commands);
            }
            if(!held.callbackMarker.isDone()){scheduleNoMedia(held);return;}
            if(held.platform!=null && !held.platform.actualOutboundExchangesJoined()){scheduleNoMedia(held);return;}
            held.callbackMarker.join();
            JSONObject proof=new JSONObject().put("$schema",EmbeddedDuplexRuntimeConfigurationBinding.NATIVE_V2.equals(held.nativeProof.getString("$schema"))
                    ?EmbeddedDuplexRuntimeConfigurationBinding.WHOLE_V2:EmbeddedDuplexConstructionClosure.NATIVE.equals(held.nativeProof.getString("$schema"))
                    ?EmbeddedDuplexConstructionClosure.WHOLE:"rusty.quest.embedded_duplex.no_media_whole_retirement.v1")
                .put("action","whole_app_close").put("app_process_generation",held.appGeneration)
                .put("app_record_sha256",held.appRecord).put("process_epoch",held.epoch)
                .put("native_retirement",held.nativeProof).put("arm_observation",held.arm)
                .put("own_capture",own).put("java_own_capture_cleanup","terminal")
                .put("java_peer_resources_cleanup","terminal").put("java_renderer_cleanup","terminal")
                .put("java_control_cleanup","terminal").put("java_callback_cleanup","terminal")
                .put("command_lane_barrier","terminal").put("whole_app_physical_cleanup","terminal");
            if(held.config!=null)proof.put("config_sha256",held.config);
            if(held.nativeGeneration>0)proof.put("native_executor_generation",held.nativeGeneration);
            processFence.afterVerifiedWholeProductCleanup(checkpointSnapshot(),evidenceSnapshot(),EmbeddedDuplexProcessFence.digest(proof.toString()));
            proof.put("app_process_fence_cleanup","terminal");held.proof=proof;
            // These are actual joined owners. Native close does not certify outer process/source EOF.
            platform=null;resources=null;runtimeConfigSha256=null;nativeExecutorGeneration=0L;nativeAppRecordSha256=null;nativeConfigurationContext=null;
            enrollmentRecordSha256=null;startIntent.afterVerifiedCleanup();localFixture=false;
            synchronized(attachmentGate){attachmentGeneration=0L;displayDetaching=false;closeInFlight=false;phase.set(Phase.NEW);}
            lastClosedActorProof=proof;
            EmbeddedDuplexOriginals.forApplication(applicationContext).actualClosedAppOwnerJoined(
                held.appGeneration,held.nativeGeneration,held.appRecord,proof,held);
            if(held.original!=null)held.original.retainedCleanupOwner=held;
            held.closedStatus=runtimeStatusOnCommandLane();
            held.completion.complete("no-media-closed");
        }catch(Throwable failure){held.completion.completeExceptionally(failure);}
    }
    static void requireCompletedNoMediaOriginal(EmbeddedDuplexOriginals.Record original,JSONObject receipt)throws Exception{
        if(original==null || !(original.retainedCleanupOwner instanceof RetainedNoMediaClose))
            throw new IllegalStateException("actual retained no-media owner absent");
        RetainedNoMediaClose held=(RetainedNoMediaClose)original.retainedCleanupOwner;
        if(held.proof==null || !held.completion.isDone() || held.completion.isCompletedExceptionally()
                || held.callbackMarker==null || !held.callbackMarker.isDone() || held.callbackMarker.isCompletedExceptionally()
                || held.platform!=null && !held.platform.actualOutboundExchangesJoined()
                || !EmbeddedDuplexCanonicalJson.encode(held.proof).equals(EmbeddedDuplexCanonicalJson.encode(receipt)))
            throw new IllegalStateException("actual no-media original/physical barriers differ");
        for(String key:new String[]{"java_own_capture_cleanup","java_peer_resources_cleanup","java_renderer_cleanup",
                "java_control_cleanup","java_callback_cleanup","command_lane_barrier","whole_app_physical_cleanup","app_process_fence_cleanup"})
            if(!"terminal".equals(receipt.getString(key)))throw new IllegalStateException("actual no-media barrier receipt pending");
        verifyNoMediaNative(held,receipt.getJSONObject("native_retirement"));
        if(!java.util.Arrays.asList(EmbeddedDuplexRuntimeConfigurationBinding.WHOLE_V2,EmbeddedDuplexConstructionClosure.WHOLE).contains(receipt.getString("$schema")))
            throw new IllegalStateException("selected configured whole schema differs");
    }
    private void scheduleNoMedia(RetainedNoMediaClose held){
        commands.schedule(()->continueNoMediaClose(held),50,java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    private static void verifyNoMediaNative(RetainedNoMediaClose held,JSONObject proof)throws Exception{
        if(EmbeddedDuplexConstructionClosure.NATIVE.equals(proof.getString("$schema"))){
            if(held.config==null||held.resources!=null||held.pendingFresh
                    ||!"source-bound-v2".equals(io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY)
                    ||held.configurationContext==null&&proof.getBoolean("provider_entered"))
                throw new IllegalStateException("partial constructor cannot replace an installed Host/finite closure");
            EmbeddedDuplexConstructionClosure.requireNative(proof,new JSONObject().put("app_process_generation",held.appGeneration)
                .put("app_record_sha256",held.appRecord).put("native_executor_generation",held.nativeGeneration).put("config_sha256",held.config));
            return;
        }
        boolean selectedConfigured=held.config!=null && held.configurationContext!=null;
        String requiredSchema=selectedConfigured?EmbeddedDuplexRuntimeConfigurationBinding.NATIVE_V2:"rusty.quest.embedded_duplex.no_media_retirement.v1";
        if(!requiredSchema.equals(proof.getString("$schema"))
                || proof.getLong("app_process_generation")!=held.appGeneration || !held.appRecord.equals(proof.getString("app_record_sha256")))
            throw new IllegalStateException("native no-media identity differs");
        java.util.HashSet<String> actualKeys=new java.util.HashSet<>();proof.keys().forEachRemaining(actualKeys::add);
        java.util.HashSet<String> expectedKeys=new java.util.HashSet<>(java.util.Arrays.asList("$schema","native_runtime_binding","disposition","app_process_generation","app_record_sha256"));
        if(selectedConfigured){expectedKeys.add("configuration_binding");EmbeddedDuplexRuntimeConfigurationBinding.requireNative(proof,held.configurationContext);}
        if(held.nativeGeneration>0)expectedKeys.add("native_executor_generation");
        if(held.config!=null)expectedKeys.add("config_sha256");
        if(held.config!=null && !held.pendingFresh)expectedKeys.add("native_close");
        if(held.pendingFresh)expectedKeys.addAll(java.util.Arrays.asList("transaction_outcome","durable_closure","future_writable_restore"));
        if(!actualKeys.equals(expectedKeys))throw new IllegalStateException("native no-media closed fields differ");
        if(held.pendingFresh){
            if(!"unknown".equals(proof.getString("transaction_outcome"))
                    || !"fenced_pending_supported_owner_reconciliation".equals(proof.getString("future_writable_restore")))
                throw new IllegalStateException("pending fresh remote disposition differs");
            JSONObject ack=proof.getJSONObject("durable_closure");java.util.HashSet<String> ackKeys=new java.util.HashSet<>();ack.keys().forEachRemaining(ackKeys::add);
            if(!ackKeys.equals(new java.util.HashSet<>(java.util.Arrays.asList("$schema","body_sha256","storage_identity","original_binding",
                    "app_process_generation","app_record_sha256","native_executor_generation","config_sha256")))
                    || !"rusty.quest.embedded_duplex.fresh_setup_closure_retained.v1".equals(ack.getString("$schema"))
                    || !ack.getString("body_sha256").matches("sha256:[0-9a-f]{64}") || ack.getString("storage_identity").isEmpty())
                throw new IllegalStateException("pending fresh durable custody ACK differs");
            for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
                if(!EmbeddedDuplexCanonicalJson.sameValue(ack.get(key),proof.get(key)))throw new IllegalStateException("pending fresh custody actor differs");
        }else if(held.config!=null){
            JSONObject closed=proof.getJSONObject("native_close");
            if(!"rusty.quest.embedded_duplex.no_media_closed.v1".equals(closed.getString("$schema"))
                    || !held.config.equals(closed.getString("config_sha256")))throw new IllegalStateException("actual native close receipt differs");
        }
        String expected=held.nativeGeneration==0?"not_claimed":held.config==null?"not_initialized":"configured";
        String disposition=held.nativeGeneration==0?"never_claimed_process_closed":held.config==null?"uninitialized_fence_closed":held.pendingFresh?"pending_fresh_local_closed":"runtime_config_closed";
        if(!expected.equals(proof.getString("native_runtime_binding")) || !disposition.equals(proof.getString("disposition")))
            throw new IllegalStateException("native no-media disposition differs");
        if(held.nativeGeneration==0){if(proof.has("native_executor_generation"))throw new IllegalStateException("invented native generation");}
        else if(proof.getLong("native_executor_generation")!=held.nativeGeneration)throw new IllegalStateException("native no-media generation differs");
        if(held.config==null){if(proof.has("config_sha256"))throw new IllegalStateException("invented config");}
        else if(!held.config.equals(proof.getString("config_sha256")))throw new IllegalStateException("native no-media config differs");
    }

    /** Scoped proof is retained only after actual native finish and Java no-media barriers. */
    private String noMediaWholeProof(String expectedSha) throws Exception {
        JSONObject closed = new JSONObject(ownNoMediaCloseProof);
        if (!expectedSha.equals(ownNoMediaConfigSha256) || !expectedSha.equals(closed.getString("config_sha256"))
                || ownNoMediaExecutorGeneration <= 0L || ownNoMediaAppGeneration != processFence.generation()
                || !"rusty.quest.embedded_duplex.no_media_closed.v1".equals(closed.getString("$schema"))
                || !"peer_subscription_only".equals(closed.getString("cleanup_scope"))
                || !closed.getBoolean("own_app_capture_retained") || platform != null || resources != null
                || nativeExecutorGeneration != 0L || runtimeConfigSha256 != null) {
            throw new IllegalStateException("retained no-media cleanup scope differs");
        }
        return new JSONObject().put("$schema", "rusty.quest.embedded_duplex.concurrent_no_media_own_cleanup.v1")
                .put("action", "whole_app_close").put("config_sha256", expectedSha)
                .put("native_executor_generation", ownNoMediaExecutorGeneration)
                .put("app_process_generation", ownNoMediaAppGeneration)
                .put("native_no_media_close_receipt", closed)
                .put("native_no_media_finish_verified", true)
                .put("java_no_media_barriers_verified", true)
                .put("peer_physical_cleanup", "never_attempted_native_proved")
                .put("native_host_physical_cleanup", "terminal")
                .put("whole_app_physical_cleanup", "pending").toString();
    }

    /** STATUS-only composition; native signed history is not physical cleanup. */
    private static JSONObject receiverPhysicalObservation(EmbeddedDuplexResources retained) {
        if (retained == null) return null;
        try { return retained.receiverPhysicalSnapshot(); }
        catch (Exception unavailable) { return null; }
    }
    private static JSONObject pendingReceiverObservation() throws Exception {
        return new JSONObject().put("$schema", "rusty.quest.embedded_duplex.remote_receiver_observation.v1")
                .put("scope", "peer_receiver_only").put("status", "pending").put("physical_cleanup", "pending")
                .put("pending_reason", "current_receiver_join_unavailable");
    }
    static JSONObject composeRemoteReceiverObservation(JSONObject first, JSONObject current,
            JSONObject before, JSONObject middle, JSONObject after, boolean sameResources,
            long executorGeneration, long appGeneration, String config) throws Exception {
        JSONObject pending = pendingReceiverObservation();
        try {
            JSONObject a = first.getJSONObject("remote_receiver_observation");
            JSONObject b = current.getJSONObject("remote_receiver_observation");
            if (!sameResources || before == null || middle == null || after == null
                    || !before.toString().equals(middle.toString()) || !middle.toString().equals(after.toString())
                    || !a.toString().equals(b.toString())
                    || !"rusty.quest.embedded_duplex.remote_receiver_observation.v1".equals(b.getString("$schema"))
                    || !"peer_receiver_only".equals(b.getString("scope"))
                    || b.getLong("native_executor_generation") != executorGeneration
                    || b.getLong("app_process_generation") != appGeneration || !config.equals(b.getString("config_sha256"))
                    || before.getLong("generation") != executorGeneration || before.getLong("incarnation") <= 0L
                    || !before.getBoolean("terminal") || !before.getBoolean("surface_released")
                    || !before.getBoolean("projection_retired") || !"stopped".equals(before.getString("state"))) {
                current.put("remote_receiver_observation", pending); return current;
            }
            JSONObject history = b.getJSONObject("signed_history");
            JSONObject signed = history.getJSONObject("history");
            if (!"rusty.quest.embedded_duplex.remote_receiver_observation_history.v1".equals(history.getString("$schema"))
                    || history.getLong("executor_generation") != executorGeneration || history.getBoolean("physical_cleanup_claimed")
                    || !"rusty.quest.embedded_duplex.remote_receiver_terminal_history.v1".equals(signed.getString("$schema"))
                    || signed.getLong("executor_generation") != executorGeneration || signed.getBoolean("physical_cleanup_claimed")
                    || !history.getString("replay_sha256").matches("[0-9a-f]{64}")
                    || !history.getString("activation_sha256").matches("[0-9a-f]{64}")) {
                current.put("remote_receiver_observation", pending); return current;
            }
            org.json.JSONArray targets = history.getJSONArray("latest_stop_targets");
            org.json.JSONArray joined = new org.json.JSONArray();
            for (int i = 0; i < targets.length(); i++) {
                JSONObject target = targets.getJSONObject(i);
                if (target.getLong("executor_generation") == executorGeneration
                        && target.getString("provider_handle_id").equals(before.getString("provider_handle_id"))
                        && target.getLong("provider_state_revision") == before.getLong("provider_state_revision")
                        && "stopped".equals(target.getString("observed_state"))) joined.put(target.getString("receipt_id"));
            }
            if (joined.length() != 1) { current.put("remote_receiver_observation", pending); return current; }
            b.put("receiver_before", before).put("receiver_after", after).put("joined_stop_receipt_ids", joined)
                    .put("status", "terminal").put("physical_cleanup", "terminal").put("pending_reason", JSONObject.NULL);
        } catch (org.json.JSONException unavailable) {
            current.put("remote_receiver_observation", pending);
        }
        return current;
    }

    private void finishWholeAppProof(JSONObject proof, String expectedSha, boolean noMedia) throws Exception {
        proof.put("whole_app_physical_cleanup", "pending");
        long[] snapshot = io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoBankControls.INSTANCE.concurrentQualification();
        boolean rendererTerminal = snapshot.length == 160 && snapshot[0] == 1L && snapshot[1] == 160L
                && snapshot[9] == 2L && snapshot[63] == 0L && snapshot[156] == 0L && snapshot[157] == 0L;
        proof.put("java_renderer_cleanup", rendererTerminal ? "terminal" : "pending");
        if (!expectedSha.equals(proof.getString("config_sha256"))
                || proof.getLong("app_process_generation") != processFence.generation()
                || proof.getLong("native_executor_generation") != (noMedia ? ownNoMediaExecutorGeneration : nativeExecutorGeneration))
            throw new IllegalStateException("whole cleanup owner binding differs");
        if (!rendererTerminal || !"terminal".equals(proof.optString("java_own_capture_cleanup"))
                || !"terminal".equals(proof.optString("native_host_physical_cleanup"))) return;
        boolean remoteOwner = !noMedia && "rusty.quest.embedded_duplex.remote_owner_cleanup.v1".equals(proof.getString("$schema"));
        if (remoteOwner && !remoteOwnerClosureVerified(proof, nativeExecutorGeneration)) return;
        if (!noMedia && !remoteOwner && !EmbeddedDuplexWholeCloseRollover.localPeerTerminalProof(proof)) return;
        // The typed native facade only publishes Peer terminal after cleanup succeeds.
        // An empty cleanup array is a real idempotent retry; the native facade preserves retained route/effect receipts.
        if (noMedia) {
            // Never manufacture a seven-owner Stop effect for a native-proved unstarted route.
            noMediaWholeProof(expectedSha);
        } else {
            EmbeddedDuplexPlatform held = platform;
            if (held == null || resources == null || !resources.productResourcesTerminal()) return;
            if(wholeCallbackMarker==null){
                display.detachAfterCleanup(attachmentGeneration, held::closeControlAfterProductCleanup);
                wholeCallbackPlatform=held;
                wholeCallbackMarker=held.retireProcessCallbacksAsync().thenRunAsync(()->{},commands);
            }
            if(wholeCallbackPlatform!=held)throw new IllegalStateException("whole callback owner changed");
            if(!wholeCallbackMarker.isDone())return;
            wholeCallbackMarker.join();
        }
        if (!noMedia && retainedWholeClose != null) {
            OwnStereoCaptureRuntime own = OwnStereoCaptureRuntime.currentForApplication();
            if (own == null || !"terminal".equals(own.physicalCleanupState())) return;
            if (!joinWholeCloseObserver(retainedWholeClose, own)) return;
        }
        proof.put("java_control_cleanup", "terminal");
        String joinedDigest = EmbeddedDuplexProcessFence.digest(proof.toString());
        EmbeddedDuplexStartJournal journal = new EmbeddedDuplexStartJournal(applicationContext);
        String prior = journal.read();
        if (prior != null) {
            JSONObject checkpoint = new JSONObject(prior);
            checkpoint.put("phase", "terminal").put("revision", Math.addExact(checkpoint.getLong("revision"), 1L))
                    .put("last_verified_receipt_sha256", joinedDigest);
            journal.persist(checkpoint.toString());
        }
        processFence.afterVerifiedWholeProductCleanup(checkpointSnapshot(), evidenceSnapshot(), joinedDigest);
        proof.put("app_process_fence_cleanup", "terminal").put("whole_app_physical_cleanup", "terminal").put("status", "terminal");
        java.util.function.Consumer<EmbeddedDuplexWholeCloseRollover> activity = noMedia ? null : wholeCloseActivity;
        EmbeddedDuplexWholeCloseRollover rollover = activity == null ? null
                : EmbeddedDuplexWholeCloseRollover.verified(proof, processFence.epochId(), processFence.generation(),
                        nativeExecutorGeneration, attachmentGeneration, expectedSha, enrollmentRecordSha256);
        lastClosedActorProof=new JSONObject(proof.toString()).put("app_record_sha256",nativeAppRecordSha256);
        EmbeddedDuplexOriginals.forApplication(applicationContext).actualClosedAppOwnerJoined(processFence.generation(),
            nativeExecutorGeneration,nativeAppRecordSha256,proof,retainedWholeClose==null?lastClosedActorProof:retainedWholeClose);
        platform = null; resources = null; runtimeConfigSha256 = null; nativeExecutorGeneration = 0L;
        nativeAppRecordSha256 = null; enrollmentRecordSha256 = null; startIntent.afterVerifiedCleanup();
        ownNoMediaStopTarget = null;
        synchronized (attachmentGate) {
            wholeCloseRollover = rollover; wholeCloseActivity = null;
            attachmentGeneration = 0L; displayDetaching = false; phase.set(Phase.NEW);
        }
        // The callback only reconciles the exact old Activity cache. It cannot change the verified close result.
        if (activity != null) try { activity.accept(rollover); }
        catch (RuntimeException retiredActivity) { /* A destroyed Activity does not become a fresh bootstrap. */ }
    }

    /** A native authenticated remote closure never becomes a local seven-owner Stop. */
    static boolean remoteOwnerClosureVerified(JSONObject proof, long expectedExecutor) throws Exception {
        if (!"rusty.quest.embedded_duplex.remote_owner_cleanup.v1".equals(proof.getString("$schema"))
                || !"whole_app_close".equals(proof.getString("action"))
                || !"not_locally_started".equals(proof.getString("peer_physical_cleanup"))) return false;
        JSONObject history = proof.getJSONObject("remote_receiver_terminal_history");
        org.json.JSONArray ids = history.getJSONArray("receiver_stop_receipt_ids");
        if (expectedExecutor <= 0L || history.getLong("executor_generation") != expectedExecutor
                || !"rusty.quest.embedded_duplex.remote_receiver_terminal_history.v1".equals(history.getString("$schema"))
                || history.getBoolean("physical_cleanup_claimed") || ids.length() == 0
                || history.getInt("verified_response_count") < 2 * ids.length()) return false;
        java.util.HashSet<String> unique = new java.util.HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            if (id.isEmpty() || !unique.add(id)) return false;
        }
        return true;
    }

    private String checkpointSnapshot() throws Exception {
        return new EmbeddedDuplexStartJournal(applicationContext).read();
    }
    private String evidenceSnapshot() throws Exception {
        return new EmbeddedDuplexRecoveryEvidenceJournal(applicationContext).readValidated();
    }
    private void requireAbandonmentEffectFree() {
        // Invoke before Activity attachment; this operation never closes a current effect.
        OwnStereoCaptureRuntime own = OwnStereoCaptureRuntime.currentForApplication();
        if(phase.get()!=Phase.NEW || attachmentGeneration!=0L || displayDetaching || closeInFlight
                || platform!=null || resources!=null || runtimeConfigSha256!=null || nativeExecutorGeneration!=0L
                || pendingReview!=null || (own!=null && !own.pollStopped())
                || !EmbeddedDuplexNative.processIdleForEnrollment())
            throw new IllegalStateException("abandonment requires fresh effect-free process");
    }
    CompletableFuture<String> recoveryStatus(String challenge) {
        return submit(() -> new JSONObject(processFence.recoveryStatus()).put("challenge",challenge).toString());
    }
    CompletableFuture<String> abandonRetainedScope(String challenge,EmbeddedDuplexAbandonmentRequest request) {
        return submit(() -> {
            synchronized(attachmentGate) {
                requireAbandonmentEffectFree();
                EmbeddedDuplexEnrollmentRequest installed=EmbeddedDuplexEnrollmentRequest.localSnapshot(applicationContext,request.role());
                String component=applicationContext.getPackageName()+"/io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity";
                request.requireInstalled(installed.packageId,installed.signerSha256,installedApkDigest.read(applicationContext),
                        component,android.os.Process.myUid(),android.os.Process.myPid());
                if(request.historical()) request.requireCurrent(android.os.Process.myPid(),android.os.Process.myUid(),
                        EmbeddedDuplexHistoricalAbandonment.currentStartTicks(),processFence.generation());
                if(request.crossBoot()) request.requireBoot(new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id")),
                        java.nio.charset.StandardCharsets.US_ASCII).trim());
                String receipt=processFence.abandon(checkpointSnapshot(),evidenceSnapshot(),request,
                        (path,directory)->{
                            android.system.Os.chmod(path.getAbsolutePath(),directory?0700:0600);
                            if(directory) {
                                java.io.FileDescriptor fd=android.system.Os.open(path.getAbsolutePath(),
                                        android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_CLOEXEC,0);
                                try { android.system.Os.fsync(fd); } finally { android.system.Os.close(fd); }
                            }
                        });
                // No runtime initialization, callbacks, grants, sessions, or media command runs here.
                return new JSONObject(receipt).put("challenge",challenge).toString();
            }
        });
    }
    private void requireFreshProcess() {
        if (processFence == null) throw new IllegalStateException("process fence unavailable");
        processFence.requireFresh();
    }
    private void ensureProcessFence() throws Exception {
        if (processFence != null) {
            processFence.requireLive(processFence.generation());
            EmbeddedDuplexOriginals.bindActualAppGeneration(processFence.generation());
            return;
        }
        // Journal constructors validate the private directory and file types.
        String checkpoint = new EmbeddedDuplexStartJournal(applicationContext).readRaw();
        String evidence = new EmbeddedDuplexRecoveryEvidenceJournal(applicationContext).readRaw();
        java.io.File directory = new java.io.File(applicationContext.getNoBackupFilesDir(),
                "embedded-duplex-replay");
        EmbeddedDuplexProcessIncarnation.Identity incarnation=EmbeddedDuplexProcessIncarnation.current(
                android.os.Process.myPid(),android.os.Process.myUid());
        processFence = EmbeddedDuplexProcessFence.acquire(directory, checkpoint, evidence, () -> {
            java.io.FileDescriptor fd = android.system.Os.open(directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY | android.system.OsConstants.O_CLOEXEC, 0);
            try { android.system.Os.fsync(fd); } finally { android.system.Os.close(fd); }
        },incarnation,EmbeddedDuplexProcessIncarnation::kernelStartTicks);
        android.system.Os.chmod(new java.io.File(directory, "process-fence.v1.lock").getAbsolutePath(), 0600);
        EmbeddedDuplexOriginals.bindActualAppGeneration(processFence.generation());
    }

    private interface Work<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> submit(Work<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        EmbeddedDuplexOriginals.Record original = EmbeddedDuplexOriginals.capture();
        EmbeddedDuplexOriginals.commandStageEntered(original);
        try{commands.execute(() -> {
            try (AutoCloseable ignored = EmbeddedDuplexOriginals.enter(original)) {
                ensureProcessFence();
                if(activeCommandOriginal!=null)throw new IllegalStateException("command original overlap");
                activeCommandOriginal=original;
                try{result.complete(action.run());}finally{activeCommandOriginal=null;}
            } catch (Throwable failure) { result.completeExceptionally(failure); }
            finally{EmbeddedDuplexOriginals.commandStageReturned(original);}
        });}catch(java.util.concurrent.RejectedExecutionException rejected){
            EmbeddedDuplexOriginals.commandStageReturned(original);result.completeExceptionally(rejected);
        }
        return result;
    }
    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }
}
