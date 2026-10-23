package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;

/** Same-process SDK lifecycle adapter. Owns no reducer, recording or SDK clock.
 * Bind/poll/pose run on the app's serialized background owner executor.
 * Revoke must run synchronously when the actual SDK scene is paused/retired.
 * Callers pass real scene handles; no observer diagnostic boolean is accepted. */
public final class SdkExperimentSessionCore {
    static { System.loadLibrary("rusty_quest_sdk_session_core"); }
    private SdkExperimentSessionCore() { }
    private static final Object AUDIO_LOCK = new Object();
    private static final class AudioOwner {
        final long epoch, runtimeEpoch;
        boolean live = true;
        AudioOwner(long epoch,long runtimeEpoch) { this.epoch=epoch; this.runtimeEpoch=runtimeEpoch; }
    }
    private static AudioOwner audioOwner;
    private static final ConditionAudioContract.OwnerScope AUDIO_SCOPE = new ConditionAudioContract.OwnerScope() {
        public Object captureAudioOwnerToken() { return SdkExperimentSessionCore.captureAudioOwnerToken(); }
        public boolean isAudioOwnerCurrent(Object token,long generation,boolean active) {
            return SdkExperimentSessionCore.isAudioOwnerCurrent(token,generation,active);
        }
        public boolean run(Object token,long generation,boolean active,Runnable action) {
            synchronized (AUDIO_LOCK) {
                if (!isAudioOwnerCurrent(token,generation,active)) return false;
                action.run(); return true;
            }
        }
    };
    static { ConditionAudioContract.installOwnerScope(AUDIO_SCOPE); }
    public static Object captureAudioOwnerToken() { synchronized (AUDIO_LOCK) { return audioOwner; } }
    public static boolean isAudioOwnerCurrent(Object token,long generation,boolean active) {
        synchronized (AUDIO_LOCK) {
            if (!(token instanceof AudioOwner) || token != audioOwner) return false;
            AudioOwner owner=(AudioOwner)token;
            return (!active || owner.live) && nativeValidateAudio(owner.epoch,owner.runtimeEpoch,generation,active);
        }
    }
    /** Executes a queued facade/presentation action under the same revoke boundary.
     * Never wait for another audio worker from inside action. */
    public static boolean runForAudioOwner(Object token,long generation,boolean active,Runnable action) {
        return AUDIO_SCOPE.run(token,generation,active,action);
    }
    private static native boolean nativeValidateAudio(long epoch,long runtimeEpoch,long generation,boolean active);
    private static native String nativeInputSource(long epoch,long identity);
    private static native String nativeButtonSample(long epoch,long sequence,long elapsed,boolean pressed);
    public static String controllerButtonSample(long epoch,long sequence,long elapsed,boolean pressed){
        synchronized(AUDIO_LOCK){return nativeButtonSample(epoch,sequence,elapsed,pressed);}
    }
    public static String controllerInputSource(long epoch,long entityId) { synchronized(AUDIO_LOCK) { return nativeInputSource(epoch,entityId); } }

    private static long controlGeneration, controlRevision, phaseGeneration, phaseIndex = -1;
    private static native String nativeBind(long epoch, long instance, long session);
    private static native String nativeRevoke(long epoch, boolean terminal);
    private static native String nativePoll(long epoch);
    private static native String nativeControllerPose(long epoch, long sequence, long sampledAndroidElapsedNs,
        double px, double py, double pz, double qx, double qy, double qz, double qw,
        boolean active, boolean tracked, boolean rightSecondaryPressed);
    static String bindScene(final long epoch,final long instance,final long session,
        final SdkOwnerLifecycle lifecycle,final SdkOwnerLifecycle.Attempt attempt) {
        final ConditionAudioRuntime.OwnerTransitionGuard guard=new ConditionAudioRuntime.OwnerTransitionGuard(){
            public boolean run(Runnable action){return lifecycle.runCurrent(attempt,action);}
        };
        final ConditionAudioRuntime audio=ControlPanelActivity.sdkAudioForTransfer();
        final ConditionAudioRuntime.OwnerTransfer transfer;
        try{transfer=audio!=null&&captureAudioOwnerToken()!=null?audio.prepareOwnerTransfer(guard):null;}
        catch(Exception pending){
            Throwable cause=pending instanceof java.util.concurrent.ExecutionException?pending.getCause():pending;
            if(cause!=null&&"audio-owner-transfer-not-quiescent".equals(cause.getMessage()))
                return "{\"status\":\"rejected\",\"reason\":\"audio-owner-transfer-not-quiescent\"}";
            return "{\"status\":\"rejected\",\"reason\":\"audio-owner-transfer-preparation-failed\"}";
        }
        final String[] text={"{\"status\":\"rejected\",\"reason\":\"sdk-bind-attempt-retired\"}"};
        final boolean[] bound={false};
        boolean attached=false;
        try{lifecycle.runCurrent(attempt,new Runnable(){public void run(){
            synchronized(AUDIO_LOCK){
                if(audioOwner!=null)audioOwner.live=false;
                text[0]=nativeBind(epoch,instance,session);
                try{
                    JSONObject value=new JSONObject(text[0]);
                    if("accepted".equals(value.optString("status"))){
                        audioOwner=new AudioOwner(epoch,value.getLong("runtime_epoch"));
                        controlGeneration=0;controlRevision=0;phaseGeneration=0;phaseIndex=-1;
                        lifecycle.nativeBound(attempt);bound[0]=true;
                    }
                }catch(Exception ignored){}
            }
        }});
        if(bound[0]&&transfer!=null){
            try{audio.attachOwnerTransfer(transfer,guard);attached=true;}
            catch(Exception failure){
                lifecycle.runCurrent(attempt,new Runnable(){public void run(){revokeScene(epoch,false);}});
                return "{\"status\":\"rejected\",\"reason\":\"audio-owner-transfer-failed\"}";
            }
        }
        return text[0];
        }finally{if(transfer!=null&&!attached)audio.retireOwnerTransfer(transfer);}
    }
    public static String revokeScene(long epoch, boolean terminal) {
        synchronized (AUDIO_LOCK) {
            if (audioOwner != null && audioOwner.epoch == epoch) audioOwner.live=false;
            // Delivery and all queued playback effects use this same monitor.
            return nativeRevoke(epoch,terminal);
        }
    }
    public static String controllerPose(long epoch, long sequence, long sampledAndroidElapsedNs,
        double px, double py, double pz, double qx, double qy, double qz, double qw,
        boolean active, boolean tracked, boolean rightSecondaryPressed) {
        synchronized (AUDIO_LOCK) {
            return nativeControllerPose(epoch, sequence, sampledAndroidElapsedNs, px, py, pz, qx, qy, qz, qw, active, tracked, rightSecondaryPressed);
        }
    }
    public static String poll(long epoch) {
        String text = nativePoll(epoch);
        synchronized (AUDIO_LOCK) {
        try {
            JSONObject value = new JSONObject(text);
            if (!"accepted".equals(value.optString("status")) || value.optLong("sdk_epoch") != epoch
                    || audioOwner == null || audioOwner.epoch != epoch
                    || audioOwner.runtimeEpoch != value.optLong("runtime_epoch")) return text;
            final Object token=audioOwner;
            JSONObject proof=value.optJSONObject("proof");
            boolean admitted=proof != null && proof.optBoolean("submission_admitted",false);
            JSONObject control = value.optJSONObject("control");
            if (control != null) {
                long generation = control.getLong("generation"), revision = control.getLong("revision");
                if (generation > controlGeneration) { controlGeneration = generation; controlRevision = 0; }
                if (generation > 0 && generation == controlGeneration && revision > controlRevision) {
                    String event=control.getString("event");
                    boolean active="official-start".equals(event)||"experiment-resumed".equals(event);
                    if ((!active || admitted) && isAudioOwnerCurrent(token,generation,active)) {
                        ControlPanelActivity.applyConditionAudioControlReceipt(generation, revision, event);
                        controlRevision = revision;
                    }
                }
            }
            JSONObject phase = value.optJSONObject("phase");
            if (phase != null && admitted) {
                long generation = phase.getLong("generation"), index = phase.getLong("index");
                if (generation == controlGeneration && controlRevision > 0 && (generation != phaseGeneration || index != phaseIndex)
                        && isAudioOwnerCurrent(token,generation,true)) {
                    ControlPanelActivity.applyBreathPhaseAudioSample(generation, index, phase.getLong("start"), phase.getLong("end"),
                        phase.getLong("active_time_ms"), phase.getString("phase"));
                    phaseGeneration = generation; phaseIndex = index;
                }
            } else if (phaseGeneration > 0 && isAudioOwnerCurrent(token,phaseGeneration,false)) {
                ControlPanelActivity.applyBreathPhaseAudioSample(phaseGeneration, 0, 0, 0, 0, "inactive");
                phaseGeneration = 0; phaseIndex = -1;
            }
        } catch (Exception failure) {
            // Leave receipt revision unconsumed on delivery failure; next poll retries.
            android.util.Log.e("SdkExperimentSession", "audio receipt delivery failed", failure);
        }
        }
        return text;
    }
}
