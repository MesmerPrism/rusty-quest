[CmdletBinding()]
param(
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$GradlePath = $env:RUSTY_QUEST_GRADLE_PATH,
    [string]$PythonPath = 'python',
    [string]$HostJsonJar = $env:RUSTY_QUEST_HOST_JSON_JAR,
    [string]$PolicyVectorPath = $env:RUSTY_QUEST_ORDINARY_START_POLICY_VECTOR,
    [string]$OutputRoot
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $JavaHome -or -not $AndroidHome -or -not $GradlePath -or -not $PolicyVectorPath) {
    throw 'Select JDK17, Android SDK, Gradle and the retained public policy vector through parameters or JAVA_HOME/ANDROID_HOME/RUSTY_QUEST_GRADLE_PATH/RUSTY_QUEST_ORDINARY_START_POLICY_VECTOR.'
}
foreach ($path in @($JavaHome, $AndroidHome, $GradlePath, $PolicyVectorPath)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required input unavailable: $path" }
}
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$AndroidHome = (Resolve-Path -LiteralPath $AndroidHome).Path
$GradlePath = (Resolve-Path -LiteralPath $GradlePath).Path
$PolicyVectorPath = (Resolve-Path -LiteralPath $PolicyVectorPath).Path
if (-not (Select-String -LiteralPath (Join-Path $JavaHome release) -Pattern '^JAVA_VERSION="17\.' -Quiet)) {
    throw 'This source check requires JDK17.'
}
if (-not $HostJsonJar) {
    $cache = Join-Path $HOME '.gradle/caches/modules-2/files-2.1/org.json/json/20240303'
    $jars = @(Get-ChildItem -LiteralPath $cache -Filter json-20240303.jar -File -Recurse -ErrorAction SilentlyContinue)
    if ($jars.Count -eq 1) { $HostJsonJar = $jars[0].FullName }
}
if (-not $HostJsonJar -or (Get-FileHash -LiteralPath $HostJsonJar).Hash.ToLowerInvariant() -cne '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed') {
    throw 'Select pinned org.json20240303 with -HostJsonJar or RUSTY_QUEST_HOST_JSON_JAR.'
}
$HostJsonJar = (Resolve-Path -LiteralPath $HostJsonJar).Path
if ((Get-FileHash -LiteralPath $PolicyVectorPath).Hash.ToLowerInvariant() -cne '9b34066eb2b685585be364527443da8a51b156ad750bebabac5d092cc67025a6') {
    throw 'Policy vector differs from the retained public owning-factory source vector.'
}
$androidJar = Join-Path $AndroidHome 'platforms/android-34/android.jar'
if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) { throw 'Android API34 compile input unavailable.' }
if (-not $OutputRoot) { $OutputRoot = Join-Path $RepoRoot ('target/ordinary-start-source-' + [guid]::NewGuid().ToString('N')) }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$target = [IO.Path]::GetFullPath((Join-Path $RepoRoot target))
if (-not $OutputRoot.StartsWith($target + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $OutputRoot)) {
    throw 'Select a create-new output directory beneath the repository target directory.'
}
[void](New-Item -ItemType Directory -Path $OutputRoot)
function Pin([string]$Path) {
    [ordered]@{ path=[IO.Path]::GetFullPath($Path); sha256=(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant() }
}
function SaveJson([string]$Name, $Value) {
    $Value | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath (Join-Path $OutputRoot $Name) -Encoding utf8NoBOM
}
$checks = [Collections.Generic.List[object]]::new()
function Run([string]$Name, [string]$Executable, [string[]]$Arguments) {
    $command = Get-Command $Executable -ErrorAction Stop
    $log = Join-Path $OutputRoot "$Name.log"
    $PSNativeCommandUseErrorActionPreference = $false
    & $Executable @Arguments *> $log
    $code = $LASTEXITCODE
    $checks.Add([ordered]@{ name=$Name; executable=(Pin $command.Source); arguments=$Arguments; exit=$code; log=(Pin $log) })
    if ($code -ne 0) {
        Get-Content -LiteralPath $log -Tail 60
        throw "Ordinary Start source check failed: $Name"
    }
    Write-Output "PASS $Name"
}
function SourcePins {
    $paths = @(& git -C $RepoRoot -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Source inventory failed.' }
    foreach ($relative in ($paths | Sort-Object -Unique)) {
        $path = Join-Path $RepoRoot $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            [ordered]@{ path=$relative; sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() }
        }
    }
}
$filters = @(
    'io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexOperatorContractTest',
    'io.github.mesmerprism.rustyquest.spatial_camera_panel.PrivateLayerPanelControlModuleTest',
    'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexStartPreflightTest',
    'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexProcessHostAttachmentTest',
    'io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialVideoSourceRouteTransitionActivityContractTest'
)
$names = @('PATH','JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME','GLSLC',
    'RUSTY_QUEST_SPATIAL_BUILD_ROOT','RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR','RUSTY_QUEST_SPATIAL_APP_BUILD_DIR',
    'RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_MEDIA_POLICY','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_SETUP_POLICY',
    'RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_POLICY_SHA256','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256',
    'RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64')
$old = @{}
foreach ($name in $names) { $old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Env([string]$Name, [AllowNull()][string]$Value) { [Environment]::SetEnvironmentVariable($Name, $Value, 'Process') }
$testResults = [Collections.Generic.List[object]]::new()
Push-Location $RepoRoot
try {
    $before = @(SourcePins)
    SaveJson source-inputs.json $before
    $testInputs = foreach ($filter in $filters) {
        $stem = Join-Path $RepoRoot ('apps/spatial-camera-panel-android/app/src/test/java/' + $filter.Replace('.', '/'))
        $found = @(@("$stem.java", "$stem.kt") | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
        if ($found.Count -ne 1) { throw "Targeted JVM source unavailable or ambiguous: $filter" }
        Pin $found[0]
    }
    foreach ($relative in @('tools/checks/Test-EmbeddedDuplexOrdinaryStartHost.py', 'tools/checks/Test-EmbeddedDuplexOriginalsHost.py',
            'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexOrdinaryStartHostTest.java',
            'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexStartIntentSlotHostTest.java',
            'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexOriginalsHostTest.java')) {
        $testInputs += @(Pin (Join-Path $RepoRoot $relative))
    }
    SaveJson test-inputs.json @($testInputs)
    Copy-Item -LiteralPath $PolicyVectorPath -Destination (Join-Path $OutputRoot policy-vector.json)
    $vector = Get-Content -Raw -LiteralPath $PolicyVectorPath | ConvertFrom-Json -Depth 64 -DateKind String
    if ($vector.'$schema' -cne 'rusty.quest.fresh_setup_build_policy_vector.v1' -or $vector.projection.selection -cne 'source-bound-v2') {
        throw 'Unexpected public policy vector shape.'
    }
    $selection = [Text.UTF8Encoding]::new($false).GetBytes($vector.enrollment_selection_canonical_json)
    $rawSha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($selection)).ToLowerInvariant()
    SaveJson tool-inputs.json @((Pin (Join-Path $JavaHome 'bin/java.exe')), (Pin (Join-Path $JavaHome 'bin/javac.exe')),
        (Pin (Join-Path $JavaHome release)), (Pin $GradlePath), (Pin $HostJsonJar), (Pin $androidJar), (Pin $PolicyVectorPath))
    Env JAVA_HOME $JavaHome
    Env PATH ((Join-Path $JavaHome bin) + [IO.Path]::PathSeparator + $old.PATH)
    Env ANDROID_HOME $AndroidHome
    Env ANDROID_NDK_HOME (Join-Path $AndroidHome 'ndk/27.2.12479018')
    Env GLSLC (Join-Path $env:ANDROID_NDK_HOME 'shader-tools/windows-x86_64/glslc.exe')
    foreach ($mode in @('finite','fresh')) {
        $fresh = $mode -eq 'fresh'
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_MEDIA_POLICY $(if ($fresh) { 'ongoing-v2' } else { 'finite' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_SETUP_POLICY $(if ($fresh) { 'source-bound-v2' } else { 'none' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_POLICY_SHA256 $(if ($fresh) { $vector.projection.policy_sha256 } else { '' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256 $(if ($fresh) { $vector.projection.enrollment_selection_sha256 } else { '' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256 $(if ($fresh) { $rawSha } else { '' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64 $(if ($fresh) { [Convert]::ToBase64String($selection) } else { '' })
        $buildRoot = Join-Path $OutputRoot "android-$mode"
        Env RUSTY_QUEST_SPATIAL_BUILD_ROOT $buildRoot
        Env RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR (Join-Path $buildRoot root)
        Env RUSTY_QUEST_SPATIAL_APP_BUILD_DIR (Join-Path $buildRoot app)
        $arguments = @('-p','apps/spatial-camera-panel-android','--offline','--no-daemon','--build-cache',':app:compileDebugKotlin',':app:testDebugUnitTest')
        foreach ($filter in $filters) { $arguments += @('--tests', $filter) }
        Run "android-$mode" $GradlePath $arguments
        $reports = @(Get-ChildItem -LiteralPath (Join-Path $buildRoot 'app/test-results/testDebugUnitTest') -Filter '*.xml' -File)
        $seen = @()
        foreach ($report in $reports) {
            [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
            $suite = $xml.DocumentElement
            $suiteName = $suite.GetAttribute('name')
            $count = [int]$suite.GetAttribute('tests'); $skipped = [int]$suite.GetAttribute('skipped')
            if ($suite.GetAttribute('failures') -ne '0' -or $suite.GetAttribute('errors') -ne '0' -or $count -le $skipped) {
                throw "Targeted JVM tests failed or did not execute: $suiteName"
            }
            $seen += $suiteName
            $testResults.Add([ordered]@{ selection=$mode; suite=$suiteName; tests=$count; skipped=$skipped; report=(Pin $report.FullName) })
        }
        foreach ($filter in $filters) { if ($filter -notin $seen) { throw "Missing targeted JVM suite: $filter" } }
        $compiled = foreach ($directory in @((Join-Path $buildRoot 'app/tmp/kotlin-classes/debug'),
                (Join-Path $buildRoot 'app/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
                (Join-Path $buildRoot 'media-stream-android/classes/java/main'))) {
            Get-ChildItem -LiteralPath $directory -Filter '*.class' -File -Recurse | Sort-Object FullName | ForEach-Object { Pin $_.FullName }
        }
        SaveJson "compiled-$mode-inputs.json" @($compiled)
    }
    $freshRoot = Join-Path $OutputRoot android-fresh
    $supportPaths = @((Join-Path $freshRoot 'app/tmp/kotlin-classes/debug'),
        (Join-Path $freshRoot 'app/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
        (Join-Path $freshRoot 'media-stream-android/classes/java/main'),
        (Join-Path $freshRoot 'spatial-sdk-shared/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar'), $androidJar)
    foreach ($path in $supportPaths) { if (-not (Test-Path -LiteralPath $path)) { throw "Current compiled helper input unavailable: $path" } }
    SaveJson helper-inputs.json @($supportPaths | ForEach-Object {
        if (Test-Path -LiteralPath $_ -PathType Leaf) { Pin $_ }
        else { Get-ChildItem -LiteralPath $_ -Filter '*.class' -File -Recurse | Sort-Object FullName | ForEach-Object { Pin $_.FullName } }
    })
    Run ordinary-start-host $PythonPath @('tools/checks/Test-EmbeddedDuplexOrdinaryStartHost.py','--root',$RepoRoot,
        '--output',(Join-Path $OutputRoot host),'--java-home',$JavaHome,'--json-jar',$HostJsonJar,
        '--support-classpath',($supportPaths -join [IO.Path]::PathSeparator))
    Run boundary $PythonPath @('tools/check_quest_boundaries.py')
    Run whitespace git @('diff','--check')
    $after = @(SourcePins)
    SaveJson source-after.json $after
    if (($before | ConvertTo-Json -Depth 8 -Compress) -cne ($after | ConvertTo-Json -Depth 8 -Compress)) {
        throw 'Source changed during qualification; retained outputs do not attest one source observation.'
    }
    SaveJson test-results.json ($testResults.ToArray())
    SaveJson checks.json ($checks.ToArray())
    SaveJson RESULT.json ([ordered]@{ schema='rusty.quest.ordinary_start_source_qualification.v1'; status='passed';
        head=(& git rev-parse HEAD).Trim(); source_inputs=(Pin (Join-Path $OutputRoot source-inputs.json));
        tests=(Pin (Join-Path $OutputRoot test-results.json)); checks=(Pin (Join-Path $OutputRoot checks.json));
        selections=@('finite/none','ongoing-v2/source-bound-v2');
        enrollment='Exact retained public owning-factory vector; no Product enrollment/adoption';
        limits='Java/Kotlin source compilation and targeted JVM/host controls with declared model leaves. No APK, Native/Cargo/device execution, physical cleanup, frame/presentation/performance or installed-package claim.' })
    Write-Output "Ordinary Start source qualification: $OutputRoot"
} finally {
    try { SaveJson checks.json ($checks.ToArray()) }
    finally {
        foreach ($name in $names) { Env $name $old[$name] }
        Pop-Location
    }
}
