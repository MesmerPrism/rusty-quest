package io.github.mesmerprism.rustyquest.media;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.function.*;import org.json.*;

/** Exact production Pipeline/Runtime methods and whole actual OwnerSet.
 * Android camera/codec/compositor/worker, LAN connection and Stop joins are modeled leaves. */
public final class PreparedCaptureReadinessHost {
 static int checks;static void check(boolean v,String m){if(!v)throw new AssertionError(m);checks++;}
 interface Effect{void run()throws Exception;}static void refuse(Effect f)throws Exception{try{f.run();}catch(IllegalStateException expected){checks++;return;}throw new AssertionError("expected readiness refusal");}
 static MediaOwnerAction action(String owner,int seq)throws Exception{return MediaOwnerAction.parse(new JSONObject().put("$schema",MediaOwnerAction.SCHEMA).put("capability","host-only").put("executor_generation",1).put("action_id","host."+seq).put("authority_epoch_id","host.epoch").put("media_acceptance_authority_revision",1).put("expected_runtime_revision",1).put("client_id","host.client").put("lease_id","host.lease").put("sequence",seq).put("operation","start").put("owner_kind",owner).put("action_kind",owner.equals("cleanup")?"arm_cleanup":"start").put("owner_id","host."+owner).put("provider_kind","host."+owner).put("resource_id","host.resource").toString());}
 static final class SystemClock{static long elapsedRealtime(){return System.nanoTime()/1_000_000L;}}
 static final class Capture{volatile boolean fresh=true;boolean fresh(){return fresh;}}
 static final class PackedStereoEncoderWorker{volatile Throwable failure;Throwable failure(){return failure;}}
 static final class PackedStereoGlCompositor{boolean fresh=true;long syntheticFrames(){return 2;}long composedFrames(){return 2;}boolean compositionFreshNow(){return fresh;}}
 static final class CameraEndpoint{boolean changing=true,fresh=true;boolean hasChangingFrames(){return changing;}boolean freshNow(){return fresh;}}
 static final class Transport{boolean connected=true;void prepareClosedSource(){}void start(){}boolean preparedConnected(){return connected;}}
 static final long SOURCE_FRESHNESS_TIMEOUT_MS=10_000L,CAMERA_SESSION_TIMEOUT_MS=10_000L;
 static final class Runtime {
  final Object lifecycleLock=new Object(),preparationMonitor=new Object();volatile boolean stopRequested,terminal,preparedDelivery,preparedDeliveryOpen;
  volatile JSONObject preparedFormat;volatile String state="created",error="";long packetCount,headerWriteCount,sourceStreamingSinceMs=-1,lastSourceFreshElapsedMs=-1;
  volatile Thread sourceThread,acceptThread;ServerSocket serverSocket;final String sourceHost="127.0.0.1";Profile profile=new Profile();Transport lanTransport=new Transport();Object encoder;
  PackedStereoGlCompositor compositor;Capture sharedCapture=new Capture();PackedStereoEncoderWorker encoderWorker;CameraEndpoint leftCamera,rightCamera;boolean synthetic;
  // Field is injected from production declaration by the runner after the fix.
  PRODUCTION_RUNTIME_FIELDS
  CountDownLatch publicationGate=new CountDownLatch(0);
  Runtime(){try(ServerSocket slot=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){profile.port=slot.getLocalPort();}catch(IOException e){throw new IllegalStateException(e);}}
  PRODUCTION_RUNTIME_METHODS
  void startCodec(){encoder=new Object();state="codec_started";}
  void startProcessor(){encoderWorker=new PackedStereoEncoderWorker();state="processor_started";}
  void acceptLoop(){try{while(!stopRequested)Thread.sleep(1);}catch(InterruptedException expected){Thread.currentThread().interrupt();}}
  void sourceLoop(){try{publicationGate.await();
   PRODUCTION_STREAMING_PUBLICATION
   synchronized(preparationMonitor){preparedFormat=new JSONObject().put("mime","video/avc");preparationMonitor.notifyAll();}
   while(!stopRequested)Thread.sleep(1);
  }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{
   PRODUCTION_STREAMING_END
  }}
  boolean failed(){return "failed".equals(state);}
  void stop(String reason)throws Exception{stop();}
  void stop()throws Exception{stopRequested=true;synchronized(preparationMonitor){preparationMonitor.notifyAll();}publicationGate.countDown();if(serverSocket!=null)serverSocket.close();if(sourceThread!=null)sourceThread.join(3000);if(acceptThread!=null)acceptThread.join(3000);check(sourceThread==null||!sourceThread.isAlive(),"source joined");check(acceptThread==null||!acceptThread.isAlive(),"listener joined");terminal=true;}
 }
 static final class Profile{int port;}
 static final class Pipeline implements PackedStereoPipeline,AutoCloseable {
  Runtime runtime=new Runtime(),preparedRuntime;
  PRODUCTION_PIPELINE_METHODS
  public void stopAndVerify(String reason){try{runtime.stop();}catch(Exception e){throw new IllegalStateException(e);}}
  public void requireStopped(){check(runtime.terminal,"actual modeled Stop terminal");}
  public void finishCleanup(){}public boolean failed(){return runtime.failed();}public boolean terminal(){return runtime.terminal;}public String observedState(){return runtime.state;}public String handleId(){return "modeled-capture-pipeline";}
  public void recreateAfterVerifiedCleanup(){requireStopped();runtime=new Runtime();preparedRuntime=null;}
  public void close(){stopAndVerify("host_end");}
 }
 static void startOwners(Pipeline p,PackedStereoMediaOwnerSet owners)throws Exception{CancellationHandle c=new CancellationHandle(1);int seq=0;for(String kind:new String[]{"cleanup","route","socket","codec","processor","source"})owners.provider(kind).execute(action(kind,seq++),c);check(p.preparedDeliveryOpened(),"only real source owner opens retained gate");}
 static void fullPathAndTwoLifetimes()throws Exception{
  try(Pipeline p=new Pipeline()){PackedStereoMediaOwnerSet owners=new PackedStereoMediaOwnerSet(1,p);
   for(int life=0;life<2;life++){
    p.prepareSender(()->false);check(p.preparedDeliveryClosed(),"prepareSender closes delivery");p.prepareTransport();check(p.preparedTransportConnected(),"actual listener/transport held");
    startOwners(p,owners);p.runtime.sharedCapture.fresh=false;check(!p.runtime.sourceFresh(),"stale actual capture revokes freshness");p.runtime.sharedCapture.fresh=true;
    // Whole owner reverse callbacks preserve terminal checks and rollover.
    CancellationHandle c=new CancellationHandle(1);for(String kind:new String[]{"source","processor","codec","socket","route","cleanup"})owners.provider(kind).compensate(action(kind,20+life),c);
    check(!p.runtime.sourceFresh(),"Stop cannot retain fresh authority");if(life==0)owners.prepareNextSdkIncarnation();
   }
  }System.out.println("PASS whole source prepare/finalize/route/socket/codec/processor/Start/Stop/two-lifetime path");
 }
 static void delayedPublicationAndStop()throws Exception{
  try(Pipeline p=new Pipeline()){p.runtime.publicationGate=new CountDownLatch(1);AtomicReference<Throwable> e=new AtomicReference<>();Thread t=new Thread(()->{try{p.prepareSender(()->false);}catch(Throwable x){e.set(x);}});t.start();while(p.runtime.sourceThread==null)Thread.yield();check(!p.runtime.sourceFresh(),"alive thread before real publication not readiness");p.runtime.publicationGate.countDown();t.join(3000);check(!t.isAlive()&&e.get()==null,"delayed real publication completes");p.prepareTransport();startOwners(p,new PackedStereoMediaOwnerSet(1,p));}
  try(Pipeline p=new Pipeline()){p.runtime.publicationGate=new CountDownLatch(1);AtomicReference<Throwable> e=new AtomicReference<>();Thread t=new Thread(()->{try{p.prepareSender(()->false);}catch(Throwable x){e.set(x);}});t.start();while(p.runtime.sourceThread==null)Thread.yield();p.runtime.stop();t.join(3000);check(!t.isAlive()&&e.get()!=null&&!p.runtime.sourceFresh(),"Stop before publication refuses prepared readiness");}
  System.out.println("PASS delayed publication and Stop interleavings");
 }
 static void negativeFreshness()throws Exception{
  try(Pipeline p=new Pipeline()){p.prepareSender(()->false);p.prepareTransport();Runtime x=p.runtime;x.sharedCapture.fresh=false;refuse(p::startSource);x.sharedCapture.fresh=true;x.encoderWorker.failure=new IllegalStateException("modeled encoder failure");refuse(p::startSource);x.encoderWorker.failure=null;x.lanTransport.connected=false;refuse(p::startSource);}
  System.out.println("PASS actual capture/worker/transport loss refusals");
 }
 static void cameraAndDeadlinePredicates()throws Exception{
  try(Pipeline p=new Pipeline()){p.prepareSender(()->false);p.prepareTransport();p.validateRoute();Runtime x=p.runtime;
   x.sharedCapture=null;x.compositor=new PackedStereoGlCompositor();x.leftCamera=new CameraEndpoint();x.rightCamera=new CameraEndpoint();
   check(x.sourceFresh(),"actual independent camera/compositor predicates survive route milestone");x.leftCamera.changing=false;check(!x.sourceFresh(),"unchanging camera refused");x.leftCamera.changing=true;x.rightCamera.fresh=false;check(!x.sourceFresh(),"stale camera timestamp refused");x.rightCamera.fresh=true;x.compositor.fresh=false;check(!x.sourceFresh(),"stale compositor timestamp refused");
   x.sourceStreamingSinceMs=SystemClock.elapsedRealtime()-SOURCE_FRESHNESS_TIMEOUT_MS-1;check(x.freshnessExpired(),"freshness deadline still enforced after route milestone");x.compositor.fresh=true;check(!x.freshnessExpired()&&x.lastSourceFreshElapsedMs>=0,"fresh sample updates real-clock observation");
   x.state="failed";check(!x.sourceFresh(),"explicit failed source refuses");x.state="route_validated";x.terminal=true;check(!x.sourceFresh(),"terminal source refuses");x.terminal=false;
  }System.out.println("PASS independent camera/compositor timestamps, failed/terminal and unchanged freshness deadline");
 }
 public static void main(String[]a)throws Exception{fullPathAndTwoLifetimes();delayedPublicationAndStop();negativeFreshness();cameraAndDeadlinePredicates();System.out.println(checks+" actual production path controls PASS; Android leaves modeled");}
}
