import io.github.mesmerprism.rustyquest.media.*;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;import org.json.JSONObject;
public final class OwnedRegistryCleanupHost implements MediaOwnerProvider{
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}interface Attempt{void run()throws Exception;}static void denied(Attempt a)throws Exception{try{a.run();throw new AssertionError("admitted");}catch(IllegalStateException expected){checks++;}}
 final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger starts=new AtomicInteger(),stops=new AtomicInteger();volatile boolean active;long revision;
 public MediaProviderReadback execute(MediaOwnerAction a,CancellationHandle c)throws Exception{c.requireCurrent(4);if("start".equals(a.actionKind())){starts.incrementAndGet();entered.countDown();release.await();active=true;return receipt(a,"started");}stops.incrementAndGet();active=false;return receipt(a,"stopped");}
 public MediaProviderReadback compensate(MediaOwnerAction a,CancellationHandle c)throws Exception{if(!"stop".equals(a.actionKind()))throw new AssertionError("boolean granted forward cleanup");return execute(a,c);}
 private MediaProviderReadback receipt(MediaOwnerAction a,String state){return new MediaProviderReadback(a,"real.handle",++revision,state,"receipt."+revision);}
 public MediaRuntimeSnapshot snapshot(){return new MediaRuntimeSnapshot(4,revision,active?"started":"stopped",!active,"physical fixture","real.handle");}
 static class EmbeddedDuplexOriginals {static Record current;static Record capture(){return current;}static class Record{JSONObject row=new JSONObject().put("operation","whole_app_close").put("native_generation",4);}}
 static class EmbeddedDuplexProcessHost {static void requireCurrentOriginalStopActor(EmbeddedDuplexOriginals.Record r){if(r.row.getLong("native_generation")!=4)throw new IllegalStateException("actor differs");}}
 static class PlatformHarness {PackagedAndroidMediaOwnerRegistry registry;Object endpoint;boolean owningWholeCleanup;void requireProcessCallback(){}
 /* ACTUAL_PLATFORM_METHODS */
 }
 static void actualPlatformLateInstall()throws Exception{
  OwnedRegistryCleanupHost provider=new OwnedRegistryCleanupHost();PlatformHarness platform=new PlatformHarness();EmbeddedDuplexOriginals.current=new EmbeddedDuplexOriginals.Record();
  EmbeddedDuplexOriginals.current.row.put("operation","start");denied(platform::fenceOwnedForwardEffects);
  EmbeddedDuplexOriginals.current.row.put("operation","whole_app_close").put("native_generation",5);denied(platform::fenceOwnedForwardEffects);
  EmbeddedDuplexOriginals.current.row.put("native_generation",4);platform.fenceOwnedForwardEffects();
  platform.installRegistry(4,new MediaProductBinding.Builder("late-install").bind("source","owner.source","provider.source","resource.source",provider).build());
  denied(()->platform.registry.execute(ticket("late.install","start","start"),false));platform.awaitOwnedForwardEffectsJoined();
  check(provider.starts.get()==0,"late registry inherits actual Whole fence");platform.registry.close();EmbeddedDuplexOriginals.current=null;
 }
 static String ticket(String id,String operation,String kind)throws Exception{return new JSONObject().put("$schema",MediaOwnerAction.SCHEMA).put("capability",id).put("executor_generation",4).put("action_id",id).put("authority_epoch_id","epoch.source").put("media_acceptance_authority_revision",2).put("expected_runtime_revision",1).put("client_id","holder.original").put("lease_id","lease.original").put("sequence",1).put("operation",operation).put("owner_kind","source").put("action_kind",kind).put("owner_id","owner.source").put("provider_kind","provider.source").put("resource_id","resource.source").toString();}
 public static void main(String[]args)throws Exception{actualPlatformLateInstall();OwnedRegistryCleanupHost provider=new OwnedRegistryCleanupHost();PackagedAndroidMediaOwnerRegistry registry=new PackagedAndroidMediaOwnerRegistry(4,new MediaProductBinding.Builder("owned-product").bind("source","owner.source","provider.source","resource.source",provider).build());ExecutorService workers=Executors.newCachedThreadPool();
 try{Future<?> forward=workers.submit(()->registry.execute(wrap("forward","start","start"),false));check(provider.entered.await(2,TimeUnit.SECONDS),"actual forward callback entered");registry.beginOwnedCleanup();
 Future<?> joined=workers.submit(()->{try{registry.awaitOwnedForwardEffectsJoined();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}});
 String stop=ticket("owned.stop","stop","stop");Future<String> cleanup=workers.submit(()->registry.execute(stop,false));Thread.sleep(80);check(!joined.isDone()&&!cleanup.isDone()&&provider.stops.get()==0,"physical cleanup joined actual creator first");
 denied(()->registry.execute(ticket("late.start","start","start"),false));denied(()->registry.execute(ticket("caller.boolean","start","start"),true));provider.release.countDown();
 try{forward.get(2,TimeUnit.SECONDS);throw new AssertionError("cancelled forward success invented");}catch(ExecutionException expected){checks++;}joined.get(2,TimeUnit.SECONDS);String raw=cleanup.get(2,TimeUnit.SECONDS);
 check(provider.starts.get()==1&&provider.stops.get()==1&&!provider.active,"late SDK Start cannot outlive local Stop");check(raw.equals(registry.execute(stop,false))&&provider.stops.get()==1,"exact unconsumed cached result remains available");check(registry.verifyAndReadEvidence(stop,raw)!=null,"actual terminal readback verified");check(registry.verifyAndReadEvidence(stop,raw)==null,"readback consumed once");denied(registry::closeIfNeverAttempted);
 String abort=ticket("start.abort","start","stop");check(registry.execute(abort,true)!=null,"typed abort cleanup remains allowed");denied(()->registry.execute(new JSONObject(ticket("wrong.counter","stop","stop")).put("executor_generation",5).toString(),false));registry.close();denied(()->registry.execute(ticket("closed.stop","stop","stop"),false));System.out.println("actual registry cleanup controls: "+checks+" PASS");}finally{provider.release.countDown();workers.shutdownNow();workers.awaitTermination(2,TimeUnit.SECONDS);}}
 static String wrap(String id,String op,String kind){try{return ticket(id,op,kind);}catch(Exception e){throw new IllegalStateException(e);}}
}
