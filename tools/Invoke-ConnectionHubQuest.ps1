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
    [switch]$UseBoundedVirtualProximity,
    [switch]$LegacyV1,
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
$QfmProximityGap = "qfm-missing-typed-bounded-virtual-proximity-v1"
$ReceiptSchema = "rusty.quest.connection_hub.operator_receipt.v1"
$ManifestSchema = "rusty.quest.connection_hub.operator_evidence_manifest.v1"
$CheckpointSchema = "rusty.quest.connection_hub.operator_checkpoint.v2"
$ProtocolVectorPath = Join-Path $RepoRoot $(if($LegacyV1){
    "apps\manifold-broker-android\contracts\connection-hub-protocol-v1.json"
}else{
    "apps\manifold-broker-android\contracts\connection-hub-protocol-v2.json"
})
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
$script:RunLogProcess = $null
$script:RunLogMarker = ""
$script:RunLogPath = ""
$script:RunLogStderrPath = ""
$script:ProcessEpochOrdinal = 0
$script:ProcessEpochs = [System.Collections.Generic.List[object]]::new()
$script:UninstallPerformed = $false
$script:VirtualProximityEnabledByRun = $false
$script:CurrentCheckpointStage = ""

if ($LegacyV1 -and ($RequireBrowserE2E -or $Action -eq "BrowserE2E")) {
    throw "The browser acceptance harness is v2-only; legacy v1 cannot claim browser E2E continuity."
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Test-ExactBoolean($Value, [bool]$Expected) {
    return $Value -is [bool] -and $Value -eq $Expected
}

function Test-ExactJsonInteger($Value, [long]$Minimum) {
    return ($Value -is [int] -or $Value -is [long]) -and [long]$Value -ge $Minimum
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
    # A stage receipt is immutable once it has been admitted into the run.  A
    # later observation with the same logical name gets a unique leaf instead
    # of silently changing the digest that closed an earlier stage.
    if ($script:Receipts.Contains($path)) {
        $ordinal = 2
        do {
            $path = Join-Path $script:RunDir ("{0}-{1}.json" -f $Name,$ordinal)
            $ordinal += 1
        } while ($script:Receipts.Contains($path) -or (Test-Path -LiteralPath $path))
    }
    Write-JsonFile $path $Value
    if (-not $script:Receipts.Contains($path)) { [void]$script:Receipts.Add($path) }
    return $Value
}

function New-Receipt([string]$Operation, [string]$Provider, [string]$Status, $Details) {
    return [ordered]@{
        '$schema' = $ReceiptSchema
        operation = $Operation
        provider = $Provider
        checkpoint_stage = $(if([string]::IsNullOrWhiteSpace($script:CurrentCheckpointStage)){$null}else{$script:CurrentCheckpointStage})
        serial = $Serial
        status = $Status
        observed_at_utc = [DateTime]::UtcNow.ToString("o")
        secrets_in_receipt = $false
        details = $Details
    }
}

function Read-ValidatedReceipt([string]$Path, [string]$ExpectedSha256) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $runPrefix = $script:RunDir.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $resolved -PathType Leaf) -or
            (Get-Sha256 $resolved) -ne $ExpectedSha256) {
        throw "Checkpoint stage receipt path or digest mismatch."
    }
    $receipt = Get-Content -Raw -LiteralPath $resolved | ConvertFrom-Json
    if ([string]$receipt.'$schema' -ne $ReceiptSchema -or
            [string]$receipt.status -notin @("passed", "not_required", "not_run", "diagnostic_only")) {
        throw "Checkpoint stage receipt schema or status is not acceptance-safe."
    }
    if (-not $script:Receipts.Contains($resolved)) { [void]$script:Receipts.Add($resolved) }
    return $receipt
}

function Assert-StageReceiptSemantics([string]$Name, [object[]]$Receipts) {
    if ($Receipts.Count -eq 0) { throw "Checkpoint stage has no semantic receipt closure: $Name" }
    $originatingAction = [string]$script:Checkpoint.originating_action
    foreach ($receipt in $Receipts) {
        if ([string]$receipt.checkpoint_stage -ne $Name) {
            throw "Checkpoint stage receipt was relabeled or substituted: $Name"
        }
    }
    if ($originatingAction -eq "SimulateE2E") {
        if ($Receipts.Count -ne 1) { throw "Simulation stage receipt multiset is not exact: $Name" }
        foreach ($receipt in $Receipts) {
            $safeOperation = ([string]$receipt.operation) -replace '[^a-z0-9]+','-'
            if ([string]$receipt.provider -ne "deterministic-simulation" -or
                    [string]$receipt.status -ne "passed" -or $safeOperation -ne $Name) {
                throw "Simulation stage receipt was relabeled or substituted: $Name"
            }
        }
        return
    }
    if ($originatingAction -ne "E2E") { return }

    $expected = switch ($Name) {
        "prerequisites" { @("prerequisites|typed-provider-discovery|passed") }
        "build" { @("build|project-build|passed") }
        "inspect" { @("inspect|questionable-file-manager|passed") }
        "capture-pre-state" { @("pre-state|qfm+serial-readback|passed") }
        "install" { @("install|questionable-file-manager|passed") }
        "run-log-capture-start" { @("run-log-capture-start|serial-scoped-streaming-adb|passed") }
        "debug-protocol-proof" { @("debug-protocol-proof|debug-shell-provider-gap|passed") }
        "real-hub-start" { @(
            "hub-real-service|android-foreground-service|passed",
            "hub-start|debug-shell-foreground-service|passed",
            "target-process-epoch|serial-scoped-adb-readback|passed") }
        "pair-hostess" { @("hostess-status|rusty-hostess|passed", "hostess-pair|rusty-hostess|passed") }
        "hostess-v2-simulation" { @("hostess-simulate-e2e|rusty-hostess|passed") }
        "spatial-first" { @(
            "launch-spatial|qfm-with-reviewed-fallback|passed",
            "wait-surface|rusty-hostess|passed", "wait-surface|rusty-hostess|passed",
            "target-process-epoch|serial-scoped-adb-readback|passed",
            "hostess-invoke-surface-command|rusty-hostess|passed") }
        "provider-lifetime-over-2m" { @("hostess-invoke-surface-command|rusty-hostess|passed") }
        "process-restart" { @(
            "hub-process-restart|debug-death+real-start-sticky|passed",
            "hostess-list-surfaces|rusty-hostess|passed", "wait-surface|rusty-hostess|passed",
            "target-process-epoch|serial-scoped-adb-readback|passed",
            "hostess-invoke-surface-command|rusty-hostess|passed") }
        "wifi-rebind" {
            if ([bool]$script:Checkpoint.require_wifi_rebind_e2e) {
                @("wifi-rebind|exact-usb-transport|passed", "hostess-list-surfaces|rusty-hostess|passed",
                    "wait-surface|rusty-hostess|passed", "hostess-invoke-surface-command|rusty-hostess|passed")
            } else { @("wifi-rebind|optional-device-provider|not_run") }
        }
        "sample-switch" { @(
            "launch-sample|qfm-with-reviewed-fallback|passed",
            "wait-surface|rusty-hostess|passed", "wait-surface|rusty-hostess|passed",
            "target-process-epoch|serial-scoped-adb-readback|passed",
            "hostess-invoke-surface-command|rusty-hostess|passed") }
        "spatial-return" { @(
            "launch-spatial|qfm-with-reviewed-fallback|passed",
            "wait-surface|rusty-hostess|passed", "wait-surface|rusty-hostess|passed",
            "target-process-epoch|serial-scoped-adb-readback|passed",
            "hostess-invoke-surface-command|rusty-hostess|passed") }
        "reconnect" { @("hostess-list-surfaces|rusty-hostess|passed") }
        "keepalive-renewal" {
            if ([bool]$script:Checkpoint.legacy_v1) {
                @("hostess-v2-keepalive-renewal|optional-v2-continuity|not_required")
            } else { @("hostess-connect-watch|rusty-hostess|passed") }
        }
        "history-rollover" {
            if ([bool]$script:Checkpoint.legacy_v1) {
                @("history-rollover|optional-v2-continuity|not_required")
            } else { @(
                "hub-force-rollover|debug-shell-provider-gap|passed",
                "hostess-list-surfaces|rusty-hostess|passed",
                "hostess-invoke-surface-command|rusty-hostess|passed",
                "history-rollover-continuity|hostess+quest-authority|passed") }
        }
        "revoke" { @("hostess-revoke|rusty-hostess|passed") }
        "browser-e2e" {
            if ([bool]$script:Checkpoint.require_browser_e2e) { @("browser-e2e|playwright-real-browser|passed") }
            else { @("browser-e2e|optional-playwright-provider|not_required") }
        }
        "logs" { @(
            "target-process-epoch|serial-scoped-adb-readback|passed",
            "run-bounded-logs|serial-scoped-streaming-adb|passed") }
        "restore-pre-state" { @(
            "stop-providers|raw-adb-fallback|passed", "hub-stop|real-activity-foreground-service|passed",
            "restore-pre-state|target-only-cleanup|passed") }
        default { throw "Unknown E2E checkpoint stage cannot be accepted: $Name" }
    }
    $actual = @($Receipts | ForEach-Object {
        "{0}|{1}|{2}" -f [string]$_.operation,[string]$_.provider,[string]$_.status
    })
    $expectedSorted = @($expected | Sort-Object)
    $actualSorted = @($actual | Sort-Object)
    if ($expectedSorted.Count -ne $actualSorted.Count -or
            ($expectedSorted -join "`n") -cne ($actualSorted -join "`n")) {
        throw "Checkpoint stage $Name receipt operation/provider/status multiset is not exact."
    }
}

function Assert-CheckpointStageClosure {
    $completed = @($script:Checkpoint.completed_stages)
    $stageMap = $script:Checkpoint.stage_receipts
    foreach ($name in $completed) {
        if (-not $stageMap.Contains([string]$name)) {
            throw "Checkpoint completed stage lacks a receipt closure: $name"
        }
        $entries = @($stageMap[[string]$name])
        if ($entries.Count -eq 0) { throw "Checkpoint stage has an empty receipt closure: $name" }
        $receipts = @($entries | ForEach-Object {
            $entry = $_
            $leaf = [string]$entry.name
            if ([System.IO.Path]::GetFileName($leaf) -ne $leaf -or -not $leaf.EndsWith(".json", [StringComparison]::Ordinal)) {
                throw "Checkpoint stage receipt name is not one exact JSON leaf."
            }
            $receipt = Read-ValidatedReceipt (Join-Path $script:RunDir $leaf) ([string]$entry.sha256)
            if ([string]$entry.operation -ne [string]$receipt.operation -or
                    [string]$entry.provider -ne [string]$receipt.provider -or
                    [string]$entry.status -ne [string]$receipt.status) {
                throw "Checkpoint stage receipt metadata mismatch: $name/$leaf"
            }
            $receipt
        })
        Assert-StageReceiptSemantics ([string]$name) $receipts
    }
    foreach ($name in @($stageMap.Keys)) {
        if ($completed -notcontains [string]$name) { throw "Checkpoint has receipts for an uncompleted stage: $name" }
    }
}

function Restore-ProcessEpochsFromReceipts {
    foreach ($path in @($script:Receipts)) {
        try { $receipt = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json } catch { continue }
        if ([string]$receipt.operation -ne "target-process-epoch") { continue }
        $script:ProcessEpochOrdinal = [Math]::Max($script:ProcessEpochOrdinal, [int]$receipt.details.ordinal)
        foreach ($target in @($receipt.details.targets)) {
            [void]$script:ProcessEpochs.Add([pscustomobject]@{
                reason=[string]$receipt.details.reason
                package=[string]$target.package
                uid=$(if($null -eq $target.uid){$null}else{[int]$target.uid})
                pids=@($target.pids | ForEach-Object { [int]$_ })
            })
        }
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
        $originatingAction = [string]$checkpoint.originating_action
        $actionMatches = if ($Action -eq "Cleanup") { $originatingAction -eq "E2E" } else { $originatingAction -eq $Action }
        if ([string]$checkpoint.'$schema' -ne $CheckpointSchema -or
                -not $actionMatches -or
                [string]$checkpoint.serial -ne $Serial -or
                [string]$checkpoint.protocol_vectors_sha256 -ne $protocolSha -or
                [string]$checkpoint.existing_target_policy -ne $ExistingTargetPolicy -or
                [string]$checkpoint.adb_sha256 -ne [string]$script:AdbPin -or
                [string]$checkpoint.adb_version -ne [string]$script:AdbVersion -or
                [string]$checkpoint.python_sha256 -ne [string]$script:PythonPin -or
                [string]$checkpoint.python_version -ne [string]$script:PythonVersion -or
                ($Action -ne "Cleanup" -and (
                    [string]$checkpoint.node_sha256 -ne [string]$script:NodePin -or
                    [string]$checkpoint.gradle_sha256 -ne [string]$script:GradlePin -or
                    [string]$checkpoint.node_version -ne [string]$script:NodeVersion -or
                    [string]$checkpoint.gradle_version -ne [string]$script:GradleVersion -or
                    [bool]$checkpoint.require_browser_e2e -ne [bool]$RequireBrowserE2E -or
                    [bool]$checkpoint.require_wifi_rebind_e2e -ne [bool]$RequireWifiRebindE2E -or
                    [bool]$checkpoint.use_bounded_virtual_proximity -ne [bool]$UseBoundedVirtualProximity -or
                    [bool]$checkpoint.legacy_v1 -ne [bool]$LegacyV1))) {
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
        if ([string]::IsNullOrWhiteSpace([string]$checkpoint.session_file)) {
            throw "Resume checkpoint lacks a bound Hostess session path."
        }
        $boundSessionFile = [System.IO.Path]::GetFullPath([string]$checkpoint.session_file)
        $expectedSessionFile = Join-Path $script:RunDir "hostess-session.json"
        if (($originatingAction -eq "E2E" -and $boundSessionFile -ne $expectedSessionFile) -or
                (-not [string]::IsNullOrWhiteSpace($SessionFile) -and
                    [System.IO.Path]::GetFullPath($SessionFile) -ne $boundSessionFile)) {
            throw "Resume checkpoint Hostess session path mismatch."
        }
        $script:SessionFile = $boundSessionFile
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
        if (-not [string]::IsNullOrWhiteSpace([string]$checkpoint.artifact_build_manifest_sha256)) {
            $manifestPath = [System.IO.Path]::GetFullPath([string]$checkpoint.artifact_build_manifest_path)
            if (-not $manifestPath.StartsWith($artifactPrefix, [StringComparison]::OrdinalIgnoreCase) -or
                    -not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
                    (Get-Sha256 $manifestPath) -ne [string]$checkpoint.artifact_build_manifest_sha256) {
                throw "Resume checkpoint build manifest digest mismatch."
            }
        }
        $stageMap = [ordered]@{}
        if ($checkpoint.stage_receipts) {
            foreach ($property in $checkpoint.stage_receipts.PSObject.Properties) {
                $stageMap[$property.Name] = @($property.Value)
            }
        }
        $script:Checkpoint.stage_receipts = $stageMap
        Assert-CheckpointStageClosure
        foreach ($receipt in Get-ChildItem -LiteralPath $script:RunDir -Filter *.json -File) {
            if ($receipt.FullName -ne $script:CheckpointPath) {
                try {
                    $candidate = Get-Content -Raw -LiteralPath $receipt.FullName | ConvertFrom-Json
                    if ([string]$candidate.'$schema' -eq $ReceiptSchema) {
                        if (-not $script:Receipts.Contains($receipt.FullName)) { [void]$script:Receipts.Add($receipt.FullName) }
                    }
                } catch { }
            }
        }
        Restore-ProcessEpochsFromReceipts
        return
    }
    $script:CheckpointPath = Join-Path $script:RunDir "checkpoint.json"
    $script:Checkpoint = [ordered]@{
        '$schema' = $CheckpointSchema
        originating_action = $Action
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
        require_browser_e2e = [bool]$RequireBrowserE2E
        require_wifi_rebind_e2e = [bool]$RequireWifiRebindE2E
        use_bounded_virtual_proximity = [bool]$UseBoundedVirtualProximity
        legacy_v1 = [bool]$LegacyV1
        session_file = [System.IO.Path]::GetFullPath($SessionFile)
        artifact_build_manifest_path = $null
        artifact_build_manifest_sha256 = $null
        artifacts = @()
        completed_stages = @()
        stage_receipts = [ordered]@{}
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
        $retainedManifestPath = Join-Path $script:RunDir "artifacts\build-manifest.json"
        Copy-Item -LiteralPath $manifestPath -Destination $retainedManifestPath -Force
        $script:Checkpoint.artifact_build_manifest_path = $retainedManifestPath
        $script:Checkpoint.artifact_build_manifest_sha256 = Get-Sha256 $retainedManifestPath
    }
    Write-Checkpoint
}

function Invoke-Stage([string]$Name, [scriptblock]$Body) {
    if (@($script:Checkpoint.completed_stages) -contains $Name) {
        return
    }
    $before = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in @($script:Receipts)) { [void]$before.Add($path) }
    $priorStage = $script:CurrentCheckpointStage
    try {
        $script:CurrentCheckpointStage = $Name
        . $Body
    } finally {
        $script:CurrentCheckpointStage = $priorStage
    }
    $stageReceipts = @($script:Receipts | Where-Object { -not $before.Contains($_) } | ForEach-Object {
        $receipt = Get-Content -Raw -LiteralPath $_ | ConvertFrom-Json
        if ([string]$receipt.'$schema' -ne $ReceiptSchema -or
                [string]$receipt.status -notin @("passed", "not_required", "not_run", "diagnostic_only")) {
            throw "Completed stage emitted a non-acceptance receipt: $Name"
        }
        [pscustomobject]@{ path=$_; receipt=$receipt }
    })
    if ($stageReceipts.Count -eq 0) { throw "Completed stage emitted no receipt closure: $Name" }
    Assert-StageReceiptSemantics $Name @($stageReceipts.receipt)
    $entries = @($stageReceipts | ForEach-Object {
        [ordered]@{
            name=(Split-Path -Leaf $_.path)
            sha256=Get-Sha256 $_.path
            operation=[string]$_.receipt.operation
            provider=[string]$_.receipt.provider
            status=[string]$_.receipt.status
        }
    })
    $script:Checkpoint.stage_receipts[$Name] = $entries
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

function Start-RunLogCapture {
    if ($null -ne $script:RunLogProcess) { throw "Run log capture is already active." }
    if ((Get-Sha256 $script:Adb) -ne $AdbSha256) { throw "ADB changed after the run lock was acquired." }
    if ($script:Checkpoint.run_log_capture -and $script:Checkpoint.run_log_capture.active -eq $true) {
        $saved = $script:Checkpoint.run_log_capture
        $script:RunLogMarker = [string]$saved.marker
        $script:RunLogPath = [System.IO.Path]::GetFullPath([string]$saved.stdout_path)
        $script:RunLogStderrPath = [System.IO.Path]::GetFullPath([string]$saved.stderr_path)
        $runPrefix = $script:RunDir.TrimEnd('\') + '\'
        if (-not $script:RunLogPath.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
                -not $script:RunLogStderrPath.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Checkpoint log capture paths escaped the exact run directory."
        }
        $process = Get-Process -Id ([int]$saved.pid) -ErrorAction SilentlyContinue
        $processMetadata = Get-CimInstance Win32_Process -Filter ("ProcessId={0}" -f [int]$saved.pid) -ErrorAction SilentlyContinue
        $expectedCommandFragment = "-s $Serial logcat -v epoch"
        if ([string]$saved.adb_sha256 -ne $AdbSha256 -or [string]$saved.serial -ne $Serial -or
                $null -eq $process -or $process.HasExited -or (Get-Sha256 $process.Path) -ne $AdbSha256 -or
                $null -eq $processMetadata -or
                -not ([string]$processMetadata.CommandLine).Contains($expectedCommandFragment, [StringComparison]::Ordinal)) {
            throw "Run-bounded log capture was lost; this checkpoint cannot make an acceptance claim."
        }
        $script:RunLogProcess = $process
        return Save-Receipt "run-log-capture-resume" (New-Receipt "run-log-capture-resume" "serial-scoped-streaming-adb" "passed" ([ordered]@{
            pid=[int]$process.Id
            marker_sha256=Get-TextSha256 $script:RunLogMarker
            capture_process_preserved=true
        }))
    }
    $script:RunLogMarker = "rq-connection-hub-" + [Guid]::NewGuid().ToString("N")
    $script:RunLogPath = Join-Path $script:RunDir "run-logcat.txt"
    $script:RunLogStderrPath = Join-Path $script:RunDir "run-logcat.stderr.txt"
    $arguments = @(
        "-s", $Serial, "logcat", "-v", "epoch", "-v", "uid",
        "RQConnectionHubE2E:I", "RustyManifoldAdmission:V", "RustyManifoldRuntime:V",
        "RqConnectionHub:V", "RustyQuestVideoControl:V", "AndroidRuntime:E",
        "ActivityManager:I", "libc:F", "DEBUG:F", "*:S")
    $process = Start-Process -FilePath $script:Adb -ArgumentList $arguments `
        -RedirectStandardOutput $script:RunLogPath -RedirectStandardError $script:RunLogStderrPath `
        -WindowStyle Hidden -PassThru
    $script:RunLogProcess = $process
    $script:Checkpoint.run_log_capture = [ordered]@{
        active=$true
        pid=[int]$process.Id
        marker=$script:RunLogMarker
        stdout_path=$script:RunLogPath
        stderr_path=$script:RunLogStderrPath
        adb_sha256=$AdbSha256
        serial=$Serial
    }
    Write-Checkpoint
    Start-Sleep -Milliseconds 300
    if ($process.HasExited) { throw "Run-bounded logcat process exited before the acceptance marker was written." }
    [void](Invoke-Adb @("shell", "log", "-t", "RQConnectionHubE2E", "START $($script:RunLogMarker)") $QfmLogGap "write the run-owned log capture marker")
    return Save-Receipt "run-log-capture-start" (New-Receipt "run-log-capture-start" "serial-scoped-streaming-adb" "passed" ([ordered]@{
        marker_sha256=Get-TextSha256 $script:RunLogMarker
        capture_scope="fixed Hub/provider tags plus Android fatal/ANR authorities"
        global_log_buffer_cleared=$false
        run_owned_process=$true
        resumable_process_id=[int]$process.Id
    }))
}

function Record-TargetProcessEpoch([string]$Reason) {
    $script:ProcessEpochOrdinal += 1
    $rows = @()
    foreach ($package in @($HubPackage, $SpatialPackage, $SamplePackage)) {
        $uidRead = Invoke-Adb @("shell", "dumpsys", "package", $package) $QfmPackageStateGap "read target UID for run log binding" -AllowFailure
        $uidMatch = [regex]::Match($uidRead.output, '(?m)^\s*userId=(\d+)\s*$')
        $pidRead = Invoke-Adb @("shell", "pidof", $package) $QfmPackageStateGap "read target PID epoch for run log binding" -AllowFailure
        $pids = @()
        if ($pidRead.exit_code -eq 0) {
            $pids = @($pidRead.output.Trim() -split '\s+' | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ })
        }
        $row = [ordered]@{
            package=$package
            uid=$(if($uidMatch.Success){[int]$uidMatch.Groups[1].Value}else{$null})
            pids=$pids
        }
        $rows += $row
        [void]$script:ProcessEpochs.Add([pscustomobject]@{reason=$Reason; package=$package; uid=$row.uid; pids=$pids})
    }
    $safeReason = $Reason -replace '[^a-z0-9]+','-'
    return Save-Receipt ("process-epoch-{0}-{1}" -f $script:ProcessEpochOrdinal,$safeReason) (New-Receipt "target-process-epoch" "serial-scoped-adb-readback" "passed" ([ordered]@{
        ordinal=$script:ProcessEpochOrdinal
        reason=$Reason
        targets=$rows
    }))
}

function Measure-RunLogWindow(
        [string]$Text,
        [string]$Marker,
        [object[]]$Epochs,
        [bool]$CaptureWithinBound,
        [bool]$CaptureProcessAliveAtEnd,
        [int]$EndMarkerWriteExitCode,
        [bool]$CaptureProcessExitObserved = $true) {
    $startMarker = "START $Marker"
    $endMarker = "END $Marker"
    $startIndex = $Text.IndexOf($startMarker, [StringComparison]::Ordinal)
    $endIndex = if($startIndex -ge 0){$Text.IndexOf($endMarker, $startIndex + $startMarker.Length, [StringComparison]::Ordinal)}else{-1}
    $startMarkerRetained = $startIndex -ge 0
    $endMarkerRetained = $endIndex -gt $startIndex
    $runWindow = if($startMarkerRetained -and $endMarkerRetained){$Text.Substring($startIndex, $endIndex + $endMarker.Length - $startIndex)}else{""}
    $targetPids = [System.Collections.Generic.HashSet[int]]::new()
    $targetUids = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($epoch in $Epochs) {
        if ($null -ne $epoch.uid) { [void]$targetUids.Add([int]$epoch.uid) }
        foreach ($targetProcessId in @($epoch.pids)) { [void]$targetPids.Add([int]$targetProcessId) }
    }
    $targetPackages = @($HubPackage, $SpatialPackage, $SamplePackage)
    $targetFatalLines = [System.Collections.Generic.List[string]]::new()
    $targetAnrLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in ($runWindow -split "`r?`n")) {
        $prefix = [regex]::Match($line, '^\s*\S+\s+(\d+)\s+(\d+)\s+')
        $uid = if($prefix.Success){[int]$prefix.Groups[1].Value}else{-1}
        $lineProcessId = if($prefix.Success){[int]$prefix.Groups[2].Value}else{-1}
        $boundProcess = $targetUids.Contains($uid) -or $targetPids.Contains($lineProcessId)
        $namesTarget = @($targetPackages | Where-Object { $line.Contains($_, [StringComparison]::Ordinal) }).Count -gt 0
        if (($boundProcess -or $namesTarget) -and
                ($line -match 'FATAL EXCEPTION|UnsatisfiedLinkError|\sE\s+AndroidRuntime\s*:|Fatal signal|Abort message')) {
            [void]$targetFatalLines.Add($line)
        }
        if (($boundProcess -or $namesTarget) -and $line -match '(?i)\bANR\b|not responding') {
            [void]$targetAnrLines.Add($line)
        }
    }
    return [ordered]@{
        start_marker_retained=$startMarkerRetained
        end_marker_retained=$endMarkerRetained
        capture_process_alive_at_end=$CaptureProcessAliveAtEnd
        capture_process_exit_observed=$CaptureProcessExitObserved
        end_marker_write_exit_code=$EndMarkerWriteExitCode
        capture_within_bound=$CaptureWithinBound
        coverage_complete=($CaptureWithinBound -and $CaptureProcessAliveAtEnd -and $CaptureProcessExitObserved -and
            $EndMarkerWriteExitCode -eq 0 -and $startMarkerRetained -and $endMarkerRetained)
        target_uids=@($targetUids | Sort-Object)
        target_pids=@($targetPids | Sort-Object)
        target_fatal_count=$targetFatalLines.Count
        target_anr_count=$targetAnrLines.Count
        target_fatal_lines_sha256=Get-TextSha256 ($targetFatalLines -join "`n")
        target_anr_lines_sha256=Get-TextSha256 ($targetAnrLines -join "`n")
    }
}

function Assert-RunLogProcessExitObserved([bool]$Observed) {
    if (-not $Observed) {
        throw "Run-owned log capture process termination was not observed."
    }
}

function Stop-RunLogCapture([switch]$FailureCleanup) {
    if ($null -eq $script:RunLogProcess) { return $null }
    $process = $script:RunLogProcess
    $captureProcessAliveAtEnd = -not $process.HasExited
    $captureProcessExitObserved = $process.HasExited
    $endMarkerWriteExitCode = -1
    try {
        if ($captureProcessAliveAtEnd) {
            $endWrite = Invoke-Adb @("shell", "log", "-t", "RQConnectionHubE2E", "END $($script:RunLogMarker)") $QfmLogGap "write the run-owned log capture end marker" -AllowFailure
            $endMarkerWriteExitCode = [int]$endWrite.exit_code
            Start-Sleep -Milliseconds 300
            if (-not $process.HasExited) { $process.Kill($true) }
            $captureProcessExitObserved = $process.WaitForExit(5000)
            if (-not $captureProcessExitObserved -and -not $process.HasExited) {
                $process.Kill($true)
                $captureProcessExitObserved = $process.WaitForExit(5000)
            }
        }
    } finally {
        if ($captureProcessExitObserved) {
            $process.Dispose()
            $script:RunLogProcess = $null
        }
    }
    if ($captureProcessExitObserved -and $script:Checkpoint.run_log_capture) {
        $script:Checkpoint.run_log_capture.active = $false
        Write-Checkpoint
    }
    $path = $script:RunLogPath
    $stdout = if(Test-Path -LiteralPath $path -PathType Leaf){[System.IO.File]::ReadAllText($path)}else{""}
    $stderr = if(Test-Path -LiteralPath $script:RunLogStderrPath -PathType Leaf){[System.IO.File]::ReadAllText($script:RunLogStderrPath)}else{""}
    $captureWithinBound = $stdout.Length -le 16777216
    $analysis = Measure-RunLogWindow $stdout $script:RunLogMarker @($script:ProcessEpochs) $captureWithinBound $captureProcessAliveAtEnd $endMarkerWriteExitCode $captureProcessExitObserved
    $status = if($analysis.coverage_complete -and $analysis.target_fatal_count -eq 0 -and $analysis.target_anr_count -eq 0 -and -not $FailureCleanup){"passed"}elseif($FailureCleanup){"diagnostic_only"}else{"failed"}
    $receipt = Save-Receipt "run-logs" (New-Receipt "run-bounded-logs" "serial-scoped-streaming-adb" $status ([ordered]@{
        provider_gap=$QfmLogGap
        capture_sha256=Get-Sha256 $path
        capture_size=(Get-Item -LiteralPath $path).Length
        marker_sha256=Get-TextSha256 $script:RunLogMarker
        start_marker_retained=$analysis.start_marker_retained
        end_marker_retained=$analysis.end_marker_retained
        end_marker_write_exit_code=$endMarkerWriteExitCode
        capture_process_alive_at_end=$captureProcessAliveAtEnd
        capture_process_exit_observed=$captureProcessExitObserved
        backlog_before_start_excluded=$true
        capture_within_16_mib_bound=$captureWithinBound
        coverage_complete=$analysis.coverage_complete
        target_uids=$analysis.target_uids
        target_pids=$analysis.target_pids
        process_epoch_count=$script:ProcessEpochs.Count
        target_fatal_count=$analysis.target_fatal_count
        target_anr_count=$analysis.target_anr_count
        target_fatal_lines_sha256=$analysis.target_fatal_lines_sha256
        target_anr_lines_sha256=$analysis.target_anr_lines_sha256
        stderr_sha256=Get-TextSha256 $stderr
        global_log_buffer_cleared=$false
        failure_cleanup=[bool]$FailureCleanup
    }))
    # A diagnostic-only fatal scan may be incomplete during failure cleanup,
    # but it may never convert an uncontained child process into success.
    Assert-RunLogProcessExitObserved $captureProcessExitObserved
    if (-not $FailureCleanup -and -not $analysis.coverage_complete) {
        throw "Run-owned log capture coverage was incomplete or exceeded its acceptance bound."
    }
    if (-not $FailureCleanup -and ($analysis.target_fatal_count -ne 0 -or $analysis.target_anr_count -ne 0)) {
        throw "Run-bounded target fatal/ANR scan failed."
    }
    return $receipt
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
        # WaitForExit(Int32) returns a Boolean. Suppress that implementation
        # detail so this secret-returning function emits only the char buffer;
        # otherwise PowerShell attempts to coerce the leading True into Char.
        [void]$process.WaitForExit(12000)
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
    if (-not (Test-ExactBoolean $manifest.connection_hub_debug_operator $true)) { throw "Debug Hub build omitted its shell operator route." }
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
    $packages = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $artifactDigests = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $expectedPackages = @($HubPackage, $SpatialPackage, $SamplePackage)
    for ($index = 0; $index -lt $Artifacts.Count; $index++) {
        $artifact = $Artifacts[$index]
        $response = Invoke-Qfm @("apk", "inspect", "--file", $artifact.path, "--json") "inspect $($artifact.label)"
        if ($null -eq $response.json -or ([string]$response.json.Sha256).ToLowerInvariant() -ne $artifact.sha256) {
            throw "File Manager inspection did not bind $($artifact.label) to its staged SHA-256."
        }
        $packageName = [string]$response.json.Identity.PackageName
        if ($packageName -ne $expectedPackages[$index] -or $null -ne $response.json.Identity.SplitName) {
            throw "File Manager inspection returned the wrong fixed package identity for $($artifact.label)."
        }
        if (-not $packages.Add($packageName) -or -not $artifactDigests.Add([string]$artifact.sha256)) {
            throw "The Hub/provider artifact set must contain three distinct packages and APK byte digests."
        }
        $signer = ([string]$response.json.Identity.SignerSha256).ToLowerInvariant()
        if ($signer -notmatch '^[0-9a-f]{64}$') { throw "File Manager returned an invalid signer for $($artifact.label)." }
        [void]$signers.Add($signer)
        $rows += [ordered]@{ label=$artifact.label; sha256=$artifact.sha256; signer_sha256=$signer; identity=$response.json.Identity }
    }
    if ($packages.Count -ne 3 -or $artifactDigests.Count -ne 3) {
        throw "The inspected artifact set is not the exact three distinct fixed applications."
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
    $preexistingTargets = @($rows | Where-Object { $_.installed })
    if ($ExistingTargetPolicy -eq "PreserveAndRestore" -and $preexistingTargets.Count -ne 0) {
        [void](Save-Receipt "pre-state-restoration-preflight" (New-Receipt "pre-state-restoration-preflight" "operator-wrapper" "failed" ([ordered]@{
            reason="preexisting_target_private_state_has_no_exact_restore_contract"
            installed_target_packages=@($preexistingTargets.package)
            running_target_packages=@($preexistingTargets | Where-Object { $_.running } | ForEach-Object { $_.package })
            mutation_started=$false
            supported_resolution="use RetainCandidate, or explicitly remove every prior target after separately preserving private state"
        })))
        throw "PreserveAndRestore refuses every pre-existing target install because restoring APK bytes cannot restore app-private state."
    }
    return $receipt
}

function Get-TargetAbsenceCleanupDecision([int]$ExitCode, [string]$Output, [string]$ErrorOutput) {
    if ($ExitCode -eq 0 -and $Output -match '(?m)^package:') { return "installed" }
    # Android's PackageManagerShellCommand displayPackageFilePath returns 1
    # with no output for a package that is not installed.  Accept only that
    # exact empty shape (plus the benign exit-0 empty variant); any diagnostic
    # text or other exit code remains a read failure.
    if ($ExitCode -in @(0,1) -and [string]::IsNullOrWhiteSpace($Output) -and
            [string]::IsNullOrWhiteSpace($ErrorOutput)) { return "already_absent" }
    return "presence_read_failed"
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
    $restoreErrors = [System.Collections.Generic.List[string]]::new()
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
                $presence = Invoke-Adb @("shell", "pm", "path", [string]$prior.package) $QfmPackageStateGap "read one fixed target before idempotent cleanup" -AllowFailure
                $cleanupDecision = Get-TargetAbsenceCleanupDecision ([int]$presence.exit_code) ([string]$presence.output) ([string]$presence.stderr)
                $wasPresent = $cleanupDecision -eq "installed"
                $targetCleanupProven = $cleanupDecision -eq "already_absent"
                if ($cleanupDecision -eq "presence_read_failed") {
                    [void]$restoreErrors.Add("target_presence_read_failed:$($prior.label)")
                } elseif ($wasPresent) {
                    $uninstall = Invoke-Adb @("shell", "pm", "uninstall", [string]$prior.package) $QfmUninstallGap "remove only a run-installed target package" -AllowFailure
                    $afterUninstall = Invoke-Adb @("shell", "pm", "path", [string]$prior.package) $QfmPackageStateGap "prove one fixed target is absent after cleanup" -AllowFailure
                    $afterDecision = Get-TargetAbsenceCleanupDecision ([int]$afterUninstall.exit_code) ([string]$afterUninstall.output) ([string]$afterUninstall.stderr)
                    if ($uninstall.exit_code -ne 0 -or $afterDecision -ne "already_absent") {
                        [void]$restoreErrors.Add("target_uninstall_not_proven:$($prior.label)")
                    } else {
                        $script:UninstallPerformed = $true
                        $targetCleanupProven = $true
                    }
                }
                $rows += [ordered]@{
                    package=$prior.package
                    policy=$ExistingTargetPolicy
                    prior_running=$prior.running
                    prior_installed=$false
                    cleanup=$(if($cleanupDecision -eq "already_absent"){"already_absent"}elseif($wasPresent -and $targetCleanupProven){"uninstalled_and_absence_proven"}else{"not_proven"})
                }
                continue
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
            [void]$restoreErrors.Add("device_invariant_changed:$name")
        }
    }
    $receipt = Save-Receipt "restore-pre-state" (New-Receipt "restore-pre-state" "target-only-cleanup" $(if($restoreErrors.Count -eq 0){"passed"}else{"partial"}) ([ordered]@{
        packages=$rows
        device_invariants_restored=($restoreErrors.Count -eq 0)
        errors=@($restoreErrors)
        unrelated_packages_touched=$false
    }))
    if ($restoreErrors.Count -ne 0) { throw "Target cleanup/restoration was partial." }
    return $receipt
}

function Revoke-RunOwnedSessionIfPresent([string]$ReceiptName) {
    if (Test-Path -LiteralPath $SessionFile -PathType Leaf) {
        return Hostess-Action "revoke"
    }
    return Save-Receipt $ReceiptName (New-Receipt "hostess-revoke" "rusty-hostess" "not_required" ([ordered]@{
        session_file_present=$false
        credentials_already_absent=$true
        run_owned_session_path_sha256=Get-TextSha256 ([System.IO.Path]::GetFullPath($SessionFile))
    }))
}

function Invoke-StandaloneCleanup($Artifacts) {
    if ([string]::IsNullOrWhiteSpace($ResumeCheckpoint)) {
        throw "Standalone Cleanup requires the exact interrupted E2E -ResumeCheckpoint."
    }
    $errors = [System.Collections.Generic.List[string]]::new()
    if ($script:Checkpoint.run_log_capture -and $script:Checkpoint.run_log_capture.active -eq $true) {
        try {
            [void](Start-RunLogCapture)
            [void](Stop-RunLogCapture -FailureCleanup)
        } catch {
            [void]$errors.Add("run_log_capture_cleanup_failed")
        }
    }
    try { [void](Revoke-RunOwnedSessionIfPresent "cleanup-hostess-revoke") }
    catch { [void]$errors.Add("hostess_revoke_failed") }
    try { [void](Restore-PreState $Artifacts) }
    catch { [void]$errors.Add("pre_state_restore_failed") }
    [void](Save-Receipt "standalone-cleanup" (New-Receipt "standalone-cleanup" "checkpoint-bound-operator" $(if($errors.Count -eq 0){"passed"}else{"partial"}) ([ordered]@{
        attempted=$true
        errors=@($errors)
        target_packages_only=$true
        run_log_capture_active_after=($null -ne $script:RunLogProcess)
    })))
    if ($errors.Count -ne 0) { throw "Standalone cleanup was partial: $($errors -join ', ')." }
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
    $failure = $launch.json.failure
    if ([string]$failure.code -ne "pre_dispatch_proof_rejected" -or
            -not (Test-ExactBoolean $failure.dispatch_attempted $false)) {
        throw "File Manager launch failed outside the reviewed export-parser gap: $($launch.combined)"
    }
    $separator = $Component.IndexOf('/')
    if ($separator -le 0 -or $separator -ge $Component.Length - 1) {
        throw "Reviewed launch fallback requires one exact package/component pair."
    }
    $package = $Component.Substring(0, $separator)
    $resolver = Invoke-Adb @(
        "shell", "cmd", "package", "query-activities", "--brief", "--components",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", $package) `
        $QfmLaunchGap "prove one fixed exported launcher component"
    $resolvedComponents = @($resolver.output -split "`r?`n" | ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($resolvedComponents.Count -ne 1 -or $resolvedComponents[0] -cne $Component) {
        throw "File Manager launch fallback did not independently resolve the exact fixed component."
    }
    $fallback = Invoke-Adb @("shell", "am", "start", "-W", "-n", $Component) $QfmLaunchGap "launch one fixed reviewed component"
    return [ordered]@{
        label=$Artifact.label
        provider="raw-adb-fallback"
        qfm_failure=$launch.json
        independently_resolved_component=$resolvedComponents[0]
        resolver=$resolver
        fallback=$fallback
    }
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
        start = "io.github.mesmerprism.rustymanifold.broker.action.START_CONNECTION_HUB"
        stop = "io.github.mesmerprism.rustymanifold.broker.action.STOP_CONNECTION_HUB"
        forget = "io.github.mesmerprism.rustymanifold.broker.action.FORGET_CONNECTION_HUB"
    }
    if ($Method -in @("start", "stop", "forget")) {
        [void](Invoke-Adb @(
            "shell", "am", "start-foreground-service", "-n", "$HubPackage/.ConnectionHubStartService",
            "-a", $lifecycleActions[$Method]) $QfmLifecycleGap `
            "dispatch one fixed DUMP-gated debug foreground-service action")
    }
    $result = Invoke-DebugOperator "status"
    if ($Method -eq "start") {
        $deadline = [DateTime]::UtcNow.AddSeconds(12)
        while (-not (Test-ExactBoolean $result.owner_receipt.listener_running $true) -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 400
            $result = Invoke-DebugOperator "status"
        }
        if (-not (Test-ExactBoolean $result.owner_receipt.listener_running $true)) { throw "Hub listener did not confirm running." }
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
    return Save-Receipt "hub-$Method" (New-Receipt "hub-$Method" "debug-shell-foreground-service" "passed" ([ordered]@{
        lifecycle_transport_gap=$QfmLifecycleGap
        observer=$result
        debug_service_exported_with_dump_permission=$true
        management_activity_validated_by_source_gate=$true
        debug_provider_performed_lifecycle_mutation=$false
    }))
}

function Prove-DebugProtocol {
    $start = Invoke-DebugOperator "start"
    if (-not (Test-ExactBoolean $start.owner_receipt.listener_running $true)) { throw "Debug protocol start proof failed." }
    $status = Invoke-DebugOperator "status"
    $stop = Invoke-DebugOperator "stop"
    if (-not (Test-ExactBoolean $status.owner_receipt.listener_running $true) -or
            -not (Test-ExactBoolean $stop.owner_receipt.listener_running $false)) {
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
    if (-not (Test-ExactBoolean $before.owner_receipt.listener_running $true)) { throw "Hub must be running before restart proof." }
    $oldPid = [int]$before.owner_receipt.pid
    $scheduled = Invoke-DebugOperator "restart-process"
    if (-not (Test-ExactBoolean $scheduled.owner_receipt.process_restart_scheduled $true) -or [int]$scheduled.owner_receipt.pid -ne $oldPid) {
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
    } while (($null -eq $status -or -not (Test-ExactBoolean $status.owner_receipt.listener_running $true)) -and [DateTime]::UtcNow -lt $deadline)
    if ($null -eq $status -or -not (Test-ExactBoolean $status.owner_receipt.listener_running $true) -or
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

function Force-HistoryRollover {
    if ($LegacyV1) { throw "Legacy v1 is explicitly not safe across authority-history rollover." }
    $result = Invoke-DebugOperator "force-rollover"
    if ([string]$result.owner_receipt.action -ne "force-rollover" -or
            -not (Test-ExactBoolean $result.owner_receipt.applied $true) -or
            [string]$result.owner_receipt.status -ne "applied" -or
            -not (Test-ExactBoolean $result.owner_receipt.listener_running $true)) {
        throw "Quest-owned forced history rollover was not applied while the Hub remained active."
    }
    return Save-Receipt "hub-force-rollover" (New-Receipt "hub-force-rollover" "debug-shell-provider-gap" "passed" ([ordered]@{
        provider_gap=$QfmServiceGap
        owner_receipt=$result.owner_receipt
        legacy_v1=$false
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
        } while (($null -eq $status -or -not (Test-ExactBoolean $status.owner_receipt.listener_running $true) -or [string]$status.owner_receipt.origin -eq "http://0.0.0.0:0") -and [DateTime]::UtcNow -lt $deadline)
        if ($null -eq $status -or -not (Test-ExactBoolean $status.owner_receipt.listener_running $true)) { throw "Hub did not rebind after Wi-Fi restoration." }
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

function Enable-BoundedVirtualProximity {
    if (-not $UseBoundedVirtualProximity) { return $null }
    $row = Invoke-Adb @(
        "shell", "am", "broadcast", "-a", "com.oculus.vrpowermanager.prox_close",
        "--ei", "duration", "600000") $QfmProximityGap `
        "enable a ten-minute self-expiring virtual proximity window"
    if ($row.output -notmatch 'Broadcast completed: result=0') {
        throw "Virtual proximity window was not acknowledged by Horizon OS."
    }
    $script:VirtualProximityEnabledByRun = $true
    return Save-Receipt "virtual-proximity-enable" (New-Receipt "virtual-proximity-enable" "bounded-horizon-broadcast" "passed" ([ordered]@{
        duration_ms=600000
        self_expiring=$true
        explicit_restore_required=$true
        command=$row
    }))
}

function Restore-BoundedVirtualProximity {
    if (-not $script:VirtualProximityEnabledByRun) { return $null }
    $row = Invoke-Adb @(
        "shell", "am", "broadcast", "-a", "com.oculus.vrpowermanager.automation_disable") `
        $QfmProximityGap "restore normal physical proximity handling"
    if ($row.output -notmatch 'Broadcast completed: result=0') {
        throw "Normal physical proximity restoration was not acknowledged by Horizon OS."
    }
    $script:VirtualProximityEnabledByRun = $false
    return Save-Receipt "virtual-proximity-restore" (New-Receipt "virtual-proximity-restore" "bounded-horizon-broadcast" "passed" ([ordered]@{
        normal_physical_proximity_restored=$true
        command=$row
    }))
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

function Test-JsonContainsUnredactedHostessSecret($Value) {
    if ($null -eq $Value -or $Value -is [string] -or $Value.GetType().IsPrimitive) {
        return $false
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [pscustomobject]) {
        foreach ($item in $Value) {
            if (Test-JsonContainsUnredactedHostessSecret $item) { return $true }
        }
        return $false
    }
    foreach ($property in $Value.PSObject.Properties) {
        $name = [string]$property.Name
        if ($name -match '^(?i:pairing_code|bearer_token|session_bearer)$') {
            return $true
        }
        if ($name -match '^(?i:pairing_code|bearer_token|session_bearer)_in_receipt$') {
            if (-not (Test-ExactBoolean $property.Value $false)) { return $true }
            continue
        }
        if (Test-JsonContainsUnredactedHostessSecret $property.Value) { return $true }
    }
    return $false
}

function Invoke-Hostess([string]$Verb, [string[]]$Arguments, [string]$ReceiptName) {
    if ((Get-Sha256 $script:Hostess) -ne $HostessCliSha256) { throw "Hostess CLI changed after run lock." }
    if ((Get-Sha256 $script:Python) -ne $PythonSha256) { throw "Python changed after the run lock was acquired." }
    $result = Invoke-Captured $script:Python (@($script:Hostess, $Verb) + $Arguments) "Hostess $Verb"
    if ($result.exit_code -ne 0) { throw "Hostess $Verb failed: $($result.combined)" }
    $json = $result.output | ConvertFrom-Json
    if (Test-JsonContainsUnredactedHostessSecret $json) {
        throw "Hostess output may contain an unredacted secret."
    }
    return Save-Receipt $ReceiptName (New-Receipt "hostess-$Verb" "rusty-hostess" "passed" $json)
}

function Read-HostessSurfaces {
    if ((Get-Sha256 $script:Hostess) -ne $HostessCliSha256) { throw "Hostess CLI changed after run lock." }
    if ((Get-Sha256 $script:Python) -ne $PythonSha256) { throw "Python changed after the run lock was acquired." }
    $result = Invoke-Captured $script:Python @($script:Hostess, "list-surfaces", "--session-file", $SessionFile) "Hostess list-surfaces"
    if ($result.exit_code -ne 0) { throw "Hostess list-surfaces failed: $($result.combined)" }
    $snapshot = $result.output | ConvertFrom-Json
    Assert-HostessProtocolFlags $snapshot "rusty.hostess.connection_hub.surface_list_receipt.v2"
    if ([string]$snapshot.status -ne "passed" -or
            -not (Test-ExactJsonInteger $snapshot.transport_epoch 1) -or
            (-not $LegacyV1 -and -not (Test-ExactJsonInteger $snapshot.next_external_request_sequence 1)) -or
            ($LegacyV1 -and $null -ne $snapshot.next_external_request_sequence) -or
            @($snapshot.surfaces).Count -gt 128) {
        throw "Hostess surface snapshot did not satisfy the exact bounded list receipt contract."
    }
    return $snapshot
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
    $pairArguments = @(
        $script:Hostess, "pair", "--origin", $Origin,
        "--transport-classification", "trusted_lan_experimental",
        "--allow-insecure-trusted-lan", "--pairing-code-stdin",
        "--controller-identity-sha256", $ControllerIdentitySha256,
        "--session-file", $SessionFile)
    if ($LegacyV1) { $pairArguments += "--legacy-v1" }
    foreach ($argument in $pairArguments) {
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
        if ([string]$json.'$schema' -ne "rusty.hostess.connection_hub.pair_receipt.v2" -or
                [string]$json.status -ne "passed" -or
                -not (Test-ExactBoolean $json.session_redacted $true) -or
                $null -ne $json.server_receipt.session) {
            throw "Hostess pair did not attest a redacted session receipt."
        }
        Assert-HostessProtocolFlags $json "rusty.hostess.connection_hub.pair_receipt.v2"
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

function Assert-HostessProtocolFlags($Details, [string]$ExpectedSchema) {
    $expectedProtocol = if($LegacyV1){"rusty.quest.connection_hub.v1"}else{"rusty.quest.connection_hub.v2"}
    if ([string]$Details.'$schema' -ne $ExpectedSchema -or
            [string]$Details.socket_protocol -ne $expectedProtocol -or
            $Details.rollover_safe -isnot [bool] -or
            $Details.rollover_safe -ne (-not [bool]$LegacyV1)) {
        throw "Hostess receipt did not preserve the exact selected socket protocol/rollover posture."
    }
}

function Assert-HostessStatusReceipt($Details) {
    if ([string]$Details.'$schema' -ne "rusty.hostess.connection_hub.status_receipt.v1" -or
            [string]$Details.status -ne "passed" -or
            $Details.hub.listener_enabled -isnot [bool] -or
            $Details.hub.pairing_available -isnot [bool] -or
            [string]$Details.hub.desired_connection_state -notin @("stopped", "running")) {
        throw "Hostess status did not satisfy the exact safe-status receipt contract."
    }
}

function Hostess-Action([string]$Kind) {
    if ($Kind -eq "status") {
        $status = Invoke-Hostess "status" @("--origin", $Origin, "--transport-classification", "trusted_lan_experimental", "--allow-insecure-trusted-lan") "hostess-status"
        Assert-HostessStatusReceipt $status.details
        return $status
    }
    if ($Kind -eq "pair") {
        $args = @("--origin", $Origin, "--transport-classification", "trusted_lan_experimental", "--allow-insecure-trusted-lan", "--controller-identity-sha256", $ControllerIdentitySha256, "--session-file", $SessionFile)
        if ($LegacyV1) { $args += "--legacy-v1" }
        if ($PairingCodeStdin) { $args += "--pairing-code-stdin" }
        elseif ($PairingCodeFd -ge 0) { $args += @("--pairing-code-fd", [string]$PairingCodeFd) }
        $receipt = Invoke-Hostess "pair" $args "hostess-pair"
        Assert-HostessProtocolFlags $receipt.details "rusty.hostess.connection_hub.pair_receipt.v2"
        if (-not (Test-ExactBoolean $receipt.details.session_redacted $true)) { throw "Hostess pair receipt was not secret-redacted." }
        $script:HostessPaired = $true
        return $receipt
    }
    if ($Kind -eq "list") {
        $list = Invoke-Hostess "list-surfaces" @("--session-file", $SessionFile) "hostess-list"
        Assert-HostessProtocolFlags $list.details "rusty.hostess.connection_hub.surface_list_receipt.v2"
        if ([string]$list.details.status -ne "passed") { throw "Hostess list receipt did not pass." }
        return $list
    }
    if ($Kind -eq "watch") {
        $watch = Invoke-Hostess "connect-watch" @("--session-file", $SessionFile, "--seconds", [string]$WatchSeconds, "--max-events", "128", "--keepalive-interval-seconds", "1") "hostess-watch"
        Assert-HostessProtocolFlags $watch.details "rusty.hostess.connection_hub.watch_receipt.v2"
        if (-not $LegacyV1 -and (
                -not (Test-ExactJsonInteger $watch.details.keepalive_count 1) -or
                -not (Test-ExactJsonInteger $watch.details.next_external_request_sequence 2))) {
            throw "Hostess watch did not prove v2 keepalive activity."
        }
        return $watch
    }
    if ($Kind -eq "renewal") {
        if ($LegacyV1) { throw "Legacy v1 has no sliding keepalive renewal contract." }
        $before = Read-HostessSurfaces
        Assert-HostessProtocolFlags $before "rusty.hostess.connection_hub.surface_list_receipt.v2"
        $renewal = Invoke-Hostess "connect-watch" @("--session-file", $SessionFile, "--seconds", "3", "--max-events", "16", "--keepalive-interval-seconds", "1") "hostess-keepalive-renewal"
        $proof = $renewal.details
        Assert-HostessProtocolFlags $proof "rusty.hostess.connection_hub.watch_receipt.v2"
        if (-not (Test-ExactBoolean $proof.transport_epoch_changed $true) -or
                -not (Test-ExactJsonInteger $proof.keepalive_count 1) -or
                -not (Test-ExactJsonInteger $before.next_external_request_sequence 1) -or
                -not (Test-ExactJsonInteger $proof.next_external_request_sequence 1) -or
                [long]$proof.next_external_request_sequence -ne
                    ([long]$before.next_external_request_sequence + [int]$proof.keepalive_count)) {
            throw "Hostess keepalive renewal did not prove exact next-sequence advancement."
        }
        return $renewal
    }
    if ($Kind -eq "reconnect") {
        $before = Read-HostessSurfaces
        $after = Invoke-Hostess "list-surfaces" @("--session-file", $SessionFile) "hostess-reconnect"
        $afterDetails = $after.details
        Assert-HostessProtocolFlags $before "rusty.hostess.connection_hub.surface_list_receipt.v2"
        Assert-HostessProtocolFlags $afterDetails "rusty.hostess.connection_hub.surface_list_receipt.v2"
        if (-not (Test-ExactBoolean $afterDetails.transport_epoch_changed $true) -or
                -not (Test-ExactJsonInteger $before.transport_epoch 1) -or
                -not (Test-ExactJsonInteger $afterDetails.transport_epoch 1) -or
                [long]$afterDetails.transport_epoch -le [long]$before.transport_epoch -or
                (-not $LegacyV1 -and (
                    [string]::IsNullOrWhiteSpace([string]$afterDetails.expires_at_utc) -or
                    -not (Test-ExactJsonInteger $before.next_external_request_sequence 1) -or
                    -not (Test-ExactJsonInteger $afterDetails.next_external_request_sequence 1) -or
                    [long]$afterDetails.next_external_request_sequence -ne [long]$before.next_external_request_sequence))) {
            throw "Reconnect did not prove a newer transport with unchanged v2 external sequence."
        }
        return $after
    }
    if ($Kind -eq "simulate") {
        $simulation = Invoke-Hostess "simulate-e2e" @() "hostess-v2-simulation"
        $requiredChecks = @(
            "status_safe_and_labelled", "pair_secret_redacted",
            "bearer_absent_from_websocket_url", "media_surface_appeared",
            "media_command_scoped", "media_provider_applied", "replay_failed_closed",
            "unknown_surface_failed_closed", "unknown_command_failed_closed",
            "second_surface_appeared", "second_provider_command_scoped",
            "keepalive_slid_session", "media_surface_removed", "logical_session_preserved",
            "transport_epoch_advanced", "reconnect_resynced_next_sequence",
            "restart_preserved_sequence_fence", "rollover_replay_failed_closed",
            "rollover_replay_not_redispatched", "reconnect_snapshot_preserved_surfaces",
            "post_reconnect_command_accepted", "explicit_revoke_applied",
            "revoke_active_socket_closed", "revoke_stale_bearer_rejected",
            "local_credentials_deleted", "revoke_terminated_socket",
            "post_revoke_reconnect_rejected", "high_rate_data_plane_absent",
            "dispatch_never_crossed_provider")
        $observedChecks = @($simulation.details.checks.PSObject.Properties.Name)
        $missingChecks = @($requiredChecks | Where-Object { $observedChecks -notcontains $_ })
        if ([string]$simulation.details.'$schema' -ne "rusty.hostess.connection_hub.simulated_e2e_receipt.v2" -or
                [string]$simulation.details.status -ne "passed" -or
                $missingChecks.Count -ne 0 -or
                @($simulation.details.checks.PSObject.Properties | Where-Object { -not (Test-ExactBoolean $_.Value $true) }).Count -ne 0) {
            throw "Hostess v2 lost-receipt/rollover-replay simulation failed."
        }
        return $simulation
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
            if (-not (Test-ExactBoolean $proof.$field $true)) { throw "Hostess revoke did not prove $field." }
        }
        Assert-HostessProtocolFlags $proof "rusty.hostess.connection_hub.revoke_receipt.v2"
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
                -not (Test-ExactBoolean $effect.request_binding_exact $true) -or
                -not (Test-ExactBoolean $effect.authority_accepted $true) -or
                -not (Test-ExactBoolean $effect.provider_applied $true) -or
                [string]$effect.status -ne "provider_effect_observed") {
            throw "Hostess command was authorized but did not prove the exact provider effect."
        }
        Assert-HostessProtocolFlags $effect "rusty.hostess.connection_hub.command_receipt.v2"
        if ($LegacyV1) {
            if ($null -ne $effect.request_sequence -or $null -ne $effect.next_external_request_sequence) {
                throw "Legacy v1 command receipt falsely claimed sequenced rollover safety."
            }
        } elseif (-not (Test-ExactJsonInteger $effect.request_sequence 1) -or
                -not (Test-ExactJsonInteger $effect.next_external_request_sequence 2) -or
                [long]$effect.next_external_request_sequence -ne ([long]$effect.request_sequence + 1)) {
            throw "V2 command receipt did not prove exact accepted next-sequence advancement."
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
        socket_protocol=$(if($LegacyV1){"rusty.quest.connection_hub.v1"}else{"rusty.quest.connection_hub.v2"})
        rollover_safe=(-not [bool]$LegacyV1)
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
        $receipt = Get-Content -Raw -LiteralPath $_ | ConvertFrom-Json
        if ($Result -eq "passed" -and ([string]$receipt.'$schema' -ne $ReceiptSchema -or
                [string]$receipt.status -in @("failed", "partial"))) {
            throw "A passed evidence manifest cannot include a failed or untyped receipt."
        }
        [ordered]@{ name=(Split-Path -Leaf $_); sha256=Get-Sha256 $_; size=(Get-Item $_).Length }
    })
    $manifest = [ordered]@{
        '$schema' = $ManifestSchema
        action = $Action
        serial = $Serial
        result = $Result
        generated_at_utc = [DateTime]::UtcNow.ToString("o")
        receipts = $entries
        cleanup = [ordered]@{ target_packages_only=$true; uninstall_performed=[bool]$script:UninstallPerformed; adb_transport_changed=$false }
        checkpoint_sha256=$(if(Test-Path -LiteralPath $script:CheckpointPath -PathType Leaf){Get-Sha256 $script:CheckpointPath}else{$null})
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
        socket_protocol = $(if($LegacyV1){"rusty.quest.connection_hub.v1"}else{"rusty.quest.connection_hub.v2"})
        rollover_safe = (-not [bool]$LegacyV1)
        reviewed_fallbacks = @(
            $QfmLaunchGap, $QfmLifecycleGap, $QfmServiceGap, $QfmStopGap,
            $QfmLogGap, $QfmDeviceStateGap, $QfmPackageStateGap,
            $QfmUninstallGap, $QfmWifiGap, $QfmProximityGap)
        hostess_secret_input = @("debug-shell-to-stdin-memory-only", "hidden-prompt", "stdin", "inherited-fd", "DPAPI-CurrentUser-session")
        e2e_sequence = @(
            "prerequisites+pre-state", "build", "inspect", "install+installed-byte-signer-readback",
            "run-log-capture-start",
            "debug-protocol-proof", "real-activity-foreground-service+notification", "pair",
            "hostess-v2-lost-receipt+rollover-replay-simulation",
            "spatial-present+command", "provider-lifetime-over-2m+command",
            "process-death+start-sticky+provider-reregister+command", "wifi-rebind-or-explicit-safety-skip",
            "spatial-removed+sample-present+command", "sample-removed+spatial-returned+command",
            "hub-persists-across-app-switches",
            "reconnect-epoch+sequence-assertion", "v2-keepalive-renewal-or-explicit-legacy-skip",
            "quest-owned-rollover+resync+fresh-command-or-explicit-legacy-skip",
            "revoke+closed-socket-assertion",
            $(if($RequireBrowserE2E){"real-browser-sequential-surface-e2e"}else{"browser-e2e-explicitly-not-required"}),
            "run-bounded-target-fatal-anr-scan",
            "target-only-pre-state-restore")
        checkpoint_resume = "serial+protocol+providers+artifacts+build-manifest+policy-bound"
        provider_lifetime_seconds = $ProviderLifetimeSeconds
        bounded_virtual_proximity = [bool]$UseBoundedVirtualProximity
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
    $expectedSessionFile = Join-Path $script:RunDir "hostess-session.json"
    if ([string]::IsNullOrWhiteSpace($SessionFile)) {
        $SessionFile = $expectedSessionFile
    } elseif ($Action -in @("E2E", "SimulateE2E") -and
            [System.IO.Path]::GetFullPath($SessionFile) -ne $expectedSessionFile) {
        throw "E2E Hostess session file must be the run-owned fixed session path."
    }
}
$needsQfm = $Action -in @("Prerequisites", "Inspect", "Install", "Start", "Status", "LaunchSpatial", "LaunchSample", "Cleanup", "E2E")
$needsHostess = $Action -like "Hostess*" -or $Action -in @("WaitSurface", "WaitSurfaceAbsent", "Cleanup", "E2E")
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

if ($needsQfm) { $script:Qfm = Lock-ExactProvider $FileManagerCli $FileManagerSha256 "File Manager CLI" }
if ($needsHostess) { $script:Hostess = Lock-ExactProvider $HostessCli $HostessCliSha256 "Hostess CLI" }
if (@($script:Checkpoint.completed_stages) -contains "pair-hostess") { $script:HostessPaired = $true }
if (@($script:Checkpoint.completed_stages) -contains "real-hub-start") { $script:HubStarted = $true }

$finalResult = "failed"
try {
    if ($Action -eq "SimulateE2E") {
        $syntheticMarker = "synthetic-window"
        $syntheticEpochs = @([pscustomobject]@{uid=10123;pids=@(4242);package=$HubPackage})
        $syntheticLog = @(
            "1.000 10123 4242 4242 E AndroidRuntime: FATAL EXCEPTION: backlog",
            "1.100 2000 88 88 I RQConnectionHubE2E: START $syntheticMarker",
            "1.200 10123 4242 4999 E AndroidRuntime: FATAL EXCEPTION: worker",
            "1.300 2000 77 77 F libc: Fatal signal 11 in $HubPackage",
            "1.400 2000 88 88 I RQConnectionHubE2E: END $syntheticMarker") -join "`n"
        $synthetic = Measure-RunLogWindow $syntheticLog $syntheticMarker $syntheticEpochs $true $true 0
        if (-not (Test-ExactBoolean $synthetic.coverage_complete $true) -or $synthetic.target_fatal_count -ne 2) {
            throw "Synthetic UID/PID/native fatal classifier did not bind the exact START/END run window."
        }
        $missingEnd = Measure-RunLogWindow ($syntheticLog -replace "(?m)^.*END $syntheticMarker$", "") $syntheticMarker $syntheticEpochs $true $true 0
        $deadCapture = Measure-RunLogWindow $syntheticLog $syntheticMarker $syntheticEpochs $true $false 0
        if ($missingEnd.coverage_complete -ne $false -or $deadCapture.coverage_complete -ne $false) {
            throw "Synthetic run-log coverage accepted a missing END marker or dead capture."
        }
        $nonTerminationRejected = $false
        try { Assert-RunLogProcessExitObserved $false } catch { $nonTerminationRejected = $true }
        if (-not $nonTerminationRejected) {
            throw "Synthetic failure cleanup accepted an uncontained log capture process."
        }
        if ((Get-TargetAbsenceCleanupDecision 1 "" "") -ne "already_absent" -or
                (Get-TargetAbsenceCleanupDecision 0 "" "") -ne "already_absent" -or
                (Get-TargetAbsenceCleanupDecision 0 "package:/data/app/example/base.apk" "") -ne "installed" -or
                (Get-TargetAbsenceCleanupDecision 1 "" "permission denied") -ne "presence_read_failed" -or
                (Get-TargetAbsenceCleanupDecision 2 "" "") -ne "presence_read_failed") {
            throw "Synthetic target cleanup did not preserve idempotent already-absent behavior."
        }
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
    } elseif ($Action -eq "Cleanup") { $a=Resolve-Apks; [void](Invoke-StandaloneCleanup $a)
    } elseif ($Action -eq "E2E") {
        Invoke-Stage "prerequisites" { [void](Test-Prerequisites) }
        Invoke-Stage "build" { [void](Build-All); [void](Resolve-Apks) }
        $a=Resolve-Apks
        Invoke-Stage "inspect" { [void](Inspect-All $a) }
        Invoke-Stage "capture-pre-state" { [void](Capture-PreState $a) }
        Invoke-Stage "install" { [void](Install-All $a) }
        if (@($script:Checkpoint.completed_stages) -notcontains "logs") {
            $captureCheckpoint = $script:Checkpoint.run_log_capture
            $postInstallCompleted = @($script:Checkpoint.completed_stages | Where-Object {
                $_ -in @("debug-protocol-proof", "real-hub-start", "pair-hostess", "spatial-first",
                    "provider-lifetime-over-2m", "process-restart", "wifi-rebind", "sample-switch",
                    "spatial-return", "reconnect", "keepalive-renewal", "history-rollover",
                    "hostess-v2-simulation", "revoke", "browser-e2e")
            }).Count -gt 0
            if ($postInstallCompleted -and ($null -eq $captureCheckpoint -or -not (Test-ExactBoolean $captureCheckpoint.active $true))) {
                throw "Resume cannot prove the earlier target log window because its run-owned capture is not active."
            }
            if (@($script:Checkpoint.completed_stages) -contains "run-log-capture-start") {
                [void](Start-RunLogCapture)
            } else {
                Invoke-Stage "run-log-capture-start" { [void](Start-RunLogCapture) }
            }
        }
        Invoke-Stage "debug-protocol-proof" { [void](Prove-DebugProtocol) }
        Invoke-Stage "real-hub-start" {
            [void](Hub-Action "start" $a[0])
            [void](Record-TargetProcessEpoch "hub-started")
        }
        if ([string]::IsNullOrWhiteSpace($Origin)) { $Origin = [string](Invoke-DebugOperator "status").owner_receipt.origin }
        Invoke-Stage "pair-hostess" {
            [void](Hostess-Action "status")
            [char[]]$pairingSecret = Get-DebugPairingSecret
            try { [void](Invoke-HostessPairWithSecret $pairingSecret) }
            finally { [Array]::Clear($pairingSecret, 0, $pairingSecret.Length); $pairingSecret = $null }
        }
        Invoke-Stage "hostess-v2-simulation" { [void](Hostess-Action "simulate") }
        [void](Enable-BoundedVirtualProximity)
        Invoke-Stage "spatial-first" {
            [void](Launch-Provider $a "spatial")
            [void](Wait-Surface "surface.spatial_video_control.media" $true)
            [void](Wait-Surface "surface.connection_hub_sample.toggle" $false)
            [void](Record-TargetProcessEpoch "spatial-first")
            $SurfaceId="surface.spatial_video_control.media"; $CommandId="command.spatial_video_control.play"; [void](Hostess-Action "command")
            if (-not (Test-ExactBoolean (Invoke-DebugOperator "status").owner_receipt.listener_running $true)) { throw "Hub stopped across Spatial app switch." }
        }
        Invoke-Stage "provider-lifetime-over-2m" {
            $deadline=[DateTime]::UtcNow.AddSeconds($ProviderLifetimeSeconds)
            $nextProbe=[DateTime]::UtcNow
            while ([DateTime]::UtcNow -lt $deadline) {
                if ([DateTime]::UtcNow -ge $nextProbe) {
                    if (-not (Test-ExactBoolean (Invoke-DebugOperator "status").owner_receipt.listener_running $true)) { throw "Hub stopped during provider lifetime hold." }
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
            [void](Record-TargetProcessEpoch "hub-restarted")
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
            [void](Record-TargetProcessEpoch "sample-switch")
            $SurfaceId="surface.connection_hub_sample.toggle"; $CommandId="command.connection_hub_sample.toggle"; [void](Hostess-Action "command")
            if (-not (Test-ExactBoolean (Invoke-DebugOperator "status").owner_receipt.listener_running $true)) { throw "Hub stopped across Sample app switch." }
        }
        Invoke-Stage "spatial-return" {
            [void](Launch-Provider $a "spatial")
            [void](Wait-Surface "surface.connection_hub_sample.toggle" $false)
            [void](Wait-Surface "surface.spatial_video_control.media" $true)
            [void](Record-TargetProcessEpoch "spatial-return")
            $SurfaceId="surface.spatial_video_control.media"; $CommandId="command.spatial_video_control.play"; [void](Hostess-Action "command")
        }
        Invoke-Stage "reconnect" { [void](Hostess-Action "reconnect") }
        Invoke-Stage "keepalive-renewal" {
            if ($LegacyV1) {
                [void](Save-Receipt "hostess-v2-keepalive-renewal" (New-Receipt "hostess-v2-keepalive-renewal" "optional-v2-continuity" "not_required" ([ordered]@{
                    legacy_v1=$true
                    rollover_safe=$false
                    acceptance_claimed=$false
                })))
            } else { [void](Hostess-Action "renewal") }
        }
        Invoke-Stage "history-rollover" {
            if ($LegacyV1) {
                [void](Save-Receipt "history-rollover" (New-Receipt "history-rollover" "optional-v2-continuity" "not_required" ([ordered]@{
                    legacy_v1=$true
                    rollover_safe=$false
                    acceptance_claimed=$false
                })))
            } else {
                $beforeRollover = Read-HostessSurfaces
                Assert-HostessProtocolFlags $beforeRollover "rusty.hostess.connection_hub.surface_list_receipt.v2"
                if (-not (Test-ExactJsonInteger $beforeRollover.next_external_request_sequence 1) -or
                        -not (Test-ExactJsonInteger $beforeRollover.transport_epoch 1)) {
                    throw "Pre-rollover Hostess state did not contain a valid sequence and transport epoch."
                }
                $beforeSequence = [long]$beforeRollover.next_external_request_sequence
                $beforeTransportEpoch = [long]$beforeRollover.transport_epoch
                [void](Force-HistoryRollover)
                $afterRollover = Hostess-Action "reconnect"
                $afterDetails = $afterRollover.details
                if (-not (Test-ExactJsonInteger $afterDetails.next_external_request_sequence 1) -or
                        -not (Test-ExactJsonInteger $afterDetails.transport_epoch 1) -or
                        [long]$afterDetails.next_external_request_sequence -ne $beforeSequence -or
                        [long]$afterDetails.transport_epoch -le $beforeTransportEpoch) {
                    throw "Post-rollover reconnect did not preserve the pre-rollover sequence on a newer transport."
                }
                $SurfaceId="surface.spatial_video_control.media"
                $CommandId="command.spatial_video_control.pause"
                $freshCommand = Hostess-Action "command"
                $commandDetails = $freshCommand.details
                if (-not (Test-ExactJsonInteger $commandDetails.request_sequence 1) -or
                        -not (Test-ExactJsonInteger $commandDetails.next_external_request_sequence 2) -or
                        [long]$commandDetails.request_sequence -ne $beforeSequence -or
                        [long]$commandDetails.next_external_request_sequence -ne ($beforeSequence + 1)) {
                    throw "Fresh post-rollover command was not bound to the exact pre-rollover next sequence."
                }
                [void](Save-Receipt "history-rollover-continuity" (New-Receipt "history-rollover-continuity" "hostess+quest-authority" "passed" ([ordered]@{
                    legacy_v1=$false
                    rollover_safe=$true
                    pre_rollover_next_external_request_sequence=$beforeSequence
                    pre_rollover_transport_epoch=$beforeTransportEpoch
                    post_rollover_next_external_request_sequence=[long]$afterDetails.next_external_request_sequence
                    post_rollover_transport_epoch=[long]$afterDetails.transport_epoch
                    fresh_command_request_sequence=[long]$commandDetails.request_sequence
                    fresh_command_next_external_request_sequence=[long]$commandDetails.next_external_request_sequence
                    sequence_fence_preserved=$true
                    fresh_command_advanced_exactly_once=$true
                })))
            }
        }
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
        [void](Restore-BoundedVirtualProximity)
        Invoke-Stage "logs" {
            [void](Record-TargetProcessEpoch "final")
            [void](Stop-RunLogCapture)
        }
        Invoke-Stage "restore-pre-state" { [void](Restore-PreState $a) }
    }
    if ($Action -in @("E2E", "SimulateE2E")) { Assert-CheckpointStageClosure }
    $finalResult = "passed"
} finally {
    if ($Action -eq "E2E" -and $finalResult -ne "passed") {
        $cleanupErrors = [System.Collections.Generic.List[string]]::new()
        if ($null -ne $script:RunLogProcess) {
            try { [void](Stop-RunLogCapture -FailureCleanup) } catch { [void]$cleanupErrors.Add("run_log_capture_cleanup_failed") }
        }
        if ($script:VirtualProximityEnabledByRun) {
            try { [void](Restore-BoundedVirtualProximity) }
            catch { [void]$cleanupErrors.Add("virtual_proximity_restore_failed") }
        }
        try { [void](Revoke-RunOwnedSessionIfPresent "failure-cleanup-hostess-revoke") }
        catch { [void]$cleanupErrors.Add("hostess_revoke_failed") }
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
