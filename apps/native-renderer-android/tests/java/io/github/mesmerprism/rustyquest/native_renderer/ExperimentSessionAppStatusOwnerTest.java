package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ExperimentSessionAppStatusOwnerTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static final class Task { final Runnable action; final long at; Task(Runnable action,long at){this.action=action;this.at=at;} }
    static final class Fixture implements ExperimentSessionAppStatusOwner.Scheduler,
            ExperimentSessionAppStatusOwner.Clock, ExperimentSessionAppStatusOwner.Poller,
            ExperimentSessionHubLifetime.Factory {
        long now=1_000_000_000L; int reads, opens, readerCloses, starts, closes; boolean failStart, driverClosed;
        Consumer<ExperimentSessionAppStatusOwner.Observation> pending;
        Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source;
        List<Task> tasks=new ArrayList<>();
        final ExperimentSessionAppStatusOwner owner=new ExperimentSessionAppStatusOwner(this,this,this,this,
            marker->{ if(marker.contains("sentinel")) throw new AssertionError("private marker"); });
        public long nowNanos(){return now;}
        public void post(Runnable action){tasks.add(new Task(action,now));}
        public void later(Runnable action,long millis){tasks.add(new Task(action,now+millis*1_000_000L));}
        public void opened(long id){opens++;}
        public void closed(long id){if(id>0)readerCloses++;}
        public void read(Consumer<ExperimentSessionAppStatusOwner.Observation> callback){reads++;pending=callback;}
        public ExperimentSessionHubLifetime.Driver create(long epoch,
                Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> value){
            source=value;
            return new ExperimentSessionHubLifetime.Driver(){
                public void start(){starts++;if(failStart)throw new IllegalStateException("denied");}
                public void close(){closes++;}
                public boolean isClosed(){return driverClosed;}
            };
        }
        void flush(){for(int limit=0;limit<100;limit++){
            int selected=-1;for(int i=0;i<tasks.size();i++)if(tasks.get(i).at<=now){selected=i;break;}
            if(selected<0)return;tasks.remove(selected).action.run();
        }throw new AssertionError("unbounded scheduler");}
        void at(long nanos){now=nanos;flush();}
        void reply(ExperimentSessionAppStatusOwner.Observation value){Consumer<ExperimentSessionAppStatusOwner.Observation> p=pending;pending=null;p.accept(value);flush();}
        void start(long id,long epoch){owner.started(id,epoch);flush();}
        void fresh(long epoch){reply(observation(epoch,now,true,false,false));}
    }
    static ExperimentSessionAppStatusOwner.Observation observation(long epoch,long completed,
            boolean accepted,boolean closed,boolean workerClosed){
        return new ExperimentSessionAppStatusOwner.Observation(
            new ExperimentSessionPanelCoordinator.NativeReceipt("",true,true,0L,0L,
                closed?"closed":"idle","none",false,false,"ready",true,
                0L,0L,0L,0L,true,true,false,0L,0L,0L,"none",0L,"sentinel"),
            epoch,completed,accepted,true,closed,workerClosed);
    }
    public static void main(String[] args){
        queuedStartAndOldOwnerAreFenced();
        continuousAckDoesNotNeedPanelOrRevisionChange();
        failedQueriesAgeAndRetire();
        absentCallbackStillRetires();
        closedAndWorkerLossRetire();
        futureAndRegressingAcksCannotRefresh();
        wrongEpochResponsesCannotRefreshOrRetireReplacement();
        terminalAdmissionDoesNotReopen();
        throwingDiagnosticsAreInert();
        expiryResumeRequiresFreshGeneration();
        terminalResumeCannotReopen();
        resumeBeforeWatchdogAndQueuedEnd();
        repeatedResumeCannotRetryAdmission();
        recoveryWithoutFreshReplyIsBounded();
        recoveryNeedsItsOwnPauseAndNoClockRegression();
        delayedDriverFailureAndRecoveryDeadlineStayTerminal();
        queuedFormerResumeCannotCrossLaterPause();
        System.out.println("ExperimentSessionAppStatusOwnerTest PASS checks="+checks);
    }
    static void queuedStartAndOldOwnerAreFenced(){
        Fixture f=new Fixture();f.owner.started(1,1);f.owner.ended(1,1);f.flush();
        check(f.reads==0&&f.opens==0,"Drop before queued start must be inert");
        f.start(2,2);Consumer<ExperimentSessionAppStatusOwner.Observation> old=f.pending;
        f.start(3,3);old.accept(observation(2,f.now,true,false,false));f.flush();
        check(f.starts==0,"former callback cannot admit replacement");
        f.fresh(3);f.owner.ended(2,2);f.flush();
        check(f.starts==1&&f.closes==0,"former Drop cannot close replacement");
        f.owner.ended(3,3);f.flush();check(f.closes==1&&f.readerCloses==2,"exact owner retires readers and driver once");
        f.owner.started(3,3);f.flush();check(f.starts==1,"retired identity never reopens");
    }
    static void continuousAckDoesNotNeedPanelOrRevisionChange(){
        Fixture f=new Fixture();f.start(1,7);check(f.starts==0,"initialization alone cannot bind");f.fresh(7);
        long initial=f.source.get().readId;
        for(int i=0;i<8;i++){f.owner.started(1,7);f.at(f.now+500_000_000L);f.fresh(7);}
        check(f.starts==1&&f.opens==1&&f.closes==0,"panel-like repeated same owner never duplicates lifetime");
        check(f.source.get().readId>initial&&f.source.get().state.revision==0,"fresh worker acknowledgements preserve idle product revision");
    }
    static void failedQueriesAgeAndRetire(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);long first=f.source.get().readId;
        f.at(f.now+500_000_000L);f.reply(observation(1,f.now,false,false,false));
        check(f.source.get().readId==first&&f.source.get().ageMs==500,"rejection cannot refresh previous witness");
        f.at(f.now+500_000_000L);Consumer<ExperimentSessionAppStatusOwner.Observation> late=f.pending;
        f.at(first+1_500_000_001L);
        check(f.source.get()==null&&f.closes==1&&f.readerCloses==1,"expired witness retires app provider and reader");
        late.accept(observation(1,f.now,true,false,false));f.flush();
        check(f.starts==1&&f.closes==1,"late successful query cannot reopen expired identity");
    }
    static void absentCallbackStillRetires(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);long first=f.now;
        f.at(first+500_000_000L);check(f.pending!=null,"second request held");
        f.at(first+1_500_000_001L);
        check(f.closes==1&&f.source.get()==null,"watchdog closes even if shell never invokes callback");
        check(f.reads==2,"stalled callback never accumulates more queries");
    }
    static void closedAndWorkerLossRetire(){
        for(boolean closed:new boolean[]{false,true}){
            Fixture f=new Fixture();f.start(1,1);f.fresh(1);f.at(f.now+500_000_000L);
            f.reply(observation(1,f.now,closed,closed,!closed));
            check(f.closes==1&&f.source.get()==null,"Closed or worker loss retires immediately");
            f.at(f.now+2_000_000_000L);check(f.reads==2,"retirement stops observation loop");
        }
    }
    static void futureAndRegressingAcksCannotRefresh(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);long first=f.now;
        f.at(first+500_000_000L);f.reply(observation(1,f.now+1,true,false,false));
        check(f.source.get().readId==first,"future query timestamp cannot refresh");
        f.at(first+1_000_000_000L);f.reply(observation(1,first-1,true,false,false));
        check(f.source.get().readId==first,"regressing completion cannot refresh");
        f.at(first+1_500_000_001L);check(f.closes==1,"invalid evidence ages out");
    }
    static void wrongEpochResponsesCannotRefreshOrRetireReplacement(){
        for(int kind=0;kind<3;kind++){
            Fixture f=new Fixture();f.start(1,2);f.fresh(2);long first=f.now;
            f.at(first+500_000_000L);f.reply(observation(1,f.now,kind==0,kind==1,kind==2));
            check(f.closes==0&&f.source.get().readId==first,"wrong epoch accepted/Closed/workerClosed has no effect on current owner");
            f.at(first+1_500_000_001L);check(f.closes==1,"wrong epoch read cannot defer bounded expiry");
        }
        Fixture f=new Fixture();f.start(1,1);Consumer<ExperimentSessionAppStatusOwner.Observation> old=f.pending;
        f.start(2,2);f.fresh(2);old.accept(observation(1,f.now,true,true,true));f.flush();
        check(f.closes==0&&f.starts==1,"old owner's terminal callback cannot retire new owner");
    }
    static void terminalAdmissionDoesNotReopen(){
        Fixture f=new Fixture();f.failStart=true;f.start(1,1);f.fresh(1);
        f.at(f.now+2_000_000_000L);
        check(f.reads==1&&f.readerCloses==1,"terminal admission retires independent polling");
        check(f.starts==1&&f.closes==1,"admission failure cannot retry same native owner");
        f.failStart=false;f.start(2,2);f.fresh(2);check(f.starts==2,"actual new native owner can make new attempt");
    }
    // Reflection keeps the failed-before control runnable against the real old
    // implementation: its only available same-owner start route is inert.
    static void lifecycle(Fixture f,long id,long epoch,boolean resume){
        try {
            java.lang.reflect.Method method=ExperimentSessionAppStatusOwner.class.getDeclaredMethod(
                resume?"resumed":"paused",long.class,long.class);
            method.invoke(f.owner,id,epoch);
        } catch(NoSuchMethodException oldImplementation) {
            if(resume)f.owner.started(id,epoch);
        } catch(Exception error){throw new AssertionError(error);}
    }
    static void expiryResumeRequiresFreshGeneration(){
        Fixture f=new Fixture();f.start(1,7);f.fresh(7);
        Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> oldSource=f.source;
        long oldRead=f.now;lifecycle(f,1,7,false);
        f.at(oldRead+500_000_000L);
        Consumer<ExperimentSessionAppStatusOwner.Observation> old=f.pending;
        f.at(oldRead+2_000_000_000L);
        check(f.closes==1&&oldSource.get()==null,"expiry withdraws the old registration");
        lifecycle(f,1,7,true);f.flush();
        check(f.opens==2&&f.starts==1,"native resume opens a new observation generation before registration");
        old.accept(observation(7,f.now,true,false,false));f.flush();
        check(f.starts==1&&oldSource.get()==null,"late same-owner callback and old source cannot resurrect status");
        f.reply(observation(7,oldRead,true,false,false));
        check(f.starts==1,"pre-resume snapshot cannot admit recovery");
        f.at(f.now+500_000_000L);f.fresh(7);
        check(f.starts==2&&f.source.get().readId==f.now,"fresh current query registers same native epoch once");
        check(f.source.get().state.revision==0,"recovery cannot mutate product revision");
        lifecycle(f,1,7,true);f.flush();
        check(f.opens==2&&f.starts==2,"duplicate resume consumes no new generation");
        f.owner.ended(1,7);f.flush();check(f.closes==2&&f.readerCloses==2,"nativeEnd closes recovered generation once");
        lifecycle(f,1,7,false);lifecycle(f,1,7,true);f.flush();
        check(f.opens==2,"nativeEnd remains permanent");
    }
    static void terminalResumeCannotReopen(){
        for(int kind=0;kind<3;kind++){
            Fixture f=new Fixture();f.failStart=kind==2;f.start(1,1);f.fresh(1);
            if(kind<2){f.at(f.now+500_000_000L);f.reply(observation(1,f.now,true,kind==0,kind==1));}
            lifecycle(f,1,1,false);lifecycle(f,1,1,true);f.flush();
            check(f.opens==1&&f.closes==1,"Closed, worker loss and admission failure are permanent across native resume");
        }
    }
    static void resumeBeforeWatchdogAndQueuedEnd(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);lifecycle(f,1,1,false);
        f.now+=2_000_000_000L;lifecycle(f,1,1,true);f.owner.ended(1,1);f.flush();
        check(f.opens==1&&f.closes==1,"synchronous nativeEnd fences queued recovery");
        Fixture g=new Fixture();g.start(1,1);g.fresh(1);lifecycle(g,1,1,false);
        g.now+=2_000_000_000L;lifecycle(g,1,1,true);g.flush();g.fresh(1);
        check(g.opens==2&&g.starts==2&&g.closes==1,"resume arriving before queued watchdog retires stale registration then admits fresh query");
        lifecycle(g,1,1,false);g.start(2,2);g.fresh(2);lifecycle(g,1,1,true);g.flush();
        check(g.opens==3&&g.starts==3,"former native resume cannot reopen or retire replacement");
    }
    static void repeatedResumeCannotRetryAdmission(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);lifecycle(f,1,1,false);
        f.at(f.now+2_000_000_000L);lifecycle(f,1,1,true);f.flush();f.failStart=true;f.fresh(1);
        for(int i=0;i<3;i++){lifecycle(f,1,1,false);lifecycle(f,1,1,true);f.flush();}
        check(f.opens==2&&f.starts==2&&f.closes==2,"terminal recovery admission failure cannot become a resume retry loop");
    }
    static void recoveryWithoutFreshReplyIsBounded(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);lifecycle(f,1,1,false);
        f.at(f.now+2_000_000_000L);lifecycle(f,1,1,true);f.flush();
        f.at(f.now+2_000_000_000L);
        check(f.readerCloses==2&&f.starts==1,"recovery without a fresh callback closes its reader after bounded deadline");
        lifecycle(f,1,1,false);lifecycle(f,1,1,true);f.flush();
        check(f.opens==2,"unresponsive recovery cannot become an automatic resume loop");
    }
    static void recoveryNeedsItsOwnPauseAndNoClockRegression(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);f.at(f.now+2_000_000_000L);
        lifecycle(f,1,1,true);f.flush();check(f.opens==1,"resume without preceding native pause is inert");
        Fixture g=new Fixture();g.start(1,1);g.fresh(1);lifecycle(g,1,1,false);
        long first=g.now;g.now=first-1;g.tasks.remove(0).action.run();
        g.now=first+1; lifecycle(g,1,1,true);g.flush();
        check(g.opens==1&&g.readerCloses==1,"clock regression is permanently terminal across resume");
        Fixture h=new Fixture();h.start(1,1);h.fresh(1);lifecycle(h,1,1,false);
        h.start(2,2);h.fresh(2);h.at(h.now+2_000_000_000L);
        lifecycle(h,2,2,true);h.flush();check(h.opens==2,"replacement cannot inherit the former owner's pause");
    }
    static void delayedDriverFailureAndRecoveryDeadlineStayTerminal(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);lifecycle(f,1,1,false);
        f.driverClosed=true;f.now+=2_000_000_000L;lifecycle(f,1,1,true);f.flush();
        check(f.opens==1&&f.closes==1,"resume cannot misclassify delayed terminal Binder failure as ACK expiry");
        Fixture g=new Fixture();g.start(1,1);g.fresh(1);lifecycle(g,1,1,false);
        g.at(g.now+2_000_000_000L);lifecycle(g,1,1,true);g.flush();
        // Complete after the deadline before the queued watchdog gets a turn.
        g.now+=2_000_000_000L;g.pending.accept(observation(1,g.now,true,false,false));
        Task reply=g.tasks.remove(g.tasks.size()-1);reply.action.run();
        check(g.starts==1&&g.readerCloses==2,"late fresh reply cannot bypass recovery deadline behind queued watchdog");
    }
    static void queuedFormerResumeCannotCrossLaterPause(){
        Fixture f=new Fixture();f.start(1,1);f.fresh(1);lifecycle(f,1,1,false);
        f.at(f.now+2_000_000_000L);lifecycle(f,1,1,true);
        lifecycle(f,1,1,false);f.flush();
        check(f.opens==1&&f.starts==1,"queued former resume is fenced by later pause before its Runnable executes");
        lifecycle(f,1,1,true);f.flush();f.fresh(1);
        check(f.opens==2&&f.starts==2,"only latest paired native resume admits one new observation generation");
        lifecycle(f,1,1,false);f.at(f.now+2_000_000_000L);
        lifecycle(f,1,1,true);lifecycle(f,1,1,false);lifecycle(f,1,1,true);f.flush();f.fresh(1);
        check(f.opens==3&&f.starts==3,"two queued lifecycle cycles cannot create an intermediate recovery generation");
    }
    static void throwingDiagnosticsAreInert(){
        Fixture f=new Fixture();ExperimentSessionAppStatusOwner noisy=new ExperimentSessionAppStatusOwner(f,f,f,f,
            marker->{throw new IllegalStateException("logging");});
        noisy.started(1,1);f.flush();f.fresh(1);noisy.ended(1,1);f.flush();
        check(f.starts==1&&f.closes==1,"diagnostics cannot change owner lifecycle");
    }
}
