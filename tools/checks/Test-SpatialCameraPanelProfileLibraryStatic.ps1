param([string]$RepoRoot)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
}
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
$sourceRoot = Join-Path $root 'apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel'
$panel = [System.IO.File]::ReadAllText((Join-Path $sourceRoot 'PrivateLayerControlPanel.kt'))
$help = [System.IO.File]::ReadAllText((Join-Path $sourceRoot 'PrivateLayerControlHelp.kt'))
$profiles = [System.IO.File]::ReadAllText((Join-Path $sourceRoot 'SpatialCameraPanelStoredProfiles.kt'))
$registration = [System.IO.File]::ReadAllText((Join-Path $sourceRoot 'SpatialComposePanelRegistrationModule.kt'))
$activity = [System.IO.File]::ReadAllText((Join-Path $sourceRoot 'SpatialCameraPanelActivity.kt'))
$toolPath = Join-Path $root 'tools\Invoke-SpatialCameraPanelProfileTransfer.ps1'
$tool = [System.IO.File]::ReadAllText($toolPath)

function Assert-Contains {
    param([string]$Text, [string]$Needle, [string]$Message)
    if (-not $Text.Contains($Needle)) { throw $Message }
}

Assert-Contains $panel 'PersistentPanelHeader(' 'The Spatial Camera Panel is missing its persistent header.'
Assert-Contains $panel 'PrivateLayerPanelPage.entries.filterNot' 'The fixed header does not expose every non-home page.'
Assert-Contains $panel 'PrivateLayerPanelPage.Profiles' 'The permanent navigation does not include Profiles.'
Assert-Contains $panel 'HelpLabel(label)' 'Slider labels are not connected to contextual help.'
Assert-Contains $panel 'Import staged bundle' 'The Profiles page does not expose staged PC import.'
Assert-Contains $help 'requiredGroupLabels' 'The contextual-help coverage catalog is missing.'
Assert-Contains $help 'staticCompositionLocalOf' 'Contextual help is not routed through the shared compact header strip.'

$headerIndex = $panel.IndexOf('PersistentPanelHeader(')
$scrollIndex = $panel.IndexOf('.verticalScroll(rememberScrollState())')
if ($headerIndex -lt 0 -or $scrollIndex -lt 0 -or $headerIndex -gt $scrollIndex) {
    throw 'The page navigation header must be composed outside and before the scrolling page body.'
}

Assert-Contains $profiles 'rusty.quest.spatial_camera_panel.profile_bundle.v1' 'Stored profiles use the wrong bundle schema.'
Assert-Contains $profiles 'AtomicFile' 'Stored profile persistence is not atomic.'
Assert-Contains $profiles 'MAX_PAYLOAD_BYTES = 1_048_576' 'Stored profile bundle size is not bounded.'
Assert-Contains $profiles 'MAX_PROFILES = 128' 'Stored profile count is not bounded.'
Assert-Contains $profiles 'videoPresentationMode' 'Stored profiles omit video presentation mode.'
Assert-Contains $profiles 'projectionInnerAlpha' 'Stored profiles omit inner transparency.'
Assert-Contains $registration 'saveStoredProfile' 'Compose registration does not expose profile save.'
Assert-Contains $registration 'loadStoredProfile' 'Compose registration does not expose profile load.'
Assert-Contains $activity 'mediaSelectionRetained=true' 'Profile application does not explicitly retain media selection.'

Assert-Contains $tool '/sdcard/Android/data/$Package/files/profile-library' 'The PC tool is not using release-compatible app external files.'
Assert-Contains $tool '-s $Serial' 'The PC tool is not serial-scoped.'
if ($tool.Contains('run-as')) { throw 'The Spatial Camera Panel PC transfer tool must not depend on debug-only run-as.' }

$temporaryBundle = Join-Path ([System.IO.Path]::GetTempPath()) "spatial-camera-panel-profile-static-$([guid]::NewGuid().ToString('N')).json"
try {
    $emptyBundle = [ordered]@{
        schema = 'rusty.quest.spatial_camera_panel.profile_bundle.v1'
        format_version = 1
        profile_count = 0
        profiles = @()
    } | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($temporaryBundle, "$emptyBundle`n", [System.Text.UTF8Encoding]::new($false))
    $validated = & $toolPath -Action Validate -BundlePath $temporaryBundle
    if ($validated.Status -ne 'host-envelope-valid' -or $validated.ProfileCount -ne 0) {
        throw 'The PC transfer tool did not validate the canonical empty bundle.'
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryBundle -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryBundle -Force
    }
}

Write-Host 'Spatial Camera Panel profile-library static checks passed.'
