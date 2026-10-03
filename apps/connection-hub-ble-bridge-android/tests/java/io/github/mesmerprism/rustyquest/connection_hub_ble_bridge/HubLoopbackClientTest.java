package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;
import java.net.*;
import java.security.SecureRandom;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import io.github.mesmerprism.rustymanifold.broker.*;

/** Actual owner HTTP server/runtime integration, with explicitly modeled authority port only. */
public final class HubLoopbackClientTest {
    static int cases;
    interface Action{void run()throws Exception;}
    static void check(boolean condition){if(!condition)throw new AssertionError();cases++;}
    static void denied(Action a)throws Exception{try{a.run();throw new AssertionError("accepted rejected route");}catch(Exception expected){cases++;}}
    static byte[] auth(String token)throws Exception{return new JSONObject().put("$schema","rusty.quest.connection_hub.socket_authenticate.v2").put("type","authenticate").put("session",token).toString().getBytes(StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        final JSONObject command=new JSONObject().put("$schema","rusty.quest.connection_hub.surface_command.v2").put("type","surface.command").put("request_id","host.test").put("request_sequence",1).put("surface_id","surface.concurrent_stereo.controls").put("command","command.concurrent_stereo.own").put("args",new JSONObject());
        HubLoopbackClient.validate(new JSONObject(new String(auth("a".repeat(32)),StandardCharsets.UTF_8)),true);cases++;
        HubLoopbackClient.validate(command,false);cases++;
        final JSONObject keepalive=new JSONObject().put("$schema","rusty.quest.connection_hub.keepalive.v2").put("type","keepalive").put("request_sequence",2);
        HubLoopbackClient.validate(keepalive,false);cases++;
        denied(new Action(){public void run()throws Exception{HubLoopbackClient.validate(new JSONObject(keepalive.toString()).put("endpoint","127.0.0.2"),false);}});
        for(final String bad:new String[]{"command.concurrent_stereo.start","command.concurrent_stereo.renew","command.spatial_camera_panel.locked_playlist.next"})denied(new Action(){public void run()throws Exception{HubLoopbackClient.validate(new JSONObject(command.toString()).put("command",bad),false);}});
        denied(new Action(){public void run()throws Exception{HubLoopbackClient.validate(new JSONObject(command.toString()).put("args",new JSONObject().put("policy",0)),false);}});
        denied(new Action(){public void run()throws Exception{HubLoopbackClient.validate(new JSONObject(command.toString()).put("endpoint","localhost"),false);}});
        final String key="dGhlIHNhbXBsZSBub25jZQ==",upgrade="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n";
        HubLoopbackClient.requireUpgrade(upgrade,key);cases++;
        denied(new Action(){public void run()throws Exception{HubLoopbackClient.requireUpgrade(upgrade.replace("s3pPLMB","S3pPLMB"),key);}});
        denied(new Action(){public void run()throws Exception{HubLoopbackClient.requireUpgrade(upgrade.replace("\r\n\r\n","\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"),key);}});
        final int[] connects={0};HubLoopbackClient.Sink quiet=new HubLoopbackClient.Sink(){public void frame(byte[] b){}public void unavailable(){}};
        final Map<String,Object> controllerSlots=new HashMap<>();final Object oldPeer=new Object(),replacementPeer=new Object();controllerSlots.put("device",replacementPeer);
        check(!HubGattBridge.removeCurrentValue(controllerSlots,oldPeer));check(controllerSlots.get("device")==replacementPeer);
        check(HubGattBridge.removeCurrentValue(controllerSlots,replacementPeer));check(controllerSlots.isEmpty());controllerSlots.put("device",oldPeer);
        final HubLoopbackClient expired=new HubLoopbackClient(new HubLoopbackClient.Readiness(){public void requireCurrent(){}},quiet,System.nanoTime()-1,new HubLoopbackClient.Connector(){public Socket open(){connects[0]++;return new Socket();}});
        denied(new Action(){public void run()throws Exception{expired.send(auth("a".repeat(32)));}});check(connects[0]==0);
        final HubLoopbackClient unavailable=new HubLoopbackClient(new HubLoopbackClient.Readiness(){public void requireCurrent(){throw new SecurityException("actual signature/listener prerequisite unavailable");}},quiet,System.nanoTime()+5_000_000_000L,new HubLoopbackClient.Connector(){public Socket open(){connects[0]++;return new Socket();}});
        denied(new Action(){public void run()throws Exception{unavailable.send(auth("a".repeat(32)));}});check(connects[0]==0);
        // The following native decision is explicitly a host model, never an actual grant.
        final ConnectionHubAuthorityPort authority=(ConnectionHubAuthorityPort)java.lang.reflect.Proxy.newProxyInstance(HubLoopbackClientTest.class.getClassLoader(),new Class[]{ConnectionHubAuthorityPort.class},new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] values){
            String name=method.getName();if(name.equals("exportOpaqueState"))return "model.authority.not-device";
            if(name.equals("trustAndOpenSession"))return new ConnectionHubAuthorityPort.Receipt(true,"model", "{}","model.session",1,System.currentTimeMillis()+60_000,null,1);
            if(name.equals("replaceTransport"))return new ConnectionHubAuthorityPort.Receipt(true,"model", "{}","model.session",((Long)values[2])+1,System.currentTimeMillis()+60_000,null,1);
            return ConnectionHubAuthorityPort.Receipt.rejected("modeled_native_authority_denied");
        }});
        final ConnectionHubStateStore store=new ConnectionHubStateStore(){ConnectionHubStateStore.State value=ConnectionHubStateStore.State.stopped();public State load(){return value;}public void save(State next){value=next;}public void clear(){value=ConnectionHubStateStore.State.stopped();}};
        final ConnectionHubRuntime runtime=new ConnectionHubRuntime(authority,store,new HubSurfaceRegistry(),new SecureRandom());runtime.startRequested();runtime.noteListenerStarted();
        String cookie=runtime.pair(new JSONObject().put("$schema","rusty.quest.connection_hub.pair_request.v1").put("pairing_code",runtime.pairingCodeForWearer()).put("controller_identity_sha256","a".repeat(64)),"model.wearer.not-device").getString("session");
        final ConnectionHubHttpServer server=new ConnectionHubHttpServer(runtime,new ConnectionHubHttpServer.AssetLoader(){public ConnectionHubHttpServer.Asset load(String p){return null;}});
        final int port=server.start(0);final BlockingQueue<JSONObject> output=new LinkedBlockingQueue<>();final CountDownLatch closed=new CountDownLatch(1);
        final HubLoopbackClient client=new HubLoopbackClient(new HubLoopbackClient.Readiness(){public void requireCurrent(){if(!runtime.listenerEnabled())throw new SecurityException("retired actual host listener");}},new HubLoopbackClient.Sink(){public void frame(byte[] b)throws Exception{output.add(new JSONObject(HubBleFrames.text(b)));}public void unavailable(){HubGattBridge.removeCurrentValue(controllerSlots,oldPeer);closed.countDown();}},System.nanoTime()+10_000_000_000L,new HubLoopbackClient.Connector(){public Socket open()throws Exception{Socket s=new Socket(InetAddress.getLoopbackAddress(),port);s.setSoTimeout(250);return s;}});
        try{
            client.send(auth(cookie));JSONObject receipt=output.poll(3,TimeUnit.SECONDS);check(receipt!=null&&"authentication_receipt".equals(receipt.getString("type")));check(receipt.getLong("transport_epoch")==2);check(runtime.requireSession(cookie).transportEpoch==2);
            JSONObject snapshot=output.poll(3,TimeUnit.SECONDS);check(snapshot!=null&&"surface_snapshot".equals(snapshot.getString("type")));
            client.send(command.toString().getBytes(StandardCharsets.UTF_8));JSONObject denied=output.poll(3,TimeUnit.SECONDS);check(denied!=null&&!denied.optBoolean("provider_applied",false));
            runtime.stopRequested();check(closed.await(3,TimeUnit.SECONDS));denied(new Action(){public void run()throws Exception{client.send(command.toString().getBytes(StandardCharsets.UTF_8));}});
            check(controllerSlots.isEmpty());controllerSlots.put("device",replacementPeer);check(!HubGattBridge.removeCurrentValue(controllerSlots,oldPeer));check(controllerSlots.get("device")==replacementPeer);
        }finally{client.close();server.close();}
        System.out.println("HubLoopbackClientTest PASS "+cases+" production cases; modeled native authority, no devices");
    }
}
