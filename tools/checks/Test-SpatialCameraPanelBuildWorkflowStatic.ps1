param([string]$RepoRoot,[switch]$PrerequisiteCompatibilityOnly,
    [string]$PrerequisiteInputSpecPath='', [string]$PrerequisiteOutRoot='')

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path

function Test-SharedPrerequisiteCompatibility {
    $modulePath=Join-Path $repo 'tools/lib/SpatialCameraPanelBuildPrerequisites.psm1'
    if(-not(Test-Path -LiteralPath $modulePath -PathType Leaf)){throw 'Shared platform prerequisite module missing.'}
    $owner=Import-Module $modulePath -PassThru
    $builder=Join-Path $repo 'tools/Build-SpatialCameraPanelAndroid.ps1'
    foreach($name in @('CargoPath','RustupPath','RequireInstalledRustTarget','WaitPolicy')){
        if(-not(Get-Command $builder).Parameters.ContainsKey($name)){throw "Missing explicit build compatibility parameter: $name"}
    }
    $resolver=Join-Path $repo 'tools/Resolve-GradleTool.ps1'
    if(-not(Get-Command $resolver).Parameters.ContainsKey('WaitPolicy')){throw 'Actual Gradle resolver caller does not accept WaitPolicy.'}
    $tokens=$null;$errors=$null
    $resolverAst=[Management.Automation.Language.Parser]::ParseFile($resolver,[ref]$tokens,[ref]$errors)
    $waitParameter=@($resolverAst.ParamBlock.Parameters|Where-Object {$_.Name.VariablePath.UserPath-ceq'WaitPolicy'})
    if($errors.Count-or$waitParameter.Count-ne1-or$waitParameter[0].DefaultValue.Value-cne'Bounded'){throw 'Resolver default wait policy changed.'}
    $scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-prerequisite-compatibility-'+[guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $scratch)
    $priorPath=$env:PATH
    try{
        $hostApplication=Join-Path $PSHOME 'pwsh.exe'
        $selected=&$owner {param($p)Resolve-QuestSpatialApplication -Name pwsh -Path $p} $hostApplication
        if([IO.Path]::GetFullPath($selected.Source)-cne[IO.Path]::GetFullPath($hostApplication)){throw 'Explicit application identity was lost.'}
        foreach($invalid in @('relative.exe',(Join-Path $scratch 'absent.exe'))){
            $denied=$false;try{&$owner {param($p)Resolve-QuestSpatialApplication -Name cargo -Path $p} $invalid|Out-Null}catch{$denied=$_.Exception.Message-like'*path is invalid*'}
            if(-not$denied){throw 'Invalid explicit application path was accepted.'}
        }
        foreach($leaf in @('one','two')){[void](New-Item -ItemType Directory -Path (Join-Path $scratch $leaf));[IO.File]::WriteAllText((Join-Path $scratch "$leaf/quest-fixture-tool.cmd"),'@exit /b 0')}
        $env:PATH=(Join-Path $scratch 'one')+';'+(Join-Path $scratch 'two')
        $denied=$false;try{&$owner {Resolve-QuestSpatialApplication -Name quest-fixture-tool.cmd}|Out-Null}catch{$denied=$_.Exception.Message-like'*missing or ambiguous*'}
        if(-not$denied){throw 'Ambiguous application selection was accepted.'}
        if($null-ne(&$owner {Resolve-QuestSpatialApplication -Name absent-quest-fixture-tool -Optional})){throw 'Optional absent application was fabricated.'}
        $env:PATH=$priorPath
        $denied=$false;try{&$owner {param($p)Assert-QuestSpatialPrerequisiteEvidencePath -Path $p -ExcludedRoots @($p)} (Join-Path $scratch 'excluded')|Out-Null}catch{$denied=$_.Exception.Message-like'*overlaps*'}
        if(-not$denied-or(Test-Path -LiteralPath (Join-Path $scratch 'excluded'))){throw 'Prerequisite overlap was not refused before writes.'}

        # A real independent .NET thread owns and releases the mutex/task.
        # This is controlled fixture work, never a production timeout or kill.
        if(-not('QuestPrerequisiteWaitControl' -as [type])){Add-Type @'
using System;
using System.Threading;
using System.Threading.Tasks;
public sealed class QuestPrerequisiteWaitControl : IDisposable {
 public readonly string Name="Local\\QuestPrerequisiteFixture-"+Guid.NewGuid().ToString("N");
 public readonly ManualResetEventSlim Held=new ManualResetEventSlim(false), Release=new ManualResetEventSlim(false);
 readonly Thread owner;
 public QuestPrerequisiteWaitControl(){owner=new Thread(()=>{using(var mutex=new Mutex(false,Name)){mutex.WaitOne();Held.Set();Release.Wait();mutex.ReleaseMutex();}});owner.Start();Held.Wait();}
 public Task ReleaseAfterControl(){return Task.Run(()=>{Thread.Sleep(50);Release.Set();});}
 public static Task<string> CompleteAfterControl(){return Task.Run(()=>{Thread.Sleep(50);return "same-original-task";});}
 public void Dispose(){Release.Set();owner.Join();Held.Dispose();Release.Dispose();}
}
'@}
        $control=[QuestPrerequisiteWaitControl]::new();$mutex=[Threading.Mutex]::new($false,$control.Name)
        try{
            $abandoned=$false
            $bounded=&$owner {param($m,$a)Wait-QuestSpatialOperation -Mutex $m -WaitPolicy Bounded -Estimate ([TimeSpan]::FromMilliseconds(1)) -Abandoned $a} $mutex ([ref]$abandoned)
            if($bounded-or$abandoned){throw 'Bounded mutex did not report the actual unacquired result.'}
            $release=$control.ReleaseAfterControl()
            $retained=&$owner {param($m,$a)Wait-QuestSpatialOperation -Mutex $m -WaitPolicy Retained -Estimate ([TimeSpan]::FromMilliseconds(1)) -Abandoned $a} $mutex ([ref]$abandoned)
            if(-not$retained-or$abandoned-or-not$control.Release.IsSet){throw 'Retained mutex lost its same original controlled wait.'}
            $mutex.ReleaseMutex();$release.GetAwaiter().GetResult()
        }finally{$mutex.Dispose();$control.Dispose()}
        $task=[QuestPrerequisiteWaitControl]::CompleteAfterControl()
        $value=&$owner {param($t)Wait-QuestSpatialOperation -Task $t -WaitPolicy Retained -Estimate ([TimeSpan]::FromMilliseconds(1))} $task
        if($value-cne'same-original-task'-or-not$task.IsCompletedSuccessfully){throw 'Retained task did not return the actual original result.'}
        $fault=[Threading.Tasks.Task]::FromException([InvalidOperationException]::new('fixture-task-fault'))
        $denied=$false;try{&$owner {param($t)Wait-QuestSpatialOperation -Task $t -WaitPolicy Retained} $fault|Out-Null}catch{$denied=$_.Exception.Message-like'*fixture-task-fault*'}
        if(-not$denied){throw 'Retained task swallowed its original failure.'}
        $cancel=[Threading.CancellationTokenSource]::new();$cancel.Cancel()
        try{
            $task=[Threading.Tasks.Task]::FromCanceled($cancel.Token);$denied=$false
            try{&$owner {param($t)Wait-QuestSpatialOperation -Task $t -WaitPolicy Bounded} $task|Out-Null}catch{$denied=$_.Exception.Message-like'*canceled*'}
            if(-not$denied-or-not$task.IsCanceled){throw 'Bounded task lost actual cancellation.'}
        }finally{$cancel.Dispose()}
        # The actual module caller reaches the actual resolver parameter binder;
        # absent fixture cache must fail closed without resolving/downloading it.
        $fixtureRepo=Join-Path $scratch 'repo';[void](New-Item -ItemType Directory -Path (Join-Path $fixtureRepo 'tools'),(Join-Path $fixtureRepo 'config'))
        Copy-Item -LiteralPath $resolver -Destination (Join-Path $fixtureRepo 'tools/Resolve-GradleTool.ps1')
        [void](New-Item -ItemType Directory -Path (Join-Path $fixtureRepo 'tools/lib'))
        Copy-Item -LiteralPath $modulePath -Destination (Join-Path $fixtureRepo 'tools/lib/SpatialCameraPanelBuildPrerequisites.psm1')
        Copy-Item -LiteralPath (Join-Path $repo 'config/gradle-9.4.1-tool.json') -Destination (Join-Path $fixtureRepo 'config/gradle-9.4.1-tool.json')
        $denied=$false;try{&$owner {param($r)Get-VerifiedPinnedGradleBat -RepoRoot $r -WaitPolicy Retained} $fixtureRepo|Out-Null}catch{$denied=$_.Exception.Message-like'*cache*absent*'-or$_.Exception.Message-like'*cache*missing*'-or$_.Exception.Message-like'*not resolved*'}
        if(-not$denied-or(Test-Path -LiteralPath (Join-Path $fixtureRepo 'local-artifacts'))){throw 'Actual retained VerifyCache caller installed or accepted absent tools.'}
        'Shared prerequisite application selection, actual resolver refusal and controlled same-original wait checks passed'
    }finally{
        $env:PATH=$priorPath
        $absolute=[IO.Path]::GetFullPath($scratch);$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
        if([IO.Path]::GetDirectoryName($absolute)-ine$temp-or[IO.Path]::GetFileName($absolute)-cnotmatch'^quest-prerequisite-compatibility-[0-9a-f]{32}$'){throw 'Unsafe prerequisite fixture cleanup.'}
        Remove-Item -LiteralPath $absolute -Recurse -Force
    }
}
function Test-ActualPlatformPrerequisites {
    param([string]$InputSpecPath,[string]$OutRoot)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
if(Test-Path -LiteralPath $OutRoot){throw 'Create-new control evidence required.'}
$null=New-Item -ItemType Directory -Path $OutRoot
$prerequisiteOwner=Import-Module (Join-Path $repo 'tools/lib/SpatialCameraPanelBuildPrerequisites.psm1') -PassThru
# This spec names existing platform installations/shared Gradle only; it contains no product key.
$spec=Get-Content -LiteralPath $InputSpecPath -Raw|ConvertFrom-Json -AsHashtable
foreach($key in $spec.Keys){if($key-notin@('AndroidHome','JavaHome','NdkHome','NdkVersion','BuildToolsVersion','SharedGradleToolRoot')){throw 'Unexpected platform fixture field.'}}
$keytool=Join-Path $spec.JavaHome 'bin/keytool.exe';$fixture=Join-Path $OutRoot 'fixture.p12'
$parent=@{};Get-ChildItem Env:|ForEach-Object{$parent[$_.Name]=$_.Value}
$checks=[Collections.Generic.List[object]]::new()
try{
    # Fresh test-only signer; never product signing or APK evidence.
    $env:RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD='fixture-only-password'
    $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD='fixture-only-password'
    $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS='fixture'
    $null=& $keytool -genkeypair -storetype PKCS12 -keystore $fixture -storepass:env RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD -keypass:env RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD -alias fixture -dname 'CN=Local Fixture' -keyalg RSA -validity 1 2>&1
    if($LASTEXITCODE-ne0){throw 'Fixture-only signer creation failed.'}
    $cert=Join-Path $OutRoot 'fixture.der'
    $null=& $keytool -exportcert -keystore $fixture -storepass:env RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD -alias fixture -file $cert 2>&1
    if($LASTEXITCODE-ne0){throw 'Fixture-only certificate read failed.'}
    $expected=(Get-FileHash -LiteralPath $cert -Algorithm SHA256).Hash.ToLowerInvariant()
    function Check([string]$Name,[hashtable]$Changes,[string]$ExpectedReason=''){
        $a=@{};foreach($key in $spec.Keys){$a[$key]=$spec[$key]}
        $a.RepoRoot=$repo;$a.EvidenceDirectory=Join-Path $OutRoot $Name;$a.Keystore=$fixture
        $a.BuildCacheRoot=Join-Path $OutRoot 'compiler-cache'
        $a.ExpectedSignerSha256=$expected;$a.AppId='example.invalid.quest.fixture';$a.BuildMode='Candidate'
        foreach($key in $Changes.Keys){$a[$key]=$Changes[$key]}
        $denied=$false;$reason='';$result=$null
        try{$result=&$prerequisiteOwner {param($parameters) Get-QuestSpatialBuildPrerequisites @parameters} $a}catch{$denied=$true;$reason=$_.Exception.Message}
        if([bool]$ExpectedReason-ne$denied-or($ExpectedReason-and-not$reason.Contains($ExpectedReason))){throw "Unexpected shared prerequisite outcome: $Name ($reason)"}
        if(-not$denied-and($result.CertificateSha256-cne$expected-or$result.Evidence.compiler_invoked-or$result.Evidence.compiler_cache_written)){throw 'Prerequisite observation exceeded scope.'}
        if($ExpectedReason-like'Prerequisite evidence overlaps*'-and(Test-Path -LiteralPath $a.EvidenceDirectory)){throw 'Evidence overlap refusal created the denied directory.'}
        foreach($cacheLeaf in @('t','h','gu','gp','n','target')){if(Test-Path -LiteralPath (Join-Path $OutRoot $cacheLeaf)){throw 'Prerequisite observation created compiler/cache stage.'}}
        $checks.Add([ordered]@{name=$Name;passed=$true;denied=$denied;reason=$reason})
    }
    $nativePreference=$PSNativeCommandUseErrorActionPreference
    try{
        $PSNativeCommandUseErrorActionPreference=$true
        Check 'actual-platform-fixture-signer-positive' @{}
        if(-not$PSNativeCommandUseErrorActionPreference){throw 'Prerequisite observer changed caller native-command preference.'}
    }finally{$PSNativeCommandUseErrorActionPreference=$nativePreference}
    # Exit 2 alone is not readiness: the real tool's invalid option also exits 2.
    $zipalign=Join-Path $spec.AndroidHome ('build-tools/'+$spec.BuildToolsVersion+'/zipalign.exe')
    $badProbe=& {
        $PSNativeCommandUseErrorActionPreference=$false
        $text=@(& $zipalign -h 2>&1|ForEach-Object{[string]$_})-join"`n"
        [pscustomobject]@{text=$text;code=$LASTEXITCODE}
    }
    $badDenied=$false
    try{&$prerequisiteOwner {param($text) Assert-QuestSpatialZipalignUsage -Text $text} $badProbe.text}catch{$badDenied=$true}
    if($badProbe.code-ne2-or-not$badDenied){throw 'Actual zipalign invalid-option exit 2 was accepted.'}
    $checks.Add([ordered]@{name='actual-zipalign-exit2-invalid-option';passed=$true;denied=$true;reason='recognized usage required'})
    Check 'missing-sdk' @{AndroidHome=(Join-Path $OutRoot 'absent-sdk')} 'tool directory is absent'
    Check 'wrong-ndk-version' @{NdkVersion='0.0.0'} 'NDK differs'
    Check 'missing-signer' @{Keystore=(Join-Path $OutRoot 'absent.p12')} 'keystore not found'
    Check 'wrong-public-signer' @{ExpectedSignerSha256=('0'*64)} 'signer fingerprint mismatch'
    Check 'cache-evidence-overlap' @{EvidenceDirectory=(Join-Path $OutRoot 'compiler-cache/readiness')} 'Prerequisite evidence overlaps'
    Check 'declared-output-evidence-overlap' @{EvidenceDirectory=(Join-Path $OutRoot 'attempt/readiness');ExcludedRoots=@((Join-Path $OutRoot 'attempt'))} 'Prerequisite evidence overlaps'
    $record=[ordered]@{passed=$true;controls=$checks.ToArray();product_signer_used=$false;compiler_invoked=$false;device_effects=0;apk_proof=$false}
    [IO.File]::WriteAllText((Join-Path $OutRoot 'RESULT.json'),($record|ConvertTo-Json -Depth 16),[Text.UTF8Encoding]::new($false))
    $record|ConvertTo-Json -Depth 16
}finally{
    [Environment]::GetEnvironmentVariables('Process').Keys|ForEach-Object{[Environment]::SetEnvironmentVariable([string]$_,$null,'Process')}
    foreach($name in $parent.Keys){[Environment]::SetEnvironmentVariable($name,$parent[$name],'Process')}
}

}
Test-SharedPrerequisiteCompatibility
if([bool]$PrerequisiteInputSpecPath-ne[bool]$PrerequisiteOutRoot){throw 'Actual prerequisite input spec and fresh output must be selected together.'}
if($PrerequisiteInputSpecPath){Test-ActualPlatformPrerequisites -InputSpecPath $PrerequisiteInputSpecPath -OutRoot $PrerequisiteOutRoot}
if($PrerequisiteCompatibilityOnly){return}
$buildPath = Join-Path $repo "tools\Build-SpatialCameraPanelAndroid.ps1"
$testPath = Join-Path $repo "tools\Test-SpatialCameraPanelAndroid.ps1"
$gradlePath = Join-Path $repo "apps\spatial-camera-panel-android\app\build.gradle.kts"
foreach ($path in @($buildPath, $testPath, $gradlePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing build workflow input: $path" }
}

$build = Get-Content -LiteralPath $buildPath -Raw
$prerequisiteSource=Get-Content -LiteralPath (Join-Path $repo 'tools/lib/SpatialCameraPanelBuildPrerequisites.psm1') -Raw
$buildAndPrerequisites=$build+"`n"+$prerequisiteSource
$test = Get-Content -LiteralPath $testPath -Raw
$gradle = Get-Content -LiteralPath $gradlePath -Raw

function Require([string]$Text, [string]$Token, [string]$Message) {
    if (-not $Text.Contains($Token, [StringComparison]::Ordinal)) { throw $Message }
}

foreach ($token in @(
    '[ValidateSet("DevFast", "Candidate")]',
    '[string]$BuildToolsVersion = "36.0.0"',
    '[switch]$AllowNonDeployableDynamicStdBenchmark',
    '[string]$BuildCacheRoot = $env:RUSTY_QUEST_BUILD_CACHE_ROOT',
    'Join-Path $workspaceDrive "b\mv"',
    'native_cache_identity.v1',
    'android_shell_cache_identity.v1',
    'package_cache_identity.v1',
    'build_cache_identities.v1',
    '$nativeReceiptTargetDir = Join-Path $BuildCacheRoot',
    '$gradleUserHome = Join-Path $BuildCacheRoot "gu"',
    '$gradleProjectCacheDir = Join-Path $BuildCacheRoot "gp"',
    '$appBuildDir = Join-Path $productBuildRoot "a"',
    '"--no-daemon"',
    'gradle_daemon = $false',
    'android.aapt2FromMavenOverride=$shortAapt2',
    "Observe 'aapt2'",
    "Observe 'android-clang'",
    "Observe 'java'",
    "Observe 'zipalign'",
    "Observe 'apksigner'",
    'Pkg\.Revision',
    'selected_aapt2_sha256',
    'pinned Temurin JDK17',
    'Dynamic Rust std is restricted to an explicitly labeled non-deployable benchmark.',
    'Candidate APKs require static Rust std linkage.',
    '[System.Threading.Mutex]::new',
    'serialized stable Spatial Camera Panel build cache lane',
    'target" "list" "--installed"',
    'Set-TextFileIfChanged',
    'Copy-FileIfChanged',
    'build_phase_receipts.v1',
    'BUILD_PHASE native-compile-link',
    'BUILD_PHASE android-shell-resources-dex-apk',
    'prior_cache_available',
    'cargo_observed_fresh',
    'cargo_compile_unit_count',
    'gradle_observed_outcomes',
    'TimeUnit.NANOSECONDS.toMillis',
    'from_cache',
    'BUILD_PHASE zipalign-sign-inspection',
    'zipalign "-c" "-P" "16"',
    'llvm-readelf',
    'plaintext video media',
    'private_path_recorded = $false'
)) {
    Require $buildAndPrerequisites $token "Spatial Camera Panel build workflow is missing: $token"
}

if ($build.Contains('$intermediateRoot = Join-Path $targetRoot ("apk-i\{0}" -f $buildInputFingerprint', [StringComparison]::Ordinal)) {
    throw "The complete APK fingerprint still selects compiler intermediates."
}
foreach ($forbidden in @('$nativeCacheHit', '$shellCacheHit', '$packageCacheHit', 'build_cache_hits')) {
    if ($build.Contains($forbidden, [StringComparison]::Ordinal)) {
        throw "Build receipts still overclaim a cache hit instead of prior-cache availability: $forbidden"
    }
}
if ($build.IndexOf('$prerequisites = Get-QuestSpatialBuildPrerequisites', [StringComparison]::Ordinal) -gt
    $build.IndexOf('Spatial Camera Panel native receipt cargo build', [StringComparison]::Ordinal)) {
    throw "The signer mismatch gate occurs after native compilation."
}
foreach ($token in @(
    'Shared-package and Candidate builds require explicit local signer input.',
    '$SharedSpatialSignerSha256 = "722f1f3dcb921918d2e02f39f1b1bd8f9ff2812e07757c5fc665f6b8f7ee32a8"',
    'Explicit Spatial Camera Panel signer fingerprint mismatch before compilation.',
    'signer_path_alias_password_recorded = $false'
)) {
    Require $buildAndPrerequisites $token "Shared-package signer fail-closed gate is missing: $token"
}

foreach ($token in @(
    'Join-Path $BuildCacheRoot "gu"',
    'Join-Path $BuildCacheRoot "gp"',
    'Join-Path $BuildCacheRoot "c\host"',
    '[System.Threading.Mutex]::new',
    'serialized stable Spatial Camera Panel build cache lane',
    'if ($BuildMode -eq "DevFast") { "--daemon" } else { "--no-daemon" }'
)) {
    Require $test $token "Host validation does not share the compatible stable cache contract: $token"
}

foreach ($token in @(
    'RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS',
    'RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD',
    'RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD',
    'spatialSigningStorePassword.get()',
    'spatialSigningKeyAlias.get()',
    'spatialSigningKeyPassword.get()'
)) {
    Require $gradle $token "Gradle signing does not consume the explicit local signer binding: $token"
}

$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($buildPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) { throw "Build wrapper PowerShell parse failed: $($errors[0].Message)" }
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($testPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) { throw "Test wrapper PowerShell parse failed: $($errors[0].Message)" }

& (Join-Path $repo "tools/checks/Test-SpatialCmakeStagingHost.ps1")
& (Join-Path $repo 'tools/checks/Test-AndroidNativeSourcePaths.ps1') -RepoRoot $repo
& (Join-Path $repo 'tools/checks/Test-AndroidComponentBuildSeam.ps1') -RepoRoot $repo
& (Join-Path $repo 'tools/checks/Test-AndroidComponentOwnedSource.ps1') -RepoRoot $repo
& python (Join-Path $repo 'tools/checks/test_android_component_closure.py')
if ($LASTEXITCODE -ne 0) { throw 'Selected Android component source controls failed.' }

Write-Host "Spatial Camera Panel fast build workflow static check: PASS"

# Evaluate the real cache geometry and final Gradle argv without running the
# builder, creating cache directories, or starting Gradle/Kotlin.
function Get-KotlinIsolationBuildObservation([string]$Source,[string]$Mode,[string]$Product,[string]$Type,[string]$Phase='Assemble') {
    $parseTokens=$null;$parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($Source,[ref]$parseTokens,[ref]$parseErrors)
    if(@($parseErrors).Count){throw 'Kotlin isolation production AST is invalid.'}
    $assignments=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.AssignmentStatementAst]-and$node.Left-is[Management.Automation.Language.VariableExpressionAst]},$true))
    $lane=@($assignments|Where-Object{$_.Left.VariablePath.UserPath-ceq'cacheLaneId'})
    $kotlin=@($assignments|Where-Object{$_.Left.VariablePath.UserPath-ceq'kotlinProjectPersistentDir'})
    $directories=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.CommandAst]-and$node.GetCommandName()-ceq'New-Item'-and$node.Extent.Text.Contains('$cacheLaneRoot')},$true))
    if($lane.Count-ne1-or$kotlin.Count-ne1-or$directories.Count-ne1){throw 'One production Kotlin cache lane and directory setup required.'}
    $targets=@($directories[0].CommandElements|Where-Object{$_-is[Management.Automation.Language.ArrayExpressionAst]})
    $argv=@($assignments|Where-Object{$_.Left.VariablePath.UserPath-ceq'gradleArguments'}|Sort-Object{$_.Extent.StartOffset})
    $dispatch=@($assignments|Where-Object{$_.Left.VariablePath.UserPath-ceq'phaseArguments'})
    if($targets.Count-ne1-or$argv.Count-eq0-or$dispatch.Count-ne1){throw 'Actual Gradle argument boundary or cache directory targets missing.'}
    $BuildCacheRoot=Join-Path ([IO.Path]::GetTempPath()) 'rq-kotlin-contract'
    $appRoot=Join-Path ([IO.Path]::GetTempPath()) 'rq-kotlin-source'
    $resolvedProductId=$Product;$BuildType=$Type;$buildTypeLower=$Type.ToLowerInvariant();$RustStdLinkage='Static';$BuildMode=$Mode
    $geometry=$Source.Substring($lane[0].Extent.StartOffset,$directories[0].Extent.StartOffset-$lane[0].Extent.StartOffset)
    . ([scriptblock]::Create($geometry))
    $created=@(& ([scriptblock]::Create($targets[0].Extent.Text)))
    $shortAapt2='fixture-aapt2';$cmakeStaging=@{path='fixture-cmake'};$mediaAndroidJar='fixture-android.jar';$mediaAndroidBuildRoot='fixture-media'
    $sourceBankBuildInputs=@{enabled=$false};$UseSourceCompositionVersionName=$false;$gradleTimingInitPath='fixture-timing.gradle'
    $argumentConstruction=$Source.Substring($argv[0].Extent.StartOffset,$dispatch[0].Extent.StartOffset-$argv[0].Extent.StartOffset)
    . ([scriptblock]::Create($argumentConstruction))
    # Consume only the real fixed-phase assignments, never invoke a tool or the
    # environment/owner body while observing argv. Expected tokens stay below.
    $moduleAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'tools/lib/AndroidComponentClosure.psm1'),[ref]$parseTokens,[ref]$parseErrors)
    if(@($parseErrors).Count){throw 'Gradle owner module AST is invalid.'}
    $function=@($moduleAst.FindAll({param($node)$node-is[Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-ceq'Invoke-AndroidComponentGradlePhase'},$true))
    if($function.Count-ne1){throw 'One fixed phase owner required.'}
    $ownerAssignments=@($function[0].FindAll({param($node)$node-is[Management.Automation.Language.AssignmentStatementAst]-and$node.Left-is[Management.Automation.Language.VariableExpressionAst]},$true))
    $InventoryPath='';$EvidenceDirectory='';$Arguments=@($gradleArguments);$ProjectDirectory=$appRoot;$GradleUserHome=$gradleUserHome
    foreach($name in @('task','selected','effectiveArguments')){
        $assignment=@($ownerAssignments|Where-Object{$_.Left.VariablePath.UserPath-ceq$name})
        if($assignment.Count-ne1){throw 'One actual fixed-phase argument assignment required.'}
        . ([scriptblock]::Create($assignment[0].Extent.Text))
    }
    [pscustomobject]@{root=$BuildCacheRoot;kotlin=$kotlinProjectPersistentDir;created=$created;arguments=@($effectiveArguments)}
}
function Assert-KotlinIsolationBuildObservation([object]$Observed,[string]$ExpectedLane,[string]$Mode,[string]$Type,[string]$Phase='Assemble') {
    $expected=Join-Path $Observed.root $ExpectedLane
    if($Observed.kotlin-cne$expected-or@($Observed.created|Where-Object{$_-ceq$expected}).Count-ne1){throw 'Kotlin persistence must use the created product/type cache lane.'}
    $persistent=@($Observed.arguments|Where-Object{$_-clike'-Pkotlin.project.persistent.dir=*'})
    $disable=@($Observed.arguments|Where-Object{$_-clike'-Pkotlin.project.persistent.dir.gradle.disableWrite=*'})
    if($persistent.Count-ne1-or$persistent[0]-cne"-Pkotlin.project.persistent.dir=$expected"-or$disable.Count-ne1-or$disable[0]-cne'-Pkotlin.project.persistent.dir.gradle.disableWrite=true'){throw 'Actual Gradle argv must isolate Kotlin persistence and suppress legacy writes in both modes.'}
    $configuration=if($Mode-ceq'Candidate'){'--no-configuration-cache'}else{'--configuration-cache'}
    $expectedTask=if($Phase-ceq'JvmPreflight'){':app:verifySelectedAndroidJvmCompilation'}else{":app:assemble$Type"}
    $tasks=@($Observed.arguments|Where-Object{$_-clike':app:*'})
    if('--no-daemon'-cnotin$Observed.arguments-or$configuration-cnotin$Observed.arguments-or$tasks.Count-ne1-or$tasks[0]-cne$expectedTask){throw 'Kotlin isolation changed the existing Gradle mode or fixed phase task.'}
    if(($Mode-ceq'DevFast')-ne('-Pkotlin.compiler.execution.strategy=in-process'-cin$Observed.arguments)){throw 'Kotlin isolation changed the existing compiler strategy.'}
    if("-PrqAndroidComponentKotlinTask=compile${Type}Kotlin" -cnotin $Observed.arguments -or
        "-PrqAndroidComponentJavaTask=compile${Type}JavaWithJavac" -cnotin $Observed.arguments){throw 'Selected Android component compiler task names must be explicit.'}
}
foreach($mode in @('DevFast','Candidate')) {
    foreach($case in @(@{product='Broker.Sample';type='Debug';lane='k\broker.sample-debug'},@{product='Other.Product';type='Release';lane='k\other.product-release'})) {
        foreach($phase in @('Assemble','JvmPreflight')){
            $observation=Get-KotlinIsolationBuildObservation $build $mode $case.product $case.type $phase
            Assert-KotlinIsolationBuildObservation $observation $case.lane $mode $case.type $phase
        }
    }
}
$persistentToken='"-Pkotlin.project.persistent.dir=$kotlinProjectPersistentDir",'
$disableToken='"-Pkotlin.project.persistent.dir.gradle.disableWrite=true",'
$dispatchBoundary='    $phaseArguments=@{'
if(-not$build.Contains($dispatchBoundary)){throw 'Actual common Gradle argv boundary is missing.'}
$devFastOnly=$build.Replace($persistentToken,'').Replace($dispatchBoundary,('if ($BuildMode -eq "DevFast") { $gradleArguments += "-Pkotlin.project.persistent.dir=$kotlinProjectPersistentDir" }'+"`n"+$dispatchBoundary))
$damages=@(
    @{name='missing persistent directory option';source=$build.Replace($persistentToken,'')},
    @{name='missing legacy-write suppression';source=$build.Replace($disableToken,'')},
    @{name='false legacy-write suppression';source=$build.Replace($disableToken,'"-Pkotlin.project.persistent.dir.gradle.disableWrite=false",')},
    @{name='source-tree persistence';source=$build.Replace('$kotlinProjectPersistentDir = Join-Path $BuildCacheRoot ("k\{0}" -f $cacheLaneId)','$kotlinProjectPersistentDir = Join-Path $appRoot ".kotlin"')},
    @{name='directory omitted from setup';source=$build.Replace('$kotlinProjectPersistentDir,','')},
    @{name='duplicate persistence option';source=$build.Replace($persistentToken,($persistentToken+"`n"+$persistentToken))},
    @{name='DevFast-only persistence';source=$devFastOnly}
)
foreach($damage in $damages) {
    if($damage.source-ceq$build){throw "Kotlin isolation damage fixture did not change production input: $($damage.name)"}
    $refused=$false;$failure=''
    try {
        $observation=Get-KotlinIsolationBuildObservation $damage.source 'Candidate' 'Broker.Sample' 'Debug'
        Assert-KotlinIsolationBuildObservation $observation 'k\broker.sample-debug' 'Candidate' 'Debug'
    } catch {
        $failure=$_.Exception.Message
        $refused=$failure-cin@('Kotlin persistence must use the created product/type cache lane.','Actual Gradle argv must isolate Kotlin persistence and suppress legacy writes in both modes.')
    }
    if(-not$refused){throw "Kotlin isolation damage '$($damage.name)' did not fail at its geometry/argv guard: $failure"}
}
Write-Host 'Kotlin persistence isolation: PASS (actual production geometry/argv in both modes; seven damaged variants refused; no Gradle execution)'
