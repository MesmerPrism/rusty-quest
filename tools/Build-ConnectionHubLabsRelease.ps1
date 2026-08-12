param(
    [Parameter(Mandatory=$true)]
    [ValidateRange(1, 2100000000)]
    [int]$VersionCode,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$')]
    [string]$VersionName,
    [Parameter(Mandatory=$true)]
    [string]$Keystore,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$ExpectedSignerSha256,
    [Parameter(Mandatory=$true)]
    [string]$ManifoldSourceRoot,
    [Parameter(Mandatory=$true)]
    [string]$OutDir,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$keystorePath = (Resolve-Path -LiteralPath $Keystore).Path
$manifoldRoot = (Resolve-Path -LiteralPath $ManifoldSourceRoot).Path
$out = [System.IO.Path]::GetFullPath($OutDir)

function Invoke-Checked([string]$Name, [string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE." }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Json([string]$Path, $Value) {
    [System.IO.File]::WriteAllText(
        $Path,
        ($Value | ConvertTo-Json -Depth 20),
        [System.Text.UTF8Encoding]::new($false))
}

$dirty = @(& git -C $repoRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) {
    throw "Connection Hub Labs release requires a clean exact Rusty Quest worktree."
}
$manifoldDirty = @(& git -C $manifoldRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $manifoldDirty.Count -ne 0) {
    throw "Connection Hub Labs release requires the exact clean pinned Manifold source."
}
if (Test-Path -LiteralPath $out) {
    if (-not (Test-Path -LiteralPath $out -PathType Container) -or
            @(Get-ChildItem -LiteralPath $out -Force).Count -ne 0) {
        throw "Release output directory must be absent or empty."
    }
} else {
    New-Item -ItemType Directory -Path $out | Out-Null
}

$sourceRevision = (& git -C $repoRoot rev-parse HEAD).Trim()
$sourceTree = (& git -C $repoRoot rev-parse 'HEAD^{tree}').Trim()
$manifoldRevision = (& git -C $manifoldRoot rev-parse HEAD).Trim()
$manifoldTree = (& git -C $manifoldRoot rev-parse 'HEAD^{tree}').Trim()
$spec = Join-Path $manifoldRoot "fixtures\broker-product\connection-hub-standalone.json"
$lock = Join-Path $manifoldRoot "fixtures\broker-product\connection-hub-standalone.lock.json"
foreach ($required in @($keystorePath, $spec, $lock)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required release input is missing: $required" }
}

$keytool = if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    (Get-Command keytool -ErrorAction Stop).Source
} else {
    Join-Path $JavaHome "bin\keytool.exe"
}
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) {
    throw "Java keytool is unavailable for the pre-build signer check: $keytool"
}
$signerProbe = Join-Path $out "keystore-signer-preflight.der"
try {
    & $keytool -exportcert `
        -keystore $keystorePath `
        -storepass android `
        -alias androiddebugkey `
        -file $signerProbe
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $signerProbe -PathType Leaf)) {
        throw "Could not export the Connection Hub signing certificate before build."
    }
    $actualSignerSha256 = Get-Sha256 $signerProbe
    if ($actualSignerSha256 -cne $ExpectedSignerSha256) {
        throw "Keystore signer mismatch before build: expected $ExpectedSignerSha256, got $actualSignerSha256. Use the stable accepted Hub keystore so the APK can update the installed app."
    }
} finally {
    if (Test-Path -LiteralPath $signerProbe -PathType Leaf) {
        Remove-Item -LiteralPath $signerProbe -Force
    }
}

$buildOut = Join-Path $out "build"
& (Join-Path $repoRoot "tools\Build-ManifoldBrokerAndroid.ps1") `
    -AndroidHome $AndroidHome `
    -JavaHome $JavaHome `
    -OutDir $buildOut `
    -ProductSpecPath $spec `
    -ProductLockPath $lock `
    -ManifoldSourceRoot $manifoldRoot `
    -Keystore $keystorePath `
    -VersionCode $VersionCode `
    -VersionName $VersionName
if ($LASTEXITCODE -ne 0) { throw "Connection Hub release build failed." }

$buildManifestPath = Join-Path $buildOut "build-manifest.json"
$buildManifest = Get-Content -Raw -LiteralPath $buildManifestPath | ConvertFrom-Json
if ([string]$buildManifest.package_name -cne "io.github.mesmerprism.rustymanifold.broker" -or
        [int]$buildManifest.version_code -ne $VersionCode -or
        [string]$buildManifest.version_name -cne $VersionName -or
        $buildManifest.connection_hub_debug_operator -ne $false -or
        [string]$buildManifest.admission_client_signing_certificate_sha256 -cne $ExpectedSignerSha256) {
    throw "Built Connection Hub artifact does not match the fixed release identity."
}

$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidHome "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if ($null -eq $buildTools) { throw "Android build tools are unavailable." }
$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$builtApk = (Resolve-Path -LiteralPath ([string]$buildManifest.apk_path)).Path
$badging = @(& $aapt2 dump badging $builtApk 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Pinned aapt2 rejected the Connection Hub release APK." }
$manifestTree = @(& $aapt2 dump xmltree $builtApk --file AndroidManifest.xml 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Pinned aapt2 could not inspect the release manifest." }
$badgingText = $badging -join "`n"
$manifestText = $manifestTree -join "`n"
if ($badgingText -notmatch "package: name='io\.github\.mesmerprism\.rustymanifold\.broker' versionCode='$VersionCode' versionName='$([regex]::Escape($VersionName))'" -or
        $manifestText -match 'ConnectionHubDebugControlProvider|android\.permission\.DUMP') {
    throw "Independent APK inspection rejected the release package/version/debug boundary."
}

$artifactName = "rusty-connection-hub-$VersionName.apk"
$artifactPath = Join-Path $out $artifactName
Copy-Item -LiteralPath $builtApk -Destination $artifactPath
$artifactSha256 = Get-Sha256 $artifactPath
if ($artifactSha256 -cne [string]$buildManifest.apk_sha256) { throw "Release artifact copy digest drifted." }

$releaseManifest = [ordered]@{
    '$schema' = "rusty.quest.connection_hub_labs_release.v1"
    product = "Rusty Connection Hub"
    release_tag = "connection-hub-v$VersionName"
    channel = "labs"
    maturity = "alpha"
    package_name = "io.github.mesmerprism.rustymanifold.broker"
    version_code = $VersionCode
    version_name = $VersionName
    source_revision = $sourceRevision
    source_tree = $sourceTree
    source_url = "https://github.com/MesmerPrism/rusty-quest/tree/$sourceRevision/apps/manifold-broker-android"
    manifold_source_revision = $manifoldRevision
    manifold_source_tree = $manifoldTree
    signer_sha256 = $ExpectedSignerSha256
    artifact_name = $artifactName
    artifact_sha256 = $artifactSha256
    artifact_size = (Get-Item -LiteralPath $artifactPath).Length
    build_manifest_sha256 = Get-Sha256 $buildManifestPath
    release_manifest_debug_operator_absent = $true
    listener_default = "stopped"
    transport_classification = "trusted_lan_experimental"
    confidentiality = "none"
    production_eligible = $false
    insecure_trusted_lan_requires_explicit_opt_in = $true
    arbitrary_remote_commands = $false
    high_rate_media_data_plane = $false
}
$releaseManifestPath = Join-Path $out "connection-hub-release-manifest.json"
Write-Json $releaseManifestPath $releaseManifest
Write-Output ($releaseManifest | ConvertTo-Json -Depth 20 -Compress)
