param(
    [Parameter(Mandatory=$true)][string]$RustyLslRoot,
    [string]$AndroidHome=$env:ANDROID_HOME,
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$OutDir="",
    [string]$Keystore=""
)
$ErrorActionPreference="Stop"
function Latest([string]$parent){$item=Get-ChildItem -LiteralPath $parent -Directory|Sort-Object Name -Descending|Select-Object -First 1;if($null-eq$item){throw "No tool directory under $parent"};$item.FullName}
function Checked([string]$name,[string]$file,[string[]]$arguments){& $file @arguments;if($LASTEXITCODE-ne0){throw "$name failed with exit code $LASTEXITCODE"}}
if([string]::IsNullOrWhiteSpace($AndroidHome)){throw "ANDROID_HOME or -AndroidHome is required."}
if([string]::IsNullOrWhiteSpace($JavaHome)){throw "JAVA_HOME or -JavaHome is required."}
$repo=(Resolve-Path(Join-Path $PSScriptRoot "..")).Path;$lsl=(Resolve-Path $RustyLslRoot).Path
foreach($item in @(@{Name="Rusty Quest";Path=$repo},@{Name="Rusty LSL";Path=$lsl})){
    if(-not[string]::IsNullOrWhiteSpace((git -C $item.Path status --short))){throw "$($item.Name) source must be clean"}
}
$questHead=(git -C $repo rev-parse HEAD).Trim();$lslHead=(git -C $lsl rev-parse HEAD).Trim()
$target=Join-Path $repo "target";if([string]::IsNullOrWhiteSpace($OutDir)){throw "-OutDir is required and must equal the claimed content-addressed output."}
$fullOut=[IO.Path]::GetFullPath($OutDir);$fullTarget=[IO.Path]::GetFullPath($target).TrimEnd("\")
if(-not$fullOut.StartsWith($fullTarget+"\",[StringComparison]::OrdinalIgnoreCase)){throw "OutDir must be under target."}
if(Test-Path $fullOut){throw "OutDir already exists; refusing destructive replacement: $fullOut"}
$app=Join-Path $repo "apps\lsl-rust-float32-lan-outlet-android";$classes=Join-Path $fullOut "classes";$dex=Join-Path $fullOut "dex";$native=Join-Path $fullOut "native"
New-Item -ItemType Directory -Force $classes,$dex,(Join-Path $native "src")|Out-Null
Copy-Item -LiteralPath (Join-Path $app "native\src\lib.rs") -Destination (Join-Path $native "src\lib.rs")
$lslToml=($lsl -replace '\\','/')+"/crates/rusty-lsl"
@"
[package]
name = "rusty-lsl-float32-lan-outlet"
version = "0.1.0"
edition = "2024"
[workspace]
[lib]
crate-type = ["cdylib"]
[dependencies]
rusty-lsl = { path = "$lslToml" }
"@ | Set-Content -Encoding UTF8 (Join-Path $native "Cargo.toml")
$ndk=Latest(Join-Path $AndroidHome "ndk");$linker=Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd"
if(-not(Test-Path $linker)){throw "Missing Android linker: $linker"}
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$linker;$cargoTarget=Join-Path $fullOut "cargo-target"
Checked "cargo lock" "cargo" @("generate-lockfile","--manifest-path",(Join-Path $native "Cargo.toml"))
Checked "cargo android build" "cargo" @("build","--manifest-path",(Join-Path $native "Cargo.toml"),"--target","aarch64-linux-android","--release","--target-dir",$cargoTarget,"--locked")
$so=Join-Path $cargoTarget "aarch64-linux-android\release\rusty_lsl_float32_lan_outlet.dll"
if(-not(Test-Path $so)){$so=Join-Path $cargoTarget "aarch64-linux-android\release\librusty_lsl_float32_lan_outlet.so"}
if(-not(Test-Path $so)){throw "Missing built native library"}
$bt=Latest(Join-Path $AndroidHome "build-tools");$platform=Latest(Join-Path $AndroidHome "platforms");$androidJar=Join-Path $platform "android.jar"
$javac=Join-Path $JavaHome "bin\javac.exe";$jar=Join-Path $JavaHome "bin\jar.exe";$aapt2=Join-Path $bt "aapt2.exe";$d8=Join-Path $bt "d8.bat";$zipalign=Join-Path $bt "zipalign.exe";$signer=Join-Path $bt "apksigner.bat";$keytool=Join-Path $JavaHome "bin\keytool.exe"
$sources=Get-ChildItem(Join-Path $app "src\main\java") -Recurse -Filter *.java|ForEach-Object FullName;$rsp=Join-Path $fullOut "sources.rsp";$sources|Set-Content -Encoding ASCII $rsp
Checked "javac" $javac @("-encoding","UTF-8","-source","1.8","-target","1.8","-bootclasspath",$androidJar,"-d",$classes,"@$rsp")
$classesJar=Join-Path $fullOut "classes.jar";Checked "jar" $jar @("cf",$classesJar,"-C",$classes,".");Checked "d8" $d8 @("--lib",$androidJar,"--output",$dex,$classesJar)
$unsigned=Join-Path $fullOut "unsigned.apk";$unaligned=Join-Path $fullOut "unaligned.apk";$aligned=Join-Path $fullOut "aligned.apk";$apk=Join-Path $fullOut "rusty-lsl-float32-lan-outlet.apk"
Checked "aapt2" $aapt2 @("link","-o",$unsigned,"--manifest",(Join-Path $app "AndroidManifest.xml"),"-I",$androidJar,"--min-sdk-version","29","--target-sdk-version","34","--version-code","1","--version-name","0.1.0")
Copy-Item $unsigned $unaligned;Checked "dex package" $jar @("uf",$unaligned,"-C",$dex,"classes.dex")
$libDir=Join-Path $fullOut "apk-lib\lib\arm64-v8a";New-Item -ItemType Directory -Force $libDir|Out-Null;Copy-Item $so (Join-Path $libDir "librusty_lsl_float32_lan_outlet.so")
Checked "native package" $jar @("uf",$unaligned,"-C",(Join-Path $fullOut "apk-lib"),"lib")
Checked "zipalign" $zipalign @("-f","4",$unaligned,$aligned)
if([string]::IsNullOrWhiteSpace($Keystore)){$Keystore=Join-Path $target "p70-lan-outlet-debug.keystore"}
if(-not(Test-Path $Keystore)){Checked "keytool" $keytool @("-genkeypair","-keystore",$Keystore,"-storepass","android","-keypass","android","-alias","androiddebugkey","-keyalg","RSA","-keysize","2048","-validity","10000","-dname","CN=Rusty LSL Float32 LAN Outlet,O=Rusty Quest,C=US")}
Checked "sign" $signer @("sign","--ks",$Keystore,"--ks-pass","pass:android","--key-pass","pass:android","--out",$apk,$aligned);Checked "verify" $signer @("verify","--verbose",$apk)
$host=Join-Path $fullOut "host-runner";New-Item -ItemType Directory -Force (Join-Path $host "src")|Out-Null
@"
[package]
name = "p70-host-runner"
version = "0.1.0"
edition = "2021"
[workspace]
[dependencies]
rusty-lsl = { path = "$lslToml" }
"@|Set-Content -Encoding UTF8 (Join-Path $host "Cargo.toml")
@'
use rusty_lsl::*;
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::sync::atomic::AtomicBool;
use std::time::Duration;
fn main() {
 let quest:SocketAddr=std::env::args().nth(1).expect("quest endpoint").parse().unwrap();
 let route=UdpSocket::bind("0.0.0.0:0").unwrap(); route.connect(quest).unwrap();
 let host=route.local_addr().unwrap().ip(); assert!(!host.is_loopback());
 let reserve=UdpSocket::bind(SocketAddr::new(host,0)).unwrap(); let reply=reserve.local_addr().unwrap().port(); drop(reserve);
 let sels=[
  RuntimeActivationSelection::new(RuntimeModule::UdpDiscovery.id(),RuntimeModule::UdpDiscovery.effective_marker()),
  RuntimeActivationSelection::new(RuntimeModule::StreamHandshake.id(),RuntimeModule::StreamHandshake.effective_marker()),
  RuntimeActivationSelection::new(RuntimeModule::TimestampedFloat32Sample.id(),RuntimeModule::TimestampedFloat32Sample.effective_marker())];
 let admission=admit_runtime_activation(ACCEPTED_FEATURE_LOCK_FINGERPRINT,"p70-host-lan-inlet",&sels).unwrap();
 let da=UdpDiscoveryActivation::new(admission.capability(RuntimeModule::UdpDiscovery).unwrap()).unwrap();
 let ha=StreamHandshakeActivation::new(admission.capability(RuntimeModule::StreamHandshake).unwrap()).unwrap();
 let sa=TimestampedFloat32SampleActivation::new(admission.capability(RuntimeModule::TimestampedFloat32Sample).unwrap(),ha).unwrap();
 let ql=ShortInfoQueryWireLimits::new(256,1024).unwrap();
 let query=ShortInfoQueryWire::encode(&ShortInfoQuery::new("name='p70-quest-outlet'".into(),reply,70,ql).unwrap(),ql).unwrap();
 let envelope=ShortInfoResponseEnvelopeLimits::new(1,4096).unwrap();
 let observed=StreamInfoObservedAdmissionLimits::new(
  StreamDescriptorLimits::new(128,128,128,8).unwrap(),
  MetadataTreeLimits::new(4,32,128,256,256).unwrap(),
  StreamInfoVolatileFieldLimits::new(128,128,128).unwrap());
 let hl=StreamHandshakeLimits::new(1024,128,Duration::from_millis(20),Duration::from_secs(10)).unwrap();
 let identity=StreamHandshakeIdentity::new("70000000-2222-4333-8444-555555555570".into(),"quest".into(),"p70-quest-source".into(),"p70".into(),hl).unwrap();
 let done=run_typed_udp_discovery_float32_session_inlet(
  da,UdpDiscoveryConfig::new(SocketAddr::new(host,reply),quest,UdpDiscoveryLimits::new(4096,1,Duration::from_millis(20),Duration::from_secs(10)).unwrap(),envelope),
  &query,&AtomicBool::new(false),envelope,observed,"p70-quest-outlet",sa,&identity,hl,
  TimestampedFloat32SampleLimits::new(Duration::from_millis(20),Duration::from_secs(10)).unwrap(),
  TimestampedFloat32SessionLimits::new(1,1).unwrap(),1,1,&AtomicBool::new(false)).unwrap();
 let r=&done.report().records()[0];
 assert_eq!(r.raw_source_timestamp().value().to_bits(),0x4092_5220_0000_0070);
 assert_eq!(r.sample().values()[0].to_bits(),0x3fa0_0070);
 println!("{{\"schema\":\"rusty.lsl.p70.host_result.v1\",\"result\":\"pass\",\"discovery_count\":{},\"selected_index\":{},\"record_count\":1,\"channel_count\":1,\"timestamp_bits\":\"0x4092522000000070\",\"value_bits\":\"0x3fa00070\",\"terminal_cleanup\":true}}",done.discovery().responses().len(),done.response_index());
}
'@|Set-Content -Encoding UTF8 (Join-Path $host "src\main.rs")
Checked "host runner lock" "cargo" @("generate-lockfile","--manifest-path",(Join-Path $host "Cargo.toml"))
Checked "host runner build" "cargo" @("build","--release","--locked","--manifest-path",(Join-Path $host "Cargo.toml"),"--target-dir",(Join-Path $host "target"))
$hostRunner=Join-Path $host "target\release\p70-host-runner.exe";if(-not(Test-Path $hostRunner)){throw "Missing host runner"}
$questTree=(git -C $repo rev-parse 'HEAD^{tree}').Trim();$lslTree=(git -C $lsl rev-parse 'HEAD^{tree}').Trim()
$manifest=[ordered]@{schema="rusty.quest.lsl_rust_float32_lan_outlet_build.v1";quest_source_head=$questHead;quest_source_tree=$questTree;rusty_lsl_source_head=$lslHead;rusty_lsl_source_tree=$lslTree;quest_source_clean=$true;rusty_lsl_source_clean=$true;rust_target="aarch64-linux-android";package="io.github.mesmerprism.rustyquest.lslrustfloat32lanoutlet";activity=".Float32LanOutletActivity";property_manifest=@();staging_inputs=@();apk_sha256=(Get-FileHash -Algorithm SHA256 $apk).Hash.ToLowerInvariant();native_sha256=(Get-FileHash -Algorithm SHA256 $so).Hash.ToLowerInvariant();host_runner_sha256=(Get-FileHash -Algorithm SHA256 $hostRunner).Hash.ToLowerInvariant()}
$manifest|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8(Join-Path $fullOut "build-manifest.json")
$manifest|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8(Join-Path $fullOut "run-capsule.json");$apk

