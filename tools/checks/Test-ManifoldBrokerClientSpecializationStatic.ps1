param([string]$RepoRoot = ".")

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$buildPath = Join-Path $repo "tools\Build-ManifoldBrokerAndroid.ps1"
$staticPath = Join-Path $repo "tools\checks\Test-ManifoldBrokerCompatibilityDiagnosticStatic.ps1"
$devicePath = Join-Path $repo "tools\Invoke-ManifoldBrokerCompatibilityDiagnostic.ps1"
$clientPath = Join-Path $repo "fixtures\broker-clients\spatial-camera-panel.client.json"
$lifecyclePath = Join-Path $repo "fixtures\broker-clients\spatial-camera-panel.media-lifecycle.json"
$bindingPaths = @(
    (Join-Path $repo "fixtures\media-runtime-products\camera2-surface.binding.json"),
    (Join-Path $repo "fixtures\media-runtime-products\spatial-camera-panel-display.binding.json"))
foreach ($path in @($buildPath, $staticPath, $devicePath, $clientPath, $lifecyclePath) +
        $bindingPaths) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Supplier specialization source is missing: $path"
    }
}

$build = Get-Content -Raw -LiteralPath $buildPath
function Require-Build([string]$Pattern, [string]$Message) {
    if ($build -cnotmatch $Pattern) { throw $Message }
}
foreach ($requirement in @(
    @('\$SpatialCameraPanelPackageName\s*=\s*""',
      "Build script omits the optional Spatial package parameter."),
    @('New-SpecializedSpatialCameraPanelClientInput',
      "Build script omits the isolated client-lock specialization."),
    @('Spatial Camera Panel package specialization requires the legacy compatibility product, remote-camera debug operator, and shared signer gate\.',
      "Specialization is not closed over product, debug-provider, and signer gates."),
    @('Legacy compatibility package builds require an explicit clean -ManifoldSourceRoot\.',
      "Legacy package builds do not require an explicit Manifold source root."),
    @('status --porcelain --untracked-files=all',
      "Explicit Manifold source is not checked for tracked cleanliness."),
    @('camera2-surface\.binding\.json',
      "Camera2 media binding is not fixed in the supplier closure."),
    @('spatial-camera-panel-display\.binding\.json',
      "Spatial display media binding is not fixed in the supplier closure."),
    @('spatial_camera_panel_package_specialized',
      "Build manifest omits specialization status."),
    @('spatial_camera_panel_client_lock_sha256',
      "Build manifest omits the specialized client-lock digest."),
    @('spatial_camera_panel_media_lifecycle_sha256',
      "Build manifest omits the specialized lifecycle digest."),
    @('inspected-same-version-replace-with-installed-byte-readback',
      "Build manifest omits the fixed inspected-install policy."),
    @('same-version-same-signer-rollback-reinstall',
      "Build manifest omits the fixed rollback policy."))) {
    Require-Build $requirement[0] $requirement[1]
}

$client = Get-Content -Raw -LiteralPath $clientPath | ConvertFrom-Json
$lifecycle = Get-Content -Raw -LiteralPath $lifecyclePath | ConvertFrom-Json
if ([string]$client.schema -cne "rusty.quest.broker_client_spec.v1" -or
    [string]$client.client_id -cne "client.quest.spatial-camera-panel" -or
    [string]$client.package_name -cne
        "io.github.mesmerprism.rustyquest.spatial_camera_panel" -or
    [string]$lifecycle.client_id -cne [string]$client.client_id -or
    [string]$lifecycle.package_name -cne [string]$client.package_name -or
    [string]$lifecycle.media_binding_path -cne
        "fixtures/media-runtime-products/spatial-camera-panel-display.binding.json") {
    throw "Spatial Camera Panel baseline client/lifecycle fixtures drifted."
}
foreach ($bindingPath in $bindingPaths) {
    $binding = Get-Content -Raw -LiteralPath $bindingPath | ConvertFrom-Json
    if ([string]$binding.manifold.'$schema' -cne
            "rusty.manifold.media.session_product_binding.v1" -or
        [string]$binding.quest.'$schema' -cne
            "rusty.quest.media_stream_runtime_product_binding.v1" -or
        [string]$binding.manifold.descriptor.payload_plane -cne "binary-media" -or
        $binding.manifold.descriptor.inline_media_payloads_allowed -isnot [bool] -or
        $binding.manifold.descriptor.inline_media_payloads_allowed) {
        throw "Supplier media binding is not an exact low-rate/binary-media boundary: $bindingPath"
    }
}

foreach ($scriptPath in @($buildPath, $staticPath, $devicePath)) {
    $tokens = $null
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile(
        $scriptPath, [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) {
        throw "PowerShell syntax is invalid: $scriptPath"
    }
}

$deviceSource = Get-Content -Raw -LiteralPath $devicePath
foreach ($token in @(
    'StaticGateReceipt', 'FeatureLockSha256', 'FeatureLockRevision',
    'FeatureLockFingerprint', 'FileManagerSha256', 'AdbSha256',
    'apk", "inspect"', 'apk", "install"', 'apk", "observe"',
    'authority-status', 'exact_prior_broker_bytes_restored')) {
    if ($deviceSource -cnotmatch [regex]::Escape($token)) {
        throw "Compatibility diagnostic omits required token: $token"
    }
}
$tokens = $null
$errors = $null
$deviceAst = [Management.Automation.Language.Parser]::ParseFile(
    $devicePath, [ref]$tokens, [ref]$errors)
$forbidden = '(?i)(\buninstall\b|\bpm\s+clear\b|\blogcat\b[^\r\n]*\s-c\b|--downgrade\b|\s-d\b|\bkill-server\b|\bstart-server\b|\breconnect\b|\btcpip\b|\bdisconnect\b)'
$unsafe = @($deviceAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
        ($node.Extent.Text -match $forbidden -or
         ($node.Extent.Text -match '(?i)\bforce-stop\b' -and
          $node.Extent.Text -cnotmatch '\$ExpectedPackageName'))
}, $true))
if ($unsafe.Count -ne 0) {
    throw "Compatibility diagnostic contains a prohibited device operation."
}

& pwsh -NoProfile -ExecutionPolicy Bypass -File (
    Join-Path $repo "tools\checks\Test-ManifoldBrokerSharedSignerGate.ps1") -RepoRoot $repo
if ($LASTEXITCODE -ne 0) {
    throw "Shared signer child gate failed with exit code $LASTEXITCODE."
}
Write-Host "Manifold broker supplier client specialization static gate: PASS"
