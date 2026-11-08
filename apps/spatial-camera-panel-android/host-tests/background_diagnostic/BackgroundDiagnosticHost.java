package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialBackgroundDiagnosticRegistry.*;

public final class BackgroundDiagnosticHost {
  static final String BOOT="12345678-1234-1234-1234-123456789abc", APK="a".repeat(64), EPOCH="b".repeat(64);
  static long now=100;
  static int tickets=0, controls=0;
  static Actor actor=new Actor("example.test",17,10042,71,BOOT,APK,EPOCH);
  static Fence fence(long revision,String mode) { return new Fence(actor,"activity-one",1,2,3,revision,mode,true,false); }
  static Map<String,Object> fields(String nonce) {
    Map<String,Object> m=new HashMap<>();m.put("nonce",nonce);m.put("expected_package","example.test");
    m.put("expected_pid","17");m.put("expected_uid","10042");m.put("expected_start_ticks","71");
    m.put("expected_boot_id",BOOT);m.put("expected_apk_sha256",APK);return m;
  }
  static String id(int n) { return String.format("%064x",n); }
  static SpatialBackgroundDiagnosticContract.Request observe(int n) {
    return SpatialBackgroundDiagnosticContract.parse("observe",null,fields(id(n)));
  }
  static SpatialBackgroundDiagnosticContract.Request select(Observation o,int n,String mode) {
    Map<String,Object> m=fields(id(n));m.put("ticket",o.ticket);m.put("observation_sha256",o.sha256);
    m.put("expected_epoch",EPOCH);m.put("mode",mode);
    return SpatialBackgroundDiagnosticContract.parse("select",null,m);
  }
  static SpatialBackgroundDiagnosticRegistry registry() {
    return new SpatialBackgroundDiagnosticRegistry(()->now,()->id(1000+(++tickets)));
  }
  static void check(boolean b,String message) { controls++;if(!b)throw new AssertionError(message); }
  static void rejects(Runnable r,String message) { boolean refused=false;try{r.run();}catch(IllegalArgumentException|SecurityException e){refused=true;}check(refused,message); }
  public static void main(String[] ignored) {
    check(!SpatialBackgroundDiagnosticContract.selected(false,1),"release must remain off");
    for(int v:new int[]{-1,0,2,10})check(!SpatialBackgroundDiagnosticContract.selected(true,v),"exact optin");
    check(SpatialBackgroundDiagnosticContract.selected(true,1),"debug explicit optin");
    rejects(()->SpatialBackgroundDiagnosticContract.requireCaller(true,0,true),"root is not shell");
    rejects(()->SpatialBackgroundDiagnosticContract.requireCaller(false,2000,true),"release caller denied");
    rejects(()->SpatialBackgroundDiagnosticContract.requireCaller(true,2000,false),"unselected caller denied");
    SpatialBackgroundDiagnosticContract.requireCaller(true,2000,true);
    Map<String,Object> bad=fields(id(1));bad.put("intent","arbitrary");final Map<String,Object> extra=bad;rejects(()->SpatialBackgroundDiagnosticContract.parse("observe",null,extra),"extra field");
    bad=fields(id(1));bad.put("expected_pid",17);final Map<String,Object> typed=bad;rejects(()->SpatialBackgroundDiagnosticContract.parse("observe",null,typed),"numeric coercion");
    rejects(()->SpatialBackgroundDiagnosticContract.parse("observe","uri",fields(id(1))),"argument denied");
    rejects(()->SpatialBackgroundDiagnosticContract.parse("start",null,fields(id(1))),"method closed");
    rejects(()->SpatialBackgroundDiagnosticContract.parse("observe",null,fields("bad")),"nonce shape");
    SpatialBackgroundDiagnosticRegistry r=registry();Fence initial=fence(0,"black");
    Observation o=r.observe(observe(2),initial);check(o.ticket!=null,"ready observe grants ticket");
    var q=select(o,3,"video");var pending=r.select(q);check(pending.phase==Phase.QUEUED,"queued");
    check(r.select(q).requestSha256.equals(pending.requestSha256),"duplicate retained once");
    AtomicInteger effects=new AtomicInteger();
    Operation done=r.execute(q.nonce,()->initial,m->{effects.incrementAndGet();return fence(1,"world-video");});
    check(done.phase==Phase.HANDLER_RETURNED&&done.effective,"effective mode only");
    r.execute(q.nonce,()->initial,m->{effects.incrementAndGet();return initial;});check(effects.get()==1,"duplicate runnable no replay");
    check(r.select(q).phase==Phase.HANDLER_RETURNED,"duplicate select returns terminal");
    final SpatialBackgroundDiagnosticRegistry first=r;
    var forged=select(o,3,"black");rejects(()->first.select(forged),"same nonce different input");
    o=r.observe(observe(4),fence(1,"world-video"));var q2=select(o,5,"black");r.select(q2);
    Operation stale=r.execute(q2.nonce,()->fence(2,"world-video"),m->{throw new AssertionError("stale effect");});
    check(stale.phase==Phase.REJECTED,"intervening UI revision rejects");
    check(first.select(q).phase==Phase.HANDLER_RETURNED,"one prior terminal retained with successor");
    var third=select(first.observe(observe(50),fence(2,"world-video")),51,"black");first.select(third);
    rejects(()->first.select(q),"evicted terminal cannot revive consumed ticket");
    r=registry();o=r.observe(observe(6),initial);var timeout=select(o,7,"video");r.select(timeout);
    check(r.timeout(timeout.nonce).phase==Phase.CANCELLED,"queued timeout cancels");
    r.execute(timeout.nonce,()->initial,m->{throw new AssertionError("cancelled effect");});
    r=registry();o=r.observe(observe(8),initial);var exp=select(o,9,"video");r.select(exp);now+=3000;
    check(r.execute(exp.nonce,()->initial,m->{throw new AssertionError("expired effect");}).phase==Phase.REJECTED,"queue expiry");
    r=registry();o=r.observe(observe(10),initial);var running=select(o,11,"video");r.select(running);
    final SpatialBackgroundDiagnosticRegistry current=r;
    Operation completed=r.execute(running.nonce,()->initial,m->{
      check(current.timeout(running.nonce).phase==Phase.EXECUTING,"executing timeout stays pending");
      // A separate thread must acquire the registry lock while handler is on the stack.
      Thread reenter=new Thread(()->current.invalidate());reenter.start();try{reenter.join(1000);}catch(InterruptedException e){throw new AssertionError(e);}
      check(!reenter.isAlive(),"handler held custody monitor");return fence(1,"world-video");
    });check(completed.phase==Phase.SUPERSEDED,"reentrant lifecycle invalidation remains visible");
    r=registry();o=r.observe(observe(12),initial);var failed=select(o,13,"video");r.select(failed);
    check(r.execute(failed.nonce,()->initial,m->{throw new IllegalStateException("private detail");}).phase==Phase.HANDLER_FAILED,"failure retained");
    check(r.select(failed).phase==Phase.HANDLER_FAILED,"failure is not retry permission");
    r=registry();o=r.observe(observe(14),initial);var paused=select(o,15,"video");r.select(paused);
    Fence notReady=new Fence(actor,"activity-one",2,2,3,0,"black",false,false);
    check(r.execute(paused.nonce,()->notReady,m->{throw new AssertionError("paused effect");}).phase==Phase.REJECTED,"pause generation");
    check(r.observe(observe(16),notReady).ticket==null,"paused observe cannot grant");
    check(r.observe(observe(17),null).ticket==null,"missing Activity cannot grant");
    r=registry();o=r.observe(observe(18),initial);var superseded=select(o,19,"video");r.select(superseded);
    check(r.execute(superseded.nonce,()->initial,m->fence(2,"world-video")).phase==Phase.SUPERSEDED,"multiple handler revisions not adopted as own");
    Map<String,Object> source=fields(id(20));var immutable=SpatialBackgroundDiagnosticContract.parse("observe",null,source);source.put("expected_pid","999");
    check(immutable.expected.pid==17,"input copied before dispatch");
    r=registry();o=r.observe(observe(21),initial);var interrupted=select(o,22,"video");r.select(interrupted);
    check(r.claimDispatch(interrupted.nonce)&&!r.claimDispatch(interrupted.nonce),"dispatch claimed once");
    Thread.currentThread().interrupt();
    try{check(r.await(interrupted.nonce,new java.util.concurrent.CompletableFuture<>(),10).phase==Phase.CANCELLED,"interrupted queued waiter cancels");}
    catch(java.util.concurrent.ExecutionException e){throw new AssertionError(e);}
    check(Thread.interrupted(),"interrupt flag preserved");
    r.execute(interrupted.nonce,()->initial,m->{throw new AssertionError("late interrupted effect");});
    r=registry();o=r.observe(observe(23),initial);var enteredWait=select(o,24,"video");r.select(enteredWait);
    final SpatialBackgroundDiagnosticRegistry enteredRegistry=r;
    r.execute(enteredWait.nonce,()->initial,m->{
      Thread.currentThread().interrupt();
      try{check(enteredRegistry.await(enteredWait.nonce,new java.util.concurrent.CompletableFuture<>(),10).phase==Phase.EXECUTING,"interrupted entered waiter stays pending");}
      catch(java.util.concurrent.ExecutionException e){throw new AssertionError(e);}
      check(Thread.interrupted(),"entered interrupt preserved");return fence(1,"world-video");
    });
    var successor=select(r.observe(observe(25),fence(1,"world-video")),26,"black");r.select(successor);
    Thread.currentThread().interrupt();
    try{check(r.await(enteredWait.nonce,new java.util.concurrent.CompletableFuture<>(),10).nonce.equals(enteredWait.nonce),"departed prior waiter remains nonce-qualified");}
    catch(java.util.concurrent.ExecutionException e){throw new AssertionError(e);}
    check(Thread.interrupted(),"prior waiter interrupt preserved");
    check(r.current().nonce.equals(successor.nonce)&&r.current().phase==Phase.QUEUED,"prior waiter does not cancel successor");
    System.out.println("Background diagnostic production contract/custody controls passed: "+controls+" assertions; no Android/GPU proof.");
  }
}
