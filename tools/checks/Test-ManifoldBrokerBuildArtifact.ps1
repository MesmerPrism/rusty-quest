param(
    [string]$RepoRoot = ".",
    [Parameter(Mandatory = $true)][string]$BuildDir,
    [string]$ExpectedProductName = "base-standalone"
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$buildCandidate = if ([System.IO.Path]::IsPathRooted($BuildDir)) { $BuildDir } else { Join-Path $repo $BuildDir }
$buildRoot = (Resolve-Path -LiteralPath $buildCandidate).Path
$manifoldFixtures = (Resolve-Path -LiteralPath (Join-Path $repo "..\rusty-manifold\fixtures\broker-product")).Path
$androidNamespace = "http://schemas.android.com/apk/res/android"

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Label)
    if ([string]$Actual -cne [string]$Expected) {
        throw "$Label mismatch: actual='$Actual' expected='$Expected'"
    }
}

function Assert-ExactStrings {
    param([object[]]$Actual, [object[]]$Expected, [string]$Label)
    $actualStrings = @($Actual | ForEach-Object { [string]$_ })
    $expectedStrings = @($Expected | ForEach-Object { [string]$_ })
    if (($actualStrings -join "|") -cne ($expectedStrings -join "|")) {
        throw "$Label mismatch: actual=[$($actualStrings -join ', ')] expected=[$($expectedStrings -join ', ')]"
    }
}

function Get-ComponentProjection {
    param([Parameter(Mandatory = $true)][xml]$Manifest)
    $components = @()
    foreach ($kind in @("activity", "service")) {
        foreach ($node in @($Manifest.manifest.application.$kind)) {
            $component = [ordered]@{
                kind = $kind
                name = [string]$node.GetAttribute("name", $androidNamespace)
                exported = [System.Convert]::ToBoolean($node.GetAttribute("exported", $androidNamespace))
            }
            $permission = [string]$node.GetAttribute("permission", $androidNamespace)
            if (-not [string]::IsNullOrWhiteSpace($permission)) { $component["permission"] = $permission }
            $foregroundServiceType = [string]$node.GetAttribute("foregroundServiceType", $androidNamespace)
            if (-not [string]::IsNullOrWhiteSpace($foregroundServiceType)) { $component["foreground_service_type"] = $foregroundServiceType }
            $components += [pscustomobject]$component
        }
    }
    return @($components)
}

function Get-ZipEntrySha256Hex {
    param(
        [Parameter(Mandatory = $true)][System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)][string]$EntryName
    )
    $entry = $Archive.GetEntry($EntryName)
    if ($null -eq $entry) { throw "APK is missing required asset '$EntryName'." }
    $stream = $entry.Open()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash($stream)
        return -join ($bytes | ForEach-Object { $_.ToString("x2") })
    } finally {
        $sha.Dispose()
        $stream.Dispose()
    }
}

$paths = [ordered]@{
    receipt = Join-Path $buildRoot "build-manifest.json"
    apk = Join-Path $buildRoot "rusty-manifold-broker.apk"
    runtime = Join-Path $buildRoot "broker-runtime-config.json"
    product_receipt = Join-Path $buildRoot "product-inputs\product-package-inputs.json"
    spec = Join-Path $buildRoot "product-inputs\product-spec.json"
    lock = Join-Path $buildRoot "product-inputs\accepted-product-lock.json"
    projection = Join-Path $buildRoot "product-inputs\manifest-projection.json"
    android_manifest = Join-Path $buildRoot "product-inputs\AndroidManifest.xml"
    registry = Join-Path $buildRoot "product-inputs\command-registry.json"
}
foreach ($path in $paths.Values) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Build artifact is missing: $path" }
}

$receipt = Get-Content -Raw -LiteralPath $paths.receipt | ConvertFrom-Json
$productReceipt = Get-Content -Raw -LiteralPath $paths.product_receipt | ConvertFrom-Json
$lock = Get-Content -Raw -LiteralPath $paths.lock | ConvertFrom-Json
$projection = Get-Content -Raw -LiteralPath $paths.projection | ConvertFrom-Json
[xml]$generatedManifest = Get-Content -Raw -LiteralPath $paths.android_manifest

Assert-Equal $receipt.'$schema' "rusty.quest.manifold_broker_android.build_manifest.v2" "build receipt schema"
Assert-Equal $receipt.manifold_product_id $productReceipt.product_id "product id"
Assert-Equal $receipt.manifold_product_lock_id $productReceipt.manifold_lock_id "product lock id"
Assert-Equal $receipt.manifold_product_lock_fingerprint $productReceipt.manifold_lock_fingerprint "product lock fingerprint"
Assert-Equal $receipt.manifold_product_lock_sha256 (Get-Sha256Hex $paths.lock) "product lock SHA-256"
Assert-Equal $receipt.manifold_product_spec_sha256 (Get-Sha256Hex $paths.spec) "product spec SHA-256"
Assert-Equal $receipt.generated_android_manifest_sha256 (Get-Sha256Hex $paths.android_manifest) "generated manifest SHA-256"
Assert-Equal $receipt.generated_manifest_projection_sha256 (Get-Sha256Hex $paths.projection) "manifest projection SHA-256"
Assert-Equal $receipt.generated_command_registry_sha256 (Get-Sha256Hex $paths.registry) "command registry SHA-256"
Assert-Equal $receipt.broker_runtime_config_sha256 (Get-Sha256Hex $paths.runtime) "runtime config SHA-256"
Assert-Equal $receipt.product_inputs_receipt_sha256 (Get-Sha256Hex $paths.product_receipt) "product-input receipt SHA-256"
Assert-Equal $receipt.apk_sha256 (Get-Sha256Hex $paths.apk) "APK SHA-256"
Assert-ExactStrings @($receipt.manifold_product_features) @($lock.features) "product features"
Assert-ExactStrings @($receipt.manifold_product_modules) @($lock.module_ids) "product modules"
Assert-ExactStrings @($receipt.manifold_product_permissions) @($lock.permissions) "product permissions"
Assert-ExactStrings @($receipt.android_permissions) @($projection.permissions | ForEach-Object { [string]$_.name } | Sort-Object -Unique) "Android permissions"

$expectedComponents = @(Get-ComponentProjection -Manifest $generatedManifest | ForEach-Object { $_ | ConvertTo-Json -Compress })
$receiptComponents = @($receipt.android_components | ForEach-Object { $_ | ConvertTo-Json -Compress })
Assert-ExactStrings $receiptComponents $expectedComponents "Android components"
foreach ($flag in @("product_spec_lock_validated", "generated_manifest_validated", "runtime_config_validated", "apk_signature_verified", "packaged_assets_validated")) {
    if (-not [bool]$receipt.validation.$flag) { throw "Build receipt validation flag '$flag' is not true." }
}
if ([bool]$receipt.legacy_camera_p2p_compatibility -or [bool]$receipt.live_stream_events_synthesized) {
    throw "The default product receipt claims legacy compatibility or synthesized live events."
}

$expectedSpec = Join-Path $manifoldFixtures "$ExpectedProductName.json"
$expectedLock = Join-Path $manifoldFixtures "$ExpectedProductName.lock.json"
$expectedManifest = Join-Path $repo "fixtures\broker-products\$ExpectedProductName.AndroidManifest.xml"
Assert-Equal (Get-Sha256Hex $paths.spec) (Get-Sha256Hex $expectedSpec) "exact product spec fixture"
Assert-Equal (Get-Sha256Hex $paths.lock) (Get-Sha256Hex $expectedLock) "exact product lock fixture"
Assert-Equal (Get-Sha256Hex $paths.android_manifest) (Get-Sha256Hex $expectedManifest) "exact generated manifest fixture"

Push-Location $repo
try {
    $digestOutput = @(& cargo run --quiet -p rusty-quest-broker-authority --bin runtime_config_digest -- $paths.runtime 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Runtime config authority validation failed: $($digestOutput -join [Environment]::NewLine)" }
} finally {
    Pop-Location
}
$canonicalDigest = @($digestOutput | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -match '^[0-9a-f]{64}$' }) | Select-Object -Last 1
Assert-Equal $receipt.broker_runtime_config_canonical_sha256 $canonicalDigest "canonical runtime config SHA-256"

$buildTools = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $buildTools) { throw "Android build-tools are unavailable." }
$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
& $apksigner verify --verbose $paths.apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed." }
$permissionOutput = @(& $aapt2 dump permissions $paths.apk 2>&1)
if ($LASTEXITCODE -ne 0) { throw "aapt2 permission inspection failed." }
$apkPermissions = @($permissionOutput | ForEach-Object {
    if ([string]$_ -match "^uses-permission: name='([^']+)'$") { $Matches[1] }
} | Where-Object { $_ } | Sort-Object -Unique)
Assert-ExactStrings $apkPermissions @($projection.permissions | ForEach-Object { [string]$_.name } | Sort-Object -Unique) "compiled APK permissions"
$xmlTree = @(& $aapt2 dump xmltree --file AndroidManifest.xml $paths.apk 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw "aapt2 manifest inspection failed." }
foreach ($required in @(".BrokerStartActivity", ".BrokerStartService", ".ManifoldAdmissionService", "BROKER_ADMISSION")) {
    if ($xmlTree -notmatch [regex]::Escape($required)) { throw "Compiled APK manifest is missing '$required'." }
}
foreach ($forbidden in @("permission.CAMERA", "HEADSET_CAMERA", "SPATIAL_CAMERA", "NEARBY_WIFI_DEVICES", "BLUETOOTH_")) {
    if ($xmlTree -match [regex]::Escape($forbidden)) { throw "Compiled APK manifest contains forbidden '$forbidden'." }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($paths.apk)
try {
    foreach ($binding in @(
        @{ entry = "assets/manifold/accepted-product-lock.json"; file = $paths.lock },
        @{ entry = "assets/manifold/command-registry.json"; file = $paths.registry },
        @{ entry = "assets/manifold/manifest-projection.json"; file = $paths.projection },
        @{ entry = "assets/manifold/runtime-config.json"; file = $paths.runtime })) {
        Assert-Equal (Get-ZipEntrySha256Hex -Archive $archive -EntryName $binding.entry) (Get-Sha256Hex $binding.file) "packaged asset $($binding.entry)"
    }
} finally {
    $archive.Dispose()
}

Write-Host "Manifold broker build artifact gate passed: product=$($receipt.manifold_product_id) apk_sha256=$($receipt.apk_sha256)"
