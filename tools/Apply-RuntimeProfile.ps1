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
$EnvironmentDepthPropertyPrefix = "debug.rustyquest.native_renderer.environment_depth."
$EnvironmentDepthModeProperty = "debug.rustyquest.native_renderer.environment_depth.mode"
$EnvironmentDepthSourceProperty = "debug.rustyquest.native_renderer.environment_depth.source"
$EnvironmentDepthLayerPolicyProperty = "debug.rustyquest.native_renderer.environment_depth.layer_policy"
$EnvironmentDepthDepthUnitsPolicyProperty = "debug.rustyquest.native_renderer.environment_depth.depth_units_policy"
$EnvironmentDepthDebugViewProperty = "debug.rustyquest.native_renderer.environment_depth.debug_view"
$EnvironmentDepthReferenceSpaceProperty = "debug.rustyquest.native_renderer.environment_depth.reference_space"
$EnvironmentDepthHandRemovalEnabledProperty = "debug.rustyquest.native_renderer.environment_depth.hand_removal.enabled"
$EnvironmentDepthParticleCapacityProperty = "debug.rustyquest.native_renderer.environment_depth.particle_capacity"
$EnvironmentDepthSampleStridePixelsProperty = "debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels"
$EnvironmentDepthNearMProperty = "debug.rustyquest.native_renderer.environment_depth.near_m"
$EnvironmentDepthFarMProperty = "debug.rustyquest.native_renderer.environment_depth.far_m"
$EnvironmentDepthHighRateJsonPayloadProperty = "debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload"
$EnvironmentDepthSurfaceModelProperty = "debug.rustyquest.native_renderer.environment_depth.surface_model"
$EnvironmentDepthSurfaceSupportRadiusCellsProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.radius_cells"
$EnvironmentDepthSurfaceSupportMinNeighborsProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors"
$EnvironmentDepthSurfaceSupportMinObservationsProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.min_observations"
$EnvironmentDepthSurfaceSupportMinSourceLayersProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.min_source_layers"
$EnvironmentDepthSurfaceSupportComponentMinCellsProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.component_min_cells"
$EnvironmentDepthSurfaceSupportNormalCoherenceProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.normal_coherence"
$EnvironmentDepthSurfaceSupportFreeSpaceDecayProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.free_space_decay"

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

function Get-NormalizedProfileValue {
    param([Parameter(Mandatory=$true)][string]$Value)
    return $Value.Trim().ToLowerInvariant().Replace("_", "-")
}

function Assert-EnvironmentDepthUInt {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value,
        [Parameter(Mandatory=$true)][uint32]$Min,
        [Parameter(Mandatory=$true)][uint32]$Max
    )
    $parsed = [uint32]0
    if (-not [uint32]::TryParse($Value.Trim(), [ref]$parsed) -or $parsed -lt $Min -or $parsed -gt $Max) {
        throw "$Name value $Value must be an integer from $Min to $Max"
    }
}

function Assert-EnvironmentDepthBool {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    $normalized = Get-NormalizedProfileValue -Value $Value
    if (@("0", "1", "false", "true", "no", "yes", "off", "on") -notcontains $normalized) {
        throw "$Name value $Value must be boolean"
    }
}

function Get-EnvironmentDepthFloat {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    try {
        $parsed = [double]::Parse($Value.Trim(), [System.Globalization.CultureInfo]::InvariantCulture)
    } catch {
        throw "$Name value $Value must be a finite number"
    }
    if ([double]::IsNaN($parsed) -or [double]::IsInfinity($parsed)) {
        throw "$Name value $Value must be a finite number"
    }
    return $parsed
}

function Assert-EnvironmentDepthProperty {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    $normalized = Get-NormalizedProfileValue -Value $Value
    switch -Exact ($Name) {
        $EnvironmentDepthModeProperty {
            if (@("disabled", "off", "status", "status-only", "provider-status", "retained-particles", "retained-particle-map", "scene-particle-map", "scene-map") -notcontains $normalized) {
                throw "Environment depth mode is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthSourceProperty {
            if (@("runtime-provider", "provider", "xr-meta-environment-depth", "meta-environment-depth", "meta-provider", "synthetic-gpu-proof", "synthetic-proof", "synthetic-depth-grid") -notcontains $normalized) {
                throw "Environment depth source is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthLayerPolicyProperty {
            if (@("mono-layer0", "layer0", "view0", "left", "mono-layer1", "layer1", "view1", "right") -notcontains $normalized) {
                throw "Environment depth layer_policy is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthDepthUnitsPolicyProperty {
            if (@("projected-depth-from-near-far", "projected-near-far", "near-far-projection") -notcontains $normalized) {
                throw "Environment depth depth_units_policy is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthDebugViewProperty {
            if (@(
                "normal",
                "off",
                "disabled",
                "raw-d16",
                "raw-depth",
                "debug-raw-d16",
                "confidence",
                "debug-confidence",
                "confidence-filter",
                "age",
                "particle-age",
                "cell-age",
                "debug-age",
                "source-layer",
                "source-layer-mask",
                "layer",
                "debug-source-layer",
                "hash-probe",
                "probe",
                "hash",
                "debug-hash-probe",
                "free-space-state",
                "free-space",
                "retired-state",
                "debug-free-space-state",
                "surface-support",
                "surface",
                "support",
                "debug-surface-support"
            ) -notcontains $normalized) {
                throw "Environment depth debug_view is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthReferenceSpaceProperty {
            if (@("local", "stage", "openxr-local", "openxr-stage") -notcontains $normalized) {
                throw "Environment depth reference_space is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthHandRemovalEnabledProperty {
            Assert-EnvironmentDepthBool -Name $Name -Value $Value
            return
        }
        $EnvironmentDepthParticleCapacityProperty {
            Assert-EnvironmentDepthUInt -Name $Name -Value $Value -Min 64 -Max 262144
            return
        }
        $EnvironmentDepthSampleStridePixelsProperty {
            Assert-EnvironmentDepthUInt -Name $Name -Value $Value -Min 1 -Max 128
            return
        }
        $EnvironmentDepthNearMProperty {
            $null = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            return
        }
        $EnvironmentDepthFarMProperty {
            $null = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            return
        }
        $EnvironmentDepthHighRateJsonPayloadProperty {
            if (@("0", "false", "no", "off") -notcontains $normalized) {
                throw "Environment depth high_rate_json_payload must be false"
            }
            return
        }
        $EnvironmentDepthSurfaceModelProperty {
            if (@("particles", "particle-cloud", "legacy-particles", "local-surfels", "local-surfels-candidates", "local", "global-surfaces", "confirmed-surfaces", "global", "hybrid", "hybrid-surfaces", "local-and-global") -notcontains $normalized) {
                throw "Environment depth surface_model is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthSurfaceSupportRadiusCellsProperty {
            Assert-EnvironmentDepthUInt -Name $Name -Value $Value -Min 1 -Max 8
            return
        }
        $EnvironmentDepthSurfaceSupportMinNeighborsProperty {
            Assert-EnvironmentDepthUInt -Name $Name -Value $Value -Min 0 -Max 26
            return
        }
        $EnvironmentDepthSurfaceSupportMinObservationsProperty {
            Assert-EnvironmentDepthUInt -Name $Name -Value $Value -Min 1 -Max 64
            return
        }
        $EnvironmentDepthSurfaceSupportMinSourceLayersProperty {
            Assert-EnvironmentDepthUInt -Name $Name -Value $Value -Min 1 -Max 2
            return
        }
        $EnvironmentDepthSurfaceSupportComponentMinCellsProperty {
            Assert-EnvironmentDepthUInt -Name $Name -Value $Value -Min 1 -Max 4096
            return
        }
        $EnvironmentDepthSurfaceSupportNormalCoherenceProperty {
            if (@("off", "loose", "low", "strict", "high") -notcontains $normalized) {
                throw "Environment depth surface_support.normal_coherence is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthSurfaceSupportFreeSpaceDecayProperty {
            if (@("soft", "hard", "immediate") -notcontains $normalized) {
                throw "Environment depth surface_support.free_space_decay is not supported: $Value"
            }
            return
        }
        default {
            throw "Unknown environment depth property: $Name"
        }
    }
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
$environmentDepthProperties = @{}
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
    $propertyName = [string]$property.name
    $propertyValue = [string]$property.value
    if (-not $owned.ContainsKey($propertyName)) {
        throw "Set property is not declared as profile-owned: $propertyName"
    }
    if ([string]::IsNullOrWhiteSpace([string]$property.source_setting_id)) {
        throw "Set property must declare source_setting_id: $propertyName"
    }
    $valueBytes = [System.Text.Encoding]::UTF8.GetByteCount($propertyValue)
    if ($valueBytes -gt $AndroidPropertyValueMaxBytes) {
        throw "Set property $propertyName value is $valueBytes bytes, above Android setprop limit $AndroidPropertyValueMaxBytes"
    }
    if ($propertyName.StartsWith($EnvironmentDepthPropertyPrefix)) {
        Assert-EnvironmentDepthProperty -Name $propertyName -Value $propertyValue
        $environmentDepthProperties[$propertyName] = $propertyValue
    }
    $operations += [ordered]@{
        kind = "set"
        name = $propertyName
        value = $propertyValue
        source_setting_id = [string]$property.source_setting_id
    }
}

if ($environmentDepthProperties.ContainsKey($EnvironmentDepthNearMProperty) -and $environmentDepthProperties.ContainsKey($EnvironmentDepthFarMProperty)) {
    $nearM = Get-EnvironmentDepthFloat -Name $EnvironmentDepthNearMProperty -Value $environmentDepthProperties[$EnvironmentDepthNearMProperty]
    $farM = Get-EnvironmentDepthFloat -Name $EnvironmentDepthFarMProperty -Value $environmentDepthProperties[$EnvironmentDepthFarMProperty]
    if ($nearM -le 0.0) {
        throw "Environment depth near_m must be greater than 0"
    }
    if ($farM -le $nearM) {
        throw "Environment depth far_m $farM must be greater than near_m $nearM"
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
