param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$buildPath = Join-Path $repo "tools\Build-SpatialCameraPanelAndroid.ps1"
$testPath = Join-Path $repo "tools\Test-SpatialCameraPanelAndroid.ps1"
$gradlePath = Join-Path $repo "apps\spatial-camera-panel-android\app\build.gradle.kts"
foreach ($path in @($buildPath, $testPath, $gradlePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing build workflow input: $path" }
}

$build = Get-Content -LiteralPath $buildPath -Raw
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
    'Invoke-SmokeChecked -Name "aapt2"',
    'Invoke-SmokeChecked -Name "android-clang"',
    'Invoke-SmokeChecked -Name "java"',
    'Invoke-SmokeChecked -Name "zipalign"',
    'Invoke-SmokeChecked -Name "apksigner"',
    'Pkg\.Revision',
    'selected_aapt2_sha256',
    'pinned Temurin JDK 17 contract',
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
    Require $build $token "Spatial Camera Panel build workflow is missing: $token"
}

if ($build.Contains('$intermediateRoot = Join-Path $targetRoot ("apk-i\{0}" -f $buildInputFingerprint', [StringComparison]::Ordinal)) {
    throw "The complete APK fingerprint still selects compiler intermediates."
}
foreach ($forbidden in @('$nativeCacheHit', '$shellCacheHit', '$packageCacheHit', 'build_cache_hits')) {
    if ($build.Contains($forbidden, [StringComparison]::Ordinal)) {
        throw "Build receipts still overclaim a cache hit instead of prior-cache availability: $forbidden"
    }
}
if ($build.IndexOf('Explicit Spatial Camera Panel signer fingerprint mismatch before compilation.', [StringComparison]::Ordinal) -gt
    $build.IndexOf('Spatial Camera Panel native receipt cargo build', [StringComparison]::Ordinal)) {
    throw "The signer mismatch gate occurs after native compilation."
}
foreach ($token in @(
    'Shared-package and candidate builds require an explicit local signer binding before compilation.',
    '$SharedSpatialSignerSha256 = "722f1f3dcb921918d2e02f39f1b1bd8f9ff2812e07757c5fc665f6b8f7ee32a8"',
    'Explicit Spatial Camera Panel signer fingerprint mismatch before compilation.',
    'signer_path_alias_password_recorded = $false'
)) {
    Require $build $token "Shared-package signer fail-closed gate is missing: $token"
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
function Get-KotlinIsolationBuildObservation([string]$Source,[string]$Mode,[string]$Product,[string]$Type) {
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
    $stopwatch=@($assignments|Where-Object{$_.Left.VariablePath.UserPath-ceq'gradleStopwatch'})
    if($targets.Count-ne1-or$argv.Count-eq0-or$stopwatch.Count-ne1){throw 'Actual Gradle argument boundary or cache directory targets missing.'}
    $BuildCacheRoot=Join-Path ([IO.Path]::GetTempPath()) 'rq-kotlin-contract'
    $appRoot=Join-Path ([IO.Path]::GetTempPath()) 'rq-kotlin-source'
    $resolvedProductId=$Product;$BuildType=$Type;$buildTypeLower=$Type.ToLowerInvariant();$RustStdLinkage='Static';$BuildMode=$Mode
    $geometry=$Source.Substring($lane[0].Extent.StartOffset,$directories[0].Extent.StartOffset-$lane[0].Extent.StartOffset)
    . ([scriptblock]::Create($geometry))
    $created=@(& ([scriptblock]::Create($targets[0].Extent.Text)))
    $shortAapt2='fixture-aapt2';$cmakeStaging=@{path='fixture-cmake'};$mediaAndroidJar='fixture-android.jar';$mediaAndroidBuildRoot='fixture-media'
    $sourceBankBuildInputs=@{enabled=$false};$UseSourceCompositionVersionName=$false;$gradleTimingInitPath='fixture-timing.gradle'
    $argumentConstruction=$Source.Substring($argv[0].Extent.StartOffset,$stopwatch[0].Extent.StartOffset-$argv[0].Extent.StartOffset)
    . ([scriptblock]::Create($argumentConstruction))
    [pscustomobject]@{root=$BuildCacheRoot;kotlin=$kotlinProjectPersistentDir;created=$created;arguments=@($gradleArguments)}
}
function Assert-KotlinIsolationBuildObservation([object]$Observed,[string]$ExpectedLane,[string]$Mode,[string]$Type) {
    $expected=Join-Path $Observed.root $ExpectedLane
    if($Observed.kotlin-cne$expected-or@($Observed.created|Where-Object{$_-ceq$expected}).Count-ne1){throw 'Kotlin persistence must use the created product/type cache lane.'}
    $persistent=@($Observed.arguments|Where-Object{$_-clike'-Pkotlin.project.persistent.dir=*'})
    $disable=@($Observed.arguments|Where-Object{$_-clike'-Pkotlin.project.persistent.dir.gradle.disableWrite=*'})
    if($persistent.Count-ne1-or$persistent[0]-cne"-Pkotlin.project.persistent.dir=$expected"-or$disable.Count-ne1-or$disable[0]-cne'-Pkotlin.project.persistent.dir.gradle.disableWrite=true'){throw 'Actual Gradle argv must isolate Kotlin persistence and suppress legacy writes in both modes.'}
    $configuration=if($Mode-ceq'Candidate'){'--no-configuration-cache'}else{'--configuration-cache'}
    if('--no-daemon'-cnotin$Observed.arguments-or$configuration-cnotin$Observed.arguments-or(":app:assemble$Type")-cnotin$Observed.arguments){throw 'Kotlin isolation changed the existing Gradle mode or assembly task.'}
    if(($Mode-ceq'DevFast')-ne('-Pkotlin.compiler.execution.strategy=in-process'-cin$Observed.arguments)){throw 'Kotlin isolation changed the existing compiler strategy.'}
    if("-PrqAndroidComponentKotlinTask=compile${Type}Kotlin" -cnotin $Observed.arguments -or
        "-PrqAndroidComponentJavaTask=compile${Type}JavaWithJavac" -cnotin $Observed.arguments){throw 'Selected Android component compiler task names must be explicit.'}
}
foreach($mode in @('DevFast','Candidate')) {
    foreach($case in @(@{product='Broker.Sample';type='Debug';lane='k\broker.sample-debug'},@{product='Other.Product';type='Release';lane='k\other.product-release'})) {
        $observation=Get-KotlinIsolationBuildObservation $build $mode $case.product $case.type
        Assert-KotlinIsolationBuildObservation $observation $case.lane $mode $case.type
    }
}
$persistentToken='"-Pkotlin.project.persistent.dir=$kotlinProjectPersistentDir",'
$disableToken='"-Pkotlin.project.persistent.dir.gradle.disableWrite=true",'
$devFastOnly=$build.Replace($persistentToken,'').Replace('$gradleStopwatch = [Diagnostics.Stopwatch]::StartNew()',('if ($BuildMode -eq "DevFast") { $gradleArguments += "-Pkotlin.project.persistent.dir=$kotlinProjectPersistentDir" }'+"`n"+'$gradleStopwatch = [Diagnostics.Stopwatch]::StartNew()'))
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
