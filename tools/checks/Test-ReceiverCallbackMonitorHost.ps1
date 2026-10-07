param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$OutputRoot,[switch]$PrepareOnly)
Set-StrictMode -Version Latest;$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$source=Join-Path $repo 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media/PackedStereoMediaReceiver.java'
if(Test-Path $OutputRoot){throw 'Create-new host output required'}
$text=[IO.File]::ReadAllText($source);$begin=$text.IndexOf('    public void stopAndVerify() {');$end=$text.IndexOf('    @Override public void close()', $begin)
if($begin-lt0-or$end-le$begin){throw 'Exact production stop method missing'}
$method=$text.Substring($begin,$end-$begin)
$header=@'
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
public final class ReceiverCallbackMonitorHost {
 final Object lock=new Object();boolean stopRequested;String state="running",connectionState="connected",failure;
 final AtomicLong revision=new AtomicLong();Thread worker;Object incomingListener,socket,decoder;
 long connectionGeneration=1;final Map<Long,Object> pendingFrames=new HashMap<>();final Bounds bounds=new Bounds();HandlerThread renderThread;
 static final class Bounds{long shutdownTimeoutMs=80;}
 void requireNotMainThread(String x){} void closeSocket(){} void closeListener(){} void releaseConnection(){}
 final class HandlerThread extends Thread {
  final int mode;final CountDownLatch quit=new CountDownLatch(1),release=new CountDownLatch(1),completed=new CountDownLatch(1);
  boolean quitCalled;
  HandlerThread(int value){mode=value;}
  public void run(){try{quit.await();if(mode==3)release.await();synchronized(lock){
   if(mode==1){renderThread=new HandlerThread(0);state="successor";}
   if(mode==2){connectionGeneration++;state="successor";}
   completed.countDown();
  }}catch(InterruptedException x){throw new AssertionError(x);}}
  void quitSafely(){quitCalled=true;quit.countDown();}
 }
'@
$tail=@'
 public static void main(String[]args)throws Exception{
  for(int mode=0;mode<4;mode++){
   ReceiverCallbackMonitorHost receiver=new ReceiverCallbackMonitorHost();HandlerThread callback=receiver.new HandlerThread(mode);receiver.renderThread=callback;callback.start();
   boolean rejected=false;try{receiver.stopAndVerify();}catch(IllegalStateException expected){rejected=true;}
   if(mode==0){if(rejected||!receiver.state.equals("stopped")||receiver.renderThread!=null||callback.isAlive())throw new AssertionError("queued callback could not drain");}
   if(mode==1){if(!rejected||!receiver.state.equals("successor")||receiver.renderThread==callback||receiver.renderThread.quitCalled)throw new AssertionError("successor thread affected");}
   if(mode==2){if(!rejected||!receiver.state.equals("successor")||receiver.connectionGeneration!=2||receiver.renderThread!=callback)throw new AssertionError("successor connection affected");}
   if(mode==3){if(!rejected||!receiver.state.equals("failed")||receiver.renderThread!=callback||!callback.isAlive())throw new AssertionError("live callback incorrectly terminal");callback.release.countDown();}
   callback.join();if(callback.completed.getCount()!=0)throw new AssertionError("callback unreaped");
  }
  ReceiverCallbackMonitorHost retained=new ReceiverCallbackMonitorHost();retained.decoder=new Object();boolean rejected=false;try{retained.stopAndVerify();}catch(IllegalStateException expected){rejected=true;}
  if(!rejected||!retained.state.equals("failed")||retained.decoder==null)throw new AssertionError("decoder barrier weakened");
  System.out.println("PASS 5 production-method schedules: queued callback drains; thread/connection successors preserved; live callback and retained decoder remain nonterminal. Android resources modeled; no physical cleanup proof.");
 }
}
'@
$null=New-Item -ItemType Directory $OutputRoot
$harness=Join-Path $OutputRoot 'ReceiverCallbackMonitorHost.java';[IO.File]::WriteAllText($harness,$header+"`n"+$method+"`n"+$tail,[Text.UTF8Encoding]::new($false))
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
$compile=@('--release','17','-Xlint:all','-Werror','-d',$OutputRoot,$harness);$execute=@('-cp',$OutputRoot,'ReceiverCallbackMonitorHost')
if($PrepareOnly){@{source_path=$source;source_sha256=(Get-FileHash $source).Hash.ToLowerInvariant();harness_path=$harness;harness_sha256=(Get-FileHash $harness).Hash.ToLowerInvariant();compile_executable=$javac;compile_arguments=$compile;test_executable=$java;test_arguments=$execute;limits='Extracted exact production method; callback/platform fields modeled; no device/decoder/native cleanup qualification.'}|ConvertTo-Json -Depth 10;return}
& $javac @compile *> (Join-Path $OutputRoot 'compile.log');if($LASTEXITCODE){throw 'Actual method host compile failed'}
& $java @execute *> (Join-Path $OutputRoot 'test.log');if($LASTEXITCODE){throw 'Callback retirement schedules failed'}
Get-Content (Join-Path $OutputRoot 'test.log')
