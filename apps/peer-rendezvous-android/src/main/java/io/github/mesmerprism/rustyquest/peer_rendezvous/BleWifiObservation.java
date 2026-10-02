package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Handler;
import android.os.SystemClock;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;

/** Only read-only Android requests. No Wi-Fi group or route mutation. */
final class BleWifiObservation {
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler handler;
    private final BleRendezvousConfig config;
    private int generation;
    private boolean closed;
    BleWifiObservation(Context context, Handler handler, BleRendezvousConfig config) throws Exception {
        this.handler=handler;this.config=config;
        String bootId=new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),StandardCharsets.US_ASCII).trim();
        if(!bootId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("wifi_boot_observation_unavailable");
        config.observationBoot=tag(bootId);
        config.readiness=new BleRoleReadiness(config.observationBoot);
        manager=context.getSystemService(WifiP2pManager.class);
        if(manager==null)throw new IllegalArgumentException("wifi_observer_unavailable");
        channel=manager.initialize(context,handler.getLooper(),()->{closed=true;generation++;config.observation=null;});
        if(channel==null)throw new IllegalArgumentException("wifi_observer_channel_unavailable");
    }
    void start(){sample();}
    void close(){closed=true;generation++;config.observation=null;channel.close();}
    private void sample() {
        if(closed)return;
        final int g=++generation;
        final long requested=SystemClock.elapsedRealtime();
        config.observation=null;
        try {
            requestInfo(g,info -> requestGroup(g,group ->
                requestInfo(g,confirmInfo -> requestGroup(g,confirmGroup -> {
                if(closed||g!=generation)return;
                try {
                    long now=SystemClock.elapsedRealtime();
                    if(now-requested>2_000||info==null||confirmInfo==null||!info.groupFormed
                            ||!confirmInfo.groupFormed||info.isGroupOwner!=confirmInfo.isGroupOwner
                            ||info.groupOwnerAddress==null||!info.groupOwnerAddress.equals(confirmInfo.groupOwnerAddress)
                            ||!sameGroup(group,confirmGroup)||group==null
                            ||info.groupOwnerAddress==null||group.getOwner()==null
                            ||group.isGroupOwner()!=info.isGroupOwner
                            ||group.getNetworkName()==null||group.getNetworkName().isEmpty())return;
                    String owner=info.groupOwnerAddress.getHostAddress();
                    String ownerMac=observedOwnerMac(group.getOwner().deviceAddress);
                    NetworkInterface iface=NetworkInterface.getByName(group.getInterface());
                    if(iface==null)return;
                    String local=null;
                    Enumeration<InetAddress> addresses=iface.getInetAddresses();
                    while(addresses.hasMoreElements()) {
                        String candidate=addresses.nextElement().getHostAddress();
                        if(BleRoleReadiness.address(candidate)) {
                            if(local!=null)return;local=candidate;
                        }
                    }
                    if(local==null)return;
                    String role=info.isGroupOwner?"group_owner":"client";
                    BleRoleReadiness.Observation observation=new BleRoleReadiness.Observation(
                            config.observationBoot,tag(config.sessionTag+"|"+group.getNetworkName()+"|"+owner+"|"+ownerMac),
                            role,owner,local,requested);
                    BleRoleReadiness.requireFresh(observation,config.observationBoot,config.rolePreference,now);
                    config.observation=observation;
                } catch(Exception ignored){config.observation=null;}
            }))));
        } catch(RuntimeException denied){config.observation=null;}
        handler.postDelayed(this::sample,2_000);
    }
    private boolean current(int g){return !closed&&g==generation;}
    private void requestInfo(int g,java.util.function.Consumer<WifiP2pInfo> next) {
        if(!current(g))return;
        try {manager.requestConnectionInfo(channel,value -> {
            if(!current(g))return;
            try {next.accept(value);}catch(RuntimeException unavailable){config.observation=null;}
        });}catch(RuntimeException unavailable){if(current(g))config.observation=null;}
    }
    private void requestGroup(int g,java.util.function.Consumer<WifiP2pGroup> next) {
        if(!current(g))return;
        try {manager.requestGroupInfo(channel,value -> {
            if(!current(g))return;
            try {next.accept(value);}catch(RuntimeException unavailable){config.observation=null;}
        });}catch(RuntimeException unavailable){if(current(g))config.observation=null;}
    }
    private static boolean sameGroup(WifiP2pGroup a,WifiP2pGroup b) {
        return a!=null&&b!=null&&a.getOwner()!=null&&b.getOwner()!=null
                &&a.isGroupOwner()==b.isGroupOwner()
                &&java.util.Objects.equals(a.getNetworkName(),b.getNetworkName())
                &&java.util.Objects.equals(a.getInterface(),b.getInterface())
                &&observedOwnerMac(a.getOwner().deviceAddress).equals(observedOwnerMac(b.getOwner().deviceAddress));
    }
    static String observedOwnerMac(String raw) {
        if(raw==null)throw new IllegalArgumentException("wifi_owner_identity_unavailable");
        String normalized=raw.toLowerCase(java.util.Locale.US);
        if(!normalized.matches("[a-f0-9]{2}(:[a-f0-9]{2}){5}")
                ||normalized.equals("00:00:00:00:00:00")||normalized.equals("02:00:00:00:00:00")
                ||(Integer.parseInt(normalized.substring(0,2),16)&1)!=0)
            throw new IllegalArgumentException("wifi_owner_identity_unavailable");
        return normalized;
    }
    static String tag(String text) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(int i=0;i<8;i++)out.append(String.format("%02x",bytes[i]&255));
        return out.toString();
    }
}
