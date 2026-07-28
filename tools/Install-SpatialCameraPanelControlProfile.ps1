[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Serial,

    [Parameter(Mandatory)]
    [string]$ProfilePath,

    [string]$Package = "io.github.mesmerprism.rustyquest.spatial_camera_panel",

    [ValidateRange(1, 60)]
    [int]$TimeoutSeconds = 15,

    [string]$OutPath,

    [string]$Adb = $env:RUSTY_QUEST_ADB
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$expectedSchema = "rusty.quest.spatial_camera_panel.control_profile.v1"
$expectedReceiptSchema = "rusty.quest.spatial_camera_panel.control_profile_apply_receipt.v1"

function Get-DeviceFileSignature {
    param(
        [Parameter(Mandatory)]
        [string]$AdbPath,

        [Parameter(Mandatory)]
        [string]$TargetSerial,

        [Parameter(Mandatory)]
        [string]$DevicePath,

        [switch]$AllowMissing
    )

    & $AdbPath -s $TargetSerial shell test -f $DevicePath | Out-Null
    $testExitCode = $LASTEXITCODE
    if ($testExitCode -eq 1 -and $AllowMissing) {
        return $null
    }
    if ($testExitCode -ne 0) {
        throw "Could not inspect the device file before publication: $DevicePath"
    }

    $rawSignature = ((& $AdbPath -s $TargetSerial shell stat -c "%s:%Y" $DevicePath) -join "`n").Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read the device file signature: $DevicePath"
    }
    if ($rawSignature -notmatch '^(?<length>[0-9]+):(?<modified>[0-9]+)$') {
        throw "Device file signature has an unsupported shape: $rawSignature"
    }

    return [pscustomobject]@{
        length = [long]$Matches.length
        modified_unix_seconds = [long]$Matches.modified
        token = $rawSignature
    }
}

if ($Serial -notmatch '^[A-Za-z0-9._:-]{2,128}$') {
    throw "Serial contains unsupported characters."
}
if ($Package -notmatch '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$') {
    throw "Package is not a valid Android application id."
}

$resolvedProfile = (Resolve-Path -LiteralPath $ProfilePath).Path
$profileInfo = Get-Item -LiteralPath $resolvedProfile
if ($profileInfo.Length -gt 65536) {
    throw "Control profile exceeds the 64 KiB limit."
}
$profile = Get-Content -LiteralPath $resolvedProfile -Raw | ConvertFrom-Json
if ([string]$profile.schema -ne $expectedSchema) {
    throw "Unsupported control profile schema: $($profile.schema)"
}
if ([string]$profile.profile_id -notmatch '^[a-z0-9][a-z0-9._-]{1,63}$') {
    throw "profile_id is invalid."
}
if ($null -eq $profile.quest_controls) {
    throw "Control profile has no quest_controls object."
}
$profileSha256 = (Get-FileHash -LiteralPath $resolvedProfile -Algorithm SHA256).Hash.ToLowerInvariant()

if ([string]::IsNullOrWhiteSpace($Adb)) {
    $adbPath = (Get-Command adb -ErrorAction Stop).Source
} elseif (Test-Path -LiteralPath $Adb -PathType Leaf) {
    $adbPath = (Resolve-Path -LiteralPath $Adb).Path
} else {
    $adbPath = (Get-Command $Adb -ErrorAction Stop).Source
}

& $adbPath -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "ADB target $Serial is unavailable."
}
$model = (& $adbPath -s $Serial shell getprop ro.product.model).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($model)) {
    throw "Could not confirm the exact ADB target."
}

$deviceDirectory = "/sdcard/Android/data/$Package/files/control-profiles"
$activePath = "$deviceDirectory/active.profile.json"
$pendingPath = "$deviceDirectory/.pending-$($profileSha256.Substring(0, 16)).profile.json"
$receiptPath = "$deviceDirectory/last-apply-receipt.json"
$startedUnixMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

& $adbPath -s $Serial shell mkdir -p $deviceDirectory
if ($LASTEXITCODE -ne 0) {
    throw "Could not create the app-specific profile directory."
}
$priorSignature = Get-DeviceFileSignature `
    -AdbPath $adbPath `
    -TargetSerial $Serial `
    -DevicePath $activePath `
    -AllowMissing

& $adbPath -s $Serial push $resolvedProfile $pendingPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Could not stage the control profile on the headset."
}

$publishModifiedUnixSeconds = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
if ($null -ne $priorSignature) {
    if ([long]$priorSignature.modified_unix_seconds -eq [long]::MaxValue) {
        throw "The active profile modification time cannot be advanced."
    }
    $nextModifiedUnixSeconds = [long]$priorSignature.modified_unix_seconds + 1L
    if ($nextModifiedUnixSeconds -gt $publishModifiedUnixSeconds) {
        $publishModifiedUnixSeconds = $nextModifiedUnixSeconds
    }
}

& $adbPath -s $Serial shell touch -m -d "@$publishModifiedUnixSeconds" $pendingPath
if ($LASTEXITCODE -ne 0) {
    throw "Could not assign a new publication generation to the staged control profile."
}
$stagedSignature = Get-DeviceFileSignature `
    -AdbPath $adbPath `
    -TargetSerial $Serial `
    -DevicePath $pendingPath
if (
    [long]$stagedSignature.length -ne [long]$profileInfo.Length -or
    [long]$stagedSignature.modified_unix_seconds -ne $publishModifiedUnixSeconds
) {
    throw "The staged control profile signature did not match its publication generation."
}
if ($null -ne $priorSignature -and [string]$stagedSignature.token -eq [string]$priorSignature.token) {
    throw "The staged control profile did not advance the device-observed signature."
}

& $adbPath -s $Serial shell mv $pendingPath $activePath
if ($LASTEXITCODE -ne 0) {
    throw "Could not atomically publish the staged control profile."
}
$publishedSignature = Get-DeviceFileSignature `
    -AdbPath $adbPath `
    -TargetSerial $Serial `
    -DevicePath $activePath
if ([string]$publishedSignature.token -ne [string]$stagedSignature.token) {
    throw "The published control profile did not retain the staged signature."
}
if ($null -ne $priorSignature -and [string]$publishedSignature.token -eq [string]$priorSignature.token) {
    throw "The published control profile did not advance the device-observed signature."
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$effectiveReceipt = $null
do {
    Start-Sleep -Milliseconds 250
    $rawReceipt = (& $adbPath -s $Serial shell cat $receiptPath 2>$null) -join "`n"
    if (-not [string]::IsNullOrWhiteSpace($rawReceipt)) {
        try {
            $candidate = $rawReceipt | ConvertFrom-Json
            $candidateTime = if ($null -ne $candidate.applied_unix_ms) {
                [long]$candidate.applied_unix_ms
            } elseif ($null -ne $candidate.rejected_unix_ms) {
                [long]$candidate.rejected_unix_ms
            } else {
                0L
            }
            if (
                [string]$candidate.schema -eq $expectedReceiptSchema -and
                [string]$candidate.profile_sha256 -eq $profileSha256 -and
                $candidateTime -ge $startedUnixMs
            ) {
                $effectiveReceipt = $candidate
                break
            }
        } catch {
            # The app publishes through a temporary file; ignore any incomplete diagnostic read.
        }
    }
} while ([DateTimeOffset]::UtcNow -lt $deadline)

if ($null -eq $effectiveReceipt) {
    throw "The active app did not emit a matching profile-application receipt within $TimeoutSeconds seconds."
}
if ([string]$effectiveReceipt.status -ne "applied") {
    throw "The active app rejected the profile: $($effectiveReceipt.error_code) $($effectiveReceipt.error)"
}

$result = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.control_profile_hotload_host_receipt.v1"
    status = "pass"
    provider = "serial-scoped-adb-fallback"
    device_model = $model
    package = $Package
    profile_id = [string]$profile.profile_id
    profile_revision = [long]$profile.revision
    profile_sha256 = $profileSha256
    device_profile_path = "external-files/control-profiles/active.profile.json"
    app_receipt_path = "external-files/control-profiles/last-apply-receipt.json"
    app_receipt = $effectiveReceipt
    publication_signature = [ordered]@{
        prior_present = ($null -ne $priorSignature)
        length = [long]$publishedSignature.length
        modified_unix_seconds = [long]$publishedSignature.modified_unix_seconds
        changed_from_prior = ($null -eq $priorSignature -or [string]$publishedSignature.token -ne [string]$priorSignature.token)
        atomic_replace = $true
    }
    high_rate_payload = $false
}

if (-not [string]::IsNullOrWhiteSpace($OutPath)) {
    $outFile = [IO.Path]::GetFullPath($OutPath)
    $outDirectory = Split-Path -Parent $outFile
    if (-not [string]::IsNullOrWhiteSpace($outDirectory)) {
        New-Item -ItemType Directory -Force -Path $outDirectory | Out-Null
    }
    [IO.File]::WriteAllText(
        $outFile,
        (($result | ConvertTo-Json -Depth 20) + [Environment]::NewLine),
        [Text.UTF8Encoding]::new($false)
    )
}

$result | ConvertTo-Json -Depth 20
