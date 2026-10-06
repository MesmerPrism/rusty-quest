param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$AndroidJar,[Parameter(Mandatory)][string]$OutputRoot)
Set-StrictMode -Version Latest;$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$dir=Join-Path $repo 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
$memo=Join-Path $dir 'InstalledApkDigestMemo.java';$adapter=Join-Path $dir 'InstalledApkDigest.java'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new host output required'}
$null=New-Item -ItemType Directory $OutputRoot
$test=@'
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class InstalledApkDigestHost {
 static final class Source implements InstalledApkDigestMemo.Source {
  volatile String identity="package/version/install/path/dev/inode/ctime-ns=1";
  volatile byte[] bytes={1,2,3};volatile boolean openFail,readFail,closeFail,changeDuring,guardFail;
  AtomicInteger reads=new AtomicInteger(),opens=new AtomicInteger(),closes=new AtomicInteger();
  public InstalledApkDigestMemo.Selection open() throws IOException {
   if(openFail)throw new IOException("unreadable");opens.incrementAndGet();String captured=identity;
   ByteArrayInputStream body=new ByteArrayInputStream(bytes);
   InputStream stream=new InputStream(){public int read()throws IOException{throw new IOException("bulk only");}
    public int read(byte[] target,int offset,int count)throws IOException{
     if(readFail)throw new IOException("read failure");reads.incrementAndGet();
     if(changeDuring)identity="changed-during-read";return body.read(target,offset,count);
    }};
   return new InstalledApkDigestMemo.Selection(){
    public Object identity(){return captured;}public InputStream input(){return stream;}
    public void requireCurrent()throws IOException{if(guardFail||!captured.equals(identity))throw new IOException("identity changed");}
    public void close()throws IOException{closes.incrementAndGet();if(closeFail)throw new IOException("close failure");}
   };
  }
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void reject(InstalledApkDigestMemo memo,Source source)throws Exception{
  boolean failed=false;try{memo.read(source);}catch(IOException expected){failed=true;}check(failed,"failed observation returned digest");
 }
 public static void main(String[]args)throws Exception{
  Source s=new Source();InstalledApkDigestMemo memo=new InstalledApkDigestMemo();String original=memo.read(s);int read=s.reads.get();
  check(original.equals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"),"digest differs");
  for(int i=0;i<20;i++)check(memo.read(s).equals(original),"stable digest differs");
  check(s.reads.get()==read&&s.opens.get()==21&&s.closes.get()==21,"hit skipped readability or rehashed");
  s.identity="changed-install-time";memo.read(s);check(s.reads.get()>read,"install invalidation missed");read=s.reads.get();
  s.identity="changed-inode-same-size-and-mtime";memo.read(s);check(s.reads.get()>read,"file identity invalidation missed");
  s.identity="changed-ctime-nanoseconds";s.bytes=new byte[]{4,5,6};check(!memo.read(s).equals(original),"changed bytes cached");
  s.openFail=true;reject(memo,s);s.openFail=false;read=s.reads.get();memo.read(s);check(s.reads.get()>read,"open failure retained cache");
  s.identity="new-read-failure";s.readFail=true;reject(memo,s);s.readFail=false;read=s.reads.get();memo.read(s);check(s.reads.get()>read,"read failure retained cache");
  s.guardFail=true;reject(memo,s);s.guardFail=false;read=s.reads.get();memo.read(s);check(s.reads.get()>read,"failed live identity retained cache");
  s.closeFail=true;reject(memo,s);s.closeFail=false;read=s.reads.get();memo.read(s);check(s.reads.get()>read,"close failure retained cache");
  s.identity="before-change";s.changeDuring=true;reject(memo,s);s.changeDuring=false;read=s.reads.get();memo.read(s);check(s.reads.get()>read,"changed-during-read published");
  read=s.reads.get();new InstalledApkDigestMemo().read(s);check(s.reads.get()>read,"new instance inherited digest");
  Source concurrent=new Source();InstalledApkDigestMemo shared=new InstalledApkDigestMemo();ExecutorService pool=Executors.newFixedThreadPool(8);
  try{List<Future<String>> tasks=new ArrayList<>();for(int i=0;i<32;i++)tasks.add(pool.submit(()->shared.read(concurrent)));
   for(Future<String> result:tasks)check(result.get().equals(original),"concurrent digest differs");
  }finally{pool.shutdown();check(pool.awaitTermination(10,TimeUnit.SECONDS),"workers unreaped");}
  check(concurrent.reads.get()==2&&concurrent.opens.get()==32&&concurrent.closes.get()==32,"concurrent requests hashed more than once");
  System.out.println("PASS 11 production memo controls; package/stat identity and live guard boundaries modeled, real SHA-256/input reads/concurrency. No Android execution or hardware speed claim.");
 }
}
'@
$harness=Join-Path $OutputRoot 'InstalledApkDigestHost.java';[IO.File]::WriteAllText($harness,$test,[Text.UTF8Encoding]::new($false))
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
& $javac --release 8 -Xlint:all -Werror -d $OutputRoot $memo $harness *> (Join-Path $OutputRoot 'host-compile.log');if($LASTEXITCODE){throw 'Production memo host compile failed'}
& $java -cp $OutputRoot io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.InstalledApkDigestHost *> (Join-Path $OutputRoot 'host-test.log');if($LASTEXITCODE){throw 'Memo host controls failed'}
& $javac --release 8 -Xlint:all -Werror -cp $AndroidJar -d $OutputRoot $memo $adapter *> (Join-Path $OutputRoot 'api34-compile.log');if($LASTEXITCODE){throw 'Actual Android adapter API34 compile failed'}
$adapterText=[IO.File]::ReadAllText($adapter);$begin=$adapterText.IndexOf('    private static void requireDescriptor(')
if($begin -lt 0 -or -not $adapterText.TrimEnd().EndsWith('}')){throw 'Actual adapter identity body unavailable'}
$identityBody=$adapterText.Substring($begin);$identityBody=$identityBody.Substring(0,$identityBody.LastIndexOf('}'))
$identityHeader=@'
import java.util.Arrays;
public final class InstalledApkIdentityHost {
 static final class Timespec { long tv_sec=10,tv_nsec=20; }
 static final class StructStat {
  long st_dev=1,st_ino=2,st_nlink=1,st_size=3;int st_mode=4,st_uid=5,st_gid=6;
  Timespec st_mtim=new Timespec(),st_ctim=new Timespec();
 }
'@
$identityTail=@'
 public static void main(String[] args)throws Exception {
  Object[] metadata={"package",1L,"version",2L,3L,4,"/source/base.apk"};
  Identity baseline=new Identity("/source/base.apk",metadata,new StructStat());
  if(!baseline.equals(new Identity("/source/base.apk",metadata.clone(),new StructStat())))throw new AssertionError("stable identity differs");
  int negatives=0;
  for(int field=0;field<metadata.length;field++){
   Object[] next=metadata.clone();next[field]="changed";
   if(baseline.equals(new Identity("/source/base.apk",next,new StructStat())))throw new AssertionError("install field ignored");negatives++;
  }
  if(baseline.equals(new Identity("/changed/base.apk",metadata,new StructStat())))throw new AssertionError("path ignored");negatives++;
  String[] names={"st_dev","st_ino","st_mode","st_uid","st_gid","st_nlink","st_size"};
  for(String name:names){StructStat changed=new StructStat();java.lang.reflect.Field field=StructStat.class.getDeclaredField(name);
   if(field.getType()==int.class)field.setInt(changed,99);else field.setLong(changed,99);
   if(baseline.equals(new Identity("/source/base.apk",metadata,changed)))throw new AssertionError("file field ignored");
   boolean denied=false;try{requireDescriptor(baseline,changed);}catch(IllegalStateException expected){denied=true;}
   if(!denied)throw new AssertionError("descriptor change accepted");negatives++;
  }
  for(int field=0;field<4;field++){StructStat changed=new StructStat();
   if(field==0)changed.st_mtim.tv_sec++;if(field==1)changed.st_mtim.tv_nsec++;
   if(field==2)changed.st_ctim.tv_sec++;if(field==3)changed.st_ctim.tv_nsec++;
   if(baseline.equals(new Identity("/source/base.apk",metadata,changed)))throw new AssertionError("timestamp field ignored");negatives++;
  }
  requireDescriptor(baseline,new StructStat());
  System.out.println("PASS production identity body: stable key/descriptor + "+negatives+" changed metadata/path/stat negatives; Android structs modeled.");
 }
}
'@
$identityHarness=Join-Path $OutputRoot 'InstalledApkIdentityHost.java'
[IO.File]::WriteAllText($identityHarness,$identityHeader+"`n"+$identityBody+"`n"+$identityTail,[Text.UTF8Encoding]::new($false))
& $javac --release 8 -Xlint:all -Werror -d $OutputRoot $identityHarness *> (Join-Path $OutputRoot 'identity-compile.log');if($LASTEXITCODE){throw 'Actual identity body host compile failed'}
& $java -cp $OutputRoot InstalledApkIdentityHost *> (Join-Path $OutputRoot 'identity-test.log');if($LASTEXITCODE){throw 'Actual identity field controls failed'}
$hostText=[IO.File]::ReadAllText((Join-Path $dir 'EmbeddedDuplexProcessHost.java'))
if($hostText -notmatch 'String apk = installedApkDigest.read\(applicationContext\);' -or $hostText -match 'FileInputStream\(applicationContext.getApplicationInfo\(\).sourceDir\)'){throw 'Process host memo integration missing'}
@{schema='rusty.quest.installed_apk_digest_host_controls.v1';memo_controls_passed=11;identity_changed_fields_rejected=19;identity_stable_key_and_descriptor_passed=$true;android_api34_typecheck=$true;scope='Actual production memo and extracted identity body, modeled package/stat/live boundaries; API34 adapter compile only; no Android/JVM APK/device or end-to-end performance qualification.';sources=@($memo,$adapter)|ForEach-Object{@{path=$_;sha256=(Get-FileHash $_).Hash.ToLowerInvariant()}}}|ConvertTo-Json -Depth 8|Set-Content -Encoding utf8NoBOM (Join-Path $OutputRoot 'RESULT.json')
Get-Content (Join-Path $OutputRoot 'host-test.log')
Get-Content (Join-Path $OutputRoot 'identity-test.log')
