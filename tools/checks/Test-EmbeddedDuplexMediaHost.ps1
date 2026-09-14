param(
    [string]$QuestRepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [Parameter(Mandatory = $true)][string]$ManifoldRoot,
    [string]$RepositoryMapPath = "",
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedQuestCommit,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedQuestTree,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedQuestAncestor,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedConsumerAncestor,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedManifoldCommit,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedManifoldTree,
    [Parameter(Mandatory = $true)][string]$OutPath,
    [ValidateRange(30, 3600)][int]$TimeoutSeconds = 900,
    [string]$GradleExecutable = $env:RUSTY_QUEST_GRADLE_EXECUTABLE,
    [string]$BuildCacheRoot = $env:RUSTY_QUEST_BUILD_CACHE_ROOT,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-GitIdentity {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$ExpectedCommit,
        [Parameter(Mandatory = $true)][string]$ExpectedTree,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $resolved = (Resolve-Path -LiteralPath $Root).Path
    $inside = ((& git -C $resolved rev-parse --is-inside-work-tree 2>$null) -join "").Trim()
    $commit = ((& git -C $resolved rev-parse HEAD 2>$null) -join "").Trim()
    $tree = ((& git -C $resolved rev-parse 'HEAD^{tree}' 2>$null) -join "").Trim()
    $status = (& git -C $resolved status --porcelain=v1 -z --untracked-files=all 2>$null) -join ""
    if ($LASTEXITCODE -ne 0 -or $inside -cne "true") { throw "$Label is not a Git worktree." }
    if ($commit -cne $ExpectedCommit -or $tree -cne $ExpectedTree) { throw "$Label does not match its exact commit/tree." }
    if (-not [string]::IsNullOrEmpty($status)) { throw "$Label must be clean." }
    return [ordered]@{ path = $resolved; commit = $commit; tree = $tree; clean = $true }
}

function Assert-GitAncestor {
    param([string]$Root, [string]$Ancestor, [string]$Descendant, [string]$Label)
    & git -C $Root merge-base --is-ancestor $Ancestor $Descendant 2>$null
    if ($LASTEXITCODE -ne 0) { throw "$Label is not an ancestor of the exact Quest candidate." }
}

function Write-Utf8NoBomCreateNew {
    param([string]$Path, [string]$Text)
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
    } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Invoke-BoundedHostCheck {
    param(
        [string]$Id,
        [string]$WorkingDirectory,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$EvidenceDirectory,
        [int]$DeadlineSeconds,
        [hashtable]$Environment = @{}
    )
    $stdoutPath = Join-Path $EvidenceDirectory "$Id.stdout.txt"
    $stderrPath = Join-Path $EvidenceDirectory "$Id.stderr.txt"
    if ((Test-Path -LiteralPath $stdoutPath) -or (Test-Path -LiteralPath $stderrPath)) { throw "Host evidence collision for '$Id'." }
    $start = [DateTime]::UtcNow
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($name in $Environment.Keys) { $info.Environment[[string]$name] = [string]$Environment[$name] }
    foreach ($argument in $Arguments) { [void]$info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $timedOut = $false
    try {
        if (-not $process.Start()) { throw "Failed to start host check '$Id'." }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($DeadlineSeconds * 1000)) {
            $timedOut = $true
            try { $process.Kill($true) } catch { Write-Verbose "Process tree was already terminated for '$Id'." }
            [void]$process.WaitForExit(10000)
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        Write-Utf8NoBomCreateNew $stdoutPath $stdout
        Write-Utf8NoBomCreateNew $stderrPath $stderr
        $exitCode = if ($timedOut) { $null } else { [int]$process.ExitCode }
    } finally { $process.Dispose() }
    $elapsed = [long]([DateTime]::UtcNow - $start).TotalMilliseconds
    $result = [ordered]@{
        check_id = $Id
        executable = $Executable
        arguments = @($Arguments)
        working_directory = $WorkingDirectory
        timeout_seconds = $DeadlineSeconds
        elapsed_milliseconds = $elapsed
        timed_out = $timedOut
        exit_code = $exitCode
        stdout = [ordered]@{ path = $stdoutPath; sha256 = Get-FileSha256 $stdoutPath; size_bytes = (Get-Item -LiteralPath $stdoutPath).Length }
        stderr = [ordered]@{ path = $stderrPath; sha256 = Get-FileSha256 $stderrPath; size_bytes = (Get-Item -LiteralPath $stderrPath).Length }
        passed = [bool](-not $timedOut -and $exitCode -eq 0)
    }
    if (-not $result.passed) { throw "Host check '$Id' failed or timed out. Evidence: $stderrPath" }
    return $result
}

$quest = Get-GitIdentity $QuestRepoRoot $ExpectedQuestCommit $ExpectedQuestTree "Quest source"
$manifold = Get-GitIdentity $ManifoldRoot $ExpectedManifoldCommit $ExpectedManifoldTree "Manifold source"
Assert-GitAncestor $quest.path $ExpectedQuestAncestor $quest.commit "Required Quest supplier"
Assert-GitAncestor $quest.path $ExpectedConsumerAncestor $quest.commit "Required Quest consumer"

if ($RepositoryMapPath) {
    $mapPath = (Resolve-Path -LiteralPath $RepositoryMapPath).Path
    $map = Get-Content -Raw -LiteralPath $mapPath | ConvertFrom-Json -Depth 32
    if ([string]$map.schema -cne "rusty.morphospace.workflow.repository_map.v1") { throw "Repository map schema is invalid." }
    $questRow = @($map.repositories | Where-Object { $_.repo_id -ceq "quest-adapter" })
    $manifoldRow = @($map.repositories | Where-Object { $_.repo_id -ceq "rusty-manifold" })
    if ($questRow.Count -ne 1 -or $manifoldRow.Count -ne 1 -or [string]$questRow[0].role -cne "source" -or [string]$manifoldRow[0].role -cne "source") { throw "Repository map lacks exact source rows." }
    if ((Resolve-Path -LiteralPath $questRow[0].path).Path -cne $quest.path -or (Resolve-Path -LiteralPath $manifoldRow[0].path).Path -cne $manifold.path) { throw "Repository map paths do not match the supplied roots." }
    $mapEvidence = [ordered]@{ path = $mapPath; sha256 = Get-FileSha256 $mapPath }
} else { $mapEvidence = $null }

$requiredPaths = @(
    "crates/rusty-quest-broker-authority/src/embedded_duplex",
    "crates/rusty-quest-media-stream/src",
    "crates/rusty-quest-media-stream-android/src",
    "apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex",
    "apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex",
    "apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialVideoSourceRoutingCoordinator.kt",
    "apps/spatial-camera-panel-android/app/src/test/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialVideoSourceRoutingCoordinatorTest.kt"
)
foreach ($relative in $requiredPaths) {
    if (-not (Test-Path -LiteralPath (Join-Path $quest.path $relative))) { throw "Required reusable embedded-duplex source is absent: $relative" }
}

$outFull = [IO.Path]::GetFullPath($OutPath)
if (Test-Path -LiteralPath $outFull) { throw "OutPath already exists." }
$evidenceDirectory = "$outFull.children"
if (Test-Path -LiteralPath $evidenceDirectory) { throw "Child evidence directory already exists." }
New-Item -ItemType Directory -Path $evidenceDirectory | Out-Null

if ([string]::IsNullOrWhiteSpace($GradleExecutable)) {
    $localGradle = Join-Path $quest.path "local-artifacts/tools/gradle-9.4.1/bin/gradle.bat"
    if (Test-Path -LiteralPath $localGradle -PathType Leaf) { $GradleExecutable = $localGradle }
}
if ([string]::IsNullOrWhiteSpace($GradleExecutable) -or -not (Test-Path -LiteralPath $GradleExecutable -PathType Leaf)) { throw "RUSTY_QUEST_GRADLE_EXECUTABLE must bind a provisioned Gradle executable." }
if ([string]::IsNullOrWhiteSpace($BuildCacheRoot)) { throw "RUSTY_QUEST_BUILD_CACHE_ROOT is required for isolated app host tests." }
if ([string]::IsNullOrWhiteSpace($AndroidHome) -or -not (Test-Path -LiteralPath $AndroidHome -PathType Container)) { throw "ANDROID_HOME must bind the Android SDK for app host tests." }
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath $JavaHome -PathType Container)) { throw "JAVA_HOME must bind the JDK for app host tests." }
$GradleExecutable = (Resolve-Path -LiteralPath $GradleExecutable).Path
$BuildCacheRoot = [IO.Path]::GetFullPath($BuildCacheRoot)
$gradleEnvironment = @{
    ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidHome).Path
    JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    GRADLE_USER_HOME = (Join-Path $BuildCacheRoot "duplex-host/gu")
    RUSTY_QUEST_SPATIAL_APP_BUILD_DIR = (Join-Path $BuildCacheRoot "duplex-host/app")
    RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR = (Join-Path $BuildCacheRoot "duplex-host/root")
}

$pwsh = (Get-Command pwsh -ErrorAction Stop).Source
$gradleArguments = @("--no-daemon", "--console=plain", "--build-cache", "--project-cache-dir", (Join-Path $BuildCacheRoot "duplex-host/project-cache"), "-p", (Join-Path $quest.path "apps/spatial-camera-panel-android"), ":app:testDebugUnitTest", "--tests", "io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialVideoSourceRoutingCoordinatorTest")
$checks = @(
    [ordered]@{ id = "media-stream-rust"; cwd = $quest.path; exe = "cargo"; argv = @("test", "--locked", "-p", "rusty-quest-media-stream") },
    [ordered]@{ id = "media-stream-android-rust"; cwd = $quest.path; exe = "cargo"; argv = @("test", "--locked", "-p", "rusty-quest-media-stream-android") },
    [ordered]@{ id = "broker-authority-rust"; cwd = $quest.path; exe = "cargo"; argv = @("test", "--locked", "-p", "rusty-quest-broker-authority") },
    [ordered]@{ id = "spatial-native-receipt-rust"; cwd = $quest.path; exe = "cargo"; argv = @("test", "--locked", "-p", "spatial-camera-panel-native-receipt") },
    [ordered]@{ id = "media-stream-android-static"; cwd = $quest.path; exe = $pwsh; argv = @("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $quest.path "tools/checks/Test-RustyQuestMediaStreamAndroid.ps1")) },
    [ordered]@{ id = "spatial-camera-panel-static"; cwd = $quest.path; exe = $pwsh; argv = @("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $quest.path "tools/checks/Test-SpatialCameraPanelAndroidStatic.ps1")) },
    [ordered]@{ id = "spatial-video-source-routing-app"; cwd = $quest.path; exe = $pwsh; env = $gradleEnvironment; argv = @("-NoProfile", "-NonInteractive", "-CommandWithArgs", '& $args[0] @($args[1..($args.Count - 1)]); exit $LASTEXITCODE', $GradleExecutable) + $gradleArguments },
    [ordered]@{ id = "manifold-runtime-host"; cwd = $manifold.path; exe = "cargo"; argv = @("test", "--locked", "-p", "rusty-manifold-runtime-host") },
    [ordered]@{ id = "manifold-peer-runtime-host"; cwd = $manifold.path; exe = "cargo"; argv = @("test", "--locked", "-p", "rusty-manifold-peer-runtime-host") },
    [ordered]@{ id = "manifold-media-session"; cwd = $manifold.path; exe = "cargo"; argv = @("test", "--locked", "-p", "rusty-manifold-media-session") }
)

$results = [Collections.Generic.List[object]]::new()
foreach ($check in $checks) {
    $environment = if ($check.Contains("env")) { $check.env } else { @{} }
    $results.Add((Invoke-BoundedHostCheck -Id $check.id -WorkingDirectory $check.cwd -Executable $check.exe -Arguments $check.argv -EvidenceDirectory $evidenceDirectory -DeadlineSeconds $TimeoutSeconds -Environment $environment))
}

$document = [ordered]@{
    schema = "rusty.quest.embedded_duplex_media_host_evidence.v1"
    status = "passed"
    created_at = [DateTime]::UtcNow.ToString("o")
    quest_source = $quest
    manifold_source = $manifold
    required_quest_ancestors = @($ExpectedQuestAncestor, $ExpectedConsumerAncestor)
    repository_map = $mapEvidence
    capabilities = @(
        "deterministic-seven-family-placement", "authenticated-owner-dispatch", "current-broker-join",
        "strict-effect-readback", "reverse-compensation", "bounded-parser-and-transport",
        "pre-render-exact-pts-registration", "decoder-generation-fencing", "authorized-cleanup-lineages",
        "app-source-routing-owner-lifecycle"
    )
    checks = @($results)
}
$temp = "$outFull.$([guid]::NewGuid().ToString('N')).tmp"
try {
    Write-Utf8NoBomCreateNew $temp ($document | ConvertTo-Json -Depth 16)
    [IO.File]::Move($temp, $outFull)
} finally { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }

Get-Content -Raw -LiteralPath $outFull
