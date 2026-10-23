import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Whole production physical producer and incoming phase owner, with modeled
 * Android/SDK/endpoint leaves. No socket, JNI, physical resource or authority. */
public class IncomingPhysicalBindingHost {
 static int groups;
 static void need(boolean value){if(!value)throw new AssertionError("incoming physical host control");}
 interface Throwing {void run()throws Exception;}
 static void refused(Throwing action)throws Exception{try{action.run();throw new AssertionError("must refuse");}catch(IllegalStateException|IllegalArgumentException expected){}}
 static EmbeddedDuplexPlatform fresh(){
  EmbeddedDuplexOriginals.CURRENT.remove();EmbeddedDuplexFreshPhaseArchive.archived=false;
  EmbeddedDuplexProcessHost.instance=new EmbeddedDuplexProcessHost();
  EmbeddedDuplexPlatform p=new EmbeddedDuplexPlatform();
  EmbeddedDuplexProcessHost.instance.platform=p;
  OwnerDispatchTcpEndpoint.CURRENT.set(new OwnerDispatchTcpEndpoint.RequestSpan(p.endpoint));
  return p;
 }
 static void binding(JSONObject value,String id)throws Exception{
  JSONObject b=value.getJSONObject("original_binding");
  need(b.length()==2 && b.getString("kind").equals("incoming_fresh_setup") && b.getString("native_request_id").equals(id));
  need(value.getString("$schema").endsWith("eligibility.v2") && value.getBoolean("eligible"));
 }
 static void suite()throws Exception{
  EmbeddedDuplexPlatform p=fresh();
  need(new JSONObject(p.freshSetupPhysicalEligibilityJson()).isNull("original_binding"));
  JSONObject initial=new JSONObject(p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first"));binding(initial,"ceremony.first");
  need(p.incomingPhases.currentNativeId()==null);refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first"));
  p.incomingPhases.bind(p.endpoint,"ceremony.first",null,"ceremony.first","initial");
  JSONObject registered=new JSONObject(p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first"));binding(registered,"ceremony.first");
  need(initial.toString().equals(registered.toString()));groups++;
  refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.foreign","ceremony.first"));
  refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","phase.foreign"));
  refused(()->p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","phase.foreign"));groups++;
  OwnerDispatchTcpEndpoint.RequestSpan first=OwnerDispatchTcpEndpoint.CURRENT.get();
  OwnerDispatchTcpEndpoint.CURRENT.set(new OwnerDispatchTcpEndpoint.RequestSpan(new OwnerDispatchTcpEndpoint()));
  refused(()->p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first"));
  refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first"));
  OwnerDispatchTcpEndpoint.CURRENT.remove();refused(()->p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first"));
  OwnerDispatchTcpEndpoint.CURRENT.set(first);groups++;
  EmbeddedDuplexOriginals.CURRENT.set(new EmbeddedDuplexOriginals.Record());
  need(new JSONObject(p.freshSetupPhysicalEligibilityJson()).getJSONObject("original_binding").getString("kind").equals("operator"));
  refused(()->p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first"));
  refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first"));
  refused(()->p.freshSetupPreparationPhysicalEligibilityJson("ceremony.first",null,"phase.next"));
  EmbeddedDuplexOriginals.CURRENT.remove();groups++;
  p.incomingFreshDesiredStop=true;refused(()->p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first"));
  refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first"));p.incomingFreshDesiredStop=false;
  EmbeddedDuplexProcessHost.instance.phase.set(EmbeddedDuplexProcessHost.Phase.CLOSING);refused(()->p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first"));
  EmbeddedDuplexProcessHost.instance.phase.set(EmbeddedDuplexProcessHost.Phase.READY);groups++;
  EmbeddedDuplexProcessHost.instance.platform=new EmbeddedDuplexPlatform();
  refused(()->p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first"));
  EmbeddedDuplexProcessHost.instance.platform=p;
  EmbeddedDuplexProcessHost.instance.processFence.live=false;
  refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first"));
  EmbeddedDuplexProcessHost.instance.processFence.live=true;
  EmbeddedDuplexFreshPhaseArchive.archived=true;refused(()->p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first"));
  refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first"));
  EmbeddedDuplexFreshPhaseArchive.archived=false;groups++;
  // New authenticated successor observation does not join its task to the old group.
  OwnerDispatchTcpEndpoint.RequestSpan next=new OwnerDispatchTcpEndpoint.RequestSpan(p.endpoint);OwnerDispatchTcpEndpoint.CURRENT.set(next);
  binding(new JSONObject(p.freshSetupInitialPhysicalEligibilityJson("ceremony.second","ceremony.second")),"ceremony.second");
  need(!p.incomingPhases.contains(next) && p.incomingPhases.currentNativeId().equals("ceremony.first"));
  refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.second","ceremony.second"));groups++;
  // Metadata admission requires the old proof; model only its terminal/archive leaves.
  refused(()->p.incomingPhases.bind(p.endpoint,"ceremony.second",null,"ceremony.second","initial"));
  first.done=true;java.lang.reflect.Field all=EmbeddedDuplexIncomingFreshPhases.class.getDeclaredField("groups");all.setAccessible(true);
  Object g=((Map<?,?>)all.get(p.incomingPhases)).get("ceremony.first");
  java.lang.reflect.Field archive=g.getClass().getDeclaredField("archive"),status=g.getClass().getDeclaredField("archiveStatus");archive.setAccessible(true);status.setAccessible(true);
  archive.set(g,new JSONObject());status.set(g,"completed");
  p.incomingPhases.bind(p.endpoint,"ceremony.second",null,"ceremony.second","initial");
  binding(new JSONObject(p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.second","ceremony.second")),"ceremony.second");
  OwnerDispatchTcpEndpoint.CURRENT.set(first);refused(()->p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first"));groups++;
  EmbeddedDuplexPlatform empty=fresh();
  binding(new JSONObject(empty.freshSetupPreparationPhysicalEligibilityJson("ceremony.new",null,"phase.new")),"ceremony.new");
  need(empty.incomingPhases.currentNativeId()==null);
  refused(()->empty.freshSetupPreparationPhysicalEligibilityJson("ceremony.new",null,"ceremony.new"));
  refused(()->empty.freshSetupPreparationPhysicalEligibilityJson("ceremony.new","phase.foreign","phase.new"));
  empty.incomingPhases.bind(empty.endpoint,"ceremony.new",null,"phase.new","initial_preparation");
  binding(new JSONObject(empty.freshSetupPhysicalEligibilityForIncomingJson("ceremony.new","phase.new")),"ceremony.new");groups++;
  EmbeddedDuplexPlatform linked=fresh();OwnerDispatchTcpEndpoint.RequestSpan old=OwnerDispatchTcpEndpoint.CURRENT.get();
  linked.incomingPhases.bind(linked.endpoint,"ceremony.linked",null,"ceremony.linked","initial");
  OwnerDispatchTcpEndpoint.CURRENT.set(new OwnerDispatchTcpEndpoint.RequestSpan(linked.endpoint));
  refused(()->linked.freshSetupPreparationPhysicalEligibilityJson("ceremony.linked","ceremony.linked","phase.next"));old.done=true;
  binding(new JSONObject(linked.freshSetupPreparationPhysicalEligibilityJson("ceremony.linked","ceremony.linked","phase.next")),"ceremony.linked");
  need(!linked.incomingPhases.contains(OwnerDispatchTcpEndpoint.CURRENT.get()));
  linked.incomingPhases.bind(linked.endpoint,"ceremony.linked","ceremony.linked","phase.next","linked_fence");
  binding(new JSONObject(linked.freshSetupPhysicalEligibilityForIncomingJson("ceremony.linked","phase.next")),"ceremony.linked");
  refused(()->linked.freshSetupPhysicalEligibilityForIncomingJson("ceremony.linked","ceremony.linked"));groups++;
  need(groups==10);System.out.println("10 production incoming binding groups passed; Android/SDK/network leaves modeled");
 }
 public static void main(String[] args)throws Exception{
  if(args.length==0){suite();return;}
  EmbeddedDuplexPlatform p=fresh();String value;
  if(args[0].equals("operator")){EmbeddedDuplexOriginals.CURRENT.set(new EmbeddedDuplexOriginals.Record());value=p.freshSetupPhysicalEligibilityJson();}
  else if(args[0].equals("null"))value=p.freshSetupPhysicalEligibilityJson();
  else if(args[0].equals("initial"))value=p.freshSetupInitialPhysicalEligibilityJson("ceremony.first","ceremony.first");
  else {p.incomingPhases.bind(p.endpoint,"ceremony.first",null,"ceremony.first","initial");value=p.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first");}
  System.out.println(value);
 }
}
class OwnerDispatchTcpEndpoint {
 static final ThreadLocal<RequestSpan> CURRENT=new ThreadLocal<>();
 static class RequestSpan {final OwnerDispatchTcpEndpoint owner;boolean done;RequestSpan(OwnerDispatchTcpEndpoint p){owner=p;}boolean terminal(){return done;}}
 static RequestSpan currentRequestSpan(){return CURRENT.get();}boolean ownsRequestSpan(RequestSpan span){return span!=null && span.owner==this && !span.done;}
}
class EmbeddedDuplexPlatform {
 final OwnerDispatchTcpEndpoint endpoint=new OwnerDispatchTcpEndpoint();
 final EmbeddedDuplexIncomingFreshPhases incomingPhases=new EmbeddedDuplexIncomingFreshPhases();boolean incomingFreshDesiredStop;
 EmbeddedDuplexPreparedStart preparedStart=new EmbeddedDuplexPreparedStart();
 EmbeddedDuplexProcessFence.CallbackLease enterProcessCallback(){return new EmbeddedDuplexProcessFence.CallbackLease();}void requireProcessCallback(){}
 boolean freshPeerEffectsNeverEntered(){return true;}boolean freshPeerControlQuiescent(){return true;}boolean actualControlTerminal(){return true;}boolean incomingFreshTaskJoined(String id){return true;}
 PRODUCTION_CALLBACKS
}
class EmbeddedDuplexProcessHost {
 enum Phase{READY,CLOSING}static EmbeddedDuplexProcessHost instance=new EmbeddedDuplexProcessHost();
 final AtomicReference<Phase> phase=new AtomicReference<>(Phase.READY);final Object applicationContext=new Object();Resources resources=new Resources();
 EmbeddedDuplexPlatform platform;EmbeddedDuplexProcessFence processFence=new EmbeddedDuplexProcessFence();
 boolean closeInFlight,localFixture;long nativeExecutorGeneration=9;String runtimeConfigSha256="b".repeat(64),nativeAppRecordSha256="a".repeat(64);
 static Object application(){return instance.applicationContext;}
 PRODUCTION_ACTOR
 PRODUCTION_HOST
}
class EmbeddedDuplexOriginals {
 static final ThreadLocal<Record> CURRENT=new ThreadLocal<>();static class Record{String id(){return "0".repeat(32);}}
 static Record capture(){return CURRENT.get();}static EmbeddedDuplexOriginals forApplication(Object c){return new EmbeddedDuplexOriginals();}
 int unresolvedForRetirement(JSONObject b){return 0;}int unresolvedExceptCurrent(){return 0;}void requireTerminalUnknown(String id){}
}
class EmbeddedDuplexProcessFence {boolean live=true;long generation(){return 7;}void requireLive(long g){if(!live||g!=7)throw new IllegalStateException("modeled fence retired");}static class CallbackLease implements AutoCloseable{public void close(){}}}
class EmbeddedDuplexPreparedStart {
 JSONObject request,sender,decoder,abortReceipt;String requestSha="sha256:"+"c".repeat(64);boolean abortEntered,decoderEntered=true;Resources resources=new Resources();
 EmbeddedDuplexPreparedStart(){try{request=new JSONObject().put("resource_target_sha256","sha256:"+"d".repeat(64));sender=new JSONObject();decoder=new JSONObject();}catch(Exception e){throw new AssertionError(e);}}
 boolean ready(){return true;}String id(){return "preparation.current";}
 PRODUCTION_STATUS
}
class Resources{boolean productResourcesTerminal(){return true;}boolean preparedDeliveryOpened(){return false;}boolean preparedSenderClosed(){return true;}boolean preparedDecoderClosed(){return true;}}
class OwnStereoCaptureRuntime{static OwnStereoCaptureRuntime currentForApplication(){return new OwnStereoCaptureRuntime();}JSONObject physicalRetirementObservation()throws Exception{return new JSONObject().put("state","live_independent");}}
class EmbeddedDuplexRendererRetirement{static boolean currentTerminal(){return true;}static boolean currentFreshEligible(String s){return true;}}
class EmbeddedDuplexFreshPhaseArchive{static boolean archived;EmbeddedDuplexFreshPhaseArchive(Object context){}boolean archivedIncoming(String n,String phase,JSONObject actor){return archived;}}
PRODUCTION_CANONICAL
class EmbeddedDuplexFreshHandoffStore{static void keys(JSONObject o,String... keys){}}
PRODUCTION_PHASES
