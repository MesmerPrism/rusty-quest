param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererInputSelection.psm1') -Force

$name = 'RUSTY_QUEST_NATIVE_RENDERER_CONTROLLER_ONLY'
$permissions = @('org.khronos.openxr.permission.OPENXR','com.oculus.permission.HAND_TRACKING')
$usesFeatures = @('android.hardware.vr.headtracking','oculus.software.handtracking')
$normal = Get-NativeRendererInputSelection -FeatureIds @('input.controllers_and_hands_optional') -BuildEnvByName @{} -Permissions $permissions -UsesFeatures $usesFeatures
if ($normal.controller_only -or $normal.permissions -notcontains 'com.oculus.permission.HAND_TRACKING' -or $normal.uses_features -notcontains 'oculus.software.handtracking') {
    throw 'Default input selection changed.'
}
$selected = Get-NativeRendererInputSelection -FeatureIds @('input.controllers_and_hands_optional','input.controllers_only') -BuildEnvByName @{ $name = 'true' } -Permissions $permissions -UsesFeatures $usesFeatures
if (-not $selected.controller_only -or $selected.permissions -contains 'com.oculus.permission.HAND_TRACKING' -or $selected.uses_features -contains 'oculus.software.handtracking') {
    throw 'Controller-only selection retained hand tracking.'
}
if ($selected.permissions -notcontains 'org.khronos.openxr.permission.OPENXR' -or $selected.uses_features -notcontains 'android.hardware.vr.headtracking') {
    throw 'Controller-only input removed unrelated manifest declarations.'
}
foreach ($case in @(
    @{ ids=@('input.controllers_only'); env=@{} },
    @{ ids=@(); env=@{ $name='true' } },
    @{ ids=@('input.controllers_only'); env=@{ $name='false' } },
    @{ ids=@('input.controllers_only','input.tracked_hand_pointer'); env=@{ $name='true' } },
    @{ ids=@('input.controllers_only','input.simultaneous_hands_and_controllers'); env=@{ $name='true' } }
)) {
    $rejected = $false
    try {
        Get-NativeRendererInputSelection -FeatureIds $case.ids -BuildEnvByName $case.env -Permissions $permissions -UsesFeatures $usesFeatures | Out-Null
    } catch { $rejected = $true }
    if (-not $rejected) { throw 'An unbound or conflicting input selection was admitted.' }
}
Write-Output 'PASS controller-only input selection, manifest isolation and default compatibility.'
