package io.github.mesmerprism.rustyquest.native_renderer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.meta.spatial.toolkit.AppSystemActivity;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Selected SDK presentation adapter. Shell/Coordinator and Rust retain all session authority. */
public abstract class SessionOwnerActivity extends AppSystemActivity {
    private static final ExperimentSessionPanelCoordinator PANEL=new ExperimentSessionPanelCoordinator();
    private static final ConcurrentHashMap<String,Object> COMMAND_OWNERS=new ConcurrentHashMap<String,Object>();
    private static final AtomicLong IDS=new AtomicLong();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile String latestNative="{}";
    private static volatile ControlPanelActivity.SdkAudioCleanup terminalCleanup;
    private static final ExperimentSessionAndroidShell SHELL=ExperimentSessionAndroidShell.installProcess(
        new ExperimentSessionAndroidShell.NativeBridge() {
            public String initialize(String root) { return ControlPanelActivity.initializeExperimentSessionRuntimeFromOwner(root); }
            public String apply(String raw) {
                try {
                    final JSONObject command=new JSONObject(raw);
                    if("status".equals(command.optString("operation"))) {
                        String response=ControlPanelActivity.applyExperimentSessionCommand(raw);latestNative=response;return response;
                    }
                    Object token=COMMAND_OWNERS.get(command.optString("operation_id"));
                    final String[] response={rejected("sdk-queued-command-owner-retired")};
                    boolean active="official-start".equals(command.optString("operation"))||"resume".equals(command.optString("operation"));
                    SdkExperimentSessionCore.runForAudioOwner(token,command.optLong("expected_generation",-1),active,new Runnable(){
                        public void run(){response[0]=ControlPanelActivity.applyExperimentSessionCommand(command.toString());}
                    });
                    return response[0];
                } catch(Exception error){return rejected("sdk-command-invalid");}
            }
            private Object stopToken(String operation) {
                String key=operation.endsWith("-audio-stop")?operation.substring(0,operation.length()-11):operation;
                return COMMAND_OWNERS.get(key);
            }
            public boolean requestAudioStop(final long generation,final String operation) {
                final boolean[] result={false};SdkExperimentSessionCore.runForAudioOwner(stopToken(operation),generation,false,new Runnable(){
                    public void run(){result[0]=ControlPanelActivity.requestConditionAudioStop(generation,operation);if(result[0])terminalCleanup=ControlPanelActivity.captureSdkAudioCleanup();}});return result[0];
            }
            public boolean requestAudioRestartStop(final long generation,final String operation) {
                final boolean[] result={false};SdkExperimentSessionCore.runForAudioOwner(stopToken(operation),generation,false,new Runnable(){
                    public void run(){result[0]=ControlPanelActivity.requestConditionAudioRestartStop(generation,operation);}});return result[0];
            }
            public String audioShutdownStatus(){return ControlPanelActivity.conditionAudioShutdownStatus();}
            public boolean cleanupAfterAcknowledgement(){ControlPanelActivity.SdkAudioCleanup captured=terminalCleanup;if(captured==null)return false;captured.run();return captured.succeeded();}
        },new ExperimentSessionJsonCodec(new java.util.function.Supplier<ExperimentSessionPanelCoordinator>(){
            public ExperimentSessionPanelCoordinator get(){return PANEL;}}),new ExperimentSessionAndroidShell.Clock(){
            public long elapsedRealtimeMillis(){return SystemClock.elapsedRealtime();}
            public long elapsedRealtimeNanos(){return SystemClock.elapsedRealtimeNanos();}
            public long utcNanos(){return System.currentTimeMillis()*1000000L;}
        },new ExperimentSessionAndroidShell.UiDispatcher(){
            public void post(Runnable work){MAIN.post(work);}
            public boolean isUiThread(){return Looper.myLooper()==Looper.getMainLooper();}
        });
    private final ScheduledExecutorService dispatch=Executors.newSingleThreadScheduledExecutor();
    private final SdkOwnerLifecycle lifecycle=new SdkOwnerLifecycle();
    private final AtomicBoolean pollPending=new AtomicBoolean();
    private final AtomicBoolean inputDraining=new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> latestInput=new java.util.concurrent.atomic.AtomicReference<Runnable>();
    private volatile boolean destroyed,initialized;
    private volatile long sdkEpoch,retiredEpoch;
    private long observedInputEpoch,observedInputIdentity;
    private volatile boolean terminalRevoked;
    private volatile Sink sink;
    private volatile String ownerResult="initializing",proofResult="{}",inputResult="{}";
    private volatile ExperimentSessionUiControlHandoff handoff;
    private final SdkPendingCommand pendingCommands=new SdkPendingCommand();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SdkExperimentSessionCore.captureAudioOwnerToken(); // Installs SDK scope before any audio owner.
        ControlPanelActivity.prepareSdkOwner(getApplicationContext());
        sink=new Sink(null);SHELL.initialize(getFilesDir().getAbsolutePath(),sink);
    }
    public final boolean ownerSelected(){return true;}
    public final String ownerBind(final long epoch,final long instance,final long session) {
        if(destroyed||epoch<=0||epoch==Long.MAX_VALUE||instance==0||session==0)return rejected("sdk-binding-invalid");
        final SdkOwnerLifecycle.Attempt attempt=lifecycle.begin(epoch,instance,session);
        if(attempt==null)return rejected("sdk-binding-retired");
        dispatch.execute(new Runnable(){public void run(){runBinding(attempt);}});return ownerStatus();
    }
    private void retryBinding(final SdkOwnerLifecycle.Attempt attempt){
        if(lifecycle.scheduleRetry(attempt))dispatch.schedule(new Runnable(){public void run(){
            if(lifecycle.resumeRetry(attempt))runBinding(attempt);
        }},100,TimeUnit.MILLISECONDS);
    }
    private void runBinding(final SdkOwnerLifecycle.Attempt attempt){
        if(!lifecycle.current(attempt))return;
        if(!initialized){
            lifecycle.runCurrent(attempt,new Runnable(){public void run(){ownerResult="owner-initializing-retry-real-binding";}});
            retryBinding(attempt);return;
        }
        lifecycle.runCurrent(attempt,new Runnable(){public void run(){
            if(handoff!=null){handoff.cancel();handoff=null;}
            pendingCommands.retireToken(SdkExperimentSessionCore.captureAudioOwnerToken());
        }});
        // Preparation and seek wait on the background executor with no lifecycle monitor held.
        final String result=SdkExperimentSessionCore.bindScene(attempt.epoch,attempt.instance,attempt.session,lifecycle,attempt);
        try{
            JSONObject value=new JSONObject(result);
            if("accepted".equals(value.optString("status"))){
                lifecycle.publish(attempt,new Runnable(){public void run(){
                    ownerResult=result;sdkEpoch=attempt.epoch;retiredEpoch=0;terminalRevoked=false;
                    Sink old=sink;if(old!=null)SHELL.detach(old);
                    sink=new Sink(SdkExperimentSessionCore.captureAudioOwnerToken());SHELL.attach(sink);SHELL.requestStatus(sink);
                }});
            }else{
                lifecycle.runCurrent(attempt,new Runnable(){public void run(){ownerResult=result;}});
                if("audio-owner-transfer-not-quiescent".equals(value.optString("reason")))retryBinding(attempt);
                else lifecycle.failed(attempt);
            }
        }catch(Exception unknown){lifecycle.failed(attempt);}
    }
    public final String ownerRevoke(long epoch,final boolean terminal) {
        synchronized(lifecycle){
            String outcome=lifecycle.revoke(epoch,terminal,new SdkOwnerLifecycle.Revoker(){public String revoke(long actualEpoch){
            final ExperimentSessionUiControlHandoff retiredHandoff=handoff;
            if(retiredHandoff!=null)dispatch.execute(new Runnable(){public void run(){
                retiredHandoff.cancel();if(handoff==retiredHandoff)handoff=null;
            }});
            final Object token=SdkExperimentSessionCore.captureAudioOwnerToken();
            pendingCommands.retireToken(token);
            try{
                final JSONObject current=nativeState();final long generation=current.getLong("generation");
                if("running".equals(current.optString("control_state"))) {
                    ExperimentSessionControlCapability.Fence fence=new ExperimentSessionControlCapability.Fence("",current.getString("session_key"),current.getString("condition_id"),
                        current.getString("non_audio_profile_sha256"),current.getString("activation_binding_sha256"),"pause",generation,current.getLong("runtime_epoch"),0,0,0);
                    final JSONObject pause=fence.nativeCommand("sdk-lifecycle-pause-"+IDS.incrementAndGet(),SystemClock.elapsedRealtimeNanos()).put("operator_origin","ui-session-v2");
                    SdkExperimentSessionCore.runForAudioOwner(token,generation,false,new Runnable(){public void run(){ControlPanelActivity.applyExperimentSessionCommand(pause.toString());}});
                }
            }catch(Exception unavailable){ownerResult="lifecycle-pause-not-confirmed";}
            final Runnable[] cleanup={null};
            if(terminal)SdkExperimentSessionCore.runForAudioOwner(token,0,false,new Runnable(){public void run(){
                ControlPanelActivity.stopCurrentConditionAudioForTerminal(true);
                cleanup[0]=ControlPanelActivity.captureSdkAudioCleanup();
            }});
            String result=SdkExperimentSessionCore.revokeScene(actualEpoch,terminal);sdkEpoch=0;retiredEpoch=actualEpoch;terminalRevoked=terminal;ownerResult=result;
            if(cleanup[0]!=null)dispatch.execute(cleanup[0]); // Captured resources; never await under delivery lock.
            if(!terminal)dispatch.schedule(new Runnable(){public void run(){ownerPoll();}},100,TimeUnit.MILLISECONDS);
            return result;
            }});
            ownerResult=outcome;return outcome;
        }
    }
    public final void ownerPoll(){
        if(destroyed||!pollPending.compareAndSet(false,true))return;
        dispatch.execute(new Runnable(){public void run(){try{
            long epoch=sdkEpoch>0?sdkEpoch:retiredEpoch;if(epoch>0)proofResult=SdkExperimentSessionCore.poll(epoch);
            Sink target=sink;if(target!=null)SHELL.requestStatus(target);
            SdkPendingCommand.Pending pending=pendingCommands.current();
            if(pending!=null)pendingCommands.observe(pending,nativeState());
        }catch(Exception unavailable){ownerResult="owner-poll-unavailable";}finally{pollPending.set(false);if(!destroyed&&sdkEpoch==0&&retiredEpoch>0&&!terminalRevoked)dispatch.schedule(new Runnable(){public void run(){ownerPoll();}},100,TimeUnit.MILLISECONDS);}}});
    }
    public final String ownerStatus(){
        try{return new JSONObject().put("selected",true).put("initialized",initialized).put("sdk_epoch",sdkEpoch)
            .put("pending_requested_epoch",lifecycle.requestedEpoch()).put("native_bound_epoch",lifecycle.actualEpoch())
            .put("currently_bound_epoch",lifecycle.publishedEpoch()).put("bind_result_phase",lifecycle.phase()).put("command_outcome",pendingCommands.outcome())
            .put("owner_result",ownerResult).put("native",new JSONObject(latestNative)).put("proof",new JSONObject(proofResult))
            .put("input",new JSONObject(inputResult)).put("panel_phase",PANEL.snapshot().phase.toString()).toString();
        }catch(Exception unavailable){return rejected("owner-status-unavailable");}
    }
    public final String ownerAction(final String action,final int biasPct){
        if(destroyed||biasPct<0||biasPct>100)return rejected("sdk-action-invalid");
        final Object token=SdkExperimentSessionCore.captureAudioOwnerToken();
        dispatch.execute(new Runnable(){public void run(){
            try{
                if(!SdkExperimentSessionCore.isAudioOwnerCurrent(token,0,false)||sdkEpoch==0)throw new IllegalStateException("sdk-owner-retired");
                if(pendingCommands.current()!=null||!PANEL.snapshot().pendingOperationId.isEmpty()||(handoff!=null&&handoff.pending()))throw new IllegalStateException("session-operation-pending");
                if("arm-a".equals(action)||"arm-b".equals(action)){
                    String condition="arm-a".equals(action)?ExperimentSessionPanelCoordinator.CONDITION_ONE:ExperimentSessionPanelCoordinator.CONDITION_TWO;
                    if(!"track-ready".equals(ControlPanelActivity.conditionAudioReadiness(condition)))throw new IllegalStateException("audio-not-ready");
                    ExperimentSessionPanelCoordinator.NativeCommand command=PANEL.arm(condition,
                        "pattern-ready".equals(ControlPanelActivity.conditionBreathGuidanceReadiness(condition))?biasPct:0);
                    submit(command,true,token);
                }else if("save-next".equals(action)){
                    if(!ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(PANEL.snapshot()))throw new IllegalStateException("save-not-admitted");
                    submit(PANEL.restartToExperimenter(PANEL.allocateRouteEvent()),false,token);
                }else if("start".equals(action)||"pause".equals(action)||"resume".equals(action)){
                    final JSONObject current=nativeState();
                    final ExperimentSessionControlCapability.Fence fence=new ExperimentSessionControlCapability.Fence("",current.getString("session_key"),current.getString("condition_id"),
                        current.getString("non_audio_profile_sha256"),current.getString("activation_binding_sha256"),action,current.getLong("generation"),current.getLong("runtime_epoch"),0,0,0);
                    final String id="sdk-ui-"+IDS.incrementAndGet();
                    if("pause".equals(action))dispatchOperator(token,fence,id,fence.nativeCommand(id,SystemClock.elapsedRealtimeNanos()).put("operator_origin","ui-session-v2"));
                    else{
                        handoff=new ExperimentSessionUiControlHandoff(new ExperimentSessionUiControlHandoff.Owner(){
                            public JSONObject read()throws Exception{if(!SdkExperimentSessionCore.isAudioOwnerCurrent(token,fence.generation,false))throw new IllegalStateException("owner-retired");return nativeState();}
                            public void returnVr(){presentation(token,fence.generation,"immersive");}
                            public void dispatch(JSONObject command)throws Exception{dispatchOperator(token,fence,id,command);}
                            public void schedule(final Runnable callback,long delay){dispatch.schedule(new Runnable(){public void run(){
                                if(!SdkExperimentSessionCore.runForAudioOwner(token,fence.generation,false,callback)&&callback instanceof ExperimentSessionUiControlHandoff){
                                    ExperimentSessionUiControlHandoff retired=(ExperimentSessionUiControlHandoff)callback;retired.cancel();if(handoff==retired)handoff=null;
                                }
                            }},delay,TimeUnit.MILLISECONDS);}
                            public long nowMs(){return SystemClock.elapsedRealtime();}public long nowNs(){return SystemClock.elapsedRealtimeNanos();}
                            public void outcome(final String value){SdkExperimentSessionCore.runForAudioOwner(token,fence.generation,false,new Runnable(){public void run(){ownerResult=value;}});}
                        },fence,id);handoff.run();
                    }
                }else throw new IllegalArgumentException("unsupported-session-action");
            }catch(Exception failure){ownerResult="action-not-confirmed: "+failure.getMessage();}
        }});return ownerStatus();
    }
    private void submit(ExperimentSessionPanelCoordinator.NativeCommand command,boolean arm,Object token){
        if(command==null)throw new IllegalStateException("coordinator-rejected");
        if(COMMAND_OWNERS.size()>=4096)throw new IllegalStateException("command-owner-capacity");
        COMMAND_OWNERS.put(command.operationId,token);SHELL.submit(command,arm,sink);
    }
    private void dispatchOperator(Object token,ExperimentSessionControlCapability.Fence fence,String id,final JSONObject command)throws Exception{
        final String[] result={rejected("sdk-owner-retired")};
        final SdkPendingCommand.Pending pending=pendingCommands.install(token,fence,id);
        if(!SdkExperimentSessionCore.runForAudioOwner(token,command.getLong("expected_generation"),!"pause".equals(command.optString("operation")),new Runnable(){
            public void run(){result[0]=ControlPanelActivity.applyExperimentSessionCommand(command.toString());}})){
            pendingCommands.settle(pending,"owner-retired-outcome-unknown-no-replay");throw new IllegalStateException("sdk-owner-retired");
        }
        JSONObject response=new JSONObject(result[0]);
        if(!"queued".equals(response.optString("command_status"))){
            if("rejected".equals(response.optString("command_status"))||"rejected".equals(response.optString("status")))pendingCommands.settle(pending,"rejected");
            throw new IllegalStateException("dispatch-not-queued-no-replay");
        }
        pendingCommands.ifCurrent(pending,new Runnable(){public void run(){ownerResult="native-queued-not-applied";}});
    }
    private JSONObject nativeState()throws Exception{
        JSONObject response=new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(new JSONObject().put("schema",ExperimentSessionPanelCoordinator.SCHEMA).put("operation","status").toString()));
        latestNative=response.toString();JSONObject state=response.getJSONObject("readback");
        JSONObject composition=new JSONObject(ControlPanelActivity.nativeReadBreathCompositionStatus()).getJSONObject("snapshot");
        String binding=composition.optString("activation_binding_sha256","");
        if(!binding.matches("[a-f0-9]{64}"))throw new IllegalStateException("composition-unproven");
        return state.put("activation_binding_sha256",binding);
    }
    public final void ownerController(final long epoch,final long entityId,final long sequence,final long requestedElapsedNs,final long acquiredElapsedNs,
        final long flags,final boolean active,final boolean tracked,final boolean rightSecondaryPressed,final double x,final double y,final double z,final double qx,final double qy,final double qz,final double qw){
        if(destroyed)return;
        boolean malformed=!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Double.isFinite(qx)||!Double.isFinite(qy)||!Double.isFinite(qz)||!Double.isFinite(qw)
            ||qx*qx+qy*qy+qz*qz+qw*qw<1e-12||sequence<=0||sequence==Long.MAX_VALUE||requestedElapsedNs<=0||requestedElapsedNs>acquiredElapsedNs||acquiredElapsedNs==Long.MAX_VALUE;
        synchronized(lifecycle){
            if(epoch!=sdkEpoch||epoch<=0)return;
            if(!active||!tracked||entityId==0||malformed||observedInputEpoch!=epoch||observedInputIdentity!=entityId){
                // Known loss/source replacement closes readiness before queued actions can run.
                SdkExperimentSessionCore.controllerInputSource(epoch,0);
            }
            observedInputEpoch=epoch;observedInputIdentity=entityId;
            if(active&&tracked&&entityId!=0&&!malformed){
                // Button acquisition is lossless and independent of coalesced pose work.
                SdkExperimentSessionCore.controllerInputSource(epoch,entityId);
                SdkExperimentSessionCore.controllerButtonSample(epoch,sequence,acquiredElapsedNs,rightSecondaryPressed);
            }
        }
        latestInput.set(new Runnable(){public void run(){
            if(epoch!=sdkEpoch||epoch<=0)return;
            String source=SdkExperimentSessionCore.controllerInputSource(epoch,entityId);
            boolean sourceAccepted=false;try{sourceAccepted="accepted".equals(new JSONObject(source).optString("status"));}catch(Exception ignored){}
            boolean timing=sourceAccepted&&requestedElapsedNs>0&&requestedElapsedNs<=acquiredElapsedNs&&acquiredElapsedNs<Long.MAX_VALUE;
            String result=SdkExperimentSessionCore.controllerPose(epoch,sequence,acquiredElapsedNs,x,y,z,qx,qy,qz,qw,active&&timing,tracked&&timing,rightSecondaryPressed&&active&&tracked&&timing);
            try{inputResult=new JSONObject().put("source","sdk-controller-pose").put("entity_id_bits",Long.toUnsignedString(entityId)).put("sequence",sequence)
                .put("requested_elapsed_ns",requestedElapsedNs).put("acquired_elapsed_ns",acquiredElapsedNs).put("flags",flags).put("source_reset",new JSONObject(source)).put("assessment",new JSONObject(result)).toString();}
            catch(Exception ignored){}
        }});scheduleInputDrain();
    }
    private void scheduleInputDrain(){
        if(destroyed||!inputDraining.compareAndSet(false,true))return;
        dispatch.execute(new Runnable(){public void run(){try{Runnable sample=latestInput.getAndSet(null);if(sample!=null)sample.run();}
            finally{inputDraining.set(false);if(latestInput.get()!=null)scheduleInputDrain();}}});
    }
    private void presentation(final Object token,final long generation,final String route){
        MAIN.post(new Runnable(){public void run(){SdkExperimentSessionCore.runForAudioOwner(token,generation,false,new Runnable(){
            public void run(){if(!destroyed)onOwnerPresentationRequested(route);}});}});
    }
    protected void onOwnerPresentationRequested(String route) { }
    @Override protected void onDestroy(){
        synchronized(lifecycle){
            long epoch=lifecycle.revokeEpoch();if(epoch>0)ownerRevoke(epoch,true);
            destroyed=true;lifecycle.destroyed();
        }
        Sink previous=sink;if(previous!=null)SHELL.detach(previous);dispatch.shutdown();super.onDestroy();
    }
    private final class Sink implements ExperimentSessionAndroidShell.UiSink {
        final Object token;Sink(Object token){this.token=token;}
        public boolean acceptsExperimentSessionCallbacks(long launch){return !destroyed&&sink==this&&SHELL.isCurrentUiAttachment(this,launch);}
        public void onExperimentSessionReadback(final ExperimentSessionAndroidShell.SessionReadback value){
            if(value==null||value.receipt==null||!acceptsExperimentSessionCallbacks(SHELL.currentLaunchEpochForUi()))return;
            initialized="ready".equals(value.initializationStatus);
            Runnable apply=new Runnable(){public void run(){
                PANEL.acceptRuntimeEpoch(value.runtimeEpoch);PANEL.acceptNativeReadback(value.runtimeEpoch,value.receipt,value.statusReadCompletedNanos,SystemClock.elapsedRealtimeNanos(),"accepted".equals(value.commandStatus),value.armAcceptance);
                ExperimentSessionPanelState state=PANEL.snapshot();
                if(token!=null&&state.phase==ExperimentSessionPanelState.Phase.ARMING&&state.recording)
                    ControlPanelActivity.startConditionAudio(state.generation,"session-arm-"+state.generation,state.activeCondition);
            }};
            if(token==null)apply.run();else SdkExperimentSessionCore.runForAudioOwner(token,value.receipt.generation,false,apply);
        }
        public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult result){
            if(result!=null&&token!=null&&result.shouldFinish)presentation(token,result.sessionGeneration,"finished");
        }
    }
    private static String rejected(String reason){try{return new JSONObject().put("status","rejected").put("reason",reason).toString();}catch(Exception impossible){return "{}";}}
}
