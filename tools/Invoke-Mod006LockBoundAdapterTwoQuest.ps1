param(
    [Parameter(Mandatory=$true)][string]$NativeSerial,
    [Parameter(Mandatory=$true)][string]$SpatialSerial,
    [string]$RepoRoot,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$NativeApkPath = "target\mod006-native-source-build-final\rusty-quest-native-renderer.apk",
    [string]$SpatialApkPath = "target\mod006-spatial-source-build-final\rusty-quest-spatial-camera-panel.apk",
    [string]$SpatialHandApkPath = "target\mod006-spatial-hand-source-build-final\rusty-quest-spatial-hand-lab.apk",
    [string]$OutDir = "",
    [int]$RunSeconds = 8,
    [switch]$PreflightOnly,
    [switch]$SkipInstall,
    [switch]$KeepPackages
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($Adb)) {
    $Adb = "adb"
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $RepoRoot "target\mod006-twoquest-$stamp"
}

$NativePackage = "io.github.mesmerprism.rustyquest.native_renderer"
$NativeActivity = "$NativePackage/android.app.NativeActivity"
$SpatialPackage = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
$SpatialActivity = "$SpatialPackage/io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity"
$SpatialHandPackage = "io.github.mesmerprism.rustyquest.spatial_hand_lab"
$SpatialHandActivity = "$SpatialHandPackage/io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity"
$SurfaceTargetAction = "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_SURFACE_TARGET"

$NativeParticleProfile = Join-Path $RepoRoot "fixtures\runtime-profiles\quest-native-renderer-particle-adapter-conformance.profile.json"
$NativeHandProfile = Join-Path $RepoRoot "fixtures\runtime-profiles\quest-native-renderer-hand-adapter-conformance.profile.json"
$SpatialParticleProfile = Join-Path $RepoRoot "fixtures\runtime-profiles\quest-spatial-camera-panel-particle-adapter-conformance.profile.json"
$SpatialHandProfile = Join-Path $RepoRoot "fixtures\runtime-profiles\quest-spatial-camera-panel-hand-adapter-conformance.profile.json"
$ApplyRuntimeProfile = Join-Path $RepoRoot "tools\Apply-RuntimeProfile.ps1"
$ParticleEvidence = Join-Path $RepoRoot "tools\Test-QuestParticleAdapterEvidence.ps1"
$HandEvidence = Join-Path $RepoRoot "tools\Test-QuestHandAdapterEvidence.ps1"

function Resolve-RepoPath {
    param([Parameter(Mandatory=$true)][string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $output = & $Adb @("-s", $Serial) @Arguments 2>&1
    $exit = $LASTEXITCODE
    if ($exit -ne 0 -and -not $AllowFailure) {
        throw "adb -s $Serial $($Arguments -join ' ') failed with exit code $exit`n$($output -join [Environment]::NewLine)"
    }
    return [pscustomobject]@{
        exit_code = $exit
        output = ($output -join [Environment]::NewLine)
    }
}

function Save-Text {
    param([Parameter(Mandatory=$true)][string]$Path, [AllowNull()][string]$Text)
    if ($null -eq $Text) { $Text = "" }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.Encoding]::UTF8)
}

function Invoke-Profile {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$Profile,
        [Parameter(Mandatory=$true)][string]$Out
    )
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $ApplyRuntimeProfile `
        -ProfilePath $Profile `
        -Execute `
        -Serial $Serial `
        -Adb $Adb `
        -Out $Out | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Runtime profile failed: $Profile"
    }
}

function Set-DeviceProperty {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    Invoke-Adb -Serial $Serial -Arguments @("shell", "setprop '$Name' '$Value'") | Out-Null
    $readback = Invoke-Adb -Serial $Serial -Arguments @("shell", "getprop '$Name'")
    if ($readback.output.Trim() -ne $Value.Trim()) {
        throw "Property readback mismatch on $Serial for $Name"
    }
}

function Clear-ProfileProperties {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string[]]$Names
    )
    foreach ($name in $Names) {
        Invoke-Adb -Serial $Serial -Arguments @("shell", "setprop '$name' ' '") -AllowFailure | Out-Null
    }
}

function Start-Native {
    param([Parameter(Mandatory=$true)][string]$Serial)
    Invoke-Adb -Serial $Serial -Arguments @("shell", "am", "start", "-W", "-n", $NativeActivity) | Out-Null
}

function Start-SpatialSurface {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$Activity,
        [Parameter(Mandatory=$true)][string]$Target,
        [Parameter(Mandatory=$true)][string]$Label
    )
    Invoke-Adb -Serial $Serial -Arguments @(
        "shell", "am", "start", "-W", "-n", $Activity, "-a", $SurfaceTargetAction,
        "--es", "participant_id", "mod006",
        "--es", "surface_target_id", $Target,
        "--es", "run_label", $Label,
        "--es", "operator_id", "codex",
        "--es", "notes", "mod006-lock-bound-adapter-gate"
    ) | Out-Null
}

function Capture-Logcat {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$Path
    )
    $dump = Invoke-Adb -Serial $Serial -Arguments @("logcat", "-d", "-v", "time") -AllowFailure
    Save-Text -Path $Path -Text $dump.output
}

function Clear-Logcat {
    param([Parameter(Mandatory=$true)][string]$Serial)
    Invoke-Adb -Serial $Serial -Arguments @("logcat", "-c") -AllowFailure | Out-Null
}

function Assert-Contains {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    $text = Get-Content -Raw -LiteralPath $Path
    if (-not $text.Contains($Needle)) {
        throw "$Path is missing expected token: $Needle"
    }
}

function Assert-NoFatalLogcat {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Label
    )
    $text = Get-Content -Raw -LiteralPath $Path
    foreach ($needle in @("FATAL EXCEPTION", "Fatal signal", "AndroidRuntime: FATAL")) {
        if ($text.Contains($needle)) {
            throw "$Label logcat contains fatal token: $needle"
        }
    }
}

function Invoke-Run {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true)][scriptblock]$Apply,
        [Parameter(Mandatory=$true)][scriptblock]$Launch,
        [Parameter(Mandatory=$true)][string]$LogPath
    )
    Invoke-Adb -Serial $Serial -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
    & $Apply
    Invoke-Adb -Serial $Serial -Arguments @("shell", "am", "force-stop", $PackageName) -AllowFailure | Out-Null
    Clear-Logcat -Serial $Serial
    & $Launch
    Start-Sleep -Seconds $RunSeconds
    Capture-Logcat -Serial $Serial -Path $LogPath
    Assert-NoFatalLogcat -Path $LogPath -Label $Name
    Save-Text -Path (Join-Path (Split-Path -Parent $LogPath) "$Name.done.txt") -Text "done"
}

$paths = @(
    (Resolve-RepoPath -Path $NativeApkPath),
    (Resolve-RepoPath -Path $SpatialApkPath),
    (Resolve-RepoPath -Path $SpatialHandApkPath),
    $NativeParticleProfile,
    $NativeHandProfile,
    $SpatialParticleProfile,
    $SpatialHandProfile,
    $ApplyRuntimeProfile,
    $ParticleEvidence,
    $HandEvidence
)
foreach ($path in $paths) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required MOD-006 harness input is missing: $path"
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$resolvedNativeApk = Resolve-RepoPath -Path $NativeApkPath
$resolvedSpatialApk = Resolve-RepoPath -Path $SpatialApkPath
$resolvedSpatialHandApk = Resolve-RepoPath -Path $SpatialHandApkPath
$summaryPath = Join-Path $OutDir "summary.json"
$summary = [ordered]@{
    schema = "rusty.quest.mod006_lock_bound_adapter_twoquest.v1"
    status = "preflight"
    native_serial = $NativeSerial
    spatial_serial = $SpatialSerial
    run_seconds = $RunSeconds
    apk_inputs = @(
        [ordered]@{ role = "native"; path = $resolvedNativeApk; sha256 = Get-FileSha256 -Path $resolvedNativeApk },
        [ordered]@{ role = "spatial"; path = $resolvedSpatialApk; sha256 = Get-FileSha256 -Path $resolvedSpatialApk },
        [ordered]@{ role = "spatial-hand"; path = $resolvedSpatialHandApk; sha256 = Get-FileSha256 -Path $resolvedSpatialHandApk }
    )
    reducers = [ordered]@{}
    off_lock = [ordered]@{}
    cleanup = [ordered]@{}
}

if ($PreflightOnly) {
    $summary.status = "preflight-passed"
    $summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -Path $summaryPath
    Write-Output $summaryPath
    return
}

$nativeParticleLog = Join-Path $OutDir "native-particle-selected.logcat.txt"
$spatialParticleLog = Join-Path $OutDir "spatial-particle-selected.logcat.txt"
$nativeHandLog = Join-Path $OutDir "native-hand-selected.logcat.txt"
$spatialHandLog = Join-Path $OutDir "spatial-hand-selected.logcat.txt"
$nativeParticleOffLog = Join-Path $OutDir "native-particle-offlock.logcat.txt"
$spatialParticleOffLog = Join-Path $OutDir "spatial-particle-offlock.logcat.txt"
$nativeHandOffLog = Join-Path $OutDir "native-hand-offlock.logcat.txt"
$spatialHandOffLog = Join-Path $OutDir "spatial-hand-offlock.logcat.txt"

try {
    if (-not $SkipInstall) {
        Invoke-Adb -Serial $NativeSerial -Arguments @("install", "-r", "-d", $resolvedNativeApk) | Out-Null
        Invoke-Adb -Serial $SpatialSerial -Arguments @("install", "-r", "-d", $resolvedSpatialApk) | Out-Null
    }

    Invoke-Run -Name "native-particle-selected" -Serial $NativeSerial -PackageName $NativePackage `
        -Apply { Invoke-Profile -Serial $NativeSerial -Profile $NativeParticleProfile -Out (Join-Path $OutDir "native-particle-profile.json") } `
        -Launch { Start-Native -Serial $NativeSerial } `
        -LogPath $nativeParticleLog

    Invoke-Run -Name "spatial-particle-selected" -Serial $SpatialSerial -PackageName $SpatialPackage `
        -Apply { Invoke-Profile -Serial $SpatialSerial -Profile $SpatialParticleProfile -Out (Join-Path $OutDir "spatial-particle-profile.json") } `
        -Launch { Start-SpatialSurface -Serial $SpatialSerial -Activity $SpatialActivity -Target "icosphere" -Label "mod006-particle-selected" } `
        -LogPath $spatialParticleLog

    $particleOut = Join-Path $OutDir "particle-adapter-scorecard.json"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $ParticleEvidence `
        -NativeRendererLogcatPath $nativeParticleLog `
        -SpatialPanelLogcatPath $spatialParticleLog `
        -Out $particleOut | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Particle adapter reducer failed."
    }
    $summary.reducers.particle = $particleOut

    Invoke-Run -Name "native-particle-offlock" -Serial $NativeSerial -PackageName $NativePackage `
        -Apply {
            Invoke-Profile -Serial $NativeSerial -Profile $NativeParticleProfile -Out (Join-Path $OutDir "native-particle-offlock-profile.json")
            Set-DeviceProperty -Serial $NativeSerial -Name "debug.rustyquest.native_renderer.particle_adapter.lock_sha256" -Value ("0" * 64)
        } `
        -Launch { Start-Native -Serial $NativeSerial } `
        -LogPath $nativeParticleOffLog
    Assert-Contains -Path $nativeParticleOffLog -Needle "RUSTY_QUEST_NATIVE_RENDERER channel=particle-adapter"
    Assert-Contains -Path $nativeParticleOffLog -Needle "status=rejected"
    Assert-Contains -Path $nativeParticleOffLog -Needle "particleAdapterEnabled=false"
    Assert-Contains -Path $nativeParticleOffLog -Needle "activationRejectReason=runtime-digest-mismatch"

    Invoke-Run -Name "spatial-particle-offlock" -Serial $SpatialSerial -PackageName $SpatialPackage `
        -Apply {
            Invoke-Profile -Serial $SpatialSerial -Profile $SpatialParticleProfile -Out (Join-Path $OutDir "spatial-particle-offlock-profile.json")
            Set-DeviceProperty -Serial $SpatialSerial -Name "debug.rustyquest.spatial_camera_panel.particle_adapter.lock_sha256" -Value ("0" * 64)
        } `
        -Launch { Start-SpatialSurface -Serial $SpatialSerial -Activity $SpatialActivity -Target "icosphere" -Label "mod006-particle-offlock" } `
        -LogPath $spatialParticleOffLog
    Assert-Contains -Path $spatialParticleOffLog -Needle "RUSTY_QUEST_SPATIAL_CAMERA_PANEL channel=particle-adapter"
    Assert-Contains -Path $spatialParticleOffLog -Needle "status=rejected"
    Assert-Contains -Path $spatialParticleOffLog -Needle "particleAdapterEnabled=false"
    Assert-Contains -Path $spatialParticleOffLog -Needle "activationRejectReason=runtime-digest-mismatch"
    $summary.off_lock.particle = "passed"

    if (-not $SkipInstall) {
        Invoke-Adb -Serial $NativeSerial -Arguments @("install", "-r", "-d", $resolvedNativeApk) | Out-Null
        Invoke-Adb -Serial $SpatialSerial -Arguments @("install", "-r", "-d", $resolvedSpatialHandApk) | Out-Null
    }

    Invoke-Run -Name "native-hand-selected" -Serial $NativeSerial -PackageName $NativePackage `
        -Apply { Invoke-Profile -Serial $NativeSerial -Profile $NativeHandProfile -Out (Join-Path $OutDir "native-hand-profile.json") } `
        -Launch { Start-Native -Serial $NativeSerial } `
        -LogPath $nativeHandLog

    Invoke-Run -Name "spatial-hand-selected" -Serial $SpatialSerial -PackageName $SpatialHandPackage `
        -Apply { Invoke-Profile -Serial $SpatialSerial -Profile $SpatialHandProfile -Out (Join-Path $OutDir "spatial-hand-profile.json") } `
        -Launch { Start-SpatialSurface -Serial $SpatialSerial -Activity $SpatialHandActivity -Target "real-hands" -Label "mod006-hand-selected" } `
        -LogPath $spatialHandLog

    $handOut = Join-Path $OutDir "hand-adapter-scorecard.json"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $HandEvidence `
        -NativeHandLogcatPath $nativeHandLog `
        -SpatialHandLogcatPath $spatialHandLog `
        -Out $handOut | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Hand adapter reducer failed."
    }
    $summary.reducers.hand = $handOut

    Invoke-Run -Name "native-hand-offlock" -Serial $NativeSerial -PackageName $NativePackage `
        -Apply {
            Invoke-Profile -Serial $NativeSerial -Profile $NativeHandProfile -Out (Join-Path $OutDir "native-hand-offlock-profile.json")
            Set-DeviceProperty -Serial $NativeSerial -Name "debug.rustyquest.native_renderer.hand_adapter.lock_sha256" -Value ("F" * 64)
        } `
        -Launch { Start-Native -Serial $NativeSerial } `
        -LogPath $nativeHandOffLog
    Assert-Contains -Path $nativeHandOffLog -Needle "RUSTY_QUEST_NATIVE_RENDERER channel=hand-adapter"
    Assert-Contains -Path $nativeHandOffLog -Needle "status=rejected"
    Assert-Contains -Path $nativeHandOffLog -Needle "handAdapterEnabled=false"
    Assert-Contains -Path $nativeHandOffLog -Needle "activationRejectReason=runtime-digest-mismatch"

    Invoke-Run -Name "spatial-hand-offlock" -Serial $SpatialSerial -PackageName $SpatialHandPackage `
        -Apply {
            Invoke-Profile -Serial $SpatialSerial -Profile $SpatialHandProfile -Out (Join-Path $OutDir "spatial-hand-offlock-profile.json")
            Set-DeviceProperty -Serial $SpatialSerial -Name "debug.rustyquest.spatial_camera_panel.hand_adapter.lock_sha256" -Value ("F" * 64)
        } `
        -Launch { Start-SpatialSurface -Serial $SpatialSerial -Activity $SpatialHandActivity -Target "real-hands" -Label "mod006-hand-offlock" } `
        -LogPath $spatialHandOffLog
    Assert-Contains -Path $spatialHandOffLog -Needle "RUSTY_QUEST_SPATIAL_CAMERA_PANEL channel=hand-adapter"
    Assert-Contains -Path $spatialHandOffLog -Needle "status=rejected"
    Assert-Contains -Path $spatialHandOffLog -Needle "handAdapterEnabled=false"
    Assert-Contains -Path $spatialHandOffLog -Needle "activationRejectReason=runtime-digest-mismatch"
    $summary.off_lock.hand = "passed"

    $summary.status = "passed"
} finally {
    Invoke-Adb -Serial $NativeSerial -Arguments @("shell", "am", "force-stop", $NativePackage) -AllowFailure | Out-Null
    Invoke-Adb -Serial $SpatialSerial -Arguments @("shell", "am", "force-stop", $SpatialPackage) -AllowFailure | Out-Null
    Invoke-Adb -Serial $SpatialSerial -Arguments @("shell", "am", "force-stop", $SpatialHandPackage) -AllowFailure | Out-Null

    Clear-ProfileProperties -Serial $NativeSerial -Names @(
        "debug.rustyquest.native_renderer.particle_adapter.enabled",
        "debug.rustyquest.native_renderer.particle_adapter.profile_id",
        "debug.rustyquest.native_renderer.particle_adapter.project_id",
        "debug.rustyquest.native_renderer.particle_adapter.feature_id",
        "debug.rustyquest.native_renderer.particle_adapter.lock_revision",
        "debug.rustyquest.native_renderer.particle_adapter.lock_sha256",
        "debug.rustyquest.native_renderer.hand_adapter.enabled",
        "debug.rustyquest.native_renderer.hand_adapter.profile_id",
        "debug.rustyquest.native_renderer.hand_adapter.project_id",
        "debug.rustyquest.native_renderer.hand_adapter.feature_id",
        "debug.rustyquest.native_renderer.hand_adapter.lock_revision",
        "debug.rustyquest.native_renderer.hand_adapter.lock_sha256"
    )
    Clear-ProfileProperties -Serial $SpatialSerial -Names @(
        "debug.rustyquest.spatial.native_surface_particle_layer.enabled",
        "debug.rustyquest.spatial_camera_panel.particle_adapter.profile_id",
        "debug.rustyquest.spatial_camera_panel.particle_adapter.project_id",
        "debug.rustyquest.spatial_camera_panel.particle_adapter.feature_id",
        "debug.rustyquest.spatial_camera_panel.particle_adapter.lock_revision",
        "debug.rustyquest.spatial_camera_panel.particle_adapter.lock_sha256",
        "debug.rustyquest.spatial_camera_panel.hand_adapter.enabled",
        "debug.rustyquest.spatial_camera_panel.hand_adapter.profile_id",
        "debug.rustyquest.spatial_camera_panel.hand_adapter.project_id",
        "debug.rustyquest.spatial_camera_panel.hand_adapter.feature_id",
        "debug.rustyquest.spatial_camera_panel.hand_adapter.lock_revision",
        "debug.rustyquest.spatial_camera_panel.hand_adapter.lock_sha256"
    )

    if (-not $KeepPackages) {
        $nativeUninstall = Invoke-Adb -Serial $NativeSerial -Arguments @("uninstall", $NativePackage) -AllowFailure
        $spatialUninstall = Invoke-Adb -Serial $SpatialSerial -Arguments @("uninstall", $SpatialPackage) -AllowFailure
        $spatialHandUninstall = Invoke-Adb -Serial $SpatialSerial -Arguments @("uninstall", $SpatialHandPackage) -AllowFailure
        $summary.cleanup.native_uninstall_exit_code = $nativeUninstall.exit_code
        $summary.cleanup.spatial_uninstall_exit_code = $spatialUninstall.exit_code
        $summary.cleanup.spatial_hand_uninstall_exit_code = $spatialHandUninstall.exit_code
    }

    $summary.completed_at = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -Path $summaryPath
}

Write-Output $summaryPath
