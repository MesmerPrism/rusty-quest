[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BuildManifest,
    [Parameter(Mandatory=$true)][string]$RollbackEvidence,
    [Parameter(Mandatory=$true)][string]$StaticGateReceipt,
    [Parameter(Mandatory=$true)][string]$FeatureLockPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$FeatureLockSha256,
    [Parameter(Mandatory=$true)][ValidateRange(1, [int]::MaxValue)][int]$FeatureLockRevision,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$FeatureLockFingerprint,
    [Parameter(Mandatory=$true)][ValidateRange(1, 2100000000)][int]$ExpectedVersionCode,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSignerCertificateSha256,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$')][string]$ExpectedPackageName,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$')][string]$SpatialCameraPanelPackageName,
    [Parameter(Mandatory=$true)][string]$FileManagerCli,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$FileManagerSha256,
    [Parameter(Mandatory=$true)][string]$AdbPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{64}$')][string]$AdbSha256,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._:-]{3,127}$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$OutDir
)

$ErrorActionPreference = "Stop"
$ExpectedVersionName = "0.1.0-unit020-lan-20260913"
$ExpectedFeatureId = "q2q-broker-compatibility-diagnostic-v1"
$ProviderAuthority =
    "io.github.mesmerprism.rustymanifold.broker.debug-remote-camera-control"
$ExpectedObservationContract = "questionable.file_manager.app_runtime_observation.v5"
$MaxChildOutputChars = 262144
$ChildTimeoutMilliseconds = 45000
$script:RawResultsDir = $null

function Get-Sha256([Parameter(Mandatory=$true)][string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-TextSha256([string]$Text) {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
    try {
        return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Resolve-ExactFile([string]$Path, [string]$ExpectedSha256, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label does not exist."
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $lock = [IO.File]::Open($resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        if ((Get-Sha256 $resolved) -cne $ExpectedSha256) { throw "$Label SHA-256 mismatch." }
    } finally { $lock.Dispose() }
    return $resolved
}

function Assert-OutputIsolated([string]$OutputPath, [string[]]$RetainedPath) {
    $outputFull = [IO.Path]::GetFullPath($OutputPath).TrimEnd('\', '/')
    if (Test-Path -LiteralPath $outputFull) {
        throw "OutDir already exists; preserve prior diagnostic evidence."
    }
    $cursor = Split-Path -Parent $outputFull
    while (-not [string]::IsNullOrWhiteSpace($cursor)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "OutDir cannot traverse a reparse point: $cursor"
            }
        }
        $parent = Split-Path -Parent $cursor
        if ($parent -ceq $cursor) { break }
        $cursor = $parent
    }
    $separator = [string][IO.Path]::DirectorySeparatorChar
    foreach ($retained in $RetainedPath) {
        $retainedFull = (Resolve-Path -LiteralPath $retained).Path.TrimEnd('\', '/')
        if ($retainedFull.Equals($outputFull, [StringComparison]::OrdinalIgnoreCase) -or
            $retainedFull.StartsWith($outputFull + $separator,
                [StringComparison]::OrdinalIgnoreCase) -or
            $outputFull.StartsWith($retainedFull + $separator,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw "OutDir must be disjoint from retained diagnostic input: $retainedFull"
        }
    }
}

function Invoke-Captured([string]$File, [string[]]$Arguments, [string]$Label,
        [switch]$AllowFailure) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $File
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { [void]$start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "$Label did not start." }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $timedOut = -not $process.WaitForExit($ChildTimeoutMilliseconds)
        if ($timedOut) {
            $process.Kill($true)
            $process.WaitForExit()
        }
        $stdoutText = $stdoutTask.GetAwaiter().GetResult()
        $stderrText = $stderrTask.GetAwaiter().GetResult()
        $outputTruncated = $stdoutText.Length -gt $MaxChildOutputChars
        $stderrTruncated = $stderrText.Length -gt $MaxChildOutputChars
        if ($outputTruncated) { $stdoutText = $stdoutText.Substring(0, $MaxChildOutputChars) }
        if ($stderrTruncated) { $stderrText = $stderrText.Substring(0, $MaxChildOutputChars) }
        $result = [ordered]@{
            label=$Label; exit_code=$process.ExitCode; output=$stdoutText; stderr=$stderrText
            timed_out=$timedOut; output_truncated=$outputTruncated; stderr_truncated=$stderrTruncated
        }
        if ($null -ne $script:RawResultsDir) {
            $script:RawResultSequence += 1
            $safeLabel = [regex]::Replace($Label, '[^A-Za-z0-9._-]+', '-').Trim('-')
            $rawPath = Join-Path $script:RawResultsDir (
                ("{0:D3}-{1}.json" -f $script:RawResultSequence, $safeLabel))
            Write-Json $rawPath $result
            $result["raw_result_path"] = $rawPath
        }
        if (($process.ExitCode -ne 0 -or $timedOut -or $outputTruncated -or $stderrTruncated) -and -not $AllowFailure) {
            throw "$Label failed with exit code $($process.ExitCode)."
        }
        return $result
    } catch {
        if ($null -ne $script:RawResultsDir) {
            $script:RawResultSequence += 1
            $safeLabel = [regex]::Replace($Label, '[^A-Za-z0-9._-]+', '-').Trim('-')
            $rawPath = Join-Path $script:RawResultsDir (
                ("{0:D3}-{1}-failure.json" -f $script:RawResultSequence, $safeLabel))
            Write-Json $rawPath ([ordered]@{
                label=$Label; failed=$true; message=[string]$_.Exception.Message
                deadline_milliseconds=$ChildTimeoutMilliseconds; output_cap_chars=$MaxChildOutputChars
            })
        }
        throw
    } finally {
        $process.Dispose()
    }
}

function Invoke-Qfm([string[]]$Arguments, [string]$Label, [switch]$AllowFailure) {
    $toolLock = [IO.File]::Open($script:Qfm, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $adbLock = [IO.File]::Open($script:Adb, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        if ((Get-Sha256 $script:Qfm) -cne $FileManagerSha256) { throw "File Manager CLI changed after its exact input lock." }
        if ((Get-Sha256 $script:Adb) -cne $AdbSha256) { throw "ADB changed after its exact input lock." }
        $result = Invoke-Captured $script:Qfm $Arguments $Label -AllowFailure:$AllowFailure
        if ((Get-Sha256 $script:Qfm) -cne $FileManagerSha256 -or
            (Get-Sha256 $script:Adb) -cne $AdbSha256) {
            throw "$Label tool bytes changed during the locked invocation."
        }
    } finally {
        $adbLock.Dispose()
        $toolLock.Dispose()
    }
    $json = $null
    if (-not [string]::IsNullOrWhiteSpace([string]$result.output)) {
        try { $json = [string]$result.output | ConvertFrom-Json } catch { }
    }
    $result["json"] = $json
    return $result
}

function Invoke-QfmWithArtifactLock([string[]]$Arguments, [string]$Label,
        [string]$ArtifactPath, [string]$ArtifactSha256, [switch]$AllowFailure) {
    $lock = [IO.File]::Open($ArtifactPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        if ((Get-Sha256 $ArtifactPath) -cne $ArtifactSha256) {
            throw "$Label artifact differs inside its locked effect boundary."
        }
        $result = Invoke-Qfm $Arguments $Label -AllowFailure:$AllowFailure
        if ((Get-Sha256 $ArtifactPath) -cne $ArtifactSha256) {
            throw "$Label artifact changed during its locked effect boundary."
        }
    } finally {
        $lock.Dispose()
    }
    return $result
}

function Invoke-Adb([string[]]$Arguments, [string]$Label, [switch]$AllowFailure) {
    $toolLock = [IO.File]::Open($script:Adb, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        if ((Get-Sha256 $script:Adb) -cne $AdbSha256) { throw "ADB changed after its exact input lock." }
        return Invoke-Captured $script:Adb (@("-s", $Serial) + $Arguments) $Label `
            -AllowFailure:$AllowFailure
    } finally { $toolLock.Dispose() }
}

function Write-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 24),
        [Text.UTF8Encoding]::new($false))
}

function Assert-QfmObservation($Observation, [string]$ExpectedSha256, [int64]$ExpectedSize,
        [string]$ExpectedVersionName, [string]$Label) {
    $value = $Observation.json
    if ([int]$Observation.exit_code -ne 0 -or $null -eq $value -or
        [string]$value.ObservationContract -cne $ExpectedObservationContract -or
        [string]$value.Installed.Serial -cne $Serial -or
        [string]$value.Installed.Identity.PackageName -cne $ExpectedPackageName -or
        [int64]$value.Installed.Identity.VersionCode -ne $ExpectedVersionCode -or
        [string]$value.Installed.Identity.VersionName -cne $ExpectedVersionName -or
        ([string]$value.Installed.Identity.SignerSha256).ToLowerInvariant() -cne $ExpectedSignerCertificateSha256 -or
        $null -ne $value.Installed.Identity.SplitName -or
        ([string]$value.Installed.BaseApkSha256).ToLowerInvariant() -cne $ExpectedSha256 -or
        [int64]$value.Installed.BaseApkSizeBytes -ne $ExpectedSize) {
        throw "$Label did not provide the exact typed installed-artifact observation."
    }
}

function Assert-QfmInstallMutation($Install, [string]$Label) {
    $mutation = $Install.json.mutation
    if ([int]$Install.exit_code -ne 0 -or $null -eq $mutation -or
        [string]::IsNullOrWhiteSpace([string]$mutation.OperationId) -or
        [string]$mutation.CommandKind -cne "installApk" -or
        [string]$mutation.Target -cne $Serial -or
        [string]$mutation.Stage -cne "confirmed" -or
        $mutation.HeadsetReadback -isnot [bool] -or -not $mutation.HeadsetReadback -or
        $mutation.IsTerminal -isnot [bool] -or -not $mutation.IsTerminal) {
        throw "$Label did not provide a confirmed typed install mutation receipt."
    }
}

function Get-DeviceSnapshot([string]$Label) {
    $packages = Invoke-Adb @("shell", "pm", "list", "packages") "$Label package inventory"
    $forwards = Invoke-Adb @("forward", "--list") "$Label forward inventory"
    if ([int]$forwards.exit_code -ne 0) { throw "$Label forward inventory failed." }
    $reverses = Invoke-Adb @("reverse", "--list") "$Label reverse inventory"
    if ([int]$reverses.exit_code -ne 0) { throw "$Label reverse inventory failed." }
    $properties = Invoke-Adb @("shell", "getprop") "$Label property inventory"
    $stablePropertyLines = @(([string]$properties.output -split "`r?`n") |
        Where-Object { $_ } | ForEach-Object {
            if ($_ -cmatch '^\[(cache_key\.[^\]]+)\]: \[-?[0-9]+\]$') {
                "[$($Matches[1])]: [<volatile>]"
            } else { $_ }
        })
    $stableProperties = $stablePropertyLines -join "`n"
    $artdState = @($stablePropertyLines | ForEach-Object {
        if ($_ -cmatch '^\[init\.svc\.artd\]: \[([^\]]*)\]$') { $Matches[1] }
    })
    if ($artdState.Count -ne 1) { throw "$Label did not expose one exact init.svc.artd property." }
    $spatialPath = Invoke-Adb @("shell", "pm", "path", $SpatialCameraPanelPackageName) `
        "$Label Spatial package path"
    $spatialPid = Invoke-Adb @("shell", "pidof", $SpatialCameraPanelPackageName) `
        "$Label Spatial process identity" -AllowFailure
    $brokerPid = Invoke-Adb @("shell", "pidof", $ExpectedPackageName) `
        "$Label broker process identity" -AllowFailure
    return [ordered]@{
        package_inventory_sha256=Get-TextSha256 ([string]$packages.output)
        forward_inventory_sha256=Get-TextSha256 ([string]$forwards.output)
        reverse_inventory_sha256=Get-TextSha256 ([string]$reverses.output)
        property_inventory_sha256=Get-TextSha256 $stableProperties
        package_effect_artd_state=[string]$artdState[0]
        spatial_package_path_sha256=Get-TextSha256 ([string]$spatialPath.output)
        spatial_process_identity_sha256=Get-TextSha256 ([string]$spatialPid.output)
        spatial_process_observed=([int]$spatialPid.exit_code -eq 0)
        broker_process_identity_sha256=Get-TextSha256 ([string]$brokerPid.output)
        broker_process_observed=([int]$brokerPid.exit_code -eq 0)
    }
}

function Wait-DevicePropertyValue([string]$Name, [string]$ExpectedValue,
        [string]$Label, [int]$MaxAttempts = 10) {
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt += 1) {
        $observation = Invoke-Adb @("shell", "getprop", $Name) "$Label attempt $attempt"
        $actualValue = ([string]$observation.output).Trim()
        if ($actualValue -ceq $ExpectedValue) { return }
        if ($attempt -lt $MaxAttempts) { Start-Sleep -Seconds 1 }
    }
    throw "$Label did not return to its exact pre-diagnostic value."
}

function Assert-SnapshotPreserved($Before, $After) {
    foreach ($field in @(
        "package_inventory_sha256", "forward_inventory_sha256",
        "reverse_inventory_sha256", "property_inventory_sha256",
        "spatial_package_path_sha256", "spatial_process_identity_sha256")) {
        if ([string]$Before.$field -cne [string]$After.$field) {
            throw "Unrelated device state changed during compatibility diagnostic: $field"
        }
    }
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$resolvedOut = [IO.Path]::GetFullPath($OutDir).TrimEnd('\', '/')
$allowedRoots = @(
    (Join-Path $repoRoot "target"),
    (Join-Path $repoRoot "local-artifacts")) | ForEach-Object {
        [IO.Path]::GetFullPath($_).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    }
if (@($allowedRoots | Where-Object {
        ($resolvedOut + [IO.Path]::DirectorySeparatorChar).StartsWith(
            $_, [StringComparison]::OrdinalIgnoreCase)
    }).Count -eq 0) {
    throw "OutDir must be under the repository target or local-artifacts ignored root."
}

$buildManifestPath = if (Test-Path -LiteralPath $BuildManifest -PathType Leaf) {
    (Resolve-Path -LiteralPath $BuildManifest).Path
} else { throw "BuildManifest does not exist." }
$rollbackEvidencePath = if (Test-Path -LiteralPath $RollbackEvidence -PathType Leaf) {
    (Resolve-Path -LiteralPath $RollbackEvidence).Path
} else { throw "RollbackEvidence does not exist." }
$staticGatePath = if (Test-Path -LiteralPath $StaticGateReceipt -PathType Leaf) {
    (Resolve-Path -LiteralPath $StaticGateReceipt).Path
} else { throw "StaticGateReceipt does not exist." }
$featureLock = Resolve-ExactFile $FeatureLockPath $FeatureLockSha256 "FeatureLockPath"
$script:Qfm = Resolve-ExactFile $FileManagerCli $FileManagerSha256 "FileManagerCli"
$script:Adb = Resolve-ExactFile $AdbPath $AdbSha256 "AdbPath"
Assert-OutputIsolated $resolvedOut @(
    $buildManifestPath, $rollbackEvidencePath, $staticGatePath, $featureLock,
    $script:Qfm, $script:Adb)

$buildManifestSha256 = Get-Sha256 $buildManifestPath
$rollbackEvidenceSha256 = Get-Sha256 $rollbackEvidencePath
$staticGateSha256 = Get-Sha256 $staticGatePath
$build = Get-Content -Raw -LiteralPath $buildManifestPath | ConvertFrom-Json
$rollback = Get-Content -Raw -LiteralPath $rollbackEvidencePath | ConvertFrom-Json
$gate = Get-Content -Raw -LiteralPath $staticGatePath | ConvertFrom-Json
$lock = Get-Content -Raw -LiteralPath $featureLock | ConvertFrom-Json
$validatorSourcePath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "checks\Test-ManifoldBrokerCompatibilityDiagnosticStatic.ps1")).Path
$validatorReadLock = [IO.File]::Open($validatorSourcePath, [IO.FileMode]::Open,
    [IO.FileAccess]::Read, [IO.FileShare]::Read)
try { $validatorSourceSha256 = Get-Sha256 $validatorSourcePath } finally { $validatorReadLock.Dispose() }
$inputTupleCanonical = @(
    "schema=rusty.quest.manifold_broker.compatibility_static_gate_input_tuple.v1",
    "validator_source_sha256=$validatorSourceSha256",
    "build_manifest_path=$buildManifestPath",
    "build_manifest_sha256=$buildManifestSha256",
    "rollback_evidence_path=$rollbackEvidencePath",
    "rollback_evidence_sha256=$rollbackEvidenceSha256",
    "feature_lock_path=$featureLock",
    "feature_lock_sha256=$FeatureLockSha256",
    "feature_lock_revision=$FeatureLockRevision",
    "feature_lock_fingerprint=$FeatureLockFingerprint",
    "expected_version_code=$ExpectedVersionCode",
    "expected_version_name=$ExpectedVersionName",
    "expected_signer_certificate_sha256=$ExpectedSignerCertificateSha256",
    "expected_package_name=$ExpectedPackageName",
    "spatial_camera_panel_package_name=$SpatialCameraPanelPackageName",
    "file_manager_cli_path=$script:Qfm",
    "file_manager_cli_sha256=$FileManagerSha256",
    "adb_path=$script:Adb",
    "adb_sha256=$AdbSha256") -join "`n"
$inputTupleSha256 = Get-TextSha256 $inputTupleCanonical

if ([string]$gate.'$schema' -cne
        "rusty.quest.manifold_broker.compatibility_static_gate_receipt.v1" -or
    [string]$gate.status -cne "passed" -or
    [string]$gate.validator.path -cne $validatorSourcePath -or
    [string]$gate.validator.sha256 -cne $validatorSourceSha256 -or
    [string]$gate.input_tuple.schema -cne
        "rusty.quest.manifold_broker.compatibility_static_gate_input_tuple.v1" -or
    [string]$gate.input_tuple.sha256 -cne $inputTupleSha256 -or
    [string]$gate.build_manifest.path -cne $buildManifestPath -or
    [string]$gate.build_manifest.sha256 -cne $buildManifestSha256 -or
    [string]$gate.rollback_evidence.path -cne $rollbackEvidencePath -or
    [string]$gate.rollback_evidence.sha256 -cne $rollbackEvidenceSha256 -or
    [string]$gate.feature_lock.path -cne $featureLock -or
    [string]$gate.feature_lock.sha256 -cne $FeatureLockSha256 -or
    [int64]$gate.feature_lock.revision -ne $FeatureLockRevision -or
    [string]$gate.feature_lock.fingerprint -cne $FeatureLockFingerprint -or
    [string]$gate.package.broker -cne $ExpectedPackageName -or
    [string]$gate.package.spatial_camera_panel -cne $SpatialCameraPanelPackageName -or
    [int64]$gate.package.version_code -ne $ExpectedVersionCode -or
    [string]$gate.package.version_name -cne $ExpectedVersionName -or
    [string]$gate.package.signer_certificate_sha256 -cne
        $ExpectedSignerCertificateSha256 -or
    [string]$gate.tools.file_manager_cli_sha256 -cne $FileManagerSha256 -or
    [string]$gate.tools.adb_sha256 -cne $AdbSha256) {
    throw "StaticGateReceipt does not match the exact diagnostic inputs."
}
foreach ($flag in @(
    "uninstall_permitted", "data_clear_permitted", "global_log_clear_permitted",
    "blanket_force_stop_permitted", "downgrade_permitted", "adb_lifecycle_permitted")) {
    if ($gate.safety.$flag -isnot [bool] -or $gate.safety.$flag) {
        throw "StaticGateReceipt permits prohibited behavior: $flag"
    }
}
if ([int64]$lock.revision -ne $FeatureLockRevision -or
    [string]$lock.lock_fingerprint -cne $FeatureLockFingerprint -or
    @($lock.selected_features | Where-Object { [string]$_ -ceq $ExpectedFeatureId }).Count -ne 1) {
    throw "Feature lock drifted after the static gate."
}
if ([string]$build.package_name -cne $ExpectedPackageName -or
    [int64]$build.version_code -ne $ExpectedVersionCode -or
    [string]$build.version_name -cne $ExpectedVersionName -or
    [string]$build.artifact_signer_sha256 -cne $ExpectedSignerCertificateSha256 -or
    [string]$build.spatial_camera_panel_package_name -cne
        $SpatialCameraPanelPackageName -or
    [string]$gate.package.candidate_apk_sha256 -cne [string]$build.apk_sha256 -or
    [string]$rollback.candidate_apk_sha256 -cne [string]$build.apk_sha256 -or
    [string]$gate.package.rollback_apk_sha256 -cne
        [string]$rollback.rollback_apk.sha256) {
    throw "Build, rollback, and gate package tuples are not identical."
}
$candidateApk = Resolve-ExactFile ([string]$build.apk_path) ([string]$build.apk_sha256) `
    "candidate APK"
$rollbackApk = Resolve-ExactFile ([string]$rollback.rollback_apk.path) `
    ([string]$rollback.rollback_apk.sha256) "rollback APK"
# Rollback evidence binds the exact APK hash, not a duplicate size field. Read
# the size from the locked artifact and immediately revalidate the hash.
$rollbackSizeLock = [IO.File]::Open($rollbackApk, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
try {
    $rollbackApkSize = (Get-Item -LiteralPath $rollbackApk).Length
    if ((Get-Sha256 $rollbackApk) -cne [string]$rollback.rollback_apk.sha256) {
        throw "rollback APK changed while deriving its exact byte size."
    }
} finally { $rollbackSizeLock.Dispose() }

# Establish a create-new, ignored evidence root before any bounded provider
# child is invoked, so both preflight and device-stage results are durable.
[void](New-Item -ItemType Directory -Path $resolvedOut)
$runDir = Join-Path $resolvedOut (
    "broker-supplier-b-" + [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffffffZ") +
    "-" + [Guid]::NewGuid().ToString("N"))
[void](New-Item -ItemType Directory -Path $runDir)
$summaryPath = Join-Path $runDir "diagnostic-evidence.json"
$failurePath = Join-Path $runDir "failure-evidence.json"
$script:RawResultsDir = Join-Path $runDir "raw-child-results"
$script:RawResultSequence = 0
[void](New-Item -ItemType Directory -Path $script:RawResultsDir)

# Both artifacts are inspected before the first device command. The static gate
# already authenticated the same inspections, so this is a freshness check only.
$candidateInspect = Invoke-Qfm @("apk", "inspect", "--file", $candidateApk, "--json") `
    "fresh candidate inspection"
$rollbackInspect = Invoke-Qfm @("apk", "inspect", "--file", $rollbackApk, "--json") `
    "fresh rollback inspection"
foreach ($inspection in @(
    [ordered]@{value=$candidateInspect.json;sha=[string]$build.apk_sha256;
        version_name=$ExpectedVersionName;label="candidate"},
    [ordered]@{value=$rollbackInspect.json;sha=[string]$rollback.rollback_apk.sha256;
        version_name=[string]$rollback.rollback_apk.actual_version_name;label="rollback"})) {
    if ($null -eq $inspection.value -or
        [string]$inspection.value.Sha256 -cne $inspection.sha -or
        [string]$inspection.value.Identity.PackageName -cne $ExpectedPackageName -or
        [int64]$inspection.value.Identity.VersionCode -ne $ExpectedVersionCode -or
        [string]$inspection.value.Identity.VersionName -cne $inspection.version_name -or
        ([string]$inspection.value.Identity.SignerSha256).ToLowerInvariant() -cne
            $ExpectedSignerCertificateSha256) {
        throw "$($inspection.label) APK freshness inspection failed."
    }
}

$candidateInstallAttempted = $false
$candidateBytesConfirmed = $false
$rollbackRestored = $false
$brokerProcessRestorationAction = "not-needed"
$before = $null
$after = $null
$providerReceipt = $null
$candidateObserve = $null
$rollbackObserve = $null
$failure = $null
try {
    $device = Invoke-Adb @("get-state") "exact device discovery"
    if ([string]$device.output.Trim() -cne "device") {
        throw "The explicit serial is not one connected authorized device."
    }
    $before = Get-DeviceSnapshot "before"
    $preinstalled = Invoke-QfmWithArtifactLock @(
        "apk", "observe", "--adb", $script:Adb, "--serial", $Serial, "--file", $rollbackApk, "--json") `
        "preinstalled rollback-byte readback" $rollbackApk ([string]$rollback.rollback_apk.sha256)
    Assert-QfmObservation $preinstalled ([string]$rollback.rollback_apk.sha256) `
        ([int64]$rollbackApkSize) ([string]$rollback.rollback_apk.actual_version_name) `
        "preinstalled rollback-byte readback"

    $candidateInstallAttempted = $true
    $candidateInstall = Invoke-QfmWithArtifactLock @(
        "apk", "install", "--adb", $script:Adb, "--serial", $Serial, "--file", $candidateApk, "--json") `
        "inspected same-version candidate install" $candidateApk ([string]$build.apk_sha256) -AllowFailure
    # A nonzero/ambiguous install result is reconciled by this typed readback before
    # either authority probing or rollback is considered.
    $candidateObserve = Invoke-QfmWithArtifactLock @(
        "apk", "observe", "--adb", $script:Adb, "--serial", $Serial, "--file", $candidateApk, "--json") `
        "candidate installed-byte readback" $candidateApk ([string]$build.apk_sha256) -AllowFailure
    Assert-QfmObservation $candidateObserve ([string]$build.apk_sha256) `
        ([int64]$build.apk_size) $ExpectedVersionName "candidate installed-byte readback"
    $candidateBytesConfirmed = $true
    Assert-QfmInstallMutation $candidateInstall "candidate install"

    $candidateStart = Invoke-Adb @(
        "shell", "am", "start", "-W", "-n",
        "$ExpectedPackageName/io.github.mesmerprism.rustymanifold.broker.BrokerStartActivity") `
        "candidate broker normal activity initialization"

    $providerCall = Invoke-Adb @(
        "shell", "content", "call", "--uri", "content://$ProviderAuthority",
        "--method", "authority-status") "bounded remote-camera authority status"
    $match = [regex]::Match([string]$providerCall.output, 'receipt_b64=([A-Za-z0-9+/=]+)')
    if (-not $match.Success) { throw "Remote-camera authority receipt is missing." }
    $providerReceipt = [Text.Encoding]::UTF8.GetString(
        [Convert]::FromBase64String($match.Groups[1].Value)) | ConvertFrom-Json
    if ([string]$providerReceipt.'$schema' -cne
            "rusty.quest.remote_camera.debug_operator_receipt.v1" -or
        [string]$providerReceipt.action -cne "authority-status" -or
        $providerReceipt.applied -isnot [bool] -or -not $providerReceipt.applied -or
        [string]::IsNullOrWhiteSpace(
            [string]$providerReceipt.authority_status.provider_epoch_id)) {
        throw "Remote-camera authority status was not effective."
    }
} catch {
    $failure = $_
} finally {
    if ($candidateInstallAttempted -and $candidateBytesConfirmed) {
        try {
            $rollbackInstall = Invoke-QfmWithArtifactLock @(
                "apk", "install", "--adb", $script:Adb, "--serial", $Serial, "--file", $rollbackApk, "--json") `
                "same-version rollback restore" $rollbackApk ([string]$rollback.rollback_apk.sha256) -AllowFailure
            $rollbackObserve = Invoke-QfmWithArtifactLock @(
                "apk", "observe", "--adb", $script:Adb, "--serial", $Serial, "--file", $rollbackApk, "--json") `
                "rollback installed-byte readback" $rollbackApk ([string]$rollback.rollback_apk.sha256) -AllowFailure
            Assert-QfmObservation $rollbackObserve ([string]$rollback.rollback_apk.sha256) `
                ([int64]$rollbackApkSize) ([string]$rollback.rollback_apk.actual_version_name) `
                "rollback installed-byte readback"
            Assert-QfmInstallMutation $rollbackInstall "rollback restore"
            $rollbackRestored = $true
            if ($null -ne $before -and $before.broker_process_observed) {
                [void](Invoke-Adb @(
                    "shell", "am", "start", "-W", "-n",
                    "$ExpectedPackageName/io.github.mesmerprism.rustymanifold.broker.BrokerStartActivity") `
                    "restore prior broker process through normal activity")
                $brokerProcessRestorationAction = "normal-activity-start"
            } else {
                [void](Invoke-Adb @("shell", "am", "force-stop", $ExpectedPackageName) `
                    "exact broker-only inactive-process cleanup")
                $brokerProcessRestorationAction = "exact-broker-force-stop"
            }
        } catch {
            if ($null -eq $failure) { $failure = $_ }
            else {
                $failure = [Exception]::new(
                    "$($failure.Exception.Message) Rollback restore also failed: $($_.Exception.Message)",
                    $failure.Exception)
            }
        }
    }
    try {
        if ($null -ne $before) {
            Wait-DevicePropertyValue "init.svc.artd" `
                ([string]$before.package_effect_artd_state) "after package-effect artd settle"
        }
        $after = Get-DeviceSnapshot "after"
        if ($null -ne $before) { Assert-SnapshotPreserved $before $after }
    } catch {
        if ($null -eq $failure) { $failure = $_ }
    }
}

$evidence = [ordered]@{
    '$schema' = "rusty.quest.broker.compatibility_diagnostic_evidence.v1"
    status = $(if ($null -eq $failure -and $rollbackRestored) { "passed" } else { "failed" })
    completed_at_utc = [DateTime]::UtcNow.ToString("O")
    serial = $Serial
    inputs = [ordered]@{
        build_manifest_sha256=$buildManifestSha256
        rollback_evidence_sha256=$rollbackEvidenceSha256
        static_gate_receipt_sha256=$staticGateSha256
        feature_lock_sha256=$FeatureLockSha256
        feature_lock_revision=$FeatureLockRevision
        feature_lock_fingerprint=$FeatureLockFingerprint
        file_manager_cli_sha256=$FileManagerSha256
        adb_sha256=$AdbSha256
    }
    package = [ordered]@{
        broker=$ExpectedPackageName; spatial_camera_panel=$SpatialCameraPanelPackageName
        version_code=$ExpectedVersionCode; version_name=$ExpectedVersionName
        signer_certificate_sha256=$ExpectedSignerCertificateSha256
        candidate_apk_sha256=[string]$build.apk_sha256
        rollback_apk_sha256=[string]$rollback.rollback_apk.sha256
    }
    observations = [ordered]@{
        before=$before; after=$after
        candidate_installed_byte_readback=($null -ne $candidateObserve)
        candidate_normal_activity_initialization=($null -ne $candidateStart)
        remote_camera_authority_status=$providerReceipt
        rollback_installed_byte_readback=($null -ne $rollbackObserve)
    }
    cleanup = [ordered]@{
        exact_prior_broker_bytes_restored=[bool]$rollbackRestored
        broker_process_restoration_action=$brokerProcessRestorationAction
        unrelated_package_inventory_preserved=$($null -ne $before -and $null -ne $after -and
            [string]$before.package_inventory_sha256 -ceq [string]$after.package_inventory_sha256)
        unrelated_spatial_process_preserved=$($null -ne $before -and $null -ne $after -and
            [string]$before.spatial_process_identity_sha256 -ceq [string]$after.spatial_process_identity_sha256)
        properties_preserved=$($null -ne $before -and $null -ne $after -and
            [string]$before.property_inventory_sha256 -ceq [string]$after.property_inventory_sha256)
        forwards_preserved=$($null -ne $before -and $null -ne $after -and
            [string]$before.forward_inventory_sha256 -ceq [string]$after.forward_inventory_sha256)
        reverses_preserved=$($null -ne $before -and $null -ne $after -and
            [string]$before.reverse_inventory_sha256 -ceq [string]$after.reverse_inventory_sha256)
        log_buffers_cleared=$false; uninstall_performed=$false; data_clear_performed=$false
        blanket_force_stop_performed=$false; downgrade_performed=$false
        adb_lifecycle_operation_performed=$false
    }
    proves = @(
        "Exact same-version shared-signer broker candidate install and installed-byte readback.",
        "Bounded remote-camera authority-status provider compatibility.",
        "Exact same-version rollback bytes restored without destructive device operations.")
    does_not_prove = @(
        "Does not prove a final common media graph, hot Local consumer handoff, publication, or remote mutation.")
}
Write-Json $summaryPath $evidence
if ($null -ne $failure) {
    Write-Json $failurePath ([ordered]@{
        '$schema'="rusty.quest.broker.compatibility_diagnostic_failure.v1"
        failed_at_utc=[DateTime]::UtcNow.ToString("O")
        message=[string]$failure.Exception.Message
        exact_prior_broker_bytes_restored=[bool]$rollbackRestored
        summary_sha256=Get-Sha256 $summaryPath
        destructive_cleanup_attempted=$false
    })
    throw $failure
}
Write-Output $summaryPath
