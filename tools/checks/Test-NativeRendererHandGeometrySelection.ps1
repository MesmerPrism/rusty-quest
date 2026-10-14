[CmdletBinding()]
param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererHandGeometrySelection.psm1') -Force
$selected = [pscustomobject]@{ selected_feature_ids = @('renderer.native_scene','renderer.native_scene.particles_only','input.tracked_hand_pointer') }
$disabled = @{ RUSTY_QUEST_NATIVE_RENDERER_HAND_GEOMETRY_ENABLED = 'false' }
$result = Get-NativeRendererHandGeometrySelection -FeatureLock $selected -BuildEnvByName $disabled
if ($result.hand_geometry_enabled -or -not $result.particles_only_selected) { throw 'Selected particles-only scene did not exclude geometry.' }
$legacy = Get-NativeRendererHandGeometrySelection -FeatureLock ([pscustomobject]@{selected_feature_ids=@('hand_mesh_visual')}) -BuildEnvByName @{}
if (-not $legacy.hand_geometry_enabled -or $legacy.particles_only_selected) { throw 'Unchanged legacy scene lost hand geometry.' }
$cases = @(
    @{ label='unselected-exclusion'; args=@{FeatureLock=$null;BuildEnvByName=$disabled} },
    @{ label='missing-exclusion'; args=@{FeatureLock=$selected;BuildEnvByName=@{}} },
    @{ label='wrong-exclusion'; args=@{FeatureLock=$selected;BuildEnvByName=@{RUSTY_QUEST_NATIVE_RENDERER_HAND_GEOMETRY_ENABLED='true'}} },
    @{ label='missing-scene'; args=@{FeatureLock=([pscustomobject]@{selected_feature_ids=@('renderer.native_scene.particles_only')});BuildEnvByName=$disabled} },
    @{ label='mesh-conflict'; args=@{FeatureLock=([pscustomobject]@{selected_feature_ids=@('renderer.native_scene','renderer.native_scene.particles_only','hand_mesh_visual')});BuildEnvByName=$disabled} },
    @{ label='prepared-rig'; args=@{FeatureLock=$selected;BuildEnvByName=$disabled;PreparedHandRigSource='unopened-private-rig.json'} },
    @{ label='orphan-rig-digest'; args=@{FeatureLock=$selected;BuildEnvByName=$disabled;ExpectedPreparedHandRigSourceSha256=('a'*64)} },
    @{ label='raw-capture'; args=@{FeatureLock=$selected;BuildEnvByName=$disabled;RecordedHandCaptureDir='unopened-capture'} },
    @{ label='capture-requirement'; args=@{FeatureLock=$selected;BuildEnvByName=$disabled;RequireRecordedHandCapture=$true} }
)
foreach ($case in $cases) {
    $rejected = $false
    try { $arguments = $case.args; $null = Get-NativeRendererHandGeometrySelection @arguments }
    catch { $rejected = $true }
    if (-not $rejected) { throw "Admission accepted $($case.label)." }
}
$envName = 'RUSTY_QUEST_NATIVE_RENDERER_HAND_GEOMETRY_ENABLED'
$previousAmbient = [Environment]::GetEnvironmentVariable($envName, 'Process')
try {
    [Environment]::SetEnvironmentVariable($envName, 'false', 'Process')
    $rejected = $false
    try { $null = Get-NativeRendererHandGeometrySelection -FeatureLock $null -BuildEnvByName @{} }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Unlocked development accepted ambient hand-geometry exclusion.' }
    $selectedResult = Get-NativeRendererHandGeometrySelection -FeatureLock $selected -BuildEnvByName $disabled
    if ($selectedResult.hand_geometry_enabled) { throw 'Admitted exact selected geometry unexpectedly enabled.' }
} finally {
    [Environment]::SetEnvironmentVariable($envName, $previousAmbient, 'Process')
}
Write-Output 'PASS particles-only selection, unchanged legacy geometry and ten rejected input/selection cases, including unlocked ambient exclusion; no compiler or input-file access.'
