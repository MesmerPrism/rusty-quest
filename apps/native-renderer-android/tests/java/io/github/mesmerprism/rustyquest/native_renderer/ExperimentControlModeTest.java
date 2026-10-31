package io.github.mesmerprism.rustyquest.native_renderer;

/** JNI is the external boundary; all snapshot validation/selection behavior is production. */
public final class ExperimentControlModeTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Checked { void run(); }
    static void rejects(Checked action){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){return;}throw new AssertionError("Invalid control mode admitted");}
    static final class NativeFixture implements ExperimentControlMode.NativeAccess {
        long value=9;int selections;
        public long initialize(boolean selected){if(selected&&value==9)value=20;return value;}
        public long read(){return value;}
        public long select(long expected,int mode){selections++;if(expected==value)value=((value>>>3)+1)*8+4+mode;return value;}
    }
    public static void main(String[] ignored) {
        check(!ExperimentControlMode.selectedComposition(false,false),"legacy closure");
        check(!ExperimentControlMode.selectedComposition(true,false),"host without gateway");
        check(!ExperimentControlMode.selectedComposition(false,true),"gateway without native host");
        check(ExperimentControlMode.selectedComposition(true,true),"selected conjunction");
        NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode adapter=new ExperimentControlMode(nativeOwner);
        int[] changes={0};adapter.onChange(()->changes[0]++);
        ExperimentControlMode.Snapshot legacy=adapter.readSnapshot();
        check(!legacy.selected&&legacy.localMutationsAllowed()&&legacy.mode==1,"legacy Direct default");legacy.requireRemoteCommands();
        ExperimentControlMode.Snapshot choose=adapter.initializeSelected(true);
        check(choose.selected&&choose.mode==0&&!choose.localMutationsAllowed()&&"choose".equals(choose.wireName()),"fresh chooser");
        rejects(()->choose.requireRemoteCommands());
        ExperimentControlMode.Snapshot direct=adapter.select(choose,1);
        check(direct.mode==1&&direct.localMutationsAllowed()&&direct.revision==3,"Direct readback");rejects(()->direct.requireRemoteCommands());
        ExperimentControlMode.Snapshot stale=adapter.select(choose,2);
        check(stale.packed==direct.packed,"stale selection changed authority");
        check(changes[0]==1,"stale selection called lifecycle hook");
        ExperimentControlMode.Snapshot remote=adapter.select(direct,2);
        check(remote.mode==2&&!remote.localMutationsAllowed()&&"remote".equals(remote.wireName()),"Remote readback");remote.requireRemoteCommands();
        check(changes[0]==2,"successful actual mode selection missed lifecycle hook");
        check(adapter.initializeSelected(true).packed==remote.packed,"recreation reset selection");
        check(adapter.initializeSelected(false).packed==remote.packed,"disabled late caller reset selection");
        int before=nativeOwner.selections;rejects(()->adapter.select(remote,0));rejects(()->adapter.select(remote,3));
        check(nativeOwner.selections==before,"invalid mode entered JNI");
        for(long value:new long[]{0,1,8,10,11,15,Long.MIN_VALUE,-1}){
            nativeOwner.value=value;rejects(()->adapter.readSnapshot());
        }
        nativeOwner.value=Long.MAX_VALUE-1;
        check(adapter.readSnapshot().revision==1152921504606846975L,"large positive revision lost");
        System.out.println("PASS "+checks+" production operator-mode adapter checks: closure, native snapshots, stale CAS, idempotent initialization, legacy behavior and invalid values");
    }
}
