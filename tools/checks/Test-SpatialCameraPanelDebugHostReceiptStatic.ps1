param(
    [Parameter(Mandatory = $true)][string]$RepoRoot
)

$ErrorActionPreference = 'Stop'
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing debug host receipt file: $RelativePath" }
    Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param([string]$Label, [string]$Text, [string]$Needle)
    if (-not $Text.Contains($Needle)) { throw "$Label is missing required token: $Needle" }
}

function Assert-NotContains {
    param([string]$Label, [string]$Text, [string]$Needle)
    if ($Text.Contains($Needle)) { throw "$Label contains forbidden token: $Needle" }
}

$authority = 'io.github.mesmerprism.rustyquest.spatial_camera_panel.debug-host-receipt'
$debugManifestPath = 'apps/spatial-camera-panel-android/app/src/debug/AndroidManifest.xml'
$contractPath = 'apps/spatial-camera-panel-android/app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/DebugHostReceiptContract.kt'
$storePath = 'apps/spatial-camera-panel-android/app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/DebugHostReceiptStore.kt'
$providerPath = 'apps/spatial-camera-panel-android/app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/DebugHostReceiptProvider.kt'
$runtimePath = 'apps/spatial-camera-panel-android/app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/DebugHostReceiptRuntime.kt'

$debugManifest = Read-RequiredText $debugManifestPath
$contract = Read-RequiredText $contractPath
$store = Read-RequiredText $storePath
$provider = Read-RequiredText $providerPath
$runtime = Read-RequiredText $runtimePath
$mainManifest = Read-RequiredText 'apps/spatial-camera-panel-android/app/src/main/AndroidManifest.xml'

Assert-Contains $debugManifestPath $debugManifest 'DebugHostReceiptProvider'
Assert-Contains $debugManifestPath $debugManifest $authority
Assert-Contains $debugManifestPath $debugManifest 'android.permission.DUMP'
Assert-Contains $debugManifestPath $debugManifest 'android:exported="true"'
Assert-Contains $debugManifestPath $debugManifest 'android:grantUriPermissions="false"'
Assert-NotContains 'main AndroidManifest.xml' $mainManifest 'DebugHostReceiptProvider'
Assert-NotContains 'main AndroidManifest.xml' $mainManifest $authority

foreach ($token in @('METHOD_ARM', 'METHOD_STATUS', 'METHOD_READ', 'METHOD_CLEANUP', '^[0-9a-f]{64}$', 'MAX_RECEIPT_BYTES = 64 * 1024')) {
    Assert-Contains $contractPath $contract $token
}
foreach ($fact in @('"source"', '"grant"', '"decoder"', '"max-count"', '"decoded-geometry"', '"prepared"', '"advancing-frame"', '"cadence"', '"render-adoption"', '"error"', '"terminal"')) {
    Assert-Contains $contractPath $contract $fact
}
foreach ($token in @('Binder.getCallingUid()', 'Process.SHELL_UID', 'override fun query', 'override fun insert', 'override fun update', 'override fun delete', 'override fun openFile', 'override fun openAssetFile', 'override fun openTypedAssetFile')) {
    Assert-Contains $providerPath $provider $token
}
foreach ($token in @('writeAtomically', 'StandardCopyOption.ATOMIC_MOVE', 'stream.fd.sync()', 'StateKind.CONSUMED', 'debug_host_receipt_replay_rejected', 'debug_host_receipt_size_exceeded', 'debug_host_receipt_privacy_rejected')) {
    Assert-Contains $storePath $store $token
}
foreach ($token in @('FileInputStream(context.applicationInfo.sourceDir)', 'MessageDigest.getInstance("SHA-256")', 'finalizeTerminalReceipt')) {
    Assert-Contains $runtimePath $runtime $token
}
foreach ($forbidden in @('startActivity', 'sendBroadcast', 'adb ', 'content://', 'Uri.parse', 'Intent(', 'ComponentName', 'Log.')) {
    Assert-NotContains $providerPath $provider $forbidden
}

$releaseRoot = Join-Path $repoRootPath 'apps/spatial-camera-panel-android/app/src/release'
if (Test-Path -LiteralPath $releaseRoot) {
    Get-ChildItem -LiteralPath $releaseRoot -Recurse -File | ForEach-Object {
        $text = Get-Content -Raw -LiteralPath $_.FullName
        Assert-NotContains $_.FullName $text 'DebugHostReceiptProvider'
        Assert-NotContains $_.FullName $text $authority
    }
}

Write-Host 'Debug host receipt static gate passed'
