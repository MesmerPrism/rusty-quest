[CmdletBinding()]
param([Parameter(Mandatory)][string]$RepoRoot,[Parameter(Mandatory)][string]$OutputRoot,
      [Parameter(Mandatory)][string]$JdkBin,[Parameter(Mandatory)][string]$JsonJar,
      [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedJsonSha256)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Fresh output required.'}
if((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ine $ExpectedJsonSha256){throw 'Exact JSON jar required.'}
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererStandaloneTerminalFacade.psm1') -Force
$null=New-Item -ItemType Directory -Path $OutputRoot
$utf8=[Text.UTF8Encoding]::new($false)
$fixture=[IO.File]::ReadAllText((Join-Path $RepoRoot 'apps/native-renderer-android/tests/java/io/github/mesmerprism/rustyquest/native_renderer/StandaloneTerminalFacadeTest.java.template'))
if(([regex]::Matches($fixture,'/\*TERMINAL_FACADE\*/')).Count -ne 1){throw 'Fixture source injection marker invalid.'}
$fixture=$fixture.Replace('/*TERMINAL_FACADE*/',(New-NativeRendererStandaloneTerminalFacadeSource))
[IO.File]::WriteAllText((Join-Path $OutputRoot 'StandaloneTerminalFacadeTest.java'),$fixture,$utf8)
$stubs=@{
 'Activity.java'='package android.app; public class Activity {public int finishes; public boolean isDestroyed(){return false;}public boolean isFinishing(){return finishes>0;}public void finishAndRemoveTask(){finishes++;}}';
 'Looper.java'='package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}';
 'SystemClock.java'='package android.os; public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}';
 'Log.java'='package android.util; public class Log {public static int i(String a,String b){return 0;}public static int e(String a,String b){return 0;}}';
 'Handler.java'=@'
package android.os;
public class Handler {
 static class Entry {Handler h;Runnable r;long at;Entry(Handler h,Runnable r,long at){this.h=h;this.r=r;this.at=at;}}
 static java.util.ArrayDeque<Entry> queue=new java.util.ArrayDeque<>();
 public Handler(Looper l){} public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){queue.add(new Entry(this,r,SystemClock.now+delay));return true;}
 public void removeCallbacksAndMessages(Object token){queue.removeIf(e->e.h==this);}
 public static void clear(){queue.clear();}public static boolean empty(){return queue.isEmpty();}
 public static boolean runNext(){if(queue.isEmpty())return false;Entry e=queue.remove();SystemClock.now=Math.max(SystemClock.now,e.at);e.r.run();return true;}
}
'@
}
foreach($name in $stubs.Keys){[IO.File]::WriteAllText((Join-Path $OutputRoot $name),$stubs[$name],$utf8)}
$sources=@(Get-ChildItem -LiteralPath $OutputRoot -Filter '*.java' | ForEach-Object FullName)
$classes=Join-Path $OutputRoot 'classes'
$null=New-Item -ItemType Directory -Path $classes
& (Join-Path $JdkBin 'javac.exe') --release 8 -encoding UTF-8 -cp $JsonJar -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Production terminal facade fixture compile failed.'}
& (Join-Path $JdkBin 'java.exe') -cp ($classes+[IO.Path]::PathSeparator+$JsonJar) 'io.github.mesmerprism.rustyquest.native_renderer.StandaloneTerminalFacadeTest'
if($LASTEXITCODE -ne 0){throw 'Production terminal facade fixture failed.'}
