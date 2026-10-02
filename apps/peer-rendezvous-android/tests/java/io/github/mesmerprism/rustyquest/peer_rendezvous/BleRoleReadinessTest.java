package io.github.mesmerprism.rustyquest.peer_rendezvous;
import android.content.Intent;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
final class BleRoleReadinessTest {
 static int cases;
 interface Step {void run()throws Exception;}
 static void pass(String name,Step s)throws Exception{s.run();cases++;System.out.println("PASS "+name);}
 static void deny(String name,Step s)throws Exception{try{s.run();}catch(IllegalArgumentException|SecurityException e){cases++;System.out.println("PASS "+name);return;}throw new AssertionError(name);}
 static BleRoleReadiness.Observation own(long at){return new BleRoleReadiness.Observation("aaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbb","group_owner","192.168.49.1","192.168.49.1",at);}
 static BleRendezvousConfig config(String peer,String expected,String role,boolean v2){return configured("sess0001",peer,expected,role,v2,1);}
 static BleRendezvousConfig configured(String session,String peer,String expected,String role,boolean v2,long epoch){
  Intent i=new Intent("io.github.mesmerprism.rustyquest.peer_rendezvous.START").putExtra("enabled",true)
   .putExtra("run_id","testrun").putExtra("session_tag",session).putExtra("coordination_epoch",epoch).putExtra("peer_tag",peer)
   .putExtra("shared_secret","0123456789abcdef0123456789abcdef").putExtra("role_preference",role)
   .putExtra("observed_coordination_v2",v2).putExtra("expected_peer_tag",expected);
  BleRendezvousConfig c=BleRendezvousConfig.fromIntent(i);c.observationBoot="aaaaaaaaaaaaaaaa";
  c.readiness=new BleRoleReadiness(c.observationBoot);return c;
 }
 static String acceptedState(BleRendezvousConfig c)throws Exception {
  StringBuilder state=new StringBuilder().append(c.coordinatedPeer).append('|').append(c.remoteConfiguredRole)
    .append('|').append(c.resolvedRole).append('|').append(c.challenge).append('|').append(c.authenticatedObservedPeer);
  if(c.readiness!=null)for(String name:new String[]{"peer","peerBoot","peerGroup","peerRole"}) {
    java.lang.reflect.Field f=BleRoleReadiness.class.getDeclaredField(name);f.setAccessible(true);state.append('|').append(f.get(c.readiness));
  }
  return state.toString();
 }
 public static void main(String[] args)throws Exception {
  pass("fresh local observation",()->BleRoleReadiness.requireFresh(own(100),"aaaaaaaaaaaaaaaa","group_owner",101));
  deny("null observation",()->BleRoleReadiness.requireFresh(null,"aaaaaaaaaaaaaaaa","group_owner",101));
  deny("stale observation",()->BleRoleReadiness.requireFresh(own(100),"aaaaaaaaaaaaaaaa","group_owner",5101));
  deny("clock reversal",()->BleRoleReadiness.requireFresh(own(100),"aaaaaaaaaaaaaaaa","group_owner",99));
  deny("foreign boot",()->BleRoleReadiness.requireFresh(own(100),"cccccccccccccccc","group_owner",101));
  deny("configured role not observation",()->BleRoleReadiness.requireFresh(own(100),"aaaaaaaaaaaaaaaa","client",101));
  BleRoleReadiness guard=new BleRoleReadiness("aaaaaaaaaaaaaaaa");
  pass("complementary peer",()->guard.requirePeer(own(100),"group_owner",101,"peer0002","peer0002","cccccccccccccccc","bbbbbbbbbbbbbbbb","client","192.168.49.1","192.168.49.2"));
  pass("stable reconnect",()->guard.requirePeer(own(100),"group_owner",102,"peer0002","peer0002","cccccccccccccccc","bbbbbbbbbbbbbbbb","client","192.168.49.1","192.168.49.2"));
  deny("same GO roles",()->guard.requirePeer(own(100),"group_owner",101,"peer0002","peer0002","cccccccccccccccc","bbbbbbbbbbbbbbbb","group_owner","192.168.49.1","192.168.49.1"));
  deny("foreign peer",()->guard.requirePeer(own(100),"group_owner",101,"peer0002","peer0003","cccccccccccccccc","bbbbbbbbbbbbbbbb","client","192.168.49.1","192.168.49.2"));
  deny("foreign group",()->guard.requirePeer(own(100),"group_owner",101,"peer0002","peer0002","cccccccccccccccc","dddddddddddddddd","client","192.168.49.1","192.168.49.2"));
  deny("peer boot changed",()->guard.requirePeer(own(100),"group_owner",101,"peer0002","peer0002","dddddddddddddddd","bbbbbbbbbbbbbbbb","client","192.168.49.1","192.168.49.2"));
  deny("partial client address",()->guard.requirePeer(own(100),"group_owner",101,"peer0002","peer0002","cccccccccccccccc","bbbbbbbbbbbbbbbb","client","192.168.49.1","192.168.49.1"));
  pass("challenge echo",()->BleRoleReadiness.requireChallenge("aaaaaaaaaaaaaaaa","aaaaaaaaaaaaaaaa"));
  deny("stale challenge",()->BleRoleReadiness.requireChallenge("aaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbb"));
  pass("244 byte MTU",()->BleRoleReadiness.requirePayload(244,247));
  deny("default MTU",()->BleRoleReadiness.requirePayload(242,23));
  deny("payload too large",()->BleRoleReadiness.requirePayload(245,517));
  BleRendezvousConfig go=config("peer0001","peer0002","group_owner",true);go.observation=own(100);
  BleRendezvousConfig client=config("peer0002","peer0001","client",true);
  client.observation=new BleRoleReadiness.Observation("aaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbb","client","192.168.49.1","192.168.49.2",100);
  byte[] offer=BleRendezvousProtocol.buildMessage(go,"offer",1);
  JSONObject parsed=BleRendezvousProtocol.verify(offer,go.sharedSecret,go.sessionTag);
  pass("actual signed observed offer",()->BleRendezvousProtocol.requireObservedPeer(client,parsed));
  client.challenge=parsed.getString("n");byte[] proposal=BleRendezvousProtocol.buildMessage(client,"proposal",2);
  JSONObject p=BleRendezvousProtocol.verify(proposal,go.sharedSecret,go.sessionTag);
  pass("actual signed proposal join",()->{BleRendezvousProtocol.requireObservedPeer(go,p);BleRoleReadiness.requireChallenge(parsed.getString("n"),p.getString("x"));});
  go.challenge=p.getString("n");byte[] accept=BleRendezvousProtocol.buildMessage(go,"accept",3);
  JSONObject a=BleRendezvousProtocol.verify(accept,go.sharedSecret,go.sessionTag);
  pass("actual signed accept join",()->{BleRendezvousProtocol.requireObservedPeer(client,a);BleRoleReadiness.requireChallenge(p.getString("n"),a.getString("x"));});
  deny("tampered authenticated role",()->{org.json.JSONArray bad=new org.json.JSONArray(new String(proposal,StandardCharsets.UTF_8));bad.put(6,"g");BleRendezvousProtocol.verify(bad.toString().getBytes(StandardCharsets.UTF_8),go.sharedSecret,go.sessionTag);});
  deny("wrong session",()->BleRendezvousProtocol.verify(proposal,go.sharedSecret,"other001"));
  deny("v1 cannot stand in for observations",()->BleRendezvousProtocol.requireObservedPeer(go,new JSONObject().put("v",1)));
  deny("unknown field",()->{org.json.JSONArray bad=new org.json.JSONArray(new String(proposal,StandardCharsets.UTF_8));bad.put(true);BleRendezvousProtocol.verify(bad.toString().getBytes(StandardCharsets.UTF_8),go.sharedSecret,go.sessionTag);});
  pass("legacy v1 stays v1",()->{BleRendezvousConfig old=config("legacy01","legacy02","either",false);byte[] wire=BleRendezvousProtocol.buildMessage(old,"offer",1);if(BleRendezvousProtocol.verify(wire,old.sharedSecret,old.sessionTag).getInt("v")!=1)throw new AssertionError();});
  for(String role:new String[]{"group_owner","client"}) {
   BleRendezvousConfig left=config("legacy01","legacy02",role,false);
   BleRendezvousConfig right=config("legacy02","legacy01",role,false);
   byte[] badOffer=BleRendezvousProtocol.buildMessage(right,"offer",1);
   pass("actual client rejects "+role+"/"+role,()->{if(new BleCallsiteHarness.Client(left).verifyPeerMessage(badOffer,"offer",1)!=null)throw new AssertionError();});
   byte[] badProposal=BleRendezvousProtocol.buildMessage(right,"proposal",2);
   pass("actual server rejects "+role+"/"+role,()->{if(new BleCallsiteHarness.Server(left).proposal(badProposal))throw new AssertionError();});
  }
  pass("either resolves complementary",()->{String l=BleRoleReadiness.resolveRole("either","either","legacy01","legacy02");String rr=BleRoleReadiness.resolveRole("either","either","legacy02","legacy01");if(l.equals(rr))throw new AssertionError();});
  pass("actual client valid complementary v1",()->{BleRendezvousConfig l=config("legacy01","legacy02","group_owner",false),rr=config("legacy02","legacy01","client",false);if(new BleCallsiteHarness.Client(l).verifyPeerMessage(BleRendezvousProtocol.buildMessage(rr,"offer",1),"offer",1)==null)throw new AssertionError();});
  pass("actual server valid complementary v1",()->{BleRendezvousConfig l=config("legacy01","legacy02","group_owner",false),rr=config("legacy02","legacy01","client",false);if(!new BleCallsiteHarness.Server(l).proposal(BleRendezvousProtocol.buildMessage(rr,"proposal",2)))throw new AssertionError();});
  BleRendezvousConfig activeGo=config("peer0001","peer0002","group_owner",true);activeGo.observation=own(100);
  BleRendezvousConfig activeClient=config("peer0002","peer0001","client",true);activeClient.observation=client.observation;
  BleCallsiteHarness.Server actualServer=new BleCallsiteHarness.Server(activeGo);
  BleCallsiteHarness.Client actualClient=new BleCallsiteHarness.Client(activeClient);
  JSONObject actualOffer=actualClient.verifyPeerMessage(actualServer.offerMessage,"offer",1);
  pass("actual v2 client offer callback",()->{if(actualOffer==null)throw new AssertionError();});
  activeClient.challenge=actualOffer.getString("n");byte[] actualProposal=BleRendezvousProtocol.buildMessage(activeClient,"proposal",2);
  pass("actual v2 server proposal callback",()->{if(!actualServer.proposal(actualProposal))throw new AssertionError();});
  activeClient.challenge=BleRendezvousProtocol.verify(actualProposal,activeGo.sharedSecret,activeGo.sessionTag).getString("n");
  pass("actual v2 client accept callback",()->{if(actualClient.verifyPeerMessage(actualServer.statusMessage,"accept",3)==null)throw new AssertionError();});
  pass("actual v2 replay proposal denied",()->{if(actualServer.proposal(actualProposal))throw new AssertionError();});
  pass("actual v2 stale local callback denied",()->{activeClient.observation=new BleRoleReadiness.Observation("aaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbb","client","192.168.49.1","192.168.49.2",-6000);if(actualClient.verifyPeerMessage(actualServer.offerMessage,"offer",1)!=null)throw new AssertionError();activeClient.observation=client.observation;});
  pass("actual v2 unavailable callback denied",()->{activeClient.observation=null;if(actualClient.verifyPeerMessage(actualServer.offerMessage,"offer",1)!=null)throw new AssertionError();activeClient.observation=client.observation;});
  pass("actual v2 signed conflicting proposal denied",()->{BleRendezvousConfig conflict=config("peer0002","peer0001","group_owner",true);conflict.observation=own(100);conflict.challenge=actualOffer.getString("n");if(actualServer.proposal(BleRendezvousProtocol.buildMessage(conflict,"proposal",2)))throw new AssertionError();});
  pass("actual v2 signed stale challenge denied",()->{activeClient.challenge="ffffffffffffffff";if(actualServer.proposal(BleRendezvousProtocol.buildMessage(activeClient,"proposal",2)))throw new AssertionError();});
  String maxSession="s".repeat(32),maxGo="g".repeat(32),maxClient="c".repeat(32);
  BleRendezvousConfig max=configured(maxSession,maxClient,maxGo,"client",true,1790962800123L);
  max.observation=new BleRoleReadiness.Observation("aaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbb","client","192.168.137.254","192.168.137.253",100);
  max.observationBoot="aaaaaaaaaaaaaaaa";max.challenge="ffffffffffffffff";
  byte[] fullWire=BleRendezvousProtocol.buildMessage(max,"proposal",2);
  pass("actual 13-digit epoch maximum tags payload",()->{if(fullWire.length>244)throw new AssertionError();BleRoleReadiness.requirePayload(fullWire.length,247);if(BleRendezvousProtocol.verify(fullWire,max.sharedSecret,max.sessionTag).getLong("e")!=1790962800123L)throw new AssertionError();});
  max.coordinationEpoch=Long.MAX_VALUE;byte[] longestWire=BleRendezvousProtocol.buildMessage(max,"proposal",2);
  pass("maximum long epoch payload",()->{if(longestWire.length>244)throw new AssertionError();BleRoleReadiness.requirePayload(longestWire.length,247);if(BleRendezvousProtocol.verify(longestWire,max.sharedSecret,max.sessionTag).getLong("e")!=Long.MAX_VALUE)throw new AssertionError();});
  for(String rejected:new String[]{"epoch","sequence","challenge"}) {
   BleRendezvousConfig cleanClient=config("peer0002","peer0001","client",true);cleanClient.observation=client.observation;
   cleanClient.challenge="1111111111111111";BleCallsiteHarness.Client clientGuard=new BleCallsiteHarness.Client(cleanClient);
   BleRendezvousConfig badGo=config("peer0001","peer0002","group_owner",true);badGo.observation=own(100);
   badGo.observationBoot="eeeeeeeeeeeeeeee";badGo.observation=new BleRoleReadiness.Observation("eeeeeeeeeeeeeeee","bbbbbbbbbbbbbbbb","group_owner","192.168.49.1","192.168.49.1",100);
   badGo.challenge="1111111111111111";if(rejected.equals("epoch"))badGo.coordinationEpoch=2;
   if(rejected.equals("challenge"))badGo.challenge="2222222222222222";
   byte[] wrong=BleRendezvousProtocol.buildMessage(badGo,"accept",rejected.equals("sequence")?4:3);
   String before=acceptedState(cleanClient);
   pass("actual client "+rejected+" denial leaves accepted state",()->{if(clientGuard.verifyPeerMessage(wrong,"accept",3)!=null||!before.equals(acceptedState(cleanClient))||!clientGuard.peerNonces.isEmpty()||clientGuard.remotePeerTag!=null||clientGuard.evidence.authenticatedMessages!=0)throw new AssertionError();});
   BleRendezvousConfig goodGo=config("peer0001","peer0002","group_owner",true);goodGo.observation=own(100);goodGo.challenge="1111111111111111";
   pass("actual client valid after rejected "+rejected,()->{if(clientGuard.verifyPeerMessage(BleRendezvousProtocol.buildMessage(goodGo,"accept",3),"accept",3)==null)throw new AssertionError();});
   BleRendezvousConfig cleanGo=config("peer0001","peer0002","group_owner",true);cleanGo.observation=own(100);BleCallsiteHarness.Server serverGuard=new BleCallsiteHarness.Server(cleanGo);
   BleRendezvousConfig badClient=config("peer0002","peer0001","client",true);badClient.observationBoot="eeeeeeeeeeeeeeee";
   badClient.observation=new BleRoleReadiness.Observation("eeeeeeeeeeeeeeee","bbbbbbbbbbbbbbbb","client","192.168.49.1","192.168.49.2",100);
   badClient.challenge=BleRendezvousProtocol.verify(serverGuard.offerMessage,cleanGo.sharedSecret,cleanGo.sessionTag).getString("n");
   if(rejected.equals("epoch"))badClient.coordinationEpoch=2;if(rejected.equals("challenge"))badClient.challenge="2222222222222222";
   byte[] wrongProposal=BleRendezvousProtocol.buildMessage(badClient,"proposal",rejected.equals("sequence")?4:2);String serverBefore=acceptedState(cleanGo);
   pass("actual server "+rejected+" denial leaves accepted state",()->{if(serverGuard.proposal(wrongProposal)||!serverBefore.equals(acceptedState(cleanGo))||!serverGuard.acceptedProposalNonces.isEmpty()||serverGuard.authenticatedProposalCount!=0||serverGuard.evidence.authenticatedMessages!=0)throw new AssertionError();});
   BleRendezvousConfig goodClient=config("peer0002","peer0001","client",true);goodClient.observation=client.observation;goodClient.challenge=BleRendezvousProtocol.verify(serverGuard.offerMessage,cleanGo.sharedSecret,cleanGo.sessionTag).getString("n");
   pass("actual server valid after rejected "+rejected,()->{if(!serverGuard.proposal(BleRendezvousProtocol.buildMessage(goodClient,"proposal",2)))throw new AssertionError();});
  }
  pass("actual observed owner MAC normalization",()->{if(!BleWifiObservation.observedOwnerMac("AA:BB:CC:DD:EE:02").equals("aa:bb:cc:dd:ee:02"))throw new AssertionError();});
  deny("redacted owner MAC unavailable",()->BleWifiObservation.observedOwnerMac("02:00:00:00:00:00"));
  deny("missing owner MAC unavailable",()->BleWifiObservation.observedOwnerMac(null));
  pass("different owners cannot share group tag",()->{String l=BleWifiObservation.tag("sess|DIRECT-same|192.168.49.1|"+BleWifiObservation.observedOwnerMac("aa:bb:cc:dd:ee:02"));String rr=BleWifiObservation.tag("sess|DIRECT-same|192.168.49.1|"+BleWifiObservation.observedOwnerMac("aa:bb:cc:dd:ee:04"));if(l.equals(rr))throw new AssertionError();});
  pass("actual cached local message current observation",()->BleRendezvousProtocol.requireCurrentLocalMessage(go,accept));
  deny("cached accept cannot outlive changed group",()->{go.observation=new BleRoleReadiness.Observation("aaaaaaaaaaaaaaaa","dddddddddddddddd","group_owner","192.168.49.1","192.168.49.1",100);BleRendezvousProtocol.requireCurrentLocalMessage(go,accept);});go.observation=own(100);
  deny("cached accept cannot outlive stale observation",()->{go.observation=own(-6000);BleRendezvousProtocol.requireCurrentLocalMessage(go,accept);});go.observation=own(100);
  System.out.println("MEASURE normal_max_13digit_bytes="+fullWire.length+" long_max_bytes="+longestWire.length);
  System.out.println("RESULT "+cases+" PASS; proposal_bytes="+proposal.length+"; host_model_only=true");
 }
}
