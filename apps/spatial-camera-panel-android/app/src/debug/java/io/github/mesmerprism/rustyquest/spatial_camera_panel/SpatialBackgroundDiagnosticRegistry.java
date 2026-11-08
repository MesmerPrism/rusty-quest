package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialBackgroundDiagnosticContract.Request;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialBackgroundDiagnosticContract.Mode;

/** One active/last command; callbacks never run under custody. No durable/native authority. */
public final class SpatialBackgroundDiagnosticRegistry {
  public static final long TICKET_MS=10_000, QUEUE_MS=2_000;
  public enum Phase { QUEUED,EXECUTING,HANDLER_RETURNED,REJECTED,CANCELLED,SUPERSEDED,HANDLER_FAILED }
  public static final class Actor {
    public final String packageId,bootId,apkSha256,epoch;
    public final int pid,uid;
    public final long startTicks;
    public Actor(String pkg,int pid,int uid,long ticks,String boot,String apk,String epoch) {
      this.packageId=Objects.requireNonNull(pkg);this.pid=pid;this.uid=uid;startTicks=ticks;
      bootId=Objects.requireNonNull(boot);apkSha256=Objects.requireNonNull(apk);this.epoch=Objects.requireNonNull(epoch);
    }
    String canonical(){return packageId+"\n"+pid+"\n"+uid+"\n"+startTicks+"\n"+bootId+"\n"+apkSha256+"\n"+epoch;}
  }
  public static final class Fence {
    public final Actor actor;
    public final String activity,mode;
    public final long resumeGeneration,sceneEpoch,vrGeneration,revision;
    public final boolean ready,retired;
    public Fence(Actor actor,String activity,long resume,long scene,long vr,long revision,String mode,boolean ready,boolean retired) {
      this.actor=Objects.requireNonNull(actor);this.activity=Objects.requireNonNull(activity);resumeGeneration=resume;sceneEpoch=scene;vrGeneration=vr;
      this.revision=revision;this.mode=Objects.requireNonNull(mode);this.ready=ready;this.retired=retired;
    }
    public boolean available(){return ready&&!retired&&resumeGeneration>0&&sceneEpoch>0&&vrGeneration>0;}
    public String sha256(){return digest(actor.canonical()+"\n"+activity+"\n"+resumeGeneration+"\n"+sceneEpoch+"\n"+vrGeneration+"\n"+revision+"\n"+mode+"\n"+ready+"\n"+retired);}
    boolean sameLifetime(Fence b){return b!=null&&actor.canonical().equals(b.actor.canonical())&&activity.equals(b.activity)&&resumeGeneration==b.resumeGeneration&&sceneEpoch==b.sceneEpoch&&vrGeneration==b.vrGeneration&&b.available();}
  }
  public static final class Operation {
    public final String nonce,requestSha256,reason;
    public final Phase phase;
    public final boolean effective;
    public final Fence before,after;
    Operation(String nonce,String hash,Phase phase,String reason,boolean effective,Fence before,Fence after){this.nonce=nonce;requestSha256=hash;this.phase=phase;this.reason=reason;this.effective=effective;this.before=before;this.after=after;}
    public boolean terminal(){return phase!=Phase.QUEUED&&phase!=Phase.EXECUTING;}
  }
  public static final class Observation {
    public final String nonce,ticket,sha256;
    public final long expiresAtMs;
    public final Fence fence;
    public final Operation operation;
    Observation(String nonce,String ticket,long expires,Fence fence,Operation operation){this.nonce=nonce;this.ticket=ticket;expiresAtMs=expires;this.fence=fence;sha256=fence==null?null:fence.sha256();this.operation=operation;}
  }
  private static final class Ticket {
    final String token;final Fence fence;final long expires,invalidation;
    Ticket(String token,Fence fence,long expires,long invalidation){this.token=token;this.fence=fence;this.expires=expires;this.invalidation=invalidation;}
  }
  private final LongSupplier clock;
  private final Supplier<String> nonce;
  private Ticket ticket;
  private Request request;
  private Operation operation;
  private Operation retainedTerminal;
  private long queueDeadline,invalidation,executionInvalidation;
  private String consumedTicket;
  private boolean dispatchClaimed;
  public SpatialBackgroundDiagnosticRegistry(LongSupplier clock,Supplier<String> nonce){this.clock=clock;this.nonce=nonce;}

  public synchronized Observation observe(Request r,Fence current) {
    if(!r.method.equals("observe"))throw new IllegalArgumentException("observe_shape");
    if(current!=null&&!r.expected.matches(current.actor))throw new SecurityException("actor_mismatch");
    if(current==null||!current.available()||(operation!=null&&!operation.terminal())) {
      ticket=null;return new Observation(r.nonce,null,0,current,operation);
    }
    String token=nonce.get();
    if(token==null||!token.matches("[0-9a-f]{64}")||token.equals(consumedTicket)||(ticket!=null&&token.equals(ticket.token)))throw new IllegalStateException("ticket_collision");
    long expires=Math.addExact(clock.getAsLong(),TICKET_MS);
    ticket=new Ticket(token,current,expires,invalidation);
    return new Observation(r.nonce,token,expires,current,operation);
  }
  public synchronized Operation select(Request r) {
    if(!r.method.equals("select"))throw new IllegalArgumentException("select_shape");
    String hash=digest(r.canonical());
    Operation duplicate=operation!=null&&operation.nonce.equals(r.nonce)?operation:
        retainedTerminal!=null&&retainedTerminal.nonce.equals(r.nonce)?retainedTerminal:null;
    if(duplicate!=null) {
      if(!duplicate.requestSha256.equals(hash))throw new SecurityException("request_collision");
      return duplicate;
    }
    if(operation!=null&&!operation.terminal())throw new SecurityException("command_pending");
    Ticket t=ticket;
    if(t==null||!t.token.equals(r.ticket)||clock.getAsLong()>=t.expires||t.invalidation!=invalidation||
        !t.fence.sha256().equals(r.observationSha256)||!r.expected.matches(t.fence.actor)||!t.fence.actor.epoch.equals(r.expectedEpoch))throw new SecurityException("ticket_rejected");
    consumedTicket=t.token;ticket=null;request=r;dispatchClaimed=false;
    if(operation!=null)retainedTerminal=operation;
    queueDeadline=Math.min(t.expires,Math.addExact(clock.getAsLong(),QUEUE_MS));
    operation=new Operation(r.nonce,hash,Phase.QUEUED,"queued",false,t.fence,null);
    return operation;
  }
  public Operation execute(String requestNonce,Supplier<Fence> snapshot,Function<Mode,Fence> effect) {
    // Snapshot may reach Activity code and must be outside this monitor too.
    Fence current;
    try{current=snapshot.get();}catch(Exception unavailable){return rejectQueued(requestNonce,"snapshot_unavailable");}
    Operation entered;Mode mode;
    synchronized(this) {
      Operation held=requireOperation(requestNonce);
      if(held!=operation||held.phase!=Phase.QUEUED)return held;
      if(clock.getAsLong()>=queueDeadline||current==null||!current.available()||!operation.before.sha256().equals(current.sha256()))return finish(Phase.REJECTED,"stale_before_effect",false,current);
      entered=operation;mode=request.mode;executionInvalidation=invalidation;
      operation=new Operation(entered.nonce,entered.requestSha256,Phase.EXECUTING,"handler_entered",false,entered.before,null);
    }
    Fence after;
    try{after=effect.apply(mode);}catch(Exception failure){
      Fence failedAfter;try{failedAfter=snapshot.get();}catch(Exception unavailable){failedAfter=null;}
      synchronized(this){return finish(Phase.HANDLER_FAILED,"handler_exception",false,failedAfter);}
    }
    synchronized(this) {
      boolean accepted=executionInvalidation==invalidation&&entered.before.sameLifetime(after)&&
          entered.before.revision<Long.MAX_VALUE&&after.revision==entered.before.revision+1&&mode.effectiveToken.equals(after.mode);
      return finish(accepted?Phase.HANDLER_RETURNED:Phase.SUPERSEDED,accepted?"effective_mode_observed":"lifetime_or_revision_changed",accepted,after);
    }
  }
  public synchronized Operation timeout(String requestNonce) {
    Operation held=requireOperation(requestNonce);
    return held==operation&&held.phase==Phase.QUEUED?finish(Phase.CANCELLED,"cancelled_before_entry",false,null):held;
  }
  public synchronized boolean claimDispatch(String requestNonce) {
    Operation held=requireOperation(requestNonce);
    if(held!=operation||held.phase!=Phase.QUEUED||dispatchClaimed)return false;
    dispatchClaimed=true;return true;
  }
  /** A departed waiter cannot leave queued effects behind. Entered effects remain Pending. */
  public Operation await(String requestNonce,Future<Operation> completion,long waitMs) throws ExecutionException {
    try{return completion.get(waitMs,TimeUnit.MILLISECONDS);}
    catch(TimeoutException elapsed){return timeout(requestNonce);}
    catch(InterruptedException interrupted){try{return timeout(requestNonce);}finally{Thread.currentThread().interrupt();}}
  }
  public synchronized void invalidate() {
    invalidation=Math.addExact(invalidation,1);ticket=null;
    if(operation!=null&&operation.phase==Phase.QUEUED)finish(Phase.REJECTED,"lifecycle_invalidated",false,null);
  }
  public synchronized Operation current(){return operation;}
  private synchronized Operation rejectQueued(String id,String reason){Operation held=requireOperation(id);return held==operation&&held.phase==Phase.QUEUED?finish(Phase.REJECTED,reason,false,null):held;}
  private Operation finish(Phase phase,String reason,boolean effective,Fence after){operation=new Operation(operation.nonce,operation.requestSha256,phase,reason,effective,operation.before,after);return operation;}
  private Operation requireOperation(String id){if(operation!=null&&operation.nonce.equals(id))return operation;if(retainedTerminal!=null&&retainedTerminal.nonce.equals(id))return retainedTerminal;throw new SecurityException("command_not_retained");}
  public static String digest(String text) {
    try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder(64);for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
}
