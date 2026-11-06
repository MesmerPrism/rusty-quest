package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.ArrayList;
import java.util.List;
public final class BreathPhaseAudioEngineTest {
 static final class Sink implements BreathPhaseAudioEngine.Backend {
  final List<String> events=new ArrayList<String>();
  public void startBackground(){events.add("start");} public void pauseBackground(){events.add("pause");}
  public void resumeBackground(){events.add("resume");} public void stopBackground(){events.add("stop");}
  public void replaceCue(String p){events.add(p);} public void stopCue(){events.add("cue-stop");}
  int count(String p){int n=0;for(String e:events)if(e.equals(p))n++;return n;}
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  Sink s=new Sink();BreathPhaseAudioEngine e=new BreathPhaseAudioEngine(true,s);e.start(1);
  e.sample(1,0,0,4000,0,"inhale");e.sample(1,0,0,4000,100,"inhale");
  e.sample(1,1,4000,8000,4000,"hold");e.sample(1,2,8000,12000,8000,"exhale");
  e.sample(1,3,12000,16000,12000,"hold");e.sample(1,4,16000,20000,16000,"hold");
  check(s.count("hold")==3,"distinct retention segments including adjacent hold");
  check(s.count("start")==1&&s.count("stop")==0,"cue never resets background");
  e.pause(1);e.resume(1);e.sample(1,4,16000,20000,17000,"hold");
  check(s.count("hold")==3&&s.count("resume")==1,"midphase resume no duplicate");
  e.sample(1,9,36000,40000,36000,"inhale");check(s.count("inhale")==2,"forward jump current only no catch-up");
  e.sample(1,10,40000,44000,41000,"exhale");check(s.count("exhale")==2,"late current edge once");
  e.sample(1,9,36000,40000,36500,"inhale");check(s.count("pause")==2,"regressed clock fail fence");
  e.stop(1);e.resume(1);check(!e.running(),"retired generation cannot resume");e.start(2);e.sample(1,0,0,4000,0,"inhale");e.sample(2,0,0,4000,0,"inhale");
  check(s.count("inhale")==3,"new generation only");e.close();e.start(3);
  check(s.count("start")==2,"close terminal");
  Sink off=new Sink();BreathPhaseAudioEngine d=new BreathPhaseAudioEngine(false,off);d.start(1);d.sample(1,0,0,4000,0,"inhale");d.close();
  check(off.events.isEmpty(),"disabled engine has no effects");
  System.out.println("BreathPhaseAudioEngineTest PASS");
 }
}
