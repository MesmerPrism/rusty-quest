[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet(
        "Prerequisites", "Build", "Inspect", "Install", "Start", "Status", "Stop", "Forget",
        "DebugProtocolProof", "RestartProcess", "WifiRebindE2E", "LaunchSpatial", "LaunchSample", "StopSpatial", "StopSample",
        "WaitSurface", "WaitSurfaceAbsent", "StopProviders", "HostessStatus", "HostessPair",
        "HostessList", "HostessWatch", "HostessCommand", "HostessReconnect",
        "HostessRevoke", "BrowserE2E", "Logs", "Cleanup", "E2E", "SimulateE2E")]
    [string]$Action,

    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{3,63}$')]
    [string]$Serial,

    [string]$EvidenceRoot = "",
    [string]$FileManagerCli = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$FileManagerSha256 = "",
    [string]$Gradle = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$GradleSha256 = "",
    [string]$Keystore = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$ExpectedSignerSha256 = "",
    [string]$HubManifoldSourceRoot = "",
    [string]$SpatialManifoldSourceRoot = "",
    [string]$HubApk = "",
    [string]$SpatialProviderApk = "",
    [string]$SampleProviderApk = "",
    [string]$HostessCli = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$HostessCliSha256 = "",
    [string]$Python = "python",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$PythonSha256 = "",
    [string]$Node = "node",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$NodeSha256 = "",
    [string]$Adb = "adb",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$AdbSha256 = "",
    [string]$PlaywrightPackageJson = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$PlaywrightPackageJsonSha256 = "",
    [string]$BrowserExecutable = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$BrowserExecutableSha256 = "",
    [switch]$RequireBrowserE2E,
    [switch]$RequireWifiRebindE2E,
    [string]$Origin = "",
    [string]$SessionFile = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$ControllerIdentitySha256 = "",
    [switch]$PairingCodeStdin,
    [int]$PairingCodeFd = -1,
    [string]$SurfaceId = "",
    [string]$CommandId = "",
    [ValidateRange(1,300)]
    [int]$WatchSeconds = 10,
    [ValidateRange(100,5000)]
    [int]$LogcatLines = 2000,
    [ValidateRange(121,600)]
    [int]$ProviderLifetimeSeconds = 125,
    [ValidateSet("RetainCandidate", "PreserveAndRestore")]
    [string]$ExistingTargetPolicy = "RetainCandidate",
    [string]$ResumeCheckpoint = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$HubPackage = "io.github.mesmerprism.rustymanifold.broker"
$HubActivity = "$HubPackage/.ConnectionHubStartActivity"
$HubDebugAuthority = "$HubPackage.debug-connection-hub-control"
$SpatialPackage = "io.github.mesmerprism.rustyquest.spatial_video_control_example"
$SpatialActivity = "$SpatialPackage/io.github.mesmerprism.rustyquest.spatial_video_control.SpatialVideoControlActivity"
$SamplePackage = "io.github.mesmerprism.rustyquest.connection_hub_sample"
$SampleActivity = "$SamplePackage/.ConnectionHubSampleActivity"
$QfmLaunchGap = "qfm-69b02f1.launch-export-parser"
$QfmServiceGap = "qfm-missing-typed-connection-hub-service-action-v1"
$QfmLifecycleGap = "qfm-missing-typed-connection-hub-activity-action-v1"
$QfmStopGap = "qfm-missing-typed-package-stop-v1"
$QfmLogGap = "qfm-missing-bounded-logcat-v1"
$QfmDeviceStateGap = "qfm-readonly-device-state-v1"
$QfmPackageStateGap = "qfm-readonly-package-state-v1"
$QfmUninstallGap = "qfm-missing-typed-target-uninstall-v1"
$QfmWifiGap = "qfm-missing-typed-wifi-rebind-v1"
$ReceiptSchema = "rusty.quest.connection_hub.operator_receipt.v1"
$ManifestSchema = "rusty.quest.connection_hub.operator_evidence_manifest.v1"
$CheckpointSchema = "rusty.quest.connection_hub.operator_checkpoint.v1"
$ProtocolVectorPath = Join-Path $RepoRoot "apps\manifold-broker-android\contracts\connection-hub-protocol-v1.json"
$script:Receipts = [System.Collections.Generic.List[string]]::new()
$script:ProviderLocks = [System.Collections.Generic.List[System.IDisposable]]::new()
$script:HubStarted = $false
$script:ProvidersLaunched = $false
$script:HostessPaired = $false
$script:Checkpoint = $null
$script:CheckpointPath = ""
$script:CachedArtifacts = $null
$script:CommandReceiptOrdinal = 0
$script:TargetMutationStarted = $false

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-TextSha256([string]$Value) {
    [byte[]]$bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    try {
        return ([BitConverter]::ToString([Security.Cryptography.SHA256]::HashData($bytes))).Replace("-", "").ToLowerInvariant()
    } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Assert-ExactFile([string]$Path, [string]$ExpectedSha256, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label path is required and must name one file."
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $actual = Get-Sha256 $resolved
    if ($actual -ne $ExpectedSha256) {
        throw "$Label SHA-256 mismatch: expected $ExpectedSha256, observed $actual"
    }
    return $resolved
}

function Lock-ExactProvider([string]$Path, [string]$ExpectedSha256, [string]$Label) {
    $resolved = Assert-ExactFile $Path $ExpectedSha256 $Label
    $lock = [System.IO.File]::Open(
        $resolved,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read)
    [void]$script:ProviderLocks.Add($lock)
    return $resolved
}

function Lock-ExactExecutable([string]$CommandOrPath, [string]$ExpectedSha256, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($ExpectedSha256)) { throw "$Label SHA-256 pin is required." }
    $resolved = if (Test-Path -LiteralPath $CommandOrPath -PathType Leaf) {
        (Resolve-Path -LiteralPath $CommandOrPath).Path
    } else {
        (Get-Command $CommandOrPath -CommandType Application -ErrorAction Stop).Source
    }
    return Lock-ExactProvider $resolved $ExpectedSha256 $Label
}

function Write-JsonFile([string]$Path, $Value) {
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $temporary = "$Path.tmp"
    [System.IO.File]::WriteAllText(
        $temporary,
        ($Value | ConvertTo-Json -Depth 30),
        (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Save-Receipt([string]$Name, $Value) {
    $path = Join-Path $script:RunDir "$Name.json"
    Write-JsonFile $path $Value
    [void]$script:Receipts.Add($path)
    return $Value
}

function New-Receipt([string]$Operation, [string]$Provider, [string]$Status, $Details) {
    return [ordered]@{
        '$schema' = $ReceiptSchema
        operation = $Operation
        provider = $Provider
        serial = $Serial
        status = $Status
        observed_at_utc = [DateTime]::UtcNow.ToString("o")
        secrets_in_receipt = $false
        details = $Details
    }
}

function Initialize-Checkpoint {
    $protocolSha = Get-Sha256 $ProtocolVectorPath
    if (-not [string]::IsNullOrWhiteSpace($ResumeCheckpoint)) {
        $resolved = (Resolve-Path -LiteralPath $ResumeCheckpoint).Path
        $evidencePrefix = $resolvedEvidenceRoot + '\'
        if (-not $resolved.StartsWith($evidencePrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Resume checkpoint must be inside the exact EvidenceRoot."
        }
        $checkpoint = Get-Content -Raw -LiteralPath $resolved | ConvertFrom-Json
        if ([string]$checkpoint.'$schema' -ne $CheckpointSchema -or
                [string]$checkpoint.serial -ne $Serial -or
                [string]$checkpoint.protocol_vectors_sha256 -ne $protocolSha -or
                [string]$checkpoint.existing_target_policy -ne $ExistingTargetPolicy -or
                [string]$checkpoint.adb_sha256 -ne [string]$script:AdbPin -or
                [string]$checkpoint.python_sha256 -ne [string]$script:PythonPin -or
                [string]$checkpoint.node_sha256 -ne [string]$script:NodePin -or
                [string]$checkpoint.gradle_sha256 -ne [string]$script:GradlePin -or
                [string]$checkpoint.adb_version -ne [string]$script:AdbVersion -or
                [string]$checkpoint.python_version -ne [string]$script:PythonVersion -or
                [string]$checkpoint.node_version -ne [string]$script:NodeVersion -or
                [string]$checkpoint.gradle_version -ne [string]$script:GradleVersion) {
            throw "Resume checkpoint identity/protocol/policy mismatch."
        }
        if (-not [string]::IsNullOrWhiteSpace($FileManagerSha256) -and
                [string]$checkpoint.file_manager_sha256 -ne $FileManagerSha256) {
            throw "Resume checkpoint File Manager hash mismatch."
        }
        if (-not [string]::IsNullOrWhiteSpace($HostessCliSha256) -and
                [string]$checkpoint.hostess_sha256 -ne $HostessCliSha256) {
            throw "Resume checkpoint Hostess hash mismatch."
        }
        $script:Checkpoint = $checkpoint
        $script:CheckpointPath = $resolved
        $script:RunDir = (Resolve-Path -LiteralPath ([string]$checkpoint.run_directory)).Path
        if (-not $script:RunDir.StartsWith($evidencePrefix, [StringComparison]::OrdinalIgnoreCase) -or
                $script:CheckpointPath -ne (Join-Path $script:RunDir "checkpoint.json")) {
            throw "Resume checkpoint run directory escaped EvidenceRoot or was substituted."
        }
        $artifactPrefix = (Join-Path $script:RunDir "artifacts") + '\'
        if ($checkpoint.artifacts) {
            $script:CachedArtifacts = @($checkpoint.artifacts | ForEach-Object {
                $artifactPath = [System.IO.Path]::GetFullPath([string]$_.path)
                if (-not $artifactPath.StartsWith($artifactPrefix, [StringComparison]::OrdinalIgnoreCase) -or
                        -not (Test-Path -LiteralPath $artifactPath -PathType Leaf) -or
                        (Get-Sha256 $artifactPath) -ne [string]$_.sha256) {
                    throw "Resume checkpoint artifact digest mismatch: $($_.label)"
                }
                [ordered]@{ label=[string]$_.label; path=$artifactPath; sha256=[string]$_.sha256; size=[long]$_.size }
            })
        }
        foreach ($receipt in Get-ChildItem -LiteralPath $script:RunDir -Filter *.json -File) {
            if ($receipt.FullName -ne $script:CheckpointPath) {
                try {
                    $candidate = Get-Content -Raw -LiteralPath $receipt.FullName | ConvertFrom-Json
                    if ([string]$candidate.'$schema' -eq $ReceiptSchema) {
                        [void]$script:Receipts.Add($receipt.FullName)
                    }
                } catch { }
            }
        }
        return
    }
    $script:CheckpointPath = Join-Path $script:RunDir "checkpoint.json"
    $script:Checkpoint = [ordered]@{
        '$schema' = $CheckpointSchema
        serial = $Serial
        run_directory = $script:RunDir
        existing_target_policy = $ExistingTargetPolicy
        protocol_vectors_sha256 = $protocolSha
        file_manager_sha256 = $FileManagerSha256
        hostess_sha256 = $HostessCliSha256
        adb_sha256 = $script:AdbPin
        adb_version = $script:AdbVersion
        python_sha256 = $script:PythonPin
        python_version = $script:PythonVersion
        node_sha256 = $script:NodePin
        node_version = $script:NodeVersion
        gradle_sha256 = $script:GradlePin
        gradle_version = $script:GradleVersion
        artifact_build_manifest_sha256 = $null
        artifacts = @()
        completed_stages = @()
        updated_at_utc = [DateTime]::UtcNow.ToString("o")
        secrets_in_checkpoint = $false
    }
    Write-Checkpoint
}

function Write-Checkpoint {
    $script:Checkpoint.updated_at_utc = [DateTime]::UtcNow.ToString("o")
    Write-JsonFile $script:CheckpointPath $script:Checkpoint
}

function Set-CheckpointArtifacts($Artifacts) {
    $script:CachedArtifacts = @($Artifacts)
    $script:Checkpoint.artifacts = @($Artifacts | ForEach-Object {
        [ordered]@{ label=$_.label; path=$_.path; sha256=$_.sha256; size=$_.size }
    })
    $manifestPath = Join-Path $RepoRoot "target\connection-hub-debug\build-manifest.json"
    if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
        $script:Checkpoint.artifact_build_manifest_sha256 = Get-Sha256 $manifestPath
    }
    Write-Checkpoint
}

function Invoke-Stage([string]$Name, [scriptblock]$Body) {
    if (@($script:Checkpoint.completed_stages) -contains $Name) {
        return
    }
    . $Body
    $script:Checkpoint.completed_stages = @($script:Checkpoint.completed_stages) + $Name
    Write-Checkpoint
}

function Invoke-Captured([string]$File, [string[]]$Arguments, [string]$Label) {
    $stderrPath = Join-Path ([System.IO.Path]::GetTempPath()) ("hub-cli-stderr-" + [Guid]::NewGuid().ToString("N"))
    try {
        $stdout = @(& $File @Arguments 2> $stderrPath)
        $exitCode = $LASTEXITCODE
        $stdoutText = $stdout -join "`n"
        $stderrText = if (Test-Path -LiteralPath $stderrPath) { [System.IO.File]::ReadAllText($stderrPath) } else { "" }
        return [ordered]@{
            label = $Label
            exit_code = $exitCode
            output = $stdoutText
            stderr = $stderrText
            combined = (($stdoutText, $stderrText) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
        }
    } finally {
        if (Test-Path -LiteralPath $stderrPath) { [System.IO.File]::Delete($stderrPath) }
    }
}

function Invoke-Qfm([string[]]$Arguments, [string]$Label, [switch]$AllowFailure) {
    if ((Get-Sha256 $script:Qfm) -ne $FileManagerSha256) {
        throw "File Manager changed after the run lock was acquired."
    }
    $result = Invoke-Captured $script:Qfm $Arguments $Label
    if ($result.exit_code -ne 0 -and -not $AllowFailure) {
        throw "$Label failed with exit code $($result.exit_code): $($result.combined)"
    }
    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($result.output)) {
        try { $parsed = $result.output | ConvertFrom-Json } catch { }
    }
    $result["json"] = $parsed
    return $result
}

function Invoke-Adb([string[]]$Arguments, [string]$GapId, [string]$Goal, [switch]$AllowFailure) {
    if ((Get-Sha256 $script:Adb) -ne $AdbSha256) { throw "ADB changed after the run lock was acquired." }
    $all = @("-s", $Serial) + $Arguments
    $result = Invoke-Captured $script:Adb $all "serial-scoped ADB fallback"
    if ($result.exit_code -ne 0 -and -not $AllowFailure) {
        throw "ADB fallback failed for $Goal with exit code $($result.exit_code)."
    }
    return [ordered]@{
        provider = "raw-adb-fallback"
        provider_gap = $GapId
        goal = $Goal
        stop_condition = "one fixed action and fresh readback"
        cleanup = "target-package-only"
        command_shape = @("adb", "-s", "<explicit-serial>") + $Arguments
        exit_code = $result.exit_code
        output = $result.output
        owner_acceptance_claimed = $false
    }
}

function Get-DebugPairingSecret {
    # This is deliberately separate from Invoke-Adb and Save-Receipt. The
    # one-use wearer code exists only in zeroed process buffers and is never
    # written to argv, a temporary file, a receipt, a log, or the manifest.
    if ((Get-Sha256 $script:Adb) -ne $AdbSha256) { throw "ADB changed after the run lock was acquired." }
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:Adb
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @(
        "-s", $Serial, "shell", "content", "call", "--uri",
        "content://$HubDebugAuthority", "--method", "pair-code")) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    [char[]]$stdout = New-Object char[] 4096
    [char[]]$stderr = New-Object char[] 1024
    [byte[]]$decoded = $null
    try {
        if (-not $process.Start()) { throw "Unable to start the dedicated pairing-secret transport." }
        $stdoutCount = $process.StandardOutput.ReadBlock($stdout, 0, $stdout.Length)
        $stderrCount = $process.StandardError.ReadBlock($stderr, 0, $stderr.Length)
        $process.WaitForExit(12000)
        if (-not $process.HasExited) { $process.Kill($true); throw "Pairing-secret transport timed out." }
        if ($process.ExitCode -ne 0 -or $stderrCount -gt 0) { throw "Pairing-secret transport failed closed." }
        [char[]]$prefix = "secret_b64=".ToCharArray()
        $startIndex = -1
        for ($i = 0; $i -le $stdoutCount - $prefix.Length; $i++) {
            $equal = $true
            for ($j = 0; $j -lt $prefix.Length; $j++) {
                if ($stdout[$i + $j] -ne $prefix[$j]) { $equal = $false; break }
            }
            if ($equal) { $startIndex = $i + $prefix.Length; break }
        }
        if ($startIndex -lt 0) { throw "Pairing-secret field is missing." }
        $length = 0
        while ($startIndex + $length -lt $stdoutCount -and
                $stdout[$startIndex + $length] -match '[A-Za-z0-9+/=]') { $length++ }
        if ($length -lt 8 -or $length -gt 16) { throw "Pairing-secret encoding is out of bounds." }
        $decoded = [Convert]::FromBase64CharArray($stdout, $startIndex, $length)
        if ($decoded.Length -ne 6) { throw "Pairing-secret length is invalid." }
        [char[]]$secret = New-Object char[] 6
        for ($i = 0; $i -lt 6; $i++) {
            if ($decoded[$i] -lt 48 -or $decoded[$i] -gt 57) { throw "Pairing-secret alphabet is invalid." }
            $secret[$i] = [char]$decoded[$i]
        }
        return $secret
    } finally {
        if ($null -ne $decoded) { [Array]::Clear($decoded, 0, $decoded.Length) }
        [Array]::Clear($stdout, 0, $stdout.Length)
        [Array]::Clear($stderr, 0, $stderr.Length)
        $process.Dispose()
    }
}

function Stage-Apk([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label APK is missing: $Path" }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $sha = Get-Sha256 $resolved
    $target = Join-Path $script:RunDir "artifacts\$sha.apk"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        Copy-Item -LiteralPath $resolved -Destination $target
    }
    if ((Get-Sha256 $target) -ne $sha) { throw "$Label staged APK digest mismatch." }
    (Get-Item -LiteralPath $target).IsReadOnly = $true
    return [ordered]@{ label = $Label; path = $target; sha256 = $sha; size = (Get-Item $target).Length }
}

function Resolve-Apks {
    if ($null -ne $script:CachedArtifacts) {
        [object[]]$cached = @($script:CachedArtifacts)
        if ($cached.Count -ne 3 -or ($cached.label -join "|") -ne "hub|spatial-provider|sample-provider") {
            throw "Checkpoint must contain the exact three-artifact Hub/provider set."
        }
        foreach ($artifact in $cached) {
            if ((Get-Sha256 $artifact.path) -ne $artifact.sha256) {
                throw "Checkpoint artifact changed: $($artifact.label)"
            }
        }
        return $cached
    }
    $hub = if ($HubApk) { $HubApk } else { Join-Path $RepoRoot "target\connection-hub-debug\rusty-manifold-broker.apk" }
    $spatial = if ($SpatialProviderApk) { $SpatialProviderApk } else { Join-Path $RepoRoot "apps\spatial-video-control-example-android\app\build\outputs\apk\debug\app-debug.apk" }
    $sample = if ($SampleProviderApk) { $SampleProviderApk } else { Join-Path $RepoRoot "apps\spatial-video-control-example-android\hub-sample-provider\build\outputs\apk\debug\hub-sample-provider-debug.apk" }
    [object[]]$artifacts = @(
        (Stage-Apk -Path $hub -Label "hub")
        (Stage-Apk -Path $spatial -Label "spatial-provider")
        (Stage-Apk -Path $sample -Label "sample-provider"))
    if ($artifacts.Count -ne 3 -or ($artifacts.label -join "|") -ne "hub|spatial-provider|sample-provider") {
        throw "Build must resolve the exact three-artifact Hub/provider set."
    }
    if ($null -ne $script:Checkpoint) { Set-CheckpointArtifacts $artifacts }
    return $artifacts
}

function Build-All {
    if (-not (Test-Path -LiteralPath $HubManifoldSourceRoot -PathType Container)) {
        throw "-HubManifoldSourceRoot must identify the exact clean Hub Manifold source."
    }
    if (-not (Test-Path -LiteralPath $SpatialManifoldSourceRoot -PathType Container)) {
        throw "-SpatialManifoldSourceRoot must identify the exact clean spatial-control Manifold source."
    }
    if ((Get-Sha256 $script:Gradle) -ne $GradleSha256) { throw "Gradle changed after the run lock was acquired." }
    if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
        throw "-Keystore must identify the one explicit signing keystore shared by all three APKs."
    }
    $resolvedKeystore = (Resolve-Path -LiteralPath $Keystore).Path
    $version = Invoke-Captured $script:Gradle @("--version") "Gradle version"
    if ($version.exit_code -ne 0 -or $version.output -notmatch 'Gradle 8\.13') {
        throw "Connection Hub build requires exact Gradle 8.13."
    }
    $hubRoot = (Resolve-Path -LiteralPath $HubManifoldSourceRoot).Path
    $spec = Join-Path $hubRoot "fixtures\broker-product\connection-hub-standalone.json"
    $lock = Join-Path $hubRoot "fixtures\broker-product\connection-hub-standalone.lock.json"
    & (Join-Path $RepoRoot "tools\Build-ManifoldBrokerAndroid.ps1") `
        -OutDir (Join-Path $RepoRoot "target\connection-hub-debug") `
        -ProductSpecPath $spec `
        -ProductLockPath $lock `
        -ManifoldSourceRoot $hubRoot `
        -Keystore $resolvedKeystore `
        -EnableConnectionHubDebugOperator
    if ($LASTEXITCODE -ne 0) { throw "Hub APK build failed." }
    $previousManifold = $env:RUSTY_MANIFOLD_SOURCE_ROOT
    $previousConnectionHubKeystore = $env:RUSTY_CONNECTION_HUB_KEYSTORE
    try {
        $env:RUSTY_MANIFOLD_SOURCE_ROOT = (Resolve-Path -LiteralPath $SpatialManifoldSourceRoot).Path
        $env:RUSTY_CONNECTION_HUB_KEYSTORE = $resolvedKeystore
        Push-Location (Join-Path $RepoRoot "apps\spatial-video-control-example-android")
        try {
            & $script:Gradle :app:assembleDebug :hub-sample-provider:assembleDebug --no-daemon
            if ($LASTEXITCODE -ne 0) { throw "Provider APK build failed." }
        } finally { Pop-Location }
    } finally {
        $env:RUSTY_MANIFOLD_SOURCE_ROOT = $previousManifold
        $env:RUSTY_CONNECTION_HUB_KEYSTORE = $previousConnectionHubKeystore
    }
    $manifest = Get-Content -Raw (Join-Path $RepoRoot "target\connection-hub-debug\build-manifest.json") | ConvertFrom-Json
    if ($manifest.connection_hub_debug_operator -ne $true) { throw "Debug Hub build omitted its shell operator route." }
    return Save-Receipt "build" (New-Receipt "build" "project-build" "passed" ([ordered]@{
        hub_build_manifest_sha256 = Get-Sha256 (Join-Path $RepoRoot "target\connection-hub-debug\build-manifest.json")
        gradle_version = "8.13"
        keystore_sha256 = Get-Sha256 $resolvedKeystore
        device_touched = $false
    }))
}

function Inspect-All($Artifacts) {
    [object[]]$Artifacts = @($Artifacts)
    if ($Artifacts.Count -ne 3 -or ($Artifacts.label -join "|") -ne "hub|spatial-provider|sample-provider") {
        throw "Inspection requires the exact three-artifact Hub/provider set."
    }
    $rows = @()
    $signers = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($artifact in $Artifacts) {
        $response = Invoke-Qfm @("apk", "inspect", "--file", $artifact.path, "--json") "inspect $($artifact.label)"
        if ($null -eq $response.json -or ([string]$response.json.Sha256).ToLowerInvariant() -ne $artifact.sha256) {
            throw "File Manager inspection did not bind $($artifact.label) to its staged SHA-256."
        }
        $signer = ([string]$response.json.Identity.SignerSha256).ToLowerInvariant()
        if ($signer -notmatch '^[0-9a-f]{64}$') { throw "File Manager returned an invalid signer for $($artifact.label)." }
        [void]$signers.Add($signer)
        $rows += [ordered]@{ label=$artifact.label; sha256=$artifact.sha256; signer_sha256=$signer; identity=$response.json.Identity }
    }
    if ($signers.Count -ne 1) { throw "Signature Binder permission would fail: the three APK signers differ." }
    $sharedSigner = @($signers)[0]
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSignerSha256) -and $sharedSigner -ne $ExpectedSignerSha256) {
        throw "Shared APK signer does not match -ExpectedSignerSha256."
    }
    return Save-Receipt "inspect" (New-Receipt "inspect" "questionable-file-manager" "passed" $rows)
}

function Get-DeviceInvariantSnapshot {
    $stay = Invoke-Adb @("shell", "settings", "get", "global", "stay_on_while_plugged_in") $QfmDeviceStateGap "read stay-awake setting"
    $timeout = Invoke-Adb @("shell", "settings", "get", "system", "screen_off_timeout") $QfmDeviceStateGap "read autosleep timeout"
    $power = Invoke-Adb @("shell", "dumpsys", "power") $QfmDeviceStateGap "read power/proximity state"
    $forwards = Invoke-Captured $script:Adb @("-s", $Serial, "forward", "--list") "read serial-scoped forwards"
    if ($forwards.exit_code -ne 0) { throw "Unable to read ADB forwards." }
    $proximityMatch = [regex]::Match($power.output, '(?im)^\s*mProximityPositive=(true|false)\s*$')
    return [ordered]@{
        stay_on_while_plugged_in=$stay.output.Trim()
        screen_off_timeout=$timeout.output.Trim()
        proximity_positive=$(if($proximityMatch.Success){$proximityMatch.Groups[1].Value}else{"unreported"})
        forwards_sha256=Get-TextSha256 $forwards.output
    }
}

function Capture-PreState($Artifacts) {
    $rows = @()
    $targets = @(
        [ordered]@{label="hub";package=$HubPackage;artifact=$Artifacts[0]},
        [ordered]@{label="spatial-provider";package=$SpatialPackage;artifact=$Artifacts[1]},
        [ordered]@{label="sample-provider";package=$SamplePackage;artifact=$Artifacts[2]})
    foreach ($target in $targets) {
        $pathRead = Invoke-Adb @("shell", "pm", "path", $target.package) $QfmPackageStateGap "read one fixed target package" -AllowFailure
        $installed = $pathRead.exit_code -eq 0 -and $pathRead.output -match '^package:'
        $runningRead = Invoke-Adb @("shell", "pidof", $target.package) $QfmPackageStateGap "read one fixed target process" -AllowFailure
        $row = [ordered]@{
            label=$target.label
            package=$target.package
            installed=$installed
            running=($runningRead.exit_code -eq 0 -and -not [string]::IsNullOrWhiteSpace($runningRead.output))
            candidate_sha256=$target.artifact.sha256
            retained_apk=$null
            retained_apk_sha256=$null
            retained_identity=$null
        }
        if ($installed) {
            $retained = Join-Path $script:RunDir ("pre-state\{0}.apk" -f $target.label)
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $retained) | Out-Null
            [void](Invoke-Qfm @("apk", "export", "--serial", $Serial, "--package", $target.package, "--output", $retained, "--overwrite", "--json") "export pre-existing $($target.label)")
            $retainedSha = Get-Sha256 $retained
            $inspection = Invoke-Qfm @("apk", "inspect", "--file", $retained, "--json") "inspect pre-existing $($target.label)"
            if (([string]$inspection.json.Sha256).ToLowerInvariant() -ne $retainedSha) {
                throw "Pre-existing $($target.label) export inspection mismatch."
            }
            $row.retained_apk=$retained
            $row.retained_apk_sha256=$retainedSha
            $row.retained_identity=$inspection.json.Identity
        }
        $rows += $row
    }
    $script:PreState = [ordered]@{
        existing_target_policy=$ExistingTargetPolicy
        packages=$rows
        device_invariants=Get-DeviceInvariantSnapshot
    }
    $receipt = Save-Receipt "pre-state" (New-Receipt "pre-state" "qfm+serial-readback" "passed" $script:PreState)
    $script:Checkpoint.pre_state_receipt_sha256 = Get-Sha256 (Join-Path $script:RunDir "pre-state.json")
    Write-Checkpoint
    if ($ExistingTargetPolicy -eq "PreserveAndRestore" -and $rows[0].running) {
        [void](Save-Receipt "pre-state-restoration-preflight" (New-Receipt "pre-state-restoration-preflight" "operator-wrapper" "failed" ([ordered]@{
            reason="preexisting_running_hub_lifecycle_contract_unproven"
            mutation_started=$false
            supported_resolution="stop the prior Hub before the run or use RetainCandidate"
        })))
        throw "PreserveAndRestore cannot prove restart of a pre-existing running Hub with an unknown lifecycle contract."
    }
    return $receipt
}

function Restore-PreState($Artifacts) {
    if ($null -eq $script:PreState) {
        $prePath = Join-Path $script:RunDir "pre-state.json"
        if (-not (Test-Path -LiteralPath $prePath -PathType Leaf)) { throw "Pre-state receipt missing." }
        $script:PreState = (Get-Content -Raw -LiteralPath $prePath | ConvertFrom-Json).details
    }
    [void](Stop-Providers)
    try { [void](Hub-Action "stop") } catch { }
    $rows = @()
    for ($index = 0; $index -lt 3; $index++) {
        $prior = $script:PreState.packages[$index]
        $artifact = $Artifacts[$index]
        if ($ExistingTargetPolicy -eq "PreserveAndRestore") {
            if ($prior.installed) {
                if ((Get-Sha256 $prior.retained_apk) -ne [string]$prior.retained_apk_sha256) {
                    throw "Retained pre-state APK changed: $($prior.label)"
                }
                [void](Invoke-Qfm @("apk", "install", "--serial", $Serial, "--file", $prior.retained_apk, "--downgrade", "--json") "restore $($prior.label)")
                $observed = Invoke-Qfm @("apk", "observe", "--serial", $Serial, "--file", $prior.retained_apk, "--json") "observe restored $($prior.label)"
                if ($observed.output.ToLowerInvariant().IndexOf([string]$prior.retained_apk_sha256) -lt 0) {
                    throw "Restored $($prior.label) did not match pre-state bytes."
                }
            } else {
                [void](Invoke-Adb @("shell", "pm", "uninstall", [string]$prior.package) $QfmUninstallGap "remove only a run-installed target package")
            }
        }
        if ($prior.running -and ($ExistingTargetPolicy -eq "RetainCandidate" -or $prior.installed)) {
            if ($index -eq 0) { [void](Hub-Action "start" $artifact) }
            else {
                $launchArtifact = if ($ExistingTargetPolicy -eq "PreserveAndRestore") {
                    [ordered]@{label=$prior.label;path=[string]$prior.retained_apk;sha256=[string]$prior.retained_apk_sha256}
                } else { $artifact }
                if ($index -eq 1) { [void](Launch-Apk $launchArtifact $SpatialActivity) }
                else { [void](Launch-Apk $launchArtifact $SampleActivity) }
            }
        } elseif (-not $prior.running) {
            [void](Invoke-Adb @("shell", "am", "force-stop", [string]$prior.package) $QfmStopGap "restore one target's stopped state")
        }
        $rows += [ordered]@{package=$prior.package;policy=$ExistingTargetPolicy;prior_running=$prior.running;prior_installed=$prior.installed}
    }
    $after = Get-DeviceInvariantSnapshot
    foreach ($name in @("stay_on_while_plugged_in", "screen_off_timeout", "proximity_positive", "forwards_sha256")) {
        if ([string]$after[$name] -ne [string]$script:PreState.device_invariants.$name) {
            throw "Device invariant changed during run: $name"
        }
    }
    return Save-Receipt "restore-pre-state" (New-Receipt "restore-pre-state" "target-only-cleanup" "passed" ([ordered]@{
        packages=$rows
        device_invariants_restored=$true
        unrelated_packages_touched=$false
    }))
}

function Install-All($Artifacts) {
    [object[]]$Artifacts = @($Artifacts)
    if ($Artifacts.Count -ne 3 -or ($Artifacts.label -join "|") -ne "hub|spatial-provider|sample-provider") {
        throw "Installation requires the exact three-artifact Hub/provider set."
    }
    $script:TargetMutationStarted = $true
    $rows = @()
    foreach ($artifact in $Artifacts) {
        $install = Invoke-Qfm @("apk", "install", "--serial", $Serial, "--file", $artifact.path, "--downgrade", "--grant-runtime-permissions", "--json") "install $($artifact.label)"
        $observe = Invoke-Qfm @("apk", "observe", "--serial", $Serial, "--file", $artifact.path, "--json") "observe $($artifact.label)"
        if ($observe.output.ToLowerInvariant().IndexOf($artifact.sha256) -lt 0) {
            throw "Installed-byte readback did not repeat $($artifact.label) SHA-256."
        }
        $rows += [ordered]@{ label=$artifact.label; sha256=$artifact.sha256; install=$install.json; observe=$observe.json }
    }
    return Save-Receipt "install" (New-Receipt "install" "questionable-file-manager" "passed" $rows)
}

function Launch-Apk($Artifact, [string]$Component) {
    $launch = Invoke-Qfm @("apk", "launch", "--serial", $Serial, "--file", $Artifact.path, "--json") "launch $($Artifact.label)" -AllowFailure
    if ($launch.exit_code -eq 0) {
        return [ordered]@{ label=$Artifact.label; provider="questionable-file-manager"; receipt=$launch.json }
    }
    if ($launch.combined -notmatch 'resolved launcher activity was not proven exported') {
        throw "File Manager launch failed outside the reviewed export-parser gap: $($launch.combined)"
    }
    $fallback = Invoke-Adb @("shell", "am", "start", "-W", "-n", $Component) $QfmLaunchGap "launch one fixed reviewed component"
    return [ordered]@{ label=$Artifact.label; provider="raw-adb-fallback"; fallback=$fallback }
}

function Invoke-DebugOperator([string]$Method) {
    $fallback = Invoke-Adb @(
        "shell", "content", "call", "--uri", "content://$HubDebugAuthority", "--method", $Method) `
        $QfmServiceGap "invoke one DUMP-protected debug Hub method"
    $match = [regex]::Match($fallback.output, 'receipt_b64=([A-Za-z0-9+/=]+)')
    if (-not $match.Success) { throw "Hub debug operator receipt is missing." }
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($match.Groups[1].Value)) | ConvertFrom-Json
    if ([string]$json.'$schema' -ne "rusty.quest.connection_hub.debug_operator_receipt.v1" -or $json.pairing_secret_in_receipt -ne $false) {
        throw "Hub debug operator receipt failed schema or secret-redaction validation."
    }
    return [ordered]@{ provider_gap=$QfmServiceGap; owner_receipt=$json }
}

function Hub-Action([string]$Method, $HubArtifact = $null) {
    $lifecycleActions = @{
        start = "io.github.mesmerprism.rustymanifold.broker.action.DEBUG_START_CONNECTION_HUB"
        stop = "io.github.mesmerprism.rustymanifold.broker.action.DEBUG_STOP_CONNECTION_HUB"
        forget = "io.github.mesmerprism.rustymanifold.broker.action.DEBUG_FORGET_CONNECTION_HUB"
    }
    if ($Method -in @("start", "stop", "forget")) {
        [void](Invoke-Adb @("shell", "am", "start", "-W", "-n", $HubActivity, "-a", $lifecycleActions[$Method]) `
            $QfmLifecycleGap "dispatch one fixed debug Activity action through the real foreground-service lifecycle")
    }
    $result = Invoke-DebugOperator "status"
    if ($Method -eq "start") {
        $deadline = [DateTime]::UtcNow.AddSeconds(12)
        while ($result.owner_receipt.listener_running -ne $true -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 400
            $result = Invoke-DebugOperator "status"
        }
        if ($result.owner_receipt.listener_running -ne $true) { throw "Hub listener did not confirm running." }
        $service = Invoke-Adb @("shell", "dumpsys", "activity", "services", $HubPackage) `
            $QfmServiceGap "read exact Hub foreground-service state"
        $notification = Invoke-Adb @("shell", "dumpsys", "notification", "--noredact") `
            $QfmServiceGap "read exact Hub foreground notification state"
        if ($service.output -notmatch 'ConnectionHubStartService' -or
                $notification.output -notmatch [regex]::Escape($HubPackage)) {
            throw "Real Hub foreground service or its notification was not observed."
        }
        [void](Save-Receipt "hub-real-service" (New-Receipt "hub-real-service" "android-foreground-service" "passed" ([ordered]@{
            service_present=$true
            notification_present=$true
            service_readback_sha256=Get-TextSha256 $service.output
            notification_readback_sha256=Get-TextSha256 $notification.output
            debug_provider_performed_lifecycle_mutation=$false
        })))
    } elseif ($Method -eq "stop") {
        $deadline = [DateTime]::UtcNow.AddSeconds(12)
        while ($result.owner_receipt.listener_running -ne $false -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 400
            $result = Invoke-DebugOperator "status"
        }
        if ($result.owner_receipt.listener_running -ne $false) { throw "Hub listener stop was not confirmed." }
    } elseif ($Method -eq "forget" -and [string]$result.owner_receipt.runtime_status -ne "applied") {
        throw "Hub forget was not observed as applied by Manifold."
    }
    if ($Method -eq "start") { $script:HubStarted = $true }
    if ($Method -eq "stop") { $script:HubStarted = $false }
    return Save-Receipt "hub-$Method" (New-Receipt "hub-$Method" "real-activity-foreground-service" "passed" ([ordered]@{
        lifecycle_transport_gap=$QfmLifecycleGap
        observer=$result
        debug_provider_performed_lifecycle_mutation=$false
    }))
}

function Prove-DebugProtocol {
    $start = Invoke-DebugOperator "start"
    if ($start.owner_receipt.listener_running -ne $true) { throw "Debug protocol start proof failed." }
    $status = Invoke-DebugOperator "status"
    $stop = Invoke-DebugOperator "stop"
    if ($status.owner_receipt.listener_running -ne $true -or $stop.owner_receipt.listener_running -ne $false) {
        throw "Debug protocol status/stop proof failed."
    }
    return Save-Receipt "debug-protocol-proof" (New-Receipt "debug-protocol-proof" "debug-shell-provider-gap" "passed" ([ordered]@{
        start=$start
        status=$status
        stop=$stop
        real_foreground_service_claimed=$false
    }))
}

function Restart-HubProcess {
    $before = Invoke-DebugOperator "status"
    if ($before.owner_receipt.listener_running -ne $true) { throw "Hub must be running before restart proof." }
    $oldPid = [int]$before.owner_receipt.pid
    $scheduled = Invoke-DebugOperator "restart-process"
    if ($scheduled.owner_receipt.process_restart_scheduled -ne $true -or [int]$scheduled.owner_receipt.pid -ne $oldPid) {
        throw "Debug process-death injection was not scheduled for the observed PID."
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(40)
    $newPid = 0
    do {
        Start-Sleep -Milliseconds 500
        $pidRead = Invoke-Adb @("shell", "pidof", $HubPackage) $QfmServiceGap "read restarted Hub PID" -AllowFailure
        if ($pidRead.exit_code -eq 0 -and $pidRead.output.Trim() -match '^\d+$') { $newPid=[int]$pidRead.output.Trim() }
    } while (($newPid -eq 0 -or $newPid -eq $oldPid) -and [DateTime]::UtcNow -lt $deadline)
    if ($newPid -eq 0 -or $newPid -eq $oldPid) { throw "START_STICKY did not produce a new Hub process." }
    $status = $null
    do {
        Start-Sleep -Milliseconds 500
        try { $status = Invoke-DebugOperator "status" } catch { $status=$null }
    } while (($null -eq $status -or $status.owner_receipt.listener_running -ne $true) -and [DateTime]::UtcNow -lt $deadline)
    if ($null -eq $status -or $status.owner_receipt.listener_running -ne $true -or
            [string]$status.owner_receipt.desired_connection_state -ne "running") {
        throw "Encrypted desired state did not restore the real Hub listener."
    }
    $service = Invoke-Adb @("shell", "dumpsys", "activity", "services", $HubPackage) $QfmServiceGap "read restarted FGS"
    $notification = Invoke-Adb @("shell", "dumpsys", "notification", "--noredact") $QfmServiceGap "read restarted notification"
    if ($service.output -notmatch 'ConnectionHubStartService' -or $notification.output -notmatch [regex]::Escape($HubPackage)) {
        throw "Restarted Hub FGS/notification was not observed."
    }
    return Save-Receipt "hub-process-restart" (New-Receipt "hub-process-restart" "debug-death+real-start-sticky" "passed" ([ordered]@{
        old_pid=$oldPid
        new_pid=$newPid
        pid_changed=$true
        listener_restored=$true
        desired_state_restored=$true
        foreground_service_restored=$true
        notification_restored=$true
        service_readback_sha256=Get-TextSha256 $service.output
        notification_readback_sha256=Get-TextSha256 $notification.output
    }))
}

function Invoke-WifiRebindE2E {
    if (-not $RequireWifiRebindE2E) {
        return Save-Receipt "wifi-rebind" (New-Receipt "wifi-rebind" "optional-device-provider" "not_run" ([ordered]@{
            ran=$false
            acceptance_claimed=$false
            not_run_safety_reason="opt-in not requested; Wi-Fi mutation and exact restoration were not authorized"
        }))
    }
    if ($Serial.Contains(":")) { throw "Wi-Fi rebind requires a non-TCP exact ADB transport." }
    $pre = Invoke-Adb @("shell", "settings", "get", "global", "wifi_on") $QfmWifiGap "read Wi-Fi pre-state"
    if ($pre.output.Trim() -ne "1") { throw "Wi-Fi rebind requires Wi-Fi initially enabled for exact restoration." }
    $before = Invoke-DebugOperator "status"
    $originBefore = [string]$before.owner_receipt.origin
    try {
        [void](Invoke-Adb @("shell", "svc", "wifi", "disable") $QfmWifiGap "disable Wi-Fi on an exact USB-connected Quest")
        Start-Sleep -Seconds 2
        [void](Invoke-Adb @("shell", "svc", "wifi", "enable") $QfmWifiGap "restore Wi-Fi on an exact USB-connected Quest")
        $deadline=[DateTime]::UtcNow.AddSeconds(45)
        $status=$null
        do {
            Start-Sleep -Seconds 1
            try { $status=Invoke-DebugOperator "status" } catch { $status=$null }
        } while (($null -eq $status -or $status.owner_receipt.listener_running -ne $true -or [string]$status.owner_receipt.origin -eq "http://0.0.0.0:0") -and [DateTime]::UtcNow -lt $deadline)
        if ($null -eq $status -or $status.owner_receipt.listener_running -ne $true) { throw "Hub did not rebind after Wi-Fi restoration." }
        return Save-Receipt "wifi-rebind" (New-Receipt "wifi-rebind" "exact-usb-transport" "passed" ([ordered]@{
            ran=$true
            wifi_pre_state=1
            wifi_restored_state=1
            origin_before=$originBefore
            origin_after=[string]$status.owner_receipt.origin
            listener_rebound=$true
        }))
    } finally {
        $current = Invoke-Adb @("shell", "settings", "get", "global", "wifi_on") $QfmWifiGap "verify Wi-Fi restoration" -AllowFailure
        if ($current.output.Trim() -ne "1") { [void](Invoke-Adb @("shell", "svc", "wifi", "enable") $QfmWifiGap "restore Wi-Fi pre-state") }
    }
}

function Launch-Provider($Artifacts, [string]$ProviderName) {
    if ($ProviderName -eq "spatial") {
        $row = Launch-Apk $Artifacts[1] $SpatialActivity
    } elseif ($ProviderName -eq "sample") {
        $row = Launch-Apk $Artifacts[2] $SampleActivity
    } else { throw "Unknown fixed provider." }
    $script:ProvidersLaunched = $true
    return Save-Receipt "launch-$ProviderName" (New-Receipt "launch-$ProviderName" "qfm-with-reviewed-fallback" "passed" $row)
}

function Stop-Provider([string]$ProviderName) {
    $package = if ($ProviderName -eq "spatial") { $SpatialPackage } elseif ($ProviderName -eq "sample") { $SamplePackage } else { throw "Unknown fixed provider." }
    $row = Invoke-Adb @("shell", "am", "force-stop", $package) $QfmStopGap "stop one fixed provider package"
    return Save-Receipt "stop-$ProviderName" (New-Receipt "stop-$ProviderName" "raw-adb-fallback" "passed" $row)
}

function Stop-Providers {
    $rows = @()
    foreach ($package in @($SpatialPackage, $SamplePackage)) {
        $rows += Invoke-Adb @("shell", "am", "force-stop", $package) $QfmStopGap "stop one fixed provider package"
    }
    $script:ProvidersLaunched = $false
    return Save-Receipt "stop-providers" (New-Receipt "stop-providers" "raw-adb-fallback" "passed" $rows)
}

function Invoke-Hostess([string]$Verb, [string[]]$Arguments, [string]$ReceiptName) {
    if ((Get-Sha256 $script:Hostess) -ne $HostessCliSha256) { throw "Hostess CLI changed after run lock." }
    if ((Get-Sha256 $script:Python) -ne $PythonSha256) { throw "Python changed after the run lock was acquired." }
    $result = Invoke-Captured $script:Python (@($script:Hostess, $Verb) + $Arguments) "Hostess $Verb"
    if ($result.exit_code -ne 0) { throw "Hostess $Verb failed: $($result.combined)" }
    $json = $result.output | ConvertFrom-Json
    if ($result.output -match '(?i)pairing_code|bearer_token' -and $result.output -notmatch 'secrets_in_receipt') {
        throw "Hostess output may contain an unredacted secret."
    }
    return Save-Receipt $ReceiptName (New-Receipt "hostess-$Verb" "rusty-hostess" "passed" $json)
}

function Read-HostessSurfaces {
    if ((Get-Sha256 $script:Hostess) -ne $HostessCliSha256) { throw "Hostess CLI changed after run lock." }
    if ((Get-Sha256 $script:Python) -ne $PythonSha256) { throw "Python changed after the run lock was acquired." }
    $result = Invoke-Captured $script:Python @($script:Hostess, "list-surfaces", "--session-file", $SessionFile) "Hostess list-surfaces"
    if ($result.exit_code -ne 0) { throw "Hostess list-surfaces failed: $($result.combined)" }
    return ($result.output | ConvertFrom-Json)
}

function Wait-Surface([string]$ExpectedSurfaceId, [bool]$Present) {
    if ($ExpectedSurfaceId -notin @("surface.spatial_video_control.media", "surface.connection_hub_sample.toggle")) {
        throw "Wait surface is not in the fixed registry."
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $snapshot = Read-HostessSurfaces
        $encoded = $snapshot | ConvertTo-Json -Depth 20 -Compress
        $seen = $encoded -match ('"surface_id"\s*:\s*"' + [regex]::Escape($ExpectedSurfaceId) + '"')
        if ($seen -eq $Present) {
            $suffix = if ($Present) { "present" } else { "absent" }
            $safeName = $ExpectedSurfaceId -replace '[^a-z0-9]+','-'
            return Save-Receipt "surface-$safeName-$suffix" (New-Receipt "wait-surface" "rusty-hostess" "passed" ([ordered]@{
                surface_id=$ExpectedSurfaceId
                expected_present=$Present
                observed_present=$seen
                snapshot=$snapshot
            }))
        }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for surface $ExpectedSurfaceId present=$Present."
}

function Invoke-HostessPairWithSecret([char[]]$Secret) {
    if ((Get-Sha256 $script:Hostess) -ne $HostessCliSha256) { throw "Hostess CLI changed after run lock." }
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:Python
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @(
        $script:Hostess, "pair", "--origin", $Origin,
        "--transport-classification", "trusted_lan_experimental",
        "--allow-insecure-trusted-lan", "--pairing-code-stdin",
        "--controller-identity-sha256", $ControllerIdentitySha256,
        "--session-file", $SessionFile)) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Unable to start Hostess pairing." }
        foreach ($character in $Secret) { $process.StandardInput.Write($character) }
        $process.StandardInput.WriteLine()
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit(30000)
        if (-not $process.HasExited) { $process.Kill($true); throw "Hostess pairing timed out." }
        if ($process.ExitCode -ne 0) { throw "Hostess pair failed: $stderr" }
        if ($stdout -match '"pairing_code"\s*:' -or $stdout -match '"bearer_token"\s*:') {
            throw "Hostess pair emitted a forbidden secret field."
        }
        $json = $stdout | ConvertFrom-Json
        if ($json.session_redacted -ne $true -or $null -ne $json.server_receipt.session) {
            throw "Hostess pair did not attest a redacted session receipt."
        }
        $script:HostessPaired = $true
        return Save-Receipt "hostess-pair" (New-Receipt "hostess-pair" "rusty-hostess" "passed" $json)
    } finally {
        [Array]::Clear($Secret, 0, $Secret.Length)
        $process.Dispose()
    }
}

function Invoke-BrowserE2EWithSecret([char[]]$Secret) {
    $packageJson = Assert-ExactFile $PlaywrightPackageJson $PlaywrightPackageJsonSha256 "Playwright package.json"
    $browser = Assert-ExactFile $BrowserExecutable $BrowserExecutableSha256 "Browser executable"
    $harness = Join-Path $RepoRoot "tools\browser\connection-hub-browser-e2e.js"
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:Node
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @(
        $harness, "--origin", $Origin, "--adb", $script:Adb, "--serial", $Serial,
        "--playwright-package-json", $packageJson, "--browser-executable", $browser)) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Unable to start browser E2E provider." }
        foreach ($character in $Secret) { $process.StandardInput.Write($character) }
        $process.StandardInput.WriteLine()
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit(120000)
        if (-not $process.HasExited) { $process.Kill($true); throw "Browser E2E timed out." }
        if ($process.ExitCode -ne 0) { throw "Browser E2E failed: $(Get-TextSha256 $stderr)" }
        $json = $stdout | ConvertFrom-Json
        if ([string]$json.'$schema' -ne "rusty.quest.connection_hub.browser_e2e_receipt.v1" -or
                [string]$json.result -ne "pass" -or $json.pairing_secret_in_receipt -ne $false -or
                [int]$json.console_error_count -ne 0 -or [int]$json.page_error_count -ne 0) {
            throw "Browser E2E receipt failed closed validation."
        }
        return Save-Receipt "browser-e2e" (New-Receipt "browser-e2e" "playwright-real-browser" "passed" ([ordered]@{
            provider_package_json_sha256=$PlaywrightPackageJsonSha256
            browser_executable_sha256=$BrowserExecutableSha256
            harness_sha256=Get-Sha256 $harness
            receipt=$json
            pairing_secret_in_receipt=$false
        }))
    } finally {
        [Array]::Clear($Secret, 0, $Secret.Length)
        $process.Dispose()
    }
}

function Hostess-Action([string]$Kind) {
    if ($Kind -eq "status") {
        return Invoke-Hostess "status" @("--origin", $Origin, "--transport-classification", "trusted_lan_experimental", "--allow-insecure-trusted-lan") "hostess-status"
    }
    if ($Kind -eq "pair") {
        $args = @("--origin", $Origin, "--transport-classification", "trusted_lan_experimental", "--allow-insecure-trusted-lan", "--controller-identity-sha256", $ControllerIdentitySha256, "--session-file", $SessionFile)
        if ($PairingCodeStdin) { $args += "--pairing-code-stdin" }
        elseif ($PairingCodeFd -ge 0) { $args += @("--pairing-code-fd", [string]$PairingCodeFd) }
        $receipt = Invoke-Hostess "pair" $args "hostess-pair"
        $script:HostessPaired = $true
        return $receipt
    }
    if ($Kind -eq "list") { return Invoke-Hostess "list-surfaces" @("--session-file", $SessionFile) "hostess-list" }
    if ($Kind -eq "watch") { return Invoke-Hostess "connect-watch" @("--session-file", $SessionFile, "--seconds", [string]$WatchSeconds, "--max-events", "128") "hostess-watch" }
    if ($Kind -eq "reconnect") {
        $before = Read-HostessSurfaces
        $after = Invoke-Hostess "list-surfaces" @("--session-file", $SessionFile) "hostess-reconnect"
        $beforeEpochs = @([regex]::Matches(($before | ConvertTo-Json -Depth 20 -Compress), '"transport_epoch"\s*:\s*(\d+)') | ForEach-Object { [long]$_.Groups[1].Value })
        $afterEpochs = @([regex]::Matches(($after | ConvertTo-Json -Depth 20 -Compress), '"transport_epoch"\s*:\s*(\d+)') | ForEach-Object { [long]$_.Groups[1].Value })
        if ($beforeEpochs.Count -eq 0 -or $afterEpochs.Count -eq 0 -or
                ($afterEpochs | Measure-Object -Maximum).Maximum -le ($beforeEpochs | Measure-Object -Maximum).Maximum) {
            throw "Reconnect did not prove a strictly newer transport epoch."
        }
        return $after
    }
    if ($Kind -eq "revoke") {
        $receipt = Invoke-Hostess "revoke" @("--session-file", $SessionFile) "hostess-revoke"
        $proof = $receipt.details
        foreach ($field in @(
            "authenticated_socket_open_before_revoke",
            "http_revoke_applied",
            "authenticated_socket_closed_within_deadline",
            "stale_bearer_auth_rejected",
            "credentials_deleted_after_negative_proof")) {
            if ($proof.$field -ne $true) { throw "Hostess revoke did not prove $field." }
        }
        $script:HostessPaired = $false
        return $receipt
    }
    if ($Kind -eq "command") {
        $allowed = @{
            "surface.spatial_video_control.media" = @("command.spatial_video_control.pause", "command.spatial_video_control.play", "command.spatial_video_control.select_next", "command.spatial_video_control.select_previous")
            "surface.connection_hub_sample.toggle" = @("command.connection_hub_sample.toggle")
        }
        if (-not $allowed.ContainsKey($SurfaceId) -or $allowed[$SurfaceId] -notcontains $CommandId) {
            throw "The requested surface/command pair is not in the fixed Connection Hub registry."
        }
        $script:CommandReceiptOrdinal += 1
        $receiptName = "hostess-command-$($script:CommandReceiptOrdinal)-" + (($SurfaceId + "-" + $CommandId) -replace '[^a-z0-9]+','-')
        $receipt = Invoke-Hostess "invoke-surface-command" @("--session-file", $SessionFile, "--surface-id", $SurfaceId, "--command", $CommandId, "--args-json", "{}") $receiptName
        $effect = $receipt.details
        if ([string]$effect.surface_id -ne $SurfaceId -or
                [string]$effect.command -ne $CommandId -or
                [string]::IsNullOrWhiteSpace([string]$effect.request_id) -or
                $effect.request_binding_exact -ne $true -or
                $effect.authority_accepted -ne $true -or
                $effect.provider_applied -ne $true -or
                [string]$effect.status -ne "provider_effect_observed") {
            throw "Hostess command was authorized but did not prove the exact provider effect."
        }
        return $receipt
    }
    throw "Unsupported Hostess action."
}

function Capture-Logs {
    $fallback = Invoke-Adb @("logcat", "-d", "-v", "threadtime", "-t", [string]$LogcatLines) $QfmLogGap "bounded logcat and fatal scan"
    $path = Join-Path $script:RunDir "logcat.txt"
    [System.IO.File]::WriteAllText($path, $fallback.output, (New-Object System.Text.UTF8Encoding($false)))
    $patterns = @("FATAL EXCEPTION", "AndroidRuntime E", "UnsatisfiedLinkError")
    $hits = @($patterns | Where-Object { $fallback.output.Contains($_, [StringComparison]::OrdinalIgnoreCase) })
    $receipt = New-Receipt "logs" "raw-adb-fallback" $(if($hits.Count -eq 0){"passed"}else{"failed"}) ([ordered]@{
        provider_gap=$QfmLogGap; bounded_lines=$LogcatLines; log_sha256=Get-Sha256 $path; fatal_patterns=$hits })
    [void](Save-Receipt "logs" $receipt)
    if ($hits.Count -ne 0) { throw "Bounded fatal scan found: $($hits -join ', ')" }
    return $receipt
}

function Test-Prerequisites {
    $device = Invoke-Captured $script:Adb @("-s", $Serial, "get-state") "exact device discovery"
    if ($device.exit_code -ne 0 -or $device.output.Trim() -ne "device") {
        throw "Exact Quest serial is not authorized and connected."
    }
    $details = [ordered]@{
        serial=$Serial
        adb_path_sha256=Get-Sha256 $script:Adb
        adb_version=$script:AdbVersion
        python_path_sha256=$(if($script:Python){Get-Sha256 $script:Python}else{$null})
        python_version=$script:PythonVersion
        node_path_sha256=$(if($script:Node){Get-Sha256 $script:Node}else{$null})
        node_version=$script:NodeVersion
        gradle_path_sha256=$(if($script:Gradle){Get-Sha256 $script:Gradle}else{$null})
        gradle_version=$script:GradleVersion
        qfm_path_sha256=$(if($script:Qfm){Get-Sha256 $script:Qfm}else{$null})
        hostess_path_sha256=$(if($script:Hostess){Get-Sha256 $script:Hostess}else{$null})
        protocol_vectors_sha256=Get-Sha256 $ProtocolVectorPath
        existing_target_policy=$ExistingTargetPolicy
        browser_e2e_required=[bool]$RequireBrowserE2E
    }
    if ($RequireBrowserE2E -or $Action -eq "BrowserE2E") {
        $packageJson = Assert-ExactFile $PlaywrightPackageJson $PlaywrightPackageJsonSha256 "Playwright package.json"
        $browser = Assert-ExactFile $BrowserExecutable $BrowserExecutableSha256 "Browser executable"
        $details.playwright_package_json_sha256=Get-Sha256 $packageJson
        $details.browser_executable_sha256=Get-Sha256 $browser
    }
    return Save-Receipt "prerequisites" (New-Receipt "prerequisites" "typed-provider-discovery" "passed" $details)
}

function Write-EvidenceManifest([string]$Result) {
    $entries = @($script:Receipts | Sort-Object | ForEach-Object {
        [ordered]@{ name=(Split-Path -Leaf $_); sha256=Get-Sha256 $_; size=(Get-Item $_).Length }
    })
    $manifest = [ordered]@{
        '$schema' = $ManifestSchema
        action = $Action
        serial = $Serial
        result = $Result
        generated_at_utc = [DateTime]::UtcNow.ToString("o")
        receipts = $entries
        cleanup = [ordered]@{ target_packages_only=$true; uninstall_performed=$false; adb_transport_changed=$false }
        secrets_in_manifest = $false
    }
    $path = Join-Path $script:RunDir "evidence-manifest.json"
    Write-JsonFile $path $manifest
    return $path
}

function New-DryRunPlan {
    return [ordered]@{
        '$schema' = "rusty.quest.connection_hub.operator_plan.v1"
        action = $Action
        serial = $Serial
        mutates_device = $Action -notin @("Build", "Inspect", "HostessStatus", "SimulateE2E")
        qfm_first = $true
        qfm_exact_sha256_required = $true
        all_apk_signers_must_match_before_install = $true
        reviewed_fallbacks = @(
            $QfmLaunchGap, $QfmLifecycleGap, $QfmServiceGap, $QfmStopGap,
            $QfmLogGap, $QfmDeviceStateGap, $QfmPackageStateGap,
            $QfmUninstallGap, $QfmWifiGap)
        hostess_secret_input = @("debug-shell-to-stdin-memory-only", "hidden-prompt", "stdin", "inherited-fd", "DPAPI-CurrentUser-session")
        e2e_sequence = @(
            "prerequisites+pre-state", "build", "inspect", "install+installed-byte-signer-readback",
            "debug-protocol-proof", "real-activity-foreground-service+notification", "pair",
            "spatial-present+command", "provider-lifetime-over-2m+command",
            "process-death+start-sticky+provider-reregister+command", "wifi-rebind-or-explicit-safety-skip",
            "spatial-removed+sample-present+command", "sample-removed+spatial-returned+command",
            "hub-persists-across-app-switches",
            "reconnect-epoch-assertion", "watch", "bounded-fatal-scan", "revoke+closed-socket-assertion",
            $(if($RequireBrowserE2E){"real-browser-sequential-surface-e2e"}else{"browser-e2e-explicitly-not-required"}),
            "target-only-pre-state-restore")
        checkpoint_resume = "serial+protocol+providers+artifacts+build-manifest+policy-bound"
        provider_lifetime_seconds = $ProviderLifetimeSeconds
        secrets_in_plan = $false
    }
}

if ($DryRun) {
    New-DryRunPlan | ConvertTo-Json -Depth 10
    exit 0
}

if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) { throw "-EvidenceRoot is required outside dry-run mode." }
$resolvedEvidenceRoot = [System.IO.Path]::GetFullPath($EvidenceRoot).TrimEnd('\')
if ($resolvedEvidenceRoot.StartsWith($RepoRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "EvidenceRoot must be outside the source checkout."
}
New-Item -ItemType Directory -Force -Path $resolvedEvidenceRoot | Out-Null
if ([string]::IsNullOrWhiteSpace($ResumeCheckpoint)) {
    $runName = "connection-hub-{0}-{1}" -f $Action.ToLowerInvariant(), ([DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffZ"))
    $script:RunDir = Join-Path $resolvedEvidenceRoot $runName
    New-Item -ItemType Directory -Path $script:RunDir | Out-Null
}
$needsQfm = $Action -in @("Prerequisites", "Inspect", "Install", "Start", "Status", "LaunchSpatial", "LaunchSample", "E2E")
$needsHostess = $Action -like "Hostess*" -or $Action -in @("WaitSurface", "WaitSurfaceAbsent", "E2E")
$needsAdb = $Action -notin @("Build", "Inspect", "SimulateE2E")
$needsPython = $needsHostess
$needsNode = $Action -eq "BrowserE2E" -or ($Action -eq "E2E" -and $RequireBrowserE2E)
$needsGradle = $Action -in @("Build", "E2E")
if ($needsAdb) {
    $script:Adb = Lock-ExactExecutable $Adb $AdbSha256 "ADB executable"
    $script:AdbPin = $AdbSha256
    $v = Invoke-Captured $script:Adb @("version") "ADB version"
    if ($v.exit_code -ne 0) { throw "ADB version read failed." }
    $script:AdbVersion = $v.combined.Trim()
} else { $script:Adb=$null; $script:AdbVersion=""; $script:AdbPin="" }
if ($needsPython) {
    $script:Python = Lock-ExactExecutable $Python $PythonSha256 "Python executable"
    $script:PythonPin = $PythonSha256
    $v = Invoke-Captured $script:Python @("--version") "Python version"
    if ($v.exit_code -ne 0) { throw "Python version read failed." }
    $script:PythonVersion = $v.combined.Trim()
} else { $script:Python=$null; $script:PythonVersion=""; $script:PythonPin="" }
if ($needsNode) {
    $script:Node = Lock-ExactExecutable $Node $NodeSha256 "Node executable"
    $script:NodePin = $NodeSha256
    $v = Invoke-Captured $script:Node @("--version") "Node version"
    if ($v.exit_code -ne 0) { throw "Node version read failed." }
    $script:NodeVersion = $v.combined.Trim()
} else { $script:Node=$null; $script:NodeVersion=""; $script:NodePin="" }
if ($needsGradle) {
    $script:Gradle = Lock-ExactExecutable $Gradle $GradleSha256 "Gradle executable"
    $script:GradlePin = $GradleSha256
    $v = Invoke-Captured $script:Gradle @("--version") "Gradle version"
    if ($v.exit_code -ne 0 -or $v.combined -notmatch 'Gradle 8\.13') { throw "Connection Hub build requires exact Gradle 8.13." }
    $script:GradleVersion = $v.combined.Trim()
} else { $script:Gradle=$null; $script:GradleVersion=""; $script:GradlePin="" }
Initialize-Checkpoint
if ([string]::IsNullOrWhiteSpace($SessionFile)) { $SessionFile = Join-Path $script:RunDir "hostess-session.json" }

if ($needsQfm) { $script:Qfm = Lock-ExactProvider $FileManagerCli $FileManagerSha256 "File Manager CLI" }
if ($needsHostess) { $script:Hostess = Lock-ExactProvider $HostessCli $HostessCliSha256 "Hostess CLI" }
if (@($script:Checkpoint.completed_stages) -contains "pair-hostess") { $script:HostessPaired = $true }
if (@($script:Checkpoint.completed_stages) -contains "real-hub-start") { $script:HubStarted = $true }

$finalResult = "failed"
try {
    if ($Action -eq "SimulateE2E") {
        foreach ($name in (New-DryRunPlan).e2e_sequence) {
            $safeName = $name -replace '[^a-z0-9]+','-'
            Invoke-Stage $safeName {
                [void](Save-Receipt "simulated-$safeName" (New-Receipt $name "deterministic-simulation" "passed" ([ordered]@{
                    device_touched=$false
                    checkpoint_bound=$true
                    sequential_foreground_provider_semantics=$true
                })))
            }
        }
    } elseif ($Action -eq "Prerequisites") { [void](Test-Prerequisites)
    } elseif ($Action -eq "Build") { [void](Build-All); [void](Resolve-Apks)
    } elseif ($Action -eq "Inspect") { $a=Resolve-Apks; [void](Inspect-All $a)
    } elseif ($Action -eq "Install") { $a=Resolve-Apks; [void](Inspect-All $a); [void](Capture-PreState $a); [void](Install-All $a)
    } elseif ($Action -eq "Start") { $a=Resolve-Apks; [void](Hub-Action "start" $a[0])
    } elseif ($Action -eq "Status") { $a=Resolve-Apks; [void](Invoke-Qfm @("apk","observe","--serial",$Serial,"--file",$a[0].path,"--json") "observe hub"); [void](Hub-Action "status")
    } elseif ($Action -eq "Stop") { [void](Hub-Action "stop")
    } elseif ($Action -eq "Forget") { [void](Hub-Action "forget")
    } elseif ($Action -eq "DebugProtocolProof") { [void](Prove-DebugProtocol)
    } elseif ($Action -eq "RestartProcess") { [void](Restart-HubProcess)
    } elseif ($Action -eq "WifiRebindE2E") { [void](Invoke-WifiRebindE2E)
    } elseif ($Action -eq "LaunchSpatial") { $a=Resolve-Apks; [void](Launch-Provider $a "spatial")
    } elseif ($Action -eq "LaunchSample") { $a=Resolve-Apks; [void](Launch-Provider $a "sample")
    } elseif ($Action -eq "StopSpatial") { [void](Stop-Provider "spatial")
    } elseif ($Action -eq "StopSample") { [void](Stop-Provider "sample")
    } elseif ($Action -eq "WaitSurface") { [void](Wait-Surface $SurfaceId $true)
    } elseif ($Action -eq "WaitSurfaceAbsent") { [void](Wait-Surface $SurfaceId $false)
    } elseif ($Action -eq "StopProviders") { [void](Stop-Providers)
    } elseif ($Action -eq "HostessStatus") { [void](Hostess-Action "status")
    } elseif ($Action -eq "HostessPair") { [void](Hostess-Action "pair")
    } elseif ($Action -eq "HostessList") { [void](Hostess-Action "list")
    } elseif ($Action -eq "HostessWatch") { [void](Hostess-Action "watch")
    } elseif ($Action -eq "HostessCommand") { [void](Hostess-Action "command")
    } elseif ($Action -eq "HostessReconnect") { [void](Hostess-Action "reconnect")
    } elseif ($Action -eq "HostessRevoke") { [void](Hostess-Action "revoke")
    } elseif ($Action -eq "BrowserE2E") {
        [void](Test-Prerequisites)
        if ([string]::IsNullOrWhiteSpace($Origin)) { $Origin = [string](Invoke-DebugOperator "status").owner_receipt.origin }
        [char[]]$browserSecret = Get-DebugPairingSecret
        try { [void](Invoke-BrowserE2EWithSecret $browserSecret) }
        finally { [Array]::Clear($browserSecret, 0, $browserSecret.Length); $browserSecret=$null }
    } elseif ($Action -eq "Logs") { [void](Capture-Logs)
    } elseif ($Action -eq "Cleanup") { $a=Resolve-Apks; [void](Restore-PreState $a)
    } elseif ($Action -eq "E2E") {
        Invoke-Stage "prerequisites" { [void](Test-Prerequisites) }
        Invoke-Stage "build" { [void](Build-All); [void](Resolve-Apks) }
        $a=Resolve-Apks
        Invoke-Stage "inspect" { [void](Inspect-All $a) }
        Invoke-Stage "capture-pre-state" { [void](Capture-PreState $a) }
        Invoke-Stage "install" { [void](Install-All $a) }
        Invoke-Stage "debug-protocol-proof" { [void](Prove-DebugProtocol) }
        Invoke-Stage "real-hub-start" { [void](Hub-Action "start" $a[0]) }
        if ([string]::IsNullOrWhiteSpace($Origin)) { $Origin = [string](Invoke-DebugOperator "status").owner_receipt.origin }
        Invoke-Stage "pair-hostess" {
            [void](Hostess-Action "status")
            [char[]]$pairingSecret = Get-DebugPairingSecret
            try { [void](Invoke-HostessPairWithSecret $pairingSecret) }
            finally { [Array]::Clear($pairingSecret, 0, $pairingSecret.Length); $pairingSecret = $null }
        }
        Invoke-Stage "spatial-first" {
            [void](Launch-Provider $a "spatial")
            [void](Wait-Surface "surface.spatial_video_control.media" $true)
            [void](Wait-Surface "surface.connection_hub_sample.toggle" $false)
            $SurfaceId="surface.spatial_video_control.media"; $CommandId="command.spatial_video_control.play"; [void](Hostess-Action "command")
            if ((Invoke-DebugOperator "status").owner_receipt.listener_running -ne $true) { throw "Hub stopped across Spatial app switch." }
        }
        Invoke-Stage "provider-lifetime-over-2m" {
            $deadline=[DateTime]::UtcNow.AddSeconds($ProviderLifetimeSeconds)
            $nextProbe=[DateTime]::UtcNow
            while ([DateTime]::UtcNow -lt $deadline) {
                if ([DateTime]::UtcNow -ge $nextProbe) {
                    if ((Invoke-DebugOperator "status").owner_receipt.listener_running -ne $true) { throw "Hub stopped during provider lifetime hold." }
                    [void](Read-HostessSurfaces)
                    $nextProbe=[DateTime]::UtcNow.AddSeconds(15)
                }
                Start-Sleep -Seconds 1
            }
            $SurfaceId="surface.spatial_video_control.media"; $CommandId="command.spatial_video_control.pause"; [void](Hostess-Action "command")
        }
        Invoke-Stage "process-restart" {
            [void](Restart-HubProcess)
            [void](Hostess-Action "reconnect")
            [void](Wait-Surface "surface.spatial_video_control.media" $true)
            $SurfaceId="surface.spatial_video_control.media"; $CommandId="command.spatial_video_control.play"; [void](Hostess-Action "command")
        }
        Invoke-Stage "wifi-rebind" {
            $wifiReceipt=Invoke-WifiRebindE2E
            if ($RequireWifiRebindE2E) {
                if ([string]$wifiReceipt.details.origin_before -ne [string]$wifiReceipt.details.origin_after) {
                    throw "Wi-Fi rebind changed origin; safe existing-session migration is not implemented."
                }
                [void](Hostess-Action "reconnect")
                [void](Wait-Surface "surface.spatial_video_control.media" $true)
                $SurfaceId="surface.spatial_video_control.media"; $CommandId="command.spatial_video_control.pause"; [void](Hostess-Action "command")
            }
        }
        Invoke-Stage "sample-switch" {
            [void](Launch-Provider $a "sample")
            [void](Wait-Surface "surface.spatial_video_control.media" $false)
            [void](Wait-Surface "surface.connection_hub_sample.toggle" $true)
            $SurfaceId="surface.connection_hub_sample.toggle"; $CommandId="command.connection_hub_sample.toggle"; [void](Hostess-Action "command")
            if ((Invoke-DebugOperator "status").owner_receipt.listener_running -ne $true) { throw "Hub stopped across Sample app switch." }
        }
        Invoke-Stage "spatial-return" {
            [void](Launch-Provider $a "spatial")
            [void](Wait-Surface "surface.connection_hub_sample.toggle" $false)
            [void](Wait-Surface "surface.spatial_video_control.media" $true)
            $SurfaceId="surface.spatial_video_control.media"; $CommandId="command.spatial_video_control.play"; [void](Hostess-Action "command")
        }
        Invoke-Stage "reconnect" { [void](Hostess-Action "reconnect") }
        Invoke-Stage "watch" { [void](Hostess-Action "watch") }
        Invoke-Stage "logs" { [void](Capture-Logs) }
        Invoke-Stage "revoke" { [void](Hostess-Action "revoke") }
        Invoke-Stage "browser-e2e" {
            if ($RequireBrowserE2E) {
                [char[]]$browserSecret = Get-DebugPairingSecret
                try { [void](Invoke-BrowserE2EWithSecret $browserSecret) }
                finally { [Array]::Clear($browserSecret, 0, $browserSecret.Length); $browserSecret=$null }
            } else {
                [void](Save-Receipt "browser-e2e" (New-Receipt "browser-e2e" "optional-playwright-provider" "not_required" ([ordered]@{
                    ran=$false
                    acceptance_claimed=$false
                })))
            }
        }
        Invoke-Stage "restore-pre-state" { [void](Restore-PreState $a) }
    }
    $finalResult = "passed"
} finally {
    if ($Action -eq "E2E" -and $finalResult -ne "passed") {
        $cleanupErrors = [System.Collections.Generic.List[string]]::new()
        if ($script:HostessPaired -and (Test-Path -LiteralPath $SessionFile -PathType Leaf)) {
            try { [void](Hostess-Action "revoke") } catch { [void]$cleanupErrors.Add("hostess_revoke_failed") }
        }
        $preStatePath = Join-Path $script:RunDir "pre-state.json"
        if ($script:TargetMutationStarted -and (Test-Path -LiteralPath $preStatePath -PathType Leaf)) {
            try { $a=Resolve-Apks; [void](Restore-PreState $a) } catch { [void]$cleanupErrors.Add("pre_state_restore_failed") }
        } else {
            if ($script:ProvidersLaunched) { try { [void](Stop-Providers) } catch { [void]$cleanupErrors.Add("provider_stop_failed") } }
            if ($script:HubStarted) { try { [void](Hub-Action "stop") } catch { [void]$cleanupErrors.Add("hub_stop_failed") } }
        }
        [void](Save-Receipt "failure-cleanup" (New-Receipt "failure-cleanup" "operator-wrapper" $(if($cleanupErrors.Count -eq 0){"passed"}else{"partial"}) ([ordered]@{
            attempted = $true
            errors = @($cleanupErrors)
            target_packages_only = $true
        })))
    }
    $manifestPath = Write-EvidenceManifest $finalResult
    foreach ($lock in $script:ProviderLocks) { $lock.Dispose() }
    [ordered]@{
        '$schema' = "rusty.quest.connection_hub.operator_run.v1"
        action = $Action
        serial = $Serial
        result = $finalResult
        evidence_manifest = $manifestPath
        secrets_in_output = $false
    } | ConvertTo-Json -Depth 8
}
