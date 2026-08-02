[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion -lt [version]'7.6.0') {
  throw 'PowerShell 7.6 or newer is required.'
}

$appRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$registryPath = Join-Path $appRoot 'contracts\trusted_local_http_v1.commands.registry.json'
$mediaRegistryPath = Join-Path $appRoot 'contracts\bundled-videos.registry.json'

$registry = Get-Content -Raw -LiteralPath $registryPath | ConvertFrom-Json -Depth 20
$expectedCommands = @(
  'describe',
  'get_state',
  'list_videos',
  'select_video',
  'play',
  'pause'
) | Sort-Object
$actualCommands = @($registry.commands.id) | Sort-Object
if (($actualCommands -join ',') -ne ($expectedCommands -join ',')) {
  throw "Command registry is not the exact closed set: $($actualCommands -join ',')"
}
if ($registry.protocol -ne 'trusted_local_http_v1') {
  throw 'Command registry protocol mismatch.'
}
if ([int]$registry.payload_max_bytes -ne 4096) {
  throw 'Command payload bound changed.'
}

$mediaRegistryRaw = Get-Content -Raw -LiteralPath $mediaRegistryPath
if ($mediaRegistryRaw.Contains('PENDING_')) {
  throw 'Bundled media registry contains a pending placeholder.'
}
$mediaRegistry = $mediaRegistryRaw | ConvertFrom-Json -Depth 20
if (@($mediaRegistry.items).Count -ne 2 -or $mediaRegistry.license -ne 'CC0-1.0') {
  throw 'Expected exactly two CC0 synthetic media items.'
}
foreach ($item in $mediaRegistry.items) {
  if ([int]$item.width_px -lt 320 -or [int]$item.height_px -lt 180 -or
      [string]$item.generation_recipe -notmatch 'rate=30' -or
      [string]$item.generation_recipe -notmatch 'constrained baseline level 1\.3') {
    throw "Bundled media is outside the Quest hardware-decoder compatibility profile: $($item.video_id)"
  }
  $sourcePath = Join-Path $appRoot ([string]$item.source_blob -replace '/', '\')
  if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "Missing media source blob: $($item.source_blob)"
  }
  $decoded = [Convert]::FromBase64String((Get-Content -Raw -LiteralPath $sourcePath))
  $actualHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($decoded)).ToLowerInvariant()
  if ($actualHash -ne [string]$item.sha256_decoded) {
    throw "Decoded media hash mismatch for $($item.video_id): $actualHash"
  }
}

$webRoot = Join-Path $appRoot 'app\src\main\assets\control'
$index = Get-Content -Raw -LiteralPath (Join-Path $webRoot 'index.html')
$script = Get-Content -Raw -LiteralPath (Join-Path $webRoot 'app.js')
if ($index -notmatch '<script src="/app\.js" defer></script>') {
  throw 'Controller script must be a packaged same-origin asset.'
}
if ($index -match '(?i)https?://' -or $script -match '(?i)https?://') {
  throw 'Controller assets must not reference an external HTTP resource.'
}
if ($script -match '(?i)eval\s*\(|new\s+Function\s*\(') {
  throw 'Controller JavaScript must not evaluate runtime code.'
}

$runtimeSources =
  Get-ChildItem -Recurse -File -LiteralPath (Join-Path $appRoot 'host\src\main') |
    Where-Object Extension -in @('.java')
$assetAndRuntimeText =
  @($runtimeSources.FullName) +
  @(
    (Join-Path $webRoot 'index.html'),
    (Join-Path $webRoot 'app.js'),
    (Join-Path $webRoot 'styles.css')
  )
if (Select-String -LiteralPath $assetAndRuntimeText -Pattern 'Access-Control-Allow-Origin' -Quiet) {
  throw 'Permissive CORS surface detected.'
}
if (Select-String -LiteralPath $assetAndRuntimeText -Pattern 'Runtime\.exec|ProcessBuilder|Class\.forName|ACTION_VIEW|dalvik\.system|\badb\b' -Quiet) {
  throw 'Arbitrary execution/discovery surface detected.'
}

$manifest = Get-Content -Raw -LiteralPath (Join-Path $appRoot 'app\src\main\AndroidManifest.xml')
if ($manifest -notmatch 'android\.permission\.INTERNET') {
  throw 'Android adapter is missing its narrow listener permission.'
}
if ($manifest -match 'CAMERA|RECORD_AUDIO|BLUETOOTH|QUERY_ALL_PACKAGES|MANAGE_EXTERNAL_STORAGE') {
  throw 'Android example gained an unrelated permission.'
}
$gradle = Get-Content -Raw -LiteralPath (Join-Path $appRoot 'app\build.gradle.kts')
if ($gradle -notmatch 'TRUSTED_LOCAL_HTTP_ENABLED_DEFAULT", "false"') {
  throw 'Android listener default must remain disabled.'
}
if ($gradle -notmatch 'outputs\.upToDateWhen \{ false \}' -or
    $gradle -notmatch 'RUSTY_MANIFOLD_SOURCE_ROOT') {
  throw 'Native Gradle builds must always revalidate the exact external Manifold source.'
}

$versions = Get-Content -Raw -LiteralPath (Join-Path $appRoot 'gradle\libs.versions.toml')
if ($versions -notmatch 'kotlin = "2\.2\.0"') {
  throw 'The Spatial SDK example must use Kotlin 2.2.0.'
}
$activity = Get-Content -Raw -LiteralPath (
  Join-Path $appRoot 'app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\SpatialVideoControlActivity.kt'
)
if ($activity -notmatch 'com\.meta\.spatial\.runtime\.ButtonBits' -or
    $activity -notmatch 'com\.meta\.spatial\.toolkit\.Panel' -or
    $activity -notmatch 'NativeManifoldAuthorityPort\.createOrNull') {
  throw 'Spatial SDK imports or native Manifold injection regressed.'
}
if ($activity -notmatch 'staticControlPanelFrontRotation\(\): Quaternion = Quaternion\(0\.0f, 180\.0f, 0\.0f\)' -or
    $activity -notmatch 'Quaternion\.lookRotationAroundY\(direction\)' -or
    $activity -notmatch 'panelFacingConvention=meta-panel-front-look-rotation-around-y' -or
    $activity -notmatch 'source = "static-fallback"' -or
    $activity -notmatch 'source = "viewer-relative-recenter"') {
  throw 'The one-sided Spatial control panel lost its viewer-relative facing route or known-facing fallback.'
}
if ($activity -match 'R\.id\.local_control_panel,[\s\S]{0,240}Quaternion\(\)') {
  throw 'The one-sided Spatial control panel must not return to an identity quaternion.'
}
if ($activity -notmatch 'CONTROL_PANEL_DISTANCE_METERS = 1\.0f' -or
    $activity -notmatch 'GrabbableType\.PIVOT_Y' -or
    $activity -notmatch 'rightControllerAActionAuthority=panel-toggle-arbiter') {
  throw 'The comfortable grabbable panel or its right-A toggle authority regressed.'
}
$toggleSource = Get-Content -Raw -LiteralPath (
  Join-Path $appRoot 'app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\RightControllerPanelToggle.kt'
)
if ($toggleSource -notmatch 'CROSS_ROUTE_DEDUPLICATION_MS = 350L' -or
    $toggleSource -notmatch 'attachment == "right_controller"' -or
    $toggleSource -notmatch 'ButtonBits\.ButtonA') {
  throw 'The right-controller A edge arbiter or controller-side observation regressed.'
}
if ($activity -notmatch 'ButtonBits\.ButtonTriggerL' -or
    $activity -notmatch 'ButtonBits\.ButtonTriggerR' -or
    $activity -match 'ButtonBits\.ButtonA') {
  throw 'Panel clicks must use triggers only; right A is reserved for toggle/recenter.'
}

$lockPath = Join-Path $appRoot 'native\manifold-source.lock.json'
$nativeLock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
$nativeManifest = Get-Content -Raw -LiteralPath (Join-Path $appRoot 'native\Cargo.toml')
if ($nativeLock.repository -ne 'https://github.com/MesmerPrism/rusty-manifold.git' -or
    [string]$nativeLock.revision -notmatch '^[0-9a-f]{40}$' -or
    [string]$nativeLock.tree -notmatch '^[0-9a-f]{40}$') {
  throw 'Native Manifold lock is incomplete or non-canonical.'
}
if (($nativeManifest | Select-String -Pattern ([regex]::Escape([string]$nativeLock.revision)) -AllMatches).Matches.Count -ne 4) {
  throw 'Every direct Manifold crate must use the exact locked Git revision.'
}
$nativeSource = Get-Content -Raw -LiteralPath (Join-Path $appRoot 'native\src\lib.rs')
$expectedNativeOperations = @(
  'nativeInitialize',
  'nativeOpenPairingWindow',
  'nativeAdmitController',
  'nativeAcceptCommand',
  'nativeDisable',
  'nativeEnforceExpiry',
  'nativeSafeStatus'
)
foreach ($operation in $expectedNativeOperations) {
  if ($nativeSource -notmatch [regex]::Escape($operation)) {
    throw "Missing typed native operation: $operation"
  }
}
$nativeRuntimeSource = $nativeSource.Split('#[cfg(test)]', 2)[0]
if ($nativeRuntimeSource -match '(?i)nativeExecute|raw[_ ]shell|process::Command') {
  throw 'Generic or arbitrary native execution surface detected.'
}
$nativeBridge = Get-Content -Raw -LiteralPath (
  Join-Path $appRoot 'app\src\main\java\io\github\mesmerprism\rustyquest\spatial_video_control\NativeManifoldAuthorityPort.kt'
)
if ($nativeBridge -notmatch 'PackageManager\.GET_SIGNING_CERTIFICATES' -or
    $nativeBridge -notmatch 'Locale\.ROOT' -or
    $nativeBridge -notmatch 'MessageDigest\.isEqual') {
  throw 'Native adapter identity or pairing verification is not fail-closed.'
}

$javac = Get-Command javac -ErrorAction Stop
$java = Get-Command java -ErrorAction Stop
$testRoot =
  Join-Path ([IO.Path]::GetTempPath()) ('rusty-quest-trusted-local-http-' + [guid]::NewGuid().ToString('N'))
$resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
if (-not $resolvedTestRoot.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Refusing to create the test output outside the system temp directory.'
}

try {
  $classes = New-Item -ItemType Directory -Force -Path (Join-Path $testRoot 'classes')
  $javaSources =
    @(
      Get-ChildItem -Recurse -File -Filter '*.java' -LiteralPath (Join-Path $appRoot 'host\src\main\java')
      Get-ChildItem -Recurse -File -Filter '*.java' -LiteralPath (Join-Path $appRoot 'host\src\test\java')
    ).FullName
  & $javac.Source -encoding UTF-8 -d $classes.FullName $javaSources
  if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
  }
  & $java.Source -cp $classes.FullName io.github.mesmerprism.rustyquest.spatial_video_control.TrustedLocalControlHostTest $appRoot
  if ($LASTEXITCODE -ne 0) {
    throw "host tests failed with exit code $LASTEXITCODE"
  }
}
finally {
  if (Test-Path -LiteralPath $resolvedTestRoot) {
    Remove-Item -Recurse -Force -LiteralPath $resolvedTestRoot
  }
}

Write-Output 'trusted_local_http_v1 source gate passed'
