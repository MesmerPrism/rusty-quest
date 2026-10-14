package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.SeekBar;
import org.json.JSONObject;

/** Standalone presentation adapter for the existing process shell and native session owner. */
public class ExperimentSessionOwnerPanelModule extends Activity implements PanelModule {
    public static final String MODULE_ID = "experiment-session-owner";
    public String panelModuleId() { return MODULE_ID; }
    private boolean destroyed;
    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24,24,24,24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        attachNativeMeshSessionControls(this, content, new java.util.function.BooleanSupplier() {
            @Override public boolean getAsBoolean() { return !destroyed; }
        });
    }
    @Override protected void onDestroy() { destroyed=true; super.onDestroy(); }
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
    /** Native mesh presentation adapter; the existing process shell and reducer retain authority. */
    public static void attachNativeMeshSessionControls(Activity activity, LinearLayout layout,
            java.util.function.BooleanSupplier current) {
        ControlPanelActivity.prepareExperimentSessionAppOwner(activity.getApplicationContext());
        new NativeMeshSessionControls(activity, layout, current).start();
    }

    private static final class NativeMeshSessionControls implements ExperimentSessionAndroidShell.UiSink {
        final Activity activity;
        final LinearLayout layout;
        final java.util.function.BooleanSupplier current;
        final Handler handler = new Handler(Looper.getMainLooper());
        final TextView status;
        final java.util.Map<String, Button> buttons = new java.util.LinkedHashMap<>();
        long epoch;
        int bias;
        ExperimentSessionUiControlHandoff handoff;
        ExperimentSessionUiCommandReceipt pendingCommand;
        NativeMeshSessionControls(Activity activity, LinearLayout layout,
                java.util.function.BooleanSupplier current) {
            this.activity=activity; this.layout=layout; this.current=current;
            status=new TextView(layout.getContext()); status.setTextColor(Color.WHITE);
            status.setText("Session owner: reading authoritative state"); layout.addView(status);
            for (String op : new String[]{"arm-a","arm-b","start","pause","resume","save-next"}) {
                Button button=new Button(layout.getContext()); button.setAllCaps(false);
                button.setText("arm-a".equals(op)?"Arm condition A":"arm-b".equals(op)?"Arm condition B":
                    "save-next".equals(op)?"Finish and save session":op);
                button.setEnabled(false); button.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View view) { dispatch(op); }
                });
                buttons.put(op,button); layout.addView(button,new LinearLayout.LayoutParams(-1,68));
            }
            TextView label=new TextView(layout.getContext()); label.setTextColor(Color.WHITE);
            label.setText("Breath guidance bias: 0%"); layout.addView(label);
            SeekBar slider=new SeekBar(layout.getContext()); slider.setMax(100);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar,int value,boolean user) {
                    if(user){bias=value;label.setText("Breath guidance bias: "+value+"%");}
                }
                public void onStartTrackingTouch(SeekBar bar) { }
                public void onStopTrackingTouch(SeekBar bar) { }
            }); layout.addView(slider);
        }
        boolean live() {
            return current.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed()
                && EXPERIMENT_SESSION_SHELL.isCurrentUiAttachment(this, epoch);
        }
        void start() {
            epoch=EXPERIMENT_SESSION_SHELL.currentLaunchEpochForUi();
            EXPERIMENT_SESSION_SHELL.initialize(activity.getFilesDir().getAbsolutePath(),this);
            handler.postDelayed(new Runnable(){public void run(){
                if(!live()){EXPERIMENT_SESSION_SHELL.detach(NativeMeshSessionControls.this);return;}
                EXPERIMENT_SESSION_SHELL.requestStatus(NativeMeshSessionControls.this);
                handler.postDelayed(this,500);
            }},500);
        }
        public boolean acceptsExperimentSessionCallbacks(long value) { return live() && value==epoch; }
        public void onExperimentSessionTerminal(ExperimentSessionAndroidShell.TerminalResult result) {
            if(live())status.setText("Terminal outcome requires the existing app shutdown owner.");
        }
        public void onExperimentSessionReadback(ExperimentSessionAndroidShell.SessionReadback readback) {
            if(!live() || readback==null || readback.receipt==null)return;
            EXPERIMENT_SESSION_PANEL.acceptRuntimeEpoch(readback.runtimeEpoch);
            EXPERIMENT_SESSION_PANEL.acceptNativeReadback(readback.runtimeEpoch,readback.receipt,
                readback.statusReadCompletedNanos,SystemClock.elapsedRealtimeNanos(),
                "accepted".equals(readback.commandStatus),readback.armAcceptance);
            ExperimentSessionPanelState state=EXPERIMENT_SESSION_PANEL.snapshot();
            if(state.phase==ExperimentSessionPanelState.Phase.ARMING && state.recording)
                ControlPanelActivity.startConditionAudio(state.generation,"session-arm-"+state.generation,state.activeCondition);
            status.setText("Session: "+state.phase+" · active "+state.activeTimeMs+" ms · "+state.storageStatus+
                "\n"+state.detail);
            if (pendingCommand != null) {
                try {
                    String receipt = pendingCommand.observe(nativeState());
                    if ("applied".equals(receipt) || "rejected".equals(receipt)) pendingCommand=null;
                    else status.append("\nOperator command: "+receipt);
                } catch (Exception unavailable) { status.append("\nOperator outcome unknown; no replay."); }
            }
            boolean ready=pendingCommand==null && state.pendingOperationId.isEmpty() && (handoff==null || !handoff.pending());
            for(java.util.Map.Entry<String,Button> entry:buttons.entrySet()) {
                String op=entry.getKey();
                entry.getValue().setEnabled(ready && (op.startsWith("arm-")?!state.hasActiveSession():
                    "start".equals(op)?state.phase==ExperimentSessionPanelState.Phase.ARMED:
                    "pause".equals(op)?state.phase==ExperimentSessionPanelState.Phase.RUNNING:
                    "resume".equals(op)?state.phase==ExperimentSessionPanelState.Phase.PAUSED:
                    ExperimentSessionPanelViewPolicy.canSaveAndPrepareNext(state)));
            }
        }
        JSONObject nativeState() throws Exception {
            if(!live())throw new SecurityException("mesh-owner-retired");
            JSONObject n=new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(
                new JSONObject().put("schema",ExperimentSessionPanelCoordinator.SCHEMA).put("operation","status").toString())).getJSONObject("readback");
            JSONObject b=new JSONObject(ControlPanelActivity.nativeReadBreathCompositionStatus()).getJSONObject("snapshot");
            if(!Boolean.TRUE.equals(b.opt("feature_lock_active")) || !Boolean.TRUE.equals(b.opt("activation_binding_matches")))
                throw new SecurityException("feature-inactive");
            return n.put("activation_binding_sha256",b.getString("activation_binding_sha256"));
        }
        void dispatchOperator(ExperimentSessionControlCapability.Fence fence,String id,JSONObject command) throws Exception {
            if (!live() || pendingCommand!=null) throw new SecurityException("operator-owner-unavailable-or-pending");
            pendingCommand=new ExperimentSessionUiCommandReceipt(fence,id);
            JSONObject response=new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(command.toString()));
            if ("rejected".equals(response.optString("command_status"))) {
                pendingCommand=null;
                throw new SecurityException("native-command-rejected");
            }
            if (!"queued".equals(response.optString("command_status"))) throw new SecurityException("native-dispatch-outcome-unknown-no-replay");
        }
        void dispatch(String operation) {
            if(!live())return;
            try {
                if(pendingCommand!=null || !EXPERIMENT_SESSION_PANEL.snapshot().pendingOperationId.isEmpty() || handoff!=null && handoff.pending())
                    throw new SecurityException("session-operation-pending");
                if(operation.startsWith("arm-")) {
                    String condition="arm-a".equals(operation)?ExperimentSessionPanelCoordinator.CONDITION_ONE:ExperimentSessionPanelCoordinator.CONDITION_TWO;
                    if(!"track-ready".equals(ControlPanelActivity.conditionAudioReadiness(condition)))throw new SecurityException("audio-not-ready");
                    ExperimentSessionPanelCoordinator.NativeCommand command=EXPERIMENT_SESSION_PANEL.arm(condition,
                        "pattern-ready".equals(ControlPanelActivity.conditionBreathGuidanceReadiness(condition))?bias:0);
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
                        handoff=new ExperimentSessionUiControlHandoff(new ExperimentSessionUiControlHandoff.Owner(){
                            public JSONObject read() throws Exception { return nativeState(); }
                            // Already in the existing NativeActivity: still wait for genuine projection readback.
                            public void returnVr() {
                                if (activity instanceof ControlPanelActivity)
                                    ControlPanelActivity.closePanelAndReturnToImmersive(activity);
                            }
                            public void dispatch(JSONObject request) throws Exception {
                                if(!live())throw new SecurityException("mesh-owner-retired");
                                dispatchOperator(fence,id,request);
                            }
                            public void schedule(Runnable run,long delay){handler.postDelayed(run,delay);}
                            public long nowMs(){return SystemClock.elapsedRealtime();}
                            public long nowNs(){return SystemClock.elapsedRealtimeNanos();}
                            public void outcome(String value){if(live())status.setText(value);}
                        },fence,id); handoff.run();
                    }
                }
                EXPERIMENT_SESSION_SHELL.requestStatus(this);
            } catch(Exception failure) {
                status.setText("Command not confirmed; refresh authoritative session state. "+failure.getMessage());
            }
        }
    }

}
