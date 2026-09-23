param([string]$RepoRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path)
$ErrorActionPreference='Stop'
function Text([string]$relative){$p=Join-Path $RepoRoot $relative;if(!(Test-Path -LiteralPath $p)){throw "Missing: $relative"};Get-Content -Raw -LiteralPath $p}
function Need([string]$label,[string]$text,[string]$pattern){if($text -notmatch $pattern){throw "$label missing: $pattern"}}
function Ban([string]$label,[string]$text,[string]$pattern){if($text -match $pattern){throw "$label forbidden: $pattern"}}
$base='crates\rusty-quest-media-stream-android\android'
$manifest=Text "$base\library\src\main\AndroidManifest.xml"
$library=Text "$base\library\build.gradle"
$rootGradle=Text "$base\build.gradle"
$settings=Text "$base\settings.gradle"
$script=Text "$base\Build-MediaStreamAar.ps1"
[xml]$manifestXml=$manifest
if($manifestXml.SelectNodes('//uses-permission | //uses-permission-sdk-23 | //permission | //service | //activity | //activity-alias | //provider | //receiver').Count){throw 'AAR has host permission or component declarations.'}
$sdk=$manifestXml.SelectSingleNode('/manifest/uses-sdk')
if($null -eq $sdk -or $sdk.GetAttribute('minSdkVersion','http://schemas.android.com/apk/res/android') -cne '29'){throw 'AAR must declare minSdk29.'}
Need 'library source level' $library 'sourceCompatibility\s*=\s*JavaVersion\.VERSION_1_8'
Need 'library bytecode level' $library 'targetCompatibility\s*=\s*JavaVersion\.VERSION_1_8'
Need 'settings' $settings 'rusty-quest-media-stream-android'
Need 'build contract' $script '--offline|offline'
Ban 'build contract' $script 'Invoke-WebRequest|Invoke-RestMethod|Start-BitsTransfer|\bsdkmanager\b|\bwinget\b|\bchoco\b|\bcurl(?:\.exe)?\b'
$javaRoot=Join-Path $RepoRoot "$base\library\src\main\java"
$java=Get-ChildItem -LiteralPath $javaRoot -Recurse -Filter '*.java'|ForEach-Object{Get-Content -Raw $_.FullName}
foreach($name in 'AndroidMediaOwnerRegistry','PackagedAndroidMediaOwnerRegistry','MediaProductBinding','MediaOwnerAction','MediaProviderReadback','MediaRuntimeSnapshot','CancellationHandle','StereoFrameIdentity','StereoFrameLease','StereoFrameSource','StereoFrameSubscription'){if(-not($java -match "public\s+(?:final\s+)?(?:class|interface)\s+$name\b")){throw "Public class missing: $name"}}
if($java -match 'io\.github\.mesmerprism\.rustymanifold|io\.github\.mesmerprism\.rustyquest\.(spatial|native_renderer)'){throw 'Android media module imports downstream application runtime classes.'}
if(@(Get-ChildItem -LiteralPath (Join-Path $RepoRoot $base) -Recurse -Filter '*.so').Count){throw 'Android media module bundles native .so.'}
$rustManifest=Text 'crates\rusty-quest-media-stream-android\Cargo.toml'
Ban 'shared Rust library' $rustManifest 'cdylib|staticlib|\[\[bin\]\]'
Need 'shared Rust default features' $rustManifest '(?m)^default\s*=\s*\[\]'
$rustLibrary=Text 'crates\rusty-quest-media-stream-android\src\lib.rs'
Need 'test executor feature boundary' $rustLibrary '#\[cfg\(feature = "test-support"\)\]\s*pub struct DeterministicAndroidMediaOwnerExecutor'
$bridge=Text 'apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldRuntimeAuthorityBridge.java'
$jni=Text 'apps\manifold-broker-android\native\src\admission_jni.rs'
Need 'broker bridge' $bridge 'nativeInstallAndroidMediaOwnerRegistry\(\s*AndroidMediaOwnerRegistry'
Need 'broker JNI' $jni 'nativeInstallAndroidMediaOwnerRegistry'
Ban 'broker JNI' $jni 'nativeInstallAndroidMediaOwnerRegistry[\s\S]{0,800}(readback|capability).*jstring'
[ordered]@{schema='rusty.quest.media_stream_android.static.v1';status='pass';runtime_or_device_proof=$false;manifest_component_free=$true;offline_contract=$true}|ConvertTo-Json
