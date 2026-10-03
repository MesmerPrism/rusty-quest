[CmdletBinding()]
param([Parameter(Mandatory)][string]$RepoRoot)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition-ne'Core'-or$PSVersionTable.PSVersion-lt[version]'7.6'){throw 'Receipt race tests require PowerShell 7.6 Core'}
# Host collaborators model Android I/O and platform effects. Actual production
# receipt store and callback receiver are compiled; this is no device proof.
# Test-only org.json 20240303: artifact POM declares Public Domain.
# https://github.com/stleary/JSON-java ; no third-party binary is committed.
$jsonSha='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
$jsonBytes=78332
$temporaryBase=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryRoot=[IO.Path]::Combine($temporaryBase,'rusty-quest-receipt-races-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporaryRoot)
try{
 $jsonJar=Join-Path $temporaryRoot 'json-20240303.jar'
 $cacheProfile=[Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
 $cache=$null
 if(-not[string]::IsNullOrWhiteSpace($cacheProfile)){$cache=Join-Path $cacheProfile '.gradle/caches/modules-2/files-2.1/org.json/json/20240303/ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a/json-20240303.jar'}
 if($cache-and(Test-Path -LiteralPath $cache -PathType Leaf)-and(Get-Item -LiteralPath $cache).Length-eq$jsonBytes-and(Get-FileHash -LiteralPath $cache).Hash.ToLowerInvariant()-ceq$jsonSha){
  [IO.File]::Copy($cache,$jsonJar,$false)
 }else{
  $handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false
  $client=[Net.Http.HttpClient]::new($handler);$deadline=[Threading.CancellationTokenSource]::new(30000)
  $response=$null;$stream=$null;$destination=$null
  try{
   $response=$client.GetAsync('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',[Net.Http.HttpCompletionOption]::ResponseHeadersRead,$deadline.Token).GetAwaiter().GetResult()
   if([int]$response.StatusCode-ne200){throw 'Pinned host JSON artifact download failed'}
   if($response.Content.Headers.ContentLength-ne$jsonBytes){throw 'Pinned host JSON artifact length differs'}
   $stream=$response.Content.ReadAsStreamAsync($deadline.Token).GetAwaiter().GetResult()
   $destination=[IO.File]::Open($jsonJar,[IO.FileMode]::CreateNew)
   $buffer=[byte[]]::new(16384);$total=0
   while(($read=$stream.ReadAsync($buffer,0,$buffer.Length,$deadline.Token).GetAwaiter().GetResult())-gt0){
    $total+=$read;if($total-gt$jsonBytes){throw 'Pinned host JSON artifact exceeds bound'}
    $destination.Write($buffer,0,$read)
   }
   if($total-ne$jsonBytes){throw 'Pinned host JSON artifact incomplete'}
  }finally{if($destination){$destination.Dispose()};if($stream){$stream.Dispose()};if($response){$response.Dispose()};$deadline.Dispose();$client.Dispose()}
 }
 if((Get-Item $jsonJar).Length-ne$jsonBytes-or(Get-FileHash $jsonJar).Hash.ToLowerInvariant()-cne$jsonSha){throw 'Pinned host JSON artifact hash differs'}
 $sources=[ordered]@{
 'android/content/Context.java'=@'
package android.content;import java.io.File;public class Context {public File dir;public int activities;public static Runnable beforeActivity;public Context(File d){dir=d;}public File getNoBackupFilesDir(){return dir;}public void startActivity(Intent i){if(beforeActivity!=null)beforeActivity.run();activities++;}}
'@
 'android/content/BroadcastReceiver.java'=@'
package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}
'@
 'android/net/Uri.java'=@'
package android.net;import java.util.*;public class Uri {public int id;public String token;public Uri(int i,String t){id=i;token=t;}public String getScheme(){return "rusty-package-updater";}public String getAuthority(){return "install";}public List<String> getPathSegments(){return Arrays.asList(""+id,token);}}
'@
 'android/content/Intent.java'=@'
package android.content;import android.net.Uri;public class Intent {public static final String EXTRA_INTENT="intent";public static final int FLAG_ACTIVITY_NEW_TASK=1;public int id,status;public String token;public static Runnable beforeConfirmation;public Intent(int i,String t,int s){id=i;token=t;status=s;}public String getAction(){return "io.github.mesmerprism.rustyquest.packageupdater.labs.INSTALL_STATUS";}public Uri getData(){return new Uri(id,token);}public int getIntExtra(String k,int d){return k.equals("session")?id:status;}public String getStringExtra(String k){return "modeled";}public <T>T getParcelableExtra(String k,Class<T> c){if(beforeConfirmation!=null)beforeConfirmation.run();return c.cast(this);}public Intent addFlags(int f){return this;}}
'@
 'android/content/pm/PackageInstaller.java'=@'
package android.content.pm;public class PackageInstaller {public static final String EXTRA_SESSION_ID="session",EXTRA_STATUS="status",EXTRA_STATUS_MESSAGE="message";public static final int STATUS_PENDING_USER_ACTION=-1,STATUS_SUCCESS=0,STATUS_FAILURE_INVALID=4,STATUS_FAILURE_ABORTED=3;}
'@
 'android/util/AtomicFile.java'=@'
package android.util;import java.io.*;public class AtomicFile {private File f;public static volatile Runnable beforeFinish;public AtomicFile(File x){f=x;}public File getBaseFile(){return f;}public FileInputStream openRead()throws Exception{return new FileInputStream(f);}public FileOutputStream startWrite()throws Exception{return new FileOutputStream(f);}public void finishWrite(FileOutputStream o)throws Exception{Runnable r=beforeFinish;if(r!=null)r.run();o.close();}public void failWrite(FileOutputStream o)throws Exception{o.close();}}
'@
 'io/github/mesmerprism/rustyquest/packageupdater/VerifiedUpdatePlan.java'=@'
package io.github.mesmerprism.rustyquest.packageupdater;class VerifiedUpdatePlan {UpdateArtifact artifact=new UpdateArtifact("test.package",1,"1",java.net.URI.create("https://example.invalid/a.apk"),1,"digest","signer");String manifestId="modeled",channel="labs",keyId="key",publicKey="public",httpsOrigin="https://example.invalid",rolloutRing="labs",signedManifestSha256="digest";long sequence=1,expiresAtMs=Long.MAX_VALUE;}
'@
 'io/github/mesmerprism/rustyquest/packageupdater/PackageInstallController.java'=@'
package io.github.mesmerprism.rustyquest.packageupdater;import android.content.Context;import org.json.JSONObject;class PackageInstallController {static int cleanups,checkpoints,readbacks;static void verifyInstalledReadback(Context c,JSONObject r){readbacks++;}static boolean commitInstalledCheckpoint(Context c,JSONObject r,long t){checkpoints++;return true;}static void cleanupTerminalArtifacts(Context c,JSONObject r){cleanups++;}}
'@
 'io/github/mesmerprism/rustyquest/packageupdater/ReceiptRaceFixture.java'=@'
package io.github.mesmerprism.rustyquest.packageupdater;
import android.content.*;import android.util.AtomicFile;import org.json.*;import java.io.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class ReceiptRaceFixture {
static int cases;static Context c;static InstallReceiptStore a,b;
static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);cases++;}
static void denied(Work work,String code)throws Exception{try{work.run();throw new AssertionError("accepted "+code);}catch(IllegalStateException e){check(code.equals(e.getMessage()),"exact deny "+code);}}
interface Work{void run()throws Exception;}
static void begin(InstallReceiptStore s,int id)throws Exception{s.begin(id,"token"+id,new VerifiedUpdatePlan(),new File(c.dir,"MODELED.apk"));}
public static void main(String[] args)throws Exception {
c=new Context(new File(args[0]));c.dir.mkdirs();a=new InstallReceiptStore(c);b=new InstallReceiptStore(c);
begin(a,1);InstallReceiptStore.CallbackReceipt first=a.captureCallback(new Intent(1,"token1",3));check(first!=null,"current callback captured");
denied(()->begin(b,2),"install_receipt_callback_active");a.updateCallbackState(first,1,"install_cancelled_by_wearer",3,"fixture");
denied(()->begin(b,2),"install_receipt_callback_active");first.close();first.close();begin(b,2);
check(a.read().getInt("session_id")==2,"new session after release");denied(()->a.updateCallbackState(first,1,"install_failed_status_3",3,"late"),"install_receipt_callback_mismatch");check(a.read().getInt("session_id")==2,"late owner cannot mutate new");
check(a.captureCallback(new Intent(1,"token1",3))==null,"retired token denied");check(a.captureCallback(new Intent(2,"wrong",3))==null,"wrong token denied");
InstallReceiptStore.CallbackReceipt second=b.captureCallback(new Intent(2,"token2",3));first.close();denied(()->begin(a,3),"install_receipt_callback_active");second.close();
// Force a competing begin during a real production store's state read/write.
CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> fail=new AtomicReference<>();AtomicBoolean newDone=new AtomicBoolean();
AtomicFile.beforeFinish=()->{if(Thread.currentThread().getName().equals("old-state")){writing.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(Exception e){throw new RuntimeException(e);}}};
Thread old=new Thread(()->{try{a.updateState(2,"install_cancelled_by_wearer",3,"fixture");}catch(Throwable e){fail.set(e);}},"old-state");
Thread newer=new Thread(()->{try{begin(b,3);newDone.set(true);}catch(Throwable e){fail.set(e);}},"new-begin");
try{old.start();check(writing.await(3,TimeUnit.SECONDS),"old writer reached owned file boundary");newer.start();Thread.sleep(50);check(!newDone.get(),"other store cannot overwrite in-flight writer");release.countDown();old.join(3000);newer.join(3000);check(!old.isAlive()&&!newer.isAlive()&&fail.get()==null,"bounded race finished");check(a.read().getInt("session_id")==3,"newest receipt survives");}
finally{AtomicFile.beforeFinish=null;release.countDown();old.interrupt();newer.interrupt();old.join(3000);newer.join(3000);}
PackageInstallCallbackReceiver receiver=new PackageInstallCallbackReceiver();receiver.onReceive(c,new Intent(2,"token2",3));check(a.read().getInt("session_id")==3&&PackageInstallController.cleanups==0,"retired receiver cannot clean new receipt");
receiver.onReceive(c,new Intent(3,"token3",3));check(a.read().optString("state").equals("install_cancelled_by_wearer")&&PackageInstallController.cleanups==1,"current receiver preserved");receiver.onReceive(c,new Intent(3,"token3",3));check(PackageInstallController.cleanups==1,"terminal duplicate has no effects");
begin(a,4);receiver.onReceive(c,new Intent(4,"token4",-1));check(c.activities==1&&a.read().optString("state").equals("pending_user_confirmation"),"current wearer confirmation preserved");
receiver.onReceive(c,new Intent(4,"token4",0));check(PackageInstallController.checkpoints==1&&a.read().optString("state").equals("installed_readback_ok"),"current success handler preserved");begin(b,5);check(a.read().getInt("session_id")==5,"receiver finally releases");
// A cancellation that wins before transition must not launch confirmation.
begin(a,6);int before=c.activities;
Intent.beforeConfirmation=()->{try{b.updateState(6,"cancel_requested_awaiting_installer_callback",null,"fixture");}catch(Exception e){throw new RuntimeException(e);}};
try{receiver.onReceive(c,new Intent(6,"token6",-1));}finally{Intent.beforeConfirmation=null;}
check(c.activities==before&&a.read().optString("state").startsWith("cancel_requested"),"cancel-before-transition suppresses confirmation");
// Attempt cancellation exactly inside dispatch; the shared receipt lock must
// serialize it after that one synchronous platform call, not lose cancellation.
begin(a,7);AtomicBoolean cancelDone=new AtomicBoolean();CountDownLatch cancelStarted=new CountDownLatch(1);AtomicReference<Throwable> cancelError=new AtomicReference<>();
Thread canceller=new Thread(()->{cancelStarted.countDown();try{b.updateState(7,"cancel_requested_awaiting_installer_callback",null,"fixture");cancelDone.set(true);}catch(Throwable e){cancelError.set(e);}},"confirmation-cancel");
Context.beforeActivity=()->{canceller.start();try{if(!cancelStarted.await(3,TimeUnit.SECONDS))throw new AssertionError("cancel did not start");Thread.sleep(50);check(!cancelDone.get(),"cancel cannot interleave transition and confirmation dispatch");}catch(Exception e){throw new RuntimeException(e);}};
try{receiver.onReceive(c,new Intent(7,"token7",-1));canceller.join(3000);check(!canceller.isAlive()&&cancelError.get()==null&&cancelDone.get()&&a.read().optString("state").startsWith("cancel_requested"),"serialized cancellation retained after dispatch");}
finally{Context.beforeActivity=null;canceller.interrupt();canceller.join(3000);}
// Counterfactual old sequence uses the actual store but splits transition and
// effect: cancellation fits between them, so a boolean return alone is unsafe.
begin(a,8);try(InstallReceiptStore.CallbackReceipt oldSequence=a.captureCallback(new Intent(8,"token8",-1))){
a.updateCallbackState(oldSequence,8,"pending_user_confirmation",-1,"fixture");b.updateState(8,"cancel_requested_awaiting_installer_callback",null,"fixture");before=c.activities;c.startActivity(new Intent(8,"token8",-1));check(c.activities==before+1&&a.read().optString("state").startsWith("cancel_requested"),"counterfactual split dispatch permits cancelled UI");}
System.out.println("Actual Store/Receiver race fixture passed: "+cases+" cases; Android I/O/effects and plan are modeled host collaborators only");
}}

'@
 }
 $javaFiles=@()
 foreach($entry in $sources.GetEnumerator()){
  $path=Join-Path $temporaryRoot $entry.Key
  [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
  [IO.File]::WriteAllText($path,$entry.Value,[Text.UTF8Encoding]::new($false))
  $javaFiles+=,$path
 }
 $production=Join-Path $RepoRoot 'apps/package-updater-android/app/src/main/java/io/github/mesmerprism/rustyquest/packageupdater'
 foreach($name in @('InstallReceiptStore.java','PackageInstallCallbackReceiver.java','UpdateArtifact.java')){$javaFiles+=,(Join-Path $production $name)}
 $classes=Join-Path $temporaryRoot 'classes';[void][IO.Directory]::CreateDirectory($classes)
 $javac=(Get-Command javac -ErrorAction Stop).Source;$java=(Get-Command java -ErrorAction Stop).Source
 & $javac -encoding UTF-8 -cp $jsonJar -d $classes @javaFiles
 if($LASTEXITCODE-ne0){throw 'Actual receipt store/receiver race fixture did not compile'}
 $classPath=$classes+[IO.Path]::PathSeparator+$jsonJar
 $result=& $java -cp $classPath 'io.github.mesmerprism.rustyquest.packageupdater.ReceiptRaceFixture' (Join-Path $temporaryRoot 'store')
 if($LASTEXITCODE-ne0-or($result-join"`n")-notmatch'Actual Store/Receiver race fixture passed: 23 cases'){throw 'Actual receipt store/receiver races failed'}
 $result
}finally{
 $relative=[IO.Path]::GetRelativePath($temporaryBase,$temporaryRoot)
 if([IO.Path]::IsPathRooted($relative)-or$relative.StartsWith('..')-or$relative.Contains([IO.Path]::DirectorySeparatorChar)-or-not$relative.StartsWith('rusty-quest-receipt-races-')){throw 'Unexpected receipt test cleanup scope'}
 if([IO.Directory]::Exists($temporaryRoot)){[IO.Directory]::Delete($temporaryRoot,$true)}
}
