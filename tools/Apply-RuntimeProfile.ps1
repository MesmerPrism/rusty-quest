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
$NativeRendererPropertyPrefix = "debug.rustyquest.native_renderer."
$NativeRendererPropertyManifestRelativePath = "fixtures\native-renderer\native-renderer-property-manifest.json"
$NativeRendererPropertyManifestSchema = "rusty.quest.native_renderer_property_manifest.v2"
$NativeRendererPropertyManifestLifecycle = "startup-effective"
$NativeRendererPropertyManifestClearBehavior = "profile-owned-explicit-set"
$NativeRendererPropertyManifestDefaultBehavior = "runtime-owner-default-when-unset"
$EnvironmentDepthPropertyPrefix = "debug.rustyquest.native_renderer.environment_depth."
$EnvironmentDepthModeProperty = "debug.rustyquest.native_renderer.environment_depth.mode"
$EnvironmentDepthSourceProperty = "debug.rustyquest.native_renderer.environment_depth.source"
$EnvironmentDepthLayerPolicyProperty = "debug.rustyquest.native_renderer.environment_depth.layer_policy"
$EnvironmentDepthDepthUnitsPolicyProperty = "debug.rustyquest.native_renderer.environment_depth.depth_units_policy"
$EnvironmentDepthDebugViewProperty = "debug.rustyquest.native_renderer.environment_depth.debug_view"
$EnvironmentDepthReferenceSpaceProperty = "debug.rustyquest.native_renderer.environment_depth.reference_space"
$EnvironmentDepthHandRemovalEnabledProperty = "debug.rustyquest.native_renderer.environment_depth.hand_removal.enabled"
$EnvironmentDepthNativePassthroughRequiredProperty = "debug.rustyquest.native_renderer.environment_depth.native_passthrough.required"
$EnvironmentDepthParticleCapacityProperty = "debug.rustyquest.native_renderer.environment_depth.particle_capacity"
$EnvironmentDepthSampleStridePixelsProperty = "debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels"
$EnvironmentDepthNearMProperty = "debug.rustyquest.native_renderer.environment_depth.near_m"
$EnvironmentDepthFarMProperty = "debug.rustyquest.native_renderer.environment_depth.far_m"
$EnvironmentDepthHighRateJsonPayloadProperty = "debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload"
$EnvironmentDepthAlignmentControlsProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.controls"
$EnvironmentDepthAlignmentJoystickControlsProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.joystick.controls"
$EnvironmentDepthAlignmentJoystickRateUvPerSecondProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.joystick.rate_uv_per_second"
$EnvironmentDepthAlignmentMaxOffsetUvProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.max_offset_uv"
$EnvironmentDepthAlignmentLeftOffsetXUvProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.left.offset.x.uv"
$EnvironmentDepthAlignmentLeftOffsetYUvProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.left.offset.y.uv"
$EnvironmentDepthAlignmentRightOffsetXUvProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.right.offset.x.uv"
$EnvironmentDepthAlignmentRightOffsetYUvProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.right.offset.y.uv"
$EnvironmentDepthAlignmentScaleProperty = "debug.rustyquest.native_renderer.environment_depth.alignment.scale"
$EnvironmentDepthSurfaceModelProperty = "debug.rustyquest.native_renderer.environment_depth.surface_model"
$EnvironmentDepthSurfaceSupportRadiusCellsProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.radius_cells"
$EnvironmentDepthSurfaceSupportMinNeighborsProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors"
$EnvironmentDepthSurfaceSupportMinObservationsProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.min_observations"
$EnvironmentDepthSurfaceSupportMinSourceLayersProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.min_source_layers"
$EnvironmentDepthSurfaceSupportComponentMinCellsProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.component_min_cells"
$EnvironmentDepthSurfaceSupportComponentModeProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.component_mode"
$EnvironmentDepthSurfaceSupportNormalSourceProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.normal_source"
$EnvironmentDepthSurfaceSupportNormalCoherenceProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.normal_coherence"
$EnvironmentDepthSurfaceSupportSmallComponentPolicyProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.small_component_policy"
$EnvironmentDepthSurfaceSupportFreeSpaceDecayProperty = "debug.rustyquest.native_renderer.environment_depth.surface_support.free_space_decay"
$StimulusVolumePropertyPrefix = "debug.rustyquest.native_renderer.stimulus_volume."
$StimulusVolumeEnabledProperty = "debug.rustyquest.native_renderer.stimulus_volume.enabled"
$StimulusVolumeProfileProperty = "debug.rustyquest.native_renderer.stimulus_volume.profile"
$StimulusVolumeCompositionProperty = "debug.rustyquest.native_renderer.stimulus_volume.composition"
$StimulusVolumeRenderTargetProperty = "debug.rustyquest.native_renderer.stimulus_volume.render_target"
$StimulusVolumeRaymarchSamplesProperty = "debug.rustyquest.native_renderer.stimulus_volume.raymarch_samples"
$StimulusVolumeCentralFovFractionProperty = "debug.rustyquest.native_renderer.stimulus_volume.central_fov_fraction"
$StimulusVolumeGradientSmoothingProperty = "debug.rustyquest.native_renderer.stimulus_volume.gradient_smoothing"
$StimulusVolumePatternFamilyProperty = "debug.rustyquest.native_renderer.stimulus_volume.pattern_family"
$StimulusVolumeRandomizeEnabledProperty = "debug.rustyquest.native_renderer.stimulus_volume.randomize.enabled"
$StimulusVolumeRandomizeMinHzProperty = "debug.rustyquest.native_renderer.stimulus_volume.randomize.min_hz"
$StimulusVolumeRandomizeMaxHzProperty = "debug.rustyquest.native_renderer.stimulus_volume.randomize.max_hz"
$StimulusVolumeSafetyAckProperty = "debug.rustyquest.native_renderer.stimulus_volume.safety_ack"
$NativeProjectionTargetPropertyPrefix = "debug.rustyquest.native_renderer.projection.target."
$NativeProjectionTargetControlsProperty = "debug.rustyquest.native_renderer.projection.target.controls"
$NativeProjectionTargetScaleProperty = "debug.rustyquest.native_renderer.projection.target.scale"
$NativeProjectionTargetTunedMaxScaleProperty = "debug.rustyquest.native_renderer.projection.target.tuned.max.scale"
$NativeProjectionTargetMinScaleProperty = "debug.rustyquest.native_renderer.projection.target.min.scale"
$NativeProjectionTargetMaxScaleProperty = "debug.rustyquest.native_renderer.projection.target.max.scale"
$NativeProjectionTargetOffsetXUvProperty = "debug.rustyquest.native_renderer.projection.target.offset.x.uv"
$NativeProjectionTargetOffsetYUvProperty = "debug.rustyquest.native_renderer.projection.target.offset.y.uv"
$NativeProjectionTargetJoystickControlsProperty = "debug.rustyquest.native_renderer.projection.target.joystick.controls"
$NativeProjectionTargetJoystickRateProperty = "debug.rustyquest.native_renderer.projection.target.joystick.scale.rate_per_second"
$NativeProjectionTargetBreathModeProperty = "debug.rustyquest.native_renderer.projection.target.breath.bridge.mode"
$NativeProjectionTargetBreathControllerAxisXProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.orientation_axis.x"
$NativeProjectionTargetBreathControllerAxisYProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.orientation_axis.y"
$NativeProjectionTargetBreathControllerAxisZProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.orientation_axis.z"
$NativeProjectionTargetBreathControllerInhaleThresholdProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.inhale_threshold"
$NativeProjectionTargetBreathControllerExhaleThresholdProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.exhale_threshold"
$NativeProjectionTargetBreathControllerRotationGuardDegreesProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.rotation_guard_degrees"
$NativeProjectionTargetBreathControllerMovingAverageGuardProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.moving_average_guard"
$NativeProjectionTargetBreathControllerShortWindowSamplesProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.short_window.samples"
$NativeProjectionTargetBreathControllerLongWindowSamplesProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.long_window.samples"
$NativeProjectionTargetBreathControllerShortWindowSecondsProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.short_window.seconds"
$NativeProjectionTargetBreathControllerLongWindowSecondsProperty = "debug.rustyquest.native_renderer.projection.target.breath.controller_state.long_window.seconds"
$NativeProjectionTargetBreathStateStreamProperty = "debug.rustyquest.native_renderer.projection.target.breath.state.stream"
$NativeProjectionTargetBreathValueStreamProperty = "debug.rustyquest.native_renderer.projection.target.breath.value.stream"
$NativeProjectionTargetBreathInhaleSecondsProperty = "debug.rustyquest.native_renderer.projection.target.breath.inhale.seconds.min_to_max"
$NativeProjectionTargetBreathExhaleSecondsProperty = "debug.rustyquest.native_renderer.projection.target.breath.exhale.seconds.max_to_min"
$NativeProjectionTargetBreathSyntheticPeriodSecondsProperty = "debug.rustyquest.native_renderer.projection.target.breath.synthetic.period.seconds"
$NativeProjectionTargetBreathHighRateJsonPayloadProperty = "debug.rustyquest.native_renderer.projection.target.breath.high_rate_json_payload"
$NativeManifoldBrokerPropertyPrefix = "debug.rustyquest.native_renderer.manifold."
$NativeManifoldBrokerHostProperty = "debug.rustyquest.native_renderer.manifold.broker.host"
$NativeManifoldBrokerPortProperty = "debug.rustyquest.native_renderer.manifold.broker.port"
$NativeManifoldBrokerPathProperty = "debug.rustyquest.native_renderer.manifold.broker.path"
$NativeManifoldEmbeddedBrokerEnabledProperty = "debug.rustyquest.native_renderer.manifold.embedded_broker.enabled"
$NativeManifoldEmbeddedBrokerBindHostProperty = "debug.rustyquest.native_renderer.manifold.embedded_broker.bind_host"
$NativeManifoldEmbeddedBrokerPortProperty = "debug.rustyquest.native_renderer.manifold.embedded_broker.port"
$NativeManifoldEmbeddedBrokerPathProperty = "debug.rustyquest.native_renderer.manifold.embedded_broker.path"
$NativeManifoldEmbeddedBrokerMaxFrameBytesProperty = "debug.rustyquest.native_renderer.manifold.embedded_broker.max_frame_bytes"
$NativeManifoldEmbeddedBrokerLanEnabledProperty = "debug.rustyquest.native_renderer.manifold.embedded_broker.lan_enabled"
$NativeManifoldEmbeddedBrokerSessionTokenRequiredProperty = "debug.rustyquest.native_renderer.manifold.embedded_broker.session_token_required"
$NativeManifoldEmbeddedBrokerSessionTokenProperty = "debug.rustyquest.native_renderer.manifold.embedded_broker.session_token"
$MakepadPropertyPrefix = "debug.rustyquest.makepad."

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

function Get-ManifestFiniteFloat {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    try {
        $parsed = [double]::Parse($Value.Trim(), [System.Globalization.CultureInfo]::InvariantCulture)
    } catch {
        throw "$Name value $Value must be a finite manifest float"
    }
    if ([double]::IsNaN($parsed) -or [double]::IsInfinity($parsed)) {
        throw "$Name value $Value must be a finite manifest float"
    }
    return $parsed
}

function Assert-NativeRendererManifestRange {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][double]$Value,
        [Parameter(Mandatory=$true)]$Entry
    )
    if ($null -eq $Entry.range) {
        return
    }
    if ($null -ne $Entry.range.min -and $Value -lt [double]$Entry.range.min) {
        throw "$Name value $Value is below manifest minimum $($Entry.range.min)"
    }
    if ($null -ne $Entry.range.max -and $Value -gt [double]$Entry.range.max) {
        throw "$Name value $Value is above manifest maximum $($Entry.range.max)"
    }
}

function Import-NativeRendererPropertyManifest {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Path
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Native renderer property manifest is missing: $Path"
    }
    $manifest = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    if ($manifest.schema -ne $NativeRendererPropertyManifestSchema) {
        throw "Native renderer property manifest has unsupported schema: $($manifest.schema)"
    }
    if ($manifest.prefix -ne $NativeRendererPropertyPrefix) {
        throw "Native renderer property manifest has unsupported prefix: $($manifest.prefix)"
    }
    $entries = @($manifest.properties)
    if ($manifest.property_count -ne $entries.Count) {
        throw "Native renderer property manifest property_count does not match properties length"
    }
    $byName = @{}
    foreach ($entry in $entries) {
        $name = [string]$entry.name
        if ([string]::IsNullOrWhiteSpace($name)) {
            throw "Native renderer property manifest contains an empty property name"
        }
        if ($byName.ContainsKey($name)) {
            throw "Native renderer property manifest contains duplicate property: $name"
        }
        if ([string]$entry.lifecycle -ne $NativeRendererPropertyManifestLifecycle) {
            throw "Native renderer property manifest entry $name has unsupported lifecycle: $($entry.lifecycle)"
        }
        if ([string]$entry.clear_behavior -ne $NativeRendererPropertyManifestClearBehavior) {
            throw "Native renderer property manifest entry $name has unsupported clear_behavior: $($entry.clear_behavior)"
        }
        if ([string]$entry.default_behavior -ne $NativeRendererPropertyManifestDefaultBehavior) {
            throw "Native renderer property manifest entry $name has unsupported default_behavior: $($entry.default_behavior)"
        }
        $byName[$name] = $entry
    }
    return $byName
}

function Assert-NativeRendererManifestProperty {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value,
        [Parameter(Mandatory=$true)]$ManifestByName
    )
    if (-not $Name.StartsWith($NativeRendererPropertyPrefix)) {
        return
    }
    if (-not $ManifestByName.ContainsKey($Name)) {
        throw "Native renderer property is missing from manifest: $Name"
    }
    $entry = $ManifestByName[$Name]
    $trimmed = $Value.Trim()
    switch ([string]$entry.value_kind) {
        "bool" {
            if (@("true", "false") -cnotcontains $trimmed.ToLowerInvariant()) {
                throw "$Name value $Value must be manifest bool true/false"
            }
            return
        }
        "token" {
            $allowed = @($entry.allowed_values | ForEach-Object { [string]$_ })
            if ($allowed -cnotcontains $Value) {
                throw "$Name value $Value is not in manifest allowed_values: $($allowed -join ', ')"
            }
            return
        }
        { $_ -in @("u16", "u32", "u64") } {
            if ($trimmed -notmatch '^\d+$') {
                throw "$Name value $Value must be a base-10 unsigned manifest integer"
            }
            $parsed = [uint64]0
            if (-not [uint64]::TryParse($trimmed, [ref]$parsed) -or $parsed.ToString() -ne $trimmed) {
                throw "$Name value $Value must be a canonical base-10 unsigned manifest integer"
            }
            if ($entry.value_kind -eq "u16" -and $parsed -gt [uint16]::MaxValue) {
                throw "$Name value $Value exceeds manifest u16 maximum"
            }
            if ($entry.value_kind -eq "u32" -and $parsed -gt [uint32]::MaxValue) {
                throw "$Name value $Value exceeds manifest u32 maximum"
            }
            Assert-NativeRendererManifestRange -Name $Name -Value ([double]$parsed) -Entry $entry
            return
        }
        "f32" {
            $parsed = Get-ManifestFiniteFloat -Name $Name -Value $Value
            Assert-NativeRendererManifestRange -Name $Name -Value $parsed -Entry $entry
            return
        }
        "f32_pair" {
            $parts = @($Value.Split(",") | ForEach-Object { $_.Trim() })
            if ($parts.Count -ne 2) {
                throw "$Name value $Value must contain two comma-separated manifest floats"
            }
            foreach ($part in $parts) {
                $parsed = Get-ManifestFiniteFloat -Name $Name -Value $part
                Assert-NativeRendererManifestRange -Name $Name -Value $parsed -Entry $entry
            }
            return
        }
        "string" {
            if ($entry.non_empty -eq $true -and [string]::IsNullOrWhiteSpace($Value)) {
                throw "$Name value must not be empty"
            }
            return
        }
        default {
            throw "$Name has unsupported manifest value_kind: $($entry.value_kind)"
        }
    }
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

function Get-EnvironmentDepthUInt {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    $parsed = [uint32]0
    if (-not [uint32]::TryParse($Value.Trim(), [ref]$parsed)) {
        throw "$Name value $Value must be an integer"
    }
    return $parsed
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

function Assert-StimulusVolumeUInt {
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

function Get-StimulusVolumeFloat {
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

function Assert-StimulusVolumeFloatRange {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value,
        [Parameter(Mandatory=$true)][double]$Min,
        [Parameter(Mandatory=$true)][double]$Max
    )
    $parsed = Get-StimulusVolumeFloat -Name $Name -Value $Value
    if ($parsed -lt $Min -or $parsed -gt $Max) {
        throw "$Name value $Value must be a finite number from $Min to $Max"
    }
}

function Get-NativeProjectionTargetFloat {
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

function Test-NormalizedTrue {
    param([Parameter(Mandatory=$true)][string]$Value)
    $normalized = Get-NormalizedProfileValue -Value $Value
    return @("1", "true", "yes", "on") -contains $normalized
}

function Assert-StimulusVolumeBool {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    $normalized = Get-NormalizedProfileValue -Value $Value
    if (@("0", "1", "true", "false", "yes", "no", "on", "off") -notcontains $normalized) {
        throw "$Name value $Value must be boolean"
    }
}

function Assert-NativeProjectionTargetProperty {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    $normalized = Get-NormalizedProfileValue -Value $Value
    switch -Exact ($Name) {
        $NativeProjectionTargetControlsProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetJoystickControlsProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathHighRateJsonPayloadProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            if (@("0", "false", "no", "off") -notcontains $normalized) {
                throw "Native projection target breath high_rate_json_payload must be false"
            }
            return
        }
        $NativeManifoldEmbeddedBrokerEnabledProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            return
        }
        $NativeManifoldEmbeddedBrokerLanEnabledProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            return
        }
        $NativeManifoldEmbeddedBrokerSessionTokenRequiredProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetScaleProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetTunedMaxScaleProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetMinScaleProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetMaxScaleProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetOffsetXUvProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetOffsetYUvProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetJoystickRateProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathInhaleSecondsProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathExhaleSecondsProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathSyntheticPeriodSecondsProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerAxisXProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerAxisYProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerAxisZProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerInhaleThresholdProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerExhaleThresholdProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerRotationGuardDegreesProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerMovingAverageGuardProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerShortWindowSamplesProperty {
            $parsed = 0
            if (-not [int]::TryParse($Value.Trim(), [ref]$parsed) -or $parsed -lt 1) {
                throw "$Name value $Value must be a positive sample count"
            }
            return
        }
        $NativeProjectionTargetBreathControllerLongWindowSamplesProperty {
            $parsed = 0
            if (-not [int]::TryParse($Value.Trim(), [ref]$parsed) -or $parsed -lt 1) {
                throw "$Name value $Value must be a positive sample count"
            }
            return
        }
        $NativeProjectionTargetBreathControllerShortWindowSecondsProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathControllerLongWindowSecondsProperty {
            $null = Get-NativeProjectionTargetFloat -Name $Name -Value $Value
            return
        }
        $NativeProjectionTargetBreathModeProperty {
            if (@("disabled", "off", "manifold-state", "pmb-state", "manifold-state-value", "pmb-state-value", "direct-controller-state", "native-controller-state", "local-controller-state", "fixed-controller-state", "synthetic") -notcontains $normalized) {
                throw "Native projection target breath bridge mode is not supported: $Value"
            }
            return
        }
        $NativeProjectionTargetBreathStateStreamProperty {
            if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name value must not be empty" }
            return
        }
        $NativeProjectionTargetBreathValueStreamProperty {
            if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name value must not be empty" }
            return
        }
        $NativeManifoldBrokerHostProperty {
            if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name value must not be empty" }
            return
        }
        $NativeManifoldBrokerPathProperty {
            if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name value must not be empty" }
            return
        }
        $NativeManifoldEmbeddedBrokerBindHostProperty {
            if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name value must not be empty" }
            return
        }
        $NativeManifoldEmbeddedBrokerPathProperty {
            if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name value must not be empty" }
            return
        }
        $NativeManifoldBrokerPortProperty {
            $parsed = 0
            if (-not [int]::TryParse($Value.Trim(), [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
                throw "$Name value $Value must be a TCP port"
            }
            return
        }
        $NativeManifoldEmbeddedBrokerPortProperty {
            $parsed = 0
            if (-not [int]::TryParse($Value.Trim(), [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
                throw "$Name value $Value must be a TCP port"
            }
            return
        }
        $NativeManifoldEmbeddedBrokerMaxFrameBytesProperty {
            $parsed = 0
            if (-not [int]::TryParse($Value.Trim(), [ref]$parsed) -or $parsed -lt 1024 -or $parsed -gt 1048576) {
                throw "$Name value $Value must be between 1024 and 1048576 bytes"
            }
            return
        }
        $NativeManifoldEmbeddedBrokerSessionTokenProperty {
            return
        }
        default {
            throw "Unknown native projection target property: $Name"
        }
    }
}

function Assert-EnvironmentDepthProperty {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    $normalized = Get-NormalizedProfileValue -Value $Value
    switch -Exact ($Name) {
        $EnvironmentDepthModeProperty {
            if (@("disabled", "off", "status", "status-only", "provider-status", "projection-sampler", "sampled-provider", "provider-sampler", "retained-particles", "retained-particle-map", "scene-particle-map", "scene-map") -notcontains $normalized) {
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
                "debug-surface-support",
                "normal-coherence",
                "coherence",
                "debug-normal-coherence",
                "support-count",
                "surface-support-count",
                "debug-support-count",
                "surface-residual",
                "residual",
                "debug-surface-residual"
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
        $EnvironmentDepthNativePassthroughRequiredProperty {
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
        $EnvironmentDepthAlignmentControlsProperty {
            Assert-EnvironmentDepthBool -Name $Name -Value $Value
            return
        }
        $EnvironmentDepthAlignmentJoystickControlsProperty {
            Assert-EnvironmentDepthBool -Name $Name -Value $Value
            return
        }
        $EnvironmentDepthAlignmentJoystickRateUvPerSecondProperty {
            $parsed = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            if ($parsed -lt 0.0 -or $parsed -gt 1.0) {
                throw "$Name value $Value must be from 0.0 to 1.0"
            }
            return
        }
        $EnvironmentDepthAlignmentMaxOffsetUvProperty {
            $parsed = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            if ($parsed -lt 0.0 -or $parsed -gt 1.0) {
                throw "$Name value $Value must be from 0.0 to 1.0"
            }
            return
        }
        $EnvironmentDepthAlignmentLeftOffsetXUvProperty {
            $parsed = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            if ($parsed -lt -1.0 -or $parsed -gt 1.0) {
                throw "$Name value $Value must be from -1.0 to 1.0"
            }
            return
        }
        $EnvironmentDepthAlignmentLeftOffsetYUvProperty {
            $parsed = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            if ($parsed -lt -1.0 -or $parsed -gt 1.0) {
                throw "$Name value $Value must be from -1.0 to 1.0"
            }
            return
        }
        $EnvironmentDepthAlignmentRightOffsetXUvProperty {
            $parsed = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            if ($parsed -lt -1.0 -or $parsed -gt 1.0) {
                throw "$Name value $Value must be from -1.0 to 1.0"
            }
            return
        }
        $EnvironmentDepthAlignmentRightOffsetYUvProperty {
            $parsed = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            if ($parsed -lt -1.0 -or $parsed -gt 1.0) {
                throw "$Name value $Value must be from -1.0 to 1.0"
            }
            return
        }
        $EnvironmentDepthAlignmentScaleProperty {
            $parsed = Get-EnvironmentDepthFloat -Name $Name -Value $Value
            if ($parsed -lt 0.25 -or $parsed -gt 4.0) {
                throw "$Name value $Value must be from 0.25 to 4.0"
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
        $EnvironmentDepthSurfaceSupportComponentModeProperty {
            if (@("off", "local-hint", "local", "hint", "local-neighborhood", "connected-labels", "connected", "labels", "connected-components") -notcontains $normalized) {
                throw "Environment depth surface_support.component_mode is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthSurfaceSupportNormalSourceProperty {
            if (@("off", "depth-neighborhood", "depth", "depth-view", "cell-neighborhood", "cell", "scene-cell", "retained-cell") -notcontains $normalized) {
                throw "Environment depth surface_support.normal_source is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthSurfaceSupportNormalCoherenceProperty {
            if (@("off", "loose", "low", "strict", "high") -notcontains $normalized) {
                throw "Environment depth surface_support.normal_coherence is not supported: $Value"
            }
            return
        }
        $EnvironmentDepthSurfaceSupportSmallComponentPolicyProperty {
            if (@("dim", "hide", "hidden", "debug-only", "debug", "diagnostic-only") -notcontains $normalized) {
                throw "Environment depth surface_support.small_component_policy is not supported: $Value"
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

function Assert-StimulusVolumeProperty {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Value
    )
    $normalized = Get-NormalizedProfileValue -Value $Value
    switch -Exact ($Name) {
        $StimulusVolumeEnabledProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            return
        }
        $StimulusVolumeProfileProperty {
            if (@("volume-only-bright-interference", "stimulus.profile.volume-only-bright-interference") -notcontains $normalized) {
                throw "Stimulus volume profile is not supported: $Value"
            }
            return
        }
        $StimulusVolumeCompositionProperty {
            if (@("opaque-black-projection", "alpha-over-native-passthrough") -notcontains $normalized) {
                throw "Stimulus volume composition is not supported: $Value"
            }
            return
        }
        $StimulusVolumePatternFamilyProperty {
            if (@(
                "randomized-trevor-vocabulary",
                "randomized",
                "random",
                "trevor-vocabulary",
                "trevor-mix",
                "mixed",
                "interference-mix",
                "stripes",
                "stripe",
                "ripples",
                "ripple",
                "rings",
                "rays",
                "ray",
                "radial-rays",
                "checker",
                "checkerboard",
                "checkers",
                "spiral",
                "spirals",
                "noise-field",
                "noise",
                "blobs"
            ) -notcontains $normalized) {
                throw "Stimulus volume pattern_family is not supported: $Value"
            }
            return
        }
        $StimulusVolumeRenderTargetProperty {
            if (@("512x512x2-rgba16f", "512x512x2-rgba8-unorm", "512x512x2-rgba8", "768x768x2-rgba16f", "768x768", "1024x1024x2-rgba16f", "1024x1024", "limit-1024") -notcontains $normalized) {
                throw "Stimulus volume render_target is not supported: $Value"
            }
            return
        }
        $StimulusVolumeRaymarchSamplesProperty {
            Assert-StimulusVolumeUInt -Name $Name -Value $Value -Min 1 -Max 48
            return
        }
        $StimulusVolumeCentralFovFractionProperty {
            Assert-StimulusVolumeFloatRange -Name $Name -Value $Value -Min 0.45 -Max 1.0
            return
        }
        $StimulusVolumeGradientSmoothingProperty {
            Assert-StimulusVolumeFloatRange -Name $Name -Value $Value -Min 0.0 -Max 1.0
            return
        }
        $StimulusVolumeRandomizeEnabledProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            return
        }
        $StimulusVolumeRandomizeMinHzProperty {
            $null = Get-StimulusVolumeFloat -Name $Name -Value $Value
            return
        }
        $StimulusVolumeRandomizeMaxHzProperty {
            $null = Get-StimulusVolumeFloat -Name $Name -Value $Value
            return
        }
        $StimulusVolumeSafetyAckProperty {
            Assert-StimulusVolumeBool -Name $Name -Value $Value
            return
        }
        default {
            throw "Unknown stimulus volume property: $Name"
        }
    }
}

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$nativeRendererPropertyManifestPath = Join-Path $RepoRoot $NativeRendererPropertyManifestRelativePath
$nativeRendererPropertyManifestByName = Import-NativeRendererPropertyManifest -Path $nativeRendererPropertyManifestPath
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
$stimulusVolumeProperties = @{}
$nativeProjectionTargetProfile = (Get-NormalizedProfileValue -Value ([string]$profile.profile_id)).Contains("breathing-room")
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
    if ($name.StartsWith($NativeRendererPropertyPrefix) -and -not $nativeRendererPropertyManifestByName.ContainsKey($name)) {
        throw "Owned native renderer property is missing from manifest: $name"
    }
    if ($owned.ContainsKey($name)) {
        throw "Duplicate owned Android property: $name"
    }
    if ($name.StartsWith($NativeProjectionTargetPropertyPrefix)) {
        $nativeProjectionTargetProfile = $true
    }
    $owned[$name] = $true
    $operations += [ordered]@{
        kind = "clear"
        name = $name
        value = " "
        source_setting_id = $null
    }
}

if ($nativeProjectionTargetProfile) {
    foreach ($name in $owned.Keys) {
        if ($name.StartsWith($MakepadPropertyPrefix)) {
            throw "Native projection target profile must not own Makepad property: $name"
        }
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
    Assert-NativeRendererManifestProperty -Name $propertyName -Value $propertyValue -ManifestByName $nativeRendererPropertyManifestByName
    if ($propertyName.StartsWith($EnvironmentDepthPropertyPrefix)) {
        Assert-EnvironmentDepthProperty -Name $propertyName -Value $propertyValue
        $environmentDepthProperties[$propertyName] = $propertyValue
    }
    if ($propertyName.StartsWith($StimulusVolumePropertyPrefix)) {
        Assert-StimulusVolumeProperty -Name $propertyName -Value $propertyValue
        $stimulusVolumeProperties[$propertyName] = $propertyValue
    }
    if ($propertyName.StartsWith($NativeProjectionTargetPropertyPrefix) -or $propertyName.StartsWith($NativeManifoldBrokerPropertyPrefix)) {
        Assert-NativeProjectionTargetProperty -Name $propertyName -Value $propertyValue
    }
    if ($nativeProjectionTargetProfile -and $propertyName.StartsWith($MakepadPropertyPrefix)) {
        throw "Native projection target profile must not set Makepad property: $propertyName"
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

if ($environmentDepthProperties.ContainsKey($EnvironmentDepthSurfaceSupportRadiusCellsProperty) -and $environmentDepthProperties.ContainsKey($EnvironmentDepthSurfaceSupportMinNeighborsProperty)) {
    $radiusCells = Get-EnvironmentDepthUInt -Name $EnvironmentDepthSurfaceSupportRadiusCellsProperty -Value $environmentDepthProperties[$EnvironmentDepthSurfaceSupportRadiusCellsProperty]
    $minNeighbors = Get-EnvironmentDepthUInt -Name $EnvironmentDepthSurfaceSupportMinNeighborsProperty -Value $environmentDepthProperties[$EnvironmentDepthSurfaceSupportMinNeighborsProperty]
    $diameter = ($radiusCells * 2) + 1
    $maxNeighbors = ($diameter * $diameter) - 1
    if ($minNeighbors -gt $maxNeighbors) {
        throw "Environment depth surface_support.min_neighbors $minNeighbors cannot exceed $maxNeighbors for radius_cells $radiusCells"
    }
}

if ($stimulusVolumeProperties.ContainsKey($StimulusVolumeRandomizeMinHzProperty) -and $stimulusVolumeProperties.ContainsKey($StimulusVolumeRandomizeMaxHzProperty)) {
    $minHz = Get-StimulusVolumeFloat -Name $StimulusVolumeRandomizeMinHzProperty -Value $stimulusVolumeProperties[$StimulusVolumeRandomizeMinHzProperty]
    $maxHz = Get-StimulusVolumeFloat -Name $StimulusVolumeRandomizeMaxHzProperty -Value $stimulusVolumeProperties[$StimulusVolumeRandomizeMaxHzProperty]
    if ($minHz -lt 3.0) {
        throw "Stimulus volume randomize min_hz must be greater than or equal to 3"
    }
    if ($maxHz -gt 40.0) {
        throw "Stimulus volume randomize max_hz must be less than or equal to 40"
    }
    if ($minHz -gt $maxHz) {
        throw "Stimulus volume randomize min_hz $minHz must be less than or equal to max_hz $maxHz"
    }
}

if ($stimulusVolumeProperties.ContainsKey($StimulusVolumeEnabledProperty) -and (Test-NormalizedTrue -Value $stimulusVolumeProperties[$StimulusVolumeEnabledProperty])) {
    if (-not $stimulusVolumeProperties.ContainsKey($StimulusVolumeSafetyAckProperty) -or -not (Test-NormalizedTrue -Value $stimulusVolumeProperties[$StimulusVolumeSafetyAckProperty])) {
        throw "Stimulus volume safety_ack must be true when stimulus_volume.enabled is true"
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
