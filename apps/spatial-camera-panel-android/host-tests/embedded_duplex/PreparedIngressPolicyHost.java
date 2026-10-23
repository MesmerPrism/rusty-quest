package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
/** Extracted production ingress/archive methods; endpoint, old SDK/Original and disk leaves modeled. */
public final class PreparedIngressPolicyHost {
 static final class EmbeddedDuplexProcessFence{static final class CallbackLease implements AutoCloseable{public void close(){}}}
 static final class OwnerDispatchTcpEndpoint{
  static final class RequestSpan{boolean stopRequested(){return false;}boolean terminal(){return true;}}
  static final RequestSpan SPAN=new RequestSpan();static RequestSpan currentRequestSpan(){return SPAN;}
  boolean foreign;boolean ownsRequestSpan(RequestSpan s){return s==SPAN&&!foreign;}
 }
 static final class EmbeddedDuplexOriginals{
  static final class Record{JSONObject row=new JSONObject().put("operation","prepare_peer_start").put("state","succeeded").put("consumer_receipt_sha256","c".repeat(64));boolean operationJoined=true;java.util.concurrent.atomic.AtomicBoolean desiredStop=new java.util.concurrent.atomic.AtomicBoolean();String id(){return "1".repeat(32);}}
  static Record current=new Record();static Record capture(){return current;}
 }
 static final class EmbeddedDuplexProcessHost{static Object application(){return null;}static JSONObject freshClosureIdentity(Object ignored){return new JSONObject();}static boolean originalExchangesJoined(EmbeddedDuplexOriginals.Record r){return exchangesJoined;}static boolean exchangesJoined=true;static void requireCurrentOriginalStopActor(EmbeddedDuplexOriginals.Record r){if(r!=EmbeddedDuplexOriginals.current)throw new IllegalStateException();}}
 static final class EmbeddedDuplexNative{static void requestPreparedPeerStartStop(String id){}}
 static final class EmbeddedDuplexOperationDiagnostics{static void observe(String phase,String boundary,String operation,Throwable failure){/* Diagnostic storage is a modeled leaf. */}}
 static final class Resources{boolean terminal=true;boolean productResourcesTerminal(){return terminal;}void retireArchivedPreparedMetadata(String id){if(!terminal)throw new IllegalStateException();}}
 static final class EmbeddedDuplexPreparedStart{
  final Resources resources=new Resources();final EmbeddedDuplexOriginals.Record original=new EmbeddedDuplexOriginals.Record();
  String id(){return "b".repeat(32);}String originalId(){return original.id();}void requestStopOwned(){original.desiredStop.set(true);}
  void requireArchiveBody(JSONObject body){if(!resources.terminal)throw new IllegalStateException();}
  PRODUCTION_HOLDER_METHODS
 }
 static final class EmbeddedDuplexPreparedArchive{
  EmbeddedDuplexPreparedArchive(Object ignored){}JSONObject retain(String body,JSONObject actor,EmbeddedDuplexPreparedStart held){held.requireArchiveBody(new JSONObject(body));if(failDisk)throw new IllegalStateException("modeled durable write refusal");return new JSONObject().put("retained",true);}JSONObject retainedExact(String b,JSONObject a,JSONObject ack){if(ack==null)throw new IllegalStateException();return ack;}
  static boolean failDisk;
 }
 private volatile EmbeddedDuplexPreparedStart preparedStart=new EmbeddedDuplexPreparedStart();
 private volatile boolean incomingPreparedDesiredStop=true;
 private long incomingPreparedStopSequence=1;
 private boolean incomingPreparedWholeStop;
 private final Set<String> archivedIncomingPreparedRemotes=new HashSet<>();
 private String incomingPreparedRemote="a".repeat(32),incomingPreparedLocal="b".repeat(32);
 private JSONObject lastPreparedArchiveAck;private String lastPreparedArchiveId;
 private final Set<OwnerDispatchTcpEndpoint.RequestSpan> incomingPreparedSpans=new HashSet<>();
 private final OwnerDispatchTcpEndpoint endpoint=new OwnerDispatchTcpEndpoint();
 EmbeddedDuplexProcessFence.CallbackLease enterProcessCallback(){return new EmbeddedDuplexProcessFence.CallbackLease();}void requireProcessCallback(){}
 PRODUCTION_PLATFORM_METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[]args)throws Exception{
  PreparedIngressPolicyHost p=new PreparedIngressPolicyHost();
  if(args.length==0){
   check(!p.incomingPreparedStartStopRequestedFor("1".repeat(32),1,true));check(p.incomingPreparedSpans.isEmpty());
   check(p.incomingPreparedStartStopRequestedFor("a".repeat(32),1,true));check(p.incomingPreparedStartStopRequestedFor("1".repeat(32),1,false));
   p.preparedStart.resources.terminal=false;check(p.incomingPreparedStartStopRequestedFor("1".repeat(32),1,true));p.preparedStart.resources.terminal=true;
   p.preparedStart.original.row.put("consumer_receipt_sha256",JSONObject.NULL);check(p.incomingPreparedStartStopRequestedFor("1".repeat(32),1,true));p.preparedStart.original.row.put("consumer_receipt_sha256","c".repeat(64));
   p.endpoint.foreign=true;check(p.incomingPreparedStartStopRequestedFor("1".repeat(32),1,true));p.endpoint.foreign=false;
   EmbeddedDuplexPreparedArchive.failDisk=true;try{p.archiveCompletedPreparedPeerStart("{}");throw new AssertionError();}catch(IllegalStateException expected){}check(p.preparedStart!=null&&p.incomingPreparedDesiredStop);EmbeddedDuplexPreparedArchive.failDisk=false;
   p.archiveCompletedPreparedPeerStart("{}");check(!p.incomingPreparedDesiredStop&&p.preparedStart==null);check(p.incomingPreparedStartStopRequestedFor("a".repeat(32),1,false));
   p.requestIncomingPreparedStop(false);check(p.incomingPreparedStartStopRequestedFor("1".repeat(32),1,true));
   p=new PreparedIngressPolicyHost();p.requestIncomingPreparedStop(true);check(p.incomingPreparedStartStopRequestedFor("1".repeat(32),2,true));p.archiveCompletedPreparedPeerStart("{}");check(p.incomingPreparedDesiredStop&&p.incomingPreparedWholeStop);
   System.out.println("PASS actual ingress/archive methods: old terminal scope, no borrowed future span, foreign/old/current/Pending/unconsumed/failed disk/newStop/wholeClose refusals");return;
  }
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in));String line;
  while((line=input.readLine())!=null){String[] a=line.split(" ");try{
   switch(a[0]){
    case "G":System.out.println(p.incomingPreparedStartStopSequence());break;
    case "S":System.out.println(p.incomingPreparedStartStopRequestedFor(a[1],Long.parseLong(a[2]),Boolean.parseBoolean(a[3])));break;
    case "A":p.archiveCompletedPreparedPeerStart("{}");System.out.println("OK");break;
    case "P":p.requestIncomingPreparedStop(Boolean.parseBoolean(a[1]));System.out.println("OK");break;
    case "F":p.endpoint.foreign=true;System.out.println("OK");break;
    case "T":p.preparedStart.resources.terminal=false;System.out.println("OK");break;
    case "Q":return;
    default:throw new IllegalArgumentException();
   }
  }catch(Exception e){System.out.println("REFUSED");}System.out.flush();}
 }
}
