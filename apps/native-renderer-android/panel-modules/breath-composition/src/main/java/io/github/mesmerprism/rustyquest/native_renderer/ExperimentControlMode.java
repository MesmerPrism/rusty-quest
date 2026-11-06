package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** App projection of the native process-local operator mode. No Java mode is retained. */
public final class ExperimentControlMode {
    public static final int CHOOSE=0, DIRECT=1, REMOTE=2;
    private static final String NATIVE_HOST="rusty.quest.native_renderer.original_panel_native_host";
    private static final String COMMAND_GATEWAY="rusty.quest.experiment.command_gateway.enabled";
    interface NativeAccess {
        long initialize(boolean selected);
        long read();
        long select(long expectedSnapshot,int mode);
    }
    private static final class JniAccess implements NativeAccess {
        private boolean loaded;
        private synchronized void load(){if(!loaded){System.loadLibrary("rusty_quest_native_renderer");loaded=true;}}
        public long initialize(boolean selected){load();return nativeInitialize(selected);}
        public long read(){load();return nativeRead();}
        public long select(long expectedSnapshot,int mode){load();return nativeSelect(expectedSnapshot,mode);}
    }
    private static final ExperimentControlMode PROCESS=new ExperimentControlMode(new JniAccess());
    private final NativeAccess nativeOwner;
    private Runnable changeListener;
    ExperimentControlMode(NativeAccess nativeOwner){this.nativeOwner=nativeOwner;}
    static ExperimentControlMode process(){return PROCESS;}

    static boolean selectedComposition(boolean originalNativeHost,boolean commandGateway){
        return originalNativeHost&&commandGateway;
    }
    /** Runs before native ownership/input. Repeated calls cannot reset the native selection. */
    static Snapshot initialize(Context context){
        boolean selected;
        try {
            Bundle metadata=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData;
            selected=metadata!=null&&selectedComposition(metadata.getBoolean(NATIVE_HOST,false),metadata.getBoolean(COMMAND_GATEWAY,false));
        }catch(Exception unavailable){throw new IllegalStateException("operator-mode-composition-unavailable",unavailable);}
        return PROCESS.initializeSelected(selected);
    }
    Snapshot initializeSelected(boolean selected){return new Snapshot(nativeOwner.initialize(selected));}
    Snapshot readSnapshot(){return new Snapshot(nativeOwner.read());}
    void onChange(Runnable listener){changeListener=listener;}
    void requireLocalDispatch(Snapshot captured){
        Snapshot current=readSnapshot();
        if(captured==null||!captured.localMutationsAllowed()||current.packed!=captured.packed||!current.localMutationsAllowed())
            throw new SecurityException("direct-control-mode-changed");
    }
    Snapshot select(Snapshot expected,int mode){
        if(expected==null||!expected.selected||mode!=DIRECT&&mode!=REMOTE)
            throw new IllegalArgumentException("explicit-selected-control-mode-required");
        Snapshot selected=new Snapshot(nativeOwner.select(expected.packed,mode));
        if(selected.packed!=expected.packed&&selected.mode==mode&&changeListener!=null)changeListener.run();
        return selected;
    }

    static final class Snapshot {
        final long packed,revision;
        final boolean selected;
        final int mode;
        Snapshot(long packed){
            this.packed=packed;mode=(int)(packed&3);selected=(packed&4)!=0;revision=packed>>>3;
            if(packed<0||revision<=0||mode>REMOTE||!selected&&mode!=DIRECT)
                throw new IllegalStateException("invalid-native-control-mode");
        }
        String wireName(){return mode==CHOOSE?"choose":mode==DIRECT?"direct":"remote";}
        boolean localMutationsAllowed(){return !selected||mode==DIRECT;}
        void requireRemoteCommands(){if(selected&&mode!=REMOTE)throw new SecurityException("remote-control-mode-required");}
    }
    private static native long nativeInitialize(boolean selected);
    private static native long nativeRead();
    private static native long nativeSelect(long expectedSnapshot,int requestedMode);
}
