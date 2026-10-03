param([Parameter(Mandatory)][string]$AndroidJar,[Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$JsonJar,[Parameter(Mandatory)][string]$HostClasses,[Parameter(Mandatory)][string]$OutDir)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutDir){throw 'Create-new evidence required.'}
$root=Split-Path $PSScriptRoot -Parent
$source=Join-Path $root 'apps/connection-hub-ble-bridge-android/src/main/java/io/github/mesmerprism/rustyquest/connection_hub_ble_bridge/BridgeService.java'
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
$inputs=@($PSCommandPath,$source,$AndroidJar,$JsonJar,$javac,$java,(Join-Path $HostClasses 'io/github/mesmerprism/rustyquest/connection_hub_ble_bridge/BridgeController.class'))
$pins=@($inputs|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}})
New-Item -ItemType Directory -Path $OutDir|Out-Null
$fixtures=@{
'android/content/Context.java'='package android.content; public class Context { public int checkSelfPermission(String s){return 0;} public <T>T getSystemService(Class<T> t){throw new AssertionError("unexpected platform effect");} public Context getApplicationContext(){return this;} public ComponentName startForegroundService(Intent i){throw new AssertionError("unexpected start");} public boolean stopService(Intent i){throw new AssertionError("unexpected stop");} }'
'android/app/Service.java'='package android.app; import android.content.*; import android.os.IBinder; public class Service extends Context {public static final int START_NOT_STICKY=2;public int stops,lastId,foreground;public boolean stopResult=true;public int onStartCommand(Intent i,int f,int id){return 0;}public boolean stopSelfResult(int id){stops++;lastId=id;return stopResult;}public void stopSelf(int id){throw new AssertionError("broad stop");}public void stopSelf(){throw new AssertionError("broad stop");}public void startForeground(int id,Notification n,int t){foreground++;}public void stopForeground(boolean b){throw new AssertionError("unexpected foreground close");}public void onDestroy(){}public IBinder onBind(Intent i){return null;}}'
'android/os/Looper.java'='package android.os; public class Looper{public static Looper getMainLooper(){return new Looper();}}'
'android/os/Handler.java'='package android.os; public class Handler{public Handler(Looper l){}public boolean post(Runnable r){throw new AssertionError("unexpected post");}public boolean postDelayed(Runnable r,long n){throw new AssertionError("unexpected timer");}public void removeCallbacks(Runnable r){throw new AssertionError("unexpected close");}}'
'android/os/SystemClock.java'='package android.os; public class SystemClock{public static long clock=100;public static long elapsedRealtime(){return clock;}}'
'android/os/Bundle.java'='package android.os; import java.util.*; public class Bundle{public final Map<String,Object> v=new HashMap<>();public Object get(String k){return v.get(k);}public int size(){return v.size();}public long getLong(String k){return (Long)v.get(k);}public String getString(String k){return (String)v.get(k);}}'
'android/content/Intent.java'='package android.content; import android.os.Bundle;public class Intent{String action;Bundle b;public Intent(){}public Intent(Context c,Class<?> t){}public Intent setAction(String s){action=s;return this;}public String getAction(){return action;}public Bundle getExtras(){return b;}public Intent putExtra(String k,String v){if(b==null)b=new Bundle();b.v.put(k,v);return this;}public Intent putExtra(String k,long v){if(b==null)b=new Bundle();b.v.put(k,Long.valueOf(v));return this;}public String getStringExtra(String k){return b==null?null:(String)b.get(k);}public long getLongExtra(String k,long d){return b==null||!(b.get(k)instanceof Long)?d:(Long)b.get(k);}}'
'io/github/mesmerprism/rustyquest/connection_hub_ble_bridge/HubGattBridge.java'='package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;import android.content.Context;final class HubGattBridge{interface Observer{void advertising();void failed(String s);}int closes;HubGattBridge(Context c,long d,Observer o){}void start(){throw new AssertionError("unexpected GATT");}void close(){closes++;}}'
'io/github/mesmerprism/rustyquest/connection_hub_ble_bridge/BridgeServiceRejectTest.java'=@'
package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;
import android.content.Intent;import android.os.SystemClock;import java.lang.reflect.Field;
public final class BridgeServiceRejectTest {
 static int cases;static void check(boolean v){if(!v)throw new AssertionError("case "+cases);cases++;}
 static void set(BridgeService s,String k,Object v)throws Exception{Field f=BridgeService.class.getDeclaredField(k);f.setAccessible(true);f.set(s,v);}
 static void cold(Intent i,int id,boolean result)throws Exception{BridgeService s=new BridgeService();s.stopResult=result;check(s.onStartCommand(i,0,id)==2);check(s.stops==1&&s.lastId==id&&s.foreground==0);}
 public static void main(String[] args)throws Exception{
 cold(null,1,true);cold(new Intent().setAction("foreign"),2,true);cold(new Intent().setAction(BridgeService.STOP),3,true);
 cold(new Intent().setAction(BridgeService.SHELL_START),4,true);
 cold(new Intent().setAction(BridgeService.SHELL_START).putExtra("process_instance_id","wrong").putExtra("generation",1L),5,true);
 cold(new Intent().setAction(BridgeService.START),6,true);
 BridgeController.Port port=new BridgeController.Port(){public long now(){return 100;}public boolean permissionsReady(){return true;}public void requireHubCurrent(){}public void start(){}public boolean stop(){return true;}};
 new BridgeController(port,BridgeController.CURRENT).enableShell(2000);SystemClock.clock=30100;
 Intent expired=new Intent().setAction(BridgeService.SHELL_START).putExtra("process_instance_id",BridgeController.CURRENT.processInstance).putExtra("generation",1L);
 cold(expired,7,true);cold(expired,8,false);
 check(!BridgeController.CURRENT.snapshot("status","observed",false,30100).getBoolean("service_observed"));
 for(boolean withBridge:new boolean[]{false,true}){BridgeService s=new BridgeService();set(s,"ownedGeneration",9L);HubGattBridge b=new HubGattBridge(null,0,null);if(withBridge)set(s,"bridge",b);
 for(Intent bad:new Intent[]{null,new Intent().setAction("foreign"),new Intent().setAction(BridgeService.STOP),expired,new Intent().setAction(BridgeService.START)}){check(s.onStartCommand(bad,0,20)==2);check(s.stops==0&&s.foreground==0&&b.closes==0);}}
 BridgeService queued=new BridgeService();queued.stopResult=false;check(queued.onStartCommand(null,0,21)==2&&queued.lastId==21&&queued.stops==1);check(!BridgeController.CURRENT.snapshot("status","observed",false,30100).getBoolean("service_observed"));
 System.out.println("PASS "+cases+" actual service seam cases");}
}
'@
}
$paths=@();foreach($entry in $fixtures.GetEnumerator()){$path=Join-Path $OutDir $entry.Key;New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent)|Out-Null;[IO.File]::WriteAllText($path,$entry.Value,[Text.UTF8Encoding]::new($false));$paths+=$path}
$classes=Join-Path $OutDir 'classes';New-Item -ItemType Directory -Path $classes|Out-Null
& $javac -encoding UTF-8 -cp "$HostClasses;$JsonJar;$AndroidJar" -d $classes $source @paths *> (Join-Path $OutDir 'compile.log')
if($LASTEXITCODE-ne 0){throw 'Production Service seam compile failed.'}
& $java -cp "$classes;$HostClasses;$JsonJar;$AndroidJar" io.github.mesmerprism.rustyquest.connection_hub_ble_bridge.BridgeServiceRejectTest *> (Join-Path $OutDir 'cases.log')
if($LASTEXITCODE-ne 0){throw 'Production Service seam cases failed.'}
foreach($pin in $pins){if((Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant()-cne$pin.sha256){throw 'Source/tool drift.'}}
@{schema='local.quest.actual_service_rejection_seam.v1';status='passed';source_tools=$pins;fixtures=@($paths|ForEach-Object {@{path=$_;sha256=(Get-FileHash $_).Hash.ToLowerInvariant()}});platform_service_stop_modeled=$true;physical_closure_claim=$false;device_calls=0}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $OutDir 'RESULT.json') -Encoding utf8
