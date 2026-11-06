package io.github.mesmerprism.rustyquest.native_renderer;

/** Publication/admission only. No preparation, seek or executor future is awaited under this monitor. */
final class SdkOwnerLifecycle {
    interface Revoker { String revoke(long actualEpoch); }
    static final class Attempt {
        final long revision,epoch,instance,session;int retries;boolean retryScheduled;
        Attempt(long revision,long epoch,long instance,long session){this.revision=revision;this.epoch=epoch;this.instance=instance;this.session=session;}
    }
    private long revision,actualEpoch,publishedEpoch;
    private Attempt pending;
    private boolean destroyed,terminal;
    private String phase="unbound";
    synchronized Attempt begin(long epoch,long instance,long session){
        if(destroyed||terminal)return null;
        pending=new Attempt(++revision,epoch,instance,session);phase="requested";return pending;
    }
    synchronized boolean current(Attempt attempt){return !destroyed&&pending==attempt&&attempt.revision==revision;}
    synchronized boolean runCurrent(Attempt attempt,Runnable action){if(!current(attempt))return false;action.run();return true;}
    synchronized void nativeBound(Attempt attempt){
        if(!current(attempt))throw new IllegalStateException("obsolete-native-bind");
        actualEpoch=attempt.epoch;publishedEpoch=0;phase="native-bound-restoring";
    }
    synchronized boolean publish(Attempt attempt,Runnable publication){
        if(!current(attempt)||actualEpoch!=attempt.epoch)return false;
        publication.run();publishedEpoch=actualEpoch;pending=null;phase="bound";return true;
    }
    synchronized boolean scheduleRetry(Attempt attempt){
        if(!current(attempt)||actualEpoch==attempt.epoch||attempt.retryScheduled)return false;
        if(attempt.retries++>=100){pending=null;phase="prebind-retry-exhausted";return false;}
        attempt.retryScheduled=true;phase="prebind-waiting";return true;
    }
    synchronized boolean resumeRetry(Attempt attempt){if(!current(attempt))return false;attempt.retryScheduled=false;return true;}
    synchronized void failed(Attempt attempt){if(current(attempt)){pending=null;phase=actualEpoch==attempt.epoch?"native-bound-failed":"prebind-rejected";}}
    synchronized String revoke(long requestedEpoch,boolean isTerminal,Revoker revoker){
        if(requestedEpoch<=0||(pending!=null?pending.epoch!=requestedEpoch:actualEpoch!=requestedEpoch))
            return "{\"status\":\"rejected\",\"reason\":\"sdk-binding-not-current\"}";
        ++revision;pending=null;publishedEpoch=0;terminal|=isTerminal;phase=isTerminal?"terminal-revoked":"revoked";
        return actualEpoch>0?revoker.revoke(actualEpoch):"{\"status\":\"accepted\",\"reason\":\"pending-bind-cancelled\"}";
    }
    synchronized long requestedEpoch(){return pending==null?0:pending.epoch;}
    synchronized long actualEpoch(){return actualEpoch;}
    synchronized long publishedEpoch(){return publishedEpoch;}
    synchronized long revokeEpoch(){return pending==null?actualEpoch:pending.epoch;}
    synchronized String phase(){return phase;}
    synchronized void destroyed(){destroyed=true;++revision;pending=null;publishedEpoch=0;}
}
