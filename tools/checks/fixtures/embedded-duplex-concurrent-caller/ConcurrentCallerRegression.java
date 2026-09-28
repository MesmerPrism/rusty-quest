package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;import io.github.mesmerprism.rustyquest.media.*;import io.github.mesmerprism.rustyquest.spatial_camera_panel.*;import java.lang.reflect.*;
public final class ConcurrentCallerRegression {
 static String ticket(String action,long generation,String owner,String capability)throws Exception {
  return new JSONObject().put("$schema",MediaOwnerAction.SCHEMA)
  .put("capability",capability).put("executor_generation",generation).put("action_id","fixture.action")
  .put("authority_epoch_id","fixture.epoch").put("media_acceptance_authority_revision",1).put("expected_runtime_revision",1)
  .put("client_id","fixture.client").put("lease_id","fixture.lease").put("sequence",1).put("operation","start")
  .put("owner_kind","sink").put("action_kind",action).put("owner_id",owner).put("provider_kind","fixture.sink").put("resource_id","fixture.resource").toString();
 }
 static MediaProductBinding binding(MediaOwnerProvider provider){return new MediaProductBinding.Builder("fixture.product").bind("sink","fixture.owner","fixture.sink","fixture.resource",provider).build();}
 static boolean fresh=true,dispatchAllowed=true;static int starts,stops;static long route;static String failureMode="";
 static Object allocate(Class<?> c)throws Exception{Class<?> u=Class.forName("sun.misc.Unsafe");Field f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return u.getMethod("allocateInstance",Class.class).invoke(f.get(null),c);}
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static void own()throws Exception{
  OwnStereoCaptureRuntime own=(OwnStereoCaptureRuntime)allocate(OwnStereoCaptureRuntime.class);set(own,"lock",new Object());set(own,"phase",OwnStereoCaptureRuntime.Phase.Live);
  PackedStereoCaptureOwner capture=(PackedStereoCaptureOwner)allocate(PackedStereoCaptureOwner.class);
  Class<?> cc=Class.forName("io.github.mesmerprism.rustyquest.media.PackedStereoGlCompositor");Object compositor=allocate(cc);
  Class<?> dc=Class.forName("io.github.mesmerprism.rustyquest.media.MonotonicFreshnessDeadline");Constructor<?> c=dc.getDeclaredConstructor(long.class);c.setAccessible(true);Object deadline=c.newInstance(1000L);Method progress=dc.getDeclaredMethod("progress",long.class);progress.setAccessible(true);progress.invoke(deadline,1000L);set(compositor,"compositionFreshness",deadline);set(capture,"compositor",compositor);
  set(capture,"poolExecutor",Proxy.newProxyInstance(ConcurrentCallerRegression.class.getClassLoader(),new Class<?>[]{PackedStereoPoolExecutor.class},(p,m,a)->{if(m.getName().equals("ownImageFresh"))return fresh;throw new AssertionError("no physical capture");}));
  set(own,"capture",capture);Field f=OwnStereoCaptureRuntime.class.getDeclaredField("process");f.setAccessible(true);f.set(null,own);
 }
 static void clear()throws Exception{Field f=OwnStereoCaptureRuntime.class.getDeclaredField("process");f.setAccessible(true);f.set(null,null);}
 static SpatialVideoSourceNativeReadback receipt(SpatialVideoSourceNativeRequest q){route=q.getRouteGeneration();return new SpatialVideoSourceNativeReadback(route,q.getSource(),0,0,q.getLaunchChallenge(),q.getSurfaceGeneration(),0,0,0,1,SpatialVideoSourceResult.Pending,SpatialVideoSourceReason.None,false,0,0);}
 static EmbeddedDuplexReceiver receiver(EmbeddedDuplexDisplay display){
  final MediaOwnerProvider provider=new MediaOwnerProvider(){public MediaProviderReadback execute(MediaOwnerAction a,CancellationHandle c){starts++;return new MediaProviderReadback(a,"fixture.sink",1,"receiver_armed","fixture.sink.receipt");}public MediaProviderReadback compensate(MediaOwnerAction a,CancellationHandle c){throw new AssertionError();}public MediaRuntimeSnapshot snapshot(){return new MediaRuntimeSnapshot(1,1,"receiver_armed",false,"fixture","fixture.sink");}};
  EmbeddedDuplexReceiver.RuntimeFactory factory=new EmbeddedDuplexReceiver.RuntimeFactory(){public EmbeddedDuplexReceiver.ProjectionResource stage(int w,int h,int n,int f,long g){return new EmbeddedDuplexReceiver.ProjectionResource(){public long routeGeneration(){return route;}public long decoderToken(){return 3;}public long readerGeneration(){return 4;}public boolean release(){throw new AssertionError();}};}public EmbeddedDuplexReceiver.ReceiverRuntime create(EmbeddedDuplexReceiver.ProjectionResource p,String h,int port,long g,PackedStereoMediaReceiver.Bounds b,PackedStereoMediaReceiver.FrameLifecycleListener l){return new EmbeddedDuplexReceiver.ReceiverRuntime(){public MediaOwnerProvider provider(){return provider;}public void start(){throw new AssertionError();}public MediaRuntimeSnapshot snapshot(){return provider.snapshot();}};}};
  return new EmbeddedDuplexReceiver(1,display,"fixture.invalid",1,2,2,1,new PackedStereoMediaReceiver.Bounds(1024,1024,2,2,1,1,1,1,1,1,0,1),factory);
 }
 static void run(String mode,String expected)throws Exception{
  fresh=true;dispatchAllowed=true;starts=0;stops=0;OwnPackedPoolNative.admitted=true;OwnPackedPoolNative.atProof=null;EmbeddedDuplexNative.quiescent=true;own();
  SpatialVideoSourceExecutionAdapter adapter=(SpatialVideoSourceExecutionAdapter)Proxy.newProxyInstance(ConcurrentCallerRegression.class.getClassLoader(),new Class<?>[]{SpatialVideoSourceExecutionAdapter.class},(p,m,a)->{
   if(m.getName().equals("updateSourceOwnerDemand"))return null;if(m.getName().equals("selectNativeProvider"))return receipt((SpatialVideoSourceNativeRequest)a[0]);
   if(m.getName().equals("stopSourceAcquisition")){stops++;throw new AssertionError("must not stop Own or old-local actor");}throw new AssertionError("unexpected effect "+m.getName());});
  SpatialVideoSourceRoutingCoordinator routing=new SpatialVideoSourceRoutingCoordinator(adapter,()->1L);
  routing.beginProjectionSourceRequest(SpatialVideoSource.Local,null);
  if(mode.equals("false-own")){OwnStereoCaptureRuntime o=OwnStereoCaptureRuntime.currentForApplication();set(o,"phase",OwnStereoCaptureRuntime.Phase.StopPending);}
  if(mode.equals("not-fresh"))fresh=false;if(mode.equals("native-quiescence"))EmbeddedDuplexNative.quiescent=false;if(mode.equals("native-actor"))OwnPackedPoolNative.admitted=false;
  if(mode.equals("stale-generation"))OwnPackedPoolNative.atProof=()->routing.beginProjectionSourceRequest(SpatialVideoSource.Local,null);
  if(mode.equals("dispatch-fence"))dispatchAllowed=false;
  EmbeddedDuplexDisplayCoordinator display=new EmbeddedDuplexDisplayCoordinator(routing,a->{if(!dispatchAllowed)return false;a.invoke();return true;},()->false,()->new SpatialVideoSourceCarrierContext(10,20),a->true,a->true,a->null,a->new long[0],a->false,()->false,()->kotlin.Unit.INSTANCE);
  EmbeddedDuplexReceiver receiver=receiver(display);PackagedAndroidMediaOwnerRegistry registry=new PackagedAndroidMediaOwnerRegistry(1,binding(receiver));String t=ticket("arm_receiver",1,"fixture.owner","fixture.arm");
  try {String rb=registry.execute(t,false);if(expected!=null)throw new AssertionError("must reject "+mode);if(registry.verifyAndReadEvidence(t,rb)==null||starts!=1||stops!=0||!"NONE".equals(receiver.failedArmStage()))throw new AssertionError("happy actual caller");}
  catch(Exception e){if(expected==null){System.err.println("happy caller rejected: sink_stage="+receiver.failedArmStage()+" preparation="+receiver.snapshot().state());throw e;}if(!expected.equals(EmbeddedDuplexPlatform.providerReason(e).name())||!"PEER_PROJECTION".equals(receiver.failedArmStage())||!"preparation_failed".equals(receiver.snapshot().state())||starts!=0)throw new AssertionError(mode,e);}
  finally{clear();}
 }
 public static void main(String[] args)throws Exception {
  run("happy",null);run("false-own","DISPLAY_ADMISSION_REJECTED");run("not-fresh","DISPLAY_ADMISSION_REJECTED");run("native-actor","DISPLAY_ADMISSION_REJECTED");run("stale-generation","DISPLAY_ADMISSION_REJECTED");run("dispatch-fence","DISPLAY_DISPATCH_FENCED");run("native-quiescence","DISPLAY_LOCAL_SHUTDOWN");
  SpatialVideoSourceExecutionAdapter adapter=(SpatialVideoSourceExecutionAdapter)Proxy.newProxyInstance(ConcurrentCallerRegression.class.getClassLoader(),new Class<?>[]{SpatialVideoSourceExecutionAdapter.class},(p,m,a)->{throw new AssertionError();});SpatialVideoSourceRoutingCoordinator routing=new SpatialVideoSourceRoutingCoordinator(adapter,()->1L);
  try{routing.beginEmbeddedProjectionPeerRequest();throw new AssertionError("exclusive guard must remain");}catch(IllegalStateException expected){if(!expected.getMessage().equals("embedded receiver requires actual local acquisition shutdown"))throw expected;}
  if(!"DISPLAY_TRANSITION_TIMEOUT".equals(EmbeddedDuplexPlatform.providerReason(new java.util.concurrent.TimeoutException()).name()))throw new AssertionError("closed timeout");
  System.out.println("PASS actual candidate Kotlin Display/Router -> Receiver/Registry happy+6 rejection cases, checked failure stage, exclusive guard and closed timeout category. Actual CaptureOwner freshness/deadline with mocked physical pool/JNI; no device proof");
 }
}
