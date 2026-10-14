package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;
import org.json.JSONObject;

/** Standalone presentation adapter for the existing process shell and native session owner. */
public class ExperimentSessionOwnerPanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID = "experiment-session-owner";
    public String panelModuleId() { return MODULE_ID; }
    private boolean destroyed;
    private AutoCloseable panelAttachment;
    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24,24,24,24);
        setContentView(content);
        panelAttachment=attachNativeMeshSessionControls(this, content, new java.util.function.BooleanSupplier() {
            @Override public boolean getAsBoolean() { return !destroyed; }
        });
    }
    @Override protected void onDestroy() { destroyed=true; try { if(panelAttachment!=null)panelAttachment.close(); } catch(Exception ignored){} super.onDestroy(); }
    private static final ExperimentSessionPanelCoordinator EXPERIMENT_SESSION_PANEL =
        new ExperimentSessionPanelCoordinator();
    private static final ExperimentSessionAndroidShell EXPERIMENT_SESSION_SHELL =
        ExperimentSessionAndroidShell.installProcess(
            new ExperimentSessionAndroidShell.NativeBridge() {
                @Override public String initialize(String appPrivateFilesRoot) {
                    return ControlPanelActivity.initializeExperimentSessionRuntimeFromOwner(
                        appPrivateFilesRoot);
                }

                @Override public String apply(String commandJson) {
                    return ControlPanelActivity.applyExperimentSessionCommand(commandJson);
                }

                @Override public boolean requestAudioStop(
                        long sessionGeneration, String operationId) {
                    return ControlPanelActivity.requestConditionAudioStop(
                        sessionGeneration, operationId);
                }

                @Override public boolean requestAudioRestartStop(
                        long sessionGeneration, String operationId) {
                    return ControlPanelActivity.requestConditionAudioRestartStop(
                        sessionGeneration, operationId);
                }

                @Override public String audioShutdownStatus() {
                    return ControlPanelActivity.conditionAudioShutdownStatus();
                }
                @Override public boolean validateLocalTerminalScope(ExperimentSessionAndroidShell.LocalTerminalScope scope){
                    try{
                        if(ControlPanelActivity.nativeReadExperimentSessionImmersiveOwnerIdentity()!=scope.ownerId)return false;
                        JSONObject state=processNativeState();
                        if(scope.idle){
                            JSONObject recording=state.optJSONObject("recording_result");
                            String status=recording==null?"":recording.optString("status","");
                            if(!"idle".equals(state.optString("phase"))||!("none".equals(status)||"saved".equals(status))
                                ||scope.idleWithoutRecording!="none".equals(status))return false;
                        }
                        return state.optLong("runtime_epoch",-1)==scope.runtimeEpoch&&state.optLong("generation",-1)==scope.sessionGeneration
                            &&scope.sessionKey.equals(nullableIdentity(state,"session_key"))&&scope.condition.equals(nullableIdentity(state,"condition_id"))
                            &&scope.profileSha256.equals(nullableIdentity(state,"non_audio_profile_sha256"))&&scope.bindingSha256.equals(state.optString("activation_binding_sha256"));
                    }catch(Exception unavailable){return false;}
                }
                @Override public boolean admitLocalTerminal(ExperimentSessionAndroidShell.LocalTerminalScope scope){return ControlPanelActivity.admitStandaloneTerminal(scope);}
                @Override public String applyLocalTerminal(String command,ExperimentSessionAndroidShell.LocalTerminalScope scope){
                    return ControlPanelActivity.nativeApplyExperimentSessionTerminalWithFence(command,scope.ownerId,scope.runtimeEpoch,scope.sessionGeneration,scope.sessionKey,scope.condition,scope.profileSha256,scope.bindingSha256);
                }
                @Override public boolean cleanupAfterAcknowledgement() {
                    return ControlPanelActivity.closeExperimentResourcesFromOwner();
                }
            },
            new ExperimentSessionJsonCodec(new java.util.function.Supplier<ExperimentSessionPanelCoordinator>() {
                @Override public ExperimentSessionPanelCoordinator get() { return EXPERIMENT_SESSION_PANEL; }
            }),
            new ExperimentSessionAndroidShell.Clock() {
                @Override public long elapsedRealtimeMillis() {
                    return SystemClock.elapsedRealtime();
                }

                @Override public long elapsedRealtimeNanos() {
                    return SystemClock.elapsedRealtimeNanos();
                }

                @Override public long utcNanos() {
                    return System.currentTimeMillis() * 1_000_000L;
                }
            },
            new ExperimentSessionAndroidShell.UiDispatcher() {
                private final Handler main = new Handler(Looper.getMainLooper());

                @Override public void post(Runnable runnable) {
                    main.post(runnable);
                }

                @Override public boolean isUiThread() {
                    return Looper.myLooper() == Looper.getMainLooper();
                }
            }
        );
    static boolean isLocalTerminalScopeCurrent(ExperimentSessionAndroidShell.LocalTerminalScope scope){return EXPERIMENT_SESSION_SHELL.isLocalTerminalScopeCurrent(scope);}
    /** Native mesh presentation adapter; the existing process shell and reducer retain authority. */
    public static AutoCloseable attachNativeMeshSessionControls(Activity activity, LinearLayout layout,
            java.util.function.BooleanSupplier current) {
        ControlPanelActivity.prepareExperimentSessionAppOwner(activity.getApplicationContext());
        return new PresentationAttachment(activity,layout,current);
    }
    /** Recreates only a retired presentation sink when its actual host resumes. */
    private static final class PresentationAttachment implements AutoCloseable,android.app.Application.ActivityLifecycleCallbacks {
        final Activity activity;final LinearLayout layout;final java.util.function.BooleanSupplier current;
        NativeMeshSessionControls controls;boolean closed;
        PresentationAttachment(Activity activity,LinearLayout layout,java.util.function.BooleanSupplier current){
            this.activity=activity;this.layout=layout;this.current=current;
            try{replace();activity.getApplication().registerActivityLifecycleCallbacks(this);}
            catch(RuntimeException failure){close();throw failure;}
            catch(Error failure){close();throw failure;}
        }
        void replace(){
            if(closed||!current.getAsBoolean()||activity.isFinishing()||activity.isDestroyed())return;
            if(controls!=null)controls.close();
            controls=new NativeMeshSessionControls(activity,layout,current);
            try{controls.start();}
            catch(RuntimeException failure){controls.close();throw failure;}
            catch(Error failure){controls.close();throw failure;}
        }
        public void close(){if(closed)return;closed=true;activity.getApplication().unregisterActivityLifecycleCallbacks(this);if(controls!=null){controls.close();controls=null;}}
        public void onActivityResumed(Activity value){
            if(value!=activity||closed||!current.getAsBoolean())return;
            if(controls==null||!controls.attached()){
                try{replace();}catch(RuntimeException failure){android.util.Log.e("RQNativeRenderer","event=panel-reattach-failed",failure);}
            }
        }
        public void onActivityCreated(Activity value,android.os.Bundle saved){}
        public void onActivityStarted(Activity value){}
        public void onActivityPaused(Activity value){}
        public void onActivityStopped(Activity value){}
        public void onActivitySaveInstanceState(Activity value,android.os.Bundle saved){}
        public void onActivityDestroyed(Activity value){if(value==activity)close();}
    }

    // Presentation continuation only; native runtime and the one Shell retain command authority.
    private static ProcessHandoff processHandoff;
    private static ExperimentSessionUiCommandReceipt processCommand;
    private static String nullableIdentity(JSONObject state,String key){return state.isNull(key)?"":state.optString(key,"");}
    private static JSONObject processNativeState() throws Exception {
        JSONObject n=new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(
            new JSONObject().put("schema",ExperimentSessionPanelCoordinator.SCHEMA).put("operation","status").toString())).getJSONObject("readback");
        JSONObject b=new JSONObject(ControlPanelActivity.nativeReadBreathCompositionStatus()).getJSONObject("snapshot");
        if(!Boolean.TRUE.equals(b.opt("feature_lock_active"))||!Boolean.TRUE.equals(b.opt("activation_binding_matches")))throw new SecurityException("feature-inactive");
        return n.put("activation_binding_sha256",b.getString("activation_binding_sha256"));
    }
    private static void dispatchProcessOperator(ExperimentSessionControlCapability.Fence fence,String id,JSONObject command)throws Exception{
        if(processCommand!=null)throw new SecurityException("operator-command-pending");
        processCommand=new ExperimentSessionUiCommandReceipt(fence,id);
        JSONObject response=new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(command.toString()));
        if("rejected".equals(response.optString("command_status"))){processCommand=null;throw new SecurityException("native-command-rejected");}
        if(!"queued".equals(response.optString("command_status")))throw new SecurityException("native-dispatch-outcome-unknown-no-replay");
    }
    private static final class ProcessHandoff implements ExperimentSessionUiControlHandoff.Owner {
        final java.lang.ref.WeakReference<NativeMeshSessionControls> origin;
        final long launchEpoch;final ExperimentSessionControlCapability.Fence fence;final String id;
        final Handler callbacks=new Handler(Looper.getMainLooper());final ExperimentSessionUiControlHandoff task;
        final ExperimentSessionUiControlHandoff.TransferScope scope;
        ProcessHandoff(NativeMeshSessionControls source,ExperimentSessionControlCapability.Fence fence,String id){
            origin=new java.lang.ref.WeakReference<NativeMeshSessionControls>(source);launchEpoch=source.epoch;this.fence=fence;this.id=id;
            scope=new ExperimentSessionUiControlHandoff.TransferScope(launchEpoch);
            task=new ExperimentSessionUiControlHandoff(this,fence,id);
        }
        boolean pending(){return task.pending();}
        boolean from(NativeMeshSessionControls value){return origin.get()==value;}
        void cancel(){scope.retire(true);task.cancel();callbacks.removeCallbacksAndMessages(null);if(processHandoff==this)processHandoff=null;}
        void requireCurrent(){
            NativeMeshSessionControls source=origin.get();
            if(processHandoff!=this||!scope.current(EXPERIMENT_SESSION_SHELL.currentLaunchEpochForUi(),source!=null&&source.live()))throw new SecurityException("handoff-owner-retired");
        }
        public JSONObject read()throws Exception{requireCurrent();return processNativeState();}
        public void returnVr(){
            requireCurrent();NativeMeshSessionControls source=origin.get();
            if(source!=null&&source.activity instanceof ControlPanelActivity){
                scope.transfer();
                try{ControlPanelActivity.closePanelAndReturnToImmersive(source.activity);}
                catch(RuntimeException failure){scope.abortTransfer();throw failure;}
            }
        }
        public void dispatch(JSONObject request)throws Exception{requireCurrent();dispatchProcessOperator(fence,id,request);}
        public void schedule(Runnable run,long delay){callbacks.postDelayed(run,delay);}
        public long nowMs(){return SystemClock.elapsedRealtime();}public long nowNs(){return SystemClock.elapsedRealtimeNanos();}
        public void outcome(String value){
            NativeMeshSessionControls source=origin.get();if(!scope.transferred()&&source!=null&&source.live())source.panel.outcome(value);
            android.util.Log.i("RQNativeRenderer","channel=session-control origin=ui-session-v2 outcome="+value);
            if(!task.pending()&&processHandoff==this)processHandoff=null;
        }
    }

    private static final class NativeMeshSessionControls implements ExperimentSessionAndroidShell.UiSink, NativeLocalControlPanel.Host, AutoCloseable {
        final Activity activity;
        final LinearLayout layout;
        final java.util.function.BooleanSupplier current;
        final Handler handler = new Handler(Looper.getMainLooper());
        final NativeLocalControlPanel panel;
        boolean closed, hostResumed=true;
        final android.app.Application.ActivityLifecycleCallbacks lifecycle=new android.app.Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity value,android.os.Bundle saved){}
            public void onActivityStarted(Activity value){}
            public void onActivityResumed(Activity value){if(value==activity&&!closed){hostResumed=true;panel.resume();EXPERIMENT_SESSION_SHELL.replayCurrentTerminal(NativeMeshSessionControls.this,epoch);EXPERIMENT_SESSION_SHELL.requestStatus(NativeMeshSessionControls.this);}}
            public void onActivityPaused(Activity value){if(value==activity&&!closed){hostResumed=false;panel.suspend();retireHandoff(false);}}
            public void onActivityStopped(Activity value){}
            public void onActivitySaveInstanceState(Activity value,android.os.Bundle saved){}
            public void onActivityDestroyed(Activity value){if(value==activity)close();}
        };
        long epoch;
        int bias=-1;
        NativeMeshSessionControls(Activity activity, LinearLayout layout,
                java.util.function.BooleanSupplier current) {
            this.activity=activity; this.layout=layout; this.current=current;
            panel=new NativeLocalControlPanel(layout.getContext(),activity.getApplicationContext(),this);
            layout.addView(panel.view(),new LinearLayout.LayoutParams(-1,0,1));
        }
        public boolean current(){return live();}
        public void outcome(String value){if(live())panel.outcome(value);}
        public ExperimentSessionPanelState state(){return EXPERIMENT_SESSION_PANEL.snapshot();}
        public boolean commandReady(){return live()&&!EXPERIMENT_SESSION_SHELL.localTerminalPending()&&processCommand==null&&state().pendingOperationId.isEmpty()&&(processHandoff==null||!processHandoff.pending());}
        public boolean settingsReady(){return live()&&!EXPERIMENT_SESSION_SHELL.localTerminalPending();}
        public boolean canSaveExit(){return live()&&(EXPERIMENT_SESSION_SHELL.localTerminalPending()?EXPERIMENT_SESSION_SHELL.localTerminalRetryReady():commandReady()&&"ready".equals(state().storageStatus)&&state().phase!=ExperimentSessionPanelState.Phase.SAVING&&state().phase!=ExperimentSessionPanelState.Phase.FINALIZING);}
        public void action(String operation){dispatch(operation);}
        public void navigationChanged(){retireHandoff(true);}
        void retireHandoff(boolean navigation){if(processHandoff!=null&&processHandoff.from(this)&&processHandoff.scope.retire(navigation))processHandoff.cancel();}
        public int bias(){
            if(EXPERIMENT_SESSION_PANEL.pendingBreathGuidanceBiasPercent()>=0)bias=EXPERIMENT_SESSION_PANEL.pendingBreathGuidanceBiasPercent();
            if(bias<0&&!state().hasActiveSession()){
                for(String condition:new String[]{ExperimentSessionPanelCoordinator.CONDITION_ONE,ExperimentSessionPanelCoordinator.CONDITION_TWO}){
                    if("pattern-ready".equals(ControlPanelActivity.conditionBreathGuidanceReadiness(condition))){
                        int value=ControlPanelActivity.conditionBreathGuidanceDefaultBias(condition);
                        if(EXPERIMENT_SESSION_PANEL.updatePendingBreathGuidanceBiasPercent(value))bias=value;break;
                    }
                }
            }
            return Math.max(0,bias);
        }
        public boolean bias(int value){if(!settingsReady()||!EXPERIMENT_SESSION_PANEL.updatePendingBreathGuidanceBiasPercent(value))return false;bias=value;return true;}
        public void returnToVr(){if(settingsReady()&&activity instanceof ControlPanelActivity)ControlPanelActivity.closePanelAndReturnToImmersive(activity);}
        public void close(){
            if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);
            activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            retireHandoff(false);
            panel.close();layout.removeView(panel.view());EXPERIMENT_SESSION_SHELL.detach(this);
        }
        boolean live() {
            return hostResumed && attached()&&EXPERIMENT_SESSION_SHELL.isCurrentUiAttachment(this,epoch);
        }
        boolean attached(){
            return !closed && current.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed()
                && (EXPERIMENT_SESSION_SHELL.isCurrentUiAttachment(this, epoch)||EXPERIMENT_SESSION_SHELL.isCurrentTerminalUiAttachment(this,epoch));
        }
        void start() {
            activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
            epoch=EXPERIMENT_SESSION_SHELL.currentLaunchEpochForUi();
            EXPERIMENT_SESSION_SHELL.initialize(activity.getFilesDir().getAbsolutePath(),this);
            handler.postDelayed(new Runnable(){public void run(){
                if(!attached()){close();return;}
                if(live())EXPERIMENT_SESSION_SHELL.requestStatus(NativeMeshSessionControls.this);
                handler.postDelayed(this,500);
            }},500);
        }
        public boolean acceptsExperimentSessionCallbacks(long value) { return live() && value==epoch; }
        public boolean acceptsExperimentSessionTerminalCallbacks(long value){return hostResumed&&attached()&&value==epoch&&EXPERIMENT_SESSION_SHELL.isCurrentTerminalUiAttachment(this,epoch);}
        public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult result) {
            if(result==null||!acceptsExperimentSessionTerminalCallbacks(epoch))return;
            panel.outcome(result.shouldFinish&&result.saved?(EXPERIMENT_SESSION_SHELL.localTerminalScope().idleWithoutRecording?"No recording to save; waiting for renderer shutdown.":"Recording saved; waiting for renderer shutdown."):"Save and exit pending: "+result.detail+"; saved=false");
            if(result.shouldFinish&&result.saved)ControlPanelActivity.finishStandaloneTerminal(activity,EXPERIMENT_SESSION_SHELL.localTerminalScope(),result);
            else panel.refresh();
        }
        public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback readback) {
            if(!live() || readback==null || readback.receipt==null)return;
            EXPERIMENT_SESSION_PANEL.acceptRuntimeEpoch(readback.runtimeEpoch);
            EXPERIMENT_SESSION_PANEL.acceptNativeReadback(readback.runtimeEpoch,readback.receipt,
                readback.statusReadCompletedNanos,SystemClock.elapsedRealtimeNanos(),
                "accepted".equals(readback.commandStatus),readback.armAcceptance);
            ExperimentSessionPanelState state=EXPERIMENT_SESSION_PANEL.snapshot();
            if(!EXPERIMENT_SESSION_SHELL.localTerminalPending() && state.phase==ExperimentSessionPanelState.Phase.ARMING && state.recording)
                ControlPanelActivity.startConditionAudio(state.generation,"session-arm-"+state.generation,state.activeCondition);
            if (processCommand != null) {
                try {
                    String receipt = processCommand.observe(nativeState());
                    if ("applied".equals(receipt) || "rejected".equals(receipt) || "owner-changed-outcome-unknown".equals(receipt)) processCommand=null;
                    else panel.outcome("Operator command: "+receipt);
                } catch (Exception unavailable) { panel.outcome("Operator outcome unknown; no replay."); }
            }
            panel.refresh();
        }
        JSONObject nativeState() throws Exception {
            if(!live())throw new SecurityException("mesh-owner-retired");return processNativeState();
        }
        void dispatchOperator(ExperimentSessionControlCapability.Fence fence,String id,JSONObject command) throws Exception {
            if(!live())throw new SecurityException("operator-owner-unavailable");dispatchProcessOperator(fence,id,command);
        }
        void dispatch(String operation) {
            if(!live())return;
            try {
                if("save-exit".equals(operation)){
                    if(!canSaveExit())throw new SecurityException("terminal-ui-admission-unavailable");
                    if(EXPERIMENT_SESSION_SHELL.localTerminalPending()){EXPERIMENT_SESSION_SHELL.resumePendingTerminal();panel.outcome("Resuming the pending exit; no accepted command is replayed.");return;}
                    JSONObject n=nativeState();long ownerId=ControlPanelActivity.nativeReadExperimentSessionImmersiveOwnerIdentity();
                    ExperimentSessionAndroidShell.LocalTerminalScope scope=new ExperimentSessionAndroidShell.LocalTerminalScope(epoch,ownerId,n.getLong("runtime_epoch"),n.getLong("generation"),nullableIdentity(n,"session_key"),nullableIdentity(n,"condition_id"),nullableIdentity(n,"non_audio_profile_sha256"),n.getString("activation_binding_sha256"),n.getJSONObject("recording_result").getString("status"));
                    if(!live()||!EXPERIMENT_SESSION_SHELL.beginLocalTerminal(scope,this))throw new SecurityException("terminal-ui-owner-retired-or-pending");
                    retireHandoff(true);panel.suspend();panel.resume();panel.outcome("Saving and closing; waiting for writer and audio acknowledgements.");panel.refresh();return;
                }
                if(EXPERIMENT_SESSION_SHELL.localTerminalPending())throw new SecurityException("terminal-exit-pending");
                if(processCommand!=null || !EXPERIMENT_SESSION_PANEL.snapshot().pendingOperationId.isEmpty() || processHandoff!=null && processHandoff.pending())
                    throw new SecurityException("session-operation-pending");
                if(operation.startsWith("arm-")) {
                    String condition="arm-a".equals(operation)?ExperimentSessionPanelCoordinator.CONDITION_ONE:ExperimentSessionPanelCoordinator.CONDITION_TWO;
                    if(!"track-ready".equals(ControlPanelActivity.conditionAudioReadiness(condition)))throw new SecurityException("audio-not-ready");
                    ExperimentSessionPanelCoordinator.NativeCommand command=EXPERIMENT_SESSION_PANEL.arm(condition,
                        "pattern-ready".equals(ControlPanelActivity.conditionBreathGuidanceReadiness(condition))?bias():0);
                    if(command==null)throw new SecurityException("arm-not-admitted");
                    EXPERIMENT_SESSION_SHELL.submit(command,true,this);
                } else if("save-next".equals(operation)) {
                    if(!ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(EXPERIMENT_SESSION_PANEL.snapshot()))throw new SecurityException("save-not-admitted");
                    ExperimentSessionPanelCoordinator.NativeCommand command=EXPERIMENT_SESSION_PANEL.restartToExperimenter(EXPERIMENT_SESSION_PANEL.allocateRouteEvent());
                    if(command==null)throw new SecurityException("save-not-admitted");
                    EXPERIMENT_SESSION_SHELL.submit(command,false,this);
                } else {
                    JSONObject n=nativeState();
                    ExperimentSessionControlCapability.Fence fence=new ExperimentSessionControlCapability.Fence("",n.getString("session_key"),n.getString("condition_id"),
                        n.getString("non_audio_profile_sha256"),n.getString("activation_binding_sha256"),operation,n.getLong("generation"),n.getLong("runtime_epoch"),0,0,0);
                    String id="ui-session-"+ExperimentSessionOpaqueIdentity.newHex(8);
                    if("pause".equals(operation)) {
                        dispatchOperator(fence,id,fence.nativeCommand(id,SystemClock.elapsedRealtimeNanos()).put("operator_origin","ui-session-v2"));
                    } else {
                        processHandoff=new ProcessHandoff(this,fence,id);
                        processHandoff.task.run();
                    }
                }
                EXPERIMENT_SESSION_SHELL.requestStatus(this);
            } catch(Exception failure) {
                panel.outcome("Command not confirmed; refresh authoritative session state. "+failure.getMessage());
            }
        }
    }

}
