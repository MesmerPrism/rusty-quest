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
$target=Join-Path $repo "target";if([string]::IsNullOrWhiteSpace($OutDir)){$OutDir=Join-Path $target "lslc-005l-rust-float32-loopback"}
$fullOut=[IO.Path]::GetFullPath($OutDir);$fullTarget=[IO.Path]::GetFullPath($target).TrimEnd("\")
if(-not$fullOut.StartsWith($fullTarget+"\",[StringComparison]::OrdinalIgnoreCase)){throw "OutDir must be under target."}
if(Test-Path $fullOut){Remove-Item -LiteralPath $fullOut -Recurse -Force}
$app=Join-Path $repo "apps\lsl-rust-float32-loopback-android";$classes=Join-Path $fullOut "classes";$dex=Join-Path $fullOut "dex";$native=Join-Path $fullOut "native"
New-Item -ItemType Directory -Force $classes,$dex,(Join-Path $native "src")|Out-Null
Copy-Item -LiteralPath (Join-Path $app "native\src\lib.rs") -Destination (Join-Path $native "src\lib.rs")
$lslToml=($lsl -replace '\\','/')+"/crates/rusty-lsl"
@"
[package]
name = "rusty-lsl-float32-loopback"
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
$so=Join-Path $cargoTarget "aarch64-linux-android\release\rusty_lsl_float32_loopback.dll"
if(-not(Test-Path $so)){$so=Join-Path $cargoTarget "aarch64-linux-android\release\librusty_lsl_float32_loopback.so"}
if(-not(Test-Path $so)){throw "Missing built native library"}
$bt=Latest(Join-Path $AndroidHome "build-tools");$platform=Latest(Join-Path $AndroidHome "platforms");$androidJar=Join-Path $platform "android.jar"
$javac=Join-Path $JavaHome "bin\javac.exe";$jar=Join-Path $JavaHome "bin\jar.exe";$aapt2=Join-Path $bt "aapt2.exe";$d8=Join-Path $bt "d8.bat";$zipalign=Join-Path $bt "zipalign.exe";$signer=Join-Path $bt "apksigner.bat";$keytool=Join-Path $JavaHome "bin\keytool.exe"
$sources=Get-ChildItem(Join-Path $app "src\main\java") -Recurse -Filter *.java|ForEach-Object FullName;$rsp=Join-Path $fullOut "sources.rsp";$sources|Set-Content -Encoding ASCII $rsp
Checked "javac" $javac @("-encoding","UTF-8","-source","1.8","-target","1.8","-bootclasspath",$androidJar,"-d",$classes,"@$rsp")
$classesJar=Join-Path $fullOut "classes.jar";Checked "jar" $jar @("cf",$classesJar,"-C",$classes,".");Checked "d8" $d8 @("--lib",$androidJar,"--output",$dex,$classesJar)
$unsigned=Join-Path $fullOut "unsigned.apk";$unaligned=Join-Path $fullOut "unaligned.apk";$aligned=Join-Path $fullOut "aligned.apk";$apk=Join-Path $fullOut "rusty-lsl-float32-loopback.apk"
Checked "aapt2" $aapt2 @("link","-o",$unsigned,"--manifest",(Join-Path $app "AndroidManifest.xml"),"-I",$androidJar,"--min-sdk-version","29","--target-sdk-version","34","--version-code","1","--version-name","0.1.0")
Copy-Item $unsigned $unaligned;Checked "dex package" $jar @("uf",$unaligned,"-C",$dex,"classes.dex")
$libDir=Join-Path $fullOut "apk-lib\lib\arm64-v8a";New-Item -ItemType Directory -Force $libDir|Out-Null;Copy-Item $so (Join-Path $libDir "librusty_lsl_float32_loopback.so")
Checked "native package" $jar @("uf",$unaligned,"-C",(Join-Path $fullOut "apk-lib"),"lib")
Checked "zipalign" $zipalign @("-f","4",$unaligned,$aligned)
if([string]::IsNullOrWhiteSpace($Keystore)){$Keystore=Join-Path $target "lslc-005l-debug.keystore"}
if(-not(Test-Path $Keystore)){Checked "keytool" $keytool @("-genkeypair","-keystore",$Keystore,"-storepass","android","-keypass","android","-alias","androiddebugkey","-keyalg","RSA","-keysize","2048","-validity","10000","-dname","CN=Rusty LSL Float32 Loopback,O=Rusty Quest,C=US")}
Checked "sign" $signer @("sign","--ks",$Keystore,"--ks-pass","pass:android","--key-pass","pass:android","--out",$apk,$aligned);Checked "verify" $signer @("verify","--verbose",$apk)
$manifest=[ordered]@{schema="rusty.quest.lsl_rust_float32_loopback_build.v1";quest_source_head=$questHead;rusty_lsl_source_head=$lslHead;quest_source_clean=$true;rusty_lsl_source_clean=$true;rust_target="aarch64-linux-android";package="io.github.mesmerprism.rustyquest.lslrustfloat32loopback";activity=".Float32LoopbackActivity";apk_sha256=(Get-FileHash -Algorithm SHA256 $apk).Hash.ToLowerInvariant();native_sha256=(Get-FileHash -Algorithm SHA256 $so).Hash.ToLowerInvariant()}
$manifest|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8(Join-Path $fullOut "build-manifest.json");$apk
