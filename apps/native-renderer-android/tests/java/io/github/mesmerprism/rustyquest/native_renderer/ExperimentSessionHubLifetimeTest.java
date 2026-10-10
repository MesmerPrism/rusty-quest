package io.github.mesmerprism.rustyquest.native_renderer;

public final class ExperimentSessionHubLifetimeTest {
    static int checks;
    static void check(boolean yes, String why) { checks++; if (!yes) throw new AssertionError(why); }
    static final class Port implements ExperimentSessionHubLifetime.Factory {
        int creates, starts, closes; long epoch; boolean fails;
        public ExperimentSessionHubLifetime.Driver create(long e,
                java.util.function.Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source) {
            creates++; epoch=e;
            return new ExperimentSessionHubLifetime.Driver() {
                public void start() { starts++; if(fails) throw new IllegalStateException("modeled admission unavailable"); }
                public void close() { closes++; }
            };
        }
    }
    static void nativeRead(ExperimentSessionPanelCoordinator c, long epoch, long time) {
        c.acceptNativeReadback(epoch,new ExperimentSessionPanelCoordinator.NativeReceipt(
            "",true,true,1L,2L,"idle","none",false,false,"ready",true,
            0L,0L,0L,0L,true,true,false,0L,0L,0L,"none",0L,"host"),time,time);
    }
    public static void main(String[] args) {
        final ExperimentSessionPanelCoordinator c=new ExperimentSessionPanelCoordinator();
        final long[] now={1L}; Port p=new Port();
        ExperimentSessionHubLifetime life=new ExperimentSessionHubLifetime(p,()->c.nativeStatusSnapshot(now[0]));
        check(p.creates==0,"construction inert"); life.refresh();
        check(p.creates==0,"no native witness cannot bind");
        nativeRead(c,9L,now[0]);life.refresh();
        check(p.starts==1&&p.epoch==9L,"actual coordinator witness starts exact epoch");
        now[0]=1000000L;life.refresh();life.refresh();
        check(p.starts==1,"poll cannot reopen or refresh native identity");
        c.openDeveloper(c.allocateRouteEvent());life.refresh();
        check(p.closes==1,"local state invalidation retires lifetime");
        nativeRead(c,9L,++now[0]);life.refresh();
        check(p.starts==1,"new read cannot reopen closed lifetime");
        life.close();life.refresh();check(p.closes==1,"destroy/pause closure idempotent");
        ExperimentSessionPanelCoordinator fresh=new ExperimentSessionPanelCoordinator();nativeRead(fresh,12L,1L);
        Port paused=new Port();ExperimentSessionHubLifetime pausedLife=new ExperimentSessionHubLifetime(paused,()->fresh.nativeStatusSnapshot(1L));
        pausedLife.close();pausedLife.refresh();check(paused.creates==0,"saved callback after pause cannot bind");
        Port failed=new Port();failed.fails=true;
        ExperimentSessionHubLifetime failure=new ExperimentSessionHubLifetime(failed,()->fresh.nativeStatusSnapshot(1L));
        failure.refresh();failure.refresh();check(failed.starts==1&&failed.closes==1,"failed start closes, never automatic retry");
        Port clock=new Port();final long[] tick={2L};
        ExperimentSessionHubLifetime clockLife=new ExperimentSessionHubLifetime(clock,()->fresh.nativeStatusSnapshot(tick[0]));
        clockLife.refresh();tick[0]=1L;clockLife.refresh();check(clock.closes==1,"clock regression invalidates eligibility");
        Port replaced=new Port();final ExperimentSessionPanelCoordinator[] current={fresh};
        ExperimentSessionHubLifetime replacement=new ExperimentSessionHubLifetime(replaced,()->current[0].nativeStatusSnapshot(3L));
        nativeRead(fresh,12L,3L);replacement.refresh();
        current[0]=new ExperimentSessionPanelCoordinator();nativeRead(current[0],13L,3L);replacement.refresh();
        check(replaced.closes==1&&replaced.starts==1,"epoch replacement closes, cannot reuse credentials");
        // Compose the real lifetime, provider and admission reducer. Replies are
        // owner-shaped host fixtures only; they are not issued credentials.
        ExperimentSessionPanelCoordinator joined=new ExperimentSessionPanelCoordinator();
        nativeRead(joined,9L,1000000L);
        ExperimentSessionHubProviderTest.Port actual=new ExperimentSessionHubProviderTest.Port();
        actual.time=1L;
        ExperimentSessionHubLifetime composed=new ExperimentSessionHubLifetime((e, source)-> {
            actual.p=new ExperimentSessionHubProvider(actual,7L,"trial",e,source);
            return new ExperimentSessionHubLifetime.Driver() {
                public void start(){actual.p.start();}
                public void close(){actual.p.close();}
            };
        },()->joined.nativeStatusSnapshot(actual.time*1000000L));
        composed.refresh();ExperimentSessionHubProviderTest.admitted(actual);
        actual.answer("{\"applied\":true}");
        check(actual.p.state().isRegistered()&&actual.sent.equals(java.util.Arrays.asList(6,1,2,20)),
            "actual lifetime-to-provider-to-reducer composition preserves admission order");
        Runnable saved=actual.tasks.get("observation_poll");
        joined.openDeveloper(joined.allocateRouteEvent());composed.refresh();
        int sends=actual.sent.size();saved.run();
        check(actual.unbinds==1&&actual.sent.get(sends-1)==22&&actual.sent.size()==sends,
            "actual invalidation unregisters owned surface and fences saved provider poll");
        System.out.println("ExperimentSessionHubLifetimeTest PASS controls="+checks+"; actual coordinator witness, modeled driver, no Binder/runtime effects");
    }
}
