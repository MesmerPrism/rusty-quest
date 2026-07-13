param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
} else {
    $RepoRoot = Resolve-Path $RepoRoot
}

$profileDir = Join-Path $RepoRoot "fixtures\runtime-profiles"
$damagedProfileDir = Join-Path $RepoRoot "fixtures\damaged"
$runtimeProfileToolPath = Join-Path $RepoRoot "tools\Apply-RuntimeProfile.ps1"
$checkAllPath = Join-Path $RepoRoot "tools\check_all.ps1"
$artifactDir = Join-Path $RepoRoot "local-artifacts"

foreach ($path in @($profileDir, $damagedProfileDir, $runtimeProfileToolPath, $checkAllPath)) {
    if (-not (Test-Path $path)) {
        throw "Missing native renderer profile matrix input: $path"
    }
}

$parseTokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($runtimeProfileToolPath, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    throw "Native renderer runtime profile tool has PowerShell parse errors: $($parseErrors[0].Message)"
}

$checkAllText = Get-Content -Raw -Path $checkAllPath
if ($checkAllText -notmatch 'Test-NativeRendererProfileMatrix\.ps1') {
    throw "check_all.ps1 must delegate the native renderer runtime profile matrix to Test-NativeRendererProfileMatrix.ps1."
}

$expectedProfileNames = @(
    "quest-native-renderer-breathing-room-pmb-scale.profile.json",
    "quest-native-renderer-broker-rmanvid1-stereo-camera.profile.json",
    "quest-native-renderer-display-composite-capture-only.profile.json",
    "quest-native-renderer-display-composite-feedback.profile.json",
    "quest-native-renderer-direct-hwb-1280x960.profile.json",
    "quest-native-renderer-direct-hwb-camera-quality-bt601-unorm.profile.json",
    "quest-native-renderer-direct-hwb-camera-quality.profile.json",
    "quest-native-renderer-direct-hwb-hold-sync-reader6.profile.json",
    "quest-native-renderer-direct-hwb-hold-sync-reader8.profile.json",
    "quest-native-renderer-direct-hwb-hold-sync.profile.json",
    "quest-native-renderer-direct-hwb-low-latency-60.profile.json",
    "quest-native-renderer-direct-hwb-low-noise-30.profile.json",
    "quest-native-renderer-direct-hwb-low-noise-record-30.profile.json",
    "quest-native-renderer-envdepth-capacity-65536.profile.json",
    "quest-native-renderer-envdepth-global-surfaces.profile.json",
    "quest-native-renderer-envdepth-hand-removal.profile.json",
    "quest-native-renderer-envdepth-hybrid-surfaces.profile.json",
    "quest-native-renderer-envdepth-layer0.profile.json",
    "quest-native-renderer-envdepth-layer1.profile.json",
    "quest-native-renderer-envdepth-local-space.profile.json",
    "quest-native-renderer-envdepth-local-surfels.profile.json",
    "quest-native-renderer-envdepth-raw-depth-debug.profile.json",
    "quest-native-renderer-envdepth-source-layer-agreement.profile.json",
    "quest-native-renderer-envdepth-stage-space.profile.json",
    "quest-native-renderer-envdepth-stride-8.profile.json",
    "quest-native-renderer-environment-depth-status.profile.json",
    "quest-native-renderer-fullscreen-stereo-video.profile.json",
    "quest-native-renderer-hand-adapter-conformance.profile.json",
    "quest-native-renderer-hwb-peripheral-stretch.profile.json",
    "quest-native-renderer-hwb-video-border-blend.profile.json",
    "quest-native-renderer-live-hand-anchor-particles.profile.json",
    "quest-native-renderer-live-hand-visual-diagnostic.profile.json",
    "quest-native-renderer-recorded-joint-replay-material.profile.json",
    "quest-native-renderer-native-passthrough-environment-depth-particles.profile.json",
    "quest-native-renderer-native-passthrough-graft-only.profile.json",
    "quest-native-renderer-native-passthrough-hands-and-grafts.profile.json",
    "quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors.profile.json",
    "quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json",
    "quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json",
    "quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json",
    "quest-native-renderer-native-passthrough-style-only.profile.json",
    "quest-native-renderer-native-passthrough-stimulus-volume.profile.json",
    "quest-native-renderer-particle-adapter-conformance.profile.json",
    "quest-native-renderer-replay-visual-proof.profile.json",
    "quest-native-renderer-solid-black-hands-and-grafts.profile.json",
    "quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json",
    "quest-native-renderer-solid-black-stimulus-volume-balanced.profile.json",
    "quest-native-renderer-solid-black-stimulus-volume-performance.profile.json",
    "quest-native-renderer-solid-black-stimulus-volume.profile.json"
)

$expectedDamagedProfileNames = @(
    "native-renderer-breathing-room-makepad-property.profile.json",
    "native-renderer-display-composite-invalid-mode.profile.json",
    "native-renderer-environment-depth-high-rate-json.profile.json",
    "native-renderer-environment-depth-impossible-neighbor-threshold.profile.json",
    "native-renderer-environment-depth-invalid-capacity.profile.json",
    "native-renderer-environment-depth-invalid-depth-units-policy.profile.json",
    "native-renderer-environment-depth-invalid-range.profile.json",
    "native-renderer-environment-depth-invalid-source-layers.profile.json",
    "native-renderer-environment-depth-invalid-surface-support.profile.json",
    "native-renderer-manifest-invalid-camera-output.profile.json",
    "native-renderer-stimulus-volume-invalid-quality-range.profile.json",
    "native-renderer-stimulus-volume-invalid-randomize-range.profile.json",
    "native-renderer-stimulus-volume-missing-safety-ack.profile.json"
)

function Assert-ExactInventory {
    param(
        [string[]]$Actual,
        [string[]]$Expected,
        [string]$Label
    )

    $actualSorted = @($Actual | Sort-Object)
    $expectedSorted = @($Expected | Sort-Object)
    $diff = @(Compare-Object -ReferenceObject $expectedSorted -DifferenceObject $actualSorted)
    if ($diff.Count -gt 0) {
        $summary = ($diff | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }) -join "; "
        throw "Native renderer profile matrix $Label inventory drift: $summary"
    }
}

function Get-ValidationCommandOutPath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Command,
        [Parameter(Mandatory=$true)]
        [string]$ProfileName
    )

    if ($Command -notmatch [regex]::Escape($ProfileName) -or $Command -notmatch '(^|\s)-DryRun(\s|$)') {
        throw "Native renderer profile validation command must dry-run its own profile: $ProfileName"
    }
    if ($Command -notmatch '(^|\s)-Out\s+(?:"([^"]+)"|''([^'']+)''|(\S+))') {
        throw "Native renderer profile validation command must declare an -Out property write plan: $ProfileName"
    }

    foreach ($captureIndex in @(2, 3, 4)) {
        if ($Matches[$captureIndex]) {
            return $Matches[$captureIndex].Replace("/", "\")
        }
    }

    throw "Native renderer profile validation command has an unreadable -Out path: $ProfileName"
}

function Get-NativeRendererProfileCase {
    param(
        [Parameter(Mandatory=$true)]
        [System.IO.FileInfo]$ProfileFile
    )

    $profile = Get-Content -Raw -Path $ProfileFile.FullName | ConvertFrom-Json
    if ($profile.schema -ne "rusty.quest.runtime_profile.v1") {
        throw "Native renderer profile has unexpected schema: $($ProfileFile.Name)"
    }
    if ($profile.profile_id -notmatch '^profile\.quest\.native_renderer\.') {
        throw "Native renderer profile has unexpected profile_id: $($ProfileFile.Name)"
    }
    if (-not $profile.validation_commands -or $profile.validation_commands.Count -lt 1) {
        throw "Native renderer profile missing validation_commands: $($ProfileFile.Name)"
    }

    $command = @($profile.validation_commands) | Select-Object -First 1
    $outPath = Get-ValidationCommandOutPath -Command $command -ProfileName $ProfileFile.Name
    [pscustomobject]@{
        ProfileName = $ProfileFile.Name
        FullPath = $ProfileFile.FullName
        OutPath = $outPath
    }
}

New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null

$profileCases = @(
    Get-ChildItem -Path $profileDir -Filter "quest-native-renderer*.profile.json" |
        Sort-Object Name |
        ForEach-Object { Get-NativeRendererProfileCase -ProfileFile $_ }
)
if ($profileCases.Count -eq 0) {
    throw "No native renderer runtime profiles were found."
}
Assert-ExactInventory `
    -Actual @($profileCases | ForEach-Object { $_.ProfileName }) `
    -Expected $expectedProfileNames `
    -Label "runtime profile"

$damagedProfiles = @(
    Get-ChildItem -Path $damagedProfileDir -Filter "native-renderer*.profile.json" |
        Sort-Object Name
)
if ($damagedProfiles.Count -eq 0) {
    throw "No damaged native renderer runtime profiles were found."
}
Assert-ExactInventory `
    -Actual @($damagedProfiles | ForEach-Object { $_.Name }) `
    -Expected $expectedDamagedProfileNames `
    -Label "damaged profile"

Push-Location $RepoRoot
try {
    foreach ($profileCase in $profileCases) {
        & $runtimeProfileToolPath `
            -ProfilePath $profileCase.FullPath `
            -DryRun `
            -Out $profileCase.OutPath | Out-Null
    }

    foreach ($damagedProfile in $damagedProfiles) {
        $outName = $damagedProfile.Name -replace '\.profile\.json$', '-property-write-plan.json'
        $outPath = Join-Path "local-artifacts" $outName
        try {
            & $runtimeProfileToolPath `
                -ProfilePath $damagedProfile.FullName `
                -DryRun `
                -Out $outPath | Out-Null
            throw "Damaged native renderer runtime profile was accepted: $($damagedProfile.Name)"
        } catch {
            if ($_.Exception.Message -like "Damaged native renderer runtime profile was accepted:*") {
                throw
            }
        }
    }
} finally {
    Pop-Location
}

Write-Output "Rusty Quest native renderer profile matrix validation passed ($($profileCases.Count) profiles, $($damagedProfiles.Count) damaged fixtures)"
