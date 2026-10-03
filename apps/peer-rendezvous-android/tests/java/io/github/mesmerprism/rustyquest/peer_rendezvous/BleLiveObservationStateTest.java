package io.github.mesmerprism.rustyquest.peer_rendezvous;

import io.github.mesmerprism.rustyquest.directp2p.BleObservationBarrier;

/** Actual process state and barrier predicates; no Android/device or owner grant. */
public final class BleLiveObservationStateTest {
    private static int cases;
    private interface Attempt {void run() throws Exception;}
    private static void pass(boolean value){if(!value)throw new AssertionError("assertion");cases++;}
    private static void deny(Attempt a)throws Exception{try{a.run();}catch(RuntimeException rejected){cases++;return;}throw new AssertionError("denial required");}
    private static final String BOOT="1111111111111111",REMOTE="2222222222222222",GROUP="3333333333333333";
    private static BleRoleReadiness.Observation observation(String boot,String group,String role,long at){return new BleRoleReadiness.Observation(boot,group,role,"192.168.49.1","192.168.49.1",at);}
    private static LiveBleObservationState fresh(){LiveBleObservationState s=new LiveBleObservationState("test-run","test-session",7,"peer-one","peer-two","group_owner",1000,10000);s.connection(true);s.authenticated(observation(BOOT,GROUP,"group_owner",1000),REMOTE,"client",1100);return s;}
    private static String[] read(LiveBleObservationState s,long now){return s.read("test-run","test-session",7,"peer-one","peer-two",observation(BOOT,GROUP,"group_owner",1000),now);}
    public static void main(String[] args)throws Exception{
        LiveBleObservationState.requireCaller("io.github.mesmerprism.rustyquest.directp2p",true,true);cases++;
        deny(()->LiveBleObservationState.requireCaller("other.package",true,true));deny(()->LiveBleObservationState.requireCaller("io.github.mesmerprism.rustyquest.directp2p",false,true));deny(()->LiveBleObservationState.requireCaller("io.github.mesmerprism.rustyquest.directp2p",true,false));
        pass(read(fresh(),1200).length==14);
        deny(()->new LiveBleObservationState("test-run","test-session",7,"peer-one","peer-two","group_owner",1000,10000).read("test-run","test-session",7,"peer-one","peer-two",observation(BOOT,GROUP,"group_owner",1000),1200));
        deny(()->fresh().read("wrong-run","test-session",7,"peer-one","peer-two",observation(BOOT,GROUP,"group_owner",1000),1200));
        deny(()->fresh().read("test-run","wrong-session",7,"peer-one","peer-two",observation(BOOT,GROUP,"group_owner",1000),1200));
        deny(()->fresh().read("test-run","test-session",8,"peer-one","peer-two",observation(BOOT,GROUP,"group_owner",1000),1200));
        deny(()->fresh().read("test-run","test-session",7,"peer-two","peer-one",observation(BOOT,GROUP,"group_owner",1000),1200));
        deny(()->read(fresh(),6101));deny(()->read(fresh(),1099));
        LiveBleObservationState changed=fresh();deny(()->changed.read("test-run","test-session",7,"peer-one","peer-two",observation(BOOT,"4444444444444444","group_owner",1000),1200));deny(()->read(changed,1200));
        LiveBleObservationState missing=fresh();deny(()->missing.read("test-run","test-session",7,"peer-one","peer-two",null,1200));deny(()->read(missing,1200));
        deny(()->fresh().read("test-run","test-session",7,"peer-one","peer-two",observation(REMOTE,GROUP,"group_owner",1000),1200));
        deny(()->fresh().authenticated(observation(BOOT,GROUP,"group_owner",1000),REMOTE,"group_owner",1100));
        LiveBleObservationState disconnected=fresh();disconnected.connection(false);deny(()->read(disconnected,1200));deny(()->disconnected.authenticated(observation(BOOT,GROUP,"group_owner",1000),REMOTE,"client",1200));disconnected.connection(true);deny(()->read(disconnected,1200));disconnected.authenticated(observation(BOOT,GROUP,"group_owner",1100),REMOTE,"client",1200);pass(disconnected.read("test-run","test-session",7,"peer-one","peer-two",observation(BOOT,GROUP,"group_owner",1100),1300).length==14);
        LiveBleObservationState cleared=fresh();cleared.clear();deny(()->read(cleared,1200));
        Object currentGatt=new Object(),retiredGatt=new Object();LiveBleObservationState current=fresh();
        pass(!BleRendezvousGattClient.acceptCurrentConnectionCallback(retiredGatt,currentGatt,current,false));
        pass(read(current,1200).length==14); // stale disconnect cannot clear current authenticated state
        pass(!BleRendezvousGattClient.acceptCurrentConnectionCallback(retiredGatt,currentGatt,current,true));
        pass(read(current,1200).length==14); // stale connect cannot reset current authentication
        pass(!BleRendezvousGattClient.acceptCurrentConnectionCallback(null,null,current,false));
        pass(read(current,1200).length==14); // no current lifecycle cannot attribute a callback
        pass(BleRendezvousGattClient.acceptCurrentConnectionCallback(currentGatt,currentGatt,current,false));
        deny(()->read(current,1200)); // actual current disconnect invalidates authentication
        pass(BleRendezvousGattClient.acceptCurrentConnectionCallback(currentGatt,currentGatt,current,true));
        deny(()->read(current,1200)); // reconnect requires a new real authenticated exchange
        current.authenticated(observation(BOOT,GROUP,"group_owner",1100),REMOTE,"client",1200);
        pass(current.read("test-run","test-session",7,"peer-one","peer-two",observation(BOOT,GROUP,"group_owner",1100),1300).length==14);
        Object serverPeer=new Object(),foreignPeer=new Object();LiveBleObservationState server=new LiveBleObservationState("test-run","test-session",7,"peer-one","peer-two","group_owner",1000,10000);
        pass(!server.connectionFrom(foreignPeer,false));pass(server.connectionFrom(serverPeer,true));
        server.authenticatedFrom(serverPeer,observation(BOOT,GROUP,"group_owner",1000),REMOTE,"client",1100);
        pass(!server.connectionFrom(foreignPeer,true));pass(!server.connectionFrom(foreignPeer,false));
        pass(read(server,1200).length==14);
        deny(()->server.requireConnectedPeer(foreignPeer));
        deny(()->server.authenticatedFrom(foreignPeer,observation(BOOT,GROUP,"group_owner",1000),REMOTE,"client",1200));
        pass(read(server,1200).length==14);
        pass(server.connectionFrom(serverPeer,false));deny(()->read(server,1200));
        deny(()->server.authenticatedFrom(serverPeer,observation(BOOT,GROUP,"group_owner",1000),REMOTE,"client",1200));
        pass(server.connectionFrom(foreignPeer,true));deny(()->read(server,1200));
        deny(()->server.authenticatedFrom(serverPeer,observation(BOOT,GROUP,"group_owner",1000),REMOTE,"client",1200));
        server.authenticatedFrom(foreignPeer,observation(BOOT,GROUP,"group_owner",1100),REMOTE,"client",1200);
        pass(server.read("test-run","test-session",7,"peer-one","peer-two",observation(BOOT,GROUP,"group_owner",1100),1300).length==14);
        String[] proof=read(fresh(),1200);BleObservationBarrier.require(proof,"test-run","test-session",7,"peer-one","peer-two",BOOT,REMOTE,GROUP,"group_owner","192.168.49.1","192.168.49.1",1200);cases++;
        deny(()->BleObservationBarrier.require(proof,"test-run","test-session",8,"peer-one","peer-two",BOOT,REMOTE,GROUP,"group_owner","192.168.49.1","192.168.49.1",1200));
        deny(()->BleObservationBarrier.require(proof,"test-run","test-session",7,"peer-one","peer-two",BOOT,REMOTE,GROUP,"client","192.168.49.1","192.168.49.1",1200));
        deny(()->BleObservationBarrier.require(proof,"test-run","test-session",7,"peer-one","peer-two",BOOT,REMOTE,GROUP,"group_owner","192.168.49.1","192.168.49.1",6101));
        pass(BleObservationBarrier.launchReserve(false,5000)==35000);pass(BleObservationBarrier.launchReserve(true,5000)==45000);
        pass(LiveBleObservationState.remaining(1000,10999,10000)==1);pass(LiveBleObservationState.remaining(1000,11000,10000)==0);pass(LiveBleObservationState.remaining(1000,999,10000)==0);
        pass(BleObservationBarrier.remaining(1000,10999)==1);pass(BleObservationBarrier.remaining(1000,11000)==0);
        pass(BleObservationBarrier.waiting(1000,10999));pass(!BleObservationBarrier.waiting(1000,11000));pass(!BleObservationBarrier.waiting(1000,999));
        Class<?> c=Class.forName("io.github.mesmerprism.rustyquest.directp2p.DirectP2pLifecycle$AuthorizationWindow");
        java.lang.reflect.Constructor<?> constructor=c.getDeclaredConstructor(long.class,long.class,long.class,long.class);constructor.setAccessible(true);
        Object window=constructor.newInstance(1000L,2000L,61000L,5000L);java.lang.reflect.Method permits=c.getDeclaredMethod("permits",long.class,long.class,long.class);permits.setAccessible(true);
        pass((Boolean)permits.invoke(window,1000L,2000L,45000L));
        pass(!(Boolean)permits.invoke(window,47000L,48000L,15000L)); // expiry consumed during barrier wait
        pass(!(Boolean)permits.invoke(window,1000L,63000L,15000L)); // monotonic expiry at pre-effect
        pass(!(Boolean)permits.invoke(window,999L,3000L,15000L)); // wall rollback
        System.out.println("Actual live observation/barrier cases="+cases+" device_calls=0");
    }
}
