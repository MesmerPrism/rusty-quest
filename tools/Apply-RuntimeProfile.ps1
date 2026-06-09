param(
    [Parameter(Mandatory=$true)]
    [string]$ProfilePath,
    [switch]$DryRun,
    [string]$Out = "local-artifacts\property-write-plan.json"
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$resolvedProfile = Resolve-Path $ProfilePath
$profile = Get-Content -Path $resolvedProfile -Raw | ConvertFrom-Json

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
    operations = $operations
}

$outPath = Join-Path $RepoRoot $Out
New-Item -ItemType Directory -Path (Split-Path $outPath -Parent) -Force | Out-Null
$plan | ConvertTo-Json -Depth 8 | Set-Content -Path $outPath -Encoding UTF8

if (-not $DryRun) {
    throw "Device execution is intentionally not implemented in this scaffold; use -DryRun"
}

Write-Output "runtime profile dry-run plan written: $outPath"

