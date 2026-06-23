param(
    [Parameter(Mandatory=$true)]
    [string]$SourceRoot,
    [string]$OutRoot = "",
    [string]$AndroidNdkHome = $env:ANDROID_NDK_HOME,
    [string]$CMake = "cmake",
    [string]$Ninja = "ninja",
    [string]$Abi = "arm64-v8a",
    [string]$AndroidPlatform = "android-29",
    [string]$ExpectedTag = "v1.17.7",
    [string]$ExpectedCommit = "64988c6a14b8dc3b3f270ece58eab4f480bfab43"
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$File,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = ""
    )
    if ([string]::IsNullOrWhiteSpace($WorkingDirectory)) {
        & $File @Arguments
    } else {
        & $File @Arguments 2>&1 | ForEach-Object { $_ }
    }
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

if ([string]::IsNullOrWhiteSpace($AndroidNdkHome)) {
    throw "ANDROID_NDK_HOME or -AndroidNdkHome is required."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$sourceRootPath = Resolve-Path -LiteralPath $SourceRoot
if ([string]::IsNullOrWhiteSpace($OutRoot)) {
    $OutRoot = Join-Path $repoRoot "local-artifacts\liblsl-android"
}
$outRootPath = [System.IO.Path]::GetFullPath($OutRoot)
$buildRoot = Join-Path $outRootPath "build\$Abi"
$stageDir = Join-Path $outRootPath $Abi
$toolchain = Join-Path $AndroidNdkHome "build\cmake\android.toolchain.cmake"
if (-not (Test-Path -LiteralPath $toolchain)) {
    throw "Android NDK CMake toolchain file not found: $toolchain"
}

$head = (& git -C $sourceRootPath rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "git rev-parse failed for liblsl source root: $sourceRootPath"
}
if ($head -ne $ExpectedCommit) {
    throw "liblsl source HEAD mismatch. Expected $ExpectedCommit ($ExpectedTag), found $head at $sourceRootPath"
}
$describe = (& git -C $sourceRootPath describe --tags --always).Trim()
if ($LASTEXITCODE -ne 0) {
    $describe = $head
}

New-Item -ItemType Directory -Force -Path $buildRoot, $stageDir | Out-Null

Push-Location $buildRoot
try {
    & $CMake @(
        "-G", "Ninja",
        "-S", $sourceRootPath,
        "-B", $buildRoot,
        "-DCMAKE_MAKE_PROGRAM=$Ninja",
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
        "-DANDROID_ABI=$Abi",
        "-DANDROID_PLATFORM=$AndroidPlatform",
        "-DANDROID_STL=c++_static",
        "-DLSL_UNITTESTS=OFF",
        "-DLSL_TOOLS=OFF",
        "-DLSL_INSTALL=OFF",
        "-DLSL_FETCH_PUGIXML=ON"
    )
    if ($LASTEXITCODE -ne 0) {
        throw "CMake configure failed with exit code $LASTEXITCODE"
    }
    & $CMake --build $buildRoot --config Release --target lsl
    if ($LASTEXITCODE -ne 0) {
        throw "CMake build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$builtLib = Get-ChildItem -LiteralPath $buildRoot -Recurse -Filter "liblsl.so" |
    Sort-Object FullName |
    Select-Object -First 1
if ($null -eq $builtLib) {
    throw "CMake build did not produce liblsl.so under $buildRoot"
}
$stagedLib = Join-Path $stageDir "liblsl.so"
Copy-Item -LiteralPath $builtLib.FullName -Destination $stagedLib -Force
$sha256 = Get-FileSha256 -Path $stagedLib

$provenance = [ordered]@{
    schema = "rusty.quest.liblsl_android_provenance.v1"
    library = "liblsl"
    expected_tag = $ExpectedTag
    expected_commit = $ExpectedCommit
    source_root = [string]$sourceRootPath
    source_head = $head
    source_describe = $describe
    abi = $Abi
    android_platform = $AndroidPlatform
    android_ndk_home = [string](Resolve-Path -LiteralPath $AndroidNdkHome)
    cmake = $CMake
    ninja = $Ninja
    staged_library = $stagedLib
    staged_library_sha256 = $sha256
}
$provenancePath = Join-Path $outRootPath "liblsl-android-provenance.json"
$provenance | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -Path $provenancePath

Write-Output $stagedLib
