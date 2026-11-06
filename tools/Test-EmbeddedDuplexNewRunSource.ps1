[CmdletBinding()]
param(
 [string]$RepoRoot=(Join-Path $PSScriptRoot '..'), [string]$JavaHome=$env:JAVA_HOME,
 [string]$AndroidHome=$env:ANDROID_HOME, [string]$GradlePath=$env:RUSTY_QUEST_GRADLE_PATH,
 [string]$HostJsonJar=$env:RUSTY_QUEST_HOST_JSON_JAR,
 [string]$PolicyVectorPath=$env:RUSTY_QUEST_ORDINARY_START_POLICY_VECTOR,
 [string]$OutputRoot
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
if(-not $JavaHome -or -not $AndroidHome -or -not $GradlePath -or -not $PolicyVectorPath){throw 'Select JDK17, Android SDK, Gradle and retained public owning-factory policy vector.'}
if(-not $HostJsonJar){$HostJsonJar=@(Get-ChildItem -LiteralPath (Join-Path $HOME '.gradle/caches/modules-2/files-2.1/org.json/json/20240303') -Filter json-20240303.jar -File -Recurse)[0].FullName}
if(-not $OutputRoot){$OutputRoot=Join-Path $RepoRoot ('target/new-run-source-'+[guid]::NewGuid().ToString('N'))}
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if(-not $OutputRoot.StartsWith([IO.Path]::GetFullPath((Join-Path $RepoRoot target))+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $OutputRoot)){throw 'Create-new output beneath ignored repository target required.'}
[void](New-Item -ItemType Directory -Path $OutputRoot)
function Pin([string]$Path){[ordered]@{path=[IO.Path]::GetFullPath($Path);sha256=(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}}
function Save([string]$Name,$Value){$Value|ConvertTo-Json -Depth 40|Set-Content -LiteralPath (Join-Path $OutputRoot $Name) -Encoding utf8NoBOM}
$checks=[Collections.Generic.List[object]]::new()
function Run([string]$Name,[string]$Exe,[string[]]$Arguments){
 & $Exe @Arguments *> (Join-Path $OutputRoot "$Name.log")
 $exit=$LASTEXITCODE;$checks.Add([ordered]@{name=$Name;exit=$exit;arguments=$Arguments;log=Pin (Join-Path $OutputRoot "$Name.log")})
 Save checks.json ($checks.ToArray())
 if($exit -ne 0){Get-Content -LiteralPath (Join-Path $OutputRoot "$Name.log") -Tail 60;throw "NewRun source check failed: $Name"}
 Write-Output "PASS $Name"
}
function Sources{foreach($path in @(& git -c core.quotepath=false ls-files --cached --others --exclude-standard)|Sort-Object -Unique){if(Test-Path -LiteralPath $path -PathType Leaf){[ordered]@{path=$path;sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}}}}
$names=@('PATH','JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME','GLSLC','CARGO_TARGET_DIR','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_MEDIA_POLICY','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_SETUP_POLICY','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_POLICY_SHA256','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64')
$old=@{};foreach($name in $names){$old[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Env([string]$Name,[AllowNull()][string]$Value){[Environment]::SetEnvironmentVariable($Name,$Value,'Process')}
Push-Location $RepoRoot
try{
 $before=@(Sources);Save source-inputs.json $before
 # Existing finite/ongoing-fresh app callers and targeted JVM suites, with current compiled helpers.
 Run app-source pwsh @('-NoProfile','-File','tools/Test-EmbeddedDuplexOrdinaryStartSource.ps1','-RepoRoot',$RepoRoot,'-JavaHome',$JavaHome,'-AndroidHome',$AndroidHome,'-GradlePath',$GradlePath,'-HostJsonJar',$HostJsonJar,'-PolicyVectorPath',$PolicyVectorPath,'-OutputRoot',(Join-Path $OutputRoot app-source))
 $fresh=Join-Path $OutputRoot 'app-source/android-fresh'
 $support=@((Join-Path $fresh 'app/tmp/kotlin-classes/debug'),(Join-Path $fresh 'app/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),(Join-Path $fresh 'media-stream-android/classes/java/main'),(Join-Path $fresh 'spatial-sdk-shared/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar'),(Join-Path $AndroidHome 'platforms/android-34/android.jar')) -join [IO.Path]::PathSeparator
 Run new-run-host python @('tools/checks/Test-EmbeddedDuplexNewRunHost.py','--root',$RepoRoot,'--output',(Join-Path $OutputRoot host),'--java-home',$JavaHome,'--json-jar',$HostJsonJar,'--support-classpath',$support,'--native-controls')
 Run app-fence pwsh @('-NoProfile','-File','tools/checks/Test-EmbeddedDuplexProcessFenceHost.ps1','-RepoRoot',$RepoRoot,'-JavaHome',$JavaHome,'-HostJsonJar',$HostJsonJar,'-AndroidJar',(Join-Path $AndroidHome 'platforms/android-34/android.jar'),'-OutputRoot',(Join-Path $OutputRoot app-fence))
 Env JAVA_HOME $JavaHome;Env PATH ((Join-Path $JavaHome bin)+[IO.Path]::PathSeparator+$old.PATH);Env ANDROID_HOME $AndroidHome
 Env ANDROID_NDK_HOME (Join-Path $AndroidHome 'ndk/27.2.12479018');Env GLSLC (Join-Path $env:ANDROID_NDK_HOME 'shader-tools/windows-x86_64/glslc.exe')
 Env CARGO_TARGET_DIR (Join-Path $OutputRoot native-target)
 $vector=Get-Content -Raw -LiteralPath $PolicyVectorPath|ConvertFrom-Json -Depth 64 -DateKind String
 $selection=[Text.UTF8Encoding]::new($false).GetBytes($vector.enrollment_selection_canonical_json)
 $rawSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($selection)).ToLowerInvariant()
 foreach($mode in @('finite','fresh')){
  $selected=$mode -eq 'fresh'
  Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_MEDIA_POLICY $(if($selected){'ongoing-v2'}else{'finite'})
  Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_SETUP_POLICY $(if($selected){'source-bound-v2'}else{'none'})
  Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_POLICY_SHA256 $(if($selected){$vector.projection.policy_sha256}else{''})
  Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256 $(if($selected){$vector.projection.enrollment_selection_sha256}else{''})
  Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256 $(if($selected){$rawSha}else{''})
  Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64 $(if($selected){[Convert]::ToBase64String($selection)}else{''})
  Run "native-$mode" cargo @('check','--locked','--tests','--target','aarch64-linux-android','-p','spatial-camera-panel-native-receipt')
 }
 Run native-host cargo @('test','--locked','-p','spatial-camera-panel-native-receipt','--lib')
 Run format cargo @('fmt','--all','--check')
 Run whitespace git @('diff','--check')
 $after=@(Sources);Save source-after.json $after
 if(($before|ConvertTo-Json -Depth 8 -Compress) -cne ($after|ConvertTo-Json -Depth 8 -Compress)){throw 'Source changed during qualification; outputs are not one candidate observation.'}
 Save RESULT.json ([ordered]@{schema='rusty.quest.new_run_source_qualification.v1';status='passed';head=(& git rev-parse HEAD).Trim();source_inputs=Pin (Join-Path $OutputRoot source-inputs.json);checks=Pin (Join-Path $OutputRoot checks.json);current_compiled_support=$support;selections=@('finite/none','ongoing-v2/source-bound-v2');limits=@('Host Android/Native physical leaves are explicitly modeled where declared.','Actual full Android Java/Kotlin/JNI Rust source compilation; no APK/link/device/media/hardware/remote closure or installed adoption.','Persistent fixture file bytes checked; actual private enrollment byte preservation is a downstream adoption check.')})
 Write-Output "NewRun source qualification: $OutputRoot"
}finally{foreach($name in $names){Env $name $old[$name]};Pop-Location}
