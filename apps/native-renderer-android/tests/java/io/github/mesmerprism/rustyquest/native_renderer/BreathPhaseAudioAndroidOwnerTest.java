package io.github.mesmerprism.rustyquest.native_renderer;
import android.media.MediaPlayer;import android.content.res.AssetManager;
public final class BreathPhaseAudioAndroidOwnerTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static ConditionAudioContract.Provider provider(String id)throws Exception{
  java.security.MessageDigest d=java.security.MessageDigest.getInstance("SHA-256");StringBuilder h=new StringBuilder();for(byte b:d.digest(new byte[]{1,2,3}))h.append(String.format("%02x",b&255));
  return new ConditionAudioContract.Provider(id,"packaged-native-app-provider","phase-audio/"+id+".mp3",h.toString(),3,"audio/mpeg");
 }
 static class Preloader implements ConditionAudioRuntime.StartupPreloader.Listener{public void onTrackPending(ConditionAudioContract.Provider p){}public void onTrackReady(ConditionAudioContract.Provider p){}public void onTrackFailed(ConditionAudioContract.Provider p,String r){throw new AssertionError(r);}}
 static class Listener implements ConditionAudioRuntime.MediaBackend.Listener{public void onPrepared(){}public void onActualStart(long p){}public void onProgress(long p){}public void onNaturalEnd(long p){}public void onError(String r){throw new AssertionError(r);}}
 public static void main(String[] args)throws Exception{
  AssetManager assets=new AssetManager();ConditionAudioContract.Provider bg=provider("background");
  ConditionAudioAndroidMediaBackendFactory normal=new ConditionAudioAndroidMediaBackendFactory(assets);
  normal.preload(new ConditionAudioContract.Provider[]{bg},new Preloader());ConditionAudioRuntime.MediaBackend n=normal.create(bg,new Listener());n.prepare(false);check(!MediaPlayer.players.get(0).looping,"existing default never loops");n.release();normal.closePreloads();
  MediaPlayer.players.clear();ConditionAudioAndroidMediaBackendFactory looping=new ConditionAudioAndroidMediaBackendFactory(assets,true);
  looping.preload(new ConditionAudioContract.Provider[]{bg},new Preloader());ConditionAudioRuntime.MediaBackend b=looping.create(bg,new Listener());b.prepare(false);b.start();MediaPlayer background=MediaPlayer.players.get(0);background.position=345;
  BreathPhaseAudioAndroidOwner owner=new BreathPhaseAudioAndroidOwner(assets,provider("inhale"),provider("retention"),provider("exhale"));
  long deadline=System.nanoTime()+2_000_000_000L;while(!"ready".equals(owner.readiness())&&System.nanoTime()<deadline)Thread.sleep(5);check("ready".equals(owner.readiness()),"all cues prepared before admission");
  owner.control(1,"official-start");owner.sample(1,0,0,4000,0,"inhale");Thread.sleep(30);
  check(background.looping&&background.playing&&background.starts==1&&background.position==345,"cue does not reset background");
  owner.sample(1,1,4000,8000,4000,"hold");Thread.sleep(30);int playing=0;for(MediaPlayer p:MediaPlayer.players)if(p.playing)playing++;check(playing==2,"exact independent background + one current cue");
  owner.control(1,"experiment-paused");b.pause();Thread.sleep(30);check(background.position==345&&!background.playing,"pause retains music position");
  b.start();owner.control(1,"experiment-resumed");owner.sample(1,1,4000,8000,4500,"hold");Thread.sleep(30);playing=0;for(MediaPlayer p:MediaPlayer.players)if(p.playing)playing++;check(playing==1,"midphase resume no duplicate cue");
  owner.control(1,"stop");b.stop();Thread.sleep(30);check("complete".equals(owner.shutdownStatus()),"cue stop acknowledgement");
  owner.close();check(owner.awaitClosed(2000),"cue cleanup acknowledged");b.release();looping.closePreloads();for(MediaPlayer p:MediaPlayer.players)check(p.released,"every player released");
  AssetManager corrupt=new AssetManager();corrupt.bytes=new byte[]{9};BreathPhaseAudioAndroidOwner bad=new BreathPhaseAudioAndroidOwner(corrupt,provider("inhale"),provider("retention"),provider("exhale"));Thread.sleep(30);check(!"ready".equals(bad.readiness()),"actual owner denies drift before cue");bad.close();bad.awaitClosed(2000);
  MediaPlayer.players.clear();
  BreathPhaseAudioAndroidOwner failing=new BreathPhaseAudioAndroidOwner(assets,provider("inhale"),provider("retention"),provider("exhale"));
  deadline=System.nanoTime()+2_000_000_000L;while(!"ready".equals(failing.readiness())&&System.nanoTime()<deadline)Thread.sleep(5);
  check("ready".equals(failing.readiness()),"failure control cues prepared");failing.control(2,"official-start");failing.sample(2,0,0,4000,0,"inhale");
  deadline=System.nanoTime()+2_000_000_000L;while(!MediaPlayer.players.get(0).playing&&System.nanoTime()<deadline)Thread.sleep(5);
  check(MediaPlayer.players.get(0).playing,"real owner cue active before injected stop failure");MediaPlayer.players.get(0).failPause=true;
  failing.close();check(!failing.awaitClosed(2000),"pause failure cannot claim successful cleanup");
  check("failed".equals(failing.shutdownStatus()),"cleanup failure remains failed");for(MediaPlayer p:MediaPlayer.players)check(p.released,"all releases attempted despite cue stop failure");
  System.out.println("BreathPhaseAudioAndroidOwnerTest PASS actual production methods, fake platform transport only");
 }
}
