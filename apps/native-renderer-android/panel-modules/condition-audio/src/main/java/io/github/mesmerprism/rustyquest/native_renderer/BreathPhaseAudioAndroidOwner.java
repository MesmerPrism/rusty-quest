package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.res.AssetManager;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate app-lifetime cue/background channels, serialized off UI/OpenXR threads.
 * Assets are supplied only by the already verified closed packaged configuration.
 */
final class BreathPhaseAudioAndroidOwner implements AutoCloseable {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final BreathPhaseAudioEngine engine;
 private final Channels channels=new Channels();
 private volatile String readiness="preparing";
 private volatile boolean closed;
 private volatile boolean stopped=true;
 private final java.util.concurrent.atomic.AtomicInteger pending=new java.util.concurrent.atomic.AtomicInteger();
 private final java.util.concurrent.atomic.AtomicReference<Runnable> latestSample=new java.util.concurrent.atomic.AtomicReference<Runnable>();
 private final java.util.concurrent.atomic.AtomicBoolean draining=new java.util.concurrent.atomic.AtomicBoolean();
 BreathPhaseAudioAndroidOwner(final AssetManager assets,
      final ConditionAudioContract.Provider inhale,
      final ConditionAudioContract.Provider retention,
      final ConditionAudioContract.Provider exhale) {
  engine=new BreathPhaseAudioEngine(true,channels);
  worker.execute(new Runnable(){public void run(){try{
   channels.inhale=prepare(assets,inhale,false);
   channels.retention=prepare(assets,retention,false);
   channels.exhale=prepare(assets,exhale,false);readiness="ready";
  }catch(Exception error){readiness="asset-preparation-failed";try{channels.release();}catch(RuntimeException cleanup){readiness="cleanup-failed";}}
  }});
 }
 String readiness(){return readiness;}
 String shutdownStatus(){return readiness.endsWith("-failed")?"failed":pending.get()==0 && stopped?"complete":"pending";}
 synchronized void control(final long generation,final String event){
  if(closed)return;pending.incrementAndGet();worker.execute(new Runnable(){public void run(){
   try{if(!"ready".equals(readiness))return;
   if("official-start".equals(event)){engine.start(generation);stopped=!engine.running();}
    else if("experiment-paused".equals(event))engine.pause(generation);
    else if("experiment-resumed".equals(event))engine.resume(generation);
    else if("stop".equals(event)){engine.stop(generation);stopped=!engine.running();}
   }catch(RuntimeException error){readiness="playback-failed";engine.close();}
   finally{pending.decrementAndGet();}
  }});
 }
 synchronized void sample(final long generation,final long index,final long start,final long end,
             final long activeTime,final String phase){
  if(closed)return;
  latestSample.set(new Runnable(){public void run(){
   if(!"ready".equals(readiness))return;
   try{if("inactive".equals(phase))engine.gap(generation);else {
    engine.sample(generation,index,start,end,activeTime,phase);
    android.util.Log.i("RustyQuestNativeRenderer","channel=breath-phase-audio generation="+generation
       +" segment="+index+" phase="+phase+" startMs="+start+" observedActiveMs="+activeTime
       +" observedLagMs="+(activeTime-start));
   }}catch(RuntimeException error){readiness="playback-failed";engine.close();}
  }});
  scheduleSampleDrain();
 }
 private synchronized void scheduleSampleDrain(){
  if(closed)return;
  if(!draining.compareAndSet(false,true))return;
  worker.execute(new Runnable(){public void run(){try{
   Runnable sample=latestSample.getAndSet(null);if(sample!=null)sample.run();
  }finally{draining.set(false);if(latestSample.get()!=null)scheduleSampleDrain();}}});
 }

 public synchronized void close(){
  if(closed)return;closed=true;latestSample.set(null);
  worker.execute(new Runnable(){public void run(){
   boolean failed=false;
   try{engine.close();}catch(RuntimeException error){failed=true;}
   try{channels.release();}catch(RuntimeException error){failed=true;}
   if(failed)readiness="cleanup-failed";else stopped=true;
  }});worker.shutdown();
 }
 boolean awaitClosed(long timeoutMs)throws InterruptedException{
  return worker.awaitTermination(timeoutMs,java.util.concurrent.TimeUnit.MILLISECONDS)
      && !readiness.endsWith("-failed");
 }

 private static MediaPlayer prepare(AssetManager assets,ConditionAudioContract.Provider p,boolean loop)throws Exception{
  if(p==null||!p.valid()||!"audio/mpeg".equals(p.mediaType))throw new SecurityException("closed MP3 identity required");
  MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
  try(InputStream in=assets.open(p.logicalDestination)){byte[] b=new byte[8192];int n;
   while((n=in.read(b))!=-1){count+=n;if(count>p.sourceBytes)throw new SecurityException("asset byte drift");digest.update(b,0,n);}}
  StringBuilder h=new StringBuilder();for(byte b:digest.digest())h.append(String.format("%02x",b&255));
  if(count!=p.sourceBytes||!h.toString().equals(p.sourceSha256))throw new SecurityException("asset identity drift");
  MediaPlayer player=new MediaPlayer();try{
   try(AssetFileDescriptor fd=assets.openFd(p.logicalDestination)){player.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());}
   player.setLooping(loop);player.prepare();return player;
  }catch(Exception error){player.release();throw error;}
 }
 private static final class Channels implements BreathPhaseAudioEngine.Backend {
  MediaPlayer inhale,retention,exhale,current;
  // Background transport/lifecycle belongs to the existing condition audio owner.
  // These no-ops prevent cue phase changes from restarting that independent channel.
  public void startBackground(){} public void pauseBackground(){}
  public void resumeBackground(){} public void stopBackground(){}
  public void replaceCue(String phase){stopCue();current="inhale".equals(phase)?inhale:"hold".equals(phase)?retention:exhale;current.seekTo(0);current.start();}
  public void stopCue(){if(current!=null&&current.isPlaying())current.pause();current=null;}
  void release(){RuntimeException failure=null;for(MediaPlayer p:new MediaPlayer[]{inhale,retention,exhale})if(p!=null){try{p.release();}catch(RuntimeException e){failure=e;}}if(failure!=null)throw failure;}
 }
}
