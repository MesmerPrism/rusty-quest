package io.github.mesmerprism.rustyquest.peer_rendezvous;

/** Process-local authenticated handshake only. No durable readiness or authority grant. */
public final class LiveBleObservationState {
    public static final long MAX_AGE=5_000L;
    private final String run, session, peer, expectedPeer, role;
    private final long epoch,start,duration;
    private String boot, group, ownerIp, localIp, remoteBoot, remoteRole;
    private long authenticatedAt=-1;
    private boolean connected;
    private Object connectedPeer;
    public LiveBleObservationState(String run,String session,long epoch,String peer,String expected,String role,long start,long duration) {
        if(!safe(run)||!safe(session)||!safe(peer)||!safe(expected)||peer.equals(expected)||epoch<=0
                ||!("group_owner".equals(role)||"client".equals(role)))throw new IllegalArgumentException("live_identity");
        if(start<0||duration<3_000L||duration>10_000L)throw new IllegalArgumentException("live_deadline");
        this.start=start;this.duration=duration;
        this.run=run;this.session=session;this.epoch=epoch;this.peer=peer;expectedPeer=expected;this.role=role;
    }
    private static boolean safe(String s){return s!=null&&s.matches("[A-Za-z0-9_.-]{4,32}");}
    public static void requireCaller(String caller,boolean sameSigner,boolean permission) {
        if(!"io.github.mesmerprism.rustyquest.directp2p".equals(caller)||!sameSigner||!permission)
            throw new SecurityException("live_observation_caller");
    }
    public static long remaining(long start,long now,long duration){return start<0||now<start||duration<=0||now-start>=duration?0:duration-(now-start);}
    public synchronized void clear(){authenticatedAt=-1;boot=null;group=null;}
    synchronized void connection(boolean live){clear();connected=live;}
    synchronized boolean connectionFrom(Object peer,boolean live){
        if(peer==null)return false;
        if(live){if(connectedPeer!=null&&!connectedPeer.equals(peer))return false;connectedPeer=peer;connection(true);return true;}
        if(connectedPeer==null||!connectedPeer.equals(peer))return false;
        connection(false);connectedPeer=null;return true;
    }
    synchronized void requireConnectedPeer(Object peer){
        if(!connected||peer==null||connectedPeer==null||!connectedPeer.equals(peer))
            throw new IllegalArgumentException("live_connection_identity");
    }
    synchronized void authenticatedFrom(Object peer,BleRoleReadiness.Observation o,String remoteBoot,String remoteRole,long now){
        requireConnectedPeer(peer);authenticated(o,remoteBoot,remoteRole,now);
    }
    synchronized void authenticated(BleRoleReadiness.Observation o,String remoteBoot,String remoteRole,long now) {
        if(!connected||remaining(start,now,duration)<=0){clear();throw new IllegalArgumentException("live_deadline_or_disconnected");}
        BleRoleReadiness.requireFresh(o,o.boot,role,now);
        if(remoteBoot==null||!remoteBoot.matches("[a-f0-9]{16}")||role.equals(remoteRole)
                ||!("group_owner".equals(remoteRole)||"client".equals(remoteRole)))throw new IllegalArgumentException("live_remote_identity");
        boot=o.boot;group=o.group;ownerIp=o.ownerIp;localIp=o.localIp;this.remoteBoot=remoteBoot;this.remoteRole=remoteRole;authenticatedAt=now;
    }
    synchronized String[] read(String requestedRun,String requestedSession,long requestedEpoch,String requestedPeer,String requestedExpected,
            BleRoleReadiness.Observation o,long now) {
        if(!run.equals(requestedRun)||!session.equals(requestedSession)||epoch!=requestedEpoch||!peer.equals(requestedPeer)
                ||!expectedPeer.equals(requestedExpected))throw new IllegalArgumentException("live_request_identity");
        if(!connected||remaining(start,now,duration)<=0){clear();throw new IllegalArgumentException("live_deadline_or_disconnected");}
        if(authenticatedAt<0||now<authenticatedAt||now-authenticatedAt>MAX_AGE)throw new IllegalArgumentException("live_handshake_stale");
        try{BleRoleReadiness.requireFresh(o,boot,role,now);}catch(RuntimeException changed){clear();throw changed;}
        if(!group.equals(o.group)||!ownerIp.equals(o.ownerIp)||!localIp.equals(o.localIp)){clear();throw new IllegalArgumentException("live_group_changed");}
        return new String[]{run,session,Long.toString(epoch),peer,expectedPeer,boot,group,role,ownerIp,localIp,remoteBoot,remoteRole,Long.toString(authenticatedAt),Long.toString(o.observedAt)};
    }
}
