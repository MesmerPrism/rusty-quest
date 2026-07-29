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
