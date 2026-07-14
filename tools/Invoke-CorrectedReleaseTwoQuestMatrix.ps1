param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Execute", "Validate", "ReplayValidate", "SelfTest")]
    [string]$Mode,
    [string[]]$Serial = @(),
    [string]$BrokerApk = "",
    [string]$NativeApk = "",
    [string]$SpatialApk = "",
    [string]$EvidenceDir = "",
    [string]$OutputPath = "",
    [string]$MatrixPath = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [int]$RunSeconds = 12,
    [switch]$ConfirmBoundedLogcatClear
)

$ErrorActionPreference = "Stop"
$script:Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:ShaPattern = '^[0-9a-f]{64}$'
$script:RevisionPattern = '^[0-9a-f]{40}$'
$script:MatrixSchema = "rusty.morphospace.corrected_release_device_matrix.v1"
$script:PeerAuthoritySchema = "rusty.quest.manifold_peer_authority_two_quest_evidence.v1"
$script:CriterionSchema = "rusty.quest.corrected_release_criterion_evidence.v1"
$script:CleanupSchema = "rusty.quest.corrected_release_cleanup_evidence.v1"
$script:RunId = ""
$script:RunStartedAt = [DateTimeOffset]::MinValue
$script:EvidenceRoot = ""
$script:PreflightSha256 = ""
$script:TransportBySerial = @{}
$script:RequiredCriteria = @(
    "module_lock_selected",
    "module_lock_off_lock",
    "client_lifecycle_native",
    "client_lifecycle_spatial",
    "enrollment_pair",
    "enrollment_revoke",
    "media_camera2",
    "media_display_composite",
    "cleanup",
    "bounded_fatal"
)

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [System.IO.File]::WriteAllText(
        $Path,
        ($Value | ConvertTo-Json -Depth 30),
        $script:Utf8NoBom
    )
}

function Read-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label is missing: $Path"
    }
    try {
        return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    } catch {
        throw "$Label is not valid JSON: $Path ($($_.Exception.Message))"
    }
}

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Hash-bound evidence file is missing: $Path"
    }
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function New-FileBinding {
    param([Parameter(Mandatory = $true)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    return [pscustomobject][ordered]@{
        path = $resolved
        sha256 = Get-FileSha256 -Path $resolved
    }
}

function Assert-FileBinding {
    param(
        [Parameter(Mandatory = $true)]$Binding,
        [Parameter(Mandatory = $true)][string]$Label,
        [switch]$RejectFixturePath,
        [string]$AllowedRoot = ""
    )
    if ($null -eq $Binding -or [string]::IsNullOrWhiteSpace([string]$Binding.path)) {
        throw "$Label lacks an evidence path."
    }
    if (-not (Test-Path -LiteralPath ([string]$Binding.path) -PathType Leaf)) {
        throw "$Label evidence file is missing: $($Binding.path)"
    }
    $expected = ([string]$Binding.sha256).ToLowerInvariant()
    if ($expected -cnotmatch $script:ShaPattern) {
        throw "$Label has an invalid SHA-256."
    }
    if ((Get-FileSha256 -Path ([string]$Binding.path)) -cne $expected) {
        throw "$Label evidence hash does not match its file."
    }
    if ($RejectFixturePath -and ([string]$Binding.path -match '(?i)[\\/]fixtures[\\/]')) {
        throw "$Label points at fixture-only evidence."
    }
    if (-not [string]::IsNullOrWhiteSpace($AllowedRoot)) {
        $full = [IO.Path]::GetFullPath([string]$Binding.path)
        $root = [IO.Path]::GetFullPath($AllowedRoot).TrimEnd('\', '/')
        $prefix = $root + [IO.Path]::DirectorySeparatorChar
        if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "$Label is outside the runner-owned evidence root."
        }
    }
}

function ConvertTo-InvariantTimestamp {
    param([object]$Value, [string]$Label)
    try {
        if ($Value -is [DateTimeOffset]) {
            $observed = [DateTimeOffset]$Value
        } elseif ($Value -is [DateTime]) {
            $observed = [DateTimeOffset]::new(([DateTime]$Value).ToUniversalTime())
        } elseif ($Value -is [string]) {
            $observed = [DateTimeOffset]::ParseExact(
                [string]$Value,
                "o",
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind)
        } else {
            throw "unsupported timestamp value"
        }
    } catch {
        throw "$Label timestamp is invalid or not invariant round-trip format."
    }
    return $observed
}

function Assert-RunTimestamp {
    param([object]$Value, [DateTimeOffset]$StartedAt, [DateTimeOffset]$FinishedAt, [string]$Label)
    $observed = ConvertTo-InvariantTimestamp -Value $Value -Label $Label
    if ($observed -lt $StartedAt.AddSeconds(-5) -or $observed -gt $FinishedAt.AddSeconds(5)) {
        throw "$Label timestamp is outside the matrix run window."
    }
}

function Assert-ExplicitSerials {
    param([string[]]$Values)
    $normalized = @($Values | ForEach-Object { if ($null -ne $_) { $_.Trim() } })
    if ($normalized.Count -ne 2 -or @($normalized | Sort-Object -Unique).Count -ne 2) {
        throw "Exactly two distinct explicit Quest serials are required."
    }
    foreach ($device in $normalized) {
        if ([string]::IsNullOrWhiteSpace($device) -or $device -notmatch '^[A-Za-z0-9._:-]+$') {
            throw "Quest serial is empty or contains unsupported characters: '$device'"
        }
    }
    return $normalized
}

function Assert-Revision {
    param([string]$Revision, [string]$Label)
    if ($Revision -cnotmatch $script:RevisionPattern) {
        throw "$Label is not an exact lowercase Git revision: $Revision"
    }
}

function Resolve-RequiredFile {
    param([string]$Path, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label is missing: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Assert-MandatoryPeerProvider {
    param([string]$Path)
    $resolved = Resolve-RequiredFile -Path $Path -Label "mandatory live Manifold peer-authority provider"
    if ([System.IO.Path]::GetFileName($resolved) -cne "Invoke-ManifoldPeerAuthorityTwoQuest.ps1") {
        throw "Peer-authority provider must be the exact Invoke-ManifoldPeerAuthorityTwoQuest.ps1 contract."
    }
    return $resolved
}

function Invoke-SerialAdb {
    param(
        [Parameter(Mandatory = $true)][string]$Device,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure,
        [int]$TimeoutSeconds = 120
    )
    $transport = $Device
    if ($script:TransportBySerial.ContainsKey($Device)) {
        $transport = [string]$script:TransportBySerial[$Device]
    }
    $adbArgs = @("-s", $transport) + $Arguments
    $stdoutPath = [IO.Path]::GetTempFileName()
    $stderrPath = [IO.Path]::GetTempFileName()
    $quotedArgs = @($adbArgs | ForEach-Object {
        $arg = [string]$_
        if ($arg -match '[\s"]') { '"' + $arg.Replace('"', '\"') + '"' } else { $arg }
    })
    $process = $null
    try {
        $process = Start-Process -FilePath $script:AdbPath -ArgumentList $quotedArgs -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru -WindowStyle Hidden
        if (-not $process.WaitForExit([Math]::Max(1, $TimeoutSeconds) * 1000)) {
            try { $process.Kill($true) } catch {}
            $exitCode = 124
            $output = "adb command timed out after $TimeoutSeconds seconds."
        } else {
            $stdout = if (Test-Path -LiteralPath $stdoutPath) { Get-Content -Raw -LiteralPath $stdoutPath } else { "" }
            $stderr = if (Test-Path -LiteralPath $stderrPath) { Get-Content -Raw -LiteralPath $stderrPath } else { "" }
            $exitCode = $process.ExitCode
            $output = (@($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
        }
    } finally {
        if ($null -ne $process) { $process.Dispose() }
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb -s $transport $($Arguments -join ' ') failed for $Device with exit code $exitCode`: $output"
    }
    return [pscustomobject][ordered]@{
        exit_code = $exitCode
        output = $output
    }
}

function Resolve-LogicalSerialsFromAdbTransports {
    param([Parameter(Mandatory = $true)][string[]]$Transports)
    $resolved = @()
    $script:TransportBySerial = @{}
    foreach ($transport in $Transports) {
        $state = Invoke-SerialAdb -Device $transport -Arguments @("get-state")
        if ($state.output.Trim() -cne "device") {
            throw "Quest transport $transport is not ready."
        }
        $serial = (Invoke-SerialAdb -Device $transport -Arguments @("shell", "getprop", "ro.serialno")).output.Trim()
        if ($serial -notmatch '^[A-Za-z0-9._-]+$') {
            throw "Quest transport $transport returned invalid hardware serial '$serial'."
        }
        $resolved += $serial
        $script:TransportBySerial[$serial] = $transport
    }
    if (@($resolved | Sort-Object -Unique).Count -ne $resolved.Count) {
        throw "ADB transports resolve to duplicate hardware serials."
    }
    return $resolved
}

function Get-AdbTransport {
    param([Parameter(Mandatory = $true)][string]$Device)
    if ($script:TransportBySerial.ContainsKey($Device)) {
        return [string]$script:TransportBySerial[$Device]
    }
    return $Device
}

function Measure-FatalEvidence {
    param([string[]]$Paths)
    $text = ""
    foreach ($path in @($Paths | Sort-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Fatal evidence path is missing: $path"
        }
        $text += "`n" + (Get-Content -Raw -LiteralPath $path)
    }
    return [pscustomobject][ordered]@{
        package_fatal_count = [regex]::Matches($text, '(?im)FATAL EXCEPTION').Count
        app_fatal_count = [regex]::Matches($text, '(?im)Fatal signal\s+\d+').Count
        system_fatal_count = [regex]::Matches(
            $text,
            '(?im)FATAL EXCEPTION IN SYSTEM PROCESS|Watchdog[^\r\n]*system_server|Fatal signal[^\r\n]*system_server|system_server[^\r\n]*fatal'
        ).Count
    }
}

function Assert-ZeroFatals {
    param($Counts, [string]$Label)
    foreach ($field in @("package_fatal_count", "app_fatal_count", "system_fatal_count")) {
        if ([int]$Counts.$field -ne 0) {
            throw "$Label has nonzero $field`: $($Counts.$field)"
        }
    }
}

function Assert-TextTokens {
    param([string]$Text, [string[]]$Tokens, [string]$Label)
    foreach ($token in $Tokens) {
        if (-not $Text.Contains($token)) {
            throw "$Label is missing '$token'."
        }
    }
}

function Assert-RunSummary {
    param(
        $Summary,
        [string]$Schema,
        [string[]]$AcceptedStatus,
        [string]$SerialValue,
        [string]$Label,
        [switch]$DollarSchema
    )
    $observedSchema = if ($DollarSchema) { [string]$Summary.'$schema' } else { [string]$Summary.schema }
    if ($observedSchema -cne $Schema) {
        throw "$Label schema mismatch: $observedSchema"
    }
    if ($AcceptedStatus -notcontains [string]$Summary.status) {
        throw "$Label status is not accepted: $($Summary.status)"
    }
    if ([string]$Summary.serial -cne $SerialValue -or
        [string]$Summary.adb_scope -cne "device-scoped-adb" -or
        ($null -ne $Summary.PSObject.Properties["adb_serial_required"] -and -not [bool]$Summary.adb_serial_required)) {
        throw "$Label is not bound to the explicit serial $SerialValue."
    }
}

function Add-OrReplaceProfileProperty {
    param($Profile, [string]$Name, [string]$Value, [string]$SourceSettingId)
    $owned = @($Profile.owned_android_properties | ForEach-Object { [string]$_ })
    if ($owned -notcontains $Name) {
        $owned += $Name
    }
    $Profile.owned_android_properties = @($owned | Sort-Object -Unique)
    $set = @($Profile.set_properties | Where-Object { [string]$_.name -cne $Name })
    $set += [pscustomobject][ordered]@{
        name = $Name
        value = $Value
        source_setting_id = $SourceSettingId
    }
    $Profile.set_properties = $set
}

function New-OffLockProfiles {
    param([string]$Directory)
    $badDigest = "0" * 64
    $nativeBasePath = Join-Path $script:RepoRoot "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json"
    $spatialBasePath = Join-Path $script:RepoRoot "fixtures\runtime-profiles\quest-spatial-camera-panel-particle-adapter-conformance.profile.json"
    $native = Read-JsonFile -Path $nativeBasePath -Label "native off-lock base profile"
    $spatial = Read-JsonFile -Path $spatialBasePath -Label "Spatial off-lock base profile"
    foreach ($item in @(
        @("debug.rustyquest.native_renderer.particle_adapter.enabled", "true", "native_renderer.particle_adapter.enabled"),
        @("debug.rustyquest.native_renderer.particle_adapter.profile_id", "profile.quest.native_renderer.particle_adapter_conformance", "native_renderer.particle_adapter.profile_id"),
        @("debug.rustyquest.native_renderer.particle_adapter.project_id", "native-renderer", "native_renderer.particle_adapter.project_id"),
        @("debug.rustyquest.native_renderer.particle_adapter.feature_id", "particle-adapter-consumer", "native_renderer.particle_adapter.feature_id"),
        @("debug.rustyquest.native_renderer.particle_adapter.lock_revision", "1", "native_renderer.particle_adapter.lock_revision")
    )) {
        Add-OrReplaceProfileProperty -Profile $native -Name $item[0] -Value $item[1] -SourceSettingId $item[2]
    }
    $nativePath = Join-Path $Directory "native-off-lock.profile.json"
    $spatialPath = Join-Path $Directory "spatial-off-lock.profile.json"
    Write-JsonFile -Path $nativePath -Value $native
    Write-JsonFile -Path $spatialPath -Value $spatial
    return [pscustomobject]@{
        native = $nativePath
        spatial = $spatialPath
        bad_digest = $badDigest
        native_lock_property = "debug.rustyquest.native_renderer.particle_adapter.lock_sha256"
        spatial_lock_property = "debug.rustyquest.spatial_camera_panel.particle_adapter.lock_sha256"
    }
}

function New-LiveSource {
    param(
        [string]$SerialValue,
        [string]$CriterionId,
        [string[]]$RawPaths,
        $FatalCounts,
        $Details
    )
    Assert-ZeroFatals -Counts $FatalCounts -Label "$SerialValue/$CriterionId"
    return [pscustomobject][ordered]@{
        run_id = $script:RunId
        observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        serial = $SerialValue
        criterion_id = $CriterionId
        status = "pass"
        device_execution = $true
        synthetic = $false
        fixture_only = $false
        supported = $true
        raw_paths = @($RawPaths | Sort-Object -Unique)
        package_fatal_count = [int]$FatalCounts.package_fatal_count
        app_fatal_count = [int]$FatalCounts.app_fatal_count
        system_fatal_count = [int]$FatalCounts.system_fatal_count
        details = $Details
    }
}

function Import-RunnerOwnedEvidence {
    param(
        [Parameter(Mandatory = $true)][string[]]$Paths,
        [Parameter(Mandatory = $true)][string]$ImportDirectory
    )
    $root = [IO.Path]::GetFullPath($script:EvidenceRoot).TrimEnd('\', '/')
    $prefix = $root + [IO.Path]::DirectorySeparatorChar
    New-Item -ItemType Directory -Force -Path $ImportDirectory | Out-Null
    $imported = @()
    foreach ($path in @($Paths | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)) {
        $full = [IO.Path]::GetFullPath([string]$path)
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
            throw "Peer provider raw evidence file is missing: $full"
        }
        if ($full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            $imported += $full
            continue
        }
        $hash = Get-FileSha256 -Path $full
        $leaf = [IO.Path]::GetFileName($full)
        $target = Join-Path $ImportDirectory "$hash-$leaf"
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            Copy-Item -LiteralPath $full -Destination $target
        }
        if ((Get-FileSha256 -Path $target) -cne $hash) {
            throw "Imported peer provider raw evidence hash mismatch: $target"
        }
        $imported += $target
    }
    return @($imported | Sort-Object -Unique)
}

function Invoke-ModuleLockSelected {
    param([string]$Device, [string]$Directory)
    $transport = Get-AdbTransport -Device $Device
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $nativeDir = Join-Path $Directory "native"
    $spatialDir = Join-Path $Directory "spatial"
    $nativeProfile = Join-Path $script:RepoRoot "fixtures\runtime-profiles\quest-native-renderer-particle-adapter-conformance.profile.json"
    $spatialProfile = Join-Path $script:RepoRoot "fixtures\runtime-profiles\quest-spatial-camera-panel-particle-adapter-conformance.profile.json"
    & (Join-Path $PSScriptRoot "Invoke-NativeRendererReplaySmoke.ps1") `
        -ApkPath $script:NativeApkPath `
        -ProfilePath $nativeProfile `
        -EvidenceMode ParticleAdapterConformance `
        -OutDir $nativeDir `
        -RunSeconds $RunSeconds `
        -Adb $script:AdbPath `
        -Serial $transport `
        -AllowFlatScreenshot `
        -AllowPerformanceBudgetMiss `
        -ClearLogcat `
        -StopAfterRun | Out-Null
    $nativeSummaryPath = Join-Path $nativeDir "run-summary.json"
    $nativeSummary = Read-JsonFile -Path $nativeSummaryPath -Label "native selected-lock summary"
    Assert-RunSummary -Summary $nativeSummary -Schema "rusty.quest.native_renderer_replay_smoke_run.v1" -AcceptedStatus @("passed") -SerialValue $transport -Label "native selected-lock summary"

    $spatialPlanPath = Join-Path $spatialDir "selected-property-plan.json"
    & (Join-Path $PSScriptRoot "Apply-RuntimeProfile.ps1") `
        -ProfilePath $spatialProfile `
        -Execute `
        -Out $spatialPlanPath `
        -Adb $script:AdbPath `
        -Serial $transport | Out-Null
    & (Join-Path $PSScriptRoot "Invoke-SpatialCameraPanelAndroidParticleVisualSmoke.ps1") `
        -RepoRoot $script:RepoRoot `
        -ApkPath $script:SpatialApkPath `
        -OutDir $spatialDir `
        -RunSeconds $RunSeconds `
        -Adb $script:AdbPath `
        -Serial $transport `
        -SurfaceTargetId icosphere `
        -AllowMissingMarkers `
        -ClearLogcat `
        -StopAfterRun | Out-Null
    $spatialSummaryPath = Join-Path $spatialDir "evidence-summary.json"
    $spatialSummary = Read-JsonFile -Path $spatialSummaryPath -Label "Spatial selected-lock summary"
    Assert-RunSummary -Summary $spatialSummary -Schema "rusty.quest.spatial_camera_panel_particle_visual_smoke.v1" -AcceptedStatus @("passed", "completed") -SerialValue $transport -Label "Spatial selected-lock summary" -DollarSchema

    $spatialCombinedPath = Join-Path $spatialDir "combined-particle-markers.txt"
    $spatialInputs = @(
        (Join-Path $spatialDir "pid-logcat.txt"),
        (Join-Path $spatialDir "activity-markers.log"),
        (Join-Path $spatialDir "native-markers.log")
    )
    $spatialCombined = @($spatialInputs | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object { Get-Content -Raw -LiteralPath $_ }) -join "`n"
    [System.IO.File]::WriteAllText($spatialCombinedPath, $spatialCombined, $script:Utf8NoBom)
    $scorecardPath = Join-Path $Directory "particle-adapter-scorecard.json"
    & (Join-Path $PSScriptRoot "Test-QuestParticleAdapterEvidence.ps1") `
        -NativeRendererLogcatPath ([string]$nativeSummary.raw_logcat_path) `
        -SpatialPanelLogcatPath $spatialCombinedPath `
        -Out $scorecardPath | Out-Null
    $scorecard = Read-JsonFile -Path $scorecardPath -Label "selected lock particle scorecard"
    if ([string]$scorecard.schema -cne "rusty.quest.particle_adapter.device_scorecard.v1" -or
        [string]$scorecard.status -cne "accepted" -or
        -not [bool]$scorecard.lock_bound_activation) {
        throw "Selected MOD-006 particle-adapter scorecard did not accept exact lock-bound activation."
    }
    $fatalPaths = @([string]$nativeSummary.raw_logcat_path, (Join-Path $spatialDir "logcat-all.txt"))
    $fatals = Measure-FatalEvidence -Paths $fatalPaths
    return New-LiveSource `
        -SerialValue $Device `
        -CriterionId "module_lock_selected" `
        -RawPaths (@($nativeSummaryPath, $spatialSummaryPath, $spatialPlanPath, $scorecardPath) + $fatalPaths) `
        -FatalCounts $fatals `
        -Details ([pscustomobject][ordered]@{
            activation_state = "applied"
            native_consumer = "native-renderer-android"
            spatial_consumer = "spatial-camera-panel"
            exact_lock_bound = $true
        })
}

function Invoke-ModuleLockOffLock {
    param([string]$Device, [string]$Directory, $Profiles)
    $transport = Get-AdbTransport -Device $Device
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $nativeDir = Join-Path $Directory "native"
    $spatialDir = Join-Path $Directory "spatial"
    $nativeRejected = $false
    try {
        & (Join-Path $PSScriptRoot "Invoke-NativeRendererReplaySmoke.ps1") `
            -ApkPath $script:NativeApkPath `
            -ProfilePath $Profiles.native `
            -EvidenceMode EnvironmentDepthParticles `
            -OutDir $nativeDir `
            -RunSeconds $RunSeconds `
            -Adb $script:AdbPath `
            -Serial $transport `
            -AllowFlatScreenshot `
            -AllowPerformanceBudgetMiss `
            -PostProfileAndroidPropertyName $Profiles.native_lock_property `
            -PostProfileAndroidPropertyValue $Profiles.bad_digest `
            -ClearLogcat `
            -StopAfterRun | Out-Null
    } catch {
        $nativeRejected = $true
    }
    $nativeSummaryPath = Join-Path $nativeDir "run-summary.json"
    $nativeSummary = Read-JsonFile -Path $nativeSummaryPath -Label "native off-lock summary"
    Assert-RunSummary -Summary $nativeSummary -Schema "rusty.quest.native_renderer_replay_smoke_run.v1" -AcceptedStatus @("failed") -SerialValue $transport -Label "native off-lock summary"
    if (-not $nativeRejected) {
        throw "Native off-lock effect request unexpectedly completed its positive smoke path."
    }
    $nativeText = Get-Content -Raw -LiteralPath ([string]$nativeSummary.raw_logcat_path)
    Assert-TextTokens -Text $nativeText -Label "native off-lock evidence" -Tokens @(
        "channel=particle-adapter",
        "status=rejected",
        "particleAdapterEnabled=false",
        "activationState=rejected",
        "activationRejectReason=runtime-digest-mismatch",
        "channel=adapter-lock-admission",
        "effectsStarted=false",
        "permissionsRequested=false",
        "sceneStarted=false",
        "inputStarted=false",
        "mediaStarted=false"
    )

    $spatialPlanPath = Join-Path $spatialDir "off-lock-property-plan.json"
    & (Join-Path $PSScriptRoot "Apply-RuntimeProfile.ps1") `
        -ProfilePath $Profiles.spatial `
        -Execute `
        -Out $spatialPlanPath `
        -Adb $script:AdbPath `
        -Serial $transport | Out-Null
    & $script:AdbPath -s $transport shell setprop $Profiles.spatial_lock_property $Profiles.bad_digest | Out-Null
    & (Join-Path $PSScriptRoot "Invoke-SpatialCameraPanelAndroidParticleVisualSmoke.ps1") `
        -RepoRoot $script:RepoRoot `
        -ApkPath $script:SpatialApkPath `
        -OutDir $spatialDir `
        -RunSeconds $RunSeconds `
        -Adb $script:AdbPath `
        -Serial $transport `
        -SurfaceTargetId icosphere `
        -ClearLogcat `
        -StopAfterRun `
        -AllowMissingMarkers | Out-Null
    $spatialSummaryPath = Join-Path $spatialDir "evidence-summary.json"
    $spatialSummary = Read-JsonFile -Path $spatialSummaryPath -Label "Spatial off-lock summary"
    Assert-RunSummary -Summary $spatialSummary -Schema "rusty.quest.spatial_camera_panel_particle_visual_smoke.v1" -AcceptedStatus @("completed") -SerialValue $transport -Label "Spatial off-lock summary" -DollarSchema
    $spatialPaths = @(
        (Join-Path $spatialDir "pid-logcat.txt"),
        (Join-Path $spatialDir "activity-markers.log"),
        (Join-Path $spatialDir "native-markers.log")
    )
    $spatialText = @($spatialPaths | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object { Get-Content -Raw -LiteralPath $_ }) -join "`n"
    Assert-TextTokens -Text $spatialText -Label "Spatial off-lock evidence" -Tokens @(
        "channel=particle-adapter",
        "status=rejected",
        "particleAdapterEnabled=false",
        "activationState=rejected",
        "activationRejectReason=runtime-digest-mismatch",
        "adapter-lock-rejected"
    )
    if ($nativeText -match '(?m)channel=particle-adapter[^\r\n]*status=accepted' -or
        $spatialText -match '(?m)channel=particle-adapter[^\r\n]*status=accepted') {
        throw "Off-lock path emitted an accepted particle-adapter marker."
    }
    $fatalPaths = @([string]$nativeSummary.raw_logcat_path, (Join-Path $spatialDir "logcat-all.txt"))
    $fatals = Measure-FatalEvidence -Paths $fatalPaths
    return New-LiveSource `
        -SerialValue $Device `
        -CriterionId "module_lock_off_lock" `
        -RawPaths (@($nativeSummaryPath, $spatialSummaryPath, $spatialPlanPath, $Profiles.native, $Profiles.spatial) + $fatalPaths) `
        -FatalCounts $fatals `
        -Details ([pscustomobject][ordered]@{
            activation_state = "rejected"
            rejection_reason = "runtime-digest-mismatch"
            effect_request_rejected = $true
            effects_started = $false
        })
}

function Assert-MultiAppBrokerSummary {
    param($Summary, [string[]]$ExpectedSerials)
    if ([string]$Summary.schema -cne "rusty.quest.multi_app_broker_two_quest_evidence.v1" -or
        [string]$Summary.status -cne "pass" -or
        [string]$Summary.coordination_mode -cne "user_authorized_serial_scoped" -or
        [int]$Summary.device_count -ne 2 -or
        [int]$Summary.client_count -ne 2) {
        throw "Real Native+Spatial broker lifecycle summary is not passing its exact live contract."
    }
    foreach ($device in $ExpectedSerials) {
        $matches = @($Summary.rows | Where-Object { [string]$_.serial -ceq $device })
        if ($matches.Count -ne 1) {
            throw "Broker lifecycle summary requires exactly one row for $device."
        }
        $row = $matches[0]
        if ([string]$row.status -cne "pass" -or
            [string]$row.native_client -cne "accepted" -or
            [string]$row.spatial_client -cne "accepted" -or
            -not [bool]$row.distinct_uid -or
            -not [bool]$row.shared_contract_parity -or
            -not [bool]$row.marker_bleed_absent -or
            -not [bool]$row.cleanup_complete -or
            [int]$row.package_fatal_count -ne 0 -or
            [int]$row.system_fatal_count -ne 0) {
            throw "Broker lifecycle row failed for $device."
        }
    }
    if (@($Summary.rows).Count -ne 2) {
        throw "Broker lifecycle summary contains undeclared device rows."
    }
}

function Invoke-BrokerLifecycle {
    param([string[]]$ExpectedSerials, [string]$Directory)
    $transportSerials = @($ExpectedSerials | ForEach-Object { Get-AdbTransport -Device $_ })
    & (Join-Path $PSScriptRoot "Invoke-MultiAppBrokerClientTwoQuest.ps1") `
        -Serial $transportSerials `
        -BrokerApk $script:BrokerApkPath `
        -NativeApk $script:NativeApkPath `
        -SpatialApk $script:SpatialApkPath `
        -EvidenceDir $Directory `
        -CollectLifecycleArtifactsFromApps `
        -GenerateLifecycleRecoveryEvidence `
        -Adb $script:AdbPath | Out-Null
    $summaryPath = Join-Path $Directory "summary.json"
    $summary = Read-JsonFile -Path $summaryPath -Label "multi-app broker lifecycle summary"
    Assert-MultiAppBrokerSummary -Summary $summary -ExpectedSerials $transportSerials
    $sources = @()
    foreach ($device in $ExpectedSerials) {
        $transport = Get-AdbTransport -Device $device
        $row = @($summary.rows | Where-Object { [string]$_.serial -ceq $transport })[0]
        $logPath = Join-Path ([string]$row.evidence_dir) "logcat.txt"
        $fatals = Measure-FatalEvidence -Paths @($logPath)
        foreach ($criterion in @("client_lifecycle_native", "client_lifecycle_spatial")) {
            $clientId = if ($criterion -eq "client_lifecycle_native") { "client.quest.native-renderer" } else { "client.quest.spatial-camera-panel" }
            $sources += New-LiveSource `
                -SerialValue $device `
                -CriterionId $criterion `
                -RawPaths @($summaryPath, $logPath, (Join-Path ([string]$row.evidence_dir) "native-package.txt"), (Join-Path ([string]$row.evidence_dir) "spatial-package.txt")) `
                -FatalCounts $fatals `
                -Details ([pscustomobject][ordered]@{
                    client_id = $clientId
                    lifecycle = "issue-use-replay-revoke-post-revoke"
                    marker_bleed_absent = $true
                    authority_revision_continuous = $true
                })
        }
    }
    return $sources
}

function Assert-Ed25519PublicKey {
    param([string]$Value, [string]$Label)
    try {
        $bytes = [Convert]::FromBase64String($Value)
    } catch {
        throw "$Label is not base64."
    }
    if ($bytes.Length -ne 32) {
        throw "$Label is not a 32-byte Ed25519 public key."
    }
}

function Assert-RequiredProperties {
    param($Value, [string[]]$Names, [string]$Label)
    if ($null -eq $Value) {
        throw "$Label is missing."
    }
    foreach ($name in $Names) {
        if ($null -eq $Value.PSObject.Properties[$name]) {
            throw "$Label is missing required field '$name'."
        }
    }
}

function Assert-PeerReceiptBindings {
    param($Row, [string]$Label)
    foreach ($field in @(
        "operator_enrollment",
        "device_identity",
        "reciprocal_signed_evidence",
        "topology_authorization",
        "direct_lane_lease",
        "key_rotation",
        "revocation",
        "replay",
        "direct_exchange"
    )) {
        if ($null -eq $Row.$field) {
            throw "$Label is missing $field."
        }
        Assert-FileBinding -Binding $Row.$field.receipt -Label "$Label/$field receipt" -RejectFixturePath
    }
    $raw = @($Row.raw_evidence)
    if ($raw.Count -lt 9) {
        throw "$Label does not bind the raw live evidence for every peer-authority phase."
    }
    foreach ($binding in $raw) {
        Assert-FileBinding -Binding $binding -Label "$Label raw evidence" -RejectFixturePath
    }
}

function Assert-PeerAuthoritySummary {
    param($Summary, [string[]]$ExpectedSerials, [string]$Revision)
    Assert-RequiredProperties -Value $Summary -Label "peer provider summary" -Names @(
        "schema", "status", "evidence_tier", "coordination_mode",
        "provider_execution", "synthetic", "fixture_only", "device_count",
        "repository_revision", "rows"
    )
    if ([string]$Summary.schema -cne $script:PeerAuthoritySchema -or
        [string]$Summary.status -cne "pass" -or
        [string]$Summary.evidence_tier -cne "live_two_quest" -or
        [string]$Summary.coordination_mode -cne "user_authorized_serial_scoped" -or
        -not [bool]$Summary.provider_execution -or
        [bool]$Summary.synthetic -or
        [bool]$Summary.fixture_only -or
        [int]$Summary.device_count -ne 2 -or
        [string]$Summary.repository_revision -cne $Revision) {
        throw "Manifold peer-authority provider summary is not exact, live, revision-bound, and passing."
    }
    $keyIds = @()
    $publicKeys = @()
    $enrollmentRevisions = @()
    $rendezvousRevisions = @()
    $roles = @()
    foreach ($device in $ExpectedSerials) {
        $matches = @($Summary.rows | Where-Object { [string]$_.serial -ceq $device })
        if ($matches.Count -ne 1) {
            throw "Peer-authority summary requires exactly one row for $device."
        }
        $row = $matches[0]
        $peer = @($ExpectedSerials | Where-Object { $_ -cne $device })[0]
        Assert-RequiredProperties -Value $row -Label "peer provider row $device" -Names @(
            "serial", "status", "repository_revision", "operator_enrollment",
            "device_identity", "reciprocal_signed_evidence", "revisions",
            "topology_authorization", "direct_lane_lease", "key_rotation",
            "revocation", "replay", "direct_exchange", "route_inactive",
            "cleanup_complete", "cleanup_packages", "package_fatal_count",
            "app_fatal_count", "system_fatal_count", "raw_evidence"
        )
        Assert-RequiredProperties -Value $row.operator_enrollment -Label "$device operator enrollment" -Names @("status", "operator_id", "receipt")
        Assert-RequiredProperties -Value $row.device_identity -Label "$device device identity" -Names @("generation", "key_id", "public_key_ed25519_base64", "receipt")
        Assert-RequiredProperties -Value $row.reciprocal_signed_evidence -Label "$device reciprocal signed evidence" -Names @("status", "peer_serial", "local_signature_valid", "peer_signature_valid", "receipt")
        Assert-RequiredProperties -Value $row.revisions -Label "$device revisions" -Names @("enrollment_revision", "current_enrollment_revision", "rendezvous_revision", "current_rendezvous_revision")
        Assert-RequiredProperties -Value $row.topology_authorization -Label "$device topology authorization" -Names @("status", "schema", "current_revision", "local_role", "receipt")
        Assert-RequiredProperties -Value $row.direct_lane_lease -Label "$device direct-lane lease" -Names @("status", "schema", "current_revision", "real_platform_lane", "lease_id", "receipt")
        Assert-RequiredProperties -Value $row.key_rotation -Label "$device key rotation" -Names @("status", "old_key_rejected", "new_key_id", "receipt")
        Assert-RequiredProperties -Value $row.revocation -Label "$device revocation" -Names @("status", "revoked_key_rejected", "receipt")
        Assert-RequiredProperties -Value $row.replay -Label "$device replay" -Names @("status", "receipt")
        Assert-RequiredProperties -Value $row.direct_exchange -Label "$device direct exchange" -Names @("status", "socket_owner", "interface", "explicit_local_bind", "sent_bytes", "received_bytes", "receipt")
        if ([string]$row.status -cne "pass" -or
            [string]$row.repository_revision -cne $Revision -or
            [string]$row.operator_enrollment.status -cne "accepted" -or
            [string]::IsNullOrWhiteSpace([string]$row.operator_enrollment.operator_id) -or
            [string]$row.device_identity.generation -cne "on-device" -or
            [string]::IsNullOrWhiteSpace([string]$row.device_identity.key_id) -or
            [string]$row.reciprocal_signed_evidence.status -cne "accepted" -or
            [string]$row.reciprocal_signed_evidence.peer_serial -cne $peer -or
            -not [bool]$row.reciprocal_signed_evidence.local_signature_valid -or
            -not [bool]$row.reciprocal_signed_evidence.peer_signature_valid) {
            throw "Peer enrollment/key/signature evidence failed for $device."
        }
        Assert-Ed25519PublicKey -Value ([string]$row.device_identity.public_key_ed25519_base64) -Label "$device public key"
        $enrollmentRevision = [int64]$row.revisions.enrollment_revision
        $rendezvousRevision = [int64]$row.revisions.rendezvous_revision
        if ($enrollmentRevision -lt 1 -or $rendezvousRevision -lt 1 -or
            [int64]$row.revisions.current_enrollment_revision -ne $enrollmentRevision -or
            [int64]$row.revisions.current_rendezvous_revision -ne $rendezvousRevision) {
            throw "Peer enrollment/rendezvous revisions are not current for $device."
        }
        if ([string]$row.topology_authorization.status -cne "accepted" -or
            [string]$row.topology_authorization.schema -cne "rusty.manifold.peer.topology_authorization.v1" -or
            -not [bool]$row.topology_authorization.current_revision -or
            [string]::IsNullOrWhiteSpace([string]$row.topology_authorization.local_role) -or
            [string]$row.direct_lane_lease.status -cne "accepted" -or
            [string]$row.direct_lane_lease.schema -cne "rusty.manifold.peer.direct_lane_lease.v1" -or
            -not [bool]$row.direct_lane_lease.current_revision -or
            -not [bool]$row.direct_lane_lease.real_platform_lane -or
            [string]::IsNullOrWhiteSpace([string]$row.direct_lane_lease.lease_id)) {
            throw "Accepted topology/current real direct-lane lease evidence failed for $device."
        }
        if ([string]$row.key_rotation.status -cne "accepted" -or
            -not [bool]$row.key_rotation.old_key_rejected -or
            [string]::IsNullOrWhiteSpace([string]$row.key_rotation.new_key_id) -or
            [string]$row.key_rotation.new_key_id -ceq [string]$row.device_identity.key_id -or
            [string]$row.revocation.status -cne "accepted" -or
            -not [bool]$row.revocation.revoked_key_rejected -or
            [string]$row.replay.status -cne "rejected") {
            throw "Peer rotate/revoke/replay rejection evidence failed for $device."
        }
        if ([string]$row.direct_exchange.status -cne "pass" -or
            [string]$row.direct_exchange.socket_owner -cne "rusty-owned" -or
            [string]$row.direct_exchange.interface -cne "p2p0" -or
            -not [bool]$row.direct_exchange.explicit_local_bind -or
            [int64]$row.direct_exchange.sent_bytes -lt 1 -or
            [int64]$row.direct_exchange.received_bytes -lt 1 -or
            -not [bool]$row.route_inactive -or
            -not [bool]$row.cleanup_complete) {
            throw "Peer direct exchange or inactive cleanup evidence failed for $device."
        }
        if (@($row.cleanup_packages | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -lt 1) {
            throw "Peer provider row lacks a concrete cleanup package for $device."
        }
        foreach ($fatalField in @("package_fatal_count", "app_fatal_count", "system_fatal_count")) {
            if ([int]$row.$fatalField -ne 0) {
                throw "Peer provider row has nonzero $fatalField for $device."
            }
        }
        Assert-PeerReceiptBindings -Row $row -Label "peer provider $device"
        $keyIds += [string]$row.device_identity.key_id
        $publicKeys += [string]$row.device_identity.public_key_ed25519_base64
        $enrollmentRevisions += $enrollmentRevision
        $rendezvousRevisions += $rendezvousRevision
        $roles += [string]$row.topology_authorization.local_role
    }
    if (@($Summary.rows).Count -ne 2 -or
        @($keyIds | Sort-Object -Unique).Count -ne 2 -or
        @($publicKeys | Sort-Object -Unique).Count -ne 2 -or
        @($enrollmentRevisions | Sort-Object -Unique).Count -ne 1 -or
        @($rendezvousRevisions | Sort-Object -Unique).Count -ne 1 -or
        @($roles | Sort-Object -Unique).Count -ne 2) {
        throw "Peer provider pair identity, revision, role, or row cardinality drifted."
    }
}

function Invoke-PeerAuthority {
    param([string[]]$ExpectedSerials, [string]$Directory, [string]$Revision, [string]$Provider)
    $transportSerials = @($ExpectedSerials | ForEach-Object { Get-AdbTransport -Device $_ })
    & $Provider `
        -Serial $transportSerials `
        -EvidenceDir $Directory `
        -Adb $script:AdbPath `
        -RepositoryRevision $Revision | Out-Null
    $summaryPath = Join-Path $Directory "summary.json"
    $summary = Read-JsonFile -Path $summaryPath -Label "Manifold peer-authority two-Quest summary"
    Assert-PeerAuthoritySummary -Summary $summary -ExpectedSerials $transportSerials -Revision $Revision
    $sources = @()
    foreach ($device in $ExpectedSerials) {
        $transport = Get-AdbTransport -Device $device
        $row = @($summary.rows | Where-Object { [string]$_.serial -ceq $transport })[0]
        $rawPaths = @($summaryPath)
        $rawPaths += @($row.raw_evidence | ForEach-Object { [string]$_.path })
        $rawPaths += @(
            [string]$row.operator_enrollment.receipt.path,
            [string]$row.device_identity.receipt.path,
            [string]$row.reciprocal_signed_evidence.receipt.path,
            [string]$row.topology_authorization.receipt.path,
            [string]$row.direct_lane_lease.receipt.path,
            [string]$row.key_rotation.receipt.path,
            [string]$row.revocation.receipt.path,
            [string]$row.replay.receipt.path,
            [string]$row.direct_exchange.receipt.path
        )
        $rawPaths = Import-RunnerOwnedEvidence `
            -Paths $rawPaths `
            -ImportDirectory (Join-Path $Directory "imported-provider-evidence")
        $counts = [pscustomobject]@{
            package_fatal_count = [int]$row.package_fatal_count
            app_fatal_count = [int]$row.app_fatal_count
            system_fatal_count = [int]$row.system_fatal_count
        }
        foreach ($criterion in @("enrollment_pair", "enrollment_revoke")) {
            $sources += New-LiveSource `
                -SerialValue $device `
                -CriterionId $criterion `
                -RawPaths $rawPaths `
                -FatalCounts $counts `
                -Details ([pscustomobject][ordered]@{
                    key_id = [string]$row.device_identity.key_id
                    enrollment_revision = [int64]$row.revisions.enrollment_revision
                    rendezvous_revision = [int64]$row.revisions.rendezvous_revision
                    direct_lane_lease_id = [string]$row.direct_lane_lease.lease_id
                    rotate_revoke_replay_rejected = $true
                    direct_exchange_passed = $true
                    route_inactive = $true
                })
        }
    }
    $logicalSummary = $summary | ConvertTo-Json -Depth 30 | ConvertFrom-Json
    foreach ($row in @($logicalSummary.rows)) {
        foreach ($device in $ExpectedSerials) {
            $transport = Get-AdbTransport -Device $device
            if ([string]$row.serial -ceq $transport) {
                $row.serial = $device
            }
            if ([string]$row.reciprocal_signed_evidence.peer_serial -ceq $transport) {
                $row.reciprocal_signed_evidence.peer_serial = $device
            }
        }
    }
    return [pscustomobject]@{
        sources = $sources
        summary = $logicalSummary
        summary_path = $summaryPath
    }
}

function Invoke-Camera2Conformance {
    param([string]$Device, [string]$Directory)
    $transport = Get-AdbTransport -Device $Device
    & (Join-Path $PSScriptRoot "Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1") `
        -RepoRoot $script:RepoRoot `
        -ApkPath $script:SpatialApkPath `
        -OutDir $Directory `
        -RunSeconds $RunSeconds `
        -Adb $script:AdbPath `
        -Serial $transport `
        -ClearLogcat `
        -StopAfterRun `
        -SkipForceStopKnownXrPackages | Out-Null
    $summaryPath = Join-Path $Directory "evidence-summary.json"
    $summary = Read-JsonFile -Path $summaryPath -Label "Camera2 media conformance summary"
    Assert-RunSummary -Summary $summary -Schema "rusty.quest.spatial_camera_panel.camera_hwb_projection_smoke.v1" -AcceptedStatus @("passed") -SerialValue $transport -Label "Camera2 media conformance summary" -DollarSchema
    foreach ($flag in @(
        "foreground_validation_passed",
        "camera_runtime_started",
        "camera_frame_acquired",
        "ahb_properties",
        "resources_created",
        "ahb_imported",
        "first_frame_presented",
        "raw_frame_presented",
        "stereo_source_camera_50_51",
        "left_camera_50",
        "right_camera_51"
    )) {
        if (-not [bool]$summary.$flag) {
            throw "Camera2 media conformance is missing $flag for $Device."
        }
    }
    $rawPaths = @($summaryPath, (Join-Path $Directory "pid-logcat.txt"), (Join-Path $Directory "logcat-all.txt"), (Join-Path $Directory "foreground-proof.json"))
    $fatals = Measure-FatalEvidence -Paths @((Join-Path $Directory "pid-logcat.txt"), (Join-Path $Directory "logcat-all.txt"))
    return New-LiveSource `
        -SerialValue $Device `
        -CriterionId "media_camera2" `
        -RawPaths $rawPaths `
        -FatalCounts $fatals `
        -Details ([pscustomobject][ordered]@{
            source_kind = "camera2_hardware_buffer"
            stereo_camera_ids = @("50", "51")
            hardware_buffer_imported = $true
            first_frame_presented = $true
        })
}

function Invoke-DisplayCompositeConformance {
    param([string]$Device, [string]$Directory)
    $transport = Get-AdbTransport -Device $Device
    & (Join-Path $PSScriptRoot "Invoke-NativeRendererDisplayCompositeSmoke.ps1") `
        -ApkPath $script:NativeApkPath `
        -OutDir $Directory `
        -RunSeconds $RunSeconds `
        -Adb $script:AdbPath `
        -Serial $transport `
        -ClearLogcat `
        -StopAfterRun | Out-Null
    $summaryPath = Join-Path $Directory "run-summary.json"
    $summary = Read-JsonFile -Path $summaryPath -Label "display-composite media conformance summary"
    Assert-RunSummary -Summary $summary -Schema "rusty.quest.native_renderer_display_composite_smoke.v1" -AcceptedStatus @("completed") -SerialValue $transport -Label "display-composite media conformance summary"
    if ([string]$summary.marker_validation_status -cne "passed") {
        throw "Display-composite marker validation did not pass for $Device."
    }
    $rawPaths = @($summaryPath, [string]$summary.raw_logcat_path, [string]$summary.filtered_logcat_path, [string]$summary.service_state_path)
    $fatals = Measure-FatalEvidence -Paths @([string]$summary.raw_logcat_path)
    return New-LiveSource `
        -SerialValue $Device `
        -CriterionId "media_display_composite" `
        -RawPaths $rawPaths `
        -FatalCounts $fatals `
        -Details ([pscustomobject][ordered]@{
            source_kind = "display_composite_mediaprojection_hardware_buffer"
            marker_validation = "passed"
            gpu_imported = $true
            feedback_rendered = $true
        })
}

function Invoke-FinalCleanup {
    param(
        [string]$Device,
        [string]$Directory,
        [string]$Revision,
        $PeerRow,
        [object[]]$DeviceSources
    )
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $packages = @(
        "io.github.mesmerprism.rustymanifold.broker",
        "io.github.mesmerprism.rustyquest.native_renderer",
        "io.github.mesmerprism.rustyquest.spatial_camera_panel"
    )
    $packages += @($PeerRow.cleanup_packages | ForEach-Object { [string]$_ })
    $packages = @($packages | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
    $cleanupCommandsPath = Join-Path $Directory "cleanup-commands.txt"
    $commandEvidence = @()
    foreach ($package in $packages) {
        $stop = Invoke-SerialAdb -Device $Device -Arguments @("shell", "am", "force-stop", $package) -AllowFailure
        $uninstall = Invoke-SerialAdb -Device $Device -Arguments @("uninstall", $package) -AllowFailure
        $commandEvidence += "package=$package force_stop_exit=$($stop.exit_code) uninstall_exit=$($uninstall.exit_code)"
    }
    [System.IO.File]::WriteAllLines($cleanupCommandsPath, [string[]]$commandEvidence, $script:Utf8NoBom)
    $packageListPath = Join-Path $Directory "installed-packages-after.txt"
    $packageList = (Invoke-SerialAdb -Device $Device -Arguments @("shell", "pm", "list", "packages")).output
    [System.IO.File]::WriteAllText($packageListPath, $packageList, $script:Utf8NoBom)
    $remaining = @($packages | Where-Object { $packageList -match "(?m)^package:$([regex]::Escape($_))$" })

    $processPath = Join-Path $Directory "package-processes-after.txt"
    $processEvidence = @()
    $processesRemaining = @()
    foreach ($package in $packages) {
        $pidResult = Invoke-SerialAdb -Device $Device -Arguments @("shell", "pidof", $package) -AllowFailure
        $pidText = $pidResult.output.Trim()
        $processEvidence += "package=$package exit=$($pidResult.exit_code) pid=$pidText"
        if (-not [string]::IsNullOrWhiteSpace($pidText)) { $processesRemaining += $package }
    }
    [System.IO.File]::WriteAllLines($processPath, [string[]]$processEvidence, $script:Utf8NoBom)

    $p2pPath = Join-Path $Directory "wifi-p2p-after.txt"
    $p2pText = (Invoke-SerialAdb -Device $Device -Arguments @("shell", "dumpsys", "wifi", "p2p")).output
    [System.IO.File]::WriteAllText($p2pPath, $p2pText, $script:Utf8NoBom)
    $interfacePath = Join-Path $Directory "p2p0-interface-after.txt"
    $interface = Invoke-SerialAdb -Device $Device -Arguments @("shell", "ip", "address", "show", "p2p0") -AllowFailure
    [System.IO.File]::WriteAllText($interfacePath, "exit=$($interface.exit_code)`n$($interface.output)", $script:Utf8NoBom)
    $p2pInterfaceExplicitlyInactive = $interface.output -match '(?im)\bp2p0:.*\bstate\s+DOWN\b' -or
        $interface.output -match '(?im)\bp2p0:.*<[^>]*NO-CARRIER'

    $routeActive = $p2pText -match '(?i)groupFormed\s*[:=]\s*true|networkInfo[^\r\n]*(?:CONNECTED|CONNECTING)'
    $routeExplicitlyInactive = $p2pText -match '(?i)groupFormed\s*[:=]\s*false|networkInfo[^\r\n]*(?:DISCONNECTED|DISCONNECTING)' -or
        $p2pInterfaceExplicitlyInactive

    $socketPath = Join-Path $Directory "tcp-sockets-after.txt"
    $socketResult = Invoke-SerialAdb -Device $Device -Arguments @("shell", "cat", "/proc/net/tcp", "/proc/net/tcp6")
    [System.IO.File]::WriteAllText($socketPath, $socketResult.output, $script:Utf8NoBom)
    $productPortHex = @(8765, 8879, 8979, 9079) | ForEach-Object { ':{0:X4}' -f $_ }
    $productSocketsRemaining = @($productPortHex | Where-Object { $socketResult.output -match [regex]::Escape($_) })

    if ($remaining.Count -ne 0 -or $processesRemaining.Count -ne 0 -or
        $productSocketsRemaining.Count -ne 0 -or $routeActive -or -not $routeExplicitlyInactive) {
        throw "Final cleanup failed for $Device; packages=$($remaining -join ','), processes=$($processesRemaining -join ','), sockets=$($productSocketsRemaining -join ','), route_active=$routeActive, route_explicitly_inactive=$routeExplicitlyInactive, p2p_interface_explicitly_inactive=$p2pInterfaceExplicitlyInactive."
    }
    $finalLogcatPath = Join-Path $Directory "bounded-final-logcat.txt"
    $finalLogcat = (Invoke-SerialAdb -Device $Device -Arguments @("logcat", "-d", "-v", "time")).output
    [System.IO.File]::WriteAllText($finalLogcatPath, $finalLogcat, $script:Utf8NoBom)
    $allFatalPaths = @($finalLogcatPath)
    foreach ($source in $DeviceSources) {
        $allFatalPaths += @($source.raw_paths | Where-Object { $_ -match '(?i)logcat|markers\.log$' })
    }
    $allFatalPaths = @($allFatalPaths | Where-Object { Test-Path -LiteralPath $_ } | Sort-Object -Unique)
    $fatals = Measure-FatalEvidence -Paths $allFatalPaths
    Assert-ZeroFatals -Counts $fatals -Label "final bounded fatal window $Device"
    $receiptPath = Join-Path $Directory "cleanup-receipt.json"
    $receipt = [ordered]@{
        schema = $script:CleanupSchema
        status = "pass"
        run_id = $script:RunId
        observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        serial = $Device
        repository_revision = $Revision
        adb_scope = "device-scoped-adb"
        packages_checked = $packages
        product_ports_checked = @(8765, 8879, 8979, 9079)
        packages_remaining = $remaining
        processes_remaining = $processesRemaining
        product_sockets_remaining = $productSocketsRemaining
        p2p_interface_explicitly_inactive = $p2pInterfaceExplicitlyInactive
        peer_route_inactive = (-not $routeActive -and $routeExplicitlyInactive)
        cleanup_complete = $true
        package_fatal_count = [int]$fatals.package_fatal_count
        app_fatal_count = [int]$fatals.app_fatal_count
        system_fatal_count = [int]$fatals.system_fatal_count
        evidence = @(
            New-FileBinding -Path $cleanupCommandsPath
            New-FileBinding -Path $packageListPath
            New-FileBinding -Path $processPath
            New-FileBinding -Path $p2pPath
            New-FileBinding -Path $interfacePath
            New-FileBinding -Path $socketPath
            New-FileBinding -Path $finalLogcatPath
        )
        command_evidence = New-FileBinding -Path $cleanupCommandsPath
        package_list_evidence = New-FileBinding -Path $packageListPath
        process_evidence = New-FileBinding -Path $processPath
        p2p_evidence = New-FileBinding -Path $p2pPath
        interface_evidence = New-FileBinding -Path $interfacePath
        socket_evidence = New-FileBinding -Path $socketPath
        logcat_evidence = New-FileBinding -Path $finalLogcatPath
    }
    Write-JsonFile -Path $receiptPath -Value $receipt
    return [pscustomobject]@{
        path = $receiptPath
        receipt = Read-JsonFile -Path $receiptPath -Label "final cleanup receipt"
        fatal_paths = $allFatalPaths
    }
}

function Invoke-EmergencyCleanup {
    param([string]$Device, [string]$Directory, [string]$Revision)
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $packages = @(
        "io.github.mesmerprism.rustymanifold.broker",
        "io.github.mesmerprism.rustyquest.native_renderer",
        "io.github.mesmerprism.rustyquest.spatial_camera_panel",
        "io.github.mesmerprism.rustyquest.direct_p2p_provider"
    )
    $commands = @()
    foreach ($package in $packages) {
        try {
            $stop = Invoke-SerialAdb -Device $Device -Arguments @("shell", "am", "force-stop", $package) -AllowFailure
            $uninstall = Invoke-SerialAdb -Device $Device -Arguments @("uninstall", $package) -AllowFailure
            $commands += "package=$package force_stop_exit=$($stop.exit_code) uninstall_exit=$($uninstall.exit_code)"
        } catch {
            $commands += "package=$package cleanup_exception=$($_.Exception.Message)"
        }
    }
    $commandPath = Join-Path $Directory "emergency-cleanup-commands.txt"
    [IO.File]::WriteAllLines($commandPath, [string[]]$commands, $script:Utf8NoBom)
    $packagePath = Join-Path $Directory "installed-packages-after.txt"
    $p2pPath = Join-Path $Directory "wifi-p2p-after.txt"
    $interfacePath = Join-Path $Directory "p2p0-interface-after.txt"
    $processPath = Join-Path $Directory "package-processes-after.txt"
    $socketPath = Join-Path $Directory "tcp-sockets-after.txt"
    $logcatPath = Join-Path $Directory "bounded-final-logcat.txt"
    $packageText = "adb-unavailable"
    $p2pText = "adb-unavailable"
    $interfaceText = "adb-unavailable"
    $processText = "adb-unavailable"
    $socketText = "adb-unavailable"
    $logcatText = "adb-unavailable"
    try { $packageText = (Invoke-SerialAdb -Device $Device -Arguments @("shell", "pm", "list", "packages") -AllowFailure).output } catch {}
    try { $p2pText = (Invoke-SerialAdb -Device $Device -Arguments @("shell", "dumpsys", "wifi", "p2p") -AllowFailure).output } catch {}
    try {
        $interfaceResult = Invoke-SerialAdb -Device $Device -Arguments @("shell", "ip", "address", "show", "p2p0") -AllowFailure
        $interfaceText = "exit=$($interfaceResult.exit_code)`n$($interfaceResult.output)"
    } catch {}
    $processProbeAvailable = $true
    $rows = @()
    foreach ($package in $packages) {
        try {
            $pidResult = Invoke-SerialAdb -Device $Device -Arguments @("shell", "pidof", $package) -AllowFailure
            $rows += "package=$package exit=$($pidResult.exit_code) pid=$($pidResult.output.Trim())"
        } catch {
            $processProbeAvailable = $false
            $rows += "package=$package probe_exception=$($_.Exception.Message)"
        }
    }
    $processText = $rows -join "`n"
    try { $socketText = (Invoke-SerialAdb -Device $Device -Arguments @("shell", "cat", "/proc/net/tcp", "/proc/net/tcp6") -AllowFailure).output } catch {}
    try { $logcatText = (Invoke-SerialAdb -Device $Device -Arguments @("logcat", "-d", "-v", "time") -AllowFailure).output } catch {}
    [IO.File]::WriteAllText($packagePath, $packageText, $script:Utf8NoBom)
    [IO.File]::WriteAllText($p2pPath, $p2pText, $script:Utf8NoBom)
    [IO.File]::WriteAllText($interfacePath, $interfaceText, $script:Utf8NoBom)
    [IO.File]::WriteAllText($processPath, $processText, $script:Utf8NoBom)
    [IO.File]::WriteAllText($socketPath, $socketText, $script:Utf8NoBom)
    [IO.File]::WriteAllText($logcatPath, $logcatText, $script:Utf8NoBom)
    $remaining = @($packages | Where-Object { $packageText -match "(?m)^package:$([regex]::Escape($_))$" })
    $routeActive = $p2pText -match '(?i)groupFormed\s*[:=]\s*true|networkInfo[^\r\n]*CONNECTED'
    $p2pInterfaceExplicitlyInactive = $interfaceText -match '(?im)\bp2p0:.*\bstate\s+DOWN\b' -or
        $interfaceText -match '(?im)\bp2p0:.*<[^>]*NO-CARRIER'
    $routeExplicitlyInactive = $p2pText -match '(?i)groupFormed\s*[:=]\s*false|networkInfo[^\r\n]*(?:DISCONNECTED|DISCONNECTING)' -or
        $p2pInterfaceExplicitlyInactive
    $processesRemaining = @($packages | Where-Object { $processText -match "(?m)^package=$([regex]::Escape($_))[^\r\n]*pid=\s*\d+" })
    $productPortHex = @(8765, 8879, 8979, 9079) | ForEach-Object { ':{0:X4}' -f $_ }
    $productSocketsRemaining = @($productPortHex | Where-Object { $socketText -match [regex]::Escape($_) })
    $fatals = Measure-FatalEvidence -Paths @($logcatPath)
    $hasCleanupException = @($commands | Where-Object { $_ -match 'cleanup_exception=' }).Count -ne 0
    $zeroFatals = [int]$fatals.package_fatal_count -eq 0 -and [int]$fatals.app_fatal_count -eq 0 -and [int]$fatals.system_fatal_count -eq 0
    $complete = $remaining.Count -eq 0 -and $processesRemaining.Count -eq 0 -and
        $productSocketsRemaining.Count -eq 0 -and -not $routeActive -and $routeExplicitlyInactive -and
        -not $hasCleanupException -and $zeroFatals -and
        $packageText -cne "adb-unavailable" -and $p2pText -cne "adb-unavailable" -and
        $processProbeAvailable -and $socketText -cne "adb-unavailable" -and
        $logcatText -cne "adb-unavailable"
    $receipt = [ordered]@{
        schema = "rusty.quest.corrected_release_emergency_cleanup.v1"
        status = if ($complete) { "pass" } else { "incomplete" }
        run_id = $script:RunId
        observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        serial = $Device
        repository_revision = $Revision
        cleanup_complete = $complete
        packages_checked = $packages
        product_ports_checked = @(8765, 8879, 8979, 9079)
        packages_remaining = $remaining
        processes_remaining = $processesRemaining
        product_sockets_remaining = $productSocketsRemaining
        process_probe_available = $processProbeAvailable
        cleanup_exception_count = @($commands | Where-Object { $_ -match 'cleanup_exception=' }).Count
        adb_unavailable = [ordered]@{
            packages = ($packageText -ceq "adb-unavailable")
            p2p = ($p2pText -ceq "adb-unavailable")
            interface = ($interfaceText -ceq "adb-unavailable")
            processes = (-not $processProbeAvailable)
            sockets = ($socketText -ceq "adb-unavailable")
            logcat = ($logcatText -ceq "adb-unavailable")
        }
        p2p_interface_explicitly_inactive = $p2pInterfaceExplicitlyInactive
        peer_route_inactive = (-not $routeActive -and $routeExplicitlyInactive)
        package_fatal_count = [int]$fatals.package_fatal_count
        app_fatal_count = [int]$fatals.app_fatal_count
        system_fatal_count = [int]$fatals.system_fatal_count
        evidence = @(
            New-FileBinding -Path $commandPath
            New-FileBinding -Path $packagePath
            New-FileBinding -Path $p2pPath
            New-FileBinding -Path $interfacePath
            New-FileBinding -Path $processPath
            New-FileBinding -Path $socketPath
            New-FileBinding -Path $logcatPath
        )
        command_evidence = New-FileBinding -Path $commandPath
        package_list_evidence = New-FileBinding -Path $packagePath
        p2p_evidence = New-FileBinding -Path $p2pPath
        interface_evidence = New-FileBinding -Path $interfacePath
        process_evidence = New-FileBinding -Path $processPath
        socket_evidence = New-FileBinding -Path $socketPath
        logcat_evidence = New-FileBinding -Path $logcatPath
    }
    $path = Join-Path $Directory "emergency-cleanup-receipt.json"
    Write-JsonFile -Path $path -Value $receipt
    return New-FileBinding -Path $path
}

function Write-CriterionReceipt {
    param(
        $Source,
        $Cleanup,
        [string]$Revision,
        [string]$Directory,
        [switch]$AllowSynthetic
    )
    if ([string]$Source.status -cne "pass" -or -not [bool]$Source.device_execution -or
        [string]$Source.run_id -cne $script:RunId) {
        throw "$($Source.serial)/$($Source.criterion_id) lacks a passing live device execution."
    }
    if (-not $AllowSynthetic -and ([bool]$Source.synthetic -or [bool]$Source.fixture_only)) {
        throw "$($Source.serial)/$($Source.criterion_id) attempted fixture/synthetic promotion."
    }
    Assert-ZeroFatals -Counts $Source -Label "$($Source.serial)/$($Source.criterion_id) source"
    if ([string]$Cleanup.receipt.status -cne "pass" -or -not [bool]$Cleanup.receipt.cleanup_complete) {
        throw "$($Source.serial)/$($Source.criterion_id) lacks final cleanup evidence."
    }
    Assert-ZeroFatals -Counts $Cleanup.receipt -Label "$($Source.serial)/$($Source.criterion_id) cleanup"
    $raw = @()
    foreach ($path in @($Source.raw_paths | Sort-Object -Unique)) {
        $binding = New-FileBinding -Path $path
        if (-not $AllowSynthetic) {
            Assert-FileBinding -Binding $binding -Label "$($Source.serial)/$($Source.criterion_id) raw evidence" -RejectFixturePath -AllowedRoot $script:EvidenceRoot
        }
        $raw += $binding
    }
    $cleanupBinding = New-FileBinding -Path $Cleanup.path
    $receipt = [ordered]@{
        schema = $script:CriterionSchema
        status = "pass"
        run_id = $script:RunId
        observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        preflight_sha256 = $script:PreflightSha256
        serial = [string]$Source.serial
        criterion_id = [string]$Source.criterion_id
        repository_revision = $Revision
        evidence_tier = "live_two_quest"
        device_execution = $true
        synthetic = $false
        fixture_only = $false
        supported = [bool]$Source.supported
        package_fatal_count = 0
        app_fatal_count = 0
        system_fatal_count = 0
        cleanup_complete = $true
        cleanup_evidence = $cleanupBinding
        raw_evidence = $raw
        details = $Source.details
    }
    $path = Join-Path $Directory "$($Source.criterion_id).json"
    Write-JsonFile -Path $path -Value $receipt
    return [pscustomobject][ordered]@{
        run_id = $script:RunId
        serial = [string]$Source.serial
        criterion_id = [string]$Source.criterion_id
        status = "pass"
        cleanup_complete = $true
        package_fatal_count = 0
        app_fatal_count = 0
        system_fatal_count = 0
        repository_revision = $Revision
        evidence = New-FileBinding -Path $path
    }
}

function Assert-ReleaseMatrix {
    param($Matrix, [string[]]$ExpectedSerials, [string]$ExpectedRunId = "")
    if ([string]$Matrix.schema -cne $script:MatrixSchema -or [string]$Matrix.status -cne "pass") {
        throw "Corrected release device matrix schema/status is invalid."
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedRunId) -and [string]$Matrix.run_id -cne $ExpectedRunId) {
        throw "Corrected release device matrix run identity drifted."
    }
    $rows = @($Matrix.rows)
    foreach ($device in $ExpectedSerials) {
        foreach ($criterion in $script:RequiredCriteria) {
            $matches = @($rows | Where-Object { [string]$_.serial -ceq $device -and [string]$_.criterion_id -ceq $criterion })
            if ($matches.Count -ne 1) {
                throw "Matrix requires exactly one $device/$criterion row."
            }
            $row = $matches[0]
            if ([string]$row.status -cne "pass" -or -not [bool]$row.cleanup_complete) {
                throw "Matrix row is not a clean pass: $device/$criterion"
            }
            if (-not [string]::IsNullOrWhiteSpace($ExpectedRunId) -and [string]$row.run_id -cne $ExpectedRunId) {
                throw "Matrix row belongs to another run: $device/$criterion"
            }
            Assert-Revision -Revision ([string]$row.repository_revision) -Label "$device/$criterion revision"
            Assert-ZeroFatals -Counts $row -Label "$device/$criterion"
            Assert-FileBinding -Binding $row.evidence -Label "$device/$criterion evidence"
        }
    }
    if ($rows.Count -ne ($ExpectedSerials.Count * $script:RequiredCriteria.Count)) {
        throw "Matrix contains missing, duplicate, unknown, or unscoped rows."
    }
}

function Assert-CriterionReceiptClosure {
    param($Row, [string]$ExpectedRevision, [string]$ExpectedRunId, [string]$ExpectedRoot, [DateTimeOffset]$StartedAt, [DateTimeOffset]$FinishedAt, [string]$ExpectedPreflightSha256)
    Assert-FileBinding -Binding $Row.evidence -Label "$($Row.serial)/$($Row.criterion_id) criterion receipt" -RejectFixturePath -AllowedRoot $ExpectedRoot
    $receipt = Read-JsonFile -Path ([string]$Row.evidence.path) -Label "criterion receipt"
    if ([string]$receipt.schema -cne $script:CriterionSchema -or
        [string]$receipt.status -cne "pass" -or
        [string]$receipt.serial -cne [string]$Row.serial -or
        [string]$receipt.criterion_id -cne [string]$Row.criterion_id -or
        [string]$receipt.run_id -cne $ExpectedRunId -or [string]$Row.run_id -cne $ExpectedRunId -or
        [string]$receipt.preflight_sha256 -cne $ExpectedPreflightSha256 -or
        [string]$receipt.repository_revision -cne $ExpectedRevision -or
        -not [bool]$receipt.device_execution -or [bool]$receipt.synthetic -or
        [bool]$receipt.fixture_only -or -not [bool]$receipt.cleanup_complete) {
        throw "$($Row.serial)/$($Row.criterion_id) criterion receipt closure drifted."
    }
    Assert-RunTimestamp -Value $receipt.observed_at -StartedAt $StartedAt -FinishedAt $FinishedAt -Label "$($Row.serial)/$($Row.criterion_id) receipt"
    Assert-ZeroFatals -Counts $receipt -Label "$($Row.serial)/$($Row.criterion_id) criterion receipt"
    $raw = @($receipt.raw_evidence)
    if ($raw.Count -eq 0) { throw "$($Row.serial)/$($Row.criterion_id) has no raw evidence." }
    foreach ($binding in $raw) {
        Assert-FileBinding -Binding $binding -Label "$($Row.serial)/$($Row.criterion_id) raw evidence" -RejectFixturePath -AllowedRoot $ExpectedRoot
    }
    Assert-FileBinding -Binding $receipt.cleanup_evidence -Label "$($Row.serial)/$($Row.criterion_id) cleanup evidence" -RejectFixturePath -AllowedRoot $ExpectedRoot
    $cleanup = Read-JsonFile -Path ([string]$receipt.cleanup_evidence.path) -Label "cleanup receipt"
    if ([string]$cleanup.schema -cne $script:CleanupSchema -or
        [string]$cleanup.status -cne "pass" -or
        [string]$cleanup.serial -cne [string]$Row.serial -or
        [string]$cleanup.run_id -cne $ExpectedRunId -or
        [string]$cleanup.repository_revision -cne $ExpectedRevision -or
        -not [bool]$cleanup.cleanup_complete -or
        -not [bool]$cleanup.peer_route_inactive -or
        @($cleanup.packages_remaining).Count -ne 0 -or
        @($cleanup.processes_remaining).Count -ne 0 -or
        @($cleanup.product_sockets_remaining).Count -ne 0) {
        throw "$($Row.serial)/$($Row.criterion_id) cleanup receipt closure drifted."
    }
    Assert-RunTimestamp -Value $cleanup.observed_at -StartedAt $StartedAt -FinishedAt $FinishedAt -Label "$($Row.serial)/$($Row.criterion_id) cleanup"
    Assert-ZeroFatals -Counts $cleanup -Label "$($Row.serial)/$($Row.criterion_id) cleanup receipt"
    foreach ($binding in @($cleanup.evidence)) {
        Assert-FileBinding -Binding $binding -Label "$($Row.serial)/$($Row.criterion_id) cleanup raw evidence" -RejectFixturePath -AllowedRoot $ExpectedRoot
    }

    foreach ($field in @("package_list_evidence", "process_evidence", "p2p_evidence", "interface_evidence", "socket_evidence", "logcat_evidence")) {
        Assert-FileBinding -Binding $cleanup.$field -Label "$($Row.serial)/$($Row.criterion_id) $field" -RejectFixturePath -AllowedRoot $ExpectedRoot
    }
    $packageText = Get-Content -Raw -LiteralPath ([string]$cleanup.package_list_evidence.path)
    foreach ($package in @($cleanup.packages_checked)) {
        if ($packageText -match "(?m)^package:$([regex]::Escape([string]$package))$") {
            throw "$($Row.serial)/$($Row.criterion_id) raw package evidence still contains $package."
        }
    }
    $processText = Get-Content -Raw -LiteralPath ([string]$cleanup.process_evidence.path)
    if ($processText -match '(?m)^package=[^\r\n]+\s+exit=\d+\s+pid=\s*\d+') {
        throw "$($Row.serial)/$($Row.criterion_id) raw process evidence still contains a product process."
    }
    $p2pText = Get-Content -Raw -LiteralPath ([string]$cleanup.p2p_evidence.path)
    $interfaceText = Get-Content -Raw -LiteralPath ([string]$cleanup.interface_evidence.path)
    $routeActive = $p2pText -match '(?i)groupFormed\s*[:=]\s*true|networkInfo[^\r\n]*(?:CONNECTED|CONNECTING)'
    $routeInactive = $p2pText -match '(?i)groupFormed\s*[:=]\s*false|networkInfo[^\r\n]*(?:DISCONNECTED|DISCONNECTING)' -or
        $interfaceText -match '(?im)\bp2p0:.*\bstate\s+DOWN\b' -or
        $interfaceText -match '(?im)\bp2p0:.*<[^>]*NO-CARRIER'
    if ($routeActive -or -not [bool]$cleanup.p2p_interface_explicitly_inactive -or -not $routeInactive) {
        throw "$($Row.serial)/$($Row.criterion_id) raw P2P evidence is active or unknown."
    }
    $socketText = Get-Content -Raw -LiteralPath ([string]$cleanup.socket_evidence.path)
    foreach ($port in @($cleanup.product_ports_checked)) {
        if ($socketText -match [regex]::Escape((':{0:X4}' -f $port))) {
            throw "$($Row.serial)/$($Row.criterion_id) raw socket evidence retains product port $port."
        }
    }
}

function Assert-FinallyCleanupClosure {
    param($Binding, [string[]]$ExpectedSerials, [string]$ExpectedRevision, [string]$ExpectedRunId, [string]$ExpectedRoot, [DateTimeOffset]$StartedAt, [DateTimeOffset]$FinishedAt)
    Assert-FileBinding -Binding $Binding -Label "matrix finally cleanup" -RejectFixturePath -AllowedRoot $ExpectedRoot
    $summary = Read-JsonFile -Path ([string]$Binding.path) -Label "matrix finally cleanup summary"
    if ([string]$summary.schema -cne "rusty.quest.corrected_release_finally_cleanup_summary.v1" -or
        [string]$summary.status -cne "completed" -or [string]$summary.run_id -cne $ExpectedRunId -or
        [string]$summary.repository_revision -cne $ExpectedRevision -or
        (@($summary.serials) -join "`n") -cne ($ExpectedSerials -join "`n") -or
        [bool]$summary.original_run_failed -or @($summary.cleanup).Count -ne 2) {
        throw "Matrix finally cleanup summary is incomplete or belongs to another run."
    }
    Assert-RunTimestamp -Value $summary.observed_at -StartedAt $StartedAt -FinishedAt $FinishedAt -Label "finally cleanup summary"
    foreach ($device in $ExpectedSerials) {
        $matches = @($summary.cleanup | Where-Object { [string]$_.serial -ceq $device })
        if ($matches.Count -ne 1) { throw "Finally cleanup requires one bound receipt for $device." }
        Assert-FileBinding -Binding $matches[0].receipt -Label "$device emergency cleanup receipt" -RejectFixturePath -AllowedRoot $ExpectedRoot
        $receipt = Read-JsonFile -Path ([string]$matches[0].receipt.path) -Label "$device emergency cleanup receipt"
        if ([string]$receipt.schema -cne "rusty.quest.corrected_release_emergency_cleanup.v1" -or
            [string]$receipt.status -cne "pass" -or [string]$receipt.run_id -cne $ExpectedRunId -or
            [string]$receipt.serial -cne $device -or [string]$receipt.repository_revision -cne $ExpectedRevision -or
            -not [bool]$receipt.cleanup_complete -or -not [bool]$receipt.peer_route_inactive -or
            @($receipt.packages_remaining).Count -ne 0 -or @($receipt.processes_remaining).Count -ne 0 -or
            @($receipt.product_sockets_remaining).Count -ne 0) {
            throw "$device emergency cleanup did not prove a closed platform state."
        }
        Assert-ZeroFatals -Counts $receipt -Label "$device emergency cleanup"
        Assert-RunTimestamp -Value $receipt.observed_at -StartedAt $StartedAt -FinishedAt $FinishedAt -Label "$device emergency cleanup"
        foreach ($field in @("package_list_evidence", "p2p_evidence", "process_evidence", "socket_evidence", "logcat_evidence")) {
            Assert-FileBinding -Binding $receipt.$field -Label "$device emergency $field" -RejectFixturePath -AllowedRoot $ExpectedRoot
        }
        $packages = Get-Content -Raw -LiteralPath ([string]$receipt.package_list_evidence.path)
        foreach ($package in @($receipt.packages_checked)) {
            if ($packages -match "(?m)^package:$([regex]::Escape([string]$package))$") {
                throw "$device emergency raw package evidence still contains $package."
            }
        }
        $processes = Get-Content -Raw -LiteralPath ([string]$receipt.process_evidence.path)
        if ($processes -match '(?m)^package=[^\r\n]+\s+exit=\d+\s+pid=\s*\d+') {
            throw "$device emergency raw process evidence retains a product process."
        }
        $sockets = Get-Content -Raw -LiteralPath ([string]$receipt.socket_evidence.path)
        foreach ($port in @($receipt.product_ports_checked)) {
            if ($sockets -match [regex]::Escape((':{0:X4}' -f [int]$port))) {
                throw "$device emergency raw socket evidence retains product port $port."
            }
        }
        $p2p = Get-Content -Raw -LiteralPath ([string]$receipt.p2p_evidence.path)
        $interface = Get-Content -Raw -LiteralPath ([string]$receipt.interface_evidence.path)
        $rawP2pExplicitlyInactive = $p2p -match '(?i)groupFormed\s*[:=]\s*false|networkInfo[^\r\n]*(?:DISCONNECTED|DISCONNECTING)' -or
            $interface -match '(?im)\bp2p0:.*\bstate\s+DOWN\b' -or
            $interface -match '(?im)\bp2p0:.*<[^>]*NO-CARRIER'
        if ($p2p -match '(?i)groupFormed\s*[:=]\s*true|networkInfo[^\r\n]*(?:CONNECTED|CONNECTING)' -or
            -not [bool]$receipt.p2p_interface_explicitly_inactive -or -not $rawP2pExplicitlyInactive) {
            throw "$device emergency raw P2P state is active or unknown."
        }
        Assert-ZeroFatals -Counts (Measure-FatalEvidence -Paths @([string]$receipt.logcat_evidence.path)) -Label "$device emergency raw logcat"
    }
}

function Get-CleanRustyQuestRevision {
    $currentRevision = (& git -C $script:RepoRoot rev-parse --verify HEAD).Trim().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0) { throw "Unable to resolve current Rusty Quest revision." }
    Assert-Revision -Revision $currentRevision -Label "current Rusty Quest revision"
    $dirty = @(& git -C $script:RepoRoot status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) {
        throw "Matrix validation requires the exact current Rusty Quest worktree to be clean."
    }
    return $currentRevision
}

function Assert-DeviceMatrixClosure {
    param(
        [Parameter(Mandatory = $true)]$Matrix,
        [Parameter(Mandatory = $true)][string]$MatrixPathValue,
        [Parameter(Mandatory = $true)][string]$ExpectedRevision
    )
    $matrix = $Matrix
    if ([string]$matrix.run_id -cnotmatch '^corrected-release-[0-9a-f]{32}$') {
        throw "Matrix run_id is invalid."
    }
    $root = [IO.Path]::GetFullPath([string]$matrix.evidence_root)
    $matrixFull = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $MatrixPathValue).Path)
    $rootPrefix = $root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $matrixFull.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Matrix is outside its runner-owned evidence root."
    }
    $startedAt = ConvertTo-InvariantTimestamp -Value $matrix.started_at -Label "matrix started_at"
    $finishedAt = ConvertTo-InvariantTimestamp -Value $matrix.finished_at -Label "matrix finished_at"
    $age = [DateTimeOffset]::UtcNow - $finishedAt.ToUniversalTime()
    if ($finishedAt -lt $startedAt -or $age.TotalMinutes -lt -5 -or $age.TotalHours -gt 24) {
        throw "Matrix run window is invalid or stale."
    }
    $serials = Assert-ExplicitSerials -Values @($matrix.serials | ForEach-Object { [string]$_ })
    if ([string]$matrix.repository -cne "rusty-quest" -or
        [string]$matrix.repository_revision -cne $ExpectedRevision -or
        [string]$matrix.coordination_mode -cne "user_authorized_serial_scoped" -or
        [string]$matrix.evidence_tier -cne "live_two_quest") {
        throw "Matrix is not bound to the exact current live Rusty Quest release surface."
    }
    Assert-ReleaseMatrix -Matrix $matrix -ExpectedSerials $serials -ExpectedRunId ([string]$matrix.run_id)
    Assert-FileBinding -Binding $matrix.preflight -Label "matrix preflight" -RejectFixturePath -AllowedRoot $root
    $preflight = Read-JsonFile -Path ([string]$matrix.preflight.path) -Label "matrix preflight"
    if ([string]$preflight.schema -cne "rusty.quest.corrected_release_two_quest_preflight.v1" -or
        [string]$preflight.status -cne "pass" -or
        [string]$preflight.run_id -cne [string]$matrix.run_id -or
        [string]$preflight.evidence_root -cne $root -or
        [string]$preflight.repository_revision -cne $ExpectedRevision -or
        (@($preflight.serials) -join "`n") -cne ($serials -join "`n") -or
        @($preflight.apks).Count -ne 3 -or
        -not [bool]$preflight.bounded_logcat_clear_confirmed) {
        throw "Matrix preflight identity/APK/serial closure drifted."
    }
    Assert-RunTimestamp -Value $preflight.observed_at -StartedAt $startedAt -FinishedAt $finishedAt -Label "matrix preflight"
    Assert-FileBinding -Binding $preflight.adb -Label "preflight ADB"
    Assert-FileBinding -Binding $preflight.peer_provider -Label "preflight peer provider" -RejectFixturePath
    if (@($preflight.logcat_clear_evidence).Count -ne 2) {
        throw "Preflight must bind one logcat-clear receipt per explicit serial."
    }
    foreach ($device in $serials) {
        $matches = @($preflight.logcat_clear_evidence | Where-Object {
            (Get-Content -Raw -LiteralPath ([string]$_.path)) -match "(?m)^serial=$([regex]::Escape($device))$"
        })
        if ($matches.Count -ne 1) { throw "Preflight logcat-clear closure is missing $device." }
        Assert-FileBinding -Binding $matches[0] -Label "$device preflight logcat clear" -RejectFixturePath -AllowedRoot $root
        $clearText = Get-Content -Raw -LiteralPath ([string]$matches[0].path)
        if ($clearText -notmatch "(?m)^run_id=$([regex]::Escape([string]$matrix.run_id))$" -or
            $clearText -notmatch '(?m)^logcat_clear=applied$') {
            throw "$device preflight logcat-clear evidence belongs to another run or is incomplete."
        }
    }
    $expectedPackages = @(
        "io.github.mesmerprism.rustymanifold.broker",
        "io.github.mesmerprism.rustyquest.native_renderer",
        "io.github.mesmerprism.rustyquest.spatial_camera_panel"
    )
    if ((@($preflight.apks | ForEach-Object { [string]$_.package_name }) -join "`n") -cne ($expectedPackages -join "`n")) {
        throw "Preflight APK package closure drifted."
    }
    foreach ($binding in @($preflight.apks)) {
        Assert-FileBinding -Binding $binding -Label "preflight APK" -RejectFixturePath
    }
    foreach ($row in @($matrix.rows)) {
        Assert-CriterionReceiptClosure -Row $row -ExpectedRevision $ExpectedRevision -ExpectedRunId ([string]$matrix.run_id) -ExpectedRoot $root -StartedAt $startedAt -FinishedAt $finishedAt -ExpectedPreflightSha256 ([string]$matrix.preflight.sha256)
    }
    Assert-FinallyCleanupClosure -Binding $matrix.finally_cleanup -ExpectedSerials $serials -ExpectedRevision $ExpectedRevision -ExpectedRunId ([string]$matrix.run_id) -ExpectedRoot $root -StartedAt $startedAt -FinishedAt $finishedAt
    return [pscustomobject][ordered]@{
        matrix_path = (Resolve-Path -LiteralPath $MatrixPathValue).Path
        matrix_sha256 = Get-FileSha256 -Path (Resolve-Path -LiteralPath $MatrixPathValue).Path
        run_id = [string]$matrix.run_id
        repository_revision = $ExpectedRevision
        serials = $serials
        evidence_root = $root
        started_at = $startedAt.ToString("o")
        finished_at = $finishedAt.ToString("o")
        row_count = @($matrix.rows).Count
    }
}

function Invoke-Validate {
    if ([string]::IsNullOrWhiteSpace($MatrixPath)) { throw "Validate requires -MatrixPath." }
    $matrix = Read-JsonFile -Path $MatrixPath -Label "corrected release device matrix"
    $currentRevision = Get-CleanRustyQuestRevision
    $closure = Assert-DeviceMatrixClosure -Matrix $matrix -MatrixPathValue $MatrixPath -ExpectedRevision $currentRevision
    Write-Output ([string]$closure.matrix_path)
}

function Invoke-ReplayValidate {
    if ([string]::IsNullOrWhiteSpace($MatrixPath)) { throw "ReplayValidate requires -MatrixPath." }
    if ([string]::IsNullOrWhiteSpace($OutputPath)) { throw "ReplayValidate requires -OutputPath for the replay receipt." }
    $matrix = Read-JsonFile -Path $MatrixPath -Label "corrected release device matrix"
    $matrixRevision = ([string]$matrix.repository_revision).ToLowerInvariant()
    Assert-Revision -Revision $matrixRevision -Label "matrix repository revision"
    $currentRevision = Get-CleanRustyQuestRevision
    if ($currentRevision -ceq $matrixRevision) {
        throw "ReplayValidate is only for validator-only recovery after the current revision advances beyond the matrix revision; use Validate for exact-source matrices."
    }
    & git -C $script:RepoRoot merge-base --is-ancestor $matrixRevision $currentRevision
    if ($LASTEXITCODE -ne 0) {
        throw "ReplayValidate requires the current revision to descend from the matrix revision."
    }
    $changedPaths = @(& git -C $script:RepoRoot diff --name-only $matrixRevision $currentRevision)
    if ($LASTEXITCODE -ne 0) { throw "ReplayValidate could not inspect changed paths between matrix and current revisions." }
    $allowedChangedPaths = @(
        "tools/Invoke-CorrectedReleaseTwoQuestMatrix.ps1",
        "tools/checks/Test-CorrectedReleaseTwoQuestMatrixStatic.ps1",
        "docs/CORRECTED_RELEASE_TWO_QUEST_MATRIX.md"
    )
    $unexpectedChangedPaths = @($changedPaths | Where-Object { $allowedChangedPaths -notcontains [string]$_ })
    if ($unexpectedChangedPaths.Count -ne 0) {
        throw "ReplayValidate allows only validator/reducer script changes after matrix revision; unexpected paths: $($unexpectedChangedPaths -join ', ')"
    }
    $closure = Assert-DeviceMatrixClosure -Matrix $matrix -MatrixPathValue $MatrixPath -ExpectedRevision $matrixRevision
    $receipt = [ordered]@{
        schema = "rusty.quest.corrected_release_replay_validation.v1"
        status = "pass"
        validation_kind = "reducer_only_replay"
        repository = "rusty-quest"
        matrix_repository_revision = $matrixRevision
        validator_repository_revision = $currentRevision
        changed_paths_since_matrix_revision = $changedPaths
        allowed_changed_paths = $allowedChangedPaths
        matrix = [ordered]@{
            path = [string]$closure.matrix_path
            sha256 = [string]$closure.matrix_sha256
            run_id = [string]$closure.run_id
            evidence_root = [string]$closure.evidence_root
            started_at = [string]$closure.started_at
            finished_at = [string]$closure.finished_at
            row_count = [int]$closure.row_count
            serials = @($closure.serials)
        }
        validator_script = New-FileBinding -Path (Join-Path $script:RepoRoot "tools\Invoke-CorrectedReleaseTwoQuestMatrix.ps1")
        observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        does_not_prove = @(
            "a fresh device run at validator_repository_revision",
            "runtime/APK behavior after matrix_repository_revision",
            "central REL-003 acceptance or release publication by itself"
        )
    }
    Write-JsonFile -Path $OutputPath -Value $receipt
    Write-Output (Resolve-Path -LiteralPath $OutputPath).Path
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Label)
    $threw = $false
    try { & $Action } catch { $threw = $true }
    if (-not $threw) { throw "Self-test damaged case was accepted: $Label" }
}

function Remove-JsonPropertyPath {
    param($Value, [string]$Path)
    $parts = @($Path -split '\.')
    if ($parts.Count -lt 1) {
        throw "Self-test property path is empty."
    }
    $cursor = $Value
    for ($index = 0; $index -lt ($parts.Count - 1); $index++) {
        $part = $parts[$index]
        if ($part -match '^\d+$') {
            $cursor = @($cursor)[[int]$part]
        } else {
            $cursor = $cursor.$part
        }
        if ($null -eq $cursor) {
            throw "Self-test property path does not exist before '$part': $Path"
        }
    }
    $leaf = $parts[-1]
    if ($null -eq $cursor.PSObject.Properties[$leaf]) {
        throw "Self-test property path does not exist: $Path"
    }
    $cursor.PSObject.Properties.Remove($leaf)
}

function New-SelfTestPeerRow {
    param([string]$Device, [string]$Peer, [string]$Role, [string]$KeyId, [string]$NewKeyId, [string]$PublicKey, $Binding, [string]$Revision)
    return [pscustomobject][ordered]@{
        serial = $Device
        status = "pass"
        repository_revision = $Revision
        operator_enrollment = [pscustomobject]@{ status = "accepted"; operator_id = "operator.selftest"; receipt = $Binding }
        device_identity = [pscustomobject]@{ generation = "on-device"; key_id = $KeyId; public_key_ed25519_base64 = $PublicKey; receipt = $Binding }
        reciprocal_signed_evidence = [pscustomobject]@{ status = "accepted"; peer_serial = $Peer; local_signature_valid = $true; peer_signature_valid = $true; receipt = $Binding }
        revisions = [pscustomobject]@{ enrollment_revision = 7; current_enrollment_revision = 7; rendezvous_revision = 9; current_rendezvous_revision = 9 }
        topology_authorization = [pscustomobject]@{ status = "accepted"; schema = "rusty.manifold.peer.topology_authorization.v1"; current_revision = $true; local_role = $Role; receipt = $Binding }
        direct_lane_lease = [pscustomobject]@{ status = "accepted"; schema = "rusty.manifold.peer.direct_lane_lease.v1"; current_revision = $true; real_platform_lane = $true; lease_id = "lease-$Device"; receipt = $Binding }
        key_rotation = [pscustomobject]@{ status = "accepted"; old_key_rejected = $true; new_key_id = $NewKeyId; receipt = $Binding }
        revocation = [pscustomobject]@{ status = "accepted"; revoked_key_rejected = $true; receipt = $Binding }
        replay = [pscustomobject]@{ status = "rejected"; receipt = $Binding }
        direct_exchange = [pscustomobject]@{ status = "pass"; socket_owner = "rusty-owned"; interface = "p2p0"; explicit_local_bind = $true; sent_bytes = 32; received_bytes = 32; receipt = $Binding }
        route_inactive = $true
        cleanup_complete = $true
        cleanup_packages = @("selftest.package")
        package_fatal_count = 0
        app_fatal_count = 0
        system_fatal_count = 0
        raw_evidence = @($Binding, $Binding, $Binding, $Binding, $Binding, $Binding, $Binding, $Binding, $Binding)
    }
}

function Invoke-SelfTest {
    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("rusty-quest-corrected-release-selftest-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $script:RunId = "corrected-release-$([guid]::NewGuid().ToString('N'))"
    $script:RunStartedAt = [DateTimeOffset]::UtcNow
    $script:EvidenceRoot = $tempRoot
    $script:PreflightSha256 = "1" * 64
    try {
        $julyStart = [DateTimeOffset]::ParseExact("2026-07-11T10:00:00.0000000+00:00", "o", [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
        $julyFinish = $julyStart.AddMinutes(2)
        $jsonDate = ('{"observed_at":"2026-07-11T10:01:00.0000000+00:00"}' | ConvertFrom-Json).observed_at
        Assert-RunTimestamp -Value $jsonDate -StartedAt $julyStart -FinishedAt $julyFinish -Label "July invariant timestamp"
        $novemberDate = ('{"observed_at":"2026-11-07T10:01:00.0000000+00:00"}' | ConvertFrom-Json).observed_at
        Assert-Throws -Label "July/November timestamp transposition" -Action {
            Assert-RunTimestamp -Value $novemberDate -StartedAt $julyStart -FinishedAt $julyFinish -Label "transposed timestamp"
        }
        $rawPath = Join-Path $tempRoot "raw.txt"
        [System.IO.File]::WriteAllText($rawPath, "self-test evidence", $script:Utf8NoBom)
        $binding = New-FileBinding -Path $rawPath
        $revision = "1" * 40
        $serials = @("SELFTESTA", "SELFTESTB")
        $keyA = [Convert]::ToBase64String([byte[]](1..32))
        $keyB = [Convert]::ToBase64String([byte[]](33..64))
        $peer = [pscustomobject][ordered]@{
            schema = $script:PeerAuthoritySchema
            status = "pass"
            evidence_tier = "live_two_quest"
            coordination_mode = "user_authorized_serial_scoped"
            provider_execution = $true
            synthetic = $false
            fixture_only = $false
            device_count = 2
            repository_revision = $revision
            rows = @(
                New-SelfTestPeerRow -Device $serials[0] -Peer $serials[1] -Role "initiator" -KeyId "key-a" -NewKeyId "key-a2" -PublicKey $keyA -Binding $binding -Revision $revision
                New-SelfTestPeerRow -Device $serials[1] -Peer $serials[0] -Role "responder" -KeyId "key-b" -NewKeyId "key-b2" -PublicKey $keyB -Binding $binding -Revision $revision
            )
        }
        Assert-PeerAuthoritySummary -Summary $peer -ExpectedSerials $serials -Revision $revision
        $requiredPeerPaths = @(
            "schema", "status", "evidence_tier", "coordination_mode",
            "provider_execution", "synthetic", "fixture_only", "device_count",
            "repository_revision", "rows",
            "rows.0.serial", "rows.0.status", "rows.0.repository_revision",
            "rows.0.operator_enrollment", "rows.0.device_identity",
            "rows.0.reciprocal_signed_evidence", "rows.0.revisions",
            "rows.0.topology_authorization", "rows.0.direct_lane_lease",
            "rows.0.key_rotation", "rows.0.revocation", "rows.0.replay",
            "rows.0.direct_exchange", "rows.0.route_inactive",
            "rows.0.cleanup_complete", "rows.0.cleanup_packages",
            "rows.0.package_fatal_count", "rows.0.app_fatal_count",
            "rows.0.system_fatal_count", "rows.0.raw_evidence",
            "rows.0.operator_enrollment.status", "rows.0.operator_enrollment.operator_id",
            "rows.0.operator_enrollment.receipt",
            "rows.0.device_identity.generation", "rows.0.device_identity.key_id",
            "rows.0.device_identity.public_key_ed25519_base64", "rows.0.device_identity.receipt",
            "rows.0.reciprocal_signed_evidence.status",
            "rows.0.reciprocal_signed_evidence.peer_serial",
            "rows.0.reciprocal_signed_evidence.local_signature_valid",
            "rows.0.reciprocal_signed_evidence.peer_signature_valid",
            "rows.0.reciprocal_signed_evidence.receipt",
            "rows.0.revisions.enrollment_revision", "rows.0.revisions.current_enrollment_revision",
            "rows.0.revisions.rendezvous_revision", "rows.0.revisions.current_rendezvous_revision",
            "rows.0.topology_authorization.status", "rows.0.topology_authorization.schema",
            "rows.0.topology_authorization.current_revision",
            "rows.0.topology_authorization.local_role", "rows.0.topology_authorization.receipt",
            "rows.0.direct_lane_lease.status", "rows.0.direct_lane_lease.schema",
            "rows.0.direct_lane_lease.current_revision", "rows.0.direct_lane_lease.real_platform_lane",
            "rows.0.direct_lane_lease.lease_id", "rows.0.direct_lane_lease.receipt",
            "rows.0.key_rotation.status", "rows.0.key_rotation.old_key_rejected",
            "rows.0.key_rotation.new_key_id", "rows.0.key_rotation.receipt",
            "rows.0.revocation.status", "rows.0.revocation.revoked_key_rejected",
            "rows.0.revocation.receipt", "rows.0.replay.status", "rows.0.replay.receipt",
            "rows.0.direct_exchange.status", "rows.0.direct_exchange.socket_owner",
            "rows.0.direct_exchange.interface", "rows.0.direct_exchange.explicit_local_bind",
            "rows.0.direct_exchange.sent_bytes", "rows.0.direct_exchange.received_bytes",
            "rows.0.direct_exchange.receipt"
        )
        foreach ($path in $requiredPeerPaths) {
            $missing = $peer | ConvertTo-Json -Depth 30 | ConvertFrom-Json
            Remove-JsonPropertyPath -Value $missing -Path $path
            Assert-Throws -Label "missing peer field $path" -Action {
                Assert-PeerAuthoritySummary -Summary $missing -ExpectedSerials $serials -Revision $revision
            }
        }
        Assert-Throws -Label "implicit serial" -Action { Assert-ExplicitSerials -Values @() | Out-Null }
        Assert-Throws -Label "duplicate serial" -Action { Assert-ExplicitSerials -Values @("A", "A") | Out-Null }
        Assert-Throws -Label "missing mandatory peer provider" -Action { Assert-MandatoryPeerProvider -Path (Join-Path $tempRoot "Invoke-ManifoldPeerAuthorityTwoQuest.ps1") | Out-Null }
        $damagedPeer = $peer | ConvertTo-Json -Depth 30 | ConvertFrom-Json
        $damagedPeer.rows[0].direct_lane_lease = $null
        Assert-Throws -Label "missing direct-lane lease" -Action { Assert-PeerAuthoritySummary -Summary $damagedPeer -ExpectedSerials $serials -Revision $revision }
        $damagedPeer = $peer | ConvertTo-Json -Depth 30 | ConvertFrom-Json
        $damagedPeer.rows[0].replay.status = "accepted"
        Assert-Throws -Label "accepted replay" -Action { Assert-PeerAuthoritySummary -Summary $damagedPeer -ExpectedSerials $serials -Revision $revision }
        $badBinding = $binding | ConvertTo-Json | ConvertFrom-Json
        $badBinding.sha256 = "0" * 64
        Assert-Throws -Label "evidence hash mismatch" -Action { Assert-FileBinding -Binding $badBinding -Label "damaged binding" }

        $cleanupPath = Join-Path $tempRoot "cleanup.json"
        $cleanupReceipt = [ordered]@{
            schema = $script:CleanupSchema
            status = "pass"
            cleanup_complete = $true
            package_fatal_count = 0
            app_fatal_count = 0
            system_fatal_count = 0
        }
        Write-JsonFile -Path $cleanupPath -Value $cleanupReceipt
        $cleanup = [pscustomobject]@{ path = $cleanupPath; receipt = (Read-JsonFile -Path $cleanupPath -Label "self-test cleanup") }
        $rows = @()
        foreach ($device in $serials) {
            $deviceDir = Join-Path $tempRoot $device
            foreach ($criterion in $script:RequiredCriteria) {
                $source = [pscustomobject]@{
                    run_id = $script:RunId
                    observed_at = [DateTimeOffset]::UtcNow.ToString("o")
                    serial = $device
                    criterion_id = $criterion
                    status = "pass"
                    device_execution = $true
                    synthetic = $false
                    fixture_only = $false
                    supported = $true
                    raw_paths = @($rawPath)
                    package_fatal_count = 0
                    app_fatal_count = 0
                    system_fatal_count = 0
                    details = [pscustomobject]@{ self_test = $true }
                }
                $rows += Write-CriterionReceipt -Source $source -Cleanup $cleanup -Revision $revision -Directory $deviceDir -AllowSynthetic
            }
        }
        $matrix = [pscustomobject][ordered]@{ schema = $script:MatrixSchema; status = "pass"; rows = $rows }
        Assert-ReleaseMatrix -Matrix $matrix -ExpectedSerials $serials
        $damaged = $matrix | ConvertTo-Json -Depth 30 | ConvertFrom-Json
        $damaged.rows[0].cleanup_complete = $false
        Assert-Throws -Label "cleanup false" -Action { Assert-ReleaseMatrix -Matrix $damaged -ExpectedSerials $serials }
        $damaged = $matrix | ConvertTo-Json -Depth 30 | ConvertFrom-Json
        $damaged.rows[0].system_fatal_count = 1
        Assert-Throws -Label "fatal count" -Action { Assert-ReleaseMatrix -Matrix $damaged -ExpectedSerials $serials }
        $damaged = $matrix | ConvertTo-Json -Depth 30 | ConvertFrom-Json
        $damaged.rows[0].criterion_id = "unknown"
        Assert-Throws -Label "unknown/missing criterion" -Action { Assert-ReleaseMatrix -Matrix $damaged -ExpectedSerials $serials }
        $syntheticSource = [pscustomobject]@{
            serial = $serials[0]; criterion_id = "module_lock_selected"; status = "pass"; device_execution = $true
            synthetic = $true; fixture_only = $false; supported = $true; raw_paths = @($rawPath)
            package_fatal_count = 0; app_fatal_count = 0; system_fatal_count = 0; details = [pscustomobject]@{}
        }
        Assert-Throws -Label "synthetic production source" -Action {
            Write-CriterionReceipt -Source $syntheticSource -Cleanup $cleanup -Revision $revision -Directory $tempRoot | Out-Null
        }
        Write-Output "[PASS] corrected release two-Quest matrix damaged self-test"
    } finally {
        $resolvedTemp = [System.IO.Path]::GetFullPath($tempRoot)
        $resolvedBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        if ($resolvedTemp.StartsWith($resolvedBase, [StringComparison]::OrdinalIgnoreCase) -and [System.IO.Directory]::Exists($resolvedTemp)) {
            [System.IO.Directory]::Delete($resolvedTemp, $true)
        }
    }
}

function Invoke-Execute {
    $requestedSerials = Assert-ExplicitSerials -Values $Serial
    if (-not $ConfirmBoundedLogcatClear) {
        throw "Execute requires -ConfirmBoundedLogcatClear so each explicitly scoped release window is intentionally bounded."
    }
    if ($RunSeconds -lt 1) {
        throw "RunSeconds must be positive."
    }
    $providerPath = Assert-MandatoryPeerProvider -Path (Join-Path $PSScriptRoot "Invoke-ManifoldPeerAuthorityTwoQuest.ps1")
    $script:AdbPath = Resolve-RequiredFile -Path $Adb -Label "ADB executable"
    $serials = Resolve-LogicalSerialsFromAdbTransports -Transports $requestedSerials
    $script:BrokerApkPath = Resolve-RequiredFile -Path $(if ([string]::IsNullOrWhiteSpace($BrokerApk)) { Join-Path $script:RepoRoot "target\manifold-broker-android\rusty-manifold-broker.apk" } else { $BrokerApk }) -Label "built Manifold broker APK"
    $script:NativeApkPath = Resolve-RequiredFile -Path $(if ([string]::IsNullOrWhiteSpace($NativeApk)) { Join-Path $script:RepoRoot "target\native-renderer-android\rusty-quest-native-renderer.apk" } else { $NativeApk }) -Label "built Native Renderer APK"
    $script:SpatialApkPath = Resolve-RequiredFile -Path $(if ([string]::IsNullOrWhiteSpace($SpatialApk)) { Join-Path $script:RepoRoot "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk" } else { $SpatialApk }) -Label "built Spatial Camera Panel APK"
    foreach ($suite in @(
        "Apply-RuntimeProfile.ps1",
        "Invoke-NativeRendererReplaySmoke.ps1",
        "Invoke-SpatialCameraPanelAndroidParticleVisualSmoke.ps1",
        "Test-QuestParticleAdapterEvidence.ps1",
        "Invoke-MultiAppBrokerClientTwoQuest.ps1",
        "Invoke-SpatialCameraPanelAndroidCameraHwbProjectionSmoke.ps1",
        "Invoke-NativeRendererDisplayCompositeSmoke.ps1"
    )) {
        Resolve-RequiredFile -Path (Join-Path $PSScriptRoot $suite) -Label "required serial-scoped suite $suite" | Out-Null
    }
    $revision = (& git -C $script:RepoRoot rev-parse HEAD).Trim().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0) { throw "Unable to resolve Rusty Quest repository revision." }
    Assert-Revision -Revision $revision -Label "Rusty Quest revision"
    $dirty = @(& git -C $script:RepoRoot status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) {
        throw "Corrected release device evidence requires a clean exact Rusty Quest tree."
    }
    if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
        $EvidenceDir = Join-Path "S:\Work\tmp" ("rusty-quest-corrected-release-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
    }
    if (Test-Path -LiteralPath $EvidenceDir -PathType Container) {
        if (@(Get-ChildItem -LiteralPath $EvidenceDir -Force).Count -ne 0) {
            throw "Execute requires a new or empty runner-owned EvidenceDir."
        }
    } else {
        New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
    }
    $EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
    $script:RunId = "corrected-release-$([guid]::NewGuid().ToString('N'))"
    $script:RunStartedAt = [DateTimeOffset]::UtcNow
    $script:EvidenceRoot = $EvidenceDir
    if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        $OutputPath = Join-Path $EvidenceDir "corrected-release-device-matrix.json"
    }
    $OutputPath = [IO.Path]::GetFullPath($OutputPath)
    $rootPrefix = $EvidenceDir.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $OutputPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputPath must stay inside the runner-owned EvidenceDir."
    }
    $preflightPath = Join-Path $EvidenceDir "preflight.json"
    $runError = $null
    $emergencyCleanup = @()
    $pendingMatrix = $null
    $finallyPath = Join-Path $EvidenceDir "finally-cleanup-summary.json"
    try {
      $clearEvidence = @()
      foreach ($device in $serials) {
        $state = Invoke-SerialAdb -Device $device -Arguments @("get-state")
        if ($state.output.Trim() -cne "device") {
            throw "Quest $device is not ready."
        }
        Invoke-SerialAdb -Device $device -Arguments @("logcat", "-c") | Out-Null
        $clearPath = Join-Path $EvidenceDir "$device\preflight-logcat-clear.txt"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $clearPath) | Out-Null
        [IO.File]::WriteAllText($clearPath, "run_id=$($script:RunId)`nserial=$device`nlogcat_clear=applied`n", $script:Utf8NoBom)
        $clearEvidence += New-FileBinding -Path $clearPath
    }

    $brokerApk = New-FileBinding -Path $script:BrokerApkPath
    $brokerApk | Add-Member -NotePropertyName package_name -NotePropertyValue "io.github.mesmerprism.rustymanifold.broker"
    $nativeApk = New-FileBinding -Path $script:NativeApkPath
    $nativeApk | Add-Member -NotePropertyName package_name -NotePropertyValue "io.github.mesmerprism.rustyquest.native_renderer"
    $spatialApk = New-FileBinding -Path $script:SpatialApkPath
    $spatialApk | Add-Member -NotePropertyName package_name -NotePropertyValue "io.github.mesmerprism.rustyquest.spatial_camera_panel"
    $preflight = [ordered]@{
        schema = "rusty.quest.corrected_release_two_quest_preflight.v1"
        status = "pass"
        run_id = $script:RunId
        observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        evidence_root = $script:EvidenceRoot
        repository_revision = $revision
        coordination_mode = "user_authorized_serial_scoped"
        serials = $serials
        adb_transports = $requestedSerials
        adb = New-FileBinding -Path $script:AdbPath
        apks = @($brokerApk, $nativeApk, $spatialApk)
        peer_provider = New-FileBinding -Path $providerPath
        logcat_clear_evidence = $clearEvidence
        bounded_logcat_clear_confirmed = $true
        shutdown_performed = $false
    }
    Write-JsonFile -Path $preflightPath -Value $preflight
    $script:PreflightSha256 = (Get-FileSha256 -Path $preflightPath)

    $sources = @()
    $profiles = New-OffLockProfiles -Directory (Join-Path $EvidenceDir "generated-off-lock-profiles")
    foreach ($device in $serials) {
        $sources += Invoke-ModuleLockSelected -Device $device -Directory (Join-Path $EvidenceDir "$device\module-lock-selected")
        $sources += Invoke-ModuleLockOffLock -Device $device -Directory (Join-Path $EvidenceDir "$device\module-lock-off-lock") -Profiles $profiles
    }
    $sources += Invoke-BrokerLifecycle -ExpectedSerials $serials -Directory (Join-Path $EvidenceDir "broker-lifecycle")
    $peerResult = Invoke-PeerAuthority -ExpectedSerials $serials -Directory (Join-Path $EvidenceDir "peer-authority") -Revision $revision -Provider $providerPath
    $sources += @($peerResult.sources)
    foreach ($device in $serials) {
        $sources += Invoke-Camera2Conformance -Device $device -Directory (Join-Path $EvidenceDir "$device\media-camera2")
        $sources += Invoke-DisplayCompositeConformance -Device $device -Directory (Join-Path $EvidenceDir "$device\media-display-composite")
    }

    $cleanupBySerial = @{}
    foreach ($device in $serials) {
        $peerRow = @($peerResult.summary.rows | Where-Object { [string]$_.serial -ceq $device })[0]
        $deviceSources = @($sources | Where-Object { [string]$_.serial -ceq $device })
        $cleanupBySerial[$device] = Invoke-FinalCleanup `
            -Device $device `
            -Directory (Join-Path $EvidenceDir "$device\final-cleanup") `
            -Revision $revision `
            -PeerRow $peerRow `
            -DeviceSources $deviceSources
        $cleanupSource = New-LiveSource `
            -SerialValue $device `
            -CriterionId "cleanup" `
            -RawPaths @($cleanupBySerial[$device].path, $peerResult.summary_path) `
            -FatalCounts $cleanupBySerial[$device].receipt `
            -Details ([pscustomobject]@{ packages_remaining = 0; route_inactive = $true })
        $fatalSource = New-LiveSource `
            -SerialValue $device `
            -CriterionId "bounded_fatal" `
            -RawPaths @($cleanupBySerial[$device].fatal_paths) `
            -FatalCounts $cleanupBySerial[$device].receipt `
            -Details ([pscustomobject]@{ bounded_window = $true; fatal_filters = @("package", "app", "system") })
        $sources += $cleanupSource
        $sources += $fatalSource
    }

    $rows = @()
    foreach ($device in $serials) {
        $criterionDir = Join-Path $EvidenceDir "$device\criteria"
        foreach ($criterion in $script:RequiredCriteria) {
            $matches = @($sources | Where-Object { [string]$_.serial -ceq $device -and [string]$_.criterion_id -ceq $criterion })
            if ($matches.Count -ne 1) {
                throw "Source reduction requires exactly one $device/$criterion result."
            }
            $rows += Write-CriterionReceipt `
                -Source $matches[0] `
                -Cleanup $cleanupBySerial[$device] `
                -Revision $revision `
                -Directory $criterionDir
        }
    }
    $pendingMatrix = [ordered]@{
        schema = $script:MatrixSchema
        status = "pass"
        run_id = $script:RunId
        evidence_root = $script:EvidenceRoot
        started_at = $script:RunStartedAt.ToString("o")
        repository = "rusty-quest"
        repository_revision = $revision
        coordination_mode = "user_authorized_serial_scoped"
        evidence_tier = "live_two_quest"
        serials = $serials
        preflight = New-FileBinding -Path $preflightPath
        rows = $rows
    }
    } catch {
        $runError = $_
        $failedPath = Join-Path $EvidenceDir "failed-or-interrupted-summary.json"
        Write-JsonFile -Path $failedPath -Value ([ordered]@{
            schema = "rusty.quest.corrected_release_interrupted_summary.v1"
            status = "failed"
            run_id = $script:RunId
            repository_revision = $revision
            serials = $serials
            error = [string]$_.Exception.Message
            observed_at = [DateTimeOffset]::UtcNow.ToString("o")
            cleanup_pending = $true
        })
    } finally {
        foreach ($device in $serials) {
            try {
                $binding = Invoke-EmergencyCleanup `
                    -Device $device `
                    -Directory (Join-Path $EvidenceDir "$device\emergency-finally-cleanup") `
                    -Revision $revision
                $emergencyCleanup += [pscustomobject][ordered]@{ serial = $device; receipt = $binding }
            } catch {
                $emergencyCleanup += [pscustomobject]@{
                    serial = $device
                    error = [string]$_.Exception.Message
                }
            }
        }
        $finallyComplete = @($emergencyCleanup).Count -eq 2
        foreach ($entry in $emergencyCleanup) {
            if ($entry.PSObject.Properties.Name -contains "error" -or $null -eq $entry.receipt) {
                $finallyComplete = $false
                continue
            }
            try {
                $cleanupReceipt = Read-JsonFile -Path ([string]$entry.receipt.path) -Label "$($entry.serial) emergency cleanup"
                Assert-FileBinding -Binding $entry.receipt -Label "$($entry.serial) emergency cleanup" -AllowedRoot $script:EvidenceRoot
                Assert-ZeroFatals -Counts $cleanupReceipt -Label "$($entry.serial) emergency cleanup"
                if ([string]$cleanupReceipt.status -cne "pass" -or -not [bool]$cleanupReceipt.cleanup_complete -or
                    -not [bool]$cleanupReceipt.peer_route_inactive -or [string]$cleanupReceipt.run_id -cne $script:RunId) {
                    $finallyComplete = $false
                }
            } catch {
                $finallyComplete = $false
            }
        }
        Write-JsonFile -Path $finallyPath -Value ([ordered]@{
            schema = "rusty.quest.corrected_release_finally_cleanup_summary.v1"
            status = if ($finallyComplete) { "completed" } else { "incomplete" }
            run_id = $script:RunId
            repository_revision = $revision
            serials = $serials
            cleanup = $emergencyCleanup
            original_run_failed = $null -ne $runError
            observed_at = [DateTimeOffset]::UtcNow.ToString("o")
        })
        if (-not $finallyComplete -and $null -eq $runError) {
            $runError = [System.Management.Automation.RuntimeException]::new("Emergency finally cleanup was incomplete or contained fatal evidence.")
        }
    }
    if ($null -ne $runError) { throw $runError }
    if ($null -eq $pendingMatrix) { throw "No passing matrix candidate was produced." }
    $pendingMatrix.finally_cleanup = New-FileBinding -Path $finallyPath
    $pendingMatrix.finished_at = [DateTimeOffset]::UtcNow.ToString("o")
    $pendingMatrix.generated_at = $pendingMatrix.finished_at
    Assert-ReleaseMatrix -Matrix $pendingMatrix -ExpectedSerials $serials -ExpectedRunId $script:RunId
    $finishedAt = ConvertTo-InvariantTimestamp -Value $pendingMatrix.finished_at -Label "matrix finished_at"
    Assert-FinallyCleanupClosure -Binding $pendingMatrix.finally_cleanup -ExpectedSerials $serials -ExpectedRevision $revision -ExpectedRunId $script:RunId -ExpectedRoot $script:EvidenceRoot -StartedAt $script:RunStartedAt -FinishedAt $finishedAt
    Write-JsonFile -Path $OutputPath -Value $pendingMatrix
    Write-Output (Resolve-Path -LiteralPath $OutputPath).Path
}

switch ($Mode) {
    "SelfTest" { Invoke-SelfTest }
    "ReplayValidate" { Invoke-ReplayValidate }
    "Validate" { Invoke-Validate }
    "Execute" { Invoke-Execute }
}
