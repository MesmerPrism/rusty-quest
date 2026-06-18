param(
    [Parameter(Mandatory=$true)]
    [string]$ProfilePath,
    [switch]$DryRun,
    [switch]$Execute,
    [string]$Out = "local-artifacts\property-write-plan.json",
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT
)

$ErrorActionPreference = "Stop"
$AndroidPropertyValueMaxBytes = 92

function ConvertTo-AndroidShellSingleQuoted {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Value
    )

    if ($Value.Contains("'")) {
        throw "Android shell single-quote escaping is not supported for value: $Value"
    }
    return "'$Value'"
}

function Resolve-AdbServerPortArgument {
    param(
        [string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    $parsed = 0
    if (-not [int]::TryParse($Value, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
        throw "ADB server port must be an integer from 1 to 65535: $Value"
    }
    return $parsed.ToString()
}

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$resolvedProfile = Resolve-Path $ProfilePath
$profile = Get-Content -Path $resolvedProfile -Raw | ConvertFrom-Json

if ($DryRun -and $Execute) {
    throw "Use either -DryRun or -Execute, not both"
}
if (-not $DryRun -and -not $Execute) {
    throw "Pass -DryRun for a plan only, or -Execute to write properties through ADB"
}

if ($profile.schema -ne "rusty.quest.runtime_profile.v1") {
    throw "Unsupported runtime profile schema: $($profile.schema)"
}
if ($profile.target_platform -ne "quest") {
    throw "Unsupported target platform: $($profile.target_platform)"
}

$owned = @{}
$operations = @()
foreach ($name in $profile.owned_android_properties) {
    if ([string]::IsNullOrWhiteSpace($name)) {
        throw "Owned Android property must not be empty"
    }
    if ($name -like "*rustyxr*" -or $name -like "*rusty.xr*") {
        throw "Legacy Android property is not allowed: $name"
    }
    if (-not $name.StartsWith("debug.rustyquest.")) {
        throw "Quest runtime properties must use debug.rustyquest.*: $name"
    }
    if ($owned.ContainsKey($name)) {
        throw "Duplicate owned Android property: $name"
    }
    $owned[$name] = $true
    $operations += [ordered]@{
        kind = "clear"
        name = $name
        value = " "
        source_setting_id = $null
    }
}

foreach ($property in $profile.set_properties) {
    if (-not $owned.ContainsKey([string]$property.name)) {
        throw "Set property is not declared as profile-owned: $($property.name)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$property.source_setting_id)) {
        throw "Set property must declare source_setting_id: $($property.name)"
    }
    $valueBytes = [System.Text.Encoding]::UTF8.GetByteCount([string]$property.value)
    if ($valueBytes -gt $AndroidPropertyValueMaxBytes) {
        throw "Set property $($property.name) value is $valueBytes bytes, above Android setprop limit $AndroidPropertyValueMaxBytes"
    }
    $operations += [ordered]@{
        kind = "set"
        name = [string]$property.name
        value = [string]$property.value
        source_setting_id = [string]$property.source_setting_id
    }
}

$plan = [ordered]@{
    schema = "rusty.quest.property_write_plan.v1"
    profile_id = [string]$profile.profile_id
    source_profile_path = [string]$resolvedProfile
    dry_run = [bool]$DryRun
    device_write_performed = [bool]$Execute
    operations = $operations
}

if ($Execute) {
    if ([string]::IsNullOrWhiteSpace($Adb)) {
        $Adb = "adb"
    }
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        throw "-Serial or RUSTY_QUEST_SERIAL is required with -Execute; device-scoped ADB writes must not use an implicit target."
    }
    $resolvedAdbServerPort = Resolve-AdbServerPortArgument -Value $AdbServerPort
    $adbArgsBase = @()
    if ($null -ne $resolvedAdbServerPort) {
        $adbArgsBase += @("-P", $resolvedAdbServerPort)
    }
    $adbArgsBase += @("-s", $Serial)

    $state = & $Adb @adbArgsBase "get-state"
    if ($LASTEXITCODE -ne 0) {
        throw "ADB get-state failed with exit code $LASTEXITCODE"
    }
    if (($state -join "`n").Trim() -ne "device") {
        throw "ADB target is not in device state: $($state -join ' ')"
    }

    $readbacks = @()
    foreach ($operation in $operations) {
        $name = [string]$operation["name"]
        $value = [string]$operation["value"]
        $setpropCommand = "setprop $(ConvertTo-AndroidShellSingleQuoted $name) $(ConvertTo-AndroidShellSingleQuoted $value)"
        & $Adb @adbArgsBase "shell" $setpropCommand
        if ($LASTEXITCODE -ne 0) {
            throw "ADB setprop failed for $name with exit code $LASTEXITCODE"
        }

        $getpropCommand = "getprop $(ConvertTo-AndroidShellSingleQuoted $name)"
        $observed = & $Adb @adbArgsBase "shell" $getpropCommand
        if ($LASTEXITCODE -ne 0) {
            throw "ADB getprop failed for $name with exit code $LASTEXITCODE"
        }
        $observedValue = ($observed -join "`n").Trim()
        $expectedValue = if ([string]$operation["kind"] -eq "clear") {
            ""
        } else {
            $value.Trim()
        }
        if ($observedValue -ne $expectedValue) {
            throw "ADB property readback mismatch for ${name}: expected '${expectedValue}' observed '${observedValue}'"
        }
        $readbacks += [ordered]@{
            name = $name
            kind = [string]$operation["kind"]
            expected_value = $expectedValue
            observed_value = $observedValue
            status = "matched"
        }
    }

    $plan.executed_at = (Get-Date).ToUniversalTime().ToString("o")
    $plan.transport = "adb"
    $plan.adb_scope = "device-scoped-adb"
    $plan.adb_serial_required = $true
    $plan.adb_serial = $Serial
    $plan.adb_server_port = $resolvedAdbServerPort
    $plan.readbacks = $readbacks
}

$outPath = if ([System.IO.Path]::IsPathRooted($Out)) {
    $Out
} else {
    Join-Path $RepoRoot $Out
}
New-Item -ItemType Directory -Path (Split-Path $outPath -Parent) -Force | Out-Null
$plan | ConvertTo-Json -Depth 8 | Set-Content -Path $outPath -Encoding UTF8

if ($DryRun) {
    Write-Output "runtime profile dry-run plan written: $outPath"
} else {
    Write-Output "runtime profile applied and read back: $outPath"
}
