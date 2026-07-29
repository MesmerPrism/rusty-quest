[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$Profile = 'release',
    [string]$OutputRoot = ''
)

$ErrorActionPreference = 'Stop'
$appRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $appRoot 'native'
$lockPath = Join-Path $nativeRoot 'manifold-source.lock.json'
$lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json

$manifoldRoot = $env:RUSTY_MANIFOLD_SOURCE_ROOT
if ([string]::IsNullOrWhiteSpace($manifoldRoot)) {
    throw 'RUSTY_MANIFOLD_SOURCE_ROOT is required while the pinned no-push Manifold commit is unpublished.'
}
$manifoldRoot = (Resolve-Path -LiteralPath $manifoldRoot).Path
$head = (& git -C $manifoldRoot rev-parse HEAD).Trim()
$tree = (& git -C $manifoldRoot rev-parse 'HEAD^{tree}').Trim()
$dirty = @(& git -C $manifoldRoot status --porcelain)
if ($LASTEXITCODE -ne 0 -or $head -ne $lock.revision -or $tree -ne $lock.tree -or $dirty.Count -ne 0) {
    throw "Manifold source does not match the clean locked revision/tree: $($lock.revision) / $($lock.tree)"
}

$ndkRoot = $env:ANDROID_NDK_ROOT
if ([string]::IsNullOrWhiteSpace($ndkRoot)) {
    throw 'ANDROID_NDK_ROOT is required.'
}
$ndkRoot = (Resolve-Path -LiteralPath $ndkRoot).Path
$prebuilt = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$linker = Join-Path $prebuilt 'aarch64-linux-android34-clang.cmd'
if (-not (Test-Path -LiteralPath $linker -PathType Leaf)) {
    throw "Android NDK API-34 linker not found: $linker"
}

$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $linker
$env:CC_AARCH64_LINUX_ANDROID = $linker
$env:AR_AARCH64_LINUX_ANDROID = Join-Path $prebuilt 'llvm-ar.exe'
$env:CARGO_NET_GIT_FETCH_WITH_CLI = 'true'
$env:GIT_CONFIG_COUNT = '1'
$env:GIT_CONFIG_KEY_0 = "url.file:///$($manifoldRoot.Replace('\', '/'))/.insteadOf"
$env:GIT_CONFIG_VALUE_0 = [string]$lock.repository

$cargoArgs = @(
    'build',
    '--locked',
    '--manifest-path', (Join-Path $nativeRoot 'Cargo.toml'),
    '--target', 'aarch64-linux-android'
)
if ($Profile -eq 'release') {
    $cargoArgs += '--release'
}
& cargo @cargoArgs
if ($LASTEXITCODE -ne 0) {
    throw "cargo build failed with exit code $LASTEXITCODE"
}

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $appRoot 'app\build\generated\native-jniLibs'
}
$abiRoot = Join-Path $OutputRoot 'arm64-v8a'
New-Item -ItemType Directory -Force -Path $abiRoot | Out-Null
$sourceProfile = if ($Profile -eq 'release') { 'release' } else { 'debug' }
$library = Join-Path $nativeRoot "target\aarch64-linux-android\$sourceProfile\librusty_quest_spatial_video_local_control.so"
if (-not (Test-Path -LiteralPath $library -PathType Leaf)) {
    throw "Expected native library not produced: $library"
}
Copy-Item -LiteralPath $library -Destination (Join-Path $abiRoot 'librusty_quest_spatial_video_local_control.so') -Force

Write-Output "native_local_control_revision=$head"
Write-Output "native_local_control_tree=$tree"
Write-Output "native_local_control_library=$(Join-Path $abiRoot 'librusty_quest_spatial_video_local_control.so')"
