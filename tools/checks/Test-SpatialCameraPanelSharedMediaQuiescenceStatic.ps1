param([string]$RepoRoot)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing shared-media quiescence file: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param([string]$Label, [string]$Text, [string]$Needle)
    if (-not $Text.Contains($Needle)) { throw "$Label is missing required token: $Needle" }
}

function Assert-NotContains {
    param([string]$Label, [string]$Text, [string]$Needle)
    if ($Text.Contains($Needle)) { throw "$Label contains forbidden token: $Needle" }
}

$panel = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerControlPanel.kt"
$client = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SharedMediaLibrarySnapshotClient.kt"
$library = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SharedOfflineImmersiveMediaLibrary.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"

Assert-NotContains "Panel recurring loop" $panel "val latestSharedMediaLibrary = sharedMediaLibrary()"
Assert-Contains "Panel event subscription" $panel "observeSharedMediaLibrary"
Assert-Contains "Panel explicit refresh" $panel '"Refresh media library"'
Assert-Contains "Media client" $client "HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND)"
Assert-Contains "Media client" $client "if (!refreshInFlight.compareAndSet(false, true))"
Assert-Contains "Media client" $client "SharedOfflineImmersiveMediaLibrary.snapshot(appContext)"
Assert-Contains "Media client" $client "worker is created lazily only after an explicit refresh or folder adoption"
Assert-Contains "Library local status" $library "fun statusWithoutScan"
Assert-NotContains "Activity panel binding" $activity "SharedOfflineImmersiveMediaLibrary.snapshot(this)"
Assert-Contains "Activity panel binding" $activity "sharedMediaLibraryClient::refresh"
Assert-Contains "Activity cleanup" $activity "sharedMediaLibraryClient.close()"

Write-Output "Spatial Camera Panel shared-media quiescence static checks passed."
