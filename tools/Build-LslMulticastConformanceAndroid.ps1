param([string]$AndroidHome=$env:ANDROID_HOME,[string]$JavaHome=$env:JAVA_HOME,[string]$OutDir="",[string]$Keystore="")
$ErrorActionPreference="Stop"
function Latest([string]$parent){$item=Get-ChildItem -LiteralPath $parent -Directory|Sort-Object Name -Descending|Select-Object -First 1;if($null-eq$item){throw "No tool directory under $parent"};$item.FullName}
function Checked([string]$name,[string]$file,[string[]]$arguments){& $file @arguments;if($LASTEXITCODE-ne0){throw "$name failed with exit code $LASTEXITCODE"}}
if([string]::IsNullOrWhiteSpace($AndroidHome)){throw "ANDROID_HOME or -AndroidHome is required."}
if([string]::IsNullOrWhiteSpace($JavaHome)){throw "JAVA_HOME or -JavaHome is required."}
$repo=(Resolve-Path(Join-Path $PSScriptRoot "..")).Path;$app=Join-Path $repo "apps\lsl-multicast-conformance-android";$target=Join-Path $repo "target"
if([string]::IsNullOrWhiteSpace($OutDir)){$OutDir=Join-Path $target "lslc-004g-multicast-conformance"}
$fullOut=[IO.Path]::GetFullPath($OutDir);$fullTarget=[IO.Path]::GetFullPath($target).TrimEnd("\")
if(-not$fullOut.StartsWith($fullTarget+"\",[StringComparison]::OrdinalIgnoreCase)){throw "OutDir must be under target."}
if(Test-Path $fullOut){Remove-Item -LiteralPath $fullOut -Recurse -Force}
$classes=Join-Path $fullOut "classes";$dex=Join-Path $fullOut "dex";New-Item -ItemType Directory -Force $classes,$dex|Out-Null
$bt=Latest(Join-Path $AndroidHome "build-tools");$platform=Latest(Join-Path $AndroidHome "platforms")
$androidJar=Join-Path $platform "android.jar";$javac=Join-Path $JavaHome "bin\javac.exe";$jar=Join-Path $JavaHome "bin\jar.exe";$aapt2=Join-Path $bt "aapt2.exe";$d8=Join-Path $bt "d8.bat";$zipalign=Join-Path $bt "zipalign.exe";$signer=Join-Path $bt "apksigner.bat";$keytool=Join-Path $JavaHome "bin\keytool.exe"
foreach($tool in @($androidJar,$javac,$jar,$aapt2,$d8,$zipalign,$signer,$keytool)){if(-not(Test-Path $tool)){throw "Missing tool: $tool"}}
$sources=Get-ChildItem(Join-Path $app "src\main\java") -Recurse -Filter *.java|ForEach-Object FullName;$rsp=Join-Path $fullOut "sources.rsp";$sources|Set-Content -Encoding ASCII $rsp
Checked "javac" $javac @("-encoding","UTF-8","-source","1.8","-target","1.8","-bootclasspath",$androidJar,"-d",$classes,"@$rsp")
$classesJar=Join-Path $fullOut "classes.jar";Checked "jar" $jar @("cf",$classesJar,"-C",$classes,".");Checked "d8" $d8 @("--lib",$androidJar,"--output",$dex,$classesJar)
$unsigned=Join-Path $fullOut "unsigned.apk";$unaligned=Join-Path $fullOut "unaligned.apk";$aligned=Join-Path $fullOut "aligned.apk";$apk=Join-Path $fullOut "rusty-lsl-multicast-conformance.apk"
Checked "aapt2" $aapt2 @("link","-o",$unsigned,"--manifest",(Join-Path $app "AndroidManifest.xml"),"-I",$androidJar,"--min-sdk-version","29","--target-sdk-version","34","--version-code","1","--version-name","0.1.0")
Copy-Item $unsigned $unaligned;Checked "dex package" $jar @("uf",$unaligned,"-C",$dex,"classes.dex");Checked "zipalign" $zipalign @("-f","4",$unaligned,$aligned)
if([string]::IsNullOrWhiteSpace($Keystore)){$Keystore=Join-Path $target "lslc-004g-debug.keystore"}
if(-not(Test-Path $Keystore)){Checked "keytool" $keytool @("-genkeypair","-keystore",$Keystore,"-storepass","android","-keypass","android","-alias","androiddebugkey","-keyalg","RSA","-keysize","2048","-validity","10000","-dname","CN=Rusty LSL Quest Conformance,O=Rusty Quest,C=US")}
Checked "sign" $signer @("sign","--ks",$Keystore,"--ks-pass","pass:android","--key-pass","pass:android","--out",$apk,$aligned);Checked "verify" $signer @("verify","--verbose",$apk)
$hash=(Get-FileHash -Algorithm SHA256 $apk).Hash.ToLowerInvariant();$manifest=[ordered]@{schema="rusty.quest.lsl_multicast_conformance_build.v1";source_head=(git -C $repo rev-parse HEAD);source_clean=([string]::IsNullOrWhiteSpace((git -C $repo status --short)));package="io.github.mesmerprism.rustyquest.lslmulticastconformance";activity=".MulticastConformanceActivity";apk_sha256=$hash;target_sdk=34;min_sdk=29}
$manifest|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8(Join-Path $fullOut "build-manifest.json");$apk
