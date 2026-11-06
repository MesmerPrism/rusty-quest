[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Serial,

    [Parameter(Mandatory)]
    [string]$ProfilePath,

    [string]$Package = "io.github.mesmerprism.rustyquest.spatial_camera_panel",

    [ValidateSet("VisualControls", "ModulesOnly")]
    [string]$ReceiptMode = "VisualControls",

    [string]$ExpectedWatcherId,
    [int]$ExpectedOwnerPid,
    [string]$ExpectedOwnerStartTicks,

    [ValidateRange(1, 60)]
    [int]$TimeoutSeconds = 15,

    [string]$OutPath,

    [string]$Adb = $env:RUSTY_QUEST_ADB
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$expectedSchema = "rusty.quest.spatial_camera_panel.control_profile.v1"
$expectedReceiptSchema = "rusty.quest.spatial_camera_panel.control_profile_apply_receipt.v1"
$moduleOnly = $ReceiptMode -eq "ModulesOnly"
if ($moduleOnly) {
    $expectedSchema = "rusty.quest.spatial_camera_panel.development_modules.v1"
    if ($ExpectedWatcherId -notmatch '^[0-9a-f-]{36}$' -or $ExpectedOwnerPid -le 0 -or
        $ExpectedOwnerStartTicks -notmatch '^[1-9][0-9]*$') {
        throw "ModulesOnly requires the current armed watcher id, owner PID and process start ticks."
    }
}

function Assert-UniqueJsonKeys([System.Text.Json.JsonElement]$Element) {
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $seen.Add($property.Name)) { throw "Duplicate JSON field: $($property.Name)" }
            Assert-UniqueJsonKeys $property.Value
        }
    } elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        foreach ($value in $Element.EnumerateArray()) { Assert-UniqueJsonKeys $value }
    }
}

function Test-ModuleOwner($Receipt) {
    return [string]$Receipt.watcher_id -ceq $ExpectedWatcherId -and
        [long]$Receipt.owner_process_pid -eq $ExpectedOwnerPid -and
        [string]$Receipt.owner_process_start_ticks -ceq $ExpectedOwnerStartTicks
}

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
if ([string]$profile.schema -cne $expectedSchema) {
    throw "Unsupported control profile schema: $($profile.schema)"
}
if ([string]$profile.profile_id -cnotmatch '^[a-z0-9][a-z0-9._-]{1,63}$') {
    throw "profile_id is invalid."
}
if (-not $moduleOnly -and $null -eq $profile.quest_controls) {
    throw "Control profile has no quest_controls object."
}
$moduleConsumers = @()
if ($moduleOnly) {
    $moduleText = [Text.UTF8Encoding]::new($false, $true).GetString([IO.File]::ReadAllBytes($resolvedProfile))
    $document = [System.Text.Json.JsonDocument]::Parse($moduleText)
    try {
        $root = $document.RootElement
        Assert-UniqueJsonKeys $root
        if ($root.GetProperty("profile_id").ValueKind -ne [System.Text.Json.JsonValueKind]::String) { throw "Module-only profile id must be a string." }
        $allowed = @("schema", "profile_id", "revision", "development_observation_module", "development_scissor_module")
        foreach ($property in $root.EnumerateObject()) {
            if ($allowed -cnotcontains $property.Name) { throw "Unsupported module-only field: $($property.Name)" }
        }
        [long]$revision = 0
        if (-not $root.GetProperty("revision").TryGetInt64([ref]$revision) -or $revision -lt 0) {
            throw "Module-only revision must be a nonnegative signed integer."
        }
        foreach ($consumer in @("observation", "scissor")) {
            $field = "development_${consumer}_module"
            if ($profile.PSObject.Properties.Name -cnotcontains $field) { continue }
            $directive = $root.GetProperty($field)
            $mode = $directive.GetProperty("mode").GetString()
            $fields = @(switch -CaseSensitive ($mode) {
                "status" { @("mode") }
                "static" { @("mode") }
                "select_retained" { @("mode", "generation") }
                "activate" { if ($consumer -eq "scissor") { @("mode", "generation", "sha256", "policy") } else { @("mode", "generation", "sha256") } }
                default { throw "Unsupported development module mode." }
            })
            $actualFields = @($directive.EnumerateObject() | ForEach-Object Name)
            if ($actualFields.Count -ne $fields.Count -or @($actualFields | Where-Object { $fields -cnotcontains $_ }).Count) {
                throw "Development module fields do not match its mode."
            }
            if ($fields -contains "generation") {
                [long]$generation = 0
                if (-not $directive.GetProperty("generation").TryGetInt64([ref]$generation) -or $generation -le 0) { throw "Invalid module generation." }
            }
            if ($mode -ceq "activate") {
                if ($directive.GetProperty("sha256").GetString() -cnotmatch '^[0-9a-f]{64}$') { throw "Invalid module SHA256." }
                if ($consumer -eq "scissor" -and @("product", "inset_one_pixel") -cnotcontains $directive.GetProperty("policy").GetString()) { throw "Invalid scissor policy." }
            }
            $moduleConsumers += $consumer
        }
        if ($moduleConsumers.Count -eq 0) { throw "Module-only envelope has no directives." }
    } finally { $document.Dispose() }
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
$activeFile = if ($moduleOnly) { "active.modules.json" } else { "active.profile.json" }
$activePath = "$deviceDirectory/$activeFile"
$pendingPath = "$deviceDirectory/.pending-$($profileSha256.Substring(0, 16)).$activeFile"
$receiptPath = "$deviceDirectory/last-apply-receipt.json"
$startedUnixMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if ($moduleOnly) {
    $watchRaw = (& $adbPath -s $Serial shell cat "$deviceDirectory/module-watch-receipt.json" 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Current module watcher receipt unavailable; nothing published." }
    $watch = $watchRaw | ConvertFrom-Json
    if ([string]$watch.schema -cne "rusty.quest.spatial_camera_panel.development_module_watch.v1" -or
        [string]$watch.phase -cne "armed" -or -not (Test-ModuleOwner $watch)) {
        throw "Current armed module watcher identity did not match; nothing published."
    }
}

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
$moduleReceipts = [ordered]@{}
if ($moduleOnly) {
    do {
        Start-Sleep -Milliseconds 250
        foreach ($consumer in $moduleConsumers) {
            $raw = (& $adbPath -s $Serial shell cat "$deviceDirectory/last-development-$consumer-module-receipt.json" 2>$null) -join "`n"
            if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) { continue }
            $candidate = $null
            try {
                $read = $raw | ConvertFrom-Json
                if ([string]$read.schema -ceq "rusty.quest.spatial_camera_panel.development_module_receipt.v1" -and
                    (Test-ModuleOwner $read) -and [string]$read.consumer -ceq $consumer -and
                    [string]$read.profile_sha256 -ceq $profileSha256 -and [string]$read.profile_id -ceq [string]$profile.profile_id -and
                    [long]$read.revision -eq [long]$profile.revision -and [string]$read.source_file -ceq $activeFile -and
                    [long]$read.source_length -eq [long]$profileInfo.Length -and
                    [long]$read.source_modified_unix_ms -eq ([long]$publishedSignature.modified_unix_seconds * 1000L) -and
                    [string]$read.mode -ceq [string]$profile."development_${consumer}_module".mode) { $candidate = $read }
            } catch { continue }
            if ($null -eq $candidate) { continue }
            if ([string]$candidate.status -in @("rejected", "failed", "unavailable", "retired")) {
                throw "Module transport returned $($candidate.status) for $consumer; publication is retained and must not be retried automatically."
            }
            if ([string]$candidate.status -ceq "observed" -and [string]$candidate.phase -ceq "consumer_readback" -and
                $null -ne $candidate.consumer_receipt) { $moduleReceipts[$consumer] = $candidate }
        }
    } while ($moduleReceipts.Count -ne $moduleConsumers.Count -and [DateTimeOffset]::UtcNow -lt $deadline)
    if ($moduleReceipts.Count -ne $moduleConsumers.Count) {
        throw "Module consumer readback is unobserved; publication remains in place. Do not retry automatically."
    }
    $effectiveReceipt = $moduleReceipts
} else {
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
}

$result = [ordered]@{
    schema = "rusty.quest.spatial_camera_panel.control_profile_hotload_host_receipt.v1"
    status = $(if ($moduleOnly) { "observed" } else { "pass" })
    receipt_mode = $ReceiptMode
    provider = "serial-scoped-adb-fallback"
    device_model = $model
    package = $Package
    profile_id = [string]$profile.profile_id
    profile_revision = [long]$profile.revision
    profile_sha256 = $profileSha256
    device_profile_path = "external-files/control-profiles/$activeFile"
    app_receipt_path = $(if ($moduleOnly) { "external-files/control-profiles/last-development-{observation,scissor}-module-receipt.json" } else { "external-files/control-profiles/last-apply-receipt.json" })
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
