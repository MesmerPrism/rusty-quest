# Controller-only packaging narrows the legacy optional-hands input substrate.
# Both resolver and builder bind that policy to the same explicit feature.
function Get-NativeRendererInputSelection {
    [CmdletBinding()]
    param(
        [string[]]$FeatureIds = @(),
        [Parameter(Mandatory)][System.Collections.IDictionary]$BuildEnvByName,
        [string[]]$Permissions = @(),
        [string[]]$UsesFeatures = @()
    )
    $featureId = 'input.controllers_only'
    $envName = 'RUSTY_QUEST_NATIVE_RENDERER_CONTROLLER_ONLY'
    $selected = $FeatureIds -ccontains $featureId
    $declared = $BuildEnvByName.Contains($envName)
    if ($selected) {
        if (-not $declared) {
            throw 'Controller-only input requires its exact descriptor build value.'
        }
        $entry = $BuildEnvByName[$envName]
        $value = if ($entry -is [string]) { $entry } else { $entry.value }
        if ($value -cne 'true') {
            throw 'Controller-only input requires its exact descriptor build value.'
        }
        $conflicts = @($FeatureIds | Where-Object {
            $_ -cin @('input.tracked_hand_pointer','input.simultaneous_hands_and_controllers','hand_mesh_live_input','hand_mesh_visual','hand_anchor_particles')
        })
        if ($conflicts.Count -gt 0) { throw "Controller-only input conflicts with $($conflicts -join ', ')." }
        $Permissions = @($Permissions | Where-Object { $_ -cne 'com.oculus.permission.HAND_TRACKING' })
        $UsesFeatures = @($UsesFeatures | Where-Object { $_ -cne 'oculus.software.handtracking' })
    } elseif ($declared -or -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($envName,'Process'))) {
        throw 'Controller-only input requires its explicitly selected feature.'
    }
    [pscustomobject]@{ controller_only=$selected; permissions=@($Permissions); uses_features=@($UsesFeatures) }
}
Export-ModuleMember -Function Get-NativeRendererInputSelection
