package io.github.mesmerprism.rustyquest.native_renderer;

import java.lang.reflect.*;

/** Actual producer scheduling policy only; no fake Android rendering or wall-time sleeps. */
public final class NativePageObservationTest {
    private static int assertions;
    private static void check(boolean pass,String message){assertions++;if(!pass)throw new AssertionError(message);}
    private static final class Policy {
        final Object owner;final Class<?> type;
        Policy()throws Exception {
            try{type=Class.forName("io.github.mesmerprism.rustyquest.native_renderer.NativeMeshUiProducer$ObservationPolicy");}
            catch(ClassNotFoundException missing){throw new AssertionError("damage-driven observation policy absent",missing);}
            Constructor<?> c=type.getDeclaredConstructor();c.setAccessible(true);owner=c.newInstance();
        }
        Object invoke(String name,Class<?>[] types,Object...arguments)throws Exception {
            Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,arguments);
        }
        long delay(long now,boolean quiet)throws Exception {return (Long)invoke("delay",new Class<?>[]{long.class,boolean.class},now,quiet);}
        long begin()throws Exception {return (Long)invoke("begin",new Class<?>[0]);}
        void finish(long token,long now,boolean complete)throws Exception {invoke("finish",new Class<?>[]{long.class,long.class,boolean.class},token,now,complete);}
        void invalidate()throws Exception {invoke("invalidate",new Class<?>[0]);}
        void stop()throws Exception {invoke("stop",new Class<?>[0]);}
        Object subscribe()throws Exception {return invoke("subscribe",new Class<?>[0]);}
        boolean current()throws Exception {return (Boolean)invoke("current",new Class<?>[0]);}
        void completeAt(long now)throws Exception {finish(begin(),now,true);}
    }
    private static Object event(Object signal,String name)throws Exception {Method m=signal.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(signal);}
    public static void main(String[] args)throws Exception {
        Policy idle=new Policy();
        check(idle.delay(0,true)==0,"first observation delayed behind idle backup");idle.completeAt(0);
        check(idle.current(),"complete initial snapshot not current");
        check(idle.delay(100,true)==400,"quiet unchanged tick walked or shifted500ms deadline");
        check(idle.delay(499,true)==1,"idle backup deadline drifted");check(idle.delay(500,true)==0,"idle backup never ran");
        check(idle.delay(100,false)==0,"active editor/gesture lost original100ms backup");
        long failed=idle.begin();idle.finish(failed,500,false);
        check(!idle.current(),"failed scan manufactured current snapshot");
        check(idle.delay(599,true)==1,"fallback retry did not preserve100ms policy");check(idle.delay(600,true)==0,"fallback retry waited500ms");

        Policy changes=new Policy();Object modal=changes.subscribe();changes.completeAt(0);
        check((Boolean)event(modal,"damage"),"actual modal damage ignored");check(!changes.current(),"dirty publication reported current");
        check(changes.delay(10,true)==22,"early event was dropped instead of coalesced to32ms");
        check((Boolean)event(modal,"damage"),"repeat damage lost");check(changes.delay(20,true)==12,"later event postponed existing flush");
        long captured=changes.begin();event(modal,"damage");changes.finish(captured,32,true);
        check(changes.delay(33,true)==31,"later damage consumed by completion of older scan");
        changes.completeAt(64);check(changes.current(),"newer complete scan did not consume pending damage");
        check(changes.delay(100,true)==464,"coalesced walk did not reset bounded backup");

        Policy identity=new Policy();identity.completeAt(0);identity.invalidate();
        check(identity.delay(1,true)==0,"identity invalidation waited ordinary32ms throttle");
        long before=identity.begin();identity.invalidate();identity.finish(before,1,true);
        check(!identity.current(),"older completion revived invalidated publication");check(identity.delay(2,true)==0,"newer identity invalidation disappeared");
        identity.completeAt(2);check(identity.current(),"matching new identity never became current");

        Policy windows=new Policy();Object old=windows.subscribe();windows.completeAt(0);
        event(old,"close");windows.invalidate();windows.completeAt(1);
        check(!(Boolean)event(old,"damage"),"removed window's late callback dirtied replacement");
        check(windows.delay(2,true)==499,"retired callback shifted replacement backup");
        Object fresh=windows.subscribe();check((Boolean)event(fresh,"damage"),"replacement window cannot trigger observation");
        windows.stop();check(windows.delay(32,true)==-1,"stop retained a pending full walk");
        check(!(Boolean)event(fresh,"damage"),"late callback survived producer stop");
        Object late=windows.subscribe();check(!(Boolean)event(late,"damage"),"subscription reopened stopped producer");
        windows.finish(windows.begin(),40,true);windows.invalidate();check(windows.delay(1000,true)==-1,"late completion or invalidation restarted stopped policy");
        System.out.println("NativePageObservationTest PASS assertions="+assertions+" scope=actual-damage-generation-coalescing-idle-backup-and-window-retirement-policy");
    }
}
