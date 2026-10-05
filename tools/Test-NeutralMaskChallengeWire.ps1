param(
 [Parameter(Mandatory)][string]$AndroidJsonSourceDirectory,
 [Parameter(Mandatory)][string]$CargoLockPath,
 [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$CargoLockSha256,
 [Parameter(Mandatory)][string]$OutDir
)
Set-StrictMode -Version Latest;$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(Test-Path -LiteralPath $OutDir){throw 'Create-new host evidence directory required'}
if((Get-FileHash -LiteralPath $CargoLockPath).Hash.ToLowerInvariant()-cne$CargoLockSha256){throw 'Host serde dependency lock changed'}
$sourcePins=@{
 'JSON'='a2cc5e4bf64bfe3edd73c6a14409f3200640454051d9947ee79f16a2396a19b1'
 'JSONArray'='24f10dbe57a9228b8aec8a891355c26285e7fc4e070a2f826a080300e3ca4180'
 'JSONObject'='b7770e43fdad8415e8f5c37a7dc06a7c1ffb3a16c557887ded1479f3116c868f'
 'JSONTokener'='744c9ffb50c2472d7269c8d64a7d8331ea1a1691fb6536619ac9590e9ebbbd4b'
 'JSONStringer'='8772ac80610cd62e906330152b09864c471a0921f199f4b9d670d98050e3d86c'
 'JSONException'='26e10aae9c209dabbc8f15b4156fe9fdc87f5d9d2cb2d2f078b475a61ba0df7d'
}
foreach($name in $sourcePins.Keys){if((Get-FileHash (Join-Path $AndroidJsonSourceDirectory "$name.java")).Hash.ToLowerInvariant()-cne$sourcePins[$name]){throw 'Primary Android JSON source changed'}}
$null=New-Item -ItemType Directory -Path $OutDir
$out=[IO.Path]::GetFullPath($OutDir);$utf8=[Text.UTF8Encoding]::new($false)
function Write-NeutralFixture($p,$s){[IO.File]::WriteAllText((Join-Path $out $p),$s,$utf8)}
function Run($exe,[string[]]$Arguments,$name){$text=&$exe @Arguments 2>&1;$code=$LASTEXITCODE;Write-NeutralFixture "$name.log" ($text-join"`n");if($code-ne0){throw "Host $name failed; retained log"};return ($text-join"`n")}
# Compile exact production modules, rather than copying the serializer into a model.
$null=New-Item -ItemType Directory -Path "$out/src"
Write-NeutralFixture 'Cargo.toml' "[package]`nname=`"mask_native_state`"`nversion=`"0.0.0`"`nedition=`"2021`"`n[dependencies]`nserde_json=`"1`"`n[workspace]`n"
[IO.File]::WriteAllBytes("$out/Cargo.lock",[IO.File]::ReadAllBytes($CargoLockPath))
$lib="#![allow(dead_code)]`npub(crate)const PROJECTION_COMPOSITION_READBACK_CAPTURE:u32=0x8000;`n"
foreach($module in @('stereo_input_set','spatial_stereo_dropouts','stereo_bank_mask_v1','spatial_stereo_qualification','spatial_sdk_depth_handoff','camera_hwb_projection_readback')){
 $path=(Join-Path $root "apps/spatial-camera-panel-android/native-receipt/src/$module.rs").Replace('\','/')
 $lib+="#[path=`"$path`"]mod $module;`n"
}
Write-NeutralFixture 'src/lib.rs' $lib
$cargo=(Get-Command cargo -CommandType Application).Source
$native=Run $cargo @('test','--manifest-path',"$out/Cargo.toml",'--offline','--locked','--','--nocapture','--test-threads=1') 'native'
$wire=@($native-split"`r?`n"|Where-Object {$_-match'MASK_CHALLENGE_WIRE_CASE '})
if($wire.Count-ne7){throw 'Actual production wire fixtures missing'}
Write-NeutralFixture 'wire-cases.jsonl' (($wire|ForEach-Object {$_.Substring($_.IndexOf('MASK_CHALLENGE_WIRE_CASE ')+'MASK_CHALLENGE_WIRE_CASE '.Length)})-join"`n")
# Android14 libcore JSON at immutable 4ce63484144d6e245ad55e7a0027ea075cbd804c.
# Annotation-only host adapters do not alter any JSON implementation method.
$null=New-Item -ItemType Directory -Path "$out/android", "$out/classes"
Write-NeutralFixture 'android/UnsupportedAppUsage.java' 'package android.compat.annotation; public @interface UnsupportedAppUsage {}'
Write-NeutralFixture 'android/SystemApi.java' 'package android.annotation; public @interface SystemApi { enum Client { MODULE_LIBRARIES } Client client(); }'
Write-NeutralFixture 'android/NonNull.java' 'package libcore.util; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface NonNull {}'
Write-NeutralFixture 'android/Nullable.java' 'package libcore.util; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface Nullable {}'
Write-NeutralFixture 'android/MaskChallengeWireMain.java' @'
import org.json.*;import java.nio.file.*;import java.util.*;
public class MaskChallengeWireMain {
 static boolean same(JSONArray observed,long[] words)throws Exception{return observed.length()==2&&observed.getLong(0)==words[0]&&observed.getLong(1)==words[1];}
 static void require(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  int cases=0;
  for(String line:Files.readAllLines(Paths.get(args[0]))) {
   JSONObject fixture=new JSONObject(line);JSONArray expected=fixture.getJSONArray("expected");
   long[] words={expected.getLong(0),expected.getLong(1)};
   JSONArray observed=fixture.getJSONObject("report").getJSONArray("challenge_words");
   require(same(observed,words));cases++;
   require(!same(observed,new long[]{words[0]^1,words[1]}));cases++;
   require(!same(observed,new long[]{words[0],words[1]^1}));cases++;
   String unsigned="["+Long.toUnsignedString(words[0])+","+Long.toUnsignedString(words[1])+"]";
   require(same(new JSONArray(unsigned),words)==(words[0]>=0&&words[1]>=0));cases++;
   for(int index=0;index<2;index++)if(words[index]<0){String mixed="["+(index==0?Long.toUnsignedString(words[0]):Long.toString(words[0]))+","+(index==1?Long.toUnsignedString(words[1]):Long.toString(words[1]))+"]";require(!same(new JSONArray(mixed),words));cases++;}
   if(words[0]!=words[1]){require(!same(new JSONArray("["+words[1]+","+words[0]+"]"),words));cases++;}
  }
  long low=Long.parseUnsignedLong("d946dd73d62a05cc",16);
  require(low==-2790299429525387828L);
  require(new JSONArray("["+Long.toUnsignedString(low)+"]").getLong(0)==Long.MAX_VALUE);cases++;
  System.out.println("PASS actual Android14 JSON challenge cases="+cases);
 }
}
'@
$json=@('JSON','JSONArray','JSONObject','JSONTokener','JSONStringer','JSONException')|ForEach-Object {Join-Path $AndroidJsonSourceDirectory "$_.java"}
$javac=(Get-Command javac -CommandType Application).Source;$java=(Get-Command java -CommandType Application).Source
$null=Run $javac (@('-encoding','UTF-8','-d',"$out/classes")+$json+@(Get-ChildItem "$out/android/*.java"|ForEach-Object FullName)) 'javac'
$javaResult=Run $java @('-cp',"$out/classes",'MaskChallengeWireMain',"$out/wire-cases.jsonl") 'android-json'
if($javaResult-notmatch'PASS actual Android14 JSON challenge cases='){throw 'Android wire proof marker missing'}
[ordered]@{status='passed';android_json_commit='4ce63484144d6e245ad55e7a0027ea075cbd804c';android_json_sources=@($json|ForEach-Object {@{path=$_;sha256=(Get-FileHash $_).Hash.ToLowerInvariant()}});native_fixture_count=7;java_result=$javaResult;device_calls=0;apk_built=$false}|ConvertTo-Json -Depth 6
